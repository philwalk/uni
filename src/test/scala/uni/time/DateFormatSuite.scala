package uni.time

import munit.FunSuite
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `DateFormat.format` must agree with `DateTimeFormatter.ofPattern` for every pattern
 * `uni` uses, so decoupling the date type from `java.time` is invisible to scripts.
 *
 * The comparison pins the English locale explicitly: `ofPattern` uses the JVM's default
 * FORMAT locale, and `DateFormat` is always English on purpose -- a library that writes
 * data files should not emit `mai` on one machine and `May` on another.
 */
class DateFormatSuite extends FunSuite:

  /** Every pattern appearing in the repo, plus the wider set scripts may use. */
  private val patterns = Seq(
    "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd",
    "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd", "yyyy", "dd-MM-yyyy",
    "yy-M-d", "y", "yyy",
    "M", "MM", "MMM", "MMMM",
    "d", "dd", "H", "HH", "h", "hh", "m", "mm", "s", "ss",
    "a", "E", "EEE", "EEEE",
    "HH:mm", "HH:mm:ss.SSS", "S", "SS", "SSS",
    "yyyyMMdd", "yyyyMMddHHmmss",
    "'at' HH:mm 'on' yyyy-MM-dd", "''", "d''d",
    "dd/MM/yyyy hh:mm:ss a", "EEE, dd MMM yyyy HH:mm:ss",

  )

  private val moments = Seq(
    LocalDateTime.of(2024, 5, 12, 14, 30, 45, 123_456_789),
    LocalDateTime.of(2024, 1, 1, 0, 0, 0, 0),            // midnight, single-digit fields
    LocalDateTime.of(2024, 12, 31, 23, 59, 59, 999_000_000),
    LocalDateTime.of(2024, 2, 29, 12, 0, 0, 0),          // leap day, noon
    LocalDateTime.of(1999, 9, 9, 9, 9, 9, 0),
    LocalDateTime.of(2000, 10, 10, 10, 10, 10, 500_000_000),
    LocalDateTime.of(1900, 1, 2, 3, 4, 5, 0),            // BadDate's fields
    LocalDateTime.of(2005, 7, 4, 13, 5, 0, 0),           // PM single-digit minute
    // Week-based-year boundaries, where capital Y and lowercase y diverge.
    LocalDateTime.of(2024, 12, 29, 0, 0, 0, 0),
    LocalDateTime.of(2024, 12, 30, 0, 0, 0, 0),
    LocalDateTime.of(2024, 12, 31, 23, 0, 0, 0),
    LocalDateTime.of(2025, 1, 1, 0, 0, 0, 0),
    LocalDateTime.of(2019, 12, 30, 0, 0, 0, 0),
    LocalDateTime.of(2021, 1, 3, 0, 0, 0, 0),
    LocalDateTime.of(2027, 1, 1, 0, 0, 0, 0),
    LocalDateTime.of(2032, 12, 31, 0, 0, 0, 0),
  )

  private def ours(d: LocalDateTime, fmt: String): String =
    DateFormat.format(d.getYear, d.getMonthValue, d.getDayOfMonth,
      d.getHour, d.getMinute, d.getSecond, d.getNano, fmt)

  private def javas(d: LocalDateTime, fmt: String): String =
    d.format(DateTimeFormatter.ofPattern(fmt, Locale.ENGLISH))

  test("every pattern agrees with DateTimeFormatter at every moment") {
    val diffs = for
      fmt <- patterns
      d   <- moments
      mine = ours(d, fmt)
      java = javas(d, fmt)
      if mine != java
    yield s"[$fmt] at $d: ours=[$mine] java=[$java]"
    assertEquals(diffs, Seq.empty[String], s"${diffs.size} disagreement(s)")
  }

  test("the patterns the repo actually uses, spelled out") {
    // Named so a regression is legible rather than one row of a matrix.
    val d = LocalDateTime.of(2024, 5, 12, 14, 30, 45, 0)
    assertEquals(ours(d, "yyyy/MM/dd HH:mm:ss"), "2024/05/12 14:30:45")
    assertEquals(ours(d, "yyyy-MM-dd'T'HH:mm:ss"), "2024-05-12T14:30:45")
    assertEquals(ours(d, "yyyy-MM-dd"), "2024-05-12")
    assertEquals(ours(d, "dd-MM-yyyy"), "12-05-2024")
    assertEquals(ours(d, "yyyy"), "2024")
  }

  test("day of week is computed, not looked up") {
    // Sakamoto's method, needed for `E` and later for `weekDay`.
    for d <- moments do
      assertEquals(DateFormat.dayOfWeek(d.getYear, d.getMonthValue, d.getDayOfMonth),
        d.getDayOfWeek.getValue, s"for $d")
    // A century boundary and a leap-century, where the /100 and /400 terms matter.
    assertEquals(DateFormat.dayOfWeek(1900, 3, 1), 4) // Thursday
    assertEquals(DateFormat.dayOfWeek(2000, 2, 29), 2) // Tuesday
  }

  test("an unsupported pattern letter is rejected, not guessed at") {
    // Java reserves every ASCII letter; silently emitting it would be worse.
    intercept[IllegalArgumentException] {
      DateFormat.format(2024, 5, 12, 0, 0, 0, 0, "yyyy-QQ")
    }
    intercept[IllegalArgumentException] {
      DateFormat.format(2024, 5, 12, 0, 0, 0, 0, "yyyy 'unclosed")
    }
  }

  test("capital Y is refused, with the fix named") {
    // Not implemented and not aliased to `y`. `ofPattern` accepts it and renders a
    // different year near New Year -- and follows the *locale's* week rules while doing
    // so, so under Locale.ENGLISH 2024-12-29 becomes week-year 2025. Every occurrence
    // found across 166 client scripts was a typo for `y`, so erroring names the fix.
    val e = intercept[IllegalArgumentException] {
      DateFormat.format(2024, 12, 30, 0, 0, 0, 0, "YYYY-MM-dd")
    }
    assert(e.getMessage.contains("use lowercase [y]"), e.getMessage)
  }

  test("the week-year divergence this avoids is real") {
    // Recorded so the decision above is legible: java.time really does render a
    // different year here, which is what a typo silently buys you.
    val d = java.time.LocalDateTime.of(2024, 12, 30, 0, 0)
    assertEquals(d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)), "2024-12-30")
    assertEquals(d.format(DateTimeFormatter.ofPattern("YYYY-MM-dd", Locale.ENGLISH)), "2025-12-30")
  }

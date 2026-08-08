package uni.apps

import uni.*
import uni.time.*

/**
 * Regenerates the cross-language parity fixture in `test-data/date-parity/` for
 * `uni.time.UniDateTime` / `uni.time.DateFormat` and their Rust port (`rust/src/utime/`).
 *
 * Consumed by two tests that must agree with each other:
 *   - uni.time.DateParitySuite     (Scala, src/test)
 *   - rust/tests/date_parity.rs    (Rust)
 *
 * Neither needs the other language installed, because both compare against the committed
 * reference rather than against each other.
 *
 * # Why this fixture carries more weight than the CSV or hash ones
 *
 * The Scala arithmetic delegates to `java.time`. The Rust port cannot -- there is no
 * `java.time` and deliberately no date crate -- so it writes out Hinnant's civil-calendar
 * algorithms, month-end clamping and time-shift carry by hand. Those are precisely the
 * places hand-rolled calendar code goes wrong, and this fixture is what makes `java.time`
 * the transitive oracle for them.
 *
 * That also means the cases are chosen adversarially rather than representatively: leap
 * days, century leap years, month ends, both sides of the epoch, and the boundaries where
 * a shift carries into the next unit.
 *
 * Nothing here is platform-dependent -- the type consults no clock, locale or filesystem,
 * and month names are English by construction -- so one reference serves every host.
 *
 * Run ONLY when the reference is meant to move: regenerating rewrites the very values the
 * tests check, so an unintended run masks a regression instead of catching it. Review the
 * diff before keeping it.
 *
 * Run:  sbt "runMain uni.apps.DateParityGen"
 */
object DateParityGen:
  def println(s: String = ""): Unit = print(s"$s\n")

  /** Fields as a tab-joined key, so a fixture row names the input unambiguously. */
  private def key(d: UniDateTime): String =
    f"${d.year}%d\t${d.month}%d\t${d.day}%d\t${d.hour}%d\t${d.minute}%d\t${d.second}%d\t${d.nano}%d"

  /** Moments on the boundaries hand-rolled calendar arithmetic gets wrong. */
  val moments: Seq[UniDateTime] = Seq(
    UniDateTime.of(2024, 5, 12, 14, 30, 45, 123456789), // all fields set
    UniDateTime.of(2024, 5, 12, 14, 30, 0),             // zero seconds: toString omits them
    UniDateTime.of(2024, 1, 31),                        // month end, the clamping source
    UniDateTime.of(2024, 2, 29, 23, 59, 59),            // leap day, end of day
    UniDateTime.of(2023, 2, 28, 12, 0, 0),              // non-leap February
    UniDateTime.of(2024, 12, 31, 23, 59, 59, 999999999),// year end, max fraction
    UniDateTime.of(2000, 2, 29, 6, 0, 0),               // century leap year
    UniDateTime.of(1900, 3, 1, 0, 0, 1),                // century non-leap, just after
    UniDateTime.of(1969, 12, 31, 23, 59, 59),           // day before the epoch
    UniDateTime.of(1970, 1, 1),                         // the epoch
    UniDateTime.of(1900, 1, 2, 3, 4, 5),                // what BadDate used to be
    UniDateTime.of(2024, 3, 10, 2, 30, 0),              // a US DST transition, which must NOT matter
  )

  /** Amounts including zero, both signs, and enough to cross several units. */
  val amounts: Seq[Long] = Seq(0L, 1L, -1L, 7L, -7L, 13L, 31L, -31L, 400L, -400L)

  /** Every pattern letter, each width, quoting, and the shapes uni's own scripts use. */
  val patterns: Seq[String] = Seq(
    "yyyy-MM-dd", "yyyyMMdd", "yy", "y", "yyyy",
    "M", "MM", "MMM", "MMMM",
    "d", "dd",
    "H", "HH", "h", "hh", "a",
    "m", "mm", "s", "ss",
    "S", "SS", "SSS", "SSSSSS", "SSSSSSSSS",
    "E", "EE", "EEE", "EEEE",
    "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "MMM d, yyyy",
    "''yyyy", "'literal'", "h:mm a", "EEE MMM d HH:mm:ss yyyy",
  )

  /** Named single-argument operations, so a fixture row reads as the call it pins. */
  val unaryOps: Seq[(String, UniDateTime => UniDateTime)] = Seq(
    "atStartOfDay"   -> (_.atStartOfDay()),
    "lastDayOfMonth" -> (_.lastDayOfMonth),
  )

  val shiftOps: Seq[(String, (UniDateTime, Long) => UniDateTime)] = Seq(
    "plusNanos"    -> (_.plusNanos(_)),
    "plusSeconds"  -> (_.plusSeconds(_)),
    "plusMinutes"  -> (_.plusMinutes(_)),
    "plusHours"    -> (_.plusHours(_)),
    "plusDays"     -> (_.plusDays(_)),
    "plusWeeks"    -> (_.plusWeeks(_)),
    "plusMonths"   -> (_.plusMonths(_)),
    "plusYears"    -> (_.plusYears(_)),
    "minusNanos"   -> (_.minusNanos(_)),
    "minusSeconds" -> (_.minusSeconds(_)),
    "minusMinutes" -> (_.minusMinutes(_)),
    "minusHours"   -> (_.minusHours(_)),
    "minusDays"    -> (_.minusDays(_)),
    "minusWeeks"   -> (_.minusWeeks(_)),
    "minusMonths"  -> (_.minusMonths(_)),
    "minusYears"   -> (_.minusYears(_)),
  )

  val withOps: Seq[(String, (UniDateTime, Int) => UniDateTime)] = Seq(
    "withYear"       -> (_.withYear(_)),
    "withMonth"      -> (_.withMonth(_)),
    "withDayOfMonth" -> (_.withDayOfMonth(_)),
    "withHour"       -> (_.withHour(_)),
    "withMinute"     -> (_.withMinute(_)),
    "withSecond"     -> (_.withSecond(_)),
    "withNano"       -> (_.withNano(_)),
  )

  /** Values chosen to include the out-of-range cases, which must yield `BadDate` rather
   *  than throwing -- the contract that distinguishes this type from `java.time`. */
  val withArgs: Seq[Int] = Seq(0, 1, 2, 12, 13, 23, 24, 28, 29, 30, 31, 59, 60, 1999, 2023, 2024)

  /** Offset text, including the anchoring cases: a bare date must not read its own digits
   *  as an offset, and a name ending in Z must not read as Zulu. */
  val offsetTexts: Seq[String] = Seq(
    "Thu, 14 Mar 2024 15:30:00 -0700",
    "Thu, 14 Mar 2024 15:30:00 +0530",
    "2024-03-14T15:30:00Z",
    "2024-03-14T15:30:00z",
    "2024-03-14 15:30:00 -07:00",
    "2024-03-14 15:30:00 +00:00",
    "14 Mar 2024 15:30 UTC",
    "14 Mar 2024 15:30 GMT",
    "14 Mar 2024 15:30 (UT)",
    "14 Mar 2024 15:30 MST",
    "12-05-1024",
    "2024-03-14",
    "2024-03-14 15:30:00 -2500",
    "Fri Jan 10 2014 2:34:17 PM EST",
    "ends in Z",
    "1900-01-02T03:04:05",
    "  15:30:00 -0800  ",
  )

  def esc(s: String): String =
    s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

  def main(args: Array[String]): Unit =
    val out = collection.mutable.ArrayBuffer.empty[String]

    // toString, including the sentinel markers and the ISO omissions.
    for d <- moments ++ Seq(BadDate, EmptyDate) do
      out += s"tostr\t${key(d)}\t${esc(d.toString)}"

    // Formatting: every pattern against every moment.
    for d <- moments; p <- patterns do
      out += s"fmt\t${key(d)}\t${esc(p)}\t${esc(d.toString(p))}"

    // Field-level predicates and the calendar.
    for d <- moments do
      out += s"dow\t${key(d)}\t${DateFormat.dayOfWeek(d.year, d.month, d.day)}"
      out += s"epochday\t${key(d)}\t${d.toEpochDay}"
      out += s"valid\t${key(d)}\t${UniDateTime.isValid(d.year, d.month, d.day, d.hour, d.minute, d.second, d.nano)}"

    // daysInMonth across every month of representative years, leap rules included.
    for y <- Seq(1900, 1999, 2000, 2023, 2024, 2100, 2400); m <- 0 to 13 do
      out += s"dim\t$y\t$m\t${UniDateTime.daysInMonth(y, m)}"

    // ofEpochDay, both sides of the epoch and across era boundaries.
    for n <- Seq(-146097L, -40000L, -1000L, -1L, 0L, 1L, 1000L, 19800L, 40000L, 146097L) do
      out += s"ofepoch\t$n\t${esc(UniDateTime.ofEpochDay(n).toString)}"

    // Arithmetic: the part the Rust port writes out by hand.
    for d <- moments; (name, op) <- shiftOps; n <- amounts do
      out += s"shift\t${key(d)}\t$name\t$n\t${esc(op(d, n).toString)}"

    for d <- moments; (name, op) <- unaryOps do
      out += s"unary\t${key(d)}\t$name\t${esc(op(d).toString)}"

    for d <- moments; (name, op) <- withOps; a <- withArgs do
      out += s"with\t${key(d)}\t$name\t$a\t${esc(op(d, a).toString)}"

    // withDayOfWeek: the "next such day" semantics, including standing on the target.
    for d <- moments; dow <- 1 to 7 do
      out += s"withdow\t${key(d)}\t$dow\t${esc(d.withDayOfWeek(java.time.DayOfWeek.of(dow)).toString)}"

    // Sentinels absorb everything.
    for s <- Seq(BadDate, EmptyDate); (name, op) <- shiftOps do
      out += s"sentinel\t${key(s)}\t$name\t${esc(op(s, 1L).toString)}"

    // Offset extraction and rendering.
    for t <- offsetTexts do
      val got = UniDateTime.offsetMinutesOf(t)
      out += s"offset\t${esc(t)}\t${got.map(_.toString).getOrElse("none")}"

    for o <- Seq(-1080, -420, -1, 0, 1, 330, 1080, 1081, -1081) do
      val d = UniDateTime.of(2024, 5, 12, 14, 30, offsetMinutes = Some(o))
      out += s"offsettext\t$o\t${UniDateTime.isValidOffset(o)}\t${esc(d.offsetText)}\t${esc(d.toString)}"

    val header = Seq(
      "# Cross-language date parity reference. Generated by uni.apps.DateParityGen.",
      "# Do not hand-edit; see the generator for what each case pins.",
      "# kind<TAB>fields...<TAB>result   (fields are y m d H M S nano; \\\\ \\t \\n \\r escaped)",
      s"# rows: ${out.length}",
    )
    val file = Paths.get("test-data/date-parity/scala-reference.txt")
    java.nio.file.Files.createDirectories(file.getParent)
    file.writeLines(header ++ out.toSeq)
    println(s"wrote ${out.length} rows -> ${file.posx}")
    FixtureGuard.warnIfIgnored(file.getParent)

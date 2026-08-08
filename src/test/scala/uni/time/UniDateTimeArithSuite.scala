package uni.time

import munit.FunSuite
import java.time.LocalDateTime

/**
 * Calendar arithmetic on [[UniDateTime]], checked against `java.time` as the oracle.
 *
 * # Why these methods exist at all
 *
 * They are not conveniences. Without them, `d.plusWeeks(1)` reaches `LocalDateTime`
 * through the implicit conversion and hands back a `LocalDateTime` -- so a script that
 * shifts a date in one branch and not the other infers the union
 * `LocalDateTime | UniDateTime`, which satisfies neither type's position and which no
 * conversion can repair, because a conversion applies to a value and a union is not one.
 * Measured across the 166-script corpus, that union was the single largest source of
 * breakage when the parser's return type moved.
 *
 * So the property under test is two-fold, and the *type* half matters as much as the
 * value half: each method must agree with `java.time` numerically **and** return a
 * `UniDateTime`. The `assertUni` helper below asserts both at once -- it takes a
 * `UniDateTime` parameter, so a method that regressed to returning `LocalDateTime` would
 * fail to compile here rather than passing quietly.
 *
 * # Why java.time is the oracle
 *
 * The bodies currently delegate to it, so today these tests confirm the plumbing and the
 * types. Their real purpose is later: when the Rust port replaces the bodies with field
 * arithmetic, and the Scala side follows, this suite is what catches a hand-rolled
 * month-end or leap-year carry that disagrees with the calendar. The cases are chosen
 * with that second life in mind, which is why they cluster on boundaries rather than
 * sampling the middle of a month.
 */
class UniDateTimeArithSuite extends FunSuite:

  /** Moments chosen to sit on the boundaries hand-rolled arithmetic gets wrong. */
  val moments: Seq[LocalDateTime] = Seq(
    LocalDateTime.of(2024, 5, 12, 14, 30, 45, 123456789), // ordinary, all fields set
    LocalDateTime.of(2024, 1, 31, 0, 0, 0),               // month-end, clamping source
    LocalDateTime.of(2024, 2, 29, 23, 59, 59),            // leap day
    LocalDateTime.of(2023, 2, 28, 12, 0, 0),              // non-leap February
    LocalDateTime.of(2024, 12, 31, 23, 59, 59, 999999999),// year boundary, max fraction
    LocalDateTime.of(2000, 2, 29, 6, 0, 0),               // century leap year
    LocalDateTime.of(1900, 3, 1, 0, 0, 1),                // century non-leap, just after
  )

  /** Asserts the value matches `java.time` and -- by its parameter type -- that the
   *  method still returns a `UniDateTime`. */
  def assertUni(actual: UniDateTime, expected: LocalDateTime, clue: String): Unit =
    assertEquals(actual.toLocalDateTime, expected, clue)

  /** Amounts including zero and both signs; `plusMonths(-1)` and `plusDays(0)` are as
   *  easy to get wrong as the large cases. */
  val amounts: Seq[Long] = Seq(0L, 1L, -1L, 7L, -7L, 13L, 400L, -400L)

  test("plus/minus agree with java.time across units, signs and boundaries") {
    for
      m <- moments
      n <- amounts
    do
      val u = UniDateTime.from(m)
      val c = s"$m by $n"
      assertUni(u.plusNanos(n), m.plusNanos(n), s"plusNanos $c")
      assertUni(u.plusSeconds(n), m.plusSeconds(n), s"plusSeconds $c")
      assertUni(u.plusMinutes(n), m.plusMinutes(n), s"plusMinutes $c")
      assertUni(u.plusHours(n), m.plusHours(n), s"plusHours $c")
      assertUni(u.plusDays(n), m.plusDays(n), s"plusDays $c")
      assertUni(u.plusWeeks(n), m.plusWeeks(n), s"plusWeeks $c")
      assertUni(u.plusMonths(n), m.plusMonths(n), s"plusMonths $c")
      assertUni(u.plusYears(n), m.plusYears(n), s"plusYears $c")

      assertUni(u.minusNanos(n), m.minusNanos(n), s"minusNanos $c")
      assertUni(u.minusSeconds(n), m.minusSeconds(n), s"minusSeconds $c")
      assertUni(u.minusMinutes(n), m.minusMinutes(n), s"minusMinutes $c")
      assertUni(u.minusHours(n), m.minusHours(n), s"minusHours $c")
      assertUni(u.minusDays(n), m.minusDays(n), s"minusDays $c")
      assertUni(u.minusWeeks(n), m.minusWeeks(n), s"minusWeeks $c")
      assertUni(u.minusMonths(n), m.minusMonths(n), s"minusMonths $c")
      assertUni(u.minusYears(n), m.minusYears(n), s"minusYears $c")
  }

  test("month arithmetic clamps to the shorter month, as java.time does") {
    // The case a naive implementation gets wrong: January 31st plus one month is
    // February 28th or 29th, not March 3rd.
    val jan31 = UniDateTime.of(2024, 1, 31)
    assertEquals(jan31.plusMonths(1).toString, "2024-02-29T00:00")
    assertEquals(UniDateTime.of(2023, 1, 31).plusMonths(1).toString, "2023-02-28T00:00")
    // And the leap day losing its year.
    assertEquals(UniDateTime.of(2024, 2, 29).plusYears(1).toString, "2025-02-28T00:00")
  }

  test("withX field replacement agrees with java.time") {
    for m <- moments do
      val u = UniDateTime.from(m)
      assertUni(u.withYear(2021), m.withYear(2021), s"withYear $m")
      assertUni(u.withHour(3), m.withHour(3), s"withHour $m")
      assertUni(u.withMinute(7), m.withMinute(7), s"withMinute $m")
      assertUni(u.withSecond(11), m.withSecond(11), s"withSecond $m")
      assertUni(u.withNano(5), m.withNano(5), s"withNano $m")
      // Only days that exist in every month above, so the oracle does not throw.
      assertUni(u.withDayOfMonth(1), m.withDayOfMonth(1), s"withDayOfMonth $m")
  }

  test("withMonth clamps to the shorter month rather than failing") {
    // `java.time` clamps here rather than throwing -- January 31st with the month set to
    // February is the 29th, not an error -- and this follows it.
    assertEquals(UniDateTime.of(2024, 1, 31).withMonth(2).ymd, "2024-02-29")
    assertEquals(LocalDateTime.of(2024, 1, 31, 0, 0).withMonth(2).toString, "2024-02-29T00:00")
    assertEquals(UniDateTime.of(2023, 1, 31).withMonth(2).ymd, "2023-02-28")
    // withYear clamps the same way, off a leap day.
    assertEquals(UniDateTime.of(2024, 2, 29).withYear(2023).ymd, "2023-02-28")
  }

  test("an impossible result is BadDate, not an exception") {
    // `java.time` does throw for a day that cannot be clamped into range, and letting
    // that escape would mean a value from `of` -- which yields `BadDate` rather than
    // throwing -- starts throwing again one call later. The contract has to hold for the
    // whole type, not just its constructor.
    intercept[java.time.DateTimeException](LocalDateTime.of(2024, 2, 1, 0, 0).withDayOfMonth(31))
    assertEquals(UniDateTime.of(2024, 2, 1).withDayOfMonth(31), UniDateTime.BadDate)
    assertEquals(UniDateTime.of(2024, 5, 12).withHour(24), UniDateTime.BadDate)
    assertEquals(UniDateTime.of(2024, 5, 12).withMinute(-1), UniDateTime.BadDate)
    assertEquals(UniDateTime.of(2024, 5, 12).withNano(1_000_000_000), UniDateTime.BadDate)
  }

  test("the sentinels absorb arithmetic instead of becoming ordinary dates") {
    // As `BigNaN` does in uni.data. `BadDate` means "this did not parse"; shifting it a
    // day would yield 1900-01-03, an ordinary date that no longer equals `BadDate` -- so
    // a parse failure would slip past the check that exists to catch it.
    for sentinel <- Seq(UniDateTime.BadDate, UniDateTime.EmptyDate) do
      assertEquals(sentinel.plusDays(1), sentinel, s"plusDays on $sentinel")
      assertEquals(sentinel.plusWeeks(52), sentinel, s"plusWeeks on $sentinel")
      assertEquals(sentinel.minusYears(3), sentinel, s"minusYears on $sentinel")
      assertEquals(sentinel.withYear(2024), sentinel, s"withYear on $sentinel")
      assertEquals(sentinel.atStartOfDay(), sentinel, s"atStartOfDay on $sentinel")
      assertEquals(sentinel.lastDayOfMonth, sentinel, s"lastDayOfMonth on $sentinel")
      assertEquals(sentinel.withDayOfWeek(java.time.DayOfWeek.FRIDAY), sentinel,
        s"withDayOfWeek on $sentinel")
      assert(!sentinel.plusDays(1).isValid, s"$sentinel must stay invalid")
  }

  test("atStartOfDay zeroes the time and keeps the date") {
    for m <- moments do
      assertUni(UniDateTime.from(m).atStartOfDay(), m.withHour(0).withMinute(0).withSecond(0).withNano(0),
        s"atStartOfDay $m")
  }

  test("lastDayOfMonth matches the TemporalAdjuster, leap years included") {
    for m <- moments do
      val expected = m.`with`(java.time.temporal.TemporalAdjusters.lastDayOfMonth())
      assertUni(UniDateTime.from(m).lastDayOfMonth, expected, s"lastDayOfMonth $m")
    // Spelled out, since this is the leap-year case the port has to reproduce.
    assertEquals(UniDateTime.of(2024, 2, 10).lastDayOfMonth.ymd, "2024-02-29")
    assertEquals(UniDateTime.of(2023, 2, 10).lastDayOfMonth.ymd, "2023-02-28")
  }

  test("withDayOfWeek moves to the NEXT such day, even when already on it") {
    // Matching `TemporalAdjusters.next`, which the LocalDateTime extension used: a date
    // already on the target day advances a full week rather than standing still.
    for m <- moments do
      val expected = m.`with`(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.FRIDAY))
      assertUni(UniDateTime.from(m).withDayOfWeek(java.time.DayOfWeek.FRIDAY), expected, s"next Friday from $m")
    val friday = UniDateTime.of(2024, 5, 10) // itself a Friday
    assertEquals(friday.dayOfWeek, java.time.DayOfWeek.FRIDAY)
    assertEquals(friday.withDayOfWeek(java.time.DayOfWeek.FRIDAY).ymd, "2024-05-17")
  }

  test("dayOfWeek agrees with java.time over a long run of consecutive days") {
    // Sakamoto's method against the calendar, across leap and century boundaries.
    val start = LocalDateTime.of(1899, 12, 1, 0, 0)
    for i <- 0 until 1200 do
      val m = start.plusDays(i.toLong)
      val u = UniDateTime.from(m)
      assertEquals(u.dayOfWeek, m.getDayOfWeek, s"dayOfWeek for $m")
      assertEquals(u.dayOfWeekNum, m.getDayOfWeek.getValue, s"dayOfWeekNum for $m")
  }

  test("arithmetic preserves a stated UTC offset") {
    // The offset travels with the value rather than being silently dropped, and is not
    // applied to the local fields -- shifting by a day must not shift the offset.
    val withOffset = UniDateTime.of(2024, 5, 12, 14, 30, offsetMinutes = Some(-420))
    assertEquals(withOffset.plusDays(1).offsetMinutes, Some(-420))
    assertEquals(withOffset.plusDays(1).offsetText, "-07:00")
    assertEquals(withOffset.atStartOfDay().offsetMinutes, Some(-420))
    assertEquals(withOffset.plusDays(1).ymd, "2024-05-13")
  }

  test("comparison operators order by local fields") {
    val a = UniDateTime.of(2024, 5, 12, 14, 30, 0)
    val b = UniDateTime.of(2024, 5, 12, 14, 30, 1)
    assert(a < b); assert(a <= b); assert(b > a); assert(b >= a)
    assert(a.isBefore(b)); assert(b.isAfter(a))
    assert(a <= a); assert(a >= a)
    assert(!(a < a)); assert(!(a > a))
    // Nanoseconds are part of the order, not rounded away.
    val c = UniDateTime.of(2024, 5, 12, 14, 30, 0, 1)
    assert(a < c, s"$a should precede $c")
  }

  test("comparison agrees with java.time across every pair of the boundary moments") {
    for
      x <- moments
      y <- moments
    do
      val (ux, uy) = (UniDateTime.from(x), UniDateTime.from(y))
      assertEquals(ux < uy, x.isBefore(y), s"$x < $y")
      assertEquals(ux > uy, x.isAfter(y), s"$x > $y")
      assertEquals(ux.isBefore(uy), x.isBefore(y), s"$x isBefore $y")
      assertEquals(ux.isAfter(uy), x.isAfter(y), s"$x isAfter $y")
  }

  test("ymd, ymdhms and fmt are the field values, not a java.time detour") {
    val u = UniDateTime.of(2024, 5, 12, 14, 30, 45)
    assertEquals(u.ymd, "2024-05-12")
    assertEquals(u.ymdhms, "2024-05-12 14:30:45")
    assertEquals(u.fmt("yyyyMMdd"), "20240512")
    assertEquals(u.fmt("yyyyMMdd"), u.toString("yyyyMMdd"))
  }

  test("toEpochDay agrees with java.time over 200 years of consecutive days") {
    // Hinnant's algorithm against the calendar, day by day rather than sampled, across
    // leap years, the 1900 century non-leap, the 2000 century leap, and both sides of the
    // epoch -- which is where the negative-branch adjustments earn their place.
    var d = java.time.LocalDate.of(1880, 1, 1)
    val end = java.time.LocalDate.of(2080, 1, 1)
    while d.isBefore(end) do
      val u = UniDateTime.of(d.getYear, d.getMonthValue, d.getDayOfMonth)
      assertEquals(u.toEpochDay, d.toEpochDay, s"toEpochDay for $d")
      d = d.plusDays(1)
  }

  test("ofEpochDay round-trips with toEpochDay, and matches LocalDate") {
    for n <- -40000L to 40000L by 7L do
      val u = UniDateTime.ofEpochDay(n)
      val l = java.time.LocalDate.ofEpochDay(n)
      assertEquals(u.ymd, l.toString, s"ofEpochDay($n)")
      assertEquals(u.toEpochDay, n, s"round trip for $n")
  }

  test("toEpochDay ignores the time of day") {
    val midnight = UniDateTime.of(2024, 5, 12)
    val late = UniDateTime.of(2024, 5, 12, 23, 59, 59, 999999999)
    assertEquals(late.toEpochDay, midnight.toEpochDay)
    assertEquals(midnight.toEpochDay, java.time.LocalDate.of(2024, 5, 12).toEpochDay)
  }

  test("ofEpochDay yields midnight") {
    assertEquals(UniDateTime.ofEpochDay(0L).toString, "1970-01-01T00:00")
    assertEquals(UniDateTime.ofEpochDay(-1L).toString, "1969-12-31T00:00")
  }

  test("a sentinel has no epoch day") {
    // Long.MinValue rather than 0, so a caller sorting or comparing cannot mistake an
    // unparsed date for 1970-01-01.
    assertEquals(UniDateTime.BadDate.toEpochDay, Long.MinValue)
    assertEquals(UniDateTime.EmptyDate.toEpochDay, Long.MinValue)
  }

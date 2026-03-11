package uni.time

import munit.FunSuite
import uni.time.*
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

class TimeUtilsSuite extends FunSuite:

  // ============================================================================
  // Constants
  // ============================================================================

  test("BadDate and EmptyDate are distinct sentinels") {
    assertNotEquals(BadDate, EmptyDate)
    assertEquals(BadDate.getYear, 1900)
    assertEquals(EmptyDate.getYear, 1800)
  }

  test("Zone constants are valid ZoneId values") {
    assertEquals(UTC.getId, "UTC")
    assert(EasternTime.getId.nonEmpty)
    assert(MountainTime.getId.nonEmpty)
  }

  // ============================================================================
  // quikDate — two input-format branches
  // ============================================================================

  test("quikDate: pure-digit yyyyMMdd → correct date") {
    val dt = quikDate("20240512")
    assertEquals(dt.getYear, 2024)
    assertEquals(dt.getMonthValue, 5)
    assertEquals(dt.getDayOfMonth, 12)
  }

  test("quikDate: delimited yyyy-MM-dd → takes first 10 chars") {
    val dt = quikDate("2024-05-12")
    assertEquals(dt.getYear, 2024)
    assertEquals(dt.getMonthValue, 5)
    assertEquals(dt.getDayOfMonth, 12)
  }

  test("quikDate: string shorter than 8 chars triggers AssertionError") {
    intercept[AssertionError] { quikDate("2024") }
  }

  // ============================================================================
  // quikDateTime — all 5 match branches
  // ============================================================================

  test("quikDateTime: 3 fields (y/m/d) → midnight") {
    val dt = quikDateTime("2024-05-12")
    assertEquals(dt.getYear, 2024)
    assertEquals(dt.getHour, 0)
  }

  test("quikDateTime: 4 fields (y/m/d/h)") {
    val dt = quikDateTime("2024-05-12 14")
    assertEquals(dt.getHour, 14)
    assertEquals(dt.getMinute, 0)
  }

  test("quikDateTime: 5 fields (y/m/d/h/mn)") {
    val dt = quikDateTime("2024-05-12 14:30")
    assertEquals(dt.getHour, 14)
    assertEquals(dt.getMinute, 30)
  }

  test("quikDateTime: 6 fields (y/m/d/h/mn/s)") {
    val dt = quikDateTime("2024-05-12 14:30:45")
    assertEquals(dt.getSecond, 45)
  }

  test("quikDateTime: >6 numeric fields throws RuntimeException") {
    intercept[RuntimeException] { quikDateTime("2024-05-12 14:30:00:00") }
  }

  test("quikDateTime: require fails on non-matching string") {
    intercept[IllegalArgumentException] { quikDateTime("notadate-xx-yy") }
  }

  // ============================================================================
  // numerifyNames
  // ============================================================================

  test("numerifyNames: no month name → passthrough") {
    assertEquals(numerifyNames("2024-05-12"), "2024-05-12")
  }

  test("numerifyNames: weekday prefix stripped, no month name") {
    val r = numerifyNames("Monday, 2024-05-12")
    assert(!r.contains("Monday"), s"result: $r")
  }

  test("numerifyNames: month name replaced with two-digit number") {
    val r = numerifyNames("12 May 2024")
    assert(r.contains("05"), s"result: $r")
    assert(!r.contains("May"), s"result: $r")
  }

  test("numerifyNames: weekday + month name → both processed") {
    val r = numerifyNames("Monday, 12 May 2024")
    assert(!r.contains("Monday"), s"result: $r")
    assert(r.contains("05"), s"result: $r")
  }

  // ============================================================================
  // parseDate — SmartParse-then-ChronoParse fallback
  // ============================================================================

  test("parseDate: valid ISO date parses correctly") {
    val r = parseDate("2024-05-12")
    assertEquals(r.getYear, 2024)
    assertEquals(r.getMonthValue, 5)
  }

  test("parseDate: unparseable string falls back and returns BadDate") {
    val r = parseDate("definitely-not-a-date-xyz")
    assertEquals(r, BadDate)
  }

  // ============================================================================
  // elapsedDays
  // ============================================================================

  test("elapsedDays: forward is positive") {
    val d1 = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
    val d2 = LocalDateTime.of(2024, 1, 11, 0, 0, 0)
    assertEquals(elapsedDays(d1, d2), 10L)
  }

  test("elapsedDays: reversed is negative") {
    val d1 = LocalDateTime.of(2024, 1, 11, 0, 0, 0)
    val d2 = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
    assertEquals(elapsedDays(d1, d2), -10L)
  }

  // ============================================================================
  // daysBetween / secondsBetween / minutesBetween / hoursBetween / daysRounded
  // ============================================================================

  test("daysBetween: 7 days") {
    val d1 = LocalDateTime.of(2024, 3, 1, 0, 0, 0)
    val d2 = LocalDateTime.of(2024, 3, 8, 0, 0, 0)
    assertEquals(daysBetween(d1, d2), 7L)
  }

  test("secondsBetween(LocalDateTime, LocalDateTime, zone): 60 s") {
    val d1 = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
    val d2 = LocalDateTime.of(2024, 1, 1, 0, 1, 0)
    assertEquals(secondsBetween(d1, d2, UTC), 60L)
  }

  test("secondsBetween(Instant, Instant): 60 s") {
    val i1 = java.time.Instant.ofEpochSecond(1000L)
    val i2 = java.time.Instant.ofEpochSecond(1060L)
    assertEquals(secondsBetween(i1, i2), 60L)
  }

  test("minutesBetween: 90 s → 1.5 min") {
    val d1 = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
    val d2 = LocalDateTime.of(2024, 1, 1, 0, 1, 30)
    assertEqualsDouble(minutesBetween(d1, d2), 1.5, 1e-9)
  }

  test("hoursBetween: 2 h") {
    val d1 = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
    val d2 = LocalDateTime.of(2024, 1, 1, 2, 0, 0)
    assertEqualsDouble(hoursBetween(d1, d2), 2.0, 1e-9)
  }

  test("daysRounded: 2 full days") {
    val d1 = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
    val d2 = LocalDateTime.of(2024, 1, 3, 0, 0, 0)
    assertEqualsDouble(daysRounded(d1, d2), 2.0, 1e-9)
  }

  // ============================================================================
  // getDuration — positive and negative sign branches
  // ============================================================================

  test("getDuration: positive — decomposed into d/h/m/s correctly") {
    val d1 = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
    val d2 = LocalDateTime.of(2024, 1, 2, 2, 3, 4)
    val (days, hours, minutes, seconds) = getDuration(d1, d2)
    assertEquals(days,    1L)
    assertEquals(hours,   2L)
    assertEquals(minutes, 3L)
    assertEquals(seconds, 4L)
  }

  test("getDuration: negative — sign on days only, rest positive") {
    val d1 = LocalDateTime.of(2024, 1, 2, 2, 3, 4)
    val d2 = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
    val (days, hours, minutes, seconds) = getDuration(d1, d2)
    assertEquals(days,    -1L)
    assertEquals(hours,    2L)
    assertEquals(minutes,  3L)
    assertEquals(seconds,  4L)
  }

  test("getDuration(zone): explicit UTC") {
    val d1 = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
    val d2 = LocalDateTime.of(2024, 1, 1, 0, 0, 30)
    val (_, _, _, sec) = getDuration(d1, d2, UTC)
    assertEquals(sec, 30L)
  }

  // ============================================================================
  // endOfMonth
  // ============================================================================

  test("endOfMonth: January → day 31") {
    assertEquals(endOfMonth(LocalDateTime.of(2024, 1, 15, 12, 0, 0)).getDayOfMonth, 31)
  }

  test("endOfMonth: February 2024 (leap year) → day 29") {
    assertEquals(endOfMonth(LocalDateTime.of(2024, 2, 5, 0, 0, 0)).getDayOfMonth, 29)
  }

  test("endOfMonth: April → day 30") {
    assertEquals(endOfMonth(LocalDateTime.of(2024, 4, 10, 0, 0, 0)).getDayOfMonth, 30)
  }

  // ============================================================================
  // epoch2DateTime
  // ============================================================================

  test("epoch2DateTime: epoch 0 in UTC → 1970-01-01") {
    val dt = epoch2DateTime(0L, UTC)
    assertEquals(dt.getYear, 1970)
    assertEquals(dt.getMonthValue, 1)
    assertEquals(dt.getDayOfMonth, 1)
  }

  test("epoch2DateTime: 1000 days after epoch → 1972") {
    val dt = epoch2DateTime(1000L * 86_400_000L, UTC)
    assertEquals(dt.getYear, 1972)
  }

  // ============================================================================
  // whenModified
  // ============================================================================

  test("whenModified: existing file → year ≥ 2020") {
    val f = Files.createTempFile("timeutils-mod-", ".tmp").toFile
    f.deleteOnExit()
    assert(whenModified(f).getYear >= 2020)
  }

  test("whenModified: non-existent path → year ≤ 1970") {
    val dt = whenModified(new File("/nonexistent/timeutils-xyz.tmp"))
    assert(dt.getYear <= 1970, s"got year ${dt.getYear}")
  }

  // ============================================================================
  // ageInMinutes / ageInDays — both branches (exists / missing)
  // ============================================================================

  test("ageInMinutes: non-existent file → 1e6") {
    assertEqualsDouble(ageInMinutes(new File("/nonexistent/xyz.tmp")), 1e6, 0.0)
  }

  test("ageInMinutes: freshly-created file → ≥ 0 and < 1") {
    val f = Files.createTempFile("timeutils-age-", ".tmp").toFile
    f.deleteOnExit()
    val age = ageInMinutes(f)
    assert(age >= 0.0 && age < 1.0, s"age was $age minutes")
  }

  test("ageInDays(File): non-existent → positive") {
    assert(ageInDays(new File("/nonexistent/xyz.tmp")) > 0.0)
  }

  test("ageInDays(String): non-existent path → positive") {
    assert(ageInDays("/nonexistent/xyz.tmp") > 0.0)
  }

  // ============================================================================
  // sysTimer
  // ============================================================================

  test("sysTimer.elapsed / elapsedMillis are non-negative") {
    sysTimer.reset()
    assert(sysTimer.elapsed >= 0L)
    assert(sysTimer.elapsedMillis >= 0L)
  }

  test("sysTimer double-precision accessors are non-negative") {
    sysTimer.reset()
    assert(sysTimer.elapsedSeconds >= 0.0)
    assert(sysTimer.elapsedMinutes >= 0.0)
    assert(sysTimer.elapsedHours   >= 0.0)
    assert(sysTimer.elapsedDays    >= 0.0)
  }

  // ============================================================================
  // now / yesterday / nowZoned / nowUTC
  // ============================================================================

  test("now: year ≥ 2025") { assert(now.getYear >= 2025) }

  test("yesterday is strictly before now and exactly 1 day earlier") {
    assert(yesterday.isBefore(now))
    assertEquals(daysBetween(yesterday, now), 1L)
  }

  test("nowZoned: year ≥ 2025") { assert(nowZoned().getYear >= 2025) }

  test("nowUTC: positive epoch millis") { assert(nowUTC.toEpochMilli > 0L) }

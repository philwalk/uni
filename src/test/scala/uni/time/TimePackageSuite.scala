package uni.time

import munit.FunSuite
import uni.time.*
import java.time.{LocalDateTime, Duration, Period, DayOfWeek}

/** Covers the extension methods and top-level definitions in uni/time/package.scala */
class TimePackageSuite extends FunSuite:

  // 2024-05-12 14:30:45 — a Sunday
  private val dt = LocalDateTime.of(2024, 5, 12, 14, 30, 45)

  // ============================================================================
  // LocalDateTime formatting extensions
  // ============================================================================

  test("ymd: formats as yyyy-MM-dd") {
    assertEquals(dt.ymd, "2024-05-12")
  }

  test("ymdhms: formats as yyyy-MM-dd HH:mm:ss") {
    assertEquals(dt.ymdhms, "2024-05-12 14:30:45")
  }

  test("fmt: custom pattern") {
    assertEquals(dt.fmt("yyyy/MM/dd"), "2024/05/12")
  }

  test("toString(fmt): custom pattern") {
    assertEquals(dt.toString("dd-MM-yyyy"), "12-05-2024")
  }

  // ============================================================================
  // LocalDateTime comparison operators
  // ============================================================================

  test("> : later date is greater") {
    val later = dt.plusDays(1)
    assert(later > dt)
    assert(!(dt > later))
  }

  test("<= : earlier-or-equal") {
    assert(dt <= dt.plusSeconds(1))
    assert(dt <= dt)
  }

  test("< : strictly before") {
    assert(dt < dt.plusSeconds(1))
    assert(!(dt.plusSeconds(1) < dt))
  }

  test(">= : later-or-equal") {
    assert(dt >= dt)
    assert(dt.plusSeconds(1) >= dt)
  }

  // ============================================================================
  // LocalDateTime field accessors
  // ============================================================================

  test("year / monthNum / day") {
    assertEquals(dt.year, 2024)
    assertEquals(dt.monthNum, 5)
    assertEquals(dt.day, 12)
  }

  test("month") {
    assertEquals(dt.month, java.time.Month.MAY)
  }

  test("dayOfMonth / dayOfYear") {
    assertEquals(dt.dayOfMonth, 12)
    assert(dt.dayOfYear > 100)  // May 12 is past day 100
  }

  test("dayOfWeek: 2024-05-12 is a Sunday") {
    assertEquals(dt.dayOfWeek, DayOfWeek.SUNDAY)
  }

  test("hour / minute / second") {
    assertEquals(dt.hour, 14)
    assertEquals(dt.minute, 30)
    assertEquals(dt.second, 45)
  }

  test("getMillis: returns positive epoch millis") {
    assert(dt.getMillis() > 0L)
  }

  // ============================================================================
  // LocalDateTime calendar extensions
  // ============================================================================

  test("atStartOfDay: zeros h/m/s, keeps date") {
    val sod = dt.atStartOfDay()
    assertEquals(sod.hour, 0)
    assertEquals(sod.minute, 0)
    assertEquals(sod.second, 0)
    assertEquals(sod.day, dt.day)
  }

  test("to(other): duration to a later date") {
    val later = dt.plusMinutes(90)
    assertEquals(dt.to(later).getSeconds, 90L * 60)
  }

  test("lastDayOfMonth: May 2024 → day 31") {
    assertEquals(dt.lastDayOfMonth.day, 31)
  }

  test("withDayOfWeek: next MONDAY after Sunday 2024-05-12") {
    val nextMon = dt.withDayOfWeek(DayOfWeek.MONDAY)
    assertEquals(nextMon.dayOfWeek, DayOfWeek.MONDAY)
    assert(nextMon.isAfter(dt))
  }

  // ============================================================================
  // Int duration extensions
  // ============================================================================

  test("2.hours = Duration.ofHours(2)") {
    assertEquals(2.hours, Duration.ofHours(2))
  }

  test("30.minutes = Duration.ofMinutes(30)") {
    assertEquals(30.minutes, Duration.ofMinutes(30))
  }

  test("10.seconds = Duration.ofSeconds(10)") {
    assertEquals(10.seconds, Duration.ofSeconds(10))
  }

  test("3.days = Duration.ofDays(3)") {
    assertEquals(3.days, Duration.ofDays(3))
  }

  // ============================================================================
  // elapsedSeconds (top-level function — absolute value)
  // ============================================================================

  test("elapsedSeconds: 2 minutes apart, forward and reverse both give 120") {
    val t1 = LocalDateTime.of(2024, 1, 1, 0, 0, 0)
    val t2 = LocalDateTime.of(2024, 1, 1, 0, 2, 0)
    assertEquals(elapsedSeconds(t1, t2), 120L)
    assertEquals(elapsedSeconds(t2, t1), 120L)  // absolute value
  }

  // ============================================================================
  // Duration extensions
  // ============================================================================

  test("getStandardSeconds / Minutes / Hours / Days on 7261 s") {
    val d = Duration.ofSeconds(7261)  // = 2h 1m 1s
    assertEquals(d.getStandardSeconds, 7261L)
    assertEquals(d.getStandardMinutes, 121L)
    assertEquals(d.getStandardHours,   2L)
    assertEquals(d.getStandardDays,    0L)
  }

  test("getStandardDays: 2 full days") {
    assertEquals(Duration.ofDays(2).getStandardDays, 2L)
  }

  // ============================================================================
  // DayOfWeek comparison operators
  // ============================================================================

  test("DayOfWeek >, >=, <, <= operators") {
    assert(DayOfWeek.WEDNESDAY >= DayOfWeek.MONDAY)
    assert(DayOfWeek.WEDNESDAY >  DayOfWeek.TUESDAY)
    assert(DayOfWeek.MONDAY    <= DayOfWeek.FRIDAY)
    assert(DayOfWeek.TUESDAY   <  DayOfWeek.WEDNESDAY)
    assert(!(DayOfWeek.FRIDAY  <  DayOfWeek.MONDAY))
    assert(DayOfWeek.MONDAY    >= DayOfWeek.MONDAY)   // equal
    assert(!(DayOfWeek.MONDAY  >  DayOfWeek.MONDAY))  // not strictly greater
  }

  // ============================================================================
  // TimeArith extensions (+/- Duration and Period)
  // ============================================================================

  test("dt + Duration advances time") {
    assertEquals((dt + 1.hours).hour, 15)
  }

  test("dt - Duration reverses time") {
    val r = dt - 30.minutes
    assertEquals(r.hour, 14)
    assertEquals(r.minute, 0)
  }

  test("dt + Period advances months") {
    assertEquals((dt + Period.ofMonths(1)).monthNum, 6)
  }

  test("dt - Period reverses days") {
    assertEquals((dt - Period.ofDays(1)).day, 11)
  }

  // ============================================================================
  // Instant extension
  // ============================================================================

  test("Instant.toString(pattern, zone): epoch 0 → 1970-01-01") {
    val i = java.time.Instant.ofEpochMilli(0L)
    assertEquals(i.toString("yyyy-MM-dd", UTC), "1970-01-01")
  }

  test("Instant.toString with default zone does not throw") {
    val s = java.time.Instant.now().toString("yyyy")
    assert(s.toInt >= 2025)
  }

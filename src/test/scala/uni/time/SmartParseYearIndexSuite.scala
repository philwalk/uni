package uni.time

import munit.FunSuite

/**
 * `SmartParse` used to lose part of the time when a number in the input happened to
 * expand to the same year.
 *
 * `parseMonthDayYear` and `parseDayMonthYear` found the year's *value* first and then
 * searched the number list for its *position* by value, allowing a two-digit
 * expansion. That matched whichever number expanded to the year -- the day `18` looks
 * like 2018, the hour `22` like 2022, the minute `29` like 2029 -- and the slice
 * between day and year then truncated the time at the wrong point.
 *
 * Nothing threw and nothing returned `BadDate`: `Aug 18 22:29:47 2018` came back as
 * midnight and `Aug 1 22:29:47 2029` as 22:00, both perfectly plausible. It struck
 * roughly one row in a hundred, unpredictably, which for a CSV date column is the
 * worst kind of failure.
 */
class SmartParseYearIndexSuite extends FunSuite:

  test("the ls/syslog form keeps its time for every day and year") {
    val wrong = for
      day  <- 1 to 28
      year <- 1990 to 2035
      in    = f"Aug $day%d 22:29:47 $year%d"
      got   = parseDateSmart(in)
      if got.getYear != year || got.getDayOfMonth != day ||
         got.getHour != 22 || got.getMinute != 29 || got.getSecond != 47
    yield s"[$in] -> $got"
    assertEquals(wrong, Seq.empty[String], s"${wrong.size} inputs mis-parsed")
  }

  test("day-month-year keeps its date for every day and year") {
    val wrong = for
      day  <- 1 to 28
      year <- 1990 to 2035
      in    = f"$day%d Aug $year%d"
      got   = parseDateSmart(in)
      if got.getYear != year || got.getDayOfMonth != day
    yield s"[$in] -> $got"
    assertEquals(wrong, Seq.empty[String], s"${wrong.size} inputs mis-parsed")
  }

  test("the specific collisions that used to fail") {
    // Named so a regression reads as itself rather than as one of 1288 combinations.
    // day == year % 100:
    assertEquals(parseDateSmart("Aug 18 22:29:47 2018").toString, "2018-08-18T22:29:47")
    assertEquals(parseDateSmart("Aug 5 01:02:03 2005").toString,  "2005-08-05T01:02:03")
    // hour == year % 100, which truncated to midnight:
    assertEquals(parseDateSmart("Aug 1 22:29:47 2022").toString,  "2022-08-01T22:29:47")
    // minute == year % 100, which kept the hour and dropped the rest:
    assertEquals(parseDateSmart("Aug 1 22:29:47 2029").toString,  "2029-08-01T22:29:47")
  }

  test("a two-digit year still works, and is still taken from position 1") {
    // The fallback path: no 4-digit number, so the year must be the second number.
    assertEquals(parseDateSmart("Aug 18 18").toString, "2018-08-18T00:00")
    assertEquals(parseDateSmart("18 Aug 18").toString, "2018-08-18T00:00")
  }

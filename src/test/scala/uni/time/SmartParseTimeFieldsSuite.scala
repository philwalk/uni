package uni.time

import munit.FunSuite

/**
 * `SmartParse` used to lose the time whenever the year came before it.
 *
 * `parseMonthDayYear` and `parseDayMonthYear` took the time to be the numbers
 * *between* the day and the year. That holds for `Aug 18 22:29:47 2018` and for
 * nothing else: in RFC 2822 (`01 Jan 2001 12:34:56 -0700`) and in
 * `May 12, 2024 2:30 PM` the year comes first, so the slice was empty and the time
 * silently became midnight -- or, with a PM flag applied to an hour of zero, noon.
 *
 * The time is now whatever numbers remain once day and year are accounted for, capped
 * at three so a trailing zone offset is not read as a fourth field.
 */
class SmartParseTimeFieldsSuite extends FunSuite:

  test("RFC 2822 keeps its time, with and without a weekday or zone name") {
    for in <- Seq(
      "01 Jan 2001 12:34:56 -0700",
      "1 Jan 2001 12:34:56 -0700",
      "Mon, 01 Jan 2001 12:34:56 -0700",
      "Mon, 01 Jan 2001 12:34:56 -0700 (MDT)",
      "01 Jan 2001 12:34:56 -0700 MDT",
    ) do
      assertEquals(parseDateSmart(in).toString, "2001-01-01T12:34:56", s"for [$in]")
  }

  test("a UTC-offset form keeps its time") {
    for in <- Seq("Sun, 12 May 2024 14:30:00 +0000", "Sun, 12 May 2024 14:30:00 GMT") do
      assertEquals(parseDateSmart(in).toString, "2024-05-12T14:30", s"for [$in]")
  }

  test("a 12-hour time after the year is not collapsed to noon") {
    // `2:30 PM` used to yield 12:00: the slice found no time, so the PM adjustment
    // was applied to an hour of zero.
    assertEquals(parseDateSmart("May 12, 2024 2:30 PM").toString, "2024-05-12T14:30")
    assertEquals(parseDateSmart("Fri Jan 10 2014 2:34:17 PM EST").toString, "2014-01-10T14:34:17")
  }

  test("midnight and noon stay distinct across AM/PM") {
    // The one place SmartParse was already right and ChronoParse was not: 12 AM is
    // midnight, not noon.
    assertEquals(parseDateSmart("9/29/2008 12:00:00 AM").toString, "2008-09-29T00:00")
    assertEquals(parseDateSmart("9/29/2008 12:00:00 PM").toString, "2008-09-29T12:00")
  }

  test("a trailing zone offset is not read as a seconds field") {
    // `-0700` tokenises as a number; without the cap it became a fourth time field.
    assertEquals(parseDateSmart("01 Jan 2001 12:34 -0700").toString, "2001-01-01T12:34")
  }

  test("the year-last form still works") {
    // The layout the old slice was written for must not regress.
    assertEquals(parseDateSmart("Aug 18 22:29:47 2018").toString, "2018-08-18T22:29:47")
    assertEquals(parseDateSmart("18 Aug 2018").toString, "2018-08-18T00:00")
  }

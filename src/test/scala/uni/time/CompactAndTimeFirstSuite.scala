package uni.time

import munit.FunSuite

/**
 * The two formats folded in from `ChronoParse` when it stopped being consulted.
 *
 * Both are handled by rewriting in `preNormalize` rather than by new shapes, so the
 * existing ISO/YMD path does the parsing and the time comes along for free.
 */
class CompactAndTimeFirstSuite extends FunSuite:

  test("a bare yyyyMMdd parses") {
    assertEquals(parseDateSmart("20200102").toString, "2020-01-02T00:00")
    assertEquals(parseDateSmart("20991231").toString, "2099-12-31T00:00")
    assertEquals(parseDateSmart("10000101").toString, "1000-01-01T00:00")
  }

  test("a bare yyyyMMdd carries a compact or separated time") {
    // The compact time is part of the same format: expanding only the date left
    // `1430` to be read as an hour, which broke `20240512 1430 MST`.
    assertEquals(parseDateSmart("20200102 13:45:56").toString, "2020-01-02T13:45:56")
    assertEquals(parseDateSmart("20240512 1430").toString,     "2024-05-12T14:30")
    assertEquals(parseDateSmart("20240512 143055").toString,   "2024-05-12T14:30:55")
    assertEquals(parseDateSmart("20240512 1430 MST").toString, "2024-05-12T14:30")
  }

  test("eight digits that are not a date stay BadDate") {
    // The guard matters: an identifier must not be silently read as a date.
    for in <- Seq("99999999", "20201345", "12345678", "20200100", "20200132") do
      assertEquals(parseDateSmart(in), BadDate, s"[$in] should not parse")
  }

  test("a valid date with an implausible compact time is left alone") {
    // All or nothing: half a rewrite is worse than none.
    assertEquals(parseDateSmart("20200102 9999"), BadDate)
  }

  test("a leading time is moved after the date") {
    assertEquals(parseDateSmart("13:45 2020-01-02").toString,    "2020-01-02T13:45")
    assertEquals(parseDateSmart("01:02:03 2020-01-02").toString, "2020-01-02T01:02:03")
  }

  test("a trailing time is unaffected by the rotation") {
    // The rotation must not fire on the normal ordering.
    assertEquals(parseDateSmart("2020-01-02 13:45").toString, "2020-01-02T13:45")
  }

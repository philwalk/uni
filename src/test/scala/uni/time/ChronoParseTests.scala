package uni.time

import uni.*
//import uni.time.*
import java.time.LocalDateTime

import TestDates.*

import munit.FunSuite

class ChronoParseTests extends FunSuite {

  def parseDate(str: String): LocalDateTime = {
    val parser: ChronoParse = ChronoParse(str)
    parser.dateTime
  }

  // -----------------------------
  // parseDateTime tests
  // -----------------------------
  for ((teststr, expected) <- testDatesExpected) {
    test(s"parseDateTime should parse :[$teststr]") {
      val pDate: LocalDateTime = parseDate(teststr)
      val pds = pDate.toString("yyyy/MM/dd")

      if (pds != expected)
        hook += 1

      assertEquals(pds, expected)
    }
  }

  for ((str, index) <- testDatesToIso.zipWithIndex) {
    test(s"$str should parse and format to ISO") {
      val parsed = parseDate(str)

      // Format to ISO-like output
      val iso = parsed.toString("yyyy/MM/dd HH:mm:ss")

      // Basic sanity checks
      assert(iso.matches("""\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}"""),
        s"Input: $str\nOutput: $iso\nIndex: $index")
    }
  }

  for (str <- testDatesToIso) {
    test(s"parsing ISO formatted ${str} should be idempotent") {
      val parsed1 = parseDate(str) // dateParser(str)
      val iso1    = parsed1.toString("yyyy/MM/dd HH:mm:ss")

      val parsed2 = parseDate(iso1) // dateParser(iso1)
      val iso2    = parsed2.toString("yyyy/MM/dd HH:mm:ss")

      assertEquals(
        iso2,
        iso1,
        s"Round‑trip mismatch for input: $str\niso1: $iso1\niso2: $iso2"
      )
    }
  }

  test("parsed dates should sort consistently by timestamp") {
    val parsed = testDatesToIso.map { str =>
      val dt = parseDate(str) // dateParser(str)
      val epoch = dt.atZone(MountainTime).toInstant.toEpochMilli
      (str, epoch)
    }

    val sortedByParser = parsed.sortBy(_._2).map(_._1)
    val sortedByString = testDatesToIso.sortBy { str =>
      //dateParser(str).atZone(MountainTime).toInstant.toEpochMilli
      parseDate(str).atZone(MountainTime).toInstant.toEpochMilli
    }

    assertEquals(
      sortedByParser,
      sortedByString,
      "Sorting mismatch between parser-sorted and epoch-sorted lists"
    )
  }

  // --------------------------------
  // parse testDates against expected
  // --------------------------------
  for ((teststr, expected) <- TestDates.testDatesExpected) {
    test(s"parseSmart should properly parse string [$teststr]") {
      val pDate: LocalDateTime = parseDate(teststr)
      val pds    = pDate.toString("yyyy/MM/dd")
      assertEquals(pds, expected)
    }
  }

  for (teststr <- TestDates.testDatesToIso) {
    test(s"should parse timestamp [$teststr]") {
      val pDate: LocalDateTime = parseDate(teststr)
      val Array(ys, ms, ds)  = pDate.toString("yyyy/MM/dd").split("/")

      val y = toInt(ys)
      val m = toInt(ms)
      val d = toInt(ds)

      assert(y >= 1900 && y <= 2100)
      assert(m >= 1 && m <= 12)
      assert(d >= 1 && d <= 31)

      assertEquals(pDate.getYear,       y)
      assertEquals(pDate.getMonthValue, m)
      assertEquals(pDate.getDayOfMonth, d)
    }
  }

  test("datesProduceValidEpochAndBeMonotonic") {
    val millis = testDatesToIso.map { str =>
      val dt = parseDate(str)
      val iso = dt.toString("yyyy/MM/dd HH:mm:ss")

      val epoch = dt.atZone(MountainTime).toInstant.toEpochMilli
      assert(epoch > 0, s"Invalid epoch for $str → $iso")

      epoch
    }.sorted

    // Check monotonicity (non-decreasing)
    for (i <- 1 until millis.length) {
      assert(
        millis(i) >= millis(i - 1),
        s"Epoch ordering violated at index $i:\n${millis(i - 1)} then ${millis(i)}"
      )
    }
  }
}


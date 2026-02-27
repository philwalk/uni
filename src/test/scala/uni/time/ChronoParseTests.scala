package uni.time

import uni.time.*
import java.time.LocalDateTime

import TestDates.*

import munit.FunSuite

class ChronoParseTests extends FunSuite {
  test("chronoParse should match smartParse for -, ., / numeric dates") {
    val inputs = List(
      "04/12/1992",
      "04-12-1992",
      "04.12.1992",
      "2019/01/31 0:01",
      "2019-01-31 0:01",
      "2019.01.31 0:01"
    )

    inputs.foreach { in =>
      val s = parseDateSmart(in)
      val c = parseDateChrono(in)
      assertEquals(
        s.toString("yyyy-MM-dd'T'HH:mm:ss"),
        c.toString("yyyy-MM-dd'T'HH:mm:ss"),
        s"Mismatch for input: $in"
      )
    }
  }

  // --------------------------------
  // parse testDates against expected
  // --------------------------------
  test(s"ampm") {
    val teststr = "04/12/1992 01:58 PM"
    val pDate: LocalDateTime = parseDateChrono(teststr)
    val pds2   = pDate.toString("yyyy-MM-dd'T'HH:mm:ss")
    val expectedStamp = "1992-04-12T13:58:00"
    assertEquals(pds2, expectedStamp,
      s"Input: $teststr\nOutput: $pDate"
    )
  }

  test(s"chronoParse should properly parse various test strings") {
    for (((teststr, expectedTimestamp), index) <- testDatesExpected.zipWithIndex) {
      val pDate: LocalDateTime = parseDateChrono(teststr)
      val pds    = pDate.toString("yyyy-MM-dd")
      val expectedDate = expectedTimestamp.replaceAll("T.*", "")
      assertEquals(pds, expectedDate,
        s"Input: $teststr\nOutput: $pDate\nIndex: $index"
      )
      val pds2   = pDate.toString("yyyy-MM-dd'T'HH:mm:ss")
      val expectedStamp = expectedTimestamp.replaceAll("[.][0-9]*$", "")
      assertEquals(pds2, expectedStamp,
        s"Input: $teststr\nOutput: $pDate\nIndex: $index"
      )
    }
  }


  test(s"parseDateChrono should parse various and format to ISO") {
    for ((str, index) <- testDatesToIso.zipWithIndex) {
      val parsed = parseDateChrono(str)

      // Format to ISO-like output
      val iso = parsed.toString("yyyy/MM/dd HH:mm:ss")

      // Basic sanity checks
      assert(iso.matches("""\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}"""),
        s"Input: $str\nOutput: $iso\nIndex: $index")
    }
  }

  test(s"parsing ISO formatted should be idempotent") {
    for (str <- testDatesToIso) {
      val parsed1 = parseDateChrono(str) // dateParser(str)
      val iso1    = parsed1.toString("yyyy/MM/dd HH:mm:ss")

      val parsed2 = parseDateChrono(iso1) // dateParser(iso1)
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
      val dt = parseDateChrono(str) // dateParser(str)
      val epoch = dt.atZone(MountainTime).toInstant.toEpochMilli
      (str, epoch)
    }

    val sortedByParser = parsed.sortBy(_._2).map(_._1)
    val sortedByString = testDatesToIso.sortBy { str =>
      //dateParser(str).atZone(MountainTime).toInstant.toEpochMilli
      parseDateChrono(str).atZone(MountainTime).toInstant.toEpochMilli
    }

    assertEquals(
      sortedByParser,
      sortedByString,
      "Sorting mismatch between parser-sorted and epoch-sorted lists"
    )
  }

  test(s"should parse diverse timestamps") {
    for (teststr <- TestDates.testDatesToIso) {
      val pDate: LocalDateTime = parseDateChrono(teststr)
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
      val dt = parseDateChrono(str)
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


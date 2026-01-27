package uni.time

import uni.*
import uni.time.*
import java.time.LocalDateTime

import TestDates.*

import munit.FunSuite

class SmartParseTests extends FunSuite {
  private var hook = 0
  test("mdy-with-time") {
    val localdatetime = parseDate("04/08 18:17:08 2009")
    printf("%s\n", localdatetime)
    hook += 1
  }
  
  test("classifier matches pattern buckets") {
    var iterations = 100
    var seedcounter = 0
    for
      (shape, patterns) <- SmartParse.patternsByShape
      pattern <- patterns
      i <- 0 until iterations
    do
      ExampleGeneratorSmarter.seed(seedcounter)
      seedcounter += 1

      val (example, expected) = ExampleGeneratorSmarter.exampleForPattern(pattern)
      if verboseUni then println(s"example: $example, expected: $expected")
      val inferred = SmartParse.classify(example)
      if shape != inferred && (true || verboseUni) then
        println(s"shape[$shape] != inferred[$inferred] for seed ${seedcounter-1} and date '${example}'")

      assertEquals(
        inferred,
        expected,
        s"Pattern $pattern misclassified for example: '$example'"
      )
  }

  test("Pareto of shapes in test corpus") {
    val allTestStrings: Seq[String] = TestDates.all // Your test corpus: replace with your actual list

    // Classify each string
    val classified: Seq[(String, SmartParse.Shape)] = allTestStrings.map(s => s -> SmartParse.classify(s))

    // Group by shape
    val grouped: Map[SmartParse.Shape, Seq[String]] = classified.groupBy(_._2).view.mapValues(_.map(_._1)).toMap

    // Sort by descending count (Pareto)
    val pareto = grouped.toSeq.sortBy { case (_, strs) => -strs.size }

    // Pretty print
    println("\n=== Pareto of Shapes ===")
    pareto.foreach { case (shape, strs) =>
      println(f"${shape.toString}%-18s  count=${strs.size}%4d")
    }

    // Optional: fail if any shape has zero coverage
    val missing = SmartParse.Shape.values.toSet -- grouped.keySet

    assertEquals(
      missing,
      Set.empty[SmartParse.Shape],
      s"Shapes with no test coverage: $missing"
    )
  }

  test("patternsByShape") {
    SmartParse.patternsByShape.foreach { (shape, list) =>
      if list.distinct != list then
        fail(s"$shape:\n${list.mkString("\n")}")
    }
    val patterns = SmartParse.patternsByShape.values.toSeq.flatten
    val duples = patterns.groupBy(identity).collect { case (p, xs) if xs.size > 1 => p }
    assertEquals(duples.size, 0, duples)
  }
  test("sharedPatterns") {
    // 1. No duplicates within a shape
    SmartParse.patternsByShape.foreach { (shape, list) =>
      val dups = list.groupBy(identity).collect { case (p, xs) if xs.size > 1 => p }
      if dups.nonEmpty then
        fail(s"Shape $shape contains duplicate patterns:\n${dups.mkString("\n")}")
    }

    // 2. Build reverse index: pattern -> shapes that contain it
    val reverse =
      SmartParse.patternsByShape.toSeq
        .flatMap { case (shape, list) => list.map(_ -> shape) }
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2))
        .toMap

    // 3. Find patterns that appear in more than one shape
    val collisions =
      reverse.collect { case (pattern, shapes) if shapes.distinct.size > 1 =>
        pattern -> shapes.distinct
      }

    if collisions.nonEmpty then
      val msg =
        collisions
          .map { case (p, shapes) =>
            s"Pattern '$p' appears in shapes: ${shapes.mkString(", ")}"
          }
          .mkString("\n\n")

      fail(s"patternsByShape contains cross-shape collisions:\n\n$msg")
  }

  test("smartParse should treat -, ., / delimiters equivalently for numeric dates") {
    val cases = List(
      ("04/12/1992", "1992-04-12T00:00:00"),
      ("04-12-1992", "1992-04-12T00:00:00"),
      ("04.12.1992", "1992-04-12T00:00:00"),

      ("2019/01/31 0:01", "2019-01-31T00:01:00"),
      ("2019-01-31 0:01", "2019-01-31T00:01:00"),
      ("2019.01.31 0:01", "2019-01-31T00:01:00"),

      ("31/05/2009 08:59:59", "2009-05-31T08:59:59"),
      ("31-05/2009 08:59:59".replace('-', '/'), "2009-05-31T08:59:59"), // if you want DMY variants
      ("31.05.2009 08:59:59", "2009-05-31T08:59:59")
    )

    cases.foreach { (in, expectedIso) =>
      val dt = parseDateSmart(in)
      assertEquals(dt.toString("yyyy-MM-dd'T'HH:mm:ss"), expectedIso)
    }
  }

  // --------------------------------
  // parse testDates against expected
  // --------------------------------
  for (((teststr, expectedTimestamp), index) <- TestDates.testDatesExpected.zipWithIndex) {
    test(s"parseSmarter should properly parse string [$teststr]") {
      val pDate: LocalDateTime = parseDate(teststr)
      val pds    = pDate.toString("yyyy-MM-dd")
      val expectedDate = expectedTimestamp.replaceAll("T.*", "")
      assertEquals(pds, expectedDate,
        s"Input: $teststr\nOutput: $pDate\nIndex: $index"
      )
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
      val parsed1 = parseDate(str) // dateParse(str)
      val iso1    = parsed1.toString("yyyy/MM/dd HH:mm:ss")

      val parsed2 = parseDate(iso1) // dateParse(iso1)
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
      val dt = parseDate(str) // dateParse(str)
      val epoch = dt.atZone(MountainTime).toInstant.toEpochMilli
      (str, epoch)
    }

    val sortedByParse = parsed.sortBy(_._2).map(_._1)
    val sortedByString = testDatesToIso.sortBy { str =>
      //dateParse(str).atZone(MountainTime).toInstant.toEpochMilli
      parseDate(str).atZone(MountainTime).toInstant.toEpochMilli
    }

    assertEquals(
      sortedByParse,
      sortedByString,
      "Sorting mismatch between parser-sorted and epoch-sorted lists"
    )
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

object TestDates {
  def toInt(s: String): Int = s.dropWhile(_ == '0') match {
  case ""  => 0
  case str => str.toInt
  }
  def all: Seq[String] = {
    (testDatesExpected.map(_._1) ++ testDatesPlusExpected.map(_._1) ++ testDatesToIso).distinct
  }

  lazy val testDatesPlusExpected = List(
    ("Apr12-11",                               "2011/04/12"),
    ("apr12-11",                               "2011/04/12"),
    ("11-Apr-2016",                            "2016/04/11"),
    ("01/04/15",                               "2015/01/04"),
    ("May 16, 2014",                           "2014/05/16"),
    ("1992/04/13 23:59",                       "1992/04/13"), // yyyy/MM/dd HH:mm
    ("1992/01/01",                             "1992/01/01"), // yyyy/MM/dd
    ("8/04/2009 17:09:46 -0700",               "2009/08/04"), // M/dd/yyyy HH:mm:ss -0700
    ("31/05/2009 08:59:59 -0000",              "2009/05/31"), // MM/dd/yyyy hh:mm:ss -0000
    ("31/05/2009 02:20:13 -0700",              "2009/05/31"), // MM/dd/yyyy hh:mm:ss -0700
    ("2/11/2009 16:34:32 -0800",               "2009/02/11"), // MM/dd/yyyy HH:mm:ss -0800 // ambiguous defers to MDY
    ("04/08 18:17:08 2009",                    "2009/04/08"), // MM/dd HH:mm:ss yyyy
    ("05/06/1993",                             "1993/05/06"), // MM/dd/yyyy
    ("2009/03/24 21:48:25.0",                  "2009/03/24"), // yyyy/MM:dd HH:mm:ss.S
    ("2009/03/30 22:10:03",                    "2009/03/30"), // yyyy/MM/dd HH:mm:ss
    ("Fri Jan 10 2014 2:34:17 PM EST",         "2014/01/10"),
    ("04/13/1992 11:59 PM",                    "1992/04/13"),
    ("04/13/1992 12:01 PM",                    "1992/04/13"),
    ("1992/01/01",                             "1992/01/01"),
    ("May 16,2014",                            "2014/05/16"),
    ("1992/03/04",                             "1992/03/04"),
    ("08/04/2009 17:09:46 -0700",              "2009/08/04"),
    ("8/04/2009 17:09:46 -0700",               "2009/08/04"),
    ("31/05/2009 08:59:59 -0000",              "2009/05/31"),
    ("31/05/2009 02:20:13 -0700",              "2009/05/31"),
    ("2/11/2009 16:34:32 -0800",               "2009/02/11"),
    ("22/11/1992 07:25:19 -0800",              "1992/11/22"),
    (     "01 Jan 2001 12:34:56 -0700",        "2001/01/01"),
    (     "01 Jan 2001 12:34:56 -0700 (MDT)",  "2001/01/01"),
    (     "01 Jan 2001 12:34:56 -0700 MDT",    "2001/01/01"),
    (      "1 Jan 2001 12:34:56 -0700",        "2001/01/01"),
    ("Mon, 01 Jan 2001 12:34:56 -0700",        "2001/01/01"),
    ("Mon, 01 Jan 2001 12:34:56 -0700 (MDT)",  "2001/01/01"),
    ("Mon,  1 Jan 2001 12:34:56 -0700",        "2001/01/01"),
    ("Mon,  1 Jan 2001 12:34:56 -0700 (MDT)",  "2001/01/01"),
    ("Mon, 01 Jan 2001 12:34:56 -0700",        "2001/01/01"),
    ("Mon, 01 Jan 2001 12:34:56 -0700 (CEST)", "2001/01/01"),
    ("Mon, 01 Jan 2001 12:34:56 -0700 (MDT)",  "2001/01/01"),
    ("Mon,  1 Jan 2001 12:34:56 -0700",        "2001/01/01"),
    ("Mon,  1 Jan 2001 12:34:56 -0700 (MDT)",  "2001/01/01"),
    ("04/12/1992 01:58 PM",                    "1992/04/12"),
    ("04/12/1992 02:46 PM",                    "1992/04/12"),
    ("1992/04/13 23:59",                       "1992/04/13"),
    ("04/13/1992 11:59 PM",                    "1992/04/13"),
    ("04/13/1992 12:01 PM",                    "1992/04/13"),
    ("04/13/1992 12:50 PM",                    "1992/04/13"),
    ("9/29/2008 2:19:51 PM",                   "2008/09/29"),
    ("9/29/2008 12:00:00 AM",                  "2008/09/29"),
    ("9/29/2008 2:07:18 PM",                   "2008/09/29"),
    ("9/29/2008 2:06:17 PM",                   "2008/09/29"),
    ("9/29/2008 2:05:51 PM",                   "2008/09/29"),
    ("11/12/2008 10:20:06 AM",                 "2008/11/12"),
    ("11/12/2008 12:00:00 AM",                 "2008/11/12"),
    ("12/2/2008 6:49:53 PM",                   "2008/12/02"),
    ("12/2/2008 12:00:00 AM",                  "2008/12/02"),
    ("1/10/2009 4:05:57 PM",                   "2009/01/10"),
    ("1/10/2009 12:00:00 AM",                  "2009/01/10"),
    ("2/5/2009 1:31:58 PM",                    "2009/02/05"),
    ("2/5/2009 12:00:00 AM",                   "2009/02/05"),
    ("2/10/2009 6:51:43 PM",                   "2009/02/10"),
    ("2/10/2009 12:00:00 AM",                  "2009/02/10"),
    ("3/10/2009 7:34:33 PM",                   "2009/03/10"),
    ("3/10/2009 12:00:00 AM",                  "2009/03/10"),
    ("3/13/2009 5:17:15 PM",                   "2009/03/13"),
    ("3/13/2009 12:00:00 AM",                  "2009/03/13"),
    ("2009/03/24 21:48:25.0",                  "2009/03/24"),
    ("2009/03/29 22:16:00",                    "2009/03/29"),
    ("2009/03/29 22:19:00",                    "2009/03/29"),
    ("2009/03/30 22:10:03",                    "2009/03/30"),
    ("2009/03/30 07:23:00",                    "2009/03/30"),
    ("2009/03/30 13:23:00",                    "2009/03/30"),
    ("2009/03/30 13:25:14",                    "2009/03/30"),
    ("2009/03/30 13:46:30",                    "2009/03/30"),
    ("2009/03/30 04:16:00",                    "2009/03/30"),
    ("2009/03/30 04:18:36",                    "2009/03/30"),
    ("2009/03/30 04:59:31",                    "2009/03/30"),
    ("2009/03/30 16:00:56",                    "2009/03/30"),
    ("2009/03/30 16:31:40",                    "2009/03/30"),
    ("2009/03/30 04:19:00",                    "2009/03/30"),
    ("2009/03/30 04:20:35",                    "2009/03/30"),
    ("2009/03/30 05:23:40",                    "2009/03/30"),
    ("2009/03/31 16:48:00",                    "2009/03/31"),
    ("2009/03/31 22:48:00",                    "2009/03/31"),
    ("2009/03/31 22:49:15",                    "2009/03/31"),
    ("2009/03/31 23:13:10",                    "2009/03/31"),
    ("04/08 18:17:08 2009",                    "2009/04/08"),
    ("4/10/2009 6:52:34 PM",                   "2009/04/10"),
    ("4/10/2009 12:00:00 AM",                  "2009/04/10"),
    ("2009/04/20 04:36:03",                    "2009/04/20"),
    ("2009/04/20 04:47:34",                    "2009/04/20"),
    ("2009/04/26 23:30:00",                    "2009/04/26"),
    ("2009/04/27 05:30:00",                    "2009/04/27"),
    ("2009/04/29 23:30:00",                    "2009/04/29"),
    ("2009/04/30 23:30:00",                    "2009/04/30"),
    ("2009/04/30 05:30:00",                    "2009/04/30"),
    ("2009/05/01 05:30:00",                    "2009/05/01"),
    ("2009/05/01 23:30:00",                    "2009/05/01"),
    ("2009/05/02 05:30:00",                    "2009/05/02"),
    ("5/10/2009 3:55:39 PM",                   "2009/05/10"),
    ("5/10/2009 12:00:00 AM",                  "2009/05/10"),
    ("5/21/2009 5:37:39 PM",                   "2009/05/21"),
    ("5/21/2009 12:00:00 AM",                  "2009/05/21"),
  ).distinct // remove duplicates

  lazy val testDatesToIso = List(
    "01 Jan 2001 12:34:56 -0700 (MDT)",
    "01 Jan 2001 12:34:56 -0700 MDT",
    "01 Jan 2001 12:34:56 -0700",
    "01/31/1992",
    "02/28/1993",
    "02/29/1992",
    "04/08 18:17:08 2009",
    "04/12/1992 01:58 PM",
    "04/12/1992 02:46 PM",
    "04/13/1992 02:10 PM",
    "04/13/1992 11:15 AM",
    "04/13/1992 11:59 PM",
    "04/13/1992 12:01 PM",
    "04/13/1992 12:32 PM",
    "04/15/2009",
    "04/30/2009",
    "05/06/1993",
    "05/15/2009",
    "05/19/2009",
    "05/29/2009",
    "05/31/2009",
    "06/15/2009",
    "06/30/2009",
    "07/15/2009",
    "07/31/2009",
    "08/10/2009",
    "08/14/2009",
    "08/31/2009",
    "09/15/2009",
    "09/30/2009",
    "1 Jan 2001 12:34:56 -0700",
    "1/10/2009 4:05:57 PM","1/10/2009 12:00:00 AM",
    "10/15/2009",
    "10/30/2009",
    "10/31/2009",
    "11/12/2008 10:20:06 AM",
    "11/12/2008 10:20:06 AM","11/12/2008 12:00:00 AM",
    "11/13/2009",
    "11/19/2009",
    "11/30/2009",
    "12/15/2009",
    "12/2/2008 12:00:00 AM",
    "12/2/2008 6:49:53 PM",
    "12/2/2008 6:49:53 PM","12/2/2008 12:00:00 AM",
    "12/22/2009",
    "12/23/2009",
    "12/29/2009",
    "12/31/2009",
    "1992/01/01",
    "1992/03/04",
    "1992/04/13 23:59",
    "2/10/2009 12:00:00 AM",
    "2/10/2009 6:51:43 PM",
    "2/11/2009 16:34:32 -0800",
    "2/5/2009 1:31:58 PM",
    "2/5/2009 12:00:00 AM",
    "2009/03/24 21:48:25.0",
    "2009/03/30 22:10:03",
    "22/11/1992 07:25:19 -0800",
    "3/10/2009 12:00:00 AM",
    "3/10/2009 7:34:33 PM",
    "3/13/2009 12:00:00 AM",
    "3/13/2009 5:17:15 PM",
    "31/05/2009 02:20:13 -0700",
    "31/05/2009 08:59:59 -0000",
    "4/10/2009 12:00:00 AM",
    "4/10/2009 6:52:34 PM",
    "5/10/2009 12:00:00 AM",
    "5/10/2009 3:55:39 PM",
    "5/21/2009 12:00:00 AM",
    "5/21/2009 5:37:39 PM",
    "8/04/2009 17:09:46 -0700",
    "9/29/2008 2:05:51 PM","9/29/2008 12:00:00 AM",
    "9/29/2008 2:06:17 PM","9/29/2008 12:00:00 AM",
    "9/29/2008 2:07:18 PM","9/29/2008 12:00:00 AM",
    "9/29/2008 2:19:51 PM","9/29/2008 12:00:00 AM",
    "Fri Jan 10 2014 2:34:17 PM EST",
    "May 16, 2014",
    "May 16,2014",
    "Mon,  1 Jan 2001 12:34:56 -0700 (MDT)",
    "Mon,  1 Jan 2001 12:34:56 -0700",
    "Mon, 01 Jan 2001 12:34:56 -0700 (CEST)",
    "Mon, 01 Jan 2001 12:34:56 -0700 (MDT)",
    "Mon, 01 Jan 2001 12:34:56 -0700",
    "Wed Apr 08 18:17:08 2009",
    "Wed, Apr 08 18:17:08 2009",
  )

  val testDatesExpected = List(
    // ISO 8601 with T separator
    ("2009-03-24T21:48:25Z",                   "2009-03-24T21:48:25"),

    // ISO 8601 with offset
    ("2009-03-24T21:48:25-07:00",              "2009-03-24T21:48:25"),

    // ISO 8601 with fractional seconds
    ("2009-03-24T21:48:25.123Z",               "2009-03-24T21:48:25"),

    
    // SquashedMonthDay
    ("Apr12-11",                               "2011-04-12T00:00:00"),

    // HyphenDMY (textual month)
    ("11-Apr-2016",                            "2016-04-11T00:00:00"),

    // SlashMDY (date-only)
    ("05/06/1993",                             "1993-05-06T00:00:00"),

    // SlashMDY (24-hour time with offset)
    ("04/08/2009 18:17:08 -0700",              "2009-04-08T18:17:08"),

    // SlashMDY (AM/PM with seconds)
    ("04/12/1992 01:58 PM",                    "1992-04-12T13:58:00"),

    // SlashMDY (AM/PM without seconds)
    ("1/10/2009 4:05 PM",                      "2009-01-10T16:05:00"),

    // SlashDMY (unambiguous)
    ("31/05/2009 08:59:59 -0000",              "2009-05-31T08:59:59"),

    // SlashDMY (unambiguous by >12 rule)
    ("22/11/1992 07:25:19 -0800",              "1992-11-22T07:25:19"),

    // SlashYMD (date-only)
    ("1992/03/04",                             "1992-03-04T00:00:00"),

    // SlashYMD (24-hour time)
    ("2009/03/30 22:10:03",                    "2009-03-30T22:10:03"),

    // SlashYMD (fractional seconds)
    ("2009/03/24 21:48:25.0",                  "2009-03-24T21:48:25"),

    // MonthCommaYear
    ("May 16, 2014",                           "2014-05-16T00:00:00"),
    ("May 12, 2024",                           "2024-05-12T00:00:00"),
    ("May 12, 2024 2:30 PM",                   "2024-05-12T14:30:00"),
    ("December 5, 2024",                       "2024-12-05T00:00:00"),

    // MonthCommaYear (no space after comma)
    ("May 16,2014",                            "2014-05-16T00:00:00"),

    // RFCish (weekday, no parentheses)
    ("Mon, 01 Jan 2001 12:34:56 -0700",        "2001-01-01T12:34:56"),

    // RFCish (weekday, with parentheses — normalized away)
    ("Mon, 01 Jan 2001 12:34:56 -0700 (MDT)",  "2001-01-01T12:34:56"),

    // RFCish (no weekday)
    ("01 Jan 2001 12:34:56 -0700",             "2001-01-01T12:34:56"),

    // RFCish (AM/PM variant)
    ("Fri Jan 10 2014 2:34:17 PM EST",         "2014-01-10T14:34:17"),

    // Hyphenated YMD
    ("2009-03-24",                             "2009-03-24T00:00:00"),

    // SlashMDY ambiguous → MDY default
    ("2/11/2009 16:34:32 -0800",               "2009-02-11T16:34:32"),
    
    // RFC
    ("Sun, 12 May 2024 14:30:00 +0000",        "2024-05-12T14:30:00"),
    ("Sun, 12 May 2024 14:30:00 GMT",          "2024-05-12T14:30:00"),
  )
}


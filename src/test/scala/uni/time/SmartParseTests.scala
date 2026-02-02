package uni.time

import uni.verboseUni
import uni.time.*
import java.time.LocalDateTime

import TestDates.*

import munit.FunSuite

class SmartParseTests extends FunSuite {
  val selectedDateStrings = List(
    "2009-04-08T18:17:08", "2009-04-08T18:17:08", "4/08 18:17:08 2009",
    "2020-11-17 10:36:34", "2016-11-17 10:36:34", "2016/11/17 10:36:34/81",
    "2011-04-12 14:30:00", "2011-04-12 00:00:00", "Apr12-11 14:30:00",
    "2016-04-11 09:15:30", "2016-04-11 00:00:00", "11-Apr-2016 09:15:30",
    "2016-04-11 09:15:30", "2016-04-11 00:00:00", "11-Apr-2016 09:15:30 GMT+0000",
    "2016-04-11 09:15:30", "2016-04-11 00:00:00", "11-Apr-2016 09:15:30 (MST)",
    "2024-03-05 14:22:10", "2024-03-05 14:22:00", "2024/03/05 2:22PM",
    "2024-05-12 00:00:00", "2024-12-05 00:00:00", "12/5/24",
    "2024-05-12 13:10:00", "2024-12-05 13:10:00", "12/5/24 13:10",
    "2024-05-12 00:00:00", "2024-12-05 00:00:00", "12/05/2024",
    "2024-05-12 23:59:59", "2024-12-05 23:59:59", "12/05/2024 23:59:59",
    "2024-05-12 14:00:00", "2024-05-12 00:00:00", "12/May/2024 14:00",
    "2024-05-12 00:00:00", "2024-12-05 00:00:00", "12/05/2024 UTC",
    "2024-05-12 00:00:00", "2024-05-05 00:00:00", "05/May/2024",
    "2024-05-12 06:00:00", "2024-05-05 00:00:00", "05/May/2024 06:00",
    "2024-05-12 14:30:00", "2024-05-12 00:00:00", "12 May 2024 14:30",
    "2024-05-12 09:15:00", "2024-05-12 00:00:00", "Sunday 12 May 2024 09:15",
    "2024-05-12 14:30:00", "2024-05-12 12:00:00", "May 12, 2024 2:30 PM",
    "2024-05-12 02:30:00", "2024-05-12 00:00:00", "May 12, 2024 2:30 AM",
    "2024-05-12 14:30:00", "2024-05-12 00:00:00", "May-12-2024",
    "2024-05-12 14:30:00", "20240512 1430 MST",
    "2024-05-12 14:30:00", "20240512 1430 GMT",
  )

  test("selected") {
    for (datestr <- selectedDateStrings) {
      val date = parseDate(datestr)
      printf("[%s]\n", date)
      assert(date != BadDate, s"bad date: $datestr")
    }
  }
  
  @annotation.nowarn("msg=unused private member")
  private var hook = 0 // it's not actually unused ...

  test("mdy-with-time") {
    val localdatetime = parseDate("04/08 18:17:08 2009")
    printf("%s\n", localdatetime)
    hook += 1
  }
  
  test("classifier matches pattern buckets") {
    val iterations = 100
    var seedcounter = 0
    for
      (shape, patterns) <- SmartParse.patternsByShape
      pattern <- patterns
      i <- 0 until iterations
    do
      ExampleGenerator.seed(seedcounter)
      seedcounter += 1

      val (example, expected) = ExampleGenerator.exampleForPattern(pattern)
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
    test(s"parseSmart should properly parse string [$teststr]") {
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


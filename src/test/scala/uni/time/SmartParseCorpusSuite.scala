package uni.time

import munit.FunSuite
import java.time.ZoneId

/**
 * Properties inherited from `ChronoParseTests`, restated against `SmartParse` so they
 * outlive `ChronoParse`.
 *
 * Two were worth keeping and one was not: `datesProduceValidEpochAndBeMonotonic` sorted
 * the epochs and then asserted the sorted list was non-decreasing, which is true of any
 * list. Only its `epoch > 0` half tested anything -- and that half is worth having,
 * since `BadDate` is 1900 and therefore negative. The monotonicity claim is restated
 * here as something that can actually fail: parse order must agree with ISO-string
 * order, which for a fixed-width ISO rendering is lexicographic.
 */
class SmartParseCorpusSuite extends FunSuite:

  private val corpus = TestDates.all
  private val zone = ZoneId.of("America/Denver")

  test("every date in the corpus parses") {
    // The guard the ChronoParse fallback used to provide. It rescued nothing, but its
    // absence should still be noticed if SmartParse ever regresses on one of these.
    val unparsed = corpus.filter(s => Set(BadDate, EmptyDate)(parseDateSmart(s)))
    assertEquals(unparsed, Seq.empty[String], s"${unparsed.size} input(s) no longer parse")
  }

  test("rendering to ISO and reparsing is idempotent") {
    // The property the CSV use case rests on: converting a date column to ISO must not
    // change what it means, and reading it back must give the same moment.
    val broken = for
      s   <- corpus
      d1   = parseDateSmart(s)
      if d1 != BadDate && d1 != EmptyDate
      iso  = d1.toString("yyyy-MM-dd HH:mm:ss")
      d2   = parseDateSmart(iso)
      if d1 != d2
    yield s"[$s] -> [$iso] -> $d2, was $d1"
    assertEquals(broken, Seq.empty[String], s"${broken.size} input(s) not idempotent")
  }

  test("no date lands before the epoch") {
    // `BadDate` is 1900-01-02, so a negative epoch catches a silent parse failure that
    // did not go through the sentinel.
    val negative = corpus.filter { s =>
      parseDateSmart(s).atZone(zone).toInstant.toEpochMilli <= 0
    }
    assertEquals(negative, Seq.empty[String], s"${negative.size} input(s) at or before 1970")
  }

  test("chronological order agrees with ISO string order") {
    // The non-vacuous form of the monotonicity check: for a fixed-width ISO rendering,
    // lexicographic order is chronological order, so sorting by one must match the
    // other. Sorting the epochs first -- as the original did -- could not fail.
    val parsed = corpus.map(parseDateSmart).filterNot(d => Set(BadDate, EmptyDate)(d))
    val byInstant = parsed.sortBy(_.atZone(zone).toInstant)
    val byIsoText = parsed.sortBy(_.toString("yyyy-MM-dd HH:mm:ss"))
    assertEquals(byInstant.map(_.toString), byIsoText.map(_.toString))
  }

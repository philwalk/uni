package uni.apps

import munit.FunSuite
import uni.*
import uni.data.*

/** THE RECORD BANDS (`recordbands-2026-09-18.tsv`): the shipped `RecordBand` literals and the
  * typical-year and up-day-share anchors are re-derived from the fixture; a banded row's percentile
  * never contradicts its miss; and a pinned series fixes `seriesReadings` and `recordResamples` to
  * the bit, so the Rust twin's `record_band_tests`, which pins the same values, shows the twins read
  * a record identically. */
class RecordBandSuite extends FunSuite:
  private val fixture = Paths.get("test-data/equity-anchors/recordbands-2026-09-18.tsv")
  private lazy val lines: Vector[String] =
    fixture.lines.toVector.filterNot(l => l.startsWith("#") || l.trim.isEmpty)

  private def row(set: String, name: String): Vector[Double] =
    lines.map(_.split('\t').toVector).find(r => r(0) == set && r(1) == name)
      .getOrElse(fail(s"fixture row [$set $name] missing")).drop(6).map(_.toDouble)

  private val sets = Vector(("sp500", MarketSim.SP500Anchors), ("nasdaq", MarketSim.NasdaqAnchors))

  /** The series both twins pin: uniform draws, no transcendental, with a 6% fall every 97
    * sessions so it has episodes to count. */
  private def pinnedSeries: Array[Double] =
    val rng = new NumPyRNG(20260918L)
    Array.tabulate(3000) { i =>
      val u = rng.nextDouble()
      if i % 97 == 50 then -0.06 else (u - 0.48) * 0.03
    }

  test("the shipped bands are the fixture's rows") {
    assertEquals(lines.head.split('\t').toVector.drop(7), MarketSim.RecordBandPcts.map(p => s"p$p"),
      "the fixture carries the shipped grid")
    for (set, a) <- sets do
      assertEquals(a.recordBands.map(_.name), MarketSim.RecordBandRows,
        s"$set: the literals follow RecordBandRows")
      for b <- a.recordBands do
        val r = row(set, b.name)
        assertEquals(b.record, r.head, s"$set ${b.name}: record")
        assertEquals(b.q, r.tail, s"$set ${b.name}: percentiles")
  }

  test("the typical-year and up-day anchors are the fixture's records") {
    for (set, a) <- sets do
      val ty = row(set, "typical-year vol %").head
      assertEqualsDouble(a.yearVol, ty, 0.1, s"$set: typical year against the record's phase mean")
      val up = row(set, "up-day share %").head
      assertEqualsDouble(a.upShare, up, 0.1, s"$set: up-day share against the record's")
  }

  test("the up-day share counts moving sessions only") {
    // two rises, one fall, two sessions that did not move
    assertEqualsDouble(MarketSim.upShareOf(Array(0.01, -0.02, 0.0, 0.03, 0.0)), 200.0 / 3.0, 1e-12)
    assert(MarketSim.upShareOf(Array(0.0, 0.0)).isNaN)
  }

  test("a percentile never contradicts the band") {
    for (set, a) <- sets; b <- a.recordBands do
      val (lo, hi) = b.band
      val span = b.q(22) - b.q(0)
      for k <- 0 to 400 do
        val x = b.q(0) - 0.1 * span + 1.2 * span * k.toDouble / 400.0
        val p = b.percentile(x).getOrElse(fail("a number places"))
        assertEquals(b.misses(x), !(p >= 5 && p <= 95), s"$set ${b.name}: $x placed at $p%")
      // just past an edge reads past it, never rounded back inside; the edge itself is in
      assert(b.percentile(hi + 1e-9 * math.max(math.abs(hi), 1.0)).exists(_ >= 96))
      assert(b.percentile(lo - 1e-9 * math.max(math.abs(lo), 1.0)).exists(_ <= 4))
      assert(!b.misses(hi) && !b.misses(lo))
      assertEquals(b.percentile(Double.NaN), None)
      assert(b.misses(Double.NaN), s"$set ${b.name}: not a number misses")
  }

  test("a pinned series reads the same in both twins") {
    // `record_band_tests` pins these same values; a change here is a change there
    val r = pinnedSeries
    assertEquals(MarketSim.seriesReadings(r), Vector(
      16.798312848172113, 17.02219625010289, -0.12963037209238956, 11.437126469063255,
      -0.022101390142453572, -0.012940593813227222, 0.7407002578651204, 29.603735914033912,
      50.766666666666666, 0.025507720490428657, 16.8, -15.548714909190897))
    assertEquals(MarketSim.yearVolPhaseMean(r), 16.862007639884688)
    val reads = MarketSim.recordResamples(r, 40, 7L)
    def med(v: Seq[Double]): Double = { val f = MarketSim.finiteSorted(v.toArray); f(f.length / 2) }
    assertEquals((0 until 12).toVector.map(k => med(reads.map(_(k)))), Vector(
      16.78655352771039, 16.877627553056403, -0.16675076546846718, 11.388024993784173,
      -0.02498629581122743, -0.01046514345997916, 0.7513339475330946, 29.880494878185225,
      50.63333333333333, 0.027222579780510212, 16.8, -26.478692229354827))
    assertEquals(MarketSim.recordBandQuantiles(reads.map(_(10))), Vector(
      8.4, 8.4, 8.4, 8.4, 8.4, 8.4, 8.4, 16.8, 16.8, 16.8, 16.8, 16.8, 16.8, 16.8, 16.8,
      16.8, 16.8, 16.8, 25.2, 25.2, 33.6, 33.6, 33.6))
  }

  test("a banded row's real is the record and its miss the band") {
    val w  = MarketSim.namedWorld("0.24.4-nasdaq").get._1
    val a  = MarketSim.NasdaqAnchors
    val st = MarketSim.measure(MarketSim.simPaths(w, 12, 27, 20260918L), 27)
    val rows = MarketSim.fidelityRows(a, st, 12, 20260918L, w)
    for r <- rows do
      a.recordBands.find(_.name == r.name) match
        case Some(b) =>
          assertEquals(r.real, b.record, s"${r.name}: real is the record")
          assertEquals(r.recordBand, Some(b.band))
          assertEquals(r.recordPctile, b.percentile(r.model))
          assertEquals(r.miss, b.misses(r.model), s"${r.name}: miss is the band's")
        case None =>
          assert(r.recordBand.isEmpty && r.recordPctile.isEmpty)
          assertEquals(r.real, r.target, s"${r.name}: an unbanded row's real is its anchor")
    val vr = rows.find(_.name == "variance ratio 60d").getOrElse(fail("row"))
    assertEquals(vr.target, 1.00, "the loss keeps its theory value")
    assert(vr.real < 0.9, s"and `real` is QQQ's own, ${vr.real}")
  }

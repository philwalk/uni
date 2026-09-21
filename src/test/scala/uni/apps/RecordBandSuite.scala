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

  // A LEVEL GATE on a record-banded quantity admits every reading the record's own 5th-95th does:
  // a gate narrower than what the record's history produces fails worlds the record cannot tell
  // from itself.  Return per vol is left out on purpose: its gate is a population value, and a band
  // drawn from the record's blocks would readmit its luckiest decades.
  test("a level gate is never narrower than its record's own spread") {
    for (set, a) <- sets; (name, gate) <- Vector(("equity vol %", a.volBand), ("typical-year vol %", a.yearVolBand)) do
      val b = a.recordBands.find(_.name == name).getOrElse(fail(s"$set carries no $name band"))
      val (p5, p95) = b.spread
      assert(gate._1 <= p5 && gate._2 >= p95, s"$set $name: gate $gate inside the record's $p5-$p95")
  }

  test("the shipped bands are the fixture's rows") {
    val header = lines.head.split('\t').toVector
    assertEquals(header.slice(7, 30), MarketSim.RecordBandPcts.map(p => s"p$p"),
      "the fixture carries the shipped grid")
    assertEquals(header.drop(30), Vector("jointC", "jointLo", "jointHi"), "and the joint band")
    for (set, a) <- sets do
      assertEquals(a.recordBands.map(_.name), MarketSim.RecordBandRows,
        s"$set: the literals follow RecordBandRows")
      for b <- a.recordBands do
        val r = row(set, b.name)
        assertEquals(b.record, r.head, s"$set ${b.name}: record")
        assertEquals(b.q, r.slice(1, 24), s"$set ${b.name}: percentiles")
        assertEquals((b.jointC, b.joint), (r(24), (r(25), r(26))), s"$set ${b.name}: joint band")
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
      // the whole percentiles inside the joint band
      val pLo = math.ceil(100.0 * (0.5 - b.jointC)).toInt
      val pHi = math.floor(100.0 * (0.5 + b.jointC)).toInt
      val span = b.q(22) - b.q(0)
      for k <- 0 to 400 do
        val x = b.q(0) - 0.1 * span + 1.2 * span * k.toDouble / 400.0
        val p = b.percentile(x).getOrElse(fail("a number places"))
        assertEquals(b.misses(x), !(p >= pLo && p <= pHi), s"$set ${b.name}: $x placed at $p%")
      // just past an edge reads past it, never rounded back inside; the edge itself is in
      assert(b.percentile(hi + 1e-9 * math.max(math.abs(hi), 1.0)).exists(_ > pHi))
      assert(b.percentile(lo - 1e-9 * math.max(math.abs(lo), 1.0)).exists(_ < pLo))
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
    val rows = MarketSim.fidelityRows(a, st, None, 27, 12, 20260918L, w)
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

  test("a horizon cut from the ensemble reads as a simulated one") {
    // the shorter horizons come from the ensemble's own paths (`Path.head`), bit for bit what
    // simulating them reads: the S&P's 72 years from a century, the Nasdaq's 27 from 80
    def bits(x: Double): Long = java.lang.Double.doubleToRawLongBits(x)
    for (recipe, set, years) <- Vector(("0.24.4", "sp500", 100), ("0.24.4-nasdaq", "nasdaq", 80)) do
      val w     = MarketSim.namedWorld(recipe).get._1
      val a     = MarketSim.anchorsNamed(set)
      val main  = MarketSim.simPaths(w, 8, years, 7L)
      val st    = MarketSim.measure(main, years)
      val cut   = MarketSim.horizonReadings(a, st, Some(main), years, 8, 7L, w, extremeToo = true)
      val fresh = MarketSim.horizonReadings(a, st, None, years, 8, 7L, w, extremeToo = true)
      assertEquals(cut.banded.keySet, fresh.banded.keySet)
      fresh.banded.foreach((n, v) => assertEquals(bits(cut.banded(n)), bits(v), s"$recipe $n"))
      fresh.extreme.foreach((n, xs) => assertEquals(cut.extreme(n).map(bits), xs.map(bits), s"$recipe $n"))
  }

  test("a banded row is read at its record's own horizon") {
    // the verdict ensemble runs a century; QQQ's band is a 27-year history's spread
    val w     = MarketSim.namedWorld("0.24.4-nasdaq").get._1
    val a     = MarketSim.NasdaqAnchors
    // 24 paths: at 8 the century's median kurtosis sat inside the 27 years' seed noise
    val st100 = MarketSim.measure(MarketSim.simPaths(w, 24, 100, 7L), 100)
    val st27  = MarketSim.measure(MarketSim.simPaths(w, 24, 27, 7L), 27)
    val rows  = MarketSim.fidelityRows(a, st100, None, 100, 24, 7L, w)
    def row(n: String) = rows.find(_.name == n).getOrElse(fail(s"no [$n] row")).model
    assertEquals(row("kurtosis"), st27.kurt, "read on the record's 27 years")
    assert(row("kurtosis") < st100.kurt, "a century's kurtosis runs higher")
    assertEquals(row("bond vol % (24y)"), st100.bondVol * 100.0,
      "an unbanded row keeps the verdict ensemble")
    // and every banded row says so: its `horizonYears` is the length it was read over, the
    // variance ratio's too, whose anchor group reads 25-year fund records
    for r <- rows if r.recordBand.isDefined do
      assertEquals(r.horizonYears, MarketSim.recordBandYears(a, r.name), r.name)
  }

  test("a band is read over the window its record spans") {
    // the variance ratio's anchor group reads 25-year fund records; its band is the index's
    for (set, a) <- sets; b <- a.recordBands do
      val r = lines.map(_.split('\t').toVector).find(r => r(0) == set && r(1) == b.name)
        .getOrElse(fail(s"fixture row [$set ${b.name}] missing"))
      val years = r(3).split("\\.\\.").map(_.take(4).toInt)
      assertEquals(MarketSim.recordBandYears(a, b.name), years(1) - years(0),
        s"$set ${b.name}: read over ${r(3)}")
  }

  test("a row's distance from its record is its band sd or its fitness term") {
    // what `-noregress` compares a candidate with the outgoing recipe on
    val a = MarketSim.NasdaqAnchors
    val w = MarketSim.namedWorld("0.24.4-nasdaq").get._1
    val st = MarketSim.measure(MarketSim.simPaths(w, 8, 27, 7L), 27)
    val ex = MarketSim.extremeScoreStats(a, 8, 7L, w)
    val (_, rows) = MarketSim.fitness(a, st, ex)
    val banded = MarketSim.bandedReadings(a, st, 27, 8, 7L, w)
    val d = MarketSim.recordDistances(a, rows, banded)
    assertEquals(d.length, rows.length)
    for ((name, dist), row) <- d.zip(rows) do
      assertEquals(name, row._1, "in fitness row order")
      a.recordBands.find(_.name == name) match
        case Some(b) =>
          val want = MarketSim.SdRelRef * math.abs(b.percentileExact(banded(name)) - 50.0) / MarketSim.PctPerSd
          assertEqualsDouble(dist, want, 1e-12, name)
        case None => assertEquals(dist, row._4, s"$name: the fitness term")
  }

  test("the exact percentile agrees with the verdict's inside the band and keeps a slope past it") {
    for (set, a) <- sets; b <- a.recordBands do
      val (lo, hi) = b.band
      // interior points: at an edge the verdict's rule never rounds back inside
      for k <- 1 until 40 do
        val x = lo + (hi - lo) * k.toDouble / 40.0
        val rounded = b.percentile(x).getOrElse(fail("a number places"))
        val exact = b.percentileExact(x)
        assert(math.abs(exact - rounded) <= 0.5 + 1e-9, s"$set ${b.name}: $exact vs $rounded")
      val span = b.q(22) - b.q(0)
      assert(b.percentileExact(b.q(22) + span) > 100.0, b.name)
      assert(b.percentileExact(b.q(0) - span) < 0.0, b.name)
      assert(b.percentileExact(Double.NaN).isNaN)
  }

  test("a band prices its miss in its own sd") {
    for (set, a) <- sets do
      val targets = MarketSim.fitTargets(a).map(_._1)
      for b <- a.recordBands do
        val (lo, hi) = b.band
        assert(hi > lo, s"$set ${b.name}: a band with no width")
        // a band row with no reading would cost four sd on every candidate
        assert(targets.contains(b.name), s"$set ${b.name}: no reading")
      val b = a.recordBands(3)
      def term(x: Double): Double = MarketSim.recordBandTerms(a, Map(b.name -> x))(3)._2
      // past the joint band's edge, in the row's own p5-p95 sd
      val (lo, hi) = b.band
      val (s5, s95) = b.spread
      val sd = (s95 - s5) / 3.29
      assertEquals(term(lo), 0.0, "the edge is inside")
      assertEquals(term(0.5 * (lo + hi)), 0.0)
      assertEqualsDouble(term(hi + sd), MarketSim.SdRelRef, 1e-12, "one sd past")
      assertEqualsDouble(term(lo - 2.0 * sd), 2.0 * MarketSim.SdRelRef, 1e-12, "two sd short")
      assertEqualsDouble(term(Double.NaN), 4.0 * MarketSim.SdRelRef, 1e-12, "unmeasurable")
  }

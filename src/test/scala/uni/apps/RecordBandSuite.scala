package uni.apps

import munit.FunSuite
import uni.*
import uni.data.*

/** THE RECORD BANDS (`recordbands-2026-09-26.tsv`): the shipped `RecordBand` literals and the
  * typical-year and up-day-share anchors are re-derived from the fixture; a banded row's percentile
  * never contradicts its miss; and a pinned series fixes `seriesReadings` and `recordResamples` to
  * the bit, so the Rust twin's `record_band_tests`, which pins the same values, shows the twins read
  * a record identically. */
class RecordBandSuite extends FunSuite:
  private def fixtureLines(name: String): Vector[String] =
    Paths.get(s"test-data/equity-anchors/$name").lines.toVector
      .filterNot(l => l.startsWith("#") || l.trim.isEmpty)
  /** the record-band fixture's rows, then the conditional rate rows' (`rateafter-2026-09-30.tsv`,
    * the same columns) without their header */
  private lazy val lines: Vector[String] =
    fixtureLines("recordbands-2026-09-26.tsv") ++ fixtureLines("rateafter-2026-09-30.tsv").drop(1)

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
      assertEquals(a.recordBands.map(_.name),
        MarketSim.RecordBandRows ++ MarketSim.RateBandRows ++ MarketSim.BondBandRows ++ MarketSim.RateAfterRows,
        s"$set: the literals follow RecordBandRows, RateBandRows, BondBandRows, RateAfterRows")
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
      val vt = row(set, "vol-timing edge pts/yr").head
      assertEqualsDouble(a.volTiming, vt, 1e-6, s"$set: vol-timing edge against the record's")
  }

  /** Thirty years of `pinnedSeries`' draws, for the rows a year is one observation of. */
  private def pinnedLongSeries: Array[Double] =
    val rng = new NumPyRNG(20260929L)
    Array.tabulate(7560): i =>
      val u = rng.nextDouble()
      if i % 97 == 50 then -0.06 else (u - 0.47) * 0.03

  // THE MULTI-YEAR ROWS' anchors are `multiyear-2026-09-29.tsv`'s records, row for row in both
  // windows, and each statistic reads a hand-built series as stated.
  // THE SECTOR ROWS read a hand-built panel as stated, and the fixture holds the record's 12-1
  // momentum on 10 industries as the docs quote it.
  test("the sector rows read a hand panel as stated and the fixture is the record") {
    val months = 40
    def ind(x: Double) = Vector.fill(months)(Some(x): Option[Double])
    val p = MarketSim.SectorPanel(Vector(ind(0.02), ind(0.0), ind(-0.02)), Vector.fill(months)(0.0), Vector.fill(months)(0.0))
    val m = MarketSim.sectorMomentum(p, 11, 1, 0)
    assertEquals(m.spreads.length, months - 12)
    assert(math.abs(m.mean - 0.04) < 1e-12 && m.sharePositive == 1.0)
    val (lo, hi) = MarketSim.sectorMomentumBand(m.spreads, 50, 7L)
    assert(math.abs(lo - 0.04) < 1e-12 && math.abs(hi - 0.04) < 1e-12)
    val d = Vector.tabulate(months)(k => Some(if (k / 12) % 2 == 0 then 0.01 else -0.01): Option[Double])
    val (v, n) = MarketSim.sectorTrend(MarketSim.SectorPanel(Vector(d), Vector.fill(months)(0.0), Vector.fill(months)(0.0)),
                                       MarketSim.SectorTrend.Sign12, 0)
    assertEquals(n, months - 12)
    assert(!v.isNaN)
    // constant industries have no correlation to read, and a NaN there is the honest answer
    val (s, ns) = MarketSim.sectorShape(p)
    assert(s(0) > 0.0 && s(1).isNaN)
    assertEquals(ns(0), months)
    val row = Paths.get("test-data/equity-anchors/sectors-2026-09-30.tsv").lines
      .find(_.startsWith("momentum\tindustries10\t12-1\tall\tmean spread")).getOrElse(fail("the 12-1 row"))
    assertEquals(row.split("\t")(5), "0.003923")
  }

  test("the multi-year anchors are the fixture's records and the statistics read as stated") {
    val rows = Paths.get("test-data/equity-anchors/multiyear-2026-09-29.tsv").lines.toVector
      .filterNot(l => l.startsWith("#") || l.trim.isEmpty || l.startsWith("set\t"))
      .map(_.split('\t').toVector)
    assertEquals(rows.length, 24, "two sets, two windows, six rows")
    for (set, a) <- sets
        (names, records) <- Vector((MarketSim.MultiYearRows, a.multiYear),
                                   (MarketSim.MultiYearLongRows, a.multiYearLong))
        (name, got) <- names.zip(records) do
      val r = rows.find(f => f(0) == set && f(1) == name).getOrElse(fail(s"fixture row [$set] $name missing"))
      assertEqualsDouble(got, r(5).toDouble, 1e-6, s"$set: $name against the record's")
    // forty years that alternate +20% and -10%, each year's move made in its first session so a
    // block at any phase holds whole moves: successive years are perfectly opposed, and three of
    // them vary no more than one
    val y = MarketSim.DaysPerYear
    val alt = Array.tabulate(40 * y): i =>
      if i % y != 0 then 0.0 else if (i / y) % 2 == 0 then 0.2 else -0.1
    val m = MarketSim.multiYearReadings(alt)
    assertEqualsDouble(m(0), -1.0, 1e-9, "annual autocorr")
    assert(m(1) > 0.30 && m(1) < 0.37, s"variance ratio 3y ${m(1)}")
    // a constant return: every 3-year window is the mean one, and nothing falls
    val flat = MarketSim.multiYearReadings(Array.fill(10 * y)(0.0004))
    assertEqualsDouble(flat(3), 0.0, 1e-9, "3y p95 excess")
    assert(flat(4).isNaN, "no decline, no gap")
    assertEqualsDouble(flat(5), 0.0, 1e-12, "never under water")
    assert(MarketSim.multiYearReadings(Array.fill(5 * y)(0.0004))(3).isNaN, "under six years, no 3-year tail")
    // three falls of 0.4 log, their peaks ten years and five years apart and the fall's own
    // session, each climbed out of at 0.001 a session: 177 sessions more than 20% under the peak
    // after the first two, and the last 101 sessions after the third
    val r = Array.fill(5 * y)(0.001) ++ Array(-0.4) ++ Array.fill(10 * y)(0.001) ++ Array(-0.4) ++
      Array.fill(5 * y)(0.001) ++ Array(-0.4) ++ Array.fill(100)(0.001)
    val f = MarketSim.multiYearReadings(r)
    assertEqualsDouble(f(4), (10 * y + 1).toDouble / y, 1e-9, "decline gap p90")
    assertEqualsDouble(f(5), (177 + 177 + 101) * 100.0 / (r.length + 1), 1e-9, "under water")
  }

  // THE JOINT BAND holds the histories it is read from: on every row of a window at once, all but
  // about `MultiYearAlpha / 2` of them; and a window is read at its own length.
  test("the multi-year band holds the histories it is read from") {
    val rng = new NumPyRNG(20260929L)
    val reads = Vector.fill(1000)(Vector.fill(6)(rng.randn()))
    val bands = MarketSim.multiYearBands(Vector((MarketSim.MultiYearRows, reads)))
    val inside = reads.count: x =>
      MarketSim.MultiYearRows.zip(x).forall: (n, v) =>
        val (lo, hi) = bands(n)
        v >= lo && v <= hi
    assert(inside >= 940 && inside <= 960, s"$inside of 1000 histories inside every band")
    assert(MarketSim.multiYearBands(Vector((MarketSim.MultiYearRows, reads.take(10)))).isEmpty,
      "too few histories to place a record")
    for a <- Vector(MarketSim.SP500Anchors, MarketSim.NasdaqAnchors) do
      val sims = MarketSim.simPaths(MarketSim.Defaults, 2, a.bubbleYears, MarketSim.DefaultSeed)
      val long = MarketSim.multiYearHistories(a, sims, a.bubbleYears)
      assertEquals(long.map(_._1), Vector(MarketSim.MultiYearLongRows), s"${a.name}: one window at the long horizon")
      assert(MarketSim.multiYearHistories(a, sims, 3).isEmpty)
  }

  test("a pinned series reads the same multi-year rows in both twins") {
    // `record_band_tests` pins these same values; a change here is a change there
    val r = pinnedLongSeries
    assertEquals(MarketSim.multiYearReadings(r), Vector(
      -0.027652854715084944, 1.0932546095255011, 1.3537020801900945, 0.48959164694365975,
      9.384920634920634, 19.785742626636686))
    // the joint band of forty twenty-year stretches of it, as `multiYearBands` reads a world's
    // histories
    val reads = Vector.tabulate(40)(k => MarketSim.multiYearReadings(r.slice(k * 60, k * 60 + 5040)))
    val (c, edges) = MarketSim.recordBandJoint(reads, Vector.range(0, 6), MarketSim.MultiYearAlpha / 2.0)
    assertEquals(c, 0.48750000000000004)
    assertEquals(edges, Vector(
      (-0.2628711828928611, 0.013658019501475917),
      (0.828774741719819, 1.2935407944701058),
      (0.873770166713951, 1.7718478665236999),
      (0.3648760869405728, 0.5040042264737004),
      (7.142857142857143, 9.384920634920634),
      (3.3326720888712558, 22.019440587185084)))
  }

  // THE BUBBLE COUPLING's anchors are `bubblebust-2026-09-24.tsv`'s records, and the statistic
  // reads a hand-built series as stated: one 3-year run-up of +1.0 into a 50% fall counts, a high
  // without three years behind it does not, and a series with no 40% fall has no reading.
  test("the bubble coupling anchors are the fixture's records and the statistic reads as stated") {
    val rows = Paths.get("test-data/equity-anchors/bubblebust-2026-09-24.tsv").lines.toVector
      .filterNot(l => l.startsWith("#") || l.trim.isEmpty || l.startsWith("set	"))
      .map(_.split('	').toVector)
    for (set, a) <- sets do
      for (name, got) <- Vector(("bubble coupling 3y", a.bubbleCoupling), ("largest 3y run-up", a.runUp3y),
                                ("longest calm stretch", a.calmStretch)) do
        val r = rows.find(f => f(0) == set && f(1) == name).getOrElse(fail(s"fixture row [$set] $name missing"))
        assertEqualsDouble(got, r(5).toDouble, 1e-6, s"$set: $name against the record's")
    // the run-up reads the best 3-year window and the calm stretch the longest run inside 20% of
    // the peak: 4 flat years, then +1.0 over 3 years, a 30% fall, then 2 flat years
    val hh = MarketSim.BubbleRunup
    val rr = Array.fill(4 * hh / 3)(0.0) ++ Array.fill(hh)(1.0 / hh) ++ Array(-0.4) ++ Array.fill(2 * hh / 3)(0.0)
    assertEqualsDouble(MarketSim.runUp3yOf(rr), 1.0, 1e-9, "run-up")
    assertEqualsDouble(MarketSim.calmStretchOf(rr), (4 * hh / 3 + hh + 1).toDouble, 0.5, "calm")
    assert(MarketSim.runUp3yOf(rr.take(hh - 1)).isNaN, "under three years, no run-up")
    assertEqualsDouble(MarketSim.calmStretchOf(Array.fill(100)(0.001)), 101.0, 0.5, "never 20% down")
    val h = MarketSim.BubbleRunup
    val r = Array.fill(h)(0.0) ++ Array.fill(h)(1.0 / h) ++ Array(-0.7) ++ Array.fill(h)(1.4 / h)
    val c = MarketSim.bubbleCouplingOf(r)
    assert(c > 0.0 && c < 1.0, s"coupling $c")
    assert(MarketSim.bubbleCouplingOf(Array.fill(4 * h)(0.001)).isNaN, "no 40% fall, no reading")
    val early = Array.fill(100)(0.01) ++ Array(-0.7) ++ Array.fill(4 * h)(0.0001)
    assert(MarketSim.bubbleCouplingOf(early).isNaN, "a peak without three years behind it")
  }

  // THE RATE ROWS' anchors are the fixture's records, and the statistic reads as stated: the mean
  // in percent and the share of sessions under the floor.
  test("the rate rows' anchors are the fixture's records and the statistic reads as stated") {
    for (set, a) <- sets do
      assertEquals(a.shortRate, row(set, "short rate %").head, s"$set: short rate")
      assertEquals(a.rateFloor, row(set, "rate floor share %").head, s"$set: floor share")
      assertEquals(MarketSim.recordBandYears(a, "short rate %"), a.rateYears, s"$set: horizon")
      assertEquals(a.postRate, row(set, "post-trough rate %").head, s"$set: post-trough rate")
      assertEquals(a.postFloor, row(set, "post-trough floor share %").head, s"$set: post-trough floor share")
      assertEquals(MarketSim.recordBandYears(a, "post-trough rate %"), a.rateYears,
        s"$set: the conditional rows read the rate window")
      assertEquals(a.bondDepth, row(set, "bond depth vs vol").head, s"$set: bond depth")
      assertEquals(MarketSim.recordBandYears(a, "bond depth vs vol"), a.bondYears, s"$set: bond horizon")
    val path = Array(0.0, 0.004, 0.005, 0.01, 0.02, 0.03, 0.04, 0.05)
    val rr = MarketSim.rateReadings(path)
    assertEqualsDouble(rr(0), 1.9875, 1e-12, s"mean ${rr(0)}")
    assertEqualsDouble(rr(1), 25.0, 1e-12, s"floor share ${rr(1)}")
  }

  // THE CONDITIONAL RATE ROWS on a hand path: two 20% declines whose post-trough windows overlap,
  // the union of the sessions after either trough (never the trough itself), the rate's mean and
  // floor share over them; NaN where no decline reaches 20%; and the paired resampler on a pinned
  // series, the values the Rust twin's `record_band_tests` pin.
  test("the conditional rate rows read the sessions after each trough and only those") {
    val px   = Array(1.0, 1.1, 0.8, 0.85, 0.9, 1.2, 1.0, 0.7, 0.9, 1.3)
    val rate = Array(0.05, 0.05, 0.05, 0.001, 0.002, 0.004, 0.006, 0.01, 0.02, 0.03)
    val rr = MarketSim.rateAfterReadings(px, rate)
    assertEqualsDouble(rr(0), 0.073 / 7.0 * 100.0, 1e-12, s"mean ${rr(0)}")
    assertEqualsDouble(rr(1), 300.0 / 7.0, 1e-12, s"floor ${rr(1)}")
    val calm = MarketSim.rateAfterReadings(Array(1.0, 1.1, 1.0, 1.05, 0.95, 1.2), rate.take(6))
    assert(calm(0).isNaN && calm(1).isNaN, "no decline, no reading")
    val r = (1 until px.length).map(i => math.log(px(i) / px(i - 1))).toArray
    val off = MarketSim.rateAfterOfReturns(r, rate.drop(1))
    assertEqualsDouble(off(0), rr(0), 1e-9, "off the returns, the first level dropped")
    assertEqualsDouble(off(1), rr(1), 1e-9, "and the rate aligned with it")
    // uniform draws, no transcendental, so both twins read the same bits
    val rng = new NumPyRNG(7L)
    val rs = Array.fill(700)((rng.nextDouble() - 0.52) * 0.04)
    val rates = Array.fill(700)(0.001 + 0.02 * rng.nextDouble())
    val reads = MarketSim.rateAfterResamples(rs, rates, 3, 5L)
    assertEquals(reads.length, 3)
    val pin = Vector(1.222787483729952, 12.5, 1.145010398988397, 21.951219512195124)
    for (got, want) <- Vector(reads(0)(0), reads(0)(1), reads(1)(0), reads(1)(1)).zip(pin) do
      assertEqualsDouble(got, want, 1e-12, s"$got vs $want")
  }

  // THE FLOOR BINDS once the mean is low: the default world's rate never reaches 0.5%, and the
  // same world at a 0.5% mean spends a share of its sessions there, which is what the row grades.
  test("the rate floor binds when the mean is low") {
    val seed = MarketSim.DefaultSeed
    val base = MarketSim.measure(MarketSim.simPaths(MarketSim.Defaults, 4, 30, seed), 30)
    val low  = MarketSim.Defaults.copy(rateMean = 0.005)
    val st   = MarketSim.measure(MarketSim.simPaths(low, 4, 30, seed), 30)
    assertEquals(base.rateFloor, 0.0, "the default's rate reaches the floor")
    assert(st.rateFloor > 5.0 && st.shortRate < base.shortRate,
      s"at a 0.5% mean the floor share reads ${st.rateFloor} and the mean ${st.shortRate} (default ${base.shortRate})")
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
      50.766666666666666, 0.025507720490428657, 16.8, -15.548714909190897, -2.6641426084559097,
      0.6469573924082413, 0.46904973503687153))
    assertEquals(MarketSim.yearVolPhaseMean(r), 16.862007639884688)
    val reads = MarketSim.recordResamples(r, 40, 7L)
    def med(v: Seq[Double]): Double = { val f = MarketSim.finiteSorted(v.toArray); f(f.length / 2) }
    assertEquals((0 until 15).toVector.map(k => med(reads.map(_(k)))), Vector(
      16.78655352771039, 16.877627553056403, -0.16675076546846718, 11.388024993784173,
      -0.02498629581122743, -0.01046514345997916, 0.7513339475330946, 29.880494878185225,
      50.63333333333333, 0.027222579780510212, 16.8, -26.478692229354827, -3.036702249727999,
      0.6332136135562098, 0.5270138283992784))
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
      // interior points: at an edge the verdict's rule never rounds back inside -- and a band
      // whose edge sits under half a percentile point from the resamples' end (the bond row's
      // one-row family) bumps a reading just inside it by up to one point rather than half
      for k <- 1 until 40 do
        val x = lo + (hi - lo) * k.toDouble / 40.0
        val rounded = b.percentile(x).getOrElse(fail("a number places"))
        val exact = b.percentileExact(x)
        assert(math.abs(exact - rounded) <= 1.0 + 1e-9, s"$set ${b.name}: $exact vs $rounded")
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

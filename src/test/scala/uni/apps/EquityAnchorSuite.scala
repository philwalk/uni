package uni.apps

import munit.FunSuite
import uni.*

/**
 * The equity depth relation's constants are FITTED NUMBERS. This re-derives every one of them from
 * the checked-in anchors, so `EquityD5Corr`/`EquityD10Corr`/`EquityD20Corr`, `EquityVolSupport` and
 * the two gate bands are derivable rather than asserted, and a re-measurement that moves the data
 * fails here instead of silently disagreeing with the code that still carries the old fit.
 *
 * The Rust twin carries the same checks in `equity_anchor_tests`, against the same file.
 *
 * The two claims that are ABOUT THE FORM rather than the fit are pinned here too, because they are
 * the reason to believe a relation stated this way at all: that the correction reaches 1.00 at the
 * top of the real volatility range (the most volatile equity markets spend random-walk time under
 * water), and that the deep rung's relation is the one that does not transport, which is why it is
 * a fit target but not a gate band.
 */
class EquityAnchorSuite extends FunSuite:

  val Anchors = "test-data/equity-anchors/yahoo-2026-08-24.tsv"

  case class Row(window: String, ticker: String, vol: Double, rv: Double,
                 d5: Double, d10: Double, d20: Double):
    def rung(r: Double): Double = if r == 0.05 then d5 else if r == 0.10 then d10 else d20

  /** Empty where the fixture is absent, which is a skip and not a failure: a source tarball ships
    * without `test-data/`, and asserting there would fail for no benefit. */
  lazy val rows: Vector[Row] =
    val p = Anchors.asPath
    if !p.exists then Vector.empty
    else
      p.lines.toVector
        .filter(l => !l.startsWith("#") && !l.startsWith("window\t") && l.trim.nonEmpty)
        .map { l =>
          val f = l.split("\t")
          val vol = f(3).toDouble
          Row(f(0), f(1), vol, f(7).toDouble / vol, f(4).toDouble, f(5).toDouble, f(6).toDouble)
        }

  /** The block the relation is fitted from: the warm-peak re-measurement, whose peaks are seeded
    * from each instrument's full prior history. The cold `w2001` block is retained in the fixture
    * as the measurement of what truncation costs, and must never be fitted from. */
  def fitBlock: Vector[Row] = rows.filter(_.window == "w2001w")

  /** The independent window, 17 instruments over 1996-2026. */
  def checkBlock: Vector[Row] = rows.filter(_.window == "w1996")

  val Rungs = Vector(
    (0.05, "EquityD5Corr",  MarketSim.EquityD5Corr),
    (0.10, "EquityD10Corr", MarketSim.EquityD10Corr),
    (0.20, "EquityD20Corr", MarketSim.EquityD20Corr))

  /** Least squares on the LOG ratio, by Gauss-Newton — the estimator the constants were fitted
    * with, and the reason is in `EquityD10Corr`: the quantity is graded as a ratio, and OLS on the
    * raw ratio leaves the deep rung's median real instrument at 0.91 of its own line. */
  def logFit(rs: Vector[Row], rung: Double): (Double, Double) =
    var a = 0.4
    var b = 0.02
    for _ <- 0 until 200 do
      var j00 = 0.0; var j01 = 0.0; var j11 = 0.0; var g0 = 0.0; var g1 = 0.0
      for r <- rs do
        val c = math.max(1e-6, a + b * r.vol)
        val resid = math.log(r.rung(rung) / (c * MarketSim.gbmDepthShare(rung, r.vol, r.rv)))
        val da = -1.0 / c
        val db = -r.vol / c
        j00 += da * da; j01 += da * db; j11 += db * db
        g0 += da * resid; g1 += db * resid
      val det = j00 * j11 - j01 * j01
      if math.abs(det) > 1e-18 then
        a -= (j11 * g0 - j01 * g1) / det
        b -= (j00 * g1 - j01 * g0) / det
    (a, b)

  /** A constant written at `dp` decimals IS the fit, rounded to the precision it is written at. */
  def roundsTo(fit: Double, dp: Int, constant: Double): Boolean =
    val scale = math.pow(10, dp.toDouble)
    math.abs(math.round(fit * scale) / scale - constant) < 1e-12

  def ratios(rs: Vector[Row], rung: Double, corr: (Double, Double)): Vector[Double] =
    rs.map(r => r.rung(rung) /
      MarketSim.equityDepthExpected(rung, corr, r.vol, r.rv)).sorted

  test("every rung's correction re-fits to the shipped constants") {
    assume(rows.nonEmpty, s"$Anchors not present in this tree (source tarball?)")
    assertEquals(fitBlock.size, 35, "the relation is fitted on the 35 warm-peak instruments")
    for (rung, name, corr) <- Rungs do
      val (a, b) = logFit(fitBlock, rung)
      assert(roundsTo(a, 4, corr._1),
        s"re-fitting the ${rung * 100}%% rung on $Anchors gives intercept $a, which does not " +
        s"round to MarketSim.$name._1 (${corr._1}). Either the anchors were re-measured and the " +
        "constant was not updated, or the constant was changed without the data.")
      assert(roundsTo(b, 5, corr._2),
        s"re-fitting the ${rung * 100}%% rung gives slope $b, which does not round to " +
        s"MarketSim.$name._2 (${corr._2}).")
  }

  test("the median real instrument sits at 1.00 of its own relation") {
    assume(rows.nonEmpty, s"$Anchors not present in this tree (source tarball?)")
    // This is what the log-ratio estimator buys, and it is the property that keeps the target of
    // 1.00 honest: a median away from 1.00 would mean a target of 1.00 asks the model to differ
    // from a typical real fund, which is the defect the relation replaced.
    for (rung, name, corr) <- Rungs do
      val med = ratios(fitBlock, rung, corr).apply(fitBlock.size / 2)
      assert(math.abs(med - 1.0) < 0.02,
        s"the median real ratio at the ${rung * 100}%% rung is $med, not 1.00, so $name is " +
        "no longer centred on the instruments it was fitted from")
  }

  test("the correction reaches random-walk time at the top of the real volatility range") {
    assume(rows.nonEmpty, s"$Anchors not present in this tree (source tarball?)")
    // The reason to believe the FORM. All three rungs land here independently; if a re-measurement
    // breaks it, the relation is no longer "real markets recover faster than chance, and the
    // fastest markets are the calmest" and the comment that says so has to change.
    val topVol = MarketSim.EquityVolSupport._2
    for (rung, name, corr) <- Rungs do
      val c = corr._1 + corr._2 * topVol
      assert(math.abs(c - 1.0) < 0.05,
        s"$name's correction reads $c at the top of the real volatility range ($topVol%%), not " +
        "~1.00: the most volatile real equity markets no longer spend random-walk time under water")
  }

  test("the support is the anchors' own volatility range") {
    assume(rows.nonEmpty, s"$Anchors not present in this tree (source tarball?)")
    val lo = fitBlock.map(_.vol).min
    val hi = fitBlock.map(_.vol).max
    assertEqualsDouble(lo, MarketSim.EquityVolSupport._1, 1e-9,
      "EquityVolSupport's floor is not the fitted instruments' lowest volatility")
    assertEqualsDouble(hi, MarketSim.EquityVolSupport._2, 1e-9,
      "EquityVolSupport's ceiling is not the fitted instruments' highest volatility")
  }

  test("the two graded bands admit every real instrument in both windows") {
    assume(rows.nonEmpty, s"$Anchors not present in this tree (source tarball?)")
    // The bands are a SCOPE statement: these funds are what the relation is about, so a band that
    // excluded one of them would be calling a real equity fund unrealistic.
    for (rung, name, corr, band) <- Vector(
          (0.05, "EquityD5Band", MarketSim.EquityD5Corr, MarketSim.EquityD5Band),
          (0.10, "EquityD10Band", MarketSim.EquityD10Corr, MarketSim.EquityD10Band)) do
      val all = ratios(fitBlock, rung, corr) ++ ratios(checkBlock, rung, corr)
      assert(all.min > band._1 && all.max < band._2,
        s"$name ${band} does not admit every real instrument: the ratios run " +
        f"${all.min}%.3f..${all.max}%.3f")
  }

  test("the deep rung is not gated, because no band there could fail") {
    assume(rows.nonEmpty, s"$Anchors not present in this tree (source tarball?)")
    // Recorded as a test so the omission reads as a decision rather than an oversight, and so that
    // a re-measurement which TIGHTENS the deep rung tells someone it can now be graded.
    val all = ratios(fitBlock, 0.20, MarketSim.EquityD20Corr) ++
              ratios(checkBlock, 0.20, MarketSim.EquityD20Corr)
    assert(all.max / all.min > 3.0,
      f"the 20%% rung's real ratios now span only ${all.min}%.2f..${all.max}%.2f; a band there " +
      "could discriminate, so it should be gated like the other two")
    val gated = MarketSim.gateChecks(MarketSim.SP500Anchors,
      MarketSim.measure(MarketSim.simPaths(MarketSim.Defaults, 4, 20, 1L), 20)).map(_._1)
    assert(!gated.exists(_.contains("d20")),
      s"a d20 gate band has appeared in ${gated.filter(_.contains("d20"))}")
  }

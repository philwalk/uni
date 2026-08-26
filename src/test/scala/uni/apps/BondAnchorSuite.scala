package uni.apps

import munit.FunSuite
import uni.*

/**
 * The bond relations' constants are FITTED NUMBERS, and until this suite nothing in the repo could
 * re-derive them: the coefficients were written down and the measurements they came from lived in a
 * prose table. This closes that by re-fitting from the checked-in anchors, so the constants are
 * derivable rather than asserted, and a re-measurement that moves a line fails here instead of
 * silently disagreeing with the code that still carries the old one.
 *
 * The Rust twin carries the same checks in `bond_anchor_tests`, against the same file and without
 * needing a JVM — which is why the fixture is committed rather than generated.
 */
class BondAnchorSuite extends FunSuite:

  val Anchors = "test-data/bond-anchors/ishares-2026-08-22.tsv"

  case class Fund(kind: String, duration: Double, annVol: Double, d10: Double)

  /** Empty where the fixture is absent, which is a skip and not a failure: a source tarball ships
    * without `test-data/`, and asserting there would fail for no benefit. */
  lazy val funds: Vector[Fund] =
    val p = Anchors.asPath
    if !p.exists then Vector.empty
    else
      p.lines.toVector
        .filter(l => !l.startsWith("#") && !l.startsWith("fund\t") && l.trim.nonEmpty)
        .map { l =>
          val f = l.split("\t")
          Fund(f(1), f(2).toDouble, f(3).toDouble, f(5).toDouble)
        }

  def treasuries: Vector[Fund] = funds.filter(_.kind == "treasury")

  /** Ordinary least squares, returning `(intercept, slope)`. */
  def ols(xs: Seq[Double], ys: Seq[Double]): (Double, Double) =
    val n   = xs.size.toDouble
    val mx  = xs.sum / n
    val my  = ys.sum / n
    val sxy = xs.zip(ys).map((x, y) => (x - mx) * (y - my)).sum
    val sxx = xs.map(x => math.pow(x - mx, 2)).sum
    val slope = sxy / sxx
    (my - slope * mx, slope)

  /** A constant written at `dp` decimals IS the fit, rounded to the precision it is written at.
    * Comparing that way rather than with an invented epsilon means the assertion states exactly the
    * claim the source makes and nothing looser. */
  def roundsTo(fit: Double, dp: Int, constant: Double): Boolean =
    val scale = math.pow(10, dp.toDouble)
    math.abs(math.round(fit * scale) / scale - constant) < 1e-12

  test("the depth line re-fits to the shipped constants") {
    assume(funds.nonEmpty, s"$Anchors not present in this tree (source tarball?)")
    assertEquals(treasuries.size, 5, "the depth line is fitted on five Treasuries")
    val (intercept, slope) = ols(treasuries.map(_.annVol), treasuries.map(_.d10))
    assert(roundsTo(slope, 4, MarketSim.BondD10Slope),
      s"re-fitting d10 on annVol over the Treasury anchors gives slope $slope, which does not " +
      s"round to MarketSim.BondD10Slope (${MarketSim.BondD10Slope}). Either the anchors were " +
      "re-measured and the constant was not updated, or the constant was changed without the data.")
    assert(roundsTo(intercept, 4, MarketSim.BondD10Intercept),
      s"re-fitting gives intercept $intercept, which does not round to " +
      s"MarketSim.BondD10Intercept (${MarketSim.BondD10Intercept}).")
  }

  test("the volatility line re-fits to the documented coefficients") {
    // Not used by any code path -- it is the justification the `SigmaNBond` comment gives for
    // scaling the noise with duration, so it is a claim in prose that can rot. An intercept that
    // rounds to zero is the whole point of it: a zero-duration bond is cash.
    assume(funds.nonEmpty, s"$Anchors not present in this tree (source tarball?)")
    val (intercept, slope) = ols(treasuries.map(_.duration), treasuries.map(_.annVol))
    assert(roundsTo(slope, 3, 0.937),
      s"vol-on-duration slope $slope no longer rounds to the documented 0.937")
    assert(roundsTo(intercept, 2, -0.07),
      s"vol-on-duration intercept $intercept no longer rounds to the documented -0.07 -- the " +
      "near-zero intercept is why SigmaNBond scales with duration at all")
  }

  test("the supports and the ladder come from the anchors") {
    // The support ranges and the ladder are FIXTURE-DERIVED VALUES written as literals, and the
    // re-fit tests alone would let them drift: a re-measured SHY duration would move the fitted
    // lines (caught) while leaving the supports and the short rung stale (previously uncaught).
    // Every rung must be derived from the anchors or deliberately, checkably past them.
    assume(funds.nonEmpty, s"$Anchors not present in this tree (source tarball?)")
    val dur = treasuries.map(_.duration)
    val vol = treasuries.map(_.annVol)
    assertEquals(MarketSim.BondDurSupport, (dur.min, dur.max),
      "BondDurSupport is not the Treasury anchors' duration range")
    assertEquals(MarketSim.BondVolSupport, (vol.min, vol.max),
      "BondVolSupport is not the Treasury anchors' volatility range")
    val agg = funds.find(_.kind == "blend")
      .getOrElse(fail("the fixture carries the Aggregate row"))
    assertEquals(MarketSim.DurationLadder(0), dur.min,
      "the short rung must be the shortest anchor fund's duration")
    assertEquals(MarketSim.DurationLadder(1), agg.duration,
      "the intermediate rung must be the Aggregate's duration")
    assertEquals(MarketSim.DurationLadder(2), MarketSim.DurationRef,
      "one rung must be the world every other report describes")
    assert(MarketSim.DurationLadder(3) > dur.max,
      "the top rung is DELIBERATELY past the anchors; inside them it tests nothing extra")
  }

  test("the band admits Treasuries and investment grade but not high yield") {
    // The band is a SCOPE decision, not only a tolerance, and nothing else in the repo says so in a
    // form that can fail. Widening it far enough to admit high yield would silently bring an asset
    // class this model has no channel for into the gate's "level readable" verdict.
    assume(funds.nonEmpty, s"$Anchors not present in this tree (source tarball?)")
    def ratio(f: Fund): Double =
      val expected = math.max(0.0, MarketSim.BondD10Slope * f.annVol + MarketSim.BondD10Intercept)
      if expected <= 0.0 then Double.NaN else f.d10 / expected
    for f <- funds do
      val r = ratio(f)
      val inside = r > MarketSim.BondD10Band._1 && r < MarketSim.BondD10Band._2
      if r.isNaN then
        // SHY sits below the line's zero crossing, so it has no ratio at all -- the same `n/a` cell
        // `-crossasset` reports at the short rung.
        assert(f.annVol < MarketSim.BondD10Zero,
          s"a fund with no ratio must be one the line cannot reach, but ${f.kind} reads ${f.annVol}")
      else if f.kind == "credit-hy" then
        assert(!inside,
          s"high yield ($r) is inside the band -- it is out of scope until there is a credit " +
          "channel, and the band is what records that")
      else
        assert(inside, s"a fund at duration ${f.duration} reads $r, outside the band")
  }

package uni.apps

import munit.FunSuite
import uni.*

/**
 * `VarRatioBands` and `VarRatioSlopeBands` are FITTED NUMBERS in the same sense the depth
 * relation's constants are: the real cross-section's own ranges, rounded outward. This re-derives
 * every bound from the checked-in readings, so a band widened to admit a world fails here instead
 * of quietly becoming a band that grades nothing.
 *
 * The Rust twin carries the same checks in `persistence_anchor_tests`, against the same file.
 *
 * The claim ABOUT THE FORM is pinned here too, because it is the reason the bands are shared
 * across anchor sets while volatility and return per volatility are carried per asset: what
 * separates these readings is the era, not the index.
 */
class PersistenceAnchorSuite extends FunSuite:

  val Fixture = "test-data/equity-anchors/persistence-2026-09-02.tsv"

  case class Row(window: String, ticker: String, kind: String, years: Double,
                 vr: Map[Int, Double])

  /** Empty where the fixture is absent, which is a skip and not a failure: a source tarball ships
    * without `test-data/`, and asserting there would fail for no benefit. */
  lazy val rows: Vector[Row] =
    val p = Fixture.asPath
    if !p.exists then Vector.empty
    else
      p.lines.toVector
        .filter(l => !l.startsWith("#") && !l.startsWith("window\t") && l.trim.nonEmpty)
        .map { l =>
          val f = l.split("\t")
          Row(f(0), f(1), f(2), f(4).toDouble,
              Map(20 -> f(5).toDouble, 60 -> f(6).toDouble, 120 -> f(7).toDouble, 250 -> f(8).toDouble))
        }

  /** The rounding step the bands are stated at. Outward from the observed range, never inward: a
    * bound that excluded a real reading would be a band asserting that a real market is not one. */
  val Step = 0.05

  def outward(x: Double, up: Boolean): Double =
    val n = if up then math.ceil(x / Step) else math.floor(x / Step)
    math.round(n * Step * 1e6) / 1e6

  test("every rung's envelope is the real range rounded outward") {
    if rows.nonEmpty then
      assertEquals(MarketSim.VarRatioBands.map(_._1), MarketSim.VarRatioLadder)
      for (q, lo, hi) <- MarketSim.VarRatioBands do
        val xs = rows.map(_.vr(q))
        assertEqualsDouble(lo, outward(xs.min, up = false), 1e-9,
          f"vr$q: the low bound no longer follows from the fixture: readings start at ${xs.min}%.3f")
        assertEqualsDouble(hi, outward(xs.max, up = true), 1e-9,
          f"vr$q: the high bound no longer follows from the fixture: readings reach ${xs.max}%.3f")
      assertEquals(MarketSim.VarRatioBands.find(_._1 == MarketSim.VarRatioQ).map(b => (b._2, b._3)),
        Some((0.50, 1.20)), "the loss row's rung carries the 60-session envelope")
  }

  test("every slope band is the real range rounded outward") {
    if rows.nonEmpty then
      for (a, b, lo, hi) <- MarketSim.VarRatioSlopeBands do
        val xs = rows.map(r => r.vr(b) - r.vr(a))
        assertEqualsDouble(lo, outward(xs.min, up = false), 1e-9, f"slope $a->$b low: readings start at ${xs.min}%.3f")
        assertEqualsDouble(hi, outward(xs.max, up = true), 1e-9, f"slope $a->$b high: readings reach ${xs.max}%.3f")
  }

  test("the profile admits every real reading") {
    // Implied by the rules above and asserted anyway, because this is the property that matters:
    // the gate uses STRICT inequalities, so a bound landing exactly on a real reading would fail
    // the market that produced it.
    for r <- rows do
      for (q, lo, hi) <- MarketSim.VarRatioBands do
        assert(r.vr(q) > lo && r.vr(q) < hi,
          f"${r.ticker} over ${r.window} reads vr$q ${r.vr(q)}%.3f, outside the envelope the gate enforces")
      for (a, b, lo, hi) <- MarketSim.VarRatioSlopeBands do
        val sl = r.vr(b) - r.vr(a)
        assert(sl > lo && sl < hi,
          f"${r.ticker} over ${r.window} has slope $a->$b $sl%+.3f, outside the band the gate enforces")
  }

  test("the profile row fails a world the boxes admit") {
    // The reason the slopes exist: 0.70 at 20 sessions and 1.15 at 60 are each inside their
    // envelope and no real series has that shape.
    val inside  = MarketSim.measure(MarketSim.simPaths(MarketSim.Defaults, 4, 30, MarketSim.DefaultSeed), 30)
    val shaped  = inside.copy(vr20 = 0.70, vr60 = 1.15, vr120 = 1.15, vr250 = 1.15)
    val (name, pass, cls) = MarketSim.varRatioProfileCheck(shaped)
    assert(!pass, s"a +0.45 slope between the short rungs must fail the profile: $name")
    assertEquals(cls, MarketSim.GateClass.Fidelity)
    assert(name.contains("20d 0.65-1.20") && name.contains("20->60 -0.25..+0.10"),
      s"the row's name must carry the bounds it enforces: $name")
  }

  test("the era separates these readings and the index does not") {
    // Why the bands are shared rather than carried per asset. Two indices as different as the
    // Nasdaq-100 and the S&P over the same era agree far more closely than one index does with
    // itself across eras -- so a per-asset band would encode a difference the record does not show,
    // and would have to be invented for every new anchor set.
    if rows.nonEmpty then
      def at(w: String, t: String) = rows.find(r => r.window == w && r.ticker == t).map(_.vr(60))
      (at("wfull", "QQQ"), at("wfull", "SPY"), at("c1926", "CRSP-VW"), at("c1990", "CRSP-VW")) match
        case (Some(qqq), Some(spy), Some(century), Some(modern)) =>
          val acrossIndex = math.abs(qqq - spy)
          val acrossEra   = math.abs(century - modern)
          assert(acrossIndex < acrossEra / 2.0,
            f"QQQ and SPY now differ by $acrossIndex%.3f against $acrossEra%.3f between the CRSP " +
            "century and 1990-2026. If the index has become the larger axis, the band belongs in " +
            "Anchors, per asset, and this test is the one that says so.")
        case _ => fail("the fixture no longer carries the four rows this claim is pinned on")
  }

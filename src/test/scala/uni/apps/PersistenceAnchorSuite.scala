package uni.apps

import munit.FunSuite
import uni.*

/**
 * `VarRatioBand` is a FITTED NUMBER in the same sense the depth relation's constants are: it is
 * the real cross-section's own range, rounded outward. This re-derives both bounds from the
 * checked-in readings, so the band is derivable rather than asserted, and a band widened to admit
 * a world fails here instead of quietly becoming a band that grades nothing.
 *
 * The Rust twin carries the same checks in `persistence_anchor_tests`, against the same file.
 *
 * The claim ABOUT THE FORM is pinned here too, because it is the reason the band is shared across
 * anchor sets while volatility and return per volatility are carried per asset: what separates
 * these readings is the era, not the index.
 */
class PersistenceAnchorSuite extends FunSuite:

  val Fixture = "test-data/equity-anchors/persistence-2026-08-29.tsv"

  case class Row(window: String, ticker: String, kind: String, years: Double,
                 vr20: Double, vr60: Double)

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
          Row(f(0), f(1), f(2), f(4).toDouble, f(5).toDouble, f(6).toDouble)
        }

  /** The rounding step the band is stated at. Outward from the observed range, never inward: a
    * bound that excluded a real reading would be a band asserting that a real market is not one. */
  val Step = 0.05

  def outward(x: Double, up: Boolean): Double =
    val n = if up then math.ceil(x / Step) else math.floor(x / Step)
    math.round(n * Step * 1e6) / 1e6

  test("VarRatioBand is the real range rounded outward") {
    if rows.nonEmpty then
      val lo = rows.map(_.vr60).min
      val hi = rows.map(_.vr60).max
      assertEqualsDouble(MarketSim.VarRatioBand._1, outward(lo, up = false), 1e-9,
        f"the low bound no longer follows from the fixture: readings start at $lo%.3f, which " +
        f"rounds outward to ${outward(lo, up = false)}%.3f")
      assertEqualsDouble(MarketSim.VarRatioBand._2, outward(hi, up = true), 1e-9,
        f"the high bound no longer follows from the fixture: readings reach $hi%.3f, which " +
        f"rounds outward to ${outward(hi, up = true)}%.3f")
  }

  test("the band admits every real reading") {
    // Implied by the rule above and asserted anyway, because this is the property that matters:
    // the gate uses STRICT inequalities, so a bound landing exactly on a real reading would fail
    // the market that produced it.
    for r <- rows do
      assert(r.vr60 > MarketSim.VarRatioBand._1 && r.vr60 < MarketSim.VarRatioBand._2,
        f"${r.ticker}%s over ${r.window}%s reads ${r.vr60}%.3f, outside the band the gate enforces")
  }

  test("the era separates these readings and the index does not") {
    // Why the band is shared rather than carried per asset. Two indices as different as the
    // Nasdaq-100 and the S&P over the same era agree far more closely than one index does with
    // itself across eras -- so a per-asset band would encode a difference the record does not show,
    // and would have to be invented for every new anchor set.
    if rows.nonEmpty then
      def at(w: String, t: String) = rows.find(r => r.window == w && r.ticker == t).map(_.vr60)
      (at("wfull", "QQQ"), at("wfull", "SPY"), at("c1926", "CRSP-VW"), at("c1990", "CRSP-VW")) match
        case (Some(qqq), Some(spy), Some(century), Some(modern)) =>
          val acrossIndex = math.abs(qqq - spy)
          val acrossEra   = math.abs(century - modern)
          assert(acrossIndex < acrossEra / 2.0,
            f"QQQ and SPY now differ by $acrossIndex%.3f against $acrossEra%.3f between the CRSP " +
            "century and 1990-2026. If the index has become the larger axis, the band belongs in " +
            "`Anchors` per asset, not shared.")
        case _ => fail("the fixture no longer carries the QQQ/SPY/CRSP rows this claim rests on")
  }

  test("the fixture covers a cross-section, not one market") {
    if rows.nonEmpty then
      assert(rows.count(_.window == "wfull") >= 15,
        s"only ${rows.count(_.window == "wfull")} instruments in the full-history block")
      assert(rows.map(_.kind).distinct.size >= 3,
        s"the readings now span only ${rows.map(_.kind).distinct.mkString(", ")}")
      assert(rows.forall(_.years >= 20.0),
        "a window shorter than 20 years has appeared; the 60-session ratio needs blocks to average")
  }

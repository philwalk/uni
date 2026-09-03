package uni.apps

import munit.FunSuite
import uni.*

/**
 * Cross-sectional drift dispersion (`-basketdrift`): a per-name annual log-drift offset, centred
 * exactly so the sector is untouched.  The dial is ANCHORED AT 0 by the checked-in fixture, and
 * that is the unusual thing worth pinning: the fixture says no true dispersion is DETECTABLE in
 * a survivor cache, so a shipped world that turned this on would be asserting something the
 * record cannot support.  The Rust twin carries the same checks in `basket_drift_tests`.
 */
class BasketDriftSuite extends FunSuite:

  val Fixture = "test-data/equity-anchors/basket-drift-2026-09-03.tsv"

  def rows(path: String): Vector[Vector[String]] =
    val p = path.asPath
    if !p.exists then Vector.empty
    else p.lines.toVector
      .filterNot(l => l.startsWith("#") || l.startsWith("group\t") || l.trim.isEmpty)
      .map(_.split('\t').toVector)

  def value(rs: Vector[Vector[String]], group: String, stat: String): Double =
    rs.find(r => r(0) == group && r(1) == stat)
      .getOrElse(fail(s"fixture row [$group $stat] missing"))(2).toDouble

  val Basket = MarketSim.Defaults.copy(basket = 8, basketBeta = 1.56, basketSector = 1.2,
                                       basketIdio = 1.0, basketGaps = 3.0)

  test("the fixture anchors the dial at 0: the observed spread is under the window's noise floor") {
    val rs = rows(Fixture)
    assume(rs.nonEmpty, s"$Fixture absent")
    for g <- Vector("eight", "pop26") do
      val spread = value(rs, g, "driftSpread")
      val floor  = value(rs, g, "noiseFloor")
      val truth  = value(rs, g, "trueSpread")
      assert(spread <= floor,
        s"[$g] the fixture claims no detectable dispersion, but $spread exceeds the floor $floor")
      assertEqualsDouble(truth, math.sqrt(math.max(0.0, spread * spread - floor * floor)), 1e-9,
        s"[$g] trueSpread must be the decomposition of its own rows")
      assertEqualsDouble(truth, 0.0, 1e-9, s"[$g] the shipped anchor is 0")
      // the same answer on the residual after the group index, so the beta spread is not the cause
      assert(value(rs, g, "alphaSpread") <= value(rs, g, "alphaNoiseFloor"), s"[$g] alpha")
    // and every shipped world carries the anchor
    for (v, w) <- MarketSim.Releases do assertEquals(w.basketDrift, 0.0, s"release $v")
    for (n, w, _) <- MarketSim.Recipes do assertEquals(w.basketDrift, 0.0, s"recipe $n")
    assertEquals(MarketSim.Defaults.basketDrift, 0.0)
  }

  test("off is bit-identical; on it disperses the names and leaves the sector untouched") {
    val off = MarketSim.simulate(Basket, 12, MarketSim.DefaultSeed)
    val on  = MarketSim.simulate(Basket.copy(basketDrift = 0.6), 12, MarketSim.DefaultSeed)
    val zero = MarketSim.simulate(Basket.copy(basketDrift = 0.0), 12, MarketSim.DefaultSeed)
    for q <- off.names.indices do
      assert(off.names(q).sameElements(zero.names(q)), s"name $q: 0 must be bit-identical")
    assert(on.price.sameElements(off.price), "the dispersion reaches no price")
    assert(on.names.exists(a => !a.sameElements(off.names(0))), "on must move the names")
    // THE CENTRING: the mean of the names' log drifts is what the sector's is, exactly.  Summing
    // the per-name total log moves cancels the offsets whatever the shared leg did.
    def meanFinal(p: MarketSim.Path) = p.names.map(a => a(a.length - 1) - a(0)).sum / p.names.size
    assertEqualsDouble(meanFinal(on), meanFinal(off), 1e-9,
      "centred offsets must leave the equal-weight sector's log drift untouched")
  }

  test("the dial widens the spread of time below peak and leaves its median where it was") {
    // At a CENTURY the off-state spread is only what estimation noise leaves (the shorter the
    // window the more of it there is), so this is where the dial's own contribution is legible.
    def stats(d: Double) =
      MarketSim.basketStats(MarketSim.simPaths(Basket.copy(basketDrift = d), 4, 100,
                                               MarketSim.DefaultSeed)).getOrElse(fail("no basket"))
    val a = stats(0.0)
    val b = stats(0.8)
    assert(b.nameD20Spread > a.nameD20Spread * 2.0,
      s"the dial must disperse time below peak: ${a.nameD20Spread} -> ${b.nameD20Spread}")
    // The MEDIAN is the common drift's, and mean-zero dispersion does not move it -- which is why
    // this dial is not the fix for the level the basket fixture discloses as survivorship.
    assertEqualsDouble(b.nameD20, a.nameD20, 0.05,
      "dispersion around the same centre must leave the median time below peak alone")
    // and the graded level-2 rows are untouched: a constant per-name drift adds no covariance
    assertEqualsDouble(b.aggCorr, a.aggCorr, 1e-3)
    assertEqualsDouble(b.aggBeta, a.aggBeta, 1e-3)
    assertEqualsDouble(b.aggVolRatio, a.aggVolRatio, 1e-3)
  }

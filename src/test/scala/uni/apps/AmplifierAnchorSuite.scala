package uni.apps

import munit.FunSuite
import uni.*

/** THE AMPLIFIER's rulers (`amplifier-2026-09-07.tsv`): the record's |r| autocorrelation profile
  * and its crash-count elasticity against volatility, and what `stressScale` does to the model's
  * own elasticity.  The Rust twin's `amplifier_anchor_tests` reads the same file. */
class AmplifierAnchorSuite extends FunSuite:
  private val fixture = Paths.get("test-data/equity-anchors/amplifier-2026-09-07.tsv")
  private lazy val rows: Vector[Vector[String]] =
    fixture.lines.toVector
      .filterNot(l => l.startsWith("#") || l.startsWith("section\t") || l.trim.isEmpty)
      .map(_.split('\t').toVector)

  private def value(section: String, series: String, window: String, stat: String): Double =
    rows.find(r => r(0) == section && r(1) == series && r(2) == window && r(3) == stat)
      .getOrElse(fail(s"fixture row [$section $series $window $stat] missing"))(5).toDouble

  test("the shipped clustering anchors are the century profile's lag-1 and lag-20 rows") {
    val a = MarketSim.SP500Anchors
    assertEqualsDouble(a.ac1, value("profile", "CRSP", "w1926", "ac1"), 0.002)
    assertEqualsDouble(a.ac20, value("profile", "CRSP", "w1926", "ac20"), 0.002)
    val n = MarketSim.NasdaqAnchors
    assertEqualsDouble(n.ac1, value("profile", "QQQ", "w1999", "ac1"), 0.002)
    assertEqualsDouble(n.ac20, value("profile", "QQQ", "w1999", "ac20"), 0.002)
  }

  test("the record's profile has a hump at lags 2-5 and a tail at 60-120, on both references") {
    for (s, w) <- Vector(("CRSP", "w1926"), ("QQQ", "w1999")) do
      val ac = Vector(1, 2, 5, 10, 20, 60, 120).map(l => value("profile", s, w, s"ac$l"))
      assert(ac(1) > ac(0) && ac(2) > ac(0), s"$s: lags 2 and 5 above lag 1, $ac")
      assert(ac(5) > 0.12 && ac(6) > 0.12, s"$s: the tail holds past 0.12 at 60 and 120, $ac")
  }

  test("crash count is volatility-flat across the fresh-start cross-section") {
    assert(value("elasticity", "cross-section", "w2007", "logSlope") < 0.2)
    assert(math.abs(value("elasticity", "cross-section", "w2007", "logCorr")) < 0.2)
    assert(value("elasticity", "SPY-QQQ", "own", "logSlope") < 0.5)
  }

  test("stressScale shrinks a thinner world's spiral -- fewer crashes, a thinner tail -- and 0 is bit-identical") {
    // the Nasdaq recipe's depth at the reference world's other dials: the gain scale at 0.5
    // multiplies the spiral's excess gain by sqrt(10 / 17.4) = 0.76 (200x100: crashes 35 -> 31,
    // kurtosis 24 -> 12)
    val d = MarketSim.Defaults
    val thin = d.copy(depth = 10.0)
    val s0 = MarketSim.measure(MarketSim.simPaths(thin, 8, 40, MarketSim.DefaultSeed), 40)
    val s5 = MarketSim.measure(MarketSim.simPaths(thin.copy(stressScale = 0.5), 8, 40, MarketSim.DefaultSeed), 40)
    assert(s5.kurt < s0.kurt, s"kurtosis ${s0.kurt} -> ${s5.kurt}")
    assert(s5.epPerPath <= s0.epPerPath, s"crashes per path ${s0.epPerPath} -> ${s5.epPerPath}")
    // the reference world is untouched at any scale, and every frozen world runs at 0
    val a = MarketSim.simulate(d, 3, MarketSim.DefaultSeed)
    val b = MarketSim.simulate(d.copy(stressScale = 1.0), 3, MarketSim.DefaultSeed)
    assert(a.price.sameElements(b.price), "depth 17.4 is the reference: the gain scale is 1 there")
    for (v, w) <- MarketSim.Releases do assertEquals(w.stressScale, 0.0, s"release $v")
    for (n, w, _) <- MarketSim.Recipes if !n.contains("nasdaq") || !n.startsWith("0.24.0") do
      assertEquals(w.stressScale, 0.0, s"recipe $n")
    assertEquals(MarketSim.Defaults.stressScale, 0.0)
  }

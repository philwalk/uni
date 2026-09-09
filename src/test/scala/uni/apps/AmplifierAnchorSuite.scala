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

  test("the record's vol response to a fall persists past lag 1, where the model's decays") {
    for (sr, w) <- Vector(("CRSP", "w1926"), ("QQQ", "w1999")) do
      val lev = Vector(1, 2, 3, 5, 10, 20).map(k => value("levprofile", sr, w, s"lev$k"))
      assert(lev.forall(_ < 0.0), s"$sr: a decline predicts more volatility at every lag, $lev")
      assert(lev(3) < -0.05, s"$sr: still -0.05 or beyond at lag 5, ${lev(3)}")
      assert(lev(3) / lev(0) > 0.6, s"$sr: lag 5 holds most of lag 1's strength, $lev")
    // the model's own, at the shipped defaults.  Item 12 disclosed a lag-5 response HALF of lag
    // 1's; the vol response and then the slow repricing channel closed most of that, so the model
    // now clears the same 0.6 the record does -- from below, and still short of the record's own
    // 0.85.  The statistic needs ensemble: it is a median over paths and reads -0.060 on eight
    // paths against -0.042 on two hundred, converging by about forty.
    val st = MarketSim.measure(MarketSim.simPaths(MarketSim.Defaults, 40, 100, MarketSim.DefaultSeed), 100)
    assert(st.lev1 < -0.05, s"the model's lag-1 response is the record's: ${st.lev1}")
    val ratio = st.lev5 / st.lev1
    assert(ratio > 0.60 && ratio < 0.80,
      s"lag 5 holds most of lag 1's strength but not the record's 0.85: $ratio " +
      s"(lag 5 ${st.lev5}, lag 1 ${st.lev1})")
  }

  test("both vol-response dials are off everywhere, and 0 is bit-identical") {
    val d = MarketSim.Defaults
    assertEquals(d.noiseAsym, 0.0); assertEquals(d.levPersist, 0.0)
    for (v, w) <- MarketSim.Releases do
      assertEquals(w.noiseAsym, 0.0, s"release $v"); assertEquals(w.levPersist, 0.0, s"release $v")
    for (n, w, _) <- MarketSim.Recipes do
      assertEquals(w.noiseAsym, 0.0, s"recipe $n"); assertEquals(w.levPersist, 0.0, s"recipe $n")
    // the persistent kick at 0 IS the shipped kick: its EWMA reduces to the signal itself
    val a = MarketSim.simulate(d, 3, MarketSim.DefaultSeed)
    val b = MarketSim.simulate(d.copy(levPersist = 0.0, noiseAsym = 0.0), 3, MarketSim.DefaultSeed)
    assert(a.price.sameElements(b.price))
    // and both move the profile the way their comments say
    val on = MarketSim.measure(MarketSim.simPaths(d.copy(noiseAsym = 0.06), 40, 100, MarketSim.DefaultSeed), 100)
    val off = MarketSim.measure(MarketSim.simPaths(d, 40, 100, MarketSim.DefaultSeed), 100)
    assert(on.lev5 < off.lev5, s"the asymmetric noise vol must deepen the lag-5 response: ${off.lev5} -> ${on.lev5}")
  }

  test("the slow repricing channel is off in every frozen world, and 0 is bit-identical") {
    val d = MarketSim.Defaults
    assert(d.slowShare > 0.0, "the shipped default runs the channel")
    for (v, w) <- MarketSim.Releases do assertEquals(w.slowShare, 0.0, s"release $v")
    // the Nasdaq recipes carry their own dials and were NOT re-solved against the channel
    for (n, w, _) <- MarketSim.Recipes if !n.startsWith("0.24.1") || n.contains("nasdaq") do
      assertEquals(w.slowShare, 0.0, s"recipe $n")
    // off, the channel's own dials reach no price and no bond
    val off = d.copy(slowShare = 0.0)
    val a = MarketSim.simulate(off, 3, MarketSim.DefaultSeed)
    val b = MarketSim.simulate(off.copy(slowVol = 3.0, slowLev = 0.2, slowPhi = 0.5,
                                        slowPerm = 1.0, slowBeta = 2.0), 3, MarketSim.DefaultSeed)
    assert(a.price.sameElements(b.price) && a.bond.sameElements(b.bond),
      "off, the channel's dials are unreachable")
    // the bond loading reaches the BOND and nothing else
    val on = MarketSim.simulate(d, 3, MarketSim.DefaultSeed)
    val noBond = MarketSim.simulate(d.copy(slowBeta = 0.0), 3, MarketSim.DefaultSeed)
    assert(on.price.sameElements(noBond.price), "the loading must reach no equity price")
    assert(!on.bond.sameElements(noBond.bond), "the loading must reach the bond")
    // and the channel FLATTENS the clustering profile, which is what it was adopted for
    val withCh = MarketSim.measure(MarketSim.simPaths(d, 40, 100, MarketSim.DefaultSeed), 100)
    val without = MarketSim.measure(MarketSim.simPaths(off, 40, 100, MarketSim.DefaultSeed), 100)
    assert(withCh.ac20 / withCh.ac1 > without.ac20 / without.ac1,
      s"the slow channel must flatten the |r| profile: ${without.ac20 / without.ac1} -> " +
      s"${withCh.ac20 / withCh.ac1}")
  }

  test("the vol response is off in every frozen world, 0.24.0's row is the seven-dial move, and 0 is bit-identical") {
    val d = MarketSim.Defaults
    // the LITERAL 0.96 in `Defaults`, because it is constructed before `NoiseAsymPhi` initializes;
    // the Rust twin spells it as the constant, and a divergence would ship in the sidecar's world
    assertEquals(d.noiseAsymPhi, MarketSim.NoiseAsymPhi)
    assertEquals(d.noiseAsymPhi, 0.96)
    assert(d.volResp > 0.0, "the shipped default runs the vol response")
    for (v, w) <- MarketSim.Releases do
      assertEquals(w.volResp, 0.0, s"release $v")
      assertEquals(w.jumpResp, 0.0, s"release $v")
      assertEquals(w.volRespAttack, 0.0, s"release $v")
      assertEquals(w.noiseAsymCap, 0.0, s"release $v")
      assertEquals(w.stressAdapt, 0.005, s"release $v")
    for (n, w, _) <- MarketSim.Recipes if !n.startsWith("0.24.1") do
      assertEquals(w.volResp, 0.0, s"recipe $n")
      assertEquals(w.jumpResp, 0.0, s"recipe $n")
      assertEquals(w.stressAdapt, 0.005, s"recipe $n")
    // the frozen row is today's default less the two mechanisms' four dials and the three
    // re-solved around them, and nothing else moved with them
    val frozen = MarketSim.releaseWorld("0.24.0").getOrElse(fail("0.24.0 must resolve"))
    val off = d.copy(volResp = frozen.volResp, volRespPhi = frozen.volRespPhi,
                     volRespAttack = frozen.volRespAttack, stressAdapt = frozen.stressAdapt,
                     stress = frozen.stress, volPersist = frozen.volPersist, jumpVar = frozen.jumpVar,
                     slowShare = frozen.slowShare, volOfVol = frozen.volOfVol,
                     jumpSkew = frozen.jumpSkew)
    assertEquals(off, frozen,
      "0.24.1 moved the two mechanisms' dials and the six re-solved around them, nothing else")
    // off, the shape dials of both mechanisms reach no price
    val a = MarketSim.simulate(off, 3, MarketSim.DefaultSeed)
    val b = MarketSim.simulate(off.copy(volRespPhi = 0.5, volRespAttack = 0.9, volRespCap = 1.0,
                                        noiseAsymPhi = 0.5, noiseAsymCap = 0.1), 3, MarketSim.DefaultSeed)
    assert(a.price.sameElements(b.price), "off, the shape dials are unreachable")
    // and the response deepens the LAG-20 profile, which is what 0.24.1 adopted it for
    val on20 = MarketSim.measure(MarketSim.simPaths(d, 20, 100, MarketSim.DefaultSeed), 100)
    val no20 = MarketSim.measure(MarketSim.simPaths(d.copy(volResp = 0.0), 20, 100, MarketSim.DefaultSeed), 100)
    assert(on20.lev20 < no20.lev20,
      s"the vol response must deepen the lag-20 response: ${no20.lev20} -> ${on20.lev20}")
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
    for (n, w, _) <- MarketSim.Recipes if !n.contains("nasdaq") || !n.startsWith("0.24.") do
      assertEquals(w.stressScale, 0.0, s"recipe $n")
    assertEquals(MarketSim.Defaults.stressScale, 0.0)
  }

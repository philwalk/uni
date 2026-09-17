package uni.apps

import munit.FunSuite

/** THE VALUATION CYCLE AS A STATE (`-cyclesd`, PLAN item 27) and the stationarity row.  Before
  * it every path started at the fundamental and the valuation gap drifted under it for decades,
  * because at the calibrated belief share the price never followed the fundamental back up; the
  * wing, dispersion and tail rows were graded on that transient.  The cycle is a stationary
  * AR(1) in perceived fair, started from its own law and repriced the same session, and the
  * `valuation stationary from the first session` row is what refuses the old start.  The Rust
  * twin's `valuation_cycle_tests` makes the same checks. */
class ValuationCycleSuite extends FunSuite:

  /** the S&P default with the cycle on and the beliefs' share halved: the probe that showed the
    * start stationary and the disaster's recovery priced (item 27) */
  private def probe: MarketSim.World =
    MarketSim.Defaults.copy(cycleSd = 0.3, cycleYears = 11.5, beliefShare = 0.5, beliefLeak = 0.0)

  test("the cycle is off in every frozen release row, and 0 is bit-identical") {
    for (v, w) <- MarketSim.Releases do assertEquals(w.cycleSd, 0.0, s"release $v")
    // at 0 the block never runs: its own stream is never drawn and the price starts at the
    // fundamental as before
    val w = MarketSim.Releases.last._2
    val a = MarketSim.simulate(w, 3, MarketSim.DefaultSeed)
    val b = MarketSim.simulate(w.copy(cycleSd = 0.0), 3, MarketSim.DefaultSeed)
    assert(a.price.sameElements(b.price) && a.bond.sameElements(b.bond), "0 must be bit-identical")
  }

  test("on, the cycle reaches the price and widens the valuation gap") {
    val off = MarketSim.Defaults.copy(beliefShare = 0.5, beliefLeak = 0.0, cycleSd = 0.0)
    val on  = probe
    val a = MarketSim.simulate(off, 20, MarketSim.DefaultSeed)
    val b = MarketSim.simulate(on, 20, MarketSim.DefaultSeed)
    assert(!a.price.sameElements(b.price), "the cycle must reach the price")
    val stOff = MarketSim.measure(MarketSim.simPaths(off, 20, 80, MarketSim.DefaultSeed), 80)
    val stOn  = MarketSim.measure(MarketSim.simPaths(on, 20, 80, MarketSim.DefaultSeed), 80)
    assert(stOn.valDisp > stOff.valDisp + 0.05,
      f"the cycle must widen the gap's dispersion: ${stOff.valDisp}%.3f -> ${stOn.valDisp}%.3f")
  }

  test("gapDriftOf reads the later half against the first decade") {
    val n = 80 * MarketSim.DaysPerYear
    val fund = Array.fill(n)(1.0)
    // +0.5 log through the first decade, -0.5 through the later half, 0 between
    val price = Array.tabulate(n) { i =>
      if i < 10 * MarketSim.DaysPerYear then math.exp(0.5)
      else if i >= 40 * MarketSim.DaysPerYear then math.exp(-0.5)
      else 1.0
    }
    val (e, en, l, ln) = MarketSim.gapDriftOf(price, fund)
    assertEqualsDouble(e / en, 0.5, 1e-12)
    assertEqualsDouble(l / ln, -0.5, 1e-12)
    assertEquals(en, (10 * MarketSim.DaysPerYear).toDouble)
    assertEquals(ln, (40 * MarketSim.DaysPerYear).toDouble)
    // a short path: the first half against the second
    val (_, en3, _, ln3) = MarketSim.gapDriftOf(price.take(1000), fund.take(1000))
    assertEquals(en3, 500.0)
    assertEquals(ln3, 500.0)
  }

  test("the beliefs' fade alone makes the outgoing default stationary, and 0 is bit-identical") {
    // the 0.24.1 row is the default before the fade and the cycle: the fair-value start
    val w = MarketSim.Releases.find(_._1 == "0.24.1").map(_._2).getOrElse(fail("no 0.24.1 release row"))
    val a = MarketSim.simulate(w, 3, MarketSim.DefaultSeed)
    val b = MarketSim.simulate(w.copy(beliefLeak = 0.0), 3, MarketSim.DefaultSeed)
    assert(a.price.sameElements(b.price), "0 must be bit-identical")
    val leaked = w.copy(beliefLeak = 0.2)
    val st = MarketSim.measure(MarketSim.simPaths(leaked, 60, 80, MarketSim.DefaultSeed), 80)
    println(f"gap drift: default + leak 0.2 ${st.gapDrift}%.3f")
    assert(math.abs(st.gapDrift) < MarketSim.GapDriftBand,
      f"the leak must hold the gap stationary: drift ${st.gapDrift}%.3f")
    // and the short-horizon reversion the belief share carries is untouched
    val st0 = MarketSim.measure(MarketSim.simPaths(w, 60, 80, MarketSim.DefaultSeed), 80)
    assert(math.abs(st.vr60 - st0.vr60) < 0.05,
      f"the 60-day variance ratio must stay put: ${st0.vr60}%.3f -> ${st.vr60}%.3f")
  }

  test("the stationarity row refuses the fair-value start and admits the cycle's") {
    // the 0.24.1 release row is the frozen transient world: its gap falls for a century from
    // the fair-value start, which the row reads as a drift far past the band
    val old = MarketSim.Releases.find(_._1 == "0.24.1").map(_._2)
      .getOrElse(fail("no 0.24.1 release row"))
    val stOld = MarketSim.measure(MarketSim.simPaths(old, 60, 80, MarketSim.DefaultSeed), 80)
    println(f"gap drift: 0.24.1 ${stOld.gapDrift}%.3f")
    assert(stOld.gapDrift < -MarketSim.GapDriftBand,
      f"the frozen transient world must fail the row: drift ${stOld.gapDrift}%.3f")
    assert(MarketSim.gateChecks(MarketSim.SP500Anchors, stOld)
      .exists((n, ok, _) => n == "valuation stationary from the first session" && !ok))
    val stNew = MarketSim.measure(MarketSim.simPaths(probe, 60, 80, MarketSim.DefaultSeed), 80)
    println(f"gap drift: probe ${stNew.gapDrift}%.3f")
    assert(math.abs(stNew.gapDrift) < MarketSim.GapDriftBand,
      f"a world started on its own law must pass the row: drift ${stNew.gapDrift}%.3f")
  }

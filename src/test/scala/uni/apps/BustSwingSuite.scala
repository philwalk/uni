package uni.apps

import munit.FunSuite

/** THE BUST SWING (`-bustamp`, PLAN item 25): the mania's unwind as the record has it.  Off in
  * every frozen world and bit-identical there; on, the swing reaches the price and raises the
  * ensemble's volatility inside the busts, not in the typical year, which is what the mania-led
  * busts it targets carry.  Every world before the 0.24.3 recipe keeps it off; that recipe
  * carries the amplitude decided by measurement under the ceiling.  The Rust twin's
  * `bust_swing_tests` makes the same checks. */
class BustSwingSuite extends FunSuite:

  test("the bust swing is off in every world before 0.24.3, and 0 is bit-identical") {
    assertEquals(MarketSim.Defaults.bustAmp, 0.0, "the S&P default keeps it off")
    for (v, w) <- MarketSim.Releases do assertEquals(w.bustAmp, 0.0, s"release $v")
    for (n, w, _) <- MarketSim.Recipes if n != "0.24.3-nasdaq" do assertEquals(w.bustAmp, 0.0, s"recipe $n")
    assertEquals(MarketSim.namedWorld("0.24.3-nasdaq").get._1.bustAmp, 0.14, "the recipe carries the measured amplitude")
    // at 0 the block never runs: its own stream is never drawn and no hook moves
    val (w, _) = MarketSim.namedWorld("0.24.2-nasdaq").get
    val a = MarketSim.simulate(w, 3, MarketSim.DefaultSeed)
    val b = MarketSim.simulate(w.copy(bustAmp = 0.0), 3, MarketSim.DefaultSeed)
    assert(a.price.sameElements(b.price) && a.bond.sameElements(b.bond), "0 must be bit-identical")
  }

  test("on, the swing reaches the price and raises the ensemble's vol inside the busts") {
    // the recipe the swing is calibrated on, with the swing off against 0.14
    val (r, _) = MarketSim.namedWorld("0.24.3-nasdaq").get
    val w = r.copy(bustAmp = 0.0)
    // a mania has to form first, which takes decades: one 80-year path
    val on  = MarketSim.simulate(w.copy(bustAmp = 0.14), 80, MarketSim.DefaultSeed)
    val off = MarketSim.simulate(w, 80, MarketSim.DefaultSeed)
    assert(!on.price.sameElements(off.price), "the swing must reach the price")
    val stOn  = MarketSim.measure(MarketSim.simPaths(w.copy(bustAmp = 0.14), 60, 80, MarketSim.DefaultSeed), 80)
    val stOff = MarketSim.measure(MarketSim.simPaths(w, 60, 80, MarketSim.DefaultSeed), 80)
    assert(stOn.vol > stOff.vol, f"the swing must add volatility: ${stOff.vol}%.4f -> ${stOn.vol}%.4f")
    // and it adds it inside the busts, not to the ordinary year (the recipe's manias are
    // frequent enough that a few of its years carry a bust, so the median year moves a
    // fraction of a point where the pooled vol moves a point)
    assert(stOn.yearVol - stOff.yearVol < 0.005,
      f"the typical year must stay put: ${stOff.yearVol}%.4f -> ${stOn.yearVol}%.4f")
  }

  test("the ceiling holds the swing under the mania's high, and never runs at 0") {
    val (w, _) = MarketSim.namedWorld("0.24.3-nasdaq").get
    val off = MarketSim.simPaths(w.copy(bustAmp = 0.0), 20, 80, MarketSim.DefaultSeed)
    assert(off.forall(_.bustCeilDays == 0), "at 0 the block never runs")
    val on       = MarketSim.simPaths(w.copy(bustAmp = 0.14), 20, 80, MarketSim.DefaultSeed)
    val held     = on.map(_.bustCeilDays.toLong).sum
    val sessions = on.map(_.price.length.toLong).sum
    assert(held > 0, "the ceiling must bind on a mania's unwind at 0.14")
    val share = held.toDouble / sessions
    println(f"ceiling held on ${share * 100}%.3f%% of sessions")
    assert(share < 0.10,
      f"the ceiling bounds the swing's rallies, not the ordinary session: held on ${share * 100}%.2f%% of sessions")
  }

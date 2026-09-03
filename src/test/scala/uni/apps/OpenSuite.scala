package uni.apps

import munit.FunSuite

/**
 * The open: a derived channel that reaches no price.  Off, the bar is the 0.23.0 bar byte for
 * byte; on, the bar brackets the open and the close, the overnight part never overshoots the
 * session on its own side, and the anchored worlds read the record's overnight share.  The Rust
 * twin carries the same checks in `open_tests`.
 */
class OpenSuite extends FunSuite:

  test("off is bit-identical and carries no open; every frozen release keeps the open off") {
    val bars = MarketSim.Defaults.copy(rangeScale = 0.63, rangeDown = 0.09)
    val off  = MarketSim.simulate(bars, 3, MarketSim.DefaultSeed)
    assert(off.logOpen.isEmpty)
    // the bar's open is the prior close: the extremes bracket the prior close and the close
    var prev = math.log(off.price(0))
    for i <- off.price.indices do
      val c = math.log(off.price(i))
      assert(off.logHi(i) >= math.max(prev, c) - 1e-9 && off.logLo(i) <= math.min(prev, c) + 1e-9, s"bar $i")
      prev = c
    for (v, w) <- MarketSim.Releases do assertEquals(w.overnight, 0.0, s"release $v")
    assertEquals(MarketSim.Recipes.find(_._1 == "0.23.0-nasdaq").get._2.overnight, 0.0)
    assertEquals(MarketSim.Defaults.overnight, 0.0)
  }

  test("on, the open sits between the prior close and the close on its own side, and the bar brackets open and close") {
    val w = MarketSim.Defaults.copy(rangeScale = 0.78, rangeDown = 0.13, overnight = 0.20)
    val p = MarketSim.simulate(w, 5, MarketSim.DefaultSeed)
    assertEquals(p.logOpen.length, p.price.length)
    val lp = p.price.map(math.log)
    var gapUp = 0; var gapDown = 0
    for i <- 1 until lp.length do
      val o = p.logOpen(i) - lp(i - 1)
      val r = lp(i) - lp(i - 1)
      // the clamp: an overnight move on the session's own side never exceeds the session
      if o < 0.0 && r < 0.0 then assert(o >= r - 1e-12, s"session $i gapped past its own decline")
      if o > 0.0 && r > 0.0 then assert(o <= r + 1e-12, s"session $i gapped past its own advance")
      if o > 0 then gapUp += 1 else if o < 0 then gapDown += 1
      assert(p.logHi(i) >= math.max(p.logOpen(i), lp(i)) - 1e-9 && p.logLo(i) <= math.min(p.logOpen(i), lp(i)) + 1e-9,
        s"bar $i must bracket its open and close")
    assert(gapUp > 0 && gapDown > 0, "the open gaps both ways")
    // the price itself is untouched by the open
    val off = MarketSim.simulate(w.copy(overnight = 0.0), 5, MarketSim.DefaultSeed)
    assert(p.price.sameElements(off.price))
  }

  test("the anchored open worlds read the record's overnight share, and the open's rows grade only when it ran") {
    // The bars fixture's overnightShare rows: 0.33 (SPY) / 0.28 (QQQ), tol 0.10.  A small
    // ensemble, so the tolerance is the fixture's, not the scoring size's.
    val a  = MarketSim.SP500Anchors
    val sp = MarketSim.measure(MarketSim.simPaths(
      MarketSim.Defaults.copy(rangeScale = 0.78, rangeDown = 0.13, volIdio = 0.34, overnight = 0.20),
      8, 100, MarketSim.DefaultSeed), 100)
    val os = sp.open.getOrElse(fail("no open readings with the dial on"))
    assert(math.abs(os.overnightShare - 0.33) < 0.10, f"S&P overnight share ${os.overnightShare}%.3f")
    assert(os.worstGapShare > os.allGapShare,
      f"the worst sessions must open with more of the day gone: ${os.worstGapShare}%.3f vs ${os.allGapShare}%.3f")
    val rows = MarketSim.gateChecks(a, sp)
    assert(rows.exists(r => r._1.startsWith("bar overnight share") && r._2), "the share row must pass at the anchored dial")
    assert(rows.exists(r => r._1.startsWith("overnight gap share") && r._2 && r._3 == MarketSim.GateClass.Mechanism))
    val off = MarketSim.measure(MarketSim.simPaths(MarketSim.Defaults, 4, 20, MarketSim.DefaultSeed), 20)
    assert(off.open.isEmpty && !MarketSim.gateChecks(a, off).exists(_._1.contains("overnight")),
      "a world without the open carries no open rows")
  }

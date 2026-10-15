package uni.apps

import munit.FunSuite

/** THE 10-YEAR LEG (`World.bond10`) is observational for every other series: it draws from its own
  * stream and reaches no price, so a world with it on is bit-identical to the same world with it
  * off on every column but its own.  The Rust twin carries the same checks in `bond10_tests`. */
class Bond10Suite extends FunSuite:

  test("the leg leaves every other series bit-identical") {
    val w   = MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe"))
    val off = MarketSim.simulate(w, 5, MarketSim.DefaultSeed)
    val on  = MarketSim.simulate(w.copy(bond10 = 8.0), 5, MarketSim.DefaultSeed)
    assert(off.bond10.isEmpty, "off emits no leg")
    assertEquals(on.bond10.length, on.bond.length, "on emits a leg as long as the bond")
    assert(off.price.sameElements(on.price) && off.bond.sameElements(on.bond) && off.rate.sameElements(on.rate))
    assert(off.cpi.sameElements(on.cpi) && off.bliq.sameElements(on.bliq) && off.inflPress.sameElements(on.inflPress))
  }

  test("the leg's volatility scales with its duration") {
    val w = MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe"))
    def vol(d: Double) = MarketSim.measure(MarketSim.simPaths(w.copy(bond10 = d), 8, 30, MarketSim.DefaultSeed), 30).bond10Vol
    val (v4, v8) = (vol(4.0), vol(8.0))
    assert(v4 > 0.0 && math.abs(v8 / v4 - 2.0) < 0.3, s"vol at 8 $v8 against 4 $v4")
  }

  /** Every field to the bit: arrays by their elements, doubles by their bits. */
  private def bits(x: Any): Any = x match
    case a: Array[Double] => a.toVector.map(java.lang.Double.doubleToLongBits)
    case a: Array[?]      => a.toVector.map(bits)
    case d: Double        => java.lang.Double.doubleToLongBits(d)
    case v: Iterable[?]   => v.map(bits).toVector
    case p: Product       => p.productIterator.map(bits).toVector
    case o                => o

  test("the report carries the verdict's leg and drops it to the bit") {
    val (w, set) = MarketSim.namedWorld("0.24.6-sp500").getOrElse(fail("recipe"))
    val a        = MarketSim.anchorsNamed(set.getOrElse(fail("an anchor set")))
    val at       = (3, 20)
    val runW     = MarketSim.reportWorld(a, w, at, at)
    assertEquals(runW.bond10, MarketSim.VerdictBond10, "the report carries the verdict's leg")
    assertEquals(runW.ext.tips, MarketSim.VerdictTips, "and the TIPS leg")
    assert(MarketSim.verdictWorld(a, w) == runW, "the legs are all that separate the two")
    assert(MarketSim.reportWorld(a, w, at, (3, 30)) == w, "another horizon is the verdict's own ensemble")
    val off = MarketSim.simPaths(w, at._1, at._2, MarketSim.DefaultSeed)
    val on  = MarketSim.simPaths(runW, at._1, at._2, MarketSim.DefaultSeed)
    assert(on.forall(p => p.bond10.nonEmpty && p.tips10.nonEmpty))
    val dropped = MarketSim.dropLegs(on)
    assertEquals(bits(dropped), bits(off), "every series, to the bit")
    assertEquals(bits(MarketSim.measureFor(a, dropped, at._2)), bits(MarketSim.measureFor(a, off, at._2)))
    // the report reads the legs again and nothing else (`main`)
    val runSt = MarketSim.measureFor(a, on, at._2)
    assertEquals(bits(runSt.withLegsFrom(MarketSim.measureNeeds(dropped, at._2, MarketSim.PathNeeds.Leg))),
                 bits(MarketSim.measureFor(a, off, at._2)), "the legs read again are the whole read again")
  }

  /** THE TIPS LEG (item 60) reaches no other series either, with the inflation form on or off; its
    * real return moves with the nominal leg's; its real yield is the nominal leg's yield less the
    * expectation; the real-side rows read finite with the leg and NaN without.  The Rust twin
    * carries the same check (`the_tips_leg_leaves_every_other_series_bit_identical`). */
  test("the TIPS leg leaves every other series bit-identical") {
    val base = MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe")).copy(bond10 = MarketSim.VerdictBond10)
    for form <- Vector(false, true) do
      val w   = if form then base.copy(ext = base.ext.copy(spikeRate = 0.4, spikeSize = 0.03, cpiNoise = 0.006,
                                                           deflProb = 0.2, deflSize = 0.035)) else base
      val off = MarketSim.simulate(w, 5, MarketSim.DefaultSeed)
      val onW = w.copy(ext = w.ext.copy(tips = MarketSim.VerdictTips))
      val on  = MarketSim.simulate(onW, 5, MarketSim.DefaultSeed)
      assert(off.tips10.isEmpty && off.realYield10.isEmpty && off.expInfl10.isEmpty && off.realRate.isEmpty, "off emits no real side")
      assertEquals(on.tips10.length, on.bond.length)
      assertEquals(on.realYield10.length, on.bond.length)
      assert(off.price.sameElements(on.price) && off.bond.sameElements(on.bond) && off.rate.sameElements(on.rate))
      assert(off.bond10.sameElements(on.bond10) && off.cpi.sameElements(on.cpi) && off.bliq.sameElements(on.bliq))
      assert(off.inflPress.sameElements(on.inflPress) && off.fundamental.sameElements(on.fundamental))
      val rT  = MarketSim.dailyReturns(on.tips10).zip(MarketSim.dailyReturns(on.cpi)).map((a, b) => a - b)
      val r10 = MarketSim.dailyReturns(on.bond10)
      assert(MarketSim.pearson(rT, r10) > 0.6, s"the leg's real return moves with the nominal leg's (form $form)")
      val tp = onW.ext.termPremium / 100.0 * onW.ext.tips
      for i <- 0 until on.rate.length by 97 do
        val want = 100.0 * (on.rate(i) + tp - on.expInfl10(i) / 100.0)
        assert(math.abs(on.realYield10(i) - want) < 1e-9, s"session $i: real yield ${on.realYield10(i)} against $want")
      val (rows, reported) = MarketSim.realRowsOfPath(on)
      assert(rows.forall(_.isFinite) && reported.forall(_.isFinite), s"$rows $reported")
      assert(MarketSim.realRowsOfPath(off)._1.forall(_.isNaN), "no leg, no rows")
  }

  /** THE TERM STATE (item 60; `WorldExt.termVol`) re-marks the three bond legs and nothing else (the long bond with item 61, marked to the same yield);
    * the legs' yield then no longer carries the overnight rate's session noise.  The Rust twin
    * carries the same check (`the_term_state_moves_the_two_legs_and_nothing_else`). */
  test("the term state moves the two legs and nothing else") {
    val base = MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe"))
    val w    = base.copy(bond10 = MarketSim.VerdictBond10, ext = base.ext.copy(tips = MarketSim.VerdictTips))
    val off  = MarketSim.simulate(w, 5, MarketSim.DefaultSeed)
    val on   = MarketSim.simulate(w.copy(ext = w.ext.copy(termVol = 0.007)), 5, MarketSim.DefaultSeed)
    assert(off.price.sameElements(on.price) && off.rate.sameElements(on.rate))
    assert(!off.bond.sameElements(on.bond), "the long bond is marked to the same yield")
    assert(off.cpi.sameElements(on.cpi) && off.inflPress.sameElements(on.inflPress))
    assert(!off.bliq.sameElements(on.bliq), "the long bond's market moves with its yield")
    assert(off.expInfl10.sameElements(on.expInfl10) && off.realRate.sameElements(on.realRate))
    assert(!off.bond10.sameElements(on.bond10) && !off.tips10.sameElements(on.tips10) && !off.realYield10.sameElements(on.realYield10))
    def dm(v: Array[Double]): Double =
      val ends = (20 until v.length by 21).map(v).toArray
      MarketSim.realYieldReadings(ends)(0)
    assert(dm(on.realYield10) < dm(off.realYield10), s"term state ${dm(on.realYield10)} against the overnight rate ${dm(off.realYield10)}")
  }

  /** THE BREAKEVEN PREMIUM (item 60; `WorldExt.brkVol`) moves the TIPS leg, the breakeven and the
    * real yield and nothing else, the last two by opposite amounts.  The Rust twin carries the same
    * check (`the_breakeven_premium_moves_the_tips_leg_and_nothing_else`). */
  test("the breakeven premium moves the TIPS leg and nothing else") {
    val base = MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe"))
    val w    = base.copy(bond10 = MarketSim.VerdictBond10, ext = base.ext.copy(tips = MarketSim.VerdictTips, termVol = 0.007))
    val off  = MarketSim.simulate(w, 5, MarketSim.DefaultSeed)
    val on   = MarketSim.simulate(w.copy(ext = w.ext.copy(brkVol = 0.005)), 5, MarketSim.DefaultSeed)
    assert(off.price.sameElements(on.price) && off.bond.sameElements(on.bond) && off.rate.sameElements(on.rate))
    assert(off.bond10.sameElements(on.bond10) && off.cpi.sameElements(on.cpi) && off.realRate.sameElements(on.realRate))
    assert(!off.tips10.sameElements(on.tips10) && !off.expInfl10.sameElements(on.expInfl10) && !off.realYield10.sameElements(on.realYield10))
    for i <- on.realYield10.indices do
      val be = on.expInfl10(i) - off.expInfl10(i)
      assert(math.abs((on.realYield10(i) - off.realYield10(i)) + be) < 1e-9, "opposite amounts")
  }

  /** THE NOMINAL SHARE (item 60; `WorldExt.brkNominal`): at 1 the premium's state is the nominal
    * yield's alone, so the nominal leg moves and the real yield is the world's without the premium;
    * the price, the long bond, the rate and the CPI never move.  The Rust twin carries the same
    * check (`the_nominal_share_moves_the_nominal_leg_and_spares_the_real_yield`). */
  test("the nominal share moves the nominal leg and spares the real yield") {
    val base = MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe"))
    val w    = base.copy(bond10 = MarketSim.VerdictBond10, ext = base.ext.copy(tips = MarketSim.VerdictTips, termVol = 0.007))
    val none    = MarketSim.simulate(w, 5, MarketSim.DefaultSeed)
    val real    = MarketSim.simulate(w.copy(ext = w.ext.copy(brkVol = 0.005)), 5, MarketSim.DefaultSeed)
    val nominal = MarketSim.simulate(w.copy(ext = w.ext.copy(brkVol = 0.005, brkNominal = 1.0)), 5, MarketSim.DefaultSeed)
    assert(none.price.sameElements(nominal.price) && !none.bond.sameElements(nominal.bond))
    assert(none.rate.sameElements(nominal.rate) && none.cpi.sameElements(nominal.cpi))
    assert(real.bond10.sameElements(none.bond10) && !nominal.bond10.sameElements(none.bond10))
    assert(nominal.expInfl10.sameElements(real.expInfl10), "the breakeven is the same state")
    for i <- none.realYield10.indices do
      assert(math.abs(nominal.realYield10(i) - none.realYield10(i)) < 1e-9, "the real yield keeps none of it")
    assert(!real.realYield10.sameElements(none.realYield10))
  }

  // A verdict world's paths with its added series dropped are its caller's own, to the bit, for
  // callers that leave every derived series off, some on, or carry a leg of their own.
  test("the report reads the verdict's paths as the caller's") {
    val a    = MarketSim.anchorsNamed("nasdaq")
    val base = MarketSim.Defaults
    val callers = Vector(
      base,
      MarketSim.namedWorld("0.24.6-nasdaq").map(_._1).getOrElse(fail("recipe")),
      base.copy(satBeta = 1.2, divYield = 1.0),
      base.copy(sectors = 3, bond10 = 6.0),
      MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe")))
    for w <- callers do
      val at   = (3, 12)
      val runW = MarketSim.reportWorld(a, w, at, at)
      assert(runW == MarketSim.verdictWorld(a, w), "the verdict's own ensemble")
      val own = MarketSim.asCallersPaths(w, MarketSim.simPaths(runW, at._1, at._2, MarketSim.DefaultSeed))
      assertEquals(bits(own), bits(MarketSim.simPaths(w, at._1, at._2, MarketSim.DefaultSeed)), "the caller's paths, to the bit")
    val sib = base.copy(macroPanel = 1, macroNull = 1)
    assert(MarketSim.reportWorld(a, sib, (3, 12), (3, 12)) == sib, "a sibling panel is its own")
  }

  test("the term premium moves only the bonds, each by its duration") {
    val w   = MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe")).copy(bond10 = 8.0)
    val off = MarketSim.simulate(w.copy(ext = w.ext.copy(termPremium = 0.0)), 10, MarketSim.DefaultSeed)
    val on  = MarketSim.simulate(w.copy(ext = w.ext.copy(termPremium = 0.2)), 10, MarketSim.DefaultSeed)
    assert(off.price.sameElements(on.price) && off.rate.sameElements(on.rate) && off.cpi.sameElements(on.cpi))
    def gain(a: Array[Double], b: Array[Double]) =
      (math.log(b.last / b.head) - math.log(a.last / a.head)) / 10.0 * 100.0
    val (g10, g) = (gain(off.bond10, on.bond10), gain(off.bond, on.bond))
    assert(math.abs(g10 - 1.6) < 0.3, s"the 10-year leg gains $g10 a year")
    assert(math.abs(g - 2.7) < 0.4, s"the long bond gains $g a year")
  }

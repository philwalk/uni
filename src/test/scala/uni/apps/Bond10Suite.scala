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

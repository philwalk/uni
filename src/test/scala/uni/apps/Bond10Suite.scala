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
    assert(MarketSim.verdictWorld(a, w) == runW, "the leg is all that separates the two")
    assert(MarketSim.reportWorld(a, w, at, (3, 30)) == w, "another horizon is the verdict's own ensemble")
    val off = MarketSim.simPaths(w, at._1, at._2, MarketSim.DefaultSeed)
    val on  = MarketSim.simPaths(runW, at._1, at._2, MarketSim.DefaultSeed)
    assert(on.forall(_.bond10.nonEmpty))
    val dropped = MarketSim.dropBond10(on)
    assertEquals(bits(dropped), bits(off), "every series, to the bit")
    assertEquals(bits(MarketSim.measureFor(a, dropped, at._2)), bits(MarketSim.measureFor(a, off, at._2)))
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

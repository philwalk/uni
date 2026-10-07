package uni.apps

import munit.FunSuite

/** A record horizon's read computes only the families its banded rows take (`PathNeeds.ofRows`),
  * and each row reads what the full read reads, bit for bit; the extreme readings keep their path
  * order beside their finite part.  The Rust twin carries the same checks in `horizon_tests`. */
class PathNeedsSuite extends FunSuite:
  private def same(x: Double, y: Double): Boolean =
    java.lang.Double.doubleToLongBits(x) == java.lang.Double.doubleToLongBits(y) || (x.isNaN && y.isNaN)

  test("a selective read matches the full read to the bit") {
    for (recipe, set) <- Vector(("0.24.4", "sp500"), ("0.24.4-nasdaq", "nasdaq")) do
      val w = MarketSim.namedWorld(recipe).map(_._1).getOrElse(fail("recipe")).copy(bond10 = MarketSim.VerdictBond10)
      val a = MarketSim.anchorsNamed(set)
      val sims = MarketSim.simPaths(w, 6, 30, 7)
      val full = MarketSim.measure(sims, 30)
      val banded = MarketSim.fitTargets(a).filter((n, _, _, _) => a.recordBands.exists(_.name == n))
      assert(banded.size >= 15, s"$set: ${banded.size} banded rows")
      for (name, get, _, _) <- banded do
        val nd = MarketSim.PathNeeds.ofRows(Vector(name))
        assert(!nd.rest, s"$set $name: a banded row names a family")
        val part = MarketSim.measureNeeds(sims, 30, nd)
        assert(same(get(full), get(part)), s"$set $name: ${get(part)} against the full read's ${get(full)}")
      val none = MarketSim.measureNeeds(sims, 30, MarketSim.PathNeeds.None)
      assert(none.vol.isNaN && none.multiYear(0).isNaN && none.bondVol.isNaN)
  }

  test("the extreme readings come one per path in path order") {
    val w    = MarketSim.namedWorld("0.24.4-nasdaq").map(_._1).getOrElse(fail("recipe"))
    val a    = MarketSim.anchorsNamed("nasdaq")
    val main = MarketSim.simPaths(w, 8, 64, 7)
    val st   = MarketSim.measure(main, 64)
    val hr   = MarketSim.horizonReadings(a, st, Some(main), 64, 8, 7, w, extremeToo = true)
    assertEquals(hr.extremeByPath.size, hr.extreme.size)
    for (name, per) <- hr.extremeByPath do
      assertEquals(per.size, 8, s"$name: one reading per path")
      val finite = per.filter(x => !x.isNaN)
      val xs     = hr.extreme(name)
      if MarketSim.BondCrashRows.contains(name) then assert(xs.forall(finite.contains), name)
      else
        assertEquals(xs.size, finite.size, name)
        assert(xs.zip(finite).forall((x, y) => same(x, y)), s"$name: the finite readings in path order")
  }

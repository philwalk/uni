package uni.apps

import munit.FunSuite

/**
 * A world the model cannot express, and a path a statistic cannot read: the world dials' domain
 * (`WorldDomain`, shared by the CLI and the search) and the first series of a path that is not a
 * number (`Path.nanSeries`). The Rust twin carries the same checks in `world_domain_tests` and
 * `nan_path_tests`.
 */
class WorldDomainSuite extends FunSuite:

  test("a path names its first nan series") {
    // the price, then the channel level, then the derived series; none on a path every statistic
    // reads
    val p0 = MarketSim.simulate(MarketSim.Defaults, 2, 11L)
    assertEquals(p0.nanSeries, None)
    val p1 = p0.copy(sectors = Vector(Array(0.0, Double.NaN)))
    assertEquals(p1.nanSeries, Some("sectors"))
    val p2 = p1.copy(names = Vector(Array(Double.NaN)))
    assertEquals(p2.nanSeries, Some("basket"))
    val p3 = p2.copy(sat = Array(0.0, Double.NaN))
    assertEquals(p3.nanSeries, Some("satellite"))
    val p4 = p3.copy(chanKDr = Double.NaN)
    assertEquals(p4.nanSeries, Some("level"))
    val p5 = p4.copy(price = p4.price.updated(40, Double.NaN))
    assertEquals(p5.nanSeries, Some("price"))
  }

  test("the domain admits the recipes and refuses with the CLI's text") {
    // every recipe inside it; the jump pair, a share past 1 and a NaN refused with the CLI's text,
    // the first failing check in the CLI's order
    assertEquals(MarketSim.worldDomainRefusal(MarketSim.Defaults), None)
    MarketSim.Recipes.foreach((name, w, _) => assertEquals(MarketSim.worldDomainRefusal(w), None, name))
    val pair = MarketSim.Defaults.copy(jumpVar = 0.09, jumpRate = 0.0)
    assertEquals(MarketSim.worldDomainRefusal(pair),
      Some("-jumpvar 0.09 needs -jumprate above 0: the jump size is set by jumpVar/jumpRate"))
    val both = pair.copy(leverage = 1.5)
    assertEquals(MarketSim.worldDomainRefusal(both), Some("-leverage wants a share in 0..1, got 1.5"))
    val d = MarketSim.Defaults
    val nan = d.copy(ext = d.ext.copy(declineVolHalf = Double.NaN))
    assertEquals(MarketSim.worldDomainRefusal(nan), Some("-declinevolhalf NaN must be above 0"))
  }

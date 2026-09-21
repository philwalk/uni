package uni.apps

import munit.FunSuite

/** THE CREDIT-TRIGGERED VOL REGIME (`creditRegime`, `creditRegimeRate`) and the slow bond leg's
  * reversal in an inflation regime (`slowBondInfl`).  The Rust twin's `credit_regime_tests` makes
  * the same checks. */
class CreditRegimeSuite extends FunSuite:
  /** The recipe with the three dials under test OFF, whatever it ships them at: each test reads a
    * dial's effect on a world that does not have it. */
  private def recipe = MarketSim.namedWorld("0.24.4-nasdaq").get._1
    .copy(creditRegime = 0.0, creditRegimeRate = 0.0, slowBondInfl = 0.0)

  private def read(w: MarketSim.World): MarketSim.WorldStats =
    MarketSim.measure(MarketSim.simPaths(w, 40, 60, 20260918L), 60)

  test("a regime that never starts leaves the world untouched") {
    // the recipe already runs the credit cycle, so the amplitude alone draws and moves nothing
    val base  = read(recipe)
    val armed = read(recipe.copy(creditRegime = 0.8, creditRegimeRate = 0.0))
    assertEquals(armed.vol, base.vol)
    assertEquals(armed.kurt, base.kurt)
  }

  test("turbulent spells raise volatility and its clustering") {
    // at the amplitude and onset rate the recipe itself runs
    val base = read(recipe)
    val on   = read(recipe.copy(creditRegime = 0.8, creditRegimeRate = 18.0))
    assert(on.vol > base.vol + 0.02, f"equity vol ${base.vol}%.4f -> ${on.vol}%.4f")
    assert(on.ac1 > base.ac1 + 0.03, f"lag-1 clustering ${base.ac1}%.3f -> ${on.ac1}%.3f")
  }

  test("the regime switches the credit cycle on") {
    // no spiral gain and no panel: the regime alone evolves the stock its onsets read
    val quiet = MarketSim.Defaults.copy(levGain = 0.0, macroPanel = 0)
    val base  = read(quiet)
    val on    = read(quiet.copy(creditRegime = 0.4, creditRegimeRate = 5.0))
    assert(on.vol > base.vol + 0.003, f"equity vol ${base.vol}%.4f -> ${on.vol}%.4f")
  }

  test("the slow bond leg, reversed, deepens the bond's inflation crashes") {
    val base = read(recipe)
    val on   = read(recipe.copy(slowBondInfl = 1.0))
    assert(on.bondInfl < base.bondInfl - 0.3,
      f"inflation-crash bond ${base.bondInfl}%.2f -> ${on.bondInfl}%.2f")
    assert(math.abs(on.bondGrowth - base.bondGrowth) < 0.5,
      f"growth-crash rally ${base.bondGrowth}%.2f -> ${on.bondGrowth}%.2f")
  }

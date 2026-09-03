package uni.apps

import munit.FunSuite
import uni.*

/**
 * The dividend stream: a derived channel that reaches no price, so `price` keeps its meaning and
 * the dial is bit-identical off.  The anchors are MEASURED numbers re-derived from the checked-in
 * fixture.  The Rust twin carries the same checks in `dividend_tests`, against the same file.
 */
class DividendSuite extends FunSuite:

  val Fixture = "test-data/equity-anchors/dividend-2026-09-02.tsv"

  def rows(path: String): Vector[Vector[String]] =
    val p = path.asPath
    if !p.exists then Vector.empty
    else p.lines.toVector
      .filterNot(l => l.startsWith("#") || l.startsWith("set\t") || l.trim.isEmpty)
      .map(_.split('\t').toVector)

  def value(rs: Vector[Vector[String]], set: String, series: String, window: String, stat: String): Double =
    rs.find(r => r(0) == set && r(1) == series && r(2) == window && r(3) == stat)
      .getOrElse(fail(s"fixture row [$set $series $window $stat] missing"))(4).toDouble

  def outward(x: Double, up: Boolean): Double =
    val n = if up then math.ceil(x / 0.1 - 1e-9) else math.floor(x / 0.1 + 1e-9)
    math.round(n * 0.1 * 1e6) / 1e6

  test("off is bit-identical and carries no columns; every frozen release and recipe is off") {
    val off = MarketSim.simulate(MarketSim.Defaults, 3, MarketSim.DefaultSeed)
    val on  = MarketSim.simulate(MarketSim.Defaults.copy(divYield = 2.95), 3, MarketSim.DefaultSeed)
    assert(off.divYield.isEmpty && off.traded.isEmpty)
    assert(on.price.sameElements(off.price) && on.fundamental.sameElements(off.fundamental),
      "the dividend stream must reach no price")
    for (v, w) <- MarketSim.Releases do assertEquals(w.divYield, 0.0, s"release $v")
    for (n, w, _) <- MarketSim.Recipes do assertEquals(w.divYield, 0.0, s"recipe $n")
    assertEquals(MarketSim.Defaults.divYield, 0.0, "the shipped default emits no dividend columns")
  }

  test("the world's mean fundamental/price is a constant of the level ensemble, and the dial is the mean yield") {
    // The level is solved on the fixed 8 x 100 ensemble at LevelSeed, in path then session order,
    // which `simPaths` at that seed reproduces path for path.  A per-path mean would leak the
    // path's future into every session's yield; this constant leaks nothing.
    val w   = MarketSim.Defaults.copy(divYield = 2.95)
    val lvl = MarketSim.worldLevel(w)
    val ens = MarketSim.simPaths(w, MarketSim.LevelPaths, MarketSim.LevelYears, MarketSim.LevelSeed)
    val (sFp, nFp) = ens.map(MarketSim.fairOverPriceSum).foldLeft((0.0, 0.0)) { case ((a, b), (c, d)) => (a + c, b + d) }
    assertEqualsDouble(lvl.kDiv, sFp / nFp, 1e-9 * lvl.kDiv, "kDiv must be the pooled mean fundamental/price")
    assert(lvl.kDiv > 1.0, f"the ensemble's mean gap sits below fair, so kDiv > 1: read ${lvl.kDiv}%.3f")
    assertEquals(MarketSim.worldLevel(MarketSim.Defaults).kDiv, 0.0, "off, no level is solved")
    // The dial IS the mean yield: an independent ensemble reads it back within sampling noise.
    val st = MarketSim.measure(MarketSim.simPaths(w, 16, 100, MarketSim.DefaultSeed), 100)
    assertEqualsDouble(st.divYieldMean, 2.95, 0.30, f"mean yield ${st.divYieldMean}%.2f against the dial 2.95")
  }

  test("the session yield is the dial times fundamental over price over its mean, and the traded price is the deflated total return") {
    val w    = MarketSim.Defaults.copy(divYield = 2.95)
    val kDiv = MarketSim.worldLevel(w).kDiv
    val p    = MarketSim.simulate(w, 5, MarketSim.DefaultSeed)
    assertEquals(p.divYield.length, p.price.length)
    assertEquals(p.traded.length, p.price.length)
    assertEqualsDouble(p.chanKDiv, kDiv, 0.0)
    for i <- p.price.indices do
      assertEqualsDouble(p.divYield(i), 2.95 * (p.fundamental(i) / p.price(i)) / kDiv, 1e-12, s"session $i")
    assertEqualsDouble(p.traded(0), p.price(0), 0.0)
    var t = p.price(0)
    for i <- 1 until p.price.length do
      t = t * (p.price(i) / p.price(i - 1) - p.divYield(i) / 100.0 / MarketSim.DaysPerYear)
      assertEqualsDouble(p.traded(i), t, 1e-9 * t, s"session $i")
    // The deflation over the path is the accrued yield: log(TR growth) - log(traded growth) is
    // the mean yield times the years, to first order in the daily accrual.
    val yrs   = p.price.length.toDouble / MarketSim.DaysPerYear
    val gap   = math.log(p.price.last / p.price.head) - math.log(p.traded.last / p.traded.head)
    val meanY = p.divYield.sum / p.divYield.length / 100.0
    assertEqualsDouble(gap, meanY * yrs, 0.02 * meanY * yrs + 1e-6,
      f"accrued yield $gap%.4f vs mean yield x years ${meanY * yrs}%.4f")
    // A rich market yields less: the yield is monotone in fundamental/price by construction.
    val hi = p.price.indices.maxBy(i => p.fundamental(i) / p.price(i))
    val lo = p.price.indices.minBy(i => p.fundamental(i) / p.price(i))
    assert(p.divYield(hi) > p.divYield(lo))
  }

  test("the level and its band are the fixture's, per anchor set") {
    val rs = rows(Fixture)
    assume(rs.nonEmpty, s"$Fixture absent")
    val sp = MarketSim.SP500Anchors; val nq = MarketSim.NasdaqAnchors
    assertEqualsDouble(sp.divYield, value(rs, "sp500", "Shiller-S&P", "1954-2023", "monthlyMean"), 1e-9)
    assertEqualsDouble(sp.divYieldBand._1, outward(value(rs, "sp500", "Shiller-S&P", "1954-2023", "annualMin"), up = false), 1e-9)
    assertEqualsDouble(sp.divYieldBand._2, outward(value(rs, "sp500", "Shiller-S&P", "1954-2023", "annualMax"), up = true), 1e-9)
    assertEqualsDouble(nq.divYield, value(rs, "nasdaq", "QQQ", "2005-2026", "annualMean"), 1e-9)
    assertEqualsDouble(nq.divYieldBand._1, outward(value(rs, "nasdaq", "QQQ", "2005-2026", "annualMin"), up = false), 1e-9)
    assertEqualsDouble(nq.divYieldBand._2, outward(value(rs, "nasdaq", "QQQ", "2005-2026", "annualMax"), up = true), 1e-9)
    for a <- MarketSim.AnchorSets do
      assert(a.divYield > a.divYieldBand._1 && a.divYield < a.divYieldBand._2,
        s"${a.name}: the anchored yield must sit inside its own band")
  }

  test("the gate grades the level only when the dial is on, and the dial is an identity parameter") {
    // At the verdict horizon: the yield is normalized by the century's mean fundamental/price, and
    // a 20-year path's own mean sits well below it, so a short ensemble reads the dial low.
    val a   = MarketSim.SP500Anchors
    val off = MarketSim.measure(MarketSim.simPaths(MarketSim.Defaults, 4, 100, MarketSim.DefaultSeed), 100)
    assert(off.divYieldMean.isNaN)
    assert(!MarketSim.gateChecks(a, off).exists(_._1.startsWith("dividend yield")),
      "a dividends-off world must carry no dividend row, or its verdict is not byte-identical")
    val on  = MarketSim.measure(MarketSim.simPaths(MarketSim.Defaults.copy(divYield = a.divYield), 4, 100,
                MarketSim.DefaultSeed), 100)
    val row = MarketSim.gateChecks(a, on).find(_._1.startsWith("dividend yield"))
      .getOrElse(fail("no dividend row with the dial on"))
    assert(row._2, s"the anchored dial must pass its own band: mean ${on.divYieldMean}, row ${row._1}")
    assertEquals(row._3, MarketSim.GateClass.Fidelity)
    val far = MarketSim.measure(MarketSim.simPaths(MarketSim.Defaults.copy(divYield = 9.0), 4, 100,
                MarketSim.DefaultSeed), 100)
    assert(!MarketSim.gateChecks(a, far).find(_._1.startsWith("dividend yield")).exists(_._2),
      "a yield outside the record's annual means must fail the row")
    assert(MarketSim.IdentityParams.contains("divYield"))
    assert(!MarketSim.CalibrateRanges.exists(_._1 == "divYield"))
  }

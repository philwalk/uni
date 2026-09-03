package uni.apps

import munit.FunSuite
import uni.*

/**
 * The basket's anchors are MEASURED numbers; this re-derives the graded bands from the
 * checked-in fixture (the eight names' ranges at level 1 and 3, SMH's relation to SPY at level 2)
 * so the code and the record cannot drift apart, and pins the channel's contracts: off is
 * bit-identical, the names are observational, the mechanism row discriminates.  The Rust twin
 * carries the same checks in `basket_anchor_tests`, against the same file.
 */
class BasketAnchorSuite extends FunSuite:

  val Fixture = "test-data/equity-anchors/basket-2026-09-02.tsv"

  def rows(path: String): Vector[Vector[String]] =
    val p = path.asPath
    if !p.exists then Vector.empty
    else p.lines.toVector
      .filterNot(l => l.startsWith("#") || l.startsWith("group\t") || l.trim.isEmpty)
      .map(_.split('\t').toVector)

  def value(rs: Vector[Vector[String]], group: String, name: String, stat: String): Double =
    rs.find(r => r(0) == group && r(1) == name && r(2) == stat)
      .getOrElse(fail(s"fixture row [$group $name $stat] missing"))(3).toDouble

  def eight(rs: Vector[Vector[String]], stat: String): Vector[Double] =
    rs.filter(r => r(0) == "eight" && r(2) == stat).map(_(3).toDouble)

  /** The anchored world: the S&P default with the basket at its anchored dials. */
  val Anchored = MarketSim.Defaults.copy(basket = 8, basketBeta = 1.56, basketSector = 1.2,
                                         basketIdio = 1.0, basketGaps = 3.0)

  test("the graded bands are the fixture's: the eight's ranges at levels 1 and 3, the basket on the set's primary at level 2") {
    val rs = rows(Fixture)
    assume(rs.nonEmpty, s"$Fixture absent")
    val st = MarketSim.measure(MarketSim.simPaths(Anchored, 2, 10, MarketSim.DefaultSeed), 10)
    // The bands live in `gateChecks`' names, derived from the bounds they test; read them back
    // off a measured world so the test grades the code that runs, not a copy.
    def gate(a: MarketSim.Anchors, name: String): (Double, Double) =
      val row = MarketSim.gateChecks(a, st).map(_._1)
        .find(_.startsWith(name + " ")).getOrElse(fail(s"no gate row [$name]"))
      val Array(lo, hi) = row.stripPrefix(name + " ").split("-").map(_.toDouble)
      (lo, hi)
    def out1(lo: Double, hi: Double) = (math.floor(lo * 10) / 10, math.ceil(hi * 10) / 10)
    def out2(lo: Double, hi: Double) = (math.floor(lo * 100) / 100, math.ceil(hi * 100) / 100)
    for (a, primary, sfx) <- Vector((MarketSim.SP500Anchors, "SPY", "Spy"), (MarketSim.NasdaqAnchors, "QQQ", "Qqq")) do
      val pv = value(rs, "basket", primary, "vol")
      val vr = eight(rs, "vol").map(_ / pv)
      assertEquals(gate(a, "basket name vol ratio"), out1(vr.min, vr.max), s"${a.name} name vol ratio")
      val gp = eight(rs, "gaps10")
      assertEquals(gate(a, "basket name gaps/yr"), out1(gp.min, gp.max))
      val corr = value(rs, "basket", "basket", "corr" + sfx)
      def at2(x: Double) = math.round(x * 100) / 100.0
      assertEquals(gate(a, "basket corr"), (at2(corr - 0.10), at2(corr + 0.10)), s"${a.name} corr")
      val beta = value(rs, "basket", "basket", "betaOn" + sfx)
      assertEquals(gate(a, "basket beta"), out1(beta - 0.25, beta + 0.25), s"${a.name} beta")
      val volr = value(rs, "basket", "basket", "volRatio" + sfx)
      assertEquals(gate(a, "basket vol ratio"), out1(volr - 0.30, volr + 0.30), s"${a.name} vol ratio")
      assertEquals(gate(a, "basket pair corr"), out2(value(rs, "cross", "pairCorr", "min"), value(rs, "cross", "pairCorr", "max")))
      assertEquals(gate(a, "basket idio share"), out2(value(rs, "cross", "idioShare", "min"), value(rs, "cross", "idioShare", "max")))
      val tc = value(rs, "cross", "tailCoincidence", "value")
      assertEquals(gate(a, "basket tail coincidence"), (math.round((tc - 0.13) * 100) / 100.0, math.round((tc + 0.12) * 100) / 100.0))
    assert(value(rs, "mechanism", "pairCorr", "spyWorstDecile") > value(rs, "mechanism", "pairCorr", "spyMiddleDecile"),
      "the mechanism row's premise must hold in the record")
  }

  test("off is bit-identical and carries no names; every frozen release and recipe keeps the basket off") {
    val off = MarketSim.simulate(MarketSim.Defaults, 3, MarketSim.DefaultSeed)
    val on  = MarketSim.simulate(Anchored, 3, MarketSim.DefaultSeed)
    assert(off.names.isEmpty)
    assertEquals(on.names.size, 8)
    assert(on.price.sameElements(off.price) && on.fundamental.sameElements(off.fundamental),
      "the names are observational: the primary must not move")
    for (v, w) <- MarketSim.Releases do assertEquals(w.basket, 0, s"release $v")
    for (n, w, _) <- MarketSim.Recipes if !n.endsWith("basket") do assertEquals(w.basket, 0, s"recipe $n")
    assertEquals(MarketSim.Recipes.find(_._1 == "0.23.1-basket").map(_._2), Some(Anchored.copy(divYield = 2.95)),
      "the basket recipe is the S&P default at the anchored basket dials, dividends on")
    assertEquals(MarketSim.Defaults.basket, 0)
    // the other channels are untouched by the basket's draws: the bars and the satellite of a
    // channels-on world are byte-identical with and without the basket
    val chans = MarketSim.Defaults.copy(satBeta = 1.2, satIdio = 0.77, rangeScale = 0.63, rangeDown = 0.09)
    val a = MarketSim.simulate(chans, 3, MarketSim.DefaultSeed)
    val b = MarketSim.simulate(chans.copy(basket = 8, basketBeta = 1.56, basketSector = 1.2, basketIdio = 1.0, basketGaps = 3.0), 3, MarketSim.DefaultSeed)
    assert(a.sat.sameElements(b.sat) && a.logHi.sameElements(b.logHi), "the basket reads its own stream only")
  }

  test("the anchored basket sits on its anchors, and the mechanism row discriminates") {
    // A small ensemble at the verdict horizon; the bands are the eight's own ranges, wide enough
    // that 8 paths read inside them wherever 200 do.
    val st = MarketSim.measure(MarketSim.simPaths(Anchored, 8, 100, MarketSim.DefaultSeed), 100)
    val b  = st.basket.getOrElse(fail("no basket readings with the channel on"))
    val rows = MarketSim.gateChecks(MarketSim.SP500Anchors, st).filter(_._1.startsWith("basket"))
    assertEquals(rows.size, 9, rows.map(_._1).mkString(", "))
    for (nm, ok, _) <- rows do assert(ok, f"$nm failed: names vol ${b.nameVolRatio}%.2f gaps ${b.nameGaps}%.2f corr ${b.aggCorr}%.3f beta ${b.aggBeta}%.2f volr ${b.aggVolRatio}%.2f pair ${b.pairCorr}%.3f idio ${b.idioShare}%.3f coinc ${b.tailCoincidence}%.3f")
    assert(b.pairCorrWorst > b.pairCorrMid + 0.15, f"stress must raise pairwise correlation materially: ${b.pairCorrWorst}%.3f vs ${b.pairCorrMid}%.3f")
    // the discrimination: idio riding the spiral too (the satellite's construction for every
    // name) removes the mechanism -- shared and idio variance rise together in stress
    assert(b.nameD20 > 0.61, f"the names' time below peak is a disclosed reading, expected above the winners' range: ${b.nameD20}%.3f")
    val off = MarketSim.measure(MarketSim.simPaths(MarketSim.Defaults, 4, 20, MarketSim.DefaultSeed), 20)
    assert(off.basket.isEmpty && !MarketSim.gateChecks(MarketSim.SP500Anchors, off).exists(_._1.startsWith("basket")))
  }

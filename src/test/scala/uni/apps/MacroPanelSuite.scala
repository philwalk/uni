package uni.apps

import munit.FunSuite
import uni.*

/**
 * The macro panel: four observables derived from the model's own state after the price loop,
 * so `price` keeps its meaning and the dial is bit-identical off.  The bands are MEASURED numbers
 * re-derived from the checked-in fixture.  The Rust twin carries the same checks in
 * `macro_panel_tests`, against the same file.
 */
class MacroPanelSuite extends FunSuite:

  val Fixture = "test-data/equity-anchors/macro-2026-09-06.tsv"

  def rows(path: String): Vector[Vector[String]] =
    val p = path.asPath
    if !p.exists then Vector.empty
    else p.lines.toVector
      .filterNot(l => l.startsWith("#") || l.startsWith("set\t") || l.trim.isEmpty)
      .map(_.split('\t').toVector)

  def value(rs: Vector[Vector[String]], set: String, series: String, member: String, stat: String): Double =
    rs.find(r => r(0) == set && r(1) == series && r(2) == member && r(3) == stat)
      .getOrElse(fail(s"fixture row [$set $series $member $stat] missing"))(6).toDouble

  test("off is bit-identical and carries no columns; every frozen release and pre-0.24.0 recipe is off") {
    val off = MarketSim.simulate(MarketSim.Defaults, 3, MarketSim.DefaultSeed)
    val on  = MarketSim.simulate(MarketSim.Defaults.copy(macroPanel = 1), 3, MarketSim.DefaultSeed)
    assert(off.macroPanel.isEmpty)
    assert(on.price.sameElements(off.price) && on.fundamental.sameElements(off.fundamental) &&
           on.bond.sameElements(off.bond) && on.rate.sameElements(off.rate),
      "the panel must reach no price")
    for (v, w) <- MarketSim.Releases do assertEquals(w.macroPanel, 0, s"release $v")
    for (n, w, _) <- MarketSim.Recipes if !n.startsWith("0.24.0") do assertEquals(w.macroPanel, 0, s"recipe $n")
    assertEquals(MarketSim.Defaults.macroPanel, 0, "the shipped default emits no macro columns")
  }

  test("the leverage cycle is off in every frozen release and pre-0.24.0 recipe, and 0 reproduces 0.23.1 bit for bit") {
    for (v, w) <- MarketSim.Releases do assertEquals(w.levGain, 0.0, s"release $v")
    for (n, w, _) <- MarketSim.Recipes if !n.startsWith("0.24.0") do assertEquals(w.levGain, 0.0, s"recipe $n")
    assert(MarketSim.Defaults.levGain > 0.0, "the shipped default runs the leverage cycle")
    // the dial off at 0.23.1's dials IS 0.23.1's world: the stock still runs (draw-free for the
    // price, its stream is its own) and the multiplier stays exactly 1.0
    val frozen = MarketSim.releaseWorld("0.23.1").getOrElse(fail("0.23.1 must resolve"))
    val off    = MarketSim.Defaults.copy(levGain = 0.0, stress = frozen.stress, jumpVar = frozen.jumpVar,
                                         jumpSkew = frozen.jumpSkew, leverage = frozen.leverage,
                                         volPersist = frozen.volPersist, fundVol = frozen.fundVol)
    assertEquals(off, frozen, "0.24.0 moved levGain and the six dials re-solved around it, nothing else")
    val a = MarketSim.simulate(frozen, 3, MarketSim.DefaultSeed)
    val b = MarketSim.simulate(off.copy(macroPanel = 1), 3, MarketSim.DefaultSeed)
    assert(a.price.sameElements(b.price) && a.bond.sameElements(b.bond), "levGain 0 must leave the price bit-identical")
  }

  test("on, the four members span the path in their counterparts' units, and the slope consumes no draw") {
    val w = MarketSim.Defaults.copy(macroPanel = 1)
    // a century: an inversion needs an inflation regime tight enough to invert, which a short
    // path can miss (the ensemble inverts 0.13 of sessions, in spells of ~480)
    val p = MarketSim.simulate(w, 100, MarketSim.DefaultSeed)
    val m = p.macroPanel.getOrElse(fail("no panel with the dial on"))
    for j <- 0 to 3 do assertEquals(m.member(j).length, p.price.length, MarketSim.MacroK.Columns(j))
    assert(m.spread.forall(_ >= MarketSim.MacroK.SpreadFloor), "a credit spread is floored, never negative")
    assert(m.ivol.forall(_ >= MarketSim.MacroK.IvolFloor), "an implied vol is floored, never negative")
    assert(m.slope.exists(_ < 0.0) && m.slope.exists(_ > 0.0), "the curve both inverts and steepens")
    // The slope is an expectation of the rate process, so it is the same function of the same
    // states whatever the measurement stream drew: the other members move with the seed's
    // panel stream, the slope only with the price loop's.
    val q = MarketSim.simulate(w, 100, MarketSim.DefaultSeed + 1)
    assert(!q.macroPanel.get.spread.sameElements(m.spread), "the spread reads its own stream")
    assert(q.macroPanel.get.slope.zip(q.rate).forall((s, r) => s.isFinite && r.isFinite))
    // the measured values hold their counterparts' scale: percentage points, a raw index, %
    assert(MarketSim.pctile(m.spread.toIndexedSeq, 0.5) > 0.5 && MarketSim.pctile(m.spread.toIndexedSeq, 0.5) < 6.0)
    assert(MarketSim.pctile(m.ivol.toIndexedSeq, 0.5) > 5.0 && MarketSim.pctile(m.ivol.toIndexedSeq, 0.5) < 60.0)
  }

  test("the readings exist only when the panel ran, and read as the ruler's statistics") {
    val off = MarketSim.measure(MarketSim.simPaths(MarketSim.Defaults, 4, 30, MarketSim.DefaultSeed), 30)
    assert(off.macroPanel.isEmpty)
    val on  = MarketSim.measure(MarketSim.simPaths(MarketSim.Defaults.copy(macroPanel = 1), 4, 30, MarketSim.DefaultSeed), 30)
    val ms  = on.macroPanel.getOrElse(fail("no readings with the dial on"))
    assert(ms.episodes > 0)
    for (m, j) <- ms.members.zipWithIndex do
      val nm = MarketSim.MacroK.Columns(j)
      assert(m.warn >= 0.0 && m.warn <= 1.0, s"$nm warn ${m.warn}")
      assert(m.warnFired >= 0.0 && m.warnFired <= 1.0, s"$nm fired ${m.warnFired}")
      assert(m.ac1 > 0.9 && m.ac1 <= 1.0, s"$nm ac1 ${m.ac1}: a level series, not a return series")
      assert(m.r2fwd60 >= 0.0 && m.r2fwd60 <= 1.0, s"$nm r2 ${m.r2fwd60}")
      assert(m.lvl10 <= m.lvl50 && m.lvl50 <= m.lvl90, s"$nm levels")
    assert(ms.invShare >= 0.0 && ms.invShare <= 1.0)
    assert(ms.vrp.isFinite && ms.r2rv >= 0.0 && ms.r2rv <= 1.0)
  }

  test("the bands are the fixture's: warning shares per set from its two references, the shape rows shared") {
    val rs = rows(Fixture)
    assume(rs.nonEmpty, s"$Fixture absent")
    def at2(x: Double) = math.round(x * 100.0) / 100.0
    // the lag bands are US-wide: +-40 sessions around the upper-middle (the twins' median) of the
    // four references' median firing lags -- sessions from the peak to the first firing, over the
    // 20% episodes the member fired in, rank members in the quarter before the peak
    val refs = Vector(("sp500", "CRSP"), ("sp500", "SPY"), ("nasdaq", "NDX"), ("nasdaq", "QQQ"))
    def lagBand(fred: String, member: String) =
      val m = MarketSim.pctile(refs.map((s, r) => value(rs, s, s"$fred/$r", member, "lag20_63")), 0.5)
      (m - 40.0, m + 40.0)
    assertEquals(MarketSim.MacroBands.SpreadLag, lagBand("BAA10Y", "spread"))
    assertEquals(MarketSim.MacroBands.CondLag,   lagBand("NFCILEVERAGE", "cond"))
    // the build-up: the conditions index's mean trailing rank over the quarter before the peak,
    // 0.12 under the four references' upper-middle, to 1 -- the coupling test, which a decoupled
    // series (the null panel, 0.49) fails by construction
    val pre = MarketSim.pctile(refs.map((s, r) => value(rs, s, s"NFCILEVERAGE/$r", "cond", "prepeak20_63")), 0.5)
    assertEquals(MarketSim.MacroBands.CondPrePeak, (at2(pre - 0.12), 1.0))
    assert(refs.forall((s, r) => value(rs, s, s"NFCILEVERAGE/$r", "cond", "prepeak20_63") > 0.8),
      "the record's leverage index builds before every reference's peaks")
    // the hazard: the references' minimum quarter-horizon 20% hazard, floored to 0.1
    val hz = refs.map((s, r) => value(rs, s, s"NFCILEVERAGE/$r", "cond", "hazard20_63"))
    assertEquals(MarketSim.MacroBands.HazardMin, math.floor(hz.min * 10.0) / 10.0)
    assert(hz.forall(_ > 1.4) && hz.max > 2.5, "leverage concentrates the big peaks on every reference")
    // the record's conditions index leads the peak on three of the four references
    assert(refs.count((s, r) => value(rs, s, s"NFCILEVERAGE/$r", "cond", "lag20_63") < 0.0) >= 3)
    // the shape rows: persistence +-0.08 around the record's, capped at 1
    def acBand(fred: String, member: String, stat: String) =
      val x = value(rs, "shared", fred, member, stat)
      (at2(x - 0.08), at2(math.min(1.0, x + 0.08)))
    assertEquals(MarketSim.MacroBands.SpreadAcK, acBand("BAA10Y", "spread", "ac20"))
    assertEquals(MarketSim.MacroBands.CondAcK,   acBand("NFCILEVERAGE", "cond", "ac4"))
    assertEquals(MarketSim.MacroBands.IvolAcK,   acBand("VIXCLS", "ivol", "ac20"))
    // the oracle bound sits above every predictive R^2 the record shows, and not far above
    val r2s = rs.filter(r => r(3) == "r2fwd60").map(_(6).toDouble)
    assert(r2s.nonEmpty && r2s.max < MarketSim.MacroBands.OracleR2 && MarketSim.MacroBands.OracleR2 <= r2s.max + 0.02,
      f"oracle bound ${MarketSim.MacroBands.OracleR2} against the record's largest ${r2s.max}%.4f")
    // the slope's inversion share and the variance risk premium bands hold the record
    val inv = value(rs, "shared", "T10Y2Y", "slope", "invShare")
    assert(inv > MarketSim.MacroBands.InvShare._1 && inv < MarketSim.MacroBands.InvShare._2)
    for ref <- Vector("CRSP", "SPY") do
      val vrp = value(rs, "shared", s"VIXCLS/$ref", "ivol", "vrp")
      assert(vrp > MarketSim.MacroBands.Vrp._1 && vrp < MarketSim.MacroBands.Vrp._2, s"$ref vrp $vrp")
  }

  test("the null panel is a sibling path's: the same price, a decoupled panel, and no macro rows") {
    val w   = MarketSim.Defaults.copy(macroPanel = 1)
    val own = MarketSim.simulate(w, 20, MarketSim.DefaultSeed)
    val nul = MarketSim.simulate(w.copy(macroNull = 1), 20, MarketSim.DefaultSeed)
    assert(nul.price.sameElements(own.price) && nul.rate.sameElements(own.rate), "the null reaches no price")
    assert(!nul.macroPanel.get.spread.sameElements(own.macroPanel.get.spread), "a sibling's panel, not this path's")
    assert(nul.macroPanel.get.sibling && !own.macroPanel.get.sibling)
    // the sibling IS another path of this world, at seed ^ NullSeed: its own panel, verbatim
    val sib = MarketSim.simulate(w, 20, MarketSim.DefaultSeed ^ MarketSim.MacroK.NullSeed)
    assert(nul.macroPanel.get.spread.sameElements(sib.macroPanel.get.spread) &&
           nul.macroPanel.get.ivol.sameElements(sib.macroPanel.get.ivol))
    val st = MarketSim.measure(MarketSim.simPaths(w.copy(macroNull = 1), 4, 30, MarketSim.DefaultSeed), 30)
    assert(st.macroPanel.exists(_.sibling))
    // the panel's rows all start "macro <member>"; "macro disasters ..." is the disaster channel's
    val panelRow = (n: String) => n.startsWith("macro ") && !n.startsWith("macro disasters")
    assert(!MarketSim.gateChecks(MarketSim.SP500Anchors, st).exists(r => panelRow(r._1)),
      "a null panel grades nothing")
    // a decoupled conditions index reads its unconditional level before a peak
    assert(st.macroPanel.get.members(2).prePeak < 0.65, s"null pre-peak rank ${st.macroPanel.get.members(2).prePeak}")
    val ownSt = MarketSim.measure(MarketSim.simPaths(w, 4, 30, MarketSim.DefaultSeed), 30)
    assert(MarketSim.gateChecks(MarketSim.SP500Anchors, ownSt).count(r => panelRow(r._1)) == 11,
      "the path's own panel carries its eleven rows")
    for (v, w0) <- MarketSim.Releases do assertEquals(w0.macroNull, 0, s"release $v")
    for (n, w0, _) <- MarketSim.Recipes do assertEquals(w0.macroNull, 0, s"recipe $n")
  }

  test("the episode spans the warning share reads are ddEpisodes' own") {
    val p  = MarketSim.simulate(MarketSim.Defaults, 20, MarketSim.DefaultSeed)
    val ep = MarketSim.ddEpisodes(p.price, 0.20)
    val sp = MarketSim.ddSpans(p.price, 0.20)
    assertEquals(ep.size, sp.size)
    for (e, s) <- ep.zip(sp) do
      assertEqualsDouble(e.depth, s.depth, 0.0)
      assertEquals(e.decline, s.trough - s.lo + 1)
      assertEquals(e.underwater, s.hi - s.lo + 1)
  }

package uni.apps

import munit.FunSuite

/**
 * Contracts on the report machinery itself — no fixture, no ensemble. The Rust twin carries the
 * same checks in `contract_tests`.
 */
class MarketSimContractSuite extends FunSuite:

  test("indexed names keep their width and their history") {
    // The padding is a promise about SORT ORDER, and it is only kept if every name a batch writes
    // is the same width. The floor at 3 is the other half of the contract: it is what keeps every
    // ensemble of 1000 or fewer reading exactly as it did before the width became variable.
    assertEquals(MarketSim.indexedName("f.tsv", 7, MarketSim.indexWidth(99)), "f-007.tsv")
    assertEquals(MarketSim.indexedName("f.tsv", 7, MarketSim.indexWidth(999)), "f-007.tsv")
    assertEquals(MarketSim.indexedName("f.tsv", 7, MarketSim.indexWidth(1999)), "f-0007.tsv")
    // one batch, one width, whatever the index inside it
    val w = MarketSim.indexWidth(1999)
    for k <- Seq(0, 999, 1000, 1999) do
      assertEquals(MarketSim.indexedName("f.tsv", k, w).length, "f-0000.tsv".length)
  }

  test("the search never fits an identity parameter") {
    // An identity parameter describes WHICH ASSET this is, and `-crossasset` grades the bond
    // relations by moving one. Letting the search fit it makes that grader circular — and the
    // range row that would do it is one line, added in a moment when the loss looks improvable.
    val searched = MarketSim.CalibrateRanges.map(_._1)
    for p <- MarketSim.IdentityParams do
      assert(!searched.contains(p),
        s"`$p` is an identity parameter (a real fund's measured number) and must not be in " +
        "-calibrate's ranges: a value chosen to reduce loss describes no asset anyone can buy, " +
        "and -crossasset would then grade the search's own choice")
  }

  test("a shifted range is the same ensemble") {
    // `-emitfrom` is only safe to chunk with because a shifted range reproduces the paths the
    // unshifted run would have written at those indices. If this drifts, two chunks of one job
    // silently stop being one ensemble.
    val w    = MarketSim.Defaults
    val all  = MarketSim.simPaths(w, 6, 2, 12345L)
    val tail = MarketSim.simPathRange(w, 4, 2, 2, 12345L)
    assertEquals(tail(0).price.toSeq, all(4).price.toSeq)
    assertEquals(tail(1).price.toSeq, all(5).price.toSeq)
  }

  test("the fidelity targets partition into equity and bond") {
    // Every fidelity target must be classified as equity or bond, exactly once. The subset check
    // this replaces caught renames but not ADDITIONS: a new equity target would simply never
    // appear in the equity section, and a shorter table reads as a shorter list of concerns.
    val expected = (MarketSim.EquityTargets ++ MarketSim.BondTargets).sorted
    val actual   = MarketSim.fitTargets(MarketSim.SP500Anchors).map(_._1).sorted
    assertEquals(actual, expected,
      "the fidelity targets and EquityTargets + BondTargets are no longer the same set. A target " +
      "was added, removed or renamed: classify it in one list (and only one) so the equity " +
      "section cannot silently lose or miss a row.")
  }

  test("the anchor groups partition the fidelity targets") {
    // Every fidelity target must carry exactly one anchor horizon, or `-noise` silently skips
    // it -- the same silent-shrinkage failure the equity/bond partition guards against, on the
    // horizon axis.
    val expected = MarketSim.anchorGroups(MarketSim.SP500Anchors).flatMap(_._3).sorted
    val actual   = MarketSim.fitTargets(MarketSim.SP500Anchors).map(_._1).sorted
    assertEquals(actual, expected,
      "the fidelity targets and the anchor groups are no longer the same set. A target was " +
      "added, removed or renamed: give it a horizon in exactly one anchor group, so the noise " +
      "report cannot silently skip it.")
  }

  test("every anchor set grades exactly the same targets") {
    // An anchor set that omits a target would silently drop it from the loss and from `-noise`,
    // and a set that names one that does not exist would fail only when that row was reached.
    // Both are the silent-shrinkage failure the partition tests above guard against, on the ASSET
    // axis -- which only exists because 0.21.0 made the asset a parameter.
    val reference = MarketSim.fitTargets(MarketSim.SP500Anchors).map(_._1)
    for a <- MarketSim.AnchorSets do
      assertEquals(MarketSim.fitTargets(a).map(_._1), reference,
        s"anchor set [${a.name}] grades a different set of targets than SP500Anchors does. " +
        "Every set must cover the same rows, or the loss means something different depending " +
        "on which index you passed.")
      assertEquals(MarketSim.anchorGroups(a).flatMap(_._3).sorted, reference.sorted,
        s"anchor set [${a.name}]'s groups do not cover its targets.")
  }

  test("the S&P anchor set still holds the values every release before 0.21.0 hard-coded") {
    // The refactor that made the asset a parameter must not have moved the default world's
    // targets. These are the literals that were in `FitTargets` before it took an argument; if
    // one changes, `-validate` changes for every consumer who never asked for a different index.
    //
    // TWO have moved since, deliberately, and both for the same reason -- the anchor was not the
    // statistic the model computes:
    //   `medDepth`   -27.1 -> -21.4 (0.22.0), the record's median at a 20% threshold where the
    //                model measures 15%+ episodes;
    //   `worstDepth` -56.8 -> -84.1 (0.22.1), the worst of 1954-2026, a window that opens AFTER
    //                the 1929-32 decline setting the record's worst, where the model computes the
    //                worst over a whole history.
    // `EpisodeAnchorSuite` re-derives both from `test-data/equity-anchors/episodes-2026-08-29.tsv`
    // and pins the evidence for what each one used to be. A future move of any value here needs the
    // same treatment: measured, recorded, re-derivable.
    val a = MarketSim.SP500Anchors
    assertEquals(a.vol, 16.0)
    assertEquals(a.retVol, 0.69)
    assertEquals(a.kurt, 28.0)
    assertEquals(a.ac1, 0.299)
    assertEquals(a.ac20, 0.225)
    assertEquals(a.crashes, 20.7)
    assertEquals(a.medDepth, -21.4)   // re-measured in 0.22.0; see above
    assertEquals(a.worstDepth, -84.1) // re-anchored in 0.22.1; see above
    assertEquals(a.volBand, (14.0, 18.0))
    assertEquals(a.retVolBand, (0.50, 0.85))
  }

  test("the Nasdaq anchor set is the measured QQQ vector, not the S&P's") {
    // Guards the transcription. Every value is QQQ 1999-03-10..2026-08-20 on the fixture's own
    // definitions, fresh-start peak seeding -- see the constant's own note for why that window
    // and not `w2001`, whose mid-bear opening reads 40.1 crashes/century against this 25.6.
    val a = MarketSim.NasdaqAnchors
    assertEquals(a.vol, 26.90)
    assertEquals(a.retVol, 0.38)
    assertEquals(a.kurt, 9.55)
    assertEquals(a.crashes, 25.6)
    assertEquals(a.medDepth, -22.8)
    assertEquals(a.worstDepth, -83.0)
    assert(a.vol > MarketSim.SP500Anchors.vol,
      "the Nasdaq is more volatile than the S&P; if this fails the two sets have been swapped")
    assert(a.kurt < MarketSim.SP500Anchors.kurt,
      "QQQ's 27-year kurtosis is BELOW the CRSP century's -- a shorter window holds fewer 1987s")
  }

  test("the drawdown-shape episode definition is the one folio-pmw measures with") {
    // A hand-built path with ONE episode, so the definition is pinned rather than described.
    // Peak at index 2, one -20% session, a grind to the trough at 5, recovery to a new high at 8.
    // This exists because the definition now lives in two repos: the consumer measures the same
    // statistic, and a second copy of a definition is a copy free to drift.
    val px = Array(100.0, 105.0, 110.0, 88.0, 86.0, 84.0, 95.0, 105.0, 112.0)
    val eps = MarketSim.ddEpisodes(px, 0.10)
    assertEquals(eps.size, 1, s"expected one episode, got ${eps.map(_.depth)}")
    val e = eps.head
    // trough is index 5 (84 against a running peak of 110): 84/110 - 1
    assertEqualsDouble(e.depth, 84.0 / 110.0 - 1.0, 1e-12)
    // underwater runs 3..7 inclusive — index 8 is the first bar back at a new high
    assertEquals(e.decline, 3, "decline is first-underwater to trough, inclusive")
    assertEquals(e.recovery, Some(3), "recovery is trough to last-underwater, inclusive")
    assertEquals(e.underwater, 5)
    // the worst session is 110 -> 88, and the leg runs from the bar BEFORE the first underwater
    // one, so the total is log(84/110) and the share is log(88/110) / log(84/110).
    assertEqualsDouble(e.worstDayShare, math.log(88.0 / 110.0) / math.log(84.0 / 110.0), 1e-12)
  }

  test("a drawdown-shape episode still underwater at the end is censored, not dropped") {
    // Its depth and decline count; its recovery does not. Dropping it would bias every duration
    // downward by discarding exactly the longest episodes.
    val px = Array(100.0, 120.0, 90.0, 85.0, 88.0)
    val eps = MarketSim.ddEpisodes(px, 0.10)
    assertEquals(eps.size, 1)
    assertEquals(eps.head.recovery, None, "an unrecovered episode must report no recovery")
    assert(eps.head.depth < -0.10, "its depth still counts")
  }

  test("the cross-asset verdict requires coverage") {
    // The three-way branch behind the ladder's verdict line. INCONCLUSIVE exists because a
    // relation that graded nothing was not tested, and an in-support miss outranks it.
    assertEquals(MarketSim.crossAssetVerdict(0, 0, Vector(("a", 3), ("b", 1))), ("PASS", true))
    assertEquals(MarketSim.crossAssetVerdict(1, 0, Vector(("a", 3), ("b", 0))), ("FAIL", false),
      "a real miss outranks empty coverage")
    assertEquals(MarketSim.crossAssetVerdict(0, 0, Vector(("a", 3), ("b", 0))), ("INCONCLUSIVE", false),
      "zero graded cells must not read as PASS")
    assertEquals(MarketSim.crossAssetVerdict(0, 1, Vector(("a", 3), ("b", 1))), ("EDGE", false),
      "a cell within noise of a band edge must not read as PASS")
    assertEquals(MarketSim.crossAssetVerdict(1, 1, Vector(("a", 3), ("b", 1))), ("FAIL", false),
      "a resolved miss outranks an unresolved edge")
  }

  test("the trading halt is absent at zero, and the frozen release rows inherit that") {
    // The halt consumes no random draws, so `haltLimit = 0` has to reproduce every earlier world
    // BIT-IDENTICALLY -- that is what makes it addable without re-searching the calibration. The
    // release rows must carry 0 for the same reason the jump channel's fields do: no release
    // before this one had the mechanism, and a row claiming otherwise would restate today's model
    // under yesterday's version number.
    MarketSim.Releases.filter(_._1 < "0.21.0").foreach: (name, w) =>
      assertEquals(w.haltLimit, 0.0, s"release row $name must carry no halt")
    assert(MarketSim.Defaults.haltLimit > 0.0, "the shipped world runs WITH the halt")
  }

  test("a halted session prints the floor exactly, and defers the rest to the next one") {
    // The whole difference from the numerical guard: a halt DEFERS, it does not cancel. Drive one
    // market far past its floor with a single enormous sell order and nothing else, and the
    // shortfall has to reappear.
    val floor = math.log(1.0 - 0.25)
    val m = new MarketSim.Market(0.0, 0.0, 1.0, 0.0, 1.0, 0.25)
    val first = m.step(0.0, -1.0)
    assertEqualsDouble(first, floor, 1e-12, "the halted session prints the floor, not the order")
    assertEquals(m.haltDays, 1)
    // Second session: the deferred remainder (-1.0 - floor) is still below the floor, so it halts
    // again rather than arriving all at once. The cascade IS the mechanism.
    val second = m.step(0.0, 0.0)
    assertEqualsDouble(second, floor, 1e-12, "the deferred pressure halts the next session too")
    assertEquals(m.haltDays, 2)
  }

  test("the halt is decline-only, and leaves an ordinary session untouched") {
    // Large advances keep the bare numerical guard: the real asymmetry is one-sided, and inventing
    // an upside halt would be a fudge wearing a mechanism's name.
    val m = new MarketSim.Market(0.0, 0.0, 1.0, 0.0, 1.0, 0.25)
    assertEqualsDouble(m.step(0.0, 0.01), 0.01, 1e-12, "an ordinary session is not touched")
    assertEquals(m.haltDays, 0)
    assertEqualsDouble(m.step(0.0, 0.40), 0.40, 1e-12, "an advance past the floor's size is not halted")
    assertEquals(m.haltDays, 0)
  }

  test("the tail-floor check is not vacuous: the 0.21.0 world fails it") {
    // The reason this check exists. `clampPct` measures the guard against ALL sessions, where it is
    // negligible by construction -- 0.000% in the world below -- while the guard was authoring one
    // in ten of that world's deep-tail sessions. A gate row that passes everywhere is not a test,
    // so this pins that the row DISCRIMINATES: off, it fails; on, it passes.
    // At the world the defect was FOUND in -- pinned via the release table, because the live
    // `Defaults` kept moving (the valuation cycle softened single-session extremes enough that
    // the un-halted guard stopped binding there, which is a property of that world, not a loss
    // of the row's discrimination).
    val base = MarketSim.Releases.find(_._1 == "0.22.1").map(_._2)
      .getOrElse(fail("no 0.22.1 release row"))
    val off = MarketSim.measure(MarketSim.simPaths(
      base.copy(haltLimit = 0.0), 60, 100, MarketSim.DefaultSeed), 100)
    val on  = MarketSim.measure(MarketSim.simPaths(
      base, 60, 100, MarketSim.DefaultSeed), 100)
    assert(off.tailFloorPct > 2.0,
      s"without the halt the guard must still shape the tail, read ${off.tailFloorPct}%")
    assertEqualsDouble(on.tailFloorPct, 0.0, 1e-12,
      "with the halt the guard must not touch the tail at all")
    assert(off.clampPct < 0.02,
      "and the OLD check must pass in that same world -- which is why it missed this")
  }

  test("ExtremeTargets names fidelity targets that exist") {
    // A name that matches nothing classifies no row, so the target it was meant to protect goes
    // back to being reported as a ratio -- silently, and only where someone reads the table.
    val names = MarketSim.fitTargets(MarketSim.SP500Anchors).map(_._1).toSet
    for n <- MarketSim.ExtremeTargets do
      assert(names.contains(n),
        s"ExtremeTargets names [$n], which is not a fidelity target. Rename it with the target, " +
        "or the row is graded as a per-path value again.")
  }

  test("an extreme row carries a percentile and no ratio; a per-path row the reverse") {
    // The invariant the sidecar rests on. A consumer must be able to tell the two apart from the
    // DATA -- `ratio: null` is what stops the division being made by accident, and a row that
    // carried both would let it be made anyway.
    val w    = MarketSim.Defaults
    val a    = MarketSim.SP500Anchors
    val st   = MarketSim.measure(MarketSim.simPaths(w, 60, 100, MarketSim.DefaultSeed), 100)
    val rows = MarketSim.fidelityRows(a, st, 60, MarketSim.DefaultSeed, w)
    assertEquals(rows.map(_.name), MarketSim.fitTargets(a).map(_._1),
      "every fidelity target must produce exactly one row, in report order")
    for r <- rows do
      if MarketSim.ExtremeTargets.contains(r.name) then
        assert(r.ratio.isEmpty,
          s"[${r.name}] is an ensemble extreme and must carry no ratio: model/real grades the " +
          "ensemble size, not the model")
        assert(r.pctile.isDefined, s"[${r.name}] must carry a percentile in the ratio's place")
        assertEquals(r.aggregation, "ensemble-extreme")
        assertEquals(r.horizonYears, a.tailYears,
          s"[${r.name}]'s percentile must be read at its own anchor's horizon, which for the " +
          "tail is its own window and NOT the equity window")
      else
        assert(r.ratio.isDefined, s"[${r.name}] is a per-path value and must carry its ratio")
        assert(r.pctile.isEmpty, s"[${r.name}] is not an extreme and must not claim a percentile")
        assertEquals(r.aggregation, "per-path")
  }

  test("the worst-crash LEVEL runs away with the ensemble; the published percentile does not") {
    // WHY the row carries no ratio, pinned so the fix cannot be undone as cosmetic. `worstDepth`
    // is a minimum over every episode in the POOLED ensemble while the anchor is the deepest
    // episode of ONE history, so it deepens without bound as paths grow. The percentile is an
    // estimate of a fixed quantity and is stable over the same range. Both halves are asserted:
    // a test that only checked the percentile was stable would also pass if it were constant
    // because nothing was being measured.
    val w    = MarketSim.Defaults
    val a    = MarketSim.SP500Anchors
    val name = "worst crash %"
    def at(paths: Int): (Double, MarketSim.FidelityRow) =
      val st = MarketSim.measure(MarketSim.simPaths(w, paths, 100, MarketSim.DefaultSeed), 100)
      val r  = MarketSim.fidelityRows(a, st, paths, MarketSim.DefaultSeed, w)
        .find(_.name == name).getOrElse(fail(s"no [$name] row"))
      (st.worstDepth, r)
    val (lvlSmall, small) = at(100)
    val (lvlLarge, large) = at(400)
    assert(lvlLarge < lvlSmall - 3.0,
      f"the pooled minimum must still run away with the ensemble or this test asserts nothing: " +
      f"$lvlSmall%.2f%% at 100 paths, $lvlLarge%.2f%% at 400")
    val (pSmall, pLarge) = (small.pctile.getOrElse(fail("no percentile at 100 paths")),
                            large.pctile.getOrElse(fail("no percentile at 400 paths")))
    // The tolerance is the estimator's own noise, not drift: an INTERIOR percentile estimated
    // from n histories carries binomial sd ~ sqrt(p(1-p)/n) -- about 4 points at n=100 -- where
    // the pooled minimum's movement is unbounded in n.
    assert(math.abs(pLarge - pSmall) <= 10,
      s"the published percentile must be stable over the range the level runs away across: " +
      s"$pSmall% at 100 paths, $pLarge% at 400")
    assertEquals(small.miss, large.miss,
      s"and its verdict must not depend on the ensemble size: $pSmall% at 100 paths, $pLarge% at 400")
  }

  test("an extreme row with too few histories reports n/a and a MISS, never a clean bill") {
    // One history reads 0% or 100% and neither is a measurement. The failure being prevented is
    // the 0.22.1 one on a new axis: `miss: false` on a statistic that could not be measured, in
    // the one field a consumer reads to decide whether to trust the file.
    val w    = MarketSim.Defaults
    val a    = MarketSim.SP500Anchors
    val st   = MarketSim.measure(MarketSim.simPaths(w, 1, 100, MarketSim.DefaultSeed), 100)
    val r    = MarketSim.fidelityRows(a, st, 1, MarketSim.DefaultSeed, w)
      .find(_.name == "worst crash %").getOrElse(fail("no worst crash % row"))
    assert(r.pctile.isEmpty, s"one history cannot place a record, read ${r.pctile}")
    assert(r.miss, "an unplaceable record must report a miss, not a pass")
  }

  test("the loss grades an extreme row by the median single-history reading, and it binds") {
    // `fitness` must price the converging statistic the caller supplies -- never `worstDepth`,
    // the pooled minimum, whose distance from a one-history anchor tracks the ensemble size.
    // Three pins: the two statistics actually differ here (or the test cannot tell them apart),
    // the loss row carries the supplied median, and the term is nonzero at the shipped defaults
    // -- a term that cannot bind is the recurring failure class in this file.
    val w   = MarketSim.Defaults
    val a   = MarketSim.SP500Anchors
    val st  = MarketSim.measure(MarketSim.simPaths(w, 20, 100, MarketSim.DefaultSeed), 100)
    val ext = MarketSim.extremeScoreStats(a, 20, MarketSim.DefaultSeed, w)
    val med = ext.getOrElse("worst crash %", fail("no scored median for worst crash %"))
    assert(math.abs(med - st.worstDepth) > 3.0,
      f"median $med%.2f%% and pooled minimum ${st.worstDepth}%.2f%% must differ at this size, " +
      "or this test cannot tell which one the loss priced")
    val row = MarketSim.fitness(a, st, ext)._2.find(_._1 == "worst crash %")
      .getOrElse(fail("no worst crash % loss row"))
    assertEqualsDouble(row._2, med, 1e-12, "the loss row must carry the median, not the minimum")
    // The term DISCRIMINATES: with the disaster channel off the century tail is far too shallow
    // and the term prices it; at the adopted defaults it is much smaller.  This is what makes the
    // tail term the thing that FOUND the adopted world, and what a cosmetic revert would undo.
    val offW  = w.copy(disasterRate = 0.0)
    val offSt = MarketSim.measure(MarketSim.simPaths(offW, 20, 100, MarketSim.DefaultSeed), 100)
    val offRow = MarketSim.fitness(a, offSt,
        MarketSim.extremeScoreStats(a, 20, MarketSim.DefaultSeed, offW))._2
      .find(_._1 == "worst crash %").getOrElse(fail("no worst crash % loss row"))
    assert(offRow._4 > row._4 + 0.05,
      f"the tail term must price the disaster-off world's shallow century tail well above the " +
      f"adopted world's: off ${offRow._4}%.4f vs on ${row._4}%.4f")
    // supplied exactly at the anchor the term is zero -- pins that the supplied value is priced
    val zeroed = MarketSim.fitness(a, st, Map("worst crash %" -> a.worstDepth))._2
      .find(_._1 == "worst crash %").getOrElse(fail("no worst crash % loss row"))
    assertEqualsDouble(zeroed._4, 0.0, 1e-12)
    // and a missing entry prices as unmeasurable, never as agreement
    val missing = MarketSim.fitness(a, st, Map.empty)._2
      .find(_._1 == "worst crash %").getOrElse(fail("no worst crash % loss row"))
    assert(missing._4 > 1.0,
      f"an unsupplied extreme stat must price as unmeasurable (weight x 4), read ${missing._4}%.4f")
  }

  test("the disaster channel is absent at zero, and the frozen release rows inherit that") {
    // Mirrors the trading-halt test: the channel's draws come from their own stream, so rate 0
    // must reproduce the pre-disaster path BIT-IDENTICALLY whatever the other disaster dials say,
    // and every frozen release row must carry rate 0 -- no release before 0.22.1 had the
    // mechanism.
    val off  = MarketSim.Defaults.copy(disasterRate = 0.0)
    val off2 = off.copy(disasterSize = 9.9, disasterLen = 0.1, disasterRecover = 0.9,
                        disasterRecLen = 0.1)
    val a = MarketSim.simulate(off, 4, MarketSim.DefaultSeed)
    val b = MarketSim.simulate(off2, 4, MarketSim.DefaultSeed)
    assert(a.price.sameElements(b.price),
      "at rate 0 every other disaster dial must be inert, bit for bit")
    // Engagement is checked on the DIAGNOSTIC over a real horizon, not on a short path's bytes:
    // at 0.6/century a 4-year path usually holds no disaster, and the channel leaving such a path
    // untouched is the design, not a defect.
    val on = MarketSim.simPaths(MarketSim.Defaults, 4, 100, MarketSim.DefaultSeed)
    assert(on.map(_.disasters).sum > 0,
      "the adopted default must actually strike within four centuries at this seed")
    // The channel shipped in 0.22.1, so only the releases BEFORE it must inherit rate 0.
    for (v, w) <- MarketSim.Releases if v < "0.22.1" do
      assertEqualsDouble(w.disasterRate, 0.0, 1e-12,
        s"release $v predates the disaster channel and must inherit rate 0")
  }

  test("the disaster channel discriminates on the statistic it was added for") {
    // The channel exists to move the CENTURY-WORST distribution, which no gate-passing dial
    // setting could reach (the sweep of 2026-08-30: recovery, bubble-drag, stress, depth, value,
    // jumpvar, haltlimit, volofvol, volpersist and fundvol all left the median at -58..-61).
    // Pinned so it cannot regress to inert: at the adopted defaults the median single-century
    // worst must be at least 8 points deeper than with the channel off, on the same seed.
    val a   = MarketSim.SP500Anchors
    val on  = MarketSim.extremeScoreStats(a, 40, MarketSim.DefaultSeed, MarketSim.Defaults)
    val off = MarketSim.extremeScoreStats(a, 40, MarketSim.DefaultSeed,
                MarketSim.Defaults.copy(disasterRate = 0.0))
    val (mOn, mOff) = (on("worst crash %"), off("worst crash %"))
    assert(mOn < mOff - 8.0,
      f"the adopted channel must deepen the median century-worst materially: on $mOn%.1f%% " +
      f"vs off $mOff%.1f%%")
  }

  test("the valuation cycle is absent at zero, and the frozen release rows inherit that") {
    // The cycle consumes no draws, so share 0 + cap 0 must reproduce the pre-cycle path
    // BIT-IDENTICALLY whatever the other cycle dials say, and every release before 0.23.0 must
    // carry both at 0.
    val off  = MarketSim.Defaults.copy(beliefShare = 0.0, capYears = 0.0)
    val off2 = off.copy(beliefYears = 0.3, capWindow = 0.5)
    val a = MarketSim.simulate(off, 4, MarketSim.DefaultSeed)
    val b = MarketSim.simulate(off2, 4, MarketSim.DefaultSeed)
    assert(a.price.sameElements(b.price),
      "at share 0 and cap 0 every other cycle dial must be inert, bit for bit")
    for (v, w) <- MarketSim.Releases do
      assert(w.beliefShare == 0.0 && w.capYears == 0.0,
        s"release $v predates the valuation cycle and must inherit share 0 and cap 0")
  }

  test("the valuation cycle discriminates on the statistic it was added for") {
    // Dispersion is why the channel exists: every dial sweep at the 0.22.1 world left
    // sd log(p/fair) at 0.095-0.11 against the record proxy's 0.24-0.41.  Pinned so the channel
    // cannot regress to inert: the adopted default must read at least 0.08 above the cycle-off
    // world on the same seed, and must sit inside its own gate band.
    val on  = MarketSim.measure(MarketSim.simPaths(MarketSim.Defaults, 40, 100,
                MarketSim.DefaultSeed), 100)
    val off = MarketSim.measure(MarketSim.simPaths(
                MarketSim.Defaults.copy(beliefShare = 0.0, capYears = 0.0), 40, 100,
                MarketSim.DefaultSeed), 100)
    assert(on.valDisp > off.valDisp + 0.08,
      f"the cycle must move dispersion materially: on ${on.valDisp}%.3f vs off ${off.valDisp}%.3f")
    assert(on.valDisp > MarketSim.ValDispBand._1 && on.valDisp < MarketSim.ValDispBand._2,
      f"the adopted default must sit inside its own band, read ${on.valDisp}%.3f")
    assert(off.valDisp < MarketSim.ValDispBand._1,
      f"the cycle-off world must FAIL the band, or the row does not discriminate: ${off.valDisp}%.3f")
  }

  test("the asymmetry dials are inert in every frozen release") {
    // Every release predates them, so the frozen rows carry leverage 0 and downShock 0 -- and
    // jumpSkew 0.4, the CONSTANT those releases compiled in, which is that dial's off-position
    // rather than 0.
    for (v, w) <- MarketSim.Releases do
      assert(w.leverage == 0.0 && w.downShock == 0.0 && w.jumpSkew == 0.4 &&
             w.newsRate == 0.0 && w.newsSize == 0.0 && w.refugeDays == 0.0,
        s"release $v predates the asymmetry mechanisms and must carry 0 / 0 / 0.4 / 0 / 0 / 0")
  }

  test("the satellite dials are inert in every frozen release") {
    for (v, w) <- MarketSim.Releases do
      assert(w.satBeta == 0.0 && w.satIdio == 0.0,
        s"release $v predates the satellite leg and must carry 0 / 0")
    // The engagement contract's off half: no satellite series exists to consume, and no
    // logSat column is written (schema 8 makes the column conditional on the dial).
    val p = MarketSim.simulate(MarketSim.Defaults, 2, MarketSim.DefaultSeed)
    assert(p.sat.isEmpty, "satBeta 0 must produce no satellite series")
  }

  test("the channel gate rows appear only when the channel runs, and can fail") {
    // The channel rows are not decoration: a leg or a bar that is materially wrong must FAIL the
    // gate, and a channels-off world must carry none of these rows at all.  Without the second
    // half the first is cheap -- a row that never appears cannot be wrong.
    val off = MarketSim.measure(MarketSim.simPaths(MarketSim.Defaults, 8, 40, MarketSim.DefaultSeed), 40)
    assert(off.sat.isEmpty && off.bars.isEmpty)
    val namesOff = MarketSim.gateChecks(MarketSim.SP500Anchors, off).map(_._1)
    assert(!namesOff.exists(n => n.startsWith("satellite") || n.startsWith("bar ")),
      "a channels-off world must carry no channel rows")

    val on = MarketSim.Defaults.copy(satBeta = 1.2, satIdio = 0.77, rangeScale = 0.63,
                                     rangeDown = 0.09, volIdio = 0.34)
    val stOn = MarketSim.measure(MarketSim.simPaths(on, 8, 40, MarketSim.DefaultSeed), 40)
    assert(stOn.sat.isDefined && stOn.bars.isDefined)
    val rowsOn = MarketSim.gateChecks(MarketSim.SP500Anchors, stOn)
    assert(rowsOn.count(_._1.startsWith("satellite")) >= 9,
      "the satellite must be graded on its full relational vector")
    assert(rowsOn.exists(_._1.startsWith("bar ")))

    // A leg at more than twice its anchored beta is not a second index; the gate must say so.
    val stB = MarketSim.measure(MarketSim.simPaths(on.copy(satBeta = 2.6), 8, 40, MarketSim.DefaultSeed), 40)
    assert(MarketSim.gateChecks(MarketSim.SP500Anchors, stB).exists((n, ok, _) => n.startsWith("satellite") && !ok),
      "a 2.6-beta leg must fail a satellite row")
    // A bar sampled at a wildly wrong scale is not a bar.
    val stW = MarketSim.measure(MarketSim.simPaths(on.copy(rangeScale = 2.0), 8, 40, MarketSim.DefaultSeed), 40)
    assert(MarketSim.gateChecks(MarketSim.SP500Anchors, stW).exists((n, ok, _) => n.startsWith("bar ") && !ok),
      "a 3x-scale bar must fail a bar row")
  }

  test("the range channel is inert in every frozen release") {
    for (v, w) <- MarketSim.Releases do
      assert(w.rangeScale == 0.0 && w.rangeDown == 0.0,
        s"release $v predates the range channel and must carry 0 / 0")
    // Off half: no bars exist.  On half: the extremes bracket every bar -- the sidecar's
    // canary is blind to this dial until it joins the world block, so these are the guards.
    val off = MarketSim.simulate(MarketSim.Defaults, 2, MarketSim.DefaultSeed)
    assert(off.logHi.isEmpty && off.logLo.isEmpty)
    val on = MarketSim.simulate(MarketSim.Defaults.copy(rangeScale = 0.63), 2, MarketSim.DefaultSeed)
    assertEquals(on.logHi.length, on.price.length)
    var prev = math.log(on.price(0))
    for i <- on.price.indices do
      val c = math.log(on.price(i))
      assert(on.logHi(i) >= math.max(prev, c) - 1e-9 && on.logLo(i) <= math.min(prev, c) + 1e-9,
        s"bar $i extremes must bracket open and close")
      prev = c
  }

  test("the volume channel is inert in every frozen release") {
    for (v, w) <- MarketSim.Releases do
      assert(w.volIdio == 0.0, s"release $v predates the volume channel and must carry 0")
    // Off half: no series.  On half: filled, finite, and volume leaves the RANGE series
    // bit-identical -- the channels share nothing but the sampled bar itself.
    val off = MarketSim.simulate(MarketSim.Defaults, 2, MarketSim.DefaultSeed)
    assert(off.logVolume.isEmpty)
    val barsOnly = MarketSim.simulate(MarketSim.Defaults.copy(rangeScale = 0.63), 2,
                                      MarketSim.DefaultSeed)
    val on = MarketSim.simulate(MarketSim.Defaults.copy(rangeScale = 0.63, volIdio = 0.34), 2,
                                MarketSim.DefaultSeed)
    assertEquals(on.logVolume.length, on.price.length)
    assert(on.logVolume.forall(_.isFinite))
    assert(on.logHi.sameElements(barsOnly.logHi), "volume must not move the sampled bars")
    assert(on.logLo.sameElements(barsOnly.logLo))
  }

  test("the asymmetry dials discriminate on their own statistics") {
    // Each mechanism moves the statistic it was added for, materially, on the same seed --
    // measured as the ADOPTED default against the same world with that one mechanism off.
    val dw = MarketSim.Defaults
    val on = MarketSim.measure(MarketSim.simPaths(dw, 40, 100, MarketSim.DefaultSeed), 100)
    val loff = MarketSim.measure(MarketSim.simPaths(dw.copy(leverage = 0.0),
                 40, 100, MarketSim.DefaultSeed), 100)
    assert(on.levCorr < loff.levCorr - 0.03,
      f"leverage 0.12 must deepen the leverage corr materially: ${on.levCorr}%.3f vs ${loff.levCorr}%.3f")
    val noff = MarketSim.measure(MarketSim.simPaths(dw.copy(newsRate = 0.0),
                 40, 100, MarketSim.DefaultSeed), 100)
    assert(on.semiExcess > noff.semiExcess + 1.5,
      f"the news channel must raise the downside excess materially: ${on.semiExcess}%.2f vs ${noff.semiExcess}%.2f")
    val roff = MarketSim.measure(MarketSim.simPaths(dw.copy(refugeDays = 0.0),
                 40, 100, MarketSim.DefaultSeed), 100)
    assert(on.tailHedge > roff.tailHedge + 0.10,
      f"refugeDays 1 must weaken the calm-day stock-bond coupling materially: ${on.tailHedge}%.2f vs ${roff.tailHedge}%.2f")
    val ds = MarketSim.measure(MarketSim.simPaths(dw.copy(downShock = 0.05),
               40, 100, MarketSim.DefaultSeed), 100)
    assert(ds.semiExcess > on.semiExcess + 1.0,
      f"downShock 0.05 must raise the downside excess materially: ${ds.semiExcess}%.2f vs ${on.semiExcess}%.2f")
  }

  test("-atrelease resolves every frozen release and the current default, and nothing else") {
    for (v, w) <- MarketSim.Releases do
      assertEquals(MarketSim.releaseWorld(v), Some(w), s"release $v must resolve to its frozen world")
    assertEquals(MarketSim.releaseWorld(MarketSim.Version), Some(MarketSim.Defaults),
      "the current version must resolve to the shipped default")
    assertEquals(MarketSim.releaseWorld("0.0.0"), None, "an unknown version must not resolve")
  }

  test("valuation dispersion grows with the horizon, which is why the verdict is pinned") {
    // The defect `GateYears` closes: sd log(p/fair) is the sample sd of a near-integrated gap,
    // so it GROWS with the measurement window -- 0.11 at 30 years against 0.21 at 100 on the
    // shipped world -- and a fixed floor read at the caller's -years graded the horizon, not
    // the world.  The ordering is far outside seed noise at 24 paths.
    val w     = MarketSim.Defaults
    val short = MarketSim.measure(MarketSim.simPaths(w, 24, 30, MarketSim.DefaultSeed), 30).valDisp
    val long  = MarketSim.measure(MarketSim.simPaths(w, 24, MarketSim.GateYears,
                  MarketSim.DefaultSeed), MarketSim.GateYears).valDisp
    assert(short < long * 0.8,
      f"short-horizon dispersion should read well below the century's: 30y $short%.3f vs 100y $long%.3f")
  }

  test("the verdict ensemble is pinned to the calibration horizon") {
    // Every verdict surface -- gate classes, fidelity table, sidecars -- grades at the
    // calibration horizon whatever -years the caller simulates; -emitgate 0 is the one explicit
    // opt-out.  At the defaults the verdict ensemble IS the report ensemble.
    assertEquals(MarketSim.verdictSpec(false, 200, 200, 100), (200, 100))
    assertEquals(MarketSim.verdictSpec(false, 200, 200, 30), (200, MarketSim.GateYears))
    assertEquals(MarketSim.verdictSpec(true, 200, 40, 33), (200, MarketSim.GateYears))
    assertEquals(MarketSim.verdictSpec(true, 50, 300, 33), (300, MarketSim.GateYears))
    assertEquals(MarketSim.verdictSpec(true, 200, 300, 100), (300, MarketSim.GateYears))
    assertEquals(MarketSim.verdictSpec(true, 0, 40, 33), (40, 33))
  }

  test("a news channel past the diffusion budget is refused") {
    // The news channel displaces diffusive variance, so past newsRate * newsSize^2 =
    // 252 * SigmaN^2 there is none left: the price runs on jumps alone and the bar channels'
    // world level (realized sd over the MEAN diffusion sd) has no denominator.  Such a world is
    // refused at the CLI, not clamped into a NaN bar -- and -calibrate's ranges cannot reach it.
    val dw = MarketSim.Defaults
    assert(MarketSim.newsBudgetRefusal(dw.newsRate, dw.newsSize).isEmpty)
    assert(MarketSim.newsBudgetRefusal(0.0, 1.0).isEmpty, "rate 0 is the channel off")
    assert(MarketSim.newsBudgetRefusal(1.3, 0.097).isEmpty, "just inside the budget")
    assert(MarketSim.newsDampAt(1.3, 0.10) <= 0.0, "past it the damp clamps to nothing")
    val why = MarketSim.newsBudgetRefusal(1.3, 0.10).getOrElse(fail("past the budget is refused"))
    assert(why.contains("0.0975"), why)
    def hi(name: String): Double =
      MarketSim.CalibrateRanges.find(_._1 == name).map(_._3).getOrElse(fail(s"no range for $name"))
    assert(MarketSim.newsBudgetRefusal(hi("newsRate"), hi("newsSize")).isEmpty)
  }

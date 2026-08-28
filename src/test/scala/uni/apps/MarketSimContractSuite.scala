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
    val a = MarketSim.SP500Anchors
    assertEquals(a.vol, 16.0)
    assertEquals(a.retVol, 0.69)
    assertEquals(a.kurt, 28.0)
    assertEquals(a.ac1, 0.299)
    assertEquals(a.ac20, 0.225)
    assertEquals(a.crashes, 20.7)
    assertEquals(a.medDepth, -27.1)
    assertEquals(a.worstDepth, -56.8)
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

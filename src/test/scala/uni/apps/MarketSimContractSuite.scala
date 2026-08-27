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
    val actual   = MarketSim.FitTargets.map(_._1).sorted
    assertEquals(actual, expected,
      "the fidelity targets and EquityTargets + BondTargets are no longer the same set. A target " +
      "was added, removed or renamed: classify it in one list (and only one) so the equity " +
      "section cannot silently lose or miss a row.")
  }

  test("the anchor groups partition the fidelity targets") {
    // Every fidelity target must carry exactly one anchor horizon, or `-noise` silently skips
    // it -- the same silent-shrinkage failure the equity/bond partition guards against, on the
    // horizon axis.
    val expected = MarketSim.AnchorGroups.flatMap(_._3).sorted
    val actual   = MarketSim.FitTargets.map(_._1).sorted
    assertEquals(actual, expected,
      "the fidelity targets and the anchor groups are no longer the same set. A target was " +
      "added, removed or renamed: give it a horizon in exactly one anchor group, so the noise " +
      "report cannot silently skip it.")
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

package uni.apps

import munit.FunSuite

/**
 * Contracts on the report machinery itself — no fixture, no ensemble. The Rust twin carries the
 * same checks in `contract_tests`.
 */
class MarketSimContractSuite extends FunSuite:

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

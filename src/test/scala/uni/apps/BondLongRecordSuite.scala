package uni.apps

import munit.FunSuite
import uni.*

/**
 * THE GRADED BOND CRASH RECORDS (`Anchors.bondCrash`) are each set's 1962-2026 fixture's medians
 * under its stated rule, by the model's own median: the market's declines for the S&P
 * (`crash-response-1962-2026-10-05.tsv`), Ken French's HiTec's for the Nasdaq
 * (`crash-response-1962-hitec-2026-10-06.tsv`).  This re-derives both records from the rows and
 * checks the regime column follows the rule.  The Rust twin carries the same checks in
 * `bond_crash_tests`.
 */
class BondLongRecordSuite extends FunSuite:

  case class Row(equityPct: Double, bondPct: Double, duration: Double, cpiPeak: Double, cpiTrough: Double, regime: String):
    def scaled: Double = bondPct * MarketSim.DurationRef / duration

  /** Empty where the fixture is absent, which is a skip and not a failure: a source tarball ships
    * without `test-data/`. */
  def rowsOf(fixture: String): Vector[Row] =
    val p = fixture.asPath
    if !p.exists then Vector.empty
    else
      p.lines.toVector
        .filter(l => !l.startsWith("#") && !l.startsWith("peak\t") && l.trim.nonEmpty)
        .map { l =>
          val f = l.split("\t")
          Row(f(2).toDouble, f(3).toDouble, f(4).toDouble, f(5).toDouble, f(6).toDouble, f(7))
        }

  for (a, fixture, declines, inflation) <- Vector(
      (MarketSim.SP500Anchors, "test-data/bond-anchors/crash-response-1962-2026-10-05.tsv", 17, 7),
      (MarketSim.NasdaqAnchors, "test-data/bond-anchors/crash-response-1962-hitec-2026-10-06.tsv", 22, 6))
  do
    test(s"${a.name}'s crash record is its 1962 fixture's medians under its stated rule") {
      val rows = rowsOf(fixture)
      if rows.nonEmpty then
        def medianOf(regime: String) = MarketSim.pctile(rows.filter(_.regime == regime).map(_.scaled), 0.5)
        assertEqualsDouble(a.bondCrash._1, medianOf("growth"), 1e-5, f"growth median ${medianOf("growth")}%.6f")
        assertEqualsDouble(a.bondCrash._2, medianOf("inflation"), 1e-5, f"inflation median ${medianOf("inflation")}%.6f")
        assertEquals(rows.size, declines, s"$fixture: declines of 15%+ since 1962")
        assertEquals(rows.count(_.regime == "inflation"), inflation)
        for r <- rows do
          assert(r.equityPct <= -15.0)
          assertEquals(r.regime == "inflation", r.cpiTrough > r.cpiPeak && r.cpiTrough >= MarketSim.CrashCpiFloor,
            s"the regime column must follow the stated rule (CPI rose over the decline and read 4%+ at " +
            s"the trough): peak CPI ${r.cpiPeak}, trough CPI ${r.cpiTrough}")
    }

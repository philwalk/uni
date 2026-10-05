package uni.apps

import munit.FunSuite
import uni.*

/**
 * The bond crash rows' 1962-2026 reference (`crash-response-1962-2026-10-05.tsv`) is REPORTED, not
 * graded, so the one thing that can go wrong is the printed claim drifting from the fixture: this
 * re-derives `MarketSim.BondLongRecord` from the rows and checks the regime column follows the
 * rule the fixture states. The Rust twin carries the same checks in `bond_crash_tests`.
 */
class BondLongRecordSuite extends FunSuite:

  val Fixture = "test-data/bond-anchors/crash-response-1962-2026-10-05.tsv"

  case class Row(equityPct: Double, bondPct: Double, duration: Double, cpiPeak: Double, cpiTrough: Double, regime: String):
    def scaled: Double = bondPct * MarketSim.DurationRef / duration

  /** Empty where the fixture is absent, which is a skip and not a failure: a source tarball ships
    * without `test-data/`. */
  lazy val rows: Vector[Row] =
    val p = Fixture.asPath
    if !p.exists then Vector.empty
    else
      p.lines.toVector
        .filter(l => !l.startsWith("#") && !l.startsWith("peak\t") && l.trim.nonEmpty)
        .map { l =>
          val f = l.split("\t")
          Row(f(2).toDouble, f(3).toDouble, f(4).toDouble, f(5).toDouble, f(6).toDouble, f(7))
        }

  def median(xs: Vector[Double]): Double =
    val s = xs.sorted
    if s.isEmpty then Double.NaN
    else if s.size % 2 == 1 then s(s.size / 2)
    else (s(s.size / 2 - 1) + s(s.size / 2)) / 2.0

  def medianOf(regime: String): Double = median(rows.filter(_.regime == regime).map(_.scaled))

  test("the reported 1962-2026 reference is the fixture's medians under its stated rule") {
    if rows.nonEmpty then
      val (growth, inflation) = MarketSim.BondLongRecord
      assertEqualsDouble(growth, medianOf("growth"), 0.05, f"growth median ${medianOf("growth")}%.2f")
      assertEqualsDouble(inflation, medianOf("inflation"), 0.05, f"inflation median ${medianOf("inflation")}%.2f")
      assertEquals(rows.size, 17, "17 declines of 15%+ since 1962")
      assertEquals(rows.count(_.regime == "inflation"), 7)
      for r <- rows do
        assert(r.equityPct <= -15.0)
        assertEquals(r.regime == "inflation", r.cpiTrough > r.cpiPeak && r.cpiTrough >= 4.0,
          s"the regime column must follow the stated rule (CPI rose over the decline and read 4%+ at " +
          s"the trough): peak CPI ${r.cpiPeak}, trough CPI ${r.cpiTrough}")
  }

package uni.apps

import munit.FunSuite
import uni.*

/**
 * The two bond crash-response targets are MEDIANS across drawdown episodes, and through 0.21.0 both
 * shipped as single episodes: `+20.0` is 2008 alone, the largest of five, and `-25.0` is a rounding
 * of the one inflation-regime drawdown. This re-derives both from the checked-in episodes so the
 * targets and the statistic they are compared against cannot drift apart again.
 *
 * The Rust twin carries the same checks in `bond_crash_tests`, against the same file.
 */
class BondCrashSuite extends FunSuite:

  val Fixture = "test-data/bond-anchors/crash-response-2026-08-29.tsv"

  case class Row(peak: String, trough: String, equityPct: Double, bondPct: Double, regime: String)

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
          Row(f(0), f(1), f(2).toDouble, f(3).toDouble, f(4))
        }

  def median(xs: Vector[Double]): Double =
    val s = xs.sorted
    if s.isEmpty then Double.NaN
    else if s.size % 2 == 1 then s(s.size / 2)
    else (s(s.size / 2 - 1) + s(s.size / 2)) / 2.0

  def medianOf(regime: String): Double = median(rows.filter(_.regime == regime).map(_.bondPct))

  test("both bond crash targets are the record's medians, not its best episode") {
    if rows.nonEmpty then
      val a = MarketSim.fitTargets(MarketSim.SP500Anchors)
      def target(n: String) = a.find(_._1 == n).map(_._3).getOrElse(fail(s"no target [$n]"))
      assertEqualsDouble(target("bond growth-crash"), medianOf("growth"), 0.05,
        f"the growth-crash target no longer matches the record's median across its growth-shock " +
        f"drawdowns (${medianOf("growth")}%.1f%%)")
      assertEqualsDouble(target("bond infl-crash"), medianOf("inflation"), 0.05,
        f"the inflation-crash target no longer matches the record's inflation-regime drawdown " +
        f"(${medianOf("inflation")}%.1f%%)")
  }

  test("the pre-0.22.0 targets were the extremes, which is why they moved") {
    // Kept as a test so the claim in `fitTargets` is checkable rather than asserted: +20.0 is the
    // MAXIMUM of the growth episodes, not their median.
    if rows.nonEmpty then
      val growth = rows.filter(_.regime == "growth").map(_.bondPct)
      assertEqualsDouble(growth.max, 22.4, 0.05,
        "the largest growth-shock bond rally is no longer 2008's; the account of where +20.0 came " +
        "from rests on it")
      assert(growth.max > medianOf("growth") * 2.0,
        f"the growth episodes no longer have a max (${growth.max}%.1f) far above their median " +
        f"(${medianOf("growth")}%.1f); if the spread has closed, re-read the anchor's provenance")
  }

  test("the episode set is the drawdowns the model would count") {
    if rows.nonEmpty then
      assert(rows.forall(_.equityPct <= -15.0),
        s"an episode shallower than the model's 15% threshold is in the fixture: " +
        rows.filter(_.equityPct > -15.0).map(_.peak).mkString(", "))
      assert(rows.count(_.regime == "inflation") == 1,
        "the record's inflation-regime drawdown count has changed; the -34.7% target is a median " +
        "of one and that is stated in the fixture, so a second episode changes the target")
      assert(rows.size >= 5, s"only ${rows.size} episodes; the medians below that are not worth the name")
  }

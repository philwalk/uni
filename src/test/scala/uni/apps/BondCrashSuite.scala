package uni.apps

import munit.FunSuite
import uni.*

/**
 * The bond crash rows' TLT-era reading (`crash-response-2026-09-25.tsv`) is REPORTED beside the
 * graded rows: the medians of each episode's TLT log return scaled by `DurationRef` over the fund's
 * empirical duration on that span.  This re-derives `MarketSim.BondTltRecord` from the checked-in
 * episodes, and checks the model reads the records' regime rule on its own CPI and that a history's
 * reading is `measure` on that history alone.
 *
 * The Rust twin carries the same checks in `bond_crash_tests`, against the same files.
 */
class BondCrashSuite extends FunSuite:

  val Fixture = "test-data/bond-anchors/crash-response-2026-09-25.tsv"

  case class Row(peak: String, trough: String, equityPct: Double, bondPct: Double, duration: Double, regime: String):
    /** The episode's bond return at the model bond's duration. */
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
          Row(f(0), f(1), f(2).toDouble, f(3).toDouble, f(4).toDouble, f(5))
        }

  /** The regime's median of the scaled returns, by the model's own median. */
  def medianOf(regime: String): Double = MarketSim.pctile(rows.filter(_.regime == regime).map(_.scaled), 0.5)

  test("the reported TLT reading is its fixture's medians") {
    if rows.nonEmpty then
      val (growth, inflation) = MarketSim.BondTltRecord
      assertEqualsDouble(growth, medianOf("growth"), 0.05, f"growth median ${medianOf("growth")}%.2f")
      assertEqualsDouble(inflation, medianOf("inflation"), 0.05, f"inflation median ${medianOf("inflation")}%.2f")
  }

  test("the pre-0.22.0 targets were the extremes, which is why they moved") {
    // Kept as a test so the account of the old targets stays checkable: +20.0 was the MAXIMUM of the
    // unscaled growth episodes, not their median.
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
      assert(rows.forall(r => r.duration >= 10.0 && r.duration <= 20.0),
        "an episode's TLT duration is outside the fund's 10-20 year range; the scaling to " +
        "DurationRef rests on it")
      assert(rows.count(_.regime == "inflation") == 1,
        "the TLT era's inflation-regime drawdown count has changed; its reported -27.9% is a " +
        "median of one, so a second episode changes it")
      assert(rows.size >= 5, s"only ${rows.size} episodes; the medians below that are not worth the name")
  }

  test("the crash regime is the records' rule on the path's CPI") {
    val y = MarketSim.DaysPerYear
    val n = 3 * y
    // 2% a year for two years, then 6%
    val cpi = Array.tabulate(n) { k =>
      val t = k.toDouble / y
      if t < 2.0 then math.exp(0.02 * t) else math.exp(0.04 + 0.06 * (t - 2.0))
    }
    def ep(peak: Int, trough: Int) = MarketSim.Episode(peak, trough, -1, -20.0)
    assertEquals(MarketSim.crashIsInflation(cpi, ep(y + 10, n - 1)), Some(true))
    assertEquals(MarketSim.crashIsInflation(cpi, ep(y + 10, 2 * y - 1)), Some(false))
    assertEquals(MarketSim.crashIsInflation(cpi, ep(n - 1, n - 1)), Some(false))
    assertEquals(MarketSim.crashIsInflation(cpi, ep(10, n - 1)), None)
  }

  test("a history's crash reading is measure on it alone, bit for bit") {
    val w = MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe"))
    val h = MarketSim.SP500Anchors.bondCrashYears
    for p0 <- MarketSim.simPaths(w, 3, 80, MarketSim.DefaultSeed) do
      val p   = p0.head(h)
      val st  = MarketSim.measure(Vector(p), h)
      val got = MarketSim.bondCrashOf(p)
      assertEquals(java.lang.Double.doubleToRawLongBits(got(0)), java.lang.Double.doubleToRawLongBits(st.bondGrowth))
      assertEquals(java.lang.Double.doubleToRawLongBits(got(1)), java.lang.Double.doubleToRawLongBits(st.bondInfl))
  }

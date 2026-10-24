package uni.apps

import munit.FunSuite
import uni.*

/**
 * The bond crash rows' TLT-era reading (`crash-response-2026-09-25.tsv`) is REPORTED beside the
 * graded rows: `crashFit` on each episode's TLT log return scaled by `DurationRef` over the fund's
 * empirical duration on that span, against the inflation the decline was seen under.  This
 * re-derives `MarketSim.BondTltRecord` from the checked-in episodes bit for bit, checks the fit and
 * the inflation read on a path's own CPI, and that a history's reading is `measure` on that history
 * alone.
 *
 * The Rust twin carries the same checks in `bond_crash_tests`, against the same files.
 */
class BondCrashSuite extends FunSuite:

  val Fixture = "test-data/bond-anchors/crash-response-2026-09-25.tsv"

  case class Row(peak: String, trough: String, equityPct: Double, bondPct: Double, duration: Double,
                 cpiPeak: Double, cpiTrough: Double, regime: String):
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
          Row(f(0), f(1), f(2).toDouble, f(3).toDouble, f(4).toDouble, f(5).toDouble, f(6).toDouble, f(7))
        }

  private def bits(x: Double): Long = java.lang.Double.doubleToRawLongBits(x)

  /** The scaled returns' median over the informational regime column, by the model's own median. */
  def medianOf(regime: String): Double = MarketSim.pctile(rows.filter(_.regime == regime).map(_.scaled), 0.5)

  test("the reported TLT reading is its fixture's crash fit") {
    if rows.nonEmpty then
      val fit = MarketSim.crashFit(rows.map(r => (r.cpiTrough, r.scaled)))
      assertEquals(bits(MarketSim.BondTltRecord._1), bits(fit(0)), f"the response at 2%% inflation ${fit(0)}%.6f")
      assertEquals(bits(MarketSim.BondTltRecord._2), bits(fit(1)), f"the slope per point ${fit(1)}%.6f")
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
      assert(rows.count(_.cpiTrough >= 4.0) == 1,
        "the TLT era's count of declines seen under 4%+ inflation has changed; the reading is " +
        "reported, not graded, because one such episode is too few")
      assert(rows.size >= 5, s"only ${rows.size} episodes; a fit over fewer is not worth the name")
  }

  test("the crash fit reads the moves on the inflation seen at the trough") {
    val y = MarketSim.DaysPerYear
    val n = 3 * y
    // 2% a year for two years, then 6%
    val cpi = Array.tabulate(n) { k =>
      val t = k.toDouble / y
      if t < 2.0 then math.exp(0.02 * t) else math.exp(0.04 + 0.06 * (t - 2.0))
    }
    def ep(peak: Int, trough: Int) = MarketSim.Episode(peak, trough, -1, -20.0)
    val late = MarketSim.crashSeen(cpi, ep(y + 10, n - 1)).getOrElse(fail("a reading"))
    assert(math.abs(late - 6.18) < 0.05, late.toString)
    val early = MarketSim.crashSeen(cpi, ep(y + 10, 2 * y - 1)).getOrElse(fail("a reading"))
    assert(math.abs(early - 2.02) < 0.05, early.toString)
    assertEquals(MarketSim.crashSeen(cpi, ep(10, y - 1)), None)
    // moves falling two per point of inflation from eight at zero: exact on the line
    val pts = Vector((1.0, 6.0), (2.0, 4.0), (4.0, 0.0), (3.0, 2.0))
    assertEquals(MarketSim.crashFit(pts), Vector(4.0, -2.0))
    assert(MarketSim.crashFit(pts.take(2)).forall(_.isNaN))
    assert(MarketSim.crashFit(Vector((1.0, 1.0), (1.0, 2.0), (1.0, 3.0))).forall(_.isNaN))
  }

  test("a history's crash reading is measure on it alone, bit for bit") {
    val w = MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe"))
    val h = MarketSim.SP500Anchors.bondCrashYears
    for p0 <- MarketSim.simPaths(w, 3, 80, MarketSim.DefaultSeed) do
      val p   = p0.head(h)
      val st  = MarketSim.measure(Vector(p), h)
      val got = MarketSim.bondCrashOf(p)
      assertEquals(bits(got(0)), bits(st.bondGrowth))
      assertEquals(bits(got(1)), bits(st.bondInfl))
  }

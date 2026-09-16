package uni.apps

import munit.FunSuite
import uni.*

/** THE TYPICAL YEAR's ruler (`yearvol-2026-09-15.tsv`): the median calendar-year vol over the
  * pooled vol, which is what separates an ordinary year from an episode.  The shipped
  * `typical-year vol %` anchors are this file's medianYearVol rows, and the finding the row exists
  * for -- that the Nasdaq anchor's pooled vol is one bust -- is pinned here so a re-measured
  * fixture that no longer shows it fails loudly.  The Rust twin's `year_vol_anchor_tests` reads
  * the same file. */
class YearVolAnchorSuite extends FunSuite:
  private val fixture = Paths.get("test-data/equity-anchors/yearvol-2026-09-15.tsv")
  private lazy val rows: Vector[Vector[String]] =
    fixture.lines.toVector
      .filterNot(l => l.startsWith("#") || l.startsWith("section" + "\t") || l.trim.isEmpty)
      .map(_.split('\t').toVector)

  private def value(series: String, window: String, stat: String): Double =
    rows.find(r => r(0) == "yearvol" && r(1) == series && r(2) == window && r(3) == stat)
      .getOrElse(fail(s"fixture row [yearvol $series $window $stat] missing"))(5).toDouble

  test("the shipped typical-year anchors are the fixture's medianYearVol rows") {
    assertEqualsDouble(MarketSim.SP500Anchors.yearVol, value("CRSP", "w1954", "medianYearVol"), 0.1)
    assertEqualsDouble(MarketSim.NasdaqAnchors.yearVol, value("QQQ", "w1999", "medianYearVol"), 0.1)
  }

  test("the Nasdaq anchor's pooled vol is one episode: QQQ's whole history alone concentrates") {
    // every other window and series spreads its vol the same way; only the window holding
    // 2000-02 (and the century holding 1929-33) reads under 0.76
    assert(value("QQQ", "w1999", "ratio") < 0.72, "QQQ 1999-2026 median year under 0.72 of pooled")
    for (s, w) <- Vector(("QQQ", "w2007"), ("SPY", "w1993"), ("CRSP", "w1954"), ("CRSP", "w1990"), ("CRSP", "w1999")) do
      assert(value(s, w, "ratio") >= 0.78, s"$s $w: an ordinary window reads 0.78 or more of pooled")
    assert(value("CRSP", "w1926", "ratio") < 0.76, "the century concentrates too: 1929-33")
    // and the typical Nasdaq year is the S&P's typical year plus about a fifth, not plus a half
    val q = value("QQQ", "w1999", "medianYearVol") / value("CRSP", "w1954", "medianYearVol")
    assert(q > 1.3 && q < 1.55, s"typical-year ratio QQQ/CRSP $q")
  }

  test("the typical year is the median over whole 252-session blocks of each block's own vol") {
    // three blocks at |r| = 0.01, 0.03, 0.02 and a partial fourth that must be ignored
    val r = Array.fill(252)(0.01) ++ Array.fill(252)(0.03) ++ Array.fill(252)(0.02) ++ Array.fill(100)(0.09)
    assertEqualsDouble(MarketSim.yearVolOf(r), 0.02 * math.sqrt(252.0), 1e-12)
    // shorter than a year: the pooled vol
    val short = Array.fill(100)(0.02)
    assertEqualsDouble(MarketSim.yearVolOf(short), 0.02 * math.sqrt(252.0), 1e-12)
  }

  test("both shipped worlds read a typical year inside their own band") {
    for (name, a) <- Vector(("default", MarketSim.SP500Anchors), ("0.24.3-nasdaq", MarketSim.NasdaqAnchors)) do
      val w = if name == "default" then MarketSim.Defaults else MarketSim.namedWorld(name).get._1
      val st = MarketSim.measure(MarketSim.simPaths(w, 24, 30, 20260915L), 30)
      val y  = st.yearVol * 100.0
      assert(y >= a.yearVolBand._1 && y <= a.yearVolBand._2,
        f"$name typical-year vol $y%.1f outside ${a.yearVolBand}")
      assert(y < st.vol * 100.0, f"$name: the typical year ($y%.1f) sits under the pooled vol (${st.vol * 100}%.1f)")
  }

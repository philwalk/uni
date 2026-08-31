package uni.apps

import munit.FunSuite
import uni.*

/**
 * The six asymmetry anchors are MEASURED numbers; these re-derive every one from the checked-in
 * fixtures so the shipped literal and the record reading cannot drift apart. The fixtures also
 * hold a committed NEGATIVE result — the Patton-Sheppard signed-half regression's era-split
 * columns — which is pinned here so nobody re-fights that measurement.
 *
 * The Rust twin carries the same checks in `asymmetry_anchor_tests`, against the same files.
 */
class AsymmetryAnchorSuite extends FunSuite:

  val Asym = "test-data/equity-anchors/asymmetry-2026-08-31.tsv"
  val Tail = "test-data/bond-anchors/tailcorr-2026-08-31.tsv"

  /** Empty where the fixture is absent, which is a skip and not a failure: the artifact ships
    * without `test-data/`, so a source-tarball build must not fail here. */
  def rows(path: String): Vector[Vector[String]] =
    val p = path.asPath
    if !p.exists then Vector.empty
    else p.lines.toVector
      .filterNot(l => l.startsWith("#") || l.startsWith("window\t") || l.startsWith("pair\t") ||
                      l.trim.isEmpty)
      .map(_.split('\t').toVector)

  def field(rs: Vector[Vector[String]], key0: String, key1: String, col: Int): Double =
    rs.find(r => r(0) == key0 && r(1) == key1)
      .getOrElse(fail(s"fixture row [$key0 $key1] missing"))(col).toDouble

  test("the shipped asymmetry anchors are the fixture rows") {
    val a = rows(Asym)
    assume(a.nonEmpty, "fixture not present in this tree (source tarball?)")
    // sdRatio column 5, levCorr column 9; the shipped excess is 100*(sdRatio - 1).
    assertEqualsDouble(MarketSim.SP500Anchors.semiExcess,
      (field(a, "c1954", "CRSP-VW", 5) - 1.0) * 100.0, 0.005,
      "S&P downside vol excess drifted from the fixture")
    assertEqualsDouble(MarketSim.NasdaqAnchors.semiExcess,
      (field(a, "wfull", "QQQ", 5) - 1.0) * 100.0, 0.005,
      "QQQ downside vol excess drifted from the fixture")
    assertEqualsDouble(MarketSim.SP500Anchors.levCorr, field(a, "c1954", "CRSP-VW", 9), 5e-5,
      "S&P leverage corr drifted from the fixture")
    assertEqualsDouble(MarketSim.NasdaqAnchors.levCorr, field(a, "wfull", "QQQ", 9), 5e-5,
      "QQQ leverage corr drifted from the fixture")
  }

  test("the shipped tail hedge anchors are the fixture rows") {
    val t = rows(Tail)
    assume(t.nonEmpty, "fixture not present in this tree (source tarball?)")
    def corrL(pair: String) =
      t.find(_(0) == pair).getOrElse(fail(s"$pair row missing"))(4).toDouble
    assertEqualsDouble(MarketSim.SP500Anchors.tailHedge, corrL("SPY/TLT"), 5e-4,
      "S&P tail hedge drifted from the fixture")
    assertEqualsDouble(MarketSim.NasdaqAnchors.tailHedge, corrL("QQQ/TLT"), 5e-4,
      "QQQ tail hedge drifted from the fixture")
  }

  test("the signed-half regression is era-split, and the leverage corr is not") {
    // The committed negative result: on close-only daily data the signed-half block regression
    // flips sign between CRSP eras, so it cannot anchor a row -- the daily leverage correlation,
    // which does not flip, is what the shipped row grades.  Pinned so the settled measurement is
    // not re-fought each cycle (the `longhorizon-2026-08-30.tsv` pattern).
    val a = rows(Asym)
    assume(a.nonEmpty, "fixture not present in this tree (source tarball?)")
    val la1926 = field(a, "c1926", "CRSP-VW", 8)
    val la1990 = field(a, "c1990", "CRSP-VW", 8)
    assert(la1926 < 0.0 && la1990 > 0.0,
      s"the era split this fixture exists to record has changed: c1926 $la1926 c1990 $la1990")
    for w <- Vector("c1926", "c1954", "c1990") do
      val lc = field(a, w, "CRSP-VW", 9)
      assert(lc >= -0.11 && lc <= -0.08,
        s"CRSP $w leverage corr $lc left the stable range the anchor relies on")
  }

package uni.apps

import munit.FunSuite
import uni.*

/**
 * The drawdown-shape references are MEASURED numbers on the model's own episode definition and
 * median; this re-derives both anchor sets' references from the checked-in fixture so the code
 * and the record cannot drift apart.  The Rust twin carries the same checks in
 * `dd_shape_anchor_tests`, against the same file.
 */
class DdShapeAnchorSuite extends FunSuite:

  val Fixture = "test-data/equity-anchors/ddshape-2026-09-02.tsv"

  /** Empty where the fixture is absent, which is a skip and not a failure: the artifact ships
    * without `test-data/`, so a source-tarball build must not fail here. */
  def rows(path: String): Vector[Vector[String]] =
    val p = path.asPath
    if !p.exists then Vector.empty
    else p.lines.toVector
      .filterNot(l => l.startsWith("#") || l.startsWith("set\t") || l.trim.isEmpty)
      .map(_.split('\t').toVector)

  val SetName = Map(MarketSim.SP500Anchors.name -> "sp500", MarketSim.NasdaqAnchors.name -> "nasdaq")

  test("every reference row of both anchor sets is the fixture's row") {
    val rs = rows(Fixture)
    assume(rs.nonEmpty, s"$Fixture absent")
    for a <- MarketSim.AnchorSets do
      val set = SetName.getOrElse(a.name, fail(s"no fixture set name for ${a.name}"))
      assert(a.ddRefs.nonEmpty, s"${a.name} carries no shape reference")
      for r <- a.ddRefs; (thr, eps, perYr, depth, decl, recov, undw, wds) <- r.rows do
        val pct = (thr * 100).toInt.toString
        val f = rs.find(x => x(0) == set && x(1) == r.series && x(5) == pct &&
                             s"${x(2).take(4)}-${x(3).take(4)}" == r.window)
          .getOrElse(fail(s"fixture row [$set ${r.series} ${r.window} $pct%] missing"))
        val tag = s"${a.name} ${r.series} ${r.window} $pct%"
        assertEqualsDouble(r.years, f(4).toDouble, 0.005, s"$tag years")
        assertEquals(eps, f(6).toInt, s"$tag episodes")
        assertEqualsDouble(perYr, f(7).toDouble, 0.0005, s"$tag per year")
        assertEqualsDouble(depth, f(8).toDouble, 0.05, s"$tag depth")
        assertEquals(decl, f(9).toInt, s"$tag decline")
        assertEquals(recov, f(10).toInt, s"$tag recovery")
        assertEquals(undw, f(11).toInt, s"$tag underwater")
        assertEqualsDouble(wds, f(12).toDouble, 0.0005, s"$tag worst-day share")
  }

  test("each reference carries both thresholds, and the primary is the longest history") {
    // The ratio reads against the FIRST reference, so it must be the one with the most episodes
    // behind its medians; the fixture's shorter windows exist to show the spread, not to anchor.
    for a <- MarketSim.AnchorSets do
      for r <- a.ddRefs do
        assertEquals(r.rows.map(_._1), Vector(0.10, 0.20), s"${a.name} ${r.series} ${r.window}")
      assert(a.ddRefs.head.years >= a.ddRefs.map(_.years).max,
        s"${a.name}: the primary reference must be the longest history")
  }

  test("the reference median is the model's own: the upper middle element, never the average") {
    // The 0.23.0 rows were NumPy medians and disagreed with the model rows by up to a third of a
    // statistic at four episodes.  Pinned here so the convention cannot silently revert.
    assertEqualsDouble(MarketSim.pctile(Vector(1.0, 2.0, 3.0, 4.0), 0.5), 3.0, 1e-12)
    assertEqualsDouble(MarketSim.pctile(Vector(4.0, 1.0, 3.0, 2.0), 0.5), 3.0, 1e-12)
  }

  test("the eight carried spreads are per anchor set, and the Nasdaq deep rung is the sharp one") {
    // These spreads were inline S&P constants through 0.23.0, so the Nasdaq loss weighted its
    // rows with the S&P world's spreads.  The one that matters most: the deep rung's spread at
    // Nasdaq volatility is a fraction of the S&P default's.
    val sp = MarketSim.SP500Anchors; val nq = MarketSim.NasdaqAnchors
    for a <- MarketSim.AnchorSets do
      for (nm, v) <- Vector("valDispSd" -> a.valDispSd, "d5Sd" -> a.d5Sd, "d10Sd" -> a.d10Sd,
                            "d20Sd" -> a.d20Sd, "bondVolSd" -> a.bondVolSd,
                            "bondGrowthSd" -> a.bondGrowthSd, "bondInflSd" -> a.bondInflSd,
                            "bondDepthSd" -> a.bondDepthSd) do
        assert(v > 0.0 && v.isFinite, s"${a.name} $nm must be a positive spread, read $v")
    assert(nq.d20Sd < sp.d20Sd / 4, s"Nasdaq d20 spread ${nq.d20Sd} vs S&P ${sp.d20Sd}")
  }

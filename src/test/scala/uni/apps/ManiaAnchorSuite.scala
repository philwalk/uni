package uni.apps

import munit.FunSuite
import uni.*

/** THE WINGS' ruler (`mania-2026-09-15.tsv`): the valuation cycle about its own 20-year mean on
  * Shiller's CAPE.  The shipped `upper wing months %` / `lower wing months %` anchors are this
  * file's w1901 rows, the record's symmetry is pinned (the model's cycle had the amplitude with
  * the wrong sign, which is the finding the rows exist for), and `wingsOf` is checked on series
  * whose wings are known.  The Rust twin's `mania_anchor_tests` reads the same file. */
class ManiaAnchorSuite extends FunSuite:
  private val fixture = Paths.get("test-data/equity-anchors/mania-2026-09-15.tsv")
  private lazy val rows: Vector[Vector[String]] =
    fixture.lines.toVector
      .filterNot(l => l.startsWith("#") || l.startsWith("section" + "\t") || l.trim.isEmpty)
      .map(_.split('\t').toVector)

  private def value(section: String, series: String, window: String, stat: String): Double =
    rows.find(r => r(0) == section && r(1) == series && r(2) == window && r(3) == stat)
      .getOrElse(fail(s"fixture row [$section $series $window $stat] missing"))(5).toDouble

  test("the shipped wing anchors are the fixture's w1901 shares, and both sets share them") {
    for a <- Vector(MarketSim.SP500Anchors, MarketSim.NasdaqAnchors) do
      assertEqualsDouble(a.wingUp, value("wing", "CAPE", "w1901", "shareAbove50") * 100, 0.1)
      assertEqualsDouble(a.wingDown, value("wing", "CAPE", "w1901", "shareBelow50") * 100, 0.1)
  }

  test("the record's wings are symmetric, and its manias are 1929 and 1999") {
    val up = value("wing", "CAPE", "w1901", "shareAbove50"); val dn = value("wing", "CAPE", "w1901", "shareBelow50")
    assert(math.abs(up - dn) < 0.02, s"symmetric wings: $up above, $dn below")
    assert(value("peak", "CAPE", "peak1929", "level") > 0.9 && value("peak", "CAPE", "peak1999", "level") > 0.9,
      "1929 and 1999 stood more than +0.9 over the 20-year mean")
    assert(value("peak", "CAPE", "peak1937", "level") > 0.5 && value("peak", "CAPE", "peak2021", "level") > 0.5,
      "1937 and 2021 crossed +0.5")
    assert(value("wing", "CAPE", "w1901", "excursionsPerCentury") > 2.5, "three or four spells past +0.5 a century")
  }

  test("wingsOf reads a level's time past +-0.5 over its own 20-year mean") {
    val burn = (MarketSim.BustMeanYears * MarketSim.DaysPerYear).toInt
    val fund = Array.fill(burn + 30 * 252)(1.0)
    // a constant level has no wings; a step of +2 held for 30 years is above +0.5 until the
    // reference has climbed within 0.5 of it, which takes 20 x ln 4 = 27.7 years; the mirror
    // image is the lower wing
    val flat = MarketSim.wingsOf(fund, fund)
    assertEquals((flat._1, flat._2), (0.0, 0.0))
    val upP = fund.zipWithIndex.map((f, i) => if i < burn then f else f * math.exp(2.0))
    val up  = MarketSim.wingsOf(upP, fund)
    assert(up._1 / up._3 > 0.9 && up._2 == 0.0, s"upper wing ${up._1 / up._3}")
    val dnP = fund.zipWithIndex.map((f, i) => if i < burn then f else f * math.exp(-2.0))
    val dn  = MarketSim.wingsOf(dnP, fund)
    assert(dn._2 / dn._3 > 0.9 && dn._1 == 0.0, s"lower wing ${dn._2 / dn._3}")
    // shorter than the reference's burn-in: nothing read
    assertEquals(MarketSim.wingsOf(fund.take(burn), fund.take(burn)), (0.0, 0.0, 0.0))
  }

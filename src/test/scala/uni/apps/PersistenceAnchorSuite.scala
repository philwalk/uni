package uni.apps

import munit.FunSuite
import uni.*

/**
 * `VarRatioBands` and `VarRatioSlopeBands` are FITTED NUMBERS in the same sense the depth
 * relation's constants are: the real cross-section's own ranges, rounded outward. This re-derives
 * every bound from the checked-in readings, so a band widened to admit a world fails here instead
 * of quietly becoming a band that grades nothing.
 *
 * The Rust twin carries the same checks in `persistence_anchor_tests`, against the same file.
 *
 * The claim ABOUT THE FORM is pinned here too, because it is the reason the bands are shared
 * across anchor sets while volatility and return per volatility are carried per asset: what
 * separates these readings is the era, not the index.
 */
class PersistenceAnchorSuite extends FunSuite:

  val Fixture = "test-data/equity-anchors/persistence-2026-09-11.tsv"

  case class Row(window: String, ticker: String, kind: String, years: Double,
                 vr: Map[Int, Double], ac1: Double)

  /** Empty where the fixture is absent, which is a skip and not a failure: a source tarball ships
    * without `test-data/`, and asserting there would fail for no benefit. */
  lazy val rows: Vector[Row] =
    val p = Fixture.asPath
    if !p.exists then Vector.empty
    else
      p.lines.toVector
        .filter(l => !l.startsWith("#") && !l.startsWith("window\t") && l.trim.nonEmpty)
        .map { l =>
          val f = l.split("\t")
          Row(f(0), f(1), f(2), f(4).toDouble,
              Map(20 -> f(5).toDouble, 60 -> f(6).toDouble, 120 -> f(7).toDouble, 250 -> f(8).toDouble),
              f(9).toDouble)
        }

  /** The rounding step the bands are stated at. Outward from the observed range, never inward: a
    * bound that excluded a real reading would be a band asserting that a real market is not one. */
  val Step = 0.05

  def outward(x: Double, up: Boolean): Double =
    val n = if up then math.ceil(x / Step) else math.floor(x / Step)
    math.round(n * Step * 1e6) / 1e6

  test("each era the 250-session rung is graded against is the fixture's own CRSP row") {
    if rows.nonEmpty then
      for (era, years, record) <- MarketSim.VarRatio250Eras.filter(_._1.startsWith("CRSP")) do
        val window = era match
          case "CRSP 1926-2026" => "c1926"
          case "CRSP 1954-2026" => "c1954"
          case _                => "c1990"
        val r = rows.find(r => r.window == window && r.ticker == "CRSP-VW").getOrElse(fail(s"no $window CRSP row"))
        assertEqualsDouble(r.vr(250), record, 5e-4, s"$era: the rung grades against $record")
        assertEqualsDouble(r.years, years, 0.05, s"$era: $years years")
    assertEquals(MarketSim.vr250EraOf(MarketSim.SP500Anchors.vr250Eras, 100.0), 0)
    assertEquals(MarketSim.vr250EraOf(MarketSim.SP500Anchors.vr250Eras, 80.0), 1)
    assertEquals(MarketSim.vr250EraOf(MarketSim.SP500Anchors.vr250Eras, 30.0), 2)
    assertEquals(MarketSim.vr250EraOf(MarketSim.NasdaqAnchors.vr250Eras, 100.0), 3)
  }

  test("every rung's envelope is the real range rounded outward") {
    if rows.nonEmpty then
      assertEquals(MarketSim.VarRatioBands.map(_._1), MarketSim.VarRatioLadder)
      for (q, lo, hi) <- MarketSim.VarRatioBands do
        val xs = rows.map(_.vr(q))
        assertEqualsDouble(lo, outward(xs.min, up = false), 1e-9,
          f"vr$q: the low bound no longer follows from the fixture: readings start at ${xs.min}%.3f")
        assertEqualsDouble(hi, outward(xs.max, up = true), 1e-9,
          f"vr$q: the high bound no longer follows from the fixture: readings reach ${xs.max}%.3f")
      assertEquals(MarketSim.VarRatioBands.find(_._1 == MarketSim.VarRatioQ).map(b => (b._2, b._3)),
        Some((0.55, 1.05)), "the loss row's rung carries the 60-session envelope")
  }

  test("the lag-1 readings the report quotes are the file's own") {
    if rows.nonEmpty then
      // The rung the ladder cannot see, REPORTED rather than graded -- so the only thing that can
      // go wrong is the printed claim drifting from the evidence.  This pins it, that every modern
      // fund reverts at one day, and that a CRSP era reads nearer zero the more of it falls before
      // `UnlagCut`, where the lag correction leaves lag-1 at about zero.
      def crsp(w: String) = rows.find(r => r.window == w && r.ticker == "CRSP-VW")
        .getOrElse(fail(s"no $w CRSP row")).ac1
      val funds = rows.filter(_.ticker != "CRSP-VW").map(_.ac1)
      val (rc26, rc54, rc90, rfLo, rfHi) = MarketSim.RetAc1Record
      assertEqualsDouble(rc26, crsp("c1926"), 5e-5, "the century reading the report quotes")
      assertEqualsDouble(rc54, crsp("c1954"), 5e-5, "the 1954 reading the report quotes")
      assertEqualsDouble(rc90, crsp("c1990"), 5e-5, "the 1990 reading the report quotes")
      assertEqualsDouble(rfLo, funds.min, 5e-5, "the modern funds' low the report quotes")
      assertEqualsDouble(rfHi, funds.max, 5e-5, "the modern funds' high the report quotes")
      assert(crsp("c1926") > crsp("c1954") && crsp("c1954") > crsp("c1990") && crsp("c1990") < 0.0,
        "the more of a CRSP era falls before the cut, the nearer zero it reads")
      assert(funds.forall(_ < 0.0), "every modern fund reverts at one day")
  }

  test("every slope band is the real range rounded outward") {
    if rows.nonEmpty then
      for (a, b, lo, hi) <- MarketSim.VarRatioSlopeBands do
        val xs = rows.map(r => r.vr(b) - r.vr(a))
        assertEqualsDouble(lo, outward(xs.min, up = false), 1e-9, f"slope $a->$b low: readings start at ${xs.min}%.3f")
        assertEqualsDouble(hi, outward(xs.max, up = true), 1e-9, f"slope $a->$b high: readings reach ${xs.max}%.3f")
  }

  test("the profile admits every real reading") {
    // Implied by the rules above and asserted anyway, because this is the property that matters:
    // the gate uses STRICT inequalities, so a bound landing exactly on a real reading would fail
    // the market that produced it.
    for r <- rows do
      for (q, lo, hi) <- MarketSim.VarRatioBands do
        assert(r.vr(q) > lo && r.vr(q) < hi,
          f"${r.ticker} over ${r.window} reads vr$q ${r.vr(q)}%.3f, outside the envelope the gate enforces")
      for (a, b, lo, hi) <- MarketSim.VarRatioSlopeBands do
        val sl = r.vr(b) - r.vr(a)
        assert(sl > lo && sl < hi,
          f"${r.ticker} over ${r.window} has slope $a->$b $sl%+.3f, outside the band the gate enforces")
  }

  test("the index separates these readings, so each set grades its own") {
    // Why the rungs are graded per set and the shared envelopes only printed: lag-free, the index
    // separates these readings about as much as the era does, so a shared band would grade one
    // index on another's spread.  Each set grades the four rungs on its own record's band.
    if rows.nonEmpty then
      def at(w: String, t: String) = rows.find(r => r.window == w && r.ticker == t).map(_.vr(60))
      (at("wfull", "QQQ"), at("wfull", "SPY"), at("c1926", "CRSP-VW"), at("c1990", "CRSP-VW")) match
        case (Some(qqq), Some(spy), Some(century), Some(modern)) =>
          val acrossIndex = math.abs(qqq - spy)
          val acrossEra   = math.abs(century - modern)
          assert(acrossIndex >= acrossEra / 2.0,
            f"QQQ and SPY now differ by $acrossIndex%.3f against $acrossEra%.3f between the CRSP " +
            "century and 1990-2026: if the era is again the axis, a shared band could grade the rungs")
          for a <- Vector(MarketSim.SP500Anchors, MarketSim.NasdaqAnchors); q <- MarketSim.VarRatioLadder do
            val name = s"variance ratio ${q}d"
            assert(a.recordBands.exists(_.name == name), s"${a.name}: [$name] has no record band of its own")
        case _ => fail("the fixture no longer carries the four rows this claim is pinned on")
  }

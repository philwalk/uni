package uni.apps

import munit.FunSuite

/**
 * The end-aligned cut and the per-history channel readers: `tailSessions` is the last sessions of
 * every series, the channels and the macro panel included; a history's bar and sector rows are the
 * ensemble's readers on it alone.  The Rust twin carries the same checks in `tail_tests`.
 */
class PathTailSuite extends FunSuite:

  private def channelPaths(paths: Int): Vector[MarketSim.Path] =
    val (w, _) = MarketSim.namedWorld("0.25.0-sp500").get
    MarketSim.simPaths(MarketSim.verdictWorld(MarketSim.SP500Anchors, w), paths, 12, MarketSim.DefaultSeed)

  private def bits(x: Double): Long = java.lang.Double.doubleToLongBits(x)

  private val k = 1000
  private def last(v: Array[Double]): Seq[Double] = v.takeRight(k).toSeq

  test("a tail is the last sessions of every series, the channels included, the scalar summaries the whole path's") {
    val p = channelPaths(1).head
    val t = p.tailSessions(k)
    assertEquals(t.price.toSeq, last(p.price))
    assertEquals(t.cpi.toSeq, last(p.cpi))
    assertEquals(t.bond10.toSeq, last(p.bond10))
    assertEquals(t.sat.toSeq, last(p.sat))
    assertEquals(t.logHi.toSeq, last(p.logHi))
    assertEquals(t.logLo.toSeq, last(p.logLo))
    assertEquals(t.logVolume.toSeq, last(p.logVolume))
    assertEquals(t.logOpen.toSeq, last(p.logOpen))
    assertEquals(t.divYield.toSeq, last(p.divYield))
    assertEquals(t.bondStressHi.toSeq, p.bondStressHi.takeRight(k).toSeq)
    assertEquals(bits(t.duration), bits(p.duration))
    assertEquals(t.disasters, p.disasters)
  }

  test("a tail cuts the sectors and the panel beside the price") {
    val p = channelPaths(1).head
    val t = p.tailSessions(k)
    assertEquals(t.sectors.length, p.sectors.length)
    for (a, b) <- t.sectors.zip(p.sectors) do assertEquals(a.toSeq, last(b))
    val (tp, pp) = (t.macroPanel.get, p.macroPanel.get)
    for j <- 0 until 9 do assertEquals(tp.member(j).toSeq, last(pp.member(j)), s"panel member $j")
  }

  test("a tail in years is its sessions, and a tail longer than the path is the path") {
    val p = channelPaths(1).head
    assertEquals(p.tail(3).price.toSeq, p.tailSessions(3 * MarketSim.DaysPerYear).price.toSeq)
    val whole = p.tailSessions(p.price.length + 7)
    assertEquals(whole.price.toSeq, p.price.toSeq)
    assertEquals(whole.logHi.toSeq, p.logHi.toSeq)
    assertEquals(whole.macroPanel.get.member(0).toSeq, p.macroPanel.get.member(0).toSeq)
  }

  test("a history's bar and sector rows are the readers on it alone; a tail reads its own bars; no channel reads NaN") {
    for p <- channelPaths(2) do
      val one = Vector(p)
      val (b, o) = (MarketSim.barStats(one).get, MarketSim.openStats(one).get)
      val rows = MarketSim.barRowsOfPath(p)
      val want = Vector(b.rangeOverCcvol, b.rangeAcf1, b.rangeDownup, b.volSd, b.volCorrRange, o.overnightShare)
      for k <- 0 until 6 do
        assert(!rows(k).isNaN && !rows(k).isInfinite, MarketSim.BarRows(k))
        assertEquals(bits(rows(k)), bits(want(k)), MarketSim.BarRows(k))
      val s = MarketSim.sectorPathStats(p)
      val sr = MarketSim.sectorRowsOfPath(p)
      val wantS = Vector(s(0) / s(5), s(3) / s(5), s(4) / s(5), s(5) / s(6), s(7), s(8), s(9))
      for k <- 0 until 7 do assertEquals(bits(sr(k)), bits(wantS(k)), MarketSim.SectorRows(k))
      val t = p.tailSessions(1500)
      assertEquals(t.logHi.length, t.price.length)
      assertEquals(t.logOpen.length, t.price.length)
      assert(MarketSim.barRowsOfPath(t).forall(v => !v.isNaN && !v.isInfinite))
    val off = MarketSim.simPaths(MarketSim.Defaults, 1, 12, MarketSim.DefaultSeed).head
    assert(MarketSim.barRowsOfPath(off).forall(_.isNaN))
    assert(MarketSim.sectorRowsOfPath(off).forall(_.isNaN))
  }

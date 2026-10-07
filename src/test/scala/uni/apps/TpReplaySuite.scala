package uni.apps

/** THE TERM-PREMIUM REPLAY: the bonds re-run from a path's tape under another premium are the full
  * simulation to the bit, and a read of the move from the cache is the full read of the moved
  * world.  The Rust twin carries the same checks beside `replay_bonds` and `tp_reread`. */
class TpReplaySuite extends munit.FunSuite:

  /** Every field to the bit: arrays by their elements, doubles by their bits. */
  private def bits(x: Any): Any = x match
    case a: Array[Double] => a.toVector.map(java.lang.Double.doubleToLongBits)
    case a: Array[?]      => a.toVector.map(bits)
    case d: Double        => java.lang.Double.doubleToLongBits(d)
    case m: Map[?, ?]     => m.toVector.map((k, v) => (k.toString, bits(v))).sortBy(_._1)
    case v: Iterable[?]   => v.map(bits).toVector
    case p: Product       => p.productIterator.map(bits).toVector
    case o                => o

  private def withTp(w: MarketSim.World, tp: Double): MarketSim.World = w.copy(ext = w.ext.copy(termPremium = tp))

  test("a term-premium replay is the full simulation to the bit") {
    for name <- Vector("0.24.6-sp500", "0.24.6-nasdaq", "0.24.4-sp500-channels") do
      val w = MarketSim.namedWorld(name).map(_._1).getOrElse(fail("recipe"))
        .copy(bond10 = MarketSim.VerdictBond10, macroPanel = 1)
      for seed <- Vector(MarketSim.DefaultSeed, 7L) do
        val (taped, tape) = MarketSim.simulateTaped(w, 12, seed)
        assertEquals(bits(taped), bits(MarketSim.simulate(w, 12, seed)), s"$name: taping changes nothing")
        val same = MarketSim.replayBonds(w, taped, tape, seed).getOrElse(fail("replayable"))
        assertEquals(bits(same), bits(taped), s"$name: the same premium")
        val w2 = withTp(w, w.ext.termPremium + 0.17)
        val replayed = MarketSim.replayBonds(w2, taped, tape, seed).getOrElse(fail("replayable"))
        assertEquals(bits(replayed), bits(MarketSim.simulate(w2, 12, seed)), s"$name seed $seed: the replay is the simulation")
        assert(!replayed.bond.sameElements(taped.bond), s"$name: the premium moved the bond")
    val w = MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe")).copy(macroPanel = 1, macroNull = 1)
    val (p, t) = MarketSim.simulateTaped(w, 5, MarketSim.DefaultSeed)
    assert(MarketSim.replayBonds(w, p, t, MarketSim.DefaultSeed).isEmpty, "a sibling panel cannot be replayed")
  }

  test("a term-premium reread is the full read to the bit") {
    for (name, set) <- Vector(("0.24.6-sp500", "sp500"), ("0.24.6-nasdaq", "nasdaq")) do
      val a  = MarketSim.anchorsNamed(set)
      val w  = MarketSim.namedWorld(name).map(_._1).getOrElse(fail("recipe"))
      val vw = MarketSim.verdictWorld(a, w)
      val (paths, years, seed) = (6, 70, 11L)
      val taped = MarketSim.simPathsTaped(vw, paths, years, seed)
      val sims  = taped.map(_._1)
      val st    = MarketSim.measure(sims, years)
      val (_, hr) = MarketSim.fidelityRowsWithReadings(a, st, Some(sims), years, paths, seed, w)
      val keep  = MarketSim.TpKeep(sims, taped.map(_._2), years, seed)
      val w2    = withTp(w, w.ext.termPremium + 0.13)
      val vw2   = MarketSim.verdictWorld(a, w2)
      val (st2, rows2, hr2) = MarketSim.tpReread(a, vw2, keep, st, hr).getOrElse(fail("replayable"))
      val full   = MarketSim.simPaths(vw2, paths, years, seed)
      val stFull = MarketSim.measure(full, years)
      val (rowsFull, hrFull) = MarketSim.fidelityRowsWithReadings(a, stFull, Some(full), years, paths, seed, w2)
      assertEquals(bits(st2), bits(stFull), s"$name: statistics")
      assertEquals(bits(rows2), bits(rowsFull), s"$name: rows")
      assertEquals(bits(hr2), bits(hrFull), s"$name: readings")
      for r <- MarketSim.Bond10BandRows do
        val xs = hrFull.bandedByPath(r)
        assertEquals(xs.size, paths, s"$name $r: one per path")
        val get = MarketSim.fitTargets(a).find(_._1 == r).getOrElse(fail("row"))._2
        val one = MarketSim.measure(Vector(full(2).head(a.bond10Years)), a.bond10Years)
        assertEquals(bits(xs(2)), bits(get(one)), s"$name $r: path 2 alone")
      assert(st2.bondVol != st.bondVol, s"$name: the move moved the bond")
      assert(keep.bytes > 0L)
  }

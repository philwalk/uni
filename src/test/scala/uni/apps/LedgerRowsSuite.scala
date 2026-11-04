package uni.apps

import munit.FunSuite
import uni.*

/** THE LEDGER ROWS (`LedgerRows`, `ledgerrows-2026-11-02.tsv`): the shipped records are the
  * fixture's, and the state profile's reader places spikes by their state.  The Rust twin carries
  * the same checks in `ledger_row_tests`, against the same file. */
class LedgerRowsSuite extends FunSuite:

  /** Empty where the fixture is absent, which is a skip and not a failure: the artifact ships
    * without `test-data/`, so a source-tarball build must not fail here. */
  def fixture(name: String): Vector[Vector[String]] =
    val p = s"test-data/equity-anchors/$name".asPath
    if !p.exists then Vector.empty
    else p.lines.toVector
      .filterNot(l => l.startsWith("#") || l.trim.isEmpty)
      .map(_.split("\t", -1).toVector)

  def bits(x: Double): Long = java.lang.Double.doubleToRawLongBits(x)

  test("the shipped records are the fixture's") {
    val rows = fixture("ledgerrows-2026-11-02.tsv")
    assume(rows.nonEmpty, "fixture not present in this tree (source tarball?)")
    val head = rows.head
    def col(name: String): Int =
      val i = head.indexOf(name)
      assert(i >= 0, s"column $name")
      i
    val (ri, ci, li, hi) = (col("record"), col("jointC"), col("jointLo"), col("jointHi"))
    for set <- Vector("nasdaq", "sp500") do
      val rec  = MarketSim.ledgerRecords(MarketSim.anchorsNamed(set)).getOrElse(fail(s"$set has no ledger records"))
      val mine = rows.filter(_(0) == set)
      assertEquals(mine.length, MarketSim.LedgerRows.length, set)
      for (r, k) <- mine.zipWithIndex do
        def num(i: Int): Double = r(i).toDouble
        assertEquals(r(1), MarketSim.LedgerRows(k), s"$set: row order")
        assertEquals(bits(rec.record(k)), bits(num(ri)), s"$set ${r(1)}")
        val banded = Vector(2, 4, 6, 7).exists(f => MarketSim.LedgerFamilies(f).contains(k))
        assertEquals(rec.bands.exists(_.name == MarketSim.LedgerRows(k)), banded,
          s"$set ${r(1)}: the coupling, the innovation and level, the state profile and the reversion are the banded rows")
        rec.bands.find(_.name == MarketSim.LedgerRows(k)).foreach { b =>
          assertEquals(bits(b.record), bits(num(ri)))
          b.q.zipWithIndex.foreach((q, j) => assertEquals(bits(q), bits(num(ri + 1 + j)), s"$set ${r(1)} p$j"))
          assertEquals(bits(b.jointC), bits(num(ci)))
          assertEquals((bits(b.joint._1), bits(b.joint._2)), (bits(num(li)), bits(num(hi))))
        }
  }

  // THE STATE PROFILE's reader: 4000 sessions alternating +-1% then +-3% from session 2000, so the
  // trailing sd is a calm or a wild constant (3979 sessions with one, the lowest and highest three
  // tenths 1193 each, calm ones first by session, wild ones last) and |z| stays under 1 but at a
  // spike.  A 10% spike at session 500 is the one 3-sd session in the calm group (ratio
  // (1 + 0.5) / (0 + 0.5) = 3); a 30% spike at 3500 the one in the wild group (1/3 alone, 1 with
  // both); the 21 sessions after a spike carry an inflated sd and fall in neither group.  The 99th
  // percentile by nearest rank is a wild session's sd (the 40th highest, below the 21 inflated by
  // the wild spike), annualised.
  test("the state profile reads spikes by their state") {
    def base(i: Int): Double =
      val a = if i < 2000 then 0.01 else 0.03
      if i % 2 == 0 then a else -a
    val r     = Array.tabulate(4000)(base)
    val calm  = MarketSim.volStateProfileOf(r.updated(500, 0.10))
    val wildR = r.updated(3500, 0.30)
    val wild  = MarketSim.volStateProfileOf(wildR)
    val bothR = wildR.updated(500, 0.10)
    val both  = MarketSim.volStateProfileOf(bothR)
    assertEquals(bits(calm(0)), bits(3.0), s"calm spike ${calm(0)}")
    assertEquals(bits(wild(0)), bits(1.0 / 3.0), s"wild spike ${wild(0)}")
    assertEquals(bits(both(0)), bits(1.0), s"both ${both(0)}")
    // 21 alternating +-a: eleven of one sign and ten of the other
    val a    = 0.03
    val mean = a / 21.0
    val v    = (11.0 * (a - mean) * (a - mean) + 10.0 * (a + mean) * (a + mean)) / 20.0
    val want = math.sqrt(v) * math.sqrt(252.0) * 100.0
    assert(math.abs(both(1) - want) < 1e-9, s"p99 ${both(1)} against $want")
    assert(MarketSim.volStateProfileOf(bothR.take(25)).forall(_.isNaN))
  }

  test("the volume's detrend is the trailing median's, the session included") {
    // a window of 252 sessions: the first output is the 252nd session less the window's median;
    // after it each session less the median of the 252 ending there, the oldest leaving
    val v   = Array.tabulate(600)(i => math.sin(0.37 * i) + 0.001 * i)
    val got = MarketSim.detrendedLogVolume(v)
    assertEquals(got.length, v.length - 251)
    for t <- 251 until v.length do
      val win = v.slice(t - 251, t + 1).sorted
      val med = 0.5 * (win(125) + win(126))
      assertEquals(bits(got(t - 251)), bits(v(t) - med), s"session $t")
    assert(MarketSim.detrendedLogVolume(v.take(251)).isEmpty)
  }

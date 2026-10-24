package uni.apps

import munit.FunSuite

/** The verdict's one-value rule and the flips' reach.  The Rust twin's `verdict_reading_tests`
  * makes the same checks. */
class VerdictReadingsSuite extends FunSuite:
  // a world whose bond is anchored (its volatility inside the funds' range, where the depth relation
  // is defined), so the bond's depth gate is among the table gates: the recipe with its term noise
  // at two thirds, the legs tracking their yields the session they move
  lazy val world =
    val w = MarketSim.namedWorld("0.25.0-nasdaq").get._1
    w.copy(ext = w.ext.copy(termVol = w.ext.termVol * 0.67))
  lazy val st    = MarketSim.measure(MarketSim.simPaths(world, 8, 27, MarketSim.DefaultSeed), 27)
  val a = MarketSim.NasdaqAnchors

  test("with no table readings the gate is the ensemble's own") {
    assertEquals(MarketSim.gateChecksAt(a, st, Map.empty), MarketSim.gateChecks(a, st))
  }

  test("a table reading replaces the ensemble's in every class that grades it") {
    def row(g: Vector[(String, Boolean, MarketSim.GateClass)], cls: MarketSim.GateClass,
            prefix: String): Boolean =
      g.find((n, _, c) => c == cls && n.startsWith(prefix)).getOrElse(fail(s"no $prefix row"))._2
    val hot = MarketSim.gateChecksAt(a, st, Map("equity vol %" -> 50.0, "kurtosis" -> 50.0,
                                                "clustering lag 1" -> 0.5))
    assert(!row(hot, MarketSim.GateClass.Realism, "equity vol"))
    assert(!row(hot, MarketSim.GateClass.Realism, "kurtosis"))
    assert(!row(hot, MarketSim.GateClass.Realism, "clustering"))
    // and the table's own value where the ensemble's would fail
    val calm = MarketSim.gateChecksAt(a, st.copy(vol = 0.50), Map("equity vol %" -> 26.0))
    assert(row(calm, MarketSim.GateClass.Realism, "equity vol"))
  }

  test("the table gates are the gates a table reading can change") {
    // every banded row unreadable: each gate that reads the table fails, and no other gate moves
    val own   = MarketSim.gateChecks(a, st)
    val blind = MarketSim.gateChecksAt(a, st, a.recordBands.map(_.name -> Double.NaN).toMap)
    assertEquals(own.length, blind.length)
    for (o, b) <- own.zip(blind) do
      if MarketSim.gateReadsTable(o._1) then assert(!b._2, s"${o._1} reads the table and cannot pass without it")
      else assertEquals(o, b, s"${o._1} reads the ensemble alone")
    // the four realism gates a table reading can change: vol, kurtosis, clustering, crash rate
    assertEquals(own.count(g => MarketSim.gateReadsTable(g._1)), 4)
  }

  test("the gate bands are the band gates, in gate order") {
    val hot   = Map("equity vol %" -> 50.0)
    val g     = MarketSim.gateChecksAt(a, st, hot)
    val bands = MarketSim.gateBandsAt(a, st, hot)
    // each is a gate, after the one before it, and passes exactly when its reading is inside
    bands.foldLeft(0) { (at, b) =>
      val k = g.indexWhere(_._1 == b.name, at)
      assert(k >= 0, s"${b.name} is not a gate after $at")
      assertEquals(g(k)._2, b.reading > b.band._1 && b.reading < b.band._2, b.name)
      assertEquals(g(k)._3, b.cls, b.name)
      k + 1
    }
    // the table's reading is there; the channels and the panel are placement rows, not gates
    def has(p: String): Boolean = bands.exists(_.name.startsWith(p))
    assert(bands.exists(b => b.name.startsWith("equity vol") && b.reading == 50.0))
    assert(!has("satellite") && !has("macro spread lag") && !has("macro vol premium"))
  }

  test("bandedOf keeps exactly the rows that carry a record band") {
    val rows = MarketSim.fidelityRows(a, st, None, 27, 8, MarketSim.DefaultSeed, world)
    val b    = MarketSim.bandedOf(rows)
    assertEquals(b.keySet, a.recordBands.map(_.name).toSet)
    // NaN where the world runs no such leg: the same reading, unmeasurable on both sides
    rows.filter(_.recordBand.isDefined).foreach(r =>
      assert(b(r.name) == r.model || (b(r.name).isNaN && r.model.isNaN), r.name))
  }

  test("predict is the return the step then makes") {
    val m = new MarketSim.Market(0.07, 3.2, 12.0 / 12.2, 6.1, 0.12, 0.25, 0.0)
    val fairs = Iterator.iterate(0.0004)(_ + 0.0004).take(400).toVector
    fairs.zipWithIndex.foreach: (fair, k) =>
      val x    = 0.012 * ((k * 37 % 19).toDouble - 9.0) / 9.0
      val want = m.predict(fair, x)
      assertEquals(m.step(fair, x), want, s"session $k")
    // reflecting the input about the day's zero flips the return exactly where the step is affine
    val (fair, x) = (fairs.last, -0.004)
    val r = m.predict(fair, x)
    assertEqualsDouble(m.predict(fair, x - 2.0 * r / m.liquidity), -r, 1e-15)
  }

  test("the value cap binds the pull only while it points up") {
    val m = new MarketSim.Market(0.07, 3.2, 12.0 / 12.2, 6.1, 0.12, 0.25, 0.0)
    val (gap, damp) = (0.30, 0.8)
    val free = m.valuePull(gap, damp)
    assertEquals(free, 0.07 * gap * damp)
    m.valueCap = 0.005
    assertEquals(m.valuePull(gap, damp), 0.005)
    assertEquals(m.valuePull(0.01, damp), 0.07 * 0.01 * damp)
    assertEquals(m.valuePull(-gap, damp), 0.07 * -gap * damp)
    m.valueCap = 0.0
    assertEquals(m.valuePull(gap, damp), free)
    // the falling-fundamental cap binds only with the session's flag up, and never downward
    m.valueCapFall = 0.002
    assertEquals(m.valuePull(gap, damp), free)
    m.fundFalling = true
    assertEquals(m.valuePull(gap, damp), 0.002)
    assertEquals(m.valuePull(-gap, damp), 0.07 * -gap * damp)
    // both caps on: the tighter binds
    m.valueCap = 0.005
    assertEquals(m.valuePull(gap, damp), 0.002)
    m.valueCapFall = 0.0
    assertEquals(m.valuePull(gap, damp), 0.005)
  }

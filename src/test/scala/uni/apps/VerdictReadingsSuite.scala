package uni.apps

import munit.FunSuite

/** The verdict's one-value rule and the flips' reach.  The Rust twin's `verdict_reading_tests`
  * makes the same checks. */
class VerdictReadingsSuite extends FunSuite:
  lazy val world = MarketSim.namedWorld("0.24.4-nasdaq").get._1
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
    assert(!row(hot, MarketSim.GateClass.Fidelity, "equity vol"))
    assert(!row(hot, MarketSim.GateClass.Realism, "kurtosis"))
    assert(!row(hot, MarketSim.GateClass.Realism, "clustering"))
    // and the table's own value where the ensemble's would fail
    val calm = MarketSim.gateChecksAt(a, st.copy(vol = 0.50), Map("equity vol %" -> 26.0))
    assert(row(calm, MarketSim.GateClass.Realism, "equity vol"))
    assert(row(calm, MarketSim.GateClass.Fidelity, "equity vol"))
  }

  test("the table gates are the gates a table reading can change") {
    // every banded row unreadable: each gate that reads the table fails, and no other gate moves
    val own   = MarketSim.gateChecks(a, st)
    val blind = MarketSim.gateChecksAt(a, st, a.recordBands.map(_.name -> Double.NaN).toMap)
    assertEquals(own.length, blind.length)
    for (o, b) <- own.zip(blind) do
      if MarketSim.gateReadsTable(o._1) then assert(!b._2, s"${o._1} reads the table and cannot pass without it")
      else assertEquals(o, b, s"${o._1} reads the ensemble alone")
    // vol in two classes, and one gate for each of the other five
    assertEquals(own.count(g => MarketSim.gateReadsTable(g._1)), 7)
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
    // the table's reading, the channels' and the panel's lags are all there
    def has(p: String): Boolean = bands.exists(_.name.startsWith(p))
    assert(bands.exists(b => b.name.startsWith("equity vol") && b.reading == 50.0))
    assert(has("satellite beta") && has("macro spread lag") && has("macro vol premium"))
  }

  test("bandedOf keeps exactly the rows that carry a record band") {
    val rows = MarketSim.fidelityRows(a, st, None, 27, 8, MarketSim.DefaultSeed, world)
    val b    = MarketSim.bandedOf(rows)
    assertEquals(b.keySet, a.recordBands.map(_.name).toSet)
    rows.filter(_.recordBand.isDefined).foreach(r => assertEquals(b(r.name), r.model))
  }

  test("a flip's reach is 1 with both dials off, and each dial's own share with one on") {
    val off = world.copy(jumpVar = 0.0, downShock = 0.0)
    assertEquals(MarketSim.flipReach(off), 1.0)
    assertEquals(MarketSim.flipReach(off.copy(jumpVar = 0.16)), math.sqrt(1.0 - 0.16))
    val d = 0.2
    assertEquals(MarketSim.flipReach(off.copy(downShock = d)), 0.5 * ((1.0 + d) + 1.0 / (1.0 + d)))
  }

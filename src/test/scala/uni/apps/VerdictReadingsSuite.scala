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

  test("every gate reports its clauses and passes when they all hold") {
    val g  = MarketSim.gateChecksAt(a, st, Map.empty)
    val cl = MarketSim.gateClausesAt(a, st, Map.empty)
    for (name, ok, cls) <- g do
      val mine = cl.filter(_.gate == name)
      assert(mine.nonEmpty, s"$name reports no clause")
      assert(mine.forall(_.cls == cls), name)
      assertEquals(ok, mine.forall(_.clause.holds), name)
    // the bands on one reading are the five `bandCheck` grades
    assertEquals(MarketSim.gateBandsAt(a, st, Map.empty).length, 5)
  }

  test("a clause holds as the gate compared and clears by its nearer bound") {
    import MarketSim.Clause
    val open = Clause.open("", 0.3, 0.1, 0.4)
    assert(open.holds && math.abs(open.clearance - 0.1) < 1e-12)
    assert(!Clause.open("", 0.1, 0.1, 0.4).holds)
    assert(Clause.closed("", 0.1, 0.1, 0.4).holds)
    assert(!Clause.open("", Double.NaN, 0.1, 0.4).holds)
    // an infinite bound is no bound
    assert(Clause.open("", Double.PositiveInfinity, 3.0, Double.PositiveInfinity).holds)
    assert(Clause.open("", -2.0, Double.NegativeInfinity, 0.02).holds)
    assert(math.abs(Clause.open("", -2.0, Double.NegativeInfinity, 0.02).clearance - 2.02) < 1e-12)
    assert(Clause.open("", 0.5, 0.6, Double.PositiveInfinity).clearance < 0.0)
    // no reading, or no bound to read it against, is no clearance
    assert(Clause.open("", Double.NaN, 3.0, Double.PositiveInfinity).clearance.isNaN)
    assert(Clause.open("", 0.5, Double.NaN, Double.PositiveInfinity).clearance.isNaN)
  }

  test("the t quantile is Student's") {
    // the tables' t(0.995, nu), and the margin it sets at four, 16 and 40 reads
    for (nu, t) <- Vector((1, 63.65674), (2, 9.924843), (3, 5.840909), (7, 3.499483), (15, 2.946713),
                          (39, 2.707913)) do
      val got = MarketSim.studentTQuantile(0.995, nu)
      assert(math.abs(got - t) < 1e-5 * t, s"t(0.995, $nu) $got against $t")
    assert(math.abs(MarketSim.studentTQuantile(0.995, 2000) - 2.578290) < 1e-5)
    assert(math.abs(MarketSim.marginNeed(4) - 6.530335) < 1e-5)
    assert(math.abs(MarketSim.marginNeed(16) - 3.037402) < 1e-5)
    assert(math.abs(MarketSim.marginNeed(40) - 2.741553) < 1e-5)
  }

  test("the margin refuses a bound a member sits on and needs its reads") {
    def clauses(cs: Seq[Double]): Vector[Vector[MarketSim.GateClause]] =
      cs.toVector.map(c => Vector(MarketSim.GateClause("g",
        MarketSim.Clause.open("", 1.0 + c, 1.0, Double.PositiveInfinity), MarketSim.GateClass.Mechanism)))
    // 16 reads a unit either side of a clearance of 2: the next read fails one time in 44
    val near = (0 until 16).map(k => 2.0 + (if k % 2 == 0 then 1.0 else -1.0))
    val m    = MarketSim.margins(clauses(near))
    assertEquals(m.length, 1)
    assert(!m.head.ok && math.abs(m.head.sds - 2.0 / math.sqrt(16.0 / 15.0)) < 1e-9)
    // four sds clear passes
    assert(MarketSim.margins(clauses(near.map(_ + 2.2))).head.ok)
    // a read outside the bound counts with its negative clearance: 40 reads 3.4 sds clear pass
    // with one of them outside, which failing any read would refuse
    val oneOut = (0 until 40).map(k => if k % 2 == 0 then 5.2 else 3.2).updated(3, -0.1)
    val o      = MarketSim.margins(clauses(oneOut)).head
    assert(o.ok && math.abs(o.sds - 3.40) < 0.01, f"${o.sds}%.3f sds")
    // a read with no reading fails the clause
    assert(!MarketSim.margins(clauses(near.map(_ + 2.2).updated(3, Double.NaN))).head.ok)
    // under the fewest reads the margin is not read
    assert(MarketSim.margins(clauses(Vector(0.01, 0.02, 0.01, 0.02))).isEmpty)
  }

  test("bandedOf keeps exactly the rows that carry a record band") {
    val rows = MarketSim.fidelityRows(a, st, None, 27, 8, MarketSim.DefaultSeed, world)
    val b    = MarketSim.bandedOf(rows)
    // the set's era rows carry a band and are reported, not read into the table
    assertEquals(b.keySet, a.recordBands.map(_.name).filterNot(a.eraRows.contains).toSet)
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

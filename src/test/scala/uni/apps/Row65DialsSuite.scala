package uni.apps

import munit.FunSuite
import scala.concurrent.duration.*

/**
 * The PLAN row 65 dials: the recession's step probes (`recessStep`, `recessSteps`), the diffusion's
 * scale (`noiseScale`), the momentum crowd's gradual adjustment (`crowdAdjust`) and the linear vol
 * answer to declines and to the drawdown (`declineVol`, `drawdownVol`, `declineVolAttack`,
 * `declineVolHalf`). The Rust twin carries the same checks in `row65_probe_tests`,
 * `noise_scale_tests`, `crowd_adjust_tests` and `lin_vol_tests`.
 */
class Row65DialsSuite extends FunSuite:

  override val munitTimeout: Duration = 300.seconds

  import MarketSim.{World, Path}

  private def recipe(name: String): World =
    MarketSim.namedWorld(name).map(_._1).getOrElse(fail(s"no recipe $name"))

  /** a searched dial set through `CalibrateRanges`, as the search sets it */
  private def set(w: World, key: String, x: Double): World =
    MarketSim.CalibrateRanges.find(_._1 == key).map(_._4).getOrElse(fail(s"$key is not searched"))(w, x)

  private def bits(xs: Array[Double]): Vector[Long] = xs.toVector.map(java.lang.Double.doubleToRawLongBits)

  /** a path's `-emitvolparts` record at `years` on seed 5 */
  private def parts(w: World, years: Int): Vector[Array[Double]] =
    val tape = MarketSim.BondTape.recordingVol(years * MarketSim.DaysPerYear + MarketSim.BurnIn)
    val _ = MarketSim.simulateAtTaped(w, years, 5L, MarketSim.worldLevel(w), tape)
    val v = tape.vol.getOrElse(fail("the record was not kept"))
    v.rows.toVector.take(v.count)

  private def col(name: String): Int =
    MarketSim.VolPartColumns.indexOf(name) match
      case -1 => fail(s"no column $name")
      case i  => i

  /** the worst session's log return */
  private def worst(p: Path): Double =
    p.price.sliding(2).map(x => MarketSim.lnDet(x(1) / x(0))).foldLeft(Double.PositiveInfinity)(math.min)

  /** a world whose recessions begin in a calm market, the size fixed */
  private def recessWorld: World =
    MarketSim.Defaults.copy(recessRate = 1.0, recessBase = 2.0, recessSize = 0.30, recessLen = 1.0,
                            recessRecover = 0.5, recessReprice = 0.6)

  // THE ROW 65 PROBES: off in every frozen world, zero the released code path, and on, the
  // recession's step lands the markdown's repriced share in the onset session where the glide
  // spreads it.

  test("both probes are off in every frozen world") {
    def off(w: World): Boolean = w.ext.recessStep == 0.0 && w.ext.recessSteps == 0.0
    assert(off(MarketSim.Defaults))
    MarketSim.Releases.foreach((v, w) => assert(off(w), s"release $v"))
    MarketSim.Recipes.foreach((n, w, _) => assert(off(w), s"recipe $n"))
  }

  test("the probe dials are optional keys outside the searched table") {
    // the probe dials a search sets and never steps: outside the searched table, each an optional
    // world key, each setter reaching its field
    MarketSim.ProbeDials.foreach { (name, setter, getter) =>
      assert(!MarketSim.CalibrateDialOrder.contains(name), s"$name is searched")
      assert(MarketSim.OptionalWorldKeys.exists(_._1 == name), s"$name is not optional")
      val w = setter(MarketSim.Defaults, 3.0)
      assert(getter(w) == 3.0, name)
      assert(MarketSim.worldJsonBody(w).exists(_.contains(s"\"$name\"")), s"$name written when on")
    }
  }

  test("the lumps split the step inside the decline") {
    // alone (no step share) the lumps reach no price; with the share they split it, so no session
    // carries the whole step and the worst carries at least a lump; a different world from the
    // onset lump's
    val w = recessWorld
    val plain = MarketSim.simulate(w, 40, MarketSim.DefaultSeed)
    val k = w.copy(ext = w.ext.copy(recessSteps = 3.0))
    assertEquals(bits(MarketSim.simulate(k, 40, MarketSim.DefaultSeed).price), bits(plain.price),
      "lumps without a share")
    val one = w.copy(ext = w.ext.copy(recessStep = 1.0))
    val onset = MarketSim.simulate(one, 40, MarketSim.DefaultSeed)
    val three = one.copy(ext = one.ext.copy(recessSteps = 3.0))
    val lumps = MarketSim.simulate(three, 40, MarketSim.DefaultSeed)
    assertNotEquals(bits(lumps.price), bits(onset.price))
    val share = 0.30 * 0.6
    assert(worst(lumps) <= -0.9 * share / 3.0, s"lumps ${worst(lumps)}")
    assert(worst(lumps) > -0.9 * share, s"lumps ${worst(lumps)}")
  }

  test("the step lands the repriced share in the onset session") {
    // with the step the worst session carries the markdown's repriced share, which no session of
    // the glide approaches; zero is the released path, bit for bit
    val w = recessWorld
    val glide = MarketSim.simulate(w, 40, MarketSim.DefaultSeed)
    val step = MarketSim.simulate(w.copy(ext = w.ext.copy(recessStep = 1.0)), 40, MarketSim.DefaultSeed)
    val share = 0.30 * 0.6
    assert(worst(step) <= -0.9 * share, s"step ${worst(step)}")
    assert(worst(glide) > -0.5 * share, s"glide ${worst(glide)}")
    val z = w.copy(ext = w.ext.copy(recessStep = 0.0))
    assertEquals(bits(MarketSim.simulate(z, 40, MarketSim.DefaultSeed).price), bits(glide.price))
  }

  // THE DIFFUSION'S SCALE (`noiseScale`)

  test("a noise scale at one is bit-identical") {
    // at 1 the scale is `SigmaN` to the bit, the news budget as it was, and a path of either 0.25.0
    // world with the dial at 1 is the path before the dial existed: a search setting 1 through the
    // table reproduces it.  Off 1 the path moves.
    for name <- Vector("0.25.0-nasdaq", "0.25.0-sp500") do
      val r = recipe(name)
      val w = r.copy(ext = r.ext.copy(noiseScale = 1.0))
      val before = MarketSim.newsDampAt(w.newsRate, w.newsSize, 1.0)
      val plain = math.sqrt(math.max(
        1.0 - (w.newsRate / MarketSim.DaysPerYear) * w.newsSize * w.newsSize / (MarketSim.SigmaN * MarketSim.SigmaN),
        0.0))
      assertEquals(java.lang.Double.doubleToRawLongBits(before), java.lang.Double.doubleToRawLongBits(plain),
        s"$name budget")
      val a = MarketSim.simulate(w, 3, 11L)
      assertEquals(bits(MarketSim.simulate(set(w, "noiseScale", 1.0), 3, 11L).price), bits(a.price),
        s"$name: the path at 1 is the path")
      assertNotEquals(bits(MarketSim.simulate(set(w, "noiseScale", 1.25), 3, 11L).price), bits(a.price),
        s"$name: off 1 the path moves")
    assert(MarketSim.worldJsonBody(MarketSim.Defaults).exists(_.contains("\"noiseScale\": 1.0")),
      "the body carries the key")
  }

  test("a noise scale scales the calm sd") {
    // at 1.25 a path's session diffusion sd (`sdPre`, the record `-emitvolparts` keeps) scales
    // about 1.25 at its median over the same world at 1: the vol state feeds back, so not exactly
    val r = recipe("0.25.0-nasdaq")
    val w = r.copy(ext = r.ext.copy(noiseScale = 1.0))
    def sdPre(w: World): Double =
      val xs = parts(w, 6).map(_(col("sdPre"))).sorted
      assert(xs.length > 1000)
      xs(xs.length / 2)
    val ratio = sdPre(w.copy(ext = w.ext.copy(noiseScale = 1.25))) / sdPre(w)
    assert(ratio >= 1.15 && ratio <= 1.35, s"median session sd scales $ratio for 1.25")
  }

  // THE MOMENTUM CROWD'S GRADUAL ADJUSTMENT (`crowdAdjust`)

  test("a crowd adjust at zero is bit-identical") {
    // at 0 the crowd's exposure is its target and a path of either 0.25.0 world with the dial at 0
    // is the path before the dial existed: a search setting 0 through the table reproduces it.  On,
    // the path moves.
    for name <- Vector("0.25.0-nasdaq", "0.25.0-sp500") do
      val r = recipe(name)
      val w = r.copy(ext = r.ext.copy(crowdAdjust = 0.0))
      assert(w.crowd == MarketSim.Crowd.Momentum, s"$name runs the momentum crowd")
      val a = MarketSim.simulate(w, 3, 11L)
      assertEquals(bits(MarketSim.simulate(set(w, "crowdAdjust", 0.0), 3, 11L).price), bits(a.price),
        s"$name: the path at 0 is the path")
      assertNotEquals(bits(MarketSim.simulate(set(w, "crowdAdjust", 2.8), 3, 11L).price), bits(a.price),
        s"$name: on, the path moves")
    assert(MarketSim.worldJsonBody(MarketSim.Defaults).exists(_.contains("\"crowdAdjust\": 0.0")),
      "the body carries the key")
  }

  test("a crowd adjust makes the crowd flow persist") {
    // the crowd leg's flow (the `crowd` column) over a path: at 0 the crowd trades its target's
    // change and the flow's lag-1 autocorrelation is the world's own return structure (below 0.3
    // on the recipe); at 2.8 a year it trades toward the target over weeks and the flow persists,
    // above 0.9 and more than three times the off reading on the same world and seed
    val r = recipe("0.25.0-nasdaq")
    val w = r.copy(ext = r.ext.copy(crowdAdjust = 0.0))
    def flowAc1(w: World): Double =
      val xs = parts(w, 6).map(_(col("crowd")))
      assert(xs.length > 1000)
      val n = xs.length.toDouble
      val mean = xs.sum / n
      val variance = xs.map(x => (x - mean) * (x - mean)).sum / n
      val cov = xs.sliding(2).map(p => (p(0) - mean) * (p(1) - mean)).sum / n
      cov / variance
    val off = flowAc1(w)
    val on = flowAc1(w.copy(ext = w.ext.copy(crowdAdjust = 2.8)))
    assert(off < 0.3, s"at 0 the flow's lag-1 autocorrelation is $off")
    assert(on > 0.9 && on > 3.0 * off,
      s"at 2.8 a year the flow persists: lag-1 autocorrelation $on against $off at 0")
  }

  // THE LINEAR VOL ANSWER TO DECLINES AND TO THE DRAWDOWN

  /** `w` with the four dials at their off values: the baseline each test below reads a dial
    * against, whatever the recipe carries */
  private def dialsOff(w: World): World =
    w.copy(ext = w.ext.copy(declineVol = 0.0, drawdownVol = 0.0, declineVolAttack = 0.0, declineVolHalf = 5.0))

  private def onSp500(f: MarketSim.WorldExt => MarketSim.WorldExt): World =
    val w = dialsOff(recipe("0.25.0-sp500"))
    w.copy(ext = f(w.ext))

  test("the four dials are searched and optional") {
    // searched, after `crowdAdjust`, and none a probe; each an optional world key whose absent
    // value is the default world's, so every frozen world and set file reads unchanged
    val four = Vector("declineVol", "drawdownVol", "declineVolAttack", "declineVolHalf")
    val rs = MarketSim.CalibrateRanges
    val at = rs.indexWhere(_._1 == "crowdAdjust")
    assert(at >= 0, "crowdAdjust is searched")
    four.zipWithIndex.foreach { (k, i) =>
      val r = rs(at + 1 + i)
      assertEquals(r._1, k)
      assert(!MarketSim.ProbeDials.exists(_._1 == k), s"$k is a probe")
      val off = MarketSim.OptionalWorldKeys.find(_._1 == k).map(_._2.toDouble).getOrElse(fail(s"$k is not optional"))
      assert(r._5(MarketSim.Defaults) == off, s"$k absent reads the default")
    }
  }

  test("the dials are off in every frozen world") {
    def off(w: World): Boolean =
      w.ext.declineVol == 0.0 && w.ext.declineVolHalf == 5.0 && w.ext.drawdownVol == 0.0 &&
        w.ext.declineVolAttack == 0.0
    assert(off(MarketSim.Defaults))
    MarketSim.Releases.foreach((v, w) => assert(off(w), s"release $v"))
    // the vol line's solve carries them (PLAN row 65): the 0.25.0 S&P recipe alone
    MarketSim.Recipes.foreach((n, w, _) => assertEquals(off(w), n != "0.25.0-sp500", s"recipe $n"))
  }

  test("both dials at zero are bit-identical") {
    // both dials at 0 are the path before the dials existed, whatever the half-life and the attack:
    // either 0.25.0 recipe with the four off, set through `CalibrateRanges`, keeps its path bit for
    // bit, and either dial on moves it
    for name <- Vector("0.25.0-nasdaq", "0.25.0-sp500") do
      val w = dialsOff(recipe(name))
      val a = MarketSim.simulate(w, 3, 11L)
      def same(p: Path): Boolean = bits(p.price) == bits(a.price) && bits(p.bond) == bits(a.bond)
      val z = set(set(set(set(w, "declineVol", 0.0), "drawdownVol", 0.0), "declineVolHalf", 9.0), "declineVolAttack", 0.8)
      assert(same(MarketSim.simulate(z, 3, 11L)), s"$name: off is the path")
      assert(!same(MarketSim.simulate(set(w, "declineVol", 0.4), 3, 11L)), s"$name: declineVol moves it")
      assert(!same(MarketSim.simulate(set(w, "drawdownVol", 2.0), 3, 11L)), s"$name: drawdownVol moves it")
  }

  /** The session sd over the five sessions after a 3-sd decline, as a multiple of the sd the
    * decline came in, over the same after a 3-sd rise (3 sd of the session's own `sdPre`). */
  private def declineContrast(w: World): Double =
    val r = parts(w, 100)
    val (dl, sd) = (col("dLogPrice"), col("sdPre"))
    def after(sign: Double): Double =
      val hits = r.sliding(6).drop(1).filter(x => sign * x(0)(dl) > 3.0 * x(0)(sd))
        .map(x => x.drop(1).map(_(sd)).sum / (5.0 * x(0)(sd))).toVector
      assert(hits.length >= 20, s"${hits.length} sessions past 3 sd")
      hits.sum / hits.length.toDouble
    after(-1.0) / after(1.0)

  test("a decline raises the sd after it more than a rise does") {
    // at 0.4 the session sd after a 3-sd decline exceeds the sd after a 3-sd rise over the next
    // five sessions, by more than the same world's contrast at 0 (the leverage kick's own)
    val off = declineContrast(onSp500(identity))
    val c = declineContrast(onSp500(_.copy(declineVol = 0.4)))
    assert(c > 1.0 && c > off, s"after a decline over after a rise $c at 0.4, $off at 0")
  }

  /** The mean session sd four sessions after a 3-sd decline over the mean sd one session after it
    * (3 sd of the session's own `sdPre`). */
  private def declineRise(w: World): Double =
    val r = parts(w, 100)
    val (dl, sd) = (col("dLogPrice"), col("sdPre"))
    val hits = r.sliding(5).drop(1).filter(x => -x(0)(dl) > 3.0 * x(0)(sd)).toVector
    assert(hits.length >= 20, s"${hits.length} sessions past 3 sd")
    hits.map(_(4)(sd)).sum / hits.map(_(1)(sd)).sum

  test("the attack builds the answer over the sessions after a decline") {
    // at `declineVol` 0.4 the path at attack 0.8 differs from the path at 0, and the sd four
    // sessions after a 3-sd decline stands higher over the sd one session after it than at attack
    // 0, where the decline state's answer is largest at lag 1
    val at0 = onSp500(_.copy(declineVol = 0.4))
    val at8 = at0.copy(ext = at0.ext.copy(declineVolAttack = 0.8))
    assertNotEquals(bits(MarketSim.simulate(at8, 3, 11L).price), bits(MarketSim.simulate(at0, 3, 11L).price))
    val (off, on) = (declineRise(at0), declineRise(at8))
    assert(on > off, s"the sd at t+4 over at t+1 $on at attack 0.8, $off at 0")
  }

  /** The mean session sd while the printed price sits 20%+ under the high of the 250 closes before
    * it, over the mean sd at that high (the record's first 250 sessions skipped, so every high
    * reads a full window). */
  private def drawdownRatio(w: World): Double =
    val r = parts(w, 100)
    val (lp, sd) = (col("logPrice"), col("sdPre"))
    val deep = -MarketSim.lnDet(0.8)
    val win = MarketSim.DrawdownVolWindow
    val gaps = r.sliding(win + 1).map { x =>
      val hi = x.take(win).map(_(lp)).foldLeft(Double.NegativeInfinity)(math.max)
      (hi - x(win - 1)(lp), x(win)(sd))
    }.toVector
    val under = gaps.filter(_._1 >= deep).map(_._2)
    val top = gaps.filter(g => !(g._1 >= deep) && g._1 <= 0.0).map(_._2)
    assert(under.length > 100 && top.length > 100, s"${under.length} sessions 20%+ under, ${top.length} at the high")
    (under.sum / under.length.toDouble) / (top.sum / top.length.toDouble)

  test("the drawdown raises the sd under the high") {
    // at 2 the mean session sd while the printed price sits 20%+ under its 250-session high
    // exceeds the sd at the high by more than 1.3x, and by more than the same world at 0
    val off = drawdownRatio(onSp500(identity))
    val r = drawdownRatio(onSp500(_.copy(drawdownVol = 2.0)))
    assert(r > 1.3 && r > off, s"sd 20%+ under the high over at it $r at 2, $off at 0")
  }

  test("the vol parts carry the two terms") {
    // between `volResp` and `asym`: zero on every session with the dials off; on, never negative,
    // the decline's within its cap, each nonzero somewhere
    assertEquals(col("declineVol"), col("volResp") + 1)
    assertEquals(col("drawdownVol"), col("declineVol") + 1)
    assertEquals(col("asym"), col("drawdownVol") + 1)
    val (d, g) = (col("declineVol"), col("drawdownVol"))
    assert(parts(onSp500(identity), 3).forall(x => x(d) == 0.0 && x(g) == 0.0))
    val r = parts(onSp500(_.copy(declineVol = 0.4, drawdownVol = 2.0)), 3)
    assert(r.forall(x => x(d) >= 0.0 && x(d) <= 0.4 * 4.0 && x(g) >= 0.0))
    assert(r.exists(_(d) > 0.0) && r.exists(_(g) > 0.0))
  }

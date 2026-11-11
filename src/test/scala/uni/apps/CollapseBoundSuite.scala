package uni.apps

import munit.FunSuite
import uni.data.*

/** The collapse bound, the two ceilings that hold a world inside it, the energy score and the rate
  * floor's stays: the checks the Rust twin's `collapse_tests`, `crps_tests` and `rate_stay_tests`
  * make, on the same inputs. */
class CollapseBoundSuite extends FunSuite:
  private val Year = MarketSim.DaysPerYear

  private def priced(lr: Array[Double]): MarketSim.Path =
    val px = lr.scanLeft(100.0)((p, r) => p * math.exp(r))
    MarketSim.simulate(MarketSim.Defaults, 30, 1L).copy(price = px)

  test("the worst year is the least sum of any 252 sessions") {
    val rng = new NumPyRNG(20261110L)
    val lr = Array.fill(1500)(0.01 * rng.randn())
    val brute = lr.sliding(Year).map(_.sum).min
    val got = priced(lr).worstYearLr
    assert(math.abs(got - brute) < 1e-9, s"$got against $brute")
    val fall = Array.tabulate(600)(i => if i >= 100 && i < 352 then -0.01 else 0.0)
    assert(math.abs(priced(fall).worstYearLr + 2.52) < 1e-9)
    assert(priced(lr.take(Year - 1)).worstYearLr.isNaN)
  }

  test("the pooled reading sets aside one history in two hundred of the pool") {
    val nan = Vector.fill(MarketSim.CollapseKeep - 3)(Double.NaN)
    val a = Vector(-5.0, -1.9, -1.0) ++ nan
    val b = Vector(-3.0, -2.5, -1.2) ++ nan
    assertEquals(MarketSim.collapseReading(Vector(a, b), Vector(200, 200)), -2.5)
    assertEquals(MarketSim.collapseReading(Vector(a), Vector(200)), -1.9)
    assertEquals(MarketSim.collapseReading(Vector(a), Vector(199)), -5.0)
    assert(MarketSim.collapseReading(Vector(Vector.fill(MarketSim.CollapseKeep)(Double.NaN)), Vector(0)).isNaN)
  }

  test("a collapsing read fails realism and a read without a year passes") {
    val a = MarketSim.anchorsNamed("sp500")
    val sims = Vector.tabulate(4)(k => MarketSim.simulate(MarketSim.Defaults, 30, (k + 1).toLong))
    val st = MarketSim.measure(sims, 30)
    def collapse(edge: Double): Boolean =
      MarketSim.gateChecks(a, st.copy(worstYearEdge = edge)).find(_._1 == MarketSim.CollapseGate)
        .getOrElse(fail("the collapse bound's gate"))._2
    assert(collapse(MarketSim.CollapseMultiple * a.worstYear + 0.01))
    assert(!collapse(MarketSim.CollapseMultiple * a.worstYear - 0.01))
    assert(collapse(Double.NaN))
  }

  test("a ceiling far above its state leaves the path and a binding one moves it") {
    val w0 = MarketSim.namedWorld("0.25.1-sp500").map(_._1).getOrElse(fail("a recipe"))
    val w = w0.copy(ext = w0.ext.copy(slowCap = 0.0, drawdownCap = 0.0))
    val base = MarketSim.simulate(w, 20, 7L).price
    def far(f: MarketSim.WorldExt => MarketSim.WorldExt): Double =
      MarketSim.simulate(w.copy(ext = f(w.ext)), 20, 7L).price.zip(base).map((x, y) => math.abs(math.log(x / y))).max
    assert(far(_.copy(slowCap = 1e6)) < 1e-4)
    assert(far(_.copy(drawdownCap = 1e6)) < 1e-4)
    assert(far(_.copy(slowCap = 1.0)) > 1e-3)
    assert(far(_.copy(drawdownCap = 0.05)) > 1e-3)
  }

  test("the energy score reads the record as its cloud") {
    val xs = Vector(3.0, 1.0, 2.0)
    assert(math.abs(MarketSim.energyScore(xs, 2.0, 0.0) - MarketSim.crps(xs, 2.0)) < 1e-14)
    assert(math.abs(MarketSim.energyScore(xs, 5.0, 0.0) - MarketSim.crps(xs, 5.0)) < 1e-14)
    assertEquals(MarketSim.CloudK, 25)
    for k <- 0 until MarketSim.CloudK do
      assert(math.abs(MarketSim.cloudPoint(k) + MarketSim.cloudPoint(MarketSim.CloudK - 1 - k)) < 1e-15, "symmetric")
      if k > 0 then assert(MarketSim.cloudPoint(k) > MarketSim.cloudPoint(k - 1), "ascending")
    assert(math.abs(MarketSim.cloudSpread - 0.554) < 0.001, s"${MarketSim.cloudSpread}")
    val cloud = Vector.tabulate(MarketSim.CloudK)(k => 1.5 + 0.2 * MarketSim.cloudPoint(k))
    assert(math.abs(MarketSim.energyScore(cloud, 1.5, 0.2)) < 1e-14, "the cloud against itself")
    val point = Vector(1.5, 1.5)
    val ePoint = MarketSim.energyScore(point, 1.5, 0.2)
    assert(ePoint > 0.2 * 0.2 && ePoint < 0.2 * 0.3, s"a point mass pays $ePoint")
    assert(MarketSim.energyScore(cloud, 1.5, 0.2) < ePoint)
    assert(MarketSim.energyScore(point, 1.5, -0.1).isNaN && MarketSim.energyScore(Vector(1.0), 1.0, 0.1).isNaN)
  }

  /** eight years: a two-year stay, a one-month dip, a one-year stay */
  private def stayPath: Array[Double] =
    Array.tabulate(8 * Year) { i =>
      if i >= Year && i < 3 * Year then 0.001
      else if i >= 4 * Year && i < 4 * Year + 21 then 0.0
      else if i >= 6 * Year && i < 7 * Year then 0.002
      else 0.04
    }

  test("a dip is not a stay and the longest reads in years") {
    assertEquals(MarketSim.rateStayReadings(stayPath), Vector(25.0, 2.0))
    assertEquals(MarketSim.rateStayReadings(Array.fill(1000)(0.04)), Vector(0.0, 0.0))
    val all = Array.fill(4 * Year)(0.0)
    assertEquals(MarketSim.rateStayReadings(all), Vector(25.0, 4.0))
    all(Year) = 0.01
    assertEquals(MarketSim.rateStayReadings(all)(0), 50.0, "a crossing splits a stay")
  }

  test("the resample draws the record's own stays") {
    val reads = MarketSim.rateStayResamples(stayPath, 2000, 7L)
    assertEquals(reads, MarketSim.rateStayResamples(stayPath, 2000, 7L), "deterministic")
    val meanCount = reads.map(_(0)).sum / reads.length
    assert(math.abs(meanCount - 25.0) < 2.5, s"Poisson at the record's rate: $meanCount")
    assert(reads.forall(x => Vector(0.0, 1.0, 2.0).contains(x(1))), "the lengths are the record's")
    assert(reads.exists(_(1) == 2.0) && reads.exists(_(1) == 0.0))
    assert(MarketSim.rateStayResamples(Array.fill(600)(0.04), 5, 1L).forall(_ == Vector(0.0, 0.0)))
  }

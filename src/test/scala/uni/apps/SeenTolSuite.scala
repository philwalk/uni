package uni.apps

import munit.FunSuite

/** The readers of the inflation seen read a rate that never moves by more than `SeenTol` as one
  * rate, not as its rounding noise, and the crash fit takes only pairs `CrashSeenRes` apart.  The
  * Rust twin carries the same checks in `seen_tol_tests`. */
class SeenTolSuite extends FunSuite:

  /** A constant rate as the simulation computes it: `rate` plus rounding noise of ~1e-13. */
  def noisy(rate: Double, n: Int): Array[Double] = Array.tabulate(n)(i => rate + 1e-14 * ((i * 7) % 11))

  /** Monthly log changes whose year-over-year rate never moves, read through ~1e-12 of noise: every
    * twelve months hold six of each step, perturbed at 1e-15. */
  def noisySteps(n: Int): Array[Double] =
    Array.tabulate(n)(i => (if i % 2 == 0 then 0.004 else 0.002) + 1e-15 * ((i * 7) % 11))

  test("the crash fit reads one noisy rate as one rate") {
    // declines all seen under one noisy rate hold no pair `CrashSeenRes` apart: NaN, not the ~1e14
    // slopes the noise makes
    val pts = noisy(2.5, 5).toVector.zip(Vector(-3.0, -9.0, 1.0, -4.0, -6.0))
    val fit = MarketSim.crashFit(pts)
    assert(fit.forall(_.isNaN), s"$fit")
  }

  test("the crash fit takes only pairs the record could resolve") {
    // declines seen apart by less than the record's resolution (a floor's decay, 1e-5 pp) hold no
    // measurable pair either; one decline a resolvable step away gives the fit its slopes
    val close = Vector((2.5, -3.0), (2.5 + 1e-5, -9.0), (2.5 + 2e-5, 1.0))
    assert(MarketSim.crashFit(close).forall(_.isNaN))
    val fit = MarketSim.crashFit(close :+ (2.5 + MarketSim.CrashSeenRes, -5.0))
    assert(fit.forall(_.isFinite), s"$fit")
    // every slope is against the far point; the median of (-2, 4, -6) over 3e-4
    assert(math.abs(fit(1) - (-2.0 / MarketSim.CrashSeenRes)) < 1e-6, s"$fit")
  }

  test("the stock-bond dependence reads zero on a noisy rate") {
    // a month the correlation leaves out cannot make it look moving; the noise itself does
    // correlate, so the guard is what reads zero
    val n    = 120
    val eq   = Array.tabulate(n)(i => ((i * 7919) % 13).toDouble / 13.0 - 0.5)
    val b    = Array.tabulate(n)(i => ((i * 104729) % 11).toDouble / 11.0 - 0.5)
    val seen = noisy(3.0, n)
    assert(MarketSim.finiteCorr(seen, eq) != 0.0, "the noise correlates")
    assertEquals(MarketSim.stockBondReadings(eq, b, seen)(1), 0.0)
    seen(5) = Double.NaN
    assertEquals(MarketSim.stockBondReadings(eq, b, seen)(1), 0.0)
  }

  test("the cpi news reads NaN on a noisy rate") {
    // a noisy constant rate seen puts every month on the upper side: NaN, not a split by noise
    assert(MarketSim.cpiNewsReading(noisySteps(96)).isNaN)
  }

  test("the cpi news puts a floor on the lower side") {
    // a rate on a noisy floor most months, rising above it in one spell of wide changes: the
    // floor, the median, goes whole to the lower side, which balances the halves, and the reading
    // finds the spread at the high rate
    val dlog = Array.tabulate(144): i =>
      if i >= 60 && i < 84 then (if i % 2 == 0 then 0.008 else 0.004)
      else 0.002 + 1e-15 * ((i * 7) % 11)
    val r = MarketSim.cpiNewsReading(dlog)
    assert(r.isFinite && r > 0.5, s"$r")
  }

  test("the cpi persistence reads NaN on a noisy rate") {
    // neither has one that settles after its first year: the later side of the pairing is flat
    assert(MarketSim.cpiPersistence(noisySteps(96)).isNaN)
    val settles = Array.fill(12)(0.004) ++ noisySteps(84)
    assert(MarketSim.cpiPersistence(settles).isNaN)
  }

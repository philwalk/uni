package uni.apps

import munit.FunSuite

/** THE REAL SIDE's record readings (`CpiBandRows` and kin, `tips-2026-10-11.tsv`) on series whose
  * rows are known in closed form.  The Rust twin carries the same checks in `real_side_tests`; the
  * fixture itself is the twins' byte-for-byte comparison on the records. */
class RealSideSuite extends FunSuite:

  private def near(a: Double, b: Double, tol: Double = 1e-9): Boolean = math.abs(a - b) <= tol

  test("the CPI rows read a constant rate") {
    val dlog = Array.fill(120)(0.003)
    val r    = MarketSim.cpiReadings(dlog)
    assert(near(r(0), 3.6), s"mean ${r(0)}")
    assert(near(r(1), 0.0) && near(r(2), 0.0), s"sds ${r(1)} ${r(2)}")
    assert(r(3) == 0.0, s"4%+ share ${r(3)}")
    val rep = MarketSim.cpiReported(dlog)
    assert(rep(0) == 0.0 && rep(1).isNaN && rep(2).isNaN, s"no deflation, no spells: $rep")
  }

  // a year of 0.4% months then a year of flat ones, repeated: the year-over-year rate is a
  // triangle wave of period 24, its own mirror twelve months on (simple percent of a log sum
  // bends it by a part in 10^4)
  test("the CPI persistence reads a mirrored rate as minus one") {
    val dlog = Array.tabulate(96)(m => if (m / 12) % 2 == 0 then 0.004 else 0.0)
    val p    = MarketSim.cpiPersistence(dlog)
    assert(near(p, -1.0, 1e-3), s"persistence $p")
    assert(MarketSim.cpiPersistence(dlog.take(14)).isNaN, "under two years")
  }

  test("the CPI rows read an alternating month and a 4% spell") {
    val dlog = Array.tabulate(48)(i => if i % 2 == 0 then 0.01 else -0.01)
    val r    = MarketSim.cpiReadings(dlog)
    assert(near(r(0), 0.0) && near(r(1), 0.0) && near(r(3), 0.0), s"$r")
    assert(near(r(2), 0.01 * math.sqrt(48.0 / 47.0) * 100.0), s"monthly sd ${r(2)}")
    val spell = Array.fill(36)(0.0) ++ Array.fill(24)(0.005) ++ Array.fill(36)(0.0)
    val rep   = MarketSim.cpiReported(spell)
    // 4%+ for the months whose trailing year holds eight or more of the 0.5% months (eight make
    // 4.08%, seven 3.56%): one spell of 21 months
    assertEquals(rep(1), 21.0)
    assertEquals(rep(2), 21.0)
  }

  test("the century rows count deflation months and spell starts") {
    // ten years: a year of -0.5% a month, then two spells of 0.5% a month a year apart
    val dlog = Array.fill(12)(0.0) ++ Array.fill(12)(-0.005) ++ Array.fill(24)(0.0) ++
      Array.fill(12)(0.005) ++ Array.fill(12)(0.0) ++ Array.fill(12)(0.005) ++ Array.fill(36)(0.0)
    val r = MarketSim.cpiCenturyReadings(dlog)
    // the year-over-year rate is below zero from the first negative month to a year after the
    // last, 23 months of the 109 the rate exists
    assert(near(r(0), 23.0 / 109.0 * 100.0), s"deflation share ${r(0)}")
    assert(near(r(1), 2.0 / 10.0 * 100.0), s"spell starts per century ${r(1)}")
  }

  test("the real-yield rows read a line and a sawtooth") {
    val x = Array.tabulate(36)(i => 1.0 + 0.1 * i)
    val r = MarketSim.realYieldReadings(x)
    assert(near(r(0), 0.0), s"a line's monthly change has no spread: ${r(0)}")
    val rep = MarketSim.realYieldReported(x)
    assert(near(rep(0), 2.75) && near(rep(2), 1.0, 1e-12) && rep(3) == 1.0 && near(rep(4), 4.5), s"$rep")
    // a sawtooth: every change is +0.3 or -0.3, so their sd is 0.3 times sqrt(n / (n - 1))
    val saw  = Array.tabulate(37)(i => if i % 2 == 0 then 1.0 else 1.3)
    val want = 0.3 * math.sqrt(36.0 / 35.0)
    val flat = Array.fill(37)(2.0)
    assert(near(MarketSim.realYieldReadings(saw)(0), want, 1e-12) && near(MarketSim.breakevenReadings(saw, flat, saw)(0), want, 1e-12) &&
           near(MarketSim.breakevenReadings(saw, flat, saw)(1), want, 1e-12) && near(MarketSim.breakevenReadings(saw, saw, saw)(1), 0.0, 1e-12) &&
           near(MarketSim.breakevenReadings(saw, flat, saw)(2), 1.0, 1e-12),
           s"sawtooth ${MarketSim.realYieldReadings(saw)}")
    val be = MarketSim.breakevenReported(saw)
    assert(near(be(0), 1.0 + 0.3 * 18.0 / 37.0, 1e-12) && be(2) == 1.0, s"$be")
  }

  test("the TIPS rows read an alternating leg") {
    val r   = Array.tabulate(252)(i => if i % 2 == 0 then 0.001 else -0.001)
    val dur = Array.fill(252)(8.0)
    val eq  = r.map(-_)
    val t   = MarketSim.tipsReadings(r, dur, r, eq)
    assert(near(t(0), 0.001 / 8.0 * math.sqrt(252.0) * 100.0), s"vol per duration ${t(0)}")
    assert(t(1).isNaN, s"never under water and a volatility that implies no depth: ${t(1)}")
    assert(near(t(2), 1.0) && near(t(3), -1.0), s"correlations ${t(2)} ${t(3)}")
  }

  test("the yield's beta on the market moves the bond against the market") {
    // the recipe with the channel off: it carries a beta of its own; over 100 years the 0.1 bound
    // is below every reading on eight seeds
    val w0 = MarketSim.namedWorld("0.25.0-sp500").map(_._1).getOrElse(fail("recipe"))
    val w  = w0.copy(ext = w0.ext.copy(yieldBeta = 0.0, yieldLag = 0.0))
    assert(w.ext.termVol > 0.0)
    val off = MarketSim.simulate(w, 100, MarketSim.DefaultSeed)
    val on  = MarketSim.simulate(w.copy(ext = w.ext.copy(yieldBeta = 0.05)), 100, MarketSim.DefaultSeed)
    assert(!off.bond.sameElements(on.bond))
    val cOff = monthlyCorr(off.price, off.bond)
    val cOn  = monthlyCorr(on.price, on.bond)
    assert(cOn < cOff - 0.1, s"monthly corr $cOn against $cOff at zero")
  }

  test("the volatility feedback makes declines persist after spikes") {
    // the recipe with the feedback off: it carries one of its own
    val w0  = MarketSim.namedWorld("0.25.0-nasdaq").map(_._1).getOrElse(fail("recipe"))
    val w   = w0.copy(ext = w0.ext.copy(volFeedback = 0.0))
    val off = MarketSim.simulate(w, 30, MarketSim.DefaultSeed)
    val on  = MarketSim.simulate(w.copy(ext = w.ext.copy(volFeedback = 1.0)), 30, MarketSim.DefaultSeed)
    assert(!off.price.sameElements(on.price))
    val a = MarketSim.volExitOf(MarketSim.volExitDaysOfPath(off))
    val b = MarketSim.volExitOf(MarketSim.volExitDaysOfPath(on))
    assert(b(0) > a(0) && b(2) < a(2), s"timing ${b(0)} vs ${a(0)}, high-vol return ${b(2)} vs ${a(2)}")
  }

  test("the anchoring moves the regime from the breakeven to the real yield") {
    val w0  = MarketSim.namedWorld("0.25.0-nasdaq").map(_._1).getOrElse(fail("recipe"))
    // the recipe anchored on the breakeven alone: it carries an anchoring of its own
    val w   = w0.copy(ext = w0.ext.copy(tips = 8.0, expAnchor = 1.0))
    assert(w.ext.termVol > 0.0)
    val off = MarketSim.simulate(w, 20, MarketSim.DefaultSeed)
    val on  = MarketSim.simulate(w.copy(ext = w.ext.copy(expAnchor = 0.5)), 20, MarketSim.DefaultSeed)
    assert(off.price.sameElements(on.price) && off.bond.sameElements(on.bond) && !off.tips10.sameElements(on.tips10))
    def sd(x: Array[Double]): Double =
      val d = (21 until x.length by 21).map(i => x(i) - x(i - 21))
      val m = d.sum / d.length
      math.sqrt(d.map(v => (v - m) * (v - m)).sum / (d.length - 1))
    assert(sd(on.expInfl10) < sd(off.expInfl10) && sd(on.realYield10) > sd(off.realYield10),
      s"expectation ${sd(on.expInfl10)} vs ${sd(off.expInfl10)}, real yield ${sd(on.realYield10)} vs ${sd(off.realYield10)}")
  }

  test("the beta moves the breakeven and not the real yield") {
    val w0  = MarketSim.namedWorld("0.25.0-nasdaq").map(_._1).getOrElse(fail("recipe"))
    val w   = w0.copy(ext = w0.ext.copy(tips = 8.0, yieldBeta = 0.0, yieldLag = 0.0))
    val off = MarketSim.simulate(w, 20, MarketSim.DefaultSeed)
    val on  = MarketSim.simulate(w.copy(ext = w.ext.copy(yieldBeta = 0.05)), 20, MarketSim.DefaultSeed)
    def sd(x: Array[Double]): Double =
      val d = (21 until x.length by 21).map(i => x(i) - x(i - 21))
      val m = d.sum / d.length
      math.sqrt(d.map(v => (v - m) * (v - m)).sum / (d.length - 1))
    val (b0, b1) = (sd(off.expInfl10), sd(on.expInfl10))
    val (r0, r1) = (sd(off.realYield10), sd(on.realYield10))
    assert(b1 > b0 * 1.05 && r1 < r0 * 1.05, s"breakeven $b0 -> $b1, real yield $r0 -> $r1")
  }

  test("the yield's lag deepens the correlation over the quarter") {
    val w    = MarketSim.namedWorld("0.25.0-sp500").map(_._1).getOrElse(fail("recipe"))
    val fast = w.copy(ext = w.ext.copy(yieldBeta = 0.05, yieldLag = 0.0))
    val slow = fast.copy(ext = fast.ext.copy(yieldLag = 21.0))
    def deep(p: MarketSim.Path): (Double, Double) =
      val eq = MarketSim.monthlySums(MarketSim.dailyReturns(p.price))
      val b  = MarketSim.monthlySums(MarketSim.dailyReturns(p.bond))
      val r  = MarketSim.stockBondReadings(eq, b, Array.fill(eq.length)(1.0))
      (r(0), r(2))
    val a = deep(MarketSim.simulate(fast, 20, MarketSim.DefaultSeed))
    val c = deep(MarketSim.simulate(slow, 20, MarketSim.DefaultSeed))
    assert(c._1 > a._1 && c._2 < a._2, s"lagged $c against instant $a")
  }

  /** the correlation of the two series' 21-session log returns */
  private def monthlyCorr(a: Array[Double], b: Array[Double]): Double =
    val n  = a.length / 21
    val ra = (1 until n).map(k => math.log(a(k * 21) / a((k - 1) * 21)))
    val rb = (1 until n).map(k => math.log(b(k * 21) / b((k - 1) * 21)))
    val (ma, mb) = (ra.sum / ra.length, rb.sum / rb.length)
    val sab = ra.zip(rb).map((x, y) => (x - ma) * (y - mb)).sum
    val saa = ra.map(x => (x - ma) * (x - ma)).sum
    val sbb = rb.map(y => (y - mb) * (y - mb)).sum
    sab / math.sqrt(saa * sbb)

  test("the CPI noise leaves every other series bit-identical") {
    val w   = MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe"))
    val off = MarketSim.simulate(w, 5, MarketSim.DefaultSeed)
    val on  = MarketSim.simulate(w.copy(ext = w.ext.copy(cpiNoise = 0.01)), 5, MarketSim.DefaultSeed)
    assert(off.price.sameElements(on.price) && off.rate.sameElements(on.rate) && off.bond.sameElements(on.bond))
    assert(off.inflPress.sameElements(on.inflPress) && off.liq.sameElements(on.liq))
    assert(!off.cpi.sameElements(on.cpi), "the level itself moves")
  }

  test("the deflation side and the spikes show in the CPI rows") {
    val w    = MarketSim.namedWorld("0.24.6-sp500").map(_._1).getOrElse(fail("recipe"))
    val base = MarketSim.cpiRowsOfPath(MarketSim.simulate(w, 100, MarketSim.DefaultSeed).cpi)
    val defl = MarketSim.cpiRowsOfPath(
      MarketSim.simulate(w.copy(ext = w.ext.copy(deflProb = 1.0, deflSize = 0.08)), 100, MarketSim.DefaultSeed).cpi)
    assertEquals(base._2(0), 0.0, "no deflation without the side")
    assert(defl._2(0) > 5.0, s"deflation share ${defl._2(0)}")
    val spiked = MarketSim.cpiRowsOfPath(
      MarketSim.simulate(w.copy(ext = w.ext.copy(spikeRate = 1.0, spikeSize = 0.05)), 100, MarketSim.DefaultSeed).cpi)
    assert(spiked._2(1) > base._2(1) + 10.0, s"spell starts a century ${spiked._2(1)} against ${base._2(1)}")
    val z = w.copy(ext = w.ext.copy(deflProb = 0.0, spikeRate = 0.0, cpiNoise = 0.0))
    assert(MarketSim.simulate(z, 5, MarketSim.DefaultSeed).price.sameElements(MarketSim.simulate(w, 5, MarketSim.DefaultSeed).price))
  }

  test("the real-rate rows read a mean and a sample sd") {
    val r = MarketSim.realRateReadings(Array(1.0, 2.0, 3.0, 4.0))
    assert(near(r(0), 2.5) && near(r(1), math.sqrt(5.0 / 3.0)), s"$r")
  }

  /** THE STOCK-BOND ROWS: equal series correlate at one, and with the inflation seen never moving
    * the slope is undefined; a bond that follows the equity when the inflation seen is high and
    * opposes it when low reads a slope of about one half a point over a four-point gap; the
    * rolling rows see a correlation that never changes sign; equal series deepen by nothing, and
    * a bond that follows the equity one month late correlates over quarters far more than over
    * months and over years no more. */
  test("the stock-bond rows read a regime switch") {
    val n    = 120
    val eq   = Array.tabulate(n)(i => ((i * 7919) % 13).toDouble / 13.0 - 0.5)
    val flat = Array.fill(n)(3.0)
    val same = MarketSim.stockBondReadings(eq, eq, flat)
    assert(near(same(0), 1.0) && near(same(1), 0.0), s"$same")
    assert(near(same(2), 0.0) && near(same(3), 0.0), s"$same")
    val late = Array.tabulate(n)(i => eq((i + n - 1) % n))
    val lag  = MarketSim.stockBondReadings(eq, late, flat)
    assert(lag(2) > 0.25 && math.abs(lag(3)) < 0.1, s"a month's lag deepens over quarters, not years: $lag")
    val seen = Array.tabulate(n)(i => if i % 2 == 0 then 5.0 else 1.0)
    val b    = eq.zip(seen).map((e, s) => if s >= 3.0 then e else -e)
    val flip = MarketSim.stockBondReadings(eq, b, seen)
    assert(flip(1) > 0.5 && flip(1) <= 1.0, s"corr with the inflation seen ${flip(1)}")
    val rep = MarketSim.stockBondReported(eq, eq)
    assert(near(rep(0), 0.0) && near(rep(1), 0.0) && near(rep(2), 1.0), s"$rep")
    val few = MarketSim.stockBondReadings(eq.take(20), eq.take(20), seen.take(20))
    assert(few(1).isNaN && few(2).isNaN && few(3).isNaN, s"under two years: $few")
  }

  /** THE CPI NEWS ROW: a level whose monthly change spreads ten times more whenever the prior
    * year's rate was high reads a share well above a half (the rate seen lags a block's start by
    * up to a year, which blurs the ten-fold ratio toward two); the noise on the low level instead
    * reads below a half; a rate that never moves has no split. */
  test("the CPI news row reads the noise by the rate seen") {
    def blocks(noiseOf: Int => (Double, Double)): Array[Double] =
      (0 until 4).toArray.flatMap { block =>
        val (level, noise) = noiseOf(block)
        Array.tabulate(60)(i => level + (if i % 2 == 0 then noise else -noise))
      }
    val r = MarketSim.cpiNewsReading(blocks(block => if block % 2 == 0 then (0.001, 0.001) else (0.01, 0.01)))
    assert(r > 0.6 && r < 0.91, s"high months spread more than the low: $r")
    val inverse = MarketSim.cpiNewsReading(blocks(block => if block % 2 == 0 then (0.001, 0.005) else (0.01, 0.0005)))
    assert(inverse < 0.41, s"low months spread more than the high: $inverse")
    val flat = Array.tabulate(72)(i => if i % 2 == 0 then 0.004 else 0.002)
    assert(MarketSim.cpiNewsReading(flat).isNaN && MarketSim.cpiNewsReading(flat.take(20)).isNaN)
  }

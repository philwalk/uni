#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.19.3

import uni.data.*
import uni.stats.Tprf3
import scala.util.Random

/**
 * Two direct hand-rolled PLS-1 implementations against the equivalent 3PRF.
 *
 * The 3PRF arm is Tprf3.pls1Fit — the pls variant (autoproxy L=1, no intercept
 * in passes 1-2) in closed form. That variant, and only that variant, equals
 * one-component PLS-1.
 *
 * It is NOT t3prf(y, X, Z = y), which this benchmark used to call: that is a
 * different estimator, because its pass 2 uses withIntercept(phi) and so
 * cross-sectionally demeans (the J(N) projection) where PLS-1 does not. Its
 * forecasts genuinely differ, so the cross-verification assert below could
 * never have passed.
 *
 * For the library's two internal routes to this same estimator compared against
 * each other, see jsrc/pls1tprfInternalBench.sc.
 */
object Pls1TprfBench:
  def println(s: String = ""): Unit = print(s"$s\n")

  // ===========================================================================
  // BENCHMARK AND VALIDATION ENGINE
  // ===========================================================================
  def main(args: Array[String]): Unit =
    println("=== Starting PLS-1 & 3PRF Kernel Verification Suite ===")

    // Shapes taken from the production call site, VolTarget.currentExposure:
    //   row(t) = Array(hrsF(t), exoAt(t))          → 2 predictors
    //   trainIdx = minLeadWeeks(26) .. tPred-horizon → weekly rows
    // 1000 weekly observations is roughly 20 years of history.
    val trainRows = 1000
    val features  = 2
    val rand = new Random(42)

    val X = Array.fill(trainRows, features)(rand.nextGaussian() * 2.0)
    val Y = Array.fill(trainRows)(rand.nextGaussian() * 1.5)
    val testRow = Array.fill(features)(rand.nextGaussian() * 2.0)

    // ---- Correctness Check ----
    val predictorOrig = pls1FitOriginal(X, Y)
    val predictorUpgr = pls1Fit(X, Y)
    
    // Tprf3.pls1Fit takes the same argument shapes as the two hand-rolled
    // implementations, so no MatD/CVec conversion is needed here at all; it
    // also carries its own column scale, so predict takes a raw row.
    val tprfModel = Tprf3.pls1Fit(X, Y)

    val outOrig = predictorOrig(testRow)
    val outUpgr = predictorUpgr(testRow)
    val outTprf = tprfModel.predict(testRow)

    println(f"Original Implementation Output : $outOrig%.10f")
    println(f"Upgraded Implementation Output : $outUpgr%.10f")
    println(f"uni.stats.Tprf3 Output         : $outTprf%.10f")

    // Cross-verify all three
    val diffUpgr = math.abs(outOrig - outUpgr)
    val diffTprf = math.abs(outOrig - outTprf)
    
    assert(diffUpgr < 1e-9, s"Upgraded implementation drifts! Diff: $diffUpgr")
    assert(diffTprf < 1e-9, s"Tprf3 implementation drifts! Diff: $diffTprf")
    println("✓ Mathematical output alignment across all three engines verified successfully.")

    // ---- Performance & GC Benchmark ----
    val iterations = 50000
    println(s"\nRunning micro-benchmark suite ($iterations iterations)...")

    // Warm-up phase
    for _ <- 0 to 5000 do
      pls1FitOriginal(X, Y)(testRow)
      pls1Fit(X, Y)(testRow)
      Tprf3.pls1Fit(X, Y).predict(testRow)

    // Benchmark Original Loop
    val startOrig = System.nanoTime()
    var k = 0
    var checksumOrig = 0.0
    while k < iterations do
      val model = pls1FitOriginal(X, Y)
      checksumOrig += model(testRow)
      k += 1
    val endOrig = System.nanoTime()
    val timeOrigMs = (endOrig - startOrig) / 1e6

    // Benchmark Upgraded Loop
    val startUpgr = System.nanoTime()
    k = 0
    var checksumUpgr = 0.0
    while k < iterations do
      val model = pls1Fit(X, Y)
      checksumUpgr += model(testRow)
      k += 1
    val endUpgr = System.nanoTime()
    val timeUpgrMs = (endUpgr - startUpgr) / 1e6

    // Benchmark uni.stats.Tprf3 Loop
    val startTprf = System.nanoTime()
    k = 0
    var checksumTprf = 0.0
    while k < iterations do
      val model = Tprf3.pls1Fit(X, Y)
      checksumTprf += model.predict(testRow)
      k += 1
    val endTprf = System.nanoTime()
    val timeTprfMs = (endTprf - startTprf) / 1e6

    println(f"Original Engine Total Runtime   : $timeOrigMs%.2f ms")
    println(f"Upgraded Engine Total Runtime   : $timeUpgrMs%.2f ms")
    println(f"uni.stats.Tprf3 Total Runtime   : $timeTprfMs%.2f ms")
    def ratio(mine: Double, base: Double): String =
      if mine <= base then f"${base / mine}%.2fx faster" else f"${mine / base}%.2fx slower"

    println(f"Upgraded vs Original            : ${ratio(timeUpgrMs, timeOrigMs)}")
    println(f"Tprf3 vs Original               : ${ratio(timeTprfMs, timeOrigMs)}")
    println(f"Tprf3 vs Upgraded               : ${ratio(timeTprfMs, timeUpgrMs)}")

    // Checksums are accumulated so the JIT cannot eliminate the timed work;
    // they must also agree, which re-checks the three engines over every call.
    assert(math.abs(checksumOrig - checksumUpgr) < 1e-6, "Upgraded checksum drift")
    assert(math.abs(checksumOrig - checksumTprf) < 1e-6, "Tprf3 checksum drift")

  // ===========================================================================
  // ORIGINAL IMPLEMENTATION
  // ===========================================================================
  def pls1FitOriginal(x: Array[Array[Double]], y: Array[Double]): Array[Double] => Double =
    val nr = x.length; val p = x(0).length
    val mu = Array.tabulate(p)(j => x.map(_(j)).sum / nr)
    val sd = Array.tabulate(p) { j => val m = mu(j); math.sqrt(x.map(r => (r(j)-m)*(r(j)-m)).sum / (nr-1)) }
    def z(row: Array[Double]) = Array.tabulate(p)(j => if sd(j) == 0.0 then 0.0 else (row(j)-mu(j))/sd(j))
    val ybar = y.sum / nr
    val xz = x.map(z); val yc = y.map(_ - ybar)
    val w0 = Array.tabulate(p)(j => xz.indices.map(i => xz(i)(j)*yc(i)).sum)
    val wn = math.sqrt(w0.map(a => a*a).sum)
    val w  = if wn == 0.0 then w0 else w0.map(_ / wn)
    val t  = xz.map(row => row.zip(w).map { case (a, b) => a*b }.sum)
    val tt = t.map(a => a*a).sum
    val b  = if tt == 0.0 then 0.0 else t.zip(yc).map { case (a, c) => a*c }.sum / tt
    (row: Array[Double]) => ybar + b * z(row).zip(w).map { case (a, c) => a*c }.sum

  // ===========================================================================
  // UPGRADED IMPLEMENTATION
  // ===========================================================================
  def pls1Fit(x: Array[Array[Double]], y: Array[Double]): Array[Double] => Double =
    val nr = x.length
    val p  = x(0).length
    val nrInv = 1.0 / nr
    val dfInv = 1.0 / (nr - 1)

    val mu = Array.ofDim[Double](p)
    var j = 0
    while j < p do
      var sum = 0.0
      var i = 0
      while i < nr do { sum += x(i)(j); i += 1 }
      mu(j) = sum * nrInv
      j += 1

    val sd = Array.ofDim[Double](p)
    j = 0
    while j < p do
      val m = mu(j)
      var sumSq = 0.0
      var i = 0
      while i < nr do
        val diff = x(i)(j) - m
        sumSq += diff * diff
        i += 1
      val s = math.sqrt(sumSq * dfInv)
      if s == 0.0 then sd(j) = Double.NaN else sd(j) = s
      j += 1

    var ySum = 0.0
    var i = 0
    while i < nr do { ySum += y(i); i += 1 }
    val ybar = ySum * nrInv
    val yc = Array.ofDim[Double](nr)
    i = 0
    while i < nr do { yc(i) = y(i) - ybar; i += 1 }

    val w = Array.ofDim[Double](p)
    var wSqSum = 0.0
    j = 0
    while j < p do
      val m = mu(j)
      val s = sd(j)
      if !s.isNaN then
        var dot = 0.0
        i = 0
        while i < nr do
          val xz_ij = (x(i)(j) - m) / s
          dot += xz_ij * yc(i)
          i += 1
        w(j) = dot
        wSqSum += dot * dot
      j += 1

    val wn = math.sqrt(wSqSum)
    if wn == 0.0 then
      var jj = 0
      while jj < p do { w(jj) = 0.0; jj += 1 }
    else
      val wnInv = 1.0 / wn
      var jj = 0
      while jj < p do { w(jj) *= wnInv; jj += 1 }

    val t = Array.ofDim[Double](nr)
    var tt = 0.0
    i = 0
    while i < nr do
      var dot = 0.0
      j = 0
      val row = x(i)
      while j < p do
        val s = sd(j)
        if !s.isNaN then
          dot += ((row(j) - mu(j)) / s) * w(j)
        j += 1
      t(i) = dot
      tt += dot * dot
      i += 1

    val b = if tt == 0.0 then 
      0.0 
    else
      var tDotYc = 0.0
      i = 0
      while i < nr do { tDotYc += t(i) * yc(i); i += 1 }
      tDotYc / tt

    val finalMu = mu
    val finalSd = sd
    val finalW  = w

    (row: Array[Double]) =>
      var score = 0.0
      var jj = 0
      while jj < p do
        val s = finalSd(jj)
        if !s.isNaN then
          score += ((row(jj) - finalMu(jj)) / s) * finalW(jj)
        jj += 1
      ybar + (b * score)

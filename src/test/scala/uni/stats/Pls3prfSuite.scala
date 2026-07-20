package uni.stats

import munit.FunSuite
import uni.data.*
import uni.stats.Tprf3.*
import scala.util.Random

/** plsClosedForm / pls1Fit: the PLS-variant 3PRF (autoproxy L=1, no intercept in
 *  passes 1-2), which coincides with one-component PLS-1.
 *
 *  The reference below is a direct PLS-1 implementation, deliberately written in
 *  the textbook form (standardize X, center y, w = Xz'yc normalized, t = Xz·w,
 *  b = t'yc/t't) rather than in terms of any 3PRF code — so the equivalence is
 *  being tested, not assumed. */
class Pls3prfSuite extends FunSuite:

  private val tol = 1e-9

  /** Textbook one-component PLS-1; returns the fitted predictor. */
  private def pls1Reference(
      x: Array[Array[Double]],
      y: Array[Double],
  ): Array[Double] => Double =
    val nr = x.length
    val p  = x(0).length
    val mu = Array.tabulate(p)(j => x.map(_(j)).sum / nr)
    val sd = Array.tabulate(p): j =>
      val m = mu(j)
      math.sqrt(x.map(r => (r(j) - m) * (r(j) - m)).sum / (nr - 1))
    def z(row: Array[Double]) =
      Array.tabulate(p)(j => if sd(j) == 0.0 then 0.0 else (row(j) - mu(j)) / sd(j))
    val ybar = y.sum / nr
    val xz   = x.map(z)
    val yc   = y.map(_ - ybar)
    val w0   = Array.tabulate(p)(j => xz.indices.map(i => xz(i)(j) * yc(i)).sum)
    val wn   = math.sqrt(w0.map(a => a * a).sum)
    val w    = if wn == 0.0 then w0 else w0.map(_ / wn)
    val t    = xz.map(row => row.zip(w).map((a, b) => a * b).sum)
    val tt   = t.map(a => a * a).sum
    val b    = if tt == 0.0 then 0.0 else t.zip(yc).map((a, c) => a * c).sum / tt
    row => ybar + b * z(row).zip(w).map((a, c) => a * c).sum

  private def dataset(rows: Int, cols: Int, seed: Int) =
    val rand = new Random(seed)
    val x    = Array.fill(rows, cols)(rand.nextGaussian() * 2.0)
    val y    = Array.fill(rows)(rand.nextGaussian() * 1.5)
    val test = Array.fill(cols)(rand.nextGaussian() * 2.0)
    (x, y, test)

  // Small N is the case the iterative pls path cannot serve: its pass 2 is a
  // cross-sectional regression over N observations but is gated by minObs (a
  // time-series guard, default 10), so estimate3prf(pls = true) yields all-NaN
  // for N < 10.  The closed form has no per-row solve and so no such floor.
  private val shapes = Seq(
    (60, 3), (60, 4), (60, 9), (60, 10), (60, 12),
    (500, 4), (500, 12), (500, 40),
  )

  for (rows, cols) <- shapes do
    test(s"pls1Fit matches PLS-1 in-sample (T=$rows, N=$cols)") {
      val (x, y, _) = dataset(rows, cols, 42)
      val model     = pls1Fit(x, y)
      val ref       = pls1Reference(x, y)
      for i <- 0 until rows do
        assertEqualsDouble(model.forecasts(i, 0), ref(x(i)), tol)
    }

    test(s"pls1Fit matches PLS-1 out-of-sample (T=$rows, N=$cols)") {
      val (x, y, testRow) = dataset(rows, cols, 42)
      val model           = pls1Fit(x, y)
      val ref             = pls1Reference(x, y)
      assertEqualsDouble(model.predict(testRow), ref(testRow), tol)
    }

  test("predict on a training row reproduces that row's fitted value") {
    val (x, y, _) = dataset(300, 15, 7)
    val model     = pls1Fit(x, y)
    for i <- 0 until 300 do
      assertEqualsDouble(model.predict(x(i)), model.forecasts(i, 0), tol)
  }

  test("predictAll agrees with predict") {
    val (x, y, _) = dataset(120, 8, 11)
    val model     = pls1Fit(x, y)
    val all       = model.predictAll(x)
    for i <- x.indices do
      assertEqualsDouble(all(i), model.predict(x(i)), tol)
  }

  // The vectorized closed form must agree with the iterative pls path at every
  // shape.  Pass 2 is cross-sectional (N observations) but was gated on minObs,
  // a time-series guard, so the iterative path returned all-NaN for N < 10; the
  // small-N shapes here are the regression test for that fix.
  test("plsClosedForm agrees with estimate3prf(pls = true), including small N") {
    for (rows, cols) <- shapes do
      val (x, y, _) = dataset(rows, cols, 42)
      val matX      = MatD(rows, cols, x.flatten)
      val matY      = MatD(rows, 1, y)
      val closed    = plsClosedForm(matY, matX)
      val iterative = estimate3prf(matY, matX, Left(1), pls = true)
      for i <- 0 until rows do
        assert(!iterative.forecasts(i, 0).isNaN,
          s"estimate3prf(pls = true) returned NaN at T=$rows N=$cols row $i")
        assertEqualsDouble(closed.forecasts(i, 0), iterative.forecasts(i, 0), 1e-8)
  }

  // N=2 with L=1 is the identification floor for the cross-sectional pass-2 fit
  // (L parameters, no intercept, so N > L).
  test("iterative pls path reaches its identification floor at N = 2") {
    val (x, y, _) = dataset(80, 2, 17)
    val matX      = MatD(80, 2, x.flatten)
    val matY      = MatD(80, 1, y)
    val iterative = estimate3prf(matY, matX, Left(1), pls = true)
    val ref       = pls1Reference(x, y)
    for i <- 0 until 80 do
      assertEqualsDouble(iterative.forecasts(i, 0), ref(x(i)), 1e-8)
  }

  // The OOS pls procedures produced all-NaN forecasts before the pass-2 minObs
  // fix.  TprfCoverageSuite only asserted forecasts.shape, so NaN went unnoticed;
  // these assert the values are real.  (The OOS modes leave forecasts NaN for the
  // initial burn-in rows by design, so only the produced region is checked.)
  for procedure <- Seq("OOS Recursive", "OOS Cross Val", "OOS Rolling") do
    test(s"$procedure with pls = true produces finite forecasts") {
      val (x, y, _) = dataset(80, 6, 23)
      val matX      = MatD(80, 6, x.flatten)
      val matY      = MatD(80, 1, y)
      val r = estimate3prf(matY, matX, Left(1), procedure = procedure,
                           mintrain = (20, 0), window = (0, 1),
                           rollwin = (30, 20, 0), pls = true)
      val produced = (0 until 80).map(i => r.forecasts(i, 0)).filterNot(_.isNaN)
      assert(produced.nonEmpty, s"$procedure produced no forecasts at all")
      assert(produced.forall(_.isFinite), s"$procedure produced non-finite forecasts")
      // A degenerate all-zero result would also be "finite" — require spread.
      assert(produced.distinct.length > 1, s"$procedure produced a constant forecast")
    }

  // ── OOS reference ──────────────────────────────────────────────────────────
  //
  // Independent re-derivation of the OOS pls forecast in plain arrays, so the
  // OOS procedures are pinned to values rather than to "finite and varied".
  // The pipeline being reproduced, per forecast index t:
  //
  //   Xn      = X / stdCols(X)              full-sample scale, applied once
  //   window  = the rows of Xn this procedure trains on for t
  //   Xt      = window / stdCols(window)    window scale; scale only, no centering
  //   oos     = Xn(t) / stdCols(window)
  //   forecast = the pls three passes on (Xt, yt) with proxy yt, applied to oos
  //
  // The pls variant drops the intercept in passes 1 and 2, so both reduce to
  // plain projections; pass 3 is OLS with an intercept.

  /** Sample column std devs, ddof = 1; zero or degenerate → 1.0 (matches stdCols). */
  private def stdColsRef(m: Array[Array[Double]]): Array[Double] =
    val n = m.length
    val p = m(0).length
    Array.tabulate(p): j =>
      if n <= 1 then 1.0
      else
        val mu = (0 until n).map(i => m(i)(j)).sum / n
        val ss = (0 until n).map(i => (m(i)(j) - mu) * (m(i)(j) - mu)).sum
        val sd = math.sqrt(ss / (n - 1))
        if sd == 0.0 then 1.0 else sd

  /** One OOS pls forecast: three passes on (xt, yt) with proxy yt, applied to oos.
   *  Pass 1 fits over n observations and pass 2 over p; NaN when either is
   *  under-identified, which is how the library's nanOls gates report it. */
  private def plsForecastRef(
      xt:          Array[Array[Double]],
      yt:          Array[Double],
      oos:         Array[Double],
      pass1MinObs: Int,
  ): Double =
    val n = xt.length
    val p = xt(0).length
    if n < pass1MinObs || p < 2 then Double.NaN
    else
      val cm = Array.tabulate(p)(j => (0 until n).map(i => xt(i)(j)).sum / n)
      val xc = Array.tabulate(n, p)((i, j) => xt(i)(j) - cm(j))
      // pass 1 — no intercept, proxy is yt itself
      val yss = yt.map(v => v * v).sum
      val phi = Array.tabulate(p)(j => (0 until n).map(i => xc(i)(j) * yt(i)).sum / yss)
      // pass 2 — no intercept, design is phi
      val pss   = phi.map(v => v * v).sum
      val sigma = Array.tabulate(n)(i => (0 until p).map(j => xc(i)(j) * phi(j)).sum / pss)
      // pass 3 — OLS of yt on [1, sigma]
      val sbar = sigma.sum / n
      val ybar = yt.sum / n
      val sxy  = (0 until n).map(i => (sigma(i) - sbar) * (yt(i) - ybar)).sum
      val sxx  = (0 until n).map(i => (sigma(i) - sbar) * (sigma(i) - sbar)).sum
      val b1   = if sxx == 0.0 then 0.0 else sxy / sxx
      val b0   = ybar - b1 * sbar
      val sigmaOos = (0 until p).map(j => (oos(j) - cm(j)) * phi(j)).sum / pss
      b0 + b1 * sigmaOos

  /** Reference forecast series for one OOS procedure. The training-row selection
   *  and the pass-1 minObs differ per procedure; both are mirrored from the
   *  corresponding branch of estimate3prf. */
  private def oosReference(
      procedure: String,
      x: Array[Array[Double]],
      y: Array[Double],
      mintrain: (Int, Int),
      window:   (Int, Int),
      rollwin:  (Int, Int, Int),
  ): Array[Double] =
    val nRows = x.length
    val nCols = x(0).length
    val fullStd = stdColsRef(x)
    val xn  = Array.tabulate(nRows, nCols)((i, j) => x(i)(j) / fullStd(j))
    val out = Array.fill(nRows)(Double.NaN)

    def forecastAt(t: Int, keep: Seq[Int], pass1MinObs: Int): Unit =
      if keep.nonEmpty then
        val win  = keep.map(xn).toArray
        val wStd = stdColsRef(win)
        val xt   = win.map(r => Array.tabulate(nCols)(j => r(j) / wStd(j)))
        val oos  = Array.tabulate(nCols)(j => xn(t)(j) / wStd(j))
        out(t) = plsForecastRef(xt, keep.map(y).toArray, oos, pass1MinObs)

    val (minSize, gap)        = mintrain
    val (before, total)       = window
    val (win, minNona, rGap)  = rollwin

    procedure match
      case "OOS Recursive" =>
        // minObs is mintrain._1; training rows are the prefix [0, t-1-gap)
        for t <- (minSize + 1 + gap) until nRows do
          forecastAt(t, 0 until (t - 1 - gap), minSize)
      case "OOS Cross Val" =>
        // minObs defaults to 10 here; the contiguous block [lo, hi) is dropped
        for t <- 0 until nRows do
          val lo = math.max(t - before, 0)
          val hi = math.min(t - before + total, nRows)
          forecastAt(t, (0 until nRows).filterNot(i => i >= lo && i < hi), 10)
      case "OOS Rolling" =>
        // minObs is rollwin._2 (minNonmissing); training rows are [lo, hi)
        for t <- (win + 1 + rGap) until nRows do
          val lo = math.max(t - win - rGap, 0)
          val hi = math.min(t - 1 - rGap, nRows)
          forecastAt(t, lo until hi, minNona)
      case other =>
        fail(s"no reference for procedure '$other'")
    out

  for procedure <- Seq("OOS Recursive", "OOS Cross Val", "OOS Rolling") do
    test(s"$procedure with pls = true matches an independent reference") {
      val mintrain = (20, 0)
      val window   = (0, 1)
      val rollwin  = (30, 20, 0)
      val (x, y, _) = dataset(80, 6, 23)
      val matX = MatD(80, 6, x.flatten)
      val matY = MatD(80, 1, y)
      val r = estimate3prf(matY, matX, Left(1), procedure = procedure,
                           mintrain = mintrain, window = window,
                           rollwin = rollwin, pls = true)
      val ref = oosReference(procedure, x, y, mintrain, window, rollwin)
      var compared = 0
      for i <- 0 until 80 do
        val got  = r.forecasts(i, 0)
        val want = ref(i)
        if want.isNaN then
          assert(got.isNaN, s"row $i: reference says NaN but got $got")
        else
          assertEqualsDouble(got, want, 1e-9)
          compared += 1
      // Guard against a reference that is NaN everywhere trivially "matching".
      assert(compared > 10, s"only $compared rows carried a real forecast")
    }

  test("retains fitted state with the documented shapes") {
    val (x, y, _) = dataset(200, 9, 3)
    val model     = pls1Fit(x, y)
    assertEquals((model.phi.rows, model.phi.cols), (9, 1))
    assertEquals((model.sigma.rows, model.sigma.cols), (200, 1))
    assertEquals((model.beta.rows, model.beta.cols), (2, 1))
    assertEquals((model.colMean.rows, model.colMean.cols), (1, 9))
    assertEquals((model.colStd.rows, model.colStd.cols), (1, 9))
    assert(model.rSquared.isFinite)
  }

  // predict takes a raw row: the scale is carried on the model, unlike
  // Tprf3Result.estimateYhat which silently requires a pre-normalized row.
  test("predict accepts a raw row, not a pre-normalized one") {
    val (x, y, testRow) = dataset(150, 12, 5)
    val model           = pls1Fit(x, y)
    val raw             = model.predict(testRow)
    val preScaled       = model.predict(
      Array.tabulate(12)(j => testRow(j) / model.colStd(0, j)))
    assertEqualsDouble(raw, pls1Reference(x, y)(testRow), tol)
    assert(math.abs(raw - preScaled) > 1e-6,
      "pre-scaling should change the answer; predict must own the normalization")
  }

  test("rejects ragged, mismatched, and NaN input") {
    val (x, y, _) = dataset(40, 5, 1)
    intercept[IllegalArgumentException](pls1Fit(Array(Array(1.0, 2.0), Array(3.0)), Array(1.0, 2.0)))
    intercept[IllegalArgumentException](pls1Fit(x, y.take(10)))
    intercept[IllegalArgumentException](pls1Fit(Array.empty[Array[Double]], Array.empty[Double]))
    val withNan = x.map(_.clone())
    withNan(0)(0) = Double.NaN
    intercept[IllegalArgumentException](pls1Fit(withNan, y))
  }

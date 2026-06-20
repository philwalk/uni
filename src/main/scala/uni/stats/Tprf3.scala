package uni.stats

import uni.data.*
import uni.*
import scala.collection.parallel.CollectionConverters.*

/**
 * Three-Pass Regression Filter (Kelly & Pruitt, 2015)
 *
 * Three entry points:
 *   - tprfClosedForm →  Tprf3Result (IS Full; closed-form projection; normalizes X)
 *   - t3prf          →  Tprf3Result (IS Full; vectorized batch solves; phi/sigma/estimateYhat)
 *   - estimate3prf   →  Tprf3Result (all procedures; OOS modes, autoproxy, avar)
 *
 * Reference: Kelly, Bryan and Seth Pruitt (2015):
 *   "The Three-Pass Regression Filter: A New Approach to Forecasting
 *    Using Many Predictors," Journal of Econometrics.
 */
object Tprf3 {

  // ── Output types ───────────────────────────────────────────────────────────

  case class AvarEstimates(
    alpha:     MatD,   // (N x N) asymptotic covariance of alpha
    forecasts: MatD,   // (T x T) asymptotic covariance of forecasts
  )

  object Tprf3Result {
    def apply(
        forecasts: MatD,
        residuals: MatD,
        rSquared:  Double,
        encnew:    Double = Double.NaN,
        rollfore:  MatD   = MatD.zeros(1, 1),
        X:         MatD   = MatD.zeros(0, 0),
        y:         MatD   = MatD.zeros(0, 0),
        Z:         MatD   = MatD.zeros(0, 0),
        beta3: Option[MatD]          = None,     // (L+1 x 1) pass-3 OLS coefficients
        alpha: Option[MatD]          = None,     // (N x 1) K&P pointests.alpha (IS Full)
        avar:  Option[AvarEstimates] = None,     // asymptotic variance (IS Full + computeAvar)
        phi:   MatD     = MatD.zeros(0, 0),
        sigma: MatD     = MatD.zeros(0, 0),
        phiHat: MatD    = MatD.zeros(0, 0)
    ): Tprf3Result = new Tprf3Result(
      forecasts, residuals, rSquared, encnew,
      rollfore, X, y, Z, beta3, alpha, avar, phi, sigma, phiHat
    )

    /** Compatibility Bridge for test suites.
     * Maps the old TprfFast signature to Tprf3Result.
     */
    def apply(
        X: MatD, y: MatD, Z: MatD,
        phi: MatD, sigma: MatD, betaHat: MatD
    ): Tprf3Result = {
      val Xaug = MatD.hstack(MatD.ones(sigma.rows, 1), sigma) // add intercept
      val yHat = Xaug *@ betaHat
      Tprf3Result(
        forecasts = yHat,
        residuals = y - yHat,
        rSquared  = 0.0, // Tests usually check specific values of phi/sigma/beta
        X = X, y = y, Z = Z,
        phi    = phi,
        sigma  = sigma,
        beta3  = Some(betaHat), // keep intercept column
        alpha  = None,
        phiHat = MatD.zeros(0, 0) // Not used in this manual test mode
      )
    }
  }

  case class Tprf3Result(
    forecasts: MatD,                        // (T x 1) forecast series
    override val residuals: MatD,           // (T x 1) forecast errors
    override val rSquared:  Double,         // R² vs rolling mean (can be negative OOS)
    encnew: Double,
    rollfore: MatD,

    // satisfy TprfResult trait fields
    X: MatD,
    y: MatD,
    Z: MatD,

    beta3: Option[MatD],                    // (L+1 x 1) pass-3 OLS coefficients; set by t3prf / tprfClosedForm
    alpha: Option[MatD],                    // (N x 1) K&P pointests.alpha; set by estimate3prf IS Full
    avar:  Option[AvarEstimates],

    // Model state from IS Full
    override val phi:   MatD,
    override val sigma: MatD,
    phiHat: MatD,
  ) extends TprfResult {
    // K&P: inner `t3prf` calls this `yhat`; outer `estimate3prf` calls this `forecasts`.
    // `forecasts` is the stored field; `y_hat` aliases it for TprfResult trait compatibility.
    def y_hat: MatD = forecasts

    override def coefficients: MatD =
      val b = beta3.getOrElse(MatD.zeros(0, 0))
      if (b.rows > 1) b(1 until b.rows, ::) else b

    /** Preserved: Vectorized OOS prediction logic */
    override def estimateYhat(oos: VecD): Double = {
      if (phi.rows == 0 || coefficients.rows == 0) Double.NaN
      else
        val oosCol = if (oos.rows == 1) oos.T else oos
        val designPhi = withIntercept(phi)

        // Project oos onto factor space
        val b_oos = (designPhi.T *@ designPhi).inverse *@ (designPhi.T *@ oosCol)
        val sigma_oos = b_oos(1 until b_oos.rows, ::)

        // finalDesign is (1 x L+1)
        val finalDesign = MatD.hstack(MatD.ones(1, 1), sigma_oos.T)
        val fullBeta = beta3.get // (L+1 x 1) pass-3 OLS coefficients

        if (finalDesign.cols != fullBeta.rows) Double.NaN
        else (finalDesign *@ fullBeta)(0, 0)
    }

    /** Preserved: Lazy pass-1 R² calculation */
    override lazy val pass1columnsRsquared: Array[Double] =
      if phiHat.rows == 0 then Array.empty
      else
        val dZ       = withIntercept(Z)
        val Xfitted1 = dZ *@ phiHat
        val colMeans = X.sum(0) / X.rows.toDouble
        val rssCols  = ((X - Xfitted1) ~^ 2.0).sum(0)
        val sstCols  = ((X - colMeans) ~^ 2.0).sum(0)
        Array.tabulate(X.cols)(i => if sstCols(0, i) == 0.0 then 0.0 else 1.0 - rssCols(0, i) / sstCols(0, i))

    override def intercept: Double = beta3.map(_(0, 0)).getOrElse(Double.NaN)

    override def toString: String =
      s"Tprf3Result(R²=$rSquared, n=$n, df=$df, residuals=${residuals.shape}, y_hat=${y_hat.shape})"
  }


  // ── TprfResult trait ────────────────────────────────────────────────────────

  trait TprfResult {
    def X: MatD       // design matrix (T×N), normalised to unit-variance columns
    def y: MatD       // response column vector (T×1)
    def Z: MatD       // proxy matrix (T×L)
    def y_hat: MatD   // estimated response (T×1)

    def residuals: MatD = y - y_hat
    lazy val n:         Int  = X.rows
    lazy val df:        Double = Z.cols.toDouble
    lazy val rss:       Double = (residuals ~^ 2.0).sum

    def rSquared: Double =
      val ssy = ((y - y.mean) ~^ 2.0).sum
      if ssy == 0.0 then 0.0 else 1.0 - rss / ssy

    def coefficients: MatD

    /** Pass-1 loadings: (N×L) regression coefficients of each X column on Z. */
    def phi: MatD = MatD.zeros(0, 0)

    /** Pass-2 factors: (T×L) estimated latent factor scores. */
    def sigma: MatD = MatD.zeros(0, 0)

    /** Adjusted R-squared: penalizes for the number of proxies (L). */
    lazy val adjRsq: Double =
      if n <= df + 1 then 0.0
      else 1.0 - (1.0 - rSquared) * (n - 1.0) / (n - df - 1.0)

    /** Per-column R² from pass-1 regressions (length N). */
    def pass1columnsRsquared: Array[Double]; // = Array.empty

    /** OOS prediction for a single held-out x row (N×1). */
    def estimateYhat(oos: VecD): Double //= Double.NaN

    def degreesOfFreedom: Double =
      // J(T)/J(N) products expressed as centering — no dense T×T matrix
      val XtJt = centerColumns(X).T                  // X'·J(T)
      val Wxz  = centerColumns(XtJt *@ Z)            // J(N)·X'·J(T)·Z
      val Sxx  = XtJt *@ X
      val hatX = Wxz *@ (Wxz.T *@ Sxx *@ Wxz).inverse *@ Wxz.T *@ XtJt
      val centered = centerColumns(hatX)
      val (u, _, _) = centered.svd
      val H = u *@ u.T
      H.trace

    def intercept: Double = Double.NaN
  }

  // ── tprfClosedForm ──────────────────────────────────────────────────────────

  /** Closed-form 3PRF (K&P IS Full, algebraic matrix formula).
   *  Collapses the three passes into a single projection; normalizes X internally.
   *  Equivalent in result to t3prf but never iterates — ŷ follows directly from:
   *
   *    α̂ = Wxz(Wxz'SxxWxz)⁻¹Wxz'X'J(T)y
   *    ŷ = J(T)Xα̂
   *
   *  where Wxz = J(N)·X'·J(T)·Z,  Sxx = X'·J(T)·X,  J(k) = I_k − (1/k)·1·1'
   *  Pass-1 (Phi) and pass-2 (Sigma) are still computed for phi/sigma fields. */
  def tprfClosedForm(y: MatD, X: MatD, Z: MatD): Tprf3Result =
    val Xn   = X / nanStdCols(X)
    val T    = Xn.rows; val N = Xn.cols
    // J(T)/J(N) products expressed as centering — no dense T×T matrix
    val JtX  = centerColumns(Xn)                   // J(T)·Xn
    val XtJt = JtX.T                               // Xn'·J(T)
    val Wxz  = centerColumns(XtJt *@ Z)            // J(N)·Xn'·J(T)·Z
    val Sxx  = XtJt *@ Xn
    val alpha_hat_factor: MatD = Wxz *@ (Wxz.T *@ Sxx *@ Wxz).inverse *@ Wxz.T *@ XtJt
    val alpha_hat = alpha_hat_factor *@ y           // N×1
    val y_hat_val = JtX *@ alpha_hat + y.mean       // T×1

    // Pass-1 (K&P: Phi N×L)
    val phiMat     = MatD.zeros(N, Z.cols)
    val phiHatFull = MatD.zeros(Z.cols + 1, N)
    val r2         = Array.ofDim[Double](N)
    for i <- 0 until N do
      val m = Lm(Xn(::, i), Z)
      phiMat(i until i+1, ::) = m.coef_.T
      phiHatFull(::, i until i+1) = m.beta
      val v = m.rSquared
      r2(i) = if isNan(v) || java.lang.Double.isInfinite(v) then 0.0 else v
    val phi = phiMat                                // N×L

    // Pass-2 (K&P: Sigma T×L)
    val sigma = MatD.zeros(T, Z.cols)
    for t <- 0 until T do
      val m = Lm(Xn(t, ::).T, phi, addIntercept = true)
      sigma(t until t+1, ::) = m.coef_.T

    // Pass-3 (K&P: beta = [iota(T) Sigma]\y)
    val Xaug  = withIntercept(sigma)
    val beta3 = (Xaug.T *@ Xaug).inverse *@ (Xaug.T *@ y)  // L+1×1

    // K&P: Szz = Z'·J(T)·Z; beta_hat = Szz⁻¹·Wxz'·alpha_hat (closed-form pass-3 alt)
    val JtZ      = centerColumns(Z)                // J(T)·Z
    val Szz      = Z.T *@ JtZ
    @annotation.unused
    lazy val _beta_hat = Szz.inverse *@ Wxz.T *@ alpha_hat       // L×1, K&P closed-form alt

    val resids = y - y_hat_val
    val ssy    = ((y - y.mean) ~^ 2.0).sum
    val rsq    = if ssy == 0.0 then 0.0 else 1.0 - (resids ~^ 2.0).sum / ssy

    Tprf3Result(
      forecasts = y_hat_val,
      residuals = resids,
      rSquared  = rsq,
      X = Xn, y = y, Z = Z,
      beta3  = Some(beta3),
      phi    = phi,
      sigma  = sigma,
      phiHat = phiHatFull
    )

  // ── t3prf (K&P vectorized) ──────────────────────────────────────────────────

  /** Vectorized 3PRF: K&P t3prf with batch matrix solves replacing N+T OLS loops.
   *  Normalizes X internally (nanStdCols). Produces identical results to tprfClosedForm.
   *  Pass-3: beta = [iota(T) Sigma] \ y,  ŷ = [iota(T) Sigma]·beta
   *  (cf. tprfClosedForm closed form: α̂ = Wxz(Wxz'SxxWxz)⁻¹Wxz'X'J(T)y, ŷ = J(T)Xα̂) */
  def t3prf(y: MatD, X: MatD, Z: MatD): Tprf3Result = {
    val Xn        = X / nanStdCols(X)
    val designZ   = withIntercept(Z)
    val B1        = (designZ.T *@ designZ).inverse *@ (designZ.T *@ Xn)
    val phi       = B1(1 until B1.rows, ::).T        // N×L
    val designPhi = withIntercept(phi)
    val B2        = (designPhi.T *@ designPhi).inverse *@ (designPhi.T *@ Xn.T)
    val sigma     = B2(1 until B2.rows, ::).T         // T×L
    val Xaug      = withIntercept(sigma)
    val beta      = (Xaug.T *@ Xaug).inverse *@ (Xaug.T *@ y)

    val y_hat_val = Xaug *@ beta
    val resids    = y - y_hat_val

    val ssy = ((y - y.mean) ~^ 2.0).sum
    val rsq = if (ssy == 0.0) 0.0 else 1.0 - (resids ~^ 2.0).sum / ssy

    Tprf3Result(
      forecasts = y_hat_val,
      residuals = resids,
      rSquared  = rsq,
      X         = Xn,
      y         = y,
      Z         = Z,
      phi       = phi,
      sigma     = sigma,
      beta3     = Some(beta),
      phiHat    = B1
    )
  }

  // ── NaN-aware OLS ──────────────────────────────────────────────────────────

  /** OLS with silent NaN-row filtering.
   *  Rows where y or any X column is NaN are dropped before fitting.
   *  All downstream members (coef_, rSquared, etc.) reflect the filtered data. */
  class Lm(y: MatD, X: MatD, addIntercept: Boolean = true):
    private val validIdx: Seq[Int] = (0 until X.rows).filter { i =>
      !y(i, 0).isNaN && (0 until X.cols).forall(j => !X(i, j).isNaN)
    }
    private val yf: MatD = if validIdx.length == X.rows then y else selectRows(y, validIdx)
    private val Xf: MatD = if validIdx.length == X.rows then X else selectRows(X, validIdx)
    val nObs:    Int = Xf.rows
    val nCols:   Int = Xf.cols
    private val Xaug: MatD =
      if addIntercept then MatD.hstack(MatD.ones(nObs, 1), Xf) else Xf
    val nParams: Int = Xaug.cols
    private val XtX: MatD   = Xaug.T *@ Xaug
    private val Xty: MatD   = Xaug.T *@ yf
    private[uni] val beta: MatD  = XtX.inverse *@ Xty
    val intercept_ : Double = if addIntercept then beta(0, 0) else 0.0
    val coef_      : MatD   = if addIntercept then beta(1 until nParams, ::) else beta
    private val yHatLm:   MatD = Xaug *@ beta
    private val residsLm: MatD = yf - yHatLm
    val rSquared: Double =
      val rss_ = (residsLm ~^ 2.0).sum
      val ybar = yf.mean
      val ssy  = ((yf - ybar) ~^ 2.0).sum
      if ssy == 0.0 then 0.0 else 1.0 - rss_ / ssy
    val dfResid: Int = nObs - nParams
    val adjRs: Double =
      if dfResid <= 0 || nObs <= 1 then 0.0
      else 1.0 - (1.0 - rSquared) * (nObs - 1.0) / dfResid

  // ── Private helpers ────────────────────────────────────────────────────────

  /** Primitive NaN test. `x.isNaN` on a `Double` routes through `Predef.double2Double`,
   *  which BOXES x into a java.lang.Double just to call its instance `isNaN` — defeating
   *  the unboxed `atD` reads in the hot loops below. The static method takes a primitive. */
  private inline def isNan(x: Double): Boolean = java.lang.Double.isNaN(x)

  /** Extract selected rows into a new MatD (shared by Lm, nanOls, OOS modes). */
  private def selectRows(m: MatD, rows: Seq[Int]): MatD =
    val arr = Array.ofDim[Double](rows.length * m.cols)
    for (r, ri) <- rows.zipWithIndex do
      for c <- 0 until m.cols do
        arr(ri * m.cols + c) = m.atD(r, c)
    Mat.create(arr, rows.length, m.cols)

  /** Centering matrix: I_n − (1/n)·1·1ᵀ */
  // The K&P centering matrix J(k) = I_k − (1/k)·1·1' never needs to be built:
  // J(rows) *@ m subtracts column means and m *@ J(cols) subtracts row means.
  // (The former jMat-based products built a dense T×T matrix and did O(T²·N)
  // work where O(T·N) centering suffices — and dominated IS Full timing.)

  /** Centre each column (subtract column mean): equivalent to J(m.rows) *@ m. */
  private def centerColumns(m: MatD): MatD =
    m - (m.sum(0) / m.rows.toDouble)

  /** Centre each row (subtract row mean): equivalent to m *@ J(m.cols). */
  private def centerRows(m: MatD): MatD =
    m - (m.sum(1) / m.cols.toDouble)

  /** Column std-dev ignoring NaN; returns (1×N). Zero/degenerate columns → 1.
   *  Row-major sweep (i outer, j inner): each column accumulates in the same i=0..rows
   *  order as a column-outer loop, so the result is bit-identical — but memory is touched
   *  sequentially over the row-major backing array instead of striding cols*8 bytes/step. */
  private def nanStdCols(m: MatD): MatD =
    val rows = m.rows; val cols = m.cols
    val n    = Array.ofDim[Int](cols)
    val sum  = Array.ofDim[Double](cols)
    var i = 0
    while i < rows do
      var j = 0
      while j < cols do
        val v = m.atD(i, j)
        if !isNan(v) then { n(j) += 1; sum(j) += v }
        j += 1
      i += 1
    val mu = Array.ofDim[Double](cols)
    var j = 0
    while j < cols do { if n(j) > 1 then mu(j) = sum(j) / n(j); j += 1 }
    val ss = Array.ofDim[Double](cols)
    i = 0
    while i < rows do
      var jj = 0
      while jj < cols do
        val v = m.atD(i, jj)
        if !isNan(v) then { val d = v - mu(jj); ss(jj) += d * d }
        jj += 1
      i += 1
    val arr = Array.ofDim[Double](cols)
    j = 0
    while j < cols do
      arr(j) =
        if n(j) > 1 then
          val sd = math.sqrt(ss(j) / (n(j) - 1))
          if sd == 0.0 then 1.0 else sd
        else 1.0
      j += 1
    Mat.create(arr, 1, cols)

  /** Column means ignoring NaN; returns (1×N). Row-major sweep, bit-identical to the
   *  column-outer form (see nanStdCols). */
  private def nanMeanCols(m: MatD): MatD =
    val rows = m.rows; val cols = m.cols
    val n    = Array.ofDim[Int](cols)
    val sum  = Array.ofDim[Double](cols)
    var i = 0
    while i < rows do
      var j = 0
      while j < cols do
        val v = m.atD(i, j)
        if !isNan(v) then { n(j) += 1; sum(j) += v }
        j += 1
      i += 1
    val arr = Array.ofDim[Double](cols)
    var j = 0
    while j < cols do { arr(j) = if n(j) > 0 then sum(j) / n(j) else Double.NaN; j += 1 }
    Mat.create(arr, 1, cols)

  /** Mean of a column vector ignoring NaN. */
  private def nanMean(v: MatD): Double =
    var n = 0; var sum = 0.0
    var i = 0
    while i < v.rows do
      val x = v.atD(i, 0)
      if !isNan(x) then { n += 1; sum += x }
      i += 1
    if n > 0 then sum / n else Double.NaN

  /** True if any element is NaN. Once-per-call detection lets the OOS hot loops
   *  take the cheaper NaN-free std/mean paths (stdCols/colMean) when possible. */
  private def anyNan(m: MatD): Boolean =
    val rows = m.rows; val cols = m.cols
    var found = false
    var i = 0
    while i < rows && !found do
      var j = 0
      while j < cols && !found do
        if isNan(m.atD(i, j)) then found = true
        j += 1
      i += 1
    found

  /** Column std-dev (1×N). When `hasNan` is false, skips the per-element NaN
   *  test and per-column count of nanStdCols (every column has `rows` obs),
   *  giving bit-identical results via the same accumulation order. */
  private def stdCols(m: MatD, hasNan: Boolean): MatD =
    if hasNan then nanStdCols(m)
    else
      val rows = m.rows; val cols = m.cols
      val sum  = Array.ofDim[Double](cols)
      var i = 0
      while i < rows do
        var j = 0
        while j < cols do { sum(j) += m.atD(i, j); j += 1 }
        i += 1
      val mu = Array.ofDim[Double](cols)
      var j = 0
      while j < cols do { if rows > 1 then mu(j) = sum(j) / rows; j += 1 }
      val ss = Array.ofDim[Double](cols)
      i = 0
      while i < rows do
        var jj = 0
        while jj < cols do { val d = m.atD(i, jj) - mu(jj); ss(jj) += d * d; jj += 1 }
        i += 1
      val arr = Array.ofDim[Double](cols)
      j = 0
      while j < cols do
        arr(j) =
          if rows > 1 then
            val sd = math.sqrt(ss(j) / (rows - 1))
            if sd == 0.0 then 1.0 else sd
          else 1.0
        j += 1
      Mat.create(arr, 1, cols)

  /** Mean of a column vector; NaN-free fast path when `hasNan` is false. */
  private def colMean(v: MatD, hasNan: Boolean): Double =
    if hasNan then nanMean(v)
    else
      val rows = v.rows
      var sum = 0.0; var i = 0
      while i < rows do { sum += v.atD(i, 0); i += 1 }
      if rows > 0 then sum / rows else Double.NaN

  /** Copy all rows of `m` except the contiguous block [lo, hi) (clamped to
   *  [0, rows)). Replaces the OOS Cross Val `setdiff` (Set build + filter) with
   *  a single primitive copy of the kept rows, in order — bit-identical to
   *  selectRows over the setdiff index list. */
  private def dropRows(m: MatD, lo: Int, hi: Int): MatD =
    val rows = m.rows; val cols = m.cols
    val a = math.max(lo, 0); val b = math.min(hi, rows)
    val drop = if b > a then b - a else 0
    val keep = rows - drop
    val arr  = Array.ofDim[Double](keep * cols)
    var ri = 0; var r = 0
    while r < rows do
      if r < a || r >= b then
        var c = 0
        while c < cols do { arr(ri * cols + c) = m.atD(r, c); c += 1 }
        ri += 1
      r += 1
    Mat.create(arr, keep, cols)

  /** Read `m` (which may be a strided slice view) and write a fresh contiguous
   *  matrix with every column divided by `stds(0, j)`. Bit-identical to
   *  `m / stds` — `Mat./` now handles views fine (fastBinOp's strided path) —
   *  but kept as an explicit SEQUENTIAL kernel because this runs inside the OOS
   *  `.par` window loop: `m / stds` would route a large window through the
   *  (to-be-)parallel broadcast path, nesting fork/join inside fork/join. Doing
   *  it sequentially here keeps all the parallelism at the window level. */
  private def normalizeContig(m: MatD, stds: MatD): MatD =
    val rows = m.rows; val cols = m.cols
    // hoist the 1×N std row into a primitive local so the inner loop reads a
    // plain array slot instead of re-dispatching stds.atD(0, j) per element
    val sd = Array.tabulate(cols)(j => stds.atD(0, j))
    val out = Array.ofDim[Double](rows * cols)
    var i = 0
    while i < rows do
      var j = 0
      while j < cols do { out(i * cols + j) = m.atD(i, j) / sd(j); j += 1 }
      i += 1
    Mat.create(out, rows, cols)

  /** Standardize a window of predictors: returns (normalized contiguous window,
   *  column stds). `window` may be a zero-copy `slice` view — its values are
   *  read through stride-safe `atD` in both the std pass and the normalizing
   *  write, so no intermediate copy of the window is materialized. */
  private def standardize(window: MatD, hasNan: Boolean): (MatD, MatD) =
    val stds = stdCols(window, hasNan)
    (normalizeContig(window, stds), stds)

  /** Like `standardize`, but for the OOS Cross Val selection (all rows of `src`
   *  except the contiguous block [lo, hi)). Fuses the row-drop, the column std,
   *  and the normalize into a single pass-set reading `src` directly — so the
   *  intermediate `dropRows` copy is never materialized (one output allocation
   *  instead of two). Bit-identical to `dropRows`→`stdCols`→`/`. The NaN case
   *  is rare here and delegates to the materializing helpers for simplicity. */
  private def standardizeDropRows(src: MatD, lo: Int, hi: Int, hasNan: Boolean): (MatD, MatD) =
    if hasNan then
      val Xt0  = dropRows(src, lo, hi)
      val stds = nanStdCols(Xt0)
      (normalizeContig(Xt0, stds), stds)
    else
      val rows = src.rows; val cols = src.cols
      val a = math.max(lo, 0); val b = math.min(hi, rows)
      val drop = if b > a then b - a else 0
      val keep = rows - drop
      val sum  = Array.ofDim[Double](cols)
      var i = 0
      while i < keep do
        val r = if i < a then i else i + drop   // skip the dropped block
        var j = 0
        while j < cols do { sum(j) += src.atD(r, j); j += 1 }
        i += 1
      val mu = Array.ofDim[Double](cols)
      var j = 0
      while j < cols do { if keep > 1 then mu(j) = sum(j) / keep; j += 1 }
      val ss = Array.ofDim[Double](cols)
      i = 0
      while i < keep do
        val r = if i < a then i else i + drop
        var jj = 0
        while jj < cols do { val d = src.atD(r, jj) - mu(jj); ss(jj) += d * d; jj += 1 }
        i += 1
      val sd = Array.ofDim[Double](cols)
      j = 0
      while j < cols do
        sd(j) =
          if keep > 1 then
            val s = math.sqrt(ss(j) / (keep - 1))
            if s == 0.0 then 1.0 else s
          else 1.0
        j += 1
      val out = Array.ofDim[Double](keep * cols)
      i = 0
      while i < keep do
        val r = if i < a then i else i + drop
        var jj = 0
        while jj < cols do { out(i * cols + jj) = src.atD(r, jj) / sd(jj); jj += 1 }
        i += 1
      (Mat.create(out, keep, cols), Mat.create(sd, 1, cols))

  /** Full-data per-column sufficient statistics: column sums and Σ(x−μ)²
   *  (the latter via the stable two-pass form). Computed once per Cross Val
   *  call and shared (read-only) across the parallel windows so each window's
   *  std becomes an O(drop·N) downdate instead of an O(keep·N) recompute. */
  private def fullColStats(m: MatD): (Array[Double], Array[Double]) =
    val rows = m.rows; val cols = m.cols
    val sum  = Array.ofDim[Double](cols)
    var i = 0
    while i < rows do
      var j = 0
      while j < cols do { sum(j) += m.atD(i, j); j += 1 }
      i += 1
    val mu = Array.ofDim[Double](cols)
    var j = 0
    while j < cols do { mu(j) = if rows > 0 then sum(j) / rows else 0.0; j += 1 }
    val ssd = Array.ofDim[Double](cols)
    i = 0
    while i < rows do
      var jj = 0
      while jj < cols do { val d = m.atD(i, jj) - mu(jj); ssd(jj) += d * d; jj += 1 }
      i += 1
    (sum, ssd)

  /** Incremental variant of `standardizeDropRows` using precomputed full-data
   *  stats. The kept-set std comes from the parallel-axis identity
   *  Σ_kept(x−m_loo)² = (Σ_all(x−μ)² − Σ_drop(x−μ)²) − keep·(m_loo−μ)², i.e. an
   *  O(drop·N) downdate of the dropped block. Numerically this is O(ε) — same
   *  order as the direct two-pass — PROVIDED the kept set is the majority, so
   *  `Σ_all − Σ_drop` doesn't cancel. When the dropped block is large (keep
   *  small) we recompute directly (cheap there anyway), keeping it not-inferior
   *  to the two-pass form in every regime. Not bit-identical (~1e-13 drift). */
  private def standardizeDropRowsInc(
    src: MatD, lo: Int, hi: Int,
    fullSum: Array[Double], fullSsd: Array[Double],
  ): (MatD, MatD) =
    val rows = src.rows; val cols = src.cols
    val a = math.max(lo, 0); val b = math.min(hi, rows)
    val drop = if b > a then b - a else 0
    val keep = rows - drop
    // guard: downdate only when the kept set dominates (cancellation < ~1 bit);
    // otherwise recompute directly — that branch is cheap since keep is small
    if keep < 2 || keep * 2 < rows then standardizeDropRows(src, lo, hi, hasNan = false)
    else
      // dropped-block sufficient stats relative to the full-data column mean
      val dropSum = Array.ofDim[Double](cols)
      val dropSsd = Array.ofDim[Double](cols)
      var r = a
      while r < b do
        var j = 0
        while j < cols do
          val v  = src.atD(r, j)
          val mu = fullSum(j) / rows
          dropSum(j) += v
          val d = v - mu
          dropSsd(j) += d * d
          j += 1
        r += 1
      val sd = Array.ofDim[Double](cols)
      var j = 0
      while j < cols do
        val mu    = fullSum(j) / rows
        val muLoo = (fullSum(j) - dropSum(j)) / keep
        val shift = muLoo - mu
        val ss0   = (fullSsd(j) - dropSsd(j)) - keep.toDouble * shift * shift
        val ss    = if ss0 > 0.0 then ss0 else 0.0       // clamp tiny negative from rounding
        val s     = math.sqrt(ss / (keep - 1))
        sd(j) = if s == 0.0 then 1.0 else s
        j += 1
      val out = Array.ofDim[Double](keep * cols)
      var i = 0
      while i < keep do
        val rr = if i < a then i else i + drop
        var jj = 0
        while jj < cols do { out(i * cols + jj) = src.atD(rr, jj) / sd(jj); jj += 1 }
        i += 1
      (Mat.create(out, keep, cols), Mat.create(sd, 1, cols))

  /** OLS with NaN-row filtering and minObs guard; returns Some(beta) or None.
   *  `hasNan = false` skips the row scan and the selectRows copies entirely —
   *  with no NaNs every row is kept, so the filtered fit equals the direct one. */
  private def nanOls(y: MatD, X: MatD, minObs: Int, hasNan: Boolean): Option[MatD] =
    if !hasNan then
      if y.rows < minObs then None
      else Some((X.T *@ X).inverse *@ (X.T *@ y))
    else
      val validBuf = Array.newBuilder[Int]
      var i = 0
      while i < y.rows do
        if !isNan(y.atD(i, 0)) then
          var allOk = true
          var jj = 0
          while jj < X.cols && allOk do
            if isNan(X.atD(i, jj)) then allOk = false
            jj += 1
          if allOk then validBuf += i
        i += 1
      val valid = validBuf.result()
      if valid.length < minObs then None
      else if valid.length == y.rows then
        Some((X.T *@ X).inverse *@ (X.T *@ y))   // nothing filtered — skip the copies
      else
        val yv  = selectRows(y, valid.toIndexedSeq)
        val Xv  = selectRows(X, valid.toIndexedSeq)
        Some((Xv.T *@ Xv).inverse *@ (Xv.T *@ yv))

  /** Prepend an intercept column: [1 | X]. Writes the augmented matrix into a
   *  single buffer (intercept slot + row copy) instead of allocating a `ones`
   *  column and routing through `hstack`'s Seq-based path. Bit-identical. */
  private def withIntercept(X: MatD): MatD =
    val rows = X.rows; val cols = X.cols
    val w    = cols + 1
    val out  = Array.ofDim[Double](rows * w)
    var i = 0
    while i < rows do
      val base = i * w
      out(base) = 1.0
      var j = 0
      while j < cols do { out(base + 1 + j) = X.atD(i, j); j += 1 }
      i += 1
    Mat.create(out, rows, w)

  private def nanCol(T: Int): MatD =
    // Array.fill boxes each element (by-name generic); fill a primitive array.
    val a = new Array[Double](T)
    java.util.Arrays.fill(a, Double.NaN)
    Mat.create(a, T, 1)

  private def encnew(foreErr1: MatD, foreErr2: MatD): Double =
    val valid = (0 until foreErr1.rows).filter(i =>
      !foreErr1(i, 0).isNaN && !foreErr2(i, 0).isNaN)
    val p  = valid.length.toDouble
    val e1 = valid.map(i => foreErr1(i, 0))
    val e2 = valid.map(i => foreErr2(i, 0))
    p * e1.zip(e2).map((a, b) => a*a - a*b).sum / e2.map(x => x*x).sum

  // ── Factory functions ───────────────────────────────────────────────────────

  // ── Core 3-pass engine (used by estimate3prf) ───────────────────────────────

  /** Vectorized three-pass engine: 2 batch matrix solves replace N+T OLS loops.
   *  No NaN-per-column tolerance in passes 1/2; pass 3 retains nanOls guard. */
  private def t3prfFast(
    y:      MatD,
    X:      MatD,
    Z:      MatD,
    oosX:   Option[MatD],
    minObs: Int,
    hasNan: Boolean,
  ): (MatD, Double) = {
    val T = y.rows
    val L = Z.cols
    if T < minObs then
      (Mat.create(Array.fill(T)(Double.NaN), T, 1), Double.NaN)
    else
      // Pass 1: batch OLS — all N columns of X on Z simultaneously
      val designZ = withIntercept(Z)
      val B1      = (designZ.T *@ designZ).inverse *@ (designZ.T *@ X)
      val Phi     = B1(1 until B1.rows, ::).T          // N×L

      // Pass 2: batch OLS — all T rows of X on Phi simultaneously
      val designPhi = withIntercept(Phi)
      val PtPinv    = (designPhi.T *@ designPhi).inverse
      val B2        = PtPinv *@ (designPhi.T *@ X.T)
      val Sigma     = B2(1 until B2.rows, ::).T         // T×L

      // Pass 3
      val Xaug = withIntercept(Sigma)
      val beta  = nanOls(y, Xaug, minObs = 1, hasNan).getOrElse(
        Mat.create(Array.fill(L + 1)(Double.NaN), L + 1, 1))
      val yhat  = Xaug *@ beta

      // OOS point forecast — reuse PtPinv; xt arrives as (1×N) row, needs (N×1)
      val yhatt = oosX match
        case None => Double.NaN
        case Some(xt) =>
          val b_oos     = PtPinv *@ (designPhi.T *@ xt.T)
          val sigma_oos = b_oos(1 until b_oos.rows, ::)
          (MatD.hstack(MatD.ones(1, 1), sigma_oos.T) *@ beta)(0, 0)

      (yhat, yhatt)
    }

  /** Three-pass OLS with hoisted design matrices and NaN tolerance.
   *  Dispatches to t3prfFast for the non-PLS case (eliminates N+T loops).
   *  @param oosX  (1×N) out-of-sample predictor row, or None */
  private def runT3prf(
    y:      MatD,
    X:      MatD,
    Z:      MatD,
    pls:    Boolean,
    oosX:   Option[MatD] = None,
    minObs: Int          = 10,
    hasNan: Boolean,
  ): (MatD, Double) =
    if !pls then t3prfFast(y, X, Z, oosX, minObs, hasNan)
    else
      val T = y.rows
      val N = X.cols
      val L = Z.cols

      val Phi   = Mat.create(Array.fill(N * L)(Double.NaN), N, L)
      val Sigma = Mat.create(Array.fill(T * L)(Double.NaN), T, L)

      val colMeans  = nanMeanCols(X)
      val xCentered = X - colMeans

      // Pass 1
      val designZ = Z
      for i <- 0 until N do
        nanOls(xCentered(::, i), designZ, minObs, hasNan) match
          case Some(phi) => Phi(i until i+1, ::) = phi.T
          case None      => ()

      // Pass 2
      val designPhi = Phi
      for t <- 0 until T do
        nanOls(xCentered(t, ::).T, designPhi, minObs, hasNan) match
          case Some(sigma) => Sigma(t until t+1, ::) = sigma.T
          case None        => ()

      // Pass 3
      val Xaug = withIntercept(Sigma)
      val beta  = nanOls(y, Xaug, minObs = 1, hasNan).getOrElse(
        Mat.create(Array.fill(L + 1)(Double.NaN), L + 1, 1))
      val yhat  = Xaug *@ beta

      val yhatt = oosX match
        case None => Double.NaN
        case Some(xt) =>
          val xc = xt - colMeans.T
          nanOls(xc.T, designPhi, minObs, hasNan) match
            case None      => Double.NaN
            case Some(sigma) =>
              (MatD.hstack(MatD.ones(1, 1), sigma.T) *@ beta)(0, 0)

      (yhat, yhatt)

  // ── Main API ───────────────────────────────────────────────────────────────

  /**
   * Three-Pass Regression Filter — full estimation.
   *
   * @param y           (T×1) target time series
   * @param X           (T×N) predictor matrix (normalised to unit variance internally)
   * @param Z           Right(matrix T×L) or Left(L: Int) for L auto-proxies
   * @param procedure   "IS Full" | "OOS Recursive" | "OOS Cross Val" | "OOS Rolling"
   * @param window      (before, total) obs dropped for OOS Cross Val
   * @param mintrain    (minSize, gap) for OOS Recursive; negative minSize → T/2
   * @param rollwin     (winSize, minNonmissing, gap) for OOS Rolling
   * @param pls         PLS variant (no intercept in passes 1 & 2; autoproxy only)
   * @param computeAvar compute asymptotic variance (IS Full only)
   */
  def estimate3prf(
    y:           MatD,
    X:           MatD,
    Z:           Either[Int, MatD],
    procedure:   String          = "IS Full",
    window:      (Int, Int)      = (0, 1),
    mintrain:    (Int, Int)      = (-1, 0),
    rollwin:     (Int, Int, Int) = (30, 20, 0),
    pls:         Boolean         = false,
    computeAvar: Boolean         = false,
  ): Tprf3Result =

    val T = y.rows
    val N = X.cols

    val (autoproxy, nProx, zMat) = Z match
      case Left(l)  => (true,  l, None)
      case Right(z) => (false, z.cols, Some(z))
    val l = nProx

    val effPls              = pls && autoproxy
    val mt                  = if mintrain._1 < 0 then (T / 2, mintrain._2) else mintrain
    val (win, minNona, gap) = rollwin

    val hasNan    = anyNan(X) || anyNan(y) || zMat.exists(anyNan)
    val Xstd      = stdCols(X, hasNan)
    val Xn        = X / Xstd
    val forecasts = nanCol(T)
    val rollfore  = nanCol(T)
    var zFinal    = zMat

    procedure match

      case "IS Full" =>
        if autoproxy then
          var r0   = y * 1.0
          var fore = nanCol(T)
          for j <- 0 until l do
            val (f, _) = runT3prf(y, Xn, r0, effPls, hasNan = hasNan)
            if j == l - 1 then zFinal = Some(r0)
            r0   = MatD.hstack(r0, y - f)
            fore = f
          for i <- 0 until T do forecasts(i, 0) = fore(i, 0)
        else
          val (f, _) = runT3prf(y, Xn, zMat.get, effPls, hasNan = hasNan)
          for i <- 0 until T do forecasts(i, 0) = f(i, 0)
          zFinal = zMat

      case "OOS Cross Val" =>
        // precompute full-data column stats once (clean case); each window's std
        // is then an O(drop·N) downdate. NaN case keeps the per-window recompute.
        val cvStats: Option[(Array[Double], Array[Double])] =
          if hasNan then None else Some(fullColStats(Xn))
        (0 until T).toVector.par.foreach { t =>
          // dropped block is contiguous [lo, hi)
          val lo   = math.max(t - window._1, 0)
          val hi   = math.min(t - window._1 + window._2, T)
          val yt   = dropRows(y, lo, hi)
          val sdr  = cvStats match
            case Some((fullSum, fullSsd)) => standardizeDropRowsInc(Xn, lo, hi, fullSum, fullSsd)
            case None                     => standardizeDropRows(Xn, lo, hi, hasNan)
          val Xt   = sdr._1
          val Xts  = sdr._2
          val oos  = Some(Xn(t, ::) / Xts)
          val tmpt =
            if autoproxy then
              var r0 = yt * 1.0; var tp = Double.NaN
              for _ <- 0 until l do
                val (tmp, t2) = runT3prf(yt, Xt, r0, effPls, oos, hasNan = hasNan)
                r0 = MatD.hstack(yt - tmp, r0); tp = t2
              tp
            else
              runT3prf(yt, Xt, dropRows(zMat.get, lo, hi), effPls, oos, hasNan = hasNan)._2
          forecasts(t, 0) = tmpt
          rollfore(t, 0)  = colMean(yt, hasNan)
        }

      case "OOS Recursive" =>
        (mt._1 + 1 + mt._2 until T).toVector.par.foreach { t =>
          // training rows are the contiguous prefix [0, end): Xn.slice is a
          // zero-copy view (offset 0, standard strides); standardize reads it
          // directly and emits a fresh contiguous Xt — no window copy.
          val end = t - 1 - mt._2
          val yt  = y(0 until end, ::)
          val std = standardize(Xn.slice(0 until end, 0 until Xn.cols), hasNan)
          val Xt  = std._1
          val Xts = std._2
          val oos = Some(Xn(t, ::) / Xts)
          val tmpt =
            if autoproxy then
              var r0 = yt * 1.0; var tp = Double.NaN
              for _ <- 0 until l do
                val (tmp, t2) = runT3prf(yt, Xt, r0, effPls, oos, mt._1, hasNan)
                r0 = MatD.hstack(yt - tmp, r0); tp = t2
              tp
            else
              runT3prf(yt, Xt, zMat.get(0 until end, ::), effPls, oos, hasNan = hasNan)._2
          forecasts(t, 0) = tmpt
          rollfore(t, 0)  = colMean(yt, hasNan)
        }

      case "OOS Rolling" =>
        (win + 1 + gap until T).toVector.par.foreach { t =>
          // rolling window is a contiguous index range [lo, hi): slice is a view
          // when wide enough; otherwise the layout guard materializes it (no
          // worse than a copy). standardize emits a fresh contiguous Xt either way.
          val lo  = math.max(t - win - gap, 0)
          val hi  = math.min(t - 1 - gap, T)
          val yt  = y(lo until hi, ::)
          val std = standardize(Xn.slice(lo until hi, 0 until Xn.cols), hasNan)
          val Xt  = std._1
          val Xts = std._2
          val oos = Some(Xn(t, ::) / Xts)
          val tmpt =
            if autoproxy then
              var r0 = yt * 1.0; var tp = Double.NaN
              for _ <- 0 until l do
                val (tmp, t2) = runT3prf(yt, Xt, r0, effPls, oos, minNona, hasNan)
                r0 = MatD.hstack(yt - tmp, r0); tp = t2
              tp
            else
              runT3prf(yt, Xt, zMat.get(lo until hi, ::), effPls, oos, hasNan = hasNan)._2
          forecasts(t, 0) = tmpt
          rollfore(t, 0)  = colMean(yt, hasNan)
        }

      case other =>
        throw IllegalArgumentException(
          s"Unknown procedure '$other'. " +
          "Choose: 'IS Full', 'OOS Recursive', 'OOS Cross Val', 'OOS Rolling'")

    // ── Point estimates ──────────────────────────────────────────────────────
    val residuals = y - forecasts
    val loc     = (0 until T).filter(i => !isNan(residuals.atD(i, 0)))

    // Primitive sums over `loc` via atD — the prior loc.map(...).sum chains boxed
    // every element (Seq[Double] storage + Numeric.plus). idxs avoids Int boxing.
    val idxs  = loc.toArray
    val nLoc  = idxs.length
    val rsq =
      if procedure == "IS Full" then
        var sy = 0.0; var k = 0
        while k < nLoc do { sy += y.atD(idxs(k), 0); k += 1 }
        val mu = sy / nLoc
        var see = 0.0; var syy = 0.0; k = 0
        while k < nLoc do
          val i = idxs(k)
          val e = residuals.atD(i, 0); see += e * e
          val d = y.atD(i, 0) - mu;   syy += d * d
          k += 1
        1.0 - see / syy
      else
        var see = 0.0; var ssT = 0.0; var k = 0
        while k < nLoc do
          val i = idxs(k)
          val e = residuals.atD(i, 0);          see += e * e
          val d = y.atD(i, 0) - rollfore.atD(i, 0); ssT += d * d
          k += 1
        if ssT != 0.0 then 1.0 - see / ssT else Double.NaN

    val encStat =
      if procedure == "OOS Recursive" then
        encnew(selectRows(rollfore, loc), selectRows(residuals, loc))
      else Double.NaN

    val alpha: Option[MatD] =
      if procedure == "IS Full" then
        zFinal.map { zf =>
          // J(T)/J(N) products expressed as centering — the former dense T×T
          // jMat matmul here dominated the whole IS Full timing
          val XtJt = centerColumns(Xn).T                 // Xn'·J(T)
          val Wxz  = centerColumns(XtJt *@ zf)           // J(N)·Xn'·J(T)·zf
          val Sxx  = XtJt *@ Xn
          Wxz *@ (Wxz.T *@ Sxx *@ Wxz).inverse *@ Wxz.T *@ (XtJt *@ y)
        }
      else None

    // ── Asymptotic variance (IS Full only) ───────────────────────────────────
    val avarests: Option[AvarEstimates] =
      if computeAvar && procedure == "IS Full" then
        zFinal.map { zf =>
          // Every J(T)/J(N) factor expressed as centering (see jMat identity note);
          // groupings preserve the original left-to-right evaluation order
          val Xc       = centerColumns(Xn)               // J(T)·Xn      (T×N)
          val Zc       = centerColumns(zf)               // J(T)·zf      (T×L)
          val XtJtZ    = Xc.T *@ zf                      // Xn'·J(T)·zf  (N×L)
          val ZtJtXnJn = centerRows(Zc.T *@ Xn)          // zf'·J(T)·Xn·J(N)  (L×N)
          val XtJtXnJn = centerRows(Xc.T *@ Xn)          // Xn'·J(T)·Xn·J(N)  (N×N)
          val a  = XtJtZ * (1.0 / T)
          val b  = (ZtJtXnJn *@ XtJtXnJn *@ XtJtZ) * (math.pow(T, -3) * math.pow(N, -2))
          val c  = ZtJtXnJn * (1.0 / T / N)
          val omegaA = centerColumns(a) *@ b.inverse *@ c   // J(N)·a·b⁻¹·c
          val Xm = Xn.sum(0) / T.toDouble
          var tmp = MatD.zeros(N, N)
          for ti <- 0 until T do
            val xrow = Xn(ti, ::) - Xm
            tmp = tmp + xrow.T *@ xrow * (math.pow(residuals(ti, 0), 2) / T)
          val alphaAvar = omegaA *@ tmp *@ omegaA.T
          AvarEstimates(
            alpha     = alphaAvar,
            forecasts = Xc *@ alphaAvar *@ Xc.T * math.pow(N, -2))  // J(T)·Xn·αA·Xn'·J(T)
        }
      else None

    Tprf3Result(
      forecasts = forecasts,
      residuals = residuals,
      rSquared = rsq,
      encnew = encStat,
      rollfore = rollfore,
      alpha = alpha,
      avar = avarests,
    )

  /** Forecasts only — simplified wrapper around estimate3prf. */
  def forecast3prf(
    y:         MatD,
    X:         MatD,
    Z:         Either[Int, MatD],
    procedure: String     = "IS Full",
    window:    (Int, Int) = (0, 1),
    mintrain:  (Int, Int) = (-1, 0),
    pls:       Boolean    = false,
  ): MatD =
    estimate3prf(y, X, Z, procedure, window, mintrain, pls = pls).forecasts

  // ── Smoke test ──────────────────────────────────────────────────────────────
  def main(args: Array[String]): Unit =
    Mat.setSeed(42)
    val T = 200; val N = 30; val L = 2
    val X = MatD.randn(T, N)
    val y = MatD.randn(T, 1)
    val Z = MatD.randn(T, L)

    // IS Full
    val rf: Tprf3Result = t3prf(y, X, Z)
    val rc: Tprf3Result = tprfClosedForm(y, X, Z)
    val r3: Tprf3Result = estimate3prf(y, X, Right(Z), procedure = "IS Full")

    printf("t3prf          R²=%.4f  yhat[0]=%.6f  adjR²=%.4f  phi:%dx%d  sigma:%dx%d%n",
      rf.rSquared, rf.y_hat(0, 0), rf.adjRsq, rf.phi.rows, rf.phi.cols, rf.sigma.rows, rf.sigma.cols)
    printf("tprfClosedForm R²=%.4f  yhat[0]=%.6f%n", rc.rSquared, rc.y_hat(0, 0))
    printf("estimate3prf   R²=%.4f  yhat[0]=%.6f%n", r3.rSquared, r3.forecasts(0, 0))

    // Autoproxy IS Full
    val r4 = estimate3prf(y, X, Left(2), procedure = "IS Full")
    printf("Autoproxy   R²=%.4f  yhat[0]=%.6f%n", r4.rSquared, r4.forecasts(0, 0))

    // OOS Recursive
    val r5 = estimate3prf(y, X, Right(Z), procedure = "OOS Recursive", mintrain = (100, 0))
    val nFore = (0 until T).count(i => !r5.forecasts(i, 0).isNaN)
    printf("OOS Rec     R²=%.4f  n_forecasts=%d%n", r5.rSquared, nFore)
}

package uni.stats

import uni.data.*
import uni.data.Mat.*
import uni.*
import scala.collection.parallel.CollectionConverters.*

/**
 * Three-Pass Regression Filter (Kelly & Pruitt, 2015)
 *
 * Two entry points:
 *   - tprfFast    →  TprfFast    (IS Full; vectorized batch solves; phi/sigma/estimateYhat)
 *   - estimate3prf →  Tprf3Result (all procedures; OOS modes, autoproxy, avar)
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
    // Custom apply method to hide the private name `_residuals` from client code
    def apply(
        forecasts: MatD,
        residuals: MatD, 
        rSquared:  Double,
        encnew:    Double = Double.NaN,
        rollfore:  MatD   = MatD.zeros(1, 1),
        X:         MatD   = MatD.zeros(0, 0),
        y:         MatD   = MatD.zeros(0, 0),
        Z:         MatD   = MatD.zeros(0, 0),
        alpha: Option[MatD]          = None,     // (N x 1) IS Full predictor coefficients
        avar:  Option[AvarEstimates] = None,     // asymptotic variance (IS Full + computeAvar)
        phi:   MatD     = MatD.zeros(0, 0),
        sigma: MatD     = MatD.zeros(0, 0),
        phiHat: MatD    = MatD.zeros(0, 0)
    ): Tprf3Result = new Tprf3Result(
      forecasts, residuals, rSquared, encnew, 
      rollfore, X, y, Z, alpha, avar, phi, sigma, phiHat
    )

    /** * Compatibility Bridge for test suites.
     * Maps the old TprfFast signature to Tprf3Result.
     */
    def apply(
        X: MatD, y: MatD, Z: MatD, 
        phi: MatD, sigma: MatD, betaHat: MatD
    ): Tprf3Result = {
      val Xaug = MatD.hstack(MatD.ones(sigma.rows, 1), sigma) // add intercept
      val yHat = Xaug ~@ betaHat
      Tprf3Result(
        forecasts = yHat,
        residuals = y - yHat,
        rSquared  = 0.0, // Tests usually check specific values of phi/sigma/beta
        X = X, y = y, Z = Z,
        phi = phi,
        sigma = sigma,
        alpha = Some(betaHat), // keep intercept column
        phiHat = MatD.zeros(0, 0) // Not used in this manual test mode
      )
    }
  }

  case class Tprf3Result(
    forecasts: MatD,                        // (T x 1) forecast series 
    private val _residuals: MatD,           // (T x 1) forecast errors
    override val rSquared:  Double,         // R² vs rolling mean (can be negative OOS)
    encnew: Double,
    rollfore: MatD,

    // satisfy TprfResult trait fields
    X: MatD,
    y: MatD,
    Z: MatD,

    alpha: Option[MatD],
    avar:  Option[AvarEstimates],

    // Model state from IS Full
    override val phi:   MatD,
    override val sigma: MatD,
    phiHat: MatD,
  ) extends TprfResult {
    // satisfy the trait's lazy requirement here
    override lazy val residuals: MatD = _residuals

    def y_hat: MatD = forecasts
    //def coefficients: MatD = alpha.getOrElse(MatD.zeros(0, 0))
    override def coefficients: MatD = 
      val a = alpha.getOrElse(MatD.zeros(0, 0))
      if (a.rows > 1) a(1 until a.rows, ::) else a

    /** Preserved: Vectorized OOS prediction logic */
    override def estimateYhat(oos: VecD): Double = {
      if (phi.rows == 0 || coefficients.rows == 0) Double.NaN
      else
        val oosCol = if (oos.rows == 1) oos.T else oos
        val designPhi = withIntercept(phi)
        
        // Project oos onto factor space
        val b_oos = (designPhi.T ~@ designPhi).inverse ~@ (designPhi.T ~@ oosCol)
        val sigma_oos = b_oos(1 until b_oos.rows, ::)
        
        // finalDesign is (1 x L+1)
        val finalDesign = MatD.hstack(MatD.ones(1, 1), sigma_oos.T)
        // Use the internal alpha (L+1 x 1) directly to match finalDesign's columns
        val fullBeta = alpha.get // get full coefficients
        
        // coefficients is now (L+1 x 1) because it includes the intercept
        if (finalDesign.cols != fullBeta.rows) Double.NaN 
        else (finalDesign ~@ fullBeta)(0, 0)
    }

    /** Preserved: Lazy pass-1 R² calculation */
    override lazy val pass1columnsRsquared: Array[Double] =
      if phiHat.rows == 0 then Array.empty
      else
        val dZ       = withIntercept(Z)
        val Xfitted1 = dZ ~@ phiHat
        val colMeans = X.sum(0) / X.rows.toDouble
        val rssCols  = ((X - Xfitted1) ~^ 2.0).sum(0)
        val sstCols  = ((X - colMeans) ~^ 2.0).sum(0)
        Array.tabulate(X.cols)(i => if sstCols(0, i) == 0.0 then 0.0 else 1.0 - rssCols(0, i) / sstCols(0, i))

    override def intercept: Double = alpha.map(_(0, 0)).getOrElse(Double.NaN)

    override def toString: String =
      s"Tprf3Result(R²=$rSquared, n=$n, df=$df, residuals=${residuals.shape}, y_hat=${y_hat.shape})"
  }


  // ── TprfResult trait ────────────────────────────────────────────────────────

  trait TprfResult {
    def X: MatD       // design matrix (T×N), normalised to unit-variance columns
    def y: MatD       // response column vector (T×1)
    def Z: MatD       // proxy matrix (T×L)
    def y_hat: MatD   // estimated response (T×1)

    lazy val residuals: MatD = y - y_hat
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
      val Jt   = jMat(X.rows); val Jn = jMat(X.cols)
      val XtJt = X.T ~@ Jt
      val Wxz  = Jn ~@ XtJt ~@ Z
      val Sxx  = XtJt ~@ X
      val hatX = Wxz ~@ (Wxz.T ~@ Sxx ~@ Wxz).inverse ~@ Wxz.T ~@ XtJt
      val centered = centerColumns(hatX)
      val (u, _, _) = centered.svd
      val H = u ~@ u.T
      H.trace

    def intercept: Double = Double.NaN
  }

  // ── TprfDirect ─────────────────────────────────────────────────────────────

  /** Closed-form 3PRF estimator. Fastest for IS Full; phi/sigma computed lazily. */
  case class TprfDirect(X: MatD, y: MatD, Z: MatD) extends TprfResult:
    assert(X.cols >= Z.cols, s"X.cols: ${X.cols} < Z.cols: ${Z.cols}")

    private val T = X.rows
    private val N = X.cols
    private val Jt:  MatD = jMat(T)
    private val Jn:  MatD = jMat(N)
    private val XtJt: MatD = X.T ~@ Jt
    private val JtX:  MatD = Jt ~@ X
    private val Wxz:  MatD = Jn ~@ XtJt ~@ Z
    private val Sxx:  MatD = XtJt ~@ X

    private lazy val JtZ: MatD = Jt ~@ Z
    private lazy val Szz: MatD = Z.T ~@ JtZ

    val alpha_hat_factor: MatD =
      Wxz ~@ (Wxz.T ~@ Sxx ~@ Wxz).inverse ~@ Wxz.T ~@ XtJt

    private val alpha_hat: MatD = alpha_hat_factor ~@ y   // (N, 1)

    private[uni] lazy val beta_hat: MatD =
      Szz.inverse ~@ Wxz.T ~@ alpha_hat

    private[uni] lazy val betaPass3: MatD =
      val Xaug = withIntercept(sigma)              // T×(L+1)
      (Xaug.T ~@ Xaug).inverse ~@ (Xaug.T ~@ y)  // (L+1)×1

    private def calcYhat(yvec: VecD): MatD =
      JtX ~@ (alpha_hat_factor ~@ yvec) + yvec.mean

    // prediction formula for a new observation:
    def estimateYhat(oos: VecD): Double =
      // ensure oos is a column vector (N x 1)
      val oosCol = if (oos.rows == 1) oos.T else oos
      // alpha_hat is already (N x 1), so alpha_hat.T is (1 x N)
      val pred = (alpha_hat.T ~@ oosCol)(0, 0) + y.mean
      pred

    val y_hat: MatD = calcYhat(y)

    // Pass-1 and pass-2 matrices, computed lazily via a single shared pass-1 sweep.
    private lazy val _pass1: (MatD, Array[Double], MatD) =
      val phiMat = MatD.zeros(N, Z.cols)
      val phiHatFull = MatD.zeros(Z.cols + 1, N) // L+1 x N
      val r2     = Array.ofDim[Double](N)
      for i <- 0 until N do
        val m  = Lm(X(::, i), Z)
        phiMat(i until i+1, ::) = m.coef_.T
        phiHatFull(::, i until i+1) = m.beta // added for Tprf3Result
        val v  = m.rSquared
        r2(i)  = if v.isNaN || v.isInfinite then 0.0 else v
      (phiMat, r2, phiHatFull)

    // Ensure it returns (L x 1)
    override def coefficients: MatD = beta_hat
    override def phi: MatD                           = _pass1._1
    override def pass1columnsRsquared: Array[Double] = _pass1._2
    def phiHatFull: MatD                    = _pass1._3 // extra for trait

    override val sigma: MatD =
      val result = MatD.zeros(T, Z.cols)
      for t <- 0 until T do
        val m = Lm(X(t, ::).T, phi, addIntercept = true)
        result(t until t+1, ::) = m.coef_.T
      result

    override def toString: String =
      "residuals: %s\ny_hat: %s\n".format(residuals, y_hat)

  object TprfDirect:
    def apply(X: MatD, y: MatD, Z: MatD): Tprf3Result =
      val impl = new TprfDirect(X, y, Z)
      Tprf3Result(
        forecasts = impl.y_hat,
        residuals = impl.residuals,
        rSquared  = impl.rSquared,
        X         = impl.X,
        y         = impl.y,
        Z         = impl.Z,
        alpha     = Some(impl.betaPass3),
        phi       = impl.phi,
        sigma     = impl.sigma,
        phiHat    = impl.phiHatFull
      )

  // ── TprfFast ──────────────────────────────────────────────────────────────

  /** Vectorized 3PRF: replaces N+T OLS loops with 2 batch matrix solves.
   *  phi/sigma available immediately; estimateYhat supported. */

  /** IS Full via vectorized matrix solves. Replaces N+T OLS loops with 2 batch solves.
   *  phi/sigma/pass1R²/adjRsq available immediately; estimateYhat supported.
   * Returns the unified Tprf3Result (same as all 3prf code)
   */
  def tprfFast(X: MatD, y: MatD, Z: MatD): Tprf3Result = {
    val Xstd      = stdcols(X)
    val Xn        = X / Xstd
    val designZ   = withIntercept(Z)
    val B1        = (designZ.T ~@ designZ).inverse ~@ (designZ.T ~@ Xn)
    val phi       = B1(1 until B1.rows, ::).T        // N×L
    val designPhi = withIntercept(phi)
    val B2        = (designPhi.T ~@ designPhi).inverse ~@ (designPhi.T ~@ Xn.T)
    val sigma     = B2(1 until B2.rows, ::).T         // T×L
    val Xaug      = withIntercept(sigma)
    val beta      = (Xaug.T ~@ Xaug).inverse ~@ (Xaug.T ~@ y)
    
    val y_hat_val = Xaug ~@ beta
    val resids    = y - y_hat_val
    
    // Calculate R-squared manually for the constructor
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
      alpha     = Some(beta),
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
    private val XtX: MatD   = Xaug.T ~@ Xaug
    private val Xty: MatD   = Xaug.T ~@ yf
    private[uni] val beta: MatD  = XtX.inverse ~@ Xty
    val intercept_ : Double = if addIntercept then beta(0, 0) else 0.0
    val coef_      : MatD   = if addIntercept then beta(1 until nParams, ::) else beta
    private val yHatLm:   MatD = Xaug ~@ beta
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

  /** Extract selected rows into a new MatD (shared by Lm, nanOls, OOS modes). */
  private def selectRows(m: MatD, rows: Seq[Int]): MatD =
    val arr = Array.ofDim[Double](rows.length * m.cols)
    for (r, ri) <- rows.zipWithIndex do
      for c <- 0 until m.cols do
        arr(ri * m.cols + c) = m(r, c)
    Mat.create(arr, rows.length, m.cols)

  /** Centering matrix: I_n − (1/n)·1·1ᵀ */
  private def jMat(n: Int): MatD =
    MatD.eye(n) - MatD.ones(n, n) * (1.0 / n)

  /** Centre each column (subtract column mean). */
  private def centerColumns(m: MatD): MatD =
    m - (m.sum(0) / m.rows.toDouble)

  /** Column std-dev (sample, no NaN handling); returns (1×N). Zero columns → 1. */
  private def stdcols(m: MatD): MatD =
    val colMeans = m.sum(0) / m.rows.toDouble
    val centered = m - colMeans
    val s = ((centered ~^ 2.0).sum(0) / (m.rows - 1).toDouble).sqrt
    val arr = new Array[Double](s.cols)
    var j = 0
    while j < s.cols do
      arr(j) = if s(0, j) == 0.0 then 1.0 else s(0, j)
      j += 1
    Mat.create(arr, 1, s.cols)

  /** Column std-dev ignoring NaN; returns (1×N). Zero/degenerate columns → 1. */
  private def nanStdCols(m: MatD): MatD =
    val arr = Array.ofDim[Double](m.cols)
    for j <- 0 until m.cols do
      val vals = (0 until m.rows).collect { case i if !m(i, j).isNaN => m(i, j) }
      arr(j) =
        if vals.length > 1 then
          val mu = vals.sum / vals.length
          val sd = math.sqrt(vals.map(x => (x - mu) * (x - mu)).sum / (vals.length - 1))
          if sd == 0.0 then 1.0 else sd
        else 1.0
    Mat.create(arr, 1, m.cols)

  /** Column means ignoring NaN; returns (1×N). */
  private def nanMeanCols(m: MatD): MatD =
    val arr = Array.ofDim[Double](m.cols)
    for j <- 0 until m.cols do
      val vals = (0 until m.rows).collect { case i if !m(i, j).isNaN => m(i, j) }
      arr(j) = if vals.nonEmpty then vals.sum / vals.length else Double.NaN
    Mat.create(arr, 1, m.cols)

  /** Mean of a column vector ignoring NaN. */
  private def nanMean(v: MatD): Double =
    val vals = (0 until v.rows).collect { case i if !v(i, 0).isNaN => v(i, 0) }
    if vals.nonEmpty then vals.sum / vals.length else Double.NaN

  /** OLS with NaN-row filtering and minObs guard; returns Some(beta) or None. */
  private def nanOls(y: MatD, X: MatD, minObs: Int): Option[MatD] =
    val valid = (0 until y.rows).filter { i =>
      !y(i, 0).isNaN && (0 until X.cols).forall(j => !X(i, j).isNaN)
    }
    if valid.length < minObs then None
    else
      val yv  = selectRows(y, valid)
      val Xv  = selectRows(X, valid)
      Some((Xv.T ~@ Xv).inverse ~@ (Xv.T ~@ yv))

  private inline def withIntercept(X: MatD): MatD = MatD.hstack(MatD.ones(X.rows, 1), X)

  private def setdiff(T: Int, drop: Seq[Int]): Seq[Int] =
    val s = drop.toSet; (0 until T).filterNot(s.contains)

  private def nanCol(T: Int): MatD =
    Mat.create(Array.fill(T)(Double.NaN), T, 1)

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
  ): (MatD, Double) = {
    val T = y.rows
    val L = Z.cols
    if T < minObs then
      (Mat.create(Array.fill(T)(Double.NaN), T, 1), Double.NaN)
    else
      // Pass 1: batch OLS — all N columns of X on Z simultaneously
      val designZ = withIntercept(Z)
      val B1      = (designZ.T ~@ designZ).inverse ~@ (designZ.T ~@ X)
      val Phi     = B1(1 until B1.rows, ::).T          // N×L

      // Pass 2: batch OLS — all T rows of X on Phi simultaneously
      val designPhi = withIntercept(Phi)
      val PtPinv    = (designPhi.T ~@ designPhi).inverse
      val B2        = PtPinv ~@ (designPhi.T ~@ X.T)
      val Sigma     = B2(1 until B2.rows, ::).T         // T×L

      // Pass 3
      val Xaug = withIntercept(Sigma)
      val beta  = nanOls(y, Xaug, minObs = 1).getOrElse(
        Mat.create(Array.fill(L + 1)(Double.NaN), L + 1, 1))
      val yhat  = Xaug ~@ beta

      // OOS point forecast — reuse PtPinv; xt arrives as (1×N) row, needs (N×1)
      val yhatt = oosX match
        case None => Double.NaN
        case Some(xt) =>
          val b_oos     = PtPinv ~@ (designPhi.T ~@ xt.T)
          val sigma_oos = b_oos(1 until b_oos.rows, ::)
          (MatD.hstack(MatD.ones(1, 1), sigma_oos.T) ~@ beta)(0, 0)

      (yhat, yhatt)
    }

  /** Three-pass OLS with hoisted design matrices and NaN tolerance.
   *  Dispatches to t3prfFast for the non-PLS case (eliminates N+T loops).
   *  @param oosX  (1×N) out-of-sample predictor row, or None */
  private def t3prf(
    y:      MatD,
    X:      MatD,
    Z:      MatD,
    pls:    Boolean,
    oosX:   Option[MatD] = None,
    minObs: Int          = 10,
  ): (MatD, Double) =
    if !pls then t3prfFast(y, X, Z, oosX, minObs)
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
        nanOls(xCentered(::, i), designZ, minObs) match
          case Some(phi) => Phi(i until i+1, ::) = phi.T
          case None      => ()

      // Pass 2
      val designPhi = Phi
      for t <- 0 until T do
        nanOls(xCentered(t, ::).T, designPhi, minObs) match
          case Some(sigma) => Sigma(t until t+1, ::) = sigma.T
          case None        => ()

      // Pass 3
      val Xaug = withIntercept(Sigma)
      val beta  = nanOls(y, Xaug, minObs = 1).getOrElse(
        Mat.create(Array.fill(L + 1)(Double.NaN), L + 1, 1))
      val yhat  = Xaug ~@ beta

      val yhatt = oosX match
        case None => Double.NaN
        case Some(xt) =>
          val xc = xt - colMeans.T
          nanOls(xc.T, designPhi, minObs) match
            case None      => Double.NaN
            case Some(sigma) =>
              (MatD.hstack(MatD.ones(1, 1), sigma.T) ~@ beta)(0, 0)

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

    val Xstd      = nanStdCols(X)
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
            val (f, _) = t3prf(y, Xn, r0, effPls)
            if j == l - 1 then zFinal = Some(r0)
            r0   = MatD.hstack(r0, y - f)
            fore = f
          for i <- 0 until T do forecasts(i, 0) = fore(i, 0)
        else
          val (f, _) = t3prf(y, Xn, zMat.get, effPls)
          for i <- 0 until T do forecasts(i, 0) = f(i, 0)
          zFinal = zMat

      case "OOS Cross Val" =>
        (0 until T).toVector.par.foreach { t =>
          val drop = (t - window._1 until t - window._1 + window._2).filter(i => i >= 0 && i < T)
          val ts   = setdiff(T, drop)
          val yt   = selectRows(y, ts)
          val Xt0  = selectRows(Xn, ts)
          val Xts  = nanStdCols(Xt0)
          val Xt   = Xt0 / Xts
          val oos  = Some(Xn(t, ::) / Xts)
          val tmpt =
            if autoproxy then
              var r0 = yt * 1.0; var tp = Double.NaN
              for _ <- 0 until l do
                val (tmp, t2) = t3prf(yt, Xt, r0, effPls, oos)
                r0 = MatD.hstack(yt - tmp, r0); tp = t2
              tp
            else
              t3prf(yt, Xt, selectRows(zMat.get, ts), effPls, oos)._2
          forecasts(t, 0) = tmpt
          rollfore(t, 0)  = nanMean(yt)
        }

      case "OOS Recursive" =>
        (mt._1 + 1 + mt._2 until T).toVector.par.foreach { t =>
          val ts  = (0 until t - 1 - mt._2).toSeq
          val yt  = selectRows(y, ts)
          val Xt0 = selectRows(Xn, ts)
          val Xts = nanStdCols(Xt0)
          val Xt  = Xt0 / Xts
          val oos = Some(Xn(t, ::) / Xts)
          val tmpt =
            if autoproxy then
              var r0 = yt * 1.0; var tp = Double.NaN
              for _ <- 0 until l do
                val (tmp, t2) = t3prf(yt, Xt, r0, effPls, oos, mt._1)
                r0 = MatD.hstack(yt - tmp, r0); tp = t2
              tp
            else
              t3prf(yt, Xt, selectRows(zMat.get, ts), effPls, oos)._2
          forecasts(t, 0) = tmpt
          rollfore(t, 0)  = nanMean(yt)
        }

      case "OOS Rolling" =>
        (win + 1 + gap until T).toVector.par.foreach { t =>
          val ts0 = (t - win - gap until t - 1 - gap).filter(i => i >= 0 && i < T)
          val yt  = selectRows(y, ts0)
          val Xt0 = selectRows(Xn, ts0)
          val Xts = nanStdCols(Xt0)
          val Xt  = Xt0 / Xts
          val oos = Some(Xn(t, ::) / Xts)
          val tmpt =
            if autoproxy then
              var r0 = yt * 1.0; var tp = Double.NaN
              for _ <- 0 until l do
                val (tmp, t2) = t3prf(yt, Xt, r0, effPls, oos, minNona)
                r0 = MatD.hstack(yt - tmp, r0); tp = t2
              tp
            else
              t3prf(yt, Xt, selectRows(zMat.get, ts0), effPls, oos)._2
          forecasts(t, 0) = tmpt
          rollfore(t, 0)  = nanMean(yt)
        }

      case other =>
        throw IllegalArgumentException(
          s"Unknown procedure '$other'. " +
          "Choose: 'IS Full', 'OOS Recursive', 'OOS Cross Val', 'OOS Rolling'")

    // ── Point estimates ──────────────────────────────────────────────────────
    val residuals = y - forecasts
    val loc     = (0 until T).filter(i => !residuals(i, 0).isNaN)

    val rsq =
      if procedure == "IS Full" then
        val fe = loc.map(i => residuals(i, 0))
        val yv = loc.map(i => y(i, 0))
        val mu = yv.sum / yv.length
        1.0 - fe.map(e => e*e).sum / yv.map(v => (v-mu)*(v-mu)).sum
      else
        val fe  = loc.map(i => residuals(i, 0))
        val yv  = loc.map(i => y(i, 0))
        val rfv = loc.map(i => rollfore(i, 0))
        val ssT = yv.zip(rfv).map((yi, ri) => (yi - ri)*(yi - ri)).sum
        if ssT != 0.0 then 1.0 - fe.map(e => e*e).sum / ssT else Double.NaN

    val encStat =
      if procedure == "OOS Recursive" then
        encnew(selectRows(rollfore, loc), selectRows(residuals, loc))
      else Double.NaN

    val alpha: Option[MatD] =
      if procedure == "IS Full" then
        zFinal.map { zf =>
          val jt   = jMat(T)
          val jn   = jMat(N)
          val XtJt = Xn.T ~@ jt
          val Wxz  = jn ~@ XtJt ~@ zf
          val Sxx  = XtJt ~@ Xn
          Wxz ~@ (Wxz.T ~@ Sxx ~@ Wxz).inverse ~@ Wxz.T ~@ XtJt ~@ y
        }
      else None

    // ── Asymptotic variance (IS Full only) ───────────────────────────────────
    val avarests: Option[AvarEstimates] =
      if computeAvar && procedure == "IS Full" then
        zFinal.map { zf =>
          val jt = jMat(T); val jn = jMat(N)
          val a  = Xn.T ~@ jt ~@ zf * (1.0 / T)
          val b  = (zf.T ~@ jt ~@ Xn ~@ jn ~@ Xn.T ~@ jt ~@ Xn ~@
                    jn ~@ Xn.T ~@ jt ~@ zf) * (math.pow(T, -3) * math.pow(N, -2))
          val c  = zf.T ~@ jt ~@ Xn ~@ jn * (1.0 / T / N)
          val omegaA = jn ~@ a ~@ b.inverse ~@ c
          val Xm = Xn.sum(0) / T.toDouble
          var tmp = MatD.zeros(N, N)
          for ti <- 0 until T do
            val xrow = Xn(ti, ::) - Xm
            tmp = tmp + xrow.T ~@ xrow * (math.pow(residuals(ti, 0), 2) / T)
          val alphaAvar = omegaA ~@ tmp ~@ omegaA.T
          AvarEstimates(
            alpha     = alphaAvar,
            forecasts = jt ~@ Xn ~@ alphaAvar ~@ Xn.T ~@ jt * math.pow(N, -2))
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
    val rf: Tprf3Result = tprfFast(X, y, Z)
    val r3: Tprf3Result = estimate3prf(y, X, Right(Z), procedure = "IS Full")

    printf("tprfFast     R²=%.4f  yhat[0]=%.6f  adjR²=%.4f  phi:%dx%d  sigma:%dx%d%n",
      rf.rSquared, rf.y_hat(0, 0), rf.adjRsq, rf.phi.rows, rf.phi.cols, rf.sigma.rows, rf.sigma.cols)
    printf("estimate3prf R²=%.4f  yhat[0]=%.6f%n", r3.rSquared, r3.forecasts(0, 0))

    // Autoproxy IS Full
    val r4 = estimate3prf(y, X, Left(2), procedure = "IS Full")
    printf("Autoproxy   R²=%.4f  yhat[0]=%.6f%n", r4.rSquared, r4.forecasts(0, 0))

    // OOS Recursive
    val r5 = estimate3prf(y, X, Right(Z), procedure = "OOS Recursive", mintrain = (100, 0))
    val nFore = (0 until T).count(i => !r5.forecasts(i, 0).isNaN)
    printf("OOS Rec     R²=%.4f  n_forecasts=%d%n", r5.rSquared, nFore)
}

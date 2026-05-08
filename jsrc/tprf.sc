#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
//package apps

//> using dep org.vastblue:uni_3:0.13.2

import uni.data.*
import uni.data.Mat.*
import uni.*
import java.io.PrintWriter

object Tprf {
  private var hook = 0

  def main(args: Array[String]): Unit = {
    Mat.setSeed(0)
    try {
      val (rows, cols, num_proxies) = (650, 40, 2)
      val X: MatD = MatD.rand(rows, cols)
      val y: MatD = MatD.rand(rows, 1)
      val Z: MatD = autoproxy(X, y, num_proxies)

      val result1: TprfResult = tprfDirect(X, y, Z)
      val result2: TprfResult = tprfLooped(X, y, Z)
      printf("direct: %s\n", result1.toString)
      printf("looped: %s\n", result2.toString)
      // same result
      assert(result1.toString == result2.toString)

      val secsDirect = reportTime(rows / 30) {
        tprfDirect(X, y, Z)
      }
      printf("direct seconds: %s\n", secsDirect)
      val secsLooped = reportTime(rows / 30) {
        tprfLooped(X, y, Z)
      }
      printf("looped seconds: %s\n", secsLooped)

      hook += 1
    } catch {
      case t: Throwable =>
        t.printStackTrace()
        sys.exit(1)
    }
  }

  extension (m: MatD)
    def shapes: String = s"(${m.rows}, ${m.cols})"

  // Ordinary least-squares linear regression.
  // y: response (n×1), X: design matrix (n×p), addIntercept: prepend column of ones.
  class Lm(y: MatD, X: MatD, addIntercept: Boolean = true):
    val nObs   = X.rows
    val nCols  = X.cols
    val Xaug: MatD =
      if addIntercept then MatD.hstack(MatD.ones(nObs, 1), X)
      else X
    val nParams = Xaug.cols
    private val XtX: MatD  = Xaug.T *@ Xaug
    private val Xty: MatD  = Xaug.T *@ y
    private val beta: MatD = XtX.inverse *@ Xty
    val intercept_ : Double = if addIntercept then beta(0, 0) else 0.0
    val coef_      : MatD   = if addIntercept then beta(1 until nParams, ::) else beta
    private val yHatLm: MatD    = Xaug *@ beta
    private val residsLm: MatD  = y - yHatLm
    val rSquared: Double =
      val rss_ = (residsLm ~^ 2.0).sum
      val ybar = y.mean
      val ssy  = ((y - ybar) ~^ 2.0).sum
      if ssy == 0.0 then 0.0 else 1.0 - rss_ / ssy
    val dfResid: Int = nObs - nParams
    val adjRs: Double =
      if dfResid <= 0 || nObs <= 1 then 0.0
      else 1.0 - (1.0 - rSquared) * (nObs - 1.0) / dfResid
    // p-values not computed (placeholder zeros)
    val p: MatD = MatD.zeros(nParams, 1)

  // Center columns of m (subtract each column mean).
  private def centerColumns(m: MatD): MatD =
    val colMeans = m.sum(0) / m.rows.toDouble   // (1, N)
    m - colMeans                                 // broadcast

  // Column-wise population standard deviation; returns (1×N) row vector.
  private def stdcols(m: MatD): MatD =
    val colMeans = m.sum(0) / m.rows.toDouble   // (1, N)
    val centered = m - colMeans                  // (T, N)
    ((centered ~^ 2.0).sum(0) / (m.rows - 1).toDouble).sqrt

  // Scalar standard deviation of a column vector.
  private def stdrow(v: MatD): Double =
    val mu      = v.mean
    val centered = v - mu
    math.sqrt((centered ~^ 2.0).sum / (v.rows - 1).toDouble)

  def reportTime(loops: Int = 1000)(func: => Unit): Double = {
    val t0: Long = System.currentTimeMillis
    def elapsedSeconds: Double = (System.currentTimeMillis - t0).toDouble / 1000.0
    for _ <- 0 until loops do
      func
    elapsedSeconds
  }

  def show(tag: String, t: TprfResult): Unit = {
    printf("%s X: %s\n",     tag, t.X.shapes)
    printf("%s y: %s\n",     tag, t.y.shapes)
    printf("%s Z: %s\n",     tag, t.Z.shapes)
    printf("%s y_hat: %s\n", tag, t.y_hat.shapes)
  }

  trait TprfResult {
    def X: MatD       // design matrix (T×N)
    def y: MatD       // response column vector (T×1)
    def Z: MatD       // proxy matrix (T×L)
    def y_hat: MatD   // estimated response (T×1)

    def prediction(periods: Int = 1): Double =
      //val ypred: MatD = y(periods until y.rows, ::)
      //val Xpred: MatD = X(0 until X.rows - periods, ::)
      y_hat(y_hat.rows - 1, 0)   // y-hat prediction for time T+1

    lazy val residuals: MatD  = y - y_hat
    lazy val n: Int           = X.rows
    lazy val df: Double       = Z.cols.toDouble
    lazy val rss: Double      = (residuals ~^ 2.0).sum

    def coefficients: MatD

    def degreesOfFreedom: Double =
      val tp       = TprfDirect(X, y, Z)
      val hatX     = tp.alpha_hat_factor
      val centered = centerColumns(hatX)
      val (u, _, _) = centered.svd
      val H = u *@ u.T
      H.trace
  }

  def I(len: Int): MatD = MatD.eye(len)

  def J(len: Int): MatD =
    MatD.eye(len) - MatD.ones(len, len) * (1.0 / len)

  def tprfDirect(X: MatD, y: MatD, Z: MatD): TprfDirect = TprfDirect(X, y, Z)

  // The 3PRF estimator α̂ is a projection coefficient relating y(t+1) to x(t)
  // under the constraint that irrelevant factors do not influence forecasts.
  case class TprfDirect(X: MatD, y: MatD, Z: MatD) extends TprfResult:
    assert(X.cols >= Z.cols, s"X.cols: ${X.cols} is less than Z.cols: ${Z.cols}")
    val mean_y: Double = y.mean

    val T: Int = X.rows
    val N: Int = X.cols
    val Jt: MatD  = J(T)
    val Jn: MatD  = J(N)
    val XtJt: MatD = X.T *@ Jt
    val JtX: MatD  = Jt *@ X
    val Wxz: MatD  = Jn *@ XtJt *@ Z
    val Sxx: MatD  = XtJt *@ X
    val SXy: MatD  = XtJt *@ y

    lazy val JtZ: MatD = Jt *@ Z
    lazy val Szz: MatD = Z.T *@ JtZ
    lazy val Sxz: MatD = X.T *@ JtZ

    // α̂ (alpha_hat) is a constrained version of OLS predictive coefficient.
    // Shape: (N, T)
    val alpha_hat_factor: MatD =
      Wxz *@ (Wxz.T *@ Sxx *@ Wxz).inverse *@ Wxz.T *@ XtJt

    val alpha_hat: MatD = alpha_hat_factor *@ y   // (N, 1)

    lazy val f_hat: MatD =
      Szz *@ (Wxz.T *@ Wxz).inverse *@ Wxz.T *@ XtJt

    lazy val beta_hat: MatD =
      Szz.inverse *@ Wxz.T *@ alpha_hat

    def calcYhat(yvec: MatD): MatD =
      val alpHat: MatD = alpha_hat_factor *@ yvec  // (N, 1)
      JtX *@ alpHat + yvec.mean                    // (T, 1) + scalar

    val y_hat: MatD = calcYhat(y)

    lazy val coefficients: MatD = beta_hat
    lazy val intercept: Double  = y_hat.mean

    override def toString: String =
      "residuals: %s\ny_hat: %s\n".format(residuals, y_hat)

  // pass1columnsRsquared: R² for regressing each X column on Z in pass 1
  // pass3model: OLS model from pass 3 (y versus sigma2)
  case class TprfLooped(
    X: MatD, y: MatD, Z: MatD,
    phi: MatD, sigma: MatD,
    pass1columnsRsquared: Array[Double],
    pass3model: Lm
  ) extends TprfResult:
    val betaHatCoeff: MatD       = pass3model.coef_
    val betaHatIntercept: Double = pass3model.intercept_
    inline def coefficients: MatD  = betaHatCoeff
    inline def intercept: Double   = betaHatIntercept
    def pValues: MatD              = pass3model.p
    def rSquared: BigDecimal       = bigVert(pass3model.rSquared)
    def adjRsq: Double             = pass3model.adjRs

    val y_hat: MatD = sigma *@ betaHatCoeff + betaHatIntercept

    def estimateYhat(oos: MatD): Double =
      val rowmodel  = Lm(oos, phi)
      val rowsigma  = rowmodel.coef_                          // (L, 1)
      (rowsigma.T *@ betaHatCoeff)(0, 0) + betaHatIntercept  // scalar

    override def toString: String =
      "residuals: %s\ny_hat: %s\n".format(residuals, y_hat)

  def bigVert(d: Double): BigDecimal =
    if d.isNaN || d.isInfinite then
      BigDecimal(0)
    else
      try BigDecimal(d)
      catch
        case _: NumberFormatException =>
          System.err.printf("NumberFormatException[%s]%n", d.toString)
          BigDecimal(0)

  def tprf(X: MatD, y: MatD, Z: MatD, oos: MatD = MatD.zeros(0, 1)): (MatD, Double) =
    val oos_present = oos.rows > 1
    val model       = tprfLooped(X, y, Z, oos)
    val yhatt       = if oos_present then model.estimateYhat(oos) else Double.NaN
    (model.y_hat, yhatt)

  def tprfLooped(X: MatD, y: MatD, Z: MatD, oos: MatD = MatD.zeros(0, 1)): TprfLooped =
    val oos_present = oos.rows > 1
    val (_T, _N, _L) = (X.rows, X.cols, Z.cols)
    assert(X.rows == _T && X.cols == _N, s"X shape: (${X.rows}, ${X.cols}), T=${_T}, N=${_N}")
    assert(y.rows == _T && y.cols == 1,  s"y shape: (${y.rows}, ${y.cols}), T=${_T}")
    assert(Z.rows == _T && Z.cols == _L, s"Z shape: (${Z.rows}, ${Z.cols}), T=${_T}, L=${_L}")
    if oos_present then
      assert(oos.rows == _N && oos.cols == 1, s"oos shape: (${oos.rows}, ${oos.cols}), expecting (${_N} x 1)")
    assert(X.cols >= Z.cols, s"X.cols: ${X.cols} is less than Z.cols: ${Z.cols}")

    // Pass 1: regress each column of X on Z; store coefficients as rows of phi.
    val phi = MatD.zeros(X.cols, Z.cols)    // (N, L)
    val pass1columnsRsquared = Array.ofDim[Double](X.cols)
    def adjustRsq(d: Double): Double = if d.isNaN || d.isInfinite then 0.0 else d

    for i <- 0 until X.cols do
      val xcol = X(::, i)
      require(xcol.rows == Z.rows)
      require(Z.rows > Z.cols)
      val model = Lm(xcol, Z)
      pass1columnsRsquared(i) = adjustRsq(model.rSquared)
      phi(i until i+1, ::) = model.coef_.T   // (1, L) → row i of phi

    // Pass 2: regress each row of X on phi; store coefficients as rows of sigma2.
    val sigma2 = MatD.zeros(X.rows, Z.cols)  // (T, L)
    for t <- 0 until X.rows do
      val rowt: MatD = X(t, ::).T            // (N, 1) column vector
      if phi.rows < phi.cols then hook += 1
      val model = Lm(rowt, phi, addIntercept = true)
      sigma2(t until t+1, ::) = model.coef_.T  // (1, L) → row t of sigma2

    // Pass 3: regress y on sigma2.
    val third_pass_model = Lm(y, sigma2)
    TprfLooped(X, y, Z, phi, sigma2, pass1columnsRsquared, third_pass_model)

  def tprfLoopedAuto(X: MatD, y: MatD, num_proxies: Int = 2, oos: MatD = MatD.zeros(0, 1)): TprfLooped =
    val Z = autoproxy(X, y, num_proxies)
    tprfLooped(X, y, Z, oos)

  def tprfDirectAuto(X: MatD, y: MatD, num_proxies: Int = 2, oos: MatD = MatD.zeros(0, 1)): TprfDirect =
    val Z = autoproxy(X, y, num_proxies)
    tprfDirect(X, y, Z)

  def autoproxy(X: MatD, y: MatD, n_proxy: Int): MatD =
    var r0: MatD = y    // (n, 1) — first proxy column is y itself
    for _ <- 1 until n_proxy do
      val yhat: MatD      = tprfDirect(X, y, r0).y_hat
      val residuals: MatD = y - yhat
      r0 = MatD.hstack(residuals, r0)
    r0

  def autoproxyLooped(X: MatD, y: MatD, n_proxy: Int): MatD =
    var r0: MatD = y
    for _ <- 0 until n_proxy do
      val yhat: MatD      = tprfLooped(X, y, r0).y_hat
      val residuals: MatD = y - yhat
      r0 = MatD.hstack(residuals, r0)
    r0

  def recursiveTrainAuto(X: MatD, y: MatD, train_window: Int, n_proxies: Int): Double =
    assert(n_proxies > 0, s"n_proxies[$n_proxies]")
    val lst = Array.ofDim[Double](X.rows - train_window)
    for t <- train_window until X.rows do
      val Z               = autoproxy(X(0 until t, ::), y(0 until t, ::), n_proxies)
      val stdcolsX: MatD  = stdcols(X(0 until t, ::))   // (1, N)
      val X_train: MatD   = X(0 until t, ::) / stdcolsX // column-wise scaling
      val X_test: MatD    =
        val tmat     = X(t, ::).T                        // (N, 1)
        val rowstdev = stdrow(tmat)
        tmat / rowstdev
      val y_train = y(0 until t, ::)
      val (_, yhatt) = tprf(X_train, y_train, Z, X_test)
      lst(t - train_window) = yhatt
    val yhatt  = Mat.create(lst, lst.length, 1)
    val y_true = y(train_window until y.rows, ::)
    rr2(y_true, yhatt)

  def recursive_train(X: MatD, y: MatD, _Z: MatD, train_window: Int, n_proxies: Int): Double =
    val do_autoproxy = n_proxies > 0
    var Z = _Z
    val lst = Array.ofDim[Double](X.rows - train_window)
    for t <- train_window until X.rows do
      if do_autoproxy then
        Z = autoproxy(X(0 until t, ::), y(0 until t, ::), n_proxies)
      else
        Z = Z(::, 0 until t)
      val stdcolsX: MatD = stdcols(X(0 until t, ::))
      val X_train: MatD  = X(0 until t, ::) / stdcolsX
      val X_test: MatD   =
        val tmat     = X(t, ::).T
        val rowstdev = stdrow(tmat)
        tmat / rowstdev
      val y_train = y(0 until t, ::)
      val (_, yhatt) = tprf(X_train, y_train, Z, X_test)
      lst(t - train_window) = yhatt
    val yhatt  = Mat.create(lst, lst.length, 1)
    val y_true = y(train_window until y.rows, ::)
    rr2(y_true, yhatt)

  def rr2(y_true: MatD, yhatt: MatD): Double =
    assert(yhatt.rows == y_true.rows)
    val residuals: MatD = y_true - yhatt
    Option(System.getenv("TPRF_RESIDUALS_OUT")).foreach { f =>
      if f.nonEmpty then
        val p = f.asPath
        if !p.exists then
          System.err.printf("creating residuals file [%s]%n", p.posx)
          val w = new PrintWriter(p.toFile)
          try
            for i <- 0 until residuals.rows do
              w.printf("%s%n", residuals(i, 0))
          finally w.close()
    }
    val rss  = (residuals ~^ 2.0).sum
    val ybar = y_true.mean
    val ssy  = ((y_true - ybar) ~^ 2.0).sum
    (ssy - rss) / ssy
}
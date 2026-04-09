#!/usr/bin/env -S scala-cli shebang -Wunused:imports -deprecation

//> using scala 3.7.0
//> using javaOpt "--add-modules=jdk.incubator.vector"
//> using dep org.vastblue:uni_3:0.12.0
//> using dep org.scalanlp::breeze:2.1.0

/////////////////////////////////////////////
// Three Pass Regression Filter Estimators //
/////////////////////////////////////////////

//' Three Pass Regression Filter
//'
//' Fits the three pass regression filter
//' @title Three Pass Regression filter
//' @param X Observations (typically lagged)
//' @param y Targets
//' @param Z Proxies
//' @param n_proxy Scalar. Number of proxies if Z is null
//' @param pls Partial Least Squares
//' @param center Center the data?
//' @param scale Scale the data
//' @param closed_form Use closed form estimation
//' @param fitalg OLS fit algorithm
//' @return TPRF object
//' @author Mohamed Ishmael Diwan Belghazi
//' @export
import uni.*
import breeze.linalg.*
import breeze.stats.*
import breeze.linalg.diag.*
import breeze.numerics.sqrt
import breeze.stats.{mean, stddev, variance}
import breeze.stats.regression.LeastSquaresRegressionResult
import uni.data.NumPyRNG

type D = Double
type Mat[T] = DenseMatrix[T]
type Vec[T] = DenseVector[T]
type MatD = DenseMatrix[Double]
type VecD = DenseVector[Double]

object ThreePrfBreeze {
  val ThreePrf = ThreePrfBreeze

  def usage(m: String=""): Nothing = {
    _usage(m, Seq(
      "<input-text-file>",
      "-pls            partial least squares",
      "-closed         closed form T3PRF",
    ))
  }

  var seed: Int = 42
  var n_proxy: Int = 1
  var center: Boolean = false
  var scale: Boolean = true
  val sigma_g = DenseVector[D](1.25, 1.75, 2.25, 2.75)
  val sigma_y = 1
  var verbose = false
  var rng: NumPyRNG = scala.compiletime.uninitialized

  def main(args: Array[String]): Unit = {
    rng = new NumPyRNG(seed) // reset to match uni version's Mat.setSeed(seed)
    var (_T, _N, _K_f, _L, center, scale, pls, closed_form, fitalg) = (200, 200, 1, 1, true, true, true, false, 2)
    eachArg(args.toSeq, usage){
    case "-v" => verbose = !verbose
    case "-pls" => pls = !pls
    case "-closed" => closed_form = !closed_form
    case arg => usage(s"unrecognized arg: [$arg]")
    }

    val sim = sim_problem(_T, _N, _K_f, sigma_g, _L, sigma_y)
    // ## * Forming infeasible best forecast
    val allfactors = cbind(sim.factors)
    val y_inf = allfactors  * sim.beta + sim.beta_0
    // ## * Running Partial least squares
    val Z = autoProxies(n_proxy, sim.X, sim.y, pls, closed_form, fitalg)
    val fit = TPRF(sim.X, sim.y, Z, L=0, pls, center, scale, closed_form, fitalg)
    val cc: MatD = fit.loadings
    dump4x4("loadings", cc)
  }

  def TPRF(
    _X: MatD,
    y: VecD,
    _Z: MatD,
    L: Int = 0, // number of proxies to automatically generate
    pls: Boolean = false,
    center: Boolean = false,
    scale: Boolean = true,
    closed_form: Boolean = false,
    fitalg: Int = 2
  ) = {
    // y should be univariate
    if (y.cols != 1) {
      sys.error("y should be univariate")
    }
    // partial least squares is equivalent to TPRF with no intercept,
    // standardized predictors and n_proxy=1
    {
      var (center, scale) = if (pls) {
        (true, true)
      } else {
        (this.center, this.scale)
      }
      def n_proxy = if (closed_form) 1 else L
      ThreePrf.n_proxy = if (closed_form) 1 else n_proxy

      // Both proxies Z and the number automatic proxies cannot be unspecified
      if( n_proxy == 0 && (_Z.rows == 0 || _Z.cols == 0) ){
        sys.error("please either provide proxies or choose a number of automatic proxies to build")
      }

      val X = breeze.linalg.scale(_X, center, scale)

      val Z = if(n_proxy == 0){
        _Z // use provided proxies
      } else {
        autoProxies(n_proxy, X, y, pls, closed_form, fitalg)
      }

      // Running three pass regression filter
      val fit = tprf_fit(X, y, Z, pls=pls, closed_form=closed_form, fitalg=fitalg)

      // Creating TPRF object
      /*
          fit           = fit,
          loadings      = fit$loadings,
          factors       = fit$factors,
          alpha_hat     = fit$alpha_hat,
          centered      = centered,
          means         = X_mean,
          scaled        = scaled,
          scales        = X_sd
          pls           = pls,
          n_proxy       = n_proxy,
          closed_form   = closed_form,
        ),
        class="t3prf"
      */
      T3prf(fit, pls, n_proxy, closed_form, center, scale)
    }
  }

  case class T3prf(fit: PredReg, pls: Boolean, n_proxy: Int, closed_form: Boolean, center: Boolean, scale: Boolean){
    def alpha_hat = fit.alpha_hat
    def y_hat = fit.y_hat
    def residuals = fit.residuals
    def loadings = fit.loadings
    def factors = fit.factors
  }

  case class PredReg(alpha_hat: VecD, y_hat: VecD, residuals: VecD, loadings: MatD, factors: MatD)

  def autoProxies(n_proxy: Int, X: MatD, y: VecD, pls: Boolean=false, closed_form: Boolean=true, fitalg: Int=2): MatD = {
    // Computing automatic proxies
    val r = new MatD(y.size, n_proxy)
    r(::, 0) := y // r[, 1] <- y
    printf("auprx:X             %s\n", X.shapes)
    printf("auprx:y             %s\n", y.shapes)
    printf("auprx:r             %s\n", r.shapes)
    for (k <- 1 until n_proxy) {
      val result = leastSquares(X, y) // fit
      /* r[, k] <- resid(.tprf_fit(X, y, r[, k - 1], pls=pls, closed_form=closed_form, fitalg=fitalg)) */
      val slice: VecD = r(::, k - 1)
      val mat = new MatD(slice.size, 1, slice.toArray) // we're appending columns
      val fit: PredReg = tprf_fit(X, y, mat, pls, closed_form, fitalg)
      r(::, k) := fit.residuals
    }
    r
  }
  //' @export
  def tprf_fit(X: MatD, y: VecD, Z: MatD, pls: Boolean=false, closed_form: Boolean=true,  fitalg: Int = 2): PredReg = {
    if(closed_form) {
      tprf_fit_closed(X, y, Z, pls=pls)
    } else {
      tprf_fit_iter(X, y, Z, pls=pls, fitalg=fitalg)
    }
  }

  //' @export
  def tprf_fit_iter(X: MatD, y: VecD, Z: MatD, pls: Boolean=false, closed_form:Boolean=true, fitalg: Int = 2): PredReg = {
    printf("fit_iter:X          %s\n", X.shapes)
    printf("fit_iter:y          %s\n", y.shapes)
    printf("fit_iter:Z          %s\n", Z.shapes)

    val plsNum = if (pls) 0 else 1 // no intercept if pls

    // Pass 1 Time series regression
    // Preallocating loadings
    val loadings = MatrixNaN(X.cols, Z.cols + plsNum)
    printf("fit_iter:loadings:  %s\n", loadings.shapes)

    if (pls) {
      for (j <- 0 until loadings.rows) {
        var Zm: MatD = Z.copy
        Zm :+= -1.0
        val Xj = X(::, j)
        val result: LeastSquaresRegressionResult = leastSquares(Zm, Xj) // fit
        val coefs: VecD = result.coefficients
        if (j==0) printf("fit_iter:coeffs:    %s\n", coefs.shapes)
        //val coefs = coef(lm(formula=X(:: , j) ~ Z - 1, na.action=na.exclude, model=false))
        loadings(j, ::) := coefs.t // row vector
      }
    } else {
      loadings(::, 0) := DenseVector.ones[D](loadings.rows)
      for (j <- 1 until loadings.rows) {
        var Zm: MatD = prependOnesColumn(Z.copy)
        Zm :+= 1.0
        val Xj = X(::, j)
        val result = leastSquares(Zm, Xj) // fit
        val coefs = result.coefficients
        if (j==0) printf("fit_iter:coeffs:    %s\n", coefs.shapes)
        // coef( lm( formula = X(::, j) ~ 1 + Z, na.action=na.exclude, model=false))
        loadings(j, ::) := coefs.t
      }
    }

    // Pass II Cross section regression
    var factors = MatrixNaN(X.rows, loadings.cols)

    // Loadings has no intercept in pls
    if (pls) {
      for (i <- 0 until factors.rows) {
        var L1 = loadings.copy
        L1 :+= -1.0
        val result = leastSquares(L1, X(i, ::).t) // fit
        val coeffs: VecD = result.coefficients
        //factors[i, ] <- coef(lm(formula=X[i,] ~ loadings - 1,       na.action=na.exclude, model=false))
        factors(i, ::) := coeffs.t
      }
    } else {
      for (i <- 1 until factors.rows) {
        var L1 = loadings.copy
//        L1  :+= 1.0
        val result = leastSquares(L1, X(i, ::).t) // fit
        val coefs: VecD = result.coefficients
        if (i==1) printf("fit_iter:L1:        %s\n", L1.shapes)
        if (i==1) printf("fit_iter:coefs:     %s\n", coefs.shapes)
        //factors[i, ] <- coef(lm(formula=X[i,] ~ 1 + loadings[, -1], na.action=na.exclude, model=false))
        factors(i, ::) := coefs.t
      }
    }

    // Pass III predictive regression
    // Factors has no intercept in pls
    def dropColZero: MatD = factors(::, 1 to -1).copy
    val factors_reg: MatD = if(pls) factors.copy else dropColZero // factors[, -1, drop=false]
    val result = leastSquares(factors_reg :+= 1.0, y) // fit
      // lm(formula=y ~ 1 + factors_reg, na.action=na.exclude, model=false)

    //predictive_reg <- lm(formula=y ~ 1 + factors_reg, na.action=na.exclude, model=false)
    // Adding loadings and factors to list
    /*
    predictive_reg$loadings <- loadings
    predictive_reg$factors <- factors
    */
    val predreg = PredReg(null, null, null, loadings, factors)
    predreg
  }


  def J(T: D): MatD = {
    val Iᴛ: MatD = DenseMatrix.eye[Double](T.toInt) // DenseMatrix (a row vector)
    val ιᴛ = DenseVector.ones[Double](T.toInt)
    val Jᴛ = Iᴛ - 1.0/T * ιᴛ  * ιᴛ.t
    Jᴛ
  }
  /*
  def J2(T: D): MatD = { // unused? translated from R
    val vec1: MatD = DenseMatrix(VecD(T.toInt))
    val ones: MatD = DenseMatrix.ones[Double](T.toInt, T.toInt)
    printf("vec1.shape:         %s\n", vec1.shape)
    printf("ones.shape:         %s\n", ones.shape)
    diag(vec1 - 1.0/T  * ones) // compile error with scala 3.2.1
  }
  */

  //
  def tprf_fit_closed(Xorig: MatD, y: VecD, Z: MatD, pls: Boolean = false): PredReg = {
    val X = Xorig.copy
    printf("fit_closed:X:       %s\n", X.shapes)
    printf("fit_closed:y:       %s\n", y.shapes)
    printf("fit_closed:Z:       %s\n", Z.shapes)
    var (y_hat: VecD, alpha_hat: VecD) = if (pls) {
      val XX = X  * X.t
      val part1: VecD = XX  * y
      val part2: D = 1.0 / (y.t  * XX  * XX  * y)
      val part3: D = y.t  * XX  * y
      val y_hat: VecD = part1  * part2  * part3 + mean(y) // Should y be demeaned?
      val alpha_hat: VecD = DenseVector.zeros[D](y_hat.size)
      (y_hat, alpha_hat)

    } else {
      val T = NROW(X)
      val N = NCOL(X)
      val Jn = J(N)
      val Jt = J(T)
      printf("fit_closed:Jn:      %s\n", Jn.shapes)
      printf("fit_closed:X.t:     %s\n", X.t.shapes)
      printf("fit_closed:Jt:      %s\n", Jt.shapes)
      printf("fit_closed:Z:       %s\n", X.shapes)
      val wxzRite = Jt  * Z
      printf("fit_closed:wxzRite: %s\n", wxzRite.shapes)
      val Xt = X.t
      val wxzLeft = Jn  * Xt
      printf("fit_closed:wxzLeft: %s\n", wxzLeft.shapes)
      val W_XZ = wxzLeft  * wxzRite // val W_XZ = Jn  * X.t  * Jt  * Z
      val S_XX = Xt  * Jt  * X
      val S_Xy = Xt  * Jt  * y
      val alpha_hat = W_XZ  * inv(W_XZ.t  * S_XX  * W_XZ)  * W_XZ.t  * S_Xy
      val y_hat = Jt  * X  * alpha_hat + mean(y)
      (y_hat, alpha_hat)
    }
    val fitted = y_hat
    val residuals = y - y_hat

    PredReg(alpha_hat, y_hat, residuals, null, null) // residuals, loadings, factors)
  }

  //###################################################
  //## Three Pass Regression Filter Model Simulation ##
  //###################################################
  def sim_factors(T: Int, K_f: Int=1, rho_f:D=0.0, rho_g:D=0.0, sigma_g: VecD= sigma_g): Seq[MatD] = {
    //## * Simulating relevant factor innovations
    val u_f = matrix(rnorm(T * K_f), nrow=T, ncol=K_f)
    val f: MatD = matrix(0, nrow=T, ncol=K_f)
    f(0, ::) := u_f(0, ::)
    for (i <- 1 until f.rows){
      val prevrow: VecD = f(i - 1, ::).t  * rho_f
      val prevu: VecD = u_f(i - 1, ::).t
      f(i, ::) := (prevrow + prevu).t
    }

    //##  * Simulating irrelevant factors innovations
    val K_g = sigma_g.size

    //## Variance have to be adjusted so that the variance of each irrelevant
    //## factor is greater than the variance of the relevant factor by the
    //## coefficients given in sigma_g
    val col0: VecD = f(::, 0)
    val col0variance: VecD = variance(col0)  * sigma_g

    // the next 2 probably not needed? PMW
    val col0varDiag: MatD = diag(col0variance)
//    val sigma_g_mat: VecD = diag(col0varDiag)
//    printf("sigma_g_mat    %s\n", sigma_g_mat.shapes)
    printf("sim_f:col0varDiag   %s\n", col0varDiag.shapes)

    var g: List[MatD] = if (K_g > 0) {
      val rn = rnorm(T * K_g)
      printf("sim_f:rn            %s\n", rn.shapes)
      val sigma_g = col0varDiag // sigma_g is a diagonal matrix inside this block!
      dump4x4("sigma_g", sigma_g)
      val sigma_g_sqrt: MatD = sqrt(sigma_g)
      val matRnorm: MatD = matrix(rnorm(T * K_g), nrow=T, ncol=K_g)
      printf("sim_f:matRnorm      %s\n", matRnorm.shapes)
//      val u_g: MatD = matRnorm  * sigma_g_sqrt
      printf("sim_f:sigma_g_sqrt  %s\n", sigma_g_sqrt.shapes)
      val u_g: MatD = matrix(rnorm(T * K_g), nrow=T, ncol=K_g)  * sigma_g_sqrt
      printf("sim_f:u_g           %s\n", u_g.shapes)
      val u_gtRow0: VecD = u_g(0, ::).t // row vector
      val u_g_row0: Transpose[VecD] = u_g(0, ::)
      val g = DenseMatrix.zeros[D](T, K_g)
      printf("sim_f:u_gtRow0      %s\n", u_gtRow0.shapes)
      printf("sim_f:g             %s\n", g.shapes)
      printf("sim_f:u_g_row0      %s\n", u_g_row0.shapes)
      g(0, ::) := u_g_row0
      for(i <- 1 until g.rows) {
        try {
          val gprevRow = g(i-1, ::)
          if (i==1) printf("sim_f:gprevRow      %s\n", gprevRow.shapes)
          val u_giRow: Transpose[VecD] = u_g(i, ::)
          if (i==1) printf("sim_f:u_giRow       %s\n", u_giRow.shapes)
          val gnuRow = gprevRow  * rho_g + u_giRow
          if (i==1) printf("sim_f:gnuRow        %s\n", gnuRow.shapes)
  //        if (i==1) printf("gnuRow.t   %s\n", gnuRow.t.shapes)
          g(i, ::) := gnuRow
        } catch {
        case t: Throwable =>
          printf("last loop before possible exception, i: %d\n", i)
          throw t
        }
      }
      List(g)
    } else {
      Nil
    }
    // ##  * returning factors
    f :: g
  }

  //##' @export
  def sim_target(factors: Seq[MatD], beta_0: D, beta: MatD, sigma_y:Int=1): VecD = {
    printf("sim_target(factors, beta_0:%s, beta, sigma_y:%s)\n", beta_0, sigma_y)
    val F = mergeFactorColumns(factors)
    val T = F.rows
    //## * Generating innovations
    val u_y: MatD = matrix(rnorm(T), nrow=T, ncol=1, byrow=false)
    val Fxbeta = F  * beta
    printf("sim_t:F             %s\n", F.shapes)
    printf("sim_t:beta          %s\n", beta.shapes)
    printf("sim_t:Fxbeta        %s\n", Fxbeta.shapes)
    printf("sim_t:u_y           %s\n", u_y.shapes)

    val y = Fxbeta + beta_0 + sigma_y.toDouble * u_y
    printf("sim_t:y             %s\n", y.shapes)
    assert(y.cols==1, s"too many columns: ${y.cols}: $y")
    y(::, 0) // there should only be one column
  }

  // partially apply R %*% multiplication rules
  def rMult(m1: MatD, m2: MatD): MatD = {
    if(m1.cols == 1 && m2.cols == 1){
      val v1 = m1(::, 0)
      val v2 = m2(::, 0)
      DenseMatrix(v1.t  * v2)
    } else {
      m1  * m2
    }
  }

  //##' @export
  def sim_observations(N: Int, factors: Seq[MatD], phi_0: D, phi: MatD): MatD = {
    printf("sim_o:factors$f     %s\n", factors(0).shapes)
    if (factors.size>1) printf("sim_o:factors$g     %s\n", factors(1).shapes)
    val F = mergeFactorColumns(factors)
    val T = F.rows
    val K = F.cols
    /*
    val (f,g) = factors.toList match {
      case f :: Nil => (f, new DenseMatrix(0, 0, Array.empty[D]))
      case f :: g :: Nil => (f, g)
      case _ => sys.error(s"bad factors Seq: ${factors}")
    }
    printf("sim_o:factors$f %s\n", factors(0).shapes)
    if (factors.size>1) printf("sim_o:factors$g %s\n", factors(1).shapes)
    val T: Int = f.rows
    val K: Int = factors.map(_.cols).sum
    printf("K:                  %5d\n", K)
    val F: MatD = c(f, g)
    */
    val epsilon: MatD = matrix(rnorm(T  * N), nrow=T, ncol=N)
    printf("sim_o:rnorm(T * N)  %5d x %5d\n", T  * N, 1)
    printf("sim_o:epsilon       %s\n", epsilon.shapes)
    printf("sim_o:F             %s\n", F.shapes)
    printf("sim_o:phi           %s\n", phi.shapes)
    val FxphiT = F  * phi.t
    val X: MatD = FxphiT + epsilon + phi_0
    X
  }
  //##' export
  def sim_proxies(L: Int, factors: Seq[MatD], lambda_0: D, lambda: MatD): MatD = {
    sim_observations(L, factors, lambda_0, lambda)
  }
  def mergeFactorColumns(factors: Seq[MatD]): MatD = {
    factors match {
      case Seq(f) => f
      case Seq(f, g) => c(f, g)
      case _ => sys.error(s"bad factors: ${factors}")
    }
  }

  //##' @export
  def sim_problem(T: Int, N: Int, K_f: Int, sigma_g: VecD, L: Int=2, sigma_y: Int=1): Simulation = {
    assert(L > 0, s"error: L <= 0, but at least one proxy is required")
    //## Simulate Factors
    val factors: Seq[MatD] @unchecked = sim_factors(T, K_f, sigma_g=sigma_g)
    for ((fact, i) <- factors.zipWithIndex) {
      printf("sim_p:factors(%d)    %s\n", i, fact.shapes)
    }
    val F = mergeFactorColumns(factors)
    val K = F.cols
    printf("sim_p:K:                    %5d\n", K)

    //## Simulate observations
    val phi_0: D = runifDbl(1, -1, 1)
    printf("sim_p:N x K:        %5d x %5d\n", N, K)
    printf("sim_p:runif(N * K)  %5d x %5d\n", N * K, 1)
    val phi = matrix(runif(N * K, -1, 1), nrow=N, ncol=K)
    printf("sim_p:phi.t         %s\n", phi.t.shapes)
    val X = sim_observations(N, factors, phi_0, phi)

    //## Simulate proxies
    val lambda_0: D = runifDbl(1, -1, 1)
    val lambda: MatD = matrix(runif(L * K), nrow=L, ncol=K)
    val Z: MatD = sim_proxies(L, factors, lambda_0, lambda)

    //## Simulate targets
    val beta_0: D = runifDbl(1, -1, 1)

    val K_g = K - K_f
    val beta = matrix(c(runif(K_f, -1, 1), rep(0, K - K_f)), nrow=K, ncol=1, byrow=true)
    val y: VecD = sim_target(factors, beta_0, beta)

    Simulation(
      factors, phi_0, phi, X, K,
      K_f, K_g, sigma_g, lambda_0,
      lambda, Z, beta_0, beta,
      y, sigma_y
    )
    /*
    val simulation = Map(
      "factors" -> factors,
      "phi_0" -> phi_0,
      "phi" -> phi,
      "X" -> X,
      "K" -> K,
      "K_f" -> K_f,
      "K_g" -> K_g,
      "sigma_g" -> sigma_g,
      "lambda_0" -> lambda_0,
      "lambda" -> lambda
      "Z" -> Z
      "beta_0" -> beta_0,
      "beta" -> beta,
      "y" -> y,
      "sigma_y" -> sigma_y,
    )
    simulation
    */
  }
  case class Simulation(
    factors: Seq[MatD],
    phi_0: D,
    phi: MatD,
    X: MatD,
    K: Int,
    K_f: Int,
    K_g: Int,
    sigma_g: VecD,
    lambda_0: D,
    lambda: MatD,
    Z: MatD,
    beta_0: D,
    beta: MatD,
    y: VecD,
    sigma_y: D
  )
  //////////////////////////////////////////////////
  def prependOnesColumn(original: DenseMatrix[D]): DenseMatrix[D] = {
    val ones = DenseMatrix.ones[D](original.rows, 1)
    val dataWithOnes = ones.data ++ original.data
    DenseMatrix.create(original.rows, original.cols + 1, dataWithOnes)
  }
  def runif(n: Int, min: D=0.0, max: D=1.0): Array[D] =
    Array.fill(n)(rng.uniform(min, max))
  def runifDbl(n: 1, min: D, max: D): D =
    rng.uniform(min, max)
  def rep(d: D, n: Int): Array[D] = {
    val nums = for {
      i <- (0 until n)
    } yield d
    nums.toArray
  }
  def rep(x: VecD, n: Int): Array[D] = {
    val nums = for {
      (_,d) <- (0 until n) zip x.toArray
    } yield d
    nums.toArray
  }
  // this must append the 2nd array as additional columns
  def c(arr1: Array[D], arr2: Array[D]): Array[D] = {
    (arr1 ++ arr2).toArray
  }
  def cbind(mats: Seq[MatD]): MatD = {
    val cols = mats.map{_.cols}.sum
    val rows = mats.head.rows
    var numat = DenseMatrix.zeros[D](rows, cols)
    var prevcols = 0
    for ((mat,m) <- mats.zipWithIndex){
      assert(mat.rows == rows, s"mat.rows[${mat.rows}] != rows[$rows]")
      for(i <- 0 until mat.cols){
        numat(::,i+prevcols) := mat(::,i)
      }
      prevcols += mat.cols
    }
    numat
  }

  def c(arr1: MatD, arr2: MatD): MatD = {
    assert(arr1.rows == arr2.rows,s"error: arr1.rows[${arr1.rows}] != arr2.rows[${arr2.rows}]")
    var rows = arr1.rows
    val cols = arr1.cols + arr2.cols
    var both = DenseMatrix.zeros[D](rows, cols)
    for(i <- 0 until arr1.cols){
      both(::,i) := arr1(::,i)
    }
    for(i <- 0 until arr2.cols){
      both(::,i+arr1.cols) := arr2(::,i)
    }
    both
  }

  def NROW[T](X: DenseMatrix[T]): Int = X.rows
  def NCOL[T](X: DenseMatrix[T]): Int = X.cols
  def NROW[T](V: DenseVector[T]): Int = V.size
  def NCOL[T](V: DenseVector[T]): Int = 1

  implicit class ExtendLS(lsResult: LeastSquaresRegressionResult) {
    def intercept_ = lsResult.coefficients.data(0)
    def coef_ = new DenseVector[D](lsResult.coefficients.data.tail.toArray)
  }
  implicit class ExtendMat[T](mat: DenseMatrix[T]) {
    def shape: (Int, Int) = (mat.rows, mat.cols)
    def shapes: String = "%5s x %5s".format(mat.rows, mat.cols)
  }
  implicit class ExtendVec[T](v: DenseVector[T]) {
    def rows: Int = v.size
    def cols: Int = 1
    def shape = (rows, cols)
    def shapes: String = "%5s x %5s".format(rows, cols)
  }
  implicit class ExtendTransposeVec[T](v: Transpose[DenseVector[T]]) {
    def rows: Int = 1
    def cols: Long = v.inner.size
    def shape = (rows, cols)
    def shapes: String = "%5s x %5s".format(rows, cols)
  }
  def MatrixNaN(rows: Int, cols: Int): MatD = {
    val mat = DenseMatrix.zeros[D](rows, cols)
    mat(mat:== 0.0) := Double.NaN
    mat
  }
  /*
  def rnormMat(rows: Int, cols: Int): MatD = {
    val randBasis: RandBasis = new RandBasis(new ThreadLocalRandomGenerator(new MersenneTwister(seed)))
    DenseMatrix.rand(rows, cols, Gaussian(0.0, 1.0)(using randBasis))
  }
  */
  def rnorm(size: Int): VecD =
    new VecD(Array.fill(size)(rng.randn()))
  def rnormRow(size: Int): Transpose[VecD] = rnorm(size).t
  // Convert row-major flat array to breeze's column-major storage.
  // arr(r*ncol + c) → cm(c*nrow + r)  so element [r,c] has the same value as in uni (MatD).
  private def toColMajor(arr: Array[D], nrow: Int, ncol: Int): Array[D] = {
    val cm = Array.ofDim[D](nrow  * ncol)
    for (r <- 0 until nrow; c <- 0 until ncol)
      cm(c  * nrow + r) = arr(r  * ncol + c)
    cm
  }
  def matrix(vec: VecD, nrow: Int): MatD = {
    val ncol = vec.size / nrow
    new DenseMatrix(nrow, ncol, toColMajor(vec.toArray, nrow, ncol))
  }
  def matrix(vec: VecD, nrow: Int, ncol: Int, byrow: Boolean=true): MatD =
    matrix(vec.toArray, nrow, ncol, byrow)
  def matrix(arr: Array[D], nrow: Int, ncol: Int, byrow: Boolean): MatD =
    if byrow then new DenseMatrix(nrow, ncol, toColMajor(arr, nrow, ncol))
    else           new DenseMatrix(nrow, ncol, arr)
  def matrix(data: Array[D], nrow: Int, ncol: Int): MatD =
    new DenseMatrix(nrow, ncol, toColMajor(data, nrow, ncol))
  def matrix(init: D, nrow: Int, ncol: Int): MatD = {
    val data: Array[D] = Array.fill[D](nrow  * ncol)(init)
    new DenseMatrix(nrow, ncol, data)  // constant — layout doesn't matter
  }
  def matrix(vec: VecD, nrow: Int, ncol: Int): MatD =
    new DenseMatrix(nrow, ncol, toColMajor(vec.toArray, nrow, ncol))

  def center(data: MatD) = {
    val centers = mean(data,Axis._0).t.toDenseVector // means of each column (axis) of the data.
    // dumpVector("centers:\n%s\n",centers)
    data(*, ::) - centers
  }
  def centerAndScale(X: MatD) = {
    val X_mean = mean(X, Axis._0).t.toDenseVector // means of each column (axis) of the X.
    var centered = X(*, ::) - X_mean
    centered(*, ::) / stddev(centered(::,*)).t.toDenseVector
  }
//  def dumpVec(tag: String, v: VecD, max: Int = 8): Unit = {
//    val limit = if( max > 0 ) max else Int.MAXINT
//    val arr = vec.toArray.take(limit)
//    for(i <- 0 until arr.size){
//      printf(" %9.7f", arr(i))
//    }
//  }
  def dump4x4(tag: String, m: MatD): Unit = {
    printf("%s: %s\n", tag, m.shape)
    var rnum = 0
    for (row <- m(*, ::)){
      rnum += 1
      if (rnum <= 4) {
        printf("[%d,] ", rnum)
        for ((c, j) <- row.toArray.take(4).zipWithIndex) {
          printf(" %9.7f", c)
        }
        printf("\n")
      }
    }
  }

  def leastSquares(data: MatD, outputs: VecD) = {
    doLeastSquares(
      data.copy,
      outputs.copy,
      new Array[D](math.max(1, data.rows  * data.cols  * 2)))
  }
  def doLeastSquares(data: MatD, outputs: VecD, workArray: Array[D]): LeastSquaresRegressionResult = {
    import org.netlib.util.intW
    import dev.ludovic.netlib.lapack.LAPACK.{getInstance => lapack}
    import java.util.Arrays

    if( data.rows != outputs.size){
      hook += 1
    }
    require(data.rows == outputs.size)
    require(data.rows >= data.cols)
    require(workArray.length >= 2  * data.rows  * data.cols)

    val info = new intW(0)
    lapack.dgels(
      "N",
      data.rows,
      data.cols,
      1,
      data.data,
      data.rows,
      outputs.data,
      data.rows,
      workArray,
      workArray.length,
      info
    )
    if (info.`val` < 0) {
      throw new ArithmeticException("Least squares did not converge.")
    }

    val coefficients = new VecD(Arrays.copyOf(outputs.data, data.cols))
    var r2 = 0.0
    for (i <- 0 until (data.rows - data.cols)) {
      r2 = r2 + math.pow(outputs.data(data.cols + i), 2)
    }
    LeastSquaresRegressionResult(coefficients, r2)
  }
}
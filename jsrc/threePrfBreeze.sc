#!/usr/bin/env -S scala-cli shebang -Wunused:imports -deprecation

//> using dep org.vastblue:uni_3:0.10.1
//> using dep org.scalanlp::breeze:2.1.0

/////////////////////////////////////////////
// Three Pass Regression Filter Estimators //
/////////////////////////////////////////////

// * Three pass regression filter
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

object ThreePrfBreeze {
  type D = Double
  type Mat[T] = DenseMatrix[T]
  type Vec[T] = DenseVector[T]

  def usage(m: String=""): Nothing = {
    _usage(m, Seq(
      "<input-text-file>",
      "-pls            partial least squares",
      "-closed         closed form T3PRF",
    ))
  }

  var seed: Int = 42
  private var rng: NumPyRNG = new NumPyRNG(seed)
  var n_proxy: Int = 1
  var center: Boolean = false
  var scale: Boolean = true
  val sigma_g = DenseVector[Double](1.25, 1.75, 2.25, 2.75)
  val sigma_y = 1
  var verbose = false

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
    val y_inf = allfactors * sim.beta + sim.beta_0
    // ## * Running Partial least squares
    val Z = autoProxies(n_proxy, sim.X, sim.y, pls, closed_form, fitalg)
    val fit = TPRF(sim.X, sim.y, Z, L=0, pls, center, scale, closed_form, fitalg)
    val cc: Mat[D] = fit.loadings
    dump4x4("loadings", cc)
  }

  def TPRF(
    _X: Mat[D],
    y: Vec[D],
    _Z: Mat[D],
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
      ThreePrfBreeze.n_proxy = if (closed_form) 1 else n_proxy

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

  case class PredReg(alpha_hat: Vec[D], y_hat: Vec[D], residuals: Vec[D], loadings: Mat[D], factors: Mat[D])

  def autoProxies(n_proxy: Int, X: Mat[D], y: Vec[D], pls: Boolean=false, closed_form: Boolean=true, fitalg: Int=2): Mat[D] = {
    // Computing automatic proxies
    val r = new Mat[D](y.size, n_proxy)
    r(::, 0) := y // r[, 1] <- y
    printf("auprx:X             %s\n", X.shapes)
    printf("auprx:y             %s\n", y.shapes)
    printf("auprx:r             %s\n", r.shapes)
    for (k <- 1 until n_proxy) {
      val result = leastSquares(X, y) // fit
      /* r[, k] <- resid(.tprf_fit(X, y, r[, k - 1], pls=pls, closed_form=closed_form, fitalg=fitalg)) */
      val slice: Vec[D] = r(::, k - 1)
      val mat = new Mat[D](slice.size, 1, slice.toArray) // we're appending columns
      val fit: PredReg = tprf_fit(X, y, mat, pls, closed_form, fitalg)
      r(::, k) := fit.residuals
    }
    r
  }
  //' @export
  def tprf_fit(X: Mat[D], y: Vec[D], Z: Mat[D], pls: Boolean=false, closed_form: Boolean=true,  fitalg: Int = 2): PredReg = {
    if(closed_form) {
      tprf_fit_closed(X, y, Z, pls=pls)
    } else {
      tprf_fit_iter(X, y, Z, pls=pls, fitalg=fitalg)
    }
  }

  //' @export
  def tprf_fit_iter(X: Mat[D], y: Vec[D], Z: Mat[D], pls: Boolean=false, closed_form:Boolean=true, fitalg: Int = 2): PredReg = {
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
        var Zm: Mat[D] = Z.copy
        Zm :+= -1.0
        val Xj = X(::, j)
        val result: LeastSquaresRegressionResult = leastSquares(Zm, Xj) // fit
        val coefs: Vec[D] = result.coefficients
        if (j==0) printf("fit_iter:coeffs:    %s\n", coefs.shapes)
        //val coefs = coef(lm(formula=X(:: , j) ~ Z - 1, na.action=na.exclude, model=false))
        loadings(j,::) := coefs.t // row vector
      }
    } else {
      loadings(::,0) := DenseVector.ones[D](loadings.rows)
      for (j <- 1 until loadings.rows) {
        var Zm: Mat[D] = prependOnesColumn(Z.copy)
        Zm :+= 1.0
        val Xj = X(::, j)
        val result = leastSquares(Zm, Xj) // fit
        val coefs: Vec[D] = result.coefficients
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
        val coeffs: Vec[D] = result.coefficients
        //factors[i, ] <- coef(lm(formula=X[i,] ~ loadings - 1,       na.action=na.exclude, model=false))
        factors(i, ::) := coeffs.t
      }
    } else {
      for (i <- 1 until factors.rows) {
        var L1 = loadings.copy
//        L1  :+= 1.0
        val result = leastSquares(L1, X(i, ::).t) // fit
        val coefs: Vec[D] = result.coefficients
        if (i==1) printf("fit_iter:L1:        %s\n", L1.shapes)
        if (i==1) printf("fit_iter:coefs:     %s\n", coefs.shapes)
        //factors[i, ] <- coef(lm(formula=X[i,] ~ 1 + loadings[, -1], na.action=na.exclude, model=false))
        factors(i, ::) := coefs.t
      }
    }

    // Pass III predictive regression
    // Factors has no intercept in pls
    def dropColZero: DenseMatrix[Double] = factors(::, 1 to -1).copy
    val factors_reg: DenseMatrix[Double] = if(pls) factors.copy else dropColZero // factors[, -1, drop=false]
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

  def J(T: D): Mat[D] = {
    val Iᴛ: Mat[D] = DenseMatrix.eye[Double](T.toInt) // DenseMatrix (a row vector)
    val ιᴛ = DenseVector.ones[Double](T.toInt)
    val Jᴛ = Iᴛ - 1.0/T * ιᴛ * ιᴛ.t
    Jᴛ
  }
  /*
  def J2(T: D): Mat[D] = { // unused? translated from R
    val vec1: Mat[D] = DenseMatrix(DenseVector[Double](T.toInt))
    val ones: Mat[D] = DenseMatrix.ones[Double](T.toInt, T.toInt)
    printf("vec1.shape:         %s\n", vec1.shape)
    printf("ones.shape:         %s\n", ones.shape)
    diag(vec1 - 1.0/T * ones) // compile error with scala 3.2.1
  }
  */

  //
  def tprf_fit_closed(Xorig: Mat[D], y: Vec[D], Z: Mat[D], pls: Boolean = false): PredReg = {
    val X = Xorig.copy
    printf("fit_closed:X:       %s\n", X.shapes)
    printf("fit_closed:y:       %s\n", y.shapes)
    printf("fit_closed:Z:       %s\n", Z.shapes)
    var (y_hat: Vec[D], alpha_hat: Vec[D]) = if (pls) {
      val XX = X * X.t
      val part1: Vec[D] = XX * y
      val part2: Double = 1.0 / (y.t * XX * XX * y)
      val part3: Double = y.t * XX * y
      val y_hat: Vec[D] = part1 * part2 * part3 + mean(y) // Should y be demeaned?
      // alpha_hat = null  // No alpha_hat in pls
      val alpha_hat: Vec[D] = DenseVector.zeros[D](y_hat.size)
      (y_hat, alpha_hat)

    } else {
      val T = NROW(X)
      val N = NCOL(X)
      //J = function(len) { diag(rep(1, len)) - 1/len * matrix(1, nrow=len, ncol=len) }

      val Jn = J(N)
      val Jt = J(T)
      printf("fit_closed:Jn:      %s\n", Jn.shapes)
      printf("fit_closed:X.t:     %s\n", X.t.shapes)
      printf("fit_closed:Jt:      %s\n", Jt.shapes)
      printf("fit_closed:Z:       %s\n", X.shapes)
      val wxzRite = Jt * Z
      printf("fit_closed:wxzRite: %s\n", wxzRite.shapes)
      val Xt = X.t
      val wxzLeft = Jn * Xt
      printf("fit_closed:wxzLeft: %s\n", wxzLeft.shapes)
      val W_XZ = wxzLeft * wxzRite // val W_XZ = Jn * X.t * Jt * Z
      val S_XX = Xt * Jt * X
      val S_Xy = Xt * Jt * y
      /*
      W_XZ <- Jn %*% X.t %*% Jt %*% Z
      S_XX <- X.t %*% Jt %*% X
      S_Xy <- X.t %*% Jt %*% y
      */

      val alpha_hat = W_XZ * inv(W_XZ.t * S_XX * W_XZ) * W_XZ.t * S_Xy
      val y_hat = Jt * X * alpha_hat + mean(y)
      (y_hat, alpha_hat)

      /*
      alpha_hat <- W_XZ %*% solve(W_XZ.t %*% S_XX %*% W_XZ) %*% W_XZ.t %*% S_Xy
      y_hat <- mean(y) + Jt %*% X %*% alpha_hat
      */

      // part1 <- Jt %*% X %*% W_XZ
      // part2 <- solve(W_XZ.t %*% S_XX %*% W_XZ)
      // part3 <- W_XZ.t %*% S_Xy
      // y_hat <- mean(y) + part1 %*% part2 %*% part3
    }
    val fitted = y_hat
    val residuals = y - y_hat

    /*
    //rownames(alpha_hat) <- paste0("alpha", 1:NROW(alpha_hat))
    fit$alpha_hat <- alpha_hat
    y_hat <- as.vector(y_hat)
    names(y_hat) <- as.character(1:length(y_hat))
    fit$fitted.values <- y_hat
    fit$residuals <- as.vector(y - y_hat)
    */

    PredReg(alpha_hat, y_hat, residuals, null, null) // residuals, loadings, factors)
  }

  //###################################################
  //## Three Pass Regression Filter Model Simulation ##
  //###################################################
  def sim_factors(T: Int, K_f: Int=1, rho_f:Double=0.0, rho_g:Double=0.0, sigma_g: Vec[D]= sigma_g): Seq[Mat[D]] = {
    //## * Simulating relevant factor innovations
    val u_f = matrix(rnorm(T * K_f), nrow=T, ncol=K_f)
    val f: Mat[D] = matrix(0, nrow=T, ncol=K_f)
    f(0, ::) := u_f(0, ::)
    for (i <- 1 until f.rows){
      val prevrow: Vec[D] = f(i - 1, ::).t * rho_f
      val prevu: Vec[D] = u_f(i - 1, ::).t
      f(i, ::) := (prevrow + prevu).t
    }

    //## * Simulating irrelevant factors innovations
    val K_g = sigma_g.size

    //## Variance have to be adjusted so that the variance of each irrelevant
    //## factor is greater than the variance of the relevant factor by the
    //## coefficients given in sigma_g
    val col0: Vec[D] = f(::, 0)
    val col0variance: Vec[D] = variance(col0) * sigma_g

    // the next 2 probably not needed? PMW
    val col0varDiag: Mat[D] = diag(col0variance)
//    val sigma_g_mat: Vec[D] = diag(col0varDiag)
//    printf("sigma_g_mat    %s\n", sigma_g_mat.shapes)
    printf("sim_f:col0varDiag   %s\n", col0varDiag.shapes)

    var g: List[Mat[D]] = if (K_g > 0) {
      val rn = rnorm(T * K_g)
      printf("sim_f:rn            %s\n", rn.shapes)
      val sigma_g = col0varDiag // sigma_g is a diagonal matrix inside this block!
      dump4x4("sigma_g", sigma_g)
   // val sigma_g_sqrt: Mat[D] = DenseMatrix(sqrt(sigma_g).toArray).t // row vec
      val sigma_g_sqrt: Mat[D] = sqrt(sigma_g)
   // val u_g: Vec[D] = rnormMat(T, K_g) * sigma_g_sqrt
      val matRnorm: Mat[D] = matrix(rnorm(T * K_g), nrow=T, ncol=K_g)
      printf("sim_f:matRnorm      %s\n", matRnorm.shapes)
//      val u_g: Mat[D] = matRnorm * sigma_g_sqrt
      printf("sim_f:sigma_g_sqrt  %s\n", sigma_g_sqrt.shapes)
      val u_g: DenseMatrix[Double] = matrix(rnorm(T * K_g), nrow=T, ncol=K_g) * sigma_g_sqrt
      printf("sim_f:u_g           %s\n", u_g.shapes)
      val u_gtRow0: Vec[D] = u_g(0, ::).t // row vector
      val u_g_row0: Transpose[Vec[D]] = u_g(0, ::)
      val g = DenseMatrix.zeros[Double](T, K_g)
      printf("sim_f:u_gtRow0      %s\n", u_gtRow0.shapes)
      printf("sim_f:g             %s\n", g.shapes)
      printf("sim_f:u_g_row0      %s\n", u_g_row0.shapes)
      g(0, ::) := u_g_row0
      for(i <- 1 until g.rows) {
        try {
          val gprevRow = g(i-1, ::)
          if (i==1) printf("sim_f:gprevRow      %s\n", gprevRow.shapes)
          val u_giRow: Transpose[Vec[D]] = u_g(i,::)
          if (i==1) printf("sim_f:u_giRow       %s\n", u_giRow.shapes)
          val gnuRow = gprevRow * rho_g + u_giRow
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
    // ## * returning factors
    f :: g
  }

  //##' @export
  def sim_target(factors: Seq[Mat[D]], beta_0: D, beta: Mat[D], sigma_y:Int=1): Vec[D] = {
    printf("sim_target(factors, beta_0:%s, beta, sigma_y:%s)\n", beta_0, sigma_y)
    val F = mergeFactorColumns(factors)
    val T = F.rows
    //## * Generating innovations
    val u_y: Mat[D] = matrix(rnorm(T), nrow=T, ncol=1, byrow=false)
    val Fxbeta = F * beta
    printf("sim_t:F             %s\n", F.shapes)
    printf("sim_t:beta          %s\n", beta.shapes)
    printf("sim_t:Fxbeta        %s\n", Fxbeta.shapes)
    printf("sim_t:u_y           %s\n", u_y.shapes)

    val y = Fxbeta + beta_0 + sigma_y.toDouble * u_y
    printf("sim_t:y             %s\n", y.shapes)
    assert(y.cols==1, s"too many columns: ${y.cols}: $y")
    y(::,0) // there should only be one column
  }
  
  // partially apply R %*% multiplication rules
  def rMult(m1: Mat[D], m2: Mat[D]): Mat[D] = {
    if(m1.cols == 1 && m2.cols == 1){
      val v1 = m1(::,0)
      val v2 = m2(::,0)
      DenseMatrix(v1.t * v2)
    } else {
      m1 * m2
    }
  }

  //##' @export
  def sim_observations(N: Int, factors: Seq[Mat[D]], phi_0: D, phi: Mat[D]): Mat[D] = {
    printf("sim_o:factors$f     %s\n", factors(0).shapes)
    if (factors.size>1) printf("sim_o:factors$g     %s\n", factors(1).shapes)
    val F = mergeFactorColumns(factors)
    val T = F.rows
    val K = F.cols
    /*
    val (f,g) = factors.toList match {
      case f :: Nil => (f, new DenseMatrix(0, 0, Array.empty[Double]))
      case f :: g :: Nil => (f, g)
      case _ => sys.error(s"bad factors Seq: ${factors}")
    }
    printf("sim_o:factors$f %s\n", factors(0).shapes)
    if (factors.size>1) printf("sim_o:factors$g %s\n", factors(1).shapes)
    val T: Int = f.rows
    val K: Int = factors.map(_.cols).sum
    printf("K:                  %5d\n", K)
    val F: Mat[D] = c(f, g)
    */
    val epsilon: Mat[D] = matrix(rnorm(T * N), nrow=T, ncol=N)
    printf("sim_o:rnorm(T * N)  %5d x %5d\n", T * N, 1)
    printf("sim_o:epsilon       %s\n", epsilon.shapes)
    printf("sim_o:F             %s\n", F.shapes)
    printf("sim_o:phi           %s\n", phi.shapes)
    val FxphiT = F * phi.t
    val X: Mat[D] = FxphiT + epsilon + phi_0
    X
  }
  //##' export
  def sim_proxies(L: Int, factors: Seq[Mat[D]], lambda_0: D, lambda: Mat[D]): Mat[D] = {
    sim_observations(L, factors, lambda_0, lambda)
  }
  def mergeFactorColumns(factors: Seq[Mat[D]]): Mat[D] = {
    factors match {
      case Seq(f) => f
      case Seq(f, g) => c(f, g)
      case _ => sys.error(s"bad factors: ${factors}")
    }
  }

  //##' @export
  def sim_problem(T: Int, N: Int, K_f: Int, sigma_g: Vec[D], L: Int=2, sigma_y: Int=1): Simulation = {
    assert(L > 0, s"error: L <= 0, but at least one proxy is required")
    //## Simulate Factors
    val factors: Seq[Mat[D]] @unchecked = sim_factors(T, K_f, sigma_g=sigma_g)
    for ((fact, i) <- factors.zipWithIndex) {
      printf("sim_p:factors(%d)    %s\n", i, fact.shapes)
    }
    val F = mergeFactorColumns(factors)
    val K = F.cols
    printf("sim_p:K:                   %5d\n", K)

    //## Simulate observations
    val phi_0: D = runifDbl(1, -1, 1)
    printf("sim_p:N x K:        %5d x %5d\n", N, K)
    printf("sim_p:runif(N * K)   %5d x %5d\n", N * K, 1)
    val phi = matrix(runif(N * K, -1, 1), nrow=N, ncol=K)
    printf("sim_p:phi.t         %s\n", phi.t.shapes)
    val X = sim_observations(N, factors, phi_0, phi)
   
    //## Simulate proxies
    val lambda_0: D = runifDbl(1, -1, 1)
    val lambda: Mat[D] = matrix(runif(L * K), nrow=L, ncol=K)
    val Z: Mat[D] = sim_proxies(L, factors, lambda_0, lambda)

    //## Simulate targets
    val beta_0: D = runifDbl(1, -1, 1)

    val K_g = K - K_f
    val beta = matrix(c(runif(K_f, -1, 1), rep(0, K - K_f)), nrow=K, ncol=1, byrow=true)
    val y: Vec[D] = sim_target(factors, beta_0, beta)

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
    factors: Seq[Mat[D]],
    phi_0: D,
    phi: Mat[D],
    X: Mat[D],
    K: Int,
    K_f: Int,
    K_g: Int,
    sigma_g: Vec[D],
    lambda_0: D,
    lambda: Mat[D],
    Z: Mat[D],
    beta_0: D,
    beta: Mat[D],
    y: Vec[D],
    sigma_y: D
  )
  //////////////////////////////////////////////////
  def prependOnesColumn(original: DenseMatrix[D]): DenseMatrix[D] = {
    val ones = DenseMatrix.ones[D](original.rows, 1)
    val dataWithOnes = ones.data ++ original.data
    DenseMatrix.create(original.rows, original.cols + 1, dataWithOnes)
  }
  def runif(n: Int, min: Double=0.0, max: Double=1.0): Array[D] =
    Array.fill(n)(rng.uniform(min, max))
  def runifDbl(n: 1, min: Double, max: Double): D =
    rng.uniform(min, max)
  def rep(d: D, n: Int): Array[D] = {
    val nums = for {
      i <- (0 until n)
    } yield d
    nums.toArray
  }
  def rep(x: Vec[D], n: Int): Array[D] = {
    val nums = for {
      (_,d) <- (0 until n) zip x.toArray
    } yield d
    nums.toArray
  }
  // this must append the 2nd array as additional columns
  def c(arr1: Array[D], arr2: Array[D]): Array[D] = {
    (arr1 ++ arr2).toArray
  }
  def cbind(mats: Seq[Mat[D]]): Mat[D] = {
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

  def c(arr1: Mat[D], arr2: Mat[D]): Mat[D] = {
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
    def coef_ = DenseVector[Double](lsResult.coefficients.data.tail.toArray)
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
  def MatrixNaN(rows: Int, cols: Int): DenseMatrix[Double] = {
    val mat = DenseMatrix.zeros[Double](rows, cols)
    mat(mat:== 0.0) := Double.NaN
    mat
  }
  /*
  def rnormMat(rows: Int, cols: Int): DenseMatrix[Double] = {
    val randBasis: RandBasis = new RandBasis(new ThreadLocalRandomGenerator(new MersenneTwister(seed)))
    DenseMatrix.rand(rows, cols, Gaussian(0.0, 1.0)(using randBasis))
  }
  */
  def rnorm(size: Int): DenseVector[Double] =
    new DenseVector[Double](Array.fill(size)(rng.randn()))
  def rnormRow(size: Int): Transpose[DenseVector[Double]] = rnorm(size).t
  // Convert row-major flat array to breeze's column-major storage.
  // arr(r*ncol + c) → cm(c*nrow + r)  so element [r,c] has the same value as in uni (MatD).
  private def toColMajor(arr: Array[D], nrow: Int, ncol: Int): Array[D] = {
    val cm = Array.ofDim[D](nrow * ncol)
    for (r <- 0 until nrow; c <- 0 until ncol)
      cm(c * nrow + r) = arr(r * ncol + c)
    cm
  }
  def matrix(vec: Vec[D], nrow: Int): Mat[D] = {
    val ncol = vec.size / nrow
    new DenseMatrix(nrow, ncol, toColMajor(vec.toArray, nrow, ncol))
  }
  def matrix(vec: Vec[D], nrow: Int, ncol: Int, byrow: Boolean=true): Mat[D] =
    matrix(vec.toArray, nrow, ncol, byrow)
  def matrix(arr: Array[D], nrow: Int, ncol: Int, byrow: Boolean): Mat[D] =
    if byrow then new DenseMatrix(nrow, ncol, toColMajor(arr, nrow, ncol))
    else           new DenseMatrix(nrow, ncol, arr)
  def matrix(data: Array[D], nrow: Int, ncol: Int): Mat[D] =
    new DenseMatrix(nrow, ncol, toColMajor(data, nrow, ncol))
  def matrix(init: Double, nrow: Int, ncol: Int): Mat[D] = {
    val data: Array[Double] = Array.fill[Double](nrow * ncol)(init)
    new DenseMatrix(nrow, ncol, data)  // constant — layout doesn't matter
  }
  def matrix(vec: Vec[D], nrow: Int, ncol: Int): Mat[D] =
    new DenseMatrix(nrow, ncol, toColMajor(vec.toArray, nrow, ncol))

  def center(data: Mat[D]) = {
    val centers = mean(data,Axis._0).t.toDenseVector // means of each column (axis) of the data.
    // dumpVector("centers:\n%s\n",centers)
    data(*,::) - centers
  }
  def centerAndScale(X: Mat[D]) = {
    val X_mean = mean(X, Axis._0).t.toDenseVector // means of each column (axis) of the X.
    var centered = X(*,::) - X_mean
    centered(*,::) / stddev(centered(::,*)).t.toDenseVector
  }
//  def dumpVec(tag: String, v: Vec[D], max: Int = 8): Unit = {
//    val limit = if( max > 0 ) max else Int.MAXINT
//    val arr = vec.toArray.take(limit)
//    for(i <- 0 until arr.size){
//      printf(" %9.7f", arr(i))
//    }
//  }
  def dump4x4(tag: String, m: Mat[D]): Unit = {
    printf("%s: %s\n", tag, m.shape)
    var rnum = 0
    for (row <- m(*,::)){
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

  def leastSquares(data: Mat[D], outputs: Vec[D]) = {
    doLeastSquares(
      data.copy,
      outputs.copy,
      new Array[Double](math.max(1, data.rows * data.cols * 2)))
  }
  def doLeastSquares(data: DenseMatrix[Double], outputs: DenseVector[Double], workArray: Array[Double]): LeastSquaresRegressionResult = {
    import breeze.linalg.*
    import org.netlib.util.intW
    import dev.ludovic.netlib.lapack.LAPACK.{getInstance => lapack}
    import java.util.Arrays

    if( data.rows != outputs.size){
      hook += 1
    }
    require(data.rows == outputs.size)
    require(data.rows >= data.cols)
    require(workArray.length >= 2 * data.rows * data.cols)

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

    val coefficients = new DenseVector[Double](Arrays.copyOf(outputs.data, data.cols))
    var r2 = 0.0
    for (i <- 0 until (data.rows - data.cols)) {
      r2 = r2 + math.pow(outputs.data(data.cols + i), 2)
    }
    LeastSquaresRegressionResult(coefficients, r2)
  }
}
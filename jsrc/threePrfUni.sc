#!/usr/bin/env -S scala-cli shebang -Wunused:imports -deprecation

//> using scala 3.7.0
//> using javaOpt "--add-modules=jdk.incubator.vector"
//> using dep org.vastblue:uni_3:0.12.3


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
import uni.data.*
import uni.data.MatD.uniform






type D = Double





object ThreePrf {

  
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
  val sigma_g = MatD(1.25, 1.75, 2.25, 2.75)
  val sigma_y = 1
  var verbose = false


  def main(args: Array[String]): Unit = {
    MatD.setSeed(seed)
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
    val y_inf = allfactors *@ sim.beta + sim.beta_0
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

      val X = _X.scale(center, scale)

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

  case class PredReg(alpha_hat: MatD, y_hat: MatD, residuals: MatD, loadings: MatD, factors: MatD)

  def autoProxies(n_proxy: Int, X: MatD, y: VecD, pls: Boolean=false, closed_form: Boolean=true, fitalg: Int=2): MatD = {
    // Computing automatic proxies
    val r = MatD(y.size, n_proxy)
    r(::, 0)  = y // r[, 1] <- y
    printf("auprx:X             %s\n", X.shapex)
    printf("auprx:y             %s\n", y.shapex)
    printf("auprx:r             %s\n", r.shapex)
    for (k <- 1 until n_proxy) {
      val result = leastSquares(X, y) // fit
      /* r[, k] <- resid(.tprf_fit(X, y, r[, k - 1], pls=pls, closed_form=closed_form, fitalg=fitalg)) */
      val slice: VecD = r(::, k - 1)
      val mat = MatD(slice.size, 1, slice.toArray) // we're appending columns
      val fit: PredReg = tprf_fit(X, y, mat, pls, closed_form, fitalg)
      r(::, k) = fit.residuals
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
    printf("fit_iter:X          %s\n", X.shapex)
    printf("fit_iter:y          %s\n", y.shapex)
    printf("fit_iter:Z          %s\n", Z.shapex)

    val plsNum = if (pls) 0 else 1 // no intercept if pls

    // Pass 1 Time series regression
    // Preallocating loadings
    val loadings = MatrixNaN(X.cols, Z.cols + plsNum)
    printf("fit_iter:loadings:  %s\n", loadings.shapex)
    
    if (pls) {
      for (j <- 0 until loadings.rows) {
        var Zm: MatD = Z.copy
        Zm :+= -1.0
        val Xj = X(::, j)
        val result = leastSquares(Zm, Xj) // fit
        val coefs = result.coefficients
        if (j==0) printf("fit_iter:coeffs:    %s\n", coefs.shapex)
        //val coefs = coef(lm(formula=X(:: , j) ~ Z - 1, na.action=na.exclude, model=false))
        loadings(j, ::) = coefs.T // row vector
      }
    } else {
      loadings(::, 0) = MatD.ones(loadings.rows)
      for (j <- 1 until loadings.rows) {
        var Zm: MatD = prependOnesColumn(Z.copy)
        Zm :+= 1.0
        val Xj = X(::, j)
        val result = leastSquares(Zm, Xj) // fit
        val coefs = result.coefficients
        if (j==0) printf("fit_iter:coeffs:    %s\n", coefs.shapex)
        // coef( lm( formula = X(::, j) ~ 1 + Z, na.action=na.exclude, model=false))
        loadings(j, ::) = coefs.T
      }
    }

    // Pass II Cross section regression
    var factors = MatrixNaN(X.rows, loadings.cols)

    // Loadings has no intercept in pls
    if (pls) {
      for (i <- 0 until factors.rows) {
        var L1 = loadings.copy
        L1 :+= -1.0
        val result = leastSquares(L1, X(i, ::).T) // fit
        val coeffs = result.coefficients
        //factors[i, ] <- coef(lm(formula=X[i,] ~ loadings - 1,       na.action=na.exclude, model=false))
        factors(i, ::) = coeffs.T
      }
    } else {
      for (i <- 1 until factors.rows) {
        var L1 = loadings.copy
//        L1  :+= 1.0
        val result = leastSquares(L1, X(i, ::).T) // fit
        val coefs = result.coefficients
        if (i==1) printf("fit_iter:L1:        %s\n", L1.shapex)
        if (i==1) printf("fit_iter:coefs:     %s\n", coefs.shapex)
        //factors[i, ] <- coef(lm(formula=X[i,] ~ 1 + loadings[, -1], na.action=na.exclude, model=false))
        factors(i, ::) = coefs.T
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
    val predreg = PredReg(NullMat, NullMat, NullMat, loadings, factors)
    predreg
  }
  lazy val NullMat = MatD(0, 0)

  def J(T: Double): MatD = {
    val Iᴛ: MatD = MatD.eye(T.toInt) // MatD (a row vector)
    val ιᴛ = MatD.ones(T.toInt)
    val Jᴛ = Iᴛ - 1.0/T * ιᴛ *@ ιᴛ.T
    Jᴛ
  }
  /*
  def J2(T: Double): MatD = { // unused? translated from R
    val vec1: MatD = MatD(VecD(T.toInt))
    val ones: MatD = MatD.ones(T.toInt, T.toInt)
    printf("vec1.shape:         %s\n", vec1.shape)
    printf("ones.shape:         %s\n", ones.shape)
    diag(vec1 - 1.0/T *@ ones) // compile error with scala 3.2.1
  }
  */

  //
  def tprf_fit_closed(Xorig: MatD, y: VecD, Z: MatD, pls: Boolean = false): PredReg = {
    val X = Xorig.copy
    printf("fit_closed:X:       %s\n", X.shapex)
    printf("fit_closed:y:       %s\n", y.shapex)
    printf("fit_closed:Z:       %s\n", Z.shapex)
    var (y_hat, alpha_hat) = if (pls) {
      val XX = X *@ X.T
      val part1 = XX.T *@ y
      val part2: Double = 1.0 / (y.T *@ XX *@ XX *@ y)(0, 0)
      val part3: Double = (y.T *@ XX *@ y)(0, 0)
      val y_hat = part1 * (part2 * part3) + y.mean // Should y be demeaned?
      val alpha_hat = MatD.zeros(y_hat.rows, 1)
      (y_hat, alpha_hat)

    } else {
      val T = X.rows
      val N = X.cols
      val Jn = J(N)
      val Jt = J(T)
      printf("fit_closed:Jn:      %s\n", Jn.shapex)
      printf("fit_closed:X.T:     %s\n", X.T.shapex)
      printf("fit_closed:Jt:      %s\n", Jt.shapex)
      printf("fit_closed:Z:       %s\n", X.shapex)
      val wxzRite = Jt *@ Z
      printf("fit_closed:wxzRite: %s\n", wxzRite.shapex)
      val Xt = X.T
      val wxzLeft = Jn *@ Xt
      printf("fit_closed:wxzLeft: %s\n", wxzLeft.shapex)
      val W_XZ = wxzLeft *@ wxzRite // val W_XZ = Jn.T *@ X.T *@ Jt.T *@ Z
      val S_XX = Xt *@ Jt *@ X
      val S_Xy = Xt *@ Jt *@ y
      val alpha_hat = W_XZ *@ (W_XZ.T *@ S_XX *@ W_XZ).inverse *@ W_XZ.T *@ S_Xy
      val y_hat = Jt *@ X *@ alpha_hat + y.mean
      (y_hat, alpha_hat)

    }
    val fitted = y_hat
    val residuals = y - y_hat

    PredReg(alpha_hat, y_hat, residuals, NullMat, NullMat) // residuals, loadings, factors)
  }

  //###################################################
  //## Three Pass Regression Filter Model Simulation ##
  //###################################################
  def sim_factors(T: Int, K_f: Int=1, rho_f:Double=0.0, rho_g:Double=0.0, sigma_g: MatD= sigma_g): Seq[MatD] = {
    //## * Simulating relevant factor innovations
    val u_f = matrix(rnorm(T * K_f), nrow=T, ncol=K_f)
    val f: MatD = matrix(0, nrow=T, ncol=K_f)
    f(0, ::) = u_f(0, ::)
    for (i <- 1 until f.rows){
      val prevrow: VecD = f(i - 1, ::).T * rho_f
      val prevu: VecD = u_f(i - 1, ::).T
      f(i, ::) = (prevrow + prevu).T
    }

    //##  * Simulating irrelevant factors innovations
    val K_g = sigma_g.size

    //## Variance have to be adjusted so that the variance of each irrelevant
    //## factor is greater than the variance of the relevant factor by the
    //## coefficients given in sigma_g
    val col0: VecD = f(::, 0)
    val col0variance = variance(col0) * sigma_g

    // the next 2 probably not needed? PMW
    val col0varDiag: MatD = MatD.diag(col0variance)
//    val sigma_g_mat: VecD = diag(col0varDiag)
//    printf("sigma_g_mat    %s\n", sigma_g_mat.shapex)
    printf("sim_f:col0varDiag   %s\n", col0varDiag.shapex)

    var g: List[MatD] = if (K_g > 0) {
      val rn = rnorm(T * K_g)
      printf("sim_f:rn            %s\n", rn.shapex)
      val sigma_g = col0varDiag // sigma_g is a diagonal matrix inside this block!
      dump4x4("sigma_g", sigma_g)
      val sigma_g_sqrt: MatD = sigma_g.sqrt
      val matRnorm: MatD = matrix(rnorm(T * K_g), nrow=T, ncol=K_g)
      printf("sim_f:matRnorm      %s\n", matRnorm.shapex)
//      val u_g: MatD = matRnorm.T *@ sigma_g_sqrt
      printf("sim_f:sigma_g_sqrt  %s\n", sigma_g_sqrt.shapex)
      val u_g: MatD = matrix(rnorm(T * K_g), nrow=T, ncol=K_g) *@ sigma_g_sqrt
      printf("sim_f:u_g           %s\n", u_g.shapex)
      val u_gtRow0: VecD = u_g(0, ::).T // row vector
      val u_g_row0 = u_g(0, ::)
      val g = MatD.zeros(T, K_g)
      printf("sim_f:u_gtRow0      %s\n", u_gtRow0.shapex)
      printf("sim_f:g             %s\n", g.shapex)
      printf("sim_f:u_g_row0      %s\n", u_g_row0.shapex)
      g(0, ::) = u_g_row0
      for(i <- 1 until g.rows) {
        try {
          val gprevRow = g(i-1, ::)
          if (i==1) printf("sim_f:gprevRow      %s\n", gprevRow.shapex)
          val u_giRow = u_g(i, ::)
          if (i==1) printf("sim_f:u_giRow       %s\n", u_giRow.shapex)
          val gnuRow = gprevRow * rho_g + u_giRow
          if (i==1) printf("sim_f:gnuRow        %s\n", gnuRow.shapex)
  //        if (i==1) printf("gnuRow.T   %s\n", gnuRow.T.shapex)
          g(i, ::) = gnuRow
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
    val Fxbeta = F *@ beta
    printf("sim_t:F             %s\n", F.shapex)
    printf("sim_t:beta          %s\n", beta.shapex)
    printf("sim_t:Fxbeta        %s\n", Fxbeta.shapex)
    printf("sim_t:u_y           %s\n", u_y.shapex)

    val y = Fxbeta + beta_0 + sigma_y.toDouble * u_y
    printf("sim_t:y             %s\n", y.shapex)
    assert(y.cols==1, s"too many columns: ${y.cols}: $y")
    y(::, 0) // there should only be one column
  }
  
  // partially apply R %*% multiplication rules
  def rMult(m1: MatD, m2: MatD): MatD = {
    if(m1.cols == 1 && m2.cols == 1){
      val v1 = m1(::, 0)
      val v2 = m2(::, 0)
      MatD(v1.T *@ v2)
    } else {
      m1 *@ m2
    }
  }

  //##' @export
  def sim_observations(N: Int, factors: Seq[MatD], phi_0: Double, phi: MatD): MatD = {
    printf("sim_o:factors$f     %s\n", factors(0).shapex)
    if (factors.size>1) printf("sim_o:factors$g     %s\n", factors(1).shapex)
    val F = mergeFactorColumns(factors)
    val T = F.rows
    val K = F.cols
    /*
    val (f,g) = factors.toList match {
      case f :: Nil => (f, MatD(0, 0, Array.empty))
      case f :: g :: Nil => (f, g)
      case _ => sys.error(s"bad factors Seq: ${factors}")
    }
    printf("sim_o:factors$f %s\n", factors(0).shapex)
    if (factors.size>1) printf("sim_o:factors$g %s\n", factors(1).shapex)
    val T: Int = f.rows
    val K: Int = factors.map(_.cols).sum
    printf("K:                  %5d\n", K)
    val F: MatD = c(f, g)
    */
    val epsilon: MatD = matrix(rnorm(T * N), nrow=T, ncol=N)
    printf("sim_o:rnorm(T * N)  %5d x %5d\n", T * N, 1)
    printf("sim_o:epsilon       %s\n", epsilon.shapex)
    printf("sim_o:F             %s\n", F.shapex)
    printf("sim_o:phi           %s\n", phi.shapex)
    val FxphiT = F *@ phi.T
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

  //##'  @export
  def sim_problem(T: Int, N: Int, K_f: Int, sigma_g: MatD, L: Int=2, sigma_y: Int=1): Simulation = {
    assert(L > 0, s"error: L <= 0, but at least one proxy is required")
    //## Simulate Factors
    val factors: Seq[MatD] @unchecked = sim_factors(T, K_f, sigma_g=sigma_g)
    for ((fact, i) <- factors.zipWithIndex) {
      printf("sim_p:factors(%d)    %s\n", i, fact.shapex)
    }
    val F = mergeFactorColumns(factors)
    val K = F.cols
    printf("sim_p:K:                    %5d\n", K)

    //## Simulate observations
    val phi_0: D = runifDbl(1, -1, 1)
    printf("sim_p:N x K:        %5d x %5d\n", N, K)
    printf("sim_p:runif(N * K)  %5d x %5d\n", N * K, 1)
    val phi = matrix(runif(N * K, -1, 1), nrow=N, ncol=K)
    printf("sim_p:phi.T         %s\n", phi.T.shapex)
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
    phi_0: Double,
    phi: MatD,
    X: MatD,
    K: Int,
    K_f: Int,
    K_g: Int,
    sigma_g: MatD,
    lambda_0: Double,
    lambda: MatD,
    Z: MatD,
    beta_0: Double,
    beta: MatD,
    y: VecD,
    sigma_y: Double
  )
  //////////////////////////////////////////////////
  def prependOnesColumn(original: MatD): MatD = {
    val ones = MatD.ones(original.rows, 1)
    val dataWithOnes = ones.data ++ original.data
    MatD(original.rows, original.cols + 1, dataWithOnes)
  }
  def runif(n: Int, min: Double=0.0, max: Double=1.0): Array[Double] = {
    uniform(min, max, n, 1).flatten
  }
  def runifDbl(n: Int, min: Double, max: Double): Double = {
    uniform(min, max, 1, 1)(0, 0)
  }
  private def variance(v: MatD): Double = {
    val n = v.size
    val mu = v.sum / n
    ((v - mu) ~^ 2.0).sum / (n - 1).max(1)
  }
  def rep(d: Double, n: Int): Array[Double] = {
    val nums = for {
      i <- (0 until n)
    } yield d
    nums.toArray
  }
  def rep(x: VecD, n: Int): Array[Double] = {
    val nums = for {
      (_,d) <- (0 until n) zip x.toArray
    } yield d
    nums.toArray
  }
  // this must append the 2nd array as additional columns
  def c(arr1: Array[Double], arr2: Array[Double]): Array[Double] = {
    (arr1 ++ arr2).toArray
  }
  def cbind(mats: Seq[MatD]): MatD = {
    val cols = mats.map{_.cols}.sum
    val rows = mats.head.rows
    var numat = MatD.zeros(rows, cols)
    var prevcols = 0
    for ((mat,m) <- mats.zipWithIndex){
      assert(mat.rows == rows, s"mat.rows[${mat.rows}] != rows[$rows]")
      for(i <- 0 until mat.cols){
        numat(::, i+prevcols) = mat(::, i)
      }
      prevcols += mat.cols
    }
    numat
  }

  def c(arr1: MatD, arr2: MatD): MatD = {
    assert(arr1.rows == arr2.rows,s"error: arr1.rows[${arr1.rows}] != arr2.rows[${arr2.rows}]")
    var rows = arr1.rows
    val cols = arr1.cols + arr2.cols
    var both = MatD.zeros(rows, cols)
    for(i <- 0 until arr1.cols){
      both(::, i) = arr1(::, i)
    }
    for(i <- 0 until arr2.cols){
      both(::, i+arr1.cols) = arr2(::, i)
    }
    both
  }

  def MatrixNaN(rows: Int, cols: Int): MatD = {
    MatD.full(rows, cols, Double.NaN)
  }
  def rnorm(size: Int): VecD = {
    MatD.randn(size)
  }
  def rnormRow(size: Int): RVecD = {
    MatD.randn(size).T // row vector
  }
  def matrix(vec: VecD, nrow: Int): MatD = {
    val ncol = vec.size / nrow
    matrix(vec.toArray, nrow, ncol, byrow=false)
  }
  def matrix(vec: VecD, nrow: Int, ncol: Int, byrow: Boolean=false): MatD = {
    matrix(vec.toArray, nrow, ncol, byrow)
  }
  def matrix(arr: Array[Double], nrow: Int, ncol: Int, byrow: Boolean): MatD = {
    if (byrow){
      // transpose array data
      var transposed: Array[Double] = Array.ofDim[Double](nrow * ncol)
   // printf("shape: %s x %s\n", nrow, ncol)
   // printf("arr.size: %d\n", transposed.length)
   // printf("%d\n", nrow * ncol)
      assert(nrow * ncol == arr.size)
      var k = 0
      for (i <- 0 until ncol){
        for (j <- 0 until nrow){
          val idx = i + j * ncol
          val d = arr(k) ; k += 1
          val odx = i * nrow + j
          //printf("%d x %d: %2d/%2d: %s\n", i,j, idx,odx, d)
          transposed(odx) = d
        }
      }
      MatD(nrow, ncol, transposed)
    } else {
      // by column is the breeze default
      MatD(nrow, ncol, arr)
    }
  }
  def matrix(data: Array[Double], nrow: Int, ncol: Int): MatD = {
    MatD(nrow, ncol, data)
  }
  def matrix(init: Double, nrow: Int, ncol: Int): MatD = {
    val data: Array[Double] = Array.fill(nrow * ncol)(init)
    MatD(nrow, ncol, data)
  }
  def matrix(vec: VecD, nrow: Int, ncol: Int): MatD = {
    MatD(nrow, ncol, vec.toArray)
  }

  def center(data: MatD) = {
    val centers = data.mean(axis = 0).T // means of each column (axis) of the data.
    // dumpVector("centers:\n%s\n",centers)
    data(*, ::) - centers
  }
  def centerAndScale(X: MatD) = {
    val X_mean = X.mean(axis = 0).T // means of each column (axis) of the X.
    val centered = X(*, ::) - X_mean
    centered(*, ::) / centered.std(axis = 0)
  }
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
  def dump4x4_(tag: String, m: MatD): Unit = {
    printf("%s: %s\n", tag, m.shape)
    for (rnum <- 1 to m.rows.min(4)) {
      val row = m(rnum - 1, ::)
      printf("[%d,] ", rnum)
      for ((c, j) <- row.toArray.take(4).zipWithIndex) {
        printf(" %9.7f", c)
      }
      printf("\n")
    }
  }
  extension(m: MatD)
    def shapex: String = "%5d x %5d".format(m.rows, m.cols)
}
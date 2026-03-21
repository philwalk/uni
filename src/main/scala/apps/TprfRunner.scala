package uni.data

import uni.*
import uni.data.*
import uni.stats.Tprf3.t3prf
import scala.collection.immutable.Vector as VecSeq
import scala.collection.parallel.CollectionConverters.*
import scala.collection.parallel.ForkJoinTaskSupport
import java.util.concurrent.ForkJoinPool

object TprfRunner {
  private var dir_tag = 0
  private var prevPct = ""
  var t0 = System.currentTimeMillis

  def usage(m: String=""): Nothing = {
    showUsage(m, "",
      "[-v]          ; verbose",
      "[-i<iter>]    ; number of iterations",
      "[-p<proxies>] ; number of proxies",
      "[-t<tncount>] ; dimension of almost square matrices",
      "[-z]          ; dump generated random data",
    )
  }
  var verbose = false
  private var dumpData = false // short-circuit the loop, in order to dump generated random data
  private var n_iter = 200
  private var n_proxies = 2 // Z=3
  private var tncount = 200

  def main(args: Array[String]): Unit =
    try {
      runner()
    } catch {
    case t: Throwable =>
      showLimitedStack(t)
      sys.exit(1)
    }

  def parseArgs(args: Array[String]): Unit =
    eachArg(args.toSeq, usage) {
      case "-v" =>
        verbose = true
      case "-z" =>
        dumpData = true
      case s if s.startsWith("-i") =>
        n_iter = thisArg.drop(2).toInt
      case s if s.startsWith("-p") =>
        n_proxies = thisArg.drop(2).toInt
      case s if s.startsWith("-t") =>
        tncount = thisArg.drop(2).toInt
      case arg =>
        usage(s"unrecognized arg [$arg]")
    }

  /** Population standard deviation of all elements in a matrix. */
  private def popStd(m: MatD): Double =
    val n = m.rows * m.cols
    val mu = m.sum / n
    math.sqrt(((m - mu) ~^ 2.0).sum / n)

  private def median(seq: Seq[Double]): Double =
    val sorted = seq.sorted
    val n = sorted.size
    if n % 2 == 1 then sorted(n / 2) else (sorted(n / 2 - 1) + sorted(n / 2)) / 2.0

  // calculate r-squared for TPRF with random data generated with serial correlation
  private def runner(): Unit = {
    /*
    Generate a list of parameter combinations to be used.
    Parameters: pf, pg, a, d
    The naming convention is same as the one in paper and mentioned in data_gen.py
    */
    Mat.setSeed(0)

    val p_values = Seq(0.3, 0.9)
    var combos = VecSeq.empty[Combo]
    for (i <- Seq(0, 1)) do
      for (a <- p_values) do
        for (d <- Seq(0.0, 1.0)) do
          val pvi = p_values(i)
          val alt_idx = (1-i) % p_values.size
          assert(alt_idx != i && alt_idx >= 0)
          val pvj = p_values(alt_idx)
          combos = combos :+ Combo(pf = pvi, pg = pvj, a = a, d = d)

    combos +:= Combo() // prepend a Combo with default values

    // Define the base data dictionary with common parameters across all combinations
    val data_dict = Parms(
      // when rows == cols, it can mask matrix transpose bugs
      T = tncount + 1,
      N = tncount - 1,
      relevant_factors = 1,
      irr_factors = 4
    )

    var non_pervasive = Seq.empty[Boolean]

    val save_dir = nextSaveDir()
    java.nio.file.Files.createDirectories(Paths.get(save_dir))
    printf("save_dir: %s\n", save_dir)

    for (strength <- Seq[Double](4.0, 2.33)) {
      if (strength == 4.0)
        non_pervasive = Seq(false, true)
      else
        non_pervasive = Seq(false)

      for (j <- non_pervasive) {
        var df = VecSeq.empty[Result]

        for (combo <- combos) {
          data_dict.pf = combo.pf
          data_dict.pg = combo.pg
          data_dict.a = combo.a
          data_dict.d = combo.d
          data_dict.non_pervasive = j
          data_dict.strength = strength

          var r2_tprf_lst = VecSeq.empty[Double]

          printf("%s\n", s"n_proxies, strength, non_pervasive, combo: $n_proxies, $strength, $j, $combo")

          if (dumpData) {
            val (_X, y) = data_generator(data_dict); val X = _X
            pydump(X, y)
          } else {
            val train_window = data_dict.T / 2
            // Generate datasets sequentially (preserves RNG order); fast, no progress needed
            val datasets = (0 until n_iter).map { _ => data_generator(data_dict) }.toVector
            // Compute in parallel; cap outer threads so BLAS has room (avoids oversubscription)
            val nThreads = (Runtime.getRuntime.availableProcessors / 2).max(2)
            t0 = System.currentTimeMillis  // reset: measure computation, not data-gen
            val counter = new java.util.concurrent.atomic.AtomicInteger(0)
            val parDs = datasets.par
            parDs.tasksupport = new ForkJoinTaskSupport(new ForkJoinPool(nThreads))
            val results = parDs.map { case (xRaw, y) =>
              val X    = xRaw
              val r    = recursiveTrainAuto(X, y, train_window, n_proxies)
              val done = counter.incrementAndGet()
              val pct  = (done * 100) / n_iter
              val prev = ((done - 1) * 100) / n_iter
              if pct != prev then printf("\r%3d%% (%1.4f s/iter)", pct, seconds / done.toDouble)
              r
            }.seq.toVector
            r2_tprf_lst = r2_tprf_lst ++ results
          }
          if (!dumpData) {
            val tprf_result: Double = median(r2_tprf_lst) * 100
            printf(" median TPRF: %9.4f\n", tprf_result)
            val tprf_name = s"3PRF$n_proxies"
            val data_row = Result(data_dict.pf, data_dict.pg, data_dict.a, data_dict.d, tprf_result, tprf_name)
            df :+= data_row
          }
        }

        if (!dumpData) {
          val sfx = "csv"
          val fname = if strength == 4.0 then
            if j then s"moderate_non_pervasive.$sfx" else s"moderate.$sfx"
          else if strength == 9.0 then
            s"weak.$sfx"
          else
            s"normal.$sfx"

          val save_file = Paths.get(save_dir.posx, fname)
          withFileWriter(save_file) { w =>
            w.printf("%s\n", Result.colnames(n_proxies).mkString(","))
            for (result <- df) {
              w.printf("%s\n", result.toString)
            }
          }
          printf("Saved: %s\n", save_file.posx)
        }
      }
    }
  }

  private def data_generator(data_dict: Parms): (MatD, MatD) = {
    /*
    Function to generate data samples, governed by the parameters passed through
    the data_dict, based on the setup mentioned in the paper.
    :return X: Array of predictor values over a time period (Shape: TxN)
    :return y: Array of returns (Shape: Tx1)
    */
    val T: Int = data_dict.T
    val N: Int = data_dict.N
    val pf = data_dict.pf
    val pg = data_dict.pg
    val a = data_dict.a
    val d = data_dict.d
    val strength = data_dict.strength
    val v: MatD = MatD.randn(T, N)

    val n_factor_loadings = data_dict.relevant_factors + data_dict.irr_factors
    val factor_loadings: MatD = MatD.randn(n_factor_loadings, N)

    if data_dict.non_pervasive then
      // randomly zero half of the loadings in row 0
      val set_zero_index = scala.util.Random.shuffle((0 until N).toList).take(N / 2)
      for idx <- set_zero_index do
        factor_loadings(0, idx) = 0.0

    val f = MatD.zeros(T, data_dict.relevant_factors)
    val g = MatD.zeros(T, data_dict.irr_factors)

    if data_dict.relevant_factors > 0 then
      f(0, ::) = MatD.randn(data_dict.relevant_factors, 1)

    val g_var = IndexedSeq(1.25, 1.75, 2.25, 2.75)
    val fNoise = MatD.randn(T - 1, data_dict.relevant_factors) // batch: one draw per step
    for t <- 1 until T do
      f(t, ::) = f(t - 1, ::) * pf + fNoise(t - 1, ::)

    g(0, ::) = MatD.randn(data_dict.irr_factors, 1)
    val g_err = MatD.zeros(T, data_dict.irr_factors)
    for i <- 0 until 4 do
      g_err(::, i) = MatD.randn(T, 1)
    val fStd = popStd(f) // hoisted: computed once, used in g scaling and sigma_y
    for j <- 0 until data_dict.irr_factors do
      for t <- 1 until T do
        g(t, j) = g(t - 1, j) * pg + g_err(t, j)

      // Adjust the irrelevant factors for higher variance than relevant factors
      val gcolj: MatD   = g(::, j)
      val scale: Double = fStd * math.sqrt(g_var(j)) / popStd(gcolj)
      g(::, j) = gcolj * scale

    // Generate the returns y as a function of relevant factors plus noise
    val y = MatD.zeros(T + 1, 1)
    val sigma_y: Double = fStd
    val yNoise = MatD.randn(T, 1) // batch: one draw per time step
    for t <- 1 until T + 1 do
      y(t, 0) = f(t - 1, ::).mean + sigma_y * yNoise(t - 1, 0)

    // Generate the idiosyncratic errors
    // Boundary condition is cyclic (prev of col 0 = col N-1, next of col N-1 = col 0),
    // matching the Python original where arr[-1] refers to the last element.
    val eta_tilda: MatD =
      if d == 0.0 then v  // (1 + 0²)*v + 0*(...) = v
      else
        val vPrev = MatD.zeros(T, N)
        val vNext = MatD.zeros(T, N)
        for i <- 0 until N do
          vPrev(::, i) = v(::, if i == 0 then N - 1 else i - 1)
          vNext(::, i) = v(::, if i == N - 1 then 0 else i + 1)
        (1 + d * d) * v + d * (vPrev + vNext)

    val eta = MatD.zeros(T, N)
    eta(0, ::) = MatD.randn(N, 1)
    for t <- 1 until T do
      eta(t, ::) = eta(t - 1, ::) * a + eta_tilda(t, ::)

    val factors  = MatD.hstack(f, g)
    val X1       = factors *@ factor_loadings
    val etaNorm  = eta / popStd(eta)
    val constant = popStd(X1) * math.sqrt(strength)
    val X        = X1 + constant * etaNorm
    (X, y(1 until y.rows, ::))
  }

  private def autoproxy(X: MatD, y: MatD, n_proxy: Int): MatD =
    var r0: MatD = y
    for _ <- 1 until n_proxy do
      val yhat: MatD      = t3prf(y, X, r0).y_hat
      val residuals: MatD = y - yhat
      r0 = MatD.hstack(residuals, r0)
    r0

  private def tprf(X: MatD, y: MatD, Z: MatD, oos: MatD): (MatD, Double) =
    val model = t3prf(y, X, Z)
    val yhatt = if oos.rows > 1 then model.estimateYhat(oos) else Double.NaN
    (model.y_hat, yhatt)

  private def rr2(y_true: MatD, yhatt: MatD): Double =
    val residuals = y_true - yhatt
    val rss = (residuals ~^ 2.0).sum
    val ssy = ((y_true - y_true.mean) ~^ 2.0).sum
    (ssy - rss) / ssy

  private def recursiveTrainAuto(X: MatD, y: MatD, train_window: Int, n_proxies: Int): Double =
    assert(n_proxies > 0, s"n_proxies[$n_proxies]")
    val lst = Array.ofDim[Double](X.rows - train_window)
    for t <- train_window until X.rows do
      val Z      = autoproxy(X(0 until t, ::), y(0 until t, ::), n_proxies)
      val (_, yhatt) = tprf(X(0 until t, ::), y(0 until t, ::), Z, X(t, ::).T)
      lst(t - train_window) = yhatt
    rr2(y(train_window until y.rows, ::), Mat.create(lst, lst.length, 1))

  private case class Combo(
    var pf: Double = 0, // Serial correlation in relevant factors
    var pg: Double = 0, // Serial correlation in irrelevant factors
    var a: Double = 0,  // Serial correlation between idiosyncratic errors
    var d: Double = 0,  // Cross correlation parameter
  ) {
    override def toString: String = "pf: %1.1f, pg: %1.1f, a: %1.1f, d: %1.1f".format(pf, pg, a, d)
  }
  case class Parms(
    var T: Int = 0,
    var N: Int = 0,
    var pf: Double = 0,
    var pg: Double = 0,
    var d: Double = 0,
    var a: Double = 0,
    var sigma_y: Double = 0,
    var relevant_factors: Int = 0,
    var irr_factors: Int = 0,
    var non_pervasive: Boolean = false,
    var strength: Double = 0,
  )
  private object Result {
    def colnames(n_proxies: Int): Seq[String] = Seq("pf", "pg", "a", "d", s"3PRF$n_proxies-rsquared")
    def apply(): Result = new Result(0, 0, 0, 0, 0)
  }
  private def fmt(v: Double): String = if v % 1.0 == 0.0 then v.toLong.toString else v.toString

  case class Result(
    var pf: Double = 0,
    var pg: Double = 0,
    var a: Double = 0,
    var d: Double = 0,
    var tprf_result: Double = 0,
    var tag: String = ""
  ) {
    override def toString: String = s"${fmt(pf)},${fmt(pg)},${fmt(a)},${fmt(d)},$tprf_result"
  }

  private def pydump(X: MatD, y: MatD): Unit = {
    println(s"X:\n${X.show("% 14.7f")}")
    println(s"y:\n${y.show("% 14.7f")}")
    sys.exit(1)
  }

  given Conversion[Int, Double] with
    def apply(i: Int): Double = i.toDouble
  given Conversion[Long, Double] with
    def apply(i: Long): Double = i.toDouble

  private lazy val save_dir_base = Paths.get(s"data_${n_iter}_scala").posx
  private def nextSaveDir(): String = {
    var dirpath = "%s_%02d".format(save_dir_base, dir_tag)
    while (dirpath.path.exists) {
      dir_tag += 1
      dirpath = "%s_%02d".format(save_dir_base, dir_tag)
    }
    dirpath
  }
  def seconds = (System.currentTimeMillis - t0).toDouble / 1000.0
  // simple progress bar
  def progBar(i: Int): Unit = {
    val pct = "%2.0f".format((100 * i.toDouble) / n_iter.toDouble)
    if (pct != prevPct) {
      val denom = i.max(1).toDouble
      val secPerIteration = seconds / denom
      prevPct = pct
      if (i > 0) {
        printf("\r%s %% (%1.4f s/iter)", pct, secPerIteration)
      }
    }
  }
}

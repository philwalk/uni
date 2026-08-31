#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.22.1

import uni.*
import uni.data.*
import uni.stats.Tprf3
import scala.util.Random

/**
 * The two routes *within* uni.stats.Tprf3 to the PLS-variant 3PRF, across three
 * predictor counts, with a scalar PLS-1 as the correctness reference.
 *
 * For hand-rolled PLS-1 implementations benchmarked against the 3PRF, see
 * jsrc/pls1tprfBench.sc — that one compares direct PLS-1 code to the library;
 * this one compares the library's own two paths to each other.
 *
 * The three arms are the same estimator, not three estimators:
 *
 *   scalar      hand-rolled textbook PLS-1 (standardize X, center y,
 *               w = Xz'yc normalized, t = Xz*w, b = t'yc/t't) — the reference
 *   closed      Tprf3.pls1Fit — the 3PRF pls variant (autoproxy L=1, no
 *               intercept in passes 1-2) in vectorized closed form
 *   iterative   Tprf3.estimate3prf(Left(1), pls = true) — the same 3PRF via
 *               the general entry point, N+T separate OLS solves
 *
 * Note this is NOT t3prf(y, X, Z = y). That is a different estimator: its
 * pass 2 uses withIntercept(phi), which cross-sectionally demeans (the J(N)
 * projection) where PLS-1 does not, so its forecasts genuinely differ.
 *
 * N=4 is included deliberately: the iterative arm used to gate its pass-2 solve
 * on minObs (default 10) even though pass 2 is cross-sectional with N
 * observations, so it returned all-NaN for N < 10. Fixed in 0.14.2; the n/a
 * branch below is kept as a guard against that regressing.
 *
 * Run:  scala-cli jsrc/pls1tprfInternalBench.sc
 */
object Pls1tprfInternalBench:
  def println(s: String = ""): Unit = print(s"$s\n")

  def usage(m: String = ""): Nothing =
    showUsage(m, "",
      "[-rows <n>]   ; training rows, default 1000",
      "[-fits <n>]   ; fit iterations, default 2000",
      "[-preds <n>]  ; predict iterations, default 2000000",
    )

  private val tol = 1e-9

  // Default rows ~= 20 years of weekly observations, the frequency the
  // downstream vol models run at.
  case class Config(rows: Int = 1000, fits: Int = 2000, preds: Int = 2000000)

  case class Dataset(x: Array[Array[Double]], y: Array[Double], testRow: Array[Double]):
    def rows: Int  = x.length
    def cols: Int  = x(0).length
    def matX: MatD = MatD(rows, cols, x.flatten)
    def matY: MatD = MatD(rows, 1, y)

  def dataset(rows: Int, cols: Int, seed: Int): Dataset =
    val rand = new Random(seed)
    Dataset(
      Array.fill(rows, cols)(rand.nextGaussian() * 2.0),
      Array.fill(rows)(rand.nextGaussian() * 1.5),
      Array.fill(cols)(rand.nextGaussian() * 2.0),
    )

  /** Textbook one-component PLS-1; returns the fitted predictor. */
  def pls1Scalar(x: Array[Array[Double]], y: Array[Double]): Array[Double] => Double =
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

  /** Elapsed ms per call, after warming up.
   *
   *  The warm-up floor is absolute, not a fraction of `reps`: at a low -fits the
   *  earlier reps/10 rule warmed only tens of iterations, leaving the timed loop
   *  running interpreted and overstating every arm by several-fold (and by
   *  different factors per arm, so the ratios were wrong too). */
  def timed(reps: Int)(body: => Unit): Double =
    val warmup = math.max(reps / 10, 5000)
    var i = 0
    while i < warmup do { body; i += 1 }
    val start = System.nanoTime()
    i = 0
    while i < reps do { body; i += 1 }
    (System.nanoTime() - start) / 1e6 / reps

  /** All three arms must agree before any timing is meaningful. */
  def verify(d: Dataset): Unit =
    val ref   = pls1Scalar(d.x, d.y)
    val model = Tprf3.pls1Fit(d.x, d.y)
    val iter  = Tprf3.estimate3prf(d.matY, d.matX, Left(1), pls = true)

    val dOos = math.abs(ref(d.testRow) - model.predict(d.testRow))
    assert(dOos < tol, s"closed form drifts from PLS-1 out-of-sample by $dOos")

    val dIn = (0 until d.rows).map(i => math.abs(ref(d.x(i)) - model.forecasts(i, 0))).max
    assert(dIn < tol, s"closed form drifts from PLS-1 in-sample by $dIn")

    val f0 = iter.forecasts(0, 0)
    if f0.isNaN then
      println(f"  N=${d.cols}%-3d scalar == closed (max diff ${dIn}%.2e); iterative n/a (NaN below minObs=10)")
    else
      val dIter = math.abs(model.forecasts(0, 0) - f0)
      assert(dIter < 1e-8, s"iterative drifts from closed form by $dIter")
      println(f"  N=${d.cols}%-3d scalar == closed == iterative (max diff ${dIn}%.2e)")

  def benchFits(d: Dataset, cfg: Config): Unit =
    val scalarMs = timed(cfg.fits)(pls1Scalar(d.x, d.y))
    val closedMs = timed(cfg.fits)(Tprf3.pls1Fit(d.x, d.y))
    val iterMs   = timed(cfg.fits)(Tprf3.estimate3prf(d.matY, d.matX, Left(1), pls = true))
    println(f"  N=${d.cols}%-3d scalar ${scalarMs}%7.3f   closed ${closedMs}%7.3f   iterative ${iterMs}%7.3f" +
            f"   closed vs iterative ${iterMs / closedMs}%5.1fx")

  def benchPredicts(d: Dataset, cfg: Config): Unit =
    val ref   = pls1Scalar(d.x, d.y)
    val model = Tprf3.pls1Fit(d.x, d.y)
    var sink  = 0.0
    val scalarNs = timed(cfg.preds)(sink += ref(d.testRow)) * 1e6
    val closedNs = timed(cfg.preds)(sink += model.predict(d.testRow)) * 1e6
    println(f"  N=${d.cols}%-3d scalar ${scalarNs}%7.1f   closed ${closedNs}%7.1f   ${scalarNs / closedNs}%5.1fx" +
            f"   (sink=${sink}%.3e)")

  def main(args: Array[String]): Unit =
    var cfg = Config()
    eachArg(args.toSeq, usage):
      case "-rows"  => cfg = cfg.copy(rows = consumeNext.toInt)
      case "-fits"  => cfg = cfg.copy(fits = consumeNext.toInt)
      case "-preds" => cfg = cfg.copy(preds = consumeNext.toInt)
      case arg      => usage(s"unrecognized arg [$arg]")

    // The predictor counts that actually occur downstream:
    //   2   VolTarget.currentExposure combiner: Array(hrsF(t), exoAt(t))
    //   5   volForecast rawPLS1{RS,MOVE,rV}
    //   9   volForecast widePlsX  ("wide-PLS1(9)")
    //  15   volForecast panelX    ("3PRF-PLS1(15)")
    // All are below the old minObs floor of 10 except the last, which is why
    // the iterative path was unusable for essentially every real call.
    val datasets = List(2, 5, 9, 15).map(n => dataset(cfg.rows, n, seed = 42))

    println(s"=== One-component PLS-1 via three routes (T=${cfg.rows}) ===")

    println("\n── Correctness ──────────────────────────────────────────────────")
    datasets.foreach(verify)

    println(s"\n── Fit, ms/call (${cfg.fits} iterations) ─────────────────────────")
    datasets.foreach(d => benchFits(d, cfg))

    println(s"\n── Predict one held-out row, ns/call (${cfg.preds} iterations) ───")
    println("  (no iterative arm: estimate3prf discards phi/sigma/beta3)")
    datasets.foreach(d => benchPredicts(d, cfg))

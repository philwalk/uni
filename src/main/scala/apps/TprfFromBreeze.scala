//#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
package apps

//> using dep org.vastblue:uni_3:0.9.3

import uni.data.*
import uni.data.Mat.*
import uni.*
import java.io.PrintWriter
import uni.stats.Tprf3.{TprfResult, TprfFast, tprfFast}

object TprfFromBreeze {

  def main(args: Array[String]): Unit = {
    Mat.setSeed(0)
    try {
      val (rows, cols, num_proxies) = (650, 40, 2)
      val X: MatD = MatD.rand(rows, cols)
      val y: MatD = MatD.rand(rows, 1)
      val Z: MatD = autoproxy(X, y, num_proxies)

      val result: TprfResult = tprfFast(X, y, Z)
      printf("fast: %s\n", result.toString)

      val secsFast = reportTime(rows / 30) {
        tprfFast(X, y, Z)
      }
      printf("fast seconds: %s\n", secsFast)
    } catch {
      case t: Throwable =>
        t.printStackTrace()
        sys.exit(1)
    }
  }

  extension (m: MatD)
    def shapes: String = s"(${m.rows}, ${m.cols})"

  def reportTime(loops: Int = 1000)(func: => Unit): Double = {
    val t0: Long = System.currentTimeMillis
    def elapsedSeconds: Double = (System.currentTimeMillis - t0).toDouble / 1000.0
    for _ <- 0 until loops do
      func
    elapsedSeconds
  }

  def tprf(X: MatD, y: MatD, Z: MatD, oos: MatD = MatD.zeros(0, 1)): (MatD, Double) =
    val oos_present = oos.rows > 1
    val model       = tprfFast(X, y, Z, oos)
    val yhatt       = if oos_present then model.estimateYhat(oos) else Double.NaN
    (model.y_hat, yhatt)

  def tprfFastAuto(X: MatD, y: MatD, num_proxies: Int = 2, oos: MatD = MatD.zeros(0, 1)): TprfFast =
    val Z = autoproxy(X, y, num_proxies)
    tprfFast(X, y, Z, oos)

  def autoproxy(X: MatD, y: MatD, n_proxy: Int): MatD =
    var r0: MatD = y    // (n, 1) — first proxy column is y itself
    for _ <- 1 until n_proxy do
      val yhat: MatD      = tprfFast(X, y, r0).y_hat
      val residuals: MatD = y - yhat
      r0 = MatD.hstack(residuals, r0)
    r0

  def recursiveTrainAuto(X: MatD, y: MatD, train_window: Int, n_proxies: Int): Double =
    assert(n_proxies > 0, s"n_proxies[$n_proxies]")
    val lst = Array.ofDim[Double](X.rows - train_window)
    for t <- train_window until X.rows do
      val Z       = autoproxy(X(0 until t, ::), y(0 until t, ::), n_proxies)
      val y_train = y(0 until t, ::)
      val (_, yhatt) = tprf(X(0 until t, ::), y_train, Z, X(t, ::).T)
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
      val y_train = y(0 until t, ::)
      val (_, yhatt) = tprf(X(0 until t, ::), y_train, Z, X(t, ::).T)
      lst(t - train_window) = yhatt
    val yhatt  = Mat.create(lst, lst.length, 1)
    val y_true = y(train_window until y.rows, ::)
    rr2(y_true, yhatt)

  def rr2(y_true: MatD, yhatt: MatD): Double =
    assert(yhatt.rows == y_true.rows)
    val residuals: MatD = y_true - yhatt
    Option(System.getenv("TPRF_RESIDUALS_OUT")).foreach { f =>
      if f.nonEmpty then
        val p = f.path
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

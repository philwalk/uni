#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
//> using dep org.vastblue:uni_3:0.14.0

// Locates the v0.14.0 IS Full regression: times estimate3prf with/without avar,
// then the avar-block sub-expressions, at the Tprf3Bench Large size.

import uni.data.*
import uni.stats.Tprf3

object profIsFull {
  def println(s: String = ""): Unit = print(s"$s\n")

  def timeMs[A](warmup: Int, loops: Int)(f: => A): Double =
    var i = 0
    while i < warmup do { f; i += 1 }
    val t0 = System.nanoTime
    i = 0
    while i < loops do { f; i += 1 }
    (System.nanoTime - t0) / 1e6 / loops

  def main(args: Array[String]): Unit =
    val T = 650; val N = 40; val L = 2
    MatD.setSeed(42)
    val y  = MatD.randn(T, 1)
    val X  = MatD.randn(T, N)
    val Z  = MatD.randn(T, L)

    val withAvar = timeMs(3, 10)(Tprf3.estimate3prf(y, X, Right(Z), computeAvar = true))
    val noAvar   = timeMs(3, 10)(Tprf3.estimate3prf(y, X, Right(Z), computeAvar = false))
    println(f"estimate3prf IS Full  computeAvar=true : $withAvar%8.2f ms")
    println(f"estimate3prf IS Full  computeAvar=false: $noAvar%8.2f ms")

    // avar sub-expressions
    val jt = MatD.eye(T) - MatD.ones(T, T) * (1.0 / T)
    val jn = MatD.eye(N) - MatD.ones(N, N) * (1.0 / N)
    val Xn = X
    val zf = Z
    val residuals = MatD.randn(T, 1)

    println(f"  jMat(T) construction              : ${timeMs(3, 10)(MatD.eye(T) - MatD.ones(T, T) * (1.0 / T))}%8.2f ms")
    println(f"  a  = Xn.T *@ jt *@ zf * (1/T)     : ${timeMs(3, 10)(Xn.T *@ jt *@ zf * (1.0 / T))}%8.2f ms")
    println(f"  b  chain (zf.T jt Xn jn ... zf)   : ${timeMs(3, 10)((zf.T *@ jt *@ Xn *@ jn *@ Xn.T *@ jt *@ Xn *@ jn *@ Xn.T *@ jt *@ zf) * (math.pow(T, -3) * math.pow(N, -2)))}%8.2f ms")
    println(f"  c  = zf.T *@ jt *@ Xn *@ jn       : ${timeMs(3, 10)(zf.T *@ jt *@ Xn *@ jn * (1.0 / T / N))}%8.2f ms")
    val Xm = Xn.sum(0) / T.toDouble
    def tmpLoop(): MatD =
      var tmp = MatD.zeros(N, N)
      var ti = 0
      while ti < T do
        val xrow = Xn(ti, ::) - Xm
        tmp = tmp + xrow.T *@ xrow * (math.pow(residuals(ti, 0), 2) / T)
        ti += 1
      tmp
    println(f"  tmp loop (T iters of N×N outer)   : ${timeMs(3, 10)(tmpLoop())}%8.2f ms")
}

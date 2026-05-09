#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using jvm 21
//> using scala 3.8.2
//> using javaOpt --add-modules jdk.incubator.vector
//> using repository m2Local
//> using dep dev.ludovic.netlib:blas:3.2.0
//> using dep org.vastblue:uni_3:0.13.3
//> using dep org.scalanlp::breeze:2.1.0

/**
 * For comparison with similar benchmark pinned to uni 0.10.2, the last release using bytedeco/OpenBLAS
 * for matrix multiply. Kept as a reproducible record of pre-switch matmul performance.
 *
 * For MatD with Bytedeco OpenBLAS, run:   scala-cli benchMatmulBytedeco.sc
 * For MatD with Netlib   OpenBLAS, run:   scala-cli benchMatmulNetlib.sc
 *
 * Bz/MD < 1.0 → Breeze faster;  Bz/MD > 1.0 → MatD faster
 *
 * Design notes
 * ------------
 * v0.11.0 switched matmul to netlib JNIBLAS (direct Java array passing, no
 * DoublePointer/DirectBuffer overhead), eliminating the gap visible here.
 * Run jsrc/benchMatmulNetlib.sc for the post-switch comparison.
 *
 * Bz/MD < 1.0 → Breeze faster;  Bz/MD > 1.0 → MatD faster
 *
 * Key differences
 * ---------------
 * Matmul  : MatD → OpenBLAS via bytedeco JNI; Breeze → netlib-java / OpenBLAS
 *
 *
 */

import scala.collection.mutable.ArrayBuffer
import uni.data.*
import breeze.linalg.DenseMatrix
import breeze.stats.distributions.Gaussian
import breeze.stats.distributions.Rand.VariableSeed.randBasis
//import breeze.numerics.{sigmoid as bzSigmoid}

// ── detect BLAS backend silently (before printing anything) ───────────────────
// JNIBLAS is package-private so we use the public BLAS.getInstance() and inspect class name
val jniBlasFailed = try {
  val blas = Class.forName("dev.ludovic.netlib.blas.BLAS")
    .getMethod("getInstance").invoke(null)
  !blas.getClass.getName.contains("JNIBLAS")
} catch { case e: Throwable =>
  val msg = Option(e.getCause).map(_.getMessage).orElse(Option(e.getMessage)).getOrElse("(no message)")
  System.err.println(s"BLAS detection: $msg")
  true
}

val N      = 1000   // square size for element-wise / reduction ops
val MM     = 512    // matmul side length (512³ ≈ 134M multiplications)
val WARMUP = 16
val ITERS  = 80

// ── timing helper — returns minimum ms ───────────────────────────────────────
def minMs(op: => Any): Double =
  for _ <- 1 to WARMUP do op
  (1 to ITERS).map { _ =>
    val t0 = System.nanoTime()
    op
    (System.nanoTime() - t0) / 1e6
  }.min

// ── result collection ─────────────────────────────────────────────────────────
case class Result(label: String, matd: Double, bz: Double):
  def ratio: Double    = bz / matd
  def scoreable: Boolean = matd >= 0.05  // exclude sub-50µs ops (O(1) transpose noise)

val results = ArrayBuffer[Result]()

// ── comparison row — prints and records ───────────────────────────────────────
def row(label: String)(matdOp: => Any)(breezeOp: => Any): Unit =
  val m = minMs(matdOp)
  val b = minMs(breezeOp)
  results += Result(label, m, b)
  println(f"  ${label}%-40s  ${m}%7.2f    ${b}%7.2f    ${b/m}%5.2f")

// ── pre-build matrices (allocation excluded from timed ops) ───────────────────
MatD.setSeed(42)
val A  = MatD.randn(MM, MM)
val B  = MatD.randn(MM, MM)
val M  = MatD.randn(N,  N)
val M2 = MatD.randn(N,  N)

val normalDist = Gaussian(0.0, 1.0)
val bzA  = DenseMatrix.rand[Double](MM, MM, normalDist)
val bzB  = DenseMatrix.rand[Double](MM, MM, normalDist)
val bzM  = DenseMatrix.rand[Double](N,  N,  normalDist)
val bzM2 = DenseMatrix.rand[Double](N,  N,  normalDist)

// force Breeze BLAS initialisation now so any warnings appear before the table
bzA * bzB

// ── header ────────────────────────────────────────────────────────────────────
println(s"\nMatD 0.9.6 vs Breeze 2.1.0   Scala 3.8.2   JVM ${System.getProperty("java.version")}")
println(s"N=$N  MM=$MM  warmup=$WARMUP  iters=$ITERS  (times = min ms)\n")
println(f"  ${"Operation"}%-40s  ${"MatD(ms)"}%7s    ${"Bz(ms)"}%7s    ${"Bz/MD"}%5s")
println("  " + "-" * 74)

// Matrix multiply — OpenBLAS in both; MatD via bytedeco JNI, Breeze via netlib-java
row("Netlib:   matmul 512×512 @ 512×512") {
  A *@ B // the new multiply operator!  (changed from ~@ at v0.11.0)
} {
  bzA * bzB
}
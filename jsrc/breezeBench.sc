#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using jvm 21
//> using scala 3.8.2
//> using javaOpt --add-modules jdk.incubator.vector
//> using javaOpt -Ddev.ludovic.netlib.blas.nativeLib=libopenblas.dll
////> using javaOpt -verbose:jni
////> using repository file:///C:/Users/philwalk/.m2-netlib
//> using dep dev.ludovic.netlib:blas:3.1.2
//> using dep org.vastblue:uni_3:0.11.0
//> using dep org.scalanlp::breeze:2.1.0

/**
 * MatD vs Breeze benchmark — side-by-side comparison of 8 core operations.
 *
 * Run:   scala-cli bench.sc
 *
 * Bz/MD < 1.0 → Breeze faster;  Bz/MD > 1.0 → MatD faster
 *
 * Design notes
 * ------------
 * - 15 warmup iterations (JVM JIT needs time to compile hot loops)
 * - 20 timed iterations; reports minimum ms (most stable metric)
 * - Same 8 operations for both libraries
 * - Matrix sizes: N=1000 for element-wise/reduction, MM=512 for matmul
 *
 * Key differences
 * ---------------
 * Matmul  : MatD → OpenBLAS via bytedeco JNI; Breeze → netlib-java / OpenBLAS
 * Sigmoid : MatD uses parallel fork/join; Breeze uses sequential UFunc
 * ReLU    : MatD uses parallel fork/join; Breeze has no built-in (map used)
 * Add     : MatD uses parallel fork/join; Breeze uses sequential element-wise
 * Map     : MatD.mapParallel is parallel; DenseMatrix.map is sequential
 */

import scala.collection.mutable.ArrayBuffer
import uni.data.*
import breeze.linalg.{DenseMatrix, sum as bzSum}
import breeze.stats.distributions.Gaussian
import breeze.stats.distributions.Rand.VariableSeed.randBasis
import breeze.numerics.{sigmoid as bzSigmoid}

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
val WARMUP = 15
val ITERS  = 20

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

// 1. Random generation — PCG64 (MatD) vs Gaussian sampler (Breeze)
row("randn(1000×1000)") {
  MatD.setSeed(42)
  MatD.randn(N, N)
} {
  DenseMatrix.rand[Double](N, N, normalDist)
}

// 2. Matrix multiply — OpenBLAS in both; MatD via bytedeco JNI, Breeze via netlib-java
row("matmul 512×512 @ 512×512") {
  A *@ B
} {
  bzA * bzB
}

// 3. Sigmoid — parallel fork/join (MatD) vs sequential UFunc (Breeze)
row("sigmoid(1000×1000)") {
  M.sigmoid
} {
  bzSigmoid(bzM)
}

// 4. ReLU — parallel (MatD) vs sequential map (Breeze; no built-in relu)
row("relu(1000×1000)") {
  M.relu
} {
  bzM.map(x => math.max(0.0, x))
}

// 5. Element-wise add — parallel (MatD) vs sequential (Breeze)
row("add 1000×1000 + 1000×1000") {
  M + M2
} {
  bzM + bzM2
}

// 6. Sum reduction
row("sum(1000×1000)") {
  M.sum
} {
  bzSum(bzM)
}

// 7. Transpose — O(1) stride/isTranspose flag in both; near-zero cost
row("transpose 1000×1000  [O(1)]") {
  M.T
} {
  bzM.t
}

// 8. Custom map fn — parallel fork/join (MatD) vs sequential (Breeze)
row("map custom fn (1000×1000)") {
  M.mapParallel(x => x * x + 2 * x + 1.0)
} {
  bzM.map(x => x * x + 2 * x + 1.0)
}

println("  " + "-" * 74)

// ── dynamic summary ───────────────────────────────────────────────────────────
val scored   = results.filter(_.scoreable).toVector
val matdWon  = scored.count(_.ratio > 1.0)
val bzWon    = scored.count(_.ratio < 1.0)
val geoMean  = math.exp(scored.map(r => math.log(r.ratio)).sum / scored.size)
val best     = scored.maxBy(_.ratio)
val closest  = scored.minBy(r => math.abs(r.ratio - 1.0))
val skipped  = results.filterNot(_.scoreable).map(_.label.trim)
val backend  = if jniBlasFailed then "VECTORAPIBLAS (JNIBLAS unavailable)" else "JNIBLAS (native OpenBLAS)"

println(s"\nSummary (${scored.size}/${results.size} operations scored):")
if skipped.nonEmpty then
  println(s"  Excluded (O(1) / sub-50µs): ${skipped.mkString(", ")}")
println(f"  MatD faster:    $matdWon%d/${scored.size}%d   geometric mean Bz/MD = $geoMean%.2f×")
if bzWon > 0 then
  val wins = scored.filter(_.ratio < 1.0).map(r => f"${r.label.trim} (${r.ratio}%.2f×)").mkString(", ")
  println(f"  Breeze faster:  $bzWon%d/${scored.size}%d   ($wins)")
else
  println(f"  Breeze faster:  0/${scored.size}%d")
println(f"  Biggest MatD lead:  ${best.label.trim} (${best.ratio}%.1f×)")
println(f"  Closest contest:    ${closest.label.trim} (${closest.ratio}%.2f×)")
println(s"  Breeze BLAS backend: $backend")
if jniBlasFailed then
  println("  (matmul gap reflects BLAS fallback; other gaps are parallel vs sequential)")
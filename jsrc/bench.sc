#!/usr/bin/env -S scala-cli shebang -deprecation

//> using scala 3.8.2
//> using dep org.vastblue:uni_3:0.9.2

/**
 * MatD benchmark — counterpart to py/bench.py (NumPy/Python).
 *
 * Run:   scala-cli bench.sc
 *
 * Design notes
 * ------------
 * - 15 warmup iterations (JVM JIT needs time to compile hot loops)
 * - 20 timed iterations; reports mean and min in ms
 * - Same 8 operations as py/bench.py for direct side-by-side comparison
 * - Matrix sizes match the Python script (N=1000, MM=512)
 *
 * Why results differ
 * ------------------
 * Matmul  : both use OpenBLAS (NumPy natively; MatD via bytedeco JNI)
 *           → results should be similar; JNI call overhead is small
 * Sigmoid : NumPy uses C + SIMD (AVX); JVM relies on JIT; gap varies by CPU
 * ReLU    : same as sigmoid — SIMD vs JIT
 * Add     : same as sigmoid
 * Sum     : NumPy uses vectorised C reduction; JVM JIT usually competitive
 * Randn   : both are PCG64 implementations — C vs JVM
 * Transpose: O(1) metadata flip in both — negligible, included as sanity check
 */

import uni.data.*

val N  = 1000   // square size for element-wise / reduction ops
val MM = 512    // matmul side length (512³ ≈ 134M multiplications)

val WARMUP = 15
val ITERS  = 20

// ── timing helper ─────────────────────────────────────────────────────────────

def bench(label: String)(op: => Any): Unit =
  for _ <- 1 to WARMUP do op                        // JVM JIT warmup
  val times = (1 to ITERS).map { _ =>
    val t0 = System.nanoTime()
    op
    (System.nanoTime() - t0) / 1e6                  // → ms
  }
  val mean_ = times.sum / ITERS
  val min_  = times.min
  println(f"  ${label}%-42s  mean=${mean_}%8.2f ms   min=${min_}%8.2f ms")

// ── pre-build matrices so allocation is excluded from timed ops ───────────────

MatD.setSeed(42)
val A  = MatD.randn(MM, MM)
val B  = MatD.randn(MM, MM)
val M  = MatD.randn(N,  N)
val M2 = MatD.randn(N,  N)

// ── benchmark suite ───────────────────────────────────────────────────────────

println(s"\nuni.MatD 0.9.2   Scala 3.7.0   JVM ${System.getProperty("java.version")}")
println(s"N=$N  MM=$MM  warmup=$WARMUP  iters=$ITERS\n")
println("  " + "-" * 72)

// 1. Random generation — PCG64; same algorithm as NumPy, JVM vs C impl
bench("randn(1000×1000)") {
  MatD.setSeed(42)
  MatD.randn(N, N)
}

// 2. Matrix multiply — bytedeco → OpenBLAS JNI; similar pathway to NumPy
bench("matmul 512×512 @ 512×512") {
  A ~@ B
}

// 3. Sigmoid — element-wise 1/(1+e^-x) over 1M elements
bench("sigmoid(1000×1000)") {
  M.sigmoid
}

// 4. ReLU — element-wise max(x,0) over 1M elements
bench("relu(1000×1000)") {
  M.relu
}

// 5. Element-wise add — simplest possible element-wise kernel
bench("add 1000×1000 + 1000×1000") {
  M + M2
}

// 6. Full reduction — sum over 1M elements
bench("sum(1000×1000)") {
  M.sum
}

// 7. Transpose — O(1) stride-flip, no data copy, should be near-zero
bench("transpose 1000×1000  [O(1)]") {
  M.T
}

// 8. Custom scalar fn via mapParallel — JVM parallel fork/join vs np.vectorize (Python loop).
//    Uses x*x + 2*x + 1 so the work per element is non-trivial but not exp-heavy.
bench("mapParallel custom fn (1000×1000)") {
  M.mapParallel(x => x * x + 2 * x + 1.0)
}

println("  " + "-" * 72)
println("""
Note: transpose is O(1) in both libraries (stride flip, no copy).
      MatD matmul uses OpenBLAS via bytedeco (org.bytedeco:openblas-platform).
      NumPy matmul uses OpenBLAS (or MKL if present in your install).
      JVM results improve significantly after warmup; first run will be slower.
      mapParallel vs np.vectorize: JVM wins because np.vectorize is a Python loop.
""")

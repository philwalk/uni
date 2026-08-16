package uni.data

import uni.*

/**
 * Measures how far Scala's two `matmul` paths sit apart, which is the fact the Rust
 * port's matmul contract has to be built on.
 *
 * `matmul` dispatches on size: `ops >= 216` (non-macOS) routes to BLAS, anything smaller
 * to the tiled pure loop. The threshold is a system property, so this is not an internal
 * detail — the same expression can already produce two different answers in Scala alone
 * depending on configuration.
 *
 * In package `uni.data` so it can call both paths directly rather than fighting the
 * `lazy val` threshold, which is read once per JVM.
 */
class MatmulDivergenceSuite extends munit.FunSuite:
  def println(s: String = ""): Unit = print(s"$s\n")

  /** Distance in representable doubles — 0 means bit-identical. */
  def ulps(a: Double, b: Double): Long =
    if a == b then 0L
    else if a.isNaN || b.isNaN then Long.MaxValue
    else
      def ord(d: Double): Long =
        val bits = java.lang.Double.doubleToLongBits(d)
        if bits < 0 then 0x8000000000000000L - bits else bits
      math.abs(ord(a) - ord(b))

  def mat(rows: Int, cols: Int, seed: Long): Mat[Double] =
    val rng = NumPyRNG(seed)
    MatD(rows, cols, Array.fill(rows * cols)(rng.randn()))

  test("how far apart are the BLAS and pure matmul paths") {
    println()
    println("  size        cells  differing   max ulp     max rel")
    val report = for (n, k) <- Vector((4, 4), (8, 8), (32, 32), (64, 64), (128, 128)) yield
      val a    = mat(n, k, 42)
      val b    = mat(k, n, 43)
      val pure = a.multiplyDouble(b)
      val blas = a.multiplyDoubleBLAS(b)
      val pa   = pure.toArray
      val ba   = blas.toArray
      val diffs = pa.indices.count(i => pa(i) != ba(i))
      val maxUlp = pa.indices.map(i => ulps(pa(i), ba(i))).maxOption.getOrElse(0L)
      val maxRel = pa.indices
        .map(i => if ba(i) == 0.0 then 0.0 else math.abs((pa(i) - ba(i)) / ba(i)))
        .maxOption
        .getOrElse(0.0)
      println(f"  ${n}x$k%-6s ${pa.length}%8d ${diffs}%10d ${maxUlp}%9d  ${maxRel}%10.3e")
      (n, diffs, maxUlp)

    // The measurement is the point; this only stops it silently becoming vacuous.
    assert(report.nonEmpty)
  }

  test("the pure path is deterministic across runs") {
    // It parallelises over row tiles, so this asks whether the split is stable. If it
    // is not, the pure path cannot serve as a pinned contract either.
    val a     = mat(96, 96, 7)
    val b     = mat(96, 96, 8)
    val first = a.multiplyDouble(b).toArray
    for _ <- 1 to 8 do
      assertEquals(
        java.util.Arrays.equals(a.multiplyDouble(b).toArray, first),
        true,
        "the tiled parallel loop is not reproducible run to run",
      )
  }

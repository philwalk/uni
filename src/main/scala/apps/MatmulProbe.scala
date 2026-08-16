package uni.apps

import uni.*
import uni.data.*

/**
 * Times every matmul path per shape, so the matmul contract can be re-measured on any
 * machine rather than argued from folklore. It is the Scala half of a three-language
 * measurement — `rust/src/bin/bench_matmul.rs` and `py/bench_matmul.py` are the others —
 * with labels in the harness format so the outputs join.
 *
 * Three rows per shape:
 *
 *  - `pure` — `matmulPure`: the tiled loop, parallel over row tiles. Its association
 *             order per cell is a sequential k-sum from 0.0, which is why it is the
 *             default: pinned bit-for-bit across languages and machines.
 *  - `blas` — `matmulBlas`: whichever backend `Mat` selected. Not pinned.
 *  - `auto` — `*@`: what a user gets in the current mode (pure unless
 *             `-Duni.mat.blas=true` / `UNI_MAT_BLAS=true`).
 *
 * The one measurement caveat that matters: BLAS's threads and the pure loop's ForkJoin
 * workers contend when measured back to back, so `pure` is measured last, and BLAS rows
 * still swing up to ~1.7x run to run on a threaded OpenBLAS.
 *
 * Run: `sbt "runMain uni.apps.MatmulProbe"`
 */
object MatmulProbe:
  def println(s: String = ""): Unit = print(s"$s\n")

  /** `(label, rowsA, colsA, colsB)`. Squares, plus the two skinny shapes the 3PRF gemms
   *  take: one operand with only a handful of columns. Must match the other halves. */
  val shapes: Vector[(String, Int, Int, Int)] = Vector(
    ("8",        8,   8,   8),
    ("16",      16,  16,  16),
    ("32",      32,  32,  32),
    ("64",      64,  64,  64),
    ("128",    128, 128, 128),
    ("256",    256, 256, 256),
    ("512",    512, 512, 512),
    ("512x8x512", 512,   8, 512),
    ("512x512x8", 512, 512,   8),
  )

  /** Minimum ms per call over `runs`, each run repeating the op enough times to fill
   *  ~`targetMs` so a µs-scale call is not measured against the timer's grain. */
  def minMs(op: () => Any, runs: Int = 7, targetMs: Double = 2.0): Double =
    var sink = 0L
    for _ <- 0 until 5 do sink += op().hashCode
    val t0  = System.nanoTime(); sink += op().hashCode
    val est = (System.nanoTime() - t0) / 1e6
    val reps = math.max(1, math.min(20000, (targetMs / math.max(est, 1e-6)).toInt))
    var best = Double.MaxValue
    for _ <- 0 until runs do
      val s = System.nanoTime()
      var i = 0
      while i < reps do { sink += op().hashCode; i += 1 }
      val ms = (System.nanoTime() - s) / 1e6 / reps
      if ms < best then best = ms
    if sink == Long.MinValue then println()
    best

  def main(args: Array[String]): Unit =
    val mode = sys.props.get("uni.mat.blas").orElse(sys.env.get("UNI_MAT_BLAS")).getOrElse("unset")
    println(s"config: jvm=${System.getProperty("java.version")} " +
      s"cores=${Runtime.getRuntime.availableProcessors} " +
      s"os=${System.getProperty("os.name")} uni.mat.blas=$mode")

    MatD.setSeed(42)
    for (label, ra, ca, cb) <- shapes do
      val a = MatD.randn(ra, ca)
      val b = MatD.randn(ca, cb)
      val auto = minMs(() => a *@ b)
      val blas = minMs(() => a.matmulBlas(b))
      val pure = minMs(() => a.matmulPure(b))
      println(f"  [Scala] matmul@pure/$label%-12s ${pure}%10.4f ms/call")
      println(f"  [Scala] matmul@blas/$label%-12s ${blas}%10.4f ms/call")
      println(f"  [Scala] matmul@auto/$label%-12s ${auto}%10.4f ms/call")

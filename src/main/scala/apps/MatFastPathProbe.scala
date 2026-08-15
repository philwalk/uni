package uni.apps

import uni.*
import uni.data.*

import scala.reflect.ClassTag

/**
 * Correctness and performance evidence for `Mat`'s reductions, as a before/after pair.
 *
 * Built to gate one change — giving `sum`, `mean`, `std`, `variance` and the order
 * statistics an unboxed branch that reads a `Mat[Double]` of ANY layout through the
 * stride equation — and kept as a standing harness, because that change is the kind
 * that recurs and because two of the three defects it caught were invisible to the
 * committed fixture.
 *
 * # How to use it
 *
 * Capture, change something, capture again, then diff:
 *
 * {{{
 *   sbt "runMain uni.apps.MatFastPathProbe -out /tmp/before"
 *   # ... edit Mat.scala ...
 *   sbt "runMain uni.apps.MatFastPathProbe -out /tmp/after"
 *   diff /tmp/before/correctness.tsv /tmp/after/correctness.tsv   # must be empty
 * }}}
 *
 * For timings, capture THREE runs per side and compare ranges, not single numbers. Three
 * runs of one unchanged build were measured spanning up to 3.9x on the small shapes, so
 * a single before/after pair will manufacture regressions that do not exist — it flagged
 * 16, five of them on operations the change could not touch. A cell has only really
 * regressed if its whole after-range sits above its whole before-range.
 *
 * # Why both files
 *
 * The change is meant to be **bit-preserving**: it unboxes the existing sequential
 * row-major fold, it does NOT route anything to `sumD` (that would change association
 * order, and `MatParitySuite` would fail immediately). So:
 *
 *   - `correctness.tsv` records the exact result of every (type, layout, shape, op) cell
 *     as a lossless string. `Double.toString`/`Float.toString` are round-trippable in
 *     Java and `BigDecimal.toString` is exact, so equality of these files is equality of
 *     values — including `-0.0` vs `0.0`, which a numeric comparison would miss.
 *     Diffing before against after is the gate.
 *   - `perf.tsv` records timings. A guard that helps large contiguous Double matrices
 *     can still *cost* something on small ones (an extra runtime type test per call) or
 *     on the non-Double types that will keep taking the general branch — so Float, Int
 *     and Big are measured too, and so are sizes below every threshold.
 *
 * # Coverage that `mat-parity` does not have
 *
 * The committed fixture is Double-only, at fixed shapes, mostly contiguous. This sweeps
 * four element types and five layouts (contiguous, transposed, offset row slice, column
 * slice — whose materialisation flips with aspect ratio — and a stride-0 broadcast),
 * because the new guard has to be wrong in none of them.
 *
 * Run:  sbt "runMain uni.apps.MatFastPathProbe -out C:/tmp/.../baseline"
 */
object MatFastPathProbe:
  def println(s: String = ""): Unit = print(s"$s\n")

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "-out DIR      ; directory to write correctness.tsv and perf.tsv into",
    "-quick        ; skip the 2000x2000 timings",
  )

  // ── Lossless rendering ────────────────────────────────────────────────────
  // toString rather than raw bits so one code path covers all four element types.
  // Lossless for each: Java's Double/Float toString is the shortest round-tripping
  // form, BigDecimal's is exact, Int's is exact. Crucially it also distinguishes
  // -0.0 from 0.0, which is where an "obvious" rewrite of abs/min/max goes wrong.
  def render[T](x: T): String = x.toString

  def renderMat[T](m: Mat[T])(using @annotation.unused ct: ClassTag[T]): String =
    val sb = StringBuilder()
    var i = 0
    while i < m.rows do
      var j = 0
      while j < m.cols do
        if i > 0 || j > 0 then sb ++= ","
        sb ++= render(m(i, j))
        j += 1
      i += 1
    sb.toString

  /** FNV-1a over the UTF-8 bytes, so a whole-matrix result is one comparable cell. */
  def fnv(s: String): String =
    var d = 0xcbf29ce484222325L
    for b <- s.getBytes("UTF-8") do d = (d ^ (b & 0xffL)) * 0x100000001b3L
    f"$d%016x"

  // ── Layouts ───────────────────────────────────────────────────────────────
  /** The five layouts a reduction can meet. Each is a function of a contiguous parent. */
  def layouts[T: ClassTag](m: Mat[T]): Vector[(String, Mat[T])] =
    val base = Vector("contig" -> m, "transposed" -> m.T)
    val sliced =
      if m.rows > 1 && m.cols > 1 then
        Vector(
          // Offset, standard strides: contiguous but NOT at offset 0.
          "rowslice" -> m.slice(1 until m.rows, 0 until m.cols),
          // Dropping a column: materialized or left a view depending on aspect ratio.
          "colslice" -> m.slice(0 until m.rows, 1 until m.cols),
        )
      else Vector.empty
    val bcast =
      if m.rows > 1 then Vector("bcast" -> m.slice(0 until 1, 0 until m.cols).broadcastTo(m.rows, m.cols))
      else Vector.empty
    base ++ sliced ++ bcast

  /** Shapes straddling the unroll boundary and both chunking thresholds. */
  val shapes: Vector[(Int, Int)] =
    Vector((1, 1), (3, 5), (5, 3), (9, 8), (64, 64), (65, 63), (257, 256), (300, 400))

  // ── The ops under change, plus the neighbours that share their code ───────
  def opsD(m: Mat[Double]): Vector[(String, String)] = Vector(
    "sum"       -> render(m.sum),
    "min"       -> render(m.min),
    "max"       -> render(m.max),
    "argmin"    -> render(m.argmin),
    "argmax"    -> render(m.argmax),
    "cummax0"   -> fnv(renderMat(m.cummax(0))),
    "cummax1"   -> fnv(renderMat(m.cummax(1))),
    "cummin0"   -> fnv(renderMat(m.cummin(0))),
    "cummin1"   -> fnv(renderMat(m.cummin(1))),
    // Neighbours: not being changed, but they read the same data and would show
    // collateral damage.
    "mean"      -> render(m.mean),
    "std"       -> render(m.std),
    "variance"  -> render(m.variance),
    "sum0"      -> fnv(renderMat(m.sum(0))),
    "sum1"      -> fnv(renderMat(m.sum(1))),
    "min0"      -> fnv(renderMat(m.min(0))),
    "max1"      -> fnv(renderMat(m.max(1))),
    "cumsum"    -> fnv(renderMat(m.cumsum)),
  )

  def opsF(m: Mat[Float]): Vector[(String, String)] = Vector(
    "sum" -> render(m.sum), "min" -> render(m.min), "max" -> render(m.max),
    "argmin" -> render(m.argmin), "argmax" -> render(m.argmax),
    "cummax0" -> fnv(renderMat(m.cummax(0))), "cummin1" -> fnv(renderMat(m.cummin(1))),
  )

  def opsI(m: Mat[Int]): Vector[(String, String)] = Vector(
    "sum" -> render(m.sum), "min" -> render(m.min), "max" -> render(m.max),
    "argmin" -> render(m.argmin), "argmax" -> render(m.argmax),
    "cummax0" -> fnv(renderMat(m.cummax(0))), "cummin1" -> fnv(renderMat(m.cummin(1))),
  )

  def opsB(m: Mat[Big.Big]): Vector[(String, String)] = Vector(
    "sum" -> render(m.sum), "min" -> render(m.min), "max" -> render(m.max),
    "argmin" -> render(m.argmin), "argmax" -> render(m.argmax),
    "cummax0" -> fnv(renderMat(m.cummax(0))), "cummin1" -> fnv(renderMat(m.cummin(1))),
  )

  /** Deterministic, badly-conditioned: mixed magnitudes make order observable. */
  def corpus(n: Int): Array[Double] =
    val rng = new NumPyRNG(20260815L)
    Array.tabulate(n)(i => if i % 2 == 0 then rng.uniform(-1e6, 1e6) else rng.uniform(-1e-6, 1e-6))

  /**
   * Adversarial values, which the random corpus cannot reach and which decide whether
   * `ord.lt`/`ord.gt` may be replaced by `<`/`>` at all.
   *
   * `min`/`max`/`argmin`/`argmax`/`cummax`/`cummin` compare through `Ordering[T]`. For
   * Double the implicitly summoned instance is `TotalOrdering`, where NaN sorts ABOVE
   * every number and `-0.0 < 0.0` — neither of which is true of the primitive `<`. An
   * unboxed rewrite that reaches for `<` would therefore change results here and nowhere
   * else, on inputs the random corpus never produces. These rows pin the existing
   * answers so that cannot happen unnoticed.
   */
  val adversarial: Vector[(String, Array[Double])] = Vector(
    "nanmid"    -> Array(1.0, Double.NaN, -1.0, 2.0),
    "nanfirst"  -> Array(Double.NaN, 1.0, -1.0),
    "nanonly"   -> Array(Double.NaN, Double.NaN),
    "zeros"     -> Array(0.0, -0.0),
    "zerosrev"  -> Array(-0.0, 0.0),
    "zerosmix"  -> Array(1.0, -0.0, 0.0, -1.0),
    "infs"      -> Array(Double.PositiveInfinity, Double.NegativeInfinity, 0.0),
    "infnan"    -> Array(Double.PositiveInfinity, Double.NaN, Double.NegativeInfinity),
    "ties"      -> Array(3.0, 1.0, 1.0, 3.0),
  )

  def adversarialRows: String =
    val sb = StringBuilder()
    for (name, arr) <- adversarial do
      // A column, a row, and the transpose of the column: each orientation for the
      // axis-oriented scans, plus one strided read of the same values.
      val cases = Vector(
        s"$name/col"  -> Mat.create(arr.clone(), arr.length, 1),
        s"$name/row"  -> Mat.create(arr.clone(), 1, arr.length),
        s"$name/colT" -> Mat.create(arr.clone(), arr.length, 1).T,
      )
      for (cname, m) <- cases do
        for (op, v) <- opsD(m) do sb ++= s"Double\tadversarial\t$cname\t$op\t$v\n"
    sb.toString

  /**
   * Empty shapes, recorded as either a result or the exception CLASS NAME.
   *
   * Added after review caught a real defect: an unboxed seed like `a(off + j * cs)`
   * reads before the loop bounds are tested, so a 0×5 raised IndexOutOfBoundsException
   * where the boxed path raises IllegalArgumentException from `apply`'s `require`. Both
   * throw, so a test that only asked "does it throw" would have passed. The behaviour
   * is also not uniform — 5×0 with axis 0 returns an empty Mat rather than throwing,
   * because the outer loop never runs — so the exception type has to be part of the
   * record, not just its presence.
   */
  def emptyRows: String =
    val sb = StringBuilder()
    def attempt(label: String, f: => Any): Unit =
      val v =
        try render(f)
        catch case e: Throwable => s"threw:${e.getClass.getSimpleName}"
      sb ++= s"Double\tempty\t$label\t$v\n"
    for (r, c) <- Vector((0, 0), (0, 5), (5, 0)) do
      val m = Mat.create(Array.ofDim[Double](r * c), r, c)
      attempt(s"${r}x$c/sum", m.sum)
      attempt(s"${r}x$c/mean", m.mean)
      attempt(s"${r}x$c/min", m.min)
      attempt(s"${r}x$c/max", m.max)
      attempt(s"${r}x$c/argmin", m.argmin)
      attempt(s"${r}x$c/argmax", m.argmax)
      attempt(s"${r}x$c/cummax0", fnv(renderMat(m.cummax(0))))
      attempt(s"${r}x$c/cummax1", fnv(renderMat(m.cummax(1))))
      attempt(s"${r}x$c/cummin0", fnv(renderMat(m.cummin(0))))
      attempt(s"${r}x$c/cummin1", fnv(renderMat(m.cummin(1))))
    sb.toString

  def correctness: String =
    val sb = StringBuilder()
    sb ++= "# type\tlayout\tshape\top\tvalue\n"
    for (r, c) <- shapes do
      val d = corpus(r * c)
      val md = Mat.create(d, r, c)
      val mf = Mat.create(d.map(_.toFloat), r, c)
      val mi = Mat.create(d.map(x => (x * 1000).toInt), r, c)
      val mb = Mat.create(d.map(Big.apply), r, c)
      for (lname, lm) <- layouts(md) do
        for (op, v) <- opsD(lm) do sb ++= s"Double\t$lname\t${r}x$c\t$op\t$v\n"
      for (lname, lm) <- layouts(mf) do
        for (op, v) <- opsF(lm) do sb ++= s"Float\t$lname\t${r}x$c\t$op\t$v\n"
      for (lname, lm) <- layouts(mi) do
        for (op, v) <- opsI(lm) do sb ++= s"Int\t$lname\t${r}x$c\t$op\t$v\n"
      for (lname, lm) <- layouts(mb) do
        for (op, v) <- opsB(lm) do sb ++= s"Big\t$lname\t${r}x$c\t$op\t$v\n"
    sb ++= adversarialRows
    sb ++= emptyRows
    sb.toString

  // ── Timings ───────────────────────────────────────────────────────────────
  /**
   * Best per-call time in ms, with the inner iteration count AUTO-CALIBRATED so every
   * timed region is roughly 2ms of work.
   *
   * The naive version — time one call, take the best of N — is unusable at the small
   * shapes: one op over 4096 elements runs in ~12us, which is the scale of a single JIT
   * tier transition, and three runs of ONE unchanged build were measured spanning 3.9x
   * on exactly those cells. Timing a batch and dividing moves each measurement into the
   * millisecond range where it is dominated by the work rather than by when HotSpot
   * happened to recompile, which is what makes a before/after comparison mean anything.
   */
  def bestMs(@annotation.unused reps: Int)(f: => Any): Double =
    // Local, not an object field: the accumulator has to be READ for the accumulation to
    // be live at all, and a write-only field is both a warning and a licence for the JIT
    // to delete the work being timed. Scoped here it also stays out of global mutable
    // state.
    var sink = 0L
    var i = 0
    while i < 20 do { sink += f.hashCode; i += 1 }   // warm up + reach steady state
    val t0 = System.nanoTime()
    sink += f.hashCode
    val oneNs = math.max(1.0, (System.nanoTime() - t0).toDouble)
    val inner = math.max(1, math.min(50000, (2e6 / oneNs).toInt))
    var best = Double.MaxValue
    var run = 0
    while run < 10 do
      val s0 = System.nanoTime()
      var k = 0
      while k < inner do { sink += f.hashCode; k += 1 }
      val ms = (System.nanoTime() - s0) / 1e6 / inner
      if ms < best then best = ms
      run += 1
    if sink == Long.MinValue then println()   // the read that keeps `sink` alive
    best

  def perf(quick: Boolean): String =
    val sb = StringBuilder()
    sb ++= "# type\tlayout\tshape\top\tms\n"
    val sizes = if quick then Vector((64, 64), (300, 400)) else Vector((64, 64), (300, 400), (2000, 2000))
    for (r, c) <- sizes do
      val reps = if r * c > 1000000 then 5 else 50
      val d = corpus(r * c)
      val md = Mat.create(d, r, c)
      val mf = Mat.create(d.map(_.toFloat), r, c)
      for (lname, m) <- layouts(md) do
        sb ++= f"Double\t$lname\t${r}x$c\tsum\t${bestMs(reps)(m.sum)}%.4f\n"
        sb ++= f"Double\t$lname\t${r}x$c\tmax\t${bestMs(reps)(m.max)}%.4f\n"
        sb ++= f"Double\t$lname\t${r}x$c\tmin\t${bestMs(reps)(m.min)}%.4f\n"
        sb ++= f"Double\t$lname\t${r}x$c\targmax\t${bestMs(reps)(m.argmax)}%.4f\n"
        sb ++= f"Double\t$lname\t${r}x$c\tcummax0\t${bestMs(reps)(m.cummax(0))}%.4f\n"
        sb ++= f"Double\t$lname\t${r}x$c\tcummin1\t${bestMs(reps)(m.cummin(1))}%.4f\n"
        sb ++= f"Double\t$lname\t${r}x$c\tmean\t${bestMs(reps)(m.mean)}%.4f\n"
        // The axis family and the elementwise maps: both trail NumPy badly on views,
        // and neither was measurable here until now, so a change to them could not be
        // told from noise.
        sb ++= f"Double\t$lname\t${r}x$c\tsum0\t${bestMs(reps)(m.sum(0))}%.4f\n"
        sb ++= f"Double\t$lname\t${r}x$c\tsum1\t${bestMs(reps)(m.sum(1))}%.4f\n"
        sb ++= f"Double\t$lname\t${r}x$c\tmean0\t${bestMs(reps)(m.mean(0))}%.4f\n"
        sb ++= f"Double\t$lname\t${r}x$c\tabs\t${bestMs(reps)(m.abs)}%.4f\n"
        sb ++= f"Double\t$lname\t${r}x$c\tsqrt\t${bestMs(reps)(m.abs.sqrt)}%.4f\n"
        sb ++= f"Double\t$lname\t${r}x$c\tcumsum\t${bestMs(reps)(m.cumsum)}%.4f\n"
      // Float must not regress: it keeps taking the general branch.
      sb ++= f"Float\tcontig\t${r}x$c\tsum\t${bestMs(reps)(mf.sum)}%.4f\n"
      sb ++= f"Float\tcontig\t${r}x$c\tmax\t${bestMs(reps)(mf.max)}%.4f\n"
    sb.toString

  def main(args: Array[String]): Unit =
    var outDir = ""
    var quick  = false
    eachArg(args.toSeq, usage) {
      case "-out"   => outDir = consumeNext
      case "-quick" => quick = true
      case a        => usage(s"unrecognized arg [$a]")
    }
    if outDir.isEmpty then usage("missing -out <dir>")
    java.nio.file.Files.createDirectories(outDir.asPath)

    val cPath = s"$outDir/correctness.tsv"
    java.nio.file.Files.writeString(cPath.asPath, correctness)
    println(s"wrote $cPath")

    val pPath = s"$outDir/perf.tsv"
    java.nio.file.Files.writeString(pPath.asPath, perf(quick))
    println(s"wrote $pPath")

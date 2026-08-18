package uni.apps

import uni.*
import uni.data.*

/**
 * The MatD benchmark tables in `docs/MatDBenchmarks.md`, all three languages, one
 * command:
 *
 * {{{
 *   sbt "runMain uni.apps.MatBench"
 * }}}
 *
 * Runs the Scala timings in-process, shells out to `py/bench.py` and to
 * `rust/target/release/bench_mat`, joins the three on their row labels and prints
 * finished markdown. Nothing needs transcribing by hand.
 *
 * Flags: `-nopython`, `-norust`, `-python <exe>`.
 *
 * # Why this exists
 *
 * The tables used to come from running `jsrc/bench.sc` and `py/bench.py` separately and
 * merging them by hand, which left three things wrong that a single runner fixes:
 *
 *   - the two halves drew their inputs from DIFFERENT generators (`MatD.setSeed` is
 *     PCG64; `np.random.seed` is the legacy MT19937 RandomState), so the columns were
 *     measured on different matrices;
 *   - their warmup/iteration counts differed (16/240 against 10/20);
 *   - there was no Rust column at all, which only became possible with the Tier 3 port.
 *
 * # Two tables
 *
 * The **operation** table is the familiar one. The **layout** table is new and is the
 * more interesting: all three libraries distinguish a contiguous matrix from a strided
 * view — NumPy's `M.T` and `M[1:]` are views exactly as MatD's are — and reductions over
 * them take a different code path. Quoting one number per operation hid that entirely.
 *
 * Rows the Rust port does not have yet (`matmul`, `sigmoid`, `relu`, `mapParallel`)
 * print "—", so the table doubles as a coverage report against `rust/PARITY.md`.
 */
object MatBench:
  def println(s: String = ""): Unit = print(s"$s\n")

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "-nopython      ; skip the NumPy column",
    "-norust        ; skip the Rust column",
    "-python <exe>  ; interpreter to use (default: first on PATH with a working numpy)",
  )

  /** Must match `py/bench.py` and `rust/src/bin/bench_mat.rs`. */
  val N = 1000
  val MM = 512
  val Warmup = 16
  val Iters = 60

  /** Minimum wall time per call, in ms — least noisy estimator, and what the cheat
   *  sheet quotes. `sink` keeps the JIT from eliminating the whole computation. */
  def minMs(op: => Any): Double =
    var sink = 0L
    for _ <- 0 until Warmup do sink += op.hashCode
    var best = Double.MaxValue
    for _ <- 0 until Iters do
      val t0 = System.nanoTime()
      sink += op.hashCode
      val ms = (System.nanoTime() - t0) / 1e6
      if ms < best then best = ms
    // Reads `sink` so it is genuinely live; without a read the JIT is free to drop the
    // accumulation, and with it the operation being timed.
    if sink == Long.MinValue then println()
    best

  /** Row labels in display order, with the Scala expression for each.
   *
   *  Split into the two tables by the presence of an `@` in the label, which is the same
   *  convention the Python and Rust halves use. */
  def scalaRows: Vector[(String, Double)] =
    MatD.setSeed(42)
    val a   = MatD.randn(MM, MM)
    val b   = MatD.randn(MM, MM)
    val m   = MatD.randn(N, N)
    val m2  = MatD.randn(N, N)
    // log and sqrt need a positive operand; negatives would make those NaN benchmarks.
    val pos = m.abs + 1.0

    val ops = Vector[(String, () => Any)](
      "randn"     -> (() => { MatD.setSeed(42); MatD.randn(N, N) }),
      "matmul"    -> (() => a *@ b),
      "sigmoid"   -> (() => m.sigmoid),
      "relu"      -> (() => m.relu),
      "vectorize" -> (() => m.mapParallel(x => x * x + 2 * x + 1.0)),
      "add"       -> (() => m + m2),
      "mul"       -> (() => m *:* m2),
      "abs"       -> (() => m.abs),
      "exp"       -> (() => m.exp),
      "log"       -> (() => pos.log),
      "sqrt"      -> (() => pos.sqrt),
      "sum"       -> (() => m.sum),
      "mean"      -> (() => m.mean),
      "std"       -> (() => m.std),
      "min"       -> (() => m.min),
      "max"       -> (() => m.max),
      "argmax"    -> (() => m.argmax),
      "sum0"      -> (() => m.sum(0)),
      "sum1"      -> (() => m.sum(1)),
      "mean0"     -> (() => m.mean(0)),
      "min0"      -> (() => m.min(0)),
      "max1"      -> (() => m.max(1)),
      "std0"      -> (() => m.std(0)),
      "cumsum"    -> (() => m.cumsum),
      "cumsum1"   -> (() => m.cumsum(1)),
      "cummin1"   -> (() => m.cummin(1)),
      "cummax0"   -> (() => m.cummax(0)),
      "transpose" -> (() => m.T),
    )

    // Every one of these is a view: `.T` swaps strides, `slice` carries an offset, and
    // broadcastTo sets a zero row-stride. NumPy has all three, so the columns compare.
    val views = Vector(
      "contig"     -> m,
      "transposed" -> m.T,
      "rowslice"   -> m.slice(1 until N, 0 until N),
      "bcast"      -> m.slice(0 until 1, 0 until N).broadcastTo(N, N),
    )
    val layoutOps = for
      (vname, v) <- views
      (oname, f) <- Vector[(String, Mat[Double] => Any)](
        "sum"  -> (_.sum),
        "max"  -> (_.max),
        "std"  -> (_.std),
        "sum0" -> (_.sum(0)),
      )
    yield s"$oname@$vname" -> (() => f(v))

    for (label, f) <- ops ++ layoutOps yield
      val ms = minMs(f())
      println(f"  [Scala] $label%-28s $ms%10.4f ms/call")
      label -> ms

  def main(args: Array[String]): Unit =
    var runPython = true
    var runRust   = true
    var pyOverride: Option[String] = None
    eachArg(args.toSeq, usage) {
      case "-nopython" => runPython = false
      case "-norust"   => runRust = false
      case "-python"   => pyOverride = Some(consumeNext)
      case a           => usage(s"unrecognized arg [$a]")
    }

    val root = sys.props.getOrElse("user.dir", ".")
    println(s"── Scala MatD Benchmarks ──────────────────────────────────────────────")
    println(s"config: jvm=${System.getProperty("java.version")} N=$N MM=$MM warmup=$Warmup iters=$Iters")
    val scala = scalaRows.toMap

    val py = if !runPython then None else
      BenchRunner.findPython(pyOverride) match
        case None =>
          println("\n(no python with a working numpy found; skipping the NumPy column)")
          None
        case Some(exe) =>
          println(s"\n── NumPy  [${BenchRunner.pythonLabel(exe)}] ──")
          Some(BenchRunner.parse(BenchRunner.capture(Seq(exe, s"$root/py/bench.py"))))

    val rust = if !runRust then None else
      val exe = BenchRunner.rustExe(root, "bench_mat")
      if !java.io.File(exe).isFile then
        println(s"\n(no $exe — build it with:  cd rust && cargo build --release --bin bench_mat)")
        None
      else
        println("\n── Rust ──")
        if BenchRunner.staleAgainstSources(root, exe) then
          println("  WARNING: binary is older than rust/src — rebuild, or these numbers are stale")
        // Single-threaded OpenBLAS: the Rust reductions are already parallel via rayon,
        // and nesting fork/join inside fork/join costs ~10%.
        Some(BenchRunner.parse(BenchRunner.capture(Seq(exe), "OPENBLAS_NUM_THREADS" -> "1")))

    emit("MatD operations", opLabels, scala, py, rust)
    emit("MatD by layout", layoutLabels, scala, py, rust)
    for r <- rust do println(s"\nRust build: ${r.config}")
    for p <- py do println(s"NumPy build: ${p.config}")

  /** Display order for the two tables. Kept explicit rather than derived from the
   *  measured keys so a missing row shows up as "—" instead of vanishing. */
  val opLabels: Vector[String] = Vector(
    "randn", "matmul", "sigmoid", "relu", "vectorize", "add", "mul", "abs", "exp",
    "log", "sqrt", "sum", "mean", "std", "min", "max", "argmax", "sum0", "sum1",
    "mean0", "min0", "max1", "std0", "cumsum", "cumsum1", "cummax0", "cummin1",
    "transpose",
  )

  val layoutLabels: Vector[String] =
    for v <- Vector("contig", "transposed", "rowslice", "bcast"); o <- Vector("sum", "max", "std", "sum0")
    yield s"$o@$v"

  private def emit(title: String, labels: Vector[String], scala: Map[String, Double],
                   py: Option[BenchRunner.Result], rust: Option[BenchRunner.Result]): Unit =
    val hasPy   = py.exists(_.ms.nonEmpty)
    val hasRust = rust.exists(_.ms.nonEmpty)
    println(s"\n## $title — ms/call (lower is better)\n")
    // Each ratio column reads "<subject> vs <baseline>", and describes the SUBJECT: the
    // cell says how much faster or slower the first-named language is than the second.
    val headers = Vector("Operation") ++
      (if hasPy then Vector("NumPy") else Vector.empty) ++ Vector("Scala") ++
      (if hasRust then Vector("Rust") else Vector.empty) ++
      (if hasPy then Vector("Scala vs NumPy") else Vector.empty) ++
      (if hasRust then Vector("Rust vs NumPy") else Vector.empty) ++
      // The two ports against each other. Rust trailing Scala is a signal that the Rust
      // side is doing more work rather than that the language is slower, so it is worth
      // a standing column rather than an occasional hand comparison.
      (if hasRust then Vector("Rust vs Scala") else Vector.empty)
    val rows = for label <- labels yield
      val s = scala.get(label)
      val p = py.flatMap(_.ms.get(label))
      val r = rust.flatMap(_.ms.get(label))
      Vector(s"`$label`") ++
        (if hasPy then Vector(BenchRunner.cell(p)) else Vector.empty) ++
        Vector(BenchRunner.cell(s)) ++
        (if hasRust then Vector(BenchRunner.cell(r)) else Vector.empty) ++
        (if hasPy then Vector((for pv <- p; sv <- s yield BenchRunner.ratioCell(pv, sv)).getOrElse("—")) else Vector.empty) ++
        (if hasRust then Vector((for pv <- p; rv <- r yield BenchRunner.ratioCell(pv, rv)).getOrElse("—")) else Vector.empty) ++
        (if hasRust then Vector((for sv <- s; rv <- r yield BenchRunner.ratioCell(sv, rv)).getOrElse("—")) else Vector.empty)
    BenchRunner.table(headers, rows)

package uni.apps

import uni.*
import uni.data.*

/**
 * Every cross-language benchmark in one apples-to-apples table:
 *
 * {{{
 *   ./runBenchAll.sh                    # builds both Rust flavours, then runs this
 *   sbt "runMain uni.apps.BenchAll"     # just this, against whatever binaries exist
 * }}}
 *
 * # Why one table, and why five numeric columns
 *
 * NumPy multiplies through OpenBLAS, always. Since 0.16.1 the Scala and Rust DEFAULT is
 * the pinned pure loop, with BLAS an opt-in. A table with one column per language
 * therefore compared NumPy-with-BLAS against uni-without, and only on the rows where it
 * matters — a mish-mash a reader could not untangle. So every platform gets one table
 * with the same five columns:
 *
 * | NumPy | Scala | Rust | Scala·BLAS | Rust·BLAS |
 *
 * `Scala`/`Rust` are the default builds; the `·BLAS` columns are the opt-in on each side
 * (`-Duni.mat.blas=true`; `--features blas`). A BLAS column carries a number only on the
 * rows BLAS can touch — `matmul` and the 3PRF rows — and `·` elsewhere: elementwise and
 * reduction code is identical in both modes, and printing a second noisy copy of the same
 * number would be exactly the mish-mash this replaces. MatD rows are min-of-60 single
 * calls; 3PRF rows are medians of 25 (IS Full: 200) after warm-up, as they always were.
 *
 * # How each column is produced
 *
 *  - Scala: `MatBench.scalaRows` and `Tprf3Bench.run` in this JVM (default), plus
 *    `matmulBlas` per call for the BLAS `matmul` cell; the BLAS 3PRF cells come from a
 *    child JVM started with `-Duni.mat.blas=true` (the mode is read once per JVM, and
 *    `Tprf3` multiplies through `*@`).
 *  - NumPy: `py/bench.py` and `py/bench_tprf3.py`, first interpreter with a working numpy.
 *  - Rust: `rust/target/release/bench_mat_pure` / `bench_mat_blas` and
 *    `bench_tprf3_pure` / `bench_tprf3_blas` — the two builds `runBenchAll.sh` produces.
 *    A plain `bench_mat` / `bench_tprf3` counts as the default build. Any missing binary
 *    drops its column rather than failing the run, so a box without OpenBLAS dev libs
 *    still gets a table.
 *
 * # The summary
 *
 * Geometric mean of the per-row speedups, with the median beside it. Geometric mean is
 * the fair aggregate for ratios — an arithmetic mean is dragged by one 125× row
 * (`vectorize`, a Python loop) and a median ignores magnitude entirely; the two together
 * say whether the mean is carried by a few rows. Each summary line states how many rows
 * it covers, and the BLAS lines cover only the rows BLAS touches.
 */
object BenchAll:
  def println(s: String = ""): Unit = print(s"$s\n")

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "-nopython      ; skip the NumPy column",
    "-norust        ; skip the Rust columns",
    "-noblas        ; skip the two BLAS columns (no child JVM, no *_blas binaries)",
    "-python <exe>  ; interpreter to use (default: first on PATH with a working numpy)",
    "",
    "Build the Rust halves first (runBenchAll.sh does all of this):",
    "  cd rust && cargo build --release --bin bench_mat --bin bench_tprf3",
    "  cp target/release/bench_mat target/release/bench_mat_pure   (and bench_tprf3)",
    "  cargo build --release --features blas --bin bench_mat --bin bench_tprf3",
    "  cp target/release/bench_mat target/release/bench_mat_blas   (and bench_tprf3)",
  )

  /** One measured column: its label→ms map and a provenance line. */
  case class Column(name: String, ms: Map[String, Double], config: String)

  val Sizes: Vector[(String, Int, Int, Int)] = Vector(("Small", 200, 30, 2), ("Large", 650, 40, 2))
  val Scenarios: Vector[String] = Vector("IS Full", "OOS Rec", "OOS CV")

  val tprfLabels: Vector[String] =
    for (size, _, _, _) <- Sizes; scen <- Scenarios yield s"3PRF $scen ($size)"

  /** Rows a BLAS build can change. Everything else is identical code in both modes. */
  val blasRows: Set[String] = Set("matmul") ++ tprfLabels

  val allLabels: Vector[String] = MatBench.opLabels ++ MatBench.layoutLabels ++ tprfLabels

  def main(args: Array[String]): Unit =
    var runPython = true
    var runRust   = true
    var runBlas   = true
    var pyOverride: Option[String] = None
    eachArg(args.toSeq, usage) {
      case "-nopython" => runPython = false
      case "-norust"   => runRust = false
      case "-noblas"   => runBlas = false
      case "-python"   => pyOverride = Some(consumeNext)
      case a           => usage(s"unrecognized arg [$a]")
    }
    val root = sys.props.getOrElse("user.dir", ".")

    // ── Scala, default build, this JVM ───────────────────────────────────────────
    println("── Scala (default: pinned matmul) ────────────────────────────────────────")
    val jvm = s"jvm=${System.getProperty("java.version")}"
    val scalaMat = MatBench.scalaRows.toMap
    val scalaTprf = (for (size, t, n, l) <- Sizes yield
      val r = Tprf3Bench.run(size, t, n, l, warmupMs = if size == "Small" then 2000 else 500, loops = if size == "Small" then 50 else 20)
      Vector(s"3PRF IS Full ($size)" -> r.isFull, s"3PRF OOS Rec ($size)" -> r.oosRec, s"3PRF OOS CV ($size)" -> r.oosCv)
    ).flatten.toMap
    val scala = Column("Scala", scalaMat ++ scalaTprf,
      s"$jvm N=${MatBench.N} MM=${MatBench.MM} warmup=${MatBench.Warmup} iters=${MatBench.Iters}; 3PRF medians of 25 (IS Full 200) after warm-up")

    // ── Scala, BLAS mode ─────────────────────────────────────────────────────────
    val scalaBlas: Option[Column] = if !runBlas then None else
      println("\n── Scala·BLAS (-Duni.mat.blas=true) ───────────────────────────────────────")
      MatD.setSeed(42)
      val a = MatD.randn(MatBench.MM, MatBench.MM)
      val b = MatD.randn(MatBench.MM, MatBench.MM)
      val mm = MatBench.minMs(a.matmulBlas(b))
      println(f"  [Scala·BLAS] matmul $mm%10.4f ms/call")
      val child = BenchRunner.captureJvm("uni.apps.Tprf3Bench", Seq("-nopython", "-norust"),
        Seq("-Duni.mat.blas=true"))
      val tprf = parseTprf(child, "Scala")
      Some(Column("Scala·BLAS", Map("matmul" -> mm) ++ tprf,
        s"$jvm, matmul via matmulBlas; 3PRF in a child JVM with -Duni.mat.blas=true (netlib/bundled OpenBLAS per platform)"))

    // ── NumPy ────────────────────────────────────────────────────────────────────
    val py: Option[Column] = if !runPython then None else
      BenchRunner.findPython(pyOverride) match
        case None =>
          println("\n(no python with a working numpy found; skipping the NumPy column)")
          None
        case Some(exe) =>
          val label = BenchRunner.pythonLabel(exe)
          println(s"\n── NumPy  [$label] ──")
          val mat = BenchRunner.parse(BenchRunner.capture(Seq(exe, s"$root/py/bench.py")))
          val tprf = parseTprf(BenchRunner.capture(Seq(exe, "-u", s"$root/py/bench_tprf3.py")), "Python Fast")
          Some(Column("NumPy", mat.ms ++ tprf, s"$label; OpenBLAS is NumPy's only mode"))

    // ── Rust, two builds ─────────────────────────────────────────────────────────
    def rustColumn(flavour: String, colName: String): Option[Column] =
      val matExe  = rustBinary(root, "bench_mat", flavour)
      val tprfExe = rustBinary(root, "bench_tprf3", flavour)
      if matExe.isEmpty && tprfExe.isEmpty then
        println(s"\n(no Rust $flavour binaries — see -h; skipping the $colName column)")
        None
      else
        println(s"\n── $colName ──")
        // bench_mat runs with OpenBLAS's default threading, like NumPy and Scala·BLAS do:
        // its `matmul` row is exactly the one where BLAS threads matter. bench_tprf3 is
        // pinned to one BLAS thread because its OOS loops are already rayon-parallel over
        // windows, and nesting BLAS threads inside that both slows it and makes the reading
        // depend on core count.
        val mat = matExe.map { exe =>
          if BenchRunner.staleAgainstSources(root, exe) then println("  WARNING: binary is older than rust/src")
          BenchRunner.parse(BenchRunner.capture(Seq(exe)))
        }
        val tprf = tprfExe.map(exe => parseTprf(BenchRunner.capture(Seq(exe), "OPENBLAS_NUM_THREADS" -> "1"), "Rust"))
        val cfg = mat.map(_.config).getOrElse("?")
        Some(Column(colName, mat.map(_.ms).getOrElse(Map.empty) ++ tprf.getOrElse(Map.empty),
          s"$cfg ($flavour build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)"))
    val rust     = if runRust then rustColumn("pure", "Rust") else None
    val rustBlas = if runRust && runBlas then rustColumn("blas", "Rust·BLAS") else None

    // ── The table ────────────────────────────────────────────────────────────────
    val cols = Vector(py, Some(scala), rust, scalaBlas, rustBlas).flatten
    println("\n## Every row, every language, both matmul modes — ms/call (lower is better)\n")
    val headers = Vector("Operation") ++ cols.map(_.name) ++
      (if py.isDefined then Vector("Scala vs NumPy") else Vector.empty) ++
      (if py.isDefined && rust.isDefined then Vector("Rust vs NumPy") else Vector.empty) ++
      (if rust.isDefined then Vector("Rust vs Scala") else Vector.empty)
    val rows = for label <- allLabels yield
      val cells = cols.map { c =>
        if c.name.endsWith("BLAS") && !blasRows(label) then "·"
        else BenchRunner.cell(c.ms.get(label))
      }
      val p = py.flatMap(_.ms.get(label)); val s = scala.ms.get(label); val r = rust.flatMap(_.ms.get(label))
      def ratio(x: Option[Double], y: Option[Double]) = (for a <- x; b <- y yield BenchRunner.ratioCell(a, b)).getOrElse("—")
      Vector(s"`$label`") ++ cells ++
        (if py.isDefined then Vector(ratio(p, s)) else Vector.empty) ++
        (if py.isDefined && rust.isDefined then Vector(ratio(p, r)) else Vector.empty) ++
        (if rust.isDefined then Vector(ratio(s, r)) else Vector.empty)
    BenchRunner.table(headers, rows)

    // ── Provenance ───────────────────────────────────────────────────────────────
    println()
    for c <- cols do println(s"- **${c.name}**: ${c.config}")
    println("- BLAS columns carry a number only on the rows BLAS can change (`matmul`, 3PRF); `·` elsewhere.")

    // ── Summary ──────────────────────────────────────────────────────────────────
    println("\n## Summary — speedup of the first-named over the second (geometric mean; median; rows)\n")
    val pairs: Vector[(String, Option[Column], Option[Column], Set[String])] = Vector(
      ("Scala vs NumPy",      Some(scala), py,        allLabels.toSet),
      ("Rust vs NumPy",       rust,        py,        allLabels.toSet),
      ("Rust vs Scala",       rust,        Some(scala), allLabels.toSet),
      ("Scala·BLAS vs NumPy", scalaBlas,   py,        blasRows),
      ("Rust·BLAS vs NumPy",  rustBlas,    py,        blasRows),
      ("Scala·BLAS vs Scala", scalaBlas,   Some(scala), blasRows),
      ("Rust·BLAS vs Rust",   rustBlas,    rust,      blasRows),
    )
    val srows = for (name, subj, base, on) <- pairs; s <- subj; b <- base yield
      val speedups = allLabels.filter(on).flatMap(l => for x <- s.ms.get(l); y <- b.ms.get(l) if x > 0 && y > 0 yield y / x)
      if speedups.isEmpty then Vector(name, "—", "—", "0")
      else Vector(name, f"${geomean(speedups)}%.2f×", f"${median(speedups)}%.2f×", speedups.length.toString)
    BenchRunner.table(Vector("pair", "geomean", "median", "rows"), srows)
    println("\nA speedup above 1× means the first-named is faster. Geometric mean is the fair aggregate for")
    println("ratios; the median beside it shows whether one row is carrying the mean. BLAS lines cover")
    println("only the rows BLAS touches.")

  // ── helpers ────────────────────────────────────────────────────────────────────

  /** `<root>/rust/target/release/<name>_<flavour>[.exe]`; a plain `<name>` counts as the
   *  default build. */
  private def rustBinary(root: String, name: String, flavour: String): Option[String] =
    val cands = Vector(s"${name}_$flavour") ++ (if flavour == "pure" then Vector(name) else Vector.empty)
    cands.map(n => BenchRunner.rustExe(root, n)).find(p => java.io.File(p).isFile)

  /** The 3PRF rows from any of the three benches' output: a `── Small (T=…` header sets
   *  the size; `[<tag>] [Tprf3.]estimate3prf <scenario>  N ms/call` adds a row. */
  def parseTprf(lines: Seq[String], tag: String): Map[String, Double] =
    val header = """.*──\s+(\S+)\s+\(T=.*""".r
    val row    = ("""^.*\[""" + java.util.regex.Pattern.quote(tag) + """\]\s+(?:Tprf3\.)?estimate3prf\s+(IS Full|OOS Rec|OOS CV)\s+([0-9.]+)\s+ms/call.*$""").r
    lines.foldLeft((Option.empty[String], Map.empty[String, Double])) {
      case ((_, acc), header(size))     => (Some(size), acc)
      case ((cur, acc), row(scen, ms))  => (cur, acc + (s"3PRF $scen (${cur.getOrElse("?")})" -> ms.toDouble))
      case (state, _)                   => state
    }._2

  def geomean(xs: Seq[Double]): Double = math.exp(xs.map(math.log).sum / xs.length)
  def median(xs: Seq[Double]): Double =
    val s = xs.sorted; val n = s.length
    if n % 2 == 1 then s(n / 2) else (s(n / 2 - 1) + s(n / 2)) / 2

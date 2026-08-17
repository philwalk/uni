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
 * NumPy multiplies through OpenBLAS, always. Scala's `*@` does too by default since
 * 0.17.0 (`-Duni.mat.blas=os-best`); Rust's does not — its BLAS is a build-time feature
 * (`--features blas`), which a library cannot switch on for its users, so the Rust
 * default is the pinned pure loop. A table with one column per language would therefore
 * mix BLAS and no-BLAS on the very rows where it matters. So every platform gets one
 * table with the same five columns, each a named configuration:
 *
 * | NumPy | Scala·pure | Rust·pure | Scala·BLAS | Rust·BLAS |
 *
 * `·pure` is the pinned loop on each side (bit-identical across the two ports and every
 * machine); `·BLAS` is each side's BLAS (`-Duni.mat.blas=<mode>`, `--features blas`). A
 * BLAS column carries a number only on the rows BLAS can touch — `matmul` and the 3PRF
 * rows — and `·` elsewhere: elementwise and reduction code is identical in both modes,
 * and printing a second noisy copy of the same number would be exactly the mish-mash this
 * replaces. MatD rows are min-of-60 single calls; 3PRF rows are medians of 25 (IS Full:
 * 200) after warm-up.
 *
 * # How each column is produced
 *
 *  - Scala·pure: `MatBench.scalaRows` in this JVM with the `matmul` cell re-measured
 *    through `matmulPure`, and `Tprf3Bench` in a child JVM under `-Duni.mat.blas=pure`.
 *  - Scala·BLAS: child JVMs under `-Duni.mat.blas=<mode>` (`MatmulBlasCell` for the
 *    matmul cell, `Tprf3Bench` for the 3PRF cells, which multiply through `*@`). The mode
 *    is read once per JVM, which is why neither Scala flavour trusts this JVM's `*@`.
 *  - NumPy: `py/bench.py` and `py/bench_tprf3.py`, first interpreter with a working numpy.
 *  - Rust: `rust/target/release/bench_mat_pure` / `bench_mat_blas` and
 *    `bench_tprf3_pure` / `bench_tprf3_blas` — the two builds `runBenchAll.sh` produces.
 *    A plain `bench_mat` / `bench_tprf3` counts as the pure build. Any missing binary
 *    drops its column rather than failing the run, so a box without OpenBLAS dev libs
 *    still gets a table.
 *
 * # The executive summary, and why it comes first
 *
 * The languages are compared like for like: BLAS on all three (each port's BLAS figure on
 * the 7 rows BLAS affects, its pure figure elsewhere — the same code in both modes) and,
 * separately, the pinned loop in both ports. Rust's default being pure is a packaging
 * constraint, not a language result, so it is never the number that represents Rust in a
 * language comparison; the as-shipped block says what each default costs, labelled as a
 * comparison of CONFIGURATIONS. Every line is a geometric mean of per-row speedups with
 * the median beside it — an arithmetic mean is dragged by one 125× row (`vectorize`, a
 * Python loop) and a median ignores magnitude; together they say whether the mean is
 * carried by a few rows — and says how many rows it covers.
 */
object BenchAll:
  def println(s: String = ""): Unit = print(s"$s\n")

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "-nopython      ; skip the NumPy column",
    "-norust        ; skip the Rust columns",
    "-noblas        ; skip the two BLAS columns (no child JVM, no *_blas binaries)",
    "-blas <mode>   ; uni.mat.blas value for the Scala·BLAS column: os-best (default) | bundled | system",
    "-python <exe>  ; interpreter to use (default: first on PATH with a working numpy)",
    "",
    "Build the Rust halves first (runBenchAll.sh does all of this):",
    "  cd rust && cargo build --release --bin bench_mat --bin bench_tprf3",
    "  cp target/release/bench_mat target/release/bench_mat_pure   (and bench_tprf3)",
    "  cargo build --release --features blas --target-dir target/blas --bin bench_mat --bin bench_tprf3",
    "  cp target/blas/release/bench_mat target/release/bench_mat_blas   (and bench_tprf3)",
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
    var blasMode  = "os-best"
    var pyOverride: Option[String] = None
    eachArg(args.toSeq, usage) {
      case "-nopython" => runPython = false
      case "-norust"   => runRust = false
      case "-noblas"   => runBlas = false
      case "-blas"     => blasMode = consumeNext
      case "-python"   => pyOverride = Some(consumeNext)
      case a           => usage(s"unrecognized arg [$a]")
    }
    val root = sys.props.getOrElse("user.dir", ".")

    // ── Scala·pure: elementwise rows in this JVM, matmul via matmulPure, 3PRF in a
    //    child JVM pinned to `pure` (this JVM's mode is whatever sbt gave it) ────────
    println("── Scala·pure (pinned matmul) ────────────────────────────────────────────")
    val jvm = s"jvm=${System.getProperty("java.version")}"
    val scalaMat = MatBench.scalaRows.toMap ++ {
      MatD.setSeed(42)
      val a = MatD.randn(MatBench.MM, MatBench.MM)
      val b = MatD.randn(MatBench.MM, MatBench.MM)
      val mm = MatBench.minMs(a.matmulPure(b))
      println(f"  [Scala·pure] matmul $mm%10.4f ms/call")
      Map("matmul" -> mm)
    }
    val pureChild = BenchRunner.captureJvm("uni.apps.Tprf3Bench", Seq("-nopython", "-norust"), Seq("-Duni.mat.blas=pure"))
    pureChild.filter(_.contains("estimate3prf")).foreach(println)
    val scala = Column("Scala·pure", scalaMat ++ parseTprf(pureChild, "Scala"),
      s"$jvm N=${MatBench.N} MM=${MatBench.MM} warmup=${MatBench.Warmup} iters=${MatBench.Iters}; matmul via matmulPure; 3PRF in a child JVM with -Duni.mat.blas=pure, medians of 25 (IS Full 200) after warm-up")

    // ── Scala·BLAS: child JVMs in the requested mode ─────────────────────────────
    val scalaBlas: Option[Column] = if !runBlas then None else
      val prop = s"-Duni.mat.blas=$blasMode"
      println(s"\n── Scala·BLAS ($prop) ───────────────────────────────────────")
      val cell = BenchRunner.captureJvm("uni.apps.MatmulBlasCell", Seq(), Seq(prop, "-Duni.blas.verbose=true"))
      cell.foreach(println)
      val mmRow = """^\s*\[Scala\]\s+matmul\s+([0-9.eE+-]+)\s+ms/call\s*$""".r
      val mm = cell.collectFirst { case mmRow(ms) => ms.toDouble }
      val child = BenchRunner.captureJvm("uni.apps.Tprf3Bench", Seq("-nopython", "-norust"), Seq(prop))
      child.filter(_.contains("estimate3prf")).foreach(println)
      val tprf = parseTprf(child, "Scala")
      Some(Column("Scala·BLAS", mm.map("matmul" -> _).toMap ++ tprf,
        s"$jvm, child JVMs with $prop (backend per platform and mode; see the [uni] line above)"))

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
    val rust     = if runRust then rustColumn("pure", "Rust·pure") else None
    val rustBlas = if runRust && runBlas then rustColumn("blas", "Rust·BLAS") else None

    // ── Like-for-like maps: each port's BLAS figure on the rows BLAS affects, its pure
    //    figure elsewhere (identical code). Absent a BLAS column, the pure map stands in.
    def withBlas(pure: Column, blas: Option[Column]): Map[String, Double] =
      pure.ms ++ blas.map(_.ms.filter((k, _) => blasRows(k))).getOrElse(Map.empty)
    val scalaLfl = withBlas(scala, scalaBlas)
    val rustLfl  = rust.map(r => withBlas(r, rustBlas))
    val all      = allLabels.toSet
    val pyMs = py.map(_.ms); val scMs = Some(scala.ms); val ruMs = rust.map(_.ms)
    def speedups(subj: Map[String, Double], base: Map[String, Double], on: Set[String]): Seq[Double] =
      allLabels.filter(on).flatMap(l => for x <- subj.get(l); y <- base.get(l) if x > 0 && y > 0 yield y / x)
    def line(name: String, subj: Option[Map[String, Double]], base: Option[Map[String, Double]], on: Set[String]): Option[Vector[String]] =
      for s <- subj; b <- base yield
        val sp = speedups(s, b, on)
        if sp.isEmpty then Vector(name, "—", "—", "0")
        else Vector(name, f"${geomean(sp)}%.2f×", f"${median(sp)}%.2f×", sp.length.toString)
    val hdr = Vector("comparison", "geomean", "median", "rows")

    // ── Executive summary ────────────────────────────────────────────────────────
    println()
    println("## Executive summary")
    println()
    println("Speedup = (second-named ms) ÷ (first-named ms), geometric mean over the rows named, with the")
    println("median beside it; above 1× means the first-named is faster.")
    println()
    println("### Languages, BLAS on all three — the like-for-like comparison")
    println()
    println("NumPy is always OpenBLAS; here Scala and Rust use theirs too on the 7 rows BLAS affects (every")
    println("other row is the same code in both modes). Rust's BLAS is a build-time feature, so its DEFAULT")
    println("build is the pure loop — a packaging constraint, not a language result; this block is Rust's")
    println("fair number.")
    println()
    BenchRunner.table(hdr, Vector(
      line("Scala vs NumPy", Some(scalaLfl), pyMs, all),
      line("Rust vs NumPy",  rustLfl,  pyMs, all),
      line("Rust vs Scala",  rustLfl,  Some(scalaLfl), all),
    ).flatten)
    println()
    println("### The pinned loop, both ports — bit-identical results, no BLAS anywhere")
    println()
    BenchRunner.table(hdr, Vector(
      line("Rust·pure vs Scala·pure", ruMs, scMs, all),
      line("Rust·pure vs Scala·pure (matmul + 3PRF only)", ruMs, scMs, blasRows),
    ).flatten)
    println()
    println("### Configurations, as shipped — what a user gets without flags")
    println()
    println("Scala's default is BLAS (`os-best`); Rust's default build is the pure loop (`--features blas`")
    println("opts in). Against NumPy's BLAS this is the price of each default, not a statement about the")
    println("languages.")
    println()
    BenchRunner.table(hdr, Vector(
      line("Scala (default: BLAS) vs NumPy", Some(scalaLfl), pyMs, all),
      line("Rust (default: pure) vs NumPy",  ruMs, pyMs, all),
      line("Rust (default: pure) vs Scala (default: BLAS)", ruMs, Some(scalaLfl), all),
    ).flatten)
    println()
    println("### What BLAS buys each port, over the 7 rows it affects")
    println()
    BenchRunner.table(hdr, Vector(
      line("Scala·BLAS vs Scala·pure", scalaBlas.map(_.ms), scMs, blasRows),
      line("Rust·BLAS vs Rust·pure",   rustBlas.map(_.ms),  ruMs, blasRows),
    ).flatten)

    // ── The table ────────────────────────────────────────────────────────────────
    val cols = Vector(py, Some(scala), rust, scalaBlas, rustBlas).flatten
    println("\n## Every row, every configuration — ms/call (lower is better)\n")
    println("The three ratio columns are like for like: BLAS on all three where BLAS applies.\n")
    val headers = Vector("Operation") ++ cols.map(_.name) ++
      (if py.isDefined then Vector("Scala vs NumPy") else Vector.empty) ++
      (if py.isDefined && rust.isDefined then Vector("Rust vs NumPy") else Vector.empty) ++
      (if rust.isDefined then Vector("Rust vs Scala") else Vector.empty)
    val rows = for label <- allLabels yield
      val cells = cols.map { c =>
        if c.name.endsWith("BLAS") && !blasRows(label) then "·"
        else BenchRunner.cell(c.ms.get(label))
      }
      val p = py.flatMap(_.ms.get(label)); val s = scalaLfl.get(label); val r = rustLfl.flatMap(_.get(label))
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

/** The `Scala·BLAS` matmul cell, run in a child JVM so `-Duni.mat.blas=<mode>` applies:
 *  one `[Scala] matmul` row in the harness format, same shape and timing as `MatBench`. */
object MatmulBlasCell:
  def main(args: Array[String]): Unit =
    MatD.setSeed(42)
    val a = MatD.randn(MatBench.MM, MatBench.MM)
    val b = MatD.randn(MatBench.MM, MatBench.MM)
    val mm = MatBench.minMs(a *@ b)
    print(f"  [Scala] matmul $mm%10.4f ms/call\n")

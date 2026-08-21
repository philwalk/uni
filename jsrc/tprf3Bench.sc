#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation -q
//package uni.apps

//> using jvm 22
//> using scala 3.8.4
//> using dep org.vastblue:uni_3:0.19.1

import uni.*
import uni.data.*
import uni.stats.Tprf3
import scala.sys.process.*

/**
 * Benchmarks Tprf3.t3prf and Tprf3.estimate3prf across two data sizes,
 * then invokes py/bench_tprf3.py and prints a side-by-side comparison table
 * (Python vs Scala, with the ratio) in the same markdown style as
 * docs/MatDCheatSheet.md.
 *
 * All three columns now run on bit-identical input matrices: each side seeds its
 * NumPy-compatible PCG64 with 0 and draws X, y, Z in that order, so the table
 * measures implementations rather than a mix of implementation and input. The
 * Rust column used to generate its own inputs with `StdRng` and a Box-Muller
 * transform, which made it the odd one out.
 *
 * Run:  scala-cli jsrc/tprf3Bench.sc
 */
object Tprf3Bench {
  def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "[-nopython]     ; skip the python benchmarks",
      "[-norust]       ; skip the rust benchmarks",
      "[-python <exe>] ; python interpreter (default: first on PATH that has numpy)",
    )
  }

  /** Scala timings for one data size. `isFull` is `estimate3prf IS Full`
   *  (the apples-to-apples match for Python's `estimate3prf_fast`); `t3prf`
   *  is the direct fast path, reported but not compared (Python has no twin). */
  case class ScalaRes(label: String, T: Int, N: Int, L: Int,
                      t3prf: Double, isFull: Double, oosRec: Double, oosCv: Double)

  /** The three scenarios that exist on both sides, in display order. */
  private val scenarios = List("IS Full", "OOS Rec", "OOS CV")

  var runPython = true
  var runRustBench = true
  var pythonOverride: Option[String] = None
  def main(args: Array[String]): Unit = {
    eachArg(args.toSeq, usage) {
    case "-nopython" =>
      runPython = false
    case "-norust" =>
      runRustBench = false
    case "-python" =>
      pythonOverride = Some(consumeNext)
    case arg =>
      usage(s"unrecognized arg [$arg]")
    }

    val rootDir = sys.props.getOrElse("user.dir", ".")
    val script  = Paths.get(s"$rootDir/py/bench_tprf3.py").posx

    println("── Scala benchmarks ─────────────────────────────────────────────────────────")
    // Small runs first in the freshly forked JVM and bears JIT startup plus
    // any post-compile/Bloop or CPU-ramp disturbance; warmupMs is the floor —
    // warmupUntilStable keeps going until per-call time settles. Large reuses
    // the now-hot code paths, so a shorter floor suffices.
    val scalaResults = List(
      run("Small", T = 200, N = 30, L = 2, warmupMs = 2000, loops = 50),
      run("Large", T = 650, N = 40, L = 2, warmupMs = 500,  loops = 20),
    )

    // ── Python benchmarks: run, echo live, and capture lines for the table ──
    val pyLines: List[String] =
      if !runPython then Nil
      else selectPython(script) match
        case None              => Nil
        case Some((hdr, exe))  => runBenchCapture(hdr, exe, script)

    // ── Rust: reported only when a release binary is already built ─────────
    val rust: Option[RustRes] = if !runRustBench then None else runRust(rootDir)

    printComparison(scalaResults, parsePython(pyLines), rust)
  }

  private def medianOf(samples: Array[Double]): Double =
    java.util.Arrays.sort(samples)
    val n = samples.length
    if n % 2 == 1 then samples(n / 2)
    else (samples(n / 2 - 1) + samples(n / 2)) / 2.0

  /** Median ms-per-call over `loops` individually timed runs. Median, not
   *  mean: a residual JIT tail or a single background blip then shifts one
   *  sample instead of polluting the whole reading. */
  private def bench(loops: Int)(block: => Unit): Double =
    val times = Array.ofDim[Double](loops)
    var i = 0
    while i < loops do
      val t0 = System.nanoTime()
      block
      times(i) = (System.nanoTime() - t0) / 1e6
      i += 1
    medianOf(times)

  /** Drive `block` `n` times before timing — forces JIT compilation and spins
   *  up the ForkJoin pool. Needed for the OOS procedures: they take a different
   *  code path (the OOS branch + parallel collections) than the IS Full
   *  warm-up exercises, so without this they are timed from cold. */
  private def warm(n: Int)(block: => Unit): Unit =
    var i = 0
    while i < n do { block; i += 1 }

  /** Adaptive warm-up: times `block` in batches of `win` calls and stops once
   *  the last three batch medians agree within `tol` (and at least `minMs` has
   *  elapsed), or at `maxMs`. A fixed-duration warm-up loses to external
   *  disturbances that outlast it — the Bloop server settling after a compile,
   *  CPU boost/core-unparking — observed medians of 3–4 ms/call (vs 0.6
   *  steady-state) over a whole 50-loop section. Waiting for stability
   *  sidesteps the cause. Limitation: a disturbance that is itself stable for
   *  seconds is indistinguishable from steady state from inside the process;
   *  the elapsed time is returned (and printed) so such runs are visible.
   */
  private def warmupUntilStable(minMs: Int, maxMs: Int = 15000, win: Int = 15,
                                tol: Double = 0.20)(block: => Unit): Double =
    val start = System.nanoTime()
    def elapsedMs: Double = (System.nanoTime() - start) / 1e6
    val batch = Array.ofDim[Double](win)
    var m2, m1 = Double.MaxValue   // medians of the two previous batches
    var stable = false
    while !stable && elapsedMs < maxMs do
      var i = 0
      while i < win do
        val t0 = System.nanoTime()
        block
        batch(i) = (System.nanoTime() - t0) / 1e6
        i += 1
      val m0 = medianOf(batch)
      val hi = math.max(m0, math.max(m1, m2))
      val lo = math.min(m0, math.min(m1, m2))
      stable = elapsedMs >= minMs && hi <= lo * (1 + tol)
      m2 = m1; m1 = m0
    elapsedMs / 1000.0

  def run(label: String, T: Int, N: Int, L: Int, warmupMs: Int, loops: Int): ScalaRes =
    println(s"\n── $label  (T=$T  N=$N  L=$L  warmup>=${warmupMs}ms  loops=$loops) ──")
    Mat.setSeed(0)
    val X: MatD = MatD.randn(T, N)
    val y: MatD = MatD.randn(T, 1)
    val Z: MatD = MatD.randn(T, L)

    print("  warming up ... ")
    Console.flush()
    val warmSecs = warmupUntilStable(minMs = warmupMs) {
      Tprf3.t3prf(y, X, Z)
      Tprf3.estimate3prf(y, X, Right(Z), procedure = "IS Full")
    }
    printf("done (%.1f s)\n", warmSecs)

    // ── IS Full ───────────────────────────────────────────────────────────
    // IS Full is sub-millisecond, so `loops` sized for the OOS procedures gives
    // only a few ms of total measurement — one GC pause from the neighbouring
    // OOS sections then moves the median by 2x. Sample it far more times; the
    // extra cost is negligible precisely because each call is so cheap.
    // Settle the heap first: garbage left by the PREVIOUS size's OOS sections
    // otherwise lands GC pauses inside this sub-millisecond sample window. That
    // is what made Large IS Full read 0.56 ms here against 0.25 ms measured
    // standalone — an artefact of neighbouring benchmarks, not of the operation.
    System.gc(); Thread.sleep(200)
    val isLoops  = math.max(loops, 200)
    val msFast   = bench(isLoops) { Tprf3.t3prf(y, X, Z) }
    val ms3prf   = bench(isLoops) { Tprf3.estimate3prf(y, X, Right(Z), procedure = "IS Full") }

    printf("  [Scala]  %-26s  %8.2f ms/call\n", "Tprf3.t3prf", msFast)
    printf("  [Scala]  %-26s  %8.2f ms/call\n", "Tprf3.estimate3prf IS Full", ms3prf)

    // ── OOS Recursive / Cross Val ─────────────────────────────────────────
    // Warm each OOS procedure explicitly (the IS Full warm-up above never runs
    // them, so the old code timed them cold — JIT + ForkJoin spin-up landed in
    // the samples) and take the median of many runs (the OOS path allocates per
    // window, so a GC pause then shifts one sample, not the reading). The prior
    // `max(5, loops/10)` = 5 cold samples gave 5–55 ms spreads for identical code.
    val oosWarm  = 10
    val oosLoops = 25

    warm(oosWarm) {
      Tprf3.estimate3prf(y, X, Right(Z), procedure = "OOS Recursive", mintrain = (T / 2, 0))
    }
    val msOosRec = bench(oosLoops) {
      Tprf3.estimate3prf(y, X, Right(Z), procedure = "OOS Recursive", mintrain = (T / 2, 0))
    }
    printf("  [Scala]  %-26s  %8.2f ms/call  (loops=%d)\n",
      "Tprf3.estimate3prf OOS Rec", msOosRec, oosLoops)

    warm(oosWarm) {
      Tprf3.estimate3prf(y, X, Right(Z), procedure = "OOS Cross Val")
    }
    val msCv = bench(oosLoops) {
      Tprf3.estimate3prf(y, X, Right(Z), procedure = "OOS Cross Val")
    }
    printf("  [Scala]  %-26s  %8.2f ms/call  (loops=%d)\n",
      "Tprf3.estimate3prf OOS CV", msCv, oosLoops)

    ScalaRes(label, T, N, L, msFast, ms3prf, msOosRec, msCv)

  /** Known native-Windows Python installations to try, after /opt/winPython. */
  private val winPythonCandidates: List[String] = List(
    "F:/WPy64-3.14.3.0/python/python.exe",
  )

  /** WinPython installs under /opt/winPython, newest-looking first. Discovered
   *  rather than hard-coded: the version-stamped directory (WPy64-NNNNNN)
   *  changes with every upgrade. */
  private def optWinPythons(): List[String] =
    val rootPath = Paths.get("/opt/winPython").posx
    val root     = java.io.File(rootPath)
    if !root.isDirectory then Nil
    else Option(root.listFiles).getOrElse(Array.empty[java.io.File])
      .filter(_.isDirectory)
      .map(d => s"$rootPath/${d.getName}/python/python.exe")
      .filter(p => java.io.File(p).isFile)
      .sorted.reverse       // version-stamped names sort oldest-first
      .toList

  /** An interpreter is usable only if it can actually import numpy. Probing with
   *  `--version` accepts installs whose numpy is broken, which then fails deep
   *  inside the bench script — as the F: WinPython above currently does. */
  private def usablePython(p: String): Boolean =
    try Seq(p, "-c", "import numpy; numpy.nan").!(ProcessLogger(_ => (), _ => ())) == 0
    catch case _: Exception => false

  private def findPython(): Option[String] =
    // MSYS2 paths resolved to Windows equivalents via Paths.get().posx
    // for MacOs, this must list homebrew ahead of /usr/bin/python3 (otherwise, no numpy)
    // (or, may need to brew install numpy)
    val msys2Paths = List("/ucrt64/bin/python3.exe", "/opt/homebrew/bin/python3", "/usr/bin/python3")
    val candidates = msys2Paths.map(Paths.get(_).posx) ++ List("python3", "python")
    candidates.find(usablePython)

  private def findWinPython(): Option[String] =
    (optWinPythons() ++ winPythonCandidates).find(usablePython)

  /** Resolves the python interpreter to run and the section header to print,
   *  or None (with a diagnostic) when none is available. */
  private def selectPython(script: String): Option[(String, String)] =
    if !java.io.File(script).exists() then
      println(s"\n(bench script not found: $script)")
      None
    // an explicit -python wins over discovery: the preferred WinPython can have
    // a broken numpy, and this is the way past it
    else if pythonOverride.isDefined then
      // an MSYS2-style absolute path needs resolving to its Windows equivalent,
      // the same way findPython does; a bare command name is left alone
      val raw = pythonOverride.get
      val exe = if raw.startsWith("/") then Paths.get(raw).posx else raw
      Some((s"── Python benchmarks  [${pythonLabel(exe)}] ──────────────────────────────", exe))
    else findWinPython() match
      case Some(exe) =>
        Some((s"── WinPython benchmarks  [${pythonLabel(exe)}] ───────────────────────────", exe))
      case None =>
        if isWin then println("\n(WinPython not found)")
        findPython() match
          case Some(exe) =>
            Some((s"── Python benchmarks  [${pythonLabel(exe)}] ──────────────────────────────", exe))
          case None =>
            println("\n(python3 not found; skipping)")
            None

  /** Returns "Python X.Y.Z  (blas-name)" for display in the section header. */
  private def pythonLabel(exe: String): String =
    val ver = try
      var v = ""
      Seq(exe, "-c", "import sys; print(sys.version.split()[0])").!(ProcessLogger(v = _, _ => ()))
      v.trim
    catch case _: Exception => "?"
    // numpy >= 2 exposes the config as a dict. The older text dump is JSON-ish
    // on some builds ("name": "msvc"), where a plain scan for a "name:" prefix
    // finds the compiler first, or nothing at all. On numpy < 2 this raises and
    // the catch yields "?", as before.
    val blas = try
      var out = ""
      Seq(exe, "-c",
        "import numpy as np,warnings;warnings.filterwarnings('ignore');" +
        "d=np.show_config('dicts');" +
        "print(((d.get('Build Dependencies') or {}).get('blas') or {}).get('name','?'))"
      ).!(ProcessLogger(out = _, _ => ()))
      val t = out.trim
      if t.isEmpty then "?" else t
    catch case _: Exception => "?"
    s"Python $ver  ($blas)"

  // ── Rust ────────────────────────────────────────────────────────────────────

  /** Rust timings keyed by (size-label, scenario), with the configuration the
   *  binary reported for itself and whether it predates its own sources. */
  case class RustRes(config: String, stale: Boolean,
                     ms: Map[(String, String), Double])

  private def rustExePath(rootDir: String): String =
    val base = s"$rootDir/rust/target/release/bench_tprf3"
    // rootDir comes from user.dir, which is a native path — normalise so the
    // result never mixes separators in the diagnostics below
    Paths.get(if isWin then s"$base.exe" else base).posx

  /** Newest mtime among the crate's own sources. `target` is deliberately not
   *  walked: it is large and holds generated .rs files that would always look
   *  newer than the binary built from them. */
  private def newestRustSource(dir: java.io.File): Long =
    if !dir.isDirectory then 0L
    else Option(dir.listFiles).getOrElse(Array.empty[java.io.File]).foldLeft(0L) { (acc, f) =>
      if f.isDirectory then math.max(acc, newestRustSource(f))
      else if f.getName.endsWith(".rs") then math.max(acc, f.lastModified)
      else acc
    }

  /** Runs the prebuilt Rust bench, if it is there.
   *
   *  Gated on the binary already existing rather than building it: that keeps
   *  this harness out of the cargo/toolchain business, lets the Scala+Python
   *  comparison run on a machine with no Rust at all, and — since the `blas`
   *  and pure-Rust builds land at the same path — leaves the choice of build
   *  entirely to whoever ran cargo. The binary reports which one it is, so the
   *  table can say so instead of silently mixing them.
   *
   *  OPENBLAS_NUM_THREADS=1 is set here because the OOS loops are already
   *  parallel over windows; letting OpenBLAS spawn its own threads nests
   *  fork/join inside fork/join and both slows it down and makes the reading
   *  depend on the host's core count. */
  private def runRust(rootDir: String): Option[RustRes] =
    val exe  = rustExePath(rootDir)
    val file = java.io.File(exe)
    if !file.isFile then
      println(s"\n(no Rust binary at $exe — build it with:")
      println( "   cd rust && cargo build --release            # or --release --features blas")
      println( " skipping the Rust column)")
      None
    else
      val newestSrc = List("src", "tests")
        .map(d => newestRustSource(java.io.File(s"$rootDir/rust/$d")))
        .max
      val stale = newestSrc > file.lastModified
      println("\n── Rust benchmarks ─────────────────────────────────────────────────────────")
      if stale then
        println("  WARNING: binary is older than rust/src — rebuild, or these numbers are stale")
      val buf = scala.collection.mutable.ListBuffer.empty[String]   // local accumulator only
      val logger = ProcessLogger(
        out => { println(out); buf += out },
        err => System.err.println(err),
      )
      val status =
        try Process(Seq(Paths.get(exe).posx), None, "OPENBLAS_NUM_THREADS" -> "1").!(logger)
        catch case e: Exception => { println(s"  (failed to run: ${e.getMessage})"); -1 }
      if status != 0 then None
      else
        val lines  = buf.toList
        val cfgRe  = """\s*config:\s+blas=(\S+)\s+threads=(\S+).*""".r
        val config = lines.collectFirst { case cfgRe(b, t) =>
          s"${if b == "on" then "OpenBLAS" else "pure-Rust"}, threads=$t"
        }.getOrElse("unreported")
        Some(RustRes(config, stale, parseRust(lines)))

  /** Parses the `[Rust] estimate3prf <scenario>  N.NNN ms/call` lines. Same
   *  shape as parsePython — the two benches print the same section headers. */
  private def parseRust(lines: List[String]): Map[(String, String), Double] =
    val labelRe = """──\s+(\S+)\s+\(T=.*""".r
    val rowRe   = """.*\[Rust\]\s+estimate3prf\s+(IS Full|OOS Rec|OOS CV)\s+([0-9.]+)\s+ms/call.*""".r
    lines.foldLeft((Option.empty[String], Map.empty[(String, String), Double])) {
      case ((_, acc), labelRe(lbl))     => (Some(lbl), acc)
      case ((cur, acc), rowRe(scn, ms)) => (cur, acc + ((cur.getOrElse("?"), scn) -> ms.toDouble))
      case (state, _)                   => state
    }._2

  /** Runs the python bench, echoing each stdout line live (preserving the
   *  original streamed output) while collecting the lines for later parsing. */
  private def runBenchCapture(header: String, exe: String, script: String): List[String] =
    println(s"\n$header")
    val buf = scala.collection.mutable.ListBuffer.empty[String]   // local accumulator only
    val logger = ProcessLogger(
      out => { println(out); buf += out },
      err => System.err.println(err),
    )
    Seq(exe, "-u", script).!(logger)
    buf.toList

  /** Parses the `[Python Fast] estimate3prf <scenario>  N.NN ms/call` lines,
   *  keyed by (size-label, scenario). A pure fold: section headers set the
   *  current label, row lines add a timing. */
  private def parsePython(lines: List[String]): Map[(String, String), Double] =
    val labelRe = """──\s+(\S+)\s+\(T=.*""".r
    val rowRe   = """.*\[Python Fast\]\s+estimate3prf\s+(IS Full|OOS Rec|OOS CV)\s+([0-9.]+)\s+ms/call.*""".r
    lines.foldLeft((Option.empty[String], Map.empty[(String, String), Double])) {
      case ((_, acc), labelRe(lbl))     => (Some(lbl), acc)
      case ((cur, acc), rowRe(scn, ms)) => (cur, acc + ((cur.getOrElse("?"), scn) -> ms.toDouble))
      case (state, _)                   => state
    }._2

  /** Renders `slower / faster` as the doc-style ratio cell. Differences inside
   *  `tiedBand` are reported as a tie rather than a direction: run-to-run
   *  variance here is several percent, so rendering a 2% gap as "1.0× slower"
   *  would publish noise as a finding. */
  private val tiedBand = 1.05
  private def ratioCell(slowMs: Double, fastMs: Double): String =
    val r = slowMs / fastMs
    if r < tiedBand && r > 1.0 / tiedBand then "≈ tied"
    else if r >= 1.0 then f"**$r%.1f× faster**"
    else f"**${1.0 / r}%.1f× slower**"

  /** Prints the side-by-side markdown table, in the same style as
   *  docs/MatDCheatSheet.md. Columns appear only for the languages that
   *  actually produced timings, so the shape follows what was run. */
  private def printComparison(scala: List[ScalaRes], py: Map[(String, String), Double],
                              rust: Option[RustRes]): Unit =
    val hasPy   = py.nonEmpty
    val hasRust = rust.exists(_.ms.nonEmpty)
    val langs   = (if hasPy then List("Python") else Nil) ::: List("Scala") :::
                  (if hasRust then List("Rust") else Nil)
    println(s"\n## 3PRF ${langs.mkString(" vs ")} — ms/call")
    println()
    if !hasPy && !hasRust then println("(no python or rust results — Scala timings only)\n")

    val cols = List("Operation") ::: (if hasPy then List("Python") else Nil) :::
      List("Scala") ::: (if hasRust then List("Rust") else Nil) :::
      (if hasPy then List("Py/Scala") else Nil) :::
      (if hasRust then List("Scala/Rust") else Nil)
    println(cols.mkString("| ", " | ", " |"))
    println(cols.map(c => if c == "Operation" || c.contains("/") then "---" else "---:")
      .mkString("|", "|", "|"))

    def cell(o: Option[Double]): String = o.map(v => f"$v%.3f ms").getOrElse("—")

    for sr <- scala do
      val scalaMs = Map("IS Full" -> sr.isFull, "OOS Rec" -> sr.oosRec, "OOS CV" -> sr.oosCv)
      for scn <- scenarios do
        val op   = s"3PRF $scn (${sr.label}: T=${sr.T}, N=${sr.N}, L=${sr.L})"
        val sMs  = scalaMs(scn)
        val pOpt = py.get((sr.label, scn))
        val rOpt = rust.flatMap(_.ms.get((sr.label, scn)))
        val cells = List(op) :::
          (if hasPy   then List(cell(pOpt)) else Nil) :::
          List(f"$sMs%.3f ms") :::
          (if hasRust then List(cell(rOpt)) else Nil) :::
          (if hasPy   then List(pOpt.map(p => ratioCell(p, sMs)).getOrElse("—")) else Nil) :::
          (if hasRust then List(rOpt.map(r => ratioCell(sMs, r)).getOrElse("—")) else Nil)
        println(cells.mkString("| ", " | ", " |"))

    // provenance: the two Rust builds land at the same path and differ by ~3x
    // on the OOS rows, so the table states which one produced these numbers
    for r <- rust do
      println()
      println(s"Rust build: ${r.config}${if r.stale then "  — STALE (older than rust/src)" else ""}")

}
//#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation -q
package uni.apps

//> using jvm 22
//> using scala 3.8.4
//> using dep org.vastblue:uni_3:0.14.0

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
 * Run:  scala-cli jsrc/tprf3Bench.sc
 */
object Tprf3Bench {
  def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "[-nopython]     ; only show scala benchmark results",
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
  def main(args: Array[String]): Unit = {
    eachArg(args.toSeq, usage) {
    case "-nopython" =>
      runPython = false
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

    printComparison(scalaResults, parsePython(pyLines))
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
    printf("done (%.1f s)%n", warmSecs)

    // ── IS Full ───────────────────────────────────────────────────────────
    val msFast   = bench(loops) { Tprf3.t3prf(y, X, Z) }
    val ms3prf   = bench(loops) { Tprf3.estimate3prf(y, X, Right(Z), procedure = "IS Full") }

    printf("  [Scala]  %-26s  %8.2f ms/call%n", "Tprf3.t3prf", msFast)
    printf("  [Scala]  %-26s  %8.2f ms/call%n", "Tprf3.estimate3prf IS Full", ms3prf)

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
    printf("  [Scala]  %-26s  %8.2f ms/call  (loops=%d)%n",
      "Tprf3.estimate3prf OOS Rec", msOosRec, oosLoops)

    warm(oosWarm) {
      Tprf3.estimate3prf(y, X, Right(Z), procedure = "OOS Cross Val")
    }
    val msCv = bench(oosLoops) {
      Tprf3.estimate3prf(y, X, Right(Z), procedure = "OOS Cross Val")
    }
    printf("  [Scala]  %-26s  %8.2f ms/call  (loops=%d)%n",
      "Tprf3.estimate3prf OOS CV", msCv, oosLoops)

    ScalaRes(label, T, N, L, msFast, ms3prf, msOosRec, msCv)

  /** Known native-Windows Python installations to try, in preference order. */
  private val winPythonCandidates: List[String] = List(
    "F:/WPy64-3.14.3.0/python/python.exe",
  )

  private def findPython(): Option[String] =
    // MSYS2 paths resolved to Windows equivalents via Paths.get().posx
    // for MacOs, this must list homebrew ahead of /usr/bin/python3 (otherwise, no numpy)
    // (or, may need to brew install numpy)
    val msys2Paths = List("/ucrt64/bin/python3.exe", "/opt/homebrew/bin/python3", "/usr/bin/python3")
    val candidates = msys2Paths.map(Paths.get(_).posx) ++ List("python3", "python")
    candidates.find { p =>
      try Seq(p, "--version").!(ProcessLogger(_ => ())) == 0
      catch case _: Exception => false
    }

  private def findWinPython(): Option[String] =
    winPythonCandidates.find { p =>
      try Seq(p, "--version").!(ProcessLogger(_ => ())) == 0
      catch case _: Exception => false
    }

  /** Resolves the python interpreter to run and the section header to print,
   *  or None (with a diagnostic) when none is available. */
  private def selectPython(script: String): Option[(String, String)] =
    if !java.io.File(script).exists() then
      println(s"\n(bench script not found: $script)")
      None
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
    val blas = try
      var lines = List.empty[String]
      Seq(exe, "-c",
        "import numpy as np, warnings; warnings.filterwarnings('ignore'); np.show_config()"
      ).!(ProcessLogger(l => lines ::= l, _ => ()))
      val nameLine = lines.reverse.find(_.trim.startsWith("name:"))
      nameLine.map(_.trim.stripPrefix("name:").trim).getOrElse("?")
    catch case _: Exception => "?"
    s"Python $ver  ($blas)"

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

  /** Renders `python / scala` as the doc-style ratio cell. */
  private def ratioCell(pyMs: Double, scalaMs: Double): String =
    val r = pyMs / scalaMs
    if r >= 1.0 then f"**$r%.1f× faster**" else f"**${1.0 / r}%.1f× slower**"

  /** Prints the side-by-side markdown table (Python vs Scala, with ratio),
   *  in the same style as docs/MatDCheatSheet.md. */
  private def printComparison(scala: List[ScalaRes], py: Map[(String, String), Double]): Unit =
    val hasPy = py.nonEmpty
    println("\n## 3PRF Scala vs Python — ms/call")
    println()
    if hasPy then
      println("| Operation | Python | Scala | Ratio |")
      println("|---|---:|---:|---|")
    else
      println("(no python results — Scala timings only)\n")
      println("| Operation | Scala |")
      println("|---|---:|")

    for sr <- scala do
      val scalaMs = Map("IS Full" -> sr.isFull, "OOS Rec" -> sr.oosRec, "OOS CV" -> sr.oosCv)
      for scn <- scenarios do
        val op = s"3PRF $scn (${sr.label}: T=${sr.T}, N=${sr.N}, L=${sr.L})"
        val sMs = scalaMs(scn)
        if hasPy then
          py.get((sr.label, scn)) match
            case Some(pMs) => println(f"| $op | $pMs%.2f ms | $sMs%.2f ms | ${ratioCell(pMs, sMs)} |")
            case None      => println(f"| $op | — | $sMs%.2f ms | — |")
        else
          println(f"| $op | $sMs%.2f ms |")

}

#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation -q
//package uni.apps

//> using dep org.vastblue:uni_3:0.14.0

import uni.*
import uni.data.*
import uni.stats.Tprf3
import scala.sys.process.*

/**
 * Benchmarks Tprf3.t3prf and Tprf3.estimate3prf across two data sizes,
 * then invokes py/bench_tprf3.py so Scala and Python results appear side-by-side.
 *
 * Run:  sbt "runMain apps.Tprf3Bench"
 */
object Tprf3Bench {
  def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "[-nopython]     ; only show scala benchmark results",
    )
  }

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
    run("Small", T = 200, N = 30, L = 2, warmupMs = 2000, loops = 50)
    run("Large", T = 650, N = 40, L = 2, warmupMs = 500,  loops = 20)
    if !runPython then
      sys.exit(0)

    if !java.io.File(script).exists() then
      println(s"\n(bench script not found: $script)")
    else
      findPython() match
        case None      => println("\n(MSYS2 python3 not found; skipping)")
        case Some(exe) => runBench(s"── Python benchmarks  [${pythonLabel(exe)}] ──────────────────────────────", exe, script)

      findWinPython() match
        case None      => println("\n(WinPython not found; skipping)")
        case Some(exe) => runBench(s"── WinPython benchmarks  [${pythonLabel(exe)}] ───────────────────────────", exe, script)
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

  def run(label: String, T: Int, N: Int, L: Int, warmupMs: Int, loops: Int): Unit =
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

    // ── OOS Recursive (fewer loops — it iterates T times internally) ──────
    // floor of 5: the median of 5 tolerates up to two outlier samples; at
    // loops/10 = 2 a single background blip dominated the reading
    // (observed OOS Rec readings of 24–112 ms for identical code)
    val oosLoops = math.max(5, loops / 10)
    val msOosRec = bench(oosLoops) {
      Tprf3.estimate3prf(y, X, Right(Z), procedure = "OOS Recursive",
        mintrain = (T / 2, 0))
    }
    printf("  [Scala]  %-26s  %8.2f ms/call  (loops=%d)%n",
      "Tprf3.estimate3prf OOS Rec", msOosRec, oosLoops)

    // ── OOS Cross Val ─────────────────────────────────────────────────────
    val msCv = bench(oosLoops) {
      Tprf3.estimate3prf(y, X, Right(Z), procedure = "OOS Cross Val")
    }
    printf("  [Scala]  %-26s  %8.2f ms/call  (loops=%d)%n",
      "Tprf3.estimate3prf OOS CV", msCv, oosLoops)

  /** Known native-Windows Python installations to try, in preference order. */
  private val winPythonCandidates: List[String] = List(
    "F:/WPy64-3.14.3.0/python/python.exe",
  )

  private def findPython(): Option[String] =
    // MSYS2 paths resolved to Windows equivalents via Paths.get().posx
    val msys2Paths = List("/ucrt64/bin/python3.exe", "/usr/bin/python3")
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

  private def runBench(header: String, exe: String, script: String): Unit =
    println(s"\n$header")
    Seq(exe, "-u", script).!

}

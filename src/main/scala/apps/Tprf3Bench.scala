package uni.apps

import uni.*
import uni.data.*
import uni.stats.Tprf3
import scala.sys.process.*

/**
 * Benchmarks Tprf3.tprfFast and Tprf3.estimate3prf across two data sizes,
 * then invokes py/bench_tprf3.py so Scala and Python results appear side-by-side.
 *
 * Run:  sbt "runMain apps.Tprf3Bench"
 */
object Tprf3Bench {

  /** Returns ms-per-call for the given block run `loops` times. */
  def bench(loops: Int)(block: => Unit): Double =
    val t0 = System.currentTimeMillis()
    var i  = 0
    while i < loops do { block; i += 1 }
    (System.currentTimeMillis() - t0).toDouble / loops

  def run(label: String, T: Int, N: Int, L: Int, warmup: Int, loops: Int): Unit =
    println(s"\n── $label  (T=$T  N=$N  L=$L  warmup=$warmup  loops=$loops) ──")
    Mat.setSeed(0)
    val X: MatD = MatD.randn(T, N)
    val y: MatD = MatD.randn(T, 1)
    val Z: MatD = MatD.randn(T, L)

    // ── warm-up (let JIT settle) ──────────────────────────────────────────
    print("  warming up ... ")
    Console.flush()
    for _ <- 0 until warmup do
      Tprf3.tprfFast(X, y, Z)
      Tprf3.estimate3prf(y, X, Right(Z), procedure = "IS Full")
    println("done")

    // ── IS Full ───────────────────────────────────────────────────────────
    val msFast   = bench(loops) { Tprf3.tprfFast(X, y, Z) }
    val ms3prf   = bench(loops) { Tprf3.estimate3prf(y, X, Right(Z), procedure = "IS Full") }

    printf("  [Scala]  %-26s  %8.2f ms/call%n", "Tprf3.tprfFast", msFast)
    printf("  [Scala]  %-26s  %8.2f ms/call%n", "Tprf3.estimate3prf IS Full", ms3prf)

    // ── OOS Recursive (fewer loops — it iterates T times internally) ──────
    val oosLoops = math.max(1, loops / 10)
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

  def main(args: Array[String]): Unit =
    val rootDir = sys.props.getOrElse("user.dir", ".")
    val script  = Paths.get(s"$rootDir/py/bench_tprf3.py").posx

    println("── Scala benchmarks ─────────────────────────────────────────────────────────")
    run("Small", T = 200, N = 30, L = 2, warmup = 5,  loops = 50)
    run("Large", T = 650, N = 40, L = 2, warmup = 3,  loops = 20)

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

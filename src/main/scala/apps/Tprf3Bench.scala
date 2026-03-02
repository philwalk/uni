package apps

import uni.*
import uni.data.*
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

  private def findPython(): Option[String] =
    // MSYS2 paths resolved to Windows equivalents via Paths.get().posx
    val msys2Paths = List("/ucrt64/bin/python3.exe", "/usr/bin/python3")
    val candidates = msys2Paths.map(Paths.get(_).posx) ++ List("python3", "python")
    candidates.find { p =>
      try Seq(p, "--version").!(ProcessLogger(_ => ())) == 0
      catch case _: Exception => false
    }

  private def runPythonBench(rootDir: String): Unit =
    val script = Paths.get(s"$rootDir/py/bench_tprf3.py").posx
    if !java.io.File(script).exists() then
      println(s"  (script not found: $script)")
      return
    findPython() match
      case None      => println("  (python3 not found; skipping Python benchmark)")
      case Some(exe) => Seq(exe, "-u", script).!

  def main(args: Array[String]): Unit =
    println("── Scala benchmarks ─────────────────────────────────────────────────────────")
    run("Small", T = 200, N = 30, L = 2, warmup = 5,  loops = 50)
    run("Large", T = 650, N = 40, L = 2, warmup = 3,  loops = 20)
    println()
    println("── Python benchmarks ────────────────────────────────────────────────────────")
    runPythonBench(sys.props.getOrElse("user.dir", "."))
}

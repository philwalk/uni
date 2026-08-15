package uni.apps

import uni.*
import scala.sys.process.*

/**
 * The machinery every cross-language benchmark needs: find a usable interpreter, find
 * and sanity-check the Rust binary, run them, parse their timings, and print a markdown
 * table.
 *
 * Extracted so `MatBench` did not become a second copy of the discovery logic in
 * [[Tprf3Bench]], which is where all of it was learned. `Tprf3Bench` predates this and
 * still carries its own copy plus a WinPython enumeration; migrating it here is a
 * worthwhile follow-up, and the reason this is a separate object rather than a private
 * helper inside `MatBench`.
 *
 * # The output contract
 *
 * Each language's benchmark prints one line per row:
 *
 * {{{
 *   [Python] sum@transposed                 0.1292 ms/call
 *   [Rust]   sum@transposed                 0.4156 ms/call
 * }}}
 *
 * and one `config: ...` line describing the build it measured. Labels are the join key,
 * so they must match across all three halves exactly — that is the whole coupling, and
 * it is deliberately a plain string so a half can be run alone and eyeballed.
 */
object BenchRunner:
  def println(s: String = ""): Unit = print(s"$s\n")
  def eprintln(s: String = ""): Unit = System.err.print(s"$s\n")

  /** `[Lang] label   1.234 ms/call` — the one line format all three halves emit. */
  private val Timing = """^\s*\[\w+\]\s+(\S+)\s+([0-9.eE+-]+)\s+ms/call\s*$""".r
  private val Config = """^\s*config:\s*(.*)$""".r

  /** Timings by label, plus whatever the process said about its own configuration. */
  case class Result(ms: Map[String, Double], config: String)

  def parse(lines: Seq[String]): Result =
    val ms = lines.collect { case Timing(label, v) => label -> v.toDouble }.toMap
    val cfg = lines.collectFirst { case Config(c) => c }.getOrElse("?")
    Result(ms, cfg)

  /** Runs a command, echoing its output, and returns the lines it printed. */
  def capture(cmd: Seq[String], env: (String, String)*): Seq[String] =
    val buf = scala.collection.mutable.ListBuffer.empty[String]
    val logger = ProcessLogger(
      line => { buf += line; println(s"  $line") },
      line => eprintln(s"  $line"),
    )
    try
      Process(cmd, None, env*).!(logger)
      buf.toList
    catch
      case e: Exception =>
        eprintln(s"  (failed: ${e.getMessage})")
        Nil

  // ── Python ──────────────────────────────────────────────────────────────────

  /** An interpreter is usable only if it can actually import numpy: probing with
   *  `--version` accepts installs whose numpy is broken, which then fails deep inside
   *  the bench script. */
  private def usablePython(p: String): Boolean =
    try Seq(p, "-c", "import numpy; numpy.nan").!(ProcessLogger(_ => (), _ => ())) == 0
    catch case _: Exception => false

  /** First interpreter on the list that has a working numpy, or the override.
   *
   *  MSYS2/homebrew paths are resolved to their Windows equivalents via `posx`. On
   *  macOS homebrew must precede `/usr/bin/python3`, which has no numpy. */
  def findPython(override_ : Option[String]): Option[String] =
    override_ match
      case Some(raw) =>
        val exe = if raw.startsWith("/") then Paths.get(raw).posx else raw
        if usablePython(exe) then Some(exe)
        else { eprintln(s"(-python $raw cannot import numpy)"); None }
      case None =>
        val msys2 = List("/ucrt64/bin/python3.exe", "/opt/homebrew/bin/python3", "/usr/bin/python3")
        (msys2.map(Paths.get(_).posx) ++ List("python3", "python")).find(usablePython)

  /** "Python X.Y.Z (blas)" for the provenance line under the table. */
  def pythonLabel(exe: String): String =
    def ask(code: String): String =
      try
        var out = ""
        Seq(exe, "-c", code).!(ProcessLogger(out = _, _ => ()))
        val t = out.trim
        if t.isEmpty then "?" else t
      catch case _: Exception => "?"
    val ver = ask("import sys; print(sys.version.split()[0])")
    // numpy >= 2 exposes the config as a dict; the older text dump is JSON-ish on some
    // builds, where scanning for a "name:" prefix finds the compiler first.
    val blas = ask(
      "import numpy as np,warnings;warnings.filterwarnings('ignore');" +
      "d=np.show_config('dicts');" +
      "print(((d.get('Build Dependencies') or {}).get('blas') or {}).get('name','?'))"
    )
    s"Python $ver ($blas)"

  // ── Rust ────────────────────────────────────────────────────────────────────

  /** Path to a release binary, `.exe` included where the platform wants it. */
  def rustExe(rootDir: String, name: String): String =
    val base = Paths.get(s"$rootDir/rust/target/release/$name").posx
    if java.io.File(s"$base.exe").isFile then s"$base.exe" else base

  /** True when the binary predates its own sources, i.e. the numbers would be stale.
   *
   *  Deliberately a warning rather than a rebuild: keeping this harness out of the
   *  cargo/toolchain business means a caller who wants `--features blas` gets exactly
   *  the build they made, and the binary reports which one it is. */
  def staleAgainstSources(rootDir: String, exe: String): Boolean =
    val bin = java.io.File(exe)
    if !bin.isFile then false
    else
      def newest(dir: java.io.File): Long =
        if !dir.isDirectory then 0L
        else Option(dir.listFiles).getOrElse(Array.empty[java.io.File]).foldLeft(0L) { (acc, f) =>
          math.max(acc, if f.isDirectory then newest(f) else f.lastModified)
        }
      List("src", "Cargo.toml").map(d => newest(java.io.File(s"$rootDir/rust/$d"))).max > bin.lastModified

  // ── Markdown ────────────────────────────────────────────────────────────────

  /** `**2.3× faster**` / `**2.3× slower**`, from the doc's own convention: the ratio is
   *  written from the first column's point of view. */
  def ratioCell(a: Double, b: Double): String =
    if a <= 0.0 || b <= 0.0 then "—"
    else
      val r = a / b
      if r >= 1.0 then f"**$r%.1f× faster**" else f"**${1.0 / r}%.1f× slower**"

  def cell(o: Option[Double]): String = o.map(v => f"$v%.4f ms").getOrElse("—")

  /** Emits a markdown table. `rows` supplies already-rendered cells per row. */
  def table(headers: Seq[String], rows: Seq[Seq[String]]): Unit =
    println(headers.mkString("| ", " | ", " |"))
    // Left-align the label and ratio columns, right-align the numeric ones.
    println(headers.map(h => if h == headers.head || h.contains("/") then "---" else "---:")
      .mkString("|", "|", "|"))
    for r <- rows do println(r.mkString("| ", " | ", " |"))

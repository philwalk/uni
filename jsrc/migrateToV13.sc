#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.12.3  // pinned: script runs against last pre-0.13 release

import uni.*
import java.nio.file.{Files, FileSystems}
import scala.jdk.CollectionConverters.*

object MigrateToV13 {
  def println(s: String = ""): Unit = print(s"$s\n")
  def eprintln(s: String = ""): Unit = System.err.print(s"$s\n")

  def usage(m: String = ""): Nothing =
    showUsage(m, "",
      "<file|dir> ...  ; scala sources to migrate (directories searched recursively)",
      "[-f]            ; apply changes (default: dry run, shows diffs)",
      "[-v]            ; verbose: list every file examined",
    )

  // Applied to each line in order.
  // Patterns are Java regexes; replacements are literal strings.
  val rewrites: Seq[(String, String)] = Seq(
    // 1. Dependency version bump
    ("""//> using dep org\.vastblue:uni_3:[0-9]+\.[0-9]+\.[0-9]+""",
     "//> using dep org.vastblue:uni_3:0.13.3"),
    (""""org\.vastblue" %% "uni" % "[0-9]+\.[0-9]+\.[0-9]+"""",
     """"org.vastblue" %% "uni" +%+ +"0.13.1""""),
    // 2. ProcStatus → ProcResult (field names unchanged, cmd field added)
    ("""\bProcStatus\b""", "ProcResult"),
    // 3. exec(...) → run(...), skipping execLines which is private[uni]
    //    Note: old exec(String*) returned String; new run(String*) returns ProcResult.
    //    Callers that used the String result will get a compile error guiding them
    //    to add .text, .lines, or .toOption as appropriate.
    ("""\bexec(?!Lines)\(""", "run("),
    // 4. Proc.call(...) → run(...)
    //    Old call returned Option[String]; new run returns ProcResult.
    //    Add .toOption at call sites where Option[String] is needed.
    ("""Proc\.call\(""", "run("),
    // 5. Proc.call(...) → run(...)  [qualified form only — bare call() is too common]
    //    spawn(...) and ProcStatus.stdout are intentionally omitted: the resulting
    //    compile errors guide the user to the exact sites needing manual attention.

    // ── Deprecated extension methods ─────────────────────────────────────────
    // 7. .suffix → .ext  (Path / JFile extension)
    ("""\.suffix\b""", ".ext"),
    // 8. .lcsuffix → .ext.lc
    ("""\.lcsuffix\b""", ".ext.lc"),
    // 9. .basename → .baseName  (capitalisation change is the distinguishing signal)
    ("""\.basename\b""", ".baseName"),
    // 10. .lcbasename → .baseName.lc
    ("""\.lcbasename\b""", ".baseName.lc"),
    // 11. .lcname → .last.lc
    ("""\.lcname\b""", ".last.lc"),
    // 12. .trimmedLines → .lines
    ("""\.trimmedLines\b""", ".lines"),
    // 13. .path → .asPath  (String / JFile extension — most common deprecation)
    //     \b excludes compound names (.paths, .pathStr, ...).
    //     (?!\s*[\.("])  excludes: package segments (.path.X), method calls (.path(...)),
    //                    and string literal tails ("...java.library.path").
    //     Residual false positives: case-class .path fields and compiler API — review diff.
    ("""\.path\b(?!\s*[\.("])""", ".asPath"),
    // 14. .file → .asFile  (Path extension)
    //     \b excludes .files, .filesIter, etc.
    //     (?!\s*\.) excludes java.nio.file.Xxx package segments.
    ("""\.file\b(?!\s*\.)""", ".asFile"),
    //
    // Intentionally skipped (false-positive risk outweighs benefit):
    //   .name   → .last          — extremely common field/method name
    //   .text   → .contentAsString — now used by ProcResult; would corrupt new code
    //   .toPath → .asPath        — also on java.io.File (correct Java); do not rewrite
    //   spawn() / .stdout        — compile errors after ProcStatus removal are sufficient
  )

  // These cannot be auto-migrated: they pass a pre-formed shell string to bash -c,
  // which is an injection risk.  Flag them for manual rewrite.
  val shellExecPattern = """.*\b(shellExec|shellExecProc)\b.*""".r

  val ignoredDirs: Set[String] = Set(
    ".git", ".idea", ".scala-build", "target",
    ".bloop", ".bsp", ".metals", ".vscode", ".cargo",
  )

  var (force, verbose) = (false, false)
  val inputs = collection.mutable.ListBuffer.empty[String]

  def main(args: Array[String]): Unit =
    eachArg(args.toSeq, usage) {
      case "-f"                          => force = true
      case "-v"                          => verbose = true
      case arg if arg.asPath.exists      => inputs += arg
      case arg                           => usage(s"not found: $arg")
    }
    if inputs.isEmpty then usage()

    val scalaExt = FileSystems.getDefault.getPathMatcher("glob:*.{sc,scala}")
    def isScala(p: Path): Boolean =
      scalaExt.matches(p.getFileName) || p.firstLine.contains("scala")
    def isTarget(p: Path): Boolean =
      p.isFile && isScala(p) && !ignoredDirs.exists(d => p.posx.contains(s"/$d/"))

    val files = inputs.toSeq.flatMap { arg =>
      val p = arg.asPath
      if p.isFile then Seq(p)
      else Files.walk(p).filter(isTarget).iterator().asScala.toSeq
    }

    if verbose then eprintln(s"examining ${files.size} files")

    var (changed, shellWarnings) = (0, 0)
    for p <- files do
      if verbose then eprintln(s"  ${p.relativePath.toString.replace('\\', '/')}")
      val (c, w) = processFile(p)
      changed      += c
      shellWarnings += w

    println(s"\n${changed} file(s) ${if force then "updated" else "would change"}.")
    if shellWarnings > 0 then
      println(s"$shellWarnings shellExec/shellExecProc call(s) flagged — see SubprocessAPI.md for manual rewrite.")
    if !force && changed > 0 then
      println("Re-run with -f to apply.")

  def processFile(p: Path): (Int, Int) =
    val original = p.lines.toSeq
    var warnings  = 0

    val updated = original.map { line =>
      val migrated = rewrites.foldLeft(line)((s, rw) => s.replaceAll(rw._1, rw._2))
      shellExecPattern.findFirstIn(migrated) match
        case Some(_) =>
          warnings += 1
          s"$migrated // MIGRATE: rewrite as run(bashExe, \"-c\", ...) — see SubprocessAPI.md"
        case None => migrated
    }

    if original == updated then
      (0, warnings)
    else
      val fname = p.relativePath.toString.replace('\\', '/')
      if !force then
        for (o, u) <- original.zip(updated) if o != u do
          println(s"--- $fname")
          println(s"-   $o")
          println(s"+   $u")
      else
        val mtime = Files.getLastModifiedTime(p)
        Files.write(p, updated.mkString("\n").getBytes("UTF-8"))
        Files.setLastModifiedTime(p, mtime)
        println(s"updated: $fname")
      (1, warnings)
}
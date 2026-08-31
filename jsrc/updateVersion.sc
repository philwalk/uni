#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.23.0

import uni.*
import java.nio.file.{Files, FileSystems}
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

object UpdateVersion {

  def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "[-f]          ; force update (else trial run)",
      "[-v]          ; verbose",
      "[--]          ; report version and exit",
      "<filename>    ; input",
    )
  }

  var (force, verbose, reportAndQuit) = (false, false, false)
  var inputFiles = Seq.empty[Path]

  def main(args: Array[String]): Unit = {
    eachArg(args.toSeq, usage) {
      case "-v" => verbose = true
      case "-f" => force = true
      case "--" => reportAndQuit = true
      case fname if fname.asPath.exists =>
        inputFiles :+= fname.asPath // files and/or directories
      case arg  =>
        usage(s"unrecognized arg [$arg]")
    }
    val version = readVersion()
    println(s"Detected project version: $version")

    if reportAndQuit then
      printf("no update\n")
      sys.exit(0)

    val sweepingAll = inputFiles.isEmpty
    val files = if !sweepingAll then
      val (dirs, files) = inputFiles.partition(_.isDirectory)
      walkDirs(dirs) ++ files

    else
      // by default, collect files below current dir -- and the files IN it.
      // `walkDirs` only ever sees directories, so top-level files (README.md
      // above all) were silently skipped: the 0.16.0 sweep left twelve stale dep
      // lines in the README because nothing here ever looked at it.
      val entries: Seq[Path] = Paths.get(".").paths
      val roots: Seq[Path] = entries.filter { dir =>
        dir.isDirectory && ! ignoredDirs.contains(dir.last)
      }
      entries.filter(fileFilter) ++ walkDirs(roots)

    val (textFiles, cargos) = files.distinct.partition(p => !isCargoFile(p))
    textFiles.foreach(updateFile(_, version))
    // The Rust manifests are swept whether or not they were named: they are matched by NAME
    // rather than extension (see `cargoFiles`), so the directory walk cannot reach them.
    val cargoTargets = if sweepingAll then cargoFiles.map(_.asPath).filter(_.exists) else cargos
    cargoTargets.foreach(updateCargoFile(_, version))

    if force && sweepingAll then
      val stale = verify(textFiles ++ cargoTargets, version)
      if stale.nonEmpty then
        stale.foreach(s => eprintf("STALE %s\n", s))
        eprintf("%d version string(s) did not move to %s -- a rewrite rule matched nothing.\n",
                stale.size, version)
        sys.exit(1)
      println(s"verified: every known version site reads $version")
    println("Done.")
  }

  def walkDirs(roots: Seq[Path]): Seq[Path] = {
    roots.flatMap { dir =>
      eprintf("# %s\n", dir)
      Files.walk(dir)
        .filter(p => fileFilter(p))
        .iterator()
        .asScala
    }.toList
  }

  lazy val matcher = FileSystems.getDefault.getPathMatcher("glob:**/*.{sc,scala,md}")
  def isScala(p: Path): Boolean =
    matcher.matches(p) || p.firstLine.contains("scala")

  /** Directory names never descended into. Grouped and deduplicated -- the flat
   *  list had accumulated `.git` and `.idea` twice, which a membership test
   *  tolerates but a reader should not have to. Names, not paths: `ignored`
   *  matches `/<name>/` anywhere in the path, so one entry covers every depth.
   *  Spans this repo and the working script corpus, since the tool runs in both. */
  lazy val ignoredDirs = Seq(
    // tooling and build output
    ".git", ".idea", ".vscode", ".metals", ".bloop", ".bsp", ".cargo", ".claude", ".sqlx",
    ".scala-build", "target", "idea-2024.3.1.lib",
    // committed test fixtures: their bytes ARE the test, so a version-like string
    // inside one must never be rewritten by a doc sweep
    "test-data", "t3prf-validation",
    // archived and superseded sources
    "jsrcArchive", "scalaArchive", "clisrcArchive", "archive", "archive-sv", "obsolete-staging",
    "qdsaved", "saved-assessor", "debris-files-to-be-reviewed",
    // other languages, binaries, jars
    "rs", "ruby", "py", "js", "ksrc", "rbin", "osxbin", "luxbin", "cygbin", "exes", "jar", "lib",
    "CobraWinLDTP",
    // data and generated reports
    "data", "data_200_scala_01", "data_200_scala_12", "data_200_scala_15", "march2quadreports",
    "quad20240228", "artifacts", "tmp", "deduplication",
    // corpus project directories
    "mortgage", "assessor", "roadtrip", "biz", "some", "drop-finstr", "drop-qual", "drop-mom",
    "drop-earnest", "drop-perf", "drop-bsdrank", "drop-rev", "drop-overall", "drop-value",
  )
  def ignored(fname: String): Boolean = {
    ignoredDirs.find(dir => fname.contains(s"/$dir/")).nonEmpty
  }
  lazy val targetedExtensions = Set(
    "sc", "scala", "sbt", "md", ""
  )

  def fileFilter(p: Path): Boolean =
    p.isFile && isScala(p) && targetedExtensions.contains(p.ext) && {
      val str = p.posx
      !ignored(str)
    }

  /** The Rust manifests, matched by NAME rather than extension. Adding `toml` and `lock` to
   *  `targetedExtensions` would sweep every manifest in the working script corpus this tool also
   *  runs over; the two that carry this project's version are these two. */
  lazy val cargoFiles = Seq("rust/Cargo.toml", "rust/Cargo.lock")
  def isCargoFile(p: Path): Boolean = Set("Cargo.toml", "Cargo.lock").contains(p.last)

  def readVersion(): String =
    // path to build.sbt
    val buildSbt = Paths.get("build.sbt")
    val versionLine = Files.readAllLines(buildSbt)
      .asScala
      .find(_.matches(""".*\bversion\s*:*=.*"""))
      .getOrElse(sys.error(s"Could not find version := in $buildSbt"))

    val VersionRegex = """.*version\s*:*=.*"([^"]+)".*""".r
    versionLine match
      case VersionRegex(v) => v
      case _ => sys.error(s"Could not parse version from: $versionLine")

  /** A release note states what THAT release shipped, so a version string inside one is a fact
   *  about the past, not a stale copy of the current version: the jar named in an old entry's
   *  run command is correct as written, and a sweep that "fixed" it would falsify the record --
   *  the same reason `ignoredDirs` excludes the committed fixtures.  (Written without an example,
   *  deliberately: a version-shaped string in this comment is one this tool would rewrite.) */
  def isHistory(p: Path): Boolean = p.last == "CHANGELOG.md"

  def updateFile(p: Path, newVersion: String): Unit = {
    // val lines = Files.readAllLines(p).asScala // vulerable to malformed input exception
    val lines = p.lines.toSeq // not vulnerable
    // regex patterns to target "uni"
    val regex1 = """//> using dep org\.vastblue(:uni_3:|::uni:)[0-9]+\.[0-9]+\.[0-9]+"""
    val target1 = s"//> using dep org.vastblue:uni_3:$newVersion"

    val regex2 = """"org.vastblue" %% "uni" +%+ +"[0-9]+[.][0-9]+[.][0-9]+""""
    // The PATTERN's " +%+ +" is quantifiers (spaces, one-or-more %, spaces); in a replacement
    // string those same characters are literal.  Copying the one into the other rewrote the
    // README's sbt line to `%% "uni" +%+ +"x.y.z"` -- invalid sbt, and thereafter unmatchable
    // by regex2, so it corrupted once and then went silent at a stale version.
    val target2 = s""""org.vastblue" %% "uni" % "$newVersion""""

    // The jar a reader is told to run, and the heading that names the release it describes.
    // Both went stale in 0.19.1 and were caught by eye rather than by this tool.
    val regex3 = """uni_3-[0-9]+\.[0-9]+\.[0-9]+\.jar"""
    val target3 = s"uni_3-$newVersion.jar"
    val regex4 = """How the simulator ships \([0-9]+\.[0-9]+\.[0-9]+\)"""
    val target4 = s"How the simulator ships ($newVersion)"

    val updated = if isHistory(p) then lines else lines.map( s =>
      if s.contains("// pinned") then s
      else s.replaceAll(regex1, target1)
            .replaceAll(regex2, target2)
            .replaceAll(regex3, target3)
            .replaceAll(regex4, target4)
    )
    writeIfChanged(p, lines, updated)
  }

  /** The Rust crate version and the Scala library version are ONE number -- the crate says which
   *  uni release it mirrors -- and `release-and-publish.sh` asserts the two agree. A sweep that
   *  moved one and not the other therefore leaves a release to fail at its own gate: 0.19.1
   *  shipped that way and needed a follow-up commit to catch the manifests up.
   *
   *  Scoped to the section, because `version = "..."` appears once per dependency in both files
   *  and only the `vastblue-uni` package's own is ours. A `[` in column zero opens a new section
   *  and clears the flag; `name = "vastblue-uni"` sets it. Cargo writes `name` before `version`
   *  in both `[package]` and every `[[package]]` stanza, which is what makes one pass enough.
   *
   *  Returns the rewritten lines AND the count, because a rule that matches nothing is
   *  indistinguishable from a file already correct unless the count is checked. */
  def retagCargo(lines: Seq[String], newVersion: String): (Seq[String], Int) =
    val VersionLine = """(\s*version\s*=\s*)"[0-9]+\.[0-9]+\.[0-9]+"(.*)""".r
    val (_, out, hits) = lines.foldLeft((false, Vector.empty[String], 0)) {
      case ((ours, acc, n), line) =>
        val nowOurs =
          if line.startsWith("[") then false
          else if line.trim == """name = "vastblue-uni"""" then true
          else ours
        line match
          case VersionLine(lhs, rest) if nowOurs => (nowOurs, acc :+ s"""$lhs"$newVersion"$rest""", n + 1)
          case _                                 => (nowOurs, acc :+ line, n)
    }
    (out, hits)

  def updateCargoFile(p: Path, newVersion: String): Unit = {
    val lines = p.lines.toSeq
    val (updated, hits) = retagCargo(lines, newVersion)
    // Exits rather than throwing: this runs from the release script, where a stack trace buries
    // the one line that says what to do.  A rule that reaches nothing is the failure mode the
    // whole verify pass exists for, so it is loud here too and not left for the pass to notice.
    if hits == 0 then
      eprintf("%s: no `version =` in the vastblue-uni section -- the manifest layout changed\n",
              p.posx)
      eprintf("  and this rule no longer reaches the crate version.  Fix `retagCargo`.\n")
      sys.exit(1)
    writeIfChanged(p, lines, updated)
  }

  /** Preserves the file's terminator. Joining with "\n" alone dropped the trailing newline from
   *  every file the sweep touched -- README.md has been missing one since some earlier release --
   *  which is a gratuitous diff on top of the intended one. */
  def writeIfChanged(p: Path, lines: Seq[String], updated: Seq[String]): Unit = {
    val fname = p.relativePath.toString.replace('\\', '/')
    if lines != updated then
      if !force then
        // show diff
        for(((orig, upd), i) <- (lines zip updated).zipWithIndex) {
          if orig != upd then
            print(s"- $orig ($fname line ${i+1})\n")
            print(s"+ $upd\n")
        }
      else
        val endsWithNewline = p.contentAsString.endsWith("\n")
        // Preserves last modified time using stat and touch
        val ts = run("stat.exe", "-c", "%y", p.posx).orElse("")
        val lfText = updated.mkString("\n") + (if endsWithNewline then "\n" else "")
        Files.write(p, lfText.getBytes("UTF-8"))
        println(s"updated: ${p.posx}")
        if ts.nonEmpty then run("touch", "-d", ts, p.posx)
  }

  /** Every pattern that carries the project version, with the version in group 1. This is the
   *  list `verify` reads; `updateFile`'s rewrite rules are its mirror image, and a site added to
   *  one without the other is what `verify` exists to report. */
  lazy val versionSites: Seq[Regex] = Seq(
    """//> using dep org\.vastblue(?::uni_3:|::uni:)([0-9]+\.[0-9]+\.[0-9]+)""".r,
    """"org\.vastblue"\s*%%\s*"uni"\s*%\s*"([0-9]+\.[0-9]+\.[0-9]+)"""".r,
    """uni_3-([0-9]+\.[0-9]+\.[0-9]+)\.jar""".r,
    """How the simulator ships \(([0-9]+\.[0-9]+\.[0-9]+)\)""".r,
  )

  /** Re-reads what was just written and reports every version site that does not read `version`.
   *  A rewrite reports what it CHANGED, which looks identical to a file that was already correct
   *  and to a file whose pattern no longer matches anything -- the third case is how 0.19.1
   *  shipped with 0.19.0 manifests. Returned rather than printed so the caller sets the exit
   *  code: a release script that cannot fail here is back where it started. */
  def verify(files: Seq[Path], version: String): Seq[String] =
    val inText = files.filter(p => !isCargoFile(p) && !isHistory(p)).flatMap { p =>
      p.lines.zipWithIndex.flatMap { (line, i) =>
        if line.contains("// pinned") then Nil
        else versionSites.flatMap(_.findAllMatchIn(line).map(_.group(1)))
                         .filter(_ != version)
                         .map(v => s"${p.posx}:${i + 1}: reads $v, expected $version")
      }
    }
    val inCargo = files.filter(isCargoFile).flatMap { p =>
      val lines = p.lines.toSeq
      val (retagged, hits) = retagCargo(lines, version)
      if hits == 0 then Seq(s"${p.posx}: no vastblue-uni `version =` line found at all")
      else if retagged != lines then Seq(s"${p.posx}: crate version is not $version")
      else Nil
    }
    inText ++ inCargo
}

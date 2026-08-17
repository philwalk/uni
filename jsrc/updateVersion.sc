#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.17.0

import uni.*
import java.nio.file.{Files, FileSystems}
import scala.jdk.CollectionConverters.*

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

    val files = if inputFiles.nonEmpty then
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

    files.distinct.foreach(updateFile(_, version))
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

  def updateFile(p: Path, newVersion: String): Unit = {
    // val lines = Files.readAllLines(p).asScala // vulerable to malformed input exception
    val lines = p.lines.toSeq // not vulnerable
    // regex patterns to target "uni"
    val regex1 = """//> using dep org\.vastblue(:uni_3:|::uni:)[0-9]+\.[0-9]+\.[0-9]+"""
    val target1 = s"//> using dep org.vastblue:uni_3:$newVersion"

    val regex2 = """"org.vastblue" %% "uni" +%+ +"[0-9]+[.][0-9]+[.][0-9]+""""
    val target2 = s""""org.vastblue" %% "uni" +%+ +"$newVersion""""

    val updated = lines.map( s =>
      if s.contains("// pinned") then s
      else s.replaceAll(regex1, target1)
            .replaceAll(regex2, target2)
    )

    val fname = p.relativePath.toString.replace('\\', '/')
    // Only write and print if the content has actually changed
    if lines != updated then
      if !force then
        // show diff
        for(((orig, upd), i) <- (lines zip updated).zipWithIndex) {
          if orig != upd then
            print(s"- $orig ($fname line ${i+1})\n")
            print(s"+ $upd\n")
        }
      else
        // Preserves last modified time using stat and touch
        val ts = run("stat.exe", "-c", "%y", p.posx).orElse("")
        val lfText = updated.mkString("\n")
        Files.write(p, lfText.getBytes("UTF-8"))
        println(s"updated: ${p.posx}")
        if ts.nonEmpty then run("touch", "-d", ts, p.posx)
  }
}
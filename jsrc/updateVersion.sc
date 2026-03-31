#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.2

import uni.*
import java.nio.file.{Files, FileSystems}
import scala.jdk.CollectionConverters.*

object Main {

  def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "<filename>    ; input",
      "[-f]          ; force update (else trial run)",
    )
  }

  var (force, verbose) = (false, false)
  def main(args: Array[String]): Unit = {
    eachArg(args.toSeq, usage) {
    case "-v" => verbose = true
    case "-f" => force = true
    case arg =>
      usage(s"unrecognized arg [$arg]")
    }
    val version = readVersion()
    println(s"Detected project version: $version")
    if args.contains("--") then
      printf("no update\n")
      sys.exit(0)

    val root = Paths.get(".")
    val matcher = FileSystems.getDefault.getPathMatcher("glob:**/*.{sc,scala,md}")
    def isScala(p: Path): Boolean =
      matcher.matches(p) || p.firstLine.contains("scala")
    def fileFilter(p: Path): Boolean = {
      p.isFile && isScala(p) && {
        val str = p.posx
        !str.contains("/.scala-build/") && !str.contains("/target/")
      }
    }

    val files =
      Files.walk(root)
        .filter(p => fileFilter(p))
        .iterator()
        .asScala
        .toList

    files.foreach(updateFile(_, version))
    println("Done.")
  }

  def readVersion(): String =
    val versionLine = Files.readAllLines(Paths.get("build.sbt"))
      .asScala
      .find(_.matches(""".*\bversion\s*:*=.*"""))
      .getOrElse(sys.error("Could not find version := in build.sbt"))

    val VersionRegex = """.*version\s*:*=.*"([^"]+)".*""".r
    versionLine match
      case VersionRegex(v) => v
      case _ => sys.error(s"Could not parse version from: $versionLine")

  def updateFile(path: Path, newVersion: String): Unit =
    val lines = Files.readAllLines(path).asScala
    val regex1 = """//> using dep org\.vastblue(:uni_3:|::uni:)[0-9]+\.[0-9]+\.[0-9]+"""
    val target1 = s"//> using dep org.vastblue:uni_3:$newVersion"

    val regex2 = """"org.vastblue" %% "uni" % "[0-9]+[.][0-9]+[.][0-9]+""""
    val target2 = s""""org.vastblue" %% "uni" % "$newVersion""""

    val updated = lines.map( s =>
      if s.contains("// pinned") then s
      else s.replaceAll(regex1, target1).replaceAll(regex2, target2)
    )

    val fname = path.relativePath.toString.replace('\\', '/')
    // Only write and print if the content has actually changed
    if (lines != updated) then
      if !force then
        // show diff
        for(((u, l), i) <- (updated zip lines).zipWithIndex) {
          if u != l then
            print(s"- $l ($fname line ${i+1})\n")
            print(s"+ $u\n")
        }
      else
        val ts = Proc.call("stat.exe", "-c", "%y", path.posx).getOrElse("")
        val lfText = updated.mkString("\n")
        Files.write(path, lfText.getBytes("UTF-8"))
        println(s"updated: $path")
        if ts.nonEmpty then Proc.call("touch", "-d", ts, path.posx)
}
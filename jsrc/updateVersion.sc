#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.2

import java.nio.file.*
import scala.jdk.CollectionConverters.*

object Main:
  def main(args: Array[String]): Unit =
    val version = readVersion()
    println(s"Detected project version: $version")

    val root = Paths.get(".")
    val matcher = FileSystems.getDefault.getPathMatcher("glob:**/*.{sc,scala,md}")

    val files =
      Files.walk(root)
        .filter(p => matcher.matches(p))
        .iterator()
        .asScala
        .toList

    files.foreach(updateFile(_, version))

    println("Done.")

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
  val target = s"//> using dep org.vastblue:uni_3:$newVersion"
  val regex = """//> using dep org\.vastblue(:uni_3:|::uni:)[0-9]+\.[0-9]+\.[0-9]+"""

  val updated = lines.map(_.replaceAll(regex, target))

  // Only write and print if the content has actually changed
  if (lines != updated) then
    val lfText = updated.mkString("\n")
    Files.write(path, lfText.getBytes("UTF-8"))
    println(s"updated: $path")

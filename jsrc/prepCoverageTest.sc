#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.5

import uni.*
import scala.sys.process.*

object PrepCoverageTest:
  val SearchDir   = "src/main"
  val ExcludePath = "/cli/"
  val BuildDir    = ".scala-build"
  
  val InlineDefRegex = """^ *inline (def|private def)""".r.unanchored

  def usage(m: String = ""): Nothing = 
    showUsage(m, "", "[-prep | -restore] [-v]")

  var verbose = false
  var op = ""

  def main(args: Array[String]): Unit =
    eachArg(args.toSeq, usage) {
      case "-v" => verbose = true
      case "-prep" | "-restore" => op = thisArg
      case arg => usage(s"unrecognized arg [$arg]")
    }

    op match
      case "-prep"    => runPrep()
      case "-restore" => runRestore()
      case _          => usage("Please specify -prep or -restore")

  def runPrep(): Unit =
    // Check for ANY modified .scala files in the repo
    val modifiedInRepo = "git status --porcelain".!!.linesIterator
      .filter(_.endsWith(".scala")).toList

    if modifiedInRepo.nonEmpty then
      println("Aborting: Uncommitted .scala changes detected in repo:")
      modifiedInRepo.foreach(line => println(s"  $line"))
      sys.exit(1)

    // Identify targets based on your shell-logic equivalent
    val targets = SearchDir.path.walk
      .filter(_.ext == "scala")
      .filterNot(_.abs.contains(ExcludePath))
      .filterNot(_.abs.contains(BuildDir))
      .filter(p => InlineDefRegex.findFirstIn(p.contentAsString).isDefined)

    if verbose then println(s"Found ${targets.size} candidate files.")
    
    targets.foreach(deInline)
    println(s"Successfully prepped ${targets.size} files for JaCoCo.")

  def runRestore(): Unit =
    // Safety: Find all modified .scala files via git and check them back out
    val toRestore = "git status --porcelain".!!.linesIterator
      .map(_.substring(3)) // Strip status codes (e.g., ' M ')
      .filter(_.endsWith(".scala")).toList

    if toRestore.isEmpty then
      println("Nothing to restore.")
    else
      if verbose then println(s"Restoring: ${toRestore.mkString(", ")}")
      s"git checkout ${toRestore.mkString(" ")}".!
      println(s"Restored ${toRestore.size} files.")

  def deInline(p: Path): Unit =
    var inProtectedZone = false
    var braceCount = 0
    
    val newLines = p.lines.map { line =>
      // Surgeon logic: Protect matmul and dot in Mat.scala
      if (line.contains("def matmul") || line.contains("def dot")) && line.contains("inline") then
        inProtectedZone = true
        braceCount = 0

      if inProtectedZone then
        braceCount += line.count(_ == '{') - line.count(_ == '}')
        if braceCount <= 0 && line.contains("}") then inProtectedZone = false
        line 
      else
        line.replaceAll("\\binline\\b\\s*", "")
    }
    p.writeLines(newLines)

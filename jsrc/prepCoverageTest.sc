#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.5

import uni.*
import scala.sys.process.*

object PrepCoverageTest:
  val InlineDefRegex = """(?m)^ *inline (def|private def)""".r.unanchored

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
    // -uno tells git to ignore untracked files
    val modifiedInRepo = "git status --porcelain -uno".!!.linesIterator
      .filter(_.endsWith(".scala")).toList

    if modifiedInRepo.nonEmpty then
      println("Aborting: Uncommitted .scala changes detected in repo:")
      modifiedInRepo.foreach(line => println(s"  $line"))
      sys.exit(1)

    // Identify targets based on your shell-logic equivalent
    val SearchDir   = "src/main"
    val BuildDir    = ".scala-build"
    
    val srcdir = SearchDir.path
    if !srcdir.isDirectory then
      usage(s"not a directory [${srcdir.posx}]")

    val targets = srcdir.walk
      .filter(_.ext == "scala")
      .filterNot { p => 
        val path: String = p.posx
        // Check if 'cli' exists as a distinct directory segment
        path.contains("/cli/") || path.contains("HereDoc") || path.contains(BuildDir)
      }
      .filter(p => InlineDefRegex.findFirstIn(p.contentAsString).isDefined)
      .toList

    if verbose then println(s"Found ${targets.size} candidate files.")
    
    targets.foreach(deInline)
    println(s"Successfully prepped ${targets.size} files for JaCoCo.")

  def runRestore(): Unit =
    // Only restore files that git actually knows about (Tracked & Modified)
    // 'git diff --name-only' is the cleanest way to get just modified tracked files
    val toRestore = "git diff --name-only".!!.linesIterator
      .filter(_.endsWith(".scala")).toList

    if toRestore.isEmpty then
      println("Nothing to restore.")
    else
      if verbose then println(s"Restoring: ${toRestore.mkString(", ")}")
      // Using -- to separate paths from the command is safer
      s"git checkout -- ${toRestore.mkString(" ")}".!
      println(s"Restored ${toRestore.size} files.")

  def deInline(p: Path): Unit =
    var content = p.contentAsString

    if p.last == "Mat.scala" && content.contains("def matmul") then
      val startKey = "inline def matmul(other: Mat[T]): Mat[T] = {"
      val startIndex = content.indexOf(startKey)
      
      if (startIndex != -1) {
        // Find the matching closing brace for the method
        var braceCount = 0
        var endIndex = -1
        for (i <- startIndex until content.length if endIndex == -1) {
          if (content(i) == '{') braceCount += 1
          else if (content(i) == '}') {
            braceCount -= 1
            if (braceCount == 0) endIndex = i + 1
          }
        }

        if (endIndex != -1) {
          val matmulReplacement = 
            """def matmul(other: Mat[T]): Mat[T] = {
              |      if m.cols != other.rows then
              |        throw IllegalArgumentException(s"m.cols[${m.cols}] != other.rows[${other.rows}]")
              |
              |      val cls = summon[ClassTag[T]].runtimeClass
              |      if (cls == classOf[Double]) {
              |        val a = m.asInstanceOf[Mat[Double]]; val b = other.asInstanceOf[Mat[Double]]
              |        (if shouldUseBLAS(a, b) then multiplyDoubleBLAS(b) else multiplyDouble(b)).asInstanceOf[Mat[T]]
              |      } 
              |      else if (cls == classOf[Float]) {
              |        val a = m.asInstanceOf[Mat[Float]]; val b = other.asInstanceOf[Mat[Float]]
              |        (if shouldUseBLAS(a, b) then multiplyFloatBLAS(b) else multiplyFloat(b)).asInstanceOf[Mat[T]]
              |      } 
              |      else if (cls == classOf[BigDecimal]) {
              |        multiplyBig(other.asInstanceOf[Mat[Big]]).asInstanceOf[Mat[T]]
              |      } else {
              |        throw UnsupportedOperationException(s"No matmul for type $cls")
              |      }
              |    }""".stripMargin
          content = content.substring(0, startIndex) + matmulReplacement + content.substring(endIndex)
        }
      }

    // Now that the 'hard' one is replaced with standard Scala, purge ALL 'inline'
    // This is safe because matmul is no longer 'inline def'
    val finalContent = content.replaceAll("(?<!@)\\binline\\b\\s*", "")
    p.write(finalContent)

  lazy val matmulReplacement: String = """
    def matmul(other: Mat[T]): Mat[T] = {
      if m.cols != other.rows then
        throw IllegalArgumentException(s"m.cols[${m.cols}] != other.rows[${other.rows}]")

      // 2. Get the runtime class from the ClassTag (which you already have)
      val cls = summon[ClassTag[T]].runtimeClass

      // 3. Match on the value of the class
      if (cls == classOf[Double]) {
        val a = m.asInstanceOf[Mat[Double]]; val b = other.asInstanceOf[Mat[Double]]
        (if shouldUseBLAS(a, b) then multiplyDoubleBLAS(b) else multiplyDouble(b)).asInstanceOf[Mat[T]]
      } 
      else if (cls == classOf[Float]) {
        val a = m.asInstanceOf[Mat[Float]]; val b = other.asInstanceOf[Mat[Float]]
        (if shouldUseBLAS(a, b) then multiplyFloatBLAS(b) else multiplyFloat(b)).asInstanceOf[Mat[T]]
      } 
      else if cls == classOf[BigDecimal] then {
        multiplyBig(other.asInstanceOf[Mat[Big]]).asInstanceOf[Mat[T]]
      } else {
        throw UnsupportedOperationException(s"No matmul for type $cls")
      }
    }
"""

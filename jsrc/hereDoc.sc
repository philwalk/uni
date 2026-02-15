#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.8.1

import uni.*
import java.nio.file.{Files as JFiles, Paths as JPaths, Path as JPath}
import scala.jdk.CollectionConverters.*

/** 
 * Provides Perl-style __DATA__ / Ruby-style __END__ sections for Scala.
 * Data must be in a trailing comment block: /* __DATA__ ... */
 */

object HereDoc {
  var verbose = false
  
  def main(args: Array[String]): Unit = {
    verbose = args.contains("-v")
    DATA.foreach(println)
  }

  def DATA: Iterator[String] = scriptData  // Perl-style
  def END: Iterator[String] = scriptData   // Ruby-style

  private def sourcePath: JPath = {
    val fname = progName(this)
    val p = JPaths.get(fname)
    if (JFiles.isRegularFile(p)) p
    else findBelowCwd(p.getFileName.toString)
  }

  private def findBelowCwd(fname: String): JPath = {
    import java.nio.file.{Files, FileVisitOption}
    val cwd = JPaths.get(".")
    
    Files.walk(cwd, FileVisitOption.FOLLOW_LINKS)
      .iterator.asScala
      .find(p => p.getFileName.toString == fname && JFiles.isRegularFile(p))
      .getOrElse(JPaths.get(fname))
  }

  private def scriptData: Iterator[String] =
    val lines = sourcePath.lines
    lines.dropWhile(!_.trim.matches("""/\*\s*__(DATA|END)__"""))
      .drop(1)  // skip the __DATA__ line
      .takeWhile(!_.trim.startsWith("*/"))
}

/* __DATA__
a=1
b=2
c=3
*/
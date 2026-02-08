package uni.data

import uni.*
import java.nio.file.{Files as JFiles, Paths as JPaths, Path as JPath}
import scala.jdk.CollectionConverters.*

/** 
 * Provides Perl-style __DATA__ / Ruby-style __END__ sections for Scala.
 * Data must be in a trailing comment block: /* __DATA__\n ...\n */
 */

/* The __DATA__ section, if present, must be at the bottom of a source file.
 * If the following 4 lines were at the end of the file, they would suffice.
 * But as the example here is not at the end of the file, it must NOT be chosen:
 */

/* __DATA___
a b c d e
f g h i j
*/

// Because __DATA__ section comment must be at the end of the source file,
// the above facsimile is not valid and must be ignored.
object HereDoc {
  inline def DATA: Iterator[String] = sourceData(progPath)  // Perl-style
  inline def END: Iterator[String] = sourceData(progPath)   // Ruby-style
  def DATA(sourcePath: String): Iterator[String] = sourceData(sourcePath)  // Perl-style
  def END(sourcePath: String): Iterator[String] = sourceData(sourcePath)   // Ruby-style

  private val verboseUni = false
  private[uni] def sourcePath(fname: String): JPath = {
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

  private def sourceData(fname: String): Iterator[String] =
    val p: Path = sourcePath(fname)
    if verboseUni then println(s"HereDoc.sourceData($fname): [${p.posx}]")
    import java.nio.charset.StandardCharsets.UTF_8
    val lines = java.nio.file.Files.readAllLines(p, UTF_8).asScala
    
    // Find all __DATA__ sections and take the last one
    val open  = "/" + "*"
    val close = "*" + "/"

    // 1. Find the index of the last opening delimiter
    val lastOpenIndex = lines.lastIndexWhere(_.trim.startsWith(open))

    // If not found, return empty iterator
    if lastOpenIndex < 0 then Iterator.empty
    else
      // 2. Stream forward from that point
      lines
        .iterator
        .drop(lastOpenIndex + 1)               // skip the /* __DATA__ line
        .takeWhile(!_.trim.startsWith(close))  // stream until */ 

}

/* __DATA__
a=1
b=2
c=3
*/

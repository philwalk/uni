#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.6.2

import uni.*
import uni.io.*

// 
object ColumnN {
  def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "<inputCsvFile>",
      "<colnum>   ; zero-based column index"
    )
  }

  var verbose = false
  var colnum = -5
  var fullstack = false
  var inputFile: Option[Path] = None

  def main(args: Array[String]): Unit = {
    try {
      eachArg(args.toSeq, usage) {
        case "-fullstack" =>
          fullstack = true
        case "-v" =>
          verbose = true
        case fname if fname.path.isFile =>
          if inputFile.nonEmpty then
            usage(s"2nd filename [$fname] but already specified [${inputFile.get}]")
          val p = fname.path
          if (!p.isFile) {
            usage(s"not found [${p.posx}]")
          }
          inputFile = Some(p)
        case n if n.matches("[0-9]+") =>
          if colnum != -5 then
            usage(s"2nd column number [$n] but already specified [$colnum]")
          colnum = n.toInt
        case arg =>
          usage(s"unrecognized arg [$arg]")
      }
      if (colnum < 0) {
        usage()
      }
      val rows = FastCsv.rowsAsync(inputFile.get).dropWhile(_.size < 2).toSeq
      if (verbose) {
        eprintf("%s x %s\n", rows.size, rows.head.size)
      }
      val columnN: Seq[String] = rows.filter { _.size > colnum }.map { row =>
        row(colnum)
      }
      printf("%s\n", columnN.mkString("\n"))
    } catch {
    case e: Exception =>
      if (fullstack) {
        throw e
      } else {
        //showLimitedStack(e)    // removes java, scala, sun, oracle, etc.
        showMinimalStack(e, this) // removes all but stack entries with this object name (case-insensitive)
      }
    }
  }
}

//#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
package uni.apps

//> using dep org.vastblue:uni_3:0.19.1

import uni.*
import uni.io.*

object ColumnN {
  def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "<inputCsvFile>",
      "<colnum>   ; zero-based column index"
    )
  }

  var verbose = false
  private var colnum = -1
  private var fullstack = false
  private var inputFile: Option[Path] = None

  def main(args: Array[String]): Unit = {
    try {
      eachArg(args.toSeq, usage) {
        case "-fullstack" =>
          fullstack = true
        case "-v" =>
          verbose = true
        case fname if fname.asPath.isFile =>
          if inputFile.nonEmpty then
            usage(s"2nd filename [$fname] but already specified [${inputFile.get}]")
          val p = fname.asPath
          if (!p.isFile) {
            usage(s"not found [${p.posx}]")
          }
          inputFile = Some(p)
        case n if n.matches("[1-9][0-9]*") =>
          if colnum >= 0 then
            usage(s"2nd column number [$n] but already specified [$colnum]")
          colnum = n.toInt
        case arg =>
          usage(s"unrecognized arg [$arg]")
      }
      if (colnum < 0) {
        usage()
      }
      val rows = FastCsv.rowsAsync(inputFile.get).toSeq
      if (verbose) {
        eprintf("%s x %s\n", rows.size, rows.map(_.size).maxOption.getOrElse(0))
      }
      // One output line per content row, "" where the row has no such cell, so the
      // output stays positionally aligned with the input on ragged files.
      val columnN: Seq[String] = rows.map(_.lift(colnum).getOrElse(""))
      printf("%s\n", columnN.mkString("\n"))
    } catch {
    case e: Exception =>
      if (fullstack) {
        throw e
      } else {
        //showLimitedStack(e)    // removes java, scala, sun, oracle, etc.
        showMinimalStack(e) // removes all but stack entries with this object name (case-insensitive)
      }
    }
  }
}
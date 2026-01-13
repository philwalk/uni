#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.5.2

import uni.*
import uni.fs.*
import uni.io.*

object CsvClientRows {

  def usage(m: String = ""): Unit = {
    if (m.nonEmpty) println(m)
    System.err.println(s"Usage: ${progName(this)} [-async | -pulled] <csv-file>")
    System.exit(1)
  }
  def main(args: Array[String]): Unit = {
    var fname = ""
    var iterType = ""
    args.foreach { arg =>
      arg match {
      case "-async" | "-pulled" =>
        iterType = arg
      case pathstr if Paths.get(pathstr).isFile =>
        fname = pathstr
      case _ =>
        usage(s"unrecognized arg [$arg]")
      }
    }
    if (fname.isEmpty) {
      usage()
    }

    val path = Paths.get(fname)
    val cfg = FastCsv.Config() // delimiter optional; discovery runs if None
    val iterator: Iterator[Seq[String]] = iterType match {
    case "-pulled" => FastCsv.rowsPulled(path, cfg)
    case "-async" => FastCsv.rowsAsync(path, cfg)
    case _ => Iterator.empty
    }
    if (iterator.isEmpty) {
      // synchronous push-based
      FastCsv.eachRow(path, cfg){ row =>
        println(row.mkString("|"))
      }
    } else {
      // row iterators
      for (row <- iterator) {
      //for (row <- FastCsv.rowsAsync(path, cfg)) {
        // Each row is a Seq[String]
        println(row.mkString("|"))
      }
    }
  }
}

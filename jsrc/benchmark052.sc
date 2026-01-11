#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.5.2

import uni.*
import uni.fs.*
import uni.io.*

// Version 0.5.2 performance check
object Benchmark052 {
  def usage(m: String = ""): Unit = {
    if (m.nonEmpty) System.err.println(m)
    System.err.println(s"Usage: ${uni.progName(this)} [-async | -pulled] <csv-file>")
    System.exit(1)
  }

  var inputFiles = Seq.empty[Path]

  def main(args: Array[String]): Unit = {
    var iterType = ""
    args.foreach { arg =>
      arg match {
      case "-async" | "-pulled" =>
        iterType = arg
      case pathstr if Paths.get(pathstr).isFile =>
        inputFiles :+= Paths.get(pathstr)
      case _ =>
        usage(s"unrecognized arg [$arg]")
      }
    }
    if (inputFiles.isEmpty) {
      usage()
    }

    val cfg = FastCsv.Config() // delimiter optional; discovery runs if None
    for (path <- inputFiles) {
      val iterator: Iterator[Seq[String]] = iterType match {
      case "-pulled" => FastCsv.rowsPulled(path, cfg)
      case "-async" => FastCsv.rowsAsync(path, cfg)
      case _ => Iterator.empty
      }
      eprintf("# %s\n", path.posx)
      if (iterator.isEmpty) {
        // synchronous push-based
        FastCsv.eachRow(path, cfg){ row =>
          println(row.mkString("|").trim)
        }
      } else {
        // row iterators
        for (row <- iterator) {
          // Each row is a Seq[String]
          println(row.mkString("|"))
        }
      }
    }
  }
}

#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.8.1

import uni.*
import uni.io.*

object CsvMinimizeQuotes {

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      System.err.println(s"Usage: ${progName(this)} <csv-file>")
      System.exit(1)
    }
    import uni.io.CsvFormatter.formatRow
    args.foreach { arg =>
      val path = Paths.get(arg)
      val res = Delimiter.detect(path, maxRows = 20)
      FastCsv.eachRow(path){ row =>
        printf("%s\n", formatRow(row, res.delimiterChar))
      }
    }
  }
}
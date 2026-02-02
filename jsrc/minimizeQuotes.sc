#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.7.0

import uni.* // {Path, Paths}
import uni.io.* // {FastCsv, CsvWriter, Delimiter}
import java.io.BufferedWriter

object MinimizeQuotes {
  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      System.err.println(s"Usage: ${progName(this)} <csv-file>")
      System.exit(1)
    }
    args.foreach { arg =>
      val p = Paths.get(arg)
      minimizeQuotes(p)
    }
  }
  def minimizeQuotes(p: Path): Unit = {
    val delimiter = Delimiter.detect(p, 20).delimiterChar

    val out = new BufferedWriter(new java.io.OutputStreamWriter(System.out))
    val writer = new CsvWriter(out, delimiter)

    FastCsv.eachRow(p) { row =>
      writer.writeRow(row)
    }

    writer.flush()
  }
}

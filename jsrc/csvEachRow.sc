#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.5.0

import uni.io.*
import java.nio.file.Paths

object CsvEachRow {

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      System.err.println("Usage: CsvClientRows <csv-file>")
      System.exit(1)
    }

    val path = Paths.get(args(0))
    val cfg = FastCsv.Config() // delimiter optional; discovery runs if None

    // Use the new rows() method
    FastCsv.eachRow(path, cfg) { row =>
      // Each row is a Seq[String]
      println(row.mkString("[", ", ", "]"))
    }
  }
}

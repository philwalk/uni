#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.0

import java.nio.charset.StandardCharsets
import uni.*
import uni.io.*

object CsvClient {

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      System.err.println("Usage: CsvClient <csv-file> [--discover]")
      System.exit(1)
    }

    val path = Paths.get(args(0))

    val cfg = FastCsv.Config(
      delimiterChar = None, // leave None if discovery is desired
      charset = StandardCharsets.UTF_8,
      bufferSize = 4 << 20 // 4 MiB buffer
    )

    if (false) {
      val sink = new FastCsv.RowSink {
        override def onRow(fields: Array[Array[Byte]]): Unit = {
          val decoded = FastCsv.decodeFields(fields, cfg.charset)
          // For demo: print each row
          println(decoded.mkString("[", ", ", "]"))
        }
      }
      FastCsv.parse(path, sink, cfg)
    } else {
      FastCsv.eachRow(path, cfg){ row =>
        println(row.mkString("|"))
      }
    }
    sys.error(s"quit after first file [${path.posx}]") 
  }
}
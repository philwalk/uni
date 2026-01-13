package uni.apps

import java.nio.file.{Files, Paths}
import uni.io.FastCsv

object CsvBench:
  def main(args: Array[String]): Unit =
    if args.length != 1 then
      System.err.println("Usage: CsvBench <csv-file>")
      sys.exit(1)

    val path = Paths.get(args(0))
    val cfg  = FastCsv.Config()

    val t0   = System.nanoTime()
    var rows = 0L
    var cells = 0L

    FastCsv.eachRow(path, cfg) { row =>
      rows += 1
      cells += row.size
      // no-op on row to avoid dead-code elimination
      if (rows == -1) println(row)
    }

    val t1 = System.nanoTime()
    val ms = (t1 - t0) / 1e6
    val mb = Files.size(path) / (1024.0 * 1024.0)
    val mbps = mb / (ms / 1000.0)

    printf(
      "Rows: %d, Cells: %d, File: %.1f MiB, Time: %.1f ms, Throughput: %.1f MiB/s%n",
      rows, cells, mb, ms, mbps
    )

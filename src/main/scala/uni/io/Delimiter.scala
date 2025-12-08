package uni.io

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import scala.collection.mutable

object Delimiter {

  // Common candidate delimiters
  val candidates: Array[Char] = Array(',', '\t', ';', '|', ':')

  final case class Result(delimiter: Char, modeColumns: Int, consistency: Double)

  def detect(path: Path, sampleRows: Int = 100): Result = {
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8).toArray
      .take(sampleRows)
      .map(_.asInstanceOf[String])

    var best: Result = Result(',', 0, 0.0)

    for (delim <- candidates) {
      val counts = lines.map { line =>
        // naive split, but respect quotes minimally
        splitRespectQuotes(line, delim).length
      }

      val freq = counts.groupBy(identity).mapValues(_.length)
      val (modeCols, modeCount) = freq.maxBy(_._2)
      val consistency = modeCount.toDouble / counts.length

      val res = Result(delim, modeCols, consistency)

      // Choose by highest consistency, then by largest mode
      if (res.consistency > best.consistency ||
          (res.consistency == best.consistency && res.modeColumns > best.modeColumns)) {
        best = res
      }
    }

    best
  }

  private def splitRespectQuotes(line: String, delim: Char): Array[String] = {
    val out = mutable.ArrayBuffer.empty[String]
    val sb = new StringBuilder
    var inQuotes = false
    var i = 0
    while (i < line.length) {
      val c = line.charAt(i)
      if (c == '"') {
        inQuotes = !inQuotes
      } else if (c == delim && !inQuotes) {
        out += sb.toString
        sb.clear()
      } else {
        sb.append(c)
      }
      i += 1
    }
    out += sb.toString
    out.toArray
  }
}

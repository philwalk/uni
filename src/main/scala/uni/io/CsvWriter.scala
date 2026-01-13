package uni.io

import java.io.Writer

class CsvWriter(
  out: Writer,
  delimiter: Char = ',',
  quoteChar: Char = '"'
) {

  private val quoteStr = quoteChar.toString
  private val doubleQuote = quoteStr + quoteStr

  /** Write a single CSV row directly to the underlying Writer. */
  def writeRow(row: Seq[String]): Unit = {
    var i = 0
    while (i < row.length) {
      if (i > 0) out.write(delimiter)
      writeField(row(i))
      i += 1
    }
    out.write('\n')
  }

  /** Write a single field with minimal quoting. */
  private def writeField(col: String): Unit = {
    val needsQuote =
      col.contains(delimiter) ||
      col.contains(quoteChar) ||
      col.contains('\n') ||
      col.contains('\r') ||
      (col.nonEmpty && (col.head.isWhitespace || col.last.isWhitespace))

    if (!needsQuote) {
      out.write(col)
    } else {
      out.write(quoteChar)
      // escape internal quotes
      var i = 0
      while (i < col.length) {
        val c = col.charAt(i)
        if (c == quoteChar) out.write(doubleQuote)
        else out.write(c)
        i += 1
      }
      out.write(quoteChar)
    }
  }

  /** Flush underlying writer. */
  def flush(): Unit = out.flush()

  /** Close underlying writer. */
  def close(): Unit = out.close()
}

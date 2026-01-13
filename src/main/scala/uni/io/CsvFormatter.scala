package uni.io

object CsvFormatter {
  /** Format a single CSV row with minimal quoting.
    *
    * @param row  sequence of column values
    * @param delimiter  delimiter character (default ',')
    * @return CSV-formatted line (without trailing newline)
    */
  def formatRow(row: Seq[String], delimiter: Char = ','): String = {
    val sb = new StringBuilder(row.size * 8)

    var i = 0
    while (i < row.length) {
      if (i > 0) sb.append(delimiter)
      sb.append(quoteIfNeeded(row(i), delimiter))
      i += 1
    }

    sb.toString
  }

  /** Quote a field only when required by RFC 4180.
    *
    * Rules:
    *   - Quote if field contains delimiter
    *   - Quote if field contains quote
    *   - Quote if field contains newline or CR
    *   - Quote if field has leading/trailing whitespace
    *   - Escape internal quotes by doubling them
    */
  private def quoteIfNeeded(col: String, delimiter: Char): String = {
    val needsQuote =
      col.contains(delimiter) ||
      col.contains('"') ||
      col.contains('\n') ||
      col.contains('\r') ||
      (col.nonEmpty && (col.head.isWhitespace || col.last.isWhitespace))

    if (!needsQuote) {
      col
    } else {
      val escaped = col.replace("\"", "\"\"")
      "\"" + escaped + "\""
    }
  }
}

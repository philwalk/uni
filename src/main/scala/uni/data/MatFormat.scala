package uni.data

def formatMatrix[T](
  tdata: Array[T],
  rows: Int,
  cols: Int,
  offset: Int,
  rs: Int,
  cs: Int,
  typeName: String,
  toDouble: T => Double,
  mkString: T => String,
  fmt: Option[String] = None
): String = {

  def getValue(i: Int, j: Int): T =
    tdata(offset + i * rs + j * cs)

  // Truncation thresholds
  val maxRows = Mat.PrintOptions.maxRows
  val maxCols = Mat.PrintOptions.maxCols
  val edgeRows = Mat.PrintOptions.edgeItems
  val edgeCols = Mat.PrintOptions.edgeItems

  val truncateRows = rows > maxRows
  val truncateCols = cols > maxCols

  // Determine which indices to display
  val rowIndices = if (truncateRows) {
    (0 until edgeRows) ++ (rows - edgeRows until rows)
  } else {
    0 until rows
  }

  val colIndices = if (truncateCols) {
    (0 until edgeCols) ++ (cols - edgeCols until cols)
  } else {
    0 until cols
  }

  val displayRows = rowIndices.size
  val displayCols = colIndices.size

  // Helper to trim zeros
  def trimZeros(s: String, keepDecimal: Boolean): String = {
    val parts = s.split("[eE]", 2)
    val main  = parts(0)
    val exp   = if (parts.length == 2) "e" + parts(1) else ""

    val trimmedMain =
      if (main.contains('.')) {
        val r = main.reverse.dropWhile(_ == '0').dropWhile(_ == '.').reverse
        if (r.isEmpty) "0"
        else if (keepDecimal && !r.contains('.')) r + "."
        else r
      } else main
    trimmedMain + exp
  }

  // Automatic formatting with high precision
  def renderAuto: String = {
    val values =
      for (i <- rowIndices; j <- colIndices)
      yield toDouble(getValue(i, j))

    if (values.isEmpty) {
      ""
    } else {
      val absMax = values.map(math.abs).max
      val absMin = values.filter(_ != 0.0).map(math.abs).minOption.getOrElse(0.0)

      val sci = absMax >= 1e8 || (absMin > 0 && absMin < 1e-6)

      val base =
        if (displayCols <= 4) 10
        else if (displayCols <= 8) 8
        else 6

      val spread = values.max - values.min

      val maxDec =
        if (sci) base
        else if (spread == 0.0) base
        else if (spread >= 100) base - 3
        else if (spread >= 10) base - 2
        else if (spread >= 1) base - 1
        else base

      val fmtStr = if (sci) s"%.${maxDec}e" else s"%.${maxDec}f"
      val raw = values.map(v => fmtStr.format(v)).toIndexedSeq
      renderRowsFromRaw(raw, isAuto = true)
    }
  }

  // Format with explicit format string
  def renderRows(fmtStr: String): String = {
    val isAuto = fmtStr.isEmpty

    val raw =
      if (isAuto) {
        for (i <- rowIndices; j <- colIndices)
        yield mkString(getValue(i, j))
      } else {
        for (i <- rowIndices; j <- colIndices)
        yield fmtStr.format(getValue(i, j))
      }
    renderRowsFromRaw(raw, isAuto)
  }

  // Core rendering logic
  def renderRowsFromRaw(raw: IndexedSeq[String], isAuto: Boolean): String = {
    val colHasDecimal = Array.fill(displayCols)(false)
    for (i <- 0 until displayRows; j <- 0 until displayCols) {
      if (raw(i * displayCols + j).contains('.')) {
        colHasDecimal(j) = true
      }
    }

    // 1. Guard against parsing non-numeric strings like "true"/"false"
    val values =
      if (raw.isEmpty || typeName == "Boolean") Nil
      else raw.map(s => scala.util.Try(s.toDouble).getOrElse(0.0)).map(math.abs)

    // 2. Only check for scientific notation if we actually have numeric values
    val useSci =
      if (values.isEmpty || typeName == "Boolean") {
        false
      } else {
        val absMax = values.max
        val absMin = values.filter(_ > 0).minOption.getOrElse(absMax)
        absMax >= 1e8 || absMin < 1e-6
      }

    val colIsInteger =
      if (isAuto) {
        if (useSci) {
          Array.fill(displayCols)(false)
        } else {
          val arr = Array.fill(displayCols)(true)
          for (i <- 0 until displayRows; j <- 0 until displayCols) {
            val d = toDouble(getValue(rowIndices(i), colIndices(j)))
            if (d != Math.rint(d)) {
              arr(j) = false
            }
          }
          arr
        }
      } else {
        Array.fill(displayCols)(false)
      }

    for (j <- 0 until displayCols) {
      if (colIsInteger(j)) {
        colHasDecimal(j) = false
      }
    }

    val trimmed =
      if (isAuto) {
        for (idx <- raw.indices) yield {
          val col = idx % displayCols
          if (colIsInteger(col)) {
            val s = raw(idx)
            val dot = s.indexOf('.')
            if (dot >= 0) s.substring(0, dot)
            else s
          } else {
            val t0 = trimZeros(raw(idx), keepDecimal = colHasDecimal(col))
            if (colHasDecimal(col)) {
              if (t0.contains('.')) {
                if (t0.endsWith(".")) t0 + "0" else t0
              } else {
                t0 + ".0"
              }
            } else {
              t0
            }
          }
        }
      } else {
        raw
      }

    val split =
      trimmed.map(_.stripLeading).map { s =>
        val parts = s.split("\\.", 2)
        if (parts.length == 1) (parts(0), "")
        else (parts(0), parts(1))
      }

    val intWidth  = Array.fill(displayCols)(0)
    val fracWidth = Array.fill(displayCols)(0)

    for (i <- 0 until displayRows; j <- 0 until displayCols) {
      val (intp, fracp) = split(i * displayCols + j)
      intWidth(j)  = math.max(intWidth(j),  intp.length)
      fracWidth(j) = math.max(fracWidth(j), fracp.length)
    }

    // Add ellipsis column widths if truncating columns
    val sb = new StringBuilder
    var idx = 0

    for (displayRowIdx <- 0 until displayRows) {
      // Insert row ellipsis after edge rows
      if (truncateRows && displayRowIdx == edgeRows) {
        sb.append(" ...\n")
      }

      sb.append(" (")
      for (displayColIdx <- 0 until displayCols) {
        // Insert column ellipsis after edge cols
        if (truncateCols && displayColIdx == edgeCols) {
          sb.append("...")
          if (displayColIdx < displayCols - 1) sb.append(", ")
        }

        val (intp, fracp) = split(idx)
        sb.append(" " * (intWidth(displayColIdx) - intp.length))
        sb.append(intp)

        if (colHasDecimal(displayColIdx)) {
          sb.append(".")
          sb.append(fracp)
          sb.append(" " * (fracWidth(displayColIdx) - fracp.length))
        }

        if (displayColIdx < displayCols - 1) sb.append(", ")
        idx += 1
      }
      sb.append(")")
      if (displayRowIdx < displayRows - 1) sb.append(",\n")
    }

    sb.toString
  }

  val header = s"${rows}x${cols} Mat[$typeName]:"
  val body =
  if (fmt.isDefined) {
    renderRows(fmt.get)
  } else if (typeName == "Boolean" || typeName == "Big") {
    renderRows("") // Passing empty string triggers the isAuto logic in renderRows
  } else {
    renderAuto
  }

  if (body.isEmpty) header
  else s"$header\n$body"

}

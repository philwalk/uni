package uni.data

import scala.reflect.ClassTag
import scala.compiletime.erasedValue
//import scala.util.Random

object Mat {
  // Opaque type wraps flat array with dimensions
  opaque type Mat[T] = Internal.MatData[T]
  private type MatData[T] = Internal.MatData[T]
  // Type aliases for documentation - same as Mat[T] at runtime
  // Vec[T]    - either row or column vector (rows==1 or cols==1)
  // RowVec[T] - row vector (rows==1)
  // ColVec[T] - column vector (cols==1)
  type Vec[T]    = Mat[T]
  type RowVec[T] = Mat[T]
  type ColVec[T] = Mat[T]

  private object Internal {
    class MatData[T] private[Internal](
      private[Mat] val _tdata: Array[T],
      private[Mat] val _rows: Int,
      private[Mat] val _cols: Int,
      private[Mat] val _transposed: Boolean = false, // Keep this temporarily for staging
      private[Mat] val _offset: Int = 0,  // Default to 0
      private[Mat] val _rs: Int = -1,     // rowSride ; phase 0_: always 'cols'
      private[Mat] val _cs: Int = -1,     // colStride ; phase 0_: always 1
    ) {
      override def toString: String = {
        val typeName = _tdata.getClass.getComponentType match {
          case java.lang.Double.TYPE => "Double"  // ← Capitalize primitive
          case java.lang.Float.TYPE  => "Float"
          case c if c == classOf[BigDecimal] => "Big"
          case other => other.getSimpleName
        }
        formatMatrix(
          _tdata, _rows, _cols, _offset, _rs, _cs,
          typeName,
          toDouble = {
            case d: Double => d
            case f: Float => f.toDouble
            case b: BigDecimal => b.toDouble
            case i: Int => i.toDouble
            case l: Long => l.toDouble
            case _ => 0.0  // fallback
          },
          mkString = _.toString,
          fmt = None
        )
      }
    }

    private[data] def create[T: ClassTag](
      _tdata: Array[T],
      _rows: Int,
      _cols: Int,
      _transposed: Boolean = false,
      _offset: Int = 0, // added in phase 2
      _rs: Int = -1,
      _cs: Int = -1,
    ): Mat[T] = {
      // We explicitly calculate the strides that your legacy code
      // was previously calculating implicitly inside the 'apply' if/else.
      val actualRs = if (_rs >= 0) _rs else (if _transposed then 1 else _cols)
      val actualCs = if (_cs >= 0) _cs else (if _transposed then _rows else 1)

      // must be the ONLY place the private constructor is used!
      val m = new MatData(_tdata, _rows, _cols, _transposed, _offset, actualRs, actualCs)

      if m.isWeirdLayout then
        val clean = m.matCopy
        //println(s"GUARD TRIGGERED: Original Transposed=${m.transposed}, clean Transposed=${clean.transposed}")
        clean
      else
        m
    }
    private[uni] def createTestView[T: ClassTag](
      _tdata: Array[T], _rows: Int, _cols: Int, _t: Boolean, _offset: Int, _rs: Int, _cs: Int
    ): Mat[T] = new MatData(_tdata, _rows, _cols, _t, _offset, _rs, _cs)

    // This is the ONLY bridge allowed to call 'new'
    // We mark it 'private' so it is ONLY visible inside 'Internal'
  //private def constructorBridge[T](...): MatData[T] = new MatData(...)

    // Now, we put the 'create' logic INSIDE Internal.
    // This makes 'Internal.create' the ONLY public entrance.
  }

  // The 'Mat.create' that the rest of your app uses
  // just forwards to the Internal gatekeeper.
  def create[T: ClassTag](
      tdata: Array[T],
      rows: Int,
      cols: Int,
      transposed: Boolean = false,
      offset: Int = 0, // added in phase 2
      rs: Int = -1,
      cs: Int = -1,
  ): Mat[T] = {
    Internal.create(tdata, rows, cols, transposed, offset, rs, cs)
  }


  object :: // Sentinel object for "all" in slicing

  def inspect: String =
    s"Mat(${rows}x${cols}, offset=$offset, rs=$rs, cs=$cs, transposed=$transposed)"

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

      val values =
        if (raw.isEmpty) Nil
        else raw.map(_.toDouble).map(math.abs)

      val useSci =
        if (values.isEmpty) {
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
    val body = if (fmt.isDefined) {
      renderRows(fmt.get)
    } else {
      renderAuto
    }
    
    if (body.isEmpty) header
    else s"$header\n$body"
    
  }

  // Global print options
  object PrintOptions {
    var maxRows: Int = 10        // NumPy doesn't have this, but useful
    var maxCols: Int = 10        // NumPy doesn't have this, but useful  
    var edgeItems: Int = 3       // NumPy has this - items to show on each edge
    var precision: Int = 8       // NumPy has this
    var suppressScientific: Boolean = false  // NumPy has this
    var threshold: Int = 1000    // NumPy has this - total elements before ellipsis
  }
  
  def setPrintOptions(
    maxRows: Int = PrintOptions.maxRows,
    maxCols: Int = PrintOptions.maxCols,
    edgeItems: Int = PrintOptions.edgeItems,
    precision: Int = PrintOptions.precision,
    suppressScientific: Boolean = PrintOptions.suppressScientific,
    threshold: Int = PrintOptions.threshold
  ): Unit = {
    PrintOptions.maxRows = maxRows
    PrintOptions.maxCols = maxCols
    PrintOptions.edgeItems = edgeItems
    PrintOptions.precision = precision
    PrintOptions.suppressScientific = suppressScientific
    PrintOptions.threshold = threshold
  }

  // ============================================================================
  // Core Properties (NumPy-aligned)
  // ============================================================================
  extension [T](m: Mat[T])
    // This is the "Unwrapper".
    // It MUST exist to tell the compiler: "Treat this as the class, not the opaque type."
    private def asData: MatData[T] = m.asInstanceOf[MatData[T]]
    private[data] def tdata: Array[T]        = asData._tdata
    private[data] def rows: Int              = asData._rows
    private[data] def cols: Int              = asData._cols
    private[data] def transposed: Boolean    = asData._transposed
    private[data] def shape: (Int, Int)      = (asData._rows, asData._cols)
    private[data] def size: Int              = asData._rows * asData._cols
    private[data] def underlying: Array[T]   = asData._tdata
    private[data] def rs: Int                = asData._rs
    private[data] def cs: Int                = asData._cs
    private[data] def offset: Int            = asData._offset
    private[data] def isEmpty: Boolean       = asData.size == 0
    def ndim: Int              = 2
    // In Mat extension methods

    def typeName(using tag: ClassTag[T]): String = {
      tag.runtimeClass match
        case java.lang.Double.TYPE => "Double"
        case java.lang.Float.TYPE  => "Float"
        case c if c == classOf[BigDecimal] => "Big"
        case other => other.getSimpleName
    }

    def show(using frac: Fractional[T], ct: ClassTag[T]): String = {
      formatMatrix(
        m.tdata, m.rows, m.cols, m.offset, m.rs, m.cs,
        typeName,
        toDouble = frac.toDouble,
        mkString = _.toString,
        fmt = None
      )
    }

    def show(fmt: String)(using frac: Fractional[T], ct: ClassTag[T]): String = {
      formatMatrix(
        m.tdata, m.rows, m.cols, m.offset, m.rs, m.cs,
        typeName,
        toDouble = frac.toDouble,
        mkString = _.toString,
        fmt = Some(fmt)
      )
    }
    def asMat: Mat[T] = m // a Vec is already a Mat, so no-op

    def isContiguous: Boolean =
      !m.transposed && m.rs == m.cols && m.cs == 1

    // 2. Stride-aware reshape
    def reshape(newRows: Int, newCols: Int)(using ct: ClassTag[T]): Mat[T] = {
      require(newRows * newCols == m.rows * m.cols,
        s"Cannot reshape ${m.rows}x${m.cols} to ${newRows}x${newCols}")

      if m.isContiguous then
        // Pass arguments in the correct order for your Mat.create:
        // tdata, rows, cols, transposed, offset, rs, cs
        Mat.create(m.tdata, newRows, newCols, false, m.offset, newCols, 1)
      else
        // If it's a slice or transposed, we must force a contiguous copy first
        m.toContiguous.reshape(newRows, newCols)
    }

    // 3. Helper to force a matrix into a standard contiguous layout
    def toContiguous(using ct: ClassTag[T]): Mat[T] = {
      if m.isContiguous then m
      else
        val newData = new Array[T](m.rows * m.cols)
        var i = 0
        while i < m.rows do
          var j = 0
          while j < m.cols do
            newData(i * m.cols + j) = m(i, j)
            j += 1
          i += 1
        Mat.create(newData, m.rows, m.cols, false, 0, m.cols, 1)
    }
    // Inside your extension [T](m: Mat[T]) block:

    /**
     * NumPy: m[i, j]
     * Element access with negative indexing support
     * m(-1, -1) accesses last element
     */
    inline def apply(row: Int, col: Int): T = {
      val r = if row < 0 then m.rows + row else row
      val c = if col < 0 then m.cols + col else col
      require(r >= 0 && r < m.rows && c >= 0 && c < m.cols,
        s"Index ($r, $c) out of bounds for ${m.rows}x${m.cols} matrix")
      // Unified stride equation
      m.tdata(m.offset + r * m.rs + c * m.cs)
    }

    /**
     * NumPy: m[i, j] = value
     * Element update with negative indexing support
     */
    inline def update(row: Int, col: Int, value: T): Unit = {
      val r = if row < 0 then m.rows + row else row
      val c = if col < 0 then m.cols + col else col
      require(r >= 0 && r < m.rows && c >= 0 && c < m.cols,
        s"Index ($r, $c) out of bounds for ${m.rows}x${m.cols} matrix")
      // Use the same unified stride equation as apply!
      m.tdata(m.offset + r * m.rs + c * m.cs) = value
    }
    /** m(rows: Range, ::) = value */
    def update(rows: Range, cols: ::.type, value: T): Unit =
      for r <- rows do
        var j = 0
        while j < m.cols do
          m(r, j) = value
          j += 1

    /** m(::, cols: Range) = value */
    def update(rows: ::.type, cols: Range, value: T): Unit =
      var i = 0
      while i < m.rows do
        for c <- cols do m(i, c) = value
        i += 1

    /** m(rows: Range, cols: Range) = value */
    def update(rows: Range, cols: Range, value: T): Unit =
      for r <- rows do
        for c <- cols do
          m(r, c) = value

    /** m(row: Int, cols: Range) = value */
    def update(row: Int, cols: Range, value: T): Unit =
      for c <- cols do m(row, c) = value

    /** m(rows: Range, col: Int) = value */
    def update(rows: Range, col: Int, value: T): Unit =
      for r <- rows do m(r, col) = value

    /** m(m.gt(0.0)) = 1.0 → set all elements where mask is true */
    def update(mask: Mat[Boolean], value: T): Unit = {
      require(mask.rows == m.rows && mask.cols == m.cols,
        s"Mask shape ${mask.shape} must match matrix shape ${m.shape}")
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          if mask(i, j) then m(i, j) = value
          j += 1
        i += 1
    }

    /** m(rows: Range, ::) = other Mat */
    def update(rows: Range, cols: ::.type, other: Mat[T]): Unit = {
      require(other.rows == rows.length && other.cols == m.cols,
        s"shape mismatch: target ${rows.length}x${m.cols} vs source ${other.shape}")
      var i = 0
      for r <- rows do
        var j = 0
        while j < m.cols do
          m(r, j) = other(i, j)
          j += 1
        i += 1
    }

    /** m(::, cols: Range) = other Mat */
    def update(rows: ::.type, cols: Range, other: Mat[T]): Unit = {
      require(other.rows == m.rows && other.cols == cols.length,
        s"shape mismatch: target ${m.rows}x${cols.length} vs source ${other.shape}")
      var i = 0
      while i < m.rows do
        var j = 0
        for c <- cols do
          m(i, c) = other(i, j)
          j += 1
        i += 1
    }

    /** m(rows: Range, cols: Range) = other Mat */
    def update(rows: Range, cols: Range, other: Mat[T]): Unit = {
      require(other.rows == rows.length && other.cols == cols.length,
        s"shape mismatch: target ${rows.length}x${cols.length} vs source ${other.shape}")
      var i = 0
      for r <- rows do
        var j = 0
        for c <- cols do
          m(r, c) = other(i, j)
          j += 1
        i += 1
    }

    // row mutator
    def update(row: Int, cols: ::.type, value: T): Unit =
      var j = 0
      while j < m.cols do
        m(row, j) = value
        j += 1

    // col mutator
    def update(rows: ::.type, col: Int, value: T): Unit =
      var i = 0
      while i < m.rows do
        m(i, col) = value
        i += 1

  // ============================================================================
  // Indexing (NumPy-aligned with negative index support)
  // ============================================================================
  extension [T: ClassTag](m: Mat[T])
  // 1. Helper to check if layout is standard row-major
    // All "Phase 0" creation methods must funnel through this helper
    // to set the default Row-Major strides.
    // Your temporary Phase 1 "Choke Point"
    def isWeirdLayout: Boolean = {
      // If it's already standard, it's definitely not weird.
      if m.isStandardContiguous then
        false
      else if m.rs == 0 || m.cs == 0 then
        false
      else
        val leadingDim = if m.transposed then m.cols else m.rows
        val majorStride = if m.transposed then m.cs else m.rs
        val isFragmented = majorStride > math.max(leadingDim, 1)
        isFragmented && !m.isStandardContiguous
    }

    def isStandardContiguous: Boolean =
      (m.rs == m.cols && m.cs == 1 && !m.transposed) ||
      (m.rs == 1 && m.cs == m.rows && m.transposed)

    /**
     * NumPy: m.copy()
     * Return deep copy preserving transposed state
     */
    def matCopy: Mat[T] = {
      val newData = Array.ofDim[T](m.rows * m.cols)
      var i = 0
      while (i < m.rows) {
        var j = 0
        while (j < m.cols) {
          // use the stride-aware apply to get the right values
          newData(i * m.cols + j) = m(i, j)
          j += 1
        }
        i += 1
      }
      Mat.create(newData, m.rows, m.cols, transposed = false)
    }

    /**
     * NumPy-style slicing: m.slice(rowRange, colRange)
     * Returns a zero-copy view of the sub-matrix.
     */
    def slice(rows: Range, cols: Range): Mat[T] = {
      // 1. Normalize ranges (handle Range.inclusive vs exclusive)
      val rStart = if (rows.start < 0) m.rows + rows.start else rows.start
      val cStart = if (cols.start < 0) m.cols + cols.start else cols.start

      val newRows = rows.length
      val newCols = cols.length

      // 2. Bounds check
      if (rStart < 0 || rStart + newRows > m.rows ||
          cStart < 0 || cStart + newCols > m.cols) {
        throw new IndexOutOfBoundsException(s"Slice $rows, $cols out of bounds for ${m.rows}x${m.cols}")
      }

      // 3. The Stride Magic: Calculate the new physical offset
      // The new starting point is the old offset plus the
      // jump to the first element of the slice.
      val newOffset = m.offset + (rStart * m.rs) + (cStart * m.cs)

      // 4. Funnel through the Choke Point
      // We pass the existing strides (rs, cs) and the new offset.
      // The Layout Guard will automatically check if this specific
      // sub-view is "weird" and copy it if necessary!
      Mat.create(
        m.underlying,
        newRows,
        newCols,
        m.transposed,
        newOffset,
        m.rs,
        m.cs
      )
    }

    /**
     * Virtually expands the matrix to the target dimensions.
     * If a dimension is 1, it can be broadcast to any size by setting stride to 0.
     */
    def broadcastTo(targetRows: Int, targetCols: Int): Mat[T] = {
      if m.rows == targetRows && m.cols == targetCols then
        m
      else
        // Stride Trick: If current dimension is 1, new stride is 0.
        // Otherwise, keep the original stride.
        val newRS = if (m.rows == 1 && targetRows > 1) 0 else m.rs
        val newCS = if (m.cols == 1 && targetCols > 1) 0 else m.cs

        // Validation: NumPy only allows broadcasting if dimensions match or are 1
        val canBroadcastRows = m.rows == targetRows || m.rows == 1
        val canBroadcastCols = m.cols == targetCols || m.cols == 1

        if (!canBroadcastRows || !canBroadcastCols) {
          throw new IllegalArgumentException(
            s"Cannot broadcast shape ${m.shape} to ($targetRows, $targetCols)"
          )
        }

        // Funnel through our offset-aware factory
        Mat.create(
          m.underlying,
          targetRows,
          targetCols,
          m.transposed,
          m.offset,
          newRS,
          newCS
        )
    }

//    /** * Enables: m(i, ::) := 10.0
//     * Or even: m(0 until 2, 0 until 2) := 0.0
//     */
//    def :=(r: Int, c: Int, value: T): Unit =
//      // Loop through the logical shape of the matrix (the slice)
//      // and use the stride-aware set logic
//      m.tdata(r * m.cols + c) = value

    /** Internal looper for broadcasting matrix-matrix operations */
    private def binOp(other: Mat[T])(op: (T, T) => T): Mat[T] = {
      val targetRows = math.max(m.rows, other.rows)
      val targetCols = math.max(m.cols, other.cols)
      val a = m.broadcastTo(targetRows, targetCols)
      val b = other.broadcastTo(targetRows, targetCols)
      val resData = new Array[T](targetRows * targetCols)
      var r = 0
      while (r < targetRows) {
        var c = 0
        while (c < targetCols) {
          resData(r * targetCols + c) = op(a(r, c), b(r, c))
          c += 1
        }
        r += 1
      }
      Mat.create(resData, targetRows, targetCols)
    }

    // --- Matrix-Matrix (Broadcasting) ---
    def +(other: Mat[T])(using num: Numeric[T]): Mat[T] = m.binOp(other)(num.plus)
    def -(other: Mat[T])(using num: Numeric[T]): Mat[T] = m.binOp(other)(num.minus)
    def *:*(other: Mat[T])(using num: Numeric[T]): Mat[T] = m.binOp(other)(num.times)
    def hadamard(other: Mat[T])(using num: Numeric[T]): Mat[T] = m *:* other

    def unary_-(using num: Numeric[T]): Mat[T] = {
      // We allocate an array for the result
      val result = new Array[T](m.rows * m.cols)
      var r = 0
      var idx = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          // Respects offset and strides!
          result(idx) = num.negate(m(r, c))
          idx += 1
          c += 1
        }
        r += 1
      }
      Mat.create(result, m.rows, m.cols)
    }

  // ============================================================================
  // Shape Manipulation
  // ============================================================================
  //extension [T: ClassTag](m: Mat[T])
    def T: Mat[T] = transpose
    /**
     * NumPy: m.T
     * O(1) transpose - flips flag and swaps dims, no data movement
     */
    def transpose: Mat[T] = {
      // We create a new view, flipping the 'transposed' flag,
      // but we MUST pass the existing offset and the SWAPPED strides.
      Mat.create(
        tdata = m.underlying,
        rows = m.cols,      // Rows/Cols swap logically
        cols = m.rows,
        transposed = !m.transposed,
        offset = m.offset,   // <--- This is likely what is missing!
        rs = m.cs,           // Row stride becomes the old Col stride
        cs = m.rs            // Col stride becomes the old Row stride
      )
    }

    def flatten: Array[T] = {
      // We use m.size here assuming it's defined as rows * cols
      if m.isContiguous && m.offset == 0 && m.tdata.length == m.rows * m.cols then
        m.tdata.clone()
      else
        val result = Array.ofDim[T](m.rows * m.cols)
        var i = 0
        while i < m.rows do
          var j = 0
          while j < m.cols do
            result(i * m.cols + j) = m(i, j)
            j += 1
          i += 1
        result
    }

    def data: Array[T] = m.asInstanceOf[MatData[T]].tdata

    /**
     * NumPy: m.ravel()
     * Return flattened view as row vector in logical order
     */
    def ravel: Mat[T] = Mat.create(flatten, 1, m.size)

    // ============================================================================
    // Arithmetic Operations
    // ============================================================================

    /** NumPy: m + scalar - Add scalar to all elements */
    def +(scalar: T)(using num: Numeric[T]): Mat[T] = {
      // 1. Allocate a fresh array for the result (this will be contiguous)
      val result = new Array[T](m.rows * m.cols)
      var r = 0
      var idx = 0
      // 2. Iterate through logical rows and columns
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          // 3. m(r, c) correctly calculates the offset using strides
          result(idx) = num.plus(m(r, c), scalar)
          idx += 1
          c += 1
        }
        r += 1
      }
      // 4. Return a new Mat pointing to the contiguous result array
      Mat.create(result, m.rows, m.cols)
    }
    def -(scalar: T)(using num: Numeric[T]): Mat[T] = {
      // 1. Allocate a fresh array for the result (this will be contiguous)
      val result = new Array[T](m.rows * m.cols)
      var r = 0
      var idx = 0
      // 2. Iterate through logical rows and columns
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          // 3. m(r, c) correctly calculates the offset using strides
          result(idx) = num.minus(m(r, c), scalar)
          idx += 1
          c += 1
        }
        r += 1
      }
      // 4. Return a new Mat pointing to the contiguous result array
      Mat.create(result, m.rows, m.cols)
    }
    def *(scalar: T)(using num: Numeric[T]): Mat[T] = {
      // 1. Allocate a fresh array for the result (this will be contiguous)
      val result = new Array[T](m.rows * m.cols)
      var r = 0
      var idx = 0
      // 2. Iterate through logical rows and columns
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          // 3. m(r, c) correctly calculates the offset using strides
          result(idx) = num.times(m(r, c), scalar)
          idx += 1
          c += 1
        }
        r += 1
      }
      // 4. Return a new Mat pointing to the contiguous result array
      Mat.create(result, m.rows, m.cols)
    }

    // ============================================================================
    // Statistical Methods
    // ============================================================================

    def min(using ord: Ordering[T]): T = {
      if (m.rows == 0 || m.cols == 0) throw new UnsupportedOperationException("empty matrix")
      var minValue = m(0, 0)
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          val current = m(r, c)
          if (ord.lt(current, minValue)) minValue = current
          c += 1
        }
        r += 1
      }
      minValue
    }

    def max(using ord: Ordering[T]): T = {
      if (m.rows == 0 || m.cols == 0) throw new UnsupportedOperationException("empty matrix")
      var maxValue = m(0, 0)
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          val current = m(r, c)
          if (ord.gt(current, maxValue)) maxValue = current
          c += 1
        }
        r += 1
      }
      maxValue
    }

    def sum(using num: Numeric[T]): T = {
      var total = num.zero
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          total = num.plus(total, m(i, j)) // Uses stride-aware apply
          j += 1
        i += 1
      total
    }

    def argmin(using ord: Ordering[T]): (Int, Int) = {
      if (m.rows == 0 || m.cols == 0) throw new UnsupportedOperationException("empty matrix")

      var minVal = m(0, 0)
      var minR = 0
      var minC = 0

      var i = 0
      while (i < m.rows) {
        var j = 0
        while (j < m.cols) {
          val current = m(i, j)
          if (ord.lt(current, minVal)) {
            minVal = current
            minR = i
            minC = j
          }
          j += 1
        }
        i += 1
      }
      (minR, minC)
    }

    def argmax(using ord: Ordering[T]): (Int, Int) = {
      if (m.rows == 0 || m.cols == 0) throw new UnsupportedOperationException("empty matrix")

      var maxVal = m(0, 0)
      var maxR = 0
      var maxC = 0

      var i = 0
      while (i < m.rows) {
        var j = 0
        while (j < m.cols) {
          val current = m(i, j)
          // Identical structure, just swapping 'lt' for 'gt'
          if (ord.gt(current, maxVal)) {
            maxVal = current
            maxR = i
            maxC = j
          }
          j += 1
        }
        i += 1
      }
      (maxR, maxC)
    }

    // ============================================================================
    // Functional Operations
    // ============================================================================

    def map[U: ClassTag](f: T => U): Mat[U] = {
      // Allocate a fresh, contiguous array for the result
      val resData = new Array[U](m.rows * m.cols)
      var r = 0
      var idx = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          // m(r, c) uses your stride/offset logic to find the REAL element
          resData(idx) = f(m(r, c))
          idx += 1
          c += 1
        }
        r += 1
      }
      Mat.create(resData, m.rows, m.cols)
    }

    def where(pred: T => Boolean): Array[(Int, Int)] = {
      val buf = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          if pred(m(i, j)) then buf += ((i, j))
          j += 1
        i += 1
      buf.toArray
    }

  // ============================================================================
  // Display
  // ============================================================================
  extension [T: ClassTag](m: Mat[T])(using frac: Fractional[T])
    /** m(0 until 2, ::) - range rows, all cols */
    def apply(rows: Range, cols: ::.type): Mat[T] =
      m(rows, 0 until m.cols)

    /** m(::, 0 until 2) - all rows, range cols */
    def apply(rows: ::.type, cols: Range): Mat[T] =
      m(0 until m.rows, cols)

    /** m(0, 0 until 2) - single row, range cols */
    def apply(row: Int, cols: Range): Mat[T] =
      m(row to row, cols)

    /** m(0 until 2, 0) - range rows, single col */
    def apply(rows: Range, col: Int): Mat[T] =
      m(rows, col to col)

    def /(scalar: T): Mat[T] = {
      val res = new Array[T](m.size)
      var r = 0; var idx = 0
      while r < m.rows do
        var c = 0
        while c < m.cols do
          res(idx) = frac.div(m(r, c), scalar)
          idx += 1; c += 1
        r += 1
      Mat.create(res, m.rows, m.cols)
    }

    /**
     * NumPy: m[:, col]
     * Extract column as column vector
     */
    def apply(rows: ::.type, col: Int): Mat[T] = {
      val c = if col < 0 then m.cols + col else col
      require(c >= 0 && c < m.cols, s"Column index $c out of bounds")
      val result = Array.ofDim[T](m.rows)
      var i = 0
      while i < m.rows do
        result(i) = m(i, c)
        i += 1
      Mat.create(result, m.rows, 1)
    }

    /**
     * NumPy: m[row, :]
     * Extract row as row vector
     */
    def apply(row: Int, cols: ::.type): Mat[T] = {
      val r = if row < 0 then m.rows + row else row
      require(r >= 0 && r < m.rows, s"Row index $r out of bounds")
      val result = Array.ofDim[T](m.cols)
      var j = 0
      while j < m.cols do
        result(j) = m(r, j)
        j += 1
      Mat.create(result, 1, m.cols)
    }

    /**
     * NumPy: m[rows, cols]
     * Rectangular slicing with Range support
     */
    def apply(rows: Range, cols: Range): Mat[T] = {
      val rowSeq = rows.toSeq
      val colSeq = cols.toSeq
      val newRows = rowSeq.length
      val newCols = colSeq.length
      val result = Array.ofDim[T](newRows * newCols)
      var i = 0
      while i < newRows do
        var j = 0
        while j < newCols do
          result(i * newCols + j) = m(rowSeq(i), colSeq(j))
          j += 1
        i += 1
      Mat.create(result, newRows, newCols)
    }


  // ============================================================================
  // Factory Methods
  // ============================================================================
  def zeros[T: ClassTag](rows: Int, cols: Int)(using frac: Fractional[T]): Mat[T] =
    Mat.create(Array.fill(rows * cols)(frac.zero), rows, cols)

  def zeros[T: ClassTag](shape: (Int, Int))(using frac: Fractional[T]): Mat[T] =
    zeros(shape._1, shape._2)

  def ones[T: ClassTag](rows: Int, cols: Int)(using frac: Fractional[T]): Mat[T] =
    Mat.create(Array.fill(rows * cols)(frac.one), rows, cols)

  def ones[T: ClassTag](shape: (Int, Int))(using frac: Fractional[T]): Mat[T] =
    ones(shape._1, shape._2)

  def eye[T: ClassTag](n: Int, k: Int = 0)(using frac: Fractional[T]): Mat[T] = {
    val result = Array.fill(n * n)(frac.zero)
    var i = 0
    while i < n do
      val j = i + k
      if j >= 0 && j < n then result(i * n + j) = frac.one
      i += 1
    Mat.create(result, n, n)
  }

  def full[T: ClassTag](rows: Int, cols: Int, value: T): Mat[T] =
    Mat.create(Array.fill(rows * cols)(value), rows, cols)

  def full[T: ClassTag](shape: (Int, Int), value: T): Mat[T] =
    full(shape._1, shape._2, value)

  def arange[T: ClassTag](stop: Int)(using frac: Fractional[T]): Mat[T] =
    Mat.create(Array.tabulate(stop)(i => frac.fromInt(i)), stop, 1)

  def arange[T: ClassTag](start: Int, stop: Int)(using frac: Fractional[T]): Mat[T] = {
    val n = stop - start
    require(n > 0, s"stop ($stop) must be greater than start ($start)")
    Mat.create(Array.tabulate(n)(i => frac.fromInt(start + i)), n, 1)
  }

  def arange[T: ClassTag](start: Int, stop: Int, step: Int)(using frac: Fractional[T]): Mat[T] = {
    require(step != 0, "step cannot be zero")
    val n = ((stop - start).toDouble / step).ceil.toInt
    require(n > 0, s"Invalid range: start=$start, stop=$stop, step=$step")
    Mat.create(Array.tabulate(n)(i => frac.fromInt(start + i * step)), n, 1)
  }

  def linspace[T: ClassTag](start: Double, stop: Double, num: Int = 50)(using frac: Fractional[T]): Mat[T] = {
    require(num > 0, "num must be positive")
    if num == 1 then Mat.create(Array(frac.fromInt(start.toInt)), 1, 1)
    else
      val step = (stop - start) / (num - 1)
      val data = Array.tabulate(num) { i =>
        val v = start + i * step
        summon[ClassTag[T]].runtimeClass match
          case c if c == classOf[Double]     => v.asInstanceOf[T]
          case c if c == classOf[Float]      => v.toFloat.asInstanceOf[T]
          case c if c == classOf[BigDecimal] => BigDecimal(v).asInstanceOf[T]
          case c => throw IllegalArgumentException(s"linspace unsupported: ${c.getName}")
      }
      Mat.create(data, num, 1)
  }
  def apply[T: ClassTag](rows: Int, cols: Int, data: Array[T]): Mat[T] = {
    require(data.length == rows * cols, s"Data length ${data.length} != $rows x $cols")
    Mat.create(data, rows, cols)
  }
  def apply[T: ClassTag](unit: Unit): Mat[T] = Mat.create(Array.ofDim[T](0), 0, 0)
  def apply[T: ClassTag](value: T): Mat[T]   = Mat.create(Array(value), 1, 1)
  def apply[T: ClassTag](tuples: Tuple*)(using frac: Fractional[T]): Mat[T] = {
    val rows = tuples.length
    if rows == 0 then Mat.create(Array.ofDim[T](0), 0, 0)
    else
      val cols = tuples(0).productArity
      val data = Array.ofDim[T](rows * cols)
      var i = 0
      while i < rows do
        val t = tuples(i)
        require(t.productArity == cols, "Jagged rows not allowed")
        var j = 0
        while j < cols do
          data(i * cols + j) = t.productElement(j) match
            case n: Int          => frac.fromInt(n)
            case n: Double       => n.asInstanceOf[T]
            case n: Float        => n.asInstanceOf[T]
            case n: BigDecimal   => n.asInstanceOf[T]
            case v: T @unchecked => v
            case other           => throw IllegalArgumentException(s"Unsupported type: ${other.getClass.getName}")
          j += 1
        i += 1
      Mat.create(data, rows, cols)
  }
  // Concrete-type single-value factories (unambiguous, no [T] required)
  def apply(value: Double): Mat[Double] = Mat.create(Array(value), 1, 1)
  def apply(value: Big): Mat[Big]       = Mat.create(Array(value), 1, 1)

  /** Explicit 1x1 matrix factory */
  def single[T: ClassTag](value: T): Mat[T] = Mat.create(Array(value), 1, 1)

  /** Create column vector from sequence */
  def fromSeq[T: ClassTag](values: Seq[T]): Mat[T] =
    if values.isEmpty then empty[T] else Mat.create(values.toArray, values.length, 1)

  /** Create row vector from varargs */
  def of[T: ClassTag](first: T, rest: T*): Mat[T] = Mat.create((first +: rest).toArray, 1, 1 + rest.length)

  /** Create matrix using generator function */
  def tabulate[T: ClassTag](rows: Int, cols: Int)(f: (Int, Int) => T): Mat[T] = {
    val data = Array.ofDim[T](rows * cols)
    var i = 0
    while i < rows do
      var j = 0
      while j < cols do { data(i * cols + j) = f(i, j); j += 1 }
      i += 1
    Mat.create(data, rows, cols)
  }
  /** Create empty 0x0 matrix */
  def empty[T: ClassTag]: Mat[T]           = Mat.create(Array.ofDim[T](0), 0, 0)

  /** Create row vector from values */
  def row[T: ClassTag](values: T*): Mat[T] = Mat.create(values.toArray, 1, values.length)

  /** Create column vector from values */
  def col[T: ClassTag](values: T*): Mat[T] = Mat.create(values.toArray, values.length, 1)

  /** Create column vector from varargs: Mat(1.0, 2.0, 3.0) → 3x1 */
  def apply[T: ClassTag](first: T, rest: T*): ColVec[T] = {
    val values = first +: rest
    create(values.toArray, values.length, 1)
  }

  extension [T: ClassTag](m: Mat[T])(using frac: Fractional[T])
    // applyAlongAxis
    def applyAlongAxis(fn: Mat[T] => T, axis: Int): Mat[T] = {
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      if axis == 0 then
        //val result = Array.tabulate(m.cols)(j => fn(m.slice(::, j)))
        val result = Array.tabulate(m.cols)(j => fn(m.slice(0 until m.rows, j to j)))

        Mat.create(result, 1, m.cols)
      else
        //val result = Array.tabulate(m.rows)(i => fn(m(i, ::)))
        val result = Array.tabulate(m.rows)(i => fn(m.slice(i to i, 0 until m.cols)))
        Mat.create(result, m.rows, 1)
    }

  // ============================================================================
  // Matrix Multiply + Linear Algebra (extension block)
  // ============================================================================
  extension [T: ClassTag](m: Mat[T]) {

    // ---- Matrix multiply -----------------------------------------------
    inline def matmul(other: Mat[T]): Mat[T] = {
      if m.cols != other.rows then
        throw IllegalArgumentException(s"m.cols[${m.cols}] != other.rows[${other.rows}]")
      inline erasedValue[T] match
        case _: Double =>
          val a = m.asInstanceOf[Mat[Double]]; val b = other.asInstanceOf[Mat[Double]]
          (if shouldUseBLAS(a, b) then multiplyDoubleBLAS(b) else multiplyDouble(b)).asInstanceOf[Mat[T]]
        case _: Float =>
          val a = m.asInstanceOf[Mat[Float]]; val b = other.asInstanceOf[Mat[Float]]
          (if shouldUseBLAS(a, b) then multiplyFloatBLAS(b) else multiplyFloat(b)).asInstanceOf[Mat[T]]
        case _: Big       => multiplyBig(other.asInstanceOf[Mat[Big]]).asInstanceOf[Mat[T]]
        case _: BigDecimal => multiplyBig(other.asInstanceOf[Mat[Big]]).asInstanceOf[Mat[T]]
    }

    inline def dot(other: Mat[T]): Mat[T] = m.matmul(other)

    // ---- Diagonal ------------------------------------------------------
    def diagonal: Array[T] = {
      val n = math.min(m.rows, m.cols)
      val result = Array.ofDim[T](n)
      var i = 0
      while i < n do { result(i) = m(i, i); i += 1 }
      result
    }

    // ---- L2 Norm (vector only) -----------------------------------------
    def norm(using frac: Fractional[T]): T = {
      require(m.cols == 1 || m.rows == 1,
        s"norm requires a vector (1xn or nx1), got ${m.shape}")
      val flat = m.flatten
      var sumSq = frac.zero
      var i = 0
      while i < flat.length do
        sumSq = frac.plus(sumSq, frac.times(flat(i), flat(i)))
        i += 1
      summon[ClassTag[T]].runtimeClass match
        case c if c == classOf[Double]     => math.sqrt(frac.toDouble(sumSq)).asInstanceOf[T]
        case c if c == classOf[Float]      => math.sqrt(frac.toDouble(sumSq)).toFloat.asInstanceOf[T]
        case c if c == classOf[BigDecimal] => sumSq.asInstanceOf[Big].sqrt.asInstanceOf[T]
        case c => throw UnsupportedOperationException(s"norm unsupported for ${c.getName}")
    }

    // ---- LU Decomposition (internal) -----------------------------------
    // Returns (LU flat Mat, pivot indices, swap count)
    private def luDecompose(using frac: Fractional[T]): (Mat[T], Array[Int], Int) = {
      require(m.rows == m.cols, s"LU requires square matrix, got ${m.shape}")
      val n = m.rows
      // Materialize to fresh flat array respecting transposed flag
      val lu = Array.ofDim[T](n * n)
      var i = 0
      while i < n do
        var j = 0
        while j < n do { lu(i * n + j) = m(i, j); j += 1 }
        i += 1
      val pivots = Array.range(0, n)
      var swaps  = 0

      i = 0
      while i < n do
        // Find pivot: largest absolute value in column i at or below row i
        var maxRow = i
        var maxAbs = math.abs(frac.toDouble(lu(i * n + i)))
        var k = i + 1
        while k < n do
          val v = math.abs(frac.toDouble(lu(k * n + i)))
          if v > maxAbs then { maxAbs = v; maxRow = k }
          k += 1
        // Swap rows
        if maxRow != i then
          var c = 0
          while c < n do
            val tmp = lu(i * n + c); lu(i * n + c) = lu(maxRow * n + c); lu(maxRow * n + c) = tmp
            c += 1
          val tp = pivots(i); pivots(i) = pivots(maxRow); pivots(maxRow) = tp
          swaps += 1
        val pivot = lu(i * n + i)
        if math.abs(frac.toDouble(pivot)) == 0.0 then
          throw ArithmeticException("Matrix is singular or nearly singular")
        // Eliminate below pivot
        var r = i + 1
        while r < n do
          val factor = frac.div(lu(r * n + i), pivot)
          lu(r * n + i) = factor
          var c = i + 1
          while c < n do
            lu(r * n + c) = frac.minus(lu(r * n + c), frac.times(factor, lu(i * n + c)))
            c += 1
          r += 1
        i += 1
      (Mat.create(lu, n, n), pivots, swaps)
    }

    // ---- Determinant ---------------------------------------------------
    def determinant(using frac: Fractional[T]): T = {
      require(m.rows == m.cols, s"determinant requires square matrix, got ${m.shape}")
      val (lu, _, swaps) = luDecompose
      val n = m.rows
      var det = if swaps % 2 == 0 then frac.one else frac.negate(frac.one)
      var i = 0
      while i < n do
        det = frac.times(det, lu(i, i))
        i += 1
      det
    }

    // ---- Inverse (LU-based) --------------------------------------------
    def inverse(using frac: Fractional[T]): Mat[T] = {
      require(m.rows == m.cols, s"inverse requires square matrix, got ${m.shape}")
      val n = m.rows
      val (lu, pivots, _) = luDecompose
      val result = Array.ofDim[T](n * n)
      var col = 0
      while col < n do
        // Permuted RHS for this column
        val x = Array.ofDim[T](n)
        var i = 0
        while i < n do { x(i) = if pivots(i) == col then frac.one else frac.zero; i += 1 }
        // Forward substitution: Lx = b (L has 1s on diagonal, stored below)
        i = 1
        while i < n do
          var k = 0
          while k < i do { x(i) = frac.minus(x(i), frac.times(lu(i, k), x(k))); k += 1 }
          i += 1
        // Backward substitution: Ux = y
        i = n - 1
        while i >= 0 do
          var k = i + 1
          while k < n do { x(i) = frac.minus(x(i), frac.times(lu(i, k), x(k))); k += 1 }
          x(i) = frac.div(x(i), lu(i, i))
          i -= 1
        // Write column into result
        var row = 0
        while row < n do { result(row * n + col) = x(row); row += 1 }
        col += 1
      Mat.create(result, n, n)
    }

    // ---- QR Decomposition (Householder reflections) -------------------
    // Returns (Q: rowsxp, R: pxcols) where m = Q * R, p = min(rows,cols)
    def qrDecomposition(using frac: Fractional[T]): (Mat[T], Mat[T]) = {
      val nRows = m.rows
      val nCols = m.cols
      val p     = math.min(nRows, nCols)

      // Work on a flat mutable copy of m (respects transposed flag)
      val R = Array.ofDim[T](nRows * nCols)
      var i = 0
      while i < nRows do
        var j = 0
        while j < nCols do
          R(i * nCols + j) = m(i, j)
          j += 1
        i += 1

      // Q accumulated as nRowsxnRows identity, updated by each reflector
      val Q = Array.ofDim[T](nRows * nRows)
      i = 0
      while i < nRows do
        Q(i * nRows + i) = frac.one
        i += 1

      // Helper: dot product of two subarrays starting at offset, length len
      inline def dot(a: Array[T], aOff: Int, b: Array[T], bOff: Int, len: Int): T =
        var s = frac.zero
        var k = 0
        while k < len do
          s = frac.plus(s, frac.times(a(aOff + k), b(bOff + k)))
          k += 1
        s

      var col = 0
      while col < p do
        val len = nRows - col  // length of the subcolumn

        // Extract subcolumn col of R below diagonal into v
        val v = Array.ofDim[T](len)
        i = 0
        while i < len do
          v(i) = R((col + i) * nCols + col)
          i += 1

        // Compute norm of v
        val normSq = dot(v, 0, v, 0, len)
        val normV: T = summon[ClassTag[T]].runtimeClass match
          case c if c == classOf[Double] =>
            math.sqrt(frac.toDouble(normSq)).asInstanceOf[T]
          case c if c == classOf[Float] =>
            math.sqrt(frac.toDouble(normSq)).toFloat.asInstanceOf[T]
          case c if c == classOf[BigDecimal] =>
            normSq.asInstanceOf[Big].sqrt.asInstanceOf[T]
          case c => throw UnsupportedOperationException(s"qr unsupported for ${c.getName}")

        if frac.toDouble(normV) != 0.0 then
          // v[0] += sign(v[0]) * norm(v)  to avoid cancellation
          val sign = if frac.toDouble(v(0)) >= 0.0 then frac.one else frac.negate(frac.one)
          v(0) = frac.plus(v(0), frac.times(sign, normV))

          // tau = 2 / (v^T v)
          val vTv = dot(v, 0, v, 0, len)
          val tau = frac.div(frac.fromInt(2), vTv)

          // Apply reflector to R: R = (I - tau*v*v^T) * R
          // Only affects rows col..nRows, cols col..nCols
          var j = col
          while j < nCols do
            // w = v^T * R[col:, j]
            var w = frac.zero
            i = 0
            while i < len do
              w = frac.plus(w, frac.times(v(i), R((col + i) * nCols + j)))
              i += 1
            // R[col+i, j] -= tau * v[i] * w
            i = 0
            while i < len do
              R((col + i) * nCols + j) = frac.minus(
                R((col + i) * nCols + j),
                frac.times(tau, frac.times(v(i), w))
              )
              i += 1
            j += 1

          // Apply reflector to Q: Q = Q * (I - tau*v*v^T)
          // For each ROW of Q, compute row dot v, then update
          var qRow = 0
          while qRow < nRows do
            // w = Q[qRow, col:] dot v
            var w = frac.zero
            i = 0
            while i < len do
              w = frac.plus(w, frac.times(Q(qRow * nRows + col + i), v(i)))
              i += 1
            // Q[qRow, col+i] -= tau * w * v[i]
            i = 0
            while i < len do
              Q(qRow * nRows + col + i) = frac.minus(
                Q(qRow * nRows + col + i),
                frac.times(tau, frac.times(w, v(i)))
              )
              i += 1
            qRow += 1

        col += 1

      // Zero out below-diagonal entries of R (numerical noise)
      i = 1
      while i < nRows do
        var j = 0
        while j < math.min(i, nCols) do
          R(i * nCols + j) = frac.zero
          j += 1
        i += 1

      // Return economy QR: Q is nRowsxp, R is pxnCols
      // Q from accumulation is nRowsxnRows - take first p columns
      val Qout = Array.ofDim[T](nRows * p)
      i = 0
      while i < nRows do
        var j = 0
        while j < p do
          Qout(i * p + j) = Q(i * nRows + j)
          j += 1
        i += 1

        // Slice R to pxnCols (drop rows p..nRows which are zero)
      val Rout = Array.ofDim[T](p * nCols)
        i = 0
        while i < p do
          var j = 0
          while j < nCols do
            Rout(i * nCols + j) = R(i * nCols + j)
            j += 1
          i += 1

      (Mat.create(Qout, nRows, p), Mat.create(Rout, p, nCols))
    }

    // ---- Eigenvalues (QR iteration) ------------------------------------
    // Suitable for symmetric matrices; converges to real eigenvalues
    def eigenvalues(iterations: Int = 500)(using frac: Fractional[T]): Array[T] = {
      require(m.rows == m.cols, s"eigenvalues requires square matrix, got ${m.shape}")
      var A: Mat[T] = m.matCopy

      // Can't use inline * here since T is abstract - dispatch via ClassTag
      def multiply(a: Mat[T], b: Mat[T]): Mat[T] =
        summon[ClassTag[T]].runtimeClass match
          case c if c == classOf[Double] =>
            a.asInstanceOf[Mat[Double]]
             .multiplyDouble(b.asInstanceOf[Mat[Double]])
             .asInstanceOf[Mat[T]]
          case c if c == classOf[Float] =>
            a.asInstanceOf[Mat[Float]]
             .multiplyFloat(b.asInstanceOf[Mat[Float]])
             .asInstanceOf[Mat[T]]
          case _ =>
            a.asInstanceOf[Mat[Big]]
             .multiplyBig(b.asInstanceOf[Mat[Big]])
             .asInstanceOf[Mat[T]]

      var iter = 0
      while iter < iterations do
        val (q, r) = A.qrDecomposition
        A = multiply(r, q)
        iter += 1
      A.diagonal
    }

    // ---- clone ---------------------------------------------------------
    def cloneMat: Mat[T] = {
      val src = m.tdata
      val dst = new Array[T](src.length)
      System.arraycopy(src, 0, dst, 0, src.length)
      Mat.create(dst, m.rows, m.cols, m.transposed)
    }

    // ---- Pure-JVM multiply (parallel tiled) ----------------------------
    private[data] def multiplyDouble(other: Mat[Double]): Mat[Double] = {
      //val a = m.tdata.asInstanceOf[Array[Double]]
      //val b = other.data.asInstanceOf[Array[Double]]
      val rowsA = m.rows; val colsA = m.cols; val colsB = other.cols
      val result = Array.ofDim[Double](rowsA * colsB)
      val TILE = 32

      val dataA = m.tdata.asInstanceOf[Array[Double]]
      val dataB = other.data.asInstanceOf[Array[Double]]
      // Extract strides and offsets once to keep the inner loop fast
      val (rsA, csA, offA) = (m.rs, m.cs, m.offset)
      val (rsB, csB, offB) = (other.rs, other.cs, other.offset)

      // Stride-aware manual accessor
      inline def getA(r: Int, c: Int): Double = dataA(offA + r * rsA + c * csA)
      inline def getB(r: Int, c: Int): Double = dataB(offB + r * rsB + c * csB)

// Then use getA(i, k) inside the loops

      java.util.stream.IntStream.range(0, (rowsA + TILE - 1) / TILE).parallel().forEach { tileI =>
        val iStart = tileI * TILE; val iEnd = math.min(iStart + TILE, rowsA)
        var tileK = 0
        while tileK < (colsA + TILE - 1) / TILE do
          val kStart = tileK * TILE; val kEnd = math.min(kStart + TILE, colsA)
          var tileJ = 0
          while tileJ < (colsB + TILE - 1) / TILE do
            val jStart = tileJ * TILE; val jEnd = math.min(jStart + TILE, colsB)
            var i = iStart
            while i < iEnd do
              var k = kStart
              while k < kEnd do
                val aVal = getA(i, k)
                var j = jStart
                while j < jEnd do { result(i * colsB + j) += aVal * getB(k, j); j += 1 }
                k += 1
              i += 1
            tileJ += 1
          tileK += 1
      }
      Mat.create(result, rowsA, colsB)
    }

    private[data] def multiplyFloat(other: Mat[Float]): Mat[Float] = {
      //val a = m.tdata.asInstanceOf[Array[Float]]
      //val b = other.data.asInstanceOf[Array[Float]]
      val rowsA = m.rows; val colsA = m.cols; val colsB = other.cols
      val result = Array.ofDim[Float](rowsA * colsB)
      val TILE = 32
      // Use the matrix accessors directly. They already handle transposition,
      // offsets, and strides correctly!
      val aAt: (Int, Int) => Float = (i, k) => m.apply(i, k).asInstanceOf[Float]
      val bAt: (Int, Int) => Float = (k, j) => other.apply(k, j).asInstanceOf[Float]

      java.util.stream.IntStream.range(0, (rowsA + TILE - 1) / TILE).parallel().forEach { tileI =>
        val iStart = tileI * TILE; val iEnd = math.min(iStart + TILE, rowsA)
        var tileK = 0
        while tileK < (colsA + TILE - 1) / TILE do
          val kStart = tileK * TILE; val kEnd = math.min(kStart + TILE, colsA)
          var tileJ = 0
          while tileJ < (colsB + TILE - 1) / TILE do
            val jStart = tileJ * TILE; val jEnd = math.min(jStart + TILE, colsB)
            var i = iStart
            while i < iEnd do
              var k = kStart
              while k < kEnd do
                val aVal = aAt(i, k)
                var j = jStart
                while j < jEnd do { result(i * colsB + j) += aVal * bAt(k, j); j += 1 }
                k += 1
              i += 1
            tileJ += 1
          tileK += 1
      }
      Mat.create(result, rowsA, colsB)
    }

    private[data] def multiplyBig(other: Mat[Big]): Mat[Big] = {
      val a = m.tdata.asInstanceOf[Array[BigDecimal]]
      val b = other.data.asInstanceOf[Array[BigDecimal]]
      val rowsA = m.rows; val colsA = m.cols; val colsB = other.cols
      val result = Array.ofDim[BigDecimal](rowsA * colsB)
      val aAt = if !m.transposed then (i: Int, k: Int) => a(i * colsA + k)
                else (i: Int, k: Int) => a(k * rowsA + i)
      val bAt = if !other.transposed then (k: Int, j: Int) => b(k * colsB + j)
                else (k: Int, j: Int) => b(j * other.rows + k)
      var i = 0
      while i < rowsA do
        var j = 0
        while j < colsB do
          var sum = BigDecimal(0)
          var k = 0
          while k < colsA do { sum = sum + (aAt(i, k) * bAt(k, j)); k += 1 }
          result(i * colsB + j) = sum
          j += 1
        i += 1
      Mat.create(result.asInstanceOf[Array[Big]], rowsA, colsB)
    }

    private inline def shouldUseBLAS[A](a: Mat[A], b: Mat[A]): Boolean =
      a.rows.toLong * a.cols * b.cols >= blasThreshold

    private[data] def multiplyDoubleBLAS(other: Mat[Double]): Mat[Double] = {
      import org.bytedeco.openblas.global.openblas.*
      import org.bytedeco.javacpp.*
      val a = m.tdata.asInstanceOf[Array[Double]]
      val b = other.data.asInstanceOf[Array[Double]]
      val rowsA = m.rows; val colsA = m.cols; val colsB = other.cols
      val result = new Array[Double](rowsA * colsB)
      val transA = if m.transposed then CblasTrans else CblasNoTrans
      val transB = if other.transposed then CblasTrans else CblasNoTrans
      val ldA = if m.transposed then m.rows else m.cols
      val ldB = if other.transposed then other.rows else other.cols
      val pA = new DoublePointer(a*); val pB = new DoublePointer(b*)
      val pC = new DoublePointer(result.length.toLong)
      cblas_dgemm(CblasRowMajor, transA, transB, rowsA, colsB, colsA,
        1.0, pA, ldA, pB, ldB, 0.0, pC, colsB)
      pC.get(result); pA.close(); pB.close(); pC.close()
      Mat.create(result, rowsA, colsB)
    }

    private[data] def multiplyFloatBLAS(other: Mat[Float]): Mat[Float] = {
      import org.bytedeco.openblas.global.openblas.*
      import org.bytedeco.javacpp.*
      val a = m.tdata.asInstanceOf[Array[Float]]
      val b = other.data.asInstanceOf[Array[Float]]
      val rowsA = m.rows; val colsA = m.cols; val colsB = other.cols
      val result = new Array[Float](rowsA * colsB)
      val transA = if m.transposed then CblasTrans else CblasNoTrans
      val transB = if other.transposed then CblasTrans else CblasNoTrans
      val ldA = if m.transposed then m.rows else m.cols
      val ldB = if other.transposed then other.rows else other.cols
      val pA = new FloatPointer(a*); val pB = new FloatPointer(b*)
      val pC = new FloatPointer(result.length.toLong)
      cblas_sgemm(CblasRowMajor, transA, transB, rowsA, colsB, colsA,
        1.0f, pA, ldA, pB, ldB, 0.0f, pC, colsB)
      pC.get(result); pA.close(); pB.close(); pC.close()
      Mat.create(result, rowsA, colsB)
    }

    def trace(using num: Numeric[T]): T = diagonal.foldLeft(num.zero)(num.plus)

    def allclose(other: Mat[T], rtol: Double = 1e-5, atol: Double = 1e-8)(using frac: Fractional[T]): Boolean = {
      if m.rows != other.rows || m.cols != other.cols then
        false
      else
        var i = 0
        var identical = true
        while i < m.rows && identical do
          var j = 0
          while j < m.cols && identical do
            val a = frac.toDouble(m(i, j))
            val b = frac.toDouble(other(i, j))
            if math.abs(a - b) > atol + rtol * math.abs(b) then
              identical = false
            j += 1
          i += 1
        identical
    }

    /** NumPy: np.sum(m, axis=0) → row vector of column sums
     *         np.sum(m, axis=1) → column vector of row sums */
    def sum(axis: Int)(using num: Numeric[T]): Mat[T] = {
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      if axis == 0 then
        // Sum down rows → result is 1xcols
        val result = Array.fill(m.cols)(num.zero)
        var i = 0
        while i < m.rows do
          var j = 0
          while j < m.cols do
            result(j) = num.plus(result(j), m(i, j))
            j += 1
          i += 1
        Mat.create(result, 1, m.cols)
      else
        // Sum across cols → result is rowsx1
        val result = Array.fill(m.rows)(num.zero)
        var i = 0
        while i < m.rows do
          var j = 0
          while j < m.cols do
            result(i) = num.plus(result(i), m(i, j))
            j += 1
          i += 1
        Mat.create(result, m.rows, 1)
    }

    def mean(using frac: Fractional[T]): T = {
      if (m.rows == 0 || m.cols == 0) then
        frac.zero
      else
        // Use the explicit nested loop logic here to avoid extension method resolution issues
        var total = frac.zero
        var r = 0
        while (r < m.rows) {
          var c = 0
          while (c < m.cols) {
            total = frac.plus(total, m(r, c))
            c += 1
          }
          r += 1
        }
        frac.div(total, frac.fromInt(m.rows * m.cols))
    }

    def mean(axis: Int)(using frac: Fractional[T]): Mat[T] = {
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      val s = m.sum(axis)
      val n = if axis == 0 then m.rows else m.cols
      s / frac.fromInt(n)
    }

    def max(axis: Int)(using ord: Ordering[T]): Mat[T] = {
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      if axis == 0 then
        // Max down rows → 1xcols
        val result = Array.tabulate(m.cols)(j => m(0, j))
        var i = 1
        while i < m.rows do
          var j = 0
          while j < m.cols do
            if ord.gt(m(i, j), result(j)) then result(j) = m(i, j)
            j += 1
          i += 1
        Mat.create(result, 1, m.cols)
      else
        // Max across cols → rowsx1
        val result = Array.tabulate(m.rows)(i => m(i, 0))
        var i = 0
        while i < m.rows do
          var j = 1
          while j < m.cols do
            if ord.gt(m(i, j), result(i)) then result(i) = m(i, j)
            j += 1
          i += 1
        Mat.create(result, m.rows, 1)
    }

    def min(axis: Int)(using ord: Ordering[T]): Mat[T] = {
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      if axis == 0 then
        val result = Array.tabulate(m.cols)(j => m(0, j))
        var i = 1
        while i < m.rows do
          var j = 0
          while j < m.cols do
            if ord.lt(m(i, j), result(j)) then result(j) = m(i, j)
            j += 1
          i += 1
        Mat.create(result, 1, m.cols)
      else
        val result = Array.tabulate(m.rows)(i => m(i, 0))
        var i = 0
        while i < m.rows do
          var j = 1
          while j < m.cols do
            if ord.lt(m(i, j), result(i)) then result(i) = m(i, j)
            j += 1
          i += 1
        Mat.create(result, m.rows, 1)
    }

    def abs(using frac: Fractional[T]): Mat[T] =
      m.map((x: T) => if frac.lt(x, frac.zero) then frac.negate(x) else x)

    def sqrt(using frac: Fractional[T]): Mat[T] =
      summon[ClassTag[T]].runtimeClass match
        case c if c == classOf[Double] =>
          m.map((x: T) => math.sqrt(frac.toDouble(x)).asInstanceOf[T])
        case c if c == classOf[Float] =>
          m.map((x: T) => math.sqrt(frac.toDouble(x)).toFloat.asInstanceOf[T])
        case c if c == classOf[BigDecimal] =>
          m.map((x: T) => x.asInstanceOf[Big].sqrt.asInstanceOf[T])
        case c => throw UnsupportedOperationException(s"sqrt unsupported for ${c.getName}")

    def exp(using frac: Fractional[T]): Mat[T] =
      m.map((x: T) => math.exp(frac.toDouble(x)).asInstanceOf[T])

    def log(using frac: Fractional[T]): Mat[T] =
      m.map((x: T) => math.log(frac.toDouble(x)).asInstanceOf[T])

    def clip(lower: T, upper: T)(using ord: Ordering[T]): Mat[T] =
      m.map((x: T) => if ord.lt(x, lower) then lower else if ord.gt(x, upper) then upper else x)

    def outer(other: Vec[T])(using num: Numeric[T]): Mat[T] = {
      require(m.size > 0 && other.size > 0, "outer requires non-empty vectors")
      val a = m.flatten
      val b = other.flatten
      val result = Array.ofDim[T](a.length * b.length)
      var i = 0
      while i < a.length do
        var j = 0
        while j < b.length do
          result(i * b.length + j) = num.times(a(i), b(j))
          j += 1
        i += 1
      Mat.create(result, a.length, b.length)
    }

    /** NumPy: np.linalg.solve(A, b) - solve Ax = b for x */
    def solve(bVec: Mat[T])(using frac: Fractional[T]): Mat[T] = {
      require(m.rows == m.cols, s"solve requires square matrix, got ${m.shape}")
      val bCol = if (bVec.rows == 1 && bVec.cols == m.rows) bVec.T else bVec
      require(bCol.rows == m.rows, s"bCol.rows ${bCol.rows} must match matrix rows ${m.rows}")
      val n = m.rows
      val nRhs = bCol.cols
      val (lu, pivots, _) = luDecompose
      val result = Array.ofDim[T](n * nRhs)

      var col = 0
      while col < nRhs do
        val x = Array.tabulate(n)(i => bCol(pivots(i), col))
        // Forward substitution
        var i = 1
        while i < n do
          var k = 0
          while k < i do
            x(i) = frac.minus(x(i), frac.times(lu(i, k), x(k)))
            k += 1
          i += 1
        // Backward substitution
        i = n - 1
        while i >= 0 do
          var k = i + 1
          while k < n do
            x(i) = frac.minus(x(i), frac.times(lu(i, k), x(k)))
            k += 1
          x(i) = frac.div(x(i), lu(i, i))
          i -= 1
        var row = 0
        while row < n do
          result(row * nRhs + col) = x(row)
          row += 1
        col += 1
      Mat.create(result, n, nRhs)
    }

    def cumsum(axis: Int)(using num: Numeric[T]): Mat[T] = {
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      val result = Array.ofDim[T](m.rows * m.cols)
      if axis == 0 then
        // Cumulative sum down rows for each column
        var j = 0
        while j < m.cols do
          var acc = num.zero
          var i = 0
          while i < m.rows do
            acc = num.plus(acc, m(i, j))
            result(i * m.cols + j) = acc
            i += 1
          j += 1
      else
        // Cumulative sum across cols for each row
        var i = 0
        while i < m.rows do
          var acc = num.zero
          var j = 0
          while j < m.cols do
            acc = num.plus(acc, m(i, j))
            result(i * m.cols + j) = acc
            j += 1
          i += 1
      Mat.create(result, m.rows, m.cols)
    }

    // No-axis version: flatten then cumsum
    def cumsum(using num: Numeric[T]): Mat[T] = {
      val flat = m.flatten
      val result = Array.ofDim[T](flat.length)
      var acc = num.zero
      var i = 0
      while i < flat.length do
        acc = num.plus(acc, flat(i))
        result(i) = acc
        i += 1
      Mat.create(result, 1, flat.length)
    }

    // 2. cov and corrcoef:
    // scala// NumPy: np.cov(m) - each ROW is a variable, each COL is an observation
    // Returns pxp covariance matrix where p = number of rows
    def cov(using frac: Fractional[T]): Mat[T] = {
      val p = m.rows  // number of variables
      val n = m.cols  // number of observations
      require(n > 1, "cov requires at least 2 observations (cols)")

      // Subtract row means
      val means = m.mean(1)  // px1
      val centered = Array.ofDim[T](p * n)
      var i = 0
      while i < p do
        var j = 0
        while j < n do
          centered(i * n + j) = frac.minus(m(i, j), means(i, 0))
          j += 1
        i += 1

      // cov = C * C^T / (n-1)
      val denom = frac.fromInt(n - 1)
      val result = Array.ofDim[T](p * p)
      i = 0
      while i < p do
        var j = 0
        while j < p do
          var s = frac.zero
          var k = 0
          while k < n do
            s = frac.plus(s, frac.times(centered(i * n + k), centered(j * n + k)))
            k += 1
          result(i * p + j) = frac.div(s, denom)
          j += 1
        i += 1
      Mat.create(result, p, p)
    }

    def corrcoef(using frac: Fractional[T]): Mat[T] = {
      val c = m.cov
      val p = c.rows
      // stddevs = sqrt of diagonal of cov
      val std = Array.ofDim[T](p)
      var i = 0
      while i < p do
        std(i) = summon[ClassTag[T]].runtimeClass match
          case c2 if c2 == classOf[Double] =>
            math.sqrt(frac.toDouble(c(i, i))).asInstanceOf[T]
          case c2 if c2 == classOf[Float] =>
            math.sqrt(frac.toDouble(c(i, i))).toFloat.asInstanceOf[T]
          case c2 if c2 == classOf[BigDecimal] =>
            c(i, i).asInstanceOf[Big].sqrt.asInstanceOf[T]
          case c2 => throw UnsupportedOperationException(s"corrcoef unsupported for ${c2.getName}")
        i += 1
      val result = Array.ofDim[T](p * p)
      i = 0
      while i < p do
        var j = 0
        while j < p do
          result(i * p + j) = frac.div(c(i, j), frac.times(std(i), std(j)))
          j += 1
        i += 1
      Mat.create(result, p, p)
    }

    // 3. sort and argsort:
    def sort(axis: Int = -1)(using ord: Ordering[T]): Mat[T] = {
      if axis == -1 then
        // Sort flattened
        val flat = m.flatten
        val sorted = flat.sorted
        Mat.create(sorted, 1, flat.length)
      else
        require(axis == 0 || axis == 1, s"axis must be -1, 0 or 1, got $axis")
        val result = Array.ofDim[T](m.rows * m.cols)
        if axis == 0 then
          // Sort each column independently
          var j = 0
          while j < m.cols do
            val col = Array.tabulate(m.rows)(i => m(i, j))
            val sorted = col.sorted
            var i = 0
            while i < m.rows do
              result(i * m.cols + j) = sorted(i)
              i += 1
            j += 1
        else
          // Sort each row independently
          var i = 0
          while i < m.rows do
            val row = Array.tabulate(m.cols)(j => m(i, j))
            val sorted = row.sorted
            var j = 0
            while j < m.cols do
              result(i * m.cols + j) = sorted(j)
              j += 1
            i += 1
        Mat.create(result, m.rows, m.cols)
    }

    def argsort(axis: Int = -1)(using ord: Ordering[T]): Mat[Int] = {
      if axis == -1 then
        val flat = m.flatten
        val indices = flat.indices.sortBy(flat(_)).toArray
        Mat.create(indices, 1, indices.length)
      else
        require(axis == 0 || axis == 1, s"axis must be -1, 0 or 1, got $axis")
        val result = Array.ofDim[Int](m.rows * m.cols)
        if axis == 0 then
          var j = 0
          while j < m.cols do
            val col = Array.tabulate(m.rows)(i => m(i, j))
            val sorted = col.indices.sortBy(col(_)).toArray
            var i = 0
            while i < m.rows do
              result(i * m.cols + j) = sorted(i)
              i += 1
            j += 1
        else
          var i = 0
          while i < m.rows do
            val row = Array.tabulate(m.cols)(j => m(i, j))
            val sorted = row.indices.sortBy(row(_)).toArray
            var j = 0
            while j < m.cols do
              result(i * m.cols + j) = sorted(j)
              j += 1
            i += 1
        Mat.create(result, m.rows, m.cols)
    }

    // 4. unique:
    def unique(using ord: Ordering[T]): (Array[T], Array[Int]) = {
      val flat = m.flatten
      val sorted = flat.sorted
      val vals = scala.collection.mutable.ArrayBuffer[T]()
      val counts = scala.collection.mutable.ArrayBuffer[Int]()
      var i = 0
      while i < sorted.length do
        if vals.isEmpty || ord.compare(sorted(i), vals.last) != 0 then
          vals += sorted(i)
          counts += 1
        else
          counts(counts.length - 1) += 1
        i += 1
      (vals.toArray, counts.toArray)
    }
    private[data] def svdDouble: (Mat[Double], Array[Double], Mat[Double]) = {
      import org.bytedeco.openblas.global.openblas.*
      val md    = m.asInstanceOf[Mat[Double]]
      val nRows = md.rows
      val nCols = md.cols
      val p     = math.min(nRows, nCols)
      val aCopy = md.flatten  // row-major, respects transposed flag
      val s     = Array.ofDim[Double](p)
      val u     = Array.ofDim[Double](nRows * nRows)
      val vt    = Array.ofDim[Double](nCols * nCols)

      val info = LAPACKE_dgesdd(
        LAPACK_ROW_MAJOR, 'A'.toByte,
        nRows, nCols,
        aCopy, nCols,   // lda = nCols for row-major
        s,
        u,  nRows,      // ldu
        vt, nCols       // ldvt
      )
      if info != 0 then
        throw ArithmeticException(s"LAPACKE_dgesdd failed with info=$info")

      (Mat.create(u, nRows, nRows), s, Mat.create(vt, nCols, nCols))
    }

    def svd(using frac: Fractional[T]): (Mat[T], Array[T], Mat[T]) =
      summon[ClassTag[T]].runtimeClass match
        case c if c == classOf[Double] =>
          val (u, s, vt) = m.asInstanceOf[Mat[Double]].svdDouble
          (u.asInstanceOf[Mat[T]], s.asInstanceOf[Array[T]], vt.asInstanceOf[Mat[T]])
        case c =>
          throw UnsupportedOperationException(s"svd only supported for Double, got ${c.getName}")

    def lstsq(b: Mat[T])(using frac: Fractional[T]): (Mat[T], Mat[T], Int, Array[T]) = {
      summon[ClassTag[T]].runtimeClass match
        case c if c == classOf[Double] =>
          val md   = m.asInstanceOf[Mat[Double]]
          val bd   = b.asInstanceOf[Mat[Double]]
          val nRows = md.rows
          val nCols = md.cols
          val nRhs  = bd.cols
          val p     = math.min(nRows, nCols)

          val (uMat, s, vtMat) = md.svdDouble
          // Extract flat row-major arrays from the Mat results
          // uMat  is nRowsxnRows, row-major: uMat.underlying(r*nRows + c) = U[r,c]
          // vtMat is nColsxnCols, row-major: vtMat.underlying(r*nCols + c) = Vt[r,c]
          val u  = uMat.underlying   // Array[Double], nRows*nRows
          val vt = vtMat.underlying  // Array[Double], nCols*nCols

          // Rank = number of singular values above threshold
          val threshold = 1e-10 * s(0)
          val rank = s.count(_ > threshold)

          val result = Array.ofDim[Double](nCols * nRhs)

          var col = 0
          while col < nRhs do
            // Step 1: tmp = U^T * b[:,col]
            // U^T[i,k] = U[k,i] = u(k*nRows + i)
            // tmp[i] = sum_k U[k,i] * b[k,col]
            val tmp = Array.ofDim[Double](nRows)
            var i = 0
            while i < nRows do
              var k = 0
              while k < nRows do
                tmp(i) += u(k * nRows + i) * bd(k, col)
                k += 1
              i += 1

            // Step 2: apply S^+  (pseudo-inverse of diagonal)
            // tmp[i] /= s[i] for i < rank, zero otherwise
            i = 0
            while i < p do
              if i < rank then tmp(i) /= s(i)
              else tmp(i) = 0.0
              i += 1

            // Step 3: x = V * tmp  (V = Vt^T)
            // V[i,k] = Vt[k,i] = vt(k*nCols + i)
            // result[i,col] = sum_k Vt[k,i] * tmp[k]
            i = 0
            while i < nCols do
              var k = 0
              while k < p do
                result(i * nRhs + col) += vt(k * nCols + i) * tmp(k)
                k += 1
              i += 1
            col += 1

          // Residuals: ||A*x - b||^2 per RHS column, only meaningful if nRows > nCols
          val residuals = Array.ofDim[Double](nRhs)
          if nRows > nCols then
            val xMat = Mat.create(result, nCols, nRhs)
            val diff = md @@ xMat - bd
            var c2 = 0
            while c2 < nRhs do
              var i = 0
              while i < nRows do
                val v = diff(i, c2)
                residuals(c2) += v * v
                i += 1
              c2 += 1

          (
            Mat.create(result, nCols, nRhs).asInstanceOf[Mat[T]],
            Mat.create(residuals, 1, nRhs).asInstanceOf[Mat[T]],
            rank,
            s.asInstanceOf[Array[T]]
          )

        case c =>
          throw UnsupportedOperationException(s"lstsq only supported for Double, got ${c.getName}")
    }

    // ---- Element-wise comparison → Mat[Boolean] ----------------------------
    def gt(other: T)(using ord: Ordering[T]): Mat[Boolean] =
      Mat.create(m.tdata.map(ord.gt(_, other)), m.rows, m.cols, m.transposed)

    def lt(other: T)(using ord: Ordering[T]): Mat[Boolean] =
      Mat.create(m.tdata.map(ord.lt(_, other)), m.rows, m.cols, m.transposed)

    def gte(other: T)(using ord: Ordering[T]): Mat[Boolean] =
      Mat.create(m.tdata.map(ord.gteq(_, other)), m.rows, m.cols, m.transposed)

    def lte(other: T)(using ord: Ordering[T]): Mat[Boolean] =
      Mat.create(m.tdata.map(ord.lteq(_, other)), m.rows, m.cols, m.transposed)

    def :==(other: T)(using ord: Ordering[T]): Mat[Boolean] =
      Mat.create(m.tdata.map(ord.equiv(_, other)), m.rows, m.cols, m.transposed)

    def :!=(other: T)(using ord: Ordering[T]): Mat[Boolean] =
      Mat.create(m.tdata.map(!ord.equiv(_, other)), m.rows, m.cols, m.transposed)

    // Int overloads for natural NumPy-style usage e.g. m.gt(0)
    def gt(other: Int)(using ord: Ordering[T], frac: Fractional[T]): Mat[Boolean] =
      m.gt(frac.fromInt(other))

    def lt(other: Int)(using ord: Ordering[T], frac: Fractional[T]): Mat[Boolean] =
      m.lt(frac.fromInt(other))

    def gte(other: Int)(using ord: Ordering[T], frac: Fractional[T]): Mat[Boolean] =
      m.gte(frac.fromInt(other))

    def lte(other: Int)(using ord: Ordering[T], frac: Fractional[T]): Mat[Boolean] =
      m.lte(frac.fromInt(other))

    def :==(other: Int)(using ord: Ordering[T], frac: Fractional[T]): Mat[Boolean] =
      m.:==(frac.fromInt(other))

    def :!=(other: Int)(using ord: Ordering[T], frac: Fractional[T]): Mat[Boolean] =
      m.:!=(frac.fromInt(other))

    // ---- Boolean mask indexing ---------------------------------------------

    /** m(m.gt(0.0)) → flat vector of elements where mask is true */
    def apply(mask: Mat[Boolean]): Mat[T] = {
      require(mask.rows == m.rows && mask.cols == m.cols,
        s"Mask shape ${mask.shape} must match matrix shape ${m.shape}")
      val buf = scala.collection.mutable.ArrayBuffer[T]()
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          if mask(i, j) then buf += m(i, j)
          j += 1
        i += 1
      val arr = buf.toArray
      Mat.create(arr, 1, arr.length)
    }

    // ---- Fancy index: row/col selection by Array[Int] ----------------------

    /** m(Array(0,2,4), ::) → select rows by index */
    def apply(rowIndices: Array[Int], cols: ::.type): Mat[T] = {
      val nCols = m.cols
      val result = Array.ofDim[T](rowIndices.length * nCols)
      var i = 0
      while i < rowIndices.length do
        val r = rowIndices(i)
        require(r >= 0 && r < m.rows, s"Row index $r out of bounds for ${m.rows} rows")
        var j = 0
        while j < nCols do
          result(i * nCols + j) = m(r, j)
          j += 1
        i += 1
      Mat.create(result, rowIndices.length, nCols)
    }

    /** m(::, Array(0,2,4)) → select cols by index */
    def apply(rows: ::.type, colIndices: Array[Int]): Mat[T] = {
      val nRows = m.rows
      val result = Array.ofDim[T](nRows * colIndices.length)
      var i = 0
      while i < nRows do
        var j = 0
        while j < colIndices.length do
          val c = colIndices(j)
          require(c >= 0 && c < m.cols, s"Col index $c out of bounds for ${m.cols} cols")
          result(i * colIndices.length + j) = m(i, c)
          j += 1
        i += 1
      Mat.create(result, nRows, colIndices.length)
    }

    /** m(Array(0,2), Array(1,3)) → select rows and cols by index */
    def apply(rowIndices: Array[Int], colIndices: Array[Int]): Mat[T] = {
      val nRows = rowIndices.length
      val nCols = colIndices.length
      val result = Array.ofDim[T](nRows * nCols)
      var i = 0
      while i < nRows do
        val r = rowIndices(i)
        require(r >= 0 && r < m.rows, s"Row index $r out of bounds")
        var j = 0
        while j < nCols do
          val c = colIndices(j)
          require(c >= 0 && c < m.cols, s"Col index $c out of bounds")
          result(i * nCols + j) = m(r, c)
          j += 1
        i += 1
      Mat.create(result, nRows, nCols)
    }

    /** NumPy: np.repeat(m, n) - repeat each element n times, returns flat row vector */
    def repeat(n: Int): Mat[T] = {
      // result size is logical elements * n
      val result = new Array[T](m.rows * m.cols * n)
      var r = 0
      var idx = 0

      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          // Get the correct logical element
          val value = m(r, c)

          // Repeat it n times in the output
          var k = 0
          while (k < n) {
            result(idx) = value
            idx += 1
            k += 1
          }
          c += 1
        }
        r += 1
      }
      // Creates a row vector (1 x total_elements)
      Mat.create(result, 1, m.rows * m.cols * n)
    }

    /** NumPy: np.repeat(m, n, axis=0) - repeat each row n times */
    /** NumPy: np.repeat(m, n, axis=1) - repeat each col n times */
    def repeat(n: Int, axis: Int): Mat[T] = {
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      if axis == 0 then
        val result = Array.ofDim[T](m.rows * n * m.cols)
        var i = 0
        while i < m.rows do
          var k = 0
          while k < n do
            var j = 0
            while j < m.cols do
              result((i * n + k) * m.cols + j) = m(i, j)
              j += 1
            k += 1
          i += 1
        Mat.create(result, m.rows * n, m.cols)
      else
        val result = Array.ofDim[T](m.rows * m.cols * n)
        var i = 0
        while i < m.rows do
          var j = 0
          while j < m.cols do
            var k = 0
            while k < n do
              result(i * m.cols * n + j * n + k) = m(i, j)
              k += 1
            j += 1
          i += 1
        Mat.create(result, m.rows, m.cols * n)
    }

    /** NumPy: np.tile(m, (rowReps, colReps)) - tile matrix */
    def tile(rowReps: Int, colReps: Int): Mat[T] = {
      val newRows = m.rows * rowReps
      val newCols = m.cols * colReps
      val result  = Array.ofDim[T](newRows * newCols)
      var i = 0
      while i < newRows do
        var j = 0
        while j < newCols do
          result(i * newCols + j) = m(i % m.rows, j % m.cols)
          j += 1
        i += 1
      Mat.create(result, newRows, newCols)
    }

    /** NumPy: np.diff(m) - first differences of flattened matrix */
    def diff(using num: Numeric[T]): Mat[T] = {
      val flat = m.flatten
      val result = Array.ofDim[T](flat.length - 1)
      var i = 0
      while i < result.length do
        result(i) = num.minus(flat(i + 1), flat(i))
        i += 1
      Mat.create(result, 1, result.length)
    }

    /** NumPy: np.diff(m, axis=0) - first differences along axis */
    def diff(axis: Int)(using num: Numeric[T]): Mat[T] = {
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      if axis == 0 then
        require(m.rows > 1, "diff axis=0 requires at least 2 rows")
        val result = Array.ofDim[T]((m.rows - 1) * m.cols)
        var i = 0
        while i < m.rows - 1 do
          var j = 0
          while j < m.cols do
            result(i * m.cols + j) = num.minus(m(i + 1, j), m(i, j))
            j += 1
          i += 1
        Mat.create(result, m.rows - 1, m.cols)
      else
        require(m.cols > 1, "diff axis=1 requires at least 2 cols")
        val result = Array.ofDim[T](m.rows * (m.cols - 1))
        var i = 0
        while i < m.rows do
          var j = 0
          while j < m.cols - 1 do
            result(i * (m.cols - 1) + j) = num.minus(m(i, j + 1), m(i, j))
            j += 1
          i += 1
        Mat.create(result, m.rows, m.cols - 1)
    }

    // percentile and median:
    private def percentileOf(arr: Array[T], p: Double)(using frac: Fractional[T]): T = {
      require(p >= 0 && p <= 100, s"percentile must be in [0,100], got $p")  // guard here
      val sorted = arr.sorted(using summon[Ordering[T]])
      val n = sorted.length
      if n == 1 then
        sorted(0)
      else
        val idx  = (p / 100.0) * (n - 1)
        val lo   = idx.toInt
        val hi   = math.min(lo + 1, n - 1)
        val frac2 = idx - lo
        val result = frac.toDouble(sorted(lo)) + frac2 * (frac.toDouble(sorted(hi)) - frac.toDouble(sorted(lo)))
        summon[ClassTag[T]].runtimeClass match
          case c if c == classOf[Double]     => result.asInstanceOf[T]
          case c if c == classOf[Float]      => result.toFloat.asInstanceOf[T]
          case c if c == classOf[BigDecimal] => BigDecimal(result).asInstanceOf[T]
          case c => throw UnsupportedOperationException(s"percentile unsupported for ${c.getName}")
    }

    /** NumPy: np.percentile(m, p) - p-th percentile of all elements, p in [0,100] */
    def percentile(p: Double)(using frac: Fractional[T]): T =
      percentileOf(m.flatten, p)

    /** NumPy: np.median(m) - median of all elements */
    def median(using frac: Fractional[T]): T =
      percentileOf(m.flatten, 50.0)

    /** NumPy: np.percentile(m, p, axis=0/1) - percentile along axis */
    def percentile(p: Double, axis: Int)(using frac: Fractional[T]): Mat[T] = {
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      if axis == 0 then
        val result = Array.ofDim[T](m.cols)
        var j = 0
        while j < m.cols do
          result(j) = percentileOf(Array.tabulate(m.rows)(i => m(i, j)), p)
          j += 1
        Mat.create(result, 1, m.cols)
      else
        val result = Array.ofDim[T](m.rows)
        var i = 0
        while i < m.rows do
          result(i) = percentileOf(Array.tabulate(m.cols)(j => m(i, j)), p)
          i += 1
        Mat.create(result, m.rows, 1)
    }

    /** NumPy: np.median(m, axis=0/1) */
    def median(axis: Int)(using frac: Fractional[T]): Mat[T] =
      percentile(50.0, axis)

    // matrix_rank:
    /** NumPy: np.linalg.matrix_rank(m) - rank via SVD singular value threshold */
    def matrixRank(tol: Double = -1.0)(using frac: Fractional[T]): Int = {
      summon[ClassTag[T]].runtimeClass match
        case c if c == classOf[Double] =>
          val (_, s, _) = m.asInstanceOf[Mat[Double]].svdDouble
          val threshold = if tol < 0 then 1e-10 * s(0) else tol
          s.count(_ > threshold)
        case c =>
          throw UnsupportedOperationException(s"matrixRank only supported for Double, got ${c.getName}")
    }

    //5. Matrix norm overload:
    /** NumPy: np.linalg.norm(m, ord='fro') - Frobenius norm = sqrt(sum of squares)
     *         np.linalg.norm(m, ord='inf') - max absolute row sum
     *         np.linalg.norm(m, ord='1')   - max absolute col sum  */
    def norm(ord: String)(using frac: Fractional[T]): T = {
      ord match
        case "fro" =>
          var sumSq = frac.zero
            var i = 0
            while i < m.rows do
              var j = 0
              while j < m.cols do
                val x = m(i, j) // Respects offset and strides
                sumSq = frac.plus(sumSq, frac.times(x, x))
                j += 1
              i += 1
          summon[ClassTag[T]].runtimeClass match
            case c if c == classOf[Double]     => math.sqrt(frac.toDouble(sumSq)).asInstanceOf[T]
            case c if c == classOf[Float]      => math.sqrt(frac.toDouble(sumSq)).toFloat.asInstanceOf[T]
            case c if c == classOf[BigDecimal] => sumSq.asInstanceOf[Big].sqrt.asInstanceOf[T]
            case c => throw UnsupportedOperationException(s"norm unsupported for ${c.getName}")
        case "inf" =>
          // max absolute row sum
          var maxRowSum = frac.zero
          var i = 0
          while i < m.rows do
            var rowSum = frac.zero
            var j = 0
            while j < m.cols do
              rowSum = frac.plus(rowSum,
                if frac.lt(m(i,j), frac.zero) then frac.negate(m(i,j)) else m(i,j))
              j += 1
            if frac.gt(rowSum, maxRowSum) then maxRowSum = rowSum
            i += 1
          maxRowSum
        case "1" =>
          // max absolute col sum
          var maxColSum = frac.zero
          var j = 0
          while j < m.cols do
            var colSum = frac.zero
            var i = 0
            while i < m.rows do
              colSum = frac.plus(colSum,
                if frac.lt(m(i,j), frac.zero) then frac.negate(m(i,j)) else m(i,j))
              i += 1
            if frac.gt(colSum, maxColSum) then maxColSum = colSum
            j += 1
          maxColSum
        case other =>
          throw IllegalArgumentException(s"unsupported norm ord '$other', use 'fro', 'inf', or '1'")
    }
    // isnan/isinf/isfinite and nanToNum:
    // These are Double/Float specific - Boolean result for data cleaning
    /** NumPy: np.isnan(m) */
    def isnan(using frac: Fractional[T]): Mat[Boolean] =
      Mat.create(m.tdata.map(x => frac.toDouble(x).isNaN), m.rows, m.cols, m.transposed)

    /** NumPy: np.isinf(m) */
    def isinf(using frac: Fractional[T]): Mat[Boolean] =
      Mat.create(m.tdata.map(x => frac.toDouble(x).isInfinite), m.rows, m.cols, m.transposed)

    /** NumPy: np.isfinite(m) */
    def isfinite(using frac: Fractional[T]): Mat[Boolean] =
      Mat.create(m.tdata.map(x => { val d = frac.toDouble(x); !d.isNaN && !d.isInfinite }),
        m.rows, m.cols, m.transposed)

    /** NumPy: np.nan_to_num(m, nan=0.0, posinf=0.0, neginf=0.0) */
    def nanToNum(
        nan:    Double = 0.0,
        posinf: Double = 0.0,
        neginf: Double = 0.0
    )(using frac: Fractional[T]): Mat[T] = {
      m.map((x: T) => {
        val d = frac.toDouble(x)
        val replaced =
          if d.isNaN then nan
          else if d.isPosInfinity then posinf
          else if d.isNegInfinity then neginf
          else d
        summon[ClassTag[T]].runtimeClass match
          case c if c == classOf[Double]     => replaced.asInstanceOf[T]
          case c if c == classOf[Float]      => replaced.toFloat.asInstanceOf[T]
          case c if c == classOf[BigDecimal] => BigDecimal(replaced).asInstanceOf[T]
          case c => throw UnsupportedOperationException(s"nanToNum unsupported for ${c.getName}")
      })
    }

    /** Add a row vector to every row of m: np equivalent of m + v (broadcast) */
    def addToEachRow(v: RowVec[T])(using num: Numeric[T]): Mat[T] = {
      require(v.size == m.cols,
        s"row vector length ${v.size} must match matrix cols ${m.cols}")
      val vFlat = v.flatten
      val result = Array.ofDim[T](m.rows * m.cols)
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          result(i * m.cols + j) = num.plus(m(i, j), vFlat(j))
          j += 1
        i += 1
      Mat.create(result, m.rows, m.cols)
    }

    /** Add a column vector to every col of m */
    def addToEachCol(v: ColVec[T])(using num: Numeric[T]): Mat[T] = {
      require(v.size == m.rows,
        s"col vector length ${v.size} must match matrix rows ${m.rows}")
      val vFlat = v.flatten
      val result = Array.ofDim[T](m.rows * m.cols)
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          result(i * m.cols + j) = num.plus(m(i, j), vFlat(i))
          j += 1
        i += 1
      Mat.create(result, m.rows, m.cols)
    }

    /** Multiply each row of m element-wise by a row vector */
    def mulEachRow(v: RowVec[T])(using num: Numeric[T]): Mat[T] = {
      require(v.size == m.cols,
        s"row vector length ${v.size} must match matrix cols ${m.cols}")
      val vFlat = v.flatten
      val result = Array.ofDim[T](m.rows * m.cols)
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          result(i * m.cols + j) = num.times(m(i, j), vFlat(j))
          j += 1
        i += 1
      Mat.create(result, m.rows, m.cols)
    }

    /** Multiply each col of m element-wise by a column vector */
    def mulEachCol(v: ColVec[T])(using num: Numeric[T]): Mat[T] = {
      require(v.size == m.rows,
        s"col vector length ${v.size} must match matrix rows ${m.rows}")
      val vFlat = v.flatten
      val result = Array.ofDim[T](m.rows * m.cols)
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          result(i * m.cols + j) = num.times(m(i, j), vFlat(i))
          j += 1
        i += 1
      Mat.create(result, m.rows, m.cols)
    }

    /** Subtract a row vector from every row */
    def subFromEachRow(v: RowVec[T])(using num: Numeric[T]): Mat[T] = {
      require(v.size == m.cols,
        s"row vector length ${v.size} must match matrix cols ${m.cols}")
      val vFlat = v.flatten
      val result = Array.ofDim[T](m.rows * m.cols)
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          result(i * m.cols + j) = num.minus(m(i, j), vFlat(j))
          j += 1
        i += 1
      Mat.create(result, m.rows, m.cols)
    }

    /** Subtract a column vector from every col */
    def subFromEachCol(v: ColVec[T])(using num: Numeric[T]): Mat[T] = {
      require(v.size == m.rows,
        s"col vector length ${v.size} must match matrix rows ${m.rows}")
      val vFlat = v.flatten
      val result = Array.ofDim[T](m.rows * m.cols)
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          result(i * m.cols + j) = num.minus(m(i, j), vFlat(i))
          j += 1
        i += 1
      Mat.create(result, m.rows, m.cols)
    }

    /** Divide each row of m element-wise by a row vector */
    def divEachRow(v: RowVec[T])(using frac: Fractional[T]): Mat[T] = {
      require(v.size == m.cols,
        s"row vector length ${v.size} must match matrix cols ${m.cols}")
      val vFlat = v.flatten
      val result = Array.ofDim[T](m.rows * m.cols)
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          result(i * m.cols + j) = frac.div(m(i, j), vFlat(j))
          j += 1
        i += 1
      Mat.create(result, m.rows, m.cols)
    }

    /** Divide each col of m element-wise by a column vector */
    def divEachCol(v: ColVec[T])(using frac: Fractional[T]): Mat[T] = {
      require(v.size == m.rows,
        s"col vector length ${v.size} must match matrix rows ${m.rows}")
      val vFlat = v.flatten
      val result = Array.ofDim[T](m.rows * m.cols)
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          result(i * m.cols + j) = frac.div(m(i, j), vFlat(i))
          j += 1
        i += 1
      Mat.create(result, m.rows, m.cols)
    }

    /** NumPy: np.linalg.eig(m) - eigenvalues and right eigenvectors
     *  Returns (realParts, imagParts, eigenvectors) since we have no Complex type
     *  For symmetric matrices prefer eigenvalues() which is more efficient */
    private[data] def eigDouble: (Array[Double], Array[Double], Mat[Double]) = {
      import org.bytedeco.openblas.global.openblas.*
      val md    = m.asInstanceOf[Mat[Double]]
      val n     = md.rows
      require(n == md.cols, s"eig requires square matrix, got ${md.shape}")
      val aCopy = md.flatten  // row-major copy

      val wr  = Array.ofDim[Double](n)  // real parts of eigenvalues
      val wi  = Array.ofDim[Double](n)  // imaginary parts
      val vr  = Array.ofDim[Double](n * n)  // right eigenvectors

      val info = LAPACKE_dgeev(
        LAPACK_ROW_MAJOR, 'N'.toByte, 'V'.toByte,
        n,
        aCopy, n,   // A, lda
        wr, wi,     // eigenvalue real and imag parts
        null, 1,    // left eigenvectors (not computed)
        vr, n       // right eigenvectors, ldvr
      )
      if info != 0 then
        throw ArithmeticException(s"LAPACKE_dgeev failed with info=$info")

      (wr, wi, Mat.create(vr, n, n))
    }

    /*
     * returns (realParts, imagParts, eigenvectors) as separate arrays.
     */
    def eig(using frac: Fractional[T]): (Array[T], Array[T], Mat[T]) = {
      summon[ClassTag[T]].runtimeClass match
        case c if c == classOf[Double] =>
          val (wr, wi, vr) = m.asInstanceOf[Mat[Double]].eigDouble
          (wr.asInstanceOf[Array[T]], wi.asInstanceOf[Array[T]], vr.asInstanceOf[Mat[T]])
        case c =>
          throw UnsupportedOperationException(s"eig only supported for Double, got ${c.getName}")
    }

    /** NumPy: np.tril(m, k=0) - lower triangular, k=0 main diagonal,
     *  k>0 above, k<0 below */
    def tril(k: Int = 0)(using num: Numeric[T]): Mat[T] = {
      val result = Array.ofDim[T](m.rows * m.cols)
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          result(i * m.cols + j) = if j <= i + k then m(i, j) else num.zero
          j += 1
        i += 1
      Mat.create(result, m.rows, m.cols)
    }

    /** NumPy: np.triu(m, k=0) - upper triangular */
    def triu(k: Int = 0)(using num: Numeric[T]): Mat[T] = {
      val result = Array.ofDim[T](m.rows * m.cols)
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          result(i * m.cols + j) = if j >= i + k then m(i, j) else num.zero
          j += 1
        i += 1
      Mat.create(result, m.rows, m.cols)
    }

    /** NumPy: np.sign(m) - element-wise sign: -1, 0, or 1 */
    def sign(using num: Numeric[T]): Mat[T] = {
      m.map((x: T) =>
        if num.lt(x, num.zero) then num.negate(num.one)
        else if num.gt(x, num.zero) then num.one
        else num.zero
      )
    }

    /** NumPy: np.round(m, decimals=0) */
    def round(decimals: Int = 0)(using frac: Fractional[T]): Mat[T] = {
      val scale = math.pow(10.0, decimals)
      m.map((x: T) => {
        val rounded = math.round(frac.toDouble(x) * scale).toDouble / scale
        summon[ClassTag[T]].runtimeClass match
          case c if c == classOf[Double]     => rounded.asInstanceOf[T]
          case c if c == classOf[Float]      => rounded.toFloat.asInstanceOf[T]
          case c if c == classOf[BigDecimal] => BigDecimal(rounded).asInstanceOf[T]
          case c => throw UnsupportedOperationException(s"round unsupported for ${c.getName}")
      })
    }

    /** NumPy: np.power(m, n) - element-wise x^n */
    def power(n: Double)(using frac: Fractional[T]): Mat[T] =
      m.map((x: T) => {
        val result = math.pow(frac.toDouble(x), n)
        summon[ClassTag[T]].runtimeClass match
          case c if c == classOf[Double]     => result.asInstanceOf[T]
          case c if c == classOf[Float]      => result.toFloat.asInstanceOf[T]
          case c if c == classOf[BigDecimal] => BigDecimal(result).asInstanceOf[T]
          case c => throw UnsupportedOperationException(s"power unsupported for ${c.getName}")
      })

    /** Integer exponent overload - no Fractional needed */
    def power(n: Int)(using num: Numeric[T]): Mat[T] = {
      m.map((x: T) => {
        var result = num.one
        var k = 0
        while k < math.abs(n) do
          result = num.times(result, x)
          k += 1
        if n < 0 then
          throw UnsupportedOperationException("negative integer power requires Fractional")
        result
      })
    }

    // newaxis equivalents
    def toRowVec: Mat[T] = Mat.create(m.flatten, 1, m.size)
    def toColVec: Mat[T] = Mat.create(m.flatten, m.size, 1)

    // in-place scalar ops
    def :+=(scalar: T)(using num: Numeric[T]): Unit = {
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          m(i, j) = num.plus(m(i, j), scalar)
          j += 1
        i += 1
    }

    def :-=(scalar: T)(using num: Numeric[T]): Unit = {
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          m(i, j) = num.minus(m(i, j), scalar)
          j += 1
        i += 1
    }

    def :*=(scalar: T)(using num: Numeric[T]): Unit = {
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          m(i, j) = num.times(m(i, j), scalar)
          j += 1
        i += 1
    }

    def :/=(scalar: T)(using frac: Fractional[T]): Unit = {
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          m(i, j) = frac.div(m(i, j), scalar)
          j += 1
        i += 1
    }

    // Int overloads
    def :+=(scalar: Int)(using frac: Fractional[T]): Unit = m.:+=(frac.fromInt(scalar))
    def :-=(scalar: Int)(using frac: Fractional[T]): Unit = m.:-=(frac.fromInt(scalar))
    def :*=(scalar: Int)(using frac: Fractional[T]): Unit = m.:*=(frac.fromInt(scalar))
    def :/=(scalar: Int)(using frac: Fractional[T]): Unit = m.:/=(frac.fromInt(scalar))

    // in-place Mat ops
    def :+=(other: Mat[T])(using num: Numeric[T]): Unit = {
      require(m.rows == other.rows && m.cols == other.cols,
        s"shape mismatch: ${m.shape} vs ${other.shape}")
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          m(i, j) = num.plus(m(i, j), other(i, j))
          j += 1
        i += 1
    }

    def :-=(other: Mat[T])(using num: Numeric[T]): Unit = {
      require(m.rows == other.rows && m.cols == other.cols,
        s"shape mismatch: ${m.shape} vs ${other.shape}")
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          m(i, j) = num.minus(m(i, j), other(i, j))
          j += 1
        i += 1
    }

    /** NumPy: np.linalg.pinv(m) - Moore-Penrose pseudoinverse via SVD */
    def pinv(tol: Double = -1.0)(using frac: Fractional[T]): Mat[T] = {
      summon[ClassTag[T]].runtimeClass match
        case c if c == classOf[Double] =>
          val md = m.asInstanceOf[Mat[Double]]
          val nRows = md.rows
          val nCols = md.cols
          val p     = math.min(nRows, nCols)

          val (uMat, s, vtMat) = md.svdDouble
          val u  = uMat.underlying   // Array[Double] - avoids Mat.apply extension interference
          val vt = vtMat.underlying  // Array[Double]

          val threshold = if tol < 0 then
            1e-10 * math.max(nRows, nCols) * s(0)
          else tol

          val sInv = s.map(sv => if sv > threshold then 1.0 / sv else 0.0)

          // pinv = V * S^+ * U^T
          // V[i,k]   = Vt[k,i] = vt(k*nCols+i)
          // U^T[k,j] = U[j,k]  = u(j*nRows+k)
          val result = Array.ofDim[Double](nCols * nRows)
          var i = 0
          while i < nCols do
            var j = 0
            while j < nRows do
              var sum = 0.0
              var k = 0
              while k < p do
                sum = sum + vt(k * nCols + i) * sInv(k) * u(j * nRows + k)
                k += 1
              result(i * nRows + j) = sum
              j += 1
            i += 1
          Mat.create(result, nCols, nRows).asInstanceOf[Mat[T]]

        case c =>
          throw UnsupportedOperationException(s"pinv only supported for Double, got ${c.getName}")
    }

    /** NumPy: np.linalg.cholesky(m) - Cholesky decomposition
     *  Returns lower triangular L such that m = L * L^T
     *  m must be symmetric positive definite */
    def cholesky(using frac: Fractional[T]): Mat[T] =
      summon[ClassTag[T]].runtimeClass match
        case c if c == classOf[Double] =>
          import org.bytedeco.openblas.global.openblas.*
          val md = m.asInstanceOf[Mat[Double]]
          val n  = md.rows
          require(n == md.cols, s"cholesky requires square matrix, got ${md.shape}")
          val aCopy = md.flatten
          val info = LAPACKE_dpotrf(
            LAPACK_ROW_MAJOR, 'L'.toByte,
            n, aCopy, n
          )
          if info > 0 then
            throw ArithmeticException(s"Matrix is not positive definite (info=$info)")
          if info < 0 then
            throw ArithmeticException(s"LAPACKE_dpotrf failed with info=$info")
          // Zero out upper triangle - LAPACKE leaves original values there
          var i = 0
          while i < n do
            var j = i + 1
            while j < n do
              aCopy(i * n + j) = 0.0
              j += 1
            i += 1
          Mat.create(aCopy, n, n).asInstanceOf[Mat[T]]
        case c =>
          throw UnsupportedOperationException(s"cholesky only supported for Double, got ${c.getName}")

    /** NumPy: np.cross(a, b) - cross product of two 3D vectors */
    def cross(other: Vec[T])(using num: Numeric[T]): Vec[T] = {
      val a = m.flatten
      val b = other.flatten
      require(a.length == 3 && b.length == 3,
        s"cross product requires 3D vectors, got lengths ${a.length} and ${b.length}")
      val result = Array.ofDim[T](3)
      // [a1*b2 - a2*b1, a2*b0 - a0*b2, a0*b1 - a1*b0]
      result(0) = num.minus(num.times(a(1), b(2)), num.times(a(2), b(1)))
      result(1) = num.minus(num.times(a(2), b(0)), num.times(a(0), b(2)))
      result(2) = num.minus(num.times(a(0), b(1)), num.times(a(1), b(0)))
      Mat.create(result, 1, 3)
    }

    /** NumPy: np.kron(a, b) - Kronecker product */
    def kron(other: Mat[T])(using num: Numeric[T]): Mat[T] = {
      val nRows = m.rows * other.rows
      val nCols = m.cols * other.cols
      val result = Array.ofDim[T](nRows * nCols)
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          var p = 0
          while p < other.rows do
            var q = 0
            while q < other.cols do
              val r = i * other.rows + p
              val c = j * other.cols + q
              result(r * nCols + c) = num.times(m(i, j), other(p, q))
              q += 1
            p += 1
          j += 1
        i += 1
      Mat.create(result, nRows, nCols)
    }

    /** NumPy: np.std(m) - population standard deviation of all elements */
    def std(using frac: Fractional[T]): T =
      val mu     = m.mean
      val n      = m.size
      val sumSq  = m.tdata.foldLeft(frac.zero) { (acc, x) =>
        val diff = frac.minus(x, mu)
        frac.plus(acc, frac.times(diff, diff))
      }
      val variance = frac.div(sumSq, frac.fromInt(n))
      summon[ClassTag[T]].runtimeClass match
        case c if c == classOf[Double]     =>
          math.sqrt(frac.toDouble(variance)).asInstanceOf[T]
        case c if c == classOf[Float]      =>
          math.sqrt(frac.toDouble(variance)).toFloat.asInstanceOf[T]
        case c if c == classOf[BigDecimal] =>
          variance.asInstanceOf[Big].sqrt.asInstanceOf[T]
        case c => throw UnsupportedOperationException(s"std unsupported for ${c.getName}")

    /** NumPy: np.std(m, axis=0/1) - std along axis */
    def std(axis: Int)(using frac: Fractional[T]): Mat[T] = {
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      if axis == 0 then
        val result = Array.ofDim[T](m.cols)
        var j = 0
        while j < m.cols do
          result(j) = Mat.create(Array.tabulate(m.rows)(i => m(i, j)), m.rows, 1).std
          j += 1
        Mat.create(result, 1, m.cols)
      else
        val result = Array.ofDim[T](m.rows)
        var i = 0
        while i < m.rows do
          result(i) = Mat.create(Array.tabulate(m.cols)(j => m(i, j)), 1, m.cols).std
          i += 1
        Mat.create(result, m.rows, 1)
    }

    /** NumPy: np.var(m) - variance */
    def variance(using frac: Fractional[T]): T =
      val mu    = m.mean
      val n     = m.size
      val sumSq = m.tdata.foldLeft(frac.zero) { (acc, x) =>
        val diff = frac.minus(x, mu)
        frac.plus(acc, frac.times(diff, diff))
      }
      frac.div(sumSq, frac.fromInt(n))

    /** NumPy: np.sin(m) - element-wise sine */
    def sin(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          data(idx) = math.sin(num.toDouble(m(r, c)))
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** NumPy: np.cos(m) - element-wise cosine */
    def cos(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          data(idx) = math.cos(num.toDouble(m(r, c)))
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** NumPy: np.tan(m) - element-wise tangent */
    def tan(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          data(idx) = math.tan(num.toDouble(m(r, c)))
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** NumPy: np.arcsin(m) - element-wise arcsine */
    def arcsin(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          data(idx) = math.asin(num.toDouble(m(r, c)))
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** NumPy: np.arccos(m) - element-wise arccosine */
    def arccos(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          data(idx) = math.acos(num.toDouble(m(r, c)))
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** NumPy: np.arctan(m) - element-wise arctangent */
    def arctan(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          data(idx) = math.atan(num.toDouble(m(r, c)))
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** NumPy: np.arctan2(y, x) - element-wise 2-argument arctangent */
    def arctan2(other: Mat[T])(using num: Fractional[T]): Mat[Double] = {
      require(m.rows == other.rows && m.cols == other.cols,
        s"Shape mismatch: ${m.rows}x${m.cols} vs ${other.rows}x${other.cols}")

      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          data(idx) = math.atan2(num.toDouble(m(r, c)), num.toDouble(other(r, c)))
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** NumPy: np.sinh(m) - element-wise hyperbolic sine */
    def sinh(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          data(idx) = math.sinh(num.toDouble(m(r, c)))
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** NumPy: np.cosh(m) - element-wise hyperbolic cosine */
    def cosh(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          data(idx) = math.cosh(num.toDouble(m(r, c)))
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** NumPy: np.tanh(m) - element-wise hyperbolic tangent */
    def tanh(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          data(idx) = math.tanh(num.toDouble(m(r, c)))
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** NumPy: np.floor(m) - element-wise floor */
    def floor(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          data(idx) = math.floor(num.toDouble(m(r, c)))
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** NumPy: np.ceil(m) - element-wise ceiling */
    def ceil(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          data(idx) = math.ceil(num.toDouble(m(r, c)))
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    // Element-wise multiplication (NumPy's * operator)
    def *(other: Mat[T])(using num: Numeric[T]): Mat[T] = m.binOp(other)(num.times)

    // Matrix multiplication (NumPy's @ operator)
    inline def @@(other: Mat[T]): Mat[T] = matmul(other)

    // Division with broadcasting
    def /(other: Mat[T])(using frac: Fractional[T]): Mat[T] = m.binOp(other)(frac.div)

    /** ML: Sigmoid activation σ(x) = 1/(1 + e^(-x)) */
    def sigmoid(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          val x = num.toDouble(m(r, c))
          // Numerically stable sigmoid
          data(idx) = if (x >= 0) {
            1.0 / (1.0 + math.exp(-x))
          } else {
            val exp_x = math.exp(x)
            exp_x / (1.0 + exp_x)
          }
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** ML: Hyperbolic tangent (already exists as tanh) - alias for clarity */
    // tanh already implemented

    /** ML: ReLU (Rectified Linear Unit) - max(0, x) */
    def relu(using num: Numeric[T]): Mat[T] = {
      val zero = num.zero
      val data = Array.ofDim[T](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          val x = m(r, c)
          data(idx) = if (num.gt(x, zero)) x else zero
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** ML: Leaky ReLU - max(alpha*x, x) */
    def leakyRelu(alpha: Double = 0.01)(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          val x = num.toDouble(m(r, c))
          data(idx) = if (x > 0) x else alpha * x
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** ML: Softmax along axis - exp(x) / sum(exp(x))
     *
     *  Numerically stable: subtract max before exp to prevent overflow
     */
    def softmax(axis: Int = 1)(using num: Fractional[T]): Mat[Double] = {
      require(axis == 0 || axis == 1, "axis must be 0 (columns) or 1 (rows)")

      val result = Array.ofDim[Double](m.rows * m.cols)

      if (axis == 1) {
        // Softmax across columns for each row
        var r = 0
        while (r < m.rows) {
          // Find max in this row
          var maxVal = num.toDouble(m(r, 0))
          var c = 1
          while (c < m.cols) {
            val v = num.toDouble(m(r, c))
            if (v > maxVal) maxVal = v
            c += 1
          }

          // Compute exp(x - max) and sum
          var sum = 0.0
          c = 0
          while (c < m.cols) {
            val expVal = math.exp(num.toDouble(m(r, c)) - maxVal)
            result(r * m.cols + c) = expVal
            sum += expVal
            c += 1
          }

          // Normalize
          c = 0
          while (c < m.cols) {
            result(r * m.cols + c) /= sum
            c += 1
          }
          r += 1
        }
      } else {
        // Softmax down rows for each column
        var c = 0
        while (c < m.cols) {
          // Find max in this column
          var maxVal = num.toDouble(m(0, c))
          var r = 1
          while (r < m.rows) {
            val v = num.toDouble(m(r, c))
            if (v > maxVal) maxVal = v
            r += 1
          }

          // Compute exp(x - max) and sum
          var sum = 0.0
          r = 0
          while (r < m.rows) {
            val expVal = math.exp(num.toDouble(m(r, c)) - maxVal)
            result(r * m.cols + c) = expVal
            sum += expVal
            r += 1
          }

          // Normalize
          r = 0
          while (r < m.rows) {
            result(r * m.cols + c) /= sum
            r += 1
          }
          c += 1
        }
      }

      create(result, m.rows, m.cols)
    }

    /** ML: Log-softmax (numerically stable) - log(softmax(x)) */
    def logSoftmax(axis: Int = 1)(using num: Fractional[T]): Mat[Double] = {
      // More stable than log(softmax(x))
      require(axis == 0 || axis == 1, "axis must be 0 (columns) or 1 (rows)")

      val result = Array.ofDim[Double](m.rows * m.cols)

      if (axis == 1) {
        var r = 0
        while (r < m.rows) {
          // Find max
          var maxVal = num.toDouble(m(r, 0))
          var c = 1
          while (c < m.cols) {
            val v = num.toDouble(m(r, c))
            if (v > maxVal) maxVal = v
            c += 1
          }

          // Compute log(sum(exp(x - max)))
          var sumExp = 0.0
          c = 0
          while (c < m.cols) {
            sumExp += math.exp(num.toDouble(m(r, c)) - maxVal)
            c += 1
          }
          val logSumExp = maxVal + math.log(sumExp)

          // Result is x - log(sum(exp(x)))
          c = 0
          while (c < m.cols) {
            result(r * m.cols + c) = num.toDouble(m(r, c)) - logSumExp
            c += 1
          }
          r += 1
        }
      } else {
        var c = 0
        while (c < m.cols) {
          var maxVal = num.toDouble(m(0, c))
          var r = 1
          while (r < m.rows) {
            val v = num.toDouble(m(r, c))
            if (v > maxVal) maxVal = v
            r += 1
          }

          var sumExp = 0.0
          r = 0
          while (r < m.rows) {
            sumExp += math.exp(num.toDouble(m(r, c)) - maxVal)
            r += 1
          }
          val logSumExp = maxVal + math.log(sumExp)

          r = 0
          while (r < m.rows) {
            result(r * m.cols + c) = num.toDouble(m(r, c)) - logSumExp
            r += 1
          }
          c += 1
        }
      }

      create(result, m.rows, m.cols)
    }

    /** ML: ELU (Exponential Linear Unit) - x if x>0, alpha*(exp(x)-1) otherwise */
    def elu(alpha: Double = 1.0)(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          val x = num.toDouble(m(r, c))
          data(idx) = if (x > 0) x else alpha * (math.exp(x) - 1.0)
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** ML: GELU (Gaussian Error Linear Unit) - approximation */
    def gelu(using num: Fractional[T]): Mat[Double] = {
      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          val x = num.toDouble(m(r, c))
          // Approximation: 0.5 * x * (1 + tanh(sqrt(2/π) * (x + 0.044715 * x^3)))
          val x3 = x * x * x
          val inner = math.sqrt(2.0 / math.Pi) * (x + 0.044715 * x3)
          data(idx) = 0.5 * x * (1.0 + math.tanh(inner))
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }
    /** ML: Dropout - randomly zero elements during training
     *  
     *  @param p Probability of dropping (zeroing) each element (default 0.5)
     *  @param training If false, returns unchanged matrix (inference mode)
     *  @param seed Random seed for reproducibility (default: use global RNG)
     */
    def dropout(p: Double = 0.5, training: Boolean = true, seed: Long = -1)(using num: Fractional[T]): Mat[Double] = {
      require(p >= 0.0 && p < 1.0, s"Dropout probability must be in [0, 1), got $p")
      
      if (training) {
        val scale = 1.0 / (1.0 - p)
        val rng = if (seed >= 0) new NumPyRNG(seed) else globalRNG
        val data = Array.ofDim[Double](m.size)
        
        var idx = 0
        var r = 0
        while (r < m.rows) {
          var c = 0
          while (c < m.cols) {
            val keep = rng.nextDouble() >= p
            data(idx) = if (keep) {
              num.toDouble(m(r, c)) * scale
            } else {
              0.0
            }
            idx += 1
            c += 1
          }
          r += 1
        }
        create(data, m.rows, m.cols)
      } else {
        // Inference mode: no dropout, no scaling
        val data = Array.ofDim[Double](m.size)
        var idx = 0
        var r = 0
        while (r < m.rows) {
          var c = 0
          while (c < m.cols) {
            data(idx) = num.toDouble(m(r, c))
            idx += 1
            c += 1
          }
          r += 1
        }
        create(data, m.rows, m.cols)
      }
    }

    /** Element-wise maximum between two matrices (NumPy: np.maximum) */
    def maximum(other: Mat[T])(using ord: Ordering[T]): Mat[T] = {
      require(m.rows == other.rows && m.cols == other.cols,
        s"Shape mismatch: ${m.rows}x${m.cols} vs ${other.rows}x${other.cols}")
      
      val data = Array.ofDim[T](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          val v1 = m(r, c)
          val v2 = other(r, c)
          data(idx) = if (ord.gt(v1, v2)) v1 else v2
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** Element-wise minimum between two matrices (NumPy: np.minimum) */
    def minimum(other: Mat[T])(using ord: Ordering[T]): Mat[T] = {
      require(m.rows == other.rows && m.cols == other.cols,
        s"Shape mismatch: ${m.rows}x${m.cols} vs ${other.rows}x${other.cols}")
      
      val data = Array.ofDim[T](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          val v1 = m(r, c)
          val v2 = other(r, c)
          data(idx) = if (ord.lt(v1, v2)) v1 else v2
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** Element-wise maximum with scalar */
    def maximum(scalar: T)(using ord: Ordering[T]): Mat[T] = {
      val data = Array.ofDim[T](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          val v = m(r, c)
          data(idx) = if (ord.gt(v, scalar)) v else scalar
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** Element-wise minimum with scalar */
    def minimum(scalar: T)(using ord: Ordering[T]): Mat[T] = {
      val data = Array.ofDim[T](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          val v = m(r, c)
          data(idx) = if (ord.lt(v, scalar)) v else scalar
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

  } // end extension

  private lazy val blasThreshold: Long = System.getProperty("uni.mat.blasThreshold", "6000").toLong

  def vstack[U: ClassTag](matrices: Mat[U]*): Mat[U] = {
    require(matrices.nonEmpty, "vstack requires at least one matrix")
    val cols = matrices.head.cols
    require(matrices.forall(_.cols == cols), "vstack requires equal column counts")
    val totalRows = matrices.map(_.rows).sum
    val result = Array.ofDim[U](totalRows * cols)
    var offset = 0
    for mat <- matrices do
      var i = 0
      while i < mat.rows do
        var j = 0
        while j < mat.cols do
          result(offset + i * cols + j) = mat(i, j)
          j += 1
        i += 1
      offset += mat.rows * cols
    Mat.create(result, totalRows, cols)
  }

  def hstack[U: ClassTag](matrices: Mat[U]*): Mat[U] = {
    require(matrices.nonEmpty, "hstack requires at least one matrix")
    val rows = matrices.head.rows
    require(matrices.forall(_.rows == rows), "hstack requires equal row counts")
    val totalCols = matrices.map(_.cols).sum
    val result = Array.ofDim[U](rows * totalCols)
    var i = 0
    while i < rows do
      var colOffset = 0
      for mat <- matrices do
        var j = 0
        while j < mat.cols do
          result(i * totalCols + colOffset + j) = mat(i, j)
          j += 1
        colOffset += mat.cols
      i += 1
    Mat.create(result, rows, totalCols)
  }

  def concatenate[U: ClassTag](matrices: Seq[Mat[U]], axis: Int = 0): Mat[U] =
    if axis == 0 then vstack(matrices*)
    else hstack(matrices*)

  // ---- where (3-argument form) -------------------------------------------
  /** NumPy: np.where(condition, x, y) - element-wise conditional select */
  def where[T: ClassTag](condition: Mat[Boolean], x: Mat[T], y: Mat[T]): Mat[T] = {
    require(
      condition.rows == x.rows && condition.cols == x.cols &&
      x.rows == y.rows && x.cols == y.cols,
      s"where: shape mismatch: condition=${condition.shape} x=${x.shape} y=${y.shape}"
    )
    val result = Array.ofDim[T](x.rows * x.cols)
    var i = 0
    while i < x.rows do
      var j = 0
      while j < x.cols do
        result(i * x.cols + j) = if condition(i, j) then x(i, j) else y(i, j)
        j += 1
      i += 1
    Mat.create(result, x.rows, x.cols)
  }

  /** np.where(condition, scalar, scalar) */
  def where[T: ClassTag](condition: Mat[Boolean], x: T, y: T): Mat[T] = {
    val result = Array.ofDim[T](condition.rows * condition.cols)
    var i = 0
    while i < condition.rows do
      var j = 0
      while j < condition.cols do
        result(i * condition.cols + j) = if condition(i, j) then x else y
        j += 1
      i += 1
    Mat.create(result, condition.rows, condition.cols)
  }

  // ---- diag --------------------------------------------------------------

  /** NumPy: np.diag(array) - construct square diagonal matrix from array */
  def diag[T: ClassTag](values: Array[T])(using frac: Fractional[T]): Mat[T] = {
    val n = values.length
    val result = Array.fill(n * n)(frac.zero)
    var i = 0
    while i < n do
      result(i * n + i) = values(i)
      i += 1
    Mat.create(result, n, n)
  }

  /** np.diag(v) where v is a vector Mat */
  def diag[T: ClassTag](v: Mat[T])(using frac: Fractional[T]): Mat[T] = {
    require(v.rows == 1 || v.cols == 1,
      s"diag requires a vector (1xn or nx1), got ${v.shape}")
    diag(v.flatten)
  }

  /** Rectangular diagonal: nRowsxnCols with values on diagonal */
  def diag[T: ClassTag](values: Array[T], rows: Int, cols: Int)
      (using frac: Fractional[T]): Mat[T] = {
    val result = Array.fill(rows * cols)(frac.zero)
    val p = math.min(math.min(rows, cols), values.length)
    var i = 0
    while i < p do
      result(i * cols + i) = values(i)
      i += 1
    Mat.create(result, rows, cols)
  }

  // In Mat companion object
  def meshgrid[T: ClassTag](x: Vec[T], y: Vec[T]): (Mat[T], Mat[T]) = {
    val xs = x.flatten  // works for row or col vector
    val ys = y.flatten
    val nRows = ys.length
    val nCols = xs.length
    val xx = Array.ofDim[T](nRows * nCols)
    val yy = Array.ofDim[T](nRows * nCols)
    var i = 0
    while i < nRows do
      var j = 0
      while j < nCols do
        xx(i * nCols + j) = xs(j)
        yy(i * nCols + j) = ys(i)
        j += 1
      i += 1
    (Mat.create(xx, nRows, nCols), Mat.create(yy, nRows, nCols))
  }

  /** NumPy: np.polyfit(x, y, deg) - least squares polynomial fit
   *  Returns coefficients [a_n, a_{n-1}, ..., a_0] highest degree first */
  def polyfit(x: Vec[Double], y: Vec[Double], deg: Int): Vec[Double] = {
    require(deg >= 1, s"degree must be >= 1, got $deg")
    val xs = x.flatten
    val ys = y.flatten
    require(xs.length == ys.length, s"x and y must have same length")
    require(xs.length > deg, s"need more points than degree")
    val n = xs.length
    // Build Vandermonde matrix: each row is [x^deg, x^(deg-1), ..., x^0]
    val vand = Array.ofDim[Double](n * (deg + 1))
    var i = 0
    while i < n do
      var j = 0
      while j <= deg do
        vand(i * (deg + 1) + j) = math.pow(xs(i), (deg - j).toDouble)
        j += 1
      i += 1
    val A = Mat.create(vand, n, deg + 1)
    val b = Mat.create(ys, n, 1)
    val (coeffs, _, _, _) = A.lstsq(b)
    // coeffs is (deg+1)x1, return as flat row vector
    Mat.create(coeffs.flatten, 1, deg + 1)
  }

  /** NumPy: np.polyval(coeffs, x) - evaluate polynomial at points
   * Horner's method for numerical stability.
   *  coeffs = [a_n, ..., a_0] highest degree first (matches polyfit output) */
  def polyval(coeffs: Vec[Double], x: Vec[Double]): Vec[Double] = {
    val cs = coeffs.flatten  // [a_n, ..., a_0]
    val xs = x.flatten
    val result = Array.ofDim[Double](xs.length)
    var i = 0
    while i < xs.length do
      // Horner's method: a_n * x^n + ... + a_0
      var acc = 0.0
      var k = 0
      while k < cs.length do
        acc = acc * xs(i) + cs(k)
        k += 1
      result(i) = acc
      i += 1
    Mat.create(result, 1, xs.length)
  }

  /** NumPy: np.convolve(a, b, mode='full') - discrete linear convolution
   *  mode: "full" (default), "same", "valid" */
  def convolve(a: Vec[Double], b: Vec[Double], mode: String = "full"): Vec[Double] = {
    val as = a.flatten
    val bs = b.flatten
    val na = as.length
    val nb = bs.length
    val nFull = na + nb - 1
    val full = Array.ofDim[Double](nFull)
    var i = 0
    while i < na do
      var j = 0
      while j < nb do
        full(i + j) += as(i) * bs(j)
        j += 1
      i += 1
    mode match
      case "full" =>
        Mat.create(full, 1, nFull)
      case "same" =>
        val start = (nb - 1) / 2
        val result = Array.ofDim[Double](na)
        var k = 0
        while k < na do
          result(k) = full(start + k)
          k += 1
        Mat.create(result, 1, na)
      case "valid" =>
        val nValid = math.max(na, nb) - math.min(na, nb) + 1
        val start  = math.min(na, nb) - 1
        val result = Array.ofDim[Double](nValid)
        var k = 0
        while k < nValid do
          result(k) = full(start + k)
          k += 1
        Mat.create(result, 1, nValid)
      case other =>
        throw IllegalArgumentException(s"unknown mode '$other', use 'full', 'same', or 'valid'")
  }

  /** NumPy: np.correlate(a, b, mode='valid') - cross-correlation
   *  Correlation is convolution with b reversed */
  def correlate(a: Vec[Double], b: Vec[Double], mode: String = "valid"): Vec[Double] = {
    val as = a.flatten
    val bs = b.flatten
    val na = as.length
    val nb = bs.length
    val nFull = na + nb - 1
    val full = Array.ofDim[Double](nFull)
    // c[k] = sum_j a[j] * b[j - (k - (nb-1))]
    // equivalent: pad and slide b across a without reversing
    var k = 0
    while k < nFull do
      var j = 0
      while j < nb do
        val ai = k - (nb - 1) + j
        if ai >= 0 && ai < na then
          full(k) += as(ai) * bs(j)
        j += 1
      k += 1
    mode match
      case "full" =>
        Mat.create(full, 1, nFull)
      case "same" =>
        val start = (nb - 1) / 2
        val result = Array.ofDim[Double](na)
        var i = 0
        while i < na do
          result(i) = full(start + i)
          i += 1
        Mat.create(result, 1, na)
      case "valid" =>
        val nValid = math.max(na, nb) - math.min(na, nb) + 1
        val start  = nb - 1
        val result = Array.ofDim[Double](nValid)
        var i = 0
        while i < nValid do
          result(i) = full(start + i)
          i += 1
        Mat.create(result, 1, nValid)
      case other =>
        throw IllegalArgumentException(s"unknown mode '$other', use 'full', 'same', or 'valid'")
  }

  // In companion object
  def zerosLike[T: ClassTag](m: Mat[T])(using frac: Fractional[T]): Mat[T] =
    Mat.zeros[T](m.rows, m.cols)

  def onesLike[T: ClassTag](m: Mat[T])(using frac: Fractional[T]): Mat[T] =
    Mat.ones[T](m.rows, m.cols)

  def fullLike[T: ClassTag](m: Mat[T], value: T): Mat[T] =
    Mat.full[T](m.rows, m.cols, value)

  // exponentiation operator is '~^', chosen to achieve numpy
  // operator-precedence w.r.t. * and ~^
  // In scala, ~^ has highter precedence than *
  //          2 * 3 ~^ 2 = 18 ; 2*(3^2)
  // whereas  2 * 3  ^ 2 = 36 ; (2*3)^2
  //
  // not directly related to exponentiation, but needed
  // to support the usage in the above comment
  // (to left-multiply a matrix by a scalar).
  extension (scalar: Double)
    def *(m: Mat[Double]): Mat[Double] = m * scalar

  extension (base: Double)
    def ~^(exponent: Double): Double = Math.pow(base, exponent)

  extension [T: ClassTag](m: Mat[T])(using frac: Fractional[T])
    def ~^(exponent: Int): Mat[T] = m.power(exponent)
    def ~^(exponent: Double): Mat[T] =
      if exponent == 0.0 then Mat.eye[T](m.rows)
      else m.power(exponent)

  // Global RNG state
  private[data] lazy val defaultRNG: NumPyRNG = new NumPyRNG(0)
  private[data] var globalRNG: NumPyRNG = defaultRNG

  /** Set random seed matching NumPy's np.random.seed() */
  def setSeed(seed: Long): Unit =
    globalRNG = new NumPyRNG(seed)

  def nextRandLong: Long = globalRNG.nextInt()
  def nextRandInt: Long = globalRNG.nextInt() & 0xFFFFFFFFL
  def nextRandDouble: Double = globalRNG.nextDouble()

  /** NumPy: np.random.rand(rows, cols) - uniform [0, 1) */
  def rand(rows: Int, cols: Int): Mat[Double] = {
    val data = Array.fill(rows * cols)(globalRNG.nextDouble())
    Mat.create(data, rows, cols)
  }

  /** NumPy: np.random.randn(rows, cols) - standard normal */
  def randn(rows: Int, cols: Int): Mat[Double] = {
    val data = Array.fill(rows * cols)(globalRNG.randn())
    Mat.create(data, rows, cols)
  }

  // np.random.normal(mean, std, size=(5, 4))
  // You'd need to add:
  def normal(mean: Double, std: Double, rows: Int, cols: Int): Mat[Double] = {
    val data = Array.fill(rows * cols)(mean + std * globalRNG.randn())
    create(data, rows, cols)
  }

  /** NumPy: np.random.uniform(low, high, (rows, cols)) */
  def uniform(low: Double, high: Double, rows: Int, cols: Int): Mat[Double] = {
    val data = Array.fill(rows * cols)(globalRNG.uniform(low, high))
    Mat.create(data, rows, cols)
  }

  /** NumPy: np.random.randint(low, high, (rows, cols)) */
  def randint(low: Int, high: Int, rows: Int, cols: Int): Mat[Int] = {
    val range = high - low
    val data = Array.fill(rows * cols) {
      val value = globalRNG.nextInt()
      ((value % range + range) % range + low).toInt  // Handle negative modulo
    }
    Mat.create(data, rows, cols)
  }

  /** NumPy: np.random.randint(low, high) - single integer */
  def randint(low: Int, high: Int): Int = {
    val range = high - low
    val value = globalRNG.nextInt()
    ((value % range + range) % range + low).toInt
  }

}

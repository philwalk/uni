package uni.data

// MatDOps.scala — unboxed Mat[Double] indexing facade.
//
// PURPOSE: the generic apply(row,col): T / update(row,col,value:T) (Mat.scala)
// box every Double (Array[T] erases to Object[]).  This object re-supplies the
// COMPLETE apply/update family (plus `at`) specialized to Mat[Double], reading
// the backing Array[Double] primitively.  For a Mat[Double] receiver these win
// over the generics by receiver specificity, so client `m(i,j)` / `m(i,j)=v`
// never box.
//
// WHY THE WHOLE FAMILY: Scala 3 extension overload resolution does not merge
// across receiver specificity — adding ANY Mat[Double] `apply` shadows the
// entire generic `apply` family for a Mat[Double] receiver (same for `update`).
// So every overload must be re-supplied, even the already-fast slices.
//
// WHY PACKAGE LEVEL (not inside object Mat): so CVec[Double]/RVec[Double] slice
// return types survive TASTY (inside object Mat they are transparent = Mat[T]).
// Same reason VecExts.scala exists.  Vector returns are wrapped explicitly via
// Mat.mkCVec / Mat.mkRVec (legal only inside uni.data).
//
// DISPATCH SCOPE: exported from VecExts.scala (E161 keeps all apply/update
// re-exports in one definition group).  External callers (import uni.data.*)
// select these by specificity.  In-package code using `import Mat.*` keeps the
// generic (boxed) scalar — explicit import beats package scope — which is fine:
// those sites are library-internal and already hand-de-boxed where hot.
//
// SELF-DISPATCH: inside MatDOps, `m(r,c)=v` and `other(i,j)` resolve to these
// Mat[Double] overloads (more specific than the import Mat.* generics), so the
// slice/range/mask overloads are unboxed too without any helper extraction.
//
// CODEGEN: MatFOps.scala (Mat[Float] twin) is generated from this file by
// scoped substitution (Double→Float, matD…→matF…, MatDOps→MatFOps, etc.).
// Keep this file pure indexing — no BLAS, no math.*, no bare Double literals.

import Mat.*
import scala.reflect.ClassTag

object MatDOps:

  extension (m: Mat[Double])

    // ---- raw fast scalar access (no checks) --------------------------------
    @annotation.targetName("matDAt2d")
    def at(r: Int, c: Int): Double =
      m.tdata.asInstanceOf[Array[Double]](m.offset + r * m.rs + c * m.cs)

    @annotation.targetName("matDAt1d")
    def at(i: Int): Double =
      if m.isContiguous then m.tdata.asInstanceOf[Array[Double]](m.offset + i)
      else m.at(i / m.cols, i % m.cols)

    // ---- scalar element access (negative indexing + bounds-checked) --------
    @annotation.targetName("matDApply2d")
    def apply(row: Int, col: Int): Double =
      val r = if row < 0 then m.rows + row else row
      val c = if col < 0 then m.cols + col else col
      require(r >= 0 && r < m.rows && c >= 0 && c < m.cols,
        s"Index ($r, $c) out of bounds for ${m.rows}x${m.cols} matrix")
      m.tdata.asInstanceOf[Array[Double]](m.offset + r * m.rs + c * m.cs)

    @annotation.targetName("matDUpdate2d")
    def update(row: Int, col: Int, value: Double): Unit =
      val r = if row < 0 then m.rows + row else row
      val c = if col < 0 then m.cols + col else col
      require(r >= 0 && r < m.rows && c >= 0 && c < m.cols,
        s"Index ($r, $c) out of bounds for ${m.rows}x${m.cols} matrix")
      m.tdata.asInstanceOf[Array[Double]](m.offset + r * m.rs + c * m.cs) = value

    // ---- slicing / views ---------------------------------------------------
    @annotation.targetName("matDApplyAll")
    def apply(all: ::.type): Mat[Double] = m(0 until m.rows, 0 until m.cols)

    @annotation.targetName("matDApplyRangeAllCols")
    def apply(rows: Range, cols: ::.type): Mat[Double] = m(rows, 0 until m.cols)

    @annotation.targetName("matDApplyAllRowsRange")
    def apply(rows: ::.type, cols: Range): Mat[Double] = m(0 until m.rows, cols)

    @annotation.targetName("matDApplyRowRange")
    def apply(row: Int, cols: Range): RVec[Double] = Mat.mkRVec(m(row to row, cols))

    @annotation.targetName("matDApplyRangeCol")
    def apply(rows: Range, col: Int): CVec[Double] = Mat.mkCVec(m(rows, col to col))

    @annotation.targetName("matDApplyAllRowsCol")
    def apply(rows: ::.type, col: Int): CVec[Double] =
      val c = if col < 0 then m.cols + col else col
      require(c >= 0 && c < m.cols, s"Column index $c out of bounds")
      val a   = m.tdata.asInstanceOf[Array[Double]]
      val off = m.offset; val rs = m.rs; val cs = m.cs
      val out = new Array[Double](m.rows)
      var i = 0
      while i < m.rows do { out(i) = a(off + i * rs + c * cs); i += 1 }
      Mat.mkCVec(Mat.create(out, m.rows, 1))

    @annotation.targetName("matDApplyRowAllCols")
    def apply(row: Int, cols: ::.type): RVec[Double] =
      val r = if row < 0 then m.rows + row else row
      require(r >= 0 && r < m.rows, s"Row index $r out of bounds")
      val a   = m.tdata.asInstanceOf[Array[Double]]
      val off = m.offset; val rs = m.rs; val cs = m.cs
      val out = new Array[Double](m.cols)
      var j = 0
      while j < m.cols do { out(j) = a(off + r * rs + j * cs); j += 1 }
      Mat.mkRVec(Mat.create(out, 1, m.cols))

    @annotation.targetName("matDApplyColsView")
    def apply(rows: ::.type, cols: `*`.type): ColsView[Double] = ColsView(m)

    @annotation.targetName("matDApplyRowsView")
    def apply(rows: `*`.type, cols: ::.type): RowsView[Double] = RowsView(m)

    @annotation.targetName("matDApplyRangeRange")
    def apply(rows: Range, cols: Range): Mat[Double] =
      val rowSeq  = rows.toSeq
      val colSeq  = cols.toSeq
      val newRows = rowSeq.length
      val newCols = colSeq.length
      val a   = m.tdata.asInstanceOf[Array[Double]]
      val off = m.offset; val rs = m.rs; val cs = m.cs
      val out = new Array[Double](newRows * newCols)
      var i = 0; var idx = 0
      while i < newRows do
        val rBase = off + rowSeq(i) * rs
        var j = 0
        while j < newCols do
          out(idx) = a(rBase + colSeq(j) * cs); idx += 1; j += 1
        i += 1
      Mat.create(out, newRows, newCols)

    // ---- boolean mask indexing ---------------------------------------------
    @annotation.targetName("matDApplyMask")
    def apply(mask: Mat[Boolean]): Mat[Double] =
      require(mask.rows == m.rows && mask.cols == m.cols,
        s"Mask shape ${mask.shape} must match matrix shape ${m.shape}")
      val buf = scala.collection.mutable.ArrayBuilder.make[Double]
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          if mask(i, j) then buf += m(i, j)
          j += 1
        i += 1
      val arr = buf.result()
      Mat.create(arr, 1, arr.length)

    // ---- fancy index: row/col selection by Array[Int] ----------------------
    @annotation.targetName("matDApplyRowsIdx")
    def apply(rowIndices: Array[Int], cols: ::.type): Mat[Double] =
      val nCols  = m.cols
      val result = new Array[Double](rowIndices.length * nCols)
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

    @annotation.targetName("matDApplyColsIdx")
    def apply(rows: ::.type, colIndices: Array[Int]): Mat[Double] =
      val nRows  = m.rows
      val result = new Array[Double](nRows * colIndices.length)
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

    @annotation.targetName("matDApplyRowsColsIdx")
    def apply(rowIndices: Array[Int], colIndices: Array[Int]): Mat[Double] =
      val nRows  = rowIndices.length
      val nCols  = colIndices.length
      val result = new Array[Double](nRows * nCols)
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

    // ---- scalar broadcast updates ------------------------------------------
    @annotation.targetName("matDUpdateRangeAllV")
    def update(rows: Range, cols: ::.type, value: Double): Unit =
      for r <- rows do
        var j = 0
        while j < m.cols do
          m(r, j) = value
          j += 1

    @annotation.targetName("matDUpdateAllRangeV")
    def update(rows: ::.type, cols: Range, value: Double): Unit =
      var i = 0
      while i < m.rows do
        for c <- cols do m(i, c) = value
        i += 1

    @annotation.targetName("matDUpdateRangeRangeV")
    def update(rows: Range, cols: Range, value: Double): Unit =
      for r <- rows do
        for c <- cols do
          m(r, c) = value

    @annotation.targetName("matDUpdateRowRangeV")
    def update(row: Int, cols: Range, value: Double): Unit =
      for c <- cols do m(row, c) = value

    @annotation.targetName("matDUpdateRangeColV")
    def update(rows: Range, col: Int, value: Double): Unit =
      for r <- rows do m(r, col) = value

    @annotation.targetName("matDUpdateMaskV")
    def update(mask: Mat[Boolean], value: Double): Unit =
      require(mask.rows == m.rows && mask.cols == m.cols,
        s"Mask shape ${mask.shape} must match matrix shape ${m.shape}")
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          if mask(i, j) then m(i, j) = value
          j += 1
        i += 1

    @annotation.targetName("matDUpdateRowAllV")
    def update(row: Int, cols: ::.type, value: Double): Unit =
      var j = 0
      while j < m.cols do
        m(row, j) = value
        j += 1

    @annotation.targetName("matDUpdateAllColV")
    def update(rows: ::.type, col: Int, value: Double): Unit =
      var i = 0
      while i < m.rows do
        m(i, col) = value
        i += 1

    // ---- matrix-source block updates ---------------------------------------
    @annotation.targetName("matDUpdateRangeAllM")
    def update(rows: Range, cols: ::.type, other: Mat[Double]): Unit =
      require(other.rows == rows.length && other.cols == m.cols,
        s"shape mismatch: target ${rows.length}x${m.cols} vs source ${other.shape}")
      var i = 0
      for r <- rows do
        var j = 0
        while j < m.cols do
          m(r, j) = other(i, j)
          j += 1
        i += 1

    @annotation.targetName("matDUpdateAllRangeM")
    def update(rows: ::.type, cols: Range, other: Mat[Double]): Unit =
      require(other.rows == m.rows && other.cols == cols.length,
        s"shape mismatch: target ${m.rows}x${cols.length} vs source ${other.shape}")
      var i = 0
      while i < m.rows do
        var j = 0
        for c <- cols do
          m(i, c) = other(i, j)
          j += 1
        i += 1

    @annotation.targetName("matDUpdateRangeRangeM")
    def update(rows: Range, cols: Range, other: Mat[Double]): Unit =
      require(other.rows == rows.length && other.cols == cols.length,
        s"shape mismatch: target ${rows.length}x${cols.length} vs source ${other.shape}")
      var i = 0
      for r <- rows do
        var j = 0
        for c <- cols do
          m(r, c) = other(i, j)
          j += 1
        i += 1

    @annotation.targetName("matDUpdateRowAllM")
    def update(row: Int, cols: ::.type, other: Mat[Double]): Unit =
      val r = if row < 0 then m.rows + row else row
      require(r >= 0 && r < m.rows, s"Row index $r out of bounds [0, ${m.rows})")
      require(other.rows == 1 && other.cols == m.cols || other.rows == m.cols && other.cols == 1,
        s"shape mismatch: row has ${m.cols} cols, source is ${other.rows}×${other.cols}")
      if other.rows == 1 then
        for c <- 0 until m.cols do m(r, c) = other(0, c)
      else
        for c <- 0 until m.cols do m(r, c) = other(c, 0)

    @annotation.targetName("matDUpdateAllColM")
    def update(rows: ::.type, col: Int, other: Mat[Double]): Unit =
      val c = if col < 0 then m.cols + col else col
      require(c >= 0 && c < m.cols, s"Column index $c out of bounds [0, ${m.cols})")
      require(other.cols == 1 && other.rows == m.rows || other.rows == 1 && other.cols == m.rows,
        s"shape mismatch: col has ${m.rows} rows, source is ${other.rows}×${other.cols}")
      if other.cols == 1 then
        for r <- 0 until m.rows do m(r, c) = other(r, 0)
      else
        for r <- 0 until m.rows do m(r, c) = other(0, r)

    @annotation.targetName("matDUpdateRowsIdxM")
    def update(rowIndices: Array[Int], cols: ::.type, other: Mat[Double]): Unit =
      require(rowIndices.length == other.rows && m.cols == other.cols,
        s"Shape mismatch: need ${rowIndices.length}x${m.cols}, got ${other.rows}x${other.cols}")
      var i = 0
      while i < rowIndices.length do
        val r = rowIndices(i)
        var c = 0
        while c < m.cols do
          m(r, c) = other(i, c)
          c += 1
        i += 1

    @annotation.targetName("matDUpdateColsIdxM")
    def update(rows: ::.type, colIndices: Array[Int], other: Mat[Double]): Unit =
      require(m.rows == other.rows && colIndices.length == other.cols,
        s"Shape mismatch: need ${m.rows}x${colIndices.length}, got ${other.rows}x${other.cols}")
      var r = 0
      while r < m.rows do
        var i = 0
        while i < colIndices.length do
          val c = colIndices(i)
          m(r, c) = other(r, i)
          i += 1
        r += 1

    @annotation.targetName("matDUpdateRowsColsIdxM")
    def update(rowIndices: Array[Int], colIndices: Array[Int], other: Mat[Double]): Unit =
      require(rowIndices.length == other.rows && colIndices.length == other.cols,
        s"Shape mismatch: need ${rowIndices.length}x${colIndices.length}, got ${other.rows}x${other.cols}")
      var i = 0
      while i < rowIndices.length do
        val r = rowIndices(i)
        var j = 0
        while j < colIndices.length do
          val c = colIndices(j)
          m(r, c) = other(i, j)
          j += 1
        i += 1

    @annotation.targetName("matDUpdateRowsIdxV")
    def update(rowIndices: Array[Int], cols: ::.type, value: Double): Unit =
      var i = 0
      while i < rowIndices.length do
        val r = rowIndices(i)
        var c = 0
        while c < m.cols do
          m(r, c) = value
          c += 1
        i += 1

    @annotation.targetName("matDUpdateColsIdxV")
    def update(rows: ::.type, colIndices: Array[Int], value: Double): Unit =
      var r = 0
      while r < m.rows do
        var i = 0
        while i < colIndices.length do
          val c = colIndices(i)
          m(r, c) = value
          i += 1
        r += 1

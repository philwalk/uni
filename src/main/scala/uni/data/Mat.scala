package uni.data

import scala.reflect.ClassTag
import scala.compiletime.erasedValue
import scala.util.Random

object Mat {
  // Opaque type wraps flat array with dimensions
  opaque type Mat[T] = MatData[T]
  
  private[data] case class MatData[T](
    data: Array[T],
    rows: Int,
    cols: Int,
    transposed: Boolean = false
  )
  
  object :: // Sentinel object for "all" in slicing
  
  // ============================================================================
  // Core Properties (NumPy-aligned)
  // ============================================================================
  extension [T](m: Mat[T])
    inline def rows: Int            = m.rows
    inline def cols: Int            = m.cols
    inline def transposed: Boolean  = m.transposed
    inline def shape: (Int, Int)    = (m.rows, m.cols)
    inline def size: Int            = m.rows * m.cols
    inline def ndim: Int            = 2
    inline def isEmpty: Boolean     = m.size == 0
    inline def underlying: Array[T] = m.data
  
  // ============================================================================
  // Indexing (NumPy-aligned with negative index support)
  // ============================================================================
  extension [T](m: Mat[T])
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
      if m.transposed then m.data(c * m.rows + r)
      else m.data(r * m.cols + c)
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
      if m.transposed then m.data(c * m.rows + r) = value
      else m.data(r * m.cols + c) = value
    }
    
    /** 
     * NumPy: m[:, col] 
     * Extract column as column vector
     */
    def apply(rows: ::.type, col: Int)(using ClassTag[T]): Mat[T] = {
      val c = if col < 0 then m.cols + col else col
      require(c >= 0 && c < m.cols, s"Column index $c out of bounds")
      val result = Array.ofDim[T](m.rows)
      var i = 0
      while i < m.rows do
        result(i) = m(i, c)
        i += 1
      MatData(result, m.rows, 1)
    }
    
    /** 
     * NumPy: m[row, :] 
     * Extract row as row vector
     */
    def apply(row: Int, cols: ::.type)(using ClassTag[T]): Mat[T] = {
      val r = if row < 0 then m.rows + row else row
      require(r >= 0 && r < m.rows, s"Row index $r out of bounds")
      val result = Array.ofDim[T](m.cols)
      var j = 0
      while j < m.cols do
        result(j) = m(r, j)
        j += 1
      MatData(result, 1, m.cols)
    }
    
    /**
     * NumPy: m[rows, cols]
     * Rectangular slicing with Range support
     */
    def apply(rows: Range, cols: Range)(using ClassTag[T]): Mat[T] = {
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
      MatData(result, newRows, newCols)
    }
  
  // ============================================================================
  // Shape Manipulation
  // ============================================================================
  extension [T: ClassTag](m: Mat[T])
    def T: Mat[T] = transpose
    /** 
     * NumPy: m.T 
     * O(1) transpose - flips flag and swaps dims, no data movement
     */
    def transpose: Mat[T] = MatData(m.data, m.cols, m.rows, !m.transposed)
    
    /** 
     * NumPy: m.reshape(rows, cols) 
     * Reshape matrix - materializes logical order first if transposed
     */
    def reshape(rows: Int, cols: Int): Mat[T] = {
      require(rows * cols == m.size,
        s"Cannot reshape ${m.rows}x${m.cols} (size ${m.size}) to ${rows}x${cols}")
      val result = Array.ofDim[T](m.size)
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          result(i * m.cols + j) = m(i, j)
          j += 1
        i += 1
      MatData(result, rows, cols)
    }
    
    /** 
     * NumPy: m.flatten() 
     * Return flattened copy in logical row-major order
     */
    def flatten: Array[T] =
      if !m.transposed then m.data.clone()
      else
        val result = Array.ofDim[T](m.size)
        var i = 0
        while i < m.rows do
          var j = 0
          while j < m.cols do
            result(i * m.cols + j) = m(i, j)
            j += 1
          i += 1
        result
    
    /** 
     * NumPy: m.copy() 
     * Return deep copy preserving transposed state
     */
    def matCopy: Mat[T] = MatData(m.data.clone(), m.rows, m.cols, m.transposed)
    /**
     * NumPy: m.ravel()
     * Return flattened view as row vector in logical order
     */
    def ravel: Mat[T] = MatData(flatten, 1, m.size)
  
  // ============================================================================
  // Arithmetic Operations
  // ============================================================================
  extension [T: ClassTag](m: Mat[T])
    /** NumPy: m + n - Element-wise addition */
    def +(other: Mat[T])(using num: Numeric[T]): Mat[T] = {
      require(m.rows == other.rows && m.cols == other.cols,
        s"Shape mismatch: ${m.shape} vs ${other.shape}")
      val result = Array.ofDim[T](m.size)
      var i = 0
      while i < m.size do { result(i) = num.plus(m.data(i), other.data(i)); i += 1 }
      MatData(result, m.rows, m.cols)
    }
    /** NumPy: m + scalar - Add scalar to all elements */
    def +(scalar: T)(using num: Numeric[T]): Mat[T] = {
      val result = Array.ofDim[T](m.size)
      var i = 0
      while i < m.size do { result(i) = num.plus(m.data(i), scalar); i += 1 }
      MatData(result, m.rows, m.cols)
    }
    /** NumPy: m - n - Element-wise subtraction */
    def -(other: Mat[T])(using num: Numeric[T]): Mat[T] = {
      require(m.rows == other.rows && m.cols == other.cols,
        s"Shape mismatch: ${m.shape} vs ${other.shape}")
      val result = Array.ofDim[T](m.size)
      var i = 0
      while i < m.size do { result(i) = num.minus(m.data(i), other.data(i)); i += 1 }
      MatData(result, m.rows, m.cols)
    }
    /** NumPy: m - scalar - Subtract scalar from all elements */
    def -(scalar: T)(using num: Numeric[T]): Mat[T] = {
      val result = Array.ofDim[T](m.size)
      var i = 0
      while i < m.size do { result(i) = num.minus(m.data(i), scalar); i += 1 }
      MatData(result, m.rows, m.cols)
    }
    /** Element-wise (Hadamard) product */
    def *:*(other: Mat[T])(using num: Numeric[T]): Mat[T] = {
      require(m.rows == other.rows && m.cols == other.cols,
        s"Shape mismatch: ${m.shape} vs ${other.shape}")
      val result = Array.ofDim[T](m.size)
      var i = 0
      while i < m.size do { result(i) = num.times(m.data(i), other.data(i)); i += 1 }
      MatData(result, m.rows, m.cols)
    }
    /** Alias for *:* */
    def hadamard(other: Mat[T])(using num: Numeric[T]): Mat[T] = m *:* other

    /** NumPy: m * scalar - Multiply all elements by scalar */
    def *(scalar: T)(using num: Numeric[T]): Mat[T] = {
      val result = Array.ofDim[T](m.size)
      var i = 0
      while i < m.size do { result(i) = num.times(m.data(i), scalar); i += 1 }
      MatData(result, m.rows, m.cols)
    }
    def /(scalar: T)(using frac: Fractional[T]): Mat[T] = {
      val result = Array.ofDim[T](m.size)
      var i = 0
      while i < m.size do { result(i) = frac.div(m.data(i), scalar); i += 1 }
      MatData(result, m.rows, m.cols)
    }
    def unary_-(using num: Numeric[T]): Mat[T] = {
      val result = Array.ofDim[T](m.size)
      var i = 0
      while i < m.size do { result(i) = num.negate(m.data(i)); i += 1 }
      MatData(result, m.rows, m.cols)
    }
  
  // ============================================================================
  // Statistical Methods
  // ============================================================================
  extension [T](m: Mat[T])
    def min(using ord: Ordering[T]): T = m.data.min
    def max(using ord: Ordering[T]): T = m.data.max
    def sum(using num: Numeric[T]): T  = m.data.foldLeft(num.zero)(num.plus)
    def mean(using frac: Fractional[T]): T =
      frac.div(m.data.foldLeft(frac.zero)(frac.plus), frac.fromInt(m.size))
    
    def argmin(using ord: Ordering[T]): (Int, Int) =
      if !m.transposed then
        val idx = m.data.indexOf(m.data.min)
        (idx / m.cols, idx % m.cols)
      else
        var minVal = m(0, 0); var minR = 0; var minC = 0
        var i = 0
        while i < m.rows do
          var j = 0
          while j < m.cols do
            if ord.lt(m(i, j), minVal) then { minVal = m(i, j); minR = i; minC = j }
            j += 1
          i += 1
        (minR, minC)
    
    def argmax(using ord: Ordering[T]): (Int, Int) =
      if !m.transposed then
        val idx = m.data.indexOf(m.data.max)
        (idx / m.cols, idx % m.cols)
      else
        var maxVal = m(0, 0); var maxR = 0; var maxC = 0
        var i = 0
        while i < m.rows do
          var j = 0
          while j < m.cols do
            if ord.gt(m(i, j), maxVal) then { maxVal = m(i, j); maxR = i; maxC = j }
            j += 1
          i += 1
        (maxR, maxC)
  
  // ============================================================================
  // Functional Operations
  // ============================================================================
  extension [T: ClassTag](m: Mat[T])
    def map[U: ClassTag](f: T => U): Mat[U] = {
      val result = Array.ofDim[U](m.size)
      var i = 0
      while i < m.size do { result(i) = f(m.data(i)); i += 1 }
      MatData(result, m.rows, m.cols)
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
    def show: String = {
      def typeName: String =
        summon[ClassTag[T]].runtimeClass.getSimpleName match
          case "double"     => "Double"
          case "float"      => "Float"
          case "BigDecimal" => "Big"
          case other        => other
      if m.isEmpty then s"Mat[$typeName]([], shape=(0, 0))"
      else
        val values = m.data
        val absMax = values.map(v => math.abs(frac.toDouble(v))).max
        val absMin = values.filter(frac.toDouble(_) != 0.0)
                           .map(v => math.abs(frac.toDouble(v)))
                           .minOption.getOrElse(0.0)
        val fmt: Double => String =
          if absMax >= 1e6 || (absMin > 0 && absMin < 1e-4) then d => f"$d%.4e"
          else
            val allInts = values.forall(v => frac.toDouble(v) == math.floor(frac.toDouble(v)))
            if allInts then d => d.toLong.toString
            else
              val doubles = values.map(frac.toDouble)
              val spread = doubles.max - doubles.min
              val dec =
                if spread == 0.0 then 1
                else if spread >= 100 then 1
                else if spread >= 10  then 2
                else if spread >= 1   then 3
                else if spread >= 0.1 then 4
                else if spread >= 0.01 then 5
                else 6
              d => s"%.${dec}f".format(d)
        val colWidth = values.map(v => fmt(frac.toDouble(v)).length).max
        val sb = new StringBuilder
        sb.append(s"Mat[$typeName](\n")
        var i = 0
        while i < m.rows do
          sb.append("  [")
          var j = 0
          while j < m.cols do
            val s = fmt(frac.toDouble(m(i, j)))
            sb.append(" " * (colWidth - s.length)).append(s)
            if j < m.cols - 1 then sb.append(", ")
            j += 1
          sb.append("]")
          if i < m.rows - 1 then sb.append(",")
          sb.append("\n")
          i += 1
        sb.append(s"  shape=${m.shape}")
        sb.toString
    }

  // ============================================================================
  // Factory Methods
  // ============================================================================
  def zeros[T: ClassTag](rows: Int, cols: Int)(using frac: Fractional[T]): Mat[T] =
    MatData(Array.fill(rows * cols)(frac.zero), rows, cols)

  def zeros[T: ClassTag](shape: (Int, Int))(using frac: Fractional[T]): Mat[T] =
    zeros(shape._1, shape._2)

  def ones[T: ClassTag](rows: Int, cols: Int)(using frac: Fractional[T]): Mat[T] =
    MatData(Array.fill(rows * cols)(frac.one), rows, cols)

  def ones[T: ClassTag](shape: (Int, Int))(using frac: Fractional[T]): Mat[T] =
    ones(shape._1, shape._2)

  def eye[T: ClassTag](n: Int)(using frac: Fractional[T]): Mat[T] =
    MatData(Array.tabulate(n * n)(i => if i % (n + 1) == 0 then frac.one else frac.zero), n, n)

  def full[T: ClassTag](rows: Int, cols: Int, value: T): Mat[T] =
    MatData(Array.fill(rows * cols)(value), rows, cols)

  def full[T: ClassTag](shape: (Int, Int), value: T): Mat[T] =
    full(shape._1, shape._2, value)

  def arange[T: ClassTag](stop: Int)(using frac: Fractional[T]): Mat[T] =
    MatData(Array.tabulate(stop)(i => frac.fromInt(i)), stop, 1)

  def arange[T: ClassTag](start: Int, stop: Int)(using frac: Fractional[T]): Mat[T] = {
    val n = stop - start
    require(n > 0, s"stop ($stop) must be greater than start ($start)")
    MatData(Array.tabulate(n)(i => frac.fromInt(start + i)), n, 1)
  }

  def arange[T: ClassTag](start: Int, stop: Int, step: Int)(using frac: Fractional[T]): Mat[T] = {
    require(step != 0, "step cannot be zero")
    val n = ((stop - start).toDouble / step).ceil.toInt
    require(n > 0, s"Invalid range: start=$start, stop=$stop, step=$step")
    MatData(Array.tabulate(n)(i => frac.fromInt(start + i * step)), n, 1)
  }
  def linspace[T: ClassTag](start: Double, stop: Double, num: Int = 50)(using frac: Fractional[T]): Mat[T] = {
    require(num > 0, "num must be positive")
    if num == 1 then MatData(Array(frac.fromInt(start.toInt)), 1, 1)
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
      MatData(data, num, 1)
  }
  def apply[T: ClassTag](rows: Int, cols: Int, data: Array[T]): Mat[T] = {
    require(data.length == rows * cols, s"Data length ${data.length} != $rows x $cols")
    MatData(data, rows, cols)
  }
  def apply[T: ClassTag](unit: Unit): Mat[T] = MatData(Array.ofDim[T](0), 0, 0)
  def apply[T: ClassTag](value: T): Mat[T]   = MatData(Array(value), 1, 1)
  def apply[T: ClassTag](tuples: Tuple*)(using frac: Fractional[T]): Mat[T] = {
    val rows = tuples.length
    if rows == 0 then MatData(Array.ofDim[T](0), 0, 0)
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
      MatData(data, rows, cols)
  }
  // Concrete-type single-value factories (unambiguous, no [T] required)
  def apply(value: Double): Mat[Double] = MatData(Array(value), 1, 1)
  def apply(value: Big): Mat[Big]       = MatData(Array(value), 1, 1)

  /** Explicit 1×1 matrix factory */
  def single[T: ClassTag](value: T): Mat[T] = MatData(Array(value), 1, 1)

  /** Create column vector from sequence */
  def fromSeq[T: ClassTag](values: Seq[T]): Mat[T] =
    if values.isEmpty then empty[T] else MatData(values.toArray, values.length, 1)

  /** Create row vector from varargs */
  def of[T: ClassTag](first: T, rest: T*): Mat[T] = MatData((first +: rest).toArray, 1, 1 + rest.length)

  /** Create matrix using generator function */
  def tabulate[T: ClassTag](rows: Int, cols: Int)(f: (Int, Int) => T): Mat[T] = {
    val data = Array.ofDim[T](rows * cols)
    var i = 0
    while i < rows do
      var j = 0
      while j < cols do { data(i * cols + j) = f(i, j); j += 1 }
      i += 1
    MatData(data, rows, cols)
  }
  /** Create empty 0×0 matrix */
  def empty[T: ClassTag]: Mat[T]           = MatData(Array.ofDim[T](0), 0, 0)

  /** Create row vector from values */
  def row[T: ClassTag](values: T*): Mat[T] = MatData(values.toArray, 1, values.length)

  /** Create column vector from values */
  def col[T: ClassTag](values: T*): Mat[T] = MatData(values.toArray, values.length, 1)

  // ============================================================================
  // Matrix Multiply + Linear Algebra (extension block)
  // ============================================================================
  extension [T: ClassTag](m: Mat[T]) {

    // ---- Matrix multiply -----------------------------------------------
    inline def *(other: Mat[T]): Mat[T] = {
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

    inline def dot(other: Mat[T]): Mat[T] = m * other

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
      (MatData(lu, n, n), pivots, swaps)
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
      MatData(result, n, n)
    }

    // ---- QR Decomposition (Householder reflections) -------------------
    // Returns (Q: rows×p, R: p×cols) where m = Q * R, p = min(rows,cols)
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

      // Q accumulated as nRows×nRows identity, updated by each reflector
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

      // Return economy QR: Q is nRows×p, R is p×nCols
      // Q from accumulation is nRows×nRows - take first p columns
      val Qout = Array.ofDim[T](nRows * p)
      i = 0
      while i < nRows do
        var j = 0
        while j < p do
          Qout(i * p + j) = Q(i * nRows + j)
          j += 1
        i += 1

        // Slice R to p×nCols (drop rows p..nRows which are zero)
      val Rout = Array.ofDim[T](p * nCols)
        i = 0
        while i < p do
          var j = 0
          while j < nCols do
            Rout(i * nCols + j) = R(i * nCols + j)
            j += 1
          i += 1

      (MatData(Qout, nRows, p), MatData(Rout, p, nCols))
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
      val src = m.data
      val dst = new Array[T](src.length)
      System.arraycopy(src, 0, dst, 0, src.length)
      MatData(dst, m.rows, m.cols, m.transposed)
    }

    // ---- Pure-JVM multiply (parallel tiled) ----------------------------
    private[data] def multiplyDouble(other: Mat[Double]): Mat[Double] = {
      val a = m.data.asInstanceOf[Array[Double]]
      val b = other.data.asInstanceOf[Array[Double]]
      val rowsA = m.rows; val colsA = m.cols; val colsB = other.cols
      val result = Array.ofDim[Double](rowsA * colsB)
      val TILE = 32
      val aAt: (Int, Int) => Double =
        if !m.transposed then (i, k) => a(i * colsA + k) else (i, k) => a(k * rowsA + i)
      val bAt: (Int, Int) => Double =
        if !other.transposed then (k, j) => b(k * colsB + j) else (k, j) => b(j * other.rows + k)
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
      MatData(result, rowsA, colsB)
    }

    private[data] def multiplyFloat(other: Mat[Float]): Mat[Float] = {
      val a = m.data.asInstanceOf[Array[Float]]
      val b = other.data.asInstanceOf[Array[Float]]
      val rowsA = m.rows; val colsA = m.cols; val colsB = other.cols
      val result = Array.ofDim[Float](rowsA * colsB)
      val TILE = 32
      val aAt: (Int, Int) => Float =
        if !m.transposed then (i, k) => a(i * colsA + k) else (i, k) => a(k * rowsA + i)
      val bAt: (Int, Int) => Float =
        if !other.transposed then (k, j) => b(k * colsB + j) else (k, j) => b(j * other.rows + k)
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
      MatData(result, rowsA, colsB)
    }

    private[data] def multiplyBig(other: Mat[Big]): Mat[Big] = {
      val a = m.data.asInstanceOf[Array[BigDecimal]]
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
      MatData(result.asInstanceOf[Array[Big]], rowsA, colsB)
    }

    private inline def shouldUseBLAS[A](a: Mat[A], b: Mat[A]): Boolean =
      a.rows.toLong * a.cols * b.cols >= blasThreshold

    private[data] def multiplyDoubleBLAS(other: Mat[Double]): Mat[Double] = {
      import org.bytedeco.openblas.global.openblas.*
      import org.bytedeco.javacpp.*
      val a = m.data.asInstanceOf[Array[Double]]
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
      MatData(result, rowsA, colsB)
    }

    private[data] def multiplyFloatBLAS(other: Mat[Float]): Mat[Float] = {
      import org.bytedeco.openblas.global.openblas.*
      import org.bytedeco.javacpp.*
      val a = m.data.asInstanceOf[Array[Float]]
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
      MatData(result, rowsA, colsB)
    }

    def trace(using num: Numeric[T]): T = diagonal.foldLeft(num.zero)(num.plus)

    def allclose(other: Mat[T], rtol: Double = 1e-5, atol: Double = 1e-8)(using frac: Fractional[T]): Boolean = {
      if m.rows != other.rows || m.cols != other.cols then return false
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          val a = frac.toDouble(m(i, j))
          val b = frac.toDouble(other(i, j))
          if math.abs(a - b) > atol + rtol * math.abs(b) then return false
          j += 1
        i += 1
      true
    }

    /** NumPy: np.sum(m, axis=0) → row vector of column sums
     *         np.sum(m, axis=1) → column vector of row sums */
    def sum(axis: Int)(using num: Numeric[T]): Mat[T] = {
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      if axis == 0 then
        // Sum down rows → result is 1×cols
        val result = Array.fill(m.cols)(num.zero)
        var i = 0
        while i < m.rows do
          var j = 0
          while j < m.cols do
            result(j) = num.plus(result(j), m(i, j))
            j += 1
          i += 1
        MatData(result, 1, m.cols)
      else
        // Sum across cols → result is rows×1
        val result = Array.fill(m.rows)(num.zero)
        var i = 0
        while i < m.rows do
          var j = 0
          while j < m.cols do
            result(i) = num.plus(result(i), m(i, j))
            j += 1
          i += 1
        MatData(result, m.rows, 1)
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
        // Max down rows → 1×cols
        val result = Array.tabulate(m.cols)(j => m(0, j))
        var i = 1
        while i < m.rows do
          var j = 0
          while j < m.cols do
            if ord.gt(m(i, j), result(j)) then result(j) = m(i, j)
            j += 1
          i += 1
        MatData(result, 1, m.cols)
      else
        // Max across cols → rows×1
        val result = Array.tabulate(m.rows)(i => m(i, 0))
        var i = 0
        while i < m.rows do
          var j = 1
          while j < m.cols do
            if ord.gt(m(i, j), result(i)) then result(i) = m(i, j)
            j += 1
          i += 1
        MatData(result, m.rows, 1)
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
        MatData(result, 1, m.cols)
      else
        val result = Array.tabulate(m.rows)(i => m(i, 0))
        var i = 0
        while i < m.rows do
          var j = 1
          while j < m.cols do
            if ord.lt(m(i, j), result(i)) then result(i) = m(i, j)
            j += 1
          i += 1
        MatData(result, m.rows, 1)
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

    def outer(other: Mat[T])(using num: Numeric[T]): Mat[T] = {
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
      MatData(result, a.length, b.length)
    }

    /** NumPy: np.linalg.solve(A, b) - solve Ax = b for x */
    def solve(b: Mat[T])(using frac: Fractional[T]): Mat[T] = {
      require(m.rows == m.cols, s"solve requires square matrix, got ${m.shape}")
      require(b.rows == m.rows, s"b.rows ${b.rows} must match matrix rows ${m.rows}")
      val n = m.rows
      val nRhs = b.cols
      val (lu, pivots, _) = luDecompose
      val result = Array.ofDim[T](n * nRhs)

      var col = 0
      while col < nRhs do
        val x = Array.tabulate(n)(i => b(pivots(i), col))
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
      MatData(result, n, nRhs)
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
      MatData(result, m.rows, m.cols)
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
      MatData(result, 1, flat.length)
    }

    // 2. cov and corrcoef:
    // scala// NumPy: np.cov(m) - each ROW is a variable, each COL is an observation
    // Returns p×p covariance matrix where p = number of rows
    def cov(using frac: Fractional[T]): Mat[T] = {
      val p = m.rows  // number of variables
      val n = m.cols  // number of observations
      require(n > 1, "cov requires at least 2 observations (cols)")

      // Subtract row means
      val means = m.mean(1)  // p×1
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
      MatData(result, p, p)
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
      MatData(result, p, p)
    }

    // 3. sort and argsort:
    def sort(axis: Int = -1)(using ord: Ordering[T]): Mat[T] = {
      if axis == -1 then
        // Sort flattened
        val flat = m.flatten
        val sorted = flat.sorted
        MatData(sorted, 1, flat.length)
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
        MatData(result, m.rows, m.cols)
    }

    def argsort(axis: Int = -1)(using ord: Ordering[T]): Mat[Int] = {
      if axis == -1 then
        val flat = m.flatten
        val indices = flat.indices.sortBy(flat(_)).toArray
        MatData(indices, 1, indices.length)
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
        MatData(result, m.rows, m.cols)
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

      (MatData(u, nRows, nRows), s, MatData(vt, nCols, nCols))
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
          // uMat  is nRows×nRows, row-major: uMat.underlying(r*nRows + c) = U[r,c]
          // vtMat is nCols×nCols, row-major: vtMat.underlying(r*nCols + c) = Vt[r,c]
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
            val xMat = MatData(result, nCols, nRhs)
            val diff = md * xMat - bd
            var c2 = 0
            while c2 < nRhs do
              var i = 0
              while i < nRows do
                val v = diff(i, c2)
                residuals(c2) += v * v
                i += 1
              c2 += 1

          (
            MatData(result, nCols, nRhs).asInstanceOf[Mat[T]],
            MatData(residuals, 1, nRhs).asInstanceOf[Mat[T]],
            rank,
            s.asInstanceOf[Array[T]]
          )

        case c =>
          throw UnsupportedOperationException(s"lstsq only supported for Double, got ${c.getName}")
    }

  } // end extension

  def rand(rows: Int, cols: Int, seed: Long = -1): Mat[Double] = {
    val rng = if seed >= 0 then Random(seed) else Random

    MatData(Array.fill(rows * cols)(rng.nextDouble()), rows, cols)
  }

  def randn(rows: Int, cols: Int, seed: Long = -1): Mat[Double] = {
    val rng = if seed >= 0 then Random(seed) else Random
    MatData(Array.fill(rows * cols)(rng.nextGaussian()), rows, cols)
  }


  // In the Mat companion object (not extension)
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
    MatData(result, totalRows, cols)
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
    MatData(result, rows, totalCols)
  }

  def concatenate[U: ClassTag](matrices: Seq[Mat[U]], axis: Int = 0): Mat[U] =
    if axis == 0 then vstack(matrices*)
    else hstack(matrices*)

  private lazy val blasThreshold: Long = System.getProperty("uni.mat.blasThreshold", "6000").toLong

}

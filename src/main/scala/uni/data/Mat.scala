package uni.data

import scala.reflect.ClassTag
import scala.compiletime.erasedValue
import com.github.fommil.netlib.BLAS

object Mat {
  // Opaque type wraps flat array with dimensions
  opaque type Mat[T] = MatData[T]
  
  private case class MatData[T](
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
    /** NumPy: m.shape - returns (rows, cols) tuple */
    inline def shape: (Int, Int) = (m.rows, m.cols)
    
    /** NumPy: m.size - total number of elements */
    inline def size: Int = m.rows * m.cols
    
    /** NumPy: m.ndim - number of dimensions (always 2 for matrices) */
    inline def ndim: Int = 2
    
    /** Check if matrix is empty */
    inline def isEmpty: Boolean = m.size == 0
    
    /** Direct access to underlying flat array (for performance) */
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
      val r = if (row < 0) m.rows + row else row
      val c = if (col < 0) m.cols + col else col
      require(r >= 0 && r < m.rows && c >= 0 && c < m.cols, 
        s"Index ($r, $c) out of bounds for ${m.rows}×${m.cols} matrix")
      if m.transposed then
        m.data(c * m.rows + r)
      else
        m.data(r * m.cols + c)
    }
    
    /** 
     * NumPy: m[i, j] = value
     * Element update with negative indexing support
     */
    inline def update(row: Int, col: Int, value: T): Unit = {
      val r = if (row < 0) m.rows + row else row
      val c = if (col < 0) m.cols + col else col
      require(r >= 0 && r < m.rows && c >= 0 && c < m.cols,
        s"Index ($r, $c) out of bounds for ${m.rows}×${m.cols} matrix")
      if m.transposed then
        m.data(c * m.rows + r) = value
      else
        m.data(r * m.cols + c) = value
    }
    
    /** 
     * NumPy: m[:, col] 
     * Extract column as column vector
     */
    def apply(rows: ::.type, col: Int)(using ClassTag[T]): Mat[T] = {
      val c = if (col < 0) m.cols + col else col
      require(c >= 0 && c < m.cols, s"Column index $c out of bounds")
      val result = Array.ofDim[T](m.rows)
      var i = 0
      while (i < m.rows) {
        result(i) = m(i, c)  // respects transposed flag
        i += 1
      }
      MatData(result, m.rows, 1)
    }
    
    /** 
     * NumPy: m[row, :] 
     * Extract row as row vector
     */
    def apply(row: Int, cols: ::.type)(using ClassTag[T]): Mat[T] = {
      val r = if (row < 0) m.rows + row else row
      require(r >= 0 && r < m.rows, s"Row index $r out of bounds")
      val result = Array.ofDim[T](m.cols)
      var j = 0
      while (j < m.cols) {
        result(j) = m(r, j)  // respects transposed flag
        j += 1
      }
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
      while (i < newRows) {
        var j = 0
        while (j < newCols) {
          result(i * newCols + j) = m(rowSeq(i), colSeq(j))
          j += 1
        }
        i += 1
      }
      MatData(result, newRows, newCols)
    }
  
  // ============================================================================
  // Shape Manipulation (NumPy-aligned)
  // ============================================================================
  extension [T: ClassTag](m: Mat[T])
    /** 
     * NumPy: m.T 
     * O(1) transpose - flips flag and swaps dims, no data movement
     */
    def T: Mat[T] = transpose
    
    /** 
     * NumPy: m.transpose() 
     * O(1) transpose - flips flag and swaps dims, no data movement
     */
    def transpose: Mat[T] =
      MatData(m.data, m.cols, m.rows, !m.transposed)
    
    /** 
     * NumPy: m.reshape(rows, cols) 
     * Reshape matrix - materializes logical order first if transposed
     */
    def reshape(rows: Int, cols: Int): Mat[T] = {
      require(rows * cols == m.size, 
        s"Cannot reshape ${m.rows}×${m.cols} (size ${m.size}) to ${rows}×${cols} (size ${rows*cols})")
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
      if !m.transposed then
        m.data.clone()
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
    def copy: Mat[T] = MatData(m.data.clone(), m.rows, m.cols, m.transposed)
    
    /**
     * NumPy: m.ravel()
     * Return flattened view as row vector in logical order
     */
    def ravel: Mat[T] = MatData(flatten, 1, m.size)
  
  // ============================================================================
  // Arithmetic Operations (NumPy-aligned)
  // ============================================================================

  extension [T: ClassTag](m: Mat[T])
    /** NumPy: m + n - Element-wise addition */
    def +(other: Mat[T])(using num: Numeric[T]): Mat[T] = {
      require(m.rows == other.rows && m.cols == other.cols, 
        s"Shape mismatch: ${m.shape} vs ${other.shape}")
      val result = Array.ofDim[T](m.size)
      var i = 0
      while (i < m.size) {
        result(i) = num.plus(m.data(i), other.data(i))
        i += 1
      }
      MatData(result, m.rows, m.cols)
    }
    
    /** NumPy: m + scalar - Add scalar to all elements */
    def +(scalar: T)(using num: Numeric[T]): Mat[T] = {
      val result = Array.ofDim[T](m.size)
      var i = 0
      while (i < m.size) {
        result(i) = num.plus(m.data(i), scalar)
        i += 1
      }
      MatData(result, m.rows, m.cols)
    }
    
    /** NumPy: m - n - Element-wise subtraction */
    def -(other: Mat[T])(using num: Numeric[T]): Mat[T] = {
      require(m.rows == other.rows && m.cols == other.cols,
        s"Shape mismatch: ${m.shape} vs ${other.shape}")
      val result = Array.ofDim[T](m.size)
      var i = 0
      while (i < m.size) {
        result(i) = num.minus(m.data(i), other.data(i))
        i += 1
      }
      MatData(result, m.rows, m.cols)
    }
    
    /** NumPy: m - scalar - Subtract scalar from all elements */
    def -(scalar: T)(using num: Numeric[T]): Mat[T] = {
      val result = Array.ofDim[T](m.size)
      var i = 0
      while (i < m.size) {
        result(i) = num.minus(m.data(i), scalar)
        i += 1
      }
      MatData(result, m.rows, m.cols)
    }
    
    /** 
     * Element-wise multiplication (*:* to match Breeze convention)
     * NumPy equivalent: m * n
     */
    def *:*(other: Mat[T])(using num: Numeric[T]): Mat[T] = {
      require(m.rows == other.rows && m.cols == other.cols,
        s"Shape mismatch: ${m.shape} vs ${other.shape}")
      val result = Array.ofDim[T](m.size)
      var i = 0
      while (i < m.size) {
        result(i) = num.times(m.data(i), other.data(i))
        i += 1
      }
      MatData(result, m.rows, m.cols)
    }
    
    /** NumPy: m * scalar - Multiply all elements by scalar */
    def *(scalar: T)(using num: Numeric[T]): Mat[T] = {
      val result = Array.ofDim[T](m.size)
      var i = 0
      while (i < m.size) {
        result(i) = num.times(m.data(i), scalar)
        i += 1
      }
      MatData(result, m.rows, m.cols)
    }
    
    /*
    def multiplyGeneric(other: Mat[T])(using num: Numeric[T]): Mat[T] = {
      require(m.cols == other.rows)

      val a = m.data
      val b = other.data
      val rowsA = m.rows
      val colsA = m.cols
      val colsB = other.cols

      val aT = m.transposed
      val bT = other.transposed

      val plus  = num.plus
      val times = num.times
      val zero  = num.zero

      val result = Array.ofDim[T](rowsA * colsB)

      var i = 0
      while i < rowsA do
        var j = 0
        while j < colsB do
          var sum = zero
          var k = 0
          while k < colsA do
            val aVal =
              if !aT then a(i * colsA + k)
              else a(k * rowsA + i)

            val bVal =
              if !bT then b(k * colsB + j)
              else b(j * other.rows + k)

            sum = plus(sum, times(aVal, bVal))
            k += 1

          result(i * colsB + j) = sum
          j += 1
        i += 1

      MatData(result, rowsA, colsB)
    }
    */
    
    /** NumPy: m.dot(n) - Matrix multiplication (explicit form) */
    //def dot(other: Mat[T])(using num: Numeric[T]): Mat[T] = m * other
    
    /** NumPy: m / scalar - Divide all elements by scalar */
    def /(scalar: T)(using frac: Fractional[T]): Mat[T] = {
      val result = Array.ofDim[T](m.size)
      var i = 0
      while (i < m.size) {
        result(i) = frac.div(m.data(i), scalar)
        i += 1
      }
      MatData(result, m.rows, m.cols)
    }
    
    /** NumPy: -m - Unary negation */
    def unary_-(using num: Numeric[T]): Mat[T] = {
      val result = Array.ofDim[T](m.size)
      var i = 0
      while (i < m.size) {
        result(i) = num.negate(m.data(i))
        i += 1
      }
      MatData(result, m.rows, m.cols)
    }
  
  // ============================================================================
  // Statistical Methods (NumPy-aligned)
  // ============================================================================
  
  extension [T](m: Mat[T])
    /** NumPy: m.min() - minimum element */
    def min(using ord: Ordering[T]): T = m.data.min
    
    /** NumPy: m.max() - maximum element */
    def max(using ord: Ordering[T]): T = m.data.max
    
    /** NumPy: m.sum() - sum of all elements */
    def sum(using num: Numeric[T]): T = 
      m.data.foldLeft(num.zero)(num.plus)
    
    /** NumPy: m.mean() - mean of all elements */
    def mean(using frac: Fractional[T]): T = {
      val s = m.data.foldLeft(frac.zero)(frac.plus)
      frac.div(s, frac.fromInt(m.size))
    }
    
    /** NumPy: m.argmin() - index of minimum element as (row, col) */
    def argmin(using ord: Ordering[T]): (Int, Int) =
      if !m.transposed then
        val idx = m.data.indexOf(m.data.min)
        (idx / m.cols, idx % m.cols)
      else
        var minVal = m(0, 0)
        var minR = 0
        var minC = 0
        var i = 0
        while i < m.rows do
          var j = 0
          while j < m.cols do
            if ord.lt(m(i, j), minVal) then
              minVal = m(i, j); minR = i; minC = j
            j += 1
          i += 1
        (minR, minC)
    
    /** NumPy: m.argmax() - index of maximum element as (row, col) */
    def argmax(using ord: Ordering[T]): (Int, Int) =
      if !m.transposed then
        val idx = m.data.indexOf(m.data.max)
        (idx / m.cols, idx % m.cols)
      else
        var maxVal = m(0, 0)
        var maxR = 0
        var maxC = 0
        var i = 0
        while i < m.rows do
          var j = 0
          while j < m.cols do
            if ord.gt(m(i, j), maxVal) then
              maxVal = m(i, j); maxR = i; maxC = j
            j += 1
          i += 1
        (maxR, maxC)
  
  // ============================================================================
  // Functional Operations
  // ============================================================================
  
  extension [T: ClassTag](m: Mat[T])
    /** Apply function to each element */
    def map[U: ClassTag](f: T => U): Mat[U] = {
      val result = Array.ofDim[U](m.size)
      var i = 0
      while (i < m.size) {
        result(i) = f(m.data(i))
        i += 1
      }
      MatData(result, m.rows, m.cols)
    }
    
    /** Return indices where predicate is true */
    def where(pred: T => Boolean): Array[(Int, Int)] = {
      val indices = scala.collection.mutable.ArrayBuffer[(Int, Int)]()
      var i = 0
      while (i < m.rows) {
        var j = 0
        while (j < m.cols) {
          if (pred(m(i, j))) indices += ((i, j))
          j += 1
        }
        i += 1
      }
      indices.toArray
    }
  
  // ============================================================================
  // Display
  // ============================================================================
  
  extension [T: ClassTag](m: Mat[T])(using frac: Fractional[T])
    /** Pretty-print matrix */
    def show: String = {
      def typeName: String =
        summon[ClassTag[T]].runtimeClass.getSimpleName match
          case "double"     => "Double"
          case "float"      => "Float"
          case "BigDecimal" => "Big"
          case other        => other
      
      if m.isEmpty then 
        s"Mat[$typeName]([], shape=(0, 0))"
      else
        // 1. Analyze data range (raw data ok - same elements regardless of transpose)
        val values = m.data
        val absMax = values.map(v => math.abs(frac.toDouble(v))).max
        val absMin = values.filter(frac.toDouble(_) != 0.0)
                           .map(v => math.abs(frac.toDouble(v)))
                           .minOption.getOrElse(0.0)
        
        // 2. Decide format based on range
        val fmt: Double => String =
          if absMax >= 1e6 || (absMin > 0 && absMin < 1e-4) then
            d => f"$d%.4e"
          else
            val allInts = values.forall(v => frac.toDouble(v) == math.floor(frac.toDouble(v)))
            if allInts then
              d => d.toLong.toString
            else
              val doubles = values.map(frac.toDouble)
              val spread = doubles.max - doubles.min
              val decPlaces = 
                if spread == 0.0  then 1
                else if spread >= 100  then 1
                else if spread >= 10   then 2
                else if spread >= 1    then 3
                else if spread >= 0.1  then 4
                else if spread >= 0.01 then 5
                else 6
              d => s"%.${decPlaces}f".format(d)

        // 3. Find max column width for alignment
        val colWidth = values.map(v => fmt(frac.toDouble(v)).length).max
        
        // 4. Render using m(i,j) to respect transposed flag
        val sb = new StringBuilder
        sb.append(s"Mat[$typeName](\n")
        var i = 0
        while i < m.rows do
          sb.append("  [")
          var j = 0
          while j < m.cols do
            val s = fmt(frac.toDouble(m(i, j)))
            sb.append(" " * (colWidth - s.length))
            sb.append(s)
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
  // Factory Methods (NumPy-aligned)
  // ============================================================================
  
  /** NumPy: np.zeros((rows, cols)) */
  def zeros[T: ClassTag](rows: Int, cols: Int)(using frac: Fractional[T]): Mat[T] =
    MatData(Array.fill(rows * cols)(frac.zero), rows, cols)
  
  /** NumPy: np.zeros(shape) where shape is tuple */
  def zeros[T: ClassTag](shape: (Int, Int))(using frac: Fractional[T]): Mat[T] = 
    zeros(shape._1, shape._2)
  
  /** NumPy: np.ones((rows, cols)) */
  def ones[T: ClassTag](rows: Int, cols: Int)(using frac: Fractional[T]): Mat[T] =
    MatData(Array.fill(rows * cols)(frac.one), rows, cols)
  
  /** NumPy: np.ones(shape) where shape is tuple */
  def ones[T: ClassTag](shape: (Int, Int))(using frac: Fractional[T]): Mat[T] = 
    ones(shape._1, shape._2)
  
  /** NumPy: np.eye(n) - n×n identity matrix */
  def eye[T: ClassTag](n: Int)(using frac: Fractional[T]): Mat[T] =
    MatData(Array.tabulate(n * n)(i => if i % (n + 1) == 0 then frac.one else frac.zero), n, n)
  
  /** NumPy: np.full((rows, cols), value) */
  def full[T: ClassTag](rows: Int, cols: Int, value: T): Mat[T] =
    MatData(Array.fill(rows * cols)(value), rows, cols)
  
  /** NumPy: np.full(shape, value) where shape is tuple */
  def full[T: ClassTag](shape: (Int, Int), value: T): Mat[T] = 
    full(shape._1, shape._2, value)
  
  /** NumPy: np.arange(stop) - column vector [0, 1, ..., stop-1] */
  def arange[T: ClassTag](stop: Int)(using frac: Fractional[T]): Mat[T] =
    MatData(Array.tabulate(stop)(i => frac.fromInt(i)), stop, 1)
  
  /** NumPy: np.arange(start, stop) */
  def arange[T: ClassTag](start: Int, stop: Int)(using frac: Fractional[T]): Mat[T] = {
    val n = stop - start
    require(n > 0, s"stop ($stop) must be greater than start ($start)")
    MatData(Array.tabulate(n)(i => frac.fromInt(start + i)), n, 1)
  }
  
  /** NumPy: np.arange(start, stop, step) */
  def arange[T: ClassTag](start: Int, stop: Int, step: Int)(using frac: Fractional[T]): Mat[T] = {
    require(step != 0, "step cannot be zero")
    val n = ((stop - start).toDouble / step).ceil.toInt
    require(n > 0, s"Invalid range: start=$start, stop=$stop, step=$step")
    MatData(Array.tabulate(n)(i => frac.fromInt(start + i * step)), n, 1)
  }
  
  /** NumPy: np.linspace(start, stop, num) */
  def linspace[T: ClassTag](start: Double, stop: Double, num: Int = 50)(using frac: Fractional[T]): Mat[T] = {
    require(num > 0, "num must be positive")
    if num == 1 then
      MatData(Array(frac.fromInt(start.toInt)), 1, 1)
    else
      val step = (stop - start) / (num - 1)
      val data = Array.tabulate(num) { i =>
        val v = start + i * step
        summon[ClassTag[T]].runtimeClass match
          case c if c == classOf[Double]     => v.asInstanceOf[T]
          case c if c == classOf[Float]      => v.toFloat.asInstanceOf[T]
          case c if c == classOf[BigDecimal] => BigDecimal(v).asInstanceOf[T]
          case c => throw IllegalArgumentException(s"linspace unsupported type: ${c.getName}")
      }
      MatData(data, num, 1)
  }
  
  /** Create matrix from flat array with explicit dimensions */
  def apply[T: ClassTag](rows: Int, cols: Int, data: Array[T]): Mat[T] = {
    require(data.length == rows * cols, 
      s"Data length ${data.length} != $rows × $cols")
    MatData(data, rows, cols)
  }
  
  /** Mat(()) - empty matrix */
  def apply[T: ClassTag](unit: Unit): Mat[T] =
    MatData(Array.ofDim[T](0), 0, 0)
  
  /** Mat(value) - 1×1 matrix from scalar */
  def apply[T: ClassTag](value: T): Mat[T] =
    MatData(Array(value), 1, 1)

  /** NumPy: np.array([[1, 2], [3, 4]]) - matrix from tuple literals */
  def apply[T: ClassTag](tuples: Tuple*)(using frac: Fractional[T]): Mat[T] = {
    val rows = tuples.length
    if rows == 0 then 
      MatData(Array.ofDim[T](0), 0, 0)
    else
      val cols = tuples(0).productArity
      val data = Array.ofDim[T](rows * cols)
      var i = 0
      while i < rows do
        val rowTuple = tuples(i)
        require(rowTuple.productArity == cols, s"Jagged rows not allowed")
        var j = 0
        while j < cols do
          data(i * cols + j) = rowTuple.productElement(j) match
            case n: Int          => frac.fromInt(n)
            case n: Double       => n.asInstanceOf[T]
            case n: Float        => n.asInstanceOf[T]
            case n: BigDecimal   => n.asInstanceOf[T]
            case v: T @unchecked => v
            case other           => throw new IllegalArgumentException(s"Unsupported type: ${other.getClass.getName}")
          j += 1
        i += 1
      MatData(data, rows, cols)
  }

  // Concrete-type single-value factories (unambiguous, no [T] required)
  def apply(value: Double): Mat[Double] = MatData(Array(value), 1, 1)
  def apply(value: Big): Mat[Big]       = MatData(Array(value), 1, 1)

  /** Explicit 1×1 matrix factory */
  def single[T: ClassTag](value: T): Mat[T] =
    MatData(Array(value), 1, 1)
  
  /** Create column vector from sequence */
  def fromSeq[T: ClassTag](values: Seq[T]): Mat[T] =
    if values.isEmpty then empty[T]
    else MatData(values.toArray, values.length, 1)
  
  /** Create row vector from varargs */
  def of[T: ClassTag](first: T, rest: T*): Mat[T] = {
    val values = first +: rest
    MatData(values.toArray, 1, values.length)
  }
  
  /** Create matrix using generator function */
  def tabulate[T: ClassTag](rows: Int, cols: Int)(f: (Int, Int) => T): Mat[T] = {
    val data = Array.ofDim[T](rows * cols)
    var i = 0
    while (i < rows) {
      var j = 0
      while (j < cols) {
        data(i * cols + j) = f(i, j)
        j += 1
      }
      i += 1
    }
    MatData(data, rows, cols)
  }
  
  /** Create empty 0×0 matrix */
  def empty[T: ClassTag]: Mat[T] = MatData(Array.ofDim[T](0), 0, 0)
  
  /** Create row vector from values */
  def row[T: ClassTag](values: T*): Mat[T] =
    MatData(values.toArray, 1, values.length)
  
  /** Create column vector from values */
  def col[T: ClassTag](values: T*): Mat[T] =
    MatData(values.toArray, values.length, 1)

  // matrix multiply
  extension [T: ClassTag](m: Mat[T]) {
    /** 
     * NumPy: m @ n or m.dot(n) - Matrix multiplication
     * Uses apply() to correctly handle transposed matrices
     */
    inline def *(other: Mat[T]): Mat[T] = {
      if m.cols != other.rows then
        throw new IllegalArgumentException(s"m.cols[${m.cols}] != other.rows[${other.rows}]")

      inline erasedValue[T] match
        case _: Double =>
          val a = m.asInstanceOf[Mat[Double]]
          val b = other.asInstanceOf[Mat[Double]]
          val out =
            if shouldUseBLAS(a, b) then multiplyDoubleBLAS(b)
            else multiplyDouble(b)
          out.asInstanceOf[Mat[T]]

        case _: Float =>
          val a = m.asInstanceOf[Mat[Float]]
          val b = other.asInstanceOf[Mat[Float]]
          val out =
            if shouldUseBLAS(a, b) then multiplyFloatBLAS(b)
            else multiplyFloat(b)
          out.asInstanceOf[Mat[T]]

        case _: Big =>
          multiplyBig(other.asInstanceOf[Mat[Big]]).asInstanceOf[Mat[T]]

        case _: BigDecimal =>
          multiplyBig(other.asInstanceOf[Mat[Big]]).asInstanceOf[Mat[T]]
    }

    private def multiplyDouble(other: Mat[Double]): Mat[Double] = {
      val a = m.data.asInstanceOf[Array[Double]]
      val b = other.data.asInstanceOf[Array[Double]]
      val rowsA = m.rows
      val colsA = m.cols
      val colsB = other.cols

      val aT = m.transposed
      val bT = other.transposed

      val result = Array.ofDim[Double](rowsA * colsB)

      // Precompute accessors to eliminate branches in the inner loop
      val aAt: (Int, Int) => Double =
        if !aT then (i, k) => a(i * colsA + k)
        else (i, k) => a(k * rowsA + i)

      val bAt: (Int, Int) => Double =
        if !bT then (k, j) => b(k * colsB + j)
        else (k, j) => b(j * other.rows + k)

      var i = 0
      while i < rowsA do
        var j = 0
        while j < colsB do
          var sum = 0.0
          var k = 0
          while k < colsA do
            sum += aAt(i, k) * bAt(k, j)
            k += 1
          result(i * colsB + j) = sum
          j += 1
        i += 1
      MatData(result, rowsA, colsB)
    }

    private def multiplyFloat(other: Mat[Float]): Mat[Float] = {
      val a = m.data.asInstanceOf[Array[Float]]
      val b = other.data.asInstanceOf[Array[Float]]
      val rowsA = m.rows
      val colsA = m.cols
      val colsB = other.cols

      val aT = m.transposed
      val bT = other.transposed

      val result = Array.ofDim[Float](rowsA * colsB)

      val aAt =
        if !aT then (i: Int, k: Int) => a(i * colsA + k)
        else (i: Int, k: Int) => a(k * rowsA + i)

      val bAt =
        if !bT then (k: Int, j: Int) => b(k * colsB + j)
        else (k: Int, j: Int) => b(j * other.rows + k)

      var i = 0
      while i < rowsA do
        var j = 0
        while j < colsB do
          var sum = 0.0f
          var k = 0
          while k < colsA do
            sum += aAt(i, k) * bAt(k, j)
            k += 1
          result(i * colsB + j) = sum
          j += 1
        i += 1

      MatData(result, rowsA, colsB)
    }

    private def multiplyBig(other: Mat[Big]): Mat[Big] = {
      val a = m.data.asInstanceOf[Array[BigDecimal]]
      val b = other.data.asInstanceOf[Array[BigDecimal]]
      val rowsA = m.rows
      val colsA = m.cols
      val colsB = other.cols

      val aT = m.transposed
      val bT = other.transposed

      val result = Array.ofDim[BigDecimal](rowsA * colsB)

      val aAt =
        if !aT then (i: Int, k: Int) => a(i * colsA + k)
        else (i: Int, k: Int) => a(k * rowsA + i)

      val bAt =
        if !bT then (k: Int, j: Int) => b(k * colsB + j)
        else (k: Int, j: Int) => b(j * other.rows + k)

      var i = 0
      while i < rowsA do
        var j = 0
        while j < colsB do
          var sum = BigDecimal(0)
          var k = 0
          while k < colsA do
            sum = sum + (aAt(i, k) * bAt(k, j))
            k += 1
          result(i * colsB + j) = sum
          j += 1
        i += 1

      MatData(result.asInstanceOf[Array[Big]], rowsA, colsB)
    }

    private inline def shouldUseBLAS[A](a: Mat[A], b: Mat[A]): Boolean =
      a.rows * a.cols * b.cols >= 50_000 // cubic work estimate

    private def multiplyDoubleBLAS(other: Mat[Double]): Mat[Double] = {
      val a = m.data.asInstanceOf[Array[Double]]
      val b = other.data.asInstanceOf[Array[Double]]
      val rowsA = m.rows
      val colsA = m.cols
      val colsB = other.cols

      // Convert to column-major
      val aCol = new Array[Double](rowsA * colsA)
      val bCol = new Array[Double](colsA * colsB)
      val cCol = new Array[Double](rowsA * colsB)

      // row-major → column-major
      var i = 0
      while i < rowsA do
        var j = 0
        while j < colsA do
          aCol(j * rowsA + i) = a(i * colsA + j)
          j += 1
        i += 1

      i = 0
      while i < colsA do
        var j = 0
        while j < colsB do
          bCol(j * colsA + i) = b(i * colsB + j)
          j += 1
        i += 1

      // BLAS call: C = A * B
      BLAS.getInstance.dgemm(
        "N", "N",
        rowsA, colsB, colsA,
        1.0,
        aCol, 0, rowsA,
        bCol, 0, colsA,
        0.0,
        cCol, 0, rowsA
      )

      // Convert back to row-major
      val result = new Array[Double](rowsA * colsB)
      i = 0
      while i < rowsA do
        var j = 0
        while j < colsB do
          result(i * colsB + j) = cCol(j * rowsA + i)
          j += 1
        i += 1

      MatData(result, rowsA, colsB)
    }
    private def multiplyFloatBLAS(other: Mat[Float]): Mat[Float] = {
      val a = m.data.asInstanceOf[Array[Float]]
      val b = other.data.asInstanceOf[Array[Float]]
      val rowsA = m.rows
      val colsA = m.cols
      val colsB = other.cols

      val aCol = new Array[Float](rowsA * colsA)
      val bCol = new Array[Float](colsA * colsB)
      val cCol = new Array[Float](rowsA * colsB)

      var i = 0
      while i < rowsA do
        var j = 0
        while j < colsA do
          aCol(j * rowsA + i) = a(i * colsA + j)
          j += 1
        i += 1

      i = 0
      while i < colsA do
        var j = 0
        while j < colsB do
          bCol(j * colsA + i) = b(i * colsB + j)
          j += 1
        i += 1

      BLAS.getInstance.sgemm(
        "N", "N",
        rowsA, colsB, colsA,
        1.0f,
        aCol, 0, rowsA,
        bCol, 0, colsA,
        0.0f,
        cCol, 0, rowsA
      )

      val result = new Array[Float](rowsA * colsB)
      i = 0
      while i < rowsA do
        var j = 0
        while j < colsB do
          result(i * colsB + j) = cCol(j * rowsA + i)
          j += 1
        i += 1

      MatData(result, rowsA, colsB)
    }
    inline def dot(other: Mat[T]): Mat[T] =
      inline erasedValue[T] match
        case _: Double =>
          multiplyDouble(other.asInstanceOf[Mat[Double]]).asInstanceOf[Mat[T]]

        case _: Float =>
          multiplyFloat(other.asInstanceOf[Mat[Float]]).asInstanceOf[Mat[T]]

        case _: BigDecimal =>
          multiplyBig(other.asInstanceOf[Mat[Big]]).asInstanceOf[Mat[T]]

        case _: Big =>
          multiplyBig(other.asInstanceOf[Mat[Big]]).asInstanceOf[Mat[T]]
  }

}

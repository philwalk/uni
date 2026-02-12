package uni.data

import scala.reflect.ClassTag

object Mat {
  // Opaque type wraps flat array with dimensions
  opaque type Mat[T] = MatData[T]
  
  private case class MatData[T](
    data: Array[T],
    rows: Int,
    cols: Int
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
        result(i) = m.data(i * m.cols + c)
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
      System.arraycopy(m.data, r * m.cols, result, 0, m.cols)
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
          val srcRow = rowSeq(i)
          val srcCol = colSeq(j)
          result(i * newCols + j) = m(srcRow, srcCol)
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
     * Transpose (short form)
     */
    def T: Mat[T] = transpose
    
    /** 
     * NumPy: m.transpose() 
     * Transpose (long form)
     */
    def transpose: Mat[T] = {
      val result = Array.ofDim[T](m.size)
      var i = 0
      while (i < m.rows) {
        var j = 0
        while (j < m.cols) {
          result(j * m.rows + i) = m.data(i * m.cols + j)
          j += 1
        }
        i += 1
      }
      MatData(result, m.cols, m.rows)
    }
    
    /** 
     * NumPy: m.reshape(rows, cols) 
     * Reshape matrix (must preserve total size)
     */
    def reshape(rows: Int, cols: Int): Mat[T] = {
      require(rows * cols == m.size, 
        s"Cannot reshape ${m.rows}×${m.cols} (size ${m.size}) to ${rows}×${cols} (size ${rows*cols})")
      MatData(m.data.clone(), rows, cols)
    }
    
    /** 
     * NumPy: m.flatten() 
     * Return flattened copy as 1D array
     */
    def flatten: Array[T] = m.data.clone()
    
    /** 
     * NumPy: m.copy() 
     * Return deep copy
     */
    def copy: Mat[T] = MatData(m.data.clone(), m.rows, m.cols)
    
    /**
     * NumPy: m.ravel()
     * Return flattened view as row vector (creates copy in our case)
     */
    def ravel: Mat[T] = MatData(m.data.clone(), 1, m.size)
  
  // ============================================================================
  // Arithmetic Operations (NumPy-aligned)
  // ============================================================================
  
  extension [T: ClassTag](m: Mat[T])
    /** 
     * NumPy: m + n 
     * Element-wise addition
     */
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
    
    /** 
     * NumPy: m + scalar 
     * Add scalar to all elements
     */
    def +(scalar: T)(using num: Numeric[T]): Mat[T] = {
      val result = Array.ofDim[T](m.size)
      var i = 0
      while (i < m.size) {
        result(i) = num.plus(m.data(i), scalar)
        i += 1
      }
      MatData(result, m.rows, m.cols)
    }
    
    /** 
     * NumPy: m - n 
     * Element-wise subtraction
     */
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
    
    /** 
     * NumPy: m - scalar 
     * Subtract scalar from all elements
     */
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
     * NumPy: m * n (element-wise multiplication)
     * Note: In NumPy, * is element-wise. Use @ for matrix mult.
     * We use *:* for element-wise to match Breeze, * for matrix mult.
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
    
    /** 
     * NumPy: m * scalar 
     * Multiply all elements by scalar
     */
    def *(scalar: T)(using num: Numeric[T]): Mat[T] = {
      val result = Array.ofDim[T](m.size)
      var i = 0
      while (i < m.size) {
        result(i) = num.times(m.data(i), scalar)
        i += 1
      }
      MatData(result, m.rows, m.cols)
    }
    
    /** 
     * NumPy: m @ n or m.dot(n)
     * Matrix multiplication
     */
    def *(other: Mat[T])(using num: Numeric[T]): Mat[T] = {
      require(m.cols == other.rows, 
        s"Cannot multiply ${m.rows}×${m.cols} by ${other.rows}×${other.cols}")
      
      val result = Array.ofDim[T](m.rows * other.cols)
      
      var i = 0
      while (i < m.rows) {
        var j = 0
        while (j < other.cols) {
          var sum = num.zero
          var k = 0
          while (k < m.cols) {
            sum = num.plus(sum, num.times(
              m.data(i * m.cols + k),
              other.data(k * other.cols + j)
            ))
            k += 1
          }
          result(i * other.cols + j) = sum
          j += 1
        }
        i += 1
      }
      
      MatData(result, m.rows, other.cols)
    }
    
    /** 
     * NumPy: m.dot(n) 
     * Matrix multiplication (explicit form)
     */
    def dot(other: Mat[T])(using num: Numeric[T]): Mat[T] = m * other
    
    /** 
     * NumPy: m / scalar 
     * Divide all elements by scalar
     */
    def /(scalar: T)(using frac: Fractional[T]): Mat[T] = {
      val result = Array.ofDim[T](m.size)
      var i = 0
      while (i < m.size) {
        result(i) = frac.div(m.data(i), scalar)
        i += 1
      }
      MatData(result, m.rows, m.cols)
    }
    
    /** 
     * NumPy: -m 
     * Unary negation
     */
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
    def argmin(using ord: Ordering[T]): (Int, Int) = {
      val idx = m.data.indexOf(m.data.min)
      (idx / m.cols, idx % m.cols)
    }
    
    /** NumPy: m.argmax() - index of maximum element as (row, col) */
    def argmax(using ord: Ordering[T]): (Int, Int) = {
      val idx = m.data.indexOf(m.data.max)
      (idx / m.cols, idx % m.cols)
    }
  
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
    
    /** Filter and return indices where predicate is true */
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
      // Helper to get type name
      def typeName[T: ClassTag]: String =
        summon[ClassTag[T]].runtimeClass.getSimpleName match
          case "double"     => "Double"
          case "float"      => "Float"
          case "BigDecimal" => "Big"
          case other        => other
      
      if m.isEmpty then 
        s"Mat[$typeName]([], shape=(0, 0))"
      else
        // 1. Analyze data range
        val values = m.data
        val absMax = values.map(v => math.abs(frac.toDouble(v))).max
        val absMin = values.filter(frac.toDouble(_) != 0.0)
                           .map(v => math.abs(frac.toDouble(v)))
                           .minOption.getOrElse(0.0)
        
        // 2. Decide format based on range
        val fmt: Double => String =
          if absMax >= 1e6 || (absMin > 0 && absMin < 1e-4) then
            // Scientific notation for very large or very small
            d => f"$d%.4e"
          else
            // Find the actual precision needed by examining fractional parts
            val allInts = values.forall(v => frac.toDouble(v) == math.floor(frac.toDouble(v)))
            if allInts then
              // All whole numbers - no decimal places needed
              d => d.toLong.toString
            else
              // Find meaningful decimal places by checking spread of fractional parts
              val doubles = values.map(frac.toDouble)
              val spread = doubles.max - doubles.min
              val decPlaces = 
                if spread == 0.0 then 1                              // all same value
                else if spread >= 100 then 1                         // large spread, 1 decimal
                else if spread >= 10  then 2
                else if spread >= 1   then 3
                else if spread >= 0.1 then 4
                else if spread >= 0.01 then 5
                else 6                                               // small spread, more precision
              d => s"%.${decPlaces}f".format(d)

        // 3. Format all values, find max width for alignment
        val formatted = values.map(v => fmt(frac.toDouble(v)))
        val colWidth = formatted.map(_.length).max
        
        // 4. Render
        val sb = new StringBuilder
        sb.append(s"Mat[$typeName](\n")
        var i = 0
        while i < m.rows do
          sb.append("  [")
          var j = 0
          while j < m.cols do
            val s = formatted(i * m.cols + j)
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
  
  /** 
   * NumPy: np.zeros((rows, cols)) 
   * Create matrix filled with zeros
   */
  def zeros[T: ClassTag](rows: Int, cols: Int)(using frac: Fractional[T]): Mat[T] = {
    val data = Array.fill(rows * cols)(frac.zero)
    MatData(data, rows, cols)
  }
  
  /** NumPy: np.zeros(shape) where shape is tuple */
  def zeros[T: ClassTag](shape: (Int, Int))(using frac: Fractional[T]): Mat[T] = 
    zeros(shape._1, shape._2)
  
  /** 
   * NumPy: np.ones((rows, cols)) 
   * Create matrix filled with ones
   */
  def ones[T: ClassTag](rows: Int, cols: Int)(using frac: Fractional[T]): Mat[T] = {
    val data = Array.fill(rows * cols)(frac.one)
    MatData(data, rows, cols)
  }
  
  /** NumPy: np.ones(shape) where shape is tuple */
  def ones[T: ClassTag](shape: (Int, Int))(using frac: Fractional[T]): Mat[T] = 
    ones(shape._1, shape._2)
  
  /** 
   * NumPy: np.eye(n) 
   * Create n×n identity matrix
   */
  def eye[T: ClassTag](n: Int)(using frac: Fractional[T]): Mat[T] = {
    val data = Array.tabulate(n * n)(i => 
      if (i % (n + 1) == 0) frac.one else frac.zero
    )
    MatData(data, n, n)
  }
  
  /** 
   * NumPy: np.full((rows, cols), value) 
   * Create matrix filled with given value
   */
  def full[T: ClassTag](rows: Int, cols: Int, value: T): Mat[T] = {
    val data = Array.fill(rows * cols)(value)
    MatData(data, rows, cols)
  }
  
  /** NumPy: np.full(shape, value) where shape is tuple */
  def full[T: ClassTag](shape: (Int, Int), value: T): Mat[T] = 
    full(shape._1, shape._2, value)
  
  /** 
   * NumPy: np.arange(stop) 
   * Create column vector [0, 1, ..., stop-1]
   */
  def arange[T: ClassTag](stop: Int)(using frac: Fractional[T]): Mat[T] = {
    val data = Array.tabulate(stop)(i => frac.fromInt(i))
    MatData(data, stop, 1)
  }
  
  /** 
   * NumPy: np.arange(start, stop) 
   * Create column vector [start, start+1, ..., stop-1]
   */
  def arange[T: ClassTag](start: Int, stop: Int)(using frac: Fractional[T]): Mat[T] = {
    val n = stop - start
    require(n > 0, s"stop ($stop) must be greater than start ($start)")
    val data = Array.tabulate(n)(i => frac.fromInt(start + i))
    MatData(data, n, 1)
  }
  
  /** 
   * NumPy: np.arange(start, stop, step) 
   * Create column vector with given step
   */
  def arange[T: ClassTag](start: Int, stop: Int, step: Int)(using frac: Fractional[T]): Mat[T] = {
    require(step != 0, "step cannot be zero")
    val n = ((stop - start).toDouble / step).ceil.toInt
    require(n > 0, s"Invalid range: start=$start, stop=$stop, step=$step")
    val data = Array.tabulate(n)(i => frac.fromInt(start + i * step))
    MatData(data, n, 1)
  }
  
  /** 
   * NumPy: np.linspace(start, stop, num) 
   * Create column vector with num evenly spaced values
   */
   def linspace[T: ClassTag](start: Double, stop: Double, num: Int = 50)(using frac: Fractional[T]): Mat[T] = {
      require(num > 0, "num must be positive")
      if num == 1 then
        MatData(Array(frac.fromInt(start.toInt)), 1, 1)
      else
        val step = (stop - start) / (num - 1)
        val data = Array.tabulate(num) { i =>
          val v = start + i * step
          // Convert Double to T via BigDecimal to preserve precision
          BigDecimal(v) match
            case bd if summon[ClassTag[T]].runtimeClass == classOf[Double]     => v.asInstanceOf[T]
            case bd if summon[ClassTag[T]].runtimeClass == classOf[Float]      => v.toFloat.asInstanceOf[T]
            case bd if summon[ClassTag[T]].runtimeClass == classOf[BigDecimal] => bd.asInstanceOf[T]
            case _                                                              => frac.fromInt(v.toInt)
        }
        MatData(data, num, 1)
    }
  
  /** 
   * Create matrix from flat array with explicit dimensions
   * Usage: Mat(2, 3, Array(1, 2, 3, 4, 5, 6))
   */
  def apply[T: ClassTag](rows: Int, cols: Int, data: Array[T]): Mat[T] = {
    require(data.length == rows * cols, 
      s"Data length ${data.length} != $rows × $cols")
    MatData(data, rows, cols)
  }
  
  /** 
   * Handle Unit () as empty matrix
   * Usage: Mat(()) creates 0×0 matrix
   */
  def apply[T: ClassTag](unit: Unit): Mat[T] = {
    MatData(Array.ofDim[T](0), 0, 0)
  }
  
  /** 
   * Handle single scalar value - promote to 1×1 matrix
   * Usage: Mat(42) creates 1×1 matrix containing 42
   * This handles the (x) case since (x) is just x, not Tuple1(x)
   */
   def apply[T: ClassTag](value: T): Mat[T] =
      MatData(Array(value), 1, 1)
  
  /** 
   * NumPy: np.array([...]) - alias for apply
   * NOT implemented, equivalent to `apply` and misleading.
   */
  // def array[T: ClassTag](tuples: Tuple*)(using frac: Fractional[T]): Mat[T] = apply(tuples*)
  
  /** 
   * NumPy: np.array([[1, 2], [3, 4]]) 
   * Create matrix from tuple literals
   * Usage: Mat((1, 2), (3, 4))
   */
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
            case n: Int          => frac.fromInt(n)       // promote Int literals ← missing!
            case n: Double       => n.asInstanceOf[T]
            case n: Float        => n.asInstanceOf[T]
            case n: BigDecimal   => n.asInstanceOf[T]
            case v: T @unchecked => v
            case other           => throw new IllegalArgumentException(s"Unsupported type: ${other.getClass.getName}")
          j += 1
        i += 1
      
      MatData(data, rows, cols)
  }

  
  def apply(value: Double): Mat[Double]                     = MatData(Array(value), 1, 1)
  def apply(value: Big): Mat[Big]                           = MatData(Array(value), 1, 1)

  /** 
   * Workaround for single scalar value (since (x) is not a tuple)
   * Usage: Mat.single(42) creates 1×1 matrix
   */
  def single[T: ClassTag](value: T): Mat[T] = {
    val data = Array(value)
    MatData(data, 1, 1)
  }
  
  /** 
   * Create matrix from sequence of scalars (column vector)
   * Usage: Mat.fromSeq(Seq(1, 2, 3)) creates 3×1 column vector
   * Workaround for: Mat(1, 2, 3) which would require varargs overload
   */
  def fromSeq[T: ClassTag](values: Seq[T]): Mat[T] = {
    if (values.isEmpty)
      empty[T]
    else
      MatData(values.toArray, values.length, 1)
  }
  
  /** 
   * Create row vector from varargs (alternative to row())
   * Usage: Mat.of(1, 2, 3) creates 1×3 row vector
   */
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
  
  /** Create empty matrix */
  def empty[T: ClassTag]: Mat[T] = MatData(Array.ofDim[T](0), 0, 0)
  
  /** 
   * Create row vector from values
   * Usage: Mat.row(1, 2, 3) creates 1×3 matrix
   */
  def row[T: ClassTag](values: T*): Mat[T] = {
    MatData(values.toArray, 1, values.length)
  }
  
  /** 
   * Create column vector from values
   * Usage: Mat.col(1, 2, 3) creates 3×1 matrix
   */
  def col[T: ClassTag](values: T*): Mat[T] = {
    MatData(values.toArray, values.length, 1)
  }
}

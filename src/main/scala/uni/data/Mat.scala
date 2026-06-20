package uni.data

import scala.reflect.ClassTag
import scala.compiletime.{erasedValue, summonFrom}
import uni.io.FileOps.*

import uni.data.Big.Big
// rows/cols/shape/shapes/size/isEmpty/transposed/isContiguous/apply are exported
// from VecExts.scala: E161 requires all overloads of a name (Mat[T] and CVec/RVec
// variants) in the same top-level definition group.
export Mat.{Mat, `::`, `~^`, ColsView, RowsView, CVec, RVec, Vec, RowVec, ColVec}
export MatD.leastSquares

object Mat {
  self =>

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
  opaque type CVec[T] <: Mat[T] = Mat[T]    // column vector (n×1)
  opaque type RVec[T] <: Mat[T] = Mat[T]    // row vector (1×n)

  // Cast bridges for the package-level CVec/RVec companions (VecExts.scala).
  // Only inside object Mat are CVec[T]/RVec[T] transparent (= Mat[T]); the
  // companions live at package level so their TASTY signatures keep the
  // opaque types (declared here, return types would dealias to Mat[T]).
  private[data] def mkCVec[T](m: Mat[T]): CVec[T] = m
  private[data] def mkRVec[T](m: Mat[T]): RVec[T] = m

  extension [T](m: Mat[T])
    // public extension methods:
    def rows: Int = asData._rows
    def cols: Int = asData._cols
    def shape: (Int, Int) = (asData._rows, asData._cols)
    def shapes: (Int, Int) = shape
    def size: Int = asData._rows * asData._cols
    def isEmpty: Boolean = asData._rows == 0 || asData._cols == 0
    def transposed: Boolean   = asData._transposed
    def isContiguous: Boolean =
      !transposed && rs == cols && cs == 1

    // Standard 2D access using strides and offset
    inline def at(r: Int, c: Int): T = 
      underlying(offset + r * rs + c * cs)

    // Flat access: direct for contiguous layouts, otherwise routed through
    // stride-aware 2D access via (i / cols, i % cols)
    inline def at(i: Int): T =
      if isContiguous then underlying(offset + i)
      else at(i / cols, i % cols)

    private def asData: MatData[T] = m.asInstanceOf[MatData[T]]
    private[data] def tdata: Array[T]       = asData._tdata
    private[data] def underlying: Array[T]  = asData._tdata
    private[data] def rs: Int               = asData._rs
    private[data] def cs: Int               = asData._cs
    private[data] def offset: Int           = asData._offset

    /** Returns a lazy iterator over all elements in row-major order */
    def iterator: Iterator[T] = new Iterator[T] {
      private var i = 0
      private val limit = m.rows * m.cols
      def hasNext: Boolean = i < limit
      def next(): T = 
        val row = i / m.cols
        val col = i % m.cols
        val value = m(row, col)
        i += 1
        value
    }

    /** Predicate check that short-circuits as soon as the condition is met */
    def exists(p: T => Boolean): Boolean = iterator.exists(p)

    /** Checks if any element is NaN (Double/Float NaN, or the BigNaN sentinel,
     *  whose Numeric[Big].toDouble is defined to return Double.NaN) */
    def containsNaN(using num: Numeric[T]): Boolean =
      exists(v => num.toDouble(v).isNaN)

    def saveCSV(filePath: uni.Path, sep: String = ",", nanAs: String = "NaN"): Unit = {
      withFileWriter(filePath) { writer =>
        for (i <- 0 until m.rows) {
          val row = for (j <- 0 until m.cols) yield {
            m(i, j).asInstanceOf[Any] match {
              // IEEE 754 Floating Point Special Cases
              case d: Double if d.isNaN => nanAs
              case d: Double if d.isPosInfinity => "Inf"
              case d: Double if d.isNegInfinity => "-Inf"
              case f: Float  if f.isNaN => nanAs
              case f: Float  if f.isPosInfinity => "Inf"
              case f: Float  if f.isNegInfinity => "-Inf"

              // Big Types — BigNaN honors nanAs exactly like Double/Float NaN
              case BigNaN         => nanAs
              case bd: BigDecimal => bd.bigDecimal.toPlainString // Prevents 1E+10 formatting
              case bi: BigInt     => bi.toString

              // Fallback for everything else (Int, Boolean, etc.)
              case null  => ""
              case other => java.lang.String.valueOf(other)
            }
          }
          writer.print(s"${row.mkString(sep)}\n")
        }
      }
    }

    def writeCsv(filePath: uni.Path, sep: String = ","): Unit = m.saveCSV(filePath, sep)
    def writeCsv(filePath: String): Unit                      = m.saveCSV(uni.Paths.get(filePath))

  extension (m: Mat[Big])
    def hasNaN: Mat[Boolean] = m.map(_ == BigNaN)

  private object Internal {
    class MatData[T] private[Internal](
      private[Mat] val _tdata: Array[T],
      private[Mat] val _rows: Int,
      private[Mat] val _cols: Int,
      private[Mat] val _transposed: Boolean = false,
      private[Mat] val _offset: Int = 0,
      private[Mat] val _rs: Int = -1,     // row stride; 'cols' for standard row-major
      private[Mat] val _cs: Int = -1,     // col stride; 1 for standard row-major
    ) {
    override def toString: String = {
      val componentType = _tdata.getClass.getComponentType
      val isBool = componentType == java.lang.Boolean.TYPE

      val typeName = componentType match {
        case java.lang.Double.TYPE  => "Double"
        case java.lang.Boolean.TYPE => "Boolean"
        case java.lang.Float.TYPE   => "Float"
        case c if c == classOf[BigDecimal] => "Big"
        case other => other.getSimpleName
      }

      val isBig = typeName == "Big" || typeName == "BigDecimal"

      formatMatrix(
        _tdata, _rows, _cols, _offset, _rs, _cs,
        typeName,
        toDouble = {
          case b: Boolean => if (b) 1.0 else 0.0
          case d: Double  => d
          case n: Number  => n.doubleValue()
          case _          => 0.0
        },
        mkString = {
          // This ensures we get "true" / "false" strings
          case BigNaN => "NaN"
          case bool: Boolean => bool.toString 
          case bigd: BigDecimal => bigd.toString 
          case other      => other.toString
        },
        // If it's Boolean, we don't want "%g" (which turns true into 1.00000)
        fmt = if (isBool || isBig) None else Some("%g")
      )
    }
  }

    private[data] def create[T: ClassTag](
      _tdata: Array[T],
      _rows: Int,
      _cols: Int,
      _transposed: Boolean = false,
      _offset: Int = 0, // added in phase 2
      _rs: Int = -1, // rowStride
      _cs: Int = -1, // colStride
    ): Mat[T] = {
      // Negative stride arguments mean "derive the standard stride for this orientation"
      val actualRs = if (_rs >= 0) _rs else (if _transposed then 1 else _cols)
      val actualCs = if (_cs >= 0) _cs else (if _transposed then _rows else 1)

      // must be the ONLY place the private constructor is used!
      val m = new MatData(_tdata, _rows, _cols, _transposed, _offset, actualRs, actualCs)

      // Layout guard: fragmented layouts are materialized to a clean copy so the
      // rest of the library only ever sees standard or simple-offset strides
      if m.isWeirdLayout then m.matCopy else m
    }

    // 'Internal.create' is the ONLY way to create a Mat.
    @annotation.unused
    private[uni] def createTestView[T: ClassTag](
      _tdata: Array[T], _rows: Int, _cols: Int, _t: Boolean, _offset: Int, _rs: Int, _cs: Int
    ): Mat[T] = new MatData(_tdata, _rows, _cols, _t, _offset, _rs, _cs)

    /** Pure view: swap dims/strides, flip transposed flag. No ClassTag needed (no allocation). */
    private[Mat] def transposeView[T](m: MatData[T]): Mat[T] =
      new MatData(m._tdata, m._cols, m._rows, !m._transposed, m._offset, m._cs, m._rs)
  }

  // The 'Mat.create' that the rest of your app uses
  // just forwards to the Internal gatekeeper.
  // This is the only public way to create a Mat.
  def create[T: ClassTag](tdata: Array[T], rows: Int, cols: Int): Mat[T] =
    Internal.create(tdata, rows, cols, false, 0, -1, -1)

  /** For tests and internal callers that need custom strides/offset. */
  private[data] def createView[T: ClassTag](
      tdata: Array[T],
      rows: Int,
      cols: Int,
      transposed: Boolean = false,
      offset: Int = 0,
      rs: Int = -1,
      cs: Int = -1,
  ): Mat[T] = Internal.create(tdata, rows, cols, transposed, offset, rs, cs)

  /** ClassTag-free transpose view: swaps dims/strides, flips transposed flag.
   *  Used by VecExts and fromMat so those callers need 0 total using params. */
  private[data] def matTransposeOf[T](m: Mat[T]): Mat[T] =
    Internal.transposeView(m.asInstanceOf[Internal.MatData[T]])

  /** Slice sentinel: m(::, 0), m(0, ::). Aliases scala.collection.immutable.::
   *  so that exporting it to uni.data doesn't shadow the standard list extractor. */
  val `::` = scala.collection.immutable.`::`

  /** Broadcast-axis sentinel: m(::, *) → ColsView; m(*, ::) → RowsView.
   *  Mirrors Breeze's X(::, *).map(f) / X(*, ::).map(f) idiom. */
  object `*`

  /** Returned by m(::, *); call .map(f) to transform each column independently. */
  final class ColsView[T: ClassTag](val m: Mat[T]):
    def map(f: ColVec[T] => ColVec[T]): Mat[T] = m.mapCols(f)
    // Normalize other to rows×1; accepts either rows×1 or 1×rows orientation
    private def asColVec(other: Mat[T]): Mat[T] =
      if other.cols == 1 then other else other.T
    def -(other: Mat[T])(using Numeric[T]): Mat[T]    = m - asColVec(other)
    def +(other: Mat[T])(using Numeric[T]): Mat[T]    = m + asColVec(other)
    def *(other: Mat[T])(using Numeric[T]): Mat[T]    = m * asColVec(other)
    def /(other: Mat[T])(using Fractional[T]): Mat[T] = m / asColVec(other)
    def foreach(f: CVec[T] => Unit): Unit =
      var j = 0
      while j < m._cols do
        val col = Array.ofDim[T](m._rows)
        var i = 0; while i < m._rows do { col(i) = m.at(i, j); i += 1 }
        f(Mat.create(col, m._rows, 1))
        j += 1

  /** Returned by m(*, ::); call .map(f) to transform each row independently. */
  final class RowsView[T: ClassTag](val m: Mat[T]):
    def map(f: RowVec[T] => RowVec[T]): Mat[T] = m.mapRows(f)
    // Normalize other to 1×cols; accepts either 1×cols or cols×1 orientation
    private def asRowVec(other: Mat[T]): Mat[T] =
      if other.rows == 1 then other else other.T
    def -(other: Mat[T])(using Numeric[T]): Mat[T]    = m - asRowVec(other)
    def +(other: Mat[T])(using Numeric[T]): Mat[T]    = m + asRowVec(other)
    def *(other: Mat[T])(using Numeric[T]): Mat[T]    = m * asRowVec(other)
    def /(other: Mat[T])(using Fractional[T]): Mat[T] = m / asRowVec(other)
    def foreach(f: RVec[T] => Unit): Unit =
      var i = 0
      while i < m._rows do
        val row = Array.ofDim[T](m._cols)
        var j = 0; while j < m._cols do { row(j) = m.at(i, j); j += 1 }
        f(Mat.create(row, 1, m._cols))
        i += 1

  // Crossover is higher on macOS/Accelerate (JNI overhead amortises later) than on Windows/Linux+OpenBLAS.
  // Mac measured: square Double=1728, thin=768. Windows/Linux: square=216, thin=384.
  private lazy val isMacOS = System.getProperty("os.name", "").toLowerCase.startsWith("mac")
  private lazy val blasThreshold:     Long = System.getProperty("uni.mat.blasThreshold",     if isMacOS then "1728" else "216").toLong
  private lazy val blasThinThreshold: Long = System.getProperty("uni.mat.blasThinThreshold", if isMacOS then "768"  else "384").toLong

  // Fork/join overhead dominates parallelSetAll / parallel streams below this size;
  // run sequentially for small arrays.
  private final val ParallelThreshold = 4096

  // bcastRows uses IntStream.parallel(), whose pipeline/spliterator/task setup
  // floor (~100µs measured) is far higher than parallelSetAll's. Parallelizing a
  // broadcast only pays once the sequential cost clears that floor — empirically
  // ~64K elements (below it, e.g. a 100×100 normalize, the stream setup makes it
  // SLOWER). Keep a dedicated, higher threshold so medium broadcasts stay serial.
  private final val BcastParallelThreshold = 1 << 16   // 65536

  /** The single guard for every Double fast path: a plain contiguous Mat[Double]
   *  with no view offset, whose backing array is exactly its logical data.
   *  One definition keeps per-site copies from drifting. */
  private[data] inline def fastD[T](m: Mat[T])(using ct: ClassTag[T]): Boolean =
    ct.runtimeClass == classOf[Double]
      && m.isContiguous && m.offset == 0 && m.tdata.length == m.rows * m.cols

  /** Fast-path guard for binary ops: both operands directly addressable.
   *  `other` needs no length check — with offset 0 and standard strides its
   *  logical data occupies the front of the backing array. */
  /** Looser left-operand guard: `m` only needs to BE Double (any layout — views,
   *  transposes, offsets). `fastBinOp` reads `m` through the stride equation, so a
   *  row/col view (e.g. `Xn(t, ::)`) no longer falls back to the boxed binOp path. */
  private[data] inline def fastDL[T](@annotation.unused m: Mat[T])(using ct: ClassTag[T]): Boolean =
    ct.runtimeClass == classOf[Double]
  private inline def fastD2L[T](m: Mat[T], other: Mat[T])(using ct: ClassTag[T]): Boolean =
    fastDL(m) && other.isContiguous && other.offset == 0

  /** In-place primitive LU decomposition (partial pivoting) of a row-major n×n
   *  Double array. Returns (pivot indices, swap count). Shared by the Double
   *  fast paths of luDecompose/determinant/inverse so they avoid the per-element
   *  Fractional[T] boxing (both the erased `Array[T]` access and `frac.*`). */
  private def luDecomposeD(lu: Array[Double], n: Int): (Array[Int], Int) =
    val pivots = Array.range(0, n)
    var swaps  = 0
    var i = 0
    while i < n do
      var maxRow = i
      var maxAbs = math.abs(lu(i * n + i))
      var k = i + 1
      while k < n do
        val v = math.abs(lu(k * n + i))
        if v > maxAbs then { maxAbs = v; maxRow = k }
        k += 1
      if maxRow != i then
        var c = 0
        while c < n do
          val tmp = lu(i * n + c); lu(i * n + c) = lu(maxRow * n + c); lu(maxRow * n + c) = tmp
          c += 1
        val tp = pivots(i); pivots(i) = pivots(maxRow); pivots(maxRow) = tp
        swaps += 1
      val pivot = lu(i * n + i)
      if pivot == 0.0 then throw ArithmeticException("Matrix is singular or nearly singular")
      var r = i + 1
      while r < n do
        val factor = lu(r * n + i) / pivot
        lu(r * n + i) = factor
        var c = i + 1
        while c < n do
          lu(r * n + c) = lu(r * n + c) - factor * lu(i * n + c)
          c += 1
        r += 1
      i += 1
    (pivots, swaps)

  /** Materialize a Mat[Double] (any layout) into a fresh row-major Double array. */
  private def toFlatD[T](m: Mat[T], n: Int): Array[Double] =
    val a   = m.tdata.asInstanceOf[Array[Double]]
    val off = m.offset; val rs = m.rs; val cs = m.cs
    val out = new Array[Double](n * n)
    var i = 0
    while i < n do
      var j = 0
      while j < n do { out(i * n + j) = a(off + i * rs + j * cs); j += 1 }
      i += 1
    out

  /** Fill `out` with f(i); parallel for large arrays, sequential below ParallelThreshold. */
  private[data] def fillD(out: Array[Double])(f: Int => Double): Unit =
    if out.length >= ParallelThreshold then
      java.util.Arrays.parallelSetAll(out, i => f(i))
    else
      var i = 0
      while i < out.length do
        out(i) = f(i)
        i += 1

  /** Run `rowFn(r, r*cols)` for each row 0..rows, in parallel above
   *  ParallelThreshold (by total element count), sequential below. Rows write
   *  disjoint output slices with no reduction, so the result is bit-identical
   *  regardless of execution order. Used to parallelize the broadcast branches
   *  of fastBinOp (row/col-vector broadcasts), which were otherwise single-
   *  threaded no matter the size. */
  private[data] inline def bcastRows(rows: Int, cols: Int)(inline rowFn: (Int, Int) => Unit): Unit =
    if rows.toLong * cols >= BcastParallelThreshold then
      java.util.stream.IntStream.range(0, rows).parallel().forEach(r => rowFn(r, r * cols))
    else
      var r = 0
      while r < rows do { rowFn(r, r * cols); r += 1 }

  /** Sum of a(from until until) with 8 independent accumulators: a single-accumulator
   *  loop is serialized by FP-add latency (~4 cycles); independent chains let the CPU
   *  run adds in parallel and reach memory bandwidth. (HotSpot does not auto-vectorize
   *  FP reductions, so this is the fastest portable scalar form.) */
  private def sumRange(a: Array[Double], from: Int, until: Int): Double =
    var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0
    var s4 = 0.0; var s5 = 0.0; var s6 = 0.0; var s7 = 0.0
    var i = from
    val limit = until - 7
    while i < limit do
      s0 += a(i);     s1 += a(i + 1); s2 += a(i + 2); s3 += a(i + 3)
      s4 += a(i + 4); s5 += a(i + 5); s6 += a(i + 6); s7 += a(i + 7)
      i += 8
    var s = ((s0 + s1) + (s2 + s3)) + ((s4 + s5) + (s6 + s7))
    while i < until do
      s += a(i)
      i += 1
    s

  /** Sum of a Double array; parallel chunked for large arrays. Chunk boundaries are
   *  fixed by length (not scheduling), so results are deterministic run-to-run.
   *  Note: plain summation, not DoubleStream's compensated summation — last-ulp
   *  rounding may differ from pre-0.14 results, but it benchmarks ~2× faster
   *  (NumPy-class reduction throughput). */
  private def sumD(a: Array[Double]): Double =
    val n = a.length
    if n < ParallelThreshold then sumRange(a, 0, n)
    else
      val chunks   = math.min(Runtime.getRuntime.availableProcessors, math.max(n / ParallelThreshold, 1))
      val step     = (n + chunks - 1) / chunks
      val partials = new Array[Double](chunks)
      java.util.stream.IntStream.range(0, chunks).parallel().forEach { c =>
        val from = c * step
        val until = math.min(from + step, n)
        if from < until then partials(c) = sumRange(a, from, until)
      }
      var s = 0.0
      var c = 0
      while c < chunks do
        s += partials(c)
        c += 1
      s

  /** Returns m if already BLAS-safe (offset=0, standard strides), else a fresh contiguous copy. */
  private def blasReady(m: Mat[Double]): Mat[Double] =
    if m.isStandardContiguous && m.offset == 0 then m
    else
      val rows = m.rows; val cols = m.cols
      val arr  = new Array[Double](rows * cols)
      var i = 0; while i < rows do { var j = 0; while j < cols do { arr(i*cols+j) = m(i,j); j+=1 }; i+=1 }
      Mat.create(arr, rows, cols)

  private def blasReadyF(m: Mat[Float]): Mat[Float] =
    if m.isStandardContiguous && m.offset == 0 then m
    else
      val rows = m.rows; val cols = m.cols
      val arr  = new Array[Float](rows * cols)
      var i = 0; while i < rows do { var j = 0; while j < cols do { arr(i*cols+j) = m(i,j); j+=1 }; i+=1 }
      Mat.create(arr, rows, cols)

  private val netlib = dev.ludovic.netlib.blas.BLAS.getInstance()
  // JNIBLAS on Linux may be backed by the slow reference Fortran BLAS (libblas3) when system
  // OpenBLAS is absent. A 64×64 timing probe distinguishes it: OpenBLAS ~0.01ms, reference ~1ms.
  // F2JBLAS / Java11BLAS are always slow. VectorBLAS and JNIBLAS+OpenBLAS are fast.
  private val netlibIsFast: Boolean =
    val name = netlib.getClass.getName
    if name.endsWith("F2JBLAS") || name.endsWith("Java11BLAS") then false
    else if name.endsWith("JNIBLAS") && sys.props("os.name").toLowerCase.contains("linux") then
      val n = 64
      val a = new Array[Double](n * n)
      val b = new Array[Double](n * n)
      val c = new Array[Double](n * n)
      netlib.dgemm("N", "N", n, n, n, 1.0, b, 0, n, a, 0, n, 0.0, c, 0, n) // warmup
      val t0 = System.nanoTime()
      netlib.dgemm("N", "N", n, n, n, 1.0, b, 0, n, a, 0, n, 0.0, c, 0, n)
      (System.nanoTime() - t0) < 500_000L // < 0.5ms → OpenBLAS; ≥ 0.5ms → reference BLAS
    else true

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
    if summon[ClassTag[U]].runtimeClass == classOf[Double] then
      // Unboxed fast path: primitive result array + atD reads (honour view strides).
      // Indexed while (not `for mat <- matrices`) avoids the foreach closure and the
      // IntRef boxing of the captured colOffset var.
      val ms  = matrices.toIndexedSeq
      val n   = ms.length
      val out = new Array[Double](rows * totalCols)
      var i = 0
      while i < rows do
        var colOffset = 0
        var k = 0
        while k < n do
          val mat = ms(k).asInstanceOf[Mat[Double]]
          val mc  = mat.cols
          var j = 0
          while j < mc do
            out(i * totalCols + colOffset + j) = mat.atD(i, j)
            j += 1
          colOffset += mc
          k += 1
        i += 1
      Mat.create(out, rows, totalCols).asInstanceOf[Mat[U]]
    else
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
  def diag[T: ClassTag](values: Array[T], rows: Int, cols: Int)(using frac: Fractional[T]): Mat[T] = {
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
  // Unboxed element read for Mat[Double]. The generic Mat[T].apply reads m.tdata,
  // typed Array[T] — for abstract T that erases to java.lang.Object, so every
  // element goes through ScalaRuntime.array_apply and boxes a java.lang.Double.
  // `atD` reads the backing double[] directly via the stride equation (correct for
  // views/transposes, so no contiguity guard needed) and stays primitive. It is a
  // distinct name on purpose: overloading `apply`/`at` in this more-specific
  // Mat[Double] clause would shadow the generic clause's rich apply/at overload
  // set (m(::, 0), m(range, ::), v(mask), …) for MatD receivers.
  extension (m: Mat[Double])
    private[uni] inline def atD(r: Int, c: Int): Double =
      m.tdata.asInstanceOf[Array[Double]](m.offset + r * m.rs + c * m.cs)

  // not directly related to exponentiation, but needed
  // to support the usage in the above comment
  // (to left-multiply a matrix by a scalar).
  extension (scalar: Double)
    def *(m: Mat[Double]): Mat[Double] = m * scalar
    def +(m: Mat[Double]): Mat[Double] = m + scalar
    def -(m: Mat[Double]): Mat[Double] = m.map(scalar - _)

  extension (base: Double)
    def ~^(exponent: Double): Double = Math.pow(base, exponent)

  // ~^ is element-wise power (NumPy ** semantics): m ~^ 0 and m ~^ 0.0 are all-ones.
  // Separate blocks because only the Double overload needs MatElem (fromDouble/nan).
  extension [T: ClassTag](m: Mat[T])(using frac: Fractional[T])
    def ~^(exponent: Int): Mat[T] = m.power(exponent)
  extension [T: ClassTag](m: Mat[T])(using frac: Fractional[T], elem: MatElem[T])
    def ~^(exponent: Double): Mat[T] = m.power(exponent)

  extension [T: ClassTag](m: Mat[T])
    /** Filters rows based on a predicate function */
    def filterRows(p: Mat[T] => Boolean): Mat[T] = {
      val keep = (0 until m.rows).filter(r => p(m(r, ::)))
      if keep.isEmpty then
        Mat.create(Array.empty[T], 0, m.cols)
      else
        // Stride-aware element copy: m may be a transposed or offset view,
        // so a flat System.arraycopy of underlying storage is not valid here.
        val newData = new Array[T](keep.length * m.cols)
        var idx = 0
        keep.foreach { r =>
          var j = 0
          while j < m.cols do
            newData(idx) = m(r, j)
            idx += 1
            j += 1
        }
        Mat.create(newData, keep.length, m.cols)
    }

    def head(n: Int): Mat[T] = m(0 until math.min(n, m.rows), ::)
    def tail(n: Int): Mat[T] = m(math.max(0, m.rows - n) until m.rows, ::)

  // Global RNG state. @volatile makes setSeed visible across threads, and every
  // generation operation captures the reference once so a concurrent setSeed
  // cannot mix two generators within one fill. NumPyRNG itself is stateful and
  // NOT thread-safe: for bit-exact NumPy reproducibility, seed and draw from a
  // single thread (same contract as np.random's global generator).
  private[data] lazy val defaultRNG: NumPyRNG = new NumPyRNG(0)
  @volatile private[data] var globalRNG: NumPyRNG = defaultRNG


  /** Set random seed matching NumPy's np.random.seed() */
  def setSeed(seed: Long): Unit =
    globalRNG = new NumPyRNG(seed)

  def nextRandLong: Long = globalRNG.nextLong()
  def nextRandInt: Long = globalRNG.nextInt() & 0xFFFFFFFFL
  def nextRandDouble: Double = globalRNG.nextDouble()

  /** NumPy: np.random.rand(rows, cols) - uniform [0, 1) */
  def rand(rows: Int, cols: Int): Mat[Double] = {
    val rng  = globalRNG
    val data = Array.fill(rows * cols)(rng.nextDouble())
    Mat.create(data, rows, cols)
  }

  /** NumPy: np.random.randn(n) - n×1 column vector, standard normal */
  def randn(n: Int): CVec[Double] =
    val rng = globalRNG
    Mat.create(Array.fill(n)(rng.randn()), n, 1)

  /** Breeze: rnorm(n) alias */
  def rnorm(n: Int): CVec[Double] = randn(n)

  /** NumPy: np.random.randn(rows, cols) - standard normal */
  def randn(rows: Int, cols: Int): Mat[Double] = {
    // Primitive loop, not Array.fill (whose by-name element boxes each draw).
    // Sequential fill preserves RNG call order → seeded reproducibility.
    val rng  = globalRNG
    val n    = rows * cols
    val data = new Array[Double](n)
    var i = 0
    while i < n do { data(i) = rng.randn(); i += 1 }
    Mat.create(data, rows, cols)
  }

  /** NumPy: np.random.normal(mean, std, size=(rows, cols)) */
  def normal(mean: Double, std: Double, rows: Int, cols: Int): Mat[Double] = {
    val rng  = globalRNG
    val n    = rows * cols
    val data = new Array[Double](n)
    var i = 0
    while i < n do { data(i) = mean + std * rng.randn(); i += 1 }
    create(data, rows, cols)
  }

  /** NumPy: np.random.uniform(low, high, (rows, cols)) */
  def uniform(low: Double, high: Double, rows: Int, cols: Int): Mat[Double] = {
    val rng  = globalRNG
    val n    = rows * cols
    val data = new Array[Double](n)
    var i = 0
    while i < n do { data(i) = rng.uniform(low, high); i += 1 }
    Mat.create(data, rows, cols)
  }

  /** NumPy: np.random.randint(low, high) - single integer */
  def randint(low: Int, high: Int): Int = {
    low + globalRNG.nextBoundedInt(high - low)
  }

  /** NumPy: np.random.randint(low, high, (rows, cols)) */
  def randint(low: Int, high: Int, rows: Int, cols: Int): Mat[Int] = {
    val rng  = globalRNG
    val data = Array.fill(rows * cols)(low + rng.nextBoundedInt(high - low))
    Mat.create(data, rows, cols)
  }

  // ============================================================================
  // Core Properties (NumPy-aligned)
  // ============================================================================
  
  extension [T](@annotation.unused m: Mat[T])
    def foreach(f: T => Unit): Unit = {
      if (m.isContiguous) {
        // Fast path for contiguous data
        var i = m.offset
        val end = m.offset + m.rows * m.cols * m.cs
        while (i < end) {
          f(m.tdata(i))
          i += m.cs
        }
      } else {
        // General case with proper indexing
        var r = 0
        while (r < m.rows) {
          var c = 0
          while (c < m.cols) {
            f(apply(r, c))
            c += 1
          }
          r += 1
        }
      }
    }

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

    // 2. Stride-aware reshape
    def reshape(newRows: Int, newCols: Int)(using ct: ClassTag[T]): Mat[T] = {
      require(newRows * newCols == m.rows * m.cols,
        s"Cannot reshape ${m.rows}x${m.cols} to ${newRows}x${newCols}")

      if m.isContiguous then
        Internal.create(m.tdata, newRows, newCols, false, m.offset, newCols, 1)
      else
        // If it's a slice or transposed, we must force a contiguous copy first
        m.toContiguous.reshape(newRows, newCols)
    }

    // 3. Helper to force a matrix into a standard contiguous layout
    def toContiguous(using ct: ClassTag[T]): Mat[T] = {
      // 1. Check if already contiguous to avoid work
      if m.isContiguous && m.offset == 0 then
        m
      else
        // 2. Pre-calculate total size to ensure it's positive
        val total = m.rows * m.cols
        val newData = new Array[T](Math.max(0, total))
        
        // 3. Use local vals for rows/cols to avoid repeated field access 
        //    which might be volatile or computed
        val rows = m.rows
        val cols = m.cols
        
        var i = 0
        while (i < rows) {
          var j = 0
          while (j < cols) {
            // Use the local 'cols' for the stride calculation
            newData(i * cols + j) = m(i, j)
            j += 1
          }
          i += 1
        }
        
        // 4. Create the new matrix with standard strides
        Mat.create(newData, rows, cols)
    }

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

    // row mutator (scalar)
    def update(row: Int, cols: ::.type, value: T): Unit =
      var j = 0
      while j < m.cols do
        m(row, j) = value
        j += 1

    // col mutator (scalar)
    def update(rows: ::.type, col: Int, value: T): Unit =
      var i = 0
      while i < m.rows do
        m(i, col) = value
        i += 1

    /** m(row, ::) = other — assign row vector (1×N) or col vector (N×1) to a single row.
     *  NumPy: m[row, :] = arr  (Breeze: m(row, ::).t := vec) */
    def update(row: Int, cols: ::.type, other: Mat[T]): Unit =
      val r = if row < 0 then m.rows + row else row
      require(r >= 0 && r < m.rows, s"Row index $r out of bounds [0, ${m.rows})")
      require(other.rows == 1 && other.cols == m.cols || other.rows == m.cols && other.cols == 1,
        s"shape mismatch: row has ${m.cols} cols, source is ${other.rows}×${other.cols}")
      if other.rows == 1 then
        for c <- 0 until m.cols do m(r, c) = other(0, c)
      else
        for c <- 0 until m.cols do m(r, c) = other(c, 0)

    /** m(::, col) = other — assign col vector (N×1) or row vector (1×N) to a single column.
     *  NumPy: m[:, col] = arr  (Breeze: m(::, col) := vec) */
    def update(rows: ::.type, col: Int, other: Mat[T]): Unit =
      val c = if col < 0 then m.cols + col else col
      require(c >= 0 && c < m.cols, s"Column index $c out of bounds [0, ${m.cols})")
      require(other.cols == 1 && other.rows == m.rows || other.rows == 1 && other.cols == m.rows,
        s"shape mismatch: col has ${m.rows} rows, source is ${other.rows}×${other.cols}")
      if other.cols == 1 then
        for r <- 0 until m.rows do m(r, c) = other(r, 0)
      else
        for r <- 0 until m.rows do m(r, c) = other(0, r)

    /** Update selected rows (NumPy: m[indices, :] = value) */
    def update(rowIndices: Array[Int], cols: ::.type, other: Mat[T]): Unit = {
      require(rowIndices.length == other.rows && m.cols == other.cols,
        s"Shape mismatch: need ${rowIndices.length}x${m.cols}, got ${other.rows}x${other.cols}")

      var i = 0
      while (i < rowIndices.length) {
        val r = rowIndices(i)
        var c = 0
        while (c < m.cols) {
          m(r, c) = other(i, c)
          c += 1
        }
        i += 1
      }
    }

    /** Update selected columns (NumPy: m[:, indices] = value) */
    def update(rows: ::.type, colIndices: Array[Int], other: Mat[T]): Unit = {
      require(m.rows == other.rows && colIndices.length == other.cols,
        s"Shape mismatch: need ${m.rows}x${colIndices.length}, got ${other.rows}x${other.cols}")

      var r = 0
      while (r < m.rows) {
        var i = 0
        while (i < colIndices.length) {
          val c = colIndices(i)
          m(r, c) = other(r, i)
          i += 1
        }
        r += 1
      }
    }

    /** Update selected rows and columns (NumPy: m[row_idx, col_idx] = value) */
    def update(rowIndices: Array[Int], colIndices: Array[Int], other: Mat[T]): Unit = {
      require(rowIndices.length == other.rows && colIndices.length == other.cols,
        s"Shape mismatch: need ${rowIndices.length}x${colIndices.length}, got ${other.rows}x${other.cols}")

      var i = 0
      while (i < rowIndices.length) {
        val r = rowIndices(i)
        var j = 0
        while (j < colIndices.length) {
          val c = colIndices(j)
          m(r, c) = other(i, j)
          j += 1
        }
        i += 1
      }
    }

    /** Update selected rows with scalar (NumPy: m[indices, :] = scalar) */
    def update(rowIndices: Array[Int], cols: ::.type, value: T): Unit = {
      var i = 0
      while (i < rowIndices.length) {
        val r = rowIndices(i)
        var c = 0
        while (c < m.cols) {
          m(r, c) = value
          c += 1
        }
        i += 1
      }
    }

    /** Update selected columns with scalar (NumPy: m[:, indices] = scalar) */
    def update(rows: ::.type, colIndices: Array[Int], value: T): Unit = {
      var r = 0
      while (r < m.rows) {
        var i = 0
        while (i < colIndices.length) {
          val c = colIndices(i)
          m(r, c) = value
          i += 1
        }
        r += 1
      }
    }

  // ============================================================================
  // Indexing (NumPy-aligned with negative index support)
  // ============================================================================
  extension [T](m: Mat[T])(using @scala.annotation.unused ct: ClassTag[T]) {
    // True when the strided layout is fragmented (major stride exceeds the
    // leading dimension), i.e. the view skips over backing-array elements.
    // Internal.create materializes such views into a contiguous copy.
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
      Mat.create(newData, m.rows, m.cols)
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

      // 2. Bounds check (IllegalArgumentException via require, like all other
      // argument/bounds validation in Mat)
      require(rStart >= 0 && rStart + newRows <= m.rows &&
              cStart >= 0 && cStart + newCols <= m.cols,
        s"Slice $rows, $cols out of bounds for ${m.rows}x${m.cols}")

      // 3. The Stride Magic: Calculate the new physical offset
      // The new starting point is the old offset plus the
      // jump to the first element of the slice.
      val newOffset = m.offset + (rStart * m.rs) + (cStart * m.cs)

      // 4. Build the view with existing strides (rs, cs) and the new offset;
      // Internal.create materializes it into a copy if the layout is fragmented.
      Internal.create(m.underlying, newRows, newCols, m.transposed, newOffset, m.rs, m.cs)
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

        Internal.create(m.underlying, targetRows, targetCols, m.transposed, m.offset, newRS, newCS)
    }

    def zipMap[U, V: ClassTag](other: Mat[U])(f: (T, U) => V): Mat[V] = {
      require(m.rows == other.rows && m.cols == other.cols,
        s"Dimension mismatch: (${m.rows}x${m.cols}) vs (${other.rows}x${other.cols})")

      val len = m.rows * m.cols
      if ct.runtimeClass == classOf[Double]
         && summon[ClassTag[V]].runtimeClass == classOf[Double]
         && other.tdata.isInstanceOf[Array[Double]]
         && m.isContiguous && m.offset == 0 && m.tdata.length == len
         && other.isContiguous && other.offset == 0 && other.tdata.length == len then
        // Unboxed (Double, Double)→Double fast path (both operands contiguous).
        val a = m.tdata.asInstanceOf[Array[Double]]
        val b = other.tdata.asInstanceOf[Array[Double]]
        val g = f.asInstanceOf[(Double, Double) => Double]
        val out = new Array[Double](len)
        var i = 0
        while i < len do { out(i) = g(a(i), b(i)); i += 1 }
        Mat.create(out, m.rows, m.cols).asInstanceOf[Mat[V]]
      else
        val res = new Array[V](len)
        var i = 0
        while i < len do { res(i) = f(m.at(i), other.at(i)); i += 1 }
        Mat.create(res, m.rows, m.cols)
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

    /** Double fast paths shared by +, -, *:* (and /): same-shape (parallel via
     *  fillD), 1×N row broadcast, and N×1 column broadcast. None when shapes
     *  need general broadcasting — caller falls back to the boxed binOp.
     *  Only valid when fastD2L(m, other) already holds. */
    private def fastBinOp(other: Mat[T])(f: (Double, Double) => Double): Option[Mat[T]] =
      val a    = m.tdata.asInstanceOf[Array[Double]]
      val rows = m.rows; val cols = m.cols
      if m.isContiguous && m.offset == 0 && a.length == rows * cols then
        // Hot path: `m` occupies the front of its backing array, indexed linearly.
        if other.rows == rows && other.cols == cols && other.tdata.length == rows * cols then
          val b   = other.tdata.asInstanceOf[Array[Double]]
          val out = Array.ofDim[Double](rows * cols)
          fillD(out)(i => f(a(i), b(i)))
          Some(Mat.create(out, rows, cols).asInstanceOf[Mat[T]])
        else if other.rows == 1 && other.cols == cols && other.tdata.length == cols then
          // Broadcast 1×N: combine the same row vector with every row
          val b   = other.tdata.asInstanceOf[Array[Double]]
          val out = Array.ofDim[Double](rows * cols)
          bcastRows(rows, cols) { (r, base) =>
            var c = 0
            while c < cols do { out(base + c) = f(a(base + c), b(c)); c += 1 }
          }
          Some(Mat.create(out, rows, cols).asInstanceOf[Mat[T]])
        else if other.rows == rows && other.cols == 1 && other.tdata.length == rows then
          // Broadcast N×1: combine each row's scalar with that row (e.g. m - m.mean(axis=1))
          val b   = other.tdata.asInstanceOf[Array[Double]]
          val out = Array.ofDim[Double](rows * cols)
          bcastRows(rows, cols) { (r, base) =>
            val s = b(r); var c = 0
            while c < cols do { out(base + c) = f(a(base + c), s); c += 1 }
          }
          Some(Mat.create(out, rows, cols).asInstanceOf[Mat[T]])
        else None
      else
        // `m` is a Double view (strided/offset, e.g. a row slice). Read it through the
        // stride equation; `other` and `out` stay contiguous (indexed r*cols + c).
        val off = m.offset; val rs = m.rs; val cs = m.cs
        if other.rows == rows && other.cols == cols && other.tdata.length == rows * cols then
          val b   = other.tdata.asInstanceOf[Array[Double]]
          val out = Array.ofDim[Double](rows * cols)
          bcastRows(rows, cols) { (r, base) =>
            var c = 0
            while c < cols do { out(base + c) = f(a(off + r * rs + c * cs), b(base + c)); c += 1 }
          }
          Some(Mat.create(out, rows, cols).asInstanceOf[Mat[T]])
        else if other.rows == 1 && other.cols == cols && other.tdata.length == cols then
          val b   = other.tdata.asInstanceOf[Array[Double]]
          val out = Array.ofDim[Double](rows * cols)
          bcastRows(rows, cols) { (r, base) =>
            var c = 0
            while c < cols do { out(base + c) = f(a(off + r * rs + c * cs), b(c)); c += 1 }
          }
          Some(Mat.create(out, rows, cols).asInstanceOf[Mat[T]])
        else if other.rows == rows && other.cols == 1 && other.tdata.length == rows then
          val b   = other.tdata.asInstanceOf[Array[Double]]
          val out = Array.ofDim[Double](rows * cols)
          bcastRows(rows, cols) { (r, base) =>
            val s = b(r); var c = 0
            while c < cols do { out(base + c) = f(a(off + r * rs + c * cs), s); c += 1 }
          }
          Some(Mat.create(out, rows, cols).asInstanceOf[Mat[T]])
        else None

    def +(other: Mat[T])(using num: Numeric[T]): Mat[T] =
      if fastD2L(m, other) then m.fastBinOp(other)(_ + _).getOrElse(m.binOp(other)(num.plus))
      else m.binOp(other)(num.plus)

    def -(other: Mat[T])(using num: Numeric[T]): Mat[T] =
      if fastD2L(m, other) then m.fastBinOp(other)(_ - _).getOrElse(m.binOp(other)(num.minus))
      else m.binOp(other)(num.minus)

    def *:*(other: Mat[T])(using num: Numeric[T]): Mat[T] =
      if fastD2L(m, other) then m.fastBinOp(other)(_ * _).getOrElse(m.binOp(other)(num.times))
      else m.binOp(other)(num.times)

    def hadamard(other: Mat[T])(using num: Numeric[T]): Mat[T] = m *:* other

    def unary_-(using num: Numeric[T]): Mat[T] =
      if fastD(m)
      then
        val a   = m.tdata.asInstanceOf[Array[Double]]
        val out = Array.ofDim[Double](a.length)
        fillD(out)(i => -a(i))
        Mat.create(out, m.rows, m.cols).asInstanceOf[Mat[T]]
      else
        val result = new Array[T](m.rows * m.cols)
        var r = 0; var idx = 0
        while r < m.rows do
          var c = 0
          while c < m.cols do
            result(idx) = num.negate(m(r, c))
            idx += 1; c += 1
          r += 1
        Mat.create(result, m.rows, m.cols)

    // ============================================================================
    // Shape Manipulation
    // ============================================================================
    def T: Mat[T] = transpose
    /** O(1) transpose — flips flag and swaps dims, no data movement */
    def transpose: Mat[T] = Internal.transposeView(m.asInstanceOf[Internal.MatData[T]])

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

    /** Return a fresh independent copy of this matrix (Breeze: m.copy). */
    def copy: Mat[T] = Mat.create(m.flatten, m.rows, m.cols)

    /** Alias for [[flatten]]: returns a fresh row-major Array[T]. */
    def toArray: Array[T] = m.flatten

    /** Extract the single element of a 1×1 matrix (NumPy: `.item()`).
     *  Throws IllegalArgumentException if the matrix is not 1×1. */
    def item: T =
      require(m.rows == 1 && m.cols == 1, s"item requires 1×1 matrix, got ${m.rows}×${m.cols}")
      m(0, 0)

    // Raw backing array — for views this is the parent's storage; never expose publicly
    // (callers wanting a flat copy use toArray/flatten).
    private[data] def data: Array[T] = m.asInstanceOf[MatData[T]].tdata

    /**
     * NumPy: m.ravel()
     * Return flattened view as row vector in logical order
     */
    def ravel: RVec[T] = Mat.create(flatten, 1, m.size)

    // ============================================================================
    // Arithmetic Operations
    // ============================================================================

    /** NumPy: m + scalar - Add scalar to all elements */
    def +(scalar: T)(using num: Numeric[T]): Mat[T] =
      if fastD(m)
      then
        val s = scalar.asInstanceOf[Double]
        val a = m.tdata.asInstanceOf[Array[Double]]
        val out = Array.ofDim[Double](a.length)
        fillD(out)(i => a(i) + s)
        Mat.create(out, m.rows, m.cols).asInstanceOf[Mat[T]]
      else
        val result = new Array[T](m.rows * m.cols)
        var r = 0; var idx = 0
        while r < m.rows do
          var c = 0
          while c < m.cols do
            result(idx) = num.plus(m(r, c), scalar)
            idx += 1; c += 1
          r += 1
        Mat.create(result, m.rows, m.cols)

    def -(scalar: T)(using num: Numeric[T]): Mat[T] =
      if fastD(m)
      then
        val s = scalar.asInstanceOf[Double]
        val a = m.tdata.asInstanceOf[Array[Double]]
        val out = Array.ofDim[Double](a.length)
        fillD(out)(i => a(i) - s)
        Mat.create(out, m.rows, m.cols).asInstanceOf[Mat[T]]
      else
        val result = new Array[T](m.rows * m.cols)
        var r = 0; var idx = 0
        while r < m.rows do
          var c = 0
          while c < m.cols do
            result(idx) = num.minus(m(r, c), scalar)
            idx += 1; c += 1
          r += 1
        Mat.create(result, m.rows, m.cols)

    def *(scalar: T)(using num: Numeric[T]): Mat[T] =
      if fastD(m)
      then
        val s = scalar.asInstanceOf[Double]
        val a = m.tdata.asInstanceOf[Array[Double]]
        val out = Array.ofDim[Double](a.length)
        fillD(out)(i => a(i) * s)
        Mat.create(out, m.rows, m.cols).asInstanceOf[Mat[T]]
      else
        val result = new Array[T](m.rows * m.cols)
        var r = 0; var idx = 0
        while r < m.rows do
          var c = 0
          while c < m.cols do
            result(idx) = num.times(m(r, c), scalar)
            idx += 1; c += 1
          r += 1
        Mat.create(result, m.rows, m.cols)

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

    def sum(using num: Numeric[T]): T =
      summon[ClassTag[T]].runtimeClass match
        case c if c == classOf[Double] && m.isContiguous && m.offset == 0 =>
          // Fast path: parallel fork/join on JVM heap — no JNI copy, uses all cores.
          // Guard: tdata may be a parent array; only use when its length matches this view.
          val data = m.tdata.asInstanceOf[Array[Double]]
          if data.length == m.rows * m.cols then
            sumD(data).asInstanceOf[T]
          else
            var total = 0.0
            var i = 0
            while i < m.rows do
              var j = 0
              while j < m.cols do
                total += data(i * m.rs + j * m.cs)
                j += 1
              i += 1
            total.asInstanceOf[T]
        case _ =>
          // General path: strided views, non-Double types, offset slices.
          var total = num.zero
          var i = 0
          while i < m.rows do
            var j = 0
            while j < m.cols do
              total = num.plus(total, m(i, j))
              j += 1
            i += 1
          total

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

    def map[U: ClassTag](f: T => U): Mat[U] =
      if ct.runtimeClass == classOf[Double] && summon[ClassTag[U]].runtimeClass == classOf[Double] then
        // Unboxed Double→Double fast path. Reads via the stride equation (honours
        // views/offset); the cast `g` calls the specialized apply$mcDD$sp when the
        // caller's lambda is a monomorphic Double function (the common case).
        val a = m.tdata.asInstanceOf[Array[Double]]
        val g = f.asInstanceOf[Double => Double]
        val rows = m.rows; val cols = m.cols
        val off = m.offset; val rs = m.rs; val cs = m.cs
        val out = new Array[Double](rows * cols)
        var r = 0; var idx = 0
        while r < rows do
          var c = 0
          while c < cols do
            out(idx) = g(a(off + r * rs + c * cs)); idx += 1; c += 1
          r += 1
        Mat.create(out, rows, cols).asInstanceOf[Mat[U]]
      else
        // Allocate a fresh, contiguous array for the result
        val resData = new Array[U](m.rows * m.cols)
        var r = 0
        var idx = 0
        while r < m.rows do
          var c = 0
          while c < m.cols do
            // m(r, c) uses your stride/offset logic to find the REAL element
            resData(idx) = f(m(r, c))
            idx += 1
            c += 1
          r += 1
        Mat.create(resData, m.rows, m.cols)

    /** Apply a Double→Double function in parallel using the fork/join pool.
     *  Requires a contiguous, non-offset Mat[Double].
     *  This is 4–8× faster than map on multi-core hardware and has no boxing
     *  in the parallel kernel (DoubleStream.map uses DoubleUnaryOperator). */
    def mapParallel(f: Double => Double)(using ev: T =:= Double): Mat[Double] =
      require(m.isContiguous && m.offset == 0 && m.tdata.length == m.rows * m.cols,
        "mapParallel requires a contiguous, non-offset Mat[Double]")
      val src = m.tdata.asInstanceOf[Array[Double]]
      val out = java.util.Arrays.stream(src).parallel().map(x => f(x)).toArray
      Mat.create(out, m.rows, m.cols)

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
  }

  // ============================================================================
  // Slicing / views (no numeric constraint — works for any element type)
  // ============================================================================
  extension [T: ClassTag](m: Mat[T])
    /** m(::) - all elements; NumPy: m[:] */
    def apply(all: ::.type): Mat[T] = m(0 until m.rows, 0 until m.cols)

    /** m(0 until 2, ::) - range rows, all cols */
    def apply(rows: Range, cols: ::.type): Mat[T] =
      m(rows, 0 until m.cols)

    /** m(::, 0 until 2) - all rows, range cols */
    def apply(rows: ::.type, cols: Range): Mat[T] =
      m(0 until m.rows, cols)

    /** m(0, 0 until 2) - single row, range cols */
    def apply(row: Int, cols: Range): RVec[T] =
      m(row to row, cols)

    /** m(0 until 2, 0) - range rows, single col */
    def apply(rows: Range, col: Int): CVec[T] =
      m(rows, col to col)

    /**
     * NumPy: m[:, col]
     * Extract column as column vector
     */
    def apply(rows: ::.type, col: Int): CVec[T] = {
      val c = if col < 0 then m.cols + col else col
      require(c >= 0 && c < m.cols, s"Column index $c out of bounds")
      if fastDL(m) then
        val a   = m.tdata.asInstanceOf[Array[Double]]
        val off = m.offset; val rs = m.rs; val cs = m.cs
        val out = new Array[Double](m.rows)
        var i = 0
        while i < m.rows do { out(i) = a(off + i * rs + c * cs); i += 1 }
        Mat.create(out, m.rows, 1).asInstanceOf[Mat[T]]
      else
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
    def apply(row: Int, cols: ::.type): RVec[T] = {
      val r = if row < 0 then m.rows + row else row
      require(r >= 0 && r < m.rows, s"Row index $r out of bounds")
      if fastDL(m) then
        val a   = m.tdata.asInstanceOf[Array[Double]]
        val off = m.offset; val rs = m.rs; val cs = m.cs
        val out = new Array[Double](m.cols)
        var j = 0
        while j < m.cols do { out(j) = a(off + r * rs + j * cs); j += 1 }
        Mat.create(out, 1, m.cols).asInstanceOf[Mat[T]]
      else
        val result = Array.ofDim[T](m.cols)
        var j = 0
        while j < m.cols do
          result(j) = m(r, j)
          j += 1
        Mat.create(result, 1, m.cols)
    }

    /** Breeze: X(::, *) — returns a ColsView for per-column mapping via .map(f) */
    def apply(rows: ::.type, cols: `*`.type): ColsView[T] = ColsView(m)

    /** Breeze: X(*, ::) — returns a RowsView for per-row mapping via .map(f) */
    def apply(rows: `*`.type, cols: ::.type): RowsView[T] = RowsView(m)

    /** Named alternative to m(::, *) — avoids `*` import conflict when mixing with breeze.linalg.* */
    def eachCol: ColsView[T] = ColsView(m)

    /** Named alternative to m(*, ::) — avoids `*` import conflict when mixing with breeze.linalg.* */
    def eachRow: RowsView[T] = RowsView(m)

    /**
     * NumPy: m[rows, cols]
     * Rectangular slicing with Range support
     */
    def apply(rows: Range, cols: Range): Mat[T] = {
      val rowSeq = rows.toSeq
      val colSeq = cols.toSeq
      val newRows = rowSeq.length
      val newCols = colSeq.length
      // Double fast path: read the backing array primitively via the stride
      // equation (correct for views/transposes); generic m(r,c) would box every
      // element. rowSeq/colSeq are Ranges, so apply(i) returns an unboxed Int.
      if fastDL(m) then
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
        Mat.create(out, newRows, newCols).asInstanceOf[Mat[T]]
      else
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

  extension [T: ClassTag](m: Mat[T])(using frac: Fractional[T])
    def /(scalar: T): Mat[T] =
      if fastD(m)
      then
        val s   = scalar.asInstanceOf[Double]
        val a   = m.tdata.asInstanceOf[Array[Double]]
        val out = Array.ofDim[Double](a.length)
        fillD(out)(i => a(i) / s)
        Mat.create(out, m.rows, m.cols).asInstanceOf[Mat[T]]
      else
        val res = new Array[T](m.size)
        var r = 0; var idx = 0
        while r < m.rows do
          var c = 0
          while c < m.cols do
            res(idx) = frac.div(m(r, c), scalar)
            idx += 1; c += 1
          r += 1
        Mat.create(res, m.rows, m.cols)


  // ============================================================================
  // Factory Methods
  // ============================================================================
  // `Array.fill(n)(frac.zero/one)` evaluates the element by-name n times, boxing
  // each result. The Double fast paths allocate a primitive array directly:
  // `new Array[Double](n)` is already 0.0-filled, so `zeros` needs no fill.
  private inline def isDoubleCt[T](using ct: ClassTag[T]): Boolean =
    ct.runtimeClass == classOf[Double]

  def zeros[T: ClassTag](rows: Int, cols: Int)(using frac: Fractional[T]): Mat[T] =
    if isDoubleCt[T] then Mat.create(new Array[Double](rows * cols), rows, cols).asInstanceOf[Mat[T]]
    else Mat.create(Array.fill(rows * cols)(frac.zero), rows, cols)

  def zeros[T: ClassTag](shape: (Int, Int))(using frac: Fractional[T]): Mat[T] =
    zeros(shape._1, shape._2)

  def ones[T: ClassTag](rows: Int, cols: Int)(using frac: Fractional[T]): Mat[T] =
    if isDoubleCt[T] then
      val a = new Array[Double](rows * cols)
      java.util.Arrays.fill(a, 1.0)
      Mat.create(a, rows, cols).asInstanceOf[Mat[T]]
    else Mat.create(Array.fill(rows * cols)(frac.one), rows, cols)

  def ones[T: ClassTag](shape: (Int, Int))(using frac: Fractional[T]): Mat[T] =
    ones(shape._1, shape._2)

  def eye[T: ClassTag](n: Int, k: Int = 0)(using frac: Fractional[T]): Mat[T] = {
    if isDoubleCt[T] then
      val a = new Array[Double](n * n)   // already 0.0-filled
      var i = 0
      while i < n do
        val j = i + k
        if j >= 0 && j < n then a(i * n + j) = 1.0
        i += 1
      Mat.create(a, n, n).asInstanceOf[Mat[T]]
    else
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

  def arange[T: ClassTag](stop: Int)(using frac: Fractional[T]): CVec[T] =
    Mat.create(Array.tabulate(stop)(i => frac.fromInt(i)), stop, 1)

  def arange[T: ClassTag](start: Int, stop: Int)(using frac: Fractional[T]): CVec[T] = {
    val n = stop - start
    require(n > 0, s"stop ($stop) must be greater than start ($start)")
    Mat.create(Array.tabulate(n)(i => frac.fromInt(start + i)), n, 1)
  }

  def arange[T: ClassTag](start: Int, stop: Int, step: Int)(using frac: Fractional[T]): CVec[T] = {
    require(step != 0, "step cannot be zero")
    val n = ((stop - start).toDouble / step).ceil.toInt
    require(n > 0, s"Invalid range: start=$start, stop=$stop, step=$step")
    Mat.create(Array.tabulate(n)(i => frac.fromInt(start + i * step)), n, 1)
  }

  def arange[T: ClassTag](stop: Double)(using elem: MatElem[T]): CVec[T] =
    arange[T](0.0, stop, 1.0)

  def arange[T: ClassTag](start: Double, stop: Double)(using elem: MatElem[T]): CVec[T] =
    arange[T](start, stop, 1.0)

  def arange[T: ClassTag](start: Double, stop: Double, step: Double)(using elem: MatElem[T]): CVec[T] = {
    require(step != 0.0, "step cannot be zero")
    val n = math.max(0, math.ceil((stop - start) / step).toInt)
    Mat.create(Array.tabulate(n)(i => elem.fromDouble(start + i * step)), n, 1)
  }

  def linspace[T: ClassTag](start: Double, stop: Double, num: Int = 50)(using elem: MatElem[T]): CVec[T] = {
    require(num > 0, "num must be positive")
    if num == 1 then Mat.create(Array(elem.fromDouble(start)), 1, 1)
    else
      val step = (stop - start) / (num - 1)
      Mat.create(Array.tabulate(num)(i => elem.fromDouble(start + i * step)), num, 1)
  }
  def apply[T: ClassTag](rows: Int, cols: Int, data: Array[T]): Mat[T] = {
    require(data.length == rows * cols, s"Data length ${data.length} != $rows x $cols")
    Mat.create(data, rows, cols)
  }
  
  def apply[T: ClassTag](@annotation.unused unit: Unit): Mat[T] = Mat.create(Array.ofDim[T](0), 0, 0)
  def apply[T: ClassTag](value: T): Mat[T]   = Mat.create(Array(value), 1, 1)
  def apply[T: ClassTag](tuples: Tuple*)(using frac: Fractional[T], elem: MatElem[T]): Mat[T] = {
    val rows = tuples.length
    if rows == 0 then Mat.create(Array.ofDim[T](0), 0, 0)
    else
      // Convert every numeric element to T — a Float in a Mat[Double] tuple or a
      // Double in a Mat[Big] tuple must be converted, not cast (CCE/ArrayStoreException).
      val targetIsBig = summon[ClassTag[T]].runtimeClass == classOf[BigDecimal]
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
            case n: Long         => elem.fromDouble(n.toDouble)
            case n: Double       => elem.fromDouble(n)
            case n: Float        => elem.fromDouble(n.toDouble)
            case n: BigDecimal   => if targetIsBig then n.asInstanceOf[T] else elem.fromDouble(n.toDouble)
            case v: T @unchecked => v
            case other           => throw IllegalArgumentException(s"Unsupported type: ${other.getClass.getName}")
          j += 1
        i += 1
      Mat.create(data, rows, cols)
  }
  // Concrete-type single-value factories (unambiguous, no [T] required)
  def apply(value: Double): Mat[Double] = Mat.create[Double](Array(value), 1, 1)
  def apply(value: Big.Big): Mat[Big.Big]       = Mat.create[Big.Big](Array(value), 1, 1)

  /** Explicit 1x1 matrix factory */
  def single[T: ClassTag](value: T): Mat[T] = Mat.create(Array(value), 1, 1)

  /** Create column vector from sequence */
  def fromSeq[T: ClassTag](values: Seq[T]): CVec[T] =
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

  extension [T: ClassTag](m: Mat[T])
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

  /** The "NaN" sentinel value for type T — compile-time evidence via MatElem
   *  (previously returned BigNaN for any non-Double/Float T, a latent
   *  ClassCastException for e.g. Mat[Int]). */
  private[data] def nanFill[T](using elem: MatElem[T]): T = elem.nan

  // ============================================================================
  // Matrix Multiply + Linear Algebra (extension block)
  // ============================================================================
  extension [T](@annotation.unused m: Mat[T])(using @annotation.unused ct: ClassTag[T]) {

    // ---- Matrix multiply -----------------------------------------------
    inline def matmul(other: Mat[T]): Mat[T] = {
      if m.cols != other.rows then
        throw IllegalArgumentException(s"m.cols[${m.cols}] != other.rows[${other.rows}]")
      summonFrom {
        case _: (T =:= Big) =>
          multiplyBig(other.asInstanceOf[Mat[Big]]).asInstanceOf[Mat[T]]
        case _ =>
          inline erasedValue[T] match
            case _: Double =>
              val a = m.asInstanceOf[Mat[Double]]; val b = other.asInstanceOf[Mat[Double]]
              (if shouldUseBLAS(a, b) then multiplyDoubleBLAS(b) else multiplyDouble(b)).asInstanceOf[Mat[T]]
            case _: Float =>
              val a = m.asInstanceOf[Mat[Float]]; val b = other.asInstanceOf[Mat[Float]]
              (if shouldUseBLAS(a, b) then multiplyFloatBLAS(b) else multiplyFloat(b)).asInstanceOf[Mat[T]]
            case _: BigDecimal =>
              multiplyBig(other.asInstanceOf[Mat[Big]]).asInstanceOf[Mat[T]]
      }
    }

    inline def dot(other: Mat[T]): Mat[T] = m.matmul(other)

    // Non-inline matmul via ClassTag runtime dispatch.  Used from VecExts.scala
    // where `inline def *@` are NOT found in Scala 3 package-level extension
    // method lookup — only non-inline methods are indexed for dispatch.
    private[data] def matmulRt(other: Mat[T]): Mat[T] = {
      if m.cols != other.rows then
        throw IllegalArgumentException(s"m.cols[${m.cols}] != other.rows[${other.rows}]")
      ct.runtimeClass match
        case c if c == classOf[Double] =>
          val a = m.asInstanceOf[Mat[Double]]; val b = other.asInstanceOf[Mat[Double]]
          (if shouldUseBLAS(a, b) then a.multiplyDoubleBLAS(b) else a.multiplyDouble(b)).asInstanceOf[Mat[T]]
        case c if c == classOf[Float] =>
          val a = m.asInstanceOf[Mat[Float]]; val b = other.asInstanceOf[Mat[Float]]
          (if shouldUseBLAS(a, b) then a.multiplyFloatBLAS(b) else a.multiplyFloat(b)).asInstanceOf[Mat[T]]
        case _ =>
          m.asInstanceOf[Mat[Big]].multiplyBig(other.asInstanceOf[Mat[Big]]).asInstanceOf[Mat[T]]
    }

    // ---- Diagonal ------------------------------------------------------
    def diagonal: Array[T] = {
      val n = math.min(m.rows, m.cols)
      val result = Array.ofDim[T](n)
      var i = 0
      while i < n do { result(i) = m(i, i); i += 1 }
      result
    }

    // ---- L2 Norm (vector only) -----------------------------------------
    def norm(using frac: Fractional[T], elem: MatElem[T]): T = {
      require(m.cols == 1 || m.rows == 1,
        s"norm requires a vector (1xn or nx1), got ${m.shape}")
      if fastD(m)
      then
        val a = m.tdata.asInstanceOf[Array[Double]]
        var sumSq = 0.0; var i = 0
        while i < a.length do { val x = a(i); sumSq += x * x; i += 1 }
        math.sqrt(sumSq).asInstanceOf[T]
      else
        val flat = m.flatten
        var sumSq = frac.zero; var i = 0
        while i < flat.length do
          sumSq = frac.plus(sumSq, frac.times(flat(i), flat(i))); i += 1
        elem.sqrtT(sumSq)
    }

    // ---- LU Decomposition (internal) -----------------------------------
    // Returns (LU flat Mat, pivot indices, swap count)
    private def luDecompose(using frac: Fractional[T]): (Mat[T], Array[Int], Int) = {
      require(m.rows == m.cols, s"LU requires square matrix, got ${m.shape}")
      val n = m.rows
      if fastDL(m) then
        val lu = toFlatD(m, n)
        val (pivots, swaps) = luDecomposeD(lu, n)
        (Mat.create(lu, n, n).asInstanceOf[Mat[T]], pivots, swaps)
      else
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
      if fastDL(m) then
        // Primitive LU + forward/backward substitution, no Fractional[T] boxing.
        val lu = toFlatD(m, n)
        val (pivots, _) = luDecomposeD(lu, n)
        val result = new Array[Double](n * n)
        var col = 0
        while col < n do
          val x = new Array[Double](n)
          var i = 0
          while i < n do { x(i) = if pivots(i) == col then 1.0 else 0.0; i += 1 }
          i = 1
          while i < n do
            var k = 0
            while k < i do { x(i) = x(i) - lu(i * n + k) * x(k); k += 1 }
            i += 1
          i = n - 1
          while i >= 0 do
            var k = i + 1
            while k < n do { x(i) = x(i) - lu(i * n + k) * x(k); k += 1 }
            x(i) = x(i) / lu(i * n + i)
            i -= 1
          var row = 0
          while row < n do { result(row * n + col) = x(row); row += 1 }
          col += 1
        Mat.create(result, n, n).asInstanceOf[Mat[T]]
      else
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
    def qrDecomposition(using frac: Fractional[T], elem: MatElem[T]): (Mat[T], Mat[T]) = {
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
        val normV: T = elem.sqrtT(normSq)

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

    // ---- Eigenvalues ----------------------------------------------------
    // Double routes through LAPACK dgeev (exact, O(n³) once). Other element
    // types fall back to unshifted QR iteration, which is only suitable for
    // symmetric matrices with real eigenvalues; `iterations` applies to the
    // fallback only.
    def eigenvalues(iterations: Int = 500)(using frac: Fractional[T], elem: MatElem[T]): Array[T] = {
      require(m.rows == m.cols, s"eigenvalues requires square matrix, got ${m.shape}")
      if summon[ClassTag[T]].runtimeClass == classOf[Double] then
        m.asInstanceOf[Mat[Double]].eigenvaluesDouble.asInstanceOf[Array[T]]
      else
        var A: Mat[T] = m.matCopy

        // Can't use inline * here since T is abstract - dispatch via ClassTag
        def multiply(a: Mat[T], b: Mat[T]): Mat[T] =
          summon[ClassTag[T]].runtimeClass match
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

    /** Eigenvalues only, via LAPACK dgeev with jobvl = jobvr = 'N' (no eigenvector
     *  computation). Returns real parts; for symmetric input all imaginary parts
     *  are zero. */
    private[data] def eigenvaluesDouble: Array[Double] = {
      import org.bytedeco.openblas.global.openblas.*
      val md    = m.asInstanceOf[Mat[Double]]
      val n     = md.rows
      val aCopy = md.flatten  // row-major copy
      val wr  = Array.ofDim[Double](n)
      val wi  = Array.ofDim[Double](n)
      val vl  = Array.ofDim[Double](n * n)  // dummy buffers — system LAPACKE requires
      val vr  = Array.ofDim[Double](n * n)  // valid arrays even with jobv* = 'N'
      val info = LAPACKE_dgeev(
        LAPACK_ROW_MAJOR, 'N'.toByte, 'N'.toByte,
        n, aCopy, n, wr, wi, vl, n, vr, n
      )
      if info != 0 then
        throw ArithmeticException(s"LAPACKE_dgeev failed with info=$info")
      wr
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
      val minDim = a.rows min b.cols
      val ops    = a.rows.toLong * a.cols * b.cols
      ops >= (if minDim >= 6 then blasThreshold else blasThinThreshold)

    private[data] def multiplyDoubleBLAS(other: Mat[Double]): Mat[Double] =
      if Mat.netlibIsFast then multiplyDoubleNetlib(other) else multiplyDoubleOB(other)

    private def multiplyDoubleNetlib(other: Mat[Double]): Mat[Double] = {
      val am = Mat.blasReady(m.asInstanceOf[Mat[Double]])
      val bm = Mat.blasReady(other)
      val a = am.tdata.asInstanceOf[Array[Double]]
      val b = bm.tdata.asInstanceOf[Array[Double]]
      val rowsA = am.rows; val colsA = am.cols; val colsB = bm.cols
      val result = new Array[Double](rowsA * colsB)
      val transA = if am.transposed then "T" else "N"
      val transB = if bm.transposed then "T" else "N"
      val ldA = if am.transposed then am.rows else am.cols
      val ldB = if bm.transposed then bm.rows else bm.cols
      // row-major C=A*B  →  col-major dgemm: swap operands and trans flags
      Mat.netlib.dgemm(transB, transA, colsB, rowsA, colsA,
        1.0, b, 0, ldB, a, 0, ldA, 0.0, result, 0, colsB)
      Mat.create(result, rowsA, colsB)
    }

    private def multiplyDoubleOB(other: Mat[Double]): Mat[Double] = {
      import org.bytedeco.openblas.global.openblas._
      val am = Mat.blasReady(m.asInstanceOf[Mat[Double]])
      val bm = Mat.blasReady(other)
      val a = am.tdata.asInstanceOf[Array[Double]]
      val b = bm.tdata.asInstanceOf[Array[Double]]
      val rowsA = am.rows; val colsA = am.cols; val colsB = bm.cols
      val result = new Array[Double](rowsA * colsB)
      val transA = if am.transposed then CblasTrans else CblasNoTrans
      val transB = if bm.transposed then CblasTrans else CblasNoTrans
      val ldA = if am.transposed then am.rows else am.cols
      val ldB = if bm.transposed then bm.rows else bm.cols
      cblas_dgemm(CblasRowMajor, transA, transB, rowsA, colsB, colsA,
        1.0, a, ldA, b, ldB, 0.0, result, colsB)
      Mat.create(result, rowsA, colsB)
    }

    private[data] def multiplyFloatBLAS(other: Mat[Float]): Mat[Float] =
      if Mat.netlibIsFast then multiplyFloatNetlib(other) else multiplyFloatOB(other)

    private def multiplyFloatNetlib(other: Mat[Float]): Mat[Float] = {
      val am = Mat.blasReadyF(m.asInstanceOf[Mat[Float]])
      val bm = Mat.blasReadyF(other)
      val a = am.tdata.asInstanceOf[Array[Float]]
      val b = bm.tdata.asInstanceOf[Array[Float]]
      val rowsA = am.rows; val colsA = am.cols; val colsB = bm.cols
      val result = new Array[Float](rowsA * colsB)
      val transA = if am.transposed then "T" else "N"
      val transB = if bm.transposed then "T" else "N"
      val ldA = if am.transposed then am.rows else am.cols
      val ldB = if bm.transposed then bm.rows else bm.cols
      // row-major C=A*B  →  col-major sgemm: swap operands and trans flags
      Mat.netlib.sgemm(transB, transA, colsB, rowsA, colsA,
        1.0f, b, 0, ldB, a, 0, ldA, 0.0f, result, 0, colsB)
      Mat.create(result, rowsA, colsB)
    }

    private def multiplyFloatOB(other: Mat[Float]): Mat[Float] = {
      import org.bytedeco.openblas.global.openblas._
      val am = Mat.blasReadyF(m.asInstanceOf[Mat[Float]])
      val bm = Mat.blasReadyF(other)
      val a = am.tdata.asInstanceOf[Array[Float]]
      val b = bm.tdata.asInstanceOf[Array[Float]]
      val rowsA = am.rows; val colsA = am.cols; val colsB = bm.cols
      val result = new Array[Float](rowsA * colsB)
      val transA = if am.transposed then CblasTrans else CblasNoTrans
      val transB = if bm.transposed then CblasTrans else CblasNoTrans
      val ldA = if am.transposed then am.rows else am.cols
      val ldB = if bm.transposed then bm.rows else bm.cols
      cblas_sgemm(CblasRowMajor, transA, transB, rowsA, colsB, colsA,
        1.0f, a, ldA, b, ldB, 0.0f, result, colsB)
      Mat.create(result, rowsA, colsB)
    }

    def trace(using num: Numeric[T]): T =
      if fastD(m)
      then
        val a    = m.tdata.asInstanceOf[Array[Double]]
        val n    = math.min(m.rows, m.cols)
        val step = m.cols + 1
        var s = 0.0; var i = 0
        while i < n do { s += a(i * step); i += 1 }
        s.asInstanceOf[T]
      else
        diagonal.foldLeft(num.zero)(num.plus)

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
      // Fast path: contiguous Double array — no boxing, no Numeric dispatch
      if fastD(m)
      then
        val a = m.tdata.asInstanceOf[Array[Double]]
        val rows = m.rows; val cols = m.cols
        if axis == 0 then
          val result = Array.ofDim[Double](cols)
          var i = 0
          while i < rows do
            val base = i * cols; var j = 0
            while j < cols do { result(j) += a(base + j); j += 1 }
            i += 1
          Mat.create(result, 1, cols).asInstanceOf[Mat[T]]
        else
          val result = Array.ofDim[Double](rows)
          var i = 0
          while i < rows do
            val base = i * cols; var s = 0.0; var j = 0
            while j < cols do { s += a(base + j); j += 1 }
            result(i) = s; i += 1
          Mat.create(result, rows, 1).asInstanceOf[Mat[T]]
      else if axis == 0 then
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

    def mean(using frac: Fractional[T]): T =
      if m.rows == 0 || m.cols == 0 then frac.zero
      else if fastD(m)
      then
        val a = m.tdata.asInstanceOf[Array[Double]]
        (sumD(a) / (m.rows * m.cols)).asInstanceOf[T]
      else
        var total = frac.zero
        var r = 0
        while r < m.rows do
          var c = 0
          while c < m.cols do
            total = frac.plus(total, m(r, c)); c += 1
          r += 1
        frac.div(total, frac.fromInt(m.rows * m.cols))

    def mean(axis: Int)(using frac: Fractional[T]): Mat[T] = {
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      val s = m.sum(axis)
      val n = if axis == 0 then m.rows else m.cols
      s / frac.fromInt(n)
    }

    /** Sum across columns → n×1 column vector (one sum per row) */
    def rowSums(using num: Numeric[T]): CVec[T] = m.sum(1)
    /** Sum down rows → 1×n row vector (one sum per column) */
    def colSums(using num: Numeric[T]): RVec[T] = m.sum(0)
    /** Mean across columns → n×1 column vector (one mean per row) */
    def rowMeans(using frac: Fractional[T]): CVec[T] = m.mean(1)
    /** Mean down rows → 1×n row vector (one mean per column) */
    def colMeans(using frac: Fractional[T]): RVec[T] = m.mean(0)

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

    def sqrt(using elem: MatElem[T]): Mat[T] = m.map(elem.sqrtT)

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
      if fastD(m)
      then
        val a      = m.tdata.asInstanceOf[Array[Double]]
        val result = Array.ofDim[Double](m.rows * m.cols)
        if axis == 0 then
          var j = 0
          while j < m.cols do
            var acc = 0.0; var i = 0
            while i < m.rows do
              acc += a(i * m.cols + j); result(i * m.cols + j) = acc; i += 1
            j += 1
        else
          var i = 0
          while i < m.rows do
            var acc = 0.0; var j = 0
            while j < m.cols do
              acc += a(i * m.cols + j); result(i * m.cols + j) = acc; j += 1
            i += 1
        Mat.create(result, m.rows, m.cols).asInstanceOf[Mat[T]]
      else
        val result = Array.ofDim[T](m.rows * m.cols)
        if axis == 0 then
          var j = 0
          while j < m.cols do
            var acc = num.zero; var i = 0
            while i < m.rows do
              acc = num.plus(acc, m(i, j)); result(i * m.cols + j) = acc; i += 1
            j += 1
        else
          var i = 0
          while i < m.rows do
            var acc = num.zero; var j = 0
            while j < m.cols do
              acc = num.plus(acc, m(i, j)); result(i * m.cols + j) = acc; j += 1
            i += 1
        Mat.create(result, m.rows, m.cols)
    }

    // No-axis version: flatten then cumsum
    def cumsum(using num: Numeric[T]): Mat[T] =
      if fastD(m)
      then
        val a      = m.tdata.asInstanceOf[Array[Double]]
        val result = Array.ofDim[Double](a.length)
        var acc = 0.0; var i = 0
        while i < a.length do { acc += a(i); result(i) = acc; i += 1 }
        Mat.create(result, 1, a.length).asInstanceOf[Mat[T]]
      else
        val flat   = m.flatten
        val result = Array.ofDim[T](flat.length)
        var acc = num.zero; var i = 0
        while i < flat.length do
          acc = num.plus(acc, flat(i)); result(i) = acc; i += 1
        Mat.create(result, 1, flat.length)

    def cummax(axis: Int)(using ord: Ordering[T]): Mat[T] =
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      val result = Array.ofDim[T](m.rows * m.cols)
      if axis == 0 then
        var j = 0
        while j < m.cols do
          var acc = m(0, j); var i = 0
          while i < m.rows do
            if ord.gt(m(i, j), acc) then acc = m(i, j)
            result(i * m.cols + j) = acc; i += 1
          j += 1
      else
        var i = 0
        while i < m.rows do
          var acc = m(i, 0); var j = 0
          while j < m.cols do
            if ord.gt(m(i, j), acc) then acc = m(i, j)
            result(i * m.cols + j) = acc; j += 1
          i += 1
      Mat.create(result, m.rows, m.cols)

    def cummin(axis: Int)(using ord: Ordering[T]): Mat[T] =
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      val result = Array.ofDim[T](m.rows * m.cols)
      if axis == 0 then
        var j = 0
        while j < m.cols do
          var acc = m(0, j); var i = 0
          while i < m.rows do
            if ord.lt(m(i, j), acc) then acc = m(i, j)
            result(i * m.cols + j) = acc; i += 1
          j += 1
      else
        var i = 0
        while i < m.rows do
          var acc = m(i, 0); var j = 0
          while j < m.cols do
            if ord.lt(m(i, j), acc) then acc = m(i, j)
            result(i * m.cols + j) = acc; j += 1
          i += 1
      Mat.create(result, m.rows, m.cols)

    // 2. cov and corrcoef:
    // scala// NumPy: np.cov(m) - each ROW is a variable, each COL is an observation
    // Returns pxp covariance matrix where p = number of rows
    def cov(using frac: Fractional[T]): Mat[T] = {
      val p = m.rows  // number of variables
      val n = m.cols  // number of observations
      require(n > 1, "cov requires at least 2 observations (cols)")
      if fastD(m)
      then
        val a        = m.tdata.asInstanceOf[Array[Double]]
        val centered = Array.ofDim[Double](p * n)
        var i = 0
        while i < p do
          var s = 0.0; var j = 0
          while j < n do { s += a(i * n + j); j += 1 }
          val mu = s / n; j = 0
          while j < n do { centered(i * n + j) = a(i * n + j) - mu; j += 1 }
          i += 1
        val denom  = (n - 1).toDouble
        val result = Array.ofDim[Double](p * p)
        i = 0
        while i < p do
          var k = 0
          while k < p do
            var s = 0.0; var j = 0
            while j < n do { s += centered(i * n + j) * centered(k * n + j); j += 1 }
            result(i * p + k) = s / denom; k += 1
          i += 1
        Mat.create(result, p, p).asInstanceOf[Mat[T]]
      else
        // Subtract row means
        val means    = m.mean(1)  // px1
        val centered = Array.ofDim[T](p * n)
        var i = 0
        while i < p do
          var j = 0
          while j < n do
            centered(i * n + j) = frac.minus(m(i, j), means(i, 0)); j += 1
          i += 1
        // cov = C * C^T / (n-1)
        val denom  = frac.fromInt(n - 1)
        val result = Array.ofDim[T](p * p)
        i = 0
        while i < p do
          var j = 0
          while j < p do
            var s = frac.zero; var k = 0
            while k < n do
              s = frac.plus(s, frac.times(centered(i * n + k), centered(j * n + k))); k += 1
            result(i * p + j) = frac.div(s, denom); j += 1
          i += 1
        Mat.create(result, p, p)
    }

    def corrcoef(using frac: Fractional[T], elem: MatElem[T]): Mat[T] = {
      val c = m.cov
      val p = c.rows
      // stddevs = sqrt of diagonal of cov
      val std = Array.ofDim[T](p)
      var i = 0
      while i < p do
        std(i) = elem.sqrtT(c(i, i))
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

    /** Economy SVD (jobz 'S'): U is nRows×p, Vt is p×nCols, p = min(nRows, nCols).
     *  Full 'A' allocated nRows×nRows for U — O(n²) memory for tall-skinny inputs —
     *  and no caller (lstsq, pinv, matrixRank) ever read beyond the first p columns. */
    private[data] def svdDouble: (Mat[Double], Array[Double], Mat[Double]) = {
      import org.bytedeco.openblas.global.openblas.*
      val md    = m.asInstanceOf[Mat[Double]]
      val nRows = md.rows
      val nCols = md.cols
      val p     = math.min(nRows, nCols)
      val aCopy = md.flatten  // row-major, respects transposed flag
      val s     = Array.ofDim[Double](p)
      val u     = Array.ofDim[Double](nRows * p)
      val vt    = Array.ofDim[Double](p * nCols)

      val info = LAPACKE_dgesdd(
        LAPACK_ROW_MAJOR, 'S'.toByte,
        nRows, nCols,
        aCopy, nCols,   // lda = nCols for row-major
        s,
        u,  p,          // ldu  = p columns in economy U
        vt, nCols       // ldvt
      )
      if info != 0 then
        throw ArithmeticException(s"LAPACKE_dgesdd failed with info=$info")

      (Mat.create(u, nRows, p), s, Mat.create(vt, p, nCols))
    }

    // The Fractional evidence on svd/lstsq/matrixRank/eig/pinv/cholesky is unused at
    // runtime (Double-only kernels) but restricts the API to numeric element types.
    def svd(using @annotation.unused frac: Fractional[T]): (Mat[T], Array[T], Mat[T]) =
      summon[ClassTag[T]].runtimeClass match
        case c if c == classOf[Double] =>
          val (u, s, vt) = m.asInstanceOf[Mat[Double]].svdDouble
          (u.asInstanceOf[Mat[T]], s.asInstanceOf[Array[T]], vt.asInstanceOf[Mat[T]])
        case c =>
          throw UnsupportedOperationException(s"svd only supported for Double, got ${c.getName}")

    def lstsq(b: Mat[T])(using @annotation.unused frac: Fractional[T]): (Mat[T], Mat[T], Int, Array[T]) = {
      summon[ClassTag[T]].runtimeClass match
        case c if c == classOf[Double] =>
          val md   = m.asInstanceOf[Mat[Double]]
          val bd   = b.asInstanceOf[Mat[Double]]
          val nRows = md.rows
          val nCols = md.cols
          val nRhs  = bd.cols
          val p     = math.min(nRows, nCols)

          val (uMat, s, vtMat) = md.svdDouble
          // Extract flat row-major arrays from the economy-SVD Mat results
          // uMat  is nRows×p, row-major: uMat.underlying(r*p + c) = U[r,c]
          // vtMat is p×nCols, row-major: vtMat.underlying(r*nCols + c) = Vt[r,c]
          val u  = uMat.underlying   // Array[Double], nRows*p
          val vt = vtMat.underlying  // Array[Double], p*nCols

          // Rank = number of singular values above threshold
          val threshold = 1e-10 * s(0)
          val rank = s.count(_ > threshold)

          val result = Array.ofDim[Double](nCols * nRhs)

          var col = 0
          while col < nRhs do
            // Step 1: tmp = U^T * b[:,col]  (only the first p rows of U^T matter)
            // U^T[i,k] = U[k,i] = u(k*p + i)
            // tmp[i] = sum_k U[k,i] * b[k,col]
            val tmp = Array.ofDim[Double](p)
            var i = 0
            while i < p do
              var k = 0
              while k < nRows do
                tmp(i) += u(k * p + i) * bd(k, col)
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
            val diff = md.matmul(xMat) - bd
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
    // m.map is stride/offset-aware; mapping m.tdata directly would read the
    // parent array of a view (wrong elements, wrong length).
    def gt(other: T)(using ord: Ordering[T]): Mat[Boolean]  = m.map(ord.gt(_, other))

    def lt(other: T)(using ord: Ordering[T]): Mat[Boolean]  = m.map(ord.lt(_, other))

    def gte(other: T)(using ord: Ordering[T]): Mat[Boolean] = m.map(ord.gteq(_, other))

    def lte(other: T)(using ord: Ordering[T]): Mat[Boolean] = m.map(ord.lteq(_, other))

    def :==(other: T)(using ord: Ordering[T]): Mat[Boolean] = m.map(ord.equiv(_, other))

    def :!=(other: T)(using ord: Ordering[T]): Mat[Boolean] = m.map(!ord.equiv(_, other))

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

    // Operator aliases for the named comparisons. Equality stays :== / :!= —
    // == and != are defined on Any and cannot return Mat[Boolean].
    def >(other: T)(using ord: Ordering[T]): Mat[Boolean]  = m.gt(other)
    def <(other: T)(using ord: Ordering[T]): Mat[Boolean]  = m.lt(other)
    def >=(other: T)(using ord: Ordering[T]): Mat[Boolean] = m.gte(other)
    def <=(other: T)(using ord: Ordering[T]): Mat[Boolean] = m.lte(other)

    def >(other: Int)(using ord: Ordering[T], frac: Fractional[T]): Mat[Boolean]  = m.gt(other)
    def <(other: Int)(using ord: Ordering[T], frac: Fractional[T]): Mat[Boolean]  = m.lt(other)
    def >=(other: Int)(using ord: Ordering[T], frac: Fractional[T]): Mat[Boolean] = m.gte(other)
    def <=(other: Int)(using ord: Ordering[T], frac: Fractional[T]): Mat[Boolean] = m.lte(other)

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

    // matrix_rank:
    /** NumPy: np.linalg.matrix_rank(m) - rank via SVD singular value threshold */
    def matrixRank(tol: Double = -1.0)(using @annotation.unused frac: Fractional[T]): Int = {
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
    def norm(ord: String)(using frac: Fractional[T], elem: MatElem[T]): T = {
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
          elem.sqrtT(sumSq)
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
      m.map(x => frac.toDouble(x).isNaN)

    /** NumPy: np.isinf(m) */
    def isinf(using frac: Fractional[T]): Mat[Boolean] =
      m.map(x => frac.toDouble(x).isInfinite)

    /** NumPy: np.isfinite(m) */
    def isfinite(using frac: Fractional[T]): Mat[Boolean] =
      m.map(x => {
        val d = frac.toDouble(x)
        !d.isNaN && !d.isInfinite
      })

    /** NumPy: np.nan_to_num(m, nan=0.0, posinf=0.0, neginf=0.0) */
    def nanToNum(nan: Double = 0.0, posinf: Double = 0.0, neginf: Double = 0.0)(using frac: Fractional[T], elem: MatElem[T]): Mat[T] =
      m.map { (x: T) =>
        val d = frac.toDouble(x)
        elem.fromDouble(
          if d.isNaN then nan
          else if d.isPosInfinity then posinf
          else if d.isNegInfinity then neginf
          else d)
      }

    def fillna(value: T)(using frac: Fractional[T]): Mat[T] =
      m.map(x => if frac.toDouble(x).isNaN then value else x)

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

      val wr  = Array.ofDim[Double](n)
      val wi  = Array.ofDim[Double](n)
      val vl  = Array.ofDim[Double](n * n)  // dummy left eigenvectors (required by system LAPACKE even when jobvl='N')
      val vr  = Array.ofDim[Double](n * n)

      val info = LAPACKE_dgeev(
        LAPACK_ROW_MAJOR, 'N'.toByte, 'V'.toByte,
        n,
        aCopy, n,
        wr, wi,
        vl, n,    // left eigenvectors (not computed, but valid buffer for system LAPACKE)
        vr, n
      )
      if info != 0 then
        throw ArithmeticException(s"LAPACKE_dgeev failed with info=$info")

      (wr, wi, Mat.create(vr, n, n))
    }

    /*
     * returns (realParts, imagParts, eigenvectors) as separate arrays.
     */
    def eig(using @annotation.unused frac: Fractional[T]): (Array[T], Array[T], Mat[T]) = {
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
    def round(decimals: Int = 0)(using frac: Fractional[T], elem: MatElem[T]): Mat[T] = {
      val scale = math.pow(10.0, decimals)
      m.map((x: T) => elem.fromDouble(math.round(frac.toDouble(x) * scale).toDouble / scale))
    }

    /** NumPy: np.power(m, n) - element-wise x^n */
    def power(n: Double)(using frac: Fractional[T], elem: MatElem[T]): Mat[T] =
      // `map`'s Double fast path can't specialize this lambda (it compiles as
      // T=>T inside the generic extension), so go primitive directly. The stride
      // equation keeps it correct for views/transposes.
      if fastDL(m) then
        val a   = m.tdata.asInstanceOf[Array[Double]]
        val rows = m.rows; val cols = m.cols
        val off = m.offset; val rs = m.rs; val cs = m.cs
        val out = new Array[Double](rows * cols)
        var r = 0; var idx = 0
        while r < rows do
          var c = 0
          while c < cols do
            out(idx) = math.pow(a(off + r * rs + c * cs), n); idx += 1; c += 1
          r += 1
        Mat.create(out, rows, cols).asInstanceOf[Mat[T]]
      else
        m.map((x: T) => elem.fromDouble(math.pow(frac.toDouble(x), n)))

    /** Integer exponent overload - no Fractional needed */
    def power(n: Int)(using num: Numeric[T]): Mat[T] = {
      // argument error → IAE (UnsupportedOperationException is reserved for
      // unsupported element types)
      require(n >= 0, "negative integer power requires Fractional — use power(n: Double) or 1.0 / m")
      if fastDL(m) then
        val a   = m.tdata.asInstanceOf[Array[Double]]
        val rows = m.rows; val cols = m.cols
        val off = m.offset; val rs = m.rs; val cs = m.cs
        val out = new Array[Double](rows * cols)
        var r = 0; var idx = 0
        while r < rows do
          var c = 0
          while c < cols do
            // integer exponent by repeated multiply — matches the generic branch
            var result = 1.0; var k = 0
            val x = a(off + r * rs + c * cs)
            while k < n do { result = result * x; k += 1 }
            out(idx) = result; idx += 1; c += 1
          r += 1
        Mat.create(out, rows, cols).asInstanceOf[Mat[T]]
      else
        m.map((x: T) => {
          var result = num.one
          var k = 0
          while k < n do
            result = num.times(result, x)
            k += 1
          result
        })
    }

    // newaxis equivalents
    def toRowVec: RVec[T] = Mat.create(m.flatten, 1, m.size)
    def toColVec: CVec[T] = Mat.create(m.flatten, m.size, 1)

    // in-place scalar ops
    def :+=(scalar: T)(using num: Numeric[T]): Mat[T] = {
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          m(i, j) = num.plus(m(i, j), scalar)
          j += 1
        i += 1
      m
    }

    def :-=(scalar: T)(using num: Numeric[T]): Mat[T] = {
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          m(i, j) = num.minus(m(i, j), scalar)
          j += 1
        i += 1
      m
    }

    def :*=(scalar: T)(using num: Numeric[T]): Mat[T] = {
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          m(i, j) = num.times(m(i, j), scalar)
          j += 1
        i += 1
      m
    }

    def :/=(scalar: T)(using frac: Fractional[T]): Mat[T] = {
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          m(i, j) = frac.div(m(i, j), scalar)
          j += 1
        i += 1
      m
    }

    // Int overloads
    def :+=(scalar: Int)(using frac: Fractional[T]): Mat[T] = m.:+=(frac.fromInt(scalar))
    def :-=(scalar: Int)(using frac: Fractional[T]): Mat[T] = m.:-=(frac.fromInt(scalar))
    def :*=(scalar: Int)(using frac: Fractional[T]): Mat[T] = m.:*=(frac.fromInt(scalar))
    def :/=(scalar: Int)(using frac: Fractional[T]): Mat[T] = m.:/=(frac.fromInt(scalar))

    // in-place Mat ops
    def :+=(other: Mat[T])(using num: Numeric[T]): Mat[T] = {
      require(m.rows == other.rows && m.cols == other.cols,
        s"shape mismatch: ${m.shape} vs ${other.shape}")
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          m(i, j) = num.plus(m(i, j), other(i, j))
          j += 1
        i += 1
      m
    }

    def :-=(other: Mat[T])(using num: Numeric[T]): Mat[T] = {
      require(m.rows == other.rows && m.cols == other.cols,
        s"shape mismatch: ${m.shape} vs ${other.shape}")
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          m(i, j) = num.minus(m(i, j), other(i, j))
          j += 1
        i += 1
      m
    }

    /** NumPy: np.linalg.pinv(m) - Moore-Penrose pseudoinverse via SVD */
    def pinv(tol: Double = -1.0)(using @annotation.unused frac: Fractional[T]): Mat[T] = {
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

          // pinv = V * S^+ * U^T   (economy SVD: U is nRows×p, row-major)
          // V[i,k]   = Vt[k,i] = vt(k*nCols+i)
          // U^T[k,j] = U[j,k]  = u(j*p+k)
          val result = Array.ofDim[Double](nCols * nRows)
          var i = 0
          while i < nCols do
            var j = 0
            while j < nRows do
              var sum = 0.0
              var k = 0
              while k < p do
                sum = sum + vt(k * nCols + i) * sInv(k) * u(j * p + k)
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
    def cholesky(using @annotation.unused frac: Fractional[T]): Mat[T] =
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
    def std(using frac: Fractional[T], elem: MatElem[T]): T =
      if fastD(m)
      then
        val a  = m.tdata.asInstanceOf[Array[Double]]
        val n  = a.length
        val mu = sumD(a) / n
        var sumSq = 0.0; var i = 0
        while i < n do { val d = a(i) - mu; sumSq += d * d; i += 1 }
        math.sqrt(sumSq / n).asInstanceOf[T]
      else
        // Stride-aware accumulation: folding m.tdata would read the parent array of a view
        val mu    = m.mean
        val n     = m.size
        var sumSq = frac.zero
        var r = 0
        while r < m.rows do
          var c = 0
          while c < m.cols do
            val diff = frac.minus(m(r, c), mu)
            sumSq = frac.plus(sumSq, frac.times(diff, diff))
            c += 1
          r += 1
        elem.sqrtT(frac.div(sumSq, frac.fromInt(n)))

    /** NumPy: np.std(m, axis=0/1) - std along axis */
    def std(axis: Int)(using frac: Fractional[T], elem: MatElem[T]): Mat[T] =
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      if fastD(m)
      then
        val a = m.tdata.asInstanceOf[Array[Double]]
        val rows = m.rows; val cols = m.cols
        if axis == 0 then
          val result = Array.ofDim[Double](cols)
          var j = 0
          while j < cols do
            var mu = 0.0; var i = 0
            while i < rows do { mu += a(i * cols + j); i += 1 }
            mu /= rows
            var ss = 0.0; i = 0
            while i < rows do { val d = a(i * cols + j) - mu; ss += d * d; i += 1 }
            result(j) = math.sqrt(ss / rows)
            j += 1
          Mat.create(result, 1, cols).asInstanceOf[Mat[T]]
        else
          val result = Array.ofDim[Double](rows)
          var i = 0
          while i < rows do
            val base = i * cols
            var mu = 0.0; var j = 0
            while j < cols do { mu += a(base + j); j += 1 }
            mu /= cols
            var ss = 0.0; j = 0
            while j < cols do { val d = a(base + j) - mu; ss += d * d; j += 1 }
            result(i) = math.sqrt(ss / cols)
            i += 1
          Mat.create(result, rows, 1).asInstanceOf[Mat[T]]
      else if axis == 0 then
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

    /** R: scale(x, center=TRUE, scale=TRUE) — subtract column means and/or divide by
     *  column std devs. Returns a new matrix with zero-mean columns (center=true)
     *  and/or unit-variance columns (scale=true).
     *
     *  Matches R's convention: the divisor is the SAMPLE std (÷(n−1), Bessel) —
     *  rows are treated as observations sampled from a population. For
     *  NumPy/sklearn-style POPULATION scaling (÷n) use `m / m.std(axis = 0)`
     *  after centering (`m.std` keeps NumPy's ddof=0 semantics). */
    def scale(center: Boolean = true, doScale: Boolean = true)(using frac: Fractional[T], elem: MatElem[T]): Mat[T] =
      val c = if center then m - m.mean(axis = 0) else m
      if doScale then
        // sampleStd = populationStd * sqrt(n / (n−1))
        val bessel = elem.fromDouble(math.sqrt(m.rows.toDouble / (m.rows - 1)))
        c / (m.std(axis = 0) * bessel)
      else c

    /** NumPy: np.var(m) - variance */
    def variance(using frac: Fractional[T]): T =
      if fastD(m)
      then
        val a  = m.tdata.asInstanceOf[Array[Double]]
        val n  = a.length
        val mu = sumD(a) / n
        var sumSq = 0.0; var i = 0
        while i < n do { val d = a(i) - mu; sumSq += d * d; i += 1 }
        (sumSq / n).asInstanceOf[T]
      else
        // Stride-aware accumulation: folding m.tdata would read the parent array of a view
        val mu    = m.mean
        val n     = m.size
        var sumSq = frac.zero
        var r = 0
        while r < m.rows do
          var c = 0
          while c < m.cols do
            val diff = frac.minus(m(r, c), mu)
            sumSq = frac.plus(sumSq, frac.times(diff, diff))
            c += 1
          r += 1
        frac.div(sumSq, frac.fromInt(n))

    // Element-wise multiplication (NumPy's * operator) — same fast paths as *:*
    def *(other: Mat[T])(using num: Numeric[T]): Mat[T] = m *:* other

    // Matrix multiplication (NumPy's @ operator)
    inline def *@(other: Mat[T]): Mat[T] = matmul(other)
    // *@ overloads for CVec and RVec — needed so import Mat.* brings them in
    // alongside the Mat overload; uses matmul to avoid *@ dispatch ambiguity.
    @annotation.targetName("matTimesCvec")
    inline def *@(cv: CVec[T]): CVec[T] = m.matmul(cv: Mat[T])
    @annotation.targetName("matTimesRvec")
    inline def *@(rv: RVec[T]): CVec[T] = m.matmul((rv: Mat[T]).transpose)

    // Division with broadcasting
    def /(other: Mat[T])(using frac: Fractional[T]): Mat[T] =
      if fastD2L(m, other) then m.fastBinOp(other)(_ / _).getOrElse(m.binOp(other)(frac.div))
      else m.binOp(other)(frac.div)

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

    // In Mat extension methods (for Mat[Boolean] specifically)

    /** Test whether all elements are true (NumPy: np.all) */
    def all(using ev: T =:= Boolean): Boolean = {
      var res = true
      var r = 0
      while (r < m.rows && res) {
        var c = 0
        while (c < m.cols && res) {
          if !ev(m(r, c)) then res = false
          c += 1
        }
        r += 1
      }
      res
    }

    /** Test whether any element is true (NumPy: np.any) */
    def any(using ev: T =:= Boolean): Boolean = {
      var res = false
      var r = 0
      while (r < m.rows && !res) {
        var c = 0
        while (c < m.cols && !res) {
          if ev(m(r, c)) then res = true
          c += 1
        }
        r += 1
      }
      res
    }

    /** Test whether all elements along axis are true (NumPy: np.all(axis=...)) */
    def all(axis: Int)(using ev: T =:= Boolean): Mat[Boolean] = {
      require(axis == 0 || axis == 1, "axis must be 0 or 1")

      if (axis == 0) {
        // Reduce along rows (check each column)
        val data = Array.fill(m.cols)(true)
        var r = 0
        while (r < m.rows) {
          var c = 0
          while (c < m.cols) {
            if (!ev(m(r, c))) data(c) = false
            c += 1
          }
          r += 1
        }
        create(data, 1, m.cols)
      } else {
        // Reduce along columns (check each row)
        val data = Array.fill(m.rows)(true)
        var r = 0
        while (r < m.rows) {
          var c = 0
          while (c < m.cols) {
            if (!ev(m(r, c))) data(r) = false
            c += 1
          }
          r += 1
        }
        create(data, m.rows, 1)
      }
    }

    /** Test whether any element along axis is true (NumPy: np.any(axis=...)) */
    def any(axis: Int)(using ev: T =:= Boolean): Mat[Boolean] = {
      require(axis == 0 || axis == 1, "axis must be 0 or 1")

      if (axis == 0) {
        // Reduce along rows (check each column)
        val data = Array.fill(m.cols)(false)
        var r = 0
        while (r < m.rows) {
          var c = 0
          while (c < m.cols) {
            if (ev(m(r, c))) data(c) = true
            c += 1
          }
          r += 1
        }
        create(data, 1, m.cols)
      } else {
        // Reduce along columns (check each row)
        val data = Array.fill(m.rows)(false)
        var r = 0
        while (r < m.rows) {
          var c = 0
          while (c < m.cols) {
            if (ev(m(r, c))) data(r) = true
            c += 1
          }
          r += 1
        }
        create(data, m.rows, 1)
      }
    }

    /** Test whether all elements satisfy the predicate */
    def all(p: T => Boolean): Boolean = {
      var res = true
      var r = 0
      while (res && r < m.rows) {
        var c = 0
        while (res && c < m.cols) {
          if (!p(m(r, c))) res = false
          c += 1
        }
        r += 1
      }
      res
    }

    /** Test whether any element satisfies the predicate */
    def any(p: T => Boolean): Boolean = {
      var res = false
      var r = 0
      while (!res && r < m.rows) {
        var c = 0
        while (!res && c < m.cols) {
          if (p(m(r, c))) res = true
          c += 1
        }
        r += 1
      }
      res
    }

    def vsplit(indices: Array[Int]): Seq[Mat[T]] = {
      require(indices.forall(i => i > 0 && i < m.rows), 
        s"Split indices must be in range (0, ${m.rows})")
      require(indices.sorted.sameElements(indices), "Indices must be sorted")
      
      val splitPoints = (0 +: indices.toSeq :+ m.rows).distinct
      
      (0 until splitPoints.length - 1).map { i =>
        val startRow = splitPoints(i)
        val endRow = splitPoints(i + 1)
        m(startRow until endRow, ::)
      }
    }

    def vsplit(n: Int): Seq[Mat[T]] = {
      require(m.rows % n == 0, s"Cannot split ${m.rows} rows into $n equal parts")
      
      val rowsPerSplit = m.rows / n
      (0 until n).map { i =>
        m(i * rowsPerSplit until (i + 1) * rowsPerSplit, ::)
      }
    }

    def hsplit(indices: Array[Int]): Seq[Mat[T]] = {
      require(indices.forall(i => i > 0 && i < m.cols), 
        s"Split indices must be in range (0, ${m.cols})")
      require(indices.sorted.sameElements(indices), "Indices must be sorted")
      
      val splitPoints = (0 +: indices.toSeq :+ m.cols).distinct
      
      (0 until splitPoints.length - 1).map { i =>
        val startCol = splitPoints(i)
        val endCol = splitPoints(i + 1)
        m(::, startCol until endCol)
      }
    }

    def hsplit(n: Int): Seq[Mat[T]] = {
      require(m.cols % n == 0, s"Cannot split ${m.cols} cols into $n equal parts")
      
      val colsPerSplit = m.cols / n
      (0 until n).map { i =>
        m(::, i * colsPerSplit until (i + 1) * colsPerSplit)
      }
    }

    def split(indices: Array[Int], axis: Int = 0): Seq[Mat[T]] = {
      require(axis == 0 || axis == 1, "axis must be 0 (rows) or 1 (columns)")
      if (axis == 0) vsplit(indices) else hsplit(indices)
    }

    def split(n: Int, axis: Int): Seq[Mat[T]] = {
      require(axis == 0 || axis == 1, "axis must be 0 (rows) or 1 (columns)")
      if (axis == 0) vsplit(n) else hsplit(n)
    }
  } // end extension

  extension [T: ClassTag](m: Mat[T]) {

    /** Breeze: X(::, *).map(f) — apply f to each column; columns are reassembled horizontally.
     *  Each column is passed as a ColVec (n×1 Mat[T]); f must return a column vector. */
    def mapCols(f: ColVec[T] => ColVec[T]): Mat[T] =
      if m.cols == 0 then Mat.create(Array.ofDim[T](0), 0, 0)
      else
        val cols = Array.tabulate(m.cols)(j => f(m.slice(0 until m.rows, j to j)))
        val nrows = cols(0).rows
        val result = Array.ofDim[T](nrows * m.cols)
        var j = 0
        while j < m.cols do
          val col = cols(j)
          var i = 0
          while i < nrows do { result(i * m.cols + j) = col(i, 0); i += 1 }
          j += 1
        Mat.create(result, nrows, m.cols)

    /** Breeze: X(*, ::).map(f) — apply f to each row; rows are reassembled vertically.
     *  Each row is passed as a RowVec (1×n Mat[T]); f must return a row vector. */
    def mapRows(f: RowVec[T] => RowVec[T]): Mat[T] =
      if m.rows == 0 then Mat.create(Array.ofDim[T](0), 0, 0)
      else
        val rows = Array.tabulate(m.rows)(i => f(m.slice(i to i, 0 until m.cols)))
        val ncols = rows(0).cols
        val result = Array.ofDim[T](m.rows * ncols)
        var i = 0
        while i < m.rows do
          val row = rows(i)
          var j = 0
          while j < ncols do { result(i * ncols + j) = row(0, j); j += 1 }
          i += 1
        Mat.create(result, m.rows, ncols)
  }

  /** Immutable bundle of display options; the active set is swapped atomically
   *  so concurrent formatters never observe a half-updated configuration. */
  final case class PrintConfig(
    maxRows: Int = 10,           // NumPy doesn't have this, but useful
    maxCols: Int = 10,           // NumPy doesn't have this, but useful
    edgeItems: Int = 3,          // NumPy has this - items to show on each edge
    precision: Int = 8,          // NumPy has this
    suppressScientific: Boolean = false,  // NumPy has this
    threshold: Int = 1000,       // NumPy has this - total elements before ellipsis
  )

  // Global print options — read-only field accessors over the volatile snapshot
  object PrintOptions {
    @volatile private[Mat] var current: PrintConfig = PrintConfig()
    def snapshot: PrintConfig = current
    def maxRows: Int = current.maxRows
    def maxCols: Int = current.maxCols
    def edgeItems: Int = current.edgeItems
    def precision: Int = current.precision
    def suppressScientific: Boolean = current.suppressScientific
    def threshold: Int = current.threshold
  }

  def setPrintOptions(
    maxRows: Int = PrintOptions.maxRows,
    maxCols: Int = PrintOptions.maxCols,
    edgeItems: Int = PrintOptions.edgeItems,
    precision: Int = PrintOptions.precision,
    suppressScientific: Boolean = PrintOptions.suppressScientific,
    threshold: Int = PrintOptions.threshold
  ): Unit =
    PrintOptions.current = PrintConfig(maxRows, maxCols, edgeItems, precision, suppressScientific, threshold)

  extension (self: Mat[Boolean])
    def &&(other: Mat[Boolean]): Mat[Boolean] = self.zipMap(other)(_ && _)
    def ||(other: Mat[Boolean]): Mat[Boolean] = self.zipMap(other)(_ || _)
    
    def unary_! : Mat[Boolean] = {
      val len = self.rows * self.cols
      val res = new Array[Boolean](len)
      var i = 0
      while (i < len) {
        res(i) = !self.at(i)
        i += 1
      }
      Mat.create(res, self.rows, self.cols)
    }
    /** Returns the count of true elements in the matrix */
    def sum: Int = {
      val len = self.rows * self.cols
      var count = 0
      var i = 0
      while (i < len) {
        if (self.at(i)) count += 1
        i += 1
      }
      count
    }

  /** Sliding-window aggregations over rows (axis=0), matching pandas rolling().
   *  Positions 0..window-2 are filled with the NaN sentinel for type T. */
  class RollingWindow[T: ClassTag](private val mat: Mat[T], window: Int):
    require(window >= 1, s"window must be >= 1, got $window")

    def mean(using frac: Fractional[T], elem: MatElem[T]): Mat[T] =
      roll { arr =>
        var s = frac.zero; var i = 0
        while i < arr.length do { s = frac.plus(s, arr(i)); i += 1 }
        frac.div(s, frac.fromInt(arr.length))
      }

    def sum(using num: Numeric[T], elem: MatElem[T]): Mat[T] =
      roll { arr =>
        var s = num.zero; var i = 0
        while i < arr.length do { s = num.plus(s, arr(i)); i += 1 }
        s
      }

    def min(using ord: Ordering[T], elem: MatElem[T]): Mat[T] =
      roll { arr =>
        var best = arr(0); var i = 1
        while i < arr.length do { if ord.lt(arr(i), best) then best = arr(i); i += 1 }
        best
      }

    def max(using ord: Ordering[T], elem: MatElem[T]): Mat[T] =
      roll { arr =>
        var best = arr(0); var i = 1
        while i < arr.length do { if ord.gt(arr(i), best) then best = arr(i); i += 1 }
        best
      }

    def std(using frac: Fractional[T], elem: MatElem[T]): Mat[T] =
      roll { arr =>
        var s = frac.zero; var i = 0
        while i < arr.length do { s = frac.plus(s, arr(i)); i += 1 }
        val mu = frac.div(s, frac.fromInt(arr.length))
        var sq = frac.zero; i = 0
        while i < arr.length do
          val d = frac.minus(arr(i), mu)
          sq = frac.plus(sq, frac.times(d, d)); i += 1
        elem.sqrtT(frac.div(sq, frac.fromInt(arr.length)))
      }

    private def roll(f: Array[T] => T)(using elem: MatElem[T]): Mat[T] =
      val fill   = nanFill[T]
      val result = Array.fill(mat.rows * mat.cols)(fill)
      var j = 0
      while j < mat.cols do
        var i = window - 1
        while i < mat.rows do
          val arr = Array.ofDim[T](window)
          var k = 0
          while k < window do
            arr(k) = mat(i - window + 1 + k, j)
            k += 1
          result(i * mat.cols + j) = f(arr)
          i += 1
        j += 1
      Mat.create(result, mat.rows, mat.cols)

  // Double / 1×1 Mat[Double] — must live inside Mat so `import Mat.*` brings it into scope
  extension (scalar: Double)
    @annotation.targetName("doubleOverMat")
    def /(m: Mat[Double]): Double =
      require(m.rows == 1 && m.cols == 1, s"/ requires 1×1 matrix, got ${m.rows}×${m.cols}")
      scalar / m(0, 0)

  // scalar * CVec / RVec — must live inside Mat so `import Mat.*` brings them in alongside
  // the existing Double * Mat[Double]; body uses direct element access to avoid operator ambiguity.
  extension (s: Double)
    @annotation.targetName("doubleTimesCVec")
    def *(cv: CVec[Double]): CVec[Double] = {
      val n = (cv: Mat[Double]).rows; val r = new Array[Double](n)
      var i = 0; while (i < n) { r(i) = s * (cv: Mat[Double]).at(i, 0); i += 1 }
      Mat.create(r, n, 1)
    }
    @annotation.targetName("doubleTimesRVec")
    def *(rv: RVec[Double]): RVec[Double] = {
      val n = (rv: Mat[Double]).cols; val r = new Array[Double](n)
      var i = 0; while (i < n) { r(i) = s * (rv: Mat[Double]).at(0, i); i += 1 }
      Mat.create(r, 1, n)
    }

  // CVec/RVec companions and all their extension methods live at package level
  // in VecExts.scala — defined there (where the opaque types are NOT transparent)
  // so TASTY records CVec[T]/RVec[T] return types instead of dealiasing to Mat[T].

  // Extension groups relocated to sibling files (file-split campaign). The
  // export re-creates them as members of object Mat, so companion implicit
  // scope and `import Mat.*` dispatch are unchanged for clients.
  export MatMathOps.*
  export MatPandasOps.*
  export MatSignalOps.*
}

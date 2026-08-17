package uni.data

import dev.ludovic.netlib.lapack.LAPACK
import org.netlib.util.intW

/**
 * The three LAPACK routines `Mat` needs (`dgeev`, `dgesvd`, `dpotrf`), through netlib's
 * LAPACK instead of bytedeco's LAPACKE — used when the bundled OpenBLAS must not be
 * loaded (`-Duni.mat.blas=system` on Linux, see `Mat.blasChoice`). netlib resolves the
 * system `liblapack.so.3` (`JNILAPACK`; on Ubuntu with `libopenblas0` that is the same
 * OpenBLAS already serving matmul, so one library stays resident) and otherwise falls
 * back to `F2jLAPACK`, its pure-Java translation — slower, never absent.
 *
 * Every entry point takes and returns row-major buffers with exactly the semantics of the
 * LAPACKE `LAPACK_ROW_MAJOR` calls in `Mat`, so the two backends are interchangeable at
 * the call site. Fortran LAPACK is column-major, so operands are transposed on the way
 * in and results on the way out — the same copies LAPACKE makes internally.
 */
private[data] object LapackNetlib:

  private lazy val lapack: LAPACK = LAPACK.getInstance()

  /** Name of the backend netlib chose, for `-Duni.blas.verbose=true`. */
  def backendName: String = lapack.getClass.getName

  /** Row-major `rows`×`cols` → column-major (i.e. the transpose laid out row-major). */
  private def toColMajor(a: Array[Double], rows: Int, cols: Int): Array[Double] =
    val out = new Array[Double](rows * cols)
    var i = 0
    while i < rows do
      var j = 0
      while j < cols do
        out(j * rows + i) = a(i * cols + j)
        j += 1
      i += 1
    out

  /** Column-major `rows`×`cols` with leading dimension `ld` → row-major `rows`×`cols`. */
  private def toRowMajor(a: Array[Double], rows: Int, cols: Int, ld: Int): Array[Double] =
    val out = new Array[Double](rows * cols)
    var i = 0
    while i < rows do
      var j = 0
      while j < cols do
        out(i * cols + j) = a(j * ld + i)
        j += 1
      i += 1
    out

  /** `dgeev` on a row-major n×n matrix: `(wr, wi, vr)`; `vr` is row-major n×n when
   *  `wantVectors`, with LAPACK's packing of complex-pair eigenvectors in adjacent
   *  columns, else empty. */
  def dgeev(n: Int, aRowMajor: Array[Double], wantVectors: Boolean): (Array[Double], Array[Double], Array[Double]) =
    val a    = toColMajor(aRowMajor, n, n)
    val wr   = new Array[Double](n)
    val wi   = new Array[Double](n)
    val ldvl = 1
    val vl   = new Array[Double](ldvl)
    val ldvr = if wantVectors then n else 1
    val vr   = new Array[Double](ldvr * (if wantVectors then n else 1))
    val jobvr = if wantVectors then "V" else "N"
    val info  = new intW(0)
    val query = new Array[Double](1)
    lapack.dgeev("N", jobvr, n, a, math.max(1, n), wr, wi, vl, ldvl, vr, ldvr, query, -1, info)
    if info.`val` != 0 then throw ArithmeticException(s"dgeev workspace query failed with info=${info.`val`}")
    val lwork = math.max(1, query(0).toInt)
    val work  = new Array[Double](lwork)
    lapack.dgeev("N", jobvr, n, a, math.max(1, n), wr, wi, vl, ldvl, vr, ldvr, work, lwork, info)
    if info.`val` != 0 then throw ArithmeticException(s"dgeev failed with info=${info.`val`}")
    (wr, wi, if wantVectors then toRowMajor(vr, n, n, n) else Array.empty[Double])

  /** Economy SVD of a row-major m×n matrix: `(u, s, vt)` as row-major m×p, p, p×n with
   *  p = min(m, n) — what LAPACKE `dgesdd(jobz = 'S')` returns in `Mat.svdDouble`. Uses
   *  `dgesvd('S','S')` rather than `dgesdd`: netlib's argument checker sizes `u` as m×m
   *  for `dgesdd` whatever `jobz` says, which would make tall inputs quadratic in memory. */
  def dgesddEconomy(m: Int, n: Int, aRowMajor: Array[Double]): (Array[Double], Array[Double], Array[Double]) =
    val p     = math.min(m, n)
    val a     = toColMajor(aRowMajor, m, n)
    val s     = new Array[Double](p)
    val ldu   = math.max(1, m)
    val u     = new Array[Double](ldu * p)
    val ldvt  = math.max(1, p)
    val vt    = new Array[Double](ldvt * n)
    val info  = new intW(0)
    val query = new Array[Double](1)
    lapack.dgesvd("S", "S", m, n, a, math.max(1, m), s, u, ldu, vt, ldvt, query, -1, info)
    if info.`val` != 0 then throw ArithmeticException(s"dgesvd workspace query failed with info=${info.`val`}")
    val lwork = math.max(1, query(0).toInt)
    val work  = new Array[Double](lwork)
    lapack.dgesvd("S", "S", m, n, a, math.max(1, m), s, u, ldu, vt, ldvt, work, lwork, info)
    if info.`val` != 0 then throw ArithmeticException(s"dgesvd failed with info=${info.`val`}")
    (toRowMajor(u, m, p, ldu), s, toRowMajor(vt, p, n, ldvt))

  /** `dpotrf` with `uplo = 'L'` on a row-major symmetric n×n matrix, in place: on return
   *  the lower triangle of `aRowMajor` holds L with A = L·Lᵀ; the strict upper triangle
   *  is left as it was (the caller zeroes it, as with LAPACKE). Returns LAPACK's `info`
   *  (> 0: not positive definite). A symmetric buffer read column-major is the same
   *  matrix, and its lower triangle in row-major terms is the upper one column-major, so
   *  this is `dpotrf('U')` on the buffer as it is — no transposes. */
  def dpotrfLower(n: Int, aRowMajor: Array[Double]): Int =
    val info = new intW(0)
    lapack.dpotrf("U", n, aRowMajor, math.max(1, n), info)
    info.`val`

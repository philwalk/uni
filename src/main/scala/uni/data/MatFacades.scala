package uni.data

// MatFacades.scala — MatD/MatB/MatF convenience facades and the element-typed
// aliases. Lives outside Mat.scala so the opaque types CVec[T]/RVec[T] are NOT
// transparent here (TASTY keeps the vector return types of arange/linspace/
// fromSeq), and to keep Mat.scala focused on the core Mat[T] implementation.

import scala.reflect.ClassTag
import uni.io.FileOps.*
import uni.data.Big.Big

import Mat.*

// This makes MatD a valid Type name for the user
type MatD = Mat[Double]
type VecD = Vec[Double]
type CVecD = CVec[Double]
type RVecD = RVec[Double]
type CVecF = CVec[Float]
type RVecF = RVec[Float]
type CVecB = CVec[Big]
type RVecB = RVec[Big]
type MatB = Mat[Big]
type VecB = Vec[Big]
type MatF = Mat[Float]
type VecF = Vec[Float]

/** Result returned by [[MatD.leastSquares]]. Mirrors Breeze's LeastSquaresResult. */
case class LeastSquaresResult(
  coefficients:   MatD,
  residuals:      MatD,
  rank:           Int,
  singularValues: Array[Double],
)

/** Shared implementation of the MatD/MatB/MatF convenience facades: same-name
 *  forwarders to the generic Mat factories, with Double arguments converted to
 *  the element type through MatElem.fromDouble (so e.g. MatB.full(_, _, nan)
 *  yields BigNaN cells rather than throwing). Type-specific members (Double-only
 *  signal processing, leastSquares, specialized RNG fills) stay in the objects. */
private[data] trait MatFacade[T: ClassTag: Fractional: MatElem]:
  private def elem: MatElem[T] = summon[MatElem[T]]

  /** Big→T conversion for readCsv (CSV cells parse as Big). */
  protected def fromBig(b: Big): T

  // ----- Constructors -----
  def zeros(rows: Int, cols: Int): Mat[T] = Mat.zeros[T](rows, cols)
  def zeros(shape: (Int, Int)): Mat[T]    = Mat.zeros[T](shape)
  def ones(rows: Int, cols: Int): Mat[T]  = Mat.ones[T](rows, cols)
  def ones(shape: (Int, Int)): Mat[T]     = Mat.ones[T](shape)
  def full(rows: Int, cols: Int, value: Double): Mat[T] = Mat.full[T](rows, cols, elem.fromDouble(value))
  def full(shape: (Int, Int), value: Double): Mat[T]    = Mat.full[T](shape, elem.fromDouble(value))
  def eye(n: Int, k: Int = 0): Mat[T] = Mat.eye[T](n, k)
  def arange(stop: Int): CVec[T] = Mat.arange[T](stop)
  def arange(start: Int, stop: Int): CVec[T] = Mat.arange[T](start, stop)
  def arange(start: Int, stop: Int, step: Int): CVec[T] = Mat.arange[T](start, stop, step)
  def arange(stop: Double): CVec[T] = Mat.arange[T](stop)
  def arange(start: Double, stop: Double): CVec[T] = Mat.arange[T](start, stop)
  def arange(start: Double, stop: Double, step: Double): CVec[T] = Mat.arange[T](start, stop, step)
  def linspace(start: Double, stop: Double, num: Int = 50): CVec[T] = Mat.linspace[T](start, stop, num)

  def apply(rows: Int, cols: Int): Mat[T] = Mat.zeros[T](rows, cols)
  def apply(m: Mat[T], row: Int, col: Int): T = m.apply(row, col)
  def apply(rows: Int, cols: Int, data: Array[T]): Mat[T] = Mat.apply[T](rows, cols, data)
  def apply(value: Double): Mat[T] = Mat.apply[T](elem.fromDouble(value))
  // Column vector, matching Mat(...), CVec(...), and Breeze's DenseVector(...)
  // convention (changed from row in v0.14.0). For a row vector use row(...).
  def apply(first: Double, rest: Double*): Mat[T] =
    Mat.col[T](elem.fromDouble(first) +: rest.map(elem.fromDouble)*)
  def apply(tuples: Tuple*): Mat[T] = Mat.apply[T](tuples*)
  def single(value: Double): Mat[T] = Mat.single[T](elem.fromDouble(value))
  def fromSeq(values: Seq[T]): CVec[T] = Mat.fromSeq[T](values)
  def of(first: Double, rest: Double*): Mat[T] =
    Mat.of[T](elem.fromDouble(first), rest.map(elem.fromDouble)*)
  def row(values: Double*): Mat[T] = Mat.row[T](values.map(elem.fromDouble)*)
  def col(values: Double*): Mat[T] = Mat.create(values.map(elem.fromDouble).toArray, values.length, 1)
  def empty: Mat[T] = Mat.empty[T]
  // ----- Diagonal -----
  def diag(values: Array[T]): Mat[T] = Mat.diag[T](values)
  def diag(v: Mat[T]): Mat[T] = Mat.diag[T](v)
  def diag(values: Array[T], rows: Int, cols: Int): Mat[T] = Mat.diag[T](values, rows, cols)
  // ----- Like-constructors -----
  def zerosLike(m: Mat[T]): Mat[T] = Mat.zerosLike[T](m)
  def onesLike(m: Mat[T]): Mat[T] = Mat.onesLike[T](m)
  def fullLike(m: Mat[T], value: Double): Mat[T] = Mat.fullLike[T](m, elem.fromDouble(value))
  // ----- Stacking -----
  def vstack(mats: Mat[T]*): Mat[T] = Mat.vstack[T](mats*)
  def hstack(mats: Mat[T]*): Mat[T] = Mat.hstack[T](mats*)
  def concatenate(mats: Seq[Mat[T]], axis: Int = 0): Mat[T] = Mat.concatenate[T](mats, axis)
  def where(condition: Mat[Boolean], x: Mat[T], y: Mat[T]): Mat[T] = Mat.where[T](condition, x, y)
  def where(condition: Mat[Boolean], x: T, y: T): Mat[T] = Mat.where[T](condition, x, y)
  def tabulate(rows: Int, cols: Int)(f: (Int, Int) => T): Mat[T] = Mat.tabulate[T](rows, cols)(f)
  def meshgrid(x: Vec[T], y: Vec[T]): (Mat[T], Mat[T]) = Mat.meshgrid[T](x, y)
  // ----- Random -----
  def setSeed(seed: Long): Unit = Mat.setSeed(seed)
  def rand(rows: Int, cols: Int): Mat[T] = fillRandom(rows, cols)(_.nextDouble())
  def randn(rows: Int, cols: Int): Mat[T] = fillRandom(rows, cols)(_.randn())
  def uniform(low: Double, high: Double, rows: Int, cols: Int): Mat[T] =
    fillRandom(rows, cols)(_.uniform(low, high))
  def normal(mean: Double, std: Double, rows: Int, cols: Int): Mat[T] =
    fillRandom(rows, cols)(rng => mean + std * rng.randn())
  def randint(low: Int, high: Int): Int = Mat.randint(low, high)
  def randint(low: Int, high: Int, rows: Int, cols: Int): Mat[Int] = Mat.randint(low, high, rows, cols)
  private def fillRandom(rows: Int, cols: Int)(next: NumPyRNG => Double): Mat[T] =
    val rng = Mat.globalRNG  // captured once: thread-safe vs concurrent setSeed
    Mat.create(Array.fill(rows * cols)(elem.fromDouble(next(rng))), rows, cols)
  // ----- CSV I/O -----
  def readCsv(p: uni.Path): Mat[T] = loadSmart(p, fromBig).mat
  def readCsv(s: String): Mat[T] =
    if s.startsWith("http://") || s.startsWith("https://") then loadSmartUrl(s, fromBig).mat
    else readCsv(uni.Paths.get(s))
  def writeCsv(m: Mat[T], p: uni.Path, sep: String = ","): Unit = m.saveCSV(p, sep)
  def writeCsv(m: Mat[T], s: String): Unit = writeCsv(m, uni.Paths.get(s))

/** Convenience facade for Mat[Double]. */
object MatD extends MatFacade[Double] {
  protected def fromBig(b: Big): Double = b.toDouble

  /** Breeze: leastSquares(A, b) — solve min‖Ax − b‖₂ via SVD.
   *  Returns a [[LeastSquaresResult]] with a `.coefficients` field. */
  def leastSquares(A: MatD, b: MatD): LeastSquaresResult =
    val (coefs, resid, rank, sv) = A.lstsq(b)
    LeastSquaresResult(coefs, resid, rank, sv)

  // Double-only single-arg vector constructors
  def zeros(n: Int): Mat[Double]  = Mat.zeros[Double](n, 1)
  def ones(n: Int): Mat[Double]   = Mat.ones[Double](n, 1)
  def randn(n: Int): CVec[Double] = Mat.randn(n)
  def rnorm(n: Int): CVec[Double] = Mat.randn(n)
  // Specialized Double RNG fills (skip the generic fromDouble round-trip)
  override def rand(rows: Int, cols: Int): Mat[Double] = Mat.rand(rows, cols)
  override def randn(rows: Int, cols: Int): Mat[Double] = Mat.randn(rows, cols)
  override def uniform(low: Double, high: Double, rows: Int, cols: Int): Mat[Double] =
    Mat.uniform(low, high, rows, cols)
  override def normal(mean: Double, std: Double, rows: Int, cols: Int): Mat[Double] =
    Mat.normal(mean, std, rows, cols)
  // ----- Signal processing (Double-only) -----
  def polyfit(x: Vec[Double], y: Vec[Double], deg: Int): Vec[Double] = Mat.polyfit(x, y, deg)
  def polyval(coeffs: Vec[Double], x: Vec[Double]): Vec[Double] = Mat.polyval(coeffs, x)
  def convolve(a: Vec[Double], b: Vec[Double], mode: String = "full"): Vec[Double] = Mat.convolve(a, b, mode)
  def correlate(a: Vec[Double], b: Vec[Double], mode: String = "valid"): Vec[Double] = Mat.correlate(a, b, mode)
}

/** Convenience facade for Mat[Big]. */
object MatB extends MatFacade[Big]:
  protected def fromBig(b: Big): Big = b

/** Convenience facade for Mat[Float]. */
object MatF extends MatFacade[Float]:
  protected def fromBig(b: Big): Float = b.toDouble.toFloat

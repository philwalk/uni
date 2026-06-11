package uni.data

import uni.data.Big.{Big, BigNaN}

/** Element-type operations for Mat's supported numeric element types
 *  (Double, Float, Big).
 *
 *  Bundles the Double→T conversion, precision-aware sqrt, and NaN sentinel
 *  that Mat operations previously dispatched per call site via
 *  `ClassTag.runtimeClass match` — making unsupported element types a
 *  compile-time error (no given) instead of a runtime throw, and keeping
 *  Big's arbitrary-precision sqrt instead of a lossy Double round-trip.
 */
trait MatElem[T]:
  /** Convert a Double result back to T. NaN maps to the type's NaN sentinel. */
  def fromDouble(d: Double): T
  /** Square root preserving the element type's precision (Big stays exact). */
  def sqrtT(x: T): T
  /** The type's NaN sentinel (Double.NaN / Float.NaN / BigNaN). */
  def nan: T

object MatElem:

  given MatElem[Double] with
    def fromDouble(d: Double): Double = d
    def sqrtT(x: Double): Double      = math.sqrt(x)
    def nan: Double                   = Double.NaN

  given MatElem[Float] with
    def fromDouble(d: Double): Float = d.toFloat
    def sqrtT(x: Float): Float       = math.sqrt(x.toDouble).toFloat
    def nan: Float                   = Float.NaN

  given MatElem[Big] with
    // BigDecimal(Double.NaN) throws — route non-finite values to the BigNaN sentinel
    def fromDouble(d: Double): Big = if d.isNaN || d.isInfinite then BigNaN else Big(d)
    def sqrtT(x: Big): Big         = x.sqrt
    def nan: Big                   = BigNaN

  /** Plain scala.math.BigDecimal (the un-wrapped type behind Big) — supported so
   *  `Mat[BigDecimal]` keeps working for callers not using the Big opaque type.
   *  No NaN sentinel of its own; reuses BigNaN's underlying value. */
  given MatElem[BigDecimal] with
    def fromDouble(d: Double): BigDecimal = BigDecimal(d)  // throws on NaN, as before
    def sqrtT(x: BigDecimal): BigDecimal =
      BigDecimal(x.bigDecimal.sqrt(java.math.MathContext.DECIMAL128))
    def nan: BigDecimal = BigNaN.toBigDecimal

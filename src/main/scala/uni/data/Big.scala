package uni.data

import uni.data.BigUtils.*
import scala.math.BigDecimal
import scala.math.BigDecimal.*
export scala.math.BigDecimal.RoundingMode

export Big.Big

object Big:
  private val MC = java.math.MathContext.DECIMAL128   // or a custom context
  // ------------------------------------------------------------
  // Opaque type
  // ------------------------------------------------------------
  opaque type Big = BigDecimal

// Derive Fractional[Big] from Fractional[BigDecimal]
  given Fractional[Big] = summon[Fractional[BigDecimal]].asInstanceOf[Fractional[Big]]

  def zero: Big = Big(BigDecimal(0))
  def one: Big = Big(BigDecimal(1))
  def ten: Big = Big(BigDecimal(10))
  def hundred: Big = Big(BigDecimal(100))

  // Constructors
  def apply(s: String): Big      = BigUtils.str2num(s)
  def apply(d: Double): Big      = BigDecimal(d)
  def apply(i: Int): Big         = BigDecimal(i)
  def apply(l: Long): Big        = BigDecimal(l)
  def apply(bd: BigDecimal): Big = bd

  // Lowercase factory (public API)
  def big(s: String): Big      = apply(s)
  def big(d: Double): Big      = apply(d)
  def big(i: Int): Big         = apply(i)
  def big(l: Long): Big        = apply(l)
  def big(bd: BigDecimal): Big = apply(bd)

  // ------------------------------------------------------------
  // Extractor for pattern matching
  // ------------------------------------------------------------
  def unapply(n: Big): Option[Big] =
    if n == BigNaN then None else Some(n)

  given CanEqual[Big, BigDecimal] = CanEqual.derived
  given CanEqual[BigDecimal, Big] = CanEqual.derived

  // widening conversions:
  // import scala.language.implicitConversions
  // given Conversion[Long, Big] = l => Big(BigDecimal(l))
  // given Conversion[Int, Big]  = i => Big(BigDecimal(i))
  // given Conversion[Double, Big] = d => Big(BigDecimal(d))

  extension (d: Double)
    inline def asBig: Big = Big(BigDecimal(d))

  extension (l: Long)
    inline def asBig: Big = Big(BigDecimal(l))

  extension (i: Int)
    inline def asBig: Big = Big(BigDecimal(i))

  given Numeric[Big] with
    def plus(x: Big, y: Big): Big = x + y
    def minus(x: Big, y: Big): Big = x - y
    def times(x: Big, y: Big): Big = x * y
    def negate(x: Big): Big = -x
    def fromInt(x: Int): Big = Big(x)
    def parseString(str: String): Option[Big] = scala.util.Try(Big(str)).toOption
    def toInt(x: Big): Int = x.toInt
    def toLong(x: Big): Long = x.toLong
    def toFloat(x: Big): Float = x.toFloat
    def toDouble(x: Big): Double = x.toDouble
    def compare(x: Big, y: Big): Int = x.compare(y)

  extension (d: Double)
    def toBig: Big = Big(BigDecimal(d))

  // ------------------------------------------------------------
  // Extension methods
  // ------------------------------------------------------------
  extension (n: Big)
    // underlying Big
    inline def value: BigDecimal = n

    inline def toBigDecimal: BigDecimal = n

    inline def signum: Int = n.signum // Inside this scope, b is treated as a BigDecimal

    inline def toPlainString: String = n.bigDecimal.toPlainString // Inside this scope, b is treated as a BigDecimal

    def ~^[T](exponent: T)(using frac: Fractional[T]): Big =
      val expDouble = frac.toDouble(exponent)
      if (expDouble == expDouble.toInt) {
        // Integer exponent - use BigDecimal.pow for precision
        val value: BigDecimal = n.underlying.pow(expDouble.toInt)
        uni.data.Big(value)
      } else {
        // Fractional exponent - fall back to double precision
        uni.data.Big(math.pow(n.toDouble, expDouble))
      }
    def ~^(exponent: Int): Big  = n ~^ exponent.toDouble
    def ~^(exponent: Long): Big = n ~^ exponent.toDouble

    def setScale(scale: Int, roundingMode: scala.math.BigDecimal.RoundingMode.RoundingMode): Big =
      n.setScale(scale, roundingMode)

    // --- BigNaN helpers -------------------------------------------------------

    // unary guard
    inline def unary_- : Big =
      if BigUtils.isBad(n) then BigNaN else Big(-n)

    // binary guard helper
    @inline private def badGuard(that: Big)(f: => Big): Big =
      if BigUtils.isBad(n) || BigUtils.isBad(that) then BigNaN
      else Big(f)

    // --- arithmetic -----------------------------------------------------------

    inline def +(that: Big): Big = badGuard(that)(n + that)
    inline def -(that: Big): Big = badGuard(that)(n - that)
    inline def *(that: Big): Big = badGuard(that)(n * that)

    /*
    inline def /(that: Big): Big =
      if BigUtils.isBad(n) || BigUtils.isBad(that) then BigNaN
      else if that == Big(0) then BigNaN
      else Big(n / that)

    inline def /(that: Int): Big =
      if BigUtils.isBad(n) || that == 0 then BigNaN
      else Big(n / BigDecimal(that))

    inline def /(that: Long): Big =
      if n == BigNaN || that == 0 then BigNaN
      else Big(n / BigDecimal(that))

    def /(that: Double): Big =
      if n == BigNaN || that == 0.0 || that.isNaN || that.isInfinite then
        BigNaN
      else {
        if that.isNaN || that.isInfinite then
          BigNaN
        else
          val denom = BigDecimal(that)
          val ratio = n / denom
          Big(ratio)
      }
      */
      /**
     * Unified division handling Big, Double, Int, etc.
     * Correctly intercepts NaN/Infinite before BigDecimal conversion.
     */
     /**
     * Unified division: Handles Big, Double, Int, Long, etc.
     * Correctly intercepts Double.NaN before it hits BigDecimal.
     */
    def /[T](that: T)(using num: Numeric[T]): Big =
      // 1. Check for non-finite values (NaN/Inf) via Double conversion
      val d = num.toDouble(that)
      if BigUtils.isBad(n) || !d.isFinite || d == 0.0 then 
        BigNaN
      else
        // 2. Optimization: If it's already a Big, use full precision
        if that.isInstanceOf[Big] then
          val divisor = that.asInstanceOf[Big]
          // Re-check zero for the opaque type just in case
          if divisor == Big(0) then BigNaN else Big(n / divisor)
        else
          // 3. For everything else (Int, Long, Double), convert via String
          // This is the safest way to create a BigDecimal from a Double
          Big(n / BigDecimal(d.toString))

    // Multiply by Int, Long, Double
    inline def *(that: Int): Big = badGuard(that)(n * BigDecimal(that))
    inline def *(that: Long): Big = badGuard(that)(n * BigDecimal(that))
    inline def *(that: Double): Big = badGuard(that)(n * BigDecimal(that))

    // Add other operators if needed
    inline def +(that: Int): Big = badGuard(that)(n + BigDecimal(that))
    inline def +(that: Long): Big = badGuard(that)(n + BigDecimal(that))
    inline def +(that: Double): Big = badGuard(that)(n + BigDecimal(that))

    inline def -(that: Int): Big = badGuard(that)(n - BigDecimal(that))
    inline def -(that: Long): Big = badGuard(that)(n - BigDecimal(that))
    inline def -(that: Double): Big = badGuard(that)(n - BigDecimal(that))

    // --- comparisons ----------------------------------------------------------
    inline def <(that: Big): Boolean    = !BigUtils.isBad(n) && !BigUtils.isBad(that) && n < that
    inline def <=(that: Big): Boolean   = !BigUtils.isBad(n) && !BigUtils.isBad(that) && n <= that
    inline def >(that: Big): Boolean    = !BigUtils.isBad(n) && !BigUtils.isBad(that) && n > that
    inline def >=(that: Big): Boolean   = !BigUtils.isBad(n) && !BigUtils.isBad(that) && n >= that

    inline def <(that: Double): Boolean = n < that.asBig
    inline def <(that: Long): Boolean   = n < that.asBig
    inline def <(that: Int): Boolean    = n < that.asBig

    inline def <=(that: Double): Boolean = n <= that.asBig
    inline def <=(that: Long): Boolean   = n <= that.asBig
    inline def <=(that: Int): Boolean    = n <= that.asBig

    inline def >(that: Double): Boolean = n > that.asBig
    inline def >(that: Long): Boolean   = n > that.asBig
    inline def >(that: Int): Boolean    = n > that.asBig

    inline def >=(that: Double): Boolean = n >= that.asBig
    inline def >=(that: Long): Boolean   = n >= that.asBig
    inline def >=(that: Int): Boolean    = n >= that.asBig

    // --- conversions ----------------------------------------------------------

    inline def toDouble: Double = if BigUtils.isBad(n) then Double.NaN else n.toDouble

    inline def toFloat: Float = if BigUtils.isBad(n) then Float.NaN else n.toFloat

    inline def toBig: Big = n

    inline def toInt: Int = n.toInt
    
    inline def toLong: Long = n.toLong
      
    inline def abs: Big = n.abs

    inline def isValidInt: Boolean = n.isValidInt

    inline def isValidLong: Boolean = n.isValidLong

    inline def isNaN: Boolean = {
      n == BigNaN
    }
    inline def isNotNaN: Boolean = n != BigNaN
  
    def sqrt: Big = 
      // BigDecimal.sqrt is available in Java 9+
      // .bigDecimal converts scala.math.BigDecimal -> java.math.BigDecimal
      val underlying: java.math.BigDecimal = n.value.bigDecimal
      Big(underlying.sqrt(Big.MC))

  import scala.language.implicitConversions
  given Conversion[Int, Big] = d => Big(BigDecimal(d))
  given Conversion[Long, Big] = d => Big(BigDecimal(d))
  // BigDecimal has no apply(Float/Double) overload — the compiler resolves BigDecimal(d) via
  // double2bigDecimal (imported from BigDecimal.*), which crashes on NaN/Infinite.
  // Using d.toString avoids that path entirely.  BigNaN can't be referenced here because
  // BigUtils imports Big.*, creating an initialization cycle; use the literal instead.
  //private val BadNumLit: BigDecimal = BigDecimal("-0.00000001234567890123456789")
  given Conversion[Float, Big]  = d => if d.isInfinite then
    BigNaN
  else
    if !d.isFinite then BigNaN else
    Big(BigDecimal(d))

  given Conversion[Double, Big] = d => if d.isInfinite then
    BigNaN
  else
    if !d.isFinite then BigNaN else
    Big(BigDecimal(d))



package uni.data

import uni.data.BigUtils.*
import scala.math.BigDecimal
import scala.math.BigDecimal.*
export scala.math.BigDecimal.RoundingMode

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
    if n == BadNum then None else Some(n)

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

    def setScale(scale: Int, roundingMode: scala.math.BigDecimal.RoundingMode.RoundingMode): Big =
      n.setScale(scale, roundingMode)

    // --- BadNum helpers -------------------------------------------------------

    // unary guard
    inline def unary_- : Big =
      if BigUtils.isBad(n) then BigUtils.BadNum else Big(-n)

    // binary guard helper
    @inline private def badGuard(that: Big)(f: => Big): Big =
      if BigUtils.isBad(n) || BigUtils.isBad(that) then BigUtils.BadNum
      else Big(f)

    // --- arithmetic -----------------------------------------------------------

    inline def +(that: Big): Big = badGuard(that)(n + that)
    inline def -(that: Big): Big = badGuard(that)(n - that)
    inline def *(that: Big): Big = badGuard(that)(n * that)
    inline def /(that: Big): Big =
      if BigUtils.isBad(n) || BigUtils.isBad(that) then BigUtils.BadNum
      else if that == Big(0) then BigUtils.BadNum
      else Big(n / that)

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

    inline def /(that: Int): Big =
      if BigUtils.isBad(n) || that == 0 then BigUtils.BadNum
      else Big(n / BigDecimal(that))

    inline def /(that: Long): Big =
      if BigUtils.isBad(n) || that == 0 then BigUtils.BadNum
      else Big(n / BigDecimal(that))

    inline def /(that: Double): Big =
      if BigUtils.isBad(n) || that == 0.0 || that.isNaN || that.isInfinite then BigUtils.BadNum
      else Big(n / BigDecimal(that))

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
  
    def sqrt: Big = 
      // BigDecimal.sqrt is available in Java 9+
      // .bigDecimal converts scala.math.BigDecimal -> java.math.BigDecimal
      val underlying: java.math.BigDecimal = n.value.bigDecimal
      Big(underlying.sqrt(Big.MC))

  import scala.language.implicitConversions
  given Conversion[Double, Big] = d => Big(BigDecimal(d))



package uni.data

import uni.data.BigUtils.*
import scala.math.BigDecimal

object Big:
  // ------------------------------------------------------------
  // Opaque type
  // ------------------------------------------------------------
  opaque type Big = BigDecimal

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
  def unapply(b: Big): Option[Big] =
    if b == BadNum then None else Some(b)

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
  extension (b: Big)
    // underlying Big
    inline def value: BigDecimal = b

    inline def signum: Int = b.signum

    inline def toBigDecimal: BigDecimal = b

    // --- BadNum helpers -------------------------------------------------------

    // unary guard
    inline def unary_- : Big =
      if BigUtils.isBad(b) then BigUtils.BadNum else Big(-b)

    // binary guard helper
    @inline private def badGuard(that: Big)(f: => Big): Big =
      if BigUtils.isBad(b) || BigUtils.isBad(that) then BigUtils.BadNum
      else Big(f)


  // ... existing methods ...
  
  // --- rounding and scale ---------------------------------------------------
    inline def setScale(scale: Int, roundingMode: BigDecimal.RoundingMode.RoundingMode): Big =
      if BigUtils.isBad(b) then BigUtils.BadNum
      else Big(b.setScale(scale, roundingMode))
    
    inline def setScale(scale: Int): Big =
      if BigUtils.isBad(b) then BigUtils.BadNum
      else Big(b.setScale(scale))

    // --- arithmetic -----------------------------------------------------------
    inline def +(that: Big): Big = badGuard(that)(b + that)
    inline def -(that: Big): Big = badGuard(that)(b - that)
    inline def *(that: Big): Big = badGuard(that)(b * that)
    inline def /(that: Big): Big = if BigUtils.isBad(b) || BigUtils.isBad(that) then BigUtils.BadNum else if that == Big(0) then BigUtils.BadNum else Big(b / that)

    // Multiply by Int, Long, Double
    inline def *(that: Int): Big = badGuard(that)(b * that.asBig)
    inline def *(that: Long): Big = badGuard(that)(b * that.asBig)
    inline def *(that: Double): Big = badGuard(that)(b * that.asBig)

    // Add other operators if needed
    inline def +(that: Int): Big = badGuard(that)(b + that.asBig)
    inline def +(that: Long): Big = badGuard(that)(b + that.asBig)
    inline def +(that: Double): Big = badGuard(that)(b + that.asBig)

    inline def -(that: Int): Big = badGuard(that)(b - that.asBig)
    inline def -(that: Long): Big = badGuard(that)(b - that.asBig)
    inline def -(that: Double): Big = badGuard(that)(b - that.asBig)

    inline def /(that: Int): Big = if BigUtils.isBad(b) || that == 0 then BigUtils.BadNum else Big(b / that.asBig)
    inline def /(that: Long): Big = if BigUtils.isBad(b) || that == 0 then BigUtils.BadNum else Big(b / that.asBig)
    inline def /(that: Double): Big = if BigUtils.isBad(b) || that == 0.0 || that.isNaN || that.isInfinite then BigUtils.BadNum else Big(b / that.asBig)

    // --- comparison operators -------------------------------------------------
    // Comparison with Big
    inline def ==(that: Big): Boolean  = if BigUtils.isBad(b) || BigUtils.isBad(that) then false else b == that
    inline def !=(that: Big): Boolean  = if BigUtils.isBad(b) || BigUtils.isBad(that) then true else b != that
    inline def <(that: Big): Boolean   = if BigUtils.isBad(b) || BigUtils.isBad(that) then false else b < that
    inline def <=(that: Big): Boolean  = if BigUtils.isBad(b) || BigUtils.isBad(that) then false else b <= that
    inline def >(that: Big): Boolean   = if BigUtils.isBad(b) || BigUtils.isBad(that) then false else b > that
    inline def >=(that: Big): Boolean  = if BigUtils.isBad(b) || BigUtils.isBad(that) then false else b >= that

    // Comparison with Int
    // inline def ==(that: Int): Boolean  = if BigUtils.isBad(b) then false else b == that.asBig
    // inline def !=(that: Int): Boolean  = if BigUtils.isBad(b) then true else b != that.asBig
    inline def <(that: Int): Boolean   = if BigUtils.isBad(b) then false else b < that.asBig
    inline def <=(that: Int): Boolean  = if BigUtils.isBad(b) then false else b <= that.asBig
    inline def >(that: Int): Boolean   = if BigUtils.isBad(b) then false else b > that.asBig
    inline def >=(that: Int): Boolean  = if BigUtils.isBad(b) then false else b >= that.asBig

    // Comparison with Long
    // inline def ==(that: Long): Boolean  = if BigUtils.isBad(b) then false else b == that.asBig
    // inline def !=(that: Long): Boolean  = if BigUtils.isBad(b) then true else b != that.asBig
    inline def <(that: Long): Boolean   = if BigUtils.isBad(b) then false else b < that.asBig
    inline def <=(that: Long): Boolean  = if BigUtils.isBad(b) then false else b <= that.asBig
    inline def >(that: Long): Boolean   = if BigUtils.isBad(b) then false else b > that.asBig
    inline def >=(that: Long): Boolean  = if BigUtils.isBad(b) then false else b >= that.asBig

    // Comparison with Double
    // inline def ==(that: Double): Boolean  = if BigUtils.isBad(b) || that.isNaN || that.isInfinite then false else b == that.asBig
    // inline def !=(that: Double): Boolean  = if BigUtils.isBad(b) || that.isNaN || that.isInfinite then true else b != that.asBig
    inline def <(that: Double): Boolean   = if BigUtils.isBad(b) || that.isNaN || that.isInfinite then false else b < that.asBig
    inline def <=(that: Double): Boolean  = if BigUtils.isBad(b) || that.isNaN || that.isInfinite then false else b <= that.asBig
    inline def >(that: Double): Boolean   = if BigUtils.isBad(b) || that.isNaN || that.isInfinite then false else b > that.asBig
    inline def >=(that: Double): Boolean  = if BigUtils.isBad(b) || that.isNaN || that.isInfinite then false else b >= that.asBig

    // --- conversions ----------------------------------------------------------

    inline def toDouble: Double = if BigUtils.isBad(b) then Double.NaN else b.toDouble

    inline def toFloat: Float = if BigUtils.isBad(b) then Float.NaN else b.toFloat

    inline def toBig: Big = b

    inline def toInt: Int = b.toInt
    
    inline def toLong: Long = b.toLong
      
    inline def abs: Big = b.abs

  import scala.language.implicitConversions
  // Define equality comparisons as given instances
  given Conversion[Int, Big] = i => Big(BigDecimal(i))
  given Conversion[Long, Big] = l => Big(BigDecimal(l))
  given Conversion[Double, Big] = d => Big(BigDecimal(d))


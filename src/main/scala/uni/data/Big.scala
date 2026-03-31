package uni.data

import scala.math.BigDecimal
import scala.math.BigDecimal.*
export scala.math.BigDecimal.RoundingMode

export Big.{Big, big, asBig, zero, one, BigOne, ten, hundred, BigNaN, BadNum, BigZero}
export Big.{given Conversion[Int, Big], given Conversion[Long, Big], given Conversion[Float, Big], given Conversion[Double, Big]}
// Mat
object Big:

  private val MC = java.math.MathContext.DECIMAL128   // or a custom context
  // ------------------------------------------------------------
  // Opaque type
  // ------------------------------------------------------------
  opaque type Big = BigDecimal

  import scala.reflect.TypeTest
  given bigTypeTest: TypeTest[Any, Big] with
    def unapply(x: Any): Option[x.type & Big] = x match
      case b: BigDecimal => Some(b.asInstanceOf[x.type & Big])
      case _ => None

  private val BadNumLiteral = "-0.00000001234567890123456789"

  // Ensure BigNaN is defined as the opaque type Big
  val BigNaN: Big = Big(BigDecimal(BadNumLiteral))
  lazy val zero: Big = Big(BigDecimal(0))
  @deprecated("use BigNaN", "0.9") lazy val BadNum: Big  = BigNaN
  @deprecated("use Big.zero", "0.9") lazy val BigZero: Big = zero
  lazy val one: Big    = Big(BigDecimal(1))
  lazy val BigOne: Big = one
  lazy val ten: Big    = Big(BigDecimal(10))
  lazy val hundred: Big = Big(BigDecimal(100))

  // Defined locally so Big.scala has no compile dependency on BigUtils (breaks the Zinc cycle).
  // BigUtils.isBad has the same implementation; both check against BigNaN.
  private inline def isBad(n: Big): Boolean =
    (n.asInstanceOf[AnyRef] eq BigNaN.asInstanceOf[AnyRef]) || (n == BigNaN)

  // Constructors
  def apply(s: String): Big      = scala.util.Try(BigDecimal(s.trim.replaceAll("[,$]", ""))).getOrElse(BigNaN)
  def apply(d: Double): Big      = BigDecimal(d)
  def apply(i: Int): Big         = BigDecimal(i)
  def apply(l: Long): Big        = BigDecimal(l)
  def apply(bd: BigDecimal): Big = bd

  def big(str: String): Big      = scala.util.Try(BigDecimal(str.trim.replaceAll("[,$]", ""))).getOrElse(BigNaN)
  def big(d: Double): Big        = BigDecimal(d)
  def big(i: Int): Big           = BigDecimal(i)
  def big(l: Long): Big          = BigDecimal(l)
  def big(bd: BigDecimal): Big   = bd.asInstanceOf[Big]

  // Lowercase factory (public API)
//  def big(s: String): Big      = apply(s)
//  def big(d: Double): Big      = apply(d)
//  def big(i: Int): Big         = apply(i)
//  def big(l: Long): Big        = apply(l)
//  def big(bd: BigDecimal): Big = apply(bd)

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

  extension (s: String)
    inline def asBig: Big = Big(BigDecimal(s))

  extension (d: Double)
    inline def asBig: Big = Big(BigDecimal(d))

  extension (l: Long)
    inline def asBig: Big = Big(BigDecimal(l))

  extension (i: Int)
    inline def asBig: Big = Big(BigDecimal(i))

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
      if isBad(n) then BigNaN else Big(-n)

    // binary guard helper
    inline private def badGuard(that: Big)(f: => Big): Big =
      if isBad(n) || isBad(that) then BigNaN
      else Big(f)

    // --- arithmetic -----------------------------------------------------------

    inline def +(that: Big): Big = badGuard(that)(n + that)
    inline def -(that: Big): Big = badGuard(that)(n - that)
    inline def *(that: Big): Big = badGuard(that)(n * that)

    /*
    inline def /(that: Big): Big =
      if isBad(n) || isBad(that) then BigNaN
      else if that == Big(0) then BigNaN
      else Big(n / that)

    inline def /(that: Int): Big =
      if isBad(n) || that == 0 then BigNaN
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
    def /(that: Big): Big =
      if isBad(n) || isBad(that) || that == zero then BigNaN else Big(n / that)
    def /(that: Double): Big =
      if isBad(n) || !that.isFinite || that == 0.0 then BigNaN else Big(n / BigDecimal(that.toString))
    def /(that: Long): Big =
      if isBad(n) || that == 0L then BigNaN else Big(n / BigDecimal(that))
    def /(that: Int): Big =
      if isBad(n) || that == 0 then BigNaN else Big(n / BigDecimal(that))

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
    inline def <(that: Big): Boolean    = !isBad(n) && !isBad(that) && n < that
    inline def <=(that: Big): Boolean   = !isBad(n) && !isBad(that) && n <= that
    inline def >(that: Big): Boolean    = !isBad(n) && !isBad(that) && n > that
    inline def >=(that: Big): Boolean   = !isBad(n) && !isBad(that) && n >= that

    inline def <(that: Double): Boolean = !isBad(n) && n < that.asBig
    inline def <(that: Long): Boolean   = !isBad(n) && n < that.asBig
    inline def <(that: Int): Boolean    = !isBad(n) && n < that.asBig

    inline def <=(that: Double): Boolean = !isBad(n) && n <= that.asBig
    inline def <=(that: Long): Boolean   = !isBad(n) && n <= that.asBig
    inline def <=(that: Int): Boolean    = !isBad(n) && n <= that.asBig

    inline def >(that: Double): Boolean = !isBad(n) && n > that.asBig
    inline def >(that: Long): Boolean   = !isBad(n) && n > that.asBig
    inline def >(that: Int): Boolean    = !isBad(n) && n > that.asBig

    inline def >=(that: Double): Boolean = !isBad(n) && n >= that.asBig
    inline def >=(that: Long): Boolean   = !isBad(n) && n >= that.asBig
    inline def >=(that: Int): Boolean    = !isBad(n) && n >= that.asBig

    // --- conversions ----------------------------------------------------------

    inline def toDouble: Double = if isBad(n) then Double.NaN else n.toDouble

    inline def toFloat: Float = if isBad(n) then Float.NaN else n.toFloat

    inline def toBig: Big = n

    inline def toInt: Int = n.toInt
    
    inline def toLong: Long = n.toLong
      
    inline def abs: Big = if isBad(n) then BigNaN else n.abs
    def max(that: Big): Big = if isBad(n) || isBad(that) then BigNaN else if n >= that then n else that
    def min(that: Big): Big = if isBad(n) || isBad(that) then BigNaN else if n <= that then n else that

    inline def isValidInt: Boolean = n.isValidInt

    inline def isValidLong: Boolean = n.isValidLong

    inline def isNaN: Boolean = {
      n == BigNaN
    }
    inline def isNotNaN: Boolean = n != BigNaN
  
    def sqrt: Big =
      if isBad(n) then BigNaN
      else
        // BigDecimal.sqrt is available in Java 9+
        // .bigDecimal converts scala.math.BigDecimal -> java.math.BigDecimal
        val underlying: java.math.BigDecimal = n.value.bigDecimal
        Big(underlying.sqrt(Big.MC))

  // scalar / Big  (left-hand division only; *,+,- live at package scope in
  // VecExts.scala so they don't shadow Mat[Double] ops at higher priority.
  // Division stays here so it doesn't shadow existing Mat[Double] 1×1
  // scalar division when imported via `import uni.data.*`.)
  extension (n: Int)    @annotation.targetName("intDivBig")    def /(b: Big): Big = Big(n) / b
  extension (n: Long)   @annotation.targetName("longDivBig")   def /(b: Big): Big = Big(n) / b
  extension (n: Double) @annotation.targetName("doubleDivBig") def /(b: Big): Big = Big(n) / b
  extension (n: Float)  @annotation.targetName("floatDivBig")  def /(b: Big): Big = Big(n.toDouble) / b

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

  // Placed inside object Big so it sits in the implicit scope of the Big opaque type
  // (Scala 3 searches the defining object for given instances automatically).
  // .value is used to obtain the underlying BigDecimal and operate on it directly.
  given Fractional[Big] with
    def plus(x: Big, y: Big): Big    = if x.isNaN || y.isNaN then BigNaN else Big(x.value + y.value)
    def minus(x: Big, y: Big): Big   = if x.isNaN || y.isNaN then BigNaN else Big(x.value - y.value)
    def times(x: Big, y: Big): Big   = if x.isNaN || y.isNaN then BigNaN else Big(x.value * y.value)
    def negate(x: Big): Big          = if x.isNaN then BigNaN else Big(-x.value)
    def div(x: Big, y: Big): Big     =
      if x.isNaN || y.isNaN || y.value == BigDecimal(0) then BigNaN else Big(x.value / y.value)
    def fromInt(x: Int): Big         = Big(x)
    def parseString(s: String): Option[Big] = scala.util.Try(Big(s)).toOption
    def toInt(x: Big): Int           = x.value.toInt
    def toLong(x: Big): Long         = x.value.toLong
    def toFloat(x: Big): Float       = if x.isNaN then Float.NaN else x.value.toFloat
    def toDouble(x: Big): Double     = if x.isNaN then Double.NaN else x.value.toDouble
    def compare(x: Big, y: Big): Int =
      if x.isNaN || y.isNaN then 0 else x.value.compare(y.value)

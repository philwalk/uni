package uni.data

import munit.FunSuite
import uni.data.Big
import uni.data.Big.*
import uni.data.BigUtils.*

/** Extended coverage for the Big opaque type, covering constructors, mixed-type
 *  arithmetic, conversions, comparisons with primitives, BigNaN propagation,
 *  math ops, and the Numeric/Fractional typeclasses. */
class BigCoverageSuite extends FunSuite {

  // ============================================================================
  // Constructors
  // ============================================================================

  test("Big(Int) constructs correctly") {
    assertEquals(Big(42).value, BigDecimal(42))
    assertEquals(Big(0).value, BigDecimal(0))
    assertEquals(Big(-7).value, BigDecimal(-7))
  }

  test("Big(Long) constructs correctly") {
    assertEquals(Big(123456789L).value, BigDecimal(123456789L))
    assertEquals(Big(Long.MaxValue).value, BigDecimal(Long.MaxValue))
  }

  test("Big(Double) constructs correctly") {
    assertEquals(Big(3.14).value, BigDecimal(3.14))
    assertEquals(Big(0.0).value, BigDecimal(0.0))
  }

  test("Big(BigDecimal) constructs correctly") {
    val bd = BigDecimal("9999999999999999.99")
    assertEquals(Big(bd).value, bd)
  }

  // ============================================================================
  // Named constants
  // ============================================================================

  test("Big constants: zero, one, ten, hundred") {
    assertEquals(Big.zero.value, BigDecimal(0))
    assertEquals(Big.one.value,  BigDecimal(1))
    assertEquals(Big.ten.value,  BigDecimal(10))
    assertEquals(Big.hundred.value, BigDecimal(100))
  }

  // ============================================================================
  // Lowercase factory (big)
  // ============================================================================

  test("Big.big factory mirrors apply") {
    assertEquals(Big.big("12.5").value, BigDecimal("12.5"))
    assertEquals(Big.big(3),            Big(3))
    assertEquals(Big.big(4L),           Big(4L))
    assertEquals(Big.big(1.5),          Big(1.5))
    assertEquals(Big.big(BigDecimal(7)), Big(BigDecimal(7)))
  }

  // ============================================================================
  // asBig / toBig extension methods
  // ============================================================================

  test("Double.asBig / Long.asBig / Int.asBig") {
    assertEquals((3.14).asBig.value, BigDecimal(3.14))
    assertEquals((100L).asBig.value, BigDecimal(100L))
    assertEquals((5).asBig.value,    BigDecimal(5))
  }

  test("Double.toBig") {
    assertEquals((2.5).toBig.value, BigDecimal(2.5))
  }

  // ============================================================================
  // Implicit widening conversions
  // ============================================================================

  test("Int widens to Big implicitly") {
    val b: Big = 7
    assertEquals(b.value, BigDecimal(7))
  }

  test("Long widens to Big implicitly") {
    val b: Big = 100L
    assertEquals(b.value, BigDecimal(100L))
  }

  test("Double widens to Big implicitly") {
    val b: Big = 1.5
    assertEquals(b.value, BigDecimal(1.5))
  }

  test("Float widens to Big implicitly") {
    val b: Big = 2.0f
    assertEquals(b.value, BigDecimal(2.0f))
  }

  // ============================================================================
  // Mixed-type arithmetic: Big op primitive
  // ============================================================================

  test("Big + Int/Long/Double") {
    val b = Big(10)
    assertEquals((b + 3).value,    BigDecimal(13))
    assertEquals((b + 3L).value,   BigDecimal(13))
    assertEquals((b + 1.5).value,  BigDecimal("11.5"))
  }

  test("Big - Int/Long/Double") {
    val b = Big(10)
    assertEquals((b - 3).value,    BigDecimal(7))
    assertEquals((b - 3L).value,   BigDecimal(7))
    assertEquals((b - 1.5).value,  BigDecimal("8.5"))
  }

  test("Big * Int/Long/Double") {
    val b = Big(4)
    assertEquals((b * 3).value,    BigDecimal(12))
    assertEquals((b * 3L).value,   BigDecimal(12))
    assertEquals((b * 2.5).value,  BigDecimal("10.0"))
  }

  test("Big / Int (non-zero)") {
    assertEquals((Big(9) / 3).value, BigDecimal(3))
  }

  test("Big / Long (non-zero)") {
    assertEquals((Big(8) / 2L).value, BigDecimal(4))
  }

  test("Big / Double (non-zero)") {
    assertEqualsDouble((Big(5) / 2.0).toDouble, 2.5, 1e-12)
  }

  // ============================================================================
  // Mixed-type division edge cases → BigNaN
  // ============================================================================

  test("Big / 0 (Int) returns BigNaN") {
    assertEquals(Big(10) / 0, BigNaN)
  }

  test("Big / 0L (Long) returns BigNaN") {
    assertEquals(Big(10) / 0L, BigNaN)
  }

  test("Big / 0.0 (Double) returns BigNaN") {
    assertEquals(Big(10) / 0.0, BigNaN)
  }

  test("Big / NaN (Double) returns BigNaN") {
    val divideByNan = Big(10) / Double.NaN
    assertEquals(divideByNan, BigNaN)
  }

  test("Big / Infinity (Double) returns BigNaN") {
    assertEquals(Big(10) / Double.PositiveInfinity, BigNaN)
  }

  // ============================================================================
  // BigNaN propagation in mixed arithmetic
  // ============================================================================

  test("BigNaN + Int propagates BigNaN") {
    assertEquals(BigNaN + 5, BigNaN)
  }

  test("BigNaN - Long propagates BigNaN") {
    assertEquals(BigNaN - 5L, BigNaN)
  }

  test("BigNaN * Double propagates BigNaN") {
    assertEquals(BigNaN * 2.0, BigNaN)
  }

  // ============================================================================
  // Unary negation
  // ============================================================================

  test("unary - negates a valid Big") {
    assertEquals((-Big(5)).value, BigDecimal(-5))
    assertEquals((-Big(-3)).value, BigDecimal(3))
  }

  test("unary - on BigNaN returns BigNaN") {
    assertEquals(-BigNaN, BigNaN)
  }

  // ============================================================================
  // Comparisons with primitives
  // ============================================================================

  test("Big < Double / Long / Int") {
    assert(Big(1) < 2.0)
    assert(Big(1) < 2L)
    assert(Big(1) < 2)
  }

  test("Big <= Double / Long / Int") {
    assert(Big(2) <= 2.0)
    assert(Big(2) <= 2L)
    assert(Big(2) <= 2)
  }

  test("Big > Double / Long / Int") {
    assert(Big(3) > 2.0)
    assert(Big(3) > 2L)
    assert(Big(3) > 2)
  }

  test("Big >= Double / Long / Int") {
    assert(Big(2) >= 2.0)
    assert(Big(2) >= 2L)
    assert(Big(2) >= 2)
  }

  // ============================================================================
  // BigNaN short-circuits comparisons (no exception)
  // ============================================================================

  test("BigNaN comparisons all return false") {
    assert(!(BigNaN < Big(1)))
    assert(!(BigNaN <= Big(1)))
    assert(!(BigNaN > Big(1)))
    assert(!(BigNaN >= Big(1)))
    assert(!(Big(1) < BigNaN))
    assert(!(Big(1) > BigNaN))
  }

  // ============================================================================
  // Conversions
  // ============================================================================

  test("toDouble / toFloat / toInt / toLong on valid Big") {
    val b = Big(42)
    assertEqualsDouble(b.toDouble, 42.0, 1e-12)
    assertEquals(b.toFloat,  42.0f)
    assertEquals(b.toInt,    42)
    assertEquals(b.toLong,   42L)
  }

  test("toDouble on BigNaN returns Double.NaN") {
    assert(BigNaN.toDouble.isNaN)
  }

  test("toFloat on BigNaN returns Float.NaN") {
    assert(BigNaN.toFloat.isNaN)
  }

  test("toBig is identity") {
    val b = Big(7)
    assertEquals(b.toBig, b)
  }

  test("toBigDecimal unwraps to underlying BigDecimal") {
    val b = Big(3)
    assertEquals(b.toBigDecimal, BigDecimal(3))
  }

  // ============================================================================
  // isNaN / isNotNaN
  // ============================================================================

  test("isNaN is true only for BigNaN") {
    assert(BigNaN.isNaN)
    assert(!Big(0).isNaN)
    assert(!Big(42).isNaN)
  }

  test("isNotNaN is false only for BigNaN") {
    assert(!BigNaN.isNotNaN)
    assert(Big(1).isNotNaN)
  }

  // ============================================================================
  // abs
  // ============================================================================

  test("abs returns absolute value") {
    assertEquals(Big(-5).abs.value,  BigDecimal(5))
    assertEquals(Big(5).abs.value,   BigDecimal(5))
    assertEquals(Big(0).abs.value,   BigDecimal(0))
  }

  // ============================================================================
  // signum
  // ============================================================================

  test("signum returns -1 / 0 / 1") {
    assertEquals(Big(-3).signum, -1)
    assertEquals(Big(0).signum,   0)
    assertEquals(Big(7).signum,   1)
  }

  // ============================================================================
  // sqrt
  // ============================================================================

  test("sqrt of perfect square is exact") {
    assertEqualsDouble(Big(4).sqrt.toDouble,   2.0,  1e-10)
    assertEqualsDouble(Big(9).sqrt.toDouble,   3.0,  1e-10)
    assertEqualsDouble(Big(100).sqrt.toDouble, 10.0, 1e-10)
  }

  test("sqrt of non-square is high precision") {
    assertEqualsDouble(Big(2).sqrt.toDouble, math.sqrt(2), 1e-10)
  }

  // ============================================================================
  // ~^ power operator
  // ============================================================================

  test("~^ with Int exponent is exact") {
    assertEquals((Big(2) ~^ 10).value, BigDecimal(1024))
    assertEquals((Big(3) ~^ 3).value,  BigDecimal(27))
    assertEquals((Big(5) ~^ 0).value,  BigDecimal(1))
  }

  test("~^ with Long exponent is exact") {
    assertEquals((Big(2) ~^ 10L).value, BigDecimal(1024))
  }

  test("~^ with Float exponent (whole number) is exact") {
    assertEquals((Big(2) ~^ 8.0f).value, BigDecimal(256))
  }

  test("~^ with Double exponent (whole number) is exact") {
    assertEquals((Big(2) ~^ 10.0).value, BigDecimal(1024))
  }

  test("~^ with BigDecimal exponent (whole number) is exact") {
    assertEquals((Big(2) ~^ BigDecimal(10)).value, BigDecimal(1024))
  }

  test("~^ with Big exponent (whole number) is exact") {
    assertEquals((Big(2) ~^ Big(10)).value, BigDecimal(1024))
  }

  test("~^ with fractional Double exponent uses double fallback") {
    assertEqualsDouble((Big(4) ~^ 0.5).toDouble, 2.0, 1e-9)
    assertEqualsDouble((Big(8) ~^ (1.0/3)).toDouble, 2.0, 1e-9)
  }

  test("~^ with fractional Big exponent uses double fallback") {
    assertEqualsDouble((Big(4) ~^ Big(0.5)).toDouble, 2.0, 1e-9)
  }

  // ============================================================================
  // toPlainString
  // ============================================================================

  test("toPlainString avoids scientific notation") {
    val b = Big(1000000000L)
    assert(!b.toPlainString.contains("E"), s"expected plain, got: ${b.toPlainString}")
    assertEquals(b.toPlainString, "1000000000")
  }

  // ============================================================================
  // unapply extractor
  // ============================================================================

  test("Big extractor matches valid values") {
    val result = Big(42) match
      case Big(v) => v.toDouble
      case _      => -1.0
    assertEqualsDouble(result, 42.0, 1e-12)
  }

  test("Big extractor does not match BigNaN") {
    val result = BigNaN match
      case Big(_) => "matched"
      case _      => "no match"
    assertEquals(result, "no match")
  }

  // ============================================================================
  // Numeric[Big] typeclass
  // ============================================================================

  test("Numeric[Big].fromInt") {
    val num = summon[Numeric[Big]]
    assertEquals(num.fromInt(7), Big(7))
  }

  test("Numeric[Big].negate") {
    val num = summon[Numeric[Big]]
    assertEquals(num.negate(Big(5)), Big(-5))
  }

//  test("Numeric[Big].parseString") {
//    val num = summon[Numeric[Big]]
//    assertEquals(num.parseString("3.14").map(_.toDouble).getOrElse(-1.0), 3.14, 1e-12)
//    assert(num.parseString("bad").isNaN)
//  }

  test("Numeric[Big].compare") {
    val num = summon[Numeric[Big]]
    assert(num.compare(Big(1), Big(2)) < 0)
    assert(num.compare(Big(2), Big(2)) == 0)
    assert(num.compare(Big(3), Big(2)) > 0)
  }

  // ============================================================================
  // isValidInt / isValidLong
  // ============================================================================

  test("isValidInt true for small integers, false for large or fractional") {
    assert(Big(42).isValidInt)
    assert(!Big(1e18.toLong + 1).isValidInt)
    assert(!Big(3.14).isValidInt)
  }

  test("isValidLong true for long-range integers") {
    assert(Big(Long.MaxValue).isValidLong)
    assert(!Big(3.14).isValidLong)
  }
}

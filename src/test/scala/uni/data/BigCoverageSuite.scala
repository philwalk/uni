package uni.data

import munit.FunSuite
import uni.data.Big
import uni.data.Big.*

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

  test("BigNaN.abs returns BigNaN") {
    assertEquals(BigNaN.abs, BigNaN)
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

  test("BigNaN.sqrt returns BigNaN") {
    assertEquals(BigNaN.sqrt, BigNaN)
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

  // ============================================================================
  // Big(String) constructor — comprehensive parsing parity with str2num
  // ============================================================================

  test("Big(String) parses plain integer") {
    assertEquals(Big("42").value, BigDecimal(42))
    assertEquals(Big("0").value,  BigDecimal(0))
  }

  test("Big(String) parses decimal") {
    assertEquals(Big("3.14").value, BigDecimal("3.14"))
  }

  test("Big(String) parses negative") {
    assertEquals(Big("-42").value, BigDecimal(-42))
    assertEquals(Big("-3.14").value, BigDecimal("-3.14"))
  }

  test("Big(String) handles leading dot") {
    assertEqualsDouble(Big(".5").toDouble, 0.5, 1e-12)
  }

  test("Big(String) strips dollar signs and commas") {
    assertEquals(Big("$1,234.56").value, BigDecimal("1234.56"))
    assertEquals(Big("$100").value,      BigDecimal(100))
    assertEquals(Big("1,000").value,     BigDecimal(1000))
  }

  test("Big(String) trailing percent divides by 100") {
    assertEqualsDouble(Big("75%").toDouble,   0.75,  1e-12)
    assertEqualsDouble(Big("50%").toDouble,   0.5,   1e-12)
    assertEqualsDouble(Big("100%").toDouble,  1.0,   1e-12)
    assertEqualsDouble(Big("12.5%").toDouble, 0.125, 1e-12)
  }

  test("Big(String) negative percent divides by 100 and preserves sign") {
    assertEqualsDouble(Big("-25%").toDouble, -0.25, 1e-12)
  }

  test("Big(String) dollar-sign and comma with percent") {
    assertEqualsDouble(Big("$50%").toDouble,     0.5,  1e-12)
    assertEqualsDouble(Big("$1,000%").toDouble, 10.0,  1e-12)
  }

  test("Big(String) handles scientific notation") {
    assertEqualsDouble(Big("1e5").toDouble,  100000.0, 1e-6)
    assertEqualsDouble(Big("1E5").toDouble,  100000.0, 1e-6)
    assertEqualsDouble(Big("1.5e2").toDouble,  150.0,  1e-10)
  }

  test("Big(String) handles leading/trailing whitespace") {
    assertEquals(Big("  42  ").value, BigDecimal(42))
    assertEqualsDouble(Big("  50%  ").toDouble, 0.5, 1e-12)
  }

  test("Big(String) returns BigNaN for non-numeric input") {
    assertEquals(Big("not-a-number"), BigNaN)
    assertEquals(Big("abc"),          BigNaN)
    assertEquals(Big("12x34"),        BigNaN)
  }

  test("Big(String) returns BigNaN for empty or whitespace") {
    assertEquals(Big(""),   BigNaN)
    assertEquals(Big("  "), BigNaN)
  }

  test("Big(String) returns BigNaN when only symbols remain after cleanup") {
    assertEquals(Big("$"), BigNaN)
    assertEquals(Big(","), BigNaN)
  }

  // big(str) must mirror apply(String) exactly
  test("Big.big(String) mirrors apply for all parsing cases") {
    assertEqualsDouble(Big.big("75%").toDouble,      0.75,  1e-12)
    assertEqualsDouble(Big.big("-25%").toDouble,    -0.25,  1e-12)
    assertEqualsDouble(Big.big("$1,234.56").toDouble, 1234.56, 1e-10)
    assertEqualsDouble(Big.big("1e5").toDouble,     100000.0, 1e-6)
    assertEquals(Big.big("abc"), BigNaN)
    assertEquals(Big.big(""),    BigNaN)
  }

  // ============================================================================
  // String.asBig extension
  // ============================================================================

  test("String.asBig parses decimal string") {
    assertEquals("9.99".asBig.value, BigDecimal("9.99"))
  }

  // ============================================================================
  // max / min
  // ============================================================================

  test("max returns larger value") {
    assertEquals(Big(7).max(Big(3)), Big(7))
    assertEquals(Big(3).max(Big(7)), Big(7))
    assertEquals(Big(5).max(Big(5)), Big(5))
  }

  test("max with BigNaN returns BigNaN") {
    assertEquals(BigNaN.max(Big(1)), BigNaN)
    assertEquals(Big(1).max(BigNaN), BigNaN)
  }

  test("min returns smaller value") {
    assertEquals(Big(3).min(Big(7)), Big(3))
    assertEquals(Big(7).min(Big(3)), Big(3))
    assertEquals(Big(5).min(Big(5)), Big(5))
  }

  test("min with BigNaN returns BigNaN") {
    assertEquals(BigNaN.min(Big(1)), BigNaN)
    assertEquals(Big(1).min(BigNaN), BigNaN)
  }

  // ============================================================================
  // setScale
  // ============================================================================

  test("setScale rounds to given decimal places") {
    val b = Big(3.14159)
    assertEquals(b.setScale(2, RoundingMode.HALF_UP).value, BigDecimal("3.14"))
  }

  // ============================================================================
  // Float / Double implicit conversion — NaN and Infinity guard
  // ============================================================================

  test("Float.NaN converts to BigNaN") {
    val b: Big = Float.NaN
    assertEquals(b, BigNaN)
  }

  test("Float.PositiveInfinity converts to BigNaN") {
    val b: Big = Float.PositiveInfinity
    assertEquals(b, BigNaN)
  }

  test("Float.NegativeInfinity converts to BigNaN") {
    val b: Big = Float.NegativeInfinity
    assertEquals(b, BigNaN)
  }

  test("Double.NaN converts to BigNaN") {
    val b: Big = Double.NaN
    assertEquals(b, BigNaN)
  }

  test("Double.PositiveInfinity converts to BigNaN") {
    val b: Big = Double.PositiveInfinity
    assertEquals(b, BigNaN)
  }

  test("Double.NegativeInfinity converts to BigNaN") {
    val b: Big = Double.NegativeInfinity
    assertEquals(b, BigNaN)
  }

  // ============================================================================
  // Numeric[Big] remaining methods
  // ============================================================================

  test("Numeric[Big].parseString returns Some for valid input") {
    val num = summon[Numeric[Big]]
    assertEquals(num.parseString("3.14").map(_.toDouble).getOrElse(-1.0), 3.14, 1e-12)
  }

  test("Numeric[Big].parseString returns Some(BigNaN) for invalid input") {
    // Big(str) catches parse failures and returns BigNaN rather than throwing,
    // so parseString("bad") = Some(BigNaN), not None.
    val num = summon[Numeric[Big]]
    assert(num.parseString("bad").exists(_.isNaN))
  }

  test("Numeric[Big].toInt / toLong / toFloat / toDouble") {
    val num = summon[Numeric[Big]]
    assertEquals(num.toInt(Big(5)),          5)
    assertEquals(num.toLong(Big(5)),         5L)
    assertEquals(num.toFloat(Big(2.5f)),     2.5f)
    assertEqualsDouble(num.toDouble(Big(2.5)), 2.5, 1e-12)
  }

  // ============================================================================
  // badGuard: right-hand BigNaN
  // ============================================================================

  test("valid Big + BigNaN returns BigNaN") {
    assertEquals(Big(5) + BigNaN, BigNaN)
  }

  test("valid Big - BigNaN returns BigNaN") {
    assertEquals(Big(5) - BigNaN, BigNaN)
  }

  test("valid Big * BigNaN returns BigNaN") {
    assertEquals(Big(5) * BigNaN, BigNaN)
  }

  test("valid Big / BigNaN returns BigNaN") {
    assertEquals(Big(5) / BigNaN, BigNaN)
  }

  test("BigNaN + BigNaN returns BigNaN") {
    assertEquals(BigNaN + BigNaN, BigNaN)
  }

  // ============================================================================
  // Big-op-Big valid arithmetic (dedicated Big×Big overload, separate inline copy
  // from Big-op-primitive overloads)
  // ============================================================================

  test("Big + Big / Big - Big / Big * Big valid paths") {
    assertEquals((Big(5) + Big(3)).value, BigDecimal(8))
    assertEquals((Big(5) - Big(3)).value, BigDecimal(2))
    assertEquals((Big(5) * Big(3)).value, BigDecimal(15))
  }

  test("Big / Big valid path and zero-divisor guard") {
    assertEqualsDouble((Big(10) / Big(2)).toDouble, 5.0, 1e-12)
    assertEquals(Big(5) / Big(0), BigNaN)
  }

  test("BigNaN / Big propagates BigNaN (left-NaN for /(Big))") {
    assertEquals(BigNaN / Big(5), BigNaN)
  }

  // ============================================================================
  // Left-NaN for arithmetic overloads not yet individually covered
  // (each inline copy generates independent bytecode branches)
  // ============================================================================

  test("BigNaN on left for -(Big) and *(Big)") {
    assertEquals(BigNaN - Big(5), BigNaN)
    assertEquals(BigNaN * Big(5), BigNaN)
  }

  test("BigNaN on left for +(Long) +(Double) -(Int) -(Double)") {
    assertEquals(BigNaN + 5L,  BigNaN)
    assertEquals(BigNaN + 1.0, BigNaN)
    assertEquals(BigNaN - 5,   BigNaN)
    assertEquals(BigNaN - 1.0, BigNaN)
  }

  test("BigNaN on left for *(Int) and *(Long)") {
    assertEquals(BigNaN * 5,  BigNaN)
    assertEquals(BigNaN * 5L, BigNaN)
  }

  test("BigNaN on left for /(Int) /(Long) /(Double)") {
    assertEquals(BigNaN / 2,   BigNaN)
    assertEquals(BigNaN / 2L,  BigNaN)
    assertEquals(BigNaN / 2.0, BigNaN)
  }

  // ============================================================================
  // Right-NaN via Double.NaN implicit conversion for primitive arithmetic overloads
  // (covers isBad(that)=true branch in +(Double), -(Double), *(Double) inline copies)
  // ============================================================================

  test("Big + Double.NaN / Big - Double.NaN / Big * Double.NaN return BigNaN") {
    assertEquals(Big(5) + Double.NaN, BigNaN)
    assertEquals(Big(5) - Double.NaN, BigNaN)
    assertEquals(Big(5) * Double.NaN, BigNaN)
  }

  // ============================================================================
  // Comparisons: Big vs Big — true and false cases
  // ============================================================================

  test("<(Big) >(Big) <=(Big) >=(Big) true and false cases") {
    assert(Big(1) < Big(3));          assert(!(Big(3) < Big(1)))
    assert(Big(3) > Big(1));          assert(!(Big(1) > Big(3)))
    assert(Big(1) <= Big(3));         assert(Big(1) <= Big(1));   assert(!(Big(3) <= Big(1)))
    assert(Big(3) >= Big(1));         assert(Big(1) >= Big(1));   assert(!(Big(1) >= Big(3)))
  }

  test("Big(1) <= BigNaN and Big(1) >= BigNaN return false (right-NaN branch)") {
    assert(!(Big(1) <= BigNaN))
    assert(!(Big(1) >= BigNaN))
  }

  // ============================================================================
  // Comparisons: BigNaN on left for primitive overloads (separate inline copies
  // from the BigNaN-left cases for the Big overloads)
  // ============================================================================

  test("BigNaN compared with Double/Long/Int via all operators returns false") {
    assert(!(BigNaN < 2.0));  assert(!(BigNaN < 2L));  assert(!(BigNaN < 2))
    assert(!(BigNaN <= 2.0)); assert(!(BigNaN <= 2L)); assert(!(BigNaN <= 2))
    assert(!(BigNaN > 2.0));  assert(!(BigNaN > 2L));  assert(!(BigNaN > 2))
    assert(!(BigNaN >= 2.0)); assert(!(BigNaN >= 2L)); assert(!(BigNaN >= 2))
  }

  // ============================================================================
  // Comparisons: false branches for primitive overloads (valid comparison, wrong direction)
  // ============================================================================

  test("primitive comparisons false branches") {
    assert(!(Big(1) < 0.5));  assert(!(Big(1) < 0L));  assert(!(Big(1) < 0))
    assert(!(Big(3) <= 2.0)); assert(!(Big(3) <= 2L)); assert(!(Big(3) <= 2))
    assert(!(Big(1) > 2.0));  assert(!(Big(1) > 2L));  assert(!(Big(1) > 2))
    assert(!(Big(2) >= 3.0)); assert(!(Big(2) >= 3L)); assert(!(Big(2) >= 3))
  }

  // ============================================================================
  // Fractional[Big] typeclass — NaN-aware; summon[Numeric[Big]] resolves here
  // because Fractional is a subtype of Numeric and this is the only given.
  // ============================================================================

  test("Fractional[Big] valid arithmetic") {
    val frac = summon[Fractional[Big]]
    assertEqualsDouble(frac.plus(Big(2), Big(3)).toDouble,  5.0,  1e-10)
    assertEqualsDouble(frac.minus(Big(5), Big(3)).toDouble, 2.0,  1e-10)
    assertEqualsDouble(frac.times(Big(4), Big(3)).toDouble, 12.0, 1e-10)
    assertEqualsDouble(frac.div(Big(10), Big(4)).toDouble,  2.5,  1e-10)
    assertEqualsDouble(frac.negate(Big(5)).toDouble,        -5.0, 1e-12)
  }

  test("Fractional[Big].plus/minus/times NaN propagation") {
    val frac = summon[Fractional[Big]]
    assertEquals(frac.plus(BigNaN, Big(1)),  BigNaN)
    assertEquals(frac.plus(Big(1),  BigNaN), BigNaN)
    assertEquals(frac.minus(BigNaN, Big(1)), BigNaN)
    assertEquals(frac.minus(Big(1), BigNaN), BigNaN)
    assertEquals(frac.times(BigNaN, Big(1)), BigNaN)
    assertEquals(frac.times(Big(1), BigNaN), BigNaN)
  }

  test("Fractional[Big].div NaN propagation and zero-divisor") {
    val frac = summon[Fractional[Big]]
    assertEquals(frac.div(BigNaN, Big(2)), BigNaN)
    assertEquals(frac.div(Big(2), BigNaN), BigNaN)
    assertEquals(frac.div(Big(2), Big(0)), BigNaN)
  }

  test("Fractional[Big].negate NaN propagation") {
    val frac = summon[Fractional[Big]]
    assertEquals(frac.negate(BigNaN), BigNaN)
  }

  test("Fractional[Big].compare returns 0 when either operand is BigNaN") {
    val frac = summon[Fractional[Big]]
    assertEquals(frac.compare(BigNaN, Big(1)), 0)
    assertEquals(frac.compare(Big(1), BigNaN), 0)
    assert(frac.compare(Big(3), Big(1)) > 0)
    assert(frac.compare(Big(1), Big(3)) < 0)
    assertEquals(frac.compare(Big(2), Big(2)), 0)
  }

  // ============================================================================
  // ~^ fractional Float exponent — covers the else (non-integer) branch in the
  // Float-instantiated generic ~^[T: Fractional] method
  // ============================================================================

  test("~^ with fractional Float exponent uses double fallback") {
    assertEqualsDouble((Big(4) ~^ 0.5f).toDouble, 2.0, 1e-10)
    assertEqualsDouble((Big(9) ~^ 0.5f).toDouble, 3.0, 1e-10)
  }
}

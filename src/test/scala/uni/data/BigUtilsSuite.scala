package uni.data

import munit.FunSuite
import uni.data.Big
import uni.data.Big.*
import uni.data.BigUtils.*

class BigUtilsSuite extends FunSuite {

  // ============================================================================
  // isBad / orBad
  // ============================================================================

  test("isBad is true for BigNaN, false otherwise") {
    assert(isBad(BigNaN))
    assert(!isBad(Big(0)))
    assert(!isBad(Big(42)))
  }

  test("orBad returns value when Some, BigNaN when None") {
    assertEquals(orBad(Some(Big(7))), Big(7))
    assertEquals(orBad(None),        BigNaN)
  }

  // ============================================================================
  // str2num
  // ============================================================================

  test("str2num parses plain integer") {
    assertEquals(str2num("42").value, BigDecimal(42))
  }

  test("str2num parses decimal") {
    assertEquals(str2num("3.14").value, BigDecimal("3.14"))
  }

  test("str2num strips commas and dollar signs") {
    assertEquals(str2num("$1,234.56").value, BigDecimal("1234.56"))
  }

  test("str2num handles leading dot") {
    assertEqualsDouble(str2num(".5").toDouble, 0.5, 1e-12)
  }

  test("str2num handles percentage: divides by 100") {
    assertEqualsDouble(str2num("50%").toDouble, 0.5, 1e-12)
  }

  test("str2num returns BigNaN for non-numeric input") {
    assertEquals(str2num("hello"), BigNaN)
    assertEquals(str2num(""),      BigNaN)
  }

  test("str2num returns BigNaN for empty-after-cleaning") {
    assertEquals(str2num("$"), BigNaN)
  }

  // ============================================================================
  // validNumChar
  // ============================================================================

  test("validNumChar accepts digits, dot, minus, E, plus, percent, dollar, comma") {
    assert(validNumChar('0'))
    assert(validNumChar('9'))
    assert(validNumChar('.'))
    assert(validNumChar('-'))
    assert(validNumChar('E'))
    assert(validNumChar('e'))
    assert(validNumChar('+'))
    assert(validNumChar('%'))
    assert(validNumChar('$'))
    assert(validNumChar(','))
    assert(!validNumChar('a'))
    assert(!validNumChar(' '))
  }

  // ============================================================================
  // isNumeric
  // ============================================================================

  test("isNumeric is false for empty string") {
    assert(!isNumeric(""))
  }

  test("isNumeric is true for plain integers and decimals") {
    assert(isNumeric("42"))
    assert(isNumeric("3.14"))
    assert(isNumeric("-5"))
  }

  test("isNumeric is false for alpha strings") {
    assert(!isNumeric("abc"))
    assert(!isNumeric("1a2"))
  }

  test("isNumeric is false for multiple dashes") {
    assert(!isNumeric("1-2-3"))
  }

  test("isNumeric is true for K/M/B suffixed numbers (NumPattern3)") {
    assert(isNumeric("5K"))
    assert(isNumeric("2M"))
    assert(isNumeric("100B"))
  }

  test("isNumeric is true for parenthesised negatives (NumPattern1)") {
    assert(isNumeric("(100)"))
    assert(isNumeric("(1.5)"))
  }

  // ============================================================================
  // getMostSpecificType
  // ============================================================================

  test("getMostSpecificType: empty string returns empty string") {
    assertEquals(getMostSpecificType(""), "")
  }

  test("getMostSpecificType: plain number returns Big") {
    getMostSpecificType("42") match
      case b: BigDecimal => assertEqualsDouble(b.toDouble, 42.0, 1e-10)
      case other         => fail(s"expected Big, got $other")
  }

  test("getMostSpecificType: short non-numeric returns String") {
    assertEquals(getMostSpecificType("abc"), "abc")
  }

  test("getMostSpecificType: K suffix scales by 1_000") {
    getMostSpecificType("5K") match
      case b: BigDecimal => assertEqualsDouble(b.toDouble, 5_000.0, 1e-6)
      case other         => fail(s"expected Big, got $other")
  }

  test("getMostSpecificType: M suffix scales by 1_000_000") {
    getMostSpecificType("2M") match
      case b: BigDecimal => assertEqualsDouble(b.toDouble, 2_000_000.0, 1e-3)
      case other         => fail(s"expected Big, got $other")
  }

  test("getMostSpecificType: B suffix scales by 1_000_000_000") {
    getMostSpecificType("1B") match
      case b: BigDecimal => assertEqualsDouble(b.toDouble, 1_000_000_000.0, 1.0)
      case other         => fail(s"expected Big, got $other")
  }

  test("getMostSpecificType: parenthesised negative") {
    getMostSpecificType("(100)") match
      case b: BigDecimal => assertEqualsDouble(b.toDouble, -100.0, 1e-10)
      case other         => fail(s"expected Big, got $other")
  }

  test("getMostSpecificType: leading minus") {
    getMostSpecificType("-42") match
      case b: BigDecimal => assertEqualsDouble(b.toDouble, -42.0, 1e-10)
      case other         => fail(s"expected Big, got $other")
  }

  test("getMostSpecificType: percentage") {
    getMostSpecificType("50%") match
      case b: BigDecimal => assertEqualsDouble(b.toDouble, 0.5, 1e-10)
      case other         => fail(s"expected Big, got $other")
  }

  test("getMostSpecificType: date-like string returns DateTime or String") {
    // A date string long enough (≥ 7 chars) should not come back as Big
    val result = getMostSpecificType("2024-01-15")
    assert(!result.isInstanceOf[BigDecimal], s"date should not be Big: $result")
  }

  // ============================================================================
  // numStr / numStrPct / num2string
  // ============================================================================

  test("numStr returns N/A for BigNaN") {
    assert(numStr(BigNaN).contains("N/A"))
  }

  test("numStr formats a regular Big value") {
    val s = numStr(Big(1234.5))
    assert(s.contains("1234.50"), s"got: '$s'")
  }

  test("numStr abbreviates billions") {
    import BigUtils.NumFormat
    val s = numStr(Big(2.5e9), NumFormat.Abbrev)
    assert(s.endsWith("B"), s"expected B suffix, got: '$s'")
  }

  test("numStr abbreviates millions") {
    import BigUtils.NumFormat
    val s = numStr(Big(3.2e6), NumFormat.Abbrev)
    assert(s.endsWith("M"), s"expected M suffix, got: '$s'")
  }

  test("numStr suppresses -0.00") {
    val s = numStr(Big(-0.001))
    assert(!s.contains("-"), s"expected no minus for near-zero, got: '$s'")
  }

  test("numStrPct formats as percentage") {
    val s = numStrPct(Big(0.25))
    assert(s.contains("25.00"), s"got: '$s'")
    assert(s.contains("%"),     s"got: '$s'")
  }

  test("num2string formats with custom decimals") {
    val s = num2string(Big(3.14159), dec = 3)
    assert(s.contains("3.142") || s.contains("3.141"), s"got: '$s'")
  }

  // ============================================================================
  // big2double
  // ============================================================================

  test("big2double returns Double.NaN for BigNaN") {
    assert(big2double(BigNaN).isNaN)
  }

  test("big2double returns value for valid Big") {
    assertEqualsDouble(big2double(Big(2.5)), 2.5, 1e-12)
  }

  // ============================================================================
  // toStr (CVD union type)
  // ============================================================================

  test("toStr handles String") {
    assertEquals(toStr("hello"), "hello")
  }

  test("toStr handles Int") {
    assertEquals(toStr(42), "42")
  }

  test("toStr handles Long") {
    assertEquals(toStr(100L), "100")
  }

  test("toStr handles BigNaN") {
    assertEquals(toStr(BigNaN), "N/A")
  }

  test("toStr handles valid Big") {
    assert(toStr(Big(7)).nonEmpty)
  }

  test("toStr handles Some(Int)") {
    assertEquals(toStr(Some(5)), "5")
  }

  test("toStr handles None") {
    assertEquals(toStr(None), "")
  }

  test("toStr handles LocalDateTime") {
    import java.time.LocalDateTime
    val dt = LocalDateTime.of(2024, 3, 15, 0, 0, 0)
    assertEquals(toStr(dt), "2024-03-15")
  }

  // ============================================================================
  // isNumeric — NumPattern2 (scientific notation with commas)
  // ============================================================================

  test("isNumeric is true for comma-separated scientific notation (NumPattern2)") {
    assert(isNumeric("1,234E5"))
  }

  // ============================================================================
  // numStr — NumFormat.IntPercent
  // ============================================================================

  test("numStr with IntPercent formats as integer percent") {
    import BigUtils.NumFormat
    val s = numStr(Big(0.75), NumFormat.IntPercent)
    assert(s.contains("75"), s"expected 75, got: '$s'")
    assert(s.contains("%"),   s"expected %, got: '$s'")
  }
}

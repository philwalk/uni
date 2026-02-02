package uni.data

import munit.*
import uni.data.BigUtils.*
import uni.data.Big
import uni.data.Big.*

final class BigSuite extends FunSuite:

  // ------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------

  private def BG(s: String): Big = Big(s)

  private def BD(s: String): BigDecimal = str2num(s).value

  // ------------------------------------------------------------
  // Construction
  // ------------------------------------------------------------

  test("Big.apply parses valid numeric strings") {
    val left = BG("123")
    val leftValue = left.value
    val right = BigDecimal(123)
    assertEquals(leftValue, right)
    assertEquals(BG("0.75").value, BD("0.75"))
    assertEquals(BG("1,234.56").value, BD("1234.56"))
  }

  test("Big.apply returns BadNum for invalid strings") {
    assertEquals(BG("abc"), BadNum)
    assertEquals(BG("12x34"), BadNum)
  }

  // ------------------------------------------------------------
  // Arithmetic
  // ------------------------------------------------------------

  test("Big arithmetic works through extension methods") {
    val a = BG("10")
    val b = BG("2.5")

    assertEquals((a + b).value, BigDecimal("12.5"))
    assertEquals((a - b).value, BigDecimal("7.5"))
    assertEquals((a * b).value, BigDecimal("25.0"))
    assertEquals((a / b).value, BigDecimal("4.0"))
  }

  test("Arithmetic with BadNum propagates BadNum") {
    val bad = BG("not-a-number")
    val good = BG("10")

    assertEquals((bad + good), BadNum)
    assertEquals((good + bad), BadNum)
    assertEquals((bad * bad), BadNum)
  }

  // ------------------------------------------------------------
  // Comparison
  // ------------------------------------------------------------

  test("Big comparison delegates to BigDecimal") {
    assert(BG("10") > BG("2"))
    assert(BG("2") < BG("10"))
    assert(BG("5") <= BG("5"))
    assert(BG("5") >= BG("5"))
  }

  // ------------------------------------------------------------
  // Formatting
  // ------------------------------------------------------------

  test("num2string formats normally") {
    val x = BG("123.456")
    val s = num2string(x, dec = 1)
    assertEquals(s.trim, "123.5")
  }

  test("numStr handles BadNum") {
    val s = numStr(BadNum)
    assert(s.contains("N/A"))
  }

  test("numStrPct formats percentages") {
    val x = BG("0.125")
    val s = numStrPct(x)
    assert(s.contains("12.5"))
  }

  // ------------------------------------------------------------
  // str2num
  // ------------------------------------------------------------

  test("str2num handles percent") {
    assertEquals(str2num("50%").value, BigDecimal("0.5"))
  }

  test("str2num handles commas and currency symbols") {
    assertEquals(str2num("$1,234.50").value, BigDecimal("1234.50"))
  }

  test("str2num rejects invalid characters") {
    assertEquals(str2num("12a3"), BadNum)
  }

  // ------------------------------------------------------------
  // getMostSpecificType
  // ------------------------------------------------------------

  test("getMostSpecificType returns Big for numeric strings") {
    getMostSpecificType("123") match
      case b: BigDecimal => assertEquals(b, BigDecimal(123))
      case _             => fail("Expected Big")
  }

  test("getMostSpecificType returns String for non-numeric") {
    getMostSpecificType("hello") match
      case s: String => assertEquals(s, "hello")
      case _         => fail("Expected String")
  }

  test("getMostSpecificType returns DateTime for date-like strings") {
    val dt = getMostSpecificType("2024-01-15")
    assert(dt.isInstanceOf[uni.time.DateTime])
  }

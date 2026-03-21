package uni.data

import munit.FunSuite
import uni.data.Big.*

/** Covers Mat.scala branches not yet reached: in-place Float/Big ops, axis Bool methods,
 *  type-dispatch for sign/power, unsupported-type error paths, predicate all/any. */
class MatDataCoverageSuite3 extends FunSuite:

  // ============================================================================
  // In-place scalar ops — Float type
  // ============================================================================

  test(":+= scalar on MatF") {
    val m = MatF.row(1.0f, 2.0f, 3.0f)
    m :+= 10.0f
    assertEquals(m(0, 0).toDouble, 11.0, 0.001)
    assertEquals(m(0, 2).toDouble, 13.0, 0.001)
  }

  test(":-= scalar on MatF") {
    val m = MatF.row(5.0f, 6.0f)
    m :-= 2.0f
    assertEquals(m(0, 0).toDouble, 3.0, 0.001)
  }

  test(":*= scalar on MatF") {
    val m = MatF.row(2.0f, 3.0f)
    m :*= 4.0f
    assertEquals(m(0, 0).toDouble, 8.0, 0.001)
    assertEquals(m(0, 1).toDouble, 12.0, 0.001)
  }

  test(":/= scalar on MatF") {
    val m = MatF.row(6.0f, 9.0f)
    m :/= 3.0f
    assertEquals(m(0, 0).toDouble, 2.0, 0.001)
    assertEquals(m(0, 1).toDouble, 3.0, 0.001)
  }

  test(":+= Int scalar on MatF (Fractional.fromInt branch)") {
    val m = MatF.row(1.0f, 2.0f)
    m :+= 5
    assertEquals(m(0, 0).toDouble, 6.0, 0.001)
    assertEquals(m(0, 1).toDouble, 7.0, 0.001)
  }

  test(":/= Int scalar on MatF (Fractional.fromInt branch)") {
    val m = MatF.row(4.0f, 8.0f)
    m :/= 2
    assertEquals(m(0, 0).toDouble, 2.0, 0.001)
    assertEquals(m(0, 1).toDouble, 4.0, 0.001)
  }

  // ============================================================================
  // In-place scalar ops — Big type
  // ============================================================================

  test(":+= scalar on MatB") {
    val m = MatB.row(1.0, 2.0)
    m :+= Big(10.0)
    assertEqualsDouble(m(0, 0).toDouble, 11.0, 1e-10)
  }

  test(":-= scalar on MatB") {
    val m = MatB.row(5.0, 6.0)
    m :-= Big(2.0)
    assertEqualsDouble(m(0, 0).toDouble, 3.0, 1e-10)
  }

  test(":*= scalar on MatB") {
    val m = MatB.row(2.0, 3.0)
    m :*= Big(4.0)
    assertEqualsDouble(m(0, 0).toDouble, 8.0, 1e-10)
    assertEqualsDouble(m(0, 1).toDouble, 12.0, 1e-10)
  }

  test(":/= scalar on MatB") {
    val m = MatB.row(6.0, 9.0)
    m :/= Big(3.0)
    assertEqualsDouble(m(0, 0).toDouble, 2.0, 1e-10)
    assertEqualsDouble(m(0, 1).toDouble, 3.0, 1e-10)
  }

  // ============================================================================
  // In-place Mat ops — shape mismatch throws
  // ============================================================================

  test(":+= Mat shape mismatch throws") {
    val m = MatD.ones(2, 3)
    val n = MatD.ones(2, 4)
    intercept[IllegalArgumentException] { m :+= n }
  }

  test(":-= Mat shape mismatch throws") {
    val m = MatD.ones(3, 2)
    val n = MatD.ones(2, 2)
    intercept[IllegalArgumentException] { m :-= n }
  }

  // ============================================================================
  // all(axis) / any(axis) — invalid axis throws
  // ============================================================================

  test("all(axis): invalid axis throws") {
    val m = MatD.ones(2, 2).gt(0.0)
    intercept[IllegalArgumentException] { m.all(2) }
  }

  test("any(axis): invalid axis throws") {
    val m = MatD.ones(2, 2).gt(0.0)
    intercept[IllegalArgumentException] { m.any(2) }
  }

  // ============================================================================
  // all(predicate) / any(predicate) — false/no-match cases
  // ============================================================================

  test("all(predicate): returns false when one element fails") {
    val m = MatD.row(1.0, 2.0, -1.0)
    assert(!m.all(_ > 0))
  }

  test("any(predicate): returns false when no element matches") {
    val m = MatD.row(1.0, 2.0, 3.0)
    assert(!m.any(_ < 0))
  }

  test("any(predicate): returns true when first element matches (early exit)") {
    val m = MatD.row(-5.0, 1.0, 2.0)
    assert(m.any(_ < 0))
  }

  // ============================================================================
  // pinv — unsupported type throws
  // ============================================================================

  test("pinv: Float type throws UnsupportedOperationException") {
    val m = MatF.ones(2, 2)
    intercept[UnsupportedOperationException] { m.pinv() }
  }

  // ============================================================================
  // cholesky — unsupported type throws
  // ============================================================================

  test("cholesky: Float type throws UnsupportedOperationException") {
    val m = MatF.ones(2, 2)
    intercept[UnsupportedOperationException] { m.cholesky }
  }

  // ============================================================================
  // sign — Float and Big dispatch branches
  // ============================================================================

  test("sign on MatF: negative → -1, positive → 1, zero → 0") {
    val m = MatF.row(-3.0f, 0.0f, 5.0f)
    val s = m.sign
    assertEquals(s(0, 0).toDouble, -1.0, 0.001)
    assertEquals(s(0, 1).toDouble,  0.0, 0.001)
    assertEquals(s(0, 2).toDouble,  1.0, 0.001)
  }

  test("sign on MatB: negative → -1, positive → 1, zero → 0") {
    val m = MatB.row(-2.0, 0.0, 7.0)
    val s = m.sign
    assertEqualsDouble(s(0, 0).toDouble, -1.0, 1e-10)
    assertEqualsDouble(s(0, 1).toDouble,  0.0, 1e-10)
    assertEqualsDouble(s(0, 2).toDouble,  1.0, 1e-10)
  }

  // ============================================================================
  // power(Double) — Big dispatch branch
  // ============================================================================

  test("power(Double) on MatB: BigDecimal branch") {
    val m = MatB.row(2.0, 3.0)
    val r = m.power(2.0)
    assertEqualsDouble(r(0, 0).toDouble, 4.0, 1e-8)
    assertEqualsDouble(r(0, 1).toDouble, 9.0, 1e-8)
  }

  // ============================================================================
  // power(Int) — Float and Big dispatch branches
  // ============================================================================

  test("power(Int) on MatF: Integer exponent via Numeric.times loop") {
    val m = MatF.row(2.0f, 3.0f)
    val r = m.power(3)
    assertEquals(r(0, 0).toDouble, 8.0, 0.01)
    assertEquals(r(0, 1).toDouble, 27.0, 0.01)
  }

  test("power(Int) on MatB: Integer exponent via Numeric.times loop") {
    val m = MatB.row(2.0, 4.0)
    val r = m.power(2)
    assertEqualsDouble(r(0, 0).toDouble, 4.0, 1e-10)
    assertEqualsDouble(r(0, 1).toDouble, 16.0, 1e-10)
  }

  // ============================================================================
  // addToEachRow / addToEachCol — Float type (success path)
  // ============================================================================

  test("addToEachRow on MatF: adds row vector to every row") {
    val m = MatF.ones(3, 2)   // all 1s
    val v = MatF.row(10.0f, 20.0f)
    val r = m.addToEachRow(v)
    assertEquals(r.rows, 3)
    assertEquals(r(0, 0).toDouble, 11.0, 0.001)
    assertEquals(r(2, 1).toDouble, 21.0, 0.001)
  }

  test("addToEachCol on MatF: adds col vector to every col") {
    val m = MatF.ones(2, 3)   // all 1s
    val v = MatF.col(10.0f, 20.0f)
    val r = m.addToEachCol(v)
    assertEquals(r.cols, 3)
    assertEquals(r(0, 0).toDouble, 11.0, 0.001)
    assertEquals(r(1, 2).toDouble, 21.0, 0.001)
  }

  // ============================================================================
  // sum / mean on Float type (exercise Float dispatch in those methods)
  // ============================================================================

  test("sum on MatF returns correct total") {
    val m = MatF.row(1.0f, 2.0f, 3.0f, 4.0f)
    assertEquals(m.sum.toDouble, 10.0, 0.001)
  }

  test("mean on MatF returns correct average") {
    val m = MatF.row(2.0f, 4.0f, 6.0f)
    assertEquals(m.mean.toDouble, 4.0, 0.001)
  }


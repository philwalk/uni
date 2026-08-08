package uni.data

import munit.FunSuite
import uni.data.Big.*

/** Covers slow-path activation functions, Float/Big math dispatch branches,
 *  and softmax/logSoftmax axis=0 paths not reached by MatTest. */
class MatActivationSuite extends FunSuite:

  // ============================================================================
  // sigmoid — slow path (Float type, Big type, transposed Double)
  // ============================================================================

  test("sigmoid on MatF: slow path (non-Double type)") {
    val m = MatF.row(-2.0f, 0.0f, 2.0f)
    val r = m.sigmoid
    assertEquals(r(0, 1), 0.5, 0.001)       // sigmoid(0) = 0.5
    assert(r(0, 0) > 0.1 && r(0, 0) < 0.15) // sigmoid(-2) ≈ 0.119
    assert(r(0, 2) > 0.85 && r(0, 2) < 0.9) // sigmoid(2) ≈ 0.881
  }

  test("sigmoid on MatB: slow path (Big type)") {
    val m = MatB.row(-1.0, 0.0, 1.0)
    val r = m.sigmoid
    assertEquals(r(0, 1), 0.5, 0.001)
    assert(r(0, 0) < 0.5)
    assert(r(0, 2) > 0.5)
  }

  test("sigmoid on transposed MatD: slow path (non-contiguous)") {
    // Transpose a 3×1 column to 1×3 row — transposed=true triggers slow path
    val col = MatD.col(-1.0, 0.0, 1.0)   // 3×1, contiguous
    val m = col.T                          // 1×3, transposed → not contiguous
    assert(!m.isContiguous)
    val r = m.sigmoid
    assertEquals(r(0, 1), 0.5, 0.001)
    assert(r(0, 0) < 0.5)
    assert(r(0, 2) > 0.5)
  }

  test("sigmoid: negative branch (x < 0) uses exp(x)/(1+exp(x)) for stability") {
    // Ensure the negative-x branch is exercised in the slow path
    val m = MatF.row(-10.0f, -1.0f)
    val r = m.sigmoid
    assert(r(0, 0) > 0.0 && r(0, 0) < 0.001)   // sigmoid(-10) ≈ 4.5e-5
    assert(r(0, 1) > 0.1 && r(0, 1) < 0.3)       // sigmoid(-1) ≈ 0.269
  }

  // ============================================================================
  // relu — slow path (Float type, Big type, transposed Double)
  // ============================================================================

  test("relu on MatF: slow path (non-Double type)") {
    val m = MatF.row(-3.0f, 0.0f, 5.0f)
    val r = m.relu
    assertEquals(r(0, 0).toDouble, 0.0, 0.001)
    assertEquals(r(0, 1).toDouble, 0.0, 0.001)
    assertEquals(r(0, 2).toDouble, 5.0, 0.001)
  }

  test("relu on MatB: slow path (Big type)") {
    val m = MatB.row(-2.0, 0.0, 3.0)
    val r = m.relu
    assertEqualsDouble(r(0, 0).toDouble, 0.0, 1e-10)
    assertEqualsDouble(r(0, 2).toDouble, 3.0, 1e-10)
  }

  test("relu on transposed MatD: slow path (non-contiguous)") {
    val col = MatD.col(-1.0, 0.0, 2.0)
    val m = col.T
    assert(!m.isContiguous)
    val r = m.relu
    assertEquals(r(0, 0), 0.0, 1e-12)
    assertEquals(r(0, 2), 2.0, 1e-12)
  }

  // ============================================================================
  // leakyRelu — Float type (exercises non-Double code path)
  // ============================================================================

  test("leakyRelu on MatF: negative values scaled by alpha") {
    val m = MatF.row(-4.0f, 0.0f, 3.0f)
    val r = m.leakyRelu(0.1)
    assertEquals(r(0, 0), -0.4, 0.001)    // -4 * 0.1
    assertEquals(r(0, 1), 0.0, 0.001)
    assertEquals(r(0, 2), 3.0, 0.001)
  }

  test("leakyRelu on MatB: negative values scaled by alpha") {
    val m = MatB.row(-2.0, 0.0, 5.0)
    val r = m.leakyRelu(0.2)
    assertEquals(r(0, 0), -0.4, 0.001)    // -2 * 0.2
    assertEquals(r(0, 2), 5.0, 0.001)
  }

  // ============================================================================
  // softmax — axis=0 path (column-wise softmax, untested in MatTest)
  // ============================================================================

  test("softmax(axis=0): column-wise softmax sums to 1 per column") {
    val m = MatD((1.0, 2.0), (3.0, 4.0), (5.0, 6.0))
    val r = m.softmax(axis = 0)
    assertEquals(r.rows, 3)
    assertEquals(r.cols, 2)
    // Each column should sum to 1
    assertEqualsDouble(r(0, 0) + r(1, 0) + r(2, 0), 1.0, 1e-10)
    assertEqualsDouble(r(0, 1) + r(1, 1) + r(2, 1), 1.0, 1e-10)
    // Larger values get larger weights
    assert(r(2, 0) > r(1, 0) && r(1, 0) > r(0, 0))
  }

  test("softmax(axis=0): invalid axis throws") {
    val m = MatD.ones(2, 2)
    intercept[IllegalArgumentException] { m.softmax(axis = 2) }
  }

  // ============================================================================
  // logSoftmax — axis=0 path
  // ============================================================================

  test("logSoftmax(axis=0): exp of result equals softmax(axis=0)") {
    val m = MatD((1.0, 2.0), (3.0, 4.0))
    val ls = m.logSoftmax(axis = 0)
    val sm = m.softmax(axis = 0)
    assertEqualsDouble(math.exp(ls(0, 0)), sm(0, 0), 1e-10)
    assertEqualsDouble(math.exp(ls(1, 1)), sm(1, 1), 1e-10)
  }

  // ============================================================================
  // sqrt — Big dispatch branch
  // ============================================================================

  test("sqrt on MatB: uses Big.sqrt (BigDecimal branch)") {
    val m = MatB.row(4.0, 9.0, 16.0)
    val r = m.sqrt
    assertEqualsDouble(r(0, 0).toDouble, 2.0, 1e-10)
    assertEqualsDouble(r(0, 1).toDouble, 3.0, 1e-10)
    assertEqualsDouble(r(0, 2).toDouble, 4.0, 1e-10)
  }

  // ============================================================================
  // abs — Float and Big branches (both positive and negative values)
  // ============================================================================

  test("abs on MatF: negates negative values, leaves positive unchanged") {
    val m = MatF.row(-3.0f, 0.0f, 5.0f)
    val r = m.abs
    assertEquals(r(0, 0).toDouble, 3.0, 0.001)
    assertEquals(r(0, 1).toDouble, 0.0, 0.001)
    assertEquals(r(0, 2).toDouble, 5.0, 0.001)
  }

  test("abs on MatB: negates negative values, leaves positive unchanged") {
    val m = MatB.row(-7.0, 0.0, 4.0)
    val r = m.abs
    assertEqualsDouble(r(0, 0).toDouble, 7.0, 1e-10)
    assertEqualsDouble(r(0, 1).toDouble, 0.0, 1e-10)
    assertEqualsDouble(r(0, 2).toDouble, 4.0, 1e-10)
  }

  // ============================================================================
  // std(axis) — Float type (axis dispatch on non-Double)
  // ============================================================================

  test("std(axis=0) on MatF: std per column") {
    // Two identical rows → std per col = 0
    val m = MatF((2.0f, 4.0f, 6.0f), (2.0f, 4.0f, 6.0f))
    val r = m.std(0)
    assertEquals(r.rows, 1)
    assertEquals(r.cols, 3)
    assertEquals(r(0, 0).toDouble, 0.0, 0.001)
  }

  test("std(axis=1) on MatF: std per row") {
    // Three identical values in each row → std = 0
    val m = MatF((3.0f, 3.0f, 3.0f), (5.0f, 5.0f, 5.0f))
    val r = m.std(1)
    assertEquals(r.rows, 2)
    assertEquals(r.cols, 1)
    assertEquals(r(0, 0).toDouble, 0.0, 0.001)
    assertEquals(r(1, 0).toDouble, 0.0, 0.001)
  }

  // ============================================================================
  // exp and log — Float type: asInstanceOf[Float] on a Double fails at JVM boxing
  // ============================================================================

  test("exp on MatF: ClassCastException (Double cannot be cast to Float via asInstanceOf)") {
    val m = MatF.row(0.0f, 1.0f)
    intercept[ClassCastException] { m.exp }
  }

  test("log on MatF: ClassCastException (same asInstanceOf limitation)") {
    val m = MatF.row(1.0f, math.E.toFloat)
    intercept[ClassCastException] { m.log }
  }


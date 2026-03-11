package uni.data

import munit.FunSuite
import Mat.*
import uni.data.Big.*

/** Covers untested math/trig/activation branches in Mat.scala:
 *  sqrt Float, round Float/Big/decimals, cumsum(axis=1)/flat, diff flat/axis=1,
 *  tile, sin/cos/tan/tanh/sinh/cosh, arcsin/arccos/arctan/arctan2, floor/ceil,
 *  gelu, elu, dropout(training=false), cross, kron, outer,
 *  histogram(binEdges), power(Double) on Float, std Float/Big, maximum/minimum. */
class MatMathSuite extends FunSuite:

  // ============================================================================
  // sqrt — Float and Big dispatch branches
  // ============================================================================

  test("sqrt on MatF: Float dispatch branch") {
    val m = MatF.row(4.0f, 9.0f, 16.0f)
    val r = m.sqrt
    assertEquals(r(0, 0).toDouble, 2.0, 0.001)
    assertEquals(r(0, 1).toDouble, 3.0, 0.001)
    assertEquals(r(0, 2).toDouble, 4.0, 0.001)
  }

  // ============================================================================
  // round — Float, Big, and decimals > 0
  // ============================================================================

  test("round on MatF: Float dispatch branch (decimals=0)") {
    val m = MatF.row(1.4f, 1.6f, -2.5f)
    val r = m.round(0)
    assertEquals(r(0, 0).toDouble, 1.0, 0.001)
    assertEquals(r(0, 1).toDouble, 2.0, 0.001)
  }

  test("round on MatB: BigDecimal dispatch branch (decimals=0)") {
    val m = MatB.row(1.4, 1.6, -2.5)
    val r = m.round(0)
    assertEqualsDouble(r(0, 0).toDouble, 1.0, 1e-10)
    assertEqualsDouble(r(0, 1).toDouble, 2.0, 1e-10)
  }

  test("round(decimals=2) on MatD: preserves decimal places") {
    val m = MatD.row(1.2345, 9.9951)
    val r = m.round(2)
    assertEqualsDouble(r(0, 0), 1.23, 1e-10)
    assertEqualsDouble(r(0, 1), 10.0, 1e-10)
  }

  // ============================================================================
  // cumsum — axis=1 (across rows) and no-axis (flat)
  // ============================================================================

  test("cumsum(axis=1): cumulative sum across each row") {
    val m = MatD((1.0, 2.0, 3.0), (4.0, 5.0, 6.0))
    val r = m.cumsum(1)
    assertEquals(r.rows, 2)
    assertEquals(r.cols, 3)
    assertEqualsDouble(r(0, 0), 1.0, 1e-12)
    assertEqualsDouble(r(0, 1), 3.0, 1e-12)
    assertEqualsDouble(r(0, 2), 6.0, 1e-12)
    assertEqualsDouble(r(1, 0), 4.0, 1e-12)
    assertEqualsDouble(r(1, 2), 15.0, 1e-12)
  }

  test("cumsum (no axis): flattens then runs cumsum") {
    val m = MatD((1.0, 2.0), (3.0, 4.0))
    val r = m.cumsum
    assertEquals(r.rows, 1)
    assertEquals(r.cols, 4)
    assertEqualsDouble(r(0, 0), 1.0, 1e-12)
    assertEqualsDouble(r(0, 1), 3.0, 1e-12)
    assertEqualsDouble(r(0, 2), 6.0, 1e-12)
    assertEqualsDouble(r(0, 3), 10.0, 1e-12)
  }

  // ============================================================================
  // diff — no-axis (flat) and axis=1
  // ============================================================================

  test("diff (no axis): differences of flattened matrix") {
    val m = MatD.row(1.0, 4.0, 9.0, 16.0)
    val r = m.diff
    assertEquals(r.cols, 3)
    assertEqualsDouble(r(0, 0), 3.0, 1e-12)
    assertEqualsDouble(r(0, 1), 5.0, 1e-12)
    assertEqualsDouble(r(0, 2), 7.0, 1e-12)
  }

  test("diff(axis=1): column-wise differences") {
    val m = MatD((1.0, 4.0, 9.0), (2.0, 6.0, 12.0))
    val r = m.diff(1)
    assertEquals(r.rows, 2)
    assertEquals(r.cols, 2)
    assertEqualsDouble(r(0, 0), 3.0, 1e-12)   // 4-1
    assertEqualsDouble(r(0, 1), 5.0, 1e-12)   // 9-4
    assertEqualsDouble(r(1, 0), 4.0, 1e-12)   // 6-2
  }

  // ============================================================================
  // tile
  // ============================================================================

  test("tile(2, 3): tiles matrix 2 row-wise and 3 col-wise") {
    val m = MatD((1.0, 2.0), (3.0, 4.0))
    val r = m.tile(2, 3)
    assertEquals(r.rows, 4)
    assertEquals(r.cols, 6)
    assertEqualsDouble(r(0, 0), 1.0, 1e-12)
    assertEqualsDouble(r(0, 2), 1.0, 1e-12)   // col 2 wraps to col 0
    assertEqualsDouble(r(2, 0), 1.0, 1e-12)   // row 2 wraps to row 0
  }

  test("tile(1, 1): returns identical matrix") {
    val m = MatD.ones(2, 3)
    val r = m.tile(1, 1)
    assertEquals(r.rows, 2)
    assertEquals(r.cols, 3)
    assert(r.allclose(m))
  }

  // ============================================================================
  // Trig functions — sin, cos, tan on MatF (exercises the loop path)
  // ============================================================================

  test("sin on MatF: returns Mat[Double] with correct values") {
    val m = MatF.row(0.0f, (math.Pi / 2).toFloat)
    val r = m.sin
    assertEqualsDouble(r(0, 0), 0.0, 1e-6)
    assertEqualsDouble(r(0, 1), 1.0, 1e-4)
  }

  test("cos on MatF: returns Mat[Double] with correct values") {
    val m = MatF.row(0.0f, math.Pi.toFloat)
    val r = m.cos
    assertEqualsDouble(r(0, 0), 1.0, 1e-6)
    assertEqualsDouble(r(0, 1), -1.0, 1e-4)
  }

  test("tan on MatD: returns Mat[Double] with correct values") {
    val m = MatD.row(0.0, math.Pi / 4)
    val r = m.tan
    assertEqualsDouble(r(0, 0), 0.0, 1e-12)
    assertEqualsDouble(r(0, 1), 1.0, 1e-10)
  }

  // ============================================================================
  // Inverse trig — arcsin, arccos, arctan, arctan2
  // ============================================================================

  test("arcsin on MatD: inverse of sin") {
    val m = MatD.row(0.0, 1.0, -1.0)
    val r = m.arcsin
    assertEqualsDouble(r(0, 0), 0.0, 1e-12)
    assertEqualsDouble(r(0, 1), math.Pi / 2, 1e-12)
    assertEqualsDouble(r(0, 2), -math.Pi / 2, 1e-12)
  }

  test("arccos on MatD: inverse of cos") {
    val m = MatD.row(1.0, -1.0, 0.0)
    val r = m.arccos
    assertEqualsDouble(r(0, 0), 0.0, 1e-12)
    assertEqualsDouble(r(0, 1), math.Pi, 1e-12)
    assertEqualsDouble(r(0, 2), math.Pi / 2, 1e-12)
  }

  test("arctan on MatD: inverse of tan") {
    val m = MatD.row(0.0, 1.0, -1.0)
    val r = m.arctan
    assertEqualsDouble(r(0, 0), 0.0, 1e-12)
    assertEqualsDouble(r(0, 1), math.Pi / 4, 1e-12)
    assertEqualsDouble(r(0, 2), -math.Pi / 4, 1e-12)
  }

  test("arctan2 on MatD: angle from y,x pairs") {
    val y = MatD.row(1.0, 1.0)
    val x = MatD.row(1.0, 0.0)
    val r = y.arctan2(x)
    assertEqualsDouble(r(0, 0), math.Pi / 4, 1e-12)   // atan2(1,1) = Pi/4
    assertEqualsDouble(r(0, 1), math.Pi / 2, 1e-12)   // atan2(1,0) = Pi/2
  }

  test("arctan2: shape mismatch throws") {
    val y = MatD.row(1.0, 2.0)
    val x = MatD.row(1.0)
    intercept[IllegalArgumentException] { y.arctan2(x) }
  }

  // ============================================================================
  // Hyperbolic trig — sinh, cosh, tanh
  // ============================================================================

  test("sinh on MatD: correct values") {
    val m = MatD.row(0.0, 1.0)
    val r = m.sinh
    assertEqualsDouble(r(0, 0), 0.0, 1e-12)
    assertEqualsDouble(r(0, 1), math.sinh(1.0), 1e-12)
  }

  test("cosh on MatD: correct values") {
    val m = MatD.row(0.0, 1.0)
    val r = m.cosh
    assertEqualsDouble(r(0, 0), 1.0, 1e-12)
    assertEqualsDouble(r(0, 1), math.cosh(1.0), 1e-12)
  }

  test("tanh on MatD: correct values at key points") {
    val m = MatD.row(0.0, 1.0, -1.0)
    val r = m.tanh
    assertEqualsDouble(r(0, 0), 0.0, 1e-12)
    assertEqualsDouble(r(0, 1), math.tanh(1.0), 1e-12)
    assertEqualsDouble(r(0, 2), math.tanh(-1.0), 1e-12)
  }

  test("tanh on MatF: input is Float, output is Mat[Double]") {
    val m = MatF.row(0.0f, 1.0f)
    val r = m.tanh
    assertEqualsDouble(r(0, 0), 0.0, 1e-4)
    assertEqualsDouble(r(0, 1), math.tanh(1.0), 1e-4)
  }

  // ============================================================================
  // floor / ceil
  // ============================================================================

  test("floor on MatD: rounds down") {
    val m = MatD.row(-1.7, 0.0, 1.3)
    val r = m.floor
    assertEqualsDouble(r(0, 0), -2.0, 1e-12)
    assertEqualsDouble(r(0, 1),  0.0, 1e-12)
    assertEqualsDouble(r(0, 2),  1.0, 1e-12)
  }

  test("ceil on MatD: rounds up") {
    val m = MatD.row(-1.7, 0.0, 1.3)
    val r = m.ceil
    assertEqualsDouble(r(0, 0), -1.0, 1e-12)
    assertEqualsDouble(r(0, 1),  0.0, 1e-12)
    assertEqualsDouble(r(0, 2),  2.0, 1e-12)
  }

  test("floor on MatF: Float input gives Mat[Double]") {
    val m = MatF.row(-1.5f, 2.9f)
    val r = m.floor
    assertEqualsDouble(r(0, 0), -2.0, 1e-4)
    assertEqualsDouble(r(0, 1),  2.0, 1e-4)
  }

  // ============================================================================
  // gelu — GELU activation
  // ============================================================================

  test("gelu on MatD: correct values at 0 and extreme") {
    val m = MatD.row(0.0, 1.0, -1.0)
    val r = m.gelu
    // gelu(0) = 0.5 * 0 * (1 + tanh(0)) = 0
    assertEqualsDouble(r(0, 0), 0.0, 1e-10)
    // gelu(1) ≈ 0.841
    assert(r(0, 1) > 0.8 && r(0, 1) < 0.9)
    // gelu(-1) ≈ -0.159
    assert(r(0, 2) > -0.2 && r(0, 2) < 0.0)
  }

  test("gelu on MatF: correct gelu output type is Double") {
    val m = MatF.row(0.0f, 2.0f)
    val r = m.gelu
    assertEqualsDouble(r(0, 0), 0.0, 1e-4)
    assert(r(0, 1) > 1.9)   // gelu(2) ≈ 1.954
  }

  // ============================================================================
  // elu — ELU activation (positive and negative branches)
  // ============================================================================

  test("elu on MatD: positive passes through, negative uses alpha*(exp(x)-1)") {
    val m = MatD.row(-1.0, 0.0, 2.0)
    val r = m.elu(1.0)
    assertEqualsDouble(r(0, 0), math.exp(-1.0) - 1.0, 1e-10)   // negative branch
    assertEqualsDouble(r(0, 1), 0.0, 1e-12)                      // zero → 0 (negative branch, exp(0)-1=0)
    assertEqualsDouble(r(0, 2), 2.0, 1e-12)                      // positive passes through
  }

  test("elu with alpha=2.0: scales negative branch") {
    val m = MatD.row(-1.0, -2.0)
    val r = m.elu(2.0)
    assertEqualsDouble(r(0, 0), 2.0 * (math.exp(-1.0) - 1.0), 1e-10)
    assertEqualsDouble(r(0, 1), 2.0 * (math.exp(-2.0) - 1.0), 1e-10)
  }

  test("elu on MatF: Float input handled correctly") {
    val m = MatF.row(-1.0f, 1.0f)
    val r = m.elu(1.0)
    assert(r(0, 0) < 0.0)
    assertEqualsDouble(r(0, 1), 1.0, 0.001)
  }

  // ============================================================================
  // dropout — training=false (inference) path
  // ============================================================================

  test("dropout(training=false): returns values unchanged as Double") {
    val m = MatD.row(1.0, 2.0, 3.0)
    val r = m.dropout(p = 0.5, training = false)
    assertEqualsDouble(r(0, 0), 1.0, 1e-12)
    assertEqualsDouble(r(0, 1), 2.0, 1e-12)
    assertEqualsDouble(r(0, 2), 3.0, 1e-12)
  }

  test("dropout(p=0.0, training=true): no elements dropped") {
    val m = MatD.row(1.0, 2.0, 3.0)
    val r = m.dropout(p = 0.0, training = true, seed = 42L)
    // With p=0, scale=1/(1-0)=1.0, all elements kept
    assertEqualsDouble(r(0, 0), 1.0, 1e-12)
    assertEqualsDouble(r(0, 1), 2.0, 1e-12)
  }

  test("dropout: invalid p throws") {
    val m = MatD.ones(2, 2)
    intercept[IllegalArgumentException] { m.dropout(p = -0.1) }
    intercept[IllegalArgumentException] { m.dropout(p = 1.0) }
  }

  test("dropout(training=true, seed): reproducible with seed") {
    val m = MatD.ones(3, 3)
    val r1 = m.dropout(p = 0.5, training = true, seed = 100L)
    val r2 = m.dropout(p = 0.5, training = true, seed = 100L)
    assert(r1.allclose(r2))
  }

  // ============================================================================
  // cross product
  // ============================================================================

  test("cross: standard 3D vectors") {
    val a = MatD.row(1.0, 0.0, 0.0)
    val b = MatD.row(0.0, 1.0, 0.0)
    val r = a.cross(b)
    assertEqualsDouble(r(0, 0), 0.0, 1e-12)
    assertEqualsDouble(r(0, 1), 0.0, 1e-12)
    assertEqualsDouble(r(0, 2), 1.0, 1e-12)
  }

  test("cross: non-3D vectors throw") {
    val a = MatD.row(1.0, 2.0)
    val b = MatD.row(3.0, 4.0)
    intercept[IllegalArgumentException] { a.cross(b) }
  }

  // ============================================================================
  // kron — Kronecker product
  // ============================================================================

  test("kron: 2x2 by 2x2 gives 4x4") {
    val a = MatD((1.0, 0.0), (0.0, 1.0))   // identity
    val b = MatD((2.0, 3.0), (4.0, 5.0))
    val r = a.kron(b)
    assertEquals(r.rows, 4)
    assertEquals(r.cols, 4)
    // a(0,0)=1 → top-left 2x2 = b
    assertEqualsDouble(r(0, 0), 2.0, 1e-12)
    assertEqualsDouble(r(0, 1), 3.0, 1e-12)
    // a(0,1)=0 → top-right 2x2 = 0
    assertEqualsDouble(r(0, 2), 0.0, 1e-12)
    // a(1,1)=1 → bottom-right 2x2 = b
    assertEqualsDouble(r(2, 2), 2.0, 1e-12)
  }

  test("kron: 1x1 by 2x2 gives 2x2 scaled") {
    val a = MatD.row(3.0)   // 1x1
    val b = MatD((1.0, 2.0), (3.0, 4.0))
    val r = a.kron(b)
    assertEquals(r.rows, 2)
    assertEquals(r.cols, 2)
    assertEqualsDouble(r(0, 0), 3.0, 1e-12)
    assertEqualsDouble(r(1, 1), 12.0, 1e-12)
  }

  // ============================================================================
  // outer — outer product
  // ============================================================================

  test("outer: 3-elem row by 2-elem row gives 3x2") {
    val a = MatD.row(1.0, 2.0, 3.0)
    val b = MatD.row(4.0, 5.0)
    val r = a.outer(b)
    assertEquals(r.rows, 3)
    assertEquals(r.cols, 2)
    assertEqualsDouble(r(0, 0), 4.0, 1e-12)
    assertEqualsDouble(r(1, 0), 8.0, 1e-12)
    assertEqualsDouble(r(2, 1), 15.0, 1e-12)
  }

  test("outer: empty vector throws") {
    val a = MatD.empty
    val b = MatD.row(1.0, 2.0)
    intercept[IllegalArgumentException] { a.outer(b) }
  }

  // ============================================================================
  // histogram with explicit binEdges
  // ============================================================================

  test("histogram(binEdges): basic 3-bin split") {
    val m = MatD.row(0.5, 1.5, 2.5, 3.5, 4.5)
    val (counts, edges) = m.histogram(Seq(0.0, 2.0, 4.0, 5.0))
    assertEquals(edges.length, 4)
    assertEquals(counts.length, 3)
    assertEquals(counts(0), 2)   // [0-2): 0.5, 1.5
    assertEquals(counts(1), 2)   // [2-4): 2.5, 3.5
    assertEquals(counts(2), 1)   // [4-5]: 4.5
  }

  test("histogram(binEdges): value equal to last edge goes in last bin") {
    val m = MatD.row(1.0, 2.0, 3.0)
    val (counts, _) = m.histogram(Seq(1.0, 2.0, 3.0))
    assertEquals(counts.sum, 3)
    // 1.0 → bin 0 (1.0 >= 1.0 && < 3.0, binary search → bin 0)
    // 2.0 → bin 1 (binary search → bin 1)
    // 3.0 == last edge → counts(last bin = 1)++
    assertEquals(counts(0), 1)
    assertEquals(counts(1), 2)
  }

  test("histogram(binEdges): requires at least 2 edges") {
    val m = MatD.row(1.0)
    intercept[IllegalArgumentException] { m.histogram(Seq(1.0)) }
  }

  // ============================================================================
  // power(Double) — Float dispatch branch
  // ============================================================================

  test("power(Double) on MatF: Float dispatch branch") {
    val m = MatF.row(2.0f, 3.0f)
    val r = m.power(2.0)
    assertEquals(r(0, 0).toDouble, 4.0, 0.001)
    assertEquals(r(0, 1).toDouble, 9.0, 0.001)
  }

  test("power(Int) negative exponent throws UnsupportedOperationException") {
    val m = MatD.row(2.0)
    intercept[UnsupportedOperationException] { m.power(-1) }
  }

  // ============================================================================
  // std() — no-axis, Float and Big dispatch
  // ============================================================================

  test("std() on MatF: Float dispatch branch (variance → Float sqrt)") {
    val m = MatF.row(2.0f, 4.0f, 6.0f)
    val s = m.std
    // mean=4, variance = ((4+0+4)/3) = 8/3, std = sqrt(8/3) ≈ 1.633
    assert(s.toDouble > 1.5 && s.toDouble < 1.8)
  }

  test("std() on MatB: BigDecimal dispatch branch") {
    val m = MatB.row(2.0, 4.0, 6.0)
    val s = m.std
    assert(s.toDouble > 1.5 && s.toDouble < 1.8)
  }

  test("std() on MatD: identical elements → std = 0") {
    val m = MatD.row(5.0, 5.0, 5.0)
    assertEqualsDouble(m.std, 0.0, 1e-12)
  }

  // ============================================================================
  // maximum / minimum
  // ============================================================================

  test("maximum: element-wise max of two matrices") {
    val a = MatD((1.0, 5.0), (3.0, 2.0))
    val b = MatD((4.0, 2.0), (1.0, 6.0))
    val r = a.maximum(b)
    assertEqualsDouble(r(0, 0), 4.0, 1e-12)
    assertEqualsDouble(r(0, 1), 5.0, 1e-12)
    assertEqualsDouble(r(1, 0), 3.0, 1e-12)
    assertEqualsDouble(r(1, 1), 6.0, 1e-12)
  }

  test("minimum: element-wise min of two matrices") {
    val a = MatD((1.0, 5.0), (3.0, 2.0))
    val b = MatD((4.0, 2.0), (1.0, 6.0))
    val r = a.minimum(b)
    assertEqualsDouble(r(0, 0), 1.0, 1e-12)
    assertEqualsDouble(r(0, 1), 2.0, 1e-12)
    assertEqualsDouble(r(1, 0), 1.0, 1e-12)
    assertEqualsDouble(r(1, 1), 2.0, 1e-12)
  }

  test("maximum: shape mismatch throws") {
    val a = MatD.ones(2, 3)
    val b = MatD.ones(2, 4)
    intercept[IllegalArgumentException] { a.maximum(b) }
  }

  test("minimum: shape mismatch throws") {
    val a = MatD.ones(3, 2)
    val b = MatD.ones(2, 2)
    intercept[IllegalArgumentException] { a.minimum(b) }
  }

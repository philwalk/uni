package uni.data

import munit.FunSuite
import Mat.*
//import uni.data.Big.*

/** Covers Mat.scala branches not reached by MatTest, MatCoverageSuite, or MatNaNSuite.
 *  Focuses on: Float-type dispatches, error paths, broadcastTo, vstack/hstack,
 *  comparison Int overloads, boolean mask edge cases, and numeric edge cases. */
class MatDataCoverageSuite2 extends FunSuite:

  // ============================================================================
  // Comparison operators — Int overloads (gte, lte, :!= with Int argument)
  // ============================================================================

  test("gte(Int): elements >= Int value") {
    val m = MatD.row(1.0, 2.0, 3.0)
    val mask = m.gte(2)
    assertEquals(mask(0, 0), false)
    assertEquals(mask(0, 1), true)
    assertEquals(mask(0, 2), true)
  }

  test("lte(Int): elements <= Int value") {
    val m = MatD.row(1.0, 2.0, 3.0)
    val mask = m.lte(2)
    assertEquals(mask(0, 0), true)
    assertEquals(mask(0, 1), true)
    assertEquals(mask(0, 2), false)
  }

  test(":!=(Int): elements not equal to Int") {
    val m = MatD.row(1.0, 2.0, 3.0)
    val mask = m.:!=(2)
    assertEquals(mask(0, 0), true)
    assertEquals(mask(0, 1), false)
    assertEquals(mask(0, 2), true)
  }

  test(":==(Int) on MatF: works via Fractional.fromInt") {
    val m = MatF.row(1.0f, 2.0f, 3.0f)
    val mask = m.:==(2)
    assertEquals(mask(0, 0), false)
    assertEquals(mask(0, 1), true)
    assertEquals(mask(0, 2), false)
  }

  test("gt(Int) on MatF: works via Fractional.fromInt") {
    val m = MatF.row(1.0f, 2.0f, 3.0f)
    val mask = m.gt(2)
    assertEquals(mask(0, 0), false)
    assertEquals(mask(0, 1), false)
    assertEquals(mask(0, 2), true)
  }

  // ============================================================================
  // Boolean mask indexing — edge cases
  // ============================================================================

  test("apply(mask): all-false mask returns empty 1x0 matrix") {
    val m = MatD.row(1.0, 2.0, 3.0)
    val mask = m.lt(0.0)   // all false
    val result = m(mask)
    assertEquals(result.rows, 1)
    assertEquals(result.cols, 0)
  }

  test("apply(mask): all-true mask returns all elements") {
    val m = MatD.row(1.0, 2.0, 3.0)
    val mask = m.gt(0.0)   // all true
    val result = m(mask)
    assertEquals(result.rows, 1)
    assertEquals(result.cols, 3)
  }

  test("apply(mask): shape mismatch throws") {
    val m = MatD.zeros(2, 3)
    val bad = MatD.zeros(2, 2).gt(0.0)
    intercept[IllegalArgumentException] { m(bad) }
  }

  // ============================================================================
  // broadcastTo — success and error paths
  // ============================================================================

  test("broadcastTo: row vector → matrix (stride trick)") {
    val v = MatD.row(1.0, 2.0, 3.0)   // 1x3
    val bcast = v.broadcastTo(4, 3)
    assertEquals(bcast.rows, 4)
    assertEquals(bcast.cols, 3)
    // every row should be [1,2,3]
    for i <- 0 until 4 do
      assertEqualsDouble(bcast(i, 0), 1.0, 1e-12)
      assertEqualsDouble(bcast(i, 2), 3.0, 1e-12)
  }

  test("broadcastTo: col vector → matrix (stride trick)") {
    val v = MatD.col(1.0, 2.0, 3.0)   // 3x1
    val bcast = v.broadcastTo(3, 4)
    assertEquals(bcast.rows, 3)
    assertEquals(bcast.cols, 4)
    for j <- 0 until 4 do
      assertEqualsDouble(bcast(1, j), 2.0, 1e-12)
  }

  test("broadcastTo: same shape returns same matrix") {
    val m = MatD.ones(2, 3)
    val bcast = m.broadcastTo(2, 3)
    assert(bcast.allclose(m))
  }

  test("broadcastTo: incompatible shape throws IllegalArgumentException") {
    val m = MatD.zeros(2, 3)
    intercept[IllegalArgumentException] { m.broadcastTo(4, 5) }
  }

  // ============================================================================
  // vstack / hstack / concatenate — error paths and single matrix
  // ============================================================================

  test("vstack: single matrix passes through") {
    val m = MatD.ones(2, 3)
    val r = MatD.vstack(m)
    assert(r.allclose(m))
  }

  test("hstack: single matrix passes through") {
    val m = MatD.ones(2, 3)
    val r = MatD.hstack(m)
    assert(r.allclose(m))
  }

  test("vstack: empty varargs throws") {
    intercept[IllegalArgumentException] { Mat.vstack[Double]() }
  }

  test("hstack: empty varargs throws") {
    intercept[IllegalArgumentException] { Mat.hstack[Double]() }
  }

  test("vstack: mismatched col counts throws") {
    val a = MatD.zeros(2, 3)
    val b = MatD.zeros(2, 4)
    intercept[IllegalArgumentException] { MatD.vstack(a, b) }
  }

  test("hstack: mismatched row counts throws") {
    val a = MatD.zeros(2, 3)
    val b = MatD.zeros(3, 3)
    intercept[IllegalArgumentException] { MatD.hstack(a, b) }
  }

  test("concatenate axis=0 is vstack") {
    val a = MatD.ones(1, 3)
    val b = MatD.zeros(1, 3)
    val r = Mat.concatenate(Seq(a, b), axis = 0)
    assertEquals(r.rows, 2)
    assertEquals(r.cols, 3)
    assertEqualsDouble(r(0, 0), 1.0, 1e-12)
    assertEqualsDouble(r(1, 0), 0.0, 1e-12)
  }

  test("concatenate axis=1 is hstack") {
    val a = MatD.ones(2, 1)
    val b = MatD.zeros(2, 1)
    val r = Mat.concatenate(Seq(a, b), axis = 1)
    assertEquals(r.rows, 2)
    assertEquals(r.cols, 2)
    assertEqualsDouble(r(0, 0), 1.0, 1e-12)
    assertEqualsDouble(r(0, 1), 0.0, 1e-12)
  }

  // ============================================================================
  // norm — Float dispatch, inf/1 with negatives, unsupported string
  // ============================================================================

  test("norm('fro') on MatF: Float dispatch branch") {
    val m = MatF.row(3.0f, 4.0f)
    val n = m.norm("fro")
    assertEquals(n.toDouble, 5.0, 0.001)
  }

  test("norm('inf') with negative elements uses absolute value") {
    val m = MatD((-3.0, 4.0), (1.0, -2.0))
    // Row sums of abs: row0 = 7, row1 = 3 → max = 7
    assertEqualsDouble(m.norm("inf"), 7.0, 1e-10)
  }

  test("norm('1') with negative elements uses absolute value") {
    val m = MatD((-3.0, 1.0), (4.0, -2.0))
    // Col sums of abs: col0 = 7, col1 = 3 → max = 7
    assertEqualsDouble(m.norm("1"), 7.0, 1e-10)
  }

  test("norm: unsupported string throws IllegalArgumentException") {
    val m = MatD.ones(2, 2)
    intercept[IllegalArgumentException] { m.norm("L2") }
  }

  // ============================================================================
  // corrcoef — Float dispatch branch
  // ============================================================================

  test("corrcoef on MatF: diagonal is all ones (Float branch)") {
    val m = MatF((1.0f, 2.0f, 3.0f), (2.0f, 4.0f, 6.0f))
    val r = m.corrcoef
    assertEquals(r.rows, 2)
    assertEquals(r.cols, 2)
    // Diagonal should be 1.0 for each variable with itself
    assertEquals(r(0, 0).toDouble, 1.0, 0.001)
    assertEquals(r(1, 1).toDouble, 1.0, 0.001)
  }

  // ============================================================================
  // isnan / isinf / isfinite — Float type
  // ============================================================================

  test("isnan on MatF: detects Float.NaN") {
    val m = MatF.row(Float.NaN, 1.0f, Float.NaN)
    val r = m.isnan
    assertEquals(r(0, 0), true)
    assertEquals(r(0, 1), false)
    assertEquals(r(0, 2), true)
  }

  test("isinf on MatF: detects Float.PositiveInfinity and NegativeInfinity") {
    val m = MatF.row(Float.PositiveInfinity, 1.0f, Float.NegativeInfinity)
    val r = m.isinf
    assertEquals(r(0, 0), true)
    assertEquals(r(0, 1), false)
    assertEquals(r(0, 2), true)
  }

  test("isfinite on MatF: true only for finite values") {
    val m = MatF.row(1.0f, Float.NaN, Float.PositiveInfinity)
    val r = m.isfinite
    assertEquals(r(0, 0), true)
    assertEquals(r(0, 1), false)
    assertEquals(r(0, 2), false)
  }

  // ============================================================================
  // nanToNum — Float type
  // ============================================================================

  test("nanToNum on MatF: replaces NaN and Inf with defaults") {
    val m = MatF.row(Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity, 1.0f)
    val r = m.nanToNum()
    assertEquals(r(0, 0).toDouble, 0.0, 1e-5)
    assertEquals(r(0, 1).toDouble, 0.0, 1e-5)
    assertEquals(r(0, 2).toDouble, 0.0, 1e-5)
    assertEquals(r(0, 3).toDouble, 1.0, 1e-5)
  }

  test("nanToNum on MatF: custom replacement values") {
    val m = MatF.row(Float.NaN, Float.PositiveInfinity)
    val r = m.nanToNum(nan = -1.0, posinf = 99.0)
    assertEquals(r(0, 0).toDouble, -1.0, 1e-5)
    assertEquals(r(0, 1).toDouble, 99.0, 1e-5)
  }

  // ============================================================================
  // addToEachRow / addToEachCol — error paths
  // ============================================================================

  test("addToEachRow: length mismatch throws") {
    val m = MatD.zeros(3, 4)
    val v = MatD.row(1.0, 2.0)   // wrong length (2 != 4)
    intercept[IllegalArgumentException] { m.addToEachRow(v) }
  }

  test("addToEachCol: length mismatch throws") {
    val m = MatD.zeros(3, 4)
    val v = MatD.col(1.0, 2.0)   // wrong length (2 != 3)
    intercept[IllegalArgumentException] { m.addToEachCol(v) }
  }

  // ============================================================================
  // diff — error paths
  // ============================================================================

  test("diff(axis) invalid axis throws") {
    val m = MatD.ones(2, 2)
    intercept[IllegalArgumentException] { m.diff(2) }
  }

  test("diff(0) on 1-row matrix throws") {
    val m = MatD.row(1.0, 2.0, 3.0)
    intercept[IllegalArgumentException] { m.diff(0) }
  }

  test("diff(1) on 1-col matrix throws") {
    val m = MatD.col(1.0, 2.0, 3.0)
    intercept[IllegalArgumentException] { m.diff(1) }
  }

  // ============================================================================
  // repeat — invalid axis throws
  // ============================================================================

  test("repeat(n, axis): invalid axis throws") {
    val m = MatD.ones(2, 2)
    intercept[IllegalArgumentException] { m.repeat(2, 2) }
  }

  // ============================================================================
  // cumsum — invalid axis throws
  // ============================================================================

  test("cumsum(axis): invalid axis throws") {
    val m = MatD.ones(2, 2)
    intercept[IllegalArgumentException] { m.cumsum(2) }
  }

  // ============================================================================
  // percentile — single-element array branch (n==1 in percentileOf)
  // ============================================================================

  test("percentile of single-element matrix returns that element") {
    val m = MatD.row(7.0)
    assertEqualsDouble(m.percentile(0), 7.0, 1e-12)
    assertEqualsDouble(m.percentile(50), 7.0, 1e-12)
    assertEqualsDouble(m.percentile(100), 7.0, 1e-12)
  }

  test("percentile(p, axis=0) with single-element columns") {
    val m = MatD.col(3.0, 6.0, 9.0)   // 3×1; each column has 3 values
    val r = m.percentile(0, 0)
    assertEqualsDouble(r(0, 0), 3.0, 1e-12)
  }

  test("percentile Float dispatch: Float branch in percentileOf") {
    val m = MatF.row(2.0f, 4.0f, 6.0f)
    val med = m.median
    assertEquals(med.toDouble, 4.0, 0.001)
  }

  // ============================================================================
  // matrixRank — unsupported type throws
  // ============================================================================

  test("matrixRank: unsupported type (Float) throws UnsupportedOperationException") {
    val m = MatF.ones(2, 2)
    intercept[UnsupportedOperationException] { m.matrixRank() }
  }

  // ============================================================================
  // cov — Float type
  // ============================================================================

  test("cov on MatF: returns p×p covariance matrix") {
    // 2 variables, 4 observations
    val m = MatF((1.0f, 2.0f, 3.0f, 4.0f), (2.0f, 4.0f, 6.0f, 8.0f))
    val c = m.cov
    assertEquals(c.rows, 2)
    assertEquals(c.cols, 2)
    // Variance of first row [1,2,3,4] should be ≈ 1.667 (Float precision)
    assertEqualsDouble(c(0, 0).toDouble, 5.0 / 3.0, 0.001)
  }


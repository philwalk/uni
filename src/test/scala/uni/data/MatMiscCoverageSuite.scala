package uni.data

import munit.FunSuite
import Mat.*
import uni.data.Big.*

/** Covers: vsplit/hsplit indexed variants, split axis dispatch, histogram branches,
 *  flatten slow path, unary_- on Float/Big, MatB/MatF arithmetic,
 *  sort/argsort invalid axis, and broadcasting + on Double. */
class MatMiscCoverageSuite extends FunSuite:

  // ============================================================================
  // vsplit(indices) — indexed split (not the evenly-divided variant)
  // ============================================================================

  test("vsplit(Array[Int]): split at row index produces correct parts") {
    val m = MatD((1.0, 2.0), (3.0, 4.0), (5.0, 6.0), (7.0, 8.0))  // 4×2
    val parts = m.vsplit(Array(2))   // split at row 2 → [0-1] + [2-3]
    assertEquals(parts.length, 2)
    assertEquals(parts(0).rows, 2)
    assertEquals(parts(1).rows, 2)
    assertEqualsDouble(parts(0)(0, 0), 1.0, 1e-12)
    assertEqualsDouble(parts(1)(0, 0), 5.0, 1e-12)
  }

  test("vsplit(Array[Int]): unsorted indices throws") {
    val m = MatD.ones(4, 2)
    intercept[IllegalArgumentException] { m.vsplit(Array(3, 1)) }
  }

  test("vsplit(Array[Int]): out-of-range index throws") {
    val m = MatD.ones(4, 2)
    intercept[IllegalArgumentException] { m.vsplit(Array(0)) }   // 0 is not in (0, rows)
  }

  test("vsplit(n): unequal split throws") {
    val m = MatD.ones(5, 2)
    intercept[IllegalArgumentException] { m.vsplit(3) }  // 5 % 3 != 0
  }

  // ============================================================================
  // hsplit(indices) — indexed column split
  // ============================================================================

  test("hsplit(Array[Int]): split at col index produces correct parts") {
    val m = MatD((1.0, 2.0, 3.0, 4.0), (5.0, 6.0, 7.0, 8.0))  // 2×4
    val parts = m.hsplit(Array(2))   // split at col 2 → [0-1] + [2-3]
    assertEquals(parts.length, 2)
    assertEquals(parts(0).cols, 2)
    assertEquals(parts(1).cols, 2)
    assertEqualsDouble(parts(0)(0, 0), 1.0, 1e-12)
    assertEqualsDouble(parts(1)(0, 0), 3.0, 1e-12)
  }

  test("hsplit(Array[Int]): unsorted indices throws") {
    val m = MatD.ones(2, 4)
    intercept[IllegalArgumentException] { m.hsplit(Array(3, 1)) }
  }

  test("hsplit(n): unequal split throws") {
    val m = MatD.ones(2, 5)
    intercept[IllegalArgumentException] { m.hsplit(3) }  // 5 % 3 != 0
  }

  // ============================================================================
  // split(indices/n, axis) — axis dispatch
  // ============================================================================

  test("split(indices, axis=0) delegates to vsplit(indices)") {
    val m = MatD((1.0, 2.0), (3.0, 4.0), (5.0, 6.0), (7.0, 8.0))
    val parts = m.split(Array(2), axis = 0)
    assertEquals(parts.length, 2)
    assertEquals(parts(0).rows, 2)
  }

  test("split(indices, axis=1) delegates to hsplit(indices)") {
    val m = MatD((1.0, 2.0, 3.0, 4.0), (5.0, 6.0, 7.0, 8.0))
    val parts = m.split(Array(2), axis = 1)
    assertEquals(parts.length, 2)
    assertEquals(parts(0).cols, 2)
  }

  test("split(n, axis=1) delegates to hsplit(n)") {
    val m = MatD.ones(2, 4)
    val parts = m.split(2, axis = 1)
    assertEquals(parts.length, 2)
    assertEquals(parts(0).cols, 2)
  }

  // ============================================================================
  // histogram — empty matrix and all-same-value branches
  // ============================================================================

  test("histogram: empty matrix returns all-zero counts") {
    val m = MatD.empty
    val (counts, edges) = m.histogram(bins = 4)
    assertEquals(counts.length, 4)
    assert(counts.forall(_ == 0))
    assertEquals(edges.length, 5)
  }

  test("histogram: all-same-value uses equal-bin branch (all in bin 0)") {
    val m = MatD.row(5.0, 5.0, 5.0)
    val (counts, edges) = m.histogram(bins = 3)
    assertEquals(counts(0), 3)
    assertEquals(counts(1), 0)
    assertEquals(counts(2), 0)
  }

  test("histogram: value equal to maxVal goes to last bin") {
    val m = MatD.row(1.0, 2.0, 3.0)   // 3 goes to last bin as value==maxVal
    val (counts, _) = m.histogram(bins = 2)
    // bins: [1.0, 2.0) and [2.0, 3.0]; 3.0 == maxVal → last bin
    assertEquals(counts.sum, 3)
  }

  test("histogram: values outside explicit range not counted") {
    val m = MatD.row(0.0, 5.0, 10.0, 100.0)
    val (counts, _) = m.histogram(bins = 5, range = Some((0.0, 10.0)))
    assertEquals(counts.sum, 3)   // 100.0 is outside [0, 10] → not counted
  }

  // ============================================================================
  // flatten — slow path (transposed matrix)
  // ============================================================================

  test("flatten: transposed matrix takes the slow iteration path") {
    val m = MatD((1.0, 2.0), (3.0, 4.0))  // 2×2
    val t = m.T                              // transposed: isContiguous = false
    assert(!t.isContiguous)
    val flat = t.flatten
    // Transposed 2×2: rows are [1,3] and [2,4], flattened = [1,3,2,4]
    assertEqualsDouble(flat(0), 1.0, 1e-12)
    assertEqualsDouble(flat(1), 3.0, 1e-12)
    assertEqualsDouble(flat(2), 2.0, 1e-12)
    assertEqualsDouble(flat(3), 4.0, 1e-12)
  }

  // ============================================================================
  // unary_- — Float and Big types
  // ============================================================================

  test("unary_- on MatF: negates all elements") {
    val m = MatF.row(1.0f, -2.0f, 3.0f)
    val r = -m
    assertEquals(r(0, 0).toDouble, -1.0, 0.001)
    assertEquals(r(0, 1).toDouble,  2.0, 0.001)
    assertEquals(r(0, 2).toDouble, -3.0, 0.001)
  }

  test("unary_- on MatB: negates all elements") {
    val m = MatB.row(5.0, -3.0)
    val r = -m
    assertEqualsDouble(r(0, 0).toDouble, -5.0, 1e-10)
    assertEqualsDouble(r(0, 1).toDouble,  3.0, 1e-10)
  }

  // ============================================================================
  // MatF arithmetic — -, *, / (+ already tested in MatCoverageSuite)
  // ============================================================================

  test("MatF - MatF: element-wise subtraction") {
    val a = MatF.row(5.0f, 6.0f)
    val b = MatF.row(1.0f, 2.0f)
    val r = a - b
    assertEquals(r(0, 0).toDouble, 4.0, 0.001)
    assertEquals(r(0, 1).toDouble, 4.0, 0.001)
  }

  test("MatF * MatF: Hadamard product") {
    val a = MatF.row(2.0f, 3.0f)
    val b = MatF.row(4.0f, 5.0f)
    val r = a * b
    assertEquals(r(0, 0).toDouble, 8.0, 0.001)
    assertEquals(r(0, 1).toDouble, 15.0, 0.001)
  }

  test("MatF / MatF: element-wise division") {
    val a = MatF.row(6.0f, 9.0f)
    val b = MatF.row(2.0f, 3.0f)
    val r = a / b
    assertEquals(r(0, 0).toDouble, 3.0, 0.001)
    assertEquals(r(0, 1).toDouble, 3.0, 0.001)
  }

  // ============================================================================
  // MatB arithmetic — +, -, *, /
  // ============================================================================

  test("MatB + MatB: element-wise addition") {
    val a = MatB.row(1.0, 2.0)
    val b = MatB.row(3.0, 4.0)
    val r = a + b
    assertEqualsDouble(r(0, 0).toDouble, 4.0, 1e-10)
    assertEqualsDouble(r(0, 1).toDouble, 6.0, 1e-10)
  }

  test("MatB - MatB: element-wise subtraction") {
    val a = MatB.row(5.0, 8.0)
    val b = MatB.row(2.0, 3.0)
    val r = a - b
    assertEqualsDouble(r(0, 0).toDouble, 3.0, 1e-10)
    assertEqualsDouble(r(0, 1).toDouble, 5.0, 1e-10)
  }

  test("MatB * MatB: Hadamard product") {
    val a = MatB.row(2.0, 3.0)
    val b = MatB.row(4.0, 5.0)
    val r = a * b
    assertEqualsDouble(r(0, 0).toDouble, 8.0, 1e-10)
    assertEqualsDouble(r(0, 1).toDouble, 15.0, 1e-10)
  }

  test("MatB / MatB: element-wise division") {
    val a = MatB.row(6.0, 9.0)
    val b = MatB.row(2.0, 3.0)
    val r = a / b
    assertEqualsDouble(r(0, 0).toDouble, 3.0, 1e-10)
    assertEqualsDouble(r(0, 1).toDouble, 3.0, 1e-10)
  }

  // ============================================================================
  // + on Double with broadcasting (goes through binOp, not the fast path)
  // ============================================================================

  test("MatD + MatD with broadcasting (row+matrix): slow path via binOp") {
    val row = MatD.row(1.0, 2.0, 3.0)   // 1×3
    val mat = MatD.ones(3, 3)            // 3×3
    val r = mat + row                    // broadcasts row to 3×3
    assertEquals(r.rows, 3)
    assertEquals(r.cols, 3)
    assertEqualsDouble(r(0, 0), 2.0, 1e-12)
    assertEqualsDouble(r(1, 1), 3.0, 1e-12)
    assertEqualsDouble(r(2, 2), 4.0, 1e-12)
  }

  // ============================================================================
  // sort / argsort — invalid axis throws
  // ============================================================================

  test("sort: invalid axis throws") {
    val m = MatD.ones(2, 2)
    intercept[IllegalArgumentException] { m.sort(2) }
  }

  test("argsort: invalid axis throws") {
    val m = MatD.ones(2, 2)
    intercept[IllegalArgumentException] { m.argsort(2) }
  }


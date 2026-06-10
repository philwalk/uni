package uni.data

import uni.data.Big.BigNaN

/** Regression tests for ops that previously read the raw backing array (m.tdata)
 *  and therefore returned wrong results for zero-copy views (offset > 0) and
 *  transposed matrices. A full-width row slice of a contiguous matrix survives
 *  the weird-layout guard as a true offset view, which is what these tests use.
 */
class ViewOpsSuite extends munit.FunSuite {

  // 6×5 matrix with values 0..29; rows 2..4 as a zero-copy offset view, plus its deep copy
  private def offsetViewAndCopy: (Mat[Double], Mat[Double]) =
    val m = Mat.tabulate[Double](6, 5)((i, j) => (i * 5 + j).toDouble)
    val v = m.slice(2 until 5, 0 until 5)
    assert(v.offset > 0, "precondition: slice must remain a zero-copy view with offset > 0")
    (v, v.matCopy)

  test("precondition: full-width row slice is an offset view with correct content") {
    val (v, _) = offsetViewAndCopy
    assertEquals(v.shape, (3, 5))
    assertEquals(v(0, 0), 10.0)
    assertEquals(v(2, 4), 24.0)
  }

  test("gt/lt/gte/lte/:==/:!= respect view offset") {
    val (v, c) = offsetViewAndCopy
    assertEquals(v.gt(17.0).toArray.toSeq,  c.gt(17.0).toArray.toSeq)
    assertEquals(v.lt(17.0).toArray.toSeq,  c.lt(17.0).toArray.toSeq)
    assertEquals(v.gte(17.0).toArray.toSeq, c.gte(17.0).toArray.toSeq)
    assertEquals(v.lte(17.0).toArray.toSeq, c.lte(17.0).toArray.toSeq)
    assertEquals(v.:==(17.0).toArray.toSeq, c.:==(17.0).toArray.toSeq)
    assertEquals(v.:!=(17.0).toArray.toSeq, c.:!=(17.0).toArray.toSeq)
    // explicit values: view holds 10..24, so 7 elements exceed 17
    assertEquals(v.gt(17.0).sum, 7)
    assertEquals(v.gt(17.0).rows, 3)
    assertEquals(v.gt(17.0).cols, 5)
  }

  test("isnan/isinf/isfinite respect view offset") {
    val m = Mat.tabulate[Double](6, 5)((i, j) => (i * 5 + j).toDouble)
    m(0, 0) = Double.NaN               // outside the view — must NOT be seen
    m(3, 2) = Double.NaN               // inside the view  (view row 1, col 2)
    m(4, 4) = Double.PositiveInfinity  // inside the view  (view row 2, col 4)
    val v = m.slice(2 until 5, 0 until 5)
    assert(v.offset > 0)

    val nn = v.isnan
    assertEquals(nn.shape, (3, 5))
    assertEquals(nn.sum, 1)
    assert(nn(1, 2))
    assert(!nn(0, 0))

    val inf = v.isinf
    assertEquals(inf.sum, 1)
    assert(inf(2, 4))

    assertEquals(v.isfinite.sum, 13)
  }

  test("std and variance respect view offset") {
    val (v, c) = offsetViewAndCopy
    assertEqualsDouble(v.std, c.std, 1e-12)
    assertEqualsDouble(v.variance, c.variance, 1e-12)
    // view holds 10..24: mean 17, population variance = 2*(1+4+9+...+49)/15
    assertEqualsDouble(v.variance, 2.0 * 140.0 / 15.0, 1e-12)
  }

  test("filterRows respects view offset") {
    val (v, c) = offsetViewAndCopy
    val f1 = v.filterRows(r => r(0, 0) > 14.0)
    val f2 = c.filterRows(r => r(0, 0) > 14.0)
    assertEquals(f1.shape, f2.shape)
    assertEquals(f1.toArray.toSeq, f2.toArray.toSeq)
  }

  test("filterRows is correct for transposed matrices") {
    val m = Mat.tabulate[Double](3, 4)((i, j) => (i * 4 + j).toDouble)
    val t = m.T  // 4×3 transposed view; row k of t = column k of m = (k, 4+k, 8+k)
    val kept = t.filterRows(r => r(0, 0) % 2.0 == 0.0)
    assertEquals(kept.shape, (2, 3))
    assertEquals(kept.toArray.toSeq, Seq(0.0, 4.0, 8.0, 2.0, 6.0, 10.0))
  }
}

/** Tests for the v0.14.0 semantic fixes in Mat. */
class MatSemanticsSuite extends munit.FunSuite {

  test("~^ is element-wise for Double exponent: m ~^ 0.0 is all-ones, not identity") {
    val m = Mat[Double]((2.0, 3.0), (4.0, 5.0))
    assert((m ~^ 0.0).allclose(Mat.ones[Double](2, 2)))
    assert((m ~^ 1.0).allclose(m))
  }

  test("power with negative Int exponent throws before computing") {
    val m = Mat[Double]((2.0, 3.0), (4.0, 5.0))
    intercept[UnsupportedOperationException](m.power(-1))
  }

  test("linspace with num=1 preserves fractional start value") {
    val l = Mat.linspace[Double](2.5, 9.0, 1)
    assertEquals(l.item, 2.5)
  }

  test("containsNaN detects Double NaN without string comparison") {
    val m = MatD(1.0, 2.0, 3.0)  // column vector since v0.14.0
    assert(!m.containsNaN)
    m(1, 0) = Double.NaN
    assert(m.containsNaN)
  }

  test("containsNaN detects BigNaN sentinel") {
    val b = MatB.ones(2, 2)
    assert(!b.containsNaN)
    b(0, 0) = BigNaN
    assert(b.containsNaN)
  }

  test("slicing, head, tail, and vsplit work without a Fractional instance (Mat[Int])") {
    val mi = Mat.create(Array(1, 2, 3, 4, 5, 6), 3, 2)
    assertEquals(mi.head(2).shape, (2, 2))
    assertEquals(mi.tail(1).shape, (1, 2))
    assertEquals(mi(0, ::).toArray.toSeq, Seq(1, 2))
    val parts = mi.vsplit(Array(1))
    assertEquals(parts.length, 2)
    assertEquals(parts(0).shape, (1, 2))
    assertEquals(parts(1).shape, (2, 2))
  }
}

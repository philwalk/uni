package uni.data

import Mat.*

import munit.FunSuite
class DefaultDoubleTests extends FunSuite {
  test("matrix factory taking a single scalar value of type Double") {
    val m = Mat(1.0)
    assertEquals(m(0, 0), 1.0)
  }
  test("matrix factory taking a single scalar value of type Big") {
    val m = Mat(Big.one)
    assertEquals(m(0, 0).toDouble, 1.0)
  }

  // Line 421: unsupported type in tuple factory
  test("apply rejects unsupported type in tuple") {
    intercept[IllegalArgumentException] {
      Mat.apply[Double](("hello", 2))
    }
  }

  // Line 577/581/586: linspace else branch + fallback
  test("linspace negative range") {
    val m = Mat.linspace[Double](10, 0, 5)
    assertEquals(m.shape, (5, 1))
    assertEqualsDouble(m(0, 0), 10.0, 1e-10, "value mismatch")
    assertEqualsDouble(m(4, 0),  0.0, 1e-10, "value mismatch")
  }

  // Lines 592-594: flat array constructor
  test("apply from flat array with dimensions") {
    val data = Array(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
    val m = Mat(2, 3, data)
    assertEquals(m.shape, (2, 3))
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(1, 2), 6.0)
  }

  // Lines 606/615/631: apply(unit), apply(value: T), apply(unit) generic
  test("apply(unit) with Float type") {
    val m = Mat[Float](())
    assertEquals(m.shape, (0, 0))
  }

  test("apply generic scalar creates 1x1") {
    val m = Mat[Float](3.14f)
    assertEquals(m.shape, (1, 1))
    assertEqualsDouble(m(0, 0).toDouble, 3.14, 1e-5)
  }

  // Line 647: v: T @unchecked branch in tuple factory (Float/BigDecimal literals)
  test("apply with Float literals") {
    val m = Mat[Float]((1.5f, 2.5f), (3.5f, 4.5f))
    assertEquals(m.shape, (2, 2))
    assertEqualsDouble(m(0, 0).toDouble, 1.5, 1e-5)
  }

  test("apply with BigDecimal literals") {
    val m = Mat[BigDecimal]((BigDecimal("1.5"), BigDecimal("2.5")))
    assertEquals(m.shape, (1, 2))
    assertEquals(m(0, 0), BigDecimal("1.5"))
  }

  // Lines 658-667: Double-defaulting overloads
  test("apply(Double) creates 1x1 Mat[Double]") {
    val m = Mat(3.14)
    assertEquals(m.shape, (1, 1))
    assertEquals(m(0, 0), 3.14)
  }

  test("ones default Double") {
    val m = Mat.ones[Double](2, 3)
    assertEquals(m.shape, (2, 3))
    assertEquals(m(0, 0), 1.0)
  }

  test("eye default Double") {
    val m = Mat.eye[Double](3)
    assertEquals(m.shape, (3, 3))
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(0, 1), 0.0)
  }

  test("full default Double") {
    val m = Mat.full[Double](2, 3, 5.0)
    assertEquals(m.shape, (2, 3))
    assertEquals(m(1, 2), 5.0)
  }

  test("arange(stop) default Double") {
    val m = Mat.arange[Double](5)
    assertEquals(m.shape, (5, 1))
    assertEquals(m(0, 0), 0.0)
    assertEquals(m(4, 0), 4.0)
  }

  test("arange(start, stop) default Double") {
    val m = Mat.arange[Double](3, 7)
    assertEquals(m.shape, (4, 1))
    assertEquals(m(0, 0), 3.0)
  }

  test("arange(start, stop, step) default Double") {
    val m = Mat.arange[Double](0, 10, 2)
    assertEquals(m.shape, (5, 1))
    assertEquals(m(2, 0), 4.0)
  }

  test("row(Int*) default Double") {
    val m = Mat.row[Double](1, 2, 3)
    assertEquals(m.shape, (1, 3))
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(0, 2), 3.0)
  }

  test("col(Int*) default Double") {
    val m = Mat.col[Double](1, 2, 3)
    assertEquals(m.shape, (3, 1))
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(2, 0), 3.0)
  }

  // Lines 674-675/685-687/695-696: single, fromSeq, of
  test("single creates 1x1 Float") {
    val m = Mat.single[Double](3.14f)
    assertEquals(m.shape, (1, 1))
  }

  test("fromSeq with non-empty sequence") {
    val m = Mat.fromSeq(Seq(1.0, 2.0, 3.0))
    assertEquals(m.shape, (3, 1))
    assertEquals(m(2, 0), 3.0)
  }

  test("of creates row vector") {
    val m = Mat.of(1.0, 2.0, 3.0)
    assertEquals(m.shape, (1, 3))
    assertEquals(m(0, 2), 3.0)
  }

  // Lines 705-711: tabulate
  test("tabulate with Double") {
    val m = Mat.tabulate(2, 3)((i, j) => (i * 3 + j).toDouble)
    assertEquals(m.shape, (2, 3))
    assertEquals(m(0, 0), 0.0)
    assertEquals(m(1, 2), 5.0)
  }

  // Line 715: empty
  test("empty[Float] creates 0x0 matrix") {
    val m = Mat.empty[Float]
    assertEquals(m.shape, (0, 0))
    assert(m.isEmpty)
  }

  // Lines 722/730: row[T]/col[T] generic versions
  test("row[Float] creates row vector") {
    val m = Mat.row[Float](1.0f, 2.0f, 3.0f)
    assertEquals(m.shape, (1, 3))
    assertEqualsDouble(m(0, 1).toDouble, 2.0, 1e-5)
  }

  test("col[Float] creates column vector") {
    val m = Mat.col[Float](1.0f, 2.0f, 3.0f)
    assertEquals(m.shape, (3, 1))
    assertEqualsDouble(m(1, 0).toDouble, 2.0, 1e-5)
  }
}

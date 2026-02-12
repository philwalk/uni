package uni.data

import Mat.*

class MatCoverageTests extends munit.FunSuite {
    
  // ============================================================================
  // Untested Factory Methods
  // ============================================================================

  test("zeros with shape tuple") {
    val m = Mat.zeros[Double]((3, 4))
    assertEquals(m.shape, (3, 4))
    assertEquals(m(0, 0), 0.0)
  }

  test("ones with shape tuple") {
    val m = Mat.ones[Double]((2, 3))
    assertEquals(m.shape, (2, 3))
    assertEquals(m(0, 0), 1.0)
  }

  test("full with shape tuple") {
    val m = Mat.full[Double]((2, 3), 5.0)
    assertEquals(m.shape, (2, 3))
    assertEquals(m(1, 2), 5.0)
  }

  test("apply from flat array with dimensions") {
    val data = Array(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
    val m = Mat(2, 3, data)
    assertEquals(m.shape, (2, 3))
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(0, 2), 3.0)
    assertEquals(m(1, 0), 4.0)
    assertEquals(m(1, 2), 6.0)
  }

  test("apply from flat array rejects mismatched dimensions") {
    val data = Array(1.0, 2.0, 3.0)
    intercept[IllegalArgumentException] {
      Mat(2, 3, data)  // need 6 elements
    }
  }

  // ============================================================================
  // linspace tests
  // ============================================================================

  test("linspace creates evenly spaced column vector") {
    val m = Mat.linspace[Double](0, 1, 5)
    assertEquals(m.shape, (5, 1))
    assertEquals(m(0, 0), 0.0)
    assertEquals(m(4, 0), 1.0)
    // check middle values
    assertEqualsDouble(m(1, 0), 0.25, 1e-10)
    assertEqualsDouble(m(2, 0), 0.5,  1e-10)
    assertEqualsDouble(m(3, 0), 0.75, 1e-10)
  }

  test("linspace creates evenly spaced column vector for Big type") {
    val m = Mat.linspace[Big](0, 1, 5)
    assertEquals(m.shape, (5, 1))
    assertEquals(m(0, 0), Big.zero)
    assertEquals(m(4, 0), Big.one)
    // check middle values
    assertEqualsDouble(m(1, 0).toDouble, 0.25, 1e-10)
    assertEqualsDouble(m(2, 0).toDouble, 0.5,  1e-10)
    assertEqualsDouble(m(3, 0).toDouble, 0.75, 1e-10)
  }

  test("linspace creates evenly spaced column vector for Float type") {
    val m = Mat.linspace[Float](0, 1, 5)
    assertEquals(m.shape, (5, 1))
    assertEquals(m(0, 0), 0.0f)
    assertEquals(m(4, 0), 1.0f)
    // check middle values
    assertEqualsDouble(m(1, 0).toDouble, 0.25, 1e-10)
    assertEqualsDouble(m(2, 0).toDouble, 0.5,  1e-10)
    assertEqualsDouble(m(3, 0).toDouble, 0.75, 1e-10)
  }

  test("linspace with num=1 returns single value") {
    val m = Mat.linspace[Double](5, 10, 1)
    assertEquals(m.shape, (1, 1))
    assertEquals(m(0, 0), 5.0)
  }

  test("linspace defaults to 50 points") {
    val m = Mat.linspace[Double](0, 1)
    assertEquals(m.shape, (50, 1))
  }

  test("linspace rejects num <= 0") {
    intercept[IllegalArgumentException] {
      Mat.linspace[Double](0, 1, 0)
    }
  }

  // ============================================================================
  // Untested Error Paths
  // ============================================================================

  test("arange rejects stop <= start") {
    intercept[IllegalArgumentException] {
      Mat.arange[Double](5, 3)
    }
  }

  test("arange rejects zero step") {
    intercept[IllegalArgumentException] {
      Mat.arange[Double](0, 10, 0)
    }
  }

  test("column slice rejects out of bounds") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    intercept[IllegalArgumentException] {
      m(::, 5)
    }
  }

  test("row slice rejects out of bounds") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    intercept[IllegalArgumentException] {
      m(5, ::)
    }
  }

  test("subtraction rejects mismatched shapes") {
    val m1 = Mat.ones[Double](2, 2)
    val m2 = Mat.ones[Double](2, 3)
    intercept[IllegalArgumentException] {
      m1 - m2
    }
  }

  test("element-wise multiply rejects mismatched shapes") {
    val m1 = Mat.ones[Double](2, 2)
    val m2 = Mat.ones[Double](2, 3)
    intercept[IllegalArgumentException] {
      m1 *:* m2
    }
  }

  // ============================================================================
  // show branch coverage
  // ============================================================================

  test("show uses scientific notation for large values") {
    val m = Mat[Double]((1e7, 2e8), (3e9, 4e10))
    val s = m.show
    assert(s.contains("e+"), s"Expected scientific notation in: $s")
  }

  test("show uses scientific notation for small values") {
    val m = Mat[Double]((1e-5, 2e-6), (3e-7, 4e-8))
    val s = m.show
    assert(s.contains("e-"), s"Expected scientific notation in: $s")
  }

  test("show uses integer format for whole numbers") {
    val m = Mat[Double]((1, 2), (3, 4))
    val s = m.show
    assert(s.contains("[1, 2]"), s"Expected integer format in: $s")
    assert(!s.contains("1.0"),   s"Should not contain decimal in: $s")
  }

  test("show precision reflects spread") {
    // small spread - needs more decimal places
    val m1 = Mat[Double]((0.0001, 0.0002), (0.0003, 0.0004))
    assert(m1.show.contains("0.000"), "small spread needs fine precision")

    // large spread - fewer decimal places needed  
    val m2 = Mat[Double]((1, 200), (3000, 40000))
    val s2 = m2.show
    assert(!s2.contains(".000000"), "large spread should not over-precision")
  }

  // ============================================================================
  // Big type arithmetic coverage
  // ============================================================================

  test("Mat[Big] basic arithmetic") {
    val m1 = Mat[Big]((1, 2), (3, 4))
    val m2 = Mat[Big]((5, 6), (7, 8))
    
    val sum  = m1 + m2
    assertEquals(sum(0, 0),  Big(6))
    assertEquals(sum(1, 1),  Big(12))
    
    val diff = m2 - m1
    assertEquals(diff(0, 0), Big(4))
    
    val prod = m1 * m2  // matrix multiply
    assertEquals(prod(0, 0), Big(19))
    assertEquals(prod(1, 1), Big(50))
  }

  test("Mat[Big] scalar operations") {
    val m = Mat[Big]((2, 4), (6, 8))
    assertEquals((m / Big(2))(0, 0), Big(1))
    assertEquals((m * Big(3))(1, 1), Big(24))
  }

  test("Mat[Big] transpose") {
    val m = Mat[Big]((1, 2, 3), (4, 5, 6))
    val t = m.T
    assertEquals(t.shape, (3, 2))
    assertEquals(t(0, 1), Big(4))
  }
}

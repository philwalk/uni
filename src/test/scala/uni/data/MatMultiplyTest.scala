package uni.data

import Mat.*

class MatMultiplyTest extends munit.FunSuite {
  
  // ============================================================================
  // Matrix Multiply Tests
  // ============================================================================
  test("matrix multiplication") {
    val m1 = Mat[Double]((1, 2), (3, 4))
    val m2 = Mat[Double]((5, 6), (7, 8))
    val prod = m1 @@ m2
    assertEquals(prod.shape, (2, 2))
    assertEquals(prod(0, 0), 19.0)  // 1*5 + 2*7
    assertEquals(prod(0, 1), 22.0)  // 1*6 + 2*8
    assertEquals(prod(1, 0), 43.0)  // 3*5 + 4*7
    assertEquals(prod(1, 1), 50.0)  // 3*6 + 4*8
  }
  
  test("matrix multiplication with non-square") {
    val m1 = Mat[Double]((1, 2, 3), (4, 5, 6))  // 2x3
    val m2 = Mat[Double]((7, 8), (9, 10), (11, 12))  // 3x2
    val prod = m1 @@ m2
    assertEquals(prod.shape, (2, 2))
    assertEquals(prod(0, 0), 58.0)   // 1*7 + 2*9 + 3*11
    assertEquals(prod(0, 1), 64.0)   // 1*8 + 2*10 + 3*12
  }
  
  test("dot is alias for matrix multiplication") {
    val m1 = Mat[Double]((1, 2), (3, 4))
    val m2 = Mat[Double]((5, 6), (7, 8))
    val p1 = m1.dot(m2)
    val p2 = m1 @@ m2
    assertEquals(p1(0, 0), p2(0, 0))
  }
  
  test("Mat[Double] @@ Mat[Double] matrix multiply promotes to Mat[Double]") {
    val m1 = Mat[Double]((1, 2), (3, 4))  // Mat[Double], 2x2
    val m2 = Mat[Double]((0.5, 1.0), (1.5, 2.0))  // Mat[Double], 2x2
    val result = m1 @@ m2
    
    // 1*0.5 + 2*1.5 = 3.5
    assertEquals(result(0, 0), 3.5)
    // 3*0.5 + 4*1.5 = 7.5
    assertEquals(result(1, 0), 7.5)
  }
  
  test("matrix multiplication rejects invalid dimensions") {
    val m1 = Mat.ones[Double](2, 3)
    val m2 = Mat.ones[Double](2, 3)
    intercept[IllegalArgumentException] {
      m1 @@ m2  // 2x3 @@ 2x3 invalid
    }
  }
  
  test("Mat[Double] / Double promotes to Mat[Double]") {
    val m = Mat[Double]((10, 20), (30, 40))  // Infers Mat[Double]
    val result = m / 2.0
    
    // Result should be Mat[Double]
    assertEquals(result(0, 0), 5.0)
    assertEquals(result(1, 1), 20.0)
    assert(result.isInstanceOf[Mat[Double]])
  }
  
  test("Mat[Double] @@ Double promotes to Mat[Double]") {
    val m = Mat[Double]((1, 2), (3, 4))  // Mat[Double]
    val result = m * 2.5
    
    assertEquals(result(0, 0), 2.5)
    assertEquals(result(1, 1), 10.0)
  }
  
  test("element-wise multiplication") {
    val m1 = Mat[Double]((1, 2), (3, 4))
    val m2 = Mat[Double]((2, 3), (4, 5))
    val prod = m1 * m2
    assertEquals(prod(0, 0), 2.0)
    assertEquals(prod(0, 1), 6.0)
    assertEquals(prod(1, 0), 12.0)
    assertEquals(prod(1, 1), 20.0)
  }
  
}

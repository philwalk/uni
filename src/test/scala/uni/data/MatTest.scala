package uni.data

import Mat.*

class MatTest extends munit.FunSuite {
  
  // ============================================================================
  // Factory Methods Tests
  // ============================================================================
  
  test("zeros creates matrix filled with zeros") {
    val m = Mat.zeros[Double](3, 4)
    assertEquals(m.shape, (3, 4))
    assertEquals(m(0, 0), 0.0)
    assertEquals(m(2, 3), 0.0)
  }
  
  test("zeros with shape tuple") {
    val m = Mat.zeros[Double]((2, 3))
    assertEquals(m.shape, (2, 3))
  }
  
  test("ones creates matrix filled with ones") {
    val m = Mat.ones[Double](2, 3)
    assertEquals(m.shape, (2, 3))
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(1, 2), 1.0)
  }
  
  test("eye creates identity matrix") {
    val m = Mat.eye[Double](3)
    assertEquals(m.shape, (3, 3))
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(1, 1), 1.0)
    assertEquals(m(2, 2), 1.0)
    assertEquals(m(0, 1), 0.0)
    assertEquals(m(1, 0), 0.0)
  }
  
  test("full creates matrix with specified value") {
    val m = Mat.full(2, 3, 7.0)
    assertEquals(m.shape, (2, 3))
    assertEquals(m(0, 0), 7.0)
    assertEquals(m(1, 2), 7.0)
  }
  
  test("arange creates sequential column vector") {
    val m = Mat.arange[Double](5)
    assertEquals(m.shape, (5, 1))
    assertEquals(m(0, 0), 0.0)
    assertEquals(m(4, 0), 4.0)
  }
  
  test("arange with start and stop") {
    val m = Mat.arange[Double](3, 7)
    assertEquals(m.shape, (4, 1))
    assertEquals(m(0, 0), 3.0)
    assertEquals(m(3, 0), 6.0)
  }
  
  test("arange with step") {
    val m = Mat.arange[Double](0, 10, 2)
    assertEquals(m.shape, (5, 1))
    assertEquals(m(0, 0), 0.0)
    assertEquals(m(2, 0), 4.0)
    assertEquals(m(4, 0), 8.0)
  }
  
  test("apply creates matrix from tuples") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    assertEquals(m.shape, (2, 3))
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(0, 2), 3.0)
    assertEquals(m(1, 0), 4.0)
    assertEquals(m(1, 2), 6.0)
  }
  
  test("apply with Int creates Double matrix") {
    val m = Mat[Double]((1, 2), (3, 4))
    assertEquals(m.shape, (2, 2))
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(1, 1), 4.0)
  }
  
  test("apply rejects jagged rows") {
    intercept[IllegalArgumentException] {
      Mat[Double]((1, 2, 3), (4, 5))
    }
  }
  
  test("row creates row vector") {
    val m = Mat.row(1.0, 2.0, 3.0)
    assertEquals(m.shape, (1, 3))
    assertEquals(m(0, 1), 2.0)
  }
  
  test("col creates column vector") {
    val m = Mat.col(1.0, 2.0, 3.0)
    assertEquals(m.shape, (3, 1))
    assertEquals(m(1, 0), 2.0)
  }
  
  test("empty creates 0x0 matrix") {
    val m = Mat.empty[Double]
    assertEquals(m.shape, (0, 0))
    assert(m.isEmpty)
  }
  
  test("tabulate creates matrix from function") {
    val m = Mat.tabulate(3, 3)((i, j) => i + j)
    assertEquals(m.shape, (3, 3))
    assertEquals(m(0, 0), 0)
    assertEquals(m(1, 1), 2)
    assertEquals(m(2, 2), 4)
  }
  
  // ============================================================================
  // Automatic Scalar Promotion and Unit Handling
  // ============================================================================
  
  test("Mat(scalar) automatically creates 1x1 matrix") {
    // (42) is just 42 (not a tuple), but we promote it
    val m = Mat(42)
    assertEquals(m.shape, (1, 1))
    assertEquals(m(0, 0), 42.0)
  }
  
  test("Mat(scalar) with Double") {
    val m = Mat(3.14)
    assertEquals(m.shape, (1, 1))
    assertEquals(m(0, 0), 3.14)
  }
  
  test("Mat(()) treats Unit as empty matrix") {
    val m = Mat[Double](())
    assertEquals(m.shape, (0, 0))
    assert(m.isEmpty)
  }
  
  test("Mat(single tuple) creates row vector") {
    // If user passes an actual tuple, treat as row
    val m = Mat[Double]((1, 2, 3))
    assertEquals(m.shape, (1, 3))
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(0, 2), 3.0)
  }
  
  // ============================================================================
  // Workaround Methods (still useful for clarity)
  // ============================================================================
  
  test("single creates 1x1 matrix (workaround for Tuple1)") {
    // Can't use Mat((42)) because (42) is just an Int
    val m = Mat.single(42)
    assertEquals(m.shape, (1, 1))
    assertEquals(m(0, 0), 42)
  }
  
  test("single with Double") {
    val m = Mat.single(3.14)
    assertEquals(m.shape, (1, 1))
    assertEquals(m(0, 0), 3.14)
  }
  
  test("fromSeq creates column vector") {
    val m = Mat.fromSeq(Seq(1, 2, 3, 4))
    assertEquals(m.shape, (4, 1))
    assertEquals(m(0, 0), 1)
    assertEquals(m(3, 0), 4)
  }
  
  test("fromSeq handles empty sequence") {
    val m = Mat.fromSeq(Seq.empty[Double])
    assertEquals(m.shape, (0, 0))
    assert(m.isEmpty)
  }
  
  test("of creates row vector from varargs") {
    val m = Mat.of(1, 2, 3, 4)
    assertEquals(m.shape, (1, 4))
    assertEquals(m(0, 0), 1)
    assertEquals(m(0, 3), 4)
  }
  
  test("of with single value") {
    val m = Mat.of(42)
    assertEquals(m.shape, (1, 1))
    assertEquals(m(0, 0), 42)
  }
  
  // ============================================================================
  // Indexing Tests
  // ============================================================================
  
  test("apply gets element at row, col") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(0, 2), 3.0)
    assertEquals(m(1, 1), 5.0)
  }
  
  test("negative indexing for rows") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    assertEquals(m(-1, 0), 4.0)  // last row, first col
    assertEquals(m(-2, 0), 1.0)  // first row, first col

    val n = Mat[Double]((1, 2, 3), (4, 5, 6))
    assertEquals(n(-1, 0), 4.0)  // last row, first col
    assertEquals(n(-2, 0), 1.0)  // first row, first col
  }
  
  test("negative indexing for cols") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    assertEquals(m(0, -1), 3.0)  // first row, last col
    assertEquals(m(1, -2), 5.0)  // second row, second-to-last col

    val n = Mat[Double]((1, 2, 3), (4, 5, 6))
    assertEquals(n(0, -1), 3.0)  // first row, last col
    assertEquals(n(1, -2), 5.0)  // second row, second-to-last col
  }
  
  test("negative indexing for both") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    assertEquals(m(-1, -1), 6.0)  // last element
  }
  
  test("update sets element") {
    val m = Mat.zeros[Double](2, 2)
    m(0, 0) = 42.0
    assertEquals(m(0, 0), 42.0)
  }
  
  test("slice extracts column") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val col = m(::, 1)
    assertEquals(col.shape, (2, 1))
    assertEquals(col(0, 0), 2.0)
    assertEquals(col(1, 0), 5.0)
  }
  
  test("slice extracts last column with negative index") {
    val m = Mat[Big]((1, 2, 3), (4, 5, 6))
    val col = m(::, -1)
    assertEquals(col.shape, (2, 1))
    assertEquals(col(0, 0), big(3))
    assertEquals(col(1, 0), big(6))
  }
  
  test("slice extracts row") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val row = m(0, ::)
    assertEquals(row.shape, (1, 3))
    assertEquals(row(0, 0), 1.0)
    assertEquals(row(0, 2), 3.0)
  }
  
  test("slice extracts last row with negative index") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val row = m(-1, ::)
    assertEquals(row.shape, (1, 3))
    assertEquals(row(0, 0), 4.0)
  }
  
  test("rectangular slicing") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val sub = m(0 to 1, 1 to 2)
    assertEquals(sub.shape, (2, 2))
    assertEquals(sub(0, 0), 2.0)
    assertEquals(sub(0, 1), 3.0)
    assertEquals(sub(1, 0), 5.0)
    assertEquals(sub(1, 1), 6.0)
  }
  
  // ============================================================================
  // Properties Tests
  // ============================================================================
  
  test("shape returns (rows, cols)") {
    val m = Mat.zeros[Double](3, 4)
    assertEquals(m.shape, (3, 4))
  }
  
  test("size returns total elements") {
    val m = Mat.zeros[Double](3, 4)
    assertEquals(m.size, 12)
  }
  
  test("ndim always returns 2") {
    val m = Mat.zeros[Double](3, 4)
    assertEquals(m.ndim, 2)
  }
  
  test("isEmpty detects empty matrix") {
    assert(Mat.empty[Double].isEmpty)
    assert(!Mat.ones[Double](1, 1).isEmpty)
  }
  
  // ============================================================================
  // Shape Manipulation Tests
  // ============================================================================
  
  test("transpose swaps rows and cols") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val t = m.transpose
    assertEquals(t.shape, (3, 2))
    assertEquals(t(0, 0), 1.0)
    assertEquals(t(0, 1), 4.0)
    assertEquals(t(1, 0), 2.0)
    assertEquals(t(2, 1), 6.0)
  }
  
  test("T is alias for transpose") {
    val m = Mat[Double]((1, 2), (3, 4))
    val t1 = m.T
    val t2 = m.transpose
    assertEquals(t1.shape, t2.shape)
    assertEquals(t1(0, 0), t2(0, 0))
  }
  
  test("reshape changes dimensions") {
    val m = Mat.arange[Double](6)
    val r = m.reshape(2, 3)
    assertEquals(r.shape, (2, 3))
    assertEquals(r(0, 0), 0.0)
    assertEquals(r(1, 2), 5.0)
  }
  
  test("reshape rejects invalid dimensions") {
    val m = Mat.arange[Double](6)
    intercept[IllegalArgumentException] {
      m.reshape(2, 4)  // 8 != 6
    }
  }
  
  test("flatten returns 1D array") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val flat = m.flatten
    assertEquals(flat.length, 6)
    assertEquals(flat(0), 1.0)
    assertEquals(flat(5), 6.0)
  }
  
  test("copy creates deep copy") {
    val m = Mat.ones[Double](2, 2)
    val c = m.copy
    m(0, 0) = 99.0
    assertEquals(m(0, 0), 99.0)
    assertEquals(c(0, 0), 1.0)  // copy unchanged
  }
  
  test("ravel returns row vector") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val r = m.ravel
    assertEquals(r.shape, (1, 6))
  }
  
  // ============================================================================
  // Arithmetic Tests
  // ============================================================================
  
  test("addition of matrices") {
    val m1 = Mat[Double]((1, 2), (3, 4))
    val m2 = Mat[Double]((10, 20), (30, 40))
    val sum = m1 + m2
    assertEquals(sum(0, 0), 11.0)
    assertEquals(sum(1, 1), 44.0)
  }
  
  test("addition rejects mismatched shapes") {
    val m1 = Mat.ones[Double](2, 2)
    val m2 = Mat.ones[Double](2, 3)
    intercept[IllegalArgumentException] {
      m1 + m2
    }
  }
  
  test("scalar addition") {
    val m = Mat[Double]((1, 2), (3, 4))
    val result = m + 10
    assertEquals(result(0, 0), 11.0)
    assertEquals(result(1, 1), 14.0)
  }
  
  test("subtraction of matrices") {
    val m1 = Mat[Double]((10, 20), (30, 40))
    val m2 = Mat[Double]((1, 2), (3, 4))
    val diff = m1 - m2
    assertEquals(diff(0, 0), 9.0)
    assertEquals(diff(1, 1), 36.0)
  }
  
  test("scalar subtraction") {
    val m = Mat[Double]((10, 20), (30, 40))
    val result = m - 5
    assertEquals(result(0, 0), 5.0)  // surprise! type is Double
    assertEquals(result(1, 0), 25.0)
  }
  
  test("scalar multiplication") {
    val m = Mat[Double]((1, 2), (3, 4))
    val result = m * 3
    assertEquals(result(0, 0), 3.0)
    assertEquals(result(1, 1), 12.0)
  }
  
  /*
  test("element-wise multiplication") {
    val m1 = Mat[Double]((1, 2), (3, 4))
    val m2 = Mat[Double]((2, 3), (4, 5))
    val prod = m1 *:* m2
    assertEquals(prod(0, 0), 2.0)
    assertEquals(prod(0, 1), 6.0)
    assertEquals(prod(1, 0), 12.0)
    assertEquals(prod(1, 1), 20.0)
  }
  
  test("matrix multiplication") {
    val m1 = Mat[Double]((1, 2), (3, 4))
    val m2 = Mat[Double]((5, 6), (7, 8))
    val prod = m1 * m2
    assertEquals(prod.shape, (2, 2))
    assertEquals(prod(0, 0), 19.0)  // 1*5 + 2*7
    assertEquals(prod(0, 1), 22.0)  // 1*6 + 2*8
    assertEquals(prod(1, 0), 43.0)  // 3*5 + 4*7
    assertEquals(prod(1, 1), 50.0)  // 3*6 + 4*8
  }
  
  test("matrix multiplication with non-square") {
    val m1 = Mat[Double]((1, 2, 3), (4, 5, 6))  // 2x3
    val m2 = Mat[Double]((7, 8), (9, 10), (11, 12))  // 3x2
    val prod = m1 * m2
    assertEquals(prod.shape, (2, 2))
    assertEquals(prod(0, 0), 58.0)   // 1*7 + 2*9 + 3*11
    assertEquals(prod(0, 1), 64.0)   // 1*8 + 2*10 + 3*12
  }
  
  test("matrix multiplication rejects invalid dimensions") {
    val m1 = Mat.ones[Double](2, 3)
    val m2 = Mat.ones[Double](2, 3)
    intercept[IllegalArgumentException] {
      m1 * m2  // 2x3 * 2x3 invalid
    }
  }
  
  test("dot is alias for matrix multiplication") {
    val m1 = Mat[Double]((1, 2), (3, 4))
    val m2 = Mat[Double]((5, 6), (7, 8))
    val p1 = m1.dot(m2)
    val p2 = m1 * m2
    assertEquals(p1(0, 0), p2(0, 0))
  }
  */
  
  test("scalar division") {
    val m = Mat[Double]((10.0, 20.0), (30.0, 40.0))
    val result = m / 2.0
    assertEquals(result(0, 0), 5.0)
    assertEquals(result(1, 1), 20.0)
  }
  
  test("unary negation") {
    val m = Mat[Double]((1, -2), (3, -4))
    val neg = -m
    assertEquals(neg(0, 0), -1.0)
    assertEquals(neg(0, 1), 2.0)
    assertEquals(neg(1, 0), -3.0)
    assertEquals(neg(1, 1), 4.0)
  }
  
  // ============================================================================
  // Smart Type Promotion Tests
  // ============================================================================
  test("Mat[Double] + Double promotes to Mat[Double]") {
    val m = Mat[Double]((1, 2), (3, 4))  // Mat[Double]
    val result = m + 0.5
    
    assertEquals(result(0, 0), 1.5)
    assertEquals(result(1, 1), 4.5)
  }
  
  test("Mat[Double] - Double promotes to Mat[Double]") {
    val m = Mat[Double]((10, 20), (30, 40))  // Mat[Double]
    val result = m - 0.5
    
    assertEquals(result(0, 0), 9.5)
    assertEquals(result(1, 1), 39.5)
  }
  
  test("Mat[Double] + Mat[Double] promotes to Mat[Double]") {
    val m1 = Mat[Double]((1, 2), (3, 4))  // Mat[Double]
    val m2 = Mat[Double]((1.5, 2.5), (3.5, 4.5))  // Mat[Double]
    val result = m1 + m2
    
    assertEquals(result(0, 0), 2.5)
    assertEquals(result(1, 1), 8.5)
  }
  
  test("Mat[Double] - Mat[Double] promotes to Mat[Double]") {
    val m1 = Mat[Double]((10, 20), (30, 40))  // Mat[Double]
    val m2 = Mat[Double]((0.5, 1.5), (2.5, 3.5))  // Mat[Double]
    val result = m1 - m2
    
    assertEquals(result(0, 0), 9.5)
    assertEquals(result(1, 1), 36.5)
  }
  
  test("Mat[Double] *:* Mat[Double] promotes to Mat[Double]") {
    val m1 = Mat[Double]((2, 3), (4, 5))  // Mat[Double]
    val m2 = Mat[Double]((1.5, 2.0), (2.5, 3.0))  // Mat[Double]
    val result = m1 *:* m2
    
    assertEquals(result(0, 0), 3.0)
    assertEquals(result(1, 1), 15.0)
  }
  
  test("NumPy-like behavior: Int literals, Double operations") {
    // Python: m = np.array([[1, 2], [3, 4]])  # int64
    //         result = m / 2.0                 # auto-promotes to float64
    val m = Mat[Double]((1, 2), (3, 4))  // Mat[Double]
    val result = m / 2.0
    
    assertEquals(result(0, 0), 0.5)
    assertEquals(result(1, 1), 2.0)
  }
  
  test("Mixed Int literals and Double literals infer Double") {
    val m = Mat[Double]((1, 2.0), (3, 4.0))  // Should infer Mat[Double]
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(1, 1), 4.0)
  }
  
  // ============================================================================
  // Statistical Methods Tests
  // ============================================================================
  
  test("min finds minimum element") {
    val m = Mat[Double]((5, 2, 8), (1, 9, 3))
    assertEquals(m.min, 1.0)
  }
  
  test("max finds maximum element") {
    val m = Mat[Double]((5, 2, 8), (1, 9, 3))
    assertEquals(m.max, 9.0)
  }
  
  test("sum computes total") {
    val m = Mat[Double]((1, 2), (3, 4))
    assertEquals(m.sum, 10.0)
  }
  
  test("mean computes average") {
    val m = Mat[Double]((2.0, 4.0), (6.0, 8.0))
    assertEquals(m.mean, 5.0)
  }
  
  test("argmin returns index of minimum") {
    val m = Mat[Double]((5, 2, 8), (1, 9, 3))
    assertEquals(m.argmin, (1, 0))  // element at row 1, col 0
  }
  
  test("argmax returns index of maximum") {
    val m = Mat[Double]((5, 2, 8), (1, 9, 3))
    assertEquals(m.argmax, (1, 1))  // element at row 1, col 1
  }
  
  // ============================================================================
  // Functional Operations Tests
  // ============================================================================
  
  test("map applies function to each element") {
    val m = Mat[Double]((1, 2), (3, 4))
    val squared = m.map(x => x * x)
    assertEquals(squared(0, 0), 1.0)
    assertEquals(squared(0, 1), 4.0)
    assertEquals(squared(1, 0), 9.0)
    assertEquals(squared(1, 1), 16.0)
  }
  
  test("where returns indices matching predicate") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val indices = m.where(_ > 3)
    assert(indices.contains((1, 0)))  // 4
    assert(indices.contains((1, 1)))  // 5
    assert(indices.contains((1, 2)))  // 6
    assertEquals(indices.length, 3)
  }
  
  // ============================================================================
  // Display Tests
  // ============================================================================
  
  test("show formats matrix nicely") {
    val m = Mat[Double]((1, 2), (3, 4))
    val s = m.show
    assert(s.contains("Mat[Double]("))
    assert(s.contains("[1, 2]"))
    assert(s.contains("[3, 4]"))
    assert(s.contains("shape=(2,2)"))
  }

  test("show formats matrix nicely with wider range") {
    val m = Mat[Double]((1, 0.2), (0.0003, 4001.000004))
    val str = m.show
    val expect = """
    |Mat[Double](
    |  [   1.0,    0.2],
    |  [   0.0, 4001.0]
    |  shape=(2,2)
    """.trim.stripMargin
    assertEquals(str, expect)
  }
  
  test("show handles empty matrix") {
    val m = Mat.empty[Double]
    assertEquals(m.show, "Mat[Double]([], shape=(0, 0))")
  }

  test("show formats Float matrix nicely") {
    val m = Mat[Float]((1, 2), (3, 4))
    val s = m.show
    assert(s.contains("Mat[Float]("))
    assert(s.contains("[1, 2]"))
    assert(s.contains("[3, 4]"))
    assert(s.contains("shape=(2,2)"))
  }
  
  test("show handles empty matrix of type Float") {
    val m = Mat.empty[Float]
    assertEquals(m.show, "Mat[Float]([], shape=(0, 0))")
  }

  test("show formats Big matrix nicely") {
    val m = Mat[Big]((1, 2), (3, 4))
    val s = m.show
    assert(s.contains("Mat[Big]("))
    assert(s.contains("[1, 2]"))
    assert(s.contains("[3, 4]"))
    assert(s.contains("shape=(2,2)"))
  }
  
  test("show handles empty matrix of type Big") {
    val m = Mat.empty[Big]
    assertEquals(m.show, "Mat[Big]([], shape=(0, 0))")
  }
  
  // ============================================================================
  // NumPy Equivalence Examples (Documentation)
  // ============================================================================
  
  test("NumPy equivalence: zeros") {
    // NumPy: m = np.zeros((3, 4))
    val m = Mat.zeros[Double](3, 4)
    assertEquals(m.shape, (3, 4))
    assertEquals(m.sum, 0.0)
  }
  
  test("NumPy equivalence: array creation") {
    // NumPy: m = np.array([[1, 2], [3, 4]])
    val m = Mat[Double]((1, 2), (3, 4))
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(1, 1), 4.0)
  }
  
  test("NumPy equivalence: indexing") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    // NumPy: m[0, 1]
    assertEquals(m(0, 1), 2.0)
    // NumPy: m[-1, -1]
    assertEquals(m(-1, -1), 6.0)
  }
  
  test("NumPy equivalence: slicing") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    // NumPy: m[:, 1]
    val col = m(::, 1)
    assertEquals(col(0, 0), 2.0)
    assertEquals(col(1, 0), 5.0)
  }
  
  test("NumPy equivalence: transpose") {
    val m = Mat[Double]((1, 2), (3, 4))
    // NumPy: m.T
    val t = m.T
    assertEquals(t(0, 0), 1.0)
    assertEquals(t(0, 1), 3.0)
  }
  
  test("NumPy equivalence: matrix operations") {
    val m1 = Mat[Double]((1, 2), (3, 4))
    val m2 = Mat[Double]((5, 6), (7, 8))
    
    // NumPy: m1 + m2
    val sum = m1 + m2
    assertEquals(sum(0, 0), 6.0)
    
    // NumPy: m1 @ m2 (or m1.dot(m2))
    val prod = m1.dot(m2)
    assertEquals(prod(0, 0), 19.0)
  }

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

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
    val c = m.matCopy
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

  test("BLAS multiply matches pure JVM multiply") {
    val m1 = Mat.tabulate[Double](50, 50)((i, j) => (i + j).toDouble / 100.0)
    val m2 = Mat.tabulate[Double](50, 50)((i, j) => (i * j).toDouble / 100.0)

    val blasResult = m1.multiplyDoubleBLAS(m2)
    val pureResult = m1.multiplyDouble(m2)

    assertEquals(blasResult.shape, pureResult.shape)
    
    // Compare all elements within floating point tolerance
    var i = 0
    while i < blasResult.rows do
      var j = 0
      while j < blasResult.cols do
        assertEqualsDouble(blasResult(i, j), pureResult(i, j), 1e-8, s"mismatch at ($i,$j)")
        j += 1
      i += 1
  }

  test("BLAS multiply matches pure JVM for transposed matrices") {
    val m1 = Mat.tabulate[Double](30, 40)((i, j) => (i + j).toDouble / 100.0)
    val m2 = Mat.tabulate[Double](40, 30)((i, j) => (i * j).toDouble / 100.0)

    // Test all four transposition combinations
    for
      t1 <- Seq(false, true)
      t2 <- Seq(false, true)
    do
      val a = if t1 then m1.T else m1
      val b = if t2 then m2.T else m2
      if a.cols == b.rows then
        val blasResult = a.multiplyDoubleBLAS(b)
        val pureResult = a.multiplyDouble(b)
        var i = 0
        while i < blasResult.rows do
          var j = 0
          while j < blasResult.cols do
            assertEqualsDouble(blasResult(i, j), pureResult(i, j), 1e-8,
              s"mismatch at ($i,$j) with t1=$t1, t2=$t2")
            j += 1
          i += 1
  }

  // ============================================================================
  // diagonal
  // ============================================================================
  test("diagonal of square matrix") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val d = m.diagonal
    assertEquals(d.toList, List(1.0, 5.0, 9.0))
  }

  test("diagonal of non-square matrix takes min dimension") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))  // 2x3
    val d = m.diagonal
    assertEquals(d.toList, List(1.0, 5.0))
  }

  test("diagonal of transposed matrix") {
    val m = Mat[Double]((1, 2), (3, 4), (5, 6)).T  // 2x3 transposed
    val d = m.diagonal
    assertEquals(d.toList, List(1.0, 4.0))
  }

  // ============================================================================
  // norm
  // ============================================================================
  test("norm of row vector") {
    val v = Mat.row[Double](3.0, 4.0)
    assertEqualsDouble(v.norm, 5.0, 1e-10)
  }

  test("norm of column vector") {
    val v = Mat.col[Double](3.0, 4.0)
    assertEqualsDouble(v.norm, 5.0, 1e-10)
  }

  test("norm of unit vector") {
    val v = Mat.row[Double](1.0, 0.0, 0.0)
    assertEqualsDouble(v.norm, 1.0, 1e-10)
  }

  test("norm of Float vector") {
    val v = Mat.row[Float](3.0f, 4.0f)
    assertEqualsDouble(v.norm.toDouble, 5.0, 1e-5)
  }

  test("norm requires vector") {
    val m = Mat[Double]((1, 2), (3, 4))
    intercept[IllegalArgumentException] { m.norm }
  }

  // ============================================================================
  // hadamard
  // ============================================================================
  test("hadamard equals *:*") {
    val a = Mat[Double]((1, 2), (3, 4))
    val b = Mat[Double]((5, 6), (7, 8))
    val h = a.hadamard(b)
    val e = a *:* b
    assertEquals(h.shape, e.shape)
    var i = 0; while i < h.rows do
      var j = 0; while j < h.cols do
        assertEqualsDouble(h(i,j), e(i,j), 1e-10)
        j += 1
      i += 1
  }

  test("hadamard correct values") {
    val a = Mat[Double]((1, 2), (3, 4))
    val b = Mat[Double]((5, 6), (7, 8))
    val h = a.hadamard(b)
    assertEquals(h(0,0), 5.0)
    assertEquals(h(0,1), 12.0)
    assertEquals(h(1,0), 21.0)
    assertEquals(h(1,1), 32.0)
  }

  // ============================================================================
  // determinant
  // ============================================================================
  test("determinant of 1x1 matrix") {
    val m = Mat[Double](5.0)
    assertEqualsDouble(m.determinant, 5.0, 1e-10)
  }

  test("determinant of 2x2 matrix") {
    // det [[1,2],[3,4]] = 1*4 - 2*3 = -2
    val m = Mat[Double]((1, 2), (3, 4))
    assertEqualsDouble(m.determinant, -2.0, 1e-10)
  }

  test("determinant of 3x3 matrix") {
    // det [[1,2,3],[4,5,6],[7,8,10]] = -3
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 10))
    assertEqualsDouble(m.determinant, -3.0, 1e-10)
  }

  test("determinant of identity matrix is 1") {
    val m = Mat.eye[Double](4)
    assertEqualsDouble(m.determinant, 1.0, 1e-10)
  }

  test("determinant of singular matrix throws") {
    val m = Mat[Double]((1, 2), (2, 4))  // row2 = 2*row1
    intercept[ArithmeticException] { m.determinant }
  }

  test("determinant requires square matrix") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    intercept[IllegalArgumentException] { m.determinant }
  }

  // ============================================================================
  // inverse
  // ============================================================================
  test("inverse of 2x2 matrix") {
    val m   = Mat[Double]((1, 2), (3, 4))
    val inv = m.inverse
    // m * inv should be identity
    val prod = m * inv
    assertEqualsDouble(prod(0,0), 1.0, 1e-10)
    assertEqualsDouble(prod(0,1), 0.0, 1e-10)
    assertEqualsDouble(prod(1,0), 0.0, 1e-10)
    assertEqualsDouble(prod(1,1), 1.0, 1e-10)
  }

  test("inverse of 3x3 matrix: m * inv = I") {
    val m   = Mat[Double]((1, 2, 3), (0, 1, 4), (5, 6, 0))
    val inv = m.inverse
    val prod = m * inv
    val n = 3
    var i = 0
    while i < n do
      var j = 0
      while j < n do
        val expected = if i == j then 1.0 else 0.0
        assertEqualsDouble(prod(i,j), expected, 1e-10, s"identity check failed at ($i,$j)")
        j += 1
      i += 1
  }

  test("inverse of identity is identity") {
    val m   = Mat.eye[Double](3)
    val inv = m.inverse
    val n = 3
    var i = 0
    while i < n do
      var j = 0
      while j < n do
        val expected = if i == j then 1.0 else 0.0
        assertEqualsDouble(inv(i,j), expected, 1e-10)
        j += 1
      i += 1
  }

  test("inverse of singular matrix throws") {
    val m = Mat[Double]((1, 2), (2, 4))
    intercept[ArithmeticException] { m.inverse }
  }

  test("inverse requires square matrix") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    intercept[IllegalArgumentException] { m.inverse }
  }

  // ============================================================================
  // qrDecomposition
  // ============================================================================
  test("qr decomposition: Q * R = original matrix") {
    val m = Mat[Double]((1, 2), (3, 4), (5, 6))  // 3x2
    val (q, r) = m.qrDecomposition
    val reconstructed = q * r
    var i = 0
    while i < m.rows do
      var j = 0
      while j < m.cols do
        assertEqualsDouble(reconstructed(i,j), m(i,j), 1e-10, s"mismatch at ($i,$j)")
        j += 1
      i += 1
  }

  test("qr decomposition: Q is orthonormal (Q^T * Q = I)") {
    val m = Mat[Double]((1, 2), (3, 4), (5, 6))  // 3x2
    val (q, _) = m.qrDecomposition
    val qtq = q.T * q  // should be 2x2 identity
    val p = qtq.rows
    var i = 0
    while i < p do
      var j = 0
      while j < p do
        val expected = if i == j then 1.0 else 0.0
        assertEqualsDouble(qtq(i,j), expected, 1e-10, s"Q^T*Q not identity at ($i,$j)")
        j += 1
      i += 1
  }

  test("qr decomposition: R is upper triangular") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 10))
    val (_, r) = m.qrDecomposition
    var i = 1
    while i < r.rows do
      var j = 0
      while j < i do
        assertEqualsDouble(r(i,j), 0.0, 1e-10, s"R not upper triangular at ($i,$j)")
        j += 1
      i += 1
  }

  test("qr decomposition square matrix: Q * R = original") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 10))
    val (q, r) = m.qrDecomposition
    val reconstructed = q * r
    var i = 0
    while i < m.rows do
      var j = 0
      while j < m.cols do
        assertEqualsDouble(reconstructed(i,j), m(i,j), 1e-10, s"mismatch at ($i,$j)")
        j += 1
      i += 1
  }

  // ============================================================================
  // eigenvalues
  // ============================================================================
  test("eigenvalues of 2x2 symmetric matrix") {
    // [[2, 1], [1, 2]] has eigenvalues 3 and 1
    val m = Mat[Double]((2, 1), (1, 2))
    val eigs = m.eigenvalues().sorted
    assertEqualsDouble(eigs(0), 1.0, 1e-6)
    assertEqualsDouble(eigs(1), 3.0, 1e-6)
  }

  test("eigenvalues of identity matrix are all 1") {
    val m = Mat.eye[Double](3)
    val eigs = m.eigenvalues()
    eigs.foreach(e => assertEqualsDouble(e, 1.0, 1e-6))
  }

  test("eigenvalues of diagonal matrix equal diagonal entries") {
    // [[3,0,0],[0,1,0],[0,0,2]] has eigenvalues 1, 2, 3
    val m = Mat[Double]((3, 0, 0), (0, 1, 0), (0, 0, 2))
    val eigs = m.eigenvalues().sorted
    assertEqualsDouble(eigs(0), 1.0, 1e-6)
    assertEqualsDouble(eigs(1), 2.0, 1e-6)
    assertEqualsDouble(eigs(2), 3.0, 1e-6)
  }

  test("eigenvalues requires square matrix") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    intercept[IllegalArgumentException] { m.eigenvalues() }
  }

  // ============================================================================
  // trace
  // ============================================================================
  test("trace of square matrix") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    assertEqualsDouble(m.trace, 15.0, 1e-10)  // 1 + 5 + 9
  }

  test("trace of identity matrix equals n") {
    val m = Mat.eye[Double](4)
    assertEqualsDouble(m.trace, 4.0, 1e-10)
  }

  test("trace of non-square matrix uses min dimension") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))  // 2x3
    assertEqualsDouble(m.trace, 6.0, 1e-10)  // 1 + 5
  }

  // ============================================================================
  // allclose
  // ============================================================================
  test("allclose identical matrices") {
    val m = Mat[Double]((1, 2), (3, 4))
    assert(m.allclose(m))
  }

  test("allclose within tolerance") {
    val a = Mat[Double]((1.0, 2.0), (3.0, 4.0))
    val b = Mat[Double]((1.0 + 1e-9, 2.0 - 1e-9), (3.0, 4.0 + 1e-9))
    assert(a.allclose(b))
  }

  test("allclose outside tolerance") {
    val a = Mat[Double]((1.0, 2.0), (3.0, 4.0))
    val b = Mat[Double]((1.0, 2.0), (3.0, 4.1))
    assert(!a.allclose(b))
  }

  test("allclose shape mismatch returns false") {
    val a = Mat[Double]((1, 2), (3, 4))
    val b = Mat[Double]((1, 2, 3), (4, 5, 6))
    assert(!a.allclose(b))
  }

  test("allclose custom tolerance") {
    val a = Mat[Double]((1.0, 2.0))
    val b = Mat[Double]((1.05, 2.05))
    assert(!a.allclose(b))
    assert(a.allclose(b, atol = 0.1))
  }

  // ============================================================================
  // axis-aware sum
  // ============================================================================
  test("sum axis=0 gives column sums") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val s = m.sum(0)
    assertEquals(s.shape, (1, 3))
    assertEqualsDouble(s(0, 0), 5.0, 1e-10)
    assertEqualsDouble(s(0, 1), 7.0, 1e-10)
    assertEqualsDouble(s(0, 2), 9.0, 1e-10)
  }

  test("sum axis=1 gives row sums") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val s = m.sum(1)
    assertEquals(s.shape, (2, 1))
    assertEqualsDouble(s(0, 0), 6.0, 1e-10)
    assertEqualsDouble(s(1, 0), 15.0, 1e-10)
  }

  test("sum invalid axis throws") {
    val m = Mat[Double]((1, 2), (3, 4))
    intercept[IllegalArgumentException] { m.sum(2) }
  }

  // ============================================================================
  // axis-aware mean
  // ============================================================================
  test("mean axis=0 gives column means") {
    val m = Mat[Double]((1, 2, 3), (3, 4, 5))
    val s = m.mean(0)
    assertEquals(s.shape, (1, 3))
    assertEqualsDouble(s(0, 0), 2.0, 1e-10)
    assertEqualsDouble(s(0, 1), 3.0, 1e-10)
    assertEqualsDouble(s(0, 2), 4.0, 1e-10)
  }

  test("mean axis=1 gives row means") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val s = m.mean(1)
    assertEquals(s.shape, (2, 1))
    assertEqualsDouble(s(0, 0), 2.0, 1e-10)
    assertEqualsDouble(s(1, 0), 5.0, 1e-10)
  }

  // ============================================================================
  // axis-aware max/min
  // ============================================================================
  test("max axis=0 gives column maxima") {
    val m = Mat[Double]((1, 5, 3), (4, 2, 6))
    val mx = m.max(0)
    assertEquals(mx.shape, (1, 3))
    assertEqualsDouble(mx(0, 0), 4.0, 1e-10)
    assertEqualsDouble(mx(0, 1), 5.0, 1e-10)
    assertEqualsDouble(mx(0, 2), 6.0, 1e-10)
  }

  test("max axis=1 gives row maxima") {
    val m = Mat[Double]((1, 5, 3), (4, 2, 6))
    val mx = m.max(1)
    assertEquals(mx.shape, (2, 1))
    assertEqualsDouble(mx(0, 0), 5.0, 1e-10)
    assertEqualsDouble(mx(1, 0), 6.0, 1e-10)
  }

  test("min axis=0 gives column minima") {
    val m = Mat[Double]((1, 5, 3), (4, 2, 6))
    val mn = m.min(0)
    assertEquals(mn.shape, (1, 3))
    assertEqualsDouble(mn(0, 0), 1.0, 1e-10)
    assertEqualsDouble(mn(0, 1), 2.0, 1e-10)
    assertEqualsDouble(mn(0, 2), 3.0, 1e-10)
  }

  test("min axis=1 gives row minima") {
    val m = Mat[Double]((1, 5, 3), (4, 2, 6))
    val mn = m.min(1)
    assertEquals(mn.shape, (2, 1))
    assertEqualsDouble(mn(0, 0), 1.0, 1e-10)
    assertEqualsDouble(mn(1, 0), 2.0, 1e-10)
  }

  // ============================================================================
  // abs
  // ============================================================================
  test("abs of matrix") {
    val m = Mat[Double]((1, -2), (-3, 4))
    val a = m.abs
    assertEqualsDouble(a(0, 0), 1.0, 1e-10)
    assertEqualsDouble(a(0, 1), 2.0, 1e-10)
    assertEqualsDouble(a(1, 0), 3.0, 1e-10)
    assertEqualsDouble(a(1, 1), 4.0, 1e-10)
  }

  test("abs leaves positive values unchanged") {
    val m = Mat[Double]((1, 2), (3, 4))
    assert(m.abs.allclose(m))
  }

  // ============================================================================
  // sqrt
  // ============================================================================
  test("sqrt of matrix") {
    val m = Mat[Double]((4, 9), (16, 25))
    val s = m.sqrt
    assertEqualsDouble(s(0, 0), 2.0, 1e-10)
    assertEqualsDouble(s(0, 1), 3.0, 1e-10)
    assertEqualsDouble(s(1, 0), 4.0, 1e-10)
    assertEqualsDouble(s(1, 1), 5.0, 1e-10)
  }

  test("sqrt of Float matrix") {
    val m = Mat[Float]((4, 9), (16, 25))
    val s = m.sqrt
    assertEqualsDouble(s(0, 0).toDouble, 2.0, 1e-5)
    assertEqualsDouble(s(0, 1).toDouble, 3.0, 1e-5)
  }

  // ============================================================================
  // exp / log
  // ============================================================================
  test("exp of zeros matrix gives ones") {
    val m = Mat.zeros[Double](2, 2)
    assert(m.exp.allclose(Mat.ones[Double](2, 2)))
  }

  test("log and exp are inverses") {
    val m = Mat[Double]((1, 2), (3, 4))
    assert(m.exp.log.allclose(m, atol = 1e-10))
  }

  test("log of ones gives zeros") {
    val m = Mat.ones[Double](2, 3)
    assert(m.log.allclose(Mat.zeros[Double](2, 3)))
  }

  // ============================================================================
  // clip
  // ============================================================================
  test("clip values within range") {
    val m = Mat[Double]((-1, 2), (5, 3))
    val c = m.clip(0.0, 4.0)
    assertEqualsDouble(c(0, 0), 0.0, 1e-10)
    assertEqualsDouble(c(0, 1), 2.0, 1e-10)
    assertEqualsDouble(c(1, 0), 4.0, 1e-10)
    assertEqualsDouble(c(1, 1), 3.0, 1e-10)
  }

  test("clip with equal bounds gives constant matrix") {
    val m = Mat[Double]((1, 2), (3, 4))
    val c = m.clip(2.0, 2.0)
    assert(c.allclose(Mat.full[Double](2, 2, 2.0)))
  }

  // ============================================================================
  // vstack / hstack / concatenate
  // ============================================================================
  test("vstack two matrices") {
    val a = Mat[Double]((1, 2), (3, 4))
    val b = Mat[Double]((5, 6), (7, 8))
    val s = Mat.vstack(a, b)
    assertEquals(s.shape, (4, 2))
    assertEqualsDouble(s(0, 0), 1.0, 1e-10)
    assertEqualsDouble(s(2, 0), 5.0, 1e-10)
    assertEqualsDouble(s(3, 1), 8.0, 1e-10)
  }

  test("hstack two matrices") {
    val a = Mat[Double]((1, 2), (3, 4))
    val b = Mat[Double]((5, 6), (7, 8))
    val s = Mat.hstack(a, b)
    assertEquals(s.shape, (2, 4))
    assertEqualsDouble(s(0, 0), 1.0, 1e-10)
    assertEqualsDouble(s(0, 2), 5.0, 1e-10)
    assertEqualsDouble(s(1, 3), 8.0, 1e-10)
  }

  test("vstack three matrices") {
    val a = Mat[Double]((1, 2))
    val b = Mat[Double]((3, 4))
    val c = Mat[Double]((5, 6))
    val s = Mat.vstack(a, b, c)
    assertEquals(s.shape, (3, 2))
    assertEqualsDouble(s(2, 1), 6.0, 1e-10)
  }

  test("concatenate axis=0 same as vstack") {
    val a = Mat[Double]((1, 2), (3, 4))
    val b = Mat[Double]((5, 6))
    val s1 = Mat.concatenate(Seq(a, b), axis = 0)
    val s2 = Mat.vstack(a, b)
    assert(s1.allclose(s2))
  }

  test("concatenate axis=1 same as hstack") {
    val a = Mat[Double]((1, 2), (3, 4))
    val b = Mat[Double]((5, 6), (7, 8))
    val s1 = Mat.concatenate(Seq(a, b), axis = 1)
    val s2 = Mat.hstack(a, b)
    assert(s1.allclose(s2))
  }

  test("vstack mismatched cols throws") {
    val a = Mat[Double]((1, 2), (3, 4))
    val b = Mat[Double]((5, 6, 7))
    intercept[IllegalArgumentException] { Mat.vstack(a, b) }
  }

  test("hstack mismatched rows throws") {
    val a = Mat[Double]((1, 2), (3, 4))
    val b = Mat[Double]((5, 6, 7))
    intercept[IllegalArgumentException] { Mat.hstack(a, b) }
  }

  // ============================================================================
  // outer
  // ============================================================================
  test("outer product of two vectors") {
    val a = Mat.col[Double](1, 2, 3)
    val b = Mat.col[Double](4, 5)
    val o = a.outer(b)
    assertEquals(o.shape, (3, 2))
    assertEqualsDouble(o(0, 0), 4.0, 1e-10)   // 1*4
    assertEqualsDouble(o(0, 1), 5.0, 1e-10)   // 1*5
    assertEqualsDouble(o(1, 0), 8.0, 1e-10)   // 2*4
    assertEqualsDouble(o(2, 1), 15.0, 1e-10)  // 3*5
  }

  test("outer product of row vectors") {
    val a = Mat.row[Double](1, 2)
    val b = Mat.row[Double](3, 4)
    val o = a.outer(b)
    assertEquals(o.shape, (2, 2))
    assertEqualsDouble(o(0, 0), 3.0, 1e-10)
    assertEqualsDouble(o(1, 1), 8.0, 1e-10)
  }

  // ============================================================================
  // solve
  // ============================================================================
  test("solve simple 2x2 system") {
    // 2x + y = 5
    // x + 3y = 10
    // solution: x=1, y=3
    val A = Mat[Double]((2, 1), (1, 3))
    val b = Mat.col[Double](5, 10)
    val x = A.solve(b)
    assertEqualsDouble(x(0, 0), 1.0, 1e-10)
    assertEqualsDouble(x(1, 0), 3.0, 1e-10)
  }

  test("solve: A * x = b gives b") {
    val A = Mat[Double]((1, 2, 3), (0, 1, 4), (5, 6, 0))
    val b = Mat.col[Double](1, 2, 3)
    val x = A.solve(b)
    val check = A * x
    assertEqualsDouble(check(0, 0), 1.0, 1e-10)
    assertEqualsDouble(check(1, 0), 2.0, 1e-10)
    assertEqualsDouble(check(2, 0), 3.0, 1e-10)
  }

  test("solve identity system") {
    val A = Mat.eye[Double](3)
    val b = Mat.col[Double](4, 5, 6)
    val x = A.solve(b)
    assert(x.allclose(b))
  }

  test("solve singular matrix throws") {
    val A = Mat[Double]((1, 2), (2, 4))
    val b = Mat.col[Double](1, 2)
    intercept[ArithmeticException] { A.solve(b) }
  }

  test("solve multiple RHS columns") {
    val A = Mat.eye[Double](3)
    val b = Mat[Double]((1, 4), (2, 5), (3, 6))  // 3x2 RHS
    val x = A.solve(b)
    assert(x.allclose(b))
  }

  // ============================================================================
  // rand / randn
  // ============================================================================
  test("rand shape is correct") {
    val m = Mat.rand(3, 4)
    assertEquals(m.shape, (3, 4))
  }

  test("rand values in [0, 1)") {
    val m = Mat.rand(10, 10)
    assert(m.min >= 0.0)
    assert(m.max < 1.0)
  }

  test("rand with seed is reproducible") {
    val m1 = Mat.rand(3, 3, seed = 42)
    val m2 = Mat.rand(3, 3, seed = 42)
    assert(m1.allclose(m2))
  }

  test("rand different seeds give different results") {
    val m1 = Mat.rand(3, 3, seed = 42)
    val m2 = Mat.rand(3, 3, seed = 99)
    assert(!m1.allclose(m2))
  }

  test("randn shape is correct") {
    val m = Mat.randn(3, 4)
    assertEquals(m.shape, (3, 4))
  }

  test("randn with seed is reproducible") {
    val m1 = Mat.randn(3, 3, seed = 42)
    val m2 = Mat.randn(3, 3, seed = 42)
    assert(m1.allclose(m2))
  }

  test("randn values roughly normal (mean near 0, std near 1)") {
    val m = Mat.randn(100, 100)
    val mu = m.mean
    val variance = m.map((x: Double) => (x - mu) * (x - mu)).mean
    // With 10000 samples, mean should be very close to 0
    assertEqualsDouble(m.mean, 0.0, 0.1)
    // Variance should be close to 1
    assertEqualsDouble(variance, 1.0, 0.1)
  }

  // ============================================================================
  // cumsum
  // ============================================================================
  test("cumsum no axis flattens and accumulates") {
    val m = Mat[Double]((1, 2), (3, 4))
    val s = m.cumsum
    assertEquals(s.shape, (1, 4))
    assertEqualsDouble(s(0, 0), 1.0, 1e-10)
    assertEqualsDouble(s(0, 1), 3.0, 1e-10)
    assertEqualsDouble(s(0, 2), 6.0, 1e-10)
    assertEqualsDouble(s(0, 3), 10.0, 1e-10)
  }

  test("cumsum axis=0 accumulates down rows") {
    val m = Mat[Double]((1, 2), (3, 4))
    val s = m.cumsum(0)
    assertEquals(s.shape, (2, 2))
    assertEqualsDouble(s(0, 0), 1.0, 1e-10)
    assertEqualsDouble(s(0, 1), 2.0, 1e-10)
    assertEqualsDouble(s(1, 0), 4.0, 1e-10)  // 1+3
    assertEqualsDouble(s(1, 1), 6.0, 1e-10)  // 2+4
  }

  test("cumsum axis=1 accumulates across cols") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val s = m.cumsum(1)
    assertEquals(s.shape, (2, 3))
    assertEqualsDouble(s(0, 0), 1.0, 1e-10)
    assertEqualsDouble(s(0, 1), 3.0, 1e-10)  // 1+2
    assertEqualsDouble(s(0, 2), 6.0, 1e-10)  // 1+2+3
    assertEqualsDouble(s(1, 0), 4.0, 1e-10)
    assertEqualsDouble(s(1, 1), 9.0, 1e-10)  // 4+5
    assertEqualsDouble(s(1, 2), 15.0, 1e-10) // 4+5+6
  }

  test("cumsum invalid axis throws") {
    val m = Mat[Double]((1, 2), (3, 4))
    intercept[IllegalArgumentException] { m.cumsum(2) }
  }

  // ============================================================================
  // cov
  // ============================================================================
  test("cov of single variable is variance") {
    // row = variable, cols = observations
    val m = Mat[Double]((2, 4, 4, 4, 5, 5, 7, 9))
    val c = m.cov
    assertEquals(c.shape, (1, 1))
    // variance of [2,4,4,4,5,5,7,9] = 4.571... (sample variance)
    assertEqualsDouble(c(0, 0), 32.0 / 7.0, 1e-10)
  }

  test("cov of two variables is 2x2") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val c = m.cov
    assertEquals(c.shape, (2, 2))
    // diagonal should be variances, off-diagonal covariances
    // var([1,2,3]) = 1.0,  var([4,5,6]) = 1.0, cov = 1.0
    assertEqualsDouble(c(0, 0), 1.0, 1e-10)
    assertEqualsDouble(c(1, 1), 1.0, 1e-10)
    assertEqualsDouble(c(0, 1), 1.0, 1e-10)
    assertEqualsDouble(c(1, 0), 1.0, 1e-10)
  }

  test("cov matrix is symmetric") {
    val m = Mat[Double]((1, 3, 2), (4, 2, 5), (1, 1, 3))
    val c = m.cov
    var i = 0
    while i < c.rows do
      var j = 0
      while j < c.cols do
        assertEqualsDouble(c(i, j), c(j, i), 1e-10, s"not symmetric at ($i,$j)")
        j += 1
      i += 1
  }

  test("cov requires at least 2 observations") {
    val m = Mat[Double](2, 1, Array(1.0, 2.0))
    intercept[IllegalArgumentException] { m.cov }
  }

  // ============================================================================
  // corrcoef
  // ============================================================================
  test("corrcoef diagonal is all ones") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val r = m.corrcoef
    assertEqualsDouble(r(0, 0), 1.0, 1e-10)
    assertEqualsDouble(r(1, 1), 1.0, 1e-10)
  }

  test("corrcoef of perfectly correlated variables is 1") {
    // y = 2x + 1, perfect positive correlation
    val m = Mat[Double]((1, 2, 3, 4), (3, 5, 7, 9))
    val r = m.corrcoef
    assertEqualsDouble(r(0, 1), 1.0, 1e-10)
    assertEqualsDouble(r(1, 0), 1.0, 1e-10)
  }

  test("corrcoef of perfectly anti-correlated variables is -1") {
    val m = Mat[Double]((1, 2, 3, 4), (4, 3, 2, 1))
    val r = m.corrcoef
    assertEqualsDouble(r(0, 1), -1.0, 1e-10)
    assertEqualsDouble(r(1, 0), -1.0, 1e-10)
  }

  test("corrcoef is symmetric") {
    val m = Mat[Double]((1, 3, 2), (4, 2, 5), (1, 1, 3))
    val r = m.corrcoef
    var i = 0
    while i < r.rows do
      var j = 0
      while j < r.cols do
        assertEqualsDouble(r(i, j), r(j, i), 1e-10, s"not symmetric at ($i,$j)")
        j += 1
      i += 1
  }

  // ============================================================================
  // sort
  // ============================================================================
  test("sort no axis flattens and sorts") {
    val m = Mat[Double]((3, 1), (4, 2))
    val s = m.sort()
    assertEquals(s.shape, (1, 4))
    assertEqualsDouble(s(0, 0), 1.0, 1e-10)
    assertEqualsDouble(s(0, 1), 2.0, 1e-10)
    assertEqualsDouble(s(0, 2), 3.0, 1e-10)
    assertEqualsDouble(s(0, 3), 4.0, 1e-10)
  }

  test("sort axis=0 sorts each column") {
    val m = Mat[Double]((3, 1), (1, 4), (2, 2))
    val s = m.sort(0)
    assertEquals(s.shape, (3, 2))
    assertEqualsDouble(s(0, 0), 1.0, 1e-10)
    assertEqualsDouble(s(1, 0), 2.0, 1e-10)
    assertEqualsDouble(s(2, 0), 3.0, 1e-10)
    assertEqualsDouble(s(0, 1), 1.0, 1e-10)
    assertEqualsDouble(s(1, 1), 2.0, 1e-10)
    assertEqualsDouble(s(2, 1), 4.0, 1e-10)
  }

  test("sort axis=1 sorts each row") {
    val m = Mat[Double]((3, 1, 2), (6, 4, 5))
    val s = m.sort(1)
    assertEquals(s.shape, (2, 3))
    assertEqualsDouble(s(0, 0), 1.0, 1e-10)
    assertEqualsDouble(s(0, 1), 2.0, 1e-10)
    assertEqualsDouble(s(0, 2), 3.0, 1e-10)
    assertEqualsDouble(s(1, 0), 4.0, 1e-10)
    assertEqualsDouble(s(1, 1), 5.0, 1e-10)
    assertEqualsDouble(s(1, 2), 6.0, 1e-10)
  }

  // ============================================================================
  // argsort
  // ============================================================================
  test("argsort no axis returns flat sort indices") {
    val m = Mat[Double]((3, 1), (4, 2))
    val idx = m.argsort()
    assertEquals(idx.shape, (1, 4))
    assertEquals(idx(0, 0), 1)  // value 1 at flat index 1
    assertEquals(idx(0, 1), 3)  // value 2 at flat index 3
    assertEquals(idx(0, 2), 0)  // value 3 at flat index 0
    assertEquals(idx(0, 3), 2)  // value 4 at flat index 2
  }

  test("argsort axis=1 returns indices that would sort each row") {
    val m = Mat[Double]((3, 1, 2), (6, 4, 5))
    val idx = m.argsort(1)
    assertEquals(idx.shape, (2, 3))
    assertEquals(idx(0, 0), 1)  // smallest in row 0 is at col 1
    assertEquals(idx(0, 1), 2)
    assertEquals(idx(0, 2), 0)
    assertEquals(idx(1, 0), 1)  // smallest in row 1 is at col 1
    assertEquals(idx(1, 1), 2)
    assertEquals(idx(1, 2), 0)
  }

  test("argsort axis=0 returns indices that would sort each column") {
    val m = Mat[Double]((3, 1), (1, 4), (2, 2))
    val idx = m.argsort(0)
    assertEquals(idx.shape, (3, 2))
    assertEquals(idx(0, 0), 1)  // smallest in col 0 is at row 1
    assertEquals(idx(1, 0), 2)
    assertEquals(idx(2, 0), 0)
    assertEquals(idx(0, 1), 0)  // smallest in col 1 is at row 0
    assertEquals(idx(1, 1), 2)
    assertEquals(idx(2, 1), 1)
  }

  // ============================================================================
  // unique
  // ============================================================================
  test("unique returns sorted distinct values") {
    val m = Mat[Double]((3, 1, 2), (1, 3, 2))
    val (vals, counts) = m.unique
    assertEquals(vals.toList, List(1.0, 2.0, 3.0))
  }

  test("unique counts are correct") {
    val m = Mat[Double]((1, 2, 1), (2, 3, 3))
    val (vals, counts) = m.unique
    assertEquals(vals.toList,  List(1.0, 2.0, 3.0))
    assertEquals(counts.toList, List(2, 2, 2))
  }

  test("unique of matrix with all same values") {
    val m = Mat.full[Double](3, 3, 5.0)
    val (vals, counts) = m.unique
    assertEquals(vals.length, 1)
    assertEquals(vals(0), 5.0)
    assertEquals(counts(0), 9)
  }

  // ============================================================================
  // svd
  // ============================================================================
  test("svd: U * diag(s) * Vt = original matrix") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))  // 2x3
    val (u, s, vt) = m.svd
    // Reconstruct: U * S * Vt
    val nRows = m.rows; val nCols = m.cols
    val p = s.length
    // Build sigma: nRows×nCols with s on diagonal
    val sigma = Mat.zeros[Double](nRows, nCols)
    var i = 0
    while i < p do
      sigma(i, i) = s(i)
      i += 1
    val reconstructed = u * sigma * vt
    var ri = 0
    while ri < nRows do
      var j = 0
      while j < nCols do
        assertEqualsDouble(reconstructed(ri, j), m(ri, j), 1e-8, s"mismatch at ($ri,$j)")
        j += 1
      ri += 1
  }

  test("svd: U is orthonormal (U^T * U = I)") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val (u, _, _) = m.svd
    val utu = u.T * u
    val n = utu.rows
    var i = 0
    while i < n do
      var j = 0
      while j < n do
        val expected = if i == j then 1.0 else 0.0
        assertEqualsDouble(utu(i, j), expected, 1e-8, s"U^T*U not identity at ($i,$j)")
        j += 1
      i += 1
  }

  test("svd: Vt is orthonormal (Vt * Vt^T = I)") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val (_, _, vt) = m.svd
    val vtVtT = vt * vt.T
    val n = vtVtT.rows
    var i = 0
    while i < n do
      var j = 0
      while j < n do
        val expected = if i == j then 1.0 else 0.0
        assertEqualsDouble(vtVtT(i, j), expected, 1e-8, s"Vt*Vt^T not identity at ($i,$j)")
        j += 1
      i += 1
  }

  test("svd singular values are non-negative and descending") {
    val m = Mat[Double]((4, 3), (2, 1))
    val (_, s, _) = m.svd
    assert(s.forall(_ >= 0.0))
    var i = 0
    while i < s.length - 1 do
      assert(s(i) >= s(i + 1), s"s($i)=${s(i)} < s(${i+1})=${s(i+1)}")
      i += 1
  }

  test("svd of identity matrix has singular values all 1") {
    val m = Mat.eye[Double](3)
    val (_, s, _) = m.svd
    s.foreach(sv => assertEqualsDouble(sv, 1.0, 1e-10))
  }

  // ============================================================================
  // lstsq
  // ============================================================================
  test("lstsq exact solution when square non-singular") {
    // Same as solve: 2x + y = 5, x + 3y = 10 → x=1, y=3
    val A = Mat[Double]((2, 1), (1, 3))
    val b = Mat.col[Double](5, 10)
    val (x, _, _, _) = A.lstsq(b)
    assertEqualsDouble(x(0, 0), 1.0, 1e-8)
    assertEqualsDouble(x(1, 0), 3.0, 1e-8)
  }

  test("lstsq overdetermined system minimizes residuals") {
    // Fit y = 2x through noisy points: (1,2.1), (2,3.9), (3,6.2)
    // A*x = b where A = [[1],[2],[3]], b = [2.1, 3.9, 6.2]
    val A = Mat[Double]((1, 1), (2, 1), (3, 1))  // design matrix with intercept
    val b = Mat.col[Double](2.1, 3.9, 6.2)
    val (x, residuals, rank, _) = A.lstsq(b)
    // slope should be ~2, intercept ~0
    assertEqualsDouble(x(0, 0), 2.05, 0.1)
    assertEquals(rank, 2)
    // residuals should be small but nonzero
    assert(residuals(0, 0) >= 0.0)
  }

  test("lstsq rank of full rank matrix") {
    val A = Mat[Double]((1, 0), (0, 1), (1, 1))
    val b = Mat.col[Double](1, 2, 3)
    val (_, _, rank, _) = A.lstsq(b)
    assertEquals(rank, 2)
  }

  test("lstsq singular values are non-negative") {
    val A = Mat[Double]((1, 2), (3, 4), (5, 6))
    val b = Mat.col[Double](1, 2, 3)
    val (_, _, _, s) = A.lstsq(b)
    s.foreach(sv => assert(sv >= 0.0))
  }

  test("lstsq A*x approximates b for overdetermined system") {
    val A = Mat[Double]((1, 1), (1, 2), (1, 3), (1, 4))
    val b = Mat.col[Double](6, 5, 7, 10)
    val (x, _, _, _) = A.lstsq(b)
    // A*x should be close to b in the least squares sense
    val ax = A * x
    // residual norm should be minimal - check it's at least finite and small
    var residNorm = 0.0
    var i = 0
    while i < b.rows do
      val diff = ax(i, 0) - b(i, 0)
      residNorm += diff * diff
      i += 1
    assert(residNorm < 10.0)  // loose bound - just sanity check
  }

  // ============================================================================
  // Comparison operators → Mat[Boolean]
  // ============================================================================
  test("gt returns correct boolean mask") {
    val m = Mat[Double]((1, 2), (3, 4))
    val mask = m.gt(2.0)
    assertEquals(mask.shape, (2, 2))
    assertEquals(mask(0, 0), false)
    assertEquals(mask(0, 1), false)
    assertEquals(mask(1, 0), true)
    assertEquals(mask(1, 1), true)
  }

  test("lt returns correct boolean mask") {
    val m = Mat[Double]((1, 2), (3, 4))
    val mask = m.lt(3.0)
    assertEquals(mask(0, 0), true)
    assertEquals(mask(0, 1), true)
    assertEquals(mask(1, 0), false)
    assertEquals(mask(1, 1), false)
  }

  test("gte includes boundary") {
    val m = Mat[Double]((1, 2), (3, 4))
    val mask = m.gte(2.0)
    assertEquals(mask(0, 0), false)
    assertEquals(mask(0, 1), true)
    assertEquals(mask(1, 0), true)
    assertEquals(mask(1, 1), true)
  }

  test("lte includes boundary") {
    val m = Mat[Double]((1, 2), (3, 4))
    val mask = m.lte(2.0)
    assertEquals(mask(0, 0), true)
    assertEquals(mask(0, 1), true)
    assertEquals(mask(1, 0), false)
    assertEquals(mask(1, 1), false)
  }

  test(":== returns correct boolean mask") {
    val m = Mat[Double]((1, 2), (2, 4))
    val mask = m.:==(2.0)
    assertEquals(mask(0, 0), false)
    assertEquals(mask(0, 1), true)
    assertEquals(mask(1, 0), true)
    assertEquals(mask(1, 1), false)
  }

  test(":!= returns correct boolean mask") {
    val m = Mat[Double]((1, 2), (2, 4))
    val mask = m.:!=(2.0)
    assertEquals(mask(0, 0), true)
    assertEquals(mask(0, 1), false)
    assertEquals(mask(1, 0), false)
    assertEquals(mask(1, 1), true)
  }

  test("gt with Int argument works") {
    val m = Mat[Double]((1, 2), (3, 4))
    val mask = m.gt(2)
    assertEquals(mask(1, 0), true)
    assertEquals(mask(0, 1), false)
  }

  test("lt with Int argument works") {
    val m = Mat[Double]((1, 2), (3, 4))
    val mask = m.lt(3)
    assertEquals(mask(0, 0), true)
    assertEquals(mask(1, 1), false)
  }

  test(":== with Int argument works") {
    val m = Mat[Double]((1, 2), (3, 4))
    val mask = m.:==(2)
    assertEquals(mask(0, 1), true)
    assertEquals(mask(0, 0), false)
  }

  test("comparison preserves shape") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    assertEquals(m.gt(3.0).shape, (2, 3))
  }

  // ============================================================================
  // Boolean mask indexing
  // ============================================================================
  test("boolean mask indexing returns matching elements as flat vector") {
    val m = Mat[Double]((1, 2), (3, 4))
    val result = m(m.gt(2.0))
    assertEquals(result.shape, (1, 2))
    assertEqualsDouble(result(0, 0), 3.0, 1e-10)
    assertEqualsDouble(result(0, 1), 4.0, 1e-10)
  }

  test("boolean mask indexing with no matches returns empty") {
    val m = Mat[Double]((1, 2), (3, 4))
    val result = m(m.gt(10.0))
    assertEquals(result.size, 0)
  }

  test("boolean mask indexing with all matches") {
    val m = Mat[Double]((1, 2), (3, 4))
    val result = m(m.gt(0.0))
    assertEquals(result.size, 4)
  }

  test("boolean mask assignment sets matching elements") {
    val m = Mat[Double]((1, 2), (3, 4))
    m(m.gt(2.0)) = 0.0
    assertEqualsDouble(m(0, 0), 1.0, 1e-10)
    assertEqualsDouble(m(0, 1), 2.0, 1e-10)
    assertEqualsDouble(m(1, 0), 0.0, 1e-10)
    assertEqualsDouble(m(1, 1), 0.0, 1e-10)
  }

  test("boolean mask assignment with :== ") {
    val m = Mat[Double]((1, 2), (2, 4))
    m(m.:==(2.0)) = -1.0
    assertEqualsDouble(m(0, 0),  1.0, 1e-10)
    assertEqualsDouble(m(0, 1), -1.0, 1e-10)
    assertEqualsDouble(m(1, 0), -1.0, 1e-10)
    assertEqualsDouble(m(1, 1),  4.0, 1e-10)
  }

  test("boolean mask shape mismatch throws") {
    val m    = Mat[Double]((1, 2), (3, 4))
    val mask = Mat.full[Boolean](2, 3, true)        // 2×3 - wrong shape
    intercept[IllegalArgumentException] { m(mask) }
  }

  // ============================================================================
  // Fancy indexing
  // ============================================================================
  test("fancy row indexing selects correct rows") {
    val m = Mat[Double]((1, 2), (3, 4), (5, 6))
    val r = m(Array(0, 2), ::)
    assertEquals(r.shape, (2, 2))
    assertEqualsDouble(r(0, 0), 1.0, 1e-10)
    assertEqualsDouble(r(0, 1), 2.0, 1e-10)
    assertEqualsDouble(r(1, 0), 5.0, 1e-10)
    assertEqualsDouble(r(1, 1), 6.0, 1e-10)
  }

  test("fancy col indexing selects correct cols") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val r = m(::, Array(0, 2))
    assertEquals(r.shape, (2, 2))
    assertEqualsDouble(r(0, 0), 1.0, 1e-10)
    assertEqualsDouble(r(0, 1), 3.0, 1e-10)
    assertEqualsDouble(r(1, 0), 4.0, 1e-10)
    assertEqualsDouble(r(1, 1), 6.0, 1e-10)
  }

  test("fancy row+col indexing selects submatrix") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r = m(Array(0, 2), Array(0, 2))
    assertEquals(r.shape, (2, 2))
    assertEqualsDouble(r(0, 0), 1.0, 1e-10)
    assertEqualsDouble(r(0, 1), 3.0, 1e-10)
    assertEqualsDouble(r(1, 0), 7.0, 1e-10)
    assertEqualsDouble(r(1, 1), 9.0, 1e-10)
  }

  test("fancy indexing preserves order of indices") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r = m(Array(2, 0, 1), ::)  // reversed row order
    assertEqualsDouble(r(0, 0), 7.0, 1e-10)
    assertEqualsDouble(r(1, 0), 1.0, 1e-10)
    assertEqualsDouble(r(2, 0), 4.0, 1e-10)
  }

  test("fancy row indexing out of bounds throws") {
    val m = Mat[Double]((1, 2), (3, 4))
    intercept[IllegalArgumentException] { m(Array(0, 5), ::) }
  }

  test("fancy col indexing out of bounds throws") {
    val m = Mat[Double]((1, 2), (3, 4))
    intercept[IllegalArgumentException] { m(::, Array(0, 5)) }
  }

  // ============================================================================
  // where (3-argument)
  // ============================================================================
  test("where with Mat arguments selects elementwise") {
    val cond = Mat[Double]((1, 2), (3, 4))
    val x    = Mat[Double]((10, 20), (30, 40))
    val y    = Mat[Double]((100, 200), (300, 400))
    val r    = Mat.where(cond.gt(2.0), x, y)
    assertEqualsDouble(r(0, 0), 100.0, 1e-10)  // 1 not > 2, take y
    assertEqualsDouble(r(0, 1), 200.0, 1e-10)  // 2 not > 2, take y
    assertEqualsDouble(r(1, 0),  30.0, 1e-10)  // 3 > 2, take x
    assertEqualsDouble(r(1, 1),  40.0, 1e-10)  // 4 > 2, take x
  }

  test("where with scalar arguments") {
    val m = Mat[Double]((1, 2), (3, 4))
    val r = Mat.where(m.gt(2.0), 1.0, -1.0)
    assertEqualsDouble(r(0, 0), -1.0, 1e-10)
    assertEqualsDouble(r(0, 1), -1.0, 1e-10)
    assertEqualsDouble(r(1, 0),  1.0, 1e-10)
    assertEqualsDouble(r(1, 1),  1.0, 1e-10)
  }

  test("where with Int scalars") {
    val m = Mat[Double]((1, 2), (3, 4))
    val r = Mat.where(m.gt(2), 1.0, -1.0)
    assertEqualsDouble(r(1, 0), 1.0, 1e-10)
    assertEqualsDouble(r(0, 0), -1.0, 1e-10)
  }

  // where shape mismatch throws
  test("where shape mismatch throws") {
    val cond = Mat.full[Boolean](2, 2, true)
    val x    = Mat[Double]((1, 2), (3, 4))
    val y    = Mat[Double]((1, 2, 3), (4, 5, 6))   // wrong shape
    intercept[IllegalArgumentException] { Mat.where(cond, x, y) }
  }

  test("where preserves shape") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val r = Mat.where(m.gte(3.0), 1.0, 0.0)
    assertEquals(r.shape, (2, 3))
  }

  // ============================================================================
  // diag
  // ============================================================================
  test("diag from array produces diagonal matrix") {
    val d = Mat.diag(Array(1.0, 2.0, 3.0))
    assertEquals(d.shape, (3, 3))
    assertEqualsDouble(d(0, 0), 1.0, 1e-10)
    assertEqualsDouble(d(1, 1), 2.0, 1e-10)
    assertEqualsDouble(d(2, 2), 3.0, 1e-10)
    assertEqualsDouble(d(0, 1), 0.0, 1e-10)
    assertEqualsDouble(d(1, 0), 0.0, 1e-10)
  }

  test("diag off-diagonal elements are zero") {
    val d = Mat.diag(Array(4.0, 5.0, 6.0))
    var i = 0
    while i < 3 do
      var j = 0
      while j < 3 do
        if i != j then assertEqualsDouble(d(i, j), 0.0, 1e-10)
        j += 1
      i += 1
  }

  test("diag from column vector Mat") {
    val v = Mat.col[Double](1, 2, 3)
    val d = Mat.diag(v)
    assertEquals(d.shape, (3, 3))
    assertEqualsDouble(d(0, 0), 1.0, 1e-10)
    assertEqualsDouble(d(1, 1), 2.0, 1e-10)
    assertEqualsDouble(d(2, 2), 3.0, 1e-10)
  }

  test("diag from row vector Mat") {
    val v = Mat.row[Double](1, 2, 3)
    val d = Mat.diag(v)
    assertEquals(d.shape, (3, 3))
    assertEqualsDouble(d(1, 1), 2.0, 1e-10)
  }

  test("diag rectangular nRows > nCols") {
    val d = Mat.diag(Array(1.0, 2.0), 3, 2)
    assertEquals(d.shape, (3, 2))
    assertEqualsDouble(d(0, 0), 1.0, 1e-10)
    assertEqualsDouble(d(1, 1), 2.0, 1e-10)
    assertEqualsDouble(d(2, 0), 0.0, 1e-10)
  }

  test("diag rectangular nRows < nCols") {
    val d = Mat.diag(Array(1.0, 2.0), 2, 3)
    assertEquals(d.shape, (2, 3))
    assertEqualsDouble(d(0, 0), 1.0, 1e-10)
    assertEqualsDouble(d(1, 1), 2.0, 1e-10)
    assertEqualsDouble(d(0, 2), 0.0, 1e-10)
  }

  test("diag from non-vector Mat throws") {
    val m = Mat[Double]((1, 2), (3, 4))
    intercept[IllegalArgumentException] { Mat.diag(m) }
  }

  test("diagonal of diag matrix recovers original values") {
    val values = Array(3.0, 1.0, 4.0)
    val d = Mat.diag(values)
    val recovered = d.diagonal
    assertEqualsDouble(recovered(0), 3.0, 1e-10)
    assertEqualsDouble(recovered(1), 1.0, 1e-10)
    assertEqualsDouble(recovered(2), 4.0, 1e-10)
  }
  // ============================================================================
  // repeat
  // ============================================================================
  test("repeat no axis repeats each element") {
    val m = Mat[Double]((1, 2), (3, 4))
    val r = m.repeat(2)
    assertEquals(r.shape, (1, 8))
    assertEqualsDouble(r(0, 0), 1.0, 1e-10)
    assertEqualsDouble(r(0, 1), 1.0, 1e-10)
    assertEqualsDouble(r(0, 2), 2.0, 1e-10)
    assertEqualsDouble(r(0, 3), 2.0, 1e-10)
    assertEqualsDouble(r(0, 4), 3.0, 1e-10)
    assertEqualsDouble(r(0, 5), 3.0, 1e-10)
    assertEqualsDouble(r(0, 6), 4.0, 1e-10)
    assertEqualsDouble(r(0, 7), 4.0, 1e-10)
  }

  test("repeat axis=0 repeats each row") {
    val m = Mat[Double]((1, 2), (3, 4))
    val r = m.repeat(3, 0)
    assertEquals(r.shape, (6, 2))
    assertEqualsDouble(r(0, 0), 1.0, 1e-10)
    assertEqualsDouble(r(1, 0), 1.0, 1e-10)
    assertEqualsDouble(r(2, 0), 1.0, 1e-10)
    assertEqualsDouble(r(3, 0), 3.0, 1e-10)
    assertEqualsDouble(r(4, 0), 3.0, 1e-10)
    assertEqualsDouble(r(5, 0), 3.0, 1e-10)
  }

  test("repeat axis=1 repeats each col") {
    val m = Mat[Double]((1, 2), (3, 4))
    val r = m.repeat(3, 1)
    assertEquals(r.shape, (2, 6))
    assertEqualsDouble(r(0, 0), 1.0, 1e-10)
    assertEqualsDouble(r(0, 1), 1.0, 1e-10)
    assertEqualsDouble(r(0, 2), 1.0, 1e-10)
    assertEqualsDouble(r(0, 3), 2.0, 1e-10)
    assertEqualsDouble(r(0, 4), 2.0, 1e-10)
    assertEqualsDouble(r(0, 5), 2.0, 1e-10)
  }

  test("repeat n=1 returns equivalent matrix") {
    val m = Mat[Double]((1, 2), (3, 4))
    val r = m.repeat(1, 0)
    assert(r.allclose(m))
  }

  // ============================================================================
  // tile
  // ============================================================================
  test("tile 1x1 returns equivalent matrix") {
    val m = Mat[Double]((1, 2), (3, 4))
    val r = m.tile(1, 1)
    assert(r.allclose(m))
  }

  test("tile 2x1 doubles rows") {
    val m = Mat[Double]((1, 2), (3, 4))
    val r = m.tile(2, 1)
    assertEquals(r.shape, (4, 2))
    assertEqualsDouble(r(0, 0), 1.0, 1e-10)
    assertEqualsDouble(r(1, 0), 3.0, 1e-10)
    assertEqualsDouble(r(2, 0), 1.0, 1e-10)  // tiles again
    assertEqualsDouble(r(3, 0), 3.0, 1e-10)
  }

  test("tile 1x3 triples cols") {
    val m = Mat[Double]((1, 2))
    val r = m.tile(1, 3)
    assertEquals(r.shape, (1, 6))
    assertEqualsDouble(r(0, 0), 1.0, 1e-10)
    assertEqualsDouble(r(0, 1), 2.0, 1e-10)
    assertEqualsDouble(r(0, 2), 1.0, 1e-10)
    assertEqualsDouble(r(0, 3), 2.0, 1e-10)
    assertEqualsDouble(r(0, 4), 1.0, 1e-10)
    assertEqualsDouble(r(0, 5), 2.0, 1e-10)
  }

  test("tile 2x2 tiles in both directions") {
    val m = Mat[Double]((1, 2), (3, 4))
    val r = m.tile(2, 2)
    assertEquals(r.shape, (4, 4))
    // top-left should be original
    assertEqualsDouble(r(0, 0), 1.0, 1e-10)
    assertEqualsDouble(r(0, 1), 2.0, 1e-10)
    assertEqualsDouble(r(1, 0), 3.0, 1e-10)
    assertEqualsDouble(r(1, 1), 4.0, 1e-10)
    // top-right should be original repeated
    assertEqualsDouble(r(0, 2), 1.0, 1e-10)
    assertEqualsDouble(r(0, 3), 2.0, 1e-10)
    // bottom-left should be original repeated
    assertEqualsDouble(r(2, 0), 1.0, 1e-10)
    assertEqualsDouble(r(3, 1), 4.0, 1e-10)
  }

  // ============================================================================
  // diff
  // ============================================================================
  test("diff no axis flattens and differences") {
    val m = Mat[Double]((1, 3), (6, 10))
    val d = m.diff
    assertEquals(d.shape, (1, 3))
    assertEqualsDouble(d(0, 0), 2.0, 1e-10)
    assertEqualsDouble(d(0, 1), 3.0, 1e-10)
    assertEqualsDouble(d(0, 2), 4.0, 1e-10)
  }

  test("diff axis=0 differences down rows") {
    val m = Mat[Double]((1, 2), (4, 6), (9, 11))
    val d = m.diff(0)
    assertEquals(d.shape, (2, 2))
    assertEqualsDouble(d(0, 0), 3.0, 1e-10)  // 4-1
    assertEqualsDouble(d(0, 1), 4.0, 1e-10)  // 6-2
    assertEqualsDouble(d(1, 0), 5.0, 1e-10)  // 9-4
    assertEqualsDouble(d(1, 1), 5.0, 1e-10)  // 11-6
  }

  test("diff axis=1 differences across cols") {
    val m = Mat[Double]((1, 3, 6), (10, 15, 21))
    val d = m.diff(1)
    assertEquals(d.shape, (2, 2))
    assertEqualsDouble(d(0, 0), 2.0, 1e-10)  // 3-1
    assertEqualsDouble(d(0, 1), 3.0, 1e-10)  // 6-3
    assertEqualsDouble(d(1, 0), 5.0, 1e-10)  // 15-10
    assertEqualsDouble(d(1, 1), 6.0, 1e-10)  // 21-15
  }

  test("diff of constant matrix is zeros") {
    val m = Mat.full[Double](3, 3, 5.0)
    val d = m.diff(0)
    assert(d.allclose(Mat.zeros[Double](2, 3)))
  }

  test("diff axis=0 requires at least 2 rows") {
    val m = Mat[Double]((1, 2, 3))
    intercept[IllegalArgumentException] { m.diff(0) }
  }

  test("diff axis=1 requires at least 2 cols") {
    val m = Mat.col[Double](1, 2, 3)
    intercept[IllegalArgumentException] { m.diff(1) }
  }

  // ============================================================================
  // percentile / median
  // ============================================================================
  test("percentile 0 is min") {
    val m = Mat[Double]((3, 1), (4, 2))
    assertEqualsDouble(m.percentile(0), 1.0, 1e-10)
  }

  test("percentile 100 is max") {
    val m = Mat[Double]((3, 1), (4, 2))
    assertEqualsDouble(m.percentile(100), 4.0, 1e-10)
  }

  test("percentile 50 is median") {
    val m = Mat[Double]((1, 2), (3, 4))
    assertEqualsDouble(m.percentile(50), m.median, 1e-10)
  }

  test("median of sorted vector") {
    val m = Mat.row[Double](1, 2, 3, 4, 5)
    assertEqualsDouble(m.median, 3.0, 1e-10)
  }

  test("median of even-length vector interpolates") {
    val m = Mat.row[Double](1, 2, 3, 4)
    assertEqualsDouble(m.median, 2.5, 1e-10)
  }

  test("percentile axis=0 gives column percentiles") {
    val m = Mat[Double]((1, 2), (3, 4), (5, 6))
    val p = m.percentile(50, 0)
    assertEquals(p.shape, (1, 2))
    assertEqualsDouble(p(0, 0), 3.0, 1e-10)
    assertEqualsDouble(p(0, 1), 4.0, 1e-10)
  }

  test("percentile axis=1 gives row percentiles") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val p = m.percentile(50, 1)
    assertEquals(p.shape, (2, 1))
    assertEqualsDouble(p(0, 0), 2.0, 1e-10)
    assertEqualsDouble(p(1, 0), 5.0, 1e-10)
  }

  test("median axis=0") {
    val m = Mat[Double]((1, 2), (3, 4), (5, 6))
    val med = m.median(0)
    assertEquals(med.shape, (1, 2))
    assertEqualsDouble(med(0, 0), 3.0, 1e-10)
    assertEqualsDouble(med(0, 1), 4.0, 1e-10)
  }

  test("percentile out of range throws") {
    val m = Mat[Double]((1, 2), (3, 4))
    intercept[IllegalArgumentException] { m.percentile(-1) }
    intercept[IllegalArgumentException] { m.percentile(101) }
  }

  // ============================================================================
  // matrixRank
  // ============================================================================
  test("matrixRank of identity matrix is n") {
    val m = Mat.eye[Double](4)
    assertEquals(m.matrixRank(), 4)
  }

  test("matrixRank of zero matrix is 0") {
    val m = Mat.zeros[Double](3, 3)
    assertEquals(m.matrixRank(), 0)
  }

  test("matrixRank of singular matrix is less than n") {
    // row2 = 2 * row1 → rank 1
    val m = Mat[Double]((1, 2), (2, 4))
    assertEquals(m.matrixRank(), 1)
  }

  test("matrixRank of full rank rectangular matrix") {
    val m = Mat[Double]((1, 0, 0), (0, 1, 0))  // 2×3 rank 2
    assertEquals(m.matrixRank(), 2)
  }

  test("matrixRank with custom tolerance") {
    val m = Mat[Double]((1, 2), (3, 4))
    assertEquals(m.matrixRank(tol = 1e-10), 2)
  }

  // ============================================================================
  // norm (matrix)
  // ============================================================================
  test("Frobenius norm of identity matrix is sqrt(n)") {
    val m = Mat.eye[Double](3)
    assertEqualsDouble(m.norm("fro"), math.sqrt(3.0), 1e-10)
  }

  test("Frobenius norm of known matrix") {
    // [[1,2],[3,4]] → sqrt(1+4+9+16) = sqrt(30)
    val m = Mat[Double]((1, 2), (3, 4))
    assertEqualsDouble(m.norm("fro"), math.sqrt(30.0), 1e-10)
  }

  test("infinity norm is max absolute row sum") {
    // row sums: [1+2=3, 3+4=7] → max = 7
    val m = Mat[Double]((1, 2), (3, 4))
    assertEqualsDouble(m.norm("inf"), 7.0, 1e-10)
  }

  test("1-norm is max absolute col sum") {
    // col sums: [1+3=4, 2+4=6] → max = 6
    val m = Mat[Double]((1, 2), (3, 4))
    assertEqualsDouble(m.norm("1"), 6.0, 1e-10)
  }

  test("Frobenius norm with negative values uses abs") {
    val m = Mat[Double]((-1, 2), (-3, 4))
    assertEqualsDouble(m.norm("fro"), math.sqrt(30.0), 1e-10)
  }

  test("unsupported norm ord throws") {
    val m = Mat[Double]((1, 2), (3, 4))
    intercept[IllegalArgumentException] { m.norm("2") }
  }

  // ============================================================================
  // isnan / isinf / isfinite
  // ============================================================================
  test("isnan detects NaN values") {
    val m = Mat[Double](2, 2, Array(1.0, Double.NaN, Double.NaN, 4.0))
    val r = m.isnan
    assertEquals(r(0, 0), false)
    assertEquals(r(0, 1), true)
    assertEquals(r(1, 0), true)
    assertEquals(r(1, 1), false)
  }

  test("isnan all false for finite matrix") {
    val m = Mat[Double]((1, 2), (3, 4))
    assert(!m.isnan.underlying.exists(identity))
  }

  test("isinf detects infinite values") {
    val m = Mat[Double](2, 2, Array(1.0, Double.PositiveInfinity, Double.NegativeInfinity, 4.0))
    val r = m.isinf
    assertEquals(r(0, 0), false)
    assertEquals(r(0, 1), true)
    assertEquals(r(1, 0), true)
    assertEquals(r(1, 1), false)
  }

  test("isfinite is complement of isnan and isinf") {
    val m = Mat[Double](1, 4, Array(1.0, Double.NaN, Double.PositiveInfinity, 4.0))
    val r = m.isfinite
    assertEquals(r(0, 0), true)
    assertEquals(r(0, 1), false)
    assertEquals(r(0, 2), false)
    assertEquals(r(0, 3), true)
  }

  // ============================================================================
  // nanToNum
  // ============================================================================
  test("nanToNum replaces NaN with 0 by default") {
    val m = Mat[Double](1, 3, Array(1.0, Double.NaN, 3.0))
    val r = m.nanToNum()
    assertEqualsDouble(r(0, 0), 1.0, 1e-10)
    assertEqualsDouble(r(0, 1), 0.0, 1e-10)
    assertEqualsDouble(r(0, 2), 3.0, 1e-10)
  }

  test("nanToNum replaces positive infinity") {
    val m = Mat[Double](1, 2, Array(Double.PositiveInfinity, 1.0))
    val r = m.nanToNum()
    assertEqualsDouble(r(0, 0), 0.0, 1e-10)
    assertEqualsDouble(r(0, 1), 1.0, 1e-10)
  }

  test("nanToNum replaces negative infinity") {
    val m = Mat[Double](1, 2, Array(Double.NegativeInfinity, 1.0))
    val r = m.nanToNum()
    assertEqualsDouble(r(0, 0), 0.0, 1e-10)
  }

  test("nanToNum with custom replacement values") {
    val m = Mat[Double](1, 3, Array(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity))
    val r = m.nanToNum(nan = -1.0, posinf = 999.0, neginf = -999.0)
    assertEqualsDouble(r(0, 0),   -1.0, 1e-10)
    assertEqualsDouble(r(0, 1),  999.0, 1e-10)
    assertEqualsDouble(r(0, 2), -999.0, 1e-10)
  }

  test("nanToNum leaves finite values unchanged") {
    val m = Mat[Double]((1, 2), (3, 4))
    assert(m.nanToNum().allclose(m))
  }

  test("nanToNum then isfinite all true") {
    val m = Mat[Double](1, 4, Array(1.0, Double.NaN, Double.PositiveInfinity, 4.0))
    assert(m.nanToNum().isfinite.underlying.forall(identity))
  }

  // ============================================================================
  // meshgrid
  // ============================================================================
  test("meshgrid produces correct shapes") {
    val x = Mat.row[Double](1, 2, 3)
    val y = Mat.row[Double](4, 5)
    val (xx, yy) = Mat.meshgrid(x, y)
    assertEquals(xx.shape, (2, 3))
    assertEquals(yy.shape, (2, 3))
  }

  test("meshgrid xx repeats x along rows") {
    val x = Mat.row[Double](1, 2, 3)
    val y = Mat.row[Double](4, 5)
    val (xx, _) = Mat.meshgrid(x, y)
    assertEqualsDouble(xx(0, 0), 1.0, 1e-10, "xx(0,0)")
    assertEqualsDouble(xx(0, 1), 2.0, 1e-10, "xx(0,1)")
    assertEqualsDouble(xx(0, 2), 3.0, 1e-10, "xx(0,2)")
    assertEqualsDouble(xx(1, 0), 1.0, 1e-10, "xx(1,0)")
    assertEqualsDouble(xx(1, 1), 2.0, 1e-10, "xx(1,1)")
    assertEqualsDouble(xx(1, 2), 3.0, 1e-10, "xx(1,2)")
  }

  test("meshgrid yy repeats y along cols") {
    val x = Mat.row[Double](1, 2, 3)
    val y = Mat.row[Double](4, 5)
    val (_, yy) = Mat.meshgrid(x, y)
    assertEqualsDouble(yy(0, 0), 4.0, 1e-10, "yy(0,0)")
    assertEqualsDouble(yy(0, 1), 4.0, 1e-10, "yy(0,1)")
    assertEqualsDouble(yy(0, 2), 4.0, 1e-10, "yy(0,2)")
    assertEqualsDouble(yy(1, 0), 5.0, 1e-10, "yy(1,0)")
    assertEqualsDouble(yy(1, 1), 5.0, 1e-10, "yy(1,1)")
    assertEqualsDouble(yy(1, 2), 5.0, 1e-10, "yy(1,2)")
  }

  test("meshgrid works with col vectors") {
    val x = Mat.col[Double](1, 2, 3)
    val y = Mat.col[Double](4, 5)
    val (xx, yy) = Mat.meshgrid(x, y)
    assertEquals(xx.shape, (2, 3))
    assertEquals(yy.shape, (2, 3))
    assertEqualsDouble(xx(0, 0), 1.0, 1e-10, "xx(0,0)")
    assertEqualsDouble(yy(1, 0), 5.0, 1e-10, "yy(1,0)")
  }

  test("meshgrid 1x1 produces 1x1 matrices") {
    val x = Mat.row[Double](3)
    val y = Mat.row[Double](7)
    val (xx, yy) = Mat.meshgrid(x, y)
    assertEquals(xx.shape, (1, 1))
    assertEqualsDouble(xx(0, 0), 3.0, 1e-10, "xx(0,0)")
    assertEqualsDouble(yy(0, 0), 7.0, 1e-10, "yy(0,0)")
  }

  // ============================================================================
  // polyval
  // ============================================================================
  test("polyval evaluates linear polynomial") {
    // 2x + 1 at x = [0, 1, 2]
    val coeffs = Mat.row[Double](2, 1)
    val x      = Mat.row[Double](0, 1, 2)
    val r      = Mat.polyval(coeffs, x)
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "x=0")
    assertEqualsDouble(r(0, 1), 3.0, 1e-10, "x=1")
    assertEqualsDouble(r(0, 2), 5.0, 1e-10, "x=2")
  }

  test("polyval evaluates quadratic polynomial") {
    // x^2 - 2x + 1 = (x-1)^2 at x = [0, 1, 2, 3]
    val coeffs = Mat.row[Double](1, -2, 1)
    val x      = Mat.row[Double](0, 1, 2, 3)
    val r      = Mat.polyval(coeffs, x)
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "x=0")
    assertEqualsDouble(r(0, 1), 0.0, 1e-10, "x=1")
    assertEqualsDouble(r(0, 2), 1.0, 1e-10, "x=2")
    assertEqualsDouble(r(0, 3), 4.0, 1e-10, "x=3")
  }

  test("polyval constant polynomial") {
    val coeffs = Mat.row[Double](5)
    val x      = Mat.row[Double](0, 1, 2)
    val r      = Mat.polyval(coeffs, x)
    assertEqualsDouble(r(0, 0), 5.0, 1e-10, "x=0")
    assertEqualsDouble(r(0, 1), 5.0, 1e-10, "x=1")
    assertEqualsDouble(r(0, 2), 5.0, 1e-10, "x=2")
  }

  // ============================================================================
  // polyfit
  // ============================================================================
  test("polyfit linear fit through exact points") {
    // y = 2x + 3, exact points
    val x = Mat.row[Double](0, 1, 2, 3)
    val y = Mat.row[Double](3, 5, 7, 9)
    val c = Mat.polyfit(x, y, 1)
    assertEquals(c.shape, (1, 2))
    assertEqualsDouble(c(0, 0), 2.0, 1e-8, "slope")
    assertEqualsDouble(c(0, 1), 3.0, 1e-8, "intercept")
  }

  test("polyfit quadratic fit through exact points") {
    // y = x^2, exact points
    val x = Mat.row[Double](0, 1, 2, 3, 4)
    val y = Mat.row[Double](0, 1, 4, 9, 16)
    val c = Mat.polyfit(x, y, 2)
    assertEquals(c.shape, (1, 3))
    assertEqualsDouble(c(0, 0), 1.0, 1e-6, "a2")  // x^2 coeff
    assertEqualsDouble(c(0, 1), 0.0, 1e-6, "a1")  // x coeff
    assertEqualsDouble(c(0, 2), 0.0, 1e-6, "a0")  // constant
  }

  test("polyfit then polyval reproduces original points") {
    val x = Mat.row[Double](1, 2, 3, 4, 5)
    val y = Mat.row[Double](2, 7, 14, 23, 34)  // y = x^2 + x
    val c = Mat.polyfit(x, y, 2)
    val r = Mat.polyval(c, x)
    var j = 0
    while j < x.cols do
      assertEqualsDouble(r(0, j), y(0, j), 1e-6, s"point $j")
      j += 1
  }

  test("polyfit degree too high throws") {
    val x = Mat.row[Double](1, 2)
    val y = Mat.row[Double](1, 2)
    intercept[IllegalArgumentException] { Mat.polyfit(x, y, 2) }
  }

  test("polyfit x y length mismatch throws") {
    val x = Mat.row[Double](1, 2, 3)
    val y = Mat.row[Double](1, 2)
    intercept[IllegalArgumentException] { Mat.polyfit(x, y, 1) }
  }

  // ============================================================================
  // convolve
  // ============================================================================
  test("convolve full mode") {
    // np.convolve([1,2,3], [4,5]) = [4, 13, 22, 15]
    val a = Mat.row[Double](1, 2, 3)
    val b = Mat.row[Double](4, 5)
    val r = Mat.convolve(a, b)
    assertEquals(r.shape, (1, 4))
    assertEqualsDouble(r(0, 0),  4.0, 1e-10, "r(0)")
    assertEqualsDouble(r(0, 1), 13.0, 1e-10, "r(1)")
    assertEqualsDouble(r(0, 2), 22.0, 1e-10, "r(2)")
    assertEqualsDouble(r(0, 3), 15.0, 1e-10, "r(3)")
  }

  test("convolve same mode") {
    val a = Mat.row[Double](1, 2, 3)
    val b = Mat.row[Double](4, 5)
    val r = Mat.convolve(a, b, "same")
    assertEquals(r.shape, (1, 3))
    assertEqualsDouble(r(0, 0),  4.0, 1e-10, "r(0)")
    assertEqualsDouble(r(0, 1), 13.0, 1e-10, "r(1)")
    assertEqualsDouble(r(0, 2), 22.0, 1e-10, "r(2)")
  }

  test("convolve valid mode") {
    val a = Mat.row[Double](1, 2, 3)
    val b = Mat.row[Double](4, 5)
    val r = Mat.convolve(a, b, "valid")
    assertEquals(r.shape, (1, 2))
    assertEqualsDouble(r(0, 0), 13.0, 1e-10, "r(0)")
    assertEqualsDouble(r(0, 1), 22.0, 1e-10, "r(1)")
  }

  test("convolve with identity filter") {
    // convolving with [1] returns original
    val a = Mat.row[Double](1, 2, 3, 4)
    val b = Mat.row[Double](1)
    val r = Mat.convolve(a, b, "same")
    assert(r.allclose(a))
  }

  test("convolve unknown mode throws") {
    val a = Mat.row[Double](1, 2, 3)
    val b = Mat.row[Double](4, 5)
    intercept[IllegalArgumentException] { Mat.convolve(a, b, "bad") }
  }

  // ============================================================================
  // correlate
  // ============================================================================
  test("correlate valid mode default") {
    // np.correlate([1,2,3], [4,5]) = [14, 23]
    val a = Mat.row[Double](1, 2, 3)
    val b = Mat.row[Double](4, 5)
    val r = Mat.correlate(a, b)
    assertEquals(r.shape, (1, 2))
    assertEqualsDouble(r(0, 0), 14.0, 1e-10, "r(0)")
    assertEqualsDouble(r(0, 1), 23.0, 1e-10, "r(1)")
  }

  test("correlate full mode") {
    val a = Mat.row[Double](1, 2, 3)
    val b = Mat.row[Double](4, 5)
    val r = Mat.correlate(a, b, "full")
    assertEquals(r.shape, (1, 4))
  }

  test("correlate of signal with itself peaks at center") {
    val a = Mat.row[Double](1, 2, 3, 2, 1)
    val r = Mat.correlate(a, a, "full")
    // peak should be at center index
    val center = r.cols / 2
    var i = 0
    while i < r.cols do
      assert(r(0, center) >= r(0, i), s"center not peak at i=$i")
      i += 1
  }

  // ============================================================================
  // broadcasting
  // ============================================================================
  test("addToEachRow adds row vector to every row") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val v = Mat.row[Double](10, 20, 30)
    val r = m.addToEachRow(v)
    assertEquals(r.shape, (2, 3))
    assertEqualsDouble(r(0, 0), 11.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(0, 1), 22.0, 1e-10, "r(0,1)")
    assertEqualsDouble(r(0, 2), 33.0, 1e-10, "r(0,2)")
    assertEqualsDouble(r(1, 0), 14.0, 1e-10, "r(1,0)")
    assertEqualsDouble(r(1, 1), 25.0, 1e-10, "r(1,1)")
    assertEqualsDouble(r(1, 2), 36.0, 1e-10, "r(1,2)")
  }

  test("addToEachCol adds col vector to every col") {
    val m = Mat[Double]((1, 2), (3, 4), (5, 6))
    val v = Mat.col[Double](10, 20, 30)
    val r = m.addToEachCol(v)
    assertEquals(r.shape, (3, 2))
    assertEqualsDouble(r(0, 0), 11.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(0, 1), 12.0, 1e-10, "r(0,1)")
    assertEqualsDouble(r(1, 0), 23.0, 1e-10, "r(1,0)")
    assertEqualsDouble(r(2, 0), 35.0, 1e-10, "r(2,0)")
  }

  test("mulEachRow multiplies each row by row vector") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val v = Mat.row[Double](2, 3, 4)
    val r = m.mulEachRow(v)
    assertEqualsDouble(r(0, 0),  2.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(0, 1),  6.0, 1e-10, "r(0,1)")
    assertEqualsDouble(r(0, 2), 12.0, 1e-10, "r(0,2)")
    assertEqualsDouble(r(1, 0),  8.0, 1e-10, "r(1,0)")
    assertEqualsDouble(r(1, 1), 15.0, 1e-10, "r(1,1)")
    assertEqualsDouble(r(1, 2), 24.0, 1e-10, "r(1,2)")
  }

  test("mulEachCol multiplies each col by col vector") {
    val m = Mat[Double]((1, 2), (3, 4), (5, 6))
    val v = Mat.col[Double](2, 3, 4)
    val r = m.mulEachCol(v)
    assertEqualsDouble(r(0, 0),  2.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(1, 0),  9.0, 1e-10, "r(1,0)")
    assertEqualsDouble(r(2, 0), 20.0, 1e-10, "r(2,0)")
    assertEqualsDouble(r(2, 1), 24.0, 1e-10, "r(2,1)")
  }

  test("subFromEachRow subtracts row vector from every row") {
    val m = Mat[Double]((10, 20), (30, 40))
    val v = Mat.row[Double](1, 2)
    val r = m.subFromEachRow(v)
    assertEqualsDouble(r(0, 0),  9.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(0, 1), 18.0, 1e-10, "r(0,1)")
    assertEqualsDouble(r(1, 0), 29.0, 1e-10, "r(1,0)")
    assertEqualsDouble(r(1, 1), 38.0, 1e-10, "r(1,1)")
  }

  test("subFromEachCol subtracts col vector from every col") {
    val m = Mat[Double]((10, 20), (30, 40))
    val v = Mat.col[Double](1, 2)
    val r = m.subFromEachCol(v)
    assertEqualsDouble(r(0, 0),  9.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(0, 1), 19.0, 1e-10, "r(0,1)")
    assertEqualsDouble(r(1, 0), 28.0, 1e-10, "r(1,0)")
    assertEqualsDouble(r(1, 1), 38.0, 1e-10, "r(1,1)")
  }

  test("divEachRow divides each row by row vector") {
    val m = Mat[Double]((2, 6), (4, 9))
    val v = Mat.row[Double](2, 3)
    val r = m.divEachRow(v)
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "r(0,0)")  // 2/2
    assertEqualsDouble(r(0, 1), 2.0, 1e-10, "r(0,1)")  // 6/3
    assertEqualsDouble(r(1, 0), 2.0, 1e-10, "r(1,0)")  // 4/2
    assertEqualsDouble(r(1, 1), 3.0, 1e-10, "r(1,1)")  // 9/3
  }

  test("divEachCol divides each col by col vector") {
    val m = Mat[Double]((4, 6), (9, 12))
    val v = Mat.col[Double](2, 3)
    val r = m.divEachCol(v)
    assertEqualsDouble(r(0, 0), 2.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(0, 1), 3.0, 1e-10, "r(0,1)")
    assertEqualsDouble(r(1, 0), 3.0, 1e-10, "r(1,0)")
    assertEqualsDouble(r(1, 1), 4.0, 1e-10, "r(1,1)")
  }

  test("addToEachRow wrong size throws") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val v = Mat.row[Double](1, 2)
    intercept[IllegalArgumentException] { m.addToEachRow(v) }
  }

  test("addToEachCol wrong size throws") {
    val m = Mat[Double]((1, 2), (3, 4), (5, 6))
    val v = Mat.col[Double](1, 2)
    intercept[IllegalArgumentException] { m.addToEachCol(v) }
  }

  test("broadcasting preserves shape") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val v = Mat.row[Double](1, 2, 3)
    assertEquals(m.addToEachRow(v).shape, (3, 3))
    assertEquals(m.mulEachRow(v).shape, (3, 3))
  }

  // ============================================================================
  // eig (non-symmetric)
  // ============================================================================
  test("eig of diagonal matrix has eigenvalues on diagonal") {
    val m = Mat[Double]((3, 0, 0), (0, 1, 0), (0, 0, 4))
    val (wr, wi, _) = m.eig
    val sorted = wr.sorted
    assertEqualsDouble(sorted(0), 1.0, 1e-8, "eig0")
    assertEqualsDouble(sorted(1), 3.0, 1e-8, "eig1")
    assertEqualsDouble(sorted(2), 4.0, 1e-8, "eig2")
  }

  test("eig of identity matrix has all eigenvalues 1") {
    val m = Mat.eye[Double](3)
    val (wr, wi, _) = m.eig
    wr.foreach(v => assertEqualsDouble(v, 1.0, 1e-8, "eig"))
  }

  test("eig imaginary parts are zero for symmetric matrix") {
    val m = Mat[Double]((2, 1), (1, 2))
    val (_, wi, _) = m.eig
    wi.foreach(v => assertEqualsDouble(v, 0.0, 1e-8, "imag"))
  }

  test("eig of 2x2 known matrix") {
    // [[1,2],[3,4]] eigenvalues: (5 ± sqrt(33)) / 2
    val m  = Mat[Double]((1, 2), (3, 4))
    val (wr, _, _) = m.eig
    val sorted = wr.sorted
    assertEqualsDouble(sorted(0), (5 - math.sqrt(33)) / 2, 1e-8, "eig0")
    assertEqualsDouble(sorted(1), (5 + math.sqrt(33)) / 2, 1e-8, "eig1")
  }

  test("eig non-square throws") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    intercept[IllegalArgumentException] { m.eig }
  }

  test("eig eigenvectors satisfy A*v = lambda*v") {
    val m = Mat[Double]((2, 1), (1, 2))
    val (wr, wi, vr) = m.eig
    // for each eigenvalue/vector pair, check A*v ≈ lambda*v
    var col = 0
    while col < 2 do
      if math.abs(wi(col)) < 1e-10 then  // only check real eigenvalues
        val v   = vr(::, col)
        val av  = m * v
        val lv  = v * wr(col)
        assert(av.allclose(lv, atol = 1e-8), s"A*v != lambda*v for col $col")
      col += 1
  }

  // ============================================================================
  // tril / triu
  // ============================================================================
  test("tril k=0 keeps lower triangular including diagonal") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r = m.tril()
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(0, 1), 0.0, 1e-10, "r(0,1)")
    assertEqualsDouble(r(0, 2), 0.0, 1e-10, "r(0,2)")
    assertEqualsDouble(r(1, 0), 4.0, 1e-10, "r(1,0)")
    assertEqualsDouble(r(1, 1), 5.0, 1e-10, "r(1,1)")
    assertEqualsDouble(r(1, 2), 0.0, 1e-10, "r(1,2)")
    assertEqualsDouble(r(2, 0), 7.0, 1e-10, "r(2,0)")
    assertEqualsDouble(r(2, 1), 8.0, 1e-10, "r(2,1)")
    assertEqualsDouble(r(2, 2), 9.0, 1e-10, "r(2,2)")
  }

  test("tril k=1 includes one superdiagonal") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r = m.tril(1)
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(0, 1), 2.0, 1e-10, "r(0,1)")
    assertEqualsDouble(r(0, 2), 0.0, 1e-10, "r(0,2)")
    assertEqualsDouble(r(1, 1), 5.0, 1e-10, "r(1,1)")
    assertEqualsDouble(r(1, 2), 6.0, 1e-10, "r(1,2)")
  }

  test("tril k=-1 excludes diagonal") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r = m.tril(-1)
    assertEqualsDouble(r(0, 0), 0.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(1, 0), 4.0, 1e-10, "r(1,0)")
    assertEqualsDouble(r(1, 1), 0.0, 1e-10, "r(1,1)")
    assertEqualsDouble(r(2, 0), 7.0, 1e-10, "r(2,0)")
    assertEqualsDouble(r(2, 1), 8.0, 1e-10, "r(2,1)")
    assertEqualsDouble(r(2, 2), 0.0, 1e-10, "r(2,2)")
  }

  test("triu k=0 keeps upper triangular including diagonal") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r = m.triu()
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(0, 1), 2.0, 1e-10, "r(0,1)")
    assertEqualsDouble(r(0, 2), 3.0, 1e-10, "r(0,2)")
    assertEqualsDouble(r(1, 0), 0.0, 1e-10, "r(1,0)")
    assertEqualsDouble(r(1, 1), 5.0, 1e-10, "r(1,1)")
    assertEqualsDouble(r(1, 2), 6.0, 1e-10, "r(1,2)")
    assertEqualsDouble(r(2, 0), 0.0, 1e-10, "r(2,0)")
    assertEqualsDouble(r(2, 1), 0.0, 1e-10, "r(2,1)")
    assertEqualsDouble(r(2, 2), 9.0, 1e-10, "r(2,2)")
  }

  test("triu k=1 excludes diagonal") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r = m.triu(1)
    assertEqualsDouble(r(0, 0), 0.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(0, 1), 2.0, 1e-10, "r(0,1)")
    assertEqualsDouble(r(0, 2), 3.0, 1e-10, "r(0,2)")
    assertEqualsDouble(r(1, 1), 0.0, 1e-10, "r(1,1)")
    assertEqualsDouble(r(1, 2), 6.0, 1e-10, "r(1,2)")
  }

  test("triu k=-1 includes one subdiagonal") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r = m.triu(-1)
    assertEqualsDouble(r(1, 0), 4.0, 1e-10, "r(1,0)")
    assertEqualsDouble(r(2, 0), 0.0, 1e-10, "r(2,0)")
    assertEqualsDouble(r(2, 1), 8.0, 1e-10, "r(2,1)")
  }

  test("tril + triu - original = diagonal matrix") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r = m.tril() + m.triu() - m
    // should equal m with only diagonal preserved
    assertEqualsDouble(r(0, 1), 0.0, 1e-10, "off-diagonal")
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "diagonal")
    assertEqualsDouble(r(1, 1), 5.0, 1e-10, "diagonal")
    assertEqualsDouble(r(2, 2), 9.0, 1e-10, "diagonal")
  }

  test("R from QR is upper triangular") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 10))
    val (_, r) = m.qrDecomposition
    assert(r.allclose(r.triu(), atol = 1e-8))
  }

  // ============================================================================
  // eye with offset k
  // ============================================================================
  test("eye k=0 is identity") {
    val m = Mat.eye[Double](3)
    assert(m.allclose(Mat.eye[Double](3, 0)))
  }

  test("eye k=1 is superdiagonal") {
    val m = Mat.eye[Double](3, 1)
    assertEqualsDouble(m(0, 1), 1.0, 1e-10, "m(0,1)")
    assertEqualsDouble(m(1, 2), 1.0, 1e-10, "m(1,2)")
    assertEqualsDouble(m(0, 0), 0.0, 1e-10, "m(0,0)")
    assertEqualsDouble(m(1, 1), 0.0, 1e-10, "m(1,1)")
  }

  test("eye k=-1 is subdiagonal") {
    val m = Mat.eye[Double](3, -1)
    assertEqualsDouble(m(1, 0), 1.0, 1e-10, "m(1,0)")
    assertEqualsDouble(m(2, 1), 1.0, 1e-10, "m(2,1)")
    assertEqualsDouble(m(0, 0), 0.0, 1e-10, "m(0,0)")
  }

  test("eye k=n-1 has single element in top right") {
    val m = Mat.eye[Double](3, 2)
    assertEqualsDouble(m(0, 2), 1.0, 1e-10, "m(0,2)")
    assertEqualsDouble(m(1, 2), 0.0, 1e-10, "m(1,2)")
  }

  test("eye k beyond bounds is all zeros") {
    val m = Mat.eye[Double](3, 3)
    assert(m.allclose(Mat.zeros[Double](3, 3)))
  }

  // ============================================================================
  // zerosLike / onesLike / fullLike
  // ============================================================================
  test("zerosLike has same shape and all zeros") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val r = Mat.zerosLike(m)
    assertEquals(r.shape, (2, 3))
    assert(r.allclose(Mat.zeros[Double](2, 3)))
  }

  test("onesLike has same shape and all ones") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val r = Mat.onesLike(m)
    assertEquals(r.shape, (2, 3))
    assert(r.allclose(Mat.ones[Double](2, 3)))
  }

  test("fullLike has same shape and correct value") {
    val m = Mat[Double]((1, 2), (3, 4))
    val r = Mat.fullLike(m, 7.0)
    assertEquals(r.shape, (2, 2))
    assert(r.allclose(Mat.full[Double](2, 2, 7.0)))
  }

  test("zerosLike does not share data with original") {
    val m = Mat[Double]((1, 2), (3, 4))
    val r = Mat.zerosLike(m)
    r(0, 0) = 99.0
    assertEqualsDouble(m(0, 0), 1.0, 1e-10, "original unchanged")
  }

  // ============================================================================
  // sign
  // ============================================================================
  test("sign of positive values is 1") {
    val m = Mat[Double]((1, 2), (3, 4))
    val s = m.sign
    assert(s.allclose(Mat.ones[Double](2, 2)))
  }

  test("sign of negative values is -1") {
    val m = Mat[Double]((-1, -2), (-3, -4))
    val s = m.sign
    assert(s.allclose(Mat.full[Double](2, 2, -1.0)))
  }

  test("sign of zero is 0") {
    val m = Mat.zeros[Double](2, 2)
    val s = m.sign
    assert(s.allclose(Mat.zeros[Double](2, 2)))
  }

  test("sign of mixed values") {
    val m = Mat[Double]((-3, 0, 5))
    val s = m.sign
    assertEqualsDouble(s(0, 0), -1.0, 1e-10, "negative")
    assertEqualsDouble(s(0, 1),  0.0, 1e-10, "zero")
    assertEqualsDouble(s(0, 2),  1.0, 1e-10, "positive")
  }

  // ============================================================================
  // round
  // ============================================================================
  test("round to 0 decimals") {
    val m = Mat[Double]((1.4, 1.5), (2.4, 2.5))
    val r = m.round()
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "1.4→1")
    assertEqualsDouble(r(0, 1), 2.0, 1e-10, "1.5→2")
    assertEqualsDouble(r(1, 0), 2.0, 1e-10, "2.4→2")
    assertEqualsDouble(r(1, 1), 3.0, 1e-10, "2.5→3") // or 2 depending on rounding mode
  }

  test("round to 2 decimals") {
    val m = Mat[Double]((1.234, 5.678))
    val r = m.round(2)
    assertEqualsDouble(r(0, 0), 1.23, 1e-10, "1.234→1.23")
    assertEqualsDouble(r(0, 1), 5.68, 1e-10, "5.678→5.68")
  }

  test("round negative decimals rounds to tens") {
    val m = Mat[Double]((123.4, 456.7))
    val r = m.round(-2)
    assertEqualsDouble(r(0, 0), 100.0, 1e-10, "123→100")
    assertEqualsDouble(r(0, 1), 500.0, 1e-10, "457→500")
  }

  test("round of integer values is unchanged") {
    val m = Mat[Double]((1, 2), (3, 4))
    assert(m.round().allclose(m))
  }

  // ============================================================================
  // power
  // ============================================================================
  test("power with Double exponent") {
    val m = Mat[Double]((4, 9), (16, 25))
    val r = m.power(0.5)  // square root
    assertEqualsDouble(r(0, 0), 2.0, 1e-10, "sqrt(4)")
    assertEqualsDouble(r(0, 1), 3.0, 1e-10, "sqrt(9)")
    assertEqualsDouble(r(1, 0), 4.0, 1e-10, "sqrt(16)")
    assertEqualsDouble(r(1, 1), 5.0, 1e-10, "sqrt(25)")
  }

  test("power with Int exponent 2") {
    val m = Mat[Double]((1, 2), (3, 4))
    val r = m.power(2)
    assertEqualsDouble(r(0, 0),  1.0, 1e-10, "1^2")
    assertEqualsDouble(r(0, 1),  4.0, 1e-10, "2^2")
    assertEqualsDouble(r(1, 0),  9.0, 1e-10, "3^2")
    assertEqualsDouble(r(1, 1), 16.0, 1e-10, "4^2")
  }

  test("power 0 gives all ones") {
    val m = Mat[Double]((2, 3), (4, 5))
    assert(m.power(0).allclose(Mat.ones[Double](2, 2)))
  }

  test("power 1 gives original") {
    val m = Mat[Double]((2, 3), (4, 5))
    assert(m.power(1).allclose(m))
  }

  test("power consistent with sqrt") {
    val m = Mat[Double]((4, 9), (16, 25))
    assert(m.power(0.5).allclose(m.sqrt, atol = 1e-10))
  }

  test("power negative Int throws") {
    val m = Mat[Double]((1, 2), (3, 4))
    intercept[UnsupportedOperationException] { m.power(-1) }
  }

  // ============================================================================
  // Range slicing mixed overloads
  // ============================================================================
  test("range rows all cols") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r = m(0 until 2, ::)
    assertEquals(r.shape, (2, 3))
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(1, 2), 6.0, 1e-10, "r(1,2)")
  }

  test("all rows range cols") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r = m(::, 1 until 3)
    assertEquals(r.shape, (3, 2))
    assertEqualsDouble(r(0, 0), 2.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(2, 1), 9.0, 1e-10, "r(2,1)")
  }

  test("single row range cols") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r = m(1, 0 until 2)
    assertEquals(r.shape, (1, 2))
    assertEqualsDouble(r(0, 0), 4.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(0, 1), 5.0, 1e-10, "r(0,1)")
  }

  test("range rows single col") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r = m(0 until 2, 1)
    assertEquals(r.shape, (2, 1))
    assertEqualsDouble(r(0, 0), 2.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(1, 0), 5.0, 1e-10, "r(1,0)")
  }

  test("step range rows") {
    val m = Mat[Double]((1, 2), (3, 4), (5, 6), (7, 8), (9, 10), (11, 12))
    val r = m(0 until 6 by 2, ::)
    assertEquals(r.shape, (3, 2))
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(1, 0), 5.0, 1e-10, "r(1,0)")
    assertEqualsDouble(r(2, 0), 9.0, 1e-10, "r(2,0)")
  }

  test("step range cols") {
    val m = Mat[Double]((1, 2, 3, 4, 5, 6))
    val r = m(::, 0 until 6 by 2)
    assertEquals(r.shape, (1, 3))
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(0, 1), 3.0, 1e-10, "r(0,1)")
    assertEqualsDouble(r(0, 2), 5.0, 1e-10, "r(0,2)")
  }

  test("reverse range rows") {
    val m = Mat[Double]((1, 2), (3, 4), (5, 6))
    val r = m(2 to 0 by -1, ::)
    assertEquals(r.shape, (3, 2))
    assertEqualsDouble(r(0, 0), 5.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(1, 0), 3.0, 1e-10, "r(1,0)")
    assertEqualsDouble(r(2, 0), 1.0, 1e-10, "r(2,0)")
  }

  // ============================================================================
  // toRowVec / toColVec
  // ============================================================================
  test("toRowVec reshapes to 1×n") {
    val m = Mat.col[Double](1, 2, 3)
    val r = m.toRowVec
    assertEquals(r.shape, (1, 3))
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(0, 2), 3.0, 1e-10, "r(0,2)")
  }

  test("toColVec reshapes to n×1") {
    val m = Mat.row[Double](1, 2, 3)
    val r = m.toColVec
    assertEquals(r.shape, (3, 1))
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(2, 0), 3.0, 1e-10, "r(2,0)")
  }

  test("toRowVec then toColVec round trips shape") {
    val m = Mat.col[Double](1, 2, 3)
    assertEquals(m.toRowVec.toColVec.shape, m.shape)
  }

  test("toRowVec does not share data with original") {
    val m = Mat.col[Double](1, 2, 3)
    val r = m.toRowVec
    r(0, 0) = 99.0
    assertEqualsDouble(m(0, 0), 1.0, 1e-10, "original unchanged")
  }

  test("toColVec of 2D matrix flattens then reshapes") {
    val m = Mat[Double]((1, 2), (3, 4))
    val r = m.toColVec
    assertEquals(r.shape, (4, 1))
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(3, 0), 4.0, 1e-10, "r(3,0)")
  }

  // ============================================================================
  // in-place scalar operators
  // ============================================================================
  test("+= scalar") {
    val m = Mat[Double]((1, 2), (3, 4))
    m :+= 10.0
    assertEqualsDouble(m(0, 0), 11.0, 1e-10, "m(0,0)")
    assertEqualsDouble(m(1, 1), 14.0, 1e-10, "m(1,1)")
  }

  test("-= scalar") {
    val m = Mat[Double]((5, 6), (7, 8))
    m :-= 2.0
    assertEqualsDouble(m(0, 0), 3.0, 1e-10, "m(0,0)")
    assertEqualsDouble(m(1, 1), 6.0, 1e-10, "m(1,1)")
  }

  test("*= scalar") {
    val m = Mat[Double]((1, 2), (3, 4))
    m :*= 3.0
    assertEqualsDouble(m(0, 0),  3.0, 1e-10, "m(0,0)")
    assertEqualsDouble(m(0, 1),  6.0, 1e-10, "m(0,1)")
    assertEqualsDouble(m(1, 0),  9.0, 1e-10, "m(1,0)")
    assertEqualsDouble(m(1, 1), 12.0, 1e-10, "m(1,1)")
  }

  test("/= scalar") {
    val m = Mat[Double]((4, 6), (8, 10))
    m :/= 2.0
    assertEqualsDouble(m(0, 0), 2.0, 1e-10, "m(0,0)")
    assertEqualsDouble(m(0, 1), 3.0, 1e-10, "m(0,1)")
    assertEqualsDouble(m(1, 0), 4.0, 1e-10, "m(1,0)")
    assertEqualsDouble(m(1, 1), 5.0, 1e-10, "m(1,1)")
  }

  test("+= Int scalar") {
    val m = Mat[Double]((1, 2), (3, 4))
    m :+= 1
    assertEqualsDouble(m(0, 0), 2.0, 1e-10, "m(0,0)")
    assertEqualsDouble(m(1, 1), 5.0, 1e-10, "m(1,1)")
  }

  test("-= Int scalar") {
    val m = Mat[Double]((5, 6), (7, 8))
    m :-= 1
    assertEqualsDouble(m(0, 0), 4.0, 1e-10, "m(0,0)")
    assertEqualsDouble(m(1, 1), 7.0, 1e-10, "m(1,1)")
  }

  test("*= Int scalar") {
    val m = Mat[Double]((1, 2), (3, 4))
    m :*= 2
    assertEqualsDouble(m(0, 1), 4.0, 1e-10, "m(0,1)")
    assertEqualsDouble(m(1, 0), 6.0, 1e-10, "m(1,0)")
  }

  test("/= Int scalar") {
    val m = Mat[Double]((4, 6), (8, 10))
    m :/= 2
    assertEqualsDouble(m(0, 0), 2.0, 1e-10, "m(0,0)")
    assertEqualsDouble(m(1, 1), 5.0, 1e-10, "m(1,1)")
  }

  // ============================================================================
  // in-place Mat operators
  // ============================================================================
  test("+= Mat element-wise") {
    val m = Mat[Double]((1, 2), (3, 4))
    val n = Mat[Double]((10, 20), (30, 40))
    m :+= n
    assertEqualsDouble(m(0, 0), 11.0, 1e-10, "m(0,0)")
    assertEqualsDouble(m(0, 1), 22.0, 1e-10, "m(0,1)")
    assertEqualsDouble(m(1, 0), 33.0, 1e-10, "m(1,0)")
    assertEqualsDouble(m(1, 1), 44.0, 1e-10, "m(1,1)")
  }

  test("-= Mat element-wise") {
    val m = Mat[Double]((10, 20), (30, 40))
    val n = Mat[Double]((1, 2), (3, 4))
    m :-= n
    assertEqualsDouble(m(0, 0),  9.0, 1e-10, "m(0,0)")
    assertEqualsDouble(m(0, 1), 18.0, 1e-10, "m(0,1)")
    assertEqualsDouble(m(1, 0), 27.0, 1e-10, "m(1,0)")
    assertEqualsDouble(m(1, 1), 36.0, 1e-10, "m(1,1)")
  }

  test("+= Mat shape mismatch throws") {
    val m = Mat[Double]((1, 2), (3, 4))
    val n = Mat[Double]((1, 2, 3), (4, 5, 6))
    intercept[IllegalArgumentException] { m :+= n }
  }

  test("-= Mat shape mismatch throws") {
    val m = Mat[Double]((1, 2), (3, 4))
    val n = Mat[Double]((1, 2, 3))
    intercept[IllegalArgumentException] { m :-= n }
  }

  test("+= Mat does not affect other") {
    val m = Mat[Double]((1, 2), (3, 4))
    val n = Mat[Double]((1, 1), (1, 1))
    m :+= n
    assertEqualsDouble(n(0, 0), 1.0, 1e-10, "n unchanged")
  }

  // ============================================================================
  // applyAlongAxis
  // ============================================================================
  test("applyAlongAxis axis=0 applies fn to each column") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    // sum of each column
    val r = m.applyAlongAxis(col => col.sum, 0)
    assertEquals(r.shape, (1, 3))
    assertEqualsDouble(r(0, 0), 5.0, 1e-10, "col0 sum")
    assertEqualsDouble(r(0, 1), 7.0, 1e-10, "col1 sum")
    assertEqualsDouble(r(0, 2), 9.0, 1e-10, "col2 sum")
  }

  test("applyAlongAxis axis=1 applies fn to each row") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    // sum of each row
    val r = m.applyAlongAxis(row => row.sum, 1)
    assertEquals(r.shape, (2, 1))
    assertEqualsDouble(r(0, 0),  6.0, 1e-10, "row0 sum")
    assertEqualsDouble(r(1, 0), 15.0, 1e-10, "row1 sum")
  }

  test("applyAlongAxis axis=0 with max") {
    val m = Mat[Double]((3, 1, 4), (1, 5, 9))
    val r = m.applyAlongAxis(col => col.max, 0)
    assertEqualsDouble(r(0, 0), 3.0, 1e-10, "col0 max")
    assertEqualsDouble(r(0, 1), 5.0, 1e-10, "col1 max")
    assertEqualsDouble(r(0, 2), 9.0, 1e-10, "col2 max")
  }

  test("applyAlongAxis axis=1 with mean") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val r = m.applyAlongAxis(row => row.mean, 1)
    assertEqualsDouble(r(0, 0), 2.0, 1e-10, "row0 mean")
    assertEqualsDouble(r(1, 0), 5.0, 1e-10, "row1 mean")
  }

  test("applyAlongAxis invalid axis throws") {
    val m = Mat[Double]((1, 2), (3, 4))
    intercept[IllegalArgumentException] { m.applyAlongAxis(col => col.sum, 2) }
  }

  test("applyAlongAxis axis=0 consistent with sum(0)") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r1 = m.applyAlongAxis(col => col.sum, 0)
    val r2 = m.sum(0)
    assert(r1.allclose(r2))
  }

  test("applyAlongAxis axis=1 consistent with sum(1)") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val r1 = m.applyAlongAxis(row => row.sum, 1)
    val r2 = m.sum(1)
    assert(r1.allclose(r2))
  }
  // ============================================================================
  // pinv
  // ============================================================================
  test("pinv of identity is identity") {
    val m = Mat.eye[Double](3)
    assert(m.pinv().allclose(Mat.eye[Double](3), atol = 1e-8))
  }

  test("pinv of square matrix: A * pinv(A) ≈ I") {
    val m = Mat[Double]((1, 2), (3, 4))
    val p = m.pinv()
    assert((m * p).allclose(Mat.eye[Double](2), atol = 1e-8))
  }

  test("pinv of rectangular matrix: A * pinv(A) ≈ I (nRows < nCols)") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))  // 2×3
    val p = m.pinv()
    assertEquals(p.shape, (3, 2))
    val r = m * p
    assert(r.allclose(Mat.eye[Double](2), atol = 1e-8))
  }

  test("pinv of rectangular matrix: pinv(A) * A ≈ I (nRows > nCols)") {
    val m = Mat[Double]((1, 2), (3, 4), (5, 6))  // 3×2
    val p = m.pinv()
    assertEquals(p.shape, (2, 3))
    val r = p * m
    assert(r.allclose(Mat.eye[Double](2), atol = 1e-8))
  }

  test("pinv of singular matrix does not throw") {
    val m = Mat[Double]((1, 2), (2, 4))  // rank 1
    val p = m.pinv()
    assertEquals(p.shape, (2, 2))
  }

  test("pinv double application: pinv(pinv(A)) ≈ A") {
    val m = Mat[Double]((1, 2), (3, 4))
    assert(m.pinv().pinv().allclose(m, atol = 1e-6))
  }

  // ============================================================================
  // cholesky
  // ============================================================================
  test("cholesky of identity is identity") {
    val m = Mat.eye[Double](3)
    assert(m.cholesky.allclose(Mat.eye[Double](3), atol = 1e-10))
  }

  test("cholesky L * L^T = original matrix") {
    val m = Mat[Double]((4, 2), (2, 3))  // symmetric positive definite
    val L = m.cholesky
    assert((L * L.T).allclose(m, atol = 1e-10))
  }

  test("cholesky result is lower triangular") {
    val m = Mat[Double]((4, 2), (2, 3))
    val L = m.cholesky
    assert(L.allclose(L.tril(), atol = 1e-10))
  }

  test("cholesky of known matrix") {
    // [[4,2],[2,3]] → L = [[2,0],[1,sqrt(2)]]
    val m = Mat[Double]((4, 2), (2, 3))
    val L = m.cholesky
    assertEqualsDouble(L(0, 0), 2.0,          1e-10, "L(0,0)")
    assertEqualsDouble(L(0, 1), 0.0,          1e-10, "L(0,1)")
    assertEqualsDouble(L(1, 0), 1.0,          1e-10, "L(1,0)")
    assertEqualsDouble(L(1, 1), math.sqrt(2), 1e-10, "L(1,1)")
  }

  test("cholesky 3x3 symmetric positive definite") {
    val m = Mat[Double]((6, 3, 2), (3, 5, 1), (2, 1, 4))
    val L = m.cholesky
    assert((L * L.T).allclose(m, atol = 1e-8))
    assert(L.allclose(L.tril(), atol = 1e-10))
  }

  test("cholesky non-square throws") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    intercept[IllegalArgumentException] { m.cholesky }
  }

  test("cholesky non-positive-definite throws") {
    val m = Mat[Double]((1, 2), (2, 1))  // not positive definite
    intercept[ArithmeticException] { m.cholesky }
  }

  // ============================================================================
  // cross
  // ============================================================================
  test("cross product of standard basis vectors") {
    val i = Mat.row[Double](1, 0, 0)
    val j = Mat.row[Double](0, 1, 0)
    val k = Mat.row[Double](0, 0, 1)
    assert(i.cross(j).allclose(k, atol = 1e-10))
    assert(j.cross(k).allclose(i, atol = 1e-10))
    assert(k.cross(i).allclose(j, atol = 1e-10))
  }

  test("cross product is anti-commutative") {
    val a = Mat.row[Double](1, 2, 3)
    val b = Mat.row[Double](4, 5, 6)
    val ab = a.cross(b)
    val ba = b.cross(a)
    assert(ab.allclose(ba * -1.0, atol = 1e-10))
  }

  test("cross product of parallel vectors is zero") {
    val a = Mat.row[Double](1, 2, 3)
    val b = Mat.row[Double](2, 4, 6)  // b = 2*a
    assert(a.cross(b).allclose(Mat.zeros[Double](1, 3), atol = 1e-10))
  }

  test("cross product known result") {
    // [1,2,3] × [4,5,6] = [-3, 6, -3]
    val a = Mat.row[Double](1, 2, 3)
    val b = Mat.row[Double](4, 5, 6)
    val r = a.cross(b)
    assertEqualsDouble(r(0, 0), -3.0, 1e-10, "x")
    assertEqualsDouble(r(0, 1),  6.0, 1e-10, "y")
    assertEqualsDouble(r(0, 2), -3.0, 1e-10, "z")
  }

  test("cross product of col vectors") {
    val a = Mat.col[Double](1, 0, 0)
    val b = Mat.col[Double](0, 1, 0)
    val r = a.cross(b)
    assertEqualsDouble(r(0, 2), 1.0, 1e-10, "z component")
  }

  test("cross product non-3D throws") {
    val a = Mat.row[Double](1, 2)
    val b = Mat.row[Double](3, 4)
    intercept[IllegalArgumentException] { a.cross(b) }
  }

  // ============================================================================
  // kron
  // ============================================================================
  test("kron with identity is block diagonal") {
    val a = Mat[Double]((1, 2), (3, 4))
    val i = Mat.eye[Double](2)
    val r = i.kron(a)  // I ⊗ a gives block diagonal, not a ⊗ I
    assertEquals(r.shape, (4, 4))
    // top-left block should be a
    assertEqualsDouble(r(0, 0), 1.0, 1e-10, "r(0,0)")
    assertEqualsDouble(r(0, 1), 2.0, 1e-10, "r(0,1)")
    assertEqualsDouble(r(1, 0), 3.0, 1e-10, "r(1,0)")
    assertEqualsDouble(r(1, 1), 4.0, 1e-10, "r(1,1)")
    // top-right block should be zeros
    assertEqualsDouble(r(0, 2), 0.0, 1e-10, "r(0,2)")
    assertEqualsDouble(r(0, 3), 0.0, 1e-10, "r(0,3)")
    // bottom-right block should be a
    assertEqualsDouble(r(2, 2), 1.0, 1e-10, "r(2,2)")
    assertEqualsDouble(r(3, 3), 4.0, 1e-10, "r(3,3)")
  }

  test("kron known result") {
    // [[1,2],[3,4]] ⊗ [[0,5],[6,7]]
    val a = Mat[Double]((1, 2), (3, 4))
    val b = Mat[Double]((0, 5), (6, 7))
    val r = a.kron(b)
    assertEquals(r.shape, (4, 4))
    // r[0:2, 0:2] = 1 * b
    assertEqualsDouble(r(0, 0),  0.0, 1e-10, "1*b(0,0)")
    assertEqualsDouble(r(0, 1),  5.0, 1e-10, "1*b(0,1)")
    assertEqualsDouble(r(1, 0),  6.0, 1e-10, "1*b(1,0)")
    assertEqualsDouble(r(1, 1),  7.0, 1e-10, "1*b(1,1)")
    // r[0:2, 2:4] = 2 * b
    assertEqualsDouble(r(0, 2),  0.0, 1e-10, "2*b(0,0)")
    assertEqualsDouble(r(0, 3), 10.0, 1e-10, "2*b(0,1)")
    assertEqualsDouble(r(1, 2), 12.0, 1e-10, "2*b(1,0)")
    assertEqualsDouble(r(1, 3), 14.0, 1e-10, "2*b(1,1)")
  }

  test("kron shape is correct") {
    val a = Mat[Double]((1, 2), (3, 4))        // 2×2
    val b = Mat[Double]((1, 2, 3), (4, 5, 6)) // 2×3
    val r = a.kron(b)
    assertEquals(r.shape, (4, 6))
  }

  test("kron with scalar matrix scales") {
    val a = Mat[Double]((1, 2), (3, 4))
    val s = Mat[Double](1, 1, Array(3.0))  // 1×1 scalar matrix
    val r = a.kron(s)
    assertEquals(r.shape, (2, 2))
    assert(r.allclose(a * 3.0))
  }

  test("kron is associative") {
    val a = Mat[Double]((1, 0), (0, 1))
    val b = Mat[Double]((1, 2), (3, 4))
    val c = Mat[Double]((0, 1), (1, 0))
    val r1 = a.kron(b).kron(c)
    val r2 = a.kron(b.kron(c))
    assert(r1.allclose(r2, atol = 1e-10))
  }

  // ============================================================================
  // slice assignment
  // ============================================================================
  test("range rows all cols scalar assignment") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    m(0 until 2, ::) = 0.0
    assertEqualsDouble(m(0, 0), 0.0, 1e-10, "m(0,0)")
    assertEqualsDouble(m(0, 2), 0.0, 1e-10, "m(0,2)")
    assertEqualsDouble(m(1, 1), 0.0, 1e-10, "m(1,1)")
    assertEqualsDouble(m(2, 0), 7.0, 1e-10, "m(2,0) unchanged")
  }

  test("all rows range cols scalar assignment") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    m(::, 1 until 3) = 0.0
    assertEqualsDouble(m(0, 0), 1.0, 1e-10, "m(0,0) unchanged")
    assertEqualsDouble(m(0, 1), 0.0, 1e-10, "m(0,1)")
    assertEqualsDouble(m(0, 2), 0.0, 1e-10, "m(0,2)")
    assertEqualsDouble(m(2, 1), 0.0, 1e-10, "m(2,1)")
  }

  test("range rows range cols scalar assignment") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    m(0 until 2, 1 until 3) = 99.0
    assertEqualsDouble(m(0, 1), 99.0, 1e-10, "m(0,1)")
    assertEqualsDouble(m(0, 2), 99.0, 1e-10, "m(0,2)")
    assertEqualsDouble(m(1, 1), 99.0, 1e-10, "m(1,1)")
    assertEqualsDouble(m(0, 0),  1.0, 1e-10, "m(0,0) unchanged")
    assertEqualsDouble(m(2, 0),  7.0, 1e-10, "m(2,0) unchanged")
  }

  test("step range assignment") {
    val m = Mat.zeros[Double](6, 2)
    m(0 until 6 by 2, ::) = 1.0
    assertEqualsDouble(m(0, 0), 1.0, 1e-10, "row 0")
    assertEqualsDouble(m(1, 0), 0.0, 1e-10, "row 1 unchanged")
    assertEqualsDouble(m(2, 0), 1.0, 1e-10, "row 2")
    assertEqualsDouble(m(3, 0), 0.0, 1e-10, "row 3 unchanged")
    assertEqualsDouble(m(4, 0), 1.0, 1e-10, "row 4")
    assertEqualsDouble(m(5, 0), 0.0, 1e-10, "row 5 unchanged")
  }

  test("range rows all cols Mat assignment") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val src = Mat[Double]((10, 20, 30), (40, 50, 60))
    m(0 until 2, ::) = src
    assertEqualsDouble(m(0, 0), 10.0, 1e-10, "m(0,0)")
    assertEqualsDouble(m(0, 2), 30.0, 1e-10, "m(0,2)")
    assertEqualsDouble(m(1, 1), 50.0, 1e-10, "m(1,1)")
    assertEqualsDouble(m(2, 0),  7.0, 1e-10, "m(2,0) unchanged")
  }

  test("all rows range cols Mat assignment") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val src = Mat[Double]((20, 30), (50, 60))
    m(::, 1 until 3) = src
    assertEqualsDouble(m(0, 0),  1.0, 1e-10, "m(0,0) unchanged")
    assertEqualsDouble(m(0, 1), 20.0, 1e-10, "m(0,1)")
    assertEqualsDouble(m(0, 2), 30.0, 1e-10, "m(0,2)")
    assertEqualsDouble(m(1, 1), 50.0, 1e-10, "m(1,1)")
  }

  test("range rows range cols Mat assignment") {
    val m = Mat.zeros[Double](4, 4)
    val src = Mat[Double]((1, 2), (3, 4))
    m(1 until 3, 1 until 3) = src
    assertEqualsDouble(m(1, 1), 1.0, 1e-10, "m(1,1)")
    assertEqualsDouble(m(1, 2), 2.0, 1e-10, "m(1,2)")
    assertEqualsDouble(m(2, 1), 3.0, 1e-10, "m(2,1)")
    assertEqualsDouble(m(2, 2), 4.0, 1e-10, "m(2,2)")
    assertEqualsDouble(m(0, 0), 0.0, 1e-10, "m(0,0) unchanged")
    assertEqualsDouble(m(3, 3), 0.0, 1e-10, "m(3,3) unchanged")
  }

  test("single row range cols assignment") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    m(0, 1 until 3) = 0.0
    assertEqualsDouble(m(0, 0), 1.0, 1e-10, "m(0,0) unchanged")
    assertEqualsDouble(m(0, 1), 0.0, 1e-10, "m(0,1)")
    assertEqualsDouble(m(0, 2), 0.0, 1e-10, "m(0,2)")
    assertEqualsDouble(m(1, 1), 5.0, 1e-10, "m(1,1) unchanged")
  }

  test("range rows single col assignment") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    m(0 until 2, 2) = 0.0
    assertEqualsDouble(m(0, 2), 0.0, 1e-10, "m(0,2)")
    assertEqualsDouble(m(1, 2), 0.0, 1e-10, "m(1,2)")
    assertEqualsDouble(m(2, 2), 9.0, 1e-10, "m(2,2) unchanged")
  }

  test("slice assignment shape mismatch throws") {
    val m   = Mat[Double]((1, 2, 3), (4, 5, 6), (7, 8, 9))
    val src = Mat[Double]((1, 2), (3, 4))  // wrong shape
    intercept[IllegalArgumentException] { m(0 until 2, ::) = src }
  }

  test("Layout Guard: transpose of a slice should not crash") {
    // 1. Create base Mat
    val base: Mat[Double] = Mat.create(Array(
      1.0, 2.0, 3.0,
      4.0, 5.0, 6.0,
      7.0, 8.0, 9.0
    ), 3, 3)

    // 2. Manually construct a "Slice" typed as Mat
    // Since MatData is private[data], this works if the test is in the same package
    // or if you use a helper in the Mat object.
    // Use the internal test-factory instead of 'new MatData'
    val sliced = Mat.createTestView(base.underlying, 3, 2, false, 1, 3, 1)
    
    // 3. Use the extension method .transpose (works because it's typed as Mat)
    val transposedSlice = sliced.transpose 
    
    // LOGGING: Add this to see what's actually happening
    println(s"Strides: rs=${transposedSlice.rs}, cs=${transposedSlice.cs}, offset=${transposedSlice.offset}")
    val guarded = Mat.create(
      transposedSlice.data, 
      transposedSlice.rows, 
      transposedSlice.cols, 
      transposedSlice.transposed,
      transposedSlice.offset,
      transposedSlice.rs,
      transposedSlice.cs,
    )

    // 5. Verification
    assert(guarded.isStandardContiguous, "Layout Guard failed to normalize a weird layout!")
    assert(guarded(0, 0) == 2.0)
    assert(guarded(1, 0) == 3.0) 
  }
}

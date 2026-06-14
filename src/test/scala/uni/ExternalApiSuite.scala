package uni

// Simulates client code: outside uni.data, only import uni.data.*
import uni.data.*

class ExternalApiSuite extends munit.FunSuite:

  // Diagnostic: rvecUniqueTest exists ONLY in VecExts.scala (no companion counterpart).
  // If this compiles, package-level extensions ARE visible cross-package.
  test("cross-package: rvecUniqueTest from VecExts.scala is accessible") {
    val rv: RVecD = RVec(1.0, 2.0, 3.0)
    val result: RVecD = rv.rvecUniqueTest(2.0)
    assertEquals(result(0), 2.0)
  }

  test("chain: y.T *@ X *@ y = Double") {
    val y: CVecD = CVec(1.0, 2.0, 3.0)
    val X: MatD = MatD((2,0,0),(0,3,0),(0,0,4))
    val q: Double = y.T *@ X *@ y
    assertEquals(q, 50.0)
  }

  // The MatDOps facade re-supplies the COMPLETE apply/update family on Mat[Double]
  // (a single Mat[Double] overload shadows ALL generics). These exercise each
  // category from external scope so a missing/incorrect re-supplied overload — or a
  // dispatch regression where the facade stops winning for `import uni.data.*` — fails
  // to compile or fails here, not silently at a client.
  test("Mat[Double] apply family reachable + correct from external scope") {
    val m: MatD = MatD((1.0, 2.0, 3.0), (4.0, 5.0, 6.0)) // 2x3
    assertEquals(m(0, 0), 1.0)                              // scalar
    assertEquals(m(-1, -1), 6.0)                            // negative indexing
    intercept[IllegalArgumentException](m(9, 9))            // bounds check preserved
    assertEquals(m(0, ::).flatten.toSeq, Seq(1.0, 2.0, 3.0))   // row slice -> RVecD
    assertEquals(m(::, 1).flatten.toSeq, Seq(2.0, 5.0))        // col slice -> CVecD
    assertEquals(m(0 to 1, 1 to 2).flatten.toSeq, Seq(2.0, 3.0, 5.0, 6.0)) // range x range
    assertEquals(m(Array(1, 0), ::).flatten.toSeq,             // fancy row index
      Seq(4.0, 5.0, 6.0, 1.0, 2.0, 3.0))
    val mask = m.gt(3.0)
    assertEquals(m(mask).flatten.toSeq, Seq(4.0, 5.0, 6.0))    // boolean mask
  }

  test("Mat[Double] update family reachable + correct from external scope") {
    val m: MatD = MatD((1.0, 2.0, 3.0), (4.0, 5.0, 6.0))
    m(1, 2) = 99.0                                          // scalar update
    assertEquals(m(1, 2), 99.0)
    m(0, -1) = -1.0                                         // negative-index update
    assertEquals(m(0, 2), -1.0)
    intercept[IllegalArgumentException](m(9, 9) = 0.0)      // bounds check preserved
    m(0, ::) = 7.0                                          // row broadcast value
    assertEquals(m(0, ::).flatten.toSeq, Seq(7.0, 7.0, 7.0))
    m(::, 0) = 5.0                                          // col broadcast value
    assertEquals(m(::, 0).flatten.toSeq, Seq(5.0, 5.0))
    m(1 to 1, ::) = MatD((8.0, 8.0, 8.0))                  // block update from Mat
    assertEquals(m(1, ::).flatten.toSeq, Seq(8.0, 8.0, 8.0))
  }

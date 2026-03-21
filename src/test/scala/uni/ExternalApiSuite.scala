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

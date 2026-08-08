package uni.data
import uni.data.*
// Minimal reproduction of the TprfRunner pattern
object MinimalTest {
  def test(): Unit = {
    val T = 10
    val pf = 0.3
    val f = MatD.zeros(T, 2)
    val fNoise = MatD.randn(T - 1, 2)
    // Step 1: does RVec arithmetic work with explicit types?
    val rv1: RVec[Double] = f(0, ::)
    val rv2: RVec[Double] = fNoise(0, ::)
    // Test: call a unique method that only exists at package level
    @annotation.unused
    val rvNeg: RVec[Double] = -rv1                 // pkgRvecNeg — unique method test
    @annotation.unused
    val rvTest: RVec[Double] = rv1.rvecUniqueTest(pf)  // UNIQUE method — confirms extensions in scope
    @annotation.unused
    val rvAdd: RVec[Double] = rv1 + rv2            // Does RVec + RVec work?
    @annotation.unused
    val rvMul: RVec[Double] = rv1 * pf             // Does RVec * scalar work?
    // Combined expr: break into two steps (Scala 3 doesn't propagate RVec expected
    // type into inner subexpression of chained operators without an intermediate val).
    // The real TprfRunner pattern `f(t,::)=rv*s+rv2` uses Mat[T] expected type which
    // propagates correctly (see line 26 below which compiles fine).
    val rvMulPf: RVec[Double] = rv1 * pf
    val rvExpr: RVec[Double] = rvMulPf + rv2
    // Step 2: does update with explicit RVec work?
    f(1, ::) = rvExpr                               // Does update(Int, ::, RVec) work?
    // Step 3: does the inline form work?
    f(2, ::) = f(1, ::) * pf + fNoise(1, ::)
  }
}

#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
//> using dep org.vastblue:uni_3:0.14.0

import uni.*

// Verifies the v0.14.0 TASTY fix from an EXTERNAL caller compiled against the
// published jar: CVec/RVec factory and extension return types must be the
// opaque vector types (not dealiased Mat[T]), so the typed vals below only
// compile if TASTY signatures are correct.
object TastyCheck {
  def println(s: String = ""): Unit = print(s"$s\n")

  def main(args: Array[String]): Unit = {
    val cv: CVecD = CVec(1.0, 2.0, 3.0)
    val rv: RVecD = cv.T                  // CVec.T must return RVec[T] in TASTY
    val cv2: CVecD = rv.T                 // RVec.T must return CVec[T] in TASTY
    assert(cv2.rows == 3 && cv2.cols == 1)

    val rv2: RVecD = RVec(4.0, 5.0, 6.0)
    val dot: Double = rv2 *@ cv           // RVec *@ CVec → scalar
    assert(dot == 32.0, s"dot=$dot")

    val outer: MatD = cv *@ rv2           // CVec *@ RVec → matrix
    assert(outer.shape == (3, 3))

    val scaled: CVecD = cv * 2.0          // CVec * scalar keeps CVec type
    assert(scaled(2) == 6.0)

    val neg: RVecD = -rv2                 // unary_- keeps RVec type
    assert(neg(0) == -4.0)

    val widened: MatD = cv                // <: Mat[T] upper bound widening
    assert(widened.sum == 6.0)

    assert(cv.show.startsWith("3x1 CVec[Double]:"), cv.show)
    assert(rv2.show.startsWith("1x3 RVec[Double]:"), rv2.show)

    val fm: CVecD = CVec.fromMat(MatD.row(7.0, 8.0))  // row input → transposed view
    assert(fm.rows == 2 && fm(1) == 8.0)

    cv(0) = 10.0                          // CVec update
    assert(cv(0) == 10.0)

    println("tastyCheck OK")
  }
}

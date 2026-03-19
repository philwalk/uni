package uni.data

import uni.data.*
import Mat.*

class CVecSuite extends munit.FunSuite:

  test("CVec.apply creates column vector") {
    val y: CVecD = CVec(1.0, 2.0, 3.0)
    assertEquals(y.size, 3)
    assertEquals(y(0), 1.0)
    assertEquals(y(1), 2.0)
    assertEquals(y(2), 3.0)
  }

  test("RVec.apply creates row vector") {
    val r: RVecD = RVec(1.0, 2.0, 3.0)
    assertEquals(r.size, 3)
    assertEquals(r(0), 1.0)
    assertEquals(r(1), 2.0)
    assertEquals(r(2), 3.0)
  }

  test("CVec.T returns RVec") {
    val y: CVecD = CVec(1.0, 2.0, 3.0)
    val r: RVecD = y.T
    assertEquals(r(0), 1.0)
    assertEquals(r(1), 2.0)
    assertEquals(r(2), 3.0)
  }

  test("RVec.T returns CVec") {
    val r: RVecD = RVec(4.0, 5.0, 6.0)
    val c: CVecD = r.T
    assertEquals(c(0), 4.0)
    assertEquals(c(2), 6.0)
  }

  test("quadratic form: y.T *@ X *@ y = 50.0") {
    val y: CVecD = CVec(1.0, 2.0, 3.0)
    val X: MatD  = MatD((2,0,0),(0,3,0),(0,0,4))
    val q: Double = y.T *@ X *@ y
    assertEquals(q, 50.0)
  }

  test("auto-transpose quadratic form: y *@ X *@ y = 50.0") {
    val y: CVecD = CVec(1.0, 2.0, 3.0)
    val X: MatD  = MatD((2,0,0),(0,3,0),(0,0,4))
    val q2: Double = y *@ X *@ y
    assertEquals(q2, 50.0)
  }

  test("Mat *@ CVec = CVec") {
    val y: CVecD = CVec(1.0, 2.0, 3.0)
    val X: MatD  = MatD((2,0,0),(0,3,0),(0,0,4))
    val Xy: CVecD = X *@ y
    assertEquals(Xy(0), 2.0)
    assertEquals(Xy(1), 6.0)
    assertEquals(Xy(2), 12.0)
  }

  test("RVec *@ Mat = RVec") {
    val y: CVecD = CVec(1.0, 2.0, 3.0)
    val X: MatD  = MatD((2,0,0),(0,3,0),(0,0,4))
    val yTX: RVecD = y.T *@ X
    assertEquals(yTX(0), 2.0)
    assertEquals(yTX(1), 6.0)
    assertEquals(yTX(2), 12.0)
  }

  test("CVec *@ RVec = outer product Mat") {
    val y: CVecD = CVec(1.0, 2.0, 3.0)
    val outer: MatD = y *@ y.T
    assertEquals(outer.shape, (3, 3))
    assertEquals(outer(0, 0), 1.0)
    assertEquals(outer(1, 1), 4.0)
    assertEquals(outer(2, 2), 9.0)
    assertEquals(outer(0, 1), 2.0)
  }

  test("CVec *@ CVec = dot product (auto-transpose)") {
    val y: CVecD = CVec(1.0, 2.0, 3.0)
    val dot: Double = y *@ y
    assertEquals(dot, 14.0)
  }

  test("CVec arithmetic: +, -, *, /") {
    val a: CVecD = CVec(1.0, 2.0, 3.0)
    val b: CVecD = CVec(4.0, 5.0, 6.0)
    val sum  = a + b
    val diff = b - a
    val scaled = a * 2.0
    val divided = b / 2.0
    assertEquals(sum(0), 5.0)
    assertEquals(diff(1), 3.0)
    assertEquals(scaled(2), 6.0)
    assertEquals(divided(0), 2.0)
  }

  test("RVec arithmetic: +, -, *") {
    val a: RVecD = RVec(1.0, 2.0, 3.0)
    val b: RVecD = RVec(4.0, 5.0, 6.0)
    val sum = a + b
    assertEquals(sum(0), 5.0)
    assertEquals(sum(1), 7.0)
  }

  test("CVec.fromMat round-trips n×1") {
    val m: MatD = MatD.col(1.0, 2.0, 3.0)
    val v = CVec.fromMat(m)
    assertEquals(v(0), 1.0)
    assertEquals(v(2), 3.0)
  }

  test("RVec.fromMat round-trips 1×n") {
    val m: MatD = MatD.row(1.0, 2.0, 3.0)
    val rv = RVec.fromMat(m)
    assertEquals(rv(0), 1.0)
    assertEquals(rv(2), 3.0)
  }

  test("scalar * CVec") {
    val v: CVecD = CVec(1.0, 2.0, 3.0)
    val s = 3.0 * v
    assertEquals(s(0), 3.0)
    assertEquals(s(2), 9.0)
  }

  test("scalar * RVec") {
    val rv: RVecD = RVec(1.0, 2.0, 3.0)
    val s = 2.0 * rv
    assertEquals(s(1), 4.0)
  }

  test("CVec.norm") {
    val v: CVecD = CVec(3.0, 4.0)
    assertEquals(v.norm, 5.0)
  }

  test("Mat *@ RVec auto-transposes to CVec") {
    val X: MatD  = MatD((2,0,0),(0,3,0),(0,0,4))
    val rv: RVecD = RVec(1.0, 2.0, 3.0)
    val result: CVecD = X *@ rv
    assertEquals(result(0), 2.0)
    assertEquals(result(1), 6.0)
    assertEquals(result(2), 12.0)
  }

  test("CVec.zeros and CVec.ones factory") {
    val z = CVec.zeros[Double](3)
    val o = CVec.ones[Double](3)
    assertEquals(z(0), 0.0)
    assertEquals(o(1), 1.0)
  }

  test("CVec.show uses CVec label") {
    val v: CVecD = CVec(1.0, 2.0, 3.0)
    assert(v.show.startsWith("3x1 CVec[Double]:"))
  }

  test("RVec.show uses RVec label") {
    val r: RVecD = RVec(1.0, 2.0, 3.0)
    assert(r.show.startsWith("1x3 RVec[Double]:"))
  }

  test("RVec.zeros and RVec.ones factory") {
    val z: RVecD = RVec.zeros[Double](3)
    val o: RVecD = RVec.ones[Double](3)
    assertEquals(z(0), 0.0)
    assertEquals(o(1), 1.0)
  }

  test("RVec.fromArray round-trips") {
    val rv: RVecD = RVec.fromArray(Array(7.0, 8.0, 9.0))
    assertEquals(rv(0), 7.0)
    assertEquals(rv(2), 9.0)
  }

  test("RVec.norm") {
    val r: RVecD = RVec(3.0, 4.0)
    assertEquals(r.norm, 5.0)
  }

  test("RVec *@ RVec = dot product (auto-transpose)") {
    val a: RVecD = RVec(1.0, 2.0, 3.0)
    val b: RVecD = RVec(4.0, 5.0, 6.0)
    val dot: Double = a *@ b
    assertEquals(dot, 32.0)
  }

  test("CVec + scalar and CVec - scalar") {
    val v: CVecD = CVec(1.0, 2.0, 3.0)
    val vp = v + 10.0
    val vm = v - 1.0
    assertEquals(vp(0), 11.0)
    assertEquals(vp(2), 13.0)
    assertEquals(vm(1), 1.0)
  }

  test("RVec + scalar and RVec - scalar") {
    val r: RVecD = RVec(1.0, 2.0, 3.0)
    val rp = r + 10.0
    val rm = r - 1.0
    assertEquals(rp(0), 11.0)
    assertEquals(rm(2), 2.0)
  }

  test("Int * CVec and Long * CVec") {
    val v: CVecD = CVec(1.0, 2.0, 3.0)
    val si = 2 * v
    val sl = 3L * v
    assertEquals(si(1), 4.0)
    assertEquals(sl(2), 9.0)
  }

  test("Int * RVec and Long * RVec") {
    val r: RVecD = RVec(1.0, 2.0, 3.0)
    val si = 2 * r
    val sl = 3L * r
    assertEquals(si(1), 4.0)
    assertEquals(sl(2), 9.0)
  }

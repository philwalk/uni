package uni.data

import munit.FunSuite
import uni.data.*
import Mat.*
import uni.data.Big.*

/** Coverage suite for Mat methods that are absent from existing test files.
 *  Targets: trig functions, math/rounding, signal processing, stats,
 *  cumsum, tril/triu, kron, cross, eig, pinv, cholesky, unique, mapParallel,
 *  filterRows, ravel, unary_-, toRowVec/toColVec, convolve, correlate,
 *  polyfit/polyval, meshgrid. */
class MatCoverageSuite extends FunSuite {

  // ============================================================================
  // Trig: sin / cos / tan
  // ============================================================================

  test("sin element-wise on row vector") {
    val m = MatD.row(0.0, math.Pi / 2, math.Pi)
    val s = m.sin
    assertEquals(s.shape, (1, 3))
    assertEqualsDouble(s(0, 0), 0.0, 1e-10)
    assertEqualsDouble(s(0, 1), 1.0, 1e-10)
    assertEqualsDouble(s(0, 2), 0.0, 1e-10)
  }

  test("cos element-wise on row vector") {
    val m = MatD.row(0.0, math.Pi / 2, math.Pi)
    val c = m.cos
    assertEqualsDouble(c(0, 0), 1.0, 1e-10)
    assertEqualsDouble(c(0, 1), 0.0, 1e-10)
    assertEqualsDouble(c(0, 2), -1.0, 1e-10)
  }

  test("tan element-wise on row vector") {
    val m = MatD.row(0.0, math.Pi / 4)
    val t = m.tan
    assertEqualsDouble(t(0, 0), 0.0, 1e-10)
    assertEqualsDouble(t(0, 1), 1.0, 1e-10)
  }

  // ============================================================================
  // Trig: arcsin / arccos / arctan
  // ============================================================================

  test("arcsin element-wise") {
    val m = MatD.row(0.0, 1.0, -1.0)
    val r = m.arcsin
    assertEqualsDouble(r(0, 0), 0.0, 1e-10)
    assertEqualsDouble(r(0, 1), math.Pi / 2, 1e-10)
    assertEqualsDouble(r(0, 2), -math.Pi / 2, 1e-10)
  }

  test("arccos element-wise") {
    val m = MatD.row(1.0, 0.0, -1.0)
    val r = m.arccos
    assertEqualsDouble(r(0, 0), 0.0, 1e-10)
    assertEqualsDouble(r(0, 1), math.Pi / 2, 1e-10)
    assertEqualsDouble(r(0, 2), math.Pi, 1e-10)
  }

  test("arctan element-wise") {
    val m = MatD.row(0.0, 1.0, -1.0)
    val r = m.arctan
    assertEqualsDouble(r(0, 0), 0.0, 1e-10)
    assertEqualsDouble(r(0, 1), math.Pi / 4, 1e-10)
    assertEqualsDouble(r(0, 2), -math.Pi / 4, 1e-10)
  }

  // ============================================================================
  // Hyperbolic: sinh / cosh / tanh
  // ============================================================================

  test("sinh element-wise") {
    val m = MatD.row(0.0, 1.0)
    val r = m.sinh
    assertEqualsDouble(r(0, 0), 0.0, 1e-10)
    assertEqualsDouble(r(0, 1), math.sinh(1.0), 1e-10)
  }

  test("cosh element-wise") {
    val m = MatD.row(0.0, 1.0)
    val r = m.cosh
    assertEqualsDouble(r(0, 0), 1.0, 1e-10)
    assertEqualsDouble(r(0, 1), math.cosh(1.0), 1e-10)
  }

  test("tanh element-wise") {
    val m = MatD.row(0.0, 1.0)
    val r = m.tanh
    assertEqualsDouble(r(0, 0), 0.0, 1e-10)
    assertEqualsDouble(r(0, 1), math.tanh(1.0), 1e-10)
  }

  // ============================================================================
  // Math / rounding: floor / ceil / round / sign / log10
  // ============================================================================

  test("floor rounds down") {
    val m = MatD.row(1.7, -1.7, 2.0)
    val f = m.floor
    assertEqualsDouble(f(0, 0), 1.0, 1e-12)
    assertEqualsDouble(f(0, 1), -2.0, 1e-12)
    assertEqualsDouble(f(0, 2), 2.0, 1e-12)
  }

  test("ceil rounds up") {
    val m = MatD.row(1.2, -1.2, 3.0)
    val c = m.ceil
    assertEqualsDouble(c(0, 0), 2.0, 1e-12)
    assertEqualsDouble(c(0, 1), -1.0, 1e-12)
    assertEqualsDouble(c(0, 2), 3.0, 1e-12)
  }

  test("round with decimals=0 rounds to integer") {
    val m = MatD.row(1.5, 2.4, -1.5)
    val r = m.round(0)
    assertEqualsDouble(r(0, 0), 2.0, 1e-12)
    assertEqualsDouble(r(0, 1), 2.0, 1e-12)
  }

  test("round with decimals=2 rounds to 2 places") {
    val m = MatD.row(3.14159, 2.71828)
    val r = m.round(2)
    assertEqualsDouble(r(0, 0), 3.14, 1e-12)
    assertEqualsDouble(r(0, 1), 2.72, 1e-12)
  }

  test("sign returns -1 / 0 / 1") {
    val m = MatD.row(-5.0, 0.0, 3.0)
    val s = m.sign
    assertEqualsDouble(s(0, 0), -1.0, 1e-12)
    assertEqualsDouble(s(0, 1), 0.0, 1e-12)
    assertEqualsDouble(s(0, 2), 1.0, 1e-12)
  }

  test("log10 element-wise") {
    val m = MatD.row(1.0, 10.0, 100.0)
    val r = m.log10
    assertEqualsDouble(r(0, 0), 0.0, 1e-10)
    assertEqualsDouble(r(0, 1), 1.0, 1e-10)
    assertEqualsDouble(r(0, 2), 2.0, 1e-10)
  }

  // ============================================================================
  // Statistics: std / variance
  // ============================================================================

  test("std (flat) of uniform values") {
    // std([1,2,3,4,5]) = sqrt(2) ≈ 1.4142
    val m = MatD.row(1.0, 2.0, 3.0, 4.0, 5.0)
    assertEqualsDouble(m.std, math.sqrt(2.0), 1e-10)
  }

  test("std(axis=0) column-wise") {
    val m = Mat[Double]((1.0, 2.0), (3.0, 4.0))
    val s = m.std(0)
    assertEquals(s.shape, (1, 2))  // one std per column
    assertEqualsDouble(s(0, 0), 1.0, 1e-10)
    assertEqualsDouble(s(0, 1), 1.0, 1e-10)
  }

  test("std(axis=1) row-wise") {
    val m = Mat[Double]((1.0, 2.0, 3.0), (4.0, 5.0, 6.0))
    val s = m.std(1)
    assertEquals(s.shape, (2, 1))  // one std per row
    assertEqualsDouble(s(0, 0), math.sqrt(2.0 / 3), 1e-10)
  }

  test("variance of constant vector is zero") {
    val m = MatD.row(5.0, 5.0, 5.0)
    assertEqualsDouble(m.variance, 0.0, 1e-12)
  }

  test("variance of simple values") {
    val m = MatD.row(1.0, 2.0, 3.0)
    // mean=2, var = (1+0+1)/3 = 2/3
    assertEqualsDouble(m.variance, 2.0 / 3.0, 1e-10)
  }

  // ============================================================================
  // cumsum: flat (no axis), axis=0, axis=1
  // ============================================================================

  test("cumsum (no axis) flattens and accumulates") {
    val m = Mat[Double]((1.0, 2.0), (3.0, 4.0))
    val cs = m.cumsum
    assertEquals(cs.shape, (1, 4))
    assertEqualsDouble(cs(0, 0), 1.0, 1e-12)
    assertEqualsDouble(cs(0, 1), 3.0, 1e-12)
    assertEqualsDouble(cs(0, 2), 6.0, 1e-12)
    assertEqualsDouble(cs(0, 3), 10.0, 1e-12)
  }

  test("cumsum(axis=0) accumulates down columns") {
    val m = Mat[Double]((1.0, 2.0), (3.0, 4.0), (5.0, 6.0))
    val cs = m.cumsum(0)
    assertEquals(cs.shape, (3, 2))
    assertEqualsDouble(cs(0, 0), 1.0, 1e-12)
    assertEqualsDouble(cs(1, 0), 4.0, 1e-12)
    assertEqualsDouble(cs(2, 0), 9.0, 1e-12)
    assertEqualsDouble(cs(0, 1), 2.0, 1e-12)
    assertEqualsDouble(cs(1, 1), 6.0, 1e-12)
    assertEqualsDouble(cs(2, 1), 12.0, 1e-12)
  }

  test("cumsum(axis=1) accumulates across rows") {
    val m = Mat[Double]((1.0, 2.0, 3.0), (4.0, 5.0, 6.0))
    val cs = m.cumsum(1)
    assertEquals(cs.shape, (2, 3))
    assertEqualsDouble(cs(0, 0), 1.0, 1e-12)
    assertEqualsDouble(cs(0, 1), 3.0, 1e-12)
    assertEqualsDouble(cs(0, 2), 6.0, 1e-12)
    assertEqualsDouble(cs(1, 0), 4.0, 1e-12)
    assertEqualsDouble(cs(1, 2), 15.0, 1e-12)
  }

  // ============================================================================
  // tril / triu (with various k values)
  // ============================================================================

  test("tril(k=0) extracts lower triangle") {
    val m = Mat[Double]((1.0, 2.0, 3.0), (4.0, 5.0, 6.0), (7.0, 8.0, 9.0))
    val l = m.tril()
    assertEquals(l(0, 0), 1.0); assertEquals(l(0, 1), 0.0); assertEquals(l(0, 2), 0.0)
    assertEquals(l(1, 0), 4.0); assertEquals(l(1, 1), 5.0); assertEquals(l(1, 2), 0.0)
    assertEquals(l(2, 0), 7.0); assertEquals(l(2, 1), 8.0); assertEquals(l(2, 2), 9.0)
  }

  test("tril(k=1) includes one superdiagonal") {
    val m = Mat[Double]((1.0, 2.0, 3.0), (4.0, 5.0, 6.0), (7.0, 8.0, 9.0))
    val l = m.tril(1)
    assertEquals(l(0, 0), 1.0); assertEquals(l(0, 1), 2.0); assertEquals(l(0, 2), 0.0)
    assertEquals(l(1, 0), 4.0); assertEquals(l(1, 1), 5.0); assertEquals(l(1, 2), 6.0)
  }

  test("triu(k=0) extracts upper triangle") {
    val m = Mat[Double]((1.0, 2.0, 3.0), (4.0, 5.0, 6.0), (7.0, 8.0, 9.0))
    val u = m.triu()
    assertEquals(u(0, 0), 1.0); assertEquals(u(0, 2), 3.0)
    assertEquals(u(1, 0), 0.0); assertEquals(u(1, 1), 5.0); assertEquals(u(1, 2), 6.0)
    assertEquals(u(2, 0), 0.0); assertEquals(u(2, 1), 0.0); assertEquals(u(2, 2), 9.0)
  }

  test("triu(k=1) excludes main diagonal") {
    val m = Mat[Double]((1.0, 2.0, 3.0), (4.0, 5.0, 6.0), (7.0, 8.0, 9.0))
    val u = m.triu(1)
    assertEquals(u(0, 0), 0.0); assertEquals(u(0, 1), 2.0); assertEquals(u(0, 2), 3.0)
    assertEquals(u(1, 0), 0.0); assertEquals(u(1, 1), 0.0); assertEquals(u(1, 2), 6.0)
    assertEquals(u(2, 0), 0.0); assertEquals(u(2, 1), 0.0); assertEquals(u(2, 2), 0.0)
  }

  // ============================================================================
  // ravel / toRowVec / toColVec / unary_-
  // ============================================================================

  test("ravel returns all elements as 1×n row") {
    val m = Mat[Double]((1.0, 2.0), (3.0, 4.0))
    val r = m.ravel
    assertEquals(r.shape, (1, 4))
    assertEqualsDouble(r(0, 0), 1.0, 1e-12)
    assertEqualsDouble(r(0, 3), 4.0, 1e-12)
  }

  test("toRowVec returns 1×n") {
    val m = MatD.col(1.0, 2.0, 3.0)
    val r = m.toRowVec
    assertEquals(r.shape, (1, 3))
  }

  test("toColVec returns n×1") {
    val m = MatD.row(1.0, 2.0, 3.0)
    val c = m.toColVec
    assertEquals(c.shape, (3, 1))
  }

  test("unary_- negates all elements") {
    val m = Mat[Double]((1.0, -2.0), (3.0, -4.0))
    val neg = -m
    assertEquals(neg.shape, (2, 2))
    assertEqualsDouble(neg(0, 0), -1.0, 1e-12)
    assertEqualsDouble(neg(0, 1), 2.0, 1e-12)
    assertEqualsDouble(neg(1, 0), -3.0, 1e-12)
    assertEqualsDouble(neg(1, 1), 4.0, 1e-12)
  }

  // ============================================================================
  // filterRows
  // ============================================================================

  test("filterRows keeps rows satisfying predicate") {
    val m = Mat[Double]((1.0, 2.0), (3.0, 4.0), (5.0, 6.0))
    val filtered = m.filterRows(row => row(0, 0) > 1.5)
    assertEquals(filtered.shape, (2, 2))
    assertEqualsDouble(filtered(0, 0), 3.0, 1e-12)
    assertEqualsDouble(filtered(1, 0), 5.0, 1e-12)
  }

  test("filterRows returns empty if no rows pass") {
    val m = Mat[Double]((1.0, 2.0), (3.0, 4.0))
    val filtered = m.filterRows(_ => false)
    assert(filtered.isEmpty)
  }

  // ============================================================================
  // mapParallel
  // ============================================================================

  test("mapParallel applies function to all elements") {
    MatD.setSeed(1L)
    val m = MatD.rand(10, 10)
    val doubled = m.mapParallel(_ * 2.0)
    assertEquals(doubled.shape, (10, 10))
    assertEqualsDouble(doubled(0, 0), m(0, 0) * 2.0, 1e-12)
    assertEqualsDouble(doubled(9, 9), m(9, 9) * 2.0, 1e-12)
  }

  // ============================================================================
  // unique
  // ============================================================================

  test("unique returns distinct values and their counts") {
    val m = MatD.row(3.0, 1.0, 2.0, 1.0, 3.0, 3.0)
    val (vals, counts) = m.unique
    assertEquals(vals.toSeq, Seq(1.0, 2.0, 3.0))
    assertEquals(counts.toSeq, Seq(2, 1, 3))
  }

  test("unique on all-equal matrix") {
    val m = MatD.full(2, 3, 7.0)
    val (vals, counts) = m.unique
    assertEquals(vals.length, 1)
    assertEquals(vals(0), 7.0)
    assertEquals(counts(0), 6)
  }

  // ============================================================================
  // cross product
  // ============================================================================

  test("cross product of two 3D unit vectors") {
    val a = MatD.row(1.0, 0.0, 0.0)  // x-hat
    val b = MatD.row(0.0, 1.0, 0.0)  // y-hat
    val c = a.cross(b)
    assertEquals(c.shape, (1, 3))
    assertEqualsDouble(c(0, 0), 0.0, 1e-12)
    assertEqualsDouble(c(0, 1), 0.0, 1e-12)
    assertEqualsDouble(c(0, 2), 1.0, 1e-12)
  }

  test("cross product anti-commutes") {
    val a = MatD.row(1.0, 2.0, 3.0)
    val b = MatD.row(4.0, 5.0, 6.0)
    val ab = a.cross(b)
    val ba = b.cross(a)
    for j <- 0 until 3 do
      assertEqualsDouble(ab(0, j), -ba(0, j), 1e-12)
  }

  test("cross product requires 3D vectors") {
    val a = MatD.row(1.0, 2.0)
    val b = MatD.row(3.0, 4.0)
    intercept[IllegalArgumentException] { a.cross(b) }
  }

  // ============================================================================
  // kron (Kronecker product)
  // ============================================================================

  test("kron of identity × eye is identity-scaled") {
    val a = Mat[Double]((1.0, 0.0), (0.0, 1.0))
    val b = Mat[Double]((2.0, 3.0), (4.0, 5.0))
    val k = a.kron(b)
    assertEquals(k.shape, (4, 4))
    // kron of I₂ × B = block-diagonal with B on diagonal
    assertEqualsDouble(k(0, 0), 2.0, 1e-12)
    assertEqualsDouble(k(0, 1), 3.0, 1e-12)
    assertEqualsDouble(k(0, 2), 0.0, 1e-12)
    assertEqualsDouble(k(2, 2), 2.0, 1e-12)
  }

  // ============================================================================
  // eig (eigenvalues)
  // ============================================================================

  test("eig of diagonal matrix returns diagonal as eigenvalues") {
    val m = Mat[Double]((3.0, 0.0), (0.0, 5.0))
    val (wr, wi, _) = m.eig
    // eigenvalues should be 3 and 5 (in some order), imaginary parts 0
    val evs = wr.sorted
    assertEqualsDouble(evs(0), 3.0, 1e-6)
    assertEqualsDouble(evs(1), 5.0, 1e-6)
    wi.foreach(w => assertEqualsDouble(w, 0.0, 1e-10))
  }

  test("eig throws for non-Double type") {
    val m = MatF.ones(2, 2)
    intercept[UnsupportedOperationException] { m.eig }
  }

  // ============================================================================
  // cholesky
  // ============================================================================

  test("cholesky of positive-definite matrix") {
    // A = [[4,2],[2,3]] is SPD; L = [[2,0],[1,sqrt(2)]]
    val a = Mat[Double]((4.0, 2.0), (2.0, 3.0))
    val l = a.cholesky
    assertEquals(l.shape, (2, 2))
    // Only check the lower-triangular values (LAPACK may leave upper triangle unchanged)
    assertEqualsDouble(l(0, 0), 2.0, 1e-10)
    assertEqualsDouble(l(1, 0), 1.0, 1e-10)
    assertEqualsDouble(l(1, 1), math.sqrt(2.0), 1e-10)
  }

  test("cholesky throws for non-Double type") {
    val m = MatF.full(2, 2, 1.0f)
    intercept[UnsupportedOperationException] { m.cholesky }
  }

  // ============================================================================
  // pinv (Moore-Penrose pseudoinverse)
  // ============================================================================

  test("pinv of square matrix is close to inverse") {
    val a = Mat[Double]((2.0, 1.0), (1.0, 2.0))
    val pi = a.pinv()
    // A * pinv(A) ≈ I
    val prod = a *@ pi
    assertEqualsDouble(prod(0, 0), 1.0, 1e-8)
    assertEqualsDouble(prod(0, 1), 0.0, 1e-8)
    assertEqualsDouble(prod(1, 0), 0.0, 1e-8)
    assertEqualsDouble(prod(1, 1), 1.0, 1e-8)
  }

  // ============================================================================
  // Signal processing: convolve
  // ============================================================================

  test("convolve mode=full") {
    val a = MatD.row(1.0, 2.0, 3.0)
    val b = MatD.row(0.0, 1.0, 0.5)
    val r = Mat.convolve(a, b, "full")
    assertEquals(r.cols, 5)  // na + nb - 1 = 5
    assertEqualsDouble(r(0, 0), 0.0, 1e-12)  // 1*0
    assertEqualsDouble(r(0, 1), 1.0, 1e-12)  // 1*1 + 2*0
    assertEqualsDouble(r(0, 2), 2.5, 1e-12)  // 1*0.5 + 2*1 + 3*0
  }

  test("convolve mode=same") {
    val a = MatD.row(1.0, 2.0, 3.0)
    val b = MatD.row(1.0, 0.0)
    val r = Mat.convolve(a, b, "same")
    assertEquals(r.cols, a.cols)
  }

  test("convolve mode=valid") {
    val a = MatD.row(1.0, 2.0, 3.0)
    val b = MatD.row(1.0, 1.0)
    val r = Mat.convolve(a, b, "valid")
    assertEquals(r.cols, 2)   // na - nb + 1
    assertEqualsDouble(r(0, 0), 3.0, 1e-12)  // 1+2
    assertEqualsDouble(r(0, 1), 5.0, 1e-12)  // 2+3
  }

  test("convolve unknown mode throws") {
    val a = MatD.row(1.0, 2.0)
    val b = MatD.row(1.0, 2.0)
    intercept[IllegalArgumentException] { Mat.convolve(a, b, "bad") }
  }

  // ============================================================================
  // Signal processing: correlate
  // ============================================================================

  test("correlate mode=full") {
    val a = MatD.row(1.0, 2.0, 3.0)
    val b = MatD.row(1.0, 1.0)
    val r = Mat.correlate(a, b, "full")
    assertEquals(r.cols, 4)  // na + nb - 1
  }

  test("correlate mode=same") {
    val a = MatD.row(1.0, 2.0, 3.0)
    val b = MatD.row(1.0, 1.0)
    val r = Mat.correlate(a, b, "same")
    assertEquals(r.cols, a.cols)
  }

  test("correlate mode=valid (default)") {
    val a = MatD.row(1.0, 2.0, 3.0)
    val b = MatD.row(1.0, 1.0)
    val r = Mat.correlate(a, b)   // default is "valid"
    assertEquals(r.cols, 2)       // na - nb + 1
    assertEqualsDouble(r(0, 0), 3.0, 1e-12)   // a[0]*b[0] + a[1]*b[1] = 1+2
    assertEqualsDouble(r(0, 1), 5.0, 1e-12)   // a[1]*b[0] + a[2]*b[1] = 2+3
  }

  test("correlate unknown mode throws") {
    val a = MatD.row(1.0, 2.0)
    val b = MatD.row(1.0, 2.0)
    intercept[IllegalArgumentException] { Mat.correlate(a, b, "bad") }
  }

  // ============================================================================
  // polyfit / polyval
  // ============================================================================

  test("polyfit degree-1 recovers slope and intercept") {
    // y = 2x + 1
    val x = MatD.col(0.0, 1.0, 2.0, 3.0)
    val y = MatD.col(1.0, 3.0, 5.0, 7.0)
    val coeffs = Mat.polyfit(x, y, 1)
    assertEquals(coeffs.shape, (1, 2))
    assertEqualsDouble(coeffs(0, 0), 2.0, 1e-8)   // slope
    assertEqualsDouble(coeffs(0, 1), 1.0, 1e-8)   // intercept
  }

  test("polyval evaluates polynomial at points") {
    // coeffs = [2, 1]  (2x + 1)
    val coeffs = MatD.row(2.0, 1.0)
    val x = MatD.row(0.0, 1.0, 2.0)
    val y = Mat.polyval(coeffs, x)
    assertEqualsDouble(y(0, 0), 1.0, 1e-8)
    assertEqualsDouble(y(0, 1), 3.0, 1e-8)
    assertEqualsDouble(y(0, 2), 5.0, 1e-8)
  }

  test("polyfit degree-0 throws (deg must be >= 1)") {
    val x = MatD.col(0.0, 1.0)
    val y = MatD.col(1.0, 2.0)
    intercept[IllegalArgumentException] { Mat.polyfit(x, y, 0) }
  }

  // ============================================================================
  // meshgrid
  // ============================================================================

  test("meshgrid produces correct shapes") {
    val x = MatD.row(1.0, 2.0, 3.0)
    val y = MatD.col(4.0, 5.0)
    val (xx, yy) = Mat.meshgrid(x, y)
    assertEquals(xx.shape, (2, 3))
    assertEquals(yy.shape, (2, 3))
    // xx broadcasts x values across rows
    assertEqualsDouble(xx(0, 0), 1.0, 1e-12)
    assertEqualsDouble(xx(0, 1), 2.0, 1e-12)
    assertEqualsDouble(xx(1, 0), 1.0, 1e-12)  // same row as above
    // yy broadcasts y values down columns
    assertEqualsDouble(yy(0, 0), 4.0, 1e-12)
    assertEqualsDouble(yy(1, 0), 5.0, 1e-12)
  }

  // ============================================================================
  // diag overloads (rectangular)
  // ============================================================================

  test("diag rectangular with explicit rows/cols") {
    val d = MatD.diag(Array(1.0, 2.0), 3, 4)
    assertEquals(d.shape, (3, 4))
    assertEqualsDouble(d(0, 0), 1.0, 1e-12)
    assertEqualsDouble(d(1, 1), 2.0, 1e-12)
    assertEqualsDouble(d(0, 1), 0.0, 1e-12)
    assertEqualsDouble(d(2, 2), 0.0, 1e-12)  // beyond values.length
  }

  test("diag from column vector") {
    val v = MatD.col(3.0, 4.0)
    val d = MatD.diag(v)
    assertEquals(d.shape, (2, 2))
    assertEqualsDouble(d(0, 0), 3.0, 1e-12)
    assertEqualsDouble(d(1, 1), 4.0, 1e-12)
  }

  // ============================================================================
  // round throws for unsupported type (covers error branch)
  // ============================================================================

  test("round on Big matrix works (BigDecimal branch)") {
    val m = MatB.row(1.567, 2.234)
    val r = m.round(2)
    assertEqualsDouble(r(0, 0).toDouble, 1.57, 1e-10)
  }

  // ============================================================================
  // Float type-dispatch branches
  // ============================================================================

  test("MatF + MatF goes through the non-Double else branch") {
    val a = MatF.row(1.0, 2.0, 3.0)
    val b = MatF.row(4.0, 5.0, 6.0)
    val c = a + b
    assertEquals(c.shape, (1, 3))
    assertEqualsDouble(c(0, 0).toDouble, 5.0, 1e-5)
    assertEqualsDouble(c(0, 1).toDouble, 7.0, 1e-5)
    assertEqualsDouble(c(0, 2).toDouble, 9.0, 1e-5)
  }

  test("MatF.round covers Float dispatch branch") {
    val m = MatF.row(1.5, 2.5)
    val r = m.round(1)
    assertEqualsDouble(r(0, 0).toDouble, 1.5, 1e-4)
    assertEqualsDouble(r(0, 1).toDouble, 2.5, 1e-4)
  }

  test("MatF.power(Double) covers Float dispatch branch") {
    val m = MatF.row(2.0, 3.0)
    val r = m.power(2.0)
    assertEqualsDouble(r(0, 0).toDouble, 4.0, 1e-4)
    assertEqualsDouble(r(0, 1).toDouble, 9.0, 1e-4)
  }

  test("MatF.std covers Float dispatch branch") {
    // [2,4,4,4,5,5,7,9]: mean=5, sumSq=32, variance=4, std=2
    val m = MatF.row(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
    assertEqualsDouble(m.std.toDouble, 2.0, 1e-4)
  }

  // ============================================================================
  // Tier 1: MatF factory methods (linspace, arange, fromSeq, tabulate,
  //         zerosLike, onesLike, fullLike, concatenate)
  // ============================================================================

  test("MatF.linspace produces correct values") {
    val m = MatF.linspace(0.0, 1.0, 5)
    assertEquals(m.rows, 5)  // linspace returns column vector (n, 1)
    assertEqualsDouble(m(0, 0).toDouble, 0.0, 1e-5)
    assertEqualsDouble(m(4, 0).toDouble, 1.0, 1e-5)
  }

  test("MatF.arange (Int stop)") {
    val m = MatF.arange(5)
    assertEquals(m.rows, 5)  // arange returns column vector (n, 1)
    assertEqualsDouble(m(0, 0).toDouble, 0.0, 1e-5)
    assertEqualsDouble(m(4, 0).toDouble, 4.0, 1e-5)
  }

  test("MatF.arange (Int start, stop, step)") {
    val m = MatF.arange(0, 10, 2)
    assertEquals(m.rows, 5)
    assertEqualsDouble(m(2, 0).toDouble, 4.0, 1e-5)
  }

  test("MatF.fromSeq") {
    val m = MatF.fromSeq(Seq(1.0f, 2.0f, 3.0f))
    assertEquals(m.rows, 3)  // fromSeq returns column vector (n, 1)
    assertEqualsDouble(m(1, 0).toDouble, 2.0, 1e-5)
  }

  test("MatF.tabulate") {
    val m = MatF.tabulate(2, 3)((r, c) => (r * 3 + c).toFloat)
    assertEquals(m.shape, (2, 3))
    assertEqualsDouble(m(1, 2).toDouble, 5.0, 1e-5)
  }

  test("MatF.zerosLike") {
    val template = MatF.ones(3, 4)
    val z = MatF.zerosLike(template)
    assertEquals(z.shape, (3, 4))
    assertEqualsDouble(z(0, 0).toDouble, 0.0, 1e-5)
  }

  test("MatF.onesLike") {
    val template = MatF.zeros(2, 5)
    val o = MatF.onesLike(template)
    assertEquals(o.shape, (2, 5))
    assertEqualsDouble(o(1, 3).toDouble, 1.0, 1e-5)
  }

  test("MatF.fullLike") {
    val template = MatF.zeros(2, 3)
    val f = MatF.fullLike(template, 7.0)
    assertEquals(f.shape, (2, 3))
    assertEqualsDouble(f(1, 2).toDouble, 7.0, 1e-4)
  }

  test("MatF.concatenate axis=0") {
    val a = MatF.ones(2, 3)
    val b = MatF.zeros(2, 3)
    val c = MatF.concatenate(Seq(a, b), axis = 0)
    assertEquals(c.shape, (4, 3))
    assertEqualsDouble(c(0, 0).toDouble, 1.0, 1e-5)
    assertEqualsDouble(c(2, 0).toDouble, 0.0, 1e-5)
  }

  // ============================================================================
  // Tier 1: MatB factory methods
  // ============================================================================

  test("MatB.linspace produces correct values") {
    val m = MatB.linspace(0.0, 1.0, 5)
    assertEquals(m.rows, 5)  // linspace returns column vector (n, 1)
    assertEqualsDouble(m(0, 0).toDouble, 0.0, 1e-10)
    assertEqualsDouble(m(4, 0).toDouble, 1.0, 1e-10)
  }

  test("MatB.arange (Int stop)") {
    val m = MatB.arange(5)
    assertEquals(m.rows, 5)  // arange returns column vector (n, 1)
    assertEqualsDouble(m(0, 0).toDouble, 0.0, 1e-10)
    assertEqualsDouble(m(4, 0).toDouble, 4.0, 1e-10)
  }

  test("MatB.arange (Int start, stop, step)") {
    val m = MatB.arange(0, 10, 2)
    assertEquals(m.rows, 5)
    assertEqualsDouble(m(2, 0).toDouble, 4.0, 1e-10)
  }

  test("MatB.fromSeq") {
    val m = MatB.fromSeq(Seq(Big(1), Big(2), Big(3)))
    assertEquals(m.rows, 3)  // fromSeq returns column vector (n, 1)
    assertEqualsDouble(m(1, 0).toDouble, 2.0, 1e-10)
  }

  test("MatB.tabulate") {
    val m = MatB.tabulate(2, 3)((r, c) => Big(r * 3 + c))
    assertEquals(m.shape, (2, 3))
    assertEqualsDouble(m(1, 2).toDouble, 5.0, 1e-10)
  }

  test("MatB.zerosLike") {
    val template = MatB.ones(3, 4)
    val z = MatB.zerosLike(template)
    assertEquals(z.shape, (3, 4))
    assertEqualsDouble(z(0, 0).toDouble, 0.0, 1e-10)
  }

  test("MatB.onesLike") {
    val template = MatB.zeros(2, 5)
    val o = MatB.onesLike(template)
    assertEquals(o.shape, (2, 5))
    assertEqualsDouble(o(1, 3).toDouble, 1.0, 1e-10)
  }

  test("MatB.fullLike") {
    val template = MatB.zeros(2, 3)
    val f = MatB.fullLike(template, 7.0)
    assertEquals(f.shape, (2, 3))
    assertEqualsDouble(f(1, 2).toDouble, 7.0, 1e-10)
  }

  test("MatB.concatenate axis=0") {
    val a = MatB.ones(2, 3)
    val b = MatB.zeros(2, 3)
    val c = MatB.concatenate(Seq(a, b), axis = 0)
    assertEquals(c.shape, (4, 3))
    assertEqualsDouble(c(0, 0).toDouble, 1.0, 1e-10)
    assertEqualsDouble(c(2, 0).toDouble, 0.0, 1e-10)
  }
}

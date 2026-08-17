package uni.data

import munit.FunSuite
import uni.*

/**
 * `LapackNetlib` must be interchangeable with the LAPACKE row-major calls it stands in
 * for on Linux `-Duni.mat.blas=system`. This suite runs it directly on every platform —
 * whatever backend netlib picks here (`F2jLAPACK` where no system LAPACK exists) — and
 * checks its results against the mathematics and against `Mat`'s default (bytedeco)
 * path, so the row/column-major plumbing is proven before Linux ever exercises it.
 */
class LapackNetlibSuite extends FunSuite:

  private def maxAbsDiff(a: Array[Double], b: Array[Double]): Double =
    a.zip(b).map((x, y) => math.abs(x - y)).max

  test("dgeev eigenvalues match bytedeco (sorted) on a random 6x6") {
    MatD.setSeed(7)
    val a = MatD.randn(6, 6)
    val (wr, wi, _) = LapackNetlib.dgeev(6, a.flatten, wantVectors = false)
    // compare complex spectra as sorted (re, im) pairs
    def key(p: (Double, Double)) = (math.round(p._1 * 1e9), math.round(p._2 * 1e9))
    val ours = wr.zip(wi).sortBy(key)
    val (br, bi, _) = a.eig
    val ref = br.zip(bi).sortBy(key)
    ours.zip(ref).foreach { case ((r1, i1), (r2, i2)) =>
      assertEqualsDouble(r1, r2, 1e-9); assertEqualsDouble(i1, i2, 1e-9)
    }
  }

  test("dgeev right eigenvectors satisfy A v = lambda v (symmetric input, real spectrum)") {
    MatD.setSeed(11)
    val r = MatD.randn(5, 5)
    val a = r + r.T
    val (wr, wi, vr) = LapackNetlib.dgeev(5, a.flatten, wantVectors = true)
    assert(wi.forall(_ == 0.0), wi.toList.toString)
    val v = MatD(5, 5, vr)
    val av = a *@ v
    val vl = v *@ MatD.diag(wr)
    assert(maxAbsDiff(av.flatten, vl.flatten) < 1e-9)
    // and the same spectrum as bytedeco
    val (br, _, _) = a.eig
    assertEquals(wr.sorted.map(x => math.round(x * 1e9)).toList, br.sorted.map(x => math.round(x * 1e9)).toList)
  }

  test("dgesdd economy: U S Vt reconstructs A, singular values match bytedeco (tall and wide)") {
    for (m, n) <- List((7, 4), (4, 7), (5, 5)) do
      MatD.setSeed(m * 10 + n)
      val a = MatD.randn(m, n)
      val p = math.min(m, n)
      val (u, s, vt) = LapackNetlib.dgesddEconomy(m, n, a.flatten)
      val um = MatD(m, p, u); val vtm = MatD(p, n, vt)
      val rec = um *@ MatD.diag(s) *@ vtm
      assert(maxAbsDiff(rec.flatten, a.flatten) < 1e-9, s"$m x $n reconstruction")
      val (_, sRef, _) = a.svd
      assert(maxAbsDiff(s, sRef) < 1e-9, s"$m x $n singular values")
      // U orthonormal columns
      val utu = um.T *@ um
      assert(maxAbsDiff(utu.flatten, MatD.eye(p).flatten) < 1e-9, s"$m x $n U orthonormal")
  }

  test("dpotrf lower: L Lt reconstructs A, matches bytedeco cholesky, and rejects indefinite") {
    MatD.setSeed(3)
    val r = MatD.randn(6, 6)
    val a = r *@ r.T + MatD.eye(6) * 6.0
    val buf = a.flatten
    val info = LapackNetlib.dpotrfLower(6, buf)
    assertEquals(info, 0)
    // zero the strict upper triangle, as Mat.cholesky does after LAPACKE
    for i <- 0 until 6; j <- i + 1 until 6 do buf(i * 6 + j) = 0.0
    val l = MatD(6, 6, buf)
    assert(maxAbsDiff((l *@ l.T).flatten, a.flatten) < 1e-9)
    assert(maxAbsDiff(l.flatten, a.cholesky.flatten) < 1e-9)
    val bad = MatD((1.0, 2.0), (2.0, 1.0)).flatten
    assert(LapackNetlib.dpotrfLower(2, bad) > 0)
  }

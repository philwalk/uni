package test.client

// Regression tests for the MatD/NumPy parity friction found converting
// marketSim's measurement layer (2026-08-13).  MUST live outside package
// uni.data: the failures only reproduce through the external dispatch path
// (VecOps re-exports), where Scala 3 commits to the first matching extension
// group — a lone scalar `*` in the CVec block made `cvec * cvec` fail with
// "Found: CVec[Double], Required: Double" while `+`/`-` (both overloads
// co-located) worked.
//
// The other two friction items (1-D `v(0 until k)` slicing and a no-axis
// cummax) proved unimplementable without destabilizing extension dispatch
// (see the dispatch-fragility note in VecExts.scala); the tests below lock in
// their supported idioms instead: `v(0 until k, 0)` and `v.cummax(0)`.

import munit.*
import uni.data.*

class VecParityFrictionSuite extends FunSuite:

  // ── 1. elementwise vec*vec / vec/vec (NumPy: a * b, a / b) ────────────────

  test("CVec * CVec resolves and multiplies elementwise") {
    val a: CVecD = CVec(1.0, 2.0, 3.0)
    val b: CVecD = CVec(4.0, 5.0, 6.0)
    val c: CVecD = a * b
    assertEquals(c.toArray.toSeq, Seq(4.0, 10.0, 18.0))
    assertEquals(c.shape, (3, 1))
  }

  test("CVec / CVec resolves and divides elementwise") {
    val a: CVecD = CVec(4.0, 10.0, 18.0)
    val b: CVecD = CVec(4.0, 5.0, 6.0)
    val c: CVecD = a / b
    assertEquals(c.toArray.toSeq, Seq(1.0, 2.0, 3.0))
  }

  test("RVec * RVec still resolves (pre-existing)") {
    val a: RVecD = RVec(1.0, 2.0, 3.0)
    val b: RVecD = RVec(4.0, 5.0, 6.0)
    assertEquals((a * b).toArray.toSeq, Seq(4.0, 10.0, 18.0))
  }

  test("CVec * scalar and scalar-left forms unaffected") {
    val a: CVecD = CVec(1.0, 2.0, 3.0)
    assertEquals((a * 2.0).toArray.toSeq, Seq(2.0, 4.0, 6.0))
    assertEquals((2 * a).toArray.toSeq, Seq(2.0, 4.0, 6.0))
  }

  test("MatD * MatD elementwise (NumPy's * operator)") {
    val a = MatD((1.0, 2.0), (3.0, 4.0))
    val b = MatD((5.0, 6.0), (7.0, 8.0))
    val c = a * b
    assertEquals(c.toArray.toSeq, Seq(5.0, 12.0, 21.0, 32.0))
  }

  test("CVec op Mat-typed operand (e.g. a slice result) stays a CVec") {
    val a: CVecD = CVec(1.0, 2.0, 3.0)
    val m: MatD  = CVec(4.0, 5.0, 6.0)   // widened via CVec <: Mat
    assertEquals((a - m).toArray.toSeq, Seq(-3.0, -3.0, -3.0))
    assertEquals((a + m).toArray.toSeq, Seq(5.0, 7.0, 9.0))
    assertEquals((a * m).toArray.toSeq, Seq(4.0, 10.0, 18.0))
    assertEquals((a / m).toArray.toSeq, Seq(0.25, 0.4, 0.5))
  }

  // ── 2. 1-D slicing idiom (NumPy: z[:k]) ──────────────────────────────────

  test("CVec 1-D slice idiom: z(0 until k, 0) returns CVecD") {
    val z: CVecD = CVec(10.0, 20.0, 30.0, 40.0, 50.0)
    val head: CVecD = z(0 until 3, 0)
    assertEquals(head.toArray.toSeq, Seq(10.0, 20.0, 30.0))
    assertEquals(head.shape, (3, 1))
    assertEquals(z(2 until 4, 0).toArray.toSeq, Seq(30.0, 40.0))
  }

  test("RVec 1-D slice idiom: r(0, 1 until 3) returns RVecD") {
    val r: RVecD = RVec(10.0, 20.0, 30.0, 40.0)
    val head: RVecD = r(0, 1 until 3)
    assertEquals(head.toArray.toSeq, Seq(20.0, 30.0))
    assertEquals(head.shape, (1, 2))
  }

  // ── 3. cummax idiom (NumPy: np.maximum.accumulate) ────────────────────────

  test("CVec cummax idiom: v.cummax(0) is shape-preserving") {
    val v: CVecD = CVec(1.0, 3.0, 2.0, 5.0, 4.0)
    val cm = v.cummax(0)
    assertEquals(cm.toArray.toSeq, Seq(1.0, 3.0, 3.0, 5.0, 5.0))
    assertEquals(cm.shape, v.shape)
    assertEquals(v.cummin(0).toArray.toSeq, Seq(1.0, 1.0, 1.0, 1.0, 1.0))
  }

  test("RVec cummax idiom: r.cummax(1) accumulates along the row") {
    val r: RVecD = RVec(1.0, 3.0, 2.0)
    assertEquals(r.cummax(1).toArray.toSeq, Seq(1.0, 3.0, 3.0))
    assertEquals(r.cummax(1).shape, (1, 3))
  }

  test("max-drawdown one-liner: 1 - exp(eq - eq.cummax(0)).min stays CVec-typed") {
    // log-equity curve peaks at 0.3 then dips to 0.1: maxDD = 1 - exp(-0.2)
    val eq: CVecD = CVec(0.0, 0.1, 0.3, 0.1, 0.2)
    val dd: CVecD = eq - eq.cummax(0)   // CVec - Mat via voCvecSubMat
    val maxDD = 1.0 - dd.exp.min
    assertEqualsDouble(maxDD, 1.0 - math.exp(-0.2), 1e-12)
  }

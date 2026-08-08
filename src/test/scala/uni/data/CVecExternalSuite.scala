package uni.data

// NOTE: deliberately NO `import Mat.*` — tests the external (library-user) import
// path where CVec/RVec methods are resolved via companion implicit scope, exactly
// as scala-cli scripts and uni.plot resolve them.  The import Mat.* in CVecSuite
// causes matFlatten (explicit import) to win over cvecFlatten (companion scope),
// masking infinite-recursion bugs in the companion extensions.
import uni.data.*

class CVecExternalSuite extends munit.FunSuite:

  // ── CVec ──────────────────────────────────────────────────────────────────

  test("CVec.flatten — direct") {
    val v: CVecD = CVec(1.0, 2.0, 3.0)
    assertEquals(v.flatten.toSeq, Seq(1.0, 2.0, 3.0))
  }

  test("CVec.flatten — from column slice (the plot-package scenario)") {
    val m = MatD((1.0, 4.0), (2.0, 5.0), (3.0, 6.0))
    val col0 = m(::, 0)
    assertEquals(col0.flatten.toSeq, Seq(1.0, 2.0, 3.0))
    assertEquals(m(::, 1).flatten.toSeq, Seq(4.0, 5.0, 6.0))
  }

  test("CVec.toArray") {
    val v: CVecD = CVec(1.0, 2.0, 3.0)
    assertEquals(v.toArray.toSeq, Seq(1.0, 2.0, 3.0))
  }

  test("CVec.norm") {
    val v: CVecD = CVec(3.0, 4.0)
    assertEqualsDouble(v.norm, 5.0, 1e-10)
  }

  test("CVec.reshape") {
    val v: CVecD = CVec(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
    val m = v.reshape(2, 3)
    assertEquals(m.rows, 2)
    assertEquals(m.cols, 3)
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(1, 2), 6.0)
  }

  // ── RVec ──────────────────────────────────────────────────────────────────

  test("RVec.flatten — direct") {
    val rv: RVecD = RVec(4.0, 5.0, 6.0)
    assertEquals(rv.flatten.toSeq, Seq(4.0, 5.0, 6.0))
  }

  test("RVec.flatten — from row slice") {
    val m = MatD((1.0, 2.0, 3.0), (4.0, 5.0, 6.0))
    assertEquals(m(0, ::).flatten.toSeq, Seq(1.0, 2.0, 3.0))
    assertEquals(m(1, ::).flatten.toSeq, Seq(4.0, 5.0, 6.0))
  }

  test("RVec.toArray") {
    val rv: RVecD = RVec(4.0, 5.0, 6.0)
    assertEquals(rv.toArray.toSeq, Seq(4.0, 5.0, 6.0))
  }

  test("RVec.norm") {
    val rv: RVecD = RVec(3.0, 4.0)
    assertEqualsDouble(rv.norm, 5.0, 1e-10)
  }

  test("RVec.reshape") {
    val rv: RVecD = RVec(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
    val m = rv.reshape(3, 2)
    assertEquals(m.rows, 3)
    assertEquals(m.cols, 2)
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(2, 1), 6.0)
  }

  // ── *@ dispatch via import uni.data.* (quadratic form) ───────────────────────

  test("same-package: y.T returns RVecD explicitly") {
    val y: CVecD  = CVec(1.0, 2.0, 3.0)
    val yT: RVecD = y.T
    assertEquals(yT(0), 1.0)
  }

  test("quadratic form: y.T *@ X *@ y = Double (import uni.data.*)") {
    val y: CVecD = CVec(1.0, 2.0, 3.0)
    val X: MatD  = MatD((2,0,0),(0,3,0),(0,0,4))
    val q: Double = y.T *@ X *@ y
    assertEquals(q, 50.0)
  }

  // ── object * export — m(*, ::) must work after `import uni.data.*` ──────────

  def center(data: MatD): MatD =
    val centers = data.mean(axis = 0).T   // cols×1
    data(*, ::) - centers                 // RowsView auto-normalises orientation

  test("center function: data(*, ::) - mean(axis=0).T gives zero column means") {
    val data = MatD((1.0, 2.0, 3.0), (4.0, 5.0, 6.0), (7.0, 8.0, 9.0))
    val c = center(data)
    for j <- 0 until c.cols do
      assertEqualsDouble(c.mean(axis = 0)(0, j), 0.0, 1e-10)
  }

  test("center function: data(*, ::) - mean(axis=0) (no .T) gives same result") {
    val data = MatD((1.0, 2.0, 3.0), (4.0, 5.0, 6.0), (7.0, 8.0, 9.0))
    val c = data(*, ::) - data.mean(axis = 0)
    for j <- 0 until c.cols do
      assertEqualsDouble(c.mean(axis = 0)(0, j), 0.0, 1e-10)
  }

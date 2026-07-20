package uni.data

import munit.FunSuite

/** Array → Mat/CVec/RVec conversions: the guessable spellings, their shapes,
 *  and their copy semantics.
 *
 *  Shape is asserted everywhere, never just compilation. Before the `Array`
 *  overloads existed, `CVec(arr)` compiled fine and produced a 1×1 holding the
 *  array itself — a test that only checked "it compiles" would have passed on
 *  exactly the bug these overloads fix. */
class ArrayConvSuite extends FunSuite:

  private def arr  = Array(1.0, 2.0, 3.0)
  private def arr2 = Array(Array(1.0, 2.0, 3.0), Array(4.0, 5.0, 6.0))

  // ── extension methods ──────────────────────────────────────────────────────

  test("Array.toCVec is a column") {
    val c = arr.toCVec
    assertEquals((c.rows, c.cols), (3, 1))
    assertEquals(c(0, 0), 1.0)
    assertEquals(c(2, 0), 3.0)
  }

  test("Array.toRVec is a row") {
    val r = arr.toRVec
    assertEquals((r.rows, r.cols), (1, 3))
    assertEquals(r(0, 2), 3.0)
  }

  test("Array.toMat defaults to a column") {
    val m = arr.toMat
    assertEquals((m.rows, m.cols), (3, 1))
  }

  test("Array[Array].toMat is row-major") {
    val m = arr2.toMat
    assertEquals((m.rows, m.cols), (2, 3))
    // The distinguishing check: row-major puts 2.0 at (0,1) and 4.0 at (1,0).
    assertEquals(m(0, 1), 2.0)
    assertEquals(m(1, 0), 4.0)
  }

  test("Seq[Array].toMat agrees with Array[Array].toMat") {
    val fromSeq = Vector(arr2(0), arr2(1)).toMat
    val fromArr = arr2.toMat
    assertEquals((fromSeq.rows, fromSeq.cols), (fromArr.rows, fromArr.cols))
    for i <- 0 until 2; j <- 0 until 3 do
      assertEquals(fromSeq(i, j), fromArr(i, j))
  }

  test("Seq[Array].toMat works for List, not just IndexedSeq") {
    val m = List(arr2(0), arr2(1)).toMat
    assertEquals((m.rows, m.cols), (2, 3))
    assertEquals(m(1, 2), 6.0)
  }

  // ── companion apply spellings ──────────────────────────────────────────────

  test("CVec(arr) is a column, not a 1x1 holding the array") {
    val c = CVec(arr)
    assertEquals((c.rows, c.cols), (3, 1))
    assertEquals(c(1, 0), 2.0)
  }

  test("RVec(arr) is a row, not a 1x1 holding the array") {
    val r = RVec(arr)
    assertEquals((r.rows, r.cols), (1, 3))
    assertEquals(r(0, 1), 2.0)
  }

  test("Mat(arr) and Mat(arr2d)") {
    val m1 = Mat(arr)
    assertEquals((m1.rows, m1.cols), (3, 1))
    val m2 = Mat(arr2)
    assertEquals((m2.rows, m2.cols), (2, 3))
    assertEquals(m2(1, 0), 4.0)
  }

  test("MatD(arr) and MatD(arr2d)") {
    val c = MatD(arr)
    assertEquals((c.rows, c.cols), (3, 1))
    val m = MatD(arr2)
    assertEquals((m.rows, m.cols), (2, 3))
    assertEquals(m(0, 1), 2.0)
  }

  test("MatD.fromRows, array and Seq forms") {
    val a = MatD.fromRows(arr2)
    val s = MatD.fromRows(Vector(arr2(0), arr2(1)))
    assertEquals((a.rows, a.cols), (2, 3))
    assertEquals((s.rows, s.cols), (2, 3))
    assertEquals(a(1, 2), 6.0)
    assertEquals(s(1, 2), 6.0)
  }

  // v0.15.0: two Int arguments used to select a (rows, cols) zeros constructor,
  // making Ints mean dimensions at that one arity while meaning values at every
  // other. The overload is now a @compileTimeOnly tombstone — deleting it would
  // have let `MatD(3, 4)` silently become a 2×1 column via the Double varargs.
  // NOTE: there is deliberately no `compileErrors("MatD(3, 4)")` test here.
  // munit's compileErrors is built on scala.compiletime.testing.typeCheckErrors,
  // which runs the TYPER only, whereas @compileTimeOnly is enforced in a later
  // phase (PostTyper) — so the call type-checks and compileErrors returns "".
  // The tombstone is verified instead by a real compile: see
  // src/test/resources/tombstone-check.md. The tests below pin the replacements
  // and the Ints-are-values rule, which are checkable here.

  test("the replacements for MatD(r, c) all work") {
    val z = MatD.zeros(3, 4)
    assertEquals((z.rows, z.cols), (3, 4))
    val e = MatD.empty
    assertEquals((e.rows, e.cols), (0, 0))
    val v = MatD(3.0, 4.0)
    assertEquals((v.rows, v.cols), (2, 1))
  }

  test("Ints mean values at every arity that still compiles") {
    val a = MatD(1, 2, 3)
    assertEquals((a.rows, a.cols), (3, 1))
    assertEquals(a(0, 0), 1.0)
    val b = MatD(1, 2, 3, 4)
    assertEquals((b.rows, b.cols), (4, 1))
    // Mat never had the (Int, Int) overload, so it was already consistent.
    val c = Mat(1, 2, 3)
    assertEquals((c.rows, c.cols), (3, 1))
  }

  test("varargs spellings still work and still mean what they did") {
    val c = CVec(1.0, 2.0, 3.0)
    assertEquals((c.rows, c.cols), (3, 1))
    val r = RVec(1.0, 2.0, 3.0)
    assertEquals((r.rows, r.cols), (1, 3))
    // The scalar 1x1 lift must be unaffected by the Array overloads.
    val s = Mat(42.0)
    assertEquals((s.rows, s.cols), (1, 1))
    assertEquals(s(0, 0), 42.0)
  }

  // ── copy semantics ─────────────────────────────────────────────────────────
  //
  // Mat is mutable (m(r,c) = v writes into the backing array), so the
  // conversions copy: neither direction of mutation may leak.

  test("toCVec copies: source mutation does not reach the matrix") {
    val a = Array(1.0, 2.0, 3.0)
    val c = a.toCVec
    a(0) = 99.0
    assertEquals(c(0, 0), 1.0)
  }

  test("toCVec copies: matrix mutation does not reach the source") {
    val a = Array(1.0, 2.0, 3.0)
    val c = a.toCVec
    c(0, 0) = 99.0
    assertEquals(a(0), 1.0)
  }

  test("2-D toMat copies in both directions") {
    val a = Array(Array(1.0, 2.0), Array(3.0, 4.0))
    val m = a.toMat
    a(0)(0) = 99.0
    assertEquals(m(0, 0), 1.0)
    m(1, 1) = 77.0
    assertEquals(a(1)(1), 4.0)
  }

  test("Mat.wrap aliases in both directions, by design") {
    val a = Array(1.0, 2.0, 3.0)
    val w = Mat.wrap(a, 3, 1)
    a(0) = 99.0
    assertEquals(w(0, 0), 99.0)
    w(1, 0) = 77.0
    assertEquals(a(1), 77.0)
  }

  test("Mat.wrap rejects a length that does not match the shape") {
    intercept[IllegalArgumentException](Mat.wrap(Array(1.0, 2.0, 3.0), 2, 2))
  }

  // ── edges ──────────────────────────────────────────────────────────────────

  test("ragged input is rejected with a message naming the row") {
    val ragged = Array(Array(1.0, 2.0), Array(3.0))
    val e = intercept[IllegalArgumentException](ragged.toMat)
    assert(e.getMessage.contains("row 1"), s"unhelpful message: ${e.getMessage}")
  }

  test("empty input gives an empty Mat") {
    val m = Array.empty[Array[Double]].toMat
    assertEquals((m.rows, m.cols), (0, 0))
    val s = Seq.empty[Array[Double]].toMat
    assertEquals((s.rows, s.cols), (0, 0))
  }

  test("round-trips through the flat representation") {
    assertEquals(arr.toMat.toArray.toSeq, arr.toSeq)
    assertEquals(arr2.toMat.toArray.toSeq, arr2.flatten.toSeq)
  }

  test("non-Double element types still convert") {
    val ints = Array(1, 2, 3)
    val c = ints.toCVec
    assertEquals((c.rows, c.cols), (3, 1))
    assertEquals(c(2, 0), 3)
  }

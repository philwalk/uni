package uni.data

import munit.FunSuite
import uni.data.Big
import uni.data.Big.*

/** Tests for MatD / MatB / MatF facade objects and their type aliases. */
class MatAliasSuite extends FunSuite {

  // ============================================================================
  // MatD
  // ============================================================================

  test("MatD type alias is Mat[Double]") {
    val m: MatD = MatD.zeros(2, 3)
    assertEquals(m.rows, 2)
    assertEquals(m.cols, 3)
    assertEquals(m(0, 0), 0.0)
  }

  test("MatD.ones / eye / full") {
    val o = MatD.ones(2, 2)
    assertEquals(o(0, 0), 1.0)

    val e = MatD.eye(3)
    assertEquals(e(0, 0), 1.0)
    assertEquals(e(0, 1), 0.0)

    val f = MatD.full(2, 2, 5.0)
    assertEquals(f(1, 1), 5.0)
  }

  test("MatD.arange / linspace") {
    val a = MatD.arange(5)
    assertEquals(a.shape, (5, 1))
    assertEquals(a(4, 0), 4.0)

    val l = MatD.linspace(0.0, 1.0, 5)
    assertEquals(l.shape, (5, 1))
    assertEqualsDouble(l(4, 0), 1.0, 1e-12)
  }

  test("MatD.of / row / col / fromSeq / single") {
    val o = MatD.of(1.0, 2.0, 3.0)
    assertEquals(o.shape, (1, 3))
    assertEquals(o(0, 1), 2.0)

    val r = MatD.row(4.0, 5.0)
    assertEquals(r.shape, (1, 2))

    val c = MatD.col(6.0, 7.0)
    assertEquals(c.shape, (2, 1))

    val fs = MatD.fromSeq(Seq(1.0, 2.0, 3.0))
    assertEquals(fs.shape, (3, 1))

    val s = MatD.single(9.0)
    assertEquals(s.shape, (1, 1))
    assertEquals(s(0, 0), 9.0)
  }

  test("MatD.tabulate") {
    val m = MatD.tabulate(2, 3)((i, j) => (i * 3 + j).toDouble)
    assertEquals(m(0, 0), 0.0)
    assertEquals(m(1, 2), 5.0)
  }

  test("MatD.diag") {
    val d = MatD.diag(Array(1.0, 2.0, 3.0))
    assertEquals(d.shape, (3, 3))
    assertEquals(d(0, 0), 1.0)
    assertEquals(d(1, 1), 2.0)
    assertEquals(d(0, 1), 0.0)
  }

  test("MatD.zerosLike / onesLike / fullLike") {
    val src = MatD.full(2, 3, 7.0)
    assertEquals(MatD.zerosLike(src)(0, 0), 0.0)
    assertEquals(MatD.onesLike(src)(0, 0), 1.0)
    assertEquals(MatD.fullLike(src, 3.0)(1, 2), 3.0)
  }

  test("MatD.vstack / hstack / concatenate") {
    val a = MatD.ones(2, 3)
    val b = MatD.zeros(2, 3)
    val vs = MatD.vstack(a, b)
    assertEquals(vs.shape, (4, 3))
    assertEquals(vs(0, 0), 1.0)
    assertEquals(vs(2, 0), 0.0)

    val hs = MatD.hstack(a, b)
    assertEquals(hs.shape, (2, 6))

    val cc = MatD.concatenate(Seq(a, b), axis = 0)
    assertEquals(cc.shape, (4, 3))
  }

  test("MatD.where (mat and scalar overloads)") {
    val eye   = MatD.eye(2)
    val cond  = eye.map(_ == 1.0)   // Mat[Boolean]: true on diagonal
    val wm    = MatD.where(cond, MatD.full(2, 2, 9.0), MatD.zeros(2, 2))
    assertEquals(wm(0, 0), 9.0)
    assertEquals(wm(0, 1), 0.0)

    val ws = MatD.where(cond, 9.0, 0.0)
    assertEquals(ws(0, 0), 9.0)
    assertEquals(ws(0, 1), 0.0)
  }

  test("MatD.setSeed / rand / randn / uniform / normal shapes") {
    MatD.setSeed(42L)
    val r  = MatD.rand(4, 5);   assertEquals(r.shape,  (4, 5))
    val rn = MatD.randn(3, 3);  assertEquals(rn.shape, (3, 3))
    val u  = MatD.uniform(-1.0, 1.0, 2, 4); assertEquals(u.shape, (2, 4))
    val n  = MatD.normal(0.0, 1.0, 3, 2);   assertEquals(n.shape, (3, 2))
  }

  test("MatD.rand values are in [0, 1)") {
    MatD.setSeed(0L)
    val m = MatD.rand(10, 10)
    m.foreach(v => assert(v >= 0.0 && v < 1.0, s"out of range: $v"))
  }

  test("MatD.uniform values are in [low, high)") {
    MatD.setSeed(1L)
    val m = MatD.uniform(-5.0, 5.0, 10, 10)
    m.foreach(v => assert(v >= -5.0 && v < 5.0, s"out of range: $v"))
  }

  test("MatD.randint (scalar and matrix)") {
    MatD.setSeed(7L)
    val i = MatD.randint(0, 10)
    assert(i >= 0 && i < 10, s"out of range: $i")

    val m = MatD.randint(0, 5, 3, 3)
    assertEquals(m.shape, (3, 3))
    m.foreach(v => assert(v >= 0 && v < 5, s"out of range: $v"))
  }

  test("MatD.setSeed produces deterministic rand") {
    MatD.setSeed(123L)
    val a = MatD.rand(2, 2)
    MatD.setSeed(123L)
    val b = MatD.rand(2, 2)
    assertEquals(a(0, 0), b(0, 0))
    assertEquals(a(1, 1), b(1, 1))
  }

  // ============================================================================
  // MatB
  // ============================================================================

  test("MatB type alias is Mat[Big]") {
    val m: MatB = MatB.zeros(2, 3)
    assertEquals(m.rows, 2)
    assertEquals(m.cols, 3)
    assertEquals(m(0, 0), Big(0))
  }

  test("MatB.ones / eye / full") {
    val o = MatB.ones(2, 2)
    assertEquals(o(0, 0), Big(1))

    val e = MatB.eye(3)
    assertEquals(e(0, 0), Big(1))
    assertEquals(e(0, 1), Big(0))

    val f = MatB.full(2, 2, 5.0)
    assertEquals(f(1, 1), Big(5))
  }

  test("MatB.arange / linspace") {
    val a = MatB.arange(4)
    assertEquals(a.shape, (4, 1))
    assertEquals(a(3, 0), Big(3))

    val l = MatB.linspace(0.0, 1.0, 3)
    assertEquals(l.shape, (3, 1))
  }

  test("MatB.of / row / col / fromSeq / single") {
    val o = MatB.of(1.0, 2.0, 3.0)
    assertEquals(o.shape, (1, 3))
    assertEquals(o(0, 1), Big(2))

    val r = MatB.row(4.0, 5.0)
    assertEquals(r.shape, (1, 2))

    val c = MatB.col(6.0, 7.0)
    assertEquals(c.shape, (2, 1))

    val fs = MatB.fromSeq(Seq(Big(1), Big(2)))
    assertEquals(fs.shape, (2, 1))

    val s = MatB.single(9.0)
    assertEquals(s(0, 0), Big(9))
  }

  test("MatB.tabulate") {
    val m = MatB.tabulate(2, 3)((i, j) => Big(i * 3 + j))
    assertEquals(m(0, 0), Big(0))
    assertEquals(m(1, 2), Big(5))
  }

  test("MatB.diag") {
    val d = MatB.diag(Array(Big(1), Big(2), Big(3)))
    assertEquals(d.shape, (3, 3))
    assertEquals(d(1, 1), Big(2))
    assertEquals(d(0, 1), Big(0))
  }

  test("MatB.zerosLike / onesLike / fullLike") {
    val src = MatB.full(2, 3, 7.0)
    assertEquals(MatB.zerosLike(src)(0, 0), Big(0))
    assertEquals(MatB.onesLike(src)(0, 0), Big(1))
  }

  test("MatB.vstack / hstack") {
    val a = MatB.ones(2, 3)
    val b = MatB.zeros(2, 3)
    val vs = MatB.vstack(a, b)
    assertEquals(vs.shape, (4, 3))
    assertEquals(vs(0, 0), Big(1))
    assertEquals(vs(2, 0), Big(0))

    val hs = MatB.hstack(a, b)
    assertEquals(hs.shape, (2, 6))
  }

  test("MatB.setSeed / rand / randn / uniform / normal shapes") {
    MatB.setSeed(42L)
    val r  = MatB.rand(4, 5);   assertEquals(r.shape,  (4, 5))
    val rn = MatB.randn(3, 3);  assertEquals(rn.shape, (3, 3))
    val u  = MatB.uniform(-1.0, 1.0, 2, 4); assertEquals(u.shape, (2, 4))
    val n  = MatB.normal(0.0, 1.0, 3, 2);   assertEquals(n.shape, (3, 2))
  }

  test("MatB.rand values are in [0, 1)") {
    MatB.setSeed(0L)
    val m = MatB.rand(5, 5)
    m.foreach { v =>
      assert(v >= Big(0) && v < Big(1), s"out of range: $v")
    }
  }

  test("MatB.uniform values are in [low, high)") {
    MatB.setSeed(2L)
    val m = MatB.uniform(-5.0, 5.0, 5, 5)
    m.foreach { v =>
      assert(v >= Big(-5) && v < Big(5), s"out of range: $v")
    }
  }

  test("MatB.randint (scalar and matrix)") {
    MatB.setSeed(7L)
    val i = MatB.randint(0, 10)
    assert(i >= 0 && i < 10, s"out of range: $i")

    val m = MatB.randint(0, 5, 3, 3)
    assertEquals(m.shape, (3, 3))
  }

  test("MatB.setSeed produces deterministic rand") {
    MatB.setSeed(99L)
    val a = MatB.rand(2, 2)
    MatB.setSeed(99L)
    val b = MatB.rand(2, 2)
    assertEquals(a(0, 0), b(0, 0))
  }

  // ============================================================================
  // MatF
  // ============================================================================

  test("MatF type alias is Mat[Float]") {
    val m: MatF = MatF.zeros(2, 3)
    assertEquals(m.rows, 2)
    assertEquals(m.cols, 3)
    assertEquals(m(0, 0), 0.0f)
  }

  test("MatF.ones / eye / full") {
    val o = MatF.ones(2, 2)
    assertEquals(o(0, 0), 1.0f)

    val e = MatF.eye(3)
    assertEquals(e(0, 0), 1.0f)
    assertEquals(e(0, 1), 0.0f)

    val f = MatF.full(2, 2, 5.0)
    assertEquals(f(1, 1), 5.0f)
  }

  test("MatF.arange / linspace") {
    val a = MatF.arange(5)
    assertEquals(a.shape, (5, 1))
    assertEquals(a(4, 0), 4.0f)

    val l = MatF.linspace(0.0, 1.0, 3)
    assertEquals(l.shape, (3, 1))
    assertEquals(l(0, 0), 0.0f)
  }

  test("MatF.of / row / col / fromSeq / single") {
    val o = MatF.of(1.0, 2.0, 3.0)
    assertEquals(o.shape, (1, 3))
    assertEquals(o(0, 1), 2.0f)

    val r = MatF.row(4.0, 5.0)
    assertEquals(r.shape, (1, 2))
    assertEquals(r(0, 0), 4.0f)

    val c = MatF.col(6.0, 7.0)
    assertEquals(c.shape, (2, 1))
    assertEquals(c(1, 0), 7.0f)

    val fs = MatF.fromSeq(Seq(1.0f, 2.0f, 3.0f))
    assertEquals(fs.shape, (3, 1))

    val s = MatF.single(9.0)
    assertEquals(s(0, 0), 9.0f)
  }

  test("MatF.tabulate") {
    val m = MatF.tabulate(2, 3)((i, j) => (i * 3 + j).toFloat)
    assertEquals(m(0, 0), 0.0f)
    assertEquals(m(1, 2), 5.0f)
  }

  test("MatF.diag") {
    val d = MatF.diag(Array(1.0f, 2.0f, 3.0f))
    assertEquals(d.shape, (3, 3))
    assertEquals(d(1, 1), 2.0f)
    assertEquals(d(0, 1), 0.0f)
  }

  test("MatF.zerosLike / onesLike / fullLike") {
    val src = MatF.full(2, 3, 7.0)
    assertEquals(MatF.zerosLike(src)(0, 0), 0.0f)
    assertEquals(MatF.onesLike(src)(0, 0), 1.0f)
    assertEquals(MatF.fullLike(src, 3.0)(1, 2), 3.0f)
  }

  test("MatF.vstack / hstack") {
    val a = MatF.ones(2, 3)
    val b = MatF.zeros(2, 3)
    val vs = MatF.vstack(a, b)
    assertEquals(vs.shape, (4, 3))
    assertEquals(vs(0, 0), 1.0f)
    assertEquals(vs(2, 0), 0.0f)

    val hs = MatF.hstack(a, b)
    assertEquals(hs.shape, (2, 6))
  }

  test("MatF.setSeed / rand / randn / uniform / normal shapes") {
    MatF.setSeed(42L)
    val r  = MatF.rand(4, 5);   assertEquals(r.shape,  (4, 5))
    val rn = MatF.randn(3, 3);  assertEquals(rn.shape, (3, 3))
    val u  = MatF.uniform(-1.0, 1.0, 2, 4); assertEquals(u.shape, (2, 4))
    val n  = MatF.normal(0.0, 1.0, 3, 2);   assertEquals(n.shape, (3, 2))
  }

  test("MatF.rand values are Float in [0, 1)") {
    MatF.setSeed(0L)
    val m = MatF.rand(5, 5)
    m.foreach { v =>
      assert(v >= 0.0f && v < 1.0f, s"out of range: $v")
    }
  }

  test("MatF.uniform values are Float in [low, high)") {
    MatF.setSeed(3L)
    val m = MatF.uniform(-5.0, 5.0, 5, 5)
    m.foreach { v =>
      assert(v >= -5.0f && v < 5.0f, s"out of range: $v")
    }
  }

  test("MatF.randint (scalar and matrix)") {
    MatF.setSeed(7L)
    val i = MatF.randint(0, 10)
    assert(i >= 0 && i < 10, s"out of range: $i")

    val m = MatF.randint(0, 5, 3, 3)
    assertEquals(m.shape, (3, 3))
  }

  test("MatF.setSeed produces deterministic rand") {
    MatF.setSeed(55L)
    val a = MatF.rand(2, 2)
    MatF.setSeed(55L)
    val b = MatF.rand(2, 2)
    assertEquals(a(0, 0), b(0, 0))
  }

  test("MatF elements are Float not Double") {
    val m = MatF.rand(2, 2)
    m.foreach { v =>
      assert(v.isInstanceOf[Float], s"expected Float, got ${v.getClass}")
    }
  }
}

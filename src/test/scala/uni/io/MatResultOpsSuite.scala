package uni.io

import munit.FunSuite
import uni.*
import uni.data.*
import uni.io.FileOps.MatResult

/**
 * `matResultOps.groupBy` / `merge`, on the same four-row tables the Rust
 * `upath::matresult` tests use, with the same expected layouts — the two suites are the
 * parity check for Tier 3 phase (f). Aggregated columns come out in HEADER order, not
 * the `Map`'s iteration order.
 */
class MatResultOpsSuite extends FunSuite:

  private def t(headers: Vector[String], rows: Seq[Seq[Double]]): MatResult[Double] =
    MatResult(headers, Mat.create(rows.flatten.toArray, rows.length, headers.length))

  private def row(m: Mat[Double], i: Int): List[Double] = (0 until m.cols).map(j => m(i, j)).toList

  test("groupBy: means in first-appearance order, per-column ops in header order") {
    val x = t(Vector("sector", "price", "vol"),
      Seq(Seq(2.0, 10.0, 1.0), Seq(1.0, 20.0, 2.0), Seq(2.0, 30.0, 3.0), Seq(1.0, 40.0, 4.0)))
    val g = x.groupBy("sector")
    assertEquals(g.headers, Vector("sector", "price_mean", "vol_mean"))
    assertEquals(row(g.mat, 0), List(2.0, 20.0, 2.0))
    assertEquals(row(g.mat, 1), List(1.0, 30.0, 3.0))
    val g2 = x.groupBy("sector", Map("vol" -> AggOp.Sum, "price" -> AggOp.Max))
    assertEquals(g2.headers, Vector("sector", "price_max", "vol_sum"))
    assertEquals(row(g2.mat, 0), List(2.0, 30.0, 4.0))
    val g3 = x.groupBy("sector", Map("price" -> AggOp.Count, "vol" -> AggOp.Std))
    assertEquals(row(g3.mat, 1), List(1.0, 2.0, 1.0))
    intercept[IllegalArgumentException](x.groupBy("sector", Map("nope" -> AggOp.Sum)))
  }

  test("groupBy: more than four aggregated columns still come out in header order") {
    val headers = Vector("k", "a", "b", "c", "d", "e", "f")
    val x = t(headers, Seq(Seq(1.0, 1, 2, 3, 4, 5, 6), Seq(1.0, 2, 3, 4, 5, 6, 7)))
    val g = x.groupBy("k", AggOp.Sum)
    assertEquals(g.headers, Vector("k", "a_sum", "b_sum", "c_sum", "d_sum", "e_sum", "f_sum"))
    assertEquals(row(g.mat, 0), List(1.0, 3.0, 5.0, 7.0, 9.0, 11.0, 13.0))
  }

  test("merge: inner, left, right") {
    val a = t(Vector("id", "p"), Seq(Seq(1.0, 10.0), Seq(2.0, 20.0), Seq(3.0, 30.0)))
    val b = t(Vector("id", "p", "q"), Seq(Seq(2.0, 200.0, 2.5), Seq(4.0, 400.0, 4.5), Seq(2.0, 201.0, 2.6)))
    val inner = a.merge(b, on = "id")
    assertEquals(inner.headers, Vector("id", "p_x", "p_y", "q"))
    assertEquals(inner.mat.rows, 2)
    assertEquals(row(inner.mat, 0), List(2.0, 20.0, 200.0, 2.5))
    assertEquals(row(inner.mat, 1), List(2.0, 20.0, 201.0, 2.6))
    val left = a.merge(b, on = "id", how = JoinType.Left)
    assertEquals(left.mat.rows, 4)
    assert(left.mat(0, 2).isNaN && left.mat(0, 0) == 1.0)
    val right = a.merge(b, on = "id", how = JoinType.Right)
    assertEquals(right.mat.rows, 3)
    assertEquals(right.mat(2, 0), 4.0)
    assert(right.mat(2, 1).isNaN)
  }

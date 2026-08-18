package uni.data

import munit.FunSuite
import uni.*

/**
 * `Mat[Big]` and `Mat[Double]` as each other's second opinion.
 *
 * The two element types run different code — the generic sequential folds in exact
 * decimals versus the chunked `sumD`, the Double fast paths and IEEE arithmetic — for
 * one set of semantics. Every fixture pins each type against ITSELF (the Rust port);
 * this suite pins them against EACH OTHER: on the same inputs, every benchmarked
 * operation must agree to Double's own precision, positions must land on the same
 * cell, and NaN — `Double.NaN` there, `BigNaN` here — must sort, mask, fold and place
 * identically. A divergence here is a semantic difference, not rounding.
 *
 * Tolerance: `rtol 1e-9`. The Double result carries ~1e-13 relative error on these
 * sizes; the decimal one is exact to 34 digits, so anything past 1e-9 is a rule that
 * differs. Known, deliberate exceptions are asserted as such at the bottom.
 */
class MatBigVsDoubleSuite extends FunSuite:

  private val N = 60
  private def dbl(m: Mat[Big]): Mat[Double] = m.map(_.toDouble)
  private def big(m: Mat[Double]): Mat[Big] = m.map(Big(_))

  private val Atol = 1e-12
  private def close(a: Double, b: Double, rtol: Double): Boolean =
    (a.isNaN && b.isNaN) || math.abs(a - b) <= Atol + rtol * math.max(math.abs(a), math.abs(b))

  private def assertAgree(name: String, d: Mat[Double], b: Mat[Big], rtol: Double = 1e-9): Unit =
    val bd = dbl(b)
    assertEquals(bd.shape, d.shape, s"$name shape")
    for i <- 0 until d.rows; j <- 0 until d.cols do
      assert(close(d(i, j), bd(i, j), rtol), s"$name at ($i,$j): Double ${d(i, j)} vs Big ${bd(i, j)}")

  private def assertAgreeScalar(name: String, d: Double, b: Big, rtol: Double = 1e-9): Unit =
    assert(close(d, b.toDouble, rtol), s"$name: Double $d vs Big ${b.toDouble}")

  MatD.setSeed(20260817)
  private val md: Mat[Double] = MatD.randn(N, N)
  private val mb: Mat[Big]    = big(md)
  private val pd: Mat[Double] = md.abs + 1.0          // strictly positive, for log/sqrt
  private val pb: Mat[Big]    = big(pd)
  private val nd: Mat[Double] = Mat.where(md.lt(-1.5), Mat.create(Array.fill(N * N)(Double.NaN), N, N), md)
  private val nb: Mat[Big]    = nd.map(x => if x.isNaN then BigNaN else Big(x))
  private val sd: Mat[Double] = MatD.randn(6, 6) + MatD.eye(6) * 6.0
  private val sb: Mat[Big]    = big(sd)

  test("elementwise: add mul abs sqrt exp log power neg") {
    assertAgree("add", md + md.T, mb + mb.T)
    assertAgree("mul", md * md, mb * mb)
    assertAgree("div", md / pd, mb / pb)
    assertAgree("scalar", md * 2.5 - 1.0, mb * Big("2.5") - Big(1))
    assertAgree("abs", md.abs, mb.abs)
    assertAgree("neg", -md, -mb)
    assertAgree("sqrt", pd.sqrt, pb.sqrt)
    assertAgree("exp", md.exp, mb.exp, rtol = 1e-15)   // both go through Math.exp on the same double
    assertAgree("log", pd.log, pb.log, rtol = 1e-15)
    assertAgree("power", md.power(3), mb.power(3))
    assertAgree("bcast row", md - md.mean(0), mb - mb.mean(0))
  }

  test("reductions: sum mean std variance min max argmin argmax norm") {
    assertAgreeScalar("sum", md.sum, mb.sum)
    assertAgreeScalar("mean", md.mean, mb.mean)
    assertAgreeScalar("std", md.std, mb.std)
    assertAgreeScalar("variance", md.variance, mb.variance)
    assertAgreeScalar("min", md.min, mb.min)
    assertAgreeScalar("max", md.max, mb.max)
    assertEquals(mb.argmin, md.argmin, "argmin")
    assertEquals(mb.argmax, md.argmax, "argmax")
    val row = md.slice(0 until 1, 0 until N)
    assertAgreeScalar("norm", row.norm, big(row).norm)
    assertAgreeScalar("trace", sd.trace, sb.trace)
  }

  test("axis family: sum mean std min max cumsum") {
    for axis <- 0 to 1 do
      assertAgree(s"sum($axis)", md.sum(axis), mb.sum(axis))
      assertAgree(s"mean($axis)", md.mean(axis), mb.mean(axis))
      assertAgree(s"std($axis)", md.std(axis), mb.std(axis))
      assertAgree(s"min($axis)", md.min(axis), mb.min(axis))
      assertAgree(s"max($axis)", md.max(axis), mb.max(axis))
      assertAgree(s"cumsum($axis)", md.cumsum(axis), mb.cumsum(axis))
    assertAgree("cumsum", md.cumsum, mb.cumsum)
  }

  test("products and linear algebra: matmul inverse determinant solve") {
    assertAgree("matmul", md.slice(0 until 20, 0 until N) *@ md.slice(0 until N, 0 until 20),
                          mb.slice(0 until 20, 0 until N) *@ mb.slice(0 until N, 0 until 20))
    assertAgree("matmul view", md.T *@ md.slice(0 until N, 0 until 3), mb.T *@ mb.slice(0 until N, 0 until 3))
    assertAgree("inverse", sd.inverse, sb.inverse)
    assertAgreeScalar("determinant", sd.determinant, sb.determinant)
    val rhs = sd.slice(0 until 6, 0 until 2)
    assertAgree("solve", sd.solve(rhs), sb.solve(big(rhs)))
  }

  test("ordering: sort argsort and the masks") {
    assertAgree("sort", md.sort(), mb.sort())
    assertEquals(mb.argsort().toArray.toList, md.argsort().toArray.toList, "argsort")
    for (name, dm, bm) <- Seq(
        ("gt", md.gt(0.5), mb.gt(Big("0.5"))),
        ("lt", md.lt(-0.5), mb.lt(Big("-0.5"))),
        ("gte", md.gte(0.0), mb.gte(Big(0))),
        ("lte", md.lte(0.0), mb.lte(Big(0))),
      ) do
      assertEquals(bm.toArray.toList, dm.toArray.toList, s"mask $name")
    assertAgree("m(mask)", md(md.gt(0.5)), mb(mb.gt(Big("0.5"))))
  }

  test("NaN travels identically: folds, min/max, argmax, sort, masks, isnan/hasNaN") {
    assert(nd.sum.isNaN && nb.sum == BigNaN, "sum")
    assert(nd.mean.isNaN && nb.mean == BigNaN, "mean")
    assertAgreeScalar("min skips NaN", nd.min, nb.min)
    assert(nd.max.isNaN && nb.max == BigNaN, "max is NaN")
    assertEquals(nb.argmax, nd.argmax, "argmax lands on the same NaN")
    assertEquals(nb.argmin, nd.argmin, "argmin")
    assertAgree("sort with NaN (NaN last)", nd.sort(), nb.sort())
    assertEquals(nb.gt(Big(0)).toArray.toList, nd.gt(0.0).toArray.toList, "gt with NaN: false")
    assertEquals(nb.lte(Big(0)).toArray.toList, nd.lte(0.0).toArray.toList, "lte with NaN: false")
    assertEquals(nb.hasNaN.toArray.toList, nd.isnan.toArray.toList, "hasNaN vs isnan")
    assertEquals(nb.containsNaN, nd.containsNaN)
    assertAgree("matmul with NaN", nd.slice(0 until 4, 0 until N) *@ nd.slice(0 until N, 0 until 4),
                                   nb.slice(0 until 4, 0 until N) *@ nb.slice(0 until N, 0 until 4))
    for axis <- 0 to 1 do
      assertAgree(s"sum($axis) with NaN", nd.sum(axis), nb.sum(axis))
      assertAgree(s"max($axis) with NaN", nd.max(axis), nb.max(axis))
      assertAgree(s"min($axis) with NaN", nd.min(axis), nb.min(axis))
  }

  test("known, deliberate differences") {
    // Big has no signed zero: abs/neg keep -0.0's VALUE (0) but not its bit.
    val z = MatD.row(-0.0, 0.0)
    assertEquals(big(z).abs.toArray.map(_.toDouble).toList, List(0.0, 0.0))
    assertEquals(z.abs.toArray.map(java.lang.Double.doubleToRawLongBits).toList,
                 List(java.lang.Double.doubleToRawLongBits(-0.0), 0L))   // MatD keeps -0.0
    // (mean of an empty matrix is defined as zero for BOTH -- not a difference.)
    assertEquals(MatD.zeros(0, 3).mean, 0.0)
    assertEquals(MatB.zeros(0, 3).mean, Big(0))
    // Double masks are IEEE (-0.0 == 0.0 and NaN != NaN); Big equality recognises the sentinel.
    assertEquals((MatD.row(Double.NaN) :== Double.NaN).toArray.toList, List(false))
    assertEquals((MatB.row(BigNaN) :== BigNaN).toArray.toList, List(true))
  }

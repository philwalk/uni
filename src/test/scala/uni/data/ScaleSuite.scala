package uni.data

import munit.FunSuite

/** `Mat.scale(center, doScale)`.
 *
 *  The divisor is the root-mean-square of the (possibly centered) values with
 *  Bessel's correction, sqrt(Σc²/(n−1)). Centered, that is the sample std;
 *  uncentered, it is the raw second moment. Before v0.15.0 the uncentered case
 *  divided by the CENTERED std, which mismatched the statistic to the data. */
class ScaleSuite extends FunSuite:

  private val tol = 1e-9

  // column 0: [10,30,50,70,90]   mean 50, sample sd sqrt(1000) = 31.6227766
  //                             uncentered rms sqrt(16500/4) = 64.2261629
  // column 1: [1,2,3,4,5]        mean 3,  sample sd sqrt(2.5) = 1.5811388
  //                             uncentered rms sqrt(55/4)     = 3.7080992
  private def m = MatD(5, 2, Array(
    10.0, 1.0,
    30.0, 2.0,
    50.0, 3.0,
    70.0, 4.0,
    90.0, 5.0))

  private def col(x: MatD, j: Int): Seq[Double] = (0 until x.rows).map(i => x(i, j))

  test("center + scale: columns get mean 0 and sample sd 1") {
    val s = m.scale(true, true)
    for j <- 0 until 2 do
      val c = col(s, j)
      val mu = c.sum / c.length
      assertEqualsDouble(mu, 0.0, tol)
      val sd = math.sqrt(c.map(v => (v - mu) * (v - mu)).sum / (c.length - 1))
      assertEqualsDouble(sd, 1.0, tol)
  }

  test("center only: subtracts the column mean, no scaling") {
    val s = m.scale(true, false)
    assertEqualsDouble(s(0, 0), -40.0, tol)
    assertEqualsDouble(s(4, 0), 40.0, tol)
    assertEqualsDouble(s(0, 1), -2.0, tol)
  }

  // The corrected case. Uncentered, the divisor must be the raw second moment.
  test("scale without centering: columns get unit RMS, not unit sd") {
    val s = m.scale(false, true)
    for j <- 0 until 2 do
      val c = col(s, j)
      val rms = math.sqrt(c.map(v => v * v).sum / (c.length - 1))
      assertEqualsDouble(rms, 1.0, tol)
    // explicit values: 10/sqrt(4125) and 1/sqrt(55/4)
    assertEqualsDouble(s(0, 0), 10.0 / math.sqrt(4125.0), tol)
    assertEqualsDouble(s(0, 1), 1.0 / math.sqrt(55.0 / 4.0), tol)
  }

  test("uncentered scaling does NOT divide by the centered std") {
    val s = m.scale(false, true)
    val wrong = 10.0 / math.sqrt(1000.0)   // the pre-v0.15.0 result, 0.3162…
    assert(math.abs(s(0, 0) - wrong) > 1e-3,
      s"still dividing by the centered std: got ${s(0, 0)}")
  }

  // Why it matters: with mean >> sd the centered-std divisor is unbounded.
  test("uncentered scaling stays bounded when the mean dwarfs the variation") {
    val tight = MatD(3, 1, Array(999.999, 1000.0, 1000.001))
    val s = tight.scale(false, true)
    for i <- 0 until 3 do
      assert(math.abs(s(i, 0)) < 10.0,
        s"scaled value ${s(i, 0)} is not O(1) — divisor is measuring variation, not level")
    // For contrast, the centered std here is ~1e-3, which would give ~1e6.
    val centeredSd = math.sqrt(((-0.001 * -0.001) + 0.0 + (0.001 * 0.001)) / 2.0)
    assert(999.999 / centeredSd > 1e5, "sanity: the old divisor really was that small")
  }

  test("centered RMS equals the sample std, so the two rules coincide") {
    val centered = m.scale(true, false)
    val viaScale = m.scale(true, true)
    for j <- 0 until 2 do
      val c = col(centered, j)
      val sd = math.sqrt(c.map(v => v * v).sum / (c.length - 1))
      for i <- 0 until 5 do
        assertEqualsDouble(viaScale(i, j), c(i) / sd, tol)
  }

  test("no scaling and no centering is the identity") {
    val s = m.scale(false, false)
    for i <- 0 until 5; j <- 0 until 2 do
      assertEqualsDouble(s(i, j), m(i, j), tol)
  }

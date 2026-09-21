package uni.apps

import munit.FunSuite
import uni.data.NumPyRNG

/** THE SKEWED BODY (`noiseSkew`, item 29): the shock is mean zero and unit variance with its median
  * right of zero and its long tail on the left, and on the outgoing Nasdaq recipe it moves the
  * up-day share.  The Rust twin's `noise_skew_tests` makes the same checks. */
class NoiseSkewSuite extends FunSuite:
  test("the skewed shock is a unit shock with its long tail on the left") {
    val d = 0.9
    val a = math.sqrt(1.0 - d * d)
    val b = math.sqrt(1.0 - 2.0 * d * d / math.Pi)
    val rng = new NumPyRNG(20260918L)
    val x = Array.fill(400000) {
      val z = rng.randn()
      MarketSim.skewedShock(z, rng.randn(), d, a, b)
    }
    val n = x.length.toDouble
    val mean = x.sum / n
    val variance = x.map(v => (v - mean) * (v - mean)).sum / n
    val up = x.count(_ > 0.0) / n
    val left = x.count(_ < -2.0) / n
    val right = x.count(_ > 2.0) / n
    assert(math.abs(mean) < 0.005, s"mean $mean")
    assert(math.abs(variance - 1.0) < 0.01, s"variance $variance")
    assert(up > 0.53, s"up share $up")
    assert(left > 3.0 * right, s"tails $left vs $right")
  }

  test("a skewed body moves the up-day share") {
    def up(d: Double): Double =
      val w = MarketSim.namedWorld("0.24.3-nasdaq").get._1.copy(noiseSkew = d)
      MarketSim.measure(MarketSim.simPaths(w, 40, 60, 20260918L), 60).upShare
    val (u0, u9) = (up(0.0), up(0.9))
    assert(u9 > u0 + 0.5, f"up-day share $u0%.2f -> $u9%.2f")
  }

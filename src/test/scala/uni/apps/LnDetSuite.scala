package uni.apps

import munit.FunSuite

/** `lnDet`, the deterministic natural log beside `expDet`: pinned to the bit at the same inputs as
  * the Rust twin's `det_math_tests`, so the two agree by test rather than by inspection. */
class LnDetSuite extends FunSuite:
  // (input, the result's bits): each pair is in both twins' tests, and the Rust twin's results
  // were never read from this one
  val Pinned: Vector[(Double, Long)] = Vector(
    (1.0,                0x0000000000000000L),
    (2.0,                0x3FE62E42FEFA39EFL),
    (2.4,                0x3FEC03D703735F8CL),
    (17.0,               0x4006AA6BC1FA7F7AL),
    (31.0,               0x400B78CE48912B5AL),
    (0.5,                0xBFE62E42FEFA39EFL),
    (1e-300,             0xC085963447F87FB5L),
    (1e300,              0x4085963447F87FB5L),
    (java.lang.Double.MIN_VALUE, 0xC0874385446D71C3L),   // the smallest subnormal
    (1.4142135623730951, 0x3FD62E42FEFA39F2L))

  test("lnDet is pinned to the bit") {
    Pinned.foreach: (x, bits) =>
      val got = java.lang.Double.doubleToRawLongBits(MarketSim.lnDet(x))
      assertEquals(got, bits, f"lnDet($x%s) = 0x$got%016X")
  }

  test("lnDet is within four ulps of the native log and inverts expDet") {
    // over 1 + rate, the search's use, it reads within 3 ulps of libm; the JVM's log may sit one
    // ulp off libm's
    val xs = (1 to 400).map(i => 0.01 * i * i) ++ (0 to 3000).map(i => 1.0 + 0.01 * i)
    xs.foreach: x =>
      val (a, b) = (MarketSim.lnDet(x), math.log(x))
      assert(math.abs(a - b) <= 4.0 * math.ulp(b), f"x $x%s: $a%s vs $b%s")
      assert(math.abs(MarketSim.expDet(a) / x - 1.0) < 1e-14, f"x $x%s")
  }

  test("lnDet's domain") {
    assert(MarketSim.lnDet(0.0).isNaN && MarketSim.lnDet(-1.0).isNaN && MarketSim.lnDet(Double.NaN).isNaN)
    assertEquals(MarketSim.lnDet(Double.PositiveInfinity), Double.PositiveInfinity)
  }

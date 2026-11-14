package uni.apps

import munit.FunSuite
import uni.*
import uni.data.*

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

  private def bitsOf(x: Double): Long = java.lang.Double.doubleToRawLongBits(x)

  /** `lnStrict` holds `x` to `StrictMath.log` to the bit and to the platform log within an ulp. */
  private def assertStrict(x: Double): Unit =
    val (got, want) = (MarketSim.lnStrict(x), StrictMath.log(x))
    if want.isNaN then assert(got.isNaN, s"lnStrict($x) = $got")
    else
      assertEquals(bitsOf(got), bitsOf(want), f"lnStrict($x%s) = 0x${bitsOf(got)}%016X")
      val native = math.log(x)
      if native.isFinite then assert(math.abs(got - native) <= math.ulp(native), s"x $x: $got vs $native")

  test("lnStrict is StrictMath.log to the bit") {
    // SplitMix64: bit patterns over every magnitude, values near 1 from 2^-1 to 2^-52 away, daily
    // price ratios and subnormals
    var state = 0x9E3779B97F4A7C15L
    def next(): Long =
      state += 0x9E3779B97F4A7C15L
      val z1 = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L
      val z2 = (z1 ^ (z1 >>> 27)) * 0x94D049BB133111EBL
      z2 ^ (z2 >>> 31)
    def unit(): Double = (next() >>> 11) * (1.0 / 9007199254740992.0)
    var i = 0
    while i < 1200000 do
      val x = i % 4 match
        case 0 => java.lang.Double.longBitsToDouble(next() & 0x7fffffffffffffffL)
        case 1 => 1.0 + (unit() - 0.5) * java.lang.Math.scalb(1.0, -((i / 4) % 52))
        case 2 => 1.0 + (unit() - 0.5) * 0.08
        case _ => java.lang.Double.longBitsToDouble(next() >>> (12 + (i / 4) % 52))
      assertStrict(x)
      i += 1
  }

  test("lnStrict's domain") {
    assertEquals(MarketSim.lnStrict(0.0), Double.NegativeInfinity)
    assertEquals(MarketSim.lnStrict(-0.0), Double.NegativeInfinity)
    assert(MarketSim.lnStrict(-1.0).isNaN && MarketSim.lnStrict(Double.NegativeInfinity).isNaN)
    assert(MarketSim.lnStrict(-java.lang.Double.MIN_VALUE).isNaN && MarketSim.lnStrict(Double.NaN).isNaN)
    assertEquals(MarketSim.lnStrict(Double.PositiveInfinity), Double.PositiveInfinity)
    assertEquals(bitsOf(MarketSim.lnStrict(1.0)), 0L)
    Vector(java.lang.Double.MIN_VALUE, java.lang.Double.MIN_NORMAL, Double.MaxValue, 2.0, 0.5,
           1.4142135623730951, 0.7071067811865476).foreach(assertStrict)
  }

  test("lnStrict reproduces the shared fixture") {
    // the Rust twin's `det_math_tests` holds `ln_strict` to the same file
    val p = s"${sys.props.getOrElse("user.dir", ".")}/test-data/ln-parity/scala-reference.txt".asPath
    require(p.isFile, s"missing ${p.posx}")
    val pairs = p.lines.iterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).map(_.split(' ')).toVector
    assertEquals(pairs.length, 4000)
    pairs.foreach: f =>
      val x = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(f(0), 16))
      val got = MarketSim.lnStrict(x)
      if f(1) == "nan" then assert(got.isNaN && StrictMath.log(x).isNaN, s"lnStrict($x) = $got")
      else
        val want = java.lang.Long.parseUnsignedLong(f(1), 16)
        assertEquals(bitsOf(got), want, f"lnStrict($x%s) = 0x${bitsOf(got)}%016X")
        assertEquals(bitsOf(StrictMath.log(x)), want, s"the fixture's line for $x is not StrictMath.log's")
  }

  test("expDet is total") {
    // past the normal range, pinned to the same bits in the Rust twin's `det_math_tests`: the
    // subnormals and the last doubles below overflow are the platform exp's, the ends 0 and +inf
    val pinned = Vector(
      (-745.0,                   0x0000000000000001L),
      (-740.0,                   0x0000000000000055L),
      (-708.5,                   0x000E6CF6D08897ACL),
      (709.5,                    0x7FE81E9B4B52D0C9L),
      (709.78,                   0x7FEFE9CE5C4C52B4L),
      (709.79,                   0x7FF0000000000000L),
      (Double.PositiveInfinity,  0x7FF0000000000000L),
      (-746.0,                   0x0000000000000000L),
      (Double.NegativeInfinity,  0x0000000000000000L))
    pinned.foreach: (y, bits) =>
      val got = java.lang.Double.doubleToRawLongBits(MarketSim.expDet(y))
      assertEquals(got, bits, f"expDet($y%s) = 0x$got%016X")
    assert(MarketSim.expDet(Double.NaN).isNaN)
  }

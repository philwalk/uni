package uni.apps

import munit.FunSuite
import uni.*

/**
 * The bar channels' anchors are MEASURED numbers; this re-derives the graded bands and the
 * volume channel's frozen structural constants from the checked-in fixture so the code and the
 * record cannot drift apart.
 *
 * The Rust twin carries the same checks in `bars_anchor_tests`, against the same file.  The
 * `-` rows belong to the python graders over `-barsemit` output.
 */
class BarsAnchorSuite extends FunSuite:

  val Bars = "test-data/equity-anchors/bars-2026-09-01.tsv"

  /** Empty where the fixture is absent, which is a skip and not a failure: the artifact ships
    * without `test-data/`, so a source-tarball build must not fail here. */
  def rows(path: String): Vector[Vector[String]] =
    val p = path.asPath
    if !p.exists then Vector.empty
    else p.lines.toVector
      .filterNot(l => l.startsWith("#") || l.startsWith("pair\t") || l.trim.isEmpty)
      .map(_.split('\t').toVector)

  def value(rs: Vector[Vector[String]], pair: String, stat: String): Double =
    rs.find(r => r(0) == pair && r(1) == stat)
      .getOrElse(fail(s"fixture row [$pair $stat] missing"))(2).toDouble

  def band(rs: Vector[Vector[String]], stat: String): (Double, Double) =
    val r = rs.find(r => r(0) == "w1993" && r(1) == stat)
      .getOrElse(fail(s"fixture row [w1993 $stat] missing"))
    (r(2).toDouble, r(3).toDouble)

  def mean(x: Array[Double]): Double = x.sum / x.length

  def sd(x: Array[Double]): Double =
    val m = mean(x)
    math.sqrt(x.map(v => (v - m) * (v - m)).sum / x.length)

  def corrOf(a: Array[Double], b: Array[Double]): Double =
    val ma = mean(a); val mb = mean(b)
    var caa = 0.0; var cbb = 0.0; var cab = 0.0
    var i = 0
    while i < a.length do
      val da = a(i) - ma; val db = b(i) - mb
      caa += da * da; cbb += db * db; cab += da * db
      i += 1
    cab / math.sqrt(caa * cbb)

  def acf1(x: Array[Double]): Double = corrOf(x.dropRight(1), x.drop(1))

  /** median of four: mean of the middle pair */
  def med4(x: Vector[Double]): Double =
    val s = x.sorted
    (s(1) + s(2)) / 2.0

  test("the volume constants are the fixture rows") {
    val a = rows(Bars)
    assume(a.nonEmpty, "fixture not present in this tree (source tarball?)")
    assertEquals(MarketSim.VolSlope, value(a, "const", "volSlope"))
    assertEquals(MarketSim.VolDown, value(a, "const", "volDown"))
    assertEquals(MarketSim.VolPhi, value(a, "const", "volPhi"))
    assertEquals(MarketSim.VolSlowShare, value(a, "const", "volSlowShare"))
  }

  test("the bar channels sit on their anchors") {
    val a = rows(Bars)
    assume(a.nonEmpty, "fixture not present in this tree (source tarball?)")
    val w = MarketSim.Defaults.copy(rangeScale = 1.1, volIdio = 0.34)
    val sims = MarketSim.simPaths(w, 4, 100, MarketSim.DefaultSeed)
    val perPath = sims.toVector.map { p =>
      val r = Array.tabulate(p.price.length - 1)(i => math.log(p.price(i + 1) / p.price(i)))
      val x = Array.tabulate(p.logHi.length)(i => p.logHi(i) - p.logLo(i))
      // The model's turnover index is trendless, so the record side's rolling-median detrend
      // is a no-op up to noise here; the raw series is graded.
      (mean(x) / sd(r), acf1(x), sd(p.logVolume), corrOf(p.logVolume, x))
    }
    val graded = Vector(
      ("rangeMeanOverCcvol", med4(perPath.map(_._1))),
      ("rangeAcf1", med4(perPath.map(_._2))),
      ("volSd", med4(perPath.map(_._3))),
      ("volCorrRange", med4(perPath.map(_._4))))
    for (stat, got) <- graded do
      val (v, tol) = band(a, stat)
      assert(math.abs(got - v) <= tol,
        f"$stat: model median $got%.3f vs anchor $v%.3f +/- $tol%.2f")
  }

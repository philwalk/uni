package uni.apps

import munit.FunSuite
import uni.*

/**
 * The satellite leg's coupling anchors are MEASURED numbers; this re-derives the graded bands
 * from the checked-in fixture so the anchored dials and the record cannot drift apart.
 *
 * The Rust twin carries the same check in `joint_coupling_tests`, against the same file. The
 * distribution and conditioning rows (`tol` = `-`) belong to the python grader over `-jointemit`
 * output, not to these tests.
 */
class JointCouplingSuite extends FunSuite:

  val Coupling = "test-data/equity-anchors/joint-coupling-2026-08-31.tsv"

  /** Empty where the fixture is absent, which is a skip and not a failure: the artifact ships
    * without `test-data/`, so a source-tarball build must not fail here. */
  def rows(path: String): Vector[Vector[String]] =
    val p = path.asPath
    if !p.exists then Vector.empty
    else p.lines.toVector
      .filterNot(l => l.startsWith("#") || l.startsWith("pair\t") || l.trim.isEmpty)
      .map(_.split('\t').toVector)

  /** (value, tol) of a GRADED w1999 row; a `-` tol marks a row these tests must not consume,
    * and fails loudly if one reaches here. */
  def band(rs: Vector[Vector[String]], stat: String): (Double, Double) =
    val r = rs.find(r => r(0) == "w1999" && r(1) == stat)
      .getOrElse(fail(s"fixture row [w1999 $stat] missing"))
    (r(2).toDouble, r(3).toDouble)

  def simpleRets(px: Array[Double]): Array[Double] =
    Array.tabulate(px.length - 1)(i => px(i + 1) / px(i) - 1.0)

  def mean(x: Array[Double]): Double = x.sum / x.length

  def corrOf(a: Array[Double], b: Array[Double]): Double =
    val ma = mean(a); val mb = mean(b)
    var caa = 0.0; var cbb = 0.0; var cab = 0.0
    var i = 0
    while i < a.length do
      val da = a(i) - ma; val db = b(i) - mb
      caa += da * da; cbb += db * db; cab += da * db
      i += 1
    cab / math.sqrt(caa * cbb)

  def betaOf(sat: Array[Double], pri: Array[Double]): Double =
    val ms = mean(sat); val mp = mean(pri)
    var cpp = 0.0; var csp = 0.0
    var i = 0
    while i < sat.length do
      cpp += (pri(i) - mp) * (pri(i) - mp)
      csp += (sat(i) - ms) * (pri(i) - mp)
      i += 1
    csp / cpp

  def sd(x: Array[Double]): Double =
    val m = mean(x)
    math.sqrt(x.map(v => (v - m) * (v - m)).sum / x.length)

  /** median of four: mean of the middle pair */
  def med4(x: Vector[Double]): Double =
    val s = x.sorted
    (s(1) + s(2)) / 2.0

  test("the satellite leg discriminates and sits on its coupling anchors") {
    val a = rows(Coupling)
    assume(a.nonEmpty, "fixture not present in this tree (source tarball?)")
    val w = MarketSim.Defaults.copy(satBeta = 1.2, satIdio = 0.77)
    val sims = MarketSim.simPaths(w, 4, 100, MarketSim.DefaultSeed)
    val perPath = sims.toVector.map { p =>
      assertEquals(p.sat.length, p.price.length, "satBeta on must fill the satellite")
      val r1 = simpleRets(p.price)
      val r2 = simpleRets(p.sat)
      (corrOf(r1, r2), corrOf(r1.map(math.abs), r2.map(math.abs)), sd(r2) / sd(r1),
       betaOf(r2, r1))
    }
    val graded = Vector(
      ("corr", med4(perPath.map(_._1))),
      ("absCorr", med4(perPath.map(_._2))),
      ("volRatio", med4(perPath.map(_._3))),
      ("beta", med4(perPath.map(_._4))))
    for (stat, got) <- graded do
      val (v, tol) = band(a, stat)
      assert(math.abs(got - v) <= tol,
        f"$stat: model median $got%.3f vs anchor $v%.3f +/- $tol%.2f")
  }

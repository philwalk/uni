package uni.stats

import munit.FunSuite
import uni.*
import uni.data.*

/**
 * Checks Tprf3 against the committed reference in `test-data/tprf3-parity/`.
 *
 * The Rust port (`rust/tests/scala_parity.rs`) checks itself against the same
 * files, so the pair pins both implementations to one set of numbers without
 * either test needing the other language installed. A change that moves Scala's
 * results fails here; a change that moves only Rust's fails there.
 *
 * Regenerate the reference with `sbt "runMain uni.apps.Tprf3ParityGen"` — and
 * only when the values are meant to move.
 */
class Tprf3ParitySuite extends FunSuite:

  private val dir = s"${sys.props.getOrElse("user.dir", ".")}/test-data/tprf3-parity"

  /** Matches the fixtures written by Tprf3ParityGen. */
  private val cases = Seq((100, 10, 2), (140, 12, 3), (60, 25, 2))

  /** Absolute floor plus relative term — a plain relative test is too harsh on
   *  forecasts that land near zero. Comfortably above the ~3e-12 drift seen
   *  between the two implementations, far below anything economically real. */
  private val atol = 1e-12
  private val rtol = 1e-9

  private lazy val reference: Map[(String, String), Double] =
    val p = s"$dir/scala-reference.txt".asPath
    require(p.isFile,
      s"missing ${p.posx} — regenerate with: sbt \"runMain uni.apps.Tprf3ParityGen\"")
    p.lines.iterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .flatMap(_.split("\\s+") match
        case Array(tag, field, v) => Some((tag, field) -> v.toDouble)
        case _                    => None)
      .toMap

  private def close(a: Double, b: Double): Boolean =
    if a.isNaN || b.isNaN then a.isNaN == b.isNaN
    else math.abs(a - b) <= atol + rtol * math.max(math.abs(a), math.abs(b))

  private def cmp(tag: String)(field: String, got: Double): Unit =
    val want = reference.getOrElse((tag, field),
      fail(s"reference has no entry for $tag $field"))
    assert(close(got, want),
      s"$tag $field: got $got, reference $want (|Δ|=${math.abs(got - want)})")

  private def check(tag: String, r: Tprf3.Tprf3Result): Unit =
    val c = cmp(tag)
    c("r2", r.rSquared)
    c("enc", r.encnew)
    for i <- 0 until r.forecasts.rows do c(s"f$i", r.forecasts(i, 0))
    for i <- 0 until r.rollfore.rows do c(s"roll$i", r.rollfore(i, 0))

  /** Closed-form rows carry no enc/rollfore. */
  private def checkClosed(tag: String, r: Tprf3.Tprf3Result): Unit =
    val c = cmp(tag)
    c("r2", r.rSquared)
    for i <- 0 until r.forecasts.rows do c(s"f$i", r.forecasts(i, 0))

  private def checkPls(tag: String, m: Tprf3.Pls3prfModel, x: MatD): Unit =
    val c = cmp(tag)
    c("r2", m.rSquared)
    c("b0", m.beta(0, 0))
    c("b1", m.beta(1, 0))
    for i <- 0 until m.forecasts.rows do c(s"f$i", m.forecasts(i, 0))
    c("pred0", m.predict(Array.tabulate(x.cols)(j => x(0, j))))

  for (t, n, l) <- cases do
    val tag = s"T${t}N${n}L${l}"
    test(s"$tag matches the parity reference"):
      val X = s"$dir/${tag}_X.csv".asPath.loadMatD
      val y = s"$dir/${tag}_y.csv".asPath.loadMatD
      val Z = s"$dir/${tag}_Z.csv".asPath.loadMatD

      check(s"$tag/isfull",
        Tprf3.estimate3prf(y, X, Right(Z), procedure = "IS Full"))
      check(s"$tag/oosrec",
        Tprf3.estimate3prf(y, X, Right(Z), procedure = "OOS Recursive", mintrain = (t / 2, 0)))
      check(s"$tag/ooscv01",
        Tprf3.estimate3prf(y, X, Right(Z), procedure = "OOS Cross Val", window = (0, 1)))
      check(s"$tag/ooscv23",
        Tprf3.estimate3prf(y, X, Right(Z), procedure = "OOS Cross Val", window = (2, 3)))
      checkClosed(s"$tag/closed", Tprf3.tprfClosedForm(y, X, Z))
      checkPls(s"$tag/pls", Tprf3.plsClosedForm(y, X), X)

      // The wrappers are pinned through what they wrap: forecast3prf must return
      // exactly the isfull forecasts, and pls1Fit exactly the plsClosedForm fit.
      val viaWrapper = Tprf3.forecast3prf(y, X, Right(Z))
      val direct     = Tprf3.estimate3prf(y, X, Right(Z), procedure = "IS Full").forecasts
      for i <- 0 until direct.rows do
        assertEquals(viaWrapper(i, 0), direct(i, 0), s"$tag forecast3prf f$i")
      val viaArrays = Tprf3.pls1Fit(
        Array.tabulate(X.rows, X.cols)((i, j) => X(i, j)),
        Array.tabulate(y.rows)(i => y(i, 0)))
      val viaMat    = Tprf3.plsClosedForm(y, X)
      for i <- 0 until viaMat.forecasts.rows do
        assertEquals(viaArrays.forecasts(i, 0), viaMat.forecasts(i, 0), s"$tag pls1Fit f$i")

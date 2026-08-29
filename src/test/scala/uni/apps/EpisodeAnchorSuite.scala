package uni.apps

import munit.FunSuite
import uni.*

/**
 * `medDepth` is a MEASURED number and it was measured wrong once. Through 0.21.0 it shipped as
 * −27.1% with no recorded convention, while the model measures every peak-to-trough decline of 15%
 * or worse; no window of the record produces −27.1% at that threshold and a 20% threshold does. This
 * re-derives the shipped value from the checked-in readings so the anchor and the statistic it is
 * compared against cannot drift apart again.
 *
 * The Rust twin carries the same checks in `episode_anchor_tests`, against the same file.
 */
class EpisodeAnchorSuite extends FunSuite:

  val Fixture = "test-data/equity-anchors/episodes-2026-08-29.tsv"

  case class Row(window: String, thr: Int, n: Int, perCentury: Double, median: Double, worst: Double)

  /** Empty where the fixture is absent, which is a skip and not a failure: a source tarball ships
    * without `test-data/`, and asserting there would fail for no benefit. */
  lazy val rows: Vector[Row] =
    val p = Fixture.asPath
    if !p.exists then Vector.empty
    else
      p.lines.toVector
        .filter(l => !l.startsWith("#") && !l.startsWith("window\t") && l.trim.nonEmpty)
        .map { l =>
          val f = l.split("\t")
          Row(f(0), f(1).toInt, f(2).toInt, f(3).toDouble, f(4).toDouble, f(5).toDouble)
        }

  /** The threshold `episodes` is called with in `measure`. If this moves, the anchor moves with it —
    * which is the whole failure this suite exists to prevent. */
  val ModelThreshold = 15

  def at(window: String, thr: Int): Option[Row] = rows.find(r => r.window == window && r.thr == thr)

  test("medDepth is the record's median at the model's own threshold and window") {
    if rows.nonEmpty then
      val row = at("w1954", ModelThreshold).getOrElse(fail("no w1954 row at the model's threshold"))
      assertEqualsDouble(MarketSim.SP500Anchors.medDepth, row.median, 0.05,
        f"the anchor no longer matches the record measured the way the model measures: the fixture " +
        f"reads ${row.median}%.1f%% over 1954-2026 at a ${ModelThreshold}%d%% threshold")
  }

  test("no window reproduces the pre-0.22.0 anchor at the model's threshold") {
    // The evidence that −27.1 was a different statistic, kept as a test so the claim in
    // `fitTargets` is checkable rather than asserted. Any window at 20% does reproduce it.
    if rows.nonEmpty then
      val atModel = rows.filter(_.thr == ModelThreshold).map(_.median)
      assert(atModel.forall(m => math.abs(m - -27.1) > 2.0),
        s"a window now reads near -27.1% at the model's own threshold (${atModel.mkString(", ")}); " +
        "the 0.22.0 re-measurement rested on no window doing so, so re-read it")
      val at20 = rows.filter(_.thr == 20).map(_.median)
      assert(at20.exists(m => math.abs(m - -27.1) < 1.5),
        s"no window reads near -27.1% at a 20% threshold either (${at20.mkString(", ")}); the " +
        "explanation for where the old anchor came from no longer holds")
  }

  test("the sibling episode anchors still reconcile, which is why only one moved") {
    // `crashes/century` and `worst crash %` were checked the same way and left alone. If either
    // stops reconciling, it needs the same treatment `medDepth` got.
    if rows.nonEmpty then
      val century = at("w1926", ModelThreshold).getOrElse(fail("no w1926 row"))
      val modern  = at("w1954", ModelThreshold).getOrElse(fail("no w1954 row"))
      val crashes = MarketSim.SP500Anchors.crashes
      assert(crashes >= century.perCentury && crashes <= modern.perCentury,
        f"crashes/century $crashes%.1f no longer sits between the record's ${century.perCentury}%.1f " +
        f"and ${modern.perCentury}%.1f; it can no longer be called consistent with this measurement")
      assert(math.abs(MarketSim.SP500Anchors.worstDepth - modern.worst) < 5.0,
        f"worst crash ${MarketSim.SP500Anchors.worstDepth}%.1f%% is no longer the same episode as the " +
        f"record's ${modern.worst}%.1f%%")
  }

  test("deeper thresholds give deeper medians, or the fixture is not what it claims") {
    for w <- rows.map(_.window).distinct do
      val byThr = rows.filter(_.window == w).sortBy(_.thr)
      assert(byThr.map(_.median).sliding(2).forall { case Seq(a, b) => b <= a + 1e-9; case _ => true },
        s"[$w] median depth does not decrease with the threshold: ${byThr.map(_.median).mkString(", ")}")
  }

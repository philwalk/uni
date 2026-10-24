package uni.apps

import munit.FunSuite
import uni.*

/** The bar and sector rows' records are their fixtures'.  The Rust twin carries the same checks in
  * `bar_record_tests`, against the same files. */
class BarRecordSuite extends FunSuite:

  /** Empty where the fixture is absent, which is a skip and not a failure: the artifact ships
    * without `test-data/`, so a source-tarball build must not fail here. */
  def fixture(name: String): Vector[Vector[String]] =
    val p = s"test-data/equity-anchors/$name".asPath
    if !p.exists then Vector.empty
    else p.lines.toVector
      .filterNot(l => l.startsWith("#") || l.trim.isEmpty)
      .map(_.split("\t", -1).toVector)

  /** Rust's `f64::round`: half away from zero. */
  def roundHalfAway(x: Double): Double = math.signum(x) * math.floor(math.abs(x) + 0.5)

  test("the bar records are the fixture's, with the intraday down/up") {
    val rows = fixture("bars-2026-09-01.tsv")
    assume(rows.nonEmpty, "fixture not present in this tree (source tarball?)")
    val stats = Vector("rangeMeanOverCcvol", "rangeAcf1", "rangeDownupIntraday", "volSd", "volCorrRange",
                       "overnightShare")
    for
      (pair, records) <- Vector(("w1993", MarketSim.BarRecordsSP500), ("w1999", MarketSim.BarRecordsNasdaq))
      (stat, record)  <- stats.zip(records)
    do
      val want = rows.find(r => r(0) == pair && r(1) == stat).flatMap(_(2).toDoubleOption)
      assertEquals(record.map(java.lang.Double.doubleToRawLongBits),
                   want.map(java.lang.Double.doubleToRawLongBits), s"$pair $stat")
  }

  test("the sector records are the fixture's forms") {
    val rows = fixture("sectors-2026-09-30.tsv")
    assume(rows.nonEmpty, "fixture not present in this tree (source tarball?)")
    def v(kind: String, form: String, window: String, stat: String): Double =
      rows.find(r => r(0) == kind && r(1) == "industries10" && r(2) == form && r(3) == window && r(4) == stat)
        .flatMap(_(5).toDoubleOption)
        .getOrElse(fail(s"no $kind $form $window $stat"))
    val xs = v("shape", "", "all", "mean cross-sectional sd")
    val want = Vector(
      v("momentum", "12-1", "all", "mean spread") / xs,
      v("trend", "12m", "all", "positive minus negative") / xs,
      v("trend", "sma10", "all", "positive minus negative") / xs,
      xs / v("shape", "", "all", "market monthly sd"),
      v("shape", "", "all", "median pairwise correlation"),
      v("shape", "", "market worst decile", "median pairwise correlation"),
      v("shape", "", "market middle decile", "median pairwise correlation"))
    for ((record, w), k) <- MarketSim.SectorRecords.zip(want).zipWithIndex do
      val rounded = roundHalfAway(w * 1e4) / 1e4
      assert(math.abs(record - rounded) < 1e-12, s"${MarketSim.SectorRows(k)}: $record against $w")
  }

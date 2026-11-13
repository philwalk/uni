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
    val rows = fixture("bars-2026-11-02.tsv")
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
    sectorRecordsMatch("sectors-2026-09-30.tsv", "industries10", MarketSim.SectorRecords)
    sectorRecordsMatch("sectors-2026-11-12.tsv", "industries40", MarketSim.SectorRecords40)
    assertEquals(MarketSim.SectorRecords40.length, MarketSim.SectorRows.length)
  }

  private val StrengthStat = "rank correlation with the next month"

  /** A table's records against its fixture rows in `SectorRows`' order: the ten's seven, the 40's
    * thirteen. */
  private def sectorRecordsMatch(file: String, table: String, records: Vector[Double]): Unit = {
    val (short, long) = (fixture(file), fixture("sectors-2026-11-13.tsv"))
    assume(short.nonEmpty && long.nonEmpty, "fixture not present in this tree (source tarball?)")
    val rows = short ++ long
    def v(kind: String, form: String, window: String, stat: String): Double =
      rows.find(r => r(0) == kind && r(1) == table && r(2) == form && r(3) == window && r(4) == stat)
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
      v("shape", "", "market middle decile", "median pairwise correlation"),
      v("strength", "13-24", "all", StrengthStat),
      v("strength", "61-120", "all", StrengthStat),
      v("shape", "10y", "all", "mean cross-sectional sd of log returns") / v("shape", "", "all", "market monthly sd"),
      v("flow", "1y", "all", "rank correlation with the next year's flow"),
      v("flow", "1y", "all", "rank correlation with the year before's return"),
      v("flow", "3y", "all", "rank correlation with the return three years on"))
    for ((record, w), k) <- records.zip(want).zipWithIndex do
      val rounded = roundHalfAway(w * 1e4) / 1e4
      assert(math.abs(record - rounded) < 1e-12, s"${MarketSim.SectorRows(k)}: $record against $w")
  }

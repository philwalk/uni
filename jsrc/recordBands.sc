#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.24.5

// THE RECORD BANDS: one real record's own sampling spread on every fidelity row a single daily
// series can be read the model's way.  The generator behind
// `test-data/equity-anchors/recordbands-2026-09-26.tsv`, and the Scala twin of
// `rust/src/bin/record_bands.rs`: same flags, same rows.
//
//     scala-cli run jsrc/recordBands.sc -- -header -set nasdaq -series QQQ \
//         -yahoo ../folio/data/yahoo/QQQ/prices.csv -from 1999-03-10 -to 2026-08-20
//
// IT DELEGATES.  Every row is read by `uni.apps.MarketSim.seriesReadings`, the functions the model
// reads a path with, and resampled by `recordResamples`, so a band cannot drift from the statistic
// it bands.  This script only reads the record and prints the rows.

import uni.*
import uni.apps.MarketSim

object RecordBands {
  def println(s: String = ""): Unit = print(s"$s\n")
  def eprintln(s: String = ""): Unit = System.err.print(s"$s\n")

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "(-yahoo FILE | -french FILE | -fred FILE) -from YYYY-MM-DD -to YYYY-MM-DD -set NAME -series LABEL",
    "",
    "-yahoo FILE   folio's cached prices: the `dlog_adj_close` column; the first row is the anchor",
    "              price, not a return, and is skipped",
    "-french FILE  Ken French's F-F_Research_Data_Factors_daily: Mkt-RF + RF compounded into an index",
    "              WITHOUT a leading 1.0 over the window, then its log returns -- which drops the",
    "              window's first session (the persistence fixture's rule)",
    "-fred FILE    FRED's daily `date,value` CSV (the effective federal funds rate, DFF): a level in",
    "              percent, read on Monday-Friday dates, for the rate rows (`-rate`)",
    "-rows A,B     print only these rows (default every `RecordBandRows` row)",
    "-resamples N  one-year-block resamples (default 20000)",
    "-seed S       the resampling stream's seed (default 20260918)",
    "-joint A      the share of the set's record-consistent worlds its joint band may miss",
    "              (default 0.10), split over the set's record windows by their rows: this window's",
    "              rows jointly miss A x (rows here) / N of the time",
    "-of N         the set's banded rows across all its windows (default: the rows printed here)",
    "-header       print the column header first",
    "-coupling     print the record's bubble coupling (`bubbleCouplingOf`), largest 3-year run-up",
    "              and longest calm stretch instead, the rows of",
    "              `bubblebust-2026-09-24.tsv`: no resampling keeps the structure it measures",
    "-rate         print the two rate rows (`RateBandRows`) of a -fred window instead: the record",
    "              by `rateReadings`, its resamples by `rateResamples`, their own joint band",
    "-bond         print the bond row (`BondBandRows`) of a -yahoo window (TLT) instead: the record",
    "              by `bondReadings`, its resamples by `bondResamples`, its own joint band",
  )

  /** `(date, log return)` for every session of folio's cached Yahoo file after its first. */
  def readYahoo(file: String): Vector[(String, Double)] =
    val lines = file.asPath.lines.toVector
    val col = lines.headOption.getOrElse(usage(s"$file is empty")).split(',').map(_.trim)
      .indexOf("dlog_adj_close")
    if col < 0 then usage(s"$file: no dlog_adj_close column")
    lines.drop(2).filter(_.trim.nonEmpty).map { l =>
      val f = l.split(',')
      val v = f.lift(col).flatMap(_.trim.toDoubleOption).getOrElse(usage(s"$file: unreadable row [$l]"))
      (f(0).trim, v)
    }

  /** `(date, Mkt-RF + RF in percent)` for every dated row of Ken French's daily factor file. */
  def readFrench(file: String): Vector[(String, Double)] =
    file.asPath.lines.toVector.flatMap { l =>
      val f = l.split(',').map(_.trim)
      val d = f.headOption.getOrElse("")
      if d.length != 8 || !d.forall(_.isDigit) || f.length < 5 then None
      else
        for mkt <- f(1).toDoubleOption; rf <- f(4).toDoubleOption
        yield (s"${d.take(4)}-${d.slice(4, 6)}-${d.slice(6, 8)}", mkt + rf)
    }

  /** `(date, rate as a decimal)` for every Monday-Friday date of FRED's daily `date,value` CSV
    * (`observation_date,DFF` in fredgraph's spelling) whose value is a number. */
  def readFred(file: String): Vector[(String, Double)] =
    file.asPath.lines.toVector.drop(1).flatMap { l =>
      val f = l.split(',').map(_.trim)
      for
        d <- f.headOption
        v <- f.lift(1).flatMap(_.toDoubleOption)
        if weekday(d)
      yield (d, v / 100.0)
    }

  /** Whether an ISO date falls on Monday to Friday (Zeller, proleptic Gregorian). */
  def weekday(d: String): Boolean =
    def p(a: Int, b: Int): Option[Long] = if d.length < b then None else d.slice(a, b).toLongOption
    (p(0, 4), p(5, 7), p(8, 10)) match
      case (Some(y0), Some(m0), Some(day)) =>
        val (y, m) = if m0 < 3 then (y0 - 1, m0 + 12) else (y0, m0)
        val k = y % 100
        val j = y / 100
        // 0 = Saturday
        val h = Math.floorMod(day + 13 * (m + 1) / 5 + k + k / 4 + j / 4 + 5 * j, 7L)
        h >= 2
      case _ => false

  def main(args: Array[String]): Unit = {
    var yahoo = ""; var french = ""; var fred = ""; var from = ""; var to = ""
    var set = ""; var series = ""; var rows = Vector.empty[String]
    var resamples = 20000; var seed = 20260918L; var header = false
    var joint = 0.10; var of = 0; var coupling = false; var rate = false; var bond = false
    eachArg(args.toSeq, usage) {
      case "-yahoo"     => yahoo = consumeNext
      case "-french"    => french = consumeNext
      case "-fred"      => fred = consumeNext
      case "-from"      => from = consumeNext
      case "-to"        => to = consumeNext
      case "-set"       => set = consumeNext
      case "-series"    => series = consumeNext
      case "-rows"      => rows = consumeNext.split(",").map(_.trim).toVector
      case "-resamples" => resamples = consumeNext.toIntOption.getOrElse(usage("-resamples wants an integer"))
      case "-seed"      => seed = consumeNext.toLongOption.getOrElse(usage("-seed wants a non-negative integer"))
      case "-joint"     => joint = consumeNext.toDoubleOption.getOrElse(usage("-joint wants a share in (0, 1)"))
      case "-of"        => of = consumeNext.toIntOption.getOrElse(usage("-of wants a row count"))
      case "-header"    => header = true
      case "-coupling"  => coupling = true
      case "-rate"      => rate = true
      case "-bond"      => bond = true
      case a            => usage(s"unrecognized arg [$a]")
    }
    if Vector(yahoo, french, fred).count(_.nonEmpty) != 1 then usage("give exactly one of -yahoo, -french and -fred")
    if rate != fred.nonEmpty then usage("-rate reads a -fred file, and a -fred file is read by -rate")
    if from.isEmpty || to.isEmpty then usage("-from and -to are required")
    if set.isEmpty || series.isEmpty then usage("-set and -series are required")
    rows.find(r => !MarketSim.RecordBandRows.contains(r))
      .foreach(r => usage(s"-rows names [$r], which is not a record-band row"))

    def inWindow(d: String) = d >= from && d <= to
    // (date of each return, the return) -- or, on a -fred source, the window's daily rate levels,
    // no session dropped
    val dated: Vector[(String, Double)] =
      if fred.nonEmpty then readFred(fred).filter((d, _) => inWindow(d))
      else if yahoo.nonEmpty then readYahoo(yahoo).filter((d, _) => inWindow(d))
      else
        val days = readFrench(french).filter((d, _) => inWindow(d))
        val idx = days.scanLeft(1.0)((p, dx) => p * (1.0 + dx._2 / 100.0)).drop(1)
        // `lnDet`, not the native log, which differs from the Rust twin's in the last bit on about
        // 0.2% of inputs: the twins must read the same returns to the bit, or the joint band's
        // ranks tie differently
        (1 until days.length).toVector.map(k => (days(k)._1, MarketSim.lnDet(idx(k) / idx(k - 1))))
    if dated.length <= 252 then
      usage(s"the window holds ${dated.length} sessions; a block bootstrap needs more than a year")
    val r = dated.map(_._2).toArray
    val window = s"${dated.head._1}..${dated.last._1}"
    if coupling then
      if header then println("set\trow\tseries\twindow\tn\trecord")
      for (name, value) <- Vector(("bubble coupling 3y", MarketSim.bubbleCouplingOf(r)),
                                  ("largest 3y run-up", MarketSim.runUp3yOf(r)),
                                  ("longest calm stretch", MarketSim.calmStretchOf(r))) do
        println(f"$set%s\t$name%s\t$series%s\t$window%s\t${r.length}%d\t$value%.6f")
      return
    if rate then
      printRateRows(set, series, rows, resamples, seed, joint, of, header, r, window)
      return
    if bond then
      printBondRows(set, series, resamples, seed, joint, of, header, r, window)
      return
    eprintln(s"$series: ${r.length} returns $window, ${r.count(_ == 0.0)} exactly zero; " +
             s"$resamples resamples, seed $seed")

    val record = MarketSim.seriesReadings(r).zip(MarketSim.RecordBandRows).map { (v, name) =>
      if name == "typical-year vol %" then MarketSim.yearVolPhaseMean(r) else v
    }
    val reads = MarketSim.recordResamples(r, resamples, seed)

    if header then
      println("set\trow\tseries\twindow\tn\tresamples\trecord\t" +
              MarketSim.RecordBandPcts.map(p => s"p$p").mkString("\t") + "\tjointC\tjointLo\tjointHi")
    // THE JOINT BAND over the rows this window prints, at this window's share of the set's miss rate
    val ks = MarketSim.RecordBandRows.indices.toVector
      .filter(k => rows.isEmpty || rows.contains(MarketSim.RecordBandRows(k)))
    val alpha = joint * ks.length / (if of == 0 then ks.length else of)
    val (c, edges) = MarketSim.recordBandJoint(reads, ks, alpha)
    for (k, (lo, hi)) <- ks.zip(edges) do
      val name = MarketSim.RecordBandRows(k)
      val qs = MarketSim.recordBandQuantiles(reads.map(_(k))).map(v => f"$v%.6f")
      println(f"$set%s\t$name%s\t$series%s\t$window%s\t${r.length}%d\t$resamples%d\t${record(k)}%.6f\t" +
              qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")
  }

  /** The two rate rows (`RateBandRows`) on a rate window: the record by `rateReadings`, its block
    * resamples by `rateResamples`, and a joint band over the two at this window's share of the
    * set's miss rate -- the same columns as the equity rows. */
  def printRateRows(set: String, series: String, rows: Vector[String], resamples: Int, seed: Long,
                    joint: Double, of: Int, header: Boolean, rate: Array[Double], window: String): Unit =
    val record = MarketSim.rateReadings(rate)
    eprintln(f"$series%s: ${rate.length}%d sessions $window%s, mean ${record(0)}%.4f%%, ${record(1)}%.2f%% under the floor; " +
             s"$resamples resamples, seed $seed")
    val reads = MarketSim.rateResamples(rate, resamples, seed)
    if header then
      println("set\trow\tseries\twindow\tn\tresamples\trecord\t" +
              MarketSim.RecordBandPcts.map(p => s"p$p").mkString("\t") + "\tjointC\tjointLo\tjointHi")
    val ks = MarketSim.RateBandRows.indices.toVector
      .filter(k => rows.isEmpty || rows.contains(MarketSim.RateBandRows(k)))
    val alpha = joint * ks.length / (if of == 0 then ks.length else of)
    val (c, edges) = MarketSim.recordBandJoint(reads, ks, alpha)
    for (k, (lo, hi)) <- ks.zip(edges) do
      val name = MarketSim.RateBandRows(k)
      val qs = MarketSim.recordBandQuantiles(reads.map(_(k))).map(v => f"$v%.6f")
      println(f"$set%s\t$name%s\t$series%s\t$window%s\t${rate.length}%d\t$resamples%d\t${record(k)}%.6f\t" +
              qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")

  /** The bond row (`BondBandRows`) on a bond's return window (`-yahoo`, TLT): the record by
    * `bondReadings`, its block resamples by `bondResamples`, a joint band over the one row at this
    * window's share of the set's miss rate -- the same columns as the equity rows. */
  def printBondRows(set: String, series: String, resamples: Int, seed: Long, joint: Double, of: Int,
                    header: Boolean, r: Array[Double], window: String): Unit =
    val record = MarketSim.bondReadings(r)
    eprintln(f"$series%s: ${r.length}%d returns $window%s, bond depth vs vol ${record(0)}%.4f; " +
             s"$resamples resamples, seed $seed")
    val reads = MarketSim.bondResamples(r, resamples, seed)
    if header then
      println("set\trow\tseries\twindow\tn\tresamples\trecord\t" +
              MarketSim.RecordBandPcts.map(p => s"p$p").mkString("\t") + "\tjointC\tjointLo\tjointHi")
    val alpha = joint / (if of == 0 then 1 else of)
    val (c, edges) = MarketSim.recordBandJoint(reads, Vector(0), alpha)
    val qs = MarketSim.recordBandQuantiles(reads.map(_(0))).map(v => f"$v%.6f")
    val (lo, hi) = edges(0)
    println(f"$set%s\t${MarketSim.BondBandRows(0)}%s\t$series%s\t$window%s\t${r.length}%d\t$resamples%d\t${record(0)}%.6f\t" +
            qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")
}

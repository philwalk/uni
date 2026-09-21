#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.24.4

// THE RECORD BANDS: one real record's own sampling spread on every fidelity row a single daily
// series can be read the model's way.  The generator behind
// `test-data/equity-anchors/recordbands-2026-09-18.tsv`, and the Scala twin of
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
    "(-yahoo FILE | -french FILE) -from YYYY-MM-DD -to YYYY-MM-DD -set NAME -series LABEL",
    "",
    "-yahoo FILE   folio's cached prices: the `dlog_adj_close` column; the first row is the anchor",
    "              price, not a return, and is skipped",
    "-french FILE  Ken French's F-F_Research_Data_Factors_daily: Mkt-RF + RF compounded into an index",
    "              WITHOUT a leading 1.0 over the window, then its log returns -- which drops the",
    "              window's first session (the persistence fixture's rule)",
    "-rows A,B     print only these rows (default every `RecordBandRows` row)",
    "-resamples N  one-year-block resamples (default 20000)",
    "-seed S       the resampling stream's seed (default 20260918)",
    "-joint A      the share of the set's record-consistent worlds its joint band may miss",
    "              (default 0.10), split over the set's record windows by their rows: this window's",
    "              rows jointly miss A x (rows here) / N of the time",
    "-of N         the set's banded rows across all its windows (default: the rows printed here)",
    "-header       print the column header first",
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

  def main(args: Array[String]): Unit = {
    var yahoo = ""; var french = ""; var from = ""; var to = ""
    var set = ""; var series = ""; var rows = Vector.empty[String]
    var resamples = 20000; var seed = 20260918L; var header = false
    var joint = 0.10; var of = 0
    eachArg(args.toSeq, usage) {
      case "-yahoo"     => yahoo = consumeNext
      case "-french"    => french = consumeNext
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
      case a            => usage(s"unrecognized arg [$a]")
    }
    if yahoo.nonEmpty == french.nonEmpty then usage("give exactly one of -yahoo and -french")
    if from.isEmpty || to.isEmpty then usage("-from and -to are required")
    if set.isEmpty || series.isEmpty then usage("-set and -series are required")
    rows.find(r => !MarketSim.RecordBandRows.contains(r))
      .foreach(r => usage(s"-rows names [$r], which is not a record-band row"))

    def inWindow(d: String) = d >= from && d <= to
    // (date of each return, the return)
    val dated: Vector[(String, Double)] =
      if yahoo.nonEmpty then readYahoo(yahoo).filter((d, _) => inWindow(d))
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
}

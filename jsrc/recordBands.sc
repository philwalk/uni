#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.24.5

// THE RECORD BANDS: one real record's own sampling spread on every fidelity row a single daily
// series can be read the model's way.  The generator behind
// `test-data/equity-anchors/recordbands-2026-09-26.tsv`, and the Scala twin of
// `rust/src/bin/record_bands.rs`: same flags, same rows.
//
//     scala-cli run jsrc/recordBands.sc -- -header -set nasdaq -series QQQ \
//         -yahoo <data>/yahoo/QQQ/prices.csv -from 1999-03-10 -to 2026-08-20
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
    "-rateafter -fred DFF (-yahoo FILE | -french FILE) -from -to -set -series [-of N]",
    "",
    "-yahoo FILE   a `date,adj_close,dlog_adj_close` CSV of adjusted closes (Yahoo's chart API, one row",
    "              a session): the `dlog_adj_close` column; the first row is the anchor price, not a",
    "              return, and is skipped",
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
    "-multiyear    print the record's multi-year rows (`multiYearReadings`) instead, the rows of",
    "              `multiyear-2026-09-29.tsv`; with -long under their long-window names",
    "-rate         print the two rate rows (`RateBandRows`) of a -fred window instead: the record",
    "              by `rateReadings`, its resamples by `rateResamples`, their own joint band",
    "-bond         print the bond row (`BondBandRows`) of a -yahoo window (TLT) instead: the record",
    "              by `bondReadings`, its resamples by `bondResamples`, its own joint band",
    "-rateafter    THE CONDITIONAL RATE ROWS (`RateAfterRows`): the -fred rate on the equity window's",
    "              session dates, the two years after each 20% decline's trough; the record by",
    "              `rateAfterOfReturns`, its paired block resamples, their own joint band",
    "-timing FILE  THE TIMING ROWS instead (`TimingRows`): Shiller's monthly S&P as a",
    "              `month,price,dividend,cpi` CSV, dividends reinvested, over -from..-to (YYYY-MM);",
    "              what a 10-month moving-average exit does on the record, the rows of",
    "              `timing-2026-09-30.tsv`",
    "-sectors DIR  THE SECTOR ROWS instead: Ken French's `10_Industry_Portfolios.CSV`,",
    "              `49_Industry_Portfolios.CSV` and `F-F_Research_Data_Factors.CSV` in DIR as the",
    "              library publishes them (unzipped), the rows of `sectors-2026-09-30.tsv` with 5-95",
    "              block-bootstrap bands where a row has one",
  )

  /** `(date, log return)` for every session of a `date,adj_close,dlog_adj_close` file after its first. */
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

  /** `(month, total-return level)` from Shiller's monthly S&P as a `month,price,dividend,cpi` CSV
    * (the workbook's Data sheet: Date, P, D, CPI): the level compounds each month's price ratio
    * with the dividend rate over twelve on the prior price, a blank dividend carrying the last one. */
  def readShillerMonthly(file: String): Vector[(String, Double)] =
    var prevP = Double.NaN
    var lastD = Double.NaN
    var level = Double.NaN
    file.asPath.lines.toVector.drop(1).flatMap { l =>
      val f = l.split(',').map(_.trim)
      if f.length < 3 then None
      else
        val p = f(1).toDoubleOption.getOrElse(usage(s"$file: price [${f(1)}]"))
        f(2).toDoubleOption.foreach(d => lastD = d)
        level = if prevP.isNaN then p else level * (p + lastD / 12.0) / prevP
        prevP = p
        Some((f(0), level))
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
    if args.contains("-timing") then
      def opt(flag: String): Option[String] =
        val i = args.indexOf(flag)
        if i < 0 then None else args.lift(i + 1)
      val file = opt("-timing").getOrElse(usage("-timing needs Shiller's monthly CSV"))
      val from = opt("-from").getOrElse("")
      val to = opt("-to").getOrElse("9999-99")
      val set = opt("-set").getOrElse(usage("-set is required"))
      val series = opt("-series").getOrElse(usage("-series is required"))
      val rows = readShillerMonthly(file).filter((m, _) => m >= from && m <= to)
      if rows.length < 24 then usage("the window holds fewer than two years of months")
      val levels = rows.map(_._2).toArray
      val window = s"${rows.head._1}..${rows.last._1}"
      if args.contains("-header") then println("set	row	series	window	n	record")
      for (name, value) <- MarketSim.TimingRows.zip(MarketSim.timingOfMonthly(levels)) do
        println(f"$set%s	$name%s	$series%s	$window%s	${levels.length}%d	$value%.6f")
      return
    val k = args.indexOf("-sectors")
    if k >= 0 then
      val dir = args.lift(k + 1).getOrElse(usage("-sectors needs a directory"))
      def num(flag: String, default: Long): Long =
        val i = args.indexOf(flag)
        if i < 0 then default
        else args.lift(i + 1).flatMap(_.toLongOption).getOrElse(usage(s"$flag wants a non-negative integer"))
      printSectorRows(dir, num("-resamples", 20000L).toInt, num("-seed", 20260918L))
      return
    var yahoo = ""; var french = ""; var fred = ""; var from = ""; var to = ""
    var set = ""; var series = ""; var rows = Vector.empty[String]
    var resamples = 20000; var seed = 20260918L; var header = false
    var joint = 0.10; var of = 0; var coupling = false; var rate = false; var bond = false
    var multiyear = false; var long = false; var rateafter = false
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
      case "-multiyear" => multiyear = true
      case "-long"      => long = true
      case "-rate"      => rate = true
      case "-bond"      => bond = true
      case "-rateafter" => rateafter = true
      case a            => usage(s"unrecognized arg [$a]")
    }
    // `-rateafter` reads a rate beside an equity source; every other mode exactly one source
    val afterRate =
      if !rateafter then ""
      else if fred.isEmpty then usage("-rateafter wants a -fred rate beside the equity")
      else
        val f = fred
        fred = ""
        f
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
    if afterRate.nonEmpty then
      // the rate on the equity's session dates: a session without a rate is dropped
      val rates = readFred(afterRate).toMap
      val joined = dated.flatMap((d, x) => rates.get(d).map(v => (x, v)))
      printRateAfterRows(set, series, resamples, seed, joint, of, header,
                         joined.map(_._1).toArray, joined.map(_._2).toArray, window)
      return
    if coupling then
      if header then println("set\trow\tseries\twindow\tn\trecord")
      for (name, value) <- Vector(("bubble coupling 3y", MarketSim.bubbleCouplingOf(r)),
                                  ("largest 3y run-up", MarketSim.runUp3yOf(r)),
                                  ("longest calm stretch", MarketSim.calmStretchOf(r))) do
        println(f"$set%s\t$name%s\t$series%s\t$window%s\t${r.length}%d\t$value%.6f")
      return
    if multiyear then
      if header then println("set\trow\tseries\twindow\tn\trecord")
      val names = if long then MarketSim.MultiYearLongRows else MarketSim.MultiYearRows
      for (name, value) <- names.zip(MarketSim.multiYearReadings(r)) do
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
  /** The first monthly block of one of Ken French's published CSVs: the run of rows keyed `YYYYMM`
    * that follows the first column-name row, as `YYYY-MM` and the columns in fractions; `-99.99`
    * and `-999`, the library's missing marks, `None`. */
  def readFrenchMonthly(file: String): (Vector[String], Vector[Vector[Option[Double]]]) =
    val lines = Paths.get(file).lines.map(_.split(",", -1).map(_.trim).toVector).toVector
    def isMonth(f: Vector[String]) = f.length > 1 && f(0).length == 6 && f(0).forall(_.isDigit)
    val start = lines.indexWhere(isMonth)
    if start < 0 then usage(s"$file: no monthly rows")
    val cols  = lines(start).length - 1
    val rows  = lines.drop(start).takeWhile(isMonth)
    for r <- rows if r.length != cols + 1 do
      usage(s"$file: [${r.mkString(",")}] has ${r.length} fields, the block ${cols + 1}")
    val dates = rows.map(r => s"${r(0).take(4)}-${r(0).drop(4)}")
    val table = Vector.tabulate(cols)(k => rows.map(r => r(k + 1).toDoubleOption.filter(_ > -99.0).map(_ / 100.0)))
    (dates, table)

  /** The panel of one industry table beside the factors, aligned on the months both hold. */
  def sectorPanel(dir: String, table: String): (Vector[String], MarketSim.SectorPanel) =
    val (fd, fac) = readFrenchMonthly(s"$dir/F-F_Research_Data_Factors.CSV")
    val (id, ind) = readFrenchMonthly(s"$dir/$table")
    val months = id.filter(fd.contains)
    def pick(dates: Vector[String], col: Vector[Option[Double]]): Vector[Option[Double]] =
      months.map(m => col(dates.indexOf(m)))
    def must(v: Vector[Option[Double]]): Vector[Double] = v.map(_.getOrElse(usage("a blank in the factors file")))
    val mktRf = must(pick(fd, fac(0)))
    val rf    = must(pick(fd, fac(3)))
    (months, MarketSim.SectorPanel(ind.map(c => pick(id, c)), mktRf.zip(rf).map(_ + _), rf))

  /** THE SECTOR ROWS (`-sectors`): momentum (12-1 and 6-1, whole record and from 1963-07), the
    * per-sector trend (the 12-month sign and the 10-month SMA) and the cross-section shape, on the
    * 10- and 49-industry tables; one TSV row each, bands where a row has one. */
  def printSectorRows(dir: String, resamples: Int, seed: Long): Unit =
    println("row\ttable\tform\twindow\tstatistic\tvalue\tlo\thi\tmonths")
    for (table, file, top) <- Vector(("industries10", "10_Industry_Portfolios.CSV", 3),
                                     ("industries49", "49_Industry_Portfolios.CSV", 10)) do
      val (months, p) = sectorPanel(dir, file)
      val from63 = months.indexWhere(_ >= "1963-07")
      if from63 < 0 then usage("no month from 1963-07")
      eprintln(s"$table: ${p.returns.length} industries, ${months.length} months ${months.head}..${months.last}; " +
               s"$resamples resamples, seed $seed")
      for (label, form) <- Vector(("12-1", 11), ("6-1", 5)); (window, from) <- Vector(("all", 0), ("post-1963", from63)) do
        val m = MarketSim.sectorMomentum(p, form, top, from)
        val (lo, hi) = MarketSim.sectorMomentumBand(m.spreads, resamples, seed)
        val n = m.spreads.length
        println(f"momentum\t$table%s\t$label%s\t$window%s\tmean spread\t${m.mean}%.6f\t$lo%.6f\t$hi%.6f\t$n%d")
        println(f"momentum\t$table%s\t$label%s\t$window%s\tt-stat\t${m.t}%.6f\t\t\t$n%d")
        println(f"momentum\t$table%s\t$label%s\t$window%s\tshare positive\t${m.sharePositive}%.6f\t\t\t$n%d")
      for (label, mode) <- Vector(("12m", MarketSim.SectorTrend.Sign12), ("sma10", MarketSim.SectorTrend.Sma10))
          (window, from) <- Vector(("all", 0), ("post-1963", from63)) do
        val (v, n) = MarketSim.sectorTrend(p, mode, from)
        val (lo, hi) = MarketSim.sectorTrendBand(p, mode, from, resamples, seed)
        println(f"trend\t$table%s\t$label%s\t$window%s\tpositive minus negative\t$v%.6f\t$lo%.6f\t$hi%.6f\t$n%d")
      val (s, n) = MarketSim.sectorShape(p)
      println(f"shape\t$table%s\t\tall\tmean cross-sectional sd\t${s(0)}%.6f\t\t\t${n(0)}%d")
      println(f"shape\t$table%s\t\tall\tmarket monthly sd\t${MarketSim.sectorMarketSd(p)}%.6f\t\t\t${p.market.length}%d")
      println(f"shape\t$table%s\t\tall\tmedian pairwise correlation\t${s(1)}%.6f\t\t\t${n(0)}%d")
      println(f"shape\t$table%s\t\tmarket worst decile\tmedian pairwise correlation\t${s(2)}%.6f\t\t\t${n(1)}%d")
      println(f"shape\t$table%s\t\tmarket middle decile\tmedian pairwise correlation\t${s(3)}%.6f\t\t\t${n(2)}%d")

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

  /** THE CONDITIONAL RATE ROWS (`-rateafter`) on a `-fred` rate beside a `-yahoo` or `-french`
    * equity window, joined on the equity's session dates: the record by `rateAfterOfReturns`,
    * its paired block resamples by `rateAfterResamples`, a joint band over the two -- the rate
    * rows' columns. */
  def printRateAfterRows(set: String, series: String, resamples: Int, seed: Long, joint: Double, of: Int,
                         header: Boolean, r: Array[Double], rate: Array[Double], window: String): Unit =
    val record = MarketSim.rateAfterOfReturns(r, rate)
    eprintln(f"$series%s: ${r.length}%d sessions $window%s joined with the rate; post-trough mean " +
             f"${record(0)}%.4f%%, ${record(1)}%.2f%% under the floor; $resamples%d resamples, seed $seed%d")
    val reads = MarketSim.rateAfterResamples(r, rate, resamples, seed)
    if header then
      println("set\trow\tseries\twindow\tn\tresamples\trecord\t" +
              MarketSim.RecordBandPcts.map(p => s"p$p").mkString("\t") + "\tjointC\tjointLo\tjointHi")
    val ks = MarketSim.RateAfterRows.indices.toVector
    val alpha = joint * ks.length / (if of == 0 then ks.length else of)
    val (c, edges) = MarketSim.recordBandJoint(reads, ks, alpha)
    for (k, (lo, hi)) <- ks.zip(edges) do
      val qs = MarketSim.recordBandQuantiles(reads.map(_(k))).map(v => f"$v%.6f")
      println(f"$set%s\t${MarketSim.RateAfterRows(k)}%s\t$series%s\t$window%s\t${r.length}%d\t$resamples%d\t${record(k)}%.6f\t" +
              qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")
}

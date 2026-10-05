#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.24.6

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
    "(-yahoo FILE [-splice FILE -at YYYY-MM-DD]... [-column NAME] | -french FILE | -fred FILE) -from YYYY-MM-DD -to YYYY-MM-DD",
    "    -set NAME -series LABEL",
    "-rateafter -fred DFF (-yahoo FILE | -french FILE) -from -to -set -series [-of N]",
    "-basket -closes WIDE.csv -closesdate YYYY-MM-DD [-from D] [-to D] [-minsessions N] [-out RULER.tsv]",
    "",
    "-yahoo FILE   a daily series, in one of three published layouts told apart by the file itself:",
    "              a `date,adj_close,dlog_adj_close` CSV of adjusted closes (Yahoo Finance's daily",
    "              history, one row a session; the `dlog_adj_close` column, the first row the anchor",
    "              price and skipped); FRED's `observation_date,DGSn` constant-maturity Treasury",
    "              yield, read as an n-year par bond rebought every observation (the coupon accrues",
    "              over the calendar days held, the price moves with the yield, semiannual",
    "              compounding; `.` rows skipped); or Ken French's daily industry file",
    "              (`10_Industry_Portfolios_Daily.CSV` as published), the value-weighted column",
    "              -column NAME names, -99.99 rows skipped",
    "-splice FILE -at DATE",
    "              a file continuing the series: the returns so far dated on or before DATE, then this",
    "              file's dated after it, in any of the three layouts. Repeatable, applied in order",
    "              (the Nasdaq splice: ^IXIC through 1985-10-01, ^NDX after; a record frozen at its",
    "              licensed source's last date continues on a published proxy: TLT on DGS20, QQQ or",
    "              the NDX on French's HiTec)",
    "-column NAME  the industry column a French industry file in the chain is read from",
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
    "-coupling     print the record's bubble coupling (`bubbleCouplingOf`), largest 3-year run-up,",
    "              longest calm stretch and 250-session variance ratio (`varianceRatio`) instead, the",
    "              rows of `bubblebust-2026-09-24.tsv`: no resampling keeps the structure they measure",
    "-multiyear    print the record's multi-year rows (`multiYearReadings`) instead, the rows of",
    "              `multiyear-2026-09-29.tsv`; with -long under their long-window names",
    "-rate         print the two rate rows (`RateBandRows`) of a -fred window instead: the record",
    "              by `rateReadings`, its resamples by `rateResamples`, their own joint band",
    "-bond         print the bond row (`BondBandRows`) of a -yahoo window (TLT) instead: the record",
    "              by `bondReadings`, its resamples by `bondResamples`, its own joint band",
    "-bond10       print the 10-year leg's rows (`Bond10BandRows`) of a -yahoo window instead -- FRED's",
    "              DGS10 as a par bond, with -fred DFF as the short rate its excess return is read",
    "              over: the record by `bond10Readings`, its resamples by `bond10Resamples`, their",
    "              own joint band over the three rows",
    "-rateafter    THE CONDITIONAL RATE ROWS (`RateAfterRows`): the -fred rate on the equity window's",
    "              session dates, the two years after each 20% decline's trough; the record by",
    "              `rateAfterOfReturns`, its paired block resamples, their own joint band",
    "-timing       THE TIMING ROWS instead (`TimingRows`), on MONTH-END levels over -from..-to: what a",
    "              10-month moving-average exit does on the record and the market's one-year trend, the",
    "              rows of `timing-2026-09-30.tsv`, from -french FILE (CRSP's daily factors compounded,",
    "              the ruler), -yahoo FILE (its log returns compounded, -splice as above) or -shiller",
    "              FILE (Shiller's monthly S&P as `month,price,dividend,cpi` -- monthly AVERAGES, for",
    "              comparison only)",
    "-volexit     THE VOLATILITY EXIT's rows (`VolExitRows`): the simple exit on the printed close at",
    "              1.5% / 2.0%, cash at the -fred rate, the 3x leg reset daily, each session joined to",
    "              the rate at its start and the calendar days it spans; the record, its one-year",
    "              block resamples (`volExitResamples`), their joint band. -closes FILE is a",
    "              `date,close,adj_close` CSV (Yahoo's printed and adjusted closes); -french FILE reads",
    "              CRSP's daily total return as both, having no printed close",
    "-basket       THE BASKET RULER for `-basketruler` (`basketRulerTsv`): -closes WIDE.csv is one row a",
    "              session, `date`, the index's column named by its ticker, then one column per name,",
    "              a name's cell empty before its listing (adjusted closes, dividends reinvested);",
    "              -closesdate is the day they were taken, since an adjusted series is recomputed on",
    "              every later distribution. -from / -to bound the window (default the file's);",
    "              -minsessions (default 252) is the sessions a name needs to enter the per-name and",
    "              pair ranges. Writes -out, or prints",
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

  /** `(date, log return)` of a daily series in whichever of the three layouts the file is in: a
    * `-yahoo` CSV (`readYahoo`), FRED's `observation_date,DGSn` yields (`readTreasury`) or Ken
    * French's daily industry file (`readIndustry`, the `-column` named). */
  def readDaily(file: String, column: String): Vector[(String, Double)] =
    val lines = file.asPath.lines.toVector
    val head  = lines.headOption.getOrElse("").split(',').map(_.trim)
    if head.contains("dlog_adj_close") then readYahoo(file)
    else if head.length == 2 && head(0) == "observation_date" && head(1).startsWith("DGS") then
      val years = head(1).drop(3).toDoubleOption.getOrElse(usage(s"$file: [${head(1)}] is not a DGSn series"))
      readTreasury(lines, years)
    else if lines.take(6).mkString(" ").toLowerCase.contains("industry portfolios") then
      if column.isEmpty then usage(s"$file: an industry file needs -column NAME")
      readIndustry(file, lines, column)
    else usage(s"$file: not a dlog_adj_close CSV, a FRED DGSn series or a French industry file")

  /** A daily series continued by each `-splice FILE -at DATE` in turn: the returns so far dated on
    * or before the date, then that file's dated after it. */
  def readDailyChain(file: String, splices: Vector[(String, String)], column: String): Vector[(String, Double)] =
    splices.foldLeft(readDaily(file, column)) { case (sofar, (next, at)) =>
      sofar.filter(_._1 <= at) ++ readDaily(next, column).filter(_._1 > at)
    }

  /** The `-splice FILE -at DATE` pairs of an argument list, in order. */
  def splicesOf(args: Seq[String]): Vector[(String, String)] =
    val (out, pending) = args.zipWithIndex.foldLeft((Vector.empty[(String, String)], Option.empty[String])) {
      case ((acc, pend), ("-splice", i)) =>
        if pend.isDefined then usage("-splice FILE wants its -at DATE before the next -splice")
        (acc, args.lift(i + 1))
      case ((acc, pend), ("-at", i)) =>
        val f = pend.getOrElse(usage("-at DATE follows a -splice FILE"))
        (acc :+ (f, args.lift(i + 1).getOrElse(usage("-at needs a date"))), None)
      case (st, _) => st
    }
    if pending.isDefined then usage("-splice FILE -at DATE go together")
    out

  /** Days since 1970-01-01 of an ISO date (proleptic Gregorian), the Rust twin's arithmetic. */
  def daysFromCivil(d: String): Long =
    def p(a: Int, b: Int): Long = d.slice(a, b).toLongOption.getOrElse(usage(s"[$d] is not a YYYY-MM-DD date"))
    val (y0, m, day) = (p(0, 4), p(5, 7), p(8, 10))
    val y   = if m <= 2 then y0 - 1 else y0
    val era = Math.floorDiv(y, 400L)
    val yoe = y - era * 400
    val mp  = (m + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    era * 146097 + doe - 719468

  /** The price of a par bond of coupon `c` at yield `y` with `t` years left, semiannual, per unit
    * face; `lnDet`/`expDet` so the twins agree to the bit. */
  def parPrice(c: Double, y: Double, t: Double): Double =
    if t <= 0.0 then 1.0
    else
      val v = MarketSim.expDet(-2.0 * t * MarketSim.lnDet(1.0 + y / 2.0))
      if y > 0.0 then (c / y) * (1.0 - v) + v else 1.0 + c * t

  /** `(date, log return)` of an n-year par Treasury rebought at every observation of a FRED
    * `observation_date,DGSn` yield file: bought at par at the last yield, sold at the next
    * observation at the new yield with the coupon accrued over the calendar days held. */
  def readTreasury(lines: Vector[String], years: Double): Vector[(String, Double)] =
    val obs = lines.drop(1).flatMap { l =>
      val f = l.split(',').map(_.trim)
      f.lift(1).flatMap(_.toDoubleOption).map(v => (f(0), v / 100.0))
    }
    (1 until obs.length).toVector.map { i =>
      val dt       = (daysFromCivil(obs(i)._1) - daysFromCivil(obs(i - 1)._1)).toDouble / 365.25
      val (y0, y1) = (obs(i - 1)._2, obs(i)._2)
      (obs(i)._1, MarketSim.lnDet(parPrice(y0, y1, years - dt) + y0 * dt))
    }

  /** `(date, log return)` of one column of Ken French's daily industry file, the first block (the
    * value-weighted returns, in percent); a -99.99 cell is a missing day and is skipped. */
  def readIndustry(file: String, lines: Vector[String], column: String): Vector[(String, Double)] =
    val rest   = lines.dropWhile(l => !l.contains("Value Weighted Returns -- Daily")).drop(1)
    val header = rest.headOption.getOrElse(usage(s"$file: no daily value-weighted block")).split(',').map(_.trim)
    val col    = header.indexOf(column)
    if col < 0 then usage(s"$file: no column [$column]; the file has ${header.drop(1).mkString(" ")}")
    rest.drop(1).map(_.split(',').map(_.trim))
      .takeWhile(f => f(0).length == 8 && f(0).forall(_.isDigit))
      .flatMap { f =>
        f.lift(col).flatMap(_.toDoubleOption).filter(_ > -99.0).map { v =>
          val d = f(0)
          (s"${d.take(4)}-${d.slice(4, 6)}-${d.slice(6, 8)}", MarketSim.lnDet(1.0 + v / 100.0))
        }
      }

  /** `(month, level at the month's last session)` from dated daily log returns: the returns summed
    * from the first, the level `expDet` of the sum, so the twins read the same levels. */
  def logMonthEnds(days: Vector[(String, Double)]): Vector[(String, Double)] =
    val sums = days.scanLeft(0.0)((c, dx) => c + dx._2).drop(1)
    days.indices.toVector.flatMap { k =>
      val d = days(k)._1
      if k + 1 == days.length || days(k + 1)._1.take(7) != d.take(7) then Some((d.take(7), MarketSim.expDet(sums(k))))
      else None
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

  /** `(month, total-return level at the month's last session)` from Ken French's daily factors:
    * Mkt-RF + RF compounded from the window's first session, the level at each calendar month's
    * last session. */
  def readFrenchMonthEnds(file: String, from: String, to: String): Vector[(String, Double)] =
    val days = readFrench(file).filter((d, _) => d >= from && d <= to)
    var level = 1.0
    days.zipWithIndex.flatMap { case ((d, x), k) =>
      level *= 1.0 + x / 100.0
      if k + 1 == days.length || days(k + 1)._1.take(7) != d.take(7) then Some((d.take(7), level)) else None
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

  /** Days from 1970-01-01 of an ISO date (Hinnant's `days_from_civil`). */
  def civilDays(d: String): Long =
    def p(a: Int, b: Int): Long = d.slice(a, b).toLongOption.getOrElse(usage(s"not a date [$d]"))
    val (y0, m, day) = (p(0, 4), p(5, 7), p(8, 10))
    val y = if m <= 2 then y0 - 1 else y0
    val era = Math.floorDiv(y, 400L)
    val yoe = y - era * 400
    val doy = (153 * (if m > 2 then m - 3 else m + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    era * 146097 + doe - 719468

  /** `(date, printed close, adjusted close)` for every session of a `date,close,adj_close` CSV. */
  def readCloses(file: String): Vector[(String, Double, Double)] =
    file.asPath.lines.toVector.drop(1).filter(_.trim.nonEmpty).map { l =>
      val f = l.split(',').map(_.trim)
      def num(k: Int): Double = f.lift(k).flatMap(_.toDoubleOption).getOrElse(usage(s"$file: unreadable row [$l]"))
      (f(0), num(1), num(2))
    }

  /** THE VOLATILITY EXIT's rows (`-volexit`): the record's sessions over `-from`..`-to`, each the
    * printed close's and the total return's simple returns, the `-fred` rate at the previous
    * session's date (the last one known on or before it), and the calendar days since that session;
    * the record and its joint band over the two rows at `-joint` x 2 / `-of`. */
  def volexitMode(args: Array[String]): Unit =
    def opt(flag: String): Option[String] =
      val i = args.indexOf(flag)
      if i < 0 then None else args.lift(i + 1)
    def req(flag: String): String = opt(flag).getOrElse(usage(s"-volexit wants $flag"))
    val (from, to, set, series) = (req("-from"), req("-to"), req("-set"), req("-series"))
    val resamples = opt("-resamples").map(_.toInt).getOrElse(20000)
    val seed = opt("-seed").map(_.toLong).getOrElse(20260918L)
    val joint = opt("-joint").map(_.toDouble).getOrElse(0.10)
    val of = opt("-of").map(_.toInt).getOrElse(2)
    val all: Vector[(String, Double, Double)] = (opt("-closes"), opt("-french")) match
      case (Some(f), None) => readCloses(f)
      case (None, Some(f)) =>
        var level = 1.0
        readFrench(f).map { (d, x) =>
          level *= 1.0 + x / 100.0
          (d, level, level)
        }
      case _ => usage("-volexit wants exactly one of -closes FILE and -french FILE")
    val levels = all.filter((d, _, _) => d >= from && d <= to)
    val rates = readFred(req("-fred"))
    val rateMap = rates.toMap
    def rateOn(d: String): Double =
      rateMap.getOrElse(d, {
        val k = rates.lastIndexWhere(_._1 <= d)
        if k < 0 then usage(s"no -fred rate on or before $d") else rates(k)._2
      })
    val days = (1 until levels.length).toVector.map { i =>
      val (d0, c0, t0) = levels(i - 1)
      val (d1, c1, t1) = levels(i)
      MarketSim.VolExitDay(c1 / c0 - 1.0, t1 / t0 - 1.0, rateOn(d0), (civilDays(d1) - civilDays(d0)).toDouble)
    }
    val window = s"${levels(1)._1}..${levels.last._1}"
    val record = MarketSim.volExitOf(days)
    val reads = MarketSim.volExitResamples(days, resamples, seed)
    eprintln(f"$series%s: ${days.length}%d sessions $window%s; timing ${record(0)}%.4f, interaction ${record(1)}%.4f; " +
             s"$resamples resamples, seed $seed")
    if args.contains("-header") then
      println("set\trow\tseries\twindow\tn\tresamples\trecord\t" +
              MarketSim.RecordBandPcts.map(p => s"p$p").mkString("\t") + "\tjointC\tjointLo\tjointHi")
    val ks = Vector(0, 1)
    val (c, edges) = MarketSim.recordBandJoint(reads, ks, joint * ks.length / of)
    for (k, (lo, hi)) <- ks.zip(edges) do
      val qs = MarketSim.recordBandQuantiles(reads.map(_(k))).map(v => f"$v%.6f")
      println(f"$set%s\t${MarketSim.VolExitRows(k)}%s\t$series%s\t$window%s\t${days.length}%d\t$resamples%d\t${record(k)}%.6f\t" +
              qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")

  /** `-basket`: the ruler from a wide closes file, written to `-out` or printed. */
  def basketMode(args: Array[String]): Unit =
    def opt(flag: String): Option[String] =
      val i = args.indexOf(flag)
      if i < 0 then None else args.lift(i + 1)
    val closes = opt("-closes").getOrElse(usage("-basket wants -closes WIDE.csv"))
    val closesDate = opt("-closesdate").getOrElse(usage("-basket wants -closesdate YYYY-MM-DD"))
    val minSessions = opt("-minsessions").fold(252)(v => v.toIntOption.getOrElse(usage("-minsessions wants a session count")))
    val p = closes.asPath
    if !p.isFile then usage(s"cannot read $closes")
    val tsv = MarketSim.parseBasketCloses(p.contentAsString)
      .flatMap(c => MarketSim.basketRulerTsv(c, opt("-from").getOrElse(""), opt("-to").getOrElse(""), closesDate, minSessions))
      .fold(m => usage(s"-basket $closes: $m"), identity)
    opt("-out") match
      case Some(out) =>
        out.asPath.write(tsv)
        eprintln(s"wrote $out")
      case None => print(tsv)

  def main(args: Array[String]): Unit = {
    if args.contains("-basket") then
      basketMode(args)
      return
    if args.contains("-volexit") then
      volexitMode(args)
      return
    if args.contains("-timing") then
      def opt(flag: String): Option[String] =
        val i = args.indexOf(flag)
        if i < 0 then None else args.lift(i + 1)
      val from = opt("-from").getOrElse("")
      val to = opt("-to").getOrElse("9999-99-99")
      val set = opt("-set").getOrElse(usage("-set is required"))
      val series = opt("-series").getOrElse(usage("-series is required"))
      val rows = (opt("-french"), opt("-shiller"), opt("-yahoo")) match
        case (Some(file), None, None) => readFrenchMonthEnds(file, from, to)
        case (None, Some(file), None) => readShillerMonthly(file).filter((m, _) => m >= from.take(7) && m <= to.take(7))
        case (None, None, Some(file)) =>
          logMonthEnds(readDailyChain(file, splicesOf(args.toSeq), opt("-column").getOrElse(""))
            .filter((d, _) => d >= from && d <= to))
        case _ => usage("-timing wants exactly one of -french FILE, -yahoo FILE and -shiller FILE")
      if rows.length < 24 then usage("the window holds fewer than two years of months")
      val levels = rows.map(_._2).toArray
      val window = s"${rows.head._1}..${rows.last._1}"
      if args.contains("-header") then println("set\trow\tseries\twindow\tn\trecord")
      for (name, value) <- MarketSim.TimingRows.zip(MarketSim.timingOfMonthly(levels)) do
        println(f"$set%s\t$name%s\t$series%s\t$window%s\t${levels.length}%d\t$value%.6f")
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
    var splice = Vector.empty[(String, String)]; var pending = ""; var column = ""
    var set = ""; var series = ""; var rows = Vector.empty[String]
    var resamples = 20000; var seed = 20260918L; var header = false
    var joint = 0.10; var of = 0; var coupling = false; var rate = false; var bond = false; var bond10 = false
    var multiyear = false; var long = false; var rateafter = false
    eachArg(args.toSeq, usage) {
      case "-yahoo"     => yahoo = consumeNext
      case "-french"    => french = consumeNext
      case "-fred"      => fred = consumeNext
      case "-splice"    =>
        if pending.nonEmpty then usage("-splice FILE wants its -at DATE before the next -splice")
        pending = consumeNext
      case "-at"        =>
        if pending.isEmpty then usage("-at DATE follows a -splice FILE")
        splice = splice :+ (pending, consumeNext); pending = ""
      case "-column"    => column = consumeNext
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
      case "-bond10"    => bond10 = true
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
    // `-bond10` reads the short rate beside its DGS10 file, for the excess-return row
    val cashRate =
      if !bond10 then ""
      else if fred.isEmpty then usage("-bond10 wants a -fred DFF beside the DGS10 file")
      else
        val f = fred
        fred = ""
        f
    if Vector(yahoo, french, fred).count(_.nonEmpty) != 1 then usage("give exactly one of -yahoo, -french and -fred")
    if rate != fred.nonEmpty then usage("-rate reads a -fred file, and a -fred file is read by -rate")
    if pending.nonEmpty then usage("-splice FILE -at DATE go together")
    if (splice.nonEmpty || column.nonEmpty) && yahoo.isEmpty then usage("-splice and -column continue a -yahoo series")
    if from.isEmpty || to.isEmpty then usage("-from and -to are required")
    if set.isEmpty || series.isEmpty then usage("-set and -series are required")
    rows.find(r => !MarketSim.RecordBandRows.contains(r))
      .foreach(r => usage(s"-rows names [$r], which is not a record-band row"))

    def inWindow(d: String) = d >= from && d <= to
    // (date of each return, the return) -- or, on a -fred source, the window's daily rate levels,
    // no session dropped
    val dated: Vector[(String, Double)] =
      if fred.nonEmpty then readFred(fred).filter((d, _) => inWindow(d))
      else if yahoo.nonEmpty then
        readDailyChain(yahoo, splice, column).filter((d, _) => inWindow(d))
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
                                  ("longest calm stretch", MarketSim.calmStretchOf(r)),
                                  ("variance ratio 250d", MarketSim.varianceRatio(r, 250))) do
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
    if bond10 then
      if yahoo.isEmpty || splice.nonEmpty then usage("-bond10 reads one FRED DGSn file given as -yahoo")
      printBond10Rows(set, series, resamples, seed, joint, of, header, yahoo, cashRate, dated, window)
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

  /** The 10-year leg's two rows (`Bond10BandRows`) on a bond series -- FRED's DGS10 read as a par
    * bond -- the record by `bond10Readings`, its one-year-block resamples by `bond10Resamples`,
    * their own joint band over the two rows. */
  /** `date -> (modified duration, the prior observation's date)` of the n-year par Treasury
    * `readTreasury` rebuys at each observation, keyed by the date of the return it earns: the
    * duration bought at the prior observation's yield. */
  def treasuryDurations(lines: Vector[String], years: Double): Map[String, (Double, String)] =
    val obs = lines.drop(1).flatMap { l =>
      val f = l.split(',').map(_.trim)
      f.lift(1).flatMap(_.toDoubleOption).map(v => (f(0), v / 100.0))
    }
    (1 until obs.length).map { i =>
      val y = obs(i - 1)._2
      val d = if y > 0.0 then (1.0 - MarketSim.expDet(-2.0 * years * MarketSim.lnDet(1.0 + y / 2.0))) / y else years
      (obs(i)._1, (d, obs(i - 1)._1))
    }.toMap

  /** `(first day number, running log accrual by day)` of FRED's daily DFF file: every calendar day
    * accrues `ln(1 + rate / 365)` at the last rate published (percent a year). */
  def cashCurve(file: String): (Long, Array[Double]) =
    val obs = file.asPath.lines.toVector.drop(1).flatMap { l =>
      val f = l.split(',').map(_.trim)
      f.lift(1).flatMap(_.toDoubleOption).map(v => (daysFromCivil(f(0)), v / 100.0))
    }
    if obs.isEmpty then usage(s"$file: no rates")
    val first = obs.head._1
    val last  = obs.last._1
    val cum = new Array[Double]((last - first + 1).toInt)
    var acc = 0.0; var rate = obs.head._2; var j = 0
    var day = first
    while day <= last do
      if j < obs.length && obs(j)._1 == day then
        rate = obs(j)._2
        j += 1
      acc += MarketSim.lnDet(1.0 + rate / 365.0)
      cum((day - first).toInt) = acc
      day += 1
    (first, cum)

  /** The short rate's log accrual over the days after `from` through `to` (`cashCurve`). */
  def cashAccrual(curve: (Long, Array[Double]), from: String, to: String): Double =
    def at(d: String): Double =
      val k = daysFromCivil(d) - curve._1
      if k < 0 then 0.0 else curve._2(math.min(k, curve._2.length - 1L).toInt)
    at(to) - at(from)

  /** The 10-year leg's three rows (`Bond10BandRows`) on FRED's DGS10 read as a par bond -- each
    * session's return, the duration it was bought at, and its log return over DFF's accrual across
    * the same days -- the record by `bond10Readings` at the window's own sessions a year, its
    * one-year-block resamples by `bond10Resamples`, their own joint band over the three rows. */
  def printBond10Rows(set: String, series: String, resamples: Int, seed: Long, joint: Double, of: Int,
                      header: Boolean, file: String, cashFile: String, dated: Vector[(String, Double)],
                      window: String): Unit =
    val lines = file.asPath.lines.toVector
    val head  = lines.headOption.getOrElse("").split(',').map(_.trim)
    if !(head.length == 2 && head(0) == "observation_date" && head(1).startsWith("DGS")) then
      usage(s"$file: -bond10 reads a FRED DGSn file")
    val years = head(1).drop(3).toDoubleOption.getOrElse(usage(s"$file: [${head(1)}] is not a DGSn series"))
    val durs  = treasuryDurations(lines, years)
    val curve = cashCurve(cashFile)
    val held  = dated.map((d, _) => durs.getOrElse(d, usage(s"$file: no duration for $d")))
    val r     = dated.map(_._2).toArray
    val dur   = held.map(_._1).toArray
    val ex    = dated.zip(held).map { case ((d, x), h) => x - cashAccrual(curve, h._2, d) }.toArray
    // sessions a year over the window, from the first return's purchase to the last return
    val span    = (daysFromCivil(dated.last._1) - daysFromCivil(held.head._2)).toDouble / 365.25
    val perYear = dated.length.toDouble / span
    val record  = MarketSim.bond10Readings(r, dur, ex, perYear)
    eprintln(f"$series%s: ${r.length}%d returns $window%s, bond10 vol per duration ${record(0)}%.4f, depth vs vol ${record(1)}%.4f, " +
             f"excess ${record(2)}%.4f pts/yr at $perYear%.2f sessions a year; $resamples%d resamples, seed $seed%d")
    val reads = MarketSim.bond10Resamples(r, dur, ex, perYear, resamples, seed)
    if header then
      println("set\trow\tseries\twindow\tn\tresamples\trecord\t" +
              MarketSim.RecordBandPcts.map(p => s"p$p").mkString("\t") + "\tjointC\tjointLo\tjointHi")
    val alpha = joint * 3.0 / (if of == 0 then 3 else of)
    val (c, edges) = MarketSim.recordBandJoint(reads, Vector(0, 1, 2), alpha)
    for (name, k) <- MarketSim.Bond10BandRows.zipWithIndex do
      val qs = MarketSim.recordBandQuantiles(reads.map(_(k))).map(v => f"$v%.6f")
      val (lo, hi) = edges(k)
      println(f"$set%s\t$name%s\t$series%s\t$window%s\t${r.length}%d\t$resamples%d\t${record(k)}%.6f\t" +
              qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")

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

#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.25.0

// THE RECORD BANDS: one real record's own sampling spread on every fidelity row a single daily
// series can be read the model's way.  The generator behind
// `test-data/equity-anchors/recordbands-2026-09-26.tsv`, and the Scala twin of
// `rust/src/bin/record_bands.rs`: same flags, same rows.
//
//     jsrc/recordBands.sc -header -set nasdaq -series QQQ \
//         -yahoo <data>/yahoo/QQQ/prices.csv -from 1999-03-10 -to 2026-08-20
//
// IT DELEGATES.  Every row is read by `uni.apps.MarketSim.seriesReadings`, the functions the model
// reads a path with, and resampled by `recordResamples`, so a band cannot drift from the statistic
// it bands.  This script only reads the record and prints the rows.

import uni.*
import uni.data.NumPyRNG
import uni.apps.MarketSim

object RecordBands {
  def println(s: String = ""): Unit = print(s"$s\n")
  def eprintln(s: String = ""): Unit = System.err.print(s"$s\n")

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "(-yahoo FILE [-splice FILE -at YYYY-MM-DD]... [-column NAME] | -french FILE) -from YYYY-MM-DD -to YYYY-MM-DD",
    "    -set NAME -series LABEL [-rows A,B] [-resamples N] [-seed S] [-joint A] [-of N] [-header]",
    "    [-coupling | -multiyear [-long] | -worstcrash]",
    "-rateafter -fred DFF (-yahoo FILE | -french FILE) -from -to -set -series [-of N]",
    "-sectors DIR [-resamples N] [-seed S]",
    "-volexit (-closes FILE | -french FILE) -fred DFF -from -to -set -series",
    "    [-resamples N] [-seed S] [-joint A] [-of N] [-header]",
    "-volstate -yahoo FILE [-column NAME] [-splice FILE -at DATE]... -fred DFF -from -to -set -series",
    "    [-refseries LABEL] [-resamples N] [-seed S] [-joint A] [-of N] [-header]",
    "-ledgerrows (-yahoo FILE [-column NAME] [-splice FILE -at DATE]... | -french FILE) -fred DFF",
    "    -from -to -set -series [-resamples N] [-seed S] [-joint A] [-header]",
    "-basket -closes WIDE.csv -closesdate YYYY-MM-DD [-from D] [-to D] [-minsessions N] [-out RULER.tsv]",
    "-profits -fred CPATAX.csv -set NAME [-series LABEL] [-header]",
    "-unlagged OUT (-french FILE | -industry FILE -column NAME | -index FILE)",
    "-scales LEDGER.tsv -runs RUNS.tsv [-resamples N]",
    "-satellite -primary SPY.csv -sat QQQ.csv -set NAME [-from D] [-to D]",
    "-dividend (-shiller FILE | -closes FILE) -set NAME [-from D] [-to D]",
    "-resampled OUT -block 252|756 [-n N] [-seed S] [-from D] [-to D] (-equity FILE)... [-fred FILE]...",
    "",
    "THE LAG-FREE RECORD (every mode): a computed daily index's sessions through 1999-03-10",
    "              (`UnlagCut`) are read with the one-day lag of stale prices removed (`unlagReturns`),",
    "              on the sessions the record takes from the source -- the file's first session through",
    "              its -splice date, or a splice file's sessions after its own, never cut by -from/-to.",
    "              French's daily factors (the market) and industry files are such indexes. A",
    "              `date,adj_close,dlog_adj_close` or `date,close,adj_close` file with a session on or",
    "              before the cut is declared: -index FILE (a computed index, read lag-free) or -fund FILE",
    "              (a fund, or a series already lag-free, read as recorded); undeclared, it is refused.",
    "              -recorded reads every source as recorded",
    "-unlagged     THE LAG-FREE SOURCE written to OUT in its own layout, for a ruler outside this tool:",
    "              -french FILE's Mkt-RF (the corrected total less RF), -industry FILE's -column, or",
    "              -index FILE's `date,adj_close,dlog_adj_close` rows (the levels compounded from the",
    "              corrected returns); the whole file is the source's taken range",
    "-worstcrash   THE WORST CRASH ROW instead (`worst crash %`, `worstCrashOf`): the deepest 15%+",
    "              decline of the price the window's returns compound to, a record alone",
    "-satellite    THE SATELLITE ROWS (`SatelliteRows`, `satRowsOf`): -sat FILE against -primary FILE,",
    "              each a CSV with date and adj_close columns, on their shared sessions over",
    "              -from..-to, a record alone",
    "-dividend     THE DIVIDEND YIELD ROW (`DividendRow`): -shiller FILE's monthly dividend over price,",
    "              or -closes FILE's trailing-252-session distribution yield (adjusted close over close)",
    "              averaged by calendar year, over -from..-to, a record alone; under -block the yield",
    "              levels are resampled, keeping their dates",
    "-resampled    THE RECORD'S BLOCK RESAMPLES AS FILES, for a ruler outside this tool: -n copies (200)",
    "              of every source at OUT/k/<its path>, each in its own layout. The first -equity file's",
    "              sessions in -from..-to are the calendar each copy draws its -block blocks on (-seed);",
    "              every source takes the same date blocks: an -equity file (French's daily factors,",
    "              `date,close,adj_close[,dlog...]`, `date,adj_close,dlog_adj_close` or",
    "              `date,open,high,low,close,adj_close,volume`) re-dated and compounded again, lag-free",
    "              as its readers read it, the bar kept relative to its close; a -fred file's",
    "              observations moved as levels to the block's session their dates fall in. Rows",
    "              outside the calendar's span are kept",
    "-block        THE RESAMPLING BLOCK, 252 or 756: one or three years of the series' own observations",
    "              (sessions, months, quarters) a block, where every mode otherwise draws its own (a year",
    "              of sessions, five years of months); under it the modes that print a record alone",
    "              (-coupling, -multiyear, -worstcrash, -timing, -profits, -cpipersist, -satellite,",
    "              -dividend) print its resamples' quantiles too, in the band columns; -sectors DIR",
    "              prints its graded rows (`SectorRows`) in them",
    "-scales       THE RECORD SCALES of the ledger's rows: each run of RUNS.tsv (a line a run, the rows",
    "              it supplies, `*` or names joined by `;`, then its arguments, tab-separated; `#` a",
    "              comment) run as this tool at its own blocks, at -block 252 and at -block 756",
    "              (-resamples N in place of its own), each printed row's (p95 - p5) / 3.29 read at each;",
    "              one line a world and row of LEDGER.tsv's `row` column: world, row, scale252,",
    "              scale756, scaleNative, scale (the largest of the three), resolution and knee, the",
    "              last two from the resamples the scale came from -- the resolution the record's own",
    "              vote on its sign against the table's `null` column (the share of resamples strictly",
    "              on the record's side less the share strictly on the other, floored at 0), the knee",
    "              the CRPS an indistinguishable world earns (the mean over the resamples of each one's",
    "              CRPS under the others, in the row's scale); `none` where no run reaches the row",
    "              (those listed on stderr by world), the resolution also without a null or with the",
    "              record at it",
    "",
    "-rateafter    THE CONDITIONAL RATE ROWS (`RateAfterRows`): the -fred rate on the equity window's",
    "              session dates, the two years after each 20% decline's trough; the record by",
    "              `rateAfterOfReturns`, its paired block resamples, their own joint band",
    "-timing       THE TIMING ROWS instead (`TimingRows`), on MONTH-END levels over -from..-to: what a",
    "              10-month moving-average exit does on the record and the market's one-year trend, the",
    "              rows of `timing-2026-09-30.tsv`, from -french FILE (CRSP's daily factors compounded,",
    "              the ruler), -yahoo FILE (its log returns compounded, -splice as below) or -shiller",
    "              FILE (Shiller's monthly S&P as `month,price,dividend,cpi` -- monthly AVERAGES, for",
    "              comparison only)",
    "-volexit      THE VOLATILITY EXIT's rows (`VolExitRows`): the simple exit on the printed close at",
    "              1.5% / 2.0%, cash at the -fred rate, the 3x leg reset daily, each session joined to",
    "              the rate at its start and the calendar days it spans; the record, its one-year",
    "              block resamples (`volExitResamples`), their joint band. -closes FILE is a",
    "              `date,close,adj_close` CSV (Yahoo's printed and adjusted closes); -french FILE reads",
    "              CRSP's daily total return as both, having no printed close",
    "-volstate     THE VOLATILITY STATE's rows (`VolStateRows`) as one family on one day series: the",
    "              downside vol excess and the vol-timing edge on the series' log returns, the",
    "              volatility exit's three rows on the exit's inputs; the record, its one-year-block",
    "              resamples (`volStateResamples`), their joint band at -joint (default 0.05) x 5 / -of",
    "              (default 5). The chain's files are told apart by themselves: a French daily industry",
    "              file (-column NAME; its total return is the signal and the earnings, as -french is",
    "              to -volexit) or a `date,close,adj_close` CSV (the printed close the signal, the",
    "              adjusted close the earnings, its log return the two rows'); each -splice FILE -at",
    "              DATE continues the chain with that file's sessions dated after DATE. The window's",
    "              first session is the base: the sessions dated after -from through -to, each with the",
    "              -fred rate at its previous session and the calendar days since it. The last spliced",
    "              file's sessions alone are read again, banded the same way, and printed after the",
    "              graded rows as the reference rows (`VolStateReferenceRows`, series -refseries,",
    "              default -series): QQQ's own window beside the long record",
    "-ledgerrows   THE LEDGER ROWS (`LedgerRows`): the volatility exit's surface over `VolExitGrid` and",
    "              its parts at 1.5% / 2.0%, the coupling of the volatility with the price, and the",
    "              declines of 10% or more a century, on a -volstate chain or -french FILE (CRSP's daily",
    "              total return as the close, the earnings and, as its log, the return the coupling and",
    "              the declines read); the sessions after -from through -to, each with the -fred rate at",
    "              its previous session and the calendar days since it; the record, its one-year-block",
    "              resamples (`ledgerResamples`) and a joint band per family (`LedgerFamilies`) at -joint",
    "              (default 0.05); then the one-day reversion (`ledgerLag1Of`) on the window's sessions",
    "              after the lag cut (`UnlagCut`) alone, its own resamples and band",
    "-basket       THE BASKET RULER for `-basketruler` (`basketRulerTsv`): -closes WIDE.csv is one row a",
    "              session, `date`, the index's column named by its ticker, then one column per name,",
    "              a name's cell empty before its listing (adjusted closes, dividends reinvested);",
    "              -closesdate is the day they were taken, since an adjusted series is recomputed on",
    "              every later distribution. -from / -to bound the window (default the file's);",
    "              -minsessions (default 252) is the sessions a name needs to enter the per-name and",
    "              pair ranges. Writes -out, or prints",
    "-profits      THE PROFIT DECLINE ROWS (`ProfitDeclineRows`): the -fred `observation_date,SERIES`",
    "              quarterly levels (corporate profits after tax, CPATAX) read by `profitDeclineOf`, the",
    "              rows of `profits-2026-10-19.tsv` for -set; the episodes to stderr",
    "-sectors DIR  THE SECTOR ROWS instead: Ken French's `10_Industry_Portfolios.CSV`,",
    "              `49_Industry_Portfolios.CSV` and `F-F_Research_Data_Factors.CSV` in DIR as the",
    "              library publishes them (unzipped), the first monthly block of each; prints the",
    "              momentum, trend and shape rows of `sectors-2026-09-30.tsv`, with 5-95",
    "              block-bootstrap bands where a row has one",
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
    "-tips         THE REAL SIDE's rows (item 60), a mode of its own: -cpi CPIAUCSL -cpicentury",
    "              CPIAUCNS -real DFII10 -breakeven T10YIE -nominal DGS10 -fred DFF -french <daily",
    "              factors> -set S [-from -to] (the real window) [-cpifrom -cpito] (the CPI and",
    "              real-rate window; the century file is read whole) [-resamples -seed -joint -of",
    "              -header]; six groups (`CpiBandRows`, `CpiCenturyRows`, `RealYieldBandRows`,",
    "              `BreakevenBandRows`, `TipsBandRows`, `RealRateBandRows`), each on its own record",
    "              with its own joint band at the mode's share of -of (default 16, the mode's graded",
    "              rows); the reported rows go to stderr",
    "-cpipersist   THE CPI PERSISTENCE ROW (`CpiPersistenceRow`, `cpiPersistence`) of -cpi over",
    "              [-cpifrom -cpito] for -set, the rows of `cpipersist-2026-10-12.tsv`: the record's",
    "              own reading, no resampling (the statistic straddles a block join in every pair),",
    "              graded among the world's single histories of the window's length [-header]",
    "-stockbond    THE STOCK-BOND ROWS (`StockBondBandRows`, `CpiNewsRow`) for -set, the rows of",
    "              `stockbond-2026-10-18.tsv`: -nominal DGS10 as a par bond and -french <daily factors>",
    "              over 21-session months of the sessions both trade in [-from -to], each month placed",
    "              by -cpi's year-over-year rate of the calendar month before its first session; and",
    "              the CPI news row over [-cpifrom -cpito]; two groups with their own joint bands at the",
    "              mode's share of -of (default 5) [-resamples -seed -joint -header]; the reported rows",
    "              (`StockBondReportedRows`) go to stderr",
    "-bondstress   THE BOND STRESS SHARE ROW (`BondStressRow`) for -set, a row of",
    "              `recordbands-2026-09-26.tsv`: the spiral's stress index run on -fred DGS20 as a",
    "              par bond of -years (20) over every session in [-from -to], a return spanning more",
    "              than ten calendar days dropped with the state carried across the gap; the share of",
    "              sessions above 0.5 after a one-year burn-in, in percent, its one-year-block",
    "              resamples and their band at -of (default 1) [-resamples -seed -joint -header]",
  )

  /** THE LAG-FREE RECORD's switches for the run (`MarketSim.unlagReturns`): `-recorded` reads
    * every source as recorded; `-index FILE` and `-fund FILE` (each repeatable) declare a
    * Yahoo-format file holding sessions on or before `MarketSim.UnlagCut` a computed index, read
    * lag-free, or a fund, read as recorded. */
  final case class Lag(recorded: Boolean, index: Vector[String], fund: Vector[String])

  /** The switches every reader of one run consults, read once in `main`: the lag-free record's and
    * THE RESAMPLING BLOCK (`-block 252|756`), one or three years of the series' own observations
    * (sessions, months, quarters), the record-scale readings `-scales` takes; `None`, each mode's
    * own blocks (a year of sessions, five years of months), the fixtures'. */
  final case class Run(lag: Lag, block: Option[Int], raw: Boolean = false)

  def blockOf(args: Seq[String]): Option[Int] =
    args.indexOf("-block") match
      case -1 => None
      case i =>
        args.lift(i + 1) match
          case Some("252") => Some(1)
          case Some("756") => Some(3)
          case other       => usage(s"-block takes 252 or 756 (one or three years), not [${other.getOrElse("")}]")

  def lagOf(args: Seq[String]): Lag =
    @annotation.tailrec
    def go(rest: List[String], acc: Lag): Lag = rest match
      case "-recorded" :: t                          => go(t, acc.copy(recorded = true))
      case "-index" :: f :: t                        => go(t, acc.copy(index = acc.index :+ f))
      case "-fund" :: f :: t                         => go(t, acc.copy(fund = acc.fund :+ f))
      case a :: Nil if a == "-index" || a == "-fund" => usage(s"$a wants a file")
      case _ :: t                                    => go(t, acc)
      case Nil                                       => acc
    val l = go(args.toList, Lag(false, Vector.empty, Vector.empty))
    l.index.find(l.fund.contains).foreach(f => usage(s"$f is declared both -index and -fund"))
    l

  /** `MarketSim.unlagReturns` on a source's taken sessions, `(date, log return)`, unless the run
    * reads the record as recorded. */
  def unlagged(days: Vector[(String, Double)])(using run: Run): Vector[(String, Double)] =
    if run.lag.recorded then days
    else
      val out = MarketSim.unlagReturns(days.map(_._1), days.map(_._2).toArray)
      days.zip(out).map((d, x) => (d._1, x))

  /** Whether a Yahoo-format file's taken sessions (the first dated `first`) are a computed index's,
    * read lag-free: declared `-index`; a fund's (`-fund`), or a file with no session on or before
    * `MarketSim.UnlagCut`, is read as recorded; an undeclared file with such a session is refused. */
  def yahooIsIndex(file: String, first: Option[String])(using run: Run): Boolean =
    val l = run.lag
    if l.recorded || l.fund.contains(file) then false
    else if l.index.contains(file) then true
    else if first.exists(_ <= MarketSim.UnlagCut) then
      usage(s"$file holds sessions on or before ${MarketSim.UnlagCut}: declare it -index $file (a computed index, " +
            s"read lag-free) or -fund $file (read as recorded)")
    else false

  /** The `(lo, hi]` date range each file of a chain gives: the first file through the first
    * splice's date, each splice file after its own date through the next's; the dates must rise. */
  def takenRanges(file: String, splices: Vector[(String, String)]): Vector[(String, Option[String], Option[String])] =
    if splices.zip(splices.drop(1)).exists((a, b) => b._2 <= a._2) then
      usage("each -splice's -at date must follow the one before")
    (file, None, splices.headOption.map(_._2)) +:
      splices.zipWithIndex.map((s, k) => (s._1, Some(s._2), splices.lift(k + 1).map(_._2)))

  /** Whether a date lies in a taken range `(lo, hi]`. */
  def inTaken(d: String, lo: Option[String], hi: Option[String]): Boolean =
    lo.forall(d > _) && hi.forall(d <= _)

  /** A flag's value: the argument after its first appearance. */
  def opt(args: Seq[String], flag: String): Option[String] =
    val i = args.indexOf(flag)
    if i < 0 then None else args.lift(i + 1)

  /** A count or a seed (non-negative), or the usage line with `msg`. */
  def countOf(args: Seq[String], flag: String, default: Long, msg: String): Long =
    opt(args, flag).fold(default)(v => v.toLongOption.filter(_ >= 0).getOrElse(usage(msg)))

  /** A number, or the usage line with `msg`. */
  def numberOf(args: Seq[String], flag: String, default: Double, msg: String): Double =
    opt(args, flag).fold(default)(v => v.toDoubleOption.getOrElse(usage(msg)))

  def readText(file: String): String =
    scala.util.Try(file.asPath.contentAsString).getOrElse(usage(s"$file: cannot be read"))

  def linesOf(text: String): Vector[String] = text.linesIterator.toVector

  def firstLine(text: String): String = text.linesIterator.nextOption().getOrElse("")

  def digits(s: String): Boolean = s.forall(c => c >= '0' && c <= '9')

  def isoOf8(d8: String): String = s"${d8.take(4)}-${d8.slice(4, 6)}-${d8.slice(6, 8)}"

  /** The count of `v`'s leading elements `p` holds for, `v` partitioned by `p`. */
  def partitionPoint[A](v: IndexedSeq[A])(p: A => Boolean): Int =
    @annotation.tailrec
    def go(lo: Int, hi: Int): Int =
      if lo >= hi then lo
      else
        val mid = (lo + hi) >>> 1
        if p(v(mid)) then go(mid + 1, hi) else go(lo, mid)
    go(0, v.length)

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

  /** What a daily file is to the lag-free record: a Yahoo-format file (an index or a fund, as
    * declared), a rate series (as recorded) or a French industry file (a computed index). */
  enum Layout:
    case Yahoo, Rate, Industry

  /** `(date, log return)` of a daily series in whichever of the three layouts the file is in, as
    * recorded: a `-yahoo` CSV (`readYahoo`), FRED's `observation_date,DGSn` yields
    * (`readTreasury`) or Ken French's daily industry file (`readIndustry`, the `-column` named). */
  def readDaily(file: String, column: Option[String]): (Layout, Vector[(String, Double)]) =
    val text = readText(file)
    val head = firstLine(text).split(",", -1).map(_.trim)
    if head.contains("dlog_adj_close") then (Layout.Yahoo, readYahoo(file))
    else if head.length == 2 && head(0) == "observation_date" && head(1).startsWith("DGS") then
      val years = head(1).drop(3).toDoubleOption.getOrElse(usage(s"$file: [${head(1)}] is not a DGSn series"))
      (Layout.Rate, readTreasury(linesOf(text), years))
    else if text.take(400).toLowerCase.contains("industry portfolios") then
      val col = column.getOrElse(usage(s"$file: an industry file needs -column NAME"))
      (Layout.Industry, readIndustry(file, text, col))
    else usage(s"$file: not a dlog_adj_close CSV, a FRED DGSn series or a French industry file")

  /** A daily series continued by each `-splice FILE -at DATE` in turn: the returns so far dated on
    * or before the date, then that file's dated after it; each file's taken sessions read lag-free
    * where it is a computed index (`unlagged`: an industry file, a Yahoo file declared `-index`). */
  def readDailyChain(file: String, splices: Vector[(String, String)], column: Option[String])(
      using Run): Vector[(String, Double)] =
    takenRanges(file, splices).flatMap: (f, lo, hi) =>
      val (layout, all) = readDaily(f, column)
      val days = all.filter((d, _) => inTaken(d, lo, hi))
      val index = layout match
        case Layout.Industry => true
        case Layout.Yahoo    => yahooIsIndex(f, days.headOption.map(_._1))
        case Layout.Rate     => false
      if index then unlagged(days) else days

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
      // the closed form holds for a negative yield too (a real yield below zero); only zero divides
      if y != 0.0 then (c / y) * (1.0 - v) + v else 1.0 + c * t

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
  def readIndustry(file: String, text: String, column: String): Vector[(String, Double)] =
    readIndustrySimple(file, text, column).map((d, x) => (d, MarketSim.lnDet(1.0 + x)))

  /** The index of `column` in the header of Ken French's daily value-weighted industry block. */
  def industryColumn(file: String, text: String, column: String): Int =
    val header = text.linesIterator.dropWhile(!_.contains("Value Weighted Returns -- Daily")).drop(1).nextOption()
      .getOrElse(usage(s"$file: no daily value-weighted block")).split(",", -1).map(_.trim)
    val k = header.indexOf(column)
    if k < 0 then usage(s"$file: no column [$column]; the file has ${header.drop(1).mkString(" ")}") else k

  /** `(date, simple return)` for every dated row of the daily value-weighted block of Ken French's
    * industry file whose `column` holds a return (-99.99 rows skipped), as recorded. */
  def readIndustrySimple(file: String, text: String, column: String): Vector[(String, Double)] =
    val col = industryColumn(file, text, column)
    text.linesIterator.dropWhile(!_.contains("Value Weighted Returns -- Daily")).drop(2)
      .map(_.split(",", -1).map(_.trim))
      .takeWhile(f => f(0).length == 8 && digits(f(0)))
      .flatMap(f => f.lift(col).flatMap(_.toDoubleOption).filter(_ > -99.0).map(v => (isoOf8(f(0)), v / 100.0)))
      .toVector

  /** `(month, level at the month's last session)` from dated daily log returns: the returns summed
    * from the first, the level `expDet` of the sum, so the twins read the same levels. */
  def logMonthEnds(days: Vector[(String, Double)]): Vector[(String, Double)] =
    val sums = days.scanLeft(0.0)((c, dx) => c + dx._2).drop(1)
    days.indices.toVector.flatMap { k =>
      val d = days(k)._1
      if k + 1 == days.length || days(k + 1)._1.take(7) != d.take(7) then Some((d.take(7), MarketSim.expDet(sums(k))))
      else None
    }

  /** `(date, Mkt-RF + RF in percent)` for every dated row of Ken French's daily factor file, the
    * market read lag-free (`unlagged`, on its log total return, the whole file its taken range); a
    * session after the cut keeps its recorded figure to the bit. */
  def readFrench(file: String)(using run: Run): Vector[(String, Double)] =
    val raw  = readFrenchRecorded(file)
    val logs = raw.map((d, x) => (d, MarketSim.lnDet(1.0 + x / 100.0)))
    raw.zip(unlagged(logs)).map { case ((d, x), (_, r)) =>
      (d, if !run.lag.recorded && d <= MarketSim.UnlagCut then 100.0 * (MarketSim.expDet(r) - 1.0) else x)
    }

  /** `(date, Mkt-RF + RF in percent)` for every dated row of Ken French's daily factor file, as
    * recorded. */
  def readFrenchRecorded(file: String): Vector[(String, Double)] =
    linesOf(readText(file)).flatMap { l =>
      val f = l.split(",", -1).map(_.trim)
      val d = f.headOption.getOrElse("")
      if d.length != 8 || !digits(d) || f.length < 5 then None
      else
        for mkt <- f(1).toDoubleOption; rf <- f(4).toDoubleOption
        yield (isoOf8(d), mkt + rf)
    }

  /** `(month, total-return level at the month's last session)` from Ken French's daily factors:
    * Mkt-RF + RF compounded from the window's first session, the level at each calendar month's
    * last session. */
  def readFrenchMonthEnds(file: String, from: String, to: String)(using Run): Vector[(String, Double)] =
    val days = readFrench(file).filter((d, _) => d >= from && d <= to)
    val levels = days.scanLeft(1.0)((level, dx) => level * (1.0 + dx._2 / 100.0)).drop(1)
    days.indices.toVector.flatMap { k =>
      val d = days(k)._1
      if k + 1 == days.length || days(k + 1)._1.take(7) != d.take(7) then Some((d.take(7), levels(k))) else None
    }

  /** `(month, total-return level)` from Shiller's monthly S&P as a `month,price,dividend,cpi` CSV
    * (the workbook's Data sheet: Date, P, D, CPI): the level compounds each month's price ratio
    * with the dividend rate over twelve on the prior price, a blank dividend carrying the last one. */
  def readShillerMonthly(file: String): Vector[(String, Double)] =
    val rows = linesOf(readText(file)).drop(1).map(_.split(",", -1).map(_.trim)).filter(_.length >= 3)
    // (the last price, the last dividend, the level), month by month
    val start = (Double.NaN, Double.NaN, Double.NaN)
    rows.scanLeft((start, "")) { case (((prevP, lastD, level), _), f) =>
      val p = f(1).toDoubleOption.getOrElse(usage(s"$file: price [${f(1)}]"))
      val d = f(2).toDoubleOption.getOrElse(lastD)
      ((p, d, if prevP.isNaN then p else level * (p + d / 12.0) / prevP), f(0))
    }.drop(1).map((st, m) => (m, st._3))

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

  /** The rate on or before `d`: the last of `rates` (sorted by date) dated on or before it. */
  def rateOn(rates: Vector[(String, Double)], d: String): Double =
    val k = partitionPoint(rates)(_._1 <= d)
    if k == 0 then usage(s"no -fred rate on or before $d") else rates(k - 1)._2

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

  /** Days from 1970-01-01 back to an ISO date (Hinnant's `civil_from_days`), `civilDays`' inverse. */
  def civilDate(z0: Long): String =
    val z   = z0 + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp  = (5 * doy + 2) / 153
    val d   = doy - (153 * mp + 2) / 5 + 1
    val m   = if mp < 10 then mp + 3 else mp - 9
    val y   = yoe + era * 400 + (if m <= 2 then 1 else 0)
    f"$y%04d-$m%02d-$d%02d"

  /** `(date, printed close, adjusted close)` for every session of a `date,close,adj_close` CSV. */
  def readCloses(file: String): Vector[(String, Double, Double)] =
    file.asPath.lines.toVector.drop(1).filter(_.trim.nonEmpty).map { l =>
      val f = l.split(',').map(_.trim)
      def num(k: Int): Double = f.lift(k).flatMap(_.toDoubleOption).getOrElse(usage(s"$file: unreadable row [$l]"))
      (f(0), num(1), num(2))
    }

  /** `readCloses`, a file declared `-index` read lag-free: the printed close's and the adjusted
    * close's log returns each `unlagged`, the levels compounded again from the first row's. */
  def closesLagFree(file: String)(using Run): Vector[(String, Double, Double)] =
    val levels = readCloses(file)
    if !yahooIsIndex(file, levels.lift(1).map(_._1)) then levels
    else
      def leg(k: Int): Vector[Double] =
        def at(i: Int): Double = if k == 0 then levels(i)._2 else levels(i)._3
        val days = (1 until levels.length).toVector.map(i => (levels(i)._1, MarketSim.lnDet(at(i) / at(i - 1))))
        unlagged(days).scanLeft(at(0))((last, dr) => last * MarketSim.expDet(dr._2))
      val (close, total) = (leg(0), leg(1))
      levels.indices.toVector.map(i => (levels(i)._1, close(i), total(i)))

  /** `(date, adjusted close)` of a CSV whose header names a `date` and an `adj_close` column. */
  def readAdjCloses(file: String): Vector[(String, Double)] =
    val lines = linesOf(readText(file))
    val head  = lines.headOption.getOrElse("").split(",", -1).map(_.trim)
    def col(name: String): Int =
      val k = head.indexOf(name)
      if k < 0 then usage(s"$file: no [$name] column") else k
    val (d, a) = (col("date"), col("adj_close"))
    lines.drop(1).filter(_.trim.nonEmpty).map { l =>
      val f = l.split(",", -1).map(_.trim)
      (f(d), f.lift(a).flatMap(_.toDoubleOption).getOrElse(usage(s"$file: unreadable row [$l]")))
    }

  /** THE TIMING ROWS (`-timing`): the four rows of `timingOfMonthly` on month-end levels, from
    * `-french FILE` (CRSP's daily factors, the ruler), `-yahoo FILE` (its log returns, continued by
    * each `-splice FILE -at DATE`) or `-shiller FILE` (monthly AVERAGES, for the comparison the
    * fixture's header states), over `-from`..`-to` (YYYY-MM-DD or YYYY-MM), for the set named;
    * under `-block` its resamples too. */
  def timingMode(args: Vector[String])(using run: Run): Unit =
    val from = opt(args, "-from").getOrElse("")
    val to = opt(args, "-to").getOrElse("9999-99-99")
    val set = opt(args, "-set").getOrElse(usage("-set is required"))
    val series = opt(args, "-series").getOrElse(usage("-series is required"))
    val rows = (opt(args, "-french"), opt(args, "-shiller"), opt(args, "-yahoo")) match
      case (Some(file), None, None) => readFrenchMonthEnds(file, from, to)
      case (None, Some(file), None) => readShillerMonthly(file).filter((m, _) => m >= from.take(7) && m <= to.take(7))
      case (None, None, Some(file)) =>
        logMonthEnds(readDailyChain(file, splicesOf(args), opt(args, "-column")).filter((d, _) => d >= from && d <= to))
      case _ => usage("-timing wants exactly one of -french FILE, -yahoo FILE and -shiller FILE")
    if rows.length < 24 then usage("the window holds fewer than two years of months")
    val levels = rows.map(_._2).toArray
    val window = s"${rows.head._1}..${rows.last._1}"
    run.block match
      case Some(y) =>
        printBandHeader(args)
        val (resamples, seed, joint) = resamplingOf(args)
        val reads = levelResamples(levels, 12, y, resamples, seed)(MarketSim.timingOfMonthly)
        printResampledRows(set, series, window, levels.length, MarketSim.TimingRows,
                           MarketSim.timingOfMonthly(levels), reads, joint)
      case None =>
        if args.contains("-header") then println("set\trow\tseries\twindow\tn\trecord")
        for (name, value) <- MarketSim.TimingRows.zip(MarketSim.timingOfMonthly(levels)) do
          println(f"$set%s\t$name%s\t$series%s\t$window%s\t${levels.length}%d\t$value%.6f")

  /** `-satellite`: THE SATELLITE ROWS (`SatelliteRows`, `satRowsOf`) of `-sat FILE` against
    * `-primary FILE` (each a CSV with `date` and `adj_close` columns, funds read as recorded), on
    * their shared sessions over -from..-to: the record alone; under `-block` the joined sessions'
    * resamples too, both legs taking the same blocks and compounding again from their first levels. */
  def satelliteMode(args: Vector[String])(using run: Run): Unit =
    def req(flag: String): String = opt(args, flag).getOrElse(usage(s"-satellite wants $flag"))
    val (set, series) = (req("-set"), opt(args, "-series").getOrElse("SPY+QQQ"))
    val from = opt(args, "-from").getOrElse("")
    val to = opt(args, "-to").getOrElse("9999")
    val primary = readAdjCloses(req("-primary"))
    val sat = readAdjCloses(req("-sat")).toMap
    val joined = primary.filter((d, _) => d >= from && d <= to).flatMap((d, p) => sat.get(d).map(s => (d, p, s)))
    if joined.length <= 252 then usage(s"the joined window holds ${joined.length} sessions")
    val lp = joined.map(_._2).toArray
    val ls = joined.map(_._3).toArray
    val window = s"${joined.head._1}..${joined.last._1}"
    val record = MarketSim.satRowsOf(lp, ls)
    val names = MarketSim.SatelliteRows
    run.block match
      case Some(y) =>
        printBandHeader(args)
        val (resamples, seed, joint) = resamplingOf(args)
        def logs(x: Array[Double]): Array[Double] = Array.tabulate(x.length - 1)(i => MarketSim.lnDet(x(i + 1) / x(i)))
        val (rp, rs) = (logs(lp), logs(ls))
        val idx = Array.tabulate(rp.length)(_.toDouble)
        def level(x0: Double, r: Array[Double], pick: Array[Double]): Array[Double] =
          x0 +: pick.scanLeft(0.0)((c, i) => c + r(i.toInt)).drop(1).map(c => x0 * MarketSim.expDet(c))
        val reads = MarketSim.seriesResamples(idx, 252, y, resamples, seed)(x =>
          MarketSim.satRowsOf(level(lp(0), rp, x), level(ls(0), rs, x)))
        printResampledRows(set, series, window, joined.length, names, record, reads, joint)
      case None =>
        if args.contains("-header") then println("set\trow\tseries\twindow\tn\trecord")
        for (name, value) <- names.zip(record) do
          println(f"$set%s\t$name%s\t$series%s\t$window%s\t${joined.length}%d\t$value%.6f")

  /** `-dividend`: THE DIVIDEND YIELD ROW (`DividendRow`) as `dividend-2026-09-02.tsv` reads it:
    * `-shiller FILE` (a `month,price,dividend,cpi` CSV of Shiller's monthly S&P) the mean of the
    * monthly dividend over price, in percent, over the months in -from..-to; or `-closes FILE` (a
    * `date,close,adj_close` CSV of a fund, read as recorded) the trailing-252-session distribution
    * yield off the adjusted-close-to-close ratio, averaged by calendar year over the sessions in
    * -from..-to, then over the years.  The record alone; under `-block` the yield LEVELS'
    * resamples too (the row is a mean of a persistent level), the sessions or months keeping their
    * dates. */
  def dividendMode(args: Vector[String])(using run: Run): Unit =
    val set  = opt(args, "-set").getOrElse(usage("-dividend wants -set"))
    val from = opt(args, "-from").getOrElse("")
    val to   = opt(args, "-to").getOrElse("9999")
    val (series, perYear, dated) =
      (opt(args, "-shiller"), opt(args, "-closes")) match
        case (Some(f), None) =>
          val dp = linesOf(readText(f)).drop(1).flatMap { l =>
            val c = l.split(",", -1).map(_.trim)
            for p <- c.lift(1).flatMap(_.toDoubleOption); d <- c.lift(2).flatMap(_.toDoubleOption)
            yield (c(0), 100.0 * d / p)
          }.filter((m, _) => m >= from.take(7) && m <= to.take(7))
          ("Shiller-S&P", 12, dp)
        case (None, Some(f)) =>
          val c = readCloses(f)
          def ratio(i: Int): Double = c(i)._3 / c(i)._2
          val y = (252 until c.length).toVector.map(t => (c(t)._1, 100.0 * (ratio(t) / ratio(t - 252) - 1.0)))
            .filter((d, _) => d >= from && d <= to)
          (opt(args, "-series").getOrElse("fund"), 252, y)
        case _ => usage("-dividend wants exactly one of -shiller FILE and -closes FILE")
    if dated.length < 12 then usage("-dividend: the window holds under a year of observations")
    val values = dated.map(_._2).toArray
    val years  = dated.map(_._1.take(4))
    // monthly: the mean; daily: the mean of each calendar year's mean, the dates kept in place
    def read(v: Array[Double]): Vector[Double] =
      if perYear == 12 then Vector(v.foldLeft(0.0)(_ + _) / v.length)
      else
        val means = v.indices.foldLeft((Vector.empty[Double], 0.0, 0)) { case ((ms, s, n), k) =>
          val (s1, n1) = (s + v(k), n + 1)
          if k + 1 == v.length || years(k + 1) != years(k) then (ms :+ s1 / n1, 0.0, 0) else (ms, s1, n1)
        }._1
        Vector(means.foldLeft(0.0)(_ + _) / means.length)
    val window = s"${dated.head._1}..${dated.last._1}"
    val names  = Vector(MarketSim.DividendRow)
    run.block match
      case Some(y) =>
        printBandHeader(args)
        val (resamples, seed, joint) = resamplingOf(args)
        val idx = Array.tabulate(values.length)(_.toDouble)
        val reads = MarketSim.seriesResamples(idx, perYear, y, resamples, seed)(x => read(x.map(i => values(i.toInt))))
        printResampledRows(set, series, window, values.length, names, read(values), reads, joint)
      case None =>
        if args.contains("-header") then println("set\trow\tseries\twindow\tn\trecord")
        println(f"$set%s\t${names(0)}%s\t$series%s\t$window%s\t${values.length}%d\t${read(values)(0)}%.6f")

  /** One source `-resampled` copies, read once: its rows by date in the file's order. */
  enum CopySource:
    /** French's daily factors, the lag-free text's lines; a dated line's cells after the date are
      * the session's */
    case French(lines: Vector[String])
    /** a dated CSV of levels (`date,close,adj_close[...]`, `date,adj_close,dlog_adj_close` or
      * `date,open,high,low,close,adj_close,volume`): its header and each session's cells by name */
    case Levels(head: Vector[String], rows: Vector[(String, Vector[Option[Double]])])
    /** FRED's `observation_date,NAME`: each observation's date and its value as printed */
    case Fred(head: String, rows: Vector[(String, String)])

  /** A level file's rows, lag-free where it is declared `-index`: the printed and adjusted closes
    * (or the adjusted alone) compounded again from the corrected returns. */
  def readLevelSource(file: String)(using Run): CopySource =
    val lines = linesOf(readText(file))
    val head  = lines.headOption.getOrElse("").split(",", -1).map(_.trim).toVector
    if !head.headOption.contains("date") then usage(s"-resampled: $file is not a dated CSV")
    val rows = lines.drop(1).filter(_.trim.nonEmpty).map { l =>
      val f = l.split(",", -1).map(_.trim)
      (f(0), (1 until head.length).toVector.map(k => f.lift(k).flatMap(_.toDoubleOption)))
    }
    def col(name: String): Option[Int] = head.indexOf(name) match
      case -1 => None
      case k  => Some(k - 1)
    val first = rows.lift(1).map(_._1)
    val fixed =
      if col("open").isEmpty && yahooIsIndex(file, first) then
        // the corrected levels, as the readers take them
        if closesLayout(file) then
          val lf = closesLagFree(file)
          rows.indices.toVector.map(i =>
            if i < lf.length then (rows(i)._1, rows(i)._2.updated(0, Some(lf(i)._2)).updated(1, Some(lf(i)._3))) else rows(i))
        else
          col("adj_close") match
            case Some(k) =>
              val days = unlagged(readYahoo(file))
              val x0 = rows(0)._2(k).getOrElse(usage(s"$file: no anchor level"))
              val lv = days.scanLeft(0.0)((c, dr) => c + dr._2).drop(1).map(c => x0 * MarketSim.expDet(c))
              rows.indices.toVector.map(i =>
                if i >= 1 && i - 1 < lv.length then (rows(i)._1, rows(i)._2.updated(k, Some(lv(i - 1)))) else rows(i))
            case None => rows
      else rows
    CopySource.Levels(head, fixed)

  /** `-resampled OUT`: THE RECORD'S BLOCK RESAMPLES AS FILES, for a ruler outside this tool (the
    * rows `-scales` cannot reach): `-n` copies (200) of every source, each in its own layout, at
    * `OUT/k/<the source's path>` (its file name where the path is absolute).  The first `-equity`
    * source's sessions in -from..-to are the master calendar; each copy draws its blocks of `-block`
    * years of sessions on it (`NumPyRNG(-seed)`, copy-major), and every source takes the same date
    * blocks: an `-equity` file's sessions re-dated and compounded again (French's factors and a
    * declared `-index` lag-free, the bar's open, high and low kept relative to its close, the volume
    * carried), a `-fred` file's observations moved as LEVELS (a persistent level keeps its
    * distribution), each to the block's session its date falls in, offset by its own days past it.
    * Rows outside the master's span are kept as recorded. */
  def resampledMode(args: Vector[String])(using run: Run): Unit =
    val out = args.lift(args.indexOf("-resampled") + 1).getOrElse(usage("-resampled wants an output directory"))
    val years = run.block.getOrElse(usage("-resampled wants -block 252 or 756"))
    val copies = countOf(args, "-n", 200, "-n wants a copy count").toInt
    val seed = countOf(args, "-seed", 20260918L, "-seed wants an integer")
    val from = opt(args, "-from").getOrElse("")
    val to = opt(args, "-to").getOrElse("9999")
    val sources = readCopySources(args)
    val master = sources.collectFirst { case (_, src) if !src.isInstanceOf[CopySource.Fred] => src } match
      case Some(src) => sourceDates(src).filter(d => d >= from && d <= to)
      case None      => usage("-resampled wants an -equity source, whose sessions are the calendar")
    val n = master.length
    val l = 252 * years
    if n <= l then usage(s"-resampled: the calendar holds $n sessions, no more than a block")
    val blocks = (n + l - 1) / l
    val bound = n - l + 1
    val rng = new NumPyRNG(seed)
    for k <- 0 until copies do
      val starts = Vector.fill(blocks)(rng.nextBoundedInt(bound))
      for (file, src) <- sources do
        // absolute as written, before any resolution against the working directory
        val asWritten = new java.io.File(file)
        val rel =
          if asWritten.isAbsolute then asWritten.getName
          else Iterator.iterate(file)(_.stripPrefix("./")).dropWhile(_.startsWith("./")).next()
        val path = s"$out/$k/$rel".asPath
        Option(path.getParent).foreach(d => java.nio.file.Files.createDirectories(d))
        path.write(copyText(src, master, starts, l))
    eprintln(s"$copies copies of ${sources.length} sources in $out: $n sessions ${master.head}..${master(n - 1)}, " +
             s"blocks of $l, seed $seed")

  /** `-resampled`'s sources in argument order: each `-equity FILE` (French's daily factors, read
    * lag-free unless `-recorded`, or a level file) and each `-fred FILE`. */
  def readCopySources(args: Vector[String])(using run: Run): Vector[(String, CopySource)] =
    @annotation.tailrec
    def go(rest: List[String], acc: Vector[(String, CopySource)]): Vector[(String, CopySource)] = rest match
      case "-equity" :: f :: t =>
        val src =
          if isFrenchFactors(f) then
            CopySource.French(linesOf(if run.lag.recorded then readText(f) else unlaggedFrench(f)))
          else readLevelSource(f)
        go(t, acc :+ (f, src))
      case "-fred" :: f :: t =>
        val lines = linesOf(readText(f))
        val rows = lines.drop(1).filter(_.trim.nonEmpty).flatMap { l =>
          val i = l.indexOf(',')
          if i < 0 then None else Some((l.take(i).trim, l.drop(i + 1).trim))
        }
        go(t, acc :+ (f, CopySource.Fred(lines.headOption.getOrElse(""), rows)))
      case a :: Nil if a == "-equity" || a == "-fred" => usage(s"$a wants a file")
      case _ :: t                                      => go(t, acc)
      case Nil                                         => acc
    go(args.toList, Vector.empty)

  /** Whether a file is French's daily factors (a dated row of five cells). */
  def isFrenchFactors(file: String): Boolean =
    linesOf(readText(file)).exists { l =>
      val f = l.split(",", -1)
      f.length == 5 && f(0).trim.length == 8 && digits(f(0).trim)
    }

  /** A source's session dates (ISO) in order; FRED's are its observation dates. */
  def sourceDates(src: CopySource): Vector[String] = src match
    case CopySource.French(lines) =>
      lines.flatMap { l =>
        val d = l.split(",", -1)(0).trim
        if d.length == 8 && digits(d) then Some(isoOf8(d)) else None
      }
    case CopySource.Levels(_, rows) => rows.map(_._1)
    case CopySource.Fred(_, rows)   => rows.map(_._1)

  /** The master position each target position reads: block `t / l`'s start plus the offset in it. */
  def sourcePosition(starts: Vector[Int], l: Int, t: Int): Int = starts(t / l) + t % l

  /** One copy of one source in its own layout (`resampledMode`).  A source is copied over its own
    * span inside the calendar's: a target session outside the dates the source holds there is not
    * written, nor one whose drawn session the source lacks. */
  def copyText(src: CopySource, master: Vector[String], starts: Vector[Int], l: Int): String = src match
    case CopySource.French(lines)      => copyFrench(lines, master, starts, l)
    case CopySource.Levels(head, rows) => copyLevels(head, rows, master, starts, l)
    case CopySource.Fred(head, rows)   => copyFred(head, rows, master, starts, l)

  /** The first and last of `dates` inside the calendar's span, `None` where none falls in it. */
  def spanIn(dates: Iterable[String], master: Vector[String]): Option[(String, String)] =
    val (lo, hi) = (master.head, master.last)
    val in = dates.filter(d => d >= lo && d <= hi)
    if in.isEmpty then None else Some((in.min, in.max))

  /** French's factors: the dated rows in the calendar's span re-dated, each target session the
    * cells of the session it draws. */
  def copyFrench(lines: Vector[String], master: Vector[String], starts: Vector[Int], l: Int): String =
    def dated(ln: String): Option[String] =
      val d = ln.split(",", -1)(0).trim
      if d.length == 8 && digits(d) then Some(isoOf8(d)) else None
    val byDate: Map[String, String] = lines.flatMap { ln =>
      val i = ln.indexOf(',')
      dated(ln).filter(_ => i >= 0).map(d => (d, ln.drop(i + 1)))
    }.toMap
    spanIn(byDate.keys, master) match
      case None => lines.mkString("\n") + "\n"
      case Some((lo, hi)) =>
        val (out, _) = lines.foldLeft((Vector.empty[String], false)) { case ((acc, placed), ln) =>
          if !dated(ln).exists(d => d >= lo && d <= hi) then (acc :+ ln, placed)
          else if placed then (acc, placed)
          else
            val drawn = master.indices.flatMap { t =>
              val target = master(t)
              if target < lo || target > hi then None
              else byDate.get(master(sourcePosition(starts, l, t))).map(rest => s"${target.replace("-", "")},$rest")
            }
            (acc ++ drawn, true)
        }
        out.mkString("\n") + "\n"

  /** FRED's observations in the calendar's span moved as levels: each to the target of the session
    * its date falls in, offset by its own days past it, inside the series' own span there. */
  def copyFred(head: String, rows: Vector[(String, String)], master: Vector[String], starts: Vector[Int],
               l: Int): String =
    spanIn(rows.map(_._1), master) match
      case None => (head +: rows.map((d, v) => s"$d,$v")).mkString("\n") + "\n"
      case Some((lo, hi)) =>
        def inside(d: String): Boolean = d >= lo && d <= hi
        val days = master.map(civilDays)
        // each observation in the span, by the master session it falls in
        val at = rows.filter((d, _) => inside(d)).map { (d, v) =>
          val k = partitionPoint(master)(_ <= d) - 1
          (k, civilDays(d) - days(k), v)
        }
        val kept = scala.collection.immutable.TreeMap.from(rows.filterNot((d, _) => inside(d)))
        val all = starts.zipWithIndex.foldLeft(kept) { case (acc, (s, b)) =>
          val a = partitionPoint(at)(_._1 < s)
          at.drop(a).takeWhile(_._1 < s + l).foldLeft(acc) { case (m, (k, off, v)) =>
            val target = b * l + (k - s)
            if target >= master.length then m
            else
              val d = civilDate(days(target) + off)
              if inside(d) then m.updated(d, v) else m
          }
        }
        head + "\n" + all.map((d, v) => s"$d,$v\n").mkString

  /** Each session's trailing median of the log volume over the last 252 sessions with volume (as
    * many as there are, early on): the trend a bar ruler detrends by, `None` without volume. */
  def volumeTrend(rows: Vector[(String, Vector[Option[Double]])], kv: Int): Vector[Option[Double]] =
    val lv = rows.map(_._2(kv).filter(_ > 0.0).map(MarketSim.lnDet))
    rows.indices.toVector.map { i =>
      val w = lv.slice(math.max(0, i - 251), i + 1).flatten.sorted(using Ordering.Double.TotalOrdering)
      val m = w.length
      if m == 0 then None else Some(if m % 2 == 1 then w(m / 2) else 0.5 * (w(m / 2 - 1) + w(m / 2)))
    }

  /** One session's cells at the chain's levels: the close columns at `level`, open, high and low
    * at their source session's ratios to its close, the rest (the volume) carried. */
  def levelCells(head: Vector[String], src: Vector[Option[Double]], level: Vector[Double],
                 levelCols: Vector[Int]): Vector[Option[Double]] =
    def col(name: String): Option[Int] = head.indexOf(name) match
      case -1 => None
      case k  => Some(k - 1)
    val atLevels = levelCols.foldLeft(src)((cells, k) => cells.updated(k, Some(level(k))))
    col("close").flatMap(c => src(c).map(srcC => (c, srcC))) match
      case Some((c, srcC)) =>
        Vector("open", "high", "low").flatMap(col)
          .foldLeft(atLevels)((cells, k) => cells.updated(k, src(k).map(x => level(c) * x / srcC)))
      case None => atLevels

  /** A level file's copy: the sessions in its span re-dated, the closes compounded again from the
    * returns the target sessions draw (from the level before the span), the sessions after it
    * continuing the chain on their own returns, the bar kept relative to its close, the volume
    * its session's ratio to its trailing median (`volumeTrend`) at the target date's median, so
    * the copy's volume keeps the record's secular level, each log column read back off the new
    * levels. */
  def copyLevels(head: Vector[String], rows: Vector[(String, Vector[Option[Double]])], master: Vector[String],
                 starts: Vector[Int], l: Int): String =
    def col(name: String): Option[Int] = head.indexOf(name) match
      case -1 => None
      case k  => Some(k - 1)
    val index: Map[String, Int] = rows.zipWithIndex.map((r, i) => (r._1, i)).toMap
    val levelCols = Vector("close", "adj_close").flatMap(col)
    def ret(i: Int, k: Int): Double =
      if i == 0 then 0.0
      else
        (rows(i)._2(k), rows(i - 1)._2(k)) match
          case (Some(a), Some(b)) => MarketSim.lnDet(a / b)
          case _                  => 0.0
    val span    = spanIn(rows.map(_._1), master)
    val lo0     = span.fold("9999")(_._1)
    val firstIn = rows.indexWhere(_._1 >= lo0) match
      case -1 => None
      case f  => Some(f)
    val before  = rows.take(firstIn.getOrElse(rows.length))
    val moved: Vector[(String, Vector[Option[Double]])] = (firstIn, span) match
      case (Some(f), Some((lo, hi))) =>
        // the levels the span starts from: the session before it, or its own first
        val level = (if f > 0 then rows(f - 1)._2 else rows(f)._2).map(_.getOrElse(Double.NaN)).toArray
        // the volume about its trailing median, re-applied at the target date's own median
        val volume = col("volume")
        val trend  = volume.map(kv => volumeTrend(rows, kv))
        def step(j: Int, date: String): Vector[Option[Double]] =
          levelCols.foreach(k => level(k) *= MarketSim.expDet(ret(j, k)))
          val cells = levelCells(head, rows(j)._2, level.toVector, levelCols)
          (volume, trend) match
            case (Some(kv), Some(tr)) =>
              val at = math.max(partitionPoint(rows)(_._1 <= date), 1) - 1
              val v = (rows(j)._2(kv), tr(j), tr(at)) match
                case (Some(v), Some(own), Some(there)) if v > 0.0 =>
                  Some(math.round(MarketSim.expDet(there + MarketSim.lnDet(v) - own)).toDouble)
                case (v, _, _) => v
              cells.updated(kv, v)
            case _ => cells
        val drawn = master.indices.toVector.flatMap { t =>
          val target = master(t)
          if target < lo || target > hi then None
          else index.get(master(sourcePosition(starts, l, t))).map(j => (target, step(j, target)))
        }
        val after = rows.indices.toVector.filter(j => rows(j)._1 > hi).map(j => (rows(j)._1, step(j, rows(j)._1)))
        drawn ++ after
      case _ => Vector.empty
    // the log columns read back off the levels, the first row's left empty
    val out = Vector(("dlog_adj_close", "adj_close"), ("dlog_close", "close")).foldLeft(before ++ moved) {
      case (o, (k, lv)) =>
        (col(k), col(lv)) match
          case (Some(kk), Some(lk)) =>
            o.indices.toVector.map { i =>
              val v =
                if i == 0 then None
                else
                  (o(i)._2(lk), o(i - 1)._2(lk)) match
                    case (Some(a), Some(b)) => Some(MarketSim.lnDet(a / b))
                    case _                  => None
              (o(i)._1, o(i)._2.updated(kk, v))
            }
          case _ => o
    }
    (head.mkString(",") +: out.map((d, cells) => s"$d,${cells.map(_.fold("")(shown)).mkString(",")}"))
      .map(_ + "\n").mkString

  /** The resampling a record-only mode reads under `-block`: `-resamples` (20000), `-seed`
    * (20260918) and `-joint` (0.05) from the arguments, as the band modes default them. */
  def resamplingOf(args: Seq[String]): (Int, Long, Double) =
    (countOf(args, "-resamples", 20000, "-resamples wants an integer").toInt,
     countOf(args, "-seed", 20260918L, "-seed wants a non-negative integer"),
     numberOf(args, "-joint", 0.05, "-joint wants a share in (0, 1)"))

  /** A band row's resample quantiles as the band line prints them (`%.6f`), and under `-raw` the
    * resamples themselves on the line before it -- `raw`, the record, each resample at full
    * precision -- for `-scales` to read the record's own vote on its sign and its knee
    * (`readScaleLines`).  Without `-raw` the output is as it was. */
  def bandQuantiles(record: Double, col: Vector[Double])(using run: Run): Vector[String] =
    if run.raw then println(("raw" +: MarketSim.rustDebug(record) +: col.map(MarketSim.rustDebug)).mkString("\t"))
    MarketSim.recordBandQuantiles(col).map(v => f"$v%.6f")

  /** A record-only mode's rows under `-block`, in the band modes' columns: each row's record, its
    * resamples' quantiles and a joint band over the rows at `joint`. */
  def printResampledRows(set: String, series: String, window: String, n: Int, names: Vector[String],
                         record: Vector[Double], reads: Vector[Vector[Double]], joint: Double)(using run: Run): Unit =
    val (c, edges) = MarketSim.recordBandJoint(reads, names.indices.toVector, joint)
    for (k, (lo, hi)) <- names.indices.zip(edges) do
      val qs = bandQuantiles(record(k), reads.map(_(k)))
      println(f"$set%s\t${names(k)}%s\t$series%s\t$window%s\t$n%d\t${reads.length}%d\t${record(k)}%.6f\t" +
              qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")

  /** `read` on block resamples of a positive level series (`perYear` observations a year): its
    * log changes resampled in blocks of `years` years, the levels compounded again from the first. */
  def levelResamples(levels: Array[Double], perYear: Int, years: Int, resamples: Int, seed: Long)(
      read: Array[Double] => Vector[Double]): Vector[Vector[Double]] =
    if levels.exists(_ <= 0.0) then usage("a level series resampled by its log changes must stay positive")
    val d = Array.tabulate(levels.length - 1)(i => MarketSim.lnDet(levels(i + 1) / levels(i)))
    MarketSim.seriesResamples(d, perYear, years, resamples, seed)(x =>
      read(levels(0) +: x.scanLeft(0.0)(_ + _).drop(1).map(c => levels(0) * MarketSim.expDet(c))))

  /** The record-band column header, when the arguments ask for it (`-header`). */
  def printBandHeader(args: Seq[String]): Unit =
    if args.contains("-header") then
      println("set\trow\tseries\twindow\tn\tresamples\trecord\t" +
              MarketSim.RecordBandPcts.map(p => s"p$p").mkString("\t") + "\tjointC\tjointLo\tjointHi")

  /** THE VOLATILITY EXIT's rows (`-volexit`): the record's sessions over `-from`..`-to`, each the
    * printed close's and the total return's simple returns, the `-fred` rate at the previous
    * session's date (the last one known on or before it), and the calendar days since that session;
    * the record and its joint band over the three rows at `-joint` x 3 / `-of`. */
  def volexitMode(args: Vector[String])(using run: Run): Unit =
    def req(flag: String): String = opt(args, flag).getOrElse(usage(s"-volexit wants $flag"))
    val (from, to, set, series) = (req("-from"), req("-to"), req("-set"), req("-series"))
    val resamples = countOf(args, "-resamples", 20000, "-resamples wants an integer").toInt
    val seed = countOf(args, "-seed", 20260918L, "-seed wants an integer")
    val joint = numberOf(args, "-joint", 0.10, "-joint wants a share")
    val of = countOf(args, "-of", 3, "-of wants a row count").toInt
    // (date, printed close level, total-return level)
    val all: Vector[(String, Double, Double)] = (opt(args, "-closes"), opt(args, "-french")) match
      case (Some(f), None) => closesLagFree(f)
      case (None, Some(f)) =>
        val days = readFrench(f)
        days.zip(days.scanLeft(1.0)((level, dx) => level * (1.0 + dx._2 / 100.0)).drop(1)).map((dx, lv) => (dx._1, lv, lv))
      case _ => usage("-volexit wants exactly one of -closes FILE and -french FILE")
    val levels = all.filter((d, _, _) => d >= from && d <= to)
    val rates = readFred(req("-fred"))
    val days = (1 until levels.length).toVector.map { i =>
      val (d0, c0, t0) = levels(i - 1)
      val (d1, c1, t1) = levels(i)
      MarketSim.VolExitDay(c1 / c0 - 1.0, t1 / t0 - 1.0, rateOn(rates, d0), (civilDays(d1) - civilDays(d0)).toDouble)
    }
    val window = s"${levels(1)._1}..${levels.last._1}"
    val record = MarketSim.volExitOf(days)
    val reads = MarketSim.volExitResamples(days, resamples, seed, run.block)
    eprintln(f"$series%s: ${days.length}%d sessions $window%s; timing ${record(0)}%.4f, interaction ${record(1)}%.4f, high-vol fifth ${record(2)}%.4f; " +
             s"$resamples resamples, seed $seed")
    printBandHeader(args)
    val ks = Vector(0, 1, 2)
    val (c, edges) = MarketSim.recordBandJoint(reads, ks, joint * 3.0 / of)
    for (k, (lo, hi)) <- ks.zip(edges) do
      val qs = bandQuantiles(record(k), reads.map(_(k)))
      println(f"$set%s\t${MarketSim.VolExitRows(k)}%s\t$series%s\t$window%s\t${days.length}%d\t$resamples%d\t${record(k)}%.6f\t" +
              qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")

  /** One session of a `-volstate` chain: the date of the session before it, its own, the printed
    * close's and the total return's simple returns, and the total return's log return. */
  final case class StateSession(prev: String, date: String, closeRet: Double, totalRet: Double, logRet: Double)

  /** The sessions of one file of a `-volstate` chain, after its first, as recorded, in the layout the
    * file itself tells, and whether it is a French industry file: a French daily industry file (the
    * `-column` named; its total return is every one of the three returns) or a
    * `date,close,adj_close` CSV (the printed close's return the signal, the adjusted close's the
    * earnings and, as `lnDet`, the log return). */
  def readStateSessions(file: String, column: Option[String]): (Boolean, Vector[StateSession]) =
    val text = readText(file)
    if text.take(400).toLowerCase.contains("industry portfolios") then
      val col  = column.getOrElse(usage(s"$file: an industry file needs -column NAME"))
      val rows = readIndustrySimple(file, text, col)
      (true, (1 until rows.length).toVector.map(i =>
        StateSession(rows(i - 1)._1, rows(i)._1, rows(i)._2, rows(i)._2, MarketSim.lnDet(1.0 + rows(i)._2))))
    else if firstLine(text).trim == "date,close,adj_close" then
      val levels = readCloses(file)
      (false, (1 until levels.length).toVector.map { i =>
        val (d0, c0, t0) = levels(i - 1)
        val (d1, c1, t1) = levels(i)
        StateSession(d0, d1, c1 / c0 - 1.0, t1 / t0 - 1.0, MarketSim.lnDet(t1 / t0))
      })
    else usage(s"$file: not a French industry file or a date,close,adj_close CSV")

  /** A `-volstate` chain: the first file's sessions, continued by each `-splice FILE -at DATE` in
    * turn, as `readDailyChain` continues a daily series, each file's taken sessions read lag-free
    * where it is a computed index (`stateLagFree`). */
  def readStateChain(file: String, splices: Vector[(String, String)], column: Option[String])(
      using Run): Vector[StateSession] =
    takenRanges(file, splices).flatMap: (f, lo, hi) =>
      val (industry, all) = readStateSessions(f, column)
      val days = all.filter(s => inTaken(s.date, lo, hi))
      if industry || yahooIsIndex(f, days.headOption.map(_.date)) then stateLagFree(days) else days

  /** A computed index's taken state sessions read lag-free (`unlagged`): the adjusted close's log
    * return, and the printed close's in log, each corrected, the simple returns read back off them;
    * a session after the cut as recorded. */
  def stateLagFree(days: Vector[StateSession])(using run: Run): Vector[StateSession] =
    val total = unlagged(days.map(s => (s.date, s.logRet)))
    val close = unlagged(days.map(s => (s.date, MarketSim.lnDet(1.0 + s.closeRet))))
    days.zip(total.zip(close)).map { case (s, ((_, lt), (_, lc))) =>
      if run.lag.recorded || s.date > MarketSim.UnlagCut then s
      else s.copy(closeRet = MarketSim.expDet(lc) - 1.0, totalRet = MarketSim.expDet(lt) - 1.0, logRet = lt)
    }

  /** One session's `VolStateDay`: its log return and the exit's inputs, the rate at its previous
    * session and the calendar days since it. */
  def stateDayOf(s: StateSession, rates: Vector[(String, Double)]): MarketSim.VolStateDay =
    MarketSim.VolStateDay(s.logRet, MarketSim.VolExitDay(s.closeRet, s.totalRet, rateOn(rates, s.prev),
                                                         (civilDays(s.date) - civilDays(s.prev)).toDouble))

  /** THE VOLATILITY STATE's rows (`-volstate`): the chain's sessions dated after `-from` through
    * `-to`, each its log return and the exit's inputs (the `-fred` rate at the previous session's
    * date, the calendar days since it), read by `volStateOf`; the record, its resamples and their
    * joint band over the five rows at `-joint` x 5 / `-of`; then the last spliced file's sessions
    * alone, read and banded the same way, as the reference rows. */
  def volStateMode(args: Vector[String])(using run: Run): Unit =
    def req(flag: String): String = opt(args, flag).getOrElse(usage(s"-volstate wants $flag"))
    val (from, to, set, series) = (req("-from"), req("-to"), req("-set"), req("-series"))
    val refseries = opt(args, "-refseries").getOrElse(series)
    val resamples = countOf(args, "-resamples", 20000, "-resamples wants an integer").toInt
    val seed = countOf(args, "-seed", 20260918L, "-seed wants an integer")
    val joint = numberOf(args, "-joint", 0.05, "-joint wants a share")
    val of = countOf(args, "-of", 5, "-of wants a row count").toInt
    val splices = splicesOf(args)
    val chain = readStateChain(req("-yahoo"), splices, opt(args, "-column"))
    val rates = readFred(req("-fred"))
    val inWindow = chain.filter(s => s.date > from && s.date <= to)
    if inWindow.length <= 252 then
      usage(s"the window holds ${inWindow.length} sessions; a block bootstrap needs more than a year")
    val alpha = joint * 5.0 / of
    printBandHeader(args)
    // the family on one span: the record, its resamples, their joint band, printed under `names`
    def printFamily(names: Vector[String], label: String, sessions: Vector[StateSession]): Unit =
      val days   = sessions.map(stateDayOf(_, rates))
      val window = s"${sessions.head.date}..${sessions.last.date}"
      val record = MarketSim.volStateOf(days)
      val reads  = MarketSim.volStateResamples(days, resamples, seed, run.block)
      eprintln(f"$label%s: ${days.length}%d sessions $window%s; downside vol excess ${record(0)}%.6f, vol-timing edge ${record(1)}%.6f, " +
               f"exit timing ${record(2)}%.6f, exit 3x interaction ${record(3)}%.6f, high-vol fifth ${record(4)}%.6f; " +
               s"$resamples resamples, seed $seed")
      val ks = Vector(0, 1, 2, 3, 4)
      val (c, edges) = MarketSim.recordBandJoint(reads, ks, alpha)
      for (k, (lo, hi)) <- ks.zip(edges) do
        val qs = bandQuantiles(record(k), reads.map(_(k)))
        println(f"$set%s\t${names(k)}%s\t$label%s\t$window%s\t${days.length}%d\t$resamples%d\t${record(k)}%.6f\t" +
                qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")
    printFamily(MarketSim.VolStateRows, series, inWindow)
    splices.lastOption.foreach { (_, at) =>
      val tail = inWindow.filter(_.date > at)
      if tail.length > 252 then printFamily(MarketSim.VolStateReferenceRows, refseries, tail)
    }

  /** THE LEDGER ROWS (`-ledgerrows`): the chain's sessions dated after `-from` through `-to`, each
    * its log return and the exit's inputs, as `-volstate` reads them, or `-french`'s market total
    * return as every one of the three; read by `ledgerReadingsOf`; the record, its resamples and a
    * joint band at `-joint` over each of `LedgerFamilies`. */
  def ledgerRowsMode(args: Vector[String])(using run: Run): Unit =
    def req(flag: String): String = opt(args, flag).getOrElse(usage(s"-ledgerrows wants $flag"))
    val (from, to, set, series) = (req("-from"), req("-to"), req("-set"), req("-series"))
    val resamples = countOf(args, "-resamples", 20000, "-resamples wants an integer").toInt
    val seed = countOf(args, "-seed", 20260918L, "-seed wants an integer")
    val joint = numberOf(args, "-joint", 0.05, "-joint wants a share")
    val chain = opt(args, "-french") match
      case Some(f) =>
        val rows = readFrench(f)
        (1 until rows.length).toVector.map(i =>
          StateSession(rows(i - 1)._1, rows(i)._1, rows(i)._2 / 100.0, rows(i)._2 / 100.0,
                       MarketSim.lnDet(1.0 + rows(i)._2 / 100.0)))
      case None => readStateChain(req("-yahoo"), splicesOf(args), opt(args, "-column"))
    val rates = readFred(req("-fred"))
    val inWindow = chain.filter(s => s.date > from && s.date <= to)
    if inWindow.length <= 252 then
      usage(s"the window holds ${inWindow.length} sessions; a block bootstrap needs more than a year")
    val days   = inWindow.map(stateDayOf(_, rates))
    val window = s"${inWindow.head.date}..${inWindow.last.date}"
    val record = MarketSim.ledgerReadingsOf(days)
    val reads  = MarketSim.ledgerResamples(days, resamples, seed, run.block)
    eprintln(s"$series: ${days.length} sessions $window; $resamples resamples, seed $seed")
    printBandHeader(args)
    for fam <- MarketSim.LedgerFamilies.filter(_.end <= MarketSim.LedgerStateRows) do
      val ks = fam.toVector
      val (c, edges) = MarketSim.recordBandJoint(reads, ks, joint)
      for (k, (lo, hi)) <- ks.zip(edges) do
        val qs = bandQuantiles(record(k), reads.map(_(k)))
        println(f"$set%s\t${MarketSim.LedgerRows(k)}%s\t$series%s\t$window%s\t${days.length}%d\t$resamples%d\t${record(k)}%.6f\t" +
                qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")
    printLag1Row(set, series, inWindow, resamples, seed, joint)

  /** The one-day reversion's row (`-ledgerrows`): the window's sessions after `UnlagCut` alone,
    * where the record has no lag, their lag-1 (`ledgerLag1Of`), its resamples and band. */
  def printLag1Row(set: String, series: String, inWindow: Vector[StateSession], resamples: Int, seed: Long,
                   joint: Double)(using run: Run): Unit =
    val after = inWindow.filter(_.date > MarketSim.UnlagCut)
    if after.length <= 252 then
      usage(s"the window holds ${after.length} sessions after ${MarketSim.UnlagCut}; the one-day reversion needs more than a year")
    val r = after.map(_.logRet).toArray
    val lagWindow = s"${after.head.date}..${after.last.date}"
    val reads = MarketSim.ledgerLag1Resamples(r, resamples, seed, run.block)
    val (c, edges) = MarketSim.recordBandJoint(reads, Vector(0), joint)
    val qs = bandQuantiles(MarketSim.ledgerLag1Of(r), reads.map(_(0)))
    val (lo, hi) = edges(0)
    println(f"$set%s\t${MarketSim.LedgerRows(MarketSim.LedgerStateRows)}%s\t$series%s\t$lagWindow%s\t${r.length}%d\t$resamples%d\t" +
            f"${MarketSim.ledgerLag1Of(r)}%.6f\t" + qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")

  /** The `-scales` run list: a line a run, its rows (`*`, or names joined by `;`), then its
    * arguments, tab-separated, `#` a comment; each run's own `-header`, `-resamples` and `-block`
    * dropped, since `-scales` sets them. */
  def readScaleRuns(text: String): Vector[(Vector[String], Vector[String])] =
    linesOf(text).filter(l => !(l.trim.isEmpty || l.startsWith("#"))).map { l =>
      val f = l.split("\t", -1).map(_.trim).toVector
      val pick = if f(0) == "*" then Vector.empty else f(0).split(";", -1).map(_.trim).toVector
      @annotation.tailrec
      def keep(rest: List[String], acc: Vector[String]): Vector[String] = rest match
        case "-header" :: t                                       => keep(t, acc)
        case a :: _ :: t if a == "-resamples" || a == "-block"    => keep(t, acc)
        case a :: Nil if a == "-resamples" || a == "-block"       => acc
        case x :: t                                               => keep(t, acc :+ x)
        case Nil                                                  => acc
      (pick, keep(f.drop(1).toList, Vector.empty))
    }

  /** One band row as a `-scales` run prints it under `-raw`: its world and name, (p95 - p5) / 3.29,
    * the record and the resamples the quantiles were read from. */
  final case class ScaleRead(world: String, row: String, scale: Double, record: Double, resamples: Vector[Double])

  /** A world and row's reading at one block: its scale, the record's vote on its sign and its knee
    * (`voteAndKnee`), and the run it came from. */
  final case class ScaleSlot(scale: Double, resolution: Option[Double], knee: Option[Double], run: Int)

  /** The ledger table's rows and nulls, by its `row` and `null` columns: a null `none`, or absent,
    * is no null. */
  def readScaleRows(ledger: String, text: String): Vector[(String, Option[Double])] =
    val ls = linesOf(text)
    val head = ls.headOption.getOrElse("").split("\t", -1).map(_.trim).toVector
    def col(name: String): Int = head.indexOf(name) match
      case -1 => usage(s"-scales: $ledger has no [$name] column")
      case i  => i
    val (rowAt, nullAt) = (col("row"), col("null"))
    ls.drop(1).filter(_.trim.nonEmpty).map { l =>
      val f = l.split("\t", -1)
      (f.lift(rowAt).fold("")(_.trim), f.lift(nullAt).flatMap(_.trim.toDoubleOption))
    }.filter(_._1.nonEmpty)

  /** The band rows one run of this tool prints, run under `-raw` so each carries its resamples
    * (`readScaleLines`).  The run is this tool again, a process of its own. */
  def scalesOfRun(args: Vector[String]): Vector[ScaleRead] =
    val javaExe = ProcessHandle.current().info().command().orElse("java")
    val main = Option(System.getProperty("sun.java.command")).flatMap(_.split(" ").headOption)
      .getOrElse(usage("-scales: this tool's main class is not known"))
    val p = new ProcessBuilder((Vector(javaExe, "-cp", System.getProperty("java.class.path"), main) ++ args :+ "-raw")*).start()
    p.getOutputStream.close()
    val err = new java.io.ByteArrayOutputStream
    val drain = new Thread(() => { val _ = p.getErrorStream.transferTo(err) })
    drain.start()
    val out = new String(p.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    drain.join()
    if p.waitFor() != 0 then
      usage(s"-scales: the run [${args.mkString(" ")}] failed:\n${err.toString(java.nio.charset.StandardCharsets.UTF_8)}")
    readScaleLines(out)

  /** A run's band rows from its output, each with its spread in normal sd: the band columns
    * (`printBandHeader`), p5 and p95 at `RecordBandPcts`' third and twenty-first places; a
    * record-only line is not a band row.  A `raw` line (`bandQuantiles`) carries the record and the
    * resamples of the band line after it; a band row without one has neither. */
  def readScaleLines(text: String): Vector[ScaleRead] =
    val cols = 7 + MarketSim.RecordBandPcts.length + 3
    def num(v: String): Double = v.toDoubleOption.getOrElse(Double.NaN)
    linesOf(text).map(_.split("\t", -1)).foldLeft((Option.empty[(Double, Vector[Double])], Vector.empty[ScaleRead])) {
      case ((_, out), f) if f.headOption.contains("raw") =>
        (Some((f.lift(1).fold(Double.NaN)(num), f.drop(2).toVector.map(num))), out)
      case ((raw, out), f) if f.length == cols && f(0) != "set" =>
        def q(i: Int): Double = num(f(7 + i))
        val (record, resamples) = raw.getOrElse((Double.NaN, Vector.empty[Double]))
        (None, out :+ ScaleRead(f(0), f(1), (q(20) - q(2)) / 3.29, record, resamples))
      case (acc, _) => acc
    }._2

  /** THE RECORD'S OWN VOTE ON ITS SIGN, AND ITS KNEE, from one band row's finite resamples at the
    * row's scale: the resolution is the share of resamples strictly on the record's side of the
    * row's null less the share strictly on the other side, floored at 0 (a resample at the null
    * votes for neither); the knee is the CRPS an indistinguishable world earns, the mean over the
    * resamples of each one's CRPS under the others (`leaveOneOutCrps`), in the row's scale.  The
    * resolution is `None` without a null, with the record at the null, or without a scale; the knee
    * `None` without a scale or under three resamples. */
  def voteAndKnee(resamples: Vector[Double], nul: Option[Double], record: Double,
                  scale: Double): (Option[Double], Option[Double]) =
    val xs = resamples.filter(_.isFinite)
    if !(scale.isFinite && scale > 0.0) || xs.isEmpty then (None, None)
    else
      val n = xs.length.toDouble
      val resolution = nul.filter(nl => record.isFinite && record != nl).map { nl =>
        val up = record > nl
        val pRec = xs.count(x => if up then x > nl else x < nl).toDouble / n
        val pOther = xs.count(x => if up then x < nl else x > nl).toDouble / n
        math.max(pRec - pOther, 0.0)
      }
      (resolution, leaveOneOutCrps(xs).map(_ / scale))

  /** The mean over a sample of each value's `MarketSim.crps` under the others: from the sorted
    * prefix sums, each value's summed distance to the others and the others' pairwise sum (the whole
    * sample's less those distances), in `crps`'s form, so the sum is the one the leave-one-out calls
    * would make, in one pass.  `None` under three values. */
  def leaveOneOutCrps(sample: Vector[Double]): Option[Double] =
    val n = sample.length
    Option.when(n >= 3) {
      val xs = sample.toArray
      java.util.Arrays.sort(xs)
      val total = xs.foldLeft(0.0)(_ + _)
      // the whole sample's pairwise sum, over j < k of x_k - x_j
      val pairs = xs.indices.foldLeft(0.0)((s, i) => s + ((2 * (i + 1)).toDouble - (n + 1).toDouble) * xs(i))
      val m = (n - 1).toDouble
      val (acc, _) = xs.indices.foldLeft((0.0, 0.0)) { case ((acc, before), p) =>
        val x = xs(p)
        val after = total - before - x
        val dist = x * p.toDouble - before + after - x * (n - 1 - p).toDouble
        (acc + (dist / m - (pairs - dist) / (m * m)), before + x)
      }
      acc / n.toDouble
    }

  /** `-scales`' table: a line a world and row, its scales at 252, at 756 and at its run's own
    * blocks, the largest, and that reading's resolution and knee; `none` where no run reaches the
    * row, those listed on stderr by world. */
  def printScales(rows: Vector[String], got: Map[(String, String), Vector[Option[ScaleSlot]]]): Unit =
    def cell(v: Option[Double]): String = v.fold("none")(x => f"$x%.6f")
    println("world\trow\tscale252\tscale756\tscaleNative\tscale\tresolution\tknee")
    for world <- Vector("sp500", "nasdaq") do
      // each row's readings at its own blocks, at 252 and at 756, and the largest scale's, the
      // first of equals
      val table = rows.map { row =>
        val s = got.getOrElse((world, row), Vector.fill(3)(None))
        def v(j: Int): Option[ScaleSlot] = s(j).filter(x => x.scale.isFinite && x.scale > 0.0)
        val (native, b252, b756) = (v(0), v(1), v(2))
        val best = Vector(native, b252, b756).flatten.foldLeft(Option.empty[ScaleSlot]) { (m, x) =>
          Some(m.filter(_.scale >= x.scale).getOrElse(x))
        }
        (row, Vector(b252.map(_.scale), b756.map(_.scale), native.map(_.scale), best.map(_.scale),
                     best.flatMap(_.resolution), best.flatMap(_.knee)))
      }
      table.foreach((row, cells) => println(s"$world\t$row\t${cells.map(cell).mkString("\t")}"))
      val missing = table.filter(_._2(3).isEmpty).map(_._1)
      eprintln(s"$world: ${missing.length} rows no run reaches")
      missing.foreach(r => eprintln(s"  $r"))

  /** `-scales LEDGER -runs RUNS`: the record scales of the ledger's rows, each with the record's vote
    * on its sign and its knee (the usage's `-scales`). */
  def scalesMode(args: Vector[String]): Unit =
    val ledger = args.lift(args.indexOf("-scales") + 1).getOrElse(usage("-scales wants the ledger's row table"))
    val runsFile = opt(args, "-runs").getOrElse(usage("-scales wants -runs RUNS.tsv"))
    val resamples = opt(args, "-resamples")
    val rows = readScaleRows(ledger, readText(ledger))
    def nullOf(row: String): Option[Double] = rows.find(_._1 == row).flatMap(_._2)
    val runs = readScaleRuns(readText(runsFile))
    // (world, row) -> the reading at each block
    val blocks = Vector(None, Some("252"), Some("756"))
    val got = runs.zipWithIndex.foldLeft(Map.empty[(String, String), Vector[Option[ScaleSlot]]]) {
      case (acc0, ((pick, a0), k)) =>
        blocks.zipWithIndex.foldLeft(acc0) { case (acc1, (block, j)) =>
          val a = a0 ++ resamples.toVector.flatMap(n => Vector("-resamples", n)) ++
                  block.toVector.flatMap(b => Vector("-block", b))
          eprintln(s"run ${k + 1} of ${runs.length} at ${block.getOrElse("its own blocks")}")
          scalesOfRun(a).filter(rd => pick.isEmpty || pick.contains(rd.row)).foldLeft(acc1) { (acc, rd) =>
            val slots = acc.getOrElse((rd.world, rd.row), Vector.fill(3)(None))
            slots(j).foreach { other =>
              if other.run != k then
                usage(s"-scales: [${rd.row}] in ${rd.world} comes from runs ${other.run + 1} and ${k + 1}; " +
                      "name it in one run's rows")
            }
            val (resolution, knee) = voteAndKnee(rd.resamples, nullOf(rd.row), rd.record, rd.scale)
            acc.updated((rd.world, rd.row), slots.updated(j, Some(ScaleSlot(rd.scale, resolution, knee, k))))
          }
        }
    }
    printScales(rows.map(_._1), got)

  /** THE LAG-FREE SOURCE (`-unlagged OUT`): one source written lag-free in its own layout, for a
    * ruler outside this tool, the whole file its taken range: `-french FILE` with each corrected
    * session's Mkt-RF the corrected total less RF; `-industry FILE -column NAME` with the column's
    * corrected sessions; `-index FILE`, a `date,adj_close,dlog_adj_close` CSV, its returns corrected
    * and its levels compounded again from the first row's.  Every other line as it is. */
  def unlaggedMode(args: Vector[String])(using run: Run): Unit =
    val out = args.lift(args.indexOf("-unlagged") + 1).getOrElse(usage("-unlagged wants an output file"))
    if run.lag.recorded then usage("-unlagged writes the lag-free series; -recorded reads none")
    val text = (opt(args, "-french"), opt(args, "-industry"), opt(args, "-index")) match
      case (Some(f), None, None) => unlaggedFrench(f)
      case (None, Some(f), None) =>
        unlaggedIndustry(f, opt(args, "-column").getOrElse(usage("-unlagged -industry wants -column")))
      case (None, None, Some(f)) if closesLayout(f) => unlaggedCloses(f)
      case (None, None, Some(f))                    => unlaggedDlog(f)
      case _ => usage("-unlagged wants exactly one of -french FILE, -industry FILE and -index FILE")
    out.asPath.write(text)
    eprintln(s"wrote $out")

  /** A cell as the Rust twin's `{}` writes a float: the shortest decimal that reads back to it, in
    * plain notation, `NaN`, `inf` or `-inf`. */
  def shown(v: Double): String =
    if v.isNaN then "NaN"
    else if v == Double.PositiveInfinity then "inf"
    else if v == Double.NegativeInfinity then "-inf"
    else
      val s = new java.math.BigDecimal(java.lang.Double.toString(v)).stripTrailingZeros.toPlainString
      if s == "0" && 1.0 / v < 0.0 then "-0" else s

  /** French's file with each corrected session's Mkt-RF the corrected total less RF. */
  def unlaggedFrench(f: String)(using Run): String =
    val fixed = readFrench(f).filter(_._1 <= MarketSim.UnlagCut).toMap
    rewriteDatedCells(f) { (d8, cells) =>
      for
        total <- fixed.get(isoOf8(d8))
        rf    <- cells.lift(4).flatMap(_.trim.toDoubleOption)
        if cells.length >= 5
      yield (1, shown(total - rf))
    }

  /** French's industry file with the column's corrected sessions in its daily value-weighted block. */
  def unlaggedIndustry(f: String, col: String)(using Run): String =
    val text  = readText(f)
    val fixed = unlagged(readIndustry(f, text, col).filter(_._1 <= MarketSim.UnlagCut)).toMap
    val k     = industryColumn(f, text, col)
    // the daily value-weighted block: its title, its header, then its dated rows
    val (_, lines) = linesOf(text).foldLeft((0, Vector.empty[String])) { case ((stage, out), l) =>
      val cells = l.split(",", -1).toVector
      val d8    = cells(0).trim
      val dated = d8.length == 8 && digits(d8)
      stage match
        case 0 if l.contains("Value Weighted Returns -- Daily") => (1, out :+ l)
        case 1                                                   => (2, out :+ l)
        case 2 if dated =>
          val row = fixed.get(isoOf8(d8)).fold(cells)(r => cells.updated(k, shown(100.0 * (MarketSim.expDet(r) - 1.0))))
          (2, out :+ row.mkString(","))
        case 2 => (3, out :+ l)
        case s => (s, out :+ l)
    }
    lines.mkString("\n") + "\n"

  /** `date,close,adj_close[,dlog_adj_close][,dlog_close]`: the levels corrected, each log column
    * read back off them, the first row's left empty. */
  def unlaggedCloses(f: String)(using Run): String =
    val head   = firstLine(readText(f)).split(",", -1).map(_.trim).toVector
    val levels = closesLagFree(f)
    val body = levels.indices.map { i =>
      val (d, c, a) = levels(i)
      val cells = head.drop(1).map { h =>
        (h, i) match
          case ("close", _)          => shown(c)
          case ("adj_close", _)      => shown(a)
          case (_, 0)                => ""
          case ("dlog_adj_close", _) => shown(MarketSim.lnDet(a / levels(i - 1)._3))
          case ("dlog_close", _)     => shown(MarketSim.lnDet(c / levels(i - 1)._2))
          case _                     => usage(s"$f: no rule for the column [$h]")
      }
      s"$d,${cells.mkString(",")}\n"
    }
    head.mkString(",") + "\n" + body.mkString

  /** A `date,adj_close,dlog_adj_close` CSV, its returns corrected and its levels compounded again
    * from the first row's. */
  def unlaggedDlog(f: String)(using Run): String =
    val first = linesOf(readText(f)).lift(1).getOrElse(usage(s"$f: no anchor row"))
    val comma = first.indexOf(',')
    if comma < 0 then usage(s"$f: unreadable anchor row [$first]")
    val (d0, x0) = (first.take(comma), first.drop(comma + 1))
    val level0 = x0.split(",", -1)(0).trim.toDoubleOption.getOrElse(usage(s"$f: unreadable anchor row [$first]"))
    val days = unlagged(readYahoo(f))
    val cum = days.scanLeft(0.0)((c, dr) => c + dr._2).drop(1)
    s"date,adj_close,dlog_adj_close\n${d0.trim},${shown(level0)},\n" +
      days.zip(cum).map { case ((d, r), c) => s"$d,${shown(level0 * MarketSim.expDet(c))},${shown(r)}\n" }.mkString

  /** Whether a Yahoo-format file carries its closes as levels: a header opening `date,close,adj_close`. */
  def closesLayout(file: String): Boolean =
    val head = firstLine(readText(file)).split(",", -1).map(_.trim)
    head.length >= 3 && head.take(3).sameElements(Array("date", "close", "adj_close"))

  /** A French file's text with one cell of each dated row rewritten: `cell` takes the row's
    * YYYYMMDD and its cells and returns the cell's index and new text, or `None` to keep the row. */
  def rewriteDatedCells(file: String)(cell: (String, Vector[String]) => Option[(Int, String)]): String =
    linesOf(readText(file)).map { l =>
      val cells = l.split(",", -1).toVector
      val d8 = cells(0).trim
      val fix = if d8.length == 8 && digits(d8) then cell(d8, cells) else None
      fix.fold(l)((k, v) => cells.updated(k, v).mkString(","))
    }.mkString("\n") + "\n"

  /** `-basket`: the ruler from a wide closes file, written to `-out` or printed. */
  def basketMode(args: Vector[String]): Unit =
    val closes = opt(args, "-closes").getOrElse(usage("-basket wants -closes WIDE.csv"))
    val closesDate = opt(args, "-closesdate").getOrElse(usage("-basket wants -closesdate YYYY-MM-DD"))
    val minSessions = opt(args, "-minsessions").fold(252)(v => v.toIntOption.getOrElse(usage("-minsessions wants a session count")))
    val p = closes.asPath
    if !p.isFile then usage(s"cannot read $closes")
    val tsv = MarketSim.parseBasketCloses(p.contentAsString)
      .flatMap(c => MarketSim.basketRulerTsv(c, opt(args, "-from").getOrElse(""), opt(args, "-to").getOrElse(""), closesDate, minSessions))
      .fold(m => usage(s"-basket $closes: $m"), identity)
    opt(args, "-out") match
      case Some(out) =>
        out.asPath.write(tsv)
        eprintln(s"wrote $out")
      case None => print(tsv)

  /** `(date, value)` of every row of a FRED `observation_date,SERIES` file whose value is a
    * number, as published: levels, or percent. */
  def fredSeries(file: String): Vector[(String, Double)] =
    file.asPath.lines.toVector.drop(1).flatMap { l =>
      val f = l.split(',').map(_.trim)
      f.lift(1).flatMap(_.toDoubleOption).map(v => (f(0), v))
    }

  /** The series name a FRED file's header carries. */
  def fredName(file: String): String =
    file.asPath.lines.toVector.headOption.getOrElse("").split(',').map(_.trim) match
      case Array("observation_date", h) => h
      case _ => usage(s"$file: not a FRED observation_date,SERIES file")

  /** Each month's last observation of a dated series: `(month, date, value)`. */
  def monthEndsOf(obs: Vector[(String, Double)]): Vector[(String, String, Double)] =
    obs.foldLeft(Vector.empty[(String, String, Double)]) { case (out, (d, v)) =>
      val m = d.take(7)
      out.lastOption match
        case Some(last) if last._1 == m => out.init :+ (m, d, v)
        case _ => out :+ (m, d, v)
    }

  /** `YYYY-MM` plus `k` months. */
  def monthAdd(key: String, k: Int): String =
    val t = key.take(4).toInt * 12 + (key.slice(5, 7).toInt - 1) + k
    f"${t / 12}%04d-${t % 12 + 1}%02d"

  /** The returns of dated log-return series over the sessions ALL of them trade, each compounded
    * across the sessions the others skip: cumulative logs differenced at the common dates, from
    * the second common date on. */
  def joinReturns(series: Vector[Vector[(String, Double)]], from: String, to: String): (Vector[String], Vector[Array[Double]]) =
    val cums: Vector[Map[String, Double]] = series.map { s =>
      s.map(_._1).zip(s.scanLeft(0.0)((acc, dr) => acc + dr._2).drop(1)).toMap
    }
    val dates = series.flatMap(_.map(_._1).filter(d => d >= from && d <= to)).distinct.sorted
      .filter(d => cums.forall(_.contains(d)))
    val rets = cums.map(c => Array.tabulate(dates.length - 1)(i => c(dates(i + 1)) - c(dates(i))))
    (dates.drop(1), rets)

  /** One row group of `-tips`: the record, its resamples' quantiles and the group's own joint band. */
  def printTipsGroup(set: String, names: Vector[String], series: String, window: String, n: Int,
                     record: Vector[Double], reads: Vector[Vector[Double]], alpha: Double)(using run: Run): Unit =
    val ks = names.indices.toVector
    val (c, edges) = MarketSim.recordBandJoint(reads, ks, alpha)
    for (name, k) <- names.zipWithIndex do
      val qs = bandQuantiles(record(k), reads.map(_(k)))
      val (lo, hi) = edges(k)
      println(f"$set%s\t$name%s\t$series%s\t$window%s\t$n%d\t${reads.length}%d\t${record(k)}%.6f\t" +
              qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")

  /** `name value, name value, ...` for a summary line. */
  def rowList(names: Vector[String], values: Vector[Double]): String =
    names.zip(values).map((n, v) => f"$n $v%.4f").mkString(", ")

  /** `YYYY-MM` less one month. */
  def monthBefore(key: String): String =
    val (y, m) = (key.take(4).toInt, key.slice(5, 7).toInt)
    if m == 1 then f"${y - 1}%04d-12" else f"$y%04d-${m - 1}%02d"

  /** The year-over-year CPI rate the market had seen at each of `months` 21-session months' first
    * session (`dates(21k)`): the CPI file's rate of the calendar month before, in simple percent,
    * or the last published month before that (October 2025 was never published). */
  def inflationSeen(cpiFile: String, dates: Vector[String], months: Int): Array[Double] =
    val level = fredSeries(cpiFile).map((d, v) => (d.take(7), v)).toMap
    val yoyByMonth = level.flatMap { (k, v) =>
      val prior = f"${k.take(4).toInt - 1}%04d-${k.slice(5, 7)}"
      level.get(prior).map(p => (k, (v / p - 1.0) * 100.0))
    }
    Array.tabulate(months) { k =>
      val first = dates(k * 21)
      Iterator.iterate(monthBefore(first.take(7)))(monthBefore).take(3).flatMap(yoyByMonth.get).nextOption()
        .getOrElse(usage(s"$cpiFile: no year-over-year rate before ${first.take(7)}"))
    }

  /** The monthly log changes of a FRED level file over `[from, to]`, with the levels and the window. */
  def cpiChanges(file: String, from: String, to: String): (Vector[(String, Double)], Array[Double], String) =
    val cpi = fredSeries(file).filter((d, _) => d >= from && d <= to)
    if cpi.length < 25 then usage(s"$file: ${cpi.length} months in the CPI window")
    val dlog = Array.tabulate(cpi.length - 1)(i => MarketSim.lnDet(cpi(i + 1)._2 / cpi(i)._2))
    (cpi, dlog, s"${cpi.head._1}..${cpi.last._1}")

  /** `-stockbond`: THE STOCK-BOND ROWS (`StockBondBandRows`, `CpiNewsRow`; `stockbond-2026-10-18.tsv`)
    * for -set: the DGS10 par bond (-nominal) and Ken French's market (-french) over 21-session months
    * of the sessions both trade in [-from, -to], each month placed by -cpi's year-over-year rate of
    * the calendar month before its first session; and the CPI news row over [-cpifrom, -cpito].
    * The reported rows (`StockBondReportedRows`) go to stderr. */
  def stockBondMode(args: Vector[String])(using run: Run): Unit =
    def need(flag: String): String = opt(args, flag).getOrElse(usage(s"-stockbond wants $flag FILE"))
    val (nomFile, frenchFile, cpiFile) = (need("-nominal"), need("-french"), need("-cpi"))
    val set       = opt(args, "-set").getOrElse(usage("-stockbond wants -set"))
    val from      = opt(args, "-from").getOrElse("")
    val to        = opt(args, "-to").getOrElse("9999")
    val cpiFrom   = opt(args, "-cpifrom").getOrElse("")
    val cpiTo     = opt(args, "-cpito").getOrElse("9999")
    val resamples = countOf(args, "-resamples", 20000, "-resamples wants an integer").toInt
    val seed      = countOf(args, "-seed", 20260918L, "-seed wants a non-negative integer")
    val joint     = numberOf(args, "-joint", 0.10, "-joint wants a share in (0, 1)")
    val of        = countOf(args, "-of", 5, "-of wants a row count").toInt
    def alpha(k: Int): Double = joint * k / of
    printBandHeader(args)
    val nomDaily = readTreasury(nomFile.asPath.lines.toVector, 10.0)
    val eqDaily  = readFrench(frenchFile).map((d, v) => (d, MarketSim.lnDet(1.0 + v / 100.0)))
    val (dates, rets) = joinReturns(Vector(nomDaily, eqDaily), from, to)
    if dates.length < 2 * 252 then usage(s"$nomFile: ${dates.length} common sessions in the window")
    val bM   = MarketSim.monthlySums(rets(0))
    val eqM  = MarketSim.monthlySums(rets(1))
    val seen = inflationSeen(cpiFile, dates, bM.length)
    val window   = s"${dates.head}..${dates.last}"
    val record   = MarketSim.stockBondReadings(eqM, bM, seen)
    val reported = MarketSim.stockBondReported(eqM, bM)
    eprintln(s"${fredName(nomFile)} par bond and the market: ${dates.length} common sessions $window, ${bM.length} months; " +
             s"${rowList(MarketSim.StockBondBandRows, record)}; " +
             f"reported ${MarketSim.StockBondReportedRows(0)}%s ${reported(0)}%.6f, ${MarketSim.StockBondReportedRows(1)}%s ${reported(1)}%.6f, " +
             f"${MarketSim.StockBondReportedRows(2)}%s ${reported(2)}%.6f; " +
             s"$resamples resamples, seed $seed")
    printTipsGroup(set, MarketSim.StockBondBandRows, s"${fredName(nomFile)} par bond, French market", window, bM.length,
                   record, MarketSim.stockBondResamples(eqM, bM, seen, resamples, seed, run.block), alpha(4))
    // the CPI news row over the CPI window
    val (_, dlog, cpiWindow) = cpiChanges(cpiFile, cpiFrom, cpiTo)
    val news = MarketSim.cpiNewsReading(dlog)
    eprintln(f"${fredName(cpiFile)}%s: ${dlog.length}%d monthly changes $cpiWindow%s; ${MarketSim.CpiNewsRow}%s $news%.4f")
    printTipsGroup(set, Vector(MarketSim.CpiNewsRow), fredName(cpiFile), cpiWindow, dlog.length, Vector(news),
                   MarketSim.cpiNewsResamples(dlog, resamples, seed, run.block), alpha(1))

  /** `-bondstress`: THE BOND STRESS SHARE ROW (`BondStressRow`; `recordbands-2026-09-26.tsv`) for
    * -set: the spiral's stress index run on the -fred yield file as an n-year par bond (-years, 20 by
    * default) over every session in [-from, -to], a return spanning more than ten calendar days (the
    * series' gap) dropped with the state carried across it; the share of sessions above 0.5 after a
    * one-year burn-in, in percent, and its one-year-block resamples. */
  def bondStressMode(args: Vector[String])(using run: Run): Unit =
    val fredFile  = opt(args, "-fred").getOrElse(usage("-bondstress wants -fred FILE"))
    val years     = numberOf(args, "-years", 20.0, "-years wants a number")
    val set       = opt(args, "-set").getOrElse(usage("-bondstress wants -set"))
    val from      = opt(args, "-from").getOrElse("")
    val to        = opt(args, "-to").getOrElse("9999")
    val resamples = countOf(args, "-resamples", 20000, "-resamples wants an integer").toInt
    val seed      = countOf(args, "-seed", 20260918L, "-seed wants a non-negative integer")
    val joint     = numberOf(args, "-joint", 0.10, "-joint wants a share in (0, 1)")
    val of        = countOf(args, "-of", 1, "-of wants a row count").toInt
    printBandHeader(args)
    val daily = readTreasury(fredFile.asPath.lines.toVector, years)
    val inWindow = daily.indices.filter(i => daily(i)._1 >= from && daily(i)._1 <= to)
    val kept = inWindow.filter(i => i == 0 || daysFromCivil(daily(i)._1) - daysFromCivil(daily(i - 1)._1) <= 10).map(daily)
    val gaps  = inWindow.length - kept.length
    val dates = kept.map(_._1)
    val rets  = kept.map(_._2).toArray
    if rets.length < 2 * 252 then usage(s"$fredFile: ${rets.length} sessions in the window")
    val window = s"${dates.head}..${dates.last}"
    val record = MarketSim.bondStressShare(rets)
    eprintln(f"${fredName(fredFile)}%s as a $years%.0f-year par bond: ${rets.length}%d sessions $window%s, $gaps%d gap returns dropped; " +
             f"${MarketSim.BondStressRow}%s $record%.4f; $resamples%d resamples, seed $seed%d")
    printTipsGroup(set, Vector(MarketSim.BondStressRow), s"${fredName(fredFile)} par bond", window, rets.length,
                   Vector(record), MarketSim.bondStressResamples(rets, resamples, seed, run.block), joint / of)

  /** `-cpipersist`: THE CPI PERSISTENCE ROW (`CpiPersistenceRow`) of -cpi over the CPI window for
    * -set, the record's own reading: a single-history row, not resampled; under `-block` its
    * resamples too. */
  def cpiPersistMode(args: Vector[String])(using run: Run): Unit =
    val cpiFile = opt(args, "-cpi").getOrElse(usage("-cpipersist wants -cpi FILE"))
    val set     = opt(args, "-set").getOrElse(usage("-cpipersist wants -set"))
    val (_, dlog, window) = cpiChanges(cpiFile, opt(args, "-cpifrom").getOrElse(""), opt(args, "-cpito").getOrElse("9999"))
    run.block match
      case Some(y) =>
        printBandHeader(args)
        val (resamples, seed, joint) = resamplingOf(args)
        def read(d: Array[Double]): Vector[Double] = Vector(MarketSim.cpiPersistence(d))
        val reads = MarketSim.seriesResamples(dlog, 12, y, resamples, seed)(read)
        printResampledRows(set, fredName(cpiFile), window, dlog.length, Vector(MarketSim.CpiPersistenceRow),
                           read(dlog), reads, joint)
      case None =>
        if args.contains("-header") then println("set\trow\tseries\twindow\tn\trecord")
        println(f"$set%s\t${MarketSim.CpiPersistenceRow}%s\t${fredName(cpiFile)}%s\t$window%s\t${dlog.length}%d\t${MarketSim.cpiPersistence(dlog)}%.6f")

  /** `-tips`: THE REAL SIDE's six row groups (item 60), each on a public-domain record -- CPI-U
    * monthly and its century, DFII10 and T10YIE at month ends, a 10-year par TIPS rebought at every
    * DFII10 observation beside the DGS10 par bond and the equity market on their common sessions,
    * and the policy rate less the next year's realised inflation -- resampled in one-year blocks of
    * each record's own cadence, each group under its own joint band at the mode's share of `-of`. */
  def tipsMode(args: Vector[String])(using run: Run): Unit =
    def need(flag: String): String = opt(args, flag).getOrElse(usage(s"-tips wants $flag FILE"))
    val (cpiFile, centuryFile) = (need("-cpi"), need("-cpicentury"))
    val (realFile, beiFile) = (need("-real"), need("-breakeven"))
    val (nomFile, dffFile, frenchFile) = (need("-nominal"), need("-fred"), need("-french"))
    val set       = opt(args, "-set").getOrElse(usage("-tips wants -set"))
    val from      = opt(args, "-from").getOrElse("")
    val to        = opt(args, "-to").getOrElse("9999")
    val cpiFrom   = opt(args, "-cpifrom").getOrElse("")
    val cpiTo     = opt(args, "-cpito").getOrElse("9999")
    val resamples = countOf(args, "-resamples", 20000, "-resamples wants an integer").toInt
    val seed      = countOf(args, "-seed", 20260918L, "-seed wants a non-negative integer")
    val joint     = numberOf(args, "-joint", 0.10, "-joint wants a share in (0, 1)")
    val of        = countOf(args, "-of", 16, "-of wants a row count").toInt
    def alpha(k: Int): Double = joint * k / of
    printBandHeader(args)

    // CPI: monthly log changes of the level over the CPI window
    val (cpi, dlog, cpiWindow) = cpiChanges(cpiFile, cpiFrom, cpiTo)
    val cpiRecord = MarketSim.cpiReadings(dlog)
    eprintln(s"${fredName(cpiFile)}: ${dlog.length} monthly changes $cpiWindow; ${rowList(MarketSim.CpiBandRows, cpiRecord)}; " +
             s"reported ${rowList(MarketSim.CpiReportedRows, MarketSim.cpiReported(dlog))}; $resamples resamples, seed $seed")
    printTipsGroup(set, MarketSim.CpiBandRows, fredName(cpiFile), cpiWindow, dlog.length, cpiRecord,
                   MarketSim.cpiResamples(dlog, resamples, seed, run.block), alpha(4))

    // the century: CPI-U not seasonally adjusted from 1913, read whole
    val (_, cdlog, cWindow) = cpiChanges(centuryFile, "", "9999")
    val cRecord = MarketSim.cpiCenturyReadings(cdlog)
    eprintln(s"${fredName(centuryFile)}: ${cdlog.length} monthly changes $cWindow; ${rowList(MarketSim.CpiCenturyRows, cRecord)}")
    printTipsGroup(set, MarketSim.CpiCenturyRows, fredName(centuryFile), cWindow, cdlog.length, cRecord,
                   MarketSim.cpiCenturyResamples(cdlog, resamples, seed, run.block), alpha(2))

    // the real yield and the breakeven at month ends over the real window, each with its month
    def monthLevels(file: String): (Vector[(String, Double)], String) =
      val ends = monthEndsOf(fredSeries(file)).filter((_, d, _) => d >= from && d <= to)
      if ends.length < 25 then usage(s"$file: ${ends.length} month ends in the real window")
      (ends.map((m, _, v) => (m, v)), s"${ends.head._2}..${ends.last._2}")
    val (ryMonths, ryWindow) = monthLevels(realFile)
    val ry       = ryMonths.map(_._2).toArray
    val ryRecord = MarketSim.realYieldReadings(ry)
    eprintln(s"${fredName(realFile)}: ${ry.length} month ends $ryWindow; ${rowList(MarketSim.RealYieldBandRows, ryRecord)}; " +
             s"reported ${rowList(MarketSim.RealYieldReportedRows, MarketSim.realYieldReported(ry))}")
    printTipsGroup(set, MarketSim.RealYieldBandRows, fredName(realFile), ryWindow, ry.length, ryRecord,
                   MarketSim.realYieldResamples(ry, resamples, seed, run.block), alpha(1))
    val (beiMonths, beiWindow) = monthLevels(beiFile)
    val bei       = beiMonths.map(_._2).toArray
    // the year-over-year rate of the CPI window's months, beside each breakeven month end
    val cpiOfMonth = cpi.map((d, v) => (d.take(7), v)).toMap
    val yoy = beiMonths.map { (m, _) =>
      val yearAgo = f"${m.take(4).toInt - 1}%04d-${m.drop(5).take(2)}"
      (cpiOfMonth.get(m), cpiOfMonth.get(yearAgo)) match
        case (Some(a), Some(b)) => 100.0 * MarketSim.lnDet(a / b)
        case _                  => Double.NaN // a month the CPI lacks (October 2025 was never published)
    }.toArray
    // the nominal ten-year yield at the breakeven's month ends
    val nomOfMonth = monthLevels(nomFile)._1.toMap
    val y = beiMonths.map((m, _) => nomOfMonth.getOrElse(m, Double.NaN)).toArray
    val beiRecord = MarketSim.breakevenReadings(bei, yoy, y)
    eprintln(s"${fredName(beiFile)}: ${bei.length} month ends $beiWindow; ${rowList(MarketSim.BreakevenBandRows, beiRecord)}; " +
             s"reported ${rowList(MarketSim.BreakevenReportedRows, MarketSim.breakevenReported(bei))}")
    printTipsGroup(set, MarketSim.BreakevenBandRows, fredName(beiFile), beiWindow, bei.length, beiRecord,
                   MarketSim.breakevenResamples(bei, yoy, y, resamples, seed, run.block), alpha(3))

    // the TIPS leg: a 10-year par TIPS on DFII10, the DGS10 par bond and the market on common days
    val realLines = realFile.asPath.lines.toVector
    val tipsDaily = readTreasury(realLines, 10.0)
    val durs      = treasuryDurations(realLines, 10.0)
    val nomDaily  = readTreasury(nomFile.asPath.lines.toVector, 10.0)
    val eqDaily   = readFrench(frenchFile).map((d, v) => (d, MarketSim.lnDet(1.0 + v / 100.0)))
    val (dates, rets) = joinReturns(Vector(tipsDaily, nomDaily, eqDaily), from, to)
    if dates.length < 2 * 252 then usage(s"$realFile: ${dates.length} common sessions in the real window")
    val dur        = dates.map(d => durs.getOrElse(d, usage(s"$realFile: no duration for $d"))._1).toArray
    val tipsWindow = s"${dates.head}..${dates.last}"
    val tipsRecord = MarketSim.tipsReadings(rets(0), dur, rets(1), rets(2))
    val r2022      = tipsDaily.filter((d, _) => d > "2021-12-31" && d <= "2022-10-31").map(_._2).foldLeft(0.0)(_ + _)
    eprintln(f"${fredName(realFile)}%s par TIPS: ${dates.length}%d common sessions $tipsWindow%s; ${rowList(MarketSim.TipsBandRows, tipsRecord)}%s; " +
             f"reported 2022 real return (Dec 2021 - Oct 2022) ${(MarketSim.expDet(r2022) - 1.0) * 100.0}%.2f%%")
    printTipsGroup(set, MarketSim.TipsBandRows, fredName(realFile), tipsWindow, dates.length, tipsRecord,
                   MarketSim.tipsResamples(rets(0), dur, rets(1), rets(2), resamples, seed, run.block), alpha(4))

    // the ex-post real rate: the month's mean policy rate less the next twelve months' inflation
    val dffMonths = fredSeries(dffFile).foldLeft(Vector.empty[(String, Double, Int)]) { case (out, (d, v)) =>
      val m = d.take(7)
      out.lastOption match
        case Some(last) if last._1 == m => out.init :+ (m, last._2 + v, last._3 + 1)
        case _ => out :+ (m, v, 1)
    }
    val cpiByMonth = cpi.map((d, v) => (d.take(7), v)).toMap
    val rr = dffMonths.flatMap { (m, sum, n) =>
      for now <- cpiByMonth.get(m); next <- cpiByMonth.get(monthAdd(m, 12))
      yield (m, sum / n - MarketSim.lnDet(next / now) * 100.0)
    }
    if rr.length < 25 then usage(s"$dffFile: ${rr.length} months with a year of CPI ahead")
    val rrWindow = s"${rr.head._1}..${rr.last._1}"
    val x        = rr.map(_._2).toArray
    val rrRecord = MarketSim.realRateReadings(x)
    // reported: the real yield less the ex-post real rate over the months both hold
    val rrByMonth = rr.toMap
    val both      = ryMonths.flatMap((m, y) => rrByMonth.get(m).map(r => (y, r)))
    val over      = both.map(_._1).sum / both.length - both.map(_._2).sum / both.length
    eprintln(f"${fredName(dffFile)}%s less the next year's ${fredName(cpiFile)}%s: ${x.length}%d months $rrWindow%s; ${rowList(MarketSim.RealRateBandRows, rrRecord)}%s; " +
             f"reported real yield less the ex-post real rate over their ${both.length}%d common months $over%.3f")
    printTipsGroup(set, MarketSim.RealRateBandRows, fredName(dffFile), rrWindow, x.length, rrRecord,
                   MarketSim.realRateResamples(x, resamples, seed, run.block), alpha(2))

  /** `-profits`: THE PROFIT DECLINE ROWS (`ProfitDeclineRows`; `profits-2026-10-19.tsv`) of a
    * quarterly FRED series (corporate profits after tax, CPATAX), read by `profitDeclineOf` on
    * every quarter the file holds, printed for `-set` as a single-history record: set, row, series,
    * window, n, record; under `-block` its resamples too. */
  def profitsMode(args: Vector[String])(using run: Run): Unit =
    val file   = opt(args, "-fred").getOrElse(usage("-profits wants -fred FILE"))
    val set    = opt(args, "-set").getOrElse(usage("-profits wants -set"))
    val series = opt(args, "-series").getOrElse(fredName(file))
    val rows   = fredSeries(file)
    if rows.isEmpty then usage(s"$file holds no quarters")
    val levels = rows.map(_._2).toArray
    val window = s"${rows.head._1}..${rows.last._1}"
    val record = MarketSim.profitDeclineOf(levels)
    val eps    = MarketSim.profitEpisodes(levels)
    eprintln(f"$series%s: ${levels.length}%d quarters $window%s; ${eps.length}%d declines of " +
             f"${100 * MarketSim.ProfitDeclineDepth}%.0f%%+")
    for e <- eps do
      val regain = e.regain.map(r => s"${(r - e.trough) * 12 / MarketSim.QuartersPerYear} months").getOrElse("open")
      eprintln(f"  ${rows(e.peak)._1}%s..${rows(e.trough)._1}%s  size ${100 * e.fall}%5.1f%%  " +
               f"fall ${(e.trough - e.peak) * 12 / MarketSim.QuartersPerYear}%3d months  regain $regain%s")
    run.block match
      case Some(y) =>
        printBandHeader(args)
        val (resamples, seed, joint) = resamplingOf(args)
        val reads = levelResamples(levels, MarketSim.QuartersPerYear, y, resamples, seed)(MarketSim.profitDeclineOf)
        printResampledRows(set, series, window, levels.length, MarketSim.ProfitDeclineRows, record, reads, joint)
      case None =>
        if args.contains("-header") then println("set\trow\tseries\twindow\tn\trecord")
        for (name, value) <- MarketSim.ProfitDeclineRows.zip(record) do
          println(f"$set%s\t$name%s\t$series%s\t$window%s\t${levels.length}%d\t$value%.6f")

  /** `-sectors DIR [-resamples N] [-seed S]`: the sector rows; under `-block` the graded rows. */
  def sectorMode(args: Vector[String])(using run: Run): Unit =
    val dir = args.lift(args.indexOf("-sectors") + 1).getOrElse(usage("-sectors needs a directory"))
    val resamples = countOf(args, "-resamples", 20000L, "-resamples wants a non-negative integer").toInt
    run.block match
      case Some(y) => printSectorScaleRows(args, dir, y)
      case None    => printSectorRows(dir, resamples, countOf(args, "-seed", 20260918L, "-seed wants a non-negative integer"))

  /** `-sectors DIR -block N`: THE GRADED SECTOR ROWS (`SectorRows`, `sectorRowsOfPanel`) on the ten
    * industries' whole monthly panel, as a history is read, and their resamples in blocks of years
    * of months, every column taking the same months, in the band columns, for -set (sp500). */
  def printSectorScaleRows(args: Vector[String], dir: String, years: Int)(using Run): Unit =
    val set = opt(args, "-set").getOrElse("sp500")
    val (months, p) = sectorPanel(dir, "10_Industry_Portfolios.CSV")
    val n = p.market.length
    val window = s"${months.head}..${months(n - 1)}"
    val (resamples, seed, joint) = resamplingOf(args)
    val idx = Array.tabulate(n)(_.toDouble)
    val reads = MarketSim.seriesResamples(idx, 12, years, resamples, seed) { x =>
      val at = x.map(_.toInt).toVector
      MarketSim.sectorRowsOfPanel(MarketSim.SectorPanel(p.returns.map(c => at.map(c)), at.map(p.market), at.map(p.rf)))
    }
    printBandHeader(args)
    printResampledRows(set, "French10", window, n, MarketSim.SectorRows, MarketSim.sectorRowsOfPanel(p), reads, joint)

  /** The modes that read their own arguments, by flag, in the order they are tried. */
  val OwnModes: Vector[String] = Vector("-scales", "-resampled", "-satellite", "-dividend", "-unlagged", "-basket",
    "-sectors", "-timing", "-volexit", "-volstate", "-ledgerrows", "-cpipersist", "-tips", "-stockbond", "-bondstress",
    "-profits")

  def ownMode(flag: String, a: Vector[String])(using Run): Unit = flag match
    case "-scales"     => scalesMode(a)
    case "-resampled"  => resampledMode(a)
    case "-satellite"  => satelliteMode(a)
    case "-dividend"   => dividendMode(a)
    case "-unlagged"   => unlaggedMode(a)
    case "-basket"     => basketMode(a)
    case "-sectors"    => sectorMode(a)
    case "-timing"     => timingMode(a)
    case "-volexit"    => volexitMode(a)
    case "-volstate"   => volStateMode(a)
    case "-ledgerrows" => ledgerRowsMode(a)
    case "-cpipersist" => cpiPersistMode(a)
    case "-tips"       => tipsMode(a)
    case "-stockbond"  => stockBondMode(a)
    case "-bondstress" => bondStressMode(a)
    case "-profits"    => profitsMode(a)
    case other         => usage(s"no mode [$other]")

  def main(args: Array[String]): Unit =
    val a = args.toVector
    given Run = Run(lagOf(a), blockOf(a), a.contains("-raw"))
    OwnModes.find(a.contains) match
      case Some(flag) => ownMode(flag, a)
      case None       => bandMode(a)

  /** Where the record comes from: one of the three file layouts the fixture is built from. */
  enum Source:
    case Yahoo(file: String)
    case French(file: String)
    /** FRED's daily `date,value` CSV (the effective federal funds rate, DFF): a level in percent,
      * read on Monday-Friday dates, for the rate rows (`-rate`) */
    case Fred(file: String)

  /** The band modes' options, read once by `parseArgs`. */
  final case class Opts(source: Source, from: String, to: String, set: String, series: String,
                        rows: Vector[String], resamples: Int, seed: Long, joint: Double, of: Int,
                        header: Boolean, coupling: Boolean, worst: Boolean, multiyear: Boolean, long: Boolean,
                        rate: Boolean, bond: Boolean, bond10: Boolean,
                        afterRate: Option[String],               // `-rateafter`: the rate read beside the equity
                        cash: Option[String],                    // `-bond10`: the short rate (FRED DFF)
                        splice: Vector[(String, String)],        // `-splice FILE -at DATE`, in order
                        column: Option[String])                  // `-column NAME`, a French industry file's

  def parseArgs(args: Vector[String]): Opts =
    var yahoo = ""; var french = ""; var fred = ""; var from = ""; var to = ""
    var splice = Vector.empty[(String, String)]; var pending = ""; var column = ""
    var set = ""; var series = ""; var rows = Vector.empty[String]
    var resamples = 20000; var seed = 20260918L; var header = false
    var joint = 0.10; var of = 0; var coupling = false; var worst = false; var rate = false; var bond = false
    var bond10 = false; var multiyear = false; var long = false; var rateafter = false
    eachArg(args, usage) {
      case "-yahoo"      => yahoo = consumeNext
      case "-french"     => french = consumeNext
      case "-fred"       => fred = consumeNext
      case "-splice"     =>
        if pending.nonEmpty then usage("-splice FILE wants its -at DATE before the next -splice")
        pending = consumeNext
      case "-at"         =>
        if pending.isEmpty then usage("-at DATE follows a -splice FILE")
        splice = splice :+ (pending, consumeNext); pending = ""
      case "-column"     => column = consumeNext
      case "-from"       => from = consumeNext
      case "-to"         => to = consumeNext
      case "-set"        => set = consumeNext
      case "-series"     => series = consumeNext
      case "-rows"       => rows = consumeNext.split(",").map(_.trim).toVector
      case "-resamples"  => resamples = consumeNext.toIntOption.filter(_ >= 0).getOrElse(usage("-resamples wants an integer"))
      case "-seed"       => seed = consumeNext.toLongOption.filter(_ >= 0).getOrElse(usage("-seed wants a non-negative integer"))
      case "-joint"      => joint = consumeNext.toDoubleOption.getOrElse(usage("-joint wants a share in (0, 1)"))
      case "-of"         => of = consumeNext.toIntOption.filter(_ >= 0).getOrElse(usage("-of wants a row count"))
      case "-header"     => header = true
      case "-coupling"   => coupling = true
      case "-worstcrash" => worst = true
      case "-multiyear"  => multiyear = true
      case "-long"       => long = true
      case "-rate"       => rate = true
      case "-bond"       => bond = true
      case "-bond10"     => bond10 = true
      case "-rateafter"  => rateafter = true
      // the lag-free record's switches, read by `lagOf`, and the block, read by `blockOf`
      case "-recorded"   => ()
      // the resamples behind each band row, for `-scales` (`bandQuantiles`)
      case "-raw"        => ()
      case "-index" | "-fund" | "-block" => val _ = consumeNext
      case a             => usage(s"unrecognized arg [$a]")
    }
    // `-rateafter` reads a rate beside an equity source; every other mode exactly one source
    def takeFred(msg: String): String =
      if fred.isEmpty then usage(msg)
      val f = fred
      fred = ""
      f
    val afterRate = Option.when(rateafter)(takeFred("-rateafter wants a -fred rate"))
    // `-bond10` reads the short rate beside its DGS10 file, for the excess-return row
    val cash = Option.when(bond10)(takeFred("-bond10 wants a -fred DFF beside the DGS10 file"))
    val source = (yahoo.nonEmpty, french.nonEmpty, fred.nonEmpty) match
      case (true, false, false) => Source.Yahoo(yahoo)
      case (false, true, false) => Source.French(french)
      case (false, false, true) => Source.Fred(fred)
      case _                    => usage("give exactly one of -yahoo, -french and -fred")
    if rate != source.isInstanceOf[Source.Fred] then usage("-rate reads a -fred file, and a -fred file is read by -rate")
    if pending.nonEmpty then usage("-splice FILE -at DATE go together")
    if (splice.nonEmpty || column.nonEmpty) && !source.isInstanceOf[Source.Yahoo] then
      usage("-splice and -column continue a -yahoo series")
    if from.isEmpty || to.isEmpty then usage("-from and -to are required")
    if set.isEmpty || series.isEmpty then usage("-set and -series are required")
    rows.find(r => !MarketSim.RecordBandRows.contains(r))
      .foreach(r => usage(s"-rows names [$r], which is not a record-band row"))
    Opts(source, from, to, set, series, rows, resamples, seed, joint, of, header, coupling, worst, multiyear, long,
         rate, bond, bond10, afterRate, cash, splice, Option.when(column.nonEmpty)(column))

  /** The window's daily log returns, each with the date it ended on -- or, on a `-fred` source,
    * the window's daily rate levels, no session dropped. */
  def returnsInWindow(o: Opts)(using Run): Vector[(String, Double)] =
    def inWindow(d: String) = d >= o.from && d <= o.to
    o.source match
      case Source.Fred(f)  => readFred(f).filter((d, _) => inWindow(d))
      case Source.Yahoo(f) => readDailyChain(f, o.splice, o.column).filter((d, _) => inWindow(d))
      case Source.French(f) =>
        val days = readFrench(f).filter((d, _) => inWindow(d))
        val idx = days.scanLeft(1.0)((p, dx) => p * (1.0 + dx._2 / 100.0)).drop(1)
        // `lnDet`, not the native log, which differs from the Rust twin's in the last bit on about
        // 0.2% of inputs: the twins must read the same returns to the bit, or the joint band's
        // ranks tie differently
        (1 until days.length).toVector.map(k => (days(k)._1, MarketSim.lnDet(idx(k) / idx(k - 1))))

  /** The band modes: the record's rows on one daily window, or the alternate readings its flags name. */
  def bandMode(args: Vector[String])(using run: Run): Unit =
    val o = parseArgs(args)
    val dated = returnsInWindow(o)
    if dated.length <= 252 then
      usage(s"the window holds ${dated.length} sessions; a block bootstrap needs more than a year")
    val r = dated.map(_._2).toArray
    val window = s"${dated.head._1}..${dated.last._1}"
    o.afterRate match
      case Some(rateFile) =>
        // the rate on the equity's session dates: a session without a rate is dropped
        val rates = readFred(rateFile).toMap
        val joined = dated.flatMap((d, x) => rates.get(d).map(v => (x, v)))
        printRateAfterRows(o, joined.map(_._1).toArray, joined.map(_._2).toArray, window)
      case None =>
        if o.coupling then printCouplingRows(o, r, window)
        else if o.multiyear then printMultiYearRows(o, r, window)
        else if o.worst then printWorstCrashRow(o, r, window)
        else if o.rate then printRateRows(o, r, window)
        else if o.bond then printBondRows(o, r, window)
        else if o.bond10 then printBond10Rows(o, dated, window)
        else printRecordRows(o, r, window)

  /** The record's rows (`RecordBandRows`) on the window, its resamples and their joint band. */
  def printRecordRows(o: Opts, r: Array[Double], window: String)(using run: Run): Unit =
    eprintln(s"${o.series}: ${r.length} returns $window, ${r.count(_ == 0.0)} exactly zero; " +
             s"${o.resamples} resamples, seed ${o.seed}")
    val record = MarketSim.seriesReadings(r).zip(MarketSim.RecordBandRows).map { (v, name) =>
      if name == "typical-year vol %" then MarketSim.yearVolPhaseMean(r) else v
    }
    val reads = MarketSim.recordResamples(r, o.resamples, o.seed, run.block)
    if o.header then printBandHeader(Vector("-header"))
    // THE JOINT BAND over the rows this window prints, at this window's share of the set's miss rate
    val ks = MarketSim.RecordBandRows.indices.toVector
      .filter(k => o.rows.isEmpty || o.rows.contains(MarketSim.RecordBandRows(k)))
    val alpha = o.joint * ks.length / (if o.of == 0 then ks.length else o.of)
    val (c, edges) = MarketSim.recordBandJoint(reads, ks, alpha)
    // how often each row's own 5th-95th band holds a resample on every row at once: the figure the
    // joint band's docstrings quote
    val own = ks.map { k =>
      val q = MarketSim.recordBandQuantiles(reads.map(_(k)))
      (q(2), q(20))
    }
    val held = reads.count(x => ks.zip(own).forall { case (k, (lo, hi)) => x(k) >= lo && x(k) <= hi })
    eprintln(f"each row's own p5..p95 held the resamples on all ${ks.length}%d rows at once ${held * 100.0 / reads.length}%.1f%% of the time")
    for (k, (lo, hi)) <- ks.zip(edges) do
      val name = MarketSim.RecordBandRows(k)
      val qs = bandQuantiles(record(k), reads.map(_(k)))
      println(f"${o.set}%s\t$name%s\t${o.series}%s\t$window%s\t${r.length}%d\t${o.resamples}%d\t${record(k)}%.6f\t" +
              qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")

  /** `-coupling`: the bubble rows of `bubblebust-2026-09-24.tsv` on the window, the record alone;
    * under `-block` their resamples too. */
  def printCouplingRows(o: Opts, r: Array[Double], window: String)(using run: Run): Unit =
    val names = Vector("bubble coupling 3y", "largest 3y run-up", "longest calm stretch", "variance ratio 250d")
    def read(x: Array[Double]): Vector[Double] =
      Vector(MarketSim.bubbleCouplingOf(x), MarketSim.runUp3yOf(x), MarketSim.calmStretchOf(x), MarketSim.varianceRatio(x, 250))
    printRecordAlone(o, r, window, names, read)

  /** `-worstcrash`: THE WORST CRASH ROW (`worst crash %`, `worstCrashOf`) on the price the window's
    * daily log returns compound to from 1, the record alone; under `-block` its resamples too. */
  def printWorstCrashRow(o: Opts, r: Array[Double], window: String)(using run: Run): Unit =
    def read(x: Array[Double]): Vector[Double] =
      Vector(MarketSim.worstCrashOf(1.0 +: x.scanLeft(0.0)(_ + _).drop(1).map(MarketSim.expDet)))
    printRecordAlone(o, r, window, Vector("worst crash %"), read)

  /** The record's multi-year rows (`multiYearReadings`), under their long-window names with
    * `-long`: records alone, since the rows are graded against the world's own histories; under
    * `-block` their resamples too. */
  def printMultiYearRows(o: Opts, r: Array[Double], window: String)(using run: Run): Unit =
    printRecordAlone(o, r, window, if o.long then MarketSim.MultiYearLongRows else MarketSim.MultiYearRows,
                     MarketSim.multiYearReadings)

  /** A record-alone mode of the band modes: `read` on the window, or under `-block` beside its
    * resamples in the band columns. */
  def printRecordAlone(o: Opts, r: Array[Double], window: String, names: Vector[String],
                       read: Array[Double] => Vector[Double])(using run: Run): Unit =
    run.block match
      case Some(y) =>
        if o.header then printBandHeader(Vector("-header"))
        val reads = MarketSim.seriesResamples(r, 252, y, o.resamples, o.seed)(read)
        printResampledRows(o.set, o.series, window, r.length, names, read(r), reads, o.joint)
      case None =>
        if o.header then println("set\trow\tseries\twindow\tn\trecord")
        for (name, value) <- names.zip(read(r)) do
          println(f"${o.set}%s\t$name%s\t${o.series}%s\t$window%s\t${r.length}%d\t$value%.6f")

  /** The two rate rows (`RateBandRows`) on a rate window: the record by `rateReadings`, its block
    * resamples by `rateResamples`, and a joint band over the two at this window's share of the
    * set's miss rate -- the same columns as the equity rows. */
  def printRateRows(o: Opts, rate: Array[Double], window: String)(using run: Run): Unit =
    val record = MarketSim.rateReadings(rate)
    eprintln(f"${o.series}%s: ${rate.length}%d sessions $window%s, mean ${record(0)}%.4f%%, ${record(1)}%.2f%% under the floor; " +
             s"${o.resamples} resamples, seed ${o.seed}")
    val reads = MarketSim.rateResamples(rate, o.resamples, o.seed, run.block)
    if o.header then printBandHeader(Vector("-header"))
    val ks = MarketSim.RateBandRows.indices.toVector
      .filter(k => o.rows.isEmpty || o.rows.contains(MarketSim.RateBandRows(k)))
    val alpha = o.joint * ks.length / (if o.of == 0 then ks.length else o.of)
    val (c, edges) = MarketSim.recordBandJoint(reads, ks, alpha)
    for (k, (lo, hi)) <- ks.zip(edges) do
      val name = MarketSim.RateBandRows(k)
      val qs = bandQuantiles(record(k), reads.map(_(k)))
      println(f"${o.set}%s\t$name%s\t${o.series}%s\t$window%s\t${rate.length}%d\t${o.resamples}%d\t${record(k)}%.6f\t" +
              qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")

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
      val d = if y != 0.0 then (1.0 - MarketSim.expDet(-2.0 * years * MarketSim.lnDet(1.0 + y / 2.0))) / y else years
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

  /** `-bond10`: the 10-year leg's three rows (`Bond10BandRows`) on FRED's DGS10 read as a par bond
    * -- each session's return, the duration it was bought at, and its log return over DFF's accrual
    * across the same days -- the record by `bond10Readings` at the window's own sessions a year,
    * its one-year-block resamples by `bond10Resamples`, their own joint band over the three rows. */
  def printBond10Rows(o: Opts, dated: Vector[(String, Double)], window: String)(using run: Run): Unit =
    val file = o.source match
      case Source.Yahoo(f) => f
      case _               => usage("-bond10 reads a FRED DGSn file given as -yahoo")
    val lines = file.asPath.lines.toVector
    val head  = lines.headOption.getOrElse("").split(',').map(_.trim)
    val years = head match
      case Array("observation_date", h) if h.startsWith("DGS") =>
        h.drop(3).toDoubleOption.getOrElse(usage(s"$file: [$h] is not a DGSn series"))
      case _ => usage(s"$file: -bond10 reads a FRED DGSn file")
    if o.splice.nonEmpty then usage("-bond10 reads one DGSn file; a splice has no yields to take durations from")
    val durs  = treasuryDurations(lines, years)
    val curve = cashCurve(o.cash.getOrElse(usage("-bond10 wants a -fred DFF")))
    val held  = dated.map((d, _) => durs.getOrElse(d, usage(s"$file: no duration for $d")))
    val r     = dated.map(_._2).toArray
    val dur   = held.map(_._1).toArray
    val ex    = dated.zip(held).map { case ((d, x), h) => x - cashAccrual(curve, h._2, d) }.toArray
    // sessions a year over the window, from the first return's purchase to the last return
    val span    = (daysFromCivil(dated.last._1) - daysFromCivil(held.head._2)).toDouble / 365.25
    val perYear = dated.length.toDouble / span
    val record  = MarketSim.bond10Readings(r, dur, ex, perYear)
    eprintln(f"${o.series}%s: ${r.length}%d returns $window%s, bond10 vol per duration ${record(0)}%.4f, depth vs vol ${record(1)}%.4f, " +
             f"excess ${record(2)}%.4f pts/yr at $perYear%.2f sessions a year; ${o.resamples}%d resamples, seed ${o.seed}%d")
    val reads = MarketSim.bond10Resamples(r, dur, ex, perYear, o.resamples, o.seed, run.block)
    if o.header then printBandHeader(Vector("-header"))
    val alpha = o.joint * 3.0 / (if o.of == 0 then 3 else o.of)
    val (c, edges) = MarketSim.recordBandJoint(reads, Vector(0, 1, 2), alpha)
    for (name, k) <- MarketSim.Bond10BandRows.zipWithIndex do
      val qs = bandQuantiles(record(k), reads.map(_(k)))
      val (lo, hi) = edges(k)
      println(f"${o.set}%s\t$name%s\t${o.series}%s\t$window%s\t${r.length}%d\t${o.resamples}%d\t${record(k)}%.6f\t" +
              qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")

  /** The bond row (`BondBandRows`) on a bond's return window (`-yahoo`, TLT): the record by
    * `bondReadings`, its block resamples by `bondResamples`, a joint band over the one row at this
    * window's share of the set's miss rate -- the same columns as the equity rows. */
  def printBondRows(o: Opts, r: Array[Double], window: String)(using run: Run): Unit =
    val record = MarketSim.bondReadings(r)
    eprintln(f"${o.series}%s: ${r.length}%d returns $window%s, bond depth vs vol ${record(0)}%.4f; " +
             s"${o.resamples} resamples, seed ${o.seed}")
    val reads = MarketSim.bondResamples(r, o.resamples, o.seed, run.block)
    if o.header then printBandHeader(Vector("-header"))
    val alpha = o.joint / (if o.of == 0 then 1 else o.of)
    val (c, edges) = MarketSim.recordBandJoint(reads, Vector(0), alpha)
    val qs = bandQuantiles(record(0), reads.map(_(0)))
    val (lo, hi) = edges(0)
    println(f"${o.set}%s\t${MarketSim.BondBandRows(0)}%s\t${o.series}%s\t$window%s\t${r.length}%d\t${o.resamples}%d\t${record(0)}%.6f\t" +
            qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")

  /** The single-history spreads the anchors' `postRateSd` and `postFloorSd` start from: the sd of
    * the rate's log, the floor share's sd over the record (an additive row). */
  def rateAfterSpreads(reads: Vector[Vector[Double]], floorRecord: Double): String =
    def sd(xs: Vector[Double]): Double =
      val m = xs.foldLeft(0.0)(_ + _) / xs.length
      math.sqrt(xs.foldLeft(0.0)((a, x) => a + (x - m) * (x - m)) / xs.length)
    val logs   = reads.map(x => math.log(x(0))).filter(_.isFinite)
    val shares = reads.map(_(1)).filter(_.isFinite)
    f"sd log rate ${sd(logs)}%.3f, floor share sd over record ${sd(shares) / floorRecord}%.3f"

  /** THE CONDITIONAL RATE ROWS (`-rateafter`) on a `-fred` rate beside a `-yahoo` or `-french`
    * equity window, joined on the equity's session dates: the record by `rateAfterOfReturns`,
    * its paired block resamples by `rateAfterResamples`, a joint band over the two -- the rate
    * rows' columns. */
  def printRateAfterRows(o: Opts, r: Array[Double], rate: Array[Double], window: String)(using run: Run): Unit =
    val record = MarketSim.rateAfterOfReturns(r, rate)
    eprintln(f"${o.series}%s: ${r.length}%d sessions $window%s joined with the rate; post-trough mean " +
             f"${record(0)}%.4f%%, ${record(1)}%.2f%% under the floor; ${o.resamples}%d resamples, seed ${o.seed}%d")
    val reads = MarketSim.rateAfterResamples(r, rate, o.resamples, o.seed, run.block)
    eprintln(s"  spreads: ${rateAfterSpreads(reads, record(1))}")
    if o.header then printBandHeader(Vector("-header"))
    val ks = MarketSim.RateAfterRows.indices.toVector
    val alpha = o.joint * ks.length / (if o.of == 0 then ks.length else o.of)
    val (c, edges) = MarketSim.recordBandJoint(reads, ks, alpha)
    for (k, (lo, hi)) <- ks.zip(edges) do
      val qs = bandQuantiles(record(k), reads.map(_(k)))
      println(f"${o.set}%s\t${MarketSim.RateAfterRows(k)}%s\t${o.series}%s\t$window%s\t${r.length}%d\t${o.resamples}%d\t${record(k)}%.6f\t" +
              qs.mkString("\t") + f"\t$c%.6f\t$lo%.6f\t$hi%.6f")
}

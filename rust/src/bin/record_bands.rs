//! THE RECORD BANDS: one real record's own sampling spread on every fidelity row a single daily
//! series can be read the model's way. The generator behind
//! `test-data/equity-anchors/recordbands-2026-09-26.tsv`, and the Rust twin of
//! `jsrc/recordBands.sc`.
//!
//! ```text
//!   cargo run --release --bin record_bands -- -header -set nasdaq -series QQQ \
//!       -yahoo <data>/yahoo/QQQ/prices.csv -from 1999-03-10 -to 2026-08-20
//!   cargo run --release --bin record_bands -- -set sp500 -series CRSP \
//!       -french <data>/french/F-F_Research_Data_Factors_daily.csv \
//!       -from 1954-01-04 -to 2026-06-30
//! ```
//!
//! Each row is read by `series_readings`, the functions the model reads a path with, so a band
//! cannot drift from the statistic it bands. Prints the record's reading and the resamples'
//! `RECORD_BAND_PCTS` as fixture rows. NOT SHIPPED: `Cargo.toml` excludes it from the published
//! crate, as it does the calibration search.

#![allow(
    clippy::print_stdout,
    clippy::print_stderr,
    reason = "a console tool: the fixture rows are its output and the input summary its receipt"
)]

#[cfg(feature = "fast-alloc")]
use uni::fast_alloc;
use uni::market_sim::{self as ms};

// the binary's allocator: see the `fast-alloc` feature
#[cfg(feature = "fast-alloc")]
#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

const USAGE: &str =
    "usage: record_bands (-yahoo FILE [-splice FILE -at YYYY-MM-DD]... [-column NAME] | -french FILE)
                    -from YYYY-MM-DD -to YYYY-MM-DD
                    -set NAME -series LABEL [-rows A,B] [-resamples N] [-seed S]
                    [-joint A] [-of N] [-header] [-coupling | -multiyear [-long]]
       record_bands -rateafter -fred DFF (-yahoo FILE | -french FILE) -from -to -set -series [-of N]
       record_bands -sectors DIR [-resamples N] [-seed S]
       record_bands -volexit (-closes FILE | -french FILE) -fred DFF -from -to -set -series
                    [-resamples N] [-seed S] [-joint A] [-of N] [-header]
       record_bands -volstate -yahoo FILE [-column NAME] [-splice FILE -at DATE]... -fred DFF
                    -from -to -set -series [-refseries LABEL] [-resamples N] [-seed S] [-joint A]
                    [-of N] [-header]
       record_bands -ledgerrows (-yahoo FILE [-column NAME] [-splice FILE -at DATE]... | -french FILE)
                    -fred DFF -from -to -set -series [-resamples N] [-seed S] [-joint A] [-header]
       record_bands -basket -closes WIDE.csv -closesdate YYYY-MM-DD [-from D] [-to D]
                    [-minsessions N] [-out RULER.tsv]
       record_bands -profits -fred CPATAX.csv -set NAME [-series LABEL] [-header]
       record_bands -unlagged OUT (-french FILE | -industry FILE -column NAME | -index FILE)
       record_bands -scales LEDGER.tsv -runs RUNS.tsv [-resamples N]
       record_bands -satellite -primary SPY.csv -sat QQQ.csv -set NAME [-from D] [-to D]
       record_bands -dividend (-shiller FILE | -closes FILE) -set NAME [-from D] [-to D]
       record_bands -resampled OUT -block 252|756 [-n N] [-seed S] [-from D] [-to D]
                    (-equity FILE)... [-fred FILE]...

  THE LAG-FREE RECORD (every mode): a computed daily index's sessions through 1999-03-10
                (`UNLAG_CUT`) are read with the one-day lag of stale prices removed
                (`unlag_returns`), on the sessions the record takes from the source -- the file's
                first session through its -splice date, or a splice file's sessions after its own,
                never cut by -from/-to. French's daily factors (the market) and industry files are
                such indexes. A `date,adj_close,dlog_adj_close` or `date,close,adj_close` file with
                a session on or before the cut is declared: -index FILE (a computed index, read
                lag-free) or -fund FILE (a fund, or a series already lag-free, read as recorded);
                undeclared, it is refused. -recorded reads every source as recorded
  -unlagged     THE LAG-FREE SOURCE written to OUT in its own layout, for a ruler outside this tool:
                -french FILE's Mkt-RF (the corrected total less RF), -industry FILE's -column, or
                -index FILE's `date,adj_close,dlog_adj_close` rows (the levels compounded from the
                corrected returns); the whole file is the source's taken range
  -worstcrash   THE WORST CRASH ROW instead (`worst crash %`, `worst_crash_of`): the deepest 15%+
                decline of the price the window's returns compound to, a record alone
  -satellite    THE SATELLITE ROWS (`SATELLITE_ROWS`, `sat_rows_of`): -sat FILE against
                -primary FILE, each a CSV with date and adj_close columns, on their shared
                sessions over -from..-to, a record alone
  -dividend     THE DIVIDEND YIELD ROW (`DIVIDEND_ROW`): -shiller FILE's monthly dividend over
                price, or -closes FILE's trailing-252-session distribution yield (adjusted close
                over close) averaged by calendar year, over -from..-to, a record alone; under
                -block the yield levels are resampled, keeping their dates
  -resampled    THE RECORD'S BLOCK RESAMPLES AS FILES, for a ruler outside this tool: -n copies
                (200) of every source at OUT/k/<its path>, each in its own layout. The first
                -equity file's sessions in -from..-to are the calendar each copy draws its
                -block blocks on (-seed); every source takes the same date blocks: an -equity
                file (French's daily factors, `date,close,adj_close[,dlog...]`,
                `date,adj_close,dlog_adj_close` or `date,open,high,low,close,adj_close,volume`)
                re-dated and compounded again, lag-free as its readers read it, the bar kept
                relative to its close; a -fred file's observations moved as levels to the
                block's session their dates fall in. Rows outside the calendar's span are kept
  -block        THE RESAMPLING BLOCK, 252 or 756: one or three years of the series' own
                observations (sessions, months, quarters) a block, where every mode otherwise
                draws its own (a year of sessions, five years of months); under it the modes that
                print a record alone (-coupling, -multiyear, -worstcrash, -timing, -profits,
                -cpipersist, -satellite, -dividend) print its resamples' quantiles too, in the
                band columns; -sectors DIR prints its graded rows (`SECTOR_ROWS`) in them
  -scales       THE RECORD SCALES of the ledger's rows: each run of RUNS.tsv (a line a run, the
                rows it supplies, `*` or names joined by `;`, then its arguments, tab-separated;
                `#` a comment) run as this tool at its own blocks, at -block 252 and at -block 756
                (-resamples N in place of its own), each printed row's (p95 - p5) / 3.29 read at
                each; one line a world and row of LEDGER.tsv's `row` column: world, row,
                scale252, scale756, scaleNative, scale (the largest of the three), resolution
                and knee, the last two from the resamples the scale came from -- the resolution
                the record's own vote on its sign against the table's `null` column (the share
                of resamples strictly on the record's side less the share strictly on the other,
                floored at 0), the knee the CRPS an indistinguishable world earns (the mean over
                the resamples of each one's CRPS under the others, in the row's scale); `none`
                where no run reaches the row (those listed on stderr by world), the resolution
                also without a null or with the record at it

  -rateafter    THE CONDITIONAL RATE ROWS (`RATE_AFTER_ROWS`): the -fred rate on the equity window's
                session dates, the two years after each 20% decline's trough; the record by
                `rate_after_of_returns`, its paired block resamples, their own joint band
  -timing       THE TIMING ROWS instead (`TIMING_ROWS`), on MONTH-END levels over -from..-to: what a
                10-month moving-average exit does on the record and the market's one-year trend, the
                rows of `timing-2026-09-30.tsv`, from -french FILE (CRSP's daily factors compounded,
                the ruler), -yahoo FILE (its log returns compounded, -splice as below) or -shiller
                FILE (a `month,price,dividend,cpi` CSV of Shiller's monthly S&P -- monthly AVERAGES,
                for comparison only)
  -volexit     THE VOLATILITY EXIT's rows (`VOL_EXIT_ROWS`): the simple exit on the printed close at
                1.5% / 2.0%, cash at the -fred rate, the 3x leg reset daily, each session joined to
                the rate at its start and the calendar days it spans; the record, its one-year
                block resamples (`vol_exit_resamples`), their joint band. -closes FILE is a
                `date,close,adj_close` CSV (Yahoo's printed and adjusted closes); -french FILE reads
                CRSP's daily total return as both, having no printed close
  -volstate     THE VOLATILITY STATE's rows (`VOL_STATE_ROWS`) as one family on one day series: the
                downside vol excess and the vol-timing edge on the series' log returns, the
                volatility exit's three rows on the exit's inputs; the record, its one-year-block
                resamples (`vol_state_resamples`), their joint band at -joint (default 0.05) x 5 /
                -of (default 5). The chain's files are told apart by themselves: a French daily
                industry file (-column NAME; its total return is the signal and the earnings, as
                -french is to -volexit) or a `date,close,adj_close` CSV (the printed close the
                signal, the adjusted close the earnings, its log return the two rows'); each
                -splice FILE -at DATE continues the chain with that file's sessions dated after
                DATE. The window's first session is the base: the sessions dated after -from
                through -to, each with the -fred rate at its previous session and the calendar
                days since it. The last spliced file's sessions alone are read again, banded the
                same way, and printed after the graded rows as the reference rows
                (`VOL_STATE_REFERENCE_ROWS`, series -refseries, default -series): QQQ's own
                window beside the long record
  -ledgerrows   THE LEDGER ROWS (`LEDGER_ROWS`): the volatility exit's surface over `VOL_EXIT_GRID`
                and its parts at 1.5% / 2.0%, the coupling of the volatility with the price, and the
                declines of 10% or more a century, on a -volstate chain or -french FILE (CRSP's
                daily total return as the close, the earnings and, as its log, the return the
                coupling and the declines read); the sessions after -from through
                -to, each with the -fred rate at its previous session and the calendar days since
                it; the record, its one-year-block resamples (`ledger_resamples`) and a joint band
                per family (`LEDGER_FAMILIES`) at -joint (default 0.05); then the one-day
                reversion (`ledger_lag1_of`) on the window's sessions after the lag cut
                (`UNLAG_CUT`) alone, its own resamples and band
  -basket       THE BASKET RULER for `-basketruler` (`basket_ruler_tsv`): -closes WIDE.csv is one row a
                session, `date`, the index's column named by its ticker, then one column per name,
                a name's cell empty before its listing (adjusted closes, dividends reinvested);
                -closesdate is the day they were taken, since an adjusted series is recomputed on
                every later distribution. -from / -to bound the window (default the file's);
                -minsessions (default 252) is the sessions a name needs to enter the per-name and
                pair ranges. Writes -out, or prints
  -profits      THE PROFIT DECLINE ROWS (`PROFIT_DECLINE_ROWS`): the -fred `observation_date,SERIES`
                quarterly levels (corporate profits after tax, CPATAX) read by `profit_decline_of`,
                the rows of `profits-2026-10-19.tsv` for -set; the episodes to stderr
  -sectors DIR  THE SECTOR ROWS instead: Ken French's `10_Industry_Portfolios.CSV`,
                `49_Industry_Portfolios.CSV` and `F-F_Research_Data_Factors.CSV` in DIR as the
                library publishes them (unzipped), the first monthly block of each; prints the
                momentum, trend and shape rows of `sectors-2026-09-30.tsv`, with 5-95
                block-bootstrap bands where a row has one

  -yahoo FILE   a daily series, in one of three published layouts told apart by the file itself:
                a `date,adj_close,dlog_adj_close` CSV of adjusted closes (Yahoo Finance's daily
                history, one row a session; the `dlog_adj_close` column, the first row the anchor
                price and skipped); FRED's `observation_date,DGSn` constant-maturity Treasury
                yield, read as an n-year par bond rebought every observation (the coupon accrues
                over the calendar days held, the price moves with the yield, semiannual
                compounding; `.` rows skipped); or Ken French's daily industry file
                (`10_Industry_Portfolios_Daily.CSV` as published), the value-weighted column
                -column NAME names, -99.99 rows skipped
  -splice FILE -at DATE
                a file continuing the series: the returns so far dated on or before DATE, then this
                file's dated after it, in any of the three layouts. Repeatable, applied in order
                (the Nasdaq splice: ^IXIC through 1985-10-01, ^NDX after; a record frozen at its
                licensed source's last date continues on a published proxy: TLT on DGS20, QQQ or
                the NDX on French's HiTec)
  -column NAME  the industry column a French industry file in the chain is read from
  -french FILE  Ken French's F-F_Research_Data_Factors_daily: Mkt-RF + RF compounded into an index
                WITHOUT a leading 1.0 over the window, then its log returns -- which drops the
                window's first session (the persistence fixture's rule)
  -rows A,B     print only these rows (default every `RECORD_BAND_ROWS` row)
  -resamples N  one-year-block resamples (default 20000)
  -seed S       the resampling stream's seed (default 20260918)
  -joint A      the share of the set's record-consistent worlds its joint band may miss
                (default 0.10), split over the set's record windows by their rows: this window's
                rows jointly miss A x (rows here) / N of the time
  -of N         the set's banded rows across all its windows (default: the rows printed here)
  -header       print the column header first
  -coupling     print the record's bubble coupling (`bubble_coupling_of`), largest 3-year run-up,
                longest calm stretch and 250-session variance ratio (`variance_ratio`) instead, the
                rows of `bubblebust-2026-09-24.tsv`: no resampling keeps the structure they measure
  -multiyear    print the record's multi-year rows (`multi_year_readings`) instead, the rows of
                `multiyear-2026-09-29.tsv`; with -long under their long-window names
  -bond10       print the 10-year leg's rows (`BOND10_BAND_ROWS`) of a -yahoo window instead -- FRED's
                DGS10 as a par bond, with -fred DFF as the short rate its excess return is read
                over: the record by `bond10_readings`, its resamples by `bond10_resamples`, their
                own joint band over the three rows
  -tips         THE REAL SIDE's rows (item 60), a mode of its own: -cpi CPIAUCSL -cpicentury
                CPIAUCNS -real DFII10 -breakeven T10YIE -nominal DGS10 -fred DFF -french <daily
                factors> -set S [-from -to] (the real window) [-cpifrom -cpito] (the CPI and
                real-rate window; the century file is read whole) [-resamples -seed -joint -of
                -header]; six groups (`CPI_BAND_ROWS`, `CPI_CENTURY_ROWS`, `REAL_YIELD_BAND_ROWS`,
                `BREAKEVEN_BAND_ROWS`, `TIPS_BAND_ROWS`, `REAL_RATE_BAND_ROWS`), each on its own
                record with its own joint band at the mode's share of -of (default 16, the mode's
                graded rows); the reported rows (`CPI_REPORTED_ROWS`, `REAL_YIELD_REPORTED_ROWS`,
                the 2022 TIPS real return) go to stderr
  -cpipersist   THE CPI PERSISTENCE ROW (`CPI_PERSISTENCE_ROW`, `cpi_persistence`) of -cpi over
                [-cpifrom -cpito] for -set, the rows of `cpipersist-2026-10-12.tsv`: the record's
                own reading, no resampling (the statistic straddles a block join in every pair),
                graded among the world's single histories of the window's length [-header]
  -stockbond    THE STOCK-BOND ROWS (`STOCK_BOND_BAND_ROWS`, `CPI_NEWS_ROW`) for -set, the rows of
                `stockbond-2026-10-18.tsv`: -nominal DGS10 as a par bond and -french <daily factors>
                over 21-session months of the sessions both trade in [-from -to], each month placed
                by -cpi's year-over-year rate of the calendar month before its first session; and
                the CPI news row over [-cpifrom -cpito]; two groups with their own joint bands at the
                mode's share of -of (default 5) [-resamples -seed -joint -header]; the reported rows
                (`STOCK_BOND_REPORTED_ROWS`) go to stderr
  -bondstress   THE BOND STRESS SHARE ROW (`BOND_STRESS_ROW`) for -set, a row of
                `recordbands-2026-09-26.tsv`: the spiral's stress index run on -fred DGS20 as a
                par bond of -years (20) over every session in [-from -to], a return spanning more
                than ten calendar days dropped with the state carried across the gap; the share of
                sessions above 0.5 after a one-year burn-in, in percent, its one-year-block
                resamples and their band at -of (default 1) [-resamples -seed -joint -header]";

fn usage(msg: &str) -> ! {
    if !msg.is_empty() {
        eprintln!("{msg}");
    }
    eprintln!("{USAGE}");
    std::process::exit(2)
}

/// THE LAG-FREE RECORD's switches for the run (`ms::unlag_returns`), read once in `main`:
/// `-recorded` reads every source as recorded; `-index FILE` and `-fund FILE` (each repeatable)
/// declare a Yahoo-format file holding sessions on or before `ms::UNLAG_CUT` a computed index,
/// read lag-free, or a fund, read as recorded.
#[derive(Default)]
struct Lag {
    recorded: bool,
    index: Vec<String>,
    fund: Vec<String>,
}

static LAG: std::sync::OnceLock<Lag> = std::sync::OnceLock::new();

/// The run's lag switches; none set, every computed index read lag-free.
fn lag() -> &'static Lag {
    LAG.get_or_init(Lag::default)
}

/// THE RESAMPLING BLOCK (`-block 252|756`): one or three years of the series' own observations
/// (sessions, months, quarters), the record-scale readings `-scales` takes; `None`, each mode's own
/// blocks (a year of sessions, five years of months), the fixtures'.
static BLOCK: std::sync::OnceLock<Option<usize>> = std::sync::OnceLock::new();

/// The run's block in years, `None` without `-block`.
fn block_years() -> Option<usize> {
    BLOCK.get().copied().flatten()
}

fn block_of(args: &[String]) -> Option<usize> {
    let i = args.iter().position(|a| a == "-block")?;
    match args.get(i + 1).map(String::as_str) {
        Some("252") => Some(1),
        Some("756") => Some(3),
        other => usage(&format!(
            "-block takes 252 or 756 (one or three years), not [{}]",
            other.unwrap_or("")
        )),
    }
}

fn lag_of(args: &[String]) -> Lag {
    let mut out = Lag::default();
    let mut it = args.iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "-recorded" => out.recorded = true,
            "-index" | "-fund" => {
                let f = it
                    .next()
                    .cloned()
                    .unwrap_or_else(|| usage(&format!("{a} wants a file")));
                if a == "-index" {
                    out.index.push(f);
                } else {
                    out.fund.push(f);
                }
            }
            _ => {}
        }
    }
    if let Some(f) = out.index.iter().find(|f| out.fund.contains(f)) {
        usage(&format!("{f} is declared both -index and -fund"));
    }
    out
}

/// `ms::unlag_returns` on a source's taken sessions, `(date, log return)`, unless the run reads
/// the record as recorded.
fn unlagged(days: Vec<(String, f64)>) -> Vec<(String, f64)> {
    if lag().recorded {
        return days;
    }
    let out = {
        let dates: Vec<&str> = days.iter().map(|(d, _)| d.as_str()).collect();
        let r: Vec<f64> = days.iter().map(|(_, x)| *x).collect();
        ms::unlag_returns(&dates, &r)
    };
    days.into_iter()
        .zip(out)
        .map(|((d, _), x)| (d, x))
        .collect()
}

/// Whether a Yahoo-format file's taken sessions (the first dated `first`) are a computed index's,
/// read lag-free: declared `-index`; a fund's (`-fund`), or a file with no session on or before
/// `ms::UNLAG_CUT`, is read as recorded; an undeclared file with such a session is refused.
fn yahoo_is_index(file: &str, first: Option<&str>) -> bool {
    let l = lag();
    if l.recorded || l.fund.iter().any(|f| f == file) {
        return false;
    }
    if l.index.iter().any(|f| f == file) {
        return true;
    }
    if first.is_some_and(|d| d <= ms::UNLAG_CUT) {
        usage(&format!(
            "{file} holds sessions on or before {}: declare it -index {file} (a computed index, \
             read lag-free) or -fund {file} (read as recorded)",
            ms::UNLAG_CUT
        ));
    }
    false
}

/// The `(lo, hi]` date range each file of a chain gives: the first file through the first
/// splice's date, each splice file after its own date through the next's; the dates must rise.
fn taken_ranges<'a>(
    file: &'a str,
    splices: &'a [(String, String)],
) -> Vec<(&'a str, Option<&'a str>, Option<&'a str>)> {
    if splices.windows(2).any(|w| w[1].1 <= w[0].1) {
        usage("each -splice's -at date must follow the one before");
    }
    let mut out = vec![(file, None, splices.first().map(|s| s.1.as_str()))];
    for (k, (f, at)) in splices.iter().enumerate() {
        out.push((
            f.as_str(),
            Some(at.as_str()),
            splices.get(k + 1).map(|s| s.1.as_str()),
        ));
    }
    out
}

/// Whether a date lies in a taken range `(lo, hi]`.
fn in_taken(d: &str, lo: Option<&str>, hi: Option<&str>) -> bool {
    lo.is_none_or(|lo| d > lo) && hi.is_none_or(|hi| d <= hi)
}

/// Where the record comes from: one of the three file layouts the fixture is built from.
enum Source {
    Yahoo(String),
    French(String),
    /// FRED's daily `date,value` CSV (the effective federal funds rate, DFF): a level in percent,
    /// read on Monday-Friday dates, for the rate rows (`-rate`)
    Fred(String),
}

struct Opts {
    source: Source,
    from: String,
    to: String,
    set: String,
    series: String,
    rows: Vec<String>,
    resamples: usize,
    seed: u64,
    joint: f64,
    of: usize,
    header: bool,
    coupling: bool,
    /// `-worstcrash`: the worst crash row on the window
    worst: bool,
    multiyear: bool,
    long: bool,
    rate: bool,
    bond: bool,
    bond10: bool,
    /// `-rateafter`: the rate file read beside the equity source
    after_rate: Option<String>,
    /// `-bond10`: the short rate (FRED DFF) its excess return is read over
    cash: Option<String>,
    /// `-splice FILE -at DATE`, in order: each file continues the series after its date
    splice: Vec<(String, String)>,
    /// `-column NAME`: the column a French industry file in the chain is read from
    column: Option<String>,
}

/// A flag's numeric value, or the usage line.
fn num<T: std::str::FromStr>(v: &str, msg: &str) -> T {
    v.parse().unwrap_or_else(|_| usage(msg))
}

#[expect(
    clippy::too_many_lines,
    reason = "one flag table: every option of the band modes, read once, in one place"
)]
fn parse_args(args: &[String]) -> Opts {
    let (mut yahoo, mut french, mut from, mut to) = (None, None, None, None);
    let (mut set, mut series, mut rows) = (String::new(), String::new(), Vec::new());
    let (mut resamples, mut seed, mut header) = (20_000usize, 20_260_918u64, false);
    let mut coupling = false;
    let mut worst = false;
    let (mut multiyear, mut long) = (false, false);
    let mut rate = false;
    let mut bond = false;
    let mut bond10 = false;
    let mut rateafter = false;
    let mut fred: Option<String> = None;
    let (mut joint, mut of) = (0.10f64, 0usize);
    let mut splice: Vec<(String, String)> = Vec::new();
    let mut pending: Option<String> = None;
    let mut column: Option<String> = None;
    let mut it = args.iter();
    while let Some(a) = it.next() {
        let mut next = || {
            it.next()
                .cloned()
                .unwrap_or_else(|| usage(&format!("{a} needs a value")))
        };
        match a.as_str() {
            "-yahoo" => yahoo = Some(next()),
            "-french" => french = Some(next()),
            "-fred" => fred = Some(next()),
            "-splice" => {
                if pending.is_some() {
                    usage("-splice FILE wants its -at DATE before the next -splice");
                }
                pending = Some(next());
            }
            "-at" => {
                let f = pending
                    .take()
                    .unwrap_or_else(|| usage("-at DATE follows a -splice FILE"));
                splice.push((f, next()));
            }
            "-column" => column = Some(next()),
            "-from" => from = Some(next()),
            "-to" => to = Some(next()),
            "-set" => set = next(),
            "-series" => series = next(),
            "-rows" => rows = next().split(',').map(|s| s.trim().to_string()).collect(),
            "-resamples" => resamples = num(&next(), "-resamples wants an integer"),
            "-seed" => seed = num(&next(), "-seed wants a non-negative integer"),
            "-joint" => joint = num(&next(), "-joint wants a share in (0, 1)"),
            "-of" => of = num(&next(), "-of wants a row count"),
            "-header" => header = true,
            "-coupling" => coupling = true,
            "-worstcrash" => worst = true,
            "-multiyear" => multiyear = true,
            "-long" => long = true,
            "-rate" => rate = true,
            "-bond" => bond = true,
            "-bond10" => bond10 = true,
            "-rateafter" => rateafter = true,
            // the lag-free record's switches, read by `lag_of`
            "-recorded" => {}
            // the resamples behind each band row, for `-scales` (`band_quantiles`)
            "-raw" => {}
            "-index" | "-fund" => {
                next();
            }
            // the resampling block, read by `block_of`
            "-block" => {
                next();
            }
            other => usage(&format!("unrecognized arg [{other}]")),
        }
    }
    // `-rateafter` reads a rate beside an equity source; every other mode exactly one source
    let after_rate = rateafter.then(|| {
        fred.take()
            .unwrap_or_else(|| usage("-rateafter wants a -fred rate"))
    });
    // `-bond10` reads the short rate beside its DGS10 file, for the excess-return row
    let cash = bond10.then(|| {
        fred.take()
            .unwrap_or_else(|| usage("-bond10 wants a -fred DFF beside the DGS10 file"))
    });
    let source = match (yahoo, french, fred) {
        (Some(f), None, None) => Source::Yahoo(f),
        (None, Some(f), None) => Source::French(f),
        (None, None, Some(f)) => Source::Fred(f),
        _ => usage("give exactly one of -yahoo, -french and -fred"),
    };
    if rate != matches!(source, Source::Fred(_)) {
        usage("-rate reads a -fred file, and a -fred file is read by -rate");
    }
    if pending.is_some() {
        usage("-splice FILE -at DATE go together");
    }
    if (!splice.is_empty() || column.is_some()) && !matches!(source, Source::Yahoo(_)) {
        usage("-splice and -column continue a -yahoo series");
    }
    let (Some(from), Some(to)) = (from, to) else {
        usage("-from and -to are required")
    };
    if set.is_empty() || series.is_empty() {
        usage("-set and -series are required");
    }
    if let Some(r) = rows
        .iter()
        .find(|r| !ms::RECORD_BAND_ROWS.contains(&r.as_str()))
    {
        usage(&format!(
            "-rows names [{r}], which is not a record-band row"
        ));
    }
    Opts {
        source,
        from,
        to,
        set,
        series,
        rows,
        resamples,
        seed,
        joint,
        of,
        header,
        coupling,
        worst,
        multiyear,
        long,
        rate,
        bond,
        bond10,
        after_rate,
        cash,
        splice,
        column,
    }
}

/// `(date, log return)` for every session of a `date,adj_close,dlog_adj_close` file after its first.
fn read_yahoo(file: &str) -> Vec<(String, f64)> {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    let mut lines = text.lines();
    let header: Vec<&str> = lines.next().unwrap_or("").split(',').collect();
    let col = header
        .iter()
        .position(|h| h.trim() == "dlog_adj_close")
        .unwrap_or_else(|| usage(&format!("{file}: no dlog_adj_close column")));
    lines
        .skip(1)
        .filter(|l| !l.trim().is_empty())
        .map(|l| {
            let f: Vec<&str> = l.split(',').collect();
            let v = f
                .get(col)
                .and_then(|s| s.trim().parse::<f64>().ok())
                .unwrap_or_else(|| usage(&format!("{file}: unreadable row [{l}]")));
            (f[0].trim().to_string(), v)
        })
        .collect()
}

/// What a daily file is to the lag-free record: a Yahoo-format file (an index or a fund, as
/// declared), a rate series (as recorded) or a French industry file (a computed index).
#[derive(Clone, Copy, PartialEq)]
enum Layout {
    Yahoo,
    Rate,
    Industry,
}

/// `(date, log return)` of a daily series in whichever of the three layouts the file is in, as
/// recorded: a `-yahoo` CSV (`read_yahoo`), FRED's `observation_date,DGSn` yields
/// (`read_treasury`) or Ken French's daily industry file (`read_industry`, the `-column` named).
fn read_daily(file: &str, column: Option<&str>) -> (Layout, Vec<(String, f64)>) {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    let head: Vec<&str> = text
        .lines()
        .next()
        .unwrap_or("")
        .split(',')
        .map(str::trim)
        .collect();
    if head.contains(&"dlog_adj_close") {
        return (Layout::Yahoo, read_yahoo(file));
    }
    if head.len() == 2 && head[0] == "observation_date" && head[1].starts_with("DGS") {
        let years: f64 = head[1][3..]
            .parse()
            .unwrap_or_else(|_| usage(&format!("{file}: [{}] is not a DGSn series", head[1])));
        return (Layout::Rate, read_treasury(&text, years));
    }
    if text[..text.len().min(400)]
        .to_ascii_lowercase()
        .contains("industry portfolios")
    {
        let col = column
            .unwrap_or_else(|| usage(&format!("{file}: an industry file needs -column NAME")));
        return (Layout::Industry, read_industry(file, &text, col));
    }
    usage(&format!(
        "{file}: not a dlog_adj_close CSV, a FRED DGSn series or a French industry file"
    ))
}

/// A daily series continued by each `-splice FILE -at DATE` in turn: the returns so far dated on
/// or before the date, then that file's dated after it; each file's taken sessions read lag-free
/// where it is a computed index (`unlagged`: an industry file, a Yahoo file declared `-index`).
fn read_daily_chain(
    file: &str,
    splices: &[(String, String)],
    column: Option<&str>,
) -> Vec<(String, f64)> {
    taken_ranges(file, splices)
        .into_iter()
        .flat_map(|(f, lo, hi)| {
            let (layout, all) = read_daily(f, column);
            let days: Vec<(String, f64)> = all
                .into_iter()
                .filter(|(d, _)| in_taken(d, lo, hi))
                .collect();
            let index = match layout {
                Layout::Industry => true,
                Layout::Yahoo => yahoo_is_index(f, days.first().map(|(d, _)| d.as_str())),
                Layout::Rate => false,
            };
            if index { unlagged(days) } else { days }
        })
        .collect()
}

/// The `-splice FILE -at DATE` pairs of an argument list, in order.
fn splices_of(args: &[String]) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let mut pending: Option<String> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "-splice" => {
                if pending.is_some() {
                    usage("-splice FILE wants its -at DATE before the next -splice");
                }
                pending = args.get(i + 1).cloned();
                i += 1;
            }
            "-at" => {
                let f = pending
                    .take()
                    .unwrap_or_else(|| usage("-at DATE follows a -splice FILE"));
                out.push((
                    f,
                    args.get(i + 1)
                        .cloned()
                        .unwrap_or_else(|| usage("-at needs a date")),
                ));
                i += 1;
            }
            _ => {}
        }
        i += 1;
    }
    if pending.is_some() {
        usage("-splice FILE -at DATE go together");
    }
    out
}

/// Days since 1970-01-01 of an ISO date (proleptic Gregorian).
fn days_from_civil(d: &str) -> i64 {
    let p = |a: usize, b: usize| {
        d.get(a..b)
            .and_then(|x| x.parse::<i64>().ok())
            .unwrap_or_else(|| usage(&format!("[{d}] is not a YYYY-MM-DD date")))
    };
    let (y, m, day) = (p(0, 4), p(5, 7), p(8, 10));
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// The price of a par bond of coupon `c` at yield `y` with `t` years left, semiannual, per unit
/// face; `ln_det`/`exp_det` so the twins agree to the bit.
fn par_price(c: f64, y: f64, t: f64) -> f64 {
    if t <= 0.0 {
        return 1.0;
    }
    let v = ms::exp_det(-2.0 * t * ms::ln_det(1.0 + y / 2.0));
    // the closed form holds for a negative yield too (a real yield below zero); only zero divides
    if y != 0.0 {
        (c / y) * (1.0 - v) + v
    } else {
        1.0 + c * t
    }
}

/// `(date, log return)` of an n-year par Treasury rebought at every observation of a FRED
/// `observation_date,DGSn` yield file: bought at par at the last yield, sold at the next
/// observation at the new yield with the coupon accrued over the calendar days held.
fn read_treasury(text: &str, years: f64) -> Vec<(String, f64)> {
    let obs: Vec<(String, f64)> = text
        .lines()
        .skip(1)
        .filter_map(|l| {
            let f: Vec<&str> = l.split(',').map(str::trim).collect();
            let v: f64 = f.get(1)?.parse().ok()?;
            Some((f[0].to_string(), v / 100.0))
        })
        .collect();
    (1..obs.len())
        .map(|i| {
            let dt = (days_from_civil(&obs[i].0) - days_from_civil(&obs[i - 1].0)) as f64 / 365.25;
            let (y0, y1) = (obs[i - 1].1, obs[i].1);
            (
                obs[i].0.clone(),
                ms::ln_det(par_price(y0, y1, years - dt) + y0 * dt),
            )
        })
        .collect()
}

/// `(date, log return)` of one column of Ken French's daily industry file, the first block (the
/// value-weighted returns, in percent); a -99.99 cell is a missing day and is skipped.
fn read_industry(file: &str, text: &str, column: &str) -> Vec<(String, f64)> {
    read_industry_simple(file, text, column)
        .into_iter()
        .map(|(d, x)| (d, ms::ln_det(1.0 + x)))
        .collect()
}

/// The index of `column` in the header of Ken French's daily value-weighted industry block.
fn industry_column(file: &str, text: &str, column: &str) -> usize {
    let header: Vec<&str> = text
        .lines()
        .skip_while(|l| !l.contains("Value Weighted Returns -- Daily"))
        .nth(1)
        .unwrap_or_else(|| usage(&format!("{file}: no daily value-weighted block")))
        .split(',')
        .map(str::trim)
        .collect();
    header.iter().position(|h| *h == column).unwrap_or_else(|| {
        usage(&format!(
            "{file}: no column [{column}]; the file has {}",
            header[1..].join(" ")
        ))
    })
}

/// `(date, simple return)` for every dated row of the daily value-weighted block of Ken French's
/// industry file whose `column` holds a return (-99.99 rows skipped), as recorded.
fn read_industry_simple(file: &str, text: &str, column: &str) -> Vec<(String, f64)> {
    let col = industry_column(file, text, column);
    let mut lines = text
        .lines()
        .skip_while(|l| !l.contains("Value Weighted Returns -- Daily"));
    lines.next();
    lines.next();
    lines
        .map(|l| l.split(',').map(str::trim).collect::<Vec<&str>>())
        .take_while(|f| f[0].len() == 8 && f[0].bytes().all(|b| b.is_ascii_digit()))
        .filter_map(|f| {
            let v: f64 = f.get(col)?.parse().ok()?;
            (v > -99.0).then(|| {
                let d = f[0];
                (format!("{}-{}-{}", &d[0..4], &d[4..6], &d[6..8]), v / 100.0)
            })
        })
        .collect()
}

/// `(date, Mkt-RF + RF in percent)` for every dated row of Ken French's daily factor file, the
/// market read lag-free (`unlagged`, on its log total return, the whole file its taken range); a
/// session after the cut keeps its recorded figure to the bit.
fn read_french(file: &str) -> Vec<(String, f64)> {
    let raw = read_french_recorded(file);
    let logs: Vec<(String, f64)> = raw
        .iter()
        .map(|(d, x)| (d.clone(), ms::ln_det(1.0 + x / 100.0)))
        .collect();
    raw.into_iter()
        .zip(unlagged(logs))
        .map(|((d, x), (_, r))| {
            let v = if !lag().recorded && d.as_str() <= ms::UNLAG_CUT {
                100.0 * (ms::exp_det(r) - 1.0)
            } else {
                x
            };
            (d, v)
        })
        .collect()
}

/// `(date, Mkt-RF + RF in percent)` for every dated row of Ken French's daily factor file, as
/// recorded.
fn read_french_recorded(file: &str) -> Vec<(String, f64)> {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    text.lines()
        .filter_map(|l| {
            let f: Vec<&str> = l.split(',').map(str::trim).collect();
            let d = f.first()?;
            if d.len() != 8 || !d.bytes().all(|b| b.is_ascii_digit()) || f.len() < 5 {
                return None;
            }
            let mkt: f64 = f[1].parse().ok()?;
            let rf: f64 = f[4].parse().ok()?;
            Some((format!("{}-{}-{}", &d[0..4], &d[4..6], &d[6..8]), mkt + rf))
        })
        .collect()
}

/// `(date, rate as a decimal)` for every Monday-Friday date of FRED's daily `date,value` CSV
/// (`observation_date,DFF` in fredgraph's spelling) whose value is a number.
fn read_fred(file: &str) -> Vec<(String, f64)> {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    text.lines()
        .skip(1)
        .filter_map(|l| {
            let f: Vec<&str> = l.split(',').map(str::trim).collect();
            let d = *f.first()?;
            let v: f64 = f.get(1)?.parse().ok()?;
            weekday(d).then(|| (d.to_string(), v / 100.0))
        })
        .collect()
}

/// Whether an ISO date falls on Monday to Friday (Zeller, proleptic Gregorian).
fn weekday(d: &str) -> bool {
    let p = |a: usize, b: usize| d.get(a..b).and_then(|x| x.parse::<i64>().ok());
    let (Some(y), Some(m), Some(day)) = (p(0, 4), p(5, 7), p(8, 10)) else {
        return false;
    };
    let (y, m) = if m < 3 { (y - 1, m + 12) } else { (y, m) };
    let k = y % 100;
    let j = y / 100;
    // 0 = Saturday
    let h = (day + 13 * (m + 1) / 5 + k + k / 4 + j / 4 + 5 * j).rem_euclid(7);
    h >= 2
}

/// The window's daily log returns, each with the date it ended on -- or, on a `-fred` source,
/// the window's daily rate levels, no session dropped.
fn returns_in_window(o: &Opts) -> Vec<(String, f64)> {
    let in_window = |d: &str| d >= o.from.as_str() && d <= o.to.as_str();
    match &o.source {
        Source::Fred(f) => read_fred(f)
            .into_iter()
            .filter(|(d, _)| in_window(d))
            .collect(),
        Source::Yahoo(f) => read_daily_chain(f, &o.splice, o.column.as_deref())
            .into_iter()
            .filter(|(d, _)| in_window(d))
            .collect(),
        Source::French(f) => {
            let days: Vec<(String, f64)> = read_french(f)
                .into_iter()
                .filter(|(d, _)| in_window(d))
                .collect();
            let mut idx = Vec::with_capacity(days.len());
            let mut p = 1.0;
            for (_, x) in &days {
                p *= 1.0 + x / 100.0;
                idx.push(p);
            }
            (1..days.len())
                // `ln_det`, not the native log, which differs from the JVM's in the last bit on
                // about 0.2% of inputs: the twins must read the same returns to the bit, or the
                // joint band's ranks tie differently
                .map(|k| (days[k].0.clone(), ms::ln_det(idx[k] / idx[k - 1])))
                .collect()
        }
    }
}

/// `-sectors DIR [-resamples N] [-seed S]`: the sector rows, then done. False when the arguments
/// name no `-sectors`.
/// `(month, total-return level)` from Shiller's monthly S&P as a `month,price,dividend,cpi` CSV
/// (the workbook's Data sheet: Date, P, D, CPI): the level compounds each month's price ratio
/// with the dividend rate over twelve on the prior price, a blank dividend carrying the last one.
fn read_shiller_monthly(file: &str) -> Vec<(String, f64)> {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    let mut out: Vec<(String, f64)> = Vec::new();
    let mut prev_p = f64::NAN;
    let mut last_d = f64::NAN;
    let mut level = f64::NAN;
    for l in text.lines().skip(1) {
        let f: Vec<&str> = l.split(',').map(str::trim).collect();
        if f.len() < 3 {
            continue;
        }
        let p: f64 = f[1]
            .parse()
            .unwrap_or_else(|_| usage(&format!("{file}: price [{}]", f[1])));
        if let Ok(d) = f[2].parse::<f64>() {
            last_d = d;
        }
        level = if prev_p.is_nan() {
            p
        } else {
            level * (p + last_d / 12.0) / prev_p
        };
        prev_p = p;
        out.push((f[0].to_string(), level));
    }
    out
}

/// `(month, total-return level at the month's last session)` from Ken French's daily factors:
/// Mkt-RF + RF compounded from the window's first session, the level at each calendar month's
/// last session.
fn read_french_month_ends(file: &str, from: &str, to: &str) -> Vec<(String, f64)> {
    let days: Vec<(String, f64)> = read_french(file)
        .into_iter()
        .filter(|(d, _)| d.as_str() >= from && d.as_str() <= to)
        .collect();
    let mut out: Vec<(String, f64)> = Vec::new();
    let mut level = 1.0;
    for (k, (d, x)) in days.iter().enumerate() {
        level *= 1.0 + x / 100.0;
        if k + 1 == days.len() || days[k + 1].0[..7] != d[..7] {
            out.push((d[..7].to_string(), level));
        }
    }
    out
}

/// `(month, level at the month's last session)` from dated daily log returns: the returns
/// summed from the first, the level `exp_det` of the sum, so the twins read the same levels.
fn log_month_ends(days: &[(String, f64)]) -> Vec<(String, f64)> {
    let mut out: Vec<(String, f64)> = Vec::new();
    let mut c = 0.0;
    for (k, (d, x)) in days.iter().enumerate() {
        c += x;
        if k + 1 == days.len() || days[k + 1].0[..7] != d[..7] {
            out.push((d[..7].to_string(), ms::exp_det(c)));
        }
    }
    out
}

/// THE TIMING ROWS (`-timing`): the four rows of `timing_of_monthly` on month-end levels, from
/// `-french FILE` (CRSP's daily factors, the ruler), `-yahoo FILE` (its log returns, continued by
/// each `-splice FILE -at DATE`) or `-shiller FILE` (monthly AVERAGES, for the comparison the fixture's
/// header states), over `-from`..`-to` (YYYY-MM-DD or YYYY-MM), for the set named.
fn timing_mode(args: &[String]) -> bool {
    if !args.iter().any(|a| a == "-timing") {
        return false;
    }
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    let from = opt("-from").unwrap_or_default();
    let to = opt("-to").unwrap_or_else(|| "9999-99-99".to_string());
    let set = opt("-set").unwrap_or_else(|| usage("-set is required"));
    let series = opt("-series").unwrap_or_else(|| usage("-series is required"));
    let rows: Vec<(String, f64)> = match (opt("-french"), opt("-shiller"), opt("-yahoo")) {
        (Some(file), None, None) => read_french_month_ends(&file, &from, &to),
        (None, Some(file), None) => read_shiller_monthly(&file)
            .into_iter()
            .filter(|(m, _)| m.as_str() >= &from[..from.len().min(7)] && m.as_str() <= &to[..7])
            .collect(),
        (None, None, Some(file)) => {
            let days: Vec<(String, f64)> =
                read_daily_chain(&file, &splices_of(args), opt("-column").as_deref())
                    .into_iter()
                    .filter(|(d, _)| d.as_str() >= from.as_str() && d.as_str() <= to.as_str())
                    .collect();
            log_month_ends(&days)
        }
        _ => usage("-timing wants exactly one of -french FILE, -yahoo FILE and -shiller FILE"),
    };
    if rows.len() < 24 {
        usage("the window holds fewer than two years of months");
    }
    let levels: Vec<f64> = rows.iter().map(|(_, x)| *x).collect();
    let window = format!("{}..{}", rows[0].0, rows[rows.len() - 1].0);
    if let Some(y) = block_years() {
        print_band_header(args);
        let (resamples, seed, joint) = resampling_of(args);
        let reads = level_resamples(&levels, 12, y, (resamples, seed), ms::timing_of_monthly);
        print_resampled_rows(
            &set,
            &series,
            &window,
            levels.len(),
            &ms::TIMING_ROWS,
            &ms::timing_of_monthly(&levels),
            &reads,
            joint,
        );
        return true;
    }
    if args.iter().any(|a| a == "-header") {
        println!("set\trow\tseries\twindow\tn\trecord");
    }
    for (name, value) in ms::TIMING_ROWS.iter().zip(ms::timing_of_monthly(&levels)) {
        println!(
            "{set}\t{name}\t{series}\t{window}\t{}\t{value:.6}",
            levels.len()
        );
    }
    true
}

/// Days from 1970-01-01 of an ISO date (Hinnant's `days_from_civil`).
fn civil_days(d: &str) -> i64 {
    let p = |a: usize, b: usize| -> i64 {
        d.get(a..b)
            .and_then(|x| x.parse().ok())
            .unwrap_or_else(|| usage(&format!("not a date [{d}]")))
    };
    let (y0, m, day) = (p(0, 4), p(5, 7), p(8, 10));
    let y = if m <= 2 { y0 - 1 } else { y0 };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// `(date, printed close, adjusted close)` for every session of a `date,close,adj_close` CSV.
fn read_closes(file: &str) -> Vec<(String, f64, f64)> {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    text.lines()
        .skip(1)
        .filter(|l| !l.trim().is_empty())
        .map(|l| {
            let f: Vec<&str> = l.split(',').map(str::trim).collect();
            let num = |k: usize| -> f64 {
                f.get(k)
                    .and_then(|x| x.parse().ok())
                    .unwrap_or_else(|| usage(&format!("{file}: unreadable row [{l}]")))
            };
            (f[0].to_string(), num(1), num(2))
        })
        .collect()
}

/// `read_closes`, a file declared `-index` read lag-free: the printed close's and the adjusted
/// close's log returns each `unlagged`, the levels compounded again from the first row's.
fn closes_lag_free(file: &str) -> Vec<(String, f64, f64)> {
    let levels = read_closes(file);
    if !yahoo_is_index(file, levels.get(1).map(|(d, _, _)| d.as_str())) {
        return levels;
    }
    let leg = |k: usize| -> Vec<f64> {
        let at = |i: usize| if k == 0 { levels[i].1 } else { levels[i].2 };
        let days: Vec<(String, f64)> = (1..levels.len())
            .map(|i| (levels[i].0.clone(), ms::ln_det(at(i) / at(i - 1))))
            .collect();
        let mut lv = vec![at(0)];
        for (_, r) in unlagged(days) {
            let last = lv[lv.len() - 1];
            lv.push(last * ms::exp_det(r));
        }
        lv
    };
    let (close, total) = (leg(0), leg(1));
    levels
        .into_iter()
        .enumerate()
        .map(|(i, (d, _, _))| (d, close[i], total[i]))
        .collect()
}

/// `(date, adjusted close)` of a CSV whose header names a `date` and an `adj_close` column.
fn read_adj_closes(file: &str) -> Vec<(String, f64)> {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    let mut lines = text.lines();
    let head: Vec<&str> = lines
        .next()
        .unwrap_or("")
        .split(',')
        .map(str::trim)
        .collect();
    let col = |name: &str| -> usize {
        head.iter()
            .position(|h| *h == name)
            .unwrap_or_else(|| usage(&format!("{file}: no [{name}] column")))
    };
    let (d, a) = (col("date"), col("adj_close"));
    lines
        .filter(|l| !l.trim().is_empty())
        .map(|l| {
            let f: Vec<&str> = l.split(',').map(str::trim).collect();
            let v = f
                .get(a)
                .and_then(|x| x.parse().ok())
                .unwrap_or_else(|| usage(&format!("{file}: unreadable row [{l}]")));
            (f[d].to_string(), v)
        })
        .collect()
}

/// `-satellite`: THE SATELLITE ROWS (`SATELLITE_ROWS`, `sat_rows_of`) of `-sat FILE` against
/// `-primary FILE` (each a CSV with `date` and `adj_close` columns, funds read as recorded), on
/// their shared sessions over -from..-to: the record alone; under `-block` the joined sessions'
/// resamples too, both legs taking the same blocks and compounding again from their first levels.
fn satellite_mode(args: &[String]) -> bool {
    if !args.iter().any(|a| a == "-satellite") {
        return false;
    }
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    let req = |flag: &str| opt(flag).unwrap_or_else(|| usage(&format!("-satellite wants {flag}")));
    let (set, series) = (
        req("-set"),
        opt("-series").unwrap_or_else(|| "SPY+QQQ".to_string()),
    );
    let from = opt("-from").unwrap_or_default();
    let to = opt("-to").unwrap_or_else(|| "9999".to_string());
    let primary = read_adj_closes(&req("-primary"));
    let sat: std::collections::HashMap<String, f64> =
        read_adj_closes(&req("-sat")).into_iter().collect();
    let joined: Vec<(String, f64, f64)> = primary
        .into_iter()
        .filter(|(d, _)| d.as_str() >= from.as_str() && d.as_str() <= to.as_str())
        .filter_map(|(d, p)| sat.get(&d).map(|s| (d, p, *s)))
        .collect();
    if joined.len() <= 252 {
        usage(&format!(
            "the joined window holds {} sessions",
            joined.len()
        ));
    }
    let lp: Vec<f64> = joined.iter().map(|x| x.1).collect();
    let ls: Vec<f64> = joined.iter().map(|x| x.2).collect();
    let window = format!("{}..{}", joined[0].0, joined[joined.len() - 1].0);
    let record = ms::sat_rows_of(&lp, &ls);
    let names = ms::SATELLITE_ROWS;
    if let Some(y) = block_years() {
        print_band_header(args);
        let (resamples, seed, joint) = resampling_of(args);
        let rp: Vec<f64> = lp.windows(2).map(|w| ms::ln_det(w[1] / w[0])).collect();
        let rs: Vec<f64> = ls.windows(2).map(|w| ms::ln_det(w[1] / w[0])).collect();
        let idx: Vec<f64> = (0..rp.len()).map(|i| i as f64).collect();
        let level = |x0: f64, r: &[f64], pick: &[f64]| -> Vec<f64> {
            let mut lv = Vec::with_capacity(pick.len() + 1);
            lv.push(x0);
            let mut c = 0.0;
            for &i in pick {
                c += r[i as usize];
                lv.push(x0 * ms::exp_det(c));
            }
            lv
        };
        let reads = ms::series_resamples(&idx, 252, y, resamples, seed, |x| {
            ms::sat_rows_of(&level(lp[0], &rp, x), &level(ls[0], &rs, x))
        });
        print_resampled_rows(
            &set,
            &series,
            &window,
            joined.len(),
            &names,
            &record,
            &reads,
            joint,
        );
        return true;
    }
    if args.iter().any(|a| a == "-header") {
        println!("set\trow\tseries\twindow\tn\trecord");
    }
    for (name, value) in names.iter().zip(record) {
        println!(
            "{set}\t{name}\t{series}\t{window}\t{}\t{value:.6}",
            joined.len()
        );
    }
    true
}

/// `-dividend`: THE DIVIDEND YIELD ROW (`DIVIDEND_ROW`) as `dividend-2026-09-02.tsv` reads it:
/// `-shiller FILE` (a `month,price,dividend,cpi` CSV of Shiller's monthly S&P) the mean of the
/// monthly dividend over price, in percent, over the months in -from..-to; or `-closes FILE` (a
/// `date,close,adj_close` CSV of a fund, read as recorded) the trailing-252-session distribution
/// yield off the adjusted-close-to-close ratio, averaged by calendar year over the sessions in
/// -from..-to, then over the years. The record alone; under `-block` the yield LEVELS' resamples
/// too (the row is a mean of a persistent level), the sessions or months keeping their dates.
fn dividend_mode(args: &[String]) -> bool {
    if !args.iter().any(|a| a == "-dividend") {
        return false;
    }
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    let set = opt("-set").unwrap_or_else(|| usage("-dividend wants -set"));
    let from = opt("-from").unwrap_or_default();
    let to = opt("-to").unwrap_or_else(|| "9999".to_string());
    let (series, per_year, dated): (String, usize, Vec<(String, f64)>) =
        match (opt("-shiller"), opt("-closes")) {
            (Some(f), None) => {
                let text =
                    std::fs::read_to_string(&f).unwrap_or_else(|e| usage(&format!("{f}: {e}")));
                let dp = text
                    .lines()
                    .skip(1)
                    .filter_map(|l| {
                        let c: Vec<&str> = l.split(',').map(str::trim).collect();
                        let p: f64 = c.get(1)?.parse().ok()?;
                        let d: f64 = c.get(2)?.parse().ok()?;
                        Some((c[0].to_string(), 100.0 * d / p))
                    })
                    .filter(|(m, _)| {
                        m.as_str() >= &from[..from.len().min(7)]
                            && m.as_str() <= &to[..to.len().min(7)]
                    })
                    .collect();
                ("Shiller-S&P".to_string(), 12, dp)
            }
            (None, Some(f)) => {
                let c = read_closes(&f);
                let ratio = |i: usize| c[i].2 / c[i].1;
                let y = (252..c.len())
                    .map(|t| (c[t].0.clone(), 100.0 * (ratio(t) / ratio(t - 252) - 1.0)))
                    .filter(|(d, _)| d.as_str() >= from.as_str() && d.as_str() <= to.as_str())
                    .collect();
                (opt("-series").unwrap_or_else(|| "fund".to_string()), 252, y)
            }
            _ => usage("-dividend wants exactly one of -shiller FILE and -closes FILE"),
        };
    if dated.len() < 12 {
        usage("-dividend: the window holds under a year of observations");
    }
    let values: Vec<f64> = dated.iter().map(|x| x.1).collect();
    let years: Vec<&str> = dated.iter().map(|(d, _)| &d[..4]).collect();
    // monthly: the mean; daily: the mean of each calendar year's mean, the dates kept in place
    let read = |v: &[f64]| -> [f64; 1] {
        if per_year == 12 {
            return [v.iter().sum::<f64>() / v.len() as f64];
        }
        let (mut means, mut s, mut n) = (Vec::new(), 0.0, 0usize);
        for (k, x) in v.iter().enumerate() {
            s += x;
            n += 1;
            if k + 1 == v.len() || years[k + 1] != years[k] {
                means.push(s / n as f64);
                (s, n) = (0.0, 0);
            }
        }
        [means.iter().sum::<f64>() / means.len() as f64]
    };
    let window = format!("{}..{}", dated[0].0, dated[dated.len() - 1].0);
    let names = [ms::DIVIDEND_ROW];
    if let Some(y) = block_years() {
        print_band_header(args);
        let (resamples, seed, joint) = resampling_of(args);
        let idx: Vec<f64> = (0..values.len()).map(|i| i as f64).collect();
        let reads = ms::series_resamples(&idx, per_year, y, resamples, seed, |x| {
            read(&x.iter().map(|&i| values[i as usize]).collect::<Vec<f64>>())
        });
        print_resampled_rows(
            &set,
            &series,
            &window,
            values.len(),
            &names,
            &read(&values),
            &reads,
            joint,
        );
        return true;
    }
    if args.iter().any(|a| a == "-header") {
        println!("set	row	series	window	n	record");
    }
    println!(
        "{set}	{}	{series}	{window}	{}	{:.6}",
        names[0],
        values.len(),
        read(&values)[0]
    );
    true
}

/// Days from 1970-01-01 back to an ISO date (Hinnant's `civil_from_days`), `civil_days`' inverse.
fn civil_date(z: i64) -> String {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}")
}

/// An ISO date from French's `YYYYMMDD`.
fn iso_of8(d8: &str) -> String {
    format!("{}-{}-{}", &d8[0..4], &d8[4..6], &d8[6..8])
}

/// One source `-resampled` copies, read once: its rows by date in the file's order.
enum CopySource {
    /// French's daily factors, the lag-free text's lines; a dated line's cells after the date
    /// are the session's
    French { lines: Vec<String> },
    /// a dated CSV of levels (`date,close,adj_close[...]`, `date,adj_close,dlog_adj_close` or
    /// `date,open,high,low,close,adj_close,volume`): its header and each session's cells by name
    Levels {
        head: Vec<String>,
        rows: Vec<(String, Vec<Option<f64>>)>,
    },
    /// FRED's `observation_date,NAME`: each observation's date and its value as printed
    Fred {
        head: String,
        rows: Vec<(String, String)>,
    },
}

/// A level file's rows, lag-free where it is declared `-index`: the printed and adjusted closes
/// (or the adjusted alone) compounded again from the corrected returns.
fn read_level_source(file: &str) -> CopySource {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    let mut lines = text.lines();
    let head: Vec<String> = lines
        .next()
        .unwrap_or("")
        .split(',')
        .map(|h| h.trim().to_string())
        .collect();
    if head.first().map(String::as_str) != Some("date") {
        usage(&format!("-resampled: {file} is not a dated CSV"));
    }
    let mut rows: Vec<(String, Vec<Option<f64>>)> = lines
        .filter(|l| !l.trim().is_empty())
        .map(|l| {
            let f: Vec<&str> = l.split(',').map(str::trim).collect();
            (
                f[0].to_string(),
                (1..head.len())
                    .map(|k| f.get(k).and_then(|x| x.parse().ok()))
                    .collect(),
            )
        })
        .collect();
    let col = |name: &str| head.iter().position(|h| h == name).map(|k| k - 1);
    let first = rows.get(1).map(|(d, _)| d.clone());
    if col("open").is_none() && yahoo_is_index(file, first.as_deref()) {
        // the corrected levels, as the readers take them
        if closes_layout(file) {
            for (i, (_, c, a)) in closes_lag_free(file).into_iter().enumerate() {
                rows[i].1[0] = Some(c);
                rows[i].1[1] = Some(a);
            }
        } else if let Some(k) = col("adj_close") {
            let days = unlagged(read_yahoo(file));
            let x0 = rows[0].1[k].unwrap_or_else(|| usage(&format!("{file}: no anchor level")));
            let mut c = 0.0;
            for (i, (_, r)) in days.iter().enumerate() {
                c += r;
                rows[i + 1].1[k] = Some(x0 * ms::exp_det(c));
            }
        }
    }
    CopySource::Levels { head, rows }
}

/// `-resampled OUT`: THE RECORD'S BLOCK RESAMPLES AS FILES, for a ruler outside this tool (the rows
/// `-scales` cannot reach): `-n` copies (200) of every source, each in its own layout, at
/// `OUT/k/<the source's path>` (its file name where the path is absolute). The first `-equity`
/// source's sessions in -from..-to are the master calendar; each copy draws its blocks of `-block`
/// years of sessions on it (`NumPyRng(-seed)`, copy-major), and every source takes the same date
/// blocks: an `-equity` file's sessions re-dated and compounded again (French's factors and a
/// declared `-index` lag-free, the bar's open, high and low kept relative to its close, the volume
/// carried), a `-fred` file's observations moved as LEVELS (a persistent level keeps its
/// distribution), each to the block's session its date falls in, offset by its own days past it.
/// Rows outside the master's span are kept as recorded.
fn resampled_mode(args: &[String]) -> bool {
    let Some(i) = args.iter().position(|a| a == "-resampled") else {
        return false;
    };
    let out = args
        .get(i + 1)
        .unwrap_or_else(|| usage("-resampled wants an output directory"));
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    let years = block_years().unwrap_or_else(|| usage("-resampled wants -block 252 or 756"));
    let copies: usize = opt("-n").map_or(200, |v| num(&v, "-n wants a copy count"));
    let seed: u64 = opt("-seed").map_or(20_260_918, |v| num(&v, "-seed wants an integer"));
    let from = opt("-from").unwrap_or_default();
    let to = opt("-to").unwrap_or_else(|| "9999".to_string());
    let sources = read_copy_sources(args);
    let master: Vec<String> = match sources
        .iter()
        .find(|(_, s)| !matches!(s, CopySource::Fred { .. }))
    {
        Some((_, src)) => source_dates(src)
            .into_iter()
            .filter(|d| d.as_str() >= from.as_str() && d.as_str() <= to.as_str())
            .collect(),
        None => usage("-resampled wants an -equity source, whose sessions are the calendar"),
    };
    let n = master.len();
    let l = 252 * years;
    if n <= l {
        usage(&format!(
            "-resampled: the calendar holds {n} sessions, no more than a block"
        ));
    }
    let blocks = n.div_ceil(l);
    let bound = u32::try_from(n - l + 1).unwrap_or_else(|_| usage("-resampled: too long"));
    let mut rng = uni::NumPyRng::new(seed);
    for k in 0..copies {
        let starts: Vec<usize> = (0..blocks)
            .map(|_| rng.next_bounded_u32(bound) as usize)
            .collect();
        for (file, src) in &sources {
            let rel = if std::path::Path::new(file).is_absolute() {
                std::path::Path::new(file)
                    .file_name()
                    .map_or_else(|| file.clone(), |x| x.to_string_lossy().to_string())
            } else {
                file.trim_start_matches("./").to_string()
            };
            let path = std::path::Path::new(out).join(k.to_string()).join(rel);
            if let Some(dir) = path.parent() {
                std::fs::create_dir_all(dir)
                    .unwrap_or_else(|e| usage(&format!("cannot create {}: {e}", dir.display())));
            }
            let text = copy_text(src, &master, &starts, l);
            std::fs::write(&path, text)
                .unwrap_or_else(|e| usage(&format!("cannot write {}: {e}", path.display())));
        }
    }
    eprintln!(
        "{copies} copies of {} sources in {out}: {n} sessions {}..{}, blocks of {l}, seed {seed}",
        sources.len(),
        master[0],
        master[n - 1]
    );
    true
}

/// `-resampled`'s sources in argument order: each `-equity FILE` (French's daily factors, read
/// lag-free unless `-recorded`, or a level file) and each `-fred FILE`.
fn read_copy_sources(args: &[String]) -> Vec<(String, CopySource)> {
    let mut sources: Vec<(String, CopySource)> = Vec::new();
    let mut it = args.iter();
    while let Some(a) = it.next() {
        let mut file = || {
            it.next()
                .cloned()
                .unwrap_or_else(|| usage(&format!("{a} wants a file")))
        };
        match a.as_str() {
            "-equity" => {
                let f = file();
                let src = if is_french_factors(&f) {
                    CopySource::French {
                        lines: if lag().recorded {
                            std::fs::read_to_string(&f)
                                .unwrap_or_else(|e| usage(&format!("{f}: {e}")))
                        } else {
                            unlagged_french(&f)
                        }
                        .lines()
                        .map(str::to_string)
                        .collect(),
                    }
                } else {
                    read_level_source(&f)
                };
                sources.push((f, src));
            }
            "-fred" => {
                let f = file();
                let text =
                    std::fs::read_to_string(&f).unwrap_or_else(|e| usage(&format!("{f}: {e}")));
                let mut lines = text.lines();
                let head = lines.next().unwrap_or("").to_string();
                let rows = lines
                    .filter(|l| !l.trim().is_empty())
                    .filter_map(|l| {
                        l.split_once(',')
                            .map(|(d, v)| (d.trim().to_string(), v.trim().to_string()))
                    })
                    .collect();
                sources.push((f, CopySource::Fred { head, rows }));
            }
            _ => {}
        }
    }
    sources
}

/// Whether a file is French's daily factors (a dated row of five cells).
fn is_french_factors(file: &str) -> bool {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    text.lines().any(|l| {
        let f: Vec<&str> = l.split(',').collect();
        f.len() == 5 && f[0].trim().len() == 8 && f[0].trim().bytes().all(|b| b.is_ascii_digit())
    })
}

/// A source's session dates (ISO) in order; FRED's are its observation dates.
fn source_dates(src: &CopySource) -> Vec<String> {
    match src {
        CopySource::French { lines } => lines
            .iter()
            .filter_map(|l| {
                let d = l.split(',').next()?.trim();
                (d.len() == 8 && d.bytes().all(|b| b.is_ascii_digit())).then(|| iso_of8(d))
            })
            .collect(),
        CopySource::Levels { rows, .. } => rows.iter().map(|(d, _)| d.clone()).collect(),
        CopySource::Fred { rows, .. } => rows.iter().map(|(d, _)| d.clone()).collect(),
    }
}

/// The master position each target position reads: block `t / l`'s start plus the offset in it.
fn source_position(starts: &[usize], l: usize, t: usize) -> usize {
    starts[t / l] + t % l
}

/// One copy of one source in its own layout (`resampled_mode`). A source is copied over its own
/// span inside the calendar's: a target session outside the dates the source holds there is not
/// written, nor one whose drawn session the source lacks.
fn copy_text(src: &CopySource, master: &[String], starts: &[usize], l: usize) -> String {
    match src {
        CopySource::French { lines } => copy_french(lines, master, starts, l),
        CopySource::Levels { head, rows } => copy_levels(head, rows, master, starts, l),
        CopySource::Fred { head, rows } => copy_fred(head, rows, master, starts, l),
    }
}

/// The first and last of `dates` inside the calendar's span, `None` where none falls in it.
fn span_in<'a>(
    dates: impl Iterator<Item = &'a str>,
    master: &[String],
) -> Option<(String, String)> {
    let (lo, hi) = (master[0].as_str(), master[master.len() - 1].as_str());
    dates
        .filter(|d| *d >= lo && *d <= hi)
        .fold(None, |acc: Option<(&str, &str)>, d| match acc {
            None => Some((d, d)),
            Some((a, b)) => Some((a.min(d), b.max(d))),
        })
        .map(|(a, b)| (a.to_string(), b.to_string()))
}

/// French's factors: the dated rows in the calendar's span re-dated, each target session the
/// cells of the session it draws.
fn copy_french(lines: &[String], master: &[String], starts: &[usize], l: usize) -> String {
    let dated = |ln: &str| -> Option<String> {
        let d = ln.split(',').next()?.trim();
        (d.len() == 8 && d.bytes().all(|b| b.is_ascii_digit())).then(|| iso_of8(d))
    };
    let by_date: std::collections::HashMap<String, &str> = lines
        .iter()
        .filter_map(|ln| Some((dated(ln)?, ln.split_once(',')?.1)))
        .collect();
    let Some((lo, hi)) = span_in(by_date.keys().map(String::as_str), master) else {
        return lines.join("\n") + "\n";
    };
    let mut out: Vec<String> = Vec::with_capacity(lines.len());
    let mut placed = false;
    for ln in lines {
        if !dated(ln).is_some_and(|d| d >= lo && d <= hi) {
            out.push(ln.clone());
            continue;
        }
        if placed {
            continue;
        }
        placed = true;
        for (t, target) in master.iter().enumerate() {
            if *target < lo || *target > hi {
                continue;
            }
            if let Some(rest) = by_date.get(&master[source_position(starts, l, t)]) {
                out.push(format!("{},{rest}", target.replace('-', "")));
            }
        }
    }
    out.join("\n") + "\n"
}

/// FRED's observations in the calendar's span moved as levels: each to the target of the session
/// its date falls in, offset by its own days past it, inside the series' own span there.
fn copy_fred(
    head: &str,
    rows: &[(String, String)],
    master: &[String],
    starts: &[usize],
    l: usize,
) -> String {
    let Some((lo, hi)) = span_in(rows.iter().map(|(d, _)| d.as_str()), master) else {
        return std::iter::once(head.to_string())
            .chain(rows.iter().map(|(d, v)| format!("{d},{v}")))
            .collect::<Vec<String>>()
            .join("\n")
            + "\n";
    };
    let inside = |d: &str| d >= lo.as_str() && d <= hi.as_str();
    let days: Vec<i64> = master.iter().map(|d| civil_days(d)).collect();
    // each observation in the span, by the master session it falls in
    let at: Vec<(usize, i64, &str)> = rows
        .iter()
        .filter(|(d, _)| inside(d))
        .map(|(d, v)| {
            let k = master.partition_point(|m| m.as_str() <= d.as_str()) - 1;
            (k, civil_days(d) - days[k], v.as_str())
        })
        .collect();
    let mut all: std::collections::BTreeMap<String, &str> = rows
        .iter()
        .filter(|(d, _)| !inside(d))
        .map(|(d, v)| (d.clone(), v.as_str()))
        .collect();
    for (b, &s) in starts.iter().enumerate() {
        let a = at.partition_point(|x| x.0 < s);
        for &(k, off, v) in at[a..].iter().take_while(|x| x.0 < s + l) {
            let target = b * l + (k - s);
            if target < master.len() {
                let d = civil_date(days[target] + off);
                if inside(&d) {
                    all.insert(d, v);
                }
            }
        }
    }
    let mut s = head.to_string() + "\n";
    for (d, v) in all {
        s.push_str(&format!("{d},{v}\n"));
    }
    s
}

/// Each session's trailing median of the log volume over the last 252 sessions with volume (as
/// many as there are, early on): the trend a bar ruler detrends by, `None` without volume.
fn volume_trend(rows: &[(String, Vec<Option<f64>>)], kv: usize) -> Vec<Option<f64>> {
    let lv: Vec<Option<f64>> = rows
        .iter()
        .map(|(_, c)| c[kv].filter(|v| *v > 0.0).map(ms::ln_det))
        .collect();
    (0..rows.len())
        .map(|i| {
            let mut w: Vec<f64> = lv[i.saturating_sub(251)..=i]
                .iter()
                .flatten()
                .copied()
                .collect();
            if w.is_empty() {
                return None;
            }
            w.sort_by(f64::total_cmp);
            let m = w.len();
            Some(if m % 2 == 1 {
                w[m / 2]
            } else {
                0.5 * (w[m / 2 - 1] + w[m / 2])
            })
        })
        .collect()
}

/// One session's cells at the chain's levels: the close columns at `level`, open, high and low
/// at their source session's ratios to its close, the rest (the volume) carried.
fn level_cells(
    head: &[String],
    src: &[Option<f64>],
    level: &[f64],
    level_cols: &[usize],
) -> Vec<Option<f64>> {
    let col = |name: &str| head.iter().position(|h| h == name).map(|k| k - 1);
    let mut cells = src.to_vec();
    for &k in level_cols {
        cells[k] = Some(level[k]);
    }
    if let Some(c) = col("close") {
        if let Some(src_c) = src[c] {
            for name in ["open", "high", "low"] {
                if let Some(k) = col(name) {
                    cells[k] = src[k].map(|x| level[c] * x / src_c);
                }
            }
        }
    }
    cells
}

/// A level file's copy: the sessions in its span re-dated, the closes compounded again from the
/// returns the target sessions draw (from the level before the span), the sessions after it
/// continuing the chain on their own returns, the bar kept relative to its close, the volume
/// its session's ratio to its trailing median (`volume_trend`) at the target date's median, so
/// the copy's volume keeps the record's secular level, each log column read back off the new
/// levels.
fn copy_levels(
    head: &[String],
    rows: &[(String, Vec<Option<f64>>)],
    master: &[String],
    starts: &[usize],
    l: usize,
) -> String {
    let col = |name: &str| head.iter().position(|h| h == name).map(|k| k - 1);
    let index: std::collections::HashMap<&str, usize> = rows
        .iter()
        .enumerate()
        .map(|(i, (d, _))| (d.as_str(), i))
        .collect();
    let level_cols: Vec<usize> = ["close", "adj_close"]
        .iter()
        .filter_map(|c| col(c))
        .collect();
    let ret = |i: usize, k: usize| -> f64 {
        match (i, rows[i].1[k], i.checked_sub(1).and_then(|j| rows[j].1[k])) {
            (0, _, _) | (_, None, _) | (_, _, None) => 0.0,
            (_, Some(a), Some(b)) => ms::ln_det(a / b),
        }
    };
    let span = span_in(rows.iter().map(|(d, _)| d.as_str()), master);
    let lo = span.as_ref().map_or("9999", |s| s.0.as_str());
    let first_in = rows.iter().position(|(d, _)| d.as_str() >= lo);
    let mut out: Vec<(String, Vec<Option<f64>>)> = Vec::with_capacity(rows.len());
    out.extend(rows.iter().take(first_in.unwrap_or(rows.len())).cloned());
    if let (Some(f), Some((lo, hi))) = (first_in, &span) {
        // the levels the span starts from: the session before it, or its own first
        let base = if f > 0 { &rows[f - 1].1 } else { &rows[f].1 };
        let mut level: Vec<f64> = base.iter().map(|x| x.unwrap_or(f64::NAN)).collect();
        // the volume about its trailing median, re-applied at the target date's own median
        let trend = col("volume").map(|kv| volume_trend(rows, kv));
        let step = |j: usize, date: &str, level: &mut Vec<f64>| {
            for &k in &level_cols {
                level[k] *= ms::exp_det(ret(j, k));
            }
            let mut cells = level_cells(head, &rows[j].1, level, &level_cols);
            if let (Some(kv), Some(tr)) = (col("volume"), &trend) {
                let at = rows.partition_point(|(d, _)| d.as_str() <= date).max(1) - 1;
                cells[kv] = match (rows[j].1[kv], tr[j], tr[at]) {
                    (Some(v), Some(own), Some(there)) if v > 0.0 => {
                        Some(ms::exp_det(there + ms::ln_det(v) - own).round())
                    }
                    (v, ..) => v,
                };
            }
            cells
        };
        for (t, target) in master.iter().enumerate() {
            if target < lo || target > hi {
                continue;
            }
            if let Some(&j) = index.get(master[source_position(starts, l, t)].as_str()) {
                out.push((target.clone(), step(j, target, &mut level)));
            }
        }
        for (j, (d, _)) in rows.iter().enumerate().filter(|(_, (d, _))| d > hi) {
            out.push((d.clone(), step(j, d, &mut level)));
        }
    }
    // the log columns read back off the levels, the first row's left empty
    for (k, level) in [("dlog_adj_close", "adj_close"), ("dlog_close", "close")] {
        if let (Some(k), Some(lk)) = (col(k), col(level)) {
            for i in 0..out.len() {
                out[i].1[k] = match (i, out[i].1[lk], i.checked_sub(1).and_then(|j| out[j].1[lk])) {
                    (0, _, _) | (_, None, _) | (_, _, None) => None,
                    (_, Some(a), Some(b)) => Some(ms::ln_det(a / b)),
                };
            }
        }
    }
    let mut s = head.join(",") + "\n";
    for (d, cells) in out {
        let c: Vec<String> = cells
            .iter()
            .map(|x| x.map_or_else(String::new, shown))
            .collect();
        s.push_str(&format!("{d},{}\n", c.join(",")));
    }
    s
}

/// The resampling a record-only mode reads under `-block`: `-resamples` (20000), `-seed`
/// (20260918) and `-joint` (0.05) from the arguments, as the band modes default them.
fn resampling_of(args: &[String]) -> (usize, u64, f64) {
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    (
        opt("-resamples").map_or(20_000, |v| num(&v, "-resamples wants an integer")),
        opt("-seed").map_or(20_260_918, |v| {
            num(&v, "-seed wants a non-negative integer")
        }),
        opt("-joint").map_or(0.05, |v| num(&v, "-joint wants a share in (0, 1)")),
    )
}

/// A record-only mode's rows under `-block`, in the band modes' columns: each row's record, its
/// resamples' quantiles and a joint band over the rows at `joint`.
#[expect(
    clippy::too_many_arguments,
    reason = "the rows' identity, the record and its resamples: one printed line a row"
)]
fn print_resampled_rows<const N: usize>(
    set: &str,
    series: &str,
    window: &str,
    n: usize,
    names: &[&str; N],
    record: &[f64; N],
    reads: &[[f64; N]],
    joint: f64,
) {
    let ks: Vec<usize> = (0..N).collect();
    let (c, edges) = ms::record_band_joint(reads, &ks, joint);
    for (k, (lo, hi)) in edges.iter().enumerate() {
        let col: Vec<f64> = reads.iter().map(|x| x[k]).collect();
        let qs = band_quantiles(record[k], &col);
        println!(
            "{set}\t{}\t{series}\t{window}\t{n}\t{}\t{:.6}\t{}\t{c:.6}\t{lo:.6}\t{hi:.6}",
            names[k],
            reads.len(),
            record[k],
            qs.join("\t")
        );
    }
}

/// `read` on block resamples of a positive level series (`per_year` observations a year): its
/// log changes resampled in blocks of `years` years, the levels compounded again from the first.
fn level_resamples<const N: usize, F: Fn(&[f64]) -> [f64; N] + Sync>(
    levels: &[f64],
    per_year: usize,
    years: usize,
    (resamples, seed): (usize, u64),
    read: F,
) -> Vec<[f64; N]> {
    if levels.iter().any(|v| *v <= 0.0) {
        usage("a level series resampled by its log changes must stay positive");
    }
    let d: Vec<f64> = levels.windows(2).map(|w| ms::ln_det(w[1] / w[0])).collect();
    ms::series_resamples(&d, per_year, years, resamples, seed, |x| {
        let mut lv = Vec::with_capacity(x.len() + 1);
        lv.push(levels[0]);
        let mut c = 0.0;
        for v in x {
            c += v;
            lv.push(levels[0] * ms::exp_det(c));
        }
        read(&lv)
    })
}

/// The record-band column header, when the arguments ask for it (`-header`).
/// A band row's resample quantiles as the band line prints them (`{:.6}`), and under `-raw` the
/// resamples themselves on the line before it -- `raw`, the record, each resample at full
/// precision -- for `-scales` to read the record's own vote on its sign and its knee
/// (`read_scale_lines`). Without `-raw` the output is as it was.
fn band_quantiles(record: f64, col: &[f64]) -> Vec<String> {
    if std::env::args().any(|a| a == "-raw") {
        let vals: Vec<String> = col.iter().map(|v| format!("{v:?}")).collect();
        println!("raw\t{record:?}\t{}", vals.join("\t"));
    }
    ms::record_band_quantiles(col)
        .iter()
        .map(|v| format!("{v:.6}"))
        .collect()
}

fn print_band_header(args: &[String]) {
    if args.iter().any(|a| a == "-header") {
        let pcts: Vec<String> = ms::RECORD_BAND_PCTS
            .iter()
            .map(|p| format!("p{p}"))
            .collect();
        println!(
            "set\trow\tseries\twindow\tn\tresamples\trecord\t{}\tjointC\tjointLo\tjointHi",
            pcts.join("\t")
        );
    }
}

/// THE VOLATILITY EXIT's rows (`-volexit`): the record's sessions over `-from`..`-to`, each the
/// printed close's and the total return's simple returns, the `-fred` rate at the previous
/// session's date (the last one known on or before it), and the calendar days since that session;
/// the record and its joint band over the three rows at `-joint` x 3 / `-of`.
fn volexit_mode(args: &[String]) -> bool {
    if !args.iter().any(|a| a == "-volexit") {
        return false;
    }
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    let req = |flag: &str| opt(flag).unwrap_or_else(|| usage(&format!("-volexit wants {flag}")));
    let (from, to, set, series) = (req("-from"), req("-to"), req("-set"), req("-series"));
    let resamples: usize =
        opt("-resamples").map_or(20_000, |v| num(&v, "-resamples wants an integer"));
    let seed: u64 = opt("-seed").map_or(20_260_918, |v| num(&v, "-seed wants an integer"));
    let joint: f64 = opt("-joint").map_or(0.10, |v| num(&v, "-joint wants a share"));
    let of: usize = opt("-of").map_or(3, |v| num(&v, "-of wants a row count"));
    // (date, printed close level, total-return level)
    let levels: Vec<(String, f64, f64)> = match (opt("-closes"), opt("-french")) {
        (Some(f), None) => closes_lag_free(&f),
        (None, Some(f)) => {
            let mut level = 1.0;
            read_french(&f)
                .into_iter()
                .map(|(d, x)| {
                    level *= 1.0 + x / 100.0;
                    (d, level, level)
                })
                .collect()
        }
        _ => usage("-volexit wants exactly one of -closes FILE and -french FILE"),
    };
    let levels: Vec<(String, f64, f64)> = levels
        .into_iter()
        .filter(|(d, _, _)| d.as_str() >= from.as_str() && d.as_str() <= to.as_str())
        .collect();
    let rates = read_fred(&req("-fred"));
    let rate_on = |d: &str| -> f64 {
        let k = rates.partition_point(|(rd, _)| rd.as_str() <= d);
        if k == 0 {
            usage(&format!("no -fred rate on or before {d}"))
        }
        rates[k - 1].1
    };
    let days: Vec<ms::VolExitDay> = (1..levels.len())
        .map(|i| {
            let (d0, c0, t0) = &levels[i - 1];
            let (d1, c1, t1) = &levels[i];
            ms::VolExitDay {
                close_ret: c1 / c0 - 1.0,
                total_ret: t1 / t0 - 1.0,
                rate: rate_on(d0),
                days: (civil_days(d1) - civil_days(d0)) as f64,
            }
        })
        .collect();
    let window = format!("{}..{}", levels[1].0, levels[levels.len() - 1].0);
    let record = ms::vol_exit_of(&days);
    let reads = ms::vol_exit_resamples(&days, resamples, seed, block_years());
    eprintln!(
        "{series}: {} sessions {window}; timing {:.4}, interaction {:.4}, high-vol fifth {:.4}; {resamples} resamples, seed {seed}",
        days.len(),
        record[0],
        record[1],
        record[2]
    );
    print_band_header(args);
    let ks = [0usize, 1, 2];
    let (c, edges) = ms::record_band_joint(&reads, &ks, joint * 3.0 / of as f64);
    for (&k, (lo, hi)) in ks.iter().zip(&edges) {
        let col: Vec<f64> = reads.iter().map(|x| x[k]).collect();
        let qs = band_quantiles(record[k], &col);
        println!(
            "{set}\t{}\t{series}\t{window}\t{}\t{resamples}\t{:.6}\t{}\t{c:.6}\t{lo:.6}\t{hi:.6}",
            ms::VOL_EXIT_ROWS[k],
            days.len(),
            record[k],
            qs.join("\t")
        );
    }
    true
}

/// One session of a `-volstate` chain: the date of the session before it, its own, the printed
/// close's and the total return's simple returns, and the total return's log return.
struct StateSession {
    prev: String,
    date: String,
    close_ret: f64,
    total_ret: f64,
    log_ret: f64,
}

/// The sessions of one file of a `-volstate` chain, after its first, as recorded, in the layout the
/// file itself tells, and whether it is a French industry file: a French daily industry file (the
/// `-column` named; its total return is every one of the three returns) or a
/// `date,close,adj_close` CSV (the printed close's return the signal, the adjusted close's the
/// earnings and, as `ln_det`, the log return).
fn read_state_sessions(file: &str, column: Option<&str>) -> (bool, Vec<StateSession>) {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    if text[..text.len().min(400)]
        .to_ascii_lowercase()
        .contains("industry portfolios")
    {
        let col = column
            .unwrap_or_else(|| usage(&format!("{file}: an industry file needs -column NAME")));
        let rows = read_industry_simple(file, &text, col);
        return (
            true,
            (1..rows.len())
                .map(|i| StateSession {
                    prev: rows[i - 1].0.clone(),
                    date: rows[i].0.clone(),
                    close_ret: rows[i].1,
                    total_ret: rows[i].1,
                    log_ret: ms::ln_det(1.0 + rows[i].1),
                })
                .collect(),
        );
    }
    if text.lines().next().unwrap_or("").trim() == "date,close,adj_close" {
        let levels = read_closes(file);
        return (
            false,
            (1..levels.len())
                .map(|i| {
                    let (d0, c0, t0) = &levels[i - 1];
                    let (d1, c1, t1) = &levels[i];
                    StateSession {
                        prev: d0.clone(),
                        date: d1.clone(),
                        close_ret: c1 / c0 - 1.0,
                        total_ret: t1 / t0 - 1.0,
                        log_ret: ms::ln_det(t1 / t0),
                    }
                })
                .collect(),
        );
    }
    usage(&format!(
        "{file}: not a French industry file or a date,close,adj_close CSV"
    ))
}

/// A `-volstate` chain: the first file's sessions, continued by each `-splice FILE -at DATE` in
/// turn, as `read_daily_chain` continues a daily series, each file's taken sessions read lag-free
/// where it is a computed index (`state_lag_free`).
fn read_state_chain(
    file: &str,
    splices: &[(String, String)],
    column: Option<&str>,
) -> Vec<StateSession> {
    taken_ranges(file, splices)
        .into_iter()
        .flat_map(|(f, lo, hi)| {
            let (industry, all) = read_state_sessions(f, column);
            let days: Vec<StateSession> = all
                .into_iter()
                .filter(|s| in_taken(&s.date, lo, hi))
                .collect();
            let index = industry || yahoo_is_index(f, days.first().map(|s| s.date.as_str()));
            if index { state_lag_free(days) } else { days }
        })
        .collect()
}

/// A computed index's taken state sessions read lag-free (`unlagged`): the adjusted close's log
/// return, and the printed close's in log, each corrected, the simple returns read back off them;
/// a session after the cut as recorded.
fn state_lag_free(days: Vec<StateSession>) -> Vec<StateSession> {
    let dated = |x: &dyn Fn(&StateSession) -> f64| -> Vec<(String, f64)> {
        unlagged(days.iter().map(|s| (s.date.clone(), x(s))).collect())
    };
    let total = dated(&|s| s.log_ret);
    let close = dated(&|s| ms::ln_det(1.0 + s.close_ret));
    days.into_iter()
        .zip(total.into_iter().zip(close))
        .map(|(s, ((_, lt), (_, lc)))| {
            if lag().recorded || s.date.as_str() > ms::UNLAG_CUT {
                s
            } else {
                StateSession {
                    close_ret: ms::exp_det(lc) - 1.0,
                    total_ret: ms::exp_det(lt) - 1.0,
                    log_ret: lt,
                    ..s
                }
            }
        })
        .collect()
}

/// THE VOLATILITY STATE's rows (`-volstate`): the chain's sessions dated after `-from` through
/// `-to`, each its log return and the exit's inputs (the `-fred` rate at the previous session's
/// date, the calendar days since it), read by `vol_state_of`; the record, its resamples and their
/// joint band over the five rows at `-joint` x 5 / `-of`; then the last spliced file's sessions
/// alone, read and banded the same way, as the reference rows.
fn vol_state_mode(args: &[String]) -> bool {
    if !args.iter().any(|a| a == "-volstate") {
        return false;
    }
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    let req = |flag: &str| opt(flag).unwrap_or_else(|| usage(&format!("-volstate wants {flag}")));
    let (from, to, set, series) = (req("-from"), req("-to"), req("-set"), req("-series"));
    let refseries = opt("-refseries").unwrap_or_else(|| series.clone());
    let resamples: usize =
        opt("-resamples").map_or(20_000, |v| num(&v, "-resamples wants an integer"));
    let seed: u64 = opt("-seed").map_or(20_260_918, |v| num(&v, "-seed wants an integer"));
    let joint: f64 = opt("-joint").map_or(0.05, |v| num(&v, "-joint wants a share"));
    let of: usize = opt("-of").map_or(5, |v| num(&v, "-of wants a row count"));
    let splices = splices_of(args);
    let chain = read_state_chain(&req("-yahoo"), &splices, opt("-column").as_deref());
    let rates = read_fred(&req("-fred"));
    let rate_on = |d: &str| -> f64 {
        let k = rates.partition_point(|(rd, _)| rd.as_str() <= d);
        if k == 0 {
            usage(&format!("no -fred rate on or before {d}"))
        }
        rates[k - 1].1
    };
    let in_window: Vec<&StateSession> = chain
        .iter()
        .filter(|s| s.date.as_str() > from.as_str() && s.date.as_str() <= to.as_str())
        .collect();
    let day_of = |s: &StateSession| ms::VolStateDay {
        log_ret: s.log_ret,
        exit: ms::VolExitDay {
            close_ret: s.close_ret,
            total_ret: s.total_ret,
            rate: rate_on(&s.prev),
            days: (civil_days(&s.date) - civil_days(&s.prev)) as f64,
        },
    };
    if in_window.len() <= 252 {
        usage(&format!(
            "the window holds {} sessions; a block bootstrap needs more than a year",
            in_window.len()
        ));
    }
    let window_of = |s: &[&StateSession]| format!("{}..{}", s[0].date, s[s.len() - 1].date);
    let alpha = joint * 5.0 / of as f64;
    print_band_header(args);
    // the family on one span: the record, its resamples, their joint band, printed under `names`
    let print_family = |names: &[&str; 5], label: &str, sessions: &[&StateSession]| {
        let days: Vec<ms::VolStateDay> = sessions.iter().map(|s| day_of(s)).collect();
        let window = window_of(sessions);
        let record = ms::vol_state_of(&days);
        let reads = ms::vol_state_resamples(&days, resamples, seed, block_years());
        eprintln!(
            "{label}: {} sessions {window}; downside vol excess {:.6}, vol-timing edge {:.6}, exit timing {:.6}, exit 3x interaction {:.6}, high-vol fifth {:.6}; {resamples} resamples, seed {seed}",
            days.len(),
            record[0],
            record[1],
            record[2],
            record[3],
            record[4]
        );
        let ks = [0usize, 1, 2, 3, 4];
        let (c, edges) = ms::record_band_joint(&reads, &ks, alpha);
        for (&k, (lo, hi)) in ks.iter().zip(&edges) {
            let col: Vec<f64> = reads.iter().map(|x| x[k]).collect();
            let qs = band_quantiles(record[k], &col);
            println!(
                "{set}\t{}\t{label}\t{window}\t{}\t{resamples}\t{:.6}\t{}\t{c:.6}\t{lo:.6}\t{hi:.6}",
                names[k],
                days.len(),
                record[k],
                qs.join("\t")
            );
        }
    };
    print_family(&ms::VOL_STATE_ROWS, &series, &in_window);
    if let Some((_, at)) = splices.last() {
        let tail: Vec<&StateSession> = in_window
            .iter()
            .copied()
            .filter(|s| s.date.as_str() > at.as_str())
            .collect();
        if tail.len() > 252 {
            print_family(&ms::VOL_STATE_REFERENCE_ROWS, &refseries, &tail);
        }
    }
    true
}

/// THE LEDGER ROWS (`-ledgerrows`): the chain's sessions dated after `-from` through `-to`, each
/// its log return and the exit's inputs, as `-volstate` reads them, or `-french`'s market total
/// return as every one of the three; read by `ledger_readings_of`; the record, its resamples and a
/// joint band at `-joint` over each of `LEDGER_FAMILIES`.
fn ledger_rows_mode(args: &[String]) -> bool {
    if !args.iter().any(|a| a == "-ledgerrows") {
        return false;
    }
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    let req = |flag: &str| opt(flag).unwrap_or_else(|| usage(&format!("-ledgerrows wants {flag}")));
    let (from, to, set, series) = (req("-from"), req("-to"), req("-set"), req("-series"));
    let resamples: usize =
        opt("-resamples").map_or(20_000, |v| num(&v, "-resamples wants an integer"));
    let seed: u64 = opt("-seed").map_or(20_260_918, |v| num(&v, "-seed wants an integer"));
    let joint: f64 = opt("-joint").map_or(0.05, |v| num(&v, "-joint wants a share"));
    let chain: Vec<StateSession> = match opt("-french") {
        Some(f) => {
            let rows = read_french(&f);
            (1..rows.len())
                .map(|i| StateSession {
                    prev: rows[i - 1].0.clone(),
                    date: rows[i].0.clone(),
                    close_ret: rows[i].1 / 100.0,
                    total_ret: rows[i].1 / 100.0,
                    log_ret: ms::ln_det(1.0 + rows[i].1 / 100.0),
                })
                .collect()
        }
        None => read_state_chain(&req("-yahoo"), &splices_of(args), opt("-column").as_deref()),
    };
    let rates = read_fred(&req("-fred"));
    let rate_on = |d: &str| -> f64 {
        let k = rates.partition_point(|(rd, _)| rd.as_str() <= d);
        if k == 0 {
            usage(&format!("no -fred rate on or before {d}"))
        }
        rates[k - 1].1
    };
    let in_window: Vec<&StateSession> = chain
        .iter()
        .filter(|s| s.date.as_str() > from.as_str() && s.date.as_str() <= to.as_str())
        .collect();
    if in_window.len() <= 252 {
        usage(&format!(
            "the window holds {} sessions; a block bootstrap needs more than a year",
            in_window.len()
        ));
    }
    let days: Vec<ms::VolStateDay> = in_window
        .iter()
        .map(|s| ms::VolStateDay {
            log_ret: s.log_ret,
            exit: ms::VolExitDay {
                close_ret: s.close_ret,
                total_ret: s.total_ret,
                rate: rate_on(&s.prev),
                days: (civil_days(&s.date) - civil_days(&s.prev)) as f64,
            },
        })
        .collect();
    let window = format!(
        "{}..{}",
        in_window[0].date,
        in_window[in_window.len() - 1].date
    );
    let record = ms::ledger_readings_of(&days);
    let reads = ms::ledger_resamples(&days, resamples, seed, block_years());
    eprintln!(
        "{series}: {} sessions {window}; {resamples} resamples, seed {seed}",
        days.len()
    );
    print_band_header(args);
    for fam in ms::LEDGER_FAMILIES
        .iter()
        .filter(|f| f.end <= ms::LEDGER_STATE_ROWS)
    {
        let ks: Vec<usize> = fam.clone().collect();
        let (c, edges) = ms::record_band_joint(&reads, &ks, joint);
        for (&k, (lo, hi)) in ks.iter().zip(&edges) {
            let col: Vec<f64> = reads.iter().map(|x| x[k]).collect();
            let qs = band_quantiles(record[k], &col);
            println!(
                "{set}\t{}\t{series}\t{window}\t{}\t{resamples}\t{:.6}\t{}\t{c:.6}\t{lo:.6}\t{hi:.6}",
                ms::LEDGER_ROWS[k],
                days.len(),
                record[k],
                qs.join("\t")
            );
        }
    }
    print_lag1_row(&set, &series, &in_window, resamples, seed, joint);
    true
}

/// The one-day reversion's row (`-ledgerrows`): the window's sessions after `UNLAG_CUT` alone,
/// where the record has no lag, their lag-1 (`ledger_lag1_of`), its resamples and band.
fn print_lag1_row(
    set: &str,
    series: &str,
    in_window: &[&StateSession],
    resamples: usize,
    seed: u64,
    joint: f64,
) {
    let after: Vec<&StateSession> = in_window
        .iter()
        .copied()
        .filter(|s| s.date.as_str() > ms::UNLAG_CUT)
        .collect();
    if after.len() <= 252 {
        usage(&format!(
            "the window holds {} sessions after {}; the one-day reversion needs more than a year",
            after.len(),
            ms::UNLAG_CUT
        ));
    }
    let r: Vec<f64> = after.iter().map(|s| s.log_ret).collect();
    let lag_window = format!("{}..{}", after[0].date, after[after.len() - 1].date);
    let reads = ms::ledger_lag1_resamples(&r, resamples, seed, block_years());
    let (c, edges) = ms::record_band_joint(&reads, &[0], joint);
    let col: Vec<f64> = reads.iter().map(|x| x[0]).collect();
    let qs = band_quantiles(ms::ledger_lag1_of(&r), &col);
    let (lo, hi) = edges[0];
    println!(
        "{set}\t{}\t{series}\t{lag_window}\t{}\t{resamples}\t{:.6}\t{}\t{c:.6}\t{lo:.6}\t{hi:.6}",
        ms::LEDGER_ROWS[ms::LEDGER_STATE_ROWS],
        r.len(),
        ms::ledger_lag1_of(&r),
        qs.join("\t")
    );
}

/// One band row as a `-scales` run prints it under `-raw`: its world and name, (p95 - p5) / 3.29,
/// the record and the resamples the quantiles were read from.
struct ScaleRead {
    world: String,
    row: String,
    scale: f64,
    record: f64,
    resamples: Vec<f64>,
}
/// One `-scales` run: the rows it supplies (empty, every row) and its arguments.
type ScaleRun = (Vec<String>, Vec<String>);
/// A world and row's reading at one block: its scale, the record's vote on its sign and its knee
/// (`vote_and_knee`), and the run it came from.
#[derive(Clone, Copy)]
struct ScaleSlot {
    scale: f64,
    resolution: Option<f64>,
    knee: Option<f64>,
    run: usize,
}
/// A world and row's readings at its own blocks, at 252 and at 756.
type ScaleSlots = [Option<ScaleSlot>; 3];

/// The `-scales` run list: a line a run, its rows (`*`, or names joined by `;`), then its
/// arguments, tab-separated, `#` a comment; each run's own `-header`, `-resamples` and `-block`
/// dropped, since `-scales` sets them.
fn read_scale_runs(text: &str) -> Vec<ScaleRun> {
    text.lines()
        .filter(|l| !(l.trim().is_empty() || l.starts_with('#')))
        .map(|l| {
            let f: Vec<String> = l.split('\t').map(|x| x.trim().to_string()).collect();
            let pick: Vec<String> = if f[0] == "*" {
                Vec::new()
            } else {
                f[0].split(';').map(|x| x.trim().to_string()).collect()
            };
            let mut a: Vec<String> = Vec::new();
            let mut it = f[1..].iter();
            while let Some(x) = it.next() {
                match x.as_str() {
                    "-header" => {}
                    "-resamples" | "-block" => {
                        it.next();
                    }
                    _ => a.push(x.clone()),
                }
            }
            (pick, a)
        })
        .collect()
}

/// The ledger table's rows and nulls, by its `row` and `null` columns: a null `none`, or absent,
/// is no null.
fn read_scale_rows(ledger: &str, text: &str) -> Vec<(String, Option<f64>)> {
    let mut lines = text.lines();
    let head: Vec<&str> = lines
        .next()
        .unwrap_or("")
        .split('\t')
        .map(str::trim)
        .collect();
    let col = |name: &str| {
        head.iter()
            .position(|h| *h == name)
            .unwrap_or_else(|| usage(&format!("-scales: {ledger} has no [{name}] column")))
    };
    let (row_at, null_at) = (col("row"), col("null"));
    lines
        .filter(|l| !l.trim().is_empty())
        .map(|l| {
            let f: Vec<&str> = l.split('\t').collect();
            (
                f.get(row_at)
                    .map_or(String::new(), |r| r.trim().to_string()),
                f.get(null_at).and_then(|v| v.trim().parse::<f64>().ok()),
            )
        })
        .filter(|(r, _)| !r.is_empty())
        .collect()
}

/// The band rows one run of this tool prints, run under `-raw` so each carries its resamples
/// (`read_scale_lines`).
fn scales_of_run(args: &[String]) -> Vec<ScaleRead> {
    let exe = std::env::current_exe().unwrap_or_else(|e| usage(&format!("-scales: {e}")));
    let mut with_raw = args.to_vec();
    with_raw.push("-raw".to_string());
    let out = std::process::Command::new(exe)
        .args(&with_raw)
        .output()
        .unwrap_or_else(|e| usage(&format!("-scales: cannot run [{}]: {e}", args.join(" "))));
    if !out.status.success() {
        usage(&format!(
            "-scales: the run [{}] failed:\n{}",
            args.join(" "),
            String::from_utf8_lossy(&out.stderr)
        ));
    }
    read_scale_lines(&String::from_utf8_lossy(&out.stdout))
}

/// A run's band rows from its output, each with its spread in normal sd: the band columns
/// (`print_band_header`), p5 and p95 at `RECORD_BAND_PCTS`' third and twenty-first places; a
/// record-only line is not a band row. A `raw` line (`band_quantiles`) carries the record and the
/// resamples of the band line after it; a band row without one has neither.
fn read_scale_lines(text: &str) -> Vec<ScaleRead> {
    let cols = 7 + ms::RECORD_BAND_PCTS.len() + 3;
    let num = |v: &str| v.parse::<f64>().unwrap_or(f64::NAN);
    let mut raw: Option<(f64, Vec<f64>)> = None;
    let mut out = Vec::new();
    for l in text.lines() {
        let f: Vec<&str> = l.split('\t').collect();
        if f.first() == Some(&"raw") {
            raw = Some((
                f.get(1).map_or(f64::NAN, |v| num(v)),
                f.iter().skip(2).map(|v| num(v)).collect(),
            ));
        } else if f.len() == cols && f[0] != "set" {
            let q = |i: usize| num(f[7 + i]);
            let (record, resamples) = raw.take().unwrap_or((f64::NAN, Vec::new()));
            out.push(ScaleRead {
                world: f[0].to_string(),
                row: f[1].to_string(),
                scale: (q(20) - q(2)) / 3.29,
                record,
                resamples,
            });
        }
    }
    out
}

/// THE RECORD'S OWN VOTE ON ITS SIGN, AND ITS KNEE, from one band row's finite resamples at the
/// row's scale: the resolution is the share of resamples strictly on the record's side of the
/// row's null less the share strictly on the other side, floored at 0 (a resample at the null
/// votes for neither); the knee is the CRPS an indistinguishable world earns, the mean over the
/// resamples of each one's CRPS under the others (`leave_one_out_crps`), in the row's scale.
/// The resolution is `None` without a null, with the record at the null, or without a scale;
/// the knee `None` without a scale or under three resamples.
fn vote_and_knee(
    resamples: &[f64],
    null: Option<f64>,
    record: f64,
    scale: f64,
) -> (Option<f64>, Option<f64>) {
    let mut xs: Vec<f64> = resamples
        .iter()
        .copied()
        .filter(|v| v.is_finite())
        .collect();
    if !(scale.is_finite() && scale > 0.0) || xs.is_empty() {
        return (None, None);
    }
    let n = xs.len() as f64;
    let resolution = null
        .filter(|&nl| record.is_finite() && record != nl)
        .map(|nl| {
            let up = record > nl;
            let rec_side = |x: f64| if up { x > nl } else { x < nl };
            let other_side = |x: f64| if up { x < nl } else { x > nl };
            let p_rec = xs.iter().filter(|&&x| rec_side(x)).count() as f64 / n;
            let p_other = xs.iter().filter(|&&x| other_side(x)).count() as f64 / n;
            (p_rec - p_other).max(0.0)
        });
    (resolution, leave_one_out_crps(&mut xs).map(|c| c / scale))
}

/// The mean over a sample of each value's `ms::crps` under the others, the sample sorted in
/// place: from the sorted prefix sums, each value's summed distance to the others and the
/// others' pairwise sum (the whole sample's less those distances), in `ms::crps`'s form, so the
/// sum is the one the leave-one-out calls would make, in one pass. `None` under three values.
fn leave_one_out_crps(xs: &mut [f64]) -> Option<f64> {
    let n = xs.len();
    if n < 3 {
        return None;
    }
    xs.sort_by(f64::total_cmp);
    let total: f64 = xs.iter().sum();
    // the whole sample's pairwise sum, over j < k of x_k - x_j
    let pairs: f64 = xs
        .iter()
        .enumerate()
        .map(|(i, v)| ((2 * (i + 1)) as f64 - (n + 1) as f64) * v)
        .sum();
    let m = (n - 1) as f64;
    let mut before = 0.0f64;
    let mut acc = 0.0f64;
    for (p, &x) in xs.iter().enumerate() {
        let after = total - before - x;
        let dist = x * p as f64 - before + after - x * (n - 1 - p) as f64;
        acc += dist / m - (pairs - dist) / (m * m);
        before += x;
    }
    Some(acc / n as f64)
}

/// `-scales`' table: a line a world and row, its scales at 252, at 756 and at its run's own
/// blocks, the largest, and that reading's resolution and knee; `none` where no run reaches the
/// row, those listed on stderr by world.
fn print_scales(rows: &[String], got: &std::collections::BTreeMap<(String, String), ScaleSlots>) {
    let cell = |v: Option<f64>| v.map_or_else(|| "none".to_string(), |x| format!("{x:.6}"));
    println!("world\trow\tscale252\tscale756\tscaleNative\tscale\tresolution\tknee");
    for world in ["sp500", "nasdaq"] {
        let mut missing: Vec<&str> = Vec::new();
        for row in rows {
            let s = got
                .get(&(world.to_string(), row.clone()))
                .copied()
                .unwrap_or_default();
            let v = |j: usize| s[j].filter(|x| x.scale.is_finite() && x.scale > 0.0);
            let (native, b252, b756) = (v(0), v(1), v(2));
            // the largest scale, the first of equals
            let best = [native, b252, b756]
                .into_iter()
                .flatten()
                .fold(None, |m: Option<ScaleSlot>, x| {
                    Some(m.filter(|m| m.scale >= x.scale).unwrap_or(x))
                });
            if best.is_none() {
                missing.push(row);
            }
            println!(
                "{world}\t{row}\t{}\t{}\t{}\t{}\t{}\t{}",
                cell(b252.map(|x| x.scale)),
                cell(b756.map(|x| x.scale)),
                cell(native.map(|x| x.scale)),
                cell(best.map(|x| x.scale)),
                cell(best.and_then(|x| x.resolution)),
                cell(best.and_then(|x| x.knee))
            );
        }
        eprintln!("{world}: {} rows no run reaches", missing.len());
        for r in missing {
            eprintln!("  {r}");
        }
    }
}

/// `-scales LEDGER -runs RUNS`: the record scales of the ledger's rows, each with the record's
/// vote on its sign and its knee (the usage's `-scales`).
fn scales_mode(args: &[String]) -> bool {
    let Some(i) = args.iter().position(|a| a == "-scales") else {
        return false;
    };
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    let ledger = args
        .get(i + 1)
        .unwrap_or_else(|| usage("-scales wants the ledger's row table"));
    let runs_file = opt("-runs").unwrap_or_else(|| usage("-scales wants -runs RUNS.tsv"));
    let resamples = opt("-resamples");
    let read = |f: &str| std::fs::read_to_string(f).unwrap_or_else(|e| usage(&format!("{f}: {e}")));
    let rows = read_scale_rows(ledger, &read(ledger));
    let names: Vec<String> = rows.iter().map(|(r, _)| r.clone()).collect();
    let null_of = |row: &str| rows.iter().find(|(r, _)| r == row).and_then(|(_, n)| *n);
    // (world, row) -> the reading at each block
    let mut got: std::collections::BTreeMap<(String, String), ScaleSlots> =
        std::collections::BTreeMap::new();
    let runs = read_scale_runs(&read(&runs_file));
    for (k, (pick, a)) in runs.iter().enumerate() {
        for (j, block) in [None, Some("252"), Some("756")].iter().enumerate() {
            let mut a = a.clone();
            if let Some(n) = &resamples {
                a.extend(["-resamples".to_string(), n.clone()]);
            }
            if let Some(b) = block {
                a.extend(["-block".to_string(), (*b).to_string()]);
            }
            eprintln!(
                "run {} of {} at {}",
                k + 1,
                runs.len(),
                block.unwrap_or("its own blocks")
            );
            for rd in scales_of_run(&a) {
                if !(pick.is_empty() || pick.contains(&rd.row)) {
                    continue;
                }
                let slot = &mut got.entry((rd.world.clone(), rd.row.clone())).or_default()[j];
                if let Some(other) = slot
                    && other.run != k
                {
                    usage(&format!(
                        "-scales: [{}] in {} comes from runs {} and {}; name it in one run's rows",
                        rd.row,
                        rd.world,
                        other.run + 1,
                        k + 1
                    ));
                }
                let (resolution, knee) =
                    vote_and_knee(&rd.resamples, null_of(&rd.row), rd.record, rd.scale);
                *slot = Some(ScaleSlot {
                    scale: rd.scale,
                    resolution,
                    knee,
                    run: k,
                });
            }
        }
    }
    print_scales(&names, &got);
    true
}

/// THE LAG-FREE SOURCE (`-unlagged OUT`): one source written lag-free in its own layout, for a
/// ruler outside this tool, the whole file its taken range: `-french FILE` with each corrected
/// session's Mkt-RF the corrected total less RF; `-industry FILE -column NAME` with the column's
/// corrected sessions; `-index FILE`, a `date,adj_close,dlog_adj_close` CSV, its returns corrected
/// and its levels compounded again from the first row's. Every other line as it is.
fn unlagged_mode(args: &[String]) -> bool {
    let Some(i) = args.iter().position(|a| a == "-unlagged") else {
        return false;
    };
    let out = args
        .get(i + 1)
        .unwrap_or_else(|| usage("-unlagged wants an output file"));
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    if lag().recorded {
        usage("-unlagged writes the lag-free series; -recorded reads none");
    }
    let text = match (opt("-french"), opt("-industry"), opt("-index")) {
        (Some(f), None, None) => unlagged_french(&f),
        (None, Some(f), None) => unlagged_industry(
            &f,
            &opt("-column").unwrap_or_else(|| usage("-unlagged -industry wants -column")),
        ),
        (None, None, Some(f)) if closes_layout(&f) => unlagged_closes(&f),
        (None, None, Some(f)) => unlagged_dlog(&f),
        _ => usage("-unlagged wants exactly one of -french FILE, -industry FILE and -index FILE"),
    };
    std::fs::write(out, &text).unwrap_or_else(|e| usage(&format!("cannot write {out}: {e}")));
    eprintln!("wrote {out}");
    true
}

fn shown(v: f64) -> String {
    format!("{v}")
}

/// French's file with each corrected session's Mkt-RF the corrected total less RF.
fn unlagged_french(f: &str) -> String {
    let fixed: std::collections::HashMap<String, f64> = read_french(f)
        .into_iter()
        .filter(|(d, _)| d.as_str() <= ms::UNLAG_CUT)
        .collect();
    rewrite_dated_cells(f, |d8, cells| {
        let iso = format!("{}-{}-{}", &d8[0..4], &d8[4..6], &d8[6..8]);
        let total = fixed.get(&iso)?;
        let rf: f64 = cells.get(4)?.trim().parse().ok()?;
        (cells.len() >= 5).then(|| (1, shown(total - rf)))
    })
}

/// French's industry file with the column's corrected sessions in its daily value-weighted block.
fn unlagged_industry(f: &str, col: &str) -> String {
    let text = std::fs::read_to_string(f).unwrap_or_else(|e| usage(&format!("{f}: {e}")));
    let fixed: std::collections::HashMap<String, f64> = unlagged(
        read_industry(f, &text, col)
            .into_iter()
            .filter(|(d, _)| d.as_str() <= ms::UNLAG_CUT)
            .collect(),
    )
    .into_iter()
    .collect();
    let k = industry_column(f, &text, col);
    // the daily value-weighted block: its title, its header, then its dated rows
    let mut stage = 0;
    let mut lines: Vec<String> = Vec::new();
    for l in text.lines() {
        let mut cells: Vec<String> = l.split(',').map(str::to_string).collect();
        let d8 = cells[0].trim().to_string();
        let dated = d8.len() == 8 && d8.bytes().all(|b| b.is_ascii_digit());
        match stage {
            0 if l.contains("Value Weighted Returns -- Daily") => stage = 1,
            1 => stage = 2,
            2 if dated => {
                let iso = format!("{}-{}-{}", &d8[0..4], &d8[4..6], &d8[6..8]);
                if let Some(r) = fixed.get(&iso) {
                    cells[k] = shown(100.0 * (ms::exp_det(*r) - 1.0));
                }
            }
            2 => stage = 3,
            _ => {}
        }
        lines.push(cells.join(","));
    }
    lines.join("\n") + "\n"
}

/// `date,close,adj_close[,dlog_adj_close][,dlog_close]`: the levels corrected, each log column
/// read back off them, the first row's left empty.
fn unlagged_closes(f: &str) -> String {
    let text = std::fs::read_to_string(f).unwrap_or_else(|e| usage(&format!("{f}: {e}")));
    let head: Vec<String> = text
        .lines()
        .next()
        .unwrap_or("")
        .split(',')
        .map(|h| h.trim().to_string())
        .collect();
    let levels = closes_lag_free(f);
    let mut s = head.join(",") + "\n";
    for (i, (d, c, a)) in levels.iter().enumerate() {
        let cells: Vec<String> = head
            .iter()
            .skip(1)
            .map(|h| match (h.as_str(), i) {
                ("close", _) => shown(*c),
                ("adj_close", _) => shown(*a),
                (_, 0) => String::new(),
                ("dlog_adj_close", _) => shown(ms::ln_det(a / levels[i - 1].2)),
                ("dlog_close", _) => shown(ms::ln_det(c / levels[i - 1].1)),
                _ => usage(&format!("{f}: no rule for the column [{h}]")),
            })
            .collect();
        s.push_str(&format!("{d},{}\n", cells.join(",")));
    }
    s
}

/// A `date,adj_close,dlog_adj_close` CSV, its returns corrected and its levels compounded again
/// from the first row's.
fn unlagged_dlog(f: &str) -> String {
    let text = std::fs::read_to_string(f).unwrap_or_else(|e| usage(&format!("{f}: {e}")));
    let first = text
        .lines()
        .nth(1)
        .unwrap_or_else(|| usage(&format!("{f}: no anchor row")));
    let (d0, x0) = first
        .split_once(',')
        .unwrap_or_else(|| usage(&format!("{f}: unreadable anchor row [{first}]")));
    let level0: f64 = x0
        .split(',')
        .next()
        .and_then(|s| s.trim().parse().ok())
        .unwrap_or_else(|| usage(&format!("{f}: unreadable anchor row [{first}]")));
    let days = unlagged(read_yahoo(f));
    let mut s = format!(
        "date,adj_close,dlog_adj_close\n{},{},\n",
        d0.trim(),
        shown(level0)
    );
    let mut c = 0.0;
    for (d, r) in &days {
        c += r;
        s.push_str(&format!(
            "{d},{},{}\n",
            shown(level0 * ms::exp_det(c)),
            shown(*r)
        ));
    }
    s
}

/// Whether a Yahoo-format file carries its closes as levels: a header opening `date,close,adj_close`.
fn closes_layout(file: &str) -> bool {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    let head: Vec<&str> = text
        .lines()
        .next()
        .unwrap_or("")
        .split(',')
        .map(str::trim)
        .collect();
    head.len() >= 3 && head[..3] == ["date", "close", "adj_close"]
}

/// A French file's text with one cell of each dated row rewritten: `cell` takes the row's
/// YYYYMMDD and its cells and returns the cell's index and new text, or `None` to keep the row.
fn rewrite_dated_cells(
    file: &str,
    cell: impl Fn(&str, &[&str]) -> Option<(usize, String)>,
) -> String {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    let mut out: Vec<String> = Vec::new();
    for l in text.lines() {
        let cells: Vec<&str> = l.split(',').collect();
        let d8 = cells[0].trim();
        let fix = if d8.len() == 8 && d8.bytes().all(|b| b.is_ascii_digit()) {
            cell(d8, &cells)
        } else {
            None
        };
        match fix {
            Some((k, v)) => {
                let mut c: Vec<String> = cells.iter().map(|s| (*s).to_string()).collect();
                c[k] = v;
                out.push(c.join(","));
            }
            None => out.push(l.to_string()),
        }
    }
    out.join("\n") + "\n"
}

/// `-basket`: the ruler from a wide closes file, written to `-out` or printed.
fn basket_mode(args: &[String]) -> bool {
    if !args.iter().any(|a| a == "-basket") {
        return false;
    }
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    let closes = opt("-closes").unwrap_or_else(|| usage("-basket wants -closes WIDE.csv"));
    let closes_date =
        opt("-closesdate").unwrap_or_else(|| usage("-basket wants -closesdate YYYY-MM-DD"));
    let min_sessions: usize =
        opt("-minsessions").map_or(252, |v| num(&v, "-minsessions wants a session count"));
    let text = std::fs::read_to_string(&closes)
        .unwrap_or_else(|e| usage(&format!("cannot read {closes}: {e}")));
    let tsv = ms::parse_basket_closes(&text)
        .and_then(|c| {
            ms::basket_ruler_tsv(
                &c,
                &opt("-from").unwrap_or_default(),
                &opt("-to").unwrap_or_default(),
                &closes_date,
                min_sessions,
            )
        })
        .unwrap_or_else(|m| usage(&format!("-basket {closes}: {m}")));
    match opt("-out") {
        Some(out) => {
            std::fs::write(&out, &tsv)
                .unwrap_or_else(|e| usage(&format!("cannot write {out}: {e}")));
            eprintln!("wrote {out}");
        }
        None => print!("{tsv}"),
    }
    true
}

/// `(date, value)` of every row of a FRED `observation_date,SERIES` file whose value is a
/// number, as published: levels, or percent.
fn fred_series(file: &str) -> Vec<(String, f64)> {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    text.lines()
        .skip(1)
        .filter_map(|l| {
            let f: Vec<&str> = l.split(',').map(str::trim).collect();
            let v: f64 = f.get(1)?.parse().ok()?;
            Some((f[0].to_string(), v))
        })
        .collect()
}

/// The series name a FRED file's header carries.
fn fred_name(file: &str) -> String {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    let head: Vec<&str> = text
        .lines()
        .next()
        .unwrap_or("")
        .split(',')
        .map(str::trim)
        .collect();
    match head.as_slice() {
        ["observation_date", h] => (*h).to_string(),
        _ => usage(&format!("{file}: not a FRED observation_date,SERIES file")),
    }
}

/// Each month's last observation of a dated series, keyed by the month: `YYYY-MM -> (date, v)`.
fn month_ends_of(obs: &[(String, f64)]) -> Vec<(String, String, f64)> {
    let mut out: Vec<(String, String, f64)> = Vec::new();
    for (d, v) in obs {
        let m = d[..7].to_string();
        match out.last_mut() {
            Some(last) if last.0 == m => *last = (m, d.clone(), *v),
            _ => out.push((m, d.clone(), *v)),
        }
    }
    out
}

/// `YYYY-MM` plus `k` months.
fn month_add(key: &str, k: usize) -> String {
    let y: usize = key[..4].parse().unwrap_or(0);
    let m: usize = key[5..7].parse().unwrap_or(1);
    let t = y * 12 + (m - 1) + k;
    format!("{:04}-{:02}", t / 12, t % 12 + 1)
}

/// The returns of dated log-return series over the sessions ALL of them trade, each compounded
/// across the sessions the others skip: cumulative logs differenced at the common dates, from the
/// second common date on.
fn join_returns(series: &[&[(String, f64)]], from: &str, to: &str) -> (Vec<String>, Vec<Vec<f64>>) {
    use std::collections::BTreeMap;
    use std::collections::HashMap;
    let cums: Vec<HashMap<&str, f64>> = series
        .iter()
        .map(|s| {
            let mut acc = 0.0f64;
            s.iter()
                .map(|(d, r)| {
                    acc += r;
                    (d.as_str(), acc)
                })
                .collect()
        })
        .collect();
    let mut count: BTreeMap<&str, usize> = BTreeMap::new();
    for s in series {
        for (d, _) in *s {
            if d.as_str() >= from && d.as_str() <= to {
                *count.entry(d.as_str()).or_default() += 1;
            }
        }
    }
    let dates: Vec<&str> = count
        .iter()
        .filter(|(_, c)| **c == series.len())
        .map(|(d, _)| *d)
        .collect();
    let rets: Vec<Vec<f64>> = cums
        .iter()
        .map(|c| {
            (1..dates.len())
                .map(|i| c[dates[i]] - c[dates[i - 1]])
                .collect()
        })
        .collect();
    (dates[1..].iter().map(|d| (*d).to_string()).collect(), rets)
}

/// One row group of `-tips`: its names, record, resamples and the series and window they were
/// read on.
struct TipsGroup<const N: usize> {
    names: &'static [&'static str; N],
    series: String,
    window: String,
    n: usize,
    record: [f64; N],
    reads: Vec<[f64; N]>,
}

/// `-tips`'s settings: the set, the two windows, and the resampling.
#[derive(Clone)]
struct TipsOpts {
    set: String,
    from: String,
    to: String,
    cpi_from: String,
    cpi_to: String,
    resamples: usize,
    seed: u64,
    joint: f64,
    of: usize,
}

impl TipsOpts {
    fn alpha(&self, k: usize) -> f64 {
        self.joint * k as f64 / self.of as f64
    }

    /// Prints one group: the record, its resamples' quantiles and the group's own joint band at
    /// the group's share of the mode's rows.
    fn print_group<const N: usize>(&self, g: &TipsGroup<N>) {
        let ks: Vec<usize> = (0..N).collect();
        let (c, edges) = ms::record_band_joint(&g.reads, &ks, self.alpha(N));
        for (k, name) in g.names.iter().enumerate() {
            let col: Vec<f64> = g.reads.iter().map(|x| x[k]).collect();
            let qs = band_quantiles(g.record[k], &col);
            let (lo, hi) = edges[k];
            println!(
                "{}\t{name}\t{}\t{}\t{}\t{}\t{:.6}\t{}\t{c:.6}\t{lo:.6}\t{hi:.6}",
                self.set,
                g.series,
                g.window,
                g.n,
                g.reads.len(),
                g.record[k],
                qs.join("\t")
            );
        }
    }

    /// The monthly log changes of a FRED level file over the CPI window, with the levels and
    /// the window.
    fn cpi_changes(&self, file: &str) -> (Vec<(String, f64)>, Vec<f64>, String) {
        let cpi: Vec<(String, f64)> = fred_series(file)
            .into_iter()
            .filter(|(d, _)| {
                d.as_str() >= self.cpi_from.as_str() && d.as_str() <= self.cpi_to.as_str()
            })
            .collect();
        if cpi.len() < 25 {
            usage(&format!("{file}: {} months in the CPI window", cpi.len()));
        }
        let dlog: Vec<f64> = (1..cpi.len())
            .map(|i| ms::ln_det(cpi[i].1 / cpi[i - 1].1))
            .collect();
        let window = format!("{}..{}", cpi[0].0, cpi[cpi.len() - 1].0);
        (cpi, dlog, window)
    }

    /// The CPI group (`CPI_BAND_ROWS`) and the century group (`CPI_CENTURY_ROWS`, the century
    /// file read whole); returns the CPI window's levels for the real-rate group.
    fn cpi_groups(&self, cpi_file: &str, century_file: &str) -> Vec<(String, f64)> {
        let (cpi, dlog, window) = self.cpi_changes(cpi_file);
        let record = ms::cpi_readings(&dlog);
        eprintln!(
            "{}: {} monthly changes {window}; {}; reported {}; {} resamples, seed {}",
            fred_name(cpi_file),
            dlog.len(),
            row_list(&ms::CPI_BAND_ROWS, &record),
            row_list(&ms::CPI_REPORTED_ROWS, &ms::cpi_reported(&dlog)),
            self.resamples,
            self.seed
        );
        self.print_group(&TipsGroup {
            names: &ms::CPI_BAND_ROWS,
            series: fred_name(cpi_file),
            window,
            n: dlog.len(),
            record,
            reads: ms::cpi_resamples(&dlog, self.resamples, self.seed, block_years()),
        });
        let whole = TipsOpts {
            cpi_from: String::new(),
            cpi_to: "9999".to_string(),
            ..self.clone()
        };
        let (_, cdlog, c_window) = whole.cpi_changes(century_file);
        let record = ms::cpi_century_readings(&cdlog);
        eprintln!(
            "{}: {} monthly changes {c_window}; {}",
            fred_name(century_file),
            cdlog.len(),
            row_list(&ms::CPI_CENTURY_ROWS, &record)
        );
        self.print_group(&TipsGroup {
            names: &ms::CPI_CENTURY_ROWS,
            series: fred_name(century_file),
            window: c_window,
            n: cdlog.len(),
            record,
            reads: ms::cpi_century_resamples(&cdlog, self.resamples, self.seed, block_years()),
        });
        cpi
    }

    /// A FRED file's month-end levels over the real window, each with its month, and the window.
    fn month_levels(&self, file: &str) -> (Vec<(String, f64)>, String) {
        let ends: Vec<(String, String, f64)> = month_ends_of(&fred_series(file))
            .into_iter()
            .filter(|(_, d, _)| d.as_str() >= self.from.as_str() && d.as_str() <= self.to.as_str())
            .collect();
        if ends.len() < 25 {
            usage(&format!(
                "{file}: {} month ends in the real window",
                ends.len()
            ));
        }
        let window = format!("{}..{}", ends[0].1, ends[ends.len() - 1].1);
        (ends.into_iter().map(|(m, _, v)| (m, v)).collect(), window)
    }

    /// The real-yield group (`REAL_YIELD_BAND_ROWS`) and the breakeven group
    /// (`BREAKEVEN_BAND_ROWS`), each on its file's month ends; the real yield's month ends are
    /// returned for the real-rate group's reported difference.
    fn level_groups(
        &self,
        real_file: &str,
        bei_file: &str,
        nom_file: &str,
        cpi: &[(String, f64)],
    ) -> Vec<(String, f64)> {
        let (ry_months, window) = self.month_levels(real_file);
        let ry: Vec<f64> = ry_months.iter().map(|(_, v)| *v).collect();
        let record = ms::real_yield_readings(&ry);
        eprintln!(
            "{}: {} month ends {window}; {}; reported {}",
            fred_name(real_file),
            ry.len(),
            row_list(&ms::REAL_YIELD_BAND_ROWS, &record),
            row_list(&ms::REAL_YIELD_REPORTED_ROWS, &ms::real_yield_reported(&ry))
        );
        self.print_group(&TipsGroup {
            names: &ms::REAL_YIELD_BAND_ROWS,
            series: fred_name(real_file),
            window,
            n: ry.len(),
            record,
            reads: ms::real_yield_resamples(&ry, self.resamples, self.seed, block_years()),
        });
        let (bei_months, window) = self.month_levels(bei_file);
        let bei: Vec<f64> = bei_months.iter().map(|(_, v)| *v).collect();
        // the year-over-year rate of the CPI window's months, beside each breakeven month end
        let cpi_by_month: std::collections::HashMap<String, f64> =
            cpi.iter().map(|(d, v)| (d[..7].to_string(), *v)).collect();
        let yoy: Vec<f64> = bei_months
            .iter()
            .map(|(m, _)| {
                let year_ago = format!(
                    "{:04}-{}",
                    m[..4].parse::<usize>().unwrap_or(1) - 1,
                    &m[5..7]
                );
                let (now, year_ago) = (cpi_by_month.get(m), cpi_by_month.get(&year_ago));
                // a month the CPI lacks (October 2025 was never published) leaves no rate
                match (now, year_ago) {
                    (Some(a), Some(b)) => 100.0 * ms::ln_det(a / b),
                    _ => f64::NAN,
                }
            })
            .collect();
        // the nominal ten-year yield at the breakeven's month ends
        let nom_by_month: std::collections::HashMap<String, f64> =
            self.month_levels(nom_file).0.into_iter().collect();
        let y: Vec<f64> = bei_months
            .iter()
            .map(|(m, _)| nom_by_month.get(m).copied().unwrap_or(f64::NAN))
            .collect();
        let record = ms::breakeven_readings(&bei, &yoy, &y);
        eprintln!(
            "{}: {} month ends {window}; {}; reported {}",
            fred_name(bei_file),
            bei.len(),
            row_list(&ms::BREAKEVEN_BAND_ROWS, &record),
            row_list(&ms::BREAKEVEN_REPORTED_ROWS, &ms::breakeven_reported(&bei))
        );
        self.print_group(&TipsGroup {
            names: &ms::BREAKEVEN_BAND_ROWS,
            series: fred_name(bei_file),
            window,
            n: bei.len(),
            record,
            reads: ms::breakeven_resamples(
                &bei,
                &yoy,
                &y,
                self.resamples,
                self.seed,
                block_years(),
            ),
        });
        ry_months
    }

    /// The TIPS group (`TIPS_BAND_ROWS`): a 10-year par TIPS on the real-yield file, the 10-year
    /// par bond on the nominal file and the market on the daily French factors, on the sessions
    /// all three trade.
    fn leg_group(&self, real_file: &str, nom_file: &str, french_file: &str) {
        let real_text = std::fs::read_to_string(real_file)
            .unwrap_or_else(|e| usage(&format!("{real_file}: {e}")));
        let nom_text = std::fs::read_to_string(nom_file)
            .unwrap_or_else(|e| usage(&format!("{nom_file}: {e}")));
        let tips_daily = read_treasury(&real_text, 10.0);
        let durs = treasury_durations(&real_text, 10.0);
        let nom_daily = read_treasury(&nom_text, 10.0);
        let eq_daily: Vec<(String, f64)> = read_french(french_file)
            .into_iter()
            .map(|(d, v)| (d, ms::ln_det(1.0 + v / 100.0)))
            .collect();
        let (dates, rets) =
            join_returns(&[&tips_daily, &nom_daily, &eq_daily], &self.from, &self.to);
        if dates.len() < 2 * 252 {
            usage(&format!(
                "{real_file}: {} common sessions in the real window",
                dates.len()
            ));
        }
        let dur: Vec<f64> = dates
            .iter()
            .map(|d| {
                durs.get(d)
                    .unwrap_or_else(|| usage(&format!("{real_file}: no duration for {d}")))
                    .0
            })
            .collect();
        let window = format!("{}..{}", dates[0], dates[dates.len() - 1]);
        let record = ms::tips_readings(&rets[0], &dur, &rets[1], &rets[2]);
        let r2022: f64 = tips_daily
            .iter()
            .filter(|(d, _)| d.as_str() > "2021-12-31" && d.as_str() <= "2022-10-31")
            .map(|(_, r)| *r)
            .sum();
        eprintln!(
            "{} par TIPS: {} common sessions {window}; {}; reported 2022 real return (Dec 2021 - Oct 2022) {:.2}%",
            fred_name(real_file),
            dates.len(),
            row_list(&ms::TIPS_BAND_ROWS, &record),
            (ms::exp_det(r2022) - 1.0) * 100.0
        );
        self.print_group(&TipsGroup {
            names: &ms::TIPS_BAND_ROWS,
            series: fred_name(real_file),
            window,
            n: dates.len(),
            record,
            reads: ms::tips_resamples(
                &rets[0],
                &dur,
                &rets[1],
                &rets[2],
                self.resamples,
                self.seed,
                block_years(),
            ),
        });
    }

    /// The real-rate group (`REAL_RATE_BAND_ROWS`): each month's mean of the daily policy rate
    /// less the next twelve months' inflation on the CPI window's levels.
    /// The real-rate group (`REAL_RATE_BAND_ROWS`) on the policy file and the CPI, and the
    /// reported difference of the real yield over the ex-post real rate on the months both hold.
    fn real_rate_group(
        &self,
        dff_file: &str,
        cpi_name: &str,
        cpi: &[(String, f64)],
        ry: &[(String, f64)],
    ) {
        let mut dff_months: Vec<(String, f64, usize)> = Vec::new();
        for (d, v) in fred_series(dff_file) {
            let m = &d[..7];
            match dff_months.last_mut() {
                Some(last) if last.0 == m => {
                    last.1 += v;
                    last.2 += 1;
                }
                _ => dff_months.push((m.to_string(), v, 1)),
            }
        }
        let cpi_by_month: std::collections::HashMap<String, f64> =
            cpi.iter().map(|(d, v)| (d[..7].to_string(), *v)).collect();
        let rr: Vec<(String, f64)> = dff_months
            .iter()
            .filter_map(|(m, sum, n)| {
                let now = cpi_by_month.get(m)?;
                let next = cpi_by_month.get(&month_add(m, 12))?;
                Some((m.clone(), sum / *n as f64 - ms::ln_det(next / now) * 100.0))
            })
            .collect();
        if rr.len() < 25 {
            usage(&format!(
                "{dff_file}: {} months with a year of CPI ahead",
                rr.len()
            ));
        }
        let window = format!("{}..{}", rr[0].0, rr[rr.len() - 1].0);
        let x: Vec<f64> = rr.iter().map(|(_, v)| *v).collect();
        let record = ms::real_rate_readings(&x);
        let rr_by_month: std::collections::HashMap<&str, f64> =
            rr.iter().map(|(m, v)| (m.as_str(), *v)).collect();
        let both: Vec<(f64, f64)> = ry
            .iter()
            .filter_map(|(m, v)| rr_by_month.get(m.as_str()).map(|r| (*v, *r)))
            .collect();
        let nb = both.len() as f64;
        let over = both.iter().map(|(y, _)| y).sum::<f64>() / nb
            - both.iter().map(|(_, r)| r).sum::<f64>() / nb;
        eprintln!(
            "{} less the next year's {cpi_name}: {} months {window}; {}; reported real yield less the ex-post real rate over their {} common months {over:.3}",
            fred_name(dff_file),
            x.len(),
            row_list(&ms::REAL_RATE_BAND_ROWS, &record),
            both.len()
        );
        self.print_group(&TipsGroup {
            names: &ms::REAL_RATE_BAND_ROWS,
            series: fred_name(dff_file),
            window,
            n: x.len(),
            record,
            reads: ms::real_rate_resamples(&x, self.resamples, self.seed, block_years()),
        });
    }
}

/// `-tips`: THE REAL SIDE's six row groups (item 60), each on a public-domain record -- CPI-U
/// monthly and its century, DFII10 and T10YIE at month ends, a 10-year par TIPS rebought at every
/// DFII10 observation beside the DGS10 par bond and the equity market on their common sessions,
/// and the policy rate less the next year's realised inflation -- resampled in one-year blocks of
/// each record's own cadence, each group under its own joint band at the mode's share of `-of`.
fn tips_mode(args: &[String]) -> bool {
    if !args.iter().any(|a| a == "-tips") {
        return false;
    }
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    let need = |flag: &str| opt(flag).unwrap_or_else(|| usage(&format!("-tips wants {flag} FILE")));
    let (cpi_file, century_file) = (need("-cpi"), need("-cpicentury"));
    let (real_file, bei_file) = (need("-real"), need("-breakeven"));
    let (nom_file, dff_file, french_file) = (need("-nominal"), need("-fred"), need("-french"));
    let o = TipsOpts {
        set: opt("-set").unwrap_or_else(|| usage("-tips wants -set")),
        from: opt("-from").unwrap_or_default(),
        to: opt("-to").unwrap_or_else(|| "9999".to_string()),
        cpi_from: opt("-cpifrom").unwrap_or_default(),
        cpi_to: opt("-cpito").unwrap_or_else(|| "9999".to_string()),
        resamples: opt("-resamples").map_or(20_000, |v| num(&v, "-resamples wants an integer")),
        seed: opt("-seed").map_or(20_260_918, |v| {
            num(&v, "-seed wants a non-negative integer")
        }),
        joint: opt("-joint").map_or(0.10, |v| num(&v, "-joint wants a share in (0, 1)")),
        of: opt("-of").map_or(16, |v| num(&v, "-of wants a row count")),
    };
    print_band_header(args);
    let cpi = o.cpi_groups(&cpi_file, &century_file);
    let ry = o.level_groups(&real_file, &bei_file, &nom_file, &cpi);
    o.leg_group(&real_file, &nom_file, &french_file);
    o.real_rate_group(&dff_file, &fred_name(&cpi_file), &cpi, &ry);
    true
}

/// `name value, name value, ...` for a summary line.
fn row_list(names: &[&str], values: &[f64]) -> String {
    names
        .iter()
        .zip(values)
        .map(|(n, v)| format!("{n} {v:.4}"))
        .collect::<Vec<_>>()
        .join(", ")
}

fn sector_mode(args: &[String]) -> bool {
    let Some(k) = args.iter().position(|a| a == "-sectors") else {
        return false;
    };
    let dir = args
        .get(k + 1)
        .cloned()
        .unwrap_or_else(|| usage("-sectors needs a directory"));
    let num = |flag: &str, default: u64| -> u64 {
        args.iter().position(|a| a == flag).map_or(default, |i| {
            args.get(i + 1)
                .and_then(|v| v.parse().ok())
                .unwrap_or_else(|| usage(&format!("{flag} wants a non-negative integer")))
        })
    };
    let resamples = usize::try_from(num("-resamples", 20_000))
        .unwrap_or_else(|_| usage("-resamples is too large"));
    if let Some(y) = block_years() {
        print_sector_scale_rows(args, &dir, y);
        return true;
    }
    print_sector_rows(&dir, resamples, num("-seed", 20_260_918));
    true
}

/// `-sectors DIR -block N`: THE GRADED SECTOR ROWS (`SECTOR_ROWS`, `sector_rows_of_panel`) on the
/// ten industries' whole monthly panel, as a history is read, and their resamples in blocks of
/// years of months, every column taking the same months, in the band columns, for -set (sp500).
fn print_sector_scale_rows(args: &[String], dir: &str, years: usize) {
    let set = args
        .iter()
        .position(|a| a == "-set")
        .and_then(|i| args.get(i + 1).cloned())
        .unwrap_or_else(|| "sp500".to_string());
    let (months, p) = sector_panel(dir, "10_Industry_Portfolios.CSV");
    let n = p.market.len();
    let window = format!("{}..{}", months[0], months[n - 1]);
    let (resamples, seed, joint) = resampling_of(args);
    let idx: Vec<f64> = (0..n).map(|i| i as f64).collect();
    let reads = ms::series_resamples(&idx, 12, years, resamples, seed, |x| {
        let at = |i: &f64| *i as usize;
        ms::sector_rows_of_panel(&ms::SectorPanel {
            returns: p
                .returns
                .iter()
                .map(|c| x.iter().map(|i| c[at(i)]).collect())
                .collect(),
            market: x.iter().map(|i| p.market[at(i)]).collect(),
            rf: x.iter().map(|i| p.rf[at(i)]).collect(),
        })
    });
    print_band_header(args);
    print_resampled_rows(
        &set,
        "French10",
        &window,
        n,
        &ms::SECTOR_ROWS,
        &ms::sector_rows_of_panel(&p),
        &reads,
        joint,
    );
}

/// `YYYY-MM` less one month.
fn month_before(key: &str) -> String {
    let y: usize = key[..4].parse().unwrap_or(0);
    let m: usize = key[5..7].parse().unwrap_or(1);
    if m == 1 {
        format!("{:04}-12", y - 1)
    } else {
        format!("{y:04}-{:02}", m - 1)
    }
}

/// The year-over-year CPI rate the market had seen at each of `months` 21-session months' first
/// session (`dates[21k]`): -cpi's rate of the calendar month before, in simple percent, or the
/// last published month before that (October 2025 was never published).
fn inflation_seen(cpi_file: &str, dates: &[String], months: usize) -> Vec<f64> {
    let level: std::collections::HashMap<String, f64> = fred_series(cpi_file)
        .into_iter()
        .map(|(d, v)| (d[..7].to_string(), v))
        .collect();
    let yoy_by_month: std::collections::HashMap<String, f64> = level
        .iter()
        .filter_map(|(k, v)| {
            let y: usize = k[..4].parse().ok()?;
            let prior = format!("{:04}-{}", y - 1, &k[5..7]);
            level
                .get(&prior)
                .map(|p| (k.clone(), (v / p - 1.0) * 100.0))
        })
        .collect();
    (0..months)
        .map(|k| {
            let first = &dates[k * 21];
            let mut m = month_before(&first[..7]);
            for _ in 0..3 {
                if let Some(v) = yoy_by_month.get(&m) {
                    return *v;
                }
                m = month_before(&m);
            }
            usage(&format!(
                "{cpi_file}: no year-over-year rate before {}",
                &first[..7]
            ))
        })
        .collect()
}

/// `-stockbond`: THE STOCK-BOND ROWS (`STOCK_BOND_BAND_ROWS`, `CPI_NEWS_ROW`;
/// `stockbond-2026-10-18.tsv`) for -set: the DGS10 par bond (-nominal) and Ken French's market
/// (-french) over 21-session months of the sessions both trade in [-from, -to], each month placed by
/// -cpi's year-over-year rate of the calendar month before its first session; and the CPI news row
/// over [-cpifrom, -cpito]. The reported rows (`STOCK_BOND_REPORTED_ROWS`) go to stderr.
fn stock_bond_mode(args: &[String]) -> bool {
    if !args.iter().any(|a| a == "-stockbond") {
        return false;
    }
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    let need =
        |flag: &str| opt(flag).unwrap_or_else(|| usage(&format!("-stockbond wants {flag} FILE")));
    let (nom_file, french_file, cpi_file) = (need("-nominal"), need("-french"), need("-cpi"));
    let o = TipsOpts {
        set: opt("-set").unwrap_or_else(|| usage("-stockbond wants -set")),
        from: opt("-from").unwrap_or_default(),
        to: opt("-to").unwrap_or_else(|| "9999".to_string()),
        cpi_from: opt("-cpifrom").unwrap_or_default(),
        cpi_to: opt("-cpito").unwrap_or_else(|| "9999".to_string()),
        resamples: opt("-resamples").map_or(20_000, |v| num(&v, "-resamples wants an integer")),
        seed: opt("-seed").map_or(20_260_918, |v| {
            num(&v, "-seed wants a non-negative integer")
        }),
        joint: opt("-joint").map_or(0.10, |v| num(&v, "-joint wants a share in (0, 1)")),
        of: opt("-of").map_or(5, |v| num(&v, "-of wants a row count")),
    };
    print_band_header(args);
    let nom_text =
        std::fs::read_to_string(&nom_file).unwrap_or_else(|e| usage(&format!("{nom_file}: {e}")));
    let nom_daily = read_treasury(&nom_text, 10.0);
    let eq_daily: Vec<(String, f64)> = read_french(&french_file)
        .into_iter()
        .map(|(d, v)| (d, ms::ln_det(1.0 + v / 100.0)))
        .collect();
    let (dates, rets) = join_returns(&[&nom_daily, &eq_daily], &o.from, &o.to);
    if dates.len() < 2 * 252 {
        usage(&format!(
            "{nom_file}: {} common sessions in the window",
            dates.len()
        ));
    }
    let b_m = ms::monthly_sums(&rets[0]);
    let eq_m = ms::monthly_sums(&rets[1]);
    let seen = inflation_seen(&cpi_file, &dates, b_m.len());
    let window = format!("{}..{}", dates[0], dates[dates.len() - 1]);
    let record = ms::stock_bond_readings(&eq_m, &b_m, &seen);
    let reported = ms::stock_bond_reported(&eq_m, &b_m);
    eprintln!(
        "{} par bond and the market: {} common sessions {window}, {} months; {}; reported {} {:.6}, {} {:.6}, {} {:.6}; {} resamples, seed {}",
        fred_name(&nom_file),
        dates.len(),
        b_m.len(),
        row_list(&ms::STOCK_BOND_BAND_ROWS, &record),
        ms::STOCK_BOND_REPORTED_ROWS[0],
        reported[0],
        ms::STOCK_BOND_REPORTED_ROWS[1],
        reported[1],
        ms::STOCK_BOND_REPORTED_ROWS[2],
        reported[2],
        o.resamples,
        o.seed
    );
    o.print_group(&TipsGroup {
        names: &ms::STOCK_BOND_BAND_ROWS,
        series: format!("{} par bond, French market", fred_name(&nom_file)),
        window,
        n: b_m.len(),
        record,
        reads: ms::stock_bond_resamples(&eq_m, &b_m, &seen, o.resamples, o.seed, block_years()),
    });
    let (_, dlog, cpi_window) = o.cpi_changes(&cpi_file);
    let news = ms::cpi_news_reading(&dlog);
    eprintln!(
        "{}: {} monthly changes {cpi_window}; {} {news:.4}",
        fred_name(&cpi_file),
        dlog.len(),
        ms::CPI_NEWS_ROW
    );
    o.print_group(&TipsGroup {
        names: &[ms::CPI_NEWS_ROW],
        series: fred_name(&cpi_file),
        window: cpi_window,
        n: dlog.len(),
        record: [news],
        reads: ms::cpi_news_resamples(&dlog, o.resamples, o.seed, block_years()),
    });
    true
}

/// `-profits`: THE PROFIT DECLINE ROWS (`PROFIT_DECLINE_ROWS`; `profits-2026-10-19.tsv`) for -set:
/// the -fred quarterly levels in file order, every row whose value parses, read by
/// `profit_decline_of`; the window the first and last dates; the episodes to stderr.
fn profits_mode(args: &[String]) -> bool {
    if !args.iter().any(|a| a == "-profits") {
        return false;
    }
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    let file = opt("-fred").unwrap_or_else(|| usage("-profits wants -fred FILE"));
    let set = opt("-set").unwrap_or_else(|| usage("-profits wants -set"));
    let series = opt("-series").unwrap_or_else(|| fred_name(&file));
    let rows = fred_series(&file);
    if rows.is_empty() {
        usage(&format!("{file} holds no quarters"));
    }
    let levels: Vec<f64> = rows.iter().map(|(_, v)| *v).collect();
    let window = format!("{}..{}", rows[0].0, rows[rows.len() - 1].0);
    let record = ms::profit_decline_of(&levels);
    let eps = ms::profit_episodes(&levels);
    let months = |q: usize| q * 12 / ms::QUARTERS_PER_YEAR;
    eprintln!(
        "{series}: {} quarters {window}; {} declines of {:.0}%+",
        levels.len(),
        eps.len(),
        100.0 * ms::PROFIT_DECLINE_DEPTH
    );
    for e in &eps {
        let regain = e.regain.map_or_else(
            || "open".to_string(),
            |r| format!("{} months", months(r - e.trough)),
        );
        eprintln!(
            "  {}..{}  size {:5.1}%  fall {:3} months  regain {regain}",
            rows[e.peak].0,
            rows[e.trough].0,
            100.0 * e.fall,
            months(e.trough - e.peak)
        );
    }
    if let Some(y) = block_years() {
        print_band_header(args);
        let (resamples, seed, joint) = resampling_of(args);
        let reads = level_resamples(
            &levels,
            ms::QUARTERS_PER_YEAR,
            y,
            (resamples, seed),
            ms::profit_decline_of,
        );
        print_resampled_rows(
            &set,
            &series,
            &window,
            levels.len(),
            &ms::PROFIT_DECLINE_ROWS,
            &record,
            &reads,
            joint,
        );
        return true;
    }
    if args.iter().any(|a| a == "-header") {
        println!("set\trow\tseries\twindow\tn\trecord");
    }
    for (name, value) in ms::PROFIT_DECLINE_ROWS.iter().zip(record) {
        println!(
            "{set}\t{name}\t{series}\t{window}\t{}\t{value:.6}",
            levels.len()
        );
    }
    true
}

/// `-bondstress`: THE BOND STRESS SHARE ROW (`BOND_STRESS_ROW`; `recordbands-2026-09-26.tsv`)
/// for -set: the spiral's stress index run on the -fred yield file as an n-year par bond (-years,
/// 20 by default) over every session in [-from, -to], a return spanning more than ten calendar
/// days (the series' gap) dropped with the state carried across it; the share of sessions above
/// 0.5 after a one-year burn-in, in percent, and its one-year-block resamples.
fn bond_stress_mode(args: &[String]) -> bool {
    if !args.iter().any(|a| a == "-bondstress") {
        return false;
    }
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    let fred_file = opt("-fred").unwrap_or_else(|| usage("-bondstress wants -fred FILE"));
    let years: f64 = opt("-years").map_or(20.0, |v| num(&v, "-years wants a number"));
    let o = TipsOpts {
        set: opt("-set").unwrap_or_else(|| usage("-bondstress wants -set")),
        from: opt("-from").unwrap_or_default(),
        to: opt("-to").unwrap_or_else(|| "9999".to_string()),
        cpi_from: String::new(),
        cpi_to: "9999".to_string(),
        resamples: opt("-resamples").map_or(20_000, |v| num(&v, "-resamples wants an integer")),
        seed: opt("-seed").map_or(20_260_918, |v| {
            num(&v, "-seed wants a non-negative integer")
        }),
        joint: opt("-joint").map_or(0.10, |v| num(&v, "-joint wants a share in (0, 1)")),
        of: opt("-of").map_or(1, |v| num(&v, "-of wants a row count")),
    };
    print_band_header(args);
    let text =
        std::fs::read_to_string(&fred_file).unwrap_or_else(|e| usage(&format!("{fred_file}: {e}")));
    let daily = read_treasury(&text, years);
    let mut dates: Vec<&str> = Vec::new();
    let mut rets: Vec<f64> = Vec::new();
    let mut gaps = 0usize;
    for (i, (d, r)) in daily.iter().enumerate() {
        if d.as_str() < o.from.as_str() || d.as_str() > o.to.as_str() {
            continue;
        }
        if i > 0 && days_from_civil(d) - days_from_civil(&daily[i - 1].0) > 10 {
            gaps += 1;
            continue;
        }
        dates.push(d);
        rets.push(*r);
    }
    if rets.len() < 2 * 252 {
        usage(&format!(
            "{fred_file}: {} sessions in the window",
            rets.len()
        ));
    }
    let window = format!("{}..{}", dates[0], dates[dates.len() - 1]);
    let record = ms::bond_stress_share(&rets);
    eprintln!(
        "{} as a {years:.0}-year par bond: {} sessions {window}, {gaps} gap returns dropped; {} {record:.4}; {} resamples, seed {}",
        fred_name(&fred_file),
        rets.len(),
        ms::BOND_STRESS_ROW,
        o.resamples,
        o.seed
    );
    o.print_group(&TipsGroup {
        names: &[ms::BOND_STRESS_ROW],
        series: format!("{} par bond", fred_name(&fred_file)),
        window,
        n: rets.len(),
        record: [record],
        reads: ms::bond_stress_resamples(&rets, o.resamples, o.seed, block_years()),
    });
    true
}

/// `-cpipersist`: THE CPI PERSISTENCE ROW (`CPI_PERSISTENCE_ROW`) of -cpi over the CPI window for
/// -set, the record's own reading: a single-history row, not resampled.
fn cpi_persist_mode(args: &[String]) -> bool {
    if !args.iter().any(|a| a == "-cpipersist") {
        return false;
    }
    let opt = |flag: &str| -> Option<String> {
        args.iter()
            .position(|a| a == flag)
            .and_then(|i| args.get(i + 1).cloned())
    };
    let cpi_file = opt("-cpi").unwrap_or_else(|| usage("-cpipersist wants -cpi FILE"));
    let o = TipsOpts {
        set: opt("-set").unwrap_or_else(|| usage("-cpipersist wants -set")),
        from: String::new(),
        to: "9999".to_string(),
        cpi_from: opt("-cpifrom").unwrap_or_default(),
        cpi_to: opt("-cpito").unwrap_or_else(|| "9999".to_string()),
        resamples: 0,
        seed: 0,
        joint: 0.0,
        of: 0,
    };
    let (_, dlog, window) = o.cpi_changes(&cpi_file);
    if let Some(y) = block_years() {
        print_band_header(args);
        let (resamples, seed, joint) = resampling_of(args);
        let read = |d: &[f64]| [ms::cpi_persistence(d)];
        let reads = ms::series_resamples(&dlog, 12, y, resamples, seed, read);
        print_resampled_rows(
            &o.set,
            &fred_name(&cpi_file),
            &window,
            dlog.len(),
            &[ms::CPI_PERSISTENCE_ROW],
            &read(&dlog),
            &reads,
            joint,
        );
        return true;
    }
    if args.iter().any(|a| a == "-header") {
        println!("set\trow\tseries\twindow\tn\trecord");
    }
    println!(
        "{}\t{}\t{}\t{window}\t{}\t{:.6}",
        o.set,
        ms::CPI_PERSISTENCE_ROW,
        fred_name(&cpi_file),
        dlog.len(),
        ms::cpi_persistence(&dlog)
    );
    true
}

/// A mode that reads its own arguments, run if the arguments name one: whether one ran.
fn own_mode(args: &[String]) -> bool {
    scales_mode(args)
        || resampled_mode(args)
        || satellite_mode(args)
        || dividend_mode(args)
        || unlagged_mode(args)
        || basket_mode(args)
        || sector_mode(args)
        || timing_mode(args)
        || volexit_mode(args)
        || vol_state_mode(args)
        || ledger_rows_mode(args)
        || cpi_persist_mode(args)
        || tips_mode(args)
        || stock_bond_mode(args)
        || bond_stress_mode(args)
        || profits_mode(args)
}

#[expect(
    clippy::too_many_lines,
    reason = "one dispatch over the modes, read once, mirroring the Scala twin's main"
)]
fn main() {
    #[cfg(feature = "fast-alloc")]
    fast_alloc::reuse_freed_memory();
    let args: Vec<String> = std::env::args().skip(1).collect();
    assert!(
        LAG.set(lag_of(&args)).is_ok(),
        "the lag switches are read once"
    );
    assert!(BLOCK.set(block_of(&args)).is_ok(), "the block is read once");
    // a mode that reads its own arguments runs and is done
    if own_mode(&args) {
        return;
    }
    let o = parse_args(&args);
    let dated = returns_in_window(&o);
    if dated.len() <= 252 {
        usage(&format!(
            "the window holds {} sessions; a block bootstrap needs more than a year",
            dated.len()
        ));
    }
    let r: Vec<f64> = dated.iter().map(|(_, x)| *x).collect();
    let window = format!("{}..{}", dated[0].0, dated[dated.len() - 1].0);
    if let Some(rate_file) = &o.after_rate {
        rate_after_mode(&o, rate_file, &dated, &window);
        return;
    }
    if o.coupling {
        print_coupling_rows(&o, &r, &window);
        return;
    }
    if o.multiyear {
        print_multi_year_rows(&o, &r, &window);
        return;
    }
    if o.worst {
        print_worst_crash_row(&o, &r, &window);
        return;
    }
    if o.rate {
        print_rate_rows(&o, &r, &window);
        return;
    }
    if o.bond {
        print_bond_rows(&o, &r, &window);
        return;
    }
    if o.bond10 {
        print_bond10_rows(&o, &dated, &window);
        return;
    }
    eprintln!(
        "{}: {} returns {window}, {} exactly zero; {} resamples, seed {}",
        o.series,
        r.len(),
        r.iter().filter(|x| **x == 0.0).count(),
        o.resamples,
        o.seed
    );

    let mut record = ms::series_readings(&r);
    for (k, name) in ms::RECORD_BAND_ROWS.iter().enumerate() {
        if *name == "typical-year vol %" {
            record[k] = ms::year_vol_phase_mean(&r);
        }
    }
    let reads = ms::record_resamples(&r, o.resamples, o.seed, block_years());

    if o.header {
        let pcts: Vec<String> = ms::RECORD_BAND_PCTS
            .iter()
            .map(|p| format!("p{p}"))
            .collect();
        println!(
            "set\trow\tseries\twindow\tn\tresamples\trecord\t{}\tjointC\tjointLo\tjointHi",
            pcts.join("\t")
        );
    }
    // THE JOINT BAND over the rows this window prints, at this window's share of the set's miss rate
    let ks: Vec<usize> = (0..ms::RECORD_BAND_ROWS.len())
        .filter(|&k| o.rows.is_empty() || o.rows.iter().any(|r| r == ms::RECORD_BAND_ROWS[k]))
        .collect();
    let of = if o.of == 0 { ks.len() } else { o.of };
    let alpha = o.joint * ks.len() as f64 / of as f64;
    let (c, edges) = ms::record_band_joint(&reads, &ks, alpha);
    // how often each row's own 5th-95th band holds a resample on every row at once: the figure
    // the joint band's docstrings quote
    let own: Vec<(f64, f64)> = ks
        .iter()
        .map(|&k| {
            let col: Vec<f64> = reads.iter().map(|x| x[k]).collect();
            let q = ms::record_band_quantiles(&col);
            (q[2], q[20])
        })
        .collect();
    let held = reads
        .iter()
        .filter(|x| {
            ks.iter()
                .zip(&own)
                .all(|(&k, (lo, hi))| x[k] >= *lo && x[k] <= *hi)
        })
        .count();
    eprintln!(
        "each row's own p5..p95 held the resamples on all {} rows at once {:.1}% of the time",
        ks.len(),
        held as f64 * 100.0 / reads.len() as f64
    );
    for (&k, (lo, hi)) in ks.iter().zip(&edges) {
        let name = ms::RECORD_BAND_ROWS[k];
        let col: Vec<f64> = reads.iter().map(|x| x[k]).collect();
        let qs = band_quantiles(record[k], &col);
        println!(
            "{}\t{name}\t{}\t{window}\t{}\t{}\t{:.6}\t{}\t{c:.6}\t{lo:.6}\t{hi:.6}",
            o.set,
            o.series,
            r.len(),
            o.resamples,
            record[k],
            qs.join("\t")
        );
    }
}

/// The record's multi-year rows (`multi_year_readings`), under their long-window names with
/// `-long`: records alone, since the rows are graded against the world's own histories.
/// `-coupling`: the bubble rows of `bubblebust-2026-09-24.tsv` on the window, the record alone;
/// under `-block` their resamples too.
fn print_coupling_rows(o: &Opts, r: &[f64], window: &str) {
    let names = [
        "bubble coupling 3y",
        "largest 3y run-up",
        "longest calm stretch",
        "variance ratio 250d",
    ];
    let read = |x: &[f64]| {
        [
            ms::bubble_coupling_of(x),
            ms::run_up_3y_of(x),
            ms::calm_stretch_of(x),
            ms::variance_ratio(x, 250),
        ]
    };
    if let Some(y) = block_years() {
        if o.header {
            print_band_header(&["-header".to_string()]);
        }
        let reads = ms::series_resamples(r, 252, y, o.resamples, o.seed, read);
        print_resampled_rows(
            &o.set,
            &o.series,
            window,
            r.len(),
            &names,
            &read(r),
            &reads,
            o.joint,
        );
        return;
    }
    if o.header {
        println!("set\trow\tseries\twindow\tn\trecord");
    }
    for (name, value) in names.iter().zip(read(r)) {
        println!(
            "{}\t{name}\t{}\t{window}\t{}\t{value:.6}",
            o.set,
            o.series,
            r.len()
        );
    }
}

/// `-worstcrash`: THE WORST CRASH ROW (`worst crash %`, `worst_crash_of`) on the price the window's
/// daily log returns compound to from 1, the record alone; under `-block` its resamples too.
fn print_worst_crash_row(o: &Opts, r: &[f64], window: &str) {
    let read = |x: &[f64]| {
        let px: Vec<f64> = std::iter::once(1.0)
            .chain(x.iter().scan(0.0, |c, v| {
                *c += v;
                Some(ms::exp_det(*c))
            }))
            .collect();
        [ms::worst_crash_of(&px)]
    };
    let names = ["worst crash %"];
    if let Some(y) = block_years() {
        if o.header {
            print_band_header(&["-header".to_string()]);
        }
        let reads = ms::series_resamples(r, 252, y, o.resamples, o.seed, read);
        print_resampled_rows(
            &o.set,
            &o.series,
            window,
            r.len(),
            &names,
            &read(r),
            &reads,
            o.joint,
        );
        return;
    }
    if o.header {
        println!("set\trow\tseries\twindow\tn\trecord");
    }
    println!(
        "{}\t{}\t{}\t{window}\t{}\t{:.6}",
        o.set,
        names[0],
        o.series,
        r.len(),
        read(r)[0]
    );
}

fn print_multi_year_rows(o: &Opts, r: &[f64], window: &str) {
    let names = if o.long {
        ms::MULTI_YEAR_LONG_ROWS
    } else {
        ms::MULTI_YEAR_ROWS
    };
    if let Some(y) = block_years() {
        if o.header {
            print_band_header(&["-header".to_string()]);
        }
        let reads = ms::series_resamples(r, 252, y, o.resamples, o.seed, ms::multi_year_readings);
        print_resampled_rows(
            &o.set,
            &o.series,
            window,
            r.len(),
            &names,
            &ms::multi_year_readings(r),
            &reads,
            o.joint,
        );
        return;
    }
    if o.header {
        println!("set\trow\tseries\twindow\tn\trecord");
    }
    for (name, value) in names.iter().zip(ms::multi_year_readings(r)) {
        println!(
            "{}\t{name}\t{}\t{window}\t{}\t{value:.6}",
            o.set,
            o.series,
            r.len()
        );
    }
}

/// The two rate rows (`RATE_BAND_ROWS`) on a rate window: the record by `rate_readings`, its
/// block resamples by `rate_resamples`, and a joint band over the two at this window's share of
/// the set's miss rate -- the same columns as the equity rows.
fn print_rate_rows(o: &Opts, rate: &[f64], window: &str) {
    eprintln!(
        "{}: {} sessions {window}, mean {:.4}%, {:.2}% under the floor; {} resamples, seed {}",
        o.series,
        rate.len(),
        ms::rate_readings(rate)[0],
        ms::rate_readings(rate)[1],
        o.resamples,
        o.seed
    );
    let record = ms::rate_readings(rate);
    let reads = ms::rate_resamples(rate, o.resamples, o.seed, block_years());
    if o.header {
        let pcts: Vec<String> = ms::RECORD_BAND_PCTS
            .iter()
            .map(|p| format!("p{p}"))
            .collect();
        println!(
            "set\trow\tseries\twindow\tn\tresamples\trecord\t{}\tjointC\tjointLo\tjointHi",
            pcts.join("\t")
        );
    }
    let ks: Vec<usize> = (0..ms::RATE_BAND_ROWS.len())
        .filter(|&k| o.rows.is_empty() || o.rows.iter().any(|r| r == ms::RATE_BAND_ROWS[k]))
        .collect();
    let of = if o.of == 0 { ks.len() } else { o.of };
    let alpha = o.joint * ks.len() as f64 / of as f64;
    let (c, edges) = ms::record_band_joint(&reads, &ks, alpha);
    for (&k, (lo, hi)) in ks.iter().zip(&edges) {
        let name = ms::RATE_BAND_ROWS[k];
        let col: Vec<f64> = reads.iter().map(|x| x[k]).collect();
        let qs = band_quantiles(record[k], &col);
        println!(
            "{}\t{name}\t{}\t{window}\t{}\t{}\t{:.6}\t{}\t{c:.6}\t{lo:.6}\t{hi:.6}",
            o.set,
            o.series,
            rate.len(),
            o.resamples,
            record[k],
            qs.join("\t")
        );
    }
}

/// The bond row (`BOND_BAND_ROWS`) on a bond's return window (`-yahoo`, TLT): the record by
/// `bond_readings`, its block resamples by `bond_resamples`, a joint band over the one row at
/// this window's share of the set's miss rate -- the same columns as the equity rows.
fn print_bond_rows(o: &Opts, r: &[f64], window: &str) {
    let record = ms::bond_readings(r);
    eprintln!(
        "{}: {} returns {window}, bond depth vs vol {:.4}; {} resamples, seed {}",
        o.series,
        r.len(),
        record[0],
        o.resamples,
        o.seed
    );
    let reads = ms::bond_resamples(r, o.resamples, o.seed, block_years());
    if o.header {
        let pcts: Vec<String> = ms::RECORD_BAND_PCTS
            .iter()
            .map(|p| format!("p{p}"))
            .collect();
        println!(
            "set\trow\tseries\twindow\tn\tresamples\trecord\t{}\tjointC\tjointLo\tjointHi",
            pcts.join("\t")
        );
    }
    let ks: Vec<usize> = vec![0];
    let of = if o.of == 0 { 1 } else { o.of };
    let alpha = o.joint / of as f64;
    let (c, edges) = ms::record_band_joint(&reads, &ks, alpha);
    let col: Vec<f64> = reads.iter().map(|x| x[0]).collect();
    let qs = band_quantiles(record[0], &col);
    let (lo, hi) = edges[0];
    println!(
        "{}\t{}\t{}\t{window}\t{}\t{}\t{:.6}\t{}\t{c:.6}\t{lo:.6}\t{hi:.6}",
        o.set,
        ms::BOND_BAND_ROWS[0],
        o.series,
        r.len(),
        o.resamples,
        record[0],
        qs.join("\t")
    );
}

/// `date -> (modified duration, the prior observation's date)` of the n-year par Treasury
/// `read_treasury` rebuys at each observation, keyed by the date of the return it earns: the
/// duration bought at the prior observation's yield.
fn treasury_durations(text: &str, years: f64) -> std::collections::HashMap<String, (f64, String)> {
    let obs: Vec<(String, f64)> = text
        .lines()
        .skip(1)
        .filter_map(|l| {
            let f: Vec<&str> = l.split(',').map(str::trim).collect();
            let v: f64 = f.get(1)?.parse().ok()?;
            Some((f[0].to_string(), v / 100.0))
        })
        .collect();
    (1..obs.len())
        .map(|i| {
            let y = obs[i - 1].1;
            let d = if y != 0.0 {
                (1.0 - ms::exp_det(-2.0 * years * ms::ln_det(1.0 + y / 2.0))) / y
            } else {
                years
            };
            (obs[i].0.clone(), (d, obs[i - 1].0.clone()))
        })
        .collect()
}

/// `(first day number, running log accrual by day)` of FRED's daily DFF file: every calendar day
/// accrues `ln(1 + rate / 365)` at the last rate published (percent a year).
fn cash_curve(file: &str) -> (i64, Vec<f64>) {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    let obs: Vec<(i64, f64)> = text
        .lines()
        .skip(1)
        .filter_map(|l| {
            let f: Vec<&str> = l.split(',').map(str::trim).collect();
            let v: f64 = f.get(1)?.parse().ok()?;
            Some((days_from_civil(f[0]), v / 100.0))
        })
        .collect();
    let Some(&(first, rate0)) = obs.first() else {
        usage(&format!("{file}: no rates"))
    };
    let last = obs.last().map_or(first, |o| o.0);
    let mut cum = Vec::new();
    let (mut acc, mut rate, mut j) = (0.0f64, rate0, 0usize);
    for day in first..=last {
        if j < obs.len() && obs[j].0 == day {
            rate = obs[j].1;
            j += 1;
        }
        acc += ms::ln_det(1.0 + rate / 365.0);
        cum.push(acc);
    }
    (first, cum)
}

/// The short rate's log accrual over the days after `from` through `to` (`cash_curve`).
fn cash_accrual(curve: &(i64, Vec<f64>), from: &str, to: &str) -> f64 {
    let at = |d: &str| -> f64 {
        let k = days_from_civil(d) - curve.0;
        match usize::try_from(k) {
            Ok(i) => curve.1[i.min(curve.1.len() - 1)],
            Err(_) => 0.0,
        }
    };
    at(to) - at(from)
}

/// `-bond10`: the 10-year leg's three rows (`BOND10_BAND_ROWS`) on FRED's DGS10 read as a par bond
/// -- each session's return, the duration it was bought at, and its log return over DFF's accrual
/// across the same days -- the record by `bond10_readings` at the window's own sessions a year,
/// its one-year-block resamples by `bond10_resamples`, their own joint band over the three rows.
fn print_bond10_rows(o: &Opts, dated: &[(String, f64)], window: &str) {
    let Source::Yahoo(file) = &o.source else {
        usage("-bond10 reads a FRED DGSn file given as -yahoo")
    };
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    let head: Vec<&str> = text
        .lines()
        .next()
        .unwrap_or("")
        .split(',')
        .map(str::trim)
        .collect();
    let years: f64 = match head.as_slice() {
        ["observation_date", h] if h.starts_with("DGS") => h[3..]
            .parse()
            .unwrap_or_else(|_| usage(&format!("{file}: [{h}] is not a DGSn series"))),
        _ => usage(&format!("{file}: -bond10 reads a FRED DGSn file")),
    };
    if !o.splice.is_empty() {
        usage("-bond10 reads one DGSn file; a splice has no yields to take durations from");
    }
    let durs = treasury_durations(&text, years);
    let curve = cash_curve(
        o.cash
            .as_deref()
            .unwrap_or_else(|| usage("-bond10 wants a -fred DFF")),
    );
    let held: Vec<&(f64, String)> = dated
        .iter()
        .map(|(d, _)| {
            durs.get(d)
                .unwrap_or_else(|| usage(&format!("{file}: no duration for {d}")))
        })
        .collect();
    let r: Vec<f64> = dated.iter().map(|(_, x)| *x).collect();
    let dur: Vec<f64> = held.iter().map(|h| h.0).collect();
    let ex: Vec<f64> = dated
        .iter()
        .zip(&held)
        .map(|((d, x), h)| x - cash_accrual(&curve, &h.1, d))
        .collect();
    // sessions a year over the window, from the first return's purchase to the last return
    let span =
        (days_from_civil(&dated[dated.len() - 1].0) - days_from_civil(&held[0].1)) as f64 / 365.25;
    let per_year = dated.len() as f64 / span;
    let record = ms::bond10_readings(&r, &dur, &ex, per_year);
    eprintln!(
        "{}: {} returns {window}, bond10 vol per duration {:.4}, depth vs vol {:.4}, excess {:.4} \
         pts/yr at {per_year:.2} sessions a year; {} resamples, seed {}",
        o.series,
        r.len(),
        record[0],
        record[1],
        record[2],
        o.resamples,
        o.seed
    );
    let reads = ms::bond10_resamples(&r, &dur, &ex, per_year, o.resamples, o.seed, block_years());
    if o.header {
        let pcts: Vec<String> = ms::RECORD_BAND_PCTS
            .iter()
            .map(|p| format!("p{p}"))
            .collect();
        println!(
            "set\trow\tseries\twindow\tn\tresamples\trecord\t{}\tjointC\tjointLo\tjointHi",
            pcts.join("\t")
        );
    }
    let ks: Vec<usize> = vec![0, 1, 2];
    let of = if o.of == 0 { 3 } else { o.of };
    let alpha = o.joint * 3.0 / of as f64;
    let (c, edges) = ms::record_band_joint(&reads, &ks, alpha);
    for (k, name) in ms::BOND10_BAND_ROWS.iter().enumerate() {
        let col: Vec<f64> = reads.iter().map(|x| x[k]).collect();
        let qs = band_quantiles(record[k], &col);
        let (lo, hi) = edges[k];
        println!(
            "{}\t{}\t{}\t{window}\t{}\t{}\t{:.6}\t{}\t{c:.6}\t{lo:.6}\t{hi:.6}",
            o.set,
            name,
            o.series,
            r.len(),
            o.resamples,
            record[k],
            qs.join("\t")
        );
    }
}

/// The first monthly block of one of Ken French's published CSVs (`10_Industry_Portfolios.CSV`,
/// `F-F_Research_Data_Factors.CSV`, ...): the run of rows keyed `YYYYMM` that follows the first
/// column-name row, as `YYYY-MM` and the columns in fractions; `-99.99` and `-999`, the library's
/// missing marks, `None`.
fn read_french_monthly(file: &str) -> (Vec<String>, Vec<Vec<Option<f64>>>) {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    let lines: Vec<Vec<String>> = text
        .lines()
        .map(|l| l.split(',').map(|f| f.trim().to_string()).collect())
        .collect();
    let is_month =
        |f: &[String]| f.len() > 1 && f[0].len() == 6 && f[0].bytes().all(|b| b.is_ascii_digit());
    let start = lines
        .iter()
        .position(|f| is_month(f))
        .unwrap_or_else(|| usage(&format!("{file}: no monthly rows")));
    let cols = lines[start].len() - 1;
    let mut dates = Vec::new();
    let mut table: Vec<Vec<Option<f64>>> = vec![Vec::new(); cols];
    for f in lines[start..].iter().take_while(|f| is_month(f)) {
        if f.len() != cols + 1 {
            usage(&format!(
                "{file}: [{}] has {} fields, the block {}",
                f.join(","),
                f.len(),
                cols + 1
            ));
        }
        dates.push(format!("{}-{}", &f[0][0..4], &f[0][4..6]));
        for (k, cell) in f[1..].iter().enumerate() {
            let v: Option<f64> = cell.parse().ok();
            table[k].push(v.filter(|x| *x > -99.0).map(|x| x / 100.0));
        }
    }
    (dates, table)
}

/// The panel of one industry table beside the factors, aligned on the months both hold.
fn sector_panel(dir: &str, table: &str) -> (Vec<String>, ms::SectorPanel) {
    let (fd, fac) = read_french_monthly(&format!("{dir}/F-F_Research_Data_Factors.CSV"));
    let (id, ind) = read_french_monthly(&format!("{dir}/{table}"));
    let months: Vec<String> = id.iter().filter(|d| fd.contains(d)).cloned().collect();
    let pick = |dates: &[String], col: &[Option<f64>]| -> Vec<Option<f64>> {
        months
            .iter()
            .map(|m| {
                col[dates
                    .iter()
                    .position(|d| d == m)
                    .unwrap_or_else(|| usage("a month the factors hold is missing"))]
            })
            .collect()
    };
    let must = |v: Vec<Option<f64>>| -> Vec<f64> {
        v.into_iter()
            .map(|x| x.unwrap_or_else(|| usage("a blank in the factors file")))
            .collect()
    };
    let mkt_rf = must(pick(&fd, &fac[0]));
    let rf = must(pick(&fd, &fac[3]));
    let market: Vec<f64> = mkt_rf.iter().zip(&rf).map(|(a, b)| a + b).collect();
    let returns: Vec<Vec<Option<f64>>> = ind.iter().map(|c| pick(&id, c)).collect();
    (
        months,
        ms::SectorPanel {
            returns,
            market,
            rf,
        },
    )
}

/// THE SECTOR ROWS (`-sectors`): momentum (12-1 and 6-1, whole record and from 1963-07), the
/// per-sector trend (the 12-month sign and the 10-month SMA) and the cross-section shape, on the
/// 10- and 49-industry tables; one TSV row each, bands where a row has one.
fn print_sector_rows(dir: &str, resamples: usize, seed: u64) {
    println!("row\ttable\tform\twindow\tstatistic\tvalue\tlo\thi\tmonths");
    for (table, file, top) in [
        ("industries10", "10_Industry_Portfolios.CSV", 3usize),
        ("industries49", "49_Industry_Portfolios.CSV", 10usize),
    ] {
        let (months, p) = sector_panel(dir, file);
        let from63 = months
            .iter()
            .position(|d| d.as_str() >= "1963-07")
            .unwrap_or_else(|| usage("no month from 1963-07"));
        eprintln!(
            "{table}: {} industries, {} months {}..{}; {resamples} resamples, seed {seed}",
            p.returns.len(),
            months.len(),
            months[0],
            months[months.len() - 1]
        );
        for (label, form) in [("12-1", 11usize), ("6-1", 5usize)] {
            for (window, from) in [("all", 0usize), ("post-1963", from63)] {
                let m = ms::sector_momentum(&p, form, top, from);
                let (lo, hi) = ms::sector_momentum_band(&m.spreads, resamples, seed);
                let n = m.spreads.len();
                println!(
                    "momentum\t{table}\t{label}\t{window}\tmean spread\t{:.6}\t{lo:.6}\t{hi:.6}\t{n}",
                    m.mean
                );
                println!(
                    "momentum\t{table}\t{label}\t{window}\tt-stat\t{:.6}\t\t\t{n}",
                    m.t
                );
                println!(
                    "momentum\t{table}\t{label}\t{window}\tshare positive\t{:.6}\t\t\t{n}",
                    m.share_positive
                );
            }
        }
        for (label, mode) in [
            ("12m", ms::SectorTrend::Sign12),
            ("sma10", ms::SectorTrend::Sma10),
        ] {
            for (window, from) in [("all", 0usize), ("post-1963", from63)] {
                let (v, n) = ms::sector_trend(&p, mode, from);
                let (lo, hi) = ms::sector_trend_band(&p, mode, from, resamples, seed);
                println!(
                    "trend\t{table}\t{label}\t{window}\tpositive minus negative\t{v:.6}\t{lo:.6}\t{hi:.6}\t{n}"
                );
            }
        }
        let (s, n) = ms::sector_shape(&p);
        println!(
            "shape\t{table}\t\tall\tmean cross-sectional sd\t{:.6}\t\t\t{}",
            s[0], n[0]
        );
        println!(
            "shape\t{table}\t\tall\tmarket monthly sd\t{:.6}\t\t\t{}",
            ms::sector_market_sd(&p),
            p.market.len()
        );
        println!(
            "shape\t{table}\t\tall\tmedian pairwise correlation\t{:.6}\t\t\t{}",
            s[1], n[0]
        );
        println!(
            "shape\t{table}\t\tmarket worst decile\tmedian pairwise correlation\t{:.6}\t\t\t{}",
            s[2], n[1]
        );
        println!(
            "shape\t{table}\t\tmarket middle decile\tmedian pairwise correlation\t{:.6}\t\t\t{}",
            s[3], n[2]
        );
    }
}

/// `-rateafter`: the rate on the equity's session dates (a session without a rate is dropped),
/// then the conditional rate rows.
fn rate_after_mode(o: &Opts, rate_file: &str, dated: &[(String, f64)], window: &str) {
    let rates: std::collections::HashMap<String, f64> = read_fred(rate_file).into_iter().collect();
    let joined: Vec<(f64, f64)> = dated
        .iter()
        .filter_map(|(d, x)| rates.get(d).map(|v| (*x, *v)))
        .collect();
    let (r, rate): (Vec<f64>, Vec<f64>) = joined.into_iter().unzip();
    print_rate_after_rows(o, &r, &rate, window);
}

/// The single-history spreads the anchors' `post_rate_sd` and `post_floor_sd` start from: the sd
/// of the rate's log, the floor share's sd over the record (an additive row).
fn rate_after_spreads(reads: &[[f64; 2]], floor_record: f64) -> String {
    let sd = |xs: &[f64]| {
        let m = xs.iter().sum::<f64>() / xs.len() as f64;
        (xs.iter().map(|x| (x - m) * (x - m)).sum::<f64>() / xs.len() as f64).sqrt()
    };
    let logs: Vec<f64> = reads
        .iter()
        .map(|x| x[0].ln())
        .filter(|v| v.is_finite())
        .collect();
    let shares: Vec<f64> = reads
        .iter()
        .map(|x| x[1])
        .filter(|v| v.is_finite())
        .collect();
    format!(
        "sd log rate {:.3}, floor share sd over record {:.3}",
        sd(&logs),
        sd(&shares) / floor_record
    )
}

/// THE CONDITIONAL RATE ROWS (`-rateafter`) on a `-fred` rate beside a `-yahoo` or `-french`
/// equity window, joined on the equity's session dates: the record by `rate_after_of_returns`,
/// its paired block resamples by `rate_after_resamples`, a joint band over the two -- the rate
/// rows' columns.
fn print_rate_after_rows(o: &Opts, r: &[f64], rate: &[f64], window: &str) {
    let record = ms::rate_after_of_returns(r, rate);
    eprintln!(
        "{}: {} sessions {window} joined with the rate; post-trough mean {:.4}%, {:.2}% under the floor; {} resamples, seed {}",
        o.series,
        r.len(),
        record[0],
        record[1],
        o.resamples,
        o.seed
    );
    let reads = ms::rate_after_resamples(r, rate, o.resamples, o.seed, block_years());
    eprintln!("  spreads: {}", rate_after_spreads(&reads, record[1]));
    if o.header {
        let pcts: Vec<String> = ms::RECORD_BAND_PCTS
            .iter()
            .map(|p| format!("p{p}"))
            .collect();
        println!(
            "set\trow\tseries\twindow\tn\tresamples\trecord\t{}\tjointC\tjointLo\tjointHi",
            pcts.join("\t")
        );
    }
    let ks: Vec<usize> = (0..ms::RATE_AFTER_ROWS.len()).collect();
    let of = if o.of == 0 { ks.len() } else { o.of };
    let alpha = o.joint * ks.len() as f64 / of as f64;
    let (c, edges) = ms::record_band_joint(&reads, &ks, alpha);
    for (&k, (lo, hi)) in ks.iter().zip(&edges) {
        let name = ms::RATE_AFTER_ROWS[k];
        let col: Vec<f64> = reads.iter().map(|x| x[k]).collect();
        let qs = band_quantiles(record[k], &col);
        println!(
            "{}\t{name}\t{}\t{window}\t{}\t{}\t{:.6}\t{}\t{c:.6}\t{lo:.6}\t{hi:.6}",
            o.set,
            o.series,
            r.len(),
            o.resamples,
            record[k],
            qs.join("\t")
        );
    }
}

/// THE LAG-FREE RECORD in the readers: a session's corrected return is its source's, whatever
/// window reads it, and a chain's blocks are bounded by its splices alone.
/// `-block`: one or three years, nothing else; a level series resampled by its log changes and
/// compounded again, so a series of constant growth resamples to itself.
#[cfg(test)]
mod block_tests {
    use super::*;

    #[test]
    fn the_block_is_one_or_three_years() {
        let args = |v: &[&str]| -> Vec<String> { v.iter().map(|s| (*s).to_string()).collect() };
        assert_eq!(block_of(&args(&["-x", "1"])), None);
        assert_eq!(block_of(&args(&["-block", "252"])), Some(1));
        assert_eq!(block_of(&args(&["-block", "756"])), Some(3));
    }

    /// A run names its rows apart by `;`, since a row's name may hold `|`; its own header,
    /// resample count and block give way to `-scales`'.
    #[test]
    fn a_run_names_its_rows_apart_by_semicolons() {
        let runs = read_scale_runs(
            "# a comment\nsatellite |r| corr;satellite corr\t-satellite\t-resamples\t9\t-header\t\
             -block\t756\t-set\tsp500\n*\t-x\n",
        );
        assert_eq!(runs.len(), 2);
        assert_eq!(runs[0].0, ["satellite |r| corr", "satellite corr"]);
        assert_eq!(runs[0].1, ["-satellite", "-set", "sp500"]);
        assert!(runs[1].0.is_empty());
    }

    /// The record's vote on its sign is the share of resamples strictly on its side of the null
    /// less the share strictly on the other, floored at 0, a resample at the null voting for
    /// neither; the knee is the mean leave-one-out CRPS of the resamples in the row's scale, the
    /// one-pass form agreeing with the calls it stands for; neither without a scale, no vote
    /// without a null or with the record at it.
    #[test]
    fn the_vote_and_the_knee_read_the_records_own_resamples() {
        let xs = [-1.0, 0.0, 1.0, 2.0, 3.0];
        let (res, knee) = vote_and_knee(&xs, Some(0.0), 2.0, 1.0);
        assert!(
            (res.expect("voted") - 0.4).abs() < 1e-12,
            "3 of 5 above 0, 1 of 5 below: {res:?}"
        );
        // by hand: the five leave-one-out CRPS are 1.875, 0.9375, 0.625, 0.9375, 1.875
        assert!(
            (knee.expect("three or more") - 1.25).abs() < 1e-12,
            "{knee:?}"
        );
        let by_calls: f64 = (0..xs.len())
            .map(|i| {
                let others: Vec<f64> = xs
                    .iter()
                    .enumerate()
                    .filter(|(j, _)| *j != i)
                    .map(|(_, v)| *v)
                    .collect();
                ms::crps(&others, xs[i])
            })
            .sum::<f64>()
            / xs.len() as f64;
        assert!((knee.expect("three or more") - by_calls).abs() < 1e-12);
        let halved = vote_and_knee(&xs, Some(0.0), 2.0, 2.0).1;
        assert!(
            (halved.expect("three or more") - 0.625).abs() < 1e-12,
            "in the row's scale: {halved:?}"
        );
        assert_eq!(
            vote_and_knee(&xs, Some(0.0), -1.0, 1.0).0,
            Some(0.0),
            "the record below with most resamples above: floored at 0"
        );
        assert_eq!(
            vote_and_knee(&xs, Some(2.0), 2.0, 1.0).0,
            None,
            "the record at the null"
        );
        assert_eq!(vote_and_knee(&xs, None, 2.0, 1.0).0, None, "no null");
        assert_eq!(
            vote_and_knee(&xs, Some(0.0), 2.0, 0.0),
            (None, None),
            "no scale"
        );
        assert_eq!(
            vote_and_knee(&[1.0, 2.0], Some(0.0), 2.0, 1.0).1,
            None,
            "two resamples"
        );
        let mut skewed = vec![0.5f64; 97];
        skewed.extend([-3.0, 1.0, f64::NAN]);
        let vote = vote_and_knee(&skewed, Some(0.0), 0.5, 1.0).0;
        assert!(
            (vote.expect("voted") - 97.0 / 99.0).abs() < 1e-12,
            "the finite resamples vote: {vote:?}"
        );
    }

    /// A run's `raw` line carries the record and the resamples of the band row after it; a band
    /// row without one has neither.
    #[test]
    fn a_raw_line_belongs_to_the_band_row_after_it() {
        let pcts: Vec<String> = (0..ms::RECORD_BAND_PCTS.len())
            .map(|i| format!("{}", i as f64 * 0.1))
            .collect();
        let band = |set: &str, row: &str| {
            format!(
                "{set}\t{row}\tS\tW\t10\t5\t0.300000\t{}\t0.1\t0.0\t2.0",
                pcts.join("\t")
            )
        };
        let text = format!(
            "raw\t0.3\t-1.0\t0.0\t1.0\t2.0\t3.0\n{}\n{}\nsp500\tno band\n",
            band("sp500", "x"),
            band("nasdaq", "y")
        );
        let reads = read_scale_lines(&text);
        assert_eq!(reads.len(), 2);
        assert_eq!(
            (reads[0].world.as_str(), reads[0].row.as_str()),
            ("sp500", "x")
        );
        assert_eq!(reads[0].record, 0.3);
        assert_eq!(reads[0].resamples, [-1.0, 0.0, 1.0, 2.0, 3.0]);
        assert!((reads[0].scale - (2.0 - 0.2) / 3.29).abs() < 1e-12);
        assert!(reads[1].record.is_nan() && reads[1].resamples.is_empty());
        let rows = read_scale_rows("t", "row\tunits\tnull\nx\tvol\t0\ny\tshape\tnone\n");
        assert_eq!(
            rows,
            [("x".to_string(), Some(0.0)), ("y".to_string(), None)]
        );
    }

    #[test]
    fn a_level_series_of_constant_growth_resamples_to_itself() {
        let levels: Vec<f64> = (0..60).map(|k| 100.0 * 1.01f64.powi(k)).collect();
        let reads = level_resamples(&levels, 12, 1, (4, 7), |lv| [lv[lv.len() - 1], lv[0]]);
        assert_eq!(reads.len(), 4);
        for r in reads {
            assert!((r[0] / levels[59] - 1.0).abs() < 1e-12, "{}", r[0]);
            assert_eq!(r[1].to_bits(), levels[0].to_bits());
        }
    }

    /// Nine sessions over three weeks, a block of three: the calendar `-resampled` draws on.
    fn calendar() -> Vec<String> {
        [
            "2001-01-02",
            "2001-01-03",
            "2001-01-04",
            "2001-01-08",
            "2001-01-09",
            "2001-01-10",
            "2001-01-15",
            "2001-01-16",
            "2001-01-17",
        ]
        .iter()
        .map(|d| (*d).to_string())
        .collect()
    }

    /// Blocks drawn where they stand, and drawn elsewhere: block 0 takes sessions 6-8, block 1
    /// sessions 0-2, block 2 sessions 3-5.
    const SAME: [usize; 3] = [0, 3, 6];
    const MOVED: [usize; 3] = [6, 0, 3];

    /// French's factors: drawn where they stand, a copy is the file; drawn elsewhere, each target
    /// session takes its source session's cells after the date, the rows outside the calendar
    /// kept.
    #[test]
    fn a_french_copy_takes_each_sessions_cells_from_its_drawn_block() {
        let (cal, same, moved) = (calendar(), SAME, MOVED);
        let mut lines = vec!["head".to_string(), "20001229,0.5,0,0,0.01".to_string()];
        lines.extend(
            cal.iter()
                .enumerate()
                .map(|(i, d)| format!("{},{i}.0,1,2,0.01", d.replace('-', ""))),
        );
        lines.push("tail".to_string());
        let french = CopySource::French {
            lines: lines.clone(),
        };
        assert_eq!(copy_text(&french, &cal, &same, 3), lines.join("\n") + "\n");
        let text = copy_text(&french, &cal, &moved, 3);
        let rows: Vec<&str> = text.lines().collect();
        assert_eq!(
            rows[1], "20001229,0.5,0,0,0.01",
            "kept outside the calendar"
        );
        assert_eq!(rows[2], "20010102,6.0,1,2,0.01");
        assert_eq!(rows[5], "20010108,0.0,1,2,0.01");
        assert_eq!(rows[10], "20010117,5.0,1,2,0.01");
        assert_eq!(rows[11], "tail");
    }

    /// A level file: the closes compound the drawn sessions' returns from the level before the
    /// calendar, the bar kept relative to its close, the log column read back off the levels.
    #[test]
    fn a_level_copy_compounds_the_drawn_sessions_returns() {
        let (cal, same, moved) = (calendar(), SAME, MOVED);
        let head: Vec<String> = ["date", "open", "close", "adj_close", "dlog_adj_close"]
            .iter()
            .map(|h| (*h).to_string())
            .collect();
        let close: Vec<f64> = (0..10).map(|i| 100.0 * 1.1f64.powi(i * i % 7)).collect();
        let rows: Vec<(String, Vec<Option<f64>>)> = std::iter::once("2000-12-29".to_string())
            .chain(cal.iter().cloned())
            .enumerate()
            .map(|(i, d)| {
                let dl = (i > 0).then(|| ms::ln_det(close[i] / close[i - 1]));
                (
                    d,
                    vec![Some(0.9 * close[i]), Some(close[i]), Some(close[i]), dl],
                )
            })
            .collect();
        let levels = CopySource::Levels {
            head: head.clone(),
            rows: rows.clone(),
        };
        let parse = |text: &str| -> Vec<Vec<f64>> {
            text.lines()
                .skip(1)
                .map(|l| {
                    l.split(',')
                        .skip(1)
                        .map(|x| x.parse().unwrap_or(f64::NAN))
                        .collect()
                })
                .collect()
        };
        let same_rows = parse(&copy_text(&levels, &cal, &same, 3));
        for (got, (_, want)) in same_rows.iter().zip(&rows) {
            assert!((got[1] / want[1].unwrap_or(f64::NAN) - 1.0).abs() < 1e-12);
        }
        let got = parse(&copy_text(&levels, &cal, &moved, 3));
        assert_eq!(got.len(), 10);
        // target session 1 (2001-01-02) draws calendar session 6 (2001-01-15, row 7)
        assert!((got[1][1] / close[0] - close[7] / close[6]).abs() < 1e-12);
        assert!(
            (got[1][0] / got[1][1] - 0.9).abs() < 1e-12,
            "the open beside its close"
        );
        assert!((got[1][3] - (got[1][2] / got[0][2]).ln()).abs() < 1e-12);
        assert!(got[0][3].is_nan(), "the first row's log column stays empty");
    }

    /// FRED: observations moved as levels to the session their date falls in, offset by the days
    /// past it; one before the calendar kept.
    #[test]
    fn a_fred_copy_moves_each_observation_with_its_session() {
        let (cal, same, moved) = (calendar(), SAME, MOVED);
        let fred = CopySource::Fred {
            head: "observation_date,X".to_string(),
            rows: [
                ("2000-12-01", "7"),
                ("2001-01-02", "a"),
                ("2001-01-06", "b"),
                ("2001-01-15", "c"),
            ]
            .iter()
            .map(|(d, v)| ((*d).to_string(), (*v).to_string()))
            .collect(),
        };
        let text = copy_text(&fred, &cal, &same, 3);
        assert_eq!(
            text,
            "observation_date,X\n2000-12-01,7\n2001-01-02,a\n2001-01-06,b\n2001-01-15,c\n"
        );
        // a falls in session 0, b in session 2 (2001-01-04) two days on, c in session 6: block 0
        // draws sessions 6-8 (c to 2001-01-02), block 1 sessions 0-2 (a to session 3,
        // 2001-01-08; b to session 5, 2001-01-10, two days on), block 2 sessions 3-5 (none)
        let text = copy_text(&fred, &cal, &moved, 3);
        assert_eq!(
            text,
            "observation_date,X\n2000-12-01,7\n2001-01-02,c\n2001-01-08,a\n2001-01-12,b\n"
        );
    }

    /// Past the calendar a level file continues the chain on its own returns, no jump at the
    /// edge; a drawn session's volume is its ratio to its own trailing median at the target
    /// date's median, and a session after the calendar keeps its own.
    #[test]
    fn a_level_copy_continues_past_the_calendar_and_carries_volume_about_its_trend() {
        let cal = calendar();
        let dates: Vec<String> = std::iter::once("2000-12-29".to_string())
            .chain(cal.iter().cloned())
            .chain(["2001-01-18".to_string(), "2001-01-19".to_string()])
            .collect();
        let close: Vec<f64> = (0..12).map(|i| 50.0 + f64::from(i * i % 5)).collect();
        let rows: Vec<(String, Vec<Option<f64>>)> = dates
            .iter()
            .enumerate()
            .map(|(i, d)| {
                (
                    d.clone(),
                    vec![
                        Some(close[i]),
                        Some((1000.0 * 1.5f64.powi(i as i32)).round()),
                    ],
                )
            })
            .collect();
        let head: Vec<String> = ["date", "close", "volume"]
            .iter()
            .map(|h| (*h).to_string())
            .collect();
        let text = copy_text(
            &CopySource::Levels {
                head: head.clone(),
                rows: rows.clone(),
            },
            &cal,
            &MOVED,
            3,
        );
        let got: Vec<Vec<f64>> = text
            .lines()
            .skip(1)
            .map(|l| {
                l.split(',')
                    .skip(1)
                    .map(|x| x.parse().unwrap_or(f64::NAN))
                    .collect()
            })
            .collect();
        assert_eq!(got.len(), 12);
        let ratio = |v: &[Vec<f64>], i: usize| v[i][0] / v[i - 1][0];
        for i in [10, 11] {
            assert!(
                (ratio(&got, i) - close[i] / close[i - 1]).abs() < 1e-12,
                "row {i}"
            );
            assert_eq!(
                got[i][1],
                rows[i].1[1].unwrap_or(f64::NAN),
                "its own volume"
            );
        }
        // target session 0 (row 1) draws calendar session 6, row 7
        let tr = volume_trend(&rows, 1);
        let want = (tr[1].unwrap_or(f64::NAN) + ms::ln_det(rows[7].1[1].unwrap_or(f64::NAN))
            - tr[7].unwrap_or(f64::NAN))
        .exp()
        .round();
        assert!(
            (got[1][1] - want).abs() <= 1.0,
            "{} against {want}",
            got[1][1]
        );
    }

    #[test]
    fn a_day_count_reads_back_to_its_date() {
        for d in ["1926-07-01", "1970-01-01", "2000-02-29", "2026-12-31"] {
            assert_eq!(civil_date(civil_days(d)), d);
        }
    }
}

#[cfg(test)]
mod lag_free_tests {
    use uni::NumPyRng;

    use super::*;

    fn temp(name: &str, text: &str) -> String {
        let dir = std::env::temp_dir().join(format!("record-bands-lag-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("temp dir");
        let f = dir.join(name);
        std::fs::write(&f, text).expect("write");
        f.to_str().expect("path").to_string()
    }

    /// `(YYYYMMDD, percent)` sessions, 28 a month, smoothed with a one-day lag of 0.3
    fn lagged_days(y0: i64, years: i64, seed: u64) -> Vec<(String, f64)> {
        let mut rng = NumPyRng::new(seed);
        let mut last = 0.0;
        (y0..y0 + years)
            .flat_map(|y| {
                (1..=12).flat_map(move |m| (1..=28).map(move |d| format!("{y}{m:02}{d:02}")))
            })
            .map(|d| {
                last = 0.7 * rng.randn() + 0.3 * last;
                (d, (last * 100.0_f64).round() / 100.0)
            })
            .collect()
    }

    fn iso(d8: &str) -> String {
        format!("{}-{}-{}", &d8[0..4], &d8[4..6], &d8[6..8])
    }

    #[test]
    fn a_session_reads_the_same_lag_free_return_from_any_window() {
        let days = lagged_days(1950, 10, 5);
        let mut text = String::from("French factor file\n,Mkt-RF,SMB,HML,RF\n");
        for (d, x) in &days {
            text.push_str(&format!("{d},{x:.2},0.00,0.00,0.01\n"));
        }
        let f = temp("ff.csv", &text);
        let read = |from: &str| {
            let a: Vec<String> = [
                "-french",
                &f,
                "-from",
                from,
                "-to",
                "1959-12-31",
                "-set",
                "x",
                "-series",
                "y",
            ]
            .iter()
            .map(|s| (*s).to_string())
            .collect();
            returns_in_window(&parse_args(&a))
        };
        let (century, late) = (read("1950-01-01"), read("1954-07-01"));
        let early: std::collections::HashMap<&str, f64> =
            century.iter().map(|(d, x)| (d.as_str(), *x)).collect();
        for (d, x) in &late {
            assert!((early[d.as_str()] - x).abs() < 1e-12, "{d}");
        }
        // the window's own blocks would read another phi for 1951-55's late sessions
        let logs: Vec<(String, f64)> = days
            .iter()
            .filter(|(d, _)| iso(d).as_str() >= "1954-07-01")
            .map(|(d, x)| (iso(d), ms::ln_det(1.0 + (x + 0.01) / 100.0)))
            .collect();
        let ds: Vec<&str> = logs.iter().map(|(d, _)| d.as_str()).collect();
        let r: Vec<f64> = logs.iter().map(|(_, x)| *x).collect();
        let windowed = ms::unlag_returns(&ds, &r);
        let k = ds
            .iter()
            .position(|d| *d == "1955-06-14")
            .expect("a 1955 session");
        let got = late
            .iter()
            .find(|(d, _)| d == "1955-06-14")
            .expect("read")
            .1;
        assert!(
            (got - windowed[k]).abs() > 1e-9,
            "the block is the source's, not the window's"
        );
    }

    #[test]
    fn a_chains_blocks_end_at_its_splice() {
        let industry = |days: &[(String, f64)]| {
            let mut s = String::from(
                "This file was created using the 202608 CRSP database. 10 Industry Portfolios\n\n  Average Value Weighted Returns -- Daily\n,NoDur,HiTec\n",
            );
            for (d, x) in days {
                s.push_str(&format!("{d},0.00,{x:.2}\n"));
            }
            s
        };
        let (a_days, b_days) = (lagged_days(1990, 9, 7), lagged_days(1990, 9, 9));
        let a = temp("a.csv", &industry(&a_days));
        let b = temp("b.csv", &industry(&b_days));
        let args: Vec<String> = [
            "-yahoo",
            &a,
            "-column",
            "HiTec",
            "-splice",
            &b,
            "-at",
            "1995-06-30",
            "-from",
            "1990-01-01",
            "-to",
            "1998-12-31",
            "-set",
            "x",
            "-series",
            "y",
        ]
        .iter()
        .map(|s| (*s).to_string())
        .collect();
        let got = returns_in_window(&parse_args(&args));
        let taken =
            |days: &[(String, f64)], keep: &dyn Fn(&str) -> bool| -> (Vec<String>, Vec<f64>) {
                days.iter()
                    .map(|(d, x)| (iso(d), ms::ln_det(1.0 + x / 100.0)))
                    .filter(|(d, _)| keep(d))
                    .unzip()
            };
        let (da, ra) = taken(&a_days, &|d| d <= "1995-06-30");
        let (db, rb) = taken(&b_days, &|d| d > "1995-06-30");
        let fixed = |d: &[String], r: &[f64]| {
            ms::unlag_returns(&d.iter().map(String::as_str).collect::<Vec<&str>>(), r)
        };
        let want: Vec<f64> = fixed(&da, &ra).into_iter().chain(fixed(&db, &rb)).collect();
        // read_industry's first session is the file's first return; `got` drops none
        assert_eq!(got.len(), want.len());
        assert!(
            got.iter()
                .zip(&want)
                .all(|((_, x), y)| x.to_bits() == y.to_bits())
        );
        // the splice file read whole would put its first half of 1995 into its 1991-95 block
        let (dall, rall) = taken(&b_days, &|_| true);
        let whole = fixed(&dall, &rall);
        let k = dall
            .iter()
            .position(|d| d == "1995-08-14")
            .expect("a session");
        let j = db
            .iter()
            .position(|d| d == "1995-08-14")
            .expect("a session");
        assert!((whole[k] - want[da.len() + j]).abs() > 1e-9);
    }
}

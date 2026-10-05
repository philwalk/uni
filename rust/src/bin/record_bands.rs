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
       record_bands -basket -closes WIDE.csv -closesdate YYYY-MM-DD [-from D] [-to D]
                    [-minsessions N] [-out RULER.tsv]

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
  -basket       THE BASKET RULER for `-basketruler` (`basket_ruler_tsv`): -closes WIDE.csv is one row a
                session, `date`, the index's column named by its ticker, then one column per name,
                a name's cell empty before its listing (adjusted closes, dividends reinvested);
                -closesdate is the day they were taken, since an adjusted series is recomputed on
                every later distribution. -from / -to bound the window (default the file's);
                -minsessions (default 252) is the sessions a name needs to enter the per-name and
                pair ranges. Writes -out, or prints
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
                `multiyear-2026-09-29.tsv`; with -long under their long-window names";

fn usage(msg: &str) -> ! {
    if !msg.is_empty() {
        eprintln!("{msg}");
    }
    eprintln!("{USAGE}");
    std::process::exit(2)
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
    multiyear: bool,
    long: bool,
    rate: bool,
    bond: bool,
    /// `-rateafter`: the rate file read beside the equity source
    after_rate: Option<String>,
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
    let (mut multiyear, mut long) = (false, false);
    let mut rate = false;
    let mut bond = false;
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
            "-multiyear" => multiyear = true,
            "-long" => long = true,
            "-rate" => rate = true,
            "-bond" => bond = true,
            "-rateafter" => rateafter = true,
            other => usage(&format!("unrecognized arg [{other}]")),
        }
    }
    // `-rateafter` reads a rate beside an equity source; every other mode exactly one source
    let after_rate = rateafter.then(|| {
        fred.take()
            .unwrap_or_else(|| usage("-rateafter wants a -fred rate"))
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
        multiyear,
        long,
        rate,
        bond,
        after_rate,
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

/// `(date, log return)` of a daily series in whichever of the three layouts the file is in: a
/// `-yahoo` CSV (`read_yahoo`), FRED's `observation_date,DGSn` yields (`read_treasury`) or Ken
/// French's daily industry file (`read_industry`, the `-column` named).
fn read_daily(file: &str, column: Option<&str>) -> Vec<(String, f64)> {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    let head: Vec<&str> = text
        .lines()
        .next()
        .unwrap_or("")
        .split(',')
        .map(str::trim)
        .collect();
    if head.contains(&"dlog_adj_close") {
        return read_yahoo(file);
    }
    if head.len() == 2 && head[0] == "observation_date" && head[1].starts_with("DGS") {
        let years: f64 = head[1][3..]
            .parse()
            .unwrap_or_else(|_| usage(&format!("{file}: [{}] is not a DGSn series", head[1])));
        return read_treasury(&text, years);
    }
    if text[..text.len().min(400)]
        .to_ascii_lowercase()
        .contains("industry portfolios")
    {
        let col = column
            .unwrap_or_else(|| usage(&format!("{file}: an industry file needs -column NAME")));
        return read_industry(file, &text, col);
    }
    usage(&format!(
        "{file}: not a dlog_adj_close CSV, a FRED DGSn series or a French industry file"
    ))
}

/// A daily series continued by each `-splice FILE -at DATE` in turn: the returns so far dated on
/// or before the date, then that file's dated after it.
fn read_daily_chain(
    file: &str,
    splices: &[(String, String)],
    column: Option<&str>,
) -> Vec<(String, f64)> {
    splices
        .iter()
        .fold(read_daily(file, column), |sofar, (next, at)| {
            sofar
                .into_iter()
                .filter(|(d, _)| d.as_str() <= at.as_str())
                .chain(
                    read_daily(next, column)
                        .into_iter()
                        .filter(|(d, _)| d.as_str() > at.as_str()),
                )
                .collect()
        })
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
    if y > 0.0 {
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
    let mut lines = text
        .lines()
        .skip_while(|l| !l.contains("Value Weighted Returns -- Daily"));
    lines.next();
    let header: Vec<&str> = lines
        .next()
        .unwrap_or_else(|| usage(&format!("{file}: no daily value-weighted block")))
        .split(',')
        .map(str::trim)
        .collect();
    let col = header.iter().position(|h| *h == column).unwrap_or_else(|| {
        usage(&format!(
            "{file}: no column [{column}]; the file has {}",
            header[1..].join(" ")
        ))
    });
    lines
        .map(|l| l.split(',').map(str::trim).collect::<Vec<&str>>())
        .take_while(|f| f[0].len() == 8 && f[0].bytes().all(|b| b.is_ascii_digit()))
        .filter_map(|f| {
            let v: f64 = f.get(col)?.parse().ok()?;
            (v > -99.0).then(|| {
                let d = f[0];
                (
                    format!("{}-{}-{}", &d[0..4], &d[4..6], &d[6..8]),
                    ms::ln_det(1.0 + v / 100.0),
                )
            })
        })
        .collect()
}

/// `(date, Mkt-RF + RF in percent)` for every dated row of Ken French's daily factor file.
fn read_french(file: &str) -> Vec<(String, f64)> {
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

/// THE VOLATILITY EXIT's rows (`-volexit`): the record's sessions over `-from`..`-to`, each the
/// printed close's and the total return's simple returns, the `-fred` rate at the previous
/// session's date (the last one known on or before it), and the calendar days since that session;
/// the record and its joint band over the two rows at `-joint` x 2 / `-of`.
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
    let of: usize = opt("-of").map_or(2, |v| num(&v, "-of wants a row count"));
    // (date, printed close level, total-return level)
    let levels: Vec<(String, f64, f64)> = match (opt("-closes"), opt("-french")) {
        (Some(f), None) => read_closes(&f),
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
    let reads = ms::vol_exit_resamples(&days, resamples, seed);
    eprintln!(
        "{series}: {} sessions {window}; timing {:.4}, interaction {:.4}; {resamples} resamples, seed {seed}",
        days.len(),
        record[0],
        record[1]
    );
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
    let ks = [0usize, 1];
    let (c, edges) = ms::record_band_joint(&reads, &ks, joint * ks.len() as f64 / of as f64);
    for (&k, (lo, hi)) in ks.iter().zip(&edges) {
        let col: Vec<f64> = reads.iter().map(|x| x[k]).collect();
        let qs: Vec<String> = ms::record_band_quantiles(&col)
            .iter()
            .map(|v| format!("{v:.6}"))
            .collect();
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
    print_sector_rows(&dir, resamples, num("-seed", 20_260_918));
    true
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    // a mode that reads its own arguments runs and is done
    if basket_mode(&args) || sector_mode(&args) || timing_mode(&args) || volexit_mode(&args) {
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
        if o.header {
            println!("set\trow\tseries\twindow\tn\trecord");
        }
        for (name, value) in [
            ("bubble coupling 3y", ms::bubble_coupling_of(&r)),
            ("largest 3y run-up", ms::run_up_3y_of(&r)),
            ("longest calm stretch", ms::calm_stretch_of(&r)),
            ("variance ratio 250d", ms::variance_ratio(&r, 250)),
        ] {
            println!(
                "{}\t{name}\t{}\t{window}\t{}\t{value:.6}",
                o.set,
                o.series,
                r.len()
            );
        }
        return;
    }
    if o.multiyear {
        print_multi_year_rows(&o, &r, &window);
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
    let reads = ms::record_resamples(&r, o.resamples, o.seed);

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
    for (&k, (lo, hi)) in ks.iter().zip(&edges) {
        let name = ms::RECORD_BAND_ROWS[k];
        let col: Vec<f64> = reads.iter().map(|x| x[k]).collect();
        let qs: Vec<String> = ms::record_band_quantiles(&col)
            .iter()
            .map(|v| format!("{v:.6}"))
            .collect();
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
fn print_multi_year_rows(o: &Opts, r: &[f64], window: &str) {
    if o.header {
        println!("set\trow\tseries\twindow\tn\trecord");
    }
    let names = if o.long {
        ms::MULTI_YEAR_LONG_ROWS
    } else {
        ms::MULTI_YEAR_ROWS
    };
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
    let reads = ms::rate_resamples(rate, o.resamples, o.seed);
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
        let qs: Vec<String> = ms::record_band_quantiles(&col)
            .iter()
            .map(|v| format!("{v:.6}"))
            .collect();
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
    let reads = ms::bond_resamples(r, o.resamples, o.seed);
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
    let qs: Vec<String> = ms::record_band_quantiles(&col)
        .iter()
        .map(|v| format!("{v:.6}"))
        .collect();
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
    let reads = ms::rate_after_resamples(r, rate, o.resamples, o.seed);
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
        let qs: Vec<String> = ms::record_band_quantiles(&col)
            .iter()
            .map(|v| format!("{v:.6}"))
            .collect();
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

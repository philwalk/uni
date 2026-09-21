//! THE RECORD BANDS: one real record's own sampling spread on every fidelity row a single daily
//! series can be read the model's way. The generator behind
//! `test-data/equity-anchors/recordbands-2026-09-18.tsv`, and the Rust twin of
//! `jsrc/recordBands.sc`.
//!
//! ```text
//!   cargo run --release --bin record_bands -- -header -set nasdaq -series QQQ \
//!       -yahoo ../../folio/data/yahoo/QQQ/prices.csv -from 1999-03-10 -to 2026-08-20
//!   cargo run --release --bin record_bands -- -set sp500 -series CRSP \
//!       -french ../../folio/tmp/french/F-F_Research_Data_Factors_daily.csv \
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
    "usage: record_bands (-yahoo FILE | -french FILE) -from YYYY-MM-DD -to YYYY-MM-DD
                    -set NAME -series LABEL [-rows A,B] [-resamples N] [-seed S]
                    [-joint A] [-of N] [-header]

  -yahoo FILE   folio's cached prices: the `dlog_adj_close` column; the first row is the anchor
                price, not a return, and is skipped
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
  -header       print the column header first";

fn usage(msg: &str) -> ! {
    if !msg.is_empty() {
        eprintln!("{msg}");
    }
    eprintln!("{USAGE}");
    std::process::exit(2)
}

/// Where the record comes from: one of the two file layouts the fixture is built from.
enum Source {
    Yahoo(String),
    French(String),
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
}

fn parse_args(args: &[String]) -> Opts {
    let (mut yahoo, mut french, mut from, mut to) = (None, None, None, None);
    let (mut set, mut series, mut rows) = (String::new(), String::new(), Vec::new());
    let (mut resamples, mut seed, mut header) = (20_000usize, 20_260_918u64, false);
    let (mut joint, mut of) = (0.10f64, 0usize);
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
            "-from" => from = Some(next()),
            "-to" => to = Some(next()),
            "-set" => set = next(),
            "-series" => series = next(),
            "-rows" => rows = next().split(',').map(|s| s.trim().to_string()).collect(),
            "-resamples" => {
                resamples = next()
                    .parse()
                    .unwrap_or_else(|_| usage("-resamples wants an integer"));
            }
            "-seed" => {
                seed = next()
                    .parse()
                    .unwrap_or_else(|_| usage("-seed wants a non-negative integer"));
            }
            "-joint" => {
                joint = next()
                    .parse()
                    .unwrap_or_else(|_| usage("-joint wants a share in (0, 1)"));
            }
            "-of" => {
                of = next()
                    .parse()
                    .unwrap_or_else(|_| usage("-of wants a row count"));
            }
            "-header" => header = true,
            other => usage(&format!("unrecognized arg [{other}]")),
        }
    }
    let source = match (yahoo, french) {
        (Some(f), None) => Source::Yahoo(f),
        (None, Some(f)) => Source::French(f),
        _ => usage("give exactly one of -yahoo and -french"),
    };
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
    }
}

/// `(date, log return)` for every session of folio's cached Yahoo file after its first.
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

/// The window's daily log returns, each with the date it ended on.
fn returns_in_window(o: &Opts) -> Vec<(String, f64)> {
    let in_window = |d: &str| d >= o.from.as_str() && d <= o.to.as_str();
    match &o.source {
        Source::Yahoo(f) => read_yahoo(f)
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

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
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

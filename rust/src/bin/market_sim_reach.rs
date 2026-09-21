//! THE REACHABILITY CHECK for the market simulator: can one small step of the searched dials move
//! the rows a world misses toward their bands without pushing any other row out of its band? The
//! Rust twin of `jsrc/marketSimReach.sc`.
//!
//! ```text
//!   cargo build --release --bin market_sim_reach
//!   ./target/release/market_sim_reach -at 0.24.4-nasdaq -gap "kurtosis,up-day share %"
//! ```
//!
//! THE DIAL RESPONSE is read on SHARED SEEDS. Each dial is stepped one search sigma (`-sigma` of
//! its width, in the search's coordinates: `ln(1 + x)` for the rate dials) and read on the same
//! seeds as the base, so the difference cancels the paths' own noise. Measured on 0.24.4-nasdaq at
//! 60 x 80, a one-dial step's same-seed variance is 0.05-0.23 of the cross-seed one; newsRate's is
//! 0.97, because moving it moves the paths themselves. Each response carries its standard error
//! over the seeds, and dials whose responses are noise read as noise.
//!
//! THE LINEAR PROGRAM maximises the share `t` of every gap row's distance to its band that one
//! step closes: `s` sigmas per dial, `|s| <= -trust`, every other row held inside its band, or no
//! further out if it already misses. `-robust k` takes each response at its k-standard-error worst
//! case, so a step is claimed only where the readings support it. `t = 1` closes every gap in the
//! linear reading. Below 1, the constraints that bind are printed with their dual weights: trust
//! bounds alone mean a longer step (re-run from the new point); a row band that binds names the
//! rows no dial moves together with the gap rows -- the specification a new mechanism must meet.
//! A step with `t > 0` is read on fresh seeds, since the linear reading is local.
//!
//! WHAT A STEP MUST ALSO MEET is what adopting it would: every gate that is a band on one reading
//! (`gate_bands_at`) is held like a row's band, and with `-noregress R` every row is held no
//! further from its record than recipe R, read on the same seeds, plus the release judge's
//! tolerance. Without them a step can close every gap by leaving a level gate or giving back what
//! the outgoing recipe had. The gates no band describes are read in the check.
//!
//! The simplex uses Bland's rule and a fixed operation order, so the twins agree to the bit on
//! byte-identical readings. NOT SHIPPED: `Cargo.toml` excludes this file from the published crate.

#![allow(
    clippy::print_stdout,
    clippy::print_stderr,
    reason = "a console tool: the report is its product"
)]
#![allow(
    clippy::let_underscore_must_use,
    reason = "std::fmt::Write into a String cannot fail; the discard is the idiom"
)]

use std::fmt::Write as _;

use uni::market_sim::World;
use uni::market_sim::{self as ms};
use uni::udata::java_format_f;

// the binary's allocator: see the `fast-alloc` feature
#[cfg(feature = "fast-alloc")]
#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

// THE REPORT'S NUMBERS ARE JAVA'S: the Scala twin's `f` interpolator rounds a double's shortest
// decimal form half-up where Rust's `{:.N}` rounds its exact binary value, and a band edge of
// 0.946650 printed 0.9466 here against 0.9467 there.

/// `%<width>.<dec>f` (width 0: none)
fn jf(v: f64, width: i32, dec: i32) -> String {
    java_format_f(v, width, dec)
}

/// `%+.<dec>f`
fn jp(v: f64, dec: i32) -> String {
    let s = java_format_f(v, 0, dec);
    if s.starts_with('-') || s == "NaN" {
        s
    } else {
        format!("+{s}")
    }
}

/// `%-<width>.<dec>f`
fn jl(v: f64, width: usize, dec: i32) -> String {
    format!("{:<width$}", java_format_f(v, 0, dec))
}

type Range = (&'static str, f64, f64, ms::Setter, ms::Getter);

/// One graded row as the verdict reads it: name, reading, and the interval `miss` admits.
type Row = (&'static str, f64, (f64, f64));

/// A dial's value in the search's coordinates (`ms::SEARCH_LOG_DIALS`), so a response is read on
/// the step a search child would take.
fn coord(r: &Range, x: f64) -> f64 {
    ms::search_coord(r.0, x)
}

fn from_coord(r: &Range, y: f64) -> f64 {
    let y = y.max(coord(r, r.1)).min(coord(r, r.2));
    ms::from_search_coord(r.0, y).max(r.1).min(r.2)
}

/// One step of a dial, in its search coordinates.
fn step_of(r: &Range, sigma: f64) -> f64 {
    sigma * (coord(r, r.2) - coord(r, r.1))
}

/// A world with its searched dials set, the news size pulled inside the search's share of the
/// diffusion budget as the search's `admissible` pulls it.
fn with_dials(base: &World, rs: &[Range], d: &[f64]) -> World {
    let rate = rs
        .iter()
        .zip(d)
        .find(|(r, _)| r.0 == "newsRate")
        .map_or(0.0, |(_, x)| *x);
    let mut w = *base;
    for (r, x) in rs.iter().zip(d) {
        let v = if r.0 == "newsSize" {
            ms::news_size_within_budget(rate, *x)
        } else {
            *x
        };
        (r.3)(&mut w, v);
    }
    w
}

// ---- the readings -------------------------------------------------------------------------

/// A row's no-regression limit (`-noregress`): the readings in which it stays no further from its
/// record than the outgoing recipe, read on the same seeds, plus the judge's tolerance.
#[derive(Clone, Copy)]
struct Limit {
    row: &'static str,
    label: &'static str,
    /// the recipe's mean distance from the record
    dist: f64,
    iv: (f64, f64),
}

/// One read, before it is aligned to the layout: every named reading with its interval, how many
/// of them lead as fidelity rows, and the gates the world fails, in every class.
struct Reading {
    rows: Vec<(String, f64, (f64, f64))>,
    n_fid: usize,
    failed: Vec<String>,
}

/// Every graded row with an interval, as the verdict reads it; then each `-noregress` limit on its
/// row's reading; then, unless `-nogates`, every gate that is a band on one reading.
fn read(w: &World, a: ms::Anchors, o: &Opts, limits: &[Limit], seed: u64) -> Reading {
    let sims = ms::sim_paths(w, o.paths, o.years, seed);
    let st = ms::measure(&sims, o.years);
    let fid = ms::fidelity_rows(a, &st, Some(&sims), o.years, o.paths, seed, w);
    let banded = ms::banded_of(&fid);
    let mut rows: Vec<(String, f64, (f64, f64))> = fid
        .iter()
        .filter_map(|r| r.interval().map(|iv| (r.name.to_string(), r.model, iv)))
        .collect();
    let n_fid = rows.len();
    for l in limits {
        let x = fid
            .iter()
            .find(|r| r.name == l.row)
            .map_or(f64::NAN, |r| r.model);
        rows.push((l.label.to_string(), x, l.iv));
    }
    if o.gates {
        for g in ms::gate_bands_at(a, &st, &banded) {
            rows.push((format!("gate {}", g.name), g.reading, g.band));
        }
    }
    let failed = ms::gate_checks_at(a, &st, &banded)
        .into_iter()
        .filter(|g| !g.1)
        .map(|g| g.0)
        .collect();
    Reading {
        rows,
        n_fid,
        failed,
    }
}

/// The reading at percentile `p` of a record band's resamples: `percentile_exact` inverted, linear
/// between the carried percentiles and continued past either end along the same segment it is.
fn reading_at(b: &ms::RecordBand, p: f64) -> f64 {
    let q = &b.q;
    let pct = |k: usize| ms::RECORD_BAND_PCTS[k] as f64;
    let last = q.len() - 1;
    if p >= 100.0 {
        match (0..last).rev().find(|&j| q[j] < q[last]) {
            Some(j) => q[last] + (p - 100.0) * (q[last] - q[j]) / (100.0 - pct(j)),
            None => q[last],
        }
    } else if p <= 0.0 {
        match (1..=last).find(|&j| q[j] > q[0]) {
            Some(j) => q[0] + p * (q[j] - q[0]) / pct(j),
            None => q[0],
        }
    } else {
        let mut i = 0;
        while pct(i + 1) < p {
            i += 1;
        }
        q[i] + (q[i + 1] - q[i]) * (p - pct(i)) / (pct(i + 1) - pct(i))
    }
}

/// THE NO-REGRESSION LIMITS (`-noregress R`): recipe R read on the shared seeds, each row's distance
/// from its record as the release judge measures it -- |percentile - 50| among the record's
/// resamples on a banded row (`percentile_exact`, the judge's printed percentile unrounded), and
/// |ln(model / record)| on any other -- averaged over the seeds, plus the judge's tolerance
/// (`-tol`), turned into the readings that keep a step no further from the record. The limit
/// holds the step's MEAN reading, where the judge averages each seed's distance, so it is the
/// slightly looser of the two. A row the recipe reads with the record's opposite sign, or cannot
/// read, gets none: nothing is further than that.
fn limits_of(o: &Opts, a: ms::Anchors, seeds: &[u64]) -> Vec<Limit> {
    let (wr, _) = ms::named_world(&o.noregress).unwrap_or_else(|| {
        usage(&format!(
            "-noregress names [{}], which is not a release or recipe",
            o.noregress
        ))
    });
    let reads: Vec<Vec<ms::FidelityRow>> = seeds
        .iter()
        .map(|&s| {
            let sims = ms::sim_paths(&wr, o.paths, o.years, s);
            let st = ms::measure(&sims, o.years);
            ms::fidelity_rows(a, &st, Some(&sims), o.years, o.paths, s, &wr)
        })
        .collect();
    let mut out = Vec::new();
    for (r, row) in reads[0].iter().enumerate() {
        if row.interval().is_none() {
            continue;
        }
        let band = a.record_bands.iter().find(|b| b.name == row.name);
        let dists: Vec<f64> = reads
            .iter()
            .map(|rd| {
                let x = rd[r].model;
                match band {
                    Some(b) => (b.percentile_exact(x) - 50.0).abs(),
                    None => {
                        let q = x / row.real;
                        if q > 0.0 && q.is_finite() {
                            ms::ln_det(q).abs()
                        } else {
                            f64::INFINITY
                        }
                    }
                }
            })
            .collect();
        let d = mean(&dists);
        if !d.is_finite() {
            continue;
        }
        let iv = match band {
            Some(b) => {
                let dd = d + o.tol.0;
                (reading_at(b, 50.0 - dd), reading_at(b, 50.0 + dd))
            }
            None => {
                let dd = d + o.tol.1;
                let (x1, x2) = (row.real * ms::exp_det(-dd), row.real * ms::exp_det(dd));
                (x1.min(x2), x1.max(x2))
            }
        };
        let label: &'static str =
            Box::leak(format!("{} vs {}", row.name, o.noregress).into_boxed_str());
        out.push(Limit {
            row: row.name,
            label,
            dist: d,
            iv,
        });
    }
    out
}

/// A left-to-right sum from +0.0, written out: `Iterator::sum` for floats starts from -0.0, and
/// the Scala twin's from +0.0, so a column of negative zeros would print with different signs.
fn total(xs: impl Iterator<Item = f64>) -> f64 {
    let mut s = 0.0;
    for x in xs {
        s += x;
    }
    s
}

fn mean(xs: &[f64]) -> f64 {
    total(xs.iter().copied()) / xs.len() as f64
}

/// The standard error of the mean, from the sample sd; 0 for one reading.
fn std_err(xs: &[f64]) -> f64 {
    if xs.len() < 2 {
        return 0.0;
    }
    let m = mean(xs);
    let ss = total(xs.iter().map(|x| (x - m) * (x - m)));
    (ss / (xs.len() - 1) as f64).sqrt() / (xs.len() as f64).sqrt()
}

/// One gap row's need: the direction it must move (+1 up, -1 down) and how far to its band.
/// A gap row's target: its band with `-margin` taken off each side, the room a noisy reading needs.
fn inset((lo, hi): (f64, f64), m: f64) -> (f64, f64) {
    (lo + m, hi - m)
}

fn need(x: f64, (lo, hi): (f64, f64)) -> Option<(f64, f64)> {
    if x > hi {
        Some((-1.0, x - hi))
    } else if x < lo {
        Some((1.0, lo - x))
    } else {
        None
    }
}

// ---- the linear program -------------------------------------------------------------------

const EPS: f64 = 1e-12;

/// The objective's price of one step on one dial: a tie-break toward the smallest step, without
/// which the program steps dials that move nothing (a zero response costs nothing to take).
/// Small enough that the share closed moves by at most 1e-4 of a gap.
const STEP_COST: f64 = 1e-6;

/// Maximise `c.z` subject to `a z <= b`, `z >= 0`, with every `b >= 0`, so the slack basis is
/// feasible. Bland's rule: the lowest-index improving column enters, and the lowest-index basis
/// variable leaves among tied ratios, so the method cannot cycle and the twins pivot alike.
/// Returns the optimum, `z`, and the duals (one per constraint), or None when unbounded.
fn simplex(a: &[Vec<f64>], b: &[f64], c: &[f64]) -> Option<(f64, Vec<f64>, Vec<f64>)> {
    let (m, n) = (a.len(), c.len());
    let w = n + m + 1;
    let mut t: Vec<Vec<f64>> = a
        .iter()
        .zip(b)
        .enumerate()
        .map(|(i, (ai, bi))| {
            let mut row = vec![0.0; w];
            row[..n].copy_from_slice(ai);
            row[n + i] = 1.0;
            row[w - 1] = *bi;
            row
        })
        .collect();
    let mut obj = vec![0.0; w];
    for (o, cj) in obj.iter_mut().zip(c) {
        *o = -cj;
    }
    let mut basis: Vec<usize> = (n..n + m).collect();
    for _ in 0..10_000 {
        let Some(e) = (0..n + m).find(|&j| obj[j] < -EPS) else {
            let mut z = vec![0.0; n];
            for (row, &bv) in t.iter().zip(&basis) {
                if bv < n {
                    z[bv] = row[w - 1];
                }
            }
            let duals = (0..m).map(|i| obj[n + i]).collect();
            return Some((obj[w - 1], z, duals));
        };
        let mut leave: Option<usize> = None;
        for (i, row) in t.iter().enumerate() {
            if row[e] > EPS {
                let r = row[w - 1] / row[e];
                leave = match leave {
                    None => Some(i),
                    Some(l) => {
                        let rl = t[l][w - 1] / t[l][e];
                        if r < rl || (r == rl && basis[i] < basis[l]) {
                            Some(i)
                        } else {
                            Some(l)
                        }
                    }
                };
            }
        }
        let p = leave?;
        let pv = t[p][e];
        for x in &mut t[p] {
            *x /= pv;
        }
        let prow = t[p].clone();
        for (i, row) in t.iter_mut().enumerate() {
            if i != p {
                let f = row[e];
                if f != 0.0 {
                    for (x, y) in row.iter_mut().zip(&prow) {
                        *x -= f * y;
                    }
                }
            }
        }
        let f = obj[e];
        for (x, y) in obj.iter_mut().zip(&prow) {
            *x -= f * y;
        }
        basis[p] = e;
    }
    None
}

/// What one step is solved over: the base's rows, the gap rows among them, each row's response
/// to one step of each dial with its standard error, and how far each dial may step each way.
struct Problem {
    rows: Vec<Row>,
    gap: Vec<usize>,
    dials: Vec<&'static str>,
    jac: Vec<Vec<f64>>,
    se: Vec<Vec<f64>>,
    up: Vec<f64>,
    down: Vec<f64>,
    /// the dials read `-probe` steps away, from a range bound
    probed: Vec<&'static str>,
    /// how far inside its band a gap row must reach (`-margin`)
    margin: f64,
}

/// The optimum at `k` standard errors: the share of every gap closed, the step in sigmas per
/// dial, and the constraints that bind, with their dual weights.
struct Step {
    t: f64,
    s: Vec<f64>,
    binding: Vec<(String, f64)>,
}

impl Problem {
    /// A row's move J s at its upper (sign +1) or lower (sign -1) k-SE bound, as coefficients on
    /// z = [s+, s-, t]: a step up moves the row by J, a step down by -J, each at its worst case.
    fn bound(&self, r: usize, sign: f64, k: f64) -> Vec<f64> {
        let nd = self.dials.len();
        let mut v = vec![0.0; 2 * nd + 1];
        for (i, (j, e)) in self.jac[r].iter().zip(&self.se[r]).enumerate() {
            v[i] = j + sign * k * e;
            v[nd + i] = -(j - sign * k * e);
        }
        v
    }

    /// The constraint a row puts on the step: its label, coefficients and right-hand sides.
    fn row_constraints(&self, r: usize, k: f64) -> Vec<(String, Vec<f64>, f64)> {
        let (name, x, iv) = self.rows[r];
        let n = 2 * self.dials.len() + 1;
        // a row the base could not measure constrains nothing
        if !x.is_finite() {
            return Vec::new();
        }
        // the worst case of dir * J s, negated: `<= 0` says it moves the right way
        let toward = |dir: f64| -> Vec<f64> {
            let mv = self.bound(r, if dir > 0.0 { -1.0 } else { 1.0 }, k);
            mv.iter().map(|c| -dir * c).collect()
        };
        // a gap row already where it must reach has nothing to close, and is held like any other
        // row: a later step of `-iterate` must not give back what an earlier one closed
        let open = if self.gap.contains(&r) {
            need(x, inset(iv, self.margin))
        } else {
            None
        };
        if let Some((dir, dist)) = open {
            // t dist <= the worst case of dir * J s
            let mut v = toward(dir);
            v[n - 1] = dist;
            vec![(format!("gap        {name}"), v, 0.0)]
        } else if let Some((dir, _)) = need(x, iv) {
            // already out: no further out, at the worst case
            vec![(format!("no further {name}"), toward(dir), 0.0)]
        } else {
            // an open side holds nothing
            let mut held = Vec::new();
            if iv.1.is_finite() {
                held.push((
                    format!("band top   {name}"),
                    self.bound(r, 1.0, k),
                    iv.1 - x,
                ));
            }
            if iv.0.is_finite() {
                let floor: Vec<f64> = self.bound(r, -1.0, k).iter().map(|c| -c).collect();
                held.push((format!("band floor {name}"), floor, x - iv.0));
            }
            held
        }
    }

    fn solve(&self, k: f64) -> Option<Step> {
        let nd = self.dials.len();
        // z = [s+ (nd), s- (nd), t]
        let n = 2 * nd + 1;
        let mut cons: Vec<(String, Vec<f64>, f64)> = (0..self.rows.len())
            .flat_map(|r| self.row_constraints(r, k))
            .collect();
        for (i, ((u, dn), name)) in self.up.iter().zip(&self.down).zip(&self.dials).enumerate() {
            let mut v = vec![0.0; n];
            v[i] = 1.0;
            cons.push((format!("trust up   {name}"), v, *u));
            let mut v = vec![0.0; n];
            v[nd + i] = 1.0;
            cons.push((format!("trust down {name}"), v, *dn));
        }
        let mut v = vec![0.0; n];
        v[n - 1] = 1.0;
        cons.push(("t <= 1".to_string(), v, 1.0));
        let a: Vec<Vec<f64>> = cons.iter().map(|c| c.1.clone()).collect();
        let b: Vec<f64> = cons.iter().map(|c| c.2).collect();
        let mut c = vec![-STEP_COST; n];
        c[n - 1] = 1.0;
        let (_, z, duals) = simplex(&a, &b, &c)?;
        let t = z[n - 1];
        let s = (0..nd).map(|i| z[i] - z[nd + i]).collect();
        let binding = cons
            .into_iter()
            .zip(duals)
            .filter(|(_, y)| *y > 1e-9)
            .map(|(con, y)| (con.0, y))
            .collect();
        Some(Step { t, s, binding })
    }
}

// ---- options ------------------------------------------------------------------------------

#[derive(Clone)]
struct Opts {
    at: String,
    anchors: String,
    paths: usize,
    years: usize,
    seeds: u64,
    base: u64,
    sigma: f64,
    trust: f64,
    robust: f64,
    gap: Vec<String>,
    dials: Vec<String>,
    out: String,
    verify: bool,
    probe: f64,
    iterate: usize,
    gates: bool,
    noregress: String,
    /// the judge's tolerance: percentile points on a banded row, log units on any other
    tol: (f64, f64),
    /// a file of searched-dial flags applied to the `-at` world
    flags: String,
    /// how far inside its band a gap row must reach
    margin: f64,
}

fn usage(msg: &str) -> ! {
    if !msg.is_empty() {
        eprintln!("error: {msg}\n");
    }
    eprintln!(
        "usage: market_sim_reach -at NAME [options]
  -at NAME      ; the release or recipe to read the dials' responses at
  -anchors A    ; sp500 or nasdaq (default: the recipe's own set, else sp500)
  -paths N      ; ensemble paths per read (default 60)
  -years Y      ; years per path (default 80)
  -seeds K      ; shared seeds per read (default 8)
  -base S       ; the seeds are S + k * 1000003; the check's are S + 991 + k * 1000003
  -sigma S      ; one step, as a fraction of each dial's width (default 0.07, the search's)
  -trust T      ; the largest step per dial, in steps (default 3)
  -robust K     ; responses at their K-standard-error worst case (default 2)
  -gap ROWS     ; comma-separated rows to close (default: every row the base misses)
  -dials LIST   ; comma-separated dials to step (default: every searched dial)
  -out FILE     ; write every response and its standard error as TSV
  -noverify     ; skip reading the proposed step
  -probe P      ; a dial at a range bound (0 is usually off) is read P steps away and its
                ;   response taken per step over them (default 3, the trust): an effect that
                ;   grows faster than linearly from 0 reads as nothing one step away
  -iterate N    ; take N steps: after each, read the responses again at the stepped world and
                ;   solve again (default 1)
  -nogates      ; do not hold the gates: by default every gate that is a band on one reading is
                ;   held like a row's band, and the check names the other gates the step breaks
  -noregress R  ; hold every row no further from its record than recipe R, read on the same seeds,
                ;   plus -tol: what the release judge compares a candidate with its outgoing recipe on
  -tol P,L      ; that tolerance: P percentile points on a banded row, L log units on any other
                ;   (default 5,0.02, the judge's)
  -flags FILE   ; set searched dials on the -at world from FILE's `-name value` pairs, in order,
                ;   the last of a name winning, as the search exports a candidate
  -margin M     ; a gap row is closed only M inside its band's edge, in the row's own units
                ;   (default 0): the room a reading needs to clear the band on other seeds"
    );
    std::process::exit(2)
}

fn list(s: &str) -> Vec<String> {
    s.split(',')
        .map(str::trim)
        .filter(|x| !x.is_empty())
        .map(str::to_string)
        .collect()
}

fn parse_args() -> Opts {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut o = Opts {
        at: String::new(),
        anchors: String::new(),
        paths: 60,
        years: 80,
        seeds: 8,
        base: 20_260_813,
        sigma: 0.07,
        trust: 3.0,
        robust: 2.0,
        gap: Vec::new(),
        dials: Vec::new(),
        out: String::new(),
        verify: true,
        probe: 3.0,
        iterate: 1,
        gates: true,
        noregress: String::new(),
        tol: (5.0, 0.02),
        flags: String::new(),
        margin: 0.0,
    };
    let mut it = args.iter();
    while let Some(flag) = it.next() {
        let mut next = || {
            it.next()
                .cloned()
                .unwrap_or_else(|| usage(&format!("{flag} wants a value")))
        };
        let int = |v: String| -> u64 {
            v.parse()
                .unwrap_or_else(|_| usage(&format!("{flag} wants an integer, got [{v}]")))
        };
        let real = |v: String| -> f64 {
            v.parse()
                .unwrap_or_else(|_| usage(&format!("{flag} wants a number, got [{v}]")))
        };
        match flag.as_str() {
            "-at" => o.at = next(),
            "-anchors" => o.anchors = next(),
            "-paths" => o.paths = usize::try_from(int(next())).unwrap_or(60),
            "-years" => o.years = usize::try_from(int(next())).unwrap_or(80),
            "-seeds" => o.seeds = int(next()),
            "-base" => o.base = int(next()),
            "-sigma" => o.sigma = real(next()),
            "-trust" => o.trust = real(next()),
            "-robust" => o.robust = real(next()),
            "-gap" => o.gap = list(&next()),
            "-dials" => o.dials = list(&next()),
            "-out" => o.out = next(),
            "-noverify" => o.verify = false,
            "-probe" => o.probe = real(next()),
            "-iterate" => o.iterate = usize::try_from(int(next())).unwrap_or(1),
            "-nogates" => o.gates = false,
            "-flags" => o.flags = next(),
            "-margin" => o.margin = real(next()),
            "-noregress" => o.noregress = next(),
            "-tol" => {
                let v = list(&next());
                if v.len() != 2 {
                    usage("-tol wants P,L");
                }
                o.tol = (real(v[0].clone()), real(v[1].clone()));
            }
            other => usage(&format!("unknown flag [{other}]")),
        }
    }
    if o.at.is_empty() {
        usage("-at names the world to read");
    }
    if o.seeds < 2 {
        usage("-seeds wants at least 2: a response's standard error needs two readings");
    }
    if o.sigma <= 0.0 || o.trust <= 0.0 || o.robust < 0.0 || o.probe < 1.0 {
        usage(
            "-sigma and -trust want positive numbers, -robust a non-negative one, -probe 1 or more",
        );
    }
    if o.iterate < 1 {
        usage("-iterate wants at least 1");
    }
    if o.tol.0 < 0.0 || o.tol.1 < 0.0 || o.margin < 0.0 {
        usage("-tol and -margin want non-negative numbers");
    }
    o
}

/// `base` with the searched dials `file` sets (`-flags`): `-name value` pairs as the search exports
/// a candidate, a name matching a searched dial without regard to case, applied in order so the
/// last of a name wins, and set through `with_dials` as a step's are.
fn with_flags(base: &World, all: &[Range], file: &str) -> World {
    let text = std::fs::read_to_string(file).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    let words: Vec<&str> = text.split_whitespace().collect();
    if !words.len().is_multiple_of(2) {
        usage(&format!(
            "{file} holds an odd number of words; it wants -name value pairs"
        ));
    }
    let mut d: Vec<f64> = all.iter().map(|r| (r.4)(base)).collect();
    for pair in words.chunks_exact(2) {
        let name = pair[0].trim_start_matches('-').to_lowercase();
        let k = all
            .iter()
            .position(|r| r.0.to_lowercase() == name)
            .unwrap_or_else(|| usage(&format!("{file}: [{}] is not a searched dial", pair[0])));
        d[k] = pair[1].parse().unwrap_or_else(|_| {
            usage(&format!(
                "{file}: {} wants a number, got [{}]",
                pair[0], pair[1]
            ))
        });
    }
    with_dials(base, all, &d)
}

// ---- the stages ---------------------------------------------------------------------------

/// What every stage reads: the options, the world, its anchor set, every searched dial with the
/// world's values, the dials being stepped, the shared seeds, the no-regression limits, and the
/// rows every read is aligned to, of which the first `n_fid` are fidelity rows.
struct Setup {
    o: Opts,
    w0: World,
    a: ms::Anchors,
    all: Vec<Range>,
    d_all: Vec<f64>,
    rs: Vec<Range>,
    seeds: Vec<u64>,
    limits: Vec<Limit>,
    layout: Vec<Row>,
    n_fid: usize,
}

/// THE LAYOUT: the rows one read of `w` carries, which every later read is aligned to by name --
/// a gate graded only inside its anchors' range can come and go with a step, and a row a read
/// lacks reads as unmeasured. The names are leaked once per layout so a `Row` stays `Copy`.
fn layout_of(
    w: &World,
    a: ms::Anchors,
    o: &Opts,
    limits: &[Limit],
    seed: u64,
) -> (Vec<Row>, usize) {
    let r = read(w, a, o, limits, seed);
    let rows = r
        .rows
        .into_iter()
        .map(|(n, _, iv)| {
            let name: &'static str = Box::leak(n.into_boxed_str());
            (name, f64::NAN, iv)
        })
        .collect();
    (rows, r.n_fid)
}

impl Setup {
    fn new(o: Opts) -> Setup {
        let (w0, spec) = ms::named_world(&o.at).unwrap_or_else(|| {
            usage(&format!(
                "-at names [{}], which is not a release or recipe",
                o.at
            ))
        });
        let a = ms::anchors_named(if o.anchors.is_empty() {
            spec.unwrap_or("sp500")
        } else {
            &o.anchors
        });
        let all: Vec<Range> = ms::calibrate_ranges();
        let rs: Vec<Range> = if o.dials.is_empty() {
            all.clone()
        } else {
            o.dials
                .iter()
                .map(|d| {
                    *all.iter().find(|r| r.0 == d.as_str()).unwrap_or_else(|| {
                        usage(&format!("-dials names [{d}], which is not searched"))
                    })
                })
                .collect()
        };
        let w0 = if o.flags.is_empty() {
            w0
        } else {
            with_flags(&w0, &all, &o.flags)
        };
        let d_all: Vec<f64> = all.iter().map(|r| (r.4)(&w0)).collect();
        let seeds: Vec<u64> = (0..o.seeds)
            .map(|k| o.base.wrapping_add(k * 1_000_003))
            .collect();
        let limits = if o.noregress.is_empty() {
            Vec::new()
        } else {
            limits_of(&o, a, &seeds)
        };
        let (layout, n_fid) = layout_of(&w0, a, &o, &limits, seeds[0]);
        Setup {
            o,
            w0,
            a,
            all,
            d_all,
            rs,
            seeds,
            limits,
            layout,
            n_fid,
        }
    }

    /// Each seed's read aligned to the layout, and the gates it fails.
    fn read_gated(&self, w: &World, seeds: &[u64]) -> (Vec<Vec<Row>>, Vec<Vec<String>>) {
        seeds
            .iter()
            .map(|&s| {
                let r = read(w, self.a, &self.o, &self.limits, s);
                let rows = self
                    .layout
                    .iter()
                    .map(|&(n, _, iv)| {
                        r.rows
                            .iter()
                            .find(|x| x.0 == n)
                            .map_or((n, f64::NAN, iv), |x| (n, x.1, x.2))
                    })
                    .collect();
                (rows, r.failed)
            })
            .unzip()
    }

    fn read_on(&self, w: &World, seeds: &[u64]) -> Vec<Vec<Row>> {
        self.read_gated(w, seeds).0
    }

    /// The world with each stepped dial moved `s[i]` steps.
    fn stepped(&self, s: &[f64]) -> World {
        let mut d = self.d_all.clone();
        for (r, si) in self.rs.iter().zip(s) {
            let k = self.all.iter().position(|x| x.0 == r.0).unwrap_or(0);
            d[k] = from_coord(r, coord(r, self.d_all[k]) + si * step_of(r, self.o.sigma));
        }
        with_dials(&self.w0, &self.all, &d)
    }

    /// The setup at the world one step `s` away, its gap rows named as they were here, so every
    /// step closes the same rows.
    fn moved(&self, s: &[f64], gap: Vec<String>) -> Setup {
        let w0 = self.stepped(s);
        let mut o = self.o.clone();
        o.gap = gap;
        let (layout, n_fid) = layout_of(&w0, self.a, &o, &self.limits, self.seeds[0]);
        Setup {
            o,
            w0,
            a: self.a,
            all: self.all.clone(),
            d_all: self.all.iter().map(|r| (r.4)(&w0)).collect(),
            rs: self.rs.clone(),
            seeds: self.seeds.clone(),
            limits: self.limits.clone(),
            layout,
            n_fid,
        }
    }
}

/// THE BASE on every shared seed, its mean rows, and the gap rows among them.
fn base_rows(su: &Setup, base: &[Vec<Row>]) -> (Vec<Row>, Vec<usize>) {
    let rows: Vec<Row> = (0..base[0].len())
        .map(|r| {
            let xs: Vec<f64> = base.iter().map(|b| b[r].1).collect();
            (base[0][r].0, mean(&xs), base[0][r].2)
        })
        .collect();
    // by default the fidelity rows the base misses; a gate or a limit it is already outside is
    // held no further out, and closed only when `-gap` names it
    let gap: Vec<usize> = if su.o.gap.is_empty() {
        (0..su.n_fid)
            .filter(|&r| need(rows[r].1, rows[r].2).is_some())
            .collect()
    } else {
        su.o.gap
            .iter()
            .map(|g| {
                rows.iter()
                    .position(|row| row.0 == g.as_str())
                    .unwrap_or_else(|| {
                        usage(&format!("-gap names [{g}], which is not a graded row"))
                    })
            })
            .collect()
    };
    println!("\nthe base, mean of {} seeds:", su.seeds.len());
    for (r, &(name, x, (lo, hi))) in rows.iter().enumerate() {
        let tag = if gap.contains(&r) {
            "  <-- GAP"
        } else if need(x, (lo, hi)).is_some() {
            "  (misses, held)"
        } else {
            ""
        };
        println!(
            "  {name:<24} {}   band {} .. {}{tag}",
            jf(x, 10, 4),
            jf(lo, 9, 4),
            jl(hi, 9, 4)
        );
    }
    (rows, gap)
}

/// THE RESPONSES: one step of each dial, read on the base's seeds, each row's mean difference per
/// step up and its standard error; and how many steps each dial may take each way.
fn respond(su: &Setup, base: &[Vec<Row>], rows: Vec<Row>, gap: Vec<usize>) -> Problem {
    let nd = su.rs.len();
    let mut p = Problem {
        rows,
        gap,
        dials: su.rs.iter().map(|r| r.0).collect(),
        jac: vec![vec![0.0; nd]; base[0].len()],
        se: vec![vec![0.0; nd]; base[0].len()],
        up: vec![0.0; nd],
        down: vec![0.0; nd],
        probed: Vec::new(),
        margin: su.o.margin,
    };
    for (i, r) in su.rs.iter().enumerate() {
        let k = su.all.iter().position(|x| x.0 == r.0).unwrap_or(0);
        let c0 = coord(r, su.d_all[k]);
        let h = step_of(r, su.o.sigma);
        // a dial at a range bound is read `-probe` steps away: switched off at 0, a mechanism's
        // effect can grow faster than linearly, and one step would read it as nothing
        let m = if c0 <= coord(r, r.1) || c0 >= coord(r, r.2) {
            su.o.probe
        } else {
            1.0
        };
        // toward the interior when a step up would leave the range
        let dir = if c0 + m * h <= coord(r, r.2) {
            1.0
        } else {
            -1.0
        };
        p.up[i] = su.o.trust.min((coord(r, r.2) - c0) / h).max(0.0);
        p.down[i] = su.o.trust.min((c0 - coord(r, r.1)) / h).max(0.0);
        let mut d = su.d_all.clone();
        d[k] = from_coord(r, c0 + dir * m * h);
        let reads = su.read_on(&with_dials(&su.w0, &su.all, &d), &su.seeds);
        for (rr, (jr, sr)) in p.jac.iter_mut().zip(p.se.iter_mut()).enumerate() {
            if !p.rows[rr].1.is_finite() {
                continue;
            }
            let diffs: Vec<f64> = reads
                .iter()
                .zip(base)
                .map(|(x1, x0)| dir * (x1[rr].1 - x0[rr].1) / m)
                .collect();
            // a reading the stepped world could not take says nothing about the dial
            if diffs.iter().all(|x| x.is_finite()) {
                jr[i] = mean(&diffs);
                sr[i] = std_err(&diffs);
            } else {
                eprintln!(
                    "  {} leaves {} unmeasured; its response is read as 0",
                    r.0, p.rows[rr].0
                );
            }
        }
        if m > 1.0 {
            p.probed.push(r.0);
        }
        eprintln!("  responded: {} ({}/{nd})", r.0, i + 1);
    }
    if !p.probed.is_empty() {
        println!(
            "\nat a range bound, read {} steps away: {}",
            jf(su.o.probe, 0, 1),
            p.probed.join(", ")
        );
    }
    p
}

/// Every response and its standard error, as TSV.
fn write_responses(p: &Problem, file: &str) {
    let mut text = String::from("row\tdial\tresponse\tse\n");
    for ((row, jr), sr) in p.rows.iter().zip(&p.jac).zip(&p.se) {
        for ((dial, j), e) in p.dials.iter().zip(jr).zip(sr) {
            let _ = writeln!(
                text,
                "{}\t{dial}\t{}\t{}",
                row.0,
                jf(*j, 0, 6),
                jf(*e, 0, 6)
            );
        }
    }
    std::fs::write(file, text).unwrap_or_else(|e| usage(&format!("{file}: {e}")));
    println!("\nwrote {file}");
}

/// WHICH DIALS MOVE EACH GAP ROW the way it must go, by how many standard errors.
fn report_gap_dials(p: &Problem) {
    for &g in &p.gap {
        let (dir, dist) = need(p.rows[g].1, inset(p.rows[g].2, p.margin)).unwrap_or((0.0, 0.0));
        let target = if p.margin > 0.0 {
            format!("{} inside its band edge", jf(p.margin, 0, 4))
        } else {
            "its band edge".to_string()
        };
        println!(
            "\n{} needs {} (to {target}); per step up, the dials that move it that way:",
            p.rows[g].0,
            jp(dir * dist, 4)
        );
        let mut by: Vec<(usize, f64)> = (0..p.dials.len())
            .map(|i| {
                let z = if p.se[g][i] > 0.0 {
                    dir * p.jac[g][i] / p.se[g][i]
                } else {
                    0.0
                };
                (i, z)
            })
            .collect();
        by.sort_by(|x, y| y.1.total_cmp(&x.1).then(x.0.cmp(&y.0)));
        for &(i, z) in by.iter().take(6) {
            println!(
                "  {:<18} {} +- {}   ({} se)",
                p.dials[i],
                jp(p.jac[g][i], 4),
                jf(p.se[g][i], 0, 4),
                jp(z, 1)
            );
        }
    }
}

fn report_step(p: &Problem, label: &str, k: f64, st: &Step) {
    println!(
        "\n{label} (responses at {} se): one step closes {}% of every gap",
        jf(k, 0, 1),
        jf(100.0 * st.t, 0, 1)
    );
    let mut moved: Vec<(usize, f64)> =
        st.s.iter()
            .enumerate()
            .filter(|(_, v)| v.abs() > 1e-9)
            .map(|(i, v)| (i, *v))
            .collect();
    moved.sort_by(|x, y| y.1.abs().total_cmp(&x.1.abs()).then(x.0.cmp(&y.0)));
    for &(i, v) in &moved {
        println!("  step {:<18} {} sigma", p.dials[i], jp(v, 3));
    }
    println!("  bound by (dual weight):");
    for (l, y) in &st.binding {
        println!("    {l:<40} {}", jf(*y, 0, 4));
    }
}

/// THE CHECK: the step read on fresh seeds, against the base on the same fresh seeds and the
/// linear prediction. Returns each row's mean reading before and after the step.
fn check(su: &Setup, p: &Problem, st: &Step) -> Vec<(f64, f64)> {
    let fresh: Vec<u64> = (0..su.o.seeds)
        .map(|k| su.o.base.wrapping_add(991 + k * 1_000_003))
        .collect();
    let (at0, f0) = su.read_gated(&su.w0, &fresh);
    let (at1, f1) = su.read_gated(&su.stepped(&st.s), &fresh);
    println!(
        "\nthe robust step read on {} fresh seeds (base -> stepped, the linear prediction):",
        fresh.len()
    );
    let mut read = Vec::new();
    for (r, row) in p.rows.iter().enumerate() {
        let x0 = mean(&at0.iter().map(|b| b[r].1).collect::<Vec<f64>>());
        let xs: Vec<f64> = at1.iter().map(|b| b[r].1).collect();
        let x1 = mean(&xs);
        read.push((x0, x1));
        let pred = x0 + total(p.jac[r].iter().zip(&st.s).map(|(j, s)| j * s));
        let tag = match (need(x0, row.2).is_some(), need(x1, row.2).is_some()) {
            (false, true) => "  <-- LEAVES its band",
            (true, false) => "  <-- ENTERS its band",
            (true, true) => "  (misses)",
            (false, false) => "",
        };
        let g = if p.gap.contains(&r) { "*" } else { " " };
        println!(
            " {g}{:<24} {} -> {} +- {}   predicted {}{tag}",
            row.0,
            jf(x0, 10, 4),
            jf(x1, 10, 4),
            jf(std_err(&xs), 0, 4),
            jf(pred, 10, 4)
        );
    }
    // every gate, in every class, by the fresh seeds' majority: the ones the program held are
    // above, and the rest -- combined and mechanism rows -- are only read here
    let often = |fs: &[Vec<String>]| -> Vec<String> {
        let mut names: Vec<&String> = Vec::new();
        for g in fs.iter().flatten() {
            if !names.contains(&g) {
                names.push(g);
            }
        }
        names
            .into_iter()
            .filter(|g| 2 * fs.iter().filter(|f| f.contains(g)).count() >= fs.len())
            .cloned()
            .collect()
    };
    let (was, now) = (often(&f0), often(&f1));
    println!(
        "  gates failed on at least half the fresh seeds: {} -> {}",
        was.len(),
        now.len()
    );
    for g in now.iter().filter(|g| !was.contains(g)) {
        println!("    FAILS  {g}");
    }
    for g in was.iter().filter(|g| !now.contains(g)) {
        println!("    passes {g}");
    }
    read
}

/// Each gap row's name and its fresh-seed readings before and after a step.
type Trail = Vec<(String, f64, f64)>;

/// One step from `su`: the base, the responses, both solves and the check. Returns the robust
/// step and its `Trail`, or why no step was taken.
fn one_step(su: &Setup) -> Result<(Step, Trail), &'static str> {
    let base = su.read_on(&su.w0, &su.seeds);
    let (rows, gap) = base_rows(su, &base);
    // nothing left to close, and the responses cost a read per dial
    if gap
        .iter()
        .all(|&g| need(rows[g].1, inset(rows[g].2, su.o.margin)).is_none())
    {
        return Err("every gap row is inside its band");
    }
    let p = respond(su, &base, rows, gap);
    if !su.o.out.is_empty() {
        write_responses(&p, &su.o.out);
    }
    report_gap_dials(&p);
    let nominal = p.solve(0.0);
    let robust = p.solve(su.o.robust);
    for (label, k, st) in [("nominal", 0.0, &nominal), ("robust", su.o.robust, &robust)] {
        match st {
            None => println!("\n{label}: the linear program is unbounded"),
            Some(st) => report_step(&p, label, k, st),
        }
    }
    let Some(st) = robust.filter(|st| st.t > 0.0) else {
        return Err("no robust step closes any share of the gap");
    };
    let trail = if su.o.verify {
        let read = check(su, &p, &st);
        p.gap
            .iter()
            .map(|&g| (p.rows[g].0.to_string(), read[g].0, read[g].1))
            .collect()
    } else {
        p.gap
            .iter()
            .map(|&g| (p.rows[g].0.to_string(), f64::NAN, f64::NAN))
            .collect()
    };
    Ok((st, trail))
}

fn main() {
    let mut su = Setup::new(parse_args());
    println!(
        "reach: {} on {}, {} x {}y on {} shared seeds; step {} of each dial's width, trust {} steps, robust {} se",
        su.o.at,
        su.a.name,
        su.o.paths,
        su.o.years,
        su.seeds.len(),
        jf(su.o.sigma, 0, 3),
        jf(su.o.trust, 0, 1),
        jf(su.o.robust, 0, 1)
    );
    if !su.o.flags.is_empty() {
        println!("its searched dials set from {}", su.o.flags);
    }
    if su.o.margin > 0.0 {
        println!(
            "a gap row is closed {} inside its band edge",
            jf(su.o.margin, 0, 4)
        );
    }
    println!(
        "{}",
        if su.o.gates {
            "every gate that is a band on one reading is held as a row's band"
        } else {
            "gates not held (-nogates)"
        }
    );
    if !su.limits.is_empty() {
        println!(
            "\nno further from the record than {} (mean of {} seeds; -tol {},{}), each row's readings:",
            su.o.noregress,
            su.seeds.len(),
            jf(su.o.tol.0, 0, 1),
            jf(su.o.tol.1, 0, 3)
        );
        for l in &su.limits {
            println!(
                "  {:<24} distance {}   {} .. {}",
                l.row,
                jf(l.dist, 8, 4),
                jf(l.iv.0, 10, 4),
                jf(l.iv.1, 10, 4)
            );
        }
    }
    // each gap row's fresh-seed reading at the start and after every step taken
    let mut path: Vec<(String, Vec<f64>)> = Vec::new();
    let steps = su.o.iterate;
    let mut stopped = None;
    for step in 1..=steps {
        if steps > 1 {
            println!("\n==== step {step} of {steps} ====");
        }
        let (st, trail) = match one_step(&su) {
            Ok(taken) => taken,
            Err(why) => {
                stopped = Some((step, why));
                break;
            }
        };
        if path.is_empty() {
            path = trail
                .iter()
                .map(|(n, x0, _)| (n.clone(), vec![*x0]))
                .collect();
        }
        for ((_, xs), (_, _, x1)) in path.iter_mut().zip(&trail) {
            xs.push(*x1);
        }
        if step < steps {
            let names = trail.iter().map(|(n, _, _)| n.clone()).collect();
            su = su.moved(&st.s, names);
        }
    }
    if let Some((step, why)) = stopped {
        println!("\nstopped at step {step}: {why}");
    }
    // only steps taken and read on fresh seeds have a reading to show
    if steps > 1 && su.o.verify && !path.is_empty() {
        println!("\nthe gap rows on fresh seeds, at the start and after each step:");
        for (name, xs) in &path {
            let cells: Vec<String> = xs.iter().map(|x| jf(*x, 0, 4)).collect();
            println!("  {name:<24} {}", cells.join(" -> "));
        }
    }
}

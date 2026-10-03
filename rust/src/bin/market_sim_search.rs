//! A CALIBRATION SEARCH for the market simulator: the Rust twin of `jsrc/marketSimSearch.sc`,
//! built to run for days and to be killed at any moment.
//!
//! ```text
//!   cargo build --release --bin market_sim_search
//!   ./target/release/market_sim_search -out search -paths 60 -years 80
//! ```
//!
//! One evaluation at 60 x 80 costs 0.67 s here against 5.17 s of warmed JVM, with byte-identical
//! readings.  NOT SHIPPED: `Cargo.toml` excludes this file from the published crate, as it does the
//! `bench_*` binaries.
//!
//! WHAT IT PRODUCES is an ARCHIVE, not a champion: 48 searched dials against ~45 graded rows that
//! are not independent means distinct worlds match the record equally well, and a strategy feels
//! the mechanism, not the summary statistic.
//!
//! THE OBJECTIVE IS LEXICOGRAPHIC.  Feasibility first, a hard gate never priced into a scalar, so a
//! failed row cannot be bought.  Then the SUM over rows of each row's distance from the record past
//! a DEAD ZONE at the record's own sampling error: inside it no gradient, so a long search cannot
//! optimise noise; outside it every row counts, so no row drifts while another binds.
//!
//! SETTINGS THAT MEASUREMENT SET: 1 uniform draw in 60 is feasible, so the search is seeded from
//! the frozen worlds; a Gaussian mutation of 5-10% of a dial's range keeps four children in five
//! feasible and 20% breaks, so plain rejection handles the constraint; seed noise is a smooth sd of
//! 0.074 plus a 0.5 jump when a band-edge row flips (one seed in six), so every `-reps` seed must
//! pass.
//!
//! EVERYTHING IS PORTABLE BETWEEN THE TWO HARNESSES, `-transport` and `-fidelity` included, and a
//! capability added to one is added to the other.  Feasibility comes from `gate_checks_at` on the
//! record-horizon readings, whose statistics are byte-identical across the twins, so a Scala
//! `-holdout` re-scores this archive exactly.  The mutation stream too: both draw from `NumPyRng`, gated bit-identical, where a
//! language-native Gaussian is not.

#![allow(
    clippy::print_stdout,
    clippy::print_stderr,
    reason = "a console tool for a days-long run: its output and its warnings are the product"
)]
#![allow(
    clippy::let_underscore_must_use,
    reason = "std::fmt::Write into a String cannot fail; the discard is the idiom"
)]

use std::collections::HashMap;
use std::fmt::Write as _;
use std::io::Write as _;

use uni::NumPyRng;
use uni::market_sim::Anchors;
use uni::market_sim::World;
use uni::market_sim::{self as ms};
use uni::udata::java_format_f;

// the binary's allocator: see the `fast-alloc` feature
#[cfg(feature = "fast-alloc")]
#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

// ---- formatting ---------------------------------------------------------------------------

/// Java's `%.8g`, which is what writes every dial in the Scala harness's archive. Eight
/// significant digits, fixed while the exponent is in -4 ..< 8 and scientific with a two-digit
/// exponent outside it, and NO stripping of trailing zeros -- C's `%g` strips, Java's does not,
/// and the archive files already written are in Java's shape. The exponent is read back from a
/// rounded rendering rather than from `log10`, so a value that rounds up into the next decade
/// switches notation the way Java's does.
fn g8(x: f64) -> String {
    const P: i32 = 8;
    if x == 0.0 {
        return format!("{:.*}", (P - 1) as usize, 0.0);
    }
    if !x.is_finite() {
        return format!("{x}");
    }
    let sci = format!("{:.*e}", (P - 1) as usize, x);
    let Some((mant, tail)) = sci.rsplit_once('e') else {
        return sci;
    };
    let Ok(exp) = tail.parse::<i32>() else {
        return sci;
    };
    if (-4..P).contains(&exp) {
        format!("{:.*}", (P - 1 - exp).max(0) as usize, x)
    } else {
        let sign = if exp < 0 { '-' } else { '+' };
        format!("{mant}e{sign}{:02}", exp.abs())
    }
}

// ---- behaviour descriptors -----------------------------------------------------------------

/// WHAT A WORLD DOES, beside what it is set to.  Never graded, never optimised: the archive keeps
/// worlds apart by dial distance, a proxy for behavioural difference; these measure it.  `retAc1`
/// leads because nothing in the target set constrains it -- variance ratios read a weighted sum of
/// autocorrelations and the clustering rows read |r| -- and it spread furthest across an archive.
/// Written for EVERY candidate into `log.tsv`: the rejected majority is gone when the run ends.
/// Both harnesses share this list; the archive is a format they share.
const DESC_NAMES: [&str; 6] = ["retAc1", "vr250", "clus20", "kurt", "epPerPath", "depthMed"];

fn desc_of(st: &ms::WorldStats) -> Vec<f64> {
    vec![
        st.ret_ac1,
        st.vr250,
        st.ac20,
        st.kurt,
        st.ep_per_path,
        st.depth_med,
    ]
}

// ---- cheap fidelity -------------------------------------------------------------------------

/// Average ranks, so ties do not invent an ordering the readings do not have.
fn ranks(xs: &[f64]) -> Vec<f64> {
    let mut idx: Vec<usize> = (0..xs.len()).collect();
    idx.sort_by(|&a, &b| xs[a].total_cmp(&xs[b]));
    let mut out = vec![0.0; xs.len()];
    let mut i = 0;
    while i < idx.len() {
        let mut j = i;
        while j + 1 < idx.len() && xs[idx[j + 1]] == xs[idx[i]] {
            j += 1;
        }
        let r = (i + j) as f64 / 2.0 + 1.0;
        for &k in &idx[i..=j] {
            out[k] = r;
        }
        i = j + 1;
    }
    out
}

/// Spearman: Pearson on the ranks. What a cheap ensemble has to preserve is the ORDER of worlds,
/// not their losses -- the search only ever compares candidates.
fn spearman(a: &[f64], b: &[f64]) -> f64 {
    let (ra, rb) = (ranks(a), ranks(b));
    let n = ra.len() as f64;
    let (ma, mb) = (ra.iter().sum::<f64>() / n, rb.iter().sum::<f64>() / n);
    let mut num = 0.0;
    let mut da = 0.0;
    let mut db = 0.0;
    for i in 0..ra.len() {
        num += (ra[i] - ma) * (rb[i] - mb);
        da += (ra[i] - ma).powi(2);
        db += (rb[i] - mb).powi(2);
    }
    if da == 0.0 || db == 0.0 {
        f64::NAN
    } else {
        num / (da * db).sqrt()
    }
}

/// `PxY` pairs: `20x40,30x60`.
fn parse_ensembles(spec: &str) -> Vec<(usize, usize)> {
    spec.split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| {
            let Some((p, y)) = s.split_once(['x', 'X']) else {
                usage(&format!("-fidelity wants PxY pairs, got [{s}]"));
            };
            let (Ok(p), Ok(y)) = (p.trim().parse(), y.trim().parse()) else {
                usage(&format!("-fidelity wants PxY pairs of integers, got [{s}]"));
            };
            (p, y)
        })
        .collect()
}

// ---- the search space ---------------------------------------------------------------------

type Range = (&'static str, f64, f64, ms::Setter, ms::Getter);

/// THE SEARCH SPACE IS THE LIBRARY'S table, never a copy: the sampler draws one uniform per dial
/// in table order, so order is part of it, and `CALIBRATE_DIAL_ORDER` pins it in both twins.
fn ranges() -> Vec<Range> {
    ms::calibrate_ranges()
}

fn dials_of(w: &World) -> Vec<f64> {
    ranges().iter().map(|r| (r.4)(w)).collect()
}

fn world_of(base: &World, d: &[f64]) -> World {
    let mut w = *base;
    for (r, x) in ranges().iter().zip(d.iter()) {
        (r.3)(&mut w, *x);
    }
    w
}

/// A dial's value in the coordinates the search steps and measures it in: ln(1 + x) for the
/// library's `SEARCH_LOG_DIALS`, which the reachability check shares.
fn coord(r: &Range, x: f64) -> f64 {
    ms::search_coord(r.0, x)
}

/// A dial's width in its search coordinates.
fn coord_width(r: &Range) -> f64 {
    coord(r, r.2) - coord(r, r.1)
}

/// Distance in normalised dial space, max over dials -- the same reading the seed pool's spread
/// was measured with, so `-sep` is comparable to it. In each dial's search coordinates.
fn apart(a: &[f64], b: &[f64]) -> f64 {
    ranges()
        .iter()
        .enumerate()
        .map(|(i, r)| (coord(r, a[i]) - coord(r, b[i])).abs() / coord_width(r))
        .fold(f64::NEG_INFINITY, f64::max)
}

fn clamped(r: &Range, x: f64) -> f64 {
    x.max(r.1).min(r.2)
}

/// A parent's dial moved by `z` sd of `sigma` times its width, in its search coordinates, and
/// held inside its range. Identical to a step on the value itself for every other dial.
fn stepped(r: &Range, x: f64, z: f64, sigma: f64) -> f64 {
    let y = coord(r, x) + z * sigma * coord_width(r);
    if ms::SEARCH_LOG_DIALS.contains(&r.0) {
        let y = y.max(coord(r, r.1)).min(coord(r, r.2));
        clamped(r, ms::from_search_coord(r.0, y))
    } else {
        clamped(r, y)
    }
}

/// THE ARCHIVE'S OWN SHAPE as a step (`-cov`): the Cholesky factor of its members' covariance in
/// the dials' search coordinates, shrunk toward the independent step by `shrink` and scaled to
/// trace `nd`, so a child's expected squared step is the independent step's and only its direction
/// changes. `None` while the archive is too small to read a shape from.
///
/// WHY: the dials are not independent in their effect -- `depth` carries pooled volatility and the
/// typical year together, the slow channel carries the bond with it -- so the admitted members lie
/// along a few directions, and a step taken one dial at a time spends most of its children across
/// the grain. Measured on the 0.24.4-nasdaq set at 60 x 80, children both feasible and closer to
/// the record than their parent: 31 of 150 this way against 17 taken independently.
fn proposal_factor(arc: &[Member], rs: &[Range], shrink: f64) -> Option<Vec<Vec<f64>>> {
    let nd = rs.len();
    let m = arc.len();
    if m < nd / 4 || m < 4 {
        return None;
    }
    let u: Vec<Vec<f64>> = arc
        .iter()
        .map(|x| {
            (0..nd)
                .map(|i| (coord(&rs[i], x.dials[i]) - coord(&rs[i], rs[i].1)) / coord_width(&rs[i]))
                .collect()
        })
        .collect();
    let mean: Vec<f64> = (0..nd)
        .map(|i| u.iter().map(|x| x[i]).sum::<f64>() / m as f64)
        .collect();
    let mut c = vec![vec![0.0; nd]; nd];
    for i in 0..nd {
        for j in 0..nd {
            c[i][j] = u
                .iter()
                .map(|x| (x[i] - mean[i]) * (x[j] - mean[j]))
                .sum::<f64>()
                / (m - 1) as f64;
        }
    }
    let tr: f64 = (0..nd).map(|i| c[i][i]).sum();
    if !(tr > 0.0 && tr.is_finite()) {
        return None;
    }
    // shrunk toward the independent step, then trace nd: the step's length is the independent
    // step's whatever shape the archive has
    let k: Vec<Vec<f64>> = (0..nd)
        .map(|i| {
            (0..nd)
                .map(|j| {
                    (1.0 - shrink) * c[i][j] * nd as f64 / tr + if i == j { shrink } else { 0.0 }
                })
                .collect()
        })
        .collect();
    // Cholesky, the twins' operation order: a lower factor L with K = L L^T
    let mut l = vec![vec![0.0; nd]; nd];
    for i in 0..nd {
        for j in 0..=i {
            let mut s = 0.0;
            for (a, b) in l[i].iter().zip(&l[j]).take(j) {
                s += a * b;
            }
            if i == j {
                l[i][j] = (k[i][i] - s).max(1e-12).sqrt();
            } else {
                l[i][j] = (k[i][j] - s) / l[j][j];
            }
        }
    }
    Some(l)
}

/// A proposal with its news size pulled inside the search's share of the diffusion budget
/// (`news_size_within_budget`), so the archive holds the dials of the world it scored.
fn admissible(d: Vec<f64>) -> Vec<f64> {
    let rs = ranges();
    let dial = |n: &str| rs.iter().zip(&d).find(|(r, _)| r.0 == n).map(|(_, x)| *x);
    let rate = dial("newsRate").unwrap_or(0.0);
    let size = dial("newsSize").map_or(0.0, |x| ms::news_size_within_budget(rate, x));
    rs.iter()
        .zip(d.iter())
        .map(|(r, &x)| if r.0 == "newsSize" { size } else { x })
        .collect()
}

/// The dead zone in the units `fitness` scores rows in. `wgt` makes one anchor sd worth
/// `SD_REL_REF` there, so `-dead 1.0` means "inside the record's own sampling error, no gradient".
/// A dial rather than a constant because at 1.0 a calibrated world scores 0 on every row: the
/// objective goes flat and the search random-walks. The raw worst is reported beside the scored
/// one so the width is chosen from what the rows actually read.
fn dead_zone(sds: f64) -> f64 {
    sds * ms::SD_REL_REF
}

/// HOW A READ IS PRICED beyond feasibility: the dead zone, and with `-noregress` the outgoing
/// recipe's distance from the record on every fitness row (`reference_distances`). A row the
/// candidate holds as close to its record as the recipe costs nothing; a row further out costs the
/// difference, whatever the dead zone says. The release rule is exactly that comparison, and a dead
/// zone alone let every row drift half an anchor sd for free, which is how a search whose members
/// all scored well produced none that could replace the recipe. The primary arm only: the
/// transport arm is a check that the mechanism carries, not a recipe being replaced.
struct Objective {
    dead: f64,
    reference: Option<Vec<(&'static str, f64)>>,
    /// THE GAP ROWS (`-gap`): the rows a release must bring inside their record band, made a
    /// feasibility condition on the primary arm like the realism and mechanism classes. Priced,
    /// a near miss on one cost less than the regressions it saved, and a search under
    /// `-noregress` traded them away member after member. The seed worlds are judged without
    /// them (`without_gap`).
    gap: Vec<&'static str>,
    /// THE CLASSES A READ MUST PASS (`-gate`), the primary arm's: the verdict's own by default
    /// (realism and mechanism). With `fidelity` the bands a search otherwise only prices -- the
    /// macro panel's, the channels', the bond's -- gate too, which is what a calibration SET needs,
    /// every member of one having to pass every class at the verdict's ensemble. Priced, a failed
    /// band is one dead zone against a row's worth of gain, so an archive fills with members that
    /// buy a row by leaving a band: of search-v40's 300, 238 failed a class at 200 paths, and 3
    /// were admissible. The TRANSPORT arm keeps the verdict's default classes at any setting -- it
    /// is a check that the mechanism carries to another market, not a member being built.
    classes: Vec<ms::GateClass>,
    /// THE ROWS A SEED IS REPORTED ON and not gated on: `without_gap` moves the gap rows here, so
    /// a seed world's read still says which of them it misses (`Read::watch_miss`). Read off the
    /// gate's labels instead, that report was blind to a row graded by its ratio, which carries
    /// no `band:` label, and to every row once the transport arm failed and `gate_fail` named
    /// that arm alone.
    watch: Vec<&'static str>,
    /// THE ROWS HELD TO A DISTANCE (`-hold ROW<=D`): a candidate whose row reads further from its
    /// record than the bar, in the judge's units (`judge_distance`), on any read of its primary
    /// arm is infeasible. A set is graded on each row's MEDIAN member distance, and the loss
    /// trades rows: four archives seeded inside every bar ended with pooled vol 36-40 points from
    /// its band's middle and the tail hedge 0.11-0.17 from its record whatever they were seeded
    /// from, because seeding sets where an archive starts and the loss where it ends. The seed
    /// worlds are exempt, as they are from `-gap`.
    hold: Vec<(&'static str, f64)>,
    /// THE STAGED READ (`-stage P`): the first P paths are read alone, and a candidate they reject
    /// is rejected there; one they pass reads the rest, and the whole is the unstaged read bit for
    /// bit, because path k is a function of the world, the horizon, the seed and k alone
    /// (`sim_path_range`). Of a set search's reads 95% are rejections, each paid at full price.
    /// 0 reads every path at once.
    stage: usize,
    /// `-stagecheck P`: read the prefix's verdict and record it (`Read::stage_fail`) without acting
    /// on it, so how often the full read overturns a prefix's rejection can be measured
    stage_check: bool,
    /// THE PROJECTED ROW (`-project DIAL:ROW`): its reading on every read (`Read::held`), from
    /// which the next child's dial is solved instead of stepped
    project_row: Option<&'static str>,
}

impl Objective {
    /// the same dead zone without the reference or the gap rows: the transport arm's pricing
    fn plain(&self) -> Objective {
        Objective {
            dead: self.dead,
            reference: None,
            gap: Vec::new(),
            classes: ms::gate_default(),
            watch: Vec::new(),
            hold: Vec::new(),
            stage: 0,
            stage_check: false,
            project_row: None,
        }
    }

    /// the same pricing with the gap rows watched, not gated: the seed worlds' (see `main`), read
    /// whole, since they root the lineages and set the bars
    fn without_gap(&self) -> Objective {
        Objective {
            dead: self.dead,
            reference: self.reference.clone(),
            gap: Vec::new(),
            classes: self.classes.clone(),
            watch: self.gap.clone(),
            hold: Vec::new(),
            stage: 0,
            stage_check: false,
            project_row: self.project_row,
        }
    }

    /// the same judgement read whole: what a staged read applies to its prefix
    fn unstaged(&self) -> Objective {
        Objective {
            dead: self.dead,
            reference: self.reference.clone(),
            gap: self.gap.clone(),
            classes: self.classes.clone(),
            watch: self.watch.clone(),
            hold: self.hold.clone(),
            stage: 0,
            stage_check: false,
            project_row: self.project_row,
        }
    }
}

/// Reads of the `-noregress` recipe its reference is the mean of.
const NOREGRESS_READS: u64 = 6;

/// THE READ STREAMS. An ensemble read at seed `s` runs path k at `s + k * PATH_STRIDE`
/// (`sim_paths`), so two reads share paths whenever their seeds differ by `t * PATH_STRIDE` with
/// |t| under the path count, not only when they are equal. Every stream therefore steps by
/// `READ_STRIDE`, which is 2209 mod the prime `PATH_STRIDE`: within a stream two reads can only
/// meet a million paths apart, and the offsets are chosen so no two streams meet either
/// (`read_streams_share_no_path` sweeps it). The streams past `-seed`: the seed pool and the
/// holdout's train at `k * READ_STRIDE`, the holdout's fresh at `HOLDOUT_FRESH_OFFSET`, the
/// `-noregress` reference at `NOREGRESS_OFFSET`, the candidates at `CANDIDATE_OFFSET`, the `-project`
/// reads at `PROJECT_OFFSET`.
#[cfg(test)]
const PATH_STRIDE: u64 = 7919;
const READ_STRIDE: u64 = 1_000_003;
const HOLDOUT_FRESH_OFFSET: u64 = 991;
const NOREGRESS_OFFSET: u64 = 7;
const CANDIDATE_OFFSET: u64 = 3301;
/// the `-project` slope and the readings of members a resumed archive brings without one
const PROJECT_OFFSET: u64 = 5501;
/// The `-project` slope's step, as a share of the dial's range, and its paired reads: two pairs on a
/// twentieth of the range read the Nasdaq slope 3.35 against a secant of 4.0 at 200 paths, and a
/// negative slope at 40.
const PROJECT_STEP: f64 = 0.10;
const PROJECT_SLOPE_READS: u64 = 4;
/// The rows a staged read's prefix must fail before the candidate is rejected there (`-stage`).
const STAGE_MIN_FAILS: usize = 2;

/// A reading at eight significant digits, as the checkpoint writes every number: the projection is
/// solved from these, so a fresh run, a resumed one and the other twin, whose readings differ in the
/// last ulps, step a child's dial alike.
fn round8(x: f64) -> f64 {
    g8(x).parse().unwrap_or(x)
}

/// The seed of a candidate's read in seed slot `slot` (`evals + rep`). Candidates stepped by
/// `PATH_STRIDE` once, which made consecutive reads one window sliding a path at a time: a
/// second rep re-read all but one path of the first, and the seed noise read between them was
/// no noise at all. Recorded as `candidateSeeds`, so an archive searched that way does not resume
/// under this one unforced.
fn candidate_seed(base: i64, slot: u64) -> u64 {
    (base as u64).wrapping_add(CANDIDATE_OFFSET.wrapping_add(slot.wrapping_mul(READ_STRIDE)))
}

/// The outgoing recipe's distance from the record on every fitness row: the mean of
/// `NOREGRESS_READS` reads at the search's own ensemble, each priced as `one_read` prices a
/// candidate (`record_distances`).
fn reference_distances(
    w: &World,
    anchors: Anchors,
    paths: usize,
    years: usize,
    base: i64,
) -> Vec<(&'static str, f64)> {
    let reads: Vec<Vec<(&'static str, f64)>> = (1..=NOREGRESS_READS)
        .map(|j| {
            let s = (base as u64).wrapping_add(NOREGRESS_OFFSET + j * READ_STRIDE);
            read_distances(w, anchors, paths, years, s)
        })
        .collect();
    reads[0]
        .iter()
        .enumerate()
        .map(|(i, (name, _))| {
            (
                *name,
                reads.iter().map(|r| r[i].1).sum::<f64>() / reads.len() as f64,
            )
        })
        .collect()
}

// ---- evaluation ---------------------------------------------------------------------------

/// Feasible on every rep, and the SUM over rows of each row's distance past the dead zone. The
/// reading is the WORST across reps, never the mean: a world that passes only on a lucky seed
/// has not passed.
#[derive(Clone)]
struct Read {
    feasible: bool,
    /// THE OBJECTIVE: `sum(max(0, term - dead))` over every row of every arm. Inside the record's
    /// own error a row contributes nothing; outside it every row counts, and a row cannot be
    /// bought below the dead zone because its excess is paid in full. The worst row alone put no
    /// pressure on any other, and an archive built on it drifted out to the binding row's level
    /// on every row (median member 5 rows past the dead zone against the default's 1).
    score: f64,
    /// The worst row's term, and its name: what a member is furthest from, for the reports.
    raw: f64,
    worst: String,
    /// MEAN over the reps, not the hardest one: a descriptor describes the world, where `raw`
    /// deliberately describes its worst seed.
    desc: Vec<f64>,
    /// THE WHOLE SIGNATURE: `fitness`'s summed loss over every row, both arms when a transport
    /// counterpart is set, as a mean over the reps. `raw` is one row, and a world can improve
    /// that row by degrading every other; this is what says whether it did. Meaningful only
    /// for a feasible reading -- an infeasible one never measured its extreme rows.
    total: f64,
    /// WHICH GATE ROWS FAILED, empty when feasible: `worstRow` is a fitness row and says nothing
    /// about rejection.
    gate_fail: Vec<String>,
    /// How far this world's SCORE moved across its own repetitions, max minus min. Free to
    /// compute and it is the noise scale the archive needs: a score difference smaller than this
    /// is a seed draw, not a better world. Zero at `-reps 1`, where `noise_reading` takes a read
    /// of its own instead.
    spread: f64,
    /// Every rep and both arms ran. False when the quality bar stopped the evaluation early:
    /// the score is the maximum over the reps plus the transport arm, so once the reps so far
    /// exceed the bar nothing later can bring it back under, and the rest is not run. Such a
    /// reading is rejected as before, but its `spread` and `desc` are partial and must not feed
    /// the noise estimate.
    complete: bool,
    /// THE WATCHED ROWS THIS READ MISSES (`Objective::watch`), in the order they were named: a
    /// seed world's gap rows. Empty for a candidate, which watches nothing, and for a read that
    /// left before the table was read.
    watch_miss: Vec<&'static str>,
    /// THE PREFIX'S VERDICT under `-stage`/`-stagecheck`: what the first P paths failed, empty when
    /// they passed; `None` when the read was not staged
    stage_fail: Option<Vec<String>>,
    /// the projected row's reading (`Objective::project_row`), NaN without one
    held: f64,
}

/// One seed's reading.  Every quantity the fidelity table grades is read at its record's horizon
/// and the extreme rows at their anchors', cut from the main ensemble where the horizon is shorter,
/// so when `-years` is the longest horizon nothing is simulated twice; a candidate that fails a
/// gate those readings cannot change never pays for them.  An infeasible candidate's extreme rows
/// stay out of its score and its worst-row search, so a rejected candidate's `worstRow` names a
/// pooled row.
///
/// `evaluate` stops at the first seed that fails, because feasibility needs every seed.
fn one_read(
    w: &World,
    anchors: Anchors,
    paths: usize,
    years: usize,
    s: u64,
    obj: &Objective,
) -> Read {
    // the verdict world: every derived series and the macro panel graded at the anchor set's
    // dials where the candidate leaves them off, so feasibility is the bundle's, as a recipe's
    // verdict is; the primary is bit-identical, so the table's horizons read `w`
    let vw = ms::verdict_world(anchors, w);
    if obj.stage == 0 || obj.stage >= paths {
        return read_paths(
            w,
            anchors,
            &ms::sim_paths(&vw, paths, years, s),
            years,
            s,
            obj,
        );
    }
    let mut main = ms::sim_path_range(&vw, 0, obj.stage, years, s);
    let head = read_paths(w, anchors, &main, years, s, &obj.unstaged());
    let stage_fail = if head.feasible {
        Vec::new()
    } else {
        head.gate_fail.clone()
    };
    // two rows failing on the prefix, not one: a single near-edge row flips back on the whole read
    // (7 of 18 feasible candidates were rejected that way at P = 50 in a measured run), two did not once
    if stage_fail.len() >= STAGE_MIN_FAILS && !obj.stage_check {
        return Read {
            gate_fail: stage_fail.iter().map(|f| format!("stage: {f}")).collect(),
            stage_fail: Some(stage_fail),
            ..head
        };
    }
    main.extend(ms::sim_path_range(
        &vw,
        obj.stage,
        paths - obj.stage,
        years,
        s,
    ));
    Read {
        stage_fail: Some(stage_fail),
        ..read_paths(w, anchors, &main, years, s, obj)
    }
}

/// One seed's reading of an ensemble already simulated (`one_read`): every path in `main`, path k
/// read at seed `s + k * PATH_STRIDE`.
#[expect(
    clippy::too_many_lines,
    reason = "one read scored in the order the Scala harness's oneRead scores it; splitting it \
              would scatter the terms the twins' scores must add up in the same order"
)]
fn read_paths(
    w: &World,
    anchors: Anchors,
    main: &[ms::Path],
    years: usize,
    s: u64,
    obj: &Objective,
) -> Read {
    let dead = obj.dead;
    let paths = main.len();
    let st = ms::measure(main, years);
    // THE VERDICT'S READINGS: vol, the typical year, return per vol, kurtosis, the clustering lags
    // and the crash rate are gated at their records' horizons (`gate_checks_at`), as a recipe's
    // verdict gates them, so the search and the verdict never disagree on those rows because they
    // read different horizons.
    // A GATE NO TABLE READING CAN CHANGE (`gate_reads_table`) fails at those horizons exactly as it
    // fails here, so a candidate that fails one is rejected before their ensembles are simulated:
    // the table's gates are then not read, and neither priced nor named.
    let default = obj.classes.clone();
    let own = ms::gate_checks(anchors, &st);
    let early = own
        .iter()
        .any(|(nm, ok, cls)| !ok && default.contains(cls) && !ms::gate_reads_table(nm));
    let hr = if early {
        ms::HorizonReadings::default()
    } else {
        ms::horizon_readings(anchors, &st, Some(main), years, paths, s, w, true)
    };
    let banded = &hr.banded;
    // at its record's horizon where the table read it, as the verdict reads it
    let held = obj.project_row.map_or(f64::NAN, |nm| {
        banded.get(nm).copied().unwrap_or_else(|| {
            ms::fit_targets(anchors)
                .into_iter()
                .find(|(n, _, _, _)| *n == nm)
                .map_or(f64::NAN, |(_, get, _, _)| get(&st))
        })
    });
    let checks = if early {
        own.into_iter()
            .filter(|(nm, _, _)| !ms::gate_reads_table(nm))
            .collect()
    } else {
        ms::gate_checks_at(anchors, &st, banded)
    };
    let bad = checks
        .iter()
        .filter(|(_, ok, cls)| !ok && default.contains(cls))
        .count();
    let gated = bad == 0;
    // THE FIDELITY BANDS THAT ARE NOT FITNESS ROWS -- the macro panel's, the channels', the
    // variance-ratio profile, the bond's -- were invisible to the search: neither gated (a
    // fidelity band flips on a seed at 60 paths, and feasibility has to hold on every seed) nor
    // scored (no fitness row reads them). An archive built blind to them had 139 of 145 members
    // failing one, and a recipe has to pass every class. Each failed band now costs one dead
    // zone, the one priced term in the score: a flip on one seed is a nudge, a band a member
    // sits outside on every seed is a row's worth of excess. Named in `gate_fail` for a
    // feasible candidate, so the log says which.
    let fid_fail: Vec<String> = checks
        .iter()
        .filter(|(_, ok, cls)| !ok && *cls == ms::GateClass::Fidelity)
        .map(|(nm, _, _)| format!("fidelity: {nm}"))
        .collect();
    // THE RECORD BANDS, read at the record's own horizon: a miss costs a dead zone, as a failed
    // fidelity band does, plus its distance past the edge, so the search has a slope back inside.
    // the gap rows' bands, read on this read: a miss on one is a failed gate (see `Objective::gap`)
    let gap_miss = if gated {
        gap_misses(anchors, &obj.gap, &hr, &st)
    } else {
        Vec::new()
    };
    // the rows a seed is reported on, where the table was read: an early exit leaves `banded`
    // empty, and a banded row without a reading counts as a miss
    let watch_miss = if early {
        Vec::new()
    } else {
        gap_misses(anchors, &obj.watch, &hr, &st)
    };
    // the held rows' distances on this read, in the judge's units: past a bar is a failed gate
    let hold_miss: Vec<&'static str> = if gated {
        obj.hold
            .iter()
            .filter(|(nm, bar)| hold_distance(anchors, nm, &hr, &st).is_none_or(|d| d > *bar))
            .map(|(nm, _)| *nm)
            .collect()
    } else {
        Vec::new()
    };
    let feasible = gated && gap_miss.is_empty() && hold_miss.is_empty();
    let band_miss: Vec<(&str, f64)> = ms::record_band_terms(anchors, banded)
        .into_iter()
        .filter(|(_, t)| feasible && *t > 0.0)
        .collect();
    let gate_fail: Vec<String> = if feasible {
        fid_fail
            .iter()
            .cloned()
            .chain(band_miss.iter().map(|(nm, _)| format!("band: {nm}")))
            .collect()
    } else if gated {
        gap_miss
            .iter()
            .map(|nm| format!("gap: {nm}"))
            .chain(hold_miss.iter().map(|nm| format!("hold: {nm}")))
            .collect()
    } else {
        default
            .iter()
            .flat_map(|cls| {
                checks
                    .iter()
                    .filter(move |(_, ok, c)| !ok && c == cls)
                    .map(|(nm, _, _)| nm.clone())
            })
            .collect()
    };

    let ex = if feasible {
        hr.extreme_scores()
    } else {
        HashMap::new()
    };
    let (total, rows) = ms::fitness(anchors, &st, &ex);
    // AGAINST THE OUTGOING RECIPE (`-noregress`): each row's excess over the recipe's distance from
    // its record, in `fitness` row order as the reference was taken
    let regress = if feasible {
        regressions(anchors, obj, &rows, banded)
    } else {
        Vec::new()
    };
    let scored: Vec<(f64, &str)> = rows
        .iter()
        .filter(|(nm, _, _, _)| feasible || !ms::extreme_target_names().contains(nm))
        .map(|(nm, _, _, term)| (*term, *nm))
        .collect();
    // the worst row, ties broken by name exactly as the Scala harness's `max` on a (term, name)
    // pair does
    let worst = scored
        .iter()
        .copied()
        .fold((f64::NEG_INFINITY, ""), |a, b| {
            if b.0 > a.0 || (b.0 == a.0 && b.1 > a.1) {
                b
            } else {
                a
            }
        });
    // in row order, a plain left fold, as the Scala harness's `sum` is; then the fidelity bands,
    // then the record bands, then the regressions
    let score = scored
        .iter()
        .map(|(term, _)| (term - dead).max(0.0))
        .sum::<f64>()
        + dead * fid_fail.len() as f64
        + band_miss.iter().map(|(_, t)| dead + t).sum::<f64>()
        + regress.iter().map(|(_, e)| e).sum::<f64>();
    // the log names a regression past a dead zone; smaller ones are priced but not listed
    let gate_fail: Vec<String> = gate_fail
        .into_iter()
        .chain(
            regress
                .iter()
                .filter(|(_, e)| *e > dead)
                .map(|(nm, _)| format!("regress: {nm}")),
        )
        .collect();
    Read {
        feasible,
        score,
        raw: worst.0,
        worst: worst.1.to_string(),
        desc: desc_of(&st),
        total,
        gate_fail,
        spread: 0.0,
        complete: true,
        watch_miss,
        stage_fail: None,
        held,
    }
}

/// The gap rows (`Objective::gap`) whose band this read misses: its banded reading outside the
/// record's joint band, or no reading at all.
/// A held row's distance on one read, in the judge's units (`-hold`): a single-history row's (the
/// multi-year and timing rows) is the record's percentile points from 50 among the read's own
/// histories, where the verdict places it, infinite under `EXTREME_MIN_HISTORIES` of them; any
/// other row's is `judge_distance`'s.
fn hold_distance(
    anchors: Anchors,
    nm: &str,
    hr: &ms::HorizonReadings,
    st: &ms::WorldStats,
) -> Option<f64> {
    if ms::is_multi_year(nm) {
        let record = ms::fit_targets(anchors)
            .into_iter()
            .find(|(n, _, _, _)| *n == nm)
            .map(|(_, _, t, _)| t)?;
        return Some(match hr.extreme.get(nm) {
            Some(xs) if xs.len() >= ms::EXTREME_MIN_HISTORIES => {
                (ms::anchor_pctile(xs, record) as f64 - 50.0).abs()
            }
            _ => f64::INFINITY,
        });
    }
    ms::judge_distance(anchors, nm, st, &hr.banded)
}

fn gap_misses(
    anchors: Anchors,
    gap: &[&'static str],
    hr: &ms::HorizonReadings,
    st: &ms::WorldStats,
) -> Vec<&'static str> {
    let banded = &hr.banded;
    gap.iter()
        .copied()
        .filter(|nm| {
            if let Some(b) = anchors.record_bands.iter().find(|b| b.name == *nm) {
                return b.misses(banded.get(nm).copied().unwrap_or(f64::NAN));
            }
            // an extreme row is graded by where the record falls among single histories of its
            // own length, the verdict's `miss` for it: outside 5-95, or too few histories to
            // place it; a multi-year row by its histories' joint band
            if ms::is_multi_year(nm) {
                let record = ms::fit_targets(anchors)
                    .into_iter()
                    .find(|(n, _, _, _)| n == nm)
                    .map_or(f64::NAN, |(_, _, t, _)| t);
                return !hr
                    .history_band
                    .get(nm)
                    .is_some_and(|(lo, hi)| (*lo..=*hi).contains(&record));
            }
            if ms::extreme_target_names().contains(nm) {
                let record = ms::fit_targets(anchors)
                    .into_iter()
                    .find(|(n, _, _, _)| n == nm)
                    .map_or(f64::NAN, |(_, _, t, _)| t);
                return !hr.extreme.get(nm).is_some_and(|xs| {
                    xs.len() >= ms::EXTREME_MIN_HISTORIES
                        && (ms::EXTREME_PCT_BAND.0..=ms::EXTREME_PCT_BAND.1)
                            .contains(&ms::anchor_pctile(xs, record))
                });
            }
            // a row without a record band is graded by its ratio to the anchor's target, which is
            // the verdict's `miss` for it (`FidelityRow::miss`)
            ms::fit_targets(anchors)
                .into_iter()
                .find(|(n, _, _, _)| n == nm)
                .is_some_and(|(_, get, target, _)| {
                    let ratio = get(st) / target;
                    !(ms::FIDELITY_RATIO_BAND.0..=ms::FIDELITY_RATIO_BAND.1).contains(&ratio)
                })
        })
        .collect()
}

/// AGAINST THE OUTGOING RECIPE (`-noregress`): each fitness row's excess over the recipe's distance
/// from its record, in `fitness` row order as the reference was taken; empty without a reference.
fn regressions(
    anchors: Anchors,
    obj: &Objective,
    rows: &[(&'static str, f64, f64, f64)],
    banded: &HashMap<&'static str, f64>,
) -> Vec<(&'static str, f64)> {
    obj.reference.as_ref().map_or_else(Vec::new, |reference| {
        ms::record_distances(anchors, rows, banded)
            .iter()
            .zip(reference)
            .map(|((name, d), (_, r))| (*name, (d - r).max(0.0)))
            .collect()
    })
}

/// A member with its readings on the two holdout streams.
type HoldoutRow = (Member, Read, Read);

#[expect(
    clippy::too_many_arguments,
    reason = "the ensemble, the seed stream and the bar are what a reading means; passing them \
              in a struct would hide the thing the checkpoint records"
)]
fn evaluate(
    w: &World,
    anchors: Anchors,
    paths: usize,
    years: usize,
    seeds: &[u64],
    obj: &Objective,
    bar: Option<f64>,
) -> Read {
    let mut reads: Vec<Read> = Vec::with_capacity(seeds.len());
    // every seed up to and including the first infeasible one, or up to the first that puts
    // the running maximum over the bar: the rest cannot change the answer
    let mut cut = false;
    for &s in seeds {
        let r = one_read(w, anchors, paths, years, s, obj);
        let stop = !r.feasible;
        let over = r.feasible && bar.is_some_and(|b| r.score > b);
        reads.push(r);
        if over && reads.len() < seeds.len() {
            cut = true;
        }
        if stop || over {
            break;
        }
    }
    let hardest = reads
        .iter()
        .fold(&reads[0], |a, b| if b.raw > a.raw { b } else { a })
        .clone();
    let desc = (0..DESC_NAMES.len())
        .map(|i| reads.iter().map(|r| r.desc[i]).sum::<f64>() / reads.len() as f64)
        .collect();
    let hi = reads
        .iter()
        .map(|r| r.score)
        .fold(f64::NEG_INFINITY, f64::max);
    let lo = reads.iter().map(|r| r.score).fold(f64::INFINITY, f64::min);
    let total = reads.iter().map(|r| r.total).sum::<f64>() / reads.len() as f64;
    Read {
        feasible: reads.iter().all(|r| r.feasible),
        // the worst seed's score, as the worst seed's row is the reported one
        score: hi,
        raw: hardest.raw,
        worst: hardest.worst,
        desc,
        total,
        // the read that FAILED says why, where there is one: the hardest read is the one with the
        // worst row, which on a rejected world is as often a read that passed
        gate_fail: reads
            .iter()
            .find(|r| !r.feasible)
            .map_or(hardest.gate_fail, |r| r.gate_fail.clone()),
        spread: hi - lo,
        complete: !cut,
        // a row any read misses, in the order the rows were named
        watch_miss: obj
            .watch
            .iter()
            .copied()
            .filter(|g| reads.iter().any(|r| r.watch_miss.contains(g)))
            .collect(),
        // the last read's: every read before it passed its prefix, or it would have stopped there
        stage_fail: reads.last().and_then(|r| r.stage_fail.clone()),
        held: reads.iter().map(|r| r.held).sum::<f64>() / reads.len() as f64,
    }
}

// ---- transport -------------------------------------------------------------------------------

/// SELECTING FOR TRANSPORT rather than testing it afterwards: a candidate is judged on the WORSE of
/// its two markets, so a world that fits the S&P by doing something the Nasdaq will not tolerate
/// never enters.  One dial vector cannot pass both sets (equity vol bands 14-18 and 22.2-31.5); the
/// MECHANISM transports and the market dials re-solve, which is the structure of the shipped
/// recipes.  So the arm is the counterpart world carrying the candidate's values on every searched
/// dial EXCEPT the market dials (`MARKET_DIALS`), which stay at the counterpart's, in either
/// direction.
struct Transport {
    name: String,
    anchors: Anchors,
    world: World,
    spec: String,
    /// the arm's share of the score (`-transportweight`): a primary-market search at 1 spent its
    /// generations on the counterpart's rows (search-v38: the S&P arm carried 5-6 of the seed's 7.1
    /// and the Nasdaq rows drifted). Feasibility in both markets stays a gate at any weight.
    weight: f64,
}

fn transport_of(name: &str, primary_spec: &str, weight: f64) -> Transport {
    let Some((world, spec)) = ms::named_world(name) else {
        usage(&format!(
            "-transport names [{name}], which is not a release or recipe"
        ));
    };
    let spec = spec.unwrap_or("sp500");
    if spec == primary_spec {
        usage(&format!(
            "-transport {name} is anchored to [{spec}], the set already being searched; a transport arm has to be the OTHER market"
        ));
    }
    Transport {
        name: name.to_string(),
        anchors: ms::anchors_named(spec),
        world,
        spec: spec.to_string(),
        weight,
    }
}

/// THE MARKET DIALS: the searched dials that say which market a world is rather than how its
/// mechanism works, held at the counterpart's values on the transport arm. Named, not derived:
/// the set used to be "the dials on which the counterpart differs from the seed world", which
/// produced this list for every hand-built recipe and all thirty for `0.24.2-nasdaq`, a recipe
/// the search itself re-solved -- an arm holding everything judges the counterpart, not the
/// candidate. Every name must be a searched dial; the harness refuses to start otherwise.
/// The news channel's rate and size are a market's magnitudes, as `jumpVar` is; how the news
/// follows leverage and how much of it reverts are the mechanism, and transport. So are the
/// valuation dials -- how much of the fundamental beliefs see, their fade and horizon, the
/// extrapolation, the cycle and the mania's bust: each market was solved on its own (the S&P
/// default runs a belief share of 0.95 with its own leak), and a Nasdaq solution carried to the S&P
/// failed its stationarity row for reasons that say nothing about the mechanism under test.
const MARKET_DIALS: [&str; 16] = [
    "depth",
    "drift",
    "stress",
    "volOfVol",
    "jumpVar",
    "refuge",
    "slowShare",
    "newsRate",
    "newsSize",
    "beliefShare",
    "capYears",
    "beliefLeak",
    "beliefYears",
    "cycleSd",
    "cycleYears",
    "bustAmp",
];

/// Which searched dials the transport arm holds, in table order.
fn held() -> Vec<bool> {
    ranges()
        .iter()
        .map(|r| MARKET_DIALS.contains(&r.0))
        .collect()
}

fn transport_world(t: &Transport, dials: &[f64]) -> World {
    let held = held();
    let mut w = t.world;
    for (i, r) in ranges().iter().enumerate() {
        if !held[i] {
            (r.3)(&mut w, dials[i]);
        }
    }
    w
}

/// One candidate's reading: the primary arm alone, or both arms when a transport counterpart is
/// set -- the scores add, feasible means feasible in both, and the worst row is the worse arm's.
/// The descriptors stay the primary world's: they describe the world the archive holds, and the
/// transport arm is a different world by construction.
#[expect(
    clippy::too_many_arguments,
    reason = "the ensemble and the seed stream are what a reading means; passing them in a struct \
              would hide the thing the checkpoint records"
)]
fn judge(
    base: &World,
    dials: &[f64],
    anchors: Anchors,
    t: Option<&Transport>,
    paths: usize,
    years: usize,
    seeds: &[u64],
    obj: &Objective,
    bar: Option<f64>,
) -> Read {
    let a = evaluate(
        &world_of(base, dials),
        anchors,
        paths,
        years,
        seeds,
        obj,
        bar,
    );
    let Some(tr) = t else { return a };
    // a candidate that fails its primary market is rejected whatever the other one says,
    // and the transport arm is a whole second evaluation; so is one whose primary arm alone
    // is over the bar, because the arms' scores add
    if !a.feasible {
        return a;
    }
    if bar.is_some_and(|b| a.score > b) {
        return Read {
            complete: false,
            ..a
        };
    }
    // the arm is priced at its weight, so its own early exit is the bar over the weight: past
    // that, the weighted sum is over the bar whatever the primary arm scored (a weight of 0
    // leaves the arm a gate and never cuts it short)
    let b = evaluate(
        &transport_world(tr, dials),
        tr.anchors,
        paths,
        years,
        seeds,
        &obj.plain(),
        bar.map(|x| x / tr.weight),
    );
    let score = a.score + tr.weight * b.score;
    let (raw, worst) = if tr.weight * b.raw > a.raw {
        (tr.weight * b.raw, format!("{}: {}", tr.spec, b.worst))
    } else {
        (a.raw, a.worst.clone())
    };
    Read {
        feasible: a.feasible && b.feasible,
        score,
        raw,
        worst,
        desc: a.desc,
        total: a.total + tr.weight * b.total,
        // the transport arm's rows carry their market, as `worst` does, so a row that only
        // exists there -- the macro rows, when the counterpart runs the panel -- is not read as
        // a primary-market failure. The primary arm is feasible here, so an infeasible reading
        // names the transport arm's failures alone, and a feasible one both arms' misses.
        gate_fail: {
            let tb = b.gate_fail.iter().map(|r| format!("{}: {r}", tr.spec));
            if b.feasible {
                a.gate_fail.iter().cloned().chain(tb).collect()
            } else {
                tb.collect()
            }
        },
        spread: a.spread.max(b.spread),
        complete: a.complete && b.complete,
        // the primary arm's: the transport arm watches nothing, is read whole and projects nothing
        watch_miss: a.watch_miss,
        stage_fail: a.stage_fail,
        held: a.held,
    }
}

/// the OBJECTIVE, as a digest of every row a candidate is judged on -- each set's name, then each
/// row's name, target and weight, then each record band's edges, for the primary set and the
/// transport arm's, the dials that arm holds, and the `-noregress` reference. Recorded with the
/// settings so a resume refuses
/// an archive scored under different weights: re-freezing one spread changes the loss every
/// member was admitted on, and nothing else in the checkpoint would show it. FNV-1a over the
/// rows as text, numbers at eight significant digits, so both twins write the same digest.
/// The reference's VALUES, not only its name: a model change that moves the recipe's readings
/// refuses a resume across it. `-export` and `-prune` score nothing and carry the archive's
/// recorded digest instead (`main`), so they never read the reference.
/// The held rows as the checkpoint and the digest spell them: `ROW<=D,...` at eight significant
/// digits, `(none)` when there are none, the same text from both twins.
fn hold_text(hold: &[(&'static str, f64)]) -> String {
    if hold.is_empty() {
        "(none)".to_string()
    } else {
        hold.iter()
            .map(|(nm, d)| format!("{nm}<={}", g8(*d)))
            .collect::<Vec<_>>()
            .join(",")
    }
}

fn objective_digest(
    anchors: Anchors,
    transport: Option<&Transport>,
    noregress: Option<(&str, &[(&'static str, f64)])>,
    gap: &[&str],
    hold: &[(&'static str, f64)],
) -> String {
    let mut text = String::new();
    for a in std::iter::once(anchors).chain(transport.map(|t| t.anchors)) {
        let _ = writeln!(text, "{}", a.name);
        for (name, _, target, weight) in ms::fit_targets(a) {
            let _ = writeln!(text, "{name}|{}|{}", g8(target), g8(weight));
        }
        for b in a.record_bands {
            let (lo, hi) = b.band();
            let _ = writeln!(text, "band|{}|{}|{}", b.name, g8(lo), g8(hi));
        }
    }
    if let Some(tr) = transport {
        let _ = writeln!(text, "held|{}", MARKET_DIALS.join(","));
        // written only off 1, so an archive scored before the weight existed keeps its digest
        if tr.weight != 1.0 {
            let _ = writeln!(text, "tweight|{}", g8(tr.weight));
        }
    }
    if !gap.is_empty() {
        let _ = writeln!(text, "gap|{}", gap.join(","));
    }
    // written only when a row is held, so an archive built before `-hold` keeps its digest
    if !hold.is_empty() {
        let _ = writeln!(text, "hold|{}", hold_text(hold));
    }
    if let Some((name, reference)) = noregress {
        let _ = writeln!(text, "noregress|{name}");
        for (row, d) in reference {
            let _ = writeln!(text, "reg|{row}|{}", g8(*d));
        }
    }
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in text.bytes() {
        h ^= u64::from(b);
        h = h.wrapping_mul(0x0100_0000_01b3);
    }
    format!("{h:016x}")
}

/// THE SEED WORLDS' OWN VALUES, digested into the archive's settings (`seedWorlds`). A member is
/// its searched dials on top of the world it was seeded from, by NAME, and an unreleased recipe may
/// be re-solved under its name: an archive resumed or exported after that would rebuild every
/// member on a different world and say nothing. Every field of every seed world, in the sidecar's
/// own key format and the archive's own width, in pool order.
fn seed_worlds_digest(pool: &[(String, World)]) -> String {
    let mut text = String::new();
    for (name, w) in pool {
        let _ = writeln!(text, "{name}");
        for l in ms::world_json_body_fmt(w, &|x| g8(x)) {
            let _ = writeln!(text, "{}", l.trim());
        }
    }
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in text.bytes() {
        h ^= u64::from(b);
        h = h.wrapping_mul(0x0100_0000_01b3);
    }
    format!("{h:016x}")
}

// ---- the archive ---------------------------------------------------------------------------

/// One archive member: which seed world carries the non-searched dials, the searched dials
/// themselves, and the reading that admitted it.
#[derive(Clone)]
struct Member {
    name: String,
    dials: Vec<f64>,
    score: f64,
    raw: f64,
    worst: String,
    desc: Vec<f64>,
}

fn names() -> Vec<&'static str> {
    ranges().iter().map(|r| r.0).collect()
}

/// THE SETTINGS ARE PART OF THE CHECKPOINT: without the ensemble and rules that judged them the
/// members cannot be reproduced, and a resume that changed a flag would mix standards silently.
/// Key/value so a later version copes with a row it does not recognise.
fn write_archive(
    dir: &str,
    arc: &[Member],
    generation: u64,
    evals: u64,
    noise: (f64, u64),
    cfg: &[(String, String)],
) {
    let mut out = String::new();
    let _ = writeln!(
        out,
        "name\tscore\traw\tworstRow\t{}\t{}",
        names().join("\t"),
        DESC_NAMES.join("\t")
    );
    for m in arc {
        let ds: Vec<String> = m.dials.iter().map(|x| g8(*x)).collect();
        let bs: Vec<String> = m.desc.iter().map(|x| g8(*x)).collect();
        let _ = writeln!(
            out,
            "{}\t{:.6}\t{:.6}\t{}\t{}\t{}",
            m.name,
            m.score,
            m.raw,
            m.worst,
            ds.join("\t"),
            bs.join("\t")
        );
    }
    write_text(&format!("{dir}/archive.tsv"), &out);

    let mut st = String::from("key\tvalue\n");
    let _ = writeln!(st, "gen\t{generation}");
    let _ = writeln!(st, "evals\t{evals}");
    // The seed-noise accumulator is resumable STATE, not a setting: the resume guard ignores it, and
    // a resume that restarted it would admit inside the noise for its first generations.  `g8` so both
    // twins write one text.
    let _ = writeln!(st, "noiseSum\t{}", g8(noise.0));
    let _ = writeln!(st, "noiseN\t{}", noise.1);
    for (k, v) in cfg {
        let _ = writeln!(st, "{k}\t{v}");
    }
    write_text(&format!("{dir}/state.tsv"), &st);
}

fn read_state(dir: &str) -> HashMap<String, String> {
    let Some(text) = read_text(&format!("{dir}/state.tsv")) else {
        return HashMap::new();
    };
    let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
    let mut m = HashMap::new();
    // the first format was a two-column `gen\tevals` header with one row under it; read it so an
    // archive written before the settings landed still resumes
    if lines.first().is_some_and(|l| l.starts_with("gen\t")) {
        let v: Vec<&str> = lines.get(1).unwrap_or(&"0\t0").split('\t').collect();
        m.insert("gen".into(), (*v.first().unwrap_or(&"0")).to_string());
        m.insert("evals".into(), (*v.get(1).unwrap_or(&"0")).to_string());
        return m;
    }
    for l in lines.iter().skip(1) {
        if let Some((k, v)) = l.split_once('\t') {
            m.insert(k.to_string(), v.to_string());
        }
    }
    m
}

fn read_archive(dir: &str) -> Vec<Member> {
    let Some(text) = read_text(&format!("{dir}/archive.tsv")) else {
        return Vec::new();
    };
    // THE HEADER IS THE CONTRACT: the dial columns are read by position, so an archive written
    // with a different dial table -- fewer dials, or the same count in another order -- must be
    // refused by name, not by width. A row-width check let a 28-dial archive read as 30 with two
    // descriptors taken for dials.
    let expect = format!(
        "name\tscore\traw\tworstRow\t{}\t{}",
        names().join("\t"),
        DESC_NAMES.join("\t")
    );
    let head = text.lines().next().unwrap_or_default();
    if head != expect {
        usage(&format!(
            "{dir}/archive.tsv was written with different columns; it has [{head}]\n  this binary reads [{expect}]"
        ));
    }
    let want = 4 + ranges().len() + DESC_NAMES.len();
    text.lines()
        .skip(1)
        .filter(|l| !l.trim().is_empty())
        .map(|l| {
            let f: Vec<&str> = l.split('\t').collect();
            if f.len() != want {
                usage(&format!(
                    "{dir}/archive.tsv has a row of {} fields where {want} are expected",
                    f.len()
                ));
            }
            let num = |v: &str| -> f64 {
                v.parse().unwrap_or_else(|_| {
                    usage(&format!("{dir}/archive.tsv has a malformed number [{v}]"))
                })
            };
            Member {
                name: f[0].to_string(),
                dials: f
                    .iter()
                    .skip(4)
                    .take(ranges().len())
                    .map(|x| num(x))
                    .collect(),
                desc: f
                    .iter()
                    .skip(4 + ranges().len())
                    .take(DESC_NAMES.len())
                    .map(|x| num(x))
                    .collect(),
                score: num(f[1]),
                raw: num(f[2]),
                worst: f[3].to_string(),
            }
        })
        .collect()
}

/// World rows (`-seedworlds`, `-noregressset`): a TSV whose header is `name` and the searched dials
/// in table order, each row `base` with those dials.
fn world_rows(file: &str, base: &World, flag: &str) -> Vec<(String, World)> {
    let text = read_text(file).unwrap_or_else(|| usage(&format!("{flag} {file} cannot be read")));
    let mut lines = text.lines().filter(|l| !l.trim().is_empty());
    let head: Vec<&str> = lines.next().unwrap_or_default().split('\t').collect();
    let nm = names();
    if head.first() != Some(&"name") || head[1..] != nm[..] {
        usage(&format!(
            "{flag} {file}: the header must be `name` and the searched dials in table order"
        ));
    }
    lines
        .map(|l| {
            let f: Vec<&str> = l.split('\t').collect();
            if f.len() != head.len() {
                usage(&format!("{flag} {file}: a row of {} fields", f.len()));
            }
            let dials: Vec<f64> = f[1..]
                .iter()
                .map(|x| {
                    x.parse()
                        .unwrap_or_else(|_| usage(&format!("{flag} {file}: not a number [{x}]")))
                })
                .collect();
            (f[0].to_string(), world_of(base, &dials))
        })
        .collect()
}

/// FNV-1a over `text`, as the checkpoint's digests are taken.
fn fnv(text: &str) -> String {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in text.bytes() {
        h ^= u64::from(b);
        h = h.wrapping_mul(0x0100_0000_01b3);
    }
    format!("{h:016x}")
}

/// One read's distance from the record on every fitness row, priced as `one_read` prices a candidate
/// (`record_distances`).
fn read_distances(
    w: &World,
    anchors: Anchors,
    paths: usize,
    years: usize,
    s: u64,
) -> Vec<(&'static str, f64)> {
    let main = ms::sim_paths(w, paths, years, s);
    let st = ms::measure(&main, years);
    let hr = ms::horizon_readings(anchors, &st, Some(&main), years, paths, s, w, true);
    let (_, rows) = ms::fitness(anchors, &st, &hr.extreme_scores());
    ms::record_distances(anchors, &rows, &hr.banded)
}

/// The median of `xs`: the middle value, or the mean of the middle two.
fn median(xs: &[f64]) -> f64 {
    let mut v = xs.to_vec();
    v.sort_by(f64::total_cmp);
    let n = v.len();
    if n % 2 == 1 {
        v[n / 2]
    } else {
        (v[n / 2 - 1] + v[n / 2]) / 2.0
    }
}

/// THE OUTGOING SET'S MEDIAN MEMBER (`-noregressset`): each fitness row's median over the members of
/// their distance from the record, one read a member at the search's ensemble. A calibration set is
/// released when every graded row's median member distance is no worse than the outgoing set's, and
/// a child priced against one recipe drifts off that centre on every row the recipe was close on: in
/// a set loop against 0.24.5-nasdaq's 30, two of 23 admitted children sat within the outgoing median
/// on clustering lag 1, against 11 of the 22 worlds they came from. Read once and kept in
/// `reference.tsv` under a digest of the set, the ensemble and the seed, since a set loop restarts the
/// harness every round.
#[expect(
    clippy::too_many_arguments,
    reason = "the set, the ensemble and the seed are what the reference means; the directory is \
              where it is kept"
)]
fn set_reference(
    rows: &[(String, World)],
    file_text: &str,
    anchors: Anchors,
    paths: usize,
    years: usize,
    base: i64,
    dir: &str,
) -> (String, Vec<(&'static str, f64)>) {
    let digest = fnv(&format!(
        "{file_text}|{}|{paths}|{years}|{base}",
        anchors.name
    ));
    let names_now: Vec<&'static str> = ms::fit_targets(anchors).iter().map(|t| t.0).collect();
    let cached = read_text(&format!("{dir}/reference.tsv")).and_then(|t| {
        let mut lines = t.lines();
        (lines.next()? == format!("digest\t{digest}")).then_some(())?;
        let got: Vec<(&'static str, f64)> = lines
            .filter_map(|l| {
                let (n, v) = l.split_once('\t')?;
                let at = names_now.iter().position(|x| *x == n)?;
                Some((names_now[at], v.parse().ok()?))
            })
            .collect();
        (!got.is_empty()).then_some(got)
    });
    if let Some(r) = cached {
        return (digest, r);
    }
    let reads: Vec<Vec<(&'static str, f64)>> = rows
        .iter()
        .enumerate()
        .map(|(k, (_, w))| {
            let s = (base as u64)
                .wrapping_add(NOREGRESS_OFFSET + (NOREGRESS_READS + 1 + k as u64) * READ_STRIDE);
            read_distances(w, anchors, paths, years, s)
        })
        .collect();
    // at eight significant digits, as the checkpoint writes every number: the twins' readings differ
    // in the last ulps, and a fresh run, a resumed one and the other twin all price against this text
    let r: Vec<(&'static str, f64)> = reads[0]
        .iter()
        .enumerate()
        .map(|(i, (name, _))| {
            let xs: Vec<f64> = reads.iter().map(|m| m[i].1).collect();
            let m = median(&xs);
            (*name, g8(m).parse().unwrap_or(m))
        })
        .collect();
    let mut out = format!("digest\t{digest}\n");
    for (n, v) in &r {
        let _ = writeln!(out, "{n}\t{}", g8(*v));
    }
    write_text(&format!("{dir}/reference.tsv"), &out);
    (digest, r)
}

/// A member's key in `held.tsv`: its seed world and its dials as the archive writes them, so a
/// member read back from the archive finds the reading it was admitted with.
fn held_key(name: &str, dials: &[f64]) -> String {
    let ds: Vec<String> = dials.iter().map(|x| g8(*x)).collect();
    format!("{name}|{}", ds.join(","))
}

/// The projected row's reading of each member (`-project`), beside the archive.
fn read_held(dir: &str) -> HashMap<String, f64> {
    read_text(&format!("{dir}/held.tsv"))
        .map(|t| {
            t.lines()
                .skip(1)
                .filter_map(|l| {
                    let (k, v) = l.rsplit_once('\t')?;
                    Some((k.to_string(), v.parse().ok()?))
                })
                .collect()
        })
        .unwrap_or_default()
}

/// The current members' readings, rewritten with the archive.
fn write_held(dir: &str, arc: &[Member], held: &HashMap<String, f64>) {
    let mut out = String::from("member\theld\n");
    for m in arc {
        let k = held_key(&m.name, &m.dials);
        if let Some(v) = held.get(&k) {
            let _ = writeln!(out, "{k}\t{}", g8(*v));
        }
    }
    write_text(&format!("{dir}/held.tsv"), &out);
}

/// Append `lines` to `dir/file`, writing `header` first when the file is new.
fn append_tsv(dir: &str, file: &str, header: &str, lines: &[String]) {
    let path = format!("{dir}/{file}");
    let fresh = !std::path::Path::new(&path).exists();
    let mut f = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
        .unwrap_or_else(|e| usage(&format!("cannot append to {path}: {e}")));
    if fresh {
        let _ = writeln!(f, "{header}");
    }
    for l in lines {
        let _ = writeln!(f, "{l}");
    }
}

/// Append, never rewrite: the log of a multi-day run outlives any one process.
fn append_log(dir: &str, lines: &[String]) {
    let path = format!("{dir}/log.tsv");
    let mut f = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
        .unwrap_or_else(|e| usage(&format!("cannot append to {path}: {e}")));
    for l in lines {
        if let Err(e) = writeln!(f, "{l}") {
            usage(&format!("cannot append to {path}: {e}"));
        }
    }
}

/// What a candidate's reading adds to the running seed noise the replacement margin reads, if
/// anything: the gap between two independent reads of one world, both feasible and run to the
/// end. With two or more reps that is the candidate's own `spread`. With one there is no gap to
/// read, and a 0 would say the score has no noise, so every better single read would replace a
/// member and the archive would fill with lucky reads: `second` is then a further read of the
/// same world, taken for this alone, and the reading is its distance from the first. The
/// candidate's score is its first read either way.
fn noise_reading(reps: usize, r: &Read, second: Option<&Read>) -> Option<f64> {
    if !(r.feasible && r.complete) {
        None
    } else if reps > 1 {
        Some(r.spread)
    } else {
        second
            .filter(|s| s.feasible && s.complete)
            .map(|s| (s.score - r.score).abs())
    }
}

/// Admit a feasible candidate.  Returns the archive and whether the candidate is IN IT AFTER THE
/// TRIM -- a replacement leaves the count unchanged, so the log needs the flag.
///
/// REPLACING NEEDS A MARGIN: a candidate takes the place of the NEAREST member inside `sep` only
/// when it beats that member by more than the running seed noise.  Without one a 4317-generation
/// run sorted an archive whose whole score range, 0.018, sat inside one seed sd of 0.044.  The dead
/// zone does not help: it discounts a row, not a comparison of two worlds.
///
/// TRIMMING DROPS THE LEAST DISTINCT, never the worst-scoring: of the closest pair in descriptor
/// space, each descriptor normalised by its range across the archive, the worse scorer goes.
/// Trimming by score is an optimiser and narrows the spread the archive exists to keep (signed
/// lag-1 range 0.057 -> 0.032 over that run; 0.043 -> 0.074 for spread-keeping in a 40-generation
/// A/B).  Feasibility is the membership test; score was never meant to be a second one.
/// THE STOPPING RULE, the report's: the spread is the descriptor span over everything admitted
/// so far, each descriptor as a fraction of its span over the whole run, averaged; the points of
/// it added over the last two blocks, a block being an eighth of the run and at least
/// `BLOCK_FLOOR` generations. Deliberately crude, and it says which numbers it read: a stopping
/// rule nobody can check is worse than no stopping rule. It reads the spread because admissions
/// never fall under spread-keeping and the best score is one row of the product; two blocks,
/// because one block of a search this noisy proves nothing. None until the run is four blocks
/// long or while no descriptor has any span.
///
/// THE FLOOR IS 75 BECAUSE THE RULER IS THE SPAN SO FAR. The report reads a finished run
/// against its final span; live, only the span so far exists, and it is still small while the
/// archive is young, so a short flat stretch reads as closed. Replayed over three archived
/// searches, a floor of 10 stopped them at generation 54-84 and a floor of 50 forfeited a tenth
/// of one set's final spread; 75 stops at 496-516 keeping 96-97% of it, and cannot fire before
/// generation 300.
const BLOCK_FLOOR: u64 = 75;

fn spread_added(trace: &[(u64, Vec<f64>)], gens: u64) -> Option<(f64, u64)> {
    let blk = (gens / 8).max(BLOCK_FLOOR);
    if gens < 4 * blk || trace.is_empty() {
        return None;
    }
    let nd = trace[0].1.len();
    let span = |before: u64| -> Vec<f64> {
        (0..nd)
            .map(|j| {
                let xs: Vec<f64> = trace
                    .iter()
                    .filter(|(g, d)| *g < before && !d[j].is_nan())
                    .map(|(_, d)| d[j])
                    .collect();
                if xs.is_empty() {
                    f64::NAN
                } else {
                    xs.iter().copied().fold(f64::MIN, f64::max)
                        - xs.iter().copied().fold(f64::MAX, f64::min)
                }
            })
            .collect()
    };
    let end = span(u64::MAX);
    let at = |before: u64| -> Option<f64> {
        let r: Vec<f64> = span(before)
            .iter()
            .zip(&end)
            .filter(|(a, b)| !a.is_nan() && **b > 0.0)
            .map(|(a, b)| a / b)
            .collect();
        if r.is_empty() {
            None
        } else {
            Some(r.iter().sum::<f64>() / r.len() as f64)
        }
    };
    Some((100.0 * (at(gens)? - at(gens - 2 * blk)?), blk))
}

fn admit(arc: Vec<Member>, m: Member, sep: f64, keep: usize, noise: f64) -> (Vec<Member>, bool) {
    // the nearest member inside `sep`, not the first found; strict `<` keeps the earliest on a tie,
    // as the Scala twin's fold does
    let near = arc
        .iter()
        .enumerate()
        .map(|(i, o)| (i, apart(&o.dials, &m.dials)))
        .filter(|&(_, d)| d < sep)
        .fold(None, |best: Option<(usize, f64)>, (i, d)| match best {
            Some((_, bd)) if bd <= d => best,
            _ => Some((i, d)),
        })
        .map(|(i, _)| i);
    let dials = m.dials.clone();
    let (mut next, placed) = match near {
        None => {
            let mut v = arc;
            v.push(m);
            (v, true)
        }
        Some(i) if m.score < arc[i].score - noise => {
            let mut v = arc;
            v[i] = m;
            (v, true)
        }
        Some(_) => (arc, false),
    };
    while next.len() > keep {
        match least_distinct(&next) {
            Some(i) => {
                next.remove(i);
            }
            None => {
                // no descriptors to judge by: fall back to the old rule rather than loop forever
                next.sort_by(|a, b| a.score.total_cmp(&b.score));
                next.truncate(keep);
            }
        }
    }
    // in the archive after the trim: an appended candidate can be the worse half of the closest pair
    let took = placed && next.iter().any(|o| o.dials == dials);
    (next, took)
}

/// THE DESCRIPTOR SPACE admission and coverage share: each descriptor's range across the archive,
/// so none of them dominates on units alone; empty when no member carries descriptors, which is
/// only an archive written before they existed.
fn desc_span(arc: &[Member]) -> Vec<f64> {
    let k = arc.iter().map(|m| m.desc.len()).max().unwrap_or(0);
    (0..k)
        .map(|j| {
            let xs: Vec<f64> = arc
                .iter()
                .filter_map(|m| m.desc.get(j).copied())
                .filter(|x| x.is_finite())
                .collect();
            if xs.is_empty() {
                0.0
            } else {
                xs.iter().copied().fold(f64::NEG_INFINITY, f64::max)
                    - xs.iter().copied().fold(f64::INFINITY, f64::min)
            }
        })
        .collect()
}

/// Two members' distance in that space, over the descriptors both read.
fn desc_dist(a: &Member, b: &Member, span: &[f64]) -> f64 {
    (0..span.len())
        .filter(|&j| span[j] > 0.0)
        .filter_map(|j| {
            let (x, y) = (a.desc.get(j)?, b.desc.get(j)?);
            if x.is_finite() && y.is_finite() {
                Some(((x - y) / span[j]).powi(2))
            } else {
                None
            }
        })
        .sum::<f64>()
        .sqrt()
}

/// COVERAGE WEIGHTS: how much of the archive's behaviour each member stands for. An archive is not
/// a sample -- where its members crowd says where the search looked, not how likely a market is --
/// so a consumer counting "the share of worlds that pass" counts a near-duplicate twice. Each
/// weight is the inverse of the member's Gaussian-kernel density in the descriptor space admission
/// keeps spread in, the bandwidth the median nearest-neighbour distance, scaled to mean 1: 0.6
/// counts as 0.6 of a world. All 1 when there are no descriptors or no spread to read.
fn coverage_weights(arc: &[Member]) -> Vec<f64> {
    let n = arc.len();
    let span = desc_span(arc);
    if n < 2 || span.is_empty() {
        return vec![1.0; n];
    }
    let d: Vec<Vec<f64>> = (0..n)
        .map(|i| (0..n).map(|j| desc_dist(&arc[i], &arc[j], &span)).collect())
        .collect();
    let mut nn: Vec<f64> = (0..n)
        .map(|i| {
            (0..n)
                .filter(|&j| j != i)
                .map(|j| d[i][j])
                .fold(f64::INFINITY, f64::min)
        })
        .collect();
    nn.sort_by(f64::total_cmp);
    let h = nn[n / 2];
    if !(h > 0.0 && h.is_finite()) {
        return vec![1.0; n];
    }
    // the kernel through `exp_det`, so the Scala harness exports the same bytes
    let raw: Vec<f64> = (0..n)
        .map(|i| {
            let mut s = 0.0;
            for x in &d[i] {
                let z = x / h;
                let e = -0.5 * z * z;
                // `exp_det` builds 2^k from raw exponent bits and does not guard its range; a
                // member far outside the bandwidth contributes under 1e-304 either way
                s += if e < -700.0 { 0.0 } else { ms::exp_det(e) };
            }
            1.0 / s
        })
        .collect();
    let mut tot = 0.0;
    for r in &raw {
        tot += r;
    }
    raw.iter().map(|r| r * n as f64 / tot).collect()
}

/// The member whose removal costs the least behavioural spread: of the closest pair in normalised
/// descriptor space, the one that scores worse. `None` when no member carries descriptors, which
/// is only an archive written before they existed.
fn least_distinct(arc: &[Member]) -> Option<usize> {
    let n = arc.len();
    let span = desc_span(arc);
    if n < 2 || span.is_empty() {
        return None;
    }
    let mut worst = (f64::INFINITY, 0usize);
    for i in 0..n {
        for j in (i + 1)..n {
            let d = desc_dist(&arc[i], &arc[j], &span);
            if d < worst.0 {
                // of the pair, lose the one that scores worse
                let loser = if arc[i].score > arc[j].score { i } else { j };
                worst = (d, loser);
            }
        }
    }
    Some(worst.1)
}

/// The archive as a worlds JSON in the sidecar's own key format -- what a consumer runs a strategy
/// across.
fn export_worlds(dir: &str, file: &str, seed_for: &dyn Fn(&str) -> World) {
    let arc = read_archive(dir);
    if arc.is_empty() {
        usage(&format!("no archive in {dir} to export"));
    }
    let cover = coverage_weights(&arc);
    let bodies: Vec<String> = arc
        .iter()
        .enumerate()
        .map(|(k, m)| {
            let w = world_of(&seed_for(&m.name), &m.dials);
            format!(
                "  {{\n    \"member\": {k},\n    \"seededFrom\": \"{}\",\n    \"score\": {:.6},\n    \"worstRow\": \"{}\",\n    \"coverageWeight\": {},\n    \"world\": {{\n{}\n    }}\n  }}",
                m.name,
                m.score,
                m.worst,
                java_format_f(cover[k], 0, 6),
                // at the archive's own width, not the report's six decimals -- this block is what a consumer
                // reconstructs a world from -- and joined on `,\n`, since `world_json_body_fmt` returns the fields
                // without separators
                ms::world_json_body_fmt(&w, &|x| g8(x)).join(",\n")
            )
        })
        .collect();
    write_text(file, &format!("[\n{}\n]\n", bodies.join(",\n")));
    let (s, s2) = cover
        .iter()
        .fold((0.0, 0.0), |(s, s2), w| (s + w, s2 + w * w));
    println!(
        "wrote {} worlds to {file}; coverage weights' effective sample {}",
        arc.len(),
        java_format_f(s * s / s2, 0, 1)
    );
}

// ---- file io ---------------------------------------------------------------------------------
// `\n` explicitly and everywhere: these files are read by the Scala harness and by `git diff`, and
// a CR would make an archive written here differ from the same archive written there.

fn write_text(path: &str, body: &str) {
    if let Err(e) = std::fs::write(path, body.as_bytes()) {
        usage(&format!("cannot write {path}: {e}"));
    }
}

fn read_text(path: &str) -> Option<String> {
    std::fs::read(path)
        .ok()
        .map(|b| String::from_utf8_lossy(&b).replace('\r', ""))
}

// ---- cli --------------------------------------------------------------------------------------

fn usage(msg: &str) -> ! {
    if !msg.is_empty() {
        eprintln!("error: {msg}\n");
    }
    eprintln!(
        "usage: market_sim_search [options]
  -out DIR      ; where the archive, log and checkpoint live (default ./search)
  -anchors A    ; sp500 or nasdaq (default sp500)
  -seeds S      ; comma-separated release/recipe names to seed from; default every one that
                ;   passes today's gate.  Non-searched dials (the channels, the macro panel)
                ;   come from the seed, so seeding from a recipe searches THAT configuration
  -paths N      ; ensemble paths per evaluation (default 60)
  -years Y      ; years per path (default 80)
  -reps K       ; seeds a candidate must pass feasibility on, all of them (default 2); at 1 each
                ;   generation reads one passing candidate once more, for the seed noise alone
  -sigma S      ; mutation sd as a fraction of each dial's range (default 0.07; 0.20 breaks),
                ;   the news rate's in ln(1 + rate)
  -keep N       ; archive size cap (default 40)
  -sep D        ; minimum separation between members in normalised dial space (default 0.12)
  -bar M        ; THE QUALITY BAR: a feasible candidate enters only if its score is at most M
                ;   times its seed world's, judged the same way (default 1.0: at least as
                ;   consistent with the record as the world it was seeded from; 0 = no bar,
                ;   distance alone admits).  Members above it are dropped on resume
  -pop P        ; candidates per generation, checkpointed after each (default 8)
  -gens G       ; generations to run; 0 runs until the spread closes, or until killed (default 0)
  -close P      ; stop when the last two blocks of generations added under P points of the
                ;   archive's spread, the report's CLOSED rule, a block being an eighth of the
                ;   run and at least 75 generations, so never before generation 300
                ;   (default 2; 0 = never stop on its own)
  -dead D       ; dead zone in anchor sds; a row inside it scores 0 (default 0.5).  At 1.0 a
                ;   calibrated world scores 0 on every row and the objective goes flat
  -seed S       ; base seed (default 20260813)
  -holdout K    ; re-score every archive member on K seeds the search never selected on, at
                ;   the recorded ensemble, and exit
  -prune        ; keep only the members that passed BOTH arms of the last -holdout, moving
                ;   the rest to dropped.tsv rather than deleting them, and exit
  -force        ; resume even though the recorded settings differ from the flags given
  -transport N  ; a counterpart recipe in the OTHER anchor set (e.g. 0.24.1-nasdaq).  Every
                ;   candidate is then judged on the WORSE of its two markets, carrying its own
                ;   values on every searched dial except the ones that counterpart moved away
                ;   from the default, which stay at the counterpart's.  Roughly doubles the cost
                ;   an evaluation
  -transportweight W ; the transport arm's share of the score (default 0.25); feasibility in
                ;   both markets stays a gate at any weight
  -cov F        ; the share of children stepped along the ARCHIVE'S OWN SHAPE rather than one
                ;   dial at a time (default 0): its covariance in the dials' search coordinates,
                ;   shrunk toward the independent step and scaled to the same expected step
                ;   length, so only the direction changes.  Dials move together in this model --
                ;   `depth` carries pooled volatility and the typical year alike -- and an
                ;   independent step spends most children across the grain
  -covshrink S  ; how far that covariance is pulled back toward the independent step, 0 to 1
                ;   (default 0.3): insurance against a shape read from few members
  -export F     ; write the archive as a worlds JSON and exit
  -hold BARS    ; comma-separated ROW<=D (e.g. 'equity vol %<=36.6,tail hedge corr<=0.08'): a graded
                ;   row a candidate must hold within D of its record on every read of its primary
                ;   arm to be feasible, D in the units a set is judged in -- percentile points from
                ;   the band's middle on a row with a record band, the record's percentile points
                ;   from 50 among the read's histories on a single-history row (the multi-year and
                ;   timing rows), |ln(model/target)| on any other.
                ;   What -gap is to a band's edges, for a distance: a priced row is traded away, and
                ;   an archive ends where the loss pulls it whatever it was seeded from.  The seed
                ;   worlds are exempt
  -gate C       ; comma-separated gate classes a candidate must pass on every read of its primary
                ;   arm to be feasible: realism, mechanism, fidelity or all (default
                ;   realism,mechanism, the verdict's own).  With fidelity the bands a search
                ;   otherwise only prices gate too, which every member of a calibration set has to
                ;   pass anyway; realism is always in
  -noregressset F ; -noregress against a SET's median member: F as -seedworlds, one read each
  -noregress R  ; price every row a candidate holds further from its record than recipe R does,
                ;   by the difference (R read at the search's ensemble, the mean of six reads):
                ;   the release rule, which a dead zone alone lets every row drift inside
  -seedset KV   ; comma-separated DIAL=VALUE (e.g. 'overshoot=0.8,recessBase=1') applied to every
                ;   seed world before the search starts: the lineage roots at the recipe moved to a
                ;   form found by hand, and the quality bar is judged against that world.  Searched
                ;   dials only; recorded in the checkpoint with the seed worlds' digest
  -fix DIALS    ; comma-separated dials (e.g. 'disasterSize,disasterOvershoot') every child keeps
                ;   at its parent's value, so at the seed's: the dials read off the record rather
                ;   than solved.  The draws are made as without it, so the other dials step alike
  -stage P      ; read a candidate's first P paths alone and reject it there if they fail it;
                ;   a candidate they pass reads the rest, and the whole is the unstaged read bit
                ;   for bit (default 0: every path at once)
  -stagecheck P ; read the same prefix and record its verdict in stage.tsv beside the full read's,
                ;   never acting on it: how often a full read overturns a prefix's rejection
  -project D:R  ; solve dial D for row R's record instead of stepping it ('drift:return per vol'):
                ;   a child's D is its parent's plus the parent's miss on R over R's slope in D
  -seedworlds F ; the seed worlds as a TSV of `name` and the searched dials in table order: each row
                ;   is the first -seeds world with those dials and roots its own lineage and bar
  -gap ROWS     ; comma-separated graded rows (e.g. 'kurtosis,up-day share %') a candidate
                ;   must hold inside their bands on every read of its primary arm to be feasible:
                ;   the rows a release has to close, which a priced miss lets a search trade away.
                ;   An extreme row is held by the record's percentile among the read's single
                ;   histories (5-95), a multi-year row by their joint band.
                ;   The seed worlds are exempt (they root the lineages); -holdout holds them to it
  -fidelity L   ; comma-separated PxY ensembles (e.g. 20x40,30x60).  Score the frozen pool at
                ;   -paths/-years and at each of these, report how well each RANKS the worlds
                ;   against the reference and what it costs, and exit.  Measured against 60x80:
                ;   30x80 is 2.0x for a rank correlation of 0.993 and no feasibility
                ;   disagreement.  Paths govern feasibility agreement and years govern ranking;
                ;   20 paths is a floor, below which the worst-crash row stops resolving"
    );
    std::process::exit(1)
}

struct Cfg {
    out: String,
    anchor_spec: String,
    seed_spec: String,
    paths: usize,
    years: usize,
    reps: usize,
    sigma: f64,
    keep: usize,
    sep: f64,
    pop: usize,
    gens: u64,
    close: f64,
    dead: f64,
    base: i64,
    holdout: usize,
    bar: f64,
    prune: bool,
    force: bool,
    export_to: String,
    fidelity: String,
    transport: String,
    transport_weight: f64,
    noregress: String,
    gap: String,
    hold: String,
    /// dials held at the parent's value (`-fix`): read off the record, never searched
    fix: String,
    /// searched dials moved on every seed world before the search starts (`-seedset`)
    seed_set: String,
    gate: String,
    /// the share of children drawn from the archive's own covariance
    cov: f64,
    cov_shrink: f64,
    /// paths read before a candidate may be rejected (`-stage`); 0 reads them all at once
    stage: usize,
    /// the same prefix read and recorded, never acted on (`-stagecheck`)
    stage_check: usize,
    /// `DIAL:ROW` solved rather than searched (`-project`)
    project: String,
    /// further seed worlds, one TSV row each (`-seedworlds`)
    seed_worlds: String,
    /// the outgoing SET whose median member a row is priced against (`-noregressset`)
    noregress_set: String,
}

fn parse_args() -> Cfg {
    let mut c = Cfg {
        out: "search".into(),
        anchor_spec: "sp500".into(),
        seed_spec: String::new(),
        paths: 60,
        years: 80,
        reps: 2,
        sigma: 0.07,
        keep: 40,
        sep: 0.12,
        pop: 8,
        gens: 0,
        close: 2.0,
        dead: 0.5,
        base: 20260813,
        holdout: 0,
        bar: 1.0,
        prune: false,
        force: false,
        export_to: String::new(),
        fidelity: String::new(),
        transport: String::new(),
        transport_weight: 0.25,
        noregress: String::new(),
        gap: String::new(),
        hold: String::new(),
        fix: String::new(),
        seed_set: String::new(),
        gate: "realism,mechanism".into(),
        cov: 0.0,
        cov_shrink: 0.3,
        stage: 0,
        stage_check: 0,
        project: String::new(),
        seed_worlds: String::new(),
        noregress_set: String::new(),
    };
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut i = 0;
    let need = |i: &mut usize, flag: &str| -> String {
        *i += 1;
        args.get(*i)
            .cloned()
            .unwrap_or_else(|| usage(&format!("{flag} wants a value")))
    };
    while i < args.len() {
        match args[i].as_str() {
            "-out" => c.out = need(&mut i, "-out"),
            "-anchors" => c.anchor_spec = need(&mut i, "-anchors"),
            "-seeds" => c.seed_spec = need(&mut i, "-seeds"),
            "-paths" => c.paths = num(&need(&mut i, "-paths"), "-paths"),
            "-years" => c.years = num(&need(&mut i, "-years"), "-years"),
            "-reps" => c.reps = num(&need(&mut i, "-reps"), "-reps"),
            "-sigma" => c.sigma = fnum(&need(&mut i, "-sigma"), "-sigma"),
            "-keep" => c.keep = num(&need(&mut i, "-keep"), "-keep"),
            "-sep" => c.sep = fnum(&need(&mut i, "-sep"), "-sep"),
            "-pop" => c.pop = num(&need(&mut i, "-pop"), "-pop"),
            "-gens" => c.gens = num::<u64>(&need(&mut i, "-gens"), "-gens"),
            "-close" => c.close = fnum(&need(&mut i, "-close"), "-close"),
            "-dead" => c.dead = fnum(&need(&mut i, "-dead"), "-dead"),
            "-seed" => c.base = num::<i64>(&need(&mut i, "-seed"), "-seed"),
            "-holdout" => c.holdout = num(&need(&mut i, "-holdout"), "-holdout"),
            "-bar" => c.bar = fnum(&need(&mut i, "-bar"), "-bar"),
            "-prune" => c.prune = true,
            "-force" => c.force = true,
            "-cov" => c.cov = fnum(&need(&mut i, "-cov"), "-cov"),
            "-covshrink" => c.cov_shrink = fnum(&need(&mut i, "-covshrink"), "-covshrink"),
            "-export" => c.export_to = need(&mut i, "-export"),
            "-fidelity" => c.fidelity = need(&mut i, "-fidelity"),
            "-transport" => c.transport = need(&mut i, "-transport"),
            "-transportweight" => {
                c.transport_weight = fnum(&need(&mut i, "-transportweight"), "-transportweight");
            }
            "-noregress" => c.noregress = need(&mut i, "-noregress"),
            "-gate" => c.gate = need(&mut i, "-gate"),
            "-hold" => c.hold = need(&mut i, "-hold"),
            "-gap" => c.gap = need(&mut i, "-gap"),
            "-fix" => c.fix = need(&mut i, "-fix"),
            "-seedset" => c.seed_set = need(&mut i, "-seedset"),
            "-stage" => c.stage = num(&need(&mut i, "-stage"), "-stage"),
            "-stagecheck" => c.stage_check = num(&need(&mut i, "-stagecheck"), "-stagecheck"),
            "-project" => c.project = need(&mut i, "-project"),
            "-seedworlds" => c.seed_worlds = need(&mut i, "-seedworlds"),
            "-noregressset" => c.noregress_set = need(&mut i, "-noregressset"),
            "-h" | "-help" | "--help" => usage(""),
            a => usage(&format!("unrecognized arg [{a}]")),
        }
        i += 1;
    }
    c
}

fn num<T: std::str::FromStr>(v: &str, flag: &str) -> T {
    v.parse()
        .unwrap_or_else(|_| usage(&format!("{flag} wants an integer, got [{v}]")))
}

fn fnum(v: &str, flag: &str) -> f64 {
    v.parse()
        .unwrap_or_else(|_| usage(&format!("{flag} wants a number, got [{v}]")))
}

// ---- main -------------------------------------------------------------------------------------

#[expect(
    clippy::too_many_lines,
    clippy::cognitive_complexity,
    reason = "a sequence of independent modes -- export, prune, holdout, search -- each short, and               splitting them would separate each from the settings it is judged under"
)]
fn main() {
    let c = parse_args();
    if c.reps < 1 {
        usage("-reps wants at least 1");
    }
    if c.sigma <= 0.0 {
        usage("-sigma wants a positive fraction");
    }
    if c.pop < 1 {
        usage("-pop wants at least 1");
    }
    if !(0.0..=1.0).contains(&c.cov) || !(0.0..=1.0).contains(&c.cov_shrink) {
        usage("-cov and -covshrink are shares, 0 to 1");
    }
    if let Err(e) = std::fs::create_dir_all(&c.out) {
        usage(&format!("cannot create {}: {e}", c.out));
    }

    let anchors = ms::anchors_named(&c.anchor_spec);
    let transport = if c.transport.is_empty() {
        None
    } else {
        if !(c.transport_weight >= 0.0 && c.transport_weight.is_finite()) {
            usage("-transportweight is a share of the score, 0 or more");
        }
        Some(transport_of(
            &c.transport,
            &c.anchor_spec,
            c.transport_weight,
        ))
    };
    // `releases()` stops at the last frozen row, so the current default is added first.  A seed is graded
    // against the anchor set it was verified against -- the Nasdaq recipes read equity vol 0.5 past
    // the dead zone on S&P anchors -- so the pool is restricted to the set being searched: an S&P
    // archive and a Nasdaq archive are separate products.
    let mut named: Vec<(String, World)> = vec![("current".to_string(), ms::default_world())];
    named.extend(ms::releases().into_iter().map(|(v, w)| (v.to_string(), w)));
    named.extend(
        ms::recipes()
            .into_iter()
            .map(|(n, w, _)| (n.to_string(), w)),
    );
    let all: Vec<(String, World)> = named
        .into_iter()
        .filter(|(n, _)| {
            let spec = ms::named_world(n).and_then(|(_, a)| a).unwrap_or("sp500");
            spec == c.anchor_spec
        })
        .collect();
    if all.is_empty() {
        usage(&format!(
            "no frozen world is anchored to [{}]",
            c.anchor_spec
        ));
    }
    let pool: Vec<(String, World)> = if c.seed_spec.is_empty() {
        all
    } else {
        let want: Vec<&str> = c.seed_spec.split(',').map(str::trim).collect();
        let got: Vec<(String, World)> = all
            .iter()
            .filter(|(n, _)| want.contains(&n.as_str()))
            .cloned()
            .collect();
        if got.len() != want.len() {
            let missing: Vec<&str> = want
                .iter()
                .filter(|n| !got.iter().any(|(g, _)| g == *n))
                .copied()
                .collect();
            usage(&format!(
                "-seeds names [{}], which is not a release or recipe",
                missing.join(", ")
            ));
        }
        got
    };
    let pool: Vec<(String, World)> = if c.seed_set.is_empty() {
        pool
    } else {
        let rs = ms::calibrate_ranges();
        let moves: Vec<(ms::Setter, f64)> = c
            .seed_set
            .split(',')
            .map(str::trim)
            .filter(|x| !x.is_empty())
            .map(|kv| {
                let (k, v) = kv
                    .split_once('=')
                    .unwrap_or_else(|| usage(&format!("-seedset wants DIAL=VALUE, got [{kv}]")));
                let x: f64 = v
                    .trim()
                    .parse()
                    .unwrap_or_else(|_| usage(&format!("-seedset [{kv}]: not a number")));
                let set = rs
                    .iter()
                    .find(|r| r.0 == k.trim())
                    .map(|r| r.3)
                    .unwrap_or_else(|| {
                        usage(&format!(
                            "-seedset names [{}], which is not a searched dial",
                            k.trim()
                        ))
                    });
                (set, x)
            })
            .collect();
        pool.into_iter()
            .map(|(n, mut w)| {
                for (set, x) in &moves {
                    set(&mut w, *x);
                }
                (n, w)
            })
            .collect()
    };
    // THE SEED ROWS (`-seedworlds`): worlds of the first seed's lineage given by their searched dials,
    // e.g. a calibration set's members moved by hand, so a search starts from all of them at once
    // the world that carries a world row's other dials: the first -seeds world
    let carrier: Option<World> = pool.first().map(|(_, w)| *w);
    let pool: Vec<(String, World)> = if c.seed_worlds.is_empty() {
        pool
    } else {
        let base = carrier
            .unwrap_or_else(|| usage("-seedworlds wants a -seeds world to carry the other dials"));
        let rows = world_rows(&c.seed_worlds, &base, "-seedworlds");
        if rows.iter().any(|(n, _)| pool.iter().any(|(p, _)| p == n)) {
            usage("-seedworlds: a row reuses a -seeds name");
        }
        if rows.is_empty() {
            usage(&format!("-seedworlds {} holds no world", c.seed_worlds));
        }
        // the rows ARE the seed worlds: the -seeds world only carries their other dials, so the
        // first row is member 0, the world a set loop treats as the recipe
        rows
    };
    let seed_world: HashMap<String, World> = pool.iter().cloned().collect();
    let world_for =
        |n: &str| -> World { seed_world.get(n).copied().unwrap_or_else(ms::default_world) };
    if let Some(t) = &transport {
        let missing: Vec<&str> = MARKET_DIALS
            .iter()
            .copied()
            .filter(|d| !names().contains(d))
            .collect();
        if !missing.is_empty() {
            usage(&format!(
                "the market dials name [{}], which is not a searched dial",
                missing.join(", ")
            ));
        }
        println!(
            "transport arm: {} ({}), holding the {} market dials of {} at its own values [{}]",
            t.name,
            t.spec,
            MARKET_DIALS.len(),
            ranges().len(),
            MARKET_DIALS.join(", ")
        );
    }

    // CHEAP FIDELITY: a smaller ensemble is good enough when it RANKS worlds as the reference does;
    // the losses need not agree.  Measured on the frozen pool at the S&P set against 60 x 80:
    //
    //     ensemble    rank corr   feasibility agrees   speedup
    //     20 x 40y        0.945           17 of 19        3.9x
    //     30 x 60y        0.952           19 of 19        2.2x
    //     30 x 80y        0.993           19 of 19        2.0x
    //     40 x 80y        1.000           19 of 19        1.5x
    //
    // Paths govern feasibility agreement and years govern ranking.  `-years` saves less than it looks:
    // the worst-crash row reads at its anchor's horizon, 100 years for the S&P, whatever `-years`
    // says.  20 paths is a floor, below which that row cannot place a record inside its band.
    // the -fidelity ranking reads other ensembles, which a reference taken at this one does not fit
    let plain = Objective {
        dead: dead_zone(c.dead),
        reference: None,
        gap: Vec::new(),
        classes: ms::gate_default(),
        watch: Vec::new(),
        hold: Vec::new(),
        stage: 0,
        stage_check: false,
        project_row: None,
    };
    if !c.fidelity.is_empty() {
        let ens = parse_ensembles(&c.fidelity);
        let seeds: Vec<u64> = (0..c.reps)
            .map(|k| (c.base as u64).wrapping_add(k as u64 * READ_STRIDE))
            .collect();
        println!(
            "cheap fidelity at {}, {} frozen worlds x {} reps",
            c.anchor_spec,
            pool.len(),
            c.reps
        );
        let t0 = std::time::Instant::now();
        let reference: Vec<Read> = pool
            .iter()
            .map(|(_, w)| {
                judge(
                    w,
                    &dials_of(w),
                    anchors,
                    transport.as_ref(),
                    c.paths,
                    c.years,
                    &seeds,
                    &plain,
                    None,
                )
            })
            .collect();
        let ref_secs = t0.elapsed().as_secs_f64() / pool.len() as f64;
        println!(
            "reference {} x {}y: {:.3} s an evaluation\n",
            c.paths, c.years, ref_secs
        );
        println!("  ensemble      rank corr   feasibility agrees   s/eval   speedup");
        let ref_raw: Vec<f64> = reference.iter().map(|r| r.raw).collect();
        let mut rows = String::from("paths\tyears\tworld\tfeasible\traw\trefFeasible\trefRaw\n");
        for (p, y) in ens {
            let t = std::time::Instant::now();
            let got: Vec<Read> = pool
                .iter()
                .map(|(_, w)| {
                    judge(
                        w,
                        &dials_of(w),
                        anchors,
                        transport.as_ref(),
                        p,
                        y,
                        &seeds,
                        &plain,
                        None,
                    )
                })
                .collect();
            let secs = t.elapsed().as_secs_f64() / pool.len() as f64;
            let raw: Vec<f64> = got.iter().map(|r| r.raw).collect();
            let agree = got
                .iter()
                .zip(&reference)
                .filter(|(a, b)| a.feasible == b.feasible)
                .count();
            println!(
                "  {p:>3} x {y:>3}y      {:>9.3}   {agree:>10} of {:<6}   {secs:>6.3}   {:>5.1}x",
                spearman(&raw, &ref_raw),
                pool.len(),
                ref_secs / secs
            );
            for (i, (nm, _)) in pool.iter().enumerate() {
                let _ = writeln!(
                    rows,
                    "{p}\t{y}\t{nm}\t{}\t{:.6}\t{}\t{:.6}",
                    got[i].feasible, got[i].raw, reference[i].feasible, reference[i].raw
                );
            }
        }
        write_text(&format!("{}/fidelity.tsv", c.out), &rows);
        println!("\nwrote {}/fidelity.tsv", c.out);
        return;
    }

    // THE REFERENCE (`-noregress`): the outgoing recipe read at this ensemble, before any candidate;
    // checked in every mode, read only where a candidate is scored (a search, `-holdout`)
    if !c.noregress.is_empty() && !c.noregress_set.is_empty() {
        usage("-noregress and -noregressset each set the reference; give one");
    }
    // `set:<digest>` names a set reference in the checkpoint and the objective digest
    let mut noregress_name = c.noregress.clone();
    let reference = if !c.noregress_set.is_empty() {
        let base = carrier.unwrap_or_else(|| {
            usage("-noregressset wants a -seeds world to carry the other dials")
        });
        let rows = world_rows(&c.noregress_set, &base, "-noregressset");
        if rows.is_empty() {
            usage(&format!("-noregressset {} holds no world", c.noregress_set));
        }
        let text = read_text(&c.noregress_set).unwrap_or_default();
        if c.prune || !c.export_to.is_empty() {
            None
        } else {
            let (digest, r) =
                set_reference(&rows, &text, anchors, c.paths, c.years, c.base, &c.out);
            noregress_name = format!("set:{digest}");
            println!(
                "noregress: the median member of {} ({} worlds) at {} x {}y; a row further from its record costs the difference",
                c.noregress_set,
                rows.len(),
                c.paths,
                c.years
            );
            Some(r)
        }
    } else if c.noregress.is_empty() {
        None
    } else {
        let (w, spec) = ms::named_world(&c.noregress).unwrap_or_else(|| {
            usage(&format!(
                "-noregress names [{}], which is not a release or recipe",
                c.noregress
            ))
        });
        if ms::anchors_named(spec.unwrap_or("sp500")).name != anchors.name {
            usage(&format!(
                "-noregress {} is anchored to another set than [{}]",
                c.noregress, c.anchor_spec
            ));
        }
        if c.prune || !c.export_to.is_empty() {
            None
        } else {
            let r = reference_distances(&w, anchors, c.paths, c.years, c.base);
            println!(
                "noregress: {} at {} x {}y, the mean of {NOREGRESS_READS} reads; a row further from its record costs the difference",
                c.noregress, c.paths, c.years
            );
            Some(r)
        }
    };
    // THE GAP ROWS (`-gap`): each must name a graded row of this anchor set -- a record band, or a
    // fitness row the verdict grades by its ratio to the target. An extreme row carries neither.
    let gap: Vec<&'static str> = c
        .gap
        .split(',')
        .map(str::trim)
        .filter(|g| !g.is_empty())
        .map(|g| {
            anchors
                .record_bands
                .iter()
                .find(|b| b.name == g)
                .map(|b| b.name)
                .or_else(|| {
                    ms::fit_targets(anchors)
                        .into_iter()
                        .map(|(n, _, _, _)| n)
                        .find(|n| *n == g)
                })
                .unwrap_or_else(|| {
                    usage(&format!(
                        "-gap names [{g}], which is not a graded row of [{}]",
                        c.anchor_spec
                    ))
                })
        })
        .collect();
    // THE HELD ROWS (`-hold ROW<=D,...`): each a graded row of this anchor set, single-history
    // rows included, the other extreme rows not, and a finite distance at or above 0 in the judge's
    // units
    let hold: Vec<(&'static str, f64)> = c
        .hold
        .split(',')
        .map(str::trim)
        .filter(|h| !h.is_empty())
        .map(|h| {
            let (row, bar) = h
                .split_once("<=")
                .unwrap_or_else(|| usage(&format!("-hold wants ROW<=D, got [{h}]")));
            let name = anchors
                .record_bands
                .iter()
                .map(|b| b.name)
                .chain(ms::fit_targets(anchors).into_iter().map(|(n, _, _, _)| n))
                .find(|n| {
                    *n == row.trim()
                        && (ms::is_multi_year(n) || !ms::extreme_target_names().contains(n))
                })
                .unwrap_or_else(|| {
                    usage(&format!(
                        "-hold names [{}], which is not a graded row of [{}]",
                        row.trim(),
                        c.anchor_spec
                    ))
                });
            let d = bar
                .trim()
                .parse::<f64>()
                .ok()
                .filter(|d| d.is_finite() && *d >= 0.0)
                .unwrap_or_else(|| {
                    usage(&format!("-hold {name}: [{}] is not a distance", bar.trim()))
                });
            (name, d)
        })
        .collect();
    // THE PROJECTED DIAL (`-project DIAL:ROW`): a searched dial and the graded row it is solved for
    let project: Option<(usize, &'static str, f64)> = (!c.project.trim().is_empty()).then(|| {
        let (dial, row) = c
            .project
            .split_once(':')
            .unwrap_or_else(|| usage("-project wants DIAL:ROW, e.g. 'drift:return per vol'"));
        let at = names()
            .iter()
            .position(|n| *n == dial.trim())
            .unwrap_or_else(|| {
                usage(&format!(
                    "-project names [{dial}], which is not a searched dial"
                ))
            });
        let (name, _, target, _) = ms::fit_targets(anchors)
            .into_iter()
            .find(|(n, _, _, _)| *n == row.trim())
            .unwrap_or_else(|| {
                usage(&format!(
                    "-project names [{row}], which is not a graded row of {}",
                    c.anchor_spec
                ))
            });
        (at, name, target)
    });
    if c.stage > 0 && c.stage_check > 0 {
        usage("-stage and -stagecheck each set the prefix; give one");
    }
    let obj = Objective {
        dead: dead_zone(c.dead),
        reference,
        gap,
        classes: ms::parse_gate(&c.gate),
        watch: Vec::new(),
        hold,
        stage: c.stage.max(c.stage_check),
        stage_check: c.stage_check > 0,
        project_row: project.map(|(_, row, _)| row),
    };

    let mut prior = read_state(&c.out);
    // `-export`, `-prune` and `-holdout` read no candidate, so they carry the archive's own scheme
    // (see `candidate_seed`): one written before the key was searched a path stride apart
    let reads_no_candidate = c.prune || !c.export_to.is_empty() || c.holdout > 0;
    let candidate_seeds = if reads_no_candidate {
        prior
            .get("candidateSeeds")
            .cloned()
            .unwrap_or_else(|| "path-stride".to_string())
    } else {
        "independent".to_string()
    };
    // likewise where the seed noise is read (see `noise_reading`): one written before the key read
    // it off the reps' spread alone, which at one rep is no reading
    let noise_reads = if reads_no_candidate {
        prior
            .get("noiseReads")
            .cloned()
            .unwrap_or_else(|| "reps".to_string())
    } else if c.reps > 1 {
        "reps".to_string()
    } else {
        "second-read".to_string()
    };
    // the weights and targets the loss applies (see `objective_digest`); `-export` and `-prune`
    // score nothing, so they carry the archive's own and never read the reference
    let objective = match prior.get("objective") {
        Some(recorded) if c.prune || !c.export_to.is_empty() => recorded.clone(),
        _ => objective_digest(
            anchors,
            transport.as_ref(),
            obj.reference
                .as_deref()
                .map(|r| (noregress_name.as_str(), r)),
            &obj.gap,
            &obj.hold,
        ),
    };
    let settings: Vec<(String, String)> = vec![
        ("paths".into(), c.paths.to_string()),
        ("years".into(), c.years.to_string()),
        ("reps".into(), c.reps.to_string()),
        ("sigma".into(), format!("{:.4}", c.sigma)),
        ("dead".into(), format!("{:.4}", c.dead)),
        ("keep".into(), c.keep.to_string()),
        ("sep".into(), format!("{:.4}", c.sep)),
        ("pop".into(), c.pop.to_string()),
        ("anchors".into(), c.anchor_spec.clone()),
        ("seed".into(), c.base.to_string()),
        ("bar".into(), format!("{:.4}", c.bar)),
        (
            "seeds".into(),
            if c.seed_spec.is_empty() {
                "(all)".to_string()
            } else {
                c.seed_spec.clone()
            },
        ),
        // the seed worlds' own values, not only their names: see `seed_worlds_digest`
        ("seedWorlds".into(), seed_worlds_digest(&pool)),
        (
            "seedSet".into(),
            if c.seed_set.is_empty() {
                "(none)".to_string()
            } else {
                c.seed_set.clone()
            },
        ),
        // how a candidate's reads are seeded: see `candidate_seed`
        ("candidateSeeds".into(), candidate_seeds),
        ("noiseReads".into(), noise_reads),
        // the admission rules and the objective are recorded with the ensemble: a resume under different
        // ones puts two standards in one archive
        ("admit".into(), "spread-keeping-nearest".to_string()),
        // the coordinates a child is stepped in and the archive's distance is read in
        (
            "steps".into(),
            format!("ln1p:{}", ms::SEARCH_LOG_DIALS.join(",")),
        ),
        // how a child is drawn from its parent: one dial at a time, or along the archive's shape
        (
            "proposal".into(),
            if c.cov > 0.0 {
                // Java's `%.2f`, as the Scala harness writes it: a tie rounds up there
                format!(
                    "cov:{},shrink:{}",
                    java_format_f(c.cov, 0, 2),
                    java_format_f(c.cov_shrink, 0, 2)
                )
            } else {
                "dial".to_string()
            },
        ),
        // the horizon the gates read the table's quantities at: their records', as the verdict does
        ("gates".into(), "record-horizon".to_string()),
        // the classes feasibility read, so an archive cannot resume across a change of standard
        (
            "gateClasses".into(),
            ms::gate_classes_label(&ms::parse_gate(&c.gate)),
        ),
        ("score".into(), "sum-excess".to_string()),
        ("objective".into(), objective),
        (
            "transport".into(),
            if c.transport.is_empty() {
                "(none)".to_string()
            } else {
                c.transport.clone()
            },
        ),
        (
            "transportWeight".into(),
            if c.transport.is_empty() {
                "(none)".to_string()
            } else {
                java_format_f(c.transport_weight, 0, 4)
            },
        ),
        (
            "noregress".into(),
            if noregress_name.is_empty() {
                "(none)".to_string()
            } else {
                noregress_name.clone()
            },
        ),
        (
            "gap".into(),
            if obj.gap.is_empty() {
                "(none)".to_string()
            } else {
                obj.gap.join(",")
            },
        ),
        ("hold".into(), hold_text(&obj.hold)),
        (
            "fix".into(),
            if c.fix.trim().is_empty() {
                "(none)".to_string()
            } else {
                c.fix
                    .split(',')
                    .map(str::trim)
                    .collect::<Vec<_>>()
                    .join(",")
            },
        ),
        // a prefix rejection can turn away a candidate the whole read would admit, so a staged
        // archive is not the unstaged one's; `-stagecheck` acts on nothing and is not recorded
        ("stage".into(), c.stage.to_string()),
        (
            "project".into(),
            project.map_or_else(
                || "(none)".to_string(),
                |(at, row, _)| format!("{}:{row}", names()[at]),
            ),
        ),
    ];

    let loaded = read_archive(&c.out);
    // a checkpoint without `score` was scored on the worst row alone, and one without `gates` gated
    // on its own ensemble; a resume must refuse, not adopt, since its members were admitted by
    // another standard
    if !loaded.is_empty() && !prior.contains_key("score") {
        prior.insert("score".into(), "worst-row".into());
    }
    // one written before `candidateSeeds` read its candidates a path stride apart
    if !loaded.is_empty() && !prior.contains_key("candidateSeeds") {
        prior.insert("candidateSeeds".into(), "path-stride".into());
    }
    // one written before `noiseReads` read its seed noise off the reps' spread alone
    if !loaded.is_empty() && !prior.contains_key("noiseReads") {
        prior.insert("noiseReads".into(), "reps".into());
    }
    // one written before `-hold` held no row
    if !loaded.is_empty() && !prior.contains_key("hold") {
        prior.insert("hold".into(), "(none)".into());
    }
    // one written before `-fix` stepped every dial
    if !loaded.is_empty() && !prior.contains_key("fix") {
        prior.insert("fix".into(), "(none)".into());
    }
    // one written before `-gate` gated on the verdict's default classes
    if !loaded.is_empty() && !prior.contains_key("gateClasses") {
        prior.insert("gateClasses".into(), "realism,mechanism".into());
    }
    if !loaded.is_empty() && !prior.contains_key("gates") {
        prior.insert("gates".into(), "search-ensemble".into());
    }
    // one written before `-stage` read every path at once, and one before `-project` stepped every dial
    if !loaded.is_empty() && !prior.contains_key("stage") {
        prior.insert("stage".into(), "0".into());
    }
    if !loaded.is_empty() && !prior.contains_key("project") {
        prior.insert("project".into(), "(none)".into());
    }
    // one written before `-cov` existed drew its children one dial at a time
    if !loaded.is_empty() && !prior.contains_key("proposal") {
        prior.insert("proposal".into(), "dial".into());
    }
    // one without `transportWeight` priced its transport arm in full
    if !loaded.is_empty() && !prior.contains_key("transportWeight") {
        let full = if prior.get("transport").is_none_or(|t| t == "(none)") {
            "(none)"
        } else {
            "1.0000"
        };
        prior.insert("transportWeight".into(), full.into());
    }
    let gen0: u64 = prior.get("gen").and_then(|s| s.parse().ok()).unwrap_or(0);
    let evals0: u64 = prior.get("evals").and_then(|s| s.parse().ok()).unwrap_or(0);
    let nsum0: f64 = prior
        .get("noiseSum")
        .and_then(|s| s.parse().ok())
        .unwrap_or(0.0);
    let nn0: u64 = prior
        .get("noiseN")
        .and_then(|s| s.parse().ok())
        .unwrap_or(0);

    // A resume that changes how a candidate is judged puts two standards in one archive and says
    // nothing about it. Refuse, unless told the change is deliberate.
    if !loaded.is_empty() {
        let drift: Vec<&(String, String)> = settings
            .iter()
            .filter(|(k, v)| prior.get(k).is_some_and(|p| p != v))
            .collect();
        let missing: Vec<&str> = settings
            .iter()
            .map(|(k, _)| k.as_str())
            .filter(|k| !prior.contains_key(*k))
            .collect();
        if !missing.is_empty() {
            eprintln!(
                "warning: {} was written before the settings were recorded; adopting the flags given for [{}]",
                c.out,
                missing.join(", ")
            );
        }
        if !drift.is_empty() && !c.force {
            let was: Vec<String> = drift
                .iter()
                .map(|(k, _)| format!("{k}={}", prior[k]))
                .collect();
            let now: Vec<String> = drift.iter().map(|(k, v)| format!("{k}={v}")).collect();
            usage(&format!(
                "{} was built with {}; this run says {}. Re-run with those, use a different -out, or pass -force to mix them.",
                c.out,
                was.join(", "),
                now.join(", ")
            ));
        }
    }

    // THE SEED WORLDS' OWN READINGS, on the pool's seeds, whenever a search will run: the archive
    // is seeded from them on a fresh start, and the quality bar is set from them either way.
    // Membership is re-checked, never inherited: the older releases were adopted against an
    // earlier gate and several no longer pass the rows the model has since grown.
    let search_mode = c.holdout == 0 && !c.prune && c.export_to.is_empty();
    let seed_reads: Vec<(String, World, Read)> = if search_mode {
        let seeds: Vec<u64> = (0..c.reps)
            .map(|k| (c.base as u64).wrapping_add(k as u64 * READ_STRIDE))
            .collect();
        println!(
            "seed worlds at {}, {} x {}y x {} reps",
            c.anchor_spec, c.paths, c.years, c.reps
        );
        // THE SEED WORLDS ARE NOT HELD TO THE GAP ROWS: they root the lineages and set the bars,
        // and the rows a release has to close are the ones the outgoing recipe misses, so gating
        // them left nothing to search from. Their misses are priced as any band's; every
        // candidate, and a `-holdout` re-score of every member, is held to the gap.
        let seed_obj = obj.without_gap();
        pool.iter()
            .map(|(nm, w)| {
                let r = judge(
                    w,
                    &dials_of(w),
                    anchors,
                    transport.as_ref(),
                    c.paths,
                    c.years,
                    &seeds,
                    &seed_obj,
                    None,
                );
                println!(
                    "  {:<24} {:<9} score {:>7.3}  raw {:>7.3}  {}",
                    nm,
                    if r.feasible { "feasible" } else { "REJECTED" },
                    r.score,
                    r.raw,
                    r.worst
                );
                // a rejected seed names the gates it failed: its worst row is a fitness row, not
                // the reason
                if !r.feasible {
                    println!("    fails: {}", r.gate_fail.join("; "));
                }
                if !r.watch_miss.is_empty() {
                    println!("    misses gap rows: {}", r.watch_miss.join(", "));
                }
                (nm.clone(), *w, r)
            })
            .collect()
    } else {
        Vec::new()
    };
    // THE QUALITY BAR. Spread-keeping admits on distance: a feasible candidate far enough from
    // every member entered whatever its score, and a set built that way read a median summed
    // excess of 1.14 against its seed's 0.65, with a third of it well outside "consistent with
    // the record". A candidate now also has to score no worse than `bar` times the world it was
    // seeded from, judged the same way -- so with the default 1.0 every member fits the record
    // at least as well as the shipped world its lineage started from, which is the world the
    // consumer already trusts. Per lineage, as the null control compares, because the gate is
    // not the same for every seed world.
    let bar_for: HashMap<String, f64> = seed_reads
        .iter()
        .map(|(nm, _, r)| (nm.clone(), c.bar * r.score))
        .collect();
    let above_bar = |name: &str, score: f64| -> bool {
        c.bar > 0.0 && bar_for.get(name).is_some_and(|b| score > *b)
    };
    let start_arc: Vec<Member> = if !loaded.is_empty() {
        // SEED SLOTS, not evaluations: every candidate is allotted `reps` seeds whether or not it stops
        // at its first infeasible one, so its seeds are independent of how earlier candidates fared
        let kept: Vec<Member> = loaded
            .iter()
            .filter(|m| !above_bar(&m.name, m.score))
            .cloned()
            .collect();
        println!(
            "resumed: {} members, generation {gen0}, {evals0} seed slots; {} above the bar dropped",
            loaded.len(),
            loaded.len() - kept.len()
        );
        if search_mode && kept.is_empty() {
            usage("every resumed member is above the bar; nothing to search from");
        }
        kept
    } else {
        let seeded: Vec<Member> = seed_reads
            .iter()
            .filter(|(_, _, r)| r.feasible)
            .map(|(nm, w, r)| Member {
                name: nm.clone(),
                dials: dials_of(w),
                score: r.score,
                raw: r.raw,
                worst: r.worst.clone(),
                desc: r.desc.clone(),
            })
            .collect();
        if seeded.is_empty() {
            usage("no seed world passes today's gate; nothing to search from");
        }
        seeded
    };

    if !c.export_to.is_empty() {
        export_worlds(&c.out, &c.export_to, &world_for);
        return;
    }

    // PRUNE to what the holdout kept. `holdout.tsv` is POSITIONAL against `archive.tsv` -- both
    // are written by this tool and holdout mode never touches the archive -- so the row count
    // having changed means the two no longer describe the same set, and matching them up would be
    // a guess. The dropped members are moved rather than deleted: a member that fails today's
    // ensemble is evidence about the ensemble as much as about the member.
    if c.prune {
        let Some(ht) = read_text(&format!("{}/holdout.tsv", c.out)) else {
            usage(&format!("no {}/holdout.tsv; run -holdout first", c.out));
        };
        if loaded.is_empty() {
            usage(&format!("no archive in {} to prune", c.out));
        }
        let hr: Vec<Vec<String>> = ht
            .lines()
            .skip(1)
            .filter(|l| !l.trim().is_empty())
            .map(|l| l.split('\t').map(str::to_string).collect())
            .collect();
        if hr.len() != loaded.len() {
            usage(&format!(
                "{}/holdout.tsv has {} rows against the archive's {}: re-run -holdout, the two no longer describe the same set",
                c.out,
                hr.len(),
                loaded.len()
            ));
        }
        let passed = |r: &[String]| r[1] == "true" && r[3] == "true";
        let kept: Vec<Member> = loaded
            .iter()
            .zip(&hr)
            .filter(|(_, r)| passed(r))
            .map(|(m, _)| m.clone())
            .collect();
        let gone: Vec<(&Member, &Vec<String>)> =
            loaded.iter().zip(&hr).filter(|(_, r)| !passed(r)).collect();
        let mut out = String::new();
        let _ = writeln!(
            out,
            "name\tscore\traw\tworstRow\t{}\t{}\tpassA\tpassB\trawB\tworstB",
            names().join("\t"),
            DESC_NAMES.join("\t")
        );
        for (m, r) in &gone {
            let ds: Vec<String> = m.dials.iter().map(|x| g8(*x)).collect();
            // the DESCRIPTORS travel with a dropped member, as they do in the archive: a member
            // that fails today's ensemble is evidence, and what it did is half of that evidence
            let bs: Vec<String> = m.desc.iter().map(|x| g8(*x)).collect();
            let _ = writeln!(
                out,
                "{}\t{:.6}\t{:.6}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
                m.name,
                m.score,
                m.raw,
                m.worst,
                ds.join("\t"),
                bs.join("\t"),
                r[1],
                r[3],
                r[4],
                r[5]
            );
        }
        write_text(&format!("{}/dropped.tsv", c.out), &out);
        write_archive(&c.out, &kept, gen0, evals0, (nsum0, nn0), &settings);
        println!(
            "archive pruned to {}; {} moved to {}/dropped.tsv",
            kept.len(),
            gone.len(),
            c.out
        );
        return;
    }

    // THE HOLDOUT: every member earned its place on seeds the search chose, and a band-edge world
    // flips on one draw in six.  Re-score each member at the same ensemble on TWO INDEPENDENT
    // STREAMS, neither of which selected the mutations (the search draws `candidate_seed`'s; stream
    // A is the pool's own seeds, stream B that shifted by `HOLDOUT_FRESH_OFFSET`).  A
    // SEED-SENSITIVITY test, not train against test, and the columns say so: a member that passes one
    // stream and fails the other was admitted by a draw, not by the record.
    if c.holdout > 0 {
        if loaded.is_empty() {
            usage(&format!("no archive in {} to re-score", c.out));
        }
        let train: Vec<u64> = (0..c.holdout)
            .map(|k| (c.base as u64).wrapping_add(k as u64 * READ_STRIDE))
            .collect();
        let fresh: Vec<u64> = (0..c.holdout)
            .map(|k| (c.base as u64).wrapping_add(HOLDOUT_FRESH_OFFSET + k as u64 * READ_STRIDE))
            .collect();
        println!(
            "re-scoring {} members on two independent streams of {} and {} seeds at {} x {}y, {}; neither selected the mutations",
            loaded.len(),
            c.holdout,
            c.holdout,
            c.paths,
            c.years,
            c.anchor_spec
        );
        let mut rows = Vec::new();
        for m in &loaded {
            let base = world_for(&m.name);
            let a = judge(
                &base,
                &m.dials,
                anchors,
                transport.as_ref(),
                c.paths,
                c.years,
                &train,
                &obj,
                None,
            );
            let b = judge(
                &base,
                &m.dials,
                anchors,
                transport.as_ref(),
                c.paths,
                c.years,
                &fresh,
                &obj,
                None,
            );
            println!(
                "  {:<22} stream A {:<4} raw {:>6.3}   stream B {:<4} raw {:>6.3}   {}",
                m.name,
                if a.feasible { "pass" } else { "FAIL" },
                a.raw,
                if b.feasible { "pass" } else { "FAIL" },
                b.raw,
                b.worst
            );
            rows.push((m.clone(), a, b));
        }
        // ---- THE NULL CONTROL: each member against the world it was seeded from, on the fresh stream.
        // The seed worlds are hand-tuned against these anchors, so a member that beats its seed means the
        // tuning left something on the table or the objective has a hole; the hole is what this exists to
        // catch.  Against its OWN seed because channel rows fire only when the channel is on, so lineages
        // face different gates.
        //
        // THE THRESHOLD is twice the seed-noise sd, pooled across lineages from each seed world's
        // deviations from its own mean over the fresh seeds (a per-world range over three draws varied
        // 22-fold between lineages).  The control prints its own resolution: seed noise is a smooth sd of
        // 0.074 plus a 0.5 jump when a band-edge row flips, so resolving 0.05 needs a dozen seeds.
        //
        // A BETTER WORLD BEATS THE WORST ROW WITHOUT PAYING FOR IT ELSEWHERE.  The score pays nothing
        // inside the dead zone, so a member can lower the worst row while every other drifts (under
        // the worst-row objective one took 0.05 off it for three times the summed loss).  A member is
        // flagged only when it also reads no worse than its seed on the WHOLE SIGNATURE, the summed
        // loss over every row of both arms; the ones that paid are counted separately, as the
        // objective's hole.
        let seed_names: Vec<String> = {
            let mut v: Vec<String> = loaded.iter().map(|m| m.name.clone()).collect();
            v.sort();
            v.dedup();
            v
        };
        println!("\nNULL CONTROL   each member against the world it was seeded from");
        if c.holdout < 3 {
            println!(
                "   -holdout {} gives {} readings a seed world, so the spread below is thin; 3 or more makes it mean something",
                c.holdout, c.holdout
            );
        }
        // pass one: read every seed world on every fresh seed, and pool the deviations
        let mut seed_reads: Vec<(String, Vec<f64>, f64)> = Vec::new();
        for nm in &seed_names {
            let w = world_for(nm);
            let dials = dials_of(&w);
            let reads: Vec<Read> = fresh
                .iter()
                .map(|&sd| {
                    judge(
                        &w,
                        &dials,
                        anchors,
                        transport.as_ref(),
                        c.paths,
                        c.years,
                        &[sd],
                        &obj,
                        None,
                    )
                })
                .collect();
            let per: Vec<f64> = reads.iter().map(|r| r.raw).collect();
            let total = reads.iter().map(|r| r.total).sum::<f64>() / reads.len() as f64;
            seed_reads.push((nm.clone(), per, total));
        }
        let mut devs: Vec<f64> = Vec::new();
        for (_, per, _) in &seed_reads {
            let m = per.iter().sum::<f64>() / per.len() as f64;
            devs.extend(per.iter().map(|x| x - m));
        }
        let dof = (devs.len() as f64 - seed_reads.len() as f64).max(1.0);
        let sd = (devs.iter().map(|d| d * d).sum::<f64>() / dof).sqrt();
        let threshold = 2.0 * sd;
        println!(
            "   seed noise sd {sd:.4} pooled over {} readings; a member must beat its seed by {threshold:.4}",
            devs.len()
        );
        println!(
            "   RESOLUTION: differences under {threshold:.4} are invisible here, whatever their sign"
        );
        println!("   seed world              its raw   its loss   beat it   paid elsewhere");
        let mut flagged: Vec<(String, String, f64, f64, f64)> = Vec::new();
        let mut paid: Vec<(String, String, f64, f64, f64)> = Vec::new();
        for (nm, per, seed_total) in &seed_reads {
            let hi = per.iter().copied().fold(f64::NEG_INFINITY, f64::max);
            let mine: Vec<&HoldoutRow> = rows.iter().filter(|(m, _, _)| &m.name == nm).collect();
            let (held, bought): (Vec<&HoldoutRow>, Vec<&HoldoutRow>) = mine
                .iter()
                .copied()
                .filter(|(_, _, b)| b.feasible && hi - b.raw > threshold)
                .partition(|(_, _, b)| b.total <= *seed_total);
            println!(
                "   {nm:<22} {hi:>7.4}   {seed_total:>8.3}   {:>4} of {:<4}  {:>4}",
                held.len(),
                mine.len(),
                bought.len()
            );
            for (m, _, b) in &held {
                flagged.push((
                    m.name.clone(),
                    b.worst.clone(),
                    hi - b.raw,
                    b.total,
                    *seed_total,
                ));
            }
            for (m, _, b) in &bought {
                paid.push((
                    m.name.clone(),
                    b.worst.clone(),
                    hi - b.raw,
                    b.total,
                    *seed_total,
                ));
            }
        }
        if flagged.is_empty() {
            println!("   PASSES: no member beats its seed by more than this control can resolve");
            println!("   without paying for it on the other rows. The archive's value is its");
            println!("   spread, which is what it was built for.");
        } else {
            println!(
                "   {} members beat their seed by more than its own spread AND read no worse on",
                flagged.len()
            );
            println!(
                "   the whole signature. Before believing it, look at WHICH ROW moved and at the"
            );
            println!("   descriptor columns, which carry the statistics nothing grades:");
            for (nm, worst, by, total, seed_total) in flagged.iter().take(8) {
                println!(
                    "     {nm:<22} better by {by:>6.4}, now worst on {worst}; loss {total:.3} vs {seed_total:.3}"
                );
            }
        }
        if !paid.is_empty() {
            println!(
                "   {} beat the worst row by PAYING FOR IT ELSEWHERE -- the objective's hole, not a",
                paid.len()
            );
            println!("   better world; the summed loss over every row is worse than the seed's:");
            for (nm, worst, by, total, seed_total) in paid.iter().take(8) {
                println!(
                    "     {nm:<22} worst row better by {by:>6.4} on {worst}; loss {total:.3} vs {seed_total:.3}"
                );
            }
        }
        println!();

        let kept = rows
            .iter()
            .filter(|(_, a, b)| a.feasible && b.feasible)
            .count();
        let lucky = rows
            .iter()
            .filter(|(_, a, b)| a.feasible != b.feasible)
            .count();
        let both = rows
            .iter()
            .filter(|(_, a, b)| !a.feasible && !b.feasible)
            .count();
        let mut out = String::from("name\tpassA\trawA\tpassB\trawB\tworstB\tlossA\tlossB\n");
        for (m, a, b) in &rows {
            let _ = writeln!(
                out,
                "{}\t{}\t{:.6}\t{}\t{:.6}\t{}\t{:.6}\t{:.6}",
                m.name, a.feasible, a.raw, b.feasible, b.raw, b.worst, a.total, b.total
            );
        }
        write_text(&format!("{}/holdout.tsv", c.out), &out);
        println!("\nsurvives both: {kept:>3} of {}", loaded.len());
        println!("seed-sensitive: {lucky:>3}  (passed one stream, failed the other)");
        println!("fails both    : {both:>3}  (rejected on both streams)");
        println!("wrote {}/holdout.tsv", c.out);
        return;
    }

    write_archive(&c.out, &start_arc, gen0, evals0, (nsum0, nn0), &settings);
    // THE HEADER IS A CONTRACT, and it is written only when the log is absent, so a resume
    // after a column was added would append wider rows under the narrower header and quietly
    // ragged the file. Refuse instead: the run that wrote those rows read a different log.
    let header = format!(
        "gen\teval\tparent\tfeasible\tscore\traw\tworstRow\tseconds\t{}\tgateFail\tadmitted\n",
        DESC_NAMES.join("\t")
    );
    match read_text(&format!("{}/log.tsv", c.out)) {
        None => write_text(&format!("{}/log.tsv", c.out), &header),
        Some(t) => {
            let had = t.lines().next().unwrap_or_default();
            if had != header.trim_end() {
                usage(&format!(
                    "{}/log.tsv was written with different columns; move it aside\n  it has [{had}]\n  this binary writes [{}]",
                    c.out,
                    header.trim_end()
                ));
            }
        }
    }

    // `-gens 0` runs until the spread closes or the run is killed; the checkpoint after every
    // generation is what makes that safe.
    let rs = ranges();
    // THE FIXED DIALS (`-fix`): each must name a dial of the table
    let fix_names: Vec<&str> = c
        .fix
        .split(',')
        .map(str::trim)
        .filter(|x| !x.is_empty())
        .collect();
    for f in &fix_names {
        if !rs.iter().any(|r| r.0 == *f) {
            usage(&format!("-fix names [{f}], which is not a searched dial"));
        }
    }
    let fixed: Vec<bool> = rs.iter().map(|r| fix_names.contains(&r.0)).collect();
    // THE PROJECTION (`-project`). Held as a `-hold`, a row the loss pulls through every free dial
    // rejects the children that drift from it (search-v144's hold on return per vol starved
    // admission); solved, every child starts on it. The slope is read on the first seed world at its
    // own value and one step up, paired on the same seeds so the step is the whole difference; the
    // readings of the members a resumed archive brings without one are read once here.
    let mut held: HashMap<String, f64> = read_held(&c.out);
    for (nm, w, r) in &seed_reads {
        held.insert(held_key(nm, &dials_of(w)), round8(r.held));
    }
    let slope = project.map_or(f64::NAN, |(at, row, target)| {
        let (nm, w, _) = &seed_reads[0];
        let lo = dials_of(w);
        let step = PROJECT_STEP * (rs[at].2 - rs[at].1);
        let mut hi = lo.clone();
        hi[at] = lo[at] + step;
        let reading = |d: &[f64], k: u64| {
            let s = (c.base as u64).wrapping_add(PROJECT_OFFSET + k * READ_STRIDE);
            one_read(
                &world_of(w, d),
                anchors,
                c.paths,
                c.years,
                s,
                &obj.without_gap(),
            )
            .held
        };
        let slope = round8(
            (0..PROJECT_SLOPE_READS)
                .map(|k| (reading(&hi, k) - reading(&lo, k)) / step)
                .sum::<f64>()
                / PROJECT_SLOPE_READS as f64,
        );
        if !(slope.is_finite() && slope > 0.0) {
            usage(&format!(
                "-project: {row} reads a slope of {slope} in {} on {nm}; nothing to solve along",
                rs[at].0
            ));
        }
        println!(
            "projecting {} onto {row} = {}: slope {} on {nm}",
            rs[at].0,
            g8(target),
            g8(slope)
        );
        slope
    });
    if project.is_some() {
        let missing: Vec<&Member> = start_arc
            .iter()
            .filter(|m| !held.contains_key(&held_key(&m.name, &m.dials)))
            .collect();
        for (k, m) in missing.iter().enumerate() {
            let s = (c.base as u64)
                .wrapping_add(PROJECT_OFFSET + (PROJECT_SLOPE_READS + k as u64) * READ_STRIDE);
            let r = one_read(
                &world_of(&world_for(&m.name), &m.dials),
                anchors,
                c.paths,
                c.years,
                s,
                &obj.without_gap(),
            );
            held.insert(held_key(&m.name, &m.dials), round8(r.held));
        }
        if !missing.is_empty() {
            println!("read {} members' projected row afresh", missing.len());
        }
    }
    // THE SPREAD TRACE the stopping rule reads: the descriptors of every candidate admitted so
    // far, by generation, rebuilt from the log on a resume so the rule reads the whole run
    let nd = DESC_NAMES.len();
    let mut trace: Vec<(u64, Vec<f64>)> = read_text(&format!("{}/log.tsv", c.out))
        .map(|t| {
            t.lines()
                .skip(1)
                .filter_map(|l| {
                    let f: Vec<&str> = l.split('\t').collect();
                    if f.len() < 10 + nd || f[f.len() - 1] != "true" {
                        return None;
                    }
                    let at: u64 = f[0].parse().ok()?;
                    let desc: Vec<f64> = f[8..8 + nd]
                        .iter()
                        .map(|x| x.parse().unwrap_or(f64::NAN))
                        .collect();
                    Some((at, desc))
                })
                .collect()
        })
        .unwrap_or_default();
    // a running seed-noise estimate from each candidate's spread across its own reps, carried across generations
    let mut noise_sum = nsum0;
    let mut noise_n = nn0;
    let mut arc = start_arc;
    let mut g = gen0;
    let mut evals = evals0;
    while c.gens == 0 || g < gen0 + c.gens {
        // One generation: `pop` mutations of members drawn from the archive, then a checkpoint. A
        // kill between generations loses at most one generation's work.
        // The same `NumPyRng` as the rest of the program and as the Scala harness: `randn` and `next_bounded_u32`
        // are gated bit-identical across the twins, so one `-seed` proposes the same candidates in both.
        // The mask keeps the derived seed non-negative in both languages.
        let mut rng =
            NumPyRng::new(((c.base ^ (g as i64).wrapping_mul(0x9e37_79b9)) & i64::MAX) as u64);
        let mut log = Vec::new();
        let mut cands: Vec<String> = Vec::new();
        let mut stage_log: Vec<String> = Vec::new();
        // the archive's shape is read once a generation; `-cov 0` reads none and draws nothing
        // extra, so a run without it proposes exactly what it always did
        let factor = if c.cov > 0.0 {
            proposal_factor(&arc, &rs, c.cov_shrink)
        } else {
            None
        };
        // the noise read's slot (see `noise_reading`), past the generation's candidates and
        // reserved whether it is used or not, so a candidate's slot never depends on an outcome
        let noise_slot = evals + (c.pop * c.reps) as u64;
        let mut noise_due = c.reps == 1;
        for _ in 0..c.pop {
            let parent = arc[rng.next_bounded_u32(arc.len() as u32) as usize].clone();
            let along = factor.as_ref().filter(|_| rng.next_f64() < c.cov);
            let z: Vec<f64> = (0..rs.len()).map(|_| rng.randn()).collect();
            let child = admissible(
                (0..rs.len())
                    .map(|i| {
                        let zi = along.map_or(z[i], |l| {
                            let mut s = 0.0;
                            for (t, zt) in z.iter().enumerate().take(i + 1) {
                                s += l[i][t] * zt;
                            }
                            s
                        });
                        if fixed[i] {
                            parent.dials[i]
                        } else if let Some((_, _, target)) = project.filter(|p| p.0 == i) {
                            // the parent's miss on the row, closed along the slope; the draw is
                            // made as without it, so the other dials step alike
                            held.get(&held_key(&parent.name, &parent.dials))
                                .filter(|h| h.is_finite())
                                .map_or(parent.dials[i], |h| {
                                    clamped(&rs[i], parent.dials[i] + (target - h) / slope)
                                })
                        } else {
                            stepped(&rs[i], parent.dials[i], zi, c.sigma)
                        }
                    })
                    .collect(),
            );
            let seeds: Vec<u64> = (0..c.reps)
                .map(|j| candidate_seed(c.base, evals + j as u64))
                .collect();
            let t0 = std::time::Instant::now();
            let r = judge(
                &world_for(&parent.name),
                &child,
                anchors,
                transport.as_ref(),
                c.paths,
                c.years,
                &seeds,
                &obj,
                // the lineage's bar, so an evaluation stops as soon as it is over it
                if c.bar > 0.0 {
                    bar_for.get(&parent.name).copied()
                } else {
                    None
                },
            );
            let secs = t0.elapsed().as_secs_f64();
            let bs: Vec<String> = r.desc.iter().map(|x| g8(*x)).collect();
            let gate_fail = r.gate_fail.join("; ");
            // admitted BEFORE the line is written, because the line records the answer
            let mut took = false;
            // THE NOISE READ (`-reps 1`): the generation's first candidate to pass its own read is
            // read once more, at the generation's reserved slot and to the end whatever the bar.
            // It feeds the estimate alone: the candidate is scored on its first read, as its
            // siblings are
            let second = (noise_due && r.feasible && r.complete).then(|| {
                judge(
                    &world_for(&parent.name),
                    &child,
                    anchors,
                    transport.as_ref(),
                    c.paths,
                    c.years,
                    &[candidate_seed(c.base, noise_slot)],
                    &obj,
                    None,
                )
            });
            // a candidate above the bar still feeds the noise estimate when it ran to the end:
            // its spread is a reading of the objective's own noise whatever its level. One the
            // bar cut short does not: a partial spread is not that reading
            if let Some(x) = noise_reading(c.reps, &r, second.as_ref()) {
                noise_sum += x;
                noise_n += 1;
                noise_due = false;
            }
            let child_key = held_key(&parent.name, &child);
            let child_dials: Vec<String> = child.iter().map(|x| g8(*x)).collect();
            if r.feasible && !above_bar(&parent.name, r.score) {
                // no reading yet is no margin: at `-reps 1` the first may come generations in
                let noise = if noise_n > 0 {
                    noise_sum / noise_n as f64
                } else {
                    0.0
                };
                let (next, entered) = admit(
                    arc,
                    Member {
                        name: parent.name.clone(),
                        dials: child,
                        score: r.score,
                        raw: r.raw,
                        worst: r.worst.clone(),
                        desc: r.desc.clone(),
                    },
                    c.sep,
                    c.keep,
                    noise,
                );
                arc = next;
                took = entered;
                if took {
                    trace.push((g, r.desc.clone()));
                    held.insert(child_key, round8(r.held));
                }
            }
            log.push(format!(
                "{g}\t{}\t{}\t{}\t{:.6}\t{:.6}\t{}\t{:.3}\t{}\t{}\t{took}",
                // `evals` is the SEED SLOT this candidate drew from (rep j reads `candidate_seed(base,
                // evals + j)`), so a log line reproduces its candidate
                evals,
                parent.name,
                r.feasible,
                r.score,
                r.raw,
                r.worst,
                secs,
                bs.join("\t"),
                gate_fail
            ));
            // every candidate's dials, so what it was can be read back without re-deriving the
            // draws: the log carries its descriptors and the archive only the members
            let staged = match &r.stage_fail {
                None => "-",
                Some(f) if f.is_empty() => "pass",
                Some(_) => "fail",
            };
            cands.push(format!(
                "{g}\t{evals}\t{}\t{}\t{took}\t{staged}\t{secs:.3}\t{}\t{}",
                parent.name,
                r.feasible,
                g8(r.held),
                child_dials.join("\t")
            ));
            if obj.stage_check {
                stage_log.push(format!(
                    "{g}\t{evals}\t{}\t{}\t{}\t{}",
                    r.stage_fail.as_ref().is_some_and(Vec::is_empty),
                    r.feasible,
                    r.stage_fail
                        .as_ref()
                        .map_or(String::new(), |f| f.join("; ")),
                    r.gate_fail.join("; ")
                ));
            }
            evals += c.reps as u64;
        }
        if c.reps == 1 {
            evals += 1;
        }
        append_log(&c.out, &log);
        append_tsv(
            &c.out,
            "cands.tsv",
            &format!(
                "gen\teval\tparent\tfeasible\tadmitted\tstage\tseconds\theld\t{}",
                names().join("\t")
            ),
            &cands,
        );
        if obj.stage_check {
            append_tsv(
                &c.out,
                "stage.tsv",
                "gen\teval\tprefixFeasible\tfeasible\tprefixFail\tgateFail",
                &stage_log,
            );
        }
        write_archive(&c.out, &arc, g + 1, evals, (noise_sum, noise_n), &settings);
        if project.is_some() {
            write_held(&c.out, &arc, &held);
        }
        let best = arc.iter().map(|m| m.score).fold(f64::INFINITY, f64::min);
        let raw = arc.iter().map(|m| m.raw).fold(f64::INFINITY, f64::min);
        let scores: Vec<f64> = arc.iter().map(|m| m.score).collect();
        println!(
            "gen {g:>5}  archive {:>3}  best {best:>7.3}  median {:>7.3}  raw {raw:>7.3}  slots {evals:>6}",
            arc.len(),
            ms::pctile(&scores, 0.5)
        );
        g += 1;
        if c.close > 0.0 {
            if let Some((added, blk)) = spread_added(&trace, g) {
                if added < c.close {
                    println!(
                        "CLOSED -- the last {} gen added {added:.1}% of the spread; stopping. Run -holdout, then -prune.",
                        2 * blk
                    );
                    break;
                }
            }
        }
    }
}

#[cfg(test)]
mod seed_stream_tests {
    use super::*;

    /// Two reads share a path when their seeds differ by `t * PATH_STRIDE` with |t| under the path
    /// count. Swept to 4096 paths over every pair of streams and every slot distance near enough
    /// to matter.
    #[test]
    fn read_streams_share_no_path() {
        const MAX_PATHS: i128 = 4096;
        let (path, read) = (PATH_STRIDE as i128, READ_STRIDE as i128);
        let streams: [(&str, i128); 5] = [
            ("pool", 0),
            ("holdout fresh", HOLDOUT_FRESH_OFFSET as i128),
            ("noregress", NOREGRESS_OFFSET as i128),
            ("candidates", CANDIDATE_OFFSET as i128),
            ("project", PROJECT_OFFSET as i128),
        ];
        // past this many slots apart the seeds are further apart than MAX_PATHS path strides
        let reach = MAX_PATHS * path / read + 2;
        for (na, a) in streams {
            for (nb, b) in streams {
                for d in -reach..=reach {
                    if na == nb && d == 0 {
                        continue;
                    }
                    let gap = a - b + d * read;
                    assert!(
                        gap % path != 0 || (gap / path).abs() >= MAX_PATHS,
                        "{na} and {nb}, {d} slots apart, sit {} path strides apart",
                        gap / path
                    );
                }
            }
        }
    }

    #[test]
    fn a_candidates_reps_and_its_neighbours_read_disjoint_paths() {
        let mut seen = std::collections::HashSet::new();
        for slot in 0..16 {
            let s = candidate_seed(20260958, slot);
            for k in 0..200u64 {
                assert!(
                    seen.insert(s.wrapping_add(k * PATH_STRIDE)),
                    "slot {slot} path {k} was read before"
                );
            }
        }
    }
}

#[cfg(test)]
mod noise_tests {
    use super::*;

    fn read(score: f64, spread: f64, feasible: bool, complete: bool) -> Read {
        Read {
            feasible,
            score,
            raw: score,
            worst: String::new(),
            desc: Vec::new(),
            total: score,
            gate_fail: Vec::new(),
            spread,
            complete,
            watch_miss: Vec::new(),
            stage_fail: None,
            held: f64::NAN,
        }
    }

    /// A STAGED READ IS THE WHOLE READ when its prefix passes: the same paths, so the same reading
    /// to the bit; and a prefix that fails rejects the candidate there, naming the stage.
    #[test]
    fn a_staged_read_is_the_whole_read_or_a_prefix_rejection() {
        let anchors = ms::anchors_named("nasdaq");
        let w = ms::named_world("0.24.5-nasdaq")
            .expect("the Nasdaq recipe")
            .0;
        let base = Objective {
            dead: dead_zone(0.5),
            reference: None,
            gap: Vec::new(),
            classes: ms::parse_gate("realism,mechanism"),
            watch: Vec::new(),
            hold: Vec::new(),
            stage: 0,
            stage_check: false,
            project_row: Some("return per vol"),
        };
        let staged = Objective {
            stage: 12,
            ..base.unstaged()
        };
        let checked = Objective {
            stage: 12,
            stage_check: true,
            ..base.unstaged()
        };
        let (paths, years, s) = (40, 30, 20_261_003);
        let whole = one_read(&w, anchors, paths, years, s, &base);
        let check = one_read(&w, anchors, paths, years, s, &checked);
        assert!(check.stage_fail.is_some());
        assert_eq!(whole.score.to_bits(), check.score.to_bits());
        assert_eq!(whole.total.to_bits(), check.total.to_bits());
        assert_eq!(whole.held.to_bits(), check.held.to_bits());
        assert_eq!(whole.feasible, check.feasible);
        assert_eq!(whole.gate_fail, check.gate_fail);
        let st = one_read(&w, anchors, paths, years, s, &staged);
        if check.stage_fail.as_ref().is_some_and(Vec::is_empty) {
            assert_eq!(whole.score.to_bits(), st.score.to_bits());
            assert_eq!(whole.gate_fail, st.gate_fail);
        } else {
            assert!(!st.feasible);
            assert!(st.gate_fail.iter().all(|f| f.starts_with("stage: ")));
        }
    }

    #[test]
    fn reps_feed_the_noise_their_own_spread() {
        let r = read(0.50, 0.07, true, true);
        assert_eq!(noise_reading(2, &r, None), Some(0.07));
        // a second read is not taken at two reps, and would not be read if it were
        assert_eq!(
            noise_reading(2, &r, Some(&read(0.90, 0.0, true, true))),
            Some(0.07)
        );
        assert_eq!(noise_reading(2, &read(0.50, 0.07, false, true), None), None);
        assert_eq!(noise_reading(2, &read(0.50, 0.07, true, false), None), None);
    }

    #[test]
    fn one_rep_feeds_it_a_second_reads_distance_and_never_a_zero() {
        let r = read(0.50, 0.0, true, true);
        // no second read is no reading: a 0 would say the score has no noise
        assert_eq!(noise_reading(1, &r, None), None);
        let gap = |s: f64| noise_reading(1, &r, Some(&read(s, 0.0, true, true)));
        assert!((gap(0.62).unwrap() - 0.12).abs() < 1e-12);
        assert!((gap(0.41).unwrap() - 0.09).abs() < 1e-12);
        // a second read that failed a gate, or that the bar cut short, scored another thing
        assert_eq!(
            noise_reading(1, &r, Some(&read(0.62, 0.0, false, true))),
            None
        );
        assert_eq!(
            noise_reading(1, &r, Some(&read(0.62, 0.0, true, false))),
            None
        );
    }
}

#[cfg(test)]
mod gate_tests {
    use super::*;

    /// `-gap` IS THE VERDICT'S OWN MISS, row for row: a row with a record band judged by that band,
    /// a row without one by its ratio to the anchor's target. A search gating on anything else
    /// would reject worlds the release rule admits, or admit ones it rejects.
    #[test]
    fn gap_miss_agrees_with_the_verdict() {
        let anchors = ms::anchors_named("nasdaq");
        let (_, w, _) = ms::recipes()
            .into_iter()
            .find(|(n, _, _)| *n == "0.24.3-nasdaq")
            .expect("the recipe the gap rows were named against");
        let (paths, years, seed) = (8, 40, 7);
        let main = ms::sim_paths(&w, paths, years, seed);
        let st = ms::measure(&main, years);
        let hr = ms::horizon_readings(anchors, &st, Some(&main), years, paths, seed, &w, true);
        let verdict = ms::fidelity_rows(anchors, &st, Some(&main), years, paths, seed, &w);
        for row in &verdict {
            let by_gap = !gap_misses(anchors, &[row.name], &hr, &st).is_empty();
            assert_eq!(by_gap, row.miss(), "{} read differently by -gap", row.name);
        }
    }
}

#[cfg(test)]
mod watch_tests {
    use super::*;

    /// A SEED'S READ NAMES EVERY GAP ROW IT MISSES, whichever way the row is graded. Read off the
    /// gate's `band:` labels that report was blind to a row graded by its ratio, which carries
    /// none -- and `0.24.3-nasdaq` misses one, the lower wing, on every seed.
    #[test]
    fn a_seed_read_reports_the_gap_rows_it_misses() {
        let anchors = ms::anchors_named("nasdaq");
        let (_, w, _) = ms::recipes()
            .into_iter()
            .find(|(n, _, _)| *n == "0.24.3-nasdaq")
            .expect("the recipe the gap rows were named against");
        let (paths, years, seed) = (16, 60, 7);
        let extreme = ms::extreme_target_names();
        let graded: Vec<&'static str> = ms::fit_targets(anchors)
            .into_iter()
            .map(|(n, _, _, _)| n)
            .filter(|n| !extreme.contains(n))
            .collect();
        // realism alone: a mechanism gate can flip at this ensemble, and an early exit reads no table
        let seed_obj = Objective {
            dead: dead_zone(0.5),
            reference: None,
            gap: graded.clone(),
            classes: ms::parse_gate("realism"),
            watch: Vec::new(),
            hold: Vec::new(),
            stage: 0,
            stage_check: false,
            project_row: None,
        }
        .without_gap();
        assert!(seed_obj.gap.is_empty() && seed_obj.watch == graded);
        let r = one_read(&w, anchors, paths, years, seed, &seed_obj);

        let main = ms::sim_paths(&w, paths, years, seed);
        let st = ms::measure(&main, years);
        let verdict = ms::fidelity_rows(anchors, &st, Some(&main), years, paths, seed, &w);
        let want: Vec<&'static str> = graded
            .iter()
            .copied()
            .filter(|n| verdict.iter().any(|row| row.name == *n && row.miss()))
            .collect();
        assert_eq!(r.watch_miss, want, "the seed's report against the verdict");
        let unbanded = |n: &&str| !anchors.record_bands.iter().any(|b| b.name == *n);
        assert!(
            r.watch_miss.iter().any(unbanded),
            "no ratio row missed here, so this read cannot tell the old report from the new: {:?}",
            r.watch_miss
        );
        // a candidate watches nothing
        let cand = one_read(&w, anchors, paths, years, seed, &seed_obj.plain());
        assert!(cand.watch_miss.is_empty());
    }
}

#[cfg(test)]
mod coverage_tests {
    use super::*;

    fn member(desc: &[f64]) -> Member {
        Member {
            name: "m".to_string(),
            dials: Vec::new(),
            score: 0.0,
            raw: 0.0,
            worst: String::new(),
            desc: desc.to_vec(),
        }
    }

    /// A member with near-duplicates stands for less of the archive than one on its own, and the
    /// weights average 1 whatever the crowding.
    #[test]
    fn coverage_weight_is_shared_among_near_duplicates() {
        // three readings at one point, one far away: the four are a two-point archive, not four
        let arc: Vec<Member> = [0.0, 0.001, 0.002, 1.0]
            .iter()
            .map(|x| member(&[*x]))
            .collect();
        let w = coverage_weights(&arc);
        let mean = w.iter().sum::<f64>() / w.len() as f64;
        assert!((mean - 1.0).abs() < 1e-12, "mean {mean}");
        // the lone member stands for most, the crowd's middle for least, its edges alike
        assert!(w[3] > w[0] && w[0] > w[1], "{w:?}");
        assert!((w[0] - w[2]).abs() < 1e-12, "{w:?}");
        assert!(
            w[0] + w[1] + w[2] < 2.0 * w[3],
            "three at one point are worth under two apart: {w:?}"
        );
        // an archive with no descriptors, or none that vary, weighs its members alike
        let flat: Vec<Member> = (0..3).map(|_| member(&[1.0])).collect();
        assert_eq!(coverage_weights(&flat), vec![1.0; 3]);
        assert_eq!(coverage_weights(&[member(&[])]), vec![1.0]);
    }
}

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
#[cfg(feature = "fast-alloc")]
use uni::fast_alloc;
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
/// library's `SEARCH_LOG_DIALS` and a cap's tightness for its `SEARCH_INV_DIALS`, which the
/// reachability check shares.
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
    if ms::SEARCH_INV_DIALS.contains(&r.0) {
        // a cap's range is off to its tightest in its tightness: every looser cap is inside
        let (y, lo, hi) = (coord(r, x), coord(r, r.1), coord(r, r.2));
        if y < lo || y > hi {
            ms::from_search_coord(r.0, y.max(lo).min(hi))
        } else {
            x
        }
    } else {
        x.max(r.1).min(r.2)
    }
}

/// A parent's dial moved by `z` sd of `sigma` times its width, in its search coordinates, and
/// held inside its range. Identical to a step on the value itself for every other dial.
fn stepped(r: &Range, x: f64, z: f64, sigma: f64) -> f64 {
    let y = coord(r, x) + z * sigma * coord_width(r);
    if ms::SEARCH_LOG_DIALS.contains(&r.0) || ms::SEARCH_INV_DIALS.contains(&r.0) {
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
    let scale = dial("noiseScale").unwrap_or(1.0);
    let size = dial("newsSize").map_or(0.0, |x| ms::news_size_within_budget(rate, x, scale));
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
/// THE OBJECTIVE'S FORM (`-objective`): the ledger score (`ledger_score`) or the record's CRPS
/// (`crps_rows`), the placement feasibility and `-noregress` the same under either.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum ObjectiveKind {
    Crps,
    Ledger,
}

impl ObjectiveKind {
    fn parse(s: &str) -> Self {
        match s {
            "crps" => Self::Crps,
            "ledger" => Self::Ledger,
            other => usage(&format!("-objective takes crps or ledger, not [{other}]")),
        }
    }

    /// the checkpoint's `score` setting, so an archive cannot resume across a change of objective
    fn label(self) -> &'static str {
        match self {
            Self::Crps => "sum-excess",
            Self::Ledger => "ledger-crps",
        }
    }
}

/// THE LEDGER'S ROW TABLE (`-ledger FILE`), in the file's order: each graded row's units class,
/// weight, null (`None` for a level or a share, which can never sit on the wrong side of
/// anything) and record scale in each world (`LEDGER_WORLDS`). The order is the archive's column
/// order and the order the score is summed in.
#[derive(Default)]
struct Ledger {
    rows: Vec<LedgerSpec>,
    index: HashMap<&'static str, usize>,
    /// the run's world as the table's column (`LEDGER_WORLDS`), fixed when the table is read,
    /// so the score's sums (`ledger_row_scores`, `ledger_families`) read its knees without the
    /// anchors in hand; `None` for a world the table has no column for
    world: Option<usize>,
}

struct LedgerSpec {
    name: &'static str,
    units: &'static str,
    weight: f64,
    null: Option<f64>,
    /// the record's own scale in each of `LEDGER_WORLDS`, `None` where the record cannot be
    /// resampled to give one
    scales: [Option<f64>; 2],
    /// THE RECORD'S OWN VOTE ON ITS SIGN in each world (ruled 2026-10-27): the share of the
    /// record's resamples strictly on its side of the null less the share strictly on the other,
    /// floored at 0, as `record_bands -scales` measures it; the sign share is scaled by it
    /// (`ledger_vector`). `None` without a null, with the record at it, or without a scale
    resolutions: [Option<f64>; 2],
    /// THE KNEE in each world: the CRPS an indistinguishable world earns, the mean over the
    /// record's resamples of each one's CRPS under the others, in the row's scale, as `-scales`
    /// measures it; the row's CRPS passes through a dead zone at it (`soft`). `None` without a
    /// scale
    knees: [Option<f64>; 2],
}

/// The worlds a table carries a scale column for, in the table's column order, as anchor set
/// specs (`ms::anchors_named`).
const LEDGER_WORLDS: [&str; 2] = ["sp500", "nasdaq"];

/// The units classes, in the order the family aggregates are laid out (`ledger_families`).
const LEDGER_UNITS: [&str; 4] = ["return", "loss", "vol", "shape"];

impl Ledger {
    fn is_empty(&self) -> bool {
        self.rows.is_empty()
    }

    fn get(&self, name: &str) -> Option<&LedgerSpec> {
        self.index.get(name).map(|&i| &self.rows[i])
    }

    /// Row `k`'s record scale in the anchor set's world; `None` for a world the table has no
    /// column for, or a row it gives none in that world.
    fn scale(&self, k: usize, anchors: Anchors) -> Option<f64> {
        ledger_world(anchors).and_then(|i| self.rows[k].scales[i])
    }

    /// Row `k`'s resolution (`LedgerSpec::resolutions`) in the anchor set's world, as `scale`.
    fn resolution(&self, k: usize, anchors: Anchors) -> Option<f64> {
        ledger_world(anchors).and_then(|i| self.rows[k].resolutions[i])
    }

    /// Row `k`'s knee (`LedgerSpec::knees`) in the anchor set's world, as `scale`.
    fn knee(&self, k: usize, anchors: Anchors) -> Option<f64> {
        ledger_world(anchors).and_then(|i| self.rows[k].knees[i])
    }

    /// Row `k`'s knee in the run's world (`world`) for the score's sums: 0, no dead zone, where
    /// the table gives none -- which `ledger_vector` refuses by name for a graded row before any
    /// vector reaches a sum, and the objective digest carries the knees, so a resumed archive's
    /// vectors were scored under this table.
    fn knee_at(&self, k: usize) -> f64 {
        self.world
            .and_then(|i| self.rows[k].knees[i])
            .unwrap_or(0.0)
    }

    /// the table's text digest, its sign rule and the score's form, in the reference cache's key
    fn digest(&self) -> String {
        let text: String = self.rows.iter().map(ledger_line).collect();
        fnv(&format!(
            "{text}sign|{}\n{}\n",
            ledger_sign_rule(),
            ledger_form()
        ))
    }
}

/// The anchor set's world as the table's column (`LEDGER_WORLDS`); `None` for a world the table
/// has no column for.
fn ledger_world(anchors: Anchors) -> Option<usize> {
    LEDGER_WORLDS
        .iter()
        .position(|w| ms::anchors_named(w).name == anchors.name)
}

/// A table row as the digests write it: name, units, weight, null, then its scale, resolution
/// and knee in each world.
fn ledger_line(r: &LedgerSpec) -> String {
    let none = || "none".to_string();
    let cell = |v: Option<f64>| v.map_or_else(none, g8);
    format!(
        "{}|{}|{}|{}|{}|{}|{}|{}|{}|{}\n",
        r.name,
        r.units,
        g8(r.weight),
        cell(r.null),
        cell(r.scales[0]),
        cell(r.scales[1]),
        cell(r.resolutions[0]),
        cell(r.resolutions[1]),
        cell(r.knees[0]),
        cell(r.knees[1])
    )
}

/// The score's form, in the digests: the CRPS in record scales, clipped, through the knee's dead
/// zone, and the sign's weight.
fn ledger_form() -> String {
    format!(
        "crps|clip {}|soft knee|sign {}",
        g8(LEDGER_CLIP),
        g8(LEDGER_SIGN_K)
    )
}

/// The sign penalty (ruled 2026-10-23): a row whose model and record sit on opposite sides of its
/// null costs up to this many record scales, in proportion to the model's share of the separation
/// -- about what a row placed outside every history costs in CRPS (1.0-2.0 scales on the
/// simulator's paths), so a full crossing keeps the ratio to a miss the gap ledger gave it.
const LEDGER_SIGN_K: f64 = 3.0;
/// A history's reading is clipped to the record plus or minus this many scales before the CRPS,
/// so a ruined leg (-inf) or an undefined extreme counts as a large miss in proportion to its
/// share of the histories rather than making the row infinite.
const LEDGER_CLIP: f64 = 50.0;
/// The sign share's rule, in the digests, so an archive or reference scored under another is
/// refused: the share is scaled by the record's own vote on its sign, the table's resolution
/// (`ledger_vector`).
fn ledger_sign_rule() -> String {
    "vote".to_string()
}

/// THE RESIDUAL TERMS UNDER THE LEDGER OBJECTIVE: a fidelity gate the placement does not cover
/// costs one tier, as a full sign crossing does, in place of the CRPS dead zone.
const LEDGER_DEAD: f64 = LEDGER_SIGN_K;

/// THE PARETO RESERVE (`reserve_after`): a member the scalar admission discards is kept when no
/// member is at least as good on all eight family aggregates within this many record scales and
/// strictly better on one -- a tenth of the CRPS between a central row and one outside every
/// history; the archive, core and reserve, is capped at `LEDGER_ARCHIVE_CAP` members.
const LEDGER_EPS: f64 = 0.1;
const LEDGER_ARCHIVE_CAP: usize = 400;

/// The row table as the gap ledger tool writes it (`gapledger.py -units`): a header
/// `row<TAB>units<TAB>weight<TAB>null<TAB>scale sp500<TAB>scale nasdaq<TAB>resolution sp500<TAB>
/// resolution nasdaq<TAB>knee sp500<TAB>knee nasdaq`, then one row a line, the null a number or
/// `none`, each scale a positive number or `none`, each resolution a share in [0, 1] or `none`,
/// each knee a non-negative number or `none`. The run's world (`anchors`) fixes the column the
/// score's sums read (`Ledger::world`).
fn read_ledger(path: &str, anchors: Anchors) -> Ledger {
    if path.is_empty() {
        usage(
            "-objective ledger needs -ledger FILE, the row table the gap ledger tool writes (-units)",
        );
    }
    let text = read_text(path).unwrap_or_else(|| usage(&format!("-ledger: cannot read {path}")));
    let mut lines = text.lines().filter(|l| !l.trim().is_empty());
    let head: Vec<&str> = lines.next().unwrap_or("").split('\t').collect();
    let want = [
        "row",
        "units",
        "weight",
        "null",
        "scale sp500",
        "scale nasdaq",
        "resolution sp500",
        "resolution nasdaq",
        "knee sp500",
        "knee nasdaq",
    ];
    if head != want {
        usage(&format!(
            "-ledger {path}: the header is [{}], not {}: the ledger scores each row's CRPS in its \
             record's scale",
            head.join("|"),
            want.join("/")
        ));
    }
    let mut table = Ledger {
        world: ledger_world(anchors),
        ..Ledger::default()
    };
    for l in lines {
        let f: Vec<&str> = l.split('\t').collect();
        if f.len() != head.len() {
            usage(&format!(
                "-ledger {path}: a row of {} fields: [{l}]",
                f.len()
            ));
        }
        let units = LEDGER_UNITS
            .iter()
            .copied()
            .find(|u| *u == f[1].trim())
            .unwrap_or_else(|| usage(&format!("-ledger {path}: units [{}] on [{}]", f[1], f[0])));
        let weight: f64 = f[2]
            .trim()
            .parse()
            .unwrap_or_else(|_| usage(&format!("-ledger {path}: weight [{}] on [{}]", f[2], f[0])));
        let null =
            match f[3].trim() {
                "none" => None,
                v => Some(v.parse::<f64>().unwrap_or_else(|_| {
                    usage(&format!("-ledger {path}: null [{v}] on [{}]", f[0]))
                })),
            };
        let cell = |what: &str, v: &str, ok: fn(f64) -> bool| ledger_cell(path, f[0], what, v, ok);
        let name: &'static str = Box::leak(f[0].trim().to_string().into_boxed_str());
        if table.index.insert(name, table.rows.len()).is_some() {
            usage(&format!("-ledger {path}: [{name}] is listed twice"));
        }
        table.rows.push(LedgerSpec {
            name,
            units,
            weight,
            null,
            scales: [
                cell("scale", f[4], |x| x > 0.0),
                cell("scale", f[5], |x| x > 0.0),
            ],
            resolutions: [
                cell("resolution", f[6], |x| (0.0..=1.0).contains(&x)),
                cell("resolution", f[7], |x| (0.0..=1.0).contains(&x)),
            ],
            knees: [
                cell("knee", f[8], |x| x >= 0.0),
                cell("knee", f[9], |x| x >= 0.0),
            ],
        });
    }
    if table.is_empty() {
        usage(&format!("-ledger {path}: no rows"));
    }
    table
}

/// One of a table row's world cells (`read_ledger`): `none`, or a finite number `ok` admits,
/// refused by the row's name and the cell's kind otherwise.
fn ledger_cell(path: &str, row: &str, what: &str, v: &str, ok: fn(f64) -> bool) -> Option<f64> {
    match v.trim() {
        "none" => None,
        s => Some(
            s.parse::<f64>()
                .ok()
                .filter(|x| x.is_finite() && ok(*x))
                .unwrap_or_else(|| usage(&format!("-ledger {path}: {what} [{s}] on [{row}]"))),
        ),
    }
}

/// A graded row as the verdict prints it: its name, the percentile the verdict prints for it
/// (`model@` for a banded row, the model's median in the record's band; `record@` for a history
/// row, the record among the read's single histories), the model and record values, and a banded
/// row's record band (`model@ P% of lo..hi`), none on a history row.
type LedgerRow = (&'static str, usize, f64, f64, Option<(f64, f64)>);

/// THE GRADED ROWS OF ONE READ, in the verdict's print order: the fidelity rows that carry a
/// percentile, then the rows the placement alone grades (a history row with a record and a finite
/// reading that no fidelity or reported row already shows), as `print_placement` lists them.
fn ledger_rows(
    anchors: Anchors,
    st: &ms::WorldStats,
    rows: &[ms::FidelityRow],
    hrows: &[ms::HistoryRow],
) -> Vec<LedgerRow> {
    let shown: Vec<&str> = rows
        .iter()
        .map(|r| r.name)
        .chain(ms::reported_rows(anchors, st).iter().map(|r| r.0))
        .collect();
    let mut out: Vec<LedgerRow> = Vec::with_capacity(rows.len() + hrows.len());
    for r in rows {
        let p = match (r.ratio, r.pctile) {
            (Some(_), _) => r.record_band.and(r.record_pctile),
            (None, p) => p,
        };
        if let Some(p) = p {
            let band = r.ratio.and(r.record_band);
            out.push((r.name, p, r.model, r.real, band));
        }
    }
    for r in hrows {
        let Some(rec) = r.record else { continue };
        if shown.contains(&r.name) {
            continue;
        }
        let finite: Vec<f64> = r.reads.iter().copied().filter(|x| x.is_finite()).collect();
        if finite.is_empty() {
            continue;
        }
        out.push((
            r.name,
            ms::anchor_pctile(&finite, rec),
            ms::pctile(&finite, 0.5),
            rec,
            None,
        ));
    }
    out
}

/// A value as the verdict prints it, to two decimals: the ledger tool reads the printed line, so
/// the sign share is taken on the same figures.
fn printed(v: f64) -> f64 {
    java_format_f(v, 0, 2).parse().unwrap_or(f64::NAN)
}

/// ONE READ'S LEDGER VECTOR, in table order: `[crps, sign share, model]` a row -- the crps the
/// record's CRPS under the read's single histories of the row in the record's own scale
/// (`ledger_crps`, raw: the knee's dead zone is applied where the vector is summed, `soft`), the
/// sign share the model's part of the separation from the record when the two sit on opposite
/// sides of the row's null, |model - null| / (|model - null| + |record - null|), scaled by the
/// record's own vote on its sign (the table's resolution in this world), else 0, at eight digits
/// as the archive writes it -- and NaN on a row the read did not grade. A row whose printed model
/// or record is not a number, or without two readable histories (`hist`, each with its record),
/// is not graded; a graded row the table does not name, or gives no scale or no knee in this
/// world, or no resolution for its null, is an error, by name.
fn ledger_vector(
    graded: &[LedgerRow],
    hist: &[ms::HistoryRow],
    anchors: Anchors,
    table: &Ledger,
) -> Result<Vec<[f64; 3]>, String> {
    let mut v = vec![[f64::NAN; 3]; table.rows.len()];
    for (name, _, model, record, _) in graded {
        let (m, r) = (printed(*model), printed(*record));
        if !m.is_finite() || !r.is_finite() {
            continue;
        }
        let Some(&k) = table.index.get(*name) else {
            return Err(format!("the ledger table has no row [{name}]"));
        };
        let Some(scale) = table.scale(k, anchors) else {
            return Err(format!(
                "the ledger table has no scale for [{name}] in {}",
                anchors.name
            ));
        };
        let crps = hist
            .iter()
            .find(|h| h.name == *name)
            .and_then(|h| h.record.map(|rec| ledger_crps(&h.reads, rec, scale)))
            .unwrap_or(f64::NAN);
        if crps.is_nan() {
            continue;
        }
        if table.knee(k, anchors).is_none() {
            return Err(format!(
                "the ledger table has no knee for [{name}] in {}",
                anchors.name
            ));
        }
        let share = match table.rows[k].null {
            None => 0.0,
            Some(n) => {
                let Some(res) = table.resolution(k, anchors) else {
                    return Err(format!(
                        "the ledger table has no resolution for [{name}] in {}",
                        anchors.name
                    ));
                };
                let (a, b) = (m - n, r - n);
                if (a < 0.0 && 0.0 < b) || (b < 0.0 && 0.0 < a) {
                    (a.abs() / (a.abs() + b.abs())) * res
                } else {
                    0.0
                }
            }
        };
        v[k] = [crps, g8(share).parse().unwrap_or(share), *model];
    }
    Ok(v)
}

/// A row's CRPS in its record's scale: `ms::crps` of the record under the histories' readings,
/// each first clipped to the record plus or minus `LEDGER_CLIP` scales (a NaN reading is left
/// out, as `ms::crps` leaves it), divided by the scale. NaN under two readable histories.
fn ledger_crps(reads: &[f64], record: f64, scale: f64) -> f64 {
    let (lo, hi) = (record - LEDGER_CLIP * scale, record + LEDGER_CLIP * scale);
    let clipped: Vec<f64> = reads
        .iter()
        .map(|&x| if x.is_nan() { x } else { x.clamp(lo, hi) })
        .collect();
    ms::crps(&clipped, record) / scale
}

/// THE DEAD ZONE (ruled 2026-10-27): a row's CRPS through a knee at `k`, the CRPS an
/// indistinguishable world earns on the row (`LedgerSpec::knees`) -- quadratic inside it,
/// c^2 / 2k, linear past it, c - k / 2, the two meeting at the knee with one slope; no knee (0
/// or less) is the CRPS itself.
fn soft(c: f64, k: f64) -> f64 {
    if k <= 0.0 {
        c
    } else if c <= k {
        c * c / (2.0 * k)
    } else {
        c - k / 2.0
    }
}

/// Each graded row's ledger score, its weight times (the crps through its knee, `soft`, +
/// `LEDGER_SIGN_K` x sign share), in table order over the rows the read graded.
fn ledger_row_scores(v: &[[f64; 3]], table: &Ledger) -> Vec<(&'static str, f64)> {
    v.iter()
        .zip(&table.rows)
        .enumerate()
        .filter(|(_, (x, _))| x[0].is_finite())
        .map(|(k, (x, r))| {
            (
                r.name,
                r.weight * (soft(x[0], table.knee_at(k)) + LEDGER_SIGN_K * x[1]),
            )
        })
        .collect()
}

/// THE LEDGER SCORE of one read (the gap ledger tool's number on the same read, and the re-pick
/// tool's weighted sum on the archive's row): the row scores summed in table order, as Python's
/// float `sum` sums them (Neumaier's compensation, since 3.12), so the tools' sums are this one
/// bit for bit. Lower is better.
fn ledger_sum(v: &[[f64; 3]], table: &Ledger) -> f64 {
    let (mut s, mut c) = (0.0f64, 0.0f64);
    for (_, x) in ledger_row_scores(v, table) {
        let t = s + x;
        if s.abs() >= x.abs() {
            c += (s - t) + x;
        } else {
            c += (x - t) + s;
        }
        s = t;
    }
    if c != 0.0 && c.is_finite() { s + c } else { s }
}

/// THE EIGHT FAMILY AGGREGATES the Pareto reserve is pruned on: for each units class in
/// `LEDGER_UNITS` order, the sum of its rows' scaled CRPS through their knees (`soft`), then of
/// its rows' sign shares x `LEDGER_SIGN_K`, over the rows the read graded, every row at its full
/// weight of one.
fn ledger_families(v: &[[f64; 3]], table: &Ledger) -> Vec<f64> {
    let mut out = vec![0.0; 2 * LEDGER_UNITS.len()];
    for (i, (x, r)) in v.iter().zip(&table.rows).enumerate() {
        if !x[0].is_finite() {
            continue;
        }
        let k = LEDGER_UNITS
            .iter()
            .position(|u| *u == r.units)
            .unwrap_or(LEDGER_UNITS.len() - 1);
        out[2 * k] += soft(x[0], table.knee_at(i));
        out[2 * k + 1] += LEDGER_SIGN_K * x[1];
    }
    out
}

/// EPSILON-DOMINANCE on the family aggregates (lower is better): `b` dominates `a` when it is at
/// least as good on every aggregate within `LEDGER_EPS` and strictly better on one.
fn eps_dominates(b: &[f64], a: &[f64]) -> bool {
    b.iter().zip(a).all(|(y, x)| *y <= x + LEDGER_EPS) && b.iter().zip(a).any(|(y, x)| y < x)
}

/// THE PARETO RESERVE after one admission: the members the scalar admission discarded (`before`
/// less `core`, and the candidate when it did not enter) join the reserve unless a member of the
/// archive dominates them; a reserve member another member dominates is dropped (of two that
/// dominate each other within the epsilon, the better score stays); then the archive is capped at
/// `LEDGER_ARCHIVE_CAP` by the spread-keeping trim on the aggregates, the reserve alone giving way.
fn reserve_after(
    before: &[Member],
    core: &[Member],
    cand: &Member,
    mut reserve: Vec<Member>,
    table: &Ledger,
) -> Vec<Member> {
    let fam = |m: &Member| m.ledger.as_ref().map(|v| ledger_families(v, table));
    let in_core = |m: &Member| core.iter().any(|o| o.dials == m.dials);
    let discards: Vec<&Member> = before
        .iter()
        .chain(std::iter::once(cand))
        .filter(|m| !in_core(m))
        .collect();
    for d in discards {
        let Some(fd) = fam(d) else { continue };
        if reserve.iter().any(|o| o.dials == d.dials) {
            continue;
        }
        let dominated = core
            .iter()
            .chain(reserve.iter())
            .filter_map(fam)
            .any(|f| eps_dominates(&f, &fd));
        if !dominated {
            reserve.push(d.clone());
        }
    }
    let fams: Vec<Option<Vec<f64>>> = reserve.iter().map(fam).collect();
    let core_fams: Vec<Vec<f64>> = core.iter().filter_map(fam).collect();
    let keep: Vec<bool> = (0..reserve.len())
        .map(|i| {
            let Some(fi) = &fams[i] else { return false };
            if core_fams.iter().any(|f| eps_dominates(f, fi)) {
                return false;
            }
            !(0..reserve.len()).any(|j| {
                let Some(fj) = (j != i).then_some(&fams[j]).and_then(|f| f.as_ref()) else {
                    return false;
                };
                eps_dominates(fj, fi)
                    && (!eps_dominates(fi, fj)
                        || reserve[j].score < reserve[i].score
                        || (reserve[j].score == reserve[i].score && j < i))
            })
        })
        .collect();
    let mut reserve: Vec<Member> = reserve
        .into_iter()
        .zip(keep)
        .filter(|(_, k)| *k)
        .map(|(m, _)| m)
        .collect();
    while core.len() + reserve.len() > LEDGER_ARCHIVE_CAP && !reserve.is_empty() {
        let all: Vec<&Member> = core.iter().chain(reserve.iter()).collect();
        let vecs: Vec<Vec<f64>> = all.iter().map(|m| fam(m).unwrap_or_default()).collect();
        let scores: Vec<f64> = all.iter().map(|m| m.score).collect();
        let removable: Vec<bool> = (0..all.len()).map(|i| i >= core.len()).collect();
        match least_distinct_on(&vecs, &scores, &removable) {
            Some(i) => {
                reserve.remove(i - core.len());
            }
            None => {
                reserve.pop();
            }
        }
    }
    reserve
}

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
    /// record than the bar, in the judge's units (`hold_distance`: `judge_distance`'s, or a ledger
    /// row's log ratio), on any read of its primary arm is infeasible. A set is graded on each row's MEDIAN member distance, and the loss
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
    /// THE YARDSTICK each graded row's CRPS is summed in (`crps_rows`): the record's own resample
    /// sd where the row carries a record band, else the reference world's single-history sd
    /// (`reference_of`), frozen in the checkpoint so widening a candidate's spread cannot improve
    /// its score. A row with no yardstick is scored in its own read's sd.
    yardstick: HashMap<&'static str, f64>,
    /// THE PLACEMENT GATES: a candidate the record is not a typical history of, among its reads'
    /// histories pooled (`placed_together`), is infeasible. False for
    /// the seed worlds (`without_gap`), which root the lineages and are the worlds a re-solve
    /// starts from because they fail it; their placement is read and reported, as their gap rows
    /// are. Every candidate, and a `-holdout` re-score of every member, is held to it.
    place: bool,
    /// THE RECORD OVERRIDE (`-record FILE`): the graded rows' records the placement and the CRPS
    /// read, where the file names them (`record_overrides`); the overfit check's synthetic record.
    /// Applied to every history read, the reference's included.
    records: Option<HashMap<String, f64>>,
    /// THE OBJECTIVE'S FORM (`-objective`): the ledger score of a feasible read or its CRPS.
    kind: ObjectiveKind,
    /// THE LEDGER'S ROW TABLE (`-ledger FILE`), empty under the CRPS objective.
    ledger: std::sync::Arc<Ledger>,
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
            yardstick: self.yardstick.clone(),
            place: self.place,
            records: self.records.clone(),
            kind: self.kind,
            ledger: self.ledger.clone(),
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
            yardstick: self.yardstick.clone(),
            place: false,
            records: self.records.clone(),
            kind: self.kind,
            ledger: self.ledger.clone(),
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
            yardstick: self.yardstick.clone(),
            place: self.place,
            records: self.records.clone(),
            kind: self.kind,
            ledger: self.ledger.clone(),
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
/// How a candidate's reads decide membership, recorded as `membership`: the placement once over
/// their pooled histories (`placed_together`); an archive without the key placed each read alone.
const MEMBERSHIP: &str = "pooled";
const MEMBERSHIP_BEFORE: &str = "per-read";

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

/// THE REFERENCE WORLD READ: `NOREGRESS_READS` reads at the search's own ensemble on the
/// reference stream; the yardstick over them pooled (`yardstick_from`), and each graded row's
/// CRPS in that yardstick as the mean over the reads, priced as `read_paths` prices a candidate.
#[expect(
    clippy::too_many_arguments,
    reason = "the world, the ensemble, the seed, the records and the objective's form: what a \
              reference means"
)]
fn reference_of(
    w: &World,
    anchors: Anchors,
    paths: usize,
    years: usize,
    base: i64,
    records: Option<&HashMap<String, f64>>,
    kind: ObjectiveKind,
    table: &Ledger,
) -> Reference {
    let seeds: Vec<u64> = (1..=NOREGRESS_READS)
        .map(|j| (base as u64).wrapping_add(NOREGRESS_OFFSET + j * READ_STRIDE))
        .collect();
    // under the ledger objective the rows priced are the reads' ledger row scores, their mean
    let (reads, per): RefReads = match kind {
        ObjectiveKind::Ledger => seeds
            .iter()
            .map(|&s| ledger_read(w, anchors, paths, years, s, records, table))
            .unzip(),
        ObjectiveKind::Crps => (
            seeds
                .iter()
                .map(|&s| history_read(w, anchors, paths, years, s, records))
                .collect(),
            Vec::new(),
        ),
    };
    let yard = yardstick_from(anchors, &reads);
    let ym: HashMap<&'static str, f64> = yard.iter().copied().collect();
    let per: Vec<Vec<(&'static str, f64)>> = if kind == ObjectiveKind::Crps {
        reads.iter().map(|r| crps_rows(r, &ym)).collect()
    } else {
        per
    };
    let crps: Vec<(&'static str, f64)> = per[0]
        .iter()
        .map(|(name, _)| {
            let xs: Vec<f64> = per
                .iter()
                .filter_map(|p| p.iter().find(|(n, _)| n == name).map(|(_, c)| *c))
                .collect();
            (*name, xs.iter().sum::<f64>() / xs.len() as f64)
        })
        .collect();
    (g8_rows(yard), g8_rows(crps))
}

/// Rows at eight significant digits IN MEMORY, as the checkpoint writes every number: a fresh
/// run, a resumed one and the other twin then price against the same text.
fn g8_rows(rows: Rows) -> Rows {
    rows.into_iter()
        .map(|(n, v)| (n, g8(v).parse().unwrap_or(v)))
        .collect()
}

// ---- evaluation ---------------------------------------------------------------------------

/// Within its bounds on every rep and the record placed among the reps' pooled histories, and the
/// SUM over rows of each row's distance past the dead zone. The reading is the WORST across reps,
/// never the mean: a world that passes only on a lucky seed has not passed.
#[derive(Clone)]
struct Read {
    feasible: bool,
    /// THE OBJECTIVE of a feasible read: the record's CRPS under the read's single histories,
    /// summed over every graded row in its yardstick (`crps_rows`, the release rule's score), plus
    /// one dead zone per failed fidelity gate the placement does not cover and the regressions
    /// against `-noregress`; an infeasible read's is `fitness`'s dead-zone sum, for the log.
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
    /// THE LEDGER SCORE PER READ under that objective, `seed:score` a read (the transport arm's
    /// prefixed with its market), so the gap ledger tool's number on the same read checks the
    /// search's; empty under the CRPS objective
    ledger: Vec<String>,
    /// THE LEDGER VECTOR of the read that set `score` (the primary arm's worst read), what the
    /// archive stores per member (`ledger_vector`); `None` under the CRPS objective
    ledger_vec: Option<Vec<[f64; 3]>>,
    /// A READ WITHIN ITS BOUNDS awaiting the placement over every read's histories pooled, which
    /// `evaluate` reads and clears (`placed_together`); `None` on a read its bounds rejected
    due: Option<Box<Due>>,
}

/// A read within its bounds, before the placement over every read's histories pooled.
#[derive(Clone)]
struct Due {
    /// the read's history rows, the records overridden
    rows: Vec<ms::HistoryRow>,
    /// where the placement's failures go in `gate_fail`: after the uncovered fidelity gates,
    /// before the regressions
    at: usize,
    /// the read should the pooled placement reject it
    rejected: Rejected,
}

/// A read ranked as a rejection: `fitness`'s dead-zone sum without the extreme rows, its worst row
/// and its summed loss.
#[derive(Clone)]
struct Rejected {
    score: f64,
    raw: f64,
    worst: String,
    total: f64,
}

impl Read {
    /// This read once the pooled placement `pl` is read (`placed_together`): the rejection it ranks
    /// as when the placement `rejects`; else the placement's failures (thin units, or a seed
    /// world's failing ones) after its uncovered fidelity gates.
    fn placed(self, pl: &ms::Placement, rejects: bool) -> Read {
        let mut r = self;
        let Some(due) = r.due.take() else {
            return r;
        };
        if rejects {
            let x = due.rejected;
            return Read {
                feasible: false,
                score: x.score,
                raw: x.raw,
                worst: x.worst,
                total: x.total,
                gate_fail: pl.failures(),
                ledger: Vec::new(),
                ledger_vec: None,
                ..r
            };
        }
        r.gate_fail.splice(due.at..due.at, pl.failures());
        r
    }
}

/// THE MEMBERSHIP RULE over a candidate's reads (`ms::pooled_history_rows`): once every read is
/// within its bounds, the record is placed once among all their histories pooled, row by row in
/// read order. Each read passing its own placement rejected a typical history 16% (Nasdaq) and 13%
/// (S&P) of the time at two reads, the pool 11% and 9%, the rule's size at one read. A pooled
/// failure rejects every read; the seed worlds (`Objective::place` false) are placed and
/// reported, never rejected.
fn placed_together(reads: Vec<Read>, place: bool) -> Vec<Read> {
    if !reads.iter().all(|r| r.due.is_some()) {
        return reads.into_iter().map(|r| Read { due: None, ..r }).collect();
    }
    let rows: Vec<&[ms::HistoryRow]> = reads
        .iter()
        .filter_map(|r| r.due.as_ref().map(|d| d.rows.as_slice()))
        .collect();
    let pl = ms::placement(&ms::pooled_history_rows(&rows), ms::PLACEMENT_ALPHA);
    let rejects = place && pl.verdict == ms::PlacementVerdict::Fail;
    reads.into_iter().map(|r| r.placed(&pl, rejects)).collect()
}

/// The rows a read is ranked on, (term, name), and the worst of them, ties broken by name exactly
/// as the Scala harness's `max` on a (term, name) pair does: an infeasible read's without the
/// extreme rows, which it is taken never to have measured.
fn ranked(
    rows: &[(&'static str, f64, f64, f64)],
    feasible: bool,
) -> (Vec<(f64, &'static str)>, (f64, &'static str)) {
    let scored: Vec<(f64, &'static str)> = rows
        .iter()
        .filter(|(nm, _, _, _)| feasible || !ms::extreme_target_names().contains(nm))
        .map(|(nm, _, _, term)| (*term, *nm))
        .collect();
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
    (scored, worst)
}

/// An infeasible read's score, for the log: the dead-zone sum over its ranked rows.
fn dead_sum(scored: &[(f64, &str)], dead: f64) -> f64 {
    scored
        .iter()
        .map(|(term, _)| (term - dead).max(0.0))
        .sum::<f64>()
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
    // (7 of 18 feasible candidates were rejected that way at P = 50 in a measured run), two did not once.
    // The prefix fails on its bounds alone: membership is the placement over every read's histories
    // pooled (`placed_together`), which a prefix's cannot speak for
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
    let (st, reads) = ms::measure_keeping(main, years);
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
    // THE FIDELITY GATES THE PLACEMENT DOES NOT COVER -- the valuation dispersion band and the
    // client-ruler basket rows, the hand bands left -- each cost one dead zone, named in
    // `gate_fail` for a feasible candidate, so the log says which.
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
    // the ledger rows' histories, read here only where a held row is one of theirs and reused by
    // the ledger objective's read (`ledger_of`), so a read holding no ledger row is the read
    // before them bit for bit
    let lh: Option<Vec<ms::HistoryRow>> = (gated
        && obj.hold.iter().any(|(nm, _)| ms::LEDGER_ROWS.contains(nm)))
    .then(|| ms::ledger_histories(anchors, main));
    // the held rows' distances on this read, in the judge's units: past a bar is a failed gate
    let hold_miss: Vec<&'static str> = if gated {
        obj.hold
            .iter()
            .filter(|(nm, bar)| {
                hold_distance(anchors, nm, &hr, &st, lh.as_deref()).is_none_or(|d| d > *bar)
            })
            .map(|(nm, _)| *nm)
            .collect()
    } else {
        Vec::new()
    };
    // THE MEMBERSHIP RULE is not one read's to decide: the record is placed once among every
    // read's histories pooled (`placed_together`). A read within its bounds -- the gates, the gap
    // and the held rows -- keeps its history rows for it, so a rejected candidate never pays for
    // them; read from the readings the gates read, not a second pass over the horizons
    // (value-identical)
    let feasible = gated && gap_miss.is_empty() && hold_miss.is_empty();
    let hrows = feasible.then(|| {
        let mut hrows = ms::history_rows_reading(anchors, &st, main, years, &hr, Some(&reads));
        if let Some(over) = &obj.records {
            ms::override_records(&mut hrows, over);
        }
        hrows
    });
    let crps_read = hrows
        .as_ref()
        .map_or_else(Vec::new, |h| crps_rows(h, &obj.yardstick));
    // a read within its bounds names the uncovered fidelity gates; the placement's thin units (and
    // a seed world's failing ones) join them once it is read
    let gate_fail: Vec<String> = match &hrows {
        Some(_) => fid_fail.clone(),
        None if gated => gap_miss
            .iter()
            .map(|nm| format!("gap: {nm}"))
            .chain(hold_miss.iter().map(|nm| format!("hold: {nm}")))
            .collect(),
        None => default
            .iter()
            .flat_map(|cls| {
                checks
                    .iter()
                    .filter(move |(_, ok, c)| !ok && c == cls)
                    .map(|(nm, _, _)| nm.clone())
            })
            .collect(),
    };

    let ex = if feasible {
        hr.extreme_scores()
    } else {
        HashMap::new()
    };
    let (total, rows) = ms::fitness(anchors, &st, &ex);
    // AGAINST THE OUTGOING RECIPE (`-noregress`): each graded row's CRPS over the recipe's
    let crps: &[(&'static str, f64)] = &crps_read;
    // THE LEDGER OBJECTIVE: a feasible read's graded rows as the verdict grades them, scored as
    // the gap ledger tool scores the same read (`ledger_of`); the CRPS sum otherwise
    let ledger = hrows
        .as_ref()
        .and_then(|h| ledger_of(obj, anchors, (&st, &hr), h, main, lh.as_deref()));
    // under the ledger objective the regressions are priced on the rows' ledger scores, and the
    // uncovered fidelity gates at one tier each (`LEDGER_DEAD`)
    let regress = if feasible {
        regressions(obj, ledger.as_ref().map_or(crps, |l| l.2.as_slice()))
    } else {
        Vec::new()
    };
    let dead_units = if obj.kind == ObjectiveKind::Ledger {
        LEDGER_DEAD
    } else {
        dead
    };
    let (scored, worst) = ranked(&rows, feasible);
    // a feasible read: the summed CRPS in row order, a plain left fold, as the Scala harness's
    // `sum` is; then the uncovered fidelity gates, then the regressions. An infeasible read keeps
    // `fitness`'s dead-zone sum, so the log still ranks rejections
    let score = if feasible {
        ledger
            .as_ref()
            .map_or_else(|| crps.iter().map(|(_, c)| c).sum::<f64>(), |l| l.1)
            + dead_units * fid_fail.len() as f64
            + regress.iter().map(|(_, e)| e).sum::<f64>()
    } else {
        dead_sum(&scored, dead)
    };
    // the read as the rejection it ranks as should the pooled placement fail
    let due = hrows.map(|h| {
        let (total, rows) = ms::fitness(anchors, &st, &HashMap::new());
        let (scored, worst) = ranked(&rows, false);
        Box::new(Due {
            rows: h,
            at: fid_fail.len(),
            rejected: Rejected {
                score: dead_sum(&scored, dead),
                raw: worst.0,
                worst: worst.1.to_string(),
                total,
            },
        })
    });
    // the log names a regression past a dead zone; smaller ones are priced but not listed
    let gate_fail: Vec<String> = gate_fail
        .into_iter()
        .chain(
            regress
                .iter()
                .filter(|(_, e)| *e > dead_units)
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
        ledger: ledger
            .as_ref()
            .map_or_else(Vec::new, |l| vec![format!("{s}:{}", g8(l.1))]),
        ledger_vec: ledger.map(|l| l.0),
        due,
    }
}

/// A feasible read's ledger under that objective: its vector (`ledger_vector`), its score
/// (`ledger_sum`) and its row scores (`ledger_row_scores`, what `-noregress` prices), over the
/// graded rows and the ledger rows read on the read's own ensemble `main` (`ms::LEDGER_ROWS`,
/// `lh` where the read already has them, for a held ledger row); `None` under the CRPS objective.
/// A row the table does not name stops the search by name.
type LedgerRead = (Vec<[f64; 3]>, f64, Vec<(&'static str, f64)>);

fn ledger_of(
    obj: &Objective,
    anchors: Anchors,
    (st, hr): (&ms::WorldStats, &ms::HorizonReadings),
    hrows: &[ms::HistoryRow],
    main: &[ms::Path],
    lh: Option<&[ms::HistoryRow]>,
) -> Option<LedgerRead> {
    if obj.kind != ObjectiveKind::Ledger {
        return None;
    }
    let mut rows = ms::rows_from_readings(anchors, st, hr);
    let read;
    let lh = match lh {
        Some(lh) => lh,
        None => {
            read = ms::ledger_histories(anchors, main);
            &read
        }
    };
    rows.extend(ms::ledger_fidelity_rows(anchors, lh));
    let graded = ledger_rows(anchors, st, &rows, hrows);
    let hist = with_ledger_histories(anchors, hrows, lh);
    let v = ledger_vector(&graded, &hist, anchors, &obj.ledger).unwrap_or_else(|e| usage(&e));
    let sum = ledger_sum(&v, &obj.ledger);
    let per = ledger_row_scores(&v, &obj.ledger);
    Some((v, sum, per))
}

/// The histories a read's ledger scores: the graded rows' (`hrows`), then the ledger rows' with
/// their records (`ms::ledger_histories_recorded`), as `-historyrows` dumps them.
fn with_ledger_histories(
    anchors: Anchors,
    hrows: &[ms::HistoryRow],
    lh: &[ms::HistoryRow],
) -> Vec<ms::HistoryRow> {
    hrows
        .iter()
        .cloned()
        .chain(ms::ledger_histories_recorded(anchors, lh))
        .collect()
}

/// A reference read under the ledger objective: the read's history rows (the yardstick's) and
/// its row scores, read as `read_paths` reads a candidate.
#[expect(
    clippy::too_many_arguments,
    reason = "the world, the ensemble, the seed, the records and the table: what the read means"
)]
fn ledger_read(
    w: &World,
    anchors: Anchors,
    paths: usize,
    years: usize,
    s: u64,
    records: Option<&HashMap<String, f64>>,
    table: &Ledger,
) -> (Vec<ms::HistoryRow>, Vec<(&'static str, f64)>) {
    let vw = ms::verdict_world(anchors, w);
    let main = ms::sim_paths(&vw, paths, years, s);
    let (st, reads) = ms::measure_keeping(&main, years);
    let hr = ms::horizon_readings(anchors, &st, Some(&main), years, paths, s, w, true);
    let mut rows = ms::rows_from_readings(anchors, &st, &hr);
    let lh = ms::ledger_histories(anchors, &main);
    rows.extend(ms::ledger_fidelity_rows(anchors, &lh));
    let mut hrows = ms::history_rows_reading(anchors, &st, &main, years, &hr, Some(&reads));
    if let Some(over) = records {
        ms::override_records(&mut hrows, over);
    }
    let graded = ledger_rows(anchors, &st, &rows, &hrows);
    let hist = with_ledger_histories(anchors, &hrows, &lh);
    let v = ledger_vector(&graded, &hist, anchors, table).unwrap_or_else(|e| usage(&e));
    let per = ledger_row_scores(&v, table);
    (hrows, per)
}

/// The gap rows (`Objective::gap`) whose band this read misses: its banded reading outside the
/// record's joint band, or no reading at all.
/// A held row's distance on one read, in the judge's units (`-hold`): a single-history row's (the
/// multi-year and timing rows) is the record's percentile points from 50 among the read's own
/// histories, where the verdict places it, infinite under `EXTREME_MIN_HISTORIES` of them; a
/// ledger row's is `ledger_hold_distance`'s on the read's ledger histories `lh`; any other row's
/// is `judge_distance`'s.
fn hold_distance(
    anchors: Anchors,
    nm: &str,
    hr: &ms::HorizonReadings,
    st: &ms::WorldStats,
    lh: Option<&[ms::HistoryRow]>,
) -> Option<f64> {
    if ms::LEDGER_ROWS.contains(&nm) {
        return ledger_hold_distance(anchors, nm, lh?);
    }
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

/// A held LEDGER row's distance on one read: `|ln(model / record)|`, the fit-target form, the
/// model the row's figure as the ledger objective reads it (`ms::ledger_fidelity_rows`: the
/// median over the read's histories) and the record the set's (`ms::ledger_records`); infinite
/// where the ratio is not a positive number. `None` for a set without ledger records, or a name
/// that is no ledger row.
fn ledger_hold_distance(anchors: Anchors, nm: &str, lh: &[ms::HistoryRow]) -> Option<f64> {
    let rows = ms::ledger_fidelity_rows(anchors, lh);
    let r = rows.iter().find(|r| r.name == nm)?;
    let ratio = r.model / r.real;
    Some(if ratio.is_finite() && ratio > 0.0 {
        ms::ln_det(ratio).abs()
    } else {
        f64::INFINITY
    })
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

/// AGAINST THE OUTGOING RECIPE (`-noregress`): each graded row's CRPS in excess of the recipe's
/// on that row (`reference_of`), in the read's row order; a row the reference did not read costs
/// nothing; empty without a reference.
fn regressions(obj: &Objective, crps: &[(&'static str, f64)]) -> Vec<(&'static str, f64)> {
    obj.reference.as_ref().map_or_else(Vec::new, |reference| {
        crps.iter()
            .map(|(name, c)| {
                let r = reference
                    .iter()
                    .find(|(n, _)| n == name)
                    .map_or(f64::INFINITY, |(_, r)| *r);
                (*name, (c - r).max(0.0))
            })
            .collect()
    })
}

/// Each graded row's CRPS of the record under the read's single histories, in its yardstick
/// (`Objective::yardstick`; the read's own sd where a row has none); a row without two finite
/// readings is left out, as the placement leaves it unread.
fn crps_rows(
    hrows: &[ms::HistoryRow],
    yard: &HashMap<&'static str, f64>,
) -> Vec<(&'static str, f64)> {
    hrows
        .iter()
        .filter_map(|r| {
            let rec = r.record?;
            let c = ms::crps(&r.reads, rec);
            if c.is_nan() {
                return None;
            }
            let unit = yard
                .get(r.name)
                .copied()
                .unwrap_or_else(|| history_sd(&r.reads));
            (unit > 0.0).then_some((r.name, c / unit))
        })
        .collect()
}

/// The sd of the finite readings, 0 under two of them.
fn history_sd(xs: &[f64]) -> f64 {
    let v: Vec<f64> = xs.iter().copied().filter(|x| x.is_finite()).collect();
    if v.len() < 2 {
        return 0.0;
    }
    let m = v.iter().sum::<f64>() / v.len() as f64;
    (v.iter().map(|x| (x - m) * (x - m)).sum::<f64>() / (v.len() - 1) as f64).sqrt()
}

/// One read's history rows: the verdict world's ensemble at seed `s`, every graded row per
/// history at its record's horizon.
fn history_read(
    w: &World,
    anchors: Anchors,
    paths: usize,
    years: usize,
    s: u64,
    records: Option<&HashMap<String, f64>>,
) -> Vec<ms::HistoryRow> {
    let vw = ms::verdict_world(anchors, w);
    let main = ms::sim_paths(&vw, paths, years, s);
    let st = ms::measure(&main, years);
    let mut rows = ms::history_rows(anchors, &st, &main, years, paths, s, w);
    if let Some(over) = records {
        ms::override_records(&mut rows, over);
    }
    rows
}

/// THE YARDSTICK over the reference's pooled reads: a banded row's is the record's own resample
/// sd (`record_sd`), any other graded row's the pooled histories' sd. In row order.
fn yardstick_from(anchors: Anchors, reads: &[Vec<ms::HistoryRow>]) -> Vec<(&'static str, f64)> {
    reads[0]
        .iter()
        .filter(|r| r.record.is_some())
        .map(|r| {
            let unit = ms::record_sd(anchors, r.name).unwrap_or_else(|| {
                let pooled: Vec<f64> = reads
                    .iter()
                    .flat_map(|rd| {
                        rd.iter()
                            .find(|x| x.name == r.name)
                            .map_or(&[][..], |x| x.reads.as_slice())
                    })
                    .copied()
                    .collect();
                history_sd(&pooled)
            });
            (r.name, unit)
        })
        .collect()
}

/// The settings an archive's state names, with the keys it was written before given the value it
/// ran under, so a resume refuses rather than adopts: its members were admitted by another
/// standard. A checkpoint without `score` was scored on the worst row alone, and one without
/// `gates` gated on its own ensemble.
fn with_settings_before(prior: &mut HashMap<String, String>) {
    if !prior.contains_key("score") {
        prior.insert("score".into(), "worst-row".into());
    }
    // one written before `candidateSeeds` read its candidates a path stride apart
    if !prior.contains_key("candidateSeeds") {
        prior.insert("candidateSeeds".into(), "path-stride".into());
    }
    // one written before `noiseReads` read its seed noise off the reps' spread alone
    if !prior.contains_key("noiseReads") {
        prior.insert("noiseReads".into(), "reps".into());
    }
    // one written before `-hold` held no row
    if !prior.contains_key("hold") {
        prior.insert("hold".into(), "(none)".into());
    }
    // one written before `-fix` stepped every dial
    if !prior.contains_key("fix") {
        prior.insert("fix".into(), "(none)".into());
    }
    // one written before `-gate` gated on the verdict's default classes
    if !prior.contains_key("gateClasses") {
        prior.insert("gateClasses".into(), "realism,mechanism".into());
    }
    if !prior.contains_key("gates") {
        prior.insert("gates".into(), "search-ensemble".into());
    }
    // one written before `-stage` read every path at once, and one before `-project` stepped every dial
    if !prior.contains_key("stage") {
        prior.insert("stage".into(), "0".into());
    }
    if !prior.contains_key("project") {
        prior.insert("project".into(), "(none)".into());
    }
    // one written before `-cov` existed drew its children one dial at a time
    if !prior.contains_key("proposal") {
        prior.insert("proposal".into(), "dial".into());
    }
    // one without `transportWeight` priced its transport arm in full
    if !prior.contains_key("transportWeight") {
        let full = if prior.get("transport").is_none_or(|t| t == "(none)") {
            "(none)"
        } else {
            "1.0000"
        };
        prior.insert("transportWeight".into(), full.into());
    }
    // one written before `membership` placed the record on each read alone
    if !prior.contains_key("membership") {
        prior.insert("membership".into(), MEMBERSHIP_BEFORE.into());
    }
}

/// The settings this run records that differ from the archive's (`with_settings_before`).
fn drifted<'a>(
    settings: &'a [(String, String)],
    prior: &HashMap<String, String>,
) -> Vec<&'a (String, String)> {
    settings
        .iter()
        .filter(|(k, v)| prior.get(k).is_some_and(|p| p != v))
        .collect()
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
    let reads = placed_together(reads, obj.place);
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
        ledger: reads
            .iter()
            .flat_map(|r| r.ledger.iter().cloned())
            .collect(),
        // the read that set the score: the first at the maximum
        ledger_vec: reads
            .iter()
            .find(|r| r.score == hi)
            .and_then(|r| r.ledger_vec.clone()),
        due: None,
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
        ledger: a
            .ledger
            .iter()
            .cloned()
            .chain(b.ledger.iter().map(|x| format!("{}:{x}", tr.spec)))
            .collect(),
        ledger_vec: a.ledger_vec,
        due: None,
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

#[expect(
    clippy::too_many_arguments,
    reason = "every term the loss applies, named: a struct would hide what the digest records"
)]
fn objective_digest(
    anchors: Anchors,
    transport: Option<&Transport>,
    noregress: Option<(&str, &[(&'static str, f64)])>,
    gap: &[&str],
    hold: &[(&'static str, f64)],
    yardstick: &[(&'static str, f64)],
    record_name: &str,
    ledger: &Ledger,
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
    // the membership rule and the score's units: an archive built under another rule is refused
    let _ = writeln!(text, "placement|{}{record_name}", g8(ms::PLACEMENT_ALPHA));
    for (row, u) in yardstick {
        let _ = writeln!(text, "yard|{row}|{}", g8(*u));
    }
    // written only under the ledger objective, so a CRPS archive keeps its digest
    for r in &ledger.rows {
        let _ = write!(text, "ledger|{}", ledger_line(r));
    }
    if !ledger.rows.is_empty() {
        let _ = writeln!(text, "ledger sign|{}", ledger_sign_rule());
        let _ = writeln!(text, "ledger {}", ledger_form());
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

/// THE SEARCHED DIALS' RANGES, digested into the archive's settings (`ranges`): every dial's name
/// and bounds in table order, in the archive's own width, so an archive resumed after a range
/// moved is refused without `-force` (`seed_worlds_digest`'s hash).
fn ranges_digest() -> String {
    let mut text = String::new();
    for r in ranges() {
        let _ = writeln!(text, "{}\t{}\t{}", r.0, g8(r.1), g8(r.2));
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
    /// the ledger vector of the read that admitted it (`Read::ledger_vec`), `None` under CRPS
    ledger: Option<Vec<[f64; 3]>>,
}

fn names() -> Vec<&'static str> {
    ranges().iter().map(|r| r.0).collect()
}

/// THE SETTINGS ARE PART OF THE CHECKPOINT: without the ensemble and rules that judged them the
/// members cannot be reproduced, and a resume that changed a flag would mix standards silently.
/// Key/value so a later version copes with a row it does not recognise.
/// THE LEDGER COLUMNS of an archive written under that objective, after the descriptors: the
/// member's tier (`core`, the scalar admission's; `pareto`, the reserve's), its `ledger` score,
/// then `crps:ROW`, `sign:ROW` and `model:ROW` for every row of the table, in table order -- what
/// the re-pick tool re-weights after the fact, its weighted sum under the table's weights being
/// the `ledger` column.
fn ledger_columns(table: &Ledger) -> String {
    let mut s = String::from("\ttier\tledger");
    for r in &table.rows {
        let _ = write!(s, "\tcrps:{}\tsign:{}\tmodel:{}", r.name, r.name, r.name);
    }
    s
}

/// The ledger cells at full round-trip precision (`{}`), not the archive's eight digits: the
/// re-pick tool's weighted sum of the stored CRPS and sign shares must equal the `ledger` cell.
fn ledger_cells(m: &Member, table: &Ledger, tier: &str) -> String {
    let nan = || "NaN".to_string();
    let mut s = format!(
        "\t{tier}\t{}",
        m.ledger
            .as_ref()
            .map_or_else(nan, |v| ledger_sum(v, table).to_string())
    );
    for k in 0..table.rows.len() {
        let x = m.ledger.as_ref().and_then(|v| v.get(k)).copied();
        for j in 0..3 {
            let _ = write!(s, "\t{}", x.map_or_else(nan, |x| x[j].to_string()));
        }
    }
    s
}

#[expect(
    clippy::too_many_arguments,
    reason = "the archive, its reserve, the run's position, its noise and its settings: what a checkpoint is"
)]
fn write_archive(
    dir: &str,
    arc: &[Member],
    reserve: &[Member],
    generation: u64,
    evals: u64,
    noise: (f64, u64),
    cfg: &[(String, String)],
    table: &Ledger,
) {
    let mut out = String::new();
    let _ = writeln!(
        out,
        "name\tscore\traw\tworstRow\t{}\t{}{}",
        names().join("\t"),
        DESC_NAMES.join("\t"),
        if table.is_empty() {
            String::new()
        } else {
            ledger_columns(table)
        }
    );
    for (m, tier) in arc
        .iter()
        .map(|m| (m, "core"))
        .chain(reserve.iter().map(|m| (m, "pareto")))
    {
        let ds: Vec<String> = m.dials.iter().map(|x| g8(*x)).collect();
        let bs: Vec<String> = m.desc.iter().map(|x| g8(*x)).collect();
        let _ = writeln!(
            out,
            "{}\t{:.6}\t{:.6}\t{}\t{}\t{}{}",
            m.name,
            m.score,
            m.raw,
            m.worst,
            ds.join("\t"),
            bs.join("\t"),
            if table.is_empty() {
                String::new()
            } else {
                ledger_cells(m, table, tier)
            }
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

/// The archive's members: the core (the scalar admission's) and the Pareto reserve, which an
/// archive written under the CRPS objective does not carry. The ledger columns, when present, are
/// read off the header's own row names; the objective digest refuses a resume under another table.
fn read_archive(dir: &str) -> (Vec<Member>, Vec<Member>) {
    let Some(text) = read_text(&format!("{dir}/archive.tsv")) else {
        return (Vec::new(), Vec::new());
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
    let base = 4 + ranges().len() + DESC_NAMES.len();
    // the ledger columns: `tier`, `ledger`, then three a row, every row named three times
    let rows: Option<usize> = if head == expect {
        None
    } else {
        let cols: Vec<&str> = head.split('\t').collect();
        let shape = head.starts_with(&format!("{expect}\ttier\tledger\t"))
            && (cols.len() - base - 2).is_multiple_of(3)
            && cols[base + 2..].chunks(3).all(|c| {
                c[0].strip_prefix("crps:").is_some_and(|n| {
                    c[1].strip_prefix("sign:") == Some(n) && c[2].strip_prefix("model:") == Some(n)
                })
            });
        if !shape {
            usage(&format!(
                "{dir}/archive.tsv was written with different columns; it has [{head}]\n  this binary reads [{expect}] with or without the ledger columns"
            ));
        }
        Some((cols.len() - base - 2) / 3)
    };
    let want = base + rows.map_or(0, |n| 2 + 3 * n);
    let (mut core, mut reserve) = (Vec::new(), Vec::new());
    for l in text.lines().skip(1).filter(|l| !l.trim().is_empty()) {
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
        let ledger = rows.map(|n| {
            (0..n)
                .map(|k| {
                    let at = base + 2 + 3 * k;
                    [num(f[at]), num(f[at + 1]), num(f[at + 2])]
                })
                .collect::<Vec<[f64; 3]>>()
        });
        let m = Member {
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
            ledger,
        };
        if rows.is_some() && f[base] == "pareto" {
            reserve.push(m);
        } else {
            core.push(m);
        }
    }
    (core, reserve)
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
    records: Option<&HashMap<String, f64>>,
    record_name: &str,
    kind: ObjectiveKind,
    table: &Ledger,
) -> (String, Reference) {
    let digest = fnv(&format!(
        "{file_text}|{}|{paths}|{years}|{base}|{}{record_name}",
        anchors.name,
        reference_tag(kind, table)
    ));
    if let Some(r) = cached_reference(dir, &digest, kind, table) {
        return (digest, r);
    }
    let seeds: Vec<u64> = (0..rows.len())
        .map(|k| {
            (base as u64)
                .wrapping_add(NOREGRESS_OFFSET + (NOREGRESS_READS + 1 + k as u64) * READ_STRIDE)
        })
        .collect();
    let (reads, per): RefReads = match kind {
        ObjectiveKind::Ledger => rows
            .iter()
            .zip(&seeds)
            .map(|((_, w), &s)| ledger_read(w, anchors, paths, years, s, records, table))
            .unzip(),
        ObjectiveKind::Crps => (
            rows.iter()
                .zip(&seeds)
                .map(|((_, w), &s)| history_read(w, anchors, paths, years, s, records))
                .collect(),
            Vec::new(),
        ),
    };
    // the set's pooled predictive sd is the yardstick where a row has no record band
    let yard = yardstick_from(anchors, &reads);
    let ym: HashMap<&'static str, f64> = yard.iter().copied().collect();
    let per: Vec<Vec<(&'static str, f64)>> = if kind == ObjectiveKind::Crps {
        reads.iter().map(|r| crps_rows(r, &ym)).collect()
    } else {
        per
    };
    let crps: Vec<(&'static str, f64)> = per[0]
        .iter()
        .map(|(name, _)| {
            let xs: Vec<f64> = per
                .iter()
                .filter_map(|p| p.iter().find(|(n, _)| n == name).map(|(_, c)| *c))
                .collect();
            (*name, median(&xs))
        })
        .collect();
    let r = (g8_rows(yard), g8_rows(crps));
    write_reference(dir, &digest, &r, kind);
    (digest, r)
}

/// The reference cache key's tag for the objective's form: `crps`, or `ledger-` and the table's
/// digest, so a reference priced under one is never read under the other.
fn reference_tag(kind: ObjectiveKind, table: &Ledger) -> String {
    match kind {
        ObjectiveKind::Crps => "crps".to_string(),
        ObjectiveKind::Ledger => format!("ledger-{}", table.digest()),
    }
}

/// Graded rows with a value each, in row order.
type Rows = Vec<(&'static str, f64)>;
/// A reference's reads: each read's history rows, and under the ledger objective its row scores.
type RefReads = (Vec<Vec<ms::HistoryRow>>, Vec<Rows>);
/// A reference: the yardstick and the reference's CRPS per graded row, each in row order.
type Reference = (Rows, Rows);

/// The reference cached in `reference.tsv` under `digest`, at eight significant digits as the
/// checkpoint writes every number: the twins' readings differ in the last ulps, and a fresh run, a
/// resumed one and the other twin all price against this text. Rows are matched by name to the
/// graded rows this build knows (`history_row_names`).
fn cached_reference(
    dir: &str,
    digest: &str,
    kind: ObjectiveKind,
    table: &Ledger,
) -> Option<Reference> {
    let t = read_text(&format!("{dir}/reference.tsv"))?;
    let mut lines = t.lines();
    (lines.next()? == format!("digest\t{digest}")).then_some(())?;
    let names = history_row_names();
    let row_tag = reference_row_tag(kind);
    let (mut yard, mut rows) = (Vec::new(), Vec::new());
    for l in lines {
        let mut p = l.split('\t');
        let (tag, n, v) = (p.next()?, p.next()?, p.next()?.parse::<f64>().ok()?);
        // a ledger row is one of the table's; any other a history row this build knows
        let name = names
            .iter()
            .copied()
            .find(|x| *x == n)
            .or_else(|| table.get(n).map(|r| r.name));
        let Some(name) = name else { continue };
        match tag {
            "yard" => yard.push((name, v)),
            t if t == row_tag => rows.push((name, v)),
            _ => return None,
        }
    }
    (!yard.is_empty()).then_some((yard, rows))
}

/// The reference's priced rows' tag in `reference.tsv`: each row's CRPS, or its ledger score.
fn reference_row_tag(kind: ObjectiveKind) -> &'static str {
    match kind {
        ObjectiveKind::Crps => "crps",
        ObjectiveKind::Ledger => "led",
    }
}

fn write_reference(dir: &str, digest: &str, r: &Reference, kind: ObjectiveKind) {
    let mut out = format!("digest\t{digest}\n");
    for (n, v) in &r.0 {
        let _ = writeln!(out, "yard\t{n}\t{}", g8(*v));
    }
    for (n, v) in &r.1 {
        let _ = writeln!(out, "{}\t{n}\t{}", reference_row_tag(kind), g8(*v));
    }
    write_text(&format!("{dir}/reference.tsv"), &out);
}

/// Every graded row name a history read can carry: the fit targets, the macro panel's, the bars',
/// the sectors', the satellite's and the dividend yield's. A row missing here is dropped from a
/// cached reference, and the objective digest of a resumed run then disagrees with the fresh one.
fn history_row_names() -> Vec<&'static str> {
    let mut v: Vec<&'static str> = ms::fit_targets(ms::anchors_named("sp500"))
        .into_iter()
        .chain(ms::fit_targets(ms::anchors_named("nasdaq")))
        .map(|t| t.0)
        .collect();
    v.extend(
        ms::macro_placement_rows(ms::anchors_named("sp500"))
            .iter()
            .map(|r| r.0),
    );
    v.extend(ms::BAR_ROWS);
    v.extend(ms::SECTOR_ROWS);
    v.extend(ms::SATELLITE_ROWS);
    v.push(ms::DIVIDEND_ROW);
    v.sort_unstable();
    v.dedup();
    v
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

/// THE SAME TRIM on any vectors -- the Pareto reserve's family aggregates -- with only the
/// `removable` entries eligible: of the closest pair with a removable member, the one that scores
/// worse goes, or the removable one when the worse is not.
fn least_distinct_on(vecs: &[Vec<f64>], scores: &[f64], removable: &[bool]) -> Option<usize> {
    let n = vecs.len();
    let k = vecs.iter().map(Vec::len).max().unwrap_or(0);
    let span: Vec<f64> = (0..k)
        .map(|j| {
            let xs: Vec<f64> = vecs
                .iter()
                .filter_map(|v| v.get(j).copied())
                .filter(|x| x.is_finite())
                .collect();
            if xs.is_empty() {
                0.0
            } else {
                xs.iter().copied().fold(f64::NEG_INFINITY, f64::max)
                    - xs.iter().copied().fold(f64::INFINITY, f64::min)
            }
        })
        .collect();
    if n < 2 || span.iter().all(|s| *s <= 0.0) {
        return None;
    }
    let dist = |a: &[f64], b: &[f64]| -> f64 {
        (0..span.len())
            .filter(|&j| span[j] > 0.0)
            .filter_map(|j| {
                let (x, y) = (a.get(j)?, b.get(j)?);
                (x.is_finite() && y.is_finite()).then(|| ((x - y) / span[j]).powi(2))
            })
            .sum::<f64>()
            .sqrt()
    };
    let mut worst: Option<(f64, usize)> = None;
    for i in 0..n {
        for j in (i + 1)..n {
            if !(removable[i] || removable[j]) {
                continue;
            }
            let d = dist(&vecs[i], &vecs[j]);
            if worst.is_none_or(|(w, _)| d < w) {
                let loser = if scores[i] > scores[j] { i } else { j };
                let loser = if removable[loser] {
                    loser
                } else {
                    i + j - loser
                };
                worst = Some((d, loser));
            }
        }
    }
    worst.map(|(_, i)| i)
}

/// The archive as a worlds JSON in the sidecar's own key format -- what a consumer runs a strategy
/// across.
fn export_worlds(dir: &str, file: &str, seed_for: &dyn Fn(&str) -> World) {
    let (arc, _) = read_archive(dir);
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

#[expect(
    clippy::too_many_lines,
    reason = "one help text, a line per option; split, the options would no longer read in one place"
)]
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
  -reps K       ; reads of a candidate (default 2): its bounds (the gate classes, -gap, -hold) on
                ;   every read, the placement once over all their histories pooled; at 1 each
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
  -dead D       ; the price, in anchor sds, of a fidelity gate the placement does not cover (the
                ;   valuation dispersion band, a basket row) and the size of regression the log names
                ;   (default 0.5); an infeasible read's logged score is still the dead-zone sum
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
                ;   timing rows), |ln(model/target)| on any other. A ledger row (e.g. 'declines 10%+
                ;   a century<=0.40') is held at |ln(model/record)|, the model the read's median
                ;   history as the ledger objective reads it.
                ;   What -gap is to a band's edges, for a distance: a priced row is traded away, and
                ;   an archive ends where the loss pulls it whatever it was seeded from.  The seed
                ;   worlds are exempt
  -gate C       ; comma-separated gate classes a candidate must pass on every read of its primary
                ;   arm to be feasible: realism, mechanism, fidelity or all (default
                ;   realism,mechanism, the verdict's own).  With fidelity the bands a search
                ;   otherwise only prices gate too, which every member of a calibration set has to
                ;   pass anyway; realism is always in
  -record F     ; a record OVERRIDE, row<TAB>value a line: the rows named read these as their
                ;   records in the placement and the score (the reference's too), so a synthetic
                ;   record cut from one history of a designed world can be fitted and the fit judged
                ;   (-validate -record F): the overfit check. Named in the objective digest
  -objective K  ; what a feasible read scores: ledger (default), the ledger score as the gap
                ;   ledger tool computes it on the same read -- over every graded row the row's
                ;   weight times the record's CRPS under the read's single histories in the
                ;   record's own scale (each reading clipped to the record +- 50 scales) plus 3
                ;   x the sign share on opposite sides of the null, scaled by the record's own
                ;   vote on its sign (resolution, from its resamples); each row's CRPS through a
                ;   dead zone at the CRPS an indistinguishable world earns (knee, from the same
                ;   resamples), quadratic inside, linear beyond -- or crps, the record's CRPS in
                ;   the
                ;   -noregress yardstick. Feasibility (the placement, the gates) and -noregress
                ;   are the same under either; the log's `ledger` column carries each read's
                ;   score as seed:score for the tool to check
  -ledger F     ; the ledger's row table, row<TAB>units<TAB>weight<TAB>null<TAB>scale sp500<TAB>
                ;   scale nasdaq a line as the gap ledger tool writes it (-units); required under
                ;   -objective ledger, and a graded row it does not name, or gives no scale in the
                ;   run's world, stops the search by name. Under it the archive carries each
                ;   member's tier (core, the scalar admission's; pareto, the reserve of members no
                ;   other is at least as good as on all eight family aggregates -- each units
                ;   class's summed scaled CRPS and summed sign shares x 3 -- within 0.1 scales and
                ;   strictly better on one, the whole capped at 400 by the spread trim on those
                ;   aggregates), its `ledger` score and, per table row, crps:ROW, sign:ROW and
                ;   model:ROW, for re-weighting after the fact; a fidelity gate the placement does
                ;   not cover costs 3 scales and -noregress prices each row's ledger score over
                ;   the recipe's
  -noregressset F ; -noregress against a SET's median member: F as -seedworlds, one read each
  -noregress R  ; price every graded row whose CRPS exceeds recipe R's on that row by the
                ;   difference (R read at the search's ensemble, the mean of six reads, which also
                ;   set the yardstick): the release rule, so no row drifts out unpaid
  -seedset KV   ; comma-separated DIAL=VALUE (e.g. 'overshoot=0.8,recessBase=1') applied to every
                ;   seed world before the search starts: the lineage roots at the recipe moved to a
                ;   form found by hand, and the quality bar is judged against that world.  Searched
                ;   dials, or the probe dials no child moves (recessStep, recessSteps);
                ;   recorded in the checkpoint with the seed worlds' digest
  -fix DIALS    ; comma-separated dials (e.g. 'disasterSize,disasterOvershoot') every child keeps
                ;   at its parent's value, so at the seed's: the dials read off the record rather
                ;   than solved.  The draws are made as without it, so the other dials step alike
  -stage P      ; read a candidate's first P paths alone and reject it there if they fail two
                ;   or more of its bounds (never its placement, the pooled reads');
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
                ;   MEMBERSHIP is the placement (the calibrated rule, docs/MarketSimWorlds.md): the
                ;   record must be a typical single history of the candidate's primary arm over
                ;   every graded row at once, p > 0.10, placed once among all its reads' histories
                ;   pooled; a candidate it fails is infeasible, the log naming the units.  THE SCORE of a feasible read is the record's CRPS under
                ;   the read's single histories summed over the graded rows, each in its yardstick
                ;   (the record's own resample sd, else the reference world's history sd)
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
    /// the record override file (`-record`), empty for the record itself
    record: String,
    /// the objective's form (`-objective`): `ledger` or `crps`
    objective: String,
    /// the ledger's row table (`-ledger FILE`)
    ledger: String,
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
        record: String::new(),
        objective: "ledger".into(),
        ledger: String::new(),
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
            "-record" => c.record = need(&mut i, "-record"),
            "-objective" => c.objective = need(&mut i, "-objective"),
            "-ledger" => c.ledger = need(&mut i, "-ledger"),
            "-depth" => usage(
                "-depth is retired: membership is the placement, the calibrated rule, on every read",
            ),
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

/// The (generation, descriptors) of every admitted candidate in a `log.tsv`'s text. The admitted
/// flag is read by its position after the descriptors, not as the line's last cell: columns
/// appended after it (the ledger) would otherwise hide every admission.
fn admitted_trace(log: &str) -> Vec<(u64, Vec<f64>)> {
    let nd = DESC_NAMES.len();
    log.lines()
        .skip(1)
        .filter_map(|l| {
            let f: Vec<&str> = l.split('\t').collect();
            if f.len() < 10 + nd || f[9 + nd] != "true" {
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
}

// ---- main -------------------------------------------------------------------------------------

#[expect(
    clippy::too_many_lines,
    clippy::cognitive_complexity,
    reason = "a sequence of independent modes -- export, prune, holdout, search -- each short, and               splitting them would separate each from the settings it is judged under"
)]
fn main() {
    #[cfg(feature = "fast-alloc")]
    fast_alloc::reuse_freed_memory();
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
                // a searched dial, or one of the probe dials no child moves (`ms::probe_dials`)
                let set = rs
                    .iter()
                    .find(|r| r.0 == k.trim())
                    .map(|r| r.3)
                    .or_else(|| {
                        ms::probe_dials()
                            .iter()
                            .find(|p| p.0 == k.trim())
                            .map(|p| p.1)
                    })
                    .unwrap_or_else(|| {
                        usage(&format!(
                            "-seedset names [{}], which is not a searched dial or a probe dial",
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
    // THE OBJECTIVE'S FORM: the ledger score by default, its row table read here and refused
    // incomplete at the first read that names a row it lacks; `-export` and `-prune` score nothing
    let kind = ObjectiveKind::parse(&c.objective);
    let ledger = std::sync::Arc::new(
        if kind == ObjectiveKind::Ledger && !(c.prune || !c.export_to.is_empty()) {
            read_ledger(&c.ledger, anchors)
        } else {
            Ledger::default()
        },
    );
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
        yardstick: HashMap::new(),
        place: true,
        records: None,
        kind,
        ledger: ledger.clone(),
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

    // THE RECORD OVERRIDE (`-record`): the overfit check's synthetic record, named in every digest
    let records: Option<HashMap<String, f64>> =
        (!c.record.is_empty()).then(|| ms::record_overrides(&c.record));
    let record_name = if c.record.is_empty() {
        String::new()
    } else {
        format!("|record:{}", fnv(&read_text(&c.record).unwrap_or_default()))
    };
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
            let (digest, r) = set_reference(
                &rows,
                &text,
                anchors,
                c.paths,
                c.years,
                c.base,
                &c.out,
                records.as_ref(),
                &record_name,
                kind,
                &ledger,
            );
            noregress_name = format!("set:{digest}");
            println!(
                "noregress: the median member of {} ({} worlds) at {} x {}y; a row's CRPS over the median member's costs the difference",
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
            let digest = fnv(&format!(
                "{}|{}|{}|{}|{}|{}{record_name}",
                c.noregress,
                anchors.name,
                c.paths,
                c.years,
                c.base,
                reference_tag(kind, &ledger)
            ));
            let r = cached_reference(&c.out, &digest, kind, &ledger).unwrap_or_else(|| {
                let r = reference_of(
                    &w,
                    anchors,
                    c.paths,
                    c.years,
                    c.base,
                    records.as_ref(),
                    kind,
                    &ledger,
                );
                write_reference(&c.out, &digest, &r, kind);
                r
            });
            println!(
                "noregress: {} at {} x {}y, the mean of {NOREGRESS_READS} reads; a row's {} over the recipe's costs the difference",
                c.noregress,
                c.paths,
                c.years,
                match kind {
                    ObjectiveKind::Crps => "CRPS",
                    ObjectiveKind::Ledger => "ledger score",
                }
            );
            Some(r)
        }
    };
    // THE YARDSTICK: the reference's, or the first seed world's read the same way, frozen in the
    // checkpoint; `-export` and `-prune` score nothing and carry none
    let (yardstick, reference): (Rows, Option<Rows>) = match reference {
        Some((yard, crps)) => (yard, Some(crps)),
        None if c.prune || !c.export_to.is_empty() => (Vec::new(), None),
        None => {
            let (_, w) = pool
                .first()
                .unwrap_or_else(|| usage("no seed world to set the yardstick from"));
            let digest = fnv(&format!(
                "yard|{}|{}|{}|{}|{}{record_name}",
                ms::world_digest(w),
                anchors.name,
                c.paths,
                c.years,
                c.base
            ));
            let (yard, _) = cached_reference(&c.out, &digest, kind, &ledger).unwrap_or_else(|| {
                let r = reference_of(
                    w,
                    anchors,
                    c.paths,
                    c.years,
                    c.base,
                    records.as_ref(),
                    kind,
                    &ledger,
                );
                write_reference(&c.out, &digest, &r, kind);
                r
            });
            (yard, None)
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
    // rows included, the other extreme rows not, or a ledger row where the set carries ledger
    // records (`ledger_hold_distance`), and a finite distance at or above 0 in the judge's units
    let ledger_held: &[&'static str] = if ms::ledger_records(anchors).is_some() {
        &ms::LEDGER_ROWS
    } else {
        &[]
    };
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
                .chain(ledger_held.iter().copied())
                .find(|n| {
                    *n == row.trim()
                        && (ms::is_multi_year(n) || !ms::extreme_target_names().contains(n))
                })
                .unwrap_or_else(|| {
                    usage(&format!(
                        "-hold names [{}], which is not a graded or ledger row of [{}]",
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
        if ms::SEARCH_INV_DIALS.contains(&dial.trim()) {
            usage(&format!(
                "-project solves a dial on a straight slope, and [{dial}] is searched in its tightness"
            ));
        }
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
        yardstick: yardstick.iter().copied().collect(),
        place: true,
        records: records.clone(),
        kind,
        ledger: ledger.clone(),
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
            &yardstick,
            &record_name,
            &obj.ledger,
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
        // the searched dials' ranges, which the steps, the separation and the spread-keeping
        // trim are all measured in: a resume under wider or narrower ones steps a different
        // space and keeps a different archive (`ranges_digest`)
        ("ranges".into(), ranges_digest()),
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
            format!(
                "ln1p:{};tight:{}",
                ms::SEARCH_LOG_DIALS.join(","),
                ms::SEARCH_INV_DIALS.join(",")
            ),
        ),
        ("membership".into(), MEMBERSHIP.to_string()),
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
        ("score".into(), obj.kind.label().to_string()),
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

    let (loaded, loaded_reserve) = read_archive(&c.out);
    if !loaded.is_empty() {
        with_settings_before(&mut prior);
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
        let drift = drifted(&settings, &prior);
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
                // the ledger score per read, for the gap ledger tool to check on the same seed
                if !r.ledger.is_empty() {
                    println!("    ledger: {}", r.ledger.join(" "));
                }
                // a rejected seed names the gates it failed: its worst row is a fitness row, not
                // the reason
                if !r.feasible {
                    println!("    fails: {}", r.gate_fail.join("; "));
                }
                let thin: Vec<&str> = r
                    .gate_fail
                    .iter()
                    .filter(|f| f.starts_with("placement: "))
                    .map(String::as_str)
                    .collect();
                if r.feasible && !thin.is_empty() {
                    println!("    {}", thin.join("; "));
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
                ledger: r.ledger_vec.clone(),
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
        write_archive(
            &c.out,
            &kept,
            &loaded_reserve,
            gen0,
            evals0,
            (nsum0, nn0),
            &settings,
            &ledger,
        );
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

    write_archive(
        &c.out,
        &start_arc,
        &loaded_reserve,
        gen0,
        evals0,
        (nsum0, nn0),
        &settings,
        &ledger,
    );
    // THE HEADER IS A CONTRACT, and it is written only when the log is absent, so a resume
    // after a column was added would append wider rows under the narrower header and quietly
    // ragged the file. Refuse instead: the run that wrote those rows read a different log.
    let header = format!(
        "gen\teval\tparent\tfeasible\tscore\traw\tworstRow\tseconds\t{}\tgateFail\tadmitted\tledger\n",
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
    let mut trace: Vec<(u64, Vec<f64>)> = read_text(&format!("{}/log.tsv", c.out))
        .map(|t| admitted_trace(&t))
        .unwrap_or_default();
    // a running seed-noise estimate from each candidate's spread across its own reps, carried across generations
    let mut noise_sum = nsum0;
    let mut noise_n = nn0;
    let mut arc = start_arc;
    // THE PARETO RESERVE (`reserve_after`), under the ledger objective: the non-dominated members
    // the scalar admission discards, kept beside the core and never drawn from as parents
    let mut reserve: Vec<Member> = loaded_reserve;
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
                let cand = Member {
                    name: parent.name.clone(),
                    dials: child,
                    score: r.score,
                    raw: r.raw,
                    worst: r.worst.clone(),
                    desc: r.desc.clone(),
                    ledger: r.ledger_vec.clone(),
                };
                let before: Vec<Member> = if obj.kind == ObjectiveKind::Ledger {
                    arc.clone()
                } else {
                    Vec::new()
                };
                let (next, entered) = admit(arc, cand.clone(), c.sep, c.keep, noise);
                arc = next;
                took = entered;
                if obj.kind == ObjectiveKind::Ledger {
                    reserve = reserve_after(&before, &arc, &cand, reserve, &obj.ledger);
                }
                if took {
                    trace.push((g, r.desc.clone()));
                    held.insert(child_key, round8(r.held));
                }
            }
            log.push(format!(
                "{g}\t{}\t{}\t{}\t{:.6}\t{:.6}\t{}\t{:.3}\t{}\t{}\t{took}\t{}",
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
                gate_fail,
                r.ledger.join(" ")
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
        write_archive(
            &c.out,
            &arc,
            &reserve,
            g + 1,
            evals,
            (noise_sum, noise_n),
            &settings,
            &ledger,
        );
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
mod ledger_tests {
    use super::*;

    /// A table for the nasdaq world, every row at scale 1, resolution 1 and knee 0: the score's
    /// old form, before the vote and the knee.
    fn table_of(rows: &[(&'static str, &'static str, f64, Option<f64>)]) -> Ledger {
        let mut t = Ledger {
            world: ledger_world(nasdaq()),
            ..Ledger::default()
        };
        for (name, units, weight, null) in rows {
            t.index.insert(name, t.rows.len());
            t.rows.push(LedgerSpec {
                name,
                units,
                weight: *weight,
                null: *null,
                scales: [Some(1.0), Some(1.0)],
                resolutions: [Some(1.0), Some(1.0)],
                knees: [Some(0.0), Some(0.0)],
            });
        }
        t
    }

    fn hist_of(name: &'static str, reads: &[f64], record: f64) -> ms::HistoryRow {
        ms::HistoryRow {
            name,
            horizon: 1,
            reads: reads.to_vec(),
            record: Some(record),
        }
    }

    fn nasdaq() -> Anchors {
        ms::anchors_named("nasdaq")
    }

    fn score_of(
        rows: &[LedgerRow],
        hist: &[ms::HistoryRow],
        table: &Ledger,
    ) -> Result<f64, String> {
        ledger_vector(rows, hist, nasdaq(), table).map(|v| ledger_sum(&v, table))
    }

    /// The score is weight x (the record's CRPS under the histories in the world's record scale,
    /// plus 3 x sign share), the share taken on the printed two-decimal figures, a level never on
    /// the wrong side; a row the table lacks, or gives no scale in this world, refused by name.
    #[test]
    fn the_ledger_score_is_the_crps_in_record_scales_plus_the_sign() {
        let mut table = table_of(&[
            ("a", "vol", 2.0, Some(1.0)),
            ("b", "loss", 3.0, None),
            ("c", "shape", 1.0, Some(0.0)),
        ]);
        // a's nasdaq scale 0.04 leaves 1 outside the record's 0.92 +- 1.645 scales (0.0658)
        table.rows[0].scales = [Some(0.5), Some(0.04)];
        table.rows[1].scales = [Some(4.0), Some(2.0)];
        table.rows[2].scales = [None, Some(1.0)];
        let rows: Vec<LedgerRow> = vec![
            // a ratio on the wrong side of 1: share 0.02 / (0.02 + 0.08)
            ("a", 96, 1.02, 0.92, None),
            // a level: the CRPS alone
            ("b", 10, -80.0, -80.0, None),
        ];
        let hist = [
            hist_of("a", &[1.0, 1.1], 0.92),
            hist_of("b", &[-90.0, -80.0, -70.0], -80.0),
        ];
        // a: E|X - y| 0.13, E|X - X'| 0.05, so 0.105 in the nasdaq scale 0.04;
        // b: 20/3 - 40/9 = 20/9 in the scale 2
        let want = 2.0 * (2.625 + 3.0 * 0.2) + 3.0 * (20.0 / 9.0 / 2.0);
        let got = score_of(&rows, &hist, &table).expect("every row named");
        assert!((got - want).abs() < 1e-9, "{got} against {want}");
        let missing = score_of(&[("tail hedge", 50, 0.0, 0.0, None)], &hist, &table);
        assert_eq!(
            missing,
            Err("the ledger table has no row [tail hedge]".to_string())
        );
        let no_scale = ledger_vector(
            &[("c", 50, 0.1, 0.15, None)],
            &[hist_of("c", &[0.1, 0.2], 0.15)],
            ms::anchors_named("sp500"),
            &table,
        );
        assert_eq!(
            no_scale,
            Err(format!(
                "the ledger table has no scale for [c] in {}",
                ms::anchors_named("sp500").name
            ))
        );
        // the vector in table order, NaN on a row the read did not grade, and the families:
        // loss crps 10/9 / signs 0; vol crps 2.625 / signs 0.6
        let v = ledger_vector(&rows, &hist, nasdaq(), &table).expect("named");
        assert!(v[2][0].is_nan() && v[1][1] == 0.0 && v[0][2] == 1.02);
        let fam = ledger_families(&v, &table);
        let want_fam = [0.0, 0.0, 10.0 / 9.0, 0.0, 2.625, 0.6, 0.0, 0.0];
        assert!(
            fam.iter().zip(want_fam).all(|(a, b)| (a - b).abs() < 1e-9),
            "{fam:?}"
        );
    }

    /// A ruined leg (-inf) or a runaway history is clipped to the record +- 50 scales, a large miss
    /// in proportion to its share; a NaN reading is left out; under two readable histories the
    /// row is not graded.
    #[test]
    fn a_ruined_history_is_a_clipped_miss_and_two_readings_are_needed() {
        let table = table_of(&[("c", "shape", 1.0, Some(0.0))]);
        let row: Vec<LedgerRow> = vec![("c", 50, 0.15, 0.15, None)];
        let hist = [hist_of("c", &[f64::NEG_INFINITY, 0.1, 0.2, f64::NAN], 0.15)];
        let v = ledger_vector(&row, &hist, nasdaq(), &table).expect("named");
        let want = ms::crps(&[0.15 - 50.0, 0.1, 0.2], 0.15);
        assert!(want.is_finite() && want > 5.0, "{want}");
        assert_eq!(v[0][0].to_bits(), want.to_bits());
        let thin = [hist_of("c", &[0.1, f64::NAN], 0.15)];
        let v = ledger_vector(&row, &thin, nasdaq(), &table).expect("named");
        assert!(v[0][0].is_nan(), "one readable history grades nothing");
        let none = ledger_vector(&row, &[], nasdaq(), &table).expect("named");
        assert!(none[0][0].is_nan(), "no histories grade nothing");
    }

    /// A ledger row held by `-hold` is held at |ln(model / record)|, the model the median over the
    /// read's histories as the ledger objective reads it (a NaN history left out), infinite where
    /// no history reads it; a name that is no ledger row is not its distance; the ledger rows come
    /// through `hold_distance` without a horizon reading, the other rows as before.
    #[test]
    fn a_held_ledger_row_is_held_at_the_log_ratio_of_its_median_to_its_record() {
        let a = nasdaq();
        let row = "declines 10%+ a century";
        let lh: Vec<ms::HistoryRow> = ms::LEDGER_ROWS
            .iter()
            .map(|&name| ms::HistoryRow {
                name,
                horizon: 10,
                reads: if name == row {
                    vec![40.0, f64::NAN, 50.0, 60.0]
                } else {
                    vec![1.0, 2.0, 3.0]
                },
                record: None,
            })
            .collect();
        let record = ms::ledger_histories_recorded(a, &lh)
            .into_iter()
            .find(|h| h.name == row)
            .and_then(|h| h.record)
            .expect("the nasdaq ledger records the declines row");
        assert!((record - 69.402_368).abs() < 1e-6, "{record}");
        let want = ms::ln_det(50.0 / record).abs();
        let got = ledger_hold_distance(a, row, &lh).expect("a ledger row");
        assert_eq!(got.to_bits(), want.to_bits());
        assert!(got > 0.32 && got < 0.33, "{got}");
        let hr = ms::HorizonReadings::default();
        let st = ms::measure(&ms::sim_paths(&ms::default_world(), 2, 3, 1), 3);
        let via = hold_distance(a, row, &hr, &st, Some(&lh)).expect("held through the hold");
        assert_eq!(via.to_bits(), want.to_bits());
        assert!(
            hold_distance(a, row, &hr, &st, None).is_none(),
            "no ledger histories, no distance"
        );
        assert!(ledger_hold_distance(a, "equity vol %", &lh).is_none());
        let blank: Vec<ms::HistoryRow> = lh
            .iter()
            .map(|h| ms::HistoryRow {
                reads: if h.name == row {
                    vec![f64::NAN, f64::NAN]
                } else {
                    h.reads.clone()
                },
                ..h.clone()
            })
            .collect();
        assert_eq!(
            ledger_hold_distance(a, row, &blank),
            Some(f64::INFINITY),
            "no history reads it: the furthest a row can be"
        );
    }

    /// A printed NaN is not a graded figure: the tool's line pattern does not match it.
    #[test]
    fn a_row_without_a_printed_figure_is_left_out() {
        let table = table_of(&[("x", "return", 4.0, Some(0.0))]);
        let hist = [hist_of("x", &[0.5, 1.5], 1.0)];
        assert_eq!(
            score_of(&[("x", 90, f64::NAN, 1.0, None)], &hist, &table),
            Ok(0.0)
        );
    }

    /// THE RECORD'S VOTE ON ITS SIGN scales the share (ruled 2026-10-27): the table's resolution
    /// in this world, 1 the share whole, 0.5 half of it, the band no longer entering; a graded
    /// row with a null and no resolution in this world is refused by name.
    #[test]
    fn the_records_vote_scales_the_sign_share() {
        let mut table = table_of(&[("x", "vol", 2.0, Some(0.0))]);
        // histories at the record: a CRPS of 0, so the score is the sign term alone; the band
        // holds the null, which no longer exempts the row
        let at = |table: &Ledger| {
            let hist = [hist_of("x", &[0.5, 0.5], 0.5)];
            score_of(&[("x", 60, -0.5, 0.5, Some((-2.96, 5.13)))], &hist, table)
        };
        assert_eq!(at(&table), Ok(2.0 * (3.0 * 0.5)), "the vote whole");
        table.rows[0].resolutions = [Some(1.0), Some(0.5)];
        let half: f64 = g8(0.5 * 0.5).parse().expect("eight digits");
        assert!(
            (at(&table).expect("named") - 2.0 * (3.0 * half)).abs() < 1e-12,
            "the vote at a half"
        );
        table.rows[0].resolutions = [Some(1.0), Some(0.0)];
        assert_eq!(at(&table), Ok(0.0), "no vote, no sign");
        table.rows[0].resolutions = [Some(1.0), None];
        assert_eq!(
            at(&table),
            Err(format!(
                "the ledger table has no resolution for [x] in {}",
                nasdaq().name
            ))
        );
        let mut level = table_of(&[("b", "loss", 3.0, None)]);
        level.rows[0].resolutions = [None, None];
        let hist = [hist_of("b", &[-80.0, -80.0], -80.0)];
        assert_eq!(
            score_of(&[("b", 10, -70.0, -80.0, None)], &hist, &level),
            Ok(0.0),
            "a level has no null to resolve"
        );
    }

    /// THE KNEE (ruled 2026-10-27): a row's CRPS passes through a dead zone at the CRPS an
    /// indistinguishable world earns, quadratic inside, linear beyond, in the row scores and the
    /// family aggregates alike, the vector's crps staying raw; a graded row without a knee in
    /// this world is refused by name.
    #[test]
    fn the_knee_is_a_dead_zone_on_the_crps() {
        for (c, k, want) in [
            (0.23, 0.5, 0.0529),
            (0.5, 0.5, 0.25),
            (1.0, 0.5, 0.75),
            (0.3, 0.0, 0.3),
        ] {
            assert!((soft(c, k) - want).abs() < 1e-12, "soft({c}, {k}) = {want}");
        }
        let mut t = table_of(&[("a", "return", 2.0, Some(0.0)), ("b", "loss", 1.0, None)]);
        t.rows[0].knees = [Some(0.1), Some(0.5)];
        t.rows[1].knees = [Some(0.1), Some(0.5)];
        let v = vec![[0.23, 0.2, 1.0], [1.0, 0.0, 1.0]];
        let per = ledger_row_scores(&v, &t);
        assert_eq!(per.len(), 2);
        assert!(
            (per[0].1 - 2.0 * (0.0529 + 3.0 * 0.2)).abs() < 1e-12,
            "{per:?}"
        );
        assert!((per[1].1 - 0.75).abs() < 1e-12, "{per:?}");
        let sum = ledger_sum(&v, &t);
        assert!((sum - (per[0].1 + per[1].1)).abs() < 1e-12);
        let fam = ledger_families(&v, &t);
        let want_fam = [0.0529, 0.6, 0.75, 0.0, 0.0, 0.0, 0.0, 0.0];
        assert!(
            fam.iter().zip(want_fam).all(|(a, b)| (a - b).abs() < 1e-12),
            "{fam:?}"
        );
        t.rows[0].knees = [Some(0.1), None];
        let hist = [hist_of("a", &[0.1, 0.2], 0.15)];
        assert_eq!(
            ledger_vector(&[("a", 50, 0.1, 0.15, None)], &hist, nasdaq(), &t),
            Err(format!(
                "the ledger table has no knee for [a] in {}",
                nasdaq().name
            ))
        );
    }

    /// Epsilon-dominance: at least as good within 0.1 scales on every aggregate and strictly
    /// better on one.
    #[test]
    fn epsilon_dominance_reads_the_eight_aggregates() {
        assert!(eps_dominates(&[10.0, 0.0], &[12.0, 0.0]));
        assert!(
            eps_dominates(&[10.0, 0.08], &[12.0, 0.0]),
            "0.08 scales worse is within the epsilon"
        );
        assert!(
            !eps_dominates(&[10.0, 0.12], &[12.0, 0.0]),
            "0.12 scales worse is not"
        );
        assert!(
            !eps_dominates(&[10.0, 0.0], &[10.0, 0.0]),
            "equal is not strictly better"
        );
    }

    /// The reserve keeps a discarded member no archive member dominates, drops one the candidate
    /// dominates, and never holds a core member.
    #[test]
    fn the_reserve_keeps_the_non_dominated_discards() {
        let table = table_of(&[("a", "return", 4.0, Some(0.0)), ("b", "loss", 3.0, None)]);
        let member = |dial: f64, score: f64, ga: f64, gb: f64| Member {
            name: "s".to_string(),
            dials: vec![dial],
            score,
            raw: 0.0,
            worst: String::new(),
            desc: Vec::new(),
            ledger: Some(vec![[ga, 0.0, 0.0], [gb, 0.0, 0.0]]),
        };
        // the core replaced m1 by the candidate; m1 is better on the loss family: kept
        let m1 = member(1.0, 300.0, 40.0, 10.0);
        let cand = member(2.0, 200.0, 10.0, 40.0);
        let core = vec![cand.clone()];
        let reserve = reserve_after(std::slice::from_ref(&m1), &core, &cand, Vec::new(), &table);
        assert_eq!(reserve.len(), 1);
        assert_eq!(reserve[0].dials, vec![1.0]);
        // a discard the candidate dominates on both families is not kept
        let m2 = member(3.0, 400.0, 30.0, 45.0);
        let reserve = reserve_after(&[m2], &core, &cand, reserve, &table);
        assert_eq!(reserve.len(), 1);
        // a later core member dominating the reserve's member drops it
        let better = member(4.0, 100.0, 5.0, 5.0);
        let core = vec![cand.clone(), better.clone()];
        let reserve = reserve_after(&[], &core, &better, reserve, &table);
        assert!(reserve.is_empty());
    }

    /// The archive round-trips its ledger columns and tiers, and the `ledger` column is the
    /// table-order weighted sum the re-pick tool computes.
    #[test]
    fn the_archive_carries_the_ledger_columns() {
        let table = table_of(&[("a", "return", 4.0, Some(0.0)), ("b", "loss", 3.0, None)]);
        let dir = std::env::temp_dir().join(format!("ledger-archive-{}", std::process::id()));
        let dir = dir.to_str().expect("path").to_string();
        std::fs::create_dir_all(&dir).expect("temp dir");
        let n = ranges().len();
        let m = Member {
            name: "seed".to_string(),
            dials: vec![0.5; n],
            score: 1.0,
            raw: 0.5,
            worst: "a".to_string(),
            desc: vec![1.0; DESC_NAMES.len()],
            ledger: Some(vec![[12.0, 0.25, 1.5], [f64::NAN; 3]]),
        };
        let p = Member {
            dials: vec![0.25; n],
            ..m.clone()
        };
        write_archive(
            &dir,
            std::slice::from_ref(&m),
            &[p],
            3,
            7,
            (0.0, 0),
            &[],
            &table,
        );
        let text = read_text(&format!("{dir}/archive.tsv")).expect("written");
        let head = text.lines().next().expect("header");
        assert!(head.ends_with("\ttier\tledger\tcrps:a\tsign:a\tmodel:a\tcrps:b\tsign:b\tmodel:b"));
        // 4 x (12 + 3 x 0.25)
        let row = text.lines().nth(1).expect("core row");
        assert!(
            row.contains("\tcore\t51\t12\t0.25\t1.5\tNaN\tNaN\tNaN"),
            "{row}"
        );
        let (core, reserve) = read_archive(&dir);
        assert_eq!((core.len(), reserve.len()), (1, 1));
        let back = core[0].ledger.as_ref().expect("the ledger columns");
        assert_eq!(back[0], [12.0, 0.25, 1.5]);
        assert!(back[1].iter().all(|x| x.is_nan()));
        assert_eq!(reserve[0].dials, vec![0.25; n]);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The table as the tool writes it, written to a temp file and read for the nasdaq world:
    /// `none` the level's null, a world's missing scale, vote or knee.
    fn written_table(tag: &str) -> Ledger {
        let dir = std::env::temp_dir().join(format!("ledger-rows-{tag}-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("temp dir");
        let f = dir.join("rows.tsv");
        std::fs::write(
            &f,
            "row\tunits\tweight\tnull\tscale sp500\tscale nasdaq\tresolution sp500\tresolution nasdaq\tknee sp500\tknee nasdaq\nreturn per vol\treturn\t4\t0\t0.12\t0.27\t0.9\t0.4\t1.1\t1.3\nvariance ratio 20d\tshape\t1\t1\t0.04\t0.05\tnone\t1\t1.2\tnone\nworst crash %\tloss\t3\tnone\tnone\t9.5\tnone\tnone\tnone\t1.4\n",
        )
        .expect("write");
        let table = read_ledger(f.to_str().expect("path"), nasdaq());
        std::fs::remove_dir_all(&dir).ok();
        table
    }

    /// The table parses as the tool writes it, `none` the level's null and a world's missing
    /// scale, and each world reads its own column.
    #[test]
    fn the_row_table_reads_as_the_tool_writes_it() {
        let table = written_table("scales");
        assert_eq!(table.rows.len(), 3);
        let spec = |n: &str| table.get(n).map(|r| (r.units, r.weight, r.null, r.scales));
        assert_eq!(
            spec("return per vol"),
            Some(("return", 4.0, Some(0.0), [Some(0.12), Some(0.27)]))
        );
        assert_eq!(
            spec("variance ratio 20d"),
            Some(("shape", 1.0, Some(1.0), [Some(0.04), Some(0.05)]))
        );
        assert_eq!(
            spec("worst crash %"),
            Some(("loss", 3.0, None, [None, Some(9.5)]))
        );
        assert_eq!(table.rows[2].name, "worst crash %");
        assert_eq!(table.scale(0, ms::anchors_named("sp500")), Some(0.12));
        assert_eq!(table.scale(0, nasdaq()), Some(0.27));
        assert_eq!(table.scale(2, ms::anchors_named("sp500")), None);
    }

    /// The table's vote and knee columns, each world its own, `none` neither; the run's world
    /// fixes the knee the sums read, none being no dead zone.
    #[test]
    fn the_row_table_carries_each_worlds_vote_and_knee() {
        let table = written_table("vote");
        assert_eq!(table.resolution(0, ms::anchors_named("sp500")), Some(0.9));
        assert_eq!(table.resolution(0, nasdaq()), Some(0.4));
        assert_eq!(table.resolution(1, ms::anchors_named("sp500")), None);
        assert_eq!(table.knee(1, ms::anchors_named("sp500")), Some(1.2));
        assert_eq!(table.knee(1, nasdaq()), None);
        assert_eq!(table.knee(2, nasdaq()), Some(1.4));
        assert_eq!(table.world, ledger_world(nasdaq()));
        assert_eq!(table.knee_at(0), 1.3, "the run's world's knee");
        assert_eq!(table.knee_at(1), 0.0, "none is no dead zone");
    }

    /// EVERY ROW AT FULL WEIGHT (ruled 2026-10-22): the family aggregates sum each row's CRPS and
    /// sign whole.
    #[test]
    fn every_row_counts_whole_in_the_family_aggregates() {
        let t = table_of(&[
            ("a", "return", 1.0, Some(0.0)),
            ("b", "return", 1.0, Some(0.0)),
        ]);
        let v = vec![[3.0, 0.2, 1.0], [1.0, 0.4, 1.0]];
        let f = ledger_families(&v, &t);
        assert!(
            (f[0] - 4.0).abs() < 1e-12 && (f[1] - 1.8).abs() < 1e-12,
            "{f:?}"
        );
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
            ledger: Vec::new(),
            ledger_vec: None,
            due: None,
        }
    }

    /// THE QUANTILE IS AS241's: the values Python's `NormalDist.inv_cdf` returns, to the last
    /// digits, in each of its three branches.
    #[test]
    fn the_normal_quantile_matches_the_judges() {
        for (p, want) in [
            (0.5, 0.0),
            (0.975, 1.959_963_984_540_054),
            (0.1, -1.281_551_565_544_600_5),
            (1e-10, -6.361_340_902_404_056),
            (0.999_999, 4.753_424_308_817_089),
        ] {
            let got = ms::ndtri(p);
            assert!((got - want).abs() < 1e-12, "{p}: {got} vs {want}");
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
            yardstick: HashMap::new(),
            place: true,
            records: None,
            kind: ObjectiveKind::Crps,
            ledger: std::sync::Arc::new(Ledger::default()),
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
            yardstick: HashMap::new(),
            place: true,
            records: None,
            kind: ObjectiveKind::Crps,
            ledger: std::sync::Arc::new(Ledger::default()),
        }
        .without_gap();
        assert!(seed_obj.gap.is_empty() && seed_obj.watch == graded);
        let r = one_read(&w, anchors, paths, years, seed, &seed_obj);

        // the verdict world's paths, as the seed read simulates them: a banded row at a horizon
        // under the paths' length is cut from them, and the legs' rows read NaN off paths without
        // the legs
        let main = ms::sim_paths(&ms::verdict_world(anchors, &w), paths, years, seed);
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
            ledger: None,
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

#[cfg(test)]
mod membership_tests {
    use super::*;

    /// Two exchangeable reads of three graded rows placed alone, the record drawn with them: each
    /// row's 20 histories and its record iid standard normal from `seed`.
    fn two_reads(seed: u64) -> (Vec<ms::HistoryRow>, Vec<ms::HistoryRow>) {
        let mut rng = NumPyRng::new(seed);
        let recs: Vec<f64> = (0..3).map(|_| rng.randn()).collect();
        let mut read = || -> Vec<ms::HistoryRow> {
            recs.iter()
                .zip(["x", "y", "z"])
                .map(|(&rec, name)| ms::HistoryRow {
                    name,
                    horizon: 1,
                    reads: (0..20).map(|_| rng.randn()).collect(),
                    record: Some(rec),
                })
                .collect()
        };
        let a = read();
        let b = read();
        (a, b)
    }

    /// A read within its bounds carrying `rows`: scored 1 with a ledger, 9 as a rejection, its
    /// uncovered fidelity gate before a regression.
    fn due_read(rows: Vec<ms::HistoryRow>) -> Read {
        Read {
            feasible: true,
            score: 1.0,
            raw: 1.0,
            worst: "x".to_string(),
            desc: Vec::new(),
            total: 1.0,
            gate_fail: vec!["fidelity: band".to_string(), "regress: x".to_string()],
            spread: 0.0,
            complete: true,
            watch_miss: Vec::new(),
            stage_fail: None,
            held: f64::NAN,
            ledger: vec!["1:1".to_string()],
            ledger_vec: Some(Vec::new()),
            due: Some(Box::new(Due {
                rows,
                at: 1,
                rejected: Rejected {
                    score: 9.0,
                    raw: 3.0,
                    worst: "y".to_string(),
                    total: 7.0,
                },
            })),
        }
    }

    fn placed(rows: &[ms::HistoryRow]) -> ms::Placement {
        ms::placement(rows, ms::PLACEMENT_ALPHA)
    }

    /// MEMBERSHIP OVER A CANDIDATE'S READS IS THE POOLED PLACEMENT'S: a read failing its own does
    /// not reject a candidate the pool passes; a read its bounds rejected leaves nothing to place.
    #[test]
    fn a_read_failing_its_own_placement_does_not_reject_what_the_pool_passes() {
        use ms::PlacementVerdict::Fail;
        use ms::PlacementVerdict::Pass;
        let (a, b) = two_reads(57);
        assert_eq!((placed(&a).verdict, placed(&b).verdict), (Fail, Pass));
        assert_eq!(placed(&ms::pooled_history_rows(&[&a, &b])).verdict, Pass);
        let got = placed_together(vec![due_read(a.clone()), due_read(b)], true);
        assert!(
            got.iter()
                .all(|r| r.feasible && r.due.is_none() && r.score == 1.0 && r.ledger_vec.is_some())
        );
        assert_eq!(got[0].gate_fail, ["fidelity: band", "regress: x"]);

        let mut out = due_read(Vec::new());
        out.feasible = false;
        out.due = None;
        let got = placed_together(vec![due_read(a), out], true);
        assert!(got[0].feasible && !got[1].feasible && got.iter().all(|r| r.due.is_none()));
    }

    /// Reads each passing their own placement do not admit a candidate the pool fails, every read
    /// ranked as the rejection it is; a seed world is placed and reported, never rejected.
    #[test]
    fn reads_passing_their_own_placements_do_not_admit_what_the_pool_fails() {
        use ms::PlacementVerdict::Fail;
        use ms::PlacementVerdict::Pass;
        let (a, b) = two_reads(107);
        assert_eq!((placed(&a).verdict, placed(&b).verdict), (Pass, Pass));
        let pool = placed(&ms::pooled_history_rows(&[&a, &b]));
        assert_eq!(pool.verdict, Fail);
        let got = placed_together(vec![due_read(a.clone()), due_read(b.clone())], true);
        for r in &got {
            assert!(!r.feasible && r.due.is_none());
            assert_eq!(
                (r.score, r.raw, r.worst.as_str(), r.total),
                (9.0, 3.0, "y", 7.0)
            );
            assert!(r.ledger.is_empty() && r.ledger_vec.is_none());
            assert_eq!(r.gate_fail, pool.failures());
        }
        let seeds = placed_together(vec![due_read(a), due_read(b)], false);
        let want: Vec<String> = ["fidelity: band".to_string()]
            .into_iter()
            .chain(pool.failures())
            .chain(["regress: x".to_string()])
            .collect();
        assert!(want.len() > 2, "the pool names its failing units");
        for r in &seeds {
            assert!(r.feasible && r.score == 1.0);
            assert_eq!(r.gate_fail, want);
        }
    }

    /// AN ARCHIVE SEARCHED UNDER PER-READ PLACEMENT REFUSES TO RESUME: its state names no
    /// `membership`, which reads as the rule it ran under.
    #[test]
    fn an_archive_placed_per_read_refuses_to_resume() {
        let settings = vec![
            ("paths".to_string(), "200".to_string()),
            ("membership".to_string(), MEMBERSHIP.to_string()),
        ];
        let mut prior: HashMap<String, String> =
            HashMap::from([("paths".to_string(), "200".to_string())]);
        with_settings_before(&mut prior);
        assert_eq!(prior["membership"], MEMBERSHIP_BEFORE);
        let drift = drifted(&settings, &prior);
        assert_eq!(drift.len(), 1);
        assert_eq!(drift[0].0, "membership");
        prior.insert("membership".to_string(), MEMBERSHIP.to_string());
        assert!(drifted(&settings, &prior).is_empty());
    }
}

#[cfg(test)]
mod trace_tests {
    use super::*;

    /// A log line as the search writes it: the eight leading cells, the descriptors, the gate's
    /// failure, the admitted flag and the ledger's cells.
    fn line(g: u64, admitted: bool, ledger: &str) -> String {
        let desc: Vec<String> = (0..DESC_NAMES.len()).map(|k| format!("{k}.5")).collect();
        format!(
            "{g}\t7\tm\ttrue\t1.000000\t1.000000\t-\t0.100\t{}\t-\t{admitted}\t{ledger}",
            desc.join("\t")
        )
    }

    /// A resume rebuilds every admission whatever follows the flag, and none of the rejections.
    #[test]
    fn trace_reads_the_admitted_flag_by_position() {
        let log = format!(
            "header\n{}\n{}\n{}\n",
            line(0, true, "0.25 0.5"),
            line(1, false, "true"),
            line(2, true, "")
        );
        let trace = admitted_trace(&log);
        assert_eq!(trace.iter().map(|t| t.0).collect::<Vec<_>>(), [0, 2]);
        assert_eq!(trace[0].1.len(), DESC_NAMES.len());
        assert!((trace[0].1[1] - 1.5).abs() < 1e-12);
    }
}

//! A CALIBRATION SEARCH for the market simulator: the Rust twin of `jsrc/marketSimSearch.sc`,
//! built to run for days and to be killed at any moment.
//!
//! ```text
//!   cargo build --release --bin market_sim_search
//!   ./target/release/market_sim_search -out search -paths 60 -years 80
//! ```
//!
//! WHY THIS EXISTS BESIDE THE SCALA ONE: measured on the shipped world at 60 x 80, one evaluation
//! costs 5.17 s of warmed JVM against 0.67 s here, and the two produce byte-identical readings.
//! A search that fills an archive overnight in Scala fills it in under two hours here.
//!
//! NOT SHIPPED.  `Cargo.toml` excludes this file from the published crate, the way the `bench_*`
//! binaries are excluded: `cargo install vastblue-uni` builds the simulator, not the search.
//!
//! WHAT IT PRODUCES is an ARCHIVE, not a champion.  Distinct worlds match the record equally well
//! -- 28 searched dials against ~45 graded rows that are not independent -- and a strategy
//! interacts with the mechanism rather than with the summary statistic, so a verdict from one
//! best-fit world carries the same over-confidence that weakens a backtest.  A verdict that holds
//! across every world consistent with the record does not.
//!
//! THE OBJECTIVE IS LEXICOGRAPHIC and fixes no target value.  Feasibility first, as a hard gate
//! never priced into a scalar, so a failed row cannot be bought with a gain elsewhere.  Then the
//! WORST row's distance from the record, with a DEAD ZONE at one anchor sd: inside the record's
//! own sampling error there is no gradient to climb, which is what stops a long search optimising
//! noise.  Minimising the worst row rather than a sum is what stops one row being sacrificed.
//!
//! THE DIAL TABLE IS THE LIBRARY'S, not a copy.  The Scala script carries its own because editing
//! it there would otherwise need a `publishLocal` round trip; this binary is rebuilt from the same
//! tree as the model, so a copy would buy nothing and cost the failure it was just fixed for --
//! the two tables were permuted against each other at positions 15-23, and the same seed therefore
//! drew different worlds in the two languages.  `CALIBRATE_DIAL_ORDER` is now a contract in both.
//!
//! EVERYTHING IS PORTABLE BETWEEN THE TWO HARNESSES.  The archive format and every reading in it:
//! feasibility comes from `gate_checks`, which reads only the ensemble's statistics, and those are
//! byte-identical across the twins, so a Scala `-holdout` re-scores a Rust archive exactly and
//! that is the check worth running before an archive is published.  The MUTATION STREAM too, since
//! both harnesses draw from `NumPyRng`, whose `randn` and `next_bounded_u32` are gated
//! bit-identical -- a language-native generator would not be, because a Gaussian is scaled by a
//! logarithm and the twins measure those 1 ulp apart on 0.235% of a corpus.  So one `-seed` walks
//! one trajectory in either language.

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

/// WHAT A WORLD DOES, recorded beside what it is set to. Never graded, never optimised: the
/// archive keeps worlds apart by DIAL distance, which is a proxy for behavioural difference
/// rather than a measurement of it, so without these the archive's spread is assumed. Two
/// members far apart in dials can produce markets a strategy cannot tell apart, and the reverse
/// is the case worth keeping both of.
///
/// `retAc1` leads because it is what the record cannot pin down: a variance ratio constrains a
/// weighted SUM of the first q-1 autocorrelations and the clustering rows read |r|, so signed
/// lag-1 is unconstrained by anything in the target set, and across a 32-world archive it spread
/// further than any other candidate statistic measured. Finding that took a separate study after
/// the run; as a column it would have been a sort.
///
/// They are written for EVERY candidate, into `log.tsv`, not only for the members that survive.
/// The rejected candidates are the large majority and they are gone when the run ends.
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

/// Distance in normalised dial space, max over dials -- the same reading the seed pool's spread
/// was measured with, so `-sep` is comparable to it.
fn apart(a: &[f64], b: &[f64]) -> f64 {
    ranges()
        .iter()
        .enumerate()
        .map(|(i, r)| (a[i] - b[i]).abs() / (r.2 - r.1))
        .fold(f64::NEG_INFINITY, f64::max)
}

fn clamped(r: &Range, x: f64) -> f64 {
    x.max(r.1).min(r.2)
}

/// The dead zone in the units `fitness` scores rows in. `wgt` makes one anchor sd worth
/// `SD_REL_REF` there, so `-dead 1.0` means "inside the record's own sampling error, no gradient".
/// A dial rather than a constant because at 1.0 a calibrated world scores 0 on every row: the
/// objective goes flat and the search random-walks. The raw worst is reported beside the scored
/// one so the width is chosen from what the rows actually read.
fn dead_zone(sds: f64) -> f64 {
    sds * ms::SD_REL_REF
}

// ---- evaluation ---------------------------------------------------------------------------

/// Feasible on every rep, and the worst row's distance past the dead zone. The reading is the
/// WORST across reps, never the mean: a world that passes only on a lucky seed has not passed.
#[derive(Clone)]
struct Read {
    feasible: bool,
    score: f64,
    raw: f64,
    worst: String,
    /// MEAN over the reps, not the hardest one: a descriptor describes the world, where `raw`
    /// deliberately describes its worst seed.
    desc: Vec<f64>,
    /// WHICH GATE ROWS FAILED, empty when the candidate is feasible. Recorded because the log's
    /// `worstRow` is a FITNESS row while feasibility is decided by the GATE -- two different sets
    /// -- so a rejected candidate's log line said nothing about why it was rejected, and there was
    /// no way to see which rows a search had walked onto the edge of.
    gate_fail: Vec<String>,
    /// How far this world's reading MOVED across its own repetitions -- max minus min of `raw`.
    /// Free to compute and it is the noise scale the archive needs: a score difference smaller
    /// than this is a seed draw, not a better world. Zero at `-reps 1`, which is honest, since
    /// one seed measures no spread at all.
    spread: f64,
}

/// One seed's reading. THREE THINGS IT DOES NOT DO, each worth more than anything else here:
///
/// It does not simulate twice when it does not have to. The extreme row is read at its anchor's
/// own horizon — 100 years for the S&P set — so a run at `-years 100` was asking for the same
/// paths from the same seed a second time, and that second ensemble is the larger half of an
/// evaluation. At the extreme horizon the main ensemble IS that ensemble.
///
/// It does not pay for the second ensemble at all once the gate has failed. Feasibility comes
/// from `gate_checks`, which reads only the pooled statistics, so a candidate that fails is
/// rejected whatever its score. The rows that need the second ensemble are then dropped from the
/// worst-row search rather than scored as unmeasurable, so a rejected candidate's `worstRow`
/// still names something that was actually measured.
///
/// And it stops at the first seed that fails, because feasibility needs every seed to pass.
fn one_read(w: &World, anchors: Anchors, paths: usize, years: usize, s: u64, dead: f64) -> Read {
    let main = ms::sim_paths(w, paths, years, s);
    let st = ms::measure(&main, years);
    let default = ms::gate_default();
    let bad = ms::gate_checks(anchors, &st)
        .into_iter()
        .filter(|(_, ok, cls)| !ok && default.contains(cls))
        .count();
    let feasible = bad == 0;
    let gate_fail: Vec<String> = if feasible {
        Vec::new()
    } else {
        default
            .iter()
            .flat_map(|cls| ms::failed_in(anchors, &st, *cls))
            .collect()
    };

    let ex = if !feasible {
        HashMap::new()
    } else if ms::extreme_horizons(anchors) == vec![years] {
        ms::extreme_score_stats_from(anchors, &main, years)
    } else {
        ms::extreme_score_stats(anchors, paths, s, w)
    };
    let rows = ms::fitness(anchors, &st, &ex).1;
    // the worst row, ties broken by name exactly as the Scala harness's `max` on a (term, name)
    // pair does
    let worst = rows
        .iter()
        .filter(|(nm, _, _, _)| feasible || !ms::extreme_target_names().contains(nm))
        .map(|(nm, _, _, term)| (*term, *nm))
        .fold((f64::NEG_INFINITY, ""), |a, b| {
            if b.0 > a.0 || (b.0 == a.0 && b.1 > a.1) {
                b
            } else {
                a
            }
        });
    Read {
        feasible,
        score: (worst.0 - dead).max(0.0),
        raw: worst.0,
        worst: worst.1.to_string(),
        desc: desc_of(&st),
        gate_fail,
        spread: 0.0,
    }
}

fn evaluate(
    w: &World,
    anchors: Anchors,
    paths: usize,
    years: usize,
    seeds: &[u64],
    dead: f64,
) -> Read {
    let mut reads: Vec<Read> = Vec::with_capacity(seeds.len());
    for &s in seeds {
        let r = one_read(w, anchors, paths, years, s, dead);
        let stop = !r.feasible;
        reads.push(r);
        if stop {
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
        .map(|r| r.raw)
        .fold(f64::NEG_INFINITY, f64::max);
    let lo = reads.iter().map(|r| r.raw).fold(f64::INFINITY, f64::min);
    Read {
        feasible: reads.iter().all(|r| r.feasible),
        score: hardest.score,
        raw: hardest.raw,
        worst: hardest.worst,
        desc,
        gate_fail: hardest.gate_fail,
        spread: hi - lo,
    }
}

// ---- transport -------------------------------------------------------------------------------

/// SELECTING FOR TRANSPORT rather than testing it afterwards: a candidate is judged on the worse
/// of its two markets, so a world that fits the S&P by doing something the Nasdaq will not tolerate
/// never enters the archive.
///
/// One dial vector cannot pass both anchor sets, and it is not supposed to. The S&P volatility
/// band is 14 to 18 and the Nasdaq's 23.5 to 30.3; a world reading both is not a market. What
/// transports is the MECHANISM, while the dials that say which market this is get re-solved, which
/// is exactly the structure the shipped recipes already have: `0.24.1-nasdaq` is the default world
/// with six searched dials moved.
///
/// So the transport arm is the counterpart recipe carrying the candidate's values on every
/// searched dial EXCEPT the ones the counterpart itself moved away from the default. Those stay at
/// the counterpart's values. The set is derived from the two worlds rather than listed here, and
/// printed at startup, because it includes both dials deliberately re-solved for that market and
/// any the recipe simply has not tracked since the default moved -- and which is which is a
/// judgement no code should make silently.
struct Transport {
    name: String,
    anchors: Anchors,
    world: World,
    pinned: Vec<bool>,
    spec: String,
}

fn transport_of(name: &str, primary_spec: &str) -> Transport {
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
    let d = ms::default_world();
    let pinned: Vec<bool> = ranges()
        .iter()
        .map(|r| (r.4)(&world) != (r.4)(&d))
        .collect();
    Transport {
        name: name.to_string(),
        anchors: ms::anchors_named(spec),
        world,
        pinned,
        spec: spec.to_string(),
    }
}

fn transport_world(t: &Transport, dials: &[f64]) -> World {
    let mut w = t.world;
    for (i, r) in ranges().iter().enumerate() {
        if !t.pinned[i] {
            (r.3)(&mut w, dials[i]);
        }
    }
    w
}

/// One candidate's reading: the primary arm alone, or the WORSE of the two arms when a transport
/// counterpart is set. Feasible means feasible in both. The descriptors stay the primary world's:
/// they describe the world the archive holds, and the transport arm is a different world by
/// construction.
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
    dead: f64,
) -> Read {
    let a = evaluate(&world_of(base, dials), anchors, paths, years, seeds, dead);
    let Some(tr) = t else { return a };
    // a candidate that fails its primary market is rejected whatever the other one says,
    // and the transport arm is a whole second evaluation
    if !a.feasible {
        return a;
    }
    let b = evaluate(
        &transport_world(tr, dials),
        tr.anchors,
        paths,
        years,
        seeds,
        dead,
    );
    let (score, raw, worst) = if b.raw > a.raw {
        (b.score, b.raw, format!("{}: {}", tr.spec, b.worst))
    } else {
        (a.score, a.raw, a.worst.clone())
    };
    Read {
        feasible: a.feasible && b.feasible,
        score,
        raw,
        worst,
        desc: a.desc,
        // the transport arm's failures carry their market, as `worst` does, so a row that only
        // exists there -- the macro rows, when the counterpart runs the panel -- is not read as
        // a primary-market failure
        gate_fail: if a.gate_fail.is_empty() {
            b.gate_fail
                .iter()
                .map(|r| format!("{}: {r}", tr.spec))
                .collect()
        } else {
            a.gate_fail
        },
        spread: a.spread.max(b.spread),
    }
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

/// THE SETTINGS ARE PART OF THE CHECKPOINT. An archive says which worlds were kept; without the
/// ensemble and the dials that judged them it does not say what "kept" meant, cannot be
/// reproduced, and silently mixes standards if a resume changes a flag. Key/value so a reader and
/// a later version can both cope with a row they do not recognise.
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
    // THE SEED-NOISE ACCUMULATOR IS RESUMABLE STATE, not a setting, so it is written here and
    // not compared by the resume guard. It is the threshold `admit` replaces on: a resume that
    // rebuilt it from one reading would admit on differences inside the noise for the first
    // generations back -- the same failure as resetting it per generation, one process
    // boundary out. `g8` so both twins write and read one text.
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
    text.lines()
        .skip(1)
        .filter(|l| !l.trim().is_empty())
        .map(|l| {
            let f: Vec<&str> = l.split('\t').collect();
            let want = 4 + ranges().len();
            if f.len() < want {
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
                // an archive written before the descriptors landed simply has none
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

/// Admit a feasible candidate.
///
/// TWO RULES, BOTH FIXED 2026-09-12 after a 4317-generation run demonstrated what the old ones do.
///
/// REPLACING NEEDS A REAL MARGIN. A candidate takes the place of the member nearest it in dial
/// space only if it beats that member by more than a seed draw moves a reading. Without that
/// margin the old rule replaced on any improvement whatever, and after thousands of generations
/// the archive's whole score range was 0.018 against a seed-noise sd of 0.0441 -- every member
/// statistically indistinguishable from every other, and the search still sorting them. The dead
/// zone does not help here: it discounts each ROW's distance from the record, and says nothing
/// about comparing two worlds' totals.
///
/// TRIMMING DROPS THE LEAST DISTINCT, not the worst-scoring. Sorting by score and truncating is an
/// optimiser, and it showed: over that run the archive's signed lag-1 range fell from 0.057 to
/// 0.032 and its variance-ratio range from 0.85 to 0.78, while the operator's own output narrowed
/// the same way. The archive exists to SPAN what the record cannot pin down, so when it overflows
/// the member to lose is the one whose removal costs the least behavioural ground: find the closest
/// pair in descriptor space and drop whichever of the two scores worse.
///
/// Distance is normalised by each descriptor's own range ACROSS THE ARCHIVE, recomputed each time,
/// so no descriptor's units dominate and no bounds have to be guessed in advance. This is the
/// plan's MAP-Elites intent without a grid: keeping a spread set needs a rule that prefers spread,
/// and a cell grid is only one way to write it.
/// Returns the archive AND WHETHER THE CANDIDATE ENTERED IT. A reader cannot recover that from
/// the archive -- a replacement leaves the member count unchanged -- and without it admission
/// pressure can only be proxied by "scores better than the worst member", which SATURATES once
/// spread-keeping starts admitting a poor scorer for its behaviour: on a 471-generation run the
/// worst member scored 0.906 against a best of 0.070, so the proxy counted every feasible
/// candidate and its verdict said "still turning over" for as long as the search ran.
fn admit(arc: Vec<Member>, m: Member, sep: f64, keep: usize, noise: f64) -> (Vec<Member>, bool) {
    let near = arc.iter().position(|o| apart(&o.dials, &m.dials) < sep);
    let (mut next, took) = match near {
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
    (next, took)
}

/// The member whose removal costs the least behavioural spread: of the closest pair in normalised
/// descriptor space, the one that scores worse. `None` when no member carries descriptors, which
/// is only an archive written before they existed.
fn least_distinct(arc: &[Member]) -> Option<usize> {
    let n = arc.len();
    let k = arc.iter().map(|m| m.desc.len()).max().unwrap_or(0);
    if n < 2 || k == 0 {
        return None;
    }
    // each descriptor's range across the archive, so none of them dominates on units alone
    let span: Vec<f64> = (0..k)
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
        .collect();
    let dist = |a: &Member, b: &Member| -> f64 {
        (0..k)
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
    };
    let mut worst = (f64::INFINITY, 0usize);
    for i in 0..n {
        for j in (i + 1)..n {
            let d = dist(&arc[i], &arc[j]);
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
    let bodies: Vec<String> = arc
        .iter()
        .enumerate()
        .map(|(k, m)| {
            let w = world_of(&seed_for(&m.name), &m.dials);
            format!(
                "  {{\n    \"member\": {k},\n    \"seededFrom\": \"{}\",\n    \"score\": {:.6},\n    \"worstRow\": \"{}\",\n    \"world\": {{\n{}\n    }}\n  }}",
                m.name,
                m.score,
                m.worst,
                // AT THE ARCHIVE'S OWN WIDTH, not the report's: this block is what a consumer
                // reconstructs a world from, and re-rendering already-truncated dials at six
                // decimals dropped two to three significant digits from 87% of them.
                // `,\n`, as the sidecar joins it: `world_json_body_fmt` returns the fields
                // WITHOUT separators, and joining them on a bare newline wrote an archive no
                // JSON parser accepts. Both twins did it, identically, which is how parity
                // missed it.
                ms::world_json_body_fmt(&w, &|x| g8(x)).join(",\n")
            )
        })
        .collect();
    write_text(file, &format!("[\n{}\n]\n", bodies.join(",\n")));
    println!("wrote {} worlds to {file}", arc.len());
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
  -reps K       ; seeds a candidate must pass feasibility on, all of them (default 2)
  -sigma S      ; mutation sd as a fraction of each dial's range (default 0.07; 0.20 breaks)
  -keep N       ; archive size cap (default 40)
  -sep D        ; minimum separation between members in normalised dial space (default 0.12)
  -pop P        ; candidates per generation, checkpointed after each (default 8)
  -gens G       ; generations to run; 0 runs until killed (default 0)
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
  -export F     ; write the archive as a worlds JSON and exit
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
    dead: f64,
    base: i64,
    holdout: usize,
    prune: bool,
    force: bool,
    export_to: String,
    fidelity: String,
    transport: String,
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
        dead: 0.5,
        base: 20260813,
        holdout: 0,
        prune: false,
        force: false,
        export_to: String::new(),
        fidelity: String::new(),
        transport: String::new(),
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
            "-dead" => c.dead = fnum(&need(&mut i, "-dead"), "-dead"),
            "-seed" => c.base = num::<i64>(&need(&mut i, "-seed"), "-seed"),
            "-holdout" => c.holdout = num(&need(&mut i, "-holdout"), "-holdout"),
            "-prune" => c.prune = true,
            "-force" => c.force = true,
            "-export" => c.export_to = need(&mut i, "-export"),
            "-fidelity" => c.fidelity = need(&mut i, "-fidelity"),
            "-transport" => c.transport = need(&mut i, "-transport"),
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
    if let Err(e) = std::fs::create_dir_all(&c.out) {
        usage(&format!("cannot create {}: {e}", c.out));
    }

    let anchors = ms::anchors_named(&c.anchor_spec);
    let transport = if c.transport.is_empty() {
        None
    } else {
        Some(transport_of(&c.transport, &c.anchor_spec))
    };
    if let Some(t) = &transport {
        let held: Vec<&str> = ranges()
            .iter()
            .zip(&t.pinned)
            .filter(|(_, p)| **p)
            .map(|(r, _)| r.0)
            .collect();
        println!(
            "transport arm: {} ({}), holding {} of {} dials at its own values [{}]",
            t.name,
            t.spec,
            held.len(),
            ranges().len(),
            held.join(", ")
        );
    }

    // `releases()` stops at the last FROZEN row, so the library's own current default -- the
    // newest and most complete world, and the one a search is usually about -- is not in it.
    // Added first so it is always a seed.
    //
    // A RECIPE CARRIES THE ANCHOR SET IT WAS VERIFIED AGAINST, and a seed graded against the wrong
    // ruler is worse than no seed: the Nasdaq recipes read `equity vol %` 0.5 past the dead zone
    // against S&P anchors and would drag an S&P archive toward a target they were never built for.
    // So the pool is restricted to seeds whose anchor set is the one being searched, which also
    // means an S&P archive and a Nasdaq archive are separate products, as they should be.
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
    let seed_world: HashMap<String, World> = pool.iter().cloned().collect();
    let world_for =
        |n: &str| -> World { seed_world.get(n).copied().unwrap_or_else(ms::default_world) };

    // CHEAP FIDELITY. The search only ever compares candidates, so a smaller ensemble is good
    // enough exactly when it RANKS worlds the way the reference does -- the losses need not agree.
    // This scores the frozen pool at the reference and at each candidate ensemble and reports the
    // rank correlation, how often the two disagree on feasibility, and what each costs.
    //
    // THE TWO AXES DO DIFFERENT DAMAGE, measured on the frozen pool at the S&P set:
    //
    //     ensemble    rank corr   feasibility agrees   speedup
    //     20 x 40y        0.945           17 of 19        3.9x
    //     30 x 60y        0.952           19 of 19        2.2x
    //     30 x 80y        0.993           19 of 19        2.0x
    //     40 x 80y        1.000           19 of 19        1.5x
    //
    // PATHS govern whether the two agree on FEASIBILITY -- 20 of them puts worlds near a band
    // edge on the wrong side, which is the seed-noise quantum showing through -- and YEARS govern
    // how faithfully the cheap ensemble RANKS. So 30 x 80 is the trade: half the cost for a rank
    // correlation of 0.993 and no feasibility disagreement at all. Do not read the cost the other
    // way round: cutting `-years` saves less than it looks, because the worst-crash row is read at
    // its own anchor's horizon -- 100 years for the S&P set, whatever `-years` says -- and that is
    // the larger half of an evaluation. 20 paths is a hard floor regardless, below which that row
    // can no longer place a record inside its own percentile band.
    if !c.fidelity.is_empty() {
        let ens = parse_ensembles(&c.fidelity);
        let seeds: Vec<u64> = (0..c.reps)
            .map(|k| (c.base as u64).wrapping_add(k as u64 * 1_000_003))
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
                    dead_zone(c.dead),
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
                        dead_zone(c.dead),
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
        (
            "seeds".into(),
            if c.seed_spec.is_empty() {
                "(all)".to_string()
            } else {
                c.seed_spec.clone()
            },
        ),
        // the ADMISSION RULES are recorded: a resume under different ones puts two standards in
        // one archive, which is the same failure the ensemble settings guard against
        ("admit".into(), "spread-keeping".to_string()),
        (
            "transport".into(),
            if c.transport.is_empty() {
                "(none)".to_string()
            } else {
                c.transport.clone()
            },
        ),
    ];

    let prior = read_state(&c.out);
    let loaded = read_archive(&c.out);
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

    let start_arc: Vec<Member> = if !loaded.is_empty() {
        println!(
            "resumed: {} members, generation {gen0}, {evals0} evaluations",
            loaded.len()
        );
        loaded.clone()
    } else {
        // Membership is re-checked, never inherited: the older releases were adopted against an
        // earlier gate and several no longer pass the rows the model has since grown.
        println!(
            "seeding from {} frozen worlds at {}, {} x {}y x {} reps",
            pool.len(),
            c.anchor_spec,
            c.paths,
            c.years,
            c.reps
        );
        let seeds: Vec<u64> = (0..c.reps)
            .map(|k| (c.base as u64).wrapping_add(k as u64 * 1_000_003))
            .collect();
        let mut seeded = Vec::new();
        for (nm, w) in &pool {
            let r = judge(
                w,
                &dials_of(w),
                anchors,
                transport.as_ref(),
                c.paths,
                c.years,
                &seeds,
                dead_zone(c.dead),
            );
            println!(
                "  {:<24} {:<9} score {:>7.3}  raw {:>7.3}  {}",
                nm,
                if r.feasible { "feasible" } else { "REJECTED" },
                r.score,
                r.raw,
                r.worst
            );
            if r.feasible {
                seeded.push(Member {
                    name: nm.clone(),
                    dials: dials_of(w),
                    score: r.score,
                    raw: r.raw,
                    worst: r.worst,
                    desc: r.desc,
                });
            }
        }
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
            "name\tscore\traw\tworstRow\t{}\tpassA\tpassB\trawB\tworstB",
            names().join("\t")
        );
        for (m, r) in &gone {
            let ds: Vec<String> = m.dials.iter().map(|x| g8(*x)).collect();
            let _ = writeln!(
                out,
                "{}\t{:.6}\t{:.6}\t{}\t{}\t{}\t{}\t{}\t{}",
                m.name,
                m.score,
                m.raw,
                m.worst,
                ds.join("\t"),
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

    // THE HOLDOUT. Every member earned its place on seeds the search itself chose, and a world
    // sitting near a band edge flips on roughly one draw in six, so some members are in on a lucky
    // pair. Re-score each one TWICE at the same ensemble, on TWO INDEPENDENT STREAMS.
    //
    // NEITHER STREAM SELECTED THE MUTATIONS. The search draws a candidate's seeds from
    // `base + (evals + j) * 7919`; stream A here is `base + k * 1_000_003`, which is what the
    // initial POOL was judged on, and stream B is that shifted by 991. So for the frozen worlds
    // stream A is the one they entered on, and for everything the search produced -- nearly the
    // whole archive -- both streams are new.
    //
    // That makes this a SEED-SENSITIVITY test rather than a train-versus-test split, and it is
    // still the test worth running: a member that passes one stream and fails the other was
    // admitted by a draw, not by the record. The columns are named for what they are, because the
    // old names said train and test and someone would eventually build an argument on that.
    if c.holdout > 0 {
        if loaded.is_empty() {
            usage(&format!("no archive in {} to re-score", c.out));
        }
        let train: Vec<u64> = (0..c.holdout)
            .map(|k| (c.base as u64).wrapping_add(k as u64 * 1_000_003))
            .collect();
        let fresh: Vec<u64> = (0..c.holdout)
            .map(|k| (c.base as u64).wrapping_add(991 + k as u64 * 1_000_003))
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
                dead_zone(c.dead),
            );
            let b = judge(
                &base,
                &m.dials,
                anchors,
                transport.as_ref(),
                c.paths,
                c.years,
                &fresh,
                dead_zone(c.dead),
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
        // ---- THE NULL CONTROL -------------------------------------------------------------
        // Every member is compared with THE WORLD IT WAS SEEDED FROM, on the same fresh stream.
        //
        // The seed worlds are hand-tuned, some of them over many releases, against these same
        // anchors. A days-long automated search against the same objective should land where that
        // work already is. Three outcomes, and they mean different things:
        //
        //   nothing beats its seed          the objective is already at its limit, and the
        //                                   archive's worth is its SPREAD, not a better world
        //   something beats it, and it holds  the hand-tuning left something on the table
        //   something beats it, and it does not  THE OBJECTIVE HAS A HOLE, and a search running for
        //                                   days will find any hole its scoring function has
        //
        // The third is what this exists to catch, and it is the one nobody goes looking for.
        //
        // AGAINST ITS OWN SEED, not against a single global default, because the gate is not the
        // same for every lineage: channel rows only fire when that channel is on, so a basket
        // world faces rows a plain one never does. Comparing across lineages would be comparing
        // scores earned under different standards.
        //
        // THE THRESHOLD IS MEASURED, not assumed, and POOLED ACROSS LINEAGES. Each seed world is
        // read on each fresh seed on its own; the deviations from each world's own mean are pooled
        // and their spread is the noise scale.
        //
        // Pooled, because the noise belongs to the OBJECTIVE and the ensemble, not to a world. A
        // per-world range over three draws is a terrible estimator and behaves accordingly: on the
        // first real archive it gave 0.0049 for one lineage and 0.1102 for another, a 22-fold
        // difference in how hard it was to raise a flag, and flagged three members whose margins
        // were 0.006 to 0.017 -- all far inside the objective's own seed noise.
        //
        // AND THE CONTROL STATES ITS OWN RESOLUTION, because a control that cannot see a difference
        // must not be read as evidence there is none. Seed noise here is large: Phase 0 measured a
        // smooth sd of 0.074 plus a half-point jump whenever a row near its band edge flips, on
        // about one seed in six. Detecting a difference of 0.05 therefore needs of order a dozen
        // holdout seeds, not three.
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
        let mut seed_reads: Vec<(String, Vec<f64>)> = Vec::new();
        for nm in &seed_names {
            let w = world_for(nm);
            let dials = dials_of(&w);
            let per: Vec<f64> = fresh
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
                        dead_zone(c.dead),
                    )
                    .raw
                })
                .collect();
            seed_reads.push((nm.clone(), per));
        }
        let mut devs: Vec<f64> = Vec::new();
        for (_, per) in &seed_reads {
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
        println!("   seed world              its raw   members beating it by more");
        let mut flagged: Vec<(String, String, f64)> = Vec::new();
        for (nm, per) in &seed_reads {
            let hi = per.iter().copied().fold(f64::NEG_INFINITY, f64::max);
            let mine: Vec<&(Member, Read, Read)> =
                rows.iter().filter(|(m, _, _)| &m.name == nm).collect();
            let beat: Vec<&&(Member, Read, Read)> = mine
                .iter()
                .filter(|(_, _, b)| b.feasible && hi - b.raw > threshold)
                .collect();
            println!(
                "   {nm:<22} {hi:>7.4}   {:>4} of {:<4}",
                beat.len(),
                mine.len()
            );
            for (m, _, b) in &beat {
                flagged.push((m.name.clone(), b.worst.clone(), hi - b.raw));
            }
        }
        if flagged.is_empty() {
            println!("   PASSES: no member beats its seed by more than this control can resolve.");
            println!("   The archive's value is its spread, which is what it was built for.");
        } else {
            println!(
                "   {} members beat their seed by more than its own spread. Before believing it,",
                flagged.len()
            );
            println!(
                "   look at WHICH ROW moved -- the objective minimises the WORST row, so a world can"
            );
            println!(
                "   degrade every other row freely while that one improves -- and at the descriptor"
            );
            println!("   columns, which carry the statistics nothing grades:");
            for (nm, worst, by) in flagged.iter().take(8) {
                println!("     {nm:<22} better by {by:>6.4}, now worst on {worst}");
            }
        }
        println!();

        let kept = rows
            .iter()
            .filter(|(_, a, b)| a.feasible && b.feasible)
            .count();
        let lucky = rows
            .iter()
            .filter(|(_, a, b)| a.feasible && !b.feasible)
            .count();
        let both = rows
            .iter()
            .filter(|(_, a, b)| !a.feasible && !b.feasible)
            .count();
        let mut out = String::from("name\tpassA\trawA\tpassB\trawB\tworstB\n");
        for (m, a, b) in &rows {
            let _ = writeln!(
                out,
                "{}\t{}\t{:.6}\t{}\t{:.6}\t{}",
                m.name, a.feasible, a.raw, b.feasible, b.raw, b.worst
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

    // `-gens 0` runs until killed; the checkpoint after every generation is what makes that safe.
    let rs = ranges();
    // A RUNNING NOISE ESTIMATE, from the spread each candidate showed across its own repetitions.
    // Measured rather than declared, and it costs nothing: the readings are already there.
    let mut noise_sum = nsum0;
    let mut noise_n = nn0;
    let mut arc = start_arc;
    let mut g = gen0;
    let mut evals = evals0;
    while c.gens == 0 || g < gen0 + c.gens {
        // One generation: `pop` mutations of members drawn from the archive, then a checkpoint. A
        // kill between generations loses at most one generation's work.
        // THE SAME GENERATOR AS THE REST OF THE PROGRAM, and the same one the Scala harness
        // uses: `NumPyRng`'s `randn` and `next_bounded_u32` are gated bit-identical across the
        // twins, so both harnesses propose the SAME candidates from one `-seed`. `-calibrate` was
        // moved off a language-native RNG for this reason and the search was the last place one
        // survived. The mask keeps the derived seed non-negative, because the two languages type
        // it differently.
        let mut rng =
            NumPyRng::new(((c.base ^ (g as i64).wrapping_mul(0x9e37_79b9)) & i64::MAX) as u64);
        let mut log = Vec::new();
        for k in 0..c.pop {
            let parent = arc[rng.next_bounded_u32(arc.len() as u32) as usize].clone();
            let child: Vec<f64> = (0..rs.len())
                .map(|i| {
                    clamped(
                        &rs[i],
                        parent.dials[i] + rng.randn() * c.sigma * (rs[i].2 - rs[i].1),
                    )
                })
                .collect();
            let seeds: Vec<u64> = (0..c.reps)
                .map(|j| (c.base as u64).wrapping_add((evals + j as u64) * 7919))
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
                dead_zone(c.dead),
            );
            let secs = t0.elapsed().as_secs_f64();
            let bs: Vec<String> = r.desc.iter().map(|x| g8(*x)).collect();
            let gate_fail = r.gate_fail.join("; ");
            // admitted BEFORE the line is written, because the line records the answer
            let mut took = false;
            if r.feasible {
                noise_sum += r.spread;
                noise_n += 1;
                // at least one reading here: the increment above is on this path
                let noise = noise_sum / noise_n as f64;
                let (next, entered) = admit(
                    arc,
                    Member {
                        name: parent.name.clone(),
                        dials: child,
                        score: r.score,
                        raw: r.raw,
                        worst: r.worst.clone(),
                        desc: r.desc,
                    },
                    c.sep,
                    c.keep,
                    noise,
                );
                arc = next;
                took = entered;
            }
            log.push(format!(
                "{g}\t{}\t{}\t{}\t{:.6}\t{:.6}\t{}\t{:.3}\t{}\t{}\t{took}",
                evals + k as u64,
                parent.name,
                r.feasible,
                r.score,
                r.raw,
                r.worst,
                secs,
                bs.join("\t"),
                gate_fail
            ));
            evals += c.reps as u64;
        }
        append_log(&c.out, &log);
        write_archive(&c.out, &arc, g + 1, evals, (noise_sum, noise_n), &settings);
        let best = arc.iter().map(|m| m.score).fold(f64::INFINITY, f64::min);
        let raw = arc.iter().map(|m| m.raw).fold(f64::INFINITY, f64::min);
        let scores: Vec<f64> = arc.iter().map(|m| m.score).collect();
        println!(
            "gen {g:>5}  archive {:>3}  best {best:>7.3}  median {:>7.3}  raw {raw:>7.3}  evals {evals:>6}",
            arc.len(),
            ms::pctile(&scores, 0.5)
        );
        g += 1;
    }
}

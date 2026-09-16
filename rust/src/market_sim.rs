//! One half of the cross-language demo pair; `jsrc/marketSim.sc` is the other.
//!
//! Unlike the other pairs, this one is not a tour of an API — it is a real workload that
//! happens to exercise `NumPyRng`, `cli` and `MatD` together at scale (200 paths × 100
//! years), and it is the consumer that drove Tier 3 milestone 1. `-emit` and `-validate`
//! are byte-identical between the two languages.
//!
//! Every mode is ported: the price-formation core (`World`, `Market`, `simulate`), the
//! measurement layer — stylised-fact statistics, drawdown episodes, the three-class
//! acceptance gate and the calibration loss — the exposure rules and grading statistics,
//! and the `-emit`/`-validate`/`-strategies`/`-power`/`-buffer`/`-fitness`/`-calibrate`/
//! `-crossasset`/`-noise`/`-releases`/`-ddshape` reports, at both `-anchors` sets. `-emit` writes a
//! TSV and a JSON sidecar, and both are byte-identical too.
//!
//! ONE surface is deliberately not ported: the usage text. The Scala twin's comes from `uni`'s own
//! `showUsage`, which has no counterpart here, so a bad argument prints the same message on both but
//! only the Scala side follows it with the flag list. Consult that side for the flags.
//!
//! Run: `cargo run --release --bin market_sim -- -validate`, or `cargo install vastblue-uni` for the
//! binary; in-process, `uni::market_sim::{named_world, simulate}` return the same paths.
//!
//! `-version` prints the crate version and exits, and the `-emit` sidecar records it. The
//! default world moved at 0.19.1 and 0.19.2, so a consumer holding an emitted path needs to
//! know which release wrote it; a stale binary on `PATH` is otherwise silent.
//!
//! # Fidelity
//!
//! The whole simulation is deterministic given a seed, and `NumPyRng` is bit-identical
//! across the two languages, so a correct port reproduces the Scala output byte for byte.
//! That makes `diff` the acceptance test rather than judgement. Things ordinary-looking
//! Rust would get wrong, and are therefore written the long way here:
//!
//! - **Draw order is load-bearing.** Every `randn()`/`next_f64()`/`next_bounded_u32()`
//!   call must happen in the same sequence as in Scala, including inside branches that
//!   look reorderable.
//! - **`%.6f` is Java's, not Rust's.** [`crate::udata::java_format_f`] rounds the shortest
//!   decimal representation half-up, as `f"$x%.6f"` does; Rust's `{:.6}` rounds the exact
//!   binary value half-to-even, and the two disagree on boundary cases.
//! - **`signum` differs.** Scala's `Double.sign` returns 0.0 at zero; Rust's `f64::signum`
//!   returns ±1.0 and never 0.0. The calibration loss branches on it, so [`scala_sign`]
//!   reproduces the Scala meaning.
//! - **Sorting is total-order.** Scala's `.sorted` on `Double` uses `TotalOrdering`, which
//!   `f64::total_cmp` matches; `partial_cmp` would not.
//! - **Plain `.sum` is a left fold**, not `MatD`'s chunked `sumD`. Where the Scala says
//!   `.sum` on a collection rather than on a `MatD`, this uses `iter().sum()` to match.

#![allow(
    clippy::print_stdout,
    clippy::print_stderr,
    reason = "a demo prints its report; here the report IS the parity check"
)]

use std::cmp::Reverse;
use std::collections::BinaryHeap;
use std::sync::Arc;

use rayon::prelude::*;

use crate::NumPyRng;
use crate::udata::MatD;
use crate::udata::java_format_f;
use crate::utime::UniDateTime;

/// Which release this binary is, from `Cargo.toml` at compile time. Never a literal: a copied
/// or stale `market_sim` cannot report a version it was not built from, which is the whole
/// point of the `-version` flag and of the sidecar's `version` field. The Scala twin reads
/// `uni.BuildInfo.version`, generated from `build.sbt` the same way, and the two agree because
/// `release-and-publish.sh` refuses to publish unless the two build files carry one version.
const VERSION: &str = env!("CARGO_PKG_VERSION");

/// The sidecar format this build writes. Bump it whenever the sidecar's SHAPE changes — a key
/// added, removed or renamed, or a value's meaning changed — so a reader can tell "I cannot parse
/// this" from "I parsed it and the world differs". Deliberately NOT derived from `VERSION`: most
/// releases move the world and leave the format alone, and a schema that tracked the release would
/// tell a reader nothing.
///
/// `EMIT_SIDECAR_KEYS` is the contract that goes with it, and the writer does NOT read it — that is
/// the point. The test below compares the keys actually emitted against this list, so adding a key
/// without touching this line fails the build at the moment the discrepancy is created, next to the
/// schema number that then has to be decided about. A test cannot force the bump; it can force the
/// decision to be conscious, which is what this pair is for.
// 4 -> 5: `world.crowdImpact` is a different quantity. It was price pressure per unit of exposure
// HELD by the momentum crowd (and per unit TRADED by the other two, on a scale 13x larger); it is
// now per unit TRADED, one rule for every crowd. A reader that reconstructs a `World` from a
// schema-4 sidecar and runs it here gets a different market with no error — exactly what the schema
// number exists to prevent.
// 5 -> 6: each `fidelity` row gained `aggregation` and `horizonYears`, and `ratio` became
// nullable, paired with a new `percentile`. A schema-5 reader that treats `ratio` as always present
// breaks loudly on the null rather than dividing two incomparable statistics in silence, which is
// the whole reason the field is null and not a number. `world` also gained the five disaster
// dials; a reader that reconstructs a `World` from a schema-5 sidecar and runs it here gets a
// market without the century-tail channel.
// 6 -> 7: `world` gained the valuation cycle's four dials (`beliefShare`, `beliefYears`,
// `capYears`, `capWindow`) and the asymmetry three (`leverage`, `downShock`, `jumpSkew` — the
// last a dialised constant, 0.4 in every prior release). A reader that reconstructs a `World`
// from a schema-6 sidecar and runs it here gets a market whose perceived fair value never leaves
// the fundamental.
// 7 -> 8: `world` gained the satellite leg's two dials (`satBeta`, `satIdio`), and the TSV a
// `logSat` column — present ONLY when `satBeta > 0`, the NATURAL LOG of the satellite price.
// Log, not a level, deliberately: a level near 1e6 rendered at %.6f puts the twins' 1-ulp
// transcendental latitude (PARITY.md §6) within reach of a rounding tie — measured at ~100
// cross-language print flips per 40 century paths — where the log sits nine orders under the
// printed digit. A reader that reconstructs a `World` from a schema-7 sidecar loses nothing:
// the dials were 0 in every world such a sidecar could describe.
// 8 -> 9: `gate` gained `anchors` (which ruler graded this world — a `-anchors nasdaq` run was
// otherwise indistinguishable from an S&P one in its own provenance record) plus `gradedSeries`
// and `ungradedChannelSeries`, which say in the artifact itself that the verdict is about `price` and
// `bond` and NOT about any emitted channel column. `world` gained the bar channels' dials
// (`rangeScale`, `rangeDown`, `volIdio`), and the TSV the
// columns `logHigh`/`logLow` (present ONLY when `rangeScale > 0` — log prices of the sampled
// intra-bar extremes; the bar's open is the prior close unless `overnight` > 0) and
// `logVolume` (present ONLY when `volIdio > 0` — a mean-free log turnover index; apply your
// own detrend convention as you would to a real series). Log columns for the same tie reason
// as `logSat`. A bars-off schema-9 file is byte-identical to its schema-8 counterpart except
// the schema number and the two new (zero) world fields.
// 9 -> 10: the gate GRADES the channel columns (fifteen `satellite *` / `bar *` rows, present
// exactly when their channel ran), so `gradedSeries` lists `logSat`, `logHigh`/`logLow` and
// `logVolume` whenever those rows exist and `ungradedChannelSeries` is empty by construction —
// a schema-9 reader that took `gradedSeries` as fixed at `["price", "bond"]` misreports the
// verdict's scope. Two `world` dials changed MEANING: `satIdio` is the leg's idio sd as a
// FRACTION of the primary's realized volatility (was an absolute per-year sd at unit vol-state;
// anchored 0.074 -> 0.77) and `rangeScale` multiplies the session scale re-levelled onto the
// world's realized volatility (was the diffusion scale alone; anchored 1.1 -> 0.63). A reader
// that reconstructs a `World` from a schema-9 sidecar with either dial on and runs it here gets
// a different leg or bar with no error — the `crowdImpact` case again. A top-level `channels`
// block carries the readings those rows grade (`satellite`, `barRange`, `barVolume`, each
// present exactly when its channel ran, led by the world `level` they were sampled at), so a
// channel FAIL can be sized from the file alone — `fidelityFailed` names a band, not a value.
// 10 -> 11: the dividend stream. `world` gained `divYield` (the world's mean yield); the TSV
// gained `logTraded` (present ONLY when `divYield > 0` — the natural log of the traded price, the
// total-return `price` deflated by the yield accrued each session, so `price` keeps its meaning)
// and `divYield` (the session yield in %/yr, a level: nothing here is near the tie magnitude);
// `channels.level` gained `kDiv`, the world's mean fundamental/price the yield was normalized
// by, and `channels.dividend` the mean yield read. THE OPEN, same release: `world` gained
// `overnight`, the TSV `logOpen` (present ONLY when `overnight > 0` — the bar's open as a log;
// `logHigh`/`logLow` then bracket the open and the close rather than the prior close and the
// close), and `channels.open` the share readings. THE BASKET, same release: `world` gained
// `basket` and its four dials, the TSV `logBasket` (the equal-weight aggregate, log) and
// `logName1..N` (present ONLY when `basket > 0`; N from the header), and `channels.basket` the
// three levels' readings. A channels-off schema-11 file is byte-identical to its schema-10
// counterpart except the schema number and the new zero world fields.
// 11 -> 12: THE MACRO PANEL. `world` gained `macro` (the flag's name, like every dial's key; the
// field is `macro_panel` because the Scala twin's cannot be `macro`, a reserved word there); the
// TSV gained `macroSpread`, `macroSlope`, `macroCond`, `macroIvol` (present ONLY when `macro > 0` — levels in their
// counterparts' units, BAA10Y / T10Y2Y / NFCILEVERAGE / VIXCLS, each the value an agency would
// MEASURE that session: cadence, release lag and revisions are the consumer's point-in-time
// layer, applied to an emitted column exactly as to the real series); and `channels.macro` the
// readings the macro rows grade, naming each column's counterpart and natural cadence so a
// consumer's loader can route it through the table its FRED name would get. A panel-off
// schema-12 file is byte-identical to its schema-11 counterpart except the schema number and the
// new zero world field.
// 12 -> 13: THE PANEL'S TWO NEW MEMBERS. The TSV gained `macroYield10` and `macroCredit`
// (present ONLY when `macro > 0`, like the first four — the 10-year yield in pp against DGS10, and
// the borrowing stock read as credit over output in percent against TOTBKCR/GDP), and
// `channels.macro` their member blocks: a schema-12 reader that took the column list as fixed at
// four, or indexed the members positionally, misroutes both. EVERY POOLED PANEL STATISTIC also
// gained a `perPath` object beside it — `[p5, p50, p95]` of the per-path readings, the width of
// the null a single path sits in — at the panel level and inside each member, so a reader that
// took `channels.macro` as flat numbers finds objects. THE VOL RESPONSE, same schema: `world`
// gained `volResp`, `volRespPhi`, `volRespCap`, `volRespAttack`, `jumpResp` and `stressAdapt`, and
// the item-12 cascade gained `noiseAsymPhi` and `noiseAsymCap`. THE SLOW REPRICING CHANNEL, same
// schema again: `world` gained `slowShare`, `slowVol`, `slowLev`, `slowPhi`, `slowPerm` and
// `slowBeta`. A reader that reconstructs a
// `World` from a schema-12 sidecar and runs it here gets no volatility response and the slow
// amplifier scale — a different market, the `crowdImpact` case again. A panel-off schema-13 file
// differs from its schema-12 counterpart in the schema number and the eight new world fields,
// which are NOT all zero: `volRespPhi`, `volRespCap`, `stressAdapt` and `noiseAsymPhi` carry their
// off values.
// 13 -> 14: THE PANEL'S POLICY RATE. The TSV gained `macroPolicy` (present ONLY when `macro > 0` —
// the overnight rate in pp against DFF: the loop's own policy rate re-set at a meeting to the
// nearest quarter point and held, which is how the record's target is published) and
// `channels.macro` its member block. The rate itself has always been the `rate` column, in DECIMAL
// and unpublished; nothing about it changed, and a consumer reading DFF wants the pp staircase
// beside the credit spread and the term spread, in the units its thresholds are in. A panel-off
// schema-14 file is byte-identical to its schema-13 counterpart except the schema number.
// 14 -> 15: THE CREDIT SYSTEM SPLIT. The TSV gained `macroBankCredit` (TOTBKCR) and `macroOutput`
// (GDP) -- output an INDEX at 100 on the first emitted session, bank credit the ratio times it,
// so it starts at the ratio's first value -- and `channels.macro` their member blocks;
// `macroCredit` is the ratio they imply and its VALUES CHANGE, because it is now its own slow
// stock rather than the leverage cycle read in percent -- a schema-14 reader gets the same
// column meaning a materially different series, and one that turns over decades rather than every
// four years. A panel-off schema-15 file is byte-identical to its schema-14 counterpart except the
// schema number.
// 15 -> 16: THE IMPLIED-VOL LEVEL. `macroIvol`'s VALUES CHANGE: the member is re-levelled in the
// premium's own statistic (`level.kIv`, now in the sidecar's `level` block), so its log premium
// over forward realized vol is the record's in every world; the old level counted the slow
// channel's variance twice. A panel-off schema-16 file is byte-identical to its schema-15
// counterpart except the schema number and the `kIv` key.
// 16 -> 17: THE BUST SWING. `world` gained `bustAmp` (0 in every shipped world); a schema-17
// file is byte-identical to its schema-16 counterpart except the schema number and that key.
const EMIT_SCHEMA: u32 = 17;

/// Frozen structural constants of the volume channel — see the `vol_idio` field. Measured
/// from the SPY/QQQ volume-on-range regression (`bars-2026-09-01.tsv`, whose rows the
/// contract tests assert these against): elasticity of detrended log volume to the range's
/// log-deviation from its slow normal; the down-day loading calibrated to the RESIDUAL
/// +0.036 down-up; the slow idio component's persistence and variance share (acf5/acf1 =
/// 0.84 rules out a single AR(1)). The slow innovation sd follows from the
/// stationary-variance identity.
const VOL_SLOPE: f64 = 0.51;
/// Yesterday's range, still moving today's volume: participation decays over days rather than
/// resetting with the bar. Identified as the second term of a distributed lag (`v_t ~ rx_t +
/// rx_{t-1}`, which drops the contemporaneous slope 0.59 -> 0.51 because the single-lag fit
/// absorbed this through the range's own autocorrelation; lag 2 adds 0.04 and is dropped).
/// WITHOUT it the volume residual is independent of the range by construction, the record's
/// cross-term corr(rx_t, resid_{t+1}) = +0.14/+0.19 reads ~0 in the model, and total volume
/// autocorrelation falls to the variance-share BLEND of its parts (0.48) where the record's
/// exceeds it (0.64/0.70).
const VOL_LAG: f64 = 0.145;
const VOL_DOWN: f64 = 0.045;
const VOL_PHI: f64 = 0.97;
const VOL_SLOW_SHARE: f64 = 0.55;

/// The base random seed `-seed` defaults to, named so `main` and the tests that reproduce a
/// default-world ensemble cannot drift apart. Mirrors the Scala twin's `DefaultSeed`.
const DEFAULT_SEED: u64 = 20_260_813;

// Referenced only from the test module below; in a normal build of the example it is
// deliberately unread — the writer must not consult its own contract.
#[cfg_attr(
    not(test),
    expect(
        dead_code,
        reason = "the contract is read by the tests, never by the writer"
    )
)]
const EMIT_SIDECAR_KEYS: [&str; 11] = [
    "generator",
    "version",
    "schema",
    "file",
    "columns",
    "header",
    "path",
    "world",
    "gate",
    "channels",
    "fidelity",
];

const DAYS_PER_YEAR: usize = 252;

/// Sessions discarded so paths start from the stationary distribution (slowest state ~600).
const BURN_IN: usize = 756;

/// Treasuries incorporate rate news SAME-DAY — at 0.05 the bond market smeared a fair-value
/// move over ~20 sessions, which crushed the daily stock-bond correlation and halved every
/// crash-window bond response. 0.7 = near-immediate tracking, with flows and the spiral
/// acting as short-lived deviations on top, which is what bond-market dysfunction is.
const K_VALUE_BOND: f64 = 0.7;
/// Bond idiosyncratic noise AT THE REFERENCE DURATION. It scales with duration in `simulate`,
/// and must: a zero-duration bond is cash. Five real iShares Treasury funds spanning 1.80 to
/// 14.89 years of duration (SHY, IEI, IEF, TLH, TLT, 20-24 years each) fit
///     vol = -0.07 + 0.937 * duration
/// — an intercept of zero to within a rounding error. Held FIXED, this term was a 5.11% volatility
/// floor: the model read 1.10x real at TLT's duration, where it was calibrated, and 4.01x at SHY's,
/// so the whole short half of the bond universe was unreachable by construction rather than by
/// parameter choice. `DURATION_REF` is the shipped default, so the ratio is a bit-exact 1.0 there
/// and the default world is unchanged.
const SIGMA_N_BOND: f64 = 0.002;
const DURATION_REF: f64 = 13.5;
/// How fast policy reaches the accommodation the stress level calls for, per year: ~2 months to
/// the cap, which is what an easing cycle takes. Frozen, not a World field: the uncertain
/// quantities are HOW FAR policy can go (`easing`) and HOW LONG it stays (`unwind`), not how
/// quickly a central bank can cut in a panic — that one the record answers the same way every
/// time.
/// POLICY ACCOMMODATION's cap is an ANCHOR, not a fitted number. `usage` interpolates it and asserts
/// it IS one full real easing cycle, which makes the value a claim the program makes about itself.
/// Real full cycles: 2007-08 took the target 5.25 -> 0.125 (5.1 points), 2001-03 took 6.50 -> 1.00
/// (5.5), 1989-92 took 9.81 -> 3.00 (6.8). The 0.046 shipped through 0.20.0 was 4.6 points — BELOW
/// every one of them, so the help text was slightly false. 0.052 is 5.2 points: inside the range and
/// below its median.
///
/// It is also the only setting that clears `-crossasset`, and the two facts are independent — the
/// anchor argument stands whether or not the ladder exists. The ladder ROTATES on this dial: at
/// 0.046 the d=5.70 depth rung falls through its 0.65 floor (0.66 at the default seed, and outright
/// FAIL on 2 of 5 seeds — the EDGE that stood since 0.19.2 was the favourable draw), and at 0.058
/// the d=13.50 rung reaches its 1.35 ceiling (1.34). The admissible window is roughly 0.050-0.056
/// and the shipped value sat under it. Cost: fitness loss 1.375 -> 1.385, every equity statistic
/// unchanged.
const EASE_IN_SPEED: f64 = 6.0;
/// THE INFLATION REGIME'S CEILING, in rate units: an inflation regime's target is a half-normal
/// of `infl_size`, capped here. Uncapped, a two-sigma regime held the policy rate near 26% for the
/// regime's one to eleven years and a century put 1.7% of sessions above 20% where the record's
/// 1954-2026 put 0.11%, its maximum 22.4% (1981) and its longest run above 20% four sessions.
/// Capped at 0.12 the target's ceiling is `rate_mean` + 12 = 16.2%, the record's 1980-81 plateau,
/// and the rate's own noise carries the spike above it. A cap consumes no draw, so a path whose
/// regimes never reach it is bit-identical to the uncapped model.
const INFL_CAP: f64 = 0.12;
/// Bond volatility is measured over NON-OVERLAPPING windows of this many years, even when the
/// paths are longer. Every other statistic is measured over the whole path.
///
/// The asymmetry is deliberate and it is not free, so it is stated here, in the row's own label
/// (`bond vol % (24y)`) and in the report's anchor header. Bond volatility is the one statistic
/// that is strongly horizon-DEPENDENT in this model — 12.57% over 24 years against 17.12% over
/// 100, because a longer window samples more rate-regime variation — while its anchor can only
/// come from fund data, and the longest clean bond-fund series run 24 years. Scoring a 100-year
/// reading against a 24-year anchor reported a ratio of 1.32 where the horizon-matched answer is
/// 0.89, the same mistake the clustering anchor carried before it was re-measured.
///
/// Measured, for the record: the other three bond statistics do NOT need this. Over 24 against 100
/// years the depth rung moves 1.02x, growth-crash 1.12x and inflation-crash 0.90x, so they stay on
/// the whole-path protocol and the split is confined to one row.
const BOND_VOL_YEARS: usize = 24;
/// Equity idiosyncratic noise, ~11% annualised alone. Top-level beside its bond counterpart so
/// the crowd-flow diagnostic can state the reflexive channel as a share of it.
/// Equity idiosyncratic noise, ~11% annualised alone. Top-level beside its bond counterpart so the
/// crowd-flow diagnostic can state the reflexive channel as a share of it.
///
/// STAYS FROZEN, and now for a measured reason rather than an untested convention. It was promoted
/// to a `World` field and swept 0.005-0.013 to ask the obvious question: does it raise volatility
/// WITHOUT raising crash frequency, which `depth` cannot? It does not. Volatility moves 0.85 -> 1.68
/// of anchor while crashes move 0.94 -> 2.64, an elasticity of 1.5 — milder than `depth`'s 1.9 and
/// nowhere near the 0 that "separates" would mean. The coupling is the same mechanism in both: more
/// noise trips the liquidity spiral more often.
///
/// The sweep also LOOKS like it fixes the shallow median crash (0.81 -> 0.95 as sigmaN rises), and
/// that reading is an artifact. Hold volatility and crash rate constant by raising `depth` and
/// easing `stress` to compensate, and median depth comes out at 0.80-0.82 — WORSE than the 0.85
/// default. The apparent gain was every drawdown being bigger at higher volatility, not a new
/// degree of freedom. A dial swept alone can look like it moves a statistic it only co-moves with;
/// the test is whether it still moves it with the co-movers pinned.
const SIGMA_N: f64 = 0.007;

/// THE SECOND TAIL CHANNEL. Daily kurtosis was a recorded scope exclusion for four releases,
/// parked as needing "a slow valuation cycle". The provenance note gives the sharper reason:
/// KURTOSIS AND CLUSTERING CANNOT BOTH BE RIGHT through `stress`, which reaches kurtosis 26.4 only
/// at clustering 1.67, outside its realism band. That is a statement about `stress` — the only tail
/// channel this model had — and the same note says so: the missing cycle "is why there is no SECOND
/// channel for tails, not why this one cannot reach them."
///
/// This is that second channel, and it is a jump rather than a valuation cycle. A share `jump_var`
/// of the equity flow's variance moves out of the diffusion and into a compensated jump, so TOTAL
/// flow variance is unchanged and `equity vol %` does not move. The model does not need more crash
/// magnitude — it already runs crashes and worst-crash depth ABOVE their anchors — it needs the
/// magnitude it has arriving in fewer, more violent sessions.
///
/// The jump is a FLOW, not a return: it goes through `Market::step` like every other shock, so a
/// jump into a thin market moves the price further than the same jump into a deep one, and the
/// stress, liquidity and crowd machinery all see it.
///
/// `JUMP_NU` MUST exceed 4. A Student-t with four or fewer degrees of freedom has an INFINITE
/// fourth moment, so its sample kurtosis never converges and a kurtosis target fitted against it is
/// not a calibration.
///
/// `JUMP_GAMMA = 2` is not a taste. Intensity scales with the volatility state as `m^gamma` where
/// `m = exp(log_vol - vol_norm)` and `log_vol` is Gaussian with variance `vol_norm`, so
/// `E[m^gamma] = exp(vol_norm * (gamma^2/2 - gamma))`, which is exactly 1 at gamma = 2 and at no
/// other positive value. Only there does `jump_rate` mean the unconditional intensity it claims.
///
/// `jump_skew` (a World dial since the leverage change; 0.4 through 0.22.1) shifts the jump down
/// by that many of its own sd, carrying the negative skew a symmetric jump cannot.
const JUMP_NU: usize = 5;
const JUMP_GAMMA: f64 = 2.0;
/// A basket name's gap size, log, per standardized t draw: 0.09 puts a 1-sd gap at ~9% and the
/// per-year count past 10% at roughly half the intensity dial. Frozen, like the jump family's
/// shape constants; `basket_gaps` is the one anchored parameter.
const BASKET_GAP_SIZE: f64 = 0.09;

/// Jump size, from the share of variance it carries and how often it fires. `1 + jump_skew^2` is
/// the shift's own contribution to the second moment; without it the channel would overshoot the
/// variance it is borrowing and `equity vol %` would drift with `jump_var` — and it is why a
/// deeper skew at fixed `jump_var` makes each jump smaller rather than the tail heavier.
fn jump_scale(w: &World) -> f64 {
    SIGMA_N * (w.jump_var / (w.jump_rate * (1.0 + w.jump_skew * w.jump_skew))).sqrt()
}

/// The diffusion damp the news channel applies. News variance DISPLACES diffusive noise instead
/// of stacking on top of it, the same budget rule `jump_var` enforces with its (1 - jump_var)
/// factor: the record's 16% already contains its bad-news days, so a world calibrated without
/// them must yield generic variance when the channel turns on — added instead, the channel taxed
/// equity vol and the crash rate ~5 seed-sd and no amplifier dial could pay it back. Sized against
/// SIGMA_N's own per-session variance; the vol-state factor is centred at 1 by `vol_norm`, so the
/// unconditional budget is the right ruler. 1.0 when the channel is off. 0 once the news variance
/// has consumed the whole budget — a price running on jumps alone, whose bar channels have no
/// diffusion sd to level on (`world_level` divides by the MEAN diffusion sd); `news_budget_refusal`
/// turns that world away at the CLI rather than letting it reach a NaN bar.
fn news_damp_at(news_rate: f64, news_size: f64) -> f64 {
    if news_rate > 0.0 {
        (1.0 - (news_rate / DAYS_PER_YEAR as f64) * news_size * news_size / (SIGMA_N * SIGMA_N))
            .max(0.0)
            .sqrt()
    } else {
        1.0
    }
}

/// The CLI's refusal text for a news channel past the diffusion budget — `news_rate *
/// news_size^2` must stay below `252 * SIGMA_N^2` — stated at the caller's rate as the largest
/// admissible size; `None` inside the budget or with the channel off.
fn news_budget_refusal(news_rate: f64, news_size: f64) -> Option<String> {
    if news_rate > 0.0 && news_damp_at(news_rate, news_size) <= 0.0 {
        let budget = DAYS_PER_YEAR as f64 * SIGMA_N * SIGMA_N;
        let max_size = (budget / news_rate).sqrt();
        Some(format!(
            "-newsrate {news_rate} -newssize {news_size} leave no diffusion to displace: newsRate*newsSize^2 must stay below 252*SIGMA_N^2 = {budget:.5} (at -newsrate {news_rate}, -newssize below {max_size:.4})"
        ))
    } else {
        None
    }
}
// THE shipped world. `main` seeds its mutable CLI variables from this and the release table
/// derives its rows from it, so every default is written once — the same one-source rule the Scala
/// twin's `Defaults` follows.
pub fn default_world() -> World {
    World {
        trend_share: 0.055,
        depth: 17.4,
        stress: 5.0,
        beta: 3.0,
        drift: 0.122,
        fund_vol: 0.060,
        rate_mean: 0.042,
        vol_persist: 0.982,
        vol_of_vol: 0.028,
        recovery_drag: 8.5,
        recovery_floor: 0.10,
        halt_limit: 0.25,
        // The disaster channel, ADOPTED 0.22.1: rate 0.6/century, total log decline 2.0 over 2.5
        // years, half reversing over 4. Chosen on the tail loss term at 60 histories and verified
        // at 200x100 on four seeds (all three gate classes PASS; the record's century-worst moves
        // from the 1st percentile of model centuries to the 16-23rd). `drift` 0.113 -> 0.118
        // compensates the expected-return cost of the unreversed half (~0.6%/yr), putting return
        // per vol back on its anchor (0.71 vs 0.69).
        disaster_rate: 0.6,
        disaster_size: 2.0,
        disaster_len: 2.5,
        disaster_recover: 0.5,
        disaster_rec_len: 4.0,
        // The slow valuation cycle, ADOPTED 0.23.0 and RETUNED against the mania anchors
        // (`mania_anchor` conventions, Shiller 1881-2023): gap-beliefs at share 0.95 with a
        // 1.5y half-life carry the dispersion, growth-capitalization at 1.5 years read through
        // a 6-year window carries the upper wing, and `drift` 0.118 -> 0.120 compensates the
        // cycle's return cost. SHORTER belief half-life and HIGHER share are the amplitude
        // dials — the sweep INVERTED the naive direction (12y reads dispersion 0.115; 1.5y
        // reads 0.26 at share 0.9) — and share is the cheap currency: years is what pays the
        // depth rungs (0.5y fails d10 outright). At 1.5/0.95 the per-path cycle reads sd 0.33,
        // half-life 7.9y, both wings ~23% past 0.25 log (record: 0.415, 11.5y, ~27.5%) —
        // roughly half the record's cycle, from a fifth of it — with dispersion 0.33, vr60
        // 1.12, frozen loss 0.955 -> 0.820, four-seed pattern unchanged (seed-7 vr60-only,
        // 1.15 from 1.17), and -crossasset PASS with no easing re-solve. Priced: d10 1.34 ->
        // 1.39, d20 2.79 -> 2.90. The record's 5y autocorrelation (0.55) stays out of reach
        // from ABOVE (model 0.84 at every setting): the model cycle is more regular than the
        // record's — disclosed, not anchored.
        belief_share: 0.95,
        belief_years: 1.5,
        cap_years: 1.5,
        cap_window: 6.0,
        leverage: 0.10,
        down_shock: 0.0,
        jump_var: 0.16,
        jump_rate: 0.0035,
        jump_skew: 0.65,
        // The asymmetry adoption, 0.23.0: the leverage kick (0.12, news-coupled), fair-value
        // news jumps (1.3/yr x -3.3%, variance-displacing) with the transitory `down_shock`
        // retired at 0, jump_skew 0.7 with the jump channel rarer-larger (0.14 var at 0.0035),
        // and the refuge bid reading settled stress (refuge_days 1, refuge 0.115; easing
        // re-solved to 0.052 — the BOTTOM of the real easing-cycle range, so its anchor holds —
        // which also puts the -crossasset short-duration rung back above its floor).
        // Verified at 200x100 on four seeds: downside vol excess +3.05 vs the record's +3.06,
        // leverage corr -0.089 vs -0.0926, calm-day tail hedge -0.24 vs -0.273, bond
        // growth-crash 6.9 vs 6.6, with the seed-7 vr60 failure unchanged from the prior world.
        // stress/vol_of_vol/vol_persist/value_pull/recovery_drag/drift re-tuned to hold the rest;
        // the two rows that give ground are clustering lag 20 (0.214 -> 0.197 vs anchor 0.225)
        // and valuation dispersion (0.230 -> 0.215 vs target 0.30), disclosed in the CHANGELOG.
        news_rate: 1.3,
        news_size: 0.033,
        refuge_days: 1.0,
        sat_beta: 0.0,
        sat_idio: 0.0,
        range_scale: 0.0,
        range_down: 0.0,
        vol_idio: 0.0,
        div_yield: 0.0,
        overnight: 0.0,
        basket: 0,
        basket_beta: 0.0,
        basket_sector: 0.0,
        basket_idio: 0.0,
        basket_gaps: 0.0,
        basket_drift: 0.0,
        macro_panel: 0,
        macro_null: 0,
        lev_persist: 0.0,
        noise_asym_phi: NOISE_ASYM_PHI,
        // THE VOL RESPONSE, 0.24.1: a persistent vol state driven by the session's decline in
        // units of the conditional sd THAT GENERATED IT, and a faster spiral scale so the spiral
        // can tell a volatile stretch from a stressed one. The record's volatility after a fall
        // stays elevated for twenty sessions; 0.24.0 read 55-62% of the record's leverage-effect
        // profile at every lag past 1, this world reads 77-135% from lag 6 out and reaches the
        // record at lag 20. `stress` 4.7 -> 5.3 and `vol_persist` 0.993 -> 0.982 re-solve around
        // the two mechanisms, `jump_var` 0.11 -> 0.12 holds the downside excess. Verified at
        // 400x100 on sixteen seeds: lag-5 response 0.52 -> 0.71 of the record and lag-20
        // 0.58 -> 0.95, the crash count 0.96 -> 0.98, time spent 20% underwater 3.11 -> 2.86,
        // every gate row green. Kurtosis does NOT move on net (0.97 either side): the lower
        // `vol_persist` costs it — 0.57 with the response off — and the response restores it.
        // PRICED, and disclosed, each on 16 of 16 seeds: lag-1 clustering 1.08 -> 1.15 of the
        // record, the lag-1 leverage correlation 0.98 -> 1.08 (the response adds to a channel
        // already AT the record), and lag-20 clustering 0.83 -> 0.78. That last is the release's
        // real trade, SYMMETRIC persistence for asymmetric: `vol_persist` 0.993 -> 0.982 takes
        // the |r| autocorrelation at lag 20 from 0.188 to 0.113 against a record of 0.230, and
        // the decline-driven state returns it only to 0.175.
        vol_resp: 0.021,
        vol_resp_phi: 0.992,
        vol_resp_cap: 40.0,
        vol_resp_attack: 0.5,
        jump_resp: 0.0,
        stress_adapt: 0.036,
        bust_amp: 0.0,
        noise_asym_cap: 0.0,
        // THE SLOW REPRICING CHANNEL, 0.24.1: a fifth of the diffusive variance leaves the
        // order-flow channel and reprices the fundamental and the price together, so it never
        // passes the spiral. It is what flattens the |r| autocorrelation profile toward the
        // record's SHAPE: lag-20 over lag-1 reads 0.594 against the record's 0.753, where the vol
        // response alone read 0.527 (400x100, default seed). `stress` 5.3 -> 5.0, `vol_of_vol` 0.022 -> 0.028, `jump_var` 0.12 ->
        // 0.16, `jump_skew` 1.0 -> 0.65 and `vol_resp` 0.019 -> 0.021 re-solve around it, holding
        // kurtosis and the crash count and putting the downside excess on the record. Verified at
        // 400x100 on sixteen seeds and scored on 32: the calibration loss falls 0.102 +/- 0.040
        // against the channel-free world, better on 29 of 32 seeds.
        // PRICED, and disclosed: equity volatility 5% over the record against 3% before, and the
        // return per unit of volatility that follows from it (0.94 against 0.96). The band keeps
        // 10.8 seed-sd of headroom at 400x100 and 3.9 at 60x80.
        slow_share: 0.20,
        slow_vol: 0.894,
        slow_lev: 1.1,
        slow_phi: 0.996,
        slow_perm: 0.30,
        slow_beta: 0.55,
        noise_asym: 0.0,
        stress_scale: 0.0,
        lev_gain: 6.0,
        value_pull: 0.056,
        crowd: Crowd::Momentum,
        crowd_impact: 0.030,
        panic: 0.0,
        duration: 13.5,
        easing: 0.052,
        unwind: 0.35,
        refuge: 0.115,
        infl_prob: 0.20,
        infl_size: 0.10,
        infl_speed: 0.010,
        rate_speed: 3.0,
        discount: 5.73,
        margin: 0.006,
    }
}

/// The default world as it shipped at each published release, so a candidate can be compared
/// against EVERY shipped version rather than only its immediate predecessor — the reading under
/// which five individually-acceptable trades accumulate invisibly.
///
/// The worlds are historical; the MEASUREMENT is current. This answers "how has the default
/// moved", NOT "what did that version report" — the mechanism moved too, and conflating those
/// would be its own error. A `World` field added after a release takes today's value in that
/// release's row, because an older world genuinely has no value for it. A field REMOVED by a
/// mechanism change is the same case read backwards: 0.17.0-0.19.0 shipped `flight = 0.38`, an
/// uncapped cut speed for which the capped accommodation has no equivalent value, so those rows
/// carry today's `easing`/`unwind`. The row still answers the question the report asks.
///
/// 0.17.0 through 0.19.0 share one world: the default did not move for three releases, and
/// 0.19.3 shipped the 0.19.2 world unchanged — it added version reporting, not a world change.
///
/// The 0.19.2 default, as a FULL literal. Historical rows chain from here, never from the
/// live `default_world()`: derived from it, every field a past release shipped unchanged
/// would silently take the current value the moment the default moves — which 0.20.0's
/// recalibration was the first to do.
fn v0_19_2() -> World {
    World {
        trend_share: 0.06,
        depth: 16.6,
        stress: 5.1,
        beta: 3.0,
        drift: 0.117,
        fund_vol: 0.13,
        rate_mean: 0.042,
        vol_persist: 0.99,
        vol_of_vol: 0.011,
        recovery_drag: 0.0,
        recovery_floor: 1.0,
        halt_limit: 0.0,
        leverage: 0.0,
        down_shock: 0.0,
        jump_var: 0.0,
        jump_rate: 0.0,
        jump_skew: 0.4,
        news_rate: 0.0,
        news_size: 0.0,
        refuge_days: 0.0,
        sat_beta: 0.0,
        sat_idio: 0.0,
        range_scale: 0.0,
        range_down: 0.0,
        vol_idio: 0.0,
        div_yield: 0.0,
        overnight: 0.0,
        basket: 0,
        basket_beta: 0.0,
        basket_sector: 0.0,
        basket_idio: 0.0,
        basket_gaps: 0.0,
        basket_drift: 0.0,
        macro_panel: 0,
        macro_null: 0,
        lev_persist: 0.0,
        noise_asym_phi: NOISE_ASYM_PHI,
        vol_resp: 0.0,
        vol_resp_phi: 0.98,
        vol_resp_cap: 40.0,
        vol_resp_attack: 0.0,
        jump_resp: 0.0,
        stress_adapt: 0.005,
        bust_amp: 0.0,
        noise_asym_cap: 0.0,
        slow_share: 0.0,
        slow_vol: 0.894,
        slow_lev: 1.1,
        slow_phi: 0.996,
        slow_perm: 0.30,
        slow_beta: 0.55,
        noise_asym: 0.0,
        stress_scale: 0.0,
        lev_gain: 0.0,
        value_pull: 0.013,
        belief_share: 0.0,
        belief_years: 2.5,
        cap_years: 0.0,
        cap_window: 6.0,
        disaster_rate: 0.0,
        disaster_size: 2.0,
        disaster_len: 2.5,
        disaster_recover: 0.5,
        disaster_rec_len: 4.0,
        crowd: Crowd::Momentum,
        crowd_impact: 0.088,
        panic: 0.0,
        duration: 13.5,
        easing: 0.045,
        unwind: 0.35,
        refuge: 0.08,
        infl_prob: 0.20,
        infl_size: 0.10,
        infl_speed: 0.010,
        rate_speed: 3.0,
        discount: 3.35,
        margin: 0.006,
    }
}

/// 0.24.0's world: the leverage cycle's, before 0.24.1's vol response moved `stress`,
/// `vol_persist` and `jump_var` around the two new mechanisms. Built from today's default and
/// moved back, so only the dials that changed are restated; the item-14 dials return to their off
/// values and this row reproduces 0.24.0 bit for bit.
fn v0_24_0() -> World {
    let mut w = default_world();
    w.stress = 4.7;
    w.vol_persist = 0.993;
    w.jump_var = 0.11;
    w.vol_resp = 0.0;
    w.vol_resp_phi = 0.98;
    w.vol_resp_attack = 0.0;
    w.stress_adapt = 0.005;
    w.slow_share = 0.0;
    w.vol_of_vol = 0.022;
    w.jump_skew = 1.0;
    w
}

/// 0.24.1's world, which 0.24.2 shipped unchanged and 0.24.3 ships unchanged: the vol response
/// and the slow repricing channel on the leverage cycle, every dial spelled out so the row cannot
/// follow a later `default_world()` move. Frozen 2026-09-16 from the 0.24.3 default's sidecar
/// block; `-atrelease 0.24.1` / `0.24.2` name it.
fn v0_24_1() -> World {
    World {
        trend_share: 0.055,
        depth: 17.4,
        stress: 5.0,
        beta: 3.0,
        drift: 0.122,
        fund_vol: 0.06,
        rate_mean: 0.042,
        vol_persist: 0.982,
        vol_of_vol: 0.028,
        leverage: 0.1,
        down_shock: 0.0,
        jump_skew: 0.65,
        jump_var: 0.16,
        jump_rate: 0.0035,
        news_rate: 1.3,
        news_size: 0.033,
        value_pull: 0.056,
        recovery_drag: 8.5,
        recovery_floor: 0.1,
        halt_limit: 0.25,
        disaster_rate: 0.6,
        disaster_size: 2.0,
        disaster_len: 2.5,
        disaster_recover: 0.5,
        disaster_rec_len: 4.0,
        belief_share: 0.95,
        belief_years: 1.5,
        cap_years: 1.5,
        cap_window: 6.0,
        crowd: Crowd::Momentum,
        crowd_impact: 0.03,
        panic: 0.0,
        duration: 13.5,
        easing: 0.052,
        unwind: 0.35,
        refuge: 0.115,
        refuge_days: 1.0,
        sat_beta: 0.0,
        sat_idio: 0.0,
        range_scale: 0.0,
        range_down: 0.0,
        vol_idio: 0.0,
        div_yield: 0.0,
        overnight: 0.0,
        basket: 0,
        basket_beta: 0.0,
        basket_sector: 0.0,
        basket_idio: 0.0,
        basket_gaps: 0.0,
        basket_drift: 0.0,
        macro_panel: 0,
        lev_gain: 6.0,
        stress_scale: 0.0,
        lev_persist: 0.0,
        noise_asym: 0.0,
        noise_asym_phi: 0.96,
        noise_asym_cap: 0.0,
        vol_resp: 0.021,
        vol_resp_phi: 0.992,
        vol_resp_cap: 40.0,
        vol_resp_attack: 0.5,
        jump_resp: 0.0,
        stress_adapt: 0.036,
        bust_amp: 0.0,
        slow_share: 0.2,
        slow_vol: 0.894,
        slow_lev: 1.1,
        slow_phi: 0.996,
        slow_perm: 0.3,
        slow_beta: 0.55,
        macro_null: 0,
        infl_prob: 0.2,
        infl_size: 0.1,
        infl_speed: 0.01,
        rate_speed: 3.0,
        discount: 5.73,
        margin: 0.006,
    }
}

pub fn releases() -> Vec<(&'static str, World)> {
    let mut pre = v0_19_2();
    pre.trend_share = 0.30;
    pre.depth = 12.0;
    pre.stress = 3.4;
    pre.vol_of_vol = 0.028;
    pre.value_pull = 0.015;
    pre.crowd_impact = 0.06;
    pre.drift = 0.100;
    pre.duration = 13.5;
    pre.infl_size = 0.07;
    pre.discount = 4.0;
    pre.margin = 0.0008;
    let mut pre_v1902 = v0_19_2();
    pre_v1902.depth = 16.3;
    pre_v1902.stress = 5.4;
    vec![
        ("0.17.0", pre),
        ("0.18.0", pre),
        ("0.19.0", pre),
        ("0.19.1", pre_v1902),
        ("0.19.2", v0_19_2()),
        ("0.19.3", v0_19_2()),
        ("0.20.0", v0_20_0()),
        ("0.21.0", v0_21_0()),
        ("0.22.0", v0_22_0()),
        ("0.22.1", v0_22_1()),
        ("0.23.0", v0_23_0()),
        ("0.23.1", v0_23_0()),
        ("0.24.0", v0_24_0()),
        ("0.24.1", v0_24_1()),
        ("0.24.2", v0_24_1()),
    ]
}

/// The world a release shipped, for `-atrelease`: the current version's default, or a frozen row
/// of the `-releases` table. `None` for anything else — the CLI dies naming what exists. The
/// frozen rows reproduce their release's world under the current binary because every mechanism
/// added since is dial-gated to bit-inertness at zero (the contract tests pin that); paths
/// reproduce statistically, and bit-for-bit only back to 0.23.0 (`exp_det` moved `trendPos` off
/// the native tanh).
fn release_world(version: &str) -> Option<World> {
    if version == VERSION {
        return Some(default_world());
    }
    releases()
        .into_iter()
        .find(|(v, _)| *v == version)
        .map(|(_, w)| w)
}

/// Named worlds `-atrelease` resolves beside the version rows: a recipe VERIFIED at a release and
/// frozen with the anchor set it was graded against, so a consumer can name a channel-emitting or
/// non-S&P world without carrying its flags. Built on the frozen row, never on `default_world`, so
/// a later defaults change cannot move it. Deliberately not `-releases` rows: that table grades
/// every world against ONE anchor set, and a Nasdaq world under S&P rulers is not a reading.
pub fn recipes() -> Vec<(&'static str, World, &'static str)> {
    // The channel-emitting Nasdaq world of MarketSimWorlds.md ("A Nasdaq world that passes the
    // gate") at the ANCHORED channel dials: realism, mechanism and fidelity PASS with all six
    // series graded (verified at 0.23.0: satellite corr 0.846, beta 1.20, vol ratio 1.42; range
    // vs cc vol 1.12, down/up 1.12).
    let mut nasdaq = v0_23_0();
    nasdaq.depth = 10.0;
    nasdaq.drift = 0.105;
    nasdaq.jump_var = 0.02;
    nasdaq.fund_vol = 0.06;
    nasdaq.sat_beta = 1.2;
    nasdaq.sat_idio = 0.77;
    nasdaq.range_scale = 0.63;
    nasdaq.range_down = 0.09;
    nasdaq.vol_idio = 0.34;
    // The same world with THE OPEN on, at the bar dials re-anchored for it, and the dividend
    // stream at its Nasdaq anchor — every 0.23.1 channel a consumer of this world wants on: the
    // intraday bridge
    // carries (1-w) of the variance, so the range dial rises from 0.63 to 0.78 (0.63/sqrt(0.67))
    // and the sign coupling, now read on the intraday return, from 0.09 to 0.13. Verified at
    // 200x100: overnight share 0.279 (record 0.28), range vs cc vol 1.104, down/up 1.136,
    // clustering 0.704, the satellite untouched; realism, mechanism and fidelity PASS. A new name
    // rather than a moved one: `0.23.0-nasdaq` keeps reproducing its bars byte for byte.
    let mut open = nasdaq;
    open.overnight = 0.22;
    open.range_scale = 0.78;
    open.range_down = 0.13;
    open.div_yield = 0.78;
    // The S&P default with THE BASKET on at its anchored dials and the dividend stream at its S&P
    // anchor (`basket-2026-09-02.tsv`: folio's
    // eight semis under SMH). Verified at 200x100: names vol 2.49x, gaps 2.13/yr; aggregate corr
    // 0.793, beta 1.562, vol 1.97x; pairwise 0.575, idio share 0.374, tail coincidence 0.547,
    // pairwise on the worst decile 0.682 vs 0.212 mid; the primary untouched. Time below peak
    // 0.548, a disclosed reading.
    let mut basket = v0_23_0();
    basket.basket = 8;
    basket.basket_beta = 1.56;
    basket.basket_sector = 1.1;
    basket.basket_idio = 0.9;
    basket.basket_gaps = 6.0;
    basket.div_yield = 2.95;
    // The Nasdaq world with THE BASKET on, anchored on the SAME eight names read against QQQ
    // instead of SPY. The dials are NOT the S&P set's: the eight's basket correlates 0.837 with
    // QQQ against 0.770 with SPY, so the shared leg carries more and the sector's own noise less
    // — `basket_sector` 1.2 -> 0.75, `basket_beta` the anchor's 1.365 -> 1.37. `basket_gaps` rises
    // 3.0 -> 3.5 because the level-3 rows need per-name tails the shared leg cannot supply (with
    // gaps off the level-3 rows go outside). Verified at 200x100: names vol 2.04x, gaps 3.78/yr;
    // aggregate corr 0.853, beta 1.371, vol 1.61x; pairwise 0.567, idio share 0.380, tail
    // coincidence 0.537, pairwise on the worst decile 0.636 vs 0.140 mid. Time below peak 0.710, a
    // disclosed reading. The exception among the graded rows is the gap RATE — see below.
    //
    // THE GAP RATE IS HIGH BY CONSTRUCTION, not by dial: level 1 grades the name's vol as a RATIO
    // to the primary, and the ratio's anchor is the eight against QQQ over 2012-2026 (20.6% vol)
    // while the model's primary is anchored to QQQ over 1999-2026 (24.9% model, 26.9% real — the
    // dot-com bust is in the second window and not the first). A name at the right RATIO is
    // therefore a fifth more volatile than the eight were, and clears 10% correspondingly more
    // often. With own gaps off entirely the rate still reads 2.14/yr against the eight's mean of
    // 1.86, so no dial reaches it. The row passes on the eight's own range (0.4-5.1, AMD at the
    // top); read the rate as a level, not as a match.
    let mut nq_basket = open;
    nq_basket.basket = 8;
    nq_basket.basket_beta = 1.37;
    nq_basket.basket_sector = 0.7;
    nq_basket.basket_idio = 0.85;
    nq_basket.basket_gaps = 8.0;
    // THE MACRO PANEL's recipes: the 0.24.0 default with `-macro 1`, and each 0.23.1 recipe with
    // the panel and the leverage cycle — the dials 0.24.0 moved taken from `default_world` for
    // the S&P worlds, so no dial is restated, and the two that are the Nasdaq's own re-solved
    // (`stress` 4.4, `jump_var` 0: its jump share was 0.02, and at its deeper dial the cycle's
    // tails need the lower base gain to hold kurtosis on four seeds; its `fund_vol` was 0.06
    // already). Verified at 200x100 on four seeds: the conditions index concentrates a 20% peak
    // within a quarter 1.61-1.69x (S&P) / 1.47-1.50x (Nasdaq, six seeds) into its top decile, builds to
    // rank 0.90-0.92 / 0.83-0.85 through the quarter before the peak, and realism, mechanism and
    // fidelity PASS on every seed.
    // `v0_24_0`, not `default_world`: these rows carry 0.24.0's name and must keep 0.24.0's world
    // when the default moves. 0.24.1's are below.
    let d = v0_24_0();
    let sp = |mut w: World| {
        w.stress = d.stress;
        w.jump_var = d.jump_var;
        w.jump_skew = d.jump_skew;
        w.leverage = d.leverage;
        w.vol_persist = d.vol_persist;
        w.fund_vol = d.fund_vol;
        w.lev_gain = d.lev_gain;
        w.macro_panel = 1;
        w
    };
    // THE AMPLIFIER's gain scale (item 11) on the Nasdaq worlds: at `stress_scale` 0.5 the
    // spiral's absolute size is 0.71 of the reference world's, which takes daily kurtosis 24 ->
    // 16 (record 9.6 at its own horizon) and lag-1 clustering 0.38 -> 0.31 (record 0.29); the
    // volatility it no longer supplies comes back through `depth` 10 -> 8.4 (24.1-24.5% on six
    // seeds against the band's 23.5% floor: 8.7 sat on the floor and failed it on a consumer's
    // seed), the bond's rally through `refuge` 0.115 -> 0.15, the hazard through `lev_gain` 8.
    // The crash count does not move at the band (35 -> 37/century against 25.6: diffusion alone
    // at this volatility crosses 15% thirty times a century) and lag-20 clustering gives 0.22 ->
    // 0.18 (record 0.25) — both disclosed.
    let nq = |mut w: World| {
        w.stress = 4.4;
        w.jump_var = 0.0;
        w.jump_skew = d.jump_skew;
        w.leverage = d.leverage;
        w.vol_persist = d.vol_persist;
        w.lev_gain = 8.0;
        w.macro_panel = 1;
        w.stress_scale = 0.5;
        w.depth = 8.4;
        w.refuge = 0.15;
        w
    };
    let mut sp_macro = v0_24_0();
    sp_macro.macro_panel = 1;
    let nq_macro = nq(open);
    let basket_macro = sp(basket);
    let nq_basket_macro = nq(nq_basket);
    // THE VOL RESPONSE's recipes (0.24.1): each 0.24.0 recipe with the two new mechanisms. The
    // S&P worlds take the moved dials from the default, so no dial is restated. The Nasdaq's are
    // its own, re-solved: `stress_adapt` 0.015 rather than the default's 0.036 (its spiral is
    // doing different work at `stress_scale` 0.5), `vol_resp` 0.008, and the three dials that pay
    // for them — `depth` 8.4 -> 10.0 and `stress` 4.4 -> 4.2 hold the crash count while the
    // response supplies the volatility, `lev_gain` 8 -> 9 holds the conditions index's build-up.
    // Verified at 200x100 on four seeds, every class PASS on both sets.
    //
    // A jump channel here (`jump_var` 0.06, with `lev_gain` 10 and `stress` 4.0 around it) would
    // put the recipe's downside excess back on the record's SIGN — 0.24.0 turned its jumps off and
    // nothing else in it carries skew — but it costs 21% of daily kurtosis on a row already at
    // 1.7x. Measured, not taken; the sign stays disclosed.
    let (sp_vr, nq_vr, basket_vr, nq_basket_vr) = recipes_0241(open, basket, nq_basket);
    let nq_searched = recipe_0242_nasdaq(nq_vr);
    vec![
        ("0.23.0-nasdaq", nasdaq, "nasdaq"),
        ("0.23.1-nasdaq", open, "nasdaq"),
        ("0.23.1-basket", basket, "sp500"),
        ("0.23.1-nasdaq-basket", nq_basket, "nasdaq"),
        ("0.24.0-macro", sp_macro, "sp500"),
        ("0.24.0-nasdaq", nq_macro, "nasdaq"),
        ("0.24.0-basket", basket_macro, "sp500"),
        ("0.24.0-nasdaq-basket", nq_basket_macro, "nasdaq"),
        ("0.24.1-macro", sp_vr, "sp500"),
        ("0.24.1-nasdaq", nq_vr, "nasdaq"),
        ("0.24.1-basket", basket_vr, "sp500"),
        ("0.24.1-nasdaq-basket", nq_basket_vr, "nasdaq"),
        ("0.24.2-nasdaq", nq_searched, "nasdaq"),
        ("0.24.3-nasdaq", recipe_0243_nasdaq(nq_searched), "nasdaq"),
    ]
}

/// THE SEARCHED NASDAQ (0.24.2): the 0.24.1 recipe re-solved by the calibration search with the
/// slow repricing channel's share and scale among the thirty searched dials, under the inflation
/// regime's ceiling, judged on both markets -- member 6 of search-v11, the member with the best
/// and steadiest four-seed loss of the fourteen that pass every class on every seed at 200 paths.
/// Every searched dial moved; the literals are the archive's, at its eight significant digits, so
/// `-atrelease 0.24.2-nasdaq` reproduces the member byte for byte. Against 0.24.1-nasdaq at 200
/// paths: crashes/century 39.7 -> 29.0 (record 25.6), lag-20 clustering 0.16 -> 0.19 (0.25), the
/// 60-day variance ratio 0.81 -> 0.96, the record's worst crash at the 14th percentile of the
/// model's 27-year worsts from the 12th, the downside excess -1.3 -> -0.3 (its sign still wrong);
/// paid in kurtosis 15.8 -> 17.3 (record 9.6), lag-1 clustering 0.32 -> 0.33 (0.29) and equity
/// vol 24.0 against 26.9. Fitness loss 1.20-1.24 on four seeds. The channel at 0.24 is what
/// carries the long-lag clustering; jump_var is 0; the un-searched dials are the 0.24.1 recipe's.
fn recipe_0242_nasdaq(mut w: World) -> World {
    w.depth = 11.7205;
    w.trend_share = 0.05;
    w.drift = 0.078012823;
    w.fund_vol = 0.03;
    w.crowd_impact = 0.048391393;
    w.stress = 4.8298039;
    w.value_pull = 0.052523421;
    w.recovery_drag = 9.0363421;
    w.recovery_floor = 0.05;
    w.disaster_rate = 0.46874687;
    w.disaster_size = 1.948638;
    w.disaster_recover = 0.56444827;
    w.belief_share = 0.84568138;
    w.cap_years = 2.9900737;
    w.vol_of_vol = 0.018951116;
    w.jump_var = 0.0;
    w.jump_rate = 0.00482868;
    w.leverage = 0.069007581;
    w.down_shock = 0.0073220361;
    w.jump_skew = 0.48665602;
    w.news_rate = 0.63064016;
    w.news_size = 0.039154222;
    w.refuge_days = 0.60052388;
    w.easing = 0.018171647;
    w.refuge = 0.11311295;
    w.infl_size = 0.11254528;
    w.discount = 7.3886613;
    w.margin = 0.008;
    w.slow_share = 0.2392292;
    w.slow_vol = 0.862505;
    w
}

/// THE SEARCHED NASDAQ (0.24.3): the 0.24.2 recipe re-solved by the calibration search under the
/// typical-year and wing rows, with the bust swing, the belief half-life and the slow channel's
/// bond leg and permanent share among the thirty-four searched dials -- member 53 of search-v18,
/// the steadiest of the nine members that pass every class on four seeds at 200 paths. The
/// literals are the archive's, so `-atrelease 0.24.3-nasdaq` reproduces the member byte for byte.
/// Against 0.24.2-nasdaq on the same four seeds: loss 1.50-1.89 from 1.58-2.61; the upper wing
/// 2.5 -> 7.2 (record 7.6), the lower 14.0 -> 10.7 (6.7), the downside excess 0.3 -> 0.1 (1.1);
/// paid in the worst crash (-66 -> -63 against -83) and crashes/century 30.8 -> 31.4 (25.6);
/// equity vol 24.4 against 26.9 and kurtosis 16.4 (9.6) as before. The bust swing runs at the
/// archive's 0.014: at 0.10 and above its rallies mint 20% peaks inside the busts and the macro
/// build-up band fails. The un-searched dials are the 0.24.2 recipe's.
fn recipe_0243_nasdaq(mut w: World) -> World {
    w.depth = 11.378441;
    w.trend_share = 0.091321869;
    w.drift = 0.085311578;
    w.fund_vol = 0.03;
    w.crowd_impact = 0.030261189;
    w.stress = 5.2350165;
    w.value_pull = 0.059293455;
    w.recovery_drag = 6.7192147;
    w.recovery_floor = 0.064560211;
    w.disaster_rate = 0.42211827;
    w.disaster_size = 2.1563503;
    w.disaster_recover = 0.61330999;
    w.belief_share = 0.7281211;
    w.cap_years = 4.4211681;
    w.vol_of_vol = 0.019282161;
    w.jump_var = 0.0;
    w.jump_rate = 0.005674479;
    w.leverage = 0.049527937;
    w.down_shock = 0.010033667;
    w.jump_skew = 0.46293234;
    w.news_rate = 1.1896798;
    w.news_size = 0.042578621;
    w.refuge_days = 0.88502808;
    w.easing = 0.034326342;
    w.refuge = 0.12391532;
    w.infl_size = 0.10614338;
    w.discount = 6.4992686;
    w.margin = 0.0066204344;
    w.slow_share = 0.21615067;
    w.slow_vol = 0.9349886;
    w.slow_beta = 0.57226776;
    w.slow_perm = 0.024347201;
    w.belief_years = 0.7730648;
    w.bust_amp = 0.01448313;
    w
}

/// The 0.24.1 recipe worlds, returned as (macro, nasdaq, basket, nasdaq-basket).
fn recipes_0241(open: World, basket: World, nq_basket: World) -> (World, World, World, World) {
    let d1 = default_world();
    let sp1 = |mut w: World| {
        w.stress = d1.stress;
        w.jump_var = d1.jump_var;
        w.jump_skew = d1.jump_skew;
        w.leverage = d1.leverage;
        w.vol_persist = d1.vol_persist;
        w.fund_vol = d1.fund_vol;
        w.lev_gain = d1.lev_gain;
        w.macro_panel = 1;
        w.vol_resp = d1.vol_resp;
        w.vol_resp_phi = d1.vol_resp_phi;
        w.vol_resp_attack = d1.vol_resp_attack;
        w.stress_adapt = d1.stress_adapt;
        w.vol_of_vol = d1.vol_of_vol;
        w.slow_share = d1.slow_share;
        w.slow_vol = d1.slow_vol;
        w.slow_lev = d1.slow_lev;
        w.slow_phi = d1.slow_phi;
        w.slow_perm = d1.slow_perm;
        w.slow_beta = d1.slow_beta;
        w
    };
    let nq1 = |mut w: World| {
        w.stress = 4.2;
        w.jump_var = 0.0;
        w.jump_skew = d1.jump_skew;
        w.leverage = d1.leverage;
        w.vol_persist = v0_24_0().vol_persist;
        w.lev_gain = 9.0;
        w.macro_panel = 1;
        w.stress_scale = 0.5;
        w.depth = 10.0;
        w.refuge = 0.15;
        w.vol_resp = 0.008;
        w.vol_resp_phi = d1.vol_resp_phi;
        w.vol_resp_attack = d1.vol_resp_attack;
        w.stress_adapt = 0.015;
        w
    };
    let mut sp_vr = default_world();
    sp_vr.macro_panel = 1;
    (sp_vr, nq1(open), sp1(basket), nq1(nq_basket))
}

/// What `-atrelease NAME` seeds from: a release's world, anchors untouched, or a recipe with the
/// anchor set it was verified against — which an explicit `-anchors` still overrides.
pub fn named_world(name: &str) -> Option<(World, Option<&'static str>)> {
    release_world(name).map(|w| (w, None)).or_else(|| {
        recipes()
            .into_iter()
            .find(|(n, _, _)| *n == name)
            .map(|(_, w, a)| (w, Some(a)))
    })
}

/// 0.22.1's world, frozen for the same reason `v0_20_0` is: the valuation cycle moved the
/// default off it.
/// 0.23.0's world, which 0.23.1 shipped unchanged (its releases row points here): the asymmetry
/// adoption and the valuation cycle moved the default onto it, and 0.24.0's leverage cycle moved
/// it off (`lev_gain`, and the six dials re-solved around it).
fn v0_23_0() -> World {
    World {
        trend_share: 0.055,
        depth: 17.4,
        stress: 5.15,
        beta: 3.0,
        drift: 0.122,
        fund_vol: 0.070,
        rate_mean: 0.042,
        vol_persist: 0.992,
        vol_of_vol: 0.022,
        recovery_drag: 8.5,
        recovery_floor: 0.10,
        halt_limit: 0.25,
        disaster_rate: 0.6,
        disaster_size: 2.0,
        disaster_len: 2.5,
        disaster_recover: 0.5,
        disaster_rec_len: 4.0,
        belief_share: 0.95,
        belief_years: 1.5,
        cap_years: 1.5,
        cap_window: 6.0,
        leverage: 0.12,
        down_shock: 0.0,
        jump_var: 0.14,
        jump_rate: 0.0035,
        jump_skew: 0.7,
        news_rate: 1.3,
        news_size: 0.033,
        refuge_days: 1.0,
        sat_beta: 0.0,
        sat_idio: 0.0,
        range_scale: 0.0,
        range_down: 0.0,
        vol_idio: 0.0,
        div_yield: 0.0,
        overnight: 0.0,
        basket: 0,
        basket_beta: 0.0,
        basket_sector: 0.0,
        basket_idio: 0.0,
        basket_gaps: 0.0,
        basket_drift: 0.0,
        macro_panel: 0,
        macro_null: 0,
        lev_persist: 0.0,
        noise_asym_phi: NOISE_ASYM_PHI,
        vol_resp: 0.0,
        vol_resp_phi: 0.98,
        vol_resp_cap: 40.0,
        vol_resp_attack: 0.0,
        jump_resp: 0.0,
        stress_adapt: 0.005,
        bust_amp: 0.0,
        noise_asym_cap: 0.0,
        slow_share: 0.0,
        slow_vol: 0.894,
        slow_lev: 1.1,
        slow_phi: 0.996,
        slow_perm: 0.30,
        slow_beta: 0.55,
        noise_asym: 0.0,
        stress_scale: 0.0,
        lev_gain: 0.0,
        value_pull: 0.056,
        crowd: Crowd::Momentum,
        crowd_impact: 0.030,
        panic: 0.0,
        duration: 13.5,
        easing: 0.052,
        unwind: 0.35,
        refuge: 0.115,
        infl_prob: 0.20,
        infl_size: 0.10,
        infl_speed: 0.010,
        rate_speed: 3.0,
        discount: 5.73,
        margin: 0.006,
    }
}

fn v0_22_1() -> World {
    World {
        trend_share: 0.055,
        depth: 17.4,
        stress: 5.37,
        beta: 3.0,
        drift: 0.118,
        fund_vol: 0.070,
        rate_mean: 0.042,
        vol_persist: 0.99,
        vol_of_vol: 0.027,
        recovery_drag: 10.0,
        recovery_floor: 0.10,
        halt_limit: 0.25,
        disaster_rate: 0.6,
        disaster_size: 2.0,
        disaster_len: 2.5,
        disaster_recover: 0.5,
        disaster_rec_len: 4.0,
        belief_share: 0.0,
        belief_years: 2.5,
        cap_years: 0.0,
        cap_window: 6.0,
        leverage: 0.0,
        down_shock: 0.0,
        jump_var: 0.17,
        jump_rate: 0.0050,
        jump_skew: 0.4,
        news_rate: 0.0,
        news_size: 0.0,
        refuge_days: 0.0,
        sat_beta: 0.0,
        sat_idio: 0.0,
        range_scale: 0.0,
        range_down: 0.0,
        vol_idio: 0.0,
        div_yield: 0.0,
        overnight: 0.0,
        basket: 0,
        basket_beta: 0.0,
        basket_sector: 0.0,
        basket_idio: 0.0,
        basket_gaps: 0.0,
        basket_drift: 0.0,
        macro_panel: 0,
        macro_null: 0,
        lev_persist: 0.0,
        noise_asym_phi: NOISE_ASYM_PHI,
        vol_resp: 0.0,
        vol_resp_phi: 0.98,
        vol_resp_cap: 40.0,
        vol_resp_attack: 0.0,
        jump_resp: 0.0,
        stress_adapt: 0.005,
        bust_amp: 0.0,
        noise_asym_cap: 0.0,
        slow_share: 0.0,
        slow_vol: 0.894,
        slow_lev: 1.1,
        slow_phi: 0.996,
        slow_perm: 0.30,
        slow_beta: 0.55,
        noise_asym: 0.0,
        stress_scale: 0.0,
        lev_gain: 0.0,
        value_pull: 0.045,
        crowd: Crowd::Momentum,
        crowd_impact: 0.030,
        panic: 0.0,
        duration: 13.5,
        easing: 0.060,
        unwind: 0.35,
        refuge: 0.11,
        infl_prob: 0.20,
        infl_size: 0.10,
        infl_speed: 0.010,
        rate_speed: 3.0,
        discount: 5.73,
        margin: 0.006,
    }
}

/// 0.22.0's world, frozen for the same reason `v0_20_0` is: the disaster channel moved the
/// default off it.
fn v0_22_0() -> World {
    World {
        trend_share: 0.055,
        depth: 17.4,
        stress: 5.37,
        beta: 3.0,
        drift: 0.113,
        fund_vol: 0.070,
        rate_mean: 0.042,
        vol_persist: 0.99,
        vol_of_vol: 0.027,
        recovery_drag: 10.0,
        recovery_floor: 0.10,
        halt_limit: 0.25,
        disaster_rate: 0.0,
        disaster_size: 2.0,
        disaster_len: 2.5,
        disaster_recover: 0.5,
        disaster_rec_len: 4.0,
        belief_share: 0.0,
        belief_years: 2.5,
        cap_years: 0.0,
        cap_window: 6.0,
        leverage: 0.0,
        down_shock: 0.0,
        jump_var: 0.17,
        jump_rate: 0.0050,
        jump_skew: 0.4,
        news_rate: 0.0,
        news_size: 0.0,
        refuge_days: 0.0,
        sat_beta: 0.0,
        sat_idio: 0.0,
        range_scale: 0.0,
        range_down: 0.0,
        vol_idio: 0.0,
        div_yield: 0.0,
        overnight: 0.0,
        basket: 0,
        basket_beta: 0.0,
        basket_sector: 0.0,
        basket_idio: 0.0,
        basket_gaps: 0.0,
        basket_drift: 0.0,
        macro_panel: 0,
        macro_null: 0,
        lev_persist: 0.0,
        noise_asym_phi: NOISE_ASYM_PHI,
        vol_resp: 0.0,
        vol_resp_phi: 0.98,
        vol_resp_cap: 40.0,
        vol_resp_attack: 0.0,
        jump_resp: 0.0,
        stress_adapt: 0.005,
        bust_amp: 0.0,
        noise_asym_cap: 0.0,
        slow_share: 0.0,
        slow_vol: 0.894,
        slow_lev: 1.1,
        slow_phi: 0.996,
        slow_perm: 0.30,
        slow_beta: 0.55,
        noise_asym: 0.0,
        stress_scale: 0.0,
        lev_gain: 0.0,
        value_pull: 0.045,
        crowd: Crowd::Momentum,
        crowd_impact: 0.030,
        panic: 0.0,
        duration: 13.5,
        easing: 0.060,
        unwind: 0.35,
        refuge: 0.11,
        infl_prob: 0.20,
        infl_size: 0.10,
        infl_speed: 0.010,
        rate_speed: 3.0,
        discount: 5.73,
        margin: 0.006,
    }
}

/// 0.21.0's world, frozen for the same reason `v0_20_0` is: the variance-ratio row moved the default
/// off it.
fn v0_21_0() -> World {
    World {
        trend_share: 0.055,
        depth: 16.94,
        stress: 5.37,
        beta: 3.0,
        drift: 0.113,
        fund_vol: 0.041,
        rate_mean: 0.042,
        vol_persist: 0.99,
        vol_of_vol: 0.027,
        recovery_drag: 10.0,
        recovery_floor: 0.10,
        halt_limit: 0.25,
        disaster_rate: 0.0,
        disaster_size: 2.0,
        disaster_len: 2.5,
        disaster_recover: 0.5,
        disaster_rec_len: 4.0,
        belief_share: 0.0,
        belief_years: 2.5,
        cap_years: 0.0,
        cap_window: 6.0,
        leverage: 0.0,
        down_shock: 0.0,
        jump_var: 0.10,
        jump_rate: 0.0010,
        jump_skew: 0.4,
        news_rate: 0.0,
        news_size: 0.0,
        refuge_days: 0.0,
        sat_beta: 0.0,
        sat_idio: 0.0,
        range_scale: 0.0,
        range_down: 0.0,
        vol_idio: 0.0,
        div_yield: 0.0,
        overnight: 0.0,
        basket: 0,
        basket_beta: 0.0,
        basket_sector: 0.0,
        basket_idio: 0.0,
        basket_gaps: 0.0,
        basket_drift: 0.0,
        macro_panel: 0,
        macro_null: 0,
        lev_persist: 0.0,
        noise_asym_phi: NOISE_ASYM_PHI,
        vol_resp: 0.0,
        vol_resp_phi: 0.98,
        vol_resp_cap: 40.0,
        vol_resp_attack: 0.0,
        jump_resp: 0.0,
        stress_adapt: 0.005,
        bust_amp: 0.0,
        noise_asym_cap: 0.0,
        slow_share: 0.0,
        slow_vol: 0.894,
        slow_lev: 1.1,
        slow_phi: 0.996,
        slow_perm: 0.30,
        slow_beta: 0.55,
        noise_asym: 0.0,
        stress_scale: 0.0,
        lev_gain: 0.0,
        value_pull: 0.045,
        crowd: Crowd::Momentum,
        crowd_impact: 0.07,
        panic: 0.0,
        duration: 13.5,
        easing: 0.052,
        unwind: 0.35,
        refuge: 0.11,
        infl_prob: 0.20,
        infl_size: 0.10,
        infl_speed: 0.010,
        rate_speed: 3.0,
        discount: 5.73,
        margin: 0.006,
    }
}

/// 0.20.0's world, frozen for the same reason `v0_19_2` is: 0.21.0 moved the default off it, and a
/// row that read `default_world()` would restate today's world under yesterday's version number.
fn v0_20_0() -> World {
    World {
        trend_share: 0.07,
        depth: 16.1,
        stress: 5.6,
        beta: 3.0,
        drift: 0.123,
        fund_vol: 0.13,
        rate_mean: 0.042,
        vol_persist: 0.99,
        vol_of_vol: 0.014,
        recovery_drag: 0.0,
        recovery_floor: 1.0,
        halt_limit: 0.0,
        leverage: 0.0,
        down_shock: 0.0,
        jump_var: 0.0,
        jump_rate: 0.0,
        jump_skew: 0.4,
        news_rate: 0.0,
        news_size: 0.0,
        refuge_days: 0.0,
        sat_beta: 0.0,
        sat_idio: 0.0,
        range_scale: 0.0,
        range_down: 0.0,
        vol_idio: 0.0,
        div_yield: 0.0,
        overnight: 0.0,
        basket: 0,
        basket_beta: 0.0,
        basket_sector: 0.0,
        basket_idio: 0.0,
        basket_gaps: 0.0,
        basket_drift: 0.0,
        macro_panel: 0,
        macro_null: 0,
        lev_persist: 0.0,
        noise_asym_phi: NOISE_ASYM_PHI,
        vol_resp: 0.0,
        vol_resp_phi: 0.98,
        vol_resp_cap: 40.0,
        vol_resp_attack: 0.0,
        jump_resp: 0.0,
        stress_adapt: 0.005,
        bust_amp: 0.0,
        noise_asym_cap: 0.0,
        slow_share: 0.0,
        slow_vol: 0.894,
        slow_lev: 1.1,
        slow_phi: 0.996,
        slow_perm: 0.30,
        slow_beta: 0.55,
        noise_asym: 0.0,
        stress_scale: 0.0,
        lev_gain: 0.0,
        value_pull: 0.0145,
        belief_share: 0.0,
        belief_years: 2.5,
        cap_years: 0.0,
        cap_window: 6.0,
        disaster_rate: 0.0,
        disaster_size: 2.0,
        disaster_len: 2.5,
        disaster_recover: 0.5,
        disaster_rec_len: 4.0,
        crowd: Crowd::Momentum,
        crowd_impact: 0.07,
        panic: 0.0,
        duration: 13.5,
        easing: 0.046,
        unwind: 0.35,
        refuge: 0.11,
        infl_prob: 0.20,
        infl_size: 0.10,
        infl_speed: 0.010,
        rate_speed: 3.0,
        discount: 5.0,
        margin: 0.006,
    }
}

/// `-power`'s default contrast arms, as 1-based indices into `rules()`, and its default history
/// lengths. Named here rather than inside the report so `main` seeds from them.
/// 21 = the traded book's span; 72 = the S&P record used for calibration; the ends bracket them.
const POWER_ARMS_DEFAULT: [usize; 4] = [2, 6, 9, 8];
const POWER_YEARS_DEFAULT: [usize; 4] = [21, 40, 72, 100];

/// No-trade band on the crowd's exposure target.
const BAND: f64 = 0.05;

/// What the non-value crowd trades on. Momentum is the generic extrapolator; the other two
/// run the SAME RULE being tested, so its de-risking moves the price it reacts to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Crowd {
    Momentum,
    Trend(i32),
    VolScaled,
    /// exposure keyed to distance from the running peak — folio's CDAP family as a crowd, so
    /// "does a drawdown rule survive a crowd running a drawdown rule" is finally posable. The
    /// parameter is the cut threshold in PERCENT below the peak (drawdown10 = de-risk past
    /// -10%), reading `px[i-1]` alone like the other banded crowds.
    Drawdown(i32),
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct World {
    pub trend_share: f64,
    pub depth: f64,
    pub stress: f64,
    pub beta: f64,
    /// fundamental drift per year; no dividend, so this IS total return
    pub drift: f64,
    pub fund_vol: f64,
    pub rate_mean: f64,
    pub vol_persist: f64,
    pub vol_of_vol: f64,
    /// how fast value arbitrage WEAKENS as the drawdown deepens. 0 is the symmetric pull every
    /// release before 0.21.0 had, bit for bit.
    pub recovery_drag: f64,
    /// the residual arbitrage that never goes away, as a share of full strength. 1.0 with drag 0
    /// is the old behaviour exactly.
    pub recovery_floor: f64,
    /// Equity trading halt: the largest ONE-session decline the market prints, as a simple
    /// fraction, with the unfilled pressure deferred to the next session. 0 disables it, which is
    /// what the frozen release rows inherit -- correctly, since no release before this one had the
    /// mechanism.
    pub halt_limit: f64,
    /// Macro disasters per CENTURY: rare multi-year collapses of the real fundamental (1929-32,
    /// not 1987) — the Barro-Rietz channel. Rare is what lets it deepen the century-scale tail
    /// without touching daily volatility or the 60d variance ratio, which fence off every
    /// CONTINUOUS extra-variance channel. 0 disables it; draws come from their own stream, so the
    /// frozen release rows inherit pre-disaster behaviour bit for bit.
    ///
    /// SCOPE: it shifts deep crashes toward FUNDAMENTAL-led (>35% crashes: 31% -> 40% on the
    /// -strategies classifier; the rest stay spiral dislocations), and every deep crash still
    /// starts from a peak AT fair value (p/f 0.96-1.19 measured). The model has no mania channel,
    /// so the 1929/2000 shape — a collapse from a peak far ABOVE fair value, multiples doing the
    /// falling — cannot occur. Price-path statistics cannot tell; anything reading the emitted
    /// `fundamental` column or `-strategies`' crash-type conditioning can.
    pub disaster_rate: f64,
    /// total log decline of the fundamental per disaster
    pub disaster_size: f64,
    /// years from onset to trough; the decline is spread evenly
    pub disaster_len: f64,
    /// share of the decline that REVERSES after the trough — Barro's cross-country estimate is
    /// about half. Without it a disaster century spends decades >20% underwater and the deep depth
    /// rung runs far past even the real 1929 century's share.
    pub disaster_recover: f64,
    /// years the recovery is spread over
    pub disaster_rec_len: f64,
    /// THE SLOW VALUATION CYCLE: how far the market's PERCEIVED fair value drifts toward realized
    /// prices. Value capital arbs the gap to what it BELIEVES fair is, and after years of elevated
    /// prices it believes them ("this time is different"); after years depressed, the pessimism is
    /// as sticky. Splits reversion by FREQUENCY: daily pull unchanged (beliefs barely move in 60
    /// sessions, so the variance-ratio band is untouched), multi-year reversion weakened to
    /// (1 - belief_share) of the pull — which is where CAPE-scale valuation swings live. Consumes
    /// no draws; 0 is bit-identical off.
    pub belief_share: f64,
    /// half-life of belief adaptation, in years
    pub belief_years: f64,
    /// THE MANIA HALF of the cycle: how many years of the fundamental's RECENT excess growth
    /// beliefs capitalize into perceived fair value — "this growth is the new normal", priced.
    /// The fundamental's drift regime (`drift_now`, redrawn every 1-11 years) is what beliefs
    /// extrapolate, so booms carry perceived fair — and the price that arbs toward it — above the
    /// true fundamental, and a regime ending on a re-draw is a valuation crash with the
    /// fundamental FINE: the 2000 shape. 0 is off, bit for bit, no draws consumed.
    pub cap_years: f64,
    /// years of EWMA through which beliefs read that growth: the narrative horizon. Short windows
    /// pass fundVol noise into the term capYears-fold (at 1y, vr60 read 2.3-5.2, measured) — the
    /// window must sit between the noise and the ~6-year regime.
    pub cap_window: f64,
    /// THE LEVERAGE EFFECT: how hard a decline raises the NEXT session's diffusive volatility,
    /// where an equal rally raises nothing — EGARCH's signed term, fed by the same decline
    /// signal the spiral's `stress_idx` reads (max(-ret,0)/scale, centred at 0.399 so the vol
    /// LEVEL does not drift with the dial). The spiral is episodic — thresholded and floored,
    /// it engages in crash dynamics — while the record's leverage correlation (-0.09 on every
    /// CRSP era, `asymmetry-2026-08-31.tsv`) is an everyday property; this is the everyday
    /// channel. In log-vol units against `vol_of_vol` 0.027, so 0.01 is a material setting.
    /// Consumes no draws; 0 is bit-identical off.
    pub leverage: f64,
    /// SIGN-DEPENDENT NEWS RESPONSE, the contemporaneous half of the asymmetry pair: a bad shock
    /// moves a levered market further than an equal good one, so the equity news term is scaled
    /// by (1 + down_shock) when negative and its reciprocal when positive — down sessions
    /// disperse more than up sessions AT THE SAME TIME, which no next-session vol response can
    /// produce (`downside vol excess %` reads the record at +3.1 while every leverage/jumpVar
    /// setting alone leaves it negative). Applied to the SHOCK only, never to the crowd's flows:
    /// amplifying a persistent signed flow manufactures signed persistence — measured, the
    /// flow-and-noise form paid vr60 1.11 -> 1.25 for the same skew, and this form pays a
    /// fraction of that. Consumes no draws; 0 is bit-identical off.
    pub down_shock: f64,
    /// share of the equity flow's VARIANCE carried by jumps rather than diffusion. 0 disables the
    /// channel and reproduces pre-0.21 behaviour byte for byte — the draws come from their own
    /// stream, so nothing else in the path shifts.
    pub jump_var: f64,
    /// unconditional jump intensity per session. With `jump_var` it fixes the size: rarer jumps of
    /// the same total variance are larger ones.
    pub jump_rate: f64,
    /// how far each jump is shifted DOWN, in units of its own sd — the contemporaneous-skew half
    /// of the asymmetry pair (`leverage` is the conditional half, and cutting `jump_var` to pay
    /// for it robs this channel; the two move together). Variance-normalised in `jump_scale`, so
    /// the skew deepens the down-jumps without fattening the tail. 0.4 reproduces every release
    /// back to 0.21.0 bit for bit.
    pub jump_skew: f64,
    /// FAIR-VALUE NEWS JUMPS, the downside-asymmetry channel: rare permanent DOWN-jumps of the
    /// fundamental that the
    /// price reprices the SAME session, gap-invariant — `log_vbase` and `log_p` drop together, so
    /// the value channel, the belief EWMA and `mispricing_pre` see nothing and there is no rebound
    /// to arbitrage back. Events per YEAR (contrast `disaster_rate`, per century); 0 disables, and
    /// draws come from a dedicated stream, so 0 is bit-identical off. The hypothesis under test:
    /// a permanent same-session repricing moves DOWNSIDE variance without moving the 60d variance
    /// ratio — the channel the transitory `down_shock` cannot be (its rebound IS trend).
    pub news_rate: f64,
    /// log decline per news event (positive; 0.02 = a -2% day). Deterministic size, so the sizing
    /// arithmetic p*J^2 stays exact; the drift cost `news_rate*news_size` is returned
    /// deterministically on BOTH legs each session, keeping return per vol comparable across
    /// sweep points without retuning `drift`. Bounded with `news_rate` by the diffusion budget it
    /// displaces — `news_rate * news_size^2 < 252 * SIGMA_N^2` (0.0123; size below 0.097 at the
    /// default rate) — and refused at the CLI past it (`news_budget_refusal`).
    pub news_size: f64,
    /// BOND DECOUPLING: half-life in SESSIONS of the settled-stress EWMA the refuge
    /// bid reads, which EXCLUDES the current session — flight-to-quality follows the stress
    /// investors went home with, not the move printing right now. The calm-day stock-bond
    /// correlation the `tail hedge corr` row grades is carried almost entirely by the SAME-session
    /// stress delta (cov(r_eq, stress_t) ≈ cov(r_eq, Δstress_t), and Δstress is r_eq's mirror),
    /// while the anchored crisis behaviour — growth-crash rally, refuge episodes — rides the
    /// stress LEVEL, which a short lag leaves intact. The `margin` term keeps reading live
    /// stress: joint-stress selling is a margin call, and margin calls do not wait overnight.
    /// 0 reads live stress, bit-identical to every frozen release.
    pub refuge_days: f64,
    /// SATELLITE EQUITY LEG (prototype): a second, higher-beta equity market — the Nasdaq to the
    /// default world's S&P — derived from the primary leg rather than agent-simulated. Its session
    /// return is `sat_beta` times the primary's OBSERVED log return (markdown and news included —
    /// they are shared factors) plus an idiosyncratic term whose volatility rides the SAME vol
    /// state as the primary's diffusion. That state-sharing is the measured constraint, not a
    /// convenience: SPY-QQQ correlation is state-FLAT (0.853 calm vs 0.852 stressed) BECAUSE
    /// idiosyncratic vol triples with the shared state (7.7 -> 23.7%/yr); constant idio noise
    /// would manufacture a stress-correlation kick the record does not have. Draws come from a
    /// dedicated stream, read only when `sat_beta > 0`, so 0 is bit-identical off. Anchors
    /// (SPY/QQQ 1999-2026): beta 1.20, corr 0.853, resid vol 14.1%/yr, rolling-252d beta
    /// p5/med/p95 0.90/1.18/1.92.
    pub sat_beta: f64,
    /// idiosyncratic volatility of the satellite leg as a FRACTION of the primary's own
    /// realized volatility, riding the primary's vol state (`derive_channels`). Dimensionless so
    /// the anchored coupling transports: an absolute per-year sd read relatively smaller at a
    /// higher-vol primary, and stacked on the Nasdaq recipe the leg's correlation climbed to
    /// 0.93 against the anchored 0.85 with nothing to catch it.
    pub sat_idio: f64,
    /// INTRA-BAR RANGE (prototype): high/low sampled per session from the EXACT Brownian-bridge
    /// extreme distributions, with endpoints at the observed open (= prior close; the model has
    /// the sampled open when `overnight` > 0) and close, and diffusion scale the session's OWN
    /// noise sd — news-damped,
    /// vol-state, leverage kick, jump mixing, spiral amplification, all as the price itself
    /// received them. The range therefore scales with the session's noise, NOT with |return|:
    /// the record's corr(lnH/L, |r|) is only 0.70-0.72, and a bar derived as a multiple of |r|
    /// is detectably fake (`bars_anchor` conventions, SPY/QQQ OHLCV). This dial is the one
    /// disclosed free parameter: a multiplier on that session scale AFTER it is re-levelled onto
    /// the world's realized close-to-close volatility (`derive_channels`), so it is a bar-to-ccvol
    /// ratio that holds across worlds — on the diffusion scale alone the same dial read 1.115
    /// at the default and 1.264 at the Nasdaq recipe, because the share of variance the
    /// diffusion carries is a property of the world. Absorbs the real intraday's sub-BM
    /// compression (~0.84 measured net of the overnight share) plus the model's session/day
    /// identification. Draws (two per session) come from a dedicated stream, read only when
    /// > 0, so 0 is bit-identical off.
    pub range_scale: f64,
    /// SAME-SESSION SIGN<->VOL COUPLING for the bar: a down session gets more intraday breadth
    /// per unit of net move — the bridge sigma is multiplied by (1 + rangeDown) when the
    /// session's return is negative and divided by it otherwise, the `downShock` shape. The
    /// record's down/up range ratio at the intraday-sign ruler is 1.11-1.14 where the model's
    /// cross-session channels deliver only 1.03; this is the conditional-distribution statement
    /// that closes it (the realized sign informs the day's breadth — no feedback into the
    /// price). ONE root serving two channels: volume's down-up gap rides this through
    /// `VOL_SLOPE` with no volume-side change. Draw-free; 0 is bit-identical off.
    pub range_down: f64,
    /// VOLUME (prototype): a log turnover index riding the RANGE — the record says volume
    /// follows the day's travel, not its net move (corr with lnH/L 0.54-0.55 vs 0.40-0.44
    /// with |r|), with elasticity ~0.6 to the range's deviation from its slow normal and a
    /// PERSISTENT residual (acf5/acf1 = 0.84: a slow component near AR-0.97 carrying ~55% of
    /// residual variance, plus white noise — an AR(1) alone cannot hold acf5 at 0.43). The
    /// structural constants are frozen from the SPY/QQQ regression (`VOL_SLOPE` 0.59,
    /// `VOL_DOWN` 0.045 from the residual's +0.036 down-up, `VOL_PHI` 0.97, slow share 0.55);
    /// this dial is the TOTAL idiosyncratic sd, the one anchored free parameter (record
    /// residual sd 0.29-0.38). Requires the range channel — volume without a range to ride is
    /// refused at the CLI. Two draws per session from a dedicated stream, read only when > 0,
    /// so 0 is bit-identical off.
    pub vol_idio: f64,
    /// DIVIDENDS: the world's MEAN dividend yield, %/yr. The session yield is div_yield x
    /// (fundamental/price) / the world's mean fundamental/price (`world_level`'s k_div — a world
    /// constant: the ensemble's mean gap sits well below fair, 2.1x at the default and 2.3x on
    /// the Nasdaq recipe, and a per-path mean would leak the path's future), so a rich session
    /// yields less, as the record's does, and the ensemble's pooled mean yield is the dial — the
    /// reported median path's mean reads ~0.9x of it (2.62 at 2.95), valuation epochs skewing
    /// the path means; the traded price is the total-return
    /// index (`price`, unchanged) deflated by the yield accrued each session —
    /// decisions read the traded series and accounting the adjusted one, the two-series
    /// convention a consumer enforces and could test only on real data. Derived after the price
    /// loop, draw-free, reaching no price; 0 = off, no columns, bit-identical. Anchored 2.95
    /// (Shiller D/P 1954-2023) for the S&P set and 0.78 (QQQ 2005-2026) for the Nasdaq set —
    /// `dividend-2026-09-02.tsv`; an identity parameter, never searched. The record's yield also
    /// moved with the payout level across eras, which this does not model.
    pub div_yield: f64,
    /// THE OPEN: the overnight share of the session's DIFFUSIVE variance (0 <= x < 1). The open is
    /// the bridge point at that share of the session — w x the non-jump move plus sqrt(w(1-w)) x
    /// the session sd x one normal from a dedicated stream — with the session's news jump and
    /// jump-channel move landing overnight WHOLE, since they are the news that arrives while the
    /// market is closed; when the gap overshoots the session on its own side the whole move is
    /// the gap. The bar's bridge then runs from the open over the remaining (1-w) of the
    /// variance, and the sign coupling reads the intraday return. 0 = the open is the prior
    /// close, bit-identical off; the record's overnight share of daily variance is 0.33 (SPY) /
    /// 0.28 (QQQ), `bars-2026-09-01.tsv`, and the graded share includes the jumps, so the dial
    /// sits below it.
    pub overnight: f64,
    /// THE BASKET: N single names as observational second-pass instances of the primary —
    /// name_i = the SECTOR leg + its own idio + its own gaps. The sector leg is the satellite
    /// construction (beta on the primary's observed return plus idio riding the re-levelled
    /// state factor) and is shared by every name: the model has no sector index, so the
    /// basket's equal-weight aggregate IS the sector, graded against SMH's own relation to SPY.
    /// A name's idio rides the VOL STATE only, not the spiral's amplification, so in stress the
    /// shared variance dominates and pairwise correlation rises — the mechanism the record shows
    /// (0.60 on SPY's worst decile vs 0.28 mid). Own gaps: a per-name Student-t jump (JUMP_NU,
    /// the primary's skew) at `basket_gaps` per year past ~10%. Reaches no price; 0 = off, no
    /// columns, bit-identical. Anchored on folio's eight semis under SMH
    /// (`basket-2026-09-02.tsv`): N = 8.
    pub basket: usize,
    /// sector leg: beta on the primary's observed return (anchored 1.56, the basket's beta on SPY)
    pub basket_beta: f64,
    /// sector leg: idio sd as a FRACTION of the primary's realized vol, riding the vol state x
    /// spiral (the satellite's `sat_idio` construction)
    pub basket_sector: f64,
    /// per-name idio sd as a FRACTION of the primary's realized vol, riding the vol state WITHOUT
    /// the spiral
    pub basket_idio: f64,
    /// per-name gap intensity, jumps per year; each a standardized t(JUMP_NU) x
    /// `BASKET_GAP_SIZE` (log), SYMMETRIC — the index's down-skew is the index's and reaches every
    /// name through the shared leg, while the record's own name-level gaps past 10% run 41 up to
    /// 32 down with mean +0.011 (`basket-drift-2026-09-03.tsv`). A shifted own-gap channel imposes
    /// drift nothing compensates — at these rates the primary's 0.7 skew is about -0.2/yr of log
    /// drift, more than the shared leg supplies, so every name's expected drift goes negative
    pub basket_gaps: f64,
    /// CROSS-SECTIONAL DRIFT DISPERSION: the sd of the names' own annual log-drift offsets, as a
    /// FRACTION of the primary's realized annualized vol (`ChannelLevel::k_dr`), so it transports.
    /// Drawn once per name per path and centred EXACTLY, so the equal-weight sector's log drift is
    /// untouched and only the cross-section moves. 0 = off, bit-identical; needs N >= 2.
    ///
    /// ANCHORED AT 0 by `basket-drift-2026-09-03.tsv`: among folio's eight the spread of realized
    /// drift (0.068) is entirely accounted for by what a 14.6-year window generates from their own
    /// idio vol (0.070), so no true dispersion is detectable, and selecting survivors truncates the
    /// left tail — 0 is a FLOOR from biased data, not a measurement. The names' time below peak is
    /// NOT what this dial fixes: that gap is the eight's COMMON drift (+0.304 against the shared leg's
    /// +0.117 over the same horizon), which is the survivorship the basket fixture discloses.
    ///
    /// WHAT IT IS FOR: at 0 every name has the same expected drift BY CONSTRUCTION (shared sector
    /// leg; idio and gaps share a mean), so the basket is a NULL WORLD for cross-sectional
    /// selection — no name is better than another and any ranking edge a rule shows on it is
    /// noise. Set the dial and there is a real edge of known size to find. Sweeping it gives a
    /// ranking rule's detection threshold and the history it needs there.
    pub basket_drift: f64,
    /// THE MACRO PANEL: 1 emits nine observables DERIVED from the model's own state —
    /// macroSpread / macroSlope / macroCond / macroIvol / macroYield10 / macroCredit /
    /// macroPolicy / macroBankCredit / macroOutput, the counterparts of BAA10Y / T10Y2Y /
    /// NFCILEVERAGE / VIXCLS / DGS10 / TOTBKCR-over-GDP / DFF / TOTBKCR / GDP — see
    /// `derive_macro` and `macro_k`. Observational: reaches no price, draws from a dedicated
    /// stream read only when on, so 0 is bit-identical.
    pub macro_panel: usize,
    /// THE NULL PANEL: 1 takes the four columns from a SIBLING path (the same world at seed ^
    /// `macro_k::NULL_SEED`), so their marginals and persistence are this world's and their
    /// coupling to this path's price is nil — the no-edge comparison for a rule that reads
    /// them. The macro rows do not grade a null panel and the sidecar lists its columns as
    /// ungraded. Needs `macro_panel`; one extra price loop per path; 0 = the path's own panel,
    /// bit-identical.
    pub macro_null: usize,
    /// THE LEVERAGE CYCLE: declines that follow leverage. A borrowing stock swings over years as
    /// a credit cycle of its own (a damped oscillator on a dedicated stream, `macro_k::LEV_*`),
    /// is paid down under stress, and the spiral's gain is multiplied by 1 + lev_gain x (the
    /// stock's rise over its trailing-year average), so an ordinary shock cascades into a 20%
    /// decline where leverage has been building and not where it has not. The ratio the
    /// panel's conditions index reads is that stock over the equity securing it, raised by the
    /// drawdown. The record's target (`macro-*.tsv`, NFCILEVERAGE): a 20% peak within a quarter
    /// 2.0-2.7x as likely with the index in its top decile, ~1x for 10% dips; the index at rank
    /// 0.92-0.94 through the quarter before the peak. 0 = the amplification is bit-identical
    /// (the stock still runs for the panel).
    pub lev_gain: f64,
    /// THE AMPLIFIER STUDY (item 11): the spiral's excess gain scaled by (depth /
    /// DEPTH_REF)^stress_scale. 0 = the spiral's size proportional to the world's everyday move
    /// (the record's crash count is volatility-FLAT across a fresh-start cross-section, the
    /// model's rises at 1.4-1.8); 1 = a liquidity event of the same absolute size in every
    /// market. The reference world is unchanged at any value.
    /// THE PERSISTENT KICK (item 12): the leverage kick's own memory. At 0 the kick raises only
    /// the NEXT session's diffusive noise; at P it raises the following ones too, through an EWMA
    /// whose weights sum to 1 — the integrated response per decline is unchanged and only its
    /// SHAPE moves. SHIPPED AT 0 after the measurement: preserving the integral divides the
    /// per-lag amplitude, so at 0.95 the clustering hump closes (lag 5 within 0.017 of lag 1,
    /// against 0.048 today) and lag-1 clustering lands on the record's 0.298, but the lag-1
    /// leverage correlation falls -0.09 -> -0.04 against a -0.093 anchor and the downside excess
    /// with it. The record's response is a PLATEAU, not a spread integral. Must stay below 1;
    /// 0 = bit-identical.
    pub lev_persist: f64,
    /// the cascade's DECAY (item 14): `NOISE_ASYM_PHI` (0.96) is a 17-session half-life; higher
    /// carries the response to lag 20.  The Scala twin spells this default as the LITERAL 0.96,
    /// because there `Defaults` is constructed before that val is initialized; the two must stay
    /// equal, and the sidecar's `world` block is where a divergence would show.
    pub noise_asym_phi: f64,
    /// THE VOL RESPONSE (item 14): the diffusive noise multiplied by exp(vol_resp * S), S the
    /// session's decline in units of the conditional sd THAT GENERATED IT, accumulated at
    /// `vol_resp_phi`. Two things separate it from the item-12 forms: the denominator is the
    /// session's own sd rather than a trailing scale (so the state is scale-free instantly and
    /// cannot self-excite), and the accumulation is UNNORMALIZED, so one decline's response is a
    /// PLATEAU of height `vol_resp` decaying at `vol_resp_phi` — not an integral divided over the
    /// following sessions, which is what halved the per-lag amplitude in item 12. Draw-free:
    /// 0 is bit-identical.
    pub vol_resp: f64,
    pub vol_resp_phi: f64,
    /// the vol response state's ceiling
    pub vol_resp_cap: f64,
    /// the vol response's ATTACK, 0 = none: the standardized decline through a fast EWMA before it
    /// accumulates, so the response BUILDS over two to five sessions instead of peaking at lag 1.
    pub vol_resp_attack: f64,
    /// THE JUMP RESPONSE (item 14): the jump INTENSITY multiplied by exp(jump_resp * S), the same
    /// persistent decline state `vol_resp` reads. Consumes no extra draw.
    pub jump_resp: f64,
    /// the equity spiral's SCALE speed (item 14): the EWMA weight on ret^2 that standardizes the
    /// decline `stress_idx` reads. 0.005 is a ~140-session memory, so a stretch a persistent vol
    /// mechanism has genuinely made volatile reads as continuous STRESS and the spiral mints
    /// spikes out of it — the measured blocker on every form tried. Faster lets the spiral tell
    /// volatile from stressed.
    pub stress_adapt: f64,
    /// THE BUST SWING (item 25): a mania's unwind is two and a half years of legs and rallies at
    /// 40-60% vol (NDX 2000-02), where the model's was a crash and a calm underwater spell. When a
    /// 0.2-log drawdown opens under a peak that stood `BUST_ARM` or more over the gap's 20-year
    /// mean, a state s arms (full strength `BUST_RAMP` above the arm) and, while the unwind makes
    /// new lows, a months-long unit-variance swing is repriced the same session in the price and
    /// in perceived fair at amplitude bust_amp x s -- a STATIONARY swing about the falling centre,
    /// so no gap opens for the pull to close and the price does not race to its trough; the
    /// recovery drag is relieved and the amplifier reads declines in ordinary units by (1 + 2s),
    /// so the legs keep their ordinary cascade and the rallies exist. Sixteen forms of the bust's
    /// vol were measured before this one (PLAN item 25's ledger): every diffusive-noise form
    /// shortened and deepened the bust. Own stream; 0 = bit-identical; the S&P default keeps 0.
    pub bust_amp: f64,
    /// the cascade's CAP, 0 = uncapped: the largest log multiplier the cascade may apply.
    pub noise_asym_cap: f64,
    /// THE SLOW REPRICING CHANNEL (item 15): the share of the diffusive VARIANCE taken out of the
    /// order-flow channel, which reappears as a repricing that moves the fundamental and the price
    /// TOGETHER — like the news jump, so the value channel has nothing to arbitrage and the move
    /// never passes through `step` and its spiral. Its volatility has LONG memory the amplifier
    /// cannot steepen, which is what puts the |r| autocorrelation profile back on the record's
    /// shape. Own RNG stream; 0 = bit-identical.
    pub slow_share: f64,
    /// the channel's scale, as a multiple of the session's base diffusive scale at this depth.
    /// Not derived from `slow_share`: the order-flow channel reaches price multiplied by the
    /// spiral's gain and this one does not, so equal variance shares are not equal price shares.
    pub slow_vol: f64,
    /// the channel's OWN leverage effect, in units of its state's stationary sd — EGARCH-style on
    /// its own draw. It is the only thing driving the state: a symmetric component was measured
    /// and is strictly worse, because variance moved out of the amplifier then loses the leverage
    /// profile the amplifier was supplying.
    pub slow_lev: f64,
    /// the state's persistence. Scaled by sqrt(1 - phi^2) on input, so `slow_lev` stays in
    /// stationary units — unscaled, its variance runs 11x nominal and volatility reaches 200%.
    pub slow_phi: f64,
    /// the share of the repricing that reaches the FUNDAMENTAL. The rest opens a gap the value
    /// channel closes over its own horizon. At 1.0 the move is permanent and nothing arbitrages
    /// it, which the momentum crowd chases into a variance ratio: 1.0 reads 1.14 against the
    /// record's 1.00, 0.30 reads 1.09.
    pub slow_perm: f64,
    /// the BOND's loading on the same repricing, opposite sign — a flight-to-quality factor.
    /// Without it the channel is equity-only, and the worst equity days have no bond response at
    /// all: the tail hedge correlation reads -0.239 against a record of -0.270, and the bond's
    /// growth-shock rally 5.64 against 6.60.
    pub slow_beta: f64,
    /// THE ASYMMETRIC NOISE VOL (item 12): the diffusive noise multiplied by exp(g - Var(g)), g a
    /// CASCADE of the session's own diffusive DRAW: -z through a fast attack into a slow decay
    /// (`NOISE_ASYM_ATTACK`, `NOISE_ASYM_PHI`), so the response BUILDS over two to five sessions
    /// and persists for twenty, the shape the record's profiles show. The draw is a unit normal by
    /// construction, so the state cannot be inflated by the price it helps set — every
    /// price-standardized form of this self-excites, measured. Level-preserving. SHIPPED AT 0
    /// after the measurement: it is the one form that moves the profile the right way (the vol
    /// response at lag 5 reaches the record's -0.05..-0.08 from -0.042 at 0.05-0.10) but the
    /// model's clustering already peaks at lag 1 from the spiral, so the hump does not close, and
    /// every setting that holds the other rows pays 5-40 points of kurtosis or the downside
    /// excess. 0 = bit-identical.
    pub noise_asym: f64,
    pub stress_scale: f64,
    pub value_pull: f64,
    pub crowd: Crowd,
    pub crowd_impact: f64,
    pub panic: f64,
    /// bond duration: sensitivity of its fair value to the rate
    pub duration: f64,
    /// CAP on policy accommodation under equity stress, in rate points
    pub easing: f64,
    /// how fast that accommodation is withdrawn, per year
    pub unwind: f64,
    /// flight-to-quality bid into the bond, per unit of equity stress
    pub refuge: f64,
    pub infl_prob: f64,
    pub infl_size: f64,
    pub infl_speed: f64,
    pub rate_speed: f64,
    /// equity fair-value markdown per pp of rate above its long-run mean
    pub discount: f64,
    /// joint-stress forced selling pressure on the bond
    pub margin: f64,
}

#[derive(Clone, Debug)]
pub struct Path {
    pub price: Vec<f64>,
    pub rate: Vec<f64>,
    pub fundamental: Vec<f64>,
    /// per-session slippage multiplier (equity market)
    pub liq: Vec<f64>,
    /// the same, for the BOND market: an arm that trades the bond is charged its own
    /// market's slippage, not the equity book's
    pub bliq: Vec<f64>,
    /// flight-to-safety asset price (its own Market)
    pub bond: Vec<f64>,
    /// inflation pressure, for regime classification
    pub infl_press: Vec<f64>,
    /// realized price level, deterministic from pressure
    pub cpi: Vec<f64>,
    /// BINDING diagnostic for the population knob
    pub mean_trend_share: f64,
    /// share of sessions on the numerical guard rails
    pub trend_pinned: f64,
    /// share of sessions the choice target saturated
    pub target_sat: f64,
    /// both markets, post-burn-in
    pub clamped_days: usize,
    /// EQUITY sessions held off the downward guard, post-burn-in, and the sessions past `TAIL_REF`
    /// that are their denominator. The equity leg alone because that is the series a tail consumer
    /// reads. `eq_halt_days` is the BINDING diagnostic for the trading halt.
    pub eq_floor_days: usize,
    pub eq_tail_days: usize,
    pub eq_halt_days: usize,
    /// BINDING diagnostic for the bond spiral
    pub mean_bond_stress: f64,
    /// share of sessions bond stress index > 0.5
    pub pct_bond_stress: f64,
    /// BINDING diagnostic for the reflexive channel: mean |crowd flow| per session, post burn-in.
    /// Its ABSENCE is why -crowdimpact sat dead in the default world across four releases.
    /// the world's bond duration, carried so the gate can judge bond volatility RELATIVE to it;
    /// a fixed absolute band can only ever fit one bond
    pub duration: f64,
    pub mean_crowd_flow: f64,
    /// BINDING diagnostic for the disaster channel: collapses begun post burn-in on this path.
    pub disasters: usize,
    /// satellite equity leg price (empty when `sat_beta` is 0)
    pub sat: Vec<f64>,
    /// intra-bar LOG high/low (empty when `range_scale` is 0). Log, not a level, unlike
    /// `price`/`sat`: these are born in log space and emitted in log space, and the level
    /// round-trip would only add transcendental noise (PARITY.md §6).
    pub log_hi: Vec<f64>,
    pub log_lo: Vec<f64>,
    /// log turnover index (empty when `vol_idio` is 0); mean-free by construction, the
    /// consumer's detrend convention applies unchanged
    pub log_volume: Vec<f64>,
    /// session dividend yield, %/yr, and the traded price LEVEL (both empty when `div_yield` is
    /// 0); the level is emitted as a log, like `sat`
    pub div_yield: Vec<f64>,
    pub traded: Vec<f64>,
    /// the bar's open, log (empty when `overnight` is 0)
    pub log_open: Vec<f64>,
    /// the basket's names, LOG prices (empty when `basket` is 0)
    pub names: Vec<Vec<f64>>,
    /// the world's channel level the bars and the satellite were sampled at (`world_level`),
    /// carried into the sidecar so the emitted data's scale is auditable; 0 / 0 when both
    /// channels are off
    pub chan_k: f64,
    pub chan_k_sat: f64,
    /// the world's mean fundamental/price the dividend yield was normalized by; 0 when off
    pub chan_k_div: f64,
    /// the basket idio's level (realized sd over the vol state's rms); 0 when no channel ran
    pub chan_k_vs: f64,
    /// the implied-vol member's level (`ChannelLevel::k_iv`); 0 when no channel ran
    pub chan_k_iv: f64,
    /// the primary's realized annualized vol, what `basket_drift` is a fraction of; 0 when off
    pub chan_k_dr: f64,
    /// the macro panel (None when `macro_panel` is 0), in its counterparts' units
    pub macro_panel: Option<MacroPanel>,
}

/// ONE price-formation mechanism for every traded asset: value demand toward `fair`, plus
/// external flow and noise, amplified when THIS market's liquidity has withdrawn after
/// one-sided selling (measured against a slowly-adapting scale, so symmetric turbulence of
/// any size leaves the index flat — E[max(0,-z)] = 0.399 regardless of scale).
/// Drawdown at which recovery drag reaches its stated strength. 0.10 keeps it inert in ordinary
/// sessions, so it shapes recoveries from real drawdowns and nothing else.
const DRAWDOWN_REF: f64 = 0.10;
/// Bound on the growth-capitalization term, in log units: perceived fair may ride at most this
/// far from the fundamental on extrapolated growth alone (tanh-squashed). 0.80 log is a 2.2x
/// valuation, past the record's worst manias. FROZEN: a guard on the term's DOMAIN, not a tuning
/// surface.
const CAP_SPAN: f64 = 0.80;
/// THE BUST SWING's constants (see `bust_amp`), frozen like `CAP_SPAN`: the gap's slow mean, the
/// peak level over it that arms the state, the level range over which the state ramps to full
/// strength, the swing's per-session persistence (a 50-session memory), the state's decay while
/// the unwind makes new lows and after a year without one (as per-session factors, written as
/// literals so both twins carry the same bits), and the drag relief / amplifier blindness at
/// full strength (1 + BUST_RELIEF x s).
const BUST_MEAN_YEARS: f64 = 20.0;
const BUST_ARM: f64 = 0.5;
const BUST_RAMP: f64 = 0.15;
const BUST_PHI: f64 = 0.98;
const BUST_DECAY_NEAR: f64 = 0.999_083_558_838_992_4;
const BUST_DECAY_AFTER: f64 = 0.994_513_935_616_828_5;
const BUST_RELIEF: f64 = 2.0;

/// DETERMINISTIC exp: Cody-Waite range reduction with fdlibm's split ln2, a fixed Horner Taylor
/// to r^12 on the reduced argument, and 2^k built from raw exponent bits. Every operation is
/// IEEE-exact-or-fixed, so the twins agree TO THE BIT by construction — which no native libm call
/// guarantees: the momentum crowd's tanh diverged from the JVM's by one ulp on a cycle-world
/// input after four releases of input luck, and rebuilding tanh from the NATIVE exp only moved
/// the divergence into exp's own wide-argument ulps (both measured 2026-08-30, the PARITY.md
/// `log` class). Accuracy ~2 ulp, which a behavioural squash cannot see; |y| is bounded by
/// `tanh_p`'s cutoff so the 2^k construction stays in range. Use it for any future transcendental
/// that must match across the twins.
fn exp_det(y: f64) -> f64 {
    // fdlibm's split ln2, as BIT PATTERNS so the twins' constants are identical by inspection.
    const LN2_HI: f64 = f64::from_bits(0x3FE6_2E42_FEE0_0000);
    const LN2_LO: f64 = f64::from_bits(0x3DEA_39EF_3579_3C76);
    // floor(x + 0.5), written out: Java's round and Rust's differ on negative halves.
    let k = (y * std::f64::consts::LOG2_E + 0.5).floor() as i64;
    let r = (y - k as f64 * LN2_HI) - k as f64 * LN2_LO;
    // Taylor e^r to r^12 in fixed Horner order; |r| <= 0.3466 puts truncation near 3e-15.
    let mut p = 1.0 / 479_001_600.0;
    p = p * r + 1.0 / 39_916_800.0;
    p = p * r + 1.0 / 3_628_800.0;
    p = p * r + 1.0 / 362_880.0;
    p = p * r + 1.0 / 40_320.0;
    p = p * r + 1.0 / 5_040.0;
    p = p * r + 1.0 / 720.0;
    p = p * r + 1.0 / 120.0;
    p = p * r + 1.0 / 24.0;
    p = p * r + 1.0 / 6.0;
    p = p * r + 0.5;
    p = p * r + 1.0;
    p = p * r + 1.0;
    p * f64::from_bits(((k + 1023) as u64) << 52)
}

/// tanh from `exp_det` via (e^2x - 1)/(e^2x + 1), so the twins agree to the bit; past +-20 the
/// guard returns the sign exactly (1 - tanh(20) ~ 8e-18, below one ulp of 1.0). Both squash sites
/// use it — the cap term and the momentum crowd's `trend_pos`.
fn tanh_p(x: f64) -> f64 {
    if x > 20.0 {
        1.0
    } else if x < -20.0 {
        -1.0
    } else {
        let e2 = exp_det(2.0 * x);
        (e2 - 1.0) / (e2 + 1.0)
    }
}
/// Admissible sd of log(price/fair): the record's CAPE-proxy windows read 0.24-0.41, the floor
/// carries the stated proxy haircut, and the ceiling is past the century with room. See the
/// `valuation dispersion` gate row and valuation-2026-08-30.tsv.
const VAL_DISP_BAND: (f64, f64) = (0.15, 0.55);

/// What counts as the DEEP tail for the guard's own accounting: a session losing more than 0.20 in
/// log terms, about -18% simple. The real record holds roughly one such session per century, so
/// this is the region where a consumer reading worst-case behaviour is reading a handful of events
/// -- and where a guard that binds at all determines what the worst one WAS.
///
/// Cut at 0.10 first and the statistic read 1.1-1.4% in every world tried, against a guard that was
/// authoring every one of the ten worst sessions: the shallower threshold buries the signal in two
/// orders of magnitude of ordinary bad days, and a band drawn there cannot fail.
const TAIL_REF: f64 = 0.20;

struct Market {
    k_value: f64,
    stress_k: f64,
    impact: f64,
    /// the EWMA weight on ret^2 in `scale_var`, the denominator `stress_idx` standardizes by; set
    /// by the loop from `stress_adapt` for the equity market
    scale_mu: f64,
    recovery_drag: f64,
    recovery_floor: f64,
    /// Trading halt: the largest ONE-session decline this market prints, as a simple fraction,
    /// pre-converted to the log floor it implies. `NEG_INFINITY` disables the mechanism.
    floor_log: f64,
    /// Pressure a halted session could not fill, deferred to the next one. A halt DEFERS, it does
    /// not cancel -- that is the whole difference from the numerical guard, and it is why the tail
    /// comes out as a multi-session cascade rather than one impossible day.
    carry: f64,
    halt_days: usize,
    log_p: f64,
    peak: f64,
    stress_idx: f64,
    /// THE LEVERAGE CYCLE's multiplier on the spiral's gain, set by the loop each session from
    /// the leverage stock (`lev_gain`); exactly 1.0 with the dial off, so the amplification is
    /// bit-identical there.
    lev_mult: f64,
    /// THE AMPLIFIER STUDY's gain scale (item 11), exactly inert at 1: the spiral's excess gain
    /// scaled by (depth / DEPTH_REF)^stress_scale, set by the loop, so a thinner market's
    /// liquidity event is not proportionally larger than the reference world's.
    gain_mult: f64,
    /// THE BUST SWING's two per-session hooks (see `bust_amp`), set by the loop and exactly 1.0
    /// otherwise: the recovery drag is divided by `drag_mult`'s reciprocal, and the amplifier's
    /// stress reads declines divided by `stress_div`.
    pub drag_mult: f64,
    pub stress_div: f64,
    last_liq: f64,
    clamps: usize,
    /// Sessions on the DOWNWARD guard, and sessions in the tail at all. Counted separately from
    /// `clamps` because the question the gate has to answer is not how often the guard binds -- it
    /// binds on almost nothing -- but what share of the extreme tail it SHAPES.
    floor_days: usize,
    tail_days: usize,
    scale_var: f64,
    /// The SPIRAL's own scale, at `scale_mu`, and the stress index built from it. Separate from
    /// `scale_var`/`stress_idx` on purpose: `stress_adapt` is the amplifier's dial, and the index
    /// the rest of the world reads — policy easing, the refuge bid, joint-stress margin selling,
    /// the leverage stock's paydown, the macro panel's spread and conditions members — was
    /// calibrated against the SLOW one. Letting the dial move both shrank the equity stress the
    /// bond's crisis behaviour reads: measured, the growth-shock rally fell 6.7 -> 4.2 against a
    /// record of 6.6 and `-crossasset` failed its short-duration rung. At `scale_mu` 0.005 the two
    /// are identical, so the dial is bit-identical off.
    scale_var_amp: f64,
    stress_amp: f64,
}

/// The depth the spiral's gain was calibrated at, the reference `stress_scale` scales from — a
/// literal, not the default's depth: a later depth move must not silently rescale the law.
const DEPTH_REF: f64 = 17.4;
/// THE ASYMMETRIC NOISE VOL's two timescales (item 12; `noise_asym` sizes the response, these
/// shape it). The record's vol after a decline BUILDS over two to five sessions and stays
/// elevated for about twenty — its |r| autocorrelation humps at lags 2-5 and its leverage-effect
/// profile still reads -0.08 at lag 5 — so the driver is a cascade: the session's draw through a
/// fast attack (`ATTACK`, ~3 sessions) into a slow decay (`PHI`, a 17-session half-life). One
/// exponential alone peaks at lag 1 and makes no hump, measured.
const NOISE_ASYM_PHI: f64 = 0.96;
const NOISE_ASYM_ATTACK: f64 = 0.50;

/// Var(g) in closed form, for the level-preserving centring: the cascade's impulse response is
/// T(1-A)(A^{k+1} - phi^{k+1})/(A - phi), and this is the sum of its squares.
fn noise_asym_var(t: f64, ph: f64) -> f64 {
    if t <= 0.0 {
        return 0.0;
    }
    let a = NOISE_ASYM_ATTACK;
    let k = t * (1.0 - a) / (a - ph);
    k * k * (a * a / (1.0 - a * a) - 2.0 * a * ph / (1.0 - a * ph) + ph * ph / (1.0 - ph * ph))
}

impl Market {
    fn new(k_value: f64, stress_k: f64, impact: f64) -> Self {
        Self::with_recovery(k_value, stress_k, impact, 0.0, 1.0, 0.0)
    }

    fn with_recovery(
        k_value: f64,
        stress_k: f64,
        impact: f64,
        recovery_drag: f64,
        recovery_floor: f64,
        halt_limit: f64,
    ) -> Self {
        Self {
            k_value,
            stress_k,
            impact,
            scale_mu: 0.005,
            recovery_drag,
            recovery_floor,
            floor_log: if halt_limit <= 0.0 {
                f64::NEG_INFINITY
            } else {
                (1.0 - halt_limit).ln()
            },
            carry: 0.0,
            halt_days: 0,
            log_p: 0.0,
            peak: 0.0,
            stress_idx: 0.0,
            lev_mult: 1.0,
            gain_mult: 1.0,
            drag_mult: 1.0,
            stress_div: 1.0,
            last_liq: impact,
            clamps: 0,
            floor_days: 0,
            tail_days: 0,
            scale_var: 0.01 * 0.01,
            scale_var_amp: 0.01 * 0.01,
            stress_amp: 0.0,
        }
    }

    fn step(&mut self, fair: f64, flow_plus_noise: f64) -> f64 {
        let scale = self.scale_var.sqrt();
        let scale_a = self.scale_var_amp.sqrt();
        let amp = 1.0 + self.stress_k * self.stress_amp * self.lev_mult * self.gain_mult;
        self.last_liq = amp * self.impact;
        // amplification applies to FLOW AND NOISE, not to the value-arbitrage pull: thin
        // liquidity makes any ORDER move price further, but amplifying the arbitrage itself
        // sets a feedback gain of kValue*amp, which for a fast-tracking market (bond,
        // kValue 0.7) exceeded 1 and OSCILLATED — 86% bond volatility from the market
        // fighting its own fair value.
        // ASYMMETRIC RECOVERY. Value arbitrage is WEAKER, not stronger, when the market is far
        // below its own peak: the capital that closes a gap is most depleted exactly when the gap
        // is largest. One-sided — it touches the pull only while it points UP and only past
        // `DRAWDOWN_REF` — so declines are unaffected and recoveries grind.
        //
        // What it fixes, measured: the model spends HALF the time below 15% that the real record
        // does (d15 0.115 against SPY's 0.240) while crossing 15% 40% MORE often, so each excursion
        // lasts a third as long (0.395 against 1.148). Median fall-to-rise ratio reads 1.02 here
        // against 1.44 for SPY and 1.28 for QQQ.
        //
        // `recovery_floor` is the residual arbitrage that is always present: unbounded, the pull
        // falls to a seventeenth of strength at a 30% drawdown, which is capital switched off
        // rather than depleted, and the deepest drawdowns run away. Both defaults reproduce the
        // symmetric pull of every earlier release BIT-IDENTICALLY — the multiplier is exactly 1.0.
        let gap = fair - self.log_p;
        let drop = self.peak - self.log_p;
        let damp = if self.recovery_drag <= 0.0 || gap <= 0.0 || drop <= DRAWDOWN_REF {
            1.0
        } else {
            self.recovery_floor.max(
                1.0 / (1.0
                    + self.recovery_drag * self.drag_mult * (drop - DRAWDOWN_REF) / DRAWDOWN_REF),
            )
        };
        let raw = (self.k_value * gap * damp + flow_plus_noise * amp) * self.impact;
        // Numerical guard ONLY, and verified to be exactly that: at ±0.25 vs ±0.50 every
        // statistic in every gate-passing world is BIT-IDENTICAL (the clamp consumes no
        // draws and never binds there). It sits at ±0.50, far from any plausible daily move
        // (worst real S&P day ~ -23% log), and the gate rejects any world where it engages.
        // Deferred pressure from a halted session arrives here, ahead of this session's own bound.
        let raw_c = raw + self.carry;
        let halted = raw_c < self.floor_log;
        if halted {
            self.halt_days += 1;
            self.carry = raw_c - self.floor_log;
        } else {
            self.carry = 0.0;
        }
        let bound = if halted { self.floor_log } else { raw_c };
        let ret = (-0.50f64).max(0.50f64.min(bound));
        if ret != bound {
            self.clamps += 1;
            if bound < 0.0 {
                self.floor_days += 1;
            }
        }
        if ret < -TAIL_REF {
            self.tail_days += 1;
        }
        self.log_p += ret;
        if self.log_p > self.peak {
            self.peak = self.log_p;
        }
        self.scale_var = 0.995 * self.scale_var + 0.005 * ret * ret;
        self.stress_idx =
            0.0f64.max(0.96 * self.stress_idx + 0.04 * (0.0f64.max(-ret) / scale - 0.399));
        self.scale_var_amp = (1.0 - self.scale_mu) * self.scale_var_amp
            + self.scale_mu * (ret / self.stress_div) * (ret / self.stress_div);
        self.stress_amp = 0.0f64.max(
            0.96 * self.stress_amp + 0.04 * (0.0f64.max(-ret / self.stress_div) / scale_a - 0.399),
        );
        ret
    }
}

/// Per-session inputs the derived channels read, recorded by `simulate`'s price loop.
struct ChannelInputs {
    /// observed log price — markdown and news included, the return a consumer measures
    px: Vec<f64>,
    /// the session's diffusion sd as the price received it: `sess_sigma` x spiral amplification
    d: Vec<f64>,
    /// the satellite's state factor: vol state x spiral amplification over the base impact
    state: Vec<f64>,
    /// the session's jump-channel move as the market delivered it (x its liquidity) plus the news
    /// repricing; 0 on a jump-free session
    jump: Vec<f64>,
    /// exp(log_vol - vol_norm): the vol state WITHOUT the spiral, the basket idio's driver
    vol_state: Vec<f64>,
    /// `Market::scale_var` after the step — the volume down-term's realized scale, read one
    /// session fresher than the leverage signal's (stated, and mirrored)
    scale_var: Vec<f64>,
    /// the spiral's amplification this session, `last_liq` over the base impact — what the
    /// implied-vol member prices a share of
    amp: Vec<f64>,
}

impl ChannelInputs {
    /// This path's contribution to the world's level: sums of the observed squared return, the
    /// session diffusion sd and the squared satellite state factor from the second session (the
    /// first has no return), plus the count — in session order, which is part of the
    /// cross-language contract.
    fn level_sums(&self) -> (f64, f64, f64, f64, f64) {
        let tot = self.px.len();
        let mut s_r2 = 0.0f64;
        let mut s_d = 0.0f64;
        let mut s_st = 0.0f64;
        let mut s_vs = 0.0f64;
        for i in 1..tot {
            let r = self.px[i] - self.px[i - 1];
            s_r2 += r * r;
            s_d += self.d[i];
            s_st += self.state[i] * self.state[i];
            s_vs += self.vol_state[i] * self.vol_state[i];
        }
        (s_r2, s_d, s_st, s_vs, (tot - 1) as f64)
    }

    /// This path's contribution to the implied-vol level: the sums of log(vol state x the
    /// forward-looking amplification) and of log forward-21-session realized vol, over the
    /// post-burn-in sessions that have one, and their count — in session order. The same window
    /// and the same factor the premium reads, so the level is taken in the premium's own
    /// statistic: a root-mean-square level put the premium 0.2 high on a thin market, whose
    /// forward realized vol is dominated by rare stretches.
    fn ivol_level_sums(&self) -> (f64, f64, f64) {
        let n = self.px.len();
        let mut r2 = vec![0.0f64; n];
        for i in 1..n {
            let d = self.px[i] - self.px[i - 1];
            r2[i] = r2[i - 1] + d * d;
        }
        let h = 21usize;
        let mut s_liv = 0.0f64;
        let mut s_lrv = 0.0f64;
        let mut c = 0.0f64;
        for i in BURN_IN..n {
            if i + h < n {
                let rv = 100.0 * (DAYS_PER_YEAR as f64 * (r2[i + h] - r2[i]) / h as f64).sqrt();
                let f = self.vol_state[i] * (1.0 + macro_k::IVOL_AMP_SHARE * (self.amp[i] - 1.0));
                if rv > 0.0 && f > 0.0 {
                    s_liv += f.ln();
                    s_lrv += rv.ln();
                    c += 1.0;
                }
            }
        }
        (s_liv, s_lrv, c)
    }
}

/// The world's channel level: `k` re-levels the session diffusion sd onto the world's realized
/// close-to-close sd, `k_sat` the satellite's state factor onto it in root-mean-square.
#[derive(Clone, Copy, Debug)]
struct ChannelLevel {
    k: f64,
    k_sat: f64,
    /// the world's mean fundamental/price the dividend yield is normalized by; 0 when off
    k_div: f64,
    /// the basket idio's level: realized sd over the vol state's rms, so a fraction dial is a
    /// fraction of the primary's realized vol whatever shape the state takes; 0 when no channel
    k_vs: f64,
    /// the primary's realized ANNUALIZED volatility — what `basket_drift` is a fraction of, so the
    /// dial transports; 0 when no channel ran
    k_dr: f64,
    /// the implied-vol member's level: exp of the mean over sessions of log forward-21-session
    /// realized vol minus log(vol state x forward-looking amplification), in annualized %, so
    /// the member's log premium over realized is the record's by construction in every world;
    /// 0 when no channel ran
    k_iv: f64,
}

/// The fixed ensemble the level is solved on. Small on purpose: the level is a mean over ~200k
/// sessions, so its sampling error is under 1% even at kurtosis 60, and it is solved once per
/// `sim_paths` call.
const LEVEL_PATHS: usize = 8;
const LEVEL_YEARS: usize = 100;
const LEVEL_SEED: u64 = 0x1e7e_1000;

/// THE WORLD'S CHANNEL LEVEL, pooled over `LEVEL_PATHS` x `LEVEL_YEARS` at `LEVEL_SEED` — a
/// function of the world alone, so path k of (world, seed) stays reproducible from its sidecar
/// and every path of a world is sampled at one scale. Sums run in path order then session order
/// in both twins. 0 / 0 when both channels are off — never read.
/// One level-ensemble path's sums: the channel inputs' three sums and count, then the
/// fundamental/price sum and count.
type LevelSums = ((f64, f64, f64, f64, f64), (f64, f64, f64), (f64, f64));

fn world_level(w: &World) -> ChannelLevel {
    let ch_on = w.range_scale > 0.0
        || w.sat_beta > 0.0
        || w.overnight > 0.0
        || w.basket > 0
        || w.macro_panel > 0; // the implied-vol member reads `k_vs`
    let div_on = w.div_yield > 0.0;
    if !(ch_on || div_on) {
        return ChannelLevel {
            k: 0.0,
            k_sat: 0.0,
            k_div: 0.0,
            k_vs: 0.0,
            k_dr: 0.0,
            k_iv: 0.0,
        };
    }
    let sums: Vec<LevelSums> = (0..LEVEL_PATHS)
        .into_par_iter()
        .map(|k| {
            let pr = price_loop(w, LEVEL_YEARS, LEVEL_SEED.wrapping_add(k as u64 * 7919));
            // the channel inputs are recorded only when a channel ran; the dividend level needs
            // none of them
            let ch = if ch_on {
                pr.inputs.level_sums()
            } else {
                (0.0, 0.0, 0.0, 0.0, 0.0)
            };
            let iv = if ch_on {
                pr.inputs.ivol_level_sums()
            } else {
                (0.0, 0.0, 0.0)
            };
            (ch, iv, fair_over_price_sum(&pr.path))
        })
        .collect();
    let mut s_r2 = 0.0f64;
    let mut s_d = 0.0f64;
    let mut s_st = 0.0f64;
    let mut s_vs = 0.0f64;
    let mut m = 0.0f64;
    let mut s_fp = 0.0f64;
    let mut n_fp = 0.0f64;
    let mut s_liv = 0.0f64;
    let mut s_lrv = 0.0f64;
    let mut n_iv = 0.0f64;
    for ((a, b, c, vs, n), (liv, lrv, niv), (fp, nf)) in sums {
        s_r2 += a;
        s_d += b;
        s_st += c;
        s_vs += vs;
        m += n;
        s_liv += liv;
        s_lrv += lrv;
        n_iv += niv;
        s_fp += fp;
        n_fp += nf;
    }
    ChannelLevel {
        k: if ch_on {
            (s_r2 / m).sqrt() / (s_d / m)
        } else {
            0.0
        },
        k_sat: if ch_on { (s_r2 / s_st).sqrt() } else { 0.0 },
        k_vs: if ch_on { (s_r2 / s_vs).sqrt() } else { 0.0 },
        // the drift dispersion's level: the primary's realized ANNUALIZED vol
        k_dr: if ch_on {
            (s_r2 / m).sqrt() * (DAYS_PER_YEAR as f64).sqrt()
        } else {
            0.0
        },
        k_div: if div_on { s_fp / n_fp } else { 0.0 },
        // `exp_det`, as the OU factors use: the twins' libm exps differ in the last bit
        k_iv: if ch_on && n_iv > 0.0 {
            exp_det((s_lrv - s_liv) / n_iv)
        } else {
            0.0
        },
    }
}

/// The dividend level's input: one path's sum of fundamental/price over its sessions, in
/// session order (part of the cross-language contract), and the count. A world constant for the
/// same reason the bar level is: read off the path being emitted, a mean over the whole path
/// leaks its future into every session's yield.
fn fair_over_price_sum(p: &Path) -> (f64, f64) {
    let mut s_fp = 0.0f64;
    for i in 0..p.price.len() {
        s_fp += p.fundamental[i] / p.price[i];
    }
    (s_fp, p.price.len() as f64)
}

/// One path's derived channels: the satellite leg's price and the sampled bars.
struct Channels {
    sat: Vec<f64>,
    log_hi: Vec<f64>,
    log_lo: Vec<f64>,
    log_volume: Vec<f64>,
    log_open: Vec<f64>,
    /// the basket's log prices, N of them
    names: Vec<Vec<f64>>,
}

/// THE DERIVED CHANNELS, sampled in a second pass from the price loop's recorded inputs. They
/// are OBSERVATIONAL — nothing here reaches a price — which is what licenses the second pass,
/// and the second pass is what licenses the level. Each channel rides the session's own state
/// (the diffusion sd as the price received it; the satellite's vol-state x spiral factor)
/// re-levelled onto the WORLD's realized close-to-close volatility — `world_level`, k =
/// realized sd / mean diffusion sd, solved once per world from a fixed ensemble and shared by
/// every path. The level is a constant of the world, and no estimator read off the path itself
/// is clean. A causal one is noisy, biased or both: an EWMA saturated at four sds (so that one
/// 10-sd session cannot lift it 50% for months) has a TRUNCATED variance as its fixed point,
/// and the truncated share is a property of the tail — 0.92 at kurtosis 13, 0.56 at 96;
/// unsaturated, slow or cumulative, it re-learns the level from every extreme session and mints
/// idio kurtosis 31 / 23 against the record's 17; frozen at end of burn-in it spans 0.60-2.08 of
/// the path's own sd. And the path's whole-path variance LEAKS: a constant says nothing about
/// when, but it carries the whole path's level, so years 1-10 of a bar series predicted the log
/// volatility of years 11-100 at +0.81 against +0.06 in a channel-free control, and pinned the
/// graded range/ccvol row by its own definition (cross-path sd 0.016 against 0.061). Both are
/// the consumer's measurements. A world constant carries nothing a path could not already know.
/// This is what makes `range_scale` a bar-to-realized-vol ratio and `sat_idio` an
/// idio-to-primary-vol fraction that hold across worlds: on the diffusion scale alone the same
/// range dial read bar/ccvol 1.115 at the default and 1.264 at the Nasdaq recipe, because the
/// share of variance the diffusion carries is a property of the world. The satellite's state
/// factor is the vol state times the spiral's amplification over the base impact, WITHOUT the
/// leverage kick and news damp the range carries (on those the leg's kurtosis outran its
/// primary's, ratio 1.2-1.4 against the record's 0.55-1.12), re-levelled by its own root mean
/// square so the idio's realized sd is `sat_idio` times the primary's whatever shape the state
/// takes — the correlation then transports by construction. Each channel reads its own
/// dedicated stream — constructed from the path's seed, read only when the channel is on, so 0
/// is bit-identical off; the range takes two uniforms per session, max then min, the volume two
/// normals, slow innovation then white, and that draw ORDER is part of the cross-language
/// contract. The first session's return-from-zero is absorbed by burn-in as before.
/// The derived channels' streams, one per channel, each constructed from the path's seed and
/// read only when its channel is on — so a channel's presence never moves another's draws.
struct ChannelRngs {
    /// satellite
    s: NumPyRng,
    /// range (two uniforms per session, max then min)
    r: NumPyRng,
    /// volume (two normals per session, slow innovation then white)
    v: NumPyRng,
    /// the open (one normal per session)
    o: NumPyRng,
    /// the basket
    b: NumPyRng,
    /// the basket's drift dispersion (N draws once per path, before the session loop)
    m: NumPyRng,
}

impl ChannelRngs {
    fn new(seed: u64) -> Self {
        Self {
            s: NumPyRng::new(seed ^ 0x5a7e_1117u64),
            r: NumPyRng::new(seed ^ 0xca9d_1e00u64),
            v: NumPyRng::new(seed ^ 0xd011_a5e5u64),
            o: NumPyRng::new(seed ^ 0x09e7_a11eu64),
            b: NumPyRng::new(seed ^ 0xba5c_e700u64),
            m: NumPyRng::new(seed ^ 0xd1f7_5eadu64),
        }
    }
}

/// The bar channels' state: the bar's open (the prior close, or the sampled open when
/// `overnight` > 0), kept independent of the satellite's tracker on purpose (the channels must
/// not couple through bookkeeping), and the volume channel's state.
struct BarState {
    prev_px: f64,
    vol: VolState,
}

/// The bar channels' outputs for one path: the open, the extremes, the volume — each empty
/// when its channel is off.
struct BarOut<'a> {
    op: &'a mut [f64],
    hi: &'a mut [f64],
    lo: &'a mut [f64],
    vv: &'a mut [f64],
}

/// One session of the bar channels: the open (see the `overnight` field — the bridge point at
/// share w of the diffusive variance, jumps overnight whole, one normal from the open's stream),
/// then the bar from it (see the `range_scale` field: the intraday bridge over the remaining
/// (1-w) of the variance, x 1.0 exactly when the open is off), then the volume riding the range.
fn bar_session(
    w: &World,
    x: &ChannelInputs,
    at: (usize, f64),
    st: &mut BarState,
    out: BarOut<'_>,
    rngs: &mut ChannelRngs,
) {
    let (i, k) = at;
    let log_px = x.px[i];
    let open_on = w.overnight > 0.0;
    let range_on = w.range_scale > 0.0;
    let r_c = log_px - st.prev_px;
    let open_px = if open_on {
        let o = overnight_move(w, x.jump[i], r_c, x.d[i] * k, rngs.o.randn());
        // Clamped, the open IS the close: assign it exactly. `prev_px + r_c` leaves a rounding
        // residual of a few ulps whose sign would pick the range coupling's branch, and the
        // twins' log prices differ at that level.
        out.op[i] = if o == r_c { log_px } else { st.prev_px + o };
        out.op[i]
    } else {
        st.prev_px
    };
    if range_on {
        let r_s = log_px - open_px;
        let intraday = if open_on {
            (1.0 - w.overnight).sqrt()
        } else {
            1.0
        };
        let sig = (x.d[i] * k) * intraday * w.range_scale;
        let (h, l) = bridge_extremes(w, r_s, sig, rngs.r.next_f64(), rngs.r.next_f64());
        out.hi[i] = open_px + h;
        out.lo[i] = open_px + l;
        if w.vol_idio > 0.0 {
            out.vv[i] = st
                .vol
                .step(out.hi[i] - out.lo[i], r_s, x.scale_var[i], &mut rngs.v);
        }
    }
    st.prev_px = log_px;
}

/// The volume channel's state — see the `vol_idio` field: the slow AR component, the EWMA of
/// ln(range) that defines the range's "normal" (half-life 126 sessions, the grading convention's
/// rolling-median window, centred), and the previous session's range deviation the lag term reads.
struct VolState {
    slow: f64,
    rx_prev: f64,
    ewma: f64,
    ewma_set: bool,
    ewma_mu: f64,
    slow_innov: f64,
    white_sd: f64,
}

impl VolState {
    fn new(w: &World) -> Self {
        Self {
            slow: 0.0,
            rx_prev: 0.0,
            ewma: 0.0,
            ewma_set: false,
            ewma_mu: 1.0 - (-(2.0f64.ln()) / 126.0).exp(),
            slow_innov: w.vol_idio * VOL_SLOW_SHARE.sqrt() * (1.0 - VOL_PHI * VOL_PHI).sqrt(),
            white_sd: w.vol_idio * (1.0 - VOL_SLOW_SHARE).sqrt(),
        }
    }

    /// One session: elasticity VOL_SLOPE to the range's log-deviation from its slow normal, a
    /// down-day term shaped like the stress innovation (VOL_DOWN calibrated to the record's
    /// RESIDUAL +0.036 — most of the raw +0.12 flows THROUGH the range), plus the two-component
    /// idio. Reads `vrng` only, two normals per session: the slow innovation, then the white.
    fn step(&mut self, range: f64, r_s: f64, scale_var: f64, vrng: &mut NumPyRng) -> f64 {
        let lnx = range.max(1e-300).ln();
        if !self.ewma_set {
            self.ewma = lnx;
            self.ewma_set = true;
        }
        let rx = lnx - self.ewma;
        self.ewma += self.ewma_mu * (lnx - self.ewma);
        let down = 0.0f64.max(-r_s) / scale_var.sqrt();
        self.slow = VOL_PHI * self.slow + self.slow_innov * vrng.randn();
        let v = VOL_SLOPE * rx
            + VOL_LAG * self.rx_prev
            + VOL_DOWN * down
            + self.slow
            + self.white_sd * vrng.randn();
        self.rx_prev = rx;
        v
    }
}

/// The overnight part of a session's move — see the `overnight` field: the session's jump
/// whole, plus the bridge point at share w of the non-jump move with its conditional noise `z`;
/// the whole move when the gap overshoots the session on its own side.
fn overnight_move(w: &World, j: f64, r_c: f64, s0: f64, z: f64) -> f64 {
    let n = r_c - j;
    let b = (w.overnight * (1.0 - w.overnight)).sqrt() * s0;
    let o0 = j + w.overnight * n + b * z;
    if (r_c < 0.0 && o0 < r_c) || (r_c > 0.0 && o0 > r_c) {
        r_c
    } else {
        o0
    }
}

/// The bar's extremes relative to its open: the sign coupling on the bridge sigma (see the
/// `range_down` field — applied BEFORE the draws, consuming nothing; the near-reciprocal pair
/// leaves the mean breadth at ~(1 + x^2/2), which `range_scale` absorbs), then the exact
/// one-sided extremes of a Brownian bridge over the session's move `r_s`, the max from `u1` then
/// the min from `u2`, the uniforms floored at 1e-300 so a zero draw cannot mint an infinite bar.
fn bridge_extremes(w: &World, r_s: f64, sig0: f64, u1: f64, u2: f64) -> (f64, f64) {
    let sig = if w.range_down > 0.0 {
        if r_s < 0.0 {
            sig0 * (1.0 + w.range_down)
        } else {
            sig0 / (1.0 + w.range_down)
        }
    } else {
        sig0
    };
    let sig2 = sig * sig;
    let u1 = u1.max(1e-300);
    let u2 = u2.max(1e-300);
    (
        (r_s + (r_s * r_s - 2.0 * sig2 * u1.ln()).sqrt()) / 2.0,
        (r_s - (r_s * r_s - 2.0 * sig2 * u2.ln()).sqrt()) / 2.0,
    )
}

fn derive_channels(w: &World, x: &ChannelInputs, level: ChannelLevel, seed: u64) -> Channels {
    let mut rngs = ChannelRngs::new(seed);
    let tot = x.px.len();
    let sat_on = w.sat_beta > 0.0;
    let range_on = w.range_scale > 0.0;
    let open_on = w.overnight > 0.0;
    let bsk_on = w.basket > 0;
    let mut sat = if sat_on {
        vec![0.0f64; tot]
    } else {
        Vec::new()
    };
    let (mut hi, mut lo) = if range_on {
        (vec![0.0f64; tot], vec![0.0f64; tot])
    } else {
        (Vec::new(), Vec::new())
    };
    let mut vv = if range_on && w.vol_idio > 0.0 {
        vec![0.0f64; tot]
    } else {
        Vec::new()
    };
    let mut op = if open_on {
        vec![0.0f64; tot]
    } else {
        Vec::new()
    };
    let mut nm: Vec<Vec<f64>> = if bsk_on {
        vec![vec![0.0f64; tot]; w.basket]
    } else {
        Vec::new()
    };
    if !(sat_on || range_on || open_on || bsk_on) {
        return Channels {
            sat,
            log_hi: hi,
            log_lo: lo,
            log_volume: vv,
            log_open: op,
            names: nm,
        };
    }
    let k = level.k;
    let k_s = level.k_sat;
    let k_v = level.k_vs;
    // BASKET state: the primary's observed log price last session (its own tracker, like the
    // satellite's), and each name's log price.
    let mut sec_prev_px = 0.0f64;
    let mut name_log_p = vec![0.0f64; w.basket];
    // DRIFT DISPERSION — see the `basket_drift` field. One draw per name from `mrng`, taken BEFORE
    // the session loop, then centred exactly so the sector's log drift is untouched: only the
    // cross-section moves. Rescaled by sqrt(N/(N-1)) because centring N draws costs exactly that
    // much sample sd, so the dial delivers the sd it names. A single name has no cross-section to
    // disperse, so N < 2 is a no-op.
    let name_mu: Vec<f64> = if bsk_on && w.basket_drift > 0.0 && w.basket >= 2 {
        let z: Vec<f64> = (0..w.basket).map(|_| rngs.m.randn()).collect();
        let zb = z.iter().sum::<f64>() / z.len() as f64;
        let n = z.len() as f64;
        let sc = w.basket_drift * level.k_dr * (n / (n - 1.0)).sqrt() / DAYS_PER_YEAR as f64;
        z.iter().map(|v| (v - zb) * sc).collect()
    } else {
        vec![0.0f64; w.basket]
    };
    // SATELLITE LEG state: its log price and the primary's observed log price last session.
    let mut sat_log_p = 0.0f64;
    let mut sat_prev_px = 0.0f64;
    let mut bar = BarState {
        prev_px: 0.0,
        vol: VolState::new(w),
    };
    for i in 0..tot {
        let log_px = x.px[i];
        // SATELLITE LEG: beta times the primary's observed log return, plus idio noise at
        // `sat_idio` times the re-levelled state factor. The spiral's share of that factor is
        // load-bearing: on log-vol alone the residual's stress/calm vol ratio read 1.13 against
        // the anchored 3.1, and the missing state manufactured a +0.30 stress-correlation kick
        // the record does not have. Reads `srng` only.
        if sat_on {
            let idio = w.sat_idio * (x.state[i] * k_s) * rngs.s.randn();
            sat_log_p += w.sat_beta * (log_px - sat_prev_px) + idio;
            sat_prev_px = log_px;
            sat[i] = sat_log_p.exp();
        }
        // RANGE CHANNEL: high/low of a Brownian bridge from the bar's open (prior close) to its
        // close, at the re-levelled session scale times the disclosed compression dial. Exact
        // inverse transforms for the one-sided extremes, max drawn first then min; sampling the
        // pair independently is the stated approximation (their joint law is not independent —
        // a joint sampler was measured and rejected, see the docs). A jump day's range is >=
        // |ret| by construction — the extremes bracket both endpoints. The uniforms are floored
        // at 1e-300 so a zero draw cannot mint an infinite bar; the floor is part of the
        // cross-language contract. Reads `rrng` only.
        // THE BASKET — see the `basket` field. The shared sector leg first (its idio like the
        // satellite's, riding state x spiral), then per name: the sector's move + own idio on the
        // vol state alone + own gap. Reads `brng` only: one normal for the sector, then per name
        // one normal, one uniform, and on a gap session one normal and JUMP_NU normals for the t
        // — that draw ORDER is part of the cross-language contract.
        if bsk_on {
            let inp = BasketIn {
                primary_ret: log_px - sec_prev_px,
                state: x.state[i] * k_s,
                vol_state: x.vol_state[i] * k_v,
            };
            basket_session(w, inp, &mut rngs.b, &mut name_log_p, &name_mu);
            sec_prev_px = log_px;
            for (q, lp) in name_log_p.iter().enumerate() {
                nm[q][i] = *lp;
            }
        }
        if range_on || open_on {
            let out = BarOut {
                op: &mut op,
                hi: &mut hi,
                lo: &mut lo,
                vv: &mut vv,
            };
            bar_session(w, x, (i, k), &mut bar, out, &mut rngs);
        }
    }
    Channels {
        sat,
        log_hi: hi,
        log_lo: lo,
        log_volume: vv,
        log_open: op,
        names: nm,
    }
}

/// One session's inputs to the basket: the primary's observed return, and the two re-levelled
/// state factors — the satellite's (vol state x spiral) for the sector leg, the vol state alone
/// for the names' idio.
struct BasketIn {
    primary_ret: f64,
    state: f64,
    vol_state: f64,
}

/// One session of the basket: the shared sector move (beta x the primary's observed return plus
/// idio riding state x spiral), then per name its own idio on the vol state alone and its own
/// gap, accumulated into `name_log_p`. Reads `brng` only — one normal for the sector, then per
/// name one normal, one uniform, and on a gap session the t's draws — the cross-language draw
/// order.
fn basket_session(
    w: &World,
    inp: BasketIn,
    brng: &mut NumPyRng,
    name_log_p: &mut [f64],
    name_mu: &[f64],
) {
    let gap_prob = w.basket_gaps / DAYS_PER_YEAR as f64;
    let sec_idio = w.basket_sector * inp.state * brng.randn();
    let sec_ret = w.basket_beta * inp.primary_ret + sec_idio;
    for (lp, mu) in name_log_p.iter_mut().zip(name_mu) {
        let idio = w.basket_idio * inp.vol_state * brng.randn();
        let gap = if brng.next_f64() < gap_prob {
            basket_gap(brng)
        } else {
            0.0
        };
        *lp += sec_ret + idio + gap + mu;
    }
}

/// One basket gap: a standardized t(JUMP_NU) draw — z / sqrt(chi2/nu), the draw ORDER the jump
/// channel's — times the frozen gap size. SYMMETRIC, unlike the primary's jumps.
fn basket_gap(brng: &mut NumPyRng) -> f64 {
    let z = brng.randn();
    let mut chi = 0.0f64;
    for _ in 0..JUMP_NU {
        let g = brng.randn();
        chi += g * g;
    }
    let nu = JUMP_NU as f64;
    let t = z / (chi / nu).sqrt() / (nu / (nu - 2.0)).sqrt();
    // SYMMETRIC, unlike the primary's jumps — see `basket_gaps`. The index's down-skew is the
    // INDEX's and already reaches every name through the shared leg; a name's own large moves are
    // earnings and idiosyncratic news, and the record shows those are not skewed down.
    t * BASKET_GAP_SIZE
}

/// The price loop and the derived channels of one path; the channel arrays of `path` are empty
/// until `simulate_at` fills them.
struct Priced {
    path: Path,
    inputs: ChannelInputs,
    macro_in: MacroInputs,
}

/// THE MACRO PANEL's inputs: per-session states the price loop already carries, recorded when
/// `macro_panel` is on and read AFTER the loop by `derive_macro`. Nothing is computed for the
/// panel's sake and nothing here reaches a price. Empty when off; draw-free either way.
struct MacroInputs {
    /// equity `stress_idx` after the session's step
    stress: Vec<f64>,
    /// bond `stress_idx`
    b_stress: Vec<f64>,
    /// exp(log_vol - vol_norm), the vol state
    vol_state: Vec<f64>,
    /// the spiral's amplification, last_liq / impact
    amp: Vec<f64>,
    /// the policy accommodation stock
    acc: Vec<f64>,
    /// the trend crowd's capital share ENTERING the session
    w_trend: Vec<f64>,
    /// the leverage ratio, read before the session's step
    lev: Vec<f64>,
    /// the borrowing stock itself, the credit cycle's level
    borrow: Vec<f64>,
    /// the policy rate, decimal
    rate: Vec<f64>,
    /// inflation pressure, decimal
    infl: Vec<f64>,
    /// the log price level, for nominal output
    log_cpi: Vec<f64>,
    /// the fundamental's log base, BEFORE the rate markdown: the model's real activity
    log_fund: Vec<f64>,
}

/// The nine emitted counterparts, one value per session, in the counterpart's units.
#[derive(Clone, Debug)]
pub struct MacroPanel {
    pub spread: Vec<f64>,
    pub slope: Vec<f64>,
    pub cond: Vec<f64>,
    pub ivol: Vec<f64>,
    pub yield10: Vec<f64>,
    pub credit: Vec<f64>,
    pub policy: Vec<f64>,
    pub bank: Vec<f64>,
    pub output: Vec<f64>,
    /// a sibling path's panel (`-macronull`), decoupled from this path's price
    pub sibling: bool,
}

impl MacroPanel {
    fn drop(&self, k: usize) -> Self {
        Self {
            spread: self.spread[k..].to_vec(),
            slope: self.slope[k..].to_vec(),
            cond: self.cond[k..].to_vec(),
            ivol: self.ivol[k..].to_vec(),
            yield10: self.yield10[k..].to_vec(),
            credit: self.credit[k..].to_vec(),
            policy: self.policy[k..].to_vec(),
            bank: self.bank[k..].to_vec(),
            output: self.output[k..].to_vec(),
            sibling: self.sibling,
        }
    }

    fn member(&self, j: usize) -> &[f64] {
        match j {
            0 => &self.spread,
            1 => &self.slope,
            2 => &self.cond,
            3 => &self.ivol,
            4 => &self.yield10,
            5 => &self.credit,
            6 => &self.policy,
            7 => &self.bank,
            _ => &self.output,
        }
    }
}

/// The panel's FIXED maps. No scale dials: every consumer vote is a percentile rank against
/// trailing history or a sign, so a column's scale is invisible to it, and each map is a literal
/// in its counterpart's units, disclosed as unanchored (the `-ddshape` precedent). The slope is
/// the exception — rate units the model anchors — and its inversion share is graded, which is
/// what `TERM_PREMIUM` is solved against. The measurement components (`*_PHI`, `*_SD`) are
/// PERSISTENT, an AR(1) per member: a real spread carries its own market's factors, which a white
/// error could not mimic without collapsing the level's autocorrelation (0.999 in the record),
/// and they are sized so the member's predictive R^2 for forward 60-session returns matches the
/// record's (`macro-2026-09-06.tsv`, <= 0.02) — the oracle-leak guard.
mod macro_k {
    use super::DAYS_PER_YEAR;
    pub(super) const T2: f64 = 2.0;
    pub(super) const T10: f64 = 10.0;
    /// 10y over 2y at neutral, decimal
    pub(super) const TERM_PREMIUM: f64 = 0.010;
    /// the regime countdown's mean, 250 + U(0, 2500)
    pub(super) const REGIME_YEARS: f64 = 1500.0 / DAYS_PER_YEAR as f64;
    pub(super) const SPREAD_BASE: f64 = 1.3;
    pub(super) const SPREAD_STRESS: f64 = 1.0;
    pub(super) const SPREAD_BOND: f64 = 1.0;
    pub(super) const SPREAD_FLOOR: f64 = 0.5;
    pub(super) const SPREAD_SLOW: f64 = 12.0;
    /// the credit cycle: stress EWMA'd at a ~400-session half-life, 1 - 0.5^(1/400)
    pub(super) const SLOW_MU: f64 = 0.00173;
    /// a credit-market factor as slow as the level itself (record ac1 0.999), sd ~0.27 pp
    pub(super) const SPREAD_PHI: f64 = 0.999;
    pub(super) const SPREAD_SD: f64 = 0.012;
    /// THE CONDITIONS INDEX: the leverage ratio over its mean, scaled to the record's spread (sd
    /// about one index unit, p50 near the record's -0.24), plus the trend crowd's share over its
    /// home. The valuation gap is NOT a member: it is a price-level reading, and with the
    /// leverage cycle carrying the coupling it only diluted the build-up (0.85 -> 0.81, measured)
    pub(super) const COND_BASE: f64 = -0.7;
    pub(super) const COND_LEV: f64 = 10.0;
    pub(super) const COND_CROWD: f64 = 2.0;
    /// THE LEVERAGE CYCLE's constants (see `lev_gain`). The stock is a damped oscillator whose
    /// period (four years) and damping ratio (0.2) put the record's NFCILEVERAGE shape on it —
    /// weekly changes autocorrelated over a quarter, the level's autocorrelation 0.42 at a year
    /// and ~0 at two, rank spells of ~four months — around a mean of 0.75 with the level's
    /// stationary sd 0.15 (`lev_sd` is the velocity innovation that yields it, computed the way
    /// the Scala twin computes it so the two agree to the bit). Paid down per session per unit
    /// of the stress index at 0.007: the paydown sets how long a cascade keeps its gain, and
    /// with it lag-20 clustering (0.17 at 0.01, 0.19 at 0.007, the record's 0.225) against
    /// kurtosis. The fragility the spiral reads is the stock's rise over its trailing-year
    /// average, so the multiplier is centred by construction; the ratio's drawdown is smoothed
    /// over 21 sessions, a balance sheet not a tape; the multiplier is floored.
    pub(super) const LEV_PERIOD: f64 = 1008.0;
    pub(super) const LEV_ZETA: f64 = 0.2;
    pub(super) const LEV_OMEGA: f64 = 2.0 * core::f64::consts::PI / LEV_PERIOD;
    pub(super) const LEV_SPRING: f64 = LEV_OMEGA * LEV_OMEGA;
    pub(super) const LEV_DAMP: f64 = 2.0 * LEV_ZETA * LEV_OMEGA;
    pub(super) const LEV_LEVEL_SD: f64 = 0.15;
    pub(super) fn lev_sd() -> f64 {
        LEV_LEVEL_SD * (4.0 * LEV_ZETA * LEV_OMEGA * LEV_OMEGA * LEV_OMEGA).sqrt()
    }
    pub(super) const LEV_MEAN: f64 = 0.75;
    pub(super) const LEV_PAYDOWN: f64 = 0.007;
    pub(super) const LEV_SLOW_K: f64 = 1.0 / 252.0;
    pub(super) const LEV_DD_K: f64 = 1.0 / 21.0;
    pub(super) const LEV_MULT_FLOOR: f64 = 0.25;
    pub(super) const COND_PHI: f64 = 0.995;
    pub(super) const COND_SD: f64 = 0.02;
    /// e^0.28: the record's log variance risk premium
    pub(super) const VRP_MULT: f64 = 1.32;
    pub(super) const IVOL_PHI: f64 = 0.97;
    pub(super) const IVOL_SD: f64 = 0.02;
    pub(super) const IVOL_FLOOR: f64 = 5.0;
    /// well below the 21-session average of the spiral's decay (0.69): the record's implied vol
    /// persists past what the spiral does, and the leverage cycle's spikes are sharper still —
    /// 0.35 read the VIX's persistence at 0.69, on the band's floor
    pub(super) const IVOL_AMP_SHARE: f64 = 0.15;
    /// the sibling path's seed offset (`-macronull`)
    pub(super) const NULL_SEED: u64 = 0x51b1_1a60;
    pub(super) const COLUMNS: [&str; 9] = [
        "macroSpread",
        "macroSlope",
        "macroCond",
        "macroIvol",
        "macroYield10",
        "macroCredit",
        "macroPolicy",
        "macroBankCredit",
        "macroOutput",
    ];
    pub(super) const COUNTERPARTS: [&str; 9] = [
        "BAA10Y",
        "T10Y2Y",
        "NFCILEVERAGE",
        "VIXCLS",
        "DGS10",
        "TOTBKCR/GDP",
        "DFF",
        "TOTBKCR",
        "GDP",
    ];
    pub(super) const CADENCE: [&str; 9] = [
        "daily",
        "daily",
        "weekly",
        "daily",
        "daily",
        "weekly",
        "daily",
        "weekly",
        "quarterly",
    ];
    /// THE CREDIT-TO-OUTPUT RATIO IS A STOCK OF ITS OWN. It used to be the leverage cycle read in
    /// percent, and one state cannot be two record series: the leverage cycle `macroCond` reads
    /// turns every four years (weekly autocorrelation 0.42 at a year, -0.07 at two) where bank
    /// credit over output turns over decades (0.97, 0.93), so the ratio changed 18pp a year
    /// against the record's 2.1 and its rank was a four-year clock.
    ///
    /// The stock grows at the smooth nominal rate output does, plus a deepening term that fades at
    /// the ceiling, minus a paydown under stress, plus the borrowing cycle's deviation; the ratio
    /// is what the two levels imply, so a depression raises it the way the record's rose in 2020.
    /// ANCHORED ON THE RECORD DETRENDED, because the record's window carries a secular rise
    /// (43 to 63 over 1990-2026, +0.72pp a year) that no century-long stationary world can hold
    /// and that dominates the raw persistence rows: against the residual the model reads
    /// 0.72 / 0.42 at one and two years (record 0.75 / 0.47), a p10-p90 spread of 7.6pp (6.6), a
    /// median 55.9 (57.3) and a year-over-year change of 2.0pp (2.1). The secular rise itself is
    /// disclosed, not fitted.
    pub(super) const CREDIT_SCALE: f64 = 100.0;
    /// the ceiling deepening fades at, percent of output
    pub(super) const CREDIT_MAX: f64 = 72.0;
    /// how much faster than output credit grows at ratio 0, per year, fading linearly to nothing
    /// at the ceiling
    pub(super) const CREDIT_DEEPEN: f64 = 0.1322;
    /// paydown per year at full equity stress, as a growth rate
    pub(super) const CREDIT_PAY: f64 = 0.45;
    /// the borrowing cycle's deviation as a growth rate per unit
    pub(super) const CREDIT_LEV: f64 = 0.18;
    /// the record's median, where the stock starts
    pub(super) const CREDIT_START: f64 = 57.3;
    /// NOMINAL OUTPUT, the denominator made explicit so `macroBankCredit` (TOTBKCR) and
    /// `macroOutput` (GDP) can be read apart. Real output grows at `OUT_REAL` and takes
    /// `OUT_SHARE` of the fundamental's EXCESS growth -- the fundamental is the model's real
    /// activity, and a macro disaster is its depression -- and the price level makes it nominal.
    /// An INDEX: the model has no anchor for the size of its economy, so the base is `OUT_BASE` at
    /// the first EMITTED session (`logBasket`'s convention) and every consumer question about it
    /// -- growth, the ratio, credit relative to output -- is scale-free.
    ///
    /// real output trend per year, the record's 1990-2026 rate. NOMINAL growth is this plus the
    /// model's own inflation, which runs 4.0%/yr where the record's window ran 2.5 -- so emitted
    /// output grows 6.4%/yr against the record's 4.85, disclosed, not fitted: the price level is
    /// anchored in the price model, not here
    pub(super) const OUT_REAL: f64 = 0.024;
    /// share of the fundamental's excess growth output takes: earnings swing ~10%/yr where output
    /// swings ~2.6%
    pub(super) const OUT_SHARE: f64 = 0.12;
    pub(super) const OUT_BASE: f64 = 100.0;
    /// THE POLICY RATE is the loop's own rate read the way policy PUBLISHES it: a target set at a
    /// meeting, quantized to a quarter point, held until the next one. The loop's rate is a
    /// diffusion — it carries the rate uncertainty that makes stocks and bonds co-move in an
    /// inflation regime — so read raw it moves every session where the record's overnight rate is
    /// unchanged on 42% of weekdays, and 0.32pp over a quarter against the record's 0.13pp. The
    /// staircase is the same state, published: it consumes no draw, so a world's other members
    /// are unchanged by it.
    ///
    /// the record's move size, percentage points
    pub(super) const POLICY_STEP: f64 = 0.25;
    /// sessions between meetings; 8 a year is 31.5
    pub(super) const POLICY_MEETING: usize = 32;
}

/// THE MACRO PANEL's bands, US-wide so shared by both sets — `macro-2026-09-06.tsv`: the FIRING
/// LAG of the spread and the conditions index, +-40 sessions (the episode-to-episode spread of
/// the record's own lags) around the upper-middle of the four references' medians (spread +7 /
/// +7 / +16 / +16, conditions -48 / -44 / -49 / +101 — QQQ's is a four-episode median with two
/// late firings); the level's autocorrelation at 20 sessions (4 weekly readings for the
/// conditions index) +-0.08 around the record's; the oracle bound above the record's largest
/// predictive R^2 (0.019); the slope's inversion share around the record's 0.115; and the
/// variance risk premium around the record's 0.28-0.29 (log). `macro_panel_tests` /
/// `MacroPanelSuite` re-derive each from the fixture.
mod macro_bands {
    /// the four references' upper-middle 0.943 less 0.12, to 1
    pub(super) const COND_PRE_PEAK: (f64, f64) = (0.82, 1.00);
    /// engaged: clear of the null panel's 0.49 (+-0.02 on 500 episodes); the model read 0.63
    /// before the leverage cycle and reads 0.92 with it
    pub(super) const COND_PRE_PEAK_NULL: f64 = 0.55;
    /// the quarter-horizon 20% hazard: the four references' minimum (QQQ 1.45) floored to 0.1;
    /// CRSP 2.7, SPY 2.2, NDX 2.0; a decoupled series 1.0
    pub(super) const HAZARD_MIN: f64 = 1.4;
    pub(super) const SPREAD_LAG: (f64, f64) = (-24.0, 56.0);
    pub(super) const COND_LAG: (f64, f64) = (-84.0, -4.0);
    pub(super) const SPREAD_AC_K: (f64, f64) = (0.88, 1.00);
    pub(super) const COND_AC_K: (f64, f64) = (0.90, 1.00);
    pub(super) const IVOL_AC_K: (f64, f64) = (0.69, 0.85);
    pub(super) const ORACLE_R2: f64 = 0.03;
    pub(super) const INV_SHARE: (f64, f64) = (0.05, 0.25);
    pub(super) const VRP: (f64, f64) = (0.15, 0.40);
}

/// THE MACRO PANEL, derived from the finished loop's recorded state. Each member is a fixed map
/// of states the loop already carries plus its own persistent measurement component, so the
/// coupling to price is causal by construction and the read is lossy by construction:
///   spread  BAA10Y-like, pp: base + equity stress (fast and credit-cycle slow) + bond stress,
///           floored
///   slope   T10Y2Y-like, pp: the 10y minus the 2y yield the rate process implies — each the
///           OU-expected average of the short rate over its horizon, the rate decaying to the
///           policy target at `rate_speed`, the target's inflation term at the regime's mean life
///           and its accommodation term at `unwind` — plus a term premium. The same path drives
///           the bond, so the slope cannot contradict the `bond` column, and it inverts when
///           policy is tight against neutral: derived, never synthesized. No noise: it is an
///           expectation, and the one anchored-scale member
///   cond    NFCILEVERAGE-like, raw index: the trend crowd's share over its home plus the
///           valuation gap — the model's build-then-unwind state
///   ivol    VIXCLS-like, annualized %: the session's conditional sd x the record's variance risk
///           premium, floored
///   yield10 DGS10-like, pp: the slope's long leg, the same expectation at ten years
///   credit  TOTBKCR/GDP-like, %: the ratio the two levels below imply — its own slow stock,
///           deepening toward a ceiling, paid down under stress, over an output that a depression
///           cuts
///   policy  DFF-like, pp: the loop's own policy rate, re-set at a meeting to the nearest quarter
///           point and held — the target as policy publishes it
///   bank    TOTBKCR-like, index: the credit stock, 100 at the first emitted session
///   output  GDP-like, index: nominal output, 100 at the first emitted session
/// The last five are DRAW-FREE: states the loop already carries, read in the counterpart's
/// units, so adding one leaves every other member of a world bit-identical.
/// Publication — cadence, release lag, revisions — is the consumer's point-in-time layer, so
/// every member is the value an agency would MEASURE that session. Three normals per session from
/// a dedicated stream, spread then cond then ivol (the draw order is part of the cross-language
/// contract); the OU factors go through `exp_det`; everything else is IEEE-exact arithmetic in
/// fixed order.
#[expect(
    clippy::too_many_lines,
    reason = "mirrors one Scala method; splitting it would obscure the draw order, which is               the thing that has to stay verifiable"
)]
fn derive_macro(w: &World, m: &MacroInputs, seed: u64, k: f64, base: usize) -> Option<MacroPanel> {
    if w.macro_panel == 0 {
        return None;
    }
    let mut rng = NumPyRng::new(seed ^ 0x3ac2_0c0du64);
    let n = m.stress.len();
    // the T-year average of a deviation decaying at speed k: (1 - e^{-kT}) / (kT), 1 at k = 0
    let phi = |k: f64, t: f64| -> f64 {
        let kt = k * t;
        if kt <= 0.0 {
            1.0
        } else {
            (1.0 - exp_det(-kt)) / kt
        }
    };
    let d_r = phi(w.rate_speed, macro_k::T10) - phi(w.rate_speed, macro_k::T2);
    let d_a = phi(w.unwind, macro_k::T10) - phi(w.unwind, macro_k::T2);
    let d_i = phi(1.0 / macro_k::REGIME_YEARS, macro_k::T10)
        - phi(1.0 / macro_k::REGIME_YEARS, macro_k::T2);
    // the 10-year yield's own factors: the level the slope is a difference of
    let p_r10 = phi(w.rate_speed, macro_k::T10);
    let p_a10 = phi(w.unwind, macro_k::T10);
    let p_i10 = phi(1.0 / macro_k::REGIME_YEARS, macro_k::T10);
    // `k` is the world's implied-vol level (`ChannelLevel::k_iv`): forward realized vol over the
    // very factor read below, as a mean of logs, so the member's log premium over forward
    // realized vol is the record's by construction in every world and only the premium's
    // R^2 and persistence read the world. Re-levelling the DIFFUSIVE sd instead counted the slow
    // channel's variance twice — a world with the channel at half share read 0.55 against the
    // record's 0.29, and the shipped default with the panel on read 0.44.
    let k_ivol = k * macro_k::VRP_MULT;
    let mut spread = vec![0.0f64; n];
    let mut slope = vec![0.0f64; n];
    let mut cond = vec![0.0f64; n];
    let mut ivol = vec![0.0f64; n];
    let mut yield10 = vec![0.0f64; n];
    let mut credit = vec![0.0f64; n];
    let mut policy = vec![0.0f64; n];
    let mut bank = vec![0.0f64; n];
    let mut output = vec![0.0f64; n];
    let mut target25 = 0.0f64;
    let mut ratio = macro_k::CREDIT_START;
    let mut log_out = 0.0f64;
    let dt_y = 1.0 / DAYS_PER_YEAR as f64;
    let mut e_s = 0.0f64;
    let mut e_c = 0.0f64;
    let mut e_v = 0.0f64;
    let mut slow = 0.0f64;
    for i in 0..n {
        e_s = macro_k::SPREAD_PHI * e_s + macro_k::SPREAD_SD * rng.randn();
        e_c = macro_k::COND_PHI * e_c + macro_k::COND_SD * rng.randn();
        e_v = macro_k::IVOL_PHI * e_v + macro_k::IVOL_SD * rng.randn();
        slow += macro_k::SLOW_MU * (m.stress[i] - slow);
        spread[i] = (macro_k::SPREAD_BASE
            + macro_k::SPREAD_STRESS * m.stress[i]
            + macro_k::SPREAD_SLOW * slow
            + macro_k::SPREAD_BOND * m.b_stress[i]
            + e_s)
            .max(macro_k::SPREAD_FLOOR);
        let target = w.rate_mean + m.infl[i] - m.acc[i];
        slope[i] = 100.0
            * (m.infl[i] * d_i - m.acc[i] * d_a
                + (m.rate[i] - target) * d_r
                + macro_k::TERM_PREMIUM);
        // THE 10-YEAR YIELD (DGS10-like, pp): the OU-expected average of the short rate over ten
        // years — the neutral rate, the regime's inflation term, the accommodation term and the
        // rate's own gap decaying at their speeds — plus the term premium. The slope above is
        // this less the 2-year's, so the level cannot contradict it. No noise: an expectation.
        yield10[i] = 100.0
            * (w.rate_mean + m.infl[i] * p_i10 - m.acc[i] * p_a10
                + (m.rate[i] - target) * p_r10
                + macro_k::TERM_PREMIUM);
        // NOMINAL OUTPUT (GDP-like, index): the real trend and the price level — the SMOOTH
        // nominal rate credit also grows at — plus `OUT_SHARE` of the fundamental's excess
        // growth, which is the model's real activity and carries its disasters as depressions.
        let d_nom = macro_k::OUT_REAL * dt_y
            + if i > 0 {
                m.log_cpi[i] - m.log_cpi[i - 1]
            } else {
                0.0
            };
        let d_excess = if i > 0 {
            macro_k::OUT_SHARE * ((m.log_fund[i] - m.log_fund[i - 1]) - w.drift * dt_y)
        } else {
            0.0
        };
        log_out += d_nom + d_excess;
        output[i] = exp_det(log_out);
        // THE CREDIT-TO-OUTPUT RATIO (TOTBKCR/GDP-like, percent). The STOCK grows at the smooth
        // nominal rate plus its own three terms, so the ratio is what the two levels imply:
        //   deepening   credit outgrows output by `CREDIT_DEEPEN` at ratio 0, fading to nothing at
        //               the ceiling — the secular half of the record's window, stationary
        //   paydown     `CREDIT_PAY` a year at full equity stress — itself a state that decays
        //               over weeks, so this is a crisis's deleveraging and not one session's —
        //               and the LEVEL then carries that crisis until deepening pulls it back,
        //               which is what makes the ratio's own history readable
        //   appetite    the borrowing cycle's deviation from its mean, so the ratio still moves
        //               with the model's own credit state at an amplitude that leaves the
        //               four-year period a ripple rather than the signal
        // and the DENOMINATOR moves it too: output's excess growth is subtracted, so a depression
        // raises the ratio the way the record's rose in 2020. Euler on a per-session step of
        // ~1e-4, so no exponential is needed and the twins share the arithmetic exactly.
        credit[i] = ratio;
        ratio += ratio
            * ((macro_k::CREDIT_DEEPEN * (1.0 - ratio / macro_k::CREDIT_MAX)
                - macro_k::CREDIT_PAY * m.stress[i]
                + macro_k::CREDIT_LEV * (m.borrow[i] - macro_k::LEV_MEAN))
                * dt_y
                - d_excess);
        ratio = ratio.max(0.0);
        // THE POLICY RATE (DFF-like, pp): the loop's own rate, published as policy publishes it —
        // re-set at a meeting to the nearest quarter point and held until the next. Draw-free.
        // `floor(x + 0.5)`, never `round`: half-way values must round the same way in both twins,
        // and Rust's round rounds half AWAY from zero where Scala's rint rounds half to EVEN.
        if i.is_multiple_of(macro_k::POLICY_MEETING) {
            target25 =
                macro_k::POLICY_STEP * (100.0 * m.rate[i] / macro_k::POLICY_STEP + 0.5).floor();
        }
        policy[i] = target25;
        // the leverage ratio — the state the record's index measures and, through `lev_gain`,
        // the state the model's big declines follow — over its mean, plus the crowd's share
        cond[i] = macro_k::COND_BASE
            + macro_k::COND_LEV * (m.lev[i] - macro_k::LEV_MEAN)
            + macro_k::COND_CROWD * (m.w_trend[i] - w.trend_share)
            + e_c;
        // a forward-looking vol prices the amplification it expects over its horizon, not the
        // session's: the spiral's stress index decays at 0.96 a session, and its 21-session
        // average is `IVOL_AMP_SHARE` of today's
        ivol[i] = (k_ivol
            * m.vol_state[i]
            * (1.0 + macro_k::IVOL_AMP_SHARE * (m.amp[i] - 1.0))
            * (1.0 + e_v))
            .max(macro_k::IVOL_FLOOR);
    }
    // THE INDEX BASE: `OUT_BASE` at the first EMITTED session, so both levels are indices a
    // consumer reads as growth and as a ratio. The credit stock is built from the rescaled output,
    // so `macroBankCredit / macroOutput x 100` reproduces `macroCredit`: to 1e-6 here, and to
    // 2e-6 pp read back from the emitted text, which is the columns' own six-decimal rounding —
    // `logBasket`'s convention.
    let f = macro_k::OUT_BASE / output[base.clamp(0, n - 1)];
    for q in 0..n {
        output[q] *= f;
        bank[q] = credit[q] * output[q] / macro_k::CREDIT_SCALE;
    }
    Some(MacroPanel {
        spread,
        slope,
        cond,
        ivol,
        yield10,
        credit,
        policy,
        bank,
        output,
        sibling: w.macro_null > 0,
    })
}

/// One independent history: the price loop, then the derived channels at the given world level.
/// THE DIVIDEND STREAM, derived from the finished path: the session yield `div_yield` x
/// (fundamental/price) / k_div in %/yr — k_div the world's mean fundamental/price from
/// `world_level`, so the dial is the world's MEAN yield and a rich session yields less — and the
/// traded price as the total-return index deflated by the yield accrued each session,
/// S_t = S_{t-1} (P_t/P_{t-1} - y_t/100/DAYS_PER_YEAR), S_0 = P_0. Observational: reaches no
/// price and consumes no draw, so 0 is bit-identical off. IEEE-exact arithmetic only, evaluated
/// left to right in both twins.
fn derive_dividends(w: &World, px: &[f64], fv: &[f64], k_div: f64) -> (Vec<f64>, Vec<f64>) {
    let on = w.div_yield > 0.0;
    if !on {
        return (Vec::new(), Vec::new());
    }
    let n = px.len();
    let y: Vec<f64> = (0..n)
        .map(|i| w.div_yield * (fv[i] / px[i]) / k_div)
        .collect();
    let mut t = vec![0.0f64; n];
    t[0] = px[0];
    for i in 1..n {
        t[i] = t[i - 1] * (px[i] / px[i - 1] - y[i] / 100.0 / DAYS_PER_YEAR as f64);
    }
    (y, t)
}

fn simulate_at(w: &World, years: usize, seed: u64, level: ChannelLevel) -> Path {
    let pr = price_loop(w, years, seed);
    let chan = derive_channels(w, &pr.inputs, level, seed);
    let div = derive_dividends(w, &pr.path.price, &pr.path.fundamental, level.k_div);
    Path {
        div_yield: div.0,
        traded: div.1,
        sat: if w.sat_beta > 0.0 {
            chan.sat[BURN_IN..].to_vec()
        } else {
            Vec::new()
        },
        log_hi: if w.range_scale > 0.0 {
            chan.log_hi[BURN_IN..].to_vec()
        } else {
            Vec::new()
        },
        log_lo: if w.range_scale > 0.0 {
            chan.log_lo[BURN_IN..].to_vec()
        } else {
            Vec::new()
        },
        log_volume: if w.vol_idio > 0.0 {
            chan.log_volume[BURN_IN..].to_vec()
        } else {
            Vec::new()
        },
        log_open: if w.overnight > 0.0 {
            chan.log_open[BURN_IN..].to_vec()
        } else {
            Vec::new()
        },
        names: if w.basket > 0 {
            chan.names.iter().map(|lp| lp[BURN_IN..].to_vec()).collect()
        } else {
            Vec::new()
        },
        chan_k: level.k,
        chan_k_sat: level.k_sat,
        chan_k_div: level.k_div,
        chan_k_vs: level.k_vs,
        chan_k_iv: level.k_iv,
        // carried only when the dial is on, so every sidecar without it is unchanged
        chan_k_dr: if w.basket_drift > 0.0 {
            level.k_dr
        } else {
            0.0
        },
        macro_panel: {
            // THE NULL PANEL (`-macronull`): the panel of a SIBLING path — the same world at
            // another seed, its own price loop and its own measurement stream — so the columns
            // keep this world's marginals and persistence and their coupling to this path's
            // price is nil. One extra price loop per path, only when on.
            let sib = seed ^ macro_k::NULL_SEED;
            let sibling = if w.macro_null > 0 {
                Some(price_loop(w, years, sib).macro_in)
            } else {
                None
            };
            let (macro_in, macro_seed) = match &sibling {
                Some(m) => (m, sib),
                None => (&pr.macro_in, seed),
            };
            derive_macro(w, macro_in, macro_seed, level.k_iv, BURN_IN).map(|m| m.drop(BURN_IN))
        },
        ..pr.path
    }
}

/// `simulate_at` at the world's own level, solved here per call — `sim_paths` solves it once
/// for the whole ensemble, so prefer that for more than one path.
pub fn simulate(w: &World, years: usize, seed: u64) -> Path {
    simulate_at(w, years, seed, world_level(w))
}

/// One independent history's PRICE LOOP, with the channels' per-session inputs recorded for the
/// second pass. Local mutable state only — nothing escapes this function.
#[expect(
    clippy::too_many_lines,
    clippy::cognitive_complexity,
    reason = "mirrors one Scala method; splitting it would obscure the draw order, which is \
              the thing that has to stay verifiable"
)]
#[expect(
    clippy::manual_range_contains,
    reason = "explicit comparisons mirror the Scala AND differ from RangeInclusive::contains \
              on NaN: `x < a || x > b` leaves NaN on the false branch, `!(a..=b).contains(&x)` \
              puts it on the true branch"
)]
fn price_loop(w: &World, years: usize, seed: u64) -> Priced {
    let n = years * DAYS_PER_YEAR;
    let tot = n + BURN_IN;
    let mut rng = NumPyRng::new(seed);
    // The jump channel's own stream. Separate BECAUSE the alternative is not survivable: a draw
    // taken from `rng` shifts every subsequent value and moves all sixteen calibrated statistics,
    // so the channel could not be added without re-searching the world. Constructed
    // unconditionally — it costs one allocation and touches nothing — and read only when
    // `jump_var > 0`.
    let mut jrng = NumPyRng::new(seed ^ 0x1eaf_7a11u64);
    // The disaster channel's own stream, for the same survivability reason as `jrng` above:
    // constructed unconditionally, read only when `disaster_rate > 0`, so rate 0 is bit-identical.
    let mut drng = NumPyRng::new(seed ^ 0xd15a_57e5u64);
    // The news channel's own stream (prototype), same survivability contract as `jrng`/`drng`:
    // constructed unconditionally, read only when `news_rate > 0`, so rate 0 is bit-identical.
    let mut nrng = NumPyRng::new(seed ^ 0x0bad_2e15u64);
    // The leverage cycle's own stream, same contract: read only when the stock is evolved.
    let mut lrng = NumPyRng::new(seed ^ 0xc2ed_17c7u64);
    // The channels' own streams are constructed in `derive_channels` from this same seed.
    let mut px = vec![0.0f64; tot];
    let mut fv = vec![0.0f64; tot];
    let mut rt = vec![0.0f64; tot];
    let mut lq = vec![0.0f64; tot];
    let mut bq = vec![0.0f64; tot];
    let mut bp = vec![0.0f64; tot];
    let mut ip = vec![0.0f64; tot];
    let mut cp = vec![0.0f64; tot];
    let dt = 1.0 / DAYS_PER_YEAR as f64;
    let sqdt = dt.sqrt();

    let mut eq_m = Market::with_recovery(
        w.value_pull,
        w.stress,
        12.0 / w.depth,
        w.recovery_drag,
        w.recovery_floor,
        w.halt_limit,
    );
    // set after construction rather than as a seventh constructor argument, the way `lev_mult` and
    // `gain_mult` are: the equity spiral's scale speed, the bond's left at its default.
    eq_m.scale_mu = w.stress_adapt;
    let mut bd_m = Market::new(K_VALUE_BOND, w.stress, 1.0);
    // THE AMPLIFIER STUDY's gain scale: the equity market's alone (the bond's impact IS its
    // reference). Exact forms at 1 and 0.5; anything else goes through `exp_det` on a log, which
    // the twins' parity run guards.
    if w.stress_scale > 0.0 {
        let ratio = w.depth / DEPTH_REF;
        eq_m.gain_mult = if w.stress_scale == 1.0 {
            ratio
        } else if w.stress_scale == 0.5 {
            ratio.sqrt()
        } else {
            exp_det(w.stress_scale * ratio.ln())
        };
    }

    let mut log_vbase = 0.0f64;
    let mut rate = w.rate_mean;
    let mut infl_press = 0.0f64;
    // policy accommodation in force, in rate points
    let mut acc = 0.0f64;
    let mut infl_target = 0.0f64;
    let mut drift_now = w.drift;
    let mut regime_countdown: i64 = 250 + i64::from(rng.next_bounded_u32(2500));
    let mut fair_b = 0.0f64;
    // realized inflation: baseline plus the same pressure that drives the rate. DELIBERATELY
    // noise-free — it consumes no random draws, so adding it left every calibrated statistic
    // bit-identical. piBase 0.025 makes rateMean 4.2% a ~1.7% real rate.
    let pi_base = 0.025f64;
    let mut log_cpi = 0.0f64;
    let mut w_trend = w.trend_share;
    let mut w_trend_sum = 0.0f64;
    let mut pinned_cnt = 0usize;
    let mut sat_cnt = 0usize;
    let mut perf_v = 0.0f64;
    let mut perf_t = 0.0f64;
    let k_adapt = 0.010f64;
    let k_home = 0.020f64;
    let mut log_vol = 0.0f64;
    // The leverage term's signal from the PREVIOUS session: max(-ret,0)/scale - 0.399, the same
    // decline reading `stress_idx` consumes, centred so the vol level does not drift with the
    // dial. Draw-free; both its update and its use sit behind `leverage > 0`, so 0 is
    // bit-identical off.
    // ITEM 12's states, both exactly inert at their dial's 0: `kick_s` is the persistent kick's
    // own EWMA of the same saturated decline signal (at 0 it IS `lev_sig`, so the multiplier is
    // the shipped one bit for bit), `asym_g` the asymmetric noise vol's log multiplier, driven by
    // the DIFFUSIVE DRAW rather than by any price-derived quantity.
    let mut kick_s = 0.0f64;
    let mut vol_resp_s = 0.0f64;
    // THE BUST SWING's state (see `bust_amp`): the gap's slow mean, the level at the running
    // peak, the armed state, the episode's low and the sessions since it, the swing and its own
    // stream. Draw-free at 0: the stream is only drawn while the dial is on.
    let mut gap_mean = 0.0f64;
    let gap_mu = 1.0 / (BUST_MEAN_YEARS * DAYS_PER_YEAR as f64);
    let mut peak_lvl = 0.0f64;
    let mut bust_s = 0.0f64;
    let mut ep_low = f64::INFINITY;
    let mut since_low: usize = 0;
    let mut bust_rng = NumPyRng::new(seed ^ 0xb057_c0deu64);
    let mut bust_swing = 0.0f64;
    let mut bust_news = 0.0f64;
    let mut bust_move = 0.0f64;
    let mut vol_resp_a = 0.0f64;
    let mut asym_g = 0.0f64;
    let mut asym_a = 0.0f64;
    let asym_norm = noise_asym_var(w.noise_asym, w.noise_asym_phi);
    // Settled equity stress for the refuge bid (see `refuge_days`); draw-free, and both its use
    // and its update sit behind `refuge_days > 0`, so 0 is bit-identical off.
    let mut settled_stress = 0.0f64;
    // THE LEVERAGE CYCLE's stock (see `lev_gain`), evolved whenever the mechanism or the macro
    // panel reads it, on its own stream, and reaching the price only through `lev_mult`, which
    // stays exactly 1.0 with the dial off. `lev` is the session's ratio, read before the step;
    // `lev_slow` the stock's trailing-year average the growth is read against; `dd_s` the
    // drawdown the ratio reads.
    let lev_on = w.lev_gain > 0.0 || w.macro_panel > 0;
    let lev_sd = macro_k::lev_sd();
    let mut borrow = macro_k::LEV_MEAN;
    let mut lev_vel = 0.0f64;
    let mut lev_slow = macro_k::LEV_MEAN;
    let mut dd_s = 0.0f64;
    let mut lev = 0.0f64;
    let settle_mu = if w.refuge_days > 0.0 {
        1.0 - (-(2.0f64.ln()) / w.refuge_days).exp()
    } else {
        0.0
    };
    let vol_norm = (w.vol_of_vol * w.vol_of_vol) / 1e-9f64.max(1.0 - w.vol_persist * w.vol_persist);
    // THE SLOW REPRICING CHANNEL (item 15). `slow_share` of the diffusive variance leaves the
    // order-flow channel and reappears as a repricing that moves the fundamental and the price
    // TOGETHER, the way the news jump does, so the value channel has nothing to arbitrage and the
    // move never passes through `step` and its spiral. Its state is driven ONLY by its own
    // leverage term, so the variance it carries is long-memoried AND asymmetric — a symmetric
    // component was measured and is strictly worse, because variance moved out of the amplifier
    // then loses the leverage profile the amplifier was supplying. Own RNG stream, and
    // `slow_share` is the switch: at 0 the block never runs and `mix` is exactly 1.
    let mut slow_rng = NumPyRng::new(seed ^ 0x510e_c0deu64);
    let mut slow_g = 0.0f64;
    let mut slow_b = 0.0f64;
    // scaled by sqrt(1 - phi^2) on input, so `slow_lev` is in units of the state's STATIONARY sd
    // and the centring is its variance; unscaled it runs 11x nominal and volatility reaches 200%.
    let slow_k = (1.0 - w.slow_phi * w.slow_phi).sqrt();
    let slow_norm = w.slow_lev * w.slow_lev;
    let slow_scale = SIGMA_N * w.slow_vol * (12.0 / w.depth);
    let mix = (1.0 - w.slow_share).sqrt();
    // News variance DISPLACES diffusive noise (see `news_damp_at`); 1.0 when the channel is off.
    let news_damp = news_damp_at(w.news_rate, w.news_size);
    let crowd_win: usize = match w.crowd {
        Crowd::Trend(d) => 2.max((f64::from(d) * 252.0 / 365.25).round() as usize),
        _ => 0,
    };
    // The crowd starts where its own target starts, so the first session is not a trade it never
    // made. The banded crowds begin fully invested (1.0); the momentum crowd's target IS
    // `trend_pos`, which is 0 while there is no history to measure momentum over.
    let crowd_init = if matches!(w.crowd, Crowd::Momentum) {
        0.0f64
    } else {
        1.0f64
    };
    let mut crowd_e = crowd_init;
    let mut crowd_prev = crowd_init;
    // BELIEF state for the slow valuation cycle: the EWMA of the price/fair gap that perceived
    // fair value has absorbed. Updated from information strictly before this session.
    let mut belief = 0.0f64;
    let belief_mu = if w.belief_years <= 0.0 {
        0.0
    } else {
        1.0 - (-(2.0f64.ln()) / (w.belief_years * DAYS_PER_YEAR as f64)).exp()
    };
    // Growth-extrapolation state: EWMA of the fundamental's per-session log change, annualized in
    // the perceived-fair term. Seeded at the unconditional drift so burn-in starts neutral.
    let mut g_ewma = w.drift * dt;
    let g_mu = if w.cap_window <= 0.0 {
        0.0
    } else {
        1.0 - (-(2.0f64.ln()) / (w.cap_window * DAYS_PER_YEAR as f64)).exp()
    };
    let mut v_prev = 0.0f64;
    let mut ma_sum = 0.0f64;
    let mut crowd_rv = 0.01 * 0.01f64;
    let mut crowd_anchor = 0.0f64;
    // The drawdown crowd's running peak of the prior session's emitted price; draw-free.
    let mut crowd_peak = 0.0f64;
    let mut bond_stress_sum = 0.0f64;
    let mut bond_stress_hi = 0usize;
    let mut crowd_flow_sum = 0.0f64;
    let mut clamps_at_burn = 0usize;
    let mut eq_floor_at_burn = 0usize;
    let mut eq_tail_at_burn = 0usize;
    let mut eq_halt_at_burn = 0usize;
    // MACRO DISASTER state: sessions left in the current collapse, its per-session decrement, the
    // recovery leg, and the post-burn-in onset count — the channel's BINDING diagnostic.
    let mut dis_left = 0usize;
    let mut dis_step = 0.0f64;
    let mut rec_left = 0usize;
    let mut rec_step = 0.0f64;
    let mut disaster_count = 0usize;
    let dis_prob = w.disaster_rate / (100.0 * DAYS_PER_YEAR as f64);
    // THE CHANNELS' INPUTS, recorded per session and sampled AFTER the loop by `derive_channels`
    // (see it for why the level is a world constant, never read off the path being emitted): the
    // observed log price, the session diffusion sd as the price received it, the satellite's
    // state factor, and the post-step realized scale the volume's down-term reads. Empty when
    // both channels are off; draw-free either way, so off worlds stay bit-identical.
    let ch_on = w.range_scale > 0.0
        || w.sat_beta > 0.0
        || w.overnight > 0.0
        || w.basket > 0
        || w.macro_panel > 0; // the implied-vol member reads `k_vs`
    let mut ch = ChannelInputs {
        px: if ch_on { vec![0.0f64; tot] } else { Vec::new() },
        d: if ch_on { vec![0.0f64; tot] } else { Vec::new() },
        state: if ch_on { vec![0.0f64; tot] } else { Vec::new() },
        scale_var: if ch_on { vec![0.0f64; tot] } else { Vec::new() },
        jump: if ch_on { vec![0.0f64; tot] } else { Vec::new() },
        vol_state: if ch_on { vec![0.0f64; tot] } else { Vec::new() },
        amp: if ch_on { vec![0.0f64; tot] } else { Vec::new() },
    };
    // THE MACRO PANEL's inputs, recorded per session and read after the loop by `derive_macro`;
    // empty when the dial is off, draw-free either way.
    let mc_on = w.macro_panel > 0;
    let mc_vec = || if mc_on { vec![0.0f64; tot] } else { Vec::new() };
    let mut mc = MacroInputs {
        stress: mc_vec(),
        b_stress: mc_vec(),
        vol_state: mc_vec(),
        amp: mc_vec(),
        acc: mc_vec(),
        w_trend: mc_vec(),
        lev: mc_vec(),
        borrow: mc_vec(),
        rate: mc_vec(),
        infl: mc_vec(),
        log_cpi: mc_vec(),
        log_fund: mc_vec(),
    };

    let mut i = 0usize;
    while i < tot {
        // ---- exogenous layer: regimes, fundamental, the policy rate ----------------------
        regime_countdown -= 1;
        if regime_countdown <= 0 {
            infl_target = if rng.next_f64() < w.infl_prob {
                INFL_CAP.min(rng.randn().abs() * w.infl_size)
            } else {
                0.0
            };
            drift_now = w.drift + rng.randn() * 0.04;
            regime_countdown = 250 + i64::from(rng.next_bounded_u32(2500));
        }
        // MACRO DISASTER: a rare multi-year collapse of the real fundamental. One uniform draw
        // per session from the channel's own stream while armed; onset starts a decline of
        // `disaster_size` log spread evenly over `disaster_len` years, which the price then tracks
        // through the ordinary value channel — the crash is fundamental-led, like 1929-32, and the
        // spiral and recovery drag shape it downstream. No new disaster starts while one runs.
        if dis_prob > 0.0 {
            if dis_left > 0 {
                log_vbase -= dis_step;
                dis_left -= 1;
                // trough reached: the RECOVERY leg arms, spreading `disaster_recover` of the
                // decline back over `disaster_rec_len` years. What does NOT reverse is permanent.
                if dis_left == 0 && w.disaster_recover > 0.0 {
                    rec_left = ((w.disaster_rec_len * DAYS_PER_YEAR as f64) as usize).max(1);
                    rec_step = w.disaster_recover * w.disaster_size / rec_left as f64;
                }
            } else {
                if rec_left > 0 {
                    log_vbase += rec_step;
                    rec_left -= 1;
                }
                if drng.next_f64() < dis_prob {
                    dis_left = ((w.disaster_len * DAYS_PER_YEAR as f64) as usize).max(1);
                    dis_step = w.disaster_size / dis_left as f64;
                    if i >= BURN_IN {
                        disaster_count += 1;
                    }
                }
            }
        }
        log_vbase += drift_now * dt + w.fund_vol * sqdt * rng.randn();
        // FAIR-VALUE NEWS JUMP (prototype): a permanent markdown repriced the SAME session — the
        // fundamental and the price take the full drop together, so the price/fair gap, and with
        // it the value channel, the belief EWMA and `mispricing_pre`, are untouched: a pure
        // random-walk step with nothing for value capital to buy back. Morning news, placed
        // before the demand-flows read of `log_p`, so the momentum crowd trades on it this
        // session the way it trades on `markdown`. The compensator is deterministic and returns
        // the expected drift cost on BOTH legs.
        let mut news_j = 0.0f64;
        let mut jump_now = 0.0f64;
        if w.news_rate > 0.0 {
            let comp = w.news_rate * w.news_size / DAYS_PER_YEAR as f64;
            log_vbase += comp;
            eq_m.log_p += comp;
            if nrng.next_f64() < w.news_rate / DAYS_PER_YEAR as f64 {
                log_vbase -= w.news_size;
                eq_m.log_p -= w.news_size;
                news_j = w.news_size;
            }
        }
        // the channel's share of THIS session's conditional variance, for the implied-vol member;
        // 0 when the channel is off, so that member is unchanged.
        let mut slow_var = 0.0f64;
        if w.slow_share > 0.0 {
            let zs = slow_rng.randn();
            let smul = (slow_g - slow_norm).exp();
            slow_var = w.slow_vol * w.slow_vol * smul * smul;
            let sm = slow_scale * smul * zs;
            // only `slow_perm` of it reaches the fundamental: the rest opens a gap the value
            // channel closes, which is what keeps the momentum crowd from chasing the whole move
            // into a variance ratio. The bond takes the same repricing with the opposite sign.
            log_vbase += w.slow_perm * sm;
            eq_m.log_p += sm;
            // a YIELD repricing, so the bond's move scales with its duration like every other
            // bond flow (`SIGMA_N_BOND`, the refuge); the ratio is a bit-exact 1.0 at the shipped
            // duration
            let bm = -w.slow_beta * sm * (w.duration / DURATION_REF);
            bd_m.log_p += bm;
            slow_b += w.slow_perm * bm;
            slow_g = w.slow_phi * slow_g - w.slow_lev * slow_k * zs;
        }
        infl_press += w.infl_speed * (infl_target - infl_press);
        // policy: chase rateMean + pressure MINUS accommodation, and accommodation is a CAPPED
        // STOCK rather than a cut speed — eased in within ~2 months, withdrawn over years. As a
        // speed it was unbounded, so a stress episode took the rate to the floor and the same
        // `rate_speed` pulled it straight back; the bond's peak was set by that spike. Inflation
        // suppresses the easing, which is what ties policy's hands in 2022-like regimes.
        let acc_want = w.easing * eq_m.stress_idx * (-infl_press / 0.005).exp();
        acc = if acc_want > acc {
            acc + EASE_IN_SPEED * (acc_want - acc) * dt
        } else {
            0.0f64.max(acc - w.unwind * acc * dt)
        };
        let r_old = rate;
        // rate UNCERTAINTY rises with inflation pressure (2022: MOVE elevated all year). This
        // is what makes stocks and bonds co-move in an inflation regime: both are priced off
        // the same rate, so more rate news = more shared-factor variance = the correlation flip.
        rate = 0.0f64.max(
            rate + w.rate_speed * ((w.rate_mean + infl_press - acc) - rate) * dt
                + 0.01 * (1.0 + 25.0 * infl_press) * sqdt * rng.randn(),
        );
        // bond fair value: carry minus duration times the realised rate move
        fair_b += rate * dt - w.duration * (rate - r_old);
        // The discount markdown applies to the OBSERVED equity price directly — same-day,
        // like the bond's duration response — because equities reprice discount-rate news
        // immediately. Routing it through the slow value channel smeared rate news over ~40
        // sessions on the equity side while the bond moved same-day, so the two assets shared
        // no same-day factor and the correlation flip could not appear at any setting.
        let markdown = w.discount * (rate - w.rate_mean);

        // ---- crowd target, from information strictly before this session ------------------
        if i > 0 {
            let p_prev = px[i - 1];
            match w.crowd {
                Crowd::Trend(_) => {
                    ma_sum += p_prev;
                    if i > crowd_win {
                        ma_sum -= px[i - 1 - crowd_win];
                    }
                    let tgt = if p_prev >= ma_sum / i.min(crowd_win) as f64 {
                        1.0
                    } else {
                        0.0
                    };
                    if (tgt - crowd_e).abs() > BAND {
                        crowd_e = tgt;
                    }
                }
                Crowd::VolScaled => {
                    let r = (p_prev / px[i.saturating_sub(2)]).ln();
                    crowd_rv = 0.94 * crowd_rv + 0.06 * r * r;
                    let v = (crowd_rv * DAYS_PER_YEAR as f64).sqrt();
                    crowd_anchor = if crowd_anchor == 0.0 {
                        v
                    } else {
                        0.999 * crowd_anchor + 0.001 * v
                    };
                    let tgt = 0.0f64.max(1.0f64.min(if v > 0.0 { crowd_anchor / v } else { 1.0 }));
                    if (tgt - crowd_e).abs() > BAND {
                        crowd_e = tgt;
                    }
                }
                Crowd::Drawdown(d) => {
                    if p_prev > crowd_peak {
                        crowd_peak = p_prev;
                    }
                    let tgt = if p_prev >= crowd_peak * (1.0 - f64::from(d) / 100.0) {
                        1.0
                    } else {
                        0.0
                    };
                    if (tgt - crowd_e).abs() > BAND {
                        crowd_e = tgt;
                    }
                }
                Crowd::Momentum => {}
            }
        }

        // ---- demand flows -----------------------------------------------------------------
        let log_pobs = eq_m.log_p - markdown; // what everyone actually sees and trades
        let mispricing_pre = log_vbase - eq_m.log_p; // value agents arb the traded component
        let lookback = 60usize;
        let past = if i >= lookback {
            px[i - lookback].ln()
        } else {
            log_pobs
        };
        let momentum = log_pobs - past;
        // `tanh_p`, not the native tanh, since 0.23.0 — see `tanh_p`: the native form diverged
        // from the JVM's by one ulp on a cycle-world input after four releases of input luck.
        let trend_pos = tanh_p(momentum / 0.12);
        // The momentum crowd's desired exposure, set here rather than in the block above because
        // `trend_pos` needs this session's `log_pobs` -- and `log_pobs` carries this session's
        // `markdown`, so this crowd reacts to the rate move being priced in the SAME session, where
        // `Crowd::Trend` and `Crowd::VolScaled` read `px[i - 1]` alone. Two live consequences:
        // `-crowd` varies information timing along with crowd type, and `perf_t` below pairs a
        // position holding -discount*d_rate with a return holding the same term, a product that is
        // structurally positive and tilts the capital spring toward the trend crowd by arithmetic
        // rather than trading. `trend_share` is calibrated, so the calibration has absorbed it;
        // whether the crowd should act one session later instead is a MECHANISM question, and
        // changing it moves every calibrated statistic. It is continuous where the other crowds'
        // targets are banded, and deliberately unbanded: the 0.05 band exists to stop a BINARY
        // target flip-flopping across a moving average, and a continuous target has nothing to
        // flip-flop about.
        if matches!(w.crowd, Crowd::Momentum) {
            crowd_e = trend_pos;
        }
        // ONE price-impact rule for every crowd: pressure comes from the exposure TRADED this
        // session, never from the exposure held. A crowd that has been long for a month and is still
        // long is not buying, and a market it is not buying does not rise because of it.
        let eq_flow = w.crowd_impact * w_trend * (crowd_e - crowd_prev);
        crowd_prev = crowd_e;
        log_vol = w.vol_persist * log_vol + w.vol_of_vol * rng.randn();
        // TRANSIENT, deliberately: the kick multiplies THIS session's diffusive noise and never
        // enters `log_vol` — fed into the 0.99-persistent state it self-excites (log-vol responds
        // per session while the normalising `scale` EWMA lags ~140, so every expansion reads as
        // fresh declines and pumps itself; measured: vol 16% -> 45% at the setting that first
        // reaches the anchor). The lag-1 form is also the statistic the `leverage corr` row
        // grades; the multi-session persistence of real post-decline volatility is the spiral's
        // job, and the clustering rows hold the total.
        // THE ASYMMETRIC NOISE VOL (`noise_asym`): `z` is this session's diffusive draw, a unit
        // normal BY CONSTRUCTION, which is why the state it drives cannot be inflated by the
        // price the way a realized-scale or return-standardized input is (measured: those forms
        // self-excite — see PLAN item 11's map). Read before its own update, like the kick.
        // Level-preserving, the same convention `vol_norm` applies to the vol state.
        let z = rng.randn();
        let asym_m = if w.noise_asym <= 0.0 {
            1.0
        } else if w.noise_asym_cap > 0.0 {
            (asym_g - asym_norm).min(w.noise_asym_cap).exp()
        } else {
            (asym_g - asym_norm).exp()
        };
        let d_noise = news_damp * SIGMA_N * (log_vol - vol_norm).exp() * z * asym_m * mix;
        // read BEFORE this session's update, like the kick: the response is to PAST declines
        let vol_resp_m = if w.vol_resp > 0.0 {
            (w.vol_resp * vol_resp_s).exp()
        } else {
            1.0
        };
        let d_noise = if w.leverage > 0.0 {
            d_noise * (w.leverage * kick_s).exp()
        } else {
            d_noise
        };
        let d_noise = if w.vol_resp > 0.0 {
            d_noise * vol_resp_m
        } else {
            d_noise
        };
        // THE BUST SWING (item 25, see `bust_amp`). The level is the pre-step price against the
        // fundamental as this session left it -- information strictly before the step, like every
        // crowd's -- and the slow mean advances after the read. The state arms once per peak, off
        // a drawdown of 0.2 log under a peak `BUST_ARM` over the mean; the unwind is in progress
        // while its lows are under a year old (the NDX made one every six months through
        // 2000-02) and over once a year has passed without one (2003-06 was calm 70% under the
        // 2000 peak). The swing is repriced here, ahead of the step, and perceived fair carries
        // it too, so the step sees no gap from it.
        if w.bust_amp > 0.0 {
            let gap_now = eq_m.log_p - log_vbase;
            let lvl = gap_now - gap_mean;
            gap_mean += gap_mu * (gap_now - gap_mean);
            if eq_m.log_p >= eq_m.peak {
                peak_lvl = lvl;
            }
            if peak_lvl > BUST_ARM && eq_m.peak - eq_m.log_p > 0.2 {
                let armed = ((peak_lvl - BUST_ARM) / BUST_RAMP).min(1.0);
                if armed > bust_s {
                    bust_s = armed;
                    ep_low = eq_m.log_p;
                    since_low = 0;
                }
                peak_lvl = 0.0;
            }
            if eq_m.log_p < ep_low {
                ep_low = eq_m.log_p;
                since_low = 0;
            } else {
                since_low += 1;
            }
            bust_s *= if since_low <= DAYS_PER_YEAR {
                BUST_DECAY_NEAR
            } else {
                BUST_DECAY_AFTER
            };
            let m = 1.0 + BUST_RELIEF * bust_s;
            eq_m.stress_div = m;
            eq_m.drag_mult = 1.0 / m;
            bust_swing =
                BUST_PHI * bust_swing + (1.0 - BUST_PHI * BUST_PHI).sqrt() * bust_rng.randn();
            let priced = w.bust_amp * bust_s * bust_swing;
            bust_move = priced - bust_news;
            eq_m.log_p += bust_move;
            bust_news = priced;
        }
        if w.noise_asym > 0.0 {
            asym_a = NOISE_ASYM_ATTACK * asym_a - (1.0 - NOISE_ASYM_ATTACK) * z;
            asym_g = w.noise_asym_phi * asym_g + w.noise_asym * asym_a;
        }
        // The session's DIFFUSION SCALE, recorded for the range and satellite channels exactly
        // as the noise term above is built — news damp, vol state, leverage kick (read BEFORE
        // this session's update, like `d_noise` itself) — plus the jump branch's
        // sqrt(1 - jumpVar) mixing. Draw-free; 0.0 when both channels are off.
        let sess_sigma = if w.range_scale > 0.0
            || w.sat_beta > 0.0
            || w.overnight > 0.0
            || w.basket > 0
            || w.macro_panel > 0
            || w.vol_resp > 0.0
            || w.jump_resp > 0.0
        {
            let lev_mult = if w.leverage > 0.0 {
                (w.leverage * kick_s).exp()
            } else {
                1.0
            };
            let jv_mult = if w.jump_var > 0.0 {
                (1.0 - w.jump_var).sqrt()
            } else {
                1.0
            };
            news_damp
                * SIGMA_N
                * (log_vol - vol_norm).exp()
                * lev_mult
                * jv_mult
                * asym_m
                * vol_resp_m
                * mix
        } else {
            0.0
        };

        // The jump channel. Its draws come from `jrng`, NOT `rng`, so `jump_var = 0` takes the
        // untouched branch below and moves NOTHING ELSE in the path — the failure mode a shared
        // stream would have caused is not a risk that was reasoned about, it is one the branch
        // removes. (Through 0.21.0 that also made `-jumpvar 0` reproduce the pre-jump world bit for
        // bit; 0.22.0 changed the price-impact law, so the isolation claim now holds only WITHIN a
        // release.) `vol_mult` is this session's volatility state, so jumps
        // CLUSTER inside a stressed stretch instead of scattering uniformly, which is what turns a
        // fat tail into a survivable-or-not sequence for anything levered.
        let eq_shock = if w.jump_var <= 0.0 {
            d_noise
        } else {
            let vol_mult = (log_vol - vol_norm).exp();
            let lam_j = if w.jump_resp > 0.0 {
                (w.jump_resp * vol_resp_s).exp()
            } else {
                1.0
            };
            let lam_now = 0.25f64.min(w.jump_rate * vol_mult.powf(JUMP_GAMMA) * lam_j);
            let scale = jump_scale(w);
            // The compensator is deterministic and consumes no draw: it removes the mean the
            // downward shift would otherwise add, so `jump_var` moves the tail without moving drift.
            // With `jump_resp` on it must be CONDITIONAL — the intensity then correlates with past
            // declines, and an unconditional compensator would leave a systematic post-decline
            // return, i.e. manufactured TREND rather than the volatility response asked for
            // (measured: the 60-day variance ratio 1.07 -> 2.65).
            let compens = if w.jump_resp > 0.0 {
                lam_now * w.jump_skew * scale
            } else {
                w.jump_rate * w.jump_skew * scale
            };
            let fired = jrng.next_f64() < lam_now;
            let jump = if !fired {
                0.0
            } else {
                // Student-t with JUMP_NU degrees of freedom, standardised to unit variance, so the
                // size is set by `scale` alone. Drawn as z / sqrt(chi2(nu)/nu) — the draw ORDER
                // here is part of the cross-language contract, not an implementation detail.
                let z = jrng.randn();
                let mut chi = 0.0f64;
                for _ in 0..JUMP_NU {
                    let g = jrng.randn();
                    chi += g * g;
                }
                let nu = JUMP_NU as f64;
                let t = z / (chi / nu).sqrt() / (nu / (nu - 2.0)).sqrt();
                (t - w.jump_skew) * scale
            };
            jump_now = jump;
            d_noise * (1.0 - w.jump_var).sqrt() + jump + compens
        };
        // The shock, not the crowd's flows — see the `down_shock` field for the measured reason.
        let eq_shock = if w.down_shock > 0.0 {
            if eq_shock < 0.0 {
                eq_shock * (1.0 + w.down_shock)
            } else {
                eq_shock / (1.0 + w.down_shock)
            }
        } else {
            eq_shock
        };

        // ---- both markets step through the SAME mechanism ---------------------------------
        // THE SLOW VALUATION CYCLE: value capital arbs the gap to PERCEIVED fair, and perception
        // drifts toward realized prices with a `belief_years` half-life; the mania term
        // capitalizes `cap_years` of the fundamental's recent excess growth (read through a
        // `cap_window`-year EWMA, tanh-squashed at CAP_SPAN). At 60 sessions the belief has moved
        // ~5% of a gap, so daily reversion — and the variance-ratio band — are untouched; over
        // years the effective pull on a PERSISTENT gap falls to (1 - belief_share) of full
        // strength, which is what lets CAPE-scale swings build. A collapsing fundamental still
        // transmits at full strength — the belief lags it by years. Consumes no draws; at share 0
        // and cap 0 the perceived fair IS the fundamental, bit for bit.
        if w.cap_years > 0.0 {
            if i > 0 {
                g_ewma += g_mu * ((log_vbase - v_prev) - g_ewma);
            }
            v_prev = log_vbase;
        }
        let perceived_fair = if w.belief_share <= 0.0 && w.cap_years <= 0.0 {
            log_vbase
        } else {
            let mut pf = log_vbase;
            if w.belief_share > 0.0 {
                belief += belief_mu * ((eq_m.log_p - log_vbase) - belief);
                pf += w.belief_share * belief;
            }
            if w.cap_years > 0.0 {
                pf += CAP_SPAN
                    * tanh_p(w.cap_years * (g_ewma * DAYS_PER_YEAR as f64 - w.drift) / CAP_SPAN);
            }
            pf + bust_news
        };
        let s_pre = if w.leverage > 0.0 {
            eq_m.scale_var.sqrt()
        } else {
            0.0
        };
        if lev_on {
            // The ratio the index reads: borrowing over the equity securing it, the log drawdown
            // from the running peak — smoothed, a balance sheet not a tape — standing in for the
            // equity's fall. The spiral reads the stock's GROWTH over its trailing year: the
            // record's index rises into every classic peak and credit growth is what predicts
            // the crisis. A level or the ratio put the gain inside the drawdown, where the
            // spiral already amplifies, and deepened crashes instead of starting them (measured:
            // hazard 1.1-1.3 at kurtosis 50-150; the growth reads 1.6-1.7 at the calibrated 28).
            dd_s += macro_k::LEV_DD_K * ((eq_m.peak - eq_m.log_p) - dd_s);
            lev = borrow * (1.0 + dd_s);
            if w.lev_gain > 0.0 {
                eq_m.lev_mult =
                    (1.0 + w.lev_gain * (borrow - lev_slow)).max(macro_k::LEV_MULT_FLOOR);
            }
        }
        let ret_e = eq_m.step(perceived_fair, eq_flow + eq_shock);
        if w.vol_resp > 0.0 || w.jump_resp > 0.0 {
            // The REALIZED decline, in units of the sd that generated it, saturated at four like
            // the kick's and centred at a normal's E[max(-z,0)] so the state has mean zero and the
            // multiplier does not move the vol LEVEL. `sess_sigma` carries this session's own
            // multiplier, so a stretch the state has already made volatile reads no larger here:
            // scale-free by construction, where a trailing-scale denominator lags ~140 sessions
            // and self-excites. The numerator is the realized return rather than the shock on
            // purpose: the spiral's amplification is part of what real volatility responds to, and
            // a shock-only driver loses the skew the downside excess is measured from.
            let vr_u = ((news_j - ret_e).max(0.0) / (sess_sigma).max(1e-12)).min(4.0) - 0.399;
            // the ATTACK stage: at 0 the state receives the session's reading whole, which peaks
            // the response at lag 1; above 0 it receives a fast EWMA of it.
            if w.vol_resp_attack > 0.0 {
                vol_resp_a = w.vol_resp_attack * vol_resp_a + (1.0 - w.vol_resp_attack) * vr_u;
            }
            let vr_in = if w.vol_resp_attack > 0.0 {
                vol_resp_a
            } else {
                vr_u
            };
            // SATURATED, and the cap is what makes the accumulation safe rather than a nicety. The
            // state is unnormalized so that one decline's response is a plateau rather than a
            // divided integral, which means a stretch of saturated readings COMPOUNDS: the
            // realized return carries the spiral's amplification while `sess_sigma` does not, so
            // in a thin market the state raises volatility, the spiral amplifies harder, and the
            // reading grows again. The S&P default is stable without a cap; the Nasdaq recipe at
            // depth 8.4 ran to 49% volatility and 70 crashes a century.
            vol_resp_s = (w.vol_resp_phi * vol_resp_s + vr_in).min(w.vol_resp_cap);
        }
        if lev_on {
            // THE CREDIT CYCLE: a damped oscillator in the stock (its velocity persists for a
            // quarter, its level swings over years — the record's NFCILEVERAGE shape), driven by
            // its own innovation and paid down under the stress the step just read; then the
            // trailing-year average the growth is read against
            lev_vel += -macro_k::LEV_DAMP * lev_vel
                - macro_k::LEV_SPRING * (borrow - macro_k::LEV_MEAN)
                + lev_sd * lrng.randn();
            borrow = (borrow + lev_vel - macro_k::LEV_PAYDOWN * eq_m.stress_idx * borrow).max(0.0);
            lev_slow += macro_k::LEV_SLOW_K * (borrow - lev_slow);
        }
        if w.leverage > 0.0 {
            // SATURATED at four realized sds, and the cap is a priced trade, not a free guard:
            // uncapped, a jump day mints a 2.6x next-session multiplier and the kurtosis
            // ceiling flips on seed draws; capped, roughly a third of the graded correlation
            // goes with those co-extreme pairs (-0.09 -> -0.06 at leverage 0.05, measured) and
            // the dial is sized about 2x larger to buy it back. Real vol responses saturate;
            // uncapped ones let one draw author the tail.
            // The decline the signal reads INCLUDES this session's news jump: a bad-news day is
            // exactly the day real volatility responds to, and the external repricing bypasses
            // `ret_e` (it never passes through `step`). `news_j` is 0 whenever the channel is off,
            // so the pre-news leverage behaviour is untouched bit for bit.
            let lev_sig = ((news_j - ret_e).max(0.0) / s_pre).min(4.0) - 0.399;
            // THE PERSISTENT KICK (`lev_persist`): the same signal through an EWMA whose weights
            // sum to 1, so the integrated log-multiplier per unit decline is what it is today and
            // only its SHAPE over the following sessions changes. At 0 this is `lev_sig` itself.
            kick_s = w.lev_persist * kick_s + (1.0 - w.lev_persist) * lev_sig;
        }
        // joint-stress margin selling: when both markets are stressed, the bond gets dumped too —
        // and against it the refuge bid, flight-to-quality into a bond that is itself still
        // orderly. DURATION-SCALED, like the bond's own noise: an absolute bid gave a 5-year bond
        // the same crash rally as a 20-year one, which no duration-relative band can then fit.
        // The stress the REFUGE bid reads: settled (through yesterday) when `refuge_days` is on,
        // live otherwise — see the `refuge_days` field for why the same-session delta is the
        // whole calm-day correlation and the level is the whole crisis behaviour. The EWMA is
        // updated AFTER this use, so today's equity move never reaches today's bond bid.
        let eq_stress_for_refuge = if w.refuge_days > 0.0 {
            settled_stress
        } else {
            eq_m.stress_idx
        };
        let bond_flow = -w.margin * eq_m.stress_idx * bd_m.stress_idx
            + w.refuge
                * (w.duration / DURATION_REF)
                * eq_stress_for_refuge
                * 0.0f64.max(1.0 - bd_m.stress_idx);
        if w.refuge_days > 0.0 {
            settled_stress += settle_mu * (eq_m.stress_idx - settled_stress);
        }
        let _ret_b = bd_m.step(
            fair_b + slow_b,
            bond_flow + SIGMA_N_BOND * (w.duration / DURATION_REF) * rng.randn(),
        );

        px[i] = (eq_m.log_p - markdown).exp();
        fv[i] = (log_vbase - markdown).exp();
        rt[i] = rate;
        lq[i] = eq_m.last_liq;
        bq[i] = bd_m.last_liq;
        bp[i] = bd_m.log_p.exp();
        ip[i] = infl_press;
        log_cpi += (pi_base + infl_press) * dt;
        cp[i] = log_cpi.exp();
        if ch_on {
            ch.px[i] = eq_m.log_p - markdown;
            ch.d[i] = sess_sigma * eq_m.last_liq;
            // the satellite's and the sector's state: the primary's conditional vol -- the
            // diffusive state at its share beside the slow channel's variance, as the implied-vol
            // member reads it -- times the spiral's amplification. Without the channel's term a
            // world carrying its long-lag clustering in the channel gave the satellite none of it,
            // and the satellite's clustering-20 ratio left its band on every seed. The branch
            // keeps a channel-off world bit-identical: sqrt(x * x) is not always x.
            let vs = (log_vol - vol_norm).exp();
            let st = if w.slow_share > 0.0 {
                (vs * mix * vs * mix + slow_var).sqrt()
            } else {
                vs
            };
            ch.state[i] = st * eq_m.last_liq * w.depth / 12.0;
            ch.scale_var[i] = eq_m.scale_var;
            // the bust swing's same-session move is a repricing the derived channels must see,
            // like the news jump; guarded so the off state stays bit-identical
            ch.jump[i] = if w.bust_amp > 0.0 {
                jump_now * eq_m.last_liq - news_j + bust_move
            } else {
                jump_now * eq_m.last_liq - news_j
            };
            let vb = (log_vol - vol_norm).exp() * vol_resp_m * mix;
            ch.vol_state[i] = (vb * vb + slow_var).sqrt();
            ch.amp[i] = eq_m.last_liq * w.depth / 12.0;
        }
        if mc_on {
            mc.stress[i] = eq_m.stress_idx;
            mc.b_stress[i] = bd_m.stress_idx;
            // TIMES the vol response's multiplier: the implied vol reads the price process's own
            // conditional variance, and with `vol_resp` on the exogenous state is no longer all of
            // it. A member that misses a real vol component reads CALM while returns are
            // turbulent, which is the failure this column exists to avoid — measured, it drops the
            // variance risk premium out of its band.
            // PLUS the slow channel's variance: a member that misses a real vol component reads
            // CALM while returns are turbulent, which drops the variance risk premium out of its
            // band — measured, two macro rows fail without it.
            let vb = (log_vol - vol_norm).exp() * vol_resp_m * mix;
            mc.vol_state[i] = (vb * vb + slow_var).sqrt();
            mc.amp[i] = eq_m.last_liq * w.depth / 12.0;
            mc.acc[i] = acc;
            mc.w_trend[i] = w_trend;
            mc.lev[i] = lev;
            mc.borrow[i] = borrow;
            mc.rate[i] = rate;
            mc.infl[i] = infl_press;
            mc.log_cpi[i] = log_cpi;
            mc.log_fund[i] = log_vbase;
        }

        // ---- capital reallocation: spring, scored on positions actually held ---------------
        perf_v = 0.99 * perf_v + 0.01 * (mispricing_pre * ret_e) * 100.0;
        // POSITION HELD, where the price impact above is position TRADED — both are correct and
        // they are different questions. A crowd earns or loses on what it is holding; it moves the
        // price by what it is buying or selling. Conflating the two is the defect that shipped
        // through 0.21.0.
        let crowd_pos = match w.crowd {
            Crowd::Momentum => trend_pos,
            _ => crowd_e - 1.0,
        };
        perf_t = 0.99 * perf_t + 0.01 * (crowd_pos * ret_e) * 100.0;
        let e_t = 50.0f64.min(w.beta * perf_t).exp();
        let e_v = 50.0f64.min(w.beta * perf_v).exp();
        let target = e_t / (e_t + e_v);
        // redemptions fast, subscriptions slow
        let k_now = k_adapt * (1.0 + w.panic * eq_m.stress_idx);
        w_trend += k_now * (target - w_trend) + k_home * (w.trend_share - w_trend);
        // numerical guard; binding is REPORTED
        w_trend = 0.02f64.max(0.95f64.min(w_trend));
        if i >= BURN_IN {
            w_trend_sum += w_trend;
            if w_trend <= 0.02 + 1e-9 || w_trend >= 0.95 - 1e-9 {
                pinned_cnt += 1;
            }
            if target < 0.02 || target > 0.98 {
                sat_cnt += 1;
            }
            bond_stress_sum += bd_m.stress_idx;
            crowd_flow_sum += eq_flow.abs();
            if bd_m.stress_idx > 0.5 {
                bond_stress_hi += 1;
            }
        }
        if i == BURN_IN {
            clamps_at_burn = eq_m.clamps + bd_m.clamps;
            eq_floor_at_burn = eq_m.floor_days;
            eq_tail_at_burn = eq_m.tail_days;
            eq_halt_at_burn = eq_m.halt_days;
        }
        i += 1;
    }

    let nf = n as f64;
    let path = Path {
        price: px[BURN_IN..].to_vec(),
        rate: rt[BURN_IN..].to_vec(),
        fundamental: fv[BURN_IN..].to_vec(),
        liq: lq[BURN_IN..].to_vec(),
        bliq: bq[BURN_IN..].to_vec(),
        bond: bp[BURN_IN..].to_vec(),
        infl_press: ip[BURN_IN..].to_vec(),
        cpi: cp[BURN_IN..].to_vec(),
        mean_trend_share: w_trend_sum / nf,
        trend_pinned: pinned_cnt as f64 / nf,
        target_sat: sat_cnt as f64 / nf,
        clamped_days: eq_m.clamps + bd_m.clamps - clamps_at_burn,
        eq_floor_days: eq_m.floor_days - eq_floor_at_burn,
        eq_tail_days: eq_m.tail_days - eq_tail_at_burn,
        eq_halt_days: eq_m.halt_days - eq_halt_at_burn,
        mean_bond_stress: bond_stress_sum / nf,
        pct_bond_stress: bond_stress_hi as f64 / nf,
        duration: w.duration,
        mean_crowd_flow: crowd_flow_sum / nf,
        disasters: disaster_count,
        sat: Vec::new(),
        log_hi: Vec::new(),
        log_lo: Vec::new(),
        log_volume: Vec::new(),
        div_yield: Vec::new(),
        traded: Vec::new(),
        log_open: Vec::new(),
        names: Vec::new(),
        chan_k: 0.0,
        chan_k_sat: 0.0,
        chan_k_div: 0.0,
        chan_k_vs: 0.0,
        chan_k_iv: 0.0,
        chan_k_dr: 0.0,
        macro_panel: None,
    };
    Priced {
        path,
        inputs: ch,
        macro_in: mc,
    }
}

// ---- stylised-fact measurements ---------------------------------------------------------

fn daily_returns(px: &[f64]) -> Vec<f64> {
    (0..px.len() - 1)
        .map(|i| (px[i + 1] / px[i]).ln())
        .collect()
}

/// mean(z^4) / mean(z^2)^2 for z = r - mean(r) — written as the formula it implements.
fn kurtosis(r: &[f64]) -> f64 {
    let m = MatD::apply(r);
    let z = &m - m.mean();
    let m2 = z.power(2).mean();
    if m2 <= 0.0 {
        f64::NAN
    } else {
        z.power(4).mean() / (m2 * m2)
    }
}

/// sum(z_t * z_(t+lag)) / sum(z_t^2) for z = |r| - mean|r| — volatility clustering.
/// THE LEVERAGE-EFFECT PROFILE (item 12): corr(r_t, |r_{t+lag}|) — how much of the next sessions'
/// volatility a decline predicts. The signed sibling of `autocorr_abs`, and the statistic
/// `amplifier-2026-09-07.tsv` measures on the record: -0.09 / -0.11 / -0.08 / -0.06 at lags
/// 1 / 2 / 5 / 10 on the CRSP century, where a market whose vol responds only to the NEXT session
/// reads its lag-1 value and then nothing.
fn lev_abs(r: &[f64], lag: usize) -> f64 {
    if r.len() <= lag {
        return f64::NAN;
    }
    let b: Vec<f64> = r[lag..].iter().map(|x| x.abs()).collect();
    pearson(&r[..r.len() - lag], &b)
}

fn autocorr_abs(r: &[f64], lag: usize) -> f64 {
    autocorrs_abs(r, &[lag])[0]
}

/// `autocorr_abs` at several lags, sharing what does not depend on the lag -- |r|, its centring and
/// the denominator -- which a caller asking for four lags otherwise builds four times.
fn autocorrs_abs(r: &[f64], lags: &[usize]) -> Vec<f64> {
    let a = MatD::apply(r).abs();
    let z = &a - a.mean();
    let den = z.power(2).sum();
    let n = r.len();
    lags.iter()
        .map(|&lag| {
            if den <= 0.0 || n <= lag {
                f64::NAN
            } else {
                // Scala writes these as z(0 until n-lag, 0) and z(lag until n, 0); on an n x 1
                // column those are exactly row slices.
                (&z.applyRowsAll(0..n - lag) * &z.applyRowsAll(lag..n)).sum() / den
            }
        })
        .collect()
}

/// Var(sum of q consecutive returns) / (q * Var(r)) on SIGNED returns: 1.0 under no serial
/// dependence at that horizon, above 1 for trend, below for mean reversion. The two `clustering`
/// rows measure |r| and are blind to this — a world can cluster its volatility exactly right while
/// manufacturing a trend no market has, and one did for four releases.
///
/// WHY A VARIANCE RATIO AND NOT AN AUTOCORRELATION. A signed autocorrelation at any single lag
/// cannot see this defect: the shipped-0.21.0 world reads about +0.01 at every lag out to 60, which
/// is inside the sampling noise of a 100-year path and would pass a per-lag check at every lag
/// separately. They are all the SAME SIGN, so they accumulate — the 60-session variance is 52%
/// above iid while no single lag looks unusual.
///
/// CONVENTION, stated for the same reason `clustering lag 1` states one, because "variance ratio"
/// names several estimators that disagree in small samples: NON-OVERLAPPING q-blocks, sample
/// variances (n-1), the series truncated to a whole number of blocks.
/// PHASE-AVERAGED over every block offset. Non-overlapping blocks have to start somewhere, and on
/// a century of daily data that arbitrary choice is worth as much as the statistic: the record's
/// own q = 60 reading spans 1.06 to 1.33 across the 60 possible offsets, q = 250 spans 0.96 to
/// 1.61, and the shipped rows sat at or near the top of that range on three rungs of four. One
/// observation of phase is what separated two vintages of `persistence-*.tsv`. Averaging over all
/// q offsets removes a free parameter nobody chose deliberately; it costs a factor of q in
/// arithmetic on an O(n) statistic and nothing in interpretation, because each offset estimates
/// the same quantity.
fn variance_ratio(r: &[f64], q: usize) -> f64 {
    let len = r.len();
    let n = len / q * q;
    if q < 2 || n < 2 * q {
        return f64::NAN;
    }
    // PREFIX SUMS, so averaging over all q offsets costs ONE pass rather than q. Written naively
    // it was q passes per rung, which across the four rungs is 450 passes where the unaveraged
    // form took 4: a measured 1.6x on the whole gate, and the same on every evaluation a search
    // makes. With running sums of r and r^2 every block sum and every slice's variance is O(1),
    // so the phase average costs what one phase used to.
    let mut s = vec![0.0f64; len + 1];
    let mut s2 = vec![0.0f64; len + 1];
    for i in 0..len {
        s[i + 1] = s[i] + r[i];
        s2[i + 1] = s2[i] + r[i] * r[i];
    }
    // Sample variance of `r[a..b]` from the running sums.
    let slice_var = |a: usize, b: usize| -> f64 {
        let m = b - a;
        if m < 2 {
            return f64::NAN;
        }
        let mu = (s[b] - s[a]) / m as f64;
        ((s2[b] - s2[a]) - m as f64 * mu * mu) / (m - 1) as f64
    };
    let at = |off: usize| -> f64 {
        let m = (len - off) / q * q;
        if m < 2 * q {
            return f64::NAN;
        }
        let nb = m / q;
        let blocks: Vec<f64> = (0..nb)
            .map(|k| s[off + (k + 1) * q] - s[off + k * q])
            .collect();
        let b_mu = blocks.iter().sum::<f64>() / nb as f64;
        let b_var = blocks.iter().map(|x| (x - b_mu) * (x - b_mu)).sum::<f64>() / (nb - 1) as f64;
        let v_daily = slice_var(off, off + m);
        // positive test rather than a negated one: a NaN daily variance falls to the NaN arm
        if v_daily > 0.0 {
            b_var / (q as f64 * v_daily)
        } else {
            f64::NAN
        }
    };
    // fixed order, so the twins sum the same doubles in the same sequence
    let vs: Vec<f64> = (0..q).map(at).filter(|v| v.is_finite()).collect();
    if vs.is_empty() {
        f64::NAN
    } else {
        vs.iter().sum::<f64>() / vs.len() as f64
    }
}

/// cov(a,b) / (sigma_a * sigma_b), in unnormalised sums — written as the formula.
fn pearson(a: &[f64], b: &[f64]) -> f64 {
    if a.len() < 50 {
        return f64::NAN;
    }
    let ma = MatD::apply(a);
    let za = &ma - ma.mean();
    let mb = MatD::apply(b);
    let zb = &mb - mb.mean();
    let den = (za.power(2).sum() * zb.power(2).sum()).sqrt();
    if den <= 0.0 {
        f64::NAN
    } else {
        (&za * &zb).sum() / den
    }
}

/// `recovered < 0` marks an episode still under water at path end: depth known, shape not.
#[derive(Clone, Copy, Debug)]
struct Episode {
    peak: usize,
    trough: usize,
    recovered: i64,
    depth_pct: f64,
}

impl Episode {
    fn censored(self) -> bool {
        self.recovered < 0
    }
    fn fall_days(self) -> i64 {
        self.trough as i64 - self.peak as i64
    }
    fn rebound_days(self) -> i64 {
        self.recovered - self.trough as i64
    }
    fn shape(self) -> f64 {
        if self.censored() || self.rebound_days() <= 0 {
            f64::NAN
        } else {
            self.fall_days() as f64 / self.rebound_days() as f64
        }
    }
}

fn episodes(px: &[f64], min_dec_pct: f64) -> Vec<Episode> {
    let mut out: Vec<Episode> = Vec::new();
    let mut pk = px[0];
    let mut pk_i = 0usize;
    let mut i = 1usize;
    while i < px.len() {
        if px[i] >= pk {
            pk = px[i];
            pk_i = i;
            i += 1;
        } else {
            let mut j = i;
            let mut tro = i;
            while j < px.len() && px[j] < pk {
                if px[j] < px[tro] {
                    tro = j;
                }
                j += 1;
            }
            let dec = (px[tro] / pk - 1.0) * 100.0;
            if dec <= -min_dec_pct {
                // censored INCLUDED
                out.push(Episode {
                    peak: pk_i,
                    trough: tro,
                    recovered: if j < px.len() { j as i64 } else { -1 },
                    depth_pct: dec,
                });
            }
            if j < px.len() {
                pk_i = j;
                pk = px[j];
                i = j + 1;
            } else {
                i = px.len();
            }
        }
    }
    out
}

/// Share of sessions spent more than 5%, 10% and 20% below the running peak — the DEPTH
/// DISTRIBUTION, which volatility, maximum drawdown and underwater fraction between them do
/// not pin. Two series can agree on all three of those and still differ here: one drifts far
/// below its peak and stays, the other hugs it and makes new highs. Every rule that reads
/// distance from a running peak is a different rule on the two.
///
/// Computed on prices directly rather than through [`drawdown_series`]' log/exp round trip.
/// The ratio is exact in both languages, so a threshold comparison cannot land on opposite
/// sides of a 1-ulp `log` gap; a count is the one reduction where that would show up as a
/// whole session. One pass for all three depths.
fn depth_shares(px: &[f64]) -> (f64, f64, f64) {
    let mut pk = px[0];
    let (mut n5, mut n10, mut n20) = (0usize, 0usize, 0usize);
    for &p in px {
        if p > pk {
            pk = p;
        }
        let d = 1.0 - p / pk;
        if d > 0.05 {
            n5 += 1;
        }
        if d > 0.10 {
            n10 += 1;
        }
        if d > 0.20 {
            n20 += 1;
        }
    }
    let n = px.len() as f64;
    (n5 as f64 / n, n10 as f64 / n, n20 as f64 / n)
}

// ---- world statistics and the ONE acceptance predicate ----------------------------------

/// The SATELLITE leg's own statistics, as RATIOS to the primary leg's — present only when the
/// leg ran. Ratios, not levels, because the satellite is a coupled second leg at this world's
/// own scale and is not claimed to BE any index: what a second, higher-beta leg must satisfy is
/// a RELATION to its primary, the same doctrine the depth rungs use when they grade each world
/// at its own volatility. The record's relation is QQQ against SPY over their shared window.
#[derive(Clone, Copy, Debug)]
pub struct SatStats {
    pub corr: f64,
    pub abs_corr: f64,
    pub beta: f64,
    pub vol_ratio: f64,
    pub kurt_ratio: f64,
    pub ac1_ratio: f64,
    pub ac20_ratio: f64,
    pub d5_ratio: f64,
    pub d10_ratio: f64,
    pub crash_ratio: f64,
}

/// The BAR channels' statistics — present only when the range channel ran. `vol_*` are NaN
/// unless the volume channel ran too. Graded against `bars-2026-09-01.tsv`, whose rows the
/// build-time suites already assert; these carry the same readings into the RUNTIME gate, so an
/// emitted `logHigh`/`logLow`/`logVolume` is covered by the verdict travelling beside it.
#[derive(Clone, Copy, Debug)]
pub struct BarStats {
    pub range_over_ccvol: f64,
    pub range_acf1: f64,
    pub range_downup: f64,
    pub vol_sd: f64,
    pub vol_corr_range: f64,
}

#[derive(Clone, Copy, Debug)]
pub struct WorldStats {
    pub vol: f64,
    /// THE TYPICAL YEAR (item 24): the median calendar-year vol, the row that separates an
    /// ordinary year from an episode.
    pub year_vol: f64,
    pub kurt: f64,
    pub ac1: f64,
    pub ac20: f64,
    /// THE VOL-RESPONSE PROFILE (item 12), reported beside the two graded clustering lags: |r|
    /// autocorrelation at 5 and 60, and the leverage-effect profile at 1, 5 and 20.
    pub ac5: f64,
    pub ac60: f64,
    pub lev1: f64,
    pub lev5: f64,
    pub lev20: f64,
    /// SIGNED-return persistence — `variance_ratio`.
    pub vr20: f64,
    pub vr60: f64,
    pub vr120: f64,
    pub vr250: f64,
    /// SIGNED lag-1 autocorrelation, the one horizon the ladder cannot see: a variance ratio
    /// constrains a weighted SUM of the first q-1 autocorrelations, so a world can hold vr60 at
    /// 1.0 with a positive first term paid for by negatives further out, and `ac1` above reads
    /// |r| and is blind to sign. REPORTED, never graded: the record's own sign flips by era
    /// (CRSP +0.047 over the century, +0.023 from 1954, -0.058 from 1990, every modern fund
    /// negative), so there is no one value to grade against.
    pub ret_ac1: f64,
    pub ann_ret: f64,
    pub n_episodes: usize,
    pub ep_per_path: f64,
    /// `None` when no satellite leg ran — the gate then carries no satellite rows at all,
    /// which is what keeps a satellite-off world's verdict byte-identical.
    pub sat: Option<SatStats>,
    /// `None` when no range channel ran.
    pub bars: Option<BarStats>,
    /// `None` when no open ran.
    pub open: Option<OpenStats>,
    /// `None` when no basket ran.
    pub basket: Option<BasketStats>,
    /// the macro panel's readings when it ran
    pub macro_panel: Option<MacroStats>,
    /// median across paths of the per-path mean session yield, %/yr; NaN when the dial is off,
    /// and the gate then carries no row
    pub div_yield_mean: f64,
    pub depth_med: f64,
    pub worst_depth: f64,
    pub v_count: usize,
    pub mid_count: usize,
    pub u_count: usize,
    pub n_shapes: usize,
    pub censored: usize,
    pub clamp_pct: f64,
    /// Share of equity sessions the halt bound.
    pub halt_pct: f64,
    /// Share of EQUITY tail sessions sitting ON the downward guard: the guard's grip on the tail,
    /// which `clamp_pct` cannot see.
    pub tail_floor_pct: f64,
    pub trend_share: f64,
    pub years_per_path: f64,
    pub trend_pinned: f64,
    pub target_sat: f64,
    pub bond_vol: f64,
    pub bond_growth: f64,
    pub bond_infl: f64,
    pub corr_calm: f64,
    pub corr_infl: f64,
    /// kept for parity with the Scala record; not printed
    pub mean_bond_stress: f64,
    pub pct_bond_stress: f64,
    pub crowd_flow: f64,
    pub dis_per_century: f64,
    /// median per-path sd of log(price/fundamental): the valuation-gap dispersion the record
    /// proxies with CAPE (valuation-2026-08-30.tsv)
    pub val_disp: f64,
    /// THE WINGS (item 25): the share of sessions the valuation level spends more than 0.5 log
    /// above / below its own 20-year mean (`wings_of`); the record's CAPE reads 0.076 / 0.067.
    pub wing_up: f64,
    pub wing_down: f64,
    /// median per-path MAX log overvaluation — the mania a century produces
    pub max_over: f64,
    /// median per-path 100*(sqrt(sum r^2 | r<0 / sum r^2 | r>0) - 1), tau = 0: how much more the
    /// downside disperses than the upside (Roy 1952 / Markowitz 1959; asymmetry-2026-08-31.tsv)
    pub semi_excess: f64,
    /// median per-path corr(r_t, r^2_{t+1}) — the leverage effect at daily lag. The sharper
    /// signed-half block regression (Patton-Sheppard) was measured and CANNOT anchor on
    /// close-only data: era-split with the sign flipping (asymmetry-2026-08-31.tsv), the
    /// longhorizon-2026-08-30 lesson again. This correlation reads -0.09 on every CRSP era.
    pub lev_corr: f64,
    /// median per-path stock-bond corr on CALM sessions with the equity return below its own
    /// calm q10 — does the refuge hold exactly where it is needed (tailcorr-2026-08-31.tsv).
    /// Calm-conditioned because the record window (TLT's history) is a disinflation era
    /// throughout; a century pooling inflation regimes is not comparable on any column.
    pub tail_hedge: f64,
    pub duration: f64,
    pub infl_ann: f64,
    /// depth profile: median share of sessions more than 5/10/20% below the running peak,
    /// equity leg then bond leg
    pub dd_eq5: f64,
    pub dd_eq10: f64,
    pub dd_eq20: f64,
    pub dd_bd5: f64,
    pub dd_bd10: f64,
    pub dd_bd20: f64,
}

/// Real equity funds' time under water, stated against the part a random walk already explains.
///
/// For a geometric random walk the share of sessions more than `rung` below the running peak has a
/// closed form: `exp(-2 * (mu/sigma^2) * ln(1/(1-rung)))`, which in the units this report already
/// carries is `exp(-2 * ret_vol * ln(1/(1-rung)) / vol)`. It is EXACT, not fitted, and that is what
/// makes it the right carrier for the return dependence. The model runs at a return per unit
/// volatility near 0.8 while the anchor funds span 0.20-0.53, so a fitted `rv` coefficient
/// evaluated at the model's own operating point is arithmetic with nothing behind it. A closed form
/// is not.
///
/// What real markets add is that they make new highs SOONER than chance, and increasingly so the
/// calmer they are. That correction is the fitted part: linear in volatility, one pair per rung,
/// from `test-data/equity-anchors` (35 instruments, 2001-2026, peaks seeded from full prior
/// history). The reason to believe the FORM rather than just the fit: all three rungs independently
/// reach 1.00 at the top of the real volatility range. The most volatile equity markets spend
/// random-walk time under water; a market at 14% volatility spends about half of it.
///
/// Fitted by least squares on the LOG ratio, because the quantity is graded AS a ratio. On OLS over
/// the raw ratio the deep rung's line is pulled up by a two-instrument dot-com tail (XLK, QQQ)
/// until the median real instrument sits at 0.91 of it — and a target of 1.00 against that line
/// would once again ask the model to be deeper than a typical real fund, which is the defect this
/// relation exists to remove. On the log fit every rung's median real row is 1.00.
const EQUITY_D5_CORR: (f64, f64) = (0.4003, 0.01628);
const EQUITY_D10_CORR: (f64, f64) = (0.1861, 0.02196);
const EQUITY_D20_CORR: (f64, f64) = (-0.0544, 0.02759);

/// Share of sessions more than `rung` below the running peak for a geometric random walk with this
/// volatility and return per unit volatility. Closed form; nothing here is fitted.
fn gbm_depth_share(rung: f64, vol_pct: f64, ret_vol: f64) -> f64 {
    if vol_pct <= 0.0 {
        f64::NAN
    } else {
        (-2.0 * ret_vol * (1.0 / (1.0 - rung)).ln() / (vol_pct / 100.0)).exp()
    }
}

/// What a real equity fund of this volatility and return spends more than `rung` below its peak.
/// NaN where the correction is non-positive — below ~2% volatility for the deep rung, far under any
/// equity this relation was fitted from, but a ratio against a non-positive prediction is not a
/// finding and must not print as one.
fn equity_depth_expected(rung: f64, corr: (f64, f64), vol_pct: f64, ret_vol: f64) -> f64 {
    let c = corr.0 + corr.1 * vol_pct;
    if c <= 0.0 {
        f64::NAN
    } else {
        c * gbm_depth_share(rung, vol_pct, ret_vol)
    }
}

/// The volatility range the anchor instruments covered, in %. Outside it the correction is a line
/// extended past its evidence, so both graders refuse rather than manufacture a verdict — the same
/// refusal the bond relations already make. `equity_anchor_tests` pins this to the fixture's own
/// min and max.
const EQUITY_VOL_SUPPORT: (f64, f64) = (14.3, 37.4);

/// Bands for the two graded rungs, shared by the acceptance gate and `-crossasset`. Each is the
/// observed residual-ratio range over BOTH windows, rounded outward to the nearest 0.05 — 0.785
/// (DIA) to 1.254 (EWJ) at the 5% rung, 0.719 (XLY) to 1.520 (XLK) at the 10% — because these funds
/// ARE the scope, unlike the bond bands where the range is a scope decision that excludes high
/// yield. A band that excluded one of them would be calling a real equity fund unrealistic.
///
/// The 20% rung is deliberately NOT gated. Its relation does not transport (R^2 0.25-0.41 to the
/// independent window, against 0.66-0.73 for the other two) and a band admitting every real
/// instrument would have to span 0.35-2.60, which cannot fail: that is a check that reads as
/// verification while testing nothing. It stays a fit target and a reported number.
const EQUITY_D5_BAND: (f64, f64) = (0.75, 1.30);
const EQUITY_D10_BAND: (f64, f64) = (0.70, 1.55);

/// The five real Treasury funds' fit of time-spent-more-than-10%-under-water against volatility:
/// `d10 = BOND_D10_SLOPE * vol% + BOND_D10_INTERCEPT`, floored at zero. Named rather than written
/// inline because `-crossasset` needs the line's zero crossing, and a second literal for it would
/// be a number free to drift away from the line it describes.
const BOND_D10_SLOPE: f64 = 0.0397;
const BOND_D10_INTERCEPT: f64 = -0.0785;

/// Volatility (%) at which the line above reaches zero. Below it the relation has NO VALUE — a
/// ratio against a non-positive prediction — which makes its usable range narrower than the
/// 1.44-14.12% range it was fitted across. Real funds at that end read `d10 = 0.000` exactly, and
/// 0/0 is not agreement.
const BOND_D10_ZERO: f64 = -BOND_D10_INTERCEPT / BOND_D10_SLOPE;

/// The `bond depth vs vol` band, shared by the acceptance gate and `-crossasset` so the two cannot
/// drift apart. 1.0 +- 0.35 is WIDER than the five Treasuries' own scatter (0.79-1.04) on purpose:
/// the band is a SCOPE decision as much as a tolerance. It admits the Aggregate (1.06) and
/// investment grade (0.71) and excludes high yield (0.50), which this model has no channel for.
const BOND_D10_BAND: (f64, f64) = (0.65, 1.35);

/// The `bond vol x duration` band, shared for the same reason. Treasuries run 0.798-0.973 and
/// investment grade 0.824; high yield's 2.001 is deliberately outside.
const BOND_VOL_PER_YEAR_BAND: (f64, f64) = (0.70, 1.10);

/// The range the anchor funds actually covered, per driving variable: Treasury durations in years,
/// and Treasury annualised volatility in %. Outside these an anchor-fitted band is arithmetic with
/// nothing behind it, so BOTH graders — the acceptance gate and `-crossasset` — refuse to grade
/// there rather than manufacture agreement or a defect. The bond-anchor tests pin each pair to the
/// min/max of the fixture's Treasury rows, so a re-measured fund moves them or fails the build.
const BOND_DUR_SUPPORT: (f64, f64) = (1.80, 14.89);
const BOND_VOL_SUPPORT: (f64, f64) = (1.44, 14.12);

/// The horizon the variance ratio is graded at, in sessions — three months. The choice is not free
/// and it is not the flattering one: q = 20 would let the 0.21.0 world through (its reading overlaps
/// the CRSP century's 1.166), and q = 252 is too noisy to band (real readings run 0.27-1.45). At 60
/// the real record is tight and that world is outside all of it.
///
/// It is also the momentum crowd's own lookback, which is the mechanism the row exists to hold
/// accountable. That is the direction that matters: a horizon chosen to spare the mechanism would be
/// a longer or shorter one, and both were available.
const VAR_RATIO_Q: usize = 60;
/// The ladder `-validate` prints and the profile row grades — the four horizons of
/// `persistence-2026-09-11.tsv`. `VAR_RATIO_Q` is the rung the loss row reads.
const VAR_RATIO_LADDER: [usize; 4] = [20, 60, 120, 250];

/// The variance-ratio envelopes, from `test-data/equity-anchors/persistence-2026-09-11.tsv`: 18 real
/// equity funds over their full histories and over the depth cross-section's own 2001-2026 window,
/// plus the CRSP value-weighted market opening in 1926, 1954 and 1990, at four horizons. At 60
/// sessions the 39 readings span 0.547 (XLV, 2001-2026) to 1.175 (the CRSP century), and each
/// envelope is its rung's range rounded outward to the nearest 0.05. The persistence-anchor tests
/// re-derive every bound from the file by that rule, so a band cannot be widened to admit a world
/// without a real market moving first.
///
/// SHARED across anchor sets rather than carried per asset, unlike the two bands in `Anchors`. What
/// separates these readings is the ERA, not the index: QQQ reads 0.720 against SPY's 0.705 over
/// their full histories, while the same market reads 1.14 over the century and 0.82 since 1990.
/// Per-rung envelopes of the real cross-section — 39 readings, 18 instruments over two windows
/// and three CRSP eras — the observed range rounded outward to 0.05, re-derived by
/// `persistence_anchor_tests`. The two long rungs cannot discriminate: at 250 sessions the record
/// itself spans 0.24-1.56. They are graded anyway, inside ONE profile row with the slopes below,
/// so a world clears the ladder as a shape and never rung by rung.
const VAR_RATIO_BANDS: [(usize, f64, f64); 4] = [
    (20, 0.70, 1.15),
    (60, 0.55, 1.20),
    (120, 0.45, 1.20),
    (250, 0.45, 1.30),
];
/// Adjacent-rung slopes vr(60)-vr(20) and vr(120)-vr(60), the cross-section's range rounded
/// outward: the profile's SHAPE, which four boxes cannot see — a world at 0.70 and 1.15 on the two
/// short rungs sits inside both boxes and outside every real profile. Both tightened when the
/// rungs became phase-averaged, the 60->120 slope from -0.30..0.20 to -0.15..0.15: a third of the
/// record's apparent shape variation was block alignment.
/// The record's lag-1 signed autocorrelation, quoted in the report: the three CRSP eras, then the
/// modern funds' range. Not derived from anything here — these are `persistence-2026-09-11.tsv`'s
/// own `ac1` readings, and `persistence_anchor_tests` checks they still are. A printed claim that
/// no longer follows from the file is worse than no claim, which is the same reason the envelope
/// row carries its own bounds in its name.
const RET_AC1_RECORD: (f64, f64, f64, f64, f64) = (0.0471, 0.0232, -0.0577, -0.1058, -0.0180);

const VAR_RATIO_SLOPE_BANDS: [(usize, usize, f64, f64); 2] =
    [(20, 60, -0.20, 0.10), (60, 120, -0.15, 0.15)];

impl WorldStats {
    /// Return per unit volatility, in the units this report already prints: `ann_ret` is a LOG
    /// return in %/yr and `vol` is a fraction. An arithmetic-mean anchor is higher by about
    /// sigma/2 (0.08 at 16% vol) and has to be restated before it can be compared with this.
    /// Bond volatility per year of duration. Real funds, 19-24 years each: Treasuries 0.798 (SHY)
    /// to 0.973 (IEF), the US Aggregate 0.745, investment-grade credit 0.824, high yield 2.001 —
    /// credit is the only thing that breaks the relationship, and this model has no credit channel.
    /// Judging bond volatility on this ratio rather than an absolute band is what lets one gate
    /// cover every duration instead of only the one the anchor was built from.
    fn bond_vol_per_year(&self) -> f64 {
        if self.duration <= 0.0 {
            f64::NAN
        } else {
            self.bond_vol * 100.0 / self.duration
        }
    }

    /// Time spent >10% below the running peak, RELATIVE to what this bond's own volatility implies.
    /// The five real Treasury funds fit `d10 = 0.0397 * vol - 0.0785` (floored at zero) across a
    /// 1.44-14.12% volatility range; 1.0 means the bond is under water as long as a real bond of the
    /// same volatility. Replaces a fixed 0.510, which was TLT's number and false for every other
    /// bond — the real range across eight funds is 0.000 to 0.499.
    fn bond_depth_vs_vol(&self) -> f64 {
        // NOT `mul_add`: that fuses to a single rounding, the Scala twin's `a * b + c` rounds
        // twice, and the two disagree in the last ulp — a parity break for a formatting nicety.
        let expected = (BOND_D10_SLOPE * (self.bond_vol * 100.0) + BOND_D10_INTERCEPT).max(0.0);
        if expected <= 0.0 {
            f64::NAN
        } else {
            self.dd_bd10 / expected
        }
    }

    /// Time spent more than a rung below the running peak, RELATIVE to what a real equity fund of
    /// this world's OWN volatility and return per unit volatility spends — see `EQUITY_D10_CORR`.
    /// 1.0 means the market is under water as long as a real one it could be mistaken for.
    ///
    /// Replaces three absolute levels that were SPY's, measured at SPY's operating point (18.6%
    /// volatility, 0.55 return per vol) while the same target set asks this model to run at 16% and
    /// 0.69. Real funds at THAT point spend 1.11x / 1.33x / 1.64x less time under water than SPY's
    /// levels demanded, so the old targets could only be met by a market too deep for its own
    /// volatility — and were.
    fn eq_depth_vs_real(&self, rung: f64, corr: (f64, f64), got: f64) -> f64 {
        let expected = equity_depth_expected(rung, corr, self.vol * 100.0, self.ret_vol());
        if expected.is_nan() || expected <= 0.0 {
            f64::NAN
        } else {
            got / expected
        }
    }

    fn eq_d5_vs_real(&self) -> f64 {
        self.eq_depth_vs_real(0.05, EQUITY_D5_CORR, self.dd_eq5)
    }

    fn eq_d10_vs_real(&self) -> f64 {
        self.eq_depth_vs_real(0.10, EQUITY_D10_CORR, self.dd_eq10)
    }

    fn eq_d20_vs_real(&self) -> f64 {
        self.eq_depth_vs_real(0.20, EQUITY_D20_CORR, self.dd_eq20)
    }

    fn ret_vol(&self) -> f64 {
        if self.vol <= 0.0 {
            f64::NAN
        } else {
            self.ann_ret / (self.vol * 100.0)
        }
    }
}

/// Scala's `.sum` on a `Seq[Double]`, which folds from `Numeric[Double].zero` — **+0.0**.
///
/// Rust's `Iterator::sum` for floats folds from **-0.0** instead. That is a deliberate std
/// choice (it makes the identity preserve the sign when every element is `-0.0`), but it
/// means an EMPTY sum is `-0.0` in Rust and `+0.0` in Scala. Invisible until a zero reaches
/// a report column, where it prints as `-0.0` on one side and `0.0` on the other — which is
/// exactly how it was found, in the share-of-time columns of the buffer report where no
/// stretch exceeded the threshold.
fn scala_sum(it: impl Iterator<Item = f64>) -> f64 {
    it.fold(0.0, |a, b| a + b)
}

/// Scala's `.sorted` on `Double` uses `TotalOrdering`; `total_cmp` is its exact counterpart.
fn sorted_total(v: &[f64]) -> Vec<f64> {
    let mut s = v.to_vec();
    s.sort_by(|a, b| a.total_cmp(b));
    s
}

/// `is_finite`, not `!is_nan`: an infinite path is no more a datum than a NaN one, and `pctile`
/// drops the same set, so a median and the percentiles printed beside it describe the same paths.
/// The finite entries, sorted by `total_cmp`, in ONE owned copy. Entries that compare equal under
/// `total_cmp` are bit-identical, so an unstable sort puts the same double at every index a stable
/// one does -- and `pctile` then indexes a sort it made once rather than two copies deep.
fn finite_sorted(v: &[f64]) -> Vec<f64> {
    let mut f: Vec<f64> = v.iter().copied().filter(|x| x.is_finite()).collect();
    f.sort_unstable_by(f64::total_cmp);
    f
}

/// `pctile`'s index rule on a series `finite_sorted` has already prepared, so a caller wanting
/// several quantiles of one series sorts it once.
fn pctile_of(sorted: &[f64], q: f64) -> f64 {
    if sorted.is_empty() {
        f64::NAN
    } else {
        sorted[((sorted.len() as f64 * q) as usize).min(sorted.len() - 1)]
    }
}

/// THE TYPICAL YEAR (item 24): the median over whole `DAYS_PER_YEAR`-session blocks of each
/// block's own annualised RMS vol — `vol`, read one year at a time — so the row separates the
/// ordinary year from the episodes the pooled row cannot tell apart. Whole blocks from the
/// path's start; shorter than a block, the pooled vol. Sequential sums, so the twins agree bit
/// for bit.
fn year_vol_of(r: &[f64]) -> f64 {
    let dpy = DAYS_PER_YEAR as f64;
    let w = DAYS_PER_YEAR;
    let nb = r.len() / w;
    if nb < 1 {
        return (MatD::apply(r).power(2).mean() * dpy).sqrt();
    }
    let v: Vec<f64> = (0..nb)
        .map(|k| {
            let mut s = 0.0;
            for x in &r[k * w..(k + 1) * w] {
                s += x * x;
            }
            (s / w as f64 * dpy).sqrt()
        })
        .collect();
    med(&v)
}

/// THE WINGS (item 25): the share of sessions the valuation level — log(price / fundamental)
/// minus its own EWMA with `BUST_MEAN_YEARS`' time constant, started at the first reading, the
/// bust swing's reference — spends more than 0.5 above and more than 0.5 below its mean, after
/// the reference's first `BUST_MEAN_YEARS`. The record's CAPE about the same reference
/// (`mania-2026-09-15.tsv`, w1901) reads 0.076 / 0.067: symmetric. Returned as COUNTS over the
/// sessions read, (0, 0, 0) on a path shorter than the burn-in, so `measure` can pool them.
fn wings_of(price: &[f64], fund: &[f64]) -> (f64, f64, f64) {
    let n = price.len();
    let burn = (BUST_MEAN_YEARS * DAYS_PER_YEAR as f64) as usize;
    if n <= burn {
        return (0.0, 0.0, 0.0);
    }
    let mu = 1.0 / (BUST_MEAN_YEARS * DAYS_PER_YEAR as f64);
    let mut reference = (price[0] / fund[0]).ln();
    let mut up = 0usize;
    let mut down = 0usize;
    for i in 0..n {
        let g = (price[i] / fund[i]).ln();
        if i >= burn {
            let lvl = g - reference;
            if lvl > 0.5 {
                up += 1;
            } else if lvl < -0.5 {
                down += 1;
            }
        }
        reference += mu * (g - reference);
    }
    (up as f64, down as f64, (n - burn) as f64)
}

/// A share pooled over paths: the counts summed over the sessions summed, NaN when nothing was
/// read. Left folds in path order, like every pooled sum here.
fn pooled_share(counts: impl Iterator<Item = f64>, sessions: impl Iterator<Item = f64>) -> f64 {
    let mut c = 0.0;
    let mut n = 0.0;
    for x in counts {
        c += x;
    }
    for x in sessions {
        n += x;
    }
    if n > 0.0 { c / n } else { f64::NAN }
}

fn med(v: &[f64]) -> f64 {
    let s = finite_sorted(v);
    if s.is_empty() {
        f64::NAN
    } else {
        s[s.len() / 2]
    }
}

/// NON-FINITE ENTRIES ARE DROPPED, the same rule `med` applies, because `total_cmp` -- like Scala's
/// `Ordering[Double]` -- ranks NaN ABOVE every number: an unfiltered sort parks them in the top
/// slots and biases every quantile DOWNWARD rather than propagating the NaN. A contaminated ensemble
/// read a 6.17% median volatility against a 15.7% baseline that way. A quantile is the wrong place
/// to LEARN that an ensemble was contaminated -- the reports count that directly.
pub fn pctile(v: &[f64], q: f64) -> f64 {
    pctile_of(&finite_sorted(v), q)
}

/// The satellite leg's statistics as ratios to the primary's — `None` when no leg ran, so a
/// satellite-off world produces exactly the rows it always did.
///
/// Every ratio is a MEDIAN over paths of that path's own ratio, not a ratio of pooled medians:
/// the two differ when the legs' dispersions differ, and the per-path form is the one the
/// record's single history is a draw from.
fn sat_stats(sims: &[Path], years: usize) -> Option<SatStats> {
    if sims.is_empty() || sims[0].sat.is_empty() {
        return None;
    }
    struct SatPath {
        corr: f64,
        abs_corr: f64,
        beta: f64,
        vol: f64,
        kurt: f64,
        ac1: f64,
        ac20: f64,
        d5: f64,
        d10: f64,
        crash: Option<f64>,
    }
    // THE PATHS ACROSS CORES, gathered in path order. Each path's reading is a pure function of
    // that path, so no median moves; sequential, these channel statistics were three quarters of
    // a channel-emitting world's `measure` once the rest ran in parallel.
    let per: Vec<SatPath> = sims
        .par_iter()
        .map(|s| {
            let rp = daily_returns(&s.price);
            let rs = daily_returns(&s.sat);
            let ap: Vec<f64> = rp.iter().map(|x| x.abs()).collect();
            let a_s: Vec<f64> = rs.iter().map(|x| x.abs()).collect();
            let mp = rp.iter().sum::<f64>() / rp.len() as f64;
            let ms = rs.iter().sum::<f64>() / rs.len() as f64;
            let cov: f64 = rp.iter().zip(&rs).map(|(x, y)| (x - mp) * (y - ms)).sum();
            let var_p: f64 = rp.iter().map(|x| (x - mp) * (x - mp)).sum();
            let var_s: f64 = rs.iter().map(|x| (x - ms) * (x - ms)).sum();
            let (p5, p10, _) = depth_shares(&s.price);
            let (s5, s10, _) = depth_shares(&s.sat);
            let ep = episodes(&s.price, 15.0).len() as f64;
            let es = episodes(&s.sat, 15.0).len() as f64;
            SatPath {
                corr: pearson(&rp, &rs),
                abs_corr: pearson(&ap, &a_s),
                beta: cov / var_p,
                vol: (var_s / var_p).sqrt(),
                kurt: kurtosis(&rs) / kurtosis(&rp),
                ac1: autocorr_abs(&rs, 1) / autocorr_abs(&rp, 1),
                ac20: autocorr_abs(&rs, 20) / autocorr_abs(&rp, 20),
                d5: s5 / p5,
                d10: s10 / p10,
                crash: (ep > 0.0).then_some(es / ep),
            }
        })
        .collect();
    let _ = years;
    let med_of = |f: fn(&SatPath) -> f64| med(&per.iter().map(f).collect::<Vec<f64>>());
    Some(SatStats {
        corr: med_of(|p| p.corr),
        abs_corr: med_of(|p| p.abs_corr),
        beta: med_of(|p| p.beta),
        vol_ratio: med_of(|p| p.vol),
        kurt_ratio: med_of(|p| p.kurt),
        ac1_ratio: med_of(|p| p.ac1),
        ac20_ratio: med_of(|p| p.ac20),
        d5_ratio: med_of(|p| p.d5),
        d10_ratio: med_of(|p| p.d10),
        crash_ratio: med(&per.iter().filter_map(|p| p.crash).collect::<Vec<f64>>()),
    })
}

/// The bar channels' statistics — `None` when no range channel ran.
/// THE BASKET's readings, medians across paths, at the three levels of `basket-2026-09-02.tsv`.
/// Level 1 per name, pooled over names and paths: vol as a ratio to the primary's, sessions past
/// 10% per year, the share of sessions >20% below the running peak. Level 2 the equal-weight
/// aggregate against the primary: correlation and beta, and vol ratio. Level 3: mean pairwise
/// correlation, idio share (1 - R^2 of a name on the aggregate), same-day tail coincidence, and
/// the mechanism — mean pairwise correlation on the primary's worst decile against its middle
/// decile. The equal-weight basket is the mean of simple returns, as a log series, the fixture's
/// convention.
#[derive(Clone, Copy, Debug)]
pub struct BasketStats {
    pub name_vol_ratio: f64,
    pub name_gaps: f64,
    pub name_d20: f64,
    pub agg_corr: f64,
    pub agg_beta: f64,
    pub agg_vol_ratio: f64,
    pub pair_corr: f64,
    pub idio_share: f64,
    pub tail_coincidence: f64,
    pub pair_corr_worst: f64,
    pub pair_corr_mid: f64,
    /// the SPREAD of time-below-peak across the names (max - min), which is what `basket_drift`
    /// moves; the eight span 0.53
    pub name_d20_spread: f64,
}

fn sd_of(r: &[f64]) -> f64 {
    let m = r.iter().sum::<f64>() / r.len() as f64;
    (r.iter().map(|v| (v - m) * (v - m)).sum::<f64>() / (r.len() - 1) as f64).sqrt()
}

fn mean_pair_corr(rn: &[Vec<f64>]) -> f64 {
    let mut sum = 0.0f64;
    let mut cnt = 0usize;
    for a in 0..rn.len() {
        for b in (a + 1)..rn.len() {
            sum += pearson(&rn[a], &rn[b]);
            cnt += 1;
        }
    }
    sum / cnt as f64
}

/// One path's eleven basket readings, in `BasketStats` field order.
fn basket_path_stats(s: &Path) -> [f64; 12] {
    let rp = daily_returns(&s.price);
    let n = rp.len();
    let rn: Vec<Vec<f64>> = s
        .names
        .iter()
        .map(|lp| (0..n).map(|t| lp[t + 1] - lp[t]).collect())
        .collect();
    let agg: Vec<f64> = (0..n)
        .map(|t| (rn.iter().map(|r| r[t].exp()).sum::<f64>() / rn.len() as f64).ln())
        .collect();
    let sd_p = sd_of(&rp);
    let vol_ratios: Vec<f64> = rn.iter().map(|r| sd_of(r) / sd_p).collect();
    let yrs = n as f64 / DAYS_PER_YEAR as f64;
    let gaps: Vec<f64> = rn
        .iter()
        .map(|r| r.iter().filter(|v| v.abs() > 0.10).count() as f64 / yrs)
        .collect();
    let d20s: Vec<f64> = s
        .names
        .iter()
        .map(|lp| {
            let px: Vec<f64> = lp.iter().map(|v| v.exp()).collect();
            depth_shares(&px).2
        })
        .collect();
    let mp = rp.iter().sum::<f64>() / n as f64;
    let ma = agg.iter().sum::<f64>() / n as f64;
    let mut cov = 0.0f64;
    let mut var_p = 0.0f64;
    for t in 0..n {
        cov += (rp[t] - mp) * (agg[t] - ma);
        var_p += (rp[t] - mp) * (rp[t] - mp);
    }
    let idio: Vec<f64> = rn
        .iter()
        .map(|r| {
            let ba = pearson(r, &agg);
            1.0 - ba * ba
        })
        .collect();
    let cuts: Vec<f64> = rn.iter().map(|r| pctile(r, 0.01)).collect();
    let agg_cut = pctile(&agg, 0.01);
    let worst_agg: Vec<usize> = (0..n).filter(|&t| agg[t] <= agg_cut).collect();
    let coinc = if worst_agg.is_empty() {
        f64::NAN
    } else {
        worst_agg
            .iter()
            .map(|&t| {
                rn.iter()
                    .enumerate()
                    .filter(|(q, r)| r[t] < cuts[*q])
                    .count() as f64
                    / rn.len() as f64
            })
            .sum::<f64>()
            / worst_agg.len() as f64
    };
    let mut order: Vec<usize> = (0..n).collect();
    order.sort_by(|&a, &b| {
        rp[a]
            .partial_cmp(&rp[b])
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let dec = n / 10;
    let pair_on = |idx: &[usize]| -> f64 {
        let sub: Vec<Vec<f64>> = rn
            .iter()
            .map(|r| idx.iter().map(|&t| r[t]).collect())
            .collect();
        mean_pair_corr(&sub)
    };
    [
        med(&vol_ratios),
        gaps.iter().sum::<f64>() / gaps.len() as f64,
        med(&d20s),
        pearson(&rp, &agg),
        cov / var_p,
        sd_of(&agg) / sd_p,
        mean_pair_corr(&rn),
        med(&idio),
        coinc,
        pair_on(&order[..dec]),
        pair_on(&order[n / 2 - dec / 2..n / 2 + dec / 2]),
        d20s.iter().cloned().fold(f64::NEG_INFINITY, f64::max)
            - d20s.iter().cloned().fold(f64::INFINITY, f64::min),
    ]
}

fn basket_stats(sims: &[Path]) -> Option<BasketStats> {
    if sims.is_empty() || sims[0].names.is_empty() {
        return None;
    }
    // THE PATHS ACROSS CORES, gathered in path order. Each path's reading is a pure function of
    // that path, so no median moves; sequential, these channel statistics were three quarters of
    // a channel-emitting world's `measure` once the rest ran in parallel.
    let per: Vec<_> = sims.par_iter().map(basket_path_stats).collect();
    let mut cols: Vec<Vec<f64>> = (0..12).map(|_| Vec::with_capacity(sims.len())).collect();
    for p in per {
        for (c, v) in cols.iter_mut().zip(p) {
            c.push(v);
        }
    }
    Some(BasketStats {
        name_vol_ratio: med(&cols[0]),
        name_gaps: med(&cols[1]),
        name_d20: med(&cols[2]),
        agg_corr: med(&cols[3]),
        agg_beta: med(&cols[4]),
        agg_vol_ratio: med(&cols[5]),
        pair_corr: med(&cols[6]),
        idio_share: med(&cols[7]),
        tail_coincidence: med(&cols[8]),
        pair_corr_worst: med(&cols[9]),
        pair_corr_mid: med(&cols[10]),
        name_d20_spread: med(&cols[11]),
    })
}

/// The open's readings, medians across paths: the overnight share of close-to-close variance
/// (sample variances), and the regression share of the overnight in the session — sum(o r) /
/// sum(r^2) — over the worst 1% of sessions and over all of them. The record's largest declines
/// open with the larger part of the day already gone.
#[derive(Clone, Copy, Debug)]
pub struct OpenStats {
    pub overnight_share: f64,
    pub worst_gap_share: f64,
    pub all_gap_share: f64,
}

fn open_stats(sims: &[Path]) -> Option<OpenStats> {
    if sims.is_empty() || sims[0].log_open.is_empty() {
        return None;
    }
    // THE PATHS ACROSS CORES, gathered in path order. Each path's reading is a pure function of
    // that path, so no median moves; sequential, these channel statistics were three quarters of
    // a channel-emitting world's `measure` once the rest ran in parallel.
    let per: Vec<(f64, f64, f64)> = sims
        .par_iter()
        .map(|s| {
            let lp: Vec<f64> = s.price.iter().map(|v| v.ln()).collect();
            let n = lp.len() - 1;
            let o: Vec<f64> = (0..n).map(|t| s.log_open[t + 1] - lp[t]).collect();
            let r: Vec<f64> = (0..n).map(|t| lp[t + 1] - lp[t]).collect();
            let sv = |x: &[f64]| -> f64 {
                let m = x.iter().sum::<f64>() / x.len() as f64;
                x.iter().map(|v| (v - m) * (v - m)).sum::<f64>() / (x.len() - 1) as f64
            };
            let reg_share = |idx: &[usize]| -> f64 {
                let mut so = 0.0f64;
                let mut sr = 0.0f64;
                for &t in idx {
                    so += o[t] * r[t];
                    sr += r[t] * r[t];
                }
                if sr > 0.0 { so / sr } else { f64::NAN }
            };
            let mut order: Vec<usize> = (0..n).collect();
            order.sort_by(|&a, &b| r[a].partial_cmp(&r[b]).unwrap_or(std::cmp::Ordering::Equal));
            let worst = &order[..1.max(n / 100)];
            let all: Vec<usize> = (0..n).collect();
            (sv(&o) / sv(&r), reg_share(worst), reg_share(&all))
        })
        .collect();
    Some(OpenStats {
        overnight_share: med(&per.iter().map(|p| p.0).collect::<Vec<f64>>()),
        worst_gap_share: med(&per.iter().map(|p| p.1).collect::<Vec<f64>>()),
        all_gap_share: med(&per.iter().map(|p| p.2).collect::<Vec<f64>>()),
    })
}

/// One macro member's readings on the ruler's statistics (`macro-2026-09-06.tsv`): the level's
/// autocorrelation at 1 and K observations (K = 20 sessions, or 4 weekly readings for the
/// conditions index), its predictive R^2 for the forward 60-session log return, the WARNING SHARE
/// — over the 20% episodes pooled across paths, the median fraction of the peak-to-trough log
/// decline still ahead when the member first fires in [peak - lookback, trough], 0 if it never
/// fires — with the share of episodes it fired in at all, and the level's percentiles. Medians
/// across paths except the pooled episode statistics.
#[derive(Clone, Copy, Debug)]
pub struct MacroMember {
    pub ac1: f64,
    pub ac_k: f64,
    pub r2fwd60: f64,
    pub warn: f64,
    pub warn_fired: f64,
    /// the firing LAG: sessions from the peak to the first firing, negative before it, median
    /// over the episodes the member fired in — the timing statistic that is invariant to how
    /// fast the decline runs
    pub lag: f64,
    /// the same lag and fired share over the 10% episodes: more events behind the timing,
    /// REPORTED
    pub lag10: f64,
    pub fired10: f64,
    /// THE BUILD-UP, the coupling statistic: the mean trailing rank over the quarter before the
    /// peak (the slope: the share inverted), median over the 20% episodes. A decoupled series
    /// reads its unconditional level there
    pub pre_peak: f64,
    pub lvl10: f64,
    pub lvl50: f64,
    pub lvl90: f64,
}

/// The panel's readings: the members in `macro_k::COLUMNS` order, the slope's inversion
/// share and mean spell length (observations, pooled), the implied-vol member's variance risk
/// premium (mean log ivol - log forward-21-session realized vol) and their R^2, and the pooled 20%
/// episode count the warning shares are medians of.
/// A graded statistic's PER-PATH spread across the ensemble: p5 / p50 / p95 of the per-path
/// readings (`pctile`, the twins' upper-middle convention). The gate grades pooled statistics and
/// a consumer runs one path; this is the width of the null that path sits in.
#[derive(Clone, Copy, Debug)]
pub struct Spread {
    pub p5: f64,
    pub p50: f64,
    pub p95: f64,
}

fn spread_of(xs: &[f64]) -> Spread {
    let f: Vec<f64> = xs.iter().copied().filter(|x| !x.is_nan()).collect();
    if f.is_empty() {
        Spread {
            p5: f64::NAN,
            p50: f64::NAN,
            p95: f64::NAN,
        }
    } else {
        Spread {
            p5: pctile(&f, 0.05),
            p50: pctile(&f, 0.5),
            p95: pctile(&f, 0.95),
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct MacroStats {
    pub members: [MacroMember; 9],
    /// per-path spreads: each member's forward-return R^2, build-up and 20% firing lag
    pub member_spread: [(Spread, Spread, Spread); 9],
    /// the world's slope inversion share, vol premium, its R^2 against forward realized vol, and
    /// the quarter hazard, per path
    pub inv_share_spread: Spread,
    pub vrp_spread: Spread,
    pub r2rv_spread: Spread,
    pub hazard_spread: Spread,
    /// the panel is a sibling path's (`-macronull`): the readings are the no-edge level and
    /// the rows do not grade them
    pub sibling: bool,
    /// THE HAZARD: how much likelier a 20% peak is within a quarter / a year, and a 10% peak
    /// within a quarter, when the conditions index sits in its top decile — the mechanism's
    /// target (record 2.0-2.7 / ~1.2 / ~1 on the S&P) — and the unconditional quarter
    /// probability of a 20% peak
    pub hazard20q: f64,
    pub hazard20y: f64,
    pub hazard10q: f64,
    pub p20q: f64,
    pub inv_share: f64,
    pub inv_dur: f64,
    pub vrp: f64,
    pub r2rv: f64,
    pub episodes: usize,
}

/// The rank rule a consumer's vote reads: the member's trailing-`win` percentile rank, the share
/// of the window (this reading included) at or below it; NaN until the window fills.
fn trailing_rank(x: &[f64], win: usize) -> Vec<f64> {
    (0..x.len())
        .map(|i| {
            if i + 1 < win {
                f64::NAN
            } else {
                let c = x[i + 1 - win..=i].iter().filter(|&&v| v <= x[i]).count();
                c as f64 / win as f64
            }
        })
        .collect()
}

/// Pearson correlation over the finite pairs.
fn pearson_finite(x: &[f64], y: &[f64]) -> f64 {
    let mut n = 0usize;
    let mut sx = 0.0f64;
    let mut sy = 0.0f64;
    for (a, b) in x.iter().zip(y) {
        if a.is_finite() && b.is_finite() {
            n += 1;
            sx += a;
            sy += b;
        }
    }
    if n < 3 {
        return f64::NAN;
    }
    let mx = sx / n as f64;
    let my = sy / n as f64;
    let mut sxx = 0.0f64;
    let mut syy = 0.0f64;
    let mut sxy = 0.0f64;
    for (a, b) in x.iter().zip(y) {
        if a.is_finite() && b.is_finite() {
            let dx = a - mx;
            let dy = b - my;
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
    }
    if sxx <= 0.0 || syy <= 0.0 {
        f64::NAN
    } else {
        sxy / (sxx * syy).sqrt()
    }
}

fn level_autocorr(x: &[f64], k: usize) -> f64 {
    if x.len() < k + 3 {
        f64::NAN
    } else {
        pearson_finite(&x[..x.len() - k], &x[k..])
    }
}

/// R^2 as the squared correlation, r * r rather than pow(r, 2) so the twins print the same digit.
fn r2_of(x: &[f64], y: &[f64]) -> f64 {
    let r = pearson_finite(x, y);
    r * r
}

/// The forward h-session log return from each session; NaN where the path ends first.
fn fwd_return(lp: &[f64], h: usize) -> Vec<f64> {
    (0..lp.len())
        .map(|i| {
            if i + h < lp.len() {
                lp[i + h] - lp[i]
            } else {
                f64::NAN
            }
        })
        .collect()
}

/// Annualized realized volatility in % over the h sessions AFTER each session.
fn fwd_realized_vol(lp: &[f64], h: usize) -> Vec<f64> {
    let n = lp.len();
    let mut r2 = vec![0.0f64; n];
    for i in 1..n {
        let d = lp[i] - lp[i - 1];
        r2[i] = r2[i - 1] + d * d;
    }
    (0..n)
        .map(|i| {
            if i + h < n {
                100.0 * (DAYS_PER_YEAR as f64 * (r2[i + h] - r2[i]) / h as f64).sqrt()
            } else {
                f64::NAN
            }
        })
        .collect()
}

/// One episode's warning: the share of the log decline still ahead at the first firing (0 if
/// never), and the firing LAG — sessions from the peak to that firing, negative before it (None
/// if never).
struct Warning {
    share: f64,
    lag: Option<i64>,
}

/// The warnings of one path's episodes: see `MacroMember`.
fn warn_shares(lp: &[f64], spans: &[DdSpan], fired: &[bool], lookback: usize) -> Vec<Warning> {
    spans
        .iter()
        .map(|s| {
            let base = s.lo.saturating_sub(1);
            let from = base.saturating_sub(lookback);
            match (from..=s.trough).find(|&t| fired[t]) {
                None => Warning {
                    share: 0.0,
                    lag: None,
                },
                Some(t) => {
                    let tot = lp[base] - lp[s.trough];
                    Warning {
                        share: ((lp[t] - lp[s.trough]) / tot).clamp(0.0, 1.0),
                        lag: Some(t as i64 - base as i64),
                    }
                }
            }
        })
        .collect()
}

/// The rank window: an episode whose peak falls inside the first `RANK_WINDOW` sessions has no
/// trailing rank to fire on and is excluded from the warning statistics, on the record and on
/// the model alike.
const RANK_WINDOW: usize = 252;

/// Lengths of the runs of `true`.
fn run_lengths(mask: &[bool]) -> Vec<usize> {
    let mut out = Vec::new();
    let mut run = 0usize;
    for &b in mask {
        if b {
            run += 1;
        } else if run > 0 {
            out.push(run);
            run = 0;
        }
    }
    if run > 0 {
        out.push(run);
    }
    out
}

/// One path's reading of one member: (ac1, acK, r2fwd60, warnings over the 20% episodes, the
/// same over the 10% episodes, (p10, p50, p90), the build-up per 20% episode).
type MemberRead = (
    f64,
    f64,
    f64,
    Vec<Warning>,
    Vec<Warning>,
    (f64, f64, f64),
    Vec<f64>,
);

/// The BUILD-UP of one path's episodes: the mean of `rank` over the quarter before each
/// episode's peak, [peak - q, peak].
fn pre_peak_ranks(rank: &[f64], spans: &[DdSpan], q: usize) -> Vec<f64> {
    spans
        .iter()
        .filter_map(|s| {
            let base = s.lo.saturating_sub(1);
            let w: Vec<f64> = rank[base.saturating_sub(q)..=base]
                .iter()
                .copied()
                .filter(|v| v.is_finite())
                .collect();
            if w.is_empty() {
                None
            } else {
                Some(w.iter().sum::<f64>() / w.len() as f64)
            }
        })
        .collect()
}

/// One path's reading of the panel: the members in `macro_k::COLUMNS` order, the slope's
/// inversion share and spells, the implied vol's log premium and its R^2 against forward
/// realized vol.
/// One path's hazard counts: top-decile sessions with a peak inside the horizon, top-decile
/// sessions, and the same over every session with a finite rank.
#[derive(Clone, Copy, Default)]
struct HazardCounts {
    hit_top: usize,
    n_top: usize,
    hit_all: usize,
    n_all: usize,
}

struct MacroPathRead {
    members: [MemberRead; 9],
    inv_share: f64,
    spells: Vec<usize>,
    vrp: f64,
    r2rv: f64,
    /// 20% peaks within a quarter, within a year, and 10% peaks within a quarter
    hazards: [HazardCounts; 3],
}

/// The conditions index read weekly like its counterpart: the last session of each five, ranked
/// over 52 readings, each reading held until the next — the weekly values, their sessions, the
/// firing flag per session (rank >= 0.90) and the held rank per session.
fn cond_weekly(cond: &[f64], n: usize) -> (Vec<f64>, Vec<usize>, Vec<bool>, Vec<f64>) {
    const WEEKLY_STRIDE: usize = 5;
    let w: Vec<f64> = (0..cond.len() / WEEKLY_STRIDE)
        .map(|t| cond[t * WEEKLY_STRIDE + WEEKLY_STRIDE - 1])
        .collect();
    let at: Vec<usize> = (0..w.len())
        .map(|t| t * WEEKLY_STRIDE + WEEKLY_STRIDE - 1)
        .collect();
    let rk = trailing_rank(&w, 52);
    let mut fired = vec![false; n];
    let mut held = vec![f64::NAN; n];
    for t in 0..w.len() {
        let next = if t + 1 < w.len() { at[t + 1] } else { n };
        let on = rk[t].is_finite() && rk[t] >= 0.90;
        for k in at[t]..next {
            held[k] = rk[t];
            if on {
                fired[k] = true;
            }
        }
    }
    (w, at, fired, held)
}

/// THE HAZARD's counts, pooled across paths by the caller: sessions in the index's top decile
/// with a peak inside the next `h`, over all such sessions, and the same for every session with
/// a finite rank — the ratio is how much likelier a peak is soon when leverage is high.
fn hazard_counts(held: &[f64], sp: &[DdSpan], h: usize) -> HazardCounts {
    let n = held.len();
    let mut ahead = vec![false; n];
    for s in sp {
        let base = s.lo.saturating_sub(1);
        for a in &mut ahead[base.saturating_sub(h)..base] {
            *a = true;
        }
    }
    let mut c = HazardCounts::default();
    for u in 0..n.saturating_sub(h) {
        if held[u].is_finite() {
            c.n_all += 1;
            if ahead[u] {
                c.hit_all += 1;
            }
            if held[u] >= 0.90 {
                c.n_top += 1;
                if ahead[u] {
                    c.hit_top += 1;
                }
            }
        }
    }
    c
}

fn macro_path_read(s: &Path) -> Option<MacroPathRead> {
    let m = s.macro_panel.as_ref()?;
    // sorted ONCE for all three quantiles; it was three filtered copies and three sorts
    let levels = |x: &[f64]| {
        let f = finite_sorted(x);
        (pctile_of(&f, 0.1), pctile_of(&f, 0.5), pctile_of(&f, 0.9))
    };
    let lp: Vec<f64> = s.price.iter().map(|v| v.ln()).collect();
    let fwd60 = fwd_return(&lp, 60);
    // the 20% episodes the rows grade, and the 10% ones — more events, mostly not macro ones on
    // a Nasdaq-like world — whose lag and fired share are reported beside them
    let episodes = |thr: f64| -> Vec<DdSpan> {
        dd_spans(&s.price, thr)
            .into_iter()
            .filter(|s| s.lo.saturating_sub(1) >= RANK_WINDOW)
            .collect()
    };
    let spans = episodes(0.20);
    let spans10 = episodes(0.10);
    // a daily rank member (spread, ivol): rank >= 0.90 in the quarter before the peak
    let rank_member = |x: &[f64]| -> MemberRead {
        let rk = trailing_rank(x, 252);
        let fired: Vec<bool> = rk.iter().map(|r| r.is_finite() && *r >= 0.90).collect();
        (
            level_autocorr(x, 1),
            level_autocorr(x, 20),
            r2_of(x, &fwd60),
            warn_shares(&lp, &spans, &fired, 63),
            warn_shares(&lp, &spans10, &fired, 63),
            levels(x),
            pre_peak_ranks(&rk, &spans, 63),
        )
    };
    // the slope: inverted in the 18 months before the peak; its build-up is the share of the
    // quarter before the peak spent inverted
    let slope_m: MemberRead = {
        let x = &m.slope;
        let fired: Vec<bool> = x.iter().map(|v| *v < 0.0).collect();
        let inverted: Vec<f64> = fired.iter().map(|&b| if b { 1.0 } else { 0.0 }).collect();
        (
            level_autocorr(x, 1),
            level_autocorr(x, 20),
            r2_of(x, &fwd60),
            warn_shares(&lp, &spans, &fired, 378),
            warn_shares(&lp, &spans10, &fired, 378),
            levels(x),
            pre_peak_ranks(&inverted, &spans, 63),
        )
    };
    // the conditions index, READ WEEKLY like its counterpart
    let (cond_w, cond_at, cond_fired, cond_held) = cond_weekly(&m.cond, lp.len());
    let cond_m: MemberRead = {
        let fwd_at: Vec<f64> = cond_at.iter().map(|&k| fwd60[k]).collect();
        (
            level_autocorr(&cond_w, 1),
            level_autocorr(&cond_w, 4),
            r2_of(&cond_w, &fwd_at),
            warn_shares(&lp, &spans, &cond_fired, 63),
            warn_shares(&lp, &spans10, &cond_fired, 63),
            levels(&cond_w),
            pre_peak_ranks(&cond_held, &spans, 63),
        )
    };
    let hazards = [
        hazard_counts(&cond_held, &spans, 63),
        hazard_counts(&cond_held, &spans, 252),
        hazard_counts(&cond_held, &spans10, 63),
    ];
    let inv: Vec<bool> = m.slope.iter().map(|v| *v < 0.0).collect();
    let rv = fwd_realized_vol(&lp, 21);
    let l_iv: Vec<f64> = m.ivol.iter().map(|v| v.ln()).collect();
    let l_rv: Vec<f64> = rv.iter().map(|v| v.ln()).collect();
    let d_ok: Vec<f64> = (0..lp.len())
        .filter(|&i| l_rv[i].is_finite())
        .map(|i| l_iv[i] - l_rv[i])
        .collect();
    Some(MacroPathRead {
        members: [
            rank_member(&m.spread),
            slope_m,
            cond_m,
            rank_member(&m.ivol),
            rank_member(&m.yield10),
            rank_member(&m.credit),
            rank_member(&m.policy),
            rank_member(&m.bank),
            rank_member(&m.output),
        ],
        inv_share: inv.iter().filter(|&&b| b).count() as f64 / inv.len() as f64,
        spells: run_lengths(&inv),
        vrp: if d_ok.is_empty() {
            f64::NAN
        } else {
            d_ok.iter().sum::<f64>() / d_ok.len() as f64
        },
        r2rv: r2_of(&l_iv, &l_rv),
        hazards,
    })
}

fn macro_stats(sims: &[Path]) -> Option<MacroStats> {
    // across cores, in path order: each read is a pure function of its own path
    let per: Vec<MacroPathRead> = sims
        .par_iter()
        .map(macro_path_read)
        .collect::<Option<_>>()?;
    if per.is_empty() {
        return None;
    }
    // the hazards, pooled: (hits in the top decile / its sessions) over (the same over every
    // session); NaN where nothing qualified
    let hazard_ratio = |j: usize| -> (f64, f64) {
        let c = per
            .iter()
            .fold(HazardCounts::default(), |a, p| HazardCounts {
                hit_top: a.hit_top + p.hazards[j].hit_top,
                n_top: a.n_top + p.hazards[j].n_top,
                hit_all: a.hit_all + p.hazards[j].hit_all,
                n_all: a.n_all + p.hazards[j].n_all,
            });
        let p_all = if c.n_all > 0 {
            c.hit_all as f64 / c.n_all as f64
        } else {
            f64::NAN
        };
        let p_top = if c.n_top > 0 {
            c.hit_top as f64 / c.n_top as f64
        } else {
            f64::NAN
        };
        (if p_all > 0.0 { p_top / p_all } else { f64::NAN }, p_all)
    };
    let fired_share = |ws: &[&Warning]| -> f64 {
        if ws.is_empty() {
            f64::NAN
        } else {
            ws.iter().filter(|w| w.share > 0.0).count() as f64 / ws.len() as f64
        }
    };
    let lag_median = |ws: &[&Warning]| -> f64 {
        let lags: Vec<f64> = ws.iter().filter_map(|w| w.lag.map(|l| l as f64)).collect();
        pctile(&lags, 0.5)
    };
    let members: [MacroMember; 9] = std::array::from_fn(|j| {
        let ws: Vec<&Warning> = per.iter().flat_map(|p| p.members[j].3.iter()).collect();
        let ws10: Vec<&Warning> = per.iter().flat_map(|p| p.members[j].4.iter()).collect();
        let shares: Vec<f64> = ws.iter().map(|w| w.share).collect();
        let col = |f: &dyn Fn(&MemberRead) -> f64| -> Vec<f64> {
            per.iter().map(|p| f(&p.members[j])).collect()
        };
        MacroMember {
            ac1: med(&col(&|r| r.0)),
            ac_k: med(&col(&|r| r.1)),
            r2fwd60: med(&col(&|r| r.2)),
            warn: pctile(&shares, 0.5),
            warn_fired: fired_share(&ws),
            lag: lag_median(&ws),
            lag10: lag_median(&ws10),
            fired10: fired_share(&ws10),
            pre_peak: pctile(
                &per.iter()
                    .flat_map(|p| p.members[j].6.iter().copied())
                    .collect::<Vec<_>>(),
                0.5,
            ),
            lvl10: med(&col(&|r| r.5.0)),
            lvl50: med(&col(&|r| r.5.1)),
            lvl90: med(&col(&|r| r.5.2)),
        }
    });
    let spells: Vec<usize> = per.iter().flat_map(|p| p.spells.iter().copied()).collect();
    let (member_spread, hazard_spread) = macro_spreads(&per);
    Some(MacroStats {
        members,
        member_spread,
        inv_share_spread: spread_of(&per.iter().map(|p| p.inv_share).collect::<Vec<_>>()),
        vrp_spread: spread_of(&per.iter().map(|p| p.vrp).collect::<Vec<_>>()),
        r2rv_spread: spread_of(&per.iter().map(|p| p.r2rv).collect::<Vec<_>>()),
        hazard_spread,
        inv_share: med(&per.iter().map(|p| p.inv_share).collect::<Vec<_>>()),
        inv_dur: if spells.is_empty() {
            f64::NAN
        } else {
            spells.iter().sum::<usize>() as f64 / spells.len() as f64
        },
        vrp: med(&per.iter().map(|p| p.vrp).collect::<Vec<_>>()),
        r2rv: med(&per.iter().map(|p| p.r2rv).collect::<Vec<_>>()),
        episodes: per.iter().map(|p| p.members[0].3.len()).sum(),
        sibling: sims
            .first()
            .and_then(|s| s.macro_panel.as_ref())
            .is_some_and(|m| m.sibling),
        hazard20q: hazard_ratio(0).0,
        hazard20y: hazard_ratio(1).0,
        hazard10q: hazard_ratio(2).0,
        p20q: hazard_ratio(0).1,
    })
}

/// The per-path spreads: one reading per path — its own median over its episodes where the
/// statistic is per episode, its own hazard ratio from its own counts (NaN where a path has no
/// top-decile session or no episode ahead).
fn macro_spreads(per: &[MacroPathRead]) -> ([(Spread, Spread, Spread); 9], Spread) {
    let member_spread: [(Spread, Spread, Spread); 9] = std::array::from_fn(|j| {
        let r2: Vec<f64> = per.iter().map(|p| p.members[j].2).collect();
        let pre: Vec<f64> = per.iter().map(|p| pctile(&p.members[j].6, 0.5)).collect();
        let lag: Vec<f64> = per
            .iter()
            .map(|p| {
                let l: Vec<f64> = p.members[j]
                    .3
                    .iter()
                    .filter_map(|w| w.lag)
                    .map(|x| x as f64)
                    .collect();
                pctile(&l, 0.5)
            })
            .collect();
        (spread_of(&r2), spread_of(&pre), spread_of(&lag))
    });
    let hazard_per: Vec<f64> = per
        .iter()
        .map(|p| {
            let c = p.hazards[0];
            if c.n_top > 0 && c.n_all > 0 && c.hit_all > 0 {
                (c.hit_top as f64 / c.n_top as f64) / (c.hit_all as f64 / c.n_all as f64)
            } else {
                f64::NAN
            }
        })
        .collect();
    (member_spread, spread_of(&hazard_per))
}

fn bar_stats(sims: &[Path]) -> Option<BarStats> {
    if sims.is_empty() || sims[0].log_hi.is_empty() {
        return None;
    }
    // THE PATHS ACROSS CORES, gathered in path order. Each path's reading is a pure function of
    // that path, so no median moves; sequential, these channel statistics were three quarters of
    // a channel-emitting world's `measure` once the rest ran in parallel.
    struct BarPath {
        range_over_ccvol: f64,
        range_acf1: f64,
        downup: Option<f64>,        // when both return signs occur
        volume: Option<(f64, f64)>, // (sd, corr with the range) when the volume channel ran
    }
    let per: Vec<BarPath> = sims
        .par_iter()
        .map(|s| {
            let r = daily_returns(&s.price);
            let x: Vec<f64> = (0..s.log_hi.len())
                .map(|i| s.log_hi[i] - s.log_lo[i])
                .collect();
            let mx = x.iter().sum::<f64>() / x.len() as f64;
            let mr = r.iter().sum::<f64>() / r.len() as f64;
            let sr = (r.iter().map(|v| (v - mr) * (v - mr)).sum::<f64>() / r.len() as f64).sqrt();
            // The bar's return is measured over the SAME window the bar spans (open = prior close),
            // so the sign that conditions the range is `r` shifted by one: bar i spans price i-1..i.
            let dn: Vec<f64> = (1..x.len())
                .filter(|&i| r[i - 1] < 0.0)
                .map(|i| x[i])
                .collect();
            let up: Vec<f64> = (1..x.len())
                .filter(|&i| r[i - 1] > 0.0)
                .map(|i| x[i])
                .collect();
            let downup = (!dn.is_empty() && !up.is_empty()).then(|| {
                let md = dn.iter().sum::<f64>() / dn.len() as f64;
                let mu = up.iter().sum::<f64>() / up.len() as f64;
                md / mu
            });
            let volume = (!s.log_volume.is_empty()).then(|| {
                let mv = s.log_volume.iter().sum::<f64>() / s.log_volume.len() as f64;
                let sd = (s
                    .log_volume
                    .iter()
                    .map(|v| (v - mv) * (v - mv))
                    .sum::<f64>()
                    / s.log_volume.len() as f64)
                    .sqrt();
                (sd, pearson(&s.log_volume, &x))
            });
            BarPath {
                range_over_ccvol: mx / sr,
                range_acf1: pearson(&x[..x.len() - 1], &x[1..]),
                downup,
                volume,
            }
        })
        .collect();
    Some(BarStats {
        range_over_ccvol: med(&per.iter().map(|p| p.range_over_ccvol).collect::<Vec<f64>>()),
        range_acf1: med(&per.iter().map(|p| p.range_acf1).collect::<Vec<f64>>()),
        range_downup: med(&per.iter().filter_map(|p| p.downup).collect::<Vec<f64>>()),
        vol_sd: med(&per
            .iter()
            .filter_map(|p| p.volume.map(|v| v.0))
            .collect::<Vec<f64>>()),
        vol_corr_range: med(&per
            .iter()
            .filter_map(|p| p.volume.map(|v| v.1))
            .collect::<Vec<f64>>()),
    })
}

/// Everything `measure` takes a median of from ONE path. Computed in a single parallel pass per
/// ensemble: as separate passes -- one per statistic, about twenty-five over the same paths -- each
/// carried too little work to pay for its own fork and join, and a 60-path ensemble on 24 cores
/// measured only 4.5 times faster than one path at a time. Every field is the expression `measure`
/// computed per path, so no median moves.
struct PathRead {
    episodes: Vec<Episode>,
    dd_eq: (f64, f64, f64),
    dd_bd: (f64, f64, f64),
    vol: f64,
    kurt: f64,
    /// the typical year: `year_vol_of`
    year_vol: f64,
    /// clustering at lags 1, 20, 5 and 60
    ac: [f64; 4],
    /// the leverage profile at lags 1, 5 and 20
    lev: [f64; 3],
    /// variance ratios at q = 20, `VAR_RATIO_Q`, 120 and 250
    vr: [f64; 4],
    ret_ac1: f64,
    ann_ret: f64,
    div_yield: f64,
    bond_vol: Vec<f64>,
    /// the bond's move over each episode outside, and inside, an inflation regime
    bond_growth: Vec<f64>,
    bond_infl: Vec<f64>,
    corr_calm: f64,
    corr_infl: f64,
    val_disp: f64,
    /// the cycle's wings about its 20-year mean, as COUNTS over `wings_of`'s sessions
    wing_up: f64,
    wing_down: f64,
    wing_n: f64,
    max_over: f64,
    semi_excess: f64,
    lev_corr: f64,
    tail_hedge: f64,
    infl_ann: f64,
}

#[expect(
    clippy::too_many_lines,
    reason = "every per-path statistic `measure` takes a median of, in one place"
)]
fn path_read(s: &Path, years: usize) -> PathRead {
    let dpy = DAYS_PER_YEAR as f64;
    let r = daily_returns(&s.price);
    // once per path (was recomputed 3x)
    let eps = episodes(&s.price, 15.0);
    let bond_in_windows = |infl_regime: bool| -> Vec<f64> {
        eps.iter()
            .filter(|ep| {
                let sum: f64 = scala_sum((ep.peak..=ep.trough).map(|k| s.infl_press[k]));
                let infl = sum / 1.max(ep.trough - ep.peak + 1) as f64;
                (infl > 0.005) == infl_regime
            })
            .map(|ep| (s.bond[ep.trough] / s.bond[ep.peak]).ln() * 100.0)
            .collect()
    };
    let corr_in = |infl_regime: bool| -> f64 {
        let idx: Vec<usize> = (1..s.price.len())
            .filter(|&i| (s.infl_press[i] > 0.005) == infl_regime)
            .collect();
        let a: Vec<f64> = idx
            .iter()
            .map(|&i| (s.price[i] / s.price[i - 1]).ln())
            .collect();
        let b: Vec<f64> = idx
            .iter()
            .map(|&i| (s.bond[i] / s.bond[i - 1]).ln())
            .collect();
        pearson(&a, &b)
    };
    let ac = autocorrs_abs(&r, &[1, 20, 5, 60]);
    let wings = wings_of(&s.price, &s.fundamental);
    PathRead {
        dd_eq: depth_shares(&s.price),
        dd_bd: depth_shares(&s.bond),
        vol: (MatD::apply(&r).power(2).mean() * dpy).sqrt(),
        year_vol: year_vol_of(&r),
        kurt: kurtosis(&r),
        ac: [ac[0], ac[1], ac[2], ac[3]],
        lev: [lev_abs(&r, 1), lev_abs(&r, 5), lev_abs(&r, 20)],
        vr: [
            variance_ratio(&r, 20),
            variance_ratio(&r, VAR_RATIO_Q),
            variance_ratio(&r, 120),
            variance_ratio(&r, 250),
        ],
        ret_ac1: level_autocorr(&r, 1),
        ann_ret: (s.price[s.price.len() - 1] / s.price[0]).ln() / years as f64 * 100.0,
        div_yield: if s.div_yield.is_empty() {
            f64::NAN
        } else {
            s.div_yield.iter().sum::<f64>() / s.div_yield.len() as f64
        },
        // Median over non-overlapping BOND_VOL_YEARS windows, pooled across paths — see
        // BOND_VOL_YEARS for why this row alone is windowed. A path shorter than one window
        // contributes itself, so a short run still reports something rather than nothing.
        bond_vol: {
            let rb = daily_returns(&s.bond);
            let w = BOND_VOL_YEARS * DAYS_PER_YEAR;
            let nw = rb.len() / w;
            let segs: Vec<Vec<f64>> = if nw < 1 {
                vec![rb.clone()]
            } else {
                (0..nw).map(|k| rb[k * w..(k + 1) * w].to_vec()).collect()
            };
            segs.into_iter()
                .map(|seg| (MatD::apply(&seg).power(2).mean() * dpy).sqrt())
                .collect()
        },
        bond_growth: bond_in_windows(false),
        bond_infl: bond_in_windows(true),
        corr_calm: corr_in(false),
        corr_infl: corr_in(true),
        val_disp: {
            let g: Vec<f64> = s
                .price
                .iter()
                .zip(s.fundamental.iter())
                .map(|(p, f)| (p / f).ln())
                .collect();
            let m = scala_sum(g.iter().copied()) / g.len() as f64;
            (scala_sum(g.iter().map(|x| (x - m) * (x - m))) / (g.len() - 1) as f64).sqrt()
        },
        wing_up: wings.0,
        wing_down: wings.1,
        wing_n: wings.2,
        max_over: s
            .price
            .iter()
            .zip(s.fundamental.iter())
            .map(|(p, f)| (p / f).ln())
            .fold(f64::MIN, f64::max),
        // the path's own returns, where these two each made a fresh copy of the same numbers
        semi_excess: {
            let d = scala_sum(r.iter().filter(|x| **x < 0.0).map(|x| x * x));
            let u = scala_sum(r.iter().filter(|x| **x > 0.0).map(|x| x * x));
            if u > 0.0 {
                ((d / u).sqrt() - 1.0) * 100.0
            } else {
                f64::NAN
            }
        },
        lev_corr: {
            let a: Vec<f64> = r[..r.len() - 1].to_vec();
            let b: Vec<f64> = r[1..].iter().map(|x| x * x).collect();
            pearson(&a, &b)
        },
        tail_hedge: {
            let idx: Vec<usize> = (1..s.price.len())
                .filter(|&i| s.infl_press[i] <= 0.005)
                .collect();
            let re: Vec<f64> = idx
                .iter()
                .map(|&i| (s.price[i] / s.price[i - 1]).ln())
                .collect();
            let rb: Vec<f64> = idx
                .iter()
                .map(|&i| (s.bond[i] / s.bond[i - 1]).ln())
                .collect();
            let q = pctile(&re, 0.10);
            let ta: Vec<f64> = re.iter().copied().filter(|x| *x < q).collect();
            let tb: Vec<f64> = re
                .iter()
                .zip(rb.iter())
                .filter(|(x, _)| **x < q)
                .map(|(_, y)| *y)
                .collect();
            // A tail too small to correlate is unmeasurable, not zero — the same rule the
            // 24-year bond windows apply.
            if ta.len() < 30 {
                f64::NAN
            } else {
                pearson(&ta, &tb)
            }
        },
        infl_ann: (s.cpi[s.cpi.len() - 1] / s.cpi[0]).ln() / years as f64 * 100.0,
        episodes: eps,
    }
}

#[expect(
    clippy::too_many_lines,
    reason = "one line per statistic, mirroring the Scala twin's WorldStats construction; splitting it would put a reading somewhere other than beside the others"
)]
pub fn measure(sims: &[Path], years: usize) -> WorldStats {
    // THE PER-PATH STATISTICS, ACROSS CORES AND IN ONE PASS: `path_read` computes each path's
    // readings and `collect` keeps path order, so every median below reads what it always did.
    let per: Vec<PathRead> = sims.par_iter().map(|s| path_read(s, years)).collect();
    let med_by = |f: fn(&PathRead) -> f64| med(&per.iter().map(f).collect::<Vec<f64>>());
    let eps: Vec<Episode> = per
        .iter()
        .flat_map(|p| p.episodes.iter().copied())
        .collect();
    let shapes: Vec<f64> = eps
        .iter()
        .map(|e| e.shape())
        .filter(|x| !x.is_nan())
        .collect();
    let days: f64 = scala_sum(sims.iter().map(|s| s.price.len() as f64));
    let n_sims = sims.len() as f64;
    // POOLED, not a median of per-path shares: most paths hold no tail session at all, so a median
    // would read 0 forever and the check built on it could not fail.
    let tail_sessions: usize = sims.iter().map(|s| s.eq_tail_days).sum();
    let tail_floor_share = if tail_sessions == 0 {
        0.0
    } else {
        scala_sum(sims.iter().map(|s| s.eq_floor_days as f64)) * 100.0 / tail_sessions as f64
    };
    let depths: Vec<f64> = eps.iter().map(|e| e.depth_pct).collect();

    WorldStats {
        vol: med_by(|p| p.vol),
        year_vol: med_by(|p| p.year_vol),
        kurt: med_by(|p| p.kurt),
        ac1: med_by(|p| p.ac[0]),
        ac20: med_by(|p| p.ac[1]),
        ac5: med_by(|p| p.ac[2]),
        ac60: med_by(|p| p.ac[3]),
        lev1: med_by(|p| p.lev[0]),
        lev5: med_by(|p| p.lev[1]),
        lev20: med_by(|p| p.lev[2]),
        vr20: med_by(|p| p.vr[0]),
        vr60: med_by(|p| p.vr[1]),
        vr120: med_by(|p| p.vr[2]),
        vr250: med_by(|p| p.vr[3]),
        ret_ac1: med_by(|p| p.ret_ac1),
        ann_ret: med_by(|p| p.ann_ret),
        n_episodes: eps.len(),
        ep_per_path: eps.len() as f64 / n_sims,
        sat: sat_stats(sims, years),
        bars: bar_stats(sims),
        open: open_stats(sims),
        basket: basket_stats(sims),
        macro_panel: macro_stats(sims),
        div_yield_mean: med_by(|p| p.div_yield),
        depth_med: med(&depths),
        worst_depth: if depths.is_empty() {
            f64::NAN
        } else {
            sorted_total(&depths)[0]
        },
        v_count: shapes.iter().filter(|&&x| x > 1.5).count(),
        mid_count: shapes
            .iter()
            .filter(|&&x| (0.67..=1.5).contains(&x))
            .count(),
        u_count: shapes.iter().filter(|&&x| x < 0.67).count(),
        n_shapes: shapes.len(),
        censored: eps.iter().filter(|e| e.censored()).count(),
        clamp_pct: scala_sum(sims.iter().map(|s| s.clamped_days as f64)) / days * 100.0,
        halt_pct: scala_sum(sims.iter().map(|s| s.eq_halt_days as f64)) / days * 100.0,
        tail_floor_pct: tail_floor_share,
        trend_share: scala_sum(sims.iter().map(|s| s.mean_trend_share)) / n_sims,
        years_per_path: years as f64,
        trend_pinned: scala_sum(sims.iter().map(|s| s.trend_pinned)) / n_sims,
        target_sat: scala_sum(sims.iter().map(|s| s.target_sat)) / n_sims,
        bond_vol: med(&per
            .iter()
            .flat_map(|p| p.bond_vol.iter().copied())
            .collect::<Vec<f64>>()),
        bond_growth: med(&per
            .iter()
            .flat_map(|p| p.bond_growth.iter().copied())
            .collect::<Vec<f64>>()),
        bond_infl: med(&per
            .iter()
            .flat_map(|p| p.bond_infl.iter().copied())
            .collect::<Vec<f64>>()),
        corr_calm: med_by(|p| p.corr_calm),
        corr_infl: med_by(|p| p.corr_infl),
        mean_bond_stress: scala_sum(sims.iter().map(|s| s.mean_bond_stress)) / n_sims,
        pct_bond_stress: scala_sum(sims.iter().map(|s| s.pct_bond_stress)) / n_sims,
        crowd_flow: scala_sum(sims.iter().map(|s| s.mean_crowd_flow)) / n_sims,
        dis_per_century: scala_sum(sims.iter().map(|s| s.disasters as f64)) / n_sims / years as f64
            * 100.0,
        val_disp: med_by(|p| p.val_disp),
        // POOLED (the tail-floor share's rule): a spell past +0.5 is once in decades, and a
        // median of per-path shares would read 0 forever
        wing_up: pooled_share(per.iter().map(|p| p.wing_up), per.iter().map(|p| p.wing_n)),
        wing_down: pooled_share(
            per.iter().map(|p| p.wing_down),
            per.iter().map(|p| p.wing_n),
        ),
        max_over: med_by(|p| p.max_over),
        semi_excess: med_by(|p| p.semi_excess),
        lev_corr: med_by(|p| p.lev_corr),
        tail_hedge: med_by(|p| p.tail_hedge),
        duration: sims[0].duration,
        infl_ann: med_by(|p| p.infl_ann),
        dd_eq5: med_by(|p| p.dd_eq.0),
        dd_eq10: med_by(|p| p.dd_eq.1),
        dd_eq20: med_by(|p| p.dd_eq.2),
        dd_bd5: med_by(|p| p.dd_bd.0),
        dd_bd10: med_by(|p| p.dd_bd.1),
        dd_bd20: med_by(|p| p.dd_bd.2),
    }
}

/// The gate answers three different questions and used to report one verdict. Each class names
/// what a failure costs, and a report declares which classes it requires (`-gate`).
///
/// [`GateClass::Realism`] asks "is this world a market at all". Its checks are unconditional
/// distributional properties of the whole sample, and a failure invalidates every conclusion
/// drawn here.
///
/// [`GateClass::Mechanism`] asks "is this mechanism engaged in this world". Its checks are all
/// conditional on crash or inflation EPISODES, and a failure invalidates only conclusions that
/// lean on the named mechanism. A world can be a perfectly good market with an inert bond
/// spiral — the duration-6y world is exactly that, and a single verdict discarded it from every
/// pooled panel.
///
/// [`GateClass::Fidelity`] asks "can this quantity's LEVEL be read here". A failure invalidates
/// only conclusions that read a level off the named quantity — a time-out-of-market, a
/// percentile threshold, a drawdown-conditioned hazard — and leaves rank comparisons, cost
/// breakevens, ruin rates and refuge mechanics untouched. It exists because a world can pass
/// every realism band and every mechanism check while a statistic those bands do not pin sits
/// far from reality: the default world's bond spends 84% of sessions more than 10% below its
/// running peak where a real long Treasury spends 51%, and a 10%-drawdown gate REVERSES SIGN
/// between them.
///
/// The realism/mechanism split also explains the export-time false alarm: the four conditional
/// statistics cannot be measured from one short path, so `-emit` takes its verdict from an
/// ensemble (`-emitgate`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum GateClass {
    Realism,
    Mechanism,
    Fidelity,
}

impl GateClass {
    /// Printed order, and the order every verdict list follows.
    const ALL: [Self; 3] = [Self::Realism, Self::Mechanism, Self::Fidelity];

    fn label(self) -> &'static str {
        match self {
            Self::Realism => "realism",
            Self::Mechanism => "mechanism",
            Self::Fidelity => "fidelity",
        }
    }

    /// Heading and what a failure costs. Kept beside the enum so a new class cannot be added
    /// without saying out loud which conclusions it kills.
    fn section(self) -> (&'static str, &'static str) {
        match self {
            Self::Realism => (
                "realism bands",
                "a failure here means this world is not a market",
            ),
            Self::Mechanism => (
                "mechanism engagement",
                "a failure here means only that mechanism is inert",
            ),
            Self::Fidelity => (
                "level fidelity",
                "a failure here means only that quantity's LEVEL cannot be read",
            ),
        }
    }
}

#[expect(
    clippy::panic,
    reason = "an unknown rung is a programming error: the ladder is a const and every caller iterates it"
)]
fn vr_of(st: &WorldStats, q: usize) -> f64 {
    match q {
        20 => st.vr20,
        60 => st.vr60,
        120 => st.vr120,
        250 => st.vr250,
        _ => panic!("no variance-ratio rung at {q} sessions"),
    }
}

/// The variance-ratio ladder as ONE fidelity row: every rung inside its envelope and both short
/// slopes inside theirs. The name is derived from the bounds, as `band_check`'s is, so it cannot
/// read as bounds it does not enforce; the report's `trend persistence` lines show which rung or
/// slope failed.
fn var_ratio_profile_check(st: &WorldStats) -> (String, bool, GateClass) {
    let rungs: Vec<(String, bool)> = VAR_RATIO_BANDS
        .iter()
        .map(|&(q, lo, hi)| {
            let v = vr_of(st, q);
            (format!("{q}d {lo:.2}-{hi:.2}"), v > lo && v < hi)
        })
        .collect();
    let slopes: Vec<(String, bool)> = VAR_RATIO_SLOPE_BANDS
        .iter()
        .map(|&(a, b, lo, hi)| {
            let sl = vr_of(st, b) - vr_of(st, a);
            (format!("{a}->{b} {lo:+.2}..{hi:+.2}"), sl > lo && sl < hi)
        })
        .collect();
    let name = format!(
        "variance-ratio profile {}, slopes {}",
        rungs
            .iter()
            .map(|r| r.0.as_str())
            .collect::<Vec<_>>()
            .join(" "),
        slopes
            .iter()
            .map(|r| r.0.as_str())
            .collect::<Vec<_>>()
            .join(" ")
    );
    let pass = rungs.iter().chain(slopes.iter()).all(|r| r.1);
    (name, pass, GateClass::Fidelity)
}

/// A gate whose printed name is DERIVED from the bounds its predicate tests, so the two cannot
/// drift apart — the failure mode where a gate reads as bounds it does not enforce. Every
/// two-sided band that can go through here does: a hand-written "0.65-1.35" inside a name is the
/// same defect this helper exists to prevent, wherever it is written.
///
/// `dp` is printed PRECISION, not tolerance: the depth rungs read 0.215-0.415 and are quoted at
/// that precision in the CHANGELOG and the upgrade plan, while the duration ratios read
/// 0.70-1.10. `unit` is whatever follows the band in the name. A caller whose printed units differ
/// from the statistic's passes the CONVERTED value (`st.vol * 100.0` against 8-40), so the band
/// and the value compared against it are in the same units by construction.
///
/// Two bands stay hand-written, because the name would stop describing the predicate if they came
/// through here: `clustering` also enforces an ac20 floor and `crash rate` also requires at least
/// one episode. Both are two-sided with visible bounds; what they are not is one clause.
#[expect(
    clippy::too_many_arguments,
    reason = "the Scala twin's parameter list, which uses named arguments for the same seven;               collapsing any pair here would make the two signatures stop reading alike"
)]
fn band_check(
    name: &str,
    got: f64,
    lo: f64,
    hi: f64,
    cls: GateClass,
    dp: i32,
    unit: &str,
) -> (String, bool, GateClass) {
    (
        format!("{name} {}-{}{unit}", jf(lo, 0, dp), jf(hi, 0, dp)),
        got > lo && got < hi,
        cls,
    )
}

/// The horizon the verdict ensemble runs at: every band and anchor weight was calibrated on
/// 100-year ensembles, and several graded statistics move with the measurement window — the
/// valuation gap's dispersion is the sample sd of a near-integrated process (0.11 at 30 years,
/// 0.21 at 100, against floors set from the 100-year record), and the depth shares and
/// clustering carry the century's regime mix. A fixed band read at the caller's `-years`
/// grades the horizon, not the world. The report section still describes the caller's
/// ensemble; only the verdict is pinned.
const GATE_YEARS: usize = 100;

/// The REALISM bands on statistics a FIDELITY row also targets, as data. The gate reads them and
/// so does the contract that no edge sits inside a target's own noise -- a test that restated
/// the literals would pass forever after someone moved one here.
pub const REALISM_VOL: (f64, f64) = (8.0, 40.0); // equity vol, % a year
pub const REALISM_KURT: (f64, f64) = (4.0, 40.0); // daily kurtosis
pub const REALISM_AC1: (f64, f64) = (0.10, 0.40); // lag-1 clustering
pub const REALISM_CRASHES: (f64, f64) = (8.0, 55.0); // 20% declines a century

/// The (paths, years) the verdict — gate classes, fidelity table, every emitted sidecar — is
/// measured on: `GATE_YEARS` always, on the larger of the report and `-emitgate` ensembles.
/// `-emitgate 0` is the caller's explicit request to grade the emitted ensemble itself,
/// caller's horizon and all. Equal to (paths, years) exactly when the report ensemble already
/// is the verdict ensemble — which at the defaults it is: same seed, same draws.
pub fn verdict_spec(
    emitting: bool,
    emit_gate: usize,
    paths: usize,
    years: usize,
) -> (usize, usize) {
    if emitting && emit_gate == 0 {
        (paths, years)
    } else if emitting && emit_gate > paths {
        (emit_gate, GATE_YEARS)
    } else {
        (paths, GATE_YEARS)
    }
}

/// TWO-SIDED wherever a plausible range exists. History of this gate: a one-sided version
/// passed a 35%-volatility world (the one reversing the ranking); a "bonds fail" check
/// written as bondInfl < bondGrowth passed while bonds still RALLIED +2.8; crash frequency
/// shipped without an upper bound WHILE the one-sided lesson was being applied elsewhere.
#[expect(
    clippy::too_many_lines,
    reason = "one table of bands, mirroring the Scala twin's gateChecks row for row"
)]
#[expect(
    clippy::cognitive_complexity,
    reason = "one row per graded quantity in gate order, each channel's rows behind its own \
              presence test; splitting it would scatter the order the report and the sidecar \
              print"
)]
pub fn gate_checks(a: Anchors, st: &WorldStats) -> Vec<(String, bool, GateClass)> {
    use GateClass::Mechanism;
    use GateClass::Realism;
    let pc = st.ep_per_path * 100.0 / st.years_per_path;
    let n = |s: &str| s.to_string();
    let mut v = vec![
        // MEASURED, not assumed. 8-25% was the S&P's shape and it asserted of 17 of the 35 real
        // equity instruments in `test-data/equity-anchors` that they are not markets — QQQ (26.9%),
        // Taiwan, Brazil, semiconductors, energy and most of Europe. That is the same failure the
        // bond band below already records ("of eight real funds it admitted one"). A REALISM band
        // answers "is this a market at all", so it must admit every market anyone has measured: the
        // 35 instruments span 15.2-37.4% over the clean w1996 window, and 8-40 rounds outward from
        // that. The FIDELITY band — now `Anchors::vol_band`, 14-18% for the S&P and 24-30% for the
        // Nasdaq — is what answers "is this THIS market", and it stayed narrow.
        band_check(
            "equity vol",
            st.vol * 100.0,
            REALISM_VOL.0,
            REALISM_VOL.1,
            Realism,
            0,
            "%",
        ),
        // WIDENED from 4-30 for the same reason as the volatility band above, and it is the
        // same failure: 30 sits two points above the S&P FIDELITY target of 28.0, so the band
        // called the actual S&P century not a market. `measure` reads kurtosis as a MEDIAN over
        // paths and a single century of it has a relative sd of 0.97, so the median's own
        // spread is 1.9 at the 200-path scoring ensemble and 2.8 at a 60-path search ensemble
        // -- the record failed on roughly a seed in four, which made this one row a THIRD of a
        // calibration search's rejections and biased the surviving set light-tailed. 40 clears
        // 28.0 by three of those spreads, the margin `contract_tests` now asserts for every row
        // graded in both classes. The cross-section would be the better ruler, as it is for
        // volatility, but `test-data/equity-anchors` carries no kurtosis column. The FIDELITY
        // target is untouched at 28.0 / 9.55: that is the row answering "is this THIS market".
        band_check(
            "kurtosis",
            st.kurt,
            REALISM_KURT.0,
            REALISM_KURT.1,
            Realism,
            0,
            "",
        ),
        (
            format!("clustering {:.2}-{:.2}", REALISM_AC1.0, REALISM_AC1.1),
            st.ac1 > REALISM_AC1.0 && st.ac1 < REALISM_AC1.1 && st.ac20 > 0.03,
            Realism,
        ),
        // Widened from 8-45 for the same reason as the volatility band above: 45 excluded two of
        // the 35 real instruments (EWA, EWW), which read 49.4 and 46.6 over the clean w1996 window
        // against a cross-section range of 13.2-49.4. A band that calls a real market unreal is not
        // a realism check.
        (
            format!(
                "crash rate {:.0}-{:.0}/century",
                REALISM_CRASHES.0, REALISM_CRASHES.1
            ),
            st.ep_per_path >= 1.0 && pc >= REALISM_CRASHES.0 && pc <= REALISM_CRASHES.1,
            Realism,
        ),
        (
            n("both recovery shapes"),
            // max(1, _) is load-bearing. n_shapes / 10 is INTEGER division, so below ten shapes
            // both clauses read ">= 0" and the check passes with NEITHER shape present —
            // measured at -drift 0.9, which produced V=0, balanced=1, U=0 and passed a check
            // named "both recovery shapes". It degenerated exactly where episodes are scarce,
            // which is where shape evidence is weakest and the check matters most. Requiring at
            // least one of each makes too-few-shapes FAIL: a gate that passes on no evidence
            // reads as verification.
            st.n_shapes > 0
                && st.v_count >= 1.max(st.n_shapes / 10)
                && st.u_count >= 1.max(st.n_shapes / 10),
            Realism,
        ),
        (n("no runaway drift"), st.ann_ret.abs() < 30.0, Realism),
        // 0.02% ~ one clamped session per 20 path-years. The old bound (0.5%) would have
        // passed a world where the clamp was already reshaping kurtosis by a third.
        (n("clamp rarely binds"), st.clamp_pct < 0.02, Realism),
        // THE DENOMINATOR IS THE POINT. `clamp_pct` measures the guard against ALL sessions, where
        // it is negligible by construction and passes in worlds whose worst sessions are ENTIRELY
        // its doing. This measures it against the tail it actually touches. Both are kept: one says
        // the guard is not distorting the body, the other that it is not authoring the tail.
        (n("clamp shapes no tail"), st.tail_floor_pct < 2.0, Realism),
        // RELATIVE to duration, not absolute. The old 7-20% band was TLT's: of eight real funds
        // it admitted one, and asserted of the US Aggregate (4.24%) that it is not a market.
        // 0.5-2.5 per year of duration admits every fund measured, high yield at 2.001 included,
        // and still catches a bond whose volatility bears no relation to what it is.
        band_check(
            "bond vol",
            st.bond_vol_per_year(),
            0.5,
            2.5,
            Realism,
            1,
            "x duration",
        ),
        (
            n("bonds rally in growth shocks"),
            st.bond_growth > 3.0,
            Mechanism,
        ),
        (
            n("bonds LOSE in inflation regimes"),
            st.bond_infl < -3.0,
            Mechanism,
        ),
        (
            n("corr flips positive under inflation"),
            !st.corr_infl.is_nan()
                && !st.corr_calm.is_nan()
                && st.corr_infl > st.corr_calm + 0.15
                && st.corr_infl > 0.0
                && st.corr_calm < 0.35,
            Mechanism,
        ),
        (
            n("bond spiral engages, not always"),
            st.pct_bond_stress > 0.002 && st.pct_bond_stress < 0.5,
            Mechanism,
        ),
        // Two-sided like the spiral's: the channel must strike, and disasters that arrive more
        // than a few times a century are not disasters — they are a second volatility regime
        // wearing the name. An off-world (rate 0) fails this row, which is what a mechanism row
        // MEANS.
        (
            n("macro disasters strike, not every decade"),
            st.dis_per_century > 0.05 && st.dis_per_century < 4.0,
            Mechanism,
        ),
        // The valuation cycle's engagement row. The floor fails a world without the mechanism
        // (the disaster-only default read 0.095); the ceiling is the unmoored guard — a
        // dispersion past 0.70 means perceived fair has lost the fundamental.
        (
            n("valuation cycle engages, not unmoored"),
            st.val_disp > 0.13 && st.val_disp < 0.70,
            Mechanism,
        ),
        band_check("inflation", st.infl_ann, 1.0, 6.0, Realism, 0, "%/yr"),
        // LEVEL bands, not realism. A 12%-volatility market is still a market, and realism is
        // ALWAYS required — either band placed there would make the sweep's own OFF-worlds
        // inadmissible in every report ("no liquidity spiral" runs at 12.6% vol, "low growth" at
        // 0.34). Class does not weaken them as a search constraint: the calibration loss counts
        // 0.5 per failed check whatever the class. Volatility keeps its realism band as well —
        // 8-40% answers "is this a market", the anchor's own band "can its level be read".
        band_check(
            "equity vol",
            st.vol * 100.0,
            a.vol_band.0,
            a.vol_band.1,
            GateClass::Fidelity,
            0,
            "%",
        ),
        band_check(
            "typical-year vol",
            st.year_vol * 100.0,
            a.year_vol_band.0,
            a.year_vol_band.1,
            GateClass::Fidelity,
            0,
            "%",
        ),
        // 0.50 clears the 1926-2026 reading (0.55) downward; 0.85 sits above the 1954-2026 anchor
        // (0.69) and below the most favourable non-overlapping 20-year block the record produced
        // (0.93). A world may be as favourable as a long-horizon market, not as favourable as its
        // luckiest two decades. The 20-year block SPREAD (0.47-0.93) is deliberately NOT the band:
        // that is sampling variation in a 20-year window, and this statistic is a population value
        // over 20,000 path-years — a band drawn from it would readmit worlds at 0.91.
        band_check(
            "return per vol",
            st.ret_vol(),
            a.ret_vol_band.0,
            a.ret_vol_band.1,
            GateClass::Fidelity,
            2,
            "",
        ),
        // SIGNED persistence at three months. FIDELITY and not realism, for the reason stated above:
        // `-crowdimpact 0.12` is one of the sweep's own OFF-worlds — pressing the reflexive channel
        // hard is what it is FOR — and a realism band would make it inadmissible in every report
        // rather than describing it. What a failure here costs is specific and large: every
        // trailing-window statistic read off this world is read against the wrong null. A momentum
        // rule's information coefficient, a p-value calibrated on synthetic paths, a
        // drawdown-conditioned hazard — all of them inherit the trend this row measures, and none of
        // the other fifteen targets can see it.
        var_ratio_profile_check(st),
        // Anchored on the record's CAPE dispersion (valuation-2026-08-30.tsv: 0.24-0.41 across
        // windows). A BAND, never a point ratio: the record has no observable fair value and
        // CAPE is a proxy, so the floor sits a stated haircut below the calmest window.
        band_check(
            "valuation dispersion",
            st.val_disp,
            VAL_DISP_BAND.0,
            VAL_DISP_BAND.1,
            GateClass::Fidelity,
            2,
            "",
        ),
    ];
    // The equity depth relation is anchor-fitted too, so it refuses outside its anchors' volatility
    // range for the same reason the two below do. That range starts at 14.3%, so the sweep's own
    // calm off-worlds are disclosed rather than failed — "no fund this quiet was measured" is not
    // the same finding as "this market's drawdowns are wrong".
    if anchored(st.vol * 100.0, EQUITY_VOL_SUPPORT, st.eq_d10_vs_real()) {
        v.push(band_check(
            "equity d5 vs real",
            st.eq_d5_vs_real(),
            EQUITY_D5_BAND.0,
            EQUITY_D5_BAND.1,
            GateClass::Fidelity,
            2,
            "",
        ));
        v.push(band_check(
            "equity d10 vs real",
            st.eq_d10_vs_real(),
            EQUITY_D10_BAND.0,
            EQUITY_D10_BAND.1,
            GateClass::Fidelity,
            2,
            "",
        ));
    }
    // The two anchor-fitted bands are graded ONLY where their anchors have data — the same
    // refusal `-crossasset` applies, because these ARE its relations. A world outside the funds'
    // range used to print FAIL here while the ladder printed n/a for the same statistic,
    // conflating "the level is wrong" with "there is no anchor to compare against". A skipped
    // check is disclosed by `unanchored_in` — in `-validate` and the sidecar — never silently
    // absent.
    if anchored(
        st.bond_vol * 100.0,
        BOND_VOL_SUPPORT,
        st.bond_depth_vs_vol(),
    ) {
        // Against what this bond's OWN volatility implies, not against TLT's 0.510 — see
        // `bond_depth_vs_vol`. The +-0.35 is the real fit's own scatter (credit funds sit below
        // the Treasury line); the default reads 1.24, so it uses about two thirds of it.
        v.push(band_check(
            "bond depth vs its vol",
            st.bond_depth_vs_vol(),
            BOND_D10_BAND.0,
            BOND_D10_BAND.1,
            GateClass::Fidelity,
            2,
            "",
        ));
    }
    if anchored(st.duration, BOND_DUR_SUPPORT, st.bond_vol_per_year()) {
        // Treasuries run 0.798-0.973 and investment grade 0.745-0.824; high yield (2.001) is out
        // of scope until there is a credit channel, so the upper bound deliberately excludes it.
        v.push(band_check(
            "bond vol",
            st.bond_vol_per_year(),
            BOND_VOL_PER_YEAR_BAND.0,
            BOND_VOL_PER_YEAR_BAND.1,
            GateClass::Fidelity,
            2,
            "x duration",
        ));
    }
    // THE SATELLITE LEG, graded — present only when a leg ran, so a satellite-off world's gate is
    // byte-identical to what it always was. Bit-identical-off is what makes a channel safe to
    // add and is ALSO what makes it invisible to a verdict computed from the primary alone; these
    // rows are the answer to that, so an emitted `logSat` is covered by the gate that travels
    // beside it rather than merely disclosed as uncovered.
    //
    // RATIOS to the primary leg, never levels. The satellite is a coupled second leg at this
    // world's own scale and is not claimed to be any index, so what can be graded is the RELATION
    // a higher-beta second leg holds to its primary — the same doctrine the depth rungs use when
    // they grade each world at its own volatility. Anchors are QQQ against SPY over their shared
    // 1999-2026 window (`joint-coupling-2026-08-31.tsv`), and every band is that record reading
    // widened to the spread its own 5-year blocks show, because one history pins a ratio far more
    // loosely than it pins a level.
    if let Some(sd) = st.sat {
        for (name, got, lo, hi, dp) in [
            ("satellite corr", sd.corr, 0.75, 0.95, 2),
            ("satellite |r| corr", sd.abs_corr, 0.65, 0.90, 2),
            ("satellite beta", sd.beta, 1.00, 1.45, 2),
            ("satellite vol ratio", sd.vol_ratio, 1.20, 1.60, 2),
            // The record's 5y blocks read 0.55-1.12 on this ratio and QQQ's kurtosis is LOWER
            // than SPY's over the shared window (9.6 vs 14.3) — a wide band because the record
            // is wide, not because the model needs room.
            ("satellite kurtosis ratio", sd.kurt_ratio, 0.45, 1.20, 2),
            ("satellite clustering-1 ratio", sd.ac1_ratio, 0.85, 1.20, 2),
            (
                "satellite clustering-20 ratio",
                sd.ac20_ratio,
                0.85,
                1.40,
                2,
            ),
            ("satellite d5 ratio", sd.d5_ratio, 1.00, 1.70, 2),
            ("satellite d10 ratio", sd.d10_ratio, 0.70, 2.20, 2),
            // DISCLOSED TENSION, not a pass by construction: the model's leg opens ~1.6 crash
            // episodes per primary episode against the record's 1.17. One history cannot resolve
            // this ratio at all — SPY and QQQ show ~6 and ~7 episodes in 27 years, and the 5-year
            // blocks read 1.00-2.00 — so the band admits the model while the central tendency
            // stays high. Read it as ungraded in practice; it is here to catch a leg that
            // crashes several times as often as its primary, which would be a broken coupling.
            ("satellite crash ratio", sd.crash_ratio, 0.80, 2.40, 2),
        ] {
            v.push(band_check(name, got, lo, hi, GateClass::Fidelity, dp, ""));
        }
    }
    // THE BAR CHANNELS, graded — same reasoning as the satellite rows above, and the same bands
    // the build-time suites already assert from `bars-2026-09-01.tsv`, carried into the RUNTIME
    // gate so an emitted bar is covered by the verdict beside it. Range rows appear when the
    // range channel ran; the volume rows only when the volume channel ran too.
    if let Some(b) = st.bars {
        for (name, got, lo, hi) in [
            ("bar range vs cc vol", b.range_over_ccvol, 1.00, 1.20),
            ("bar range clustering", b.range_acf1, 0.57, 0.77),
            // Against the INTRADAY ruler (1.109-1.142): with `overnight` off the bar has no
            // overnight, so the record's close-to-close down/up of 1.175-1.205 carries
            // conditioning it cannot have; with it on the coupling reads the intraday return.
            ("bar range down/up", b.range_downup, 1.00, 1.30),
        ] {
            v.push(band_check(name, got, lo, hi, GateClass::Fidelity, 2, ""));
        }
        if b.vol_sd.is_finite() {
            for (name, got, lo, hi) in [
                ("bar volume sd", b.vol_sd, 0.40, 0.60),
                ("bar volume vs range", b.vol_corr_range, 0.44, 0.64),
            ] {
                v.push(band_check(name, got, lo, hi, GateClass::Fidelity, 2, ""));
            }
        }
    }
    // THE DIVIDEND LEVEL, graded when the dial is on: the yield at fair value is an identity
    // parameter, so this row can only catch a dial set outside what the record's own annual
    // means span — `dividend-2026-09-02.tsv`.
    if st.div_yield_mean.is_finite() {
        v.push(band_check(
            "dividend yield %",
            st.div_yield_mean,
            a.div_yield_band.0,
            a.div_yield_band.1,
            GateClass::Fidelity,
            1,
            "",
        ));
    }
    // THE OPEN, graded when it ran: the overnight share against the record's (0.33 SPY / 0.28
    // QQQ, `bars-2026-09-01.tsv`, tol 0.10 like the other bar rows), and the mechanism the
    // record shows — its worst sessions open with more of the day already gone.
    if let Some(os) = st.open {
        v.push(band_check(
            "bar overnight share",
            os.overnight_share,
            0.23,
            0.43,
            GateClass::Fidelity,
            2,
            "",
        ));
        v.push((
            "overnight gap share rises on the worst sessions".to_string(),
            os.worst_gap_share > os.all_gap_share,
            Mechanism,
        ));
    }
    // THE BASKET, graded when it ran — `basket-2026-09-02.tsv`, the eight semis under SMH: level 1
    // as a POPULATION (the names' vol 1.9-3.5x SPY's or 1.5-2.8x QQQ's, gaps 0.4-5.1/yr — the
    // eight's ranges rounded outward, graded on the pooled median), level 2 the aggregate against
    // the set's primary (the eight's basket on SPY: corr 0.77, beta 1.56, vol 2.0x; on QQQ: 0.84,
    // 1.37, 1.63x; +-0.10 / +-0.25 / +-0.3), level 3 the structure a
    // basket rule reads (pairwise 0.59, idio share 0.37, tail coincidence 0.48), and the
    // mechanism: pairwise correlation on the primary's worst decile above its middle decile (0.60
    // vs 0.28). The names' d20 is REPORTED, not graded: the eight's 0.08-0.61 is the time below
    // peak of names selected today as winners (the survivorship the fixture discloses), and a
    // name at the sector's drift and 2.6x the index's volatility spends most of a century more
    // than 20% below its peak, as a real name of that drift would.
    if let Some(b) = st.basket {
        // level 2 bands from the set's anchor: corr +-0.10 at the row's 0.01 (the anchor carries a
        // third decimal the row does not print), beta +-0.25 and vol ratio +-0.3 rounded outward
        // to 0.1 — the satellite's tolerances
        let at2 = |x: f64| (x * 100.0).round() / 100.0;
        let out = |lo: f64, hi: f64| ((lo * 10.0).floor() / 10.0, (hi * 10.0).ceil() / 10.0);
        let (beta_lo, beta_hi) = out(a.basket_beta - 0.25, a.basket_beta + 0.25);
        let (vol_lo, vol_hi) = out(a.basket_vol_ratio - 0.30, a.basket_vol_ratio + 0.30);
        for (name, got, lo, hi) in [
            (
                "basket name vol ratio",
                b.name_vol_ratio,
                a.basket_name_vol_band.0,
                a.basket_name_vol_band.1,
            ),
            ("basket name gaps/yr", b.name_gaps, 0.40, 5.10),
            (
                "basket corr",
                b.agg_corr,
                at2(a.basket_corr - 0.10),
                at2(a.basket_corr + 0.10),
            ),
            ("basket beta", b.agg_beta, beta_lo, beta_hi),
            ("basket vol ratio", b.agg_vol_ratio, vol_lo, vol_hi),
            ("basket pair corr", b.pair_corr, 0.42, 0.86),
            ("basket idio share", b.idio_share, 0.26, 0.60),
            ("basket tail coincidence", b.tail_coincidence, 0.35, 0.60),
        ] {
            v.push(band_check(name, got, lo, hi, GateClass::Fidelity, 2, ""));
        }
        v.push((
            "basket pair corr rises on the worst decile".to_string(),
            b.pair_corr_worst > b.pair_corr_mid,
            Mechanism,
        ));
    }
    // THE MACRO PANEL, graded when it ran — `macro-2026-09-06.tsv`. Mechanism: the spread and the
    // conditions index FIRE — their trailing rank crosses 90 in most 20% episodes (the record:
    // 0.71-1.00 of them across both sets' references). Fidelity: their FIRING LAG, sessions from
    // the peak to the first firing — the record's conditions index leads the peak by about two
    // months on every reference's classic episodes and its spread trails it by one to three
    // weeks, and the lag is invariant to how fast the decline runs; each member's persistence at
    // K observations (record +-0.08); the ORACLE BOUND (no member predicts the forward 60-session
    // return better than the record's counterparts do, <= 0.02 there); the slope's inversion
    // share — rate units the model anchors — and the implied-vol member's variance risk premium.
    // REPORTED, not graded: every WARNING SHARE (at one and the same lag it reads lower on an
    // index that runs up harder into its peaks and falls less deep, so it grades the index's
    // price dynamics as much as the signal), the implied-vol member's lag (the model's leads the
    // peak by a month where the record's is coincident — a high vol state is a CAUSE of the
    // model's declines where VIX is a response), the slope's (never: the model's inversions are
    // regime-length) and the inversion spell length, and the ivol's R^2 against forward realized
    // vol (the unforecastable jump share of the model's realized variance) — each disclosed in
    // MarketSimWorlds.md.
    // a decoupled panel grades nothing: its readings are the no-edge level, by construction
    if let Some(ms) = st.macro_panel.filter(|m| !m.sibling) {
        // the oracle bound is the noise-sizing guard for the four measured members; the two
        // draw-free levels (10-year, credit ratio) are reported — the record's own reach 0.038
        // on the QQQ window, above the bound
        let r2_max = ms.members[..4]
            .iter()
            .map(|m| m.r2fwd60)
            .fold(f64::NEG_INFINITY, f64::max);
        // the coupling test: the conditions index builds before the peak, clear of what a
        // decoupled series reads there (the null panel: 0.49); "fires in most episodes" is not
        // one (a null panel fires in 0.70-0.77 of them), so the fired shares are reported. The
        // LEVEL of the build-up is a fidelity row against the record's span, which the model
        // misses at ~0.63: its 20% declines are not late-cycle events the way the record's are,
        // and no map of its recorded states reaches the record — a price-model limit, disclosed.
        v.push((
            "macro cond builds before the peak: rank above a decoupled series' 0.49".to_string(),
            ms.members[2].pre_peak > macro_bands::COND_PRE_PEAK_NULL,
            Mechanism,
        ));
        v.push((
            format!(
                "macro cond concentrates the big peaks: a 20% peak within a quarter above {}x as likely",
                jf(macro_bands::HAZARD_MIN, 0, 1)
            ),
            ms.hazard20q > macro_bands::HAZARD_MIN,
            Mechanism,
        ));
        v.push(band_check(
            "macro cond build-up",
            ms.members[2].pre_peak,
            macro_bands::COND_PRE_PEAK.0,
            macro_bands::COND_PRE_PEAK.1,
            GateClass::Fidelity,
            2,
            "",
        ));
        // a signed band in sessions, `lo..hi`, since `-84--4` reads as nothing
        for (name, got, (lo, hi)) in [
            (
                "macro spread lag",
                ms.members[0].lag,
                macro_bands::SPREAD_LAG,
            ),
            ("macro cond lag", ms.members[2].lag, macro_bands::COND_LAG),
        ] {
            v.push((
                format!("{name} {}..{} sessions", jf(lo, 0, 0), jf(hi, 0, 0)),
                got > lo && got < hi,
                GateClass::Fidelity,
            ));
        }
        for (name, got, lo, hi, dp) in [
            (
                "macro spread persistence",
                ms.members[0].ac_k,
                macro_bands::SPREAD_AC_K.0,
                macro_bands::SPREAD_AC_K.1,
                2,
            ),
            (
                "macro cond persistence",
                ms.members[2].ac_k,
                macro_bands::COND_AC_K.0,
                macro_bands::COND_AC_K.1,
                2,
            ),
            (
                "macro ivol persistence",
                ms.members[3].ac_k,
                macro_bands::IVOL_AC_K.0,
                macro_bands::IVOL_AC_K.1,
                2,
            ),
            (
                "macro oracle bound r2",
                r2_max,
                -1e-12,
                macro_bands::ORACLE_R2,
                3,
            ),
            (
                "macro inversion share",
                ms.inv_share,
                macro_bands::INV_SHARE.0,
                macro_bands::INV_SHARE.1,
                2,
            ),
            (
                "macro vol premium",
                ms.vrp,
                macro_bands::VRP.0,
                macro_bands::VRP.1,
                2,
            ),
        ] {
            v.push(band_check(name, got, lo, hi, GateClass::Fidelity, dp, ""));
        }
    }
    v
}

/// Whether an anchor-fitted band can be graded here: its driving variable inside the range the
/// anchor funds covered, and the statistic defined. Mirrors [`Relation::grade`]'s refusal.
fn anchored(driver: f64, support: (f64, f64), got: f64) -> bool {
    (support.0..=support.1).contains(&driver) && !got.is_nan()
}

/// The anchor-fitted fidelity bands `gate_checks` did NOT grade here, each with its reason:
/// driving variable outside the anchors' range, or the relation undefined at this volatility.
/// Disclosed — printed by `-validate`, recorded in the sidecar's `fidelityUnanchored` — rather
/// than failed, because "no anchor to compare against" and "the level is wrong" are different
/// findings and only one of them is about the model.
fn unanchored_in(st: &WorldStats) -> Vec<String> {
    let mut out = Vec::new();
    let eq_vol = st.vol * 100.0;
    if !anchored(eq_vol, EQUITY_VOL_SUPPORT, st.eq_d10_vs_real()) {
        if eq_vol < EQUITY_VOL_SUPPORT.0 || eq_vol > EQUITY_VOL_SUPPORT.1 {
            out.push(format!(
                "equity d5 and d10 vs real (equity vol {}% outside the anchors' {}-{}%)",
                jf(eq_vol, 0, 2),
                jf(EQUITY_VOL_SUPPORT.0, 0, 1),
                jf(EQUITY_VOL_SUPPORT.1, 0, 1)
            ));
        } else {
            out.push("equity d5 and d10 vs real (no fitted value at this volatility)".to_string());
        }
    }
    let vol = st.bond_vol * 100.0;
    if !anchored(vol, BOND_VOL_SUPPORT, st.bond_depth_vs_vol()) {
        let why = if vol < BOND_VOL_SUPPORT.0 || vol > BOND_VOL_SUPPORT.1 {
            format!(
                "bond vol {}% outside the anchors' {}-{}%",
                jf(vol, 0, 2),
                jf(BOND_VOL_SUPPORT.0, 0, 2),
                jf(BOND_VOL_SUPPORT.1, 0, 2)
            )
        } else {
            format!(
                "no fitted value below {}% bond vol",
                jf(BOND_D10_ZERO, 0, 2)
            )
        };
        out.push(format!("bond depth vs its vol ({why})"));
    }
    if !anchored(st.duration, BOND_DUR_SUPPORT, st.bond_vol_per_year()) {
        out.push(format!(
            "bond vol x duration (duration {}y outside the anchors' {}-{}y)",
            jf(st.duration, 0, 2),
            jf(BOND_DUR_SUPPORT.0, 0, 2),
            jf(BOND_DUR_SUPPORT.1, 0, 2)
        ));
    }
    out
}

pub fn failed_in(a: Anchors, st: &WorldStats, cls: GateClass) -> Vec<String> {
    gate_checks(a, st)
        .into_iter()
        .filter(|(_, ok, c)| !ok && *c == cls)
        .map(|(n, _, _)| n)
        .collect()
}

/// Admissibility under the classes a report has declared it requires. A class not required is
/// a class whose failures are disclosed and tolerated, which is the whole point of the split.
fn gate_ok(a: Anchors, st: &WorldStats, required: &[GateClass]) -> bool {
    gate_checks(a, st)
        .iter()
        .all(|(_, ok, c)| *ok || !required.contains(c))
}

/// The historical binary verdict: a market with its mechanisms live. Level fidelity is NOT in
/// it, so every report keeps the admissibility it had before the depth profile was measured —
/// a consumer that reads levels asks for `fidelity` explicitly.
pub fn gate_default() -> Vec<GateClass> {
    vec![GateClass::Realism, GateClass::Mechanism]
}

/// Realism is ALWAYS in the result: its failure means the world is not a market, which no
/// report can declare itself indifferent to. Without this, `-gate fidelity` on a
/// realism-failing world exits 0 — an admissibility check that can be configured into
/// admitting non-markets.
fn parse_gate(spec: &str) -> Vec<GateClass> {
    let mut out: Vec<GateClass> = Vec::new();
    for tok in spec.to_lowercase().split(',') {
        let add: &[GateClass] = match tok.trim() {
            "" => &[],
            "realism" => &[GateClass::Realism],
            "mechanism" => &[GateClass::Mechanism],
            "fidelity" => &[GateClass::Fidelity],
            "all" | "full" => &GateClass::ALL,
            other => cli_die(&format!(
                "unknown -gate class [{other}]; use realism, mechanism, fidelity or all"
            )),
        };
        for c in add {
            if !out.contains(c) {
                out.push(*c);
            }
        }
    }
    if out.is_empty() {
        cli_die(&format!(
            "-gate got no classes in [{spec}]; use realism, mechanism, fidelity or all"
        ));
    }
    if !out.contains(&GateClass::Realism) {
        out.push(GateClass::Realism);
    }
    out
}

/// A fit row's reading of the ensemble statistics.
pub type StatFn = fn(&WorldStats) -> f64;

/// name, extractor, target, weight
/// Reference relative sd for the precision factor below: a weight of `judgment` means "as
/// measurable as a target whose single-history sd is 20% of its anchor" — near the median of the
/// measured set, and chosen so the weights SUM to about what the equal-precision objective's did
/// (12.2 against 12.5), which keeps the 0.5-per-failed-gate penalty at its established bite.
pub const SD_REL_REF: f64 = 0.20;

/// A fidelity weight: JUDGMENT x measured PRECISION.
///
/// `judgment` carries what a number cannot: redundancy (the three depth rungs are one
/// distribution read three times), scope, and importance. `kurtosis` keeps the 0.5 it was given as
/// a recorded exclusion: it is no longer excluded, but it is still ONE number summarising a whole
/// tail, and the judgment was never only about scope.
/// `sd_rel` is the target's single-history sd over its anchor, measured by `-noise` at the
/// anchor's OWN horizon — 2026-08-25, 200 paths, the default world — and FROZEN here exactly as
/// the anchors themselves are. Frozen is load-bearing: computed live, a candidate world under
/// `-calibrate` that widens its own spread would down-weight its own misses. Re-measure by
/// running `-noise`, then change these literals deliberately.
///
/// No cap on the precision factor: the measurement says equity vol is the best-pinned target in
/// the set (sd/real 0.10), and capping its weight would re-smuggle the equal-measurability
/// assumption this function exists to remove.
/// WHICH REAL ASSET a world is being graded against.
///
/// Every equity fidelity target was the S&P's, hard-coded, so a world calibrated to any other index
/// failed the target set for BEING that other index — it could be run but not graded, and
/// `-calibrate` could not search for one at all. This makes the asset a parameter.
///
/// Only the EQUITY rows vary. The bond targets stay literal in `fit_targets`: the refuge asset is
/// the same Treasury whatever the equity index is. The three depth rungs are already RATIOS against
/// a relation evaluated at the world's own volatility and return, so they read 1.00 for any asset by
/// construction — which is exactly why 0.21.0 restated them that way.
///
/// `judgment` is NOT here. It says what a target is worth given redundancy and importance, which is
/// a property of the statistic, not of the index; only the measured level and its sampling spread
/// are asset-specific.
///
/// The realism bands are not here either. `equity vol 8-40%` and `kurtosis 4-40` say "is this a
/// market at all", and a Nasdaq is still a market. The two FIDELITY bands are, because they say "is
/// this THIS market".
#[derive(Clone, Copy)]
pub struct Anchors {
    pub name: &'static str,
    pub equity_window: &'static str,
    pub equity_years: usize,
    pub cluster_window: &'static str,
    /// Window for the return-per-volatility anchor. Its own field because it is NOT the equity
    /// window: the S&P set takes r/v from CRSP 1954-2026 where its levels come from the S&P, and
    /// the Nasdaq set takes it from QQQ. The report header printed "CRSP 1954-2026" as a literal
    /// and so mislabelled every Nasdaq run.
    pub ret_vol_window: &'static str,
    pub cluster_years: usize,
    /// The TAIL reads its own window, and for a sharper reason than horizon-sensitivity: the
    /// deepest episode is the one statistic a window can DELETE. Across the committed fixture the
    /// median depth swings 11% between windows and the crash rate 30%, while the worst swings 54%
    /// — -84.1% over the century against -54.6% from 1954, because 1954 opens after the crash that
    /// set it. A tail graded on a window chosen to exclude the record's worst extreme cannot fail
    /// on the thing it exists to test. Never fold this back into `equity_window`: the two coincide
    /// in neither shipped set for the same reason, and coinciding today is not a reason to share a
    /// field.
    pub tail_window: &'static str,
    pub tail_years: usize,
    pub vol: f64,
    pub vol_sd: f64,
    /// THE TYPICAL YEAR (item 24): the median calendar-year vol of the equity window, from
    /// `yearvol-2026-09-15.tsv`. The pooled `vol` cannot tell an ordinary year from an episode;
    /// this can, and it is what keeps a search from closing the pooled row by making every year
    /// more volatile.
    pub year_vol: f64,
    pub year_vol_sd: f64,
    pub ret_vol: f64,
    pub ret_vol_sd: f64,
    pub kurt: f64,
    pub kurt_sd: f64,
    pub ac1: f64,
    pub ac1_sd: f64,
    pub ac20: f64,
    pub ac20_sd: f64,
    pub crashes: f64,
    pub crashes_sd: f64,
    pub med_depth: f64,
    pub med_depth_sd: f64,
    pub worst_depth: f64,
    pub worst_depth_sd: f64,
    pub vol_band: (f64, f64),
    /// the typical year's: one sd of the row's own single-history spread (0.11 at 72 years,
    /// 0.18 at 27)
    pub year_vol_band: (f64, f64),
    pub ret_vol_band: (f64, f64),
    /// 100*(sdRatio - 1) from `asymmetry-2026-08-31.tsv` — the raw model/real quotient of
    /// sdRatio itself sits so near 1 by construction that no miss could ever fire; the EXCESS is
    /// the phenomenon (positive everywhere the record was measured).
    pub semi_excess: f64,
    pub semi_excess_sd: f64,
    /// corr(r_t, r^2_{t+1}) from the same fixture — the one leverage statistic that is stable
    /// across every CRSP era and all 18 funds on close-only data.
    pub lev_corr: f64,
    pub lev_corr_sd: f64,
    /// Left-tail stock-bond correlation from `tailcorr-2026-08-31.tsv` (the equity leg's own
    /// pair against TLT).
    pub tail_hedge: f64,
    pub tail_hedge_sd: f64,
    /// THE WINGS (item 25), in percent of sessions: the valuation level's time more than 0.5 log
    /// above and below its own 20-year mean, from Shiller's CAPE (`mania-2026-09-15.tsv`, w1901,
    /// one series shared by both sets like the dispersion row). The record is symmetric; the
    /// model's cycle had the record's amplitude with the wrong sign (2.5% above, 13.7% below on
    /// the Nasdaq recipe) until these rows gave the search a reason to care. THE SPREAD IS THE
    /// RECORD'S OWN, not `-noise`'s: a world that never makes the upper wing reads a spread of
    /// 0.07 for it (the default) and one that makes it rarely 0.79 (the recipe) — neither is a
    /// sampling error of the statistic. A moving-block bootstrap of the record's level series
    /// (20-year blocks, 4000 resamples; the fixture's bootSd rows) puts the share's relative sd
    /// at 0.60 for both wings — one spell, 1995-2003, is most of the upper share.
    pub wing_up: f64,
    pub wing_up_sd: f64,
    pub wing_down: f64,
    pub wing_down_sd: f64,
    /// Sampling spreads for the rows whose LEVEL is not asset-specific — the theory-valued depth
    /// rungs, the 60-session variance ratio, the valuation proxy and the bond rows — but whose
    /// spread is: measured by `-noise` at the set's own world, 200 paths, and frozen like the
    /// spreads above. A spread passed inline to `wgt` weights both sets' rows with one world's
    /// reading; the variance ratio's 0.35 did, where the two worlds read 0.28 and 0.24.
    pub val_disp_sd: f64,
    pub vr60_sd: f64,
    pub d5_sd: f64,
    pub d10_sd: f64,
    pub d20_sd: f64,
    pub bond_vol_sd: f64,
    pub bond_growth_sd: f64,
    pub bond_infl_sd: f64,
    pub bond_depth_sd: f64,
    /// Drawdown-SHAPE references for `-ddshape`, the first the primary the ratios read against;
    /// `ddshape-2026-09-02.tsv`, on the model's own episode definition and median.
    pub dd_refs: &'static [DdRef],
    /// The dividend yield at fair value (%/yr) and the band its level is graded against when the
    /// `div_yield` dial is on — `dividend-2026-09-02.tsv`: the window's annual means rounded out.
    pub div_yield: f64,
    pub div_yield_band: (f64, f64),
    /// THE BASKET's relation to this set's primary — `basket-2026-09-02.tsv`: the equal-weight
    /// eight on SPY / on QQQ (corr, beta, vol ratio), and the eight's vol as a ratio to the
    /// primary's, rounded outward. Level 3 of that fixture is a property of the names among
    /// themselves and stays shared.
    pub basket_corr: f64,
    pub basket_beta: f64,
    pub basket_vol_ratio: f64,
    pub basket_name_vol_band: (f64, f64),
}

/// One real drawdown-shape reference: a series over a window, and per threshold (thr, episodes,
/// per year, median depth %, median decline, median recovery, median underwater, median worst-day
/// share) — every median `pctile(.., 0.5)`, the model rows' own. Windows of the century at SPY's
/// own length carry the spread one SPY-length history can show.
pub struct DdRef {
    pub series: &'static str,
    pub window: &'static str,
    pub years: f64,
    pub rows: [DdRefRow; 2],
}

const DD_REFS_SP500: [DdRef; 5] = [
    DdRef {
        series: "CRSP",
        window: "1926-2026",
        years: 100.00,
        rows: [
            (0.10, 31, 0.310, -20.2, 78, 84, 196, 0.153),
            (0.20, 16, 0.160, -27.7, 235, 234, 434, 0.143),
        ],
    },
    DdRef {
        series: "CRSP",
        window: "1926-1959",
        years: 33.50,
        rows: [
            (0.10, 7, 0.209, -12.8, 125, 67, 196, 0.191),
            (0.20, 3, 0.090, -28.3, 273, 722, 994, 0.133),
        ],
    },
    DdRef {
        series: "CRSP",
        window: "1960-1993",
        years: 33.07,
        rows: [
            (0.10, 14, 0.423, -18.7, 135, 103, 269, 0.140),
            (0.20, 7, 0.212, -27.7, 167, 233, 368, 0.131),
        ],
    },
    DdRef {
        series: "CRSP",
        window: "1993-2026",
        years: 33.42,
        rows: [
            (0.10, 10, 0.299, -20.4, 65, 94, 145, 0.236),
            (0.20, 6, 0.180, -25.6, 235, 296, 530, 0.153),
        ],
    },
    DdRef {
        series: "SPY",
        window: "1993-2026",
        years: 33.59,
        rows: [
            (0.10, 12, 0.357, -18.8, 64, 75, 131, 0.290),
            (0.20, 4, 0.119, -33.7, 355, 869, 1223, 0.158),
        ],
    },
];

const DD_REFS_NASDAQ: [DdRef; 2] = [
    DdRef {
        series: "NDX",
        window: "1990-2026",
        years: 36.66,
        rows: [
            (0.10, 32, 0.873, -13.2, 34, 37, 74, 0.302),
            (0.20, 7, 0.191, -28.0, 62, 78, 142, 0.181),
        ],
    },
    DdRef {
        series: "QQQ",
        window: "1999-2026",
        years: 27.48,
        rows: [
            (0.10, 21, 0.764, -12.0, 20, 44, 61, 0.304),
            (0.20, 5, 0.182, -28.6, 80, 75, 154, 0.181),
        ],
    },
];

/// The S&P/CRSP set. The LEVELS are the ones every release before 0.21.0 hard-coded, moved rather
/// than re-measured (except the two the 0.22 releases re-anchored — `med_depth` and `worst_depth`
/// — each re-derived from a committed fixture). The SPREADS were re-frozen from `-noise -paths 200`
/// at the adopted 0.24.1 vol-response world, 2026-09-12, as the defaults-change rule requires. The
/// same command at the outgoing 0.24.0 world reproduces 19 of the 20 previous literals exactly, so
/// every move below is the world's; the twentieth, the variance ratio's, had been a constant shared
/// with the Nasdaq set and never measured. `-noise`'s `sd/real` column agrees with the `wt` beside
/// it, which is the whole point of printing them together.
const SP500_ANCHORS: Anchors = Anchors {
    name: "S&P 500 / CRSP",
    equity_window: "S&P / CRSP 1954-2026",
    ret_vol_window: "CRSP 1954-2026",
    equity_years: 72,
    cluster_window: "CRSP 1926-2026, the century",
    cluster_years: 100,
    tail_window: "CRSP 1926-2026, the century",
    tail_years: 100,
    vol: 16.0,
    vol_sd: 0.12,
    // CRSP 1954-2026 (`yearvol-2026-09-15.tsv`, w1954): 12.87, 0.82 of pooled; the S&P index's
    // own daily record is not in the fixture, and CRSP is the series the r/v row reads too.
    year_vol: 12.9,
    year_vol_sd: 0.11,
    ret_vol: 0.69,
    ret_vol_sd: 0.27,
    kurt: 28.0,
    kurt_sd: 0.85,
    ac1: 0.299,
    ac1_sd: 0.15,
    ac20: 0.225,
    ac20_sd: 0.21,
    crashes: 20.7,
    crashes_sd: 0.30,
    med_depth: -21.4,
    med_depth_sd: 0.15,
    // RE-ANCHORED in 0.22.1, same error class as `med_depth` in 0.22.0: -56.8 was the 2007-09
    // episode, the worst of the 1954-2026 window, used where the model computes the worst over a
    // whole history. 1954 opens AFTER the crash that set the record's worst, so the anchor graded
    // the tail against a window with the tail removed. Over the century, on the model's own 15%
    // threshold, the record reads -84.1% (`episodes-2026-08-29.tsv`, w1926) — the 1929-32 decline,
    // which every threshold in that window agrees on because it is one episode. `tail_years` moves
    // to 100 with it, so the percentile is read at the window's own length.
    //
    // Its sd is read at the same 100 years: a 72-year history's spread of the worst decline is
    // not a century's.
    worst_depth: -84.1,
    worst_depth_sd: 0.20,
    vol_band: (14.0, 18.0),
    year_vol_band: (11.3, 14.5),
    ret_vol_band: (0.50, 0.85),
    // CRSP c1954 rows of asymmetry-2026-08-31.tsv; the tail hedge is SPY/TLT. A single 72-year
    // history barely pins the semivariance excess (one crash day swings it), and the record reads
    // as a TYPICAL history of this model on all three rows — the 51st percentile (semivariance),
    // 39th (leverage corr), 39th (tail hedge).
    semi_excess: 3.06,
    semi_excess_sd: 1.42,
    lev_corr: -0.0926,
    lev_corr_sd: 0.40,
    tail_hedge: -0.273,
    tail_hedge_sd: 0.34,
    wing_up: 7.6,
    wing_up_sd: 0.60,
    wing_down: 6.7,
    wing_down_sd: 0.61,
    val_disp_sd: 0.62,
    vr60_sd: 0.28,
    d5_sd: 0.18,
    d10_sd: 0.45,
    d20_sd: 4.18,
    bond_vol_sd: 0.36,
    bond_growth_sd: 1.69,
    bond_infl_sd: 1.55,
    bond_depth_sd: 0.34,
    dd_refs: &DD_REFS_SP500,
    div_yield: 2.95,
    div_yield_band: (1.1, 5.8),
    basket_corr: 0.770,
    basket_beta: 1.557,
    basket_vol_ratio: 2.023,
    basket_name_vol_band: (1.9, 3.5),
};

/// The Nasdaq-100 set, measured 2026-08-28 from QQQ daily adjusted closes over its own full history,
/// 1999-03-10 to 2026-08-20 (27.4 years).
///
/// THAT WINDOW IS A DECISION, not a default. Drawdown-episode counts swing 1.7x on the measurement
/// convention alone: the same QQQ data reads 24.1 episodes per century with the running peak seeded
/// from prior history, 40.1 with a fresh start on a window opening 2001-08-27 (mid dot-com bear,
/// which resets the peak ~60% down and MANUFACTURES episodes on the recovery), and 25.6 fresh-start
/// from QQQ's own inception. The model measures each path fresh from its own start, so a fresh start
/// is the matching convention — but only on a window that OPENS near a high, or the reset does the
/// manufacturing. QQQ's inception in March 1999 is such a window. The equity-anchor fixture already
/// states this rule for `w1996` and warns against grading a model ensemble on the mid-bear `w2001`
/// block.
///
/// Control: the same pipeline on SPY 1993-01-29 reproduces the committed w1993 fixture row exactly.
///
/// THE SAMPLING SPREADS ARE THE NASDAQ WORLD'S OWN, re-frozen 2026-09-16 from
/// `-noise -paths 200 -atrelease 0.24.3-nasdaq`, the recipe this set describes. The same command
/// at the outgoing 0.24.2-nasdaq recipe reproduces all 21 of the previous literals exactly, so
/// every move is the world's: the 0.24.3 recipe reads narrower on median depth (0.45 -> 0.36),
/// the tail hedge (0.48 -> 0.37) and valuation dispersion (0.46 -> 0.38), wider on kurtosis
/// (1.53 -> 1.69) and the deep rung (0.35 -> 0.44), the slow channel's regime showing
/// in single histories. They were
/// first carried over from the S&P, and those values were badly wrong where the two worlds differ
/// most: `med_depth_sd`
/// read 0.10 against a measured 0.37, a 3.7x OVERWEIGHT on the heaviest row in this set's loss
/// (weight is `SD_REL_REF / sd_rel`), and `semi_excess_sd` 1.54 against 3.57. Re-measure these
/// whenever the recipe moves; a spread is model-implied, so it belongs to the world, not the index.
///
/// The two fidelity bands are likewise the S&P bands' proportional widths around the Nasdaq levels.
const NASDAQ_ANCHORS: Anchors = Anchors {
    name: "Nasdaq-100 / QQQ",
    equity_window: "QQQ 1999-2026",
    ret_vol_window: "QQQ 1999-2026",
    equity_years: 27,
    cluster_window: "QQQ 1999-2026",
    cluster_years: 27,
    tail_window: "QQQ 1999-2026",
    tail_years: 27,
    vol: 26.90,
    vol_sd: 0.12,
    // QQQ 1999-2026 (`yearvol-2026-09-15.tsv`, w1999): 18.26, only 0.68 of pooled — the window's
    // vol is 2000-02 at 58 / 55 / 42%; QQQ from 2007 reads 0.82 like SPY.
    year_vol: 18.3,
    year_vol_sd: 0.16,
    ret_vol: 0.38,
    ret_vol_sd: 0.50,
    kurt: 9.55,
    kurt_sd: 1.69,
    ac1: 0.293,
    ac1_sd: 0.24,
    ac20: 0.249,
    ac20_sd: 0.22,
    crashes: 25.6,
    crashes_sd: 0.49,
    med_depth: -22.8,
    med_depth_sd: 0.36,
    worst_depth: -83.0,
    worst_depth_sd: 0.18,
    vol_band: (23.5, 30.3),
    year_vol_band: (15.0, 21.6),
    ret_vol_band: (0.27, 0.47),
    // QQQ wfull row of asymmetry-2026-08-31.tsv; the tail hedge is QQQ/TLT.
    semi_excess: 1.13,
    semi_excess_sd: 4.47,
    lev_corr: -0.1073,
    lev_corr_sd: 0.47,
    tail_hedge: -0.236,
    tail_hedge_sd: 0.37,
    wing_up: 7.6,
    wing_up_sd: 0.60,
    wing_down: 6.7,
    wing_down_sd: 0.61,
    // d20's spread is a fraction of the S&P world's (0.35 against 4.18): at Nasdaq volatility the
    // deep rung is pinned where the S&P default leaves it unreadable, so the row carries real
    // weight here.
    val_disp_sd: 0.38,
    vr60_sd: 0.29,
    d5_sd: 0.12,
    d10_sd: 0.22,
    d20_sd: 0.44,
    bond_vol_sd: 0.37,
    bond_growth_sd: 1.64,
    bond_infl_sd: 1.61,
    bond_depth_sd: 0.28,
    dd_refs: &DD_REFS_NASDAQ,
    div_yield: 0.78,
    div_yield_band: (0.3, 1.5),
    basket_corr: 0.837,
    basket_beta: 1.365,
    basket_vol_ratio: 1.630,
    basket_name_vol_band: (1.5, 2.8),
};

pub fn anchors_named(spec: &str) -> Anchors {
    match spec {
        "sp500" | "sp" | "spx" => SP500_ANCHORS,
        "nasdaq" | "ndx" | "qqq" => NASDAQ_ANCHORS,
        other => cli_die(&format!("unknown -anchors [{other}]; use sp500 or nasdaq")),
    }
}

fn wgt(judgment: f64, sd_rel: f64) -> f64 {
    judgment * (SD_REL_REF / sd_rel)
}

#[expect(
    clippy::too_many_lines,
    reason = "one row per fidelity target, and the target list is the contract"
)]
/// Every fidelity row `fitness` scores for an anchor set: name, reading, target, weight.
pub fn fit_targets(a: Anchors) -> Vec<(&'static str, StatFn, f64, f64)> {
    vec![
        (
            "equity vol %",
            (|st| st.vol * 100.0) as StatFn,
            a.vol,
            wgt(1.0, a.vol_sd),
        ),
        // THE TYPICAL YEAR (item 24): the median calendar-year vol, so the pooled row above
        // cannot be closed by making every year more volatile. QQQ 1999-2026 reads 18.3 against
        // a pooled 26.9 — the gap is 2000-02 — where QQQ from 2007, SPY and CRSP from 1954 all
        // read 0.82-0.83 of pooled, as the model does on both worlds (0.81). The pooled miss
        // that remains on the Nasdaq is the bust's, and only a mania closes it.
        (
            "typical-year vol %",
            (|st| st.year_vol * 100.0) as StatFn,
            a.year_vol,
            wgt(1.0, a.year_vol_sd),
        ),
        // Ken French F-F_Research_Data_Factors, US total market (Mkt-RF + RF), measured in the
        // units this row is compared in: annualised LOG return over sqrt(mean(r^2) * 252) on
        // DAILY data. Both conversions matter — a CAGR read as a simple rate and a monthly-derived
        // volatility each inflate the ratio, and together they turned a 0.69 anchor into 0.76.
        //   1954-2026 (the window of the rows around this one)  10.82%/yr over 15.68%  =  0.69
        //   1926-2026 (the only 100-year sample there is)        9.38%/yr over 17.14%  =  0.55
        // The target stays on the anchor window so the target set is internally consistent, NOT
        // because 0.55 is the wrong reading for a generator scored on 100-year paths; the gate
        // band admits it rather than legislating it away.
        (
            "return per vol",
            (|st: &WorldStats| st.ret_vol()) as StatFn,
            a.ret_vol,
            wgt(1.0, a.ret_vol_sd),
        ),
        // kurtosis's sdRel moved 0.14 -> 2.65 in 0.21.0, and the 19x is not a re-measurement of
        // the same thing: the jump channel makes single-history kurtosis as variable as it really
        // is. One 72-year window reads 8.8 at the 5th percentile and 205 at the 95th, because a
        // window either contains its 1987 or does not — SPY 1993-2026 reads 14.4 where the CRSP
        // century reads 28. Weighting by measurability therefore drops this target to 0.04, and
        // that is correct rather than unfortunate: one history barely pins it. What now pins
        // `jump_var` is CLUSTERING, at a combined weight of 3.1 and an sdRel a tenth of this one —
        // turning the channel off moves clustering 1.03 -> 1.11 and 1.05 -> 1.15, which the loss
        // sees clearly. A mechanism whose only defender is its least measurable target is a
        // mechanism a search will quietly discard.
        (
            "kurtosis",
            (|st| st.kurt) as StatFn,
            a.kurt,
            wgt(0.5, a.kurt_sd),
        ),
        // Ken French / CRSP value-weighted US market, daily, 1926-07-01..2026-06-30 — the FULL
        // century, and deliberately NOT the 1954-2026 window the rows above use. The model's
        // clustering is horizon-INDEPENDENT (0.320 at 20 years, 0.330 at 150) while the real
        // statistic is not (0.271 over 72 years, 0.299 over 100, 0.175-0.311 across non-overlapping
        // 20-year blocks), because a longer window spans more regimes. The model is scored on
        // 100-year paths, so a 72-year anchor compares a 100-year model reading against a 72-year
        // real one and reports 1.22 where the horizon-matched answer is 1.07.
        //
        // CONVENTION, stated because its absence is what blocked this for a release: autocorrelation
        // of |r| about its mean, normalised by the FULL-series sum of squares — `autocorr_abs`
        // itself. `jsrc/clusteringAnchor.sc` calls the Scala twin of that function to measure the
        // anchor, so the two cannot drift. On this data autocorr(r^2) reads 0.108 at lag 20 against
        // 0.208 for |r|, 92% apart: a re-derivation using the wrong one would conclude the model is
        // 2.2x too high rather than 1.07.
        //
        // The 20-year block spread is wide enough that an honestly derived BAND (about 0.16-0.33 at
        // lag 1) would not exclude the model. Real clustering varies by nearly two-to-one between
        // eras; a band tight enough to fail this world would have to exclude two of the five real
        // 20-year eras, which is a band chosen to produce a verdict rather than derived from a
        // record.
        (
            "clustering lag 1",
            (|st| st.ac1) as StatFn,
            a.ac1,
            wgt(1.0, a.ac1_sd),
        ),
        (
            "clustering lag 20",
            (|st| st.ac20) as StatFn,
            a.ac20,
            wgt(0.5, a.ac20_sd),
        ),
        // SIGNED persistence, the axis the two rows above cannot see — they are |r|, and a world can
        // cluster its volatility exactly right while its price trends. See `variance_ratio` for why
        // this is not a per-lag autocorrelation and `VAR_RATIO_BANDS` for the cross-section behind it.
        //
        // 1.00 IS A THEORY VALUE, DELIBERATELY, and it is the one row in this table that is not a
        // reading off a record. The real cross-section sits BELOW it — 0.74 median at 2001-2026 —
        // because modern equity indices mean-revert mildly at three months, and this model has no
        // mean-reversion channel to reproduce that with. Targeting 0.74 would ask a search to close a
        // gap with the only dials it has, which are the trend dials, and it would close it by
        // removing the reflexive channel entirely. The target says "do not manufacture a trend"; the
        // BAND is where the record's own spread lives, and it admits every reading in the fixture.
        //
        // NOT redundant with `crashes/century` or the depth rungs even though the same dial moves all
        // four: across the crowdImpact sweep corr(vr60, equity d20 vs real) is 0.98, which is the
        // finding, not an argument for dropping a row. The depth rungs said the world was too deep
        // and named no cause; this row names it.
        (
            "variance ratio 60d",
            (|st: &WorldStats| st.vr60) as StatFn,
            1.00,
            wgt(1.0, a.vr60_sd),
        ),
        // THE THIRD ASYMMETRY AXIS the rows above cannot see: clustering is |r| (sign-blind),
        // vr60 is the signed MEAN's persistence — this pair is the signed SECOND moment. Graded
        // as the EXCESS because the raw down/up ratio sits so near 1 that its model/real
        // quotient could never miss. Anchored on CRSP 1954-2026 (the equity window); the record
        // reads +2.8 to +3.1 on every CRSP era and positive on 15 of 18 funds
        // (asymmetry-2026-08-31.tsv). NO GATE BAND yet — first-cycle rows, disclosure before
        // enforcement, the d20 precedent.
        (
            "downside vol excess %",
            (|st: &WorldStats| st.semi_excess) as StatFn,
            a.semi_excess,
            wgt(0.5, a.semi_excess_sd),
        ),
        // The leverage effect, graded by the one statistic that survives close-only data:
        // corr(r_t, r^2_{t+1}) reads -0.09 on every CRSP era and negative on all 18 funds. The
        // sharper Patton-Sheppard signed-half regression was measured and CANNOT anchor here —
        // era-split with the sign flipping (c1926 -0.20, c1990 +0.34), the same negative result
        // longhorizon-2026-08-30.tsv records for long variance ratios — and the fixture keeps
        // its columns so it stays settled.
        (
            "leverage corr",
            (|st: &WorldStats| st.lev_corr) as StatFn,
            a.lev_corr,
            wgt(0.5, a.lev_corr_sd),
        ),
        // The record proxy (sd log CAPE) reads 0.24-0.41 across windows; 0.30 is the judgment
        // centre and the LITERAL is shared by both anchor sets — one Shiller record, no QQQ
        // equivalent. Judgment 0.5 for the proxy commensurability stated in
        // valuation-2026-08-30.tsv.
        (
            "valuation dispersion",
            (|st: &WorldStats| st.val_disp) as StatFn,
            0.30,
            wgt(0.5, a.val_disp_sd),
        ),
        // THE WINGS (item 25): the same cycle's time far above and far below its own 20-year
        // mean. Dispersion alone is sign-blind, and the model reached the record's amplitude
        // with crashes below and no manias above; these two rows are what a mania-capable world
        // has to read.
        (
            "upper wing months %",
            (|st: &WorldStats| st.wing_up * 100.0) as StatFn,
            a.wing_up,
            wgt(0.5, a.wing_up_sd),
        ),
        (
            "lower wing months %",
            (|st: &WorldStats| st.wing_down * 100.0) as StatFn,
            a.wing_down,
            wgt(0.5, a.wing_down_sd),
        ),
        (
            "crashes/century",
            (|st: &WorldStats| st.ep_per_path * 100.0 / st.years_per_path) as StatFn,
            a.crashes,
            wgt(1.0, a.crashes_sd),
        ),
        // RE-MEASURED in 0.22.0, and the old value was not this statistic. `-27.1` shipped through
        // 0.21.0 with no recorded convention; the model measures every peak-to-trough decline of 15%
        // or worse, and NO window of the record produces -27.1% at that threshold. A 20% threshold
        // does (-26.6% over 1954-2026, -28.0% over the century), so the model was graded against a
        // statistic it does not compute and pushed toward crashes deeper than the record's for its
        // own definition.
        //
        // Measured with `episodes` itself on the same CRSP total-return control the two rows above
        // use: -21.4% over 1954-2026, -23.7% over the century, -21.9% since 1990. The anchor set's
        // own window wins. Recorded in `test-data/equity-anchors/episodes-2026-08-29.tsv`, from which
        // `episode_anchor_tests` re-derives the shipped value.
        //
        // `crashes/century` 20.7 survives the same check — it sits between the record's 19.2 and
        // 24.9 — and was left alone. `worst crash %` did NOT: see its own entry below.
        (
            "median depth %",
            (|st| st.depth_med) as StatFn,
            a.med_depth,
            wgt(1.0, a.med_depth_sd),
        ),
        // Scored by the MEDIAN of single-history worsts at the anchor's own horizon — `fitness`
        // swaps the statistic in by name, supplied from `extreme_score_stats` — never by the
        // pooled ensemble minimum this StatFn computes. The minimum's distance from a one-history
        // anchor tracks the ensemble size (the frozen scoring ensemble's happens to sit 0.004 from
        // the anchor, a "perfect" reading for a tail `-validate` puts at the record's 1st
        // percentile); the median converges, is the centre of the distribution the report's
        // percentile is read from, and pulling it toward the anchor and pulling the percentile
        // toward 50 are the same act. The StatFn stays the pooled minimum because the REPORTS
        // read it as a level. Judgment 0.5: one draw of a max, partially redundant with the
        // crash-rate and depth rows. sdRel 0.15 measured at the 100-year horizon (2026-08-30).
        (
            "worst crash %",
            (|st| st.worst_depth) as StatFn,
            a.worst_depth,
            wgt(0.5, a.worst_depth_sd),
        ),
        // The "(24y)" is load-bearing, not decoration: this row is measured on a different
        // horizon from every other, and the label is the only part that travels when the number
        // is quoted.
        (
            "bond vol % (24y)",
            (|st| st.bond_vol * 100.0) as StatFn,
            13.0,
            wgt(1.0, a.bond_vol_sd),
        ),
        // RE-MEASURED in 0.22.0, same error class as `median depth %` above: `20.0` is 2008 ALONE,
        // the largest of the five growth-shock episodes in the record, and this row is a MEDIAN
        // across episodes. Measured the way `measure` measures it — SPY drawdowns of 15%+, TLT's log
        // return over the same peak-to-trough span — the record reads +6.6%, from episodes of
        // +6.6 / +22.4 / +4.4 / +13.3 / +0.8. The model was therefore read as UNDERSTATING a bond
        // rally it in fact overstates. Six episodes is the honest limit and `-noise` prices it in.
        // `test-data/bond-anchors/crash-response-2026-08-29.tsv`; `bond_crash_tests` re-derives both.
        (
            "bond growth-crash",
            (|st| st.bond_growth) as StatFn,
            6.6,
            wgt(1.0, a.bond_growth_sd),
        ),
        // The judgment stays at 1.5 — inflation-crash behaviour is why the bond refuge exists —
        // and the measured precision crushes the weight to ~0.13 anyway: sd/real 2.89, and only
        // 95 of 200 24-year histories produce a reading at all. The old 1.5 was the largest
        // weight in the loss on the least measurable target in the set.
        // RE-MEASURED with it: `-25.0` was a rounding of the ONE inflation-regime drawdown the
        // record has, which reads -34.7% (SPY 2022-01-03..2022-10-12, TLT over the same span). A
        // median of one is that one, so the anchor is the episode — but rounded 28% toward zero,
        // which is not a convention, it is an error.
        (
            "bond infl-crash",
            (|st| st.bond_infl) as StatFn,
            -34.7,
            wgt(1.5, a.bond_infl_sd),
        ),
        // Does the refuge hold exactly where it is needed — stock-bond correlation on calm
        // sessions with the equity return below its own calm q10, against the pair's own record
        // (tailcorr-2026-08-31.tsv). Calm-conditioned on BOTH sides by construction: the TLT
        // window is a disinflation era throughout, and the model's calm mask is the same one
        // `corr_calm` uses. What it currently discloses: the model's refuge is about twice too
        // good in the left tail (-0.56 against -0.27) while its full-sample calm correlation
        // sits 0.35 too high — day-frequency dependence is concentrated in the tail rather than
        // spread across the sample.
        (
            "tail hedge corr",
            (|st| st.tail_hedge) as StatFn,
            a.tail_hedge,
            wgt(0.5, a.tail_hedge_sd),
        ),
        // DEPTH PROFILE, stated RELATIVE to what a real fund of the same volatility and return
        // spends under water rather than as three absolute levels — see `EQUITY_D10_CORR` for the
        // relation and `eq_depth_vs_real` for what the ratio means. A level target is a statement
        // about one fund; a ratio is a statement about the mechanism, which is the same reason
        // `bond depth vs vol` is written this way.
        //
        // The absolute levels this replaces were SPY's over 1993-2026 (0.447 / 0.315 / 0.169), and
        // they were internally inconsistent with the two rows at the top of this table. SPY
        // produced them at 18.6% volatility and 0.554 return per vol; `equity vol %` and `return
        // per vol` ask this model to run at 16.0 and 0.69, and 35 real instruments at THAT
        // operating point spend 1.11x / 1.33x / 1.64x less time under water than SPY's numbers
        // demanded. The target set was asking for a market that is calmer than SPY and
        // better-returning than SPY and yet under water as long as SPY, which no real fund is. The
        // only way to satisfy it was an over-hot fundamental, and the search duly bought one — see
        // the `fundVol` range in `calibrate_ranges`.
        //
        // Anchor provenance is unchanged in kind and wider in coverage: 35 broad, sector and
        // country equity funds over 2001-2026 (`test-data/equity-anchors`, peaks seeded from full
        // prior history), with a 17-instrument 1996-2026 block as the independent transport check.
        // SPY is one row of it and no longer sets the level, which also retires the old caveat that
        // SPY could never serve as validation because its rungs WERE the targets.
        //
        // Only two of the three rungs are gated; the 20% rung's relation does not transport well
        // enough for a band that could fail anything. It stays a fit target — the loss is a
        // continuous quantity, not a verdict, and its weight already carries the redundancy
        // discount.
        // The bond anchor is a clean iShares TLT total-return series over 24 years, and only
        // the 10% rung of it has been measured. The other two bond rungs are REPORTED, not
        // targeted: filling them in by interpolation would manufacture a calibration anchor out
        // of nothing.
        // Re-measured by `-noise` when the rungs became ratios, and again at the 0.21.0 defaults
        // these are frozen from: a ratio compounds the depth share's own sampling error with the
        // volatility and return sampling that enters its denominator, so these are NOT the absolute
        // rungs' 0.22 / 0.34 / 0.55. The deep rung's 0.99 still holds its weight near 0.10 — the
        // measurement saying one 25-year record barely pins the 20% rung's ratio, which is also why
        // it carries no gate band.
        //
        // The same run is the fix's own evidence. At the 0.20.0 world the real value sat at the
        // 14th, 7th and 4th percentile of the model-implied spread — the record was in the model's
        // tail, on all three rungs at once. At this world it sits at the 63rd, 65th and 55th: the
        // anchors can no longer tell this model from the cross-section they were measured from,
        // which is a stronger statement than any ratio near 1.00, because it is made against the
        // spread rather than the point.
        (
            "equity d5 vs real",
            (|st: &WorldStats| st.eq_d5_vs_real()) as StatFn,
            1.00,
            wgt(0.5, a.d5_sd),
        ),
        (
            "equity d10 vs real",
            (|st: &WorldStats| st.eq_d10_vs_real()) as StatFn,
            1.00,
            wgt(1.0, a.d10_sd),
        ),
        // d20's sdRel moved 0.99 -> 1.56 in the 0.21.0 recovery-drag change, and like kurtosis's
        // move it is a re-measurement of a statistic that genuinely became more variable, not a
        // correction: slowing recovery from deep drawdowns makes time spent DEEP swing much harder
        // between histories (p5 0.19, p95 4.35 over 25 years). Weighting by measurability drops it
        // to 0.06. No other target's sdRel moved beyond its own noise, so none were churned.
        (
            "equity d20 vs real",
            (|st: &WorldStats| st.eq_d20_vs_real()) as StatFn,
            1.00,
            wgt(0.5, a.d20_sd),
        ),
        (
            "bond depth vs vol",
            (|st: &WorldStats| st.bond_depth_vs_vol()) as StatFn,
            1.00,
            wgt(0.5, a.bond_depth_sd),
        ),
    ]
}

/// Targets whose model statistic is an EXTREME order statistic over the pooled ensemble rather
/// than a per-path central value. `worst_depth` is the minimum over every episode in the run, so it
/// deepens without bound as the ensemble grows: on one world with every dial fixed it reads 1.28x
/// its anchor at 1 path and 1.58x at 400. A ratio that moves with `-paths` grades the SAMPLE SIZE,
/// not the model, and the anchor it is divided by is the deepest episode of ONE 72-year history
/// against the deepest of ~4,400.
///
/// These rows are reported as the anchor's PERCENTILE among single histories of the anchor's own
/// length — `-noise`'s `real@`, which converges — and carry no ratio at all. A median survives
/// pooling and a minimum does not; that is the whole distinction. The contract test requires every
/// name here to be a fidelity target.
const EXTREME_TARGETS: &[&str] = &["worst crash %"];

/// The admissible interval for a per-path fidelity ratio, and the admissible percentile band for an
/// `EXTREME_TARGETS` row. Stated ONCE: the report, the sidecar and the tests read the same pair, so
/// a consumer's `miss` and a reader's `<-- MISS` cannot drift apart.
///
/// Outside 5-95 is the condition `-noise`'s header already names — the model cannot produce
/// record-like histories on that statistic — and it is the honest analogue of a ratio miss: both
/// say "this level cannot be read off this world", neither says how far off it is.
const FIDELITY_RATIO_BAND: (f64, f64) = (0.667, 1.5);
const EXTREME_PCT_BAND: (usize, usize) = (5, 95);

/// Fewest single histories that can place a record within `EXTREME_PCT_BAND`. One history reads 0%
/// or 100% and neither is a measurement; in general the resolution is `100/n` percentile points, so
/// resolving a 5-point band edge needs 20. Below this the row reports `n/a` and a MISS — "too few
/// histories to place the record" and "the model cannot produce record-like histories" are
/// different findings, and only the second is about the model, but neither is a clean bill of
/// health in the one field a consumer reads to decide whether to trust the file.
const EXTREME_MIN_HISTORIES: usize = 100 / EXTREME_PCT_BAND.0;

/// One fidelity row AS REPORTED. A per-path target carries a ratio; an `EXTREME_TARGETS` row
/// carries the anchor's percentile among single histories instead, and no ratio. The two are
/// different judgements and a consumer must be able to tell them apart from the data alone — the
/// whole defect this type exists to prevent is a reader dividing two numbers that are not the same
/// statistic and reading the quotient as a bias.
///
/// `horizon_years` is the length of the record the anchor was read over, from `anchor_groups`; it
/// is carried on EVERY row, not just the extreme ones, because a per-path ratio still folds a
/// horizon mismatch a reader cannot otherwise see.
#[derive(Debug, Clone)]
pub struct FidelityRow {
    pub name: &'static str,
    pub model: f64,
    pub real: f64,
    pub ratio: Option<f64>,
    pub pctile: Option<usize>,
    pub horizon_years: usize,
    pub n_histories: usize,
}

impl FidelityRow {
    /// Stated as the admissible interval and NEGATED, so an unmeasurable row reports a miss rather
    /// than a clean bill of health — a `NaN` ratio fails both outward comparisons, and an extreme
    /// row whose ensemble produced no reading has nothing to stand on either.
    fn miss(&self) -> bool {
        match self.ratio {
            Some(r) => !(FIDELITY_RATIO_BAND.0..=FIDELITY_RATIO_BAND.1).contains(&r),
            None => !self
                .pctile
                .is_some_and(|p| (EXTREME_PCT_BAND.0..=EXTREME_PCT_BAND.1).contains(&p)),
        }
    }

    fn aggregation(&self) -> &'static str {
        if EXTREME_TARGETS.contains(&self.name) {
            "ensemble-extreme"
        } else {
            "per-path"
        }
    }
}

/// Where an anchor falls among model readings, as a percentage. `-noise`'s `real@` column and the
/// extreme rows' `record@` are the SAME number and are computed here so they stay so: two reports
/// disagreeing about one world would replace the confusion being fixed with a new one.
fn anchor_pctile(xs: &[f64], want: f64) -> usize {
    100 * xs.iter().filter(|x| **x <= want).count() / xs.len()
}

/// The horizon each target's anchor was read over, inverted from `anchor_groups` — which the
/// contract test already pins as a partition of the fidelity targets, so every target has one.
fn anchor_horizon(a: Anchors, name: &str) -> usize {
    anchor_groups(a)
        .iter()
        .find(|(_, _, names)| names.contains(&name))
        .map_or(0, |(_, yrs, _)| *yrs)
}

/// Each extreme target's per-single-history readings at its OWN horizon, from one ensemble. This
/// is the distribution behind BOTH the report's percentile and the loss's median — one function,
/// so the two judgements cannot be read off different ensembles, and the same measurement
/// `-noise` prints as `real@`. One extra ensemble per distinct horizon, and only
/// `EXTREME_TARGETS` need it, so at the shipped anchor sets that is exactly one.
/// The fidelity rows that are read as a MEDIAN OF SINGLE HISTORIES rather than off the pooled
/// ensemble. Exposed so a caller that has skipped the second ensemble knows which rows it
/// therefore has no reading for, instead of scoring them as unmeasurable.
pub fn extreme_target_names() -> &'static [&'static str] {
    EXTREME_TARGETS
}

/// The horizons an `EXTREME_TARGETS` row is read at — the anchor group's own window length,
/// never the caller's `-years`. Exposed so a caller running AT one of them can simulate once
/// instead of twice: see `extreme_readings_from`.
pub fn extreme_horizons(a: Anchors) -> Vec<usize> {
    anchor_groups(a)
        .into_iter()
        .filter(|(_, _, names)| names.iter().any(|n| EXTREME_TARGETS.contains(n)))
        .map(|(_, yrs, _)| yrs)
        .collect()
}

/// The single-history readings for every `EXTREME_TARGETS` row whose group is read at `yrs`,
/// from an ensemble the CALLER already holds.
///
/// Split out of `extreme_readings` for one reason: the extreme row costs a SECOND ensemble at its
/// own horizon, and that is over half of an evaluation — 2.89 s against the main reading's 2.28 s
/// at 60 x 80 on the shipped world. A caller running at the extreme horizon is simulating exactly
/// the same paths from exactly the same seed twice. Nothing here changes what is computed.
///
/// Each path is measured on its own, in PARALLEL: `measure` is pure and the collect preserves
/// order, so the readings and their median are what they were.
/// ONE PATH'S READING of an `EXTREME_TARGETS` row, taken directly instead of through `measure`.
/// Each of these rows reads a single statistic of each path, and `measure` on a one-path ensemble
/// computed every other statistic too -- the macro panel and the channels included -- and dropped
/// them: most of the extreme ensemble's cost. `None` for a row with no direct reading, which then
/// takes the full path. A contract test holds each direct reading to `measure`'s own, bit for bit.
fn extreme_reading(nm: &str, p: &Path) -> Option<f64> {
    match nm {
        // `measure`'s `worst_depth` for this path alone: its smallest episode depth, NaN without one
        "worst crash %" => {
            let depths: Vec<f64> = episodes(&p.price, 15.0)
                .iter()
                .map(|e| e.depth_pct)
                .collect();
            Some(if depths.is_empty() {
                f64::NAN
            } else {
                sorted_total(&depths)[0]
            })
        }
        _ => None,
    }
}

pub fn extreme_readings_from(
    a: Anchors,
    sims: &[Path],
    yrs: usize,
) -> std::collections::HashMap<&'static str, Vec<f64>> {
    let mut out: std::collections::HashMap<&'static str, Vec<f64>> =
        std::collections::HashMap::new();
    // `measure` per path only for a row with no direct reading, and then only once
    let mut full: Option<Vec<WorldStats>> = None;
    for (_, gy, names) in anchor_groups(a) {
        if gy != yrs {
            continue;
        }
        for nm in names
            .iter()
            .copied()
            .filter(|n| EXTREME_TARGETS.contains(n))
        {
            let Some((_, get, _, _)) = fit_targets(a).into_iter().find(|(n, _, _, _)| *n == nm)
            else {
                cli_die(&format!(
                    "EXTREME_TARGETS names [{nm}], which is not a fidelity target"
                ));
            };
            let direct: Option<Vec<f64>> =
                sims.par_iter().map(|p| extreme_reading(nm, p)).collect();
            let vals: Vec<f64> = direct.unwrap_or_else(|| {
                full.get_or_insert_with(|| {
                    sims.par_iter()
                        .map(|p| measure(std::slice::from_ref(p), yrs))
                        .collect()
                })
                .iter()
                .map(get)
                .collect()
            });
            out.insert(nm, vals.into_iter().filter(|x| !x.is_nan()).collect());
        }
    }
    out
}

/// The median of `extreme_readings_from`, for an ensemble the caller already holds.
pub fn extreme_score_stats_from(
    a: Anchors,
    sims: &[Path],
    yrs: usize,
) -> std::collections::HashMap<&'static str, f64> {
    extreme_readings_from(a, sims, yrs)
        .into_iter()
        .map(|(nm, xs)| (nm, med(&xs)))
        .collect()
}

fn extreme_readings(
    a: Anchors,
    paths: usize,
    seed: u64,
    w: &World,
) -> std::collections::HashMap<&'static str, Vec<f64>> {
    let mut out: std::collections::HashMap<&'static str, Vec<f64>> =
        std::collections::HashMap::new();
    for yrs in extreme_horizons(a) {
        out.extend(extreme_readings_from(
            a,
            &sim_paths(w, paths, yrs, seed),
            yrs,
        ));
    }
    out
}

/// What the LOSS grades an extreme row by: the median of the single-history readings. A median of
/// extremes converges as histories are added, where the pooled minimum deepens without bound. NaN
/// where the ensemble produced no finite reading, which `fitness` prices as unmeasurable rather
/// than as agreement.
pub fn extreme_score_stats(
    a: Anchors,
    histories: usize,
    seed: u64,
    w: &World,
) -> std::collections::HashMap<&'static str, f64> {
    extreme_readings(a, histories, seed, w)
        .into_iter()
        .map(|(nm, xs)| (nm, med(&xs)))
        .collect()
}

/// Every fidelity row as the report and the sidecar both read it. Built ONCE per invocation so the
/// printed table and the emitted JSON cannot describe the same world differently.
pub fn fidelity_rows(
    a: Anchors,
    st: &WorldStats,
    paths: usize,
    seed: u64,
    w: &World,
) -> Vec<FidelityRow> {
    let readings = if fit_targets(a)
        .iter()
        .any(|(n, _, _, _)| EXTREME_TARGETS.contains(n))
    {
        extreme_readings(a, paths, seed, w)
    } else {
        std::collections::HashMap::new()
    };
    fit_targets(a)
        .into_iter()
        .map(|(name, get, want, _)| {
            let model = get(st);
            let horizon_years = anchor_horizon(a, name);
            if EXTREME_TARGETS.contains(&name) {
                let empty: Vec<f64> = Vec::new();
                let xs = readings.get(name).unwrap_or(&empty);
                let pctile = if xs.len() < EXTREME_MIN_HISTORIES {
                    None
                } else {
                    Some(anchor_pctile(xs, want))
                };
                FidelityRow {
                    name,
                    model,
                    real: want,
                    ratio: None,
                    pctile,
                    horizon_years,
                    n_histories: xs.len(),
                }
            } else {
                FidelityRow {
                    name,
                    model,
                    real: want,
                    ratio: Some(if want == 0.0 { f64::NAN } else { model / want }),
                    pctile: None,
                    horizon_years,
                    n_histories: 1,
                }
            }
        })
        .collect()
}

/// Scala's `Double.sign`, which returns 0.0 (preserving the zero's sign) at zero.
/// Rust's `f64::signum` returns ±1.0 there instead, which would change the branch below.
fn scala_sign(x: f64) -> f64 {
    if x.is_nan() {
        f64::NAN
    } else if x > 0.0 {
        1.0
    } else if x < 0.0 {
        -1.0
    } else {
        x
    }
}

/// Scalar calibration loss: weighted |log(model/target)| over the fidelity targets, a
/// penalty of 2 for a wrong sign, and 0.5 per failed gate check.
///
/// `extreme_stats`: the median single-history reading per `EXTREME_TARGETS` row, from
/// `extreme_score_stats` — the loss must never price the pooled minimum those rows' StatFn
/// computes, so the caller supplies the converging statistic explicitly and a missing entry
/// prices as unmeasurable rather than silently falling back.
pub fn fitness(
    a: Anchors,
    st: &WorldStats,
    extreme_stats: &std::collections::HashMap<&'static str, f64>,
) -> (f64, Vec<(&'static str, f64, f64, f64)>) {
    let rows: Vec<(&'static str, f64, f64, f64)> = fit_targets(a)
        .into_iter()
        .map(|(name, get, target, weight)| {
            let m = if EXTREME_TARGETS.contains(&name) {
                extreme_stats.get(name).copied().unwrap_or(f64::NAN)
            } else {
                get(st)
            };
            let term = if m.is_nan() {
                weight * 4.0
            } else if scala_sign(m) != scala_sign(target) && target != 0.0 {
                weight * (2.0 + (m.abs().max(1e-6) / target.abs()).ln().abs())
            } else {
                weight * (m.abs().max(1e-6) / target.abs()).ln().abs()
            };
            (name, m, target, term)
        })
        .collect();
    let gate_penalty = gate_checks(a, st).iter().filter(|(_, ok, _)| !ok).count() as f64 * 0.5;
    let total: f64 = scala_sum(rows.iter().map(|r| r.3)) + gate_penalty;
    (total, rows)
}

pub fn sim_paths(w: &World, paths: usize, years: usize, seed: u64) -> Vec<Path> {
    sim_path_range(w, 0, paths, years, seed)
}

/// Paths `from..from + count`. Path k is a function of (world, years, seed, k) alone, so a range
/// taken from the middle is byte-identical to the same indices of a run that started at zero —
/// which is what lets `-emitfrom` split one batch across invocations.
pub fn sim_path_range(w: &World, from: usize, count: usize, years: usize, seed: u64) -> Vec<Path> {
    let level = world_level(w);
    (from..from + count)
        .into_par_iter()
        .map(|k| simulate_at(w, years, seed.wrapping_add(k as u64 * 7919), level))
        .collect()
}

// ---- exposure rules ---------------------------------------------------------------------

fn banded(target: &[f64]) -> Vec<f64> {
    let mut out = vec![0.0f64; target.len()];
    let mut held = 1.0f64;
    for i in 0..target.len() {
        if (target[i] - held).abs() > BAND {
            held = target[i];
        }
        out[i] = held;
    }
    out
}

fn trailing_mean(px: &[f64], win: usize) -> Vec<f64> {
    let mut out = vec![0.0f64; px.len()];
    let mut s = 0.0f64;
    for i in 0..px.len() {
        s += px[i];
        if i >= win {
            s -= px[i - win];
        }
        out[i] = s / (i + 1).min(win) as f64;
    }
    out
}

fn sessions_for(cal_days: i32) -> usize {
    2.max((f64::from(cal_days) * 252.0 / 365.25).round() as usize)
}

/// `f64` ordered by `total_cmp`, so it can sit in a `BinaryHeap`. Scala's
/// `PriorityQueue[Double]` is a max-heap under `Ordering[Double]`; this is its counterpart.
#[derive(PartialEq)]
struct Ord64(f64);
impl Eq for Ord64 {}
impl Ord for Ord64 {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        self.0.total_cmp(&other.0)
    }
}
// Derived on purpose from `cmp` rather than from the field: `f64`'s own `PartialOrd`
// returns None for NaN, which would make the heap ordering inconsistent with `Ord`.
impl PartialOrd for Ord64 {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

/// Per-path indicator cache. The moving averages are memoised because several rules ask for
/// the same window, and `vol_ratio` is computed once — mirroring Scala's `HashMap` +
/// `lazy val`. Interior mutability is what lets the exposure closures take `&Indicators`;
/// an `Indicators` never crosses a thread, it is built inside each parallel path.
struct Indicators {
    px: Vec<f64>,
    ma_cache: std::cell::RefCell<std::collections::HashMap<usize, std::rc::Rc<Vec<f64>>>>,
    vol_ratio: std::cell::OnceCell<Vec<f64>>,
}

impl Indicators {
    fn new(px: &[f64]) -> Self {
        Self {
            px: px.to_vec(),
            ma_cache: std::cell::RefCell::new(std::collections::HashMap::new()),
            vol_ratio: std::cell::OnceCell::new(),
        }
    }

    fn ma(&self, sessions: usize) -> std::rc::Rc<Vec<f64>> {
        if let Some(v) = self.ma_cache.borrow().get(&sessions) {
            return std::rc::Rc::clone(v);
        }
        let v = std::rc::Rc::new(trailing_mean(&self.px, sessions));
        self.ma_cache
            .borrow_mut()
            .insert(sessions, std::rc::Rc::clone(&v));
        v
    }

    /// Realised vol relative to its own running MEDIAN, via the two-heap median: `lower` is
    /// a max-heap holding the smaller half, `upper` a min-heap holding the larger, so
    /// `lower`'s root IS the median. Scala gets `upper` by passing `Ordering[Double].reverse`
    /// to `PriorityQueue`; `Reverse<Ord64>` is the same thing.
    fn vol_ratio(&self) -> &[f64] {
        self.vol_ratio.get_or_init(|| {
            let px = &self.px;
            let n = px.len();
            let mut rv = vec![0.0f64; n];
            let mut ew = 0.01 * 0.01f64;
            for i in 1..n {
                let r = (px[i] / px[i - 1]).ln();
                ew = 0.94 * ew + 0.06 * r * r;
                rv[i] = (ew * DAYS_PER_YEAR as f64).sqrt();
            }
            let mut lower: BinaryHeap<Ord64> = BinaryHeap::new();
            let mut upper: BinaryHeap<Reverse<Ord64>> = BinaryHeap::new();
            let mut out = vec![0.0f64; n];
            out[0] = 1.0;
            for i in 1..n {
                if i > 260 {
                    let x = rv[i];
                    if lower.is_empty() || lower.peek().is_some_and(|m| x <= m.0) {
                        lower.push(Ord64(x));
                    } else {
                        upper.push(Reverse(Ord64(x)));
                    }
                    if lower.len() > upper.len() + 1 {
                        if let Some(m) = lower.pop() {
                            upper.push(Reverse(m));
                        }
                    } else if upper.len() > lower.len() {
                        if let Some(Reverse(m)) = upper.pop() {
                            lower.push(m);
                        }
                    }
                    out[i] = if rv[i] > 0.0 {
                        lower.peek().map_or(1.0, |m| m.0) / rv[i]
                    } else {
                        1.0
                    };
                } else {
                    out[i] = 1.0;
                }
            }
            out
        })
    }
}

/// `Arc` rather than `Box` so the matched-constant arms can wrap a rule's own exposure
/// function, and so the rules survive being shared across rayon threads.
type ExposeFn = Arc<dyn Fn(&Indicators) -> Vec<f64> + Send + Sync>;

#[derive(Clone)]
struct Rule {
    name: String,
    expose: ExposeFn,
}

fn trend_rule(cal_days: i32, floor: f64) -> Rule {
    let name = format!("trend {cal_days}d, floor {}%", jf(floor * 100.0, 0, 0));
    Rule {
        name,
        expose: Arc::new(move |ind: &Indicators| {
            let ma = ind.ma(sessions_for(cal_days));
            let t: Vec<f64> = (0..ind.px.len())
                .map(|i| if ind.px[i] >= ma[i] { 1.0 } else { floor })
                .collect();
            banded(&t)
        }),
    }
}

fn drawdown_rule(pct: f64, floor: f64) -> Rule {
    let name = format!(
        "cut below -{}%, floor {}%",
        jf(pct, 0, 0),
        jf(floor * 100.0, 0, 0)
    );
    Rule {
        name,
        expose: Arc::new(move |ind: &Indicators| {
            let px = &ind.px;
            let mut out = vec![0.0f64; px.len()];
            let mut pk = 0.0f64;
            for i in 0..px.len() {
                pk = pk.max(px[i]);
                out[i] = if px[i] < pk * (1.0 - pct / 100.0) {
                    floor
                } else {
                    1.0
                };
            }
            banded(&out)
        }),
    }
}

fn vol_rule(floor: f64) -> Rule {
    let name = format!("volatility-scaled, floor {}%", jf(floor * 100.0, 0, 0));
    Rule {
        name,
        expose: Arc::new(move |ind: &Indicators| {
            let t: Vec<f64> = ind
                .vol_ratio()
                .iter()
                .map(|r| floor.max(1.0f64.min(*r)))
                .collect();
            banded(&t)
        }),
    }
}

fn combo_rule(cal_days: i32, floor: f64) -> Rule {
    let name = format!(
        "volatility + trend {cal_days}d, floor {}%",
        jf(floor * 100.0, 0, 0)
    );
    Rule {
        name,
        expose: Arc::new(move |ind: &Indicators| {
            let ma = ind.ma(sessions_for(cal_days));
            let vr = ind.vol_ratio();
            let t: Vec<f64> = (0..ind.px.len())
                .map(|i| {
                    let v = 1.0f64.min(0.0f64.max(vr[i]));
                    let tr = if ind.px[i] >= ma[i] { 1.0 } else { 0.0 };
                    floor.max(v.min(tr))
                })
                .collect();
            banded(&t)
        }),
    }
}

fn rules() -> Vec<Rule> {
    vec![
        Rule {
            name: "always fully invested".to_string(),
            expose: Arc::new(|ind: &Indicators| vec![1.0; ind.px.len()]),
        },
        // production analog — the paired-comparison reference
        vol_rule(0.4),
        vol_rule(0.0),
        trend_rule(150, 0.0),
        trend_rule(200, 0.4),
        trend_rule(200, 0.0),
        trend_rule(250, 0.0),
        drawdown_rule(10.0, 0.0),
        combo_rule(200, 0.0),
    ]
}

#[expect(
    clippy::panic,
    reason = "mirrors the Scala's sys.error: a report naming a nonexistent rule is a coding \
              error in this file, not a runtime condition to recover from"
)]
fn rule_named(nm: &str) -> Rule {
    rules()
        .into_iter()
        .find(|r| r.name == nm)
        .unwrap_or_else(|| panic!("report names a rule not in Rules: [{nm}]"))
}

// ---- evaluation -------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Safe {
    Cash,
    Bond,
}

/// The exposure-matched constant twin of a rule ON THIS PATH: the same average exposure,
/// held flat, in the same two assets.
fn matched_constant(e: &[f64]) -> Vec<f64> {
    // bound FIRST: the Scala note is that an inline e.sum would be recomputed per element
    let m = scala_sum(e.iter().copied()) / e.len() as f64;
    vec![m; e.len()]
}

struct ArmPath {
    log_eq: Vec<f64>,
    real_log_eq: Vec<f64>,
    steps: Vec<f64>,
    mean_e: f64,
    churn: f64,
    eff_churn: f64,
    cost_paid: f64,
    eq_ret_sum: f64,
    safe_ret_sum: f64,
}

/// What ONE arm actually earned: its log-equity path, the real counterpart, the daily steps,
/// and the trading totals. Everything that grades an arm reads this, so no two reports can
/// disagree about what the arm did.
fn arm_path(p: &Path, e: &[f64], cost: f64, safe: Safe) -> ArmPath {
    let n = p.price.len();
    // day i earns: exposure e(i-1) times the asset return, the remainder times the safe
    // return, minus |exposure change| * cost * that session's liquidity state
    let eq_rets = MatD::apply(&daily_returns(&p.price));
    let safe_rets = match safe {
        Safe::Cash => MatD::apply(
            &(0..n - 1)
                .map(|k| p.rate[k].ln_1p() / DAYS_PER_YEAR as f64)
                .collect::<Vec<f64>>(),
        ),
        Safe::Bond => MatD::apply(&daily_returns(&p.bond)),
    };
    let e_held = MatD::apply(e).head(n - 1);
    let d_e = MatD::apply(
        &(0..n - 1)
            .map(|k| (e[k + 1] - e[k]).abs())
            .collect::<Vec<f64>>(),
    );
    // tail is end-anchored where copyOfRange(p.liq, 1, n) was start-anchored; these agree
    // because every Path series is allocated at `tot` and dropped by BurnIn together
    let liq_t = MatD::apply(&p.liq).tail(n - 1);
    let costs = &(&d_e * cost) * &liq_t;
    let steps = &(&(&e_held * &eq_rets) + &(&(1.0 - &e_held) * &safe_rets)) - &costs;
    let mut eq = vec![0.0f64; n];
    eq[1..n].copy_from_slice(&steps.cumsum().toArray());
    let real_eq = (&MatD::apply(&eq)
        - &MatD::apply(
            &(0..n)
                .map(|k| (p.cpi[k] / p.cpi[0]).ln())
                .collect::<Vec<f64>>(),
        ))
        .toArray();
    ArmPath {
        log_eq: eq,
        real_log_eq: real_eq,
        steps: steps.toArray(),
        mean_e: scala_sum(e.iter().copied()) / e.len() as f64,
        churn: d_e.sum(),
        eff_churn: (&d_e * &liq_t).sum(),
        cost_paid: costs.sum(),
        eq_ret_sum: eq_rets.sum(),
        safe_ret_sum: safe_rets.sum(),
    }
}

/// Depth below the running peak, session by session — the series every depth measure reduces.
fn drawdown_series(log_eq: &[f64]) -> Vec<f64> {
    let mut out = vec![0.0f64; log_eq.len()];
    let mut pk = log_eq[0];
    for i in 0..log_eq.len() {
        pk = pk.max(log_eq[i]);
        out[i] = 1.0 - (log_eq[i] - pk).exp();
    }
    out
}

/// An underwater stretch: from a running peak until the path regains it. A stretch still
/// under water at path end is INCLUDED at its length so far.
#[derive(Clone, Copy, Debug)]
struct Underwater {
    peak: usize,
    end: usize,
    worst_depth: f64,
}

impl Underwater {
    fn sessions(self) -> usize {
        self.end - self.peak
    }
}

fn underwater(log_eq: &[f64]) -> Vec<Underwater> {
    let mut out: Vec<Underwater> = Vec::new();
    let mut pk = log_eq[0];
    let mut pk_i = 0usize;
    let mut i = 1usize;
    while i < log_eq.len() {
        if log_eq[i] >= pk {
            pk = log_eq[i];
            pk_i = i;
            i += 1;
        } else {
            let mut j = i;
            let mut worst = 0.0f64;
            while j < log_eq.len() && log_eq[j] < pk {
                worst = worst.max(1.0 - (log_eq[j] - pk).exp());
                j += 1;
            }
            out.push(Underwater {
                peak: pk_i,
                end: j,
                worst_depth: worst,
            });
            if j < log_eq.len() {
                pk = log_eq[j];
                pk_i = j;
                i = j + 1;
            } else {
                i = log_eq.len();
            }
        }
    }
    out
}

/// Worst depth reached only AFTER a stretch has outlasted a cash buffer of `buf_sessions`.
/// NaN when the stretch never exhausts the buffer — such an episode forces no sale and costs
/// nothing, so entering it as a zero would flatter the average with episodes that never
/// happened.
fn depth_at_exhaustion(log_eq: &[f64], u: Underwater, buf_sessions: usize) -> f64 {
    let from = u.peak + buf_sessions;
    if from >= u.end {
        return f64::NAN;
    }
    let pk = log_eq[u.peak];
    let vals: Vec<f64> = (from..u.end)
        .map(|k| 1.0 - (log_eq[k] - pk).exp())
        .collect();
    max_total(&vals)
}

/// `.max` on a Scala `Seq[Double]` under `TotalOrdering`.
fn max_total(v: &[f64]) -> f64 {
    if v.is_empty() {
        return f64::NAN;
    }
    let s = sorted_total(v);
    s[s.len() - 1]
}

// ---- evaluation -------------------------------------------------------------------------

/// NOTE ON FRAMES: differences of annual returns (vsFlat, the decomposition) are
/// DEFLATOR-INVARIANT — subtracting the same inflation from both sides cancels. What real
/// grading changes is the PATH: real drawdowns, real ruin, and the sustainable withdrawal
/// rate. `swr` is the pipeline's own decision lens for the cash-vehicle question.
#[derive(Clone, Copy, Debug)]
#[expect(
    dead_code,
    reason = "ann_g, mean_e and swr_lo are carried by the Scala Outcome but read by no \
              report column; kept so the record matches field for field"
)]
struct Outcome {
    ann_g: f64,
    ann: f64,
    max_dd: f64,
    real_dd: f64,
    mean_e: f64,
    vs_flat_g: f64,
    vs_flat: f64,
    churn: f64,
    eff_churn: f64,
    swr: f64,
    swr_lo: f64,
}

impl Outcome {
    fn slip_mult(self) -> f64 {
        if self.churn > 1e-9 {
            self.eff_churn / self.churn
        } else {
            1.0
        }
    }
}

fn fundamental_led(p: &Path, ep: Episode) -> bool {
    let pd = (p.price[ep.trough] / p.price[ep.peak]).ln();
    let fd = (p.fundamental[ep.trough] / p.fundamental[ep.peak]).ln();
    fd <= 0.5 * pd
}

/// `vsFlat` is the advantage over a CONSTANT portfolio holding this rule's own average
/// exposure IN THE SAME TWO ASSETS — with safe = Bond it is timing versus a static
/// equity/bond mix, the house matched-constant control at the two-asset level.
#[expect(
    clippy::too_many_arguments,
    reason = "mirrors the Scala signature; bundling them would obscure the correspondence"
)]
fn evaluate(
    p: &Path,
    eps: &[Episode],
    fund_led: &[bool],
    rule: &ExposeFn,
    ind: &Indicators,
    cost: f64,
    years: usize,
    safe: Safe,
) -> (Outcome, Vec<(bool, f64, f64)>) {
    let n = p.price.len();
    let ap = arm_path(p, &rule(ind), cost, safe);
    let eq = &ap.log_eq;
    // maximum drawdown IS this formula; the scalar scan existed only because cummax was missing
    let eq_v = MatD::apply(eq);
    let real_eq_v = MatD::apply(&ap.real_log_eq);
    let mdd = 1.0 - (&eq_v - &eq_v.cummax(0)).exp().min();
    let mdd_r = 1.0 - (&real_eq_v - &real_eq_v.cummax(0)).exp().min();
    let (swr_med, swr_low) = swr_stats(&ap.real_log_eq);
    let me = ap.mean_e;
    // the constant twin never trades, so its return is exactly linear in the two totals
    let ann_n = eq[n - 1] / years as f64 * 100.0;
    let ann_g = (eq[n - 1] + ap.cost_paid) / years as f64 * 100.0;
    let flat = (me * ap.eq_ret_sum + (1.0 - me) * ap.safe_ret_sum) / years as f64 * 100.0;
    let per_ep: Vec<(bool, f64, f64)> = eps
        .iter()
        .zip(fund_led)
        .map(|(ep, fl)| {
            let end = if ep.recovered >= 0 {
                ep.recovered as usize
            } else {
                n - 1
            };
            (
                *fl,
                eq[end] - eq[ep.peak],
                (p.price[end] / p.price[ep.peak]).ln(),
            )
        })
        .collect();
    (
        Outcome {
            ann_g,
            ann: ann_n,
            max_dd: mdd * 100.0,
            real_dd: mdd_r * 100.0,
            mean_e: me,
            vs_flat_g: ann_g - flat,
            vs_flat: ann_n - flat,
            churn: ap.churn / years as f64,
            eff_churn: ap.eff_churn / years as f64,
            swr: swr_med,
            swr_lo: swr_low,
        },
        per_ep,
    )
}

/// Every world is tagged CHARACTER (false) or REFLEXIVE (true). A character world varies what
/// the market is like; a reflexive world changes WHO IS TRADING, by handing the crowd a rule to
/// run. They answer different questions and must never be averaged together — see
/// `run_strategy_sweep`, where the ranks are computed over each set separately.
///
/// `with_reflexive` is false for `-power` and `-buffer`: reflexivity is the point in the
/// rank-stability table, and a second-order effect on dispersion and crash dynamics elsewhere.
fn sweep_worlds(
    base: &World,
    single: bool,
    with_reflexive: bool,
) -> Vec<(&'static str, World, bool)> {
    if single {
        return vec![("baseline", *base, false)];
    }
    let with = |f: fn(&mut World)| -> World {
        let mut w = *base;
        f(&mut w);
        w
    };
    let mut out = vec![
        ("baseline", *base, false),
        // RELATIVE, not absolute. Absolute perturbation points are silently invalidated by a
        // change of defaults: at 0.19.1 the old pairs stopped bracketing the baseline entirely —
        // "few trend followers" (0.15) had 2.5x the baseline's trend followers and "deep market"
        // (15.0) was shallower than it. A multiplier below 1 and one above cannot stop straddling
        // the base, so the property is structural instead of a thing to remember to re-check.
        // (A base of exactly 0 collapses both arms onto it; that is true of the existing relative
        // arms too, and `-stress 0` already has it.)
        // The mandate is a spring, so the REALIZED share moves far less than the mandate: these
        // arms span 0.19-0.30 realized against the baseline's 0.22.
        ("few trend followers", with(|w| w.trend_share /= 3.0), false),
        (
            "many trend followers",
            with(|w| w.trend_share *= 3.0),
            false,
        ),
        ("no liquidity spiral", with(|w| w.stress = 0.0), false),
        ("severe liquidity spiral", with(|w| w.stress *= 1.5), false),
        ("weak value anchor", with(|w| w.value_pull *= 0.6), false),
        ("calm volatility", with(|w| w.vol_of_vol *= 0.5), false),
        ("turbulent volatility", with(|w| w.vol_of_vol *= 2.0), false),
        ("sticky capital", with(|w| w.beta = 1.0), false),
        ("fickle capital", with(|w| w.beta = 6.0), false),
        ("low growth", with(|w| w.drift = 0.060), false),
        ("high growth", with(|w| w.drift = 0.140), false),
        ("shallow market", with(|w| w.depth *= 0.8), false),
        ("deep market", with(|w| w.depth *= 1.25), false),
        // NOT "cash leg only" any more: the rate level sets bond carry, and the zero floor
        // binds at low rates (an emergent zero-lower-bound). These double as carry-level
        // probes (low ~ 2022, high ~ 1970s).
        ("low rates / low carry", with(|w| w.rate_mean = 0.01), false),
        (
            "high rates / high carry",
            with(|w| w.rate_mean = 0.07),
            false,
        ),
        // OFF-world: refuge
        // OFF-world: refuge. BOTH channels, because either alone leaves the bond a refuge by the
        // other route and the world stops being the off-switch it is labelled as.
        (
            "no refuge channel",
            with(|w| {
                w.easing = 0.0;
                w.refuge = 0.0;
            }),
            false,
        ),
        // OFF-world: margin
        ("no margin coupling", with(|w| w.margin = 0.0), false),
        // OFF-world: disasters
        ("no macro disasters", with(|w| w.disaster_rate = 0.0), false),
        // OFF-world: the valuation cycle
        (
            "no valuation cycle",
            with(|w| {
                w.belief_share = 0.0;
                w.cap_years = 0.0;
            }),
            false,
        ),
        (
            "double inflation severity",
            with(|w| w.infl_size *= 2.0),
            false,
        ),
    ];
    if with_reflexive {
        // TWO AXES, not two modes. Before the momentum crowd got a strength dial there was only
        // one dimension here, so "which crowd" was the whole question; now a mode entry that does
        // not state a strength silently picks the default, which is not the interesting value.
        // 0.20 rather than the default 0.030, and the two numbers are NOT comparable as strengths:
        // since 0.22.0 one impact law covers every crowd, and this crowd's target moves in small
        // continuous steps where the momentum crowd's swings across a saturating tanh. 0.20 is the
        // largest setting that stays a market — 0.30 fails the kurtosis realism band — and it still
        // only reaches 2.3% of the noise term against the default crowd's 5.2%. THAT IS THE
        // FINDING: a crowd selling into volatility destabilises the market faster than a crowd
        // buying trends, so it cannot be run as hard. Left at the default it would be inert (1.2%),
        // which is the dead-knob defect this entry exists to avoid.
        out.push((
            "reflexive: crowd runs a vol rule",
            with(|w| {
                w.crowd = Crowd::VolScaled;
                w.crowd_impact = 0.20;
            }),
            true,
        ));
        // 0.12 is the stress case: 4x the default, admissible on realism and mechanism, and outside
        // the persistence band — which is what pressing a trend crowd hard is SUPPOSED to look like,
        // and is disclosed rather than hidden.
        out.push((
            "reflexive: crowd pressed hard",
            with(|w| w.crowd_impact = 0.12),
            true,
        ));
    }
    out
}

// ---- grading statistics -----------------------------------------------------------------

/// Highest constant REAL withdrawal (% of starting balance/yr, inflation-adjusted monthly) the
/// REAL equity path could sustain for 30 years without exhaustion; closed-form, evaluated at
/// every rolling monthly start via prefix sums. Returns (median start, worst start).
fn swr_stats(real_log_eq: &[f64]) -> (f64, f64) {
    let monthly: Vec<f64> = (0..real_log_eq.len() / 21)
        .map(|m| real_log_eq[m * 21])
        .collect();
    // 30 years of monthly withdrawals
    let window = 360usize;
    if monthly.len() < window + 1 {
        return (f64::NAN, f64::NAN);
    }
    // closed form: the window starting at st survives withdrawal w iff
    //   w <= 1 / sum over the window of exp(C_st - C_t),  C = cumulative real log growth
    let rel = &MatD::apply(&monthly) - monthly[0];
    // pref(t) = sum of exp(-C_u), u <= t
    let pref = (&rel * -1.0).exp().cumsum().toArray();
    let exp_rel = rel.exp().toArray();
    let rates: Vec<f64> = (0..monthly.len() - window)
        .map(|st| 1200.0 / (exp_rel[st] * (pref[st + window] - pref[st])))
        .collect();
    (pctile(&rates, 0.5), min_total(&rates))
}

/// `.min` on a Scala `Seq[Double]` under `TotalOrdering`.
fn min_total(v: &[f64]) -> f64 {
    if v.is_empty() {
        return f64::NAN;
    }
    sorted_total(v)[0]
}

/// The candidate grading statistics for one arm, NAMED AT THE SOURCE so no report can
/// mislabel a column. maxDD, Calmar and max-years-under are single order statistics and are
/// here precisely so the power report can price what reading one extremum costs.
fn grading_stats(ap: &ArmPath, years: usize) -> Vec<(&'static str, f64)> {
    let eq = &ap.log_eq;
    let n = eq.len();
    let depths = drawdown_series(eq);
    let depths_r = drawdown_series(&ap.real_log_eq);
    let mu = scala_sum(ap.steps.iter().copied()) / ap.steps.len() as f64;
    let sd = (MatD::apply(&ap.steps).power(2).mean() - mu * mu).sqrt();
    let max_dd = max_total(&depths) * 100.0;
    let ann_ret = eq[n - 1] / years as f64 * 100.0;
    let longest = underwater(&ap.real_log_eq)
        .iter()
        .map(|u| u.sessions())
        .max()
        .unwrap_or(0);
    vec![
        ("annRet %/yr", ann_ret),
        (
            "Sharpe",
            if sd > 0.0 {
                mu / sd * (DAYS_PER_YEAR as f64).sqrt()
            } else {
                f64::NAN
            },
        ),
        ("maxDD %", max_dd),
        (
            "Calmar",
            if max_dd > 0.0 {
                ann_ret / max_dd
            } else {
                f64::NAN
            },
        ),
        (
            "frac under (nom)",
            depths.iter().filter(|d| **d > 0.0).count() as f64 / n as f64,
        ),
        (
            "frac under (real)",
            depths_r.iter().filter(|d| **d > 0.0).count() as f64 / n as f64,
        ),
        (
            "Ulcer %",
            MatD::apply(&depths).power(2).mean().sqrt() * 100.0,
        ),
        ("max yrs under", longest as f64 / DAYS_PER_YEAR as f64),
        ("real 30y SWR %", swr_stats(&ap.real_log_eq).0),
    ]
}

fn stat_names() -> Vec<&'static str> {
    grading_stats(
        &ArmPath {
            log_eq: vec![0.0, 0.0],
            real_log_eq: vec![0.0, 0.0],
            steps: vec![0.0],
            mean_e: 1.0,
            churn: 0.0,
            eff_churn: 0.0,
            cost_paid: 0.0,
            eq_ret_sum: 0.0,
            safe_ret_sum: 0.0,
        },
        1,
    )
    .into_iter()
    .map(|(n, _)| n)
    .collect()
}

/// n* counts histories, so it floors at 1 — rounding 0.4 to "0" would read as "none needed".
fn n_star_str(x: f64) -> String {
    if x.is_nan() {
        "  n/a".to_string()
    } else if x > 9999.0 {
        ">9999".to_string()
    } else {
        jf(x.max(1.0), 5, 0)
    }
}

// ---- calibration search -----------------------------------------------------------------

pub type Setter = fn(&mut World, f64);
pub type Getter = fn(&World) -> f64;

/// Parameters that say WHICH ASSET is being simulated, not how a market behaves. Each is a real
/// fund's published number: MEASURED once and then held, never fitted. `-calibrate` must not
/// search one, for two reasons that are separate. A duration chosen to reduce loss describes no
/// bond anyone can buy, so the fitted world stops being a claim about a real asset. And
/// `-crossasset` grades the bond relations by MOVING duration across the values real funds have —
/// if the shipped duration were itself fitted, that grader would be scoring the search's choice
/// against bands the same search was free to accommodate, which is circular.
///
/// Enforced by `contract_tests` against `calibrate_ranges`, not by this comment: the 0.20.0
/// re-search proposed `duration = 11.1` and was refused by hand, and a rule that lives in someone's
/// memory of that refusal is one range row away from being lost.
// Referenced only from the test module below: the rule is a contract on the ranges table, not
// a value any search reads.
#[cfg_attr(
    not(test),
    expect(
        dead_code,
        reason = "the rule is read by the tests, never by the search"
    )
)]
const IDENTITY_PARAMS: &[&str] = &["duration", "divYield"];

/// THE ORDER IS A CROSS-TWIN CONTRACT, restated here so a reorder fails a build rather than a
/// diff. `-calibrate` draws one uniform per dial from a single stream in table order, so a
/// permutation hands every draw to a different dial: the twins sampled different worlds from the
/// same seed for as long as their tables disagreed, and the loss gap that showed up downstream
/// read like a rounding divergence in the scoring path. It was this. A search archive's columns
/// are in this order too, so the order is also the archive's format. Same shape as `EMIT_SCHEMA` /
/// `EmitSchema`: the literal is in the model, checked by each twin's own contract test, and
/// changing one twin without the other cannot pass.
pub const CALIBRATE_DIAL_ORDER: [&str; 34] = [
    "depth",
    "trendShare",
    "drift",
    "fundVol",
    "crowdImpact",
    "stress",
    "valuePull",
    "recoveryDrag",
    "recoveryFloor",
    "disasterRate",
    "disasterSize",
    "disasterRecover",
    "beliefShare",
    "capYears",
    "volOfVol",
    "jumpVar",
    "jumpRate",
    "leverage",
    "downShock",
    "jumpSkew",
    "newsRate",
    "newsSize",
    "refugeDays",
    "easing",
    "refuge",
    "inflSize",
    "discount",
    "margin",
    "slowShare",
    "slowVol",
    "slowBeta",
    "slowPerm",
    "beliefYears",
    "bustAmp",
];

/// What `-calibrate` samples, and the ONLY place a searchable parameter is declared. A function
/// rather than an inline `vec!` so the identity-parameter rule above can be tested against it.
///
/// EVERY BOUND CONTAINS EVERY FROZEN WORLD, and `contract_tests` asserts it. Four did not:
/// `margin` shipped at 0.006 from 0.19.1 onward against a ceiling of 0.004, so for eleven releases
/// the search could not propose the value the model itself uses and every candidate-vs-default
/// line it printed was across a boundary the candidate could not cross; `depth` 8.4 (the 0.24.0
/// Nasdaq recipes) sat under a floor of 10.0, `jumpRate` 0.005 (0.22.x) and 0 (through 0.20.0)
/// outside 0.0004 .. 0.004, `volOfVol` 0.011 (0.19.x) under 0.012. This is the `fundVol` failure
/// the note below already records, and the test is what stops it recurring: a range that excludes
/// a shipped world is a search that cannot reach the model. Bounds are rounded OUTWARD past the
/// extreme so a value at the edge can still be explored.
#[expect(
    clippy::too_many_lines,
    reason = "one row per searched dial, mirroring the Scala twin's table; splitting it would               put the bounds somewhere other than beside the dial they bound"
)]
pub fn calibrate_ranges() -> Vec<(&'static str, f64, f64, Setter, Getter)> {
    vec![
        ("depth", 8.0, 26.0, |w, x| w.depth = x, |w| w.depth),
        (
            "trendShare",
            0.05,
            0.70,
            |w, x| w.trend_share = x,
            |w| w.trend_share,
        ),
        ("drift", 0.06, 0.16, |w, x| w.drift = x, |w| w.drift),
        // The depth profile's second axis, and the one no sweep could reach before 0.21: the value
        // channel passes only a few percent of a fundamental move into any one session, so
        // fundamental variance accumulates into time under water without moving daily return scale.
        // It is in the search only now that the depth targets are stated against a real relation —
        // against SPY's absolute levels a search free to raise it would have closed them by making
        // the fundamental hotter still, which is how the world it replaces was reached.
        ("fundVol", 0.03, 0.16, |w, x| w.fund_vol = x, |w| w.fund_vol),
        (
            "crowdImpact",
            0.01,
            0.20,
            |w, x| w.crowd_impact = x,
            |w| w.crowd_impact,
        ),
        ("stress", 2.0, 6.0, |w, x| w.stress = x, |w| w.stress),
        // Widened from 0.010-0.035 in 0.21.0: with the recovery drag the base pull governs
        // SHALLOW water only, so its useful range moved up. The old ceiling would have excluded the
        // shipped value, which is the `fund_vol` failure mode — a search that cannot reach the
        // answer.
        (
            "valuePull",
            0.010,
            0.070,
            |w, x| w.value_pull = x,
            |w| w.value_pull,
        ),
        // Both in the ranges from the release they arrive in, for the same reason.
        (
            "recoveryDrag",
            0.0,
            20.0,
            |w, x| w.recovery_drag = x,
            |w| w.recovery_drag,
        ),
        (
            "recoveryFloor",
            0.05,
            1.0,
            |w, x| w.recovery_floor = x,
            |w| w.recovery_floor,
        ),
        (
            "disasterRate",
            0.0,
            1.5,
            |w, x| w.disaster_rate = x,
            |w| w.disaster_rate,
        ),
        (
            "disasterSize",
            0.5,
            2.5,
            |w, x| w.disaster_size = x,
            |w| w.disaster_size,
        ),
        (
            "disasterRecover",
            0.0,
            0.9,
            |w, x| w.disaster_recover = x,
            |w| w.disaster_recover,
        ),
        (
            "beliefShare",
            0.0,
            0.97,
            |w, x| w.belief_share = x,
            |w| w.belief_share,
        ),
        (
            "capYears",
            0.0,
            8.0,
            |w, x| w.cap_years = x,
            |w| w.cap_years,
        ),
        (
            "volOfVol",
            0.010,
            0.030,
            |w, x| w.vol_of_vol = x,
            |w| w.vol_of_vol,
        ),
        // In the ranges from the release it arrived in. `fund_vol` sat outside them for four
        // releases and that is exactly why its defect survived four releases of one-knob-at-a-time
        // sweeps; a mechanism the search cannot reach is a mechanism nobody will find the wrong
        // value of.
        ("jumpVar", 0.00, 0.20, |w, x| w.jump_var = x, |w| w.jump_var),
        (
            "jumpRate",
            0.0,
            0.006,
            |w, x| w.jump_rate = x,
            |w| w.jump_rate,
        ),
        // The asymmetry pair and the jump shift, in the ranges the hand sweeps mapped: leverage
        // reaches the `leverage corr` anchor near 0.10 under the saturation cap, downShock pays
        // vr60 ~+0.02 per 0.01 so the band bounds it near 0.03, and the best hand candidate
        // (0.10 / 0.015 / jumpVar 0.12 / drift 0.124) missed a four-seed gate PASS only on
        // `bond depth vs vol` — the search has the bond dials in its hands where a hand sweep
        // does not.
        ("leverage", 0.0, 0.15, |w, x| w.leverage = x, |w| w.leverage),
        (
            "downShock",
            0.0,
            0.05,
            |w, x| w.down_shock = x,
            |w| w.down_shock,
        ),
        (
            "jumpSkew",
            0.0,
            1.4,
            |w, x| w.jump_skew = x,
            |w| w.jump_skew,
        ),
        (
            "newsRate",
            0.0,
            3.0,
            |w, x| w.news_rate = x,
            |w| w.news_rate,
        ),
        (
            "newsSize",
            0.0,
            0.05,
            |w, x| w.news_size = x,
            |w| w.news_size,
        ),
        (
            "refugeDays",
            0.0,
            3.0,
            |w, x| w.refuge_days = x,
            |w| w.refuge_days,
        ),
        ("easing", 0.0, 0.09, |w, x| w.easing = x, |w| w.easing),
        ("refuge", 0.0, 0.20, |w, x| w.refuge = x, |w| w.refuge),
        (
            "inflSize",
            0.03,
            0.12,
            |w, x| w.infl_size = x,
            |w| w.infl_size,
        ),
        ("discount", 3.0, 10.0, |w, x| w.discount = x, |w| w.discount),
        ("margin", 0.0, 0.008, |w, x| w.margin = x, |w| w.margin),
        // THE SLOW REPRICING CHANNEL's share and scale, searched from 0.24.2: the channel is what
        // carries the |r| autocorrelation past lag 20 (the S&P reads 0.117 at lag 60 with it and
        // the Nasdaq recipe, which runs it at 0, reads 0.036 against a record of 0.175), and no
        // searched dial could reach that row — measured on a 113-member archive, the one row no
        // member reached. `slow_vol` beside `slow_share` because the channel bypasses the spiral:
        // the share takes volatility out and the scale gives it back without thinning the market
        // (0.5 / 1.5 on the Nasdaq recipe reads vol 27.2, lag-20 0.27, lag-60 0.175 against
        // 26.9 / 0.25 / 0.175).
        (
            "slowShare",
            0.0,
            0.80,
            |w, x| w.slow_share = x,
            |w| w.slow_share,
        ),
        ("slowVol", 0.5, 2.50, |w, x| w.slow_vol = x, |w| w.slow_vol),
        // THE CHANNEL'S BOND LEG AND ITS PERMANENT SHARE, searched from 0.24.3. The bond takes
        // `slowBeta` x the equity's repricing, so with `slowBeta` fixed every step the search
        // took in `slowVol` moved Treasury vol with it: at 0.5 / 1.5 on the Nasdaq recipe the
        // bond leaves its 0.70-1.10x-duration band, and the archive could not grow the equity's
        // channel without growing the bond's. Held at the recipe's product (slowBeta x slowVol),
        // 0.4 / 1.3 passes every class at vol 25.1 with lag-20 0.23 at the recipe's loss. `slowPerm` because
        // the share that reaches the fundamental sets what the value channel has to close, which
        // is the channel's variance-ratio and drawdown footprint; both dials ship at one value
        // in every frozen world.
        (
            "slowBeta",
            0.0,
            1.20,
            |w, x| w.slow_beta = x,
            |w| w.slow_beta,
        ),
        (
            "slowPerm",
            0.0,
            1.00,
            |w, x| w.slow_perm = x,
            |w| w.slow_perm,
        ),
        // THE BELIEF HALF-LIFE, searched from 0.24.3 beside `beliefShare` and `capYears`: the
        // 0.23.0 sweep found 1.0 / 0.95 puts the cycle's persistence on Shiller's where 1.5 /
        // 0.85 (the Nasdaq recipe) does not, and nothing graded the wings until item 25's rows.
        (
            "beliefYears",
            0.5,
            4.00,
            |w, x| w.belief_years = x,
            |w| w.belief_years,
        ),
        // THE BUST SWING's amplitude, searched from 0.24.3: 0.14 reads the record's mania bust on
        // the Nasdaq recipe (item 25); the wings and the typical-year row are what it trades
        // against.
        ("bustAmp", 0.0, 0.30, |w, x| w.bust_amp = x, |w| w.bust_amp),
    ]
}

fn calibrate(a: Anchors, n_samples: usize, base: &World, seed: u64) {
    // depth, trendShare, drift and crowdImpact are in the search because they are the strongest
    // levers on the
    // two defects the eight below cannot reach. depth carries crash frequency (at fixed stress,
    // 12 -> 24 takes it from 35 to 13 per century) but moves volatility in lockstep with it.
    // drift is the ONLY knob that moves the depth profile at constant volatility — which is why
    // it cannot be searched without the return-per-vol band above, or the search buys the depth
    // rungs with a Sharpe no 20-year stretch of the real record produced. Their CLI flags are
    // inert under -calibrate, exactly like the eight below.
    let ranges = calibrate_ranges();
    // the only RNG in the program that was not already NumPyRng
    let mut sr = NumPyRng::new(seed ^ 0x5ca1_ab1e);
    let train_seed = seed;
    let hold_seed = seed + 7_777_777;
    // scored at 100-year paths: an 80-year protocol missed a worst-crash blowup that only
    // appears at the horizon actually used — tune at the scale you evaluate at
    let score = |w: &World, s: u64| -> f64 {
        // The extreme rows' median ensemble rides along at the same 50 histories, so a
        // candidate is priced on the same statistic every report reads.
        fitness(
            a,
            &measure(&sim_paths(w, 50, 100, s), 100),
            &extreme_score_stats(a, 50, s, w),
        )
        .0
    };
    eprintln!(
        "calibrate: {n_samples} samples, 50 paths x 100 years each; holdout re-score of top 5"
    );
    let mut scored: Vec<(f64, World, String)> = (0..n_samples)
        .map(|k| {
            let mut w = *base;
            let mut desc: Vec<String> = Vec::new();
            for (nm, lo, hi, set, _) in &ranges {
                let x = sr.uniform(*lo, *hi);
                set(&mut w, x);
                desc.push(format!("{nm}={}", jf(x, 0, 4)));
            }
            let f = score(&w, train_seed);
            eprintln!(
                "  sample {}  train loss {}",
                jf(k as f64, 3, 0),
                jf(f, 7, 3)
            );
            (f, w, desc.join(" "))
        })
        .collect();
    // Scala's sortBy(_._1) is stable; sort_by with total_cmp matches
    scored.sort_by(|a, b| a.0.total_cmp(&b.0));
    println!("top 5 of {n_samples}, re-scored on the HELD-OUT seed:");
    for (f, w, d) in scored.iter().take(5) {
        let h = score(w, hold_seed);
        println!("  train {}   holdout {}   {d}", jf(*f, 7, 3), jf(h, 7, 3));
    }
    println!(
        "current defaults: train {}   holdout {}",
        jf(score(base, train_seed), 0, 3),
        jf(score(base, hold_seed), 0, 3)
    );
}

// ---- the strategy sweep -----------------------------------------------------------------

/// Index of the paired-comparison reference rule (the production analog, `volRule(0.4)`).
const REF_IDX: usize = 1;

/// One arm on one path: its Outcome, plus the per-crash-window entries
/// `(fundamental-led?, rule log return, buy-and-hold log return)`.
type ArmOutcome = (Outcome, Vec<(bool, f64, f64)>);
type PathOutcomes = Vec<ArmOutcome>;
type Evald = Vec<PathOutcomes>;

/// Evaluate every rule on every path, cash leg then bond leg — the `2 * Rules.size` layout
/// every column below indexes into.
fn eval_world(sims: &[Path], cost: f64, years: usize) -> Evald {
    let rs = rules();
    (0..sims.len())
        .into_par_iter()
        .map(|k| {
            let s = &sims[k];
            let ind = Indicators::new(&s.price);
            let eps = episodes(&s.price, 15.0);
            let fl: Vec<bool> = eps.iter().map(|ep| fundamental_led(s, *ep)).collect();
            let mut out: PathOutcomes = rs
                .iter()
                .map(|r| evaluate(s, &eps, &fl, &r.expose, &ind, cost, years, Safe::Cash))
                .collect();
            out.extend(
                rs.iter()
                    .map(|r| evaluate(s, &eps, &fl, &r.expose, &ind, cost, years, Safe::Bond)),
            );
            out
        })
        .collect()
}

#[expect(
    clippy::too_many_lines,
    reason = "one linear report, mirroring the Scala twin statement for statement"
)]
#[expect(
    clippy::too_many_arguments,
    reason = "the parameter list mirrors the Scala twin's, and the twins are diffed"
)]
#[expect(
    clippy::cognitive_complexity,
    reason = "one linear report, mirroring the Scala twin section for section"
)]
fn run_strategy_sweep(
    a: Anchors,
    paths: usize,
    years: usize,
    seed: u64,
    cost: f64,
    single: bool,
    base: &World,
    gate_req: &[GateClass],
) {
    let rs = rules();
    let nr = rs.len();
    let worlds = sweep_worlds(base, single, true);
    eprintln!(
        "{} worlds x {paths} paths x {years} years, {nr} rules x {{cash,bond}}",
        worlds.len()
    );
    let results: Vec<(&str, bool, WorldStats, Evald, bool)> = worlds
        .iter()
        .map(|(wname, w, reflexive)| {
            let sims = sim_paths(w, paths, years, seed);
            let st = measure(&sims, years);
            let ok = gate_ok(a, &st, gate_req);
            (*wname, ok, st, eval_world(&sims, cost, years), *reflexive)
        })
        .collect();

    println!(
        "Worlds failing the acceptance gate are marked and EXCLUDED from rank stability; their"
    );
    println!(
        "detail stays visible so the exclusion is auditable.  vsFlat = advantage over a constant"
    );
    println!("portfolio at the rule's own average exposure IN THE SAME ASSETS; g/n = gross/net of");
    println!("liquidity-scaled trading costs.  ruin = share of paths with a loss worse than 50%.");
    for (wname, ok, st, evald, reflexive) in &results {
        println!(
            "\nWORLD: {:<34} {}{}",
            wname,
            if *reflexive { "[REFLEXIVE] " } else { "" },
            if *ok {
                ""
            } else {
                "*** OUT OF RANGE — excluded from ranks ***"
            }
        );
        println!(
            "  inflation {}%/yr   eq vol {}%  kurt {}  clus {}/{}  crashes/path {}  depth {}%  censored {}  trend share {}  clamp {}% (tail {}%)",
            jf(st.infl_ann, 0, 1),
            jf(st.vol * 100.0, 0, 1),
            jf(st.kurt, 0, 1),
            jf(st.ac1, 0, 2),
            jf(st.ac20, 0, 2),
            jf(st.ep_per_path, 0, 1),
            jf(st.depth_med, 0, 1),
            st.censored,
            jf(st.trend_share, 0, 2),
            jf(st.clamp_pct, 0, 3),
            jf(st.tail_floor_pct, 0, 1)
        );
        println!(
            "  bond vol {}%  growth-crash {}  infl-crash {}  corr {}/{}  bond spiral {}% of sessions",
            jf(st.bond_vol * 100.0, 0, 1),
            jfs(st.bond_growth, 1),
            jfs(st.bond_infl, 1),
            jfs(st.corr_calm, 2),
            jfs(st.corr_infl, 2),
            jf(st.pct_bond_stress * 100.0, 0, 1)
        );
        println!(
            "  {:<34} {:>8} {:>8} {:>7} {:>7} {:>5} {:>9} {:>9} {:>6} {:>6} {:>7} {:>9}",
            "rule",
            "ret/yr",
            "worst5%",
            "maxDD",
            "realDD",
            "ruin",
            "vsFlat g",
            "vsFlat n",
            "swr",
            "churn",
            "slip x",
            "beats ref"
        );
        for j in 0..nr {
            let outs: Vec<Outcome> = evald.iter().map(|v| v[j].0).collect();
            let refs: Vec<Outcome> = evald.iter().map(|v| v[REF_IDX].0).collect();
            let ann: Vec<f64> = outs.iter().map(|o| o.ann).collect();
            let ruin =
                outs.iter().filter(|o| o.max_dd > 50.0).count() as f64 / outs.len() as f64 * 100.0;
            let win = outs
                .iter()
                .zip(&refs)
                .filter(|(o, r)| o.ann > r.ann)
                .count() as f64
                / outs.len() as f64
                * 100.0;
            let win_txt = if j == REF_IDX {
                "ref".to_string()
            } else {
                format!("{}%", jf(win, 0, 0))
            };
            println!(
                "  {:<34} {}% {}% {}% {}% {}% {} {} {} {} {} {:>9}",
                rs[j].name,
                jf(pctile(&ann, 0.5), 7, 2),
                jf(pctile(&ann, 0.05), 7, 2),
                jf(
                    pctile(&outs.iter().map(|o| o.max_dd).collect::<Vec<f64>>(), 0.5),
                    6,
                    1
                ),
                jf(
                    pctile(&outs.iter().map(|o| o.real_dd).collect::<Vec<f64>>(), 0.5),
                    6,
                    1
                ),
                jf(ruin, 4, 0),
                jfsw(
                    pctile(&outs.iter().map(|o| o.vs_flat_g).collect::<Vec<f64>>(), 0.5),
                    9,
                    2
                ),
                jfsw(
                    pctile(&outs.iter().map(|o| o.vs_flat).collect::<Vec<f64>>(), 0.5),
                    9,
                    2
                ),
                jf(
                    pctile(&outs.iter().map(|o| o.swr).collect::<Vec<f64>>(), 0.5),
                    6,
                    2
                ),
                jf(
                    scala_sum(outs.iter().map(|o| o.churn)) / outs.len() as f64,
                    6,
                    2
                ),
                jf(
                    scala_sum(outs.iter().map(|o| o.slip_mult())) / outs.len() as f64,
                    7,
                    2
                ),
                win_txt
            );
        }
    }

    // Character and reflexive worlds are ranked SEPARATELY and never pooled. A character world
    // varies what the market is like; a reflexive world changes who is trading. One pooled
    // "stable across 21 worlds" that concealed an inversion in the two worlds most able to
    // produce one would be worse than not running them: the split is structural, not cosmetic.
    type Row<'a> = &'a (&'a str, bool, WorldStats, Evald, bool);
    let valid: Vec<Row> = results
        .iter()
        .filter(|(_, ok, _, _, refl)| *ok && !*refl)
        .collect();
    let reflexive: Vec<Row> = results
        .iter()
        .filter(|(_, ok, _, _, refl)| *ok && *refl)
        .collect();
    let n_char = results.iter().filter(|t| !t.4).count();
    let n_refl = results.len() - n_char;
    println!(
        "\n\nRANK STABILITY — {} of {} CHARACTER worlds pass the gate; ranks use only those.",
        valid.len(),
        n_char
    );
    println!("Rank stability is the WEAK form of robustness: magnitudes vary far more than ranks.");
    if !single {
        println!(
            "These ranks hold the crowd FIXED AND NON-REACTIVE; the reflexive panel below varies it."
        );
    }
    // An empty admissible set is a RESULT, not a table to print anyway: a rank over no worlds has
    // no best and no worst. Printing it as zeros reads as "every rule tied", which is a claim.
    if valid.is_empty() {
        println!("\n  no world in this sweep passes the required gate classes — nothing to rank.");
        println!(
            "  Widen the requirement with -gate, or fix the world; do not read the tables below"
        );
        println!("  as pooled over market-like worlds, because there are none.");
    }
    type OutcomeMetric = (&'static str, fn(&Outcome) -> f64);
    let metrics: Vec<OutcomeMetric> = vec![
        ("median net return", |o: &Outcome| o.ann),
        ("median GROSS edge vs the fixed twin", |o: &Outcome| {
            o.vs_flat_g
        }),
    ];
    for (metric_name, get) in metrics {
        if valid.is_empty() {
            break;
        }
        println!("\n  ranked by {metric_name}   (1 = best)");
        // `j` indexes both `rs` and each path's outcome vector, so it stays an index
        #[expect(
            clippy::needless_range_loop,
            reason = "j indexes two parallel collections"
        )]
        for j in 0..nr {
            let ranks: Vec<usize> = valid
                .iter()
                .map(|(_, _, _, evald, _)| {
                    let mut med: Vec<(usize, f64)> = (0..nr)
                        .map(|k| {
                            (
                                k,
                                pctile(
                                    &evald.iter().map(|v| get(&v[k].0)).collect::<Vec<f64>>(),
                                    0.5,
                                ),
                            )
                        })
                        .collect();
                    // Scala's sortBy(-_._2): descending, and STABLE, so ties keep rule order
                    med.sort_by(|a, b| (-a.1).total_cmp(&(-b.1)));
                    med.iter().position(|(k, _)| *k == j).unwrap_or(0) + 1
                })
                .collect();
            let cells: Vec<String> = ranks.iter().map(|r| format!("{r:>2}")).collect();
            println!(
                "  {:<34} {}   best {}  worst {}",
                rs[j].name,
                cells.join(" "),
                ranks.iter().min().copied().unwrap_or(0),
                ranks.iter().max().copied().unwrap_or(0)
            );
        }
    }

    // ---- reflexivity: the qualifier the character ranks carry, made visible ---------------
    if !single {
        println!(
            "\n\nREFLEXIVITY: {} of {} reflexive worlds pass the gate.",
            reflexive.len(),
            n_refl
        );
        println!(
            "The ranks above hold the crowd fixed and non-reactive.  These worlds hand the crowd a"
        );
        println!(
            "rule to run, so its de-risking moves the price it reacts to: they change WHO IS TRADING"
        );
        println!(
            "rather than the market's character.  They are NOT pooled with the ranks above, and the"
        );
        println!("flight-to-safety and refuge tables below exclude them for the same reason.");
        if reflexive.is_empty() {
            println!(
                "\n  no reflexive world passes the required gate classes; the qualifier stands untested."
            );
        } else {
            let metrics2: Vec<OutcomeMetric> = vec![
                ("median net return", |o: &Outcome| o.ann),
                ("median GROSS edge vs the fixed twin", |o: &Outcome| {
                    o.vs_flat_g
                }),
            ];
            let rank_in = |set: &[Row], j: usize, get: fn(&Outcome) -> f64| -> Vec<usize> {
                set.iter()
                    .map(|(_, _, _, evald, _)| {
                        let mut med: Vec<(usize, f64)> = (0..nr)
                            .map(|k| {
                                (
                                    k,
                                    pctile(
                                        &evald.iter().map(|v| get(&v[k].0)).collect::<Vec<f64>>(),
                                        0.5,
                                    ),
                                )
                            })
                            .collect();
                        med.sort_by(|a, b| (-a.1).total_cmp(&(-b.1)));
                        med.iter().position(|(k, _)| *k == j).unwrap_or(0) + 1
                    })
                    .collect()
            };
            for (metric_name, get) in metrics2 {
                let names: Vec<&str> = reflexive.iter().map(|t| t.0).collect();
                println!(
                    "\n  ranked by {metric_name}   (1 = best)   {}",
                    names.join(" | ")
                );
                #[expect(
                    clippy::needless_range_loop,
                    reason = "j indexes two parallel collections"
                )]
                for j in 0..nr {
                    let ranks = rank_in(&reflexive, j, get);
                    let chr = rank_in(&valid, j, get);
                    let cmin = chr.iter().min().copied().unwrap_or(0);
                    let cmax = chr.iter().max().copied().unwrap_or(0);
                    // ANY reflexive world outside the character range is the finding, not all of
                    // them. The two reflexive worlds vary different axes and routinely disagree — a
                    // vol-scaling crowd ranks trend rules last where a pressed momentum crowd ranks
                    // them first — so a test requiring the whole reflexive SPAN to clear the range
                    // flagged nothing in exactly the case worth flagging.
                    let inverts = !chr.is_empty() && ranks.iter().any(|&r| r < cmin || r > cmax);
                    let cells: Vec<String> = ranks.iter().map(|r| format!("{r:>2}")).collect();
                    println!(
                        "  {:<34} {}   character {}-{}{}",
                        rs[j].name,
                        cells.join(" "),
                        cmin,
                        cmax,
                        if inverts {
                            "   <-- MOVES OUTSIDE THE CHARACTER RANGE"
                        } else {
                            ""
                        }
                    );
                }
            }
        }
    }

    // ---- flight to safety, DECOMPOSED so carry cannot masquerade as timing ----------------
    //   total  = bond-refuge net return minus cash-refuge net return
    //   static = what a CONSTANT mix at the same average exposure gains just from holding bonds
    //   timing = the change in the rule's edge over its own constant twin when the twin also
    //            holds bonds — the only part attributable to timed flight
    let pooled: Vec<&PathOutcomes> = valid.iter().flat_map(|(_, _, _, e, _)| e.iter()).collect();
    println!(
        "\nFLIGHT TO SAFETY — de-risking into BONDS instead of cash, pooled over the market-like"
    );
    println!(
        "worlds.  Return columns are net pp/yr and DEFLATOR-INVARIANT (the same inflation cancels"
    );
    println!(
        "from both sides).  What real grading adds is the WITHDRAWAL column: dSwr = paired median"
    );
    println!(
        "change in the 30-year sustainable REAL withdrawal rate from choosing the bond refuge —"
    );
    println!(
        "the cash-vehicle decision metric, and the axis on which 1970s-style bonds look worst."
    );
    println!(
        "  {:<34} {:>7} {:>8} {:>8} {:>9} {:>9} {:>7}",
        "rule", "total", "static", "timing", "swr cash", "swr bond", "dSwr"
    );
    for j in 0..nr {
        let tot: Vec<f64> = pooled
            .iter()
            .map(|v| v[nr + j].0.ann - v[j].0.ann)
            .collect();
        let sta: Vec<f64> = pooled
            .iter()
            .map(|v| (v[nr + j].0.ann - v[nr + j].0.vs_flat) - (v[j].0.ann - v[j].0.vs_flat))
            .collect();
        let tim: Vec<f64> = pooled
            .iter()
            .map(|v| v[nr + j].0.vs_flat - v[j].0.vs_flat)
            .collect();
        let sw_c: Vec<f64> = pooled
            .iter()
            .map(|v| v[j].0.swr)
            .filter(|x| !x.is_nan())
            .collect();
        let sw_b: Vec<f64> = pooled
            .iter()
            .map(|v| v[nr + j].0.swr)
            .filter(|x| !x.is_nan())
            .collect();
        let d_sw: Vec<f64> = pooled
            .iter()
            .map(|v| v[nr + j].0.swr - v[j].0.swr)
            .filter(|x| !x.is_nan())
            .collect();
        println!(
            "  {:<34} {} {} {} {} {} {}",
            rs[j].name,
            jfsw(pctile(&tot, 0.5), 7, 2),
            jfsw(pctile(&sta, 0.5), 8, 2),
            jfsw(pctile(&tim, 0.5), 8, 2),
            jf(pctile(&sw_c, 0.5), 9, 2),
            jf(pctile(&sw_b, 0.5), 9, 2),
            jfsw(pctile(&d_sw, 0.5), 7, 2)
        );
    }

    // ---- refuge severity curve: the conclusion as a CURVE, not a point --------------------
    println!(
        "\nREFUGE SEVERITY CURVE — the same decomposition as inflation severity is dialed; where"
    );
    println!("the timing column crosses zero is where timed flight stops paying.  Baseline world");
    println!("otherwise; severity multiplies inflSize.");
    println!(
        "  {:<9} {:<34} {:>7} {:>8} {:>8} {:>7} {:>16}",
        "severity", "rule", "total", "static", "timing", "dSwr", "infl-crash bond"
    );
    for mult in [0.5f64, 1.0, 1.5, 2.5] {
        let mut w = *base;
        w.infl_size = base.infl_size * mult;
        let sims = sim_paths(&w, paths.min(120), years, seed);
        let st = measure(&sims, years);
        // gated AT USE TIME, like every other conclusion path
        let ok_sev = gate_ok(a, &st, gate_req);
        let ev: Vec<Vec<Outcome>> = (0..sims.len())
            .into_par_iter()
            .map(|k| {
                let s = &sims[k];
                let ind = Indicators::new(&s.price);
                let eps = episodes(&s.price, 15.0);
                let fl: Vec<bool> = eps.iter().map(|ep| fundamental_led(s, *ep)).collect();
                let mut out = Vec::new();
                for j in [REF_IDX, nr - 1] {
                    out.push(
                        evaluate(s, &eps, &fl, &rs[j].expose, &ind, cost, years, Safe::Cash).0,
                    );
                    out.push(
                        evaluate(s, &eps, &fl, &rs[j].expose, &ind, cost, years, Safe::Bond).0,
                    );
                }
                out
            })
            .collect();
        for (j, off) in [(REF_IDX, 0usize), (nr - 1, 2usize)] {
            let tot: Vec<f64> = ev.iter().map(|v| v[off + 1].ann - v[off].ann).collect();
            let sta: Vec<f64> = ev
                .iter()
                .map(|v| (v[off + 1].ann - v[off + 1].vs_flat) - (v[off].ann - v[off].vs_flat))
                .collect();
            let tim: Vec<f64> = ev
                .iter()
                .map(|v| v[off + 1].vs_flat - v[off].vs_flat)
                .collect();
            let d_sw: Vec<f64> = ev
                .iter()
                .map(|v| v[off + 1].swr - v[off].swr)
                .filter(|x| !x.is_nan())
                .collect();
            println!(
                "  x{} {:<34} {} {} {} {} {}{}",
                jfl(mult, 8, 1),
                rs[j].name,
                jfsw(pctile(&tot, 0.5), 7, 2),
                jfsw(pctile(&sta, 0.5), 8, 2),
                jfsw(pctile(&tim, 0.5), 8, 2),
                jfsw(pctile(&d_sw, 0.5), 7, 2),
                jfsw(st.bond_infl, 15, 1),
                if ok_sev { "" } else { "   *** OUT OF GATE ***" }
            );
        }
    }

    // ---- cost breakeven ------------------------------------------------------------------
    println!(
        "\nCOST BREAKEVEN — the calm-market per-unit cost at which the rule's gross edge over its"
    );
    println!(
        "fixed twin reaches zero; liquidity-weighted churn in the denominator.  The flat-rate"
    );
    println!("column is what a constant fee would have implied.");
    println!(
        "  {:<34} {:>12} {:>9} {:>11}",
        "rule", "breakeven", "5th pct", "flat-rate"
    );
    for j in 0..nr {
        let os: Vec<Outcome> = pooled
            .iter()
            .map(|v| v[j].0)
            .filter(|o| o.churn > 0.05)
            .collect();
        if os.is_empty() {
            println!("  {:<34} (does not trade)", rs[j].name);
        } else {
            let be: Vec<f64> = os
                .iter()
                .map(|o| o.vs_flat_g * 100.0 / o.eff_churn)
                .collect();
            let flat: Vec<f64> = os.iter().map(|o| o.vs_flat_g * 100.0 / o.churn).collect();
            println!(
                "  {:<34} {} bp {} bp {} bp",
                rs[j].name,
                jf(pctile(&be, 0.5), 9, 0),
                jf(pctile(&be, 0.05), 7, 0),
                jf(pctile(&flat, 0.5), 9, 0)
            );
        }
    }

    // ---- crash-type decomposition --------------------------------------------------------
    println!(
        "\nCRASH TYPES — rule return minus buy-and-hold over each crash window, by whether the"
    );
    println!("fundamental fell at least half as far as price.  Log points x 100.");
    for j in 0..nr {
        let entries: Vec<(bool, f64, f64)> =
            pooled.iter().flat_map(|v| v[j].1.iter().copied()).collect();
        let fl: Vec<f64> = entries
            .iter()
            .filter(|e| e.0)
            .map(|e| (e.1 - e.2) * 100.0)
            .collect();
        let ll: Vec<f64> = entries
            .iter()
            .filter(|e| !e.0)
            .map(|e| (e.1 - e.2) * 100.0)
            .collect();
        println!(
            "  {:<34} fund-led {} (n={})   liq-led {} (n={})",
            rs[j].name,
            jfsw(pctile(&fl, 0.5), 7, 1),
            fl.len(),
            jfsw(pctile(&ll, 0.5), 7, 1),
            ll.len()
        );
    }
}

/// Per contrast, per statistic: `(hit rate, n*)`.
type PowerTable = Vec<Vec<(f64, f64)>>;

// ---- the power report -------------------------------------------------------------------

/// Every fidelity ratio at every published default, plus the world this invocation describes.
/// Exists because the natural comparison — candidate against its immediate predecessor — is
/// exactly the reading under which a sequence of individually-acceptable trades accumulates with
/// nothing ever showing it. The `worse than best` column is the accumulation detector.
fn run_release_report(a: Anchors, paths: usize, years: usize, seed: u64, base: &World) {
    let mut cols: Vec<(&str, World)> = releases();
    cols.push(("current", *base));
    eprintln!("{} worlds x {paths} paths x {years} years", cols.len());
    let stats: Vec<(&str, WorldStats)> = cols
        .iter()
        .map(|(v, w)| (*v, measure(&sim_paths(w, paths, years, seed), years)))
        .collect();
    println!(
        "CROSS-RELEASE FIDELITY — every target at every published default, and at the world this"
    );
    println!(
        "invocation describes.  The WORLDS are historical; the MEASUREMENT is current, so this shows"
    );
    println!(
        "how the DEFAULT has moved, not what each version reported — the mechanism moved too.  A"
    );
    println!("World field added after a release -- or REMOVED by a mechanism change, as 0.19.2's");
    println!("rate cut was -- takes today's value in that release's row.");
    if !std::ptr::eq(a.name, SP500_ANCHORS.name) {
        println!();
        println!(
            "  NOTE: every frozen release world was calibrated against the S&P set; this run grades"
        );
        println!(
            "  them with {} anchors.  The columns are still comparable to EACH OTHER, but a row's",
            a.name
        );
        println!(
            "  distance from 1.00 is not a defect of that release -- it was never fitted here."
        );
    }
    println!();
    let mut hdr = format!("  {:<22}", "target");
    for (v, _) in &cols {
        hdr.push_str(&format!("{v:>8}"));
    }
    println!("{hdr}   {:>7}   worse than best", "best");
    let mut best_total = 0.0f64;
    for (name, get, want, _) in fit_targets(a) {
        let rs: Vec<f64> = stats.iter().map(|(_, st)| get(st) / want).collect();
        let errs: Vec<f64> = rs.iter().map(|r| (r - 1.0).abs()).collect();
        let cur = errs[errs.len() - 1];
        let mut best_idx = 0usize;
        for i in 1..errs.len() {
            if errs[i] < errs[best_idx] {
                best_idx = i;
            }
        }
        best_total += errs[best_idx];
        let flag = if best_idx != errs.len() - 1 && errs[best_idx] < cur - 0.005 {
            format!("<-- {} was {}", cols[best_idx].0, jf(rs[best_idx], 0, 2))
        } else {
            String::new()
        };
        let mut line = format!("  {name:<22}");
        for r in &rs {
            line.push_str(&format!("{:>8}", jf(*r, 0, 2)));
        }
        println!("{line}   {:>7}   {flag}", jf(rs[best_idx], 0, 2));
    }
    println!();
    let mut agg = format!("  {:<22}", "AGGREGATE |ratio-1|");
    for (_, st) in &stats {
        let t: f64 = fit_targets(a)
            .iter()
            .map(|(_, get, want, _)| (get(st) / want - 1.0).abs())
            .sum();
        agg.push_str(&format!("{:>8}", jf(t, 0, 2)));
    }
    println!(
        "{agg}   {:>7}   best achievable per row, across all releases",
        jf(best_total, 0, 2)
    );
    println!();
    println!(
        "  A flagged row is one where some published default read CLOSER to real than the current"
    );
    println!(
        "  world does.  That is not automatically wrong — a trade may have been worth making — but"
    );
    println!("  it is the thing no predecessor-only comparison can show.");
    // Kept as a ratio here, and ONLY here, because every column shares one ensemble size: the
    // divergence that makes the level meaningless cancels in a world-to-world comparison, so the
    // MOVEMENT across columns is real even though no column's value is a fidelity judgement.
    // `-validate` reports these rows as a percentile; a reader who carries a level across from this
    // table to that one is comparing two different things.
    println!();
    println!(
        "  ROWS THAT ARE NOT FIDELITY RATIOS: {}.",
        EXTREME_TARGETS.join(", ")
    );
    println!(
        "  These are extremes over the pooled ensemble, so the LEVEL grades the ensemble size —"
    );
    println!(
        "  read them across columns (which world is deeper), never against 1.00.  The AGGREGATE"
    );
    println!(
        "  row includes them, and is the old equal-measurability objective's opinion regardless."
    );
}

// ---- the cross-asset report -------------------------------------------------------------

/// Duration rungs, in years. MEASURED, never fitted: a rung moves when a real fund's duration is
/// re-measured or a new anchor is added, never to make a cell pass. A ladder whose rungs are
/// chosen after seeing the results is not a test of anything.
///
/// 1.80 is the short end of the five iShares Treasury funds the bond relations were fitted across
/// (SHY, IEI, IEF, TLH, TLT — see `SIGMA_N_BOND`), 13.50 is `DURATION_REF` so one rung is the world
/// every other report describes, and 5.70 is the Aggregate-like intermediate recorded as admissible
/// at 0.19.2. 25.00 is DELIBERATELY past the fund span: a ladder whose every rung sits inside the
/// fitted range cannot show the difference between agreeing with the data and extrapolating it.
const DURATION_LADDER: [f64; 4] = [1.80, 5.70, 13.50, 25.00];

/// A scale-free relation: a band measured across real funds, plus the range of the driving
/// variable those funds actually covered.
///
/// `support` is what makes the ladder a test rather than an assertion. Both bands come from
/// fitting a line across a handful of funds, and outside the range they covered the line is
/// arithmetic with nothing behind it. A cell there is disclosed, never scored — grading it would
/// manufacture agreement or manufacture a defect, and there is no way to tell which.
struct Relation {
    name: &'static str,
    /// The graded, scale-free quantity. Scale-free is why it can cross assets at all: a level
    /// target is a statement about ONE fund, a ratio is a statement about the mechanism.
    get: fn(&WorldStats) -> f64,
    lo: f64,
    hi: f64,
    /// What `support` is expressed in. Not the same variable for both relations, which is why it
    /// is carried per relation rather than assumed.
    driver: &'static str,
    /// The driving variable at a rung. Takes the duration too, because one relation's driver is a
    /// world parameter rather than a measured statistic.
    driver_of: fn(&WorldStats, f64) -> f64,
    support: (f64, f64),
}

/// Why a cell is or is not graded. The two ungraded cases are NOT the same finding and must not
/// print alike: `Extrap` says the ladder went past the funds, `Undefined` says the relation has no
/// value to compare at a rung the funds do cover. The second is a statement about the RELATION —
/// the depth line predicts non-positive time-under-water below ~1.98% volatility, so its usable
/// range is narrower than the range it was fitted across, and no ladder can widen it.
enum Cell {
    Graded(bool),
    /// Within one sampling sd of a band edge, on EITHER side. A hard verdict there is a
    /// seed draw wearing a verdict's clothes — measured: the d=5.70 depth cell flips
    /// PASS/FAIL across seeds at 200 paths under both the 0.19.2 and 0.20.0 defaults,
    /// because the world genuinely sits at the band floor. EDGE says "not resolvable at
    /// this ensemble size" instead of resolving it by luck.
    Edge,
    Extrap,
    Undefined,
}

impl Relation {
    /// `sd` is this cell's own sampling noise, estimated by the caller from quarter-ensemble
    /// spread; NaN disables the EDGE test (tiny ensembles), leaving the hard threshold.
    fn grade(&self, st: &WorldStats, dur: f64, sd: f64) -> Cell {
        let d = (self.driver_of)(st, dur);
        // Boundary counts as inside: the support's endpoints are fund readings, not a gap.
        if d < self.support.0 || d > self.support.1 {
            return Cell::Extrap;
        }
        let v = (self.get)(st);
        if v.is_nan() {
            Cell::Undefined
        } else if !sd.is_nan() && ((v - self.lo).abs() <= sd || (v - self.hi).abs() <= sd) {
            Cell::Edge
        } else {
            Cell::Graded(v > self.lo && v < self.hi)
        }
    }
}

/// Verdict for the ladder. A relation that graded nothing was not tested, and "the test did not
/// run" must not print as the test passing — a PASS resting on zero cells is the vacuous fixture
/// this repo has been burned by before. An in-support miss outranks empty coverage. Pure, so the
/// three-way branch is testable without running an ensemble.
fn cross_asset_verdict(
    outside_band: usize,
    edge: usize,
    rel_graded: &[(&str, usize)],
) -> (&'static str, bool) {
    if outside_band > 0 {
        ("FAIL", false)
    } else if rel_graded.iter().any(|(_, g)| *g == 0) {
        ("INCONCLUSIVE", false)
    } else if edge > 0 {
        ("EDGE", false)
    } else {
        ("PASS", true)
    }
}

/// The two bond relations that already carry a real-fund band, and only those. Every other fidelity
/// target is a level calibrated to a single fund — grading those here would re-assert TLT's numbers
/// at four durations and call the agreement evidence.
fn bond_relations() -> [Relation; 2] {
    [
        Relation {
            name: "bond vol x duration",
            get: WorldStats::bond_vol_per_year,
            lo: BOND_VOL_PER_YEAR_BAND.0,
            hi: BOND_VOL_PER_YEAR_BAND.1,
            driver: "duration y",
            driver_of: |_, dur| dur,
            support: BOND_DUR_SUPPORT,
        },
        Relation {
            name: "bond depth vs vol",
            get: WorldStats::bond_depth_vs_vol,
            lo: BOND_D10_BAND.0,
            hi: BOND_D10_BAND.1,
            driver: "bond vol %",
            driver_of: |st, _| st.bond_vol * 100.0,
            support: BOND_VOL_SUPPORT,
        },
    ]
}

/// The equity-leg fidelity targets, in report order. Membership is a DECISION, not a derivation:
/// the partition test requires every fidelity target to be classified as equity or bond, so a
/// target added or renamed fails the build until someone places it. The failure being prevented is
/// a target silently absent from the equity section — a shorter table reads as a shorter list of
/// concerns, not as a bug.
const EQUITY_TARGETS: [&str; 18] = [
    "equity vol %",
    "typical-year vol %",
    "return per vol",
    "kurtosis",
    "clustering lag 1",
    "clustering lag 20",
    "variance ratio 60d",
    "downside vol excess %",
    "leverage corr",
    "valuation dispersion",
    "upper wing months %",
    "lower wing months %",
    "crashes/century",
    "median depth %",
    "worst crash %",
    "equity d5 vs real",
    "equity d10 vs real",
    "equity d20 vs real",
];

/// The other half of the partition. Read only by the partition test — the report has no bond
/// section to drive; the list exists so a new fidelity target cannot land unclassified.
#[cfg_attr(
    not(test),
    expect(
        dead_code,
        reason = "the partition contract is read by the tests, never by a report"
    )
)]
const BOND_TARGETS: [&str; 5] = [
    "bond vol % (24y)",
    "bond growth-crash",
    "bond infl-crash",
    "bond depth vs vol",
    "tail hedge corr",
];

/// Bisection bracket for the depth solve, and how many halvings. Ten steps over this bracket
/// leaves the depth uncertain by 21/1024 ~ 0.021, worth about 0.03 points of volatility — far
/// inside the sampling noise of any ensemble that could be run here. Each step is a full ensemble,
/// so this is the cost knob: twelve ensembles in total, including the two bracket probes.
///
/// The low end reaches BELOW the Nasdaq recipe's own `depth 10`: volatility falls as depth rises,
/// so a 26.9%-volatility anchor needs a thinner market than that recipe runs, and a bracket
/// starting at 10.0 refused the solve outright — the equity-at-anchor section simply declined for
/// every Nasdaq world.
const DEPTH_BRACKET: (f64, f64) = (5.0, 26.0);
const VOL_SOLVE_STEPS: usize = 10;

/// Solve `depth` for a target equity volatility. Volatility DECREASES with depth (impact scales as
/// `12/depth`), so the low end of the bracket is the high-volatility end.
///
/// `None` where the bracket cannot reach the target — refused rather than clamped, for the same
/// reason a cell outside a band's support is refused rather than graded: an endpoint returned as
/// if it were a solution would put every row below it at a volatility nobody asked for.
fn depth_for_vol(base: &World, target: f64, paths: usize, years: usize, seed: u64) -> Option<f64> {
    let vol_at = |d: f64| {
        let mut w = *base;
        w.depth = d;
        measure(&sim_paths(&w, paths, years, seed), years).vol * 100.0
    };
    let (mut lo, mut hi) = DEPTH_BRACKET;
    if vol_at(lo) < target || vol_at(hi) > target {
        return None;
    }
    for _ in 0..VOL_SOLVE_STEPS {
        let mid = (lo + hi) / 2.0;
        if vol_at(mid) > target {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    Some((lo + hi) / 2.0)
}

/// Every equity target re-read with volatility put ON its anchor.
///
/// `depth` moves volatility and drawdown together, so grading a drawdown statistic while the model
/// sits 10% below its own volatility anchor mixes two errors and reports one. This section removes
/// the volatility miss and shows what the others then read: one identity parameter, set from one
/// measured statistic, nothing else touched.
///
/// DIAGNOSTIC ONLY — it does not touch the exit code. The equity leg has no cross-index bands yet
/// (rung 2b), so there is nothing here to pass or fail against; what it has is a pair of ratios and
/// the difference between them.
fn run_equity_at_anchor(a: Anchors, paths: usize, years: usize, seed: u64, base: &World) {
    let target = fit_targets(a)
        .into_iter()
        .find(|(n, _, _, _)| *n == "equity vol %")
        .map_or_else(
            || cli_die("no `equity vol %` fidelity target to anchor volatility on"),
            |(_, _, want, _)| want,
        );
    println!();
    println!(
        "EQUITY — every equity target re-read with volatility ON ITS ANCHOR.  Diagnostic: this"
    );
    println!("section does not affect the exit code.");
    println!();
    println!(
        "A LEVEL read while the model sits below its own volatility anchor mixes two errors and"
    );
    println!(
        "reports one.  Here depth is solved so volatility sits on the anchor and every equity target"
    );
    println!(
        "is re-read: 1 identity parameter, set from 1 measured statistic, nothing else touched."
    );
    println!();
    println!("The three depth rungs are graded against a relation evaluated at each world's OWN");
    println!(
        "volatility and return, so they should read ALIKE in both columns -- solving depth moves"
    );
    println!(
        "their prediction with their measurement.  A rung that moves anyway is reporting that the"
    );
    println!("model and the real cross-section disagree about how time under water responds to");
    println!("volatility, which is the one thing this pair of columns can still show about them.");
    println!();
    let Some(solved) = depth_for_vol(base, target, paths, years, seed) else {
        println!(
            "  cannot reach {} % volatility with depth in {}-{}: this world's volatility is set by",
            jf(target, 0, 2),
            jf(DEPTH_BRACKET.0, 0, 1),
            jf(DEPTH_BRACKET.1, 0, 1)
        );
        println!("  something other than depth, and the section has nothing to say about it.");
        return;
    };
    let mut w = *base;
    w.depth = solved;
    let st_def = measure(&sim_paths(base, paths, years, seed), years);
    let st_anc = measure(&sim_paths(&w, paths, years, seed), years);
    println!(
        "  solved: depth {} gives the anchored volatility (bisection, {VOL_SOLVE_STEPS} steps over \
         depth {}-{}); the world's own depth is {}",
        jf(solved, 0, 2),
        jf(DEPTH_BRACKET.0, 0, 1),
        jf(DEPTH_BRACKET.1, 0, 1),
        jf(base.depth, 0, 2)
    );
    println!();
    println!(
        "  {:<22}{:>10}{:>11}{:>10}{:>11}{:>11}",
        "statistic", "default", "at anchor", "real", "ratio def", "ratio anc"
    );
    for name in EQUITY_TARGETS {
        let Some((_, get, want, _)) = fit_targets(a).into_iter().find(|(n, _, _, _)| *n == name)
        else {
            cli_die(&format!(
                "EQUITY_TARGETS names [{name}], which is not a fidelity target"
            ));
        };
        let (d, a) = (get(&st_def), get(&st_anc));
        let (rd, ra) = (d / want, a / want);
        // The point of the section: the rows where putting volatility on its anchor CHANGES the
        // verdict. A row that reads the same either way was never distorted by the miss. Judge a
        // flagged move against `-noise`'s seed-noise section before reading it as real; the two
        // columns share one seed, so 2 sd there is the conservative bound on this difference.
        let flag = if (ra - rd).abs() > 0.05 {
            format!("<-- moves {}", jf(ra - rd, 0, 2))
        } else {
            String::new()
        };
        // Both columns share one ensemble size, so the MOVE is readable on every row; the LEVEL is
        // not, on the extremes — see the note below and `-validate`'s percentile.
        let kind = if EXTREME_TARGETS.contains(&name) {
            " *"
        } else {
            ""
        };
        println!(
            "  {name:<22}{:>10}{:>11}{:>10}{:>11}{:>11}   {flag}{kind}",
            jf(d, 0, 2),
            jf(a, 0, 2),
            jf(want, 0, 2),
            jf(rd, 0, 2),
            jf(ra, 0, 2)
        );
    }
    if EQUITY_TARGETS.iter().any(|n| EXTREME_TARGETS.contains(n)) {
        println!();
        println!(
            "  * an extreme over the pooled ensemble, not a per-path value: the MOVE between the two"
        );
        println!(
            "    columns is real, the LEVEL grades the ensemble size.  -validate reports it as a"
        );
        println!("    percentile among single histories instead; do not carry a level across.");
    }
}

fn cross_asset_preamble() {
    println!(
        "CROSS-ASSET — ONE mechanism across the duration ladder.  1 identity parameter: 0 FITTED,"
    );
    println!(
        "1 MEASURED.  Every mechanism parameter is frozen at the world this invocation describes;"
    );
    println!(
        "only `duration` moves, and it moves to values real funds have.  That is the whole test: a"
    );
    println!("band holds across the ladder, or the mechanism is duration-specific.");
    println!();
    println!(
        "Each band was fitted across real funds, so each carries a SUPPORT — the range of its own"
    );
    println!(
        "driving variable those funds covered.  A rung outside it reads EXTRAP: disclosed, and"
    );
    println!(
        "excluded from the verdict, because a line evaluated past its data can neither pass nor"
    );
    println!("fail honestly.");
    println!();
    println!(
        "WHAT THIS DOES NOT PROVE.  The bands were fitted on Treasury funds and these are Treasury"
    );
    println!(
        "durations, so this is a CONSISTENCY check: it cannot detect a mechanism that is wrong in a"
    );
    println!("way every Treasury shares.  That needs an asset class the bands did not come from.");
    println!();
}

/// One mechanism, every rung of the duration ladder, graded only where the bands have data.
#[expect(
    clippy::too_many_lines,
    reason = "one linear report, mirroring the Scala twin statement for statement"
)]
fn run_cross_asset_report(a: Anchors, paths: usize, years: usize, seed: u64, base: &World) -> bool {
    eprintln!(
        "{} durations x {paths} paths x {years} years",
        DURATION_LADDER.len()
    );
    // Per rung: the full-ensemble reading, plus four quarter-ensemble readings for the
    // in-run noise estimate. The quarters reuse the SAME simulated paths — the estimate
    // costs four extra measure() calls and zero extra simulation. sd(full) is taken as
    // sd(quarters)/2; approximate for a median-based statistic, and stated as an estimate.
    let stats: Vec<(f64, WorldStats, Vec<WorldStats>)> = DURATION_LADDER
        .iter()
        .map(|d| {
            let mut w = *base;
            w.duration = *d;
            let sims = sim_paths(&w, paths, years, seed);
            let quarters: Vec<WorldStats> = if paths >= 8 {
                let g = paths / 4;
                (0..4)
                    .map(|k| measure(&sims[k * g..(k + 1) * g], years))
                    .collect()
            } else {
                Vec::new()
            };
            (*d, measure(&sims, years), quarters)
        })
        .collect();
    cross_asset_preamble();
    let mut hdr = format!("  {:<22}", "relation");
    for (d, _, _) in &stats {
        hdr.push_str(&format!("{:>10}", format!("d={}", jf(*d, 0, 2))));
    }
    println!("{hdr}   band          support");
    let mut graded = 0usize;
    let mut extrap = 0usize;
    let mut undef = 0usize;
    let mut edge = 0usize;
    let mut failed: Vec<String> = Vec::new();
    let mut edges: Vec<String> = Vec::new();
    let mut rel_graded: Vec<(&'static str, usize)> = Vec::new();
    for rel in bond_relations() {
        let mut line = format!("  {:<22}", rel.name);
        let mut mine = 0usize;
        for (d, st, quarters) in &stats {
            let qs: Vec<f64> = quarters
                .iter()
                .map(|q| (rel.get)(q))
                .filter(|x| !x.is_nan())
                .collect();
            let sd = if qs.len() >= 2 {
                let mean = scala_sum(qs.iter().copied()) / qs.len() as f64;
                (scala_sum(qs.iter().map(|x| (x - mean) * (x - mean))) / (qs.len() - 1) as f64)
                    .sqrt()
                    / (qs.len() as f64).sqrt()
            } else {
                f64::NAN
            };
            let cell = match rel.grade(st, *d, sd) {
                Cell::Extrap => {
                    extrap += 1;
                    "EXTRAP".to_string()
                }
                Cell::Undefined => {
                    undef += 1;
                    "n/a".to_string()
                }
                Cell::Edge => {
                    graded += 1;
                    mine += 1;
                    edge += 1;
                    edges.push(format!(
                        "{} at d={} ({} within {} of the band)",
                        rel.name,
                        jf(*d, 0, 2),
                        jf((rel.get)(st), 0, 2),
                        jf(sd, 0, 2)
                    ));
                    format!("{}~", jf((rel.get)(st), 0, 2))
                }
                Cell::Graded(ok) => {
                    graded += 1;
                    mine += 1;
                    if !ok {
                        failed.push(format!("{} at d={}", rel.name, jf(*d, 0, 2)));
                    }
                    jf((rel.get)(st), 0, 2)
                }
            };
            line.push_str(&format!("{cell:>10}"));
        }
        rel_graded.push((rel.name, mine));
        println!(
            "{line}   {}-{}   {} {}-{}",
            jf(rel.lo, 0, 2),
            jf(rel.hi, 0, 2),
            rel.driver,
            jf(rel.support.0, 0, 2),
            jf(rel.support.1, 0, 2)
        );
    }
    // The drivers themselves, ungraded: without them a EXTRAP cell says only "out of range" and
    // not how far out, which is the difference between a near miss and a different asset.
    let mut drv = format!("  {:<22}", "(bond vol %)");
    for (_, st, _) in &stats {
        drv.push_str(&format!("{:>10}", jf(st.bond_vol * 100.0, 0, 2)));
    }
    println!();
    println!("{drv}   driver of the depth relation, ungraded");
    println!();
    let (word, ok) = cross_asset_verdict(failed.len(), edge, &rel_graded);
    println!(
        "  verdict: {word}  — {graded} graded, {} outside band, {edge} at edge, {extrap} EXTRAP, {undef} n/a",
        failed.len()
    );
    if !failed.is_empty() {
        println!("    outside: {}", failed.join(", "));
    }
    if !edges.is_empty() {
        println!("    edge (~): {}", edges.join(", "));
        println!(
            "    a ~ cell sits within one estimated sampling sd of a band edge, on either side: the"
        );
        println!(
            "    verdict cannot resolve it at this ensemble size, and a hard PASS or FAIL there"
        );
        println!("    would be a seed draw wearing a verdict's clothes.");
    }
    if word == "INCONCLUSIVE" {
        let empty: Vec<&str> = rel_graded
            .iter()
            .filter(|(_, g)| *g == 0)
            .map(|(n, _)| *n)
            .collect();
        println!(
            "    INCONCLUSIVE: [{}] graded ZERO cells — every rung EXTRAP or n/a — so the ladder",
            empty.join(", ")
        );
        println!("    tested nothing for it, and \"the test did not run\" must not print as PASS.");
    }
    if undef > 0 {
        println!(
            "    n/a = the relation has no value at that rung, INSIDE its support: the depth line"
        );
        println!(
            "    predicts non-positive time-under-water below {} % volatility, so its usable range",
            jf(BOND_D10_ZERO, 0, 2)
        );
        println!(
            "    is narrower than the range it was fitted across.  A property of the relation, not"
        );
        println!("    of this ladder — widening the ladder cannot reach those rungs.");
    }
    run_equity_at_anchor(a, paths, years, seed, base);
    ok
}

// ---- the anchor-noise report ------------------------------------------------------------

/// Each fidelity anchor's own measurement horizon, in years, and the targets read over it. The
/// windows are the ones the fidelity header names — S&P/CRSP 1954-2026, the CRSP century for
/// clustering, the equity funds for the depth rungs, the clean 24-year TLT series for the bond —
/// because sampling error depends on the length of the record actually behind each number, not
/// on the horizon the model is scored at. The contract test pins this to `fit_targets` as a
/// partition, so a new target cannot land without a declared horizon.
fn anchor_groups(a: Anchors) -> [(&'static str, usize, &'static [&'static str]); 7] {
    [
        (
            a.equity_window,
            a.equity_years,
            &[
                "equity vol %",
                "typical-year vol %",
                "return per vol",
                "kurtosis",
                "crashes/century",
                "median depth %",
                "downside vol excess %",
                "leverage corr",
            ],
        ),
        (
            a.cluster_window,
            a.cluster_years,
            &["clustering lag 1", "clustering lag 20"],
        ),
        // Its own group because its own window — see `Anchors::tail_window`. For both shipped sets
        // this is the instrument's whole history, which is the only window that cannot have deleted
        // the deepest episode.
        (a.tail_window, a.tail_years, &["worst crash %"]),
        // The Shiller record is one series shared by every anchor set, at its own century horizon.
        (
            "Shiller CAPE 1881-2023",
            100,
            &[
                "valuation dispersion",
                "upper wing months %",
                "lower wing months %",
            ],
        ),
        // 18 equity funds and three CRSP windows, the shortest of them 24.9 years — see
        // `VAR_RATIO_BANDS`. The horizon is one instrument's record, as it is for the depth rungs, and
        // the target this group carries is a theory value rather than a reading, so `real@` here says
        // where 1.00 falls in the model's own spread of 25-year readings, not where a record does.
        ("equity funds + CRSP, 25y", 25, &["variance ratio 60d"]),
        // 35 equity funds over 2001-2026; the horizon is one instrument's record, because that is
        // what each residual ratio in the fit was measured from.
        (
            "equity funds, 25y",
            25,
            &[
                "equity d5 vs real",
                "equity d10 vs real",
                "equity d20 vs real",
            ],
        ),
        (
            "clean TLT, 24y",
            24,
            &[
                "bond vol % (24y)",
                "bond growth-crash",
                "bond infl-crash",
                "bond depth vs vol",
                "tail hedge corr",
            ],
        ),
    ]
}

/// Replicates for the seed-noise section, and the seed stride between them. 1_000_003 is not a
/// multiple of the 7919 path stride (1_000_003 mod 7919 = 2209), so within the replicate count
/// used here no path seed recurs across replicates.
const NOISE_REPLICATES: usize = 8;
const NOISE_SEED_STRIDE: u64 = 1_000_003;

/// What one history can pin down, per fidelity target — and what one seed can, per ensemble.
///
/// Every fidelity target is a POINT read from one historical record. Section 1 asks the model
/// what spread of readings independent histories of that anchor's own length produce, and where
/// the real record falls in that spread. Section 2 measures the seed-to-seed noise of the scoring
/// ensemble itself, which is what licenses reading a ratio difference — in `-releases`, or between
/// `-crossasset`'s equity columns — as a change rather than a draw.
///
/// MODEL-IMPLIED, and the circularity is stated in the report: the spreads come from this model's
/// own dynamics, so where the model is known biased the spread is too. There is no other
/// estimate — the record is one draw.
#[expect(
    clippy::too_many_lines,
    reason = "one linear report, mirroring the Scala twin statement for statement"
)]
fn run_noise_report(a: Anchors, paths: usize, seed: u64, base: &World) {
    println!(
        "ANCHOR NOISE — what one history can pin down.  Every fidelity target is a POINT read from"
    );
    println!(
        "one historical record; this report asks the model what spread of readings independent"
    );
    println!(
        "histories of that anchor's OWN length would produce, and where the real record falls."
    );
    println!();
    println!(
        "MODEL-IMPLIED, circularity stated: the spreads come from this model's own dynamics, so"
    );
    println!(
        "where the model is known biased (the deep drawdown rung, 1.7x real) the spread is too."
    );
    println!("There is no other estimate — the record is one draw.");
    println!();
    println!(
        "Read `real@` as the share of model histories at or below the real anchor: near 50% the"
    );
    println!("record is a typical history of this model, near 0/100% the model cannot produce");
    println!(
        "record-like histories on that statistic.  `sd/real` beside `wt` is the mis-weighting"
    );
    println!(
        "check: equal weight with unequal sd/real grades two targets as equally measurable, and"
    );
    println!("they are not.  `p50` vs `real` is the HORIZON-MATCHED reading; -fitness grades the");
    println!(
        "extreme rows on it (the median of these single histories), and the per-path rows on the"
    );
    println!("100-year scoring ensemble against these mixed-horizon anchors.");
    // Merged for the REPORT only, in first-appearance order: `anchor_groups` keeps one entry per
    // anchor because the windows are separate DECISIONS that happen to coincide in both shipped
    // sets, and printing one header and running one ensemble per distinct (window, horizon) is what
    // a reader wants from that. Merging the field would be the coupling; merging the display is not.
    let mut noise_groups: Vec<(&'static str, usize, Vec<&'static str>)> = Vec::new();
    for (label, years, names) in anchor_groups(a) {
        match noise_groups
            .iter_mut()
            .find(|(l, y, _)| *l == label && *y == years)
        {
            Some((_, _, acc)) => acc.extend_from_slice(names),
            None => noise_groups.push((label, years, names.to_vec())),
        }
    }
    for (label, years, targets) in noise_groups {
        eprintln!("{paths} paths x {years}y — {label}");
        let sims = sim_paths(base, paths, years, seed);
        let sts: Vec<WorldStats> = sims
            .iter()
            .map(|p| measure(std::slice::from_ref(p), years))
            .collect();
        println!();
        println!("  {label} — {years}-year single histories:");
        println!(
            "  {:<22}{:>8}{:>8}{:>8}{:>8}{:>7}{:>5}{:>8}{:>5}",
            "target", "real", "p5", "p50", "p95", "real@", "n", "sd/real", "wt"
        );
        for name in targets {
            let Some((_, get, want, weight)) =
                fit_targets(a).into_iter().find(|(n, _, _, _)| *n == name)
            else {
                cli_die(&format!(
                    "anchor group names [{name}], not a fidelity target"
                ));
            };
            let mut xs: Vec<f64> = sts.iter().map(get).filter(|x| !x.is_nan()).collect();
            xs.sort_by(f64::total_cmp);
            let n = xs.len();
            if n == 0 {
                println!(
                    "  {name:<22}{:>8}{:>8}{:>8}{:>8}{:>7}{n:>5}{:>8}{:>5}",
                    jf(want, 8, 2),
                    "n/a",
                    "n/a",
                    "n/a",
                    "-",
                    "n/a",
                    jf(weight, 5, 1)
                );
                continue;
            }
            let p = |q: usize| xs[(n - 1) * q / 100];
            let mean = scala_sum(xs.iter().copied()) / n as f64;
            let sd = if n > 1 {
                (scala_sum(xs.iter().map(|x| (x - mean) * (x - mean))) / (n - 1) as f64).sqrt()
            } else {
                f64::NAN
            };
            let ps = format!("{}%", anchor_pctile(&xs, want));
            println!(
                "  {name:<22}{:>8}{:>8}{:>8}{:>8}{ps:>7}{n:>5}{:>8}{:>5}",
                jf(want, 8, 2),
                jf(p(5), 8, 2),
                jf(p(50), 8, 2),
                jf(p(95), 8, 2),
                jf(sd / want.abs(), 8, 2),
                jf(weight, 5, 1)
            );
        }
    }
    eprintln!("{NOISE_REPLICATES} replicates x {paths} paths x 100y — seed noise");
    let reps: Vec<WorldStats> = (0..NOISE_REPLICATES)
        .map(|k| {
            measure(
                &sim_paths(base, paths, 100, seed + (k as u64 + 1) * NOISE_SEED_STRIDE),
                100,
            )
        })
        .collect();
    println!();
    println!(
        "  seed noise of the SCORING ensemble — {NOISE_REPLICATES} replicates of {paths} paths x \
         100 years.  -releases rows,"
    );
    println!(
        "  -crossasset's equity ratios and any candidate-vs-default comparison are readings of"
    );
    println!("  this configuration: a ratio difference below ~2 sd is a seed draw, not a change.");
    println!(
        "  (-crossasset's two equity columns share one seed, so their DIFFERENCE is less noisy"
    );
    println!("  than two independent readings; 2 sd is the conservative bound.)");
    println!();
    println!(
        "  {:<22}{:>11}{:>11}{:>11}",
        "target", "ratio mean", "ratio sd", "2 sd"
    );
    for (name, get, want, _) in fit_targets(a) {
        let rs: Vec<f64> = reps.iter().map(|st| get(st) / want).collect();
        let mean = scala_sum(rs.iter().copied()) / rs.len() as f64;
        let sd =
            (scala_sum(rs.iter().map(|x| (x - mean) * (x - mean))) / (rs.len() - 1) as f64).sqrt();
        println!(
            "  {name:<22}{:>11}{:>11}{:>11}",
            jf(mean, 11, 3),
            jf(sd, 11, 3),
            jf(2.0 * sd, 11, 3)
        );
    }
}

#[expect(
    clippy::too_many_lines,
    reason = "one linear report, mirroring the Scala twin statement for statement"
)]
#[expect(
    clippy::too_many_arguments,
    reason = "the parameter list mirrors the Scala twin's, and the twins are diffed"
)]
fn run_power_report(
    a: Anchors,
    paths: usize,
    seed: u64,
    cost: f64,
    single: bool,
    base: &World,
    gate_req: &[GateClass],
    arm_idx: &[usize],
    horizons: &[usize],
) {
    // Arms and horizons are the CALLER's, so the consumer's own question — these two arms, at the
    // length of history I possess — is answerable without a code change. The defaults reproduce
    // the report this had before it took either.
    let rs_all = rules();
    let focus: Vec<Rule> = arm_idx.iter().map(|i| rs_all[i - 1].clone()).collect();
    let mut arms: Vec<ExposeFn> = Vec::new();
    for r in &focus {
        arms.push(Arc::clone(&r.expose));
        let inner = Arc::clone(&r.expose);
        arms.push(Arc::new(move |i: &Indicators| matched_constant(&inner(i))) as ExposeFn);
    }
    arms.push(Arc::new(|ind: &Indicators| vec![1.0; ind.px.len()]) as ExposeFn);
    let always_idx = arms.len() - 1;
    let mut pairs: Vec<(String, usize, usize, bool)> = Vec::new();
    for (k, r) in focus.iter().enumerate() {
        pairs.push((
            format!("{}  vs its exposure-matched constant", r.name),
            2 * k,
            2 * k + 1,
            false,
        ));
        pairs.push((
            format!("{}  vs always fully invested", r.name),
            2 * k,
            always_idx,
            false,
        ));
    }
    pairs.push((
        format!("NULL — {}  vs ITSELF on an independent path", focus[0].name),
        0,
        0,
        true,
    ));

    let names = stat_names();

    // per contrast, per statistic: (hit rate, n*). Gate verdict travels with the numbers.
    let power = |w: &World, l: usize, sd: u64| -> (bool, PowerTable) {
        let sims = sim_paths(w, paths, l, sd);
        let ok = gate_ok(a, &measure(&sims, l), gate_req);
        let stats: Vec<Vec<Vec<f64>>> = (0..sims.len())
            .into_par_iter()
            .map(|k| {
                let p = &sims[k];
                let ind = Indicators::new(&p.price);
                arms.iter()
                    .map(|fna| {
                        grading_stats(&arm_path(p, &fna(&ind), cost, Safe::Cash), l)
                            .into_iter()
                            .map(|(_, v)| v)
                            .collect()
                    })
                    .collect()
            })
            .collect();
        let np = stats.len();
        let res = pairs
            .iter()
            .map(|(_, ia, ib, is_null)| {
                (0..names.len())
                    .map(|j| {
                        // the null pairs the first half of the paths against the second, giving
                        // genuinely independent differences; pairing every path with a shifted
                        // partner would force the hit rate to 50% ARITHMETICALLY
                        let d: Vec<f64> = if *is_null {
                            (0..np / 2)
                                .map(|k| stats[k][*ia][j] - stats[k + np / 2][*ib][j])
                                .filter(|x| !x.is_nan())
                                .collect()
                        } else {
                            (0..np)
                                .map(|k| stats[k][*ia][j] - stats[k][*ib][j])
                                .filter(|x| !x.is_nan())
                                .collect()
                        };
                        if d.len() < 8 {
                            return (f64::NAN, f64::NAN);
                        }
                        // truth from one half, hit rate scored on the OTHER: reading both off the
                        // same sample would grade the estimator against a target it helped define
                        let h = d.len() / 2;
                        let truth = scala_sum(d[..h].iter().copied()) / h as f64;
                        let test = &d[h..];
                        let hit = test
                            .iter()
                            .filter(|x| scala_sign(**x) == scala_sign(truth))
                            .count() as f64
                            / test.len() as f64;
                        let mu = scala_sum(d.iter().copied()) / d.len() as f64;
                        let sdv = (scala_sum(d.iter().map(|x| (x - mu) * (x - mu)))
                            / d.len() as f64)
                            .sqrt();
                        (
                            hit,
                            if sdv <= 0.0 || mu == 0.0 {
                                f64::NAN
                            } else {
                                (1.96 * sdv / mu.abs()).powi(2)
                            },
                        )
                    })
                    .collect()
            })
            .collect();
        (ok, res)
    };

    println!(
        "ESTIMATOR POWER — what each grading statistic can and cannot resolve from ONE history."
    );
    println!(
        "Cells are  hit%/n*:  hit% = share of single L-year histories whose measured difference has"
    );
    println!(
        "the same sign as the long-run difference at that length (50% = coin flip); n* = independent"
    );
    println!(
        "L-year histories a 95% paired interval would need to exclude zero.  The real record has 1."
    );
    println!(
        "Safe leg is CASH.  Read DOWN a column (statistics against each other); across columns the"
    );
    println!("question changes.");
    println!();
    for (j, (lbl, _, _, _)) in pairs.iter().enumerate() {
        // Scala is `f"  C${j + 1}%-3d $lbl%s"`: the width applies to the NUMBER, with the
        // `C` outside it — not to the whole "C1" token.
        println!("  C{:<3} {}", j + 1, lbl);
    }
    for &l in horizons {
        let (ok, res) = power(base, l, seed.wrapping_add(l as u64 * 1_000_003));
        let verdict = if ok {
            "gate PASS"
        } else {
            "gate FAIL — read nothing from this block"
        };
        println!(
            "\n  L = {} years   ({paths} independent histories, {verdict})",
            jf(l as f64, 3, 0)
        );
        let hdr: String = (0..pairs.len())
            .map(|j| format!("   C{:<8}", j + 1))
            .collect();
        println!("  {:<19}{}", "statistic", hdr);
        for (j, nm) in names.iter().enumerate() {
            let row: String = (0..pairs.len())
                .map(|c| {
                    let (hit, ns) = res[c][j];
                    if hit.is_nan() {
                        "       n/a".to_string()
                    } else {
                        format!("  {}/{}", jf(hit * 100.0, 3, 0), n_star_str(ns))
                    }
                })
                .collect();
            println!("  {nm:<19}{row}");
        }
    }

    if !single {
        let l = horizons[0];
        println!(
            "\n  ACROSS THE WORLD SWEEP at L = {l} years, contrast C1 — a measurement conclusion has"
        );
        println!(
            "  to hold in every world the gate admits, or it is a property of one parameter setting."
        );
        let per_world: Vec<(bool, PowerTable)> = sweep_worlds(base, false, false)
            .iter()
            .map(|(_, w, _)| power(w, l, seed + 31))
            .collect();
        let passing: Vec<&PowerTable> = per_world
            .iter()
            .filter(|(ok, _)| *ok)
            .map(|(_, r)| r)
            .collect();
        println!(
            "  {} of {} worlds pass the gate",
            passing.len(),
            per_world.len()
        );
        println!(
            "  {:<19} {:>8} {:>8} {:>8} {:>12}",
            "statistic", "min n*", "median", "max n*", "median hit%"
        );
        for (j, nm) in names.iter().enumerate() {
            let ns = sorted_total(
                &passing
                    .iter()
                    .map(|r| r[0][j].1)
                    .filter(|x| !x.is_nan())
                    .collect::<Vec<f64>>(),
            );
            let hs: Vec<f64> = passing
                .iter()
                .map(|r| r[0][j].0)
                .filter(|x| !x.is_nan())
                .collect();
            let hm = if hs.is_empty() {
                "n/a".to_string()
            } else {
                format!("{}%", jf(pctile(&hs, 0.5) * 100.0, 0, 0))
            };
            println!(
                "  {nm:<19} {:>8} {:>8} {:>8} {hm:>12}",
                n_star_str(ns.first().copied().unwrap_or(f64::NAN)),
                n_star_str(if ns.is_empty() {
                    f64::NAN
                } else {
                    pctile(&ns, 0.5)
                }),
                n_star_str(ns.last().copied().unwrap_or(f64::NAN))
            );
        }
    }
}

// ---- the buffer report ------------------------------------------------------------------

type BufferArm = (Vec<f64>, Vec<f64>, Vec<Vec<f64>>);

// ---- drawdown SHAPE: how a decline is delivered, not how deep it gets -------------------------
//
// A SECOND episode definition, and the difference from the model's own is the whole point.
// `measure` counts a crash as a 15%-below-peak excursion that re-arms once price is back within 2%
// — a definition built for COUNTING crashes. This one is peak-to-trough-to-FULL-recovery, built for
// SHAPE: how long a decline takes and how much of it arrives in one session. The two answer
// different questions and must not be mixed.
//
// Reported here rather than left in a consumer's own script because a second copy of a definition
// is a copy free to drift from this one.
//
// NOTHING HERE IS GATED. The real reference is ONE history: 12 episodes at the 10% threshold and 4
// at the 20%. A band drawn off four episodes could not fail.
pub struct DdEpisode {
    pub depth: f64,
    pub decline: usize,
    pub recovery: Option<usize>,
    pub underwater: usize,
    pub worst_day_share: f64,
}

/// An underwater span deeper than a threshold: `lo` the first underwater bar (the peak is the
/// session before it), `trough` its deepest, `hi` its last, `depth` px/peak - 1 at the trough.
struct DdSpan {
    lo: usize,
    trough: usize,
    hi: usize,
    depth: f64,
    censored: bool,
}

/// The spans `dd_episodes` and the macro panel's warning share share, so the two read the same
/// episodes.
fn dd_spans(px: &[f64], threshold: f64) -> Vec<DdSpan> {
    let n = px.len();
    let mut peak = f64::NEG_INFINITY;
    let under: Vec<f64> = px
        .iter()
        .map(|&p| {
            peak = peak.max(p);
            p / peak - 1.0
        })
        .collect();
    let mut spans: Vec<(usize, usize, bool)> = Vec::new();
    let mut start: Option<usize> = None;
    for (i, &u) in under.iter().enumerate() {
        let below = u < -1e-12;
        match (below, start) {
            (true, None) => start = Some(i),
            (false, Some(lo)) => {
                spans.push((lo, i - 1, false));
                start = None;
            }
            _ => {}
        }
    }
    if let Some(lo) = start {
        spans.push((lo, n - 1, true));
    }
    let mut out = Vec::new();
    for (lo, hi, censored) in spans {
        let depth = under[lo..=hi].iter().copied().fold(f64::INFINITY, f64::min);
        if depth > -threshold {
            continue;
        }
        let mut trough = lo;
        for k in lo..=hi {
            if under[k] < under[trough] {
                trough = k;
            }
        }
        out.push(DdSpan {
            lo,
            trough,
            hi,
            depth,
            censored,
        });
    }
    out
}

/// Peak-to-trough-to-recovery episodes deeper than `threshold`. An episode still underwater at the
/// end is CENSORED: its depth and decline count, its recovery does not.
///
/// `worst_day_share` is the fraction of the peak-to-trough LOG decline delivered by its single
/// worst session — low means the decline ground down, high means it gapped. The leg starts at the
/// session BEFORE the first underwater bar, because that is the session the fall began on.
pub fn dd_episodes(px: &[f64], threshold: f64) -> Vec<DdEpisode> {
    let mut out = Vec::new();
    for s in dd_spans(px, threshold) {
        let (lo, trough, hi, depth, censored) = (s.lo, s.trough, s.hi, s.depth, s.censored);
        let base = lo.saturating_sub(1);
        let total = (px[trough] / px[base]).ln();
        let worst = (lo.max(1)..=trough)
            .map(|k| (px[k] / px[k - 1]).ln())
            .fold(f64::INFINITY, f64::min);
        let worst = if worst.is_finite() { worst } else { 0.0 };
        out.push(DdEpisode {
            depth,
            decline: trough - lo + 1,
            recovery: if censored {
                None
            } else {
                Some(hi - trough + 1)
            },
            underwater: hi - lo + 1,
            worst_day_share: if total < 0.0 { worst / total } else { f64::NAN },
        });
    }
    out
}

/// One real drawdown reference row:
/// (threshold, episodes, per year, depth %, decline, recovery, underwater, worst-day share)
pub type DdRefRow = (f64, usize, f64, f64, usize, usize, usize, f64);

/// One threshold of the shape report: every reference row, the min/max across them, the model's
/// pooled row, and the ratio against the primary reference.
fn print_dd_threshold(thr: f64, eps: &[DdEpisode], rows: &[(&DdRef, DdRefRow)], p_yrs: f64) {
    let pct = (thr * 100.0) as usize;
    let med = |f: &dyn Fn(&DdEpisode) -> f64| {
        let v: Vec<f64> = eps.iter().map(f).collect();
        pctile(&v, 0.5)
    };
    let recov: Vec<f64> = eps
        .iter()
        .filter_map(|e| e.recovery.map(|r| r as f64))
        .collect();
    for (r, (_, r_eps, r_yr, r_depth, r_decl, r_recov, r_undw, r_wds)) in rows {
        println!(
            "  {:<15} {:>3}% {:>5} {} {}% {:>8} {:>9} {:>9} {}%",
            format!("{} {}", r.series, r.window),
            pct,
            r_eps,
            jf(*r_yr, 7, 2),
            jf(*r_depth, 7, 1),
            r_decl,
            r_recov,
            r_undw,
            jf(r_wds * 100.0, 9, 1)
        );
    }
    let ext = |pick: &dyn Fn(&[f64]) -> f64| -> [f64; 6] {
        let col = |g: &dyn Fn(&DdRefRow) -> f64| -> f64 {
            let v: Vec<f64> = rows.iter().map(|(_, row)| g(row)).collect();
            pick(&v)
        };
        [
            col(&|r| r.2),
            col(&|r| r.3),
            col(&|r| r.4 as f64),
            col(&|r| r.5 as f64),
            col(&|r| r.6 as f64),
            col(&|r| r.7),
        ]
    };
    let fmin = |v: &[f64]| v.iter().cloned().fold(f64::INFINITY, f64::min);
    let fmax = |v: &[f64]| v.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
    for (label, x) in [("refs min", ext(&fmin)), ("refs max", ext(&fmax))] {
        println!(
            "  {:<15} {:>3}% {:>5} {} {}% {} {} {} {}%",
            label,
            pct,
            "",
            jf(x[0], 7, 2),
            jf(x[1], 7, 1),
            jf(x[2], 8, 0),
            jf(x[3], 9, 0),
            jf(x[4], 9, 0),
            jf(x[5] * 100.0, 9, 1)
        );
    }
    let (_, _, r_yr, r_depth, r_decl, r_recov, r_undw, r_wds) = rows[0].1;
    let m_depth = med(&|e| e.depth) * 100.0;
    let m_decl = med(&|e| e.decline as f64);
    let m_recov = pctile(&recov, 0.5);
    let m_undw = med(&|e| e.underwater as f64);
    let m_wds = med(&|e| e.worst_day_share);
    println!(
        "  {:<15} {:>3}% {:>5} {} {}% {} {} {} {}%",
        "model",
        pct,
        eps.len(),
        jf(eps.len() as f64 / p_yrs, 7, 2),
        jf(m_depth, 7, 1),
        jf(m_decl, 8, 0),
        jf(m_recov, 9, 0),
        jf(m_undw, 9, 0),
        jf(m_wds * 100.0, 9, 1)
    );
    println!(
        "  {:<15} {:>3}% {:>5} {} {} {} {} {} {}",
        "ratio",
        pct,
        "",
        jf(eps.len() as f64 / p_yrs / r_yr, 7, 2),
        jf(m_depth / r_depth, 8, 2),
        jf(m_decl / r_decl as f64, 8, 2),
        jf(m_recov / r_recov as f64, 9, 2),
        jf(m_undw / r_undw as f64, 9, 2),
        jf(m_wds / r_wds, 10, 2)
    );
    println!();
}

fn run_drawdown_shape(a: &Anchors, paths: usize, years: usize, seed: u64, base: &World) {
    eprintln!("{paths} paths x {years} years");
    let sims = sim_paths(base, paths, years, seed);
    let p_yrs = sims.len() as f64 * years as f64;
    let refs = a.dd_refs;
    println!("DRAWDOWN SHAPE — how a decline is DELIVERED: how long it takes, and how much of it");
    println!(
        "arrives in its single worst session.  This is a SECOND episode definition on purpose:"
    );
    println!(
        "the model's own crash count is a 15%-below-peak excursion re-arming at 2%, built for"
    );
    println!(
        "counting; these are peak-to-trough-to-FULL-recovery, built for shape.  Do not mix them."
    );
    println!();
    println!(
        "References for the {} set, every median on the model's own pctile(.., 0.5); the",
        a.name
    );
    println!(
        "ratio reads against {} {} ({:.0} years) and the min/max rows span every",
        refs[0].series, refs[0].window, refs[0].years
    );
    println!("reference.  NOTHING HERE IS GATED: the episode counts are printed so nobody reads a");
    println!(
        "median of four as a population value, and the ratios are for reading, not for passing."
    );
    println!();
    println!(
        "  {:<15} {:>4} {:>5} {:>7} {:>8} {:>8} {:>9} {:>9} {:>10}",
        "series", "thr", "eps", "eps/yr", "depth", "decline", "recovery", "underwtr", "worst-day"
    );
    for thr in [0.10, 0.20] {
        let eps: Vec<DdEpisode> = sims
            .iter()
            .flat_map(|p| dd_episodes(&p.price, thr))
            .collect();
        let rows: Vec<(&DdRef, DdRefRow)> = refs
            .iter()
            .filter_map(|r| r.rows.iter().find(|row| row.0 == thr).map(|row| (r, *row)))
            .collect();
        print_dd_threshold(thr, &eps, &rows, p_yrs);
    }
    println!("  A LOW worst-day ratio means the model's declines GRIND where the real one GAPPED.");
    println!(
        "  Read it beside the decline column: a decline taking twice as long dilutes its worst"
    );
    println!("  session by construction, so the two move together -- and read both against the");
    println!(
        "  min/max rows before the ratio: the references disagree with each other by more than"
    );
    println!("  most model/real ratios here.");
    println!();
    println!("  Medians here are `pctile(.., 0.5)`: the UPPER of the two middle elements of an");
    println!(
        "  ascending sort on an even count (a depth reads the shallower, a duration the longer),"
    );
    println!("  where NumPy averages them.  The reference rows are on the same median, so a");
    println!("  consumer reproducing them with NumPy lands one element away on a four-episode");
    println!("  statistic and is right.");
}

#[expect(
    clippy::too_many_lines,
    reason = "one linear report, mirroring the Scala twin statement for statement"
)]
#[expect(
    clippy::too_many_arguments,
    reason = "the parameter list mirrors the Scala twin's, and the twins are diffed"
)]
fn run_buffer_report(
    a: Anchors,
    paths: usize,
    years: usize,
    seed: u64,
    cost: f64,
    single: bool,
    base: &World,
    gate_req: &[GateClass],
) {
    // 15% is the repo's existing episode threshold; reusing it keeps this report from
    // introducing a new arbitrary constant. Without it the distribution is drowned.
    const MATERIAL_DEPTH: f64 = 0.15;
    let focus = [
        ("vol-scaled 40%", "volatility-scaled, floor 40%"),
        ("vol+trend 200d", "volatility + trend 200d, floor 0%"),
    ];
    let mut arms: Vec<(String, ExposeFn)> = vec![(
        "100% equity".to_string(),
        Arc::new(|ind: &Indicators| vec![1.0; ind.px.len()]) as ExposeFn,
    )];
    for (short, nm) in &focus {
        let r = rule_named(nm);
        arms.push(((*short).to_string(), Arc::clone(&r.expose)));
        let inner = Arc::clone(&r.expose);
        arms.push((
            format!("static mix @ {short}"),
            Arc::new(move |i: &Indicators| matched_constant(&inner(i))) as ExposeFn,
        ));
    }
    let buffers = [5usize, 15usize];
    let overruns = [3.0f64, 5.0, 10.0, 15.0];
    let path_years = paths as f64 * years as f64;

    // per arm: (material-stretch lengths in years, ALL stretch lengths, depth at exhaustion
    // per buffer). ALL stretches are kept for the time-share column, because a buffer policy
    // is chosen before knowing which stretch you land in.
    let buffer_stats = |w: &World| -> (bool, Vec<BufferArm>) {
        let sims = sim_paths(w, paths, years, seed);
        let ok = gate_ok(a, &measure(&sims, years), gate_req);
        let per: Vec<Vec<BufferArm>> = (0..sims.len())
            .into_par_iter()
            .map(|k| {
                let p = &sims[k];
                let ind = Indicators::new(&p.price);
                arms.iter()
                    .map(|(_, f)| {
                        let ap = arm_path(p, &f(&ind), cost, Safe::Bond);
                        let us = underwater(&ap.real_log_eq);
                        let yrs: Vec<f64> = us
                            .iter()
                            .map(|u| u.sessions() as f64 / DAYS_PER_YEAR as f64)
                            .collect();
                        let mat: Vec<f64> = us
                            .iter()
                            .zip(&yrs)
                            .filter(|(u, _)| u.worst_depth >= MATERIAL_DEPTH)
                            .map(|(_, y)| *y)
                            .collect();
                        let by_buf: Vec<Vec<f64>> = buffers
                            .iter()
                            .map(|b| {
                                us.iter()
                                    .map(|u| {
                                        depth_at_exhaustion(&ap.real_log_eq, *u, b * DAYS_PER_YEAR)
                                    })
                                    .filter(|x| !x.is_nan())
                                    .collect()
                            })
                            .collect();
                        (mat, yrs, by_buf)
                    })
                    .collect()
            })
            .collect();
        let res: Vec<BufferArm> = (0..arms.len())
            .map(|j| {
                (
                    per.iter().flat_map(|r| r[j].0.iter().copied()).collect(),
                    per.iter().flat_map(|r| r[j].1.iter().copied()).collect(),
                    (0..buffers.len())
                        .map(|b| per.iter().flat_map(|r| r[j].2[b].iter().copied()).collect())
                        .collect(),
                )
            })
            .collect();
        (ok, res)
    };

    let (ok, res) = buffer_stats(base);
    println!(
        "THE BUFFER QUESTION — length of REAL (CPI-deflated) underwater stretches, pooled over"
    );
    println!(
        "{paths} independent {years}-year histories = {} path-years.  Safe leg is the BOND,",
        path_years as i64
    );
    println!(
        "so a 'static mix' arm is a constant equity/bond portfolio at that rule's own average"
    );
    println!(
        "exposure.  Stretches still under water at path end are INCLUDED at their length so far."
    );
    println!(
        "  baseline world: {}",
        if ok {
            "gate PASS"
        } else {
            "gate FAIL — read nothing below"
        }
    );
    println!();
    println!(
        "  material stretches (real depth >= {}%)        share of ALL calendar time spent inside a",
        jf(MATERIAL_DEPTH * 100.0, 0, 0)
    );
    println!(
        "                                                     stretch that ends up running longer than"
    );
    let head_over: String = overruns
        .iter()
        .map(|b| format!("{:>7}", format!("{}y", jf(*b, 0, 0))))
        .collect();
    println!(
        "  {:<28} {:>7} {:>6} {:>6} {:>6} {:>6}  {}",
        "arm", "n", "med", "90th", "99th", "worst", head_over
    );
    for j in 0..arms.len() {
        let (mat, all, _) = &res[j];
        let share = |y: f64| -> f64 {
            scala_sum(all.iter().filter(|x| **x > y).copied()) / path_years * 100.0
        };
        let cols: Vec<String> = overruns
            .iter()
            .map(|b| format!("{}%", jf(share(*b), 6, 1)))
            .collect();
        println!(
            "  {:<28} {:>7} {} {} {} {}  {}",
            arms[j].0,
            mat.len(),
            jf(pctile(mat, 0.5), 6, 1),
            jf(pctile(mat, 0.90), 6, 1),
            jf(pctile(mat, 0.99), 6, 1),
            jf(max_total(mat), 6, 1),
            cols.join(" ")
        );
    }

    println!(
        "\n  DEPTH AT EXHAUSTION — how often a buffer of B years is outlasted, and how deep it has"
    );
    println!(
        "  got by then.  Stretches that never outlast the buffer force no sale and are EXCLUDED;"
    );
    println!("  entering them as zeros would average in episodes that cost nothing.");
    let head_buf: String = buffers
        .iter()
        .map(|b| {
            format!(
                "    {:>18} {:>7} {:>7}",
                format!("B={b}y per century"),
                "median",
                "worst"
            )
        })
        .collect();
    println!("  {:<28}{}", "arm", head_buf);
    for j in 0..arms.len() {
        let row: String = (0..buffers.len())
            .map(|b| {
                let e = &res[j].2[b];
                let per_century = e.len() as f64 * 100.0 / path_years;
                if e.is_empty() {
                    format!("    {} {:>7} {:>7}", jf(per_century, 18, 2), "n/a", "n/a")
                } else {
                    format!(
                        "    {} {}% {}%",
                        jf(per_century, 18, 2),
                        jf(pctile(e, 0.5) * 100.0, 6, 1),
                        jf(max_total(e) * 100.0, 6, 1)
                    )
                }
            })
            .collect();
        println!("  {:<28}{}", arms[j].0, row);
    }

    if !single {
        println!(
            "\n  ACROSS THE WORLD SWEEP — gate-passing worlds only.  A buffer number that moves with"
        );
        println!(
            "  the world parameters is a property of one parameter setting, not a planning figure."
        );
        let per_world: Vec<(bool, Vec<BufferArm>)> = sweep_worlds(base, false, false)
            .iter()
            .map(|(_, w, _)| buffer_stats(w))
            .collect();
        let passing: Vec<&Vec<BufferArm>> = per_world
            .iter()
            .filter(|(ok, _)| *ok)
            .map(|(_, r)| r)
            .collect();
        println!(
            "  {} of {} worlds pass the gate",
            passing.len(),
            per_world.len()
        );
        println!(
            "  {:<28} {:>32}   share of time in a >10y stretch",
            "arm", "99th pct material stretch, yrs"
        );
        println!(
            "  {:<28} {:>10} {:>10} {:>10}   {:>9} {:>9} {:>9}",
            " ", "min", "median", "max", "min", "median", "max"
        );
        for j in 0..arms.len() {
            let q = sorted_total(
                &passing
                    .iter()
                    .map(|r| pctile(&r[j].0, 0.99))
                    .collect::<Vec<f64>>(),
            );
            let t = sorted_total(
                &passing
                    .iter()
                    .map(|r| {
                        scala_sum(r[j].1.iter().filter(|x| **x > 10.0).copied()) / path_years
                            * 100.0
                    })
                    .collect::<Vec<f64>>(),
            );
            println!(
                "  {:<28} {} {} {}   {}% {}% {}%",
                arms[j].0,
                jf(q.first().copied().unwrap_or(f64::NAN), 10, 1),
                jf(pctile(&q, 0.5), 10, 1),
                jf(q.last().copied().unwrap_or(f64::NAN), 10, 1),
                jf(t.first().copied().unwrap_or(f64::NAN), 8, 1),
                jf(pctile(&t, 0.5), 8, 1),
                jf(t.last().copied().unwrap_or(f64::NAN), 8, 1)
            );
        }
    }
}

/// Consume the next argument as an `f64`, keeping the default if it is missing or unparseable.
// Numeric arguments fail LOUDLY. The old parse-or-default silently substituted the default —
// `-emitpath -1` emitted path 0 with exit 0, a plausible file for an index nobody asked for —
// and the Scala twin died on the same input with a raw NumberFormatException. Both now reject.
fn cli_die(msg: &str) -> ! {
    eprintln!("{msg}");
    std::process::exit(2);
}

fn req_arg<'a>(it: &mut impl Iterator<Item = &'a String>, flag: &str) -> &'a String {
    it.next()
        .unwrap_or_else(|| cli_die(&format!("{flag} wants a value")))
}

fn req_usize_list<'a>(it: &mut impl Iterator<Item = &'a String>, flag: &str) -> Vec<usize> {
    let v = req_arg(it, flag);
    let parts: Vec<&str> = v
        .split(',')
        .map(str::trim)
        .filter(|p| !p.is_empty())
        .collect();
    if parts.is_empty() {
        cli_die(&format!(
            "{flag} wants a comma-separated list of integers, got [{v}]"
        ));
    }
    parts
        .iter()
        .map(|p| {
            p.parse()
                .unwrap_or_else(|_| cli_die(&format!("{flag} wants integers, got [{p}]")))
        })
        .collect()
}

fn req_f64<'a>(it: &mut impl Iterator<Item = &'a String>, flag: &str) -> f64 {
    let v = req_arg(it, flag);
    v.parse()
        .unwrap_or_else(|_| cli_die(&format!("{flag} wants a number, got [{v}]")))
}

fn req_usize<'a>(it: &mut impl Iterator<Item = &'a String>, flag: &str) -> usize {
    let v = req_arg(it, flag);
    v.parse()
        .unwrap_or_else(|_| cli_die(&format!("{flag} wants a non-negative integer, got [{v}]")))
}

fn req_u64<'a>(it: &mut impl Iterator<Item = &'a String>, flag: &str) -> u64 {
    let v = req_arg(it, flag);
    v.parse()
        .unwrap_or_else(|_| cli_die(&format!("{flag} wants a non-negative integer, got [{v}]")))
}

// ---- Java-compatible formatting ---------------------------------------------------------

/// `%<width>.<dec>f`
fn jf(v: f64, width: i32, dec: i32) -> String {
    java_format_f(v, width, dec)
}

/// A rendering whose digits are ALL ZERO carries no sign: the quantity is zero to the
/// precision shown, so a leading `-` there reports rounding NOISE as direction. uni's own
/// `numStr` blanks the sign for the same reason.
///
/// It matters here beyond tidiness. A column whose true value is identically zero — the
/// always-invested rule against buy-and-hold, where the cumulative sum telescopes — has
/// nothing left in it but the last-ulp gap between the JVM's `Math.log` and libm's, measured
/// at 0.235% of calls, 1 ulp. Without this the sign printed there is a coin flip, and the
/// two languages cannot agree on it.
fn blank_zero_sign(s: String) -> String {
    let has_digit = s.bytes().any(|b| b.is_ascii_digit());
    let all_zero = s.bytes().all(|b| !b.is_ascii_digit() || b == b'0');
    if has_digit && all_zero {
        s.chars()
            .map(|c| if c == '+' || c == '-' { ' ' } else { c })
            .collect()
    } else {
        s
    }
}

/// `%+.<dec>f` — Java prefixes non-negative values with `+`, and leaves NaN unsigned.
fn jfs(v: f64, dec: i32) -> String {
    if v.is_nan() {
        return "NaN".to_string();
    }
    let s = java_format_f(v, 0, dec);
    blank_zero_sign(if s.starts_with('-') {
        s
    } else {
        format!("+{s}")
    })
}

/// `%+<width>.<dec>f` — the sign joins the number, then the whole thing is right-justified.
fn jfsw(v: f64, width: i32, dec: i32) -> String {
    let body = jfs(v, dec);
    let pad = width.max(0) as usize;
    if body.len() < pad {
        format!("{}{body}", " ".repeat(pad - body.len()))
    } else {
        body
    }
}

/// `%-<width>.<dec>f` — LEFT-justified, which `java_format_f` does not do.
fn jfl(v: f64, width: i32, dec: i32) -> String {
    let body = java_format_f(v, 0, dec);
    let pad = width.max(0) as usize;
    if body.len() < pad {
        format!("{body}{}", " ".repeat(pad - body.len()))
    } else {
        body
    }
}

// ---- export: the full state, named, dated and provenanced -------------------------------
//
// An emitted path is the whole external interface: a consumer grades its own rules on it
// without importing either twin. Three properties make that work, and all three were missing.
//   1. EVERY series the model knows, not just price and bond. A rule that de-risks to cash is
//      mis-scored without `rate`; a real-terms question is unanswerable without `cpi`; slippage
//      cannot be charged the way `arm_path` charges it without `liq`/`bliq`; and `fundamental`
//      is an oracle label (fundamental-led vs liquidity-led decline) that no real series can
//      supply.
//   2. A NAMED path. `seed + k*7919` makes the family reproducible, but nothing in the output
//      said which (world, seed, k) produced a file, so an ensemble could not be inventoried and
//      the same paths could be re-drawn and counted twice as independent evidence.
//   3. A verdict measured on the WORLD, not on the sample. The four mechanism checks are
//      conditional on crash episodes, so one short path cannot measure them and every export
//      carried a false alarm — worse than no warning. See `-emitgate`.

const EMIT_COLUMNS: [&str; 9] = [
    "date",
    "price",
    "bond",
    "rate",
    "cpi",
    "liq",
    "bliq",
    "fundamental",
    "inflPress",
];

/// `%.6f`, with negative zero folded to positive. Emitted columns are levels rather than
/// differences, so the signed-zero trap PARITY.md documents is remote here — but `rate` is
/// floored at zero and `inflPress` starts there, and IEEE-754 guarantees (-0.0) + 0.0 = +0.0 in
/// both languages, so the fold costs nothing and removes the last way the two writers could
/// disagree on a byte.
fn ef(x: f64) -> String {
    jf(if x == 0.0 { 0.0 } else { x }, 0, 6)
}

/// Scala's `Double.toString` for the range a DIAL is typed in: Rust's `Display` prints `3` where
/// Scala prints `3.0`, and the domain messages below are the twins' only user-visible text carrying
/// a raw f64. NOT general `Double.toString` parity -- the exponent forms and the shortest-repr
/// corners are out of scope, and nothing echoes a dial from there.
fn scala_dbl(x: f64) -> String {
    let t = format!("{x}");
    if x.is_finite() && !t.contains(['.', 'e', 'E']) {
        format!("{t}.0")
    } else {
        t
    }
}

fn json_str(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            _ => out.push(c),
        }
    }
    out.push('"');
    out
}

fn crowd_name(c: Crowd) -> String {
    match c {
        Crowd::Momentum => "momentum".to_string(),
        Crowd::Trend(d) => format!("trend{d}"),
        Crowd::VolScaled => "volscaled".to_string(),
        Crowd::Drawdown(d) => format!("drawdown{d}"),
    }
}

/// Session dates. An empty `start_ymd` keeps the historical synthetic calendar: 1900-01-02
/// stepping 365/252 days, which lands on weekends and so can never be joined to a real dated
/// series. A date instead steps by WEEKDAYS (no holiday calendar — recorded, not hidden), which
/// is what lets an emitted path through a normal dated loader untouched.
pub fn session_dates(n: usize, start_ymd: &str) -> Vec<String> {
    if start_ymd.is_empty() {
        let start = UniDateTime::ofYmd(1900, 1, 2);
        return (0..n)
            .map(|i| {
                start
                    .plusDays((i as i64 * 365) / DAYS_PER_YEAR as i64)
                    .ymd()
            })
            .collect();
    }
    // Validate BEFORE building the date. uni's sentinel invariant is that an invalid
    // UniDateTime propagates itself — plusDays returns the same date — so feeding one into the
    // weekday recurrence below is an infinite loop, not an error. The guard lives here, with
    // the consumer, exactly as the sentinel contract requires.
    let f: Vec<&str> = start_ymd.split('-').collect();
    if f.len() != 3 {
        cli_die(&format!("-emitstart wants YYYY-MM-DD, got [{start_ymd}]"));
    }
    let parse = |s: &str| -> i32 {
        s.parse()
            .unwrap_or_else(|_| cli_die(&format!("-emitstart wants YYYY-MM-DD, got [{start_ymd}]")))
    };
    let (y, m, dd) = (parse(f[0]), parse(f[1]), parse(f[2]));
    let leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0);
    if !(1..=9999).contains(&y) || !(1..=12).contains(&m) {
        cli_die(&format!("-emitstart [{start_ymd}] is not a calendar date"));
    }
    let dim = match m {
        2 => {
            if leap {
                29
            } else {
                28
            }
        }
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    };
    if !(1..=dim).contains(&dd) {
        cli_die(&format!("-emitstart [{start_ymd}] is not a calendar date"));
    }
    fn next_weekday(d: UniDateTime) -> UniDateTime {
        let mut d = d;
        while d.dayOfWeekNum() > 5 {
            d = d.plusDays(1);
        }
        d
    }
    // a stateful recurrence written as one, like simulate(): each session is the next weekday
    // strictly after the previous one
    let mut out = Vec::with_capacity(n);
    let mut d = next_weekday(UniDateTime::ofYmd(y, m, dd));
    for _ in 0..n {
        out.push(d.ymd());
        d = next_weekday(d.plusDays(1));
    }
    out
}

/// `foo.tsv` -> `foo.json`; a name with no extension just gains one.
fn sidecar_name(file: &str) -> String {
    let cut = file.rfind('.');
    let sep = file.rfind(['/', '\\']);
    match cut {
        Some(c) if sep.is_none_or(|s| c > s) => format!("{}.json", &file[..c]),
        _ => format!("{file}.json"),
    }
}

/// Zero-padding width for `indexed_name`, from the highest index a batch writes. Floored at 3 so
/// every ensemble of 1000 or fewer keeps the names it has always had; a larger one widens rather
/// than losing the sort order the padding exists to give.
fn index_width(last_index: usize) -> usize {
    3.max(last_index.to_string().len())
}

/// `foo.tsv` -> `foo-007.tsv`, so an ensemble sorts in path order.
fn indexed_name(file: &str, k: usize, width: usize) -> String {
    let cut = file.rfind('.');
    let sep = file.rfind(['/', '\\']);
    let tag = format!("-{k:0width$}");
    match cut {
        Some(c) if sep.is_none_or(|s| c > s) => format!("{}{tag}{}", &file[..c], &file[c..]),
        _ => format!("{file}{tag}"),
    }
}

fn write_or_die(file: &str, body: &str) {
    std::fs::write(file, body).unwrap_or_else(|e| {
        eprintln!("cannot write {file}: {e}");
        std::process::exit(1);
    });
}

/// The TSV and its sidecar. `gate_st` is measured on the gate ensemble — a different, usually
/// much larger and (per `GATE_YEARS`) usually longer sample than the one path being written —
/// and `gate_rows` are built from it once per batch, because building them simulates the
/// extreme rows' own-horizon ensemble.
#[expect(
    clippy::too_many_arguments,
    reason = "the sidecar records the whole provenance tuple; grouping it would only move the list"
)]
pub fn write_emitted(
    a: Anchors,
    file: &str,
    p: &Path,
    k: usize,
    w: &World,
    years: usize,
    seed: u64,
    start_ymd: &str,
    gate_st: &WorldStats,
    gate_paths: usize,
    gate_years: usize,
    gate_rows: &[FidelityRow],
) {
    // A non-finite path is refused, not written -- a file whose every row reads NaN is not data.
    // The CLI's clean refusal (message + exit 2) lives at the emit sites in `main`, which pre-check
    // before calling; here it PANICS, because this is also API and a `process::exit` in a library
    // function takes a test harness down whole rather than failing one test.
    assert!(
        p.price.iter().all(|x| x.is_finite())
            && p.sat.iter().all(|x| x.is_finite())
            && p.log_hi.iter().all(|x| x.is_finite())
            && p.log_lo.iter().all(|x| x.is_finite())
            && p.log_volume.iter().all(|x| x.is_finite())
            && p.div_yield.iter().all(|x| x.is_finite())
            && p.traded.iter().all(|x| x.is_finite())
            && p.log_open.iter().all(|x| x.is_finite())
            && p.names.iter().all(|lp| lp.iter().all(|x| x.is_finite()))
            && p.macro_panel
                .as_ref()
                .is_none_or(|m| (0..9).all(|j| m.member(j).iter().all(|x| x.is_finite()))),
        "path {k} holds a non-finite value; refusing {file}"
    );
    let dates = session_dates(p.price.len(), start_ymd);
    write_emit_tsv(file, p, &dates);
    write_emit_sidecar(
        a, file, p, k, w, years, seed, start_ymd, &dates, gate_st, gate_paths, gate_years,
        gate_rows,
    );
}

/// The optional TSV columns, in header order, each present exactly when its channel ran — the
/// one list the header, the sidecar's `columns` and its `gradedSeries` all read.
fn channel_columns(p: &Path) -> Vec<&'static str> {
    let mut cols: Vec<&'static str> = Vec::new();
    if !p.sat.is_empty() {
        cols.push("logSat");
    }
    if !p.log_hi.is_empty() {
        cols.push("logHigh");
        cols.push("logLow");
    }
    if !p.log_volume.is_empty() {
        cols.push("logVolume");
    }
    if !p.traded.is_empty() {
        cols.push("logTraded");
        cols.push("divYield");
    }
    if !p.log_open.is_empty() {
        cols.push("logOpen");
    }
    cols
}

/// The macro panel's columns, present exactly when it ran — after the basket's in every list.
fn macro_columns(p: &Path) -> &'static [&'static str] {
    if p.macro_panel.is_some() {
        &macro_k::COLUMNS
    } else {
        &[]
    }
}

/// The basket's optional columns: the aggregate, then one per name.
fn basket_columns(p: &Path) -> Vec<String> {
    if p.names.is_empty() {
        return Vec::new();
    }
    let mut cols = vec!["logBasket".to_string()];
    cols.extend((1..=p.names.len()).map(|q| format!("logName{q}")));
    cols
}

/// The equal-weight aggregate of the names as a LOG price series: the mean of the names' price
/// levels relative to their common start, the fixture's convention (equal weights held from the
/// first session, never rebalanced).
fn basket_aggregate(names: &[Vec<f64>]) -> Vec<f64> {
    (0..names[0].len())
        .map(|i| {
            (names.iter().map(|lp| (lp[i] - lp[0]).exp()).sum::<f64>() / names.len() as f64).ln()
        })
        .collect()
}

/// One session's optional cells, in header order, each present exactly when its channel ran.
fn push_channel_cells(tsv: &mut String, p: &Path, i: usize, basket_agg: &[f64]) {
    let mut cell = |v: f64| {
        tsv.push('\t');
        tsv.push_str(&ef(v));
    };
    if !p.sat.is_empty() {
        cell(p.sat[i].ln());
    }
    if !p.log_hi.is_empty() {
        cell(p.log_hi[i]);
        cell(p.log_lo[i]);
    }
    if !p.log_volume.is_empty() {
        cell(p.log_volume[i]);
    }
    if !p.traded.is_empty() {
        cell(p.traded[i].ln());
        cell(p.div_yield[i]);
    }
    if !p.log_open.is_empty() {
        cell(p.log_open[i]);
    }
    if !p.names.is_empty() {
        cell(basket_agg[i]);
        for lp in &p.names {
            cell(lp[i]);
        }
    }
    if let Some(m) = &p.macro_panel {
        cell(m.spread[i]);
        cell(m.slope[i]);
        cell(m.cond[i]);
        cell(m.ivol[i]);
        cell(m.yield10[i]);
        cell(m.credit[i]);
        cell(m.policy[i]);
        cell(m.bank[i]);
        cell(m.output[i]);
    }
}

pub fn write_emit_tsv(file: &str, p: &Path, dates: &[String]) {
    let mut tsv = String::new();
    tsv.push_str(&EMIT_COLUMNS.join("\t"));
    // The optional columns, present only when their channel ran — a channels-off file is
    // byte-identical to its predecessor schema's. LOG columns throughout: see the 7 -> 8 and
    // 8 -> 9 notes at `EMIT_SCHEMA`.
    if !p.sat.is_empty() {
        tsv.push_str("\tlogSat");
    }
    if !p.log_hi.is_empty() {
        tsv.push_str("\tlogHigh\tlogLow");
    }
    if !p.log_volume.is_empty() {
        tsv.push_str("\tlogVolume");
    }
    if !p.traded.is_empty() {
        tsv.push_str("\tlogTraded\tdivYield");
    }
    if !p.log_open.is_empty() {
        tsv.push_str("\tlogOpen");
    }
    for c in basket_columns(p) {
        tsv.push('\t');
        tsv.push_str(&c);
    }
    for c in macro_columns(p) {
        tsv.push('\t');
        tsv.push_str(c);
    }
    let basket_agg = if p.names.is_empty() {
        Vec::new()
    } else {
        basket_aggregate(&p.names)
    };
    tsv.push('\n');
    for (i, d) in dates.iter().enumerate() {
        tsv.push_str(d);
        for v in [
            p.price[i],
            p.bond[i],
            p.rate[i],
            p.cpi[i],
            p.liq[i],
            p.bliq[i],
            p.fundamental[i],
            p.infl_press[i],
        ] {
            tsv.push('\t');
            tsv.push_str(&ef(v));
        }
        push_channel_cells(&mut tsv, p, i, &basket_agg);
        tsv.push('\n');
    }
    write_or_die(file, &tsv);
}

/// Every `World` field, in declaration order, as the indented body of a JSON object. A world
/// that reaches a consumer without its parameters cannot be re-simulated.
/// The world under its CLI flag names, at the sidecar's own width.
pub fn world_json_body(w: &World) -> Vec<String> {
    world_json_body_fmt(w, &ef)
}

/// As above, with every dial rendered by `num`. A caller needing a different width than the
/// report's asks for one here rather than keeping a second copy of the key list: the calibration
/// search exports its archive at the archive's own width, because a consumer RECONSTRUCTS a world
/// from this block and the report's six decimals drop digits the archive holds.
pub fn world_json_body_fmt(w: &World, num: &dyn Fn(f64) -> String) -> Vec<String> {
    let fields: Vec<(&str, String)> = vec![
        ("trendShare", num(w.trend_share)),
        ("depth", num(w.depth)),
        ("stress", num(w.stress)),
        ("beta", num(w.beta)),
        ("drift", num(w.drift)),
        ("fundVol", num(w.fund_vol)),
        ("rateMean", num(w.rate_mean)),
        ("volPersist", num(w.vol_persist)),
        ("volOfVol", num(w.vol_of_vol)),
        ("leverage", num(w.leverage)),
        ("downShock", num(w.down_shock)),
        ("jumpSkew", num(w.jump_skew)),
        ("jumpVar", num(w.jump_var)),
        ("jumpRate", num(w.jump_rate)),
        ("newsRate", num(w.news_rate)),
        ("newsSize", num(w.news_size)),
        ("valuePull", num(w.value_pull)),
        ("recoveryDrag", num(w.recovery_drag)),
        ("recoveryFloor", num(w.recovery_floor)),
        ("haltLimit", num(w.halt_limit)),
        ("disasterRate", num(w.disaster_rate)),
        ("disasterSize", num(w.disaster_size)),
        ("disasterLen", num(w.disaster_len)),
        ("disasterRecover", num(w.disaster_recover)),
        ("disasterRecLen", num(w.disaster_rec_len)),
        ("beliefShare", num(w.belief_share)),
        ("beliefYears", num(w.belief_years)),
        ("capYears", num(w.cap_years)),
        ("capWindow", num(w.cap_window)),
        ("crowd", json_str(&crowd_name(w.crowd))),
        ("crowdImpact", num(w.crowd_impact)),
        ("panic", num(w.panic)),
        ("duration", num(w.duration)),
        ("easing", num(w.easing)),
        ("unwind", num(w.unwind)),
        ("refuge", num(w.refuge)),
        ("refugeDays", num(w.refuge_days)),
        ("satBeta", num(w.sat_beta)),
        ("satIdio", num(w.sat_idio)),
        ("rangeScale", num(w.range_scale)),
        ("rangeDown", num(w.range_down)),
        ("volIdio", num(w.vol_idio)),
        ("divYield", num(w.div_yield)),
        ("overnight", num(w.overnight)),
        ("basket", w.basket.to_string()),
        ("basketBeta", num(w.basket_beta)),
        ("basketSector", num(w.basket_sector)),
        ("basketIdio", num(w.basket_idio)),
        ("basketGaps", num(w.basket_gaps)),
        ("basketDrift", num(w.basket_drift)),
        // the flag's name, as every dial's key is: the field is `macro_panel` only because the
        // Scala twin's cannot be `macro`, a reserved word there
        ("macro", w.macro_panel.to_string()),
        ("levGain", num(w.lev_gain)),
        ("stressScale", num(w.stress_scale)),
        ("levPersist", num(w.lev_persist)),
        ("noiseAsym", num(w.noise_asym)),
        ("noiseAsymPhi", num(w.noise_asym_phi)),
        ("noiseAsymCap", num(w.noise_asym_cap)),
        ("volResp", num(w.vol_resp)),
        ("volRespPhi", num(w.vol_resp_phi)),
        ("volRespCap", num(w.vol_resp_cap)),
        ("volRespAttack", num(w.vol_resp_attack)),
        ("jumpResp", num(w.jump_resp)),
        ("stressAdapt", num(w.stress_adapt)),
        ("bustAmp", num(w.bust_amp)),
        ("slowShare", num(w.slow_share)),
        ("slowVol", num(w.slow_vol)),
        ("slowLev", num(w.slow_lev)),
        ("slowPhi", num(w.slow_phi)),
        ("slowPerm", num(w.slow_perm)),
        ("slowBeta", num(w.slow_beta)),
        ("macroNull", w.macro_null.to_string()),
        ("inflProb", num(w.infl_prob)),
        ("inflSize", num(w.infl_size)),
        ("inflSpeed", num(w.infl_speed)),
        ("rateSpeed", num(w.rate_speed)),
        ("discount", num(w.discount)),
        ("margin", num(w.margin)),
    ];
    fields
        .iter()
        .map(|(nm, v)| format!("    {}: {v}", json_str(nm)))
        .collect()
}

/// The channel readings the `satellite *` / `bar *` gate rows grade, as DATA: `fidelityFailed`
/// names a band, and a reader that never sees the report could not size a channel FAIL from it.
/// Each object is present exactly when its channel ran (`sat_stats`/`bar_stats` return `Some`);
/// `{}` when none did, else led by the world level they were sampled at (`world_level`). NaN
/// prints as null, the `fidelity` rows' rule.
/// The sidecar's `level` block: the world level every channel was sampled at.
fn level_block(p: &Path) -> String {
    let num = |x: f64| {
        if x.is_nan() {
            "null".to_string()
        } else {
            ef(x)
        }
    };
    format!(
        "    \"level\": {{ \"k\": {}, \"kSat\": {}, \"kDiv\": {}, \"kVs\": {}, \"kIv\": {}{} }}",
        num(p.chan_k),
        num(p.chan_k_sat),
        num(p.chan_k_div),
        num(p.chan_k_vs),
        num(p.chan_k_iv),
        if p.chan_k_dr > 0.0 {
            format!(", \"kDr\": {}", num(p.chan_k_dr))
        } else {
            String::new()
        }
    )
}

fn channel_readings_block(st: &WorldStats, p: &Path) -> String {
    let num = |x: f64| {
        if x.is_nan() {
            "null".to_string()
        } else {
            ef(x)
        }
    };
    let mut blocks: Vec<String> = Vec::new();
    if st.sat.is_some()
        || st.bars.is_some()
        || st.open.is_some()
        || st.basket.is_some()
        || st.div_yield_mean.is_finite()
        || st.macro_panel.is_some()
    {
        blocks.push(level_block(p));
    }
    if let Some(sd) = st.sat {
        blocks.push(format!(
            "    \"satellite\": {{ \"corr\": {}, \"absCorr\": {}, \"beta\": {}, \"volRatio\": {}, \
             \"kurtRatio\": {}, \"ac1Ratio\": {}, \"ac20Ratio\": {}, \"d5Ratio\": {}, \
             \"d10Ratio\": {}, \"crashRatio\": {} }}",
            num(sd.corr),
            num(sd.abs_corr),
            num(sd.beta),
            num(sd.vol_ratio),
            num(sd.kurt_ratio),
            num(sd.ac1_ratio),
            num(sd.ac20_ratio),
            num(sd.d5_ratio),
            num(sd.d10_ratio),
            num(sd.crash_ratio)
        ));
    }
    if let Some(b) = st.bars {
        blocks.push(format!(
            "    \"barRange\": {{ \"rangeOverCcvol\": {}, \"rangeAcf1\": {}, \"rangeDownup\": {} }}",
            num(b.range_over_ccvol),
            num(b.range_acf1),
            num(b.range_downup)
        ));
        if b.vol_sd.is_finite() {
            blocks.push(format!(
                "    \"barVolume\": {{ \"volSd\": {}, \"volCorrRange\": {} }}",
                num(b.vol_sd),
                num(b.vol_corr_range)
            ));
        }
    }
    if st.div_yield_mean.is_finite() {
        blocks.push(format!(
            "    \"dividend\": {{ \"meanYield\": {} }}",
            num(st.div_yield_mean)
        ));
    }
    if let Some(os) = st.open {
        blocks.push(format!(
            "    \"open\": {{ \"overnightShare\": {}, \"worstGapShare\": {}, \"allGapShare\": {} }}",
            num(os.overnight_share),
            num(os.worst_gap_share),
            num(os.all_gap_share)
        ));
    }
    if let Some(b) = st.basket {
        blocks.push(format!(
            "    \"basket\": {{ \"nameVolRatio\": {}, \"nameGaps\": {}, \"nameD20\": {}, \"aggCorr\": {}, \
             \"aggBeta\": {}, \"aggVolRatio\": {}, \"pairCorr\": {}, \"idioShare\": {}, \
             \"tailCoincidence\": {}, \"pairCorrWorst\": {}, \"pairCorrMid\": {}, \
             \"nameD20Spread\": {} }}",
            num(b.name_vol_ratio),
            num(b.name_gaps),
            num(b.name_d20),
            num(b.agg_corr),
            num(b.agg_beta),
            num(b.agg_vol_ratio),
            num(b.pair_corr),
            num(b.idio_share),
            num(b.tail_coincidence),
            num(b.pair_corr_worst),
            num(b.pair_corr_mid),
            num(b.name_d20_spread)
        ));
    }
    blocks.extend(st.macro_panel.iter().map(macro_readings_block));
    if blocks.is_empty() {
        "  \"channels\": {},".to_string()
    } else {
        format!("  \"channels\": {{\n{}\n  }},", blocks.join(",\n"))
    }
}

/// The macro panel's `channels.macro` block: the readings its rows grade, each member naming its
/// counterpart and natural cadence — the routing a consumer's point-in-time loader needs, in the
/// data rather than in prose. NaN prints as null, the `fidelity` rows' rule.
fn macro_readings_block(ms: &MacroStats) -> String {
    let num = |x: f64| {
        if x.is_nan() {
            "null".to_string()
        } else {
            ef(x)
        }
    };
    // A per-path spread beside each pooled statistic: `[p5, p50, p95]` of the per-path readings,
    // the width of the null a single path sits in.
    let sp = |x: Spread| format!("[{}, {}, {}]", num(x.p5), num(x.p50), num(x.p95));
    let members: Vec<String> = (0..9)
        .map(|j| {
            let m = &ms.members[j];
            let (r2s, pres, lags) = ms.member_spread[j];
            format!(
                "      {{ \"column\": {}, \"counterpart\": {}, \"cadence\": {}, \"ac1\": {}, \"acK\": {}, \
                 \"r2fwd60\": {}, \"warn20\": {}, \"fired\": {}, \"lag20\": {}, \"lag10\": {}, \
                 \"fired10\": {}, \"prePeak\": {},\n        \
                 \"perPath\": {{ \"r2fwd60\": {}, \"prePeak\": {}, \"lag20\": {} }} }}",
                json_str(macro_k::COLUMNS[j]),
                json_str(macro_k::COUNTERPARTS[j]),
                json_str(macro_k::CADENCE[j]),
                num(m.ac1),
                num(m.ac_k),
                num(m.r2fwd60),
                num(m.warn),
                num(m.warn_fired),
                num(m.lag),
                num(m.lag10),
                num(m.fired10),
                num(m.pre_peak),
                sp(r2s),
                sp(pres),
                sp(lags)
            )
        })
        .collect();
    format!(
        "    \"macro\": {{ \"null\": {}, \"episodes\": {}, \"invShare\": {}, \"invDur\": {}, \"vrp\": {}, \"r2rv\": {}, \
         \"hazard20q\": {}, \"hazard20y\": {}, \"hazard10q\": {}, \"p20q\": {},\n      \
         \"perPath\": {{ \"invShare\": {}, \"vrp\": {}, \"r2rv\": {}, \"hazard20q\": {} }},\n      \
         \"members\": [\n{}\n      ] }}",
        ms.sibling,
        ms.episodes,
        num(ms.inv_share),
        num(ms.inv_dur),
        num(ms.vrp),
        num(ms.r2rv),
        num(ms.hazard20q),
        num(ms.hazard20y),
        num(ms.hazard10q),
        num(ms.p20q),
        sp(ms.inv_share_spread),
        sp(ms.vrp_spread),
        sp(ms.r2rv_spread),
        sp(ms.hazard_spread),
        members.join(",\n")
    )
}

fn str_list<S: AsRef<str>>(v: &[S]) -> String {
    let items: Vec<String> = v.iter().map(|s| json_str(s.as_ref())).collect();
    format!("[{}]", items.join(", "))
}

/// WHICH RULER, and WHICH SERIES — the gate block's scope, as three JSON lines.
///
/// Without the first, a `-anchors nasdaq` run's verdict is indistinguishable from an S&P one in
/// its own provenance record. Without the rest, a PASS sits beside emitted columns it never
/// examined: the gate measures `price` and `bond`, so a satellite leg or a sampled bar is outside
/// its scope entirely, and `logSat` is exactly the column a consumer would take as their second
/// index. Same doctrine as `fidelityUnanchored` — name what was not graded, in the artifact that
/// carries the verdict.
///
/// `gradedSeries` is the authoritative half: the verdict is computed from THOSE series — `price`
/// and `bond` always, and each channel column exactly when its `satellite *` / `bar *` rows ran,
/// which is the same condition under which the column exists. The channel list is scoped to
/// optional channels rather than to every other column, so it cannot be read as a claim that
/// `rate`/`cpi`/`liq` are graded — they are context, and some feed a band only indirectly.
fn gate_scope_lines(a: Anchors, p: &Path) -> String {
    // The presence conditions are `sat_stats`'/`bar_stats`' own — they return `Some` exactly
    // when these columns are non-empty — so a column is listed the session its rows exist.
    let basket_cols = basket_columns(p);
    let mut graded = vec!["price", "bond"];
    graded.extend(channel_columns(p));
    graded.extend(basket_cols.iter().map(String::as_str));
    // The field that says a column reached the file UNGRADED: `logSat` is covered by the
    // `satellite *` rows and the bar columns by the `bar *` rows, and the one case today is a
    // NULL macro panel (`-macronull`), whose four columns are a sibling path's and grade nothing
    // by construction — said here, in the artifact, rather than in a doc nobody reads beside the
    // data.
    let null_panel = p.macro_panel.as_ref().is_some_and(|m| m.sibling);
    let ungraded: Vec<&str> = if null_panel {
        macro_columns(p).to_vec()
    } else {
        Vec::new()
    };
    if !null_panel {
        graded.extend(macro_columns(p));
    }
    format!(
        "    \"anchors\": {},\n    \"gradedSeries\": {},\n    \"ungradedChannelSeries\": {},",
        json_str(a.name),
        str_list(&graded),
        str_list(&ungraded)
    )
}

/// Everything that licenses the TSV: which (world, seed, path) produced it, on what calendar,
/// and what the world's two gate verdicts and fidelity ratios were. A warning printed to stderr
/// at export time does not survive the file being moved; this does.
///
/// `schema` and `version` answer different questions and neither substitutes for the other:
/// `schema` says whether a reader can parse the file, `version` says which release's simulator
/// wrote it. The default world moved at 0.19.1 and again at 0.19.2, so two files with identical
/// columns and identical schema can still be incomparable — a consumer that pins its calibration
/// to a release checks `version`, and one that needs the exact parameters reads `world` below.
/// `schema` went 1 -> 2 when `version` was added, so its absence is detectable rather than
/// ambiguous.
#[expect(
    clippy::too_many_arguments,
    reason = "the sidecar records the whole provenance tuple; grouping it would only move the list"
)]
fn write_emit_sidecar(
    a: Anchors,
    file: &str,
    p: &Path,
    k: usize,
    w: &World,
    years: usize,
    seed: u64,
    start_ymd: &str,
    dates: &[String],
    gate_st: &WorldStats,
    gate_paths: usize,
    gate_years: usize,
    gate_rows: &[FidelityRow],
) {
    let n = p.price.len();
    let realism_bad = failed_in(a, gate_st, GateClass::Realism);
    let mechanism_bad = failed_in(a, gate_st, GateClass::Mechanism);
    let fidelity_bad = failed_in(a, gate_st, GateClass::Fidelity);
    let num = |x: f64| -> String {
        if x.is_nan() {
            "null".to_string()
        } else {
            ef(x)
        }
    };
    // `aggregation` and `horizonYears` are the terms of the comparison, and they are in the DATA
    // because prose does not travel: a consumer holding this file has no access to the report's
    // note, and an `ensemble-extreme` row divided by its anchor gives a quotient that grades the
    // ensemble size. Such a row carries `ratio: null` and a `percentile` instead — where the record
    // falls among single histories of its own length — so the division cannot be made by accident.
    // `miss` is the admissible interval NEGATED for both kinds, so a row that could not be measured
    // reports a miss rather than a clean bill of health.
    let fidelity: Vec<String> = gate_rows
        .iter()
        .map(|r| {
            // Two pieces, not one line-continued literal: `\<newline>` keeps the source
            // indentation inside the string, and the twins must emit byte-identical JSON.
            let head = format!(
                "    {{ \"name\": {}, \"model\": {}, \"real\": {}, ",
                json_str(r.name),
                num(r.model),
                num(r.real)
            );
            let tail = format!(
                "\"aggregation\": {}, \"horizonYears\": {}, \"ratio\": {}, \"percentile\": {}, \"miss\": {} }}",
                json_str(r.aggregation()),
                r.horizon_years,
                r.ratio.map_or_else(|| "null".to_string(), num),
                r.pctile
                    .map_or_else(|| "null".to_string(), |x| x.to_string()),
                r.miss()
            );
            head + &tail
        })
        .collect();
    let world_body = world_json_body(w);
    let verdict = |bad: &[String]| if bad.is_empty() { "PASS" } else { "FAIL" };
    let calendar = if start_ymd.is_empty() {
        "synthetic-365-252"
    } else {
        "weekday"
    };
    let json = [
        "{".to_string(),
        "  \"generator\": \"market_sim\",".to_string(),
        format!("  \"version\": {},", json_str(VERSION)),
        format!("  \"schema\": {EMIT_SCHEMA},"),
        format!("  \"file\": {},", json_str(file)),
        format!("  \"columns\": {},", {
            let mut cols: Vec<String> = EMIT_COLUMNS.iter().map(|c| c.to_string()).collect();
            cols.extend(channel_columns(p).into_iter().map(str::to_string));
            cols.extend(basket_columns(p));
            cols.extend(macro_columns(p).iter().map(|c| c.to_string()));
            let refs: Vec<&str> = cols.iter().map(String::as_str).collect();
            str_list(&refs)
        }),
        "  \"header\": true,".to_string(),
        "  \"path\": {".to_string(),
        format!("    \"index\": {k},"),
        format!("    \"baseSeed\": {seed},"),
        "    \"seedStride\": 7919,".to_string(),
        format!("    \"pathSeed\": {},", seed + k as u64 * 7919),
        format!("    \"years\": {years},"),
        format!("    \"sessions\": {n},"),
        format!("    \"burnIn\": {BURN_IN},"),
        format!("    \"sessionsPerYear\": {DAYS_PER_YEAR},"),
        format!("    \"calendar\": {},", json_str(calendar)),
        format!("    \"startDate\": {},", json_str(&dates[0])),
        format!("    \"endDate\": {}", json_str(&dates[n - 1])),
        "  },".to_string(),
        "  \"world\": {".to_string(),
        world_body.join(",\n"),
        "  },".to_string(),
        "  \"gate\": {".to_string(),
        format!("    \"ensemblePaths\": {gate_paths},"),
        format!("    \"ensembleYears\": {gate_years},"),
        gate_scope_lines(a, p),
        format!("    \"realism\": {},", json_str(verdict(&realism_bad))),
        format!("    \"mechanism\": {},", json_str(verdict(&mechanism_bad))),
        format!("    \"fidelity\": {},", json_str(verdict(&fidelity_bad))),
        format!("    \"realismFailed\": {},", str_list(&realism_bad)),
        format!("    \"mechanismFailed\": {},", str_list(&mechanism_bad)),
        format!("    \"fidelityFailed\": {},", str_list(&fidelity_bad)),
        // Bands the anchors could not grade in this world, with the reason. Without this a path
        // emitted from (say) a 1.8-year-duration world shows fidelity PASS and nothing says the
        // depth level was never graded at all — a consumer would read levels off it.
        format!(
            "    \"fidelityUnanchored\": {},",
            str_list(&unanchored_in(gate_st))
        ),
        // THE PROFILE ROW'S READINGS. `fidelity` below carries the loss rows, and the
        // variance-ratio profile is a gate row over four rungs, not a loss row: without this a
        // consumer learns the profile passed and cannot read it.
        format!(
            "    \"varianceRatio\": {{ {} }}",
            VAR_RATIO_LADDER
                .iter()
                .map(|&q| format!("\"{q}\": {}", num(vr_of(gate_st, q))))
                .collect::<Vec<_>>()
                .join(", ")
        ),
        "  },".to_string(),
        channel_readings_block(gate_st, p),
        "  \"fidelity\": [".to_string(),
        fidelity.join(",\n"),
        "  ]".to_string(),
        "}".to_string(),
    ];
    write_or_die(&sidecar_name(file), &format!("{}\n", json.join("\n")));
}

/// One `"key": value` line of a machine-written world block, as (key, value). `None` for anything
/// else, which is how the block's braces and a member's provenance rows are skipped.
fn json_field(l: &str) -> Option<(String, String)> {
    let (k, rest) = l.strip_prefix('"')?.split_once("\":")?;
    if k.is_empty() || !k.chars().all(|c| c.is_ascii_alphanumeric()) {
        return None;
    }
    let v = rest.trim().trim_end_matches(',').trim();
    if v.is_empty() || v == "{" {
        return None;
    }
    Some((k.to_string(), v.to_string()))
}

/// Member `index` of an exported calibration archive, as ARGUMENTS.
///
/// The `world` block is keyed by FLAG NAME by design -- `macro`, never the field's `macro_panel`
/// -- so a loader has only to lowercase each key. Turning the block into arguments and letting the
/// ordinary flag loop set them keeps ONE rule where a second copy of 76 setters would be a parity
/// surface, and it gives the member `-atrelease`'s precedence for free: a dial flag after
/// `-worldset` still overrides it.
///
/// The expected keys ARE `world_json_body`'s, read off its own output, so a dial added to the world
/// cannot be silently skipped here. A block not carrying exactly them is REFUSED: a missing dial
/// would take the shipped default, which is a different world wearing a member's name.
///
/// `Err` rather than `cli_die` so the refusals are testable -- `cli_die` exits the process, where
/// the Scala twin's `usage` can have its exit swapped for a throw. Same rules, both sides.
fn world_set_flags(text: &str, file: &str, index: i64) -> Result<Vec<String>, String> {
    // The file the search writes: one `"key": value` a line inside a member's `world` block. A
    // general JSON parser would buy nothing here and cost a dependency -- a foreign file fails the
    // key check below, which is the check that matters.
    let ls: Vec<&str> = text.lines().map(str::trim).collect();
    let opens: Vec<usize> = (0..ls.len()).filter(|&i| ls[i] == "\"world\": {").collect();
    if opens.is_empty() {
        return Err(format!(
            "-worldset {file} holds no world block; -export writes the file this reads"
        ));
    }
    let mut members: Vec<(i64, &[&str])> = Vec::with_capacity(opens.len());
    for &i in &opens {
        let Some(end) = (i + 1..ls.len()).find(|&j| ls[j] == "}") else {
            return Err(format!(
                "-worldset {file}: the world block at line {} never closes",
                i + 1
            ));
        };
        let id = ls[..i].iter().rev().find_map(|l| match json_field(l) {
            Some((k, v)) if k == "member" => v.parse::<i64>().ok(),
            _ => None,
        });
        let Some(id) = id else {
            return Err(format!(
                "-worldset {file}: the world block at line {} has no member number",
                i + 1
            ));
        };
        members.push((id, &ls[i + 1..end]));
    }
    let Some(block) = members.iter().find(|(k, _)| *k == index).map(|(_, b)| *b) else {
        return Err(format!(
            "-worldindex {index} is not in {file}: it holds {} members, {} to {}",
            members.len(),
            members.iter().map(|(k, _)| *k).min().unwrap_or(0),
            members.iter().map(|(k, _)| *k).max().unwrap_or(0)
        ));
    };
    let mut fields: Vec<(String, String)> = Vec::with_capacity(block.len());
    for l in block {
        let Some(kv) = json_field(l) else {
            return Err(format!(
                "-worldset {file} member {index}: cannot read [{l}] as a world field"
            ));
        };
        fields.push(kv);
    }
    let want: Vec<String> = world_json_body(&default_world())
        .iter()
        .filter_map(|l| json_field(l.trim()).map(|(k, _)| k))
        .collect();
    let got: Vec<String> = fields.iter().map(|(k, _)| k.clone()).collect();
    let mut seen = got.clone();
    let mut expect = want.clone();
    seen.sort();
    expect.sort();
    if seen != expect {
        let missing: Vec<&str> = want
            .iter()
            .filter(|k| !got.contains(*k))
            .map(String::as_str)
            .collect();
        let extra: Vec<&str> = got
            .iter()
            .filter(|k| !want.contains(*k))
            .map(String::as_str)
            .collect();
        let mut m = format!("-worldset {file} member {index} does not carry this binary's dials");
        if !missing.is_empty() {
            m.push_str(&format!("; missing [{}]", missing.join(", ")));
        }
        if !extra.is_empty() {
            m.push_str(&format!("; unknown [{}]", extra.join(", ")));
        }
        return Err(m);
    }
    Ok(fields
        .iter()
        .flat_map(|(k, v)| {
            // a quoted value is a MODE name (`crowd`), which its flag takes unquoted
            let val = v
                .strip_prefix('"')
                .and_then(|x| x.strip_suffix('"'))
                .unwrap_or(v);
            [format!("-{}", k.to_lowercase()), val.to_string()]
        })
        .collect())
}

/// `-worldset F -worldindex K` runs member K of an exported archive. Pre-scanned as `-atrelease`
/// is, but it arrives as ARGUMENTS placed BEFORE the command line's own, so a dial flag after it
/// still overrides the member.
fn with_world_set(args: Vec<String>) -> Vec<String> {
    let Some(i) = args.iter().position(|a| a == "-worldset") else {
        if args.iter().any(|a| a == "-worldindex") {
            cli_die("-worldindex wants -worldset");
        }
        return args;
    };
    if args[i + 1..].iter().any(|a| a == "-worldset") {
        cli_die("-worldset given twice");
    }
    if args.iter().any(|a| a == "-atrelease") {
        cli_die("-worldset and -atrelease each name a whole world; give one");
    }
    let file = args
        .get(i + 1)
        .cloned()
        .unwrap_or_else(|| cli_die("-worldset wants an exported archive file"));
    let index = match args.iter().position(|a| a == "-worldindex") {
        None => 0,
        Some(j) => args
            .get(j + 1)
            .and_then(|v| v.parse::<i64>().ok())
            .unwrap_or_else(|| cli_die("-worldindex wants a member number")),
    };
    if !std::path::Path::new(&file).exists() {
        cli_die(&format!("-worldset {file} does not exist"));
    }
    let text = std::fs::read_to_string(&file)
        .unwrap_or_else(|e| cli_die(&format!("-worldset {file} cannot be read: {e}")));
    let mut out = world_set_flags(&text, &file, index).unwrap_or_else(|m| cli_die(&m));
    out.extend(args);
    out
}

#[expect(
    clippy::too_many_lines,
    reason = "one linear report, mirroring the Scala twin's main statement for statement"
)]
#[expect(
    clippy::cognitive_complexity,
    reason = "one linear dispatch over the CLI, as in the Scala twin"
)]
pub fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let args = with_world_set(args);

    let mut paths = 200usize;
    let mut years = 100usize;
    let mut seed = DEFAULT_SEED;
    let mut emit = String::new();
    let mut emit_path = 0usize;
    let mut emit_all = false;
    let mut emit_from = 0usize;
    let mut emit_start = String::new();
    let mut emit_gate = 200usize;
    let mut gate_req = gate_default();
    let mut validate = false;
    let mut buffer_report = false;
    let mut dd_shape = false;
    let mut power_report = false;
    let mut release_report = false;
    let mut cross_asset = false;
    let mut noise_report = false;
    let mut power_arms: Vec<usize> = POWER_ARMS_DEFAULT.to_vec();
    let mut power_years: Vec<usize> = POWER_YEARS_DEFAULT.to_vec();
    let mut strategies = false;
    let mut single = false;
    let mut cost = 0.0010f64;
    let mut fitness_only = false;
    let mut paths_given = false;
    let mut years_given = false;
    let mut calibrate_n = 0usize;
    // defaults = a random search against the fitness loss, scored at 100-year paths, lightly
    // rounded. Reachable ONLY because depth, trendShare, drift and crowdImpact are in the search;
    // held fixed, as all four were until 0.19.1, no sample gets here. Loss 3.13-3.57 across five
    // scoring seeds against the pre-0.19.1 defaults' 5.77-6.11. Those figures are under the
    // equal-precision objective that search ran against. The 0.20.0 defaults come from a
    // re-search under the measured-precision objective (see `wgt` and the CHANGELOG): the loss
    // now prices clustering at 2.2x, which is why `stress` could move UP to 5.6 with the
    // clustering regression bought knowingly (1.08) instead of blindly — the guard below is
    // HISTORY explaining the 0.19.1/0.19.2 choices, not a description of the current trade.
    //
    // 0.21.0 re-searched again with `fundVol` in the ranges for the first time and the depth rungs
    // stated against a real relation (see `EQUITY_D10_CORR`). Two search results were declined by
    // hand, both for reasons the loss cannot see. `crowdImpact` was pushed to its 0.01 range
    // floor, which reads 0.9% of the noise term on `mean_crowd_flow` — the reflexive channel
    // switched off, which is the defect that diagnostic exists to catch; pinned back at 0.07 it
    // reads 6.7%, and the pin also BOUGHT volatility (16.03 against 15.38) and crash depth.
    // `refuge` was raised 0.11 -> 0.159, which took bond volatility to 1.12x duration, outside its
    // band; returned to 0.11 it reads 1.03 and the equity side does not move at all. And `easing`
    // was cut 0.046 -> 0.037, which is not a tuning question: the Scala twin's `usage` interpolates
    // this field and asserts it IS one full real easing cycle, and real cycles run about 5 rate
    // points (2008: 5.25 -> 0.25; 2001: 6.5 -> 1.0). At 0.037 the help text states something false,
    // so the value is anchored the way `duration` is and the search does not get to move it.
    //   `inflSize` was cut 0.10 -> 0.084 and reverted, for the SECOND time and the same reason:
    // 0.20.0's search proposed the same cut and it was reverted then because it breaks the d=5.70
    // rung of the `-crossasset` bond ladder, which no version of the loss can see. Measured here:
    // 0.084 puts that rung over its floor on 1 seed of 4, 0.10 on 3 of 4. The cost is `bond
    // infl-crash` 1.08 -> 1.28, on the row whose own `-noise` measurement says one 24-year record
    // barely produces a reading. A parameter the search keeps proposing to cut and that keeps
    // having to be put back is a candidate for the identity list; it has not been promoted yet
    // because unlike `duration` it names no single published number.
    //
    // Scored on the MEDIAN of three seeds, not one: a single-seed refinement here found a 1.687
    // that was a 2.15 median over five seeds. Depth-rung agreement is cheap to overfit because
    // the relation's denominator moves with the sample.
    //
    // `stress` IS NOT AT THE OBJECTIVE'S MINIMUM, deliberately, and has now been moved DOWN twice
    // for the same reason. The liquidity spiral is a single amplifier producing volatility, fat
    // tails AND volatility clustering together — `stress` alone moves ac1 from 0.160 at 3.4 to
    // 0.420 at 7.0 — so buying tails always buys clustering with them, and clustering above 1.0
    // means volatility is more forecastable here than in the record, which flatters every rule
    // that forecasts it. 0.19.1 chose 5.4 over the then-minimum 5.9 on that trade; 0.19.2 chose
    // 5.1 over 5.4 on the same one, because capping the rate cut (see `easing`) removed a
    // discount-channel cushion in crashes and pushed clustering from 1.08 to 1.13 at unchanged
    // `stress`. 5.1 with depth 16.6 returns clustering to 1.06 and costs kurtosis 0.46 -> 0.42,
    // which is a recorded scope exclusion either way. Do not "optimise" `stress` upward without
    // re-reading this: the objective does not weigh the clustering regression heavily enough.
    //   `depth` moved 16.3 -> 16.6 in the same step and for a different reason: the same lost
    //   cushion raised the crash rate from 1.20 to 1.38, and depth is the dial that carries crash
    //   frequency. It buys back a third of it (1.32). The rest is the mechanism's price, stated
    //   in the CHANGELOG rather than tuned away.
    //   The clustering figures here are against the CENTURY anchor. Measured against the 72-year
    //   one this shipped with, the same worlds read 0.90 / 1.20 / 1.33 — the horizon mismatch, not
    //   a change in the model.
    //
    // KURTOSIS AND CLUSTERING COULD NOT BOTH BE RIGHT THROUGH `stress`: at stress 7.5 kurtosis
    // reached 26.4 against a real 28 and clustering hit 1.67, failing its realism band. That was the
    // measured reason the kurtosis MISS stood, and the note it replaced was more precise than "no
    // slow valuation cycle" — the cycle is why there was no SECOND channel for tails, not why that
    // one could not reach them.
    //
    // 0.21.0 ADDED THE SECOND CHANNEL and the trade-off disappeared with it. `jump_var` 0.10 moves a
    // tenth of the equity flow's variance from diffusion into a volatility-clustered compensated
    // jump; kurtosis goes 0.45 -> 1.00 and clustering IMPROVES, 1.11 -> 1.03 and 1.15 -> 1.05,
    // because variance taken out of the diffusion shortens the persistence the clamped volatility
    // process was over-supplying. Volatility, return per vol and crash rate all improved too, and
    // the calibration loss fell 1.947 -> 1.575 with no other parameter touched — almost all of it
    // from CLUSTERING, since kurtosis's own weight collapsed once its sdRel was re-measured. The
    // channel is defended by the target it was not aimed at. The lesson is not
    // about jumps: an "X and Y cannot both be right" finding is a statement about the CHANNEL that
    // was tried, and stays one until someone tries a different channel.
    //
    // ASYMMETRIC RECOVERY closed the crash-rate and shallow-median misses TOGETHER, because they
    // were one defect.  The model spent HALF the real record's time below 15% (d15 0.115 against
    // SPY's 0.240) while crossing 15% 40% MORE often -- its deep drawdowns recovered three times too
    // fast.  `recoveryDrag` weakens value arbitrage as a drawdown deepens, which is what depleted
    // capital does; `crashes/century` goes 1.32 -> 1.13 and `median depth %` 0.84 -> 0.95, and
    // `-noise` moves the real anchors from the 4th and 6th percentiles of the model-implied spread
    // to the 33rd and 30th.  Five mechanisms were tried first and all failed -- see the CHANGELOG;
    // the one that worked keys on distance below the PEAK, which is what the statistic is about,
    // where a pull convex in the gap to FAIR VALUE cannot tell a deep drawdown from an ordinary one.
    //
    // THE MACRO-DISASTER CHANNEL (0.22.1) is the "channel that deepens a crash without adding
    // low-frequency variance" this note used to call for and attribute to the absent valuation
    // cycle — it buys the exemption through RARITY, not through a cycle, and it carried the
    // CENTURY tail: the record's -84.1% moved from the 1st percentile of model centuries to the
    // 18th. What a valuation cycle alone could still add is documented at `disaster_rate`'s World
    // field and in docs/MarketSimWorlds.md: valuation-LED deep crashes (2000-02: multiples
    // collapse, earnings fine) and peaks that sit far above fair value before they fall. Every
    // deep crash here starts from a peak AT fair value, and a consumer reading the emitted
    // `fundamental` column or `-strategies`' crash-type conditioning sees the shifted mix.
    //
    // ONE KNOWN BIAS DIRECTION, netted away nowhere else: the DEEP drawdown rung reads 2.36 (d20),
    // partly the drag's cost — a slower climb out of a deep hole is more time deep — and, since
    // 0.22.1, partly the RULER's: the relation is fitted on 2001-2026 funds, a window with no
    // depression in it, while the model's own share of sessions >20% under water (0.126, median
    // path) sits BELOW a rough reading of the real century's (~0.15-0.20). Rules keyed to a deep
    // distance from peak inherit the model number; the shallow rungs read 0.98 and 1.13.
    // Ruin rates for levered sleeves read off the ensemble MINIMUM remain UPPER BOUNDS, not
    // estimates -- 20,000 market-years of worst case, and no fund lives that long.
    // Seeded from `default_world()`, never restated. A second copy of the shipped world here is
    // the failure that function's own docstring claims not to have: it would drift silently,
    // because the only thing comparing the two is a `-releases` run noticing that its 0.19.2 row
    // and its `current` row disagree.
    //
    // `-atrelease` swaps the BASE the dials seed from — the frozen world of a past release, so a
    // pinned consumer can take binary fixes without taking a recalibration. Resolved before the
    // flag loop on purpose: explicit dial flags override the base wherever they sit on the
    // command line, where a base applied mid-loop would clobber the flags before it. The gate
    // still grades with the CURRENT rulers — a pre-0.23.0 world has no valuation cycle and
    // honestly fails the valuation mechanism row AND the valuation dispersion band; pair with
    // `-gate realism` to require only what such a world claims, and read the rest as disclosure.
    let (dw, recipe_anchors) = match args.iter().position(|a| a == "-atrelease") {
        None => (default_world(), None),
        Some(i) => {
            if args[i + 1..].iter().any(|a| a == "-atrelease") {
                cli_die("-atrelease given twice");
            }
            let v = args
                .get(i + 1)
                .unwrap_or_else(|| cli_die("-atrelease wants a version or a recipe name"));
            named_world(v).unwrap_or_else(|| {
                cli_die(&format!(
                    "-atrelease {v} names no release or recipe this binary can reproduce; it has [{}], {VERSION} and [{}]",
                    releases().iter().map(|(v, _)| *v).collect::<Vec<_>>().join(", "),
                    recipes().iter().map(|(n, _, _)| *n).collect::<Vec<_>>().join(", ")
                ))
            })
        }
    };
    let mut trend_share = dw.trend_share;
    let mut depth = dw.depth;
    let mut stress = dw.stress;
    let mut beta = dw.beta;
    let mut vol_persist = dw.vol_persist;
    let mut vol_of_vol = dw.vol_of_vol;
    // A recipe carries the anchor set it was verified against; `-anchors` in the loop overrides.
    let mut anchor_spec = recipe_anchors.unwrap_or("sp500").to_string();
    let mut recovery_drag = dw.recovery_drag;
    let mut recovery_floor = dw.recovery_floor;
    let mut halt_limit = dw.halt_limit;
    let mut disaster_rate = dw.disaster_rate;
    let mut disaster_size = dw.disaster_size;
    let mut disaster_len = dw.disaster_len;
    let mut disaster_recover = dw.disaster_recover;
    let mut disaster_rec_len = dw.disaster_rec_len;
    let mut belief_share = dw.belief_share;
    let mut belief_years = dw.belief_years;
    let mut cap_years = dw.cap_years;
    let mut cap_window = dw.cap_window;
    let mut leverage = dw.leverage;
    let mut down_shock = dw.down_shock;
    let mut jump_var = dw.jump_var;
    let mut jump_skew = dw.jump_skew;
    let mut news_rate = dw.news_rate;
    let mut news_size = dw.news_size;
    let mut refuge_days = dw.refuge_days;
    let mut sat_beta = dw.sat_beta;
    let mut sat_idio = dw.sat_idio;
    let mut range_scale = dw.range_scale;
    let mut range_down = dw.range_down;
    let mut vol_idio = dw.vol_idio;
    let mut div_yield = dw.div_yield;
    let mut overnight = dw.overnight;
    let mut basket = dw.basket;
    let mut basket_beta = dw.basket_beta;
    let mut basket_sector = dw.basket_sector;
    let mut basket_idio = dw.basket_idio;
    let mut basket_gaps = dw.basket_gaps;
    let mut basket_drift = dw.basket_drift;
    let mut macro_panel = dw.macro_panel;
    let mut macro_null = dw.macro_null;
    let mut lev_gain = dw.lev_gain;
    let mut stress_scale = dw.stress_scale;
    let mut lev_persist = dw.lev_persist;
    let mut noise_asym = dw.noise_asym;
    let mut noise_asym_phi = dw.noise_asym_phi;
    let mut noise_asym_cap = dw.noise_asym_cap;
    let mut vol_resp = dw.vol_resp;
    let mut vol_resp_phi = dw.vol_resp_phi;
    let mut vol_resp_cap = dw.vol_resp_cap;
    let mut vol_resp_attack = dw.vol_resp_attack;
    let mut jump_resp = dw.jump_resp;
    let mut stress_adapt = dw.stress_adapt;
    let mut bust_amp = dw.bust_amp;
    let mut slow_share = dw.slow_share;
    let mut slow_vol = dw.slow_vol;
    let mut slow_lev = dw.slow_lev;
    let mut slow_phi = dw.slow_phi;
    let mut slow_perm = dw.slow_perm;
    let mut slow_beta = dw.slow_beta;
    let mut joint_emit = String::new();
    let mut bars_emit = String::new();
    let mut jump_rate = dw.jump_rate;
    let mut value_pull = dw.value_pull;
    let mut crowd_name = crowd_name(dw.crowd);
    let mut crowd_impact = dw.crowd_impact;
    let mut panic_k = dw.panic;
    let mut drift = dw.drift;
    let mut fund_vol = dw.fund_vol;
    let mut rate_mean = dw.rate_mean;
    let mut duration = dw.duration;
    let mut easing = dw.easing;
    let mut unwind = dw.unwind;
    let mut refuge = dw.refuge;
    let mut infl_prob = dw.infl_prob;
    let mut infl_size = dw.infl_size;
    let mut infl_speed = dw.infl_speed;
    let mut rate_speed = dw.rate_speed;
    let mut discount = dw.discount;
    let mut margin = dw.margin;
    let mut it = args.iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            // Bare version on stdout and nothing else, so a caller can gate on it without
            // parsing: `[ "$(market_sim -version)" = "$want" ] || exit 1`. Handled where it is
            // seen, so it answers before any other flag is validated.
            "-version" => {
                println!("{VERSION}");
                std::process::exit(0)
            }
            "-paths" => {
                paths = req_usize(&mut it, "-paths");
                paths_given = true;
            }
            "-years" => {
                years = req_usize(&mut it, "-years");
                years_given = true;
            }
            "-seed" => seed = req_u64(&mut it, "-seed"),
            "-emit" => emit = req_arg(&mut it, "-emit").clone(),
            "-emitpath" => emit_path = req_usize(&mut it, "-emitpath"),
            "-emitall" => emit_all = true,
            "-emitfrom" => emit_from = req_usize(&mut it, "-emitfrom"),
            "-emitstart" => emit_start = req_arg(&mut it, "-emitstart").clone(),
            "-emitgate" => emit_gate = req_usize(&mut it, "-emitgate"),
            "-gate" => gate_req = parse_gate(req_arg(&mut it, "-gate")),
            "-validate" => validate = true,
            "-buffer" => buffer_report = true,
            "-ddshape" => dd_shape = true,
            "-power" => power_report = true,
            "-releases" => release_report = true,
            "-crossasset" => cross_asset = true,
            "-noise" => noise_report = true,
            "-powerarms" => power_arms = req_usize_list(&mut it, "-powerarms"),
            "-poweryears" => power_years = req_usize_list(&mut it, "-poweryears"),
            "-strategies" => strategies = true,
            "-single" => single = true,
            "-cost" => cost = req_f64(&mut it, "-cost"),
            "-fitness" => fitness_only = true,
            "-calibrate" => calibrate_n = req_usize(&mut it, "-calibrate"),
            "-crowd" => crowd_name = req_arg(&mut it, "-crowd").clone(),
            "-trendshare" => trend_share = req_f64(&mut it, "-trendshare"),
            "-depth" => depth = req_f64(&mut it, "-depth"),
            "-stress" => stress = req_f64(&mut it, "-stress"),
            "-beta" => beta = req_f64(&mut it, "-beta"),
            "-volpersist" => vol_persist = req_f64(&mut it, "-volpersist"),
            "-volofvol" => vol_of_vol = req_f64(&mut it, "-volofvol"),
            "-anchors" => anchor_spec = req_arg(&mut it, "-anchors").clone(),
            // Applied in the pre-scan that seeded `dw`; consumed here so the loop does not
            // reject it as unknown.
            "-atrelease" => {
                req_arg(&mut it, "-atrelease");
            }
            // Likewise: the pre-scan already turned the member into the arguments ahead of
            // these.
            "-worldset" => {
                req_arg(&mut it, "-worldset");
            }
            "-worldindex" => {
                req_arg(&mut it, "-worldindex");
            }
            "-recoverydrag" => recovery_drag = req_f64(&mut it, "-recoverydrag"),
            "-recoveryfloor" => recovery_floor = req_f64(&mut it, "-recoveryfloor"),
            "-disasterrate" => disaster_rate = req_f64(&mut it, "-disasterrate"),
            "-disastersize" => disaster_size = req_f64(&mut it, "-disastersize"),
            "-disasterlen" => disaster_len = req_f64(&mut it, "-disasterlen"),
            "-disasterrecover" => disaster_recover = req_f64(&mut it, "-disasterrecover"),
            "-disasterreclen" => disaster_rec_len = req_f64(&mut it, "-disasterreclen"),
            "-beliefshare" => belief_share = req_f64(&mut it, "-beliefshare"),
            "-beliefyears" => belief_years = req_f64(&mut it, "-beliefyears"),
            "-capyears" => cap_years = req_f64(&mut it, "-capyears"),
            "-capwindow" => cap_window = req_f64(&mut it, "-capwindow"),
            "-haltlimit" => halt_limit = req_f64(&mut it, "-haltlimit"),
            "-leverage" => leverage = req_f64(&mut it, "-leverage"),
            "-downshock" => down_shock = req_f64(&mut it, "-downshock"),
            "-jumpvar" => jump_var = req_f64(&mut it, "-jumpvar"),
            "-jumpskew" => jump_skew = req_f64(&mut it, "-jumpskew"),
            "-newsrate" => news_rate = req_f64(&mut it, "-newsrate"),
            "-newssize" => news_size = req_f64(&mut it, "-newssize"),
            "-refugedays" => refuge_days = req_f64(&mut it, "-refugedays"),
            "-satbeta" => sat_beta = req_f64(&mut it, "-satbeta"),
            "-satidio" => sat_idio = req_f64(&mut it, "-satidio"),
            "-rangescale" => range_scale = req_f64(&mut it, "-rangescale"),
            "-rangedown" => range_down = req_f64(&mut it, "-rangedown"),
            "-volidio" => vol_idio = req_f64(&mut it, "-volidio"),
            "-divyield" => div_yield = req_f64(&mut it, "-divyield"),
            "-overnight" => overnight = req_f64(&mut it, "-overnight"),
            "-basket" => basket = req_usize(&mut it, "-basket"),
            "-basketbeta" => basket_beta = req_f64(&mut it, "-basketbeta"),
            "-basketsector" => basket_sector = req_f64(&mut it, "-basketsector"),
            "-basketidio" => basket_idio = req_f64(&mut it, "-basketidio"),
            "-basketgaps" => basket_gaps = req_f64(&mut it, "-basketgaps"),
            "-basketdrift" => basket_drift = req_f64(&mut it, "-basketdrift"),
            "-macro" => macro_panel = req_usize(&mut it, "-macro"),
            "-macronull" => macro_null = req_usize(&mut it, "-macronull"),
            "-levgain" => lev_gain = req_f64(&mut it, "-levgain"),
            "-stressscale" => stress_scale = req_f64(&mut it, "-stressscale"),
            "-levpersist" => lev_persist = req_f64(&mut it, "-levpersist"),
            "-noiseasym" => noise_asym = req_f64(&mut it, "-noiseasym"),
            "-noiseasymphi" => noise_asym_phi = req_f64(&mut it, "-noiseasymphi"),
            "-noiseasymcap" => noise_asym_cap = req_f64(&mut it, "-noiseasymcap"),
            "-jumpresp" => jump_resp = req_f64(&mut it, "-jumpresp"),
            "-stressadapt" => stress_adapt = req_f64(&mut it, "-stressadapt"),
            "-bustamp" => bust_amp = req_f64(&mut it, "-bustamp"),
            "-slowshare" => slow_share = req_f64(&mut it, "-slowshare"),
            "-slowvol" => slow_vol = req_f64(&mut it, "-slowvol"),
            "-slowlev" => slow_lev = req_f64(&mut it, "-slowlev"),
            "-slowphi" => slow_phi = req_f64(&mut it, "-slowphi"),
            "-slowperm" => slow_perm = req_f64(&mut it, "-slowperm"),
            "-slowbeta" => slow_beta = req_f64(&mut it, "-slowbeta"),
            "-volresp" => vol_resp = req_f64(&mut it, "-volresp"),
            "-volrespphi" => vol_resp_phi = req_f64(&mut it, "-volrespphi"),
            "-volrespattack" => vol_resp_attack = req_f64(&mut it, "-volrespattack"),
            "-volrespcap" => vol_resp_cap = req_f64(&mut it, "-volrespcap"),
            "-jointemit" => joint_emit = req_arg(&mut it, "-jointemit").clone(),
            "-barsemit" => bars_emit = req_arg(&mut it, "-barsemit").clone(),
            "-jumprate" => jump_rate = req_f64(&mut it, "-jumprate"),
            // `-valuepull` names the DIAL, which is the key `world_json_body` writes and so
            // the flag `-worldset` synthesizes; `-value` is kept because it shipped.
            "-value" | "-valuepull" => value_pull = req_f64(&mut it, a),
            "-crowdimpact" => crowd_impact = req_f64(&mut it, "-crowdimpact"),
            "-panic" => panic_k = req_f64(&mut it, "-panic"),
            "-drift" => drift = req_f64(&mut it, "-drift"),
            "-fundvol" => fund_vol = req_f64(&mut it, "-fundvol"),
            "-ratemean" => rate_mean = req_f64(&mut it, "-ratemean"),
            "-duration" => duration = req_f64(&mut it, "-duration"),
            "-easing" => easing = req_f64(&mut it, "-easing"),
            "-unwind" => unwind = req_f64(&mut it, "-unwind"),
            "-refuge" => refuge = req_f64(&mut it, "-refuge"),
            // Rejected, not silently reinterpreted: -flight was a rate cut SPEED per year and
            // -easing is a cut CAP in rate points, so every recorded -flight value is wrong by two
            // orders of magnitude under the new mechanism and would still have run plausibly.
            "-flight" => cli_die(
                "-flight is gone: the rate cut is now a CAPPED, slowly unwound \
                 accommodation. Use -easing (cap, rate points) and -unwind (withdrawal \
                 per year). No -flight value carries over.",
            ),
            "-inflprob" => infl_prob = req_f64(&mut it, "-inflprob"),
            "-inflsize" => infl_size = req_f64(&mut it, "-inflsize"),
            "-inflspeed" => infl_speed = req_f64(&mut it, "-inflspeed"),
            "-ratespeed" => rate_speed = req_f64(&mut it, "-ratespeed"),
            "-discount" => discount = req_f64(&mut it, "-discount"),
            "-margin" => margin = req_f64(&mut it, "-margin"),
            other => cli_die(&format!("unrecognized arg [{other}]")),
        }
    }
    // Bounds that make the run meaningful. -paths 0 -emitall crashed on `written[0]`;
    // -years 0 crashed in measure. (usize already rules out the negatives Scala must check.)
    if paths < 1 {
        cli_die(&format!("-paths must be at least 1, got {paths}"));
    }
    if years < 1 {
        cli_die(&format!("-years must be at least 1, got {years}"));
    }
    // Refused rather than ignored: silently writing 0..paths-1 under a flag that asked for a
    // different range is how a chunked batch ends up with every chunk holding path 0.
    if emit_from > 0 && !emit_all {
        cli_die("-emitfrom applies to -emitall; use -emitpath for one path");
    }
    // A bad index here is the one place the rule list has to be discoverable: the report names
    // the rules but not their numbers, and the numbers are what the flag takes. Without this,
    // `-powerarms 99` panicked on an out-of-bounds index and `-powerarms 0` underflowed usize.
    {
        let n_rules = rules().len();
        if power_arms.iter().any(|&i| i < 1 || i > n_rules) {
            let list: Vec<String> = rules()
                .iter()
                .enumerate()
                .map(|(i, r)| format!("  {}  {}", i + 1, r.name))
                .collect();
            cli_die(&format!(
                "-powerarms indices must be 1-{n_rules}; the rules are:
{}",
                list.join(
                    "
"
                )
            ));
        }
        if power_years.iter().any(|&l| l < 1) {
            let got: Vec<String> = power_years.iter().map(usize::to_string).collect();
            cli_die(&format!(
                "-poweryears wants year counts of at least 1, got [{}]",
                got.join(",")
            ));
        }
    }
    // DOMAINS for the world dials. Out of domain they do not fail on their own: `-jumprate 0` with a
    // positive `-jumpvar` divides by zero in `jump_scale` and emitted a file of NaN at exit 0, and
    // `-recoveryfloor 3` inverts asymmetric recovery -- arbitrage STRONGER in a deep drawdown, the
    // documented mechanism run backwards -- into a world that then PASSES the acceptance gate.
    //
    // Reject what the mechanism cannot express, never what merely looks unusual -- an over-tight
    // bound breaks a sweep script for no defect. Every value recorded anywhere in this repo is
    // admitted, `-jumpvar 0` and `-haltlimit 0` (the documented disable values) included, as is
    // every range `calibrate` sweeps. A `contains` test is false for NaN, so a NaN literal is
    // refused here rather than reaching the model.
    {
        let share = |flag: &str, x: f64| {
            if !(0.0..=1.0).contains(&x) {
                cli_die(&format!(
                    "{flag} wants a share in 0..1, got {}",
                    scala_dbl(x)
                ));
            }
        };
        let below_one = |flag: &str, x: f64| {
            if !(0.0..1.0).contains(&x) {
                cli_die(&format!(
                    "{flag} wants at least 0 and below 1, got {}",
                    scala_dbl(x)
                ));
            }
        };
        let non_neg = |flag: &str, x: f64| {
            if x.is_nan() || x < 0.0 {
                cli_die(&format!(
                    "{flag} wants a non-negative number, got {}",
                    scala_dbl(x)
                ));
            }
        };
        let positive = |flag: &str, x: f64| {
            if x.is_nan() || x <= 0.0 {
                cli_die(&format!(
                    "{flag} wants a positive number, got {}",
                    scala_dbl(x)
                ));
            }
        };
        share("-trendshare", trend_share);
        share("-leverage", leverage);
        share("-downshock", down_shock);
        share("-jumpvar", jump_var);
        share("-jumprate", jump_rate);
        share("-recoveryfloor", recovery_floor);
        share("-disasterrecover", disaster_recover);
        share("-inflprob", infl_prob);
        share("-inflspeed", infl_speed);
        below_one("-volpersist", vol_persist);
        below_one("-haltlimit", halt_limit);
        positive("-depth", depth);
        positive("-duration", duration);
        non_neg("-stress", stress);
        non_neg("-beta", beta);
        non_neg("-volofvol", vol_of_vol);
        non_neg("-value", value_pull);
        non_neg("-newsrate", news_rate);
        non_neg("-newssize", news_size);
        if let Some(why) = news_budget_refusal(news_rate, news_size) {
            cli_die(&why);
        }
        non_neg("-refugedays", refuge_days);
        non_neg("-satbeta", sat_beta);
        non_neg("-satidio", sat_idio);
        non_neg("-rangescale", range_scale);
        non_neg("-rangedown", range_down);
        if range_down > 0.0 && range_scale <= 0.0 {
            cli_die("-rangedown requires -rangescale > 0: it shapes the sampled bar");
        }
        non_neg("-volidio", vol_idio);
        non_neg("-divyield", div_yield);
        non_neg("-overnight", overnight);
        non_neg("-basketbeta", basket_beta);
        non_neg("-basketsector", basket_sector);
        non_neg("-levgain", lev_gain);
        non_neg("-stressscale", stress_scale);
        non_neg("-noiseasym", noise_asym);
        non_neg("-noiseasymcap", noise_asym_cap);
        non_neg("-volresp", vol_resp);
        non_neg("-bustamp", bust_amp);
        non_neg("-volrespcap", vol_resp_cap);
        non_neg("-jumpresp", jump_resp);
        if !(0.0..1.0).contains(&lev_persist) {
            cli_die("-levpersist is a persistence in [0, 1)");
        }
        if !(0.0..1.0).contains(&noise_asym_phi) {
            cli_die("-noiseasymphi is a persistence in [0, 1)");
        }
        if !(0.0..1.0).contains(&vol_resp_phi) {
            cli_die("-volrespphi is a persistence in [0, 1)");
        }
        if !(0.0..1.0).contains(&vol_resp_attack) {
            cli_die("-volrespattack is a persistence in [0, 1)");
        }
        if !(0.0..1.0).contains(&stress_adapt) || stress_adapt <= 0.0 {
            cli_die("-stressadapt is an EWMA weight in (0, 1)");
        }
        non_neg("-slowvol", slow_vol);
        non_neg("-slowlev", slow_lev);
        non_neg("-slowbeta", slow_beta);
        if !(0.0..1.0).contains(&slow_share) {
            cli_die("-slowshare is a share in [0, 1)");
        }
        if !(0.0..1.0).contains(&slow_phi) {
            cli_die("-slowphi is a persistence in [0, 1)");
        }
        if !(0.0..=1.0).contains(&slow_perm) {
            cli_die("-slowperm is a share in [0, 1]");
        }
        non_neg("-basketidio", basket_idio);
        non_neg("-basketgaps", basket_gaps);
        non_neg("-basketdrift", basket_drift);
        if macro_panel > 1 {
            cli_die(&format!("-macro {macro_panel}: 0 (off) or 1 (the panel)"));
        }
        if macro_null > 1 {
            cli_die(&format!(
                "-macronull {macro_null}: 0 (the path's own panel) or 1 (a sibling's)"
            ));
        }
        if macro_null > 0 && macro_panel == 0 {
            cli_die("-macronull needs -macro 1: it is the panel's null, not a panel");
        }
        if basket > 0 && basket_beta <= 0.0 {
            cli_die(
                "-basket requires -basketbeta > 0: a name with no sector leg is not a member of anything",
            );
        }
        if overnight >= 1.0 {
            cli_die(&format!(
                "-overnight {overnight} leaves the intraday session no variance to run the bridge on; it must be below 1"
            ));
        }
        if vol_idio > 0.0 && range_scale <= 0.0 {
            cli_die("-volidio requires -rangescale > 0: volume rides the range");
        }
        non_neg("-disasterrate", disaster_rate);
        non_neg("-disastersize", disaster_size);
        if disaster_rate > 0.0 && (disaster_size <= 0.0 || disaster_len <= 0.0) {
            cli_die(&format!(
                "-disasterrate {disaster_rate} needs -disastersize and -disasterlen above 0"
            ));
        }
        if disaster_recover > 0.0 && disaster_rec_len <= 0.0 {
            cli_die(&format!(
                "-disasterrecover {disaster_recover} needs -disasterreclen above 0"
            ));
        }
        // A 2-sd shift is already past every fitted setting; negative would skew jumps UP.
        if !(0.0..=2.0).contains(&jump_skew) {
            cli_die(&format!(
                "-jumpskew {jump_skew} out of range; needs 0 <= skew <= 2"
            ));
        }
        // beliefShare 1.0 would unmoor perceived fair from the fundamental entirely — the pull
        // chases its own shadow and nothing anchors the price level. Strictly below 1.
        if !(0.0..1.0).contains(&belief_share) {
            cli_die(&format!(
                "-beliefshare {belief_share} out of range; needs 0 <= share < 1"
            ));
        }
        if belief_share > 0.0 && belief_years <= 0.0 {
            cli_die(&format!(
                "-beliefshare {belief_share} needs -beliefyears above 0"
            ));
        }
        non_neg("-capyears", cap_years);
        if cap_years > 0.0 && cap_window <= 0.0 {
            cli_die(&format!("-capyears {cap_years} needs -capwindow above 0"));
        }
        non_neg("-recoverydrag", recovery_drag);
        non_neg("-crowdimpact", crowd_impact);
        non_neg("-panic", panic_k);
        non_neg("-fundvol", fund_vol);
        non_neg("-ratemean", rate_mean);
        non_neg("-easing", easing);
        non_neg("-unwind", unwind);
        non_neg("-refuge", refuge);
        non_neg("-inflsize", infl_size);
        non_neg("-ratespeed", rate_speed);
        non_neg("-discount", discount);
        non_neg("-margin", margin);
        non_neg("-cost", cost);
        // `-drift` carries no domain: a negative fundamental drift is a world, not an error.
        // The PAIR is what no per-dial check can see -- `jump_scale` divides by `jump_rate`.
        if jump_var > 0.0 && jump_rate <= 0.0 {
            cli_die(&format!(
                "-jumpvar {} needs -jumprate above 0: the jump size is set by jumpVar/jumpRate",
                scala_dbl(jump_var)
            ));
        }
        // The loss is only comparable on the ensemble the -noise weights were frozen from, so
        // -fitness pins 60x80 -- and REFUSES rather than ignores an explicit override, the same
        // rule -emitfrom follows. Accepted-then-ignored is how "the loss improved" gets read off a
        // different sample.
        if fitness_only && (paths_given || years_given) {
            cli_die("-fitness scores the frozen 60x80 ensemble; -paths/-years do not apply");
        }
    }

    let crowd = match crowd_name.to_lowercase().as_str() {
        "momentum" => Crowd::Momentum,
        "volscaled" => Crowd::VolScaled,
        t if t.starts_with("trend") => match t[5..].parse::<i32>() {
            Ok(d) if d > 0 => Crowd::Trend(d),
            _ => cli_die(&format!(
                "unknown -crowd [{crowd_name}]; use momentum, trendNNN, volscaled, or drawdownNN"
            )),
        },
        t if t.starts_with("drawdown") => match t[8..].parse::<i32>() {
            Ok(d) if d > 0 && d < 100 => Crowd::Drawdown(d),
            _ => cli_die(&format!(
                "unknown -crowd [{crowd_name}]; use momentum, trendNNN, volscaled, or drawdownNN"
            )),
        },
        _ => cli_die(&format!(
            "unknown -crowd [{crowd_name}]; use momentum, trendNNN, volscaled, or drawdownNN"
        )),
    };
    let anchors = anchors_named(&anchor_spec);
    let w = World {
        trend_share,
        depth,
        stress,
        beta,
        drift,
        fund_vol,
        rate_mean,
        vol_persist,
        vol_of_vol,
        recovery_drag,
        recovery_floor,
        halt_limit,
        disaster_rate,
        disaster_size,
        disaster_len,
        disaster_recover,
        disaster_rec_len,
        belief_share,
        belief_years,
        cap_years,
        cap_window,
        leverage,
        down_shock,
        jump_var,
        jump_rate,
        jump_skew,
        news_rate,
        news_size,
        refuge_days,
        sat_beta,
        sat_idio,
        range_scale,
        range_down,
        vol_idio,
        div_yield,
        overnight,
        basket,
        basket_beta,
        basket_sector,
        basket_idio,
        basket_gaps,
        basket_drift,
        macro_panel,
        macro_null,
        lev_gain,
        stress_scale,
        lev_persist,
        noise_asym,
        noise_asym_phi,
        noise_asym_cap,
        vol_resp,
        vol_resp_phi,
        vol_resp_cap,
        vol_resp_attack,
        jump_resp,
        stress_adapt,
        bust_amp,
        slow_share,
        slow_vol,
        slow_lev,
        slow_phi,
        slow_perm,
        slow_beta,
        value_pull,
        crowd,
        crowd_impact,
        panic: panic_k,
        duration,
        easing,
        unwind,
        refuge,
        infl_prob,
        infl_size,
        infl_speed,
        rate_speed,
        discount,
        margin,
    };

    // SATELLITE PROTOTYPE: write per-path primary+satellite LOG prices for grading against the
    // SPY-QQQ coupling anchors (the joint_anchor conventions, graded python-side). Deliberately
    // OUTSIDE the -emit interface: no sidecar, no schema claim — a measurement tap, not a
    // consumer surface. LOG prices, not levels: the twins' transcendentals carry a 1-ulp
    // latitude (PARITY.md §6), and a level near 1e6 rendered at %.6f puts that latitude within
    // ~1e-4 of a rounding tie — a handful of cross-language print flips per 40 paths, measured.
    // A log near 13 puts the same latitude nine orders under the printed digit: a rendering
    // rule, not a tolerance.
    if !joint_emit.is_empty() {
        if sat_beta <= 0.0 {
            cli_die("-jointemit requires -satbeta > 0");
        }
        for k in 0..paths {
            let p = simulate(&w, years, seed + k as u64 * 7919);
            let mut tsv = String::from("logPrice\tlogSat\n");
            for i in 0..p.price.len() {
                tsv.push_str(&ef(p.price[i].ln()));
                tsv.push('\t');
                tsv.push_str(&ef(p.sat[i].ln()));
                tsv.push('\n');
            }
            write_or_die(&format!("{joint_emit}-{k:03}.tsv"), &tsv);
        }
        return;
    }
    // RANGE PROTOTYPE: per-path log price/high/low for grading against the bars anchors
    // (the bars_anchor conventions, graded python-side). Same contract as -jointemit: a
    // measurement tap outside the -emit interface, LOG columns per the parity lesson.
    if !bars_emit.is_empty() {
        if range_scale <= 0.0 {
            cli_die("-barsemit requires -rangescale > 0");
        }
        for k in 0..paths {
            let p = simulate(&w, years, seed + k as u64 * 7919);
            let mut tsv = String::from("logPrice\tlogHigh\tlogLow");
            if vol_idio > 0.0 {
                tsv.push_str("\tlogVolume");
            }
            tsv.push('\n');
            for i in 0..p.price.len() {
                tsv.push_str(&ef(p.price[i].ln()));
                tsv.push('\t');
                tsv.push_str(&ef(p.log_hi[i]));
                tsv.push('\t');
                tsv.push_str(&ef(p.log_lo[i]));
                if vol_idio > 0.0 {
                    tsv.push('\t');
                    tsv.push_str(&ef(p.log_volume[i]));
                }
                tsv.push('\n');
            }
            write_or_die(&format!("{bars_emit}-{k:03}.tsv"), &tsv);
        }
        return;
    }
    if calibrate_n > 0 {
        calibrate(anchors, calibrate_n, &w, seed);
        return;
    }
    if fitness_only {
        let st = measure(&sim_paths(&w, 60, 80, seed), 80);
        let (loss, rows) = fitness(anchors, &st, &extreme_score_stats(anchors, 60, seed, &w));
        println!(
            "fitness loss {}  (lower is better; includes 0.5 per failed gate check)",
            jf(loss, 0, 3)
        );
        for (n, m, t, term) in &rows {
            println!(
                "  {n:<22} model {}   target {}   term {}",
                jf(*m, 8, 2),
                jf(*t, 8, 2),
                jf(*term, 6, 3)
            );
        }
        for (n, ok, _) in gate_checks(anchors, &st) {
            if !ok {
                println!("  FAILED GATE: {n}  (+0.500)");
            }
        }
        // The model column for these rows is a DIFFERENT statistic from -validate's: said here
        // because a reader comparing the two tables would otherwise take the disagreement for a
        // bug.
        if rows.iter().any(|(n, _, _, _)| EXTREME_TARGETS.contains(n)) {
            println!(
                "  NOTE: {} — the model value scored (and shown",
                EXTREME_TARGETS.join(", ")
            );
            println!(
                "    above) is the MEDIAN of single histories at the anchor's own horizon, the"
            );
            println!(
                "    converging centre of the distribution -validate's percentile reads.  The pooled"
            );
            println!(
                "    ensemble minimum is never scored: its distance from a one-history anchor"
            );
            println!("    tracks the ensemble size.");
        }
        return;
    }
    if release_report {
        run_release_report(anchors, paths, years, seed, &w);
        return;
    }
    if cross_asset {
        // Exits non-zero on an in-support miss, or when a relation graded nothing
        // (INCONCLUSIVE) — an EXTRAP cell alone is disclosed, not fatal.
        if !run_cross_asset_report(anchors, paths, years, seed, &w) {
            std::process::exit(1);
        }
        return;
    }
    if noise_report {
        // -years is ignored deliberately: the horizons come from the anchors themselves, and the
        // seed-noise section from the scoring configuration.
        run_noise_report(anchors, paths, seed, &w);
        return;
    }
    if strategies {
        run_strategy_sweep(anchors, paths, years, seed, cost, single, &w, &gate_req);
        return;
    }
    if power_report {
        run_power_report(
            anchors,
            paths,
            seed,
            cost,
            single,
            &w,
            &gate_req,
            &power_arms,
            &power_years,
        );
        return;
    }
    if dd_shape {
        run_drawdown_shape(&anchors, paths, years, seed, &w);
        return;
    }
    if buffer_report {
        run_buffer_report(anchors, paths, years, seed, cost, single, &w, &gate_req);
        return;
    }

    eprintln!("simulating {paths} paths x {years} years");
    let sims = sim_paths(&w, paths, years, seed);
    let st = measure(&sims, years);

    // The verdict is a property of the WORLD, so it is measured on an ensemble large enough for
    // the conditional mechanism statistics to exist AND at the horizon the bands were calibrated
    // at. Judging the world by the one path being written made every short export raise all four
    // mechanism failures; judging it at a short `-years` failed fixed bands on horizon-growing
    // statistics the same way (`GATE_YEARS`). The rows are built ONCE: the printed table and
    // every sidecar render these same rows, so the extreme rows' own-horizon ensemble — the
    // expensive part — runs once per invocation, not once per emitted path.
    let (verdict_paths, verdict_years) = verdict_spec(!emit.is_empty(), emit_gate, paths, years);
    let verdict_st = if (verdict_paths, verdict_years) == (paths, years) {
        st
    } else {
        measure(
            &sim_paths(&w, verdict_paths, verdict_years, seed),
            verdict_years,
        )
    };
    let verdict_rows = fidelity_rows(anchors, &verdict_st, verdict_paths, seed, &w);

    if !emit.is_empty() {
        let realism_bad = failed_in(anchors, &verdict_st, GateClass::Realism);
        let mechanism_bad = failed_in(anchors, &verdict_st, GateClass::Mechanism);
        if !realism_bad.is_empty() {
            eprintln!(
                "WARNING: this world FAILS the realism bands [{}] — the emitted path is not market-like",
                realism_bad.join(", ")
            );
        }
        if !mechanism_bad.is_empty() {
            eprintln!(
                "NOTE: mechanisms inert in this world [{}] — conclusions that lean on them are not supported here",
                mechanism_bad.join(", ")
            );
        }
        let fidelity_bad = failed_in(anchors, &verdict_st, GateClass::Fidelity);
        if !fidelity_bad.is_empty() {
            eprintln!(
                "NOTE: levels not readable in this world [{}] — rank comparisons survive, anything reading a level off these does not",
                fidelity_bad.join(", ")
            );
        }
        // path k is a function of (world, years, seed, k) alone, so an index past the report
        // ensemble is simulated directly rather than forcing a larger run
        let path_at = |k: usize| -> Path {
            if k < sims.len() {
                sims[k].clone()
            } else {
                simulate(&w, years, seed + k as u64 * 7919)
            }
        };
        // REFUSED, not warned about. Every other gate verdict is advisory because an unrealistic
        // world is still a world; a path holding a non-finite price is not data at all. The dial
        // domains close the routes reachable from the command line; this closes the file.
        let refuse_non_finite = |p: &Path, k: usize, f: &str| {
            if !p.price.iter().all(|x| x.is_finite())
                || !p.sat.iter().all(|x| x.is_finite())
                || !p.log_hi.iter().all(|x| x.is_finite())
                || !p.log_lo.iter().all(|x| x.is_finite())
                || !p.log_volume.iter().all(|x| x.is_finite())
                || !p.div_yield.iter().all(|x| x.is_finite())
                || !p.traded.iter().all(|x| x.is_finite())
                || !p.log_open.iter().all(|x| x.is_finite())
                || !p.names.iter().all(|lp| lp.iter().all(|x| x.is_finite()))
            {
                eprintln!("REFUSED: path {k} holds a non-finite value; nothing written to {f}");
                std::process::exit(2);
            }
        };
        let written: Vec<String> = if emit_all {
            // At the default offset this IS the report ensemble; shifted, the range is re-simulated
            // (in parallel, not one at a time through `path_at`) because the report and the gate
            // stay measured on 0..paths — the verdict describes the WORLD, not the chunk.
            let batch: Vec<Path> = if emit_from == 0 {
                sims.clone()
            } else {
                sim_path_range(&w, emit_from, paths, years, seed)
            };
            let width = index_width(emit_from + paths - 1);
            (emit_from..emit_from + paths)
                .map(|k| {
                    let f = indexed_name(&emit, k, width);
                    refuse_non_finite(&batch[k - emit_from], k, &f);
                    write_emitted(
                        anchors,
                        &f,
                        &batch[k - emit_from],
                        k,
                        &w,
                        years,
                        seed,
                        &emit_start,
                        &verdict_st,
                        verdict_paths,
                        verdict_years,
                        &verdict_rows,
                    );
                    f
                })
                .collect()
        } else {
            let p = path_at(emit_path);
            refuse_non_finite(&p, emit_path, &emit);
            write_emitted(
                anchors,
                &emit,
                &p,
                emit_path,
                &w,
                years,
                seed,
                &emit_start,
                &verdict_st,
                verdict_paths,
                verdict_years,
                &verdict_rows,
            );
            vec![emit.clone()]
        };
        let sessions = path_at(if emit_all { emit_from } else { emit_path })
            .price
            .len();
        let span = if written.len() > 1 {
            format!(" .. {}", written[written.len() - 1])
        } else {
            String::new()
        };
        eprintln!(
            "wrote {} path(s), {} columns x {sessions} sessions, to {}{span} (+ sidecar {})",
            written.len(),
            EMIT_COLUMNS.len()
                + usize::from(w.sat_beta > 0.0)
                + 2 * usize::from(w.range_scale > 0.0)
                + usize::from(w.vol_idio > 0.0)
                + 2 * usize::from(w.div_yield > 0.0)
                + usize::from(w.overnight > 0.0)
                + if w.basket > 0 { w.basket + 1 } else { 0 },
            written[0],
            sidecar_name(&written[0])
        );
    }

    let all_rets: Vec<Vec<f64>> = sims.iter().map(|s| daily_returns(&s.price)).collect();
    let ann_vol: Vec<f64> = all_rets
        .iter()
        .map(|r| {
            (scala_sum(r.iter().map(|x| x * x)) / r.len() as f64 * DAYS_PER_YEAR as f64).sqrt()
        })
        .collect();
    let ann_ret: Vec<f64> = sims
        .iter()
        .map(|s| (s.price[s.price.len() - 1] / s.price[0]).ln() / years as f64 * 100.0)
        .collect();

    println!(
        "paths {paths} x {years} years   {} simulated years",
        paths * years
    );
    // `med` and `pctile` both drop non-finite paths, so summarising the survivors in silence is how
    // a contaminated ensemble reads as an ordinary world. The count is stated where the medians it
    // excludes are read.
    let non_finite_paths = sims
        .iter()
        .filter(|s| !s.price.iter().all(|x| x.is_finite()))
        .count();
    if non_finite_paths > 0 {
        println!(
            "  WARNING: {non_finite_paths} of {} paths hold a non-finite price and are EXCLUDED from",
            sims.len()
        );
        println!(
            "           every median and percentile below -- this world is not simulable as dialled"
        );
    }
    println!();
    println!(
        "  annualised return      median {}%   5th {}%   95th {}%",
        jf(st.ann_ret, 6, 2),
        jf(pctile(&ann_ret, 0.05), 6, 2),
        jf(pctile(&ann_ret, 0.95), 6, 2)
    );
    println!(
        "  annualised volatility  median {}%   5th {}%   95th {}%",
        jf(st.vol * 100.0, 6, 2),
        jf(pctile(&ann_vol, 0.05) * 100.0, 6, 2),
        jf(pctile(&ann_vol, 0.95) * 100.0, 6, 2)
    );
    println!("  daily return kurtosis  median {}", jf(st.kurt, 6, 2));
    println!(
        "  volatility clustering  lag  1 {}   lag 20 {}   (lag 5 {}   lag 60 {})",
        jf(st.ac1, 6, 3),
        jf(st.ac20, 6, 3),
        jf(st.ac5, 0, 3),
        jf(st.ac60, 0, 3)
    );
    println!(
        "  vol response to a fall corr(r, |r+k|)  k=1 {}   k=5 {}   k=20 {}   (record -0.09 / -0.08 / -0.04)",
        jf(st.lev1, 6, 3),
        jf(st.lev5, 6, 3),
        jf(st.lev20, 6, 3)
    );
    // The line above is |r| and the line below is r, which is the whole reason both are printed:
    // they are different axes and a world can be right on one and wrong on the other.
    println!(
        "  trend persistence      variance ratio {}   (1.0 = no serial dependence)",
        VAR_RATIO_LADDER
            .iter()
            .map(|&q| format!("{q}d {:.3}", vr_of(&st, q)))
            .collect::<Vec<_>>()
            .join("  ")
    );
    // The rung the ladder cannot see. REPORTED, never graded: the record's sign flips by era, so
    // the cross-section carries no one value to grade against — see persistence-2026-09-11.tsv.
    let (rc26, rc54, rc90, rf_lo, rf_hi) = RET_AC1_RECORD;
    println!(
        "                         lag-1 signed  {:+.4}   (record: CRSP {rc26:+.3} century, \
         {rc54:+.3} from 1954, {rc90:+.3} from 1990; every modern fund {rf_lo:+.3}..{rf_hi:+.3})",
        st.ret_ac1
    );
    println!(
        "                         envelopes {}; slopes {}",
        VAR_RATIO_BANDS
            .iter()
            .map(|&(q, lo, hi)| format!("{q}d {lo:.2}-{hi:.2}"))
            .collect::<Vec<_>>()
            .join("  "),
        VAR_RATIO_SLOPE_BANDS
            .iter()
            .map(|&(a, b, lo, hi)| {
                let sl = vr_of(&st, b) - vr_of(&st, a) + 0.0;
                format!("{a}->{b} {sl:+.3} ({lo:+.2}..{hi:+.2})")
            })
            .collect::<Vec<_>>()
            .join("  ")
    );
    println!();
    println!(
        "  drawdowns of 15%+      {}, {} per path; {} unrecovered at path end (included in depth)",
        st.n_episodes,
        jf(st.ep_per_path, 0, 1),
        st.censored
    );
    println!(
        "  their depth            median {}%   worst {}%",
        jf(st.depth_med, 6, 1),
        jf(st.worst_depth, 6, 1)
    );
    println!(
        "  recovery shape         V {}   balanced {}   U {}",
        st.v_count, st.mid_count, st.u_count
    );
    println!(
        "  bond refuge            vol {}% (24y windows)   growth-crash {}   infl-crash {}",
        jf(st.bond_vol * 100.0, 0, 1),
        jfs(st.bond_growth, 1),
        jfs(st.bond_infl, 1)
    );
    println!(
        "  stock-bond correlation calm {}   inflation regime {}",
        jfs(st.corr_calm, 2),
        jfs(st.corr_infl, 2)
    );
    println!(
        "  realized inflation     {}%/yr median (deterministic from regime pressure; no draws consumed)",
        jf(st.infl_ann, 0, 2)
    );
    // The channel readings the gate rows grade, printed so a channel FAIL can be sized; absent
    // when the channel is off, like the rows themselves.
    if let Some(sd) = st.sat {
        println!(
            "  satellite leg          corr {}   |r| corr {}   beta {}   vol ratio {}   kurtosis ratio {}",
            jf(sd.corr, 0, 3),
            jf(sd.abs_corr, 0, 3),
            jf(sd.beta, 0, 3),
            jf(sd.vol_ratio, 0, 3),
            jf(sd.kurt_ratio, 0, 3)
        );
        println!(
            "                         clustering ratio lag 1 {}   lag 20 {}   d5 ratio {}   d10 ratio {}   crash ratio {}",
            jf(sd.ac1_ratio, 0, 3),
            jf(sd.ac20_ratio, 0, 3),
            jf(sd.d5_ratio, 0, 3),
            jf(sd.d10_ratio, 0, 3),
            jf(sd.crash_ratio, 0, 3)
        );
    }
    if let Some(b) = st.bars {
        println!(
            "  bar range              vs cc vol {}   clustering {}   down/up {}",
            jf(b.range_over_ccvol, 0, 3),
            jf(b.range_acf1, 0, 3),
            jf(b.range_downup, 0, 3)
        );
        if b.vol_sd.is_finite() {
            println!(
                "  bar volume             sd {}   vs range {}",
                jf(b.vol_sd, 0, 3),
                jf(b.vol_corr_range, 0, 3)
            );
        }
    }
    if let Some(b) = st.basket {
        println!(
            "  basket names           vol ratio {}   gaps/yr {}   d20 {} (spread {})",
            jf(b.name_vol_ratio, 0, 2),
            jf(b.name_gaps, 0, 2),
            jf(b.name_d20, 0, 3),
            jf(b.name_d20_spread, 0, 3)
        );
        println!(
            "  basket aggregate       corr {}   beta {}   vol ratio {}",
            jf(b.agg_corr, 0, 3),
            jf(b.agg_beta, 0, 3),
            jf(b.agg_vol_ratio, 0, 2)
        );
        println!(
            "  basket structure       pair corr {}   idio share {}   tail coincidence {}   pair corr worst/mid {}/{}",
            jf(b.pair_corr, 0, 3),
            jf(b.idio_share, 0, 3),
            jf(b.tail_coincidence, 0, 3),
            jf(b.pair_corr_worst, 0, 3),
            jf(b.pair_corr_mid, 0, 3)
        );
    }
    if let Some(os) = st.open {
        println!(
            "  bar open               overnight share {}   gap share worst-1% {} vs all {}",
            jf(os.overnight_share, 0, 3),
            jf(os.worst_gap_share, 0, 3),
            jf(os.all_gap_share, 0, 3)
        );
    }
    if let Some(ms) = &st.macro_panel {
        println!(
            "  macro panel            {} pooled 20% episodes; warning share = median fraction of the log decline still ahead at the first firing",
            ms.episodes
        );
        if ms.sibling {
            println!(
                "    NULL PANEL: the columns are a sibling path's, decoupled from this price -- these readings are the"
            );
            println!("    no-edge level, and the macro rows do not grade them");
        }
        println!(
            "    {:<12} {:>7} {:>7} {:>8} {:>7} {:>6} {:>5} {:>6} {:>8} {:>5} {:>8} {:>8} {:>8}",
            "member",
            "ac1",
            "acK",
            "r2fwd60",
            "warn20",
            "fired",
            "lag",
            "lag10",
            "fired10",
            "pre",
            "p10",
            "p50",
            "p90"
        );
        for (j, m) in ms.members.iter().enumerate() {
            println!(
                "    {:<12} {} {} {} {} {} {} {} {} {} {} {} {}",
                macro_k::COLUMNS[j],
                jf(m.ac1, 7, 4),
                jf(m.ac_k, 7, 4),
                jf(m.r2fwd60, 8, 4),
                jf(m.warn, 7, 3),
                jf(m.warn_fired, 6, 2),
                jf(m.lag, 5, 0),
                jf(m.lag10, 6, 0),
                jf(m.fired10, 8, 2),
                jf(m.pre_peak, 5, 2),
                jf(m.lvl10, 8, 2),
                jf(m.lvl50, 8, 2),
                jf(m.lvl90, 8, 2)
            );
        }
        println!(
            "    leverage hazard        20% peak within a quarter x{} (unconditional {})   within a year x{}   10% within a quarter x{}",
            jf(ms.hazard20q, 0, 2),
            jf(ms.p20q, 0, 3),
            jf(ms.hazard20y, 0, 2),
            jf(ms.hazard10q, 0, 2)
        );
        let (c_r2, c_pre, _) = ms.member_spread[2];
        println!(
            "    per path (p5 / p50 / p95)  hazard x{} / {} / {}   slope inverted {} / {} / {}   cond r2fwd60 {} / {} / {}   cond build-up {} / {} / {}",
            jf(ms.hazard_spread.p5, 0, 2),
            jf(ms.hazard_spread.p50, 0, 2),
            jf(ms.hazard_spread.p95, 0, 2),
            jf(ms.inv_share_spread.p5, 0, 2),
            jf(ms.inv_share_spread.p50, 0, 2),
            jf(ms.inv_share_spread.p95, 0, 2),
            jf(c_r2.p5, 0, 3),
            jf(c_r2.p50, 0, 3),
            jf(c_r2.p95, 0, 3),
            jf(c_pre.p5, 0, 2),
            jf(c_pre.p50, 0, 2),
            jf(c_pre.p95, 0, 2)
        );
        println!(
            "    slope inverted         share {}   mean spell {} sessions",
            jf(ms.inv_share, 0, 3),
            jf(ms.inv_dur, 0, 1)
        );
        println!(
            "    ivol premium           vrp {} (log)   r2 vs forward realized {}",
            jf(ms.vrp, 0, 3),
            jf(ms.r2rv, 0, 3)
        );
    }
    if st.div_yield_mean.is_finite() {
        println!(
            "  dividend yield         mean {}%/yr   (the dial, varying with fundamental/price; anchor {}, band {}-{})",
            jf(st.div_yield_mean, 0, 2),
            jf(anchors.div_yield, 0, 2),
            jf(anchors.div_yield_band.0, 0, 1),
            jf(anchors.div_yield_band.1, 0, 1)
        );
    }
    println!("  depth profile          share of sessions below the running peak, median path");
    // Against the relation at THIS world's own volatility and return, not against SPY's levels:
    // SPY produced 0.447 / 0.315 / 0.169 at 18.6% volatility and 0.554 return per vol, so printing
    // them beside a world at a different operating point invites exactly the comparison the rungs
    // were restated to stop — and would show a correct world as a large miss.
    let eq_vol_pct = st.vol * 100.0;
    println!(
        "    equity               >5% {}   >10% {}   >20% {}      real funds at this vol/return {} / {} / {}",
        jf(st.dd_eq5, 0, 3),
        jf(st.dd_eq10, 0, 3),
        jf(st.dd_eq20, 0, 3),
        jf(
            equity_depth_expected(0.05, EQUITY_D5_CORR, eq_vol_pct, st.ret_vol()),
            0,
            3
        ),
        jf(
            equity_depth_expected(0.10, EQUITY_D10_CORR, eq_vol_pct, st.ret_vol()),
            0,
            3
        ),
        jf(
            equity_depth_expected(0.20, EQUITY_D20_CORR, eq_vol_pct, st.ret_vol()),
            0,
            3
        )
    );
    println!(
        "    bond                 >5% {}   >10% {}   >20% {}      real TLT   -   / 0.510 /   -",
        jf(st.dd_bd5, 0, 3),
        jf(st.dd_bd10, 0, 3),
        jf(st.dd_bd20, 0, 3)
    );
    println!(
        "  binding diagnostics    trend share {} (pinned {}%, target saturated {}%)   bond spiral {}% of sessions   clamped {}% of all sessions, {}% of tail sessions   halts {}%",
        jf(st.trend_share, 0, 2),
        jf(st.trend_pinned * 100.0, 0, 1),
        jf(st.target_sat * 100.0, 0, 1),
        jf(st.pct_bond_stress * 100.0, 0, 1),
        jf(st.clamp_pct, 0, 3),
        jf(st.tail_floor_pct, 0, 1),
        jf(st.halt_pct, 0, 3)
    );
    println!(
        "                         crowd flow {} bp/session ({}% of the noise term) — the reflexive channel   macro disasters {}/century",
        jf(st.crowd_flow * 1e4, 0, 2),
        jf(st.crowd_flow / SIGMA_N * 100.0, 0, 1),
        jf(st.dis_per_century, 0, 2)
    );
    println!(
        "  valuation gap          sd log(p/fair) {}   century max +{}% over fair   (record proxy: sd log CAPE 0.24-0.41, peaks +70-100%)",
        jf(st.val_disp, 0, 3),
        jf(st.max_over * 100.0, 0, 0)
    );

    println!();
    // The anchors do NOT share one window, and a single-window label invites a reader to re-derive
    // them from it and conclude the model has drifted. The depth rungs are the exception by
    // construction: they are graded against a RELATION evaluated at this world's own volatility and
    // return, so they carry no window of their own to be compared at.
    println!(
        // The anchor SET is named because the equity rows are asset-specific: the same world
        // graded against a different index is a different verdict, and a report that does not say
        // which index it used cannot be read six months later.
        "  fidelity against {} targets, by anchor (each row is against the window named for it):",
        anchors.name
    );
    // Named whenever the two differ, so a reader cannot take the verdict for a reading of the
    // ensemble described above it.
    if (verdict_paths, verdict_years) != (paths, years) {
        println!(
            "    graded on {verdict_paths} paths x {verdict_years} years — the calibration horizon; the report above describes {paths} x {years}"
        );
    }
    println!(
        "    equity {}   |   depth rungs 35 equity funds 2001-2026, vs each world's",
        anchors.equity_window
    );
    println!(
        "      OWN volatility and return   |   return per vol {}",
        anchors.ret_vol_window
    );
    println!(
        "    clustering {} (horizon-dependent: the statistic moves with the",
        anchors.cluster_window
    );
    println!(
        "      model is scored on 100-year paths)   |   refuge long Treasury   |   bond depth"
    );
    println!("      rung clean TLT, 24y");
    println!(
        "    NOTE: bond volatility alone is measured over 24-YEAR windows, not the whole path —"
    );
    println!(
        "      it is the one horizon-dependent statistic whose anchor can only come from fund"
    );
    println!(
        "      data, and no clean bond-fund series runs longer.  Every other row is whole-path."
    );
    println!(
        "    NOTE: a row whose model statistic is an EXTREME over the ensemble carries no ratio —"
    );
    println!(
        "      the deepest of ~4,400 pooled episodes over the deepest of ONE history grades the"
    );
    println!(
        "      sample size, not the model, and deepens without bound as -paths grows.  Those rows"
    );
    println!(
        "      report where the record falls among single histories of its own length instead;"
    );
    println!(
        "      near 50% the record is a typical history of this model.  Same reading as -noise."
    );
    for r in &verdict_rows {
        let flag = if r.miss() { "  <-- MISS" } else { "" };
        let judgement = match (r.ratio, r.pctile) {
            (Some(x), _) => format!("ratio {}", jf(x, 5, 2)),
            (None, Some(pc)) => format!(
                "record@ {pc:>3}% of {}y histories (n={})",
                r.horizon_years, r.n_histories
            ),
            (None, None) => format!(
                "record@  n/a — {} histories, needs {EXTREME_MIN_HISTORIES}",
                r.n_histories
            ),
        };
        println!(
            "     {:<22} model {}   real {}   {}{}",
            r.name,
            jf(r.model, 8, 2),
            jf(r.real, 8, 2),
            judgement,
            flag
        );
    }

    if validate {
        let checks = gate_checks(anchors, &verdict_st);
        let bad: Vec<Vec<String>> = GateClass::ALL
            .iter()
            .map(|c| failed_in(anchors, &verdict_st, *c))
            .collect();
        let verdict = |i: usize| {
            if bad[i].is_empty() { "PASS" } else { "FAIL" }
        };
        println!();
        println!("  acceptance gate:");
        let una = unanchored_in(&verdict_st);
        for cls in GateClass::ALL {
            let (banner, cost) = cls.section();
            println!("    {banner} — {cost}:");
            for (n, ok, _) in checks.iter().filter(|(_, _, c)| *c == cls) {
                println!("     {:<5} {}", if *ok { "PASS" } else { "FAIL" }, n);
            }
            // A band whose anchors cannot grade this world is disclosed where it would have
            // appeared, not silently absent — n/a, never PASS or FAIL.
            if cls == GateClass::Fidelity {
                for n in &una {
                    println!("     {:<5} {n}", "n/a");
                }
            }
        }
        println!(
            "    verdict: realism {}   mechanism {}   fidelity {}",
            verdict(0),
            verdict(1),
            verdict(2)
        );
        if !bad[1].is_empty() {
            println!("      inert: {}", bad[1].join(", "));
        }
        if !bad[2].is_empty() {
            println!("      levels not readable: {}", bad[2].join(", "));
        }
        if !una.is_empty() {
            println!("      no anchor: {}", una.join(", "));
        }
        // exit code follows the classes this run declared it requires, nothing more
        let blocking: Vec<GateClass> = GateClass::ALL
            .into_iter()
            .enumerate()
            .filter(|(i, c)| gate_req.contains(c) && !bad[*i].is_empty())
            .map(|(_, c)| c)
            .collect();
        if !blocking.is_empty() {
            let names: Vec<&str> = blocking.iter().map(|c| c.label()).collect();
            let failures: Vec<String> = GateClass::ALL
                .into_iter()
                .enumerate()
                .filter(|(_, c)| blocking.contains(c))
                .flat_map(|(i, _)| bad[i].clone())
                .collect();
            let mut req: Vec<&str> = gate_req.iter().map(|c| c.label()).collect();
            req.sort_unstable();
            eprintln!(
                "acceptance gate FAILED for required {} [{}] — required classes are {}; change them with -gate",
                names.join(", "),
                failures.join(", "),
                req.join(",")
            );
            std::process::exit(1);
        }
    }
}

/// The sidecar declares a schema number, and a consumer is told to read it first — a missing
/// `version` means schema 1, not a malformed file (`docs/MarketSimWorlds.md`). That instruction is
/// only safe while the declared number and the emitted shape agree.
///
/// Nothing in the writer keeps them in step: the schema is one integer and the shape is a
/// hand-built list of lines. These tests compare what is actually emitted against
/// `EMIT_SIDECAR_KEYS`, which the writer never reads, so adding, removing or renaming a key fails
/// HERE — beside the schema number that then has to be decided about — instead of in a consumer
/// that trusted the declaration. The Scala twin carries the same checks in `EmitSidecarSuite`.
///
/// They cannot force a bump, and do not pretend to: a shape change with the contract updated and
/// the number left alone still passes. What they remove is the silent case.
#[cfg(test)]
mod emit_sidecar_tests {
    use super::*;

    /// Emit one real path and return the sidecar's lines, then clean up. The smallest run that
    /// still produces a real sidecar: two years, with the gate verdict measured on the single path
    /// simulated (the `-emitgate 0` reading), so this costs one short simulation rather than a
    /// 200-path ensemble.
    ///
    /// `tag` keeps concurrent callers apart: the harness runs tests in parallel, and a shared
    /// directory name lets one test delete the sidecar another is writing — `write_or_die` then
    /// `process::exit`s and takes the whole harness down, not just the raced test. Each test
    /// passes its own tag; the pid separates simultaneous harness invocations.
    fn sidecar_lines(tag: &str) -> Vec<String> {
        let years = 2usize;
        let seed = 20260825u64;
        let w = default_world();
        let p = simulate(&w, years, seed);
        let st = measure(std::slice::from_ref(&p), years);
        let dir = std::env::temp_dir().join(format!("emit_sidecar_{tag}_{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("temp dir");
        let tsv = dir.join("emit_sidecar_tests.tsv");
        // Native separators are fine: sidecar_name splits on both / and backslash.
        let tsv = tsv.to_string_lossy().into_owned();
        let rows = fidelity_rows(SP500_ANCHORS, &st, 1, seed, &w);
        write_emitted(
            SP500_ANCHORS,
            &tsv,
            &p,
            0,
            &w,
            years,
            seed,
            "",
            &st,
            1,
            years,
            &rows,
        );
        let json = sidecar_name(&tsv);
        let text = std::fs::read_to_string(&json).expect("sidecar written");
        std::fs::remove_file(&tsv).ok();
        std::fs::remove_file(&json).ok();
        std::fs::remove_dir(&dir).ok();
        text.lines().map(str::to_string).collect()
    }

    /// Every optional column the TSV carries is named in `ungradedChannelSeries`, and the base
    /// columns in `gradedSeries` — so a consumer cannot read a verdict as covering a series it
    /// never examined. The channels are ON here precisely because that is the trap: with them
    /// off, the disclosure is vacuously right.
    #[test]
    fn the_gate_names_every_series_it_did_not_grade() {
        let years = 2usize;
        let seed = 20260825u64;
        let mut w = default_world();
        w.sat_beta = 1.2;
        w.sat_idio = 0.77;
        w.range_scale = 0.63;
        w.vol_idio = 0.34;
        let p = simulate(&w, years, seed);
        let st = measure(std::slice::from_ref(&p), years);
        let dir = std::env::temp_dir().join(format!("emit_scope_{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("temp dir");
        let tsv = dir.join("scope.tsv").to_string_lossy().into_owned();
        let rows = fidelity_rows(SP500_ANCHORS, &st, 1, seed, &w);
        write_emitted(
            SP500_ANCHORS,
            &tsv,
            &p,
            0,
            &w,
            years,
            seed,
            "",
            &st,
            1,
            years,
            &rows,
        );
        let header = std::fs::read_to_string(&tsv)
            .expect("tsv")
            .lines()
            .next()
            .expect("header")
            .to_string();
        let json = sidecar_name(&tsv);
        let side = std::fs::read_to_string(&json).expect("sidecar");
        std::fs::remove_file(&tsv).ok();
        std::fs::remove_file(&json).ok();
        std::fs::remove_dir(&dir).ok();
        let declared: Vec<&str> = side
            .lines()
            .filter(|l| {
                l.trim_start().starts_with("\"gradedSeries\"")
                    || l.trim_start().starts_with("\"ungradedChannelSeries\"")
            })
            .collect();
        assert_eq!(declared.len(), 2, "both scope fields must be present");
        let cols: Vec<&str> = header.split('\t').collect();
        // The graded half must name series the file actually carries...
        for col in ["price", "bond"] {
            assert!(
                cols.contains(&col),
                "graded series {col} must be an emitted column"
            );
        }
        // ...and every UNGRADED optional-channel column must be declared outside the verdict's
        // scope. `logSat` is deliberately absent from that list: the satellite leg carries its
        // own gate rows, so the verdict does cover it.
        // COVERAGE, which is the invariant that matters: every emitted channel column must be
        // either GRADED by a gate row or DECLARED ungraded. A column that is neither is exactly
        // the trap this pair of fields exists to close — a verdict sitting beside a series it
        // never examined and never admitted to.
        let graded = declared[0];
        let ungraded = declared[1];
        let rows = gate_checks(SP500_ANCHORS, &st);
        let graded_by = |prefix: &str| rows.iter().any(|(n, _, _)| n.starts_with(prefix));
        for (col, prefix) in [
            ("logSat", "satellite"),
            ("logHigh", "bar range"),
            ("logLow", "bar range"),
            ("logVolume", "bar volume"),
        ] {
            assert!(
                graded_by(prefix) || ungraded.contains(col),
                "{col} is neither graded (no `{prefix} *` gate row) nor declared ungraded"
            );
            // ...and the graded list must SAY so: a verdict computed from a column that the
            // scope record does not name is the original defect inverted.
            assert_eq!(
                graded_by(prefix),
                graded.contains(col),
                "{col}: gradedSeries must list exactly the channel columns the gate rows grade"
            );
        }
        // and this world grades all four, so the declared list is empty
        assert!(
            !ungraded.contains("log"),
            "every channel here is graded; nothing should be declared ungraded"
        );
    }

    /// A top-level key of the sidecar object: exactly two spaces of indent, then a quoted name.
    /// Nested blocks (`path`, `world`, `gate`) indent by four, so this cannot reach into them.
    fn top_level_keys(lines: &[String]) -> Vec<String> {
        lines
            .iter()
            .filter_map(|l| {
                let rest = l.strip_prefix("  \"")?;
                let name = rest.split('"').next()?;
                if l.starts_with("   ") {
                    None
                } else {
                    Some(name.to_string())
                }
            })
            .collect()
    }

    #[test]
    fn emitted_sidecar_declares_emit_schema() {
        let lines = sidecar_lines("schema");
        let declared = lines.iter().find_map(|l| {
            l.strip_prefix("  \"schema\": ")
                .and_then(|v| v.trim_end_matches(',').parse::<u32>().ok())
        });
        assert_eq!(
            declared,
            Some(EMIT_SCHEMA),
            "the sidecar declares a schema that is not EMIT_SCHEMA — the writer and the constant \
             have come apart"
        );
    }

    #[test]
    fn emitted_sidecar_carries_the_promised_keys() {
        let got = top_level_keys(&sidecar_lines("keys"));
        let want: Vec<String> = EMIT_SIDECAR_KEYS.iter().map(|k| k.to_string()).collect();
        assert_eq!(
            got, want,
            "the sidecar's top-level keys differ from EMIT_SIDECAR_KEYS. Its SHAPE changed: \
             update the contract, and decide in the same edit whether EMIT_SCHEMA (now {}) must \
             be bumped — a reader that pins the schema is relying on that number to mean this \
             shape.",
            EMIT_SCHEMA
        );
    }

    /// The two twins write the same sidecar, so a consumer reading the schema must get the same
    /// answer whichever produced the file. Skipped where the Scala half is absent (source tarball).
    #[test]
    fn scala_twin_declares_the_same_schema() {
        let scala = std::path::Path::new("../src/main/scala/apps/MarketSim.scala");
        let Ok(text) = std::fs::read_to_string(scala) else {
            return;
        };
        let declared = text.lines().find_map(|l| {
            l.trim()
                .strip_prefix("val EmitSchema: Int = ")
                .and_then(|v| v.trim().parse::<u32>().ok())
        });
        assert_eq!(
            declared,
            Some(EMIT_SCHEMA),
            "EmitSchema in the Scala twin differs from EMIT_SCHEMA"
        );
    }
}

/// The bond relations' constants are FITTED NUMBERS, and until now nothing in the repo could
/// re-derive them: the coefficients were written down and the measurements they came from lived in
/// a prose table. These tests close that by re-fitting from the checked-in anchors, so the
/// constants are derivable rather than asserted, and a re-measurement that moves a line fails here
/// instead of silently disagreeing with the code that still carries the old one.
///
/// The Scala twin carries the same checks in `BondAnchorSuite`, against the same file and without
/// needing Rust — the reason the fixture is committed rather than generated.
#[cfg(test)]
mod bond_anchor_tests {
    use super::*;

    const ANCHORS: &str = "../test-data/bond-anchors/ishares-2026-08-22.tsv";

    struct Fund {
        kind: String,
        duration: f64,
        ann_vol: f64,
        d10: f64,
    }

    /// `None` where the fixture is absent, which is a skip and not a failure: the crate ships
    /// without `test-data/`, so a source-tarball build must not fail here.
    fn anchors() -> Option<Vec<Fund>> {
        let text = std::fs::read_to_string(ANCHORS).ok()?;
        Some(
            text.lines()
                .filter(|l| !l.starts_with('#') && !l.starts_with("fund\t") && !l.trim().is_empty())
                .map(|l| {
                    let f: Vec<&str> = l.split('\t').collect();
                    Fund {
                        kind: f[1].to_string(),
                        duration: f[2].parse().expect("duration"),
                        ann_vol: f[3].parse().expect("annVol"),
                        d10: f[5].parse().expect("d10"),
                    }
                })
                .collect(),
        )
    }

    /// Ordinary least squares, returning `(intercept, slope)`.
    fn ols(xs: &[f64], ys: &[f64]) -> (f64, f64) {
        let n = xs.len() as f64;
        let mx = xs.iter().sum::<f64>() / n;
        let my = ys.iter().sum::<f64>() / n;
        let sxy: f64 = xs.iter().zip(ys).map(|(x, y)| (x - mx) * (y - my)).sum();
        let sxx: f64 = xs.iter().map(|x| (x - mx).powi(2)).sum();
        let slope = sxy / sxx;
        (my - slope * mx, slope)
    }

    /// A constant written at `dp` decimals IS the fit, rounded to the precision it is written at.
    /// Comparing that way rather than with an invented epsilon means the assertion states exactly
    /// the claim the source makes and nothing looser.
    fn rounds_to(fit: f64, dp: i32, constant: f64) -> bool {
        let scale = 10f64.powi(dp);
        ((fit * scale).round() / scale - constant).abs() < 1e-12
    }

    fn treasuries(funds: &[Fund]) -> (Vec<f64>, Vec<f64>, Vec<f64>) {
        let t: Vec<&Fund> = funds.iter().filter(|f| f.kind == "treasury").collect();
        (
            t.iter().map(|f| f.duration).collect(),
            t.iter().map(|f| f.ann_vol).collect(),
            t.iter().map(|f| f.d10).collect(),
        )
    }

    #[test]
    fn depth_line_refits_to_the_shipped_constants() {
        let Some(funds) = anchors() else { return };
        let (_, vol, d10) = treasuries(&funds);
        assert_eq!(vol.len(), 5, "the depth line is fitted on five Treasuries");
        let (intercept, slope) = ols(&vol, &d10);
        assert!(
            rounds_to(slope, 4, BOND_D10_SLOPE),
            "re-fitting d10 on annVol over the Treasury anchors gives slope {slope}, which does \
             not round to BOND_D10_SLOPE ({BOND_D10_SLOPE}). Either the anchors were re-measured \
             and the constant was not updated, or the constant was changed without the data."
        );
        assert!(
            rounds_to(intercept, 4, BOND_D10_INTERCEPT),
            "re-fitting gives intercept {intercept}, which does not round to BOND_D10_INTERCEPT \
             ({BOND_D10_INTERCEPT})."
        );
    }

    /// Not used by any code path — it is the justification the `SIGMA_N_BOND` comment gives for
    /// scaling the noise with duration, so it is a claim in prose that can rot. An intercept that
    /// rounds to zero is the whole point of it: a zero-duration bond is cash.
    #[test]
    fn volatility_line_refits_to_the_documented_coefficients() {
        let Some(funds) = anchors() else { return };
        let (dur, vol, _) = treasuries(&funds);
        let (intercept, slope) = ols(&dur, &vol);
        assert!(
            rounds_to(slope, 3, 0.937),
            "vol-on-duration slope {slope} no longer rounds to the documented 0.937"
        );
        assert!(
            rounds_to(intercept, 2, -0.07),
            "vol-on-duration intercept {intercept} no longer rounds to the documented -0.07 — the \
             near-zero intercept is why SIGMA_N_BOND scales with duration at all"
        );
    }

    /// The support ranges and the ladder are FIXTURE-DERIVED VALUES written as literals, and the
    /// re-fit tests alone would let them drift: a re-measured SHY duration would move the fitted
    /// lines (caught) while leaving the supports and the short rung stale (previously uncaught).
    /// Every rung must be derived from the anchors or deliberately, checkably past them.
    #[test]
    fn supports_and_ladder_come_from_the_anchors() {
        let Some(funds) = anchors() else { return };
        let (dur, vol, _) = treasuries(&funds);
        let dmin = dur.iter().copied().fold(f64::INFINITY, f64::min);
        let dmax = dur.iter().copied().fold(f64::NEG_INFINITY, f64::max);
        let vmin = vol.iter().copied().fold(f64::INFINITY, f64::min);
        let vmax = vol.iter().copied().fold(f64::NEG_INFINITY, f64::max);
        assert_eq!(
            BOND_DUR_SUPPORT,
            (dmin, dmax),
            "BOND_DUR_SUPPORT is not the Treasury anchors' duration range"
        );
        assert_eq!(
            BOND_VOL_SUPPORT,
            (vmin, vmax),
            "BOND_VOL_SUPPORT is not the Treasury anchors' volatility range"
        );
        let agg = funds
            .iter()
            .find(|f| f.kind == "blend")
            .expect("the fixture carries the Aggregate row");
        assert_eq!(
            DURATION_LADDER[0], dmin,
            "the short rung must be the shortest anchor fund's duration"
        );
        assert_eq!(
            DURATION_LADDER[1], agg.duration,
            "the intermediate rung must be the Aggregate's duration"
        );
        assert_eq!(
            DURATION_LADDER[2], DURATION_REF,
            "one rung must be the world every other report describes"
        );
        assert!(
            DURATION_LADDER[3] > dmax,
            "the top rung is DELIBERATELY past the anchors; inside them it tests nothing extra"
        );
    }

    /// The band is a SCOPE decision, not only a tolerance, and nothing else in the repo says so in
    /// a form that can fail. Widening it far enough to admit high yield would silently bring an
    /// asset class this model has no channel for into the gate's "level readable" verdict.
    #[test]
    fn the_band_admits_treasuries_and_credit_but_not_high_yield() {
        let Some(funds) = anchors() else { return };
        let ratio = |f: &Fund| {
            let expected = (BOND_D10_SLOPE * f.ann_vol + BOND_D10_INTERCEPT).max(0.0);
            if expected <= 0.0 {
                f64::NAN
            } else {
                f.d10 / expected
            }
        };
        for f in &funds {
            let r = ratio(f);
            let inside = r > BOND_D10_BAND.0 && r < BOND_D10_BAND.1;
            match f.kind.as_str() {
                // SHY sits below the line's zero crossing, so it has no ratio at all — the same
                // `n/a` cell `-crossasset` reports at the short rung.
                "treasury" if r.is_nan() => assert!(
                    f.ann_vol < BOND_D10_ZERO,
                    "a Treasury with no ratio must be one the line cannot reach"
                ),
                "credit-hy" => assert!(
                    !inside,
                    "high yield ({r}) is inside the band — it is out of scope until there is a \
                     credit channel, and the band is what records that"
                ),
                _ => assert!(inside, "{} reads {r}, outside the band", f.duration),
            }
        }
    }
}

/// Contracts on the report machinery itself — no fixture, no ensemble. The Scala twin carries the
/// same checks in `MarketSimContractSuite`.
#[cfg(test)]
mod contract_tests {
    use super::*;

    /// The padding is a promise about SORT ORDER, and it is only kept if every name a batch writes
    /// is the same width. The floor at 3 is the other half of the contract: it is what keeps every
    /// ensemble of 1000 or fewer reading exactly as it did before the width became variable.
    #[test]
    fn index_names_keep_their_width_and_their_history() {
        assert_eq!(indexed_name("f.tsv", 7, index_width(99)), "f-007.tsv");
        assert_eq!(indexed_name("f.tsv", 7, index_width(999)), "f-007.tsv");
        assert_eq!(indexed_name("f.tsv", 7, index_width(1999)), "f-0007.tsv");
        // one batch, one width, whatever the index inside it
        let w = index_width(1999);
        for k in [0usize, 999, 1000, 1999] {
            assert_eq!(indexed_name("f.tsv", k, w).len(), "f-0000.tsv".len());
        }
    }

    /// The news channel displaces diffusive variance, so past `news_rate * news_size^2 =
    /// 252 * SIGMA_N^2` there is none left: the price runs on jumps alone and the bar channels'
    /// world level (realized sd over the MEAN diffusion sd) has no denominator. Such a world is
    /// refused at the CLI, not clamped into a NaN bar — and `-calibrate`'s ranges cannot reach it.
    #[test]
    fn a_news_channel_past_the_diffusion_budget_is_refused() {
        let dw = default_world();
        assert!(news_budget_refusal(dw.news_rate, dw.news_size).is_none());
        assert!(
            news_budget_refusal(0.0, 1.0).is_none(),
            "rate 0 is the channel off"
        );
        assert!(
            news_budget_refusal(1.3, 0.097).is_none(),
            "just inside the budget"
        );
        assert!(
            news_damp_at(1.3, 0.10) <= 0.0,
            "past it the damp clamps to nothing"
        );
        let why = news_budget_refusal(1.3, 0.10).expect("past the budget is refused");
        assert!(why.contains("0.0975"), "{why}");
        let hi = |name: &str| {
            calibrate_ranges()
                .iter()
                .find(|r| r.0 == name)
                .map(|r| r.2)
                .expect("a -calibrate range")
        };
        assert!(news_budget_refusal(hi("newsRate"), hi("newsSize")).is_none());
    }

    /// A range that excludes a shipped world is a search that cannot reach the model: `margin`
    /// shipped at 0.006 from 0.19.1 while the range stopped at 0.004, so for eleven releases
    /// `-calibrate` could not propose the value the default itself uses and every
    /// candidate-vs-default line it printed was across a boundary the candidate could not cross.
    /// The same class as the `fundVol` range that hid a defect for four releases. Releases AND
    /// recipes: a frozen row is permanent, so this must not be scoped to the ones today's gate
    /// still likes.
    #[test]
    fn every_searchable_range_contains_every_frozen_world() {
        let mut worlds: Vec<(&str, World)> = releases();
        worlds.extend(recipes().into_iter().map(|(n, w, _)| (n, w)));
        for (nm, lo, hi, _, get) in calibrate_ranges() {
            for (label, w) in &worlds {
                let v = get(w);
                assert!(
                    v >= lo && v <= hi,
                    "{nm} = {v} in {label} is outside its -calibrate range [{lo}, {hi}]: the                      search cannot reach a world the model has shipped"
                );
            }
        }
    }

    /// `-calibrate` draws one uniform per dial from a single stream in table order, so the order
    /// IS part of the sampler: permute it and the same seed yields a different world. This table
    /// was permuted at positions 15-23 against the Scala twin's through 0.24.1, which is why the
    /// two disagreed on `-calibrate`'s per-sample loss while agreeing byte for byte on
    /// `-validate` and `-fitness`. A search archive's columns are in this order too.
    #[test]
    fn the_searchable_dials_are_in_the_order_both_twins_agree_on() {
        let searched: Vec<&str> = calibrate_ranges().iter().map(|r| r.0).collect();
        assert_eq!(
            searched.as_slice(),
            CALIBRATE_DIAL_ORDER.as_slice(),
            "the searchable dial order changed; CalibrateDialOrder in the Scala twin must match"
        );
    }

    /// An identity parameter describes WHICH ASSET this is, and `-crossasset` grades the bond
    /// relations by moving one. Letting the search fit it makes that grader circular — and the
    /// range row that would do it is one line, added in a moment when the loss looks improvable.
    #[test]
    fn the_search_never_fits_an_identity_parameter() {
        let searched: Vec<&str> = calibrate_ranges().iter().map(|r| r.0).collect();
        for p in IDENTITY_PARAMS {
            assert!(
                !searched.contains(p),
                "`{p}` is an identity parameter (a real fund's measured number) and must not be in \
                 -calibrate's ranges: a value chosen to reduce loss describes no asset anyone can \
                 buy, and -crossasset would then grade the search's own choice"
            );
        }
    }

    /// `-emitfrom` is only safe to chunk with because a shifted range reproduces the paths the
    /// unshifted run would have written at those indices. If this drifts, two chunks of one job
    /// silently stop being one ensemble.
    #[test]
    fn a_shifted_range_is_the_same_ensemble() {
        let w = default_world();
        let all = sim_paths(&w, 6, 2, 12345);
        let tail = sim_path_range(&w, 4, 2, 2, 12345);
        assert_eq!(tail[0].price, all[4].price);
        assert_eq!(tail[1].price, all[5].price);
    }

    /// Every fidelity target must be classified as equity or bond, exactly once. The subset check
    /// this replaces caught renames but not ADDITIONS: a new equity target would simply never
    /// appear in the equity section, and a shorter table reads as a shorter list of concerns.
    #[test]
    fn fit_targets_partition_into_equity_and_bond() {
        let mut expected: Vec<&str> = EQUITY_TARGETS
            .iter()
            .chain(BOND_TARGETS.iter())
            .copied()
            .collect();
        let mut actual: Vec<&str> = fit_targets(SP500_ANCHORS)
            .into_iter()
            .map(|(n, _, _, _)| n)
            .collect();
        expected.sort_unstable();
        actual.sort_unstable();
        assert_eq!(
            actual, expected,
            "the fidelity targets and EQUITY_TARGETS + BOND_TARGETS are no longer the same set. \
             A target was added, removed or renamed: classify it in one list (and only one) so \
             the equity section cannot silently lose or miss a row."
        );
    }

    /// Every fidelity target must carry exactly one anchor horizon, or `-noise` silently skips
    /// it — the same silent-shrinkage failure the equity/bond partition guards against, on the
    /// horizon axis.
    #[test]
    fn every_anchor_set_grades_exactly_the_same_targets() {
        // An anchor set that omits a target would silently drop it from the loss and from
        // `-noise`, and one that names a target that does not exist would fail only when that row
        // was reached. Both are the silent-shrinkage failure the partition tests guard against, on
        // the ASSET axis — which only exists because 0.21.0 made the asset a parameter.
        let reference: Vec<&str> = fit_targets(SP500_ANCHORS)
            .into_iter()
            .map(|(n, _, _, _)| n)
            .collect();
        for a in [SP500_ANCHORS, NASDAQ_ANCHORS] {
            let got: Vec<&str> = fit_targets(a).into_iter().map(|(n, _, _, _)| n).collect();
            assert_eq!(
                got, reference,
                "anchor set [{}] grades a different set of targets than SP500_ANCHORS does",
                a.name
            );
        }
    }

    #[test]
    fn the_sp500_set_holds_the_values_hard_coded_before_0_21_0() {
        // The refactor that made the asset a parameter must not have moved the default world's
        // targets. If one changes, `-validate` changes for every consumer who never asked for a
        // different index.
        //
        // TWO have moved since, deliberately, and both for the same reason — the anchor was not the
        // statistic the model computes:
        //   `med_depth`   -27.1 -> -21.4 (0.22.0), the record's median at a 20% threshold where the
        //                 model measures 15%+ episodes;
        //   `worst_depth` -56.8 -> -84.1 (0.22.1), the worst of 1954-2026, a window that opens AFTER
        //                 the 1929-32 decline setting the record's worst, where the model computes
        //                 the worst over a whole history.
        // `episode_anchor_tests` re-derives both and pins the evidence for what each one used to be.
        // A future move of any value here needs the same treatment: measured, recorded,
        // re-derivable.
        let a = SP500_ANCHORS;
        assert_eq!(a.vol, 16.0);
        assert_eq!(a.ret_vol, 0.69);
        assert_eq!(a.kurt, 28.0);
        assert_eq!(a.ac1, 0.299);
        assert_eq!(a.ac20, 0.225);
        assert_eq!(a.crashes, 20.7);
        assert_eq!(a.med_depth, -21.4); // re-measured in 0.22.0; see episode_anchor_tests
        assert_eq!(a.worst_depth, -84.1); // re-anchored in 0.22.1; see episode_anchor_tests
        assert_eq!(a.vol_band, (14.0, 18.0));
        assert_eq!(a.ret_vol_band, (0.50, 0.85));
    }

    #[test]
    fn the_nasdaq_set_is_the_measured_qqq_vector() {
        // Guards the transcription. Every value is QQQ 1999-03-10..2026-08-20 on the fixture's own
        // definitions, fresh-start peak seeding — see the constant's note for why that window and
        // not `w2001`, whose mid-bear opening reads 40.1 crashes/century against this 25.6.
        let a = NASDAQ_ANCHORS;
        assert_eq!(a.vol, 26.90);
        assert_eq!(a.ret_vol, 0.38);
        assert_eq!(a.kurt, 9.55);
        assert_eq!(a.crashes, 25.6);
        assert_eq!(a.med_depth, -22.8);
        assert_eq!(a.worst_depth, -83.0);
        assert!(
            a.vol > SP500_ANCHORS.vol,
            "the Nasdaq is more volatile than the S&P; if this fails the sets have been swapped"
        );
        assert!(
            a.kurt < SP500_ANCHORS.kurt,
            "QQQ's 27-year kurtosis is BELOW the CRSP century's — a shorter window holds fewer 1987s"
        );
    }

    #[test]
    fn anchor_groups_partition_the_fit_targets() {
        let mut expected: Vec<&str> = anchor_groups(SP500_ANCHORS)
            .into_iter()
            .flat_map(|(_, _, ts)| ts.iter().copied())
            .collect();
        let mut actual: Vec<&str> = fit_targets(SP500_ANCHORS)
            .into_iter()
            .map(|(n, _, _, _)| n)
            .collect();
        expected.sort_unstable();
        actual.sort_unstable();
        assert_eq!(
            actual, expected,
            "the fidelity targets and the anchor groups are no longer the same set. A target was \
             added, removed or renamed: give it a horizon in exactly one anchor group, so the \
             noise report cannot silently skip it."
        );
    }

    /// The three-way branch behind the ladder's verdict line. INCONCLUSIVE exists because a
    /// relation that graded nothing was not tested, and an in-support miss outranks it.
    #[test]
    fn verdict_requires_coverage() {
        assert_eq!(
            cross_asset_verdict(0, 0, &[("a", 3), ("b", 1)]),
            ("PASS", true)
        );
        assert_eq!(
            cross_asset_verdict(1, 0, &[("a", 3), ("b", 0)]),
            ("FAIL", false),
            "a real miss outranks empty coverage"
        );
        assert_eq!(
            cross_asset_verdict(0, 0, &[("a", 3), ("b", 0)]),
            ("INCONCLUSIVE", false),
            "zero graded cells must not read as PASS"
        );
        assert_eq!(
            cross_asset_verdict(0, 1, &[("a", 3), ("b", 1)]),
            ("EDGE", false),
            "a cell within noise of a band edge must not read as PASS"
        );
        assert_eq!(
            cross_asset_verdict(1, 1, &[("a", 3), ("b", 1)]),
            ("FAIL", false),
            "a resolved miss outranks an unresolved edge"
        );
    }

    /// A name that matches nothing classifies no row, so the target it was meant to protect goes
    /// back to being reported as a ratio — silently, and only where someone reads the table.
    #[test]
    fn extreme_targets_name_fidelity_targets_that_exist() {
        let names: Vec<&str> = fit_targets(SP500_ANCHORS)
            .into_iter()
            .map(|(n, _, _, _)| n)
            .collect();
        for n in EXTREME_TARGETS {
            assert!(
                names.contains(n),
                "EXTREME_TARGETS names [{n}], which is not a fidelity target. Rename it with the                  target, or the row is graded as a per-path value again."
            );
        }
    }

    /// The invariant the sidecar rests on. A consumer must be able to tell the two apart from the
    /// DATA — `ratio: null` is what stops the division being made by accident, and a row that
    /// carried both would let it be made anyway.
    #[test]
    fn an_extreme_row_carries_a_percentile_and_no_ratio() {
        let w = default_world();
        let a = SP500_ANCHORS;
        let st = measure(&sim_paths(&w, 60, 100, DEFAULT_SEED), 100);
        let rows = fidelity_rows(a, &st, 60, DEFAULT_SEED, &w);
        let names: Vec<&str> = fit_targets(a).into_iter().map(|(n, _, _, _)| n).collect();
        assert_eq!(
            rows.iter().map(|r| r.name).collect::<Vec<_>>(),
            names,
            "every fidelity target must produce exactly one row, in report order"
        );
        for r in &rows {
            if EXTREME_TARGETS.contains(&r.name) {
                assert!(
                    r.ratio.is_none(),
                    "[{}] is an ensemble extreme and must carry no ratio: model/real grades the                      ensemble size, not the model",
                    r.name
                );
                assert!(
                    r.pctile.is_some(),
                    "[{}] must carry a percentile in the ratio's place",
                    r.name
                );
                assert_eq!(r.aggregation(), "ensemble-extreme");
                assert_eq!(
                    r.horizon_years, a.tail_years,
                    "[{}]'s percentile must be read at its own anchor's horizon, which for the                      tail is its own window and NOT the equity window",
                    r.name
                );
            } else {
                assert!(
                    r.ratio.is_some(),
                    "[{}] is a per-path value and must carry its ratio",
                    r.name
                );
                assert!(
                    r.pctile.is_none(),
                    "[{}] is not an extreme and must not claim a percentile",
                    r.name
                );
                assert_eq!(r.aggregation(), "per-path");
            }
        }
    }

    /// WHY the row carries no ratio, pinned so the fix cannot be undone as cosmetic. `worst_depth`
    /// is a minimum over every episode in the POOLED ensemble while the anchor is the deepest
    /// episode of ONE history, so it deepens without bound as paths grow. The percentile is an
    /// estimate of a fixed quantity and is stable over the same range. Both halves are asserted: a
    /// test that only checked the percentile was stable would also pass if it were constant because
    /// nothing was being measured.
    #[test]
    fn the_worst_crash_level_runs_away_with_the_ensemble_but_the_percentile_does_not() {
        let w = default_world();
        let a = SP500_ANCHORS;
        let at = |paths: usize| -> (f64, FidelityRow) {
            let st = measure(&sim_paths(&w, paths, 100, DEFAULT_SEED), 100);
            let row = fidelity_rows(a, &st, paths, DEFAULT_SEED, &w)
                .into_iter()
                .find(|r| r.name == "worst crash %")
                .expect("no [worst crash %] row");
            (st.worst_depth, row)
        };
        let (lvl_small, small) = at(100);
        let (lvl_large, large) = at(400);
        // COMPARED IN LOGS. The run-away is unbounded but PERCENT depth is not — it saturates at
        // -100%, and at 400 paths every world is already there (0.23.1 -99.62, 0.24.0 -99.65),
        // so a margin in points measures the floor rather than the run-away.
        let log_depth = |lvl: f64| (1.0 + lvl / 100.0).ln();
        assert!(
            log_depth(lvl_large) < log_depth(lvl_small) - 0.5,
            "the pooled minimum must still run away with the ensemble or this test asserts              nothing: {lvl_small:.2}% at 100 paths, {lvl_large:.2}% at 400"
        );
        let p_small = small.pctile.expect("no percentile at 100 paths");
        let p_large = large.pctile.expect("no percentile at 400 paths");
        // The tolerance is the estimator's own noise, not drift: an INTERIOR percentile estimated
        // from n histories carries binomial sd ~ sqrt(p(1-p)/n) — about 4 points at n=100 — where
        // the pooled minimum's movement is unbounded in n.
        assert!(
            p_large.abs_diff(p_small) <= 10,
            "the published percentile must be stable over the range the level runs away across:              {p_small}% at 100 paths, {p_large}% at 400"
        );
        assert_eq!(
            small.miss(),
            large.miss(),
            "and its verdict must not depend on the ensemble size: {p_small}% at 100 paths,              {p_large}% at 400"
        );
    }

    /// `fitness` must price the converging statistic the caller supplies — never `worst_depth`,
    /// the pooled minimum, whose distance from a one-history anchor tracks the ensemble size.
    /// Three pins: the two statistics actually differ here (or the test cannot tell them apart),
    /// the loss row carries the supplied median, and the term is nonzero at the shipped defaults
    /// — a term that cannot bind is the recurring failure class in this file.
    #[test]
    fn the_loss_grades_an_extreme_row_by_the_median_and_it_binds() {
        let w = default_world();
        let a = SP500_ANCHORS;
        let st = measure(&sim_paths(&w, 20, 100, DEFAULT_SEED), 100);
        let ext = extreme_score_stats(a, 20, DEFAULT_SEED, &w);
        let med = *ext
            .get("worst crash %")
            .expect("no scored median for worst crash %");
        assert!(
            (med - st.worst_depth).abs() > 3.0,
            "median {med:.2}% and pooled minimum {:.2}% must differ at this size, or this test              cannot tell which one the loss priced",
            st.worst_depth
        );
        let rows = fitness(a, &st, &ext).1;
        let row = rows
            .iter()
            .find(|(n, _, _, _)| *n == "worst crash %")
            .expect("no worst crash % loss row");
        assert!(
            (row.1 - med).abs() < 1e-12,
            "the loss row must carry the median, not the minimum"
        );
        // The term DISCRIMINATES: with the disaster channel off the century tail is far too
        // shallow and the term prices it; at the adopted defaults it is much smaller. This is what
        // makes the tail term the thing that FOUND the adopted world, and what a cosmetic revert
        // would undo.
        let mut off_w = default_world();
        off_w.disaster_rate = 0.0;
        let off_st = measure(&sim_paths(&off_w, 20, 100, DEFAULT_SEED), 100);
        let off_ext = extreme_score_stats(a, 20, DEFAULT_SEED, &off_w);
        let off_rows = fitness(a, &off_st, &off_ext).1;
        let off_row = off_rows
            .iter()
            .find(|(n, _, _, _)| *n == "worst crash %")
            .expect("no worst crash % loss row");
        // the contrast is a fifth since 0.24.0 (it was a half): the leverage cycle's cascades
        // carry a share of the century tail the disasters used to carry alone
        assert!(
            off_row.3 > row.3 * 1.2,
            "the tail term must price the disaster-off world's shallow century tail well above              the adopted world's: off {:.4} vs on {:.4}",
            off_row.3,
            row.3
        );
        // supplied exactly at the anchor the term is zero — pins that the supplied value is priced
        let at_anchor: std::collections::HashMap<&'static str, f64> =
            [("worst crash %", a.worst_depth)].into_iter().collect();
        let zeroed = fitness(a, &st, &at_anchor).1;
        let z = zeroed
            .iter()
            .find(|(n, _, _, _)| *n == "worst crash %")
            .expect("no worst crash % loss row");
        assert!(z.3.abs() < 1e-12);
        // and a missing entry prices as unmeasurable, never as agreement
        let none: std::collections::HashMap<&'static str, f64> = std::collections::HashMap::new();
        let missing = fitness(a, &st, &none).1;
        let m = missing
            .iter()
            .find(|(n, _, _, _)| *n == "worst crash %")
            .expect("no worst crash % loss row");
        assert!(
            m.3 > 1.0,
            "an unsupplied extreme stat must price as unmeasurable (weight x 4), read {:.4}",
            m.3
        );
    }

    /// One history reads 0% or 100% and neither is a measurement. The failure being prevented is
    /// the 0.22.1 one on a new axis: `miss: false` on a statistic that could not be measured, in
    /// the one field a consumer reads to decide whether to trust the file.
    #[test]
    fn an_extreme_row_with_too_few_histories_reports_a_miss() {
        let w = default_world();
        let a = SP500_ANCHORS;
        let st = measure(&sim_paths(&w, 1, 100, DEFAULT_SEED), 100);
        let r = fidelity_rows(a, &st, 1, DEFAULT_SEED, &w)
            .into_iter()
            .find(|r| r.name == "worst crash %")
            .expect("no [worst crash %] row");
        assert!(
            r.pctile.is_none(),
            "one history cannot place a record, read {:?}",
            r.pctile
        );
        assert!(
            r.miss(),
            "an unplaceable record must report a miss, not a pass"
        );
    }

    /// Mirrors the trading-halt test: the channel's draws come from their own stream, so rate 0
    /// must reproduce the pre-disaster path BIT-IDENTICALLY whatever the other disaster dials say,
    /// and every frozen release row must carry rate 0 — no release before 0.22.1 had the
    /// mechanism.
    #[test]
    fn the_disaster_channel_is_absent_at_zero_and_releases_inherit_that() {
        let mut off = default_world();
        off.disaster_rate = 0.0;
        let mut off2 = off;
        off2.disaster_size = 9.9;
        off2.disaster_len = 0.1;
        off2.disaster_recover = 0.9;
        off2.disaster_rec_len = 0.1;
        let a = simulate(&off, 4, DEFAULT_SEED);
        let b = simulate(&off2, 4, DEFAULT_SEED);
        assert_eq!(
            a.price, b.price,
            "at rate 0 every other disaster dial must be inert, bit for bit"
        );
        // Engagement is checked on the DIAGNOSTIC over a real horizon, not on a short path's
        // bytes: at 0.6/century a 4-year path usually holds no disaster, and the channel leaving
        // such a path untouched is the design, not a defect.
        let on = sim_paths(&default_world(), 4, 100, DEFAULT_SEED);
        assert!(
            on.iter().map(|p| p.disasters).sum::<usize>() > 0,
            "the adopted default must actually strike within four centuries at this seed"
        );
        // The channel shipped in 0.22.1, so only the releases BEFORE it must inherit rate 0.
        for (v, w) in releases() {
            if v < "0.22.1" {
                assert!(
                    w.disaster_rate.abs() < 1e-12,
                    "release {v} predates the disaster channel and must inherit rate 0"
                );
            }
        }
    }

    /// The channel exists to move the CENTURY-WORST distribution, which no gate-passing dial
    /// setting could reach (the sweep of 2026-08-30: recovery, bubble-drag, stress, depth, value,
    /// jumpvar, haltlimit, volofvol, volpersist and fundvol all left the median at -58..-61).
    /// Pinned so it cannot regress to inert: at the adopted defaults the median single-century
    /// worst must be at least 8 points deeper than with the channel off, on the same seed.
    #[test]
    fn the_disaster_channel_discriminates_on_the_statistic_it_was_added_for() {
        let a = SP500_ANCHORS;
        let on = extreme_score_stats(a, 40, DEFAULT_SEED, &default_world());
        let mut off_w = default_world();
        off_w.disaster_rate = 0.0;
        let off = extreme_score_stats(a, 40, DEFAULT_SEED, &off_w);
        let (m_on, m_off) = (on["worst crash %"], off["worst crash %"]);
        assert!(
            m_on < m_off - 8.0,
            "the adopted channel must deepen the median century-worst materially: on {m_on:.1}%              vs off {m_off:.1}%"
        );
    }

    /// The cycle consumes no draws, so share 0 + cap 0 must reproduce the pre-cycle path
    /// BIT-IDENTICALLY whatever the other cycle dials say, and every release before 0.23.0 must
    /// carry both at 0.
    #[test]
    fn the_valuation_cycle_is_absent_at_zero_and_releases_inherit_that() {
        let mut off = default_world();
        off.belief_share = 0.0;
        off.cap_years = 0.0;
        let mut off2 = off;
        off2.belief_years = 0.3;
        off2.cap_window = 0.5;
        let a = simulate(&off, 4, DEFAULT_SEED);
        let b = simulate(&off2, 4, DEFAULT_SEED);
        assert_eq!(
            a.price, b.price,
            "at share 0 and cap 0 every other cycle dial must be inert, bit for bit"
        );
        // The cycle shipped in 0.23.0, so only the releases BEFORE it must inherit share 0 and cap 0.
        for (v, w) in releases() {
            if v < "0.23.0" {
                assert!(
                    w.belief_share.abs() < 1e-12 && w.cap_years.abs() < 1e-12,
                    "release {v} predates the valuation cycle and must inherit share 0 and cap 0"
                );
            }
        }
    }

    /// Dispersion is why the channel exists: every dial sweep at the 0.22.1 world left
    /// sd log(p/fair) at 0.095-0.11 against the record proxy's 0.24-0.41. Pinned so the channel
    /// cannot regress to inert.
    #[test]
    fn the_valuation_cycle_discriminates_on_the_statistic_it_was_added_for() {
        let on = measure(&sim_paths(&default_world(), 40, 100, DEFAULT_SEED), 100);
        let mut off_w = default_world();
        off_w.belief_share = 0.0;
        off_w.cap_years = 0.0;
        let off = measure(&sim_paths(&off_w, 40, 100, DEFAULT_SEED), 100);
        assert!(
            on.val_disp > off.val_disp + 0.08,
            "the cycle must move dispersion materially: on {:.3} vs off {:.3}",
            on.val_disp,
            off.val_disp
        );
        assert!(
            on.val_disp > VAL_DISP_BAND.0 && on.val_disp < VAL_DISP_BAND.1,
            "the adopted default must sit inside its own band, read {:.3}",
            on.val_disp
        );
        assert!(
            off.val_disp < VAL_DISP_BAND.0,
            "the cycle-off world must FAIL the band, or the row does not discriminate: {:.3}",
            off.val_disp
        );
    }

    /// The defect `GATE_YEARS` closes: sd log(p/fair) is the sample sd of a near-integrated
    /// gap, so it GROWS with the measurement window — 0.11 at 30 years against 0.21 at 100 on
    /// the shipped world — and a fixed floor read at the caller's `-years` graded the horizon,
    /// not the world. The ordering is far outside seed noise at 24 paths.
    #[test]
    fn valuation_dispersion_grows_with_the_horizon_so_the_verdict_is_pinned() {
        let w = default_world();
        let short = measure(&sim_paths(&w, 24, 30, DEFAULT_SEED), 30).val_disp;
        let long = measure(&sim_paths(&w, 24, GATE_YEARS, DEFAULT_SEED), GATE_YEARS).val_disp;
        assert!(
            short < long * 0.8,
            "short-horizon dispersion should read well below the century's: 30y {short:.3} vs 100y {long:.3}"
        );
    }

    /// Every release predates the asymmetry dials, so the frozen rows carry leverage 0 and
    /// downShock 0 — and jumpSkew 0.4, the CONSTANT those releases compiled in, which is that
    /// dial's off-position rather than 0.
    #[test]
    fn the_asymmetry_dials_are_inert_in_every_frozen_release() {
        for (v, w) in releases().into_iter().filter(|(v, _)| *v < "0.23.0") {
            assert!(
                w.leverage == 0.0
                    && w.down_shock == 0.0
                    && w.jump_skew == 0.4
                    && w.news_rate == 0.0
                    && w.news_size == 0.0
                    && w.refuge_days == 0.0,
                "release {v} predates the asymmetry mechanisms and must carry 0 / 0 / 0.4 / 0 / 0 / 0"
            );
        }
    }

    #[test]
    fn the_satellite_dials_are_inert_in_every_frozen_release() {
        for (v, w) in releases() {
            assert!(
                w.sat_beta == 0.0 && w.sat_idio == 0.0,
                "release {v} predates the satellite leg and must carry 0 / 0"
            );
        }
        // The engagement contract's off half: no satellite series exists to consume, and no
        // logSat column is written (schema 8 makes the column conditional on the dial).
        let p = simulate(&default_world(), 2, DEFAULT_SEED);
        assert!(
            p.sat.is_empty(),
            "satBeta 0 must produce no satellite series"
        );
    }

    /// The channel rows are not decoration: a leg or a bar that is materially wrong must FAIL
    /// the gate, and a channels-off world must carry none of these rows at all. Without the
    /// second half the first is cheap — a row that never appears cannot be wrong.
    #[test]
    fn the_channel_gate_rows_appear_only_when_the_channel_runs_and_can_fail() {
        let off = measure(&sim_paths(&default_world(), 8, 40, DEFAULT_SEED), 40);
        assert!(off.sat.is_none() && off.bars.is_none());
        let names_off: Vec<String> = gate_checks(SP500_ANCHORS, &off)
            .into_iter()
            .map(|(n, _, _)| n)
            .collect();
        assert!(
            !names_off
                .iter()
                .any(|n| n.starts_with("satellite") || n.starts_with("bar ")),
            "a channels-off world must carry no channel rows"
        );

        let mut on = default_world();
        on.sat_beta = 1.2;
        on.sat_idio = 0.77;
        on.range_scale = 0.63;
        on.range_down = 0.09;
        on.vol_idio = 0.34;
        let st_on = measure(&sim_paths(&on, 8, 40, DEFAULT_SEED), 40);
        assert!(st_on.sat.is_some() && st_on.bars.is_some());
        let rows_on = gate_checks(SP500_ANCHORS, &st_on);
        assert!(
            rows_on
                .iter()
                .filter(|(n, _, _)| n.starts_with("satellite"))
                .count()
                >= 9,
            "the satellite must be graded on its full relational vector"
        );
        assert!(rows_on.iter().any(|(n, _, _)| n.starts_with("bar ")));

        // A leg at more than twice its anchored beta is not a second index; the gate must say so.
        let mut broken = on;
        broken.sat_beta = 2.6;
        let st_b = measure(&sim_paths(&broken, 8, 40, DEFAULT_SEED), 40);
        assert!(
            gate_checks(SP500_ANCHORS, &st_b)
                .iter()
                .any(|(n, ok, _)| n.starts_with("satellite") && !ok),
            "a 2.6-beta leg must fail a satellite row"
        );
        // A bar sampled at a wildly wrong scale is not a bar.
        let mut wide = on;
        wide.range_scale = 2.0;
        let st_w = measure(&sim_paths(&wide, 8, 40, DEFAULT_SEED), 40);
        assert!(
            gate_checks(SP500_ANCHORS, &st_w)
                .iter()
                .any(|(n, ok, _)| n.starts_with("bar ") && !ok),
            "a 3x-scale bar must fail a bar row"
        );
    }

    #[test]
    fn the_range_channel_is_inert_in_every_frozen_release() {
        for (v, w) in releases() {
            assert!(
                w.range_scale == 0.0 && w.range_down == 0.0,
                "release {v} predates the range channel and must carry 0 / 0"
            );
        }
        // Off half: no bars exist. On half: the extremes bracket every bar — the sidecar's
        // canary is blind to this dial until it joins the world block, so these are the guards.
        let off = simulate(&default_world(), 2, DEFAULT_SEED);
        assert!(off.log_hi.is_empty() && off.log_lo.is_empty());
        let mut w = default_world();
        w.range_scale = 0.63;
        let on = simulate(&w, 2, DEFAULT_SEED);
        assert_eq!(on.log_hi.len(), on.price.len());
        let mut prev = on.price[0].ln();
        for i in 0..on.price.len() {
            let c = on.price[i].ln();
            assert!(
                on.log_hi[i] >= prev.max(c) - 1e-9 && on.log_lo[i] <= prev.min(c) + 1e-9,
                "bar {i} extremes must bracket open and close"
            );
            prev = c;
        }
    }

    #[test]
    fn the_volume_channel_is_inert_in_every_frozen_release() {
        for (v, w) in releases() {
            assert!(
                w.vol_idio == 0.0,
                "release {v} predates the volume channel and must carry 0"
            );
        }
        // Off half: no series. On half: filled, finite, and volume leaves the RANGE series
        // bit-identical — the channels share nothing but the sampled bar itself.
        let off = simulate(&default_world(), 2, DEFAULT_SEED);
        assert!(off.log_volume.is_empty());
        let mut rw = default_world();
        rw.range_scale = 0.63;
        let bars_only = simulate(&rw, 2, DEFAULT_SEED);
        let mut w = rw;
        w.vol_idio = 0.34;
        let on = simulate(&w, 2, DEFAULT_SEED);
        assert_eq!(on.log_volume.len(), on.price.len());
        assert!(on.log_volume.iter().all(|x| x.is_finite()));
        assert_eq!(
            on.log_hi, bars_only.log_hi,
            "volume must not move the sampled bars"
        );
        assert_eq!(on.log_lo, bars_only.log_lo);
    }

    /// Each dial moves the statistic it was added for, materially, on the same seed — the same
    /// discrimination bar the valuation cycle's dial met.
    #[test]
    fn the_asymmetry_dials_discriminate_on_their_own_statistics() {
        let dw = default_world();
        let on = measure(&sim_paths(&dw, 40, 100, DEFAULT_SEED), 100);
        // leverage: the adopted 0.12 vs off, on the row it was added for
        let mut lw = dw;
        lw.leverage = 0.0;
        let loff = measure(&sim_paths(&lw, 40, 100, DEFAULT_SEED), 100);
        assert!(
            on.lev_corr < loff.lev_corr - 0.03,
            "leverage 0.12 must deepen the leverage corr materially: {:.3} vs {:.3}",
            on.lev_corr,
            loff.lev_corr
        );
        // the news channel: the adopted 1.3 x 0.033 vs off, on the downside excess
        let mut nw = dw;
        nw.news_rate = 0.0;
        let noff = measure(&sim_paths(&nw, 40, 100, DEFAULT_SEED), 100);
        assert!(
            on.semi_excess > noff.semi_excess + 1.5,
            "the news channel must raise the downside excess materially: {:.2} vs {:.2}",
            on.semi_excess,
            noff.semi_excess
        );
        // the settled-stress refuge: the adopted lag vs live stress, on the calm-day hedge
        let mut rw = dw;
        rw.refuge_days = 0.0;
        let roff = measure(&sim_paths(&rw, 40, 100, DEFAULT_SEED), 100);
        assert!(
            on.tail_hedge > roff.tail_hedge + 0.10,
            "refugeDays 1 must weaken the calm-day stock-bond coupling materially: {:.2} vs {:.2}",
            on.tail_hedge,
            roff.tail_hedge
        );
        // downShock ships at 0 but must still discriminate when engaged
        let mut dsw = dw;
        dsw.down_shock = 0.05;
        let ds = measure(&sim_paths(&dsw, 40, 100, DEFAULT_SEED), 100);
        assert!(
            ds.semi_excess > on.semi_excess + 1.0,
            "downShock 0.05 must raise the downside excess materially: {:.2} vs {:.2}",
            ds.semi_excess,
            on.semi_excess
        );
    }

    /// `-atrelease` resolves exactly the rows `-releases` grades, plus the current default —
    /// and nothing else.
    #[test]
    fn atrelease_resolves_every_frozen_release_and_the_current_default() {
        for (v, w) in releases() {
            assert!(
                release_world(v) == Some(w),
                "release {v} must resolve to its frozen world"
            );
        }
        assert!(
            release_world(VERSION) == Some(default_world()),
            "the current version must resolve to the shipped default"
        );
        assert!(
            release_world("0.0.0").is_none(),
            "an unknown version must not resolve"
        );
        // A version row resolves with NO anchor set (the caller's -anchors stands); a recipe
        // resolves with the set it was verified against; a recipe name must never shadow a
        // version.
        for (v, w) in releases() {
            assert!(named_world(v) == Some((w, None)));
        }
        for (n, w, a) in recipes() {
            assert!(
                named_world(n) == Some((w, Some(a))),
                "recipe {n} must resolve with its anchors"
            );
            assert!(
                !releases().iter().any(|(v, _)| *v == n),
                "recipe {n} shadows a release version"
            );
            let _ = anchors_named(a); // dies on an unknown set
        }
        assert!(
            named_world("0.0.0-nasdaq").is_none(),
            "an unknown recipe must not resolve"
        );
    }

    /// The row was frozen when 0.23.1 development opened. Until a default moves, the literal and
    /// `default_world` must agree field for field, or `-atrelease 0.23.0` under the next binary
    /// would reproduce a world 0.23.0 never shipped. (Once `VERSION` moves on, `release_world` no
    /// longer reaches `default_world` for 0.23.0 and this test compares the row with itself.)
    #[test]
    fn the_frozen_0_23_0_row_is_the_shipped_default_while_the_version_is_0_23_0() {
        let row = releases()
            .into_iter()
            .find(|(v, _)| *v == "0.23.0")
            .map(|(_, w)| w)
            .expect("no 0.23.0 row");
        if VERSION == "0.23.0" {
            assert!(
                row == default_world(),
                "the 0.23.0 row has drifted from the shipped default"
            );
        }
    }

    /// Pins the recipe to the docs' "A Nasdaq world that passes the gate" and to the ANCHORED
    /// channel dials, so neither can drift under a retune: the recipe is built on the frozen row
    /// and differs from it in these nine fields only.
    #[test]
    fn the_nasdaq_recipe_is_the_frozen_0_23_0_world_with_exactly_the_published_dials_moved() {
        let (name, w, a) = recipes()
            .into_iter()
            .find(|(n, _, _)| *n == "0.23.0-nasdaq")
            .expect("no Nasdaq recipe");
        let mut want = releases()
            .into_iter()
            .find(|(v, _)| *v == "0.23.0")
            .map(|(_, w)| w)
            .expect("no 0.23.0 row");
        want.depth = 10.0;
        want.drift = 0.105;
        want.jump_var = 0.02;
        want.fund_vol = 0.06;
        want.sat_beta = 1.2;
        want.sat_idio = 0.77;
        want.range_scale = 0.63;
        want.range_down = 0.09;
        want.vol_idio = 0.34;
        assert_eq!(a, "nasdaq");
        assert!(
            w == want,
            "{name} must differ from the frozen 0.23.0 world in the nine published dials only"
        );
        assert!(
            w.range_scale > 0.0 && w.sat_beta > 0.0 && w.vol_idio > 0.0,
            "the recipe exists to name a world that emits every channel"
        );
    }

    /// Every verdict surface — gate classes, fidelity table, sidecars — grades at the
    /// calibration horizon whatever `-years` the caller simulates; `-emitgate 0` is the one
    /// explicit opt-out. At the defaults the verdict ensemble IS the report ensemble.
    #[test]
    fn the_verdict_ensemble_is_pinned_to_the_calibration_horizon() {
        assert_eq!(verdict_spec(false, 200, 200, 100), (200, 100));
        assert_eq!(verdict_spec(false, 200, 200, 30), (200, GATE_YEARS));
        assert_eq!(verdict_spec(true, 200, 40, 33), (200, GATE_YEARS));
        assert_eq!(verdict_spec(true, 50, 300, 33), (300, GATE_YEARS));
        assert_eq!(verdict_spec(true, 200, 300, 100), (300, GATE_YEARS));
        assert_eq!(verdict_spec(true, 0, 40, 33), (40, 33));
    }

    fn member_file(body: &[String]) -> String {
        let mut v = vec![
            "[".to_string(),
            "  {".to_string(),
            "    \"member\": 0,".to_string(),
            "    \"seededFrom\": \"current\",".to_string(),
            "    \"score\": 0.000000,".to_string(),
            "    \"worstRow\": \"none\",".to_string(),
            "    \"world\": {".to_string(),
        ];
        v.push(body.join(",\n"));
        v.push("    }".to_string());
        v.push("  }".to_string());
        v.push("]".to_string());
        v.join("\n")
    }

    #[test]
    fn worldset_reads_a_member_back_as_exactly_the_flags_that_wrote_it() {
        // THE LOADER RESTS ON ONE RULE: a world block's key IS its flag name, lowercased. Assert
        // it over every dial rather than trusting it -- a dial whose flag does not follow the rule
        // makes `-worldset` die on an unrecognized argument, loudly, but only for whoever runs a
        // member next.
        let body = world_json_body(&default_world());
        let keys: Vec<String> = body
            .iter()
            .filter_map(|l| json_field(l.trim()).map(|(k, _)| k))
            .collect();
        let got = world_set_flags(&member_file(&body), "f.json", 0).expect("the default loads");
        assert_eq!(
            got.len(),
            body.len() * 2,
            "a flag and a value for every dial"
        );
        let flags: Vec<&String> = got.iter().step_by(2).collect();
        let want: Vec<String> = keys
            .iter()
            .map(|k| format!("-{}", k.to_lowercase()))
            .collect();
        assert_eq!(flags, want.iter().collect::<Vec<&String>>());
        let vals: Vec<&String> = got.iter().skip(1).step_by(2).collect();
        let at = |k: &str| keys.iter().position(|x| x == k).expect(k);
        assert_eq!(vals[at("depth")], &ef(default_world().depth));
        assert!(
            !vals[at("crowd")].starts_with('"'),
            "a mode name loses its quotes"
        );
    }

    #[test]
    fn a_world_block_that_is_not_exactly_this_binarys_dials_is_refused_never_defaulted() {
        // A dial the file omits would take the shipped default and be a DIFFERENT world wearing a
        // member's name, which is the one failure a consumer could not see.
        let full = world_json_body(&default_world());
        let short: Vec<String> = full
            .iter()
            .filter(|l| !l.contains("\"volOfVol\""))
            .cloned()
            .collect();
        let why = world_set_flags(&member_file(&short), "f.json", 0).expect_err("refused");
        assert!(why.contains("missing [volOfVol]"), "{why}");
        let mut more = full.clone();
        more.push("    \"noSuchDial\": 1.0".to_string());
        let why = world_set_flags(&member_file(&more), "f.json", 0).expect_err("refused");
        assert!(why.contains("unknown [noSuchDial]"), "{why}");
        let why = world_set_flags(&member_file(&full), "f.json", 7).expect_err("refused");
        assert!(why.contains("-worldindex 7 is not in"), "{why}");
    }

    #[test]
    fn no_realism_band_edge_sits_inside_a_fidelity_targets_own_noise_for_the_same_statistic() {
        // THE RECORD IS ADMISSIBLE BY DEFINITION. A realism band answers "is this a market at
        // all", so an edge within the estimator's own noise of a FIDELITY target for the same
        // statistic calls the real market not a market on some share of seeds. Kurtosis 4-30
        // against an S&P century of 28.0 did exactly that, and was a third of a calibration
        // search's rejections.
        //
        // A target's spread is its frozen `sd_rel` -- ONE history's relative sd, from `-noise` --
        // and `measure` reads these rows as a median over paths, so the median's spread is that
        // over sqrt(paths). 200 paths is the scoring ensemble those spreads are frozen against.
        const SCORING_PATHS: f64 = 200.0;
        const K: f64 = 3.0;
        // (distance from the target to the nearer edge, K of the median's own spreads)
        let room = |band: (f64, f64), target: f64, sd_rel: f64| {
            (
                (target - band.0).abs().min((band.1 - target).abs()),
                K * sd_rel * target.abs() / SCORING_PATHS.sqrt(),
            )
        };

        // The rule catches the band it was written for, so the assertions below can fail.
        let sp = SP500_ANCHORS;
        let (old_edge, old_need) = room((4.0, 30.0), sp.kurt, sp.kurt_sd);
        assert!(
            old_edge < old_need,
            "4-30 should violate: {old_edge:.3} vs {old_need:.3}"
        );

        for a in [SP500_ANCHORS, NASDAQ_ANCHORS] {
            for (name, band, target, sd_rel) in [
                ("equity vol", REALISM_VOL, a.vol, a.vol_sd),
                ("kurtosis", REALISM_KURT, a.kurt, a.kurt_sd),
                ("clustering", REALISM_AC1, a.ac1, a.ac1_sd),
                ("crash rate", REALISM_CRASHES, a.crashes, a.crashes_sd),
            ] {
                let (edge, need) = room(band, target, sd_rel);
                assert!(
                    edge >= need,
                    "{} {name}: the band {}-{} sits {edge:.3} from the target {target}, inside {K} of its own spreads ({need:.3})",
                    a.name,
                    band.0,
                    band.1
                );
            }
        }
    }

    #[test]
    fn each_direct_extreme_reading_is_what_measure_reads_on_that_path() {
        // The extreme rows skip `measure` per path; this holds each direct reading to the full path
        // bit for bit, on horizons short enough that some paths have no episode (the NaN arm).
        for (w, spec) in [
            (default_world(), "sp500"),
            (named_world("0.24.3-nasdaq").expect("recipe").0, "nasdaq"),
        ] {
            let a = anchors_named(spec);
            for years in [2usize, 8, 40] {
                let sims = sim_paths(&w, 6, years, 20_260_813 + years as u64);
                for nm in EXTREME_TARGETS.iter().copied() {
                    let (_, get, _, _) = fit_targets(a)
                        .into_iter()
                        .find(|(n, _, _, _)| *n == nm)
                        .expect("an extreme target is a fidelity target");
                    for p in &sims {
                        let Some(direct) = extreme_reading(nm, p) else {
                            continue;
                        };
                        let full = get(&measure(std::slice::from_ref(p), years));
                        assert!(
                            direct.to_bits() == full.to_bits()
                                || (direct.is_nan() && full.is_nan()),
                            "{nm} at {years}y: direct {direct} against measure's {full}"
                        );
                    }
                }
            }
        }
    }
}

/// The equity depth relation's constants are FITTED NUMBERS. These re-derive every one of them from
/// the checked-in anchors, so `EQUITY_D5_CORR`/`EQUITY_D10_CORR`/`EQUITY_D20_CORR`,
/// `EQUITY_VOL_SUPPORT` and the two gate bands are derivable rather than asserted, and a
/// re-measurement that moves the data fails here instead of silently disagreeing with the code that
/// still carries the old fit.
///
/// The Scala twin carries the same checks in `EquityAnchorSuite`, against the same file.
///
/// The two claims that are ABOUT THE FORM rather than the fit are pinned here too, because they are
/// the reason to believe a relation stated this way at all: that the correction reaches 1.00 at the
/// top of the real volatility range (the most volatile equity markets spend random-walk time under
/// water), and that the deep rung's relation is the one that does not transport, which is why it is
/// a fit target but not a gate band.
#[cfg(test)]
mod equity_anchor_tests {
    use super::*;

    const ANCHORS: &str = "../test-data/equity-anchors/yahoo-2026-08-24.tsv";

    struct Row {
        window: String,
        vol: f64,
        rv: f64,
        d: [f64; 3],
    }

    /// The rungs in report order, with the constant each one pins.
    const RUNGS: [(f64, &str); 3] = [
        (0.05, "EQUITY_D5_CORR"),
        (0.10, "EQUITY_D10_CORR"),
        (0.20, "EQUITY_D20_CORR"),
    ];

    fn corr_of(i: usize) -> (f64, f64) {
        [EQUITY_D5_CORR, EQUITY_D10_CORR, EQUITY_D20_CORR][i]
    }

    /// `None` where the fixture is absent, which is a skip and not a failure: the crate ships
    /// without `test-data/`, so a source-tarball build must not fail here.
    fn anchors() -> Option<Vec<Row>> {
        let text = std::fs::read_to_string(ANCHORS).ok()?;
        Some(
            text.lines()
                .filter(|l| {
                    !l.starts_with('#') && !l.starts_with("window\t") && !l.trim().is_empty()
                })
                .map(|l| {
                    let f: Vec<&str> = l.split('\t').collect();
                    let vol: f64 = f[3].parse().expect("annVol");
                    let ann: f64 = f[7].parse().expect("annRet");
                    Row {
                        window: f[0].to_string(),
                        vol,
                        rv: ann / vol,
                        d: [
                            f[4].parse().expect("d5"),
                            f[5].parse().expect("d10"),
                            f[6].parse().expect("d20"),
                        ],
                    }
                })
                .collect(),
        )
    }

    /// The block the relation is fitted from is `w2001w`, the warm-peak re-measurement whose peaks
    /// are seeded from each instrument's full prior history. The cold `w2001` block is retained in
    /// the fixture as the measurement of what truncation costs, and must never be fitted from.
    fn block<'a>(rows: &'a [Row], window: &str) -> Vec<&'a Row> {
        rows.iter().filter(|r| r.window == window).collect()
    }

    /// Least squares on the LOG ratio, by Gauss-Newton — the estimator the constants were fitted
    /// with, and the reason is in `EQUITY_D10_CORR`: the quantity is graded as a ratio, and OLS on
    /// the raw ratio leaves the deep rung's median real instrument at 0.91 of its own line.
    fn log_fit(rows: &[&Row], i: usize, rung: f64) -> (f64, f64) {
        let (mut a, mut b) = (0.4f64, 0.02f64);
        for _ in 0..200 {
            let (mut j00, mut j01, mut j11, mut g0, mut g1) = (0.0, 0.0, 0.0, 0.0, 0.0);
            for r in rows {
                let c = (a + b * r.vol).max(1e-6);
                let resid = (r.d[i] / (c * gbm_depth_share(rung, r.vol, r.rv))).ln();
                let (da, db) = (-1.0 / c, -r.vol / c);
                j00 += da * da;
                j01 += da * db;
                j11 += db * db;
                g0 += da * resid;
                g1 += db * resid;
            }
            let det = j00 * j11 - j01 * j01;
            if det.abs() > 1e-18 {
                a -= (j11 * g0 - j01 * g1) / det;
                b -= (j00 * g1 - j01 * g0) / det;
            }
        }
        (a, b)
    }

    /// A constant written at `dp` decimals IS the fit, rounded to the precision it is written at.
    fn rounds_to(fit: f64, dp: i32, constant: f64) -> bool {
        let scale = 10f64.powi(dp);
        ((fit * scale).round() / scale - constant).abs() < 1e-12
    }

    fn ratios(rows: &[&Row], i: usize, rung: f64) -> Vec<f64> {
        let mut v: Vec<f64> = rows
            .iter()
            .map(|r| r.d[i] / equity_depth_expected(rung, corr_of(i), r.vol, r.rv))
            .collect();
        v.sort_by(f64::total_cmp);
        v
    }

    #[test]
    fn every_rung_refits_to_the_shipped_constants() {
        let Some(rows) = anchors() else { return };
        let fit = block(&rows, "w2001w");
        assert_eq!(
            fit.len(),
            35,
            "the relation is fitted on the 35 warm-peak instruments"
        );
        for (i, (rung, name)) in RUNGS.iter().enumerate() {
            let (a, b) = log_fit(&fit, i, *rung);
            let c = corr_of(i);
            assert!(
                rounds_to(a, 4, c.0),
                "re-fitting the {rung} rung on {ANCHORS} gives intercept {a}, which does not \
                 round to {name}.0 ({}). Either the anchors were re-measured and the constant was \
                 not updated, or the constant was changed without the data.",
                c.0
            );
            assert!(
                rounds_to(b, 5, c.1),
                "re-fitting the {rung} rung gives slope {b}, which does not round to {name}.1 ({})",
                c.1
            );
        }
    }

    #[test]
    fn the_median_real_instrument_sits_at_one() {
        let Some(rows) = anchors() else { return };
        let fit = block(&rows, "w2001w");
        // This is what the log-ratio estimator buys, and it is the property that keeps the target
        // of 1.00 honest: a median away from 1.00 would mean a target of 1.00 asks the model to
        // differ from a typical real fund, which is the defect the relation replaced.
        for (i, (rung, name)) in RUNGS.iter().enumerate() {
            let r = ratios(&fit, i, *rung);
            let med = r[r.len() / 2];
            assert!(
                (med - 1.0).abs() < 0.02,
                "the median real ratio at the {rung} rung is {med}, not 1.00, so {name} is no \
                 longer centred on the instruments it was fitted from"
            );
        }
    }

    #[test]
    fn the_correction_reaches_random_walk_time_at_the_top_of_the_range() {
        // The reason to believe the FORM. All three rungs land here independently; if a
        // re-measurement breaks it, the relation is no longer "real markets recover faster than
        // chance, and the fastest markets are the calmest" and the comment saying so must change.
        let top = EQUITY_VOL_SUPPORT.1;
        for (i, (_, name)) in RUNGS.iter().enumerate() {
            let c = corr_of(i);
            let v = c.0 + c.1 * top;
            assert!(
                (v - 1.0).abs() < 0.05,
                "{name}'s correction reads {v} at the top of the real volatility range ({top}%), \
                 not ~1.00: the most volatile real equity markets no longer spend random-walk time \
                 under water"
            );
        }
    }

    #[test]
    fn the_support_is_the_anchors_own_volatility_range() {
        let Some(rows) = anchors() else { return };
        let fit = block(&rows, "w2001w");
        let lo = fit.iter().map(|r| r.vol).fold(f64::INFINITY, f64::min);
        let hi = fit.iter().map(|r| r.vol).fold(f64::NEG_INFINITY, f64::max);
        assert!(
            (lo - EQUITY_VOL_SUPPORT.0).abs() < 1e-9,
            "EQUITY_VOL_SUPPORT's floor is not the fitted instruments' lowest volatility ({lo})"
        );
        assert!(
            (hi - EQUITY_VOL_SUPPORT.1).abs() < 1e-9,
            "EQUITY_VOL_SUPPORT's ceiling is not the fitted instruments' highest volatility ({hi})"
        );
    }

    #[test]
    fn the_graded_bands_admit_every_real_instrument() {
        let Some(rows) = anchors() else { return };
        // The bands are a SCOPE statement: these funds are what the relation is about, so a band
        // that excluded one of them would be calling a real equity fund unrealistic.
        for (i, band, name) in [
            (0usize, EQUITY_D5_BAND, "EQUITY_D5_BAND"),
            (1usize, EQUITY_D10_BAND, "EQUITY_D10_BAND"),
        ] {
            let rung = RUNGS[i].0;
            let mut all = ratios(&block(&rows, "w2001w"), i, rung);
            all.extend(ratios(&block(&rows, "w1996"), i, rung));
            let lo = all.iter().copied().fold(f64::INFINITY, f64::min);
            let hi = all.iter().copied().fold(f64::NEG_INFINITY, f64::max);
            assert!(
                lo > band.0 && hi < band.1,
                "{name} {band:?} does not admit every real instrument: the ratios run \
                 {lo:.3}..{hi:.3}"
            );
        }
    }

    #[test]
    fn the_deep_rung_is_not_gated_because_no_band_could_fail() {
        let Some(rows) = anchors() else { return };
        // Recorded as a test so the omission reads as a decision rather than an oversight, and so
        // that a re-measurement which TIGHTENS the deep rung tells someone it can now be graded.
        let mut all = ratios(&block(&rows, "w2001w"), 2, 0.20);
        all.extend(ratios(&block(&rows, "w1996"), 2, 0.20));
        let lo = all.iter().copied().fold(f64::INFINITY, f64::min);
        let hi = all.iter().copied().fold(f64::NEG_INFINITY, f64::max);
        assert!(
            hi / lo > 3.0,
            "the 20% rung's real ratios now span only {lo:.2}..{hi:.2}; a band there could \
             discriminate, so it should be gated like the other two"
        );
        let st = measure(&sim_paths(&default_world(), 4, 20, 1), 20);
        let gated: Vec<String> = gate_checks(SP500_ANCHORS, &st)
            .into_iter()
            .map(|(n, _, _)| n)
            .collect();
        assert!(
            !gated.iter().any(|n| n.contains("d20")),
            "a d20 gate band has appeared in {gated:?}"
        );
    }
}

/// `VAR_RATIO_BANDS` is a FITTED NUMBER in the same sense the depth relation's constants are: it is
/// the real cross-section's own range, rounded outward. This re-derives both bounds from the
/// checked-in readings, so the band is derivable rather than asserted, and a band widened to admit a
/// world fails here instead of quietly becoming a band that grades nothing.
///
/// The Scala twin carries the same checks in `PersistenceAnchorSuite`, against the same file.
#[cfg(test)]
mod persistence_anchor_tests {
    use super::*;

    const FIXTURE: &str = "../test-data/equity-anchors/persistence-2026-09-11.tsv";

    struct Row {
        window: String,
        ticker: String,
        vr: [(usize, f64); 4],
        ac1: f64,
    }

    impl Row {
        fn at(&self, q: usize) -> f64 {
            self.vr.iter().find(|r| r.0 == q).expect("rung").1
        }
    }

    const STEP: f64 = 0.05;

    fn outward(x: f64, up: bool) -> f64 {
        let n = if up {
            (x / STEP).ceil()
        } else {
            (x / STEP).floor()
        };
        (n * STEP * 1e6).round() / 1e6
    }

    fn rows() -> Option<Vec<Row>> {
        let text = std::fs::read_to_string(FIXTURE).ok()?;
        Some(
            text.lines()
                .filter(|l| {
                    !l.starts_with('#') && !l.starts_with("window\t") && !l.trim().is_empty()
                })
                .map(|l| {
                    let f: Vec<&str> = l.split('\t').collect();
                    let v = |i: usize| -> f64 { f[i].parse().expect("vr") };
                    Row {
                        window: f[0].to_string(),
                        ticker: f[1].to_string(),
                        vr: [(20, v(5)), (60, v(6)), (120, v(7)), (250, v(8))],
                        ac1: v(9),
                    }
                })
                .collect(),
        )
    }

    /// The rung the ladder cannot see is REPORTED rather than graded, so the only thing that can
    /// go wrong is the printed claim drifting from the evidence. This pins it, and pins the era
    /// flip that is the reason it is not graded.
    #[test]
    fn the_lag_1_readings_the_report_quotes_are_the_files_own() {
        let Some(rows) = rows() else { return };
        let crsp = |w: &str| -> f64 {
            rows.iter()
                .find(|r| r.window == w && r.ticker == "CRSP-VW")
                .unwrap_or_else(|| panic!("no {w} CRSP row"))
                .ac1
        };
        let funds: Vec<f64> = rows
            .iter()
            .filter(|r| r.ticker != "CRSP-VW")
            .map(|r| r.ac1)
            .collect();
        let f_lo = funds.iter().copied().fold(f64::INFINITY, f64::min);
        let f_hi = funds.iter().copied().fold(f64::NEG_INFINITY, f64::max);
        let (rc26, rc54, rc90, rf_lo, rf_hi) = RET_AC1_RECORD;
        for (quoted, actual, what) in [
            (rc26, crsp("c1926"), "the century reading"),
            (rc54, crsp("c1954"), "the 1954 reading"),
            (rc90, crsp("c1990"), "the 1990 reading"),
            (rf_lo, f_lo, "the modern funds' low"),
            (rf_hi, f_hi, "the modern funds' high"),
        ] {
            assert!(
                (quoted - actual).abs() < 5e-5,
                "{what} the report quotes is {quoted}, the file says {actual}"
            );
        }
        assert!(
            crsp("c1926") > 0.0 && crsp("c1990") < 0.0,
            "the era flip is what makes a single lag-1 target meaningless"
        );
        assert!(
            funds.iter().all(|&x| x < 0.0),
            "every modern fund reverts at one day"
        );
    }

    #[test]
    fn every_rungs_envelope_is_the_real_range_rounded_outward() {
        let Some(rows) = rows() else { return };
        let rungs: Vec<usize> = VAR_RATIO_BANDS.iter().map(|b| b.0).collect();
        assert_eq!(rungs, VAR_RATIO_LADDER.to_vec());
        for (q, lo, hi) in VAR_RATIO_BANDS {
            let mn = rows.iter().map(|r| r.at(q)).fold(f64::INFINITY, f64::min);
            let mx = rows
                .iter()
                .map(|r| r.at(q))
                .fold(f64::NEG_INFINITY, f64::max);
            assert!(
                (lo - outward(mn, false)).abs() < 1e-9,
                "vr{q}: the low bound no longer follows from the fixture: readings start at {mn:.3}"
            );
            assert!(
                (hi - outward(mx, true)).abs() < 1e-9,
                "vr{q}: the high bound no longer follows from the fixture: readings reach {mx:.3}"
            );
        }
        let rung60 = VAR_RATIO_BANDS
            .iter()
            .find(|b| b.0 == VAR_RATIO_Q)
            .map(|b| (b.1, b.2));
        assert!(
            rung60 == Some((0.55, 1.20)),
            "the loss row's rung carries the 60-session envelope"
        );
    }

    #[test]
    fn every_slope_band_is_the_real_range_rounded_outward() {
        let Some(rows) = rows() else { return };
        for (a, b, lo, hi) in VAR_RATIO_SLOPE_BANDS {
            let xs: Vec<f64> = rows.iter().map(|r| r.at(b) - r.at(a)).collect();
            let mn = xs.iter().cloned().fold(f64::INFINITY, f64::min);
            let mx = xs.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
            assert!(
                (lo - outward(mn, false)).abs() < 1e-9,
                "slope {a}->{b} low: readings start at {mn:.3}"
            );
            assert!(
                (hi - outward(mx, true)).abs() < 1e-9,
                "slope {a}->{b} high: readings reach {mx:.3}"
            );
        }
    }

    /// Implied by the rules above and asserted anyway, because this is the property that matters:
    /// the gate uses STRICT inequalities, so a bound landing exactly on a real reading would fail
    /// the market that produced it.
    #[test]
    fn the_profile_admits_every_real_reading() {
        let Some(rows) = rows() else { return };
        for r in &rows {
            for (q, lo, hi) in VAR_RATIO_BANDS {
                assert!(
                    r.at(q) > lo && r.at(q) < hi,
                    "{} over {} reads vr{q} {:.3}, outside the envelope the gate enforces",
                    r.ticker,
                    r.window,
                    r.at(q)
                );
            }
            for (a, b, lo, hi) in VAR_RATIO_SLOPE_BANDS {
                let sl = r.at(b) - r.at(a);
                assert!(
                    sl > lo && sl < hi,
                    "{} over {} has slope {a}->{b} {sl:+.3}, outside the band the gate enforces",
                    r.ticker,
                    r.window
                );
            }
        }
    }

    /// The reason the slopes exist: 0.70 at 20 sessions and 1.15 at 60 are each inside their
    /// envelope and no real series has that shape.
    #[test]
    fn the_profile_row_fails_a_world_the_boxes_admit() {
        let mut st = measure(&sim_paths(&default_world(), 4, 30, DEFAULT_SEED), 30);
        st.vr20 = 0.70;
        st.vr60 = 1.15;
        st.vr120 = 1.15;
        st.vr250 = 1.15;
        let (name, pass, cls) = var_ratio_profile_check(&st);
        assert!(
            !pass,
            "a +0.45 slope between the short rungs must fail the profile: {name}"
        );
        assert!(cls == GateClass::Fidelity);
        assert!(
            name.contains("20d 0.70-1.15") && name.contains("20->60 -0.20..+0.10"),
            "the row's name must carry the bounds it enforces: {name}"
        );
    }

    /// Why the bands are shared rather than carried per asset. Two indices as different as the
    /// Nasdaq-100 and the S&P over the same era agree far more closely than one index does with
    /// itself across eras — so a per-asset band would encode a difference the record does not
    /// show, and would have to be invented for every new anchor set.
    #[test]
    fn the_era_separates_these_readings_and_the_index_does_not() {
        let Some(rows) = rows() else { return };
        let at = |w: &str, t: &str| {
            rows.iter()
                .find(|r| r.window == w && r.ticker == t)
                .map(|r| r.at(60))
        };
        match (
            at("wfull", "QQQ"),
            at("wfull", "SPY"),
            at("c1926", "CRSP-VW"),
            at("c1990", "CRSP-VW"),
        ) {
            (Some(qqq), Some(spy), Some(century), Some(modern)) => {
                let across_index = (qqq - spy).abs();
                let across_era = (century - modern).abs();
                assert!(
                    across_index < across_era / 2.0,
                    "QQQ and SPY now differ by {across_index:.3} against {across_era:.3} between \
                     the CRSP century and 1990-2026. If the index has become the larger axis, the \
                     band belongs in Anchors, per asset, and this test is the one that says so."
                );
            }
            _ => panic!("the fixture no longer carries the four rows this claim is pinned on"),
        }
    }
}

/// The six asymmetry anchors are MEASURED numbers; these re-derive every one from the checked-in
/// fixtures so the shipped literal and the record reading cannot drift apart. The fixtures also
/// hold a committed NEGATIVE result — the Patton-Sheppard signed-half regression's era-split
/// columns — which these tests pin so nobody re-fights that measurement.
///
/// The Scala twin carries the same checks in `AsymmetryAnchorSuite`, against the same files.
#[cfg(test)]
mod asymmetry_anchor_tests {
    use super::*;

    const ASYM: &str = "../test-data/equity-anchors/asymmetry-2026-08-31.tsv";
    const TAIL: &str = "../test-data/bond-anchors/tailcorr-2026-08-31.tsv";

    /// `None` where a fixture is absent — the crate ships without `test-data/`, so a
    /// source-tarball build must not fail here.
    fn rows(path: &str) -> Option<Vec<Vec<String>>> {
        let text = std::fs::read_to_string(path).ok()?;
        Some(
            text.lines()
                .filter(|l| {
                    !l.starts_with('#')
                        && !l.starts_with("window\t")
                        && !l.starts_with("pair\t")
                        && !l.trim().is_empty()
                })
                .map(|l| l.split('\t').map(str::to_string).collect())
                .collect(),
        )
    }

    fn field(rows: &[Vec<String>], key0: &str, key1: &str, col: usize) -> f64 {
        rows.iter()
            .find(|r| r[0] == key0 && r[1] == key1)
            .unwrap_or_else(|| panic!("fixture row [{key0} {key1}] missing"))[col]
            .parse()
            .expect("numeric fixture field")
    }

    #[test]
    fn the_shipped_asymmetry_anchors_are_the_fixture_rows() {
        let Some(a) = rows(ASYM) else { return };
        // sdRatio column 5, levCorr column 9; the shipped excess is 100*(sdRatio - 1).
        let sp_excess = (field(&a, "c1954", "CRSP-VW", 5) - 1.0) * 100.0;
        let qq_excess = (field(&a, "wfull", "QQQ", 5) - 1.0) * 100.0;
        assert!(
            (SP500_ANCHORS.semi_excess - sp_excess).abs() < 0.005,
            "S&P downside vol excess: shipped {} vs fixture {sp_excess}",
            SP500_ANCHORS.semi_excess
        );
        assert!(
            (NASDAQ_ANCHORS.semi_excess - qq_excess).abs() < 0.005,
            "QQQ downside vol excess: shipped {} vs fixture {qq_excess}",
            NASDAQ_ANCHORS.semi_excess
        );
        assert!(
            (SP500_ANCHORS.lev_corr - field(&a, "c1954", "CRSP-VW", 9)).abs() < 5e-5,
            "S&P leverage corr drifted from the fixture"
        );
        assert!(
            (NASDAQ_ANCHORS.lev_corr - field(&a, "wfull", "QQQ", 9)).abs() < 5e-5,
            "QQQ leverage corr drifted from the fixture"
        );
    }

    #[test]
    fn the_shipped_tail_hedge_anchors_are_the_fixture_rows() {
        let Some(t) = rows(TAIL) else { return };
        let sp = t.iter().find(|r| r[0] == "SPY/TLT").expect("SPY/TLT row")[4]
            .parse::<f64>()
            .expect("corrL");
        let qq = t.iter().find(|r| r[0] == "QQQ/TLT").expect("QQQ/TLT row")[4]
            .parse::<f64>()
            .expect("corrL");
        assert!(
            (SP500_ANCHORS.tail_hedge - sp).abs() < 5e-4,
            "S&P tail hedge drifted from the fixture"
        );
        assert!(
            (NASDAQ_ANCHORS.tail_hedge - qq).abs() < 5e-4,
            "QQQ tail hedge drifted from the fixture"
        );
    }

    /// The committed negative result: on close-only daily data the signed-half block regression
    /// flips sign between CRSP eras, so it cannot anchor a row — the daily leverage correlation,
    /// which does not flip, is what the shipped row grades. Pinned so the settled measurement is
    /// not re-fought each cycle (the `longhorizon-2026-08-30.tsv` pattern).
    #[test]
    fn the_signed_half_regression_is_era_split_and_the_leverage_corr_is_not() {
        let Some(a) = rows(ASYM) else { return };
        let lev_asym_1926 = field(&a, "c1926", "CRSP-VW", 8);
        let lev_asym_1990 = field(&a, "c1990", "CRSP-VW", 8);
        assert!(
            lev_asym_1926 < 0.0 && lev_asym_1990 > 0.0,
            "the era split this fixture exists to record has changed: c1926 {lev_asym_1926} c1990 {lev_asym_1990}"
        );
        for w in ["c1926", "c1954", "c1990"] {
            let lc = field(&a, w, "CRSP-VW", 9);
            assert!(
                (-0.11..=-0.08).contains(&lc),
                "CRSP {w} leverage corr {lc} left the stable range the anchor relies on"
            );
        }
    }
}

/// `med_depth` is a MEASURED number and it was measured wrong once. Through 0.21.0 it shipped as
/// -27.1% with no recorded convention, while the model measures every peak-to-trough decline of 15%
/// or worse; no window of the record produces -27.1% at that threshold and a 20% threshold does.
/// This re-derives the shipped value from the checked-in readings so the anchor and the statistic it
/// is compared against cannot drift apart again.
///
/// The Scala twin carries the same checks in `EpisodeAnchorSuite`, against the same file.
#[cfg(test)]
mod episode_anchor_tests {
    use super::*;

    const FIXTURE: &str = "../test-data/equity-anchors/episodes-2026-08-29.tsv";

    /// The threshold `episodes` is called with in `measure`. If this moves, the anchor moves with
    /// it — which is the whole failure this module exists to prevent.
    const MODEL_THRESHOLD: u32 = 15;

    struct Row {
        window: String,
        thr: u32,
        per_century: f64,
        median: f64,
        worst: f64,
    }

    /// `None` where the fixture is absent, which is a skip and not a failure: the crate ships
    /// without `test-data/`.
    fn rows() -> Option<Vec<Row>> {
        let text = std::fs::read_to_string(FIXTURE).ok()?;
        Some(
            text.lines()
                .filter(|l| {
                    !l.starts_with('#') && !l.starts_with("window\t") && !l.trim().is_empty()
                })
                .map(|l| {
                    let f: Vec<&str> = l.split('\t').collect();
                    Row {
                        window: f[0].to_string(),
                        thr: f[1].parse().expect("thr"),
                        per_century: f[3].parse().expect("perCentury"),
                        median: f[4].parse().expect("median"),
                        worst: f[5].parse().expect("worst"),
                    }
                })
                .collect(),
        )
    }

    fn at<'a>(rows: &'a [Row], window: &str, thr: u32) -> Option<&'a Row> {
        rows.iter().find(|r| r.window == window && r.thr == thr)
    }

    #[test]
    fn med_depth_is_the_record_at_the_models_own_threshold() {
        let Some(rows) = rows() else { return };
        let row = at(&rows, "w1954", MODEL_THRESHOLD).expect("no w1954 row at the model threshold");
        assert!(
            (SP500_ANCHORS.med_depth - row.median).abs() < 0.05,
            "the anchor no longer matches the record measured the way the model measures: the \
             fixture reads {:.1}% over 1954-2026 at a {}% threshold",
            row.median,
            MODEL_THRESHOLD
        );
    }

    #[test]
    fn no_window_reproduces_the_pre_0_22_anchor_at_the_models_threshold() {
        let Some(rows) = rows() else { return };
        let at_model: Vec<f64> = rows
            .iter()
            .filter(|r| r.thr == MODEL_THRESHOLD)
            .map(|r| r.median)
            .collect();
        assert!(
            at_model.iter().all(|m| (m + 27.1).abs() > 2.0),
            "a window now reads near -27.1% at the model's own threshold ({at_model:?}); the 0.22.0 \
             re-measurement rested on no window doing so, so re-read it"
        );
        let at_20: Vec<f64> = rows
            .iter()
            .filter(|r| r.thr == 20)
            .map(|r| r.median)
            .collect();
        assert!(
            at_20.iter().any(|m| (m + 27.1).abs() < 1.5),
            "no window reads near -27.1% at a 20% threshold either ({at_20:?}); the explanation for \
             where the old anchor came from no longer holds"
        );
    }

    #[test]
    fn crashes_per_century_still_reconciles_across_windows() {
        let Some(rows) = rows() else { return };
        let century = at(&rows, "w1926", MODEL_THRESHOLD).expect("no w1926 row");
        let modern = at(&rows, "w1954", MODEL_THRESHOLD).expect("no w1954 row");
        let crashes = SP500_ANCHORS.crashes;
        assert!(
            crashes >= century.per_century && crashes <= modern.per_century,
            "crashes/century {crashes:.1} no longer sits between the record's {:.1} and {:.1}",
            century.per_century,
            modern.per_century
        );
    }

    /// This replaces a test that asserted the opposite. It pinned `worst_depth` to `w1954.worst`,
    /// and 1954 opens AFTER the 1929-32 decline that sets the record's worst — so the check
    /// certified an anchor that had the tail removed, on the one row a window can delete outright.
    /// A test can hold a mis-specified anchor in place as firmly as it holds a correct one.
    #[test]
    fn worst_depth_is_the_deepest_episode_of_the_whole_record() {
        let Some(rows) = rows() else { return };
        let century = at(&rows, "w1926", MODEL_THRESHOLD).expect("no w1926 row");
        assert!(
            (SP500_ANCHORS.worst_depth - century.worst).abs() < 0.05,
            "worst_depth must be the deepest episode of the LONGEST window in the fixture, which              reads {:.1}% over 1926-2026 at a {MODEL_THRESHOLD}% threshold",
            century.worst
        );
        assert_eq!(
            SP500_ANCHORS.tail_years, 100,
            "the tail's horizon must be the window its anchor was read over, or the percentile in              -validate is read at a length the anchor never described"
        );
    }

    /// The DISCRIMINATING half: without this, re-anchoring reads as a taste change. The record's
    /// worst is the single most window-sensitive statistic in the fixture — 54% between windows,
    /// against 11% for median depth and 30% for the crash rate — and the shipped value was the
    /// shallow end of that range.
    #[test]
    fn no_shorter_window_could_have_produced_the_tail_anchor() {
        let Some(rows) = rows() else { return };
        let worsts: Vec<f64> = rows
            .iter()
            .filter(|r| r.thr == MODEL_THRESHOLD)
            .map(|r| r.worst)
            .collect();
        let deepest = worsts.iter().copied().fold(f64::INFINITY, f64::min);
        let shallowest = worsts.iter().copied().fold(f64::NEG_INFINITY, f64::max);
        assert!(
            (deepest - shallowest).abs() / shallowest.abs() > 0.4,
            "the fixture no longer shows the worst episode as strongly window-dependent              ({worsts:?}); the reason this anchor needs its own window would no longer hold"
        );
        let modern = at(&rows, "w1954", MODEL_THRESHOLD).expect("no w1954 row");
        assert!(
            (modern.worst - -54.6).abs() < 0.05,
            "w1954's worst is the value that shipped as -56.8 through 0.22.0 — pinned so the              account of what was wrong stays checkable rather than asserted"
        );
        assert!(
            SP500_ANCHORS.worst_depth < modern.worst - 20.0,
            "the shipped anchor {:.1}% is no deeper than the truncated window's {:.1}%; the              re-anchoring has been undone",
            SP500_ANCHORS.worst_depth,
            modern.worst
        );
    }

    #[test]
    fn deeper_thresholds_give_deeper_medians() {
        let Some(rows) = rows() else { return };
        let mut windows: Vec<&str> = rows.iter().map(|r| r.window.as_str()).collect();
        windows.sort_unstable();
        windows.dedup();
        for w in windows {
            let mut by_thr: Vec<&Row> = rows.iter().filter(|r| r.window == w).collect();
            by_thr.sort_by_key(|r| r.thr);
            for pair in by_thr.windows(2) {
                assert!(
                    pair[1].median <= pair[0].median + 1e-9,
                    "[{w}] median depth does not decrease with the threshold"
                );
            }
        }
    }
}

/// The two bond crash-response targets are MEDIANS across drawdown episodes, and through 0.21.0 both
/// shipped as single episodes: `+20.0` is 2008 alone, the largest of five, and `-25.0` is a rounding
/// of the one inflation-regime drawdown. This re-derives both from the checked-in episodes so the
/// targets and the statistic they are compared against cannot drift apart again.
///
/// The Scala twin carries the same checks in `BondCrashSuite`, against the same file.
#[cfg(test)]
mod bond_crash_tests {
    use super::*;

    const FIXTURE: &str = "../test-data/bond-anchors/crash-response-2026-08-29.tsv";

    struct Row {
        equity_pct: f64,
        bond_pct: f64,
        regime: String,
    }

    /// `None` where the fixture is absent, which is a skip and not a failure.
    fn rows() -> Option<Vec<Row>> {
        let text = std::fs::read_to_string(FIXTURE).ok()?;
        Some(
            text.lines()
                .filter(|l| !l.starts_with('#') && !l.starts_with("peak\t") && !l.trim().is_empty())
                .map(|l| {
                    let f: Vec<&str> = l.split('\t').collect();
                    Row {
                        equity_pct: f[2].parse().expect("equityPct"),
                        bond_pct: f[3].parse().expect("bondPct"),
                        regime: f[4].to_string(),
                    }
                })
                .collect(),
        )
    }

    fn median_of(rows: &[Row], regime: &str) -> f64 {
        let mut v: Vec<f64> = rows
            .iter()
            .filter(|r| r.regime == regime)
            .map(|r| r.bond_pct)
            .collect();
        v.sort_by(|a, b| a.partial_cmp(b).unwrap());
        if v.is_empty() {
            f64::NAN
        } else if v.len() % 2 == 1 {
            v[v.len() / 2]
        } else {
            (v[v.len() / 2 - 1] + v[v.len() / 2]) / 2.0
        }
    }

    fn target(name: &str) -> f64 {
        fit_targets(SP500_ANCHORS)
            .into_iter()
            .find(|(n, _, _, _)| *n == name)
            .map(|(_, _, t, _)| t)
            .unwrap_or_else(|| panic!("no target [{name}]"))
    }

    #[test]
    fn both_bond_crash_targets_are_the_records_medians() {
        let Some(rows) = rows() else { return };
        assert!(
            (target("bond growth-crash") - median_of(&rows, "growth")).abs() < 0.05,
            "the growth-crash target no longer matches the record's median across its growth-shock \
             drawdowns ({:.1}%)",
            median_of(&rows, "growth")
        );
        assert!(
            (target("bond infl-crash") - median_of(&rows, "inflation")).abs() < 0.05,
            "the inflation-crash target no longer matches the record's inflation-regime drawdown \
             ({:.1}%)",
            median_of(&rows, "inflation")
        );
    }

    #[test]
    fn the_pre_0_22_targets_were_the_extremes() {
        let Some(rows) = rows() else { return };
        let growth: Vec<f64> = rows
            .iter()
            .filter(|r| r.regime == "growth")
            .map(|r| r.bond_pct)
            .collect();
        let mx = growth.iter().copied().fold(f64::NEG_INFINITY, f64::max);
        assert!(
            (mx - 22.4).abs() < 0.05,
            "the largest growth-shock bond rally is no longer 2008's; the account of where +20.0 \
             came from rests on it"
        );
        assert!(
            mx > median_of(&rows, "growth") * 2.0,
            "the growth episodes no longer have a max ({mx:.1}) far above their median; if the \
             spread has closed, re-read the anchor's provenance"
        );
    }

    #[test]
    fn the_episode_set_is_what_the_model_would_count() {
        let Some(rows) = rows() else { return };
        assert!(
            rows.iter().all(|r| r.equity_pct <= -15.0),
            "an episode shallower than the model's 15% threshold is in the fixture"
        );
        assert_eq!(
            rows.iter().filter(|r| r.regime == "inflation").count(),
            1,
            "the record's inflation-regime drawdown count has changed; the -34.7% target is a \
             median of one, so a second episode changes the target"
        );
        assert!(
            rows.len() >= 5,
            "only {} episodes; the medians below that are not worth the name",
            rows.len()
        );
    }
}

/// The satellite leg's coupling anchors are MEASURED numbers; this re-derives the graded
/// bands from the checked-in fixture so the anchored dials and the record cannot drift apart.
/// The Scala twin carries the same check in `JointCouplingSuite`, against the same file.
/// The distribution and conditioning rows (`tol` = `-`) belong to the python grader over
/// `-jointemit` output, not to these tests.
#[cfg(test)]
mod joint_coupling_tests {
    use super::*;

    const COUPLING: &str = "../test-data/equity-anchors/joint-coupling-2026-08-31.tsv";

    /// `None` where the fixture is absent — the crate ships without `test-data/`, so a
    /// source-tarball build must not fail here.
    fn rows(path: &str) -> Option<Vec<Vec<String>>> {
        let text = std::fs::read_to_string(path).ok()?;
        Some(
            text.lines()
                .filter(|l| !l.starts_with('#') && !l.starts_with("pair\t") && !l.trim().is_empty())
                .map(|l| l.split('\t').map(str::to_string).collect())
                .collect(),
        )
    }

    /// (value, tol) of a GRADED w1999 row; panics on a `-` tol, which marks a row these
    /// tests must not consume.
    fn band(rows: &[Vec<String>], stat: &str) -> (f64, f64) {
        let r = rows
            .iter()
            .find(|r| r[0] == "w1999" && r[1] == stat)
            .unwrap_or_else(|| panic!("fixture row [w1999 {stat}] missing"));
        (
            r[2].parse().expect("numeric fixture value"),
            r[3].parse().expect("graded row wants a numeric tol"),
        )
    }

    fn simple_rets(px: &[f64]) -> Vec<f64> {
        (0..px.len() - 1).map(|i| px[i + 1] / px[i] - 1.0).collect()
    }

    fn mean(x: &[f64]) -> f64 {
        x.iter().sum::<f64>() / x.len() as f64
    }

    fn corr_of(a: &[f64], b: &[f64]) -> f64 {
        let (ma, mb) = (mean(a), mean(b));
        let mut caa = 0.0;
        let mut cbb = 0.0;
        let mut cab = 0.0;
        for i in 0..a.len() {
            let (da, db) = (a[i] - ma, b[i] - mb);
            caa += da * da;
            cbb += db * db;
            cab += da * db;
        }
        cab / (caa * cbb).sqrt()
    }

    fn beta_of(sat: &[f64], pri: &[f64]) -> f64 {
        let (ms, mp) = (mean(sat), mean(pri));
        let mut cpp = 0.0;
        let mut csp = 0.0;
        for i in 0..sat.len() {
            cpp += (pri[i] - mp) * (pri[i] - mp);
            csp += (sat[i] - ms) * (pri[i] - mp);
        }
        csp / cpp
    }

    fn sd(x: &[f64]) -> f64 {
        let m = mean(x);
        (x.iter().map(|v| (v - m) * (v - m)).sum::<f64>() / x.len() as f64).sqrt()
    }

    /// median of four: mean of the middle pair
    fn med4(mut x: Vec<f64>) -> f64 {
        x.sort_by(f64::total_cmp);
        (x[1] + x[2]) / 2.0
    }

    #[test]
    fn the_satellite_leg_discriminates_and_sits_on_its_coupling_anchors() {
        let Some(a) = rows(COUPLING) else { return };
        let mut w = default_world();
        w.sat_beta = 1.2;
        w.sat_idio = 0.77;
        let sims = sim_paths(&w, 4, 100, DEFAULT_SEED);
        let mut corrs = Vec::new();
        let mut acorrs = Vec::new();
        let mut ratios = Vec::new();
        let mut betas = Vec::new();
        for p in &sims {
            assert_eq!(
                p.sat.len(),
                p.price.len(),
                "satBeta on must fill the satellite"
            );
            let r1 = simple_rets(&p.price);
            let r2 = simple_rets(&p.sat);
            corrs.push(corr_of(&r1, &r2));
            let abs1: Vec<f64> = r1.iter().map(|v| v.abs()).collect();
            let abs2: Vec<f64> = r2.iter().map(|v| v.abs()).collect();
            acorrs.push(corr_of(&abs1, &abs2));
            ratios.push(sd(&r2) / sd(&r1));
            betas.push(beta_of(&r2, &r1));
        }
        for (stat, got) in [
            ("corr", med4(corrs)),
            ("absCorr", med4(acorrs)),
            ("volRatio", med4(ratios)),
            ("beta", med4(betas)),
        ] {
            let (v, tol) = band(&a, stat);
            assert!(
                (got - v).abs() <= tol,
                "{stat}: model median {got:.3} vs anchor {v:.3} +/- {tol:.2}"
            );
        }
    }
}

/// The bar channels' anchors are MEASURED numbers; this re-derives the graded bands and the
/// volume channel's frozen structural constants from the checked-in fixture so the code and
/// the record cannot drift apart. The Scala twin carries the same checks in `BarsAnchorSuite`,
/// against the same file. The `-` rows belong to the python graders over `-barsemit` output.
#[cfg(test)]
mod bars_anchor_tests {
    use super::*;

    const BARS: &str = "../test-data/equity-anchors/bars-2026-09-01.tsv";

    /// `None` where the fixture is absent — the crate ships without `test-data/`, so a
    /// source-tarball build must not fail here.
    fn rows(path: &str) -> Option<Vec<Vec<String>>> {
        let text = std::fs::read_to_string(path).ok()?;
        Some(
            text.lines()
                .filter(|l| !l.starts_with('#') && !l.starts_with("pair\t") && !l.trim().is_empty())
                .map(|l| l.split('\t').map(str::to_string).collect())
                .collect(),
        )
    }

    fn value(rows: &[Vec<String>], pair: &str, stat: &str) -> f64 {
        rows.iter()
            .find(|r| r[0] == pair && r[1] == stat)
            .unwrap_or_else(|| panic!("fixture row [{pair} {stat}] missing"))[2]
            .parse()
            .expect("numeric fixture value")
    }

    fn band(rows: &[Vec<String>], stat: &str) -> (f64, f64) {
        let r = rows
            .iter()
            .find(|r| r[0] == "w1993" && r[1] == stat)
            .unwrap_or_else(|| panic!("fixture row [w1993 {stat}] missing"));
        (
            r[2].parse().expect("numeric fixture value"),
            r[3].parse().expect("graded row wants a numeric tol"),
        )
    }

    fn mean(x: &[f64]) -> f64 {
        x.iter().sum::<f64>() / x.len() as f64
    }

    fn sd(x: &[f64]) -> f64 {
        let m = mean(x);
        (x.iter().map(|v| (v - m) * (v - m)).sum::<f64>() / x.len() as f64).sqrt()
    }

    fn corr_of(a: &[f64], b: &[f64]) -> f64 {
        let (ma, mb) = (mean(a), mean(b));
        let mut caa = 0.0;
        let mut cbb = 0.0;
        let mut cab = 0.0;
        for i in 0..a.len() {
            let (da, db) = (a[i] - ma, b[i] - mb);
            caa += da * da;
            cbb += db * db;
            cab += da * db;
        }
        cab / (caa * cbb).sqrt()
    }

    fn acf1(x: &[f64]) -> f64 {
        corr_of(&x[..x.len() - 1], &x[1..])
    }

    /// median of four: mean of the middle pair
    fn med4(mut x: Vec<f64>) -> f64 {
        x.sort_by(f64::total_cmp);
        (x[1] + x[2]) / 2.0
    }

    #[test]
    fn the_volume_constants_are_the_fixture_rows() {
        let Some(a) = rows(BARS) else { return };
        assert_eq!(VOL_SLOPE, value(&a, "const", "volSlope"));
        assert_eq!(VOL_LAG, value(&a, "const", "volLag"));
        assert_eq!(VOL_DOWN, value(&a, "const", "volDown"));
        assert_eq!(VOL_PHI, value(&a, "const", "volPhi"));
        assert_eq!(VOL_SLOW_SHARE, value(&a, "const", "volSlowShare"));
    }

    #[test]
    fn the_bar_channels_sit_on_their_anchors() {
        let Some(a) = rows(BARS) else { return };
        let mut w = default_world();
        w.range_scale = 0.63;
        w.vol_idio = 0.34;
        let sims = sim_paths(&w, 4, 100, DEFAULT_SEED);
        let mut mroc = Vec::new();
        let mut racf = Vec::new();
        let mut vsd = Vec::new();
        let mut vcx = Vec::new();
        for p in &sims {
            let r: Vec<f64> = (0..p.price.len() - 1)
                .map(|i| (p.price[i + 1] / p.price[i]).ln())
                .collect();
            let x: Vec<f64> = (0..p.log_hi.len())
                .map(|i| p.log_hi[i] - p.log_lo[i])
                .collect();
            mroc.push(mean(&x) / sd(&r));
            racf.push(acf1(&x));
            // The model's turnover index is trendless, so the record side's rolling-median
            // detrend is a no-op up to noise here; the raw series is graded.
            vsd.push(sd(&p.log_volume));
            vcx.push(corr_of(&p.log_volume, &x));
        }
        for (stat, got) in [
            ("rangeMeanOverCcvol", med4(mroc)),
            ("rangeAcf1", med4(racf)),
            ("volSd", med4(vsd)),
            ("volCorrRange", med4(vcx)),
        ] {
            let (v, tol) = band(&a, stat);
            assert!(
                (got - v).abs() <= tol,
                "{stat}: model median {got:.3} vs anchor {v:.3} +/- {tol:.2}"
            );
        }
    }
}

/// The drawdown-shape references are MEASURED numbers on the model's own episode definition and
/// median; this re-derives both anchor sets' references from the checked-in fixture so the code
/// and the record cannot drift apart. The Scala twin carries the same checks in
/// `DdShapeAnchorSuite`, against the same file.
#[cfg(test)]
mod dd_shape_anchor_tests {
    use super::*;

    const FIXTURE: &str = "../test-data/equity-anchors/ddshape-2026-09-02.tsv";

    /// `None` where the fixture is absent — the crate ships without `test-data/`, so a
    /// source-tarball build must not fail here.
    fn rows() -> Option<Vec<Vec<String>>> {
        let text = std::fs::read_to_string(FIXTURE).ok()?;
        Some(
            text.lines()
                .filter(|l| !l.starts_with('#') && !l.starts_with("set\t") && !l.trim().is_empty())
                .map(|l| l.split('\t').map(str::to_string).collect())
                .collect(),
        )
    }

    fn set_name(a: &Anchors) -> &'static str {
        if a.name == SP500_ANCHORS.name {
            "sp500"
        } else if a.name == NASDAQ_ANCHORS.name {
            "nasdaq"
        } else {
            panic!("no fixture set name for {}", a.name)
        }
    }

    #[test]
    fn every_reference_row_of_both_anchor_sets_is_the_fixtures_row() {
        let Some(rs) = rows() else {
            return;
        };
        for a in [SP500_ANCHORS, NASDAQ_ANCHORS] {
            let set = set_name(&a);
            assert!(
                !a.dd_refs.is_empty(),
                "{} carries no shape reference",
                a.name
            );
            for r in a.dd_refs {
                for (thr, eps, per_yr, depth, decl, recov, undw, wds) in r.rows {
                    let pct = ((thr * 100.0) as usize).to_string();
                    let f = rs
                        .iter()
                        .find(|x| {
                            x[0] == set
                                && x[1] == r.series
                                && x[5] == pct
                                && format!("{}-{}", &x[2][..4], &x[3][..4]) == r.window
                        })
                        .unwrap_or_else(|| {
                            panic!(
                                "fixture row [{set} {} {} {pct}%] missing",
                                r.series, r.window
                            )
                        });
                    let tag = format!("{} {} {} {pct}%", a.name, r.series, r.window);
                    let num = |i: usize| -> f64 { f[i].parse().expect("numeric fixture value") };
                    assert!((r.years - num(4)).abs() < 0.005, "{tag} years");
                    assert_eq!(eps, num(6) as usize, "{tag} episodes");
                    assert!((per_yr - num(7)).abs() < 0.0005, "{tag} per year");
                    assert!((depth - num(8)).abs() < 0.05, "{tag} depth");
                    assert_eq!(decl, num(9) as usize, "{tag} decline");
                    assert_eq!(recov, num(10) as usize, "{tag} recovery");
                    assert_eq!(undw, num(11) as usize, "{tag} underwater");
                    assert!((wds - num(12)).abs() < 0.0005, "{tag} worst-day share");
                }
            }
        }
    }

    /// The ratio reads against the FIRST reference, so it must be the one with the most episodes
    /// behind its medians; the fixture's shorter windows exist to show the spread, not to anchor.
    #[test]
    fn each_reference_carries_both_thresholds_and_the_primary_is_the_longest_history() {
        for a in [SP500_ANCHORS, NASDAQ_ANCHORS] {
            for r in a.dd_refs {
                let thrs: Vec<f64> = r.rows.iter().map(|row| row.0).collect();
                assert_eq!(
                    thrs,
                    vec![0.10, 0.20],
                    "{} {} {}",
                    a.name,
                    r.series,
                    r.window
                );
            }
            let longest = a
                .dd_refs
                .iter()
                .map(|r| r.years)
                .fold(f64::NEG_INFINITY, f64::max);
            assert!(
                a.dd_refs[0].years >= longest,
                "{}: the primary reference must be the longest history",
                a.name
            );
        }
    }

    /// The 0.23.0 rows were NumPy medians and disagreed with the model rows by up to a third of a
    /// statistic at four episodes. Pinned here so the convention cannot silently revert.
    #[test]
    fn the_reference_median_is_the_models_own_upper_middle_element_never_the_average() {
        assert_eq!(pctile(&[1.0, 2.0, 3.0, 4.0], 0.5), 3.0);
        assert_eq!(pctile(&[4.0, 1.0, 3.0, 2.0], 0.5), 3.0);
    }

    /// These spreads were inline S&P constants through 0.23.0, so the Nasdaq loss weighted its
    /// rows with the S&P world's spreads. The one that matters most: the deep rung's spread at
    /// Nasdaq volatility is a fraction of the S&P default's.
    #[test]
    fn the_eight_carried_spreads_are_per_anchor_set_and_the_nasdaq_deep_rung_is_the_sharp_one() {
        for a in [SP500_ANCHORS, NASDAQ_ANCHORS] {
            for (nm, v) in [
                ("val_disp_sd", a.val_disp_sd),
                ("d5_sd", a.d5_sd),
                ("d10_sd", a.d10_sd),
                ("d20_sd", a.d20_sd),
                ("bond_vol_sd", a.bond_vol_sd),
                ("bond_growth_sd", a.bond_growth_sd),
                ("bond_infl_sd", a.bond_infl_sd),
                ("bond_depth_sd", a.bond_depth_sd),
            ] {
                assert!(
                    v > 0.0 && v.is_finite(),
                    "{} {nm} must be a positive spread, read {v}",
                    a.name
                );
            }
        }
        // Read through `anchors_named` so the comparison is a runtime value, not a folded const.
        let (sp, nq) = (anchors_named("sp500"), anchors_named("nasdaq"));
        assert!(
            nq.d20_sd < sp.d20_sd / 4.0,
            "Nasdaq d20 spread {} vs S&P {}",
            nq.d20_sd,
            sp.d20_sd
        );
    }
}

/// The macro panel: nine observables derived from the model's own state after the price loop, so
/// `price` keeps its meaning and the dial is bit-identical off. The bands are MEASURED numbers
/// re-derived from the checked-in fixture. The Scala twin carries the same checks in
/// THE TYPICAL YEAR's ruler (`yearvol-2026-09-15.tsv`): the median calendar-year vol over the
/// pooled vol, which is what separates an ordinary year from an episode. The shipped
/// `typical-year vol %` anchors are this file's medianYearVol rows, and the finding the row
/// exists for — that the Nasdaq anchor's pooled vol is one bust — is pinned here so a
/// re-measured fixture that no longer shows it fails loudly. The Scala twin's
/// `YearVolAnchorSuite` reads the same file.
#[cfg(test)]
mod year_vol_anchor_tests {
    use super::*;

    fn rows() -> Vec<Vec<String>> {
        std::fs::read_to_string("../test-data/equity-anchors/yearvol-2026-09-15.tsv")
            .expect("fixture")
            .lines()
            .filter(|l| !(l.starts_with('#') || l.starts_with("section\t") || l.trim().is_empty()))
            .map(|l| l.split('\t').map(str::to_string).collect())
            .collect()
    }

    fn value(rs: &[Vec<String>], series: &str, window: &str, stat: &str) -> f64 {
        rs.iter()
            .find(|r| r[0] == "yearvol" && r[1] == series && r[2] == window && r[3] == stat)
            .unwrap_or_else(|| panic!("fixture row [yearvol {series} {window} {stat}] missing"))[5]
            .parse()
            .expect("value")
    }

    #[test]
    fn the_shipped_typical_year_anchors_are_the_fixtures_median_year_vol_rows() {
        let rs = rows();
        assert!(
            (anchors_named("sp500").year_vol - value(&rs, "CRSP", "w1954", "medianYearVol")).abs()
                < 0.1
        );
        assert!(
            (anchors_named("nasdaq").year_vol - value(&rs, "QQQ", "w1999", "medianYearVol")).abs()
                < 0.1
        );
    }

    #[test]
    fn the_nasdaq_anchors_pooled_vol_is_one_episode() {
        let rs = rows();
        // every other window and series spreads its vol the same way; only the window holding
        // 2000-02 (and the century holding 1929-33) reads under 0.76
        assert!(value(&rs, "QQQ", "w1999", "ratio") < 0.72);
        for (s, w) in [
            ("QQQ", "w2007"),
            ("SPY", "w1993"),
            ("CRSP", "w1954"),
            ("CRSP", "w1990"),
            ("CRSP", "w1999"),
        ] {
            assert!(
                value(&rs, s, w, "ratio") >= 0.78,
                "{s} {w}: an ordinary window reads 0.78 or more of pooled"
            );
        }
        assert!(value(&rs, "CRSP", "w1926", "ratio") < 0.76);
        // and the typical Nasdaq year is the S&P's typical year plus about a fifth, not plus a half
        let q = value(&rs, "QQQ", "w1999", "medianYearVol")
            / value(&rs, "CRSP", "w1954", "medianYearVol");
        assert!(q > 1.3 && q < 1.55, "typical-year ratio QQQ/CRSP {q}");
    }

    #[test]
    fn the_typical_year_is_the_median_over_whole_blocks_of_each_blocks_own_vol() {
        // three blocks at |r| = 0.01, 0.03, 0.02 and a partial fourth that must be ignored
        let mut r = vec![0.01; 252];
        r.extend(vec![0.03; 252]);
        r.extend(vec![0.02; 252]);
        r.extend(vec![0.09; 100]);
        assert!((year_vol_of(&r) - 0.02 * 252f64.sqrt()).abs() < 1e-12);
        // shorter than a year: the pooled vol
        let short = vec![0.02; 100];
        assert!((year_vol_of(&short) - 0.02 * 252f64.sqrt()).abs() < 1e-12);
    }

    #[test]
    fn both_shipped_worlds_read_a_typical_year_inside_their_own_band() {
        for (name, a) in [
            ("default", anchors_named("sp500")),
            ("0.24.3-nasdaq", anchors_named("nasdaq")),
        ] {
            let w = if name == "default" {
                default_world()
            } else {
                named_world(name).expect("recipe").0
            };
            let st = measure(&sim_paths(&w, 24, 30, 20260915), 30);
            let y = st.year_vol * 100.0;
            assert!(
                y >= a.year_vol_band.0 && y <= a.year_vol_band.1,
                "{name} typical-year vol {y:.1} outside {:?}",
                a.year_vol_band
            );
            assert!(
                y < st.vol * 100.0,
                "{name}: the typical year ({y:.1}) sits under the pooled vol ({:.1})",
                st.vol * 100.0
            );
        }
    }
}

/// THE BUST SWING (`-bustamp`, PLAN item 25): the mania's unwind as the record has it. Off in
/// every frozen world and bit-identical there; on, the swing reaches the price and raises the
/// ensemble's volatility inside the busts, not in the typical year, which is what the mania-led
/// busts it targets carry. The Scala twin's `BustSwingSuite` makes the same checks.
#[cfg(test)]
mod bust_swing_tests {
    use super::*;

    #[test]
    fn the_bust_swing_is_off_in_every_world_before_0_24_3_and_zero_is_bit_identical() {
        assert!(
            default_world().bust_amp == 0.0,
            "the S&P default keeps it off"
        );
        for (v, w) in releases() {
            assert!(w.bust_amp == 0.0, "release {v}");
        }
        for (n, w, _) in recipes() {
            if n != "0.24.3-nasdaq" {
                assert!(w.bust_amp == 0.0, "recipe {n}");
            }
        }
        // the searched 0.24.3 recipe carries the archive's own amplitude
        let (w0243, _) = named_world("0.24.3-nasdaq").expect("recipe");
        assert!(w0243.bust_amp == 0.01448313, "0.24.3-nasdaq");
        // at 0 the block never runs: its own stream is never drawn and no hook moves
        let (w, _) = named_world("0.24.2-nasdaq").expect("recipe");
        let mut z = w;
        z.bust_amp = 0.0;
        let a = simulate(&w, 3, DEFAULT_SEED);
        let b = simulate(&z, 3, DEFAULT_SEED);
        assert!(
            a.price == b.price && a.bond == b.bond,
            "0 must be bit-identical"
        );
    }

    #[test]
    fn on_the_swing_reaches_the_price_and_raises_the_ensembles_vol_inside_the_busts() {
        let (w, _) = named_world("0.24.2-nasdaq").expect("recipe");
        let mut on_w = w;
        on_w.bust_amp = 0.14;
        // a mania has to form first, which takes decades: one 80-year path
        let on = simulate(&on_w, 80, DEFAULT_SEED);
        let off = simulate(&w, 80, DEFAULT_SEED);
        assert!(on.price != off.price, "the swing must reach the price");
        let st_on = measure(&sim_paths(&on_w, 60, 80, DEFAULT_SEED), 80);
        let st_off = measure(&sim_paths(&w, 60, 80, DEFAULT_SEED), 80);
        assert!(
            st_on.vol > st_off.vol,
            "the swing must add volatility: {:.4} -> {:.4}",
            st_off.vol,
            st_on.vol
        );
        // and it adds it inside the busts, not to the ordinary year
        assert!(
            st_on.year_vol - st_off.year_vol < 0.002,
            "the typical year must stay put: {:.4} -> {:.4}",
            st_off.year_vol,
            st_on.year_vol
        );
    }
}

/// THE WINGS' ruler (`mania-2026-09-15.tsv`): the valuation cycle about its own 20-year mean on
/// Shiller's CAPE. The shipped `upper wing months %` / `lower wing months %` anchors are this
/// file's w1901 rows, the record's symmetry is pinned (the model's cycle had the amplitude with
/// the wrong sign, which is the finding the rows exist for), and `wings_of` is checked on series
/// whose wings are known. The Scala twin's `ManiaAnchorSuite` reads the same file.
#[cfg(test)]
mod mania_anchor_tests {
    use super::*;

    fn rows() -> Vec<Vec<String>> {
        std::fs::read_to_string("../test-data/equity-anchors/mania-2026-09-15.tsv")
            .expect("fixture")
            .lines()
            .filter(|l| !(l.starts_with('#') || l.starts_with("section\t") || l.trim().is_empty()))
            .map(|l| l.split('\t').map(str::to_string).collect())
            .collect()
    }

    fn value(rs: &[Vec<String>], section: &str, series: &str, window: &str, stat: &str) -> f64 {
        rs.iter()
            .find(|r| r[0] == section && r[1] == series && r[2] == window && r[3] == stat)
            .unwrap_or_else(|| panic!("fixture row [{section} {series} {window} {stat}] missing"))
            [5]
        .parse()
        .expect("value")
    }

    #[test]
    fn the_shipped_wing_anchors_are_the_fixtures_w1901_shares_and_both_sets_share_them() {
        let rs = rows();
        for a in [anchors_named("sp500"), anchors_named("nasdaq")] {
            assert!(
                (a.wing_up - value(&rs, "wing", "CAPE", "w1901", "shareAbove50") * 100.0).abs()
                    < 0.1
            );
            assert!(
                (a.wing_down - value(&rs, "wing", "CAPE", "w1901", "shareBelow50") * 100.0).abs()
                    < 0.1
            );
        }
    }

    #[test]
    fn the_records_wings_are_symmetric_and_its_manias_are_1929_and_1999() {
        let rs = rows();
        let up = value(&rs, "wing", "CAPE", "w1901", "shareAbove50");
        let dn = value(&rs, "wing", "CAPE", "w1901", "shareBelow50");
        assert!(
            (up - dn).abs() < 0.02,
            "symmetric wings: {up} above, {dn} below"
        );
        assert!(
            value(&rs, "peak", "CAPE", "peak1929", "level") > 0.9
                && value(&rs, "peak", "CAPE", "peak1999", "level") > 0.9
        );
        assert!(
            value(&rs, "peak", "CAPE", "peak1937", "level") > 0.5
                && value(&rs, "peak", "CAPE", "peak2021", "level") > 0.5
        );
        assert!(value(&rs, "wing", "CAPE", "w1901", "excursionsPerCentury") > 2.5);
    }

    #[test]
    fn wings_of_reads_a_levels_time_past_half_a_log_over_its_own_20_year_mean() {
        let burn = (BUST_MEAN_YEARS * DAYS_PER_YEAR as f64) as usize;
        let fund = vec![1.0; burn + 30 * 252];
        // a constant level has no wings; a step of +2 held for 30 years is above +0.5 until the
        // reference has climbed within 0.5 of it, which takes 20 x ln 4 = 27.7 years; the mirror
        // image is the lower wing
        let flat = wings_of(&fund, &fund);
        assert!(flat.0 == 0.0 && flat.1 == 0.0);
        let up_p: Vec<f64> = fund
            .iter()
            .enumerate()
            .map(|(i, f)| if i < burn { *f } else { f * 2.0f64.exp() })
            .collect();
        let up = wings_of(&up_p, &fund);
        assert!(
            up.0 / up.2 > 0.9 && up.1 == 0.0,
            "upper wing {}",
            up.0 / up.2
        );
        let dn_p: Vec<f64> = fund
            .iter()
            .enumerate()
            .map(|(i, f)| if i < burn { *f } else { f * (-2.0f64).exp() })
            .collect();
        let dn = wings_of(&dn_p, &fund);
        assert!(
            dn.1 / dn.2 > 0.9 && dn.0 == 0.0,
            "lower wing {}",
            dn.1 / dn.2
        );
        // shorter than the reference's burn-in: nothing read
        assert!(wings_of(&fund[..burn], &fund[..burn]) == (0.0, 0.0, 0.0));
    }
}

/// THE AMPLIFIER's rulers (`amplifier-2026-09-07.tsv`): the record's |r| autocorrelation profile
/// and its crash-count elasticity against volatility, and what `stress_scale` does to the model's
/// own elasticity. The Scala twin's `AmplifierAnchorSuite` reads the same file.
#[cfg(test)]
mod amplifier_anchor_tests {
    use super::*;

    fn rows() -> Vec<Vec<String>> {
        std::fs::read_to_string("../test-data/equity-anchors/amplifier-2026-09-07.tsv")
            .expect("fixture")
            .lines()
            .filter(|l| !(l.starts_with('#') || l.starts_with("section\t") || l.trim().is_empty()))
            .map(|l| l.split('\t').map(str::to_string).collect())
            .collect()
    }

    fn value(rs: &[Vec<String>], section: &str, series: &str, window: &str, stat: &str) -> f64 {
        rs.iter()
            .find(|r| r[0] == section && r[1] == series && r[2] == window && r[3] == stat)
            .unwrap_or_else(|| panic!("fixture row [{section} {series} {window} {stat}] missing"))
            [5]
        .parse()
        .expect("value")
    }

    #[test]
    fn the_shipped_clustering_anchors_are_the_century_profiles_lag_1_and_lag_20_rows() {
        let rs = rows();
        let a = anchors_named("sp500");
        assert!((a.ac1 - value(&rs, "profile", "CRSP", "w1926", "ac1")).abs() < 0.002);
        assert!((a.ac20 - value(&rs, "profile", "CRSP", "w1926", "ac20")).abs() < 0.002);
        let n = anchors_named("nasdaq");
        assert!((n.ac1 - value(&rs, "profile", "QQQ", "w1999", "ac1")).abs() < 0.002);
        assert!((n.ac20 - value(&rs, "profile", "QQQ", "w1999", "ac20")).abs() < 0.002);
    }

    #[test]
    fn the_records_profile_has_a_hump_at_lags_2_to_5_and_a_tail_at_60_to_120() {
        let rs = rows();
        for (s, w) in [("CRSP", "w1926"), ("QQQ", "w1999")] {
            let ac: Vec<f64> = [1, 2, 5, 10, 20, 60, 120]
                .iter()
                .map(|l| value(&rs, "profile", s, w, &format!("ac{l}")))
                .collect();
            assert!(
                ac[1] > ac[0] && ac[2] > ac[0],
                "{s}: lags 2 and 5 above lag 1, {ac:?}"
            );
            assert!(
                ac[5] > 0.12 && ac[6] > 0.12,
                "{s}: the tail holds past 0.12, {ac:?}"
            );
        }
    }

    #[test]
    fn the_records_vol_response_to_a_fall_persists_past_lag_1_where_the_models_decays() {
        let rs = rows();
        for (sr, w) in [("CRSP", "w1926"), ("QQQ", "w1999")] {
            let lev: Vec<f64> = [1, 2, 3, 5, 10, 20]
                .iter()
                .map(|k| value(&rs, "levprofile", sr, w, &format!("lev{k}")))
                .collect();
            assert!(
                lev.iter().all(|v| *v < 0.0),
                "{sr}: negative at every lag, {lev:?}"
            );
            assert!(
                lev[3] < -0.05,
                "{sr}: still -0.05 or beyond at lag 5, {}",
                lev[3]
            );
            assert!(
                lev[3] / lev[0] > 0.6,
                "{sr}: lag 5 holds most of lag 1's strength, {lev:?}"
            );
        }
        // the model's own, at the shipped defaults. Item 12 disclosed a lag-5 response HALF of
        // lag 1's; the vol response and then the slow repricing channel closed most of that, so
        // the model now clears the same 0.6 the record does — from below, and still short of the
        // record's own 0.85. The statistic needs ensemble: a median over paths, it reads -0.060
        // on eight paths against -0.042 on two hundred, converging by about forty.
        let st = measure(&sim_paths(&default_world(), 40, 100, DEFAULT_SEED), 100);
        assert!(
            st.lev1 < -0.05,
            "the model's lag-1 response is the record's: {}",
            st.lev1
        );
        let ratio = st.lev5 / st.lev1;
        assert!(
            (0.60..0.80).contains(&ratio),
            "lag 5 holds most of lag 1's strength but not the record's 0.85: {ratio}              (lag 5 {}, lag 1 {})",
            st.lev5,
            st.lev1
        );
    }

    #[test]
    fn both_vol_response_dials_are_off_everywhere_and_zero_is_bit_identical() {
        let d = default_world();
        assert!(d.noise_asym == 0.0 && d.lev_persist == 0.0);
        for (v, w) in releases() {
            assert!(w.noise_asym == 0.0 && w.lev_persist == 0.0, "release {v}");
        }
        for (n, w, _) in recipes() {
            assert!(w.noise_asym == 0.0 && w.lev_persist == 0.0, "recipe {n}");
        }
        // the persistent kick at 0 IS the shipped kick: its EWMA reduces to the signal itself
        let a = simulate(&d, 3, DEFAULT_SEED);
        let mut z = d;
        z.lev_persist = 0.0;
        z.noise_asym = 0.0;
        let b = simulate(&z, 3, DEFAULT_SEED);
        assert!(a.price == b.price);
        // and both move the profile the way their comments say
        let mut on_w = d;
        on_w.noise_asym = 0.06;
        let on = measure(&sim_paths(&on_w, 40, 100, DEFAULT_SEED), 100);
        let off = measure(&sim_paths(&d, 40, 100, DEFAULT_SEED), 100);
        assert!(
            on.lev5 < off.lev5,
            "the asymmetric noise vol must deepen the lag-5 response: {} -> {}",
            off.lev5,
            on.lev5
        );
    }

    /// THE VOL RESPONSE and the spiral's scale speed (item 14): off in every frozen world,
    /// unreachable through their own shape dials when off, and 0.24.1's default is 0.24.0's row
    /// plus exactly seven dials.
    /// THE SLOW REPRICING CHANNEL (item 15): off in every frozen world, its shape dials
    /// unreachable when it is, the bond loading reaching the BOND and not the price, and the
    /// mechanism moving the |r| autocorrelation SHAPE it was adopted for.
    #[test]
    fn the_slow_channel_is_off_in_every_frozen_world_and_zero_is_bit_identical() {
        let d = default_world();
        assert!(d.slow_share > 0.0, "the shipped default runs the channel");
        for (v, w) in releases().into_iter().filter(|(v, _)| *v < "0.24.1") {
            assert!(w.slow_share == 0.0, "release {v}");
        }
        for (n, w, _) in recipes() {
            // the 0.24.1 Nasdaq recipes carry their own dials and were NOT re-solved against the
            // channel; the 0.24.2 Nasdaq recipe was
            if !n.starts_with("0.24.")
                || n.starts_with("0.24.0")
                || n == "0.24.1-nasdaq"
                || n == "0.24.1-nasdaq-basket"
            {
                assert!(w.slow_share == 0.0, "recipe {n}");
            }
        }
        // off, the channel's own dials reach no price and no bond
        let mut off = d;
        off.slow_share = 0.0;
        let a = simulate(&off, 3, DEFAULT_SEED);
        let mut z = off;
        z.slow_vol = 3.0;
        z.slow_lev = 0.2;
        z.slow_phi = 0.5;
        z.slow_perm = 1.0;
        z.slow_beta = 2.0;
        let b = simulate(&z, 3, DEFAULT_SEED);
        assert!(
            a.price == b.price && a.bond == b.bond,
            "off, the channel's dials are unreachable"
        );
        // the bond loading reaches the BOND and nothing else
        let mut nb = d;
        nb.slow_beta = 0.0;
        let on = simulate(&d, 3, DEFAULT_SEED);
        let no_bond = simulate(&nb, 3, DEFAULT_SEED);
        assert!(
            on.price == no_bond.price,
            "the loading must reach no equity price"
        );
        assert!(on.bond != no_bond.bond, "the loading must reach the bond");
        // and the channel FLATTENS the clustering profile, which is what it was adopted for
        let with = measure(&sim_paths(&d, 40, 100, DEFAULT_SEED), 100);
        let without = measure(&sim_paths(&off, 40, 100, DEFAULT_SEED), 100);
        assert!(
            with.ac20 / with.ac1 > without.ac20 / without.ac1,
            "the slow channel must flatten the |r| profile: {} -> {}",
            without.ac20 / without.ac1,
            with.ac20 / with.ac1
        );
    }

    #[test]
    fn the_vol_response_is_off_in_every_frozen_world_and_zero_is_bit_identical() {
        let d = default_world();
        // the Scala twin spells this default as the LITERAL 0.96, because `Defaults` is built
        // there before the val initializes; a divergence would ship in the sidecar's `world` block
        assert!(d.noise_asym_phi == NOISE_ASYM_PHI && d.noise_asym_phi == 0.96);
        assert!(
            d.vol_resp > 0.0,
            "the shipped default runs the vol response"
        );
        for (v, w) in releases().into_iter().filter(|(v, _)| *v < "0.24.1") {
            assert!(w.vol_resp == 0.0 && w.jump_resp == 0.0, "release {v}");
            assert!(
                w.vol_resp_attack == 0.0 && w.noise_asym_cap == 0.0,
                "release {v}"
            );
            assert!(w.stress_adapt == 0.005, "release {v}");
        }
        for (n, w, _) in recipes() {
            if !(n.starts_with("0.24.1") || n.starts_with("0.24.2") || n.starts_with("0.24.3")) {
                assert!(w.vol_resp == 0.0 && w.jump_resp == 0.0, "recipe {n}");
                assert!(w.stress_adapt == 0.005, "recipe {n}");
            }
        }
        // the frozen row is today's default less the two mechanisms' four dials and the three
        // re-solved around them, and nothing else moved with them
        let frozen = release_world("0.24.0").expect("0.24.0 must resolve");
        let mut off = d;
        off.vol_resp = frozen.vol_resp;
        off.vol_resp_phi = frozen.vol_resp_phi;
        off.vol_resp_attack = frozen.vol_resp_attack;
        off.stress_adapt = frozen.stress_adapt;
        off.stress = frozen.stress;
        off.vol_persist = frozen.vol_persist;
        off.jump_var = frozen.jump_var;
        off.slow_share = frozen.slow_share;
        off.vol_of_vol = frozen.vol_of_vol;
        off.jump_skew = frozen.jump_skew;
        assert!(
            off == frozen,
            "0.24.1 moved the two mechanisms' dials and the six re-solved around them,              nothing else"
        );
        // off, the shape dials of both mechanisms reach no price
        let a = simulate(&off, 3, DEFAULT_SEED);
        let mut z = off;
        z.vol_resp_phi = 0.5;
        z.vol_resp_attack = 0.9;
        z.vol_resp_cap = 1.0;
        z.noise_asym_phi = 0.5;
        z.noise_asym_cap = 0.1;
        let b = simulate(&z, 3, DEFAULT_SEED);
        assert!(a.price == b.price, "off, the shape dials are unreachable");
        // and the response deepens the LAG-20 profile, which is what 0.24.1 adopted it for
        let mut without = d;
        without.vol_resp = 0.0;
        let on = measure(&sim_paths(&d, 20, 100, DEFAULT_SEED), 100);
        let no = measure(&sim_paths(&without, 20, 100, DEFAULT_SEED), 100);
        assert!(
            on.lev20 < no.lev20,
            "the vol response must deepen the lag-20 response: {} -> {}",
            no.lev20,
            on.lev20
        );
    }

    #[test]
    fn crash_count_is_volatility_flat_across_the_fresh_start_cross_section() {
        let rs = rows();
        assert!(value(&rs, "elasticity", "cross-section", "w2007", "logSlope") < 0.2);
        assert!(value(&rs, "elasticity", "cross-section", "w2007", "logCorr").abs() < 0.2);
        assert!(value(&rs, "elasticity", "SPY-QQQ", "own", "logSlope") < 0.5);
    }

    #[test]
    fn stress_scale_shrinks_a_thinner_worlds_spiral_and_zero_is_bit_identical() {
        // the Nasdaq recipe's depth at the reference world's other dials: the gain scale at 0.5
        // multiplies the spiral's excess gain by sqrt(10 / 17.4) = 0.76 (200x100: crashes
        // 35 -> 31, kurtosis 24 -> 12)
        let d = default_world();
        let mut thin = d;
        thin.depth = 10.0;
        let s0 = measure(&sim_paths(&thin, 8, 40, DEFAULT_SEED), 40);
        let mut thin5 = thin;
        thin5.stress_scale = 0.5;
        let s5 = measure(&sim_paths(&thin5, 8, 40, DEFAULT_SEED), 40);
        assert!(s5.kurt < s0.kurt, "kurtosis {} -> {}", s0.kurt, s5.kurt);
        assert!(
            s5.ep_per_path <= s0.ep_per_path,
            "crashes per path {} -> {}",
            s0.ep_per_path,
            s5.ep_per_path
        );
        let a = simulate(&d, 3, DEFAULT_SEED);
        let mut w = d;
        w.stress_scale = 1.0;
        let b = simulate(&w, 3, DEFAULT_SEED);
        assert!(
            a.price == b.price,
            "depth 17.4 is the reference: the gain scale is 1 there"
        );
        for (v, w) in releases() {
            assert!(w.stress_scale == 0.0, "release {v}");
        }
        for (n, w, _) in recipes() {
            if !n.contains("nasdaq") || !n.starts_with("0.24.") {
                assert!(w.stress_scale == 0.0, "recipe {n}");
            }
        }
        assert!(default_world().stress_scale == 0.0);
    }
}

/// `MacroPanelSuite`, against the same file.
#[cfg(test)]
mod macro_panel_tests {
    use super::*;

    #[test]
    fn off_is_bit_identical_and_carries_no_columns_and_every_frozen_world_is_off() {
        let off = simulate(&default_world(), 3, DEFAULT_SEED);
        let mut w = default_world();
        w.macro_panel = 1;
        let on = simulate(&w, 3, DEFAULT_SEED);
        assert!(off.macro_panel.is_none());
        assert!(
            on.price == off.price
                && on.fundamental == off.fundamental
                && on.bond == off.bond
                && on.rate == off.rate,
            "the panel must reach no price"
        );
        for (v, w) in releases() {
            assert!(w.macro_panel == 0, "release {v}");
        }
        for (n, w, _) in recipes() {
            if !n.starts_with("0.24.") {
                assert!(w.macro_panel == 0, "recipe {n}");
            }
        }
        assert!(default_world().macro_panel == 0);
    }

    #[test]
    fn the_leverage_cycle_is_off_in_every_frozen_world_and_zero_reproduces_0_23_1_bit_for_bit() {
        // 0.24.0 is the release that ADOPTED the cycle, so its frozen row carries it
        for (v, w) in releases() {
            if v < "0.24.0" {
                assert!(w.lev_gain == 0.0, "release {v}");
            }
        }
        for (n, w, _) in recipes() {
            if !n.starts_with("0.24.") {
                assert!(w.lev_gain == 0.0, "recipe {n}");
            }
        }
        assert!(
            default_world().lev_gain > 0.0,
            "the shipped default runs the leverage cycle"
        );
        // the dial off at 0.23.1's dials IS 0.23.1's world: the stock still runs (its stream is
        // its own) and the multiplier stays exactly 1.0
        let frozen = release_world("0.23.1").expect("0.23.1 must resolve");
        // against 0.24.0's world, not today's: 0.24.1 moved the vol response's dials on top
        let mut off = release_world("0.24.0").expect("0.24.0 must resolve");
        off.lev_gain = 0.0;
        off.stress = frozen.stress;
        off.jump_var = frozen.jump_var;
        off.jump_skew = frozen.jump_skew;
        off.leverage = frozen.leverage;
        off.vol_persist = frozen.vol_persist;
        off.fund_vol = frozen.fund_vol;
        assert!(
            off == frozen,
            "0.24.0 moved lev_gain and the six dials re-solved around it, nothing else"
        );
        let a = simulate(&frozen, 3, DEFAULT_SEED);
        off.macro_panel = 1;
        let b = simulate(&off, 3, DEFAULT_SEED);
        assert!(
            a.price == b.price && a.bond == b.bond,
            "lev_gain 0 must leave the price bit-identical"
        );
    }

    #[test]
    fn on_the_nine_members_span_the_path_in_their_counterparts_units_and_the_slope_consumes_no_draw()
     {
        let mut w = default_world();
        w.macro_panel = 1;
        // a century: an inversion needs an inflation regime tight enough to invert, which a
        // short path can miss (the ensemble inverts 0.13 of sessions, in spells of ~480)
        let p = simulate(&w, 100, DEFAULT_SEED);
        let m = p.macro_panel.as_ref().expect("no panel with the dial on");
        for j in 0..9 {
            assert_eq!(m.member(j).len(), p.price.len(), "{}", macro_k::COLUMNS[j]);
        }
        assert!(
            m.spread.iter().all(|&x| x >= macro_k::SPREAD_FLOOR),
            "a credit spread is floored, never negative"
        );
        assert!(
            m.ivol.iter().all(|&x| x >= macro_k::IVOL_FLOOR),
            "an implied vol is floored, never negative"
        );
        assert!(
            m.slope.iter().any(|&x| x < 0.0) && m.slope.iter().any(|&x| x > 0.0),
            "the curve both inverts and steepens"
        );
        // The slope is an expectation of the rate process, so it is the same function of the
        // same states whatever the measurement stream drew: the other members move with the
        // seed's panel stream, the slope only with the price loop's.
        let q = simulate(&w, 100, DEFAULT_SEED + 1);
        let qm = q.macro_panel.as_ref().expect("panel");
        assert!(qm.spread != m.spread, "the spread reads its own stream");
        assert!(
            qm.slope
                .iter()
                .zip(&q.rate)
                .all(|(s, r)| s.is_finite() && r.is_finite())
        );
        // the measured values hold their counterparts' scale: percentage points, a raw index, %
        let mid = pctile(&m.spread, 0.5);
        assert!(mid > 0.5 && mid < 6.0);
        let iv = pctile(&m.ivol, 0.5);
        assert!(iv > 5.0 && iv < 60.0);
        // THE POLICY RATE is a published target, not the loop's rate: never negative, an exact
        // multiple of the step (0.25 is binary-exact, so `%` is exact too), and it moves only at
        // a meeting — the burn-in is dropped whole, so the phase survives it
        assert!(
            m.policy
                .iter()
                .all(|&x| x >= 0.0 && x % macro_k::POLICY_STEP == 0.0),
            "the published rate is a non-negative multiple of the step"
        );
        let moved: Vec<usize> = (1..m.policy.len())
            .filter(|&i| m.policy[i] != m.policy[i - 1])
            .collect();
        assert!(
            moved
                .iter()
                .all(|i| (i + BURN_IN).is_multiple_of(macro_k::POLICY_MEETING)),
            "the rate changes only at a meeting"
        );
        assert!(
            !moved.is_empty(),
            "a century of policy is not one held rate"
        );
    }

    #[test]
    fn the_published_rate_sits_on_the_records_scale_and_reads_the_loops_own_rate() {
        let Some(rs) = rows() else { return };
        let mut w = default_world();
        w.macro_panel = 1;
        let p = simulate(&w, 100, DEFAULT_SEED);
        let m = p.macro_panel.as_ref().expect("no panel with the dial on");
        // the record's DFF, 1990-2026 on weekdays: a model century's median overnight rate has to
        // land inside it, or the panel's rate map is off its counterpart's scale
        let lo = value(&rs, "shared", "DFF", "policy", "lvl10");
        let hi = value(&rs, "shared", "DFF", "policy", "lvl90");
        let med = pctile(&m.policy, 0.5);
        assert!(
            med > lo && med < hi,
            "published rate median {med} outside the record's [{lo}, {hi}]"
        );
        // it is the LOOP's rate, published: never more than half a step from it at a meeting
        for i in
            (0..m.policy.len()).filter(|i| (i + BURN_IN).is_multiple_of(macro_k::POLICY_MEETING))
        {
            assert!(
                (m.policy[i] - 100.0 * p.rate[i]).abs() <= macro_k::POLICY_STEP / 2.0,
                "session {i}: published {} against the loop's {}",
                m.policy[i],
                100.0 * p.rate[i]
            );
        }
    }

    #[test]
    fn the_credit_ratio_is_a_slow_stock_of_its_own_and_the_two_levels_reproduce_it() {
        let Some(rs) = rows() else { return };
        let mut w = default_world();
        w.macro_panel = 1;
        let p = simulate(&w, 100, DEFAULT_SEED);
        let m = p.macro_panel.as_ref().expect("no panel with the dial on");
        // THE SPLIT: a consumer that divides the two levels gets the ratio column back. The levels
        // are indices, so only their ratio is meaningful — and it is, to the columns' own rounding.
        let worst = (0..m.credit.len())
            .map(|i| (100.0 * m.bank[i] / m.output[i] - m.credit[i]).abs())
            .fold(0.0f64, f64::max);
        assert!(
            worst < 1e-6,
            "credit / output does not reproduce the ratio: {worst}"
        );
        assert!(
            m.output.iter().all(|&x| x > 0.0) && m.bank.iter().all(|&x| x > 0.0),
            "levels are positive"
        );
        assert!(
            m.output[0] == macro_k::OUT_BASE,
            "the index base is the first emitted session: {}",
            m.output[0]
        );
        assert!(
            m.output[m.output.len() - 1] > m.output[0],
            "output grows over a century"
        );
        // A STOCK OF ITS OWN, not the leverage cycle: that oscillator turned every four years and
        // its level read -0.40 at two years. Against the record DETRENDED (the window's secular
        // rise is no stationary stock's), the ratio's own persistence is what the fixture carries.
        let wk: Vec<f64> = m.credit.iter().step_by(5).copied().collect();
        let ac104 = level_autocorr(&wk, 104);
        assert!(
            ac104 > 0.1,
            "a four-year oscillator reads below zero at two years: {ac104}"
        );
        let ac52 = level_autocorr(&wk, 52);
        let rec52 = value(&rs, "shared", "TOTBKCR/GDP", "credit", "ac52d");
        assert!(
            (ac52 - rec52).abs() < 0.25,
            "ratio ac52 {ac52} against the record's detrended {rec52}"
        );
        // the level sits on the record's scale, and the ratio is bounded by construction
        let med = pctile(&m.credit, 0.5);
        assert!(med > 40.0 && med < 75.0, "ratio median {med}");
        assert!(
            m.credit
                .iter()
                .all(|&x| x > 0.0 && x < macro_k::CREDIT_MAX * 1.5),
            "the ratio stays bounded"
        );
    }

    #[test]
    fn the_readings_exist_only_when_the_panel_ran_and_read_as_the_rulers_statistics() {
        let off = measure(&sim_paths(&default_world(), 4, 30, DEFAULT_SEED), 30);
        assert!(off.macro_panel.is_none());
        let mut w = default_world();
        w.macro_panel = 1;
        let on = measure(&sim_paths(&w, 4, 30, DEFAULT_SEED), 30);
        let ms = on.macro_panel.expect("no readings with the dial on");
        assert!(ms.episodes > 0);
        for (j, m) in ms.members.iter().enumerate() {
            let nm = macro_k::COLUMNS[j];
            assert!((0.0..=1.0).contains(&m.warn), "{nm} warn {}", m.warn);
            assert!(
                (0.0..=1.0).contains(&m.warn_fired),
                "{nm} fired {}",
                m.warn_fired
            );
            assert!(
                m.ac1 > 0.9 && m.ac1 <= 1.0,
                "{nm} ac1 {}: a level series, not a return series",
                m.ac1
            );
            assert!((0.0..=1.0).contains(&m.r2fwd60), "{nm} r2 {}", m.r2fwd60);
            assert!(m.lvl10 <= m.lvl50 && m.lvl50 <= m.lvl90, "{nm} levels");
        }
        assert!((0.0..=1.0).contains(&ms.inv_share));
        assert!(ms.vrp.is_finite() && (0.0..=1.0).contains(&ms.r2rv));
    }

    const FIXTURE: &str = "../test-data/equity-anchors/macro-2026-09-06.tsv";

    fn rows() -> Option<Vec<Vec<String>>> {
        let text = std::fs::read_to_string(FIXTURE).ok()?;
        Some(
            text.lines()
                .filter(|l| !l.starts_with('#') && !l.starts_with("set\t") && !l.trim().is_empty())
                .map(|l| l.split('\t').map(str::to_string).collect())
                .collect(),
        )
    }

    fn value(rs: &[Vec<String>], set: &str, series: &str, member: &str, stat: &str) -> f64 {
        rs.iter()
            .find(|r| r[0] == set && r[1] == series && r[2] == member && r[3] == stat)
            .unwrap_or_else(|| panic!("fixture row [{set} {series} {member} {stat}] missing"))[6]
            .parse()
            .expect("numeric fixture value")
    }

    #[test]
    fn the_policy_rates_upper_tail_is_the_records_an_inflation_regimes_target_is_capped() {
        let Some(rs) = rows() else { return };
        let mut w = default_world();
        w.macro_panel = 1;
        // eight centuries: the tail is a 0.1% event on the record, and a century holds one or two
        // inflation regimes that reach the cap
        let ps = sim_paths(&w, 8, 100, DEFAULT_SEED);
        let policy = |p: &Path| -> Vec<f64> {
            p.macro_panel
                .as_ref()
                .expect("no panel with the dial on")
                .policy
                .clone()
        };
        let pol: Vec<f64> = ps.iter().flat_map(policy).collect();
        let gt20 = pol.iter().filter(|&&x| x > 20.0).count() as f64 / pol.len() as f64;
        let rec_gt20 = value(&rs, "shared", "DFF", "policy", "shareGt20");
        let rec_max = value(&rs, "shared", "DFF", "policy", "max");
        // the record's share is one 1980-81 spike in seventy-two years; uncapped, the model put
        // fifteen times as much there
        assert!(
            gt20 <= 3.0 * rec_gt20,
            "share above 20%: {gt20:.4} against the record's {rec_gt20:.4}"
        );
        // the ceiling is rate_mean + INFL_CAP and the rate's own noise carries the spike, never
        // past the record's maximum by more than that noise reaches
        let mx = pol.iter().copied().fold(f64::MIN, f64::max);
        assert!(
            mx <= rec_max + 3.0,
            "policy rate reached {mx:.2} against the record's {rec_max:.2}"
        );
        // no path holds 20% for a year: the record's longest run above it is four sessions
        let longest = ps
            .iter()
            .map(|p| {
                policy(p)
                    .iter()
                    .fold((0usize, 0usize), |(best, cur), &x| {
                        let c = if x > 20.0 { cur + 1 } else { 0 };
                        (best.max(c), c)
                    })
                    .0
            })
            .max()
            .unwrap_or(0);
        assert!(
            longest <= 252,
            "a path held the rate above 20% for {longest} sessions"
        );
    }

    fn at2(x: f64) -> f64 {
        (x * 100.0).round() / 100.0
    }

    #[test]
    fn the_bands_are_the_fixtures_warning_shares_per_set_from_its_two_references_the_shape_rows_shared()
     {
        let Some(rs) = rows() else {
            return;
        };
        // the lag bands are US-wide: +-40 sessions around the upper-middle (the twins' median)
        // of the four references' median firing lags — sessions from the peak to the first
        // firing, over the 20% episodes the member fired in, rank members in the quarter before
        // the peak
        let refs = [
            ("sp500", "CRSP"),
            ("sp500", "SPY"),
            ("nasdaq", "NDX"),
            ("nasdaq", "QQQ"),
        ];
        let lag_band = |fred: &str, member: &str| -> (f64, f64) {
            let v: Vec<f64> = refs
                .iter()
                .map(|(s, r)| value(&rs, s, &format!("{fred}/{r}"), member, "lag20_63"))
                .collect();
            let m = pctile(&v, 0.5);
            (m - 40.0, m + 40.0)
        };
        assert_eq!(macro_bands::SPREAD_LAG, lag_band("BAA10Y", "spread"));
        assert_eq!(macro_bands::COND_LAG, lag_band("NFCILEVERAGE", "cond"));
        // the build-up: the conditions index's mean trailing rank over the quarter before the
        // peak, 0.12 under the four references' upper-middle, to 1 — the coupling test, which a
        // decoupled series (the null panel, 0.49) fails by construction
        let pres: Vec<f64> = refs
            .iter()
            .map(|(s, r)| value(&rs, s, &format!("NFCILEVERAGE/{r}"), "cond", "prepeak20_63"))
            .collect();
        assert_eq!(
            macro_bands::COND_PRE_PEAK,
            (at2(pctile(&pres, 0.5) - 0.12), 1.0)
        );
        assert!(
            pres.iter().all(|&p| p > 0.8),
            "the record's leverage index builds before every reference's peaks"
        );
        // the hazard: the references' minimum quarter-horizon 20% hazard, floored to 0.1
        let hz: Vec<f64> = refs
            .iter()
            .map(|(s, r)| value(&rs, s, &format!("NFCILEVERAGE/{r}"), "cond", "hazard20_63"))
            .collect();
        let hz_min = hz.iter().copied().fold(f64::INFINITY, f64::min);
        assert_eq!(macro_bands::HAZARD_MIN, (hz_min * 10.0).floor() / 10.0);
        assert!(
            hz.iter().all(|&h| h > 1.4)
                && hz.iter().copied().fold(f64::NEG_INFINITY, f64::max) > 2.5,
            "leverage concentrates the big peaks on every reference"
        );
        // the record's conditions index leads the peak on three of the four references
        let leads = refs
            .iter()
            .filter(|(s, r)| value(&rs, s, &format!("NFCILEVERAGE/{r}"), "cond", "lag20_63") < 0.0)
            .count();
        assert!(leads >= 3, "{leads} of four references lead");
        // the shape rows: persistence +-0.08 around the record's, capped at 1
        let ac_band = |fred: &str, member: &str, stat: &str| -> (f64, f64) {
            let x = value(&rs, "shared", fred, member, stat);
            (at2(x - 0.08), at2((x + 0.08).min(1.0)))
        };
        assert_eq!(
            macro_bands::SPREAD_AC_K,
            ac_band("BAA10Y", "spread", "ac20")
        );
        assert_eq!(
            macro_bands::COND_AC_K,
            ac_band("NFCILEVERAGE", "cond", "ac4")
        );
        assert_eq!(macro_bands::IVOL_AC_K, ac_band("VIXCLS", "ivol", "ac20"));
        // the oracle bound sits above every predictive R^2 the record shows, and not far above
        // over the four measured members; the two draw-free levels are reported, not bounded
        let measured = ["spread", "slope", "cond", "ivol"];
        let r2_max = rs
            .iter()
            .filter(|r| r[3] == "r2fwd60" && measured.contains(&r[2].as_str()))
            .map(|r| r[6].parse::<f64>().expect("numeric"))
            .fold(f64::NEG_INFINITY, f64::max);
        assert!(
            r2_max < macro_bands::ORACLE_R2 && macro_bands::ORACLE_R2 <= r2_max + 0.02,
            "oracle bound {} against the record's largest {r2_max:.4}",
            macro_bands::ORACLE_R2
        );
        // the slope's inversion share and the variance risk premium bands hold the record
        let inv = value(&rs, "shared", "T10Y2Y", "slope", "invShare");
        assert!(inv > macro_bands::INV_SHARE.0 && inv < macro_bands::INV_SHARE.1);
        for r in ["CRSP", "SPY"] {
            let vrp = value(&rs, "shared", &format!("VIXCLS/{r}"), "ivol", "vrp");
            assert!(
                vrp > macro_bands::VRP.0 && vrp < macro_bands::VRP.1,
                "{r} vrp {vrp}"
            );
        }
    }

    #[test]
    fn the_null_panel_is_a_sibling_paths_the_same_price_a_decoupled_panel_and_no_macro_rows() {
        let mut w = default_world();
        w.macro_panel = 1;
        let own = simulate(&w, 20, DEFAULT_SEED);
        let mut n = w;
        n.macro_null = 1;
        let nul = simulate(&n, 20, DEFAULT_SEED);
        assert!(
            nul.price == own.price && nul.rate == own.rate,
            "the null reaches no price"
        );
        let op = own.macro_panel.as_ref().expect("panel");
        let np = nul.macro_panel.as_ref().expect("panel");
        assert!(np.spread != op.spread, "a sibling's panel, not this path's");
        assert!(np.sibling && !op.sibling);
        // the sibling IS another path of this world, at seed ^ NULL_SEED: its own panel, verbatim
        let sib = simulate(&w, 20, DEFAULT_SEED ^ macro_k::NULL_SEED);
        let sp = sib.macro_panel.as_ref().expect("panel");
        assert!(np.spread == sp.spread && np.ivol == sp.ivol);
        // 20 x 100, not 4 x 30: the pre-peak rank is a median over 20% EPISODES, and a handful of
        // paths yields a handful of episodes — at 4 x 30 the null reads 0.87 where it converges to
        // 0.55 by twelve paths. The same ensemble lesson the vol-response statistics carry.
        let st = measure(&sim_paths(&n, 20, 100, DEFAULT_SEED), 100);
        assert!(st.macro_panel.is_some_and(|m| m.sibling));
        // the panel's rows all start "macro <member>"; "macro disasters ..." is the disaster
        // channel's
        let panel_row = |n: &str| n.starts_with("macro ") && !n.starts_with("macro disasters");
        assert!(
            !gate_checks(anchors_named("sp500"), &st)
                .iter()
                .any(|r| panel_row(&r.0)),
            "a null panel grades nothing"
        );
        // a decoupled conditions index reads its unconditional level before a peak
        let null_pre = st.macro_panel.expect("readings").members[2].pre_peak;
        assert!(null_pre < 0.65, "null pre-peak rank {null_pre}");
        let own = measure(&sim_paths(&w, 4, 30, DEFAULT_SEED), 30);
        assert_eq!(
            gate_checks(anchors_named("sp500"), &own)
                .iter()
                .filter(|r| panel_row(&r.0))
                .count(),
            11,
            "the path's own panel carries its eleven rows"
        );
        for (v, w0) in releases() {
            assert!(w0.macro_null == 0, "release {v}");
        }
        for (nm, w0, _) in recipes() {
            assert!(w0.macro_null == 0, "recipe {nm}");
        }
    }

    #[test]
    fn the_episode_spans_the_warning_share_reads_are_dd_episodes_own() {
        let p = simulate(&default_world(), 20, DEFAULT_SEED);
        let ep = dd_episodes(&p.price, 0.20);
        let sp = dd_spans(&p.price, 0.20);
        assert_eq!(ep.len(), sp.len());
        for (e, s) in ep.iter().zip(&sp) {
            assert!(e.depth == s.depth);
            assert_eq!(e.decline, s.trough - s.lo + 1);
            assert_eq!(e.underwater, s.hi - s.lo + 1);
        }
    }
}

/// The dividend stream: a derived channel that reaches no price, so `price` keeps its meaning and
/// the dial is bit-identical off. The anchors are MEASURED numbers re-derived from the checked-in
/// fixture. The Scala twin carries the same checks in `DividendSuite`, against the same file.
#[cfg(test)]
mod dividend_tests {
    use super::*;

    const FIXTURE: &str = "../test-data/equity-anchors/dividend-2026-09-02.tsv";

    fn rows() -> Option<Vec<Vec<String>>> {
        let text = std::fs::read_to_string(FIXTURE).ok()?;
        Some(
            text.lines()
                .filter(|l| !l.starts_with('#') && !l.starts_with("set\t") && !l.trim().is_empty())
                .map(|l| l.split('\t').map(str::to_string).collect())
                .collect(),
        )
    }

    fn value(rs: &[Vec<String>], set: &str, series: &str, window: &str, stat: &str) -> f64 {
        rs.iter()
            .find(|r| r[0] == set && r[1] == series && r[2] == window && r[3] == stat)
            .unwrap_or_else(|| panic!("fixture row [{set} {series} {window} {stat}] missing"))[4]
            .parse()
            .expect("numeric fixture value")
    }

    fn outward(x: f64, up: bool) -> f64 {
        let n = if up {
            (x / 0.1 - 1e-9).ceil()
        } else {
            (x / 0.1 + 1e-9).floor()
        };
        (n * 0.1 * 1e6).round() / 1e6
    }

    #[test]
    fn off_is_bit_identical_and_carries_no_columns_and_every_frozen_world_is_off() {
        let off = simulate(&default_world(), 3, DEFAULT_SEED);
        let mut w = default_world();
        w.div_yield = 2.95;
        let on = simulate(&w, 3, DEFAULT_SEED);
        assert!(off.div_yield.is_empty() && off.traded.is_empty());
        assert!(
            on.price == off.price && on.fundamental == off.fundamental,
            "the dividend stream must reach no price"
        );
        for (v, w) in releases() {
            assert!(w.div_yield == 0.0, "release {v}");
        }
        // the 0.23.1 recipes carry the stream at their set's anchor, and the 0.24.0 ones are
        // those bases
        for (n, w, _) in recipes() {
            if !n.starts_with("0.23.1") && !n.starts_with("0.24.") {
                assert!(w.div_yield == 0.0, "recipe {n}");
            }
        }
        assert!(default_world().div_yield == 0.0);
    }

    /// The level is solved on the fixed 8 x 100 ensemble at LEVEL_SEED, in path then session
    /// order, which `sim_paths` at that seed reproduces path for path. A per-path mean would leak
    /// the path's future into every session's yield; this constant leaks nothing.
    #[test]
    fn the_worlds_mean_fundamental_over_price_is_a_constant_of_the_level_ensemble_and_the_dial_is_the_mean_yield()
     {
        let mut w = default_world();
        w.div_yield = 2.95;
        let lvl = world_level(&w);
        let ens = sim_paths(&w, LEVEL_PATHS, LEVEL_YEARS, LEVEL_SEED);
        let (s_fp, n_fp) = ens
            .iter()
            .map(fair_over_price_sum)
            .fold((0.0, 0.0), |(a, b), (c, d)| (a + c, b + d));
        assert!(
            (lvl.k_div - s_fp / n_fp).abs() <= 1e-9 * lvl.k_div,
            "k_div must be the pooled mean fundamental/price"
        );
        assert!(
            lvl.k_div > 1.0,
            "the ensemble's mean gap sits below fair, so k_div > 1: read {}",
            lvl.k_div
        );
        assert!(
            world_level(&default_world()).k_div == 0.0,
            "off, no level is solved"
        );
        let st = measure(&sim_paths(&w, 16, 100, DEFAULT_SEED), 100);
        assert!(
            (st.div_yield_mean - 2.95).abs() < 0.30,
            "mean yield {:.2} against the dial 2.95",
            st.div_yield_mean
        );
    }

    #[test]
    fn the_session_yield_is_the_dial_times_fundamental_over_price_over_its_mean_and_the_traded_price_is_the_deflated_total_return()
     {
        let mut w = default_world();
        w.div_yield = 2.95;
        let k_div = world_level(&w).k_div;
        let p = simulate(&w, 5, DEFAULT_SEED);
        assert_eq!(p.div_yield.len(), p.price.len());
        assert_eq!(p.traded.len(), p.price.len());
        assert_eq!(p.chan_k_div, k_div);
        for i in 0..p.price.len() {
            assert!(
                (p.div_yield[i] - 2.95 * (p.fundamental[i] / p.price[i]) / k_div).abs() < 1e-12,
                "session {i}"
            );
        }
        assert_eq!(p.traded[0], p.price[0]);
        let mut t = p.price[0];
        for i in 1..p.price.len() {
            t *= p.price[i] / p.price[i - 1] - p.div_yield[i] / 100.0 / DAYS_PER_YEAR as f64;
            assert!((p.traded[i] - t).abs() <= 1e-9 * t, "session {i}");
        }
        let yrs = p.price.len() as f64 / DAYS_PER_YEAR as f64;
        let gap = (p.price[p.price.len() - 1] / p.price[0]).ln()
            - (p.traded[p.traded.len() - 1] / p.traded[0]).ln();
        let mean_y = p.div_yield.iter().sum::<f64>() / p.div_yield.len() as f64 / 100.0;
        assert!(
            (gap - mean_y * yrs).abs() <= 0.02 * mean_y * yrs + 1e-6,
            "accrued yield {gap:.4} vs mean yield x years {:.4}",
            mean_y * yrs
        );
    }

    #[test]
    fn the_level_and_its_band_are_the_fixtures_per_anchor_set() {
        let Some(rs) = rows() else {
            return;
        };
        let (sp, nq) = (anchors_named("sp500"), anchors_named("nasdaq"));
        assert!(
            (sp.div_yield - value(&rs, "sp500", "Shiller-S&P", "1954-2023", "monthlyMean")).abs()
                < 1e-9
        );
        assert!(
            (sp.div_yield_band.0
                - outward(
                    value(&rs, "sp500", "Shiller-S&P", "1954-2023", "annualMin"),
                    false
                ))
            .abs()
                < 1e-9
        );
        assert!(
            (sp.div_yield_band.1
                - outward(
                    value(&rs, "sp500", "Shiller-S&P", "1954-2023", "annualMax"),
                    true
                ))
            .abs()
                < 1e-9
        );
        assert!(
            (nq.div_yield - value(&rs, "nasdaq", "QQQ", "2005-2026", "annualMean")).abs() < 1e-9
        );
        assert!(
            (nq.div_yield_band.0
                - outward(value(&rs, "nasdaq", "QQQ", "2005-2026", "annualMin"), false))
            .abs()
                < 1e-9
        );
        assert!(
            (nq.div_yield_band.1
                - outward(value(&rs, "nasdaq", "QQQ", "2005-2026", "annualMax"), true))
            .abs()
                < 1e-9
        );
        for a in [sp, nq] {
            assert!(
                a.div_yield > a.div_yield_band.0 && a.div_yield < a.div_yield_band.1,
                "{}: the anchored yield must sit inside its own band",
                a.name
            );
        }
    }

    /// At the verdict horizon: the yield is normalized by the century's mean fundamental/price,
    /// and a 20-year path's own mean sits well below it, so a short ensemble reads the dial low.
    #[test]
    fn the_gate_grades_the_level_only_when_the_dial_is_on_and_the_dial_is_an_identity_parameter() {
        let a = anchors_named("sp500");
        let off = measure(&sim_paths(&default_world(), 4, 100, DEFAULT_SEED), 100);
        assert!(off.div_yield_mean.is_nan());
        assert!(
            !gate_checks(a, &off)
                .iter()
                .any(|r| r.0.starts_with("dividend yield")),
            "a dividends-off world must carry no dividend row"
        );
        let mut w = default_world();
        w.div_yield = a.div_yield;
        let on = measure(&sim_paths(&w, 4, 100, DEFAULT_SEED), 100);
        let row = gate_checks(a, &on)
            .into_iter()
            .find(|r| r.0.starts_with("dividend yield"))
            .expect("no dividend row with the dial on");
        assert!(
            row.1,
            "the anchored dial must pass its own band: mean {}, row {}",
            on.div_yield_mean, row.0
        );
        assert!(row.2 == GateClass::Fidelity);
        let mut far = default_world();
        far.div_yield = 9.0;
        let far_st = measure(&sim_paths(&far, 4, 100, DEFAULT_SEED), 100);
        assert!(
            !gate_checks(a, &far_st)
                .iter()
                .any(|r| r.0.starts_with("dividend yield") && r.1),
            "a yield outside the record's annual means must fail the row"
        );
        assert!(IDENTITY_PARAMS.contains(&"divYield"));
        assert!(!calibrate_ranges().iter().any(|r| r.0 == "divYield"));
    }
}

/// The open: a derived channel that reaches no price. Off, the bar is the 0.23.0 bar byte for
/// byte; on, the bar brackets the open and the close, the overnight part never overshoots the
/// session on its own side, and the anchored worlds read the record's overnight share. The Scala
/// twin carries the same checks in `OpenSuite`.
#[cfg(test)]
mod open_tests {
    use super::*;

    fn recipe(name: &str) -> World {
        recipes()
            .into_iter()
            .find(|(n, _, _)| *n == name)
            .map(|(_, w, _)| w)
            .unwrap_or_else(|| panic!("no recipe {name}"))
    }

    #[test]
    fn off_is_bit_identical_and_carries_no_open_and_every_frozen_release_keeps_the_open_off() {
        let mut bars = default_world();
        bars.range_scale = 0.63;
        bars.range_down = 0.09;
        let off = simulate(&bars, 3, DEFAULT_SEED);
        assert!(off.log_open.is_empty());
        let mut prev = off.price[0].ln();
        for i in 0..off.price.len() {
            let c = off.price[i].ln();
            assert!(
                off.log_hi[i] >= prev.max(c) - 1e-9 && off.log_lo[i] <= prev.min(c) + 1e-9,
                "bar {i}"
            );
            prev = c;
        }
        for (v, w) in releases() {
            assert!(w.overnight == 0.0, "release {v}");
        }
        assert!(recipe("0.23.0-nasdaq").overnight == 0.0);
        assert!(default_world().overnight == 0.0);
    }

    #[test]
    fn the_open_recipe_is_the_nasdaq_recipe_with_exactly_the_open_and_the_re_anchored_bar_dials_moved()
     {
        let mut want = recipe("0.23.0-nasdaq");
        want.overnight = 0.22;
        want.range_scale = 0.78;
        want.range_down = 0.13;
        want.div_yield = 0.78;
        assert!(
            recipe("0.23.1-nasdaq") == want,
            "0.23.1-nasdaq must differ from 0.23.0-nasdaq in the open, the two bar dials and the dividend only"
        );
    }

    #[test]
    fn the_nasdaq_basket_recipe_is_the_0_23_1_nasdaq_world_with_exactly_the_basket_dials_moved() {
        // The S&P set's basket dials do NOT transport: the eight correlate more with QQQ than with
        // SPY, so the sector's own noise falls, and the level-3 rows want more per-name tail.
        let mut want = recipe("0.23.1-nasdaq");
        want.basket = 8;
        want.basket_beta = 1.37;
        want.basket_sector = 0.7;
        want.basket_idio = 0.85;
        want.basket_gaps = 8.0;
        assert!(recipe("0.23.1-nasdaq-basket") == want);
        let sp = recipe("0.23.1-basket");
        assert!(want.basket_sector != sp.basket_sector && want.basket_beta != sp.basket_beta);
        assert!(
            recipes()
                .iter()
                .find(|(n, _, _)| *n == "0.23.1-nasdaq-basket")
                .expect("recipe")
                .2
                == "nasdaq"
        );
    }

    #[test]
    fn on_the_open_sits_between_the_prior_close_and_the_close_on_its_own_side_and_the_bar_brackets_open_and_close()
     {
        let mut w = default_world();
        w.range_scale = 0.78;
        w.range_down = 0.13;
        w.overnight = 0.20;
        let p = simulate(&w, 5, DEFAULT_SEED);
        assert_eq!(p.log_open.len(), p.price.len());
        let lp: Vec<f64> = p.price.iter().map(|v| v.ln()).collect();
        let (mut gap_up, mut gap_down) = (0usize, 0usize);
        for i in 1..lp.len() {
            let o = p.log_open[i] - lp[i - 1];
            let r = lp[i] - lp[i - 1];
            if o < 0.0 && r < 0.0 {
                assert!(o >= r - 1e-12, "session {i} gapped past its own decline");
            }
            if o > 0.0 && r > 0.0 {
                assert!(o <= r + 1e-12, "session {i} gapped past its own advance");
            }
            if o > 0.0 {
                gap_up += 1;
            } else if o < 0.0 {
                gap_down += 1;
            }
            assert!(
                p.log_hi[i] >= p.log_open[i].max(lp[i]) - 1e-9
                    && p.log_lo[i] <= p.log_open[i].min(lp[i]) + 1e-9,
                "bar {i} must bracket its open and close"
            );
        }
        assert!(gap_up > 0 && gap_down > 0, "the open gaps both ways");
        let mut off_w = w;
        off_w.overnight = 0.0;
        let off = simulate(&off_w, 5, DEFAULT_SEED);
        assert!(
            p.price == off.price,
            "the price itself is untouched by the open"
        );
    }

    /// The bars fixture's overnightShare rows: 0.33 (SPY) / 0.28 (QQQ), tol 0.10. A small
    /// ensemble, so the tolerance is the fixture's, not the scoring size's.
    #[test]
    fn the_anchored_open_worlds_read_the_records_overnight_share_and_the_opens_rows_grade_only_when_it_ran()
     {
        let a = anchors_named("sp500");
        let mut w = default_world();
        w.range_scale = 0.78;
        w.range_down = 0.13;
        w.vol_idio = 0.34;
        w.overnight = 0.20;
        let sp = measure(&sim_paths(&w, 8, 100, DEFAULT_SEED), 100);
        let os = sp.open.expect("no open readings with the dial on");
        assert!(
            (os.overnight_share - 0.33).abs() < 0.10,
            "S&P overnight share {:.3}",
            os.overnight_share
        );
        assert!(
            os.worst_gap_share > os.all_gap_share,
            "the worst sessions must open with more of the day gone: {:.3} vs {:.3}",
            os.worst_gap_share,
            os.all_gap_share
        );
        let rows = gate_checks(a, &sp);
        assert!(
            rows.iter()
                .any(|r| r.0.starts_with("bar overnight share") && r.1)
        );
        assert!(
            rows.iter().any(|r| r.0.starts_with("overnight gap share")
                && r.1
                && r.2 == GateClass::Mechanism)
        );
        let off = measure(&sim_paths(&default_world(), 4, 20, DEFAULT_SEED), 20);
        assert!(
            off.open.is_none()
                && !gate_checks(a, &off)
                    .iter()
                    .any(|r| r.0.contains("overnight"))
        );
    }
}

/// The basket's anchors are MEASURED numbers; this re-derives the graded bands from the
/// checked-in fixture (the eight names' ranges at level 1 and 3, SMH's relation to SPY at level
/// 2) so the code and the record cannot drift apart, and pins the channel's contracts: off is
/// bit-identical, the names are observational, the mechanism row discriminates. The Scala twin
/// carries the same checks in `BasketAnchorSuite`, against the same file.
#[cfg(test)]
mod basket_anchor_tests {
    use super::*;

    const FIXTURE: &str = "../test-data/equity-anchors/basket-2026-09-02.tsv";

    fn rows() -> Option<Vec<Vec<String>>> {
        let text = std::fs::read_to_string(FIXTURE).ok()?;
        Some(
            text.lines()
                .filter(|l| {
                    !l.starts_with('#') && !l.starts_with("group\t") && !l.trim().is_empty()
                })
                .map(|l| l.split('\t').map(str::to_string).collect())
                .collect(),
        )
    }

    fn value(rs: &[Vec<String>], group: &str, name: &str, stat: &str) -> f64 {
        rs.iter()
            .find(|r| r[0] == group && r[1] == name && r[2] == stat)
            .unwrap_or_else(|| panic!("fixture row [{group} {name} {stat}] missing"))[3]
            .parse()
            .expect("numeric fixture value")
    }

    fn eight(rs: &[Vec<String>], stat: &str) -> Vec<f64> {
        rs.iter()
            .filter(|r| r[0] == "eight" && r[2] == stat)
            .map(|r| r[3].parse().expect("numeric"))
            .collect()
    }

    fn anchored() -> World {
        let mut w = default_world();
        w.basket = 8;
        w.basket_beta = 1.56;
        w.basket_sector = 1.1;
        w.basket_idio = 0.9;
        w.basket_gaps = 6.0;
        w
    }

    fn fmin(v: &[f64]) -> f64 {
        v.iter().cloned().fold(f64::INFINITY, f64::min)
    }
    fn fmax(v: &[f64]) -> f64 {
        v.iter().cloned().fold(f64::NEG_INFINITY, f64::max)
    }
    fn near(a: f64, b: f64) -> bool {
        (a - b).abs() < 1e-9
    }

    /// The bands live in `gate_checks`' names, derived from the bounds they test; read them back
    /// off a measured world so the test grades the code that runs, not a copy.
    fn gate(a: Anchors, name: &str) -> (f64, f64) {
        let st = measure(&sim_paths(&anchored(), 2, 10, DEFAULT_SEED), 10);
        let row = gate_checks(a, &st)
            .into_iter()
            .map(|r| r.0)
            .find(|n| n.starts_with(&format!("{name} ")))
            .unwrap_or_else(|| panic!("no gate row [{name}]"));
        let rest = row[name.len() + 1..].to_string();
        let (lo, hi) = rest.split_once('-').expect("lo-hi");
        (lo.parse().expect("lo"), hi.parse().expect("hi"))
    }

    fn out1(lo: f64, hi: f64) -> (f64, f64) {
        ((lo * 10.0).floor() / 10.0, (hi * 10.0).ceil() / 10.0)
    }
    fn out2(lo: f64, hi: f64) -> (f64, f64) {
        ((lo * 100.0).floor() / 100.0, (hi * 100.0).ceil() / 100.0)
    }
    fn at2(x: f64) -> f64 {
        (x * 100.0).round() / 100.0
    }
    fn same(g: (f64, f64), w: (f64, f64)) -> bool {
        near(g.0, w.0) && near(g.1, w.1)
    }

    #[test]
    fn the_graded_bands_are_the_fixtures_per_anchor_set() {
        let Some(rs) = rows() else {
            return;
        };
        for (a, primary, sfx) in [
            (anchors_named("sp500"), "SPY", "Spy"),
            (anchors_named("nasdaq"), "QQQ", "Qqq"),
        ] {
            let pv = value(&rs, "basket", primary, "vol");
            let vr: Vec<f64> = eight(&rs, "vol").iter().map(|v| v / pv).collect();
            assert!(
                same(gate(a, "basket name vol ratio"), out1(fmin(&vr), fmax(&vr))),
                "{} name vol ratio",
                a.name
            );
            let gp = eight(&rs, "gaps10");
            assert!(same(
                gate(a, "basket name gaps/yr"),
                out1(fmin(&gp), fmax(&gp))
            ));
            let corr = value(&rs, "basket", "basket", &format!("corr{sfx}"));
            assert!(
                same(gate(a, "basket corr"), (at2(corr - 0.10), at2(corr + 0.10))),
                "{} corr",
                a.name
            );
            let beta = value(&rs, "basket", "basket", &format!("betaOn{sfx}"));
            assert!(
                same(gate(a, "basket beta"), out1(beta - 0.25, beta + 0.25)),
                "{} beta",
                a.name
            );
            let volr = value(&rs, "basket", "basket", &format!("volRatio{sfx}"));
            assert!(
                same(gate(a, "basket vol ratio"), out1(volr - 0.30, volr + 0.30)),
                "{} vol ratio",
                a.name
            );
            assert!(same(
                gate(a, "basket pair corr"),
                out2(
                    value(&rs, "cross", "pairCorr", "min"),
                    value(&rs, "cross", "pairCorr", "max")
                )
            ));
            assert!(same(
                gate(a, "basket idio share"),
                out2(
                    value(&rs, "cross", "idioShare", "min"),
                    value(&rs, "cross", "idioShare", "max")
                )
            ));
            let tc = value(&rs, "cross", "tailCoincidence", "value");
            assert!(same(
                gate(a, "basket tail coincidence"),
                (
                    ((tc - 0.13) * 100.0).round() / 100.0,
                    ((tc + 0.12) * 100.0).round() / 100.0
                )
            ));
        }
        assert!(
            value(&rs, "mechanism", "pairCorr", "spyWorstDecile")
                > value(&rs, "mechanism", "pairCorr", "spyMiddleDecile"),
            "the mechanism row's premise must hold in the record"
        );
    }

    #[test]
    fn off_is_bit_identical_and_carries_no_names_and_every_frozen_world_keeps_the_basket_off() {
        let off = simulate(&default_world(), 3, DEFAULT_SEED);
        let on = simulate(&anchored(), 3, DEFAULT_SEED);
        assert!(off.names.is_empty());
        assert_eq!(on.names.len(), 8);
        assert!(
            on.price == off.price && on.fundamental == off.fundamental,
            "the names are observational"
        );
        for (v, w) in releases() {
            assert!(w.basket == 0, "release {v}");
        }
        for (n, w, _) in recipes() {
            if !n.ends_with("basket") {
                assert!(w.basket == 0, "recipe {n}");
            }
        }
        assert!(default_world().basket == 0);
        let mut chans = default_world();
        chans.sat_beta = 1.2;
        chans.sat_idio = 0.77;
        chans.range_scale = 0.63;
        chans.range_down = 0.09;
        let a = simulate(&chans, 3, DEFAULT_SEED);
        let mut with = chans;
        with.basket = 8;
        with.basket_beta = 1.56;
        with.basket_sector = 1.1;
        with.basket_idio = 0.9;
        with.basket_gaps = 6.0;
        let b = simulate(&with, 3, DEFAULT_SEED);
        assert!(
            a.sat == b.sat && a.log_hi == b.log_hi,
            "the basket reads its own stream only"
        );
    }

    /// A small ensemble at the verdict horizon; the bands are the eight's own ranges, wide enough
    /// that 8 paths read inside them wherever 200 do.
    #[test]
    fn the_anchored_basket_sits_on_its_anchors_and_the_mechanism_row_discriminates() {
        let Some(rs) = rows() else {
            return;
        };
        let st = measure(&sim_paths(&anchored(), 8, 100, DEFAULT_SEED), 100);
        let b = st.basket.expect("no basket readings with the channel on");
        let rows: Vec<(String, bool, GateClass)> = gate_checks(anchors_named("sp500"), &st)
            .into_iter()
            .filter(|r| r.0.starts_with("basket"))
            .collect();
        assert_eq!(rows.len(), 9);
        for (nm, ok, _) in &rows {
            assert!(
                ok,
                "{nm} failed: names vol {:.2} gaps {:.2} corr {:.3} beta {:.2} volr {:.2} pair {:.3} idio {:.3} coinc {:.3}",
                b.name_vol_ratio,
                b.name_gaps,
                b.agg_corr,
                b.agg_beta,
                b.agg_vol_ratio,
                b.pair_corr,
                b.idio_share,
                b.tail_coincidence
            );
        }
        assert!(
            b.pair_corr_worst > b.pair_corr_mid + 0.15,
            "stress must raise pairwise correlation materially: {:.3} vs {:.3}",
            b.pair_corr_worst,
            b.pair_corr_mid
        );
        // A DISCLOSED reading, not a gate. Since the own-gap channel became symmetric the model
        // lands inside the eight's 0.08-0.61 but above their median (0.236): what is left of the
        // gap is their COMMON drift, +0.304/yr against the shared leg's, which is the
        // survivorship the fixture discloses — see `basket-drift-2026-09-03.tsv`.
        let mut eight_d20 = eight(&rs, "d20");
        eight_d20.sort_by(|a, b| a.partial_cmp(b).expect("finite"));
        assert!(
            b.name_d20 > eight_d20[eight_d20.len() / 2],
            "the names' time below peak is a disclosed reading, expected above the winners' median: {}",
            b.name_d20
        );
        let off = measure(&sim_paths(&default_world(), 4, 20, DEFAULT_SEED), 20);
        assert!(
            off.basket.is_none()
                && !gate_checks(anchors_named("sp500"), &off)
                    .iter()
                    .any(|r| r.0.starts_with("basket"))
        );
    }
}

/// Cross-sectional drift dispersion (`basket_drift`): a per-name annual log-drift offset, centred
/// exactly so the sector is untouched. The dial is ANCHORED AT 0 by the checked-in fixture, and
/// that is the unusual thing worth pinning: the fixture says no true dispersion is DETECTABLE in a
/// survivor cache, so a shipped world that turned this on would be asserting something the record
/// cannot support. The Scala twin carries the same checks in `BasketDriftSuite`.
#[cfg(test)]
mod basket_drift_tests {
    use super::*;

    const FIXTURE: &str = "../test-data/equity-anchors/basket-drift-2026-09-03.tsv";

    fn rows() -> Option<Vec<Vec<String>>> {
        let text = std::fs::read_to_string(FIXTURE).ok()?;
        Some(
            text.lines()
                .filter(|l| {
                    !l.starts_with('#') && !l.starts_with("group\t") && !l.trim().is_empty()
                })
                .map(|l| l.split('\t').map(str::to_string).collect())
                .collect(),
        )
    }

    fn value(rs: &[Vec<String>], group: &str, stat: &str) -> f64 {
        rs.iter()
            .find(|r| r[0] == group && r[1] == stat)
            .unwrap_or_else(|| panic!("fixture row [{group} {stat}] missing"))[2]
            .parse()
            .expect("numeric fixture value")
    }

    fn basket() -> World {
        let mut w = default_world();
        w.basket = 8;
        w.basket_beta = 1.56;
        w.basket_sector = 1.1;
        w.basket_idio = 0.9;
        w.basket_gaps = 6.0;
        w
    }

    #[test]
    fn the_fixture_anchors_the_dial_at_zero_the_spread_is_under_the_windows_noise_floor() {
        let Some(rs) = rows() else {
            return;
        };
        for g in ["eight", "pop26"] {
            let spread = value(&rs, g, "driftSpread");
            let floor = value(&rs, g, "noiseFloor");
            let truth = value(&rs, g, "trueSpread");
            assert!(
                spread <= floor,
                "[{g}] the fixture claims no detectable dispersion, but {spread} exceeds {floor}"
            );
            let decomposed = (spread * spread - floor * floor).max(0.0).sqrt();
            assert!((truth - decomposed).abs() < 1e-9, "[{g}] trueSpread");
            assert!(truth.abs() < 1e-9, "[{g}] the shipped anchor is 0");
            // the same answer on the residual after the group index, so beta spread is not the cause
            assert!(
                value(&rs, g, "alphaSpread") <= value(&rs, g, "alphaNoiseFloor"),
                "[{g}] alpha"
            );
        }
        for (v, w) in releases() {
            assert!(w.basket_drift == 0.0, "release {v}");
        }
        for (n, w, _) in recipes() {
            assert!(w.basket_drift == 0.0, "recipe {n}");
        }
        assert!(default_world().basket_drift == 0.0);
    }

    #[test]
    fn off_is_bit_identical_and_on_disperses_the_names_without_moving_the_sector() {
        let off = simulate(&basket(), 12, DEFAULT_SEED);
        let mut w = basket();
        w.basket_drift = 0.6;
        let on = simulate(&w, 12, DEFAULT_SEED);
        let mut z = basket();
        z.basket_drift = 0.0;
        let zero = simulate(&z, 12, DEFAULT_SEED);
        for q in 0..off.names.len() {
            assert!(
                off.names[q] == zero.names[q],
                "name {q}: 0 must be bit-identical"
            );
        }
        assert!(on.price == off.price, "the dispersion reaches no price");
        assert!(
            on.names.iter().any(|a| *a != off.names[0]),
            "on must move the names"
        );
        // THE CENTRING: the mean of the names' log drifts is what the sector's is, exactly.
        let mean_final = |p: &Path| -> f64 {
            p.names.iter().map(|a| a[a.len() - 1] - a[0]).sum::<f64>() / p.names.len() as f64
        };
        assert!(
            (mean_final(&on) - mean_final(&off)).abs() < 1e-9,
            "centred offsets must leave the equal-weight sector's log drift untouched"
        );
    }

    #[test]
    fn the_dial_widens_the_spread_of_time_below_peak_and_leaves_its_median_alone() {
        // At a CENTURY the off-state spread is only what estimation noise leaves, so this is where
        // the dial's own contribution is legible.
        let stats = |d: f64| {
            let mut w = basket();
            w.basket_drift = d;
            basket_stats(&sim_paths(&w, 4, 100, DEFAULT_SEED)).expect("basket")
        };
        let a = stats(0.0);
        let b = stats(0.8);
        assert!(
            b.name_d20_spread > a.name_d20_spread * 2.0,
            "the dial must disperse time below peak: {} -> {}",
            a.name_d20_spread,
            b.name_d20_spread
        );
        // THE LEVEL IS THE COMMON DRIFT'S, and mean-zero dispersion barely touches it — which is
        // why this dial is not the fix for the level the basket fixture discloses as
        // survivorship. Stated as a ratio rather than an absolute: the order statistic does shift
        // a little once the level is off its ceiling, but by a small fraction of what the spread
        // does (at 200x100 the spread runs 0.14 -> 0.67 across the dial while the median goes
        // 0.528 -> 0.557).
        let d_level = (b.name_d20 - a.name_d20).abs();
        let d_spread = b.name_d20_spread - a.name_d20_spread;
        assert!(
            d_level < 0.25 * d_spread,
            "the dial must move the SPREAD, not the level: level {d_level} vs spread {d_spread}"
        );
        // the graded level-2 rows are untouched: a constant per-name drift adds no covariance
        assert!((b.agg_corr - a.agg_corr).abs() < 1e-3);
        assert!((b.agg_beta - a.agg_beta).abs() < 1e-3);
        assert!((b.agg_vol_ratio - a.agg_vol_ratio).abs() < 1e-3);
    }

    #[test]
    fn the_names_own_gaps_impose_no_drift_the_channel_is_symmetric_as_the_records_are() {
        // THE DEFECT THIS PINS. Own gaps carrying the primary's down-skew cost -0.22/yr of log
        // drift that no dial compensated: every name's expected drift went NEGATIVE and the
        // time-below-peak reading sat near 1. The record says a name's OWN large moves are not
        // skewed down — the index's skew is the INDEX's, and already reaches every name through
        // the shared leg.
        let Some(rs) = rows() else {
            return;
        };
        assert!(
            value(&rs, "eight", "idioGapsUp") >= value(&rs, "eight", "idioGapsDown"),
            "the record's own-name gaps past 10% are not down-skewed"
        );
        assert!(
            value(&rs, "eight", "idioGapMean") >= 0.0,
            "nor is their mean negative"
        );
        assert!(
            value(&rs, "eight", "indexGapSkew") < 0.0,
            "the INDEX is down-skewed — that is the skew the shared leg carries, and only it"
        );
        let drift = |w: &World| -> f64 {
            let ps = sim_paths(w, 8, 100, DEFAULT_SEED);
            let per: Vec<f64> = ps
                .iter()
                .map(|p| {
                    let years = p.names[0].len() as f64 / DAYS_PER_YEAR as f64;
                    p.names
                        .iter()
                        .map(|a| (a[a.len() - 1] - a[0]) / years)
                        .sum::<f64>()
                        / p.names.len() as f64
                })
                .collect();
            per.iter().sum::<f64>() / per.len() as f64
        };
        let on = drift(&basket());
        let mut off_w = basket();
        off_w.basket_gaps = 0.0;
        let off = drift(&off_w);
        assert!(
            (on - off).abs() < 0.03,
            "the gap channel must be drift-neutral: {off:.4} with gaps off, {on:.4} with them on"
        );
    }
}

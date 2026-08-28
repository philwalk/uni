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
//! Run: `cargo run --release --example market_sim -- -validate`
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
//! - **`%.6f` is Java's, not Rust's.** [`uni::udata::java_format_f`] rounds the shortest
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
use uni::NumPyRng;
use uni::udata::MatD;
use uni::udata::java_format_f;
use uni::utime::UniDateTime;

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
const EMIT_SCHEMA: u32 = 3;

// Referenced only from the test module below; in a normal build of the example it is
// deliberately unread — the writer must not consult its own contract.
#[cfg_attr(
    not(test),
    expect(
        dead_code,
        reason = "the contract is read by the tests, never by the writer"
    )
)]
const EMIT_SIDECAR_KEYS: [&str; 10] = [
    "generator",
    "version",
    "schema",
    "file",
    "columns",
    "header",
    "path",
    "world",
    "gate",
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
/// `JUMP_ASYM` shifts the jump down by 0.4 of its own sd, carrying the negative skew a symmetric
/// jump cannot.
const JUMP_NU: usize = 5;
const JUMP_GAMMA: f64 = 2.0;
const JUMP_ASYM: f64 = 0.4;

/// Jump size, from the share of variance it carries and how often it fires. `1 + JUMP_ASYM^2` is
/// the shift's own contribution to the second moment; without it the channel would overshoot the
/// variance it is borrowing and `equity vol %` would drift with `jump_var`.
fn jump_scale(w: &World) -> f64 {
    SIGMA_N * (w.jump_var / (w.jump_rate * (1.0 + JUMP_ASYM * JUMP_ASYM))).sqrt()
}
/// `crowdImpact` at which the momentum crowd reproduces the frozen `k_trend` exactly. The ratio
/// is what enters the flow, so the default divides to a bit-exact 1.0 and the shipped world is
/// unchanged; every other setting scales the reflexive channel that used to have no dial at all.
const CROWD_IMPACT_REF: f64 = 0.06;
/// THE shipped world. `main` seeds its mutable CLI variables from this and the release table
/// derives its rows from it, so every default is written once — the same one-source rule the Scala
/// twin's `Defaults` follows.
fn default_world() -> World {
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
        jump_var: 0.10,
        jump_rate: 0.0010,
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
        jump_var: 0.0,
        jump_rate: 0.0,
        value_pull: 0.013,
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

fn releases() -> Vec<(&'static str, World)> {
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
        ("0.21.0", default_world()),
    ]
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
        jump_var: 0.0,
        jump_rate: 0.0,
        value_pull: 0.0145,
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
enum Crowd {
    Momentum,
    Trend(i32),
    VolScaled,
}

#[derive(Clone, Copy, Debug)]
struct World {
    trend_share: f64,
    depth: f64,
    stress: f64,
    beta: f64,
    /// fundamental drift per year; no dividend, so this IS total return
    drift: f64,
    fund_vol: f64,
    rate_mean: f64,
    vol_persist: f64,
    vol_of_vol: f64,
    /// how fast value arbitrage WEAKENS as the drawdown deepens. 0 is the symmetric pull every
    /// release before 0.21.0 had, bit for bit.
    recovery_drag: f64,
    /// the residual arbitrage that never goes away, as a share of full strength. 1.0 with drag 0
    /// is the old behaviour exactly.
    recovery_floor: f64,
    /// share of the equity flow's VARIANCE carried by jumps rather than diffusion. 0 disables the
    /// channel and reproduces pre-0.21 behaviour byte for byte — the draws come from their own
    /// stream, so nothing else in the path shifts.
    jump_var: f64,
    /// unconditional jump intensity per session. With `jump_var` it fixes the size: rarer jumps of
    /// the same total variance are larger ones.
    jump_rate: f64,
    value_pull: f64,
    crowd: Crowd,
    crowd_impact: f64,
    panic: f64,
    /// bond duration: sensitivity of its fair value to the rate
    duration: f64,
    /// CAP on policy accommodation under equity stress, in rate points
    easing: f64,
    /// how fast that accommodation is withdrawn, per year
    unwind: f64,
    /// flight-to-quality bid into the bond, per unit of equity stress
    refuge: f64,
    infl_prob: f64,
    infl_size: f64,
    infl_speed: f64,
    rate_speed: f64,
    /// equity fair-value markdown per pp of rate above its long-run mean
    discount: f64,
    /// joint-stress forced selling pressure on the bond
    margin: f64,
}

#[derive(Clone, Debug)]
struct Path {
    price: Vec<f64>,
    rate: Vec<f64>,
    fundamental: Vec<f64>,
    /// per-session slippage multiplier (equity market)
    liq: Vec<f64>,
    /// the same, for the BOND market: an arm that trades the bond is charged its own
    /// market's slippage, not the equity book's
    bliq: Vec<f64>,
    /// flight-to-safety asset price (its own Market)
    bond: Vec<f64>,
    /// inflation pressure, for regime classification
    infl_press: Vec<f64>,
    /// realized price level, deterministic from pressure
    cpi: Vec<f64>,
    /// BINDING diagnostic for the population knob
    mean_trend_share: f64,
    /// share of sessions on the numerical guard rails
    trend_pinned: f64,
    /// share of sessions the choice target saturated
    target_sat: f64,
    /// both markets, post-burn-in
    clamped_days: usize,
    /// BINDING diagnostic for the bond spiral
    mean_bond_stress: f64,
    /// share of sessions bond stress index > 0.5
    pct_bond_stress: f64,
    /// BINDING diagnostic for the reflexive channel: mean |crowd flow| per session, post burn-in.
    /// Its ABSENCE is why -crowdimpact sat dead in the default world across four releases.
    /// the world's bond duration, carried so the gate can judge bond volatility RELATIVE to it;
    /// a fixed absolute band can only ever fit one bond
    duration: f64,
    mean_crowd_flow: f64,
}

/// ONE price-formation mechanism for every traded asset: value demand toward `fair`, plus
/// external flow and noise, amplified when THIS market's liquidity has withdrawn after
/// one-sided selling (measured against a slowly-adapting scale, so symmetric turbulence of
/// any size leaves the index flat — E[max(0,-z)] = 0.399 regardless of scale).
/// Drawdown at which recovery drag reaches its stated strength. 0.10 keeps it inert in ordinary
/// sessions, so it shapes recoveries from real drawdowns and nothing else.
const DRAWDOWN_REF: f64 = 0.10;

struct Market {
    k_value: f64,
    stress_k: f64,
    impact: f64,
    recovery_drag: f64,
    recovery_floor: f64,
    log_p: f64,
    peak: f64,
    stress_idx: f64,
    last_liq: f64,
    clamps: usize,
    scale_var: f64,
}

impl Market {
    fn new(k_value: f64, stress_k: f64, impact: f64) -> Self {
        Self::with_recovery(k_value, stress_k, impact, 0.0, 1.0)
    }

    fn with_recovery(
        k_value: f64,
        stress_k: f64,
        impact: f64,
        recovery_drag: f64,
        recovery_floor: f64,
    ) -> Self {
        Self {
            k_value,
            stress_k,
            impact,
            recovery_drag,
            recovery_floor,
            log_p: 0.0,
            peak: 0.0,
            stress_idx: 0.0,
            last_liq: impact,
            clamps: 0,
            scale_var: 0.01 * 0.01,
        }
    }

    fn step(&mut self, fair: f64, flow_plus_noise: f64) -> f64 {
        let scale = self.scale_var.sqrt();
        let amp = 1.0 + self.stress_k * self.stress_idx;
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
            self.recovery_floor
                .max(1.0 / (1.0 + self.recovery_drag * (drop - DRAWDOWN_REF) / DRAWDOWN_REF))
        };
        let raw = (self.k_value * gap * damp + flow_plus_noise * amp) * self.impact;
        // Numerical guard ONLY, and verified to be exactly that: at ±0.25 vs ±0.50 every
        // statistic in every gate-passing world is BIT-IDENTICAL (the clamp consumes no
        // draws and never binds there). It sits at ±0.50, far from any plausible daily move
        // (worst real S&P day ~ -23% log), and the gate rejects any world where it engages.
        let ret = (-0.50f64).max(0.50f64.min(raw));
        if ret != raw {
            self.clamps += 1;
        }
        self.log_p += ret;
        if self.log_p > self.peak {
            self.peak = self.log_p;
        }
        self.scale_var = 0.995 * self.scale_var + 0.005 * ret * ret;
        self.stress_idx =
            0.0f64.max(0.96 * self.stress_idx + 0.04 * (0.0f64.max(-ret) / scale - 0.399));
        ret
    }
}

/// One independent history. Local mutable state only — nothing escapes this function.
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
fn simulate(w: &World, years: usize, seed: u64) -> Path {
    let n = years * DAYS_PER_YEAR;
    let tot = n + BURN_IN;
    let mut rng = NumPyRng::new(seed);
    // The jump channel's own stream. Separate BECAUSE the alternative is not survivable: a draw
    // taken from `rng` shifts every subsequent value and moves all sixteen calibrated statistics,
    // so the channel could not be added without re-searching the world. Constructed
    // unconditionally — it costs one allocation and touches nothing — and read only when
    // `jump_var > 0`.
    let mut jrng = NumPyRng::new(seed ^ 0x1eaf_7a11u64);
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
    );
    let mut bd_m = Market::new(K_VALUE_BOND, w.stress, 1.0);

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
    let k_trend = 0.0045f64;
    let k_adapt = 0.010f64;
    let k_home = 0.020f64;
    let mut log_vol = 0.0f64;
    let vol_norm = (w.vol_of_vol * w.vol_of_vol) / 1e-9f64.max(1.0 - w.vol_persist * w.vol_persist);
    let crowd_win: usize = match w.crowd {
        Crowd::Trend(d) => 2.max((f64::from(d) * 252.0 / 365.25).round() as usize),
        _ => 0,
    };
    let mut crowd_e = 1.0f64;
    let mut crowd_prev = 1.0f64;
    let mut ma_sum = 0.0f64;
    let mut crowd_rv = 0.01 * 0.01f64;
    let mut crowd_anchor = 0.0f64;
    let mut bond_stress_sum = 0.0f64;
    let mut bond_stress_hi = 0usize;
    let mut crowd_flow_sum = 0.0f64;
    let mut clamps_at_burn = 0usize;

    let mut i = 0usize;
    while i < tot {
        // ---- exogenous layer: regimes, fundamental, the policy rate ----------------------
        regime_countdown -= 1;
        if regime_countdown <= 0 {
            infl_target = if rng.next_f64() < w.infl_prob {
                rng.randn().abs() * w.infl_size
            } else {
                0.0
            };
            drift_now = w.drift + rng.randn() * 0.04;
            regime_countdown = 250 + i64::from(rng.next_bounded_u32(2500));
        }
        log_vbase += drift_now * dt + w.fund_vol * sqdt * rng.randn();
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
        let trend_pos = (momentum / 0.12).tanh();
        let eq_flow = match w.crowd {
            Crowd::Momentum => k_trend * (w.crowd_impact / CROWD_IMPACT_REF) * w_trend * trend_pos,
            _ => w.crowd_impact * w_trend * (crowd_e - crowd_prev),
        };
        crowd_prev = crowd_e;
        log_vol = w.vol_persist * log_vol + w.vol_of_vol * rng.randn();
        let d_noise = SIGMA_N * (log_vol - vol_norm).exp() * rng.randn();

        // The jump channel. Its draws come from `jrng`, NOT `rng`, so a world with jump_var = 0
        // takes the untouched branch below and every pre-0.21 statistic reproduces bit for bit —
        // the failure mode a shared stream would have caused is not a risk that was reasoned about,
        // it is one the branch removes. `vol_mult` is this session's volatility state, so jumps
        // CLUSTER inside a stressed stretch instead of scattering uniformly, which is what turns a
        // fat tail into a survivable-or-not sequence for anything levered.
        let eq_shock = if w.jump_var <= 0.0 {
            d_noise
        } else {
            let vol_mult = (log_vol - vol_norm).exp();
            let lam_now = 0.25f64.min(w.jump_rate * vol_mult.powf(JUMP_GAMMA));
            let scale = jump_scale(w);
            // The compensator is deterministic and consumes no draw: it removes the mean the
            // downward shift would otherwise add, so `jump_var` moves the tail without moving drift.
            let compens = w.jump_rate * JUMP_ASYM * scale;
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
                (t - JUMP_ASYM) * scale
            };
            d_noise * (1.0 - w.jump_var).sqrt() + jump + compens
        };

        // ---- both markets step through the SAME mechanism ---------------------------------
        let ret_e = eq_m.step(log_vbase, eq_flow + eq_shock);
        // joint-stress margin selling: when both markets are stressed, the bond gets dumped too —
        // and against it the refuge bid, flight-to-quality into a bond that is itself still
        // orderly. DURATION-SCALED, like the bond's own noise: an absolute bid gave a 5-year bond
        // the same crash rally as a 20-year one, which no duration-relative band can then fit.
        let bond_flow = -w.margin * eq_m.stress_idx * bd_m.stress_idx
            + w.refuge
                * (w.duration / DURATION_REF)
                * eq_m.stress_idx
                * 0.0f64.max(1.0 - bd_m.stress_idx);
        let _ret_b = bd_m.step(
            fair_b,
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

        // ---- capital reallocation: spring, scored on positions actually held ---------------
        perf_v = 0.99 * perf_v + 0.01 * (mispricing_pre * ret_e) * 100.0;
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
        }
        i += 1;
    }

    let nf = n as f64;
    Path {
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
        mean_bond_stress: bond_stress_sum / nf,
        pct_bond_stress: bond_stress_hi as f64 / nf,
        duration: w.duration,
        mean_crowd_flow: crowd_flow_sum / nf,
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
fn autocorr_abs(r: &[f64], lag: usize) -> f64 {
    let a = MatD::apply(r).abs();
    let z = &a - a.mean();
    let den = z.power(2).sum();
    if den <= 0.0 || r.len() <= lag {
        f64::NAN
    } else {
        let n = r.len();
        // Scala writes these as z(0 until n-lag, 0) and z(lag until n, 0); on an n x 1
        // column those are exactly row slices.
        (&z.applyRowsAll(0..n - lag) * &z.applyRowsAll(lag..n)).sum() / den
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

#[derive(Clone, Copy, Debug)]
struct WorldStats {
    vol: f64,
    kurt: f64,
    ac1: f64,
    ac20: f64,
    ann_ret: f64,
    n_episodes: usize,
    ep_per_path: f64,
    depth_med: f64,
    worst_depth: f64,
    v_count: usize,
    mid_count: usize,
    u_count: usize,
    n_shapes: usize,
    censored: usize,
    clamp_pct: f64,
    trend_share: f64,
    years_per_path: f64,
    trend_pinned: f64,
    target_sat: f64,
    bond_vol: f64,
    bond_growth: f64,
    bond_infl: f64,
    corr_calm: f64,
    corr_infl: f64,
    #[expect(
        dead_code,
        reason = "kept for parity with the Scala record; not printed"
    )]
    mean_bond_stress: f64,
    pct_bond_stress: f64,
    crowd_flow: f64,
    duration: f64,
    infl_ann: f64,
    /// depth profile: median share of sessions more than 5/10/20% below the running peak,
    /// equity leg then bond leg
    dd_eq5: f64,
    dd_eq10: f64,
    dd_eq20: f64,
    dd_bd5: f64,
    dd_bd10: f64,
    dd_bd20: f64,
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

fn med(v: &[f64]) -> f64 {
    let f: Vec<f64> = v.iter().copied().filter(|x| !x.is_nan()).collect();
    if f.is_empty() {
        return f64::NAN;
    }
    let s = sorted_total(&f);
    s[s.len() / 2]
}

fn pctile(v: &[f64], q: f64) -> f64 {
    if v.is_empty() {
        return f64::NAN;
    }
    let s = sorted_total(v);
    s[((v.len() as f64 * q) as usize).min(v.len() - 1)]
}

#[expect(
    clippy::too_many_lines,
    reason = "one Scala method; the field-by-field construction is the readable form"
)]
fn measure(sims: &[Path], years: usize) -> WorldStats {
    let rets: Vec<Vec<f64>> = sims.iter().map(|s| daily_returns(&s.price)).collect();
    // once per path (was recomputed 3x)
    let eps_by: Vec<(&Path, Vec<Episode>)> =
        sims.iter().map(|s| (s, episodes(&s.price, 15.0))).collect();
    let dd_eq: Vec<(f64, f64, f64)> = sims.iter().map(|s| depth_shares(&s.price)).collect();
    let dd_bd: Vec<(f64, f64, f64)> = sims.iter().map(|s| depth_shares(&s.bond)).collect();
    let eps: Vec<Episode> = eps_by.iter().flat_map(|(_, e)| e.iter().copied()).collect();
    let shapes: Vec<f64> = eps
        .iter()
        .map(|e| e.shape())
        .filter(|x| !x.is_nan())
        .collect();
    let days: f64 = scala_sum(sims.iter().map(|s| s.price.len() as f64));

    let bond_in_windows = |infl_regime: bool| -> f64 {
        let vals: Vec<f64> = eps_by
            .iter()
            .flat_map(|(sp, es)| {
                es.iter()
                    .filter(|ep| {
                        let s: f64 = scala_sum((ep.peak..=ep.trough).map(|k| sp.infl_press[k]));
                        let infl = s / 1.max(ep.trough - ep.peak + 1) as f64;
                        (infl > 0.005) == infl_regime
                    })
                    .map(|ep| (sp.bond[ep.trough] / sp.bond[ep.peak]).ln() * 100.0)
                    .collect::<Vec<f64>>()
            })
            .collect();
        med(&vals)
    };

    let corr_in = |infl_regime: bool| -> f64 {
        let vals: Vec<f64> = sims
            .iter()
            .map(|sp| {
                let idx: Vec<usize> = (1..sp.price.len())
                    .filter(|&i| (sp.infl_press[i] > 0.005) == infl_regime)
                    .collect();
                let a: Vec<f64> = idx
                    .iter()
                    .map(|&i| (sp.price[i] / sp.price[i - 1]).ln())
                    .collect();
                let b: Vec<f64> = idx
                    .iter()
                    .map(|&i| (sp.bond[i] / sp.bond[i - 1]).ln())
                    .collect();
                pearson(&a, &b)
            })
            .collect();
        med(&vals)
    };

    let dpy = DAYS_PER_YEAR as f64;
    let n_sims = sims.len() as f64;
    let depths: Vec<f64> = eps.iter().map(|e| e.depth_pct).collect();

    WorldStats {
        vol: med(&rets
            .iter()
            .map(|r| (MatD::apply(r).power(2).mean() * dpy).sqrt())
            .collect::<Vec<f64>>()),
        kurt: med(&rets.iter().map(|r| kurtosis(r)).collect::<Vec<f64>>()),
        ac1: med(&rets
            .iter()
            .map(|r| autocorr_abs(r, 1))
            .collect::<Vec<f64>>()),
        ac20: med(&rets
            .iter()
            .map(|r| autocorr_abs(r, 20))
            .collect::<Vec<f64>>()),
        ann_ret: med(&sims
            .iter()
            .map(|s| (s.price[s.price.len() - 1] / s.price[0]).ln() / years as f64 * 100.0)
            .collect::<Vec<f64>>()),
        n_episodes: eps.len(),
        ep_per_path: eps.len() as f64 / n_sims,
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
        trend_share: scala_sum(sims.iter().map(|s| s.mean_trend_share)) / n_sims,
        years_per_path: years as f64,
        trend_pinned: scala_sum(sims.iter().map(|s| s.trend_pinned)) / n_sims,
        target_sat: scala_sum(sims.iter().map(|s| s.target_sat)) / n_sims,
        // Median over non-overlapping BOND_VOL_YEARS windows, pooled across paths — see
        // BOND_VOL_YEARS for why this row alone is windowed. A path shorter than one window
        // contributes itself, so a short run still reports something rather than nothing.
        bond_vol: med(&sims
            .iter()
            .flat_map(|s| {
                let r = daily_returns(&s.bond);
                let w = BOND_VOL_YEARS * DAYS_PER_YEAR;
                let nw = r.len() / w;
                let segs: Vec<Vec<f64>> = if nw < 1 {
                    vec![r.clone()]
                } else {
                    (0..nw).map(|k| r[k * w..(k + 1) * w].to_vec()).collect()
                };
                segs.into_iter()
                    .map(|seg| (MatD::apply(&seg).power(2).mean() * dpy).sqrt())
                    .collect::<Vec<f64>>()
            })
            .collect::<Vec<f64>>()),
        bond_growth: bond_in_windows(false),
        bond_infl: bond_in_windows(true),
        corr_calm: corr_in(false),
        corr_infl: corr_in(true),
        mean_bond_stress: scala_sum(sims.iter().map(|s| s.mean_bond_stress)) / n_sims,
        pct_bond_stress: scala_sum(sims.iter().map(|s| s.pct_bond_stress)) / n_sims,
        crowd_flow: scala_sum(sims.iter().map(|s| s.mean_crowd_flow)) / n_sims,
        duration: sims[0].duration,
        infl_ann: med(&sims
            .iter()
            .map(|s| (s.cpi[s.cpi.len() - 1] / s.cpi[0]).ln() / years as f64 * 100.0)
            .collect::<Vec<f64>>()),
        dd_eq5: med(&dd_eq.iter().map(|d| d.0).collect::<Vec<f64>>()),
        dd_eq10: med(&dd_eq.iter().map(|d| d.1).collect::<Vec<f64>>()),
        dd_eq20: med(&dd_eq.iter().map(|d| d.2).collect::<Vec<f64>>()),
        dd_bd5: med(&dd_bd.iter().map(|d| d.0).collect::<Vec<f64>>()),
        dd_bd10: med(&dd_bd.iter().map(|d| d.1).collect::<Vec<f64>>()),
        dd_bd20: med(&dd_bd.iter().map(|d| d.2).collect::<Vec<f64>>()),
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
enum GateClass {
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

/// TWO-SIDED wherever a plausible range exists. History of this gate: a one-sided version
/// passed a 35%-volatility world (the one reversing the ranking); a "bonds fail" check
/// written as bondInfl < bondGrowth passed while bonds still RALLIED +2.8; crash frequency
/// shipped without an upper bound WHILE the one-sided lesson was being applied elsewhere.
#[expect(
    clippy::manual_range_contains,
    reason = "kept in the Scala's spelling so the gate reads as the bounds it documents"
)]
#[expect(
    clippy::too_many_lines,
    reason = "one table of bands, mirroring the Scala twin's gateChecks row for row"
)]
fn gate_checks(a: Anchors, st: &WorldStats) -> Vec<(String, bool, GateClass)> {
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
        band_check("equity vol", st.vol * 100.0, 8.0, 40.0, Realism, 0, "%"),
        band_check("kurtosis", st.kurt, 4.0, 30.0, Realism, 0, ""),
        (
            n("clustering 0.10-0.40"),
            st.ac1 > 0.10 && st.ac1 < 0.40 && st.ac20 > 0.03,
            Realism,
        ),
        // Widened from 8-45 for the same reason as the volatility band above: 45 excluded two of
        // the 35 real instruments (EWA, EWW), which read 49.4 and 46.6 over the clean w1996 window
        // against a cross-section range of 13.2-49.4. A band that calls a real market unreal is not
        // a realism check.
        (
            n("crash rate 8-55/century"),
            st.ep_per_path >= 1.0 && pc >= 8.0 && pc <= 55.0,
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

fn failed_in(a: Anchors, st: &WorldStats, cls: GateClass) -> Vec<String> {
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
fn gate_default() -> Vec<GateClass> {
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

type StatFn = fn(&WorldStats) -> f64;

/// name, extractor, target, weight
/// Reference relative sd for the precision factor below: a weight of `judgment` means "as
/// measurable as a target whose single-history sd is 20% of its anchor" — near the median of the
/// measured set, and chosen so the weights SUM to about what the equal-precision objective's did
/// (12.2 against 12.5), which keeps the 0.5-per-failed-gate penalty at its established bite.
const SD_REL_REF: f64 = 0.20;

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
/// The realism bands are not here either. `equity vol 8-40%` and `kurtosis 4-30` say "is this a
/// market at all", and a Nasdaq is still a market. The two FIDELITY bands are, because they say "is
/// this THIS market".
#[derive(Clone, Copy)]
struct Anchors {
    name: &'static str,
    equity_window: &'static str,
    equity_years: usize,
    cluster_window: &'static str,
    cluster_years: usize,
    vol: f64,
    vol_sd: f64,
    ret_vol: f64,
    ret_vol_sd: f64,
    kurt: f64,
    kurt_sd: f64,
    ac1: f64,
    ac1_sd: f64,
    ac20: f64,
    ac20_sd: f64,
    crashes: f64,
    crashes_sd: f64,
    med_depth: f64,
    med_depth_sd: f64,
    worst_depth: f64,
    worst_depth_sd: f64,
    vol_band: (f64, f64),
    ret_vol_band: (f64, f64),
}

/// The S&P/CRSP set, unchanged from every release before 0.21.0 — the values and sampling spreads
/// are the ones that were already here, moved rather than re-measured, so the default reproduces
/// earlier output byte for byte.
const SP500_ANCHORS: Anchors = Anchors {
    name: "S&P 500 / CRSP",
    equity_window: "S&P / CRSP 1954-2026",
    equity_years: 72,
    cluster_window: "CRSP 1926-2026, the century",
    cluster_years: 100,
    vol: 16.0,
    vol_sd: 0.10,
    ret_vol: 0.69,
    ret_vol_sd: 0.20,
    kurt: 28.0,
    kurt_sd: 2.65,
    ac1: 0.299,
    ac1_sd: 0.09,
    ac20: 0.225,
    ac20_sd: 0.11,
    crashes: 20.7,
    crashes_sd: 0.22,
    med_depth: -27.1,
    med_depth_sd: 0.15,
    worst_depth: -56.8,
    worst_depth_sd: 0.17,
    vol_band: (14.0, 18.0),
    ret_vol_band: (0.50, 0.85),
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
/// THE SAMPLING SPREADS ARE THE S&P'S, CARRIED OVER, and that is the one soft number here. An sdRel
/// is model-implied, so an honest Nasdaq set needs `-noise -anchors nasdaq` run at a Nasdaq-
/// calibrated world, which does not exist yet. Re-freeze them when one is. The two fidelity bands
/// are likewise the S&P bands' proportional widths around the Nasdaq levels.
const NASDAQ_ANCHORS: Anchors = Anchors {
    name: "Nasdaq-100 / QQQ",
    equity_window: "QQQ 1999-2026",
    equity_years: 27,
    cluster_window: "QQQ 1999-2026",
    cluster_years: 27,
    vol: 26.90,
    vol_sd: 0.10,
    ret_vol: 0.38,
    ret_vol_sd: 0.20,
    kurt: 9.55,
    kurt_sd: 2.65,
    ac1: 0.293,
    ac1_sd: 0.09,
    ac20: 0.249,
    ac20_sd: 0.11,
    crashes: 25.6,
    crashes_sd: 0.22,
    med_depth: -22.8,
    med_depth_sd: 0.15,
    worst_depth: -83.0,
    worst_depth_sd: 0.17,
    vol_band: (23.5, 30.3),
    ret_vol_band: (0.27, 0.47),
};

fn anchors_named(spec: &str) -> Anchors {
    match spec {
        "sp500" | "sp" | "spx" => SP500_ANCHORS,
        "nasdaq" | "ndx" | "qqq" => NASDAQ_ANCHORS,
        other => cli_die(&format!("unknown -anchors [{other}]; use sp500 or nasdaq")),
    }
}

fn wgt(judgment: f64, sd_rel: f64) -> f64 {
    judgment * (SD_REL_REF / sd_rel)
}

fn fit_targets(a: Anchors) -> Vec<(&'static str, StatFn, f64, f64)> {
    vec![
        (
            "equity vol %",
            (|st| st.vol * 100.0) as StatFn,
            a.vol,
            wgt(1.0, a.vol_sd),
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
        (
            "crashes/century",
            (|st: &WorldStats| st.ep_per_path * 100.0 / st.years_per_path) as StatFn,
            a.crashes,
            wgt(1.0, a.crashes_sd),
        ),
        (
            "median depth %",
            (|st| st.depth_med) as StatFn,
            a.med_depth,
            wgt(1.0, a.med_depth_sd),
        ),
        // Judgment 0.5, DOWN from 1.0, on `-noise`'s finding: graded at 100 years against a
        // 72-year anchor this ratio is mostly a max-order-statistic horizon artifact (at the
        // anchor's own horizon the record sits at the model's 61st percentile). Until the target
        // is horizon-matched, weighting it fully would push `-calibrate` to close an artifact.
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
            wgt(1.0, 0.51),
        ),
        (
            "bond growth-crash",
            (|st| st.bond_growth) as StatFn,
            20.0,
            wgt(1.0, 0.39),
        ),
        // The judgment stays at 1.5 — inflation-crash behaviour is why the bond refuge exists —
        // and the measured precision crushes the weight to ~0.13 anyway: sd/real 2.89, and only
        // 95 of 200 24-year histories produce a reading at all. The old 1.5 was the largest
        // weight in the loss on the least measurable target in the set.
        (
            "bond infl-crash",
            (|st| st.bond_infl) as StatFn,
            -25.0,
            wgt(1.5, 2.89),
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
            wgt(0.5, 0.13),
        ),
        (
            "equity d10 vs real",
            (|st: &WorldStats| st.eq_d10_vs_real()) as StatFn,
            1.00,
            wgt(1.0, 0.25),
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
            wgt(0.5, 1.56),
        ),
        (
            "bond depth vs vol",
            (|st: &WorldStats| st.bond_depth_vs_vol()) as StatFn,
            1.00,
            wgt(0.5, 0.33),
        ),
    ]
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
fn fitness(a: Anchors, st: &WorldStats) -> (f64, Vec<(&'static str, f64, f64, f64)>) {
    let rows: Vec<(&'static str, f64, f64, f64)> = fit_targets(a)
        .into_iter()
        .map(|(name, get, target, weight)| {
            let m = get(st);
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

fn sim_paths(w: &World, paths: usize, years: usize, seed: u64) -> Vec<Path> {
    sim_path_range(w, 0, paths, years, seed)
}

/// Paths `from..from + count`. Path k is a function of (world, years, seed, k) alone, so a range
/// taken from the middle is byte-identical to the same indices of a run that started at zero —
/// which is what lets `-emitfrom` split one batch across invocations.
fn sim_path_range(w: &World, from: usize, count: usize, years: usize, seed: u64) -> Vec<Path> {
    (from..from + count)
        .into_par_iter()
        .map(|k| simulate(w, years, seed.wrapping_add(k as u64 * 7919)))
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
        out.push((
            "reflexive: crowd runs a vol rule",
            with(|w| w.crowd = Crowd::VolScaled),
            true,
        ));
        // 0.12 is the stress case: admissible, where 0.25 fails the gate.
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

type Setter = fn(&mut World, f64);

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
// Referenced only from the test module below; the search reads its own ranges, never this list.
#[cfg_attr(
    not(test),
    expect(
        dead_code,
        reason = "the rule is read by the tests, never by the search"
    )
)]
const IDENTITY_PARAMS: &[&str] = &["duration"];

/// What `-calibrate` samples, and the ONLY place a searchable parameter is declared. A function
/// rather than an inline `vec!` so the identity-parameter rule above can be tested against it.
fn calibrate_ranges() -> Vec<(&'static str, f64, f64, Setter)> {
    vec![
        ("depth", 10.0, 26.0, |w, x| w.depth = x),
        ("trendShare", 0.05, 0.70, |w, x| w.trend_share = x),
        ("drift", 0.06, 0.16, |w, x| w.drift = x),
        // The depth profile's second axis, and the one no sweep could reach before 0.21: the value
        // channel passes only a few percent of a fundamental move into any one session, so
        // fundamental variance accumulates into time under water without moving daily return scale.
        // It is in the search only now that the depth targets are stated against a real relation —
        // against SPY's absolute levels a search free to raise it would have closed them by making
        // the fundamental hotter still, which is how the world it replaces was reached.
        ("fundVol", 0.03, 0.16, |w, x| w.fund_vol = x),
        ("crowdImpact", 0.01, 0.20, |w, x| w.crowd_impact = x),
        ("stress", 2.0, 6.0, |w, x| w.stress = x),
        // Widened from 0.010-0.035 in 0.21.0: with the recovery drag the base pull governs
        // SHALLOW water only, so its useful range moved up. The old ceiling would have excluded the
        // shipped value, which is the `fund_vol` failure mode — a search that cannot reach the
        // answer.
        ("valuePull", 0.010, 0.070, |w, x| w.value_pull = x),
        // Both in the ranges from the release they arrive in, for the same reason.
        ("recoveryDrag", 0.0, 20.0, |w, x| w.recovery_drag = x),
        ("recoveryFloor", 0.05, 1.0, |w, x| w.recovery_floor = x),
        ("volOfVol", 0.012, 0.030, |w, x| w.vol_of_vol = x),
        // In the ranges from the release it arrived in. `fund_vol` sat outside them for four
        // releases and that is exactly why its defect survived four releases of one-knob-at-a-time
        // sweeps; a mechanism the search cannot reach is a mechanism nobody will find the wrong
        // value of.
        ("jumpVar", 0.00, 0.20, |w, x| w.jump_var = x),
        ("jumpRate", 0.0004, 0.0040, |w, x| w.jump_rate = x),
        ("easing", 0.0, 0.09, |w, x| w.easing = x),
        ("refuge", 0.0, 0.20, |w, x| w.refuge = x),
        ("inflSize", 0.03, 0.12, |w, x| w.infl_size = x),
        ("discount", 3.0, 10.0, |w, x| w.discount = x),
        ("margin", 0.0, 0.004, |w, x| w.margin = x),
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
    let score =
        |w: &World, s: u64| -> f64 { fitness(a, &measure(&sim_paths(w, 50, 100, s), 100)).0 };
    eprintln!(
        "calibrate: {n_samples} samples, 50 paths x 100 years each; holdout re-score of top 5"
    );
    let mut scored: Vec<(f64, World, String)> = (0..n_samples)
        .map(|k| {
            let mut w = *base;
            let mut desc: Vec<String> = Vec::new();
            for (nm, lo, hi, set) in &ranges {
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
            "  inflation {}%/yr   eq vol {}%  kurt {}  clus {}/{}  crashes/path {}  depth {}%  censored {}  trend share {}  clamp {}%",
            jf(st.infl_ann, 0, 1),
            jf(st.vol * 100.0, 0, 1),
            jf(st.kurt, 0, 1),
            jf(st.ac1, 0, 2),
            jf(st.ac20, 0, 2),
            jf(st.ep_per_path, 0, 1),
            jf(st.depth_med, 0, 1),
            st.censored,
            jf(st.trend_share, 0, 2),
            jf(st.clamp_pct, 0, 3)
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
const EQUITY_TARGETS: [&str; 11] = [
    "equity vol %",
    "return per vol",
    "kurtosis",
    "clustering lag 1",
    "clustering lag 20",
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
const BOND_TARGETS: [&str; 4] = [
    "bond vol % (24y)",
    "bond growth-crash",
    "bond infl-crash",
    "bond depth vs vol",
];

/// Bisection bracket for the depth solve, and how many halvings. Ten steps over this bracket
/// leaves the depth uncertain by 16/1024 ~ 0.016, worth about 0.02 points of volatility — far
/// inside the sampling noise of any ensemble that could be run here. Each step is a full ensemble,
/// so this is the cost knob: twelve ensembles in total, including the two bracket probes.
const DEPTH_BRACKET: (f64, f64) = (10.0, 26.0);
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
        println!(
            "  {name:<22}{:>10}{:>11}{:>10}{:>11}{:>11}   {flag}",
            jf(d, 0, 2),
            jf(a, 0, 2),
            jf(want, 0, 2),
            jf(rd, 0, 2),
            jf(ra, 0, 2)
        );
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
fn anchor_groups(a: Anchors) -> [(&'static str, usize, &'static [&'static str]); 4] {
    [
        (
            a.equity_window,
            a.equity_years,
            &[
                "equity vol %",
                "return per vol",
                "kurtosis",
                "crashes/century",
                "median depth %",
                "worst crash %",
            ],
        ),
        (
            a.cluster_window,
            a.cluster_years,
            &["clustering lag 1", "clustering lag 20"],
        ),
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
        "where the model is known biased (clustering 1.06x real) the spread is biased with it."
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
    println!("they are not.  `p50` vs `real` is the HORIZON-MATCHED reading; -fitness grades a");
    println!(
        "100-year model reading against these mixed-horizon anchors, so its ratios fold a horizon"
    );
    println!("artifact into targets like worst crash %.");
    for (label, years, targets) in anchor_groups(a) {
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
                fit_targets(a).into_iter().find(|(n, _, _, _)| n == name)
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
            let below = xs.iter().filter(|x| **x <= want).count();
            let ps = format!("{}%", 100 * below / n);
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
struct DdEpisode {
    depth: f64,
    decline: usize,
    recovery: Option<usize>,
    underwater: usize,
    worst_day_share: f64,
}

/// Peak-to-trough-to-recovery episodes deeper than `threshold`. An episode still underwater at the
/// end is CENSORED: its depth and decline count, its recovery does not.
///
/// `worst_day_share` is the fraction of the peak-to-trough LOG decline delivered by its single
/// worst session — low means the decline ground down, high means it gapped. The leg starts at the
/// session BEFORE the first underwater bar, because that is the session the fall began on.
fn dd_episodes(px: &[f64], threshold: f64) -> Vec<DdEpisode> {
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
type DdRefRow = (f64, usize, f64, f64, usize, usize, usize, f64);

/// SPY total return, 1993-01-29..2026-08-26, measured with `dd_episodes` above. ONE history, and
/// the episode counts are printed so nobody reads a median of four as a population value.
const DD_REAL_SPY: [DdRefRow; 2] = [
    (0.10, 12, 0.36, -18.9, 50, 67, 125, 0.286),
    (0.20, 4, 0.12, -40.6, 275, 582, 856, 0.144),
];

fn run_drawdown_shape(paths: usize, years: usize, seed: u64, base: &World) {
    eprintln!("{paths} paths x {years} years");
    let sims = sim_paths(base, paths, years, seed);
    let p_yrs = sims.len() as f64 * years as f64;
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
        "Reference: SPY total return 1993-01-29..2026-08-26, ONE history — 12 episodes at the"
    );
    println!(
        "10% threshold, 4 at the 20%.  NOTHING HERE IS GATED; a band off four episodes could not"
    );
    println!("fail.  The ratios are for reading, not for passing.");
    println!();
    println!(
        "  {:<10} {:>4} {:>5} {:>7} {:>8} {:>8} {:>9} {:>9} {:>10}",
        "series", "thr", "eps", "eps/yr", "depth", "decline", "recovery", "underwtr", "worst-day"
    );
    for (thr, r_eps, r_yr, r_depth, r_decl, r_recov, r_undw, r_wds) in DD_REAL_SPY {
        let eps: Vec<DdEpisode> = sims
            .iter()
            .flat_map(|p| dd_episodes(&p.price, thr))
            .collect();
        let med = |f: &dyn Fn(&DdEpisode) -> f64| {
            let v: Vec<f64> = eps.iter().map(f).collect();
            pctile(&v, 0.5)
        };
        let recov: Vec<f64> = eps
            .iter()
            .filter_map(|e| e.recovery.map(|r| r as f64))
            .collect();
        let pct = (thr * 100.0) as usize;
        let m_depth = med(&|e| e.depth) * 100.0;
        let m_decl = med(&|e| e.decline as f64);
        let m_recov = pctile(&recov, 0.5);
        let m_undw = med(&|e| e.underwater as f64);
        let m_wds = med(&|e| e.worst_day_share);
        println!(
            "  {:<10} {:>3}% {:>5} {} {}% {:>8} {:>9} {:>9} {}%",
            "real SPY",
            pct,
            r_eps,
            jf(r_yr, 7, 2),
            jf(r_depth, 7, 1),
            r_decl,
            r_recov,
            r_undw,
            jf(r_wds * 100.0, 9, 1)
        );
        println!(
            "  {:<10} {:>3}% {:>5} {} {}% {} {} {} {}%",
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
            "  {:<10} {:>3}% {:>5} {} {} {} {} {} {}",
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
    println!("  A LOW worst-day ratio means the model's declines GRIND where the real one GAPPED.");
    println!(
        "  Read it beside the decline column: a decline taking twice as long dilutes its worst"
    );
    println!("  session by construction, so the two move together.  Daily KURTOSIS is not the");
    println!(
        "  explanation -- it has sat on its anchor since 0.21.0 while this ratio barely moved."
    );
    println!();
    println!(
        "  Medians here are `pctile(.., 0.5)`: the lower of the two middle elements on an even"
    );
    println!(
        "  count, where NumPy averages them.  A consumer reproducing this can land one element"
    );
    println!("  away on a duration and be right.");
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
    }
}

/// Session dates. An empty `start_ymd` keeps the historical synthetic calendar: 1900-01-02
/// stepping 365/252 days, which lands on weekends and so can never be joined to a real dated
/// series. A date instead steps by WEEKDAYS (no holiday calendar — recorded, not hidden), which
/// is what lets an emitted path through a normal dated loader untouched.
fn session_dates(n: usize, start_ymd: &str) -> Vec<String> {
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

/// The TSV and its sidecar. `gate_st` is measured on the gate ensemble, which is a different
/// and usually much larger sample than the one path being written.
#[expect(
    clippy::too_many_arguments,
    reason = "the sidecar records the whole provenance tuple; grouping it would only move the list"
)]
fn write_emitted(
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
) {
    let dates = session_dates(p.price.len(), start_ymd);
    write_emit_tsv(file, p, &dates);
    write_emit_sidecar(
        a, file, p, k, w, years, seed, start_ymd, &dates, gate_st, gate_paths,
    );
}

fn write_emit_tsv(file: &str, p: &Path, dates: &[String]) {
    let mut tsv = String::new();
    tsv.push_str(&EMIT_COLUMNS.join("\t"));
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
        tsv.push('\n');
    }
    write_or_die(file, &tsv);
}

/// Every `World` field, in declaration order, as the indented body of a JSON object. A world
/// that reaches a consumer without its parameters cannot be re-simulated.
fn world_json_body(w: &World) -> Vec<String> {
    let fields: Vec<(&str, String)> = vec![
        ("trendShare", ef(w.trend_share)),
        ("depth", ef(w.depth)),
        ("stress", ef(w.stress)),
        ("beta", ef(w.beta)),
        ("drift", ef(w.drift)),
        ("fundVol", ef(w.fund_vol)),
        ("rateMean", ef(w.rate_mean)),
        ("volPersist", ef(w.vol_persist)),
        ("volOfVol", ef(w.vol_of_vol)),
        ("jumpVar", ef(w.jump_var)),
        ("jumpRate", ef(w.jump_rate)),
        ("valuePull", ef(w.value_pull)),
        ("recoveryDrag", ef(w.recovery_drag)),
        ("recoveryFloor", ef(w.recovery_floor)),
        ("crowd", json_str(&crowd_name(w.crowd))),
        ("crowdImpact", ef(w.crowd_impact)),
        ("panic", ef(w.panic)),
        ("duration", ef(w.duration)),
        ("easing", ef(w.easing)),
        ("unwind", ef(w.unwind)),
        ("refuge", ef(w.refuge)),
        ("inflProb", ef(w.infl_prob)),
        ("inflSize", ef(w.infl_size)),
        ("inflSpeed", ef(w.infl_speed)),
        ("rateSpeed", ef(w.rate_speed)),
        ("discount", ef(w.discount)),
        ("margin", ef(w.margin)),
    ];
    fields
        .iter()
        .map(|(nm, v)| format!("    {}: {v}", json_str(nm)))
        .collect()
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
#[expect(
    clippy::manual_range_contains,
    reason = "the MISS flag must leave a NaN ratio UNflagged, as `ratio > 1.5 || ratio < 0.667` \
              does; `!(0.667..=1.5).contains(&ratio)` would flag it"
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
) {
    let n = p.price.len();
    let realism_bad = failed_in(a, gate_st, GateClass::Realism);
    let mechanism_bad = failed_in(a, gate_st, GateClass::Mechanism);
    let fidelity_bad = failed_in(a, gate_st, GateClass::Fidelity);
    fn str_list<S: AsRef<str>>(v: &[S]) -> String {
        let items: Vec<String> = v.iter().map(|s| json_str(s.as_ref())).collect();
        format!("[{}]", items.join(", "))
    }
    let num = |x: f64| -> String {
        if x.is_nan() {
            "null".to_string()
        } else {
            ef(x)
        }
    };
    let fidelity: Vec<String> = fit_targets(a)
        .into_iter()
        .map(|(nm, get, want, _)| {
            let got = get(gate_st);
            let ratio = if want == 0.0 { f64::NAN } else { got / want };
            let miss = ratio > 1.5 || ratio < 0.667;
            format!(
                "    {{ \"name\": {}, \"model\": {}, \"real\": {}, \"ratio\": {}, \"miss\": {} }}",
                json_str(nm),
                num(got),
                num(want),
                num(ratio),
                miss
            )
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
        format!("  \"columns\": {},", str_list(&EMIT_COLUMNS)),
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
        format!("    \"ensembleYears\": {years},"),
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
            "    \"fidelityUnanchored\": {}",
            str_list(&unanchored_in(gate_st))
        ),
        "  },".to_string(),
        "  \"fidelity\": [".to_string(),
        fidelity.join(",\n"),
        "  ]".to_string(),
        "}".to_string(),
    ];
    write_or_die(&sidecar_name(file), &format!("{}\n", json.join("\n")));
}

#[expect(
    clippy::too_many_lines,
    reason = "one linear report, mirroring the Scala twin's main statement for statement"
)]
#[expect(
    clippy::manual_range_contains,
    reason = "the MISS flag must leave a NaN ratio UNflagged, as `ratio > 1.5 || ratio < 0.667` \
              does; `!(0.667..=1.5).contains(&ratio)` would flag it"
)]
#[expect(
    clippy::cognitive_complexity,
    reason = "one linear dispatch over the CLI, as in the Scala twin"
)]
fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();

    let mut paths = 200usize;
    let mut years = 100usize;
    let mut seed = 20_260_813u64;
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
    // TWO KNOWN BIAS DIRECTIONS, netted away nowhere else: worst crash at 1.61 puts index paths
    // near -92% against a real -56.8%, which no levered fund survives, so ruin rates for levered
    // sleeves are UPPER BOUNDS, not estimates; and the DEEP drawdown rung runs long at 1.78, which
    // is what the drag costs -- a slower climb out of a deep hole is more time deep.  Rules keyed
    // to a deep distance from peak inherit that; the shallow rungs read 0.94 and 1.03.
    //
    // That last one is the price of the depth rungs and it is structural, not a tuning miss:
    // every world that puts time under water near the real relation lands median crash depth at
    // 0.78-0.85, because cooling the fundamental makes drawdowns shorter AND shallower together.
    // Measured across the global search's winner, the local optimum and six hand variants
    // including `stress` at its range ceiling; nothing available separates them. Closing it needs
    // a channel that deepens a crash without adding low-frequency variance — the same absent
    // valuation cycle the kurtosis note above blames for there being no second tail channel.
    // Seeded from `default_world()`, never restated. A second copy of the shipped world here is
    // the failure that function's own docstring claims not to have: it would drift silently,
    // because the only thing comparing the two is a `-releases` run noticing that its 0.19.2 row
    // and its `current` row disagree.
    let dw = default_world();
    let mut trend_share = dw.trend_share;
    let mut depth = dw.depth;
    let mut stress = dw.stress;
    let mut beta = dw.beta;
    let mut vol_persist = dw.vol_persist;
    let mut vol_of_vol = dw.vol_of_vol;
    let mut anchor_spec = "sp500".to_string();
    let mut recovery_drag = dw.recovery_drag;
    let mut recovery_floor = dw.recovery_floor;
    let mut jump_var = dw.jump_var;
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
            "-paths" => paths = req_usize(&mut it, "-paths"),
            "-years" => years = req_usize(&mut it, "-years"),
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
            "-recoverydrag" => recovery_drag = req_f64(&mut it, "-recoverydrag"),
            "-recoveryfloor" => recovery_floor = req_f64(&mut it, "-recoveryfloor"),
            "-jumpvar" => jump_var = req_f64(&mut it, "-jumpvar"),
            "-jumprate" => jump_rate = req_f64(&mut it, "-jumprate"),
            "-value" => value_pull = req_f64(&mut it, "-value"),
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

    let crowd = match crowd_name.to_lowercase().as_str() {
        "momentum" => Crowd::Momentum,
        "volscaled" => Crowd::VolScaled,
        t if t.starts_with("trend") => match t[5..].parse::<i32>() {
            Ok(d) if d > 0 => Crowd::Trend(d),
            _ => cli_die(&format!(
                "unknown -crowd [{crowd_name}]; use momentum, trendNNN, or volscaled"
            )),
        },
        _ => cli_die(&format!(
            "unknown -crowd [{crowd_name}]; use momentum, trendNNN, or volscaled"
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
        jump_var,
        jump_rate,
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

    if calibrate_n > 0 {
        calibrate(anchors, calibrate_n, &w, seed);
        return;
    }
    if fitness_only {
        let st = measure(&sim_paths(&w, 60, 80, seed), 80);
        let (loss, rows) = fitness(anchors, &st);
        println!(
            "fitness loss {}  (lower is better; includes 0.5 per failed gate check)",
            jf(loss, 0, 3)
        );
        for (n, m, t, term) in rows {
            println!(
                "  {n:<22} model {}   target {}   term {}",
                jf(m, 8, 2),
                jf(t, 8, 2),
                jf(term, 6, 3)
            );
        }
        for (n, ok, _) in gate_checks(anchors, &st) {
            if !ok {
                println!("  FAILED GATE: {n}  (+0.500)");
            }
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
        run_drawdown_shape(paths, years, seed, &w);
        return;
    }
    if buffer_report {
        run_buffer_report(anchors, paths, years, seed, cost, single, &w, &gate_req);
        return;
    }

    eprintln!("simulating {paths} paths x {years} years");
    let sims = sim_paths(&w, paths, years, seed);
    let st = measure(&sims, years);

    if !emit.is_empty() {
        // The verdict is a property of the WORLD, so it is measured on an ensemble large enough
        // for the conditional mechanism statistics to exist. Judging the world by the one path
        // being written made every short export raise all four mechanism failures — a
        // guaranteed false alarm, which trains a consumer to ignore the warning entirely.
        let (gate_st, gate_paths) = if emit_gate > paths {
            (
                measure(&sim_paths(&w, emit_gate, years, seed), years),
                emit_gate,
            )
        } else {
            (st, paths)
        };
        let realism_bad = failed_in(anchors, &gate_st, GateClass::Realism);
        let mechanism_bad = failed_in(anchors, &gate_st, GateClass::Mechanism);
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
        let fidelity_bad = failed_in(anchors, &gate_st, GateClass::Fidelity);
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
                    write_emitted(
                        anchors,
                        &f,
                        &batch[k - emit_from],
                        k,
                        &w,
                        years,
                        seed,
                        &emit_start,
                        &gate_st,
                        gate_paths,
                    );
                    f
                })
                .collect()
        } else {
            let p = path_at(emit_path);
            write_emitted(
                anchors,
                &emit,
                &p,
                emit_path,
                &w,
                years,
                seed,
                &emit_start,
                &gate_st,
                gate_paths,
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
            EMIT_COLUMNS.len(),
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
        "  volatility clustering  lag  1 {}   lag 20 {}",
        jf(st.ac1, 6, 3),
        jf(st.ac20, 6, 3)
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
        "  binding diagnostics    trend share {} (pinned {}%, target saturated {}%)   bond spiral {}% of sessions   clamped {}%",
        jf(st.trend_share, 0, 2),
        jf(st.trend_pinned * 100.0, 0, 1),
        jf(st.target_sat * 100.0, 0, 1),
        jf(st.pct_bond_stress * 100.0, 0, 1),
        jf(st.clamp_pct, 0, 3)
    );
    println!(
        "                         crowd flow {} bp/session ({}% of the noise term) — the reflexive channel",
        jf(st.crowd_flow * 1e4, 0, 2),
        jf(st.crowd_flow / SIGMA_N * 100.0, 0, 1)
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
    println!(
        "    equity {}   |   depth rungs 35 equity funds 2001-2026, vs each world's",
        anchors.equity_window
    );
    println!("      OWN volatility and return   |   return per vol CRSP 1954-2026");
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
    for (n, get, want, _) in fit_targets(anchors) {
        let got = get(&st);
        let ratio = if want != 0.0 { got / want } else { f64::NAN };
        let flag = if ratio > 1.5 || ratio < 0.667 {
            "  <-- MISS"
        } else {
            ""
        };
        println!(
            "     {:<22} model {}   real {}   ratio {}{}",
            n,
            jf(got, 8, 2),
            jf(want, 8, 2),
            jf(ratio, 5, 2),
            flag
        );
    }

    if validate {
        let checks = gate_checks(anchors, &st);
        let bad: Vec<Vec<String>> = GateClass::ALL
            .iter()
            .map(|c| failed_in(anchors, &st, *c))
            .collect();
        let verdict = |i: usize| {
            if bad[i].is_empty() { "PASS" } else { "FAIL" }
        };
        println!();
        println!("  acceptance gate:");
        let una = unanchored_in(&st);
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
        write_emitted(SP500_ANCHORS, &tsv, &p, 0, &w, years, seed, "", &st, 1);
        let json = sidecar_name(&tsv);
        let text = std::fs::read_to_string(&json).expect("sidecar written");
        std::fs::remove_file(&tsv).ok();
        std::fs::remove_file(&json).ok();
        std::fs::remove_dir(&dir).ok();
        text.lines().map(str::to_string).collect()
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
        let a = SP500_ANCHORS;
        assert_eq!(a.vol, 16.0);
        assert_eq!(a.ret_vol, 0.69);
        assert_eq!(a.kurt, 28.0);
        assert_eq!(a.ac1, 0.299);
        assert_eq!(a.ac20, 0.225);
        assert_eq!(a.crashes, 20.7);
        assert_eq!(a.med_depth, -27.1);
        assert_eq!(a.worst_depth, -56.8);
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

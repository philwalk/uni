//! One half of the cross-language demo pair; `jsrc/marketSim.sc` is the other.
//!
//! Unlike the other pairs, this one is not a tour of an API — it is a real workload that
//! happens to exercise `NumPyRng`, `cli` and `MatD` together at scale (200 paths × 100
//! years), and it is the consumer that drove Tier 3 milestone 1. `-emit` and `-validate`
//! are byte-identical between the two languages.
//!
//! Every mode is ported: the price-formation core (`World`, `Market`, `simulate`), the
//! measurement layer — stylised-fact statistics, drawdown episodes, the two-class
//! acceptance gate and the calibration loss — the exposure rules and grading statistics,
//! and the `-emit`/`-validate`/`-strategies`/`-power`/`-buffer`/`-fitness`/`-calibrate`
//! reports. `-emit` writes a TSV and a JSON sidecar, and both are byte-identical too.
//!
//! Run: `cargo run --release --example market_sim -- -validate`
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

const DAYS_PER_YEAR: usize = 252;

/// Sessions discarded so paths start from the stationary distribution (slowest state ~600).
const BURN_IN: usize = 756;

/// Treasuries incorporate rate news SAME-DAY — at 0.05 the bond market smeared a fair-value
/// move over ~20 sessions, which crushed the daily stock-bond correlation and halved every
/// crash-window bond response. 0.7 = near-immediate tracking, with flows and the spiral
/// acting as short-lived deviations on top, which is what bond-market dysfunction is.
const K_VALUE_BOND: f64 = 0.7;
const SIGMA_N_BOND: f64 = 0.002;
/// Equity idiosyncratic noise, ~11% annualised alone. Top-level beside its bond counterpart so
/// the crowd-flow diagnostic can state the reflexive channel as a share of it.
const SIGMA_N: f64 = 0.007;
/// `crowdImpact` at which the momentum crowd reproduces the frozen `k_trend` exactly. The ratio
/// is what enters the flow, so the default divides to a bit-exact 1.0 and the shipped world is
/// unchanged; every other setting scales the reflexive channel that used to have no dial at all.
const CROWD_IMPACT_REF: f64 = 0.06;

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
    value_pull: f64,
    crowd: Crowd,
    crowd_impact: f64,
    panic: f64,
    /// bond duration: sensitivity of its fair value to the rate
    duration: f64,
    /// policy/flight rate response to equity stress (inflation-suppressed)
    flight: f64,
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
    mean_crowd_flow: f64,
}

/// ONE price-formation mechanism for every traded asset: value demand toward `fair`, plus
/// external flow and noise, amplified when THIS market's liquidity has withdrawn after
/// one-sided selling (measured against a slowly-adapting scale, so symmetric turbulence of
/// any size leaves the index flat — E[max(0,-z)] = 0.399 regardless of scale).
struct Market {
    k_value: f64,
    stress_k: f64,
    impact: f64,
    log_p: f64,
    stress_idx: f64,
    last_liq: f64,
    clamps: usize,
    scale_var: f64,
}

impl Market {
    fn new(k_value: f64, stress_k: f64, impact: f64) -> Self {
        Self {
            k_value,
            stress_k,
            impact,
            log_p: 0.0,
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
        let raw = (self.k_value * (fair - self.log_p) + flow_plus_noise * amp) * self.impact;
        // Numerical guard ONLY, and verified to be exactly that: at ±0.25 vs ±0.50 every
        // statistic in every gate-passing world is BIT-IDENTICAL (the clamp consumes no
        // draws and never binds there). It sits at ±0.50, far from any plausible daily move
        // (worst real S&P day ~ -23% log), and the gate rejects any world where it engages.
        let ret = (-0.50f64).max(0.50f64.min(raw));
        if ret != raw {
            self.clamps += 1;
        }
        self.log_p += ret;
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

    let mut eq_m = Market::new(w.value_pull, w.stress, 12.0 / w.depth);
    let mut bd_m = Market::new(K_VALUE_BOND, w.stress, 1.0);

    let mut log_vbase = 0.0f64;
    let mut rate = w.rate_mean;
    let mut infl_press = 0.0f64;
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
        // policy: chase rateMean+pressure; cut on equity stress UNLESS inflation ties its hands
        let flight_cut = w.flight * eq_m.stress_idx * (-infl_press / 0.005).exp();
        let r_old = rate;
        // rate UNCERTAINTY rises with inflation pressure (2022: MOVE elevated all year). This
        // is what makes stocks and bonds co-move in an inflation regime: both are priced off
        // the same rate, so more rate news = more shared-factor variance = the correlation flip.
        rate = 0.0f64.max(
            rate + w.rate_speed * ((w.rate_mean + infl_press) - rate) * dt - flight_cut * dt
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

        // ---- both markets step through the SAME mechanism ---------------------------------
        let ret_e = eq_m.step(log_vbase, eq_flow + d_noise);
        // joint-stress margin selling: when both markets are stressed, the bond gets dumped too
        let bond_flow = -w.margin * eq_m.stress_idx * bd_m.stress_idx;
        let _ret_b = bd_m.step(fair_b, bond_flow + SIGMA_N_BOND * rng.randn());

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

impl WorldStats {
    /// Return per unit volatility, in the units this report already prints: `ann_ret` is a LOG
    /// return in %/yr and `vol` is a fraction. An arithmetic-mean anchor is higher by about
    /// sigma/2 (0.08 at 16% vol) and has to be restated before it can be compared with this.
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
        bond_vol: med(&sims
            .iter()
            .map(|s| (MatD::apply(&daily_returns(&s.bond)).power(2).mean() * dpy).sqrt())
            .collect::<Vec<f64>>()),
        bond_growth: bond_in_windows(false),
        bond_infl: bond_in_windows(true),
        corr_calm: corr_in(false),
        corr_infl: corr_in(true),
        mean_bond_stress: scala_sum(sims.iter().map(|s| s.mean_bond_stress)) / n_sims,
        pct_bond_stress: scala_sum(sims.iter().map(|s| s.pct_bond_stress)) / n_sims,
        crowd_flow: scala_sum(sims.iter().map(|s| s.mean_crowd_flow)) / n_sims,
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

/// How far a depth share may sit from the real one and still have a readable level. The plan's
/// acceptance for W9 is that "a drawdown-rule arm's %out lands within a few points of the same
/// rule's %out on a real series"; ten percentage points is that, made two-sided and concrete.
/// ABSOLUTE, not relative: the quantity being compared — a rule's share of sessions out of the
/// market — is itself a share, so a point is the same size at every rung.
const DEPTH_TOL: f64 = 0.10;

/// The band is derived from the real anchor here, so the printed name and the predicate cannot
/// drift apart — the failure mode where a gate reads as bounds it does not enforce.
fn depth_check(name: &str, got: f64, real: f64) -> (String, bool, GateClass) {
    let lo = real - DEPTH_TOL;
    let hi = real + DEPTH_TOL;
    (
        format!("{name} {}-{}", jf(lo, 0, 3), jf(hi, 0, 3)),
        got > lo && got < hi,
        GateClass::Fidelity,
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
fn gate_checks(st: &WorldStats) -> Vec<(String, bool, GateClass)> {
    use GateClass::Mechanism;
    use GateClass::Realism;
    let pc = st.ep_per_path * 100.0 / st.years_per_path;
    let n = |s: &str| s.to_string();
    vec![
        (
            n("equity vol 8-25%"),
            st.vol > 0.08 && st.vol < 0.25,
            Realism,
        ),
        (n("kurtosis 4-30"), st.kurt > 4.0 && st.kurt < 30.0, Realism),
        (
            n("clustering 0.10-0.40"),
            st.ac1 > 0.10 && st.ac1 < 0.40 && st.ac20 > 0.03,
            Realism,
        ),
        (
            n("crash rate 8-45/century"),
            st.ep_per_path >= 1.0 && pc >= 8.0 && pc <= 45.0,
            Realism,
        ),
        (
            n("both recovery shapes"),
            st.n_shapes > 0 && st.v_count >= st.n_shapes / 10 && st.u_count >= st.n_shapes / 10,
            Realism,
        ),
        (n("no runaway drift"), st.ann_ret.abs() < 30.0, Realism),
        // 0.02% ~ one clamped session per 20 path-years. The old bound (0.5%) would have
        // passed a world where the clamp was already reshaping kurtosis by a third.
        (n("clamp rarely binds"), st.clamp_pct < 0.02, Realism),
        (
            n("bond vol 7-20%"),
            st.bond_vol > 0.07 && st.bond_vol < 0.20,
            Realism,
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
        (
            n("inflation 1-6%/yr"),
            st.infl_ann > 1.0 && st.infl_ann < 6.0,
            Realism,
        ),
        // LEVEL bands, not realism. A 12%-volatility market is still a market, and realism is
        // ALWAYS required — either band placed there would make the sweep's own OFF-worlds
        // inadmissible in every report ("no liquidity spiral" runs at 12.6% vol, "low growth" at
        // 0.34). Class does not weaken them as a search constraint: the calibration loss counts
        // 0.5 per failed check whatever the class. Volatility keeps its realism band as well —
        // 8-25% answers "is this a market", 14-18% answers "can its level be read".
        (
            n("equity vol 14-18%"),
            st.vol > 0.14 && st.vol < 0.18,
            GateClass::Fidelity,
        ),
        // 0.50 clears the 1926-2026 reading (0.55) downward; 0.85 sits above the 1954-2026 anchor
        // (0.69) and below the most favourable non-overlapping 20-year block the record produced
        // (0.93). A world may be as favourable as a long-horizon market, not as favourable as its
        // luckiest two decades. The 20-year block SPREAD (0.47-0.93) is deliberately NOT the band:
        // that is sampling variation in a 20-year window, and this statistic is a population value
        // over 20,000 path-years — a band drawn from it would readmit worlds at 0.91.
        (
            n("return per vol 0.50-0.85"),
            st.ret_vol() > 0.50 && st.ret_vol() < 0.85,
            GateClass::Fidelity,
        ),
        // Only the rungs with a measured real anchor are gated. The bond's >5% and >20% shares
        // are reported everywhere but targeted nowhere: interpolating them would manufacture an
        // anchor.
        depth_check("equity >5% below peak", st.dd_eq5, 0.447),
        depth_check("equity >10% below peak", st.dd_eq10, 0.315),
        depth_check("equity >20% below peak", st.dd_eq20, 0.169),
        depth_check("bond >10% below peak", st.dd_bd10, 0.510),
    ]
}

fn failed_in(st: &WorldStats, cls: GateClass) -> Vec<String> {
    gate_checks(st)
        .into_iter()
        .filter(|(_, ok, c)| !ok && *c == cls)
        .map(|(n, _, _)| n)
        .collect()
}

/// Admissibility under the classes a report has declared it requires. A class not required is
/// a class whose failures are disclosed and tolerated, which is the whole point of the split.
fn gate_ok(st: &WorldStats, required: &[GateClass]) -> bool {
    gate_checks(st)
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
fn fit_targets() -> Vec<(&'static str, StatFn, f64, f64)> {
    vec![
        ("equity vol %", (|st| st.vol * 100.0) as StatFn, 16.0, 1.0),
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
            0.69,
            1.0,
        ),
        ("kurtosis", (|st| st.kurt) as StatFn, 28.0, 0.5),
        ("clustering lag 1", (|st| st.ac1) as StatFn, 0.27, 1.0),
        ("clustering lag 20", (|st| st.ac20) as StatFn, 0.20, 0.5),
        (
            "crashes/century",
            (|st: &WorldStats| st.ep_per_path * 100.0 / st.years_per_path) as StatFn,
            20.7,
            1.0,
        ),
        ("median depth %", (|st| st.depth_med) as StatFn, -27.1, 1.0),
        ("worst crash %", (|st| st.worst_depth) as StatFn, -56.8, 1.0),
        (
            "bond vol %",
            (|st| st.bond_vol * 100.0) as StatFn,
            13.0,
            1.0,
        ),
        (
            "bond growth-crash",
            (|st| st.bond_growth) as StatFn,
            20.0,
            1.0,
        ),
        ("bond infl-crash", (|st| st.bond_infl) as StatFn, -25.0, 1.5),
        // DEPTH PROFILE. Real equity anchors are SPY 1993-01-29..2026-08-20 (8447 sessions) — a
        // different window from the 1954-2026 record behind the rows above, and named as such in
        // the report, because this is a TIME SHARE rather than a max order statistic: it is
        // horizon-stable where maximum drawdown is not (measured: the model's >10% share is 0.464
        // at both 20 and 100 years), so the two windows are comparable in a way maxDD's would not
        // be.
        //
        // HORIZON-stable is not WINDOW-stable, and the difference is large enough to matter. The
        // real 10% rung reads 0.269 over 1954-2026, 0.315 over 1993-2026 and 0.386 over 1926-2026.
        // The +-0.10 gate bands span that spread, which is part of why they pass; do not read a
        // passing depth rung as agreement with a particular window.
        //
        // Validated once against a series the calibration never saw (CRSP value-weighted, 33-year
        // windows inside 1954-2026): the model's 0.500 / 0.350 / 0.150 against a real median of
        // 0.451 / 0.291 / 0.151. The 20% rung is essentially exact; the 5% and 10% rungs sit at or
        // just above the top of the real range, i.e. this model still spends 10-30% too long in
        // SHALLOW drawdowns. That is a LEVEL bias and it survives the gate.
        //
        // The bond anchor is a clean iShares TLT total-return series over 24 years, and only
        // the 10% rung of it has been measured. The other two bond rungs are REPORTED, not
        // targeted: filling them in by interpolation would manufacture a calibration anchor out
        // of nothing.
        (
            "equity >5% below pk",
            (|st| st.dd_eq5) as StatFn,
            0.447,
            0.5,
        ),
        (
            "equity >10% below pk",
            (|st| st.dd_eq10) as StatFn,
            0.315,
            1.0,
        ),
        (
            "equity >20% below pk",
            (|st| st.dd_eq20) as StatFn,
            0.169,
            0.5,
        ),
        ("bond >10% below pk", (|st| st.dd_bd10) as StatFn, 0.51, 0.5),
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
fn fitness(st: &WorldStats) -> (f64, Vec<(&'static str, f64, f64, f64)>) {
    let rows: Vec<(&'static str, f64, f64, f64)> = fit_targets()
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
    let gate_penalty = gate_checks(st).iter().filter(|(_, ok, _)| !ok).count() as f64 * 0.5;
    let total: f64 = scala_sum(rows.iter().map(|r| r.3)) + gate_penalty;
    (total, rows)
}

fn sim_paths(w: &World, paths: usize, years: usize, seed: u64) -> Vec<Path> {
    (0..paths)
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
        ("no flight bid", with(|w| w.flight = 0.0), false),
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

fn calibrate(n_samples: usize, base: &World, seed: u64) {
    type Setter = fn(&mut World, f64);
    // depth, trendShare, drift and crowdImpact are in the search because they are the strongest
    // levers on the
    // two defects the eight below cannot reach. depth carries crash frequency (at fixed stress,
    // 12 -> 24 takes it from 35 to 13 per century) but moves volatility in lockstep with it.
    // drift is the ONLY knob that moves the depth profile at constant volatility — which is why
    // it cannot be searched without the return-per-vol band above, or the search buys the depth
    // rungs with a Sharpe no 20-year stretch of the real record produced. Their CLI flags are
    // inert under -calibrate, exactly like the eight below.
    let ranges: Vec<(&str, f64, f64, Setter)> = vec![
        ("depth", 10.0, 26.0, |w, x| w.depth = x),
        ("trendShare", 0.05, 0.70, |w, x| w.trend_share = x),
        ("drift", 0.06, 0.16, |w, x| w.drift = x),
        ("crowdImpact", 0.01, 0.20, |w, x| w.crowd_impact = x),
        ("stress", 2.0, 6.0, |w, x| w.stress = x),
        ("valuePull", 0.010, 0.035, |w, x| w.value_pull = x),
        ("volOfVol", 0.012, 0.030, |w, x| w.vol_of_vol = x),
        ("flight", 0.2, 1.6, |w, x| w.flight = x),
        ("duration", 8.0, 18.0, |w, x| w.duration = x),
        ("inflSize", 0.03, 0.12, |w, x| w.infl_size = x),
        ("discount", 3.0, 10.0, |w, x| w.discount = x),
        ("margin", 0.0, 0.004, |w, x| w.margin = x),
    ];
    // the only RNG in the program that was not already NumPyRng
    let mut sr = NumPyRng::new(seed ^ 0x5ca1_ab1e);
    let train_seed = seed;
    let hold_seed = seed + 7_777_777;
    // scored at 100-year paths: an 80-year protocol missed a worst-crash blowup that only
    // appears at the horizon actually used — tune at the scale you evaluate at
    let score = |w: &World, s: u64| -> f64 { fitness(&measure(&sim_paths(w, 50, 100, s), 100)).0 };
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
            let ok = gate_ok(&st, gate_req);
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
                    let inverts = !chr.is_empty()
                        && (ranks.iter().min().copied().unwrap_or(0) > cmax
                            || ranks.iter().max().copied().unwrap_or(0) < cmin);
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
        let ok_sev = gate_ok(&st, gate_req);
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

#[expect(
    clippy::too_many_lines,
    reason = "one linear report, mirroring the Scala twin statement for statement"
)]
fn run_power_report(
    paths: usize,
    seed: u64,
    cost: f64,
    single: bool,
    base: &World,
    gate_req: &[GateClass],
) {
    // 21 = the traded book's span; 72 = the S&P record used for calibration
    let horizons = [21usize, 40, 72, 100];
    let focus: Vec<Rule> = [
        "volatility-scaled, floor 40%",
        "trend 200d, floor 0%",
        "volatility + trend 200d, floor 0%",
        "cut below -10%, floor 0%",
    ]
    .iter()
    .map(|n| rule_named(n))
    .collect();
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
        let ok = gate_ok(&measure(&sims, l), gate_req);
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
    for l in horizons {
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

#[expect(
    clippy::too_many_lines,
    reason = "one linear report, mirroring the Scala twin statement for statement"
)]
#[expect(
    clippy::too_many_arguments,
    reason = "the parameter list mirrors the Scala twin's, and the twins are diffed"
)]
fn run_buffer_report(
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
        let ok = gate_ok(&measure(&sims, years), gate_req);
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

/// `foo.tsv` -> `foo-007.tsv`, so an ensemble sorts in path order.
fn indexed_name(file: &str, k: usize) -> String {
    let cut = file.rfind('.');
    let sep = file.rfind(['/', '\\']);
    let tag = format!("-{k:03}");
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
        file, p, k, w, years, seed, start_ymd, &dates, gate_st, gate_paths,
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
        ("valuePull", ef(w.value_pull)),
        ("crowd", json_str(&crowd_name(w.crowd))),
        ("crowdImpact", ef(w.crowd_impact)),
        ("panic", ef(w.panic)),
        ("duration", ef(w.duration)),
        ("flight", ef(w.flight)),
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
    let realism_bad = failed_in(gate_st, GateClass::Realism);
    let mechanism_bad = failed_in(gate_st, GateClass::Mechanism);
    let fidelity_bad = failed_in(gate_st, GateClass::Fidelity);
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
    let fidelity: Vec<String> = fit_targets()
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
        "  \"schema\": 1,".to_string(),
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
        format!("    \"fidelityFailed\": {}", str_list(&fidelity_bad)),
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
    let mut emit_start = String::new();
    let mut emit_gate = 200usize;
    let mut gate_req = gate_default();
    let mut validate = false;
    let mut buffer_report = false;
    let mut power_report = false;
    let mut strategies = false;
    let mut single = false;
    let mut cost = 0.0010f64;
    let mut fitness_only = false;
    let mut calibrate_n = 0usize;
    // defaults = a random search against the fitness loss, scored at 100-year paths, lightly
    // rounded. Reachable ONLY because depth, trendShare, drift and crowdImpact are in the search;
    // held fixed, as all four were until 0.19.1, no sample gets here. Loss 3.13-3.57 across five
    // scoring seeds against the pre-0.19.1 defaults' 5.77-6.11.
    //
    // `stress` IS NOT AT THE OBJECTIVE'S MINIMUM, deliberately. The loss minimises at stress 5.9
    // (3.128 against 3.280 here, ~0.13 across five seeds); 5.4 was chosen to cut a REGRESSION the
    // objective does not weigh heavily enough to see. The liquidity spiral is a single amplifier
    // producing volatility, fat tails AND volatility clustering together — `stress` alone moves
    // ac1 from 0.160 at 3.4 to 0.420 at 7.0 — so raising it to fix kurtosis (0.28 -> 0.58) drove
    // clustering from an almost-exact 0.90 to 1.33. At 5.4 the split is: clustering 1.20 (from
    // 1.33), the 10% depth rung 1.06 (from 1.13), crash rate 1.20 (from 1.26), worst crash 1.49
    // (from 1.54, back under the MISS threshold); paid for with kurtosis 0.46 (from 0.58), equity
    // vol 0.92 (from 0.98), return per vol 1.14 (from 1.08), median depth 0.93 (from 0.97) and the
    // 20% rung 0.92 (from 1.02). Do not "optimise" this back to 5.9 without re-reading that trade.
    //
    // KURTOSIS AND CLUSTERING CANNOT BOTH BE RIGHT. stress 7.5 reaches kurtosis 26.4 against a real
    // 28 — and clustering 1.67, failing the realism band. That is the measured reason the kurtosis
    // MISS stands, more precise than "no slow valuation cycle": the cycle is why there is no SECOND
    // channel for tails, not why this one cannot reach them.
    //
    // TWO KNOWN BIAS DIRECTIONS, netted away nowhere else, pointing opposite ways: clustering at
    // 1.20 makes volatility more predictable here than in the record, which flatters any rule that
    // forecasts it; worst crash at 1.49 puts index paths near -84% against a real -56.8%, which no
    // levered fund survives, so ruin rates for levered sleeves are UPPER BOUNDS, not estimates.
    let mut trend_share = 0.06f64;
    let mut depth = 16.3f64;
    let mut stress = 5.4f64;
    let mut beta = 3.0f64;
    let mut vol_persist = 0.99f64;
    let mut vol_of_vol = 0.011f64;
    let mut value_pull = 0.013f64;
    let mut crowd_name = "momentum".to_string();
    let mut crowd_impact = 0.088f64;
    let mut panic_k = 0.0f64;
    let mut drift = 0.117f64;
    let mut rate_mean = 0.042f64;
    let mut duration = 13.5f64;
    let mut flight = 0.48f64;
    let mut infl_prob = 0.20f64;
    let mut infl_size = 0.10f64;
    let mut infl_speed = 0.010f64;
    let mut rate_speed = 3.0f64;
    let mut discount = 3.35f64;
    let mut margin = 0.006f64;
    let mut it = args.iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "-paths" => paths = req_usize(&mut it, "-paths"),
            "-years" => years = req_usize(&mut it, "-years"),
            "-seed" => seed = req_u64(&mut it, "-seed"),
            "-emit" => emit = req_arg(&mut it, "-emit").clone(),
            "-emitpath" => emit_path = req_usize(&mut it, "-emitpath"),
            "-emitall" => emit_all = true,
            "-emitstart" => emit_start = req_arg(&mut it, "-emitstart").clone(),
            "-emitgate" => emit_gate = req_usize(&mut it, "-emitgate"),
            "-gate" => gate_req = parse_gate(req_arg(&mut it, "-gate")),
            "-validate" => validate = true,
            "-buffer" => buffer_report = true,
            "-power" => power_report = true,
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
            "-value" => value_pull = req_f64(&mut it, "-value"),
            "-crowdimpact" => crowd_impact = req_f64(&mut it, "-crowdimpact"),
            "-panic" => panic_k = req_f64(&mut it, "-panic"),
            "-drift" => drift = req_f64(&mut it, "-drift"),
            "-ratemean" => rate_mean = req_f64(&mut it, "-ratemean"),
            "-duration" => duration = req_f64(&mut it, "-duration"),
            "-flight" => flight = req_f64(&mut it, "-flight"),
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
    let w = World {
        trend_share,
        depth,
        stress,
        beta,
        drift,
        fund_vol: 0.13,
        rate_mean,
        vol_persist,
        vol_of_vol,
        value_pull,
        crowd,
        crowd_impact,
        panic: panic_k,
        duration,
        flight,
        infl_prob,
        infl_size,
        infl_speed,
        rate_speed,
        discount,
        margin,
    };

    if calibrate_n > 0 {
        calibrate(calibrate_n, &w, seed);
        return;
    }
    if fitness_only {
        let st = measure(&sim_paths(&w, 60, 80, seed), 80);
        let (loss, rows) = fitness(&st);
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
        for (n, ok, _) in gate_checks(&st) {
            if !ok {
                println!("  FAILED GATE: {n}  (+0.500)");
            }
        }
        return;
    }
    if strategies {
        run_strategy_sweep(paths, years, seed, cost, single, &w, &gate_req);
        return;
    }
    if power_report {
        run_power_report(paths, seed, cost, single, &w, &gate_req);
        return;
    }
    if buffer_report {
        run_buffer_report(paths, years, seed, cost, single, &w, &gate_req);
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
        let realism_bad = failed_in(&gate_st, GateClass::Realism);
        let mechanism_bad = failed_in(&gate_st, GateClass::Mechanism);
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
        let fidelity_bad = failed_in(&gate_st, GateClass::Fidelity);
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
            (0..paths)
                .map(|k| {
                    let f = indexed_name(&emit, k);
                    write_emitted(
                        &f,
                        &sims[k],
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
        let sessions = path_at(if emit_all { 0 } else { emit_path }).price.len();
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
        "  bond refuge            vol {}%   growth-crash {}   infl-crash {}",
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
    println!(
        "    equity               >5% {}   >10% {}   >20% {}      real SPY 0.447 / 0.315 / 0.169",
        jf(st.dd_eq5, 0, 3),
        jf(st.dd_eq10, 0, 3),
        jf(st.dd_eq20, 0, 3)
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
    // them from it and conclude the model has drifted. Measured over 1954-2026, the equity depth
    // rungs read 0.436 / 0.269 / 0.126 against the 0.447 / 0.315 / 0.169 targeted here.
    println!(
        "  fidelity against targets, by anchor (each row is against the window named for it):"
    );
    println!(
        "    equity S&P 1954-2026   |   depth rungs SPY 1993-2026   |   return per vol CRSP 1954-2026"
    );
    println!("    refuge long Treasury   |   bond depth rung clean TLT, 24y");
    for (n, get, want, _) in fit_targets() {
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
        let checks = gate_checks(&st);
        let bad: Vec<Vec<String>> = GateClass::ALL.iter().map(|c| failed_in(&st, *c)).collect();
        let verdict = |i: usize| {
            if bad[i].is_empty() { "PASS" } else { "FAIL" }
        };
        println!();
        println!("  acceptance gate:");
        for cls in GateClass::ALL {
            let (banner, cost) = cls.section();
            println!("    {banner} — {cost}:");
            for (n, ok, _) in checks.iter().filter(|(_, _, c)| *c == cls) {
                println!("     {:<5} {}", if *ok { "PASS" } else { "FAIL" }, n);
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

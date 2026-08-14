//! One half of the cross-language demo pair; `jsrc/marketSim.sc` is the other.
//!
//! Unlike the other pairs, this one is not a tour of an API — it is a real workload that
//! happens to exercise `NumPyRng`, `cli` and `MatD` together at scale (200 paths × 100
//! years), and it is the consumer that drove Tier 3 milestone 1. `-emit` and `-validate`
//! are byte-identical between the two languages.
//!
//! Ported so far: the price-formation core (`World`, `Market`, `simulate`, `-emit`) and
//! the measurement layer — stylised-fact statistics, drawdown episodes, the acceptance
//! gate and the calibration loss, which is what `-validate` reports. Still to come: the
//! exposure rules, grading statistics, and the `-strategies`/`-power`/`-buffer` reports.
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

/// No-trade band on the crowd's exposure target.
const BAND: f64 = 0.05;

/// What the non-value crowd trades on. Momentum is the generic extrapolator; the other two
/// run the SAME RULE being tested, so its de-risking moves the price it reacts to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[expect(
    dead_code,
    reason = "Trend/VolScaled are constructed by the -crowd flag, which lands with the \
              exposure rules; simulate already dispatches on them"
)]
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
    #[expect(dead_code, reason = "consumed by the exposure rules in a later milestone")]
    rate: Vec<f64>,
    #[expect(dead_code, reason = "consumed by the exposure rules in a later milestone")]
    fundamental: Vec<f64>,
    /// per-session slippage multiplier (equity market)
    #[expect(dead_code, reason = "consumed by armPath in a later milestone")]
    liq: Vec<f64>,
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
    let sigma_n = 0.007f64;
    let k_adapt = 0.010f64;
    let k_home = 0.020f64;
    let mut log_vol = 0.0f64;
    let vol_norm =
        (w.vol_of_vol * w.vol_of_vol) / 1e-9f64.max(1.0 - w.vol_persist * w.vol_persist);
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
            rate + w.rate_speed * ((w.rate_mean + infl_press) - rate) * dt
                - flight_cut * dt
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
            Crowd::Momentum => k_trend * w_trend * trend_pos,
            _ => w.crowd_impact * w_trend * (crowd_e - crowd_prev),
        };
        crowd_prev = crowd_e;
        log_vol = w.vol_persist * log_vol + w.vol_of_vol * rng.randn();
        let d_noise = sigma_n * (log_vol - vol_norm).exp() * rng.randn();

        // ---- both markets step through the SAME mechanism ---------------------------------
        let ret_e = eq_m.step(log_vbase, eq_flow + d_noise);
        // joint-stress margin selling: when both markets are stressed, the bond gets dumped too
        let bond_flow = -w.margin * eq_m.stress_idx * bd_m.stress_idx;
        let _ret_b = bd_m.step(fair_b, bond_flow + SIGMA_N_BOND * rng.randn());

        px[i] = (eq_m.log_p - markdown).exp();
        fv[i] = (log_vbase - markdown).exp();
        rt[i] = rate;
        lq[i] = eq_m.last_liq;
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
        bond: bp[BURN_IN..].to_vec(),
        infl_press: ip[BURN_IN..].to_vec(),
        cpi: cp[BURN_IN..].to_vec(),
        mean_trend_share: w_trend_sum / nf,
        trend_pinned: pinned_cnt as f64 / nf,
        target_sat: sat_cnt as f64 / nf,
        clamped_days: eq_m.clamps + bd_m.clamps - clamps_at_burn,
        mean_bond_stress: bond_stress_sum / nf,
        pct_bond_stress: bond_stress_hi as f64 / nf,
    }
}

// ---- stylised-fact measurements ---------------------------------------------------------

fn daily_returns(px: &[f64]) -> Vec<f64> {
    (0..px.len() - 1).map(|i| (px[i + 1] / px[i]).ln()).collect()
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
        (&z.rowsSlice(0, n - lag) * &z.rowsSlice(lag, n)).sum() / den
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
    #[expect(dead_code, reason = "kept for parity with the Scala record; not printed")]
    mean_bond_stress: f64,
    pct_bond_stress: f64,
    infl_ann: f64,
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
    let eps_by: Vec<(&Path, Vec<Episode>)> = sims
        .iter()
        .map(|s| (s, episodes(&s.price, 15.0)))
        .collect();
    let eps: Vec<Episode> = eps_by.iter().flat_map(|(_, e)| e.iter().copied()).collect();
    let shapes: Vec<f64> = eps
        .iter()
        .map(|e| e.shape())
        .filter(|x| !x.is_nan())
        .collect();
    let days: f64 = sims.iter().map(|s| s.price.len() as f64).sum();

    let bond_in_windows = |infl_regime: bool| -> f64 {
        let vals: Vec<f64> = eps_by
            .iter()
            .flat_map(|(sp, es)| {
                es.iter()
                    .filter(|ep| {
                        let s: f64 = (ep.peak..=ep.trough).map(|k| sp.infl_press[k]).sum();
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
        vol: med(
            &rets
                .iter()
                .map(|r| (MatD::apply(r).power(2).mean() * dpy).sqrt())
                .collect::<Vec<f64>>(),
        ),
        kurt: med(&rets.iter().map(|r| kurtosis(r)).collect::<Vec<f64>>()),
        ac1: med(&rets.iter().map(|r| autocorr_abs(r, 1)).collect::<Vec<f64>>()),
        ac20: med(&rets.iter().map(|r| autocorr_abs(r, 20)).collect::<Vec<f64>>()),
        ann_ret: med(
            &sims
                .iter()
                .map(|s| (s.price[s.price.len() - 1] / s.price[0]).ln() / years as f64 * 100.0)
                .collect::<Vec<f64>>(),
        ),
        n_episodes: eps.len(),
        ep_per_path: eps.len() as f64 / n_sims,
        depth_med: med(&depths),
        worst_depth: if depths.is_empty() {
            f64::NAN
        } else {
            sorted_total(&depths)[0]
        },
        v_count: shapes.iter().filter(|&&x| x > 1.5).count(),
        mid_count: shapes.iter().filter(|&&x| (0.67..=1.5).contains(&x)).count(),
        u_count: shapes.iter().filter(|&&x| x < 0.67).count(),
        n_shapes: shapes.len(),
        censored: eps.iter().filter(|e| e.censored()).count(),
        clamp_pct: sims.iter().map(|s| s.clamped_days as f64).sum::<f64>() / days * 100.0,
        trend_share: sims.iter().map(|s| s.mean_trend_share).sum::<f64>() / n_sims,
        years_per_path: years as f64,
        trend_pinned: sims.iter().map(|s| s.trend_pinned).sum::<f64>() / n_sims,
        target_sat: sims.iter().map(|s| s.target_sat).sum::<f64>() / n_sims,
        bond_vol: med(
            &sims
                .iter()
                .map(|s| (MatD::apply(&daily_returns(&s.bond)).power(2).mean() * dpy).sqrt())
                .collect::<Vec<f64>>(),
        ),
        bond_growth: bond_in_windows(false),
        bond_infl: bond_in_windows(true),
        corr_calm: corr_in(false),
        corr_infl: corr_in(true),
        mean_bond_stress: sims.iter().map(|s| s.mean_bond_stress).sum::<f64>() / n_sims,
        pct_bond_stress: sims.iter().map(|s| s.pct_bond_stress).sum::<f64>() / n_sims,
        infl_ann: med(
            &sims
                .iter()
                .map(|s| (s.cpi[s.cpi.len() - 1] / s.cpi[0]).ln() / years as f64 * 100.0)
                .collect::<Vec<f64>>(),
        ),
    }
}

/// TWO-SIDED wherever a plausible range exists. History of this gate: a one-sided version
/// passed a 35%-volatility world (the one reversing the ranking); a "bonds fail" check
/// written as bondInfl < bondGrowth passed while bonds still RALLIED +2.8; crash frequency
/// shipped without an upper bound WHILE the one-sided lesson was being applied elsewhere.
#[expect(
    clippy::manual_range_contains,
    reason = "kept in the Scala's spelling so the gate reads as the bounds it documents"
)]
fn gate_checks(st: &WorldStats) -> Vec<(&'static str, bool)> {
    let pc = st.ep_per_path * 100.0 / st.years_per_path;
    vec![
        ("equity vol 8-25%", st.vol > 0.08 && st.vol < 0.25),
        ("kurtosis 4-30", st.kurt > 4.0 && st.kurt < 30.0),
        (
            "clustering 0.10-0.40",
            st.ac1 > 0.10 && st.ac1 < 0.40 && st.ac20 > 0.03,
        ),
        (
            "crash rate 8-45/century",
            st.ep_per_path >= 1.0 && pc >= 8.0 && pc <= 45.0,
        ),
        (
            "both recovery shapes",
            st.n_shapes > 0
                && st.v_count >= st.n_shapes / 10
                && st.u_count >= st.n_shapes / 10,
        ),
        ("no runaway drift", st.ann_ret.abs() < 30.0),
        // 0.02% ~ one clamped session per 20 path-years. The old bound (0.5%) would have
        // passed a world where the clamp was already reshaping kurtosis by a third.
        ("clamp rarely binds", st.clamp_pct < 0.02),
        ("bond vol 7-20%", st.bond_vol > 0.07 && st.bond_vol < 0.20),
        ("bonds rally in growth shocks", st.bond_growth > 3.0),
        ("bonds LOSE in inflation regimes", st.bond_infl < -3.0),
        (
            "corr flips positive under inflation",
            !st.corr_infl.is_nan()
                && !st.corr_calm.is_nan()
                && st.corr_infl > st.corr_calm + 0.15
                && st.corr_infl > 0.0
                && st.corr_calm < 0.35,
        ),
        (
            "bond spiral engages, not always",
            st.pct_bond_stress > 0.002 && st.pct_bond_stress < 0.5,
        ),
        ("inflation 1-6%/yr", st.infl_ann > 1.0 && st.infl_ann < 6.0),
    ]
}

type StatFn = fn(&WorldStats) -> f64;

/// name, extractor, target, weight
fn fit_targets() -> Vec<(&'static str, StatFn, f64, f64)> {
    vec![
        ("equity vol %", (|st| st.vol * 100.0) as StatFn, 16.0, 1.0),
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
        ("bond vol %", (|st| st.bond_vol * 100.0) as StatFn, 13.0, 1.0),
        ("bond growth-crash", (|st| st.bond_growth) as StatFn, 20.0, 1.0),
        ("bond infl-crash", (|st| st.bond_infl) as StatFn, -25.0, 1.5),
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
    let gate_penalty = gate_checks(st).iter().filter(|(_, ok)| !ok).count() as f64 * 0.5;
    let total: f64 = rows.iter().map(|r| r.3).sum::<f64>() + gate_penalty;
    (total, rows)
}

fn sim_paths(w: &World, paths: usize, years: usize, seed: u64) -> Vec<Path> {
    (0..paths)
        .into_par_iter()
        .map(|k| simulate(w, years, seed.wrapping_add(k as u64 * 7919)))
        .collect()
}

// ---- Java-compatible formatting ---------------------------------------------------------

/// `%<width>.<dec>f`
fn jf(v: f64, width: i32, dec: i32) -> String {
    java_format_f(v, width, dec)
}

/// `%+.<dec>f` — Java prefixes non-negative values with `+`, and leaves NaN unsigned.
fn jfs(v: f64, dec: i32) -> String {
    if v.is_nan() {
        return "NaN".to_string();
    }
    let s = java_format_f(v, 0, dec);
    if s.starts_with('-') { s } else { format!("+{s}") }
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
fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();

    let mut paths = 200usize;
    let mut years = 100usize;
    let mut seed = 20_260_813u64;
    let mut emit = String::new();
    let mut validate = false;
    let mut it = args.iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "-paths" => paths = it.next().and_then(|v| v.parse().ok()).unwrap_or(paths),
            "-years" => years = it.next().and_then(|v| v.parse().ok()).unwrap_or(years),
            "-seed" => seed = it.next().and_then(|v| v.parse().ok()).unwrap_or(seed),
            "-emit" => emit = it.next().cloned().unwrap_or_default(),
            "-validate" => validate = true,
            other => {
                eprintln!("not ported yet: [{other}]");
                std::process::exit(2);
            }
        }
    }

    // defaults = best of a 50-sample random search against the fitness loss, scored at
    // 100-year paths (train 3.43, holdout 3.44 — indistinguishable, so not seed-fit)
    let w = World {
        trend_share: 0.30,
        depth: 12.0,
        stress: 3.4,
        beta: 3.0,
        drift: 0.100,
        fund_vol: 0.13,
        rate_mean: 0.042,
        vol_persist: 0.99,
        vol_of_vol: 0.028,
        value_pull: 0.015,
        crowd: Crowd::Momentum,
        crowd_impact: 0.06,
        panic: 0.0,
        duration: 13.5,
        flight: 0.38,
        infl_prob: 0.20,
        infl_size: 0.07,
        infl_speed: 0.010,
        rate_speed: 3.0,
        discount: 4.0,
        margin: 0.0008,
    };

    eprintln!("simulating {paths} paths x {years} years");
    let sims = sim_paths(&w, paths, years, seed);
    let st = measure(&sims, years);

    if !emit.is_empty() {
        // an exported path can end up inside the real-data harnesses with no memory of where
        // it came from, so the gate verdict travels with the export — loudly, at export time
        let checks = gate_checks(&st);
        if !checks.iter().all(|(_, ok)| *ok) {
            let failed: Vec<&str> = checks
                .iter()
                .filter(|(_, ok)| !ok)
                .map(|(n, _)| *n)
                .collect();
            eprintln!(
                "WARNING: this world FAILS the acceptance gate [{}] — the emitted path is not market-like",
                failed.join(", ")
            );
        }
        let p = &sims[0];
        let start = UniDateTime::ofYmd(1900, 1, 2);
        let mut out = String::new();
        for i in 0..p.price.len() {
            let d = start.plusDays((i as i64 * 365) / DAYS_PER_YEAR as i64).ymd();
            out.push_str(&d);
            out.push('\t');
            out.push_str(&jf(p.price[i], 0, 6));
            out.push('\t');
            out.push_str(&jf(p.bond[i], 0, 6));
            out.push('\n');
        }
        std::fs::write(&emit, out).unwrap_or_else(|e| {
            eprintln!("cannot write {emit}: {e}");
            std::process::exit(1);
        });
        eprintln!(
            "wrote path 0 (price, bond) to {emit} ({} sessions)",
            p.price.len()
        );
    }

    let all_rets: Vec<Vec<f64>> = sims.iter().map(|s| daily_returns(&s.price)).collect();
    let ann_vol: Vec<f64> = all_rets
        .iter()
        .map(|r| (r.iter().map(|x| x * x).sum::<f64>() / r.len() as f64 * DAYS_PER_YEAR as f64).sqrt())
        .collect();
    let ann_ret: Vec<f64> = sims
        .iter()
        .map(|s| (s.price[s.price.len() - 1] / s.price[0]).ln() / years as f64 * 100.0)
        .collect();

    println!("paths {paths} x {years} years   {} simulated years", paths * years);
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
    println!(
        "  binding diagnostics    trend share {} (pinned {}%, target saturated {}%)   bond spiral {}% of sessions   clamped {}%",
        jf(st.trend_share, 0, 2),
        jf(st.trend_pinned * 100.0, 0, 1),
        jf(st.target_sat * 100.0, 0, 1),
        jf(st.pct_bond_stress * 100.0, 0, 1),
        jf(st.clamp_pct, 0, 3)
    );

    println!();
    println!("  fidelity against targets (S&P 1954-2026 equity; long-Treasury refuge):");
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
        println!();
        println!("  acceptance gate:");
        for (n, ok) in &checks {
            println!("     {:<5} {}", if *ok { "PASS" } else { "FAIL" }, n);
        }
        if checks.iter().any(|(_, ok)| !ok) {
            eprintln!("acceptance gate FAILED — this world is not fit to compare strategies in");
            std::process::exit(1);
        }
    }

    let _ = fitness(&st); // -fitness flag lands in a later milestone
}

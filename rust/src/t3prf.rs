//! Three-Pass Regression Filter (Kelly & Pruitt, 2015).
//!
//! Ported to match `src/main/scala/uni/stats/Tprf3.scala` and `py/tprf3.py`,
//! which share the reference MATLAB's conventions: the recursive training window
//! is `[0, t-1-gap)`, the Cross Val drop block is clipped with signed arithmetic,
//! and the out-of-sample R² is measured against the prevailing training-window
//! mean (`rollfore`) rather than the full-sample mean.
//!
//! Inputs must be NaN-free. The reference implementations additionally drop
//! NaN rows per regression; that path is not ported here.

#![allow(
    non_snake_case,
    reason = "the closed-form entry points mirror the Scala API name-for-name (the crate's \
              naming contract); this module's older snake_case entry points predate the \
              contract and keep their names."
)]

use ndarray::Array2;
use ndarray::ArrayView2;
use ndarray::Axis;
use ndarray::s;
use rayon::prelude::*;

use crate::error::Error;

/// Minimum training observations per three-pass fit, matching the reference
/// implementations' `min_obs` / `minObs` default. Shorter windows yield a NaN
/// forecast instead of an ill-conditioned fit.
const MIN_OBS: usize = 10;

#[derive(Debug, Clone)]
pub struct Tprf3Result {
    pub forecasts: Array2<f64>,
    pub residuals: Array2<f64>,
    pub r_squared: f64,
    /// Prevailing (training-window) mean of `y` at each forecast origin — the
    /// benchmark series the out-of-sample R² is measured against. All-NaN for
    /// IS Full, which is scored against the full-sample mean instead.
    pub rollfore: Array2<f64>,
    /// Clark–McCracken ENC-NEW statistic; NaN except for OOS Recursive.
    pub encnew: f64,
}

// ── Small dense linear algebra ──────────────────────────────────────────────
//
// Every system solved here is `AᵀA·B = AᵀY` with `A` having `L+1` columns, so
// the factored matrix is tiny (typically 3×3) while `Y` may be wide. `AᵀA` is
// symmetric positive definite whenever `A` has full column rank, which is
// exactly the condition for the fit to be identified — so Cholesky both suits
// the structure and detects rank deficiency, with half the work of an LU.

/// Lower-triangular Cholesky factor of a small symmetric positive-definite
/// matrix, held row-major in a flat buffer.
struct Chol {
    l: Vec<f64>,
    k: usize,
}

impl Chol {
    fn new(a: ArrayView2<'_, f64>) -> Result<Self, Error> {
        let k = a.nrows();
        let mut l = vec![0.0_f64; k * k];
        for i in 0..k {
            for j in 0..=i {
                let mut sum = a[[i, j]];
                for p in 0..j {
                    sum -= l[i * k + p] * l[j * k + p];
                }
                if i == j {
                    // non-positive or NaN pivot ⇒ the design is rank deficient
                    if sum <= 0.0 || sum.is_nan() {
                        return Err(Error::SingularMatrix(format!(
                            "normal equations not positive definite at pivot {i}"
                        )));
                    }
                    l[i * k + i] = sum.sqrt();
                } else {
                    l[i * k + j] = sum / l[j * k + j];
                }
            }
        }
        Ok(Self { l, k })
    }

    /// Solve `L·Lᵀ·X = rhs` for every column of `rhs`.
    fn solve(&self, rhs: ArrayView2<'_, f64>) -> Array2<f64> {
        let k = self.k;
        let mut x = rhs.to_owned();
        let m = x.ncols();
        for i in 0..k {
            let lii = self.l[i * k + i];
            for c in 0..m {
                let mut v = x[[i, c]];
                for p in 0..i {
                    v -= self.l[i * k + p] * x[[p, c]];
                }
                x[[i, c]] = v / lii;
            }
        }
        for i in (0..k).rev() {
            let lii = self.l[i * k + i];
            for c in 0..m {
                let mut v = x[[i, c]];
                for p in (i + 1)..k {
                    v -= self.l[p * k + i] * x[[p, c]];
                }
                x[[i, c]] = v / lii;
            }
        }
        x
    }
}

/// Cached factorization of `AᵀA` for a design matrix `A`. Holding it lets one
/// design serve several right-hand sides: pass 2 reuses its factorization for
/// the out-of-sample row rather than re-forming and re-factorizing the same
/// system, which is the single largest redundancy in the OOS loops.
struct NormalEq {
    chol: Chol,
}

impl NormalEq {
    fn factor(a: ArrayView2<'_, f64>) -> Result<Self, Error> {
        Ok(Self {
            chol: Chol::new(a.t().dot(&a).view())?,
        })
    }

    /// Solve `AᵀA·B = rhs`, where `rhs` is already the cross-product `Aᵀ·Y`.
    fn solve_normal(&self, rhs: ArrayView2<'_, f64>) -> Array2<f64> {
        self.chol.solve(rhs)
    }
}

// ── Column statistics and window preparation ────────────────────────────────

/// Prepend a column of ones: `[1 | m]`.
fn with_intercept(m: ArrayView2<'_, f64>) -> Array2<f64> {
    let mut out = Array2::<f64>::ones((m.nrows(), m.ncols() + 1));
    out.slice_mut(s![.., 1..]).assign(&m);
    out
}

fn nan_col(t: usize) -> Array2<f64> {
    Array2::from_elem((t, 1), f64::NAN)
}

/// NaN-ignoring mean of column 0.
fn mean_col(v: ArrayView2<'_, f64>) -> f64 {
    let (mut n, mut sum) = (0_usize, 0.0_f64);
    for i in 0..v.nrows() {
        let x = v[[i, 0]];
        if !x.is_nan() {
            n += 1;
            sum += x;
        }
    }
    if n > 0 { sum / n as f64 } else { f64::NAN }
}

/// Column sample standard deviations (ddof = 1); zero std becomes 1.0 so the
/// scaling below never divides by zero. Swept row-major so the passes run
/// sequentially over the backing store instead of striding one column at a time.
fn std_cols_view(m: ArrayView2<'_, f64>) -> Array2<f64> {
    let (rows, cols) = (m.nrows(), m.ncols());
    let mut out = Array2::<f64>::ones((1, cols));
    if rows <= 1 {
        return out;
    }
    let mut sum = vec![0.0_f64; cols];
    for row in m.rows() {
        for (j, &v) in row.iter().enumerate() {
            sum[j] += v;
        }
    }
    let mu: Vec<f64> = sum.iter().map(|s| s / rows as f64).collect();
    let mut ss = vec![0.0_f64; cols];
    for row in m.rows() {
        for (j, &v) in row.iter().enumerate() {
            let d = v - mu[j];
            ss[j] += d * d;
        }
    }
    for j in 0..cols {
        let sd = (ss[j] / (rows - 1) as f64).sqrt();
        out[[0, j]] = if sd == 0.0 { 1.0 } else { sd };
    }
    out
}

/// Reciprocal column std-devs, the form the three passes actually consume.
fn inv_std_cols(m: ArrayView2<'_, f64>) -> Vec<f64> {
    std_cols_view(m).iter().map(|s| 1.0 / s).collect()
}

/// Scale column `j` of `m` by `f[j]`.
fn scale_cols(m: &mut Array2<f64>, f: &[f64]) {
    for mut row in m.rows_mut() {
        for (j, v) in row.iter_mut().enumerate() {
            *v *= f[j];
        }
    }
}

/// Scale row `i` of `m` by `f[i]`.
fn scale_rows(m: &mut Array2<f64>, f: &[f64]) {
    for (i, mut row) in m.rows_mut().into_iter().enumerate() {
        let s = f[i];
        for v in row.iter_mut() {
            *v *= s;
        }
    }
}

/// Column sample std-devs (ddof = 1) over the rows outside `[lo, hi)`, computed
/// without materializing that subset. Zero std becomes 1.0.
fn std_cols_excluding(m: ArrayView2<'_, f64>, lo: usize, hi: usize) -> Array2<f64> {
    let (rows, cols) = (m.nrows(), m.ncols());
    let keep = rows - (hi - lo);
    let mut out = Array2::<f64>::ones((1, cols));
    if keep <= 1 {
        return out;
    }
    let mut sum = vec![0.0_f64; cols];
    for i in (0..lo).chain(hi..rows) {
        for (j, &v) in m.row(i).iter().enumerate() {
            sum[j] += v;
        }
    }
    let mu: Vec<f64> = sum.iter().map(|s| s / keep as f64).collect();
    let mut ss = vec![0.0_f64; cols];
    for i in (0..lo).chain(hi..rows) {
        for (j, &v) in m.row(i).iter().enumerate() {
            let d = v - mu[j];
            ss[j] += d * d;
        }
    }
    for j in 0..cols {
        let sd = (ss[j] / (keep - 1) as f64).sqrt();
        out[[0, j]] = if sd == 0.0 { 1.0 } else { sd };
    }
    out
}

/// Full-sample per-column sufficient statistics: column sums and Σ(x−μ)².
/// Computed once per Cross Val call and shared read-only across windows.
fn full_col_stats(m: ArrayView2<'_, f64>) -> (Vec<f64>, Vec<f64>) {
    let (rows, cols) = (m.nrows(), m.ncols());
    let mut sum = vec![0.0_f64; cols];
    for row in m.rows() {
        for (j, &v) in row.iter().enumerate() {
            sum[j] += v;
        }
    }
    let mu: Vec<f64> = sum
        .iter()
        .map(|s| if rows > 0 { s / rows as f64 } else { 0.0 })
        .collect();
    let mut ssd = vec![0.0_f64; cols];
    for row in m.rows() {
        for (j, &v) in row.iter().enumerate() {
            let d = v - mu[j];
            ssd[j] += d * d;
        }
    }
    (sum, ssd)
}

/// Reciprocal column std-devs of the Cross Val kept-set (every row but
/// `[lo, hi)`), derived from precomputed full-sample stats. The kept-set sum of
/// squares follows from
///
/// ```text
/// Σ_kept(x − m_keep)² = (Σ_all(x − μ)² − Σ_drop(x − μ)²) − keep·(m_keep − μ)²
/// ```
///
/// making the std an O(drop·N) downdate instead of an O(keep·N) recompute — the
/// dominant saving when the window drops only a row or two. Cancellation is
/// negligible while the kept set is the majority; when it is not, recompute
/// directly from `kept`, which is cheap in that regime anyway. Not bit-identical
/// to the direct two-pass form (~1e-13 drift).
fn kept_inv_std(
    src: ArrayView2<'_, f64>,
    lo: usize,
    hi: usize,
    full: &(Vec<f64>, Vec<f64>),
) -> Vec<f64> {
    let (rows, cols) = (src.nrows(), src.ncols());
    let keep = rows - (hi - lo);
    if keep < 2 || keep * 2 < rows {
        return std_cols_excluding(src, lo, hi)
            .iter()
            .map(|s| 1.0 / s)
            .collect();
    }

    let (full_sum, full_ssd) = full;
    let mut drop_sum = vec![0.0_f64; cols];
    let mut drop_ssd = vec![0.0_f64; cols];
    for i in lo..hi {
        for (j, &v) in src.row(i).iter().enumerate() {
            let mu = full_sum[j] / rows as f64;
            drop_sum[j] += v;
            let d = v - mu;
            drop_ssd[j] += d * d;
        }
    }

    (0..cols)
        .map(|j| {
            let mu = full_sum[j] / rows as f64;
            let mu_keep = (full_sum[j] - drop_sum[j]) / keep as f64;
            let shift = mu_keep - mu;
            // clamp the tiny negative rounding can leave when a column is constant
            let ss = ((full_ssd[j] - drop_ssd[j]) - keep as f64 * shift * shift).max(0.0);
            let s = (ss / (keep - 1) as f64).sqrt();
            1.0 / if s == 0.0 { 1.0 } else { s }
        })
        .collect()
}

// ── Core three-pass engine ──────────────────────────────────────────────────

/// Three-pass filter, optionally producing a point forecast for one
/// out-of-sample predictor row.
///
/// `x_raw` is the *unscaled* window and `inv_sd` the reciprocal column std-devs.
/// Column scaling commutes with both products the filter takes against X:
///
/// ```text
/// Zᵀ·(X·D⁻¹) == (Zᵀ·X)·D⁻¹        (scale the (L+1)×N result)
/// (X·D⁻¹)·P  == X·(D⁻¹·P)         (scale the N×(L+1) operand)
/// ```
///
/// so the scaling always lands on a matrix with `L+1` columns and the T×N
/// product `X·D⁻¹` is never materialized. That lets every caller pass a plain
/// view of its window — the OOS loops allocate nothing per iteration for X.
fn t3prf_view(
    y: ArrayView2<'_, f64>,
    x_raw: ArrayView2<'_, f64>,
    inv_sd: &[f64],
    z: ArrayView2<'_, f64>,
    oos_raw: Option<ArrayView2<'_, f64>>,
    min_obs: usize,
) -> Result<Option<Tail>, Error> {
    let t = x_raw.nrows();
    if t < min_obs {
        return Ok(None);
    }

    // Pass 1: regress X on [1 | Z] → Phi (N×L, held transposed as the L×N tail)
    let dz = with_intercept(z);
    let ne1 = NormalEq::factor(dz.view())?;
    let mut rhs1 = dz.t().dot(&x_raw);
    scale_cols(&mut rhs1, inv_sd); // == dzᵀ·(X·D⁻¹)
    let beta1 = ne1.solve_normal(rhs1.view());
    let dp = with_intercept(beta1.slice(s![1.., ..]).t());
    Ok(Some(t3prf_tail(y, x_raw, inv_sd, &dp, oos_raw)?))
}

/// Three-pass outputs. The fitted series is not built here: only IS Full reads
/// it, while every OOS window reads just `yhatt` — materializing it there would
/// cost a matmul and a T×1 allocation per window, discarded immediately. Call
/// [`Tail::fitted`] where it is actually wanted.
struct Tail {
    ds: Array2<f64>,
    beta3: Array2<f64>,
    yhatt: f64,
}

impl Tail {
    fn fitted(&self) -> Array2<f64> {
        self.ds.dot(&self.beta3)
    }
}

/// Passes 2 and 3 plus the optional out-of-sample forecast, given the pass-1
/// design `dp = [1 | Phi]`. Shared by the direct pass-1 path above and the
/// downdated one used by OOS Recursive.
fn t3prf_tail(
    y: ArrayView2<'_, f64>,
    x_raw: ArrayView2<'_, f64>,
    inv_sd: &[f64],
    dp: &Array2<f64>,
    oos_raw: Option<ArrayView2<'_, f64>>,
) -> Result<Tail, Error> {
    let l = dp.ncols() - 1;

    // Pass 2: regress Xᵀ on [1 | Phi] → Sigma (T×L).
    // dpᵀ·Xᵀ == (X·dp)ᵀ, so form the small T×(L+1) product and swap axes; the
    // T×N transpose of X is never materialized either.
    let ne2 = NormalEq::factor(dp.view())?;
    let mut dps = dp.clone();
    scale_rows(&mut dps, inv_sd); // == D⁻¹·dp, so X·dps == (X·D⁻¹)·dp
    let beta2 = ne2.solve_normal(x_raw.dot(&dps).reversed_axes().view());

    // Pass 3: regress y on [1 | Sigma]
    let ds = with_intercept(beta2.slice(s![1.., ..]).t());
    let ne3 = NormalEq::factor(ds.view())?;
    let beta3 = ne3.solve_normal(ds.t().dot(&y).view());

    let yhatt = match oos_raw {
        None => f64::NAN,
        Some(oos) => {
            // reuse pass 2's factorization and its scaled design
            let b_oos = ne2.solve_normal(oos.dot(&dps).reversed_axes().view());
            (0..l).fold(beta3[[0, 0]], |acc, j| {
                acc + b_oos[[j + 1, 0]] * beta3[[j + 1, 0]]
            })
        }
    };
    Ok(Tail { ds, beta3, yhatt })
}

// ── OOS window specialisations ──────────────────────────────────────────────

/// Full-sample quantities shared read-only across the windows of an OOS run.
///
/// Every window keeps all rows but one contiguous block — an interior block for
/// Cross Val, the suffix `[end, T)` for Recursive — so its pass-1 cross products
/// are the full-sample ones minus that block's contribution: an O(drop·N·L)
/// downdate instead of an O(keep·N·L) product.
struct FullSample {
    dz: Array2<f64>,           // [1 | Z]    T×(L+1)
    ztz: Array2<f64>,          // dzᵀ·dz     (L+1)×(L+1)
    ztx: Array2<f64>,          // dzᵀ·X      (L+1)×N
    col: (Vec<f64>, Vec<f64>), // column sums and Σ(x−μ)², for the std downdate
}

impl FullSample {
    fn new(x_norm: ArrayView2<'_, f64>, z: ArrayView2<'_, f64>) -> Self {
        let dz = with_intercept(z);
        let ztz = dz.t().dot(&dz);
        let ztx = dz.t().dot(&x_norm);
        Self {
            dz,
            ztz,
            ztx,
            col: full_col_stats(x_norm),
        }
    }

    /// Pass-1 normal equations for the window that drops rows `[lo, hi)`:
    /// `(dz_keptᵀ·dz_kept, dz_keptᵀ·X_kept·D⁻¹)`.
    fn pass1_downdated(
        &self,
        x_norm: ArrayView2<'_, f64>,
        lo: usize,
        hi: usize,
        inv_sd: &[f64],
    ) -> (Array2<f64>, Array2<f64>) {
        let dz_drop = self.dz.slice(s![lo..hi, ..]);
        let x_drop = x_norm.slice(s![lo..hi, ..]);
        let ztz = &self.ztz - &dz_drop.t().dot(&dz_drop);
        let mut rhs = &self.ztx - &dz_drop.t().dot(&x_drop);
        scale_cols(&mut rhs, inv_sd);
        (ztz, rhs)
    }
}

/// One Recursive window: fit on the prefix `[0, end)` and forecast row `t`.
///
/// The prefix is the full sample minus the suffix `[end, T)`, so pass 1 and the
/// column std both come from downdating that block — O((T−end)·N·L) instead of
/// O(end·N·L), the cheaper side of the trade for every window past the halfway
/// point (and `kept_inv_std` falls back to a direct pass below it).
///
/// Pass 2 runs on the prefix slice itself: unlike Cross Val the kept rows are
/// contiguous, so it is a zero-copy view and there is nothing to gain from
/// computing over the full matrix.
fn t3prf_rec_step(
    full: &FullSample,
    x_norm: ArrayView2<'_, f64>,
    y: ArrayView2<'_, f64>,
    t: usize,
    end: usize,
    min_obs: usize,
) -> Result<(f64, f64), Error> {
    let rows = x_norm.nrows();
    let y_tr = y.slice(s![..end, ..]);
    let roll = mean_col(y_tr);
    if end < min_obs {
        return Ok((f64::NAN, roll));
    }

    let inv_sd = kept_inv_std(x_norm, end, rows, &full.col);
    let (ztz, rhs1) = full.pass1_downdated(x_norm, end, rows, &inv_sd);
    let beta1 = Chol::new(ztz.view())?.solve(rhs1.view());
    let dp = with_intercept(beta1.slice(s![1.., ..]).t());

    let tail = t3prf_tail(
        y_tr,
        x_norm.slice(s![..end, ..]),
        &inv_sd,
        &dp,
        Some(x_norm.slice(s![t..=t, ..])),
    )?;
    Ok((tail.yhatt, roll))
}

/// A Cross Val fold: forecast row `t` with rows `[lo, hi)` held out.
#[derive(Clone, Copy)]
struct CvWindow {
    t: usize,
    lo: usize,
    hi: usize,
}

/// One Cross Val window: fit on every row outside `[lo, hi)`, forecast row `t`.
/// Returns `(forecast, rollfore)`.
///
/// X is never gathered. Pass 1 downdates the precomputed full-sample cross
/// products by the dropped block, and pass 2's `X_kept·dps` is just `X_full·dps`
/// with the dropped rows skipped — identical flops, but the only per-window copy
/// is `L+1` wide instead of `N` wide. Row `t` of that same product is the
/// out-of-sample design, so the forecast costs nothing extra.
fn t3prf_cv_step(
    pre: &FullSample,
    x_norm: ArrayView2<'_, f64>,
    y: ArrayView2<'_, f64>,
    win: CvWindow,
    min_obs: usize,
) -> Result<(f64, f64), Error> {
    let CvWindow { t, lo, hi } = win;
    let rows = x_norm.nrows();
    let l = pre.dz.ncols() - 1;
    let keep = rows - (hi - lo);

    // prevailing mean of the kept response, the OOS R² benchmark
    let (mut ysum, mut yn) = (0.0_f64, 0_usize);
    for i in (0..lo).chain(hi..rows) {
        let v = y[[i, 0]];
        if !v.is_nan() {
            ysum += v;
            yn += 1;
        }
    }
    let roll = if yn > 0 { ysum / yn as f64 } else { f64::NAN };
    if keep < min_obs {
        return Ok((f64::NAN, roll));
    }

    let inv_sd = kept_inv_std(x_norm, lo, hi, &pre.col);
    let (ztz, rhs1) = pre.pass1_downdated(x_norm, lo, hi, &inv_sd);
    let beta1 = Chol::new(ztz.view())?.solve(rhs1.view());
    let dp = with_intercept(beta1.slice(s![1.., ..]).t());

    // Pass 2 — over every row; the kept ones are read out below
    let ne2 = NormalEq::factor(dp.view())?;
    let mut dps = dp.clone();
    scale_rows(&mut dps, &inv_sd);
    let w_full = x_norm.dot(&dps); // T×(L+1)
    let b2 = ne2.solve_normal(w_full.t()); // (L+1)×T

    // Pass 3 — gather the kept design and response together, L+1 wide
    let mut ds = Array2::<f64>::ones((keep, l + 1));
    let mut y_kept = Array2::<f64>::zeros((keep, 1));
    for (oi, si) in (0..lo).chain(hi..rows).enumerate() {
        for j in 0..l {
            ds[[oi, j + 1]] = b2[[j + 1, si]];
        }
        y_kept[[oi, 0]] = y[[si, 0]];
    }
    let ne3 = NormalEq::factor(ds.view())?;
    let beta3 = ne3.solve_normal(ds.t().dot(&y_kept).view());

    // column t of b2 is already the out-of-sample solve
    let yhatt = (0..l).fold(beta3[[0, 0]], |acc, j| {
        acc + b2[[j + 1, t]] * beta3[[j + 1, 0]]
    });
    Ok((yhatt, roll))
}

// ── Scoring ─────────────────────────────────────────────────────────────────

/// In-sample R²: 1 − Σe² / Σ(y − ȳ)² over the rows with a fitted value.
fn is_full_r2(y: ArrayView2<'_, f64>, residuals: ArrayView2<'_, f64>) -> f64 {
    let idx: Vec<usize> = (0..y.nrows())
        .filter(|&i| !residuals[[i, 0]].is_nan())
        .collect();
    let mu = idx.iter().map(|&i| y[[i, 0]]).sum::<f64>() / idx.len() as f64;
    let sse: f64 = idx.iter().map(|&i| residuals[[i, 0]].powi(2)).sum();
    let syy: f64 = idx.iter().map(|&i| (y[[i, 0]] - mu).powi(2)).sum();
    1.0 - sse / syy
}

/// Out-of-sample R² against the prevailing training-window mean:
/// 1 − Σe² / Σ(y − rollfore)², over the rows where a forecast exists.
fn oos_r2(
    y: ArrayView2<'_, f64>,
    residuals: ArrayView2<'_, f64>,
    rollfore: ArrayView2<'_, f64>,
) -> f64 {
    let (mut sse, mut sst) = (0.0_f64, 0.0_f64);
    for i in 0..y.nrows() {
        let e = residuals[[i, 0]];
        if !e.is_nan() {
            sse += e * e;
            let d = y[[i, 0]] - rollfore[[i, 0]];
            sst += d * d;
        }
    }
    if sst != 0.0 {
        1.0 - sse / sst
    } else {
        f64::NAN
    }
}

/// Clark–McCracken ENC-NEW statistic, over rows where both series are present.
/// Argument order follows the reference implementations, which pass `rollfore`
/// as the first series and the forecast errors as the second.
fn encnew_stat(a: ArrayView2<'_, f64>, b: ArrayView2<'_, f64>) -> f64 {
    let idx: Vec<usize> = (0..a.nrows())
        .filter(|&i| !a[[i, 0]].is_nan() && !b[[i, 0]].is_nan())
        .collect();
    let p = idx.len() as f64;
    let num: f64 = idx
        .iter()
        .map(|&i| a[[i, 0]] * a[[i, 0]] - a[[i, 0]] * b[[i, 0]])
        .sum();
    let den: f64 = idx.iter().map(|&i| b[[i, 0]] * b[[i, 0]]).sum();
    p * num / den
}

// ── Public estimation entry points ──────────────────────────────────────────

/// IS Full 3PRF: fit on the whole sample and return the fitted series.
pub fn estimate_3prf_is_full(
    y: &Array2<f64>,
    x: &Array2<f64>,
    z: &Array2<f64>,
) -> Result<Tprf3Result, Error> {
    // the scaling rides on `inv_sd`, so the normalized copy of X is never built
    let inv_sd = inv_std_cols(x.view());
    let forecasts = match t3prf_view(y.view(), x.view(), &inv_sd, z.view(), None, MIN_OBS)? {
        Some(tail) => tail.fitted(),
        None => nan_col(y.nrows()),
    };
    let residuals = y - &forecasts;
    let r_squared = is_full_r2(y.view(), residuals.view());
    Ok(Tprf3Result {
        rollfore: nan_col(y.nrows()),
        encnew: f64::NAN,
        forecasts,
        residuals,
        r_squared,
    })
}

/// OOS Recursive 3PRF: for each `t`, fit on `[0, t-1)` and forecast row `t`.
///
/// `min_train` is the smallest training size; forecasts begin at `min_train + 1`.
pub fn estimate_3prf_oos_rec(
    y: &Array2<f64>,
    x: &Array2<f64>,
    z: &Array2<f64>,
    min_train: usize,
) -> Result<Tprf3Result, Error> {
    let t_total = x.nrows();
    let x_norm = standardize_columns(x);
    let full = FullSample::new(x_norm.view(), z.view());

    let fitted: Vec<(usize, f64, f64)> = (min_train + 1..t_total)
        .into_par_iter()
        .map(|t| {
            // training rows [0, t-1), matching the reference
            let (yhatt, roll) = t3prf_rec_step(&full, x_norm.view(), y.view(), t, t - 1, MIN_OBS)?;
            Ok((t, yhatt, roll))
        })
        .collect::<Result<Vec<_>, Error>>()?;

    let mut forecasts = nan_col(t_total);
    let mut rollfore = nan_col(t_total);
    for (t, f, roll) in fitted {
        forecasts[[t, 0]] = f;
        rollfore[[t, 0]] = roll;
    }

    let residuals = y - &forecasts;
    let r_squared = oos_r2(y.view(), residuals.view(), rollfore.view());
    let encnew = encnew_stat(rollfore.view(), residuals.view());
    Ok(Tprf3Result {
        forecasts,
        residuals,
        r_squared,
        rollfore,
        encnew,
    })
}

/// OOS Cross Val 3PRF: for each `t`, drop the block starting `win_left` rows
/// before `t` and spanning `win_right` rows, fit on the rest, and forecast `t`.
pub fn estimate_3prf_oos_cv(
    y: &Array2<f64>,
    x: &Array2<f64>,
    z: &Array2<f64>,
    win_left: usize,
    win_right: usize,
) -> Result<Tprf3Result, Error> {
    let t_total = x.nrows();
    let x_norm = standardize_columns(x);
    let pre = FullSample::new(x_norm.view(), z.view());

    let fitted: Vec<(usize, f64, f64)> = (0..t_total)
        .into_par_iter()
        .map(|t| {
            // Signed arithmetic then clamp, as in the reference: a block starting
            // before row 0 is clipped, never wrapped.
            let lo_raw = t as i64 - win_left as i64;
            let lo = lo_raw.max(0) as usize;
            let hi = (lo_raw + win_right as i64).clamp(0, t_total as i64) as usize;
            let hi = hi.max(lo);

            let win = CvWindow { t, lo, hi };
            let (yhatt, roll) = t3prf_cv_step(&pre, x_norm.view(), y.view(), win, MIN_OBS)?;
            Ok((t, yhatt, roll))
        })
        .collect::<Result<Vec<_>, Error>>()?;

    let mut forecasts = nan_col(t_total);
    let mut rollfore = nan_col(t_total);
    for (t, f, roll) in fitted {
        forecasts[[t, 0]] = f;
        rollfore[[t, 0]] = roll;
    }

    let residuals = y - &forecasts;
    let r_squared = oos_r2(y.view(), residuals.view(), rollfore.view());
    Ok(Tprf3Result {
        forecasts,
        residuals,
        r_squared,
        rollfore,
        encnew: f64::NAN,
    })
}

// ── Retained public helpers ─────────────────────────────────────────────────

/// Column-wise sample standard deviation (ddof = 1); zero std becomes 1.0.
pub fn std_cols(x: &Array2<f64>) -> Array2<f64> {
    std_cols_view(x.view())
}

/// Scale each column of `x` to unit sample variance.
pub fn standardize_columns(x: &Array2<f64>) -> Array2<f64> {
    let stds = std_cols_view(x.view());
    x / &stds
}

/// Least-squares solution of `A·B = Y` via the normal equations `AᵀA·B = AᵀY`.
pub fn ols_solve(a: &Array2<f64>, y: &Array2<f64>) -> Result<Array2<f64>, Error> {
    if y.nrows() != a.nrows() {
        return Err(Error::DimensionMismatch {
            expected: format!("{} rows", a.nrows()),
            actual: format!("{} rows", y.nrows()),
        });
    }
    let ne = NormalEq::factor(a.view())?;
    Ok(ne.solve_normal(a.t().dot(y).view()))
}

/// Core three-pass filter on pre-standardized `x_std`.
pub fn t3prf_core(
    y: &Array2<f64>,
    x_std: &Array2<f64>,
    z: &Array2<f64>,
    oos_x: Option<&Array2<f64>>,
) -> Result<(Array2<f64>, f64), Error> {
    let unit = vec![1.0_f64; x_std.ncols()];
    let tail = t3prf_view(
        y.view(),
        x_std.view(),
        &unit,
        z.view(),
        oos_x.map(|m| m.view()),
        MIN_OBS,
    )?;
    Ok(match tail {
        Some(tail) => (tail.fitted(), tail.yhatt),
        None => (nan_col(x_std.nrows()), f64::NAN),
    })
}

// ── Closed-form variants ────────────────────────────────────────────────────
//
// Ports of the Scala 0.16.0 closed-form entry points: `tprfClosedForm`,
// `plsClosedForm`, `pls1Fit` and `forecast3prf`. Pinned by the same
// `test-data/tprf3-parity/` fixture as the procedures above (`closed` and
// `pls` rows).

/// Row of per-column means, shape `(1, cols)`.
fn mean_cols(m: ArrayView2<'_, f64>) -> Array2<f64> {
    let means = m.sum_axis(Axis(0)) / m.nrows() as f64;
    means.insert_axis(Axis(0))
}

/// Centre each column (subtract its mean): `J(rows)·m` without the dense `J`.
fn center_columns(m: ArrayView2<'_, f64>) -> Array2<f64> {
    &m - &mean_cols(m)
}

/// `y` must be (T×1) and, when given, `z` must have T rows.
fn check_shapes(t: usize, y: &Array2<f64>, z: Option<&Array2<f64>>) -> Result<(), Error> {
    if y.nrows() != t || y.ncols() != 1 || z.is_some_and(|z| z.nrows() != t) {
        return Err(Error::DimensionMismatch {
            expected: format!("y {t}x1 with {t}-row z"),
            actual: format!(
                "y {}x{}, z {}",
                y.nrows(),
                y.ncols(),
                z.map_or_else(
                    || "-".to_string(),
                    |z| format!("{}x{}", z.nrows(), z.ncols())
                )
            ),
        });
    }
    Ok(())
}

/// In-sample R² against the full-sample mean of `y`.
fn is_r2(y: &Array2<f64>, residuals: &Array2<f64>, ybar: f64) -> f64 {
    let ssy: f64 = y.iter().map(|v| (v - ybar) * (v - ybar)).sum();
    if ssy == 0.0 {
        0.0
    } else {
        1.0 - residuals.iter().map(|r| r * r).sum::<f64>() / ssy
    }
}

/// Closed-form 3PRF (K&P IS Full, algebraic matrix formula). Collapses the
/// three passes into a single projection; normalizes `x` internally.
/// Numerically equivalent to `estimate_3prf_is_full` (the parity fixture pins
/// both):
///
/// ```text
/// alpha = Wxz(Wxz'SxxWxz)^-1 Wxz'X'J(T)y      yhat = J(T)X·alpha + ybar
/// ```
///
/// where `Wxz = J(N)·X'·J(T)·Z`, `Sxx = X'·J(T)·X`, and `J(k)` is the
/// centering matrix, applied as column centering rather than built dense.
/// Unlike the Scala original this does not also run passes 1 and 2 — the Rust
/// result type carries no `phi`/`sigma` state.
pub fn tprfClosedForm(
    y: &Array2<f64>,
    x: &Array2<f64>,
    z: &Array2<f64>,
) -> Result<Tprf3Result, Error> {
    let t = x.nrows();
    check_shapes(t, y, Some(z))?;
    let xn = standardize_columns(x);
    let jt_x = center_columns(xn.view()); // J(T)·Xn                      (T×N)
    let wxz = center_columns(jt_x.t().dot(z).view()); // J(N)·Xn'·J(T)·Z  (N×L)
    // Wxz'·Sxx·Wxz = (J(T)Xn·Wxz)'(J(T)Xn·Wxz): Gram form keeps it SPD for Chol.
    let g = jt_x.dot(&wxz); //                                            (T×L)
    let core = g.t().dot(&g); //                                          (L×L)
    let rhs = wxz.t().dot(&jt_x.t().dot(y)); // Wxz'·Xn'·J(T)·y           (L×1)
    let s = Chol::new(core.view())?.solve(rhs.view()); //                 (L×1)
    let alpha = wxz.dot(&s); //                                           (N×1)
    let ybar = mean_col(y.view());
    let forecasts = jt_x.dot(&alpha) + ybar; //                           (T×1)
    let residuals = y - &forecasts;
    let r_squared = is_r2(y, &residuals, ybar);
    Ok(Tprf3Result {
        forecasts,
        residuals,
        r_squared,
        rollfore: nan_col(t),
        encnew: f64::NAN,
    })
}

/// Fitted PLS-variant 3PRF model (see [`plsClosedForm`]), retaining the
/// pass-1/2/3 state plus the column mean and scale used to normalise `x`, so
/// [`predict`](Self::predict) takes a raw row — callers never reproduce the
/// internal normalisation.
#[derive(Debug, Clone)]
pub struct Pls3prfModel {
    /// (N×1) pass-1 loadings.
    pub phi: Array2<f64>,
    /// (T×1) pass-2 factor scores.
    pub sigma: Array2<f64>,
    /// (2×1) pass-3 coefficients: `[intercept, slope]`.
    pub beta: Array2<f64>,
    /// (T×1) in-sample fitted values.
    pub forecasts: Array2<f64>,
    /// (1×N) column means of the scaled `x`.
    pub colMean: Array2<f64>,
    /// (1×N) column std-devs of the raw `x`.
    pub colStd: Array2<f64>,
    pub rSquared: f64,
}

impl Pls3prfModel {
    /// Forecast for one raw (un-normalised) predictor row.
    ///
    /// Where the Scala original throws on a wrong-length row, this returns
    /// `NaN` (the crate never panics where Scala throws).
    pub fn predict(&self, row: &[f64]) -> f64 {
        let n = self.phi.nrows();
        if row.len() != n {
            return f64::NAN;
        }
        // sigma_new = phi'·xc / (phi'phi),  yhat = b0 + b1·sigma_new
        let phi_ss: f64 = self.phi.iter().map(|p| p * p).sum();
        let mut dot = 0.0;
        for (j, &v) in row.iter().enumerate() {
            dot += (v / self.colStd[[0, j]] - self.colMean[[0, j]]) * self.phi[[j, 0]];
        }
        self.beta[[0, 0]] + self.beta[[1, 0]] * (dot / phi_ss)
    }

    /// Forecast for each raw predictor row.
    pub fn predictAll(&self, rows: &[Vec<f64>]) -> Vec<f64> {
        rows.iter().map(|r| self.predict(r)).collect()
    }
}

/// Closed-form PLS-variant 3PRF: K&P autoproxy with `L = 1` and no intercept
/// in passes 1 and 2 — the 3PRF whose forecasts coincide with one-component
/// PLS-1 (see [`pls1Fit`]):
///
/// ```text
/// Phi   = Xc'y / (y'y)          pass 1  (N×1)
/// Sigma = Xc·Phi / (Phi'Phi)    pass 2  (T×1)
/// beta  = ols([1 Sigma], y)     pass 3  (2×1)
/// ```
///
/// where `Xc` is `x` scaled to unit column variance, then column-centred.
/// Requires NaN-free input (as does this whole module): the vectorised passes
/// cannot do per-regression NaN-row dropping.
pub fn plsClosedForm(y: &Array2<f64>, x: &Array2<f64>) -> Result<Pls3prfModel, Error> {
    check_shapes(x.nrows(), y, None)?;
    if x.iter().any(|v| v.is_nan()) || y.iter().any(|v| v.is_nan()) {
        return Err(Error::InvalidInput(
            "plsClosedForm requires NaN-free input".to_string(),
        ));
    }
    let col_std = std_cols(x);
    let xn = x / &col_std;
    let col_mean = mean_cols(xn.view());
    let xc = &xn - &col_mean;

    // Pass 1 — no intercept, proxy is y itself
    let yss = y.t().dot(y)[[0, 0]];
    let phi = xc.t().dot(y) / yss; //                                     (N×1)

    // Pass 2 — no intercept, design is phi
    let phi_ss = phi.t().dot(&phi)[[0, 0]];
    let sigma = xc.dot(&phi) / phi_ss; //                                 (T×1)

    // Pass 3 — with intercept, as in every 3PRF variant
    let xaug = with_intercept(sigma.view()); //                           (T×2)
    let beta = ols_solve(&xaug, y)?; //                                   (2×1)

    let forecasts = xaug.dot(&beta);
    let residuals = y - &forecasts;
    let r_squared = is_r2(y, &residuals, mean_col(y.view()));
    Ok(Pls3prfModel {
        phi,
        sigma,
        beta,
        forecasts,
        colMean: col_mean,
        colStd: col_std,
        rSquared: r_squared,
    })
}

/// [`plsClosedForm`] over plain arrays: `x` is row-major (T rows × N columns).
///
/// Fitted from the argument shapes a hand-rolled PLS-1 would take; predict
/// with `model.predict(row)`. The data is copied, so the returned model shares
/// no storage with the caller's slices.
pub fn pls1Fit(x: &[Vec<f64>], y: &[f64]) -> Result<Pls3prfModel, Error> {
    if x.is_empty() {
        return Err(Error::InvalidInput("x is empty".to_string()));
    }
    if x.len() != y.len() {
        return Err(Error::DimensionMismatch {
            expected: format!("{} rows of y", x.len()),
            actual: format!("{} rows", y.len()),
        });
    }
    let n = x[0].len();
    if x.iter().any(|row| row.len() != n) {
        return Err(Error::InvalidInput("ragged rows in x".to_string()));
    }
    let flat: Vec<f64> = x.iter().flatten().copied().collect();
    let xm = Array2::from_shape_vec((x.len(), n), flat)
        .map_err(|e| Error::InvalidInput(e.to_string()))?;
    let ym = Array2::from_shape_vec((y.len(), 1), y.to_vec())
        .map_err(|e| Error::InvalidInput(e.to_string()))?;
    plsClosedForm(&ym, &xm)
}

/// Forecasts only — simplified wrapper around the estimate functions, keyed by
/// the same procedure names as the Scala `forecast3prf`:
/// `"IS Full"`, `"OOS Recursive"`, `"OOS Cross Val"`.
///
/// `window` is `(before, total)` for Cross Val; `mintrain` is `(minSize, gap)`
/// for Recursive, with a negative `minSize` meaning `T/2` as in Scala. The
/// Scala `pls` flag and `gap != 0` are not ported (no such paths here).
pub fn forecast3prf(
    y: &Array2<f64>,
    x: &Array2<f64>,
    z: &Array2<f64>,
    procedure: &str,
    window: (usize, usize),
    mintrain: (i64, i64),
) -> Result<Array2<f64>, Error> {
    match procedure {
        "IS Full" => Ok(estimate_3prf_is_full(y, x, z)?.forecasts),
        "OOS Recursive" => {
            if mintrain.1 != 0 {
                return Err(Error::InvalidInput(
                    "mintrain gap != 0 is not ported".to_string(),
                ));
            }
            let mt = if mintrain.0 < 0 {
                x.nrows() / 2
            } else {
                usize::try_from(mintrain.0).unwrap_or(0)
            };
            Ok(estimate_3prf_oos_rec(y, x, z, mt)?.forecasts)
        }
        "OOS Cross Val" => Ok(estimate_3prf_oos_cv(y, x, z, window.0, window.1)?.forecasts),
        other => Err(Error::InvalidInput(format!("unknown procedure: {other}"))),
    }
}

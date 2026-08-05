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

use ndarray::Array2;
use ndarray::ArrayView2;
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

/// Copy every row of `m` except the contiguous block `[lo, hi)` — two block
/// copies rather than an index-gather through a mask array.
fn drop_rows(m: ArrayView2<'_, f64>, lo: usize, hi: usize) -> Array2<f64> {
    let rows = m.nrows();
    let lo = lo.min(rows);
    let hi = hi.min(rows).max(lo);
    let mut out = Array2::<f64>::zeros((rows - (hi - lo), m.ncols()));
    out.slice_mut(s![..lo, ..]).assign(&m.slice(s![..lo, ..]));
    out.slice_mut(s![lo.., ..]).assign(&m.slice(s![hi.., ..]));
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
    kept: ArrayView2<'_, f64>,
    lo: usize,
    hi: usize,
    full: &(Vec<f64>, Vec<f64>),
) -> Vec<f64> {
    let (rows, cols) = (src.nrows(), src.ncols());
    let keep = kept.nrows();
    if keep < 2 || keep * 2 < rows {
        return inv_std_cols(kept);
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
) -> Result<(Array2<f64>, f64), Error> {
    let t = x_raw.nrows();
    let l = z.ncols();
    if t < min_obs {
        return Ok((nan_col(t), f64::NAN));
    }

    // Pass 1: regress X on [1 | Z] → Phi (N×L, held transposed as the L×N tail)
    let dz = with_intercept(z);
    let ne1 = NormalEq::factor(dz.view())?;
    let mut rhs1 = dz.t().dot(&x_raw);
    scale_cols(&mut rhs1, inv_sd); // == dzᵀ·(X·D⁻¹)
    let beta1 = ne1.solve_normal(rhs1.view());
    let dp = with_intercept(beta1.slice(s![1.., ..]).t());

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
    let yhat = ds.dot(&beta3);

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
    Ok((yhat, yhatt))
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
    if sst != 0.0 { 1.0 - sse / sst } else { f64::NAN }
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
    let (forecasts, _) = t3prf_view(y.view(), x.view(), &inv_sd, z.view(), None, MIN_OBS)?;
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

    let fitted: Vec<(usize, f64, f64)> = (min_train + 1..t_total)
        .into_par_iter()
        .map(|t| {
            let end = t - 1; // training rows [0, t-1), matching the reference
            let win = x_norm.slice(s![..end, ..]);
            let inv_sd = inv_std_cols(win);
            let y_tr = y.slice(s![..end, ..]);
            let (_, yhatt) = t3prf_view(
                y_tr,
                win,
                &inv_sd,
                z.slice(s![..end, ..]),
                Some(x_norm.slice(s![t..=t, ..])),
                MIN_OBS,
            )?;
            Ok((t, yhatt, mean_col(y_tr)))
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
    let full = full_col_stats(x_norm.view());

    let fitted: Vec<(usize, f64, f64)> = (0..t_total)
        .into_par_iter()
        .map(|t| {
            // Signed arithmetic then clamp, as in the reference: a block starting
            // before row 0 is clipped, never wrapped.
            let lo_raw = t as i64 - win_left as i64;
            let lo = lo_raw.max(0) as usize;
            let hi = (lo_raw + win_right as i64).clamp(0, t_total as i64) as usize;
            let hi = hi.max(lo);

            // the kept rows are not contiguous, so X must be gathered; the
            // scaling still rides on `inv_sd` rather than a second pass
            let x_tr = drop_rows(x_norm.view(), lo, hi);
            let inv_sd = kept_inv_std(x_norm.view(), x_tr.view(), lo, hi, &full);
            let y_tr = drop_rows(y.view(), lo, hi);
            let z_tr = drop_rows(z.view(), lo, hi);
            let (_, yhatt) = t3prf_view(
                y_tr.view(),
                x_tr.view(),
                &inv_sd,
                z_tr.view(),
                Some(x_norm.slice(s![t..=t, ..])),
                MIN_OBS,
            )?;
            Ok((t, yhatt, mean_col(y_tr.view())))
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
    t3prf_view(
        y.view(),
        x_std.view(),
        &unit,
        z.view(),
        oos_x.map(|m| m.view()),
        MIN_OBS,
    )
}

"""
Three-Pass Regression Filter (Kelly & Pruitt, 2015)
Vectorized implementation: batch lstsq replaces per-column/per-row loops.

Identical API to tprf3.py; optional n_jobs parameter enables parallel OOS
via ThreadPoolExecutor (numpy lstsq releases the GIL, so threads provide
genuine parallelism without the overhead of process pickling).

Reference: Kelly, Bryan and Seth Pruitt (2015):
  "The Three-Pass Regression Filter: A New Approach to Forecasting
   Using Many Predictors," Journal of Econometrics.
"""

import numpy as np
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Optional, Union
import os


# ---------------------------------------------------------------------------
# Output structures
# ---------------------------------------------------------------------------

@dataclass
class PointEstimates:
    """Point estimates returned by estimate3prf_fast."""
    forecasts: np.ndarray          # (T, 1) forecast series
    ferrors:   np.ndarray          # (T, 1) forecast errors
    rsquare:   float               # R² vs. rolling mean (can be negative OOS)
    encnew:    float = np.nan      # ENC-NEW stat (OOS Recursive only)
    rollfore:  np.ndarray = field(
        default_factory=lambda: np.full((1, 1), np.nan)
    )                              # (T, 1) rolling historical mean forecasts
    alpha:     Optional[np.ndarray] = None   # (N, 1) IS Full predictor coefficients


@dataclass
class AvarEstimates:
    """Asymptotic variance estimates (IS Full only)."""
    alpha:     np.ndarray    # (N, N) asymp. covariance of alpha
    forecasts: np.ndarray    # (T, T) asymp. covariance of forecasts


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

# The K&P centering matrix J(n) = I_n - (1/n)·1·1' is never built explicitly:
# J(rows) @ M subtracts column means and M @ J(cols) subtracts row means,
# which is O(rows·cols) instead of O(rows²·cols).

def _center_cols(M: np.ndarray) -> np.ndarray:
    """J(M.rows) @ M — subtract column means."""
    return M - M.mean(axis=0, keepdims=True)


def _center_rows(M: np.ndarray) -> np.ndarray:
    """M @ J(M.cols) — subtract row means."""
    return M - M.mean(axis=1, keepdims=True)


def _nanstd_cols(X: np.ndarray) -> np.ndarray:
    """Column-wise sample std dev ignoring NaN; returns (1, N) row.
    Columns with std = 0 are set to 1 to avoid division by zero."""
    s = np.nanstd(X, axis=0, ddof=1)
    s[s == 0] = 1.0
    return s.reshape(1, -1)


def _std_cols(X: np.ndarray, has_nan: bool) -> np.ndarray:
    """Column-wise sample std dev; returns (1, N) row. Columns with std = 0 are
    set to 1 to avoid division by zero. Uses the much faster NaN-free `std` path
    when the data has no NaNs (detected once per call); only falls back to the
    `nanstd` machinery when NaNs are actually present."""
    s = np.nanstd(X, axis=0, ddof=1) if has_nan else X.std(axis=0, ddof=1)
    s[s == 0] = 1.0
    return s.reshape(1, -1)


def _col_mean(v: np.ndarray, has_nan: bool) -> float:
    """Mean of a column vector, skipping the `nanmean` overhead when NaN-free."""
    return float(np.nanmean(v)) if has_nan else float(v.mean())


def _ols(A: np.ndarray, Y: np.ndarray) -> np.ndarray:
    """Least-squares solution of A·B = Y via the normal equations.

    The 3PRF design matrices have only L+1 columns (typically 3), so AᵀA is a
    tiny SPD matrix and `solve` is several times faster than the SVD driver in
    `np.linalg.lstsq`. For full-rank, well-conditioned designs the result is
    identical to lstsq to machine precision; if AᵀA is exactly singular (a
    rank-deficient design), `solve` raises and we fall back to the rank-revealing
    `lstsq(rcond=None)` to preserve the original minimum-norm behaviour."""
    AtA = A.T @ A
    AtY = A.T @ Y
    try:
        return np.linalg.solve(AtA, AtY)
    except np.linalg.LinAlgError:
        return np.linalg.lstsq(A, Y, rcond=None)[0]


def _solve_normal(AtA: np.ndarray, AtY: np.ndarray) -> np.ndarray:
    """`_ols` for callers that already hold the cross-products `AᵀA` and `AᵀY`.

    The OOS windows below build those by downdating full-sample quantities, so
    the design matrix `A` is never assembled and the `lstsq(A, Y)` fallback in
    `_ols` is not available. The degenerate branch resolves the normal system
    instead; for a rank-deficient design that picks a different least-squares
    solution than `_ols` would, which only matters in a regime where the
    forecast is meaningless anyway."""
    try:
        return np.linalg.solve(AtA, AtY)
    except np.linalg.LinAlgError:
        return np.linalg.lstsq(AtA, AtY, rcond=None)[0]


@dataclass(frozen=True)
class _FullSample:
    """Full-sample quantities shared read-only across the windows of an OOS run.

    Every window of OOS Recursive and OOS Cross Val keeps all rows but one
    contiguous block — the suffix `[end, T)` for Recursive, an interior block for
    Cross Val — so its pass-1 cross products are the full-sample ones minus that
    block's contribution. That makes pass 1 an O(drop·N·L) downdate instead of an
    O(keep·N·L) product, which for a Cross Val window dropping a single row is
    the difference between O(N·L) and O(T·N·L)."""
    dZ:      np.ndarray   # [1 | Z]                (T, L+1)
    ZtZ:     np.ndarray   # dZᵀ·dZ                 (L+1, L+1)
    ZtX:     np.ndarray   # dZᵀ·Xn                 (L+1, N)
    col_sum: np.ndarray   # column sums of Xn      (N,)
    col_ssd: np.ndarray   # Σ(x − μ)² per column   (N,)


def _full_sample(Xn: np.ndarray, Z: np.ndarray) -> _FullSample:
    T = Xn.shape[0]
    dZ = np.column_stack([np.ones(T), Z])
    return _FullSample(
        dZ=dZ, ZtZ=dZ.T @ dZ, ZtX=dZ.T @ Xn,
        col_sum=Xn.sum(axis=0),
        col_ssd=((Xn - Xn.mean(axis=0)) ** 2).sum(axis=0),
    )


def _kept_inv_std(Xn: np.ndarray, lo: int, hi: int, full: _FullSample) -> np.ndarray:
    """Reciprocal column std devs of the kept set (every row but `[lo, hi)`).

    Derived from the precomputed full-sample stats via

        Σ_kept(x − m_keep)² = (Σ_all(x − μ)² − Σ_drop(x − μ)²) − keep·(m_keep − μ)²

    an O(drop·N) downdate rather than an O(keep·N) pass. Cancellation is
    negligible while the kept set is the majority; when it is not, the direct
    recompute is cheap in that regime anyway, so take it. Not bit-identical to
    the two-pass form — expect ~1e-13 drift, the same trade the Scala and Rust
    implementations make."""
    rows = Xn.shape[0]
    keep = rows - (hi - lo)
    if keep < 2 or keep * 2 < rows:
        kept = np.concatenate([Xn[:lo], Xn[hi:]]) if hi > lo else Xn
        return 1.0 / _std_cols(kept, False).ravel()

    mu       = full.col_sum / rows
    drop     = Xn[lo:hi]
    drop_sum = drop.sum(axis=0)
    drop_ssd = ((drop - mu) ** 2).sum(axis=0)
    mu_keep  = (full.col_sum - drop_sum) / keep
    shift    = mu_keep - mu
    # clamp the tiny negative that rounding can leave when a column is constant
    ss = np.maximum((full.col_ssd - drop_ssd) - keep * shift * shift, 0.0)
    s  = np.sqrt(ss / (keep - 1))
    s[s == 0.0] = 1.0
    return 1.0 / s


def _pass1_downdated(full: _FullSample, Xn: np.ndarray, lo: int, hi: int,
                     inv_sd: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Pass-1 cross products for the kept set, and the scaled right-hand side.

    Column scaling commutes with the product pass 1 takes against X:

        Zᵀ·(X·D⁻¹) == (Zᵀ·X)·D⁻¹

    so the scaling lands on the (L+1)×N result instead of on a keep×N copy of X,
    and the normalised window is never materialised."""
    dz_drop = full.dZ[lo:hi]
    ZtZ = full.ZtZ - dz_drop.T @ dz_drop
    ZtX = full.ZtX - dz_drop.T @ Xn[lo:hi]
    return ZtZ, ZtX * inv_sd


def _dp_from_pass1(ZtZ: np.ndarray, ZtX: np.ndarray, N: int) -> np.ndarray:
    """[1 | Phi] — the pass-2 design, N×(L+1)."""
    return np.column_stack([np.ones(N), _solve_normal(ZtZ, ZtX)[1:].T])


def _rec_step(full: _FullSample, Xn: np.ndarray, y: np.ndarray,
              t: int, end: int, has_nan: bool) -> float:
    """One OOS Recursive window: fit on the prefix `[0, end)`, forecast row `t`.

    The prefix is the full sample minus the suffix `[end, T)`, so pass 1 and the
    column std both come from downdating that block. Pass 2 runs in natural
    order — `dpᵀ·Xᵀ == (X·dp)ᵀ`, so the small end×(L+1) product is formed and
    transposed rather than materialising X's transpose — and the scaling rides on
    the N×(L+1) operand, since `(X·D⁻¹)·dp == X·(D⁻¹·dp)`."""
    T, N   = Xn.shape
    inv_sd = _kept_inv_std(Xn, end, T, full)
    dp     = _dp_from_pass1(*_pass1_downdated(full, Xn, end, T, inv_sd), N)
    dps    = dp * inv_sd[:, None]
    AtA2   = dp.T @ dp

    Sigma = _solve_normal(AtA2, (Xn[:end] @ dps).T)[1:].T      # end×L
    dS    = np.column_stack([np.ones(end), Sigma])
    beta  = _solve_normal(dS.T @ dS, dS.T @ y[:end])

    sigma_t = _solve_normal(AtA2, (Xn[t:t + 1] @ dps).T)[1:]
    return float(np.insert(sigma_t.ravel(), 0, 1.0) @ beta.ravel())


def _cv_step(full: _FullSample, Xn: np.ndarray, y: np.ndarray,
             t: int, lo: int, hi: int, has_nan: bool) -> tuple[float, float]:
    """One OOS Cross Val window: fit outside `[lo, hi)`, forecast row `t`.

    X is never gathered. Pass 2 is a *cross-sectional* regression, independent
    per row, so running it over all T rows costs one extra row and yields the
    held-out row's design as a by-product — the forecast comes free and the only
    per-window gather is `L+1` wide instead of `N` wide."""
    T, N   = Xn.shape
    inv_sd = _kept_inv_std(Xn, lo, hi, full)
    dp     = _dp_from_pass1(*_pass1_downdated(full, Xn, lo, hi, inv_sd), N)
    dps    = dp * inv_sd[:, None]
    AtA2   = dp.T @ dp

    Sigma = _solve_normal(AtA2, (Xn @ dps).T)[1:].T            # T×L, row t included
    if hi > lo:
        Sig_k = np.concatenate([Sigma[:lo], Sigma[hi:]])
        y_k   = np.concatenate([y[:lo], y[hi:]])
    else:
        Sig_k, y_k = Sigma, y

    dS   = np.column_stack([np.ones(Sig_k.shape[0]), Sig_k])
    beta = _solve_normal(dS.T @ dS, dS.T @ y_k)
    yhat = float(np.insert(Sigma[t], 0, 1.0) @ beta.ravel())
    return yhat, _col_mean(y_k, has_nan)


def _encnew(fore_err1: np.ndarray, fore_err2: np.ndarray) -> float:
    """Clark-McCracken (2001) ENC-NEW statistic."""
    e1, e2 = fore_err1.ravel(), fore_err2.ravel()
    loc = ~np.isnan(e1 + e2)
    P   = loc.sum()
    return float(P * np.nansum(e1[loc]**2 - e1[loc] * e2[loc]) / np.nansum(e2[loc]**2))


# ---------------------------------------------------------------------------
# Core vectorized engine  (X must already be column-normalised)
# ---------------------------------------------------------------------------

def _t3prf_core(
    y:     np.ndarray,
    X:     np.ndarray,          # pre-normalised (T, N)
    Z:     np.ndarray,          # proxy matrix  (T, L)
    oos_x: Optional[np.ndarray] = None,  # pre-normalised (1, N) OOS row
) -> tuple[np.ndarray, float]:
    """Vectorised 3-pass engine.  X (and oos_x) must already be column-normalised.

    Pass 1: lstsq([1|Z], X)   → Phi   (N×L)
    Pass 2: lstsq([1|Phi], Xᵀ) → Sigma (T×L)
    Pass 3: lstsq([1|Sigma], y) → beta, yhat
    """
    T, N = X.shape

    # Pass 1  — normal-equations solve (L+1 columns ⇒ tiny AᵀA), see _ols
    dZ  = np.column_stack([np.ones(T), Z])
    Phi = _ols(dZ, X)[1:].T     # N×L

    # Pass 2
    dP  = np.column_stack([np.ones(N), Phi])
    Sigma = _ols(dP, X.T)[1:].T # T×L

    # Pass 3
    dS   = np.column_stack([np.ones(T), Sigma])
    beta = _ols(dS, y)
    yhat = dS @ beta

    # OOS point forecast (reuse dP)
    yhatt = np.nan
    if oos_x is not None:
        sigma_t = _ols(dP, oos_x.T)[1:]
        yhatt = float(np.insert(sigma_t.ravel(), 0, 1.0) @ beta.ravel())

    return yhat, yhatt


def _t3prf_fast(
    y:     np.ndarray,
    X:     np.ndarray,
    Z:     np.ndarray,
    pls:   bool = False,
    oos_x: Optional[np.ndarray] = None,
) -> tuple[np.ndarray, float]:
    """Vectorised 3-pass with internal X normalisation (backward-compat wrapper)."""
    Xstd = _nanstd_cols(X)
    Xn   = X / Xstd
    if oos_x is not None:
        oos_x = oos_x / Xstd
    return _t3prf_core(y, Xn, Z, oos_x)


# ---------------------------------------------------------------------------
# Main API
# ---------------------------------------------------------------------------

def estimate3prf_fast(
    y:            np.ndarray,
    X:            np.ndarray,
    Z:            Union[np.ndarray, int],
    procedure:    str   = 'IS Full',
    window:       tuple = (0, 1),
    mintrain:     Union[int, tuple, None] = None,
    rollwin:      Union[tuple, list] = (30, 20, 0),
    pls:          bool  = False,
    compute_avar: bool  = False,
    n_jobs:       int   = 1,
) -> tuple[np.ndarray, PointEstimates, Optional[AvarEstimates]]:
    """Vectorised 3PRF — full output, identical API to estimate3prf in tprf3.py.

    Normalization matches tprf3.py exactly:
      - X is normalised globally once at entry.
      - OOS loops re-normalise each training window (per-window on top of global).

    Raises
    ------
    ValueError
        If X, y or Z contains NaN. The batched solves cannot carry the
        per-regression NaN masks that tprf3.estimate3prf applies, so NaN input is
        rejected rather than silently yielding an all-NaN series. Use
        tprf3.estimate3prf for data with missing values.

    Parameters
    ----------
    n_jobs : int
        Number of worker threads for OOS loops.
        1  = sequential (default).
        -1 = os.cpu_count() threads.
        numpy lstsq releases the GIL, so ThreadPoolExecutor provides genuine
        parallelism without the overhead of process-level pickling.
    """
    T, N = X.shape

    # ── autoproxy / Z ────────────────────────────────────────────────────────
    if np.isscalar(Z) and not isinstance(Z, np.ndarray):
        autoproxy, L, Z_mat = True, int(Z), None
    else:
        autoproxy, L, Z_mat = False, Z.shape[1], np.asarray(Z, dtype=float)
        pls = False    # pls only valid with autoproxy

    # ── normalise scalar options (matches tprf3.py) ──────────────────────────
    if mintrain is None:
        mintrain = (round(T / 2), 0)
    elif np.isscalar(mintrain):
        mintrain = (int(abs(mintrain)), 0)
    else:
        mintrain = (int(abs(mintrain[0])), int(abs(mintrain[1])) if len(mintrain) > 1 else 0)

    if len(rollwin) == 2:
        rollwin = (abs(int(rollwin[0])), abs(int(rollwin[1])), 0)
    else:
        rollwin = tuple(abs(int(v)) for v in rollwin)

    window = (abs(int(window[0])), abs(int(window[1])))

    # ── NaN input is rejected, not tolerated ─────────────────────────────────
    #
    # tprf3.py drops NaN rows per regression, and each of the N pass-1 fits may
    # drop a different row set — which is exactly what batching them into single
    # solves gives up. A NaN here therefore poisons AᵀA and every result derived
    # from it, and the function used to return an all-NaN series: a caller could
    # not tell an unsupported input from a model that fitted nothing. Fail
    # instead, and name the offending array. Use estimate3prf from tprf3.py for
    # NaN data; the Rust port declines the same way ("inputs must be NaN-free").
    offending = [(name, int(np.isnan(arr).sum()))
                 for name, arr in (("X", X), ("y", y), ("Z", Z_mat))
                 if arr is not None and np.isnan(arr).any()]
    if offending:
        found = ", ".join(f"{name} ({n} NaN)" for name, n in offending)
        raise ValueError(
            f"estimate3prf_fast does not support NaN input; found NaN in: {found}. "
            "The vectorized passes batch every per-column and per-row regression "
            "into one solve, which cannot carry a per-regression NaN mask. "
            "Use tprf3.estimate3prf, which filters NaN rows per regression."
        )
    # Constant from here on. The `has_nan` parameters downstream are kept rather
    # than stripped: they mark which steps would need a NaN-aware form if this
    # ever grows one, and `_std_cols`/`_col_mean` are shared with the NaN-aware
    # `_t3prf_fast` wrapper.
    has_nan = False

    # ── global normalisation (matches tprf3.py) ──────────────────────────────
    Xstd = _std_cols(X, has_nan)
    Xn   = X / Xstd

    forecasts = np.full((T, 1), np.nan)
    rollfore  = np.full((T, 1), np.nan)
    Z_final   = Z_mat

    nw = os.cpu_count() if n_jobs == -1 else max(1, n_jobs)

    # Full-sample cross products, shared read-only by every window of the two
    # procedures whose kept set is "all rows but one contiguous block". Built
    # only where the downdate is valid: autoproxy rebuilds Z on every inner
    # iteration so there is nothing to precompute, and the downdates have no
    # NaN-aware form, so those two cases keep the direct per-window path.
    # OOS Rolling is excluded for a different reason — its kept set is the small
    # contiguous window itself, so a direct pass is already the cheap side.
    full: Optional[_FullSample] = None
    if (not autoproxy) and (not has_nan) and procedure in ('OOS Recursive', 'OOS Cross Val'):
        full = _full_sample(Xn, Z_mat)

    # ── IS Full ───────────────────────────────────────────────────────────────
    if procedure == 'IS Full':
        if autoproxy:
            r0 = y.copy(); fore = None
            for j in range(L):
                fore, _ = _t3prf_core(y, Xn, r0)
                if j == L - 1:
                    Z_final = r0.copy()
                r0 = np.hstack([r0, y - fore])
            forecasts = fore
        else:
            forecasts, _ = _t3prf_core(y, Xn, Z_mat)
            Z_final = Z_mat

    # ── OOS Cross Val ─────────────────────────────────────────────────────────
    elif procedure == 'OOS Cross Val':

        def _cv_task(t: int) -> tuple[int, float, float]:
            # The dropped block is a contiguous index range; a boolean mask
            # selects the complement directly, avoiding setdiff1d's hash/sort.
            lo = max(t - window[0], 0)
            hi = min(t - window[0] + window[1], T)
            if full is not None:
                f_t, rf = _cv_step(full, Xn, y, t, lo, hi, has_nan)
                return t, f_t, rf
            if hi > lo:
                keep = np.ones(T, dtype=bool); keep[lo:hi] = False
                Xt0 = Xn[keep]; yt = y[keep]
                Zt  = Z_mat[keep] if Z_mat is not None else None
            else:
                Xt0 = Xn; yt = y; Zt = Z_mat
            Xts = _std_cols(Xt0, has_nan); Xt = Xt0 / Xts
            oos = Xn[t:t+1] / Xts
            rf  = _col_mean(yt, has_nan)
            if autoproxy:
                r0 = yt.copy(); tmpt = np.nan
                for _ in range(L):
                    tmp, tmpt = _t3prf_core(yt, Xt, r0, oos_x=oos)
                    r0 = np.hstack([yt - tmp, r0])
                return t, tmpt, rf
            else:
                _, f_t = _t3prf_core(yt, Xt, Zt, oos_x=oos)
                return t, f_t, rf

        if nw > 1:
            with ThreadPoolExecutor(max_workers=nw) as ex:
                for t, f_t, rf in ex.map(_cv_task, range(T)):
                    forecasts[t] = f_t; rollfore[t] = rf
        else:
            for t in range(T):
                _, f_t, rf = _cv_task(t)
                forecasts[t] = f_t; rollfore[t] = rf

    # ── OOS Recursive ─────────────────────────────────────────────────────────
    elif procedure == 'OOS Recursive':
        mt, gap = mintrain

        def _rec_task(t: int) -> tuple[int, float, float]:
            # Training rows are the contiguous prefix [0, end); slicing yields a
            # view (no copy) instead of fancy-indexing a freshly built index array.
            end = t - 1 - gap
            if full is not None:
                return t, _rec_step(full, Xn, y, t, end, has_nan), _col_mean(y[:end], has_nan)
            Xt0 = Xn[:end]; Xts = _std_cols(Xt0, has_nan); Xt = Xt0 / Xts
            oos = Xn[t:t+1] / Xts
            yt  = y[:end]
            rf  = _col_mean(yt, has_nan)
            if autoproxy:
                r0 = yt.copy(); tmpt = np.nan
                for _ in range(L):
                    tmp, tmpt = _t3prf_core(yt, Xt, r0, oos_x=oos)
                    r0 = np.hstack([yt - tmp, r0])
                return t, tmpt, rf
            else:
                _, f_t = _t3prf_core(yt, Xt, Z_mat[:end], oos_x=oos)
                return t, f_t, rf

        ts_range = range(mt + 1 + gap, T)
        if nw > 1:
            with ThreadPoolExecutor(max_workers=nw) as ex:
                for t, f_t, rf in ex.map(_rec_task, ts_range):
                    forecasts[t] = f_t; rollfore[t] = rf
        else:
            for t in ts_range:
                _, f_t, rf = _rec_task(t)
                forecasts[t] = f_t; rollfore[t] = rf

    # ── OOS Rolling ───────────────────────────────────────────────────────────
    elif procedure == 'OOS Rolling':
        win, min_nona, gap = rollwin

        def _roll_task(t: int) -> tuple[int, float, float]:
            # Rolling window is a contiguous index range; slice it as a view.
            lo  = max(t - win - gap, 0)
            hi  = min(t - 1 - gap, T)
            Xt0 = Xn[lo:hi]; Xts = _std_cols(Xt0, has_nan); Xt = Xt0 / Xts
            oos = Xn[t:t+1] / Xts
            yt  = y[lo:hi]
            rf  = _col_mean(yt, has_nan)
            if autoproxy:
                r0 = yt.copy(); tmpt = np.nan
                for _ in range(L):
                    tmp, tmpt = _t3prf_core(yt, Xt, r0, oos_x=oos)
                    r0 = np.hstack([yt - tmp, r0])
                return t, tmpt, rf
            else:
                _, f_t = _t3prf_core(yt, Xt, Z_mat[lo:hi], oos_x=oos)
                return t, f_t, rf

        ts_range = range(win + 1 + gap, T)
        if nw > 1:
            with ThreadPoolExecutor(max_workers=nw) as ex:
                for t, f_t, rf in ex.map(_roll_task, ts_range):
                    forecasts[t] = f_t; rollfore[t] = rf
        else:
            for t in ts_range:
                _, f_t, rf = _roll_task(t)
                forecasts[t] = f_t; rollfore[t] = rf

    else:
        raise ValueError(
            f"Unknown procedure: {procedure!r}. "
            "Choose from 'IS Full', 'OOS Recursive', 'OOS Cross Val', 'OOS Rolling'"
        )

    # ── point estimates ───────────────────────────────────────────────────────
    ferrors = y - forecasts
    loc     = ~np.isnan(ferrors.ravel())

    if procedure == 'IS Full':
        rsq      = 1.0 - np.nanvar(ferrors[loc], ddof=1) / np.nanvar(y[loc], ddof=1)
        enc_stat = np.nan
    else:
        denom    = np.nansum((y[loc].ravel() - rollfore[loc].ravel()) ** 2)
        rsq      = 1.0 - np.nansum(ferrors[loc].ravel() ** 2) / denom if denom != 0 else np.nan
        enc_stat = np.nan
        if procedure == 'OOS Recursive':
            enc_stat = _encnew(rollfore[loc].ravel(), ferrors[loc].ravel())

    alpha = None
    if procedure == 'IS Full' and Z_final is not None:
        # J(T)/J(N) products expressed as centering — no dense T×T matrix
        XtJt = _center_cols(Xn).T               # Xn' J(T)
        Wxz  = _center_cols(XtJt @ Z_final)     # J(N) Xn' J(T) Z
        Sxx  = XtJt @ Xn
        alpha = Wxz @ np.linalg.inv(Wxz.T @ Sxx @ Wxz) @ Wxz.T @ (XtJt @ y)

    pointests = PointEstimates(
        forecasts=forecasts, ferrors=ferrors, rsquare=rsq,
        encnew=enc_stat, rollfore=rollfore, alpha=alpha,
    )

    # ── asymptotic variance (IS Full only) ────────────────────────────────────
    avarests = None
    if compute_avar and procedure == 'IS Full' and Z_final is not None:
        # Every J(T)/J(N) factor expressed as centering; groupings preserve the
        # original term structure: [Z'J(T)XnJ(N)] [Xn'J(T)XnJ(N)] [Xn'J(T)Z]
        Xc        = _center_cols(Xn)             # J(T) Xn   (T×N)
        Zc        = _center_cols(Z_final)        # J(T) Z    (T×L)
        XtJtZ     = Xc.T @ Z_final               # Xn' J(T) Z       (N×L)
        ZtJtXnJn  = _center_rows(Zc.T @ Xn)      # Z' J(T) Xn J(N)  (L×N)
        XtJtXnJn  = _center_rows(Xc.T @ Xn)      # Xn' J(T) Xn J(N) (N×N)
        A  = (1 / T) * XtJtZ
        B  = (T**-3) * (N**-2) * (ZtJtXnJn @ XtJtXnJn @ XtJtZ)
        C  = (1 / T / N) * ZtJtXnJn
        omega_a = _center_cols(A) @ np.linalg.inv(B) @ C   # J(N) A B⁻¹ C
        Xm  = Xn.mean(axis=0)
        tmp = np.zeros((N, N))
        for ti in range(T):
            xrow = Xn[ti] - Xm
            tmp += (1 / T) * ferrors[ti].item() ** 2 * np.outer(xrow, xrow)
        alpha_avar = omega_a @ tmp @ omega_a.T
        avarests = AvarEstimates(
            alpha=alpha_avar,
            forecasts=(N**-2) * Xc @ alpha_avar @ Xc.T,    # J(T) Xn αA Xn' J(T)
        )

    return forecasts, pointests, avarests


def forecast3prf(
    y:         np.ndarray,
    X:         np.ndarray,
    Z:         Union[np.ndarray, int],
    procedure: str   = 'IS Full',
    window:    tuple = (0, 1),
    mintrain:  Union[int, tuple, None] = None,
    pls:       bool  = False,
) -> np.ndarray:
    """Three-Pass Regression Filter — forecasts only (fast vectorized version)."""
    forecasts, _, _ = estimate3prf_fast(
        y, X, Z, procedure=procedure,
        window=window, mintrain=mintrain, pls=pls,
    )
    return forecasts


# ---------------------------------------------------------------------------
# Quick smoke test
# ---------------------------------------------------------------------------
if __name__ == '__main__':
    import os as _os
    rng = np.random.default_rng(42)
    T, N, L = 200, 30, 2
    X = rng.standard_normal((T, N))
    y = rng.standard_normal((T, 1))
    Z = rng.standard_normal((T, L))

    fore, pt, _ = estimate3prf_fast(y, X, Z, procedure='IS Full')
    print(f"IS Full       R²: {pt.rsquare:.4f}  yhat[:3]: {fore[:3].ravel()}")
    print(f"              alpha shape: {pt.alpha.shape if pt.alpha is not None else None}")

    fore2, pt2, _ = estimate3prf_fast(y, X, 2, procedure='IS Full')
    print(f"Autoproxy     R²: {pt2.rsquare:.4f}  yhat[:3]: {fore2[:3].ravel()}")

    fore3, pt3, _ = estimate3prf_fast(y, X, Z, procedure='OOS Recursive', mintrain=100)
    valid = ~np.isnan(fore3.ravel())
    print(f"OOS Rec       R²: {pt3.rsquare:.4f}  n_forecasts: {valid.sum()}  encnew: {pt3.encnew:.4f}")

    nw = _os.cpu_count()
    fore4, pt4, _ = estimate3prf_fast(y, X, Z, procedure='OOS Recursive', mintrain=100, n_jobs=-1)
    valid4 = ~np.isnan(fore4.ravel())
    print(f"OOS Rec (par) R²: {pt4.rsquare:.4f}  n_forecasts: {valid4.sum()}  n_jobs={nw}")
    print(f"  results match: {np.allclose(fore3[valid], fore4[valid], atol=1e-10)}")

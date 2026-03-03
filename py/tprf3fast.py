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

def _j(n: int) -> np.ndarray:
    """Centering matrix: I_n - (1/n) * ones(n, n)."""
    return np.eye(n) - np.ones((n, n)) / n


def _nanstd_cols(X: np.ndarray) -> np.ndarray:
    """Column-wise sample std dev ignoring NaN; returns (1, N) row.
    Columns with std = 0 are set to 1 to avoid division by zero."""
    s = np.nanstd(X, axis=0, ddof=1)
    s[s == 0] = 1.0
    return s.reshape(1, -1)


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

    # Pass 1
    dZ  = np.column_stack([np.ones(T), Z])
    B1, _, _, _ = np.linalg.lstsq(dZ, X, rcond=None)
    Phi = B1[1:].T              # N×L

    # Pass 2
    dP  = np.column_stack([np.ones(N), Phi])
    B2, _, _, _ = np.linalg.lstsq(dP, X.T, rcond=None)
    Sigma = B2[1:].T            # T×L

    # Pass 3
    dS   = np.column_stack([np.ones(T), Sigma])
    beta, _, _, _ = np.linalg.lstsq(dS, y, rcond=None)
    yhat = dS @ beta

    # OOS point forecast (reuse dP)
    yhatt = np.nan
    if oos_x is not None:
        soo, _, _, _ = np.linalg.lstsq(dP, oos_x.T, rcond=None)
        sigma_t = soo[1:]
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

    # ── global normalisation (matches tprf3.py) ──────────────────────────────
    Xstd = _nanstd_cols(X)
    Xn   = X / Xstd

    forecasts = np.full((T, 1), np.nan)
    rollfore  = np.full((T, 1), np.nan)
    Z_final   = Z_mat

    nw = os.cpu_count() if n_jobs == -1 else max(1, n_jobs)

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
        all_idx = np.arange(T)

        def _cv_task(t: int) -> tuple[int, float, float]:
            drop = np.arange(t - window[0], t - window[0] + window[1])
            drop = drop[(drop >= 0) & (drop < T)]
            ts   = np.setdiff1d(all_idx, drop)
            Xt0  = Xn[ts]; Xts = _nanstd_cols(Xt0); Xt = Xt0 / Xts
            oos  = Xn[t:t+1] / Xts
            rf   = float(np.nanmean(y[ts]))
            if autoproxy:
                r0 = y[ts].copy(); tmpt = np.nan
                for _ in range(L):
                    tmp, tmpt = _t3prf_core(y[ts], Xt, r0, oos_x=oos)
                    r0 = np.hstack([y[ts] - tmp, r0])
                return t, tmpt, rf
            else:
                _, f_t = _t3prf_core(y[ts], Xt, Z_mat[ts], oos_x=oos)
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
            ts  = np.arange(0, t - 1 - gap)
            Xt0 = Xn[ts]; Xts = _nanstd_cols(Xt0); Xt = Xt0 / Xts
            oos = Xn[t:t+1] / Xts
            rf  = float(np.nanmean(y[ts]))
            if autoproxy:
                r0 = y[ts].copy(); tmpt = np.nan
                for _ in range(L):
                    tmp, tmpt = _t3prf_core(y[ts], Xt, r0, oos_x=oos)
                    r0 = np.hstack([y[ts] - tmp, r0])
                return t, tmpt, rf
            else:
                _, f_t = _t3prf_core(y[ts], Xt, Z_mat[ts], oos_x=oos)
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
            ts0 = np.arange(t - win - gap, t - 1 - gap)
            ts0 = ts0[(ts0 >= 0) & (ts0 < T)]
            Xt0 = Xn[ts0]; Xts = _nanstd_cols(Xt0); Xt = Xt0 / Xts
            oos = Xn[t:t+1] / Xts
            rf  = float(np.nanmean(y[ts0]))
            if autoproxy:
                r0 = y[ts0].copy(); tmpt = np.nan
                for _ in range(L):
                    tmp, tmpt = _t3prf_core(y[ts0], Xt, r0, oos_x=oos)
                    r0 = np.hstack([y[ts0] - tmp, r0])
                return t, tmpt, rf
            else:
                _, f_t = _t3prf_core(y[ts0], Xt, Z_mat[ts0], oos_x=oos)
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
        jt   = _j(T); jn = _j(N)
        XtJt = Xn.T @ jt
        Wxz  = jn @ XtJt @ Z_final
        Sxx  = XtJt @ Xn
        alpha = Wxz @ np.linalg.inv(Wxz.T @ Sxx @ Wxz) @ Wxz.T @ XtJt @ y

    pointests = PointEstimates(
        forecasts=forecasts, ferrors=ferrors, rsquare=rsq,
        encnew=enc_stat, rollfore=rollfore, alpha=alpha,
    )

    # ── asymptotic variance (IS Full only) ────────────────────────────────────
    avarests = None
    if compute_avar and procedure == 'IS Full' and Z_final is not None:
        jt = _j(T); jn = _j(N)
        A  = (1 / T) * Xn.T @ jt @ Z_final
        B  = ((T**-3) * (N**-2) *
              Z_final.T @ jt @ Xn @ jn @ Xn.T @ jt @ Xn @ jn @ Xn.T @ jt @ Z_final)
        C  = (1 / T / N) * Z_final.T @ jt @ Xn @ jn
        omega_a = jn @ A @ np.linalg.inv(B) @ C
        Xm  = Xn.mean(axis=0)
        tmp = np.zeros((N, N))
        for ti in range(T):
            xrow = Xn[ti] - Xm
            tmp += (1 / T) * float(ferrors[ti]) ** 2 * np.outer(xrow, xrow)
        alpha_avar = omega_a @ tmp @ omega_a.T
        avarests = AvarEstimates(
            alpha=alpha_avar,
            forecasts=(N**-2) * jt @ Xn @ alpha_avar @ Xn.T @ jt,
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

"""
Three-Pass Regression Filter (Kelly & Pruitt, 2015)
NumPy translation of estimate3PRF.m and forecast3PRF.m

Reference: Kelly, Bryan and Seth Pruitt (2015):
  "The Three-Pass Regression Filter: A New Approach to Forecasting
   Using Many Predictors," Journal of Econometrics.
"""

import numpy as np
from dataclasses import dataclass, field
from typing import Optional, Union


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _j(n: int) -> np.ndarray:
    """Centering matrix: I_n - (1/n) * ones(n, n)."""
    return np.eye(n) - np.ones((n, n)) / n


def _iota(n: int) -> np.ndarray:
    """Column vector of ones (n x 1)."""
    return np.ones((n, 1))


def _nanstd_cols(X: np.ndarray) -> np.ndarray:
    """Column-wise sample std dev ignoring NaN; returns (1, N) row.
    Columns with std = 0 are set to 1 to avoid division by zero."""
    s = np.nanstd(X, axis=0, ddof=1)
    s[s == 0] = 1.0
    return s.reshape(1, -1)


def _ols(y: np.ndarray, X: np.ndarray, min_obs: int = 10) -> np.ndarray:
    """OLS with NaN row filtering.

    Rows where y or any X column is NaN are dropped.
    Returns coefficient column vector (p x 1), or NaN vector if
    fewer than min_obs valid rows remain.
    """
    valid = ~np.isnan(y.ravel()) & ~np.any(np.isnan(X), axis=1)
    if valid.sum() >= min_obs:
        b, _, _, _ = np.linalg.lstsq(X[valid], y[valid], rcond=None)
        return b.reshape(-1, 1)
    return np.full((X.shape[1], 1), np.nan)


def _t3prf(
    y:       np.ndarray,
    X:       np.ndarray,
    Z:       np.ndarray,
    pls:     bool = False,
    oos_x:   Optional[np.ndarray] = None,
    min_obs: int = 10,
) -> tuple[np.ndarray, float]:
    """Core three-pass regression filter.

    Parameters
    ----------
    y       : (T, 1) response
    X       : (T, N) predictors (should already be column-normalised)
    Z       : (T, L) proxies
    pls     : if True, run passes 1 & 2 without intercept (PLS variant);
              X is centred internally
    oos_x   : (1, N) out-of-sample predictor row for point forecast
    min_obs : minimum valid observations required per regression

    Returns
    -------
    yhat  : (T, 1) in-sample fitted values
    yhatt : scalar out-of-sample forecast, or nan if oos_x is None
    """
    T, N = X.shape
    L    = Z.shape[1]
    Phi   = np.full((N, L), np.nan)
    Sigma = np.full((T, L), np.nan)

    if pls:
        Xm = np.nanmean(X, axis=0, keepdims=True)   # (1, N)
        Xd = X - Xm

    # Pass 1: regress each predictor column on Z
    for i in range(N):
        if pls:
            phi = _ols(Xd[:, i:i+1], Z, min_obs)
            Phi[i, :] = phi.ravel()
        else:
            phi = _ols(X[:, i:i+1], np.hstack([_iota(T), Z]), min_obs)
            Phi[i, :] = phi[1:].ravel()     # drop intercept

    # Pass 2: regress each cross-section row on Phi
    for t in range(T):
        if pls:
            sigma = _ols(Xd[t:t+1, :].T, Phi, min_obs)
            Sigma[t, :] = sigma.ravel()
        else:
            sigma = _ols(X[t:t+1, :].T, np.hstack([_iota(N), Phi]), min_obs)
            Sigma[t, :] = sigma[1:].ravel()  # drop intercept

    # Pass 3: regress y on Sigma
    Xaug = np.hstack([_iota(T), Sigma])
    beta  = _ols(y, Xaug, min_obs=1)
    yhat  = Xaug @ beta

    # Out-of-sample point forecast
    yhatt = np.nan
    if oos_x is not None:                    # oos_x is (1, N)
        if pls:
            sigma_t = _ols((oos_x - Xm).T, Phi, min_obs)
            yhatt = float(np.hstack([[1.0], sigma_t.ravel()]) @ beta.ravel())
        else:
            sigma_t = _ols(oos_x.T, np.hstack([_iota(N), Phi]), min_obs)
            yhatt = float(np.hstack([[1.0], sigma_t[1:].ravel()]) @ beta.ravel())

    return yhat, float(yhatt)


def _encnew(fore_err1: np.ndarray, fore_err2: np.ndarray) -> float:
    """Clark-McCracken (2001) ENC-NEW statistic.

    fore_err1 : forecast errors from the nested (smaller) model
    fore_err2 : forecast errors from the encompassing (larger) model
    """
    e1, e2 = fore_err1.ravel(), fore_err2.ravel()
    if e1.shape[0] != e2.shape[0]:
        raise ValueError("fore_err1 and fore_err2 must have the same length")
    loc = ~np.isnan(e1 + e2)
    P   = loc.sum()
    return float(P * np.nansum(e1[loc]**2 - e1[loc] * e2[loc]) / np.nansum(e2[loc]**2))


# ---------------------------------------------------------------------------
# Output structures
# ---------------------------------------------------------------------------

@dataclass
class PointEstimates:
    """Point estimates returned by estimate3prf."""
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
# Main API
# ---------------------------------------------------------------------------

def estimate3prf(
    y:            np.ndarray,
    X:            np.ndarray,
    Z:            Union[np.ndarray, int],
    procedure:    str   = 'IS Full',
    window:       tuple = (0, 1),
    mintrain:     Union[int, tuple, None] = None,
    rollwin:      Union[tuple, list] = (30, 20, 0),
    pls:          bool  = False,
    compute_avar: bool  = False,
) -> tuple[np.ndarray, Optional[PointEstimates], Optional[AvarEstimates]]:
    """Three-Pass Regression Filter — full estimation.

    Parameters
    ----------
    y            : (T, 1) target time series
    X            : (T, N) predictor matrix (normalised to unit variance internally)
    Z            : (T, L) proxy matrix, OR int for L auto-proxies
    procedure    : 'IS Full' | 'OOS Recursive' | 'OOS Cross Val' | 'OOS Rolling'
    window       : [before, total] dropped obs for OOS Cross Val (default [0, 1])
    mintrain     : smallest training size [, gap] for OOS Recursive (default T//2)
    rollwin      : [win_size, min_nonmissing, gap] for OOS Rolling (default [30,20,0])
    pls          : PLS variant — no intercept in passes 1 & 2 (autoproxy only)
    compute_avar : compute asymptotic variance estimates (IS Full only)

    Returns
    -------
    forecasts  : (T, 1)
    pointests  : PointEstimates
    avarests   : AvarEstimates or None
    """
    T, N = X.shape

    # Detect autoproxy
    if np.isscalar(Z) and not isinstance(Z, np.ndarray):
        autoproxy = True
        L = int(Z)
        Z_mat = None
    else:
        autoproxy = False
        Z_mat = np.asarray(Z, dtype=float)
        L = Z_mat.shape[1]
        pls = False        # pls only valid with autoproxy

    # Normalise option scalars
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

    # Normalise X to unit variance (column-wise, NaN-aware)
    Xstd = _nanstd_cols(X)    # (1, N)
    X    = X / Xstd

    forecasts = np.full((T, 1), np.nan)
    rollfore  = np.full((T, 1), np.nan)
    Z_final   = Z_mat          # updated for IS Full autoproxy

    # ---- IS Full -------------------------------------------------------
    if procedure == 'IS Full':
        if autoproxy:
            r0 = y.copy()
            fore = None
            for j in range(L):
                fore, _ = _t3prf(y, X, r0, pls)
                if j == L - 1:
                    Z_final = r0.copy()
                r0 = np.hstack([r0, y - fore])
            forecasts = fore
        else:
            forecasts, _ = _t3prf(y, X, Z_mat, pls)
            Z_final = Z_mat

    # ---- OOS Cross Val -------------------------------------------------
    elif procedure == 'OOS Cross Val':
        all_idx = np.arange(T)
        for t in range(T):
            drop = np.arange(t - window[0], t - window[0] + window[1])
            drop = drop[(drop >= 0) & (drop < T)]
            ts   = np.setdiff1d(all_idx, drop)
            yt = y[ts]; Xt = X[ts]
            Xts = _nanstd_cols(Xt)
            Xt  = Xt / Xts
            oos = X[t:t+1] / Xts
            if autoproxy:
                r0 = yt.copy()
                tmpt = np.nan
                for _ in range(L):
                    tmp, tmpt = _t3prf(yt, Xt, r0, pls, oos)
                    r0 = np.hstack([yt - tmp, r0])
                forecasts[t] = tmpt
            else:
                _, tmpt = _t3prf(yt, Xt, Z_mat[ts], pls, oos)
                forecasts[t] = tmpt
            rollfore[t] = np.nanmean(yt)

    # ---- OOS Recursive -------------------------------------------------
    elif procedure == 'OOS Recursive':
        mt, gap = mintrain
        for t in range(mt + 1 + gap, T):
            ts  = np.arange(0, t - 1 - gap)
            yt  = y[ts]; Xt = X[ts]
            Xts = _nanstd_cols(Xt)
            Xt  = Xt / Xts
            oos = X[t:t+1] / Xts
            if autoproxy:
                r0 = yt.copy()
                tmpt = np.nan
                for _ in range(L):
                    tmp, tmpt = _t3prf(yt, Xt, r0, pls, oos, mt)
                    r0 = np.hstack([yt - tmp, r0])
                forecasts[t] = tmpt
            else:
                _, tmpt = _t3prf(yt, Xt, Z_mat[ts], pls, oos)
                forecasts[t] = tmpt
            rollfore[t] = np.nanmean(yt)

    # ---- OOS Rolling ---------------------------------------------------
    elif procedure == 'OOS Rolling':
        win, min_nona, gap = rollwin
        for t in range(win + 1 + gap, T):
            ts  = np.arange(t - win - gap, t - 1 - gap)
            ts  = ts[(ts >= 0) & (ts < T)]
            yt  = y[ts]; Xt = X[ts]
            Xts = _nanstd_cols(Xt)
            Xt  = Xt / Xts
            oos = X[t:t+1] / Xts
            if autoproxy:
                r0 = yt.copy()
                tmpt = np.nan
                for _ in range(L):
                    tmp, tmpt = _t3prf(yt, Xt, r0, pls, oos, min_nona)
                    r0 = np.hstack([yt - tmp, r0])
                forecasts[t] = tmpt
            else:
                _, tmpt = _t3prf(yt, Xt, Z_mat[ts], pls, oos)
                forecasts[t] = tmpt
            rollfore[t] = np.nanmean(yt)

    else:
        raise ValueError(
            f"Unknown procedure: {procedure!r}. "
            "Choose from 'IS Full', 'OOS Recursive', 'OOS Cross Val', 'OOS Rolling'"
        )

    # ---- Point estimates -----------------------------------------------
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

    # IS Full alpha (N x 1) predictor coefficient
    alpha = None
    if procedure == 'IS Full' and Z_final is not None:
        jt  = _j(T); jn = _j(N)
        XtJt = X.T @ jt             # (N, T)
        Wxz  = jn @ XtJt @ Z_final  # (N, L)
        Sxx  = XtJt @ X             # (N, N)
        alpha = Wxz @ np.linalg.inv(Wxz.T @ Sxx @ Wxz) @ Wxz.T @ XtJt @ y

    pointests = PointEstimates(
        forecasts=forecasts, ferrors=ferrors, rsquare=rsq,
        encnew=enc_stat, rollfore=rollfore, alpha=alpha,
    )

    # ---- Asymptotic variance (IS Full only) ----------------------------
    avarests = None
    if compute_avar and procedure == 'IS Full' and Z_final is not None:
        jt = _j(T); jn = _j(N)
        A  = (1 / T) * X.T @ jt @ Z_final                                   # (N, L)
        B  = ((T**-3) * (N**-2) *
              Z_final.T @ jt @ X @ jn @ X.T @ jt @ X @ jn @ X.T @ jt @ Z_final)  # (L, L)
        C  = (1 / T) * (1 / N) * Z_final.T @ jt @ X @ jn                    # (L, N)
        omega_a = jn @ A @ np.linalg.inv(B) @ C                              # (N, N)
        Xm  = X.mean(axis=0)
        tmp = np.zeros((N, N))
        for ti in range(T):
            xrow = X[ti] - Xm
            tmp += (1 / T) * float(ferrors[ti]) ** 2 * np.outer(xrow, xrow)
        alpha_avar = omega_a @ tmp @ omega_a.T
        avarests = AvarEstimates(
            alpha=alpha_avar,
            forecasts=(N**-2) * jt @ X @ alpha_avar @ X.T @ jt,
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
    """Three-Pass Regression Filter — forecasts only.

    Simplified interface that returns only the (T, 1) forecast series.
    See estimate3prf for the full output including point/asymptotic estimates.
    Supports 'IS Full', 'OOS Recursive', and 'OOS Cross Val'.
    """
    forecasts, _, _ = estimate3prf(
        y, X, Z, procedure=procedure,
        window=window, mintrain=mintrain, pls=pls,
    )
    return forecasts


# ---------------------------------------------------------------------------
# Quick smoke test
# ---------------------------------------------------------------------------
if __name__ == '__main__':
    rng = np.random.default_rng(42)
    T, N, L = 200, 30, 2
    X = rng.standard_normal((T, N))
    y = rng.standard_normal((T, 1))
    Z = rng.standard_normal((T, L))

    fore, pt, _ = estimate3prf(y, X, Z, procedure='IS Full')
    print(f"IS Full  R²: {pt.rsquare:.4f}  yhat[:3]: {fore[:3].ravel()}")

    fore2, pt2, _ = estimate3prf(y, X, 2, procedure='IS Full')
    print(f"Autoproxy R²: {pt2.rsquare:.4f}  yhat[:3]: {fore2[:3].ravel()}")

    fore3, pt3, _ = estimate3prf(y, X, Z, procedure='OOS Recursive', mintrain=100)
    valid = ~np.isnan(fore3.ravel())
    print(f"OOS Rec  R²: {pt3.rsquare:.4f}  n_forecasts: {valid.sum()}")

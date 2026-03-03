#!/usr/bin/env python3
"""
Generate test fixtures for cross-validating py/tprf3.py against
src/main/scala/apps/Tprf3.scala.

Writes CSV files to test-data/tprf3/ (relative to workspace root):
  y.csv, X.csv, Z.csv          -- input data
  fore_is.csv                  -- IS Full forecasts (explicit Z)
  fore_is_auto.csv             -- IS Full forecasts (autoproxy L=2)
  fore_oos_rec.csv             -- OOS Recursive forecasts
  fore_oos_cv.csv              -- OOS Cross Val forecasts
"""

import os, sys
import numpy as np

# Resolve workspace root (one level up from this script's directory)
script_dir = os.path.dirname(os.path.abspath(__file__))
root_dir   = os.path.dirname(script_dir)
out_dir    = os.path.join(root_dir, 'test-data', 'tprf3')
os.makedirs(out_dir, exist_ok=True)

sys.path.insert(0, script_dir)
from tprf3 import estimate3prf


def save(name: str, arr: np.ndarray):
    path = os.path.join(out_dir, name)
    np.savetxt(path, arr, delimiter=',', fmt='%.17g')
    print(f"  wrote {path}  shape={arr.shape}")


# ── Generate data ────────────────────────────────────────────────────────────
rng = np.random.default_rng(12345)
T, N, L = 100, 20, 2
X = rng.standard_normal((T, N))
y = rng.standard_normal((T, 1))
Z = rng.standard_normal((T, L))

print("Saving inputs ...")
save('y.csv', y)
save('X.csv', X)
save('Z.csv', Z)

# ── IS Full (explicit Z) ─────────────────────────────────────────────────────
fore, pt, _ = estimate3prf(y, X, Z, procedure='IS Full')
print(f"\nIS Full        R²={pt.rsquare:.6f}  fore[0]={fore[0,0]:.10f}")
save('fore_is.csv', fore)

# ── IS Full (autoproxy L=2) ──────────────────────────────────────────────────
fore_a, pt_a, _ = estimate3prf(y, X, L, procedure='IS Full')
print(f"IS Full auto   R²={pt_a.rsquare:.6f}  fore[0]={fore_a[0,0]:.10f}")
save('fore_is_auto.csv', fore_a)

# ── OOS Recursive ────────────────────────────────────────────────────────────
fore_r, pt_r, _ = estimate3prf(y, X, Z, procedure='OOS Recursive', mintrain=50)
n_valid = int(np.sum(~np.isnan(fore_r)))
print(f"OOS Recursive  R²={pt_r.rsquare:.6f}  n_valid={n_valid}")
save('fore_oos_rec.csv', fore_r)

# ── OOS Cross Val ────────────────────────────────────────────────────────────
fore_cv, pt_cv, _ = estimate3prf(y, X, Z, procedure='OOS Cross Val')
n_valid_cv = int(np.sum(~np.isnan(fore_cv)))
print(f"OOS Cross Val  R²={pt_cv.rsquare:.6f}  n_valid={n_valid_cv}")
save('fore_oos_cv.csv', fore_cv)

print("\nDone.  Run Scala comparison with:  sbt 'runMain apps.Tprf3Compare'")

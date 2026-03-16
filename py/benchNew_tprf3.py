#!/usr/bin/env python3
"""
Benchmark py/tprf3.py and py/tprf3fast.py to evaluate performance and parity.
"""

import sys, os, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np
from tprf3 import estimate3prf as estimate_orig
from tprf3fast import estimate3prf_fast as estimate_fast, _t3prf_fast


def bench(loops: int, fn) -> float:
    """Returns ms-per-call for fn() run loops times."""
    t0 = time.perf_counter()
    for _ in range(loops):
        fn()
    return (time.perf_counter() - t0) * 1000.0 / loops

def run(label: str, T: int, N: int, L: int, warmup: int, loops: int):
    print(f"\n── {label}  (T={T}  N={N}  L={L}  warmup={warmup}  loops={loops}) ──")
    rng = np.random.default_rng(0)
    X = rng.standard_normal((T, N))
    y = rng.standard_normal((T, 1))
    Z = rng.standard_normal((T, L))

    # ── warm-up (Optimized to avoid the 'Large' bottleneck) ──────────────────
    print("  warming up ... ", end="", flush=True)
    # Warm up IS Full (Fast)
    for _ in range(warmup):
        estimate_orig(y, X, Z, procedure="IS Full")
    
    # Only one pass of the heavy OOS loops for warmup to prime the system
    estimate_orig(y, X, Z, procedure="OOS Recursive", mintrain=T // 2)
    print("done")

    # ── Parity Check ─────────────────────────────────────────────────────────
    print("  verifying results ... ", end="", flush=True)
    # Get yhat from the original implementation
    # (Handling the 'too many values to unpack' by taking the first element)
    res_orig = estimate_orig(y, X, Z, procedure="IS Full")
    yhat_orig = res_orig[0] if isinstance(res_orig, (list, tuple)) else res_orig
    
    # Get yhat from the new fast implementation
    yhat_fast, _ = _t3prf_fast(y, X, Z)

    if np.allclose(yhat_orig, yhat_fast, atol=1e-8):
        print("MATCHED ✅")
    else:
        print("FAILED ❌")
        # diff = np.abs(yhat_orig - yhat_fast).max()
        # print(f"    Max Abs Diff: {diff}")

    # ── IS Full ──────────────────────────────────────────────────────────────
 #  ms_is_orig = bench(loops, lambda: estimate_orig(y, X, Z, procedure="IS Full"))
    ms_is_fast = bench(loops, lambda: _t3prf_fast(y, X, Z))
 #  print(f"  [Python]      {'estimate3prf IS Full':<20}  {ms_is_orig:8.2f} ms/call")
    print(f"  [Python Fast] {'estimate3prf IS Full':<20}  {ms_is_fast:8.2f} ms/call") #  ({ms_is_orig/ms_is_fast:.1f}x speedup)")

    # ── OOS Recursive ────────────────────────────────────────────────────────
    oos_loops = max(1, loops // 10)
  # ms_rec_orig = bench(oos_loops, lambda: estimate_orig(y, X, Z, procedure="OOS Recursive", mintrain=T // 2))
    ms_rec_fast = bench(oos_loops, lambda: estimate_fast(y, X, Z, procedure="OOS Recursive", mintrain=T // 2))
  # print(f"  [Python]      {'estimate3prf OOS Rec':<20}  {ms_rec_orig:8.2f} ms/call  (loops={oos_loops})")
    print(f"  [Python Fast] {'estimate3prf OOS Rec':<20}  {ms_rec_fast:8.2f} ms/call  (loops={oos_loops})") # , {ms_rec_orig/ms_rec_fast:.1f}x speedup)")

    # ── OOS Cross Val ────────────────────────────────────────────────────────
  # ms_cv_orig = bench(oos_loops, lambda: estimate_orig(y, X, Z, procedure="OOS Cross Val"))
    ms_cv_fast = bench(oos_loops, lambda: estimate_fast(y, X, Z, procedure="OOS Cross Val"))
  # print(f"  [Python]      {'estimate3prf OOS CV':<20}  {ms_cv_orig:8.2f} ms/call  (loops={oos_loops})")
    print(f"  [Python Fast] {'estimate3prf OOS CV':<20}  {ms_cv_fast:8.2f} ms/call  (loops={oos_loops})") # , {ms_cv_orig/ms_cv_fast:.1f}x speedup)")


if __name__ == "__main__":
    run("Small", T=200, N=30, L=2, warmup=5,  loops=50)
    run("Large", T=650, N=40, L=2, warmup=3,  loops=20)


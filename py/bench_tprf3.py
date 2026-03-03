#!/usr/bin/env python3
"""
Benchmark py/tprf3.py (loop-based) and py/tprf3fast.py (vectorized) against
Tprf3Bench.scala results. Mirrors the same sizes, scenarios, and warmup/loop counts.

Rows:
  [Python]      estimate3prf      — loop-based OLS (tprf3.py)
  [Python Fast] estimate3prf_fast — vectorized lstsq (tprf3fast.py), sequential

Note: estimate3prf_fast also accepts n_jobs=-1 for ThreadPoolExecutor parallelism,
but this requires OPENBLAS_NUM_THREADS=1 set before Python starts to avoid
OpenBLAS/thread contention.  Run the benchmark as:
    OPENBLAS_NUM_THREADS=1 python bench_tprf3.py
to enable the [Python Parallel] row via bench_tprf3_parallel.py (if present).
"""

import sys, os, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np
from tprf3     import estimate3prf
from tprf3fast import estimate3prf_fast


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

    # ── warm-up ──────────────────────────────────────────────────────────────
    print("  warming up ... ", end="", flush=True)
    for _ in range(warmup):
        estimate3prf(y, X, Z, procedure="IS Full")
        estimate3prf_fast(y, X, Z, procedure="IS Full")
    estimate3prf(y, X, Z, procedure="OOS Recursive", mintrain=T // 2)
    estimate3prf_fast(y, X, Z, procedure="OOS Recursive", mintrain=T // 2)
    print("done")

    oos_loops = max(1, loops // 10)

    # ── IS Full ───────────────────────────────────────────────────────────────
    ms_is      = bench(loops, lambda: estimate3prf(y, X, Z, procedure="IS Full"))
    ms_is_fast = bench(loops, lambda: estimate3prf_fast(y, X, Z, procedure="IS Full"))
    print(f"  [Python]      {'estimate3prf IS Full':<22}  {ms_is:8.2f} ms/call")
    print(f"  [Python Fast] {'estimate3prf IS Full':<22}  {ms_is_fast:8.2f} ms/call")

    # ── OOS Recursive ─────────────────────────────────────────────────────────
    ms_rec      = bench(oos_loops, lambda: estimate3prf(y, X, Z, procedure="OOS Recursive", mintrain=T // 2))
    ms_rec_fast = bench(oos_loops, lambda: estimate3prf_fast(y, X, Z, procedure="OOS Recursive", mintrain=T // 2))
    print(f"  [Python]      {'estimate3prf OOS Rec':<22}  {ms_rec:8.2f} ms/call  (loops={oos_loops})")
    print(f"  [Python Fast] {'estimate3prf OOS Rec':<22}  {ms_rec_fast:8.2f} ms/call  (loops={oos_loops})")

    # ── OOS Cross Val ─────────────────────────────────────────────────────────
    ms_cv      = bench(oos_loops, lambda: estimate3prf(y, X, Z, procedure="OOS Cross Val"))
    ms_cv_fast = bench(oos_loops, lambda: estimate3prf_fast(y, X, Z, procedure="OOS Cross Val"))
    print(f"  [Python]      {'estimate3prf OOS CV':<22}  {ms_cv:8.2f} ms/call  (loops={oos_loops})")
    print(f"  [Python Fast] {'estimate3prf OOS CV':<22}  {ms_cv_fast:8.2f} ms/call  (loops={oos_loops})")


if __name__ == "__main__":
    run("Small", T=200, N=30, L=2, warmup=5,  loops=50)
    run("Large", T=650, N=40, L=2, warmup=3,  loops=20)

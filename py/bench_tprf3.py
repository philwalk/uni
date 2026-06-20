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
    """Returns the MEDIAN ms-per-call over `loops` individually timed runs.
    Median, not mean: a single GC/scheduler spike then shifts one sample instead
    of inflating the whole reading (the mean form did the latter)."""
    times = []
    for _ in range(loops):
        t0 = time.perf_counter()
        fn()
        times.append((time.perf_counter() - t0) * 1000.0)
    times.sort()
    return times[len(times) // 2]


def warm(calls: int, fn) -> None:
    """Spin up the OpenBLAS thread pool / warm caches for fn before timing it."""
    for _ in range(calls):
        fn()


def run(label: str, T: int, N: int, L: int, warmup: int, loops: int):
    print(f"\n── {label}  (T={T}  N={N}  L={L}  warmup={warmup}  loops={loops}) ──")
    rng = np.random.default_rng(0)
    X = rng.standard_normal((T, N))
    y = rng.standard_normal((T, 1))
    Z = rng.standard_normal((T, L))

    # No JIT in Python, but warm each measured op right before timing it so
    # OpenBLAS thread-pool spin-up / CPU ramp / cache misses don't land in the
    # samples — and warm OOS Cross Val too (previously only OOS Recursive was).
    oos_warm  = 10
    oos_loops = 25

    print("  warming up ... ", end="", flush=True)

    # ── IS Full ───────────────────────────────────────────────────────────────
    warm(warmup, lambda: estimate3prf_fast(y, X, Z, procedure="IS Full"))
    ms_is_fast = bench(loops, lambda: estimate3prf_fast(y, X, Z, procedure="IS Full"))

    # ── OOS Recursive ─────────────────────────────────────────────────────────
    warm(oos_warm, lambda: estimate3prf_fast(y, X, Z, procedure="OOS Recursive", mintrain=T // 2))
    ms_rec_fast = bench(oos_loops, lambda: estimate3prf_fast(y, X, Z, procedure="OOS Recursive", mintrain=T // 2))

    # ── OOS Cross Val ─────────────────────────────────────────────────────────
    warm(oos_warm, lambda: estimate3prf_fast(y, X, Z, procedure="OOS Cross Val"))
    ms_cv_fast = bench(oos_loops, lambda: estimate3prf_fast(y, X, Z, procedure="OOS Cross Val"))

    print("done")
    print(f"  [Python Fast] {'estimate3prf IS Full':<22}  {ms_is_fast:8.2f} ms/call")
    print(f"  [Python Fast] {'estimate3prf OOS Rec':<22}  {ms_rec_fast:8.2f} ms/call  (loops={oos_loops})")
    print(f"  [Python Fast] {'estimate3prf OOS CV':<22}  {ms_cv_fast:8.2f} ms/call  (loops={oos_loops})")


if __name__ == "__main__":
    run("Small", T=200, N=30, L=2, warmup=5,  loops=50)
    run("Large", T=650, N=40, L=2, warmup=3,  loops=20)

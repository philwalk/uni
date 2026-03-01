#!/usr/bin/env -S python3
"""
NumPy benchmark — counterpart to bench.sc (MatD/JVM).

Run:  python py/bench.py
Deps: numpy  (pip install numpy)

Design notes
------------
* 10 warmup iterations (Python has no JIT, but memory/cache matters)
* 20 timed iterations; reports mean and min in ms
* Same 7 operations as bench.sc for direct comparison
* Matrix sizes chosen to be meaningful but not hour-long
"""

import numpy as np
import time, sys

N  = 1000   # square size for element-wise / reduction ops
MM = 512    # matmul side length (512³ ≈ 134M multiplications)

WARMUP = 10
ITERS  = 20

# ── timing helper ────────────────────────────────────────────────────────────

def bench(label, fn):
    for _ in range(WARMUP):
        fn()
    times = []
    for _ in range(ITERS):
        t0 = time.perf_counter_ns()
        fn()
        times.append((time.perf_counter_ns() - t0) / 1e6)  # → ms
    mean_ = sum(times) / ITERS
    min_  = min(times)
    print(f"  {label:<42}  mean={mean_:8.2f} ms   min={min_:8.2f} ms")

# ── pre-build matrices so allocation is excluded from timed ops ──────────────

np.random.seed(42)
A  = np.random.randn(MM, MM)
B  = np.random.randn(MM, MM)
M  = np.random.randn(N,  N)
M2 = np.random.randn(N,  N)

# ── sigmoid / relu helpers ────────────────────────────────────────────────────

def sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))

def relu(x):
    return np.maximum(x, 0.0)

# ── benchmark suite ──────────────────────────────────────────────────────────

print(f"\nNumPy {np.__version__}   Python {sys.version.split()[0]}")
print(f"N={N}  MM={MM}  warmup={WARMUP}  iters={ITERS}\n")
print("  " + "-" * 72)

# 1. Random generation — tests RNG (both implementations use PCG64)
bench("randn(1000×1000)",
      lambda: np.random.randn(N, N))

# 2. Matrix multiply — NumPy calls OpenBLAS; MatD calls OpenBLAS via bytedeco
bench("matmul 512×512 @ 512×512",
      lambda: A @ B)

# 3. Sigmoid — 1/(1+e^-x) over 1M elements; SIMD in NumPy, JIT in MatD
bench("sigmoid(1000×1000)",
      lambda: sigmoid(M))

# 4. ReLU — max(x,0) over 1M elements; branch-free SIMD in NumPy
bench("relu(1000×1000)",
      lambda: relu(M))

# 5. Element-wise add — simplest possible element-wise kernel
bench("add 1000×1000 + 1000×1000",
      lambda: M + M2)

# 6. Full reduction — tests memory bandwidth and vectorised summation
bench("sum(1000×1000)",
      lambda: M.sum())

# 7. Transpose — O(1) view (no copy) in both NumPy and MatD
bench("transpose 1000×1000  [O(1)]",
      lambda: M.T)

print("  " + "-" * 72)
print("\nNote: transpose is O(1) in both libraries (stride flip, no copy).")
print("      MatD matmul uses OpenBLAS via bytedeco (org.bytedeco:openblas-platform).")
print("      NumPy matmul uses OpenBLAS (or MKL if present in your install).\n")

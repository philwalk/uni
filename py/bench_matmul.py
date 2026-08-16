#!/usr/bin/env python3
"""NumPy half of the matmul contract measurement.

Counterpart to `uni.data.MatmulProbe` (Scala) and `rust/src/bin/bench_matmul.rs`. Same
shapes, same label format, so the three join on `matmul@numpy/<shape>`.

`A @ B` is whatever BLAS this numpy was built against, with its default threading; the
config line says which. Set OPENBLAS_NUM_THREADS=1 to measure single-threaded.
"""
import time
import numpy as np

SHAPES = [
    ("8", 8, 8, 8),
    ("16", 16, 16, 16),
    ("32", 32, 32, 32),
    ("64", 64, 64, 64),
    ("128", 128, 128, 128),
    ("256", 256, 256, 256),
    ("512", 512, 512, 512),
    ("512x8x512", 512, 8, 512),
    ("512x512x8", 512, 512, 8),
]


def min_ms(fn, runs=7, target_ms=2.0):
    for _ in range(5):
        fn()
    t0 = time.perf_counter()
    fn()
    est = (time.perf_counter() - t0) * 1000.0
    reps = max(1, min(20000, int(target_ms / max(est, 1e-6))))
    best = float("inf")
    for _ in range(runs):
        s = time.perf_counter()
        for _ in range(reps):
            fn()
        ms = (time.perf_counter() - s) * 1000.0 / reps
        best = min(best, ms)
    return best


def blas_name():
    try:
        d = np.show_config("dicts")
        return ((d.get("Build Dependencies") or {}).get("blas") or {}).get("name", "?")
    except Exception:  # noqa: BLE001 - older numpy has no dict form
        return "?"


def main():
    print(f"config: numpy={np.__version__} blas={blas_name()}")
    rng = np.random.default_rng(42)
    for label, ra, ca, cb in SHAPES:
        a = rng.standard_normal((ra, ca))
        b = rng.standard_normal((ca, cb))
        ms = min_ms(lambda: (a @ b)[0, 0])
        print(f"  [Python] matmul@numpy/{label:<12} {ms:10.4f} ms/call")


if __name__ == "__main__":
    main()

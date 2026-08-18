#!/usr/bin/env -S python3
"""
NumPy half of the MatD benchmark tables in docs/MatDBenchmarks.md.

Counterpart to jsrc/bench.sc (Scala/MatD) and rust/src/bin/bench_mat.rs (Rust/MatD).
All three run the same operations at the same sizes on inputs drawn from the same
PCG64 sequence. Run it through the orchestrator unless you only want this column:

    sbt "runMain uni.apps.MatBench"      # all three languages, both tables
    python py/bench.py                   # this column alone

Deps: numpy  (pip install numpy)

Three things are deliberately aligned with the other two halves; changing any of them
in one place only makes the tables compare harnesses rather than implementations:

  * `default_rng(42)` — PCG64, the generator uni's `MatD.randn` and Rust's `NumPyRng`
    implement. This used to be `np.random.seed(42)`, the LEGACY MT19937 RandomState:
    a different generator, so the NumPy column was measured on different matrices
    than the Scala one.
  * WARMUP / ITERS, which were 10 / 20 here against 16 / 240 in bench.sc.
  * The reported statistic: the MINIMUM, which is what the cheat sheet quotes.

Two tables are emitted. The operation table is the familiar one. The layout table is
new and is the more interesting: NumPy's `M.T` and `M[1:]` are views exactly as MatD's
are, and reductions over them take a different path — the same distinction that costs
up to 10x on the Rust side. Labels carrying an `@layout` suffix route there.

Output is one `[Python] <label>  <ms> ms/call` line per row, with labels that must
match the other two halves exactly — MatBench joins the three on the label.
"""

import time

import numpy as np

N = 1000    # square side for elementwise / reduction ops (1M elements)
MM = 512    # matmul side length (512³ ≈ 134M multiply-adds)

WARMUP = 16
ITERS = 60


def bench(label, fn):
    """Minimum wall time per call, in ms."""
    for _ in range(WARMUP):
        fn()
    best = float("inf")
    for _ in range(ITERS):
        t0 = time.perf_counter_ns()
        fn()
        ms = (time.perf_counter_ns() - t0) / 1e6
        if ms < best:
            best = ms
    print(f"  [Python] {label:<28} {best:10.4f} ms/call")


# ── inputs: one generator, drawn in this order, matching the other two halves ──
rng = np.random.default_rng(42)
A = rng.standard_normal((MM, MM))
B = rng.standard_normal((MM, MM))
M = rng.standard_normal((N, N))
M2 = rng.standard_normal((N, N))
# log and sqrt need a positive operand; negatives would make those NaN benchmarks.
POS = np.abs(M) + 1.0


def sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))


def relu(x):
    return np.maximum(x, 0.0)


# np.vectorize is a Python-level loop, not a C kernel — the row exists to show that,
# and its MatD counterpart is mapParallel.
_vec_fn = np.vectorize(lambda x: x * x + 2.0 * x + 1.0)

print("── NumPy Benchmarks ───────────────────────────────────────────────────")
print(f"config: numpy={np.__version__} N={N} MM={MM} warmup={WARMUP} iters={ITERS}")

# ── operation table ───────────────────────────────────────────────────────────
bench("randn", lambda: np.random.default_rng(42).standard_normal((N, N)))
bench("matmul", lambda: A @ B)
bench("sigmoid", lambda: sigmoid(M))
bench("relu", lambda: relu(M))
bench("vectorize", lambda: _vec_fn(M))
bench("add", lambda: M + M2)
bench("mul", lambda: M * M2)
bench("abs", lambda: np.abs(M))
bench("exp", lambda: np.exp(M))
bench("log", lambda: np.log(POS))
bench("sqrt", lambda: np.sqrt(POS))
bench("sum", lambda: M.sum())
bench("mean", lambda: M.mean())
bench("std", lambda: M.std())
bench("min", lambda: M.min())
bench("max", lambda: M.max())
bench("argmax", lambda: M.argmax())
bench("sum0", lambda: M.sum(axis=0))
bench("sum1", lambda: M.sum(axis=1))
bench("mean0", lambda: M.mean(axis=0))
bench("min0", lambda: M.min(axis=0))
bench("max1", lambda: M.max(axis=1))
bench("std0", lambda: M.std(axis=0))
bench("cumsum", lambda: M.cumsum())
bench("cumsum1", lambda: M.cumsum(axis=1))
bench("cummin1", lambda: np.minimum.accumulate(M, axis=1))
bench("cummax0", lambda: np.maximum.accumulate(M, axis=0))
bench("transpose", lambda: M.T)

# ── layout table ──────────────────────────────────────────────────────────────
# Every one of these is a view in NumPy, as it is in MatD: `.T` swaps strides, `M[1:]`
# carries an offset, and broadcast_to sets a zero stride. Reductions over them take a
# different path from the contiguous case in all three libraries.
VIEWS = {
    "contig": M,
    "transposed": M.T,
    "rowslice": M[1:, :],
    "bcast": np.broadcast_to(M[0:1, :], (N, N)),
}
for name, V in VIEWS.items():
    bench(f"sum@{name}", lambda V=V: V.sum())
    bench(f"max@{name}", lambda V=V: V.max())
    bench(f"std@{name}", lambda V=V: V.std())
    bench(f"sum0@{name}", lambda V=V: V.sum(axis=0))

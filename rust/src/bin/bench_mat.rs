//! Rust half of the MatD benchmark tables in `docs/MatDCheatSheet.md`.
//!
//! Counterpart to `jsrc/bench.sc` (Scala) and `py/bench.py` (NumPy). All three run the
//! same operations at the same sizes on inputs drawn from the same PCG64 sequence, and
//! `uni.apps.MatBench` runs all three and prints the finished markdown. Run it through
//! that rather than alone, unless you only want the Rust column.
//!
//! # Two tables, because layout is a dimension
//!
//! The **operation** table is the familiar one: elementwise, reduction and axis work on
//! a contiguous 1000×1000.
//!
//! The **layout** table is new, and is the more interesting of the two. All three
//! libraries distinguish a contiguous matrix from a strided view — NumPy's `M.T` is a
//! view exactly as `MatD`'s is — and all three take a different code path for it. On
//! this side that difference was measured at up to 10× on a 2000×2000, so quoting a
//! single number per operation was hiding most of the story.
//!
//! # What is missing, and why that is worth printing
//!
//! Several rows have no Rust column yet: `matmul` (Tier 3 phase (e)), `sigmoid`/`relu`
//! (`MatMathOps`, phase (c)) and the `mapParallel`/`np.vectorize` row. Those are absent
//! from the port, not slow in it. The orchestrator prints "—", which makes the table
//! double as a coverage report against `PARITY.md`.

#![allow(
    clippy::print_stdout,
    reason = "benchmark runner; its whole output is the result"
)]

use std::hint::black_box;
use std::time::Instant;

use uni::NumPyRng;
use uni::udata::MatD;

/// Square side for the elementwise and reduction rows — 1M elements.
const N: usize = 1000;

/// Warmup and timed iteration counts. These must match the Scala and Python halves or
/// the tables compare harnesses rather than implementations.
const WARMUP: usize = 16;
const ITERS: usize = 60;

/// A standard-normal matrix drawn to match the other two languages element for element.
///
/// `jsrc/bench.sc` seeds uni's PCG64 and fills row-major; `py/bench.py` does the same
/// through `np.random.default_rng`. (`randn` agrees to ~40 bits rather than exactly —
/// see the `log1p` note in `PARITY.md` — irrelevant to timing, but worth knowing before
/// reusing this for a value check.)
fn normal_matrix(rng: &mut NumPyRng, rows: usize, cols: usize) -> MatD {
    let data: Vec<f64> = (0..rows * cols).map(|_| rng.randn()).collect();
    MatD::create(data, rows, cols)
}

/// Minimum wall time per call in ms.
///
/// The minimum, not the mean: least noisy estimator of the underlying cost, and what the
/// cheat sheet quotes. The closure returns a value derived from the result and hands it
/// to `black_box`, without which LTO can see the whole computation is dead.
fn min_ms<F: FnMut() -> f64>(mut op: F) -> f64 {
    for _ in 0..WARMUP {
        black_box(op());
    }
    let mut best = f64::INFINITY;
    for _ in 0..ITERS {
        let t0 = Instant::now();
        let sink = op();
        let ms = t0.elapsed().as_secs_f64() * 1000.0;
        black_box(sink);
        if ms < best {
            best = ms;
        }
    }
    best
}

/// One row. The label must match the Scala and Python halves exactly — `MatBench` joins
/// the three languages on it. A `@layout` suffix routes the row to the layout table.
fn row(label: &str, ms: f64) {
    println!("  [Rust] {label:<28} {ms:10.4} ms/call");
}

/// Elementwise and whole-matrix work on a contiguous matrix.
fn operation_table(m: &MatD, m2: &MatD, pos: &MatD) {
    row(
        "randn",
        min_ms(|| {
            let mut r = NumPyRng::new(42);
            normal_matrix(&mut r, N, N).at(0, 0)
        }),
    );
    row("add", min_ms(|| (m + m2).at(0, 0)));
    row("mul", min_ms(|| (m * m2).at(0, 0)));
    row("abs", min_ms(|| m.abs().at(0, 0)));
    row("exp", min_ms(|| m.exp().at(0, 0)));
    // log and sqrt need a positive operand; negatives would make this a NaN benchmark.
    row("log", min_ms(|| pos.log().at(0, 0)));
    row("sqrt", min_ms(|| pos.sqrt().at(0, 0)));
    row("sum", min_ms(|| m.sum()));
    row("mean", min_ms(|| m.mean()));
    row("std", min_ms(|| m.std()));
    row("min", min_ms(|| m.min()));
    row("max", min_ms(|| m.max()));
    row("argmax", min_ms(|| m.argmax().0 as f64));
    row("sum0", min_ms(|| m.sumAxis(0).at(0, 0)));
    row("sum1", min_ms(|| m.sumAxis(1).at(0, 0)));
    row("cumsum", min_ms(|| m.cumsum().at(0, 0)));
    row("cummax0", min_ms(|| m.cummax(0).at(0, 0)));
    // O(1) in all three: a stride flip, no data movement. A sanity row — if it ever
    // costs anything, something started copying.
    row("transpose", min_ms(|| m.T().at(0, 0)));
}

/// The same reductions over four layouts.
///
/// `transposed` and `rowslice` are views: the first swaps strides, the second carries an
/// offset. `bcast` stretches one row over all of them with a zero row-stride. Every one
/// of these exists in NumPy too, so the three columns are comparable — and on this side
/// the spread between them reached 10×, which is the point of measuring it.
fn layout_table(m: &MatD) {
    let rows = m.rows() as i64;
    let cols = m.cols() as i64;
    let views: [(&str, MatD); 4] = [
        ("contig", m.clone()),
        ("transposed", m.T()),
        ("rowslice", m.slice(1..rows, 0..cols)),
        (
            "bcast",
            m.slice(0..1, 0..cols).broadcastTo(m.rows(), m.cols()),
        ),
    ];
    for (name, v) in &views {
        row(&format!("sum@{name}"), min_ms(|| v.sum()));
        row(&format!("max@{name}"), min_ms(|| v.max()));
        row(&format!("std@{name}"), min_ms(|| v.std()));
        row(&format!("sum0@{name}"), min_ms(|| v.sumAxis(0).at(0, 0)));
    }
}

fn main() {
    println!("── Rust MatD Benchmarks ───────────────────────────────────────────────");
    if cfg!(debug_assertions) {
        println!("  WARNING: debug build — run with `cargo run --release --bin bench_mat`");
    }
    println!(
        "config: blas={} threads={} N={N} warmup={WARMUP} iters={ITERS}",
        if cfg!(feature = "blas") { "on" } else { "off" },
        std::env::var("OPENBLAS_NUM_THREADS").unwrap_or_else(|_| "unset".to_string())
    );

    let mut rng = NumPyRng::new(42);
    let m = normal_matrix(&mut rng, N, N);
    let m2 = normal_matrix(&mut rng, N, N);
    let pos = &m.abs() + 1.0;

    operation_table(&m, &m2, &pos);
    layout_table(&m);
}

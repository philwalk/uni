//! Rust half of the matmul contract measurement — counterpart to
//! `uni.data.MatmulProbe` (Scala) and `py/bench_matmul.py` (NumPy).
//!
//! Times every candidate for the Rust `matmul` default, per shape:
//!
//! - `pure`: `MatD::matmulPure`, the pinned default — a sequential k-sum from 0.0 per
//!   cell, bit-identical to Scala's, through the register-blocked microkernel.
//! - `pureT`: the same with a transposed-view left operand, the layout `Tprf3` feeds it.
//! - `mm`: ndarray `dot`, i.e. the pure-Rust `matrixmultiply` kernels — the dependency-
//!   free alternative the default was measured against.
//! - `blas`: ndarray `dot` routed to OpenBLAS; only present under `--features blas`.
//!
//! Labels are `matmul@<path>/<shape>` in the harness format so the three halves join.
//!
//! Run: `cargo run --release --bin bench_matmul` (add `--features blas` for that row).

#![allow(
    clippy::print_stdout,
    clippy::cast_precision_loss,
    clippy::expect_used,
    reason = "a benchmark binary: printing is the output, ms are approximate, and a shape \
              mismatch in fixed constants should abort loudly"
)]

use std::hint::black_box;
use std::time::Instant;

use ndarray::Array2;
use uni::NumPyRng;
use uni::udata::MatD;

/// `(label, rowsA, colsA, colsB)` — must match `MatmulProbe.shapes`.
const SHAPES: &[(&str, usize, usize, usize)] = &[
    ("8", 8, 8, 8),
    ("16", 16, 16, 16),
    ("32", 32, 32, 32),
    ("64", 64, 64, 64),
    ("128", 128, 128, 128),
    ("256", 256, 256, 256),
    ("512", 512, 512, 512),
    ("512x8x512", 512, 8, 512),
    ("512x512x8", 512, 512, 8),
];

/// Minimum ms per call over `runs`, each repeating the op to fill ~`target_ms`.
fn min_ms<F: FnMut() -> f64>(mut op: F, runs: usize, target_ms: f64) -> f64 {
    for _ in 0..5 {
        black_box(op());
    }
    let t0 = Instant::now();
    black_box(op());
    let est = t0.elapsed().as_secs_f64() * 1000.0;
    let reps = ((target_ms / est.max(1e-6)) as usize).clamp(1, 20_000);
    let mut best = f64::INFINITY;
    for _ in 0..runs {
        let s = Instant::now();
        for _ in 0..reps {
            black_box(op());
        }
        let ms = s.elapsed().as_secs_f64() * 1000.0 / reps as f64;
        if ms < best {
            best = ms;
        }
    }
    best
}

fn row(label: &str, ms: f64) {
    println!("  [Rust] {label:<28} {ms:10.4} ms/call");
}

fn main() {
    let blas = if cfg!(feature = "blas") {
        "openblas"
    } else {
        "none"
    };
    println!(
        "config: rustc={} threads={} blas={}",
        env!("CARGO_PKG_RUST_VERSION"),
        rayon::current_num_threads(),
        blas
    );
    let mut rng = NumPyRng::new(42);
    for &(label, ra, ca, cb) in SHAPES {
        let a: Vec<f64> = (0..ra * ca).map(|_| rng.randn()).collect();
        let b: Vec<f64> = (0..ca * cb).map(|_| rng.randn()).collect();
        let am = Array2::from_shape_vec((ra, ca), a.clone()).expect("shape");
        let bm = Array2::from_shape_vec((ca, cb), b.clone()).expect("shape");
        let ma = MatD::create(a.clone(), ra, ca);
        let mb = MatD::create(b.clone(), ca, cb);
        // A transposed view with the same logical values: build (ca×ra) row-major from
        // A's transpose, then view it back — what a `X.T *@ Y` call site hands the kernel.
        let mat_t = MatD::create(ma.T().toArray(), ca, ra).T();

        // The first timed series after the operands are built pays for the allocator
        // growing into the new size class; take that hit on an unrecorded series.
        black_box(min_ms(|| ma.matmulPure(&mb).at(0, 0), 3, 2.0));
        let pure = min_ms(|| ma.matmulPure(&mb).at(0, 0), 7, 2.0);
        let pure_t = min_ms(|| mat_t.matmulPure(&mb).at(0, 0), 7, 2.0);
        let mm = min_ms(|| am.dot(&bm)[(0, 0)], 7, 2.0);
        row(&format!("matmul@pure/{label}"), pure);
        row(&format!("matmul@pureT/{label}"), pure_t);
        // Under `--features blas` ndarray's dot IS the blas row; the label says which.
        row(
            &format!(
                "matmul@{}/{label}",
                if cfg!(feature = "blas") { "blas" } else { "mm" }
            ),
            mm,
        );
    }
}

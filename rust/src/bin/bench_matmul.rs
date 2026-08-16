//! Rust half of the matmul contract measurement — counterpart to
//! `uni.data.MatmulProbe` (Scala) and `py/bench_matmul.py` (NumPy).
//!
//! Times every candidate for the Rust `matmul` default, per shape:
//!
//! - `pure`: a faithful port of Scala's `multiplyDouble` — TILE=32, parallel over row
//!   tiles, sequential k-sum from 0.0 per cell. This is the path that CAN be pinned
//!   bit-for-bit, so its cost is what pinning would cost if it were also the default.
//! - `pureST`: the same loop single-threaded, to separate the algorithm from rayon.
//! - `mm`: ndarray `dot`, i.e. the pure-Rust `matrixmultiply` kernels — what the crate
//!   runs today without `--features blas`.
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
use rayon::prelude::*;
use uni::NumPyRng;

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

const TILE: usize = 32;

/// Scala's `multiplyDouble`, contiguous operands: `tileI` parallel, then `tileK`,
/// `tileJ`, `i`, `k`, `j`, accumulating into `result[i*colsB + j]`. For every cell that
/// is k ascending from 0.0, which is the whole association-order contract.
fn pure_tiled(
    a: &[f64],
    b: &[f64],
    rows_a: usize,
    cols_a: usize,
    cols_b: usize,
    parallel: bool,
) -> Vec<f64> {
    let mut out = vec![0.0f64; rows_a * cols_b];
    let tiles_i = rows_a.div_ceil(TILE);
    let body = |tile_i: usize, out_rows: &mut [f64]| {
        let i_start = tile_i * TILE;
        let i_end = (i_start + TILE).min(rows_a);
        for tile_k in 0..cols_a.div_ceil(TILE) {
            let k_start = tile_k * TILE;
            let k_end = (k_start + TILE).min(cols_a);
            for tile_j in 0..cols_b.div_ceil(TILE) {
                let j_start = tile_j * TILE;
                let j_end = (j_start + TILE).min(cols_b);
                for i in i_start..i_end {
                    let orow = &mut out_rows[(i - i_start) * cols_b..(i - i_start + 1) * cols_b];
                    for k in k_start..k_end {
                        let a_val = a[i * cols_a + k];
                        let brow = &b[k * cols_b..(k + 1) * cols_b];
                        for j in j_start..j_end {
                            orow[j] += a_val * brow[j];
                        }
                    }
                }
            }
        }
    };
    if parallel {
        out.par_chunks_mut(TILE * cols_b)
            .enumerate()
            .for_each(|(tile_i, chunk)| body(tile_i, chunk));
    } else {
        for (tile_i, chunk) in out.chunks_mut(TILE * cols_b).enumerate() {
            body(tile_i, chunk);
        }
    }
    debug_assert_eq!(tiles_i, rows_a.div_ceil(TILE));
    out
}

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
        "config: rustc={} threads={} blas={} tile={TILE}",
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

        let pure = min_ms(|| pure_tiled(&a, &b, ra, ca, cb, true)[0], 7, 2.0);
        let pure_st = min_ms(|| pure_tiled(&a, &b, ra, ca, cb, false)[0], 7, 2.0);
        let mm = min_ms(|| am.dot(&bm)[(0, 0)], 7, 2.0);
        row(&format!("matmul@pure/{label}"), pure);
        row(&format!("matmul@pureST/{label}"), pure_st);
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

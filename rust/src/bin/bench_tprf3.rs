#![allow(
    clippy::print_stdout,
    reason = "Benchmark runner outputs results to stdout"
)]

use std::hint::black_box;
use std::time::Instant;

use ndarray::Array2;
use rand::RngExt;
use rand::SeedableRng;
use rand::rngs::StdRng;
use t3prf::Error;
use t3prf::estimate_3prf_is_full;
use t3prf::estimate_3prf_oos_cv;
use t3prf::estimate_3prf_oos_rec;

fn generate_normal_matrix(rng: &mut StdRng, rows: usize, cols: usize) -> Array2<f64> {
    let mut mat = Array2::<f64>::zeros((rows, cols));
    for r in 0..rows {
        for c in 0..cols {
            // Standard normal approximation via Box-Muller transform
            let u1: f64 = rng.random();
            let u2: f64 = rng.random();
            let z = (-2.0 * u1.ln()).sqrt() * (2.0 * std::f64::consts::PI * u2).cos();
            mat[[r, c]] = z;
        }
    }
    mat
}

/// Median wall time per call. The closure returns a value derived from the
/// result, which is fed to `black_box` — without it, LTO can see the whole
/// computation is dead and eliminate part of the work being measured.
fn median_ms<F>(loops: usize, mut op: F) -> Result<f64, Error>
where
    F: FnMut() -> Result<f64, Error>,
{
    let mut times = Vec::with_capacity(loops);
    for _ in 0..loops {
        let t0 = Instant::now();
        let sink = op()?;
        let elapsed_ms = t0.elapsed().as_secs_f64() * 1000.0;
        black_box(sink);
        times.push(elapsed_ms);
    }
    times.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let n = times.len();
    if n == 0 {
        Ok(0.0)
    } else if n % 2 == 1 {
        Ok(times[n / 2])
    } else {
        Ok((times[n / 2 - 1] + times[n / 2]) / 2.0)
    }
}

fn run_suite(
    label: &str,
    t: usize,
    n: usize,
    l: usize,
    warmup: usize,
    loops: usize,
) -> Result<(), Error> {
    println!("\n── {label}  (T={t}  N={n}  L={l}  warmup={warmup}  loops={loops}) ──");
    let mut rng = StdRng::seed_from_u64(42);

    let x = generate_normal_matrix(&mut rng, t, n);
    let y = generate_normal_matrix(&mut rng, t, 1);
    let z = generate_normal_matrix(&mut rng, t, l);

    // Warm every path, not just IS Full — the OOS routines are where rayon's
    // pool spins up, and paying that inside the first timed iteration skewed
    // the OOS medians.
    for _ in 0..warmup {
        black_box(estimate_3prf_is_full(&y, &x, &z)?.r_squared);
        black_box(estimate_3prf_oos_rec(&y, &x, &z, t / 2)?.r_squared);
        black_box(estimate_3prf_oos_cv(&y, &x, &z, 0, 1)?.r_squared);
    }

    let is_full_ms = median_ms(loops, || {
        Ok(estimate_3prf_is_full(black_box(&y), black_box(&x), black_box(&z))?.r_squared)
    })?;

    let oos_rec_ms = median_ms(loops, || {
        Ok(estimate_3prf_oos_rec(black_box(&y), black_box(&x), black_box(&z), t / 2)?.r_squared)
    })?;

    let oos_cv_ms = median_ms(loops, || {
        Ok(estimate_3prf_oos_cv(black_box(&y), black_box(&x), black_box(&z), 0, 1)?.r_squared)
    })?;

    println!("  [Rust] estimate3prf IS Full  {is_full_ms:8.3} ms/call");
    println!("  [Rust] estimate3prf OOS Rec  {oos_rec_ms:8.2} ms/call");
    println!("  [Rust] estimate3prf OOS CV   {oos_cv_ms:8.2} ms/call");

    Ok(())
}

fn main() -> Result<(), Error> {
    println!("── Rust 3PRF Benchmarks (ndarray) ─────────────────────────────────────");
    if cfg!(debug_assertions) {
        println!("  WARNING: debug build — run with `cargo run --release --bin bench_tprf3`");
    }
    // Both feature combinations build to the same path, and they differ by ~3x
    // on the OOS rows — so the binary reports which one it is rather than
    // leaving a caller to guess. uni.apps.Tprf3Bench parses this line.
    println!(
        "config: blas={} threads={}",
        if cfg!(feature = "blas") { "on" } else { "off" },
        std::env::var("OPENBLAS_NUM_THREADS").unwrap_or_else(|_| "unset".to_string())
    );
    // loop counts match jsrc/tprf3Bench.sc so the tables compare like with like
    run_suite("Small", 200, 30, 2, 5, 50)?;
    run_suite("Large", 650, 40, 2, 3, 25)?;
    Ok(())
}

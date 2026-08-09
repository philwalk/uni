#![allow(
    clippy::print_stdout,
    reason = "Benchmark runner outputs results to stdout"
)]

use std::hint::black_box;
use std::time::Instant;

use ndarray::Array2;
use uni::Error;
use uni::NumPyRng;
use uni::estimate_3prf_is_full;
use uni::estimate_3prf_oos_cv;
use uni::estimate_3prf_oos_rec;

/// Standard normal matrix, drawn to match the Scala and Python benchmarks
/// element for element.
///
/// `jsrc/tprf3Bench.sc` does `Mat.setSeed(0)` then `MatD.randn` for X, y and Z in
/// that order; `py/bench_tprf3.py` does the same through
/// `np.random.default_rng(0)`. `NumPyRng` reproduces that draw sequence, and
/// `Mat`, NumPy and `ndarray` all store row-major, so a row-major fill puts the
/// same value at the same `(r, c)` in all three.
///
/// This previously used `StdRng` with a Box–Muller transform, which made the
/// Rust column of the benchmark tables the only one measured on different
/// matrices than the other two.
fn generate_normal_matrix(rng: &mut NumPyRng, rows: usize, cols: usize) -> Array2<f64> {
    let mut mat = Array2::<f64>::zeros((rows, cols));
    // Fill order matters, not just the values: this has to be row-major to line
    // up with the flat sequential fill in `Mat.randn` and NumPy's C order.
    for r in 0..rows {
        for c in 0..cols {
            mat[[r, c]] = rng.randn();
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
    // Seed 0, and one generator for all three matrices in this order — both the
    // Scala and Python benchmarks do exactly that, and a fresh generator per
    // matrix would hand X's draws to y as well.
    let mut rng = NumPyRng::new(0);

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

/// Reports the build configuration, because the default one is not the right
/// baseline for a cross-language comparison.
///
/// The Scala side runs on JNIBLAS-backed native OpenBLAS, so a default
/// `blas=off` Rust build is not like-for-like. Measured on Windows at
/// T=650/N=40/L=2, the pure-Rust kernels cost ~2.3× on both large OOS rows
/// (OOS Rec 3.54 → 1.42 ms, OOS CV 4.51 → 2.06 ms with `--features blas`) —
/// enough to flip OOS Recursive from losing to Scala to beating it. The OOS
/// gemms are skinny (one operand has only L+1 columns), which `matrixmultiply`
/// handles poorly.
///
/// It reverses at the small size, where BLAS call overhead outweighs the kernel
/// win on the shorter path: OOS Rec goes 0.38 → 0.51 ms with the feature on,
/// while OOS CV still improves (0.95 → 0.80 ms). So neither build dominates —
/// quote the one that matches what you are comparing against, and say which.
fn print_config() {
    println!(
        "config: blas={} threads={}",
        if cfg!(feature = "blas") { "on" } else { "off" },
        std::env::var("OPENBLAS_NUM_THREADS").unwrap_or_else(|_| "unset".to_string())
    );
}

fn main() -> Result<(), Error> {
    println!("── Rust 3PRF Benchmarks (ndarray) ─────────────────────────────────────");
    if cfg!(debug_assertions) {
        println!("  WARNING: debug build — run with `cargo run --release --bin bench_tprf3`");
    }
    // Both feature combinations build to the same path, and they differ enough
    // to change which language wins — so the binary reports which one it is
    // rather than leaving a caller to guess. uni.apps.Tprf3Bench parses this
    // line. See print_config for what the difference actually costs.
    print_config();
    // loop counts match jsrc/tprf3Bench.sc so the tables compare like with like
    run_suite("Small", 200, 30, 2, 5, 50)?;
    run_suite("Large", 650, 40, 2, 3, 25)?;
    Ok(())
}

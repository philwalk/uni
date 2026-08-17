//! The factor-generation recurrences from `src/main/scala/apps/TprfRunner.scala`, ported.
//!
//! # Why this exists
//!
//! [`MatMut`] was added for one idiom — sequential recurrences, where each row depends on
//! the one before — and this is the real code that idiom came from, rather than a
//! synthetic exercise. It is therefore the reference for how a mutating port should read:
//!
//! ```scala
//! f(0, ::) = MatD.randn(relevant_factors, 1)
//! for t <- 1 until T do
//!   f(t, ::) = f(t - 1, ::) * pf + fNoise(t - 1, ::)
//! ```
//!
//! `MatD::zeros(...).intoMut()` is the one construction route, mirroring Scala's single
//! `MatD.zeros`. [`MatD::intoMut`] panics if the buffer is shared, which is why `generate`
//! returns a plain tuple here exactly as the Scala method does — a `Result` would
//! propagate into every caller and change the shape of the port.
//!
//! # Scope
//!
//! Everything `data_generator` does: the four recurrences, `hstack`, the matmul with the
//! factor loadings — `factors.matmulPure(factor_loadings)` there, `matmul` here: the
//! pinned pure loop on both sides — and the noise blend. It is the Rust half of a demo pair
//! with `jsrc/tprfRunner.sc`, byte-identical output.
//!
//! The `non_pervasive` branch is deliberately omitted: it uses `scala.util.Random.shuffle`
//! to zero half the loadings, and that shuffle has no portable counterpart.
//!
//! Run: `cargo run --release --example tprf_runner`

#![allow(
    non_snake_case,
    clippy::print_stdout,
    reason = "mirrors the Scala names; a runnable example whose output is the point"
)]

use uni::NumPyRng;
use uni::udata::MatD;
use uni::udata::java_format_f;

/// `MatD.randn(rows, cols)` — row-major fill from the shared generator, so the draw
/// ORDER matches the Scala side call for call, not just the distribution.
fn randn(rng: &mut NumPyRng, rows: usize, cols: usize) -> MatD {
    let data: Vec<f64> = (0..rows * cols).map(|_| rng.randn()).collect();
    MatD::create(data, rows, cols)
}

/// `TprfRunner.popStd`, formula for formula: `sqrt(sum((m - mu)^2) / n)` with `mu = sum/n`.
/// The square is `x * x` rather than `power(2.0)`, so both twins compute `fl(x*x)`
/// by definition instead of relying on their `pow` agreeing at exponent 2.
fn popStd(m: &MatD) -> f64 {
    let n = (m.rows() * m.cols()) as f64;
    let mu = m.sum() / n;
    let d = m - mu;
    ((&d * &d).sum() / n).sqrt()
}

/// Everything in `TprfRunner.generate` up to the matmul, in one piece as the Scala is.
fn generate(
    rng: &mut NumPyRng,
    t: usize,
    n: usize,
    relevant: usize,
    irr: usize,
) -> (MatD, MatD, MatD, MatD, MatD, MatD) {
    let (pf, pg, a, d, strength) = (0.9_f64, 0.9_f64, 0.9_f64, 0.0_f64, 1.0_f64);
    // The cyclic neighbour blend below is only skippable because d is 0; asserting it
    // keeps the omission visible without inventing an error path for a fixed constant.
    assert!(d == 0.0, "d != 0 needs the cyclic neighbour blend");

    // Draw order is load-bearing: the Scala draws v, then the loadings, then f's seed
    // row, then the noise batches, in exactly this sequence.
    let v = randn(rng, t, n);
    let factor_loadings = randn(rng, relevant + irr, n);

    // f: the recurrence MatMut exists for.
    let mut f = MatD::zeros(t, relevant).intoMut();
    f.updateRowAllFrom(0, &randn(rng, relevant, 1));
    let fNoise = randn(rng, t - 1, relevant);
    for step in 1..t {
        let prev = f.applyRowAll(step - 1);
        f.updateRowAllFrom(step, &(&(&prev * pf) + &fNoise.applyRowAll(step - 1)));
    }
    let f = f.freeze();
    let fStd = popStd(&f);

    // g: written a column at a time, then rescaled per column.
    let mut g = MatD::zeros(t, irr).intoMut();
    g.updateRowAllFrom(0, &randn(rng, irr, 1));
    let mut g_err = MatD::zeros(t, irr).intoMut();
    for i in 0..4.min(irr) {
        g_err.updateAllColFrom(i, &randn(rng, t, 1));
    }
    let g_err = g_err.freeze();
    let g_var = [1.25_f64, 1.75, 2.25, 2.75];
    for j in 0..irr {
        for step in 1..t {
            g.updateAt(step, j, g.at(step - 1, j) * pg + g_err.at(step, j));
        }
        let gcolj = g.applyAllCol(j);
        let scale = fStd * g_var[j.min(g_var.len() - 1)].sqrt() / popStd(&gcolj);
        g.updateAllColFrom(j, &(&gcolj * scale));
    }
    let g = g.freeze();

    // y: scalar writes into a column.
    let mut y = MatD::zeros(t + 1, 1).intoMut();
    let yNoise = randn(rng, t, 1);
    for step in 1..=t {
        y.updateAt(
            step,
            0,
            f.applyRowAll(step - 1).mean() + fStd * yNoise.at(step - 1, 0),
        );
    }
    let y = y.freeze();

    // eta: a row recurrence over the idiosyncratic errors.
    let eta_tilda = v;
    let mut eta = MatD::zeros(t, n).intoMut();
    eta.updateRowAllFrom(0, &randn(rng, n, 1));
    for step in 1..t {
        let prev = eta.applyRowAll(step - 1);
        eta.updateRowAllFrom(step, &(&(&prev * a) + &eta_tilda.applyRowAll(step)));
    }
    let eta = eta.freeze();

    // The tail of `data_generator`: stack the factors, load them onto N predictors —
    // `factors.matmulPure(factor_loadings)`, the pinned matmul — and blend in the scaled errors.
    let factors = MatD::hstack(&[&f, &g]);
    let x1 = factors.matmul(&factor_loadings);
    let eta_norm = &eta / popStd(&eta);
    let constant = popStd(&x1) * strength.sqrt();
    let x = &x1 + &(&eta_norm * constant);
    let y_tail = y.applyRowsAll(1..t + 1);

    (f, g, eta, x1, x, y_tail)
}

/// The JVM's `%+.10f`: half-up on the exact decimal expansion, where Rust's `{:+.10}`
/// is half-even. They differ only on exact ties, but a demo pair cannot afford "only".
fn f10(v: f64) -> String {
    let s = java_format_f(v, 0, 10);
    if v.is_sign_negative() || s.starts_with('-') {
        s
    } else {
        format!("+{s}")
    }
}

fn main() {
    let (t, n, relevant, irr) = (12usize, 6usize, 3usize, 4usize);
    let mut rng = NumPyRng::new(0);
    let (f, g, eta, x1, x, y) = generate(&mut rng, t, n, relevant, irr);

    println!("── TprfRunner data_generator ──────────────────────────────────────────");
    println!("T={t} N={n} relevant={relevant} irrelevant={irr} strength=1.0  seed=0");
    println!();
    for (name, m) in [
        ("f", &f),
        ("g", &g),
        ("eta", &eta),
        ("X1", &x1),
        ("X", &x),
        ("y", &y),
    ] {
        println!(
            "{name:<3} {:>2}x{:<2}  first={}  last={}  mean={}  std={}",
            m.rows(),
            m.cols(),
            f10(m.at(0, 0)),
            f10(m.at(m.rows() - 1, m.cols() - 1)),
            f10(m.mean()),
            f10(popStd(m))
        );
    }
    println!();
    println!("X, every row:");
    for r in 0..x.rows() {
        let cells: Vec<String> = (0..x.cols()).map(|c| f10(x.at(r, c))).collect();
        println!("  {}", cells.join(" "));
    }
    println!(
        "y: {}",
        (0..y.rows())
            .map(|r| f10(y.at(r, 0)))
            .collect::<Vec<_>>()
            .join(" ")
    );
}

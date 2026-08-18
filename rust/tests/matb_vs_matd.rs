//! `MatB` and `MatD` as each other's second opinion — the Rust twin of Scala's
//! `MatBigVsDoubleSuite`, same operations, same tolerances, same seed.
//!
//! The two types run different code — the sequential exact-decimal folds versus the
//! chunked `sumD`, the f64 fast paths and IEEE arithmetic — for one set of semantics.
//! The fixtures pin each type against ITSELF (its Scala reference); this pins them against
//! EACH OTHER: on the same inputs every benchmarked operation must agree to Double's own
//! precision, positions must land on the same cell, and NaN — `f64::NAN` there, `BigNaN`
//! here — must fold, order, mask and place identically. A divergence here is a semantic
//! difference, not rounding.
//!
//! Tolerance `rtol 1e-9`: the f64 result carries ~1e-13 relative error on these sizes,
//! the decimal one is exact to 34 digits, so anything past 1e-9 is a rule that differs.
#![allow(
    clippy::expect_used,
    clippy::panic,
    clippy::unwrap_used,
    reason = "a test aborts loudly"
)]

use uni::NumPyRng;
use uni::udata::Big;
use uni::udata::MatB;
use uni::udata::MatD;

const N: usize = 60;
const ATOL: f64 = 1e-12;

fn close(a: f64, b: f64, rtol: f64) -> bool {
    (a.is_nan() && b.is_nan()) || (a - b).abs() <= ATOL + rtol * a.abs().max(b.abs())
}

fn assert_agree(name: &str, d: &MatD, b: &MatB, rtol: f64) {
    let bd = b.toMatD();
    assert_eq!(bd.shape(), d.shape(), "{name} shape");
    for i in 0..d.rows() {
        for j in 0..d.cols() {
            assert!(
                close(d.at(i, j), bd.at(i, j), rtol),
                "{name} at ({i},{j}): Double {} vs Big {}",
                d.at(i, j),
                bd.at(i, j)
            );
        }
    }
}

fn assert_agree_scalar(name: &str, d: f64, b: &Big, rtol: f64) {
    assert!(
        close(d, b.toDouble(), rtol),
        "{name}: Double {d} vs Big {}",
        b.toDouble()
    );
}

struct Inputs {
    md: MatD,
    mb: MatB,
    pd: MatD,
    pb: MatB,
    nd: MatD,
    nb: MatB,
    sd: MatD,
    sb: MatB,
}

fn inputs() -> Inputs {
    let mut rng = NumPyRng::new(20_260_817);
    let md = MatD::create((0..N * N).map(|_| rng.randn()).collect(), N, N);
    let mb = MatB::fromMatD(&md);
    let pd = &md.abs() + 1.0;
    let pb = MatB::fromMatD(&pd);
    let nan_mat = MatD::create(vec![f64::NAN; N * N], N, N);
    let nd = md.lt(-1.5).whereMat(&nan_mat, &md);
    let nb = MatB::fromMatD(&nd);
    let mut eye = vec![0.0; 36];
    for i in 0..6 {
        eye[i * 6 + i] = 6.0;
    }
    let sd = &MatD::create((0..36).map(|_| rng.randn()).collect(), 6, 6) + &MatD::create(eye, 6, 6);
    let sb = MatB::fromMatD(&sd);
    Inputs {
        md,
        mb,
        pd,
        pb,
        nd,
        nb,
        sd,
        sb,
    }
}

#[test]
fn elementwise_add_mul_abs_sqrt_exp_log_power_neg() {
    let x = inputs();
    assert_agree("add", &(&x.md + &x.md.T()), &x.mb.add(&x.mb.T()), 1e-9);
    assert_agree("mul", &(&x.md * &x.md), &x.mb.mul(&x.mb), 1e-9);
    assert_agree("div", &(&x.md / &x.pd), &x.mb.div(&x.pb), 1e-9);
    assert_agree(
        "scalar",
        &(&(&x.md * 2.5) - 1.0),
        &x.mb.mulScalar(&Big::parse("2.5")).subScalar(&Big::one()),
        1e-9,
    );
    assert_agree("abs", &x.md.abs(), &x.mb.abs(), 1e-9);
    assert_agree("neg", &-&x.md, &x.mb.neg(), 1e-9);
    assert_agree("sqrt", &x.pd.sqrt(), &x.pb.sqrt(), 1e-9);
    // both go through exp on the same double
    assert_agree("exp", &x.md.exp(), &x.mb.exp(), 1e-15);
    assert_agree("log", &x.pd.log(), &x.pb.log(), 1e-15);
    assert_agree("power", &x.md.power(3), &x.mb.power(3), 1e-9);
    assert_agree(
        "bcast row",
        &(&x.md - &x.md.meanAxis(0)),
        &x.mb.sub(&x.mb.meanAxis(0)),
        1e-9,
    );
}

#[test]
fn reductions_sum_mean_std_variance_min_max_arg_norm() {
    let x = inputs();
    assert_agree_scalar("sum", x.md.sum(), &x.mb.sum(), 1e-9);
    assert_agree_scalar("mean", x.md.mean(), &x.mb.mean(), 1e-9);
    assert_agree_scalar("std", x.md.std(), &x.mb.std(), 1e-9);
    assert_agree_scalar("variance", x.md.variance(), &x.mb.variance(), 1e-9);
    assert_agree_scalar("min", x.md.min(), &x.mb.min(), 1e-9);
    assert_agree_scalar("max", x.md.max(), &x.mb.max(), 1e-9);
    assert_eq!(x.mb.argmin(), x.md.argmin(), "argmin");
    assert_eq!(x.mb.argmax(), x.md.argmax(), "argmax");
    let row = x.md.slice(0..1, 0..N as i64);
    assert_agree_scalar("norm", row.norm(), &MatB::fromMatD(&row).norm(), 1e-9);
    assert_agree_scalar("trace", x.sd.trace(), &x.sb.trace(), 1e-9);
}

#[test]
fn axis_family_sum_mean_std_min_max_cumsum() {
    let x = inputs();
    for axis in 0..2 {
        assert_agree(
            &format!("sum({axis})"),
            &x.md.sumAxis(axis),
            &x.mb.sumAxis(axis),
            1e-9,
        );
        assert_agree(
            &format!("mean({axis})"),
            &x.md.meanAxis(axis),
            &x.mb.meanAxis(axis),
            1e-9,
        );
        assert_agree(
            &format!("std({axis})"),
            &x.md.stdAxis(axis),
            &x.mb.stdAxis(axis),
            1e-9,
        );
        assert_agree(
            &format!("min({axis})"),
            &x.md.minAxis(axis),
            &x.mb.minAxis(axis),
            1e-9,
        );
        assert_agree(
            &format!("max({axis})"),
            &x.md.maxAxis(axis),
            &x.mb.maxAxis(axis),
            1e-9,
        );
        assert_agree(
            &format!("cumsum({axis})"),
            &x.md.cumsumAxis(axis),
            &x.mb.cumsumAxis(axis),
            1e-9,
        );
    }
    assert_agree("cumsum", &x.md.cumsum(), &x.mb.cumsum(), 1e-9);
}

#[test]
fn products_and_linear_algebra() {
    let x = inputs();
    let n = N as i64;
    assert_agree(
        "matmul",
        &x.md.slice(0..20, 0..n).matmulPure(&x.md.slice(0..n, 0..20)),
        &x.mb.slice(0..20, 0..n).matmul(&x.mb.slice(0..n, 0..20)),
        1e-9,
    );
    assert_agree(
        "matmul view",
        &x.md.T().matmulPure(&x.md.slice(0..n, 0..3)),
        &x.mb.T().matmul(&x.mb.slice(0..n, 0..3)),
        1e-9,
    );
    assert_agree(
        "inverse",
        &x.sd.inverse().unwrap(),
        &x.sb.inverse().unwrap(),
        1e-9,
    );
    assert_agree_scalar(
        "determinant",
        x.sd.determinant().unwrap(),
        &x.sb.determinant().unwrap(),
        1e-9,
    );
    let rhs = x.sd.slice(0..6, 0..2);
    assert_agree(
        "solve",
        &x.sd.solve(&rhs).unwrap(),
        &x.sb.solve(&MatB::fromMatD(&rhs)).unwrap(),
        1e-9,
    );
}

#[test]
fn ordering_sort_argsort_masks() {
    let x = inputs();
    assert_agree("sort", &x.md.sort(None), &x.mb.sort(), 1e-9);
    assert_eq!(
        x.mb.argsort().toArray(),
        x.md.argsort(None).toArray(),
        "argsort"
    );
    assert_eq!(
        x.mb.gt(&Big::parse("0.5")).toArray(),
        x.md.gt(0.5).toArray(),
        "gt"
    );
    assert_eq!(
        x.mb.lt(&Big::parse("-0.5")).toArray(),
        x.md.lt(-0.5).toArray(),
        "lt"
    );
    assert_eq!(
        x.mb.gte(&Big::zero()).toArray(),
        x.md.gte(0.0).toArray(),
        "gte"
    );
    assert_eq!(
        x.mb.lte(&Big::zero()).toArray(),
        x.md.lte(0.0).toArray(),
        "lte"
    );
    let mask_d = x.md.gt(0.5);
    let mask_b = x.mb.gt(&Big::parse("0.5"));
    assert_agree(
        "m(mask)",
        &x.md.applyMask(&mask_d),
        &x.mb.applyMask(&mask_b),
        1e-9,
    );
}

#[test]
fn nan_travels_identically() {
    let x = inputs();
    assert!(x.nd.sum().is_nan() && x.nb.sum().isNaN(), "sum");
    assert!(x.nd.mean().is_nan() && x.nb.mean().isNaN(), "mean");
    assert_agree_scalar("min skips NaN", x.nd.min(), &x.nb.min(), 1e-9);
    assert!(x.nd.max().is_nan() && x.nb.max().isNaN(), "max is NaN");
    assert_eq!(x.nb.argmax(), x.nd.argmax(), "argmax lands on the same NaN");
    assert_eq!(x.nb.argmin(), x.nd.argmin(), "argmin");
    assert_agree(
        "sort with NaN (NaN last)",
        &x.nd.sort(None),
        &x.nb.sort(),
        1e-9,
    );
    assert_eq!(
        x.nb.gt(&Big::zero()).toArray(),
        x.nd.gt(0.0).toArray(),
        "gt with NaN: false"
    );
    assert_eq!(
        x.nb.lte(&Big::zero()).toArray(),
        x.nd.lte(0.0).toArray(),
        "lte with NaN: false"
    );
    assert_eq!(
        x.nb.hasNaN().toArray(),
        x.nd.isnan().toArray(),
        "hasNaN vs isnan"
    );
    assert_eq!(x.nb.containsNaN(), x.nd.containsNaN());
    let n = N as i64;
    assert_agree(
        "matmul with NaN",
        &x.nd.slice(0..4, 0..n).matmulPure(&x.nd.slice(0..n, 0..4)),
        &x.nb.slice(0..4, 0..n).matmul(&x.nb.slice(0..n, 0..4)),
        1e-9,
    );
    for axis in 0..2 {
        assert_agree(
            &format!("sum({axis}) with NaN"),
            &x.nd.sumAxis(axis),
            &x.nb.sumAxis(axis),
            1e-9,
        );
        assert_agree(
            &format!("max({axis}) with NaN"),
            &x.nd.maxAxis(axis),
            &x.nb.maxAxis(axis),
            1e-9,
        );
        assert_agree(
            &format!("min({axis}) with NaN"),
            &x.nd.minAxis(axis),
            &x.nb.minAxis(axis),
            1e-9,
        );
    }
}

#[test]
fn known_deliberate_differences() {
    // Big has no signed zero: abs keeps -0.0's VALUE (0) but not its bit; MatD keeps the bit.
    let z = MatD::create(vec![-0.0, 0.0], 1, 2);
    assert_eq!(MatB::fromMatD(&z).abs().toMatD().toArray(), vec![0.0, 0.0]);
    assert_eq!(z.abs().toArray()[0].to_bits(), (-0.0f64).to_bits());
    // mean of an empty matrix is zero for both.
    assert_eq!(MatD::zeros(0, 3).mean(), 0.0);
    assert!(MatB::zeros(0, 3).mean().compare(&Big::zero()) == 0);
    // Double masks are IEEE (NaN != NaN); Big equality recognises the sentinel.
    assert_eq!(
        MatD::create(vec![f64::NAN], 1, 1).eqTo(f64::NAN).toArray(),
        vec![false]
    );
    assert_eq!(
        MatB::row(&[Big::nan()]).eqTo(&Big::nan()).toArray(),
        vec![true]
    );
}

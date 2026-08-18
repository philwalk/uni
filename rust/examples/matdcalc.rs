//! One half of the cross-language demo pair; `jsrc/matdcalc.sc` is the other. Both run the
//! same MatD program on the same seeded matrix and print byte-identical output on any
//! machine — the matrix core end to end: creation, views, broadcasting, reductions, the
//! pinned matmul, masks, exact linear algebra, pandas-style ops, stacking, CSV text.
//! The two files are written to read line for line alike.
//!
//! ```text
//! scala-cli run jsrc/matdcalc.sc > scala.out
//! cargo run --release --manifest-path rust/Cargo.toml --example matdcalc > rust.out
//! diff scala.out rust.out
//! ```
//!
//! Matrices print through Java's `Double.toString` (the CSV cell rendering both ports
//! share); scalars that could differ in the last ulp between LAPACK-free ports (svd) print
//! through `Big` + `numStr` at four decimals, whose rendering is parity-pinned.

#![allow(
    non_snake_case,
    clippy::print_stdout,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::too_many_lines,
    reason = "a demo prints its report and dies loudly; one main, read top to bottom               against the Scala half, whose names it mirrors"
)]

use uni::NumPyRng;
use uni::udata::Big;
use uni::udata::MatD;
use uni::udata::NumFormat;
use uni::udata::linalg::NormOrd;
use uni::udata::numStr;

/// Row-major cells as Java renders doubles — `m.toArray.map(_.toString)` on the Scala side.
fn cells(m: &MatD) -> String {
    m.csvText(", ", "NaN").trim_end().replace('\n', ", ")
}

fn scalar(d: f64) -> String {
    cells(&MatD::create(vec![d], 1, 1))
}

fn num(d: f64) -> String {
    let fmt = NumFormat {
        colWidth: 1,
        dec: 4,
        factor: 1.0,
        abbreviate: false,
        suffix: String::new(),
    };
    numStr(&Big::from_f64(d), &fmt).trim().to_owned()
}

fn main() {
    let mut rng = NumPyRng::new(20260818);
    let m = MatD::rand(&mut rng, 4, 3);
    println!("m {}x{}: [{}]", m.rows(), m.cols(), cells(&m));
    println!(
        "m(1, 2) = {}   m(-1, -1) = {}",
        scalar(m.at(1, 2)),
        scalar(m.at(m.rows() - 1, m.cols() - 1))
    );
    println!();

    println!("views and slices:");
    println!("  m.T shape {}x{}", m.T().rows(), m.T().cols());
    println!("  m(1, ::)            [{}]", cells(&m.applyRowAll(1)));
    println!("  m(::, 2)            [{}]", cells(&m.applyAllCol(2)));
    println!(
        "  m(0 until 2, 1 until 3) [{}]",
        cells(&m.slice(0..2, 1..3))
    );
    println!(
        "  m(Array(3, 0), ::)  [{}]",
        cells(&m.applyRowsIdx(&[3, 0]))
    );
    println!();

    println!("arithmetic and broadcasting:");
    println!("  m * 2.0 + 1.0       [{}]", cells(&(&m * 2.0 + 1.0)));
    println!("  m - m.mean(0)       [{}]", cells(&(&m - &m.meanAxis(0))));
    println!("  m / m.sum(1)        [{}]", cells(&(&m / &m.sumAxis(1))));
    println!(
        "  m.exp.log ~ m       {}",
        (&m.exp().log() - &m).abs().max() < 1e-12
    );
    println!();

    println!("reductions:");
    println!(
        "  sum {}   mean {}   std {}   min {}   max {}",
        scalar(m.sum()),
        scalar(m.mean()),
        scalar(m.std()),
        scalar(m.min()),
        scalar(m.max())
    );
    let (ar, ac) = m.argmax();
    let (ir, ic) = m.argmin();
    println!("  argmax ({ar},{ac})   argmin ({ir},{ic})");
    println!("  sum(0)  [{}]", cells(&m.sumAxis(0)));
    println!("  mean(1) [{}]", cells(&m.meanAxis(1)));
    println!("  cumsum  [{}]", cells(&m.cumsum()));
    println!();

    println!("matmul (the pinned loop, bit-identical in both ports):");
    let g = m.T().matmul(&m);
    println!("  m.T *@ m 3x3 [{}]", cells(&g));
    println!(
        "  trace {}   norm(fro) {}",
        scalar(g.trace()),
        num(g.normOrd(NormOrd::Fro))
    );
    println!();

    println!("masks:");
    let mask = m.gt(0.5);
    println!(
        "  (m > 0.5).count {}   any {}   all {}",
        mask.count(),
        mask.any(),
        mask.all()
    );
    println!("  m(mask)         [{}]", cells(&m.applyMask(&mask)));
    println!("  where(mask,1,0) [{}]", cells(&mask.whereScalar(1.0, 0.0)));
    println!("  between(0.2, 0.6).count {}", m.between(0.2, 0.6).count());
    println!();

    println!("linear algebra (exact ports):");
    let a = MatD::create(vec![4.0, 2.0, 1.0, 3.0], 2, 2);
    println!(
        "  A [{}]   det {}",
        cells(&a),
        scalar(a.determinant().unwrap())
    );
    println!("  A.inverse [{}]", cells(&a.inverse().unwrap()));
    println!(
        "  A.solve([1, 2]) [{}]",
        cells(&a.solve(&MatD::apply(&[1.0, 2.0])).unwrap())
    );
    let (_, s, _) = a.svd();
    println!(
        "  singular values {}",
        s.iter().map(|&v| num(v)).collect::<Vec<_>>().join(", ")
    );
    println!(
        "  A *@ A.inverse ~ I {}",
        (&a.matmul(&a.inverse().unwrap()) - &MatD::eye(2))
            .abs()
            .max()
            < 1e-12
    );
    println!();

    println!("pandas-style:");
    let c0 = m.applyAllCol(0);
    println!("  col0 sorted     [{}]", cells(&c0.sort(None)));
    println!(
        "  col0 argsort    [{}]",
        c0.argsort(None)
            .toArray()
            .iter()
            .map(|&i| format!("{}", i as i64))
            .collect::<Vec<_>>()
            .join(", ")
    );
    println!(
        "  col0 median {}   percentile(25) {}",
        scalar(c0.median()),
        scalar(c0.percentile(25.0))
    );
    println!("  col0 rolling(2).mean [{}]", cells(&c0.rolling(2).mean()));
    println!("  col0 diff       [{}]", cells(&c0.diff()));
    println!("  m.nlargest(2)   [{}]", cells(&m.nlargest(2)));
    println!();

    println!("stacking and CSV:");
    let stacked = MatD::vstack(&[&m, &m.meanAxis(0)]);
    let wide = MatD::hstack(&[&m, &m.sumAxis(1)]);
    println!(
        "  vstack(m, m.mean(0)) {}x{}   hstack(m, m.sum(1)) {}x{}",
        stacked.rows(),
        stacked.cols(),
        wide.rows(),
        wide.cols()
    );
    let row0 = m.applyRowAll(0);
    println!("  csv row 0: {}", row0.csvText(",", "NaN").trim_end());
}

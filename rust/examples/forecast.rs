//! One half of the cross-language demo pair; `jsrc/forecast.sc` is the other.
//! Both generate the same seeded dataset, write it as CSV, read it back with
//! loadMatBig, and run the 3PRF closed forms — the first cross-language t3prf
//! DEMO (the benchmark twins measure speed; this one shows the API). See the
//! Scala twin for the run recipe and the determinism notes.

#![allow(
    non_snake_case,
    reason = "mirrors the Scala twin line for line; the shared API is camelCase by design"
)]
#![allow(
    clippy::print_stdout,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::float_cmp,
    reason = "a demo prints its report and dies loudly; the float equality IS the round-trip test"
)]

use ndarray::Array2;
use uni::numpy_rng::NumPyRng;
use uni::t3prf::forecast3prf;
use uni::t3prf::pls1Fit;
use uni::t3prf::plsClosedForm;
use uni::t3prf::tprfClosedForm;
use uni::udata::Big;
use uni::udata::NumFormat;
use uni::udata::numStr;
use uni::upath::StrPathExts;
use uni::upath::io::Charset;

const T: usize = 40;
const N: usize = 5;
const L: usize = 1;

fn num(d: f64) -> String {
    let fmt = NumFormat {
        colWidth: 1,
        dec: 6,
        factor: 1.0,
        abbreviate: false,
        suffix: String::new(),
    };
    numStr(&Big::from_f64(d), &fmt).trim().to_owned()
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let csvPath = args
        .first()
        .map_or("target/forecast-demo.csv", String::as_str);

    // Deterministic data, drawn in a fixed order so both languages see the same
    // doubles: X row-major, then the noise, then the proxy Z.
    let mut rng = NumPyRng::new(7);
    let mut x = vec![vec![0.0f64; N]; T];
    for row in &mut x {
        for v in row.iter_mut() {
            *v = rng.randn();
        }
    }
    let eps: Vec<f64> = (0..T).map(|_| rng.randn()).collect();
    let mut z = Array2::<f64>::zeros((T, L));
    for t in 0..T {
        for l in 0..L {
            z[[t, l]] = rng.randn();
        }
    }
    let w = [0.6, 0.3, -0.2, 0.1, 0.05];
    let y: Vec<f64> = (0..T)
        .map(|t| (0..N).map(|i| w[i] * x[t][i]).sum::<f64>() + 0.25 * eps[t])
        .collect();

    // CSV via Big's rendering of each double -- Double.toString semantics in both
    // languages, pinned by the big-parity fixture, so the files are byte-identical.
    let out = csvPath.as_path().expect("csv path");
    let ok = out.withWriter(Charset::Utf8, false, |wtr| {
        let header: Vec<String> = std::iter::once("y".to_owned())
            .chain((1..=N).map(|i| format!("x{i}")))
            .collect();
        wtr.print(&format!("{}\n", header.join(",")))?;
        for t in 0..T {
            let cells: Vec<String> = std::iter::once(Big::from_f64(y[t]).toString())
                .chain((0..N).map(|i| Big::from_f64(x[t][i]).toString()))
                .collect();
            wtr.print(&format!("{}\n", cells.join(",")))?;
        }
        Ok(())
    });
    assert!(ok, "cannot write {}", out.posx());

    let m = out.loadMatBig();
    println!(
        "loadMatBig: {} x {} from {}",
        m.nrows(),
        m.ncols(),
        out.last().unwrap_or("")
    );
    println!(
        "  cell(0,0)  {}   round-trip ok: {}",
        m[[0, 0]].toString(),
        m[[0, 0]].toDouble() == y[0]
    );
    println!(
        "  cell(39,5) {}   round-trip ok: {}",
        m[[39, 5]].toString(),
        m[[39, 5]].toDouble() == x[39][4]
    );
    println!();

    // back to doubles, THROUGH the loaded Big matrix: the loader is in the loop
    let yM = Array2::from_shape_fn((T, 1), |(t, _)| m[[t, 0]].toDouble());
    let xM = Array2::from_shape_fn((T, N), |(t, i)| m[[t, i + 1]].toDouble());

    let tp = tprfClosedForm(&yM, &xM, &z).expect("tprfClosedForm");
    println!("tprfClosedForm: rSquared {}", num(tp.r_squared));
    println!(
        "  forecasts: first {}   last {}",
        num(tp.forecasts[[0, 0]]),
        num(tp.forecasts[[T - 1, 0]])
    );

    let pls = plsClosedForm(&yM, &xM).expect("plsClosedForm");
    println!("plsClosedForm:  rSquared {}", num(pls.rSquared));
    println!(
        "  beta: intercept {}   slope {}",
        num(pls.beta[[0, 0]]),
        num(pls.beta[[1, 0]])
    );

    let xRows: Vec<Vec<f64>> = (0..T)
        .map(|t| (0..N).map(|i| m[[t, i + 1]].toDouble()).collect())
        .collect();
    let yRow: Vec<f64> = (0..T).map(|t| m[[t, 0]].toDouble()).collect();
    let p1 = pls1Fit(&xRows, &yRow).expect("pls1Fit");
    println!(
        "pls1Fit:        rSquared {}   agrees with plsClosedForm: {}",
        num(p1.rSquared),
        p1.rSquared == pls.rSquared
    );

    let fc = forecast3prf(&yM, &xM, &z, "IS Full", (0, 1), (-1, 0)).expect("forecast3prf");
    println!(
        "forecast3prf(IS Full): first {}   last {}",
        num(fc[[0, 0]]),
        num(fc[[T - 1, 0]])
    );

    // the out-of-sample procedures: early entries are untrained, so only the
    // final forecast is printed
    let cv = forecast3prf(&yM, &xM, &z, "OOS Cross Val", (0, 1), (-1, 0)).expect("oos cross val");
    let rec = forecast3prf(&yM, &xM, &z, "OOS Recursive", (0, 1), (-1, 0)).expect("oos rec");
    println!("forecast3prf(OOS Cross Val):  last {}", num(cv[[T - 1, 0]]));
    println!("forecast3prf(OOS Recursive): last {}", num(rec[[T - 1, 0]]));
}

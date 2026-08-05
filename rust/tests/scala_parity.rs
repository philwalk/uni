//! Checks the Rust port against the committed reference in
//! `test-data/tprf3-parity/`, produced by the Scala implementation.
//!
//! The Scala side (`uni.stats.Tprf3ParitySuite`) checks itself against the same
//! files, so the pair pins both implementations to one set of numbers without
//! either test needing the other language installed. A change that moves Rust's
//! results fails here; a change that moves only Scala's fails there.
//!
//! Regenerate the reference with `sbt "runMain uni.apps.Tprf3ParityGen"` — and
//! only when the values are meant to move.

#![allow(
    clippy::expect_used,
    clippy::panic,
    reason = "a missing or malformed fixture should abort the test loudly, not be handled"
)]

use std::collections::HashMap;
use std::fs;
use std::path::Path;
use std::path::PathBuf;

use ndarray::Array2;
use t3prf::Tprf3Result;
use t3prf::estimate_3prf_is_full;
use t3prf::estimate_3prf_oos_cv;
use t3prf::estimate_3prf_oos_rec;

/// Matches the fixtures written by Tprf3ParityGen.
const CASES: &[(usize, usize, usize)] = &[(100, 10, 2), (140, 12, 3), (60, 25, 2)];

/// Absolute floor plus relative term — a plain relative test is too harsh on
/// forecasts that land near zero. On these fixtures the two implementations
/// actually agree to ~3e-15 relative, so this leaves ~6 orders of headroom for
/// platform BLAS differences while still catching anything economically real.
const ATOL: f64 = 1e-12;
const RTOL: f64 = 1e-9;

fn fixture_dir() -> PathBuf {
    // CARGO_MANIFEST_DIR is <repo>/rust; the fixtures are shared with Scala.
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../test-data/tprf3-parity")
        .canonicalize()
        .expect("test-data/tprf3-parity missing — regenerate with: sbt \"runMain uni.apps.Tprf3ParityGen\"")
}

fn load_csv(path: &Path) -> Array2<f64> {
    let text =
        fs::read_to_string(path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()));
    let rows: Vec<Vec<f64>> = text
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(|l| {
            l.split(',')
                .map(|v| v.trim().parse::<f64>().expect("parse f64"))
                .collect()
        })
        .collect();
    let (r, c) = (rows.len(), rows[0].len());
    let flat: Vec<f64> = rows.into_iter().flatten().collect();
    Array2::from_shape_vec((r, c), flat).expect("consistent row widths")
}

/// Reference values keyed by "<tag> <field>".
fn load_reference(dir: &Path) -> HashMap<String, f64> {
    let path = dir.join("scala-reference.txt");
    let text =
        fs::read_to_string(&path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()));
    text.lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .filter_map(|l| {
            let mut it = l.split_whitespace();
            match (it.next(), it.next(), it.next()) {
                (Some(tag), Some(field), Some(v)) => {
                    Some((format!("{tag} {field}"), v.parse().expect("parse f64")))
                }
                _ => None,
            }
        })
        .collect()
}

fn close(a: f64, b: f64) -> bool {
    if a.is_nan() || b.is_nan() {
        a.is_nan() == b.is_nan()
    } else {
        (a - b).abs() <= ATOL + RTOL * a.abs().max(b.abs())
    }
}

fn check(reference: &HashMap<String, f64>, tag: &str, r: &Tprf3Result) {
    let cmp = |field: String, got: f64| {
        let key = format!("{tag} {field}");
        let want = *reference
            .get(&key)
            .unwrap_or_else(|| panic!("reference has no entry for {key}"));
        assert!(
            close(got, want),
            "{key}: got {got}, reference {want} (|delta|={})",
            (got - want).abs()
        );
    };
    cmp("r2".to_string(), r.r_squared);
    cmp("enc".to_string(), r.encnew);
    for i in 0..r.forecasts.nrows() {
        cmp(format!("f{i}"), r.forecasts[[i, 0]]);
    }
    for i in 0..r.rollfore.nrows() {
        cmp(format!("roll{i}"), r.rollfore[[i, 0]]);
    }
}

#[test]
fn matches_scala_reference() {
    let dir = fixture_dir();
    let reference = load_reference(&dir);

    for &(t, n, l) in CASES {
        let tag = format!("T{t}N{n}L{l}");
        let x = load_csv(&dir.join(format!("{tag}_X.csv")));
        let y = load_csv(&dir.join(format!("{tag}_y.csv")));
        let z = load_csv(&dir.join(format!("{tag}_Z.csv")));

        check(
            &reference,
            &format!("{tag}/isfull"),
            &estimate_3prf_is_full(&y, &x, &z).expect("IS Full"),
        );
        check(
            &reference,
            &format!("{tag}/oosrec"),
            &estimate_3prf_oos_rec(&y, &x, &z, t / 2).expect("OOS Recursive"),
        );
        check(
            &reference,
            &format!("{tag}/ooscv01"),
            &estimate_3prf_oos_cv(&y, &x, &z, 0, 1).expect("OOS Cross Val (0,1)"),
        );
        check(
            &reference,
            &format!("{tag}/ooscv23"),
            &estimate_3prf_oos_cv(&y, &x, &z, 2, 3).expect("OOS Cross Val (2,3)"),
        );
    }
}

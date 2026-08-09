//! The `Big` CSV loaders against the fixture's `csvdim`/`csvcell` rows — every cell of
//! `test-data/big-parity/inputs/cells.csv` as `loadMatBig` reads it, rendered and compared
//! with the Scala's own answers. The CSV deliberately contains `$2,000.00` **unquoted**, so
//! the comma splits it: padding, missing cells and the `%`/`$` grammar are all pinned.

#![allow(
    clippy::expect_used,
    clippy::panic,
    reason = "a missing fixture should abort the test loudly"
)]

use std::sync::Arc;

use t3prf::udata::Big;
use t3prf::upath::{PathContext, UPath, UserInfo};

fn fixture_root() -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../test-data/big-parity")
}

fn csv_path() -> UPath {
    let root = fixture_root().join("inputs");
    let d = root.to_string_lossy().replace('\\', "/");
    let ctx = Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", &d, &d),
        cfg!(windows),
    ));
    UPath::resolve(&ctx, &format!("{d}/cells.csv")).expect("resolve cells.csv")
}

fn render(b: &Big) -> String {
    if b.isNaN() { "!nan".to_owned() } else { b.toString() }
}

#[test]
fn every_cell_matches_the_scala() {
    let reference = std::fs::read_to_string(fixture_root().join("scala-reference.txt"))
        .expect("missing fixture; run BigParityGen");
    let m = csv_path().loadMatBig();

    let mut checked = 0;
    for row in reference.lines().filter(|l| !l.starts_with('#')) {
        let p: Vec<&str> = row.split('\t').collect();
        match p.as_slice() {
            ["csvdim", _, want] => {
                assert_eq!(format!("{},{}", m.nrows(), m.ncols()), *want, "dimensions");
                checked += 1;
            }
            ["csvcell", rc, want] => {
                let (r, c) = rc.split_once(',').expect("row,col");
                let (r, c): (usize, usize) = (r.parse().expect("row"), c.parse().expect("col"));
                assert_eq!(render(&m[[r, c]]), *want, "cell {r},{c}");
                checked += 1;
            }
            _ => {}
        }
    }
    assert!(checked > 10, "the csv rows must actually be present: {checked}");
}

#[test]
fn the_alias_loaders_agree() {
    let p = csv_path();
    let a = p.loadMatBig();
    let b = p.loadMatB();
    let c = p.readCsvB();
    assert_eq!(a.dim(), b.dim());
    assert_eq!(a.dim(), c.dim());
    assert_eq!(render(&a[[0, 0]]), render(&b[[0, 0]]));
    assert_eq!(render(&a[[0, 0]]), render(&c[[0, 0]]));
    // And the header-aware form sees the same data region.
    let t = p.loadSmartBig();
    assert_eq!(t.mat.nrows(), a.nrows(), "loadSmartBig data rows");
}

//! Checks `upath::hash` against the committed reference in `test-data/hash-parity/`,
//! produced by `uni`.
//!
//! The Scala side (`uni.io.HashParitySuite`) checks itself against the same files.
//!
//! For `md5`, `sha256` and `cksum` this is a *second* check — the unit tests in
//! `upath::hash` already pin them to RFC 1321, FIPS 180-4 and the POSIX utility, so
//! those three are anchored to published specifications rather than to Scala.
//!
//! `hash64` is different: it has no external specification, agrees with xxHash on
//! nothing despite its name, and so the Scala is its only definition. This fixture is
//! the entirety of that definition. The `block-halves` pair guards a defect that was
//! fixed in both languages together — `hash64` used to ignore bytes 32–63 of every
//! block, so the pair hashed identically.
//!
//! Regenerate with `sbt "runMain uni.apps.HashParityGen"`.

#![allow(
    clippy::expect_used,
    clippy::panic,
    reason = "a missing or malformed fixture should abort the test loudly, not be handled"
)]

use std::path::PathBuf;
use std::sync::Arc;

use uni::upath::PathContext;
use uni::upath::UPath;
use uni::upath::UserInfo;

/// One line of the reference.
struct Row {
    name: String,
    size: u64,
    crc: u32,
    len: u64,
    hash64: String,
    md5: String,
    sha256: String,
}

fn fixture_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../test-data/hash-parity")
}

fn ctx(dir: &std::path::Path) -> Arc<PathContext> {
    let dir = dir.to_string_lossy().replace('\\', "/");
    Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", &dir, &dir),
        cfg!(windows),
    ))
}

fn load_reference() -> Vec<Row> {
    let path = fixture_dir().join("scala-reference.txt");
    let text = std::fs::read_to_string(&path).unwrap_or_else(|e| {
        panic!("cannot read {path:?}: {e}. Regenerate with: sbt \"runMain uni.apps.HashParityGen\"")
    });
    let rows: Vec<Row> = text
        .lines()
        .filter(|l| !l.starts_with('#') && !l.is_empty())
        .map(|line| {
            let f: Vec<&str> = line.split('\t').collect();
            assert_eq!(f.len(), 7, "malformed fixture line: {line}");
            Row {
                name: f[0].to_owned(),
                size: f[1].parse().expect("size"),
                crc: f[2].parse().expect("crc"),
                len: f[3].parse().expect("len"),
                hash64: f[4].to_owned(),
                md5: f[5].to_owned(),
                sha256: f[6].to_owned(),
            }
        })
        .collect();
    assert!(rows.len() >= 20, "only {} cases in fixture", rows.len());
    rows
}

fn input(name: &str) -> UPath {
    let dir = fixture_dir().join("inputs");
    let file = dir.join(format!("{name}.bin"));
    assert!(file.is_file(), "missing fixture input {file:?}");
    UPath::resolve(&ctx(&dir), &file.to_string_lossy().replace('\\', "/")).expect("resolves")
}

#[test]
fn every_hash_matches_the_scala_reference() {
    for row in load_reference() {
        let p = input(&row.name);
        let c = p.cksum();
        assert_eq!(c.len, row.size, "[{}] byte count", row.name);
        assert_eq!(c.len, row.len, "[{}] cksum length field", row.name);
        assert_eq!(c.crc, row.crc, "[{}] cksum", row.name);
        assert_eq!(p.hash64(), row.hash64, "[{}] hash64", row.name);
        assert_eq!(p.md5(), row.md5, "[{}] md5", row.name);
        assert_eq!(p.sha256(), row.sha256, "[{}] sha256", row.name);
    }
}

#[test]
fn the_block_halves_pair_is_separated_by_every_hash() {
    // Two 128-byte inputs differing in 64 of their bytes. `hash64` used to return the
    // same value for both, because it never mixed bytes 32–63 of a block — for a
    // duplicate-file finder, that is a false positive on entirely different files.
    let a = input("block-halves-a");
    let b = input("block-halves-b");

    assert_ne!(a.hash64(), b.hash64(), "hash64 must see the whole block");
    assert_ne!(a.md5(), b.md5());
    assert_ne!(a.sha256(), b.sha256());
    assert_ne!(a.cksum().crc, b.cksum().crc);
    assert_eq!(
        a.cksum().len,
        b.cksum().len,
        "same length, different content"
    );
}

#[test]
fn a_missing_file_is_empty_but_try_reports_why() {
    let dir = fixture_dir().join("inputs");
    let p = UPath::resolve(&ctx(&dir), "no-such-file.bin").expect("resolves");

    assert_eq!(p.md5(), "");
    assert_eq!(p.sha256(), "");
    assert_eq!(p.hash64(), "");
    assert_eq!(p.cksum().len, 0);

    let err = p.try_md5().expect_err("missing file should error");
    assert_eq!(err.kind(), std::io::ErrorKind::NotFound);
    assert!(p.try_sha256().is_err());
    assert!(p.try_hash64().is_err());
    assert!(p.try_cksum().is_err());
}

#[test]
fn the_empty_file_case_agrees_with_the_published_vectors() {
    // Cross-checks the fixture itself: if a regeneration ever corrupted it, these
    // three constants are independent of anything Scala produced.
    let p = input("pattern-0");
    assert_eq!(p.md5(), "d41d8cd98f00b204e9800998ecf8427e");
    assert_eq!(
        p.sha256(),
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    );
    assert_eq!(p.cksum().crc, 4_294_967_295);
}

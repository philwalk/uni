//! Line-oriented reading: `lines`, `firstLine`, `eachLine`, `linesStream`, `withLines`.
//!
//! # Carriage returns never survive a line
//!
//! That is uni policy, and this file is where it is pinned. A residual `\r` inside a line is one
//! of the larger classes of hard-to-diagnose bug: invisible in most output, survives comparison,
//! and breaks a string match for no visible reason. Every line-oriented reader here removes **all**
//! of them — deliberately stricter than Rust's `BufRead::lines`, which strips only the one
//! immediately before the `\n`.
//!
//! Splitting on `\n` and discarding `\r` also makes the result independent of where the file came
//! from: `\r\n` and `\n` land on the same lines, which matters because Windows emits either.
//!
//! `contentAsString` and `byteArray` are the escape hatch, and are asserted below to keep the
//! bytes intact — they are the only way to see a `\r` through this API, and that is the design.
//!
//! This divergence went unnoticed until an interior-CR case was tried by hand: `csv-parity` has a
//! `lone-cr` case, but it exercises CR as a line *separator*, never inside a line.

#![allow(
    clippy::expect_used,
    clippy::panic,
    clippy::unwrap_used,
    reason = "a fixture that cannot be written should abort the test loudly"
)]

use std::sync::Arc;

use t3prf::upath::PathContext;
use t3prf::upath::UPath;
use t3prf::upath::UserInfo;
use t3prf::upath::io::Charset;

/// A path in a temp directory, with the bytes written.
fn with_bytes(dir: &std::path::Path, name: &str, bytes: &[u8]) -> UPath {
    let d = dir.to_string_lossy().replace('\\', "/");
    let ctx = Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", &d, &d),
        cfg!(windows),
    ));
    let p = UPath::resolve(&ctx, &format!("{d}/{name}")).unwrap_or_else(|e| panic!("resolve: {e}"));
    std::fs::write(p.as_std_path(), bytes).unwrap_or_else(|e| panic!("write: {e}"));
    p
}

/// Interior CR, a CRLF terminator, and a lone CR mid-line — the exact bytes the Scala was probed
/// with, so the expected values are the Scala's answers rather than a guess.
const MIXED: &[u8] = b"a\rb\nc\r\nd\re\n";
const EXPECTED: [&str; 3] = ["ab", "c", "de"];

#[test]
fn lines_removes_every_carriage_return() {
    let t = tempfile::tempdir().expect("tmp");
    let p = with_bytes(t.path(), "mixed.txt", MIXED);
    assert_eq!(p.lines(), EXPECTED, "lines");
    assert_eq!(
        p.try_lines().expect("try_lines"),
        EXPECTED,
        "try_lines agrees with lines"
    );
}

#[test]
fn lines_stream_removes_every_carriage_return() {
    let t = tempfile::tempdir().expect("tmp");
    let p = with_bytes(t.path(), "mixed.txt", MIXED);
    let got: Vec<String> = p.linesStream(Charset::default()).collect();
    assert_eq!(got, EXPECTED, "linesStream");
    // And the eager and lazy paths must not disagree, which is how a fix to one of them would slip.
    assert_eq!(got, p.lines(), "linesStream agrees with lines");
}

#[test]
fn with_lines_and_each_line_agree() {
    let t = tempfile::tempdir().expect("tmp");
    let p = with_bytes(t.path(), "mixed.txt", MIXED);
    let counted = p.withLines(Charset::default(), |it| it.count());
    assert_eq!(counted, 3, "withLines sees three lines");
    let mut seen: Vec<String> = Vec::new();
    p.eachLine(|l| seen.push(l.to_owned()));
    assert_eq!(seen, EXPECTED, "eachLine");
    assert_eq!(p.firstLine(), "ab", "firstLine");
}

#[test]
fn content_and_bytes_keep_the_carriage_returns() {
    // The escape hatch. If these ever start stripping, the only way to see the real bytes is gone.
    let t = tempfile::tempdir().expect("tmp");
    let p = with_bytes(t.path(), "mixed.txt", MIXED);
    assert_eq!(p.byteArray(), MIXED, "byteArray is untouched");
    assert!(
        p.contentAsString().contains('\r'),
        "contentAsString keeps CR: {:?}",
        p.contentAsString()
    );
}

#[test]
fn line_endings_do_not_change_the_lines() {
    // The point of splitting on `\n` and discarding `\r`: the same content sourced from Windows or
    // Unix yields identical lines.
    let t = tempfile::tempdir().expect("tmp");
    let unix = with_bytes(t.path(), "unix.txt", b"one\ntwo\nthree\n");
    let dos = with_bytes(t.path(), "dos.txt", b"one\r\ntwo\r\nthree\r\n");
    let no_final = with_bytes(t.path(), "nofinal.txt", b"one\r\ntwo\r\nthree");
    assert_eq!(unix.lines(), ["one", "two", "three"]);
    assert_eq!(dos.lines(), unix.lines(), "CRLF matches LF");
    assert_eq!(no_final.lines(), unix.lines(), "a missing final newline too");
}

#[test]
fn a_missing_file_reads_as_empty_not_an_error() {
    let t = tempfile::tempdir().expect("tmp");
    let d = t.path().to_string_lossy().replace('\\', "/");
    let ctx = Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", &d, &d),
        cfg!(windows),
    ));
    let missing = UPath::resolve(&ctx, &format!("{d}/absent.txt")).expect("resolve");
    assert!(missing.lines().is_empty(), "lines");
    assert_eq!(missing.linesStream(Charset::default()).count(), 0, "linesStream");
    assert_eq!(missing.withLines(Charset::default(), |it| it.count()), 0, "withLines");
    assert_eq!(missing.firstLine(), "", "firstLine");
    // A directory is not a file, and must read as empty rather than as a truncated stream.
    let dir = UPath::resolve(&ctx, &d).expect("resolve dir");
    assert_eq!(dir.linesStream(Charset::default()).count(), 0, "a directory streams empty");
}

#[test]
fn lines_stream_is_lazy_and_closes_early() {
    // The reason the Scala needs `withLines` and this does not: the iterator owns its reader, so
    // abandoning it early still closes the handle. Observable on Windows, where an open handle
    // blocks deletion.
    let t = tempfile::tempdir().expect("tmp");
    let p = with_bytes(t.path(), "big.txt", b"1\n2\n3\n4\n5\n");
    let first_two: Vec<String> = p.linesStream(Charset::default()).take(2).collect();
    assert_eq!(first_two, ["1", "2"]);
    assert!(
        std::fs::remove_file(p.as_std_path()).is_ok(),
        "the handle must be closed after an abandoned stream"
    );
}

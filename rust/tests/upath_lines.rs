//! Line-oriented reading: `lines`, `firstLine`, `eachLine`, `linesStream`, `withLines`.
//!
//! # Carriage returns: exactly a split on `\r?\n`
//!
//! That is uni policy, and this file is where it is pinned. The only CR a line reader removes is
//! the one a newline consumed -- so a CRLF terminator loses its CR, while **an interior `\r` and a
//! trailing `\r` at end-of-file both survive**, neither having a `\n` to pair with.
//!
//! Stated as a pattern rather than as "remove every `\r`" because those two rules disagree, and
//! only the pattern is self-consistent: it is what makes lines independent of the OS that wrote
//! the file (`\r\n` and `\n` land on the same lines, and Windows emits either) without
//! discarding a `\r` that carries information. Rust's `BufRead::lines` applies the same rule.
//!
//! `contentAsString` and `byteArray` keep the bytes exactly, and are asserted below.
//!
//! Both halves of this went unnoticed for a while. The interior-CR case surfaced only when tried
//! by hand -- `csv-parity` has a `lone-cr` case, but it exercises CR as a line *separator*. The
//! end-of-file case surfaced later still, from noticing that "remove every CR" and the stated
//! `\r?\n` split cannot both be true.

#![allow(
    clippy::expect_used,
    clippy::panic,
    clippy::unwrap_used,
    reason = "a fixture that cannot be written should abort the test loudly"
)]

use std::sync::Arc;

use uni::upath::PathContext;
use uni::upath::UPath;
use uni::upath::UserInfo;
use uni::upath::io::Charset;

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

/// Interior CR, a CRLF terminator, and a second interior CR: the CRLF loses its CR, the two
/// interior ones stay. Kept as the Scala is probed with the same bytes.
const MIXED: &[u8] = b"a\rb\nc\r\nd\re\n";
const EXPECTED: [&str; 3] = ["a\rb", "c", "d\re"];

#[test]
fn lines_keeps_an_interior_carriage_return() {
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
fn lines_stream_keeps_an_interior_carriage_return() {
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
    assert_eq!(p.firstLine(), "a\rb", "firstLine");
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
    // The half of the rule that does hold unconditionally: a terminator's CR always goes, so the
    // same content sourced from Windows or Unix yields identical lines.
    let t = tempfile::tempdir().expect("tmp");
    let unix = with_bytes(t.path(), "unix.txt", b"one\ntwo\nthree\n");
    let dos = with_bytes(t.path(), "dos.txt", b"one\r\ntwo\r\nthree\r\n");
    let no_final = with_bytes(t.path(), "nofinal.txt", b"one\r\ntwo\r\nthree");
    assert_eq!(unix.lines(), ["one", "two", "three"]);
    assert_eq!(dos.lines(), unix.lines(), "CRLF matches LF");
    assert_eq!(
        no_final.lines(),
        unix.lines(),
        "a missing final newline too"
    );
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
    assert_eq!(
        missing.linesStream(Charset::default()).count(),
        0,
        "linesStream"
    );
    assert_eq!(
        missing.withLines(Charset::default(), |it| it.count()),
        0,
        "withLines"
    );
    assert_eq!(missing.firstLine(), "", "firstLine");
    // A directory is not a file, and must read as empty rather than as a truncated stream.
    let dir = UPath::resolve(&ctx, &d).expect("resolve dir");
    assert_eq!(
        dir.linesStream(Charset::default()).count(),
        0,
        "a directory streams empty"
    );
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

#[test]
fn a_trailing_carriage_return_at_eof_is_data() {
    // The case that exposed the inconsistency. No `\n` follows this CR, so a split on `\r?\n`
    // never consumes it -- it belongs to the line. "Remove every CR" would have eaten it, which is
    // why that phrasing was wrong.
    let t = tempfile::tempdir().expect("tmp");
    let p = with_bytes(t.path(), "eofcr.txt", b"one\ntwo\r");
    assert_eq!(p.lines(), ["one", "two\r"], "lines");
    let streamed: Vec<String> = p.linesStream(Charset::default()).collect();
    assert_eq!(streamed, p.lines(), "linesStream agrees");
    // Contrast: the identical CR *with* a newline after it is a terminator and goes.
    let q = with_bytes(t.path(), "crlf.txt", b"one\ntwo\r\n");
    assert_eq!(
        q.lines(),
        ["one", "two"],
        "the same CR, now paired with a newline"
    );
}

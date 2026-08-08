//! Round-trip tests for `upath`'s file I/O.
//!
//! These touch a real temporary directory rather than the shared fixture: the
//! fixture pins *conversions*, which are pure, while these pin behaviour that only
//! shows up against a filesystem — CRLF handling, the deliberate trailing newline,
//! and the total-vs-fallible split.

#![allow(
    clippy::expect_used,
    clippy::panic,
    reason = "a broken temp dir should abort the test loudly, not be handled"
)]

use std::sync::Arc;

use t3prf::upath::Charset;
use t3prf::upath::PathContext;
use t3prf::upath::UPath;
use t3prf::upath::UserInfo;

/// A context rooted at a real temp directory, so `UPath` resolution and the
/// filesystem agree about where things are.
fn ctx(dir: &std::path::Path) -> Arc<PathContext> {
    let dir = dir.to_string_lossy().replace('\\', "/");
    Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", &dir, &dir),
        cfg!(windows),
    ))
}

fn temp_dir() -> tempfile::TempDir {
    tempfile::tempdir().expect("temp dir")
}

fn at(ctx: &Arc<PathContext>, name: &str) -> UPath {
    UPath::resolve(ctx, name).expect("resolves")
}

#[test]
fn write_then_read_round_trips() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, "round.txt");

    assert!(p.write("hello\nworld\n"));
    assert_eq!(p.contentAsString(), "hello\nworld\n");
    assert_eq!(p.lines(), vec!["hello", "world"]);
    assert_eq!(p.firstLine(), "hello");
    assert_eq!(p.byteArray().len(), 12);
}

#[test]
fn write_lines_appends_a_trailing_newline() {
    // Deliberate on the Scala side: "ensures the file isn't missing a newline at
    // EOF". Losing it would be an invisible behaviour change.
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, "lines.txt");

    assert!(p.writeLines(&["a", "b"]));
    assert_eq!(p.contentAsString(), "a\nb\n");
    // And the trailing newline does not produce a phantom empty last line.
    assert_eq!(p.lines(), vec!["a", "b"]);
}

#[test]
fn write_lines_uses_lf_on_every_platform() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, "endings.txt");

    p.writeLines(&["x", "y"]);
    assert!(!p.byteArray().contains(&b'\r'), "must not emit CR");
}

#[test]
fn crlf_input_reads_back_without_the_cr() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, "crlf.txt");

    p.write("one\r\ntwo\r\n");
    assert_eq!(p.lines(), vec!["one", "two"]);
    // The two decode paths must agree: BufRead::lines for strict UTF-8, and the
    // decoded-string split for everything else.
    assert_eq!(
        p.try_lines_with(Charset::Utf8).expect("utf8"),
        p.try_lines_with(Charset::Latin1).expect("latin1")
    );
}

#[test]
fn missing_file_is_empty_but_try_reports_why() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, "absent.txt");

    // The whole point of the two layers.
    assert_eq!(p.contentAsString(), "");
    assert_eq!(p.lines(), Vec::<String>::new());
    assert_eq!(p.byteArray(), Vec::<u8>::new());
    assert_eq!(p.firstLine(), "");

    let err = p.try_bytes().expect_err("missing file should error");
    assert_eq!(err.kind(), std::io::ErrorKind::NotFound);
    assert!(p.try_lines().is_err());
    assert!(!p.isFile());
}

#[test]
fn invalid_utf8_falls_back_to_latin1_but_strict_utf8_errors() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, "latin.txt");

    // 0xE9 is `é` in Latin-1 and not valid UTF-8 on its own.
    std::fs::write(p.as_std_path(), [b'a', 0xE9, b'b']).expect("write bytes");

    assert_eq!(p.contentAsString(), "a\u{e9}b");
    assert_eq!(
        p.try_content_string(Charset::Latin1).expect("latin1"),
        "a\u{e9}b"
    );
    let err = p
        .try_content_string(Charset::Utf8)
        .expect_err("strict utf8 should reject");
    assert_eq!(err.kind(), std::io::ErrorKind::InvalidData);
}

#[test]
fn each_line_visits_every_line_and_tolerates_a_missing_file() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, "each.txt");
    p.writeLines(&["1", "2", "3"]);

    let mut seen = Vec::new();
    p.eachLine(|l| seen.push(l.to_owned()));
    assert_eq!(seen, vec!["1", "2", "3"]);

    let mut none = 0;
    at(&c, "gone.txt").eachLine(|_| none += 1);
    assert_eq!(none, 0);
}

#[test]
fn charset_names_resolve_the_way_forname_does() {
    assert_eq!(Charset::by_name("UTF-8"), Charset::Utf8);
    assert_eq!(Charset::by_name("utf8"), Charset::Utf8);
    assert_eq!(Charset::by_name("ISO-8859-1"), Charset::Latin1);
    assert_eq!(Charset::by_name("latin1"), Charset::Latin1);
    assert_eq!(Charset::by_name(""), Charset::default());
    // Unknown names default to UTF-8 rather than failing, as the Scala does by
    // catching whatever `Charset.forName` throws.
    assert_eq!(Charset::by_name("no-such-charset"), Charset::Utf8);
}

//! CSV behaviour that only shows up against a real filesystem.
//!
//! The parity fixture pins *parsing*, which is pure. These pin the rest: writing,
//! round-tripping, the total-vs-fallible split, and delimiter sniffing on a file
//! rather than on a list of lines.

#![allow(
    clippy::expect_used,
    clippy::panic,
    reason = "a broken temp dir should abort the test loudly, not be handled"
)]

use std::sync::Arc;

use uni::upath::CsvConfig;
use uni::upath::PathContext;
use uni::upath::UPath;
use uni::upath::UserInfo;

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

fn rows(cells: &[&[&str]]) -> Vec<Vec<String>> {
    cells
        .iter()
        .map(|r| r.iter().map(|s| (*s).to_owned()).collect())
        .collect()
}

#[test]
fn write_then_read_round_trips() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, "round.csv");

    let data = rows(&[&["a", "b"], &["1", "2"]]);
    assert!(p.writeCsv(&data));
    assert_eq!(p.csvRows(), data);
}

#[test]
fn awkward_fields_survive_a_round_trip() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, "awkward.csv");

    // Each of these needs quoting for a different reason, and the trailing-space
    // one would silently lose its spaces if the writer skipped quoting it — the
    // reader trims unquoted fields.
    let data = rows(&[
        &["plain", "a,b", "say \"hi\"", " padded "],
        &["x", "line\nbreak", "semi;colon", "tab\there"],
    ]);
    assert!(p.writeCsv(&data));
    assert_eq!(p.csvRows(), data);
}

#[test]
fn written_files_use_lf_on_every_platform() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, "endings.csv");
    p.writeCsv(&rows(&[&["a", "b"], &["c", "d"]]));
    assert!(!p.byteArray().contains(&b'\r'), "must not emit CR");
}

#[test]
fn a_missing_file_reads_as_empty_but_try_reports_why() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, "absent.csv");

    assert_eq!(p.csvRows(), Vec::<Vec<String>>::new());
    assert_eq!(p.csvRowsStream().count(), 0);
    let err = p.try_csv_rows().expect_err("missing file should error");
    assert_eq!(err.kind(), std::io::ErrorKind::NotFound);
}

#[test]
fn the_delimiter_is_sniffed_from_the_file() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    for (name, text, want) in [
        ("c.csv", "a,b,c\n1,2,3\n", vec!["a", "b", "c"]),
        ("s.csv", "a;b;c\n1;2;3\n", vec!["a", "b", "c"]),
        ("t.csv", "a\tb\tc\n1\t2\t3\n", vec!["a", "b", "c"]),
        ("p.csv", "a|b|c\n1|2|3\n", vec!["a", "b", "c"]),
    ] {
        let p = at(&c, name);
        p.write(text);
        assert_eq!(p.csvRows()[0], want, "sniffing failed for {name}");
    }
}

#[test]
fn an_explicit_delimiter_overrides_sniffing() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, "forced.csv");
    // Commas dominate, but the caller says semicolon — so the commas are content.
    p.write("a,b;c,d\n1,2;3,4\n");

    let cfg = CsvConfig::with_delimiter(b';');
    let out: Vec<Vec<String>> = p.try_csv_rows_stream(&cfg).expect("opens").collect();
    assert_eq!(out[0], vec!["a,b", "c,d"]);
}

#[test]
fn a_directory_is_not_readable_as_csv() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, ".");
    assert_eq!(p.csvRows(), Vec::<Vec<String>>::new());
    assert!(p.try_csv_rows().is_err());
}

#[test]
fn an_empty_file_yields_no_rows_rather_than_one_blank_one() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, "empty.csv");
    p.write("");
    assert_eq!(p.csvRows(), Vec::<Vec<String>>::new());

    // And a file of nothing but blank lines is equally empty.
    let q = at(&c, "blanks.csv");
    q.write("\n\n   \n");
    assert_eq!(q.csvRows(), Vec::<Vec<String>>::new());
}

#[test]
fn writing_zero_rows_produces_an_empty_file() {
    let dir = temp_dir();
    let c = ctx(dir.path());
    let p = at(&c, "none.csv");
    assert!(p.writeCsv::<String>(&[]));
    assert_eq!(p.byteArray().len(), 0);
    assert_eq!(p.csvRows(), Vec::<Vec<String>>::new());
}

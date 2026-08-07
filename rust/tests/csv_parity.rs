//! Checks `upath::csv` against the committed reference in `test-data/csv-parity/`,
//! produced by `uni.io.FastCsv`.
//!
//! The Scala side (`uni.io.CsvParitySuite`) checks itself against the same files, so
//! the pair pins both implementations to one set of expectations without either test
//! needing the other language installed.
//!
//! Unlike the path fixture there is no platform split: the parser works on bytes and
//! never consults the OS, so one reference serves every host. Line endings are part
//! of the input rather than the environment.
//!
//! Regenerate with `sbt "runMain uni.apps.CsvParityGen"` — and only when the
//! expectations are meant to move.

#![allow(
    clippy::expect_used,
    clippy::panic,
    reason = "a missing or malformed fixture should abort the test loudly, not be handled"
)]

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::Arc;

use t3prf::upath::CsvTable;
use t3prf::upath::PathContext;
use t3prf::upath::UPath;
use t3prf::upath::UserInfo;

fn fixture_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../test-data/csv-parity")
}

/// A context rooted at the fixture's input directory.
fn ctx(dir: &std::path::Path) -> Arc<PathContext> {
    let dir = dir.to_string_lossy().replace('\\', "/");
    Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", &dir, &dir),
        cfg!(windows),
    ))
}

/// Reverses the generator's escaping. Must consume `\\` as one unit, or `\\n` would
/// wrongly decode to a newline.
fn unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('t') => out.push('\t'),
            Some('n') => out.push('\n'),
            Some('r') => out.push('\r'),
            Some('\\') => out.push('\\'),
            other => panic!("unknown escape in fixture: {other:?}"),
        }
    }
    out
}

/// Expected rows, keyed by `(kind, case)` and ordered by row index.
type Expected = BTreeMap<(String, String), Vec<Vec<String>>>;

fn load_reference() -> Expected {
    let path = fixture_dir().join("scala-reference.txt");
    let text = std::fs::read_to_string(&path).unwrap_or_else(|e| {
        panic!("cannot read {path:?}: {e}. Regenerate with: sbt \"runMain uni.apps.CsvParityGen\"")
    });

    let mut out: Expected = BTreeMap::new();
    for line in text.lines() {
        if line.starts_with('#') || line.is_empty() {
            continue;
        }
        let parts: Vec<&str> = line.split('\t').collect();
        assert!(
            parts.len() >= 3,
            "malformed fixture line ({} fields): {line}",
            parts.len()
        );
        let kind = parts[0].to_owned();
        let case = parts[1].to_owned();
        let idx: usize = parts[2].parse().expect("row index");
        let fields: Vec<String> = parts[3..].iter().map(|f| unescape(f)).collect();

        let rows = out.entry((kind, case)).or_default();
        assert_eq!(rows.len(), idx, "fixture rows out of order at {line}");
        rows.push(fields);
    }
    assert!(!out.is_empty(), "fixture is empty");
    out
}

fn input(case: &str) -> UPath {
    let dir = fixture_dir().join("inputs");
    let file = dir.join(format!("{case}.csv"));
    assert!(file.is_file(), "missing fixture input {file:?}");
    UPath::resolve(&ctx(&dir), &file.to_string_lossy().replace('\\', "/")).expect("resolves")
}

#[test]
fn csv_rows_matches_the_scala_reference() {
    let reference = load_reference();
    let mut checked = 0;
    for ((kind, case), expected) in &reference {
        if kind != "rows" {
            continue;
        }
        let actual = input(case).csv_rows();
        assert_eq!(&actual, expected, "csv_rows differs for case [{case}]");
        checked += 1;
    }
    assert!(checked >= 20, "only {checked} cases checked");
}

#[test]
fn csv_rows_stream_matches_the_scala_reference() {
    let reference = load_reference();
    let mut checked = 0;
    for ((kind, case), expected) in &reference {
        if kind != "stream" {
            continue;
        }
        let actual: Vec<Vec<String>> = input(case).csv_rows_stream().collect();
        assert_eq!(&actual, expected, "csv_rows_stream differs for case [{case}]");
        checked += 1;
    }
    assert!(checked >= 20, "only {checked} cases checked");
}

#[test]
fn the_two_readings_agree_on_which_rows_exist() {
    // They may disagree about *width* — the stream only pads to its window — but
    // never about how many rows a file has, or about any field's content.
    let reference = load_reference();
    for ((kind, case), rows) in &reference {
        if kind != "rows" {
            continue;
        }
        let stream = reference
            .get(&("stream".to_owned(), case.clone()))
            .unwrap_or_else(|| panic!("no stream reading for [{case}]"));
        assert_eq!(rows.len(), stream.len(), "row count differs for [{case}]");
        for (r, (wide, narrow)) in rows.iter().zip(stream).enumerate() {
            assert!(
                wide.len() >= narrow.len(),
                "[{case}] row {r}: stream is wider than the full read"
            );
            assert_eq!(&wide[..narrow.len()], &narrow[..], "[{case}] row {r} content");
            assert!(
                wide[narrow.len()..].iter().all(String::is_empty),
                "[{case}] row {r}: full read added a non-empty field"
            );
        }
    }
}

#[test]
fn the_streaming_window_leaves_a_later_wider_row_jagged() {
    // The one case where the two readings are *meant* to differ, called out so a
    // change to the window is a deliberate act rather than a quiet fixture diff.
    let p = input("past-window");
    let stream: Vec<Vec<String>> = p.csv_rows_stream().collect();
    let all = p.csv_rows();

    assert_eq!(stream[0].len(), 2, "window saw only 2-wide rows");
    assert_eq!(stream[100].len(), 4, "the wide row must not be truncated");
    assert_eq!(all[0].len(), 4, "the full read knows the true width");
    assert_eq!(all.len(), stream.len());
}

#[test]
fn unescape_round_trips_the_generator_escaping() {
    assert_eq!(unescape(r"a\tb"), "a\tb");
    assert_eq!(unescape(r"a\nb"), "a\nb");
    assert_eq!(unescape(r"a\rb"), "a\rb");
    // The reason `\\` must be consumed as a unit.
    assert_eq!(unescape(r"a\\nb"), r"a\nb");
    assert_eq!(unescape(""), "");
}

/// Encodes a double the way the generator does: raw IEEE bits, or the literal `NaN`.
///
/// Comparing formatted decimals would test the two languages' float printers rather
/// than their parsers. Bits compare the values themselves.
fn cell(d: f64) -> String {
    if d.is_nan() {
        "NaN".to_owned()
    } else {
        format!("{:016x}", d.to_bits())
    }
}

#[test]
fn read_csv_smart_matches_the_scala_reference() {
    let reference = load_reference();
    let mut checked = 0;
    for ((kind, case), expected) in &reference {
        if kind != "mat" {
            continue;
        }
        let table: CsvTable<f64> = input(case).read_csv_smart();
        let actual: Vec<Vec<String>> = table
            .mat
            .rows()
            .into_iter()
            .map(|r| r.iter().copied().map(cell).collect())
            .collect();
        assert_eq!(&actual, expected, "matrix differs for case [{case}]");
        checked += 1;
    }
    assert!(checked >= 15, "only {checked} cases had matrix data");
}

#[test]
fn header_detection_matches_the_scala_reference() {
    let reference = load_reference();
    let mut checked = 0;
    for ((kind, case), expected) in &reference {
        if kind != "hdr" {
            continue;
        }
        let table: CsvTable<f64> = input(case).read_csv_smart();
        // The generator writes one line per case; an empty header list is one
        // trailing empty field, which is how "no header" arrives.
        let want: &[String] = match expected.first() {
            Some(row) if row.len() == 1 && row[0].is_empty() => &[],
            Some(row) => row,
            None => &[],
        };
        assert_eq!(table.headers, want, "headers differ for case [{case}]");
        checked += 1;
    }
    assert!(checked >= 20, "only {checked} cases checked");
}

#[test]
fn the_numeric_cell_case_pins_big_string_parsing() {
    // Called out by name: this is the case that caught a trailing dot being valid
    // and `-0` losing its sign, both of which were guessed wrong first.
    let t: CsvTable<f64> = input("numeric-cells").read_csv_smart();
    assert_eq!(t.headers[0], "plain");
    let by = |name: &str, row: usize| t.col(name).unwrap_or_else(|| panic!("no col {name}"))[row];

    assert_eq!(by("currency", 0), 1234.56);
    assert_eq!(by("percent", 0), 0.12);
    assert_eq!(by("trailing-dot", 0), 4.0);
    assert_eq!(by("plus", 0), 5.0);
    assert_eq!(by("leading-dot", 0), 0.5);
    assert!(by("blank", 0).is_nan());
    assert!(by("junk", 0).is_nan());
    assert!(by("infinite", 0).is_nan(), "inf must not survive as infinity");
    assert!(by("notanumber", 0).is_nan());
    // BigDecimal has no signed zero.
    assert_eq!(by("plus", 1).to_bits(), 0_f64.to_bits());
}


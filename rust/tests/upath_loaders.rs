//! The named matrix/CSV loaders and the prefetching row reader.
//!
//! The named loaders (`loadMatD`, `loadMatF`, `readCsvF`, `loadSmartD`) are thin wrappers over the
//! generic `readCsv::<T>` / `read_csv_smart::<T>`, so what is tested here is mainly that they
//! *exist under the Scala names* and dispatch to the right cell type. The parsing itself is already
//! covered by `test-data/csv-parity/`.
//!
//! `csvRowsAsync` gets more scrutiny, because a prefetching reader can be wrong in ways a
//! synchronous one cannot: dropping the last row when the channel closes, reordering, or leaving a
//! thread running after the consumer walks away.

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

fn csv_at(dir: &std::path::Path, name: &str, body: &str) -> UPath {
    let d = dir.to_string_lossy().replace('\\', "/");
    let ctx = Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", &d, &d),
        cfg!(windows),
    ));
    let p = UPath::resolve(&ctx, &format!("{d}/{name}")).unwrap_or_else(|e| panic!("resolve: {e}"));
    std::fs::write(p.as_std_path(), body).unwrap_or_else(|e| panic!("write: {e}"));
    p
}

const BODY: &str = "alpha,beta\n1.5,2.5\n3.5,4.5\n";

#[test]
fn named_loaders_dispatch_to_the_right_cell_type() {
    let t = tempfile::tempdir().expect("tmp");
    let p = csv_at(t.path(), "m.csv", BODY);

    let d = p.loadMatD();
    assert_eq!(d.dim(), (2, 2), "loadMatD shape");
    assert!((d[[0, 0]] - 1.5).abs() < 1e-12, "loadMatD value");

    let f = p.loadMatF();
    assert_eq!(f.dim(), (2, 2), "loadMatF shape");
    assert!((f[[1, 1]] - 4.5).abs() < 1e-6, "loadMatF value");
    assert_eq!(p.readCsvF().dim(), f.dim(), "readCsvF aliases loadMatF");

    // `readCsv` is generic; the named form must agree with it.
    assert_eq!(p.readCsv::<f64>().dim(), d.dim(), "readCsv::<f64> agrees");
}

#[test]
fn load_smart_d_keeps_the_headers() {
    let t = tempfile::tempdir().expect("tmp");
    let p = csv_at(t.path(), "m.csv", BODY);
    let table = p.loadSmartD();
    assert_eq!(table.rows(), 2, "data rows, header excluded");
    assert_eq!(table.cols(), 2);
    assert_eq!(table.column_index("beta"), Some(1), "header lookup");
    let col = table.col("alpha").expect("alpha column");
    assert!((col[0] - 1.5).abs() < 1e-12);
}

#[test]
fn csv_rows_async_matches_the_synchronous_stream() {
    // The property that matters: prefetching changes timing, never content or order.
    let t = tempfile::tempdir().expect("tmp");
    // Enough rows to exceed the channel bound, so the backpressure path is exercised rather than
    // everything fitting in the buffer.
    let mut body = String::from("h1,h2\n");
    for i in 0..1000 {
        body.push_str(&format!("{i},{}\n", i * 2));
    }
    let p = csv_at(t.path(), "big.csv", &body);

    let sync: Vec<Vec<String>> = p.csvRowsStream().collect();
    let async_rows: Vec<Vec<String>> = p.csvRowsAsync().collect();
    assert_eq!(async_rows.len(), sync.len(), "row count");
    assert_eq!(async_rows, sync, "same rows in the same order");
    // The last row specifically: dropping it when the channel closes is the classic failure.
    assert_eq!(
        async_rows.last().map(|r| r[0].clone()),
        Some("999".to_owned()),
        "the final row must survive"
    );
}

#[test]
fn csv_rows_async_stops_cleanly_when_abandoned() {
    // Taking a couple of rows and walking away must not hang or panic: the send fails once the
    // receiver drops, and the reader thread ends.
    let t = tempfile::tempdir().expect("tmp");
    let mut body = String::from("h\n");
    for i in 0..5000 {
        body.push_str(&format!("{i}\n"));
    }
    let p = csv_at(t.path(), "abandon.csv", &body);
    let first: Vec<Vec<String>> = p.csvRowsAsync().take(3).collect();
    assert_eq!(first.len(), 3);
    // If the thread were still holding the file, this would fail on Windows.
    assert!(
        std::fs::remove_file(p.as_std_path()).is_ok(),
        "the reader thread must release the file"
    );
}

#[test]
fn csv_rows_async_is_empty_for_a_missing_file() {
    let t = tempfile::tempdir().expect("tmp");
    let d = t.path().to_string_lossy().replace('\\', "/");
    let ctx = Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", &d, &d),
        cfg!(windows),
    ));
    let missing = UPath::resolve(&ctx, &format!("{d}/nope.csv")).expect("resolve");
    assert_eq!(missing.csvRowsAsync().count(), 0);
}

//! `newerThan`/`olderThan` direction, mirrored by the test of the same name in
//! `uni.PathExtsSuite`. The direction was never pinned in either language, which is how both
//! carried the comparison inverted -- `a.newerThan(b)` answering whether *b* was newer -- until
//! the 0.16.0 pair probe surfaced it and the flip landed on both sides together.

#![allow(clippy::expect_used, clippy::panic, reason = "a failed setup should abort loudly")]

use std::fs;
use std::sync::Arc;
use std::time::{Duration, UNIX_EPOCH};

use t3prf::upath::{PathContext, UPath, UserInfo};

fn file_with_mtime(dir: &std::path::Path, name: &str, millis: u64) -> UPath {
    let d = dir.to_string_lossy().replace('\\', "/");
    let ctx = Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", &d, &d),
        cfg!(windows),
    ));
    let p = UPath::resolve(&ctx, &format!("{d}/{name}")).expect("resolve");
    fs::write(p.as_std_path(), "x").expect("write");
    let f = fs::OpenOptions::new().write(true).open(p.as_std_path()).expect("open");
    f.set_modified(UNIX_EPOCH + Duration::from_millis(millis)).expect("set_modified");
    p
}

#[test]
fn the_comparison_reads_as_the_name_does() {
    let t = tempfile::tempdir().expect("tmp");
    let old = file_with_mtime(t.path(), "old.txt", 1_000_000_000_000);
    let fresh = file_with_mtime(t.path(), "fresh.txt", 2_000_000_000_000);
    assert!(fresh.newerThan(&old), "the newer file IS newerThan the older");
    assert!(!old.newerThan(&fresh), "the older file is NOT newerThan the newer");
    assert!(old.olderThan(&fresh), "the older file IS olderThan the newer");
    assert!(!fresh.olderThan(&old), "the newer file is NOT olderThan the older");
    assert!(!old.newerThan(&old), "strict: a file is not newer than itself");
}

//! That `pathsIter`/`filesIter`/`walk` are genuinely lazy, and that the eager spellings keep their
//! order. Mirrors `uni.LazyTraversalSuite`.
//!
//! The distinction is the reason both spellings exist: an ordered listing must be complete before
//! it can be ordered, so on a slow USB or network directory `paths` cannot yield anything until the
//! last entry arrives. `pathsIter` gives that up for first-result latency.

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

fn tree_of(n: usize) -> (tempfile::TempDir, UPath) {
    let t = tempfile::tempdir().expect("tmp");
    let d = t.path().to_string_lossy().replace('\\', "/");
    let ctx = Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", &d, &d),
        cfg!(windows),
    ));
    let root = UPath::resolve(&ctx, &d).expect("resolve root");
    let sub = t.path().join("sub");
    std::fs::create_dir(&sub).expect("mkdir");
    for i in 0..n {
        std::fs::write(t.path().join(format!("f{i:04}.txt")), "x").expect("write");
        std::fs::write(sub.join(format!("g{i:04}.txt")), "x").expect("write");
    }
    (t, root)
}

#[test]
fn paths_iter_yields_before_the_listing_is_complete() {
    let (_t, root) = tree_of(300);
    let mut it = root.pathsIter();
    let first = it.next().expect("an entry");
    assert!(first.exists(), "yielded a real entry");
    // Dropping mid-listing must release the handle; on Windows a held handle blocks removal.
    drop(it);
    assert!(
        root.isDirectory(),
        "still a directory after an abandoned listing"
    );
}

#[test]
fn taking_five_does_not_walk_the_whole_tree() {
    let (_t, root) = tree_of(300);
    let five: Vec<UPath> = root.walk().take(5).collect();
    assert_eq!(five.len(), 5, "stopped early");
}

#[test]
fn lazy_and_eager_see_the_same_entries() {
    let (_t, root) = tree_of(20);
    let mut lazy_names: Vec<String> = root
        .pathsIter()
        .map(|p| p.baseName().unwrap_or_default().to_owned())
        .collect();
    let mut eager_names: Vec<String> = root
        .paths()
        .iter()
        .map(|p| p.baseName().unwrap_or_default().to_owned())
        .collect();
    lazy_names.sort_unstable();
    eager_names.sort_unstable();
    assert_eq!(lazy_names, eager_names, "same directory entries");

    let mut lazy_tree: Vec<String> = root
        .walk()
        .map(|p| p.baseName().unwrap_or_default().to_owned())
        .collect();
    let mut eager_tree: Vec<String> = root
        .pathsTree()
        .iter()
        .map(|p| p.baseName().unwrap_or_default().to_owned())
        .collect();
    lazy_tree.sort_unstable();
    eager_tree.sort_unstable();
    assert_eq!(lazy_tree, eager_tree, "same tree entries");
}

#[test]
fn each_path_visits_what_paths_iter_yields() {
    let (_t, root) = tree_of(5);
    let mut visited: Vec<String> = Vec::new();
    root.eachPath(|p| visited.push(p.baseName().unwrap_or_default().to_owned()));
    let mut yielded: Vec<String> = root
        .pathsIter()
        .map(|p| p.baseName().unwrap_or_default().to_owned())
        .collect();
    visited.sort_unstable();
    yielded.sort_unstable();
    assert_eq!(visited, yielded, "same entries, either spelling");
}

#[test]
fn files_iter_aliases_paths_iter() {
    let (_t, root) = tree_of(5);
    let a: Vec<String> = root
        .pathsIter()
        .map(|p| p.baseName().unwrap_or_default().to_owned())
        .collect();
    let b: Vec<String> = root
        .filesIter()
        .map(|p| p.baseName().unwrap_or_default().to_owned())
        .collect();
    assert_eq!(a.len(), b.len(), "same count");
}

#[test]
fn a_non_directory_and_a_missing_path_iterate_empty() {
    let t = tempfile::tempdir().expect("tmp");
    let d = t.path().to_string_lossy().replace('\\', "/");
    let ctx = Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", &d, &d),
        cfg!(windows),
    ));
    let f = UPath::resolve(&ctx, &format!("{d}/plain.txt")).expect("resolve");
    std::fs::write(f.as_std_path(), "x").expect("write");
    assert_eq!(f.pathsIter().count(), 0, "a file lists empty");
    let missing = UPath::resolve(&ctx, &format!("{d}/absent")).expect("resolve");
    assert_eq!(missing.pathsIter().count(), 0, "a missing path lists empty");
    assert_eq!(missing.walk().count(), 0, "and walks empty");
}

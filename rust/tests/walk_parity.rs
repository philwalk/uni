//! Checks `upath::walk` against the committed reference in `test-data/walk-parity/`, produced by
//! `uni`. The Scala side (`uni.WalkParitySuite`) checks itself against the same files.
//!
//! # What this pins, and what it cannot
//!
//! Contents, not order. Neither `File.listFiles`/`Files.walk` nor Rust's `read_dir` guarantees
//! sibling order, so both sides sort before comparing. The one ordering property that *is*
//! guaranteed — pre-order, a directory before its contents — survives no sort, so it lives in
//! the unit tests in `upath::walk` instead.
//!
//! The input tree is committed under `inputs/`, so both languages walk an identical shape rather
//! than each building its own and hoping they match.
//!
//! Regenerate with `sbt "runMain uni.apps.WalkParityGen"`.

#![allow(
    clippy::expect_used,
    clippy::panic,
    clippy::unwrap_used,
    reason = "a missing or malformed fixture should abort the test loudly, not be handled"
)]

use std::path::PathBuf;
use std::sync::Arc;

use t3prf::upath::PathContext;
use t3prf::upath::UPath;
use t3prf::upath::UserInfo;

fn fixture_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../test-data/walk-parity/inputs")
}

fn rows() -> Vec<Vec<String>> {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../test-data/walk-parity/scala-reference.txt");
    let text = std::fs::read_to_string(&path).unwrap_or_else(|e| {
        panic!("cannot read {path:?}: {e}. Regenerate with: sbt \"runMain uni.apps.WalkParityGen\"")
    });
    text.lines()
        .filter(|l| !l.starts_with('#') && !l.trim().is_empty())
        .map(|l| l.split('\t').map(str::to_owned).collect())
        .collect()
}

/// The fixture root, with the context it was resolved in.
///
/// The context comes back alongside rather than being read off the `UPath`: `ctx()` is
/// `pub(crate)`, and an integration test is a separate crate, so it cannot reach it.
fn ctx_and_root() -> (Arc<PathContext>, UPath) {
    let canon = fixture_root()
        .canonicalize()
        .unwrap_or_else(|e| panic!("fixture tree missing: {e}"));
    let slashed = canon.to_string_lossy().replace('\\', "/");
    // Windows canonicalize prepends a verbatim prefix, which is not a form the resolver expects.
    let d = slashed.strip_prefix("//?/").unwrap_or(&slashed).to_owned();
    let ctx = Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", &d, &d),
        cfg!(windows),
    ));
    let root = UPath::resolve(&ctx, &d).unwrap_or_else(|e| panic!("resolve root: {e}"));
    (ctx, root)
}

/// A path relative to the fixture root, forward-slashed, with the root itself as ".".
fn rel(root: &UPath, p: &UPath) -> String {
    let (r, s) = (root.posx(), p.posx());
    match s.strip_prefix(r) {
        Some(rest) => {
            let t = rest.trim_start_matches('/');
            if t.is_empty() {
                ".".to_owned()
            } else {
                t.to_owned()
            }
        }
        None => s.to_owned(),
    }
}

fn sorted_rel(root: &UPath, ps: &[UPath]) -> Vec<String> {
    let mut v: Vec<String> = ps.iter().map(|p| rel(root, p)).collect();
    v.sort();
    v
}

/// The recorded list; an empty field is an empty Vec rather than a one-element Vec of "".
fn expected(r: &[String]) -> Vec<String> {
    if r[2].is_empty() {
        Vec::new()
    } else {
        r[2].split(',').map(str::to_owned).collect()
    }
}

fn start(ctx: &Arc<PathContext>, root: &UPath, key: &str) -> UPath {
    if key == "." {
        root.clone()
    } else {
        UPath::resolve(ctx, &format!("{}/{key}", root.posx()))
            .unwrap_or_else(|e| panic!("resolve {key}: {e}"))
    }
}

#[test]
fn fixture_and_input_tree_are_present() {
    let all = rows();
    assert!(all.len() >= 20, "only {} rows", all.len());
    assert!(
        fixture_root().is_dir(),
        "missing input tree {:?}",
        fixture_root()
    );
    for kind in [
        "paths",
        "subdirs",
        "subfiles",
        "tree",
        "alias-walk",
        "alias-files",
    ] {
        assert!(all.iter().any(|r| r[0] == kind), "no [{kind}] rows");
    }
}

#[test]
fn listings_match_the_reference() {
    let (ctx, root) = ctx_and_root();
    for r in rows() {
        let p = match r[0].as_str() {
            "paths" | "subdirs" | "subfiles" => start(&ctx, &root, &r[1]),
            _ => continue,
        };
        let got = match r[0].as_str() {
            "paths" => sorted_rel(&root, &p.paths()),
            "subdirs" => sorted_rel(&root, &p.subdirs()),
            _ => sorted_rel(&root, &p.subfiles()),
        };
        assert_eq!(got, expected(&r), "{} of [{}]", r[0], r[1]);
    }
}

#[test]
fn tree_matches_the_reference_root_included() {
    let (ctx, root) = ctx_and_root();
    for r in rows().into_iter().filter(|r| r[0] == "tree") {
        let got = sorted_rel(&root, &start(&ctx, &root, &r[1]).pathsTree());
        assert_eq!(got, expected(&r), "tree of [{}]", r[1]);
    }
}

#[test]
fn walk_and_files_are_aliases() {
    let (_ctx, root) = ctx_and_root();
    for r in rows() {
        match r[0].as_str() {
            "alias-walk" => assert_eq!(
                (sorted_rel(&root, &root.walk()) == sorted_rel(&root, &root.pathsTree()))
                    .to_string(),
                r[2]
            ),
            "alias-files" => assert_eq!(
                (sorted_rel(&root, &root.files()) == sorted_rel(&root, &root.paths())).to_string(),
                r[2]
            ),
            _ => {}
        }
    }
}

#[test]
fn hidden_and_spaced_names_are_listed() {
    // Both are easy to drop by accident, and a port that filtered them would still look correct
    // on a tidy tree.
    let (_ctx, root) = ctx_and_root();
    let names = sorted_rel(&root, &root.paths());
    assert!(
        names.contains(&".dotfile".to_owned()),
        "dotfile missing from {names:?}"
    );
    assert!(
        names.contains(&"with space.txt".to_owned()),
        "spaced name missing from {names:?}"
    );
}

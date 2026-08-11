//! The three parent spellings, and why they coincide in Rust.
//!
//! Scala has three: `getParentNonNull` (falls back to *self*), `parent` (`toAbsolutePath.getParent`)
//! and `getParentPath` (relative parent, else absolutised). They differ **only for a relative
//! `java.nio.file.Path`**.
//!
//! A `UPath` is resolved against its context when constructed, so it is always absolute and the
//! three necessarily agree. All three names exist anyway, so a line ported from Scala reads the
//! same -- but the agreement is structural, and this file pins that rather than leaving it implied.
//!
//! Rust's method named `parent` used to implement `getParentNonNull`, which made the API audit
//! match it to Scala's `parent` while the semantics differed, with no test either side.

#![allow(
    clippy::expect_used,
    clippy::panic,
    reason = "a failed setup should abort loudly"
)]

use std::sync::Arc;

use uni::upath::PathContext;
use uni::upath::UPath;
use uni::upath::UserInfo;

fn at(p: &str) -> UPath {
    let ctx = Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", "/home/tester", "/home/tester"),
        cfg!(windows),
    ));
    UPath::resolve(&ctx, p).unwrap_or_else(|e| panic!("resolve {p}: {e}"))
}

fn posix(p: &UPath) -> String {
    p.posix().expect("posix")
}

#[test]
fn a_upath_is_always_absolute() {
    // The premise everything below rests on: a bare name resolves against the context.
    let p = at("loneName.txt");
    assert_eq!(
        posix(&p),
        "/home/tester/loneName.txt",
        "resolved, not relative"
    );
}

#[test]
fn the_three_spellings_agree_because_upath_is_absolute() {
    for input in ["/home/tester/dir/file.txt", "loneName.txt", "dir/file.txt"] {
        let p = at(input);
        let non_null = posix(&p.getParentNonNull());
        assert_eq!(posix(&p.parent()), non_null, "parent, for {input}");
        assert_eq!(
            posix(&p.getParentPath()),
            non_null,
            "getParentPath, for {input}"
        );
    }
}

#[test]
fn the_parent_of_a_resolved_name_is_its_directory() {
    assert_eq!(posix(&at("loneName.txt").parent()), "/home/tester");
    assert_eq!(
        posix(&at("/home/tester/dir/file.txt").parent()),
        "/home/tester/dir"
    );
}

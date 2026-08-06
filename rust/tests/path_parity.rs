//! Checks `upath` against the committed reference in `test-data/path-parity/`,
//! produced by `uni.Paths`.
//!
//! The Scala side (`uni.PathParitySuite`) checks itself against the same files, so
//! the pair pins both implementations to one set of expectations without either
//! test needing the other language installed.
//!
//! Every case is driven by an explicit synthetic mount table and a fake user, so
//! nothing depends on this machine's drives. And because `PathContext` takes
//! `is_windows` as **data**, this test checks the Windows rules on Linux and macOS
//! too — which the Scala suite cannot do, since its `isWin` comes from `os.name`
//! and its path suites skip off Windows.
//!
//! Regenerate with `sbt "runMain uni.apps.PathParityGen"` — and only when the
//! expectations are meant to move.

#![allow(
    clippy::expect_used,
    clippy::panic,
    reason = "a missing or malformed fixture should abort the test loudly, not be handled"
)]

use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;

use t3prf::upath::PathContext;
use t3prf::upath::UserInfo;
use t3prf::upath::resolve::classify;
use t3prf::upath::resolve::posix_abs;
use t3prf::upath::resolve::resolve_pathstr;

/// Sentinels the generator writes for values that are not plain strings.
const ERROR: &str = "!error";
const EMPTY: &str = "!empty";

fn fixture_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../test-data/path-parity")
}

#[derive(Debug, Default)]
struct Reference {
    platform: String,
    user: Option<UserInfo>,
    /// Mount lines per table id, in fstab order — the order decides one-to-many.
    tables: BTreeMap<String, Vec<String>>,
    /// (table, field) -> value, for `cygdrive` and `msysroot`.
    derived: BTreeMap<(String, String), String>,
    /// (table, field, input) -> expected.
    cases: Vec<((String, String, String), String)>,
}

/// Splits a ` | `-delimited record, trimming each field.
fn fields(line: &str) -> Vec<&str> {
    line.split('|').map(str::trim).collect()
}

/// `!empty` decodes back to the empty string; everything else is literal.
fn decode(s: &str) -> String {
    if s == EMPTY {
        String::new()
    } else {
        s.to_owned()
    }
}

fn parse(text: &str) -> Reference {
    let mut r = Reference::default();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        match fields(line).as_slice() {
            ["platform", p] => r.platform = (*p).to_owned(),
            ["user", name, home, dir] => r.user = Some(UserInfo::new(name, home, dir)),
            // Mount lines contain spaces and ' type ', but never '|', so the tail
            // after the id is the line verbatim.
            ["table", id, rest @ ..] => r
                .tables
                .entry((*id).to_owned())
                .or_default()
                .push(rest.join(" | ")),
            ["derived", id, field, value] => {
                r.derived
                    .insert(((*id).to_owned(), (*field).to_owned()), decode(value));
            }
            ["case", id, field, input, expected] => r.cases.push((
                ((*id).to_owned(), (*field).to_owned(), decode(input)),
                decode(expected),
            )),
            other => panic!("malformed fixture line ({} fields): {line}", other.len()),
        }
    }
    r
}

/// Runs one case, rendering the outcome the way the generator rendered it.
fn evaluate(ctx: &PathContext, field: &str, input: &str) -> String {
    let render = |r: Result<String, _>| match r {
        Ok(s) if s.is_empty() => EMPTY.to_owned(),
        Ok(s) => s,
        Err(_) => ERROR.to_owned(),
    };
    match field {
        "classify" => format!("{:?}", classify(input)),
        "win" => render(resolve_pathstr(ctx, input, &[])),
        "posixabs" => render(posix_abs(ctx, input)),
        "drivecwd" => {
            let drive = input.chars().next().unwrap_or('?');
            render(ctx.drive_cwd(drive))
        }
        other => panic!("fixture names field {other}, which this test cannot evaluate"),
    }
}

/// Scala's `WinPathKind.UNC` vs Rust's `PathKind::Unc` — same variant, different
/// casing convention in each language.
fn kinds_match(got: &str, want: &str) -> bool {
    got.eq_ignore_ascii_case(want)
}

fn check_platform(platform: &str) -> Vec<String> {
    let path = fixture_dir().join(format!("scala-reference-{platform}.txt"));
    let Ok(text) = fs::read_to_string(&path) else {
        return Vec::new(); // that platform's block has not been generated
    };
    let reference = parse(&text);
    let user = reference.user.clone().expect("fixture has a user record");
    let is_windows = reference.platform == "windows";
    assert_eq!(
        reference.platform, platform,
        "fixture {path:?} is tagged {} but named {platform}",
        reference.platform
    );

    let contexts: BTreeMap<String, PathContext> = reference
        .tables
        .iter()
        .map(|(id, lines)| {
            (
                id.clone(),
                PathContext::synthetic(lines, user.clone(), is_windows),
            )
        })
        .collect();

    let mut failures = Vec::new();

    // Derived facts first: a wrong cygdrive or msysroot explains every case under
    // that table, so reporting it separately keeps the diagnosis short.
    for ((id, field), want) in &reference.derived {
        let ctx = &contexts[id];
        let got = match field.as_str() {
            "cygdrive" => ctx.mounts.cygdrive.clone(),
            "msysroot" => ctx.msys_root().to_owned(),
            other => panic!("unknown derived field {other}"),
        };
        if &got != want {
            failures.push(format!(
                "[{platform}] {id} derived {field}: got {got:?}, want {want:?}"
            ));
        }
    }

    for ((id, field, input), want) in &reference.cases {
        let ctx = &contexts[id];
        let got = evaluate(ctx, field, input);
        let ok = if field == "classify" {
            kinds_match(&got, want)
        } else {
            &got == want
        };
        if !ok {
            failures.push(format!(
                "[{platform}] {id} {field} {input:?}: got {got:?}, want {want:?}"
            ));
        }
    }
    failures
}

#[test]
fn matches_scala_reference() {
    let mut failures = Vec::new();
    let mut checked = 0_usize;
    for platform in ["windows", "posix"] {
        let f = check_platform(platform);
        if fixture_dir()
            .join(format!("scala-reference-{platform}.txt"))
            .exists()
        {
            checked += 1;
        }
        failures.extend(f);
    }
    assert!(
        checked > 0,
        "no fixture found in {:?} — regenerate with: sbt \"runMain uni.apps.PathParityGen\"",
        fixture_dir()
    );
    failures.sort();
    assert!(
        failures.is_empty(),
        "{} case(s) diverged from the reference:\n  {}",
        failures.len(),
        failures.join("\n  ")
    );
}

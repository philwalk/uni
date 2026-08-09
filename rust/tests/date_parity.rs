//! Checks `utime` against the committed reference in `test-data/date-parity/`, produced by
//! `uni`. The Scala side (`uni.time.DateParitySuite`) checks itself against the same files.
//!
//! # Why this fixture carries more weight than the CSV or hash ones
//!
//! For the hashes, Scala is a second opinion — RFC 1321 and FIPS 180-4 are the real authority.
//! Here Scala *is* the authority, and transitively `java.time`: the Scala arithmetic delegates
//! to it, while this port cannot and so writes out Hinnant's civil-calendar algorithms,
//! month-end clamping and time-shift carry by hand. Those are exactly where hand-rolled
//! calendar code goes wrong, and this file is what makes `java.time` the oracle for them.
//!
//! Regenerate with `sbt "runMain uni.apps.DateParityGen"`.

#![allow(
    clippy::expect_used,
    clippy::panic,
    clippy::unwrap_used,
    reason = "a missing or malformed fixture should abort the test loudly, not be handled"
)]

use std::path::PathBuf;

use uni::upath::PathContext;
use uni::upath::UPath;
use uni::upath::UserInfo;
use uni::upath::epoch2DateTime;
use uni::upath::times::round6;
use uni::utime::UniDateTime;
use uni::utime::dayOfWeek;

fn fixture() -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../test-data/date-parity/scala-reference.txt");
    std::fs::read_to_string(&path).unwrap_or_else(|e| {
        panic!(
            "cannot read {path:?}: {e}. Regenerate with: sbt \"runMain uni.apps.DateParityGen\""
        )
    })
}

/// Rows of the reference, already split on tabs, excluding comments.
fn rows() -> Vec<Vec<String>> {
    fixture()
        .lines()
        .filter(|l| !l.starts_with('#') && !l.trim().is_empty())
        .map(|l| l.split('\t').map(str::to_owned).collect())
        .collect()
}

fn unesc(s: &str) -> String {
    // Backslash last, mirroring the generator escaping it first.
    s.replace("\\t", "\t")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\\\", "\\")
}

fn i(s: &str) -> i32 {
    s.parse().unwrap_or_else(|_| panic!("not an i32: [{s}]"))
}
fn l(s: &str) -> i64 {
    s.parse().unwrap_or_else(|_| panic!("not an i64: [{s}]"))
}

/// Rebuilds the moment a row names. The sentinels are recognised by their field values --
/// day 0 is what makes that unambiguous, since `of` can never produce it.
fn moment(f: &[String]) -> UniDateTime {
    let (y, mo, d) = (i(&f[1]), i(&f[2]), i(&f[3]));
    let (h, mi, s, nano) = (i(&f[4]), i(&f[5]), i(&f[6]), i(&f[7]));
    if d == 0 && y == 1900 {
        UniDateTime::BAD_DATE
    } else if d == 0 && y == 1800 {
        UniDateTime::EMPTY_DATE
    } else {
        UniDateTime::ofFull(y, mo, d, h, mi, s, nano, None)
    }
}

fn of_kind(kind: &str) -> Vec<Vec<String>> {
    rows().into_iter().filter(|r| r[0] == kind).collect()
}

fn shift(d: &UniDateTime, op: &str, n: i64) -> UniDateTime {
    match op {
        "plusNanos" => d.plusNanos(n),
        "plusSeconds" => d.plusSeconds(n),
        "plusMinutes" => d.plusMinutes(n),
        "plusHours" => d.plusHours(n),
        "plusDays" => d.plusDays(n),
        "plusWeeks" => d.plusWeeks(n),
        "plusMonths" => d.plusMonths(n),
        "plusYears" => d.plusYears(n),
        "minusNanos" => d.minusNanos(n),
        "minusSeconds" => d.minusSeconds(n),
        "minusMinutes" => d.minusMinutes(n),
        "minusHours" => d.minusHours(n),
        "minusDays" => d.minusDays(n),
        "minusWeeks" => d.minusWeeks(n),
        "minusMonths" => d.minusMonths(n),
        "minusYears" => d.minusYears(n),
        other => panic!("unknown shift op [{other}]"),
    }
}

fn with_field(d: &UniDateTime, op: &str, a: i32) -> UniDateTime {
    match op {
        "withYear" => d.withYear(a),
        "withMonth" => d.withMonth(a),
        "withDayOfMonth" => d.withDayOfMonth(a),
        "withHour" => d.withHour(a),
        "withMinute" => d.withMinute(a),
        "withSecond" => d.withSecond(a),
        "withNano" => d.withNano(a),
        other => panic!("unknown with op [{other}]"),
    }
}

#[test]
fn fixture_has_every_kind_of_case() {
    let all = rows();
    assert!(all.len() > 3000, "only {} rows", all.len());
    for kind in [
        "tostr", "fmt", "dow", "epochday", "valid", "dim", "ofepoch", "shift", "unary", "with",
        "withdow", "sentinel", "offset", "offsettext",
    ] {
        assert!(
            all.iter().any(|r| r[0] == kind),
            "no [{kind}] rows in the fixture"
        );
    }
}

#[test]
fn to_string_matches() {
    for r in of_kind("tostr") {
        assert_eq!(
            moment(&r).to_string(),
            unesc(&r[8]),
            "toString for {:?}",
            &r[1..8]
        );
    }
}

#[test]
fn formatting_matches_for_every_pattern() {
    for r in of_kind("fmt") {
        let pattern = unesc(&r[8]);
        let got = moment(&r)
            .format(&pattern)
            .unwrap_or_else(|e| panic!("[{pattern}] failed: {e}"));
        assert_eq!(got, unesc(&r[9]), "[{pattern}] on {:?}", &r[1..8]);
    }
}

#[test]
fn day_of_week_epoch_day_and_validity_match() {
    for r in of_kind("dow") {
        let d = moment(&r);
        assert_eq!(
            dayOfWeek(d.year(), d.month(), d.day()).to_string(),
            r[8],
            "dow {:?}",
            &r[1..8]
        );
        assert_eq!(d.dayOfWeekName(), r[9], "dayOfWeekName {:?}", &r[1..8]);
        assert_eq!(d.dayOfWeekFull(), r[10], "dayOfWeekFull {:?}", &r[1..8]);
    }
    for r in of_kind("epochday") {
        assert_eq!(
            moment(&r).toEpochDay().to_string(),
            r[8],
            "epochDay {:?}",
            &r[1..8]
        );
    }
    for r in of_kind("valid") {
        let got = UniDateTime::isValidFields(
            i(&r[1]),
            i(&r[2]),
            i(&r[3]),
            i(&r[4]),
            i(&r[5]),
            i(&r[6]),
            i(&r[7]),
        );
        assert_eq!(got.to_string(), r[8], "isValidFields {:?}", &r[1..8]);
    }
}

#[test]
fn days_in_month_matches_including_out_of_range() {
    for r in of_kind("dim") {
        assert_eq!(
            UniDateTime::daysInMonth(i(&r[1]), i(&r[2])).to_string(),
            r[3],
            "daysInMonth({}, {})",
            r[1],
            r[2]
        );
    }
}

#[test]
fn of_epoch_day_matches_across_era_boundaries() {
    for r in of_kind("ofepoch") {
        assert_eq!(
            UniDateTime::ofEpochDay(l(&r[1])).to_string(),
            unesc(&r[2]),
            "ofEpochDay({})",
            r[1]
        );
    }
}

#[test]
fn shift_arithmetic_matches() {
    for r in of_kind("shift") {
        let got = shift(&moment(&r), &r[8], l(&r[9]));
        assert_eq!(
            got.to_string(),
            unesc(&r[10]),
            "{}({}) on {:?}",
            r[8],
            r[9],
            &r[1..8]
        );
    }
}

#[test]
fn at_start_of_day_and_last_day_of_month_match() {
    for r in of_kind("unary") {
        let d = moment(&r);
        let got = match r[8].as_str() {
            "atStartOfDay" => d.atStartOfDay(),
            "lastDayOfMonth" => d.lastDayOfMonth(),
            other => panic!("unknown unary op [{other}]"),
        };
        assert_eq!(got.to_string(), unesc(&r[9]), "{} on {:?}", r[8], &r[1..8]);
    }
}

#[test]
fn with_field_matches_including_out_of_range() {
    for r in of_kind("with") {
        let got = with_field(&moment(&r), &r[8], i(&r[9]));
        assert_eq!(
            got.to_string(),
            unesc(&r[10]),
            "{}({}) on {:?}",
            r[8],
            r[9],
            &r[1..8]
        );
    }
}

#[test]
fn with_day_of_week_matches() {
    for r in of_kind("withdow") {
        let got = moment(&r).withDayOfWeek(i(&r[8]));
        assert_eq!(
            got.to_string(),
            unesc(&r[9]),
            "withDayOfWeek({}) on {:?}",
            r[8],
            &r[1..8]
        );
    }
}

#[test]
fn sentinels_absorb_every_shift() {
    for r in of_kind("sentinel") {
        let got = shift(&moment(&r), &r[8], 1);
        assert_eq!(got.to_string(), unesc(&r[9]), "{} on {:?}", r[8], &r[1..8]);
    }
}

fn f(s: &str) -> f64 {
    s.parse().unwrap_or_else(|_| panic!("not an f64: [{s}]"))
}

#[test]
fn round6_matches() {
    // Parsed, not string-compared: Scala renders 5e-7 as "5.0E-7" and Rust as "5e-7". The
    // fixture pins the value, not either language's float formatter.
    for r in of_kind("round6") {
        let got = round6(f(&r[1]));
        let want = f(&r[2]);
        assert!(
            (got - want).abs() < 1e-15,
            "round6({}) was {got}, expected {want}",
            r[1]
        );
    }
}

#[test]
fn epoch_conversion_matches_at_fixed_offsets() {
    for r in of_kind("epochconv") {
        let millis = l(&r[1]);
        let off = i(&r[2]);
        let d = epoch2DateTime(millis, off);
        assert_eq!(
            d.to_string(),
            unesc(&r[3]),
            "epoch2DateTime({millis}, {off})"
        );
        // `none` in the fixture: the zone shifts the fields and is then discarded. Recording
        // the offset here would make the same timestamp compare unequal across the two
        // languages.
        let expected = if r[4] == "none" { None } else { Some(i(&r[4])) };
        assert_eq!(
            d.offsetMinutes(),
            expected,
            "offsetMinutes for {millis} at {off}"
        );
    }
}

#[test]
fn file_reading_path_matches_at_a_set_mtime() {
    // The generator *sets* each mtime, so this is deterministic. Whole seconds only: mtime
    // granularity varies by filesystem, and a sub-second value would round differently per
    // host.
    //
    // The only test here that touches the filesystem, and what distinguishes a fixture over the
    // conversion arithmetic from one over the whole `lastModified` path. No offset: file
    // timestamps are UTC in both languages.
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../test-data/date-parity/inputs");
    let ctx = std::sync::Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", "/tmp", "/tmp"),
        cfg!(windows),
    ));
    for r in of_kind("mtime") {
        let file = dir.join(&r[1]);
        assert!(file.is_file(), "missing fixture input {file:?}");
        let p = UPath::resolve(&ctx, &file.to_string_lossy().replace('\\', "/"))
            .unwrap_or_else(|e| panic!("cannot resolve {file:?}: {e}"));
        assert_eq!(p.lastModified().to_string(), r[2], "lastModified of {}", r[1]);
        assert_eq!(
            p.lastModifiedTime().to_string(),
            unesc(&r[3]),
            "lastModifiedTime of {}",
            r[1]
        );
        assert_eq!(
            p.lastModifiedYMD(),
            unesc(&r[4]),
            "lastModifiedYMD of {}",
            r[1]
        );
        assert_eq!(p.weekDay().to_string(), r[5], "weekDay of {}", r[1]);
        assert_eq!(p.weekDayName(), r[6], "weekDayName of {}", r[1]);
    }
}

#[test]
fn offset_extraction_matches_including_anchoring() {
    for r in of_kind("offset") {
        let text = unesc(&r[1]);
        let expected = if r[2] == "none" {
            None
        } else {
            Some(i(&r[2]))
        };
        assert_eq!(
            UniDateTime::offsetMinutesOf(&text),
            expected,
            "offsetMinutesOf [{text}]"
        );
    }
}

#[test]
fn offset_validity_and_rendering_match() {
    for r in of_kind("offsettext") {
        let o = i(&r[1]);
        assert_eq!(
            UniDateTime::isValidOffset(o).to_string(),
            r[2],
            "isValidOffset({o})"
        );
        let d = UniDateTime::ofFull(2024, 5, 12, 14, 30, 0, 0, Some(o));
        assert_eq!(d.offsetText(), unesc(&r[3]), "offsetText for {o}");
        assert_eq!(d.to_string(), unesc(&r[4]), "to_string for offset {o}");
    }
}

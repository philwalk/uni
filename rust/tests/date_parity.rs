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

use t3prf::utime::UniDateTime;
use t3prf::utime::day_of_week;

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
        UniDateTime::of_full(y, mo, d, h, mi, s, nano, None)
    }
}

fn of_kind(kind: &str) -> Vec<Vec<String>> {
    rows().into_iter().filter(|r| r[0] == kind).collect()
}

fn shift(d: &UniDateTime, op: &str, n: i64) -> UniDateTime {
    match op {
        "plusNanos" => d.plus_nanos(n),
        "plusSeconds" => d.plus_seconds(n),
        "plusMinutes" => d.plus_minutes(n),
        "plusHours" => d.plus_hours(n),
        "plusDays" => d.plus_days(n),
        "plusWeeks" => d.plus_weeks(n),
        "plusMonths" => d.plus_months(n),
        "plusYears" => d.plus_years(n),
        "minusNanos" => d.minus_nanos(n),
        "minusSeconds" => d.minus_seconds(n),
        "minusMinutes" => d.minus_minutes(n),
        "minusHours" => d.minus_hours(n),
        "minusDays" => d.minus_days(n),
        "minusWeeks" => d.minus_weeks(n),
        "minusMonths" => d.minus_months(n),
        "minusYears" => d.minus_years(n),
        other => panic!("unknown shift op [{other}]"),
    }
}

fn with_field(d: &UniDateTime, op: &str, a: i32) -> UniDateTime {
    match op {
        "withYear" => d.with_year(a),
        "withMonth" => d.with_month(a),
        "withDayOfMonth" => d.with_day_of_month(a),
        "withHour" => d.with_hour(a),
        "withMinute" => d.with_minute(a),
        "withSecond" => d.with_second(a),
        "withNano" => d.with_nano(a),
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
            day_of_week(d.year(), d.month(), d.day()).to_string(),
            r[8],
            "dow {:?}",
            &r[1..8]
        );
    }
    for r in of_kind("epochday") {
        assert_eq!(
            moment(&r).to_epoch_day().to_string(),
            r[8],
            "epochDay {:?}",
            &r[1..8]
        );
    }
    for r in of_kind("valid") {
        let got = UniDateTime::fields_valid(
            i(&r[1]),
            i(&r[2]),
            i(&r[3]),
            i(&r[4]),
            i(&r[5]),
            i(&r[6]),
            i(&r[7]),
        );
        assert_eq!(got.to_string(), r[8], "fields_valid {:?}", &r[1..8]);
    }
}

#[test]
fn days_in_month_matches_including_out_of_range() {
    for r in of_kind("dim") {
        assert_eq!(
            UniDateTime::days_in_month(i(&r[1]), i(&r[2])).to_string(),
            r[3],
            "days_in_month({}, {})",
            r[1],
            r[2]
        );
    }
}

#[test]
fn of_epoch_day_matches_across_era_boundaries() {
    for r in of_kind("ofepoch") {
        assert_eq!(
            UniDateTime::of_epoch_day(l(&r[1])).to_string(),
            unesc(&r[2]),
            "of_epoch_day({})",
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
            "atStartOfDay" => d.at_start_of_day(),
            "lastDayOfMonth" => d.last_day_of_month(),
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
        let got = moment(&r).with_day_of_week(i(&r[8]));
        assert_eq!(
            got.to_string(),
            unesc(&r[9]),
            "with_day_of_week({}) on {:?}",
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
            UniDateTime::offset_minutes_of(&text),
            expected,
            "offset_minutes_of [{text}]"
        );
    }
}

#[test]
fn offset_validity_and_rendering_match() {
    for r in of_kind("offsettext") {
        let o = i(&r[1]);
        assert_eq!(
            UniDateTime::is_valid_offset(o).to_string(),
            r[2],
            "is_valid_offset({o})"
        );
        let d = UniDateTime::of_full(2024, 5, 12, 14, 30, 0, 0, Some(o));
        assert_eq!(d.offset_text(), unesc(&r[3]), "offset_text for {o}");
        assert_eq!(d.to_string(), unesc(&r[4]), "to_string for offset {o}");
    }
}

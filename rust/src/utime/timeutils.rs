//! Clock, between/duration and file-age functions — a port of the Scala
//! `uni.time.TimeUtils` surface (exported there as `uni.time.*`).
//!
//! # Zones
//!
//! The difference family is zone-free **on both sides, by design**: a difference
//! between two local datetimes is field arithmetic no timezone can legitimately
//! affect, and the Scala `ZoneId` parameters these functions once carried were removed
//! in 0.16.0 (they imported DST artifacts into results the arguments fully determine).
//! So these are exact ports, not approximations. Zone-sensitive arithmetic belongs on
//! instants — Scala keeps an `Instant` overload of `secondsBetween` for that; `nowZoned`
//! has no port, since resolving a zone needs a tz database `std` does not have.
//!
//! # The clock
//!
//! [`now`] is local wall-clock fields, like Scala's `LocalDateTime.now()`: the system
//! clock provides an instant (UTC by nature), and [`localOffsetMinutes`] — one platform
//! call, no tz database — supplies the current offset that turns it into wall time.
//! On a platform that cannot report its offset, [`now`] falls back to UTC. [`nowUTC`]
//! is always the UTC fields.
//!
//! # Parity
//!
//! The pure parts (`secondsBetween` … `getDuration`, `endOfMonth`, `quikDate`,
//! `quikDateTime`, `monthAbbrev2Number`) are pinned by `between`/`duration`/`eom`/
//! `quikdate`/`quikdt`/`mon2num` rows in `test-data/date-parity/`; the clock and
//! file-age functions read the environment and get behavioral tests instead.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name, so a script kept in both \
              languages needs no mental translation. Internal helpers stay snake_case, \
              so the case says whether a Scala counterpart exists."
)]

use std::path::Path;
use std::time::SystemTime;
use std::time::UNIX_EPOCH;

use crate::utime::UniDateTime;

const NANOS_PER_SEC: i128 = 1_000_000_000;
const NANOS_PER_DAY: i128 = 86_400 * NANOS_PER_SEC;

/// Naive epoch nanoseconds of the local fields (any stated offset is ignored, matching
/// Scala's `toLocalDateTime`-based arithmetic).
fn epoch_nanos(d: &UniDateTime) -> i128 {
    let secs = i128::from(d.toEpochDay()) * 86_400
        + i128::from(d.hour()) * 3600
        + i128::from(d.minute()) * 60
        + i128::from(d.second());
    secs * NANOS_PER_SEC + i128::from(d.nano())
}

/// A `UniDateTime` from signed epoch milliseconds, at UTC.
///
/// The same conversion as `upath::times::epoch2DateTime(millis, 0)`, restated here so
/// `utime` stays independent of `upath` (the dependency already runs the other way).
fn from_epoch_millis_utc(millis: i64) -> UniDateTime {
    let days = millis.div_euclid(86_400_000);
    let within = millis.rem_euclid(86_400_000);
    UniDateTime::ofEpochDay(days).plusNanos(within * 1_000_000)
}

// ── The local offset, from the platform ─────────────────────────────────────
//
// `now` needs exactly one number a tz database is NOT needed for: the UTC offset in
// effect right now, which every OS knows because it converts the (UTC) hardware clock
// to wall time itself. One platform call each — no new dependency, declared directly.

#[cfg(windows)]
mod local_offset {
    #[repr(C)]
    struct SystemTime {
        year: u16,
        month: u16,
        day_of_week: u16,
        day: u16,
        hour: u16,
        minute: u16,
        second: u16,
        milliseconds: u16,
    }

    /// `TIME_ZONE_INFORMATION`; biases are in minutes, `UTC = local + bias`.
    #[repr(C)]
    struct TimeZoneInformation {
        bias: i32,
        standard_name: [u16; 32],
        standard_date: SystemTime,
        standard_bias: i32,
        daylight_name: [u16; 32],
        daylight_date: SystemTime,
        daylight_bias: i32,
    }

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetTimeZoneInformation(tzi: *mut TimeZoneInformation) -> u32;
    }

    pub(super) fn local_offset_minutes() -> Option<i32> {
        // SAFETY: all-integer struct, zeroed is a valid value; the call writes into it.
        let mut tzi = unsafe { core::mem::zeroed::<TimeZoneInformation>() };
        // SAFETY: tzi outlives the call and is exclusively borrowed.
        let code = unsafe { GetTimeZoneInformation(&raw mut tzi) };
        let active = match code {
            0 => tzi.bias,                     // TIME_ZONE_ID_UNKNOWN: no DST rule
            1 => tzi.bias + tzi.standard_bias, // TIME_ZONE_ID_STANDARD
            2 => tzi.bias + tzi.daylight_bias, // TIME_ZONE_ID_DAYLIGHT
            _ => return None,                  // TIME_ZONE_ID_INVALID
        };
        Some(-active)
    }
}

#[cfg(all(unix, target_pointer_width = "64"))]
mod local_offset {
    /// `struct tm` as glibc, musl, macOS and the BSDs lay it out on LP64: nine ints,
    /// then the seconds-east-of-UTC extension field this exists to read.
    #[repr(C)]
    struct Tm {
        tm_sec: i32,
        tm_min: i32,
        tm_hour: i32,
        tm_mday: i32,
        tm_mon: i32,
        tm_year: i32,
        tm_wday: i32,
        tm_yday: i32,
        tm_isdst: i32,
        tm_gmtoff: i64,
        tm_zone: *const core::ffi::c_char,
    }

    unsafe extern "C" {
        fn localtime_r(timep: *const i64, result: *mut Tm) -> *mut Tm;
    }

    pub(super) fn local_offset_minutes() -> Option<i32> {
        let now: i64 = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .ok()?
            .as_secs()
            .try_into()
            .ok()?;
        // SAFETY: zeroed is a valid Tm (the pointer field is never read before the
        // call fills it); localtime_r writes through the exclusive borrow.
        let mut tm = unsafe { core::mem::zeroed::<Tm>() };
        // SAFETY: both pointers are valid for the duration of the call.
        let res = unsafe { localtime_r(&raw const now, &raw mut tm) };
        if res.is_null() {
            None
        } else {
            i32::try_from(tm.tm_gmtoff / 60).ok()
        }
    }
}

#[cfg(not(any(windows, all(unix, target_pointer_width = "64"))))]
mod local_offset {
    pub(super) fn local_offset_minutes() -> Option<i32> {
        None
    }
}

/// The machine's current UTC offset in minutes — `Some(-420)` for Mountain Daylight
/// Time — or `None` where the platform cannot say. From `GetTimeZoneInformation` on
/// Windows and `localtime_r`'s `tm_gmtoff` on 64-bit unix; no tz database involved,
/// because the *current* offset is one number the OS already knows.
#[must_use]
pub fn localOffsetMinutes() -> Option<i32> {
    local_offset::local_offset_minutes()
}

// ── The clock ───────────────────────────────────────────────────────────────

/// The current UTC fields, straight from the system clock (which is UTC-based).
/// A system clock set before 1970 answers `BAD_DATE`.
fn now_utc() -> UniDateTime {
    match SystemTime::now().duration_since(UNIX_EPOCH) {
        Ok(d) => {
            let secs = i64::try_from(d.as_secs()).unwrap_or(i64::MAX);
            let days = secs.div_euclid(86_400);
            UniDateTime::ofEpochDay(days)
                .plusSeconds(secs.rem_euclid(86_400))
                .plusNanos(i64::from(d.subsec_nanos()))
        }
        Err(_) => UniDateTime::BAD_DATE,
    }
}

/// Now, as local wall-clock fields — what Scala's `now` (`LocalDateTime.now()`) reads.
/// The offset comes from [`localOffsetMinutes`] and, exactly as in `java.time`, is
/// consumed in the conversion and not recorded on the result. Falls back to UTC on a
/// platform that cannot report its offset.
#[must_use]
pub fn now() -> UniDateTime {
    let utc = now_utc();
    match localOffsetMinutes() {
        Some(off) => utc.plusMinutes(i64::from(off)),
        None => utc,
    }
}

/// `now` minus one day.
#[must_use]
pub fn yesterday() -> UniDateTime {
    now().minusDays(1)
}

/// The current moment in UTC fields. Scala's `nowUTC` returns an `Instant`, which is
/// always UTC; this is the same moment as [`now`] read at offset zero.
#[must_use]
pub fn nowUTC() -> UniDateTime {
    now_utc()
}

// ── Between and duration ────────────────────────────────────────────────────

/// Whole seconds from `d1` to `d2` (signed, truncated toward zero — the
/// `ChronoUnit.SECONDS.between` contract).
#[must_use]
pub fn secondsBetween(d1: &UniDateTime, d2: &UniDateTime) -> i64 {
    i64::try_from((epoch_nanos(d2) - epoch_nanos(d1)) / NANOS_PER_SEC).unwrap_or(i64::MAX)
}

/// Seconds from `d1` to [`now`] — the age of a past moment, positive.
#[must_use]
pub fn secondsSince(d1: &UniDateTime) -> i64 {
    secondsBetween(d1, &now())
}

/// [`secondsBetween`] in minutes (the truncation happens at the seconds level, as in
/// Scala: `secondsBetween(...).toDouble / 60.0`).
#[must_use]
pub fn minutesBetween(d1: &UniDateTime, d2: &UniDateTime) -> f64 {
    secondsBetween(d1, d2) as f64 / 60.0
}

/// [`secondsBetween`] in hours.
#[must_use]
pub fn hoursBetween(d1: &UniDateTime, d2: &UniDateTime) -> f64 {
    secondsBetween(d1, d2) as f64 / 3600.0
}

/// [`secondsBetween`] in days, fractional.
#[must_use]
pub fn daysRounded(d1: &UniDateTime, d2: &UniDateTime) -> f64 {
    secondsBetween(d1, d2) as f64 / (24.0 * 3600.0)
}

/// Complete 24-hour periods from `d1` to `d2` (signed, truncated toward zero — the
/// `ChronoUnit.DAYS.between(LocalDateTime, LocalDateTime)` contract, so
/// `23:00 → 22:59 next day` is `0`).
#[must_use]
pub fn daysBetween(d1: &UniDateTime, d2: &UniDateTime) -> i64 {
    i64::try_from((epoch_nanos(d2) - epoch_nanos(d1)) / NANOS_PER_DAY).unwrap_or(i64::MAX)
}

/// Signed days between the dates. Since the family went zone-free (both sides,
/// 0.16.0) this coincides with [`daysBetween`]; both names survive because both
/// exist in Scala and ported scripts use either.
#[must_use]
pub fn elapsedDays(d1: &UniDateTime, d2: &UniDateTime) -> i64 {
    daysBetween(d1, d2)
}

/// `(days, hours, minutes, seconds)` between the timestamps, sign carried on `days`
/// alone. Total seconds **floor** rather than truncate (the `java.time.Duration`
/// normalization Scala inherits), so a −0.5 s difference reports one second, not zero.
#[must_use]
pub fn getDuration(d1: &UniDateTime, d2: &UniDateTime) -> (i64, i64, i64, i64) {
    let total = i64::try_from((epoch_nanos(d2) - epoch_nanos(d1)).div_euclid(NANOS_PER_SEC))
        .unwrap_or(i64::MAX);
    let abs = total.abs();
    let days = abs / 86_400;
    let hours = (abs % 86_400) / 3600;
    let minutes = (abs % 3600) / 60;
    let seconds = abs % 60;
    if total >= 0 {
        (days, hours, minutes, seconds)
    } else {
        (-days, hours, minutes, seconds)
    }
}

/// Midnight on the last day of `d`'s month.
#[must_use]
pub fn endOfMonth(d: &UniDateTime) -> UniDateTime {
    d.lastDayOfMonth().atStartOfDay()
}

/// Epoch millis of the fields read at UTC — the inverse of
/// `upath::times::epoch2DateTime(_, 0)`, so the round trip is exact modulo
/// sub-millisecond nanos (floored, as `Instant.toEpochMilli` floors). Scala's
/// `getMillis()` extension reads at UTC since 0.16.0 for the same round trip.
#[must_use]
pub fn getMillis(d: &UniDateTime) -> i64 {
    i64::try_from(epoch_nanos(d).div_euclid(1_000_000)).unwrap_or(i64::MAX)
}

// ── Strict parsers and month names ──────────────────────────────────────────

/// Month number from a name or abbreviation, or `-1` when it is not one.
#[must_use]
pub fn monthAbbrev2Number(name: &str) -> i32 {
    let lc: String = name.to_lowercase().chars().take(3).collect();
    match lc.as_str() {
        "jan" => 1,
        "feb" => 2,
        "mar" => 3,
        "apr" => 4,
        "may" => 5,
        "jun" => 6,
        "jul" => 7,
        "aug" => 8,
        "sep" => 9,
        "oct" => 10,
        "nov" => 11,
        "dec" => 12,
        _ => -1,
    }
}

/// Fast path for 8-character `yyyyMMdd` (or anything [`quikDateTime`] takes).
///
/// The Scala original is the *strict* counterpart to `parseDate` and throws on
/// malformed input; this answers `BAD_DATE` instead (the crate never panics where
/// Scala throws).
#[must_use]
pub fn quikDate(s: &str) -> UniDateTime {
    let b = s.as_bytes();
    if b.len() < 8 {
        return UniDateTime::BAD_DATE;
    }
    if b[..8].iter().all(u8::is_ascii_digit) {
        let f8 = &s[..8]; // all ASCII digits, so the byte boundary is a char boundary
        let ymd = format!("{}-{}-{}", &f8[..4], &f8[4..6], &f8[6..8]);
        quikDateTime(&ymd)
    } else {
        // char-boundary-safe take(10); a multibyte boundary means malformed anyway
        quikDateTime(s.get(..10).unwrap_or(s))
    }
}

/// Strict `yyyy?MM?dd[ HH[:mm[:ss]]]` (any non-digit separators), or `BAD_DATE` where
/// the Scala throws.
#[must_use]
pub fn quikDateTime(s: &str) -> UniDateTime {
    // the Scala guard: [0-9]{4}\D[0-9]{2}\D[0-9]{2}.*
    let b = s.as_bytes();
    let shaped = b.len() >= 10
        && b[..4].iter().all(u8::is_ascii_digit)
        && !b[4].is_ascii_digit()
        && b[5..7].iter().all(u8::is_ascii_digit)
        && !b[7].is_ascii_digit()
        && b[8..10].iter().all(u8::is_ascii_digit);
    if !shaped {
        return UniDateTime::BAD_DATE;
    }
    let fields: Vec<i32> = s
        .split(|c: char| !c.is_ascii_digit())
        .filter(|t| !t.trim().is_empty())
        .map(str::parse)
        .collect::<Result<_, _>>()
        .unwrap_or_default();
    match fields.as_slice() {
        [y, m, d] => UniDateTime::of(*y, *m, *d, 0, 0, 0),
        [y, m, d, h] => UniDateTime::of(*y, *m, *d, *h, 0, 0),
        [y, m, d, h, mn] => UniDateTime::of(*y, *m, *d, *h, *mn, 0),
        [y, m, d, h, mn, sec] => UniDateTime::of(*y, *m, *d, *h, *mn, *sec),
        _ => UniDateTime::BAD_DATE,
    }
}

// ── File age ────────────────────────────────────────────────────────────────

/// Signed epoch milliseconds of a file's mtime, or `None` when unreadable.
fn mtime_millis(p: &Path) -> Option<i64> {
    let t = std::fs::metadata(p).and_then(|m| m.modified()).ok()?;
    Some(match t.duration_since(UNIX_EPOCH) {
        Ok(d) => i64::try_from(d.as_millis()).unwrap_or(i64::MAX),
        Err(e) => i64::try_from(e.duration().as_millis())
            .map(|m| -m)
            .unwrap_or(i64::MIN),
    })
}

/// The file's mtime as a `UniDateTime`, in UTC on both sides — Scala's `whenModified`
/// moved to UTC in 0.16.0, consistent with `Path.lastModifiedTime`. A missing file
/// reads as mtime `-1` ms, exactly as in Scala: `1969-12-31T23:59:59.999`.
#[must_use]
pub fn whenModified(p: impl AsRef<Path>) -> UniDateTime {
    from_epoch_millis_utc(mtime_millis(p.as_ref()).unwrap_or(-1))
}

/// Minutes since the file was modified; a missing file is VERY old (`1e6`), so age
/// thresholds treat it as stale rather than fresh.
#[must_use]
pub fn ageInMinutes(p: impl AsRef<Path>) -> f64 {
    match mtime_millis(p.as_ref()) {
        Some(mtime) => {
            let now_ms = match SystemTime::now().duration_since(UNIX_EPOCH) {
                Ok(d) => i64::try_from(d.as_millis()).unwrap_or(i64::MAX),
                Err(_) => 0,
            };
            (now_ms - mtime) as f64 / 60_000.0
        }
        None => 1e6,
    }
}

/// [`ageInMinutes`] in days. Takes anything path-like, covering both of Scala's
/// `File` and `String` overloads.
#[must_use]
pub fn ageInDays(p: impl AsRef<Path>) -> f64 {
    ageInMinutes(p) / (24.0 * 60.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dt(y: i32, mo: i32, d: i32, h: i32, mi: i32, s: i32) -> UniDateTime {
        UniDateTime::of(y, mo, d, h, mi, s)
    }

    #[test]
    fn seconds_truncate_toward_zero_but_duration_floors() {
        let a = dt(2024, 5, 12, 14, 30, 45);
        let b = a.plusNanos(500_000_000);
        assert_eq!(secondsBetween(&a, &b), 0);
        assert_eq!(secondsBetween(&b, &a), 0); // trunc toward zero
        assert_eq!(getDuration(&a, &b), (0, 0, 0, 0));
        assert_eq!(getDuration(&b, &a), (0, 0, 0, 1)); // floor: −0.5 s is one second
    }

    #[test]
    fn days_between_counts_complete_periods() {
        let a = dt(2024, 5, 12, 23, 0, 0);
        assert_eq!(daysBetween(&a, &dt(2024, 5, 13, 22, 59, 59)), 0);
        assert_eq!(daysBetween(&a, &dt(2024, 5, 13, 23, 0, 0)), 1);
        assert_eq!(daysBetween(&dt(2024, 5, 13, 23, 0, 0), &a), -1);
    }

    #[test]
    fn the_clock_reads_as_a_valid_recent_local_date() {
        let n = now();
        assert!(n.isValid());
        assert!(n.year() >= 2026, "year {}", n.year());
        // yesterday() first: a later now() is ≥ 24h after it, an earlier one is not
        let y = yesterday();
        assert_eq!(daysBetween(&y, &now()), 1);
        let age = secondsSince(&n);
        assert!((0..=2).contains(&age), "secondsSince(now) = {age}");
    }

    #[test]
    fn the_local_offset_shifts_now_away_from_now_utc_by_itself() {
        let Some(off) = localOffsetMinutes() else {
            return; // platform cannot say; now() falls back to UTC
        };
        assert!((-14 * 60..=14 * 60).contains(&off), "offset {off}");
        assert_eq!(
            off % 15,
            0,
            "real-world offsets are quarter-hour multiples: {off}"
        );
        let drift = secondsBetween(&nowUTC(), &now()) - i64::from(off) * 60;
        assert!((0..=2).contains(&drift), "now - nowUTC - offset = {drift}s");
    }

    #[test]
    fn get_millis_round_trips_at_utc() {
        let d = dt(2024, 5, 12, 14, 30, 45);
        assert_eq!(getMillis(&d), 1_715_524_245_000);
        let pre = dt(1969, 12, 31, 23, 59, 59).plusNanos(999_500_000);
        assert_eq!(
            getMillis(&pre),
            -1,
            "floors at the millisecond, like toEpochMilli"
        );
    }

    #[test]
    fn quik_parsers_answer_bad_date_where_scala_throws() {
        assert_eq!(quikDate("2024051"), UniDateTime::BAD_DATE); // too short
        assert_eq!(quikDateTime("not a date"), UniDateTime::BAD_DATE);
        assert_eq!(
            quikDateTime("2024-05-12 14:30:45.123"), // 7 fields: Scala sys.error
            UniDateTime::BAD_DATE
        );
        assert!(quikDate("20240512").isValid());
    }

    #[test]
    fn missing_files_are_very_old_and_mtime_minus_one() {
        let ghost = Path::new("definitely/not/a/file.txt");
        assert!((ageInMinutes(ghost) - 1e6).abs() < f64::EPSILON);
        assert_eq!(whenModified(ghost).to_string(), "1969-12-31T23:59:59.999");
    }

    #[test]
    fn when_modified_reflects_a_set_mtime() {
        let dir = std::env::temp_dir();
        let f = dir.join("uni_timeutils_mtime_probe.txt");
        std::fs::write(&f, "probe").expect("write probe");
        // 2024-05-12T14:30:00Z
        let t = UNIX_EPOCH + std::time::Duration::from_millis(1_715_524_200_000);
        // write access: a read-only handle cannot set times on Windows
        let file = std::fs::OpenOptions::new()
            .write(true)
            .open(&f)
            .expect("open probe");
        file.set_modified(t).expect("set mtime");
        drop(file);
        assert_eq!(whenModified(&f).to_string(), "2024-05-12T14:30");
        drop(std::fs::remove_file(&f)); // best-effort cleanup
    }
}

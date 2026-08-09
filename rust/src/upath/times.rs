//! File timestamps — a port of the `lastMod*` family in `uni.ext.PathExts`.
//!
//! # File timestamps are UTC, and timezone does not appear in this API
//!
//! [`UPath::lastModifiedTime`] renders in **UTC**, with no zone or offset parameter. That is
//! not a concession to Rust's limitations; it is the same definition the Scala now uses, so the
//! two agree exactly and a fixture can pin the whole path.
//!
//! The reasoning is that a file timestamp is used either for *comparison* — where any
//! consistent offset cancels out — or for *display*, where being machine-independent is an
//! advantage. An offset parameter would have appeared on every call while nearly every caller
//! had no opinion about it. Measured across the 166-script corpus: `lastModifiedYMD` and
//! `weekDay` had **zero** callers, and of eight `lastModifiedTime` callers three were pure
//! comparisons.
//!
//! It also removed an inconsistency on the Scala side, where `epoch2DateTime` had always
//! defaulted to UTC while `lastModifiedTime` used the system zone — so the two disagreed by the
//! local offset for the same file.
//!
//! A caller who genuinely wants local time converts explicitly with [`epoch2DateTime`], having
//! obtained the offset from a timezone crate: `std` has neither a timezone database nor any way
//! to read the local offset, and this port takes no dependency to get one.
//!
//! # A missing file reads as epoch zero
//!
//! Java's `File.lastModified()` returns `0` for a file that does not exist or cannot be
//! stat'd, and never throws. Rust's equivalent is a double `Result`
//! (`fs::metadata()?.modified()?`). This mirrors the Java: `0`, no error. That preserves
//! parity with every caller written against the Scala, and matches how the values are
//! actually used — `newerThan`/`olderThan` guard with `isFile` first, so the sentinel is
//! never reached in the paths that matter.
//!
//! Note `0` is also a legitimate timestamp, so Java cannot distinguish a missing file from one
//! stamped at the epoch, and neither can this. What this does *not* do is conflate pre-1970
//! with the epoch: see [`millisOf`].
//!
//! # The `Ago` arithmetic is deliberately split from the clock
//!
//! [`agoFromMillis`] takes the elapsed milliseconds and does the arithmetic; the `lastMod*Ago`
//! methods just read the clock and call it. That split is what makes the arithmetic testable
//! and fixture-able, because the methods themselves are non-deterministic by nature.
//!
//! It matters more than it looks, because the Scala rounds at **every step, each time on an
//! already-rounded value**:
//!
//! ```text
//! secondsAgo = millisAgo / 1000.0            (unrounded)
//! minutesAgo = round(secondsAgo / 60.0, 6)
//! hoursAgo   = round(minutesAgo / 60.0, 6)   <- rounds a rounded value
//! daysAgo    = round(hoursAgo   / 24.0, 6)   <- rounds a twice-rounded value
//! ```
//!
//! So `daysAgo` is **not** `round(millis / 86_400_000, 6)`. The difference is ~1e-6 and
//! irrelevant to any real use, but computing it the tidier direct way would disagree with the
//! Scala in the last decimal — and since these read the clock, no fixture would catch it. The
//! chain is transcribed exactly for that reason.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name, so a script kept in both \
              languages needs no mental translation. Internal helpers and Rust trait \
              contracts stay snake_case, so the case says whether a Scala counterpart exists."
)]

use std::time::SystemTime;
use std::time::UNIX_EPOCH;

use crate::upath::UPath;
use crate::utime::UniDateTime;

/// Milliseconds per day, per hour, and so on — named so the chain below reads as the Scala.
const MILLIS_PER_SEC: f64 = 1000.0;

/// `uni.helpers.round(number, scale = 6)`: half-up to six decimal places.
///
/// Half-up, not Rust's `round` (half-away-from-zero on the whole number). The Scala goes
/// through `BigDecimal.setScale(6, HALF_UP)`, and at six decimal places on values of this
/// magnitude the two agree — but the scaling has to be explicit either way.
#[must_use]
pub fn round6(number: f64) -> f64 {
    if !number.is_finite() {
        return number;
    }
    let scale = 1_000_000.0_f64;
    let scaled = number * scale;
    // HALF_UP means away from zero at exactly .5, which is what `abs`+`floor`+sign gives.
    let rounded = (scaled.abs() + 0.5).floor() * scaled.signum();
    rounded / scale
}

/// The four `Ago` values for a given elapsed-milliseconds count.
///
/// Split from the clock so the arithmetic is deterministic and can be pinned by a fixture;
/// the methods on [`UPath`] supply `millisAgo` from the clock and delegate here.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Ago {
    /// Elapsed seconds, unrounded — as the Scala leaves it.
    pub seconds: f64,
    /// Elapsed minutes, rounded to 6 dp.
    pub minutes: f64,
    /// Elapsed hours, rounded to 6 dp **from the rounded minutes**.
    pub hours: f64,
    /// Elapsed days, rounded to 6 dp **from the rounded hours**.
    pub days: f64,
}

/// Reproduces the Scala chain exactly, including that each step rounds an already-rounded
/// value. See the module docs for why this is not simplified.
#[must_use]
pub fn agoFromMillis(millisAgo: i64) -> Ago {
    #[expect(
        clippy::cast_precision_loss,
        reason = "matches the Scala, which divides a Long by a Double; at millisecond \
                  magnitudes f64 is exact well past any file age"
    )]
    let seconds = millisAgo as f64 / MILLIS_PER_SEC;
    let minutes = round6(seconds / 60.0);
    let hours = round6(minutes / 60.0);
    let days = round6(hours / 24.0);
    Ago {
        seconds,
        minutes,
        hours,
        days,
    }
}

impl UPath {
    /// The file's last-modified time as epoch milliseconds, or `0` when it cannot be read.
    ///
    /// `0` rather than an error, mirroring Java's `File.lastModified()`. See the module docs.
    #[must_use]
    pub fn lastModified(&self) -> i64 {
        std::fs::metadata(self.as_std_path())
            .and_then(|m| m.modified())
            .ok()
            .map_or(0, millisOf)
    }

    /// Milliseconds since the file was last modified.
    #[must_use]
    pub fn lastModMillisAgo(&self) -> i64 {
        nowMillis().saturating_sub(self.lastModified())
    }

    /// All four `Ago` values at once, from a single clock read.
    ///
    /// Preferred over calling the four accessors in turn, which read the clock four times and
    /// can therefore disagree with each other.
    #[must_use]
    pub fn ago(&self) -> Ago {
        agoFromMillis(self.lastModMillisAgo())
    }

    /// Seconds since the file was last modified, unrounded.
    #[must_use]
    pub fn lastModSecondsAgo(&self) -> f64 {
        self.ago().seconds
    }
    /// Minutes since the file was last modified, to 6 dp.
    #[must_use]
    pub fn lastModMinutesAgo(&self) -> f64 {
        self.ago().minutes
    }
    /// Hours since the file was last modified, to 6 dp.
    #[must_use]
    pub fn lastModHoursAgo(&self) -> f64 {
        self.ago().hours
    }
    /// Days since the file was last modified, to 6 dp.
    #[must_use]
    pub fn lastModDaysAgo(&self) -> f64 {
        self.ago().days
    }

    /// The file's last-modified time, in UTC. No offset parameter — see the module docs.
    #[must_use]
    pub fn lastModifiedTime(&self) -> UniDateTime {
        epoch2DateTime(self.lastModified(), 0)
    }

    /// `yyyy-MM-dd HH:mm:ss` in UTC.
    #[must_use]
    pub fn lastModifiedYMD(&self) -> String {
        self.lastModifiedTime().ymdhms()
    }

    /// Day of the week of last modification, in UTC: 1 = Monday .. 7 = Sunday.
    #[must_use]
    pub fn weekDay(&self) -> i32 {
        self.lastModifiedTime().dayOfWeekNum()
    }

    /// Three-letter weekday abbreviation of last modification, in UTC: `Mon` .. `Sun`.
    #[must_use]
    pub fn weekDayName(&self) -> String {
        self.lastModifiedTime().dayOfWeekName()
    }

    /// True when **this** file was modified more recently than `other`, and both are files.
    ///
    /// The comparison reads as the name does. Before 0.16.0 both languages had it inverted --
    /// `a.newerThan(b)` answered whether *b* was newer -- and this port faithfully reproduced the
    /// inversion; both flipped together. The `isFile` guards are the Scala's, and they are what
    /// make the epoch-zero sentinel unreachable here.
    #[must_use]
    pub fn newerThan(&self, other: &Self) -> bool {
        self.isFile() && other.isFile() && self.lastModified() > other.lastModified()
    }

    /// True when **this** file was modified less recently than `other`, and both are files.
    #[must_use]
    pub fn olderThan(&self, other: &Self) -> bool {
        self.isFile() && other.isFile() && self.lastModified() < other.lastModified()
    }
}

/// Epoch milliseconds to a date at the given UTC offset.
///
/// Unlike the methods above, the offset here is meaningful rather than a don't-care: converting
/// an epoch value *at a stated offset* is what this function is for. Pass `0` for UTC, which is
/// what Scala's `epoch2DateTime(epoch, timezone = UTC)` defaults to.
///
/// The offset shifts the rendered fields but is **not** recorded on the result, so
/// `epoch2DateTime(t, -420).offsetMinutes()` is `None` and its fields read as Mountain time.
///
/// Recording it would be more informative, and was the first implementation. It is wrong for
/// parity: Scala's `epoch2DateTime` goes through `UniDateTime.ofInstant` -> `from`, which uses
/// the zone to shift and then discards it. A Rust result carrying `Some(-420)` where Scala
/// carries `None` would compare unequal for the same timestamp across the two languages —
/// exactly the silent divergence these fixtures exist to prevent.
///
/// The field is reserved for an offset *stated in the source text*, not one inferred from a
/// conversion. Mirrors Scala's `epoch2DateTime(epoch, timezone)`, with an offset in place of a
/// `ZoneId`, since resolving a zone name needs a database `std` does not have.
#[must_use]
pub fn epoch2DateTime(epochMillis: i64, offsetMinutes: i32) -> UniDateTime {
    let shifted = epochMillis.saturating_add(i64::from(offsetMinutes).saturating_mul(60_000));
    let days = shifted.div_euclid(86_400_000);
    let within = shifted.rem_euclid(86_400_000);
    let base = UniDateTime::ofEpochDay(days);
    let millis_of_day = i32::try_from(within).unwrap_or(0);
    let secs = millis_of_day / 1000;
    UniDateTime::ofFull(
        base.year(),
        base.month(),
        base.day(),
        secs / 3600,
        (secs % 3600) / 60,
        secs % 60,
        (millis_of_day % 1000) * 1_000_000,
        None,
    )
}

/// A `SystemTime` as epoch milliseconds, **signed**.
///
/// Both directions are needed, and getting this wrong is easy: `duration_since` returns `Err`
/// when the receiver is *earlier* than the argument, so the obvious
/// `t.duration_since(UNIX_EPOCH).ok().unwrap_or(0)` silently reports a pre-1970 file as the
/// epoch itself. Java returns a negative `long` there, and files stamped before 1970 do occur —
/// archives, deliberately back-dated files, some checkouts. So the error branch measures the
/// other way and negates.
#[must_use]
pub fn millisOf(t: SystemTime) -> i64 {
    match t.duration_since(UNIX_EPOCH) {
        Ok(d) => i64::try_from(d.as_millis()).unwrap_or(i64::MAX),
        Err(e) => i64::try_from(e.duration().as_millis())
            .map(|m| -m)
            .unwrap_or(i64::MIN),
    }
}

/// Now, as epoch milliseconds.
fn nowMillis() -> i64 {
    millisOf(SystemTime::now())
}

#[cfg(test)]
mod tests {
    use super::agoFromMillis;
    use super::millisOf;
    use super::epoch2DateTime;
    use super::round6;

    #[test]
    fn round6_is_half_up_at_six_places() {
        assert!((round6(1.234_567_49) - 1.234_567).abs() < 1e-12);
        assert!((round6(1.234_567_5) - 1.234_568).abs() < 1e-12);
        assert!((round6(-1.234_567_5) + 1.234_568).abs() < 1e-12);
        assert!((round6(2.0) - 2.0).abs() < 1e-12);
    }

    #[test]
    fn ago_chain_rounds_at_every_step() {
        // One day exactly: the chain and a direct computation agree here.
        let a = agoFromMillis(86_400_000);
        assert!((a.seconds - 86_400.0).abs() < 1e-9);
        assert!((a.minutes - 1440.0).abs() < 1e-9);
        assert!((a.hours - 24.0).abs() < 1e-9);
        assert!((a.days - 1.0).abs() < 1e-9);
    }

    #[test]
    fn ago_chain_is_not_the_direct_computation() {
        // The case the module docs describe: rounding at every step differs from rounding
        // once. Measured over 200k file ages up to 400 days, the two disagree for **2.0% of
        // inputs**, always by exactly one unit in the sixth decimal place -- so a guessed
        // value is likely to agree by luck. These four are known-divergent.
        //
        // If this ever starts matching, the chain has been "simplified" and parity with the
        // Scala is gone. Nothing else would catch it: the `lastMod*Ago` methods read the
        // clock, so no fixture can pin them.
        for (millis, expected) in [
            (12_001_768_487_i64, 138.909_358_f64),
            (10_385_758_440, 120.205_537),
            (8_936_028_647, 103.426_258),
            (4_571_094_167, 52.906_183),
        ] {
            let chained = agoFromMillis(millis).days;
            #[expect(clippy::cast_precision_loss, reason = "test fixture magnitude")]
            let direct = round6(millis as f64 / 86_400_000.0);
            assert!(
                (chained - expected).abs() < 1e-9,
                "chained days for {millis} was {chained}, expected {expected}"
            );
            assert_ne!(
                chained, direct,
                "chained and direct agreed for {millis}; the rounding order must be preserved"
            );
            assert!(
                (chained - direct).abs() < 1e-5,
                "the difference should be one unit in the last place, was {}",
                (chained - direct).abs()
            );
        }
    }

    #[test]
    fn millis_of_is_signed_across_the_epoch() {
        use std::time::Duration;
        use std::time::SystemTime;
        use std::time::UNIX_EPOCH;
        assert_eq!(millisOf(UNIX_EPOCH), 0);
        assert_eq!(millisOf(UNIX_EPOCH + Duration::from_millis(1_500)), 1_500);
        // The case the first implementation got wrong: `duration_since` errors here, and
        // treating that as "unknown -> 0" reports a 1969 file as the epoch. Java returns a
        // negative value, so this must too.
        let pre: SystemTime = UNIX_EPOCH - Duration::from_secs(86_400);
        assert!(
            pre.duration_since(UNIX_EPOCH).is_err(),
            "premise: duration_since errors for an earlier receiver"
        );
        assert_eq!(millisOf(pre), -86_400_000);
        assert_eq!(millisOf(UNIX_EPOCH - Duration::from_millis(1)), -1);
    }

    #[test]
    fn epoch_zero_is_the_epoch() {
        assert_eq!(epoch2DateTime(0, 0).to_string(), "1970-01-01T00:00");
        assert_eq!(epoch2DateTime(0, 0).offsetMinutes(), None);
    }

    #[test]
    fn epoch_conversion_shifts_fields_without_recording_the_offset() {
        // 2024-05-12T14:30:00Z
        let t = 1_715_524_200_000_i64;
        assert_eq!(epoch2DateTime(t, 0).to_string(), "2024-05-12T14:30");
        assert_eq!(epoch2DateTime(t, -420).to_string(), "2024-05-12T07:30");
        assert_eq!(epoch2DateTime(t, 330).to_string(), "2024-05-12T20:00");
        // Not recorded, matching Scala: see the note on `epoch2DateTime`.
        assert_eq!(epoch2DateTime(t, -420).offsetMinutes(), None);
    }

    #[test]
    fn millis_survive_as_nanos() {
        let t = 1_715_524_200_123_i64;
        assert_eq!(epoch2DateTime(t, 0).to_string(), "2024-05-12T14:30:00.123");
    }

    #[test]
    fn before_the_epoch_borrows_correctly() {
        assert_eq!(epoch2DateTime(-1, 0).to_string(), "1969-12-31T23:59:59.999");
        assert_eq!(epoch2DateTime(-86_400_000, 0).to_string(), "1969-12-31T00:00");
    }
}

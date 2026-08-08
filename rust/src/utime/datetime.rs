//! A date and time as plain integer fields.
//!
//! A port of `uni.time.UniDateTime`. The Scala side exists to decouple the date type from
//! `java.time`; this is the payoff — the same fields, the same validation, the same sentinels,
//! with **no date-library dependency**. The alternative was a crate plus a translation layer
//! from Java pattern letters to strftime codes.
//!
//! # Where this differs from the Scala, and why
//!
//! The Scala arithmetic delegates to `java.time` for calendar math. There is nothing to
//! delegate to here, so it is written out: day shifts go through the epoch-day conversion,
//! month and year shifts clamp to the shorter month, and time shifts carry into the date.
//! Every one of those is pinned against the Scala — and therefore against `java.time` — by
//! `tests/date_parity.rs`, which is the whole reason that fixture exists.
//!
//! # Validity is enforced here
//!
//! [`UniDateTime::of`] validates and yields [`UniDateTime::BAD_DATE`] rather than producing an
//! impossible value, so the fields are private and `of` is the only way in. February 30th is
//! data, not a panic.
//!
//! # Sentinels
//!
//! [`UniDateTime::BAD_DATE`] and [`UniDateTime::EMPTY_DATE`] carry **day 0**, a date that
//! exists in no month. Since `of` validates, no constructed date can equal a sentinel — the
//! collision is impossible rather than unlikely. They were legal dates once
//! (`1900-01-02T03:04:05`), and that value turns up in real financial data as a placeholder.
//!
//! They render as `<BadDate>` / `<EmptyDate>` so a failure is visible instead of reading as a
//! date from 1900, and they absorb arithmetic the way a NaN does.

use core::cmp::Ordering;
use core::fmt;

use crate::utime::format;
use crate::utime::format::FormatError;

/// A date and time, or one of the two sentinels.
///
/// `PartialEq` includes the offset; the ordering deliberately does not — see [`Self::cmp`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct UniDateTime {
    year: i32,
    month: i32,
    day: i32,
    hour: i32,
    minute: i32,
    second: i32,
    nano: i32,
    offset_minutes: Option<i32>,
}

/// Days from 1970-01-01 to 0000-03-01, the shift Hinnant's algorithms are built around.
const EPOCH_SHIFT: i64 = 719_468;
/// Days in a 400-year Gregorian era.
const DAYS_PER_ERA: i64 = 146_097;
/// One less than [`DAYS_PER_ERA`], and **not** interchangeable with it.
///
/// `civil_from_days` divides the day-of-era by this when recovering the year-of-era. Using
/// 146_097 there yields 400 instead of 399 on the last day of an era, which lands the result a
/// day into the next month: 2000-02-29 came back as 2000-03-01. The parity fixture caught it
/// on the century leap day, which is the only place it shows.
const DAYS_PER_ERA_LAST: i64 = 146_096;
const NANOS_PER_SEC: i64 = 1_000_000_000;
const SECS_PER_DAY: i64 = 86_400;

impl UniDateTime {
    /// Unparseable input.
    pub const BAD_DATE: Self = Self {
        year: 1900,
        month: 1,
        day: 0,
        hour: 3,
        minute: 4,
        second: 5,
        nano: 0,
        offset_minutes: None,
    };

    /// Empty input, as distinct from unparseable.
    pub const EMPTY_DATE: Self = Self {
        year: 1800,
        month: 1,
        day: 0,
        hour: 3,
        minute: 4,
        second: 5,
        nano: 0,
        offset_minutes: None,
    };

    // ── Accessors ─────────────────────────────────────────────────────────────

    /// The year.
    #[must_use]
    pub const fn year(&self) -> i32 {
        self.year
    }
    /// The month, 1-12.
    #[must_use]
    pub const fn month(&self) -> i32 {
        self.month
    }
    /// Alias for [`Self::month`], mirroring the Scala `monthNum`.
    #[must_use]
    pub const fn month_num(&self) -> i32 {
        self.month
    }
    /// The day of the month, 1-31 (0 for a sentinel).
    #[must_use]
    pub const fn day(&self) -> i32 {
        self.day
    }
    /// Alias for [`Self::day`].
    #[must_use]
    pub const fn day_of_month(&self) -> i32 {
        self.day
    }
    /// The hour, 0-23.
    #[must_use]
    pub const fn hour(&self) -> i32 {
        self.hour
    }
    /// The minute, 0-59.
    #[must_use]
    pub const fn minute(&self) -> i32 {
        self.minute
    }
    /// The second, 0-59.
    #[must_use]
    pub const fn second(&self) -> i32 {
        self.second
    }
    /// The nanosecond of the second.
    #[must_use]
    pub const fn nano(&self) -> i32 {
        self.nano
    }
    /// The stated UTC offset in minutes east of Greenwich, if any.
    #[must_use]
    pub const fn offset_minutes(&self) -> Option<i32> {
        self.offset_minutes
    }

    // ── Construction ──────────────────────────────────────────────────────────

    /// Days in `month` of `year`, Gregorian leap rule included. `0` for a month out of range.
    #[must_use]
    pub const fn days_in_month(year: i32, month: i32) -> i32 {
        match month {
            1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
            4 | 6 | 9 | 11 => 30,
            2 => {
                if year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) {
                    29
                } else {
                    28
                }
            }
            _ => 0,
        }
    }

    /// Whether these fields name a real moment.
    #[must_use]
    #[expect(
        clippy::too_many_arguments,
        reason = "taking the fields rather than a date object is the decoupled design; a struct \
                  parameter would only move the list"
    )]
    pub const fn fields_valid(
        year: i32,
        month: i32,
        day: i32,
        hour: i32,
        minute: i32,
        second: i32,
        nano: i32,
    ) -> bool {
        month >= 1
            && month <= 12
            && day >= 1
            && day <= Self::days_in_month(year, month)
            && hour >= 0
            && hour <= 23
            && minute >= 0
            && minute <= 59
            && second >= 0
            && second <= 59
            && nano >= 0
            && nano <= 999_999_999
    }

    /// Offsets ISO 8601 admits: +/-18:00.
    #[must_use]
    pub const fn is_valid_offset(minutes: i32) -> bool {
        minutes >= -1080 && minutes <= 1080
    }

    /// Builds a value, or [`Self::BAD_DATE`] when the fields do not name a real moment.
    #[must_use]
    #[expect(
        clippy::too_many_arguments,
        reason = "mirrors the Scala `UniDateTime.of`, whose parameter list is the type's shape"
    )]
    pub const fn of_full(
        year: i32,
        month: i32,
        day: i32,
        hour: i32,
        minute: i32,
        second: i32,
        nano: i32,
        offset_minutes: Option<i32>,
    ) -> Self {
        let offset_ok = match offset_minutes {
            Some(o) => Self::is_valid_offset(o),
            None => true,
        };
        if Self::fields_valid(year, month, day, hour, minute, second, nano) && offset_ok {
            Self {
                year,
                month,
                day,
                hour,
                minute,
                second,
                nano,
                offset_minutes,
            }
        } else {
            Self::BAD_DATE
        }
    }

    /// A date at midnight, or [`Self::BAD_DATE`].
    #[must_use]
    pub const fn of_ymd(year: i32, month: i32, day: i32) -> Self {
        Self::of_full(year, month, day, 0, 0, 0, 0, None)
    }

    /// A date and time to the second, or [`Self::BAD_DATE`].
    #[must_use]
    pub const fn of(year: i32, month: i32, day: i32, hour: i32, minute: i32, second: i32) -> Self {
        Self::of_full(year, month, day, hour, minute, second, 0, None)
    }

    /// True unless this is one of the sentinels.
    #[must_use]
    pub fn is_valid(&self) -> bool {
        *self != Self::BAD_DATE && *self != Self::EMPTY_DATE
    }

    // ── Epoch-day conversion ──────────────────────────────────────────────────
    //
    // Hinnant's civil-calendar algorithms, as plain integer arithmetic on the proleptic
    // Gregorian calendar. `div_euclid`/`rem_euclid` throughout: the algorithms are written for
    // C++'s truncating division with explicit negative-branch adjustments, and Euclidean
    // division expresses the same thing without them.

    /// Days from 1970-01-01 to the given civil date.
    #[must_use]
    pub fn epoch_day_of(year: i32, month: i32, day: i32) -> i64 {
        let m = i64::from(month);
        let y0 = i64::from(year) - i64::from(month <= 2);
        let era = y0.div_euclid(400);
        let yoe = y0 - era * 400;
        let mp = if m > 2 { m - 3 } else { m + 9 };
        let doy = (153 * mp + 2) / 5 + i64::from(day) - 1;
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        era * DAYS_PER_ERA + doe - EPOCH_SHIFT
    }

    /// Days since 1970-01-01, ignoring the time of day.
    ///
    /// A sentinel has no meaningful epoch day; both yield `i64::MIN` so a caller sorting or
    /// comparing cannot mistake one for a date near the epoch.
    #[must_use]
    pub fn to_epoch_day(&self) -> i64 {
        if self.is_valid() {
            Self::epoch_day_of(self.year, self.month, self.day)
        } else {
            i64::MIN
        }
    }

    /// The civil date `epoch_day` days after 1970-01-01, at midnight.
    #[must_use]
    pub fn of_epoch_day(epoch_day: i64) -> Self {
        let (y, m, d) = Self::civil_from_days(epoch_day);
        Self::of_ymd(y, m, d)
    }

    fn civil_from_days(epoch_day: i64) -> (i32, i32, i32) {
        let z = epoch_day + EPOCH_SHIFT;
        let era = z.div_euclid(DAYS_PER_ERA);
        let doe = z - era * DAYS_PER_ERA;
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / DAYS_PER_ERA_LAST) / 365;
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        let mp = (5 * doy + 2) / 153;
        let d = doy - (153 * mp + 2) / 5 + 1;
        let m = if mp < 10 { mp + 3 } else { mp - 9 };
        let y = yoe + era * 400 + i64::from(m <= 2);
        (
            i32::try_from(y).unwrap_or(i32::MAX),
            i32::try_from(m).unwrap_or(1),
            i32::try_from(d).unwrap_or(1),
        )
    }

    /// Nanoseconds since midnight.
    fn nano_of_day(&self) -> i64 {
        ((i64::from(self.hour) * 60 + i64::from(self.minute)) * 60 + i64::from(self.second))
            * NANOS_PER_SEC
            + i64::from(self.nano)
    }

    /// Rebuilds from an epoch day plus a nanosecond-of-day, carrying any overflow into the
    /// date. Preserves the offset, which arithmetic never shifts.
    fn rebuilt_from(&self, epoch_day: i64, nanos: i64) -> Self {
        let day_nanos = SECS_PER_DAY * NANOS_PER_SEC;
        let extra_days = nanos.div_euclid(day_nanos);
        let within = nanos.rem_euclid(day_nanos);
        let (y, m, d) = Self::civil_from_days(epoch_day + extra_days);
        let secs = within / NANOS_PER_SEC;
        Self::of_full(
            y,
            m,
            d,
            i32::try_from(secs / 3600).unwrap_or(0),
            i32::try_from((secs % 3600) / 60).unwrap_or(0),
            i32::try_from(secs % 60).unwrap_or(0),
            i32::try_from(within % NANOS_PER_SEC).unwrap_or(0),
            self.offset_minutes,
        )
    }

    // ── Arithmetic ────────────────────────────────────────────────────────────
    //
    // A sentinel absorbs every one of these, as a NaN does. `BAD_DATE` means "this did not
    // parse"; shifting it by a day would produce an ordinary 1900-01-03 that no longer
    // compares equal to the sentinel, so a failure would slip past the check meant to catch
    // it.

    fn shift_nanos(&self, delta: i64) -> Self {
        if !self.is_valid() {
            return *self;
        }
        let base = Self::epoch_day_of(self.year, self.month, self.day);
        self.rebuilt_from(base, self.nano_of_day().saturating_add(delta))
    }

    fn shift_days(&self, days: i64) -> Self {
        if !self.is_valid() {
            return *self;
        }
        let base = Self::epoch_day_of(self.year, self.month, self.day).saturating_add(days);
        self.rebuilt_from(base, self.nano_of_day())
    }

    /// Shifts by whole months, clamping the day to the shorter month.
    ///
    /// January 31st plus one month is February 28th or 29th, never March 3rd — matching
    /// `java.time.LocalDateTime::plusMonths`, which the Scala side delegates to.
    fn shift_months(&self, months: i64) -> Self {
        if !self.is_valid() {
            return *self;
        }
        let total = (i64::from(self.year) * 12 + i64::from(self.month) - 1).saturating_add(months);
        let y = i32::try_from(total.div_euclid(12)).unwrap_or(self.year);
        let m = i32::try_from(total.rem_euclid(12) + 1).unwrap_or(self.month);
        let d = self.day.min(Self::days_in_month(y, m));
        Self::of_full(
            y,
            m,
            d,
            self.hour,
            self.minute,
            self.second,
            self.nano,
            self.offset_minutes,
        )
    }

    /// Adds nanoseconds.
    #[must_use]
    pub fn plus_nanos(&self, n: i64) -> Self {
        self.shift_nanos(n)
    }
    /// Adds seconds.
    #[must_use]
    pub fn plus_seconds(&self, n: i64) -> Self {
        self.shift_nanos(n.saturating_mul(NANOS_PER_SEC))
    }
    /// Adds minutes.
    #[must_use]
    pub fn plus_minutes(&self, n: i64) -> Self {
        self.plus_seconds(n.saturating_mul(60))
    }
    /// Adds hours.
    #[must_use]
    pub fn plus_hours(&self, n: i64) -> Self {
        self.plus_seconds(n.saturating_mul(3600))
    }
    /// Adds days.
    #[must_use]
    pub fn plus_days(&self, n: i64) -> Self {
        self.shift_days(n)
    }
    /// Adds weeks.
    #[must_use]
    pub fn plus_weeks(&self, n: i64) -> Self {
        self.shift_days(n.saturating_mul(7))
    }
    /// Adds months, clamping the day.
    #[must_use]
    pub fn plus_months(&self, n: i64) -> Self {
        self.shift_months(n)
    }
    /// Adds years, clamping a leap day.
    #[must_use]
    pub fn plus_years(&self, n: i64) -> Self {
        self.shift_months(n.saturating_mul(12))
    }

    /// Subtracts nanoseconds.
    #[must_use]
    pub fn minus_nanos(&self, n: i64) -> Self {
        self.shift_nanos(-n)
    }
    /// Subtracts seconds.
    #[must_use]
    pub fn minus_seconds(&self, n: i64) -> Self {
        self.plus_seconds(-n)
    }
    /// Subtracts minutes.
    #[must_use]
    pub fn minus_minutes(&self, n: i64) -> Self {
        self.plus_minutes(-n)
    }
    /// Subtracts hours.
    #[must_use]
    pub fn minus_hours(&self, n: i64) -> Self {
        self.plus_hours(-n)
    }
    /// Subtracts days.
    #[must_use]
    pub fn minus_days(&self, n: i64) -> Self {
        self.shift_days(-n)
    }
    /// Subtracts weeks.
    #[must_use]
    pub fn minus_weeks(&self, n: i64) -> Self {
        self.shift_days(-n.saturating_mul(7))
    }
    /// Subtracts months, clamping the day.
    #[must_use]
    pub fn minus_months(&self, n: i64) -> Self {
        self.shift_months(-n)
    }
    /// Subtracts years, clamping a leap day.
    #[must_use]
    pub fn minus_years(&self, n: i64) -> Self {
        self.shift_months(-n.saturating_mul(12))
    }

    /// Replaces the year, clamping a leap day into a non-leap February.
    #[must_use]
    pub fn with_year(&self, y: i32) -> Self {
        self.replaced(y, self.month, self.day.min(Self::days_in_month(y, self.month)))
    }

    /// Replaces the month, clamping the day to the shorter month.
    #[must_use]
    pub fn with_month(&self, m: i32) -> Self {
        self.replaced(
            self.year,
            m,
            self.day.min(Self::days_in_month(self.year, m).max(1)),
        )
    }

    /// Replaces the day of the month. A day the month does not have yields
    /// [`Self::BAD_DATE`], where `java.time` throws.
    #[must_use]
    pub fn with_day_of_month(&self, d: i32) -> Self {
        self.replaced(self.year, self.month, d)
    }

    fn replaced(&self, y: i32, m: i32, d: i32) -> Self {
        if !self.is_valid() {
            return *self;
        }
        Self::of_full(
            y,
            m,
            d,
            self.hour,
            self.minute,
            self.second,
            self.nano,
            self.offset_minutes,
        )
    }

    /// Replaces the hour. Out of range yields [`Self::BAD_DATE`].
    #[must_use]
    pub fn with_hour(&self, h: i32) -> Self {
        self.replaced_time(h, self.minute, self.second, self.nano)
    }
    /// Replaces the minute. Out of range yields [`Self::BAD_DATE`].
    #[must_use]
    pub fn with_minute(&self, mi: i32) -> Self {
        self.replaced_time(self.hour, mi, self.second, self.nano)
    }
    /// Replaces the second. Out of range yields [`Self::BAD_DATE`].
    #[must_use]
    pub fn with_second(&self, s: i32) -> Self {
        self.replaced_time(self.hour, self.minute, s, self.nano)
    }
    /// Replaces the nanosecond. Out of range yields [`Self::BAD_DATE`].
    #[must_use]
    pub fn with_nano(&self, n: i32) -> Self {
        self.replaced_time(self.hour, self.minute, self.second, n)
    }

    fn replaced_time(&self, h: i32, mi: i32, s: i32, nano: i32) -> Self {
        if !self.is_valid() {
            return *self;
        }
        Self::of_full(
            self.year,
            self.month,
            self.day,
            h,
            mi,
            s,
            nano,
            self.offset_minutes,
        )
    }

    /// Midnight on the same date.
    #[must_use]
    pub fn at_start_of_day(&self) -> Self {
        if !self.is_valid() {
            return *self;
        }
        Self {
            hour: 0,
            minute: 0,
            second: 0,
            nano: 0,
            ..*self
        }
    }

    /// The last day of this month, keeping the time.
    #[must_use]
    pub fn last_day_of_month(&self) -> Self {
        if !self.is_valid() {
            return *self;
        }
        Self {
            day: Self::days_in_month(self.year, self.month),
            ..*self
        }
    }

    /// The *next* occurrence of `dow` (1 = Monday .. 7 = Sunday).
    ///
    /// Matches the Scala `withDayOfWeek`, which uses `TemporalAdjusters.next`: a date already
    /// on `dow` moves a full week forward rather than standing still.
    #[must_use]
    pub fn with_day_of_week(&self, dow: i32) -> Self {
        if !self.is_valid() {
            return *self;
        }
        let current = self.day_of_week_num();
        let delta = (dow - current).rem_euclid(7);
        self.plus_days(if delta == 0 { 7 } else { i64::from(delta) })
    }

    /// 1 = Monday .. 7 = Sunday.
    #[must_use]
    pub fn day_of_week_num(&self) -> i32 {
        format::day_of_week(self.year, self.month, self.day)
    }

    // ── The offset ────────────────────────────────────────────────────────────

    /// This moment with a UTC offset attached, or removed by passing `None`.
    ///
    /// A sentinel absorbs this. Copying one with a new offset would otherwise yield a value
    /// that is *not* a sentinel yet still names an impossible date, so `is_valid` would answer
    /// `true` and the field accessors would hand out day 0.
    #[must_use]
    pub fn with_offset_minutes(&self, offset: Option<i32>) -> Self {
        if !self.is_valid() {
            return *self;
        }
        match offset {
            Some(o) if !Self::is_valid_offset(o) => *self,
            other => Self {
                offset_minutes: other,
                ..*self
            },
        }
    }

    /// The offset as `+HH:MM`, or empty when there is none.
    #[must_use]
    pub fn offset_text(&self) -> String {
        match self.offset_minutes {
            None => String::new(),
            Some(o) => {
                let sign = if o < 0 { '-' } else { '+' };
                let a = o.abs();
                format!("{sign}{:02}:{:02}", a / 60, a % 60)
            }
        }
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    /// Formats with a `java.time`-style pattern.
    ///
    /// # Errors
    ///
    /// [`FormatError`] on an unsupported pattern letter, a capital `Y`, or an unclosed quote.
    pub fn format(&self, fmt: &str) -> Result<String, FormatError> {
        format::format(
            self.year,
            self.month,
            self.day,
            self.hour,
            self.minute,
            self.second,
            self.nano,
            fmt,
        )
    }

    /// `yyyy-MM-dd`. A sentinel yields digits (`1900-01-00`), not a marker — see the note on
    /// [`Self::fmt`].
    #[must_use]
    pub fn ymd(&self) -> String {
        self.format("yyyy-MM-dd").unwrap_or_default()
    }

    /// `yyyy-MM-dd HH:mm:ss`.
    #[must_use]
    pub fn ymdhms(&self) -> String {
        self.format("yyyy-MM-dd HH:mm:ss").unwrap_or_default()
    }

    /// Formats with a pattern, yielding an empty string on a malformed pattern.
    ///
    /// The pattern formatters deliberately render a sentinel as digits rather than as a
    /// marker: they feed filenames, CSV keys and SQL binds, where a marker would produce
    /// valid-looking output that quietly matches nothing. Day zero makes the digits
    /// self-evidently not a date. Guard those paths with [`Self::is_valid`].
    #[must_use]
    pub fn fmt(&self, pattern: &str) -> String {
        self.format(pattern).unwrap_or_default()
    }

    // ── Offset extraction ─────────────────────────────────────────────────────

    /// Extracts a UTC offset from date text, in minutes east of Greenwich.
    ///
    /// Numeric offsets only, plus the zero-offset names (`UT`, `UTC`, `GMT`, trailing `Z`).
    /// Abbreviations like `MST` are deliberately not mapped: they are ambiguous — `CST` is
    /// three different offsets — and resolving them needs a timezone database, the dependency
    /// this type exists to avoid. `None` means "no offset stated", not "UTC".
    ///
    /// Hand-rolled rather than regex-based, there being no regex crate here. The anchoring
    /// rules are the ones the Scala regexes encode, and `tests/date_parity.rs` pins the two
    /// implementations together: an unanchored search finds an offset inside a bare date,
    /// reading `12-05-1024` as +10:24.
    #[must_use]
    pub fn offset_minutes_of(text: &str) -> Option<i32> {
        let zulu = Self::has_zulu(text);
        Self::scan_offset(text).or_else(|| zulu.then_some(0))
    }

    /// A trailing `Z` (which must follow a digit, so a name ending in Z is not mistaken for
    /// it), or one of the zero-offset names as a whole token.
    fn has_zulu(text: &str) -> bool {
        let named = text
            .split(|c: char| c.is_whitespace() || c == '(' || c == ')')
            .any(|t| matches!(t.to_ascii_lowercase().as_str(), "ut" | "utc" | "gmt"));
        if named {
            return true;
        }
        let trimmed = text.trim_end();
        let mut chars = trimmed.chars().rev();
        matches!(
            (chars.next(), chars.next()),
            (Some('Z' | 'z'), Some(prev)) if prev.is_ascii_digit()
        )
    }

    /// The `[+-]HH:?MM` form, anchored: the sign must follow whitespace or a time, and the
    /// offset must end the token.
    fn scan_offset(text: &str) -> Option<i32> {
        let b: Vec<char> = text.chars().collect();
        for (i, &c) in b.iter().enumerate() {
            if c != '+' && c != '-' {
                continue;
            }
            if !Self::sign_is_anchored(&b, i) {
                continue;
            }
            let (magnitude, end) = Self::read_hhmm(&b, i + 1)?;
            // Must end the token: end of input or whitespace.
            let ends = b.get(end).is_none_or(|&n| n.is_whitespace());
            if !ends {
                continue;
            }
            let signed = if c == '-' { -magnitude } else { magnitude };
            return Self::is_valid_offset(signed).then_some(signed);
        }
        None
    }

    /// Preceded by whitespace, or by `HH:MM` / `HH:MM:SS` — which is where a real offset sits.
    fn sign_is_anchored(b: &[char], i: usize) -> bool {
        match i.checked_sub(1).and_then(|p| b.get(p)) {
            None => false,
            Some(&prev) if prev.is_whitespace() => true,
            Some(_) => {
                // ...dd:dd immediately before the sign.
                let tail: Vec<char> = b[..i].iter().rev().take(5).copied().collect();
                matches!(tail.as_slice(), [d1, d2, ':', d3, d4]
                    if d1.is_ascii_digit() && d2.is_ascii_digit()
                        && d3.is_ascii_digit() && d4.is_ascii_digit())
            }
        }
    }

    /// Reads `HHMM` or `HH:MM` starting at `i`, returning the magnitude in minutes and the
    /// index just past it.
    fn read_hhmm(b: &[char], i: usize) -> Option<(i32, usize)> {
        let d = |k: usize| b.get(k).filter(|c| c.is_ascii_digit()).copied();
        let (h1, h2) = (d(i)?, d(i + 1)?);
        let mut k = i + 2;
        if b.get(k) == Some(&':') {
            k += 1;
        }
        let (m1, m2) = (d(k)?, d(k + 1)?);
        let to_i = |c: char| i32::from(u32::from(c) as u8 - b'0');
        let hours = to_i(h1) * 10 + to_i(h2);
        let mins = to_i(m1) * 10 + to_i(m2);
        Some((hours * 60 + mins, k + 2))
    }
}

/// Chronological by *local* fields, ignoring the offset — the same order the Scala
/// `Ordering[UniDateTime]` gives, which is `LocalDateTime`'s order.
///
/// Note this disagrees with `PartialEq`, which does include the offset: two values differing
/// only in offset compare `Ordering::Equal` here but are not `==`. The Scala side has the same
/// split, for the same reason — offset-aware ordering would need the fields normalised to UTC
/// first, and mixing the two silently is worse than making the caller choose.
impl Ord for UniDateTime {
    fn cmp(&self, other: &Self) -> Ordering {
        (
            self.year,
            self.month,
            self.day,
            self.hour,
            self.minute,
            self.second,
            self.nano,
        )
            .cmp(&(
                other.year,
                other.month,
                other.day,
                other.hour,
                other.minute,
                other.second,
                other.nano,
            ))
    }
}

impl PartialOrd for UniDateTime {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

/// ISO-8601, matching `LocalDateTime::toString` — except for the sentinels.
///
/// Seconds are omitted when zero and the fraction when it is: `2024-05-12T14:30`, not
/// `2024-05-12T14:30:00`. Scripts print these, so the shape is part of the compatibility
/// surface.
///
/// The sentinels break that shape on purpose. Rendering `BAD_DATE` as `1900-01-02T03:04:05`
/// was the classic silent-sentinel bug: it read as real data in every log line.
impl fmt::Display for UniDateTime {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if *self == Self::BAD_DATE {
            return f.write_str("<BadDate>");
        }
        if *self == Self::EMPTY_DATE {
            return f.write_str("<EmptyDate>");
        }
        write!(
            f,
            "{:04}-{:02}-{:02}T{:02}:{:02}",
            self.year, self.month, self.day, self.hour, self.minute
        )?;
        if self.nano != 0 {
            // 3, 6 or 9 fractional digits, never a trailing group of zeros.
            write!(f, ":{:02}.", self.second)?;
            if self.nano % 1_000_000 == 0 {
                write!(f, "{:03}", self.nano / 1_000_000)
            } else if self.nano % 1_000 == 0 {
                write!(f, "{:06}", self.nano / 1_000)
            } else {
                write!(f, "{:09}", self.nano)
            }
        } else if self.second != 0 {
            write!(f, ":{:02}", self.second)
        } else {
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::UniDateTime;

    #[test]
    fn of_validates_and_yields_bad_date() {
        assert_eq!(UniDateTime::of_ymd(2023, 2, 29), UniDateTime::BAD_DATE);
        assert_eq!(UniDateTime::of_ymd(2024, 13, 1), UniDateTime::BAD_DATE);
        assert!(UniDateTime::of_ymd(2024, 2, 29).is_valid());
    }

    #[test]
    fn sentinels_are_impossible_dates() {
        assert_eq!(UniDateTime::BAD_DATE.day(), 0);
        assert!(!UniDateTime::fields_valid(1900, 1, 0, 3, 4, 5, 0));
        // The moment BAD_DATE used to be is an ordinary date again.
        let real = UniDateTime::of(1900, 1, 2, 3, 4, 5);
        assert_ne!(real, UniDateTime::BAD_DATE);
        assert_eq!(real.to_string(), "1900-01-02T03:04:05");
    }

    #[test]
    fn display_omits_zero_seconds_and_fraction() {
        assert_eq!(UniDateTime::of(2024, 5, 12, 14, 30, 0).to_string(), "2024-05-12T14:30");
        assert_eq!(
            UniDateTime::of(2024, 5, 12, 14, 30, 45).to_string(),
            "2024-05-12T14:30:45"
        );
        assert_eq!(UniDateTime::BAD_DATE.to_string(), "<BadDate>");
        assert_eq!(UniDateTime::EMPTY_DATE.to_string(), "<EmptyDate>");
    }

    #[test]
    fn epoch_day_round_trips() {
        for n in (-40_000_i64..40_000).step_by(97) {
            let d = UniDateTime::of_epoch_day(n);
            assert_eq!(d.to_epoch_day(), n, "round trip for {n}");
        }
        assert_eq!(UniDateTime::of_epoch_day(0).ymd(), "1970-01-01");
        assert_eq!(UniDateTime::of_epoch_day(-1).ymd(), "1969-12-31");
    }

    #[test]
    fn month_arithmetic_clamps() {
        assert_eq!(UniDateTime::of_ymd(2024, 1, 31).plus_months(1).ymd(), "2024-02-29");
        assert_eq!(UniDateTime::of_ymd(2023, 1, 31).plus_months(1).ymd(), "2023-02-28");
        assert_eq!(UniDateTime::of_ymd(2024, 2, 29).plus_years(1).ymd(), "2025-02-28");
    }

    #[test]
    fn sentinels_absorb_arithmetic() {
        for s in [UniDateTime::BAD_DATE, UniDateTime::EMPTY_DATE] {
            assert_eq!(s.plus_days(1), s);
            assert_eq!(s.plus_months(3), s);
            assert_eq!(s.at_start_of_day(), s);
            assert_eq!(s.last_day_of_month(), s);
            assert_eq!(s.with_offset_minutes(Some(0)), s);
            assert_eq!(s.to_epoch_day(), i64::MIN);
        }
    }

    #[test]
    fn time_shift_carries_into_the_date() {
        let d = UniDateTime::of(2024, 5, 12, 23, 59, 59);
        assert_eq!(d.plus_seconds(1).to_string(), "2024-05-13T00:00");
        assert_eq!(d.plus_nanos(1_000_000_000).to_string(), "2024-05-13T00:00");
        let m = UniDateTime::of(2024, 5, 12, 0, 0, 0);
        assert_eq!(m.minus_seconds(1).to_string(), "2024-05-11T23:59:59");
    }

    #[test]
    fn offset_extraction_is_anchored() {
        assert_eq!(
            UniDateTime::offset_minutes_of("Thu, 14 Mar 2024 15:30:00 -0700"),
            Some(-420)
        );
        assert_eq!(UniDateTime::offset_minutes_of("2024-03-14T15:30:00Z"), Some(0));
        assert_eq!(UniDateTime::offset_minutes_of("14 Mar 2024 15:30 UTC"), Some(0));
        // A bare date must not read its own digits as an offset.
        assert_eq!(UniDateTime::offset_minutes_of("12-05-1024"), None);
        assert_eq!(UniDateTime::offset_minutes_of("2024-03-14"), None);
    }

    #[test]
    fn ordering_ignores_the_offset_where_equality_does_not() {
        let a = UniDateTime::of(2024, 5, 12, 14, 30, 0);
        let b = a.with_offset_minutes(Some(-420));
        assert_ne!(a, b);
        assert_eq!(a.cmp(&b), core::cmp::Ordering::Equal);
    }
}

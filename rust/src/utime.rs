//! Date and time as plain integer fields — a port of Scala's `uni.time`.
//!
//! Modules mirroring the Scala split:
//!
//! - [`datetime`] — [`UniDateTime`], the field-based date type: validation, sentinels,
//!   calendar arithmetic, epoch-day conversion, offset extraction.
//! - [`format`] — pattern-based formatting over those fields, a replacement for
//!   `DateTimeFormatter.ofPattern`.
//! - [`timeutils`] — the `TimeUtils` package-level surface: the clock, the
//!   between/duration family, the strict `quik*` parsers, and file age. Zone-naive
//!   and UTC-clocked by design — its module doc says exactly where that can differ
//!   from the Scala.
//!
//! # No date-library dependency
//!
//! That is the point of the design, on both sides. `std` has no calendar or date formatting,
//! and the alternative here was a crate plus a translation layer from Java's pattern letters
//! to strftime's codes. Because the Scala type is a plain case class of integers and its
//! formatter works on those integers rather than on a `java.time` object, the port needs
//! neither.
//!
//! # The parser
//!
//! [`smartparse`] is the port of `SmartParse`, the format-autodetecting parser --
//! [`parseDateSmart`] reads a date string of unknown format or answers `BAD_DATE`. The
//! dynamically-scoped `timeConfig` of the Scala becomes an explicit [`smartparse::TimeConfig`]
//! parameter on the `*With` forms.
//!
//! # Parity
//!
//! The Scala arithmetic delegates to `java.time`; this has nothing to delegate to, so it is
//! written out — Hinnant's civil-calendar algorithms for epoch days, explicit month-end
//! clamping, explicit carry for time shifts. `tests/date_parity.rs` checks all of it against
//! a committed fixture generated from the Scala, which makes `java.time` the transitive
//! oracle. Regenerate with `sbt "runMain uni.apps.DateParityGen"`.

pub mod datetime;
pub mod format;
pub mod smartparse;
pub mod timeutils;

pub use datetime::UniDateTime;
pub use format::FormatError;
pub use format::dayOfWeek;
pub use smartparse::parseDate;
pub use smartparse::parseDateSmart;
pub use timeutils::ageInDays;
pub use timeutils::ageInMinutes;
pub use timeutils::daysBetween;
pub use timeutils::daysRounded;
pub use timeutils::elapsedDays;
pub use timeutils::endOfMonth;
pub use timeutils::getDuration;
pub use timeutils::getMillis;
pub use timeutils::hoursBetween;
pub use timeutils::localOffsetMinutes;
pub use timeutils::minutesBetween;
pub use timeutils::monthAbbrev2Number;
pub use timeutils::now;
pub use timeutils::nowUTC;
pub use timeutils::quikDate;
pub use timeutils::quikDateTime;
pub use timeutils::secondsBetween;
pub use timeutils::secondsSince;
pub use timeutils::whenModified;
pub use timeutils::yesterday;

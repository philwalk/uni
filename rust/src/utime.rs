//! Date and time as plain integer fields — a port of Scala's `uni.time`.
//!
//! Two modules, mirroring the Scala split:
//!
//! - [`datetime`] — [`UniDateTime`], the field-based date type: validation, sentinels,
//!   calendar arithmetic, epoch-day conversion, offset extraction.
//! - [`format`] — pattern-based formatting over those fields, a replacement for
//!   `DateTimeFormatter.ofPattern`.
//!
//! # No date-library dependency
//!
//! That is the point of the design, on both sides. `std` has no calendar or date formatting,
//! and the alternative here was a crate plus a translation layer from Java's pattern letters
//! to strftime's codes. Because the Scala type is a plain case class of integers and its
//! formatter works on those integers rather than on a `java.time` object, the port needs
//! neither.
//!
//! # What is *not* ported
//!
//! `SmartParse` — the format-autodetecting parser. Only the date type and its formatter are
//! here. A Rust script that needs to read arbitrary date text still has to do that itself.
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

pub use datetime::UniDateTime;
pub use format::FormatError;
pub use format::dayOfWeek;

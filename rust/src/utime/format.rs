//! Pattern-based date formatting over plain integer fields.
//!
//! A port of `uni.time.DateFormat`. Like the Scala original it works on the field values
//! rather than on a date object, which is what lets it exist here at all: `std` has no date
//! formatting, and the alternative was a dependency plus a translation layer from Java's
//! pattern letters to strftime's `%` codes — more work than this, and one more thing to get
//! subtly wrong.
//!
//! # Supported pattern letters
//!
//! `y` year, `M` month, `d` day, `H` hour (0-23), `h` hour (1-12), `m` minute, `s` second,
//! `S` fraction, `a` AM/PM, `E` day of week.
//!
//! Repeat count sets the width, as in Java: `M` is `5`, `MM` is `05`, `MMM` is `May`, `MMMM`
//! is `May` spelled out. Text inside single quotes is literal (`'T'`), `''` is a literal
//! quote, and any non-letter passes through untouched.
//!
//! # Deliberate differences from `DateTimeFormatter`
//!
//! Month and weekday names are always English, matching the Scala side. `ofPattern` uses the
//! JVM's default FORMAT locale, which makes output depend on where the machine thinks it is.
//!
//! Capital `Y` — the week-based year — is rejected rather than guessed at. `ofPattern`
//! accepts it and quietly renders a *different* year near New Year, and its definition is
//! locale-dependent on top of that. Every occurrence found in the wild was a typo for `y`.
//!
//! An unsupported letter is an error rather than a literal, matching Java, which reserves
//! every ASCII letter in a pattern.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name, so a script kept in both \n              languages needs no mental translation. Internal helpers stay snake_case, so \n              the case says whether a Scala counterpart exists."
)]

use core::fmt;

/// A pattern that cannot be rendered. A malformed *pattern* is a programming error, unlike a
/// malformed date, which is data and yields
/// [`BAD_DATE`](crate::utime::datetime::UniDateTime::BAD_DATE).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FormatError {
    /// A pattern letter this implementation does not support.
    UnsupportedLetter {
        /// The offending letter.
        letter: char,
        /// The whole pattern, for context.
        pattern: String,
    },
    /// Capital `Y`, called out separately because the fix is specific.
    WeekBasedYear {
        /// The whole pattern, for context.
        pattern: String,
    },
    /// A `'` with no closing `'`.
    UnclosedQuote {
        /// The whole pattern, for context.
        pattern: String,
    },
}

impl fmt::Display for FormatError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match *self {
            Self::UnsupportedLetter {
                letter,
                ref pattern,
            } => write!(f, "unsupported pattern letter [{letter}] in [{pattern}]"),
            Self::WeekBasedYear { ref pattern } => write!(
                f,
                "pattern letter [Y] in [{pattern}] is the week-based year, not the calendar \
                 year; use lowercase [y]. It differs only near New Year, so a typo here \
                 renders a date a year out for a few days each December."
            ),
            Self::UnclosedQuote { ref pattern } => write!(f, "unclosed quote in pattern [{pattern}]"),
        }
    }
}

impl core::error::Error for FormatError {}

const MONTH_NAMES: [&str; 12] = [
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
];

/// Monday-first, matching `java.time.DayOfWeek`'s numbering.
const DAY_NAMES: [&str; 7] = [
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
    "Sunday",
];

/// Day of week for a proleptic Gregorian date: 1 = Monday .. 7 = Sunday.
///
/// Sakamoto's method. Needed here for `E`, and by the date type for `dayOfWeek`, so it
/// lives with the calendar arithmetic rather than being derived from a date object.
#[must_use]
pub fn dayOfWeek(year: i32, month: i32, day: i32) -> i32 {
    const T: [i32; 12] = [0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4];
    let y = if month < 3 { year - 1 } else { year };
    let idx = usize::try_from(month.clamp(1, 12) - 1).unwrap_or(0);
    // `rem_euclid`, not `%`: Rust's `%` keeps the sign of the dividend, so a negative year
    // would yield a negative index. Java's `%` behaves the same way, but the Scala original
    // only ever sees years >= 1, so this is the port being stricter rather than different.
    let dow = (y + y.div_euclid(4) - y.div_euclid(100) + y.div_euclid(400) + T[idx] + day)
        .rem_euclid(7);
    // Sakamoto yields 0 = Sunday; shift so Monday is 1.
    if dow == 0 { 7 } else { dow }
}

/// Zero-pads to `width`, keeping a leading `-` outside the padding.
fn pad(n: i32, width: usize) -> String {
    let body = n.unsigned_abs().to_string();
    let padded = if body.len() >= width {
        body
    } else {
        let mut s = String::with_capacity(width);
        for _ in 0..(width - body.len()) {
            s.push('0');
        }
        s.push_str(&body);
        s
    };
    if n < 0 { format!("-{padded}") } else { padded }
}

/// The fields, rendered per `fmt`.
///
/// # Errors
///
/// [`FormatError`] on an unsupported pattern letter, a capital `Y`, or an unclosed quote.
#[expect(
    clippy::too_many_arguments,
    reason = "mirrors the Scala signature, which takes the fields rather than a date object -- \
              that is the point of the design"
)]
pub fn format(
    year: i32,
    month: i32,
    day: i32,
    hour: i32,
    minute: i32,
    second: i32,
    nano: i32,
    fmt: &str,
) -> Result<String, FormatError> {
    let chars: Vec<char> = fmt.chars().collect();
    let mut out = String::with_capacity(fmt.len() + 8);
    let mut i = 0usize;
    while i < chars.len() {
        let c = chars[i];
        if c == '\'' {
            // '' is a literal quote; otherwise everything to the next quote is literal.
            if chars.get(i + 1) == Some(&'\'') {
                out.push('\'');
                i += 2;
            } else {
                let close = chars[i + 1..].iter().position(|&q| q == '\'').ok_or_else(|| {
                    FormatError::UnclosedQuote {
                        pattern: fmt.to_owned(),
                    }
                })?;
                out.extend(&chars[i + 1..=i + close]);
                i += close + 2;
            }
        } else if c.is_alphabetic() {
            let mut n = 0usize;
            while chars.get(i + n) == Some(&c) {
                n += 1;
            }
            out.push_str(&field(
                c, n, year, month, day, hour, minute, second, nano, fmt,
            )?);
            i += n;
        } else {
            out.push(c);
            i += 1;
        }
    }
    Ok(out)
}

#[expect(
    clippy::too_many_arguments,
    reason = "one field of a date needs the whole date; splitting it would only move the list"
)]
fn field(
    c: char,
    n: usize,
    year: i32,
    month: i32,
    day: i32,
    hour: i32,
    minute: i32,
    second: i32,
    nano: i32,
    fmt: &str,
) -> Result<String, FormatError> {
    let month_idx = usize::try_from(month.clamp(1, 12) - 1).unwrap_or(0);
    Ok(match c {
        // Two `y`s mean the last two digits; anything else is the year padded to width.
        'y' => {
            if n == 2 {
                pad(year.abs() % 100, 2)
            } else {
                pad(year, n)
            }
        }
        'Y' => {
            return Err(FormatError::WeekBasedYear {
                pattern: fmt.to_owned(),
            });
        }
        'M' => {
            if n >= 4 {
                MONTH_NAMES[month_idx].to_owned()
            } else if n == 3 {
                MONTH_NAMES[month_idx].chars().take(3).collect()
            } else {
                pad(month, n)
            }
        }
        'd' => pad(day, n),
        'H' => pad(hour, n),
        'h' => {
            // 12-hour clock: midnight and noon are both 12, not 0.
            let h = hour.rem_euclid(12);
            pad(if h == 0 { 12 } else { h }, n)
        }
        'm' => pad(minute, n),
        's' => pad(second, n),
        // Fraction of a second, truncated to the requested width rather than rounded, as
        // `DateTimeFormatter` does.
        'S' => {
            let divisor = 10_i32.pow(u32::try_from(9usize.saturating_sub(n)).unwrap_or(9));
            pad(nano / divisor.max(1), n)
        }
        'a' => (if hour < 12 { "AM" } else { "PM" }).to_owned(),
        'E' => {
            let idx = usize::try_from(dayOfWeek(year, month, day) - 1).unwrap_or(0);
            let name = DAY_NAMES[idx];
            if n >= 4 {
                name.to_owned()
            } else {
                name.chars().take(3).collect()
            }
        }
        other => {
            return Err(FormatError::UnsupportedLetter {
                letter: other,
                pattern: fmt.to_owned(),
            });
        }
    })
}

#[cfg(test)]
mod tests {
    use super::dayOfWeek;
    use super::format;
    use super::pad;
    use super::FormatError;

    #[test]
    fn pads_and_keeps_sign_outside() {
        assert_eq!(pad(5, 2), "05");
        assert_eq!(pad(123, 2), "123");
        assert_eq!(pad(-7, 3), "-007");
    }

    #[test]
    fn renders_the_documented_letters() {
        let f = |p: &str| format(2024, 5, 12, 14, 30, 45, 123_456_789, p);
        assert_eq!(f("yyyy-MM-dd").as_deref(), Ok("2024-05-12"));
        assert_eq!(f("yy").as_deref(), Ok("24"));
        assert_eq!(f("M/d/y").as_deref(), Ok("5/12/2024"));
        assert_eq!(f("MMM").as_deref(), Ok("May"));
        assert_eq!(f("MMMM").as_deref(), Ok("May"));
        assert_eq!(f("HH:mm:ss").as_deref(), Ok("14:30:45"));
        assert_eq!(f("h a").as_deref(), Ok("2 PM"));
        assert_eq!(f("SSS").as_deref(), Ok("123"));
        assert_eq!(f("E").as_deref(), Ok("Sun"));
        assert_eq!(f("EEEE").as_deref(), Ok("Sunday"));
    }

    #[test]
    fn twelve_hour_clock_maps_zero_and_noon_to_twelve() {
        assert_eq!(format(2024, 5, 12, 0, 0, 0, 0, "h a").as_deref(), Ok("12 AM"));
        assert_eq!(format(2024, 5, 12, 12, 0, 0, 0, "h a").as_deref(), Ok("12 PM"));
    }

    #[test]
    fn quoting() {
        let f = |p: &str| format(2024, 5, 12, 14, 30, 0, 0, p);
        assert_eq!(f("yyyy'T'MM").as_deref(), Ok("2024T05"));
        assert_eq!(f("''yyyy").as_deref(), Ok("'2024"));
        assert_eq!(f("'literal text'").as_deref(), Ok("literal text"));
        assert!(matches!(
            f("yyyy'unclosed"),
            Err(FormatError::UnclosedQuote { .. })
        ));
    }

    #[test]
    fn rejects_week_based_year_and_unknown_letters() {
        assert!(matches!(
            format(2024, 12, 30, 0, 0, 0, 0, "YYYY"),
            Err(FormatError::WeekBasedYear { .. })
        ));
        assert!(matches!(
            format(2024, 5, 12, 0, 0, 0, 0, "Q"),
            Err(FormatError::UnsupportedLetter { letter: 'Q', .. })
        ));
    }

    #[test]
    fn day_of_week_matches_known_dates() {
        // 2024-05-12 was a Sunday; 2000-02-29 a Tuesday; 1900-03-01 a Thursday.
        assert_eq!(dayOfWeek(2024, 5, 12), 7);
        assert_eq!(dayOfWeek(2000, 2, 29), 2);
        assert_eq!(dayOfWeek(1900, 3, 1), 4);
    }
}

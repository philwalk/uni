//! Formatting and parsing helpers over [`Big`] — a port of Scala's
//! `uni.data.BigUtils` (exported there as `uni.data.*`).
//!
//! # Fidelity notes
//!
//! [`numStr`] reproduces Java's `String.format("%w.df", double)` exactly: Java rounds
//! the *shortest decimal representation* of the double half-up (its documented `%f`
//! contract), which is also what Rust's `{}` display of an `f64` produces — so the
//! pipeline here is shortest-digits → [`Big::setScale`]`(dec, HalfUp)` → pad. The
//! fixture pins it, including the `-0.00` blanking quirk and the abbreviate branches.
//! (Scala's `format` is locale-dependent; the reference behavior is the `.`-decimal
//! locale the fixtures were generated under.)
//!
//! [`isNumeric`] ports four Scala regexes as character scans, bug-for-bug: pattern 3
//! takes `K`/`M`/`B` case-sensitively while pattern 1 is case-insensitive, and
//! pattern 4's unescaped `.` matches any character. The fixture pins a broad input
//! set so none of that drifts.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name, so a script kept in both \
              languages needs no mental translation. Internal helpers stay snake_case, \
              so the case says whether a Scala counterpart exists."
)]

use crate::udata::big::RoundingMode;
use crate::udata::Big;

/// The characters `str2num`/`isNumeric` treat as potentially numeric.
fn valid_num_char(c: char) -> bool {
    c.is_ascii_digit() || matches!(c, '.' | '-' | 'E' | 'e' | '+' | '%' | '$' | ',')
}

/// Is `n` the BigNaN sentinel?
#[must_use]
pub fn isBad(n: &Big) -> bool {
    n.isNaN()
}

/// The value, or BigNaN for `None`.
#[must_use]
pub fn orBad(opt: Option<Big>) -> Big {
    opt.unwrap_or_else(Big::nan)
}

/// Lenient string → [`Big`]: strips `$`/`,`/leading junk, reads a trailing `%` as
/// divide-by-100, and answers BigNaN for anything else. The Scala original, step for
/// step.
#[must_use]
pub fn str2num(raw: &str) -> Big {
    let trimmed = raw.trim();
    if !trimmed.chars().all(valid_num_char) {
        return Big::nan();
    }
    // ^[^-\.\d]+ — strip leading chars until a sign, dot or digit
    let cleaned: String = trimmed
        .chars()
        .skip_while(|&c| !(c == '-' || c == '.' || c.is_ascii_digit()))
        .filter(|&c| c != '$' && c != ',')
        .collect();
    let normalized = if cleaned.starts_with('.') {
        format!("0{cleaned}")
    } else {
        cleaned
    };
    if normalized.is_empty() || !normalized.chars().all(valid_num_char) {
        return Big::nan();
    }
    let nopct: String = normalized.chars().filter(|&c| c != '%').collect();
    let base = Big::parse(&nopct);
    if base.isNaN() {
        Big::nan()
    } else if nopct != normalized {
        base.div(&Big::parse("100"))
    } else {
        base
    }
}

// ── The four NumPattern matchers, as character scans ─────────────────────────
//
// Each is a full-string match, like Scala's `s match { case NumPattern(..) }`.

type Scan<'a> = std::iter::Peekable<std::str::Chars<'a>>;

/// Consume one char if it satisfies `pred`; whether one was taken.
fn take_one(it: &mut Scan<'_>, pred: impl Fn(char) -> bool) -> bool {
    if it.peek().copied().is_some_and(&pred) {
        it.next();
        true
    } else {
        false
    }
}

/// Consume a run satisfying `pred`; how many were taken.
fn take_while(it: &mut Scan<'_>, pred: impl Fn(char) -> bool) -> usize {
    let mut n = 0;
    while take_one(it, &pred) {
        n += 1;
    }
    n
}

fn num_body(c: char) -> bool {
    c == '.' || c == ',' || c.is_ascii_digit()
}

/// `(?i)([-\(]\s*)?(\d[\.\d,]+)[%\)]?[%KMB]?`
fn pat1(s: &str) -> bool {
    let it = &mut s.chars().peekable();
    if take_one(it, |c| c == '-' || c == '(') {
        take_while(it, char::is_whitespace);
    }
    if !take_one(it, |c| c.is_ascii_digit()) || take_while(it, num_body) == 0 {
        return false;
    }
    take_one(it, |c| c == '%' || c == ')');
    take_one(it, |c| {
        matches!(c.to_ascii_uppercase(), '%' | 'K' | 'M' | 'B')
    });
    it.next().is_none()
}

/// `(-?\s*[\.\d,]+)(E-?\d+)([%\)]?)([%KMB]?)` — case-sensitive, unlike pattern 1
fn pat2(s: &str) -> bool {
    let it = &mut s.chars().peekable();
    take_one(it, |c| c == '-');
    take_while(it, char::is_whitespace);
    if take_while(it, num_body) == 0 || !take_one(it, |c| c == 'E') {
        return false;
    }
    take_one(it, |c| c == '-');
    if take_while(it, |c| c.is_ascii_digit()) == 0 {
        return false;
    }
    take_one(it, |c| c == '%' || c == ')');
    take_one(it, |c| matches!(c, '%' | 'K' | 'M' | 'B'));
    it.next().is_none()
}

/// `-?(\d+)([%KMB]?)` — case-sensitive
fn pat3(s: &str) -> bool {
    let it = &mut s.chars().peekable();
    take_one(it, |c| c == '-');
    if take_while(it, |c| c.is_ascii_digit()) == 0 {
        return false;
    }
    take_one(it, |c| matches!(c, '%' | 'K' | 'M' | 'B'));
    it.next().is_none()
}

/// `-?(\d+)(.?[0-9]*E[-+][0-9]+)?` — the unescaped `.` (any char) ported as-is
fn pat4(s: &str) -> bool {
    let rest = s.strip_prefix('-').unwrap_or(s);
    let digits = rest.chars().take_while(char::is_ascii_digit).count();
    if digits == 0 {
        return false;
    }
    let tail: Vec<char> = rest.chars().skip(digits).collect();
    if tail.is_empty() {
        return true;
    }
    // (.? [0-9]* E [-+] [0-9]+): try with and without the leading any-char
    let try_from = |t: &[char]| -> bool {
        let mut i = 0;
        while i < t.len() && t[i].is_ascii_digit() {
            i += 1;
        }
        if t.get(i) != Some(&'E') {
            return false;
        }
        i += 1;
        if !matches!(t.get(i), Some('-' | '+')) {
            return false;
        }
        i += 1;
        let start = i;
        while i < t.len() && t[i].is_ascii_digit() {
            i += 1;
        }
        i > start && i == t.len()
    };
    try_from(&tail) || try_from(&tail[1..])
}

/// Could this cell be a number? The Scala heuristic, verbatim: all-plain strings try
/// `Double.parseDouble` then all four patterns; strings with other characters
/// (parentheses, suffixes) try patterns 1 and 3 only. More than one `-` disqualifies.
#[must_use]
pub fn isNumeric(col: &str) -> bool {
    let s = col.trim();
    if s.is_empty() {
        return false;
    }
    let nums_and_such: String = s.chars().filter(|&c| valid_num_char(c)).collect();
    if nums_and_such
        .chars()
        .filter(|&c| c == '-' || c == '/')
        .count()
        > 1
    {
        return false;
    }
    if s.len() == nums_and_such.len() {
        s.parse::<f64>().is_ok() || pat1(s) || pat2(s) || pat3(s) || pat4(s)
    } else {
        pat1(s) || pat3(s)
    }
}

// ── Formatting ────────────────────────────────────────────────────────────────

/// Scala's `NumFormat` formatting descriptor.
#[derive(Debug, Clone)]
pub struct NumFormat {
    pub colWidth: i32,
    pub dec: i32,
    pub factor: f64,
    pub abbreviate: bool,
    pub suffix: String,
}

impl Default for NumFormat {
    fn default() -> Self {
        Self {
            colWidth: 9,
            dec: 2,
            factor: 1.0,
            abbreviate: false,
            suffix: String::new(),
        }
    }
}

impl NumFormat {
    /// 9-wide, 2 decimals.
    #[must_use]
    pub fn Default() -> Self {
        Self::default()
    }

    /// Billions/millions collapse to `B`/`M`.
    #[must_use]
    pub fn Abbrev() -> Self {
        Self {
            abbreviate: true,
            ..Self::default()
        }
    }

    /// ×100 with a `%` suffix.
    #[must_use]
    pub fn Percent() -> Self {
        Self {
            factor: 100.0,
            suffix: "%".to_string(),
            ..Self::default()
        }
    }

    /// 3-wide integer percent.
    #[must_use]
    pub fn IntPercent() -> Self {
        Self {
            colWidth: 3,
            dec: 0,
            factor: 100.0,
            suffix: "%".to_string(),
            ..Self::default()
        }
    }
}

/// Java's `String.format("%<width>.<dec>f", v)`: shortest decimal digits of the
/// double, rounded half-up at `dec` decimals, right-justified to `width`.
fn java_format_f(v: f64, width: i32, dec: i32) -> String {
    let body = if v.is_nan() {
        "NaN".to_string()
    } else if v.is_infinite() {
        if v < 0.0 {
            "-Infinity".to_string()
        } else {
            "Infinity".to_string()
        }
    } else {
        // The formatter keeps the double's sign even when the digits round to zero.
        let neg = v < 0.0 || (v == 0.0 && v.is_sign_negative());
        let digits = format!("{}", v.abs()); // shortest round-trip, positional
        let rounded = Big::parse(&digits)
            .setScale(dec, RoundingMode::HalfUp)
            .toPlainString();
        if neg {
            format!("-{rounded}")
        } else {
            rounded
        }
    };
    let pad = width.max(0) as usize;
    if body.len() < pad {
        format!("{}{}", " ".repeat(pad - body.len()), body)
    } else {
        body
    }
}

/// BigNaN-aware column formatting: `N/A` for the sentinel, otherwise
/// `%colWidth.decf` of `xx * factor` (with `B`/`M` abbreviation when asked), the
/// suffix appended, and an exact `-0.00` blanked to ` 0.00` — the Scala, verbatim.
#[must_use]
pub fn numStr(xx: &Big, fmt: &NumFormat) -> String {
    if isBad(xx) {
        return format!("{}N/A", " ".repeat((fmt.colWidth - 3).max(0) as usize));
    }
    let scaled = xx.mul(&Big::from_f64(fmt.factor));
    let raw = if fmt.abbreviate && scaled.compare(&Big::from_f64(1e9)) >= 0 {
        let v = scaled.div(&Big::from_f64(1e9)).toDouble();
        format!("{}B", java_format_f(v, fmt.colWidth - 1, fmt.dec))
    } else if fmt.abbreviate && scaled.compare(&Big::from_f64(1e6)) >= 0 {
        let v = scaled.div(&Big::from_f64(1e6)).toDouble();
        format!("{}M", java_format_f(v, fmt.colWidth - 1, fmt.dec))
    } else {
        java_format_f(scaled.toDouble(), fmt.colWidth, fmt.dec)
    };
    let with_suffix = format!("{raw}{}", fmt.suffix);
    if with_suffix.trim() == "-0.00" {
        with_suffix.replace('-', " ")
    } else {
        with_suffix
    }
}

/// [`numStr`] with the percent format.
#[must_use]
pub fn numStrPct(xx: &Big, fmt: &NumFormat) -> String {
    numStr(xx, fmt)
}

/// [`numStr`] with just decimals and a factor.
#[must_use]
pub fn num2string(xx: &Big, dec: i32, factor: f64) -> String {
    numStr(
        xx,
        &NumFormat {
            dec,
            factor,
            ..NumFormat::default()
        },
    )
}

/// The double, or `NaN` for the sentinel.
#[must_use]
pub fn big2double(xx: &Big) -> f64 {
    if isBad(xx) {
        f64::NAN
    } else {
        xx.toDouble()
    }
}

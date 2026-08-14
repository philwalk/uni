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
//! [`isNumeric`] ports four Scala regexes as character scans. Two authoring accidents
//! in the originals were repaired on the Scala side in 0.16.0 and mirrored here —
//! pattern 3 now takes `K`/`M`/`B` case-insensitively like pattern 1, and pattern 4's
//! decimal point is literal (the unescaped `.` accepted any character, so `12,34E+5`
//! read as numeric). The fixture pins a broad input set so neither side drifts.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name, so a script kept in both \
              languages needs no mental translation. Internal helpers stay snake_case, \
              so the case says whether a Scala counterpart exists."
)]

use crate::udata::Big;
use crate::udata::big::RoundingMode;

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

/// Lenient string → [`Big`]: `$`/`,` are decoration wherever they appear, one
/// trailing `%` divides by 100, and anything else answers BigNaN. The 0.16.0 Scala,
/// step for step (the old leading-junk strip made `"%50"` parse as 50, and the old
/// global %-removal made `"5%5"` parse as 55).
#[must_use]
pub fn str2num(raw: &str) -> Big {
    let trimmed = raw.trim();
    if !trimmed.chars().all(valid_num_char) {
        return Big::nan();
    }
    let cleaned: String = trimmed.chars().filter(|&c| c != '$' && c != ',').collect();
    let normalized = if cleaned.starts_with('.') {
        format!("0{cleaned}")
    } else {
        cleaned
    };
    if normalized.is_empty() {
        return Big::nan();
    }
    let nopct = normalized.strip_suffix('%').unwrap_or(&normalized);
    let base = Big::parse(nopct);
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

/// One definition of "numeric": whatever `str2num` can parse. Delegating keeps
/// the two functions coherent by construction — the previous pattern-set
/// rejected `$1,234.56` while `str2num` (and the CSV loaders' type sniffing)
/// parsed it happily. Mirrors the Scala.
pub fn isNumeric(col: &str) -> bool {
    !isBad(&str2num(col))
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
///
/// Public because a script ported from Scala needs it directly: `f"$x%.6f"` there
/// rounds the *shortest decimal representation* half-up, while Rust's `{:.6}` rounds
/// the *exact binary value* half-to-even. Those disagree whenever the two fall on
/// opposite sides of the boundary — rare per value, but a byte-identical demo pair
/// printing thousands of numbers cannot rely on never hitting one.
///
/// Stays snake_case under the naming contract: it has no `uni` Scala counterpart to
/// mirror, because on that side the behaviour comes from the `f` interpolator, a
/// language feature rather than a library method.
pub fn java_format_f(v: f64, width: i32, dec: i32) -> String {
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
        if neg { format!("-{rounded}") } else { rounded }
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
    // abbreviation is by magnitude (0.16.0): -2.5e9 gets its "B" like +2.5e9 does
    let mag = scaled.abs();
    let raw = if fmt.abbreviate && mag.compare(&Big::from_f64(1e9)) >= 0 {
        let v = scaled.div(&Big::from_f64(1e9)).toDouble();
        format!("{}B", java_format_f(v, fmt.colWidth - 1, fmt.dec))
    } else if fmt.abbreviate && mag.compare(&Big::from_f64(1e6)) >= 0 {
        let v = scaled.div(&Big::from_f64(1e6)).toDouble();
        format!("{}M", java_format_f(v, fmt.colWidth - 1, fmt.dec))
    } else {
        java_format_f(scaled.toDouble(), fmt.colWidth, fmt.dec)
    };
    // ANY all-zero negative rendering blanks its sign, before the suffix (0.16.0):
    // "-0.000" at dec 3 and "-0.00%" no longer keep a minus on a zero
    let unsigned = if is_negative_zero_render(raw.trim()) {
        raw.replace('-', " ")
    } else {
        raw
    };
    format!("{unsigned}{}", fmt.suffix)
}

/// `-0`, `-0.0`, `-0.00`, … — the shapes whose minus sign [`numStr`] blanks.
fn is_negative_zero_render(t: &str) -> bool {
    t == "-0" || (t.len() > 3 && t.starts_with("-0.") && t[3..].bytes().all(|b| b == b'0'))
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
    if isBad(xx) { f64::NAN } else { xx.toDouble() }
}

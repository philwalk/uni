//! Format-autodetecting date parser: a port of `uni.time.SmartParse`.
//!
//! Total by contract: [`parseDateSmart`] returns [`UniDateTime::BAD_DATE`] rather than erroring,
//! which is what makes it usable for ripping through a CSV column without wrapping every cell.
//!
//! # Faithful, including the warts
//!
//! This is a port of behaviour, not of intent, and `test-data/smartparse-parity/` holds the
//! Scala's own answers. Three things a reader might "fix" are deliberate:
//!
//! - **The ISO fast path is dead in both languages.** `parseSmart` dispatches on
//!   `classifyTokens`, which never returns `ISO8601` -- an ISO string travels the YMD token path.
//!   One visible consequence: fractional seconds are dropped (`.123` tokenises as a fourth
//!   number past the three time fields), so `2024-05-12T14:30:00.123` parses with `nano = 0`.
//!   `classify` *does* answer `ISO8601`, from the raw-shape test alone.
//! - **A 2-digit year splits at 30**: 0-30 becomes 2000-2030, 31-99 becomes 1931-1999.
//! - **Month-word fuzzing accepts edit distance 1.** Where a typo is within distance 1 of two
//!   months (`jum`), the Scala answers by `Map` iteration order; this port scans January to
//!   December. The fixture pins only unambiguous typos, on purpose.
//!
//! # Configuration is a parameter here
//!
//! The Scala consults a dynamically-scoped `timeConfig`. Rust has no dynamic scope, so the
//! config is an explicit [`TimeConfig`] argument on the `*With` forms, with [`parseDateSmart`]
//! and [`classify`] fixed at the default (`monthFirst = true`, `order = Auto`).

#![allow(
    non_snake_case,
    reason = "public methods mirror the Scala API name-for-name; internal helpers stay snake_case"
)]

use crate::utime::UniDateTime;

/// Day/month order enforcement. `uni.time.DateOrder`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DateOrder {
    /// Unambiguous input wins; ambiguous input uses `monthFirst`. The default.
    Auto,
    /// Month first, always. A date only readable as day-first yields `BAD_DATE`.
    MonthFirst,
    /// Day first, always. A date only readable as month-first yields `BAD_DATE`.
    DayFirst,
}

/// Parsing configuration. `uni.time.TimeConfig`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TimeConfig {
    /// Which reading wins when a numeric date fits both (`04/05/2001`).
    pub monthFirst: bool,
    /// Whether an unambiguous date may override `monthFirst`.
    pub order: DateOrder,
}

impl Default for TimeConfig {
    fn default() -> Self {
        Self {
            monthFirst: true,
            order: DateOrder::Auto,
        }
    }
}

/// What a string looks like, before committing to a parse. `SmartParse.Shape`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Shape {
    ISO8601,
    YMD,
    MDY,
    DMY,
    /// `Jan 1 2001`
    MonthDayYear,
    /// `1 Jan 2001`
    DayMonthYear,
    /// `MM/DD HH:MM[:SS] YYYY`
    MDYWithTime,
    Unknown,
}

impl Shape {
    /// The Scala enum case name, for the parity fixture.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::ISO8601 => "ISO8601",
            Self::YMD => "YMD",
            Self::MDY => "MDY",
            Self::DMY => "DMY",
            Self::MonthDayYear => "MonthDayYear",
            Self::DayMonthYear => "DayMonthYear",
            Self::MDYWithTime => "MDYWithTime",
            Self::Unknown => "Unknown",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Token {
    Num(i32),
    Word(String),
    AmPm(bool),
}

// ── Public API ────────────────────────────────────────────────────────────────

/// Parses `datestr`, or [`UniDateTime::BAD_DATE`] when it cannot be read. `parseDateSmart`.
#[must_use]
pub fn parseDateSmart(datestr: &str) -> UniDateTime {
    parseDateSmartWith(datestr, TimeConfig::default())
}

/// [`parseDateSmart`] under an explicit configuration.
#[must_use]
pub fn parseDateSmartWith(datestr: &str, cfg: TimeConfig) -> UniDateTime {
    parse_smart(datestr, cfg).unwrap_or(UniDateTime::BAD_DATE)
}

/// Alias for [`parseDateSmart`]: `TimeUtils.parseDate` delegates to it in Scala.
#[must_use]
pub fn parseDate(datestr: &str) -> UniDateTime {
    parseDateSmart(datestr)
}

/// The shape of `s` under the default configuration. `SmartParse.classify`.
#[must_use]
pub fn classify(s: &str) -> Shape {
    classifyWith(s, TimeConfig::default())
}

/// [`classify`] under an explicit configuration.
#[must_use]
pub fn classifyWith(s: &str, cfg: TimeConfig) -> Shape {
    if looks_like_iso8601_raw(s) {
        Shape::ISO8601
    } else {
        classify_tokens(&tokenize(&pre_normalize(s)), cfg)
    }
}

/// Which convention a numeric date *proves*, or `None` when it fits both.
///
/// Deliberately independent of any configuration -- it reports what the string says, not what
/// a configuration would make of it. `SmartParse.numericDateOrder`.
#[must_use]
#[expect(
    clippy::if_same_then_else,
    reason = "the branches mirror the Scala ladder, and each
                                             None means a different thing -- see comments"
)]
pub fn numericDateOrder(s: &str) -> Option<DateOrder> {
    let nums: Vec<i32> = tokenize(&pre_normalize(s))
        .iter()
        .filter_map(|t| {
            if let Token::Num(v) = t {
                Some(*v)
            } else {
                None
            }
        })
        .collect();
    if nums.len() < 3 {
        return None;
    }
    let (a, b, c) = (nums[0], nums[1], nums[2]);
    if is_year(a) {
        None // a leading 4-digit year is ISO and says nothing about day/month order
    } else if !(is_year(c) || is_two_digit_year(c)) {
        None
    } else if is_month(a) && is_day(a) && is_month(b) && is_day(b) {
        None // ambiguous
    } else if is_month(a) && is_day(b) {
        Some(DateOrder::MonthFirst)
    } else if is_day(a) && is_month(b) {
        Some(DateOrder::DayFirst)
    } else {
        None
    }
}

// ── The dispatcher ────────────────────────────────────────────────────────────

fn parse_smart(s: &str, cfg: TimeConfig) -> Option<UniDateTime> {
    let cleaned = pre_normalize(s);
    let tokens = strip_weekday(tokenize(&cleaned));
    match classify_tokens(&tokens, cfg) {
        // Dead in the Scala too: `classifyTokens` never answers ISO8601 (only the public
        // `classify` does, from the raw shape). Kept so the dispatch reads the same.
        Shape::ISO8601 => parse_iso8601(s).or_else(|| parse_ymd(&tokens)),
        Shape::YMD => parse_ymd(&tokens),
        Shape::MDY => parse_mdy(&tokens),
        Shape::DMY => parse_dmy(&tokens),
        Shape::MonthDayYear => parse_month_day_year(&tokens),
        Shape::DayMonthYear => parse_day_month_year(&tokens),
        Shape::MDYWithTime => parse_mdy_with_time(&tokens),
        Shape::Unknown => None,
    }
}

// ── Pre-normalisation ─────────────────────────────────────────────────────────

/// The Scala runs NFKC and then folds every `\p{Z}`/`\s` to a space. `std` has no NFKC, so
/// this maps the pieces the corpus actually meets: every whitespace character to a space, and
/// the fullwidth ASCII block to its ASCII originals. A divergence here would show up as a
/// fixture failure, not a silent one.
fn nfkc_lite(s: &str) -> String {
    s.chars()
        .map(|c| {
            if c.is_whitespace() {
                ' '
            } else if ('\u{FF01}'..='\u{FF5E}').contains(&c) {
                // Fullwidth ASCII variants sit at a fixed offset from ASCII.
                char::from_u32(c as u32 - 0xFEE0).unwrap_or(c)
            } else {
                c
            }
        })
        .collect()
}

fn pre_normalize(s: &str) -> String {
    let ws = nfkc_lite(s);
    let no_paren_tz = strip_paren_timezone(&ws);
    let no_t = split_t_between_digits(&no_paren_tz);
    let expanded = expand_compact_date(&no_t);
    let ordered = time_first_to_last(&expanded);
    let bytes = ordered.as_bytes();
    // `[^A-Za-z0-9]+$` then trim, as in the Scala.
    let mut end = bytes.len();
    while end > 0 && !bytes[end - 1].is_ascii_alphanumeric() {
        end -= 1;
    }
    ordered[..end].trim().to_owned()
}

/// Removes every `(...)` whose body is `[A-Za-z0-9+\-: ]+` -- a parenthesised timezone.
fn strip_paren_timezone(s: &str) -> String {
    let chars: Vec<char> = s.chars().collect();
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '(' {
            let mut j = i + 1;
            while j < chars.len()
                && (chars[j].is_ascii_alphanumeric() || matches!(chars[j], '+' | '-' | ':' | ' '))
            {
                j += 1;
            }
            // At least one body character, and a real close: drop the group.
            if j > i + 1 && j < chars.len() && chars[j] == ')' {
                i = j + 1;
                continue;
            }
        }
        out.push(chars[i]);
        i += 1;
    }
    out
}

/// `(?<=\d)T(?=\d)` -> space: the ISO date/time separator becomes a token boundary.
fn split_t_between_digits(s: &str) -> String {
    let chars: Vec<char> = s.chars().collect();
    let mut out = String::with_capacity(s.len());
    for (i, &c) in chars.iter().enumerate() {
        let is_t_between = c == 'T'
            && i > 0
            && chars[i - 1].is_ascii_digit()
            && chars.get(i + 1).is_some_and(char::is_ascii_digit);
        out.push(if is_t_between { ' ' } else { c });
    }
    out
}

/// Expands a leading `yyyyMMdd[ HHmm[ss]]` into separated form, all or nothing.
///
/// Guarded on every field being plausible -- eight digits are not necessarily a date, and a
/// valid date followed by an implausible time is not this format.
fn expand_compact_date(s: &str) -> String {
    let b = s.as_bytes();
    if b.len() < 8 || !b[..8].iter().all(u8::is_ascii_digit) {
        return s.to_owned();
    }
    if b.get(8).is_some_and(u8::is_ascii_digit) {
        return s.to_owned(); // `(?!\d)`: nine digits are not this format
    }
    let digits = |r: std::ops::Range<usize>| -> i32 { s[r].parse().unwrap_or(-1) };
    let (y, mo, d) = (digits(0..4), digits(4..6), digits(6..8));
    let date_ok = is_year(y) && is_month(mo) && (1..=31).contains(&d);

    // The optional compact time: one or more spaces, then HHmm or HHmmss, then neither a digit
    // nor a colon. When the lookahead fails the group is skipped, as regex backtracking would.
    let mut time: Option<(bool, String)> = None;
    let mut rest_start = 8;
    let mut i = 8;
    while b.get(i) == Some(&b' ') {
        i += 1;
    }
    if i > 8 && b.len() >= i + 4 && b[i..i + 4].iter().all(u8::is_ascii_digit) {
        let with_sec = b.len() >= i + 6 && b[i + 4..i + 6].iter().all(u8::is_ascii_digit);
        let end = if with_sec { i + 6 } else { i + 4 };
        // A six-digit run whose tail fails the lookahead backtracks to the four-digit
        // reading, whose own lookahead then sees a digit and fails too -- so only the exact
        // regex alternatives survive.
        let lookahead_ok = !b.get(end).is_some_and(|&c| c.is_ascii_digit() || c == b':');
        if lookahead_ok {
            let (h, mi_) = (digits(i..i + 2), digits(i + 2..i + 4));
            let sec = with_sec.then(|| digits(i + 4..i + 6));
            let ok = h <= 23 && mi_ <= 59 && sec.is_none_or(|x| x <= 59);
            let text = if with_sec {
                format!("{}:{}:{}", &s[i..i + 2], &s[i + 2..i + 4], &s[i + 4..i + 6])
            } else {
                format!("{}:{}", &s[i..i + 2], &s[i + 2..i + 4])
            };
            time = Some((ok, text));
            rest_start = end;
        }
    }
    if date_ok && time.as_ref().is_none_or(|t| t.0) {
        let time_part = time.map(|t| format!(" {}", t.1)).unwrap_or_default();
        format!(
            "{}-{}-{}{}{}",
            &s[0..4],
            &s[4..6],
            &s[6..8],
            time_part,
            &s[rest_start..]
        )
    } else {
        s.to_owned()
    }
}

/// Moves a leading `H:mm[:ss]` to the end: every shape expects the date first.
fn time_first_to_last(s: &str) -> String {
    let b = s.as_bytes();
    let mut i = 0;
    while i < b.len() && i < 2 && b[i].is_ascii_digit() {
        i += 1;
    }
    if i == 0 || b.get(i) != Some(&b':') {
        return s.to_owned();
    }
    i += 1;
    if !(b.len() >= i + 2 && b[i].is_ascii_digit() && b[i + 1].is_ascii_digit()) {
        return s.to_owned();
    }
    i += 2;
    if b.get(i) == Some(&b':')
        && b.len() >= i + 3
        && b[i + 1].is_ascii_digit()
        && b[i + 2].is_ascii_digit()
    {
        i += 3;
    }
    let time_end = i;
    let mut j = i;
    while b.get(j) == Some(&b' ') {
        j += 1;
    }
    if j == time_end || j >= b.len() {
        return s.to_owned(); // `\s+(.+)$` needs both the gap and a remainder
    }
    format!("{} {}", &s[j..], &s[..time_end])
}

// ── Tokeniser ─────────────────────────────────────────────────────────────────

/// Splits on runs of non-alphanumerics and on every letter/digit boundary.
fn split_tokens(s: &str) -> Vec<String> {
    #[derive(PartialEq, Clone, Copy)]
    enum Kind {
        Letter,
        Digit,
        Other,
    }
    let kind = |c: char| {
        if c.is_ascii_alphabetic() {
            Kind::Letter
        } else if c.is_ascii_digit() {
            Kind::Digit
        } else {
            Kind::Other
        }
    };
    let mut out = Vec::new();
    let mut current = String::new();
    let mut current_kind = Kind::Other;
    for c in s.chars() {
        let k = kind(c);
        if matches!(k, Kind::Other) {
            if !current.is_empty() {
                out.push(std::mem::take(&mut current));
            }
            current_kind = Kind::Other;
        } else if k == current_kind {
            current.push(c);
        } else {
            if !current.is_empty() {
                out.push(std::mem::take(&mut current));
            }
            current.push(c);
            current_kind = k;
        }
    }
    if !current.is_empty() {
        out.push(current);
    }
    out
}

fn tokenize(s: &str) -> Vec<Token> {
    split_tokens(s)
        .into_iter()
        .map(|t| {
            // Runs are homogeneous by construction, so the Scala's mixed-token case
            // cannot arise here.
            if t.bytes().all(|c| c.is_ascii_digit()) {
                // An absurd run of digits overflows to the Scala's `-1`, not an error.
                Token::Num(t.parse().unwrap_or(-1))
            } else {
                match t.to_ascii_lowercase().as_str() {
                    "am" => Token::AmPm(false),
                    "pm" => Token::AmPm(true),
                    _ => Token::Word(t),
                }
            }
        })
        .collect()
}

const WEEKDAY_PREFIXES: [&str; 7] = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];

fn strip_weekday(tokens: Vec<Token>) -> Vec<Token> {
    if let Some(Token::Word(w)) = tokens.first() {
        let lower = w.to_ascii_lowercase();
        let prefix = &lower[..lower.len().min(3)];
        if WEEKDAY_PREFIXES.contains(&prefix) {
            return tokens[1..].to_vec();
        }
    }
    tokens
}

// ── Classifier ────────────────────────────────────────────────────────────────

fn nums_of(tokens: &[Token]) -> Vec<i32> {
    tokens
        .iter()
        .filter_map(|t| {
            if let Token::Num(v) = t {
                Some(*v)
            } else {
                None
            }
        })
        .collect()
}

fn words_of(tokens: &[Token]) -> Vec<&str> {
    tokens
        .iter()
        .filter_map(|t| {
            if let Token::Word(w) = t {
                Some(w.as_str())
            } else {
                None
            }
        })
        .collect()
}

fn ampm_of(tokens: &[Token]) -> Option<bool> {
    tokens.iter().find_map(|t| {
        if let Token::AmPm(p) = t {
            Some(*p)
        } else {
            None
        }
    })
}

#[expect(
    clippy::cognitive_complexity,
    clippy::too_many_lines,
    reason = "a direct port of the Scala decision ladder;
                                                splitting it would hide the correspondence"
)]
fn classify_tokens(tokens: &[Token], cfg: TimeConfig) -> Shape {
    let nums = nums_of(tokens);
    let words = words_of(tokens);

    let head_is_month_word = matches!(tokens.first(), Some(Token::Word(w)) if is_month_word(w));
    let head_is_day_num = matches!(tokens.first(), Some(Token::Num(v)) if is_day(*v));

    // Word-based patterns first, in actual token order.
    if head_is_month_word && nums.len() >= 2 && is_day(nums[0]) {
        let has_valid_year = nums[1..].iter().any(|&n| is_year(n))
            || (nums.len() >= 2 && is_two_digit_year(nums[1]));
        if has_valid_year {
            Shape::MonthDayYear
        } else {
            Shape::Unknown
        }
    } else if head_is_day_num && !words.is_empty() && is_month_word(words[0]) {
        let has_valid_year = nums[1..].iter().any(|&n| is_year(n))
            || (nums.len() >= 2 && is_two_digit_year(nums[1]));
        if has_valid_year {
            Shape::DayMonthYear
        } else {
            Shape::Unknown
        }
    } else if nums.len() >= 3 {
        let (a, b, c) = (nums[0], nums[1], nums[2]);

        // Five or more numbers may be `MM/DD HH:MM[:SS] YYYY`.
        if nums.len() >= 5 && is_month(a) && is_day(b) && (0..=23).contains(&c) {
            let d = nums[3];
            let e = nums[4];
            if nums.len() >= 6 {
                let year = nums[5];
                if (0..=59).contains(&d) && (is_year(year) || is_two_digit_year(year)) {
                    return Shape::MDYWithTime;
                }
            } else if (0..=59).contains(&d) && (is_year(e) || is_two_digit_year(e)) {
                return Shape::MDYWithTime;
            }
        }

        if is_year(a) && is_month(b) && is_day(c) {
            Shape::YMD
        } else if is_month(a)
            && is_day(a)
            && is_month(b)
            && is_day(b)
            && (is_year(c) || is_two_digit_year(c))
        {
            // Both readings valid: the configured order decides.
            if cfg.monthFirst {
                Shape::MDY
            } else {
                Shape::DMY
            }
        } else if is_month(a) && is_day(b) && (is_year(c) || is_two_digit_year(c)) {
            // Only month-first reads. Under an enforced day-first order, refuse it.
            if cfg.order == DateOrder::DayFirst {
                Shape::Unknown
            } else {
                Shape::MDY
            }
        } else if is_day(a) && is_month(b) && (is_year(c) || is_two_digit_year(c)) {
            if cfg.order == DateOrder::MonthFirst {
                Shape::Unknown
            } else {
                Shape::DMY
            }
        } else {
            Shape::Unknown
        }
    } else {
        Shape::Unknown
    }
}

// ── Field helpers ─────────────────────────────────────────────────────────────

const fn is_month(n: i32) -> bool {
    n >= 1 && n <= 12
}
const fn is_day(n: i32) -> bool {
    n >= 1 && n <= 31
}
const fn is_year(n: i32) -> bool {
    n >= 1000 && n <= 9999
}
const fn is_two_digit_year(y: i32) -> bool {
    y >= 0 && y <= 99
}

/// 0-30 becomes 2000-2030; 31-99 becomes 1931-1999.
const fn expand_two_digit_year(y: i32) -> i32 {
    if y >= 0 && y <= 30 {
        2000 + y
    } else if y >= 31 && y <= 99 {
        1900 + y
    } else {
        y
    }
}

/// Month names and abbreviations, in calendar order -- which is also the scan order for the
/// fuzzy match, where the Scala's `Map` iteration order is unspecified.
const MONTH_WORDS: [(&str, i32); 24] = [
    ("jan", 1),
    ("january", 1),
    ("feb", 2),
    ("february", 2),
    ("mar", 3),
    ("march", 3),
    ("apr", 4),
    ("april", 4),
    ("may", 5),
    ("jun", 6),
    ("june", 6),
    ("jul", 7),
    ("july", 7),
    ("aug", 8),
    ("august", 8),
    ("sep", 9),
    ("sept", 9),
    ("september", 9),
    ("oct", 10),
    ("october", 10),
    ("nov", 11),
    ("november", 11),
    ("dec", 12),
    ("december", 12),
];

fn normalize_month_token(w: &str) -> String {
    let lower = w.to_ascii_lowercase();
    let no_comma = lower.strip_suffix(',').unwrap_or(&lower);
    no_comma.strip_suffix('.').unwrap_or(no_comma).to_owned()
}

fn is_month_word(raw: &str) -> bool {
    month_from_word(raw).is_some()
}

/// Exact match, then a prefix of at least three letters, then edit distance <= 1.
///
/// `None` where the Scala throws (and its caller catches into `BadDate`).
fn month_from_word(raw: &str) -> Option<i32> {
    let w = normalize_month_token(raw);
    if let Some(&(_, m)) = MONTH_WORDS.iter().find(|(k, _)| *k == w) {
        return Some(m);
    }
    if w.len() >= 3
        && let Some(&(_, m)) = MONTH_WORDS.iter().find(|(k, _)| k.starts_with(&w))
    {
        return Some(m);
    }
    MONTH_WORDS
        .iter()
        .find(|(k, _)| levenshtein(&w, k) <= 1)
        .map(|&(_, m)| m)
}

fn levenshtein(a: &str, b: &str) -> usize {
    let a: Vec<char> = a.chars().collect();
    let b: Vec<char> = b.chars().collect();
    let mut prev: Vec<usize> = (0..=b.len()).collect();
    for i in 1..=a.len() {
        let mut row = vec![i; b.len() + 1];
        for j in 1..=b.len() {
            let cost = usize::from(a[i - 1] != b[j - 1]);
            row[j] = (prev[j] + 1).min(row[j - 1] + 1).min(prev[j - 1] + cost);
        }
        prev = row;
    }
    prev[b.len()]
}

// ── The ISO fast path ─────────────────────────────────────────────────────────

fn looks_like_iso8601_raw(s: &str) -> bool {
    s.contains('T') && s.matches('-').count() == 2 && s.contains(':')
}

/// A hand-rolled subset of `DateTimeFormatter.ISO_DATE_TIME`: local date-time with optional
/// fraction, offset and `[zone]`, the extras parsed and discarded as `LocalDateTime.parse`
/// does.
///
/// Unreachable from [`parseDateSmart`] -- the dispatcher never answers `ISO8601`, in either
/// language -- but ported so the dead arm it serves reads the same as the Scala's.
#[expect(
    clippy::cognitive_complexity,
    clippy::too_many_lines,
    reason = "one grammar, one function: field-by-field
                                                ISO reading does not decompose usefully"
)]
fn parse_iso8601(raw: &str) -> Option<UniDateTime> {
    let b = raw.as_bytes();
    let digits = |r: std::ops::Range<usize>| -> Option<i32> {
        if r.end <= b.len() && b[r.clone()].iter().all(u8::is_ascii_digit) {
            raw[r].parse().ok()
        } else {
            None
        }
    };
    let year = digits(0..4)?;
    if b.get(4) != Some(&b'-') {
        return None;
    }
    let month = digits(5..7)?;
    if b.get(7) != Some(&b'-') {
        return None;
    }
    let day = digits(8..10)?;
    if b.get(10) != Some(&b'T') {
        return None;
    }
    let hour = digits(11..13)?;
    if b.get(13) != Some(&b':') {
        return None;
    }
    let minute = digits(14..16)?;
    let mut i = 16;
    let mut second = 0;
    let mut nano = 0;
    if b.get(i) == Some(&b':') {
        second = digits(i + 1..i + 3)?;
        i += 3;
        if b.get(i) == Some(&b'.') {
            let start = i + 1;
            let mut j = start;
            while j < b.len() && b[j].is_ascii_digit() {
                j += 1;
            }
            let frac = &raw[start..j];
            if frac.is_empty() || frac.len() > 9 {
                return None;
            }
            nano = format!("{frac:0<9}").parse().ok()?;
            i = j;
        }
    }
    // Offset and zone, accepted and ignored: `Z`, `+HH[:mm[:ss]]`, `-HH[:mm[:ss]]`, `[...]`.
    match b.get(i) {
        None => {}
        Some(&b'Z') => i += 1,
        Some(&(b'+' | b'-')) => {
            digits(i + 1..i + 3)?;
            i += 3;
            if b.get(i) == Some(&b':') {
                digits(i + 1..i + 3)?;
                i += 3;
                if b.get(i) == Some(&b':') {
                    digits(i + 1..i + 3)?;
                    i += 3;
                }
            }
        }
        Some(_) => {}
    }
    if b.get(i) == Some(&b'[') && b.last() == Some(&b']') {
        i = b.len();
    }
    if i != b.len() {
        return None;
    }
    let dt = UniDateTime::ofFull(year, month, day, hour, minute, second, nano, None);
    dt.isValid().then_some(dt)
}

// ── Semantic parsers ──────────────────────────────────────────────────────────

fn parse_ymd(tokens: &[Token]) -> Option<UniDateTime> {
    let nums = nums_of(tokens);
    let ampm = ampm_of(tokens);
    if nums.len() >= 3 && is_year(nums[0]) && is_month(nums[1]) && is_day(nums[2]) {
        Some(build_with_ampm(nums[0], nums[1], nums[2], &nums[3..], ampm))
    } else {
        None
    }
}

fn parse_mdy(tokens: &[Token]) -> Option<UniDateTime> {
    let nums = nums_of(tokens);
    let ampm = ampm_of(tokens);
    if nums.len() >= 3 && is_month(nums[0]) && is_day(nums[1]) {
        let year = if is_year(nums[2]) {
            nums[2]
        } else {
            expand_two_digit_year(nums[2])
        };
        Some(build_with_ampm(year, nums[0], nums[1], &nums[3..], ampm))
    } else {
        None
    }
}

fn parse_dmy(tokens: &[Token]) -> Option<UniDateTime> {
    let nums = nums_of(tokens);
    let ampm = ampm_of(tokens);
    if nums.len() >= 3 && is_day(nums[0]) && is_month(nums[1]) {
        let year = if is_year(nums[2]) {
            nums[2]
        } else {
            expand_two_digit_year(nums[2])
        };
        Some(build_with_ampm(year, nums[1], nums[0], &nums[3..], ampm))
    } else {
        None
    }
}

/// The year and its index, located together, four-digit preferred.
///
/// Searching for the position afterwards -- by value, allowing 2-digit expansion -- matched
/// whichever number happened to expand to the same year: in `MMM d HH:mm:ss yyyy` the day `18`
/// looks like 2018 and the hour `22` looks like 2022, each truncating the time differently.
fn year_and_index(nums: &[i32]) -> Option<(i32, usize)> {
    if let Some(idx) = nums.iter().skip(1).position(|&n| is_year(n)) {
        let i = idx + 1;
        return Some((nums[i], i));
    }
    if nums.len() >= 2 && is_two_digit_year(nums[1]) {
        return Some((expand_two_digit_year(nums[1]), 1));
    }
    None
}

/// The time is whatever numbers remain once the day and the year are accounted for -- not
/// merely what sits between them -- capped at three, and stopped at the first number that
/// cannot be the field it would fill (a trailing `-0700` tokenises as another number).
fn time_nums_around(nums: &[i32], year_idx: usize) -> Vec<i32> {
    nums.iter()
        .enumerate()
        .filter(|&(i, _)| i != 0 && i != year_idx)
        .map(|(_, &n)| n)
        .take(3)
        .enumerate()
        .take_while(|&(i, n)| n >= 0 && n <= if i == 0 { 23 } else { 59 })
        .map(|(_, n)| n)
        .collect()
}

fn parse_month_day_year(tokens: &[Token]) -> Option<UniDateTime> {
    let words = words_of(tokens);
    let nums = nums_of(tokens);
    let ampm = ampm_of(tokens);
    if !words.is_empty() && is_month_word(words[0]) && nums.len() >= 2 && is_day(nums[0]) {
        let (year, year_idx) = year_and_index(&nums)?;
        let month = month_from_word(words[0])?;
        let day = nums[0];
        let time = time_nums_around(&nums, year_idx);
        Some(build_with_ampm(year, month, day, &time, ampm))
    } else {
        None
    }
}

fn parse_day_month_year(tokens: &[Token]) -> Option<UniDateTime> {
    let words = words_of(tokens);
    let nums = nums_of(tokens);
    let ampm = ampm_of(tokens);
    if !nums.is_empty() && is_day(nums[0]) && !words.is_empty() && is_month_word(words[0]) {
        let (year, year_idx) = year_and_index(&nums)?;
        let month = month_from_word(words[0])?;
        let day = nums[0];
        let time = time_nums_around(&nums, year_idx);
        Some(build_with_ampm(year, month, day, &time, ampm))
    } else {
        None
    }
}

fn parse_mdy_with_time(tokens: &[Token]) -> Option<UniDateTime> {
    let nums = nums_of(tokens);
    let ampm = ampm_of(tokens);
    if nums.len() >= 5 {
        let (month, day, hour, minute) = (nums[0], nums[1], nums[2], nums[3]);
        let (second, year) = if nums.len() >= 6 {
            let yr = if is_year(nums[5]) {
                nums[5]
            } else {
                expand_two_digit_year(nums[5])
            };
            (nums[4], yr)
        } else {
            let yr = if is_year(nums[4]) {
                nums[4]
            } else {
                expand_two_digit_year(nums[4])
            };
            (0, yr)
        };
        if is_month(month)
            && is_day(day)
            && (0..=23).contains(&hour)
            && (0..=59).contains(&minute)
            && (0..=59).contains(&second)
        {
            let adjusted = adjust_ampm(hour, ampm);
            Some(UniDateTime::of(year, month, day, adjusted, minute, second))
        } else {
            None
        }
    } else {
        None
    }
}

const fn adjust_ampm(hour: i32, ampm: Option<bool>) -> i32 {
    match ampm {
        Some(true) if hour < 12 => hour + 12, // PM: add 12 unless already 12
        Some(false) if hour == 12 => 0,       // 12 AM is midnight
        _ => hour,
    }
}

/// Assembles the fields; `UniDateTime::of` validates, so February 30th is `BAD_DATE`, not a
/// panic.
fn build_with_ampm(
    year: i32,
    month: i32,
    day: i32,
    rest: &[i32],
    ampm: Option<bool>,
) -> UniDateTime {
    let hour = rest.first().copied().unwrap_or(0);
    let min = rest.get(1).copied().unwrap_or(0);
    let sec = rest.get(2).copied().unwrap_or(0);
    UniDateTime::of(year, month, day, adjust_ampm(hour, ampm), min, sec)
}

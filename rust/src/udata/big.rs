//! Arbitrary-precision decimal with `java.math.BigDecimal` semantics: a port of
//! `uni.data.Big`.
//!
//! # The semantics being mirrored, precisely
//!
//! - **`+`, `-`, `*` are exact and unbounded**, as in Scala: `scala.math.BigDecimal`'s
//!   operators call the exact `java.math.BigDecimal` methods, so a product of two 34-digit
//!   values keeps all 68 digits. Only `/` and `sqrt` round, to `MathContext.DECIMAL128`
//!   (34 significant digits, HALF_EVEN), with Java's preferred-scale and trailing-zero
//!   rules.
//! - **Scale is part of the value's identity for rendering** (`2.50 * 0.25` is `0.6250`)
//!   but not for equality: `==` is `compareTo`-based, as in Scala, so `2.0 == 2.00`.
//! - **The BigNaN sentinel is a value, not a flag** — the 28-digit walking-pattern literal,
//!   recognised by numeric equality. Every operation short-circuits on it, because any
//!   arithmetic would silently destroy it (`uni-sentinel-invariants`).
//! - **`toString` follows `BigDecimal.toString`'s notation rules** — plain unless
//!   `scale < 0` or the adjusted exponent drops below -6 — because the parity fixture
//!   compares renderings, which pins scale as well as numeric value.
//!
//! # Divergences, all documented and all loud
//!
//! `sqrt` of a negative and a negative `pow` exponent are **BigNaN in both languages**
//! as of 0.16.0: Scala used to throw and adopted the port's answer, so the fixture now
//! pins those rows. What remains: Scala throws on some division rounding surprises where
//! this port answers `BigNaN`, and the fixture records no such input.

#![allow(
    non_snake_case,
    reason = "public methods mirror the Scala API name-for-name; internal helpers stay snake_case"
)]

use std::cmp::Ordering;
use std::sync::LazyLock;

// ─── Magnitude: little-endian base-10⁹ limbs ─────────────────────────────────
//
// Base 10⁹ rather than 2⁶⁴ because every hard operation here is decimal: counting
// significant digits, scaling by powers of ten, rounding at a decimal position, and
// string I/O are all limb-local in a decimal base. Schoolbook multiplication is O(n²),
// which is irrelevant at accounting sizes.

const BASE: u64 = 1_000_000_000;
const BASE_DIGITS: usize = 9;

/// Unsigned magnitude. No trailing (most-significant) zero limbs; zero is the empty vec.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Mag(Vec<u32>);

impl Mag {
    const fn zero() -> Self {
        Self(Vec::new())
    }

    fn from_u64(mut v: u64) -> Self {
        let mut limbs = Vec::new();
        while v > 0 {
            limbs.push((v % BASE) as u32);
            v /= BASE;
        }
        Self(limbs)
    }

    fn is_zero(&self) -> bool {
        self.0.is_empty()
    }

    fn trim(mut self) -> Self {
        while self.0.last() == Some(&0) {
            self.0.pop();
        }
        self
    }

    /// Decimal digit count; 0 has one digit by convention (callers guard zero anyway).
    fn digits(&self) -> usize {
        match self.0.last() {
            None => 1,
            Some(&top) => {
                let mut d = (self.0.len() - 1) * BASE_DIGITS;
                let mut t = top;
                while t > 0 {
                    d += 1;
                    t /= 10;
                }
                d
            }
        }
    }

    fn cmp_mag(&self, other: &Self) -> Ordering {
        match self.0.len().cmp(&other.0.len()) {
            Ordering::Equal => {
                for i in (0..self.0.len()).rev() {
                    match self.0[i].cmp(&other.0[i]) {
                        Ordering::Equal => {}
                        ord => return ord,
                    }
                }
                Ordering::Equal
            }
            ord => ord,
        }
    }

    fn add_mag(&self, other: &Self) -> Self {
        let (long, short) = if self.0.len() >= other.0.len() {
            (self, other)
        } else {
            (other, self)
        };
        let mut out = Vec::with_capacity(long.0.len() + 1);
        let mut carry = 0u64;
        for i in 0..long.0.len() {
            let s = u64::from(long.0[i]) + short.0.get(i).map_or(0, |&x| u64::from(x)) + carry;
            out.push((s % BASE) as u32);
            carry = s / BASE;
        }
        if carry > 0 {
            out.push(carry as u32);
        }
        Self(out).trim()
    }

    /// `self - other`, requiring `self >= other`.
    fn sub_mag(&self, other: &Self) -> Self {
        let mut out = Vec::with_capacity(self.0.len());
        let mut borrow = 0i64;
        for i in 0..self.0.len() {
            let mut d = i64::from(self.0[i]) - other.0.get(i).map_or(0, |&x| i64::from(x)) - borrow;
            if d < 0 {
                d += BASE as i64;
                borrow = 1;
            } else {
                borrow = 0;
            }
            out.push(d as u32);
        }
        debug_assert_eq!(borrow, 0, "sub_mag requires self >= other");
        Self(out).trim()
    }

    fn mul_mag(&self, other: &Self) -> Self {
        if self.is_zero() || other.is_zero() {
            return Self::zero();
        }
        let mut out = vec![0u64; self.0.len() + other.0.len()];
        for (i, &a) in self.0.iter().enumerate() {
            let mut carry = 0u64;
            for (j, &b) in other.0.iter().enumerate() {
                let cur = out[i + j] + u64::from(a) * u64::from(b) + carry;
                out[i + j] = cur % BASE;
                carry = cur / BASE;
            }
            let mut k = i + other.0.len();
            while carry > 0 {
                let cur = out[k] + carry;
                out[k] = cur % BASE;
                carry = cur / BASE;
                k += 1;
            }
        }
        Self(out.into_iter().map(|x| x as u32).collect()).trim()
    }

    /// Multiply by 10^k.
    fn mul_pow10(&self, k: usize) -> Self {
        if self.is_zero() || k == 0 {
            return self.clone().trim();
        }
        let limb_shift = k / BASE_DIGITS;
        let digit_shift = k % BASE_DIGITS;
        let mut out = vec![0u32; limb_shift];
        out.extend_from_slice(&self.0);
        let mut m = Self(out);
        if digit_shift > 0 {
            m = m.mul_small(10u64.pow(digit_shift as u32));
        }
        m.trim()
    }

    fn mul_small(&self, f: u64) -> Self {
        debug_assert!(f < BASE * 2, "factor fits the carry arithmetic");
        let mut out = Vec::with_capacity(self.0.len() + 1);
        let mut carry = 0u64;
        for &a in &self.0 {
            let cur = u64::from(a) * f + carry;
            out.push((cur % BASE) as u32);
            carry = cur / BASE;
        }
        while carry > 0 {
            out.push((carry % BASE) as u32);
            carry /= BASE;
        }
        Self(out).trim()
    }

    /// `(self / d, self % d)` for a small divisor.
    fn divrem_small(&self, d: u64) -> (Self, u64) {
        debug_assert!(d > 0 && d < BASE * BASE);
        let mut out = vec![0u32; self.0.len()];
        let mut rem = 0u64;
        for i in (0..self.0.len()).rev() {
            let cur = rem * BASE + u64::from(self.0[i]);
            out[i] = (cur / d) as u32;
            rem = cur % d;
        }
        (Self(out).trim(), rem)
    }

    /// `(self / 10^k, self % 10^k)`.
    fn divrem_pow10(&self, k: usize) -> (Self, Self) {
        if k == 0 {
            return (self.clone(), Self::zero());
        }
        let limb_shift = k / BASE_DIGITS;
        let digit_shift = k % BASE_DIGITS;
        if limb_shift >= self.0.len() && (limb_shift > self.0.len() || digit_shift > 0 || true) {
            // Not enough limbs to shift past: quotient may still be nonzero when
            // limb_shift == len only if digit_shift == 0 handled above; be conservative.
            if limb_shift >= self.0.len() {
                return (Self::zero(), self.clone().trim());
            }
        }
        let rem_low = Self(self.0[..limb_shift].to_vec()).trim();
        let mut q = Self(self.0[limb_shift..].to_vec());
        let mut rem = rem_low;
        if digit_shift > 0 {
            let p = 10u64.pow(digit_shift as u32);
            let (q2, r2) = q.divrem_small(p);
            // r2 becomes the top digits of the remainder.
            rem = Self::from_u64(r2)
                .mul_pow10(limb_shift * BASE_DIGITS)
                .add_mag(&rem);
            q = q2;
        }
        (q.trim(), rem.trim())
    }

    /// Schoolbook long division: `(self / other, self % other)`. `other` must be nonzero.
    fn divrem(&self, other: &Self) -> (Self, Self) {
        debug_assert!(!other.is_zero());
        if self.cmp_mag(other) == Ordering::Less {
            return (Self::zero(), self.clone().trim());
        }
        if other.0.len() == 1 {
            let (q, r) = self.divrem_small(u64::from(other.0[0]));
            return (q, Self::from_u64(r));
        }
        // Digit-at-a-time restoring division in base 10: slow but simple and exactly
        // auditable, which matters more here than speed.
        let digits = self.digits();
        let mut q_digits: Vec<u8> = Vec::with_capacity(digits);
        let mut rem = Self::zero();
        for pos in (0..digits).rev() {
            let (_, digit_mag) = self.digit_at(pos);
            rem = rem
                .mul_small(10)
                .add_mag(&Self::from_u64(u64::from(digit_mag)));
            // Trial digit by binary search over 0..=9.
            let mut d = 0u8;
            for t in (1..=9u8).rev() {
                if other.mul_small(u64::from(t)).cmp_mag(&rem) != Ordering::Greater {
                    d = t;
                    break;
                }
            }
            if d > 0 {
                rem = rem.sub_mag(&other.mul_small(u64::from(d)));
            }
            q_digits.push(d);
        }
        let q_str: String = q_digits.iter().map(|d| char::from(b'0' + d)).collect();
        (Self::from_decimal_str(q_str.trim_start_matches('0')), rem)
    }

    /// The decimal digit at position `pos` (0 = least significant).
    fn digit_at(&self, pos: usize) -> (usize, u32) {
        let limb = pos / BASE_DIGITS;
        let within = pos % BASE_DIGITS;
        let v = self.0.get(limb).copied().unwrap_or(0);
        (pos, (v / 10u32.pow(within as u32)) % 10)
    }

    /// From ASCII digits (possibly with leading zeros); empty or all-zero is zero.
    fn from_decimal_str(s: &str) -> Self {
        let s = s.trim_start_matches('0');
        if s.is_empty() {
            return Self::zero();
        }
        let bytes = s.as_bytes();
        let mut limbs = Vec::with_capacity(bytes.len() / BASE_DIGITS + 1);
        let mut i = bytes.len();
        while i > 0 {
            let start = i.saturating_sub(BASE_DIGITS);
            let chunk = &s[start..i];
            limbs.push(chunk.parse::<u32>().unwrap_or(0));
            i = start;
        }
        Self(limbs).trim()
    }

    fn to_decimal_string(&self) -> String {
        if self.is_zero() {
            return "0".to_owned();
        }
        let mut s = String::with_capacity(self.0.len() * BASE_DIGITS);
        for (i, &limb) in self.0.iter().rev().enumerate() {
            if i == 0 {
                s.push_str(&limb.to_string());
            } else {
                s.push_str(&format!("{limb:09}"));
            }
        }
        s
    }

    /// The value modulo 2⁶⁴, for Java's low-order-bits `longValue`.
    fn low_u64(&self) -> u64 {
        let mut acc = 0u64;
        for &limb in self.0.iter().rev() {
            acc = acc.wrapping_mul(BASE).wrapping_add(u64::from(limb));
        }
        acc
    }
}

// ─── Big ─────────────────────────────────────────────────────────────────────

/// The DECIMAL128 precision: 34 significant digits, HALF_EVEN.
const MC_PRECISION: usize = 34;

/// Rounding modes for [`Big::setScale`], mirroring `scala.math.BigDecimal.RoundingMode`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RoundingMode {
    Up,
    Down,
    Ceiling,
    Floor,
    HalfUp,
    HalfDown,
    HalfEven,
}

impl RoundingMode {
    /// The Scala enum case name, for the parity fixture.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            Self::Up => "UP",
            Self::Down => "DOWN",
            Self::Ceiling => "CEILING",
            Self::Floor => "FLOOR",
            Self::HalfUp => "HALF_UP",
            Self::HalfDown => "HALF_DOWN",
            Self::HalfEven => "HALF_EVEN",
        }
    }
}

/// Arbitrary-precision decimal: `(-1)^neg × coefficient × 10^(-scale)`.
///
/// `uni.data.Big`. Zero keeps its scale (`0.00` renders `0.00`), and is never negative.
#[derive(Debug, Clone)]
pub struct Big {
    neg: bool,
    coeff: Mag,
    scale: i32,
    /// The value's MathContext precision: `max(34, digits at construction)`.
    ///
    /// This is the part of `scala.math.BigDecimal` a port gets wrong by reading the Java
    /// docs instead of probing: construction from a string **never rounds** -- a 35-digit
    /// literal keeps 35 digits and carries `MathContext(35)` -- and every operator applies
    /// the **left operand's** context to the exact Java result. Measured, then encoded.
    prec: usize,
}

/// The sentinel, by its defining literal. See the module docs.
static NAN: LazyLock<Big> = LazyLock::new(|| {
    Big::parse_decimal("-0.000000012345678901234567890123").unwrap_or_else(|| Big::from_i64(0))
});

impl Big {
    // ── Constructors ──────────────────────────────────────────────────────────

    /// The BigNaN sentinel. `Big.BigNaN`.
    #[must_use]
    pub fn nan() -> Self {
        NAN.clone()
    }

    #[must_use]
    pub fn zero() -> Self {
        Self {
            neg: false,
            coeff: Mag::zero(),
            scale: 0,
            prec: MC_PRECISION,
        }
    }

    #[must_use]
    pub fn one() -> Self {
        Self::from_i64(1)
    }

    #[must_use]
    pub fn from_i64(v: i64) -> Self {
        let coeff = Mag::from_u64(v.unsigned_abs());
        let prec = MC_PRECISION.max(coeff.digits());
        Self {
            neg: v < 0,
            coeff,
            scale: 0,
            prec,
        }
    }

    /// `Big(s)`: trims, strips `$` and `,`, honours a trailing `%`, and answers the
    /// sentinel for anything unparseable -- total, like the Scala constructor.
    #[must_use]
    pub fn parse(s: &str) -> Self {
        let t: String = s.trim().chars().filter(|&c| c != ',' && c != '$').collect();
        if let Some(body) = t.strip_suffix('%') {
            match Self::parse_decimal(body) {
                Some(v) => v.div_impl(&Self::from_i64(100)).unwrap_or_else(Self::nan),
                None => Self::nan(),
            }
        } else {
            Self::parse_decimal(&t).unwrap_or_else(Self::nan)
        }
    }

    /// `Big(d)`: through Java's `Double.toString` rendering, so `Big::from_f64(0.1)` is
    /// exactly `0.1` and `1e7` carries the scale Java's scientific notation gives it.
    #[must_use]
    pub fn from_f64(d: f64) -> Self {
        if !d.is_finite() {
            return Self::nan();
        }
        Self::parse_decimal(&java_double_to_string(d)).unwrap_or_else(Self::nan)
    }

    /// The strict `java.math.BigDecimal(String)` grammar: sign, digits, point, exponent.
    fn parse_decimal(s: &str) -> Option<Self> {
        let s = s.trim();
        if s.is_empty() {
            return None;
        }
        let bytes = s.as_bytes();
        let mut i = 0;
        let neg = match bytes[0] {
            b'-' => {
                i = 1;
                true
            }
            b'+' => {
                i = 1;
                false
            }
            _ => false,
        };
        let int_start = i;
        while i < bytes.len() && bytes[i].is_ascii_digit() {
            i += 1;
        }
        let int_part = &s[int_start..i];
        let mut frac_part = "";
        if i < bytes.len() && bytes[i] == b'.' {
            i += 1;
            let frac_start = i;
            while i < bytes.len() && bytes[i].is_ascii_digit() {
                i += 1;
            }
            frac_part = &s[frac_start..i];
        }
        if int_part.is_empty() && frac_part.is_empty() {
            return None;
        }
        let mut exp: i64 = 0;
        if i < bytes.len() && (bytes[i] == b'e' || bytes[i] == b'E') {
            i += 1;
            let exp_start = i;
            if i < bytes.len() && (bytes[i] == b'+' || bytes[i] == b'-') {
                i += 1;
            }
            while i < bytes.len() && bytes[i].is_ascii_digit() {
                i += 1;
            }
            exp = s[exp_start..i].parse().ok()?;
        }
        if i != bytes.len() {
            return None;
        }
        let digits = format!("{int_part}{frac_part}");
        let scale = i64::try_from(frac_part.len()).ok()? - exp;
        let coeff = Mag::from_decimal_str(&digits);
        let scale = i32::try_from(scale).ok()?;
        let prec = MC_PRECISION.max(coeff.digits());
        Some(Self {
            neg: neg && !coeff.is_zero(),
            coeff,
            scale,
            prec,
        })
    }

    // ── The sentinel ──────────────────────────────────────────────────────────

    /// Numeric equality with the sentinel, exactly as Scala's `isBad`/`isNaN`.
    #[must_use]
    pub fn isNaN(&self) -> bool {
        self.numeric_cmp(&NAN) == Ordering::Equal
    }

    #[must_use]
    pub fn isNotNaN(&self) -> bool {
        !self.isNaN()
    }

    // ── Arithmetic (exact, as in Scala) ───────────────────────────────────────

    fn align(&self, other: &Self) -> (Mag, Mag, i32) {
        match self.scale.cmp(&other.scale) {
            Ordering::Equal => (self.coeff.clone(), other.coeff.clone(), self.scale),
            Ordering::Less => {
                let k = (other.scale - self.scale) as usize;
                (self.coeff.mul_pow10(k), other.coeff.clone(), other.scale)
            }
            Ordering::Greater => {
                let k = (self.scale - other.scale) as usize;
                (self.coeff.clone(), other.coeff.mul_pow10(k), self.scale)
            }
        }
    }

    /// Rounds the coefficient to `prec` significant digits, HALF_EVEN, adjusting scale --
    /// what the Java operations-with-MathContext do to their exact result.
    fn round_to_prec(mut self) -> Self {
        let d = self.coeff.digits();
        if self.coeff.is_zero() || d <= self.prec {
            return self;
        }
        let drop = d - self.prec;
        let (q, r) = self.coeff.divrem_pow10(drop);
        let mut q = q;
        if !r.is_zero() {
            let twice = r.mul_small(2);
            let half = Mag::from_u64(1).mul_pow10(drop);
            let round_up = match twice.cmp_mag(&half) {
                Ordering::Greater => true,
                Ordering::Less => false,
                Ordering::Equal => q.0.first().is_some_and(|&x| x % 10 % 2 == 1),
            };
            if round_up {
                q = q.add_mag(&Mag::from_u64(1));
            }
        }
        let mut scale = i64::from(self.scale) - drop as i64;
        // Rounding 999…9 up gains a digit; shed the trailing zero it created.
        if q.digits() > self.prec {
            let (q2, _) = q.divrem_small(10);
            q = q2;
            scale -= 1;
        }
        self.coeff = q;
        self.scale = i32::try_from(scale).unwrap_or(i32::MAX);
        self
    }

    fn add_impl(&self, other: &Self) -> Self {
        let (a, b, scale) = self.align(other);
        let raw = if self.neg == other.neg {
            Self {
                neg: self.neg,
                coeff: a.add_mag(&b),
                scale,
                prec: self.prec,
            }
            .normal_zero()
        } else {
            match a.cmp_mag(&b) {
                Ordering::Equal => Self {
                    neg: false,
                    coeff: Mag::zero(),
                    scale,
                    prec: self.prec,
                },
                Ordering::Greater => Self {
                    neg: self.neg,
                    coeff: a.sub_mag(&b),
                    scale,
                    prec: self.prec,
                },
                Ordering::Less => Self {
                    neg: other.neg,
                    coeff: b.sub_mag(&a),
                    scale,
                    prec: self.prec,
                },
            }
        };
        raw.round_to_prec()
    }

    fn mul_impl(&self, other: &Self) -> Self {
        Self {
            neg: self.neg != other.neg,
            coeff: self.coeff.mul_mag(&other.coeff),
            scale: self.scale + other.scale,
            prec: self.prec,
        }
        .normal_zero()
        .round_to_prec()
    }

    /// Java's `divide(that, mc)` with the left operand's context: quotient rounded
    /// HALF_EVEN to `self.prec` significant digits, then trailing zeros stripped down
    /// toward the preferred scale (`self.scale - other.scale`) when the division was exact.
    #[expect(
        clippy::cognitive_complexity,
        reason = "one division algorithm, one function: the rounding ladder mirrors Java's"
    )]
    fn div_impl(&self, other: &Self) -> Option<Self> {
        if other.coeff.is_zero() {
            return None;
        }
        let preferred = self.scale - other.scale;
        if self.coeff.is_zero() {
            return Some(Self {
                neg: false,
                coeff: Mag::zero(),
                scale: preferred,
                prec: self.prec,
            });
        }
        // Scale the dividend so the integer quotient lands in [10^(prec-1), 10^(prec+1)):
        // with digit counts xd, yd, the quotient of the scaled magnitudes has prec or
        // prec+1 digits by construction.
        let prec = self.prec as i64;
        let xd = self.coeff.digits() as i64;
        let yd = other.coeff.digits() as i64;
        let shift = prec - (xd - yd);
        let mut num = self.coeff.clone();
        let mut den = other.coeff.clone();
        if shift >= 0 {
            num = num.mul_pow10(shift as usize);
        } else {
            den = den.mul_pow10((-shift) as usize);
        }
        let (mut q, mut r) = num.divrem(&den);
        let mut scale = i64::from(self.scale) - i64::from(other.scale) + shift;
        if q.digits() > self.prec {
            // prec+1 digits: fold the dropped digit and the remainder into HALF_EVEN.
            let (q2, dropped) = q.divrem_small(10);
            let round_up = match dropped.cmp(&5) {
                Ordering::Greater => true,
                Ordering::Less => false,
                Ordering::Equal => {
                    if r.is_zero() {
                        q2.0.first().is_some_and(|&d| d % 10 % 2 == 1)
                    } else {
                        true
                    }
                }
            };
            let was_exact = r.is_zero() && dropped == 0;
            q = if round_up {
                q2.add_mag(&Mag::from_u64(1))
            } else {
                q2
            };
            scale -= 1;
            if q.digits() > self.prec {
                let (q3, _) = q.divrem_small(10);
                q = q3;
                scale -= 1;
            }
            if !was_exact {
                r = Mag::from_u64(1); // inexact marker: no stripping below
            }
        } else if !r.is_zero() {
            // Exactly prec digits: HALF_EVEN on the true remainder against the divisor.
            let twice = r.mul_small(2);
            let round_up = match twice.cmp_mag(&den) {
                Ordering::Greater => true,
                Ordering::Less => false,
                Ordering::Equal => q.0.first().is_some_and(|&d| d % 10 % 2 == 1),
            };
            if round_up {
                q = q.add_mag(&Mag::from_u64(1));
                if q.digits() > self.prec {
                    let (q2, _) = q.divrem_small(10);
                    q = q2;
                    scale -= 1;
                }
            }
        }
        // Exact quotient: strip trailing zeros down toward the preferred scale.
        if r.is_zero() {
            while scale > i64::from(preferred) {
                let (q2, rem) = q.divrem_small(10);
                if rem != 0 {
                    break;
                }
                q = q2;
                scale -= 1;
            }
        }
        let scale = i32::try_from(scale).ok()?;
        Some(
            Self {
                neg: self.neg != other.neg,
                coeff: q,
                scale,
                prec: self.prec,
            }
            .normal_zero(),
        )
    }

    /// Zero is never negative, matching `BigDecimal`'s unsigned zero.
    fn normal_zero(mut self) -> Self {
        if self.coeff.is_zero() {
            self.neg = false;
        }
        self
    }

    // ── Guarded public operators ─────────────────────────────────────────────

    fn guarded(&self, other: &Self, f: impl FnOnce() -> Self) -> Self {
        if self.isNaN() || other.isNaN() {
            Self::nan()
        } else {
            f()
        }
    }

    #[must_use]
    pub fn add(&self, other: &Self) -> Self {
        self.guarded(other, || self.add_impl(other))
    }

    #[must_use]
    pub fn sub(&self, other: &Self) -> Self {
        self.guarded(other, || {
            let negated = Self {
                neg: !other.neg,
                ..other.clone()
            }
            .normal_zero();
            self.add_impl(&negated)
        })
    }

    #[must_use]
    pub fn mul(&self, other: &Self) -> Self {
        self.guarded(other, || self.mul_impl(other))
    }

    /// Division: BigNaN for a NaN operand **or a zero divisor**, as in Scala.
    #[must_use]
    pub fn div(&self, other: &Self) -> Self {
        if self.isNaN() || other.isNaN() {
            return Self::nan();
        }
        self.div_impl(other).unwrap_or_else(Self::nan)
    }

    /// Unary negation, sentinel-guarded.
    #[must_use]
    pub fn neg(&self) -> Self {
        if self.isNaN() {
            return Self::nan();
        }
        Self {
            neg: !self.neg,
            ..self.clone()
        }
        .normal_zero()
    }

    #[must_use]
    pub fn abs(&self) -> Self {
        if self.isNaN() {
            return Self::nan();
        }
        Self {
            neg: false,
            ..self.clone()
        }
    }

    #[must_use]
    pub fn signum(&self) -> i32 {
        if self.coeff.is_zero() {
            0
        } else if self.neg {
            -1
        } else {
            1
        }
    }

    /// Rounds to `scale` decimal places, propagating BigNaN -- the guard is essential:
    /// rounding the tiny sentinel to any normal scale would yield an ordinary zero.
    #[must_use]
    pub fn setScale(&self, scale: i32, mode: RoundingMode) -> Self {
        if self.isNaN() {
            return Self::nan();
        }
        match scale.cmp(&self.scale) {
            Ordering::Equal => self.clone(),
            Ordering::Greater => Self {
                neg: self.neg,
                coeff: self.coeff.mul_pow10((scale - self.scale) as usize),
                scale,
                prec: self.prec,
            },
            Ordering::Less => {
                let k = (self.scale - scale) as usize;
                let (q, r) = self.coeff.divrem_pow10(k);
                let coeff = if r.is_zero() {
                    q
                } else {
                    let round_up = match mode {
                        RoundingMode::Up => true,
                        RoundingMode::Down => false,
                        RoundingMode::Ceiling => !self.neg,
                        RoundingMode::Floor => self.neg,
                        RoundingMode::HalfUp | RoundingMode::HalfDown | RoundingMode::HalfEven => {
                            let twice = r.mul_small(2);
                            let half = Mag::from_u64(1).mul_pow10(k);
                            match twice.cmp_mag(&half) {
                                Ordering::Greater => true,
                                Ordering::Less => false,
                                Ordering::Equal => match mode {
                                    RoundingMode::HalfUp => true,
                                    RoundingMode::HalfDown => false,
                                    _ => q.0.first().is_some_and(|&d| d % 2 == 1),
                                },
                            }
                        }
                    };
                    if round_up {
                        q.add_mag(&Mag::from_u64(1))
                    } else {
                        q
                    }
                };
                Self {
                    neg: self.neg,
                    coeff,
                    scale,
                    prec: self.prec,
                }
                .normal_zero()
            }
        }
    }

    /// `java.math.BigDecimal.round(new MathContext(precision, mode))`: the value rounded
    /// to `precision` significant digits. Precision <= 0 means unlimited — the value
    /// returns unchanged — as does a value that already fits. When rounding carries into
    /// an extra digit (`999.9` at precision 3 → `1000`), the exact trailing zero is
    /// dropped just as Java's `doRound` drops it: `1.00E+3`, three digits, scale −1.
    /// NaN propagates, for the same reason it does through [`setScale`](Self::setScale).
    #[must_use]
    pub fn round(&self, precision: i32, mode: RoundingMode) -> Self {
        if self.isNaN() {
            return Self::nan();
        }
        if precision <= 0 {
            return self.clone();
        }
        let digits = self.coeff.digits() as i32;
        if digits <= precision {
            return self.clone();
        }
        let mut r = self.setScale(self.scale - (digits - precision), mode);
        while r.coeff.digits() as i32 > precision {
            let (q, rem) = r.coeff.divrem_pow10(1);
            debug_assert!(rem.is_zero(), "carry digit must be an exact zero");
            r = Self {
                neg: r.neg,
                coeff: q,
                scale: r.scale - 1,
                prec: r.prec,
            };
        }
        r
    }

    /// `sqrt(MathContext.DECIMAL128)`: 34 significant digits, exact results stripped to the
    /// preferred scale `self.scale / 2`. **BigNaN for a negative** — as in Scala since
    /// 0.16.0, which adopted this answer in place of throwing.
    #[must_use]
    pub fn sqrt(&self) -> Self {
        if self.isNaN() || self.neg {
            return Self::nan();
        }
        if self.coeff.is_zero() {
            return Self {
                neg: false,
                coeff: Mag::zero(),
                scale: self.scale / 2,
                prec: self.prec,
            };
        }
        // Scale the coefficient so its digit count is about 2×(34+2) and the adjusted
        // scale is even; the integer square root then has ~35-36 digits.
        let d = self.coeff.digits();
        let target = 2 * (MC_PRECISION + 2);
        let mut k = target.saturating_sub(d);
        if (i64::from(self.scale) + k as i64) % 2 != 0 {
            k += 1;
        }
        let n = self.coeff.mul_pow10(k);
        let s = isqrt(&n);
        let exact = s.mul_mag(&s) == n;
        let mut scale = (i64::from(self.scale) + k as i64) / 2;
        let mut q = s;
        if exact {
            let preferred = i64::from(self.scale) / 2;
            while scale > preferred {
                let (q2, rem) = q.divrem_small(10);
                if rem != 0 {
                    break;
                }
                q = q2;
                scale -= 1;
            }
            // An exact root can still carry more than 34 digits of trailing zeros already
            // stripped; what remains is exact and short.
        } else {
            // Round to 34 significant digits, HALF_EVEN. The remainder direction: q is the
            // floor, so compare the fraction against one half via (2s+1)² vs 4n.
            while q.digits() > MC_PRECISION {
                let (q2, dropped) = q.divrem_small(10);
                let round_up = match dropped.cmp(&5) {
                    Ordering::Greater => true,
                    Ordering::Less => false,
                    // The true value is irrational here, so it cannot sit exactly on the
                    // tie; the floor said 5, meaning the fraction pushes it above half.
                    Ordering::Equal => true,
                };
                q = if round_up {
                    q2.add_mag(&Mag::from_u64(1))
                } else {
                    q2
                };
                scale -= 1;
                if q.digits() > MC_PRECISION {
                    continue;
                }
            }
        }
        let Ok(scale) = i32::try_from(scale) else {
            return Self::nan();
        };
        let prec = MC_PRECISION.max(q.digits());
        Self {
            neg: false,
            coeff: q,
            scale,
            prec,
        }
    }

    /// `~^` with an integer exponent: exact `pow`, as Java's. **BigNaN for a negative
    /// exponent** — as in Scala since 0.16.0, which adopted this answer in place of
    /// throwing `ArithmeticException`.
    #[must_use]
    pub fn pow(&self, exp: i32) -> Self {
        if self.isNaN() || exp < 0 {
            return Self::nan();
        }
        // Java's pow(int) is exact -- no MathContext -- so multiply raw, without the
        // per-operation rounding the arithmetic operators apply.
        let mut coeff = Mag::from_u64(1);
        let mut scale: i64 = 0;
        for _ in 0..exp {
            coeff = coeff.mul_mag(&self.coeff);
            scale += i64::from(self.scale);
        }
        let Ok(scale) = i32::try_from(scale) else {
            return Self::nan();
        };
        let neg = self.neg && exp % 2 == 1;
        let prec = MC_PRECISION.max(coeff.digits());
        Self {
            neg,
            coeff,
            scale,
            prec,
        }
        .normal_zero()
    }

    /// `~^` with a fractional exponent: through `f64::powf`, as the Scala falls back to
    /// `math.pow`, then back through the Java double rendering.
    #[must_use]
    pub fn powf(&self, exp: f64) -> Self {
        if self.isNaN() {
            return Self::nan();
        }
        if exp == exp.trunc() && (0.0..=i32::MAX as f64).contains(&exp) {
            #[expect(
                clippy::cast_possible_truncation,
                reason = "guarded integral and in range"
            )]
            return self.pow(exp as i32);
        }
        let d = self.toDouble().powf(exp);
        if d.is_nan() {
            Self::nan()
        } else {
            Self::from_f64(d)
        }
    }

    // ── Comparisons ──────────────────────────────────────────────────────────

    fn numeric_cmp(&self, other: &Self) -> Ordering {
        match (self.signum(), other.signum()) {
            (a, b) if a != b => a.cmp(&b),
            (0, 0) => Ordering::Equal,
            _ => {
                let (a, b, _) = self.align(other);
                let mag = a.cmp_mag(&b);
                if self.neg { mag.reverse() } else { mag }
            }
        }
    }

    /// `compare`, with the Scala's NaN rule: BigNaN ranks above every number and equal to
    /// itself — as `Double.compare` treats NaN — so sorting, `min` and `max` over a `MatB`
    /// behave exactly as over a `MatD` (NaN last; `min` skips it, `max` returns it).
    #[must_use]
    pub fn compare(&self, other: &Self) -> i32 {
        match (self.isNaN(), other.isNaN()) {
            (true, true) => return 0,
            (true, false) => return 1,
            (false, true) => return -1,
            (false, false) => {}
        }
        match self.numeric_cmp(other) {
            Ordering::Less => -1,
            Ordering::Equal => 0,
            Ordering::Greater => 1,
        }
    }

    #[must_use]
    pub fn max(&self, other: &Self) -> Self {
        if self.isNaN() || other.isNaN() {
            Self::nan()
        } else if self.numeric_cmp(other) == Ordering::Less {
            other.clone()
        } else {
            self.clone()
        }
    }

    #[must_use]
    pub fn min(&self, other: &Self) -> Self {
        if self.isNaN() || other.isNaN() {
            Self::nan()
        } else if self.numeric_cmp(other) == Ordering::Greater {
            other.clone()
        } else {
            self.clone()
        }
    }

    // ── Conversions ──────────────────────────────────────────────────────────

    /// `Double.NaN` for the sentinel, else the correctly-rounded double.
    #[must_use]
    pub fn toDouble(&self) -> f64 {
        if self.isNaN() {
            return f64::NAN;
        }
        // Through the scientific rendering, which Rust's parser rounds correctly.
        let s = format!(
            "{}E{}",
            self.coeff.to_decimal_string(),
            -i64::from(self.scale)
        );
        let v: f64 = s.parse().unwrap_or(f64::NAN);
        if self.neg { -v } else { v }
    }

    #[must_use]
    pub fn toFloat(&self) -> f32 {
        if self.isNaN() {
            return f32::NAN;
        }
        #[expect(
            clippy::cast_possible_truncation,
            reason = "float narrowing is the semantics"
        )]
        {
            self.toDouble() as f32
        }
    }

    /// Java's `intValue`: the fraction dropped, then the **low-order 32 bits** -- a wart,
    /// ported because callers were compiled against it.
    #[must_use]
    pub fn toInt(&self) -> i32 {
        #[expect(
            clippy::cast_possible_truncation,
            reason = "low-order bits are the semantics"
        )]
        {
            self.toLong() as i32
        }
    }

    /// Java's `longValue`: fraction dropped, low-order 64 bits.
    #[must_use]
    pub fn toLong(&self) -> i64 {
        let truncated = if self.scale > 0 {
            let (q, _) = self.coeff.divrem_pow10(self.scale as usize);
            q
        } else if self.scale < 0 {
            self.coeff.mul_pow10((-self.scale) as usize)
        } else {
            self.coeff.clone()
        };
        let low = truncated.low_u64();
        #[expect(
            clippy::cast_possible_wrap,
            reason = "two's-complement wrap is the semantics"
        )]
        let v = low as i64;
        if self.neg { v.wrapping_neg() } else { v }
    }

    #[must_use]
    pub fn isValidInt(&self) -> bool {
        self.is_integral() && {
            let l = self.toLong();
            i32::try_from(l).is_ok() && !self.exceeds_long()
        }
    }

    #[must_use]
    pub fn isValidLong(&self) -> bool {
        self.is_integral() && !self.exceeds_long()
    }

    fn is_integral(&self) -> bool {
        if self.scale <= 0 {
            return true;
        }
        let (_, r) = self.coeff.divrem_pow10(self.scale as usize);
        r.is_zero()
    }

    fn exceeds_long(&self) -> bool {
        // Magnitude with fraction dropped, compared against 2⁶³.
        let truncated = if self.scale > 0 {
            self.coeff.divrem_pow10(self.scale as usize).0
        } else if self.scale < 0 {
            self.coeff.mul_pow10((-self.scale) as usize)
        } else {
            self.coeff.clone()
        };
        let limit = Mag::from_decimal_str(if self.neg {
            "9223372036854775808"
        } else {
            "9223372036854775807"
        });
        truncated.cmp_mag(&limit) == Ordering::Greater
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    /// `BigDecimal.toString`: plain notation unless the scale is negative or the adjusted
    /// exponent drops below -6, then scientific with an explicit sign on the exponent.
    #[must_use]
    pub fn toString(&self) -> String {
        let digits = self.coeff.to_decimal_string();
        let sign = if self.neg { "-" } else { "" };
        if self.scale == 0 {
            return format!("{sign}{digits}");
        }
        let precision = i64::try_from(digits.len()).unwrap_or(i64::MAX);
        let adjusted = precision - 1 - i64::from(self.scale);
        if self.scale > 0 && adjusted >= -6 {
            // Plain.
            let scale = self.scale as usize;
            if digits.len() > scale {
                let point = digits.len() - scale;
                format!("{sign}{}.{}", &digits[..point], &digits[point..])
            } else {
                format!("{sign}0.{}{}", "0".repeat(scale - digits.len()), digits)
            }
        } else {
            // Scientific: one digit, the rest after the point, exponent always signed.
            let head = &digits[..1];
            let tail = &digits[1..];
            let e = if adjusted >= 0 {
                format!("E+{adjusted}")
            } else {
                format!("E{adjusted}")
            };
            if tail.is_empty() {
                format!("{sign}{head}{e}")
            } else {
                format!("{sign}{head}.{tail}{e}")
            }
        }
    }

    /// `toPlainString`: never scientific.
    #[must_use]
    pub fn toPlainString(&self) -> String {
        let digits = self.coeff.to_decimal_string();
        let sign = if self.neg { "-" } else { "" };
        match self.scale.cmp(&0) {
            Ordering::Equal => format!("{sign}{digits}"),
            Ordering::Less => {
                format!("{sign}{digits}{}", "0".repeat((-self.scale) as usize))
            }
            Ordering::Greater => {
                let scale = self.scale as usize;
                if digits.len() > scale {
                    let point = digits.len() - scale;
                    format!("{sign}{}.{}", &digits[..point], &digits[point..])
                } else {
                    format!("{sign}0.{}{}", "0".repeat(scale - digits.len()), digits)
                }
            }
        }
    }
}

// ─── Trait impls ─────────────────────────────────────────────────────────────

impl std::fmt::Display for Big {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.toString())
    }
}

/// Numeric equality, as Scala's: `2.0 == 2.00`, and BigNaN equals BigNaN (it is how the
/// sentinel is recognised, the opposite of the float convention on purpose).
impl PartialEq for Big {
    fn eq(&self, other: &Self) -> bool {
        self.numeric_cmp(other) == Ordering::Equal
    }
}
impl Eq for Big {}

impl std::ops::Add for &Big {
    type Output = Big;
    fn add(self, rhs: &Big) -> Big {
        Big::add(self, rhs)
    }
}
impl std::ops::Sub for &Big {
    type Output = Big;
    fn sub(self, rhs: &Big) -> Big {
        Big::sub(self, rhs)
    }
}
impl std::ops::Mul for &Big {
    type Output = Big;
    fn mul(self, rhs: &Big) -> Big {
        Big::mul(self, rhs)
    }
}
impl std::ops::Div for &Big {
    type Output = Big;
    fn div(self, rhs: &Big) -> Big {
        Big::div(self, rhs)
    }
}
impl std::ops::Neg for &Big {
    type Output = Big;
    fn neg(self) -> Big {
        Big::neg(self)
    }
}

// ─── Java Double.toString ────────────────────────────────────────────────────

/// Java's `Double.toString`: shortest round-trip digits, plain notation with a forced
/// fraction digit for 10⁻³ ≤ |d| < 10⁷, scientific (`D.DDDE±x`, fraction forced) outside.
///
/// Rust's `{:e}` supplies the same shortest digit sequence; only the layout differs, and
/// the layout is what decides the resulting `BigDecimal` scale.
pub(crate) fn java_double_to_string(d: f64) -> String {
    if d == 0.0 {
        return if d.is_sign_negative() {
            "-0.0".to_owned()
        } else {
            "0.0".to_owned()
        };
    }
    let sci = format!("{d:e}"); // e.g. "1.2345e7", "-5e-4"
    let (mantissa, exp_str) = sci.split_once('e').unwrap_or((sci.as_str(), "0"));
    let exp: i64 = exp_str.parse().unwrap_or(0);
    let (sign, mantissa) = match mantissa.strip_prefix('-') {
        Some(m) => ("-", m),
        None => ("", mantissa),
    };
    let digits: String = mantissa.chars().filter(char::is_ascii_digit).collect();
    if (-3..7).contains(&exp) {
        // Plain: place the point after (exp + 1) digits.
        let point = exp + 1;
        if point <= 0 {
            format!("{sign}0.{}{}", "0".repeat((-point) as usize), digits)
        } else if (point as usize) >= digits.len() {
            format!(
                "{sign}{}{}.0",
                digits,
                "0".repeat(point as usize - digits.len())
            )
        } else {
            format!(
                "{sign}{}.{}",
                &digits[..point as usize],
                &digits[point as usize..]
            )
        }
    } else {
        let head = &digits[..1];
        let tail = if digits.len() > 1 { &digits[1..] } else { "0" };
        format!("{sign}{head}.{tail}E{exp}")
    }
}

/// Integer square root by Newton's method on the magnitude.
fn isqrt(n: &Mag) -> Mag {
    if n.is_zero() {
        return Mag::zero();
    }
    // Initial guess: 10^(ceil(digits/2)), always >= the true root.
    let mut x = Mag::from_u64(1).mul_pow10(n.digits().div_ceil(2));
    loop {
        // x' = (x + n/x) / 2
        let (nx, _) = n.divrem(&x);
        let sum = x.add_mag(&nx);
        let (next, _) = sum.divrem_small(2);
        if next.cmp_mag(&x) != Ordering::Less {
            return x;
        }
        x = next;
    }
}

// ── std::ops overloads: the OWNED and MIXED ownership combos ────────────────
//
// The `&Big op &Big` forms above predate these; without the owned combos,
// `a + b` only compiled as `&a + &b`, which is not how the Scala twin reads.
// All forward to the inherent methods, so the BigNaN-absorbing semantics ride
// along unchanged. Comparison operators are deliberately NOT provided:
// `PartialOrd` would have to answer `None` for BigNaN, and the sentinel's
// equality-recognised semantics (BigNaN == BigNaN) disagree with IEEE — the
// explicit `compare` keeps that decision visible.
macro_rules! forward_binop_owned {
    ($trait:ident, $method:ident) => {
        impl core::ops::$trait<Big> for &Big {
            type Output = Big;
            fn $method(self, rhs: Big) -> Big {
                Big::$method(self, &rhs)
            }
        }
        impl core::ops::$trait<&Big> for Big {
            type Output = Big;
            fn $method(self, rhs: &Big) -> Big {
                Big::$method(&self, rhs)
            }
        }
        impl core::ops::$trait<Big> for Big {
            type Output = Big;
            fn $method(self, rhs: Big) -> Big {
                Big::$method(&self, &rhs)
            }
        }
    };
}
forward_binop_owned!(Add, add);
forward_binop_owned!(Sub, sub);
forward_binop_owned!(Mul, mul);
forward_binop_owned!(Div, div);

impl core::ops::Neg for Big {
    type Output = Big;
    fn neg(self) -> Big {
        Big::neg(&self)
    }
}

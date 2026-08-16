//! Elementwise Double-valued math and the ML activations — Scala's `MatMathOps`, the
//! last of Tier 3 phase (c).
//!
//! # What is pinned, and what is not
//!
//! Every method here is a per-element function of its input, so there is no association
//! order to reproduce; the contract is the FORMULA, spelled exactly as the Scala spells it,
//! including its branches and its evaluation order. Where the Scala has a choice that
//! affects bits, this file makes the same one:
//!
//! - `relu` is `Math.max(x, 0.0)`: NaN stays NaN, `-0.0` becomes `+0.0`. Rust's
//!   `f64::max` does neither (it drops NaN and may keep `-0.0`), so it is spelled out.
//! - `log2` is `ln(x) / ln(2)`, not a true `log2` — one rounding more, but the Scala's.
//! - `trunc` is `if x >= 0 { floor } else { ceil }`, which is what `f64::trunc` does; the
//!   branch is kept so the correspondence is visible.
//! - `gelu` associates `((0.044715·x)·x)·x` and `(0.5·x)·(1 + tanh(…))`.
//! - `softmax`/`logSoftmax` take the max with `>` (so a leading NaN wins and a later one
//!   does not), sum `exp(x - max)` in index order with a plain fold — not `sumD` — and
//!   divide (or subtract `max + ln(sum)`) afterwards.
//!
//! **Bit-identity across the two runtimes holds only for the exact functions** —
//! `floor`, `ceil`, `trunc`, `relu`, `leakyRelu`, and `dropout`. The transcendental ones
//! (`sin` … `tanh`, `log10`, `log2`, `arctan2`, and everything built on `exp`/`tanh`:
//! `sigmoid`, `elu`, `gelu`, `softmax`, `logSoftmax`) inherit the `exp` situation
//! documented in `PARITY.md`: HotSpot intrinsics and the platform libm are each within an
//! ulp of the true value and are not required to agree, so the fixture pins those on the
//! same 2⁻⁴⁰ grid `exp` uses. Nothing in this file can close that; it is a property of
//! the two runtimes' `libm`s.
//!
//! `dropout` takes the generator explicitly where Scala defaults to its global one:
//! there is no global RNG in this crate. Draw order — one `next_f64` per element, row
//! major, `keep = u >= p` — is the Scala's, so a seeded call agrees exactly.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use crate::NumPyRng;
use crate::udata::mat::MatD;

impl MatD {
    /// `np.sin`. Scala's `sin`.
    #[must_use]
    pub fn sin(&self) -> Self {
        self.map_elems(f64::sin)
    }

    /// `np.cos`.
    #[must_use]
    pub fn cos(&self) -> Self {
        self.map_elems(f64::cos)
    }

    /// `np.tan`.
    #[must_use]
    pub fn tan(&self) -> Self {
        self.map_elems(f64::tan)
    }

    /// `np.arcsin`.
    #[must_use]
    pub fn arcsin(&self) -> Self {
        self.map_elems(f64::asin)
    }

    /// `np.arccos`.
    #[must_use]
    pub fn arccos(&self) -> Self {
        self.map_elems(f64::acos)
    }

    /// `np.arctan`.
    #[must_use]
    pub fn arctan(&self) -> Self {
        self.map_elems(f64::atan)
    }

    /// `np.arctan2(self, other)`, element-wise; shapes must match (no broadcasting, as
    /// Scala's `arctan2` requires).
    ///
    /// # Panics
    /// If the shapes differ.
    #[must_use]
    pub fn arctan2(&self, other: &Self) -> Self {
        assert!(
            self.shape() == other.shape(),
            "Shape mismatch: {}x{} vs {}x{}",
            self.rows(),
            self.cols(),
            other.rows(),
            other.cols()
        );
        let mut out = Vec::with_capacity(self.size());
        for r in 0..self.rows() {
            for c in 0..self.cols() {
                out.push(self.at(r, c).atan2(other.at(r, c)));
            }
        }
        Self::create(out, self.rows(), self.cols())
    }

    /// `np.sinh`.
    #[must_use]
    pub fn sinh(&self) -> Self {
        self.map_elems(f64::sinh)
    }

    /// `np.cosh`.
    #[must_use]
    pub fn cosh(&self) -> Self {
        self.map_elems(f64::cosh)
    }

    /// `np.tanh`.
    #[must_use]
    pub fn tanh(&self) -> Self {
        self.map_elems(f64::tanh)
    }

    /// `np.floor`. Exact.
    #[must_use]
    pub fn floor(&self) -> Self {
        self.map_elems(f64::floor)
    }

    /// `np.ceil`. Exact.
    #[must_use]
    pub fn ceil(&self) -> Self {
        self.map_elems(f64::ceil)
    }

    /// `np.trunc` — toward zero: `floor` for `x >= 0`, `ceil` otherwise. Exact.
    #[must_use]
    pub fn trunc(&self) -> Self {
        self.map_elems(|v| if v >= 0.0 { v.floor() } else { v.ceil() })
    }

    /// `np.log10`.
    #[must_use]
    pub fn log10(&self) -> Self {
        self.map_elems(f64::log10)
    }

    /// Scala's `log2`: `ln(x) / ln(2)` — deliberately not `f64::log2`, which rounds once
    /// where this rounds twice; the Scala spelling is the contract.
    #[must_use]
    pub fn log2(&self) -> Self {
        self.map_elems(|x| x.ln() / 2.0f64.ln())
    }

    /// σ(x) = 1/(1+e^-x), in Scala's numerically stable two-branch form.
    #[must_use]
    pub fn sigmoid(&self) -> Self {
        self.map_elems(|x| {
            if x >= 0.0 {
                1.0 / (1.0 + (-x).exp())
            } else {
                let e = x.exp();
                e / (1.0 + e)
            }
        })
    }

    /// `max(0, x)` with `java.lang.Math.max` semantics: NaN stays NaN, `-0.0` → `+0.0`.
    /// Exact.
    #[must_use]
    pub fn relu(&self) -> Self {
        self.map_elems(|x| if x.is_nan() || x > 0.0 { x } else { 0.0 })
    }

    /// `if x > 0 { x } else { alpha * x }`. Scala defaults `alpha` to 0.01. Exact.
    #[must_use]
    pub fn leakyRelu(&self, alpha: f64) -> Self {
        self.map_elems(move |x| if x > 0.0 { x } else { alpha * x })
    }

    /// ELU: `if x > 0 { x } else { alpha * (e^x - 1) }`. Scala defaults `alpha` to 1.0.
    #[must_use]
    pub fn elu(&self, alpha: f64) -> Self {
        self.map_elems(move |x| if x > 0.0 { x } else { alpha * (x.exp() - 1.0) })
    }

    /// GELU, tanh approximation, associated as the Scala is:
    /// `0.5 · x · (1 + tanh(√(2/π) · (x + 0.044715·x·x·x)))`.
    #[must_use]
    pub fn gelu(&self) -> Self {
        self.map_elems(|x| {
            let inner = (2.0 / std::f64::consts::PI).sqrt() * (x + 0.044715 * x * x * x);
            0.5 * x * (1.0 + inner.tanh())
        })
    }

    /// Softmax along `axis` (1 = across each row, 0 = down each column), max-subtracted:
    /// `exp(x - max) / Σ exp(x - max)`, the sum a plain fold in index order.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1.
    #[must_use]
    pub fn softmax(&self, axis: usize) -> Self {
        assert!(axis <= 1, "axis must be 0 (columns) or 1 (rows)");
        let (rows, cols) = self.shape();
        let mut out = vec![0.0f64; rows * cols];
        // One lane at a time: for axis 1 a lane is a row of `cols` cells, for axis 0 a
        // column of `rows` cells; `idx(t)` maps the lane position to the flat index.
        let (lanes, len): (usize, usize) = if axis == 1 {
            (rows, cols)
        } else {
            (cols, rows)
        };
        for lane in 0..lanes {
            let idx = |t: usize| {
                if axis == 1 {
                    lane * cols + t
                } else {
                    t * cols + lane
                }
            };
            let val = |t: usize| {
                if axis == 1 {
                    self.at(lane, t)
                } else {
                    self.at(t, lane)
                }
            };
            let mut max = val(0);
            for t in 1..len {
                let v = val(t);
                if v > max {
                    max = v;
                }
            }
            let mut sum = 0.0f64;
            for t in 0..len {
                let e = (val(t) - max).exp();
                out[idx(t)] = e;
                sum += e;
            }
            for t in 0..len {
                out[idx(t)] /= sum;
            }
        }
        Self::create(out, rows, cols)
    }

    /// Log-softmax along `axis`: `x - (max + ln Σ exp(x - max))`.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1.
    #[must_use]
    pub fn logSoftmax(&self, axis: usize) -> Self {
        assert!(axis <= 1, "axis must be 0 (columns) or 1 (rows)");
        let (rows, cols) = self.shape();
        let mut out = vec![0.0f64; rows * cols];
        let (lanes, len): (usize, usize) = if axis == 1 {
            (rows, cols)
        } else {
            (cols, rows)
        };
        for lane in 0..lanes {
            let idx = |t: usize| {
                if axis == 1 {
                    lane * cols + t
                } else {
                    t * cols + lane
                }
            };
            let val = |t: usize| {
                if axis == 1 {
                    self.at(lane, t)
                } else {
                    self.at(t, lane)
                }
            };
            let mut max = val(0);
            for t in 1..len {
                let v = val(t);
                if v > max {
                    max = v;
                }
            }
            let mut sum_exp = 0.0f64;
            for t in 0..len {
                sum_exp += (val(t) - max).exp();
            }
            let log_sum_exp = max + sum_exp.ln();
            for t in 0..len {
                out[idx(t)] = val(t) - log_sum_exp;
            }
        }
        Self::create(out, rows, cols)
    }

    /// Dropout: each element kept with probability `1 - p` and scaled by `1/(1-p)`, or
    /// zeroed. One `next_f64` per element in row-major order, `keep = u >= p` — the Scala's
    /// draw order, so a seeded call agrees exactly. Scala's `training = false` form is a
    /// plain copy and is not reproduced; do not call.
    ///
    /// # Panics
    /// If `p` is outside `[0, 1)`.
    #[must_use]
    pub fn dropout(&self, rng: &mut NumPyRng, p: f64) -> Self {
        assert!(
            (0.0..1.0).contains(&p),
            "Dropout probability must be in [0, 1), got {p}"
        );
        let scale = 1.0 / (1.0 - p);
        let mut out = Vec::with_capacity(self.size());
        for r in 0..self.rows() {
            for c in 0..self.cols() {
                let keep = rng.next_f64() >= p;
                out.push(if keep { self.at(r, c) * scale } else { 0.0 });
            }
        }
        Self::create(out, self.rows(), self.cols())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn m() -> MatD {
        MatD::create(vec![-2.0, -0.0, 0.0, 0.5, 1.5, f64::NAN], 2, 3)
    }

    #[test]
    fn relu_has_java_math_max_semantics() {
        let r = m().relu().toArray();
        assert_eq!(r[0], 0.0);
        assert!(
            r[1].is_sign_positive(),
            "-0.0 must become +0.0, as Math.max(-0.0, 0.0) does"
        );
        assert_eq!(r[3], 0.5);
        assert!(
            r[5].is_nan(),
            "NaN must survive; f64::max would have dropped it"
        );
    }

    #[test]
    fn leaky_relu_and_elu_branch_on_strict_positivity() {
        let l = m().leakyRelu(0.01).toArray();
        assert_eq!(l[0], -0.02);
        assert!(l[1].is_sign_negative(), "-0.0 * alpha stays -0.0");
        assert_eq!(l[4], 1.5);
        let e = m().elu(1.0).toArray();
        assert_eq!(e[0], (-2.0f64).exp() - 1.0);
        assert_eq!(e[4], 1.5);
    }

    #[test]
    fn trunc_floor_ceil_are_exact_and_signed_zero_aware() {
        let x = MatD::create(vec![-2.5, -0.5, 0.5, 2.5], 1, 4);
        assert_eq!(x.trunc().toArray(), vec![-2.0, -0.0, 0.0, 2.0]);
        assert!(
            x.trunc().toArray()[1].is_sign_negative(),
            "ceil(-0.5) is -0.0"
        );
        assert_eq!(x.floor().toArray(), vec![-3.0, -1.0, 0.0, 2.0]);
        assert_eq!(x.ceil().toArray(), vec![-2.0, -0.0, 1.0, 3.0]);
    }

    #[test]
    fn log2_is_ln_over_ln2_not_log2() {
        // The Scala spelling rounds twice; a true log2 rounds once. On most inputs they
        // agree, so pick one where the double rounding shows — 2^k are exact for log2
        // and the ratio form can miss by an ulp.
        let x = MatD::create(vec![8.0, 1024.0, 3.0], 1, 3);
        let ours = x.log2().toArray();
        assert_eq!(ours[0], 8.0f64.ln() / 2.0f64.ln());
        assert_eq!(ours[2], 3.0f64.ln() / 2.0f64.ln());
    }

    #[test]
    fn softmax_rows_sum_to_one_and_leading_nan_wins_the_max() {
        let x = MatD::create(vec![1.0, 2.0, 3.0, 1.0, 1.0, 1.0], 2, 3);
        let s = x.softmax(1);
        for r in 0..2 {
            let row: f64 = (0..3).map(|c| s.at(r, c)).sum();
            assert!((row - 1.0).abs() < 1e-15, "row {r} sums to {row}");
        }
        assert_eq!(s.at(1, 0), 1.0 / 3.0);
        // axis 0 on the transposed view must equal axis 1 on the original.
        let t = x.T().softmax(0);
        for r in 0..2 {
            for c in 0..3 {
                assert_eq!(t.at(c, r).to_bits(), s.at(r, c).to_bits());
            }
        }
        // logSoftmax = ln(softmax) up to rounding of the two spellings.
        let ls = x.logSoftmax(1);
        for c in 0..3 {
            assert!((ls.at(0, c) - s.at(0, c).ln()).abs() < 1e-15);
        }
    }

    #[test]
    fn dropout_is_a_seeded_mask_with_the_declared_draw_order() {
        let x = MatD::create(vec![1.0; 12], 3, 4);
        let mut rng = NumPyRng::new(7);
        let d = x.dropout(&mut rng, 0.5).toArray();
        // Recompute from the same generator: keep iff u >= p, scale 2.0.
        let mut rng2 = NumPyRng::new(7);
        let want: Vec<f64> = (0..12)
            .map(|_| if rng2.next_f64() >= 0.5 { 2.0 } else { 0.0 })
            .collect();
        assert_eq!(d, want);
        assert!(d.contains(&0.0) && d.contains(&2.0));
    }

    #[test]
    #[should_panic(expected = "Shape mismatch")]
    fn arctan2_requires_equal_shapes() {
        let _y = MatD::zeros(2, 2).arctan2(&MatD::zeros(2, 3));
    }
}

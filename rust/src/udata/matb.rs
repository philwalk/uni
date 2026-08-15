//! `MatB` — Scala's `Mat[Boolean]`, and the mask family that produces and consumes it.
//!
//! # Why a whole type rather than `&[bool]`
//!
//! A flat slice would carry a mask, but nothing else Scala does with one: `&&`/`||`/`!`
//! combine masks, `all`/`any` reduce them along an axis back into masks, and `where`
//! selects between two matrices with one. Every operation needs the SHAPE, so the shape
//! belongs in the type.
//!
//! # IEEE, not the Ordering
//!
//! `gt`/`lt`/`gte`/`lte`/`eqTo`/`neTo` are IEEE-754 comparisons: **every comparison
//! involving NaN is false**, and `-0.0 == 0.0`. That is NumPy's `>` and, since 0.16.1,
//! Scala's — the Scala family previously went through `Ordering[Double]`, which is
//! `TotalOrdering`, ranking NaN above every number and `-0.0` below `0.0`. Those remain
//! the semantics of `min`/`max`/`sort`/`argmax`, which are order statistics; a mask is
//! not one. `MatNaNSuite` pins the Scala side and `mat_parity` the agreement.
//!
//! # Storage
//!
//! Contiguous `Vec<bool>`, with no view model: a mask is always freshly produced by a
//! comparison, never a strided window onto another mask. The MatD it came from may be any
//! layout — the comparison reads through the stride equation and materialises.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use std::ops::BitAnd;
use std::ops::BitOr;
use std::ops::Not;

use crate::udata::mat::MatD;

/// A `rows`×`cols` matrix of booleans — Scala's `Mat[Boolean]`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MatB {
    data: Vec<bool>,
    rows: usize,
    cols: usize,
}

impl MatB {
    /// Wrap row-major `data`.
    ///
    /// # Panics
    /// If `data.len()` is not `rows * cols`.
    #[must_use]
    pub fn create(data: Vec<bool>, rows: usize, cols: usize) -> Self {
        assert!(
            data.len() == rows * cols,
            "data length {} does not match {rows}x{cols}",
            data.len()
        );
        Self { data, rows, cols }
    }

    /// A `rows`×`cols` mask with every element `value`.
    #[must_use]
    pub fn filled(rows: usize, cols: usize, value: bool) -> Self {
        Self {
            data: vec![value; rows * cols],
            rows,
            cols,
        }
    }

    #[must_use]
    pub fn rows(&self) -> usize {
        self.rows
    }

    #[must_use]
    pub fn cols(&self) -> usize {
        self.cols
    }

    #[must_use]
    pub fn shape(&self) -> (usize, usize) {
        (self.rows, self.cols)
    }

    #[must_use]
    pub fn size(&self) -> usize {
        self.data.len()
    }

    #[must_use]
    pub fn isEmpty(&self) -> bool {
        self.rows == 0 || self.cols == 0
    }

    /// Element at `(r, c)`.
    #[must_use]
    pub fn at(&self, r: usize, c: usize) -> bool {
        self.data[r * self.cols + c]
    }

    /// Row-major copy of the elements.
    #[must_use]
    pub fn toArray(&self) -> Vec<bool> {
        self.data.clone()
    }

    /// How many elements are true — Scala reaches this by summing the `Mat[Boolean]`.
    #[must_use]
    pub fn count(&self) -> usize {
        self.data.iter().filter(|&&b| b).count()
    }

    /// True when every element is. Vacuously true when empty, as `np.all` is.
    #[must_use]
    pub fn all(&self) -> bool {
        self.data.iter().all(|&b| b)
    }

    /// True when any element is. False when empty, as `np.any` is.
    #[must_use]
    pub fn any(&self) -> bool {
        self.data.iter().any(|&b| b)
    }

    /// `np.all(axis=…)`: axis 0 reduces down the rows to a 1×cols mask, axis 1 across the
    /// columns to a rows×1 one — the same orientation as the numeric axis reductions.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1.
    #[must_use]
    pub fn allAxis(&self, axis: usize) -> Self {
        self.reduceAxis(axis, true)
    }

    /// `np.any(axis=…)`. See [`MatB::allAxis`] for the orientation.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1.
    #[must_use]
    pub fn anyAxis(&self, axis: usize) -> Self {
        self.reduceAxis(axis, false)
    }

    /// The shared body of `allAxis`/`anyAxis`: `all` seeds true and clears on a false
    /// element, `any` seeds false and sets on a true one.
    fn reduceAxis(&self, axis: usize, isAll: bool) -> Self {
        assert!(axis <= 1, "axis must be 0 or 1, got {axis}");
        let n = if axis == 0 { self.cols } else { self.rows };
        let mut out = vec![isAll; n];
        for r in 0..self.rows {
            for c in 0..self.cols {
                let slot = if axis == 0 { c } else { r };
                if isAll {
                    out[slot] &= self.at(r, c);
                } else {
                    out[slot] |= self.at(r, c);
                }
            }
        }
        if axis == 0 {
            Self::create(out, 1, n)
        } else {
            Self::create(out, n, 1)
        }
    }

    /// Scala's `Mat.where(cond, x, y)`: element-wise pick from `x` where true, `y` where
    /// false. Named `whereMat` because `where` is a Rust keyword.
    ///
    /// # Panics
    /// If either source does not match this mask's shape.
    #[must_use]
    pub fn whereMat(&self, x: &MatD, y: &MatD) -> MatD {
        assert!(
            x.shape() == self.shape() && y.shape() == self.shape(),
            "where: shapes {:?} / {:?} must match the mask {:?}",
            x.shape(),
            y.shape(),
            self.shape()
        );
        let mut out = Vec::with_capacity(self.size());
        for r in 0..self.rows {
            for c in 0..self.cols {
                out.push(if self.at(r, c) {
                    x.at(r, c)
                } else {
                    y.at(r, c)
                });
            }
        }
        MatD::create(out, self.rows, self.cols)
    }

    /// Scala's scalar `Mat.where(cond, x, y)` overload.
    #[must_use]
    pub fn whereScalar(&self, x: f64, y: f64) -> MatD {
        let out: Vec<f64> = self.data.iter().map(|&b| if b { x } else { y }).collect();
        MatD::create(out, self.rows, self.cols)
    }

    /// Element-wise combine, shapes required to match — the body behind `&` and `|`.
    ///
    /// # Panics
    /// If the shapes differ. Scala's `zipMap` requires the same, and broadcasting a mask
    /// is deliberately not offered: NumPy allows it, Scala does not, and silently
    /// stretching a mask is the kind of divergence that shows up as wrong answers rather
    /// than as an error.
    fn zipWith(&self, other: &Self, op: impl Fn(bool, bool) -> bool) -> Self {
        assert!(
            self.shape() == other.shape(),
            "mask shapes {:?} and {:?} must match",
            self.shape(),
            other.shape()
        );
        let data = self
            .data
            .iter()
            .zip(other.data.iter())
            .map(|(&a, &b)| op(a, b))
            .collect();
        Self {
            data,
            rows: self.rows,
            cols: self.cols,
        }
    }
}

/// Scala's `&&`. Rust cannot overload `&&`, which is short-circuiting and boolean-only.
impl BitAnd<&MatB> for &MatB {
    type Output = MatB;
    fn bitand(self, rhs: &MatB) -> MatB {
        self.zipWith(rhs, |a, b| a && b)
    }
}

/// Scala's `||`.
impl BitOr<&MatB> for &MatB {
    type Output = MatB;
    fn bitor(self, rhs: &MatB) -> MatB {
        self.zipWith(rhs, |a, b| a || b)
    }
}

/// Scala's `unary_!`.
impl Not for &MatB {
    type Output = MatB;
    fn not(self) -> MatB {
        MatB {
            data: self.data.iter().map(|&b| !b).collect(),
            rows: self.rows,
            cols: self.cols,
        }
    }
}

impl MatD {
    /// Element-wise comparison producing a mask, reading through the stride equation so a
    /// view compares its own elements rather than its parent's.
    fn cmp(&self, op: impl Fn(f64) -> bool) -> MatB {
        let mut out = Vec::with_capacity(self.size());
        for r in 0..self.rows() {
            for c in 0..self.cols() {
                out.push(op(self.at(r, c)));
            }
        }
        MatB::create(out, self.rows(), self.cols())
    }

    /// `m > other` — IEEE, so false at every NaN. Scala's `gt`.
    #[must_use]
    pub fn gt(&self, other: f64) -> MatB {
        self.cmp(|x| x > other)
    }

    /// `m < other`. Scala's `lt`.
    #[must_use]
    pub fn lt(&self, other: f64) -> MatB {
        self.cmp(|x| x < other)
    }

    /// `m >= other`. Scala's `gte`.
    #[must_use]
    pub fn gte(&self, other: f64) -> MatB {
        self.cmp(|x| x >= other)
    }

    /// `m <= other`. Scala's `lte`.
    #[must_use]
    pub fn lte(&self, other: f64) -> MatB {
        self.cmp(|x| x <= other)
    }

    /// `m == other` — false everywhere when `other` is NaN, and true for `-0.0 == 0.0`.
    /// Scala's `:==`, spelled out because `:==` is not a Rust identifier.
    #[must_use]
    pub fn eqTo(&self, other: f64) -> MatB {
        self.cmp(|x| x == other)
    }

    /// `m != other`. Scala's `:!=`.
    #[must_use]
    pub fn neTo(&self, other: f64) -> MatB {
        self.cmp(|x| x != other)
    }

    /// `np.isnan`. Scala's `isnan`.
    #[must_use]
    pub fn isnan(&self) -> MatB {
        self.cmp(f64::is_nan)
    }

    /// `np.isinf`. Scala's `isinf`.
    #[must_use]
    pub fn isinf(&self) -> MatB {
        self.cmp(f64::is_infinite)
    }

    /// `np.isfinite`. Scala's `isfinite`.
    #[must_use]
    pub fn isfinite(&self) -> MatB {
        self.cmp(f64::is_finite)
    }

    /// `m(mask)` — the elements where the mask is true, as a 1×k row.
    ///
    /// Row-major order and the 1×k shape both follow Scala's `apply(mask)`. NumPy returns
    /// a 1-D array from the same expression, which prints differently but holds the same
    /// elements in the same order.
    ///
    /// # Panics
    /// If the mask shape does not match.
    #[must_use]
    pub fn applyMask(&self, mask: &MatB) -> MatD {
        assert!(
            mask.shape() == self.shape(),
            "mask shape {:?} must match matrix shape {:?}",
            mask.shape(),
            self.shape()
        );
        let mut out = Vec::with_capacity(mask.count());
        for r in 0..self.rows() {
            for c in 0..self.cols() {
                if mask.at(r, c) {
                    out.push(self.at(r, c));
                }
            }
        }
        let n = out.len();
        MatD::create(out, 1, n)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn comparisons_are_ieee_not_total_order() {
        // The whole point of the type: `Ordering[Double]` would rank NaN above 0.0 and
        // -0.0 below 0.0. NumPy does neither, and neither does this.
        let m = MatD::create(vec![1.0, f64::NAN, -1.0], 1, 3);
        assert_eq!(m.gt(0.0).toArray(), vec![true, false, false]);
        assert_eq!(m.lt(0.0).toArray(), vec![false, false, true]);
        assert_eq!(m.gte(0.0).toArray(), vec![true, false, false]);
        assert_eq!(m.lte(0.0).toArray(), vec![false, false, true]);

        let z = MatD::create(vec![-0.0, 0.0], 1, 2);
        assert_eq!(z.eqTo(0.0).toArray(), vec![true, true]);
        assert_eq!(z.lt(0.0).toArray(), vec![false, false]);

        // NaN is not equal to itself, and IS unequal to itself.
        let n = MatD::create(vec![f64::NAN], 1, 1);
        assert_eq!(n.eqTo(f64::NAN).toArray(), vec![false]);
        assert_eq!(n.neTo(f64::NAN).toArray(), vec![true]);
    }

    #[test]
    fn a_view_compares_its_own_elements() {
        // The stride equation matters here: a transposed view must not compare the
        // parent's buffer in parent order.
        let m = MatD::create((1..=6).map(f64::from).collect(), 2, 3);
        let t = m.T();
        assert_eq!(t.shape(), (3, 2));
        assert_eq!(
            t.gt(3.0).toArray(),
            vec![false, true, false, true, false, true]
        );
    }

    #[test]
    fn logical_ops_combine_masks() {
        let m = MatD::create(vec![1.0, 2.0, 3.0, 4.0], 1, 4);
        let big = m.gt(2.0); // F F T T
        let odd = &m.eqTo(1.0) | &m.eqTo(3.0); // T F T F
        assert_eq!((&big & &odd).toArray(), vec![false, false, true, false]);
        assert_eq!((&big | &odd).toArray(), vec![true, false, true, true]);
        assert_eq!((!&big).toArray(), vec![true, true, false, false]);
    }

    #[test]
    fn axis_reductions_keep_the_numeric_orientation() {
        // [[T, F], [T, T]]
        let b = MatB::create(vec![true, false, true, true], 2, 2);
        assert_eq!(b.allAxis(0).toArray(), vec![true, false]);
        assert_eq!(b.allAxis(0).shape(), (1, 2));
        assert_eq!(b.anyAxis(0).toArray(), vec![true, true]);
        assert_eq!(b.allAxis(1).toArray(), vec![false, true]);
        assert_eq!(b.allAxis(1).shape(), (2, 1));
        assert_eq!(b.anyAxis(1).toArray(), vec![true, true]);
        assert!(b.any());
        assert!(!b.all());
        assert_eq!(b.count(), 3);
    }

    #[test]
    fn mask_indexing_flattens_row_major() {
        let m = MatD::create((1..=6).map(f64::from).collect(), 2, 3);
        assert_eq!(m.applyMask(&m.gt(2.0)).toArray(), vec![3.0, 4.0, 5.0, 6.0]);
        assert_eq!(m.applyMask(&m.gt(2.0)).shape(), (1, 4));
        // An all-false mask yields an empty 1x0, not a panic.
        assert_eq!(m.applyMask(&m.gt(99.0)).shape(), (1, 0));
    }

    #[test]
    fn where_selects_element_wise() {
        let m = MatD::create(vec![1.0, 2.0, 3.0, 4.0], 2, 2);
        let neg = &m * -1.0;
        assert_eq!(
            m.gt(2.0).whereMat(&m, &neg).toArray(),
            vec![-1.0, -2.0, 3.0, 4.0]
        );
        assert_eq!(
            m.gt(2.0).whereScalar(1.0, -1.0).toArray(),
            vec![-1.0, -1.0, 1.0, 1.0]
        );
    }

    #[test]
    #[should_panic(expected = "must match")]
    fn combining_different_shapes_is_an_error() {
        let a = MatB::filled(2, 2, true);
        let b = MatB::filled(2, 3, true);
        let _ = &a & &b;
    }
}

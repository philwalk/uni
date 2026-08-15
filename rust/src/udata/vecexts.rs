//! `CVecD` and `RVecD` — the port of Scala's `uni.data.VecExts` at `Double`.
//!
//! # What these are
//!
//! On the Scala side `CVec[T]` and `RVec[T]` are *opaque types with an upper bound*:
//!
//! ```scala
//! opaque type CVec[T] <: Mat[T] = Mat[T]    // column vector (n×1)
//! opaque type RVec[T] <: Mat[T] = Mat[T]    // row vector    (1×n)
//! ```
//!
//! They carry no data of their own. Their entire job is to make orientation part of the
//! *type*, so `v.T` is known to be a row rather than merely hoped to be, while the `<:
//! Mat[T]` bound lets a vector widen to a matrix with no conversion at the call site.
//!
//! The Rust analog is a newtype plus [`std::ops::Deref`]: every `MatD` method is
//! reachable through the wrapper, `&CVecD` coerces to `&MatD` where a matrix is wanted,
//! and the orientation-preserving operators below shadow the `MatD` ones so arithmetic
//! on a vector stays a vector. That is as close as Rust gets to the bound; it is not
//! identical, since `Deref` does not participate in generic bounds.
//!
//! # The one thing worth knowing
//!
//! [`CVecD::fromMat`] and [`RVecD::fromMat`] **transpose** a wrongly-oriented argument
//! rather than copying it, exactly as Scala's `if m.cols == 1 then m else
//! Mat.matTransposeOf(m)` does. The result is therefore a transposed *view*, and a
//! transposed view sums by a different association order than a contiguous matrix — see
//! the layout note in `mat.rs`. This is faithful, not incidental: a `CVec.fromMat` of a
//! row vector has the same last-ulp behaviour in both languages.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use std::ops::Add;
use std::ops::Deref;
use std::ops::Div;
use std::ops::Index;
use std::ops::Mul;
use std::ops::Neg;
use std::ops::Sub;

use crate::udata::mat::MatD;

/// A column vector — Scala's `CVec[Double]`, an `n`×1 matrix whose orientation is part
/// of its type.
#[derive(Clone, Debug, PartialEq)]
pub struct CVecD(MatD);

/// A row vector — Scala's `RVec[Double]`, a 1×`n` matrix whose orientation is part of
/// its type.
#[derive(Clone, Debug, PartialEq)]
pub struct RVecD(MatD);

impl CVecD {
    /// Scala's `CVec(elems*)` / `CVec.fromArray(arr)`: an `n`×1 holding a copy.
    pub fn apply(elems: &[f64]) -> Self {
        Self::fromArray(elems)
    }

    /// Scala's `CVec.fromArray` — copies, so the caller keeps its slice.
    pub fn fromArray(arr: &[f64]) -> Self {
        Self(MatD::create(arr.to_vec(), arr.len(), 1))
    }

    /// Scala's `CVec.zeros(n)`.
    pub fn zeros(n: usize) -> Self {
        Self(MatD::zeros(n, 1))
    }

    /// Scala's `CVec.ones(n)`.
    pub fn ones(n: usize) -> Self {
        Self(MatD::ones(n, 1))
    }

    /// Scala's `CVec.fromMat`: accepts either orientation, **transposing** a row vector
    /// into a column rather than copying it. See the module note — the result of that
    /// branch is a view, with the summation consequences a view carries.
    ///
    /// # Panics
    /// If the matrix is not vector-shaped, mirroring Scala's `require`.
    pub fn fromMat(m: MatD) -> Self {
        assert!(
            m.cols() == 1 || m.rows() == 1,
            "fromMat requires a vector-shaped matrix, got {:?}",
            m.shape()
        );
        if m.cols() == 1 {
            Self(m)
        } else {
            Self(m.transpose())
        }
    }

    /// Widen to the underlying matrix — Scala's `asMat`, which there is the `<: Mat[T]`
    /// bound doing nothing at runtime.
    pub fn asMat(&self) -> &MatD {
        &self.0
    }

    /// Scala's `toMat` — hands over the matrix by value.
    pub fn toMat(self) -> MatD {
        self.0
    }

    /// Scala's `cv.T` — the row vector with the same elements, as a transposed view.
    pub fn T(&self) -> RVecD {
        RVecD::fromMat(self.0.transpose())
    }

    /// Scala's `toRowVec` — alias for [`CVecD::T`].
    pub fn toRowVec(&self) -> RVecD {
        self.T()
    }
}

impl RVecD {
    /// Scala's `RVec(elems*)` / `RVec.fromArray(arr)`: a 1×`n` holding a copy.
    pub fn apply(elems: &[f64]) -> Self {
        Self::fromArray(elems)
    }

    /// Scala's `RVec.fromArray` — copies, so the caller keeps its slice.
    pub fn fromArray(arr: &[f64]) -> Self {
        Self(MatD::create(arr.to_vec(), 1, arr.len()))
    }

    /// Scala's `RVec.zeros(n)`.
    pub fn zeros(n: usize) -> Self {
        Self(MatD::zeros(1, n))
    }

    /// Scala's `RVec.ones(n)`.
    pub fn ones(n: usize) -> Self {
        Self(MatD::ones(1, n))
    }

    /// Scala's `RVec.fromMat`: accepts either orientation, transposing a column vector
    /// into a row. See the module note.
    ///
    /// # Panics
    /// If the matrix is not vector-shaped, mirroring Scala's `require`.
    pub fn fromMat(m: MatD) -> Self {
        assert!(
            m.rows() == 1 || m.cols() == 1,
            "fromMat requires a vector-shaped matrix, got {:?}",
            m.shape()
        );
        if m.rows() == 1 {
            Self(m)
        } else {
            Self(m.transpose())
        }
    }

    /// Widen to the underlying matrix — Scala's `asMat`.
    pub fn asMat(&self) -> &MatD {
        &self.0
    }

    /// Scala's `toMat` — hands over the matrix by value.
    pub fn toMat(self) -> MatD {
        self.0
    }

    /// Scala's `rv.T` — the column vector with the same elements, as a transposed view.
    pub fn T(&self) -> CVecD {
        CVecD::fromMat(self.0.transpose())
    }

    /// Scala's `toColVec` — alias for [`RVecD::T`].
    pub fn toColVec(&self) -> CVecD {
        self.T()
    }
}

/// Stands in for Scala's `CVec[T] <: Mat[T]` bound: every `MatD` method — `sum`, `mean`,
/// `norm`, `toArray`, `shape` … — is reachable on a `CVecD` without restating it here.
impl Deref for CVecD {
    type Target = MatD;

    fn deref(&self) -> &MatD {
        &self.0
    }
}

/// Stands in for Scala's `RVec[T] <: Mat[T]` bound. See [`CVecD`]'s note.
impl Deref for RVecD {
    type Target = MatD;

    fn deref(&self) -> &MatD {
        &self.0
    }
}

/// `cv[i]` — Scala's `cv(i)`, i.e. `at(i, 0)`. Rust sugar; only scalar reads can use
/// bracket syntax.
impl Index<usize> for CVecD {
    type Output = f64;

    fn index(&self, i: usize) -> &f64 {
        &self.0[(i, 0)]
    }
}

/// `rv[j]` — Scala's `rv(j)`, i.e. `at(0, j)`.
impl Index<usize> for RVecD {
    type Output = f64;

    fn index(&self, j: usize) -> &f64 {
        &self.0[(0, j)]
    }
}

impl From<CVecD> for MatD {
    fn from(v: CVecD) -> Self {
        v.0
    }
}

impl From<RVecD> for MatD {
    fn from(v: RVecD) -> Self {
        v.0
    }
}

/// Orientation-preserving arithmetic. Scala spells these out per vector type for the
/// same reason — so that `cv * 2.0` stays a `CVec` instead of widening to `Mat` — and
/// routes each through `asMat` then back through `fromMat`, which is what this does.
macro_rules! vec_binops {
    ($vec:ident) => {
        impl Add<f64> for &$vec {
            type Output = $vec;
            fn add(self, rhs: f64) -> $vec {
                $vec::fromMat(self.asMat() + rhs)
            }
        }
        impl Sub<f64> for &$vec {
            type Output = $vec;
            fn sub(self, rhs: f64) -> $vec {
                $vec::fromMat(self.asMat() - rhs)
            }
        }
        impl Mul<f64> for &$vec {
            type Output = $vec;
            fn mul(self, rhs: f64) -> $vec {
                $vec::fromMat(self.asMat() * rhs)
            }
        }
        impl Div<f64> for &$vec {
            type Output = $vec;
            fn div(self, rhs: f64) -> $vec {
                $vec::fromMat(self.asMat() / rhs)
            }
        }
        impl Add<&$vec> for &$vec {
            type Output = $vec;
            fn add(self, rhs: &$vec) -> $vec {
                $vec::fromMat(self.asMat() + rhs.asMat())
            }
        }
        impl Sub<&$vec> for &$vec {
            type Output = $vec;
            fn sub(self, rhs: &$vec) -> $vec {
                $vec::fromMat(self.asMat() - rhs.asMat())
            }
        }
        impl Mul<&$vec> for &$vec {
            type Output = $vec;
            fn mul(self, rhs: &$vec) -> $vec {
                $vec::fromMat(self.asMat() * rhs.asMat())
            }
        }
        impl Div<&$vec> for &$vec {
            type Output = $vec;
            fn div(self, rhs: &$vec) -> $vec {
                $vec::fromMat(self.asMat() / rhs.asMat())
            }
        }
        impl Neg for &$vec {
            type Output = $vec;
            fn neg(self) -> $vec {
                $vec::fromMat(-self.asMat())
            }
        }
    };
}

vec_binops!(CVecD);
vec_binops!(RVecD);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_column_vector_is_n_by_one() {
        let v = CVecD::apply(&[1.0, 2.0, 3.0]);
        assert_eq!(v.shape(), (3, 1));
        assert_eq!(v[1], 2.0);
        assert_eq!(v.sum(), 6.0, "MatD methods are reachable through Deref");
    }

    #[test]
    fn a_row_vector_is_one_by_n() {
        let v = RVecD::apply(&[1.0, 2.0, 3.0]);
        assert_eq!(v.shape(), (1, 3));
        assert_eq!(v[2], 3.0);
    }

    #[test]
    fn transposing_swaps_the_orientation_and_the_type() {
        let c = CVecD::apply(&[1.0, 2.0, 3.0]);
        let r: RVecD = c.T();
        assert_eq!(r.shape(), (1, 3));
        assert_eq!(r.T(), c);
    }

    #[test]
    fn from_mat_transposes_rather_than_copying() {
        // Scala's `fromMat` reorients with matTransposeOf, so the result is a VIEW —
        // which matters, because a view sums by a different association order.
        let row = MatD::create(vec![1.0, 2.0, 3.0], 1, 3);
        let c = CVecD::fromMat(row);
        assert_eq!(c.shape(), (3, 1));
        assert!(c.transposed(), "reorientation must stay a transposed view");
    }

    #[test]
    fn arithmetic_keeps_the_orientation() {
        let c = CVecD::apply(&[1.0, 2.0]);
        let doubled: CVecD = &c * 2.0;
        assert_eq!(doubled.toArray(), vec![2.0, 4.0]);
        assert_eq!(doubled.shape(), (2, 1));
        let summed: CVecD = &c + &c;
        assert_eq!(summed.toArray(), vec![2.0, 4.0]);
        assert_eq!((-&c).toArray(), vec![-1.0, -2.0]);
    }

    #[test]
    fn norm_reaches_through_deref() {
        assert_eq!(CVecD::apply(&[3.0, 4.0]).norm(), 5.0);
        assert_eq!(RVecD::apply(&[3.0, 4.0]).norm(), 5.0);
    }
}

//! `MatB` — Scala's `Mat[Big]`: the exact-decimal matrix, on the same view model as
//! [`MatD`] (`Mat<T>` shares the descriptor, views, slicing, transpose, stacking and
//! gathers; this file adds the numerics for `T = Big`).
//!
//! # What is pinned
//!
//! On the Scala side `Mat[Big]` is the generic `Mat[T]` with `Fractional[Big]`: no fast
//! path, no chunked `sumD` — every reduction is the plain sequential fold and every
//! elementwise op the guarded `Big` operator, in row-major order. This file reproduces
//! those folds exactly, so results agree with the JVM to the last decimal digit
//! (`toString` and `toPlainString` alike, since [`Big`] carries Java's `MathContext`
//! and scale rules). In particular:
//!
//! - `sum`, `mean`, `std`, `variance`, `norm`, `cumsum`, the axis family and `matmul`
//!   accumulate from `Big(0)` with `Fractional[Big].plus`, so a `BigNaN` anywhere makes
//!   its result `BigNaN`, and rounding is the accumulator's context (34 digits, HALF_EVEN)
//!   at every addition. `mean(axis)` is `sum(axis) / n` per lane; `std(axis)` is the
//!   whole-matrix `std` of each lane.
//! - `power(n)` is `n` guarded multiplications from `Big(1)` (rounding each step), NOT
//!   the exact `Big::pow`; `abs` is `if x < 0 { -x } else { x }` under the `Ordering`.
//! - `min`/`max`/`argmin`/`argmax` use `Big::compare`, under which `BigNaN` ranks
//!   highest (as `Double.compare` ranks NaN): `min` skips it, `max` returns it, ties keep
//!   the first occurrence. The ordering masks `gt lt gte lte` are false against `BigNaN`
//!   (the guarded operators), while `eqTo`/`neTo` recognise it — as in Scala.
//! - `inverse`/`determinant`/`solve` are the generic LU with the pivot chosen by
//!   `|toDouble|`, elimination in exact `Big` arithmetic — Scala's non-Double branch.
//!
//! `svd`, `eig`, `cholesky`, `lstsq`, `pinv` and `matrixRank` are Double-only in Scala
//! (`UnsupportedOperationException`) and simply do not exist on `MatB`.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use std::cmp::Ordering;
use std::fmt;

use ndarray::Array2;

use crate::Error;
use crate::udata::big::Big;
use crate::udata::mat::Mat;
use crate::udata::mat::MatD;
use crate::udata::matbool::MatBool;

/// Scala's `MatB` — `Mat[Big]`.
pub type MatB = Mat<Big>;

fn big_i(n: usize) -> Big {
    Big::from_i64(i64::try_from(n).unwrap_or(i64::MAX))
}

impl MatB {
    // ── Construction ───────────────────────────────────────────────────────────

    /// Scala's `MatB(arr)`: an `n`×1 column holding a copy of `arr`.
    #[must_use]
    pub fn apply(arr: &[Big]) -> Self {
        Self::create(arr.to_vec(), arr.len(), 1)
    }

    /// `MatB.fromSeq`: an `n`×1 column.
    #[must_use]
    pub fn fromSeq(values: &[Big]) -> Self {
        Self::apply(values)
    }

    /// `MatB.col(values*)`: an `n`×1 column.
    #[must_use]
    pub fn col(values: &[Big]) -> Self {
        Self::apply(values)
    }

    /// `MatB.row(values*)`: a 1×`n` row.
    #[must_use]
    pub fn row(values: &[Big]) -> Self {
        Self::create(values.to_vec(), 1, values.len())
    }

    /// An `rows`×`cols` matrix of `Big(0)`.
    #[must_use]
    pub fn zeros(rows: usize, cols: usize) -> Self {
        Self::filled(rows, cols, Big::zero())
    }

    /// An `rows`×`cols` matrix of `Big(1)`.
    #[must_use]
    pub fn ones(rows: usize, cols: usize) -> Self {
        Self::filled(rows, cols, Big::one())
    }

    /// The `n`×`n` identity.
    #[must_use]
    pub fn eye(n: usize) -> Self {
        let mut d = vec![Big::zero(); n * n];
        for i in 0..n {
            d[i * n + i] = Big::one();
        }
        Self::create(d, n, n)
    }

    /// Parses every cell with [`Big::parse`] — the shape of a fixture or a doc example
    /// (`MatB::parseRows(&[&["1.5", "2.25"], &["-3", "0.125"]])`).
    ///
    /// # Panics
    /// If the rows are ragged.
    #[must_use]
    pub fn parseRows(rows: &[&[&str]]) -> Self {
        let cols = rows.first().map_or(0, |r| r.len());
        assert!(
            rows.iter().all(|r| r.len() == cols),
            "parseRows: ragged rows"
        );
        let data: Vec<Big> = rows
            .iter()
            .flat_map(|r| r.iter().map(|s| Big::parse(s)))
            .collect();
        Self::create(data, rows.len(), cols)
    }

    /// From the loaders' `Array2<Big>` (`loadMatBig`, `readCsvB`, `loadSmartBig`).
    #[must_use]
    pub fn fromArray2(a: &Array2<Big>) -> Self {
        let (r, c) = a.dim();
        Self::create(a.iter().cloned().collect(), r, c)
    }

    /// `m.map(_.toDouble)`: `BigNaN` → `f64::NAN`.
    #[must_use]
    pub fn toMatD(&self) -> MatD {
        let (r, c) = self.shape();
        MatD::create(self.flatten().iter().map(Big::toDouble).collect(), r, c)
    }

    /// `m.map(Big(_))` through `MatElem[Big].fromDouble`: NaN and ±∞ → `BigNaN`, else
    /// Java's `Double.toString` digits.
    #[must_use]
    pub fn fromMatD(m: &MatD) -> Self {
        let (r, c) = m.shape();
        Self::create(m.flatten().into_iter().map(Big::from_f64).collect(), r, c)
    }

    // ── Elementwise ────────────────────────────────────────────────────────────

    /// A fresh contiguous matrix, `f(r, c)` per cell in row-major order.
    fn tabulate(rows: usize, cols: usize, f: impl Fn(usize, usize) -> Big) -> Self {
        let mut out = Vec::with_capacity(rows * cols);
        for r in 0..rows {
            for c in 0..cols {
                out.push(f(r, c));
            }
        }
        Self::create(out, rows, cols)
    }

    /// `f` per element, in row-major order — Scala's `map` on `Mat[Big]`.
    #[must_use]
    pub fn map(&self, f: impl Fn(&Big) -> Big) -> Self {
        let (rows, cols) = self.shape();
        Self::tabulate(rows, cols, |r, c| f(&self.at(r, c)))
    }

    /// Broadcasting binary op — Scala's `binOp`.
    fn bin_op(&self, other: &Self, op: impl Fn(&Big, &Big) -> Big) -> Self {
        let rows = self.rows().max(other.rows());
        let cols = self.cols().max(other.cols());
        let a = self.broadcastTo(rows, cols);
        let b = other.broadcastTo(rows, cols);
        Self::tabulate(rows, cols, |r, c| op(&a.at(r, c), &b.at(r, c)))
    }

    /// `m + other`, broadcasting.
    #[must_use]
    pub fn add(&self, other: &Self) -> Self {
        self.bin_op(other, Big::add)
    }
    /// `m - other`, broadcasting.
    #[must_use]
    pub fn sub(&self, other: &Self) -> Self {
        self.bin_op(other, Big::sub)
    }
    /// `m * other`, elementwise, broadcasting.
    #[must_use]
    pub fn mul(&self, other: &Self) -> Self {
        self.bin_op(other, Big::mul)
    }
    /// `m / other`, elementwise, broadcasting; `BigNaN` where the divisor is zero.
    #[must_use]
    pub fn div(&self, other: &Self) -> Self {
        self.bin_op(other, Big::div)
    }
    /// `m + s`.
    #[must_use]
    pub fn addScalar(&self, s: &Big) -> Self {
        self.map(|x| x.add(s))
    }
    /// `m - s`.
    #[must_use]
    pub fn subScalar(&self, s: &Big) -> Self {
        self.map(|x| x.sub(s))
    }
    /// `m * s`.
    #[must_use]
    pub fn mulScalar(&self, s: &Big) -> Self {
        self.map(|x| x.mul(s))
    }
    /// `m / s`.
    #[must_use]
    pub fn divScalar(&self, s: &Big) -> Self {
        self.map(|x| x.div(s))
    }
    /// `-m`.
    #[must_use]
    pub fn neg(&self) -> Self {
        self.map(Big::neg)
    }
    /// `if x < 0 then -x else x` under the ordering (so `BigNaN` stays).
    #[must_use]
    pub fn abs(&self) -> Self {
        let zero = Big::zero();
        self.map(|x| {
            if x.compare(&zero) < 0 {
                x.neg()
            } else {
                x.clone()
            }
        })
    }
    /// `power(n: Int)`: `n` guarded multiplications from `Big(1)`, rounding each step.
    #[must_use]
    pub fn power(&self, n: u32) -> Self {
        self.map(|x| {
            let mut result = Big::one();
            for _ in 0..n {
                result = result.mul(x);
            }
            result
        })
    }
    /// `Big.sqrt` per element (`BigNaN` for a negative).
    #[must_use]
    pub fn sqrt(&self) -> Self {
        self.map(Big::sqrt)
    }
    /// `Big(math.exp(x.toDouble))` per element — through `f64`, as the Scala.
    #[must_use]
    pub fn exp(&self) -> Self {
        self.map(|x| Big::from_f64(x.toDouble().exp()))
    }
    /// `Big(math.log(x.toDouble))` per element; a non-positive gives `BigNaN`.
    #[must_use]
    pub fn log(&self) -> Self {
        self.map(|x| Big::from_f64(x.toDouble().ln()))
    }

    // ── Reductions ─────────────────────────────────────────────────────────────

    /// Sequential row-major sum from `Big(0)`.
    #[must_use]
    pub fn sum(&self) -> Big {
        let mut total = Big::zero();
        for r in 0..self.rows() {
            for c in 0..self.cols() {
                total = total.add(&self.at(r, c));
            }
        }
        total
    }

    /// `sum / size` (`Big(0)` for an empty matrix).
    #[must_use]
    pub fn mean(&self) -> Big {
        if self.isEmpty() {
            return Big::zero();
        }
        self.sum().div(&big_i(self.size()))
    }

    /// Population variance: `Σ(x − mean)² / n`, sequential.
    #[must_use]
    pub fn variance(&self) -> Big {
        let mu = self.mean();
        let mut sum_sq = Big::zero();
        for r in 0..self.rows() {
            for c in 0..self.cols() {
                let d = self.at(r, c).sub(&mu);
                sum_sq = sum_sq.add(&d.mul(&d));
            }
        }
        sum_sq.div(&big_i(self.size()))
    }

    /// `sqrt(variance)`.
    #[must_use]
    pub fn std(&self) -> Big {
        self.variance().sqrt()
    }

    /// L2 norm of a vector: `sqrt(Σ x²)`.
    ///
    /// # Panics
    /// If the matrix is not 1×n or n×1.
    #[must_use]
    pub fn norm(&self) -> Big {
        assert!(
            self.cols() == 1 || self.rows() == 1,
            "norm requires a vector (1xn or nx1), got {:?}",
            self.shape()
        );
        let mut sum_sq = Big::zero();
        for x in self.flatten() {
            sum_sq = sum_sq.add(&x.mul(&x));
        }
        sum_sq.sqrt()
    }

    fn scan(&self, better: Ordering) -> ((usize, usize), Big) {
        assert!(!self.isEmpty(), "empty matrix");
        let mut best = (0, 0);
        let mut best_v = self.at(0, 0);
        for r in 0..self.rows() {
            for c in 0..self.cols() {
                let v = self.at(r, c);
                if v.compare(&best_v) == better as i32 {
                    best_v = v;
                    best = (r, c);
                }
            }
        }
        (best, best_v)
    }

    /// Smallest under `Big::compare` (`BigNaN` highest, so skipped unless alone).
    ///
    /// # Panics
    /// On an empty matrix.
    #[must_use]
    pub fn min(&self) -> Big {
        self.scan(Ordering::Less).1
    }
    /// Largest under `Big::compare` (`BigNaN` if present).
    ///
    /// # Panics
    /// On an empty matrix.
    #[must_use]
    pub fn max(&self) -> Big {
        self.scan(Ordering::Greater).1
    }
    /// `(row, col)` of the smallest, first on ties.
    ///
    /// # Panics
    /// On an empty matrix.
    #[must_use]
    pub fn argmin(&self) -> (usize, usize) {
        self.scan(Ordering::Less).0
    }
    /// `(row, col)` of the largest, first on ties.
    ///
    /// # Panics
    /// On an empty matrix.
    #[must_use]
    pub fn argmax(&self) -> (usize, usize) {
        self.scan(Ordering::Greater).0
    }

    /// Running sums in row-major order, as a 1×size row — Scala's `cumsum`.
    #[must_use]
    pub fn cumsum(&self) -> Self {
        let mut acc = Big::zero();
        let out: Vec<Big> = self
            .flatten()
            .into_iter()
            .map(|x| {
                acc = acc.add(&x);
                acc.clone()
            })
            .collect();
        let n = out.len();
        Self::create(out, 1, n)
    }

    fn lanes(&self, axis: usize) -> Vec<Vec<Big>> {
        assert!(axis == 0 || axis == 1, "axis must be 0 or 1, got {axis}");
        if axis == 0 {
            (0..self.cols())
                .map(|j| (0..self.rows()).map(|i| self.at(i, j)).collect())
                .collect()
        } else {
            (0..self.rows())
                .map(|i| (0..self.cols()).map(|j| self.at(i, j)).collect())
                .collect()
        }
    }

    fn per_lane(&self, axis: usize, f: impl Fn(&[Big]) -> Big) -> Self {
        let out: Vec<Big> = self.lanes(axis).iter().map(|l| f(l)).collect();
        if axis == 0 {
            let n = out.len();
            Self::create(out, 1, n)
        } else {
            let n = out.len();
            Self::create(out, n, 1)
        }
    }

    /// `sum(axis)`: per column (0 → 1×cols) or row (1 → rows×1), sequential from `Big(0)`.
    #[must_use]
    pub fn sumAxis(&self, axis: usize) -> Self {
        self.per_lane(axis, |l| l.iter().fold(Big::zero(), |s, x| s.add(x)))
    }
    /// `mean(axis)`: `sum(axis) / n`.
    #[must_use]
    pub fn meanAxis(&self, axis: usize) -> Self {
        let n = big_i(if axis == 0 { self.rows() } else { self.cols() });
        self.sumAxis(axis).divScalar(&n)
    }
    /// `min(axis)` under `Big::compare`.
    #[must_use]
    pub fn minAxis(&self, axis: usize) -> Self {
        self.per_lane(axis, |l| Self::row(l).min())
    }
    /// `max(axis)` under `Big::compare`.
    #[must_use]
    pub fn maxAxis(&self, axis: usize) -> Self {
        self.per_lane(axis, |l| Self::row(l).max())
    }
    /// `std(axis)`: the whole-matrix `std` of each lane.
    #[must_use]
    pub fn stdAxis(&self, axis: usize) -> Self {
        self.per_lane(axis, |l| Self::row(l).std())
    }
    /// `cumsum(axis)`: running sums down each column (0) or along each row (1).
    #[must_use]
    pub fn cumsumAxis(&self, axis: usize) -> Self {
        let (rows, cols) = self.shape();
        let mut out = vec![Big::zero(); rows * cols];
        if axis == 0 {
            for j in 0..cols {
                let mut acc = Big::zero();
                for i in 0..rows {
                    acc = acc.add(&self.at(i, j));
                    out[i * cols + j] = acc.clone();
                }
            }
        } else {
            assert!(axis == 1, "axis must be 0 or 1, got {axis}");
            for i in 0..rows {
                let mut acc = Big::zero();
                for j in 0..cols {
                    acc = acc.add(&self.at(i, j));
                    out[i * cols + j] = acc.clone();
                }
            }
        }
        Self::create(out, rows, cols)
    }

    // ── Masks ──────────────────────────────────────────────────────────────────

    fn mask(&self, f: impl Fn(&Big) -> bool) -> MatBool {
        let (rows, cols) = self.shape();
        let mut data = Vec::with_capacity(rows * cols);
        for r in 0..rows {
            for c in 0..cols {
                data.push(f(&self.at(r, c)));
            }
        }
        MatBool::create(data, rows, cols)
    }
    fn ordered(a: &Big, b: &Big) -> Option<Ordering> {
        if a.isNaN() || b.isNaN() {
            None
        } else {
            Some(a.compare(b).cmp(&0))
        }
    }
    /// `m > s`; false against `BigNaN`.
    #[must_use]
    pub fn gt(&self, s: &Big) -> MatBool {
        self.mask(|x| Self::ordered(x, s) == Some(Ordering::Greater))
    }
    /// `m < s`; false against `BigNaN`.
    #[must_use]
    pub fn lt(&self, s: &Big) -> MatBool {
        self.mask(|x| Self::ordered(x, s) == Some(Ordering::Less))
    }
    /// `m >= s`; false against `BigNaN`.
    #[must_use]
    pub fn gte(&self, s: &Big) -> MatBool {
        self.mask(|x| {
            matches!(
                Self::ordered(x, s),
                Some(Ordering::Greater | Ordering::Equal)
            )
        })
    }
    /// `m <= s`; false against `BigNaN`.
    #[must_use]
    pub fn lte(&self, s: &Big) -> MatBool {
        self.mask(|x| matches!(Self::ordered(x, s), Some(Ordering::Less | Ordering::Equal)))
    }
    /// `m :== s` — equality RECOGNISES the sentinel: `eqTo(&Big::nan())` finds the NaNs.
    #[must_use]
    pub fn eqTo(&self, s: &Big) -> MatBool {
        self.mask(|x| x.compare(s) == 0)
    }
    /// `m :!= s`.
    #[must_use]
    pub fn neTo(&self, s: &Big) -> MatBool {
        self.mask(|x| x.compare(s) != 0)
    }
    /// Scala's `hasNaN` on `Mat[Big]`: the sentinel's positions.
    #[must_use]
    pub fn hasNaN(&self) -> MatBool {
        self.mask(Big::isNaN)
    }
    /// Whether any element is the sentinel.
    #[must_use]
    pub fn containsNaN(&self) -> bool {
        self.flatten().iter().any(Big::isNaN)
    }
    /// The elements where `mask` is true, as a 1×k row — Scala's `m(mask)`.
    ///
    /// # Panics
    /// On a shape mismatch.
    #[must_use]
    pub fn applyMask(&self, mask: &MatBool) -> Self {
        assert!(
            mask.shape() == self.shape(),
            "mask shape {:?} != {:?}",
            mask.shape(),
            self.shape()
        );
        let mut out = Vec::new();
        for r in 0..self.rows() {
            for c in 0..self.cols() {
                if mask.at(r, c) {
                    out.push(self.at(r, c));
                }
            }
        }
        let n = out.len();
        Self::create(out, 1, n)
    }

    // ── Products and linear algebra ─────────────────────────────────────────────

    /// `m *@ other`: every cell a sequential k-sum from `Big(0)` through the guarded
    /// arithmetic — Scala's `multiplyBig`.
    ///
    /// # Panics
    /// If the inner dimensions do not agree.
    #[must_use]
    pub fn matmul(&self, other: &Self) -> Self {
        assert!(
            self.cols() == other.rows(),
            "m.cols[{}] != other.rows[{}]",
            self.cols(),
            other.rows()
        );
        let (ra, ca, cb) = (self.rows(), self.cols(), other.cols());
        Self::tabulate(ra, cb, |i, j| {
            let mut sum = Big::zero();
            for k in 0..ca {
                sum = sum.add(&self.at(i, k).mul(&other.at(k, j)));
            }
            sum
        })
    }

    /// Main diagonal.
    #[must_use]
    pub fn diagonal(&self) -> Vec<Big> {
        (0..self.rows().min(self.cols()))
            .map(|i| self.at(i, i))
            .collect()
    }
    /// Sum of the diagonal, sequential from `Big(0)`.
    #[must_use]
    pub fn trace(&self) -> Big {
        self.diagonal().iter().fold(Big::zero(), |s, x| s.add(x))
    }

    /// LU with partial pivoting on a row-major copy — Scala's generic `luDecompose`: the
    /// pivot is the largest `|toDouble|` in the column, the elimination exact.
    fn lu(&self, what: &str) -> Result<(Vec<Big>, Vec<usize>, usize), Error> {
        assert!(
            self.rows() == self.cols(),
            "{what} requires square matrix, got {:?}",
            self.shape()
        );
        let n = self.rows();
        let mut lu = self.flatten();
        let mut pivots: Vec<usize> = (0..n).collect();
        let mut swaps = 0;
        for i in 0..n {
            let mut max_row = i;
            let mut max_abs = lu[i * n + i].toDouble().abs();
            for k in (i + 1)..n {
                let v = lu[k * n + i].toDouble().abs();
                if v > max_abs {
                    max_abs = v;
                    max_row = k;
                }
            }
            if max_row != i {
                for c in 0..n {
                    lu.swap(i * n + c, max_row * n + c);
                }
                pivots.swap(i, max_row);
                swaps += 1;
            }
            let pivot = lu[i * n + i].clone();
            if pivot.toDouble().abs() == 0.0 {
                return Err(Error::SingularMatrix(
                    "Matrix is singular or nearly singular".to_string(),
                ));
            }
            for r in (i + 1)..n {
                let factor = lu[r * n + i].div(&pivot);
                lu[r * n + i] = factor.clone();
                for c in (i + 1)..n {
                    lu[r * n + c] = lu[r * n + c].sub(&factor.mul(&lu[i * n + c]));
                }
            }
        }
        Ok((lu, pivots, swaps))
    }

    fn lu_substitute(lu: &[Big], n: usize, x: &mut [Big]) {
        for i in 1..n {
            for k in 0..i {
                x[i] = x[i].sub(&lu[i * n + k].mul(&x[k]));
            }
        }
        for i in (0..n).rev() {
            for k in (i + 1)..n {
                x[i] = x[i].sub(&lu[i * n + k].mul(&x[k]));
            }
            x[i] = x[i].div(&lu[i * n + i]);
        }
    }

    /// Determinant via LU: `±Π diag`.
    ///
    /// # Errors
    /// [`Error::SingularMatrix`] on an exactly-zero pivot.
    ///
    /// # Panics
    /// If the matrix is not square.
    pub fn determinant(&self) -> Result<Big, Error> {
        let (lu, _, swaps) = self.lu("determinant")?;
        let n = self.rows();
        let mut det = if swaps % 2 == 0 {
            Big::one()
        } else {
            Big::one().neg()
        };
        for i in 0..n {
            det = det.mul(&lu[i * n + i]);
        }
        Ok(det)
    }

    /// Inverse via LU and per-column substitution.
    ///
    /// # Errors
    /// [`Error::SingularMatrix`] on an exactly-zero pivot.
    ///
    /// # Panics
    /// If the matrix is not square.
    pub fn inverse(&self) -> Result<Self, Error> {
        let (lu, pivots, _) = self.lu("inverse")?;
        let n = self.rows();
        let mut result = vec![Big::zero(); n * n];
        for col in 0..n {
            let mut x: Vec<Big> = (0..n)
                .map(|i| {
                    if pivots[i] == col {
                        Big::one()
                    } else {
                        Big::zero()
                    }
                })
                .collect();
            Self::lu_substitute(&lu, n, &mut x);
            for (row, v) in x.into_iter().enumerate() {
                result[row * n + col] = v;
            }
        }
        Ok(Self::create(result, n, n))
    }

    /// `solve(A, b)`: a 1×n `b` is taken as a column, as the Scala does.
    ///
    /// # Errors
    /// [`Error::SingularMatrix`] on an exactly-zero pivot.
    ///
    /// # Panics
    /// If the matrix is not square or `b` has the wrong number of rows.
    pub fn solve(&self, b: &Self) -> Result<Self, Error> {
        assert!(
            self.rows() == self.cols(),
            "solve requires square matrix, got {:?}",
            self.shape()
        );
        let b_col = if b.rows() == 1 && b.cols() == self.rows() {
            b.T()
        } else {
            b.clone()
        };
        assert!(
            b_col.rows() == self.rows(),
            "bCol.rows {} must match matrix rows {}",
            b_col.rows(),
            self.rows()
        );
        let (lu, pivots, _) = self.lu("solve")?;
        let n = self.rows();
        let n_rhs = b_col.cols();
        let mut result = vec![Big::zero(); n * n_rhs];
        for col in 0..n_rhs {
            let mut x: Vec<Big> = (0..n).map(|i| b_col.at(pivots[i], col)).collect();
            Self::lu_substitute(&lu, n, &mut x);
            for (row, v) in x.into_iter().enumerate() {
                result[row * n_rhs + col] = v;
            }
        }
        Ok(Self::create(result, n, n_rhs))
    }

    // ── Ordering ───────────────────────────────────────────────────────────────

    /// `sort()`: the flattened elements ascending under `Big::compare` (`BigNaN` last),
    /// as a 1×size row.
    #[must_use]
    pub fn sort(&self) -> Self {
        let mut v = self.flatten();
        v.sort_by(|a, b| a.compare(b).cmp(&0));
        let n = v.len();
        Self::create(v, 1, n)
    }

    /// `argsort()`: the permutation that sorts the flattened elements, stable on ties, as
    /// a 1×size row of indices (exact integers).
    #[must_use]
    pub fn argsort(&self) -> MatD {
        let v = self.flatten();
        let mut idx: Vec<usize> = (0..v.len()).collect();
        idx.sort_by(|&a, &b| v[a].compare(&v[b]).cmp(&0));
        let n = idx.len();
        #[expect(clippy::cast_precision_loss, reason = "an index")]
        MatD::create(idx.into_iter().map(|i| i as f64).collect(), 1, n)
    }

    // ── Text ───────────────────────────────────────────────────────────────────

    /// One line per row, `sep` between cells, each through `Big::toString` (Java's
    /// `BigDecimal.toString`), the sentinel as `nan_as` — Scala's `saveCSV` text for a
    /// `Mat[Big]`.
    #[must_use]
    pub fn csvText(&self, sep: &str, nan_as: &str) -> String {
        let mut out = String::new();
        for i in 0..self.rows() {
            let cells: Vec<String> = (0..self.cols())
                .map(|j| {
                    let x = self.at(i, j);
                    if x.isNaN() {
                        nan_as.to_owned()
                    } else {
                        x.toString()
                    }
                })
                .collect();
            out.push_str(&cells.join(sep));
            out.push('\n');
        }
        out
    }
}

impl PartialEq for MatB {
    /// Same shape and every cell `compare == 0` (so `BigNaN == BigNaN`, and `1.0 == 1.00`).
    fn eq(&self, other: &Self) -> bool {
        self.shape() == other.shape()
            && self
                .flatten()
                .iter()
                .zip(other.flatten())
                .all(|(a, b)| a.compare(&b) == 0)
    }
}

impl fmt::Debug for MatB {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(f, "{}x{} MatB:", self.rows(), self.cols())?;
        for i in 0..self.rows() {
            let cells: Vec<String> = (0..self.cols())
                .map(|j| {
                    let x = self.at(i, j);
                    if x.isNaN() {
                        "NaN".to_owned()
                    } else {
                        x.toPlainString()
                    }
                })
                .collect();
            writeln!(f, " ({})", cells.join(", "))?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::MatB;
    use crate::udata::MatD;
    use crate::udata::big::Big;

    fn b(s: &str) -> Big {
        Big::parse(s)
    }

    fn m() -> MatB {
        MatB::parseRows(&[&["1.5", "2.25", "-3"], &["4", "0.125", "6.5"]])
    }

    fn strs(m: &MatB) -> Vec<String> {
        m.flatten().iter().map(Big::toString).collect()
    }

    // Expected strings are what the JVM printed for the same matrix (jsrc probe).

    #[test]
    fn whole_matrix_reductions() {
        assert_eq!(m().sum().toString(), "11.375");
        assert_eq!(m().mean().toString(), "1.895833333333333333333333333333333");
        assert_eq!(m().min().toString(), "-3");
        assert_eq!(m().max().toString(), "6.5");
        assert_eq!(m().argmin(), (0, 2));
        assert_eq!(m().argmax(), (1, 2));
        assert_eq!(m().std().toString(), "2.972457480305180449903203849230351");
        assert_eq!(
            m().variance().toString(),
            "8.835503472222222222222222222222222"
        );
        let row = m().slice(0..1, 0..3);
        assert_eq!(row.norm().toString(), "4.038873605350878023438032868655247");
    }

    #[test]
    fn axis_family() {
        assert_eq!(strs(&m().sumAxis(0)), ["5.5", "2.375", "3.5"]);
        assert_eq!(
            m().meanAxis(1).flatten()[1].toString(),
            "3.541666666666666666666666666666667"
        );
        assert_eq!(strs(&m().stdAxis(0)), ["1.25", "1.0625", "4.75"]);
        assert_eq!(strs(&m().minAxis(0)), ["1.5", "0.125", "-3"]);
        assert_eq!(m().cumsumAxis(1).flatten()[5].toString(), "10.625");
        assert_eq!(m().cumsum().flatten()[5].toString(), "11.375");
    }

    #[test]
    fn elementwise_and_products() {
        assert_eq!(
            m().divScalar(&b("3")).flatten()[3].toString(),
            "1.333333333333333333333333333333333"
        );
        let q = m().div(&m().addScalar(&b("10")));
        assert_eq!(
            q.flatten()[0].toString(),
            "0.1304347826086956521739130434782609"
        );
        assert_eq!(
            m().sqrt().flatten()[0].toString(),
            "1.224744871391589049098642037352946"
        );
        assert_eq!(
            m().matmul(&MatB::ones(3, 2)).flatten()[2].toString(),
            "10.625"
        );
        let row = m().slice(0..1, 0..3);
        assert_eq!(m().add(&row).flatten()[3].toString(), "5.5");
        assert_eq!(strs(&m().mul(&m()))[3], "16");
        assert_eq!(strs(&m().neg())[2], "3");
        assert_eq!(strs(&m().abs())[2], "3");
        assert_eq!(strs(&m().power(2))[1], "5.0625");
    }

    #[test]
    fn nan_orders_highest_and_propagates() {
        let v = MatB::row(&[b("1"), Big::nan(), b("3")]);
        assert!(v.sum().isNaN() && v.mean().isNaN());
        assert_eq!(v.min().toString(), "1");
        assert!(v.max().isNaN());
        assert_eq!(v.argmax(), (0, 1));
        let s = v.sort().flatten();
        assert!(s[2].isNaN() && s[0].toString() == "1");
        // A leading NaN is skipped too: the ordering, not the position, decides.
        assert_eq!(MatB::row(&[Big::nan(), b("1")]).min().toString(), "1");
        assert!(MatB::row(&[Big::nan()]).min().isNaN());
        assert!(v.matmul(&MatB::ones(3, 1)).flatten()[0].isNaN());
        assert!(v.abs().flatten()[1].isNaN());
    }

    #[test]
    fn nan_masks() {
        let v = MatB::row(&[b("1"), Big::nan(), b("3")]);
        assert_eq!(v.gt(&b("0")).toArray(), vec![true, false, true]);
        assert_eq!(v.lte(&b("3")).toArray(), vec![true, false, true]);
        assert_eq!(v.eqTo(&Big::nan()).toArray(), vec![false, true, false]);
        assert_eq!(v.hasNaN().toArray(), vec![false, true, false]);
        assert!(v.containsNaN());
        assert_eq!(v.applyMask(&v.gt(&b("0"))).flatten().len(), 2);
    }

    #[test]
    fn linear_algebra_is_exact() {
        let a = MatB::parseRows(&[&["2", "1"], &["1", "3"]]);
        assert_eq!(strs(&a.inverse().unwrap()), ["0.6", "-0.2", "-0.2", "0.4"]);
        assert_eq!(a.determinant().unwrap().toString(), "5.0");
        let x = a.solve(&MatB::row(&[b("3"), b("4")])).unwrap();
        assert_eq!(strs(&x), ["1", "1"]);
        assert_eq!(a.trace().toString(), "5");
        assert!(
            MatB::parseRows(&[&["1", "2"], &["2", "4"]])
                .inverse()
                .is_err()
        );
        assert_eq!(MatB::eye(3).matmul(&MatB::eye(3)), MatB::eye(3));
    }

    #[test]
    fn conversions_and_text() {
        let d = m().toMatD();
        assert_eq!(d.at(1, 1), 0.125);
        assert_eq!(MatB::fromMatD(&d), m());
        assert!(
            MatB::fromMatD(&MatD::create(vec![f64::NAN], 1, 1))
                .at(0, 0)
                .isNaN()
        );
        let v = MatB::row(&[b("1.50"), Big::nan()]);
        assert_eq!(
            v.csvText(",", "N/A"),
            "1.50,N/A
"
        );
        assert_eq!(v.power(3).flatten()[0].toString(), "3.375000");
        assert_eq!(v.argsort().flatten(), vec![0.0, 1.0]);
        assert_eq!(
            format!("{:?}", MatB::row(&[b("1.5"), Big::nan()])),
            "1x2 MatB:
 (1.5, NaN)
"
        );
    }
}

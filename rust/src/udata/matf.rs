//! `MatF` — Scala's `Mat[Float]`: single precision on the same `Mat<T>` core as `MatD`
//! and `MatB`.
//!
//! # What is pinned
//!
//! On the Scala side `Mat[Float]` is the generic `Mat[T]` with `Numeric[Float]`, plus
//! three Float-specific paths: the IEEE mask family (`cmpIeee`, widening to Double),
//! `multiplyFloat` (a tiled loop whose per-cell order is still a sequential k-sum from
//! `0.0f`), and `MatElem[Float]` (`sqrt` through Double, `fromDouble` = `toFloat`).
//! Everything else is the plain sequential fold in `f32` arithmetic. This file reproduces
//! those, so bits agree with the JVM (`f32` add/mul/div are IEEE on both, and neither
//! runtime fuses `x*y + z`):
//!
//! - `sum mean std variance norm cumsum` and the axis family: sequential from `0.0f32`
//!   in `f32`; `mean` divides by `n as f32`; `std` is `sqrt` through `f64` then back
//!   (`MatElem[Float].sqrtT`), and `sqrt` per element likewise.
//! - `min max argmin argmax sort argsort` use `Float.compare` ([`java_float_compare`]:
//!   NaN highest, `-0.0 < 0.0`), first occurrence on ties — Scala's `Ordering[Float]`
//!   is the total one, exactly as `Ordering[Double]` is for `MatD`.
//! - `abs` is `if x < 0 { -x } else { x }` under IEEE `<` (so `-0.0` and NaN survive) —
//!   `Numeric[Float].lt` is the IEEE ordering, unlike the `Ordering[Float]` above.
//! - The masks are IEEE (NaN comparisons false, `-0.0 == 0.0`), as for `MatD`.
//! - `exp`/`log` go through `f64` and back — the libm situation of `PARITY.md` applies,
//!   so those are not fixture-pinned. `power(n)` is `n` multiplications from `1.0f32`.
//! - `matmul` is the sequential k-sum from `0.0f32` per cell (`multiplyFloat`'s order).
//!   Scala routes `Mat[Float]` products to BLAS under `-Duni.mat.blas`; here `matmul`
//!   is always the pinned loop (there is no `sgemm` path in the crate).
//! - LU `inverse`/`determinant`/`solve`: the generic branch, pivot by `|toDouble|`,
//!   elimination in `f32`.
//!
//! `svd`, `eig`, `cholesky`, `lstsq`, `pinv`, `matrixRank` are Double-only in Scala and
//! absent here.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use std::cmp::Ordering;
use std::fmt;

use crate::Error;
use crate::udata::mat::Mat;
use crate::udata::mat::MatD;
use crate::udata::matbool::MatBool;

/// Scala's `MatF` — `Mat[Float]`.
pub type MatF = Mat<f32>;

/// `java.lang.Float.compare` — the ordering Scala's `min`/`max`/`argmin`/`argmax`/`sort`
/// use for `Mat[Float]`: NaN above everything (all NaNs equal), `-0.0 < 0.0`.
#[must_use]
pub fn java_float_compare(a: f32, b: f32) -> Ordering {
    if a < b {
        return Ordering::Less;
    }
    if a > b {
        return Ordering::Greater;
    }
    let ka = if a.is_nan() {
        0x7fc0_0000_i32
    } else {
        a.to_bits() as i32
    };
    let kb = if b.is_nan() {
        0x7fc0_0000_i32
    } else {
        b.to_bits() as i32
    };
    ka.cmp(&kb)
}

/// `MatElem[Float].sqrtT`: `math.sqrt(x.toDouble).toFloat`.
fn sqrt_f(x: f32) -> f32 {
    f64::from(x).sqrt() as f32
}

impl MatF {
    // ── Construction ───────────────────────────────────────────────────────────

    /// Scala's `MatF(arr)`: an `n`×1 column holding a copy of `arr`.
    #[must_use]
    pub fn apply(arr: &[f32]) -> Self {
        Self::create(arr.to_vec(), arr.len(), 1)
    }
    /// `MatF.col(values*)`.
    #[must_use]
    pub fn col(values: &[f32]) -> Self {
        Self::apply(values)
    }
    /// `MatF.row(values*)`.
    #[must_use]
    pub fn row(values: &[f32]) -> Self {
        Self::create(values.to_vec(), 1, values.len())
    }
    /// An `rows`×`cols` matrix of zeros.
    #[must_use]
    pub fn zeros(rows: usize, cols: usize) -> Self {
        Self::filled(rows, cols, 0.0)
    }
    /// An `rows`×`cols` matrix of ones.
    #[must_use]
    pub fn ones(rows: usize, cols: usize) -> Self {
        Self::filled(rows, cols, 1.0)
    }
    /// The `n`×`n` identity.
    #[must_use]
    pub fn eye(n: usize) -> Self {
        let mut d = vec![0.0f32; n * n];
        for i in 0..n {
            d[i * n + i] = 1.0;
        }
        Self::create(d, n, n)
    }
    /// From the loaders' `Array2<f32>` (`path.loadMatF()`, `readCsvF`): a row-major copy.
    #[must_use]
    pub fn fromArray2(a: &ndarray::Array2<f32>) -> Self {
        let (r, c) = a.dim();
        Self::create(a.iter().copied().collect(), r, c)
    }
    /// `m.map(_.toFloat)`: every element narrowed (round-to-nearest-even, as the JVM).
    #[must_use]
    pub fn fromMatD(m: &MatD) -> Self {
        let (r, c) = m.shape();
        #[expect(
            clippy::cast_possible_truncation,
            reason = "the narrowing is the point"
        )]
        Self::create(m.flatten().into_iter().map(|x| x as f32).collect(), r, c)
    }
    /// `m.map(_.toDouble)`: exact widening.
    #[must_use]
    pub fn toMatD(&self) -> MatD {
        let (r, c) = self.shape();
        MatD::create(self.flatten().into_iter().map(f64::from).collect(), r, c)
    }

    // ── Elementwise ────────────────────────────────────────────────────────────

    fn tabulate(rows: usize, cols: usize, f: impl Fn(usize, usize) -> f32) -> Self {
        let mut out = Vec::with_capacity(rows * cols);
        for r in 0..rows {
            for c in 0..cols {
                out.push(f(r, c));
            }
        }
        Self::create(out, rows, cols)
    }
    /// `f` per element, row-major — Scala's `map`.
    #[must_use]
    pub fn map(&self, f: impl Fn(f32) -> f32) -> Self {
        let (rows, cols) = self.shape();
        Self::tabulate(rows, cols, |r, c| f(self.at(r, c)))
    }
    fn bin_op(&self, other: &Self, op: impl Fn(f32, f32) -> f32) -> Self {
        let rows = self.rows().max(other.rows());
        let cols = self.cols().max(other.cols());
        let a = self.broadcastTo(rows, cols);
        let b = other.broadcastTo(rows, cols);
        Self::tabulate(rows, cols, |r, c| op(a.at(r, c), b.at(r, c)))
    }
    /// `m + other`, broadcasting.
    #[must_use]
    pub fn add(&self, other: &Self) -> Self {
        self.bin_op(other, |a, b| a + b)
    }
    /// `m - other`, broadcasting.
    #[must_use]
    pub fn sub(&self, other: &Self) -> Self {
        self.bin_op(other, |a, b| a - b)
    }
    /// `m * other`, elementwise, broadcasting.
    #[must_use]
    pub fn mul(&self, other: &Self) -> Self {
        self.bin_op(other, |a, b| a * b)
    }
    /// `m / other`, elementwise, broadcasting.
    #[must_use]
    pub fn div(&self, other: &Self) -> Self {
        self.bin_op(other, |a, b| a / b)
    }
    /// `m + s`.
    #[must_use]
    pub fn addScalar(&self, s: f32) -> Self {
        self.map(|x| x + s)
    }
    /// `m - s`.
    #[must_use]
    pub fn subScalar(&self, s: f32) -> Self {
        self.map(|x| x - s)
    }
    /// `m * s`.
    #[must_use]
    pub fn mulScalar(&self, s: f32) -> Self {
        self.map(|x| x * s)
    }
    /// `m / s`.
    #[must_use]
    pub fn divScalar(&self, s: f32) -> Self {
        self.map(|x| x / s)
    }
    /// `-m`.
    #[must_use]
    pub fn neg(&self) -> Self {
        self.map(|x| -x)
    }
    /// `if x < 0 { -x } else { x }` under IEEE `<`: `-0.0` and NaN survive.
    #[must_use]
    pub fn abs(&self) -> Self {
        self.map(|x| if x < 0.0 { -x } else { x })
    }
    /// `power(n: Int)`: `n` multiplications from `1.0f32`.
    #[must_use]
    pub fn power(&self, n: u32) -> Self {
        self.map(|x| {
            let mut r = 1.0f32;
            for _ in 0..n {
                r *= x;
            }
            r
        })
    }
    /// `sqrt` through `f64` and back — `MatElem[Float].sqrtT`.
    #[must_use]
    pub fn sqrt(&self) -> Self {
        self.map(sqrt_f)
    }
    /// `Math.exp(x.toDouble).toFloat`.
    #[must_use]
    pub fn exp(&self) -> Self {
        #[expect(
            clippy::cast_possible_truncation,
            reason = "the narrowing is the Scala's"
        )]
        self.map(|x| f64::from(x).exp() as f32)
    }
    /// `Math.log(x.toDouble).toFloat`.
    #[must_use]
    pub fn log(&self) -> Self {
        #[expect(
            clippy::cast_possible_truncation,
            reason = "the narrowing is the Scala's"
        )]
        self.map(|x| f64::from(x).ln() as f32)
    }

    // ── Reductions ─────────────────────────────────────────────────────────────

    /// Sequential row-major sum from `0.0f32`.
    #[must_use]
    pub fn sum(&self) -> f32 {
        let mut total = 0.0f32;
        for r in 0..self.rows() {
            for c in 0..self.cols() {
                total += self.at(r, c);
            }
        }
        total
    }
    /// `sum / size` (`0.0` for an empty matrix).
    #[must_use]
    pub fn mean(&self) -> f32 {
        if self.isEmpty() {
            return 0.0;
        }
        #[expect(clippy::cast_precision_loss, reason = "a size, as Scala's fromInt")]
        let n = self.size() as f32;
        self.sum() / n
    }
    /// Population variance, sequential.
    #[must_use]
    pub fn variance(&self) -> f32 {
        let mu = self.mean();
        let mut sum_sq = 0.0f32;
        for r in 0..self.rows() {
            for c in 0..self.cols() {
                let d = self.at(r, c) - mu;
                sum_sq += d * d;
            }
        }
        #[expect(clippy::cast_precision_loss, reason = "a size, as Scala's fromInt")]
        let n = self.size() as f32;
        sum_sq / n
    }
    /// `sqrt(variance)` through `f64`.
    #[must_use]
    pub fn std(&self) -> f32 {
        sqrt_f(self.variance())
    }
    /// L2 norm of a vector.
    ///
    /// # Panics
    /// If the matrix is not 1×n or n×1.
    #[must_use]
    pub fn norm(&self) -> f32 {
        assert!(
            self.cols() == 1 || self.rows() == 1,
            "norm requires a vector (1xn or nx1), got {:?}",
            self.shape()
        );
        let mut s = 0.0f32;
        for x in self.flatten() {
            s += x * x;
        }
        sqrt_f(s)
    }
    fn scan(&self, better: Ordering) -> ((usize, usize), f32) {
        assert!(!self.isEmpty(), "empty matrix");
        let mut best = (0, 0);
        let mut best_v = self.at(0, 0);
        for r in 0..self.rows() {
            for c in 0..self.cols() {
                let v = self.at(r, c);
                if java_float_compare(v, best_v) == better {
                    best_v = v;
                    best = (r, c);
                }
            }
        }
        (best, best_v)
    }
    /// Smallest under `Float.compare`.
    ///
    /// # Panics
    /// On an empty matrix.
    #[must_use]
    pub fn min(&self) -> f32 {
        self.scan(Ordering::Less).1
    }
    /// Largest under `Float.compare` (NaN if present).
    ///
    /// # Panics
    /// On an empty matrix.
    #[must_use]
    pub fn max(&self) -> f32 {
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
    /// Running sums in row-major order, as a 1×size row.
    #[must_use]
    pub fn cumsum(&self) -> Self {
        let mut acc = 0.0f32;
        let out: Vec<f32> = self
            .flatten()
            .into_iter()
            .map(|x| {
                acc += x;
                acc
            })
            .collect();
        let n = out.len();
        Self::create(out, 1, n)
    }
    fn lanes(&self, axis: usize) -> Vec<Vec<f32>> {
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
    fn per_lane(&self, axis: usize, f: impl Fn(&[f32]) -> f32) -> Self {
        let out: Vec<f32> = self.lanes(axis).iter().map(|l| f(l)).collect();
        let n = out.len();
        if axis == 0 {
            Self::create(out, 1, n)
        } else {
            Self::create(out, n, 1)
        }
    }
    /// `sum(axis)`, sequential from `0.0f32`.
    #[must_use]
    pub fn sumAxis(&self, axis: usize) -> Self {
        self.per_lane(axis, |l| l.iter().fold(0.0f32, |s, &x| s + x))
    }
    /// `mean(axis)`: `sum(axis) / n`.
    #[must_use]
    pub fn meanAxis(&self, axis: usize) -> Self {
        #[expect(clippy::cast_precision_loss, reason = "a size, as Scala's fromInt")]
        let n = (if axis == 0 { self.rows() } else { self.cols() }) as f32;
        self.sumAxis(axis).divScalar(n)
    }
    /// `min(axis)` under `Float.compare`.
    #[must_use]
    pub fn minAxis(&self, axis: usize) -> Self {
        self.per_lane(axis, |l| Self::row(l).min())
    }
    /// `max(axis)` under `Float.compare`.
    #[must_use]
    pub fn maxAxis(&self, axis: usize) -> Self {
        self.per_lane(axis, |l| Self::row(l).max())
    }
    /// `std(axis)`: the whole-matrix `std` of each lane.
    #[must_use]
    pub fn stdAxis(&self, axis: usize) -> Self {
        self.per_lane(axis, |l| Self::row(l).std())
    }
    /// `cumsum(axis)`.
    #[must_use]
    pub fn cumsumAxis(&self, axis: usize) -> Self {
        assert!(axis == 0 || axis == 1, "axis must be 0 or 1, got {axis}");
        let (rows, cols) = self.shape();
        let mut out = vec![0.0f32; rows * cols];
        if axis == 0 {
            for j in 0..cols {
                let mut acc = 0.0f32;
                for i in 0..rows {
                    acc += self.at(i, j);
                    out[i * cols + j] = acc;
                }
            }
        } else {
            for i in 0..rows {
                let mut acc = 0.0f32;
                for j in 0..cols {
                    acc += self.at(i, j);
                    out[i * cols + j] = acc;
                }
            }
        }
        Self::create(out, rows, cols)
    }

    // ── Masks (IEEE, widened to f64 as Scala's `cmpIeee`) ──────────────────────

    fn mask(&self, f: impl Fn(f64) -> bool) -> MatBool {
        let (rows, cols) = self.shape();
        let mut data = Vec::with_capacity(rows * cols);
        for r in 0..rows {
            for c in 0..cols {
                data.push(f(f64::from(self.at(r, c))));
            }
        }
        MatBool::create(data, rows, cols)
    }
    /// `m > s` (IEEE: false against NaN).
    #[must_use]
    pub fn gt(&self, s: f32) -> MatBool {
        let o = f64::from(s);
        self.mask(|x| x > o)
    }
    /// `m < s`.
    #[must_use]
    pub fn lt(&self, s: f32) -> MatBool {
        let o = f64::from(s);
        self.mask(|x| x < o)
    }
    /// `m >= s`.
    #[must_use]
    pub fn gte(&self, s: f32) -> MatBool {
        let o = f64::from(s);
        self.mask(|x| x >= o)
    }
    /// `m <= s`.
    #[must_use]
    pub fn lte(&self, s: f32) -> MatBool {
        let o = f64::from(s);
        self.mask(|x| x <= o)
    }
    /// `m :== s` (IEEE: `-0.0 == 0.0`, NaN never equal).
    #[must_use]
    pub fn eqTo(&self, s: f32) -> MatBool {
        let o = f64::from(s);
        self.mask(|x| x == o)
    }
    /// `m :!= s`.
    #[must_use]
    pub fn neTo(&self, s: f32) -> MatBool {
        let o = f64::from(s);
        self.mask(|x| x != o)
    }
    /// `np.isnan`.
    #[must_use]
    pub fn isnan(&self) -> MatBool {
        self.mask(f64::is_nan)
    }
    /// Whether any element is NaN.
    #[must_use]
    pub fn containsNaN(&self) -> bool {
        self.flatten().iter().any(|x| x.is_nan())
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

    /// `m *@ other`: every cell a sequential k-sum from `0.0f32` — `multiplyFloat`'s
    /// per-cell order (its tiles visit k in order). Always the pinned loop here.
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
            let mut sum = 0.0f32;
            for k in 0..ca {
                sum += self.at(i, k) * other.at(k, j);
            }
            sum
        })
    }
    /// Main diagonal.
    #[must_use]
    pub fn diagonal(&self) -> Vec<f32> {
        (0..self.rows().min(self.cols()))
            .map(|i| self.at(i, i))
            .collect()
    }
    /// Sum of the diagonal, sequential.
    #[must_use]
    pub fn trace(&self) -> f32 {
        self.diagonal().iter().fold(0.0f32, |s, &x| s + x)
    }
    fn lu(&self, what: &str) -> Result<(Vec<f32>, Vec<usize>, usize), Error> {
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
            let mut max_abs = f64::from(lu[i * n + i]).abs();
            for k in (i + 1)..n {
                let v = f64::from(lu[k * n + i]).abs();
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
            let pivot = lu[i * n + i];
            if f64::from(pivot).abs() == 0.0 {
                return Err(Error::SingularMatrix(
                    "Matrix is singular or nearly singular".to_string(),
                ));
            }
            for r in (i + 1)..n {
                let factor = lu[r * n + i] / pivot;
                lu[r * n + i] = factor;
                for c in (i + 1)..n {
                    lu[r * n + c] -= factor * lu[i * n + c];
                }
            }
        }
        Ok((lu, pivots, swaps))
    }
    fn lu_substitute(lu: &[f32], n: usize, x: &mut [f32]) {
        for i in 1..n {
            for k in 0..i {
                x[i] -= lu[i * n + k] * x[k];
            }
        }
        for i in (0..n).rev() {
            for k in (i + 1)..n {
                x[i] -= lu[i * n + k] * x[k];
            }
            x[i] /= lu[i * n + i];
        }
    }
    /// Determinant via LU.
    ///
    /// # Errors
    /// [`Error::SingularMatrix`] on an exactly-zero pivot.
    ///
    /// # Panics
    /// If the matrix is not square.
    pub fn determinant(&self) -> Result<f32, Error> {
        let (lu, _, swaps) = self.lu("determinant")?;
        let n = self.rows();
        let mut det = if swaps % 2 == 0 { 1.0f32 } else { -1.0f32 };
        for i in 0..n {
            det *= lu[i * n + i];
        }
        Ok(det)
    }
    /// Inverse via LU.
    ///
    /// # Errors
    /// [`Error::SingularMatrix`] on an exactly-zero pivot.
    ///
    /// # Panics
    /// If the matrix is not square.
    pub fn inverse(&self) -> Result<Self, Error> {
        let (lu, pivots, _) = self.lu("inverse")?;
        let n = self.rows();
        let mut result = vec![0.0f32; n * n];
        for col in 0..n {
            let mut x: Vec<f32> = (0..n)
                .map(|i| if pivots[i] == col { 1.0 } else { 0.0 })
                .collect();
            Self::lu_substitute(&lu, n, &mut x);
            for (row, v) in x.into_iter().enumerate() {
                result[row * n + col] = v;
            }
        }
        Ok(Self::create(result, n, n))
    }
    /// `solve(A, b)`; a 1×n `b` is taken as a column.
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
        let mut result = vec![0.0f32; n * n_rhs];
        for col in 0..n_rhs {
            let mut x: Vec<f32> = (0..n).map(|i| b_col.at(pivots[i], col)).collect();
            Self::lu_substitute(&lu, n, &mut x);
            for (row, v) in x.into_iter().enumerate() {
                result[row * n_rhs + col] = v;
            }
        }
        Ok(Self::create(result, n, n_rhs))
    }

    // ── Ordering ───────────────────────────────────────────────────────────────

    /// `sort()`: the flattened elements ascending under `Float.compare`, as a 1×size row.
    #[must_use]
    pub fn sort(&self) -> Self {
        let mut v = self.flatten();
        v.sort_by(|a, b| java_float_compare(*a, *b));
        let n = v.len();
        Self::create(v, 1, n)
    }
    /// `argsort()`, stable on ties, as a 1×size row of indices in a `MatD`.
    #[must_use]
    pub fn argsort(&self) -> MatD {
        let v = self.flatten();
        let mut idx: Vec<usize> = (0..v.len()).collect();
        idx.sort_by(|&a, &b| java_float_compare(v[a], v[b]));
        let n = idx.len();
        #[expect(clippy::cast_precision_loss, reason = "an index")]
        MatD::create(idx.into_iter().map(|i| i as f64).collect(), 1, n)
    }
}

impl PartialEq for MatF {
    /// Same shape and bit-identical elements (`Float.floatToIntBits`, all NaNs one).
    fn eq(&self, other: &Self) -> bool {
        self.shape() == other.shape()
            && self
                .flatten()
                .iter()
                .zip(other.flatten())
                .all(|(a, b)| java_float_compare(*a, b) == Ordering::Equal)
    }
}

impl fmt::Debug for MatF {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(f, "{}x{} MatF:", self.rows(), self.cols())?;
        for i in 0..self.rows() {
            let cells: Vec<String> = (0..self.cols())
                .map(|j| self.at(i, j).to_string())
                .collect();
            writeln!(f, " ({})", cells.join(", "))?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::cmp::Ordering;

    use super::MatF;
    use super::java_float_compare;

    fn m() -> MatF {
        MatF::create(vec![1.5, 2.25, -3.0, 4.0, 0.125, 6.5], 2, 3)
    }

    #[test]
    fn folds_are_single_precision_and_sequential() {
        assert_eq!(m().sum(), 11.375);
        assert_eq!(m().mean(), 11.375 / 6.0);
        assert_eq!(m().sumAxis(0).flatten(), vec![5.5, 2.375, 3.5]);
        assert_eq!(m().cumsum().flatten()[5], 11.375);
        assert_eq!(m().min(), -3.0);
        assert_eq!(m().argmax(), (1, 2));
        // 0.1f32 summed thrice is not 0.3f32 — the single-precision fold, not a double one
        let t = MatF::row(&[0.1, 0.1, 0.1]);
        assert_eq!(t.sum(), 0.1f32 + 0.1f32 + 0.1f32);
        assert_ne!(f64::from(t.sum()), 0.1f64 + 0.1f64 + 0.1f64);
    }

    #[test]
    fn ordering_is_float_compare_and_masks_are_ieee() {
        let v = MatF::row(&[1.0, f32::NAN, -0.0, 0.0]);
        assert_eq!(java_float_compare(-0.0, 0.0), Ordering::Less);
        assert_eq!(
            java_float_compare(f32::NAN, f32::INFINITY),
            Ordering::Greater
        );
        let s = v.sort().flatten();
        assert_eq!(s[0].to_bits(), (-0.0f32).to_bits());
        assert!(s[3].is_nan());
        assert!(v.max().is_nan());
        assert_eq!(v.min().to_bits(), (-0.0f32).to_bits());
        assert_eq!(v.gt(0.0).toArray(), vec![true, false, false, false]);
        assert_eq!(v.eqTo(0.0).toArray(), vec![false, false, true, true]);
        assert_eq!(v.isnan().toArray(), vec![false, true, false, false]);
        assert_eq!(v.abs().flatten()[2].to_bits(), (-0.0f32).to_bits()); // IEEE `<` keeps -0.0
        assert!(v.abs().flatten()[1].is_nan());
    }

    #[test]
    fn products_and_lu() {
        let a = MatF::create(vec![2.0, 1.0, 1.0, 3.0], 2, 2);
        assert_eq!(a.matmul(&MatF::eye(2)), a);
        let inv = a.inverse().unwrap();
        assert!((inv.at(0, 0) - 0.6).abs() < 1e-6 && (inv.at(1, 1) - 0.4).abs() < 1e-6);
        assert_eq!(a.determinant().unwrap(), 5.0);
        let x = a.solve(&MatF::row(&[3.0, 4.0])).unwrap();
        assert!((x.at(0, 0) - 1.0).abs() < 1e-6 && (x.at(1, 0) - 1.0).abs() < 1e-6);
        assert!(
            MatF::create(vec![1.0, 2.0, 2.0, 4.0], 2, 2)
                .inverse()
                .is_err()
        );
        assert_eq!(m().T().matmul(&m()).shape(), (3, 3));
    }

    #[test]
    fn conversions_and_elementwise() {
        let d = m().toMatD();
        assert_eq!(d.at(1, 1), 0.125);
        assert_eq!(MatF::fromMatD(&d), m());
        assert_eq!(m().power(2).flatten()[1], 2.25f32 * 2.25f32);
        assert_eq!(m().sqrt().flatten()[3], 2.0);
        assert_eq!(m().add(&m().slice(0..1, 0..3)).flatten()[3], 5.5);
        assert_eq!(m().mulScalar(2.0).flatten()[0], 3.0);
        assert_eq!(
            m().applyMask(&m().gt(1.0)).flatten(),
            vec![1.5, 2.25, 4.0, 6.5]
        );
        assert_eq!(m().argsort().flatten(), vec![2.0, 4.0, 0.0, 1.0, 3.0, 5.0]);
        assert_eq!(
            format!("{:?}", MatF::row(&[1.5, -0.0])),
            "1x2 MatF:\n (1.5, -0)\n"
        );
    }
}

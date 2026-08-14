//! Dense `f64` matrices — the start of the port of Scala's `uni.data.Mat`.
//!
//! # Scope
//!
//! This is milestone 1 of Tier 3 in `PARITY.md`, scoped by a real consumer rather than
//! by API completeness: the operations `jsrc/marketSim.sc` needs. Construction from a
//! slice, elementwise arithmetic against a scalar and against a same-shape matrix,
//! [`MatD::abs`], [`MatD::power`], [`MatD::cumsum`], [`MatD::mean`], [`MatD::sum`],
//! [`MatD::toArray`], [`MatD::head`], [`MatD::tail`]. Broadcasting, slicing, transpose,
//! `CVecD`/`RVecD` and the axis reductions come next; a shape mismatch is an explicit
//! panic here rather than a silent broadcast, so nothing can quietly do the wrong thing
//! before that lands.
//!
//! # Bit-exactness is the point
//!
//! Every reduction reproduces Scala's floating-point *association order*, not merely its
//! mathematical value. Scala's `sumD` is not a naive fold, and a Rust `iter().sum()`
//! lands on a different last ulp, which would silently break any byte-identical demo
//! pair built on it. Three details carry that:
//!
//! - [`sum_range`] accumulates into 8 unrolled accumulators and combines them as
//!   `((s0+s1)+(s2+s3))+((s4+s5)+(s6+s7))`, then adds the tail elementwise.
//! - [`sum_d`] splits arrays of at least [`PARALLEL_THRESHOLD`] into
//!   `min(MAX_SUM_CHUNKS, n / PARALLEL_THRESHOLD)` chunks, sums each with
//!   [`sum_range`], and combines the partials **sequentially in index order**. Chunk
//!   count is a pure function of length — Scala pinned `MaxSumChunks` to a constant for
//!   exactly this reason, so the answer does not depend on core count, container CPU
//!   limits or `-XX:ActiveProcessorCount`. Both constants must stay in lockstep with
//!   `Mat.scala`; changing either moves the low-order bits of every large sum.
//! - [`MatD::abs`] tests `x < 0.0` rather than calling `f64::abs`, because Scala's
//!   `abs` is `if x < zero then -x else x` — which leaves `-0.0` as `-0.0`, where
//!   `f64::abs(-0.0)` returns `+0.0`.
//!
//! [`MatD::power`] likewise multiplies repeatedly from `1.0` rather than calling
//! `powi`, mirroring the Scala loop exactly.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name, so a script kept in both \
              languages needs no mental translation. Internal helpers stay snake_case, \
              so the case says whether a Scala counterpart exists."
)]

use rayon::prelude::*;
use std::ops::Add;
use std::ops::Index;
use std::ops::Mul;
use std::ops::Sub;

/// Fork/join overhead dominates below this size; sum sequentially. Mirrors
/// `Mat.ParallelThreshold`.
pub const PARALLEL_THRESHOLD: usize = 4096;

/// Chunk-count cap for [`sum_d`], mirroring `Mat.MaxSumChunks`. Pinned to a constant so
/// the summation order — and therefore the last ulp — is a function of length alone.
pub const MAX_SUM_CHUNKS: usize = 16;

/// Sum of `a[from..until]`, 8-way unrolled with Scala's exact combine tree.
///
/// The association order here is load-bearing: it is what makes the Rust and Scala sums
/// agree bit for bit. Do not "simplify" this to a fold.
fn sum_range(a: &[f64], from: usize, until: usize) -> f64 {
    let (mut s0, mut s1, mut s2, mut s3) = (0.0f64, 0.0f64, 0.0f64, 0.0f64);
    let (mut s4, mut s5, mut s6, mut s7) = (0.0f64, 0.0f64, 0.0f64, 0.0f64);
    let mut i = from;
    // Scala computes `until - 7` in Int arithmetic, where a short range yields a
    // negative limit and the unrolled loop simply does not run. saturating_sub
    // reproduces that without underflowing usize.
    let limit = until.saturating_sub(7);
    while i < limit {
        s0 += a[i];
        s1 += a[i + 1];
        s2 += a[i + 2];
        s3 += a[i + 3];
        s4 += a[i + 4];
        s5 += a[i + 5];
        s6 += a[i + 6];
        s7 += a[i + 7];
        i += 8;
    }
    let mut s = ((s0 + s1) + (s2 + s3)) + ((s4 + s5) + (s6 + s7));
    while i < until {
        s += a[i];
        i += 1;
    }
    s
}

/// Sum of a slice; chunked in parallel above [`PARALLEL_THRESHOLD`], with partials
/// combined sequentially in index order. Bit-identical to Scala's `Mat.sumD`.
fn sum_d(a: &[f64]) -> f64 {
    let n = a.len();
    if n < PARALLEL_THRESHOLD {
        return sum_range(a, 0, n);
    }
    let chunks = MAX_SUM_CHUNKS.min((n / PARALLEL_THRESHOLD).max(1));
    let step = n.div_ceil(chunks);
    // collect() preserves index order, so the sequential fold below sees the same
    // sequence Scala's `partials` array holds regardless of how rayon schedules.
    let partials: Vec<f64> = (0..chunks)
        .into_par_iter()
        .map(|c| {
            let from = c * step;
            let until = (from + step).min(n);
            // Scala leaves the slot at its 0.0 initialiser when the chunk is empty.
            if from < until { sum_range(a, from, until) } else { 0.0 }
        })
        .collect();
    let mut s = 0.0f64;
    for p in partials {
        s += p;
    }
    s
}

/// A dense, contiguous, row-major `f64` matrix — Scala's `Mat[Double]` / `MatD`.
///
/// Backed by a flat `Vec<f64>` rather than `ndarray::Array2`. Every operation at this
/// milestone is flat elementwise work or a whole-buffer reduction, for which the shape
/// is only ever used to compute a row offset — and a `Vec` keeps both the backing-slice
/// accessor and construction *infallible*, where `Array2` would force a `Result` and an
/// `Option` into paths that cannot actually fail. That matters here because the crate is
/// deliberately free of `unwrap`/`expect`/`panic!`. When transpose, matmul and the BLAS
/// crossover land, an `ArrayView2::from_shape` over this same buffer is zero-copy and
/// confines the fallibility to one reviewable site.
#[derive(Clone, Debug, PartialEq)]
pub struct MatD {
    data: Vec<f64>,
    rows: usize,
    cols: usize,
}

impl MatD {
    /// Scala's `MatD(arr)`: an `n`×1 column matrix holding a **copy** of `arr`.
    ///
    /// The copy is deliberate and matches `Mat.apply(Array)`, which clones so the caller
    /// keeps ownership of its array. Callers wanting to hand over storage use
    /// [`MatD::create`].
    pub fn apply(arr: &[f64]) -> Self {
        Self::create(arr.to_vec(), arr.len(), 1)
    }

    /// Scala's `Mat.create(data, rows, cols)` — takes ownership, row-major.
    ///
    /// # Panics
    /// If `data.len() != rows * cols`.
    pub fn create(data: Vec<f64>, rows: usize, cols: usize) -> Self {
        assert_eq!(
            data.len(),
            rows * cols,
            "create: {} elements does not fill {rows}x{cols}",
            data.len()
        );
        Self { data, rows, cols }
    }

    /// An `rows`×`cols` matrix of zeros.
    pub fn zeros(rows: usize, cols: usize) -> Self {
        Self { data: vec![0.0; rows * cols], rows, cols }
    }

    /// An `rows`×`cols` matrix of ones.
    pub fn ones(rows: usize, cols: usize) -> Self {
        Self { data: vec![1.0; rows * cols], rows, cols }
    }

    pub fn rows(&self) -> usize {
        self.rows
    }

    pub fn cols(&self) -> usize {
        self.cols
    }

    /// Total element count — Scala's `size`, i.e. `rows * cols`, not `rows`.
    pub fn size(&self) -> usize {
        self.data.len()
    }

    pub fn shape(&self) -> (usize, usize) {
        (self.rows(), self.cols())
    }

    pub fn isEmpty(&self) -> bool {
        self.data.is_empty()
    }

    /// Element at `(r, c)`. Scala's `at`; also reachable as `m[(r, c)]`.
    pub fn at(&self, r: usize, c: usize) -> f64 {
        self.data[r * self.cols + c]
    }

    /// Row-major flat copy — Scala's `toArray` (an alias for `flatten`).
    pub fn toArray(&self) -> Vec<f64> {
        self.data.clone()
    }

    /// Read-only view of the backing storage, row-major. No Scala counterpart —
    /// `Mat.data` is `private[data]` there — so this stays snake_case. Infallible by
    /// construction: the buffer *is* the matrix.
    fn as_slice(&self) -> &[f64] {
        &self.data
    }

    /// First `min(n, rows)` rows, all columns — Scala's `head(n)`.
    ///
    /// Note this is row-oriented even for a column vector, exactly as in Scala: for the
    /// `n`×1 shape [`MatD::apply`] produces, it yields the first `n` elements.
    pub fn head(&self, n: usize) -> Self {
        let keep = n.min(self.rows());
        self.rowsSlice(0, keep)
    }

    /// Last `min(n, rows)` rows, all columns — Scala's `tail(n)`.
    pub fn tail(&self, n: usize) -> Self {
        let start = self.rows().saturating_sub(n);
        self.rowsSlice(start, self.rows())
    }

    /// Rows `start..end`, all columns. Mirrors the Scala `apply(rows: Range, ::)`
    /// overload collectively with the other `applyXxx`/`rowsXxx` members; see the naming
    /// note in `PARITY.md`.
    pub fn rowsSlice(&self, start: usize, end: usize) -> Self {
        assert!(
            start <= end && end <= self.rows(),
            "rowsSlice({start}, {end}) out of bounds for {}x{}",
            self.rows(),
            self.cols()
        );
        let cols = self.cols();
        let src = self.as_slice();
        let data = src[start * cols..end * cols].to_vec();
        Self::create(data, end - start, cols)
    }

    /// Elementwise absolute value — Scala's `abs`.
    ///
    /// Tests `x < 0.0` rather than calling `f64::abs`, so `-0.0` survives as `-0.0`
    /// exactly as it does in Scala. `f64::abs(-0.0)` would return `+0.0`.
    pub fn abs(&self) -> Self {
        self.map_elems(|x| if x < 0.0 { -x } else { x })
    }

    /// Elementwise integer power — Scala's `power(n: Int)`.
    ///
    /// Repeated multiplication starting from `1.0`, mirroring the Scala loop rather than
    /// calling `powi`, so the association order cannot drift.
    ///
    /// # Panics
    /// Never for `u32`; Scala's `require(n >= 0)` is enforced by the type here.
    pub fn power(&self, n: u32) -> Self {
        self.map_elems(|x| {
            let mut result = 1.0f64;
            for _ in 0..n {
                result *= x;
            }
            result
        })
    }

    /// Running total over the row-major flattening, returned as a **1×n row** matrix.
    ///
    /// The shape change is Scala's, not an accident: `cumsum` there ends with
    /// `Mat.create(result, 1, a.length)` whatever the input shape was.
    pub fn cumsum(&self) -> Self {
        let src = self.as_slice();
        let mut out = Vec::with_capacity(src.len());
        let mut acc = 0.0f64;
        for &x in src {
            acc += x;
            out.push(acc);
        }
        let n = out.len();
        Self::create(out, 1, n)
    }

    /// Sum of every element — bit-identical to Scala's `sum` on a contiguous `Mat[Double]`.
    pub fn sum(&self) -> f64 {
        sum_d(self.as_slice())
    }

    /// Arithmetic mean — Scala's `mean`: `sumD(a) / (rows * cols)`, one division.
    ///
    /// Returns `0.0` for an empty matrix, matching Scala's `frac.zero` branch.
    pub fn mean(&self) -> f64 {
        if self.rows() == 0 || self.cols() == 0 {
            return 0.0;
        }
        sum_d(self.as_slice()) / (self.rows() * self.cols()) as f64
    }

    /// Elementwise map preserving shape. Internal — Scala's `map` is public and generic,
    /// and lands in a later phase.
    fn map_elems(&self, f: impl Fn(f64) -> f64) -> Self {
        let data = self.as_slice().iter().map(|&x| f(x)).collect();
        Self::create(data, self.rows(), self.cols())
    }

    /// Elementwise combine of two same-shape matrices.
    fn zip_elems(&self, other: &Self, f: impl Fn(f64, f64) -> f64) -> Self {
        assert_eq!(
            self.shape(),
            other.shape(),
            "shape mismatch: {:?} vs {:?} — broadcasting is not in this milestone",
            self.shape(),
            other.shape()
        );
        let data = self
            .as_slice()
            .iter()
            .zip(other.as_slice())
            .map(|(&a, &b)| f(a, b))
            .collect();
        Self::create(data, self.rows(), self.cols())
    }
}

/// `m[(r, c)]` as Rust sugar over [`MatD::at`]. Only scalar reads can use bracket
/// syntax — `Index` must return a reference, so slicing stays method-shaped.
impl Index<(usize, usize)> for MatD {
    type Output = f64;

    fn index(&self, (r, c): (usize, usize)) -> &f64 {
        &self.data[r * self.cols + c]
    }
}

impl From<&[f64]> for MatD {
    fn from(arr: &[f64]) -> Self {
        Self::apply(arr)
    }
}

macro_rules! elementwise_binop {
    ($trait:ident, $method:ident, $op:tt) => {
        // MatD ⊕ MatD, elementwise. Scala's `*` on two Mats aliases `*:*`, so `Mul`
        // here is elementwise and NOT matrix multiplication.
        impl $trait<&MatD> for &MatD {
            type Output = MatD;
            fn $method(self, rhs: &MatD) -> MatD {
                self.zip_elems(rhs, |a, b| a $op b)
            }
        }
        impl $trait<MatD> for MatD {
            type Output = MatD;
            fn $method(self, rhs: MatD) -> MatD {
                self.zip_elems(&rhs, |a, b| a $op b)
            }
        }
        // MatD ⊕ scalar
        impl $trait<f64> for &MatD {
            type Output = MatD;
            fn $method(self, rhs: f64) -> MatD {
                self.map_elems(|a| a $op rhs)
            }
        }
        impl $trait<f64> for MatD {
            type Output = MatD;
            fn $method(self, rhs: f64) -> MatD {
                self.map_elems(|a| a $op rhs)
            }
        }
        // scalar ⊕ MatD — Scala defines these separately (Mat.scala:656-658) precisely
        // so `1.0 - m` means `m.map(1.0 - _)` and not `m.map(_ - 1.0)`.
        impl $trait<&MatD> for f64 {
            type Output = MatD;
            fn $method(self, rhs: &MatD) -> MatD {
                rhs.map_elems(|b| self $op b)
            }
        }
        impl $trait<MatD> for f64 {
            type Output = MatD;
            fn $method(self, rhs: MatD) -> MatD {
                rhs.map_elems(|b| self $op b)
            }
        }
    };
}

elementwise_binop!(Add, add, +);
elementwise_binop!(Sub, sub, -);
elementwise_binop!(Mul, mul, *);

#[cfg(test)]
mod tests {
    use super::*;

    /// A deterministic, badly-conditioned sequence: mixing magnitudes is what makes
    /// association order observable at all.
    fn probe(n: usize) -> Vec<f64> {
        (0..n).map(|i| (i as f64).sin() * 1e3 + 1e-9 * i as f64).collect()
    }

    #[test]
    fn sum_matches_the_unrolled_tree_below_threshold() {
        let a = probe(100);
        // Independent restatement of the combine tree, not a copy of sum_range.
        let mut acc = [0.0f64; 8];
        let mut i = 0;
        while i + 8 <= 96 {
            for k in 0..8 {
                acc[k] += a[i + k];
            }
            i += 8;
        }
        let mut want = ((acc[0] + acc[1]) + (acc[2] + acc[3])) + ((acc[4] + acc[5]) + (acc[6] + acc[7]));
        while i < 100 {
            want += a[i];
            i += 1;
        }
        assert_eq!(MatD::apply(&a).sum().to_bits(), want.to_bits());
    }

    #[test]
    fn chunk_count_is_a_pure_function_of_length() {
        // Straddles both thresholds: sequential, 1 chunk, mid, and capped at 16.
        for n in [4095usize, 4096, 65_536, 65_537, 200_000] {
            let a = probe(n);
            let once = sum_d(&a);
            let twice = sum_d(&a);
            assert_eq!(once.to_bits(), twice.to_bits(), "n={n} not reproducible");

            let expected_chunks = if n < PARALLEL_THRESHOLD {
                0
            } else {
                MAX_SUM_CHUNKS.min((n / PARALLEL_THRESHOLD).max(1))
            };
            if expected_chunks > 0 {
                let step = n.div_ceil(expected_chunks);
                let mut manual = 0.0f64;
                for c in 0..expected_chunks {
                    let from = c * step;
                    let until = (from + step).min(n);
                    if from < until {
                        manual += sum_range(&a, from, until);
                    }
                }
                assert_eq!(manual.to_bits(), once.to_bits(), "n={n} chunking drifted");
            }
        }
    }

    #[test]
    fn abs_preserves_negative_zero_like_scala() {
        let m = MatD::apply(&[-0.0, -1.5, 2.5]);
        let got = m.abs().toArray();
        assert!(got[0].is_sign_negative(), "-0.0 must survive as -0.0, not become +0.0");
        assert_eq!(got[1], 1.5);
        assert_eq!(got[2], 2.5);
    }

    #[test]
    fn power_is_repeated_multiply_from_one() {
        let m = MatD::apply(&[2.0, -3.0, 0.5]);
        assert_eq!(m.power(2).toArray(), vec![4.0, 9.0, 0.25]);
        // n = 0 yields the 1.0 seed, matching the Scala loop.
        assert_eq!(m.power(0).toArray(), vec![1.0, 1.0, 1.0]);
    }

    #[test]
    fn cumsum_returns_a_row_whatever_the_input_shape() {
        let m = MatD::apply(&[1.0, 2.0, 3.0]);
        assert_eq!(m.shape(), (3, 1));
        let c = m.cumsum();
        assert_eq!(c.shape(), (1, 3), "Scala's cumsum always returns 1 x n");
        assert_eq!(c.toArray(), vec![1.0, 3.0, 6.0]);
    }

    #[test]
    fn head_and_tail_are_row_oriented() {
        let m = MatD::apply(&[1.0, 2.0, 3.0, 4.0]);
        assert_eq!(m.head(2).toArray(), vec![1.0, 2.0]);
        assert_eq!(m.tail(2).toArray(), vec![3.0, 4.0]);
        // Saturating, like Scala's min/max guards.
        assert_eq!(m.head(99).toArray(), m.toArray());
        assert_eq!(m.tail(99).toArray(), m.toArray());
    }

    #[test]
    fn scalar_on_the_left_is_not_commuted() {
        let m = MatD::apply(&[1.0, 4.0]);
        // Scala Mat.scala:658 — `1.0 - m` is `m.map(1.0 - _)`.
        assert_eq!((1.0 - &m).toArray(), vec![0.0, -3.0]);
        assert_eq!((&m - 1.0).toArray(), vec![0.0, 3.0]);
    }

    #[test]
    fn mul_between_matrices_is_elementwise() {
        let a = MatD::apply(&[2.0, 3.0]);
        let b = MatD::apply(&[5.0, 7.0]);
        assert_eq!((&a * &b).toArray(), vec![10.0, 21.0]);
    }

    #[test]
    fn mean_is_sum_over_count() {
        let a = probe(10_000);
        let m = MatD::apply(&a);
        assert_eq!(m.mean().to_bits(), (sum_d(&a) / 10_000.0).to_bits());
        assert_eq!(MatD::zeros(0, 0).mean(), 0.0);
    }
}

//! Axis reductions for [`MatD`] — Scala's `sum(axis)`, `mean(axis)`, `min(axis)`,
//! `max(axis)`, `std(axis)`, `cumsum(axis)`, `cummax(axis)`, `cummin(axis)` and the four
//! `rowSums`/`colSums`/`rowMeans`/`colMeans` shorthands.
//!
//! Split out of `mat.rs` only for length; these are members of `Mat` itself on the Scala
//! side, not of `MatDOps` (which is the *indexing* family — see `PARITY.md`).
//!
//! # Naming
//!
//! Scala overloads: `m.sum` and `m.sum(axis)` are the same name. Rust cannot, so the
//! axis forms take an `Axis` suffix per the `PARITY.md` contract — `sumAxis`, `meanAxis`,
//! `minAxis`, `maxAxis`, `stdAxis`, `cumsumAxis`. `cummax`/`cummin` keep their bare
//! names because Scala has no non-axis form of them.
//!
//! As in NumPy and Scala: **axis 0 reduces down the rows**, giving one value per column
//! (a 1×cols row); **axis 1 reduces across the columns**, giving one value per row (a
//! rows×1 column).
//!
//! # Association order
//!
//! None of these use the chunked `sumD`: each lane is a plain sequential fold in
//! increasing index order, whatever the layout. `stdAxis` follows the same rule -- its
//! per-lane mean is the one `meanAxis` returns -- so there is no exception left.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use rayon::prelude::*;

use crate::udata::mat::LANE_PARALLEL_THRESHOLD;
use crate::udata::mat::MatD;
use crate::udata::mat::gt_total;
use crate::udata::mat::lt_total;
use crate::udata::vecexts::CVecD;
use crate::udata::vecexts::RVecD;

/// Chunk cap for the lane split, mirroring `Mat.MaxSumChunks`.
const MAX_LANE_CHUNKS: usize = 16;

/// Runs `f(lane0, slot)` over `out` split into lane chunks, in parallel above
/// [`LANE_PARALLEL_THRESHOLD`]. Mirrors Scala's `overLanes`.
///
/// Splitting by lane is what keeps every lane's accumulation order intact, so any
/// reduction expressed through this is bit-identical to the sequential sweep.
fn over_lanes(total: usize, out: &mut [f64], f: impl Fn(usize, &mut [f64]) + Sync + Send) {
    let lanes = out.len();
    if total < LANE_PARALLEL_THRESHOLD || lanes < 8 {
        f(0, out);
    } else {
        let step = lanes.div_ceil(MAX_LANE_CHUNKS.min((lanes / 8).max(1)));
        out.par_chunks_mut(step)
            .enumerate()
            .for_each(|(k, slot)| f(k * step, slot));
    }
}

/// Panics unless `axis` is 0 or 1, mirroring Scala's `require`.
fn check(axis: usize) {
    assert!(axis == 0 || axis == 1, "axis must be 0 or 1, got {axis}");
}

/// Wraps a per-lane result in the shape Scala gives it: 1×n for axis 0, n×1 for axis 1.
fn shaped(out: Vec<f64>, axis: usize) -> MatD {
    let n = out.len();
    if axis == 0 {
        MatD::create(out, 1, n)
    } else {
        MatD::create(out, n, 1)
    }
}

impl MatD {
    /// Column sums for the columns starting at `c0`, accumulating into `slot`.
    ///
    /// Eight columns at a time with the accumulators in locals for the whole row sweep.
    /// The naive `slot[j] += at(i, j)` is a load, a load and a store per element --
    /// three times the traffic of a whole-matrix sum whose accumulator lives in a
    /// register, and it measured ~3.5x slower on the Scala side for exactly that reason.
    /// Each column still accumulates in increasing row order, so this is bit-identical.
    fn col_sums(&self, c0: usize, slot: &mut [f64]) {
        let n = slot.len();
        let mut jb = 0usize;
        while jb + 8 <= n {
            let (mut s0, mut s1, mut s2, mut s3) =
                (slot[jb], slot[jb + 1], slot[jb + 2], slot[jb + 3]);
            let (mut s4, mut s5, mut s6, mut s7) =
                (slot[jb + 4], slot[jb + 5], slot[jb + 6], slot[jb + 7]);
            for i in 0..self.rows() {
                let c = c0 + jb;
                s0 += self.at(i, c);
                s1 += self.at(i, c + 1);
                s2 += self.at(i, c + 2);
                s3 += self.at(i, c + 3);
                s4 += self.at(i, c + 4);
                s5 += self.at(i, c + 5);
                s6 += self.at(i, c + 6);
                s7 += self.at(i, c + 7);
            }
            slot[jb] = s0;
            slot[jb + 1] = s1;
            slot[jb + 2] = s2;
            slot[jb + 3] = s3;
            slot[jb + 4] = s4;
            slot[jb + 5] = s5;
            slot[jb + 6] = s6;
            slot[jb + 7] = s7;
            jb += 8;
        }
        while jb < n {
            let mut s = slot[jb];
            for i in 0..self.rows() {
                s += self.at(i, c0 + jb);
            }
            slot[jb] = s;
            jb += 1;
        }
    }

    /// Row sums for the rows starting at `r0`. Rows are independent sequential folds, so
    /// splitting by row is bit-identical by construction.
    fn row_sums(&self, r0: usize, slot: &mut [f64]) {
        for (k, out) in slot.iter_mut().enumerate() {
            let i = r0 + k;
            let mut acc = 0.0f64;
            for j in 0..self.cols() {
                acc += self.at(i, j);
            }
            *out = acc;
        }
    }

    /// The `(lane_count, lane_len)` pair for an axis: axis 0 has one lane per column,
    /// each as long as there are rows.
    fn lanes(&self, axis: usize) -> (usize, usize) {
        if axis == 0 {
            (self.cols(), self.rows())
        } else {
            (self.rows(), self.cols())
        }
    }

    /// Sums along `axis` — Scala's `sum(axis)`. Axis 0 gives a 1×cols row of column
    /// sums; axis 1 a rows×1 column of row sums.
    ///
    /// A plain sequential fold per lane, not `sumD`. Scala's contiguous fast path and
    /// its strided path visit elements in the same order and so agree bit for bit.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1.
    pub fn sumAxis(&self, axis: usize) -> Self {
        check(axis);
        let (rows, cols) = (self.rows(), self.cols());
        // Traversal order is row-major in BOTH cases, as Scala's is. Reducing down the
        // rows lane-by-lane would be a column-strided walk over row-major storage and
        // costs about 2x; carrying one accumulator per column instead visits memory in
        // order. Each lane still accumulates in increasing index order either way, so
        // this is a locality change and not an association-order change.
        let parallel = rows * cols >= LANE_PARALLEL_THRESHOLD;
        // Split by LANE -- columns for axis 0, rows for axis 1. That is the only split
        // that leaves every lane's accumulation order intact, so it is bit-preserving;
        // splitting the other way would combine partial sums and move the last ulp.
        let out = if axis == 0 {
            let mut acc = vec![0.0f64; cols];
            if parallel && cols >= 8 {
                let step = cols.div_ceil(MAX_LANE_CHUNKS.min((cols / 8).max(1)));
                acc.par_chunks_mut(step).enumerate().for_each(|(k, slot)| {
                    self.col_sums(k * step, slot);
                });
            } else {
                self.col_sums(0, &mut acc);
            }
            acc
        } else {
            let mut out = vec![0.0f64; rows];
            if parallel && rows >= 8 {
                let step = rows.div_ceil(MAX_LANE_CHUNKS.min((rows / 8).max(1)));
                out.par_chunks_mut(step).enumerate().for_each(|(k, slot)| {
                    self.row_sums(k * step, slot);
                });
            } else {
                self.row_sums(0, &mut out);
            }
            out
        };
        shaped(out, axis)
    }

    /// Means along `axis` — Scala's `mean(axis)`: `sum(axis)` divided by the lane length.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1.
    pub fn meanAxis(&self, axis: usize) -> Self {
        check(axis);
        let (_, len) = self.lanes(axis);
        &self.sumAxis(axis) / len as f64
    }

    /// Minima along `axis` — Scala's `min(axis)`.
    ///
    /// Seeded from the lane's first element and compared with `java_double_compare`,
    /// the ordering Scala's `Ordering[Double]` uses: `-0.0 < 0.0`, and NaN outranks
    /// every number. Ties keep their first occurrence.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1, or on an empty matrix.
    pub fn minAxis(&self, axis: usize) -> Self {
        self.extremumAxis(axis, lt_total)
    }

    /// Maxima along `axis` — Scala's `max(axis)`. Compared with `>`.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1, or on an empty matrix.
    pub fn maxAxis(&self, axis: usize) -> Self {
        self.extremumAxis(axis, gt_total)
    }

    /// The shape shared by [`MatD::minAxis`] and [`MatD::maxAxis`], which Scala writes
    /// as the same loop with `lt` swapped for `gt`.
    fn extremumAxis(&self, axis: usize, better: impl Fn(f64, f64) -> bool + Sync + Send) -> Self {
        check(axis);
        assert!(!self.isEmpty(), "min/max along an axis of an empty matrix");
        let (rows, cols) = (self.rows(), self.cols());
        let parallel = rows * cols >= LANE_PARALLEL_THRESHOLD;
        // Split by LANE, as the axis sums do: every lane still scans in increasing index
        // order and replaces only on a strict comparison, so ties keep their first
        // occurrence and the result is bit-identical to the sequential sweep.
        let out = if axis == 0 {
            let mut acc: Vec<f64> = (0..cols).map(|j| self.at(0, j)).collect();
            if parallel && cols >= 8 {
                let step = cols.div_ceil(MAX_LANE_CHUNKS.min((cols / 8).max(1)));
                acc.par_chunks_mut(step).enumerate().for_each(|(k, slot)| {
                    self.col_extrema(k * step, slot, &better);
                });
            } else {
                self.col_extrema(0, &mut acc, &better);
            }
            acc
        } else {
            let mut out = vec![0.0f64; rows];
            if parallel && rows >= 8 {
                let step = rows.div_ceil(MAX_LANE_CHUNKS.min((rows / 8).max(1)));
                out.par_chunks_mut(step).enumerate().for_each(|(k, slot)| {
                    self.row_extrema(k * step, slot, &better);
                });
            } else {
                self.row_extrema(0, &mut out, &better);
            }
            out
        };
        shaped(out, axis)
    }

    /// Running extremum down the columns starting at `c0`, seeded by the caller from
    /// row 0. Eight columns at a time with the accumulators in locals, as `col_sums`.
    fn col_extrema(
        &self,
        c0: usize,
        slot: &mut [f64],
        better: &(impl Fn(f64, f64) -> bool + Sync + Send),
    ) {
        let n = slot.len();
        let mut jb = 0usize;
        while jb + 8 <= n {
            let (mut s0, mut s1, mut s2, mut s3) =
                (slot[jb], slot[jb + 1], slot[jb + 2], slot[jb + 3]);
            let (mut s4, mut s5, mut s6, mut s7) =
                (slot[jb + 4], slot[jb + 5], slot[jb + 6], slot[jb + 7]);
            for i in 0..self.rows() {
                let c = c0 + jb;
                let v0 = self.at(i, c);
                let v1 = self.at(i, c + 1);
                let v2 = self.at(i, c + 2);
                let v3 = self.at(i, c + 3);
                let v4 = self.at(i, c + 4);
                let v5 = self.at(i, c + 5);
                let v6 = self.at(i, c + 6);
                let v7 = self.at(i, c + 7);
                if better(v0, s0) {
                    s0 = v0;
                }
                if better(v1, s1) {
                    s1 = v1;
                }
                if better(v2, s2) {
                    s2 = v2;
                }
                if better(v3, s3) {
                    s3 = v3;
                }
                if better(v4, s4) {
                    s4 = v4;
                }
                if better(v5, s5) {
                    s5 = v5;
                }
                if better(v6, s6) {
                    s6 = v6;
                }
                if better(v7, s7) {
                    s7 = v7;
                }
            }
            slot[jb] = s0;
            slot[jb + 1] = s1;
            slot[jb + 2] = s2;
            slot[jb + 3] = s3;
            slot[jb + 4] = s4;
            slot[jb + 5] = s5;
            slot[jb + 6] = s6;
            slot[jb + 7] = s7;
            jb += 8;
        }
        // Tail columns, one accumulator each.
        while jb < n {
            slot[jb] = self.col_extremum(c0 + jb, slot[jb], better);
            jb += 1;
        }
    }

    /// Running extremum down one column, seeded from `seed`.
    fn col_extremum(
        &self,
        col: usize,
        seed: f64,
        better: &(impl Fn(f64, f64) -> bool + Sync + Send),
    ) -> f64 {
        let mut s = seed;
        for i in 0..self.rows() {
            let v = self.at(i, col);
            if better(v, s) {
                s = v;
            }
        }
        s
    }

    /// Running extremum across the rows starting at `r0`. Rows are independent scans.
    fn row_extrema(
        &self,
        r0: usize,
        slot: &mut [f64],
        better: &(impl Fn(f64, f64) -> bool + Sync + Send),
    ) {
        for (k, out) in slot.iter_mut().enumerate() {
            let i = r0 + k;
            let mut s = self.at(i, 0);
            for j in 1..self.cols() {
                let v = self.at(i, j);
                if better(v, s) {
                    s = v;
                }
            }
            *out = s;
        }
    }

    /// Population standard deviations along `axis` — Scala's `std(axis)`.
    ///
    /// **This one is layout-dependent, and deliberately so.** Scala's two branches do
    /// not merely differ in speed: the contiguous branch takes each lane's mean with a
    /// plain fold, while the strided branch builds the lane into a fresh matrix and
    /// calls `std` on it — which takes the mean with the chunked `sumD` instead. For a
    /// lane of 8 or more elements those are different association orders, so a matrix
    /// and its transpose can report different standard deviations, in both languages
    /// alike. Reproduced rather than tidied up; see the layout note in `mat.rs`.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1.
    pub fn stdAxis(&self, axis: usize) -> Self {
        check(axis);
        let (rows, cols) = (self.rows(), self.cols());
        let total = rows * cols;
        // The per-lane mean is the one `meanAxis` returns: a single accumulator folded in
        // increasing index order. Both passes read the lane in place and split by lane,
        // so this is bit-identical to the scalar sweep it replaces.
        if axis == 0 {
            let n = rows as f64;
            let mut mu = vec![0.0f64; cols];
            over_lanes(total, &mut mu, |c0, slot| self.col_sums(c0, slot));
            for m in mu.iter_mut() {
                *m /= n;
            }
            let mut ss = vec![0.0f64; cols];
            over_lanes(total, &mut ss, |c0, slot| self.col_sq_dev(c0, &mu, slot));
            for s in ss.iter_mut() {
                *s = (*s / n).sqrt();
            }
            shaped(ss, axis)
        } else {
            let mut out = vec![0.0f64; rows];
            over_lanes(total, &mut out, |r0, slot| self.row_std(r0, slot));
            shaped(out, axis)
        }
    }

    /// Sum of squared deviations from `mu`, down the columns starting at `c0`.
    ///
    /// Eight columns at a time with the accumulators in locals, as `col_sums`: the naive
    /// form is a load and a read-modify-write per element. Each column still accumulates
    /// in increasing row order.
    fn col_sq_dev(&self, c0: usize, mu: &[f64], slot: &mut [f64]) {
        let n = slot.len();
        let mut jb = 0usize;
        while jb + 8 <= n {
            let c = c0 + jb;
            let mut s = [0.0f64; 8];
            for i in 0..self.rows() {
                for (k, acc) in s.iter_mut().enumerate() {
                    let d = self.at(i, c + k) - mu[c + k];
                    *acc += d * d;
                }
            }
            slot[jb..jb + 8].copy_from_slice(&s);
            jb += 8;
        }
        while jb < n {
            let c = c0 + jb;
            let mut acc = 0.0f64;
            for i in 0..self.rows() {
                let d = self.at(i, c) - mu[c];
                acc += d * d;
            }
            slot[jb] = acc;
            jb += 1;
        }
    }

    /// Per-row standard deviation for the rows starting at `r0`; already row-major, so
    /// this gains only the lane split.
    fn row_std(&self, r0: usize, slot: &mut [f64]) {
        let cols = self.cols();
        let n = cols as f64;
        for (k, out) in slot.iter_mut().enumerate() {
            let i = r0 + k;
            let mut acc = 0.0f64;
            for j in 0..cols {
                acc += self.at(i, j);
            }
            let mu = acc / n;
            let mut ss = 0.0f64;
            for j in 0..cols {
                let d = self.at(i, j) - mu;
                ss += d * d;
            }
            *out = (ss / n).sqrt();
        }
    }

    /// Running totals along `axis` — Scala's `cumsum(axis)`. Shape is preserved, unlike
    /// the axis-free [`MatD::cumsum`], which always returns 1×n.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1.
    pub fn cumsumAxis(&self, axis: usize) -> Self {
        self.scanAxis(axis, |acc, x| acc + x, 0.0)
    }

    /// Running maximum along `axis` — Scala's `cummax(axis)`. Shape is preserved.
    ///
    /// Scala seeds the accumulator from the lane's first cell and then compares under
    /// `Ordering[Double]`, so that first cell is written unconditionally and a NaN
    /// anywhere in a lane pins the running maximum from that point on.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1.
    pub fn cummax(&self, axis: usize) -> Self {
        self.scanFromFirst(axis, |acc, x| if gt_total(x, acc) { x } else { acc })
    }

    /// Running minimum along `axis` — Scala's `cummin(axis)`.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1.
    pub fn cummin(&self, axis: usize) -> Self {
        self.scanFromFirst(axis, |acc, x| if lt_total(x, acc) { x } else { acc })
    }

    /// Shape-preserving scan seeded from `init` — the `cumsum(axis)` shape.
    fn scanAxis(&self, axis: usize, step: impl Fn(f64, f64) -> f64, init: f64) -> Self {
        check(axis);
        let (rows, cols) = (self.rows(), self.cols());
        let mut out = vec![0.0f64; rows * cols];
        // Explicit per-axis loops. Routing both through `lane_at`/`lane_index` put a
        // branch on `axis` inside the inner loop, on every element.
        if axis == 0 {
            // One accumulator per column advanced row by row, so `out` is written in
            // row-major order rather than jumping `cols` elements per store. Each column
            // still accumulates in increasing row order.
            let mut acc = vec![init; cols];
            for i in 0..rows {
                let obase = i * cols;
                for (j, a) in acc.iter_mut().enumerate() {
                    *a = step(*a, self.at(i, j));
                    out[obase + j] = *a;
                }
            }
        } else {
            for i in 0..rows {
                let obase = i * cols;
                let mut acc = init;
                for j in 0..cols {
                    acc = step(acc, self.at(i, j));
                    out[obase + j] = acc;
                }
            }
        }
        Self::create(out, rows, cols)
    }

    /// Shape-preserving scan seeded from the lane's first element — the `cummax`/
    /// `cummin` shape, which differs from [`MatD::scanAxis`] only in the seed.
    fn scanFromFirst(&self, axis: usize, step: impl Fn(f64, f64) -> f64) -> Self {
        check(axis);
        if self.isEmpty() {
            return Self::create(Vec::new(), self.rows(), self.cols());
        }
        let (rows, cols) = (self.rows(), self.cols());
        let mut out = vec![0.0f64; rows * cols];
        if axis == 0 {
            // One accumulator per column, advanced row by row, rather than a
            // column-at-a-time outer loop. Each column's running extremum still advances
            // in increasing row order, so every cell is identical -- but `out` is written
            // in row-major order instead of jumping `cols` elements per store, which was
            // costing 4x against the Scala side.
            let mut acc: Vec<f64> = (0..cols).map(|j| self.at(0, j)).collect();
            for i in 0..rows {
                let obase = i * cols;
                for (j, a) in acc.iter_mut().enumerate() {
                    *a = step(*a, self.at(i, j));
                    out[obase + j] = *a;
                }
            }
        } else {
            for i in 0..rows {
                let obase = i * cols;
                let mut acc = self.at(i, 0);
                for j in 0..cols {
                    acc = step(acc, self.at(i, j));
                    out[obase + j] = acc;
                }
            }
        }
        Self::create(out, rows, cols)
    }

    /// Sum across columns → one sum per row — Scala's `rowSums`, i.e. `sum(1)`.
    pub fn rowSums(&self) -> CVecD {
        CVecD::fromMat(self.sumAxis(1))
    }

    /// Sum down rows → one sum per column — Scala's `colSums`, i.e. `sum(0)`.
    pub fn colSums(&self) -> RVecD {
        RVecD::fromMat(self.sumAxis(0))
    }

    /// Mean across columns → one mean per row — Scala's `rowMeans`, i.e. `mean(1)`.
    pub fn rowMeans(&self) -> CVecD {
        CVecD::fromMat(self.meanAxis(1))
    }

    /// Mean down rows → one mean per column — Scala's `colMeans`, i.e. `mean(0)`.
    pub fn colMeans(&self) -> RVecD {
        RVecD::fromMat(self.meanAxis(0))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 2×3, row-major:  1 2 3 / 4 5 6
    fn m() -> MatD {
        MatD::create(vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0], 2, 3)
    }

    #[test]
    fn axis_zero_reduces_down_the_rows() {
        let s = m().sumAxis(0);
        assert_eq!(s.shape(), (1, 3), "axis 0 yields one value per column");
        assert_eq!(s.toArray(), vec![5.0, 7.0, 9.0]);
        assert_eq!(m().meanAxis(0).toArray(), vec![2.5, 3.5, 4.5]);
    }

    #[test]
    fn axis_one_reduces_across_the_columns() {
        let s = m().sumAxis(1);
        assert_eq!(s.shape(), (2, 1), "axis 1 yields one value per row");
        assert_eq!(s.toArray(), vec![6.0, 15.0]);
        assert_eq!(m().meanAxis(1).toArray(), vec![2.0, 5.0]);
    }

    #[test]
    fn min_and_max_along_an_axis() {
        let m = MatD::create(vec![3.0, 1.0, 5.0, 0.0, 9.0, 2.0], 2, 3);
        assert_eq!(m.minAxis(0).toArray(), vec![0.0, 1.0, 2.0]);
        assert_eq!(m.maxAxis(0).toArray(), vec![3.0, 9.0, 5.0]);
        assert_eq!(m.minAxis(1).toArray(), vec![1.0, 0.0]);
        assert_eq!(m.maxAxis(1).toArray(), vec![5.0, 9.0]);
    }

    #[test]
    fn cumsum_along_an_axis_keeps_the_shape() {
        let c = m().cumsumAxis(1);
        assert_eq!(c.shape(), (2, 3));
        assert_eq!(c.toArray(), vec![1.0, 3.0, 6.0, 4.0, 9.0, 15.0]);
        assert_eq!(
            m().cumsumAxis(0).toArray(),
            vec![1.0, 2.0, 3.0, 5.0, 7.0, 9.0]
        );
    }

    #[test]
    fn cummax_and_cummin_run_along_the_lane() {
        let m = MatD::create(vec![1.0, 5.0, 2.0, 9.0, 3.0, 4.0], 2, 3);
        assert_eq!(m.cummax(1).toArray(), vec![1.0, 5.0, 5.0, 9.0, 9.0, 9.0]);
        assert_eq!(m.cummin(1).toArray(), vec![1.0, 1.0, 1.0, 9.0, 3.0, 3.0]);
        assert_eq!(m.cummax(0).toArray(), vec![1.0, 5.0, 2.0, 9.0, 5.0, 4.0]);
    }

    #[test]
    fn std_along_an_axis_matches_the_population_formula() {
        // Lanes of 2 are below the 8-way unroll, so both of Scala's mean algorithms
        // agree here and this is a plain correctness check.
        let s = m().stdAxis(0);
        assert_eq!(s.shape(), (1, 3));
        for (got, want) in s.toArray().into_iter().zip([1.5, 1.5, 1.5]) {
            assert!((got - want).abs() < 1e-12, "got {got}, want {want}");
        }
    }

    #[test]
    fn the_four_shorthands_carry_the_vector_orientation() {
        assert_eq!(m().rowSums().shape(), (2, 1));
        assert_eq!(m().colSums().shape(), (1, 3));
        assert_eq!(m().rowMeans().toArray(), vec![2.0, 5.0]);
        assert_eq!(m().colMeans().toArray(), vec![2.5, 3.5, 4.5]);
    }

    #[test]
    fn an_axis_reduction_reads_a_view_correctly() {
        // The transpose is a view, so this exercises the stride equation rather than
        // the flat buffer.
        let t = m().transpose();
        assert_eq!(t.shape(), (3, 2));
        assert_eq!(t.sumAxis(0).toArray(), vec![6.0, 15.0]);
        assert_eq!(t.sumAxis(1).toArray(), vec![5.0, 7.0, 9.0]);
    }
}

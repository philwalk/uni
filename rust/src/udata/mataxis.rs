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
//! None of these use the chunked `sumD`. Scala accumulates each lane with a plain
//! sequential fold, and its contiguous and strided branches visit elements in the same
//! order, so one implementation covers both. `stdAxis` is the exception and is
//! documented where it breaks the pattern.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use crate::udata::mat::MatD;
use crate::udata::mat::gt_total;
use crate::udata::mat::lt_total;
use crate::udata::mat::sum_d;
use crate::udata::vecexts::CVecD;
use crate::udata::vecexts::RVecD;

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
    /// The `(lane_count, lane_len)` pair for an axis: axis 0 has one lane per column,
    /// each as long as there are rows.
    fn lanes(&self, axis: usize) -> (usize, usize) {
        if axis == 0 {
            (self.cols(), self.rows())
        } else {
            (self.rows(), self.cols())
        }
    }

    /// Element `t` of lane `k` along `axis`.
    fn lane_at(&self, axis: usize, k: usize, t: usize) -> f64 {
        if axis == 0 {
            self.at(t, k)
        } else {
            self.at(k, t)
        }
    }

    /// Lane `k` along `axis`, gathered in order.
    fn lane(&self, axis: usize, k: usize) -> Vec<f64> {
        let (_, len) = self.lanes(axis);
        (0..len).map(|t| self.lane_at(axis, k, t)).collect()
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
        let out = if axis == 0 {
            let mut acc = vec![0.0f64; cols];
            for i in 0..rows {
                for (j, slot) in acc.iter_mut().enumerate() {
                    *slot += self.at(i, j);
                }
            }
            acc
        } else {
            (0..rows)
                .map(|i| {
                    let mut acc = 0.0f64;
                    for j in 0..cols {
                        acc += self.at(i, j);
                    }
                    acc
                })
                .collect()
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
    fn extremumAxis(&self, axis: usize, better: impl Fn(f64, f64) -> bool) -> Self {
        check(axis);
        assert!(!self.isEmpty(), "min/max along an axis of an empty matrix");
        let (rows, cols) = (self.rows(), self.cols());
        // Row-major in both cases, for the locality reason in `sumAxis`. Scala seeds
        // from row 0 / column 0 and replaces only on a strict Less/Greater under
        // `Ordering[Double]`, so ties keep their first occurrence -- and NaN, which
        // that ordering ranks above every number, DOES displace a max.
        let out = if axis == 0 {
            let mut acc: Vec<f64> = (0..cols).map(|j| self.at(0, j)).collect();
            for i in 1..rows {
                for (j, slot) in acc.iter_mut().enumerate() {
                    let current = self.at(i, j);
                    if better(current, *slot) {
                        *slot = current;
                    }
                }
            }
            acc
        } else {
            (0..rows)
                .map(|i| {
                    let mut acc = self.at(i, 0);
                    for j in 1..cols {
                        let current = self.at(i, j);
                        if better(current, acc) {
                            acc = current;
                        }
                    }
                    acc
                })
                .collect()
        };
        shaped(out, axis)
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
        let fast = self.fast_d();
        let (count, len) = self.lanes(axis);
        let n = len as f64;
        let out = (0..count)
            .map(|k| {
                let lane = self.lane(axis, k);
                let mu = if fast {
                    lane.iter().fold(0.0f64, |a, &b| a + b) / n
                } else {
                    sum_d(&lane) / n
                };
                let mut sum_sq = 0.0f64;
                for x in lane {
                    let d = x - mu;
                    sum_sq += d * d;
                }
                (sum_sq / n).sqrt()
            })
            .collect();
        shaped(out, axis)
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
        let (count, len) = self.lanes(axis);
        let mut out = vec![0.0f64; self.size()];
        for k in 0..count {
            let mut acc = init;
            for t in 0..len {
                acc = step(acc, self.lane_at(axis, k, t));
                out[self.lane_index(axis, k, t)] = acc;
            }
        }
        Self::create(out, self.rows(), self.cols())
    }

    /// Shape-preserving scan seeded from the lane's first element — the `cummax`/
    /// `cummin` shape, which differs from [`MatD::scanAxis`] only in the seed.
    fn scanFromFirst(&self, axis: usize, step: impl Fn(f64, f64) -> f64) -> Self {
        check(axis);
        if self.isEmpty() {
            return Self::create(Vec::new(), self.rows(), self.cols());
        }
        let (count, len) = self.lanes(axis);
        let mut out = vec![0.0f64; self.size()];
        for k in 0..count {
            let mut acc = self.lane_at(axis, k, 0);
            for t in 0..len {
                acc = step(acc, self.lane_at(axis, k, t));
                out[self.lane_index(axis, k, t)] = acc;
            }
        }
        Self::create(out, self.rows(), self.cols())
    }

    /// Row-major position in the *result* of element `t` of lane `k`.
    fn lane_index(&self, axis: usize, k: usize, t: usize) -> usize {
        if axis == 0 {
            t * self.cols() + k
        } else {
            k * self.cols() + t
        }
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

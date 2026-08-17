//! Pandas/NumPy-style ordering and descriptive statistics on `MatD` — Scala's
//! `MatPandasOps` and `RollingWindow`, Tier 3 phase (d).
//!
//! Everything here orders with `Ordering[Double]` — `java.lang.Double.compare` — as the
//! Scala does: every NaN sorts above `+∞`, `-0.0` below `0.0`, and equal-by-compare
//! elements are the same bits (NaN payloads apart). So `sort`, `argsort`, `unique`,
//! `nlargest`, `percentile`, `median`, `idxmin`/`idxmax` and `between` are all
//! reproduced bit for bit; the two stable sorts (`sortBy` there, `sort_by` here) keep
//! ties in index order alike, which is what makes `argsort` and `valueCounts` agree.
//!
//! `percentile` interpolates linearly between order statistics (`(p/100)·(n−1)`, floor,
//! `lo + f·(hi − lo)`) — NumPy's default `linear` method, in the Scala's exact
//! arithmetic. `pct_change` and the rolling window fill with NaN where pandas would.
//! `histogram` reproduces the Scala's two forms, including the `1/10000` step it invents
//! for a constant series and the binary search over supplied edges.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use std::cmp::Ordering;

use crate::udata::mat::MatD;
use crate::udata::mat::java_double_compare;
use crate::udata::matbool::MatBool;

fn sorted_java(mut v: Vec<f64>) -> Vec<f64> {
    v.sort_by(|a, b| java_double_compare(*a, *b));
    v
}

fn argsort_java(v: &[f64]) -> Vec<f64> {
    let mut idx: Vec<usize> = (0..v.len()).collect();
    idx.sort_by(|&a, &b| java_double_compare(v[a], v[b]));
    #[expect(clippy::cast_precision_loss, reason = "an index, exact below 2^53")]
    idx.into_iter().map(|i| i as f64).collect()
}

/// `MatPandasOps.percentileOf`: linear interpolation between order statistics.
fn percentile_of(arr: &[f64], p: f64) -> f64 {
    assert!(
        (0.0..=100.0).contains(&p),
        "percentile must be in [0,100], got {p}"
    );
    let sorted = sorted_java(arr.to_vec());
    let n = sorted.len();
    if n == 1 {
        return sorted[0];
    }
    #[expect(clippy::cast_precision_loss, reason = "a length")]
    let idx = (p / 100.0) * (n - 1) as f64;
    #[expect(
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss,
        reason = "idx ≥ 0, floored"
    )]
    let lo = idx as usize;
    let hi = (lo + 1).min(n - 1);
    #[expect(clippy::cast_precision_loss, reason = "an index")]
    let frac2 = idx - lo as f64;
    sorted[lo] + frac2 * (sorted[hi] - sorted[lo])
}

impl MatD {
    fn column(&self, j: usize) -> Vec<f64> {
        (0..self.rows()).map(|i| self.at(i, j)).collect()
    }
    fn row(&self, i: usize) -> Vec<f64> {
        (0..self.cols()).map(|j| self.at(i, j)).collect()
    }

    /// Row-major result from a per-column function (`axis 0`, result rows×cols filled
    /// column by column) or per-row function (`axis 1`).
    fn per_lane_full(&self, axis: usize, f: impl Fn(&[f64]) -> Vec<f64>) -> Self {
        assert!(
            axis == 0 || axis == 1,
            "axis must be -1, 0 or 1, got {axis}"
        );
        let (rows, cols) = self.shape();
        let mut result = vec![0.0; rows * cols];
        if axis == 0 {
            for j in 0..cols {
                let out = f(&self.column(j));
                for i in 0..rows {
                    result[i * cols + j] = out[i];
                }
            }
        } else {
            for i in 0..rows {
                let out = f(&self.row(i));
                result[i * cols..(i + 1) * cols].copy_from_slice(&out);
            }
        }
        Self::create(result, rows, cols)
    }

    /// One value per column (`axis 0` → 1×cols) or per row (`axis 1` → rows×1).
    fn per_lane_reduce(&self, axis: usize, f: impl Fn(&[f64]) -> f64) -> Self {
        assert!(axis == 0 || axis == 1, "axis must be 0 or 1, got {axis}");
        if axis == 0 {
            let data: Vec<f64> = (0..self.cols()).map(|j| f(&self.column(j))).collect();
            Self::create(data, 1, self.cols())
        } else {
            let data: Vec<f64> = (0..self.rows()).map(|i| f(&self.row(i))).collect();
            Self::create(data, self.rows(), 1)
        }
    }

    /// pandas `idxmin(axis)`: index of the smallest element per column (0) or row (1),
    /// first on ties, under `Ordering[Double]`; returned as a 1×cols / rows×1 matrix of
    /// indices.
    #[must_use]
    pub fn idxmin(&self, axis: usize) -> Self {
        self.per_lane_reduce(axis, |lane| best_index(lane, Ordering::Less))
    }

    /// pandas `idxmax(axis)`: index of the largest element per column (0) or row (1).
    #[must_use]
    pub fn idxmax(&self, axis: usize) -> Self {
        self.per_lane_reduce(axis, |lane| best_index(lane, Ordering::Greater))
    }

    /// `np.sort`: `axis = -1` sorts the flattened matrix into a 1×size row; `0` sorts each
    /// column, `1` each row. Scala's `sort(axis)`; `-1` is spelled `None` here.
    ///
    /// # Panics
    /// If `axis` is not `None`, 0 or 1.
    #[must_use]
    pub fn sort(&self, axis: Option<usize>) -> Self {
        match axis {
            None => {
                let s = sorted_java(self.flatten());
                let n = s.len();
                Self::create(s, 1, n)
            }
            Some(a) => self.per_lane_full(a, |lane| sorted_java(lane.to_vec())),
        }
    }

    /// `np.argsort`, same axis convention as [`MatD::sort`]; stable on ties. Indices come
    /// back as a matrix of exact integers.
    ///
    /// # Panics
    /// If `axis` is not `None`, 0 or 1.
    #[must_use]
    pub fn argsort(&self, axis: Option<usize>) -> Self {
        match axis {
            None => {
                let s = argsort_java(&self.flatten());
                let n = s.len();
                Self::create(s, 1, n)
            }
            Some(a) => self.per_lane_full(a, argsort_java),
        }
    }

    /// pandas `nlargest(n)`: the `n` largest elements, descending, as a 1×n row.
    #[must_use]
    pub fn nlargest(&self, n: usize) -> Self {
        let mut s = sorted_java(self.flatten());
        s.reverse();
        s.truncate(n.min(self.size()));
        let len = s.len();
        Self::create(s, 1, len)
    }

    /// pandas `nsmallest(n)`: the `n` smallest elements, ascending, as a 1×n row.
    #[must_use]
    pub fn nsmallest(&self, n: usize) -> Self {
        let mut s = sorted_java(self.flatten());
        s.truncate(n.min(self.size()));
        let len = s.len();
        Self::create(s, 1, len)
    }

    /// pandas `between(lo, hi)`: `lo <= x <= hi` under `Ordering[Double]` (so NaN is
    /// "between" anything and `+∞`… only when `hi` is NaN).
    #[must_use]
    pub fn between(&self, lo: f64, hi: f64) -> MatBool {
        let (rows, cols) = self.shape();
        let mut data = Vec::with_capacity(rows * cols);
        for i in 0..rows {
            for j in 0..cols {
                let x = self.at(i, j);
                data.push(
                    java_double_compare(x, lo) != Ordering::Less
                        && java_double_compare(x, hi) != Ordering::Greater,
                );
            }
        }
        MatBool::create(data, rows, cols)
    }

    /// `np.unique(m, return_counts=True)`: the distinct values ascending under
    /// `Ordering[Double]`, and how often each occurs.
    #[must_use]
    pub fn unique(&self) -> (Vec<f64>, Vec<usize>) {
        let sorted = sorted_java(self.flatten());
        let mut vals: Vec<f64> = Vec::new();
        let mut counts: Vec<usize> = Vec::new();
        for x in sorted {
            match vals.last() {
                Some(&last) if java_double_compare(x, last) == Ordering::Equal => {
                    if let Some(c) = counts.last_mut() {
                        *c += 1;
                    }
                }
                _ => {
                    vals.push(x);
                    counts.push(1);
                }
            }
        }
        (vals, counts)
    }

    /// pandas `nunique`.
    #[must_use]
    pub fn nunique(&self) -> usize {
        self.unique().0.len()
    }

    /// pandas `value_counts`: `(value, count)` pairs, most frequent first; ties keep the
    /// ascending value order (a stable sort by descending count, as the Scala's `sortBy`).
    #[must_use]
    pub fn valueCounts(&self) -> Vec<(f64, usize)> {
        let (vals, counts) = self.unique();
        let mut pairs: Vec<(f64, usize)> = vals.into_iter().zip(counts).collect();
        pairs.sort_by_key(|p| std::cmp::Reverse(p.1));
        pairs
    }

    /// `np.diff(m)`: first differences of the flattened matrix, as a 1×(size−1) row.
    ///
    /// # Panics
    /// On an empty matrix.
    #[must_use]
    pub fn diff(&self) -> Self {
        let flat = self.flatten();
        assert!(!flat.is_empty(), "diff of an empty matrix");
        let out: Vec<f64> = flat.windows(2).map(|w| w[1] - w[0]).collect();
        let n = out.len();
        Self::create(out, 1, n)
    }

    /// `np.diff(m, axis)`: differences along rows (0: (rows−1)×cols) or columns
    /// (1: rows×(cols−1)).
    ///
    /// # Panics
    /// If `axis` is not 0 or 1, or the axis has fewer than two entries.
    #[must_use]
    pub fn diffAxis(&self, axis: usize) -> Self {
        assert!(axis == 0 || axis == 1, "axis must be 0 or 1, got {axis}");
        let (rows, cols) = self.shape();
        if axis == 0 {
            assert!(rows > 1, "diff axis=0 requires at least 2 rows");
            let mut result = vec![0.0; (rows - 1) * cols];
            for i in 0..rows - 1 {
                for j in 0..cols {
                    result[i * cols + j] = self.at(i + 1, j) - self.at(i, j);
                }
            }
            Self::create(result, rows - 1, cols)
        } else {
            assert!(cols > 1, "diff axis=1 requires at least 2 cols");
            let mut result = vec![0.0; rows * (cols - 1)];
            for i in 0..rows {
                for j in 0..cols - 1 {
                    result[i * (cols - 1) + j] = self.at(i, j + 1) - self.at(i, j);
                }
            }
            Self::create(result, rows, cols - 1)
        }
    }

    /// pandas `shift(n)`: move rows (`axis 0`) or columns (`axis 1`) by `n` (negative:
    /// the other way), filling the vacated lanes with `fill`.
    #[must_use]
    pub fn shift(&self, n: i64, fill: f64, axis: usize) -> Self {
        let (rows, cols) = self.shape();
        let mut result = vec![fill; rows * cols];
        let mag = n.unsigned_abs() as usize;
        if axis == 0 {
            let (src, dst) = if n >= 0 { (0, mag) } else { (mag, 0) };
            if rows > mag {
                for i in 0..rows - mag {
                    for j in 0..cols {
                        result[(dst + i) * cols + j] = self.at(src + i, j);
                    }
                }
            }
        } else {
            let (src, dst) = if n >= 0 { (0, mag) } else { (mag, 0) };
            if cols > mag {
                for i in 0..rows {
                    for j in 0..cols - mag {
                        result[i * cols + dst + j] = self.at(i, src + j);
                    }
                }
            }
        }
        Self::create(result, rows, cols)
    }

    /// pandas `pct_change(axis)`: `(x − prev) / prev` against the previous row (0) or
    /// column (1); NaN where there is no previous value or it is zero.
    #[must_use]
    pub fn pct_change(&self, axis: usize) -> Self {
        let prev = self.shift(1, f64::NAN, axis);
        let (rows, cols) = self.shape();
        let mut result = vec![0.0; rows * cols];
        for i in 0..rows {
            for j in 0..cols {
                let p = prev.at(i, j);
                result[i * cols + j] = if p.is_nan() || p == 0.0 {
                    f64::NAN
                } else {
                    (self.at(i, j) - p) / p
                };
            }
        }
        Self::create(result, rows, cols)
    }

    /// `np.percentile(m, p)` over all elements, `p` in `[0, 100]`.
    ///
    /// # Panics
    /// If `p` is outside `[0, 100]`.
    #[must_use]
    pub fn percentile(&self, p: f64) -> f64 {
        percentile_of(&self.flatten(), p)
    }

    /// `np.median(m)` over all elements.
    #[must_use]
    pub fn median(&self) -> f64 {
        percentile_of(&self.flatten(), 50.0)
    }

    /// `np.percentile(m, p, axis)`: per column (0 → 1×cols) or row (1 → rows×1).
    ///
    /// # Panics
    /// If `axis` is not 0 or 1 or `p` is outside `[0, 100]`.
    #[must_use]
    pub fn percentileAxis(&self, p: f64, axis: usize) -> Self {
        self.per_lane_reduce(axis, |lane| percentile_of(lane, p))
    }

    /// `np.median(m, axis)`.
    #[must_use]
    pub fn medianAxis(&self, axis: usize) -> Self {
        self.percentileAxis(50.0, axis)
    }

    /// pandas `describe()` per column: the row labels and an 8×cols matrix —
    /// `count mean std min 25% 50% 75% max`.
    #[must_use]
    pub fn describe(&self) -> (Vec<&'static str>, Self) {
        #[expect(clippy::cast_precision_loss, reason = "a row count")]
        let count = Self::create(vec![self.rows() as f64; self.cols()], 1, self.cols());
        let rows = [
            count,
            self.meanAxis(0),
            self.stdAxis(0),
            self.minAxis(0),
            self.percentileAxis(25.0, 0),
            self.medianAxis(0),
            self.percentileAxis(75.0, 0),
            self.maxAxis(0),
        ];
        let refs: Vec<&Self> = rows.iter().collect();
        (
            vec!["count", "mean", "std", "min", "25%", "50%", "75%", "max"],
            Self::vstack(&refs),
        )
    }

    /// pandas `rolling(window)`: a [`RollingWindow`] over the rows of each column.
    ///
    /// # Panics
    /// If `window` is zero.
    #[must_use]
    pub fn rolling(&self, window: usize) -> RollingWindow<'_> {
        assert!(window >= 1, "window must be >= 1, got {window}");
        RollingWindow { mat: self, window }
    }

    /// `np.histogram(m, bins, range)`: `(counts, edges)`, `bins + 1` edges. Without a
    /// range the data's min/max bound it; a constant series gets `1/10000` steps and
    /// everything in the first bin; the top edge is inclusive.
    ///
    /// # Panics
    /// If `bins` is zero.
    #[must_use]
    pub fn histogram(&self, bins: usize, range: Option<(f64, f64)>) -> (Vec<usize>, Vec<f64>) {
        assert!(bins > 0, "bins must be positive");
        let data = self.flatten();
        if data.is_empty() {
            return (vec![0; bins], vec![0.0; bins + 1]);
        }
        let (min_val, max_val) = range.unwrap_or_else(|| {
            let (mut mi, mut ma) = (data[0], data[0]);
            for &v in &data {
                if v < mi {
                    mi = v;
                }
                if v > ma {
                    ma = v;
                }
            }
            (mi, ma)
        });
        #[expect(clippy::cast_precision_loss, reason = "bin indices")]
        if min_val == max_val {
            let step = 1.0 / 10000.0;
            let edges: Vec<f64> = (0..=bins).map(|i| min_val + i as f64 * step).collect();
            let mut counts = vec![0; bins];
            counts[0] = data.len();
            return (counts, edges);
        }
        let bin_width = (max_val - min_val) / bins as f64;
        #[expect(clippy::cast_precision_loss, reason = "bin indices")]
        let edges: Vec<f64> = (0..=bins)
            .map(|i| {
                if i == bins {
                    max_val
                } else {
                    min_val + i as f64 * bin_width
                }
            })
            .collect();
        let mut counts = vec![0; bins];
        for &value in &data {
            if value >= min_val && value <= max_val {
                if value == max_val {
                    counts[bins - 1] += 1;
                } else {
                    #[expect(
                        clippy::cast_possible_truncation,
                        reason = "a bin index, clamped below"
                    )]
                    let idx = ((value - min_val) / bin_width) as i64;
                    let clamped = idx.clamp(0, i64::try_from(bins - 1).unwrap_or(i64::MAX));
                    counts[usize::try_from(clamped).unwrap_or(0)] += 1;
                }
            }
        }
        (counts, edges)
    }

    /// `np.histogram(m, bins=edges)`: counts against supplied ascending edges (`edges[i]
    /// <= x < edges[i+1]`, the last edge inclusive), by binary search.
    ///
    /// # Panics
    /// With fewer than two edges.
    #[must_use]
    pub fn histogramEdges(&self, edges: &[f64]) -> (Vec<usize>, Vec<f64>) {
        assert!(edges.len() >= 2, "binEdges must have at least 2 elements");
        let num_bins = edges.len() - 1;
        let mut counts = vec![0; num_bins];
        let (first, last) = (edges[0], edges[num_bins]);
        for value in self.flatten() {
            if value == last {
                counts[num_bins - 1] += 1;
            } else if value >= first && value < last {
                let (mut left, mut right) = (0, num_bins - 1);
                while left < right {
                    let mid = left + (right - left).div_ceil(2);
                    if edges[mid] <= value {
                        left = mid;
                    } else {
                        right = mid - 1;
                    }
                }
                counts[left] += 1;
            }
        }
        (counts, edges.to_vec())
    }
}

/// Index of the extreme element of `lane` under `Ordering[Double]`, first on ties.
fn best_index(lane: &[f64], want: Ordering) -> f64 {
    let mut best = 0;
    for (i, &v) in lane.iter().enumerate().skip(1) {
        if java_double_compare(v, lane[best]) == want {
            best = i;
        }
    }
    #[expect(clippy::cast_precision_loss, reason = "an index")]
    let b = best as f64;
    b
}

/// Scala's `RollingWindow`: statistics over a trailing window of `window` rows in each
/// column; the first `window − 1` rows of the result are NaN.
pub struct RollingWindow<'a> {
    mat: &'a MatD,
    window: usize,
}

impl RollingWindow<'_> {
    fn roll(&self, f: impl Fn(&[f64]) -> f64) -> MatD {
        let (rows, cols) = self.mat.shape();
        let mut result = vec![f64::NAN; rows * cols];
        for j in 0..cols {
            let mut i = self.window - 1;
            while i < rows {
                let arr: Vec<f64> = (0..self.window)
                    .map(|k| self.mat.at(i + 1 - self.window + k, j))
                    .collect();
                result[i * cols + j] = f(&arr);
                i += 1;
            }
        }
        MatD::create(result, rows, cols)
    }

    /// Rolling mean: a sequential sum over the window, then `/ window`.
    #[must_use]
    pub fn mean(&self) -> MatD {
        #[expect(clippy::cast_precision_loss, reason = "a window length")]
        self.roll(|w| w.iter().fold(0.0, |s, x| s + x) / w.len() as f64)
    }

    /// Rolling sum, sequential.
    #[must_use]
    pub fn sum(&self) -> MatD {
        self.roll(|w| w.iter().fold(0.0, |s, x| s + x))
    }

    /// Rolling min under `Ordering[Double]`.
    #[must_use]
    pub fn min(&self) -> MatD {
        self.roll(|w| {
            let mut best = w[0];
            for &x in &w[1..] {
                if java_double_compare(x, best) == Ordering::Less {
                    best = x;
                }
            }
            best
        })
    }

    /// Rolling max under `Ordering[Double]`.
    #[must_use]
    pub fn max(&self) -> MatD {
        self.roll(|w| {
            let mut best = w[0];
            for &x in &w[1..] {
                if java_double_compare(x, best) == Ordering::Greater {
                    best = x;
                }
            }
            best
        })
    }

    /// Rolling population standard deviation: sequential mean, then sequential sum of
    /// squared deviations, `/ window`, `sqrt`.
    #[must_use]
    pub fn std(&self) -> MatD {
        #[expect(clippy::cast_precision_loss, reason = "a window length")]
        self.roll(|w| {
            let n = w.len() as f64;
            let mu = w.iter().fold(0.0, |s, x| s + x) / n;
            let sq = w.iter().fold(0.0, |s, x| {
                let d = x - mu;
                s + d * d
            });
            (sq / n).sqrt()
        })
    }
}

#[cfg(test)]
mod tests {
    use crate::udata::mat::MatD;

    fn m() -> MatD {
        MatD::create(vec![3.0, 1.0, 2.0, 1.0, 5.0, 4.0, 3.0, 3.0, 0.0], 3, 3)
    }

    #[test]
    fn sort_argsort_unique() {
        assert_eq!(
            m().sort(None).flatten(),
            vec![0.0, 1.0, 1.0, 2.0, 3.0, 3.0, 3.0, 4.0, 5.0]
        );
        assert_eq!(
            m().sort(Some(0)).flatten(),
            vec![1.0, 1.0, 0.0, 3.0, 3.0, 2.0, 3.0, 5.0, 4.0]
        );
        assert_eq!(
            m().sort(Some(1)).flatten(),
            vec![1.0, 2.0, 3.0, 1.0, 4.0, 5.0, 0.0, 3.0, 3.0]
        );
        assert_eq!(
            m().argsort(None).flatten(),
            vec![8.0, 1.0, 3.0, 2.0, 0.0, 6.0, 7.0, 5.0, 4.0]
        );
        assert_eq!(
            m().argsort(Some(1)).flatten(),
            vec![1.0, 2.0, 0.0, 0.0, 2.0, 1.0, 2.0, 0.0, 1.0]
        );
        let (vals, counts) = m().unique();
        assert_eq!(vals, vec![0.0, 1.0, 2.0, 3.0, 4.0, 5.0]);
        assert_eq!(counts, vec![1, 2, 1, 3, 1, 1]);
        assert_eq!(m().nunique(), 6);
        assert_eq!(m().valueCounts()[0], (3.0, 3));
        assert_eq!(m().valueCounts()[1], (1.0, 2));
        assert_eq!(m().nlargest(2).flatten(), vec![5.0, 4.0]);
        assert_eq!(m().nsmallest(2).flatten(), vec![0.0, 1.0]);
    }

    #[test]
    fn sort_orders_like_java_compare() {
        let nan = MatD::create(vec![1.0, f64::NAN, -0.0, 0.0], 1, 4);
        let s = nan.sort(None).flatten();
        assert_eq!(s[0].to_bits(), (-0.0f64).to_bits());
        assert_eq!(s[1].to_bits(), 0.0f64.to_bits());
        assert!(s[3].is_nan());
    }

    #[test]
    fn idx_between_diff_shift_pct() {
        assert_eq!(m().idxmin(0).flatten(), vec![1.0, 0.0, 2.0]);
        assert_eq!(m().idxmax(1).flatten(), vec![0.0, 1.0, 0.0]);
        assert_eq!(
            m().between(1.0, 3.0).toArray(),
            vec![true, true, true, true, false, false, true, true, false]
        );
        assert_eq!(
            m().diff().flatten(),
            vec![-2.0, 1.0, -1.0, 4.0, -1.0, -1.0, 0.0, -3.0]
        );
        assert_eq!(
            m().diffAxis(0).flatten(),
            vec![-2.0, 4.0, 2.0, 2.0, -2.0, -4.0]
        );
        assert_eq!(
            m().diffAxis(1).flatten(),
            vec![-2.0, 1.0, 4.0, -1.0, 0.0, -3.0]
        );
        let sh = m().shift(1, -9.0, 0);
        assert_eq!(
            sh.flatten(),
            vec![-9.0, -9.0, -9.0, 3.0, 1.0, 2.0, 1.0, 5.0, 4.0]
        );
        let sh = m().shift(-1, -9.0, 1);
        assert_eq!(
            sh.flatten(),
            vec![1.0, 2.0, -9.0, 5.0, 4.0, -9.0, 3.0, 0.0, -9.0]
        );
        let pc = m().pct_change(0).flatten();
        assert!(pc[..3].iter().all(|v| v.is_nan()));
        assert_eq!(pc[3], (1.0 - 3.0) / 3.0);
        assert!(pc[8].is_nan() || pc[8] == (0.0 - 4.0) / 4.0);
    }

    #[test]
    fn percentiles_describe_rolling_histogram() {
        assert_eq!(m().median(), 3.0);
        assert_eq!(m().percentile(0.0), 0.0);
        assert_eq!(m().percentile(100.0), 5.0);
        assert_eq!(m().percentile(25.0), 1.0);
        assert_eq!(m().percentileAxis(50.0, 0).flatten(), vec![3.0, 3.0, 2.0]);
        assert_eq!(m().medianAxis(1).flatten(), vec![2.0, 4.0, 3.0]);
        let (labels, d) = m().describe();
        assert_eq!(labels.len(), 8);
        assert_eq!(d.shape(), (8, 3));
        assert_eq!(d.at(0, 0), 3.0);
        assert_eq!(d.at(7, 1), 5.0);
    }

    #[test]
    fn rolling_window() {
        let mm = m();
        let r = mm.rolling(2);
        let rm = r.mean().flatten();
        assert!(rm[..3].iter().all(|v| v.is_nan()));
        assert_eq!(&rm[3..], &[2.0, 3.0, 3.0, 2.0, 4.0, 2.0]);
        assert_eq!(&r.sum().flatten()[3..], &[4.0, 6.0, 6.0, 4.0, 8.0, 4.0]);
        assert_eq!(&r.max().flatten()[3..], &[3.0, 5.0, 4.0, 3.0, 5.0, 4.0]);
        assert_eq!(&r.min().flatten()[3..], &[1.0, 1.0, 2.0, 1.0, 3.0, 0.0]);
        assert_eq!(&r.std().flatten()[3..], &[1.0, 2.0, 1.0, 1.0, 1.0, 2.0]);
    }

    #[test]
    fn histograms() {
        let (counts, edges) = m().histogram(5, None);
        assert_eq!(edges.len(), 6);
        assert_eq!(counts.iter().sum::<usize>(), 9);
        assert_eq!(counts, vec![1, 2, 1, 3, 2]);
        let (c2, _) = m().histogramEdges(&[0.0, 2.0, 4.0, 5.0]);
        assert_eq!(c2, vec![3, 4, 2]);
        let (c3, e3) = MatD::create(vec![2.0; 4], 2, 2).histogram(3, None);
        assert_eq!(c3, vec![4, 0, 0]);
        assert_eq!(e3[1], 2.0 + 1.0 / 10000.0);
    }
}

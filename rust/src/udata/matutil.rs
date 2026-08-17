//! The remaining `Mat` conveniences on `MatD` — Scala's element-wise pairs, the
//! broadcast helpers spelled by name, the split/tile/repeat family, the row/column
//! mapping closures, `scale`, and `saveCSV`.
//!
//! Every method here is a port of the Scala loop, so its bits agree with the JVM. Two
//! things worth knowing before reading the code:
//!
//! - `maximum`/`minimum` compare with `Ordering[Double]`, i.e. `java.lang.Double.compare`
//!   ([`java_double_compare`]): every NaN is greater than `+∞`, `-0.0 < 0.0`. So
//!   `maximum(1.0, NaN)` is NaN and `minimum(NaN, 1.0)` is `1.0` — the Scala's answers,
//!   which are not `np.maximum`'s (NaN both ways). Recorded, not corrected: these two are
//!   pinned to Scala, and the IEEE mask family is where NumPy semantics were adopted.
//! - `scale` squares with `x·x`, which is what `Math.pow(x, 2.0)` is on HotSpot (the
//!   intrinsic special-cases the exponent) — so `~^ 2.0` on the Scala side and `x*x` here
//!   are the same bits. `powerF` (Scala's `power(n: Double)`) is a genuine `pow` and
//!   inherits the libm situation documented in `PARITY.md`.
//!
//! Where Scala takes a closure over a `RowVec`/`ColVec` (`mapRows`, `mapCols`,
//! `filterRows`, `applyAlongAxis`, `zipMap`, `exists`, `where`), the Rust takes an
//! `impl Fn` over the corresponding 1×n / n×1 `MatD` — the ported line reads the same.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use std::cmp::Ordering;

use crate::udata::big::java_double_to_string;
use crate::udata::mat::MatD;
use crate::udata::mat::java_double_compare;
use crate::upath::ext::UPath;

impl MatD {
    fn same_shape(&self, other: &Self, what: &str) {
        assert!(
            self.shape() == other.shape(),
            "{what}: Shape mismatch: {}x{} vs {}x{}",
            self.rows(),
            self.cols(),
            other.rows(),
            other.cols()
        );
    }

    fn zip_with(&self, other: &Self, f: impl Fn(f64, f64) -> f64) -> Self {
        let (rows, cols) = self.shape();
        let mut data = Vec::with_capacity(rows * cols);
        for r in 0..rows {
            for c in 0..cols {
                data.push(f(self.at(r, c), other.at(r, c)));
            }
        }
        Self::create(data, rows, cols)
    }

    /// `np.maximum(m, other)` under `Ordering[Double]` — see the module note.
    ///
    /// # Panics
    /// On a shape mismatch.
    #[must_use]
    pub fn maximum(&self, other: &Self) -> Self {
        self.same_shape(other, "maximum");
        self.zip_with(other, |a, b| {
            if java_double_compare(a, b) == Ordering::Greater {
                a
            } else {
                b
            }
        })
    }

    /// `np.minimum(m, other)` under `Ordering[Double]` — see the module note.
    ///
    /// # Panics
    /// On a shape mismatch.
    #[must_use]
    pub fn minimum(&self, other: &Self) -> Self {
        self.same_shape(other, "minimum");
        self.zip_with(other, |a, b| {
            if java_double_compare(a, b) == Ordering::Less {
                a
            } else {
                b
            }
        })
    }

    /// `np.maximum(m, scalar)` — Scala's `maximum(scalar: T)`.
    #[must_use]
    pub fn maximumScalar(&self, scalar: f64) -> Self {
        self.map_elems(move |v| {
            if java_double_compare(v, scalar) == Ordering::Greater {
                v
            } else {
                scalar
            }
        })
    }

    /// `np.minimum(m, scalar)` — Scala's `minimum(scalar: T)`.
    #[must_use]
    pub fn minimumScalar(&self, scalar: f64) -> Self {
        self.map_elems(move |v| {
            if java_double_compare(v, scalar) == Ordering::Less {
                v
            } else {
                scalar
            }
        })
    }

    /// `np.sign`: −1, 0 or 1 by `<`/`>` against zero (so NaN → 0, as the Scala's
    /// `Numeric` comparisons give).
    #[must_use]
    pub fn sign(&self) -> Self {
        self.map_elems(|x| {
            if x < 0.0 {
                -1.0
            } else if x > 0.0 {
                1.0
            } else {
                0.0
            }
        })
    }

    /// `np.round(m, decimals)`: `Math.round(x · 10^d) / 10^d` — Java's `round` is
    /// `floor(x + 0.5)` on the double, saturating to `i64`.
    #[must_use]
    pub fn round(&self, decimals: i32) -> Self {
        let scale = 10f64.powi(decimals);
        self.map_elems(move |x| java_math_round(x * scale) / scale)
    }

    /// `np.power(m, n)` for a real exponent — Scala's `power(n: Double)`, i.e.
    /// `Math.pow` per element. Not pinned across runtimes (libm); the integer
    /// [`MatD::power`] is.
    #[must_use]
    pub fn powerF(&self, n: f64) -> Self {
        self.map_elems(move |x| x.powf(n))
    }

    /// Element-wise product, spelled — Scala's `hadamard` (`m *:* other`).
    ///
    /// # Panics
    /// On a shape mismatch.
    #[must_use]
    pub fn hadamard(&self, other: &Self) -> Self {
        self * other
    }

    /// `np.allclose(m, other, rtol, atol)`: `|a − b| ≤ atol + rtol·|b|` everywhere;
    /// `false` on a shape mismatch. NaN never passes.
    #[must_use]
    pub fn allclose(&self, other: &Self, rtol: f64, atol: f64) -> bool {
        if self.shape() != other.shape() {
            return false;
        }
        for i in 0..self.rows() {
            for j in 0..self.cols() {
                let (a, b) = (self.at(i, j), other.at(i, j));
                if (a - b).abs() > atol + rtol * b.abs() {
                    return false;
                }
            }
        }
        true
    }

    /// Whether any element is NaN — Scala's `containsNaN`.
    #[must_use]
    pub fn containsNaN(&self) -> bool {
        self.exists(f64::is_nan)
    }

    /// Whether any element satisfies `p`, in row-major order — Scala's `exists`.
    pub fn exists(&self, p: impl Fn(f64) -> bool) -> bool {
        for i in 0..self.rows() {
            for j in 0..self.cols() {
                if p(self.at(i, j)) {
                    return true;
                }
            }
        }
        false
    }

    /// The `(row, col)` positions whose element satisfies `pred`, row-major — Scala's
    /// `where(pred)`.
    pub fn wherePred(&self, pred: impl Fn(f64) -> bool) -> Vec<(usize, usize)> {
        let mut out = Vec::new();
        for i in 0..self.rows() {
            for j in 0..self.cols() {
                if pred(self.at(i, j)) {
                    out.push((i, j));
                }
            }
        }
        out
    }

    /// `np.nan_to_num`: NaN → `nan`, `+∞` → `posinf`, `−∞` → `neginf`.
    #[must_use]
    pub fn nanToNum(&self, nan: f64, posinf: f64, neginf: f64) -> Self {
        self.map_elems(move |d| {
            if d.is_nan() {
                nan
            } else if d == f64::INFINITY {
                posinf
            } else if d == f64::NEG_INFINITY {
                neginf
            } else {
                d
            }
        })
    }

    /// `2` — Scala's `ndim`.
    #[must_use]
    pub const fn ndim(&self) -> usize {
        2
    }

    /// The matrix itself when it is already a contiguous, offset-0 layout, else a
    /// row-major copy — Scala's `toContiguous`.
    #[must_use]
    pub fn toContiguous(&self) -> Self {
        if self.isContiguous() && self.strided().1 == 0 {
            self.clone()
        } else {
            let (r, c) = self.shape();
            Self::create(self.flatten(), r, c)
        }
    }

    /// Element-wise `f(a, b)` over two same-shape matrices — Scala's `zipMap`.
    ///
    /// # Panics
    /// On a shape mismatch.
    #[must_use]
    pub fn zipMap(&self, other: &Self, f: impl Fn(f64, f64) -> f64) -> Self {
        self.same_shape(other, "zipMap");
        self.zip_with(other, f)
    }

    // ── Rows and columns as vectors ─────────────────────────────────────────────

    /// Row `i` as a 1×cols view (Scala's `m(i, ::)`).
    fn row_view(&self, i: usize) -> Self {
        let (r, c) = (i as i64, self.cols() as i64);
        self.slice(r..r + 1, 0..c)
    }

    /// Column `j` as a rows×1 view (Scala's `m(::, j)`).
    fn col_view(&self, j: usize) -> Self {
        let (r, c) = (self.rows() as i64, j as i64);
        self.slice(0..r, c..c + 1)
    }

    /// Breeze `X(*, ::).map(f)`: `f` on every row (a 1×n view), rows reassembled
    /// vertically; the width of the result is what `f` returns for the first row.
    #[must_use]
    pub fn mapRows(&self, f: impl Fn(&Self) -> Self) -> Self {
        if self.rows() == 0 {
            return Self::create(Vec::new(), 0, 0);
        }
        let rows: Vec<Self> = (0..self.rows()).map(|i| f(&self.row_view(i))).collect();
        let ncols = rows[0].cols();
        let mut result = vec![0.0; self.rows() * ncols];
        for (i, row) in rows.iter().enumerate() {
            for j in 0..ncols {
                result[i * ncols + j] = row.at(0, j);
            }
        }
        Self::create(result, self.rows(), ncols)
    }

    /// Breeze `X(::, *).map(f)`: `f` on every column (an n×1 view), columns reassembled
    /// horizontally; the height of the result is what `f` returns for the first column.
    #[must_use]
    pub fn mapCols(&self, f: impl Fn(&Self) -> Self) -> Self {
        if self.cols() == 0 {
            return Self::create(Vec::new(), 0, 0);
        }
        let cols: Vec<Self> = (0..self.cols()).map(|j| f(&self.col_view(j))).collect();
        let nrows = cols[0].rows();
        let mut result = vec![0.0; nrows * self.cols()];
        for (j, col) in cols.iter().enumerate() {
            for i in 0..nrows {
                result[i * self.cols() + j] = col.at(i, 0);
            }
        }
        Self::create(result, nrows, self.cols())
    }

    /// The rows for which `p(row)` holds, in order — Scala's `filterRows`. An empty
    /// selection is `0×cols`.
    #[must_use]
    pub fn filterRows(&self, p: impl Fn(&Self) -> bool) -> Self {
        let cols = self.cols();
        let keep: Vec<usize> = (0..self.rows()).filter(|&r| p(&self.row_view(r))).collect();
        let mut data = Vec::with_capacity(keep.len() * cols);
        for &r in &keep {
            for j in 0..cols {
                data.push(self.at(r, j));
            }
        }
        Self::create(data, keep.len(), cols)
    }

    /// `np.apply_along_axis`: `fn` reduces each column (axis 0 → 1×cols) or each row
    /// (axis 1 → rows×1).
    ///
    /// # Panics
    /// If `axis` is not 0 or 1.
    #[must_use]
    pub fn applyAlongAxis(&self, f: impl Fn(&Self) -> f64, axis: usize) -> Self {
        assert!(axis == 0 || axis == 1, "axis must be 0 or 1, got {axis}");
        if axis == 0 {
            let data: Vec<f64> = (0..self.cols()).map(|j| f(&self.col_view(j))).collect();
            Self::create(data, 1, self.cols())
        } else {
            let data: Vec<f64> = (0..self.rows()).map(|i| f(&self.row_view(i))).collect();
            Self::create(data, self.rows(), 1)
        }
    }

    // ── Broadcast helpers, by name ──────────────────────────────────────────────

    fn each_row(&self, v: &Self, what: &str, f: impl Fn(f64, f64) -> f64) -> Self {
        assert!(
            v.size() == self.cols(),
            "{what}: row vector length {} must match matrix cols {}",
            v.size(),
            self.cols()
        );
        let vf = v.flatten();
        let (rows, cols) = self.shape();
        let mut result = vec![0.0; rows * cols];
        for i in 0..rows {
            for j in 0..cols {
                result[i * cols + j] = f(self.at(i, j), vf[j]);
            }
        }
        Self::create(result, rows, cols)
    }

    fn each_col(&self, v: &Self, what: &str, f: impl Fn(f64, f64) -> f64) -> Self {
        assert!(
            v.size() == self.rows(),
            "{what}: col vector length {} must match matrix rows {}",
            v.size(),
            self.rows()
        );
        let vf = v.flatten();
        let (rows, cols) = self.shape();
        let mut result = vec![0.0; rows * cols];
        for i in 0..rows {
            for j in 0..cols {
                result[i * cols + j] = f(self.at(i, j), vf[i]);
            }
        }
        Self::create(result, rows, cols)
    }

    /// `m + v` for a row vector `v`, spelled — Scala's `addToEachRow`.
    ///
    /// # Panics
    /// If `v` does not have `cols` elements.
    #[must_use]
    pub fn addToEachRow(&self, v: &Self) -> Self {
        self.each_row(v, "addToEachRow", |a, b| a + b)
    }
    /// `m + v` for a column vector `v` — Scala's `addToEachCol`.
    ///
    /// # Panics
    /// If `v` does not have `rows` elements.
    #[must_use]
    pub fn addToEachCol(&self, v: &Self) -> Self {
        self.each_col(v, "addToEachCol", |a, b| a + b)
    }
    /// `m − v` for a row vector `v` — Scala's `subFromEachRow`.
    ///
    /// # Panics
    /// If `v` does not have `cols` elements.
    #[must_use]
    pub fn subFromEachRow(&self, v: &Self) -> Self {
        self.each_row(v, "subFromEachRow", |a, b| a - b)
    }
    /// `m − v` for a column vector `v` — Scala's `subFromEachCol`.
    ///
    /// # Panics
    /// If `v` does not have `rows` elements.
    #[must_use]
    pub fn subFromEachCol(&self, v: &Self) -> Self {
        self.each_col(v, "subFromEachCol", |a, b| a - b)
    }
    /// `m · v` for a row vector `v` — Scala's `mulEachRow`.
    ///
    /// # Panics
    /// If `v` does not have `cols` elements.
    #[must_use]
    pub fn mulEachRow(&self, v: &Self) -> Self {
        self.each_row(v, "mulEachRow", |a, b| a * b)
    }
    /// `m · v` for a column vector `v` — Scala's `mulEachCol`.
    ///
    /// # Panics
    /// If `v` does not have `rows` elements.
    #[must_use]
    pub fn mulEachCol(&self, v: &Self) -> Self {
        self.each_col(v, "mulEachCol", |a, b| a * b)
    }
    /// `m / v` for a row vector `v` — Scala's `divEachRow`.
    ///
    /// # Panics
    /// If `v` does not have `cols` elements.
    #[must_use]
    pub fn divEachRow(&self, v: &Self) -> Self {
        self.each_row(v, "divEachRow", |a, b| a / b)
    }
    /// `m / v` for a column vector `v` — Scala's `divEachCol`.
    ///
    /// # Panics
    /// If `v` does not have `rows` elements.
    #[must_use]
    pub fn divEachCol(&self, v: &Self) -> Self {
        self.each_col(v, "divEachCol", |a, b| a / b)
    }

    /// R's `scale(x, center, scale)`: subtract the column means (`center`), then divide
    /// each column by its root-mean-square with Bessel's correction, `sqrt(Σc² / (n−1))`
    /// (`do_scale`) — the sample standard deviation when centred, the raw second moment
    /// when not. Squares are `x·x` (see the module note).
    #[must_use]
    pub fn scale(&self, center: bool, do_scale: bool) -> Self {
        let c = if center {
            self - &self.meanAxis(0)
        } else {
            self.clone()
        };
        if do_scale {
            let sq = c.map_elems(|x| x * x);
            #[expect(clippy::cast_precision_loss, reason = "a matrix dimension")]
            let denom = (&sq.sumAxis(0) / (self.rows() - 1) as f64).sqrt();
            &c / &denom
        } else {
            c
        }
    }

    // ── Repeat, tile, split ────────────────────────────────────────────────────

    /// `np.repeat(m, n)`: each element `n` times, flattened to a 1×(size·n) row.
    #[must_use]
    pub fn repeat(&self, n: usize) -> Self {
        let mut result = Vec::with_capacity(self.size() * n);
        for r in 0..self.rows() {
            for c in 0..self.cols() {
                let v = self.at(r, c);
                for _ in 0..n {
                    result.push(v);
                }
            }
        }
        Self::create(result, 1, self.size() * n)
    }

    /// `np.repeat(m, n, axis)`: each row (`axis = 0`) or column (`axis = 1`) `n` times.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1.
    #[must_use]
    pub fn repeatAxis(&self, n: usize, axis: usize) -> Self {
        assert!(axis == 0 || axis == 1, "axis must be 0 or 1, got {axis}");
        let (rows, cols) = self.shape();
        if axis == 0 {
            let mut result = vec![0.0; rows * n * cols];
            for i in 0..rows {
                for k in 0..n {
                    for j in 0..cols {
                        result[(i * n + k) * cols + j] = self.at(i, j);
                    }
                }
            }
            Self::create(result, rows * n, cols)
        } else {
            let mut result = vec![0.0; rows * cols * n];
            for i in 0..rows {
                for j in 0..cols {
                    for k in 0..n {
                        result[i * cols * n + j * n + k] = self.at(i, j);
                    }
                }
            }
            Self::create(result, rows, cols * n)
        }
    }

    /// `np.tile(m, (row_reps, col_reps))`.
    #[must_use]
    pub fn tile(&self, row_reps: usize, col_reps: usize) -> Self {
        let (rows, cols) = self.shape();
        let (new_rows, new_cols) = (rows * row_reps, cols * col_reps);
        let mut result = vec![0.0; new_rows * new_cols];
        for i in 0..new_rows {
            for j in 0..new_cols {
                result[i * new_cols + j] = self.at(i % rows, j % cols);
            }
        }
        Self::create(result, new_rows, new_cols)
    }

    fn split_points(indices: &[usize], extent: usize, what: &str) -> Vec<usize> {
        assert!(
            indices.iter().all(|&i| i > 0 && i < extent),
            "{what}: Split indices must be in range (0, {extent})"
        );
        assert!(
            indices.windows(2).all(|w| w[0] <= w[1]),
            "{what}: Indices must be sorted"
        );
        let mut points = Vec::with_capacity(indices.len() + 2);
        points.push(0);
        for &i in indices {
            if !points.contains(&i) {
                points.push(i);
            }
        }
        if !points.contains(&extent) {
            points.push(extent);
        }
        points
    }

    /// `np.vsplit(m, indices)`: row blocks `[0, i₁)`, `[i₁, i₂)`, …, as views.
    ///
    /// # Panics
    /// If an index is out of `(0, rows)` or the indices are not sorted.
    #[must_use]
    pub fn vsplit(&self, indices: &[usize]) -> Vec<Self> {
        let pts = Self::split_points(indices, self.rows(), "vsplit");
        let c = self.cols() as i64;
        pts.windows(2)
            .map(|w| self.slice(w[0] as i64..w[1] as i64, 0..c))
            .collect()
    }

    /// `np.vsplit(m, n)`: `n` equal row blocks, as views.
    ///
    /// # Panics
    /// If `rows` is not a multiple of `n`.
    #[must_use]
    pub fn vsplitN(&self, n: usize) -> Vec<Self> {
        assert!(
            n > 0 && self.rows().is_multiple_of(n),
            "Cannot split {} rows into {n} equal parts",
            self.rows()
        );
        let per = (self.rows() / n) as i64;
        let c = self.cols() as i64;
        (0..n as i64)
            .map(|i| self.slice(i * per..(i + 1) * per, 0..c))
            .collect()
    }

    /// `np.hsplit(m, indices)`: column blocks, as views.
    ///
    /// # Panics
    /// If an index is out of `(0, cols)` or the indices are not sorted.
    #[must_use]
    pub fn hsplit(&self, indices: &[usize]) -> Vec<Self> {
        let pts = Self::split_points(indices, self.cols(), "hsplit");
        let r = self.rows() as i64;
        pts.windows(2)
            .map(|w| self.slice(0..r, w[0] as i64..w[1] as i64))
            .collect()
    }

    /// `np.hsplit(m, n)`: `n` equal column blocks, as views.
    ///
    /// # Panics
    /// If `cols` is not a multiple of `n`.
    #[must_use]
    pub fn hsplitN(&self, n: usize) -> Vec<Self> {
        assert!(
            n > 0 && self.cols().is_multiple_of(n),
            "Cannot split {} cols into {n} equal parts",
            self.cols()
        );
        let per = (self.cols() / n) as i64;
        let r = self.rows() as i64;
        (0..n as i64)
            .map(|i| self.slice(0..r, i * per..(i + 1) * per))
            .collect()
    }

    /// `np.split(m, indices, axis)`: `vsplit` for axis 0, `hsplit` for axis 1.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1.
    #[must_use]
    pub fn split(&self, indices: &[usize], axis: usize) -> Vec<Self> {
        assert!(
            axis == 0 || axis == 1,
            "axis must be 0 (rows) or 1 (columns)"
        );
        if axis == 0 {
            self.vsplit(indices)
        } else {
            self.hsplit(indices)
        }
    }

    /// `np.split(m, n, axis)`: `vsplitN` for axis 0, `hsplitN` for axis 1.
    ///
    /// # Panics
    /// If `axis` is not 0 or 1.
    #[must_use]
    pub fn splitN(&self, n: usize, axis: usize) -> Vec<Self> {
        assert!(
            axis == 0 || axis == 1,
            "axis must be 0 (rows) or 1 (columns)"
        );
        if axis == 0 {
            self.vsplitN(n)
        } else {
            self.hsplitN(n)
        }
    }

    // ── CSV out ────────────────────────────────────────────────────────────────

    /// The CSV text `saveCSV` writes: one line per row, `sep` between cells, each value
    /// through Java's `Double.toString`, NaN as `nan_as`, infinities as `Inf`/`-Inf`.
    #[must_use]
    pub fn csvText(&self, sep: &str, nan_as: &str) -> String {
        let mut out = String::new();
        for i in 0..self.rows() {
            let cells: Vec<String> = (0..self.cols())
                .map(|j| {
                    let d = self.at(i, j);
                    if d.is_nan() {
                        nan_as.to_owned()
                    } else if d == f64::INFINITY {
                        "Inf".to_owned()
                    } else if d == f64::NEG_INFINITY {
                        "-Inf".to_owned()
                    } else {
                        java_double_to_string(d)
                    }
                })
                .collect();
            out.push_str(&cells.join(sep));
            out.push('\n');
        }
        out
    }

    /// Scala's `saveCSV(path, sep, nanAs)`: writes [`MatD::csvText`]; `false` when the
    /// write failed. `writeCsv` is the same call with `","` and `"NaN"`.
    pub fn saveCSV(&self, path: &UPath, sep: &str, nan_as: &str) -> bool {
        path.write(&self.csvText(sep, nan_as))
    }

    /// Scala's `writeCsv(path)`: [`MatD::saveCSV`] with `","` and `"NaN"`.
    pub fn writeCsv(&self, path: &UPath) -> bool {
        self.saveCSV(path, ",", "NaN")
    }
}

/// `java.lang.Math.round(double)`: `floor(x + 0.5)` as a `long`, NaN → 0, saturating.
fn java_math_round(x: f64) -> f64 {
    if x.is_nan() {
        return 0.0;
    }
    #[expect(
        clippy::cast_possible_truncation,
        reason = "Java's round saturates the same way"
    )]
    let r = (x + 0.5).floor() as i64;
    #[expect(clippy::cast_precision_loss, reason = "the value came from a double")]
    let f = r as f64;
    f
}

#[cfg(test)]
mod tests {
    use crate::udata::mat::MatD;

    fn m23() -> MatD {
        MatD::create(vec![1.0, -2.0, 3.0, 4.0, 5.0, -6.0], 2, 3)
    }

    #[test]
    fn maximum_minimum_follow_java_compare() {
        let a = MatD::create(vec![1.0, f64::NAN, -0.0], 1, 3);
        let b = MatD::create(vec![f64::NAN, 1.0, 0.0], 1, 3);
        let mx = a.maximum(&b).flatten();
        assert!(mx[0].is_nan() && mx[1].is_nan());
        assert_eq!(mx[2].to_bits(), 0.0f64.to_bits());
        let mn = a.minimum(&b).flatten();
        assert_eq!(mn[0], 1.0);
        assert_eq!(mn[1], 1.0);
        assert_eq!(mn[2].to_bits(), (-0.0f64).to_bits());
        assert_eq!(
            m23().maximumScalar(0.0).flatten(),
            vec![1.0, 0.0, 3.0, 4.0, 5.0, 0.0]
        );
        assert_eq!(
            m23().minimumScalar(0.0).flatten(),
            vec![0.0, -2.0, 0.0, 0.0, 0.0, -6.0]
        );
    }

    #[test]
    fn sign_round_nan_to_num_allclose() {
        assert_eq!(m23().sign().flatten(), vec![1.0, -1.0, 1.0, 1.0, 1.0, -1.0]);
        let r = MatD::create(vec![1.25, -1.5, 2.5, 0.125], 1, 4);
        assert_eq!(r.round(0).flatten(), vec![1.0, -1.0, 3.0, 0.0]);
        assert_eq!(r.round(1).flatten(), vec![1.3, -1.5, 2.5, 0.1]);
        let bad = MatD::create(vec![f64::NAN, f64::INFINITY, f64::NEG_INFINITY, 1.0], 2, 2);
        assert_eq!(
            bad.nanToNum(0.0, 9.0, -9.0).flatten(),
            vec![0.0, 9.0, -9.0, 1.0]
        );
        assert!(bad.containsNaN() && !m23().containsNaN());
        assert!(m23().allclose(&(&m23() + 1e-9), 1e-5, 1e-8));
        assert!(!m23().allclose(&(&m23() + 1e-3), 1e-5, 1e-8));
        assert!(!m23().allclose(&m23().T(), 1e-5, 1e-8));
    }

    #[test]
    fn broadcast_helpers_and_scale() {
        let m = m23();
        let row = MatD::create(vec![10.0, 20.0, 30.0], 1, 3);
        let col = MatD::create(vec![100.0, 200.0], 2, 1);
        assert_eq!(
            m.addToEachRow(&row).flatten(),
            vec![11.0, 18.0, 33.0, 14.0, 25.0, 24.0]
        );
        assert_eq!(
            m.addToEachCol(&col).flatten(),
            vec![101.0, 98.0, 103.0, 204.0, 205.0, 194.0]
        );
        assert_eq!(
            m.mulEachRow(&row).flatten(),
            vec![10.0, -40.0, 90.0, 40.0, 100.0, -180.0]
        );
        assert_eq!(
            m.divEachCol(&col).flatten(),
            vec![0.01, -0.02, 0.03, 0.02, 0.025, -0.03]
        );
        assert_eq!(m.subFromEachRow(&row).flatten(), (&m - &row).flatten());
        assert_eq!(m.mulEachCol(&col).flatten(), (&m * &col).flatten());
        assert_eq!(m.divEachRow(&row).flatten(), (&m / &row).flatten());
        assert_eq!(m.subFromEachCol(&col).flatten(), (&m - &col).flatten());
        // scale: centred columns have zero mean and unit sample std
        let x = MatD::create(vec![1.0, 10.0, 2.0, 20.0, 3.0, 30.0, 4.0, 40.0], 4, 2);
        let s = x.scale(true, true);
        let means = s.meanAxis(0).flatten();
        assert!(means.iter().all(|v| v.abs() < 1e-15), "{means:?}");
        let sd = s.stdAxis(0).flatten(); // population std of a unit-sample-std column
        assert!(
            sd.iter().all(|v| (v - (3f64 / 4.0).sqrt()).abs() < 1e-12),
            "{sd:?}"
        );
        assert_eq!(x.scale(false, false), x);
    }

    #[test]
    fn repeat_tile_split() {
        let m = m23();
        assert_eq!(m.repeat(2).shape(), (1, 12));
        assert_eq!(m.repeat(2).flatten()[..4], [1.0, 1.0, -2.0, -2.0]);
        assert_eq!(
            m.repeatAxis(2, 0).flatten(),
            vec![
                1.0, -2.0, 3.0, 1.0, -2.0, 3.0, 4.0, 5.0, -6.0, 4.0, 5.0, -6.0
            ]
        );
        assert_eq!(
            m.repeatAxis(2, 1).flatten(),
            vec![
                1.0, 1.0, -2.0, -2.0, 3.0, 3.0, 4.0, 4.0, 5.0, 5.0, -6.0, -6.0
            ]
        );
        assert_eq!(
            m.tile(2, 1).flatten(),
            vec![
                1.0, -2.0, 3.0, 4.0, 5.0, -6.0, 1.0, -2.0, 3.0, 4.0, 5.0, -6.0
            ]
        );
        assert_eq!(m.tile(1, 2).shape(), (2, 6));
        let parts = m.hsplit(&[1]);
        assert_eq!(parts.len(), 2);
        assert_eq!(parts[0].shape(), (2, 1));
        assert_eq!(parts[1].flatten(), vec![-2.0, 3.0, 5.0, -6.0]);
        let v = m.vsplitN(2);
        assert_eq!(v[1].flatten(), vec![4.0, 5.0, -6.0]);
        assert_eq!(m.split(&[1], 0).len(), 2);
        assert_eq!(m.splitN(3, 1).len(), 3);
        assert_eq!(m.hsplit(&[1, 1, 2]).len(), 3);
    }

    #[test]
    fn row_and_column_closures() {
        let m = m23();
        // mapRows: subtract each row's first element
        let r = m.mapRows(|row| row - row.at(0, 0));
        assert_eq!(r.flatten(), vec![0.0, -3.0, 2.0, 0.0, 1.0, -10.0]);
        // mapCols: each column divided by its max
        let c = m.mapCols(|col| col / col.max());
        assert_eq!(c.flatten(), vec![0.25, -0.4, 1.0, 1.0, 1.0, -2.0]);
        assert_eq!(
            m.filterRows(|row| row.sum() > 0.0).flatten(),
            vec![1.0, -2.0, 3.0, 4.0, 5.0, -6.0]
        );
        assert_eq!(m.filterRows(|row| row.at(0, 0) > 2.0).shape(), (1, 3));
        assert_eq!(m.filterRows(|_| false).shape(), (0, 3));
        assert_eq!(
            m.applyAlongAxis(MatD::sum, 0).flatten(),
            vec![5.0, 3.0, -3.0]
        );
        assert_eq!(m.applyAlongAxis(MatD::max, 1).flatten(), vec![3.0, 5.0]);
        assert_eq!(m.zipMap(&m, |a, b| a * b).flatten(), (&m * &m).flatten());
        assert_eq!(m.wherePred(|x| x < 0.0), vec![(0, 1), (1, 2)]);
        assert!(m.exists(|x| x == 5.0) && !m.exists(|x| x == 7.0));
        assert_eq!(m.T().toContiguous().flatten(), m.T().flatten());
        assert_eq!(m.ndim(), 2);
    }

    #[test]
    fn csv_text_is_java_formatted() {
        let m = MatD::create(vec![1.0, 0.1, 1e10, f64::NAN, f64::INFINITY, -1e-5], 2, 3);
        assert_eq!(m.csvText(",", "NaN"), "1.0,0.1,1.0E10\nNaN,Inf,-1.0E-5\n");
        assert_eq!(m.csvText(";", "?").lines().next(), Some("1.0;0.1;1.0E10"));
    }
}

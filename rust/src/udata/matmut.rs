//! `MatMut` — the mutable half of Tier 3 phase (c), Scala's `MatDOps` `update` family.
//!
//! # Why this is a different type when Scala has only one
//!
//! Scala's `update` writes straight into the backing array through the stride equation:
//!
//! ```scala
//! m.tdata(m.offset + r * m.rs + c * m.cs) = value
//! ```
//!
//! so writing through a **view** mutates the parent, exactly as NumPy does. That is not
//! expressible in safe Rust. `Arc::make_mut` would silently detach the view instead, and
//! genuine shared mutation would need `UnsafeCell` — which here is not merely
//! un-idiomatic but unsound, because [`MatD`] is `Sync` and the reductions run under
//! rayon.
//!
//! So mutation is gated on **proven sole ownership**: [`MatD::intoMut`] consumes the
//! matrix and **panics** when anything else holds the buffer. The divergence becomes
//! structural and loud — you cannot obtain a mutable handle to a shared buffer at all —
//! rather than a silent copy-on-write.
//!
//! One type with one conversion was chosen over a `MatDOwned`/`MatD` type split, which
//! would have been further from BOTH references: Scala has exactly one `Mat[Double]` and
//! NumPy exactly one `ndarray`, each always mutable. A split diverges at every
//! declaration; this diverges at one call.
//!
//! The conversion panics rather than returning a `Result` because a `Result` propagates:
//! a ported function that mutates a matrix it received would return `Result<...>` where
//! its Scala original returns a plain value, and every caller up the chain would inherit
//! that. The panic keeps ported signatures the same shape as the Scala. Copying when
//! shared was the third candidate and is the worst of the three — it reads exactly like
//! Scala while inverting the semantics, since the view would silently stop tracking its
//! parent.
//!
//! # What is still not reproduced
//!
//! ```python
//! v = m[1:, :]
//! v[0, 0] = 5.0     # m is modified — in NumPy and in Scala, but not here
//! ```
//!
//! No safe Rust design gives that. It is recorded in `PARITY.md` as an intentional
//! divergence. Note the in-repo corpus does not rely on it: every write in `TprfRunner`,
//! `MatDCheck`, `MinimalTest` and `Tprf3ParityGen` targets a matrix the code owns.
//!
//! # The idiom it exists for
//!
//! Sequential recurrences, which is where mutation earns its place — each row depends on
//! the one before, and a pure fold would allocate a matrix per step:
//!
//! ```scala
//! for t <- 1 until T do
//!   f(t, ::) = f(t - 1, ::) * pf + fNoise(t - 1, ::)
//! ```

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use std::ops::Range;

use crate::udata::mat::MatD;
use crate::udata::mat::MatParts;
use crate::udata::matb::MatB;

/// A uniquely-owned matrix that can be written to. Obtain with [`MatD::intoMut`] and
/// return to the immutable form with [`MatMut::freeze`].
///
/// Holds the buffer outright rather than behind an `Arc`: `intoMut` only returns when the
/// `Arc` had a single owner, so there is nothing left to share with.
#[derive(Clone, Debug)]
pub struct MatMut {
    data: Vec<f64>,
    rows: usize,
    cols: usize,
    offset: usize,
    rs: usize,
    cs: usize,
}

impl MatMut {
    /// Internal constructor; `MatD::intoMut` is the public route.
    pub(crate) fn fromParts(p: MatParts) -> Self {
        Self {
            data: p.data,
            rows: p.rows,
            cols: p.cols,
            offset: p.offset,
            rs: p.rs,
            cs: p.cs,
        }
    }

    pub fn rows(&self) -> usize {
        self.rows
    }

    pub fn cols(&self) -> usize {
        self.cols
    }

    pub fn shape(&self) -> (usize, usize) {
        (self.rows, self.cols)
    }

    pub fn isEmpty(&self) -> bool {
        self.rows == 0 || self.cols == 0
    }

    /// Element at `(r, c)` — Scala's `m(r, c)` read.
    pub fn at(&self, r: usize, c: usize) -> f64 {
        self.data[self.offset + r * self.rs + c * self.cs]
    }

    /// Index of `(r, c)` in the backing buffer.
    fn idx(&self, r: usize, c: usize) -> usize {
        self.offset + r * self.rs + c * self.cs
    }

    /// Back to the immutable form. Free — the buffer moves.
    pub fn freeze(self) -> MatD {
        MatD::fromOwnedParts(MatParts {
            data: self.data,
            rows: self.rows,
            cols: self.cols,
            offset: self.offset,
            rs: self.rs,
            cs: self.cs,
        })
    }

    /// One row as a fresh 1×n matrix — Scala's `m(row, ::)` read.
    ///
    /// Copies, as Scala's does: its `apply(row, ::)` builds a new array rather than a
    /// view, so a recurrence that reads the previous row pays the same allocation on
    /// both sides.
    pub fn applyRowAll(&self, row: usize) -> MatD {
        let data: Vec<f64> = (0..self.cols).map(|c| self.at(row, c)).collect();
        MatD::create(data, 1, self.cols)
    }

    /// One column as a fresh n×1 matrix — Scala's `m(::, col)` read.
    pub fn applyAllCol(&self, col: usize) -> MatD {
        let data: Vec<f64> = (0..self.rows).map(|r| self.at(r, col)).collect();
        MatD::create(data, self.rows, 1)
    }

    // ── scalar writes ───────────────────────────────────────────────────────

    /// `m(r, c) = value`.
    ///
    /// # Panics
    /// If the index is out of bounds, mirroring Scala's `require`.
    pub fn updateAt(&mut self, r: usize, c: usize, value: f64) {
        assert!(
            r < self.rows && c < self.cols,
            "index ({r}, {c}) out of bounds for {}x{}",
            self.rows,
            self.cols
        );
        let i = self.idx(r, c);
        self.data[i] = value;
    }

    /// `m(row, ::) = value`.
    pub fn updateRowAll(&mut self, row: usize, value: f64) {
        self.updateRowCols(row, 0..self.cols, value);
    }

    /// `m(::, col) = value`.
    pub fn updateAllCol(&mut self, col: usize, value: f64) {
        self.updateRowsCol(0..self.rows, col, value);
    }

    /// `m(rows, ::) = value`.
    pub fn updateRowsAll(&mut self, rows: Range<usize>, value: f64) {
        let cols = self.cols;
        self.updateRowsCols(rows, 0..cols, value);
    }

    /// `m(::, cols) = value`.
    pub fn updateAllCols(&mut self, cols: Range<usize>, value: f64) {
        let rows = self.rows;
        self.updateRowsCols(0..rows, cols, value);
    }

    /// `m(row, cols) = value`.
    pub fn updateRowCols(&mut self, row: usize, cols: Range<usize>, value: f64) {
        self.updateRowsCols(row..row + 1, cols, value);
    }

    /// `m(rows, col) = value`.
    pub fn updateRowsCol(&mut self, rows: Range<usize>, col: usize, value: f64) {
        self.updateRowsCols(rows, col..col + 1, value);
    }

    /// `m(rows, cols) = value` — the rectangle every scalar form reduces to.
    ///
    /// # Panics
    /// If the rectangle leaves the matrix.
    pub fn updateRowsCols(&mut self, rows: Range<usize>, cols: Range<usize>, value: f64) {
        assert!(
            rows.end <= self.rows && cols.end <= self.cols,
            "({rows:?}, {cols:?}) out of bounds for {}x{}",
            self.rows,
            self.cols
        );
        for r in rows {
            for c in cols.clone() {
                let i = self.idx(r, c);
                self.data[i] = value;
            }
        }
    }

    /// `m(mask) = value` — Scala's `update(mask, value)`, and NumPy's `m[m > 0] = 1.0`.
    ///
    /// # Panics
    /// If the mask shape does not match.
    pub fn updateMask(&mut self, mask: &MatB, value: f64) {
        assert!(
            mask.shape() == (self.rows, self.cols),
            "mask shape {:?} must match matrix shape {:?}",
            mask.shape(),
            (self.rows, self.cols)
        );
        for r in 0..self.rows {
            for c in 0..self.cols {
                if mask.at(r, c) {
                    let i = self.idx(r, c);
                    self.data[i] = value;
                }
            }
        }
    }

    // ── writes from another matrix ──────────────────────────────────────────

    /// `m(row, ::) = other`, where `other` supplies `cols` values in row-major order.
    ///
    /// # Panics
    /// If `other` does not hold exactly `cols` elements.
    pub fn updateRowAllFrom(&mut self, row: usize, other: &MatD) {
        assert_eq!(
            other.size(),
            self.cols,
            "row source has {} elements, need {}",
            other.size(),
            self.cols
        );
        let src = other.toArray();
        for (c, &v) in src.iter().enumerate() {
            let i = self.idx(row, c);
            self.data[i] = v;
        }
    }

    /// `m(::, col) = other`, where `other` supplies `rows` values.
    ///
    /// # Panics
    /// If `other` does not hold exactly `rows` elements.
    pub fn updateAllColFrom(&mut self, col: usize, other: &MatD) {
        assert_eq!(
            other.size(),
            self.rows,
            "column source has {} elements, need {}",
            other.size(),
            self.rows
        );
        let src = other.toArray();
        for (r, &v) in src.iter().enumerate() {
            let i = self.idx(r, col);
            self.data[i] = v;
        }
    }

    /// `m(rows, cols) = other`, taking `other` in row-major order.
    ///
    /// # Panics
    /// If the rectangle leaves the matrix or `other` has the wrong element count.
    pub fn updateRowsColsFrom(&mut self, rows: Range<usize>, cols: Range<usize>, other: &MatD) {
        let (nr, nc) = (rows.end - rows.start, cols.end - cols.start);
        assert!(
            rows.end <= self.rows && cols.end <= self.cols,
            "({rows:?}, {cols:?}) out of bounds"
        );
        assert_eq!(
            other.size(),
            nr * nc,
            "source has {} elements, need {}",
            other.size(),
            nr * nc
        );
        let src = other.toArray();
        for (k, r) in rows.enumerate() {
            for (l, c) in cols.clone().enumerate() {
                let i = self.idx(r, c);
                self.data[i] = src[k * nc + l];
            }
        }
    }
}

impl MatD {
    /// Consume this matrix and return a writable handle.
    ///
    /// # Panics
    /// If another `MatD` still holds the same buffer — which includes any live view taken
    /// from it. That case is exactly where Scala and NumPy would write THROUGH to the
    /// other matrix, and no safe Rust design reproduces it; panicking is the loud form of
    /// the divergence.
    ///
    /// Panics rather than returning a `Result` so that a ported function keeps the
    /// signature its Scala original has. A `Result` here propagates: `generate` would
    /// return `Result<(MatD, ...)>` where the Scala returns a tuple, and every caller up
    /// the chain inherits it — changing the shape of the port, not just the call site.
    /// The other candidate, copying when shared, was rejected for reading identically to
    /// Scala while silently inverting its semantics: the view would stop tracking its
    /// parent.
    #[must_use]
    pub fn intoMut(self) -> MatMut {
        MatMut::fromParts(self.intoOwnedParts())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    #[should_panic(expected = "matrix buffer is shared")]
    fn a_shared_buffer_cannot_be_made_mutable() {
        let m = MatD::zeros(3, 3);
        let _view = m.T(); // shares the buffer
        let _mm = m.intoMut();
    }

    /// The companion to the panic above: the same construction with nothing else holding
    /// the buffer succeeds, which is what proves the panic is about SHARING rather than
    /// about views being special.
    #[test]
    fn an_unshared_buffer_converts() {
        let m = MatD::zeros(3, 3);
        let view = m.T();
        drop(view);
        let mm = m.intoMut();
        assert_eq!(mm.shape(), (3, 3));
    }

    #[test]
    fn freeze_round_trips_without_copying_semantics() {
        let mut mm = MatD::zeros(2, 3).intoMut();
        mm.updateAt(1, 2, 7.5);
        mm.updateRowAll(0, 1.0);
        let m = mm.freeze();
        assert_eq!(m.at(1, 2), 7.5);
        assert_eq!(m.applyRowAll(0).toArray(), vec![1.0, 1.0, 1.0]);
        assert_eq!(m.at(1, 0), 0.0);
    }

    #[test]
    fn the_recurrence_idiom_reads_like_the_scala() {
        // The block this type exists for, from `TprfRunner`:
        //
        //   f(0, ::) = <row>
        //   for t <- 1 until T do
        //     f(t, ::) = f(t - 1, ::) * pf + fNoise(t - 1, ::)
        //
        // If this ever needs restructuring -- loops split, handles threaded through
        // signatures -- the ergonomics have changed the SHAPE of ported code and the
        // fallible-conversion design should be revisited.
        let t_max = 5usize;
        let k = 3usize;
        let pf = 0.5;
        let noise = MatD::create((0..(t_max * k)).map(|i| i as f64 * 0.1).collect(), t_max, k);

        let mut f = MatD::zeros(t_max, k).intoMut();
        f.updateRowAllFrom(0, &MatD::create(vec![1.0, 2.0, 3.0], 1, k));
        for t in 1..t_max {
            let prev = f.applyRowAll(t - 1);
            f.updateRowAllFrom(t, &(&(&prev * pf) + &noise.applyRowAll(t - 1)));
        }
        let f = f.freeze();

        // Recomputed independently, so the test is not the implementation restated.
        let mut want = vec![1.0, 2.0, 3.0];
        for t in 1..t_max {
            want = (0..k).map(|j| want[j] * pf + noise.at(t - 1, j)).collect();
            for (j, &w) in want.iter().enumerate() {
                assert!((f.at(t, j) - w).abs() < 1e-12, "row {t} col {j}");
            }
        }
    }

    #[test]
    fn column_writes_cover_the_other_recurrence_shape() {
        // `g(::, i) = <column>` and `g(t, j) = <scalar>` from the same source.
        let mut g = MatD::zeros(4, 2).intoMut();
        g.updateAllColFrom(1, &MatD::create(vec![9.0, 8.0, 7.0, 6.0], 4, 1));
        g.updateAt(2, 0, -1.0);
        let g = g.freeze();
        assert_eq!(g.applyAllCol(1).toArray(), vec![9.0, 8.0, 7.0, 6.0]);
        assert_eq!(g.at(2, 0), -1.0);
        assert_eq!(g.at(0, 0), 0.0);
    }

    #[test]
    fn mask_writes_touch_only_the_true_cells() {
        // Scala: m(m.gt(2.0)) = 0.0 ;  NumPy: m[m > 2.0] = 0.0
        let m = MatD::create((1..=6).map(f64::from).collect(), 2, 3);
        let mask = m.gt(2.0);
        let mut mm = m.intoMut();
        mm.updateMask(&mask, 0.0);
        let m = mm.freeze();
        assert_eq!(m.toArray(), vec![1.0, 2.0, 0.0, 0.0, 0.0, 0.0]);
    }

    #[test]
    fn fancy_index_reads_select_the_cross_product() {
        let m = MatD::create((1..=12).map(f64::from).collect(), 3, 4);
        assert_eq!(
            m.applyRowsIdx(&[2, 0]).toArray(),
            vec![9.0, 10.0, 11.0, 12.0, 1.0, 2.0, 3.0, 4.0]
        );
        assert_eq!(
            m.applyIdxCols(&[3, 1]).toArray(),
            vec![4.0, 2.0, 8.0, 6.0, 12.0, 10.0]
        );
        // NumPy's ix_ behaviour, not its bare fancy indexing: the rectangle, not a diagonal.
        assert_eq!(
            m.applyIdxIdx(&[0, 2], &[1, 3]).toArray(),
            vec![2.0, 4.0, 10.0, 12.0]
        );
    }
}

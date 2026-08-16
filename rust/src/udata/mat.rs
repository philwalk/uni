//! Dense `f64` matrices — the port of Scala's `uni.data.Mat` at `Mat[Double]`.
//!
//! # Scope
//!
//! Tier 3 of `PARITY.md`, phases (a) and (b). Milestone 1 covered construction,
//! elementwise arithmetic and the whole-matrix reductions that `jsrc/marketSim.sc`
//! needs. This adds the rest of the core: the **strided view model** (transpose,
//! `slice`, `broadcastTo`), broadcasting arithmetic, the axis reductions, and — in
//! `vecexts.rs` — `CVecD`/`RVecD`.
//!
//! # Why a matrix here carries strides
//!
//! It would be simpler to make every derived matrix a fresh contiguous copy, and for a
//! library that only had to be *correct* that would be fine. It is not fine for a
//! library that has to be **bit-identical to Scala**, because on the Scala side the
//! layout decides which summation algorithm runs:
//!
//! ```text
//! m.sum        // contiguous, offset 0 -> sumD: 8-way unrolled, chunked  (see sum_d)
//! m.T.sum      // a transposed VIEW    -> plain sequential fold
//! ```
//!
//! Those two land on different last ulps for the same numbers, because `sumD` — the
//! chunked, 8-way unrolled sum — is only ever reached with a **contiguous array whose
//! length is exactly `rows * cols`**. `Mat.scala` calls it from four places (`sum`,
//! `mean`, `std`, `variance`) and every one of them is guarded that way. A strided view
//! is summed **sequentially** instead — not by a degraded fallback, but through the same
//! inline stride equation `tdata(offset + r * rs + c * cs)` that `apply(r, c)` uses
//! everywhere else. The difference between the two is association ORDER, not quality:
//! chunked-and-unrolled versus left-to-right. Materialising views in the port would
//! silently pick the chunked order where Scala picks the sequential one.
//!
//! Most of the family spells that guard as the shared `fastD` predicate — contiguous,
//! offset 0, backing array is exactly this matrix's data — which [`MatD::fast_d`]
//! mirrors. `sum` is the exception and writes the test out itself, in three branches
//! rather than two: `sumD`; a stride-indexed loop over the raw array when the view is
//! contiguous at offset 0 but shares a longer parent buffer; and the general
//! `Numeric`-dispatched loop, which reads through that same stride equation. The
//! middle branch holds `offset == 0`, so
//! `data(i * rs + j * cs)` is exactly `at(i, j)` — which is why [`MatD::sum`] collapses
//! the two loops into one without changing a single bit. Note also that
//! `min`/`max`/`argmin`/`argmax`/`cummax`/`cummin` have no fast path at all on either
//! side: one stride-aware scan, whatever the layout.
//!
//! The same reasoning explains the odd-looking [`MatD::is_weird_layout`] check: Scala's
//! `Internal.create` *materialises* a fragmented view into a contiguous copy, which
//! flips that view back onto the fast path. Whether a given `slice` is fragmented
//! depends on the parent's aspect ratio, so the same logical slice of a 3×5 and of a 5×3
//! sums by two different algorithms. That is reproduced here rather than tidied up.
//!
//! Buffers are shared through an `Arc<Vec<f64>>` so a view stays O(1), as it is in Scala.
//! Nothing mutates a matrix in place yet; when the `update` family lands it will need an
//! explicit decision about aliasing, since `Arc::make_mut` would give copy-on-write
//! where Scala writes through to the parent.
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

use std::cmp::Ordering;
use std::fmt;
use std::ops::Add;
use std::ops::Div;
use std::ops::Index;
use std::ops::Mul;
use std::ops::Neg;
use std::ops::Range;
use std::ops::Sub;
use std::sync::Arc;

use rayon::prelude::*;

/// Fork/join overhead dominates below this size; sum sequentially. Mirrors
/// `Mat.ParallelThreshold`.
pub const PARALLEL_THRESHOLD: usize = 4096;

/// Chunk-count cap for [`sum_d`], mirroring `Mat.MaxSumChunks`. Pinned to a constant so
/// the summation order — and therefore the last ulp — is a function of length alone.
pub const MAX_SUM_CHUNKS: usize = 16;

/// Element count above which a STRIDED sum splits into row blocks.
///
/// Part of the numeric contract, not a tuning knob: it selects between one block and
/// many, and partials are combined in block order, so it changes the answer. Pinned in
/// `test-data/mat-parity/` and mirrored by `Mat.StridedSumParallelThreshold`. Higher
/// than [`PARALLEL_THRESHOLD`] because a strided block has more per-element overhead to
/// amortise.
pub const STRIDED_SUM_PARALLEL_THRESHOLD: usize = 1 << 16;

/// Element count above which the order statistics split across threads, mirroring
/// `Mat.ScanParallelThreshold`.
///
/// Unlike the sum thresholds this is pure tuning, not contract: an extremum is
/// associative and commutative under a total order, so the chunk count cannot move the
/// answer. Higher than [`PARALLEL_THRESHOLD`] because one comparison per element does
/// not amortise fork/join at 4096.
pub(crate) const SCAN_PARALLEL_THRESHOLD: usize = 1 << 16;

/// Element count above which the AXIS sums split across lanes, mirroring
/// `Mat.LaneParallelThreshold`. Also pure tuning: splitting by lane leaves every lane's
/// accumulation order intact.
pub(crate) const LANE_PARALLEL_THRESHOLD: usize = 1 << 19;

/// Element count above which elementwise work is spread across rows, mirroring
/// `Mat.BcastParallelThreshold`.
///
/// Unlike [`sum_d`]'s chunking this cannot move a single bit, and for the reason Scala
/// states at its own `bcastRows`: rows write **disjoint output slices with no
/// reduction**, so each element is computed by the same expression from the same inputs
/// no matter which thread runs it or in what order. Parallelism is only ever observable
/// in a reduction, and no reduction happens here.
pub const BCAST_PARALLEL_THRESHOLD: usize = 1 << 16;

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
pub(crate) fn sum_d(a: &[f64]) -> f64 {
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
            if from < until {
                sum_range(a, from, until)
            } else {
                0.0
            }
        })
        .collect();
    let mut s = 0.0f64;
    for p in partials {
        s += p;
    }
    s
}

/// `java.lang.Double.compare` — the ordering Scala's `min`/`max`/`argmin`/`argmax`/
/// `cummax`/`cummin` actually use, and **not** what `f64::total_cmp` implements.
///
/// `Ordering[Double]` resolves by default to `Ordering.Double.TotalOrdering`, whose
/// `compare` *is* `java.lang.Double.compare`. Two of its rules differ from the primitive
/// `<`/`>` that a port naturally reaches for:
///
/// - **NaN sorts above every number.** `max(&[1.0, NaN, -1.0, 2.0])` is NaN, not 2.0.
///   With `>` it would be 2.0, because every comparison against NaN is false.
/// - **`-0.0 < 0.0`.** `min(&[0.0, -0.0])` is `-0.0`, at index 1. With `<` the scan
///   never moves off index 0.
///
/// It is also not quite `f64::total_cmp`, which implements the IEEE-754 totalOrder
/// predicate and places **-NaN below -Infinity**. `Double.doubleToLongBits` collapses
/// every NaN to one canonical pattern first, so the JVM treats -NaN and +NaN as equal
/// and both as the largest value. That is the only case where the two disagree, and it
/// is reproduced here rather than approximated.
///
/// Named for what it mirrors: a Java library method, not a uni one, so it keeps Java's
/// spelling in snake_case exactly as [`crate::udata::java_format_f`] does.
pub fn java_double_compare(a: f64, b: f64) -> Ordering {
    if a < b {
        return Ordering::Less;
    }
    if a > b {
        return Ordering::Greater;
    }
    canonical_bits(a).cmp(&canonical_bits(b))
}

/// `Double.doubleToLongBits` as a signed key: every NaN collapses to one pattern, and
/// the sign bit makes `-0.0`'s key negative, hence less than `0.0`'s zero.
fn canonical_bits(x: f64) -> i64 {
    if x.is_nan() {
        0x7ff8_0000_0000_0000_u64 as i64
    } else {
        x.to_bits() as i64
    }
}

/// `Ordering.Double.TotalOrdering.lt`.
pub(crate) fn lt_total(a: f64, b: f64) -> bool {
    java_double_compare(a, b) == Ordering::Less
}

/// `Ordering.Double.TotalOrdering.gt`.
pub(crate) fn gt_total(a: f64, b: f64) -> bool {
    java_double_compare(a, b) == Ordering::Greater
}

/// A dense `f64` matrix — Scala's `Mat[Double]` / `MatD` — possibly a **view** onto a
/// shared buffer.
///
/// The seven fields are exactly Scala's `MatData`: the backing storage plus the logical
/// shape, a transposed flag, and the offset/row-stride/column-stride triple that turns
/// `(r, c)` into an index. Element access is the one stride equation
/// `data[offset + r * rs + c * cs]`, as it is there.
///
/// Storage is `Arc<Vec<f64>>` rather than a bare `Vec<f64>` so a view costs a refcount bump
/// instead of a copy, matching Scala's zero-copy `transpose`/`slice`/`broadcastTo`. It
/// is deliberately not `ndarray::Array2`: that forces a `Result` and an `Option` into
/// paths that cannot fail, and this crate is free of `unwrap`/`expect`/`panic!`. An
/// `ArrayView2::from_shape` over the same buffer stays available, zero-copy, when matmul
/// and the BLAS crossover arrive.
///
/// `Arc<Vec<f64>>` and not the tidier-looking `Arc<[f64]>`, for a measured reason:
/// `Vec<f64> -> Arc<[f64]>` cannot move, so it allocates a second buffer and memcpies.
/// Every elementwise op builds its result as a `Vec` and hands it to `create`, so that
/// spelling put a full copy of the matrix on the cost of `m * 2.0` — 32MB at 2000x2000,
/// and about half the runtime of the operation. `Arc::new(vec)` moves. The extra pointer
/// hop on read is hoisted out of any loop that matters.
#[derive(Clone)]
pub struct MatD {
    data: Arc<Vec<f64>>,
    rows: usize,
    cols: usize,
    transposed: bool,
    offset: usize,
    rs: usize,
    cs: usize,
}

/// A matrix taken apart: the buffer plus its descriptor. Moves between [`MatD`] and
/// `MatMut` when ownership changes hands, and exists so neither side needs a six-argument
/// constructor or a six-element tuple.
pub(crate) struct MatParts {
    pub data: Vec<f64>,
    pub rows: usize,
    pub cols: usize,
    /// Carried, not dropped: the flag is part of how a layout is CLASSIFIED
    /// (`isStandardContiguous`, `is_weird_layout`), and classification selects the
    /// summation algorithm. Losing it on a `MatMut` round trip left values intact but
    /// changed which path a transposed view took after `freeze`.
    pub transposed: bool,
    pub offset: usize,
    pub rs: usize,
    pub cs: usize,
}

/// The layout half of a view — Scala's `(transposed, offset, rs, cs)` arguments to
/// `Internal.create`. Grouped so the gatekeeper takes four parameters rather than seven;
/// `None` for a stride means "derive the standard one", which is Scala's `-1` sentinel.
struct Layout {
    transposed: bool,
    offset: usize,
    rs: Option<usize>,
    cs: Option<usize>,
}

// ── Construction and layout ─────────────────────────────────────────────────────

impl MatD {
    /// Scala's `MatD(arr)`: an `n`×1 column matrix holding a **copy** of `arr`.
    ///
    /// The copy is deliberate and matches `Mat.apply(Array)`, which clones so the caller
    /// keeps ownership of its array. Callers wanting to hand over storage use
    /// [`MatD::create`].
    pub fn apply(arr: &[f64]) -> Self {
        Self::create(arr.to_vec(), arr.len(), 1)
    }

    /// Scala's `Mat.create(data, rows, cols)` — takes ownership, row-major, contiguous.
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
        // Standard strides are never fragmented, so this cannot need materialising and
        // skips the gatekeeper — exactly as `Mat.create` reduces to in Scala.
        Self {
            data: Arc::new(data),
            rows,
            cols,
            transposed: false,
            offset: 0,
            rs: cols,
            cs: 1,
        }
    }

    /// The gatekeeper — Scala's `Internal.create`, the only path that builds a view.
    ///
    /// `None` for a stride means "derive the standard one for this orientation", which
    /// is what Scala's negative sentinel does. Fragmented layouts are materialised here
    /// so the rest of the library only ever sees standard or simple-offset strides —
    /// and, crucially, so a materialised view lands back on the fast summation path in
    /// both languages together.
    fn createView(data: &Arc<Vec<f64>>, rows: usize, cols: usize, lay: Layout) -> Self {
        let transposed = lay.transposed;
        let rs = lay.rs.unwrap_or(if transposed { 1 } else { cols });
        let cs = lay.cs.unwrap_or(if transposed { rows } else { 1 });
        let m = Self {
            data: Arc::clone(data),
            rows,
            cols,
            transposed,
            offset: lay.offset,
            rs,
            cs,
        };
        if m.is_weird_layout() { m.matCopy() } else { m }
    }

    /// An `rows`×`cols` matrix of zeros.
    pub fn zeros(rows: usize, cols: usize) -> Self {
        Self::create(vec![0.0; rows * cols], rows, cols)
    }

    /// An `rows`×`cols` matrix of ones.
    pub fn ones(rows: usize, cols: usize) -> Self {
        Self::create(vec![1.0; rows * cols], rows, cols)
    }

    pub fn rows(&self) -> usize {
        self.rows
    }

    pub fn cols(&self) -> usize {
        self.cols
    }

    /// Total element count — Scala's `size`, i.e. `rows * cols`, not `rows`. For a view
    /// this is the *logical* count, which may be smaller than the backing buffer.
    pub fn size(&self) -> usize {
        self.rows * self.cols
    }

    pub fn shape(&self) -> (usize, usize) {
        (self.rows, self.cols)
    }

    pub fn isEmpty(&self) -> bool {
        self.rows == 0 || self.cols == 0
    }

    /// Whether this is a transposed view — Scala's `transposed`.
    pub fn transposed(&self) -> bool {
        self.transposed
    }

    /// Row-major with unit column stride and no transpose — Scala's `isContiguous`.
    /// Note it says nothing about `offset`, so it alone does not license a fast path.
    pub fn isContiguous(&self) -> bool {
        !self.transposed && self.rs == self.cols && self.cs == 1
    }

    /// Either orientation's standard stride pair — Scala's `isStandardContiguous`.
    pub fn isStandardContiguous(&self) -> bool {
        (self.rs == self.cols && self.cs == 1 && !self.transposed)
            || (self.rs == 1 && self.cs == self.rows && self.transposed)
    }

    /// A layout whose major stride skips over backing elements, so the view is
    /// fragmented — Scala's `isWeirdLayout`, which [`MatD::createView`] materialises.
    ///
    /// The comparison of a *stride* against a *count of rows* looks dimensionally odd,
    /// and it is; it is reproduced verbatim because the predicate decides which
    /// summation algorithm a sliced matrix gets, on both sides.
    fn is_weird_layout(&self) -> bool {
        if self.isStandardContiguous() {
            false
        } else if self.rs == 0 || self.cs == 0 {
            // Broadcast views are deliberately fragmented; Scala exempts them.
            false
        } else {
            let leading_dim = if self.transposed {
                self.cols
            } else {
                self.rows
            };
            let major_stride = if self.transposed { self.cs } else { self.rs };
            major_stride > leading_dim.max(1)
        }
    }

    /// Scala's `fastD`: contiguous, no view offset, and the backing buffer is exactly
    /// this matrix's data.
    ///
    /// `Mat.scala` calls this the single guard for its Double fast paths and uses it at
    /// most of them — the scalar operators, `norm`, `sum(axis)`, `cumsum`, `std`,
    /// `variance`, `cov`. It is not universal there: `sum` spells the same three
    /// conditions out inline (see [`MatD::sum`]), and the order statistics never branch
    /// at all.
    pub(crate) fn fast_d(&self) -> bool {
        self.isContiguous() && self.offset == 0 && self.data.len() == self.rows * self.cols
    }
}

// ── Element access and materialisation ──────────────────────────────────────────

impl MatD {
    /// Element at `(r, c)` through the stride equation — Scala's `at`. Also reachable as
    /// `m[(r, c)]`.
    pub fn at(&self, r: usize, c: usize) -> f64 {
        self.data[self.offset + r * self.rs + c * self.cs]
    }

    /// Flat access in logical row-major order — Scala's `at(i: Int)`.
    ///
    /// # Panics
    /// On an empty matrix, where the row/column decomposition would divide by zero.
    pub fn atFlat(&self, i: usize) -> f64 {
        assert!(!self.isEmpty(), "atFlat on an empty matrix");
        if self.isContiguous() {
            self.data[self.offset + i]
        } else {
            self.at(i / self.cols, i % self.cols)
        }
    }

    /// Fresh row-major `Vec` in logical order — Scala's `flatten`.
    pub fn flatten(&self) -> Vec<f64> {
        if self.fast_d() {
            return self.data.to_vec();
        }
        let mut out = Vec::with_capacity(self.size());
        for i in 0..self.rows {
            for j in 0..self.cols {
                out.push(self.at(i, j));
            }
        }
        out
    }

    /// Alias for [`MatD::flatten`] — Scala's `toArray`.
    pub fn toArray(&self) -> Vec<f64> {
        self.flatten()
    }

    /// Deep copy through the stride equation — Scala's `matCopy`.
    ///
    /// The result is always plain contiguous: Scala's implementation gathers logical
    /// elements and hands them to `Mat.create`, so despite its doc comment it does *not*
    /// preserve the transposed flag. The behaviour is what matters here, since it is
    /// what puts a materialised view back on the fast summation path.
    pub fn matCopy(&self) -> Self {
        let mut out = Vec::with_capacity(self.size());
        for i in 0..self.rows {
            for j in 0..self.cols {
                out.push(self.at(i, j));
            }
        }
        Self::create(out, self.rows, self.cols)
    }

    /// Independent contiguous copy — Scala's `copy`, i.e. `Mat.create(m.flatten, ...)`.
    pub fn copy(&self) -> Self {
        Self::create(self.flatten(), self.rows, self.cols)
    }

    /// Logical order as a 1×n row — Scala's `ravel`.
    pub fn ravel(&self) -> Self {
        let n = self.size();
        Self::create(self.flatten(), 1, n)
    }

    /// The single element of a 1×1 matrix — Scala's `item`.
    ///
    /// # Panics
    /// If the matrix is not 1×1, mirroring Scala's `require`.
    pub fn item(&self) -> f64 {
        assert!(
            self.rows == 1 && self.cols == 1,
            "item requires a 1x1 matrix, got {:?}",
            self.shape()
        );
        self.at(0, 0)
    }
}

// ── Shape manipulation ──────────────────────────────────────────────────────────

impl MatD {
    /// O(1) transpose — Scala's `transpose`: swap dims and strides, flip the flag, move
    /// no data. Note this deliberately bypasses the [`MatD::createView`] gatekeeper,
    /// exactly as Scala's `Internal.transposeView` constructs `MatData` directly.
    pub fn transpose(&self) -> Self {
        Self {
            data: Arc::clone(&self.data),
            rows: self.cols,
            cols: self.rows,
            transposed: !self.transposed,
            offset: self.offset,
            rs: self.cs,
            cs: self.rs,
        }
    }

    /// Scala's `m.T` — alias for [`MatD::transpose`].
    pub fn T(&self) -> Self {
        self.transpose()
    }

    /// Zero-copy sub-matrix view — Scala's `slice(rowRange, colRange)`.
    ///
    /// Ranges are `i64` because Scala accepts a negative *start*, read as an offset from
    /// the end (`-2 until 0` is the last two). Only the start is adjusted; the length is
    /// the range's own length, as there.
    ///
    /// This is **not** `m(rowRange, colRange)` — see [`MatD::applyRowsCols`], which
    /// gathers a fresh contiguous copy. The distinction is observable: a view sums by a
    /// different algorithm.
    ///
    /// # Panics
    /// If the requested rectangle leaves the matrix, mirroring Scala's `require`.
    pub fn slice(&self, rows: Range<i64>, cols: Range<i64>) -> Self {
        let (r0, n_rows) = normalize_range(&rows, self.rows);
        let (c0, n_cols) = normalize_range(&cols, self.cols);
        assert!(
            r0 >= 0
                && r0 + n_rows <= self.rows as i64
                && c0 >= 0
                && c0 + n_cols <= self.cols as i64,
            "slice({rows:?}, {cols:?}) out of bounds for {}x{}",
            self.rows,
            self.cols
        );
        let new_offset = self.offset + (r0 as usize) * self.rs + (c0 as usize) * self.cs;
        let lay = Layout {
            transposed: self.transposed,
            offset: new_offset,
            rs: Some(self.rs),
            cs: Some(self.cs),
        };
        Self::createView(&self.data, n_rows as usize, n_cols as usize, lay)
    }

    /// Virtual expansion to `targetRows`×`targetCols` — Scala's `broadcastTo`.
    ///
    /// A dimension of 1 is stretched by setting its stride to 0; anything else must
    /// already match. Returns `self` unchanged when the shape already fits.
    ///
    /// # Panics
    /// If a dimension is neither equal to the target nor 1, mirroring NumPy and Scala.
    pub fn broadcastTo(&self, targetRows: usize, targetCols: usize) -> Self {
        if self.rows == targetRows && self.cols == targetCols {
            return self.clone();
        }
        assert!(
            (self.rows == targetRows || self.rows == 1)
                && (self.cols == targetCols || self.cols == 1),
            "cannot broadcast shape {:?} to ({targetRows}, {targetCols})",
            self.shape()
        );
        let new_rs = if self.rows == 1 && targetRows > 1 {
            0
        } else {
            self.rs
        };
        let new_cs = if self.cols == 1 && targetCols > 1 {
            0
        } else {
            self.cs
        };
        let lay = Layout {
            transposed: self.transposed,
            offset: self.offset,
            rs: Some(new_rs),
            cs: Some(new_cs),
        };
        Self::createView(&self.data, targetRows, targetCols, lay)
    }

    /// Rectangular gather — Scala's `m(rows, cols)` overload, which **copies** into a
    /// fresh contiguous matrix rather than returning a view. Named per the `PARITY.md`
    /// contract, since the Scala spelling is an `apply` overload with no Rust analog.
    ///
    /// # Panics
    /// If either range leaves the matrix.
    pub fn applyRowsCols(&self, rows: Range<usize>, cols: Range<usize>) -> Self {
        assert!(
            rows.end <= self.rows
                && cols.end <= self.cols
                && rows.start <= rows.end
                && cols.start <= cols.end,
            "({rows:?}, {cols:?}) out of bounds for {}x{}",
            self.rows,
            self.cols
        );
        let (n_rows, n_cols) = (rows.end - rows.start, cols.end - cols.start);
        let mut out = Vec::with_capacity(n_rows * n_cols);
        for i in rows {
            for j in cols.clone() {
                out.push(self.at(i, j));
            }
        }
        Self::create(out, n_rows, n_cols)
    }

    /// Scala's `m(rows, ::)` — a range of rows, every column.
    pub fn applyRowsAll(&self, rows: Range<usize>) -> Self {
        self.applyRowsCols(rows, 0..self.cols)
    }

    /// Scala's `m(::, cols)` — every row, a range of columns.
    pub fn applyAllCols(&self, cols: Range<usize>) -> Self {
        self.applyRowsCols(0..self.rows, cols)
    }

    /// Scala's `m(::, col)` — one column as an `n`×1 column vector.
    pub fn applyAllCol(&self, col: usize) -> Self {
        self.applyRowsCols(0..self.rows, col..col + 1)
    }

    /// Scala's `m(row, ::)` — one row as a 1×n row vector.
    pub fn applyRowAll(&self, row: usize) -> Self {
        self.applyRowsCols(row..row + 1, 0..self.cols)
    }

    /// Scala's `m(rows, col)` — a range of rows from one column, as a column vector.
    pub fn applyRowsCol(&self, rows: Range<usize>, col: usize) -> Self {
        self.applyRowsCols(rows, col..col + 1)
    }

    /// Scala's `m(row, cols)` — a range of columns from one row, as a row vector.
    pub fn applyRowCols(&self, row: usize, cols: Range<usize>) -> Self {
        self.applyRowsCols(row..row + 1, cols)
    }

    /// First `min(n, rows)` rows, all columns — Scala's `head(n)`.
    ///
    /// Note this is row-oriented even for a column vector, exactly as in Scala: for the
    /// `n`×1 shape [`MatD::apply`] produces, it yields the first `n` elements.
    pub fn head(&self, n: usize) -> Self {
        self.applyRowsAll(0..n.min(self.rows))
    }

    /// Last `min(n, rows)` rows, all columns — Scala's `tail(n)`.
    pub fn tail(&self, n: usize) -> Self {
        self.applyRowsAll(self.rows.saturating_sub(n)..self.rows)
    }

    /// Rows selected by index — Scala's `m(rowIndices, ::)`. Gathers a fresh matrix.
    ///
    /// # Panics
    /// If any index is out of bounds.
    pub fn applyRowsIdx(&self, rowIndices: &[usize]) -> Self {
        let mut out = Vec::with_capacity(rowIndices.len() * self.cols);
        for &r in rowIndices {
            assert!(
                r < self.rows,
                "row index {r} out of bounds for {} rows",
                self.rows
            );
            for c in 0..self.cols {
                out.push(self.at(r, c));
            }
        }
        Self::create(out, rowIndices.len(), self.cols)
    }

    /// Columns selected by index — Scala's `m(::, colIndices)`.
    ///
    /// # Panics
    /// If any index is out of bounds.
    pub fn applyIdxCols(&self, colIndices: &[usize]) -> Self {
        let mut out = Vec::with_capacity(self.rows * colIndices.len());
        for r in 0..self.rows {
            for &c in colIndices {
                assert!(
                    c < self.cols,
                    "column index {c} out of bounds for {} cols",
                    self.cols
                );
                out.push(self.at(r, c));
            }
        }
        Self::create(out, self.rows, colIndices.len())
    }

    /// The rectangle of both index lists — Scala's `m(rowIndices, colIndices)`.
    ///
    /// Note this is NumPy's `ix_` behaviour, not NumPy's bare fancy indexing: it selects
    /// the cross product, not the zipped diagonal. Scala's overload does the same.
    ///
    /// # Panics
    /// If any index is out of bounds.
    pub fn applyIdxIdx(&self, rowIndices: &[usize], colIndices: &[usize]) -> Self {
        let mut out = Vec::with_capacity(rowIndices.len() * colIndices.len());
        for &r in rowIndices {
            assert!(
                r < self.rows,
                "row index {r} out of bounds for {} rows",
                self.rows
            );
            for &c in colIndices {
                assert!(
                    c < self.cols,
                    "column index {c} out of bounds for {} cols",
                    self.cols
                );
                out.push(self.at(r, c));
            }
        }
        Self::create(out, rowIndices.len(), colIndices.len())
    }

    /// Hands the buffer over when nothing else holds it — the engine behind
    /// [`MatD::intoMut`]. Strides come along, so a view whose parent has been dropped
    /// still works.
    ///
    /// # Panics
    /// If the buffer is shared. See [`MatD::intoMut`] for why this panics rather than
    /// returning a `Result`.
    #[expect(
        clippy::panic,
        reason = "the panic IS the design: a Result here would propagate into every \
                  caller of every ported function that mutates, changing the shape of \
                  the port relative to the Scala it mirrors"
    )]
    pub(crate) fn intoOwnedParts(self) -> MatParts {
        let (rows, cols, transposed, offset, rs, cs) = (
            self.rows,
            self.cols,
            self.transposed,
            self.offset,
            self.rs,
            self.cs,
        );
        match Arc::try_unwrap(self.data) {
            Ok(data) => MatParts {
                data,
                rows,
                cols,
                transposed,
                offset,
                rs,
                cs,
            },
            Err(_) => panic!(
                "matrix buffer is shared, so it cannot be made mutable; \
                 a view or clone taken from it is still alive"
            ),
        }
    }

    /// Rebuilds from parts a `MatMut` hands back. Not public: the only caller is
    /// `MatMut::freeze`, and the parts are its own.
    pub(crate) fn fromOwnedParts(p: MatParts) -> Self {
        Self {
            data: Arc::new(p.data),
            rows: p.rows,
            cols: p.cols,
            transposed: p.transposed,
            offset: p.offset,
            rs: p.rs,
            cs: p.cs,
        }
    }

    /// Same elements in logical order, re-laid out — Scala's `reshape`.
    ///
    /// # Panics
    /// If the new shape holds a different number of elements.
    pub fn reshape(&self, rows: usize, cols: usize) -> Self {
        assert_eq!(
            rows * cols,
            self.size(),
            "reshape to {rows}x{cols} does not preserve {} elements",
            self.size()
        );
        Self::create(self.flatten(), rows, cols)
    }
}

/// Scala's `Range`-start normalisation: a negative start counts from the end, and the
/// length is the range's own, not the clamped span. Returns `(start, length)`.
fn normalize_range(r: &Range<i64>, extent: usize) -> (i64, i64) {
    let start = if r.start < 0 {
        extent as i64 + r.start
    } else {
        r.start
    };
    (start, (r.end - r.start).max(0))
}

// ── Elementwise operations ──────────────────────────────────────────────────────

impl MatD {
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
    pub fn power(&self, n: u32) -> Self {
        self.map_elems(|x| {
            let mut result = 1.0f64;
            for _ in 0..n {
                result *= x;
            }
            result
        })
    }

    /// Elementwise `e^x` — Scala's `exp`, i.e. `m.map(math.exp)`.
    ///
    /// This is the one operation here that is NOT bit-identical to the Scala side, and
    /// the reason is on the JVM's end, not this one. Measured over 200,000 values:
    /// `f64::exp`, the platform C `exp` and NumPy's `np.exp` agree exactly — 0
    /// differences — while the JVM's `Math.exp` differs from all three on 0.561%, never
    /// by more than 1 ulp. `Math.exp` is a hand-written HotSpot assembly intrinsic, not
    /// a libm call, so no platform C library matches it; routing this through
    /// `extern "C" { fn exp(f64) -> f64 }` would change nothing, because that is already
    /// the symbol `f64::exp` resolves to on targets with a system libm.
    ///
    /// The `expq` case in `test-data/mat-parity/` therefore digests every element on a
    /// 2^-40 grid, keeping the agreement an observed, stated bound rather than an
    /// assumption.
    pub fn exp(&self) -> Self {
        self.map_elems(f64::exp)
    }

    /// Elementwise natural log — Scala's `log`. Carries the same ~1 ulp caveat as
    /// [`MatD::exp`]: measured, `Math.log` and libm's `log` differ on 0.235% of a
    /// 200,000-value corpus.
    pub fn log(&self) -> Self {
        self.map_elems(f64::ln)
    }

    /// Elementwise square root — Scala's `sqrt`. Exact in IEEE-754, so unlike
    /// [`MatD::exp`] this one *is* bit-identical.
    pub fn sqrt(&self) -> Self {
        self.map_elems(f64::sqrt)
    }

    /// Elementwise clamp to `[lower, upper]` — Scala's `clip`.
    ///
    /// Written as Scala's two comparisons rather than `f64::clamp`, which panics on a
    /// NaN bound and orders NaN inputs differently.
    pub fn clip(&self, lower: f64, upper: f64) -> Self {
        self.map_elems(|x| {
            if x < lower {
                lower
            } else if x > upper {
                upper
            } else {
                x
            }
        })
    }

    /// Running total over the row-major flattening, returned as a **1×n row** matrix.
    ///
    /// The shape change is Scala's, not an accident: `cumsum` there ends with
    /// `Mat.create(result, 1, a.length)` whatever the input shape was.
    pub fn cumsum(&self) -> Self {
        // A prefix scan is a serial dependency chain, so the arithmetic floor is one
        // FP-add latency per element and neither language beats it — Scala sits on that
        // floor at 0.77ms, so C2 is not vectorising the scan either. What separates the
        // two is the OUTPUT BUFFER. Five safe spellings, measured at 1000x1000:
        //
        //   with_capacity + extend(scan)   1.50 ms   <- this one
        //   with_capacity + push           1.53 ms
        //   vec![0.0; n] + indexed         2.19 ms   allocator reuses the block, so the
        //                                            zero-fill is a real memset
        //   to_vec + prefix in place       2.48 ms   32MB of traffic instead of 16
        //   scan().collect()               3.47 ms   `Scan` is NOT TrustedLen: its
        //                                            size_hint lower bound is 0, so
        //                                            collect starts at capacity 0 and
        //                                            grows by doubling
        //
        // The last two lines are the useful pair: the same iterator into a pre-reserved
        // Vec costs 1.50, so the 3.47 was reallocation growth, not the iterator.
        //
        // The JVM hands out array storage from an already-zeroed heap region and writes
        // by index with no per-element bookkeeping; Rust pays either a memset or a
        // capacity check. Closing the rest needs `spare_capacity_mut` + `set_len`, and
        // this crate keeps `unsafe` for FFI only.
        let n = self.size();
        let mut out: Vec<f64> = Vec::with_capacity(n);
        if self.fast_d() {
            out.extend(self.data.iter().scan(0.0f64, |acc, &x| {
                *acc += x;
                Some(*acc)
            }));
        } else {
            let mut acc = 0.0f64;
            for i in 0..self.rows {
                for j in 0..self.cols {
                    acc += self.at(i, j);
                    out.push(acc);
                }
            }
        }
        Self::create(out, 1, n)
    }

    /// Elementwise map in logical row-major order, producing a fresh contiguous matrix.
    ///
    /// Internal: Scala's `map` is public and generic, and lands in a later phase. Reads
    /// through the stride equation, as Scala's Double fast path does, so it is correct
    /// for views.
    pub(crate) fn map_elems(&self, f: impl Fn(f64) -> f64 + Sync + Send) -> Self {
        if self.fast_d() {
            // Contiguous: read the source slice linearly and collect. Two costs go away.
            // `at(r, c)` carries a multiply the compiler cannot strength-reduce, because
            // the strides are runtime values; and collecting allocates without the
            // zero-fill that `vec![0.0; n]` does before being fully overwritten.
            let src: &[f64] = &self.data;
            let out: Vec<f64> = if src.len() >= BCAST_PARALLEL_THRESHOLD {
                src.par_iter().map(|&x| f(x)).collect()
            } else {
                src.iter().map(|&x| f(x)).collect()
            };
            return Self::create(out, self.rows, self.cols);
        }
        self.fill_rows(|r, row| {
            for (c, slot) in row.iter_mut().enumerate() {
                *slot = f(self.at(r, c));
            }
        })
    }

    /// Broadcasting elementwise combine — Scala's `binOp`.
    ///
    /// The target shape is the elementwise max of the two, each operand is stretched to
    /// it, and the result is a fresh contiguous matrix. Scala also has a `fastBinOp`
    /// short-circuit for the same-shape, 1×N and N×1 cases; it produces identical values
    /// in identical order, so there is one path here.
    ///
    /// # Panics
    /// If the shapes do not broadcast.
    fn bin_op(&self, other: &Self, op: impl Fn(f64, f64) -> f64 + Sync + Send) -> Self {
        let target_rows = self.rows.max(other.rows);
        let target_cols = self.cols.max(other.cols);
        let a = self.broadcastTo(target_rows, target_cols);
        let b = other.broadcastTo(target_rows, target_cols);
        if a.fast_d() && b.fast_d() {
            // Both contiguous and the same shape: zip the two slices. See `map_elems`.
            let (sa, sb): (&[f64], &[f64]) = (&a.data, &b.data);
            let out: Vec<f64> = if sa.len() >= BCAST_PARALLEL_THRESHOLD {
                sa.par_iter()
                    .zip(sb.par_iter())
                    .map(|(&x, &y)| op(x, y))
                    .collect()
            } else {
                sa.iter().zip(sb.iter()).map(|(&x, &y)| op(x, y)).collect()
            };
            return Self::create(out, target_rows, target_cols);
        }
        a.fill_rows(|r, row| {
            for (c, slot) in row.iter_mut().enumerate() {
                *slot = op(a.at(r, c), b.at(r, c));
            }
        })
    }

    /// Builds a fresh contiguous matrix of this shape by filling one row at a time,
    /// spread across threads above [`BCAST_PARALLEL_THRESHOLD`] — Scala's `bcastRows`.
    ///
    /// Rows write disjoint slices and nothing is reduced, so the output is bit-identical
    /// however the work is scheduled. The single shape behind every elementwise
    /// operation here.
    fn fill_rows(&self, f: impl Fn(usize, &mut [f64]) + Sync + Send) -> Self {
        let (rows, cols) = (self.rows, self.cols);
        let mut out = vec![0.0f64; rows * cols];
        if rows * cols >= BCAST_PARALLEL_THRESHOLD {
            out.par_chunks_mut(cols.max(1))
                .enumerate()
                .for_each(|(r, row)| f(r, row));
        } else {
            for (r, row) in out.chunks_mut(cols.max(1)).enumerate() {
                f(r, row);
            }
        }
        Self::create(out, rows, cols)
    }
}

// ── Whole-matrix reductions ─────────────────────────────────────────────────────

impl MatD {
    /// Sum of every element — Scala's `sum`.
    ///
    /// On a contiguous matrix at offset 0 whose buffer is exactly `rows * cols` long,
    /// this is `sumD`: 8 unrolled accumulators, chunked above [`PARALLEL_THRESHOLD`].
    /// Anything else is a plain sequential row-major fold — a **different** association
    /// order and a different last ulp. Which one a matrix gets is a property of its
    /// layout, so `m.sum()` and `m.T().sum()` are not required to agree, in either
    /// language.
    ///
    /// Scala writes this as three branches, not two, and does not route it through
    /// `fastD`: `sumD`; a stride-indexed loop over the raw array for a contiguous view
    /// at offset 0 that shares a longer parent buffer; and the general
    /// `Numeric`-dispatched loop, which reads through that same stride equation rather
    /// than degrading to anything else. The middle branch holds `offset == 0`, which makes
    /// `data(i * rs + j * cs)` identical to `at(i, j)` — so the two loops are the same
    /// arithmetic in the same order, and collapsing them here changes no bit.
    pub fn sum(&self) -> f64 {
        if self.fast_d() {
            sum_d(&self.data)
        } else {
            self.strided_sum()
        }
    }

    /// Sum of a strided view, in the same shape as [`sum_d`] but over the logical
    /// row-major sequence: 8 unrolled accumulators, then `min(MAX_SUM_CHUNKS, rows / 8)`
    /// row blocks whose partials are combined sequentially in block order.
    ///
    /// CHANGED in 0.16.1, in lockstep with `Mat.sumStrided` on the Scala side, and the
    /// fixture regenerated. It was a single sequential accumulator, which no amount of
    /// tuning could improve without moving its last ulp. The contiguous path is
    /// untouched.
    ///
    /// One rule now covers every layout: **8 unrolled accumulators over the logical
    /// row-major sequence, chunked, partials combined in order.** What differs between a
    /// matrix and its transpose is the sequence, not the algorithm.
    ///
    /// Accumulators carry across row boundaries within a block, and each row's sub-8
    /// tail goes to its own carried accumulator — that is what makes the result a
    /// function of shape and strides alone, not of how rows divided into blocks.
    fn strided_sum(&self) -> f64 {
        let n = self.rows * self.cols;
        if n < STRIDED_SUM_PARALLEL_THRESHOLD || self.rows < 8 {
            return self.sum_row_block(0, self.rows);
        }
        let chunks = MAX_SUM_CHUNKS.min((self.rows / 8).max(1));
        let step = self.rows.div_ceil(chunks);
        let partials: Vec<f64> = (0..chunks)
            .into_par_iter()
            .map(|c| {
                let from = c * step;
                let until = (from + step).min(self.rows);
                if from < until {
                    self.sum_row_block(from, until)
                } else {
                    0.0
                }
            })
            .collect();
        let mut total = 0.0f64;
        for p in partials {
            total += p;
        }
        total
    }

    /// Rows `[r0, r1)`, 8 unrolled accumulators carried across rows. Mirrors Scala's
    /// `sumRowBlock` exactly; the association order here is the contract.
    fn sum_row_block(&self, r0: usize, r1: usize) -> f64 {
        let (mut s0, mut s1, mut s2, mut s3) = (0.0f64, 0.0f64, 0.0f64, 0.0f64);
        let (mut s4, mut s5, mut s6, mut s7) = (0.0f64, 0.0f64, 0.0f64, 0.0f64);
        let mut tail = 0.0f64;
        let a = &self.data;
        let (off, rs, cs, cols) = (self.offset, self.rs, self.cs, self.cols);
        // Scala computes `cols - 7` in Int arithmetic, where a short row yields a
        // negative limit and the unrolled loop does not run.
        let limit = cols.saturating_sub(7);
        for r in r0..r1 {
            let base = off + r * rs;
            let mut j = 0;
            while j < limit {
                s0 += a[base + j * cs];
                s1 += a[base + (j + 1) * cs];
                s2 += a[base + (j + 2) * cs];
                s3 += a[base + (j + 3) * cs];
                s4 += a[base + (j + 4) * cs];
                s5 += a[base + (j + 5) * cs];
                s6 += a[base + (j + 6) * cs];
                s7 += a[base + (j + 7) * cs];
                j += 8;
            }
            while j < cols {
                tail += a[base + j * cs];
                j += 1;
            }
        }
        (((s0 + s1) + (s2 + s3)) + ((s4 + s5) + (s6 + s7))) + tail
    }

    /// Arithmetic mean — Scala's `mean`: the matrix sum over the element count, one
    /// division. Inherits [`MatD::sum`]'s layout-dependent association order.
    ///
    /// Returns `0.0` for an empty matrix, matching Scala's `frac.zero` branch.
    pub fn mean(&self) -> f64 {
        if self.isEmpty() {
            return 0.0;
        }
        self.sum() / self.size() as f64
    }

    /// Population variance (NumPy's `ddof=0`) — Scala's `variance`.
    ///
    /// The mean is taken by [`MatD::sum`] (so it follows the layout), and the squared
    /// deviations by a plain fold in both branches.
    pub fn variance(&self) -> f64 {
        let n = self.size() as f64;
        let mu = self.sum() / n;
        let mut sum_sq = 0.0f64;
        for i in 0..self.rows {
            for j in 0..self.cols {
                let d = self.at(i, j) - mu;
                sum_sq += d * d;
            }
        }
        sum_sq / n
    }

    /// Population standard deviation — Scala's `std`, i.e. `sqrt(variance)`.
    pub fn std(&self) -> f64 {
        self.variance().sqrt()
    }

    /// L2 norm of a vector — Scala's `norm`.
    ///
    /// # Panics
    /// If the matrix is not 1×n or n×1, mirroring Scala's `require`.
    pub fn norm(&self) -> f64 {
        assert!(
            self.cols == 1 || self.rows == 1,
            "norm requires a vector (1xn or nx1), got {:?}",
            self.shape()
        );
        let mut sum_sq = 0.0f64;
        for i in 0..self.rows {
            for j in 0..self.cols {
                let x = self.at(i, j);
                sum_sq += x * x;
            }
        }
        sum_sq.sqrt()
    }

    /// Smallest element — Scala's `min`.
    ///
    /// Compares with [`java_double_compare`], not `<`: Scala scans through
    /// `Ordering[Double]`, under which `-0.0 < 0.0` and NaN outranks every number.
    /// `min(&[0.0, -0.0])` is therefore `-0.0`. Seeded from element (0,0) and replaced
    /// only on a strict `Less`, so ties keep their first occurrence.
    ///
    /// # Panics
    /// On an empty matrix, mirroring Scala's `UnsupportedOperationException`.
    pub fn min(&self) -> f64 {
        assert!(!self.isEmpty(), "min of an empty matrix");
        self.scan(lt_total).1
    }

    /// Largest element — Scala's `max`. See [`MatD::min`] on the comparison; here it
    /// means `max(&[1.0, NaN, -1.0, 2.0])` is **NaN**, as it is in Scala.
    ///
    /// # Panics
    /// On an empty matrix, mirroring Scala's `UnsupportedOperationException`.
    pub fn max(&self) -> f64 {
        assert!(!self.isEmpty(), "max of an empty matrix");
        self.scan(gt_total).1
    }

    /// `(row, col)` of the smallest element — Scala's `argmin`. First occurrence wins.
    ///
    /// # Panics
    /// On an empty matrix.
    pub fn argmin(&self) -> (usize, usize) {
        assert!(!self.isEmpty(), "argmin of an empty matrix");
        self.scan(lt_total).0
    }

    /// `(row, col)` of the largest element — Scala's `argmax`. First occurrence wins.
    ///
    /// # Panics
    /// On an empty matrix.
    pub fn argmax(&self) -> (usize, usize) {
        assert!(!self.isEmpty(), "argmax of an empty matrix");
        self.scan(gt_total).0
    }

    /// Row-major scan seeded from (0,0), replacing the accumulator whenever `better`
    /// holds. The single shape behind `min`/`max`/`argmin`/`argmax`, all four of which
    /// Scala writes as this same loop.
    fn scan(&self, better: impl Fn(f64, f64) -> bool + Sync + Send) -> ((usize, usize), f64) {
        if self.rows * self.cols < SCAN_PARALLEL_THRESHOLD || self.rows < 8 {
            return self.scan_rows(0, self.rows, &better);
        }
        // Split by row block. This cannot move the answer -- an extremum is associative
        // and commutative under a total order -- and the combine below runs in block
        // order replacing only on a strictly better value, so each block keeps its own
        // first occurrence and the earliest block wins a tie, exactly as the sequential
        // scan does.
        let chunks = MAX_SUM_CHUNKS.min((self.rows / 8).max(1));
        let step = self.rows.div_ceil(chunks);
        let partials: Vec<Option<((usize, usize), f64)>> = (0..chunks)
            .into_par_iter()
            .map(|c| {
                let from = c * step;
                let until = (from + step).min(self.rows);
                if from < until {
                    Some(self.scan_rows(from, until, &better))
                } else {
                    None
                }
            })
            .collect();
        let mut acc: Option<((usize, usize), f64)> = None;
        for p in partials.into_iter().flatten() {
            match acc {
                None => acc = Some(p),
                Some((_, best)) if better(p.1, best) => acc = Some(p),
                _ => {}
            }
        }
        acc.unwrap_or(((0, 0), self.at(0, 0)))
    }

    /// Rows `[r0, r1)`, seeded from the first cell of `r0`.
    fn scan_rows(
        &self,
        r0: usize,
        r1: usize,
        better: &(impl Fn(f64, f64) -> bool + Sync + Send),
    ) -> ((usize, usize), f64) {
        let mut best = self.at(r0, 0);
        let mut at = (r0, 0usize);
        for i in r0..r1 {
            for j in 0..self.cols {
                let current = self.at(i, j);
                if better(current, best) {
                    best = current;
                    at = (i, j);
                }
            }
        }
        (at, best)
    }
}

// ── Traits ──────────────────────────────────────────────────────────────────────

/// Logical equality: same shape, same elements in row-major order. Two matrices with
/// different layouts over different buffers compare equal if they read the same, which
/// is what a caller means by `==`. NaN never equals NaN, as everywhere else in IEEE-754.
impl PartialEq for MatD {
    fn eq(&self, other: &Self) -> bool {
        self.shape() == other.shape()
            && (0..self.rows).all(|i| (0..self.cols).all(|j| self.at(i, j) == other.at(i, j)))
    }
}

/// Shape, layout and *logical* contents — never the raw backing buffer, which for a view
/// is the parent's storage and would be actively misleading.
impl fmt::Debug for MatD {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "MatD({}x{}", self.rows, self.cols)?;
        if !self.fast_d() {
            write!(
                f,
                " view[t={} off={} rs={} cs={}]",
                self.transposed, self.offset, self.rs, self.cs
            )?;
        }
        write!(f, " {:?})", self.flatten())
    }
}

/// `m[(r, c)]` as Rust sugar over [`MatD::at`]. Only scalar reads can use bracket
/// syntax — `Index` must return a reference, so slicing stays method-shaped.
impl Index<(usize, usize)> for MatD {
    type Output = f64;

    fn index(&self, (r, c): (usize, usize)) -> &f64 {
        &self.data[self.offset + r * self.rs + c * self.cs]
    }
}

impl From<&[f64]> for MatD {
    fn from(arr: &[f64]) -> Self {
        Self::apply(arr)
    }
}

impl Neg for &MatD {
    type Output = MatD;

    fn neg(self) -> MatD {
        self.map_elems(|x| -x)
    }
}

impl Neg for MatD {
    type Output = MatD;

    fn neg(self) -> MatD {
        self.map_elems(|x| -x)
    }
}

macro_rules! elementwise_binop {
    ($trait:ident, $method:ident, $op:tt) => {
        // MatD ⊕ MatD, elementwise WITH BROADCASTING. Scala's `*` on two Mats aliases
        // `*:*`, so `Mul` here is elementwise and NOT matrix multiplication.
        impl $trait<&MatD> for &MatD {
            type Output = MatD;
            fn $method(self, rhs: &MatD) -> MatD {
                self.bin_op(rhs, |a, b| a $op b)
            }
        }
        impl $trait<MatD> for MatD {
            type Output = MatD;
            fn $method(self, rhs: MatD) -> MatD {
                self.bin_op(&rhs, |a, b| a $op b)
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
    };
}

elementwise_binop!(Add, add, +);
elementwise_binop!(Sub, sub, -);
elementwise_binop!(Mul, mul, *);
elementwise_binop!(Div, div, /);

macro_rules! scalar_on_the_left {
    ($trait:ident, $method:ident, $op:tt) => {
        // Scala defines these separately (Mat.scala:656-658) precisely so `1.0 - m`
        // means `m.map(1.0 - _)` and not `m.map(_ - 1.0)`.
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

scalar_on_the_left!(Add, add, +);
scalar_on_the_left!(Sub, sub, -);
scalar_on_the_left!(Mul, mul, *);

#[cfg(test)]
mod tests {
    use super::*;

    /// A deterministic, badly-conditioned sequence: mixing magnitudes is what makes
    /// association order observable at all.
    fn probe(n: usize) -> Vec<f64> {
        (0..n)
            .map(|i| (i as f64).sin() * 1e3 + 1e-9 * i as f64)
            .collect()
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
        let mut want =
            ((acc[0] + acc[1]) + (acc[2] + acc[3])) + ((acc[4] + acc[5]) + (acc[6] + acc[7]));
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
        assert!(
            got[0].is_sign_negative(),
            "-0.0 must survive as -0.0, not become +0.0"
        );
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

    #[test]
    fn transpose_is_a_view_that_reads_transposed() {
        let m = MatD::create(vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0], 2, 3);
        let t = m.transpose();
        assert_eq!(t.shape(), (3, 2));
        assert!(
            t.transposed() && !t.fast_d(),
            "transpose must stay a view, not materialise"
        );
        assert_eq!(t.toArray(), vec![1.0, 4.0, 2.0, 5.0, 3.0, 6.0]);
        assert_eq!(t.transpose(), m, "transposing twice is the identity");
    }

    #[test]
    fn a_transposed_sum_takes_the_slow_path_and_may_differ() {
        // The whole reason MatD carries strides. Association order follows layout, so
        // these two are NOT required to agree — and on a badly-conditioned corpus they
        // do not. If this ever passes trivially, pick a nastier probe.
        let a = probe(8192);
        let m = MatD::create(a, 64, 128);
        assert!(m.fast_d() && !m.transpose().fast_d());
        assert_ne!(
            m.sum().to_bits(),
            m.transpose().sum().to_bits(),
            "chunked sumD and the strided fold happened to agree; the fixture needs a \
             corpus that distinguishes them"
        );
    }

    #[test]
    fn slice_is_a_view_but_a_fragmented_one_materialises() {
        // 3x5: dropping a column leaves rs=5 > rows=3, which Scala calls fragmented and
        // copies — putting it back on the fast path.
        let wide = MatD::create((1..=15).map(f64::from).collect(), 3, 5);
        let cut = wide.slice(0..3, 1..5);
        assert!(
            cut.fast_d(),
            "a fragmented slice is materialised, as Internal.create does"
        );
        assert_eq!(
            cut.toArray(),
            vec![
                2.0, 3.0, 4.0, 5.0, 7.0, 8.0, 9.0, 10.0, 12.0, 13.0, 14.0, 15.0
            ]
        );

        // 5x3: the same logical operation leaves rs=3 <= rows=5, so it stays a view.
        let tall = MatD::create((1..=15).map(f64::from).collect(), 5, 3);
        let kept = tall.slice(0..5, 1..3);
        assert!(
            !kept.fast_d(),
            "aspect ratio decides; this one stays a view"
        );
        assert_eq!(kept.at(0, 0), 2.0);
        assert_eq!(kept.at(4, 1), 15.0);
    }

    #[test]
    fn slice_accepts_a_negative_start() {
        let m = MatD::apply(&[1.0, 2.0, 3.0, 4.0]);
        // -2 until 0: length 2 starting two from the end, exactly as Scala reads it.
        assert_eq!(m.slice(-2..0, 0..1).toArray(), vec![3.0, 4.0]);
    }

    #[test]
    fn broadcast_stretches_a_length_one_axis() {
        let m = MatD::create(vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0], 2, 3);
        let row = MatD::create(vec![10.0, 20.0, 30.0], 1, 3);
        assert_eq!(
            (&m + &row).toArray(),
            vec![11.0, 22.0, 33.0, 14.0, 25.0, 36.0]
        );
        let col = MatD::create(vec![100.0, 200.0], 2, 1);
        assert_eq!(
            (&m + &col).toArray(),
            vec![101.0, 102.0, 103.0, 204.0, 205.0, 206.0]
        );
    }

    #[test]
    fn argmin_and_argmax_keep_the_first_occurrence() {
        let m = MatD::create(vec![3.0, 1.0, 5.0, 1.0, 5.0, 2.0], 2, 3);
        assert_eq!(m.argmin(), (0, 1));
        assert_eq!(m.argmax(), (0, 2));
    }

    #[test]
    fn order_statistics_follow_scalas_total_ordering_not_the_primitive_comparisons() {
        // The bug this pins: `<`/`>` disagree with Scala on exactly two inputs, and the
        // random parity corpus contains neither, so the 427-row fixture passed while
        // these were wrong.
        let nan = MatD::apply(&[1.0, f64::NAN, -1.0, 2.0]);
        assert!(
            nan.max().is_nan(),
            "Ordering[Double] ranks NaN above every number"
        );
        assert_eq!(nan.argmax(), (1, 0));
        assert_eq!(nan.min(), -1.0, "but NaN never becomes the minimum");
        assert_eq!(nan.argmin(), (2, 0));

        let zeros = MatD::apply(&[0.0, -0.0]);
        assert!(
            zeros.min().is_sign_negative(),
            "-0.0 < 0.0 under TotalOrdering"
        );
        assert_eq!(zeros.argmin(), (1, 0), "the scan must move off index 0");
        assert!(zeros.max().is_sign_positive());
        assert_eq!(zeros.argmax(), (0, 0), "0.0 is already the max at index 0");
    }

    #[test]
    fn java_double_compare_is_not_total_cmp() {
        // Agree everywhere except negative NaN, which the JVM canonicalises away and
        // IEEE totalOrder sorts below -Infinity.
        let neg_nan = f64::from_bits(0xfff8_0000_0000_0000);
        assert!(neg_nan.is_nan() && neg_nan.is_sign_negative());
        assert_eq!(
            java_double_compare(neg_nan, f64::NEG_INFINITY),
            Ordering::Greater
        );
        assert_eq!(neg_nan.total_cmp(&f64::NEG_INFINITY), Ordering::Less);
        assert_eq!(java_double_compare(neg_nan, f64::NAN), Ordering::Equal);
        // And on the two rules that matter for min/max, it agrees with the JVM.
        assert_eq!(java_double_compare(-0.0, 0.0), Ordering::Less);
        assert_eq!(
            java_double_compare(f64::NAN, f64::INFINITY),
            Ordering::Greater
        );
    }

    #[test]
    fn cumulative_extrema_propagate_nan_like_scala() {
        let m = MatD::create(vec![1.0, f64::NAN, 0.5, 3.0], 1, 4);
        let run = m.cummax(1).toArray();
        assert_eq!(run[0], 1.0);
        assert!(run[1].is_nan());
        assert!(run[2].is_nan(), "once NaN leads, nothing displaces it");
        assert!(run[3].is_nan());
    }

    #[test]
    fn norm_rejects_a_matrix() {
        let v = MatD::apply(&[3.0, 4.0]);
        assert_eq!(v.norm(), 5.0);
    }
}

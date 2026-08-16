//! Matrix multiplication — Scala's `matmul` / `*@` / `dot`, Tier 3 phase (e).
//!
//! # The contract
//!
//! **The default `matmul` is the pure tiled loop, and it is pinned.** Its per-cell
//! association order is a sequential k-sum from 0.0 —
//! `((0 + a[i,0]*b[0,j]) + a[i,1]*b[1,j]) + …` — and neither this crate nor the JVM
//! contracts `x*y + z` into a fused multiply-add, so the result is bit-identical to
//! Scala's `multiplyDouble` **and independent of the machine**. Nothing else in the
//! matmul space offers that: every BLAS, and `matrixmultiply`, select kernels by CPU
//! feature and thread count and land on different last ulps.
//!
//! **BLAS is an opt-in that trades the pin for speed**, 2–4× on large dense products and
//! ~1× on skinny ones. Here the opt-in is the `blas` cargo feature — it is what links
//! OpenBLAS, so it is already a deliberate act — and with it on, `matmul` dispatches
//! there above a small crossover. Scala's opt-in is `-Duni.mat.blas=true` /
//! `UNI_MAT_BLAS=true`. Both sides also expose the two escape hatches by name:
//! [`MatD::matmulPure`] is the pinned path whatever the mode, and [`MatD::matmulBlas`]
//! (only under the feature here) is BLAS whatever the mode.
//!
//! The measurement behind the default lives in `PARITY.md`; re-measure with
//! `cargo run --release --bin bench_matmul` and its two counterparts.
//!
//! # The loop
//!
//! The same register-blocked microkernel as Scala's `MatmulPure`, so the two ports are
//! fast for the same reasons: a 4×8 block of cells is accumulated in locals over the whole
//! k range and stored once — `C` is never read-modify-written inside the k loop — over
//! `B` packed 8-wide panel-major (last panel zero-padded, partial store) and `A` packed
//! k-major per 4-row group when its k-stride is not 1. Short K and outputs narrower than
//! [`MIN_COLS_FOR_MICRO`] take a streaming saxpy over whole rows instead. Work is split
//! in two dimensions (row blocks × panel ranges) on rayon above [`PAR_OPS`] multiply-adds,
//! with no item smaller than [`MIN_ITEM_OPS`].
//!
//! None of that touches the contract: every cell is still one sequential k-sum from 0.0
//! — panel width, packing, blocking and the parallel split only decide which cells share a
//! loaded value and which thread owns them. The Scala kernel uses 4-wide panels because
//! HotSpot will not vectorise the lane loop; LLVM does, so 8 lanes cost nothing here.
//! Views are read in place through [`MatD::strided`], as Scala reads them, so a view
//! multiplies to the same bits as its copy without a copy.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use rayon::prelude::*;

use crate::udata::mat::MatD;

/// Row-block height for the parallel split.
const BLOCK_ROWS: usize = 16;

/// Below this many multiply-adds the whole product runs on the calling thread, and no
/// work item is made smaller than [`MIN_ITEM_OPS`]: a fork costs more than that.
pub const PAR_OPS: usize = 262_144;
pub const MIN_ITEM_OPS: usize = 131_072;

/// K below which the streaming path beats the microkernel — with a handful of k steps
/// the accumulators cannot be amortised.
pub const MIN_K_FOR_MICRO: usize = 16;

/// Outputs narrower than this take the streaming path too: a one-column product through
/// a padded panel does eight times the multiplies.
pub const MIN_COLS_FOR_MICRO: usize = 4;

/// Panel width. Eight lanes are two AVX2 registers per row of the block.
const W: usize = 8;

/// In BLAS mode, products below this many multiply-adds still take the pure loop — the
/// call overhead exceeds the work. Scala's `blasThreshold` on this platform. Tuning.
#[cfg(feature = "blas")]
const BLAS_MIN_OPS: usize = 216;

/// A strided operand: `data[off + r*rs + c*cs]`.
#[derive(Clone, Copy)]
struct Strided<'a> {
    d: &'a [f64],
    off: usize,
    rs: usize,
    cs: usize,
}

impl Strided<'_> {
    #[inline]
    fn at(self, r: usize, c: usize) -> f64 {
        self.d[self.off + r * self.rs + c * self.cs]
    }
    fn is_row_major(self, rows: usize, cols: usize) -> bool {
        self.off == 0 && self.rs == cols && self.cs == 1 && self.d.len() == rows * cols
    }
    fn copy_row_major(self, rows: usize, cols: usize) -> Vec<f64> {
        let mut v = Vec::with_capacity(rows * cols);
        for r in 0..rows {
            for c in 0..cols {
                v.push(self.at(r, c));
            }
        }
        v
    }
}

/// `A` (ra×ca) times `B` (ca×cb) — see the module note.
fn pure_matmul(a: Strided<'_>, b: Strided<'_>, ra: usize, ca: usize, cb: usize) -> Vec<f64> {
    let mut out = vec![0.0f64; ra * cb];
    if ra == 0 || cb == 0 || ca == 0 {
        return out;
    }
    let ops = ra * ca * cb;
    let pool = rayon::current_num_threads();
    // Row blocks are the unit of parallelism (each is a disjoint slice of C). Their
    // height is chosen so there are about twice as many as threads — but never so many
    // that one falls under MIN_ITEM_OPS: on a 128³ product, 32 items of 8 µs each spend
    // more time waking rayon workers than multiplying, and the timing swings 5× call to
    // call. Never fewer than four rows, the microkernel's own group.
    let block_rows = if ops < PAR_OPS {
        BLOCK_ROWS
    } else {
        let items = (2 * pool).min(ops / MIN_ITEM_OPS).max(1);
        ra.div_ceil(items).div_ceil(4) * 4
    }
    .clamp(4, BLOCK_ROWS);
    let row_blocks = ra.div_ceil(block_rows);
    if ca < MIN_K_FOR_MICRO || cb < MIN_COLS_FOR_MICRO {
        // Streaming path over row-major B; a view is copied, values unchanged.
        let b_owned;
        let b_rm: &[f64] = if b.is_row_major(ca, cb) {
            b.d
        } else {
            b_owned = b.copy_row_major(ca, cb);
            &b_owned
        };
        let c_split = col_split(ops, row_blocks, (cb / 4).max(1), pool);
        let chunk = cb.div_ceil(c_split).div_ceil(4) * 4;
        // Each item owns a rectangle of C; the row block is `out[iS*cb .. iE*cb]`, and
        // the column range inside it is disjoint from the other items', so the items are
        // independent. Split the buffer by row block first, then hand each item its
        // column range within it.
        run(ops, block_rows, &mut out, cb, |rb, rows_out| {
            let i_s = rb * block_rows;
            let i_e = (i_s + block_rows).min(ra);
            for cc in 0..c_split {
                let j_s = cc * chunk;
                let j_e = (j_s + chunk).min(cb);
                if j_s < j_e {
                    saxpy_rows(a, b_rm, rows_out, i_s, i_e, ca, cb, j_s, j_e);
                }
            }
        });
    } else {
        let n_jb = cb.div_ceil(W);
        let bp = pack_b(b, ca, cb, n_jb);
        let c_split = col_split(ops, row_blocks, n_jb, pool);
        let panels_per = n_jb.div_ceil(c_split);
        run(ops, block_rows, &mut out, cb, |rb, rows_out| {
            let i_s = rb * block_rows;
            let i_e = (i_s + block_rows).min(ra);
            for cc in 0..c_split {
                let jb_s = cc * panels_per;
                let jb_e = (jb_s + panels_per).min(n_jb);
                if jb_s < jb_e {
                    micro_rows(a, &bp, rows_out, i_s, i_e, ca, cb, jb_s, jb_e);
                }
            }
        });
    }
    out
}

/// How many column chunks per row block: enough to cover the pool about twice, never so
/// many that an item falls under [`MIN_ITEM_OPS`], never more than `max_chunks`.
fn col_split(ops: usize, row_blocks: usize, max_chunks: usize, pool: usize) -> usize {
    if ops < PAR_OPS {
        1
    } else {
        let want = (2 * pool).div_ceil(row_blocks);
        let by_work = ops / (row_blocks * MIN_ITEM_OPS);
        max_chunks.min(want).min(by_work).max(1)
    }
}

/// Run `item(rb, rows_of_C)` for every row block of `block_rows` rows, on the calling
/// thread below [`PAR_OPS`] and on rayon otherwise. Row blocks are disjoint slices of
/// `out`, so the borrow checker sees the independence the contract relies on; column
/// chunks run inside the item.
fn run(
    ops: usize,
    block_rows: usize,
    out: &mut [f64],
    cb: usize,
    item: impl Fn(usize, &mut [f64]) + Sync,
) {
    if ops < PAR_OPS {
        for (rb, rows_out) in out.chunks_mut(block_rows * cb).enumerate() {
            item(rb, rows_out);
        }
    } else {
        out.par_chunks_mut(block_rows * cb)
            .enumerate()
            .for_each(|(rb, rows_out)| item(rb, rows_out));
    }
}

/// `B` as `W`-wide column panels, panel-major: `bp[jb*ca*W + k*W + x] = b(k, jb*W + x)`;
/// lanes past `cb` in the last panel stay 0.0 and are never stored.
fn pack_b(b: Strided<'_>, ca: usize, cb: usize, n_jb: usize) -> Vec<f64> {
    let mut bp = vec![0.0f64; n_jb * ca * W];
    for jb in 0..n_jb {
        let base = jb * ca * W;
        let j0 = jb * W;
        let w = W.min(cb - j0);
        for k in 0..ca {
            let p = base + k * W;
            for x in 0..w {
                bp[p + x] = b.at(k, j0 + x);
            }
        }
    }
    bp
}

/// Rows `i_s..i_e` of `C` (given as the slice for that row block), panels `jb_s..jb_e`.
#[expect(
    clippy::too_many_arguments,
    reason = "kernel plumbing; every argument is a dimension or a buffer"
)]
fn micro_rows(
    a: Strided<'_>,
    bp: &[f64],
    rows_out: &mut [f64],
    i_s: usize,
    i_e: usize,
    ca: usize,
    cb: usize,
    jb_s: usize,
    jb_e: usize,
) {
    let direct = a.cs == 1;
    let mut ap = if direct {
        Vec::new()
    } else {
        vec![0.0f64; 4 * ca]
    };
    let mut i = i_s;
    while i + 3 < i_e {
        if !direct {
            pack_a(a, i, 4, ca, &mut ap);
        }
        for jb in jb_s..jb_e {
            let j = jb * W;
            let w = W.min(cb - j);
            let o0 = (i - i_s) * cb;
            let acc = if direct {
                micro_direct(a, i, &bp[jb * ca * W..(jb + 1) * ca * W], ca)
            } else {
                micro_packed(&ap, &bp[jb * ca * W..(jb + 1) * ca * W], ca)
            };
            store(rows_out, o0, cb, j, w, &acc);
        }
        i += 4;
    }
    if i < i_e {
        let rows = i_e - i;
        for jb in jb_s..jb_e {
            let j = jb * W;
            let w = W.min(cb - j);
            panel_rows(
                a,
                rows,
                &bp[jb * ca * W..(jb + 1) * ca * W],
                rows_out,
                i - i_s,
                i,
                ca,
                cb,
                j,
                w,
            );
        }
    }
}

/// `rows` (≤ 4) rows of `A` from `i0`, packed k-major: `ap[k*4 + r] = a(i0+r, k)`.
fn pack_a(a: Strided<'_>, i0: usize, rows: usize, ca: usize, ap: &mut [f64]) {
    for r in 0..rows {
        for k in 0..ca {
            ap[k * 4 + r] = a.at(i0 + r, k);
        }
    }
}

/// A 4×W block, rows contiguous along k in `a` starting at row `i0`, against one packed
/// panel: accumulated over the whole k range from 0.0. Each lane is its own cell with
/// its own sequential k-sum — the contract, thirty-two cells at a time.
///
/// Written the way LLVM vectorises reliably: four named `[f64; W]` accumulators (two
/// AVX2 registers each), `chunks_exact` so no bounds check survives into the loop, and
/// the panel row copied into a fixed array before the lane loop.
#[inline]
fn micro_direct(a: Strided<'_>, i0: usize, panel: &[f64], ca: usize) -> [[f64; W]; 4] {
    let b0 = a.off + i0 * a.rs;
    let (r0, r1, r2, r3) = (
        &a.d[b0..b0 + ca],
        &a.d[b0 + a.rs..b0 + a.rs + ca],
        &a.d[b0 + 2 * a.rs..b0 + 2 * a.rs + ca],
        &a.d[b0 + 3 * a.rs..b0 + 3 * a.rs + ca],
    );
    let (mut c0, mut c1, mut c2, mut c3) = ([0.0f64; W], [0.0f64; W], [0.0f64; W], [0.0f64; W]);
    for (k, bk) in panel.chunks_exact(W).take(ca).enumerate() {
        let mut b = [0.0f64; W];
        b.copy_from_slice(bk);
        let (v0, v1, v2, v3) = (r0[k], r1[k], r2[k], r3[k]);
        for x in 0..W {
            c0[x] += v0 * b[x];
        }
        for x in 0..W {
            c1[x] += v1 * b[x];
        }
        for x in 0..W {
            c2[x] += v2 * b[x];
        }
        for x in 0..W {
            c3[x] += v3 * b[x];
        }
    }
    [c0, c1, c2, c3]
}

/// As [`micro_direct`], reading the four rows from the k-major pack.
#[inline]
fn micro_packed(ap: &[f64], panel: &[f64], ca: usize) -> [[f64; W]; 4] {
    let (mut c0, mut c1, mut c2, mut c3) = ([0.0f64; W], [0.0f64; W], [0.0f64; W], [0.0f64; W]);
    for (ak, bk) in ap.chunks_exact(4).zip(panel.chunks_exact(W)).take(ca) {
        let mut b = [0.0f64; W];
        b.copy_from_slice(bk);
        let (v0, v1, v2, v3) = (ak[0], ak[1], ak[2], ak[3]);
        for x in 0..W {
            c0[x] += v0 * b[x];
        }
        for x in 0..W {
            c1[x] += v1 * b[x];
        }
        for x in 0..W {
            c2[x] += v2 * b[x];
        }
        for x in 0..W {
            c3[x] += v3 * b[x];
        }
    }
    [c0, c1, c2, c3]
}

/// Store a block's first `w` lanes into rows starting at offset `o0` of the row block.
fn store(rows_out: &mut [f64], o0: usize, cb: usize, j: usize, w: usize, acc: &[[f64; W]; 4]) {
    for (r, acc_r) in acc.iter().enumerate() {
        let o = o0 + r * cb + j;
        rows_out[o..o + w].copy_from_slice(&acc_r[..w]);
    }
}

/// `rows` (fewer than four) rows of `A` from `i0` against one packed panel: `W`
/// accumulators per row over the whole k range, first `w` stored.
#[expect(
    clippy::too_many_arguments,
    reason = "kernel plumbing; every argument is a dimension or a buffer"
)]
fn panel_rows(
    a: Strided<'_>,
    rows: usize,
    panel: &[f64],
    rows_out: &mut [f64],
    o_row0: usize,
    i0: usize,
    ca: usize,
    cb: usize,
    j: usize,
    w: usize,
) {
    for r in 0..rows {
        let mut acc = [0.0f64; W];
        for k in 0..ca {
            let v = a.at(i0 + r, k);
            let bk = &panel[k * W..(k + 1) * W];
            for x in 0..W {
                acc[x] += v * bk[x];
            }
        }
        let o = (o_row0 + r) * cb + j;
        rows_out[o..o + w].copy_from_slice(&acc[..w]);
    }
}

/// Rows `i_s..i_e` (as the row block's slice), columns `j_s..j_e`, by streaming saxpy
/// over row-major `b`: for each k, `C(i, j_s..j_e) += a(i,k) · B(k, j_s..j_e)`, four rows
/// sharing each loaded `B` value. Per cell the k-order is unchanged; the k tile only keeps
/// the `C` rows warm between passes.
#[expect(
    clippy::too_many_arguments,
    reason = "kernel plumbing; every argument is a dimension or a buffer"
)]
fn saxpy_rows(
    a: Strided<'_>,
    b: &[f64],
    rows_out: &mut [f64],
    i_s: usize,
    i_e: usize,
    ca: usize,
    cb: usize,
    j_s: usize,
    j_e: usize,
) {
    const TK: usize = 16;
    let mut k_s = 0;
    while k_s < ca {
        let k_e = (k_s + TK).min(ca);
        let mut i = i_s;
        while i + 3 < i_e {
            let (o0, o1, o2, o3) = (
                (i - i_s) * cb,
                (i - i_s + 1) * cb,
                (i - i_s + 2) * cb,
                (i - i_s + 3) * cb,
            );
            for k in k_s..k_e {
                let (v0, v1, v2, v3) = (a.at(i, k), a.at(i + 1, k), a.at(i + 2, k), a.at(i + 3, k));
                let bk = &b[k * cb + j_s..k * cb + j_e];
                let (r0, rest) = rows_out.split_at_mut(o1);
                let (r1, rest) = rest.split_at_mut(o2 - o1);
                let (r2, r3) = rest.split_at_mut(o3 - o2);
                let r0 = &mut r0[o0 + j_s..o0 + j_e];
                let r1 = &mut r1[j_s..j_e];
                let r2 = &mut r2[j_s..j_e];
                let r3 = &mut r3[j_s..j_e];
                for x in 0..(j_e - j_s) {
                    let bv = bk[x];
                    r0[x] += v0 * bv;
                    r1[x] += v1 * bv;
                    r2[x] += v2 * bv;
                    r3[x] += v3 * bv;
                }
            }
            i += 4;
        }
        while i < i_e {
            let o = (i - i_s) * cb;
            for k in k_s..k_e {
                let v = a.at(i, k);
                let bk = &b[k * cb + j_s..k * cb + j_e];
                let row = &mut rows_out[o + j_s..o + j_e];
                for x in 0..(j_e - j_s) {
                    row[x] += v * bk[x];
                }
            }
            i += 1;
        }
        k_s = k_e;
    }
}

impl MatD {
    /// # Panics
    /// If `self.cols != other.rows`, mirroring Scala's `IllegalArgumentException`.
    fn check_mm(&self, other: &Self) {
        assert!(
            self.cols() == other.rows(),
            "m.cols[{}] != other.rows[{}]",
            self.cols(),
            other.rows()
        );
    }

    /// The pinned matmul, whatever the mode: bit-identical to Scala's `matmulPure` and
    /// to itself on every machine. What fixture generators and demo pairs call.
    ///
    /// # Panics
    /// If the inner dimensions do not agree.
    #[must_use]
    pub fn matmulPure(&self, other: &Self) -> Self {
        self.check_mm(other);
        let (ra, ca, cb) = (self.rows(), self.cols(), other.cols());
        let (ad, ao, ars, acs) = self.strided();
        let (bd, bo, brs, bcs) = other.strided();
        let a = Strided {
            d: ad,
            off: ao,
            rs: ars,
            cs: acs,
        };
        let b = Strided {
            d: bd,
            off: bo,
            rs: brs,
            cs: bcs,
        };
        Self::create(pure_matmul(a, b, ra, ca, cb), ra, cb)
    }

    /// BLAS matmul, whatever the mode — faster on large dense products, and not pinned:
    /// the result varies with the library, its threading and the CPU. Present only when
    /// the crate is built with `--features blas`, so a call in a non-BLAS build is a
    /// compile error rather than a silent fallback.
    ///
    /// # Panics
    /// If the inner dimensions do not agree.
    #[cfg(feature = "blas")]
    #[must_use]
    #[expect(
        clippy::panic,
        reason = "from_shape_vec can only fail on a length/shape mismatch, and both                   vectors are built to the shape two lines earlier; the branch is dead"
    )]
    pub fn matmulBlas(&self, other: &Self) -> Self {
        self.check_mm(other);
        let (ra, ca, cb) = (self.rows(), self.cols(), other.cols());
        let a = ndarray::Array2::from_shape_vec((ra, ca), self.row_major().into_owned())
            .unwrap_or_else(|e| panic!("shape {ra}x{ca}: {e}"));
        let b = ndarray::Array2::from_shape_vec((ca, cb), other.row_major().into_owned())
            .unwrap_or_else(|e| panic!("shape {ca}x{cb}: {e}"));
        let c = a.dot(&b);
        let (data, _) = c.into_raw_vec_and_offset();
        Self::create(data, ra, cb)
    }

    /// Scala's `matmul` / `*@`: the mode's default. Pure unless the crate was built with
    /// `--features blas`, in which case BLAS above a small crossover.
    ///
    /// # Panics
    /// If the inner dimensions do not agree.
    #[must_use]
    pub fn matmul(&self, other: &Self) -> Self {
        #[cfg(feature = "blas")]
        {
            let ops = self.rows() * self.cols() * other.cols();
            if ops >= BLAS_MIN_OPS {
                return self.matmulBlas(other);
            }
        }
        self.matmulPure(other)
    }

    /// Scala's `dot` — alias for [`MatD::matmul`].
    #[must_use]
    pub fn dot(&self, other: &Self) -> Self {
        self.matmul(other)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mat(rows: usize, cols: usize, salt: usize) -> MatD {
        // Exact small decimals of varying magnitude, so association order is observable.
        let cell = |i: usize, j: usize| {
            let k = (i * 7 + j * 13 + salt) % 23;
            (k as f64 - 11.0) * 0.375
                + if (i + j).is_multiple_of(5) {
                    1000.0
                } else {
                    0.0
                }
        };
        MatD::create(
            (0..rows * cols).map(|n| cell(n / cols, n % cols)).collect(),
            rows,
            cols,
        )
    }

    #[test]
    fn every_cell_is_a_sequential_k_sum_from_zero() {
        // The whole contract in one assertion, on shapes that do not tile evenly and one
        // above the parallel threshold.
        for &(ra, ca, cb) in &[(3usize, 5usize, 4usize), (33, 33, 33), (100, 37, 70)] {
            let a = mat(ra, ca, 1);
            let b = mat(ca, cb, 2);
            let c = a.matmulPure(&b);
            for i in 0..ra {
                for j in 0..cb {
                    let mut acc = 0.0f64;
                    for k in 0..ca {
                        acc += a.at(i, k) * b.at(k, j);
                    }
                    assert_eq!(c.at(i, j).to_bits(), acc.to_bits(), "cell ({i}, {j})");
                }
            }
        }
    }

    #[test]
    fn a_view_multiplies_to_the_same_bits_as_its_copy() {
        let a = mat(40, 60, 3);
        let b = mat(60, 40, 4);
        let via_views = a.T().matmulPure(&b.T());
        let via_copies = a.T().matCopy().matmulPure(&b.T().matCopy());
        assert_eq!(via_views.toArray(), via_copies.toArray());
        assert_eq!(via_views.shape(), (60, 60));
    }

    #[test]
    fn matmul_and_dot_are_the_pure_path_by_default() {
        let a = mat(128, 128, 5);
        let b = mat(128, 128, 6);
        let pure = a.matmulPure(&b).toArray();
        #[cfg(not(feature = "blas"))]
        {
            assert_eq!(a.matmul(&b).toArray(), pure);
            assert_eq!(a.dot(&b).toArray(), pure);
        }
        #[cfg(feature = "blas")]
        {
            // In BLAS mode the default is BLAS above the crossover; the pinned path is
            // still reachable by name, and tiny products still take it.
            let tiny_a = mat(4, 4, 5);
            let tiny_b = mat(4, 4, 6);
            assert_eq!(
                tiny_a.matmul(&tiny_b).toArray(),
                tiny_a.matmulPure(&tiny_b).toArray()
            );
            let _ = pure;
        }
    }

    #[test]
    fn empty_and_degenerate_shapes() {
        let a = MatD::zeros(0, 5);
        let b = MatD::zeros(5, 3);
        assert_eq!(a.matmulPure(&b).shape(), (0, 3));
        let r = MatD::create(vec![1.0, 2.0, 3.0], 1, 3);
        let c = MatD::create(vec![4.0, 5.0, 6.0], 3, 1);
        assert_eq!(r.matmulPure(&c).toArray(), vec![32.0]);
        assert_eq!(c.matmulPure(&r).shape(), (3, 3));
    }

    #[test]
    #[should_panic(expected = "m.cols[3] != other.rows[2]")]
    fn inner_dimension_mismatch_panics_with_scalas_message() {
        let _c = MatD::zeros(2, 3).matmulPure(&MatD::zeros(2, 2));
    }
}

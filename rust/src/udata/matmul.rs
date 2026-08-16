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
//! `TILE = 32`, parallel over row tiles above [`MATMUL_PAR_ROWS`] rows, then `tileK`,
//! `tileJ`, `i`, `k`, `j` — Scala's `multiplyDouble` exactly. Row-tile parallelism is
//! order-preserving because each tile owns disjoint output rows and the k-loop inside a
//! cell is untouched; the threshold is therefore tuning, not contract. Strided operands
//! are read in logical order via [`MatD::row_major`], which is what Scala's stride
//! accessors read, so a view multiplies to the same bits as its copy.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use rayon::prelude::*;

use crate::udata::mat::MatD;

/// Scala's `TILE`. Contract only insofar as it does not change the k-order — it does
/// not, in either language — so it is free to move for speed; it is kept equal for the
/// cache behaviour to match.
const TILE: usize = 32;

/// Below this many output rows the tiled loop runs on the calling thread: a 32-row
/// product is two tiles, not worth a fork. Tuning; order-preserving either way.
pub const MATMUL_PAR_ROWS: usize = 64;

/// In BLAS mode, products below this many multiply-adds still take the pure loop — the
/// call overhead exceeds the work. Scala's `blasThreshold` on this platform. Tuning.
#[cfg(feature = "blas")]
const BLAS_MIN_OPS: usize = 216;

/// The tiled loop over contiguous row-major slices — see the module note.
fn pure_tiled(a: &[f64], b: &[f64], rows_a: usize, cols_a: usize, cols_b: usize) -> Vec<f64> {
    let mut out = vec![0.0f64; rows_a * cols_b];
    if rows_a == 0 || cols_b == 0 {
        return out;
    }
    let tile = |tile_i: usize, out_rows: &mut [f64]| {
        let i_start = tile_i * TILE;
        let i_end = (i_start + TILE).min(rows_a);
        for tile_k in 0..cols_a.div_ceil(TILE) {
            let (k_start, k_end) = (tile_k * TILE, ((tile_k + 1) * TILE).min(cols_a));
            for tile_j in 0..cols_b.div_ceil(TILE) {
                let (j_start, j_end) = (tile_j * TILE, ((tile_j + 1) * TILE).min(cols_b));
                for i in i_start..i_end {
                    let orow = &mut out_rows[(i - i_start) * cols_b..(i - i_start + 1) * cols_b];
                    for k in k_start..k_end {
                        let a_val = a[i * cols_a + k];
                        let brow = &b[k * cols_b..(k + 1) * cols_b];
                        for j in j_start..j_end {
                            orow[j] += a_val * brow[j];
                        }
                    }
                }
            }
        }
    };
    if rows_a >= MATMUL_PAR_ROWS {
        out.par_chunks_mut(TILE * cols_b)
            .enumerate()
            .for_each(|(t, chunk)| tile(t, chunk));
    } else {
        for (t, chunk) in out.chunks_mut(TILE * cols_b).enumerate() {
            tile(t, chunk);
        }
    }
    out
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
        let a = self.row_major();
        let b = other.row_major();
        Self::create(pure_tiled(&a, &b, ra, ca, cb), ra, cb)
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

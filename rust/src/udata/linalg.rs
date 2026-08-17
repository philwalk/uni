//! Linear algebra on `MatD` — Scala's `Mat` decomposition family, the remainder of Tier 3
//! phase (e).
//!
//! # Two contracts, stated per method
//!
//! **Pinned** — a port of the Scala loop, association order and all, so the bits agree
//! with the JVM on every machine: `diagonal` `trace` `normOrd` `determinant`
//! `inverse` `solve` `qrDecomposition` `outer` `cross` `kron` `tril` `triu` `fillna`
//! `cov` `corrcoef`. The fixture pins these as raw bits.
//!
//! **To tolerance** — Scala routes `svd` `lstsq` `pinv` `matrixRank` `cholesky` through
//! LAPACK, whose blocked kernels reassociate; no pure loop reproduces their bits, and
//! LAPACK does not reproduce its own across builds (the bundled and the system OpenBLAS
//! differ in the last ulps). This crate implements them itself — a one-sided Jacobi SVD
//! (Hestenes) and a Cholesky–Banachiewicz factorisation — and the fixture pins them on a
//! quantised grid, the way the transcendental `MatMathOps` are pinned. Singular values
//! agree to ~1e-15 relative on well-conditioned input; `lstsq`/`pinv` inherit that
//! through the conditioning of the problem.
//!
//! `svd` is economy (U m×p, s p, Vt p×n, p = min(m, n)), like Scala's `dgesdd('S')`.
//! Singular values are descending; the signs of singular-vector pairs are whatever the
//! rotations produced (LAPACK's are whatever *its* algorithm produced — neither is a
//! contract, and `lstsq`/`pinv` are invariant to them). For rank-deficient input the U
//! columns of zero singular values are left zero rather than completed to an orthonormal
//! basis; nothing in `Mat` reads them.
//!
//! Failures that Scala reports with `ArithmeticException` — a singular matrix in
//! `determinant`/`inverse`/`solve`, a non-positive-definite one in `cholesky` — come back
//! as `Err(Error::SingularMatrix)`; shape violations that Scala `require`s are panics, as
//! everywhere else in `MatD`.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use crate::Error;
use crate::udata::mat::MatD;

/// Scala's `LeastSquaresResult` — what `MatD.leastSquares(A, b)` returns.
#[derive(Clone, Debug, PartialEq)]
pub struct LeastSquaresResult {
    /// The solution, cols×k.
    pub coefficients: MatD,
    /// `‖A·x − b‖²` per right-hand side, 1×k (zero unless rows > cols).
    pub residuals: MatD,
    /// Singular values above the rank threshold.
    pub rank: usize,
    /// The singular values of `A`, descending.
    pub singularValues: Vec<f64>,
}

/// The matrix norms `norm(ord)` offers — Scala takes the string `"fro" | "inf" | "1"`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NormOrd {
    /// Frobenius: `sqrt(Σ x²)`.
    Fro,
    /// Max absolute row sum.
    Inf,
    /// Max absolute column sum.
    One,
}

/// Row-major LU with partial pivoting, in place — Scala's `luDecomposeD`. Returns the
/// pivot permutation and the swap count.
fn lu_decompose(lu: &mut [f64], n: usize) -> Result<(Vec<usize>, usize), Error> {
    let mut pivots: Vec<usize> = (0..n).collect();
    let mut swaps = 0;
    for i in 0..n {
        let mut max_row = i;
        let mut max_abs = lu[i * n + i].abs();
        for k in (i + 1)..n {
            let v = lu[k * n + i].abs();
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
        if pivot == 0.0 {
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
    Ok((pivots, swaps))
}

/// Forward then backward substitution through a packed LU, one right-hand side, in place
/// — the loop `inverse` and `solve` share in Scala.
fn lu_substitute(lu: &[f64], n: usize, x: &mut [f64]) {
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

/// One Householder reflector of Scala's `qrDecomposition`: built from column `col` of `r`
/// below the diagonal, applied to `r` (rows/cols from `col`) and accumulated into `q`
/// (n_rows×n_rows) — the same three loops, in the same order.
fn householder_step(r: &mut [f64], q: &mut [f64], n_rows: usize, n_cols: usize, col: usize) {
    let len = n_rows - col;
    let mut v: Vec<f64> = (0..len).map(|i| r[(col + i) * n_cols + col]).collect();
    let mut norm_sq = 0.0;
    for x in &v {
        norm_sq += x * x;
    }
    let norm_v = norm_sq.sqrt();
    if norm_v == 0.0 {
        return;
    }
    let sign = if v[0] >= 0.0 { 1.0 } else { -1.0 };
    v[0] += sign * norm_v;
    let mut vtv = 0.0;
    for x in &v {
        vtv += x * x;
    }
    let tau = 2.0 / vtv;
    for j in col..n_cols {
        let mut w = 0.0;
        for i in 0..len {
            w += v[i] * r[(col + i) * n_cols + j];
        }
        for i in 0..len {
            r[(col + i) * n_cols + j] -= tau * (v[i] * w);
        }
    }
    for q_row in 0..n_rows {
        let mut w = 0.0;
        for i in 0..len {
            w += q[q_row * n_rows + col + i] * v[i];
        }
        for i in 0..len {
            q[q_row * n_rows + col + i] -= tau * (w * v[i]);
        }
    }
}

// ── The pinned family ───────────────────────────────────────────────────────────

impl MatD {
    /// Main diagonal, `min(rows, cols)` long — Scala's `diagonal`.
    #[must_use]
    pub fn diagonal(&self) -> Vec<f64> {
        let n = self.rows().min(self.cols());
        (0..n).map(|i| self.at(i, i)).collect()
    }

    /// `np.trace`: sum of the diagonal, sequential from 0.0.
    #[must_use]
    pub fn trace(&self) -> f64 {
        let n = self.rows().min(self.cols());
        let mut s = 0.0;
        for i in 0..n {
            s += self.at(i, i);
        }
        s
    }

    /// `np.linalg.norm(m, ord)` for `'fro'`, `'inf'`, `'1'` — Scala's `norm(ord: String)`.
    #[must_use]
    pub fn normOrd(&self, ord: NormOrd) -> f64 {
        match ord {
            NormOrd::Fro => {
                let mut sum_sq = 0.0;
                for i in 0..self.rows() {
                    for j in 0..self.cols() {
                        let x = self.at(i, j);
                        sum_sq += x * x;
                    }
                }
                sum_sq.sqrt()
            }
            NormOrd::Inf => {
                let mut max_row = 0.0;
                for i in 0..self.rows() {
                    let mut row_sum = 0.0;
                    for j in 0..self.cols() {
                        let x = self.at(i, j);
                        row_sum += if x < 0.0 { -x } else { x };
                    }
                    if row_sum > max_row {
                        max_row = row_sum;
                    }
                }
                max_row
            }
            NormOrd::One => {
                let mut max_col = 0.0;
                for j in 0..self.cols() {
                    let mut col_sum = 0.0;
                    for i in 0..self.rows() {
                        let x = self.at(i, j);
                        col_sum += if x < 0.0 { -x } else { x };
                    }
                    if col_sum > max_col {
                        max_col = col_sum;
                    }
                }
                max_col
            }
        }
    }

    /// Row-major copy of a square matrix through the stride equation, plus its LU.
    fn lu(&self, what: &str) -> Result<(Vec<f64>, Vec<usize>, usize, usize), Error> {
        assert!(
            self.rows() == self.cols(),
            "{what} requires square matrix, got {:?}",
            self.shape()
        );
        let n = self.rows();
        let mut lu = self.flatten();
        let (pivots, swaps) = lu_decompose(&mut lu, n)?;
        Ok((lu, pivots, swaps, n))
    }

    /// `np.linalg.det` via LU — Scala's `determinant`. `Err` on a singular matrix, as the
    /// Scala throws (NumPy would return 0).
    ///
    /// # Errors
    /// [`Error::SingularMatrix`] when a pivot is exactly zero.
    ///
    /// # Panics
    /// If the matrix is not square.
    pub fn determinant(&self) -> Result<f64, Error> {
        let (lu, _, swaps, n) = self.lu("determinant")?;
        let mut det = if swaps % 2 == 0 { 1.0 } else { -1.0 };
        for i in 0..n {
            det *= lu[i * n + i];
        }
        Ok(det)
    }

    /// `np.linalg.inv` via LU and per-column substitution — Scala's `inverse`.
    ///
    /// # Errors
    /// [`Error::SingularMatrix`] when a pivot is exactly zero.
    ///
    /// # Panics
    /// If the matrix is not square.
    pub fn inverse(&self) -> Result<Self, Error> {
        let (lu, pivots, _, n) = self.lu("inverse")?;
        let mut result = vec![0.0; n * n];
        for col in 0..n {
            let mut x: Vec<f64> = (0..n)
                .map(|i| if pivots[i] == col { 1.0 } else { 0.0 })
                .collect();
            lu_substitute(&lu, n, &mut x);
            for (row, v) in x.iter().enumerate() {
                result[row * n + col] = *v;
            }
        }
        Ok(Self::create(result, n, n))
    }

    /// `np.linalg.solve(A, b)` — Scala's `solve`. A 1×n `b` is taken as a column, as the
    /// Scala does; otherwise `b` is n×k and the result n×k.
    ///
    /// # Errors
    /// [`Error::SingularMatrix`] when a pivot is exactly zero.
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
        let (lu, pivots, _, n) = self.lu("solve")?;
        let n_rhs = b_col.cols();
        let mut result = vec![0.0; n * n_rhs];
        for col in 0..n_rhs {
            let mut x: Vec<f64> = (0..n).map(|i| b_col.at(pivots[i], col)).collect();
            lu_substitute(&lu, n, &mut x);
            for (row, v) in x.iter().enumerate() {
                result[row * n_rhs + col] = *v;
            }
        }
        Ok(Self::create(result, n, n_rhs))
    }

    /// Householder QR, economy: `(Q rows×p, R p×cols)` with `m = Q·R`, p = min(rows, cols)
    /// — Scala's `qrDecomposition`, reflector by reflector.
    #[must_use]
    pub fn qrDecomposition(&self) -> (Self, Self) {
        let n_rows = self.rows();
        let n_cols = self.cols();
        let p = n_rows.min(n_cols);
        let mut r = self.flatten();
        let mut q = vec![0.0; n_rows * n_rows];
        for i in 0..n_rows {
            q[i * n_rows + i] = 1.0;
        }
        for col in 0..p {
            householder_step(&mut r, &mut q, n_rows, n_cols, col);
        }
        for i in 1..n_rows {
            for j in 0..i.min(n_cols) {
                r[i * n_cols + j] = 0.0;
            }
        }
        let mut q_out = vec![0.0; n_rows * p];
        for i in 0..n_rows {
            for j in 0..p {
                q_out[i * p + j] = q[i * n_rows + j];
            }
        }
        let r_out = r[..p * n_cols].to_vec();
        (Self::create(q_out, n_rows, p), Self::create(r_out, p, n_cols))
    }

    /// `np.outer`: the |a|×|b| product of two vectors' flattened elements.
    ///
    /// # Panics
    /// If either operand is empty.
    #[must_use]
    pub fn outer(&self, other: &Self) -> Self {
        assert!(
            self.size() > 0 && other.size() > 0,
            "outer requires non-empty vectors"
        );
        let a = self.flatten();
        let b = other.flatten();
        let mut result = vec![0.0; a.len() * b.len()];
        for (i, ai) in a.iter().enumerate() {
            for (j, bj) in b.iter().enumerate() {
                result[i * b.len() + j] = ai * bj;
            }
        }
        Self::create(result, a.len(), b.len())
    }

    /// `np.cross` of two 3-vectors, as a 1×3 row.
    ///
    /// # Panics
    /// If either operand does not have exactly three elements.
    #[must_use]
    pub fn cross(&self, other: &Self) -> Self {
        let a = self.flatten();
        let b = other.flatten();
        assert!(
            a.len() == 3 && b.len() == 3,
            "cross product requires 3D vectors, got lengths {} and {}",
            a.len(),
            b.len()
        );
        Self::create(
            vec![
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0],
            ],
            1,
            3,
        )
    }

    /// `np.kron`: the Kronecker product.
    #[must_use]
    pub fn kron(&self, other: &Self) -> Self {
        let n_rows = self.rows() * other.rows();
        let n_cols = self.cols() * other.cols();
        let mut result = vec![0.0; n_rows * n_cols];
        for i in 0..self.rows() {
            for j in 0..self.cols() {
                for p in 0..other.rows() {
                    for q in 0..other.cols() {
                        let r = i * other.rows() + p;
                        let c = j * other.cols() + q;
                        result[r * n_cols + c] = self.at(i, j) * other.at(p, q);
                    }
                }
            }
        }
        Self::create(result, n_rows, n_cols)
    }

    /// `np.tril(m, k)`: elements with `j <= i + k` kept, the rest zero.
    #[must_use]
    pub fn tril(&self, k: i64) -> Self {
        self.triangle(|i, j| j <= i + k)
    }

    /// `np.triu(m, k)`: elements with `j >= i + k` kept, the rest zero.
    #[must_use]
    pub fn triu(&self, k: i64) -> Self {
        self.triangle(|i, j| j >= i + k)
    }

    fn triangle(&self, keep: impl Fn(i64, i64) -> bool) -> Self {
        let (rows, cols) = self.shape();
        let mut result = vec![0.0; rows * cols];
        for i in 0..rows {
            for j in 0..cols {
                let ii = i64::try_from(i).unwrap_or(i64::MAX);
                let jj = i64::try_from(j).unwrap_or(i64::MAX);
                result[i * cols + j] = if keep(ii, jj) { self.at(i, j) } else { 0.0 };
            }
        }
        Self::create(result, rows, cols)
    }

    /// pandas `fillna`: every NaN replaced by `value`.
    #[must_use]
    pub fn fillna(&self, value: f64) -> Self {
        self.map_elems(move |x| if x.is_nan() { value } else { x })
    }

    /// `np.cov(m)`: rows are variables, columns observations; the (p×p) sample covariance
    /// with `n − 1` in the denominator, means and cross-sums as sequential folds.
    ///
    /// # Panics
    /// With fewer than two columns.
    #[must_use]
    pub fn cov(&self) -> Self {
        let p = self.rows();
        let n = self.cols();
        assert!(n > 1, "cov requires at least 2 observations (cols)");
        // Scala centres a contiguous matrix with a sequential row mean and a view with
        // `mean(1)`; the two can differ in the last ulp, so both are reproduced.
        let fast = self.fast_d();
        let means = if fast { Self::zeros(0, 0) } else { self.meanAxis(1) };
        let a = self.flatten();
        let mut centered = vec![0.0; p * n];
        for i in 0..p {
            let mut s = 0.0;
            for j in 0..n {
                s += a[i * n + j];
            }
            let mu = if fast { s / n as f64 } else { means.at(i, 0) };
            for j in 0..n {
                centered[i * n + j] = a[i * n + j] - mu;
            }
        }
        let denom = (n - 1) as f64;
        let mut result = vec![0.0; p * p];
        for i in 0..p {
            for k in 0..p {
                let mut s = 0.0;
                for j in 0..n {
                    s += centered[i * n + j] * centered[k * n + j];
                }
                result[i * p + k] = s / denom;
            }
        }
        Self::create(result, p, p)
    }

    /// `np.corrcoef(m)`: `cov` scaled by the products of the standard deviations.
    #[must_use]
    pub fn corrcoef(&self) -> Self {
        let c = self.cov();
        let p = c.rows();
        let std: Vec<f64> = (0..p).map(|i| c.at(i, i).sqrt()).collect();
        let mut result = vec![0.0; p * p];
        for i in 0..p {
            for j in 0..p {
                result[i * p + j] = c.at(i, j) / (std[i] * std[j]);
            }
        }
        Self::create(result, p, p)
    }
}

// ── The decompositions Scala takes from LAPACK ──────────────────────────────────

/// One-sided Jacobi (Hestenes) SVD of a row-major m×n matrix with m ≥ n. Returns
/// `(u, s, v)`: `u` row-major m×n with orthonormal columns where `s > 0`, `s` descending,
/// `v` row-major n×n orthogonal, with `a = u · diag(s) · vᵀ`.
fn jacobi_svd_tall(a: &[f64], m: usize, n: usize) -> (Vec<f64>, Vec<f64>, Vec<f64>) {
    // Column-major working copies: rotations touch whole columns.
    let mut u = vec![0.0; m * n];
    for i in 0..m {
        for j in 0..n {
            u[j * m + i] = a[i * n + j];
        }
    }
    let mut v = vec![0.0; n * n];
    for j in 0..n {
        v[j * n + j] = 1.0;
    }
    for _sweep in 0..100 {
        let mut rotated = false;
        for p in 0..n {
            for q in (p + 1)..n {
                rotated |= jacobi_rotate(&mut u, &mut v, m, n, p, q);
            }
        }
        if !rotated {
            break;
        }
    }
    jacobi_finish(&u, &v, m, n)
}

/// One Hestenes rotation on columns `p`, `q` of the column-major `u` (m×n) and `v` (n×n).
/// Returns whether a rotation was applied (the pair was not yet orthogonal to working
/// precision).
fn jacobi_rotate(u: &mut [f64], v: &mut [f64], m: usize, n: usize, p: usize, q: usize) -> bool {
    let (mut alpha, mut beta, mut gamma) = (0.0, 0.0, 0.0);
    for i in 0..m {
        let up = u[p * m + i];
        let uq = u[q * m + i];
        alpha += up * up;
        beta += uq * uq;
        gamma += up * uq;
    }
    if gamma == 0.0 || gamma.abs() <= f64::EPSILON * (alpha * beta).sqrt() {
        return false;
    }
    let zeta = (beta - alpha) / (2.0 * gamma);
    let t = zeta.signum() / (zeta.abs() + (1.0 + zeta * zeta).sqrt());
    let c = 1.0 / (1.0 + t * t).sqrt();
    let s = c * t;
    for i in 0..m {
        let up = u[p * m + i];
        let uq = u[q * m + i];
        u[p * m + i] = c * up - s * uq;
        u[q * m + i] = s * up + c * uq;
    }
    for i in 0..n {
        let vp = v[p * n + i];
        let vq = v[q * n + i];
        v[p * n + i] = c * vp - s * vq;
        v[q * n + i] = s * vp + c * vq;
    }
    true
}

/// Singular values as the column norms of the converged `u`, columns normalised, then
/// everything sorted descending and returned row-major.
fn jacobi_finish(u: &[f64], v: &[f64], m: usize, n: usize) -> (Vec<f64>, Vec<f64>, Vec<f64>) {
    let s: Vec<f64> = (0..n)
        .map(|j| {
            let mut ss = 0.0;
            for i in 0..m {
                ss += u[j * m + i] * u[j * m + i];
            }
            ss.sqrt()
        })
        .collect();
    let mut order: Vec<usize> = (0..n).collect();
    order.sort_by(|&x, &y| s[y].total_cmp(&s[x]));
    let sorted_s: Vec<f64> = order.iter().map(|&j| s[j]).collect();
    let mut u_out = vec![0.0; m * n];
    let mut v_out = vec![0.0; n * n];
    for (new_j, &old_j) in order.iter().enumerate() {
        let sv = s[old_j];
        for i in 0..m {
            u_out[i * n + new_j] = if sv > 0.0 { u[old_j * m + i] / sv } else { 0.0 };
        }
        for i in 0..n {
            v_out[i * n + new_j] = v[old_j * n + i];
        }
    }
    (u_out, sorted_s, v_out)
}

/// Row-major transpose of an r×c buffer.
fn transpose_buf(a: &[f64], r: usize, c: usize) -> Vec<f64> {
    let mut out = vec![0.0; r * c];
    for i in 0..r {
        for j in 0..c {
            out[j * r + i] = a[i * c + j];
        }
    }
    out
}

impl MatD {
    /// Economy SVD `(U rows×p, s, Vt p×cols)`, p = min(rows, cols), singular values
    /// descending — Scala's `svd` (`dgesdd('S')`) to tolerance; see the module note.
    #[must_use]
    pub fn svd(&self) -> (Self, Vec<f64>, Self) {
        let (m, n) = self.shape();
        let a = self.flatten();
        if m >= n {
            let (u, s, v) = jacobi_svd_tall(&a, m, n);
            let vt = transpose_buf(&v, n, n);
            (Self::create(u, m, n), s, Self::create(vt, n, n))
        } else {
            // A = (Aᵀ)ᵀ: with Aᵀ = U'·S·V'ᵀ (n×m, tall), A = V'·S·U'ᵀ.
            let at = transpose_buf(&a, m, n);
            let (u2, s, v2) = jacobi_svd_tall(&at, n, m);
            let vt = transpose_buf(&u2, n, m); // U'ᵀ is m×n
            (Self::create(v2, m, m), s, Self::create(vt, m, n))
        }
    }

    /// `np.linalg.lstsq(A, b)`: `(x cols×k, residuals 1×k, rank, singular values)` via the
    /// SVD, exactly the three steps Scala takes (`Uᵀb`, `S⁺`, `V·`); rank counts singular
    /// values above `1e-10 · s₀`; residuals are `‖A·x − b‖²` per column when
    /// `rows > cols`, else zero.
    ///
    /// # Panics
    /// If `b` does not have `rows` rows.
    #[must_use]
    pub fn lstsq(&self, b: &Self) -> (Self, Self, usize, Vec<f64>) {
        let (n_rows, n_cols) = self.shape();
        assert!(
            b.rows() == n_rows,
            "lstsq: b has {} rows, expected {n_rows}",
            b.rows()
        );
        let n_rhs = b.cols();
        let p = n_rows.min(n_cols);
        let (u_mat, s, vt_mat) = self.svd();
        let u = u_mat.flatten();
        let vt = vt_mat.flatten();
        let threshold = 1e-10 * s.first().copied().unwrap_or(0.0);
        let rank = s.iter().filter(|&&x| x > threshold).count();
        let mut result = vec![0.0; n_cols * n_rhs];
        for col in 0..n_rhs {
            let mut tmp = vec![0.0; p];
            for i in 0..p {
                for k in 0..n_rows {
                    tmp[i] += u[k * p + i] * b.at(k, col);
                }
            }
            for i in 0..p {
                if i < rank {
                    tmp[i] /= s[i];
                } else {
                    tmp[i] = 0.0;
                }
            }
            for i in 0..n_cols {
                for k in 0..p {
                    result[i * n_rhs + col] += vt[k * n_cols + i] * tmp[k];
                }
            }
        }
        let x = Self::create(result, n_cols, n_rhs);
        let mut residuals = vec![0.0; n_rhs];
        if n_rows > n_cols {
            let diff = &self.matmulPure(&x) - b;
            for (c2, r) in residuals.iter_mut().enumerate() {
                for i in 0..n_rows {
                    let v = diff.at(i, c2);
                    *r += v * v;
                }
            }
        }
        (x, Self::create(residuals, 1, n_rhs), rank, s)
    }

    /// Breeze-style `leastSquares(A, b)`: [`MatD::lstsq`] with named fields — Scala's
    /// `MatD.leastSquares`.
    ///
    /// # Panics
    /// If `b` does not have `A.rows` rows.
    #[must_use]
    pub fn leastSquares(a: &Self, b: &Self) -> LeastSquaresResult {
        let (coefficients, residuals, rank, singularValues) = a.lstsq(b);
        LeastSquaresResult {
            coefficients,
            residuals,
            rank,
            singularValues,
        }
    }

    /// `np.linalg.matrix_rank`: singular values above `tol`, default `1e-10 · s₀`.
    #[must_use]
    pub fn matrixRank(&self, tol: Option<f64>) -> usize {
        let (_, s, _) = self.svd();
        let threshold = tol.unwrap_or_else(|| 1e-10 * s.first().copied().unwrap_or(0.0));
        s.iter().filter(|&&x| x > threshold).count()
    }

    /// `np.linalg.pinv`: `V · S⁺ · Uᵀ`, singular values at or below `tol` (default
    /// `1e-10 · max(rows, cols) · s₀`) treated as zero.
    #[must_use]
    pub fn pinv(&self, tol: Option<f64>) -> Self {
        let (n_rows, n_cols) = self.shape();
        let p = n_rows.min(n_cols);
        let (u_mat, s, vt_mat) = self.svd();
        let u = u_mat.flatten();
        let vt = vt_mat.flatten();
        let threshold = tol
            .unwrap_or_else(|| 1e-10 * n_rows.max(n_cols) as f64 * s.first().copied().unwrap_or(0.0));
        let s_inv: Vec<f64> = s
            .iter()
            .map(|&sv| if sv > threshold { 1.0 / sv } else { 0.0 })
            .collect();
        let mut result = vec![0.0; n_cols * n_rows];
        for i in 0..n_cols {
            for j in 0..n_rows {
                let mut sum = 0.0;
                for k in 0..p {
                    sum += vt[k * n_cols + i] * s_inv[k] * u[j * p + k];
                }
                result[i * n_rows + j] = sum;
            }
        }
        Self::create(result, n_cols, n_rows)
    }

    /// `np.linalg.cholesky`: lower-triangular L with `m = L·Lᵀ` (Cholesky–Banachiewicz,
    /// row by row; the strict upper triangle is zero).
    ///
    /// # Errors
    /// [`Error::SingularMatrix`] when the matrix is not positive definite.
    ///
    /// # Panics
    /// If the matrix is not square.
    pub fn cholesky(&self) -> Result<Self, Error> {
        assert!(
            self.rows() == self.cols(),
            "cholesky requires square matrix, got {:?}",
            self.shape()
        );
        let n = self.rows();
        let a = self.flatten();
        let mut l = vec![0.0; n * n];
        for i in 0..n {
            for j in 0..=i {
                let mut sum = a[i * n + j];
                for k in 0..j {
                    sum -= l[i * n + k] * l[j * n + k];
                }
                if i == j {
                    if sum <= 0.0 {
                        return Err(Error::SingularMatrix(format!(
                            "Matrix is not positive definite (info={})",
                            i + 1
                        )));
                    }
                    l[i * n + i] = sum.sqrt();
                } else {
                    l[i * n + j] = sum / l[j * n + j];
                }
            }
        }
        Ok(Self::create(l, n, n))
    }
}

#[cfg(test)]
mod tests {
    use super::NormOrd;
    use crate::NumPyRng;
    use crate::udata::mat::MatD;

    fn randn(rng: &mut NumPyRng, r: usize, c: usize) -> MatD {
        MatD::create((0..r * c).map(|_| rng.randn()).collect(), r, c)
    }

    fn max_abs_diff(a: &MatD, b: &MatD) -> f64 {
        a.flatten()
            .iter()
            .zip(b.flatten())
            .map(|(x, y)| (x - y).abs())
            .fold(0.0, f64::max)
    }

    fn eye(n: usize) -> MatD {
        let mut d = vec![0.0; n * n];
        for i in 0..n {
            d[i * n + i] = 1.0;
        }
        MatD::create(d, n, n)
    }

    #[test]
    fn inverse_times_matrix_is_identity_and_solve_agrees() {
        let mut rng = NumPyRng::new(3);
        let a = &randn(&mut rng, 6, 6) + &(&eye(6) * 6.0);
        let inv = a.inverse().unwrap();
        assert!(max_abs_diff(&a.matmulPure(&inv), &eye(6)) < 1e-12);
        let b = randn(&mut rng, 6, 2);
        let x = a.solve(&b).unwrap();
        assert!(max_abs_diff(&a.matmulPure(&x), &b) < 1e-12);
        let det = a.determinant().unwrap();
        let det_inv = inv.determinant().unwrap();
        assert!((det * det_inv - 1.0).abs() < 1e-9, "{det} {det_inv}");
    }

    #[test]
    fn a_singular_matrix_is_an_error_not_a_panic() {
        let s = MatD::create(vec![1.0, 2.0, 2.0, 4.0], 2, 2);
        assert!(s.inverse().is_err());
        assert!(s.determinant().is_err());
    }

    #[test]
    fn qr_reconstructs_and_q_is_orthonormal() {
        let mut rng = NumPyRng::new(5);
        for (r, c) in [(7, 4), (4, 7), (5, 5)] {
            let a = randn(&mut rng, r, c);
            let (q, rr) = a.qrDecomposition();
            assert!(max_abs_diff(&q.matmulPure(&rr), &a) < 1e-12, "{r}x{c}");
            let p = r.min(c);
            assert!(max_abs_diff(&q.T().matmulPure(&q), &eye(p)) < 1e-12, "{r}x{c} Q");
        }
    }

    #[test]
    fn svd_reconstructs_tall_wide_and_square() {
        let mut rng = NumPyRng::new(11);
        for (r, c) in [(7, 4), (4, 7), (5, 5), (1, 3), (3, 1)] {
            let a = randn(&mut rng, r, c);
            let (u, s, vt) = a.svd();
            let p = r.min(c);
            assert_eq!(u.shape(), (r, p));
            assert_eq!(vt.shape(), (p, c));
            assert!(s.windows(2).all(|w| w[0] >= w[1]), "descending {s:?}");
            let mut sd = vec![0.0; p * p];
            for i in 0..p {
                sd[i * p + i] = s[i];
            }
            let rec = u.matmulPure(&MatD::create(sd, p, p)).matmulPure(&vt);
            assert!(max_abs_diff(&rec, &a) < 1e-12, "{r}x{c} reconstruction");
            assert!(max_abs_diff(&u.T().matmulPure(&u), &eye(p)) < 1e-12, "{r}x{c} U");
            assert!(max_abs_diff(&vt.matmulPure(&vt.T()), &eye(p)) < 1e-12, "{r}x{c} Vt");
        }
    }

    #[test]
    fn lstsq_solves_the_normal_equations_and_reports_rank() {
        let mut rng = NumPyRng::new(2);
        let a = randn(&mut rng, 10, 3);
        let b = randn(&mut rng, 10, 2);
        let (x, res, rank, s) = a.lstsq(&b);
        assert_eq!(rank, 3);
        assert_eq!(s.len(), 3);
        // Aᵀ(Ax − b) = 0
        let g = a.T().matmulPure(&(&a.matmulPure(&x) - &b));
        assert!(g.flatten().iter().all(|v| v.abs() < 1e-11), "{g:?}");
        assert_eq!(res.shape(), (1, 2));
        assert!(res.flatten().iter().all(|&v| v > 0.0));
        assert_eq!(a.matrixRank(None), 3);
        // rank-deficient: third column = first + second
        let mut d = a.flatten();
        for i in 0..10 {
            d[i * 3 + 2] = d[i * 3] + d[i * 3 + 1];
        }
        let ad = MatD::create(d, 10, 3);
        assert_eq!(ad.matrixRank(None), 2);
        assert_eq!(ad.lstsq(&b).2, 2);
    }

    #[test]
    fn pinv_is_a_left_inverse_for_full_column_rank() {
        let mut rng = NumPyRng::new(9);
        let a = randn(&mut rng, 8, 3);
        let pi = a.pinv(None);
        assert_eq!(pi.shape(), (3, 8));
        assert!(max_abs_diff(&pi.matmulPure(&a), &eye(3)) < 1e-11);
    }

    #[test]
    fn cholesky_reconstructs_and_rejects_indefinite() {
        let mut rng = NumPyRng::new(4);
        let r = randn(&mut rng, 5, 5);
        let a = &r.matmulPure(&r.T()) + &(&eye(5) * 5.0);
        let l = a.cholesky().unwrap();
        assert!(max_abs_diff(&l.matmulPure(&l.T()), &a) < 1e-12);
        assert_eq!(l.triu(1).flatten().iter().filter(|&&v| v != 0.0).count(), 0);
        let bad = MatD::create(vec![1.0, 2.0, 2.0, 1.0], 2, 2);
        assert!(bad.cholesky().is_err());
    }

    #[test]
    fn small_exact_ones() {
        let m = MatD::create(vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0], 2, 3);
        assert_eq!(m.diagonal(), vec![1.0, 5.0]);
        assert_eq!(m.trace(), 6.0);
        assert_eq!(m.normOrd(NormOrd::Inf), 15.0);
        assert_eq!(m.normOrd(NormOrd::One), 9.0);
        assert_eq!(m.normOrd(NormOrd::Fro), 91f64.sqrt());
        assert_eq!(m.tril(0).flatten(), vec![1.0, 0.0, 0.0, 4.0, 5.0, 0.0]);
        assert_eq!(m.triu(1).flatten(), vec![0.0, 2.0, 3.0, 0.0, 0.0, 6.0]);
        let a = MatD::create(vec![1.0, 0.0, 0.0], 1, 3);
        let b = MatD::create(vec![0.0, 1.0, 0.0], 1, 3);
        assert_eq!(a.cross(&b).flatten(), vec![0.0, 0.0, 1.0]);
        let k = MatD::create(vec![1.0, 2.0], 1, 2).kron(&MatD::create(vec![1.0, 10.0], 2, 1));
        assert_eq!(k.shape(), (2, 2));
        assert_eq!(k.flatten(), vec![1.0, 2.0, 10.0, 20.0]);
        let o = MatD::create(vec![1.0, 2.0], 2, 1).outer(&MatD::create(vec![3.0, 4.0, 5.0], 1, 3));
        assert_eq!(o.flatten(), vec![3.0, 4.0, 5.0, 6.0, 8.0, 10.0]);
        let f = MatD::create(vec![1.0, f64::NAN], 1, 2).fillna(-1.0);
        assert_eq!(f.flatten(), vec![1.0, -1.0]);
        let c = MatD::create(vec![1.0, 2.0, 3.0, 2.0, 4.0, 6.0], 2, 3);
        assert_eq!(c.cov().flatten(), vec![1.0, 2.0, 2.0, 4.0]);
        assert!(max_abs_diff(&c.corrcoef(), &MatD::create(vec![1.0, 1.0, 1.0, 1.0], 2, 2)) < 1e-15);
    }
}

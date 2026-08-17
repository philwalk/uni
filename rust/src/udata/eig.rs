//! Real eigen-decomposition of a general (non-symmetric) square `MatD` — Scala's `eig`
//! and `eigenvalues`, which route through LAPACK `dgeev`.
//!
//! No pure loop reproduces `dgeev`'s bits (blocked Hessenberg reduction, multishift QR),
//! so this is the classic EISPACK route as JAMA spells it: orthogonal Hessenberg
//! reduction (`orthes`) then the shifted QR iteration with back-substitution (`hqr2`).
//! Eigenvalues agree with LAPACK to the conditioning of the problem; the fixture pins
//! them sorted and on the 2^-20 grid. Eigenvectors are a basis, not a canonical one:
//! LAPACK normalises each to unit Euclidean norm with its largest component real, and
//! this port does the same for real eigenvectors (sign left as the iteration produced
//! it) and for complex pairs, so a caller sees the same shape of answer — but only
//! `A·v = λ·v` is a contract, never the vector's bits.
//!
//! Complex pairs are packed as LAPACK packs them: for eigenvalues `wr[j] ± i·wi[j]`
//! (`wi[j] > 0`), columns `j` and `j+1` of the vector matrix hold the real and imaginary
//! parts of the eigenvector of `wr[j] + i·wi[j]`; its conjugate is the other member.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use crate::udata::mat::MatD;

/// The working state of the decomposition: `h` is the Hessenberg (then Schur) form and
/// `v` the accumulated transformations, both n×n row-major.
struct EigWork {
    n: usize,
    h: Vec<f64>,
    v: Vec<f64>,
    d: Vec<f64>,
    e: Vec<f64>,
}

impl EigWork {
    fn at(&self, i: usize, j: usize) -> f64 {
        self.h[i * self.n + j]
    }
    fn set(&mut self, i: usize, j: usize, x: f64) {
        self.h[i * self.n + j] = x;
    }
    fn vat(&self, i: usize, j: usize) -> f64 {
        self.v[i * self.n + j]
    }
    fn vset(&mut self, i: usize, j: usize, x: f64) {
        self.v[i * self.n + j] = x;
    }

    /// Reduce to Hessenberg form by orthogonal similarity (JAMA `orthes`).
    #[expect(clippy::cognitive_complexity, reason = "the EISPACK reduction is one algorithm")]
    #[expect(clippy::needless_range_loop, reason = "index arithmetic mirrors the reference")]
    fn orthes(&mut self) {
        let n = self.n;
        if n == 0 {
            return;
        }
        let low = 0usize;
        let high = n - 1;
        let mut ort = vec![0.0; n];
        for m in (low + 1)..high {
            let mut scale = 0.0;
            for i in m..=high {
                scale += self.at(i, m - 1).abs();
            }
            if scale != 0.0 {
                let mut h = 0.0;
                for i in (m..=high).rev() {
                    ort[i] = self.at(i, m - 1) / scale;
                    h += ort[i] * ort[i];
                }
                let mut g = h.sqrt();
                if ort[m] > 0.0 {
                    g = -g;
                }
                h -= ort[m] * g;
                ort[m] -= g;
                for j in m..n {
                    let mut f = 0.0;
                    for i in (m..=high).rev() {
                        f += ort[i] * self.at(i, j);
                    }
                    f /= h;
                    for i in m..=high {
                        let x = self.at(i, j) - f * ort[i];
                        self.set(i, j, x);
                    }
                }
                for i in 0..=high {
                    let mut f = 0.0;
                    for j in (m..=high).rev() {
                        f += ort[j] * self.at(i, j);
                    }
                    f /= h;
                    for j in m..=high {
                        let x = self.at(i, j) - f * ort[j];
                        self.set(i, j, x);
                    }
                }
                ort[m] *= scale;
                self.set(m, m - 1, scale * g);
            }
        }
        for i in 0..n {
            for j in 0..n {
                self.vset(i, j, if i == j { 1.0 } else { 0.0 });
            }
        }
        for m in ((low + 1)..high).rev() {
            if self.at(m, m - 1) != 0.0 {
                for i in (m + 1)..=high {
                    ort[i] = self.at(i, m - 1);
                }
                for j in m..=high {
                    let mut g = 0.0;
                    for i in m..=high {
                        g += ort[i] * self.vat(i, j);
                    }
                    // Double division avoids possible underflow.
                    g = (g / ort[m]) / self.at(m, m - 1);
                    for i in m..=high {
                        let x = self.vat(i, j) + g * ort[i];
                        self.vset(i, j, x);
                    }
                }
            }
        }
    }
}

/// Complex scalar division `(xr + i·xi) / (yr + i·yi)`.
fn cdiv(xr: f64, xi: f64, yr: f64, yi: f64) -> (f64, f64) {
    if yr.abs() > yi.abs() {
        let r = yi / yr;
        let d = yr + r * yi;
        ((xr + r * xi) / d, (xi - r * xr) / d)
    } else {
        let r = yr / yi;
        let d = yi + r * yr;
        ((r * xr + xi) / d, (r * xi - xr) / d)
    }
}

impl EigWork {
    /// Hessenberg to real Schur form by shifted QR, eigenvectors by back-substitution
    /// (JAMA `hqr2`). Split into the iteration ([`Self::hqr2_iterate`]) and the
    /// back-substitution ([`Self::hqr2_backsubstitute`]).
    fn hqr2(&mut self) {
        let norm = self.hqr2_iterate();
        self.hqr2_backsubstitute(norm);
    }

    /// The QR iteration: deflates one or two eigenvalues at a time from the bottom of the
    /// active window until it is empty. Returns the matrix norm used as the zero test.
    #[expect(clippy::too_many_lines, reason = "the EISPACK iteration is one algorithm")]
    #[expect(clippy::cognitive_complexity, reason = "the EISPACK iteration is one algorithm")]
    fn hqr2_iterate(&mut self) -> f64 {
        let nn = self.n;
        if nn == 0 {
            return 0.0;
        }
        let low: i64 = 0;
        let high: i64 = nn as i64 - 1;
        let eps = 2f64.powi(-52);
        let mut exshift = 0.0;
        let (mut p, mut q, mut r) = (0.0, 0.0, 0.0);
        let (mut s, mut z, mut w, mut x, mut y);

        // Store roots isolated by balanc and compute matrix norm.
        let mut norm = 0.0;
        for i in 0..nn {
            for j in i.saturating_sub(1)..nn {
                norm += self.at(i, j).abs();
            }
        }

        // Outer loop over eigenvalue index.
        let mut n: i64 = high;
        let mut iter = 0;
        while n >= low {
            // Look for single small sub-diagonal element.
            let mut l = n;
            while l > low {
                let lu = l as usize;
                s = self.at(lu - 1, lu - 1).abs() + self.at(lu, lu).abs();
                if s == 0.0 {
                    s = norm;
                }
                if self.at(lu, lu - 1).abs() < eps * s {
                    break;
                }
                l -= 1;
            }
            let nu = n as usize;
            // Check for convergence.
            if l == n {
                // One root found.
                self.set(nu, nu, self.at(nu, nu) + exshift);
                self.d[nu] = self.at(nu, nu);
                self.e[nu] = 0.0;
                n -= 1;
                iter = 0;
            } else if l == n - 1 {
                // Two roots found.
                w = self.at(nu, nu - 1) * self.at(nu - 1, nu);
                p = (self.at(nu - 1, nu - 1) - self.at(nu, nu)) / 2.0;
                q = p * p + w;
                z = q.abs().sqrt();
                self.set(nu, nu, self.at(nu, nu) + exshift);
                self.set(nu - 1, nu - 1, self.at(nu - 1, nu - 1) + exshift);
                x = self.at(nu, nu);
                if q >= 0.0 {
                    // Real pair.
                    z = if p >= 0.0 { p + z } else { p - z };
                    self.d[nu - 1] = x + z;
                    self.d[nu] = self.d[nu - 1];
                    if z != 0.0 {
                        self.d[nu] = x - w / z;
                    }
                    self.e[nu - 1] = 0.0;
                    self.e[nu] = 0.0;
                    x = self.at(nu, nu - 1);
                    s = x.abs() + z.abs();
                    p = x / s;
                    q = z / s;
                    r = (p * p + q * q).sqrt();
                    p /= r;
                    q /= r;
                    // Row modification.
                    for j in (nu - 1)..nn {
                        z = self.at(nu - 1, j);
                        self.set(nu - 1, j, q * z + p * self.at(nu, j));
                        self.set(nu, j, q * self.at(nu, j) - p * z);
                    }
                    // Column modification.
                    for i in 0..=nu {
                        z = self.at(i, nu - 1);
                        self.set(i, nu - 1, q * z + p * self.at(i, nu));
                        self.set(i, nu, q * self.at(i, nu) - p * z);
                    }
                    // Accumulate transformations.
                    for i in (low as usize)..=(high as usize) {
                        z = self.vat(i, nu - 1);
                        self.vset(i, nu - 1, q * z + p * self.vat(i, nu));
                        self.vset(i, nu, q * self.vat(i, nu) - p * z);
                    }
                } else {
                    // Complex pair.
                    self.d[nu - 1] = x + p;
                    self.d[nu] = x + p;
                    self.e[nu - 1] = z;
                    self.e[nu] = -z;
                }
                n -= 2;
                iter = 0;
            } else {
                // No convergence yet. Form shift.
                x = self.at(nu, nu);
                y = 0.0;
                w = 0.0;
                if l < n {
                    y = self.at(nu - 1, nu - 1);
                    w = self.at(nu, nu - 1) * self.at(nu - 1, nu);
                }
                // Wilkinson's original ad hoc shift.
                if iter == 10 {
                    exshift += x;
                    for i in (low as usize)..=nu {
                        self.set(i, i, self.at(i, i) - x);
                    }
                    s = self.at(nu, nu - 1).abs() + self.at(nu - 1, nu - 2).abs();
                    x = 0.75 * s;
                    y = x;
                    w = -0.4375 * s * s;
                }
                // MATLAB's new ad hoc shift.
                if iter == 30 {
                    s = (y - x) / 2.0;
                    s = s * s + w;
                    if s > 0.0 {
                        s = s.sqrt();
                        if y < x {
                            s = -s;
                        }
                        s = x - w / ((y - x) / 2.0 + s);
                        for i in (low as usize)..=nu {
                            self.set(i, i, self.at(i, i) - s);
                        }
                        exshift += s;
                        x = 0.964;
                        y = x;
                        w = x;
                    }
                }
                iter += 1;
                // Look for two consecutive small sub-diagonal elements.
                let mut m = n - 2;
                while m >= l {
                    let mu = m as usize;
                    z = self.at(mu, mu);
                    r = x - z;
                    s = y - z;
                    p = (r * s - w) / self.at(mu + 1, mu) + self.at(mu, mu + 1);
                    q = self.at(mu + 1, mu + 1) - z - r - s;
                    r = self.at(mu + 2, mu + 1);
                    s = p.abs() + q.abs() + r.abs();
                    p /= s;
                    q /= s;
                    r /= s;
                    if m == l {
                        break;
                    }
                    if self.at(mu, mu - 1).abs() * (q.abs() + r.abs())
                        < eps
                            * (p.abs()
                                * (self.at(mu - 1, mu - 1).abs()
                                    + z.abs()
                                    + self.at(mu + 1, mu + 1).abs()))
                    {
                        break;
                    }
                    m -= 1;
                }
                let mu = m as usize;
                for i in (mu + 2)..=nu {
                    self.set(i, i - 2, 0.0);
                    if i > mu + 2 {
                        self.set(i, i - 3, 0.0);
                    }
                }
                // Double QR step involving rows l:n and columns m:n.
                let mut k = mu;
                while k < nu {
                    let notlast = k != nu - 1;
                    if k != mu {
                        p = self.at(k, k - 1);
                        q = self.at(k + 1, k - 1);
                        r = if notlast { self.at(k + 2, k - 1) } else { 0.0 };
                        x = p.abs() + q.abs() + r.abs();
                        if x == 0.0 {
                            k += 1;
                            continue;
                        }
                        p /= x;
                        q /= x;
                        r /= x;
                    }
                    s = (p * p + q * q + r * r).sqrt();
                    if p < 0.0 {
                        s = -s;
                    }
                    if s != 0.0 {
                        if k != mu {
                            self.set(k, k - 1, -s * x);
                        } else if l != m {
                            self.set(k, k - 1, -self.at(k, k - 1));
                        }
                        p += s;
                        x = p / s;
                        y = q / s;
                        z = r / s;
                        q /= p;
                        r /= p;
                        // Row modification.
                        for j in k..nn {
                            p = self.at(k, j) + q * self.at(k + 1, j);
                            if notlast {
                                p += r * self.at(k + 2, j);
                                self.set(k + 2, j, self.at(k + 2, j) - p * z);
                            }
                            self.set(k, j, self.at(k, j) - p * x);
                            self.set(k + 1, j, self.at(k + 1, j) - p * y);
                        }
                        // Column modification.
                        let imax = if nu < k + 3 { nu } else { k + 3 };
                        for i in 0..=imax {
                            p = x * self.at(i, k) + y * self.at(i, k + 1);
                            if notlast {
                                p += z * self.at(i, k + 2);
                                self.set(i, k + 2, self.at(i, k + 2) - p * r);
                            }
                            self.set(i, k, self.at(i, k) - p);
                            self.set(i, k + 1, self.at(i, k + 1) - p * q);
                        }
                        // Accumulate transformations.
                        for i in (low as usize)..=(high as usize) {
                            p = x * self.vat(i, k) + y * self.vat(i, k + 1);
                            if notlast {
                                p += z * self.vat(i, k + 2);
                                self.vset(i, k + 2, self.vat(i, k + 2) - p * r);
                            }
                            self.vset(i, k, self.vat(i, k) - p);
                            self.vset(i, k + 1, self.vat(i, k + 1) - p * q);
                        }
                    }
                    k += 1;
                }
            }
        }
        norm
    }

    /// Back-substitute the eigenvectors of the quasi-triangular Schur form and
    /// transform them back (the second half of JAMA `hqr2`).
    #[expect(clippy::too_many_lines, reason = "the EISPACK back-substitution is one algorithm")]
    #[expect(clippy::cognitive_complexity, reason = "the EISPACK back-substitution is one algorithm")]
    fn hqr2_backsubstitute(&mut self, norm: f64) {
        let nn = self.n;
        if nn == 0 || norm == 0.0 {
            return;
        }
        let eps = 2f64.powi(-52);
        let (mut p, mut q, mut r, mut s, mut z, mut t, mut w, mut x, mut y);
        r = 0.0;
        s = 0.0;
        z = 0.0;
        for n in (0..nn).rev() {
            p = self.d[n];
            q = self.e[n];
            if q == 0.0 {
                // Real vector.
                let mut l = n;
                self.set(n, n, 1.0);
                for i in (0..n).rev() {
                    w = self.at(i, i) - p;
                    r = 0.0;
                    for j in l..=n {
                        r += self.at(i, j) * self.at(j, n);
                    }
                    if self.e[i] < 0.0 {
                        z = w;
                        s = r;
                    } else {
                        l = i;
                        if self.e[i] == 0.0 {
                            let v = if w == 0.0 { -r / (eps * norm) } else { -r / w };
                            self.set(i, n, v);
                        } else {
                            // Solve real equations.
                            x = self.at(i, i + 1);
                            y = self.at(i + 1, i);
                            q = (self.d[i] - p) * (self.d[i] - p) + self.e[i] * self.e[i];
                            t = (x * s - z * r) / q;
                            self.set(i, n, t);
                            let v = if x.abs() > z.abs() {
                                (-r - w * t) / x
                            } else {
                                (-s - y * t) / z
                            };
                            self.set(i + 1, n, v);
                        }
                        // Overflow control.
                        t = self.at(i, n).abs();
                        if (eps * t) * t > 1.0 {
                            for j in i..=n {
                                self.set(j, n, self.at(j, n) / t);
                            }
                        }
                    }
                }
            } else if q < 0.0 {
                // Complex vector.
                let mut l = n - 1;
                // Last vector component imaginary so matrix is triangular.
                if self.at(n, n - 1).abs() > self.at(n - 1, n).abs() {
                    self.set(n - 1, n - 1, q / self.at(n, n - 1));
                    self.set(n - 1, n, -(self.at(n, n) - p) / self.at(n, n - 1));
                } else {
                    let (cr, ci) = cdiv(0.0, -self.at(n - 1, n), self.at(n - 1, n - 1) - p, q);
                    self.set(n - 1, n - 1, cr);
                    self.set(n - 1, n, ci);
                }
                self.set(n, n - 1, 0.0);
                self.set(n, n, 1.0);
                for i in (0..n.saturating_sub(1)).rev() {
                    let (mut ra, mut sa) = (0.0, 0.0);
                    for j in l..=n {
                        ra += self.at(i, j) * self.at(j, n - 1);
                        sa += self.at(i, j) * self.at(j, n);
                    }
                    w = self.at(i, i) - p;
                    if self.e[i] < 0.0 {
                        z = w;
                        r = ra;
                        s = sa;
                    } else {
                        l = i;
                        if self.e[i] == 0.0 {
                            let (cr, ci) = cdiv(-ra, -sa, w, q);
                            self.set(i, n - 1, cr);
                            self.set(i, n, ci);
                        } else {
                            // Solve complex equations.
                            x = self.at(i, i + 1);
                            y = self.at(i + 1, i);
                            let mut vr = (self.d[i] - p) * (self.d[i] - p) + self.e[i] * self.e[i] - q * q;
                            let vi = (self.d[i] - p) * 2.0 * q;
                            if vr == 0.0 && vi == 0.0 {
                                vr = eps * norm * (w.abs() + q.abs() + x.abs() + y.abs() + z.abs());
                            }
                            let (cr, ci) = cdiv(x * r - z * ra + q * sa, x * s - z * sa - q * ra, vr, vi);
                            self.set(i, n - 1, cr);
                            self.set(i, n, ci);
                            if x.abs() > (z.abs() + q.abs()) {
                                self.set(i + 1, n - 1, (-ra - w * self.at(i, n - 1) + q * self.at(i, n)) / x);
                                self.set(i + 1, n, (-sa - w * self.at(i, n) - q * self.at(i, n - 1)) / x);
                            } else {
                                let (cr, ci) = cdiv(-r - y * self.at(i, n - 1), -s - y * self.at(i, n), z, q);
                                self.set(i + 1, n - 1, cr);
                                self.set(i + 1, n, ci);
                            }
                        }
                        // Overflow control.
                        t = self.at(i, n - 1).abs().max(self.at(i, n).abs());
                        if (eps * t) * t > 1.0 {
                            for j in i..=n {
                                self.set(j, n - 1, self.at(j, n - 1) / t);
                                self.set(j, n, self.at(j, n) / t);
                            }
                        }
                    }
                }
            }
        }
        // Back transformation to get eigenvectors of original matrix.
        for j in (0..nn).rev() {
            for i in 0..nn {
                z = 0.0;
                for k in 0..=j {
                    z += self.vat(i, k) * self.at(k, j);
                }
                self.vset(i, j, z);
            }
        }
    }

    /// LAPACK's normalisation: each real eigenvector to unit Euclidean norm; each
    /// complex pair to unit norm with its largest-magnitude component made real.
    fn normalise(&mut self) {
        let n = self.n;
        let mut j = 0;
        while j < n {
            if self.e[j] == 0.0 {
                let mut ss = 0.0;
                for i in 0..n {
                    ss += self.vat(i, j) * self.vat(i, j);
                }
                let nrm = ss.sqrt();
                if nrm > 0.0 {
                    for i in 0..n {
                        let x = self.vat(i, j) / nrm;
                        self.vset(i, j, x);
                    }
                }
                j += 1;
            } else {
                let mut ss = 0.0;
                let (mut k, mut best) = (0usize, -1.0);
                for i in 0..n {
                    let (re, im) = (self.vat(i, j), self.vat(i, j + 1));
                    let mag = re * re + im * im;
                    ss += mag;
                    if mag > best {
                        best = mag;
                        k = i;
                    }
                }
                let nrm = ss.sqrt();
                if nrm > 0.0 {
                    // Rotate so component k is real and positive, then scale.
                    let (re, im) = (self.vat(k, j), self.vat(k, j + 1));
                    let mag = (re * re + im * im).sqrt();
                    let (cr, ci) = if mag > 0.0 { (re / mag, -im / mag) } else { (1.0, 0.0) };
                    for i in 0..n {
                        let (a, b) = (self.vat(i, j), self.vat(i, j + 1));
                        self.vset(i, j, (a * cr - b * ci) / nrm);
                        self.vset(i, j + 1, (a * ci + b * cr) / nrm);
                    }
                    self.vset(k, j + 1, 0.0);
                }
                j += 2;
            }
        }
    }
}

impl MatD {
    fn eig_work(&self, what: &str) -> EigWork {
        assert!(
            self.rows() == self.cols(),
            "{what} requires square matrix, got {:?}",
            self.shape()
        );
        let n = self.rows();
        let mut w = EigWork {
            n,
            h: self.flatten(),
            v: vec![0.0; n * n],
            d: vec![0.0; n],
            e: vec![0.0; n],
        };
        w.orthes();
        w.hqr2();
        w
    }

    /// `np.linalg.eigvals`, real parts only — Scala's `eigenvalues` (its `iterations`
    /// parameter applies only to the non-Double fallback there). Order is the order the
    /// iteration deflated them in, as `dgeev`'s is its own; sort before comparing.
    ///
    /// # Panics
    /// If the matrix is not square.
    #[must_use]
    pub fn eigenvalues(&self) -> Vec<f64> {
        self.eig_work("eigenvalues").d
    }

    /// `np.linalg.eig` as Scala returns it: `(real parts, imaginary parts, eigenvectors)`,
    /// eigenvectors as the columns of an n×n real matrix with complex pairs packed as
    /// LAPACK packs them (see the module note).
    ///
    /// # Panics
    /// If the matrix is not square.
    #[must_use]
    pub fn eig(&self) -> (Vec<f64>, Vec<f64>, Self) {
        let mut w = self.eig_work("eig");
        w.normalise();
        let n = w.n;
        (w.d, w.e, Self::create(w.v, n, n))
    }
}

#[cfg(test)]
mod tests {
    use crate::NumPyRng;
    use crate::udata::mat::MatD;

    fn randn(rng: &mut NumPyRng, r: usize, c: usize) -> MatD {
        MatD::create((0..r * c).map(|_| rng.randn()).collect(), r, c)
    }

    /// `A·v = λ·v` for every eigenpair, real or complex, to 1e-9.
    fn check_eigenpairs(a: &MatD) {
        let n = a.rows();
        let (wr, wi, v) = a.eig();
        let av = a.matmulPure(&v);
        let mut j = 0;
        while j < n {
            if wi[j] == 0.0 {
                for i in 0..n {
                    let lhs = av.at(i, j);
                    let rhs = wr[j] * v.at(i, j);
                    assert!((lhs - rhs).abs() < 1e-9, "real pair {j}: {lhs} vs {rhs}");
                }
                j += 1;
            } else {
                // (A)(vr + i vi) = (wr + i wi)(vr + i vi)
                for i in 0..n {
                    let re = wr[j] * v.at(i, j) - wi[j] * v.at(i, j + 1);
                    let im = wr[j] * v.at(i, j + 1) + wi[j] * v.at(i, j);
                    assert!((av.at(i, j) - re).abs() < 1e-9, "complex pair {j} re");
                    assert!((av.at(i, j + 1) - im).abs() < 1e-9, "complex pair {j} im");
                }
                assert!(wi[j] > 0.0 && wi[j + 1] == -wi[j], "conjugate order at {j}");
                j += 2;
            }
        }
    }

    #[test]
    fn symmetric_matrix_has_real_spectrum_and_orthonormal_vectors() {
        let mut rng = NumPyRng::new(21);
        let r = randn(&mut rng, 6, 6);
        let a = &r + &r.T();
        let (wr, wi, v) = a.eig();
        assert!(wi.iter().all(|&x| x == 0.0));
        assert_eq!(wr.len(), 6);
        check_eigenpairs(&a);
        // trace = sum of eigenvalues
        let tr: f64 = wr.iter().sum();
        assert!((tr - a.trace()).abs() < 1e-9);
        // unit columns
        for j in 0..6 {
            let nrm: f64 = (0..6).map(|i| v.at(i, j) * v.at(i, j)).sum::<f64>().sqrt();
            assert!((nrm - 1.0).abs() < 1e-12);
        }
    }

    #[test]
    fn a_rotation_has_a_complex_pair_and_a_general_matrix_satisfies_av_eq_lv() {
        let rot = MatD::create(vec![0.0, -1.0, 1.0, 0.0], 2, 2);
        let (wr, wi, _) = rot.eig();
        assert!(wr.iter().all(|&x| x.abs() < 1e-15));
        assert!((wi[0] - 1.0).abs() < 1e-15 && (wi[1] + 1.0).abs() < 1e-15);
        check_eigenpairs(&rot);
        let mut rng = NumPyRng::new(8);
        for n in [3, 5, 8, 12] {
            let a = randn(&mut rng, n, n);
            check_eigenpairs(&a);
            let ev = a.eigenvalues();
            let tr: f64 = ev.iter().sum();
            assert!((tr - a.trace()).abs() < 1e-8, "n={n} trace {tr} vs {}", a.trace());
        }
    }

    #[test]
    fn known_spectrum() {
        let a = MatD::create(vec![2.0, 1.0, 1.0, 2.0], 2, 2);
        let mut ev = a.eigenvalues();
        ev.sort_by(f64::total_cmp);
        assert!((ev[0] - 1.0).abs() < 1e-12 && (ev[1] - 3.0).abs() < 1e-12);
        let tri = MatD::create(vec![1.0, 2.0, 3.0, 0.0, 4.0, 5.0, 0.0, 0.0, 6.0], 3, 3);
        let mut ev = tri.eigenvalues();
        ev.sort_by(f64::total_cmp);
        assert_eq!(ev, vec![1.0, 4.0, 6.0]);
    }
}

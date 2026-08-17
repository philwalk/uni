//! Signal-processing companions — Scala's `MatSignalOps`: `polyfit`, `polyval`,
//! `convolve`, `correlate`, as free functions on `MatD` vectors (any shape; the elements
//! are taken flattened, and the results are 1×n rows, as the Scala's are).
//!
//! `polyval`, `convolve` and `correlate` are plain loops in a fixed order and agree with
//! the JVM bit for bit. `polyfit` builds the Vandermonde matrix with `pow` and solves it
//! through [`MatD::lstsq`], so it inherits both the libm and the SVD situations: pinned to
//! tolerance, on the 2^-20 grid.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use crate::udata::mat::MatD;

/// How `convolve`/`correlate` trim the full-length result — NumPy's `mode` strings.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ConvMode {
    /// Every overlap: `na + nb − 1` values.
    Full,
    /// Centred, `na` values.
    Same,
    /// Complete overlaps only: `max(na, nb) − min(na, nb) + 1` values.
    Valid,
}

/// `np.polyfit(x, y, deg)`: least-squares polynomial coefficients, highest degree first,
/// as a 1×(deg+1) row.
///
/// # Panics
/// If `deg < 1`, the lengths differ, or there are not more points than `deg`.
#[must_use]
pub fn polyfit(x: &MatD, y: &MatD, deg: usize) -> MatD {
    assert!(deg >= 1, "degree must be >= 1, got {deg}");
    let xs = x.flatten();
    let ys = y.flatten();
    assert!(xs.len() == ys.len(), "x and y must have same length");
    assert!(xs.len() > deg, "need more points than degree");
    let n = xs.len();
    let mut vand = vec![0.0; n * (deg + 1)];
    for (i, &xi) in xs.iter().enumerate() {
        for j in 0..=deg {
            #[expect(clippy::cast_precision_loss, reason = "a small exponent")]
            let e = (deg - j) as f64;
            vand[i * (deg + 1) + j] = xi.powf(e);
        }
    }
    let a = MatD::create(vand, n, deg + 1);
    let b = MatD::create(ys, n, 1);
    let (coeffs, _, _, _) = a.lstsq(&b);
    MatD::create(coeffs.flatten(), 1, deg + 1)
}

/// `np.polyval(coeffs, x)`: Horner's rule, coefficients highest degree first; a 1×n row.
#[must_use]
pub fn polyval(coeffs: &MatD, x: &MatD) -> MatD {
    let cs = coeffs.flatten();
    let xs = x.flatten();
    let out: Vec<f64> = xs
        .iter()
        .map(|&xi| cs.iter().fold(0.0, |acc, &c| acc * xi + c))
        .collect();
    let n = out.len();
    MatD::create(out, 1, n)
}

fn trim(full: Vec<f64>, na: usize, nb: usize, mode: ConvMode, valid_start: usize) -> MatD {
    match mode {
        ConvMode::Full => {
            let n = full.len();
            MatD::create(full, 1, n)
        }
        ConvMode::Same => {
            let start = (nb - 1) / 2;
            MatD::create(full[start..start + na].to_vec(), 1, na)
        }
        ConvMode::Valid => {
            let n_valid = na.max(nb) - na.min(nb) + 1;
            MatD::create(
                full[valid_start..valid_start + n_valid].to_vec(),
                1,
                n_valid,
            )
        }
    }
}

/// `np.convolve(a, b, mode)`: discrete linear convolution.
///
/// # Panics
/// If either input is empty.
#[must_use]
pub fn convolve(a: &MatD, b: &MatD, mode: ConvMode) -> MatD {
    let av = a.flatten();
    let bv = b.flatten();
    let (na, nb) = (av.len(), bv.len());
    assert!(na > 0 && nb > 0, "convolve of an empty vector");
    let mut full = vec![0.0; na + nb - 1];
    for (i, &ai) in av.iter().enumerate() {
        for (j, &bj) in bv.iter().enumerate() {
            full[i + j] += ai * bj;
        }
    }
    trim(full, na, nb, mode, na.min(nb) - 1)
}

/// `np.correlate(a, b, mode)`: cross-correlation (`b` slid across `a`, not reversed).
///
/// # Panics
/// If either input is empty.
#[must_use]
pub fn correlate(a: &MatD, b: &MatD, mode: ConvMode) -> MatD {
    let av = a.flatten();
    let bv = b.flatten();
    let (na, nb) = (av.len(), bv.len());
    assert!(na > 0 && nb > 0, "correlate of an empty vector");
    let n_full = na + nb - 1;
    let mut full = vec![0.0; n_full];
    for (k, cell) in full.iter_mut().enumerate() {
        for (j, &bj) in bv.iter().enumerate() {
            // ai = k − (nb − 1) + j, kept only inside [0, na)
            if let Some(ai) = (k + j).checked_sub(nb - 1) {
                if ai < na {
                    *cell += av[ai] * bj;
                }
            }
        }
    }
    trim(full, na, nb, mode, nb - 1)
}

#[cfg(test)]
mod tests {
    use super::ConvMode;
    use super::convolve;
    use super::correlate;
    use super::polyfit;
    use super::polyval;
    use crate::udata::mat::MatD;

    fn row(v: &[f64]) -> MatD {
        MatD::create(v.to_vec(), 1, v.len())
    }

    #[test]
    fn convolve_and_correlate_match_numpy() {
        let a = row(&[1.0, 2.0, 3.0]);
        let b = row(&[0.0, 1.0, 0.5]);
        assert_eq!(
            convolve(&a, &b, ConvMode::Full).flatten(),
            vec![0.0, 1.0, 2.5, 4.0, 1.5]
        );
        assert_eq!(
            convolve(&a, &b, ConvMode::Same).flatten(),
            vec![1.0, 2.5, 4.0]
        );
        assert_eq!(convolve(&a, &b, ConvMode::Valid).flatten(), vec![2.5]);
        assert_eq!(correlate(&a, &b, ConvMode::Valid).flatten(), vec![3.5]);
        assert_eq!(
            correlate(&a, &b, ConvMode::Full).flatten(),
            vec![0.5, 2.0, 3.5, 3.0, 0.0]
        );
        assert_eq!(
            correlate(&a, &b, ConvMode::Same).flatten(),
            vec![2.0, 3.5, 3.0]
        );
    }

    #[test]
    fn polyfit_recovers_a_quadratic_and_polyval_evaluates_it() {
        let x = row(&[0.0, 1.0, 2.0, 3.0, 4.0]);
        let y = row(&[1.0, 3.0, 9.0, 19.0, 33.0]); // 2x² + 1
        let c = polyfit(&x, &y, 2).flatten();
        assert!(
            (c[0] - 2.0).abs() < 1e-9 && c[1].abs() < 1e-9 && (c[2] - 1.0).abs() < 1e-9,
            "{c:?}"
        );
        let v = polyval(&row(&[2.0, 0.0, 1.0]), &row(&[3.0, -1.0])).flatten();
        assert_eq!(v, vec![19.0, 3.0]);
    }
}

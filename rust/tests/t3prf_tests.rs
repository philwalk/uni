use ndarray::Array2;
use uni::estimate_3prf_is_full;
use uni::estimate_3prf_oos_cv;
use uni::estimate_3prf_oos_rec;

fn create_synthetic_data(t: usize, n: usize, l: usize) -> (Array2<f64>, Array2<f64>, Array2<f64>) {
    let mut x = Array2::<f64>::zeros((t, n));
    let mut y = Array2::<f64>::zeros((t, 1));
    let mut z = Array2::<f64>::zeros((t, l));

    for r in 0..t {
        let tr = r as f64;
        y[[r, 0]] = (tr * 0.05).sin() + 0.1 * (tr * 0.1).cos();
        for c in 0..n {
            let tc = c as f64;
            x[[r, c]] = (tr * 0.02 + tc).cos() + 0.5 * (tr * 0.01 * (tc + 1.0)).sin();
        }
        for c in 0..l {
            let tc = c as f64;
            z[[r, c]] = y[[r, 0]] + 0.05 * (tr * 0.03 + tc).sin();
        }
    }
    (y, x, z)
}

#[test]
fn test_is_full_synthetic() {
    let (y, x, z) = create_synthetic_data(100, 10, 2);
    let res = estimate_3prf_is_full(&y, &x, &z).expect("IS Full solve succeeds");
    assert_eq!(res.forecasts.nrows(), 100);
    assert_eq!(res.forecasts.ncols(), 1);
    assert!(res.r_squared > 0.0, "IS Full R² should be positive");
}

#[test]
fn test_oos_rec_synthetic() {
    let (y, x, z) = create_synthetic_data(100, 10, 2);
    let res = estimate_3prf_oos_rec(&y, &x, &z, 50).expect("OOS Rec solve succeeds");
    assert_eq!(res.forecasts.nrows(), 100);
    assert!(res.forecasts[[0, 0]].is_nan());
    assert!(!res.forecasts[[55, 0]].is_nan());
}

#[test]
fn test_oos_cv_synthetic() {
    let (y, x, z) = create_synthetic_data(100, 10, 2);
    let res = estimate_3prf_oos_cv(&y, &x, &z, 0, 1).expect("OOS CV solve succeeds");
    assert_eq!(res.forecasts.nrows(), 100);
    assert!(!res.forecasts[[0, 0]].is_nan());
    assert!(!res.forecasts[[50, 0]].is_nan());
}

// Linked for its side effect only: it provides the cblas symbols that
// ndarray's `blas` feature declares but does not supply.
#[cfg(feature = "blas")]
extern crate blas_src;

pub mod cli;
pub mod error;
pub mod numpy_rng;
pub mod t3prf;
pub mod udata;
pub mod upath;
pub mod uplot;
pub mod utime;

pub use error::Error;
pub use numpy_rng::NumPyRng;
pub use t3prf::Pls3prfModel;
pub use t3prf::Tprf3Result;
pub use t3prf::estimate_3prf_is_full;
pub use t3prf::estimate_3prf_oos_cv;
pub use t3prf::estimate_3prf_oos_rec;
pub use t3prf::forecast3prf;
pub use t3prf::ols_solve;
pub use t3prf::pls1Fit;
pub use t3prf::plsClosedForm;
pub use t3prf::standardize_columns;
pub use t3prf::std_cols;
pub use t3prf::t3prf_core;
pub use t3prf::tprfClosedForm;

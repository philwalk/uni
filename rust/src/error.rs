use thiserror::Error;

#[derive(Debug, Error)]
pub enum Error {
    #[error("Dimension mismatch: expected {expected}, got {actual}")]
    DimensionMismatch { expected: String, actual: String },

    #[error("Singular matrix encountered during inversion: {0}")]
    SingularMatrix(String),

    #[error("Invalid input: {0}")]
    InvalidInput(String),
}

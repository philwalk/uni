//! Numeric data types — a port of Scala's `uni.data`: the `Big` decimal and the
//! `Mat[Double]` matrix stack.
//!
//! # No dependency, deliberately
//!
//! `Big` is `scala.math.BigDecimal` under `MathContext.DECIMAL128` on the Scala side. `std`
//! has no decimal type and no big integer, so [`big`] carries its own arbitrary-precision
//! decimal — base-10⁹ limbs, exact `+`/`-`/`*`, DECIMAL128-rounded `/` and `sqrt` — rather
//! than taking the port's first dependency. `test-data/big-parity/` holds
//! `java.math.BigDecimal`'s own answers, which makes the JDK the transitive oracle for
//! every rounding boundary, preferred-scale rule and `toString` threshold.
//!
//! # The matrix stack
//!
//! [`mat`] holds `MatD`, [`mataxis`] its axis reductions, and [`vecexts`] the
//! orientation-typed `CVecD`/`RVecD` wrappers. Unlike `Big`, this half *does* lean on
//! dependencies the crate already carries (`rayon` for the chunked sum, `ndarray`
//! available for a future matmul) — the "no dependencies" story is specifically no JNI,
//! no date crate, no decimal crate and no regex engine.
//!
//! `MatD` carries Scala's own `(transposed, offset, rs, cs)` stride descriptor rather
//! than materialising views, because on the Scala side the layout is what selects the
//! summation algorithm. See the module note in [`mat`]; it is the single least obvious
//! thing about this port.

pub mod big;
pub mod bigutils;
pub mod mat;
pub mod mataxis;
pub mod matb;
pub mod matmut;
pub mod vecexts;

pub use big::Big;
pub use bigutils::NumFormat;
pub use bigutils::big2double;
pub use bigutils::isBad;
pub use bigutils::isNumeric;
pub use bigutils::java_format_f;
pub use bigutils::num2string;
pub use bigutils::numStr;
pub use bigutils::numStrPct;
pub use bigutils::orBad;
pub use bigutils::str2num;
pub use mat::MatD;
pub use matb::MatB;
pub use matmut::MatMut;
pub use vecexts::CVecD;
pub use vecexts::RVecD;

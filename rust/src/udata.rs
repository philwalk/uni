//! Numeric data types — a port of Scala's `uni.data`, so far the `Big` decimal.
//!
//! # No dependency, deliberately
//!
//! `Big` is `scala.math.BigDecimal` under `MathContext.DECIMAL128` on the Scala side. `std`
//! has no decimal type and no big integer, so [`big`] carries its own arbitrary-precision
//! decimal — base-10⁹ limbs, exact `+`/`-`/`*`, DECIMAL128-rounded `/` and `sqrt` — rather
//! than taking the port's first dependency. `test-data/big-parity/` holds
//! `java.math.BigDecimal`'s own answers, which makes the JDK the transitive oracle for
//! every rounding boundary, preferred-scale rule and `toString` threshold.

pub mod big;
pub mod bigutils;

pub use big::Big;
pub use bigutils::big2double;
pub use bigutils::isBad;
pub use bigutils::isNumeric;
pub use bigutils::num2string;
pub use bigutils::numStr;
pub use bigutils::numStrPct;
pub use bigutils::orBad;
pub use bigutils::str2num;
pub use bigutils::NumFormat;

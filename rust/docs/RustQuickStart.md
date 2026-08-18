# uni for Rust — Quick Start Guide

The Rust port of `uni`'s NumPy-compatible matrix library: the same view model, the same
reductions, the same results as the JVM — bit for bit where the fixture pins them. This
guide parallels [`docs/QuickStartGuide.md`](../../docs/QuickStartGuide.md) section for
section, so a reader who knows one can read the other; every block is a complete program
compiled by `checkRustDocs.sh` in CI and before every release.

## Installation
```toml
[dependencies]
uni = { package = "vastblue-uni", version = "0.17" }
```
The crate is `vastblue-uni` on crates.io; its library name is `uni`, so the paths below read
`use uni::…`.

## Basic Usage

### Creating Matrices
```rust
use uni::NumPyRng;
use uni::udata::MatD;

fn main() {
    // From literal values (row-major, takes ownership of the Vec)
    let m = MatD::create(vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0], 2, 3); // 2x3

    // Column vector from a slice — MatD::apply(&[...]) is Scala's MatD(1.0, 2.0, 3.0)
    let v = MatD::apply(&[1.0, 2.0, 3.0]);              // 3x1 column vector
    let r = MatD::create(vec![1.0, 2.0, 3.0], 1, 3);    // 1x3 row vector

    // Common constructors
    let zeros = MatD::zeros(3, 4);                     // All zeros
    let ones = MatD::ones(2, 5);                       // All ones
    let identity = MatD::eye(3);                       // Identity matrix
    let filled = MatD::full(3, 3, 7.0);                // All 7.0
    let range = MatD::arange(0.0, 10.0, 1.0);          // [0..9] as a column vector
    let spaced = MatD::linspace(0.0, 1.0, 50);         // 50 points from 0 to 1
    let table = MatD::tabulate(2, 3, |i, j| (i * 3 + j) as f64);

    // Random matrices (NumPy-compatible; the generator is explicit — no global RNG)
    let mut rng = NumPyRng::new(42);                   // reproducible, same draws as Scala/NumPy
    let uniform = MatD::rand(&mut rng, 10, 10);        // Uniform [0, 1)
    let normal = MatD::randn(&mut rng, 10, 10);        // Standard normal N(0,1)

    println!("{:?} {:?} {:?}", m.shape(), v.shape(), r.shape());
    println!("{:?} {:?} {:?} {:?}", zeros.shape(), ones.shape(), identity.shape(), filled.at(0, 0));
    println!("{:?} {:?} {:?}", range.shape(), spaced.shape(), table.at(1, 2));
    println!("{:?} {:?}", uniform.shape(), normal.shape());
}
```

### Indexing & Slicing
```rust
use uni::udata::MatD;

fn main() {
    let m = MatD::create(vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0], 2, 3);

    // Basic indexing (zero-based)
    let value = m.at(0, 1);               // Element at row 0, col 1
    let same = m[(0, 1)];                 // Index sugar for the same read
    let last = m.at(m.rows() - 1, m.cols() - 1);

    // Copies: the apply* family is Scala's m(rows, cols)
    let row = m.applyRowAll(0);           // First row
    let col = m.applyAllCol(2);           // Third column
    let sub = m.applyRowsCols(0..2, 1..3); // Submatrix [rows 0-1, cols 1-2]

    // Zero-copy view of the same cells (Scala's m.slice); i64 ranges, a negative start
    // counts from the end
    let view = m.slice(0..2, 0..3);
    let last_two_cols = m.slice(0..2, -2..0);

    // Fancy indexing with index lists
    let reordered = m.applyRowsIdx(&[1, 0]); // Select and reorder rows

    // Boolean masking (IEEE comparisons, like NumPy: NaN never compares true)
    let mask = m.gt(4.0);
    let filtered = m.applyMask(&mask);    // Elements > 4.0, as a 1×k row
    println!("{value} {same} {last} {:?} {:?} {:?}", row.shape(), col.shape(), sub.shape());
    println!("{:?} {:?} {:?} {:?}", view.shape(), last_two_cols.shape(), reordered.at(0, 0), filtered.shape());
}
```

### Writing to a Matrix
`MatD` is immutable and freely shared by views. Writing is an explicit phase: `intoMut`
gives a `MatMut` (it panics if the buffer is shared — `matCopy()` first when in doubt),
`freeze` gives the `MatD` back. This is what Scala's `m(mask) = 0.0` becomes.
```rust
use uni::udata::MatD;

fn main() {
    let m = MatD::create(vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0], 2, 3);
    let mask = m.gt(4.0);

    let mut w = m.intoMut();          // m is consumed; nothing else holds the buffer
    w.updateAt(0, 0, 10.0);           // one cell
    w.updateRowAll(1, 0.0);           // a whole row
    w.updateMask(&mask, 0.0);         // set matching elements to 0
    let m = w.freeze();

    println!("{:?}", m.toArray());    // [10.0, 2.0, 3.0, 0.0, 0.0, 0.0]
}
```

### Arithmetic Operations
```rust
use uni::udata::MatD;

fn main() {
    let a = MatD::create(vec![1.0, 2.0, 3.0, 4.0], 2, 2);
    let b = MatD::create(vec![5.0, 6.0, 7.0, 8.0], 2, 2);

    // Element-wise operations, on references (with broadcasting)
    let sum = &a + &b;
    let diff = &a - &b;
    let prod = &a * &b;                   // element-wise (Hadamard)
    let quot = &a / &b;

    // Scalar operations
    let scaled = &a * 2.0;
    let shifted = &a + 10.0;
    let flipped = 1.0 - &a;               // scalar on the left works too

    // Matrix multiplication
    let matmul = a.matmul(&b);            // or a.dot(&b); a.matmulPure(&b) is the pinned loop

    // Element-wise power (NumPy **)
    let squared = a.power(2);             // integer exponent, repeated multiply
    let roots = a.powerF(0.5);            // real exponent, through pow

    // Broadcasting with vectors
    let row_vec = MatD::create(vec![1.0, 2.0], 1, 2);
    let result = &a + &row_vec;           // adds row_vec to each row

    println!("{:?} {:?} {:?} {:?}", sum.toArray(), diff.toArray(), prod.toArray(), quot.at(1, 1));
    println!("{:?} {:?} {:?} {:?}", scaled.at(0, 0), shifted.at(0, 0), flipped.at(0, 0), matmul.toArray());
    println!("{:?} {:?} {:?}", squared.toArray(), roots.at(1, 1), result.toArray());
}
```

### Linear Algebra
```rust
use uni::udata::MatD;
use uni::udata::linalg::NormOrd;

fn main() {
    let a = MatD::create(vec![1.0, 2.0, 3.0, 4.0], 2, 2);
    let v = MatD::apply(&[1.0, 2.0, 3.0]);

    // Basic operations
    let at = a.T();                                    // Transpose (a zero-copy view)
    let inv = a.inverse().expect("a is invertible");   // Result: Err on a singular matrix
    let det = a.determinant().expect("square");
    let tr = a.trace();

    // Solve Ax = b
    let b = MatD::apply(&[1.0, 2.0]);                  // 2x1 column vector
    let x = a.solve(&b).expect("a is invertible");

    // Decompositions
    let (q, r) = a.qrDecomposition();                  // QR
    let (u, s, vt) = a.svd();                          // economy SVD; s descending
    let (vals, vals_imag, vecs) = a.eig();             // eigenvalues (re, im) and vectors

    // Norms
    let norm = v.norm();                               // L2 norm of a vector
    let frob = a.normOrd(NormOrd::Fro);                // Frobenius norm

    println!("{:?} {:?} {det} {tr} {:?}", at.shape(), inv.toArray(), x.toArray());
    println!("{:?} {:?} {:?} {:?} {:?}", q.shape(), r.shape(), u.shape(), s, vt.shape());
    println!("{:?} {:?} {:?} {norm} {frob}", vals, vals_imag, vecs.shape());
}
```

### Statistics
```rust
use uni::NumPyRng;
use uni::udata::MatD;

fn main() {
    let mut rng = NumPyRng::new(1);
    let m = MatD::randn(&mut rng, 5, 4);

    // Reductions
    let min = m.min();
    let max = m.max();
    let total = m.sum();
    let avg = m.mean();
    let std_dev = m.std();                // population
    let med = m.median();

    // Axis-wise operations
    let col_sums = m.sumAxis(0);          // Sum each column (1×cols)
    let row_means = m.meanAxis(1);        // Mean of each row (rows×1)
    let col_max = m.maxAxis(0);

    // Index of extremes
    let (min_row, min_col) = m.argmin();
    let (max_row, max_col) = m.argmax();

    // Distribution functions
    let p50 = m.percentile(50.0);
    let p90 = m.percentile(90.0);

    println!("{min} {max} {total} {avg} {std_dev} {med}");
    println!("{:?} {:?} {:?}", col_sums.shape(), row_means.shape(), col_max.shape());
    println!("({min_row},{min_col}) ({max_row},{max_col}) {p50} {p90}");
}
```

### Element-wise Math Functions
```rust
use uni::NumPyRng;
use uni::udata::MatD;

fn main() {
    let mut rng = NumPyRng::new(2);
    let m = MatD::randn(&mut rng, 5, 4);
    let m1 = MatD::randn(&mut rng, 5, 4);
    let m2 = MatD::randn(&mut rng, 5, 4);

    // Basic math
    let abs_m = m.abs();
    let sqrt_m = m.abs().sqrt();
    let exp_m = m.exp();
    let log_m = m.abs().log();
    let log10_m = m.abs().log10();

    // Trigonometric
    let sin_m = m.sin();
    let cos_m = m.cos();
    let tanh_m = m.tanh();

    // Rounding
    let rounded = m.round(2);
    let floored = m.floor();
    let ceiled = m.ceil();
    let truncated = m.trunc();

    // Clipping and bounds
    let clipped = m.clip(0.0, 1.0);
    let max_elems = m1.maximum(&m2);
    let min_elems = m1.minimum(&m2);

    println!("{:?} {:?} {:?} {:?} {:?}", abs_m.at(0, 0), sqrt_m.at(0, 0), exp_m.at(0, 0), log_m.at(0, 0), log10_m.at(0, 0));
    println!("{:?} {:?} {:?}", sin_m.at(0, 0), cos_m.at(0, 0), tanh_m.at(0, 0));
    println!("{:?} {:?} {:?} {:?}", rounded.at(0, 0), floored.at(0, 0), ceiled.at(0, 0), truncated.at(0, 0));
    println!("{:?} {:?} {:?}", clipped.at(0, 0), max_elems.at(0, 0), min_elems.at(0, 0));
}
```

### Machine Learning Functions
```rust
use uni::NumPyRng;
use uni::udata::MatD;

fn main() {
    let mut rng = NumPyRng::new(3);
    let m = MatD::randn(&mut rng, 5, 4);

    // Activation functions
    let sigmoid = m.sigmoid();
    let relu = m.relu();
    let leaky = m.leakyRelu(0.01);
    let softmax = m.softmax(1);           // per row
    let gelu = m.gelu();

    // Training utilities (the RNG is explicit; draw order matches Scala's)
    let dropped = m.dropout(&mut rng, 0.5);

    // Custom distributions
    let custom = MatD::normal(&mut rng, 5.0, 2.0, 100, 10);

    println!("{:?} {:?} {:?} {:?} {:?}", sigmoid.at(0, 0), relu.at(0, 0), leaky.at(0, 0), softmax.sumAxis(1).at(0, 0), gelu.at(0, 0));
    println!("{:?} {:?}", dropped.shape(), custom.mean().round());
}
```

### Data Manipulation
```rust
use uni::NumPyRng;
use uni::udata::MatD;

fn main() {
    let mut rng = NumPyRng::new(4);
    let m = MatD::randn(&mut rng, 9, 6);   // 9 rows, 6 cols (54 elements)
    let m1 = MatD::randn(&mut rng, 3, 6);
    let m2 = MatD::randn(&mut rng, 3, 6);
    let m3 = MatD::randn(&mut rng, 3, 6);

    // Reshaping
    let reshaped = m.reshape(6, 9);        // must keep the size: 9×6 = 6×9
    let flat = m.flatten();                // Vec<f64>, row-major
    let row_vec = m.ravel();               // 1×n

    // Combining matrices
    let vstacked = MatD::vstack(&[&m1, &m2, &m3]);
    let hstacked = MatD::hstack(&[&m1, &m2]);

    // Splitting matrices (views)
    let parts = m.vsplit(&[3, 7]);         // Split at rows 3 and 7
    let thirds = m.hsplitN(3);             // 3 equal column groups

    // Repeating and tiling
    let repeated = m.repeatAxis(3, 0);     // Repeat each row 3 times
    let tiled = m.tile(2, 3);              // Tile 2 rows × 3 cols

    println!("{:?} {} {:?}", reshaped.shape(), flat.len(), row_vec.shape());
    println!("{:?} {:?} {} {}", vstacked.shape(), hstacked.shape(), parts.len(), thirds.len());
    println!("{:?} {:?}", repeated.shape(), tiled.shape());
}
```

### Comparison & Boolean Operations
```rust
use uni::NumPyRng;
use uni::udata::{MatBool, MatD};

fn main() {
    let mut rng = NumPyRng::new(5);
    let m = MatD::randn(&mut rng, 5, 4);

    // Comparisons return a MatBool; the semantics are IEEE like NumPy's operators
    let mask1 = m.gt(0.5);
    let mask2 = m.lte(1.0);
    let mask3 = m.eqTo(0.0);

    // Combine masks with & | ! on references
    let band: MatBool = &m.gte(-1.0) & &m.lte(1.0);
    let outside = !&band;

    // Boolean reductions
    let all_positive = m.gt(0.0).all();
    let has_negative = m.lt(0.0).any();
    let cols_all_pos = m.gt(0.0).allAxis(0);
    let how_many = band.count();

    // NaN and infinity checks
    let has_nan = m.isnan();
    let has_inf = m.isinf();
    let finite = m.isfinite();
    let cleaned = m.nanToNum(0.0, 0.0, 0.0);

    // Where
    let clipped_up = band.whereMat(&m, &MatD::zeros(5, 4));
    let flags = band.whereScalar(1.0, 0.0);

    println!("{} {} {} {} {}", mask1.count(), mask2.count(), mask3.count(), outside.count(), how_many);
    println!("{all_positive} {has_negative} {:?}", cols_all_pos.toArray());
    println!("{} {} {} {:?}", has_nan.any(), has_inf.any(), finite.all(), cleaned.shape());
    println!("{:?} {:?}", clipped_up.shape(), flags.sumAxis(0).toArray());
}
```

### Display & Formatting
```rust
use uni::NumPyRng;
use uni::udata::MatD;

fn main() {
    let mut rng = NumPyRng::new(6);
    let m = MatD::randn(&mut rng, 3, 4);

    // Debug prints every row; there is no print-options configuration in the crate
    println!("{m:?}");

    // Custom formatting: iterate the cells
    for i in 0..m.rows() {
        let cells: Vec<String> = (0..m.cols()).map(|j| format!("{:8.2}", m.at(i, j))).collect();
        println!("{}", cells.join(" "));
    }
}
```

### Data Loading & Persistence
CSV goes through `UPath` (the crate's portable path type — Windows/MSYS2/Cygwin/Linux/WSL/macOS
spellings all resolve) and Java's `Double.toString` cell text, so a file written by the Scala
side is read here byte for byte, and vice versa.
```rust
use ndarray::Array2;
use uni::NumPyRng;
use uni::udata::{MatB, MatD, MatF};
use uni::upath::StrPathExts;

fn main() {
    let dir = std::env::temp_dir().join("uni-quickstart");
    std::fs::create_dir_all(&dir).expect("temp dir");
    let file = dir.join("data.csv");
    let path = file.to_string_lossy().as_path().expect("a path");

    // 1. Saving from a MatD (write first so the file exists for reading)
    let mut rng = NumPyRng::new(9);
    let m = MatD::randn(&mut rng, 10, 5);
    assert!(m.writeCsv(&path));                     // comma-separated, Java number text

    // 2. Loading: the loaders give an ndarray Array2<T>; the Mat types take it from there
    let a: Array2<f64> = path.loadMatD();
    let m1 = MatD::fromArray2(&a);                  // as MatD
    let m2 = MatB::fromArray2(&path.readCsv());     // as MatB (exact decimals of the text)
    let m3 = MatF::fromArray2(&path.loadMatF());    // as MatF

    // 3. Any separator, any NaN spelling
    assert!(m.saveCSV(&path, "\t", "NA"));

    println!("{:?} {:?} {:?} {}", m1.shape(), m2.shape(), m3.shape(), m1 == m);
    let _ = std::fs::remove_dir_all(&dir);
}
```

### Working with Different Types
```rust
use uni::udata::{Big, MatB, MatD, MatF};

fn main() {
    // Mat works with f64, f32 and Big (java.math.BigDecimal semantics)
    let doubles = MatD::create(vec![1.5, 2.5, 3.5, 4.5], 2, 2);
    let floats = MatF::create(vec![1.5, 2.5, 3.5, 4.5], 2, 2);
    let bigs = MatB::parseRows(&[&["1.5", "2.5"], &["3.5", "4.5"]]);

    // Conversions
    let f_from_d = MatF::fromMatD(&doubles);        // narrows, round-to-nearest-even
    let b_from_d = MatB::fromMatD(&doubles);        // Java's Double.toString digits
    let back = bigs.toMatD();

    // Big is exact: 0.1 + 0.2 is 0.3
    let three_tenths = Big::parse("0.1").add(&Big::parse("0.2"));

    println!("{:?} {:?} {:?}", doubles.sum(), floats.sum(), bigs.sum().toString());
    println!("{} {} {}", f_from_d == floats, b_from_d == bigs, back == doubles);
    println!("{}", three_tenths.toString());
}
```

### Vector Types (CVecD / RVecD)
`CVecD` (n×1) and `RVecD` (1×n) wrap a `MatD` and deref to it — every matrix method works on
them — and say in a signature which orientation is meant, as the Scala opaque types do. The
Scala `*@` dispatch by static type has no operator form here; use `matmul` on the underlying
matrices and `item()` for the 1×1 result.
```rust
use uni::udata::{CVecD, MatD, RVecD};

fn main() {
    let y = CVecD::apply(&[1.0, 2.0, 3.0]);           // 3×1 column vector
    let x = MatD::create(vec![2.0, 0.0, 0.0, 0.0, 3.0, 0.0, 0.0, 0.0, 4.0], 3, 3);

    let yt: RVecD = y.T();                              // 1×3
    let q = yt.matmul(&x).matmul(&y).item();            // yᵀ X y = 50.0
    let xy = x.matmul(&y);                              // 3×1
    let outer = y.matmul(&yt);                          // 3×3
    let dot = yt.matmul(&y).item();                     // 14.0

    // Arithmetic (deref to MatD): CVecD + CVecD → MatD
    let sum = &*y + &*CVecD::apply(&[0.1, 0.2, 0.3]);
    let s2 = 2.0 * &*y;
    let first = y[0];

    println!("{q} {:?} {:?} {dot} {:?} {:?} {first}", xy.shape(), outer.shape(), sum.toArray(), s2.toArray());
}
```

| Factory | Description |
| :--- | :--- |
| `CVecD::apply(&[1.0, 2.0, 3.0])` | from a slice |
| `CVecD::zeros(n)`<br>`CVecD::ones(n)` | n zeros, n ones |
| `CVecD::fromArray(&arr)` | from a slice (alias) |
| `CVecD::fromMat(m)` | from an n×1 (or 1×n) `MatD` |

`RVecD` has the same factories; `c.T()` and `r.T()` convert between them.

### Extracting a Scalar from a 1×1 Matrix
```rust
use uni::udata::MatD;

fn main() {
    let a = MatD::create(vec![3.0, 1.0, 1.0, 2.0], 2, 2);
    let t: f64 = a.trace();                                     // the scalar directly, or…
    let one_by_one = MatD::create(vec![1.0], 1, 1).matmul(&MatD::create(vec![5.0], 1, 1));
    let s: f64 = one_by_one.item();                             // panics if not 1×1
    println!("{t} {s}");
}
```

### Charts

`uni::uplot` draws charts from any `MatD` — the crate renders SVG itself and opens it in
the browser, or writes it when `saveTo` is set. The `*Svg` twins return the text. Scala's
named parameters are option structs with `Default`; the SVG is byte-identical to the Scala
side's for the same matrix.

```rust
use uni::NumPyRng;
use uni::udata::MatD;
use uni::uplot::{HeatmapOpts, HistOpts, PlotOpts, ScatterOpts};

fn main() {
    let mut rng = NumPyRng::new(7);
    let m = MatD::randn(&mut rng, 200, 3);
    let dir = std::env::temp_dir().join("uni-quickstart");
    let names = vec!["a".to_owned(), "b".to_owned(), "c".to_owned()];
    let at = |name: &str| dir.join(name).to_string_lossy().into_owned();
    m.plot(&PlotOpts { title: "three series".into(), labels: names.clone(), saveTo: at("lines"), ..Default::default() }); // lines.svg
    m.hist(&HistOpts { bins: 25, saveTo: at("hist.html"), ..Default::default() });                                          // a page
    m.T().corrcoef().heatmap(&HeatmapOpts { rowLabels: names.clone(), colLabels: names, saveTo: at("corr"), ..Default::default() });
    let svg = m.scatterSvg(&ScatterOpts { xCol: 0, yCol: 1, title: "a vs b".into(), ..Default::default() }); // the text
    println!("{} {}", svg.len() > 1000, svg.starts_with("<svg"));   // true true
}
```

### NumPy Translation Examples
```python
# NumPy
import numpy as np
rng = np.random.default_rng(42)
X = rng.standard_normal((100, 10))
y = rng.standard_normal((100, 1))
weights = np.linalg.lstsq(X, y)[0]
pred = X @ weights
```
```rust
use uni::NumPyRng;
use uni::udata::MatD;

fn main() {
    let mut rng = NumPyRng::new(42);            // the same draws as np.random.default_rng(42)
    let x = MatD::randn(&mut rng, 100, 10);
    let y = MatD::randn(&mut rng, 100, 1);
    let (weights, _residuals, _rank, _sv) = x.lstsq(&y);
    let pred = x.matmul(&weights);
    println!("{:?} {:?}", weights.shape(), pred.shape());
}
```

## Key Differences from the Scala API
1. **Names carry over; overloads become suffixes**: `sum(0)` → `sumAxis(0)`, `m(mask)` →
   `applyMask(&mask)`, `sort()` → `sort(None)`, `m(i, ::)` → `applyRowAll(i)`.
2. **Immutability**: writes go through `MatMut` (`intoMut` / `freeze`); Scala's `m(i, j) = v`
   has no in-place form on `MatD`.
3. **Operators take references** (`&a + &b`), so arithmetic never moves a matrix; matmul is a
   method (`a.matmul(&b)`), and by default the pinned loop where Scala's `*@` is BLAS.
4. **Fallible calls return `Result`** (`inverse`, `determinant`, `solve`, `cholesky` on a
   singular / non-PD matrix); shape violations panic, as Scala's `require` throws.
5. **No global RNG**: `NumPyRng` is passed explicitly, and it is bit-identical to
   `np.random.default_rng` and Scala's `NumPyRNG`.
6. **No print configuration**: `Debug` prints the whole matrix.

## Next Steps
- [`RustCheatSheet.md`](RustCheatSheet.md) — every operation side by side: uni Scala | uni Rust | NumPy.
- [`RustScriptingGuide.md`](RustScriptingGuide.md) — paths, files, CSV tables, dates and `Big`.
- `cargo doc --open` — the API reference; `rust/PARITY.md` — what is pinned to the JVM and how.
- The demo pairs in `rust/examples/` and `jsrc/` — byte-identical output across the two languages.

## Performance Tips
1. `matmul` is the pinned loop; build with `--features blas` and call `matmulBlas` for large dense products.
2. `slice`/`T`/`broadcastTo` are views — no copy; the `apply*` gathers copy.
3. Reuse one `NumPyRng` for the whole program to keep the draw sequence reproducible.
4. Elementwise operators and reductions are parallel above 64K elements; nothing to configure.

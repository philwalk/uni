# uni — Scala | Rust | NumPy Cheat Sheet

One row per operation, three columns: the Scala library, the Rust crate that ports it
(`vastblue-uni`, `use uni::…`), and NumPy. It parallels [`docs/MatDCheatSheet.md`](../../docs/MatDCheatSheet.md)
section for section, so the two can be read together; every complete example below is
compiled by `checkRustDocs.sh` (CI and release gate), so what is shown is what builds.

**The one rule for reading the two columns:** a Rust method carries the Scala method's
name — `sumAxis`, `matmulPure`, `whereMat` — where the Scala name is an overload the Rust
cannot express, the axis or argument type moves into the name (`sum(0)` → `sumAxis(0)`,
`m(mask)` → `applyMask(&mask)`, `sort()` → `sort(None)`). Case tells you whether a Scala
counterpart exists: camelCase mirrors uni, snake_case is a Rust-only helper or a `try_*`
variant.

Cross-language results are pinned by `test-data/mat-parity/` (3,354 rows): every
reduction, view, mask, decomposition and pandas-style op below answers the same bits on
the JVM and in Rust. See `rust/PARITY.md` for what each family pins.

---

## Setup / Import

| | Code |
|---|---|
| **uni Rust** | `use uni::udata::{MatD, MatB, MatF, MatBool, Big, CVecD, RVecD};` |
| **uni Scala** | `import uni.data.*` |
| **NumPy** | `import numpy as np` |

```toml
[dependencies]
uni = { package = "vastblue-uni", version = "0.17" }
```

The crate publishes as `vastblue-uni` (the name `uni` is taken on crates.io) but its
library name is `uni`, so `use uni::…` works as the Scala `import uni.…` does. Optional
feature `blas` routes `matmulBlas` through a system OpenBLAS.

---

## Matrix Creation

| Operation | uni Scala | uni Rust | NumPy |
|---|---|---|---|
| All zeros | `MatD.zeros(r, c)` | `MatD::zeros(r, c)` | `np.zeros((r, c))` |
| All ones | `MatD.ones(r, c)` | `MatD::ones(r, c)` | `np.ones((r, c))` |
| Filled | `Mat.full(r, c, v)` | `MatD::full(r, c, v)` | `np.full((r, c), v)` |
| Identity | `MatD.eye(n)` | `MatD::eye(n)` | `np.eye(n)` |
| Shifted diagonal | `MatD.eye(n, k)` | `MatD::eyeK(n, k)` | `np.eye(n, k=k)` |
| From flat array (row-major, takes ownership) | `MatD(r, c, arr)` | `MatD::create(vec, r, c)` | `np.array(lst).reshape(r, c)` |
| Column vector | `MatD(1.0, 2.0, 3.0)` | `MatD::apply(&[1.0, 2.0, 3.0])` | `np.array([[1],[2],[3]])` |
| Row vector | `MatD.row(1, 2, 3)` | `MatD::create(vec![1.0, 2.0, 3.0], 1, 3)` | `np.array([[1, 2, 3]])` |
| From function | `MatD.tabulate(r, c)((i, j) => f(i, j))` | `MatD::tabulate(r, c, \|i, j\| f(i, j))` | `np.fromfunction(f, (r, c))` |
| Diagonal matrix | `MatD.diag(vec)` | `MatD::diag(&v)` | `np.diag(v)` |
| Range | `MatD.arange(0.0, 1.0, 0.25)` | `MatD::arange(0.0, 1.0, 0.25)` | `np.arange(0, 1, 0.25)` |
| Linear space | `MatD.linspace(0.0, 1.0, 5)` | `MatD::linspace(0.0, 1.0, 5)` | `np.linspace(0, 1, 5)` |
| Zeros / ones / fill like | `MatD.zerosLike(m)` … | `MatD::zerosLike(&m)`, `onesLike`, `fullLike(&m, v)` | `np.zeros_like(m)` … |
| Copy of an array as a column | `arr.toCVec` | `CVecD::fromArray(&arr)` | `np.array(lst)[:, None]` |

`create` **takes ownership** of the `Vec` (Scala's `Mat.create` aliases the array); every other
constructor copies. `arange`/`linspace` are n×1 columns, as in Scala.

---

## Random Generation

The crate has no global RNG: every random constructor takes the generator explicitly, and
`NumPyRng` is bit-identical to `np.random.default_rng` (and to Scala's `NumPyRNG`), so a
seeded draw is the same number in all three languages.

| Operation | uni Scala | uni Rust | NumPy |
|---|---|---|---|
| Generator | `MatD.setSeed(42)` (global) | `let mut rng = NumPyRng::new(42);` | `rng = np.random.default_rng(42)` |
| Standard normal matrix | `MatD.randn(r, c)` | `MatD::randn(&mut rng, r, c)` | `rng.standard_normal((r, c))` |
| Uniform `[0, 1)` | `MatD.rand(r, c)` | `MatD::rand(&mut rng, r, c)` | `rng.random((r, c))` |
| Uniform `[lo, hi)` | `MatD.uniform(lo, hi, r, c)` | `MatD::uniform(&mut rng, lo, hi, r, c)` | `rng.uniform(lo, hi, (r, c))` |
| Normal | `MatD.normal(mu, sd, r, c)` | `MatD::normal(&mut rng, mu, sd, r, c)` | `rng.normal(mu, sd, (r, c))` |
| One draw | `NumPyRNG(seed).randn()` | `rng.randn()`, `rng.uniform(lo, hi)`, `rng.next_f64()` | — |

---

## Shape and Info

| Operation | uni Scala | uni Rust | NumPy |
|---|---|---|---|
| Rows / cols | `m.rows`, `m.cols` | `m.rows()`, `m.cols()` | `m.shape[0]`, `m.shape[1]` |
| Shape | `m.shape` | `m.shape()` | `m.shape` |
| Element count | `m.size` | `m.size()` | `m.size` |
| Empty? | `m.isEmpty` | `m.isEmpty()` | `m.size == 0` |
| Layout | `m.isContiguous`, `m.transposed` | `m.isContiguous()`, `m.transposed()` | `m.flags` |
| Reshape | `m.reshape(r, c)` | `m.reshape(r, c)` | `m.reshape(r, c)` |
| Flatten to a row | `m.ravel` | `m.ravel()` | `m.ravel()` |
| To a `Vec`/array (row-major) | `m.toArray`<br>`m.flatten` | `m.toArray()`<br>`m.flatten()` | `m.ravel().tolist()` |
| Contiguous copy | `m.copy`<br>`m.matCopy` | `m.copy()`<br>`m.matCopy()` | `m.copy()` |
| 1×1 to scalar | `m.item` | `m.item()` | `m.item()` |

---

## Indexing and Slicing

| Operation | uni Scala | uni Rust | NumPy |
|---|---|---|---|
| Single element | `m(i, j)` | `m.at(i, j)` or `m[(i, j)]` | `m[i, j]` |
| Flat index (row-major) | `m.at(k)` | `m.atFlat(k)` | `m.flat[k]` |
| Row (copy) | `m(i, ::)` | `m.applyRowAll(i)` | `m[i, :]` |
| Column (copy) | `m(::, j)` | `m.applyAllCol(j)` | `m[:, j]` |
| Sub-block (copy) | `m(r0 until r1, c0 until c1)` | `m.applyRowsCols(r0..r1, c0..c1)` | `m[r0:r1, c0:c1].copy()` |
| Rows by index list (copy) | `m(Array(2, 0), ::)` | `m.applyRowsIdx(&[2, 0])` | `m[[2, 0], :]` |
| Columns by index list | `m(::, Array(1, 3))` | `m.applyIdxCols(&[1, 3])` | `m[:, [1, 3]]` |
| Both | `m(rows, cols)` | `m.applyIdxIdx(&rows, &cols)` | `m[np.ix_(rows, cols)]` |
| First / last n rows | `m.head(n)`, `m.tail(n)` | `m.head(n)`, `m.tail(n)` | `m[:n]`, `m[-n:]` |
| Zero-copy view | `m.slice(rows, cols)` | `m.slice(r0..r1, c0..c1)` (`i64` ranges; a negative start counts from the end) | `m[r0:r1, c0:c1]` |
| Transpose (O(1) view) | `m.T` | `m.T()`<br>`m.transpose()` | `m.T` |
| Broadcast view | `m.broadcastTo(r, c)` | `m.broadcastTo(r, c)` | `np.broadcast_to(m, (r, c))` |
| Masked elements (1×k row) | `m(mask)` | `m.applyMask(&mask)` | `m[mask]` |

The view model is the Scala's exactly: `slice`, `T`, `broadcastTo` share storage with the
parent (a refcount bump, no copy); the `apply*` gathers copy. A view whose layout is
"fragmented" (a slice of a transposed view) is materialised at creation, in both
languages, so the reductions below take the same summation path.

---

## Writing: `MatMut`

`MatD` is immutable and shared by views; Scala's `m(i, j) = v` becomes an explicit
mutable phase. `intoMut` **panics if the buffer is shared** (another `MatD` or view holds
it) — take `matCopy()` first when unsure; `freeze` gives the `MatD` back.

| Operation | uni Scala | uni Rust | NumPy |
|---|---|---|---|
| Enter / leave the mutable phase | (always mutable) | `let mut w = m.intoMut(); … let m = w.freeze();` | — |
| One cell | `m(i, j) = v` | `w.updateAt(i, j, v)` | `m[i, j] = v` |
| A row / column | `m(i, ::) = v` | `w.updateRowAll(i, v)`, `w.updateAllCol(j, v)` | `m[i, :] = v` |
| A block | `m(rows, cols) = v` | `w.updateRowsCols(rows, cols, v)` | `m[r0:r1, c0:c1] = v` |
| From another matrix | `m(i, ::) = row` | `w.updateRowAllFrom(i, &row)`, `updateAllColFrom`, `updateRowsColsFrom` | `m[i, :] = row` |
| Under a mask | `m(mask) = v` | `w.updateMask(&mask, v)` | `m[mask] = v` |
| Read while mutable | `m(i, j)` | `w.at(i, j)`, `w.applyRowAll(i)` | `m[i, j]` |

---

## Element-wise Arithmetic

Operators are defined on **references** (`&a + &b`), so a matrix is never moved by
arithmetic; scalars go on either side. Broadcasting follows NumPy/Scala: a 1×n or n×1
operand stretches to the other's shape.

| Operation | uni Scala | uni Rust | NumPy |
|---|---|---|---|
| Add / subtract | `a + b`, `a - b` | `&a + &b`, `&a - &b` | `a + b` |
| Multiply / divide (elementwise) | `a * b`, `a / b` | `&a * &b`, `&a / &b` | `a * b`, `a / b` |
| Scalar | `a + 1.0`, `2.0 * a` | `&a + 1.0`, `2.0 * &a` | `a + 1`, `2 * a` |
| Negate | `-a` | `-&a` | `-a` |
| Broadcast a row / column | `m - m.mean(0)` | `&m - &m.meanAxis(0)` | `m - m.mean(0)` |
| Named broadcast helpers | `m.addToEachRow(v)` … | `m.addToEachRow(&v)`, `subFromEachCol`, `mulEachRow`, `divEachCol` … | — |
| Hadamard, spelled | `a.hadamard(b)` | `a.hadamard(&b)` | `a * b` |
| Power | `m ~^ 2`<br>`m.power(2)` | `m.power(2)` (integer, repeated multiply)<br>`m.powerF(2.5)` | `m ** 2` |
| Combine two matrices with a closure | `a.zipMap(b)(f)` | `a.zipMap(&b, \|x, y\| …)` | `np.vectorize(f)(a, b)` |
| Clip | `m.clip(lo, hi)` | `m.clip(lo, hi)` | `np.clip(m, lo, hi)` |
| Round | `m.round(2)` | `m.round(2)` | `np.round(m, 2)` |
| Sign | `m.sign` | `m.sign()` | `np.sign(m)` |
| Elementwise max / min of two | `a.maximum(b)` | `a.maximum(&b)`, `a.maximumScalar(0.0)` | `np.maximum(a, b)` |
| Replace NaN / ∞ | `m.nanToNum()` | `m.nanToNum(0.0, 0.0, 0.0)`, `m.fillna(v)` | `np.nan_to_num(m)` |
| Scale columns (R `scale`) | `m.scale()` | `m.scale(true, true)` | `(m - m.mean(0)) / m.std(0, ddof=1)` |

---

## Matrix Multiplication

| Operation | uni Scala | uni Rust | NumPy |
|---|---|---|---|
| Product | `a *@ b`<br>`a.matmul(b)` | `a.matmul(&b)`<br>`a.dot(&b)` | `a @ b` |
| The pinned loop, whatever the mode | `a.matmulPure(b)` | `a.matmulPure(&b)` | — |
| BLAS, whatever the mode | `a.matmulBlas(b)` | `a.matmulBlas(&b)` (feature `blas`) | `a @ b` |
| Outer / Kronecker / cross | `u.outer(v)`, `a.kron(b)`, `u.cross(v)` | `u.outer(&v)`, `a.kron(&b)`, `u.cross(&v)` | `np.outer`, `np.kron`, `np.cross` |

The two defaults differ, on purpose: Scala's `*@` goes to a native BLAS by default
(`-Duni.mat.blas=os-best`, like NumPy), Rust's `matmul` is the pinned loop (BLAS is a
build-time feature a library cannot switch on for its users). `matmulPure` on both sides is
**bit-identical across the two languages and every machine** — a sequential k-sum from 0.0
per cell — and is what the demo pairs and fixtures call.

---

## Reductions

| Operation | uni Scala | uni Rust | NumPy |
|---|---|---|---|
| Sum / mean | `m.sum`, `m.mean` | `m.sum()`, `m.mean()` | `m.sum()`, `m.mean()` |
| Std / variance (population) | `m.std`, `m.variance` | `m.std()`, `m.variance()` | `m.std()`, `m.var()` |
| Min / max | `m.min`, `m.max` | `m.min()`, `m.max()` | `m.min()`, `m.max()` |
| Arg min / max (row, col) | `m.argmin`, `m.argmax` | `m.argmin()`, `m.argmax()` | `np.unravel_index(m.argmin(), m.shape)` |
| Along an axis | `m.sum(0)`, `m.mean(1)`, `m.std(0)`, `m.min(0)`, `m.max(1)` | `m.sumAxis(0)`, `m.meanAxis(1)`, `m.stdAxis(0)`, `m.minAxis(0)`, `m.maxAxis(1)` | `m.sum(0)` … |
| Row / column sums, means | `m.rowSums`, `m.colMeans` | `m.rowSums()`, `m.colSums()`, `m.rowMeans()`, `m.colMeans()` | `m.sum(1)`, `m.mean(0)` |
| Cumulative | `m.cumsum`, `m.cumsum(1)`, `m.cummax(0)`, `m.cummin(1)` | `m.cumsum()`, `m.cumsumAxis(1)`, `m.cummax(0)`, `m.cummin(1)` | `np.cumsum(m)` … |
| Vector norm | `v.norm` | `v.norm()` | `np.linalg.norm(v)` |
| Matrix norm | `m.norm("fro")` | `m.normOrd(NormOrd::Fro)` (`Inf`, `One`) | `np.linalg.norm(m, 'fro')` |
| Trace / diagonal | `m.trace`, `m.diagonal` | `m.trace()`, `m.diagonal()` | `np.trace(m)`, `np.diag(m)` |
| Reduce each column / row with a closure | `m.applyAlongAxis(f, 0)` | `m.applyAlongAxis(\|v\| …, 0)` | `np.apply_along_axis(f, 0, m)` |

`sum` on a contiguous matrix is `sumD` — 8 unrolled accumulators, chunked above 4096 —
and on a view a sequential fold; **which one runs is a function of the layout, in both
languages alike**, so `m.sum()` and `m.T().sum()` may differ in the last ulp on both sides
identically. `min`/`max`/`argmin`/`argmax` order with `java_double_compare` (Scala's
`Ordering[Double]`: NaN highest, `-0.0 < 0.0`).

---

## Element-wise Math

| Operation | uni Scala | uni Rust | NumPy |
|---|---|---|---|
| Absolute / sqrt / exp / log | `m.abs`, `m.sqrt`, `m.exp`, `m.log` | `m.abs()`, `m.sqrt()`, `m.exp()`, `m.log()` | `np.abs` … |
| Trig / hyperbolic | `m.sin` … `m.tanh`, `m.arctan2(n)` | `m.sin()` … `m.tanh()`, `m.arctan2(&n)` | `np.sin` … |
| Rounding | `m.floor`, `m.ceil`, `m.trunc` | `m.floor()`, `m.ceil()`, `m.trunc()` | `np.floor` … |
| Logs | `m.log10`, `m.log2` | `m.log10()`, `m.log2()` | `np.log10`, `np.log2` |
| Map any closure | `m.map(f)` | `m.mapRows(\|r\| …)`, `m.mapCols(\|c\| …)`, `m.zipMap(&n, \|x, y\| …)` | `np.vectorize(f)(m)` |

`abs` is `if x < 0 { -x } else { x }` — keeps `-0.0`, as the Scala's does. The
transcendental ones (`sin` … `exp`) are within an ulp of the JVM's intrinsics but not
required to agree bit for bit; the fixture pins them on a grid (see `PARITY.md`).

---

## Activation Functions

| Operation | uni Scala | uni Rust | NumPy / framework |
|---|---|---|---|
| Sigmoid / ReLU | `m.sigmoid`, `m.relu` | `m.sigmoid()`, `m.relu()` | `1/(1+np.exp(-m))`, `np.maximum(m, 0)` |
| Leaky ReLU / ELU / GELU | `m.leakyRelu(0.01)`, `m.elu(1.0)`, `m.gelu` | `m.leakyRelu(0.01)`, `m.elu(1.0)`, `m.gelu()` | — |
| Softmax / log-softmax | `m.softmax(1)`, `m.logSoftmax(1)` | `m.softmax(1)`, `m.logSoftmax(1)` | `scipy.special.softmax(m, 1)` |
| Dropout | `m.dropout(0.5)` | `m.dropout(&mut rng, 0.5)` | — |

`relu` keeps `Math.max` semantics (NaN survives, `-0.0` → `+0.0`); `dropout` takes the
generator, and its draw order is the Scala's, so a seeded call agrees exactly.

---

## Boolean / Masking

Comparisons produce a `MatBool`; the mask family is **IEEE**, like NumPy's operators:
every comparison involving NaN is false, `-0.0 == 0.0`.

| Operation | uni Scala | uni Rust | NumPy |
|---|---|---|---|
| Compare with a scalar | `m > 0.5`, `m.gt(0.5)`, `m :== 0.0`, `m :!= 0.0` | `m.gt(0.5)`, `m.lt`, `m.gte`, `m.lte`, `m.eqTo(0.0)`, `m.neTo(0.0)` | `m > 0.5`, `m == 0` |
| NaN / inf tests | `m.isnan`, `m.isinf`, `m.isfinite` | `m.isnan()`, `m.isinf()`, `m.isfinite()` | `np.isnan(m)` … |
| Range | `m.between(lo, hi)` | `m.between(lo, hi)` | `(m >= lo) & (m <= hi)` |
| Combine masks | `a && b`, `a \|\| b`, `!a` | `&a & &b`, `&a \| &b`, `!&a` | `a & b`, `a \| b`, `~a` |
| Count / any / all | `mask.sum`, `mask.any`, `mask.all` | `mask.count()`, `mask.any()`, `mask.all()`, `mask.anyAxis(0)` | `mask.sum()`, `mask.any()` |
| Where | `Mat.where(mask, x, y)`, `Mat.where(mask, 1.0, 0.0)` | `mask.whereMat(&x, &y)`, `mask.whereScalar(1.0, 0.0)` | `np.where(mask, x, y)` |
| Select elements | `m(mask)` | `m.applyMask(&mask)` | `m[mask]` |
| Positions satisfying a predicate | `m.where(p)` | `m.wherePred(\|x\| …)` | `np.argwhere(p(m))` |
| Any element satisfies | `m.exists(p)`, `m.containsNaN` | `m.exists(\|x\| …)`, `m.containsNaN()` | `p(m).any()` |
| Approximately equal | `a.allclose(b)` | `a.allclose(&b, 1e-5, 1e-8)` | `np.allclose(a, b)` |

---

## Stacking and Splitting

| Operation | uni Scala | uni Rust | NumPy |
|---|---|---|---|
| Stack side by side | `Mat.hstack(a, b)` | `MatD::hstack(&[&a, &b])` | `np.hstack((a, b))` |
| Stack on top | `Mat.vstack(a, b)` | `MatD::vstack(&[&a, &b])` | `np.vstack((a, b))` |
| Split by count | `m.vsplit(2)`, `m.hsplit(3)` | `m.vsplitN(2)`, `m.hsplitN(3)`, `m.splitN(n, axis)` | `np.vsplit(m, 2)` |
| Split at indices | `m.vsplit(Array(2, 5))` | `m.vsplit(&[2, 5])`, `m.hsplit(&[…])`, `m.split(&[…], axis)` | `np.vsplit(m, [2, 5])` |
| Repeat / tile | `m.repeat(2, axis = 1)`, `m.tile(2, 3)` | `m.repeatAxis(2, 1)`, `m.repeat(2)`, `m.tile(2, 3)` | `np.repeat`, `np.tile` |
| Rows matching a predicate | `m.filterRows(p)` | `m.filterRows(\|row\| …)` | `m[p(m)]` |
| Triangles | `m.tril(k)`, `m.triu(k)` | `m.tril(k)`, `m.triu(k)` | `np.tril`, `np.triu` |

Splits are views (zero-copy), as in Scala.

---

## Linear Algebra

| Operation | uni Scala | uni Rust | NumPy |
|---|---|---|---|
| Inverse / determinant / solve | `m.inverse`, `m.determinant`, `m.solve(b)` | `m.inverse()?`, `m.determinant()?`, `m.solve(&b)?` — `Result`; singular → `Err(Error::SingularMatrix)` | `np.linalg.inv` … |
| QR | `m.qrDecomposition` | `m.qrDecomposition()` → `(q, r)` | `np.linalg.qr(m)` |
| SVD (economy) | `m.svd` | `m.svd()` → `(u, s, vt)` | `np.linalg.svd(m, full_matrices=False)` |
| Least squares | `m.lstsq(b)`, `MatD.leastSquares(A, b)` | `m.lstsq(&b)` → `(x, residuals, rank, s)`, `MatD::leastSquares(&a, &b)` | `np.linalg.lstsq(A, b)` |
| Pseudo-inverse / rank | `m.pinv()`, `m.matrixRank()` | `m.pinv(None)`, `m.matrixRank(None)` | `np.linalg.pinv`, `matrix_rank` |
| Eigen | `m.eig`, `m.eigenvalues()` | `m.eig()` → `(wr, wi, v)`, `m.eigenvalues()` | `np.linalg.eig` |
| Cholesky | `m.cholesky` | `m.cholesky()?` | `np.linalg.cholesky` |
| Covariance / correlation | `m.cov`, `m.corrcoef` | `m.cov()`, `m.corrcoef()` | `np.cov(m)`, `np.corrcoef(m)` |

The pure-loop routines (`inverse` `determinant` `solve` `qrDecomposition` `cov` `corrcoef`
`trace` `outer` `kron`…) are bit-identical to the JVM; `svd`/`lstsq`/`pinv`/`eig`/`cholesky`
are the crate's own kernels (Jacobi SVD, EISPACK `hqr2`, Cholesky–Banachiewicz) where Scala
uses LAPACK — pinned to tolerance, and eigenvectors are a basis, not a canonical one.

---

## Pandas-Style Data Analysis

| Operation | uni Scala | uni Rust | pandas / NumPy |
|---|---|---|---|
| Sort / argsort | `m.sort()`, `m.sort(0)`, `m.argsort()` | `m.sort(None)`, `m.sort(Some(0))`, `m.argsort(None)` | `np.sort`, `np.argsort` |
| n largest / smallest | `m.nlargest(5)`, `m.nsmallest(5)` | `m.nlargest(5)`, `m.nsmallest(5)` | `s.nlargest(5)` |
| Unique / counts | `m.unique`, `m.nunique`, `m.valueCounts` | `m.unique()`, `m.nunique()`, `m.valueCounts()` | `np.unique(m, return_counts=True)` |
| Differences | `m.diff`, `m.diff(0)`, `m.shift(1, NaN, 0)`, `m.pct_change()` | `m.diff()`, `m.diffAxis(0)`, `m.shift(1, f64::NAN, 0)`, `m.pct_change(0)` | `np.diff`, `s.shift`, `s.pct_change` |
| Percentiles / median | `m.percentile(90)`, `m.median`, `m.median(0)` | `m.percentile(90.0)`, `m.median()`, `m.medianAxis(0)`, `m.percentileAxis(p, axis)` | `np.percentile`, `np.median` |
| Describe | `m.describe` | `m.describe()` → `(labels, 8×cols)` | `df.describe()` |
| Index of min / max per axis | `m.idxmin(0)`, `m.idxmax(1)` | `m.idxmin(0)`, `m.idxmax(1)` | `df.idxmin()` |
| Rolling window | `m.rolling(3).mean` | `m.rolling(3).mean()` (`sum`, `min`, `max`, `std`) | `df.rolling(3).mean()` |
| Histogram | `m.histogram(10)`, `m.histogram(edges)` | `m.histogram(10, None)`, `m.histogramEdges(&edges)` | `np.histogram(m, 10)` |
| Group by / join (named tables) | `result.groupBy("k", AggOp.Mean)`, `a.merge(b, on = "id")` | `table.groupBy("k", AggOp::Mean)`, `groupByOps`, `a.merge(&b, "id", JoinType::Inner)` on `CsvTable<f64>` | `df.groupby`, `pd.merge` |

Every ordering above uses `java_double_compare` and every sort is stable, so `argsort` and
`valueCounts` agree with the JVM to the last index.

---

## Signal Processing (1-D vectors)

| Operation | uni Scala | uni Rust | NumPy |
|---|---|---|---|
| Polynomial fit / eval | `Mat.polyfit(x, y, 2)`, `Mat.polyval(c, x)` | `polyfit(&x, &y, 2)`, `polyval(&c, &x)` (in `uni::udata::signal`) | `np.polyfit`, `np.polyval` |
| Convolution / correlation | `Mat.convolve(a, b, "same")` | `convolve(&a, &b, ConvMode::Same)` (`Full`, `Valid`), `correlate(…)` | `np.convolve`, `np.correlate` |

---

## Display and CSV

| Operation | uni Scala | uni Rust | NumPy |
|---|---|---|---|
| Print | `println(m)`, `m.show("%.4f")` | `println!("{m:?}")` | `print(m)` |
| CSV text / file | `m.saveCSV(path)`, `path.writeCsv(m)` | `m.csvText(",", "NaN")`, `m.saveCSV(&path, ",", "NaN")`, `m.writeCsv(&path)` | `np.savetxt` |
| CSV in | `path.loadMatD`, `path.loadSmartD` | `path.loadMatD()` (`Array2<f64>`), `path.loadSmartD()` (`CsvTable<f64>`) | `np.loadtxt` |

Cell text is Java's `Double.toString` in both languages (`1.0E10`, `-1.0E-5`), so a CSV
written by one side is read by the other byte-for-byte. `Debug` prints all rows; the
Scala print configuration (`Mat.setPrintOptions`) has no Rust counterpart.

---

## Charts (`uni.plot` / `uni::uplot`)

Both sides draw the chart themselves — no charting library — and produce the same SVG
bytes for the same matrix; `saveTo` writes `<name>.svg` (or `.html`), empty opens the
default browser. Scala's named parameters are option structs with `Default` in Rust.

| Operation | uni Scala | uni Rust | matplotlib |
|---|---|---|---|
| Import | `import uni.plot.*` | `use uni::uplot::{PlotOpts, PlotStyle, …};` | `import matplotlib.pyplot as plt` |
| Line plot | `m.plot(title = "t", labels = Seq("a", "b"))` | `m.plot(&PlotOpts { title: "t".into(), labels: vec!["a".into(), "b".into()], ..Default::default() })` | `plt.plot(m)` |
| Scatter, grouped | `m.scatter(0, 1, groupCol = 2)` | `m.scatter(&ScatterOpts { xCol: 0, yCol: 1, groupCol: 2, ..Default::default() })` | `plt.scatter(m[:,0], m[:,1], c=m[:,2])` |
| Histogram | `m.hist(bins = 20)` | `m.hist(&HistOpts { bins: 20, ..Default::default() })` | `plt.hist(m.ravel(), bins=20)` |
| Bar | `m.bar(col = 1, labelCol = 0)` | `m.bar(&BarOpts { col: 1, labelCol: 0, ..Default::default() })` | `plt.bar(m[:,0], m[:,1])` |
| Heatmap | `m.heatmap(rowLabels = names, colLabels = names)` | `m.heatmap(&HeatmapOpts { rowLabels: names.clone(), colLabels: names, ..Default::default() })` | `plt.imshow(m)` |
| Box plot | `m.boxPlot(labels = names)` | `m.boxPlot(&BoxPlotOpts { labels: names, ..Default::default() })` | `plt.boxplot(m)` |
| Scatterplot matrix | `m.pairs(labels = names)` | `m.pairs(&PairsOpts { labels: names, ..Default::default() })` | `pd.plotting.scatter_matrix(df)` |
| Save instead of show | `saveTo = "out/chart"` (→ `out/chart.svg`) | `saveTo: "out/chart".into()` | `plt.savefig("chart.svg")` |
| The SVG text | `m.plotSvg(...)`, `m.histSvg(...)`, … | `m.plotSvg(&opts)`, `m.histSvg(&opts)`, … | — |
| Style | `PlotStyle(width = 900, height = 600, xLabel = "x", yLog = true, seriesColors = Seq(Color.RED))` | `PlotStyle { width: 900, height: 600, xLabel: "x".into(), yLog: true, seriesColors: vec![Color::RED], ..Default::default() }` | `plt.figure(figsize=…)`, `plt.yscale("log")` |

---

## `MatF` and `MatB`

The three element types share one generic core (`Mat<T>`): the same views, slicing,
transpose, stacking and gathers, with type-specific numerics.

| | `MatD = Mat<f64>` | `MatF = Mat<f32>` | `MatB = Mat<Big>` |
|---|---|---|---|
| Scala | `Mat[Double]` | `Mat[Float]` | `Mat[Big]` (exact decimals) |
| Construct | `MatD::create`, `apply`, `zeros`… | `MatF::create`, `row`, `col`, `zeros`, `eye`, `fromMatD` | `MatB::parseRows(&[&["1.5", "2"]])`, `col`, `row`, `fromMatD`, `fromArray2` |
| Arithmetic | operators | `add`, `sub`, `mul`, `div`, `addScalar`… (methods) | `add`, `sub`, `mul`, `div`, `mulScalar(&Big)`… |
| Reductions / axis | `sum` … `stdAxis` | same names, single precision | same names, exact decimals |
| Ordering | `java_double_compare` | `java_float_compare` | `Big::compare` (BigNaN highest) |
| Masks | IEEE | IEEE (widened through `f64`) | ordering masks false against `BigNaN`; `eqTo` and `hasNaN` recognise it |
| Matmul | pinned loop / BLAS | pinned loop | sequential exact k-sum |
| LU | `inverse` `determinant` `solve` | same | same, exact |
| Convert | — | `toMatD()` | `toMatD()` |
| Missing | — | decompositions (Double-only in Scala too) | decompositions; `exp` and `log` go through `f64` |

`Big` itself: `Big::parse("12.34")`, `Big::from_i64`, `Big::from_f64` (Java's
`Double.toString` digits), `add/sub/mul/div/neg/abs/sqrt/pow`, `setScale(2, RoundingMode::HalfEven)`,
`toString`/`toPlainString`, the sentinel `Big::nan()`/`isNaN()`, and `numStr`/`numStrPct`/
`str2num`/`isNumeric` — every one `java.math.BigDecimal`'s rule, fixture-pinned. See
[`docs/BigTypeGuide.md`](../../docs/BigTypeGuide.md).

---

## Column and Row Vectors

`CVecD` (n×1) and `RVecD` (1×n) wrap a `MatD` and deref to it, so every matrix method
is available; they exist for the same reason as the Scala opaque types — a signature that
says which orientation it takes.

| Operation | uni Scala | uni Rust |
|---|---|---|
| Build | `CVec(1.0, 2.0)`, `RVec(1.0, 2.0)`, `arr.toCVec` | `CVecD::apply(&[1.0, 2.0])`, `RVecD::fromArray(&arr)`, `CVecD::zeros(n)` |
| Transpose between them | `c.T` (RVec) | `c.T()` → `RVecD`, `r.T()` → `CVecD` |
| Element | `c(i)` | `c[i]` |
| As a matrix | (is one) | `c.asMat()`, `c.toMat()`, `MatD::from(c)` |
| Row / column sums of a matrix | `m.rowSums` | `m.rowSums()` → `CVecD`, `m.colSums()` → `RVecD` |

---

## Quick Reference Card

```rust
use uni::NumPyRng;
use uni::udata::{MatBool, MatD};

fn main() {
    // Create
    let a = MatD::zeros(3, 4);
    let mut rng = NumPyRng::new(42);          // seeded, NumPy-identical
    let b = MatD::randn(&mut rng, 3, 4);
    let c = MatD::eye(3);
    let w = MatD::uniform(&mut rng, -0.1, 0.1, 64, 32);

    // Slice (zero-indexed; apply* forms copy, slice/T are zero-copy views)
    let row0 = b.applyRowAll(0);
    let col0 = b.applyAllCol(0);
    let bt = b.T();
    let block = b.slice(0..2, 1..3);

    // Arithmetic
    let d = &a + &b;                          // element-wise
    let e = &a * &b;                          // Hadamard product
    let f = c.matmul(&b);                     // matmul (pinned loop)

    // Write, then freeze
    let mut m = b.matCopy().intoMut();
    m.updateAt(0, 0, 1.0);
    m.updateRowsAll(1..3, 0.0);
    let b2 = m.freeze();

    // Activations and reductions
    let g = f.sigmoid();
    let s = b2.sum();
    let mu = b2.meanAxis(0);

    // Boolean
    let mask: MatBool = &b2.eqTo(0.0) | &b2.eqTo(1.0);
    let hits = mask.count();
    let r = mask.whereScalar(1.0, 0.0);

    // Stack / split
    let tall = MatD::vstack(&[&a, &b2]);
    let halves = tall.vsplitN(2);

    println!(
        "{:?} {:?} {:?} {:?} {:?} {:?} {:?} {} {} {:?} {:?} {:?}",
        row0.shape(), col0.shape(), bt.shape(), block.shape(), d.shape(), e.shape(),
        g.shape(), s.is_finite(), hits, r.shape(), halves.len(), (w.shape(), mu.shape())
    );
}
```

---

## Type Summary

| Rust type | Scala type | Notes |
|---|---|---|
| `Mat<T>` | `Mat[T]` | the generic core: descriptor, views, slicing, stacking, gathers |
| `MatD` = `Mat<f64>` | `MatD` = `Mat[Double]` | every numeric method; BLAS opt-in |
| `MatF` = `Mat<f32>` | `MatF` = `Mat[Float]` | single precision, `Float.compare` ordering |
| `MatB` = `Mat<Big>` | `MatB` = `Mat[Big]` | exact decimals, `BigNaN` sentinel |
| `MatBool` | `Mat[Boolean]` | masks; `&`, `\|`, `!` |
| `MatMut` | (a mutable `Mat`) | the write phase: enter with `intoMut`, leave with `freeze` |
| `CVecD`, `RVecD` | `CVecD`, `RVecD` | column / row vectors, deref to `MatD` |
| `Big` | `Big` | `java.math.BigDecimal` semantics |
| `NumPyRng` | `NumPyRNG` | `np.random.default_rng`-identical PCG64 |
| `CsvTable<T>` | `MatResult[T]` | a matrix with headers; `groupBy` and `merge` on `CsvTable<f64>` |
| `Error` | exceptions | `SingularMatrix`, `DimensionMismatch`, `InvalidInput` for the fallible calls; shape violations panic, as Scala's `require` throws |

---

## Every table row above, compiled

The block below calls each Rust spelling in the tables once — it is what makes the
tables trustworthy: `checkRustDocs.sh` builds it (warnings denied) in CI and before every
release, so a row that stops matching the API stops the build.

```rust
#![allow(unused_variables, clippy::many_single_char_names)]
use ndarray::Array2;
use uni::NumPyRng;
use uni::udata::linalg::NormOrd;
use uni::udata::signal::{convolve, correlate, polyfit, polyval, ConvMode};
use uni::udata::{Big, CVecD, MatB, MatBool, MatD, MatF, RVecD};
use uni::udata::big::RoundingMode;
use uni::upath::{AggOp, CsvTable, JoinType};
use uni::uplot::{BarOpts, BoxPlotOpts, Color, HeatmapOpts, HistOpts, PairsOpts, PlotOpts, PlotStyle, ScatterOpts};

fn main() {
    let mut rng = NumPyRng::new(7);
    // creation
    let z = MatD::zeros(2, 3);
    let o = MatD::ones(2, 3);
    let f = MatD::full(2, 3, 2.5);
    let i3 = MatD::eye(3);
    let ik = MatD::eyeK(3, 1);
    let m = MatD::create(vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0], 2, 3);
    let col = MatD::apply(&[1.0, 2.0, 3.0]);
    let row = MatD::create(vec![1.0, 2.0, 3.0], 1, 3);
    let t = MatD::tabulate(2, 2, |i, j| (i * 2 + j) as f64);
    let d = MatD::diag(&[1.0, 2.0]);
    let ar = MatD::arange(0.0, 1.0, 0.25);
    let ls = MatD::linspace(0.0, 1.0, 5);
    let zl = MatD::zerosLike(&m);
    let ol = MatD::onesLike(&m);
    let fl = MatD::fullLike(&m, 9.0);
    let cv = CVecD::fromArray(&[1.0, 2.0]);
    // random
    let rn = MatD::randn(&mut rng, 4, 4);
    let ru = MatD::rand(&mut rng, 2, 2);
    let uu = MatD::uniform(&mut rng, -1.0, 1.0, 2, 2);
    let nn = MatD::normal(&mut rng, 0.0, 1.0, 2, 2);
    let (x1, x2, x3) = (rng.randn(), rng.uniform(0.0, 1.0), rng.next_f64());
    // shape and info
    let info = (m.rows(), m.cols(), m.shape(), m.size(), m.isEmpty(), m.isContiguous(), m.transposed());
    let rs = m.reshape(3, 2);
    let rv = m.ravel();
    let arr = m.toArray();
    let fl2 = m.flatten();
    let cp = m.copy();
    let mc = m.matCopy();
    let it = MatD::create(vec![7.0], 1, 1).item();
    // indexing and slicing
    let e = (m.at(0, 1), m[(1, 2)], m.atFlat(4));
    let r0 = m.applyRowAll(0);
    let c0 = m.applyAllCol(0);
    let blk = m.applyRowsCols(0..2, 1..3);
    let ri = m.applyRowsIdx(&[1, 0]);
    let ci = m.applyIdxCols(&[2, 0]);
    let ii = m.applyIdxIdx(&[1], &[0, 2]);
    let (h, tl) = (m.head(1), m.tail(1));
    let sl = m.slice(0..2, -2..0);
    let mt = m.T();
    let tr = m.transpose();
    let bc = row.broadcastTo(2, 3);
    let masked = m.applyMask(&m.gt(2.0));
    // MatMut
    let mut w = m.matCopy().intoMut();
    w.updateAt(0, 0, 10.0);
    w.updateRowAll(1, 0.0);
    w.updateAllCol(2, 1.0);
    w.updateRowsCols(0..1, 0..2, 5.0);
    w.updateRowAllFrom(0, &row);
    w.updateAllColFrom(0, &MatD::apply(&[8.0, 9.0]));
    w.updateRowsColsFrom(0..2, 0..3, &m);
    w.updateMask(&m.lt(3.0), -1.0);
    let peek = (w.at(0, 0), w.applyRowAll(0));
    let m2 = w.freeze();
    // arithmetic
    let ops = (&m + &m2, &m - &m2, &m * &m2, &m / &(&m2 + 1.0), &m + 1.0, 2.0 * &m, -&m);
    let bcast = &m - &m.meanAxis(0);
    let col2 = col.applyRowsAll(0..2);
    let helpers = (m.addToEachRow(&row), m.subFromEachCol(&col2), m.mulEachRow(&row), m.divEachCol(&col2));
    let hd = m.hadamard(&m2);
    let pw = (m.power(2), m.powerF(2.5));
    let zm = m.zipMap(&m2, |x, y| x.max(y));
    let misc = (m.clip(2.0, 5.0), m.round(1), m.sign(), m.maximum(&m2), m.maximumScalar(3.0), m.minimum(&m2), m.minimumScalar(3.0));
    let nans = (m.nanToNum(0.0, 0.0, 0.0), m.fillna(0.0), m.scale(true, true));
    // matmul
    let prod = (m.matmul(&mt), m.dot(&mt), m.matmulPure(&mt));
    let more = (row.outer(&col), m.kron(&i3), row.cross(&row));
    // reductions
    let red = (m.sum(), m.mean(), m.std(), m.variance(), m.min(), m.max(), m.argmin(), m.argmax());
    let axes = (m.sumAxis(0), m.meanAxis(1), m.stdAxis(0), m.minAxis(0), m.maxAxis(1));
    let rc = (m.rowSums(), m.colSums(), m.rowMeans(), m.colMeans());
    let cum = (m.cumsum(), m.cumsumAxis(1), m.cummax(0), m.cummin(1));
    let norms = (row.norm(), m.normOrd(NormOrd::Fro), m.normOrd(NormOrd::Inf), m.normOrd(NormOrd::One));
    let td = (i3.trace(), i3.diagonal());
    let along = m.applyAlongAxis(|v| v.sum(), 0);
    // elementwise math
    let em = (m.abs(), m.sqrt(), m.exp(), m.log(), m.sin(), m.cos(), m.tan(), m.tanh(), m.arctan2(&m2));
    let em2 = (m.floor(), m.ceil(), m.trunc(), m.log10(), m.log2());
    let mapped = (m.mapRows(|r| r - r.mean()), m.mapCols(|c| c * 2.0));
    // activations
    let act = (m.sigmoid(), m.relu(), m.leakyRelu(0.01), m.elu(1.0), m.gelu(), m.softmax(1), m.logSoftmax(1), m.dropout(&mut rng, 0.5));
    // masks
    let mk = (m.gt(2.0), m.lt(2.0), m.gte(2.0), m.lte(2.0), m.eqTo(2.0), m.neTo(2.0), m.isnan(), m.isinf(), m.isfinite(), m.between(2.0, 4.0));
    let comb: MatBool = &(&m.gt(1.0) & &m.lt(5.0)) | &!&m.eqTo(3.0);
    let cnt = (comb.count(), comb.any(), comb.all(), comb.anyAxis(0), comb.allAxis(1));
    let wh = (comb.whereMat(&m, &m2), comb.whereScalar(1.0, 0.0));
    let pos = (m.wherePred(|x| x > 4.0), m.exists(|x| x > 4.0), m.containsNaN(), m.allclose(&m2, 1e-5, 1e-8));
    // stacking and splitting
    let st = (MatD::hstack(&[&m, &m2]), MatD::vstack(&[&m, &m2]));
    let sp = (m.vsplitN(2), m.hsplitN(3), m.splitN(2, 0), m.vsplit(&[1]), m.hsplit(&[1, 2]), m.split(&[1], 1));
    let rt = (m.repeatAxis(2, 1), m.repeat(2), m.tile(2, 3), m.filterRows(|r| r.sum() > 6.0), m.tril(0), m.triu(1));
    // linear algebra
    let a = &rn + &(&MatD::eye(4) * 4.0);
    let inv = a.inverse().unwrap();
    let det = a.determinant().unwrap();
    let sol = a.solve(&a.applyAllCol(0)).unwrap();
    let (q, r) = a.qrDecomposition();
    let (u, s, vt) = a.svd();
    let (xs, res, rank, sv) = a.lstsq(&a.applyAllCol(1));
    let lsq = MatD::leastSquares(&a, &a.applyAllCol(1));
    let pr = (a.pinv(None), a.matrixRank(None));
    let (wr, wi, v) = a.eig();
    let ev = a.eigenvalues();
    let spd = &a.matmul(&a.T()) + &(&MatD::eye(4) * 4.0);
    let ch = spd.cholesky().unwrap();
    let cc = (m.cov(), m.corrcoef());
    // pandas-style
    let pd = (m.sort(None), m.sort(Some(0)), m.argsort(None), m.nlargest(2), m.nsmallest(2));
    let un = (m.unique(), m.nunique(), m.valueCounts());
    let df = (m.diff(), m.diffAxis(0), m.shift(1, f64::NAN, 0), m.pct_change(0));
    let pc = (m.percentile(90.0), m.median(), m.medianAxis(0), m.percentileAxis(25.0, 1));
    let (labels, desc) = m.describe();
    let ix = (m.idxmin(0), m.idxmax(1));
    let roll = (m.rolling(2).mean(), m.rolling(2).sum(), m.rolling(2).min(), m.rolling(2).max(), m.rolling(2).std());
    let hg = (m.histogram(3, None), m.histogramEdges(&[0.0, 3.0, 7.0]));
    let table = CsvTable {
        headers: vec!["k".to_owned(), "v".to_owned()],
        mat: Array2::from_shape_vec((2, 2), vec![1.0, 10.0, 1.0, 20.0]).unwrap(),
    };
    let grouped = (table.groupBy("k", AggOp::Mean), table.groupByOps("k", &[("v", AggOp::Sum)]), table.merge(&table, "k", JoinType::Inner));
    // signal
    let coeffs = polyfit(&ar, &ar.power(2), 2);
    let sig = (polyval(&coeffs, &ar), convolve(&row, &row, ConvMode::Same), correlate(&row, &row, ConvMode::Full));
    // display and CSV
    let text = (format!("{m:?}"), m.csvText(",", "NaN"));
    // charts: the *Svg twins render without touching a file or a browser
    let names = vec!["a".to_owned(), "b".to_owned(), "c".to_owned()];
    let style = PlotStyle { width: 600, height: 400, xLabel: "x".into(), seriesColors: vec![Color::RED], ..Default::default() };
    let charts = (
        m.plotSvg(&PlotOpts { title: "t".into(), labels: names.clone(), style, ..Default::default() }).len(),
        m.scatterSvg(&ScatterOpts { xCol: 0, yCol: 1, groupCol: 2, ..Default::default() }).len(),
        m.histSvg(&HistOpts { bins: 5, ..Default::default() }).len(),
        m.barSvg(&BarOpts { col: 1, labelCol: 0, ..Default::default() }).len(),
        m.heatmapSvg(&HeatmapOpts { rowLabels: names.clone(), colLabels: names.clone(), ..Default::default() }).len(),
        m.boxPlotSvg(&BoxPlotOpts { labels: names.clone(), ..Default::default() }).len(),
        m.pairsSvg(&PairsOpts { labels: names, ..Default::default() }).len(),
    );
    // MatF and MatB
    let mf = MatF::fromMatD(&m);
    let mff = (mf.sum(), mf.meanAxis(0), mf.matmul(&mf.T()), mf.gt(2.0), mf.sort(), mf.toMatD(), MatF::eye(2), MatF::row(&[1.0, 2.0]));
    let mb = MatB::parseRows(&[&["1.50", "2"], &["-3", "0.125"]]);
    let two = Big::parse("2");
    let mbb = (mb.sum().toString(), mb.mulScalar(&two), mb.inverse().unwrap(), mb.gt(&Big::zero()), mb.eqTo(&Big::nan()), mb.toMatD(), MatB::fromMatD(&m), MatB::fromArray2(&Array2::from_shape_vec((1, 1), vec![Big::one()]).unwrap()));
    let big = (Big::parse("12.34").add(&Big::from_i64(1)).setScale(1, RoundingMode::HalfEven).toString(), Big::from_f64(0.1).toPlainString(), Big::nan().isNaN());
    // vectors
    let cvec = CVecD::apply(&[1.0, 2.0]);
    let rvec: RVecD = cvec.T();
    let back: MatD = rvec.T().toMat();
    let el = cvec[1];
    println!("{:?}", (z.shape(), o.shape(), f.shape(), i3.shape(), ik.shape(), col.shape(), t.shape(), d.shape()));
    println!("{:?}", (ar.shape(), ls.shape(), zl.shape(), ol.shape(), fl.shape(), cv.rows(), rn.shape(), ru.shape()));
    println!("{:?}", (uu.shape(), nn.shape(), x1.is_finite(), x2, x3, info, rs.shape(), rv.shape()));
    println!("{:?}", (arr.len(), fl2.len(), cp.shape(), mc.shape(), it, e, r0.shape(), c0.shape()));
    println!("{:?}", (blk.shape(), ri.shape(), ci.shape(), ii.shape(), h.shape(), tl.shape(), sl.shape(), mt.shape()));
    println!("{:?}", (tr.shape(), bc.shape(), masked.shape(), peek.0, m2.shape(), ops.0.shape(), bcast.shape(), helpers.0.shape()));
    println!("{:?}", (hd.shape(), pw.0.shape(), zm.shape(), misc.0.shape(), nans.0.shape(), prod.0.shape(), more.0.shape()));
    println!("{:?}", (red, axes.0.shape(), rc.0.rows(), cum.0.shape(), norms, td, along.shape()));
    println!("{:?}", (em.0.shape(), em2.0.shape(), mapped.0.shape(), act.0.shape(), mk.0.shape(), cnt, wh.0.shape()));
    println!("{:?}", (pos, st.0.shape(), sp.0.len(), rt.0.shape(), inv.shape(), det, sol.shape(), q.shape()));
    println!("{:?}", (r.shape(), u.shape(), s.len(), vt.shape(), xs.shape(), res.shape(), rank, sv.len()));
    println!("{:?}", (lsq.rank, pr.1, wr.len(), wi.len(), v.shape(), ev.len(), ch.shape(), cc.0.shape()));
    println!("{:?}", (pd.0.shape(), un.1, df.0.shape(), pc.0, labels.len(), desc.shape(), ix.0.shape(), roll.0.shape()));
    println!("{:?}", (hg.0 .0.len(), grouped.0.headers.len(), coeffs.shape(), sig.0.shape(), text.1.len()));
    println!("{:?}", (mff.0, mbb.0, big, back.shape(), el, charts.0 > 0));
}
```

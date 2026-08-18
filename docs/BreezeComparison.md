# Breeze ↔ uni.MatD — comparison and cheat sheet

For readers who know [Breeze](https://github.com/scalanlp/breeze): what changes when a
program moves to `uni.MatD`, every operation side by side, and how the two perform on the
same machines. `MatD` = `Mat[Double]` in [uni](../README.md); the general reference is
[MatDCheatSheet.md](MatDCheatSheet.md) (MatD | NumPy | R | MATLAB) and the Rust twin is
[the Scala | Rust | NumPy sheet](../rust/docs/RustCheatSheet.md).

---

## Migrating from Breeze

Key syntax differences for Breeze users. Most idioms carry over directly; the main changes are
operator names and a few method renames.

| Breeze | MatD | Note |
|--------|------|------|
| `X * Y` | `X *@ Y` | Matrix multiply (`*` is element-wise in MatD) |
| `X :* Y` | `X * Y` | Element-wise multiply |
| `X :/ Y` | `X / Y` | Element-wise divide |
| `X ^:^ p` | `X ~^ p` | Element-wise power; `~^` binds tighter than `*@` — write `(X ~^ 2) *@ Y` |
| `X.t` | `X.T` | Transpose |
| `sum(X, Axis._0)` | `X.sum(axis=0)` | Column-wise sum |
| `sum(X, Axis._1)` | `X.sum(axis=1)` | Row-wise sum |
| `X(::, *).map(f)` | `X(::, *).map(f)` or `X.eachCol.map(f)` | Apply f to each column |
| `X(*, ::).map(f)` | `X(*, ::).map(f)` or `X.eachRow.map(f)` | Apply f to each row |
| `X(i, ::)` | `X(i, ::)` | Row slice (identical) |
| `X(::, j)` | `X(::, j)` | Column slice (identical) |
| `svd(X)` | `X.svd` | SVD decomposition |
| `X \ b` | `X.solve(b)` | Least-squares solve |

<sub>The broadcast sentinel `*` is spelled identically in both libraries. In files that import both,
use `X.eachCol` / `X.eachRow` to sidestep the name collision, or rename at import:
`import uni.data.{\`*\` as All}`.</sub>

---

## Operation by operation

The MatD and Breeze columns of the cheat sheet, section for section. `import uni.data.*` on
one side, `import breeze.linalg.*` on the other.

### Matrix Creation

| Operation | MatD | Breeze |
|---|---|---|
| All zeros | `MatD.zeros(r, c)` | `DenseMatrix.zeros[Double](r, c)` |
| All ones | `MatD.ones(r, c)` | `DenseMatrix.ones[Double](r, c)` |
| Identity | `MatD.eye(n)` | `DenseMatrix.eye[Double](n)` |
| From flat array | `MatD(r, c, arr)` | `new DenseMatrix(r, c, arr)` |
| From 2-D array | `arr2d.toMat`<br>`MatD(arr2d)` | `DenseMatrix(rows: _*)` |
| From rows (`Seq[Array]`) | `rows.toMat`<br>`MatD.fromRows(rows)` | `DenseMatrix(rows: _*)` |
| From rows (tuples) | `MatD((1,2),(3,4))` | `DenseMatrix((1.0,2.0),(3.0,4.0))` |
| Column vector | `MatD(1.0, 2.0, 3.0)` | `DenseVector(1.0, 2.0, 3.0)` |
| Column from array | `arr.toCVec`<br>`MatD(arr)` | `DenseVector(arr)` |
| Row vector | `MatD.row(1, 2, 3)` | `DenseVector(1.0, 2.0, 3.0).t` |
| Row from array | `arr.toRVec` | `DenseVector(arr).t` |
| Wrap array, no copy | `Mat.wrap(arr, r, c)` | `new DenseMatrix(r, c, arr)` |
| From function | `MatD.tabulate(r,c)((i,j) => f(i,j))` | `DenseMatrix.tabulate(r,c)(f)` |
| Diagonal matrix | `MatD.diag(vec)` | `diag(v)` |
| Zeros like | `MatD.zerosLike(m)` | `DenseMatrix.zeros[Double](m.rows, m.cols)` |
| Ones like | `MatD.onesLike(m)` | `DenseMatrix.ones[Double](m.rows, m.cols)` |
| Fill like | `MatD.fullLike(m, v)` | `DenseMatrix.fill(m.rows, m.cols)(v)` |

### Random Generation

| Operation | MatD | Breeze |
|---|---|---|
| Set seed | `MatD.setSeed(42)` | `RandBasis.withSeed(42)` |
| Uniform [0,1) | `MatD.rand(r, c)` | `DenseMatrix.rand(r, c)` |
| Standard normal | `MatD.randn(r, c)` | `DenseMatrix.randn(r, c)` |
| Uniform [lo,hi) | `MatD.uniform(lo, hi, r, c)` | `DenseMatrix.rand(r,c,Uniform(lo,hi))` |
| Normal(μ,σ) | `MatD.normal(mu, sd, r, c)` | `DenseMatrix.rand(r,c,Gaussian(mu,sd))` |
| Random ints | `MatD.randint(lo, hi, r, c)` | `DenseMatrix.rand(r,c,...).map(_.toInt)` |

### Shape and Info

| Operation | MatD | Breeze |
|---|---|---|
| Dimensions | `m.shape` | `(m.rows, m.cols)` |
| Row count | `m.rows`<br>`m.shape._1` | `m.rows` |
| Col count | `m.cols`<br>`m.shape._2` | `m.cols` |
| Total elements | `m.size` | `m.size` |
| Reshape | `m.reshape(r, c)` | `m.reshape(r, c)` |
| Flatten to array | `m.flatten` | `m.data` |
| Flatten to row vec | `m.ravel` | — |
| Extract scalar | `m.item`<br>`m(0, 0)` | `m(0,0)` |

### Indexing and Slicing

| Operation | MatD | Breeze |
|---|---|---|
| Single element | `m(i, j)` | `m(i, j)` |
| Row slice (copy) | `m(i, ::)` | `m(i, ::)` |
| Column slice (copy) | `m(::, j)` | `m(::, j)` |
| Zero-copy view | `m.slice(rows, cols)` | — |
| Transpose (O(1)) | `m.T` or `m.transpose` | `m.t` |

### Column / Row Mapping

| Operation | MatD | Breeze |
|---|---|---|
| Map each column | `m.eachCol.map(f)`<br>`m(::, *).map(f)`<br>`m.mapCols(f)` | `X(::, *).map(f)` |
| Map each row | `m.eachRow.map(f)`<br>`m(*, ::).map(f)`<br>`m.mapRows(f)` | `X(*, ::).map(f)` |

### Element-wise Arithmetic

| Operation | MatD | Breeze |
|---|---|---|
| Add matrices | `a + b` | `a + b` |
| Subtract | `a - b` | `a - b` |
| Multiply (Hadamard) | `a * b` | `a :* b` |
| Divide | `a / b` | `a :/ b` |
| Add scalar | `a + 2.0` | `a + 2.0` |
| Multiply scalar | `a * 3.0` | `a * 3.0` |

### Matrix Multiplication

| Operation | MatD | Breeze |
|---|---|---|
| Matrix multiply | `a *@ b` | `a * b` |
| Mat × col-vec | `X *@ y` where `y: CVecD` → `CVecD` | `X * y` |
| row-vec × Mat | `r *@ X` where `r: RVecD` → `RVecD` | `r * X` |
| dot product | `y *@ y` (CVec auto-transposes) → `Double` | `y dot y` |
| outer product | `y *@ y.T` (CVec × RVec) → `MatD` | `y * y.t` |

### In-place Operations

| Operation | MatD | Breeze |
|---|---|---|
| Add scalar in-place | `m :+= 2.0` | `m :+= 2.0` |
| Subtract scalar | `m :-= 1.0` | `m :-= 1.0` |
| Multiply scalar | `m :*= 3.0` | `m :*= 3.0` |
| Divide scalar | `m :/= 2.0` | `m :/= 2.0` |
| Add matrix in-place | `m :+= n` | `m :+= n` |

### Reductions

| Operation | MatD | Breeze |
|---|---|---|
| Sum all | `m.sum` | `sum(m)` |
| Mean all | `m.mean` | `mean(m)` |
| Min all | `m.min` | `min(m)` |
| Max all | `m.max` | `max(m)` |
| Std dev all | `m.std` | `stddev(m)` |
| Variance all | `m.variance` | `variance(m)` |
| Median all | `m.median` | `median(m)` |
| Per column | `m.sum(axis=0)` → 1×cols | `sum(m, Axis._0)` |
| Per row | `m.sum(axis=1)` → rows×1 | `sum(m, Axis._1)` |

### Element-wise Math

| Operation | MatD | Breeze |
|---|---|---|
| Absolute value | `m.map(_.abs)` | `abs(m)` |
| Square root | `m.map(math.sqrt)` | `sqrt(m)` |
| Exponential | `m.map(math.exp)` | `exp(m)` |
| Log | `m.map(math.log)` | `log(m)` |
| Power (scalar) | `m ~^ p` | `m ^:^ p` |
| Map arbitrary fn | `m.map(f)` | `m.map(f)` |

### Activation Functions

| Operation | MatD | Breeze |
|---|---|---|
| ReLU | `m.relu` | `max(m, 0.0)` |
| Sigmoid | `m.sigmoid` | `sigmoid(m)` |
| Softmax | `m.softmax` | `softmax(m)` |
| Leaky ReLU | `m.leakyRelu` | — |

### Boolean / Masking

| Operation | MatD | Breeze |
|---|---|---|
| Element comparison | `m :== 0.0` → `Mat[Boolean]` | `m :== 0.0` |
| Not equal | `m :!= 0.0` | `m :!= 0.0` |
| Greater / less | `m > 2.0`, `m < 2.0` (or `gt`, `lt`) | `m >:> 2.0`, `m <:< 2.0` |
| Greater/less or equal | `m >= 2.0`, `m <= 2.0` (or `gte`, `lte`) | `m >:= 2.0`, `m <:= 2.0` |
| In range | `m.between(lo, hi)` | — |
| Combine masks | `a && b`, `a \|\| b` | `a &:& b`, `a \|:\| b` |
| Negate mask | `!mask` | `!mask` |
| Count true | `mask.sum` | `sum(mask)` |
| Any true | `mask.any` | `any(mask)` |
| All true | `mask.all` | `all(mask)` |
| Conditional select | `Mat.where(cond, x, y)` | `where(cond, x, y)` |

### Stacking and Splitting

| Operation | MatD | Breeze |
|---|---|---|
| Stack rows | `MatD.vstack(a, b)` | `DenseMatrix.vertcat(a, b)` |
| Stack cols | `MatD.hstack(a, b)` | `DenseMatrix.horzcat(a, b)` |
| Split rows | `m.vsplit(n)` | — |
| Split cols | `m.hsplit(n)` | — |

### Signal Processing (1-D Vectors)

| Operation | MatD | Breeze |
|---|---|---|
| Polynomial fit | `MatD.polyfit(x, y, deg)` | — |
| Polynomial eval | `MatD.polyval(coeffs, x)` | — |
| Convolve | `MatD.convolve(a, b)` | `convolve(a, b)` |
| Correlate | `MatD.correlate(a, b)` | — |
| Meshgrid | `MatD.meshgrid(x, y)` | — |

### Display and Formatting

| Operation | MatD | Breeze |
|---|---|---|
| Print default | `println(m)` | `println(m)` |
| Explicit show | `m.show` | `m.toString` |
| Custom format | `m.show("%.2f")` | — |
| Set thresholds | `Mat.setPrintOptions(maxRows=20, maxCols=20, edgeItems=5)` | — |

---

## Performance vs Breeze

Measured on the same machine: Breeze 2.1.0 vs uni.MatD 0.14.1 / Scala 3.8.2 / JVM 21; min times.
Both ran `matmul` on the same pure-JVM VectorBLAS fallback (JNIBLAS unavailable on this
machine), so that row is a like-for-like comparison. See [`jsrc/benchBreeze.sc`](../jsrc/benchBreeze.sc) to reproduce.

| Operation | MatD | Breeze | Ratio | Notes |
|---|---:|---:|---|---|
| `randn(1000×1000)` | 7.1 ms | 21.6 ms | **3.1× faster** | PCG64 (MatD) vs Gaussian sampler (Breeze) |
| `matmul 512×512` | 5.4 ms | 5.3 ms | **tied** | Same VectorBLAS fallback backend on both; with JNIBLAS both call OpenBLAS at the same latency |
| `sigmoid(1000×1000)` | 0.73 ms | 4.2 ms | **5.7× faster** | Parallel fork/join (MatD) vs sequential UFunc (Breeze) |
| `relu(1000×1000)` | 0.51 ms | 2.9 ms | **5.7× faster** | Parallel fork/join (MatD) vs sequential map (Breeze) |
| `add(1000×1000)` | 0.59 ms | 1.2 ms | **2.0× faster** | Parallel fork/join (MatD) vs sequential element-wise (Breeze) |
| `sum(1000×1000)` | 0.04 ms | 0.37 ms | **8.4× faster** | v0.14.1 chunked multi-accumulator parallel reduction vs sequential loop |
| `mean(1000×1000)` | 0.03 ms | 3.6 ms | **~109× faster** | Same reduction as `sum`; Breeze mean is a slow generic path |
| `std(1000×1000)` | 0.48 ms | 5.4 ms | **11× faster** | Two-pass with the v0.14.1 reduction for the mean pass |
| `transpose(1000×1000)` | ≈0 ms | ≈0 ms | **tied** | O(1) stride-flip in both — no data copy |
| `mapParallel` custom fn | 0.52 ms | 5.9 ms | **11× faster** | Parallel fork/join (MatD) vs sequential map (Breeze) |

**Practical guidance:**
- MatD wins 6 of the 7 operations `benchBreeze.sc` scores and ties the seventh (matmul); geometric mean: MatD is **~4.3×** faster overall (the sub-50 µs `sum`/`mean`/`transpose` rows are excluded from scoring).
- Matmul is tied: both libraries ran the same VectorBLAS fallback here; with netlib JNIBLAS loaded (direct Java array passing, no DoublePointer/DirectBuffer overhead) both call native OpenBLAS at the same latency.
- Element-wise, reduction, and custom-function operations show the largest gaps because MatD parallelizes with fork/join while Breeze processes elements sequentially.

### Three machines, uni 0.14.1

The tables the README carried through 0.17.0. NumPy: Python 3.14.6 / NumPy 2.4.6
([`py/bench.py`](../py/bench.py)); Breeze/MatD: uni 0.14.1 / Scala 3.8.2 / JVM 21
([`jsrc/bench.sc`](../jsrc/bench.sc), [`jsrc/benchBreeze.sc`](../jsrc/benchBreeze.sc)); min
times. Current NumPy vs Scala vs Rust numbers are regenerated in
[MatDBenchmarks.md](MatDBenchmarks.md).

#### Windows 11 (zx)

| Operation | NumPy | Breeze | MatD |
| :--- | ---: | ---: | ---: |
| `randn(1000×1000)` | 8.5 ms | 21.6 ms | 6.8 ms |
| `matmul 512×512` | 0.79 ms | 5.3 ms | 3.2 ms |
| `sigmoid(1000×1000)` | 9.0 ms | 4.2 ms | 0.73 ms |
| `relu(1000×1000)` | 1.3 ms | 2.9 ms | 0.51 ms |
| `add(1000×1000)` | 1.7 ms | 1.2 ms | 0.58 ms |
| `sum(1000×1000)` | 0.13 ms | 0.37 ms | 0.03 ms |
| `mean(1000×1000)` | 0.14 ms | 3.6 ms | 0.03 ms |
| `std(1000×1000)` | 2.4 ms | 5.4 ms | 0.43 ms |
| `transpose(1000×1000)` | ≈0 ms | ≈0 ms | ≈0 ms |
| custom fn (`mapParallel` / `map` / `np.vectorize`) | 64 ms | 5.9 ms | 0.53 ms |

MatD wins 8/9 scored operations vs NumPy — matmul is the exception, the reproducible
default loop losing to NumPy's native OpenBLAS unless BLAS is opted in — and wins or ties
all scored operations vs Breeze (benchBreeze geometric mean **~4.3×** over its 7 scored ops).
The v0.14.1 chunked multi-accumulator parallel reduction keeps `sum`/`mean`/`std`
4–6× ahead of NumPy 2.4.x's SIMD reductions.
`matmul` vs Breeze is tied here — both ran a pure-JVM loop; with BLAS opted in on
MatD's side both call OpenBLAS at the same latency.

#### Linux (Intel Core i5-6500, Ubuntu 24.04, OpenBLAS)

Breeze/MatD: uni 0.14.1 / Scala 3.8.2 / JVM 21, both using native OpenBLAS via netlib JNIBLAS.

| Operation | Breeze | MatD | Bz/MD |
| :--- | ---: | ---: | ---: |
| `randn(1000×1000)` | 57.7 ms | 15.6 ms | 3.7× |
| `matmul 512×512` | 1.83 ms | 1.82 ms | 1.00× |
| `sigmoid(1000×1000)` | 9.52 ms | 3.66 ms | 2.6× |
| `relu(1000×1000)` | 3.87 ms | 1.06 ms | 3.7× |
| `add(1000×1000)` | 1.85 ms | 2.34 ms | 0.79× |
| `sum(1000×1000)` | 1.17 ms | 0.18 ms | 6.7× |
| `mean(1000×1000)` | 7.33 ms | 0.27 ms | 27× |
| `std(1000×1000)` | 8.97 ms | 1.55 ms | 5.8× |
| `transpose(1000×1000)` | ≈0 ms | ≈0 ms | — |
| custom fn (`mapParallel` / `map`) | 10.68 ms | 1.08 ms | 9.9× |

MatD faster 8/9 scored, geometric mean **4.03× faster** than Breeze.
`matmul` is tied (both use OpenBLAS via JNIBLAS); `add` is the lone Breeze win (0.79×). `sum` flipped to a 6.7× MatD win after the v0.14.1 chunked parallel-reduction rewrite.

#### macOS (Apple Silicon)

Breeze/MatD: uni 0.14.1 / Scala 3.8.2 / JVM 21, both using native OpenBLAS via netlib JNIBLAS.

| Operation | Breeze | MatD | Bz/MD |
| :--- | ---: | ---: | ---: |
| `randn(1000×1000)` | 40.0 ms | 3.93 ms | 10.2× |
| `matmul 512×512` | 1.06 ms | 1.05 ms | 1.01× |
| `sigmoid(1000×1000)` | 12.5 ms | 2.64 ms | 4.7× |
| `relu(1000×1000)` | 1.66 ms | 0.37 ms | 4.5× |
| `add(1000×1000)` | 0.78 ms | 1.04 ms | 0.75× |
| `sum(1000×1000)` | 0.97 ms | 0.10 ms | 9.3× |
| `mean(1000×1000)` | 5.45 ms | 0.08 ms | 72× |
| `std(1000×1000)` | 8.06 ms | 1.03 ms | 7.8× |
| `transpose(1000×1000)` | ≈0 ms | ≈0 ms | — |
| custom fn (`mapParallel` / `map`) | 5.99 ms | 0.42 ms | 14.2× |

MatD faster 8/9 scored, geometric mean **6.11× faster** than Breeze.
`matmul` is tied (both use OpenBLAS via JNIBLAS); `add` is the lone Breeze win (0.75×).

# MatD Cheat Sheet

Side-by-side reference for **uni.MatD**, NumPy, R, and MATLAB. Breeze users: the
comparison and migration table are in [BreezeComparison.md](BreezeComparison.md).

`MatD` = `Mat[Double]` — the standard double-precision matrix type in [uni.Mat](../README.md).
Benchmarks (NumPy | Scala | Rust, three operating systems, and how to regenerate them) are in
[MatDBenchmarks.md](MatDBenchmarks.md).

---

## Setup / Import

| | Code |
|---|---|
| **MatD** | `import uni.data.*` |
| **NumPy** | `import numpy as np` |
| **R** | *(built-in)* |
| **MATLAB** | *(built-in)* |

netlib's BLAS-backend announcement (`INFO: Using dev.ludovic.netlib.blas.JNIBLAS` on
stderr at first MatD use) is suppressed by default; run with `-Duni.blas.verbose=true`
to see it again, or enable uni's generic verbosity mode with `-Duni.verbose=true` or
the `VERBOSE_UNI` env var (for scala-cli: `--java-opt -Duni.blas.verbose=true`).

---

## Matrix Creation

| Operation | MatD | NumPy | R | MATLAB |
|---|---|---|---|---|
| All zeros | `MatD.zeros(r, c)` | `np.zeros((r, c))` | `matrix(0, r, c)` | `zeros(r, c)` |
| All ones | `MatD.ones(r, c)` | `np.ones((r, c))` | `matrix(1, r, c)` | `ones(r, c)` |
| Identity | `MatD.eye(n)` | `np.eye(n)` | `diag(n)` | `eye(n)` |
| From flat array | `MatD(r, c, arr)` | `np.array(lst).reshape(r, c)` | `matrix(v, r, c)` | `reshape(v, r, c)` |
| From 2-D array | `arr2d.toMat`<br>`MatD(arr2d)` | `np.array(lst2d)` | `do.call(rbind, l)` | `cell2mat(c)` |
| From rows (`Seq[Array]`) | `rows.toMat`<br>`MatD.fromRows(rows)` | `np.array(list(rows))` | `do.call(rbind, l)` | `cell2mat(c)` |
| From rows (tuples) | `MatD((1,2),(3,4))` | `np.array([[1,2],[3,4]])` | `rbind(c(1,2),c(3,4))` | `[1 2; 3 4]` |
| Column vector | `MatD(1.0, 2.0, 3.0)` | `np.array([[1],[2],[3]])` | `matrix(1:3)` | `[1; 2; 3]` |
| Column from array | `arr.toCVec`<br>`MatD(arr)` | `np.array(lst)[:,None]` | `matrix(v)` | `v(:)` |
| Row vector | `MatD.row(1, 2, 3)` | `np.array([[1, 2, 3]])` | `t(matrix(1:3))` | `[1 2 3]` |
| Row from array | `arr.toRVec` | `np.array(lst)[None,:]` | `t(matrix(v))` | `v(:).'` |
| Wrap array, no copy | `Mat.wrap(arr, r, c)` | `np.frombuffer(...)` | — | — |
| From function | `MatD.tabulate(r,c)((i,j) => f(i,j))` | `np.fromfunction(f, (r,c))` | `outer(1:r, 1:c, f)` | `arrayfun(f, I, J)` |
| Diagonal matrix | `MatD.diag(vec)` | `np.diag(v)` | `diag(v)` | `diag(v)` |
| Zeros like | `MatD.zerosLike(m)` | `np.zeros_like(m)` | `matrix(0, nrow(m), ncol(m))` | `zeros(size(m))` |
| Ones like | `MatD.onesLike(m)` | `np.ones_like(m)` | `matrix(1, nrow(m), ncol(m))` | `ones(size(m))` |
| Fill like | `MatD.fullLike(m, v)` | `np.full_like(m, v)` | `matrix(v, nrow(m), ncol(m))` | `repmat(v, size(m))` |

> **Since v0.14.1:** flat varargs `MatD(1.0, 2.0, 3.0)` (also `MatB`, `MatF`) build a **column**
> vector, matching `Mat(…)` and `CVec(…)` — previously they built a row.
> Use `MatD.row(…)` for an explicit row vector.
> Integer arguments are **values**, as in NumPy — `MatD(1, 2, 3)` is a 3×1 column of
> `1.0, 2.0, 3.0`, just like `np.array([1, 2, 3])`. This holds at every arity.
>
> **Changed in v0.15.0:** `MatD(rows, cols)` — exactly two Ints — used to be a `(rows, cols)`
> zero-matrix constructor, the one place where Ints meant dimensions instead of values.
> It no longer compiles. Use `MatD.zeros(3, 4)` for a zero matrix, `MatD.empty` for 0×0, or
> `MatD(3.0, 4.0)` for a 2×1 column of values. NumPy separates the two the same way —
> `np.array([1, 2])` vs `np.zeros((3, 4))` — by name rather than by argument type.
> The overload is retained as a compile-time tombstone, so the old spelling gives an error
> naming the replacements rather than silently becoming a 2×1 column.

### Converting arrays

Every conversion below **copies**, so the result shares no storage with the source array.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.23.0

import uni.data.*

val arr   = Array(1.0, 2.0, 3.0)
val arr2d = Array(Array(1.0, 2.0, 3.0), Array(4.0, 5.0, 6.0))

arr.toCVec        // 3×1 column      arr.toRVec   // 1×3 row
arr.toMat         // 3×1 (column is the default)
arr2d.toMat       // 2×3, row-major

val rowA = Array(1.0, 2.0); val rowB = Array(3.0, 4.0)
Vector(rowA, rowB).toMat        // Seq[Array[T]] works too
List(rowA, rowB).toMat
println(s"${arr.toCVec.shape} ${arr.toRVec.shape} ${arr.toMat.shape} ${arr2d.toMat.shape} ${List(rowA, rowB).toMat.shape}")
// (3,1) (1,3) (3,1) (2,3) (2,2)
```

The same shapes are reachable through the companions, so whichever spelling you
reach for first works:

| you write | you get |
|---|---|
| `arr.toCVec`, `CVec(arr)`, `MatD(arr)`, `Mat(arr)` | n×1 column |
| `arr.toRVec`, `RVec(arr)` | 1×n row |
| `arr2d.toMat`, `MatD(arr2d)`, `Mat(arr2d)`, `MatD.fromRows(arr2d)` | row-major matrix |
| `rows.toMat`, `MatD.fromRows(rows)` | row-major matrix from `Seq[Array[T]]` |

2-D input is **row-major**: `arr2d.toMat(0, 1) == 2.0` and `arr2d.toMat(1, 0) == 4.0`.
Ragged input raises `IllegalArgumentException` naming the offending row; empty input gives 0×0.

> **Copy vs alias.** `Mat` is mutable — `m(r, c) = v` writes into the backing array — so the
> conversions above copy to keep the matrix and the source array independent.
> Two entry points do **not** copy and alias the array you hand them:
> `Mat.create(arr, r, c)` and `MatD(r, c, arr)`. After either, writing to the matrix changes
> your array and vice versa. When you want that, prefer `Mat.wrap(arr, r, c)` — same behaviour,
> but the intent is visible at the call site.

> **Before v0.15.0**, `CVec(arr)` bound the varargs overload with `T := Array[Double]` and produced
> a **1×1 holding the array object** rather than a 3×1 column; `Mat(arr)` did the same via the
> scalar lift. Both now do the obvious thing. The scalar lift `Mat(42.0)` → 1×1 is unchanged.

---

## Random Generation

| Operation | MatD | NumPy | R | MATLAB |
|---|---|---|---|---|
| Set seed | `MatD.setSeed(42)` | `np.random.seed(42)` | `set.seed(42)` | `rng(42)` |
| Uniform [0,1) | `MatD.rand(r, c)` | `np.random.rand(r, c)` | `matrix(runif(r*c), r, c)` | `rand(r, c)` |
| Standard normal | `MatD.randn(r, c)` | `np.random.randn(r, c)` | `matrix(rnorm(r*c), r, c)` | `randn(r, c)` |
| Uniform [lo,hi) | `MatD.uniform(lo, hi, r, c)` | `np.random.uniform(lo, hi, (r,c))` | `matrix(runif(r*c,lo,hi),r,c)` | `lo+(hi-lo)*rand(r,c)` |
| Normal(μ,σ) | `MatD.normal(mu, sd, r, c)` | `np.random.normal(mu, sd, (r,c))` | `matrix(rnorm(r*c,mu,sd),r,c)` | `mu+sd*randn(r,c)` |
| Random ints | `MatD.randint(lo, hi, r, c)` | `np.random.randint(lo, hi, (r,c))` | `matrix(sample(lo:hi,r*c,T),r,c)` | `randi([lo hi-1],r,c)` |

> **Note:** `MatD.setSeed` / `MatD.rand` / `MatD.randn` / `MatD.uniform` use a 100% faithful PCG64 implementation matching NumPy's output bit-for-bit.

---

## Shape and Info

| Operation | MatD | NumPy | R | MATLAB |
|---|---|---|---|---|
| Dimensions | `m.shape` | `m.shape` | `dim(m)` | `size(m)` |
| Row count | `m.rows`<br>`m.shape._1` | `m.shape[0]` | `nrow(m)` | `size(m,1)` |
| Col count | `m.cols`<br>`m.shape._2` | `m.shape[1]` | `ncol(m)` | `size(m,2)` |
| Total elements | `m.size` | `m.size` | `length(m)` | `numel(m)` |
| Reshape | `m.reshape(r, c)` | `m.reshape(r, c)` | `matrix(m, r, c)` | `reshape(m, r, c)` |
| Flatten to array | `m.flatten` | `m.flatten()` | `as.vector(m)` | `m(:)` |
| Flatten to row vec | `m.ravel` | `m.ravel()` | — | — |
| Extract scalar | `m.item`<br>`m(0, 0)` | `m.item()` | `m[1,1]` | `m(1,1)` |

---

## Indexing and Slicing

| Operation | MatD | NumPy | R | MATLAB |
|---|---|---|---|---|
| Single element | `m(i, j)` | `m[i, j]` | `m[i+1, j+1]` | `m(i+1, j+1)` |
| Row slice (copy) | `m(i, ::)` | `m[i, :]` | `m[i+1,]` | `m(i+1,:)` |
| Column slice (copy) | `m(::, j)` | `m[:, j]` | `m[,j+1]` | `m(:,j+1)` |
| Zero-copy view | `m.slice(rows, cols)` | `m[r0:r1, c0:c1]` | — | — |
| Transpose (O(1)) | `m.T` or `m.transpose` | `m.T` | `t(m)` | `m'` |

> Indexing is **zero-based** with negative-index support. Unlike NumPy, the `m(...)` slice
> forms return independent **copies**; `m.slice(rows, cols)` (and `.T`) are zero-copy views
> that share storage with the parent.

---

## Column / Row Mapping

| Operation | MatD | NumPy | R | MATLAB |
|---|---|---|---|---|
| Map each column | `m.eachCol.map(f)`<br>`m(::, *).map(f)`<br>`m.mapCols(f)` | `np.apply_along_axis(f, 0, m)` | `apply(m, 2, f)` | — |
| Map each row | `m.eachRow.map(f)`<br>`m(*, ::).map(f)`<br>`m.mapRows(f)` | `np.apply_along_axis(f, 1, m)` | `apply(m, 1, f)` | — |

`f` receives a `ColVec[T]` (n×1) for column mapping, a `RowVec[T]` (1×n) for row mapping, and must return the same shape. All three MatD spellings per row are equivalent.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.23.0

import uni.data.*

val m = MatD((3.0, 1.0, 2.0), (6.0, 5.0, 4.0))

// Sort each column independently (the broadcast sentinel `*`)
val sortedA = m(::, *).map(col => col.sort())

// Sort each column independently (named method — no `*` to collide with another library)
val sortedB = m.eachCol.map(col => col.sort())

// Reverse each row
val reversed = m.eachRow.map(row => row(::, row.cols-1 to 0 by -1))
println(sortedA); println(sortedB); println(reversed)
```

> **Note:** For broadcasting operations (subtract column means, divide by std) use arithmetic directly — `m - m.mean(axis=0)` is both simpler and faster.

---

## Element-wise Arithmetic

| Operation | MatD | NumPy | R | MATLAB |
|---|---|---|---|---|
| Add matrices | `a + b` | `a + b` | `a + b` | `a + b` |
| Subtract | `a - b` | `a - b` | `a - b` | `a - b` |
| Multiply (Hadamard) | `a * b` | `a * b` | `a * b` | `a .* b` |
| Divide | `a / b` | `a / b` | `a / b` | `a ./ b` |
| Add scalar | `a + 2.0` | `a + 2.0` | `a + 2` | `a + 2` |
| Multiply scalar | `a * 3.0` | `a * 3.0` | `a * 3` | `a * 3` |

All four operators work with `CVecD`/`RVecD` operands too, elementwise, and keep the
vector type: `vecA * vecB`, `vecA / vecB`, and `cvec op mat` (a Mat-typed vector
expression such as `eq - eq.cummax(0)`) all return the vector type of the left operand.

Vector idioms for two NumPy 1-D forms:

| NumPy (1-D `v`) | MatD | Notes |
|---|---|---|
| `v[:k]` | `v(0 until k, 0)` (CVec), `r(0, 0 until k)` (RVec) | returns `CVecD` or `RVecD` |
| `np.maximum.accumulate(v)` | `v.cummax(0)` (CVec), `r.cummax(1)` (RVec) | `cummin` likewise |
| max drawdown | `1.0 - (eq - eq.cummax(0)).exp.min` | log-equity curve `eq: CVecD` |

---

## Matrix Multiplication

| Operation | MatD | NumPy | R | MATLAB |
|---|---|---|---|---|
| Matrix multiply | `a *@ b` | `a @ b` | `a %*% b` | `a * b` |
| Mat × col-vec | `X *@ y` where `y: CVecD` → `CVecD` | `X @ y` | `X %*% y` | `X * y` |
| row-vec × Mat | `r *@ X` where `r: RVecD` → `RVecD` | `r @ X` | `r %*% X` | `r * X` |
| dot product | `y *@ y` (CVec auto-transposes) → `Double` | `y @ y` | `t(y) %*% y` | `y' * y` |
| outer product | `y *@ y.T` (CVec × RVec) → `MatD` | `np.outer(y, y)` | `y %o% y` | `y * y'` |

---

## In-place Operations

| Operation | MatD | NumPy | R | MATLAB |
|---|---|---|---|---|
| Add scalar in-place | `m :+= 2.0` | `m += 2.0` | `m[] <- m + 2` | `m = m + 2` |
| Subtract scalar | `m :-= 1.0` | `m -= 1.0` | `m[] <- m - 1` | `m = m - 1` |
| Multiply scalar | `m :*= 3.0` | `m *= 3.0` | `m[] <- m * 3` | `m = m * 3` |
| Divide scalar | `m :/= 2.0` | `m /= 2.0` | `m[] <- m / 2` | `m = m / 2` |
| Add matrix in-place | `m :+= n` | `m += n` | `m[] <- m + n` | `m = m + n` |

---

## Reductions

| Operation | MatD | NumPy | R | MATLAB |
|---|---|---|---|---|
| Sum all | `m.sum` | `m.sum()` | `sum(m)` | `sum(m(:))` |
| Mean all | `m.mean` | `m.mean()` | `mean(m)` | `mean(m(:))` |
| Min all | `m.min` | `m.min()` | `min(m)` | `min(m(:))` |
| Max all | `m.max` | `m.max()` | `max(m)` | `max(m(:))` |
| Std dev all | `m.std` | `m.std()` | `sd(m)` | `std(m(:))` |
| Variance all | `m.variance` | `m.var()` | `var(c(m))` | `var(m(:))` |
| Median all | `m.median` | `np.median(m)` | `median(m)` | `median(m(:))` |
| Per column | `m.sum(axis=0)` → 1×cols | `m.sum(axis=0)` | `colSums(m)` | `sum(m, 1)` |
| Per row | `m.sum(axis=1)` → rows×1 | `m.sum(axis=1)` | `rowSums(m)` | `sum(m, 2)` |

---

## Element-wise Math

| Operation | MatD | NumPy | R | MATLAB |
|---|---|---|---|---|
| Absolute value | `m.map(_.abs)` | `np.abs(m)` | `abs(m)` | `abs(m)` |
| Square root | `m.map(math.sqrt)` | `np.sqrt(m)` | `sqrt(m)` | `sqrt(m)` |
| Exponential | `m.map(math.exp)` | `np.exp(m)` | `exp(m)` | `exp(m)` |
| Log | `m.map(math.log)` | `np.log(m)` | `log(m)` | `log(m)` |
| Power (scalar) | `m ~^ p` | `m ** p` | `m ^ p` | `m .^ p` |
| Map arbitrary fn | `m.map(f)` | `np.vectorize(f)(m)` | `apply(m, c(1,2), f)` | `arrayfun(f, m)` |

---

## Activation Functions

| Operation | MatD | NumPy / SciPy | R | MATLAB |
|---|---|---|---|---|
| ReLU | `m.relu` | `np.maximum(m, 0)` | `pmax(m, 0)` | `max(m, 0)` |
| Sigmoid | `m.sigmoid` | `scipy.special.expit(m)` | `1/(1+exp(-m))` | `1./(1+exp(-m))` |
| Softmax | `m.softmax` | `scipy.special.softmax(m)` | `exp(m)/sum(exp(m))` | `exp(m)./sum(exp(m))` |
| Leaky ReLU | `m.leakyRelu` | `np.where(m>0, m, 0.01*m)` | `ifelse(m>0, m, 0.01*m)` | `max(0.01*m, m)` |

---

## Boolean / Masking

| Operation | MatD | NumPy | R | MATLAB |
|---|---|---|---|---|
| Element comparison | `m :== 0.0` → `Mat[Boolean]` | `m == 0` | `m == 0` | `m == 0` |
| Not equal | `m :!= 0.0` | `m != 0` | `m != 0` | `m ~= 0` |
| Greater / less | `m > 2.0`, `m < 2.0` (or `gt`, `lt`) | `m > 2`, `m < 2` | `m > 2`, `m < 2` | `m > 2`, `m < 2` |
| Greater/less or equal | `m >= 2.0`, `m <= 2.0` (or `gte`, `lte`) | `m >= 2`, `m <= 2` | `m >= 2`, `m <= 2` | `m >= 2`, `m <= 2` |
| In range | `m.between(lo, hi)` | `(m >= lo) & (m <= hi)` | `m >= lo & m <= hi` | `m >= lo & m <= hi` |
| Combine masks | `a && b`, `a \|\| b` | `a & b`, `a \| b` | `a & b`, `a \| b` | `a & b`, `a \| b` |
| Negate mask | `!mask` | `~mask` | `!mask` | `~mask` |
| Count true | `mask.sum` | `mask.sum()` | `sum(mask)` | `sum(mask(:))` |
| Any true | `mask.any` | `mask.any()` | `any(mask)` | `any(mask(:))` |
| All true | `mask.all` | `mask.all()` | `all(mask)` | `all(mask(:))` |
| Conditional select | `Mat.where(cond, x, y)` | `np.where(cond, x, y)` | `ifelse(cond, x, y)` | `cond.*x + ~cond.*y` |

---

## Stacking and Splitting

| Operation | MatD | NumPy | R | MATLAB |
|---|---|---|---|---|
| Stack rows | `MatD.vstack(a, b)` | `np.vstack([a, b])` | `rbind(a, b)` | `[a; b]` |
| Stack cols | `MatD.hstack(a, b)` | `np.hstack([a, b])` | `cbind(a, b)` | `[a, b]` |
| Split rows | `m.vsplit(n)` | `np.vsplit(m, n)` | — | `mat2cell(m, repmat(r/n,1,n), c)` |
| Split cols | `m.hsplit(n)` | `np.hsplit(m, n)` | — | `mat2cell(m, r, repmat(c/n,1,n))` |

---

## Signal Processing (1-D Vectors)

| Operation | MatD | NumPy | R | MATLAB |
|---|---|---|---|---|
| Polynomial fit | `MatD.polyfit(x, y, deg)` | `np.polyfit(x, y, deg)` | `lm(y ~ poly(x, deg))` | `polyfit(x, y, deg)` |
| Polynomial eval | `MatD.polyval(coeffs, x)` | `np.polyval(coeffs, x)` | `predict(fit, ...)` | `polyval(coeffs, x)` |
| Convolve | `MatD.convolve(a, b)` | `np.convolve(a, b)` | `convolve(a, b)` | `conv(a, b)` |
| Correlate | `MatD.correlate(a, b)` | `np.correlate(a, b)` | `ccf(a, b)` | `xcorr(a, b)` |
| Meshgrid | `MatD.meshgrid(x, y)` | `np.meshgrid(x, y)` | `expand.grid(x, y)` | `meshgrid(x, y)` |

---

## Display and Formatting

| Operation | MatD | NumPy | R | MATLAB |
|---|---|---|---|---|
| Print default | `println(m)` | `print(m)` | `print(m)` | `disp(m)` |
| Explicit show | `m.show` | `str(m)` | `print(m)` | `disp(m)` |
| Custom format | `m.show("%.2f")` | `np.set_printoptions(...)` | `format(m, digits=2)` | `format short` |
| Set thresholds | `Mat.setPrintOptions(maxRows=20, maxCols=20, edgeItems=5)` | `np.set_printoptions(threshold=...)` | `options(max.print=...)` | `format compact` |

> **`show` returns the rendering, it does not print it** — like NumPy's `str(m)`, and unlike
> `disp`. `m.show` alone on a line discards the result and outputs nothing; write
> `println(m.show)`.
>
> The two renderings also differ: `show` is compact (`(1, 2)`) where `toString` pads to a
> fixed width (`(1.00000, 2.00000)`). Use `show` for eyeballing, interpolation for a stable
> shape.
>
> **`println(m)` and the CR-free output convention conflict.** Scripts that shadow `println`
> to avoid carriage returns —
> `def println(s: String = ""): Unit = print(s"$s\n")` — give it a `String`-only signature,
> so `println(m)` no longer compiles for a matrix. Use `println(m.show)` or
> `println(s"$m")`; both reach the shadowed `String` overload.

---

## Pandas-Style Data Analysis

| Operation | MatD | pandas | Notes |
|---|---|---|---|
| First n rows | `m.head(n)` | `df.head(n)` | Returns `Mat[T]` |
| Last n rows | `m.tail(n)` | `df.tail(n)` | Returns `Mat[T]` |
| Index of min per axis | `m.idxmin(axis)` | `df.idxmin(axis)` | Returns `Mat[Int]` |
| Index of max per axis | `m.idxmax(axis)` | `df.idxmax(axis)` | Returns `Mat[Int]` |
| Cumulative max | `m.cummax(axis)` | `df.cummax(axis)` | axis=0 (rows) or 1 (cols) |
| Cumulative min | `m.cummin(axis)` | `df.cummin(axis)` | axis=0 (rows) or 1 (cols) |
| N largest values | `m.nlargest(n)` | `df.nlargest(n, col)` | Returns 1×n row vector |
| N smallest values | `m.nsmallest(n)` | `df.nsmallest(n, col)` | Returns 1×n row vector |
| Range test | `m.between(lo, hi)` | `s.between(lo, hi)` | Returns `Mat[Boolean]` |
| Unique value count | `m.nunique` | `df.nunique()` | `Int` |
| Frequency table | `m.valueCounts` | `s.value_counts()` | `Array[(T, Int)]`, descending |
| Shift / lag | `m.shift(n, fill)` | `df.shift(n)` | fill: sentinel for new cells |
| Shift columns | `m.shift(n, fill, axis=1)` | `df.shift(n, axis=1)` | |
| Percent change | `m.pct_change()` | `df.pct_change()` | First row is NaN |
| Fill NaN | `m.fillna(v)` | `df.fillna(v)` | Replaces NaN/BigNaN |
| Descriptive stats | `m.describe` | `df.describe()` | Returns `(Array[String], Mat[Double])` — 8 rows |
| Rolling mean | `m.rolling(w).mean` | `df.rolling(w).mean()` | First w-1 rows are NaN |
| Rolling sum | `m.rolling(w).sum` | `df.rolling(w).sum()` | |
| Rolling min | `m.rolling(w).min` | `df.rolling(w).min()` | |
| Rolling max | `m.rolling(w).max` | `df.rolling(w).max()` | |
| Rolling std dev | `m.rolling(w).std` | `df.rolling(w).std()` | |
| Column by name | `result("Col")` | `df["Col"]` | From `MatResult`; throws if absent |
| Column option | `result.col("Col")` | `df.get("Col")` | `Option[ColVec[T]]` |

### describe output layout

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.23.0

import uni.data.*

val m = MatD((1,2),(3,4),(5,6))
val (labels, stats) = m.describe
// labels = Array("count","mean","std","min","25%","50%","75%","max")
// stats  = 8 × m.cols  Mat[Double]
```

### MatResult — CSV with named columns

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.23.0

import uni.*
import uni.io.FileOps.*

val csv = "data.csv".asPath                       // any CSV with a header row
csv.write("Close,Volume\n101.5,1200\n102.25,900\n")

val r = loadSmart(csv)                            // MatResult[Big]  (auto-detects header)
val r2 = loadSmart(csv, _.toDouble)               // MatResult[Double]

r("Close")          // ColVec[Big]   — throws NoSuchElementException if absent
r.col("Volume")     // Option[ColVec[Big]]
r.columnIndex       // Map[String, Int]  (pre-computed; free repeated lookups)
println(s"${r2("Close").sum} ${r.columnIndex}")  // 203.75 Map(Close -> 0, Volume -> 1)
csv.delete()
```

---

## Quick Reference Card

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.23.0

import uni.data.*

// Create
val a = MatD.zeros(3, 4)
val b = MatD.randn(3, 4)
val c = MatD.eye(3)

// Seed + random (100% NumPy-compatible)
MatD.setSeed(42)
val w = MatD.uniform(-0.1, 0.1, 64, 32)

// Slice (zero-indexed; m(...) forms copy, m.slice/.T are zero-copy views)
val row0 = b(0, ::)   // first row (copy)
val col0 = b(::, 0)   // first column (copy)
val bT   = b.T        // transpose (O(1) view)

// Arithmetic
val d = a + b         // element-wise
val e = a * b         // Hadamard product
val f = c *@ b        // matmul

// In-place
b :*= 0.5
b :+= a

// Activations
val g = f.sigmoid
val h = f.relu

// Reductions
val s = b.sum
val u = b.mean

// Boolean
val mask = (b :== 0.0) || (b :== 1.0)
val inv  = !mask
val hits = mask.sum

// Conditional
val r = Mat.where(mask, 1.0, 0.0)

// Stack / split
val tall = MatD.vstack(a, b)
val halves = tall.vsplit(2)   // Seq[MatD]

// Display
println(b.show("%.4f"))
Mat.setPrintOptions(maxRows = 20, maxCols = 20, edgeItems = 5)
```

---

## Type Aliases Summary

| Alias | Full Type | Factory Object | Notes |
|---|---|---|---|
| `MatD` | `Mat[Double]` | `MatD` | Default, IEEE 754 double |
| `MatF` | `Mat[Float]` | `MatF` | Half the memory of MatD |
| `MatB` | `Mat[Big]` | `MatB` | Arbitrary precision, NaN-safe |
| `CVecD` | `CVec[Double]` | `CVec` | Column vector (n×1); opaque type |
| `RVecD` | `RVec[Double]` | `RVec` | Row vector (1×n); opaque type |

`MatD`, `MatF`, and `MatB` share the same API; factory objects mirror `Mat` methods with appropriate types.
`CVec` / `RVec` provide type-safe `*@` dispatch: `X *@ y` returns `CVecD`, `y.T *@ X` returns `RVecD`, and `y *@ y` returns `Double`.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.23.0

import uni.data.*

val y: CVecD = CVec(1.0, 2.0, 3.0)
val r: RVecD = RVec(4.0, 5.0, 6.0)
val z  = CVec.zeros[Double](5)
val o  = RVec.ones[Double](5)
val s: Double = (y *@ y)           // dot product = 14.0

// from an existing array (copies)
val arr = Array(1.0, 2.0, 3.0)
val c: CVecD = arr.toCVec          // or CVec(arr) / CVec.fromArray(arr)
val w: RVecD = arr.toRVec          // or RVec(arr) / RVec.fromArray(arr)
println(s"$s ${r.shape} ${z.shape} ${o.shape} ${c.shape} ${w.shape}")   // 14.0 (1,3) (5,1) (1,5) (3,1) (1,3)
```

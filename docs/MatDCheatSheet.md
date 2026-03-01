# MatD Cheat Sheet

Side-by-side reference for **uni.MatD**, NumPy, Breeze, R, and MATLAB.

`MatD` = `Mat[Double]` — the standard double-precision matrix type in [uni.Mat](../README.md).

---

## Performance vs NumPy

Measured on the same machine: NumPy 2.4.1 / Python 3.14.3 vs uni.MatD 0.9.2 / Scala 3.7.0 / JVM 17.
Both use OpenBLAS. See [`jsrc/bench.sc`](../jsrc/bench.sc) and [`py/bench.py`](../py/bench.py) to reproduce.

| Operation | NumPy | MatD | Ratio | Notes |
|---|---:|---:|---|---|
| `randn(1000×1000)` | 19 ms | 21 ms | **≈ tied** | PCG64 with Long arithmetic; was 252 ms before BigInt rewrite |
| `matmul 512×512` | 1.7 ms | 3.3 ms | 1.9× slower | Both use OpenBLAS; gap is JNI call overhead |
| `sigmoid(1000×1000)` | 12.6 ms | 3.0 ms | **4.2× faster** | Parallel fork/join beats single-core SIMD |
| `relu(1000×1000)` | 2.0 ms | 0.8 ms | **2.5× faster** | Parallel fork/join beats single-core SIMD |
| `add(1000×1000)` | 2.3 ms | 1.7 ms | **1.4× faster** | Parallel fork/join beats single-core SIMD |
| `sum(1000×1000)` | 0.3 ms | 0.5 ms | 1.6× slower | NumPy SIMD reduction hard to beat |
| `transpose(1000×1000)` | ≈0 ms | ≈0 ms | **tied** | O(1) stride-flip in both — no data copy |
| `mapParallel` custom fn | 440 ms | 0.9 ms | **470× faster** | `np.vectorize` is a Python loop; JVM is compiled |

**Practical guidance:**
- Element-wise ops (`relu`, `sigmoid`, `add`) now run faster than NumPy — parallel JVM cores beat single-core C SIMD.
- Custom scalar functions: `mapParallel` vs `np.vectorize` shows a 470× JVM advantage; the Python interpreter overhead dominates.
- Matmul: NumPy still wins (~2×) due to OpenBLAS JNI overhead on the JVM side.
- `sum`: NumPy's vectorised C reduction is hard to beat; MatD is within 2×.

---

## Setup / Import

| | Code |
|---|---|
| **MatD** | `import uni.data.*` |
| **NumPy** | `import numpy as np` |
| **Breeze** | `import breeze.linalg.*` |
| **R** | *(built-in)* |
| **MATLAB** | *(built-in)* |

---

## Matrix Creation

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| All zeros | `MatD.zeros(r, c)` | `np.zeros((r, c))` | `DenseMatrix.zeros[Double](r, c)` | `matrix(0, r, c)` | `zeros(r, c)` |
| All ones | `MatD.ones(r, c)` | `np.ones((r, c))` | `DenseMatrix.ones[Double](r, c)` | `matrix(1, r, c)` | `ones(r, c)` |
| Identity | `MatD.eye(n)` | `np.eye(n)` | `DenseMatrix.eye[Double](n)` | `diag(n)` | `eye(n)` |
| From array | `MatD.from(arr, r, c)` | `np.array(lst).reshape(r, c)` | `new DenseMatrix(r, c, arr)` | `matrix(v, r, c)` | `reshape(v, r, c)` |
| From function | `MatD.tabulate(r,c)((i,j) => f(i,j))` | `np.fromfunction(f, (r,c))` | `DenseMatrix.tabulate(r,c)(f)` | `outer(1:r, 1:c, f)` | `arrayfun(f, I, J)` |
| Diagonal matrix | `MatD.diag(vec)` | `np.diag(v)` | `diag(v)` | `diag(v)` | `diag(v)` |
| Zeros like | `MatD.zerosLike(m)` | `np.zeros_like(m)` | `DenseMatrix.zeros[Double](m.rows, m.cols)` | `matrix(0, nrow(m), ncol(m))` | `zeros(size(m))` |
| Ones like | `MatD.onesLike(m)` | `np.ones_like(m)` | `DenseMatrix.ones[Double](m.rows, m.cols)` | `matrix(1, nrow(m), ncol(m))` | `ones(size(m))` |
| Fill like | `MatD.fullLike(m, v)` | `np.full_like(m, v)` | `DenseMatrix.fill(m.rows, m.cols)(v)` | `matrix(v, nrow(m), ncol(m))` | `repmat(v, size(m))` |

---

## Random Generation

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Set seed | `MatD.setSeed(42)` | `np.random.seed(42)` | `RandBasis.withSeed(42)` | `set.seed(42)` | `rng(42)` |
| Uniform [0,1) | `MatD.rand(r, c)` | `np.random.rand(r, c)` | `DenseMatrix.rand(r, c)` | `matrix(runif(r*c), r, c)` | `rand(r, c)` |
| Standard normal | `MatD.randn(r, c)` | `np.random.randn(r, c)` | `DenseMatrix.randn(r, c)` | `matrix(rnorm(r*c), r, c)` | `randn(r, c)` |
| Uniform [lo,hi) | `MatD.uniform(lo, hi, r, c)` | `np.random.uniform(lo, hi, (r,c))` | `DenseMatrix.rand(r,c,Uniform(lo,hi))` | `matrix(runif(r*c,lo,hi),r,c)` | `lo+(hi-lo)*rand(r,c)` |
| Normal(μ,σ) | `MatD.normal(mu, sd, r, c)` | `np.random.normal(mu, sd, (r,c))` | `DenseMatrix.rand(r,c,Gaussian(mu,sd))` | `matrix(rnorm(r*c,mu,sd),r,c)` | `mu+sd*randn(r,c)` |
| Random ints | `MatD.randint(lo, hi, r, c)` | `np.random.randint(lo, hi, (r,c))` | `DenseMatrix.rand(r,c,...).map(_.toInt)` | `matrix(sample(lo:hi,r*c,T),r,c)` | `randi([lo hi-1],r,c)` |

> **Note:** `MatD.setSeed` / `MatD.rand` / `MatD.randn` / `MatD.uniform` use a 100% faithful PCG64 implementation matching NumPy's output bit-for-bit.

---

## Shape and Info

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Dimensions | `m.shape` → `(r, c)` | `m.shape` | `(m.rows, m.cols)` | `dim(m)` | `size(m)` |
| Row count | `m.rows` | `m.shape[0]` | `m.rows` | `nrow(m)` | `size(m,1)` |
| Col count | `m.cols` | `m.shape[1]` | `m.cols` | `ncol(m)` | `size(m,2)` |
| Total elements | `m.rows * m.cols` | `m.size` | `m.size` | `length(m)` | `numel(m)` |

---

## Indexing and Slicing

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Single element | `m(i, j)` | `m[i, j]` | `m(i, j)` | `m[i+1, j+1]` | `m(i+1, j+1)` |
| Row slice (view) | `m(i, ::)` | `m[i, :]` | `m(i, ::)` | `m[i+1,]` | `m(i+1,:)` |
| Column slice (view) | `m(::, j)` | `m[:, j]` | `m(::, j)` | `m[,j+1]` | `m(:,j+1)` |
| Transpose (O(1)) | `m.T` or `m.transpose` | `m.T` | `m.t` | `t(m)` | `m'` |

> MatD slices are **zero-indexed views** with O(1) cost (no data copy), matching NumPy's strided semantics.

---

## Element-wise Arithmetic

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Add matrices | `a + b` | `a + b` | `a + b` | `a + b` | `a + b` |
| Subtract | `a - b` | `a - b` | `a - b` | `a - b` | `a - b` |
| Multiply (Hadamard) | `a * b` | `a * b` | `a :* b` | `a * b` | `a .* b` |
| Divide | `a / b` | `a / b` | `a :/ b` | `a / b` | `a ./ b` |
| Add scalar | `a + 2.0` | `a + 2.0` | `a + 2.0` | `a + 2` | `a + 2` |
| Multiply scalar | `a * 3.0` | `a * 3.0` | `a * 3.0` | `a * 3` | `a * 3` |

---

## Matrix Multiplication

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Matrix multiply | `a ~@ b` | `a @ b` | `a * b` | `a %*% b` | `a * b` |

---

## In-place Operations

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Add scalar in-place | `m :+= 2.0` | `m += 2.0` | `m :+= 2.0` | `m[] <- m + 2` | `m = m + 2` |
| Subtract scalar | `m :-= 1.0` | `m -= 1.0` | `m :-= 1.0` | `m[] <- m - 1` | `m = m - 1` |
| Multiply scalar | `m :*= 3.0` | `m *= 3.0` | `m :*= 3.0` | `m[] <- m * 3` | `m = m * 3` |
| Divide scalar | `m :/= 2.0` | `m /= 2.0` | `m :/= 2.0` | `m[] <- m / 2` | `m = m / 2` |
| Add matrix in-place | `m :+= n` | `m += n` | `m :+= n` | `m[] <- m + n` | `m = m + n` |

---

## Reductions

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Sum all | `m.sum` | `m.sum()` | `sum(m)` | `sum(m)` | `sum(m(:))` |
| Mean all | `m.mean` | `m.mean()` | `mean(m)` | `mean(m)` | `mean(m(:))` |
| Min all | `m.min` | `m.min()` | `min(m)` | `min(m)` | `min(m(:))` |
| Max all | `m.max` | `m.max()` | `max(m)` | `max(m)` | `max(m(:))` |

---

## Element-wise Math

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Absolute value | `m.map(_.abs)` | `np.abs(m)` | `abs(m)` | `abs(m)` | `abs(m)` |
| Square root | `m.map(math.sqrt)` | `np.sqrt(m)` | `sqrt(m)` | `sqrt(m)` | `sqrt(m)` |
| Exponential | `m.map(math.exp)` | `np.exp(m)` | `exp(m)` | `exp(m)` | `exp(m)` |
| Log | `m.map(math.log)` | `np.log(m)` | `log(m)` | `log(m)` | `log(m)` |
| Power (scalar) | `m.map(math.pow(_, p))` | `m ** p` | `m ^:^ p` | `m ^ p` | `m .^ p` |
| Map arbitrary fn | `m.map(f)` | `np.vectorize(f)(m)` | `m.map(f)` | `apply(m, c(1,2), f)` | `arrayfun(f, m)` |

---

## Activation Functions

| Operation | MatD | NumPy / SciPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| ReLU | `m.relu` | `np.maximum(m, 0)` | `max(m, 0.0)` | `pmax(m, 0)` | `max(m, 0)` |
| Sigmoid | `m.sigmoid` | `scipy.special.expit(m)` | `sigmoid(m)` | `1/(1+exp(-m))` | `1./(1+exp(-m))` |
| Softmax | `m.softmax` | `scipy.special.softmax(m)` | `softmax(m)` | `exp(m)/sum(exp(m))` | `exp(m)./sum(exp(m))` |
| Leaky ReLU | `m.leakyRelu` | `np.where(m>0, m, 0.01*m)` | — | `ifelse(m>0, m, 0.01*m)` | `max(0.01*m, m)` |

---

## Boolean / Masking

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Element comparison | `m :== 0.0` → `Mat[Boolean]` | `m == 0` | `m :== 0.0` | `m == 0` | `m == 0` |
| Negate mask | `!mask` | `~mask` | `!mask` | `!mask` | `~mask` |
| Count true | `mask.sum` | `mask.sum()` | `sum(mask)` | `sum(mask)` | `sum(mask(:))` |
| Any true | `mask.any` | `mask.any()` | `any(mask)` | `any(mask)` | `any(mask(:))` |
| All true | `mask.all` | `mask.all()` | `all(mask)` | `all(mask)` | `all(mask(:))` |
| Conditional select | `Mat.where(cond, x, y)` | `np.where(cond, x, y)` | `where(cond, x, y)` | `ifelse(cond, x, y)` | `cond.*x + ~cond.*y` |

---

## Stacking and Splitting

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Stack rows | `MatD.vstack(a, b)` | `np.vstack([a, b])` | `DenseMatrix.vertcat(a, b)` | `rbind(a, b)` | `[a; b]` |
| Stack cols | `MatD.hstack(a, b)` | `np.hstack([a, b])` | `DenseMatrix.horzcat(a, b)` | `cbind(a, b)` | `[a, b]` |
| Split rows | `m.vsplit(n)` | `np.vsplit(m, n)` | — | — | `mat2cell(m, repmat(r/n,1,n), c)` |
| Split cols | `m.hsplit(n)` | `np.hsplit(m, n)` | — | — | `mat2cell(m, r, repmat(c/n,1,n))` |

---

## Signal Processing (1-D Vectors)

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Polynomial fit | `MatD.polyfit(x, y, deg)` | `np.polyfit(x, y, deg)` | — | `lm(y ~ poly(x, deg))` | `polyfit(x, y, deg)` |
| Polynomial eval | `MatD.polyval(coeffs, x)` | `np.polyval(coeffs, x)` | — | `predict(fit, ...)` | `polyval(coeffs, x)` |
| Convolve | `MatD.convolve(a, b)` | `np.convolve(a, b)` | `convolve(a, b)` | `convolve(a, b)` | `conv(a, b)` |
| Correlate | `MatD.correlate(a, b)` | `np.correlate(a, b)` | — | `ccf(a, b)` | `xcorr(a, b)` |
| Meshgrid | `MatD.meshgrid(x, y)` | `np.meshgrid(x, y)` | — | `expand.grid(x, y)` | `meshgrid(x, y)` |

---

## Display and Formatting

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Print default | `println(m)` | `print(m)` | `println(m)` | `print(m)` | `disp(m)` |
| Explicit show | `m.show` | `str(m)` | `m.toString` | `print(m)` | `disp(m)` |
| Custom format | `m.show("%.2f")` | `np.set_printoptions(...)` | — | `format(m, digits=2)` | `format short` |
| Set thresholds | `Mat.setPrintOptions(maxRows=20, maxCols=20, edgeItems=5)` | `np.set_printoptions(threshold=...)` | — | `options(max.print=...)` | `format compact` |

---

## Quick Reference Card

```scala
import uni.data.*

// Create
val a = MatD.zeros(3, 4)
val b = MatD.randn(3, 4)
val c = MatD.eye(3)

// Seed + random (100% NumPy-compatible)
MatD.setSeed(42)
val w = MatD.uniform(-0.1, 0.1, 64, 32)

// Slice (zero-indexed, O(1) views)
val row0 = b(0, ::)   // first row
val col0 = b(::, 0)   // first column
val bT   = b.T        // transpose

// Arithmetic
val d = a + b         // element-wise
val e = a * b         // Hadamard product
val f = c ~@ b        // matmul

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

All three share the same API; factory objects mirror `Mat` methods with appropriate types.

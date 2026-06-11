# Mat - Complete API Documentation with Examples

*Examples mirror the comprehensive test suite (2,200+ tests)*

## Table of Contents

1. [Matrix Creation](#matrix-creation)
2. [Indexing and Slicing](#indexing-and-slicing)
3. [Arithmetic Operations](#arithmetic-operations)
4. [Broadcasting](#broadcasting)
5. [Linear Algebra](#linear-algebra)
6. [Statistical Functions](#statistical-functions)
7. [Element-wise Math](#element-wise-math)
8. [Machine Learning](#machine-learning)
9. [Random Number Generation](#random-number-generation)
10. [Data Manipulation](#data-manipulation)
11. [Comparison and Boolean Operations](#comparison-and-boolean-operations)
12. [Display and Formatting](#display-and-formatting)
13. [Vector Types (CVec / RVec)](#vector-types-cvec--rvec)
14. [Scalar Extraction](#scalar-extraction)
15. [Pandas-Style Data Analysis](#pandas-style-data-analysis)

---

## Matrix Creation

### Basic Constructors

**zeros creates matrix filled with zeros**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD.zeros(3, 4)        // idiomatic — MatD is an alias for Mat[Double]
// Mat.zeros[Double](3, 4)      // equivalent longer form
println(m)
// 3x4 Mat[Double]:
//  (0.0, 0.0, 0.0, 0.0),
//  (0.0, 0.0, 0.0, 0.0),
//  (0.0, 0.0, 0.0, 0.0)
```

**ones creates matrix filled with ones**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD.ones(2, 2)         // idiomatic
// Mat.ones[Double](2, 2)       // equivalent longer form
println(m)
// 2x2 Mat[Double]:
//  (1.0, 1.0),
//  (1.0, 1.0)
```

**eye creates identity matrix**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD.eye(3)             // idiomatic
// Mat.eye[Double](3)           // equivalent longer form
println(m)
// 3x3 Mat[Double]:
//  (1.0, 0.0, 0.0),
//  (0.0, 1.0, 0.0),
//  (0.0, 0.0, 1.0)
```

**full creates matrix with specified value**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD.full(2, 3, 7.0)   // idiomatic
// Mat.full[Double](2, 3, 7.0) // equivalent longer form
println(m)
// 2x3 Mat[Double]:
//  (7.0, 7.0, 7.0),
//  (7.0, 7.0, 7.0)
```

### From Tuples and Values

**apply creates matrix from tuples**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = Mat[Double]((1, 2), (3, 4))
println(m)
// 2x2 Mat[Double]:
//  (1.0, 2.0),
//  (3.0, 4.0)
```

**row creates row vector**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val v = Mat.row[Double](1, 2, 3)
println(v)
// 1x3 Mat[Double]:
//  (1.0, 2.0, 3.0)
```

**col creates column vector**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val v = Mat.col[Double](1, 2, 3)
println(v)
// 3x1 Mat[Double]:
//  (1.0),
//  (2.0),
//  (3.0)
```

**Mat(scalars...) creates column vector**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val v = Mat[Double](1.0, 2.0, 3.0)
println(v)
// 3x1 Mat[Double]:
//  (1.0),
//  (2.0),
//  (3.0)
```

**MatD / MatB / MatF flat varargs also create column vectors** *(changed in v0.14.0 — previously a row)*
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val v = MatD(1.0, 2.0, 3.0)   // 3x1 column — same orientation as Mat(...), CVec(...)
val r = MatD.row(1, 2, 3)     // 1x3 — the explicit row-vector factory

// Gotcha: integer arguments select the (rows, cols) zeros constructor
val z = MatD(3, 4)            // 3x4 zero matrix — NOT a 2-element vector
val p = MatD(3.0, 4.0)        // 2x1 column vector
```

**Sequences and ranges**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val a = MatD.arange(0, 10)            // 10x1 column: 0.0, 1.0, ..., 9.0
val b = MatD.arange(0.0, 1.0, 0.25)   // 4x1 column: 0.0, 0.25, 0.5, 0.75
val c = MatD.linspace(0.0, 1.0, 5)    // 5x1 column: 0.0, 0.25, 0.5, 0.75, 1.0
val d = MatD.fromSeq(Seq(1.0, 2.0))   // 2x1 column from any Seq[Double]
val e = MatD(2, 3, Array(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))  // 2x3 from flat row-major array
```

`linspace(start, stop, num = 1)` returns just `start` (fractional values preserved since v0.14.0).

---

## Indexing and Slicing

Indexing is zero-based and supports negative indices. **The `m(...)` slicing forms return
copies**; for a NumPy-style zero-copy view use `m.slice(rows, cols)` — like `.T` and
`broadcastTo`, it shares storage with the parent, so writes through it are visible there.
Since v0.14.0, slicing works for any element type (e.g. `Mat[Int]`) — no `Fractional` required.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((1, 2, 3), (4, 5, 6), (7, 8, 9))

// Element access / update (negative indices count from the end)
val x  = m(0, 1)                      // 2.0
val y  = m(-1, -1)                    // 9.0 (last element)
m(0, 0) = 10.0

// Slices — m(...) forms return independent copies
val row1: RVecD = m(1, ::)            // row 1 as 1x3 RVec
val col2: CVecD = m(::, 2)            // col 2 as 3x1 CVec
val sub  = m(0 until 2, 1 until 3)    // 2x2 submatrix
val all  = m(::)                      // whole-matrix copy

// Zero-copy view — shares storage with m (use .copy / .matCopy to detach)
val v = m.slice(0 until 2, 0 until 3)
v(0, 0) = 99.0                        // visible in m(0, 0)

// Fancy indexing (returns a copy, like NumPy)
val picked = m(Array(2, 0), ::)       // rows 2 and 0, in that order

// Boolean masking
val mask = m > 5.0                    // Mat[Boolean] (alias for m.gt(5.0))
val hits = m(mask)                    // matching elements as a vector
m(mask) = 0.0                         // assign to matching elements

// Slice assignment
m(0 until 2, ::) = 1.0                // fill first two rows
```

---

## Arithmetic Operations

`+`, `-`, `*`, `/` are element-wise; `*@` is matrix multiply. `*@` has the same precedence
as `*` and `/`, so `A *@ B / n` means `(A *@ B) / n`. The element-wise power operator `~^`
binds tighter — parenthesize when mixing: `(A ~^ 2) *@ B`.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val A = MatD((1, 2), (3, 4))
val B = MatD((5, 6), (7, 8))

// Element-wise
val sum  = A + B
val diff = A - B
val had  = A * B            // Hadamard product
val quot = A / B
val neg  = -A

// Scalar (both operand orders work)
val s1 = A * 2.0
val s2 = 2.0 * A
val s3 = A + 10.0

// Matrix multiply
val P = A *@ B              // or A.dot(B)

// Element-wise power (NumPy **)
val sq    = A ~^ 2          // each element squared
val roots = A ~^ 0.5        // fractional exponent
val ones  = A ~^ 0          // all-ones matrix (element-wise; since v0.14.0 `~^ 0.0` agrees)
// A.power(-1) throws UnsupportedOperationException — use 1.0 / A or A.inverse

// In-place (mutates and returns m)
A :+= 1.0
A :-= B
A :*= 2.0
A :/= 4.0
```

---

## Broadcasting

NumPy broadcasting rules: dimensions must match or be 1; size-1 axes are stretched
(stride 0 — no data copy).

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((1, 2, 3), (4, 5, 6))

val rowV = MatD.row(10, 20, 30)       // 1x3
val colV = MatD(100.0, 200.0)         // 2x1

val a = m + rowV                      // row added to every row
val b = m + colV                      // column added to every column

// Center each column — mean(axis=0) is 1xN and broadcasts back over rows
val centered = m - m.mean(axis = 0)

// Explicit broadcast view
val big = rowV.broadcastTo(4, 3)      // 4x3 view, no allocation
```

---

## Linear Algebra

Dense `Double` paths route through native BLAS/LAPACK (netlib for matmul, OpenBLAS for
decompositions); other element types use pure-Scala fallbacks.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val A = MatD((4, 2), (2, 3))

// Basics
val At   = A.T                        // O(1) transpose view (also A.transpose)
val inv  = A.inverse
val det  = A.determinant              // 8.0
val tr   = A.trace                    // 7.0
val dia  = A.diagonal                 // Array(4.0, 3.0)
val nrm  = MatD(3.0, 4.0).norm        // 5.0 — L2 norm; vectors (1xn / nx1) only
val frob = A.norm("fro")              // Frobenius norm for matrices

// Solving
val b = MatD(1.0, 2.0)                            // 2x1
val x = A.solve(b)                                // exact solve via LU
val (w, residuals, rank, sv) = A.lstsq(b)         // least squares (NumPy lstsq)

// Decompositions
val (q, r2)      = A.qrDecomposition              // A = Q * R
val (u, s, vt)   = A.svd                          // economy SVD: U n×p, Vt p×n, s: Array (p = min dim)
val l            = A.cholesky                     // lower triangular, A = L * L.T
val (re, im, v)  = A.eig                          // eigenvalues (re, im) + eigenvectors
val ev           = A.eigenvalues()                // eigenvalues only
```

---

## Statistical Functions

Whole-matrix reductions return a scalar; `axis = 0` reduces over rows (one result per
column, 1×cols), `axis = 1` over columns (rows×1).

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((1, 2), (3, 4), (5, 6))

// Scalar reductions
val s   = m.sum
val mu  = m.mean
val sd  = m.std                       // population std (ddof=0), like np.std
val va  = m.variance
val med = m.median
val p90 = m.percentile(90)
val lo  = m.min
val hi  = m.max

// Axis reductions
val colSums  = m.sum(axis = 0)        // 1x2
val rowMeans = m.mean(axis = 1)       // 3x1
val colStd   = m.std(axis = 0)

// Locations
val (minR, minC) = m.argmin
val (maxR, maxC) = m.argmax

// Other
val c   = m.cov                       // NumPy np.cov convention: each ROW is a variable → rows×rows
val cs  = m.cumsum                    // running sum (flat); cumsum(axis) per axis
val srt = m.sort()                    // flattened sorted copy (1xN); sort(0)/sort(1) per column/row
val ord = m.argsort()                 // indices that would sort (same axis rules)
val (vals, counts) = m.unique         // sorted distinct values + their counts
```

---

## Element-wise Math

`map` applies any scalar function; `mapParallel` fork/joins across rows for `Mat[Double]`.
Transcendental helpers (`sin`, `cos`, `tanh`, `log10`, `floor`, `ceil`, `trunc`) return
`Mat[Double]` regardless of input element type.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD.randn(5, 4)

val a  = m.abs
val sq = m.sqrt
val ex = m.exp
val ln = m.log
val l1 = m.log10
val si = m.sin
val co = m.cos
val th = m.tanh

// Rounding
val r0 = m.round()                    // nearest integer
val r2 = m.round(decimals = 2)
val fl = m.floor
val ce = m.ceil
val tc = m.trunc                      // toward zero

// Clamping
val cl = m.clip(-1.0, 1.0)
val mx = m.maximum(0.0)               // element-wise max vs a scalar
val mn = m.minimum(MatD.zeros(5, 4))  // element-wise min vs another Mat

// Arbitrary functions
val f1 = m.map(x => x * x + 1)
val f2 = m.mapParallel(x => math.expm1(x))   // parallel, Mat[Double] only
```

---

## Machine Learning

Activation functions return `Mat[Double]`. `sigmoid` and `relu` use parallel fast paths
for large `Mat[Double]` inputs.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD.randn(5, 4)

val sg = m.sigmoid                    // 1 / (1 + e^-x)
val re = m.relu                       // max(0, x)
val lk = m.leakyRelu(alpha = 0.01)
val sm = m.softmax(axis = 1)          // rows sum to 1
val ge = m.gelu

val dr = m.dropout(p = 0.5, training = true)   // pass seed = n for reproducibility
```

---

## Random Number Generation

The generator is a bit-for-bit faithful PCG64 — with the same seed, `rand` / `randn` /
`uniform` produce **exactly** the same values as NumPy's `np.random.default_rng` family.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

MatD.setSeed(42)                      // global seed (also Mat.setSeed)

val u  = MatD.rand(3, 4)              // uniform [0, 1)
val n  = MatD.randn(3, 4)             // standard normal
val nv = MatD.randn(5)                // 5x1 column CVec
val ab = MatD.uniform(-1.0, 1.0, 3, 4)
val g  = MatD.normal(5.0, 2.0, 3, 4)  // mean 5, std 2
val k  = MatD.randint(0, 10, 3, 4)    // Mat[Int], values in [0, 10)
```

---

## Data Manipulation

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m  = MatD.randn(4, 6)
val m1 = MatD.randn(2, 6)

// Shape changes
val rs = m.reshape(6, 4)              // same element count required
val fl: Array[Double] = m.flatten     // row-major copy (also m.toArray)
val rv = m.ravel                      // flatten to 1xN RVec
val cv = m.toColVec                   // flatten to Nx1 CVec

// Copying (m.slice / .T views share storage — copy to detach)
val snapshot = m.copy                 // also m.matCopy

// Stack / split
val tall  = MatD.vstack(m, m1)        // rows: 4 + 2 = 6
val wide  = MatD.hstack(m, m)         // cols: 6 + 6 = 12
val rowsP = tall.vsplit(3)            // 3 equal row blocks: Seq[MatD]
val colsP = m.hsplit(Array(2, 4))     // split before cols 2 and 4
val byAx  = m.split(2, axis = 1)      // generic axis split

// Repeat / tile
val rep = m.repeat(3, axis = 0)       // each row repeated 3 times
val til = m.tile(2, 3)                // whole matrix tiled 2x3

// Row filtering (predicate sees each row as a 1xN Mat)
val pos = m.filterRows(row => row(0, 0) > 0)

// First / last rows
val top = m.head(2)
val bot = m.tail(2)
```

> Removed in v0.14.0: `cloneMat` (use `copy` / `matCopy`) and the raw `data` accessor
> (use `toArray` / `flatten` — `data` exposed the parent's storage for views).

---

## Comparison and Boolean Operations

Ordering comparisons use `>` / `<` / `>=` / `<=` (named aliases `gt` / `lt` / `gte` / `lte`
also exist); equality is `:==` / `:!=` because Scala defines `==` / `!=` on `Any` — they
cannot return `Mat[Boolean]`. All comparisons return `Mat[Boolean]`.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((1, 2), (3, 4))

// Comparisons (operator and named forms are equivalent)
val gt = m > 2.0                      // same as m.gt(2.0)
val le = m <= 3.0                     // same as m.lte(3.0)
val ge = m >= 2                       // Int arguments work too
val eq = m :== 4.0
val ne = m :!= 4.0
val in = m.between(2.0, 3.0)          // inclusive on both ends

// Combining masks — unlike NumPy's &, Scala's && binds looser than comparisons,
// so no parentheses are needed
val both   = m > 1.0 && m < 4.0
val either = m < 2.0 || m > 3.0
val nope   = !both

// Reductions on masks
val anyBig  = (m > 3.0).any           // Boolean
val allPos  = (m > 0.0).all           // Boolean
val colsAll = (m > 0.0).all(axis = 0) // 1x2 Mat[Boolean]
val nTrue   = (m > 1.0).sum           // Int — count of true cells

// Conditional select
val r = Mat.where(m > 2.0, 1.0, 0.0)

// Floating-point hygiene
val nan  = m.isnan                    // also isinf, isfinite
val has  = m.containsNaN              // works for Double, Float, and BigNaN
val safe = m.nanToNum(nan = 0.0)      // replace NaN/±inf
val near = m.allclose(m, rtol = 1e-5, atol = 1e-8)
```

---

## Display and Formatting

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD.randn(5, 7)

println(m)                            // default formatting
println(m.show)                       // explicit, same output
println(m.show("%.2f"))               // printf-style per-element format

// Large matrices are elided NumPy-style; tune the thresholds:
Mat.setPrintOptions(maxRows = 20, maxCols = 20, edgeItems = 5)
```

---

## Vector Types (CVec / RVec)

`CVec[T]` (column vector, n×1) and `RVec[T]` (row vector, 1×n) are opaque types backed by `Mat[T]`.
Their `*@` overloads dispatch entirely on static types — no `asInstanceOf` or runtime branching.

### CVec / RVec creation

**CVec.apply / RVec.apply — from varargs**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val y: CVecD = CVec(1.0, 2.0, 3.0)   // 3×1 column vector
val r: RVecD = RVec(4.0, 5.0, 6.0)   // 1×3 row vector
```

**Factory methods**
```scala
val z = CVec.zeros[Double](5)        // 5-element column of zeros
val o = CVec.ones[Double](5)         // 5-element column of ones
val v = CVec.fromArray(Array(1.0, 2.0, 3.0))
val c = CVec.fromMat(someMatD)       // n×1 or 1×n Mat → CVec
```

`RVec` provides the same factories. Type aliases: `CVecD = CVec[Double]`, `RVecD = RVec[Double]`.

### *@ dispatch table

| Expression | Types | Result |
| :--- | :--- | :--- |
| `y.T *@ y` | `RVec *@ CVec` | `T` (dot product) |
| `y *@ y` | `CVec *@ CVec` | `T` (auto-transpose) |
| `r *@ r` | `RVec *@ RVec` | `T` (auto-transpose) |
| `y *@ y.T` | `CVec *@ RVec` | `Mat[T]` (outer product) |
| `X *@ y` | `Mat *@ CVec` | `CVec[T]` |
| `y.T *@ X` | `RVec *@ Mat` | `RVec[T]` |
| `y *@ X` | `CVec *@ Mat` | `RVec[T]` (auto-transpose) |
| `X *@ r` | `Mat *@ RVec` | `CVec[T]` (auto-transpose) |

**Quadratic form example**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val y: CVecD = CVec(1.0, 2.0, 3.0)
val X: MatD  = MatD((2,0,0),(0,3,0),(0,0,4))

val q: Double = y.T *@ X *@ y   // = 50.0
// println(q)
// 50.0
```

### CVec arithmetic

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val a: CVecD = CVec(1.0, 2.0, 3.0)
val b: CVecD = CVec(4.0, 5.0, 6.0)

val sum   = a + b           // CVec + CVec
val diff  = b - a           // CVec - CVec
val sp1   = a + 1.0         // CVec + scalar
val sm1   = a - 1.0         // CVec - scalar
val s2    = 2.0 * a         // Double * CVec
val i2    = 2   * a         // Int    * CVec
val l2    = 2L  * a         // Long   * CVec
val norm  = a.norm          // Euclidean norm
// println(a.show)
// 3x1 CVec[Double]:
//  (1.0, 2.0, 3.0)
```

`RVec` supports the same arithmetic operations.

---

## Scalar Extraction

**item — extract scalar from 1×1 matrix (NumPy: `.item()`)**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val a = MatD((3.0, 1.0), (1.0, 2.0))
val s: Double = (a *@ a.T).diagonal.sum.item   // extract scalar from 1×1
// throws IllegalArgumentException if not 1×1
```

---

## Pandas-Style Data Analysis

All methods are defined on `Mat[T]` and work with `MatD`, `MatF`, and `MatB`.

### Row selection

**head returns first n rows**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((1,2),(3,4),(5,6),(7,8))
m.head(2)
// 2x2 Mat[Double]:
//  (1.0, 2.0),
//  (3.0, 4.0)
```

**tail returns last n rows**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((1,2),(3,4),(5,6),(7,8))
m.tail(2)
// 2x2 Mat[Double]:
//  (5.0, 6.0),
//  (7.0, 8.0)
```

---

### Argmin / Argmax per axis

**idxmin(axis) — index of minimum per column (axis=0) or per row (axis=1)**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((3,1),(2,4),(5,0))
m.idxmin(0)   // 1x3 — column minimums at rows: (1, 2, 2)
m.idxmin(1)   // 3x1 — row minimums at cols:    (1, 0, 1)
```

**idxmax(axis) — index of maximum per column or row**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((3,1),(2,4),(5,0))
m.idxmax(0)   // 1x3 — column maximums at rows: (2, 1, 1)
m.idxmax(1)   // 3x1 — row maximums at cols:    (0, 1, 0)
```

---

### Cumulative min / max

**cummax(axis) — running maximum along an axis**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((3,1),(1,4),(5,2))
m.cummax(0)
// 3x2 Mat[Double]:
//  (3.0, 1.0),
//  (3.0, 4.0),
//  (5.0, 4.0)
```

**cummin(axis) — running minimum along an axis**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((3,1),(1,4),(5,2))
m.cummin(0)
// 3x2 Mat[Double]:
//  (3.0, 1.0),
//  (1.0, 1.0),
//  (1.0, 1.0)
```

---

### Top / bottom N values

**nlargest(n) — n largest values as a row vector**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((3,1),(2,5),(4,0))
m.nlargest(3)   // 1x3: (5.0, 4.0, 3.0)
```

**nsmallest(n) — n smallest values as a row vector**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((3,1),(2,5),(4,0))
m.nsmallest(3)  // 1x3: (0.0, 1.0, 2.0)
```

---

### Range test

**between(lo, hi) — boolean mask: `lo <= x <= hi`**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((1,5),(3,7),(9,2))
m.between(2.0, 6.0)
// 3x2 Mat[Boolean]:
//  (false, true),
//  (true,  false),
//  (false, true)
```

---

### Unique values and frequency

**nunique — count of distinct values**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((1,2),(2,3),(1,3))
m.nunique   // 3
```

**valueCounts — frequency table, descending**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((1,2),(2,3),(1,3))
m.valueCounts   // Array((2.0, 2), (1.0, 2), (3.0, 2)) — ties broken by order
```

---

### Shift / lag

**shift(n, fill, axis=0) — shift rows (axis=0) or columns (axis=1) by n steps**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((1,2),(3,4),(5,6))
m.shift(1, Double.NaN)
// 3x2 Mat[Double]:
//  (NaN,  NaN),
//  (1.0,  2.0),
//  (3.0,  4.0)
```

**pct_change(axis=0) — element-wise percent change from previous position**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val prices = MatD((100.0),(110.0),(99.0))
prices.pct_change()
// 3x1 Mat[Double]:
//  (NaN),
//  (0.1),
//  (-0.1)
```

---

### NaN fill

**fillna(value) — replace NaN with a constant**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((1,Double.NaN),(Double.NaN,4))
m.fillna(0.0)
// 2x2 Mat[Double]:
//  (1.0, 0.0),
//  (0.0, 4.0)
```

---

### Descriptive statistics summary

**describe — returns (labels, 8×cols summary matrix)**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((1,2),(3,4),(5,6))
val (labels, stats) = m.describe
// labels: Array("count","mean","std","min","25%","50%","75%","max")
// stats:  8x2 Mat[Double] — one column per input column
```

---

### Rolling window

**rolling(window) — sliding-window aggregations (NaN for the first window-1 rows)**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.data.*

val m = MatD((1),(2),(3),(4),(5))
m.rolling(3).mean
// 5x1 Mat[Double]:
//  (NaN), (NaN), (2.0), (3.0), (4.0)

m.rolling(3).sum
// 5x1 Mat[Double]:
//  (NaN), (NaN), (6.0), (9.0), (12.0)
```

Available aggregations: `.mean`, `.sum`, `.min`, `.max`, `.std`

---

### CSV with named columns (`MatResult`)

`FileOps.loadSmart` returns a `MatResult[T]` that pairs headers with matrix data:

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.14.0

import uni.*
import uni.data.*
import uni.io.FileOps.*

val result = loadSmart(Paths.get("data.csv"))          // MatResult[Big]
val col    = result("Price")                            // ColVec[Big] — throws if not found
val maybeQ = result.col("Qty")                         // Option[ColVec[Big]]
val idx    = result.columnIndex                         // Map[String, Int]

// Convert to Double
val dbl = loadSmart(Paths.get("data.csv"), _.toDouble) // MatResult[Double]
val prices: ColVec[Double] = dbl("Price")
```

---
[Quick Start Guide](QuickStartGuide.md) | [README](../README.md)
*Examples mirror the Mat test suite; [`jsrc/docCheck.sc`](../jsrc/docCheck.sc) compile-checks and runs the
snippets in this guide against the published library.*
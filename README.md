# uni.data

**uni.Mat** is a high-performance, NumPy-compatible matrix library for **Scala 3.7.0+**.

It provides a zero-overhead, type-safe interface for scientific computing by leveraging Scala 3 **Opaque Types**. Designed for developers who need the ergonomics and reproducibility of the Python/NumPy ecosystem within the JVM, `uni.Mat` features 100% faithful implementations of NumPy's random generation and strided array logic.

## Key Features

* **Zero-Overhead Types:** Uses `opaque type Mat[T] = Internal.MatData[T]` to ensure that at runtime, your matrices are as lean as raw arrays, with no wrapper object overhead.
* **NumPy Random Fidelity:** 1:1 behavioral matching for `rand`, `randn`, `uniform`, `randint`, etc. using a high-performance **PCG64** implementation.
* **Strided Memory Layout:** Supports `rowStride` and `colStride`, enabling $O(1)$ `transpose` and zero-copy slicing/views, mirroring NumPy's internal engine.
* **Broadcasting & In-place Ops:** Built-in support for NumPy-style broadcasting and memory-efficient in-place mutation operators (`:+=`, `:-=`, `:*=`, `:/=`).
* **Deep Learning Primitives:** Activation functions (`sigmoid`, `relu`, `softmax`, `leakyRelu`) use parallel fork/join for contiguous matrices — outperforming NumPy's single-core SIMD on multi-core hardware.

For high-accuracy scientific modeling or other applications requiring extreme precision, `uni.Mat` provides a `Big` numeric type.

* **High Precision:** [Big Type Guide](docs/BigTypeGuide.md) — Learn about high-precision matrices, and how to use `Mat[Big]`.

## Performance vs NumPy

Measured on the same machine (JVM 17 / Scala 3.7.0 vs Python 3.14.3 / NumPy 2.4.1). Both use OpenBLAS.

| Operation | NumPy | MatD | Ratio |
| :--- | ---: | ---: | :--- |
| `randn(1000×1000)` | 19 ms | 21 ms | **≈ tied** |
| `matmul 512×512` | 1.7 ms | 3.3 ms | 1.9× slower |
| `sigmoid(1000×1000)` | 12.6 ms | 3.0 ms | **4× faster** |
| `relu(1000×1000)` | 2.0 ms | 0.8 ms | **2.5× faster** |
| `add(1000×1000)` | 2.3 ms | 1.7 ms | **1.4× faster** |
| `sum(1000×1000)` | 0.3 ms | 0.5 ms | 1.6× slower |
| `transpose(1000×1000)` | ≈0 ms | ≈0 ms | **tied** |
| custom fn (`mapParallel` vs `np.vectorize`) | 440 ms | 0.9 ms | **470× faster** |
| `3PRF IS Full (T=650, N=40, L=2)` | 7 ms | 1.6 ms | **4.4× faster** |
| `3PRF OOS Recursive (T=650, N=40, L=2)` | 265 ms | 140 ms | **1.9× faster** |
| `3PRF OOS Cross Val (T=650, N=40, L=2)` | 679 ms | 334 ms | **2× faster** |

  **3PRF benchmark key** — Three-Pass Regression Filter ([Kelly & Pruitt 2015](https://doi.org/10.1111/jofi.12246));
  `T` = observations, `N` = predictors, `L` = proxy factors.
  Python: `estimate3prf_fast` (vectorized NumPy). Scala: `tprfFast` / `estimate3prf` with parallel collections.

  | Label | Description |
  | :--- | :--- |
  | IS Full | In-sample fit over the full sample; two vectorized batch solves |
  | OOS Recursive | Expanding-window out-of-sample; re-estimates at each step |
  | OOS Cross Val | Leave-one-out cross-validation across all T windows |

Full results and methodology: [MatD Cheat Sheet — Performance](docs/MatDCheatSheet.md).

## Design Philosophy

uni.Mat is built on the principle that developers shouldn't have to choose between type safety and the ergonomics of NumPy. Its design leverages strides, offsets, and broadcasting for high efficiency—including LAPACK integration—all while maintaining a clean, expression-oriented API.

## Test Coverage

Measured with `sbt jacoco` (1,663 tests). Branch coverage is lower than line coverage because Scala 3 `inline` methods generate independent bytecode copies at each call site, each with their own branch counters.

| Package | Branch | Line |
| :--- | ---: | ---: |
| `uni.data` | 55% | 88% |
| `uni.stats` | 34% | 88% |
| `uni.time` | 34% | 71% |
| `uni.cli` | 18% | 24% |
| `uni` | 29% | 43% |

## Installation

Add the following to your `build.sbt`:

```scala
libraryDependencies += "org.vastblue" %% "uni" % "0.9.5"
```

## Quick Start

### Matrix Creation & Randomness

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.5

import uni.data.*

// 100% faithful to NumPy's PCG64-based np.random.uniform
MatD.setSeed(42)
val weights = MatD.uniform(low = -0.1, high = 0.1, rows = 64, cols = 32)

// Standard constructors
val zeros    = MatD.zeros(10, 10)
val identity = MatD.eye(5)
val normal   = MatD.normal(10, 10)
println(normal)
```

## Matrix Type Aliases

To keep your code concise and idiomatic, `uni.MatD` provides type aliases and matching factory objects for supported numeric types.

| Alias | Full Type | Description |
| :--- | :--- | :--- |
| `MatD` | `Mat[Double]` | Standard 64-bit floating point matrix (default) |
| `MatB` | `Mat[Big]` | High-precision, NaN-safe `BigDecimal` matrix |
| `MatF` | `Mat[Float]` | 32-bit floating point matrix for memory efficiency |

Each alias has a matching factory object mirroring the `MatD` API:

```scala
import uni.data.*

val weights:    MatD = MatD.randn(64, 32)
val prices:     MatB = MatB.zeros(10, 1)
val embeddings: MatF = MatF.rand(128, 64)

// Extension method from Big
val preciseVal = 10.5.asBig
val identityB: MatB = MatB.eye(5)
```

## NumPy-like Syntax and Features

### Slicing and Views

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.5

import uni.data.*
import uni.data.MatD.*

MatD.setSeed(95)
val data = MatD.randn(100, 10)

// Extract a row or column as a view using the :: sentinel
val row = data(0, ::)    // first row
val col = data(::, 0)    // first column

// Constant-time transpose (no data copy)
val rotated = data.T
println(s"row: $row")
println(s"col: $col")
println(s"rotated: $rotated")
println(s"rotated: ${rotated.show("%7.2f")}")
```

### Mathematical Operations

```scala
val a = MatD.randn(3, 3)
val b = MatD.randn(3, 3)

val c = a ~@ b    // matrix multiplication (matmul)
val d = a + b     // element-wise addition
val e = a * b     // Hadamard (element-wise) product
val f = a.relu    // built-in activation function
```

### In-place Operations

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.5

import uni.data.*
import uni.data.MatD.*

val m = MatD.ones(4, 4)
m :+= 2.0    // add scalar in-place
m :-= 1.0    // subtract scalar in-place
m :*= 3.0    // multiply scalar in-place
m :/= 2.0    // divide scalar in-place

val n = MatD.ones(4, 4)
m :+= n      // element-wise add matrix in-place
```

### Boolean Operations

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.5

import uni.data.*
import uni.data.MatD.*

val a    = MatD.randn(3, 3)
val mask = (a :== 0) || (a :== 1)    // MatD[Boolean]

val inverted = !mask
val count    = mask.sum    // count of true elements
val anyTrue  = mask.any
val allTrue  = mask.all
```

### Stacking and Splitting

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.5

import uni.data.*
import uni.data.MatD.*

val top    = MatD.ones(2, 4)
val bottom = MatD.zeros(2, 4)

val stacked = MatD.vstack(top, bottom)    // 4x4 MatD
val halves  = stacked.vsplit(2)          // Seq of two 2x4 Mats

val left  = MatD.ones(4, 2)
val right = MatD.zeros(4, 2)
val wide  = MatD.hstack(left, right)      // 4x4 MatD
val cols  = wide.hsplit(2)               // Seq of two 4x2 Mats
```

### Display and Formatting

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.5

import uni.data.*
import uni.data.MatD.*

val m = MatD.randn(3, 3)

println(m)               // calls toString
println(m.show)          // equivalent, explicit
println(m.show("%.2f"))  // custom format string

// Adjust truncation thresholds for large matrices
MatD.setPrintOptions(maxRows = 20, maxCols = 20, edgeItems = 5)
```

### Usage Example

## Comprehensive Example: MatD in Action

The following example demonstrates a wide array of ```uni.MatD``` capabilities, including matrix creation, slicing, broadcasting, linear algebra (SVD, QR, Inverse), and in-place mutation. It illustrates how the library brings NumPy-style ergonomics to Scala while maintaining high performance.

```scala
#!/usr/bin/env -S scala-cli shebang -deprecation
//> using dep org.vastblue:uni_3:0.9.5

import uni.data.*
import uni.data.MatD.*

object MatDCheck {
  def main(args: Array[String]): Unit = {
    // 1. Creation and Basic Shapes
    val m = MatD.zeros[Double](3, 4)
    val v = MatD.row(1, 2, 3, 4)
    val eye = MatD.eye(2)
    val r = MatD.arange(0, 10, 2)
    val ls = MatD.linspace(0, 1, 5)

    // 2. Arithmetic & Broadcasting
    val result = m + v      // Row-wise broadcasting
    val powr = result ~^ 2  // Power operator
    val matMult = powr ~@ v.T // Matrix multiplication (matmul)

    // 3. Math Functions
    val sq = m.sqrt
    val ex = v.exp
    val cl = v.clip(0, 5)

    // 4. Reductions
    val s = m.sum
    val s0 = m.sum(0) // Sum over axis 0
    val mn = m.mean

    // 5. Linear Algebra
    val A = MatD((1, 2), (3, 4))
    val b = MatD.row(5, 6)
    val x = A.solve(b)         // Linear solver
    val inv_A = A.inverse
    val det_A = A.determinant
    val (u, s2, vt) = A.svd    // Singular Value Decomposition
    val (q, r2) = A.qrDecomposition

    // 6. Boolean Masking & Filtering
    val mask = v.gt(1)
    val filtered = v(mask)
    v(v.lt(0)) = 0 // Conditional assignment

    // 7. Slicing & Manipulation
    val sub = m(0 until 2, ::) // Slice rows
    val col = m(::, 0)         // Slice column
    val reshaped = m.reshape(2, 6)
    val transposed = m.T       // O(1) Transpose
    val stacked = MatD.vstack(m, m)

    // 8. Random Generation
    val rand_m = MatD.rand(3, 3)
    val randn_m = MatD.randn(3, 3)

    // 9. In-place Mutation
    m :+= 1
    m :*= 2
    for i <- 0 until 3 do
      m(i, ::) = i * 2
  }
}
```

## Advanced Usage

* **Cheat Sheet:** [MatD Cheat Sheet](docs/MatDCheatSheet.md) — Side-by-side comparison of MatD vs NumPy, Breeze, R, and MATLAB.
* **High Precision:** [Big Type Guide](docs/BigTypeGuide.md) — High-precision matrices using `MatD[Big]`.

### NumPy to uni.MatD Mapping

| NumPy | uni.MatD | Note |
| :--- | :--- | :--- |
| `a @ b` | `a ~@ b` | Matrix multiplication |
| `a * b` | `a * b` | Element-wise product |
| `a[0, :]` | `a(0, ::)` | Row slice |
| `a[:, 0]` | `a(::, 0)` | Column slice |
| `a.T` | `a.T` | $O(1)$ view |
| `np.random.randn` | `MatD.randn` | PCG64-backed |
| `np.where(c, x, y)` | `MatD.where(c, x, y)` | Conditional selection |
| `np.vstack` / `np.vsplit` | `MatD.vstack` / `m.vsplit(n)` | Row-wise stack / split |

## Example: Neural Network Layer

Because activation functions are members of the `MatD` type, building layers is idiomatic:

```scala
def denseLayer(input: MatD[Double], weights: MatD[Double], bias: MatD[Double]): MatD[Double] = {
  // Simple, readable forward pass
  (input ~@ weights + bias).sigmoid
}
```

## Other Features

`uni` also includes utilities that predate `uni.data.MatD` and remain available via `import uni.*`:

* **Scripting Shortcuts:** [Portable Programming Utilities](docs/UniScriptingTools.md) — MSYS2/Cygwin-aware paths, smart date parsing, command-line argument handling, and inline data embedding.

### Tolerant Numeric Parsing for Big Data Prep

Raw financial and scientific datasets rarely arrive in clean form. `uni.data.BigUtils` provides `isNumeric` and `getMostSpecificType` that accept the messy formats found in spreadsheets and CSV exports **without requiring pre-cleaning**:

| Raw input | Recognised as |
| :--- | :--- |
| `"$1,234.56"` | `Big(1234.56)` |
| `"(500.00)"` | `Big(-500.00)` — accounting negative |
| `"7.5K"` / `"2.1M"` / `"4B"` | `Big(7500)` / `Big(2_100_000)` / `Big(4_000_000_000)` |
| `"12.5%"` | `Big(0.125)` — already divided by 100 |
| `"-0.042"` | `Big(-0.042)` |
| `"2024-01-15"` | `DateTime` |
| anything else | `String` (passed through unchanged) |

`getMostSpecificType` promotes each raw cell to the most specific type it can without error, so a column of mixed-format numbers loads correctly as `Mat[Big]` in a single pass:

```scala
import uni.data.*
import uni.data.BigUtils.*

val raw = Seq("$1,200", "(300)", "1.5K", "0.75%", "N/A", "2024-03-01")
val typed = raw.map(getMostSpecificType)
// => Seq(Big(1200), Big(-300), Big(1500), Big(0.0075), "N/A", DateTime(...))

// Load numeric cells directly into a column vector
val nums = typed.collect { case b: BigDecimal => Big(b) }
val col  = MatB.col(nums*)
```

This eliminates the typical ETL step of writing format-specific regex cleaners before ingestion — particularly valuable when working with multi-source datasets where currency symbols, parenthesised negatives, and scale suffixes appear unpredictably across columns.

---
© 2026 vastblue.org. Distributed under the Apache License 2.0.
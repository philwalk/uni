# uni.Mat

**uni.Mat** is a high-performance, NumPy-compatible matrix library for **Scala 3.7.0+**.

It provides a zero-overhead, type-safe interface for scientific computing by leveraging Scala 3 **Opaque Types**. Designed for developers who need the ergonomics and reproducibility of the Python/NumPy ecosystem within the JVM, `uni.Mat` features 100% faithful implementations of NumPy's random generation and strided array logic.

## Key Features

* **Zero-Overhead Opaque Types:** Uses `opaque type Mat[T] = Internal.MatData[T]` to ensure that at runtime, your matrices are as lean as raw arrays, with no wrapper object overhead.
* **NumPy Random Fidelity:** 1:1 behavioral matching for `rand`, `randn`, `uniform`, `randint`, etc. using a high-performance **PCG64** implementation.
* **Strided Memory Layout:** Supports `rowStride` and `colStride`, enabling $O(1)$ `transpose` and zero-copy slicing/views, mirroring NumPy's internal engine.
* **Broadcasting & In-place Ops:** Built-in support for NumPy-style broadcasting and memory-efficient in-place mutation operators (`:+=`, `:-=`, `:*=`, `:/=`).
* **Deep Learning Primitives:** Optimized activation functions (`sigmoid`, `relu`, `softmax`, `leakyRelu`) and linear algebra operators (`~@`) built directly into the core type.

## Installation

Add the following to your `build.sbt`:

```scala
libraryDependencies += "org.vastblue" %% "uni" % "0.9.1"
```

## Quick Start

### Matrix Creation & Randomness

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.1

import uni.data.*

// 100% faithful to NumPy's PCG64-based np.random.uniform
Mat.setSeed(42)
val weights = Mat.uniform(low = -0.1, high = 0.1, rows = 64, cols = 32)

// Standard constructors
val zeros    = Mat.zeros(10, 10)
val identity = Mat.eye(5)
val normal   = Mat.normal(10, 10)
println(normal)
```

## Matrix Type Aliases

To keep your code concise and idiomatic, `uni.Mat` provides type aliases and matching factory objects for common numeric types.

| Alias | Full Type | Description |
| :--- | :--- | :--- |
| `MatD` | `Mat[Double]` | Standard 64-bit floating point matrix (default) |
| `MatB` | `Mat[Big]` | High-precision, NaN-safe `BigDecimal` matrix |
| `MatF` | `Mat[Float]` | 32-bit floating point matrix for memory efficiency |

Each alias has a matching factory object mirroring the `Mat` API:

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
val data = Mat.randn(100, 10)

// Extract a row or column as a view using the :: sentinel
val row = data(0, ::)    // first row
val col = data(::, 0)    // first column

// Constant-time transpose (no data copy)
val rotated = data.T
```

### Mathematical Operations

```scala
val a = Mat.randn(3, 3)
val b = Mat.randn(3, 3)

val c = a ~@ b    // matrix multiplication (matmul)
val d = a + b     // element-wise addition
val e = a * b     // Hadamard (element-wise) product
val f = a.relu    // built-in activation function
```

### In-place Operations

```scala
val m = Mat.ones(4, 4)
m :+= 2.0    // add scalar in-place
m :-= 1.0    // subtract scalar in-place
m :*= 3.0    // multiply scalar in-place
m :/= 2.0    // divide scalar in-place

val n = Mat.ones(4, 4)
m :+= n      // element-wise add matrix in-place
```

### Boolean Operations

```scala
val a    = Mat.randn(3, 3)
val mask = (a :== 0) || (a :== 1)    // Mat[Boolean]

val inverted = !mask
val count    = mask.sum    // count of true elements
val anyTrue  = mask.any
val allTrue  = mask.all
```

### Stacking and Splitting

```scala
val top    = Mat.ones(2, 4)
val bottom = Mat.zeros(2, 4)

val stacked = Mat.vstack(top, bottom)    // 4x4 Mat
val halves  = stacked.vsplit(2)          // Seq of two 2x4 Mats

val left  = Mat.ones(4, 2)
val right = Mat.zeros(4, 2)
val wide  = Mat.hstack(left, right)      // 4x4 Mat
val cols  = wide.hsplit(2)               // Seq of two 4x2 Mats
```

### Display and Formatting

```scala
val m = Mat.randn(3, 3)

println(m)               // calls toString
println(m.show)          // equivalent, explicit
println(m.show("%.2f"))  // custom format string

// Adjust truncation thresholds for large matrices
Mat.setPrintOptions(maxRows = 20, maxCols = 20, edgeItems = 5)
```

## Advanced Usage

For high-accuracy scientific modeling or other applications requiring extreme precision, `uni.Mat` provides a `Big` numeric type.

* **High Precision:** [Big Type Guide](docs/BigTypeGuide.md) — Learn about high-precision matrices, and how to use `Mat[Big]`.

## Design Philosophy

`uni.Mat` is built on the principle that Scala developers shouldn't have to choose between type safety and the proven ergonomics of NumPy. By using **Opaque Types**, we hide the implementation details of strides and offsets while providing a clean, expression-oriented API.

### NumPy to uni.Mat Mapping

| NumPy | uni.Mat | Note |
| :--- | :--- | :--- |
| `a @ b` | `a ~@ b` | Matrix multiplication |
| `a * b` | `a * b` | Element-wise product |
| `a[0, :]` | `a(0, ::)` | Row slice |
| `a[:, 0]` | `a(::, 0)` | Column slice |
| `a.T` | `a.T` or `a.transpose` | $O(1)$ view |
| `np.random.randn` | `Mat.randn` | PCG64-backed |
| `np.where(c, x, y)` | `Mat.where(c, x, y)` | Conditional selection |
| `np.vstack` / `np.vsplit` | `Mat.vstack` / `m.vsplit(n)` | Row-wise stack / split |

## Example: Neural Network Layer

Because activation functions are members of the `Mat` type, building layers is idiomatic:

```scala
def denseLayer(input: Mat[Double], weights: Mat[Double], bias: Mat[Double]): Mat[Double] = {
  // Simple, readable forward pass
  (input ~@ weights + bias).sigmoid
}
```

---
© 2026 vastblue.org. Distributed under the Apache License 2.0.

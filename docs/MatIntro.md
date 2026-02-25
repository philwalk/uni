# Mat - NumPy-Compatible Matrix Library for Scala

## Installation
````scala
libraryDependencies += "org.someorg" %% "mat" % "0.9.0"
````

## Quick Start
````scala
import uni.data.Mat
import uni.data.Mat.*

// Create matrices
val m = Mat[Double]((1, 2, 3), (4, 5, 6))  // 2x3 matrix from tuples
val v = Mat(1.0, 2.0, 3.0)                  // Column vector
val zeros = Mat.zeros[Double](3, 3)
val ones = Mat.ones[Double](2, 4)
val identity = Mat.eye[Double](5)
val random = Mat.rand(10, 10)               // Uniform [0,1)
````

### 2. **Core Capabilities** (from your test files)

**Matrix Creation** (from MatTest basic tests)
````scala
// From test: "zeros creates zero matrix"
val z = Mat.zeros[Double](3, 4)

// From test: "ones creates matrix of ones"  
val o = Mat.ones[Double](2, 5)

// From test: "eye creates identity matrix"
val I = Mat.eye[Double](3)

// From test: "full creates matrix filled with value"
val f = Mat.full[Double](3, 3, 7.0)

// From test: "arange creates range matrix"
val r = Mat.arange[Double](0, 10)  // Row vector [0..9]
````

**Indexing & Slicing** (from your slicing tests)
````scala
// From test: "basic indexing works"
val value = m(0, 1)

// From test: "slice returns submatrix"
val sub = m(0 until 2, 1 until 3)

// From test: "colon operator selects all"
val row = m(0, ::)     // First row
val col = m(::, 2)     // Third column

// From test: "negative indices work"
val last = m(-1, -1)   // Bottom-right element

// From test: "boolean masking filters elements"
val mask = m > 5.0
val filtered = m(mask)

// From test: "fancy indexing with arrays"
val indices = Array(0, 2, 1)
val reordered = m(indices, ::)
````

**Broadcasting** (from broadcasting tests)
````scala
// From test: "broadcasting: matrix + row vector"
val m = Mat[Double]((1, 2, 3), (4, 5, 6))
val v = Mat.row[Double](10, 20, 30)
val result = m + v  // Broadcasts to (2, 3)

// From test: "broadcasting: matrix * column vector"
val colVec = Mat[Double](2, 3)  // 2x1
val scaled = m * colVec  // Each row scaled differently
````

**Linear Algebra** (from linear algebra tests)
````scala
// From test: "matrix multiplication"
val A = Mat[Double]((1, 2), (3, 4))
val B = Mat[Double]((5, 6), (7, 8))
val C = A @@ B  // or A.dot(B)

// From test: "transpose"
val AT = A.T

// From test: "inverse of 2x2 matrix"
val inv = A.inverse

// From test: "determinant"
val det = A.determinant

// From test: "solve: A * x = b"
val b = Mat[Double](1, 2)
val x = A.solve(b)

// From test: "svd: U * diag(s) * Vt = original matrix"
val (U, s, Vt) = A.svd

// From test: "qr decomposition: Q * R = original matrix"
val (Q, R) = A.qrDecomposition

// From test: "eigenvalues"
val (eigVals, eigValsImag, eigVecs) = A.eig
````

**Statistical Functions** (from stats tests)
````scala
// From test: "mean computes average"
val avg = m.mean

// From test: "std computes standard deviation"
val stdDev = m.std

// From test: "median finds middle value"
val med = m.median

// From test: "percentile computes quantiles"
val p90 = m.percentile(90)

// From test: "min/max find extremes"
val min = m.min
val max = m.max

// From test: "argmin/argmax return indices"
val (minRow, minCol) = m.argmin
val (maxRow, maxCol) = m.argmax
````

**Element-wise Operations** (from math tests)
````scala
// From test: "abs computes absolute value"
val absM = m.abs

// From test: "sqrt computes square root"
val sqrtM = m.sqrt

// From test: "exp computes exponential"
val expM = m.exp

// From test: "log computes natural logarithm"
val logM = m.log

// From test: "trigonometric functions"
val sinM = m.sin
val cosM = m.cos
val tanM = m.tan

// From test: "maximum/minimum element-wise"
val maxElems = m1.maximum(m2)
val minElems = m1.minimum(m2)

// From test: "clip values to range"
val clipped = m.clip(0.0, 1.0)
````

**Machine Learning Functions** (from ML tests)
````scala
// From test: "sigmoid activation"
val sig = m.sigmoid

// From test: "relu activation"  
val relu = m.relu

// From test: "softmax along axis"
val probs = m.softmax(axis = 1)

// From test: "dropout for training"
val dropped = m.dropout(p = 0.5, training = true)

// From test: "leaky relu"
val leaky = m.leakyRelu(alpha = 0.01)
````

**Random Number Generation** (from RNG tests)
````scala
// From test: "rand with seed is reproducible"
Mat.setSeed(42)
val r1 = Mat.rand(5, 5)

Mat.setSeed(42)  
val r2 = Mat.rand(5, 5)
// r1 == r2 (exactly, bit-for-bit)

// From test: "randn produces normal distribution"
val normal = Mat.randn(1000, 1000)  // mean≈0, std≈1

// From test: "uniform distribution in range"
val uniform = Mat.uniform(0.0, 10.0, 5, 5)

// From test: "random integers"
val ints = Mat.randint(0, 100, 3, 3)
````

**Data Manipulation** (from manipulation tests)
````scala
// From test: "reshape changes dimensions"
val reshaped = m.reshape(6, 2)

// From test: "flatten converts to 1D"
val flat = m.flatten

// From test: "vsplit splits vertically"
val (train, test) = m.vsplit(Array(800))

// From test: "hsplit splits horizontally"  
val parts = m.hsplit(3)

// From test: "concatenate joins matrices"
val combined = Mat.vstack(m1, m2, m3)

// From test: "repeat tiles along axis"
val repeated = m.repeat(3, axis = 0)

// From test: "tile replicates matrix"
val tiled = m.tile(2, 3)
````

**Display & Formatting** (from show tests)
````scala
// From test: "show formats matrix nicely"
println(m.show)
// 5x3 Mat[Double]:
//  ( 1.234,  2.345,  3.456),
//  ( 4.567,  5.678,  6.789),
//  ...

// From test: "formatMatrix truncates large matrices"
Mat.setPrintOptions(edgeItems = 5, maxRows = 20)
println(bigMatrix)  // Shows edges with ... for large matrices

// From test: "show with explicit format"
println(m.show("%.2f"))
````

**Boolean Operations** (from comparison tests)
````scala
// From test: "comparison operators"
val mask = m > 5.0
val mask2 = m.lte(10.0)

// From test: "all/any reductions"
val allTrue = mask.all
val anyTrue = mask.any
val colAll = mask.all(axis = 0)
````

### 3. **NumPy Compatibility Guide**
````markdown
## NumPy → Mat Translation

| NumPy | Mat (Scala) | Notes |
|-------|-------------|-------|
| `np.array([[1,2],[3,4]])` | `Mat[Double]((1,2), (3,4))` | Tuple syntax |
| `np.zeros((3,4))` | `Mat.zeros[Double](3, 4)` | Type parameter |
| `np.random.seed(42)` | `Mat.setSeed(42)` | Exact PCG64 compatibility |
| `np.random.randn(5,5)` | `Mat.randn(5, 5)` | Same distribution |
| `a @ b` | `a @@ b` or `a.dot(b)` | Matrix multiplication |
| `a * b` | `a * b` | Element-wise (with broadcasting) |
| `a[0:2, :]` | `a(0 until 2, ::)` | Slicing |
| `a[mask]` | `a(mask)` | Boolean indexing |
| `a[[0,2,1], :]` | `a(Array(0,2,1), ::)` | Fancy indexing |
| `np.linalg.svd(a)` | `a.svd` | Returns (U, s, Vt) |
| `np.maximum(a, b)` | `a.maximum(b)` | Element-wise max |

## Complete Translation Example
```python
# NumPy
import numpy as np
np.random.seed(42)
X = np.random.randn(100, 10)
y = np.random.randn(100, 1)
weights = np.linalg.lstsq(X, y)[0]
```
```scala
// Mat
import uni.data.Mat
import uni.data.Mat.*

Mat.setSeed(42)
val X = Mat.randn(100, 10)
val y = Mat.randn(100, 1)
val (weights, _, _, _) = X.lstsq(y)
```
````

### 4. **API Reference** (auto-generated from tests)

Would you like me to generate a complete API reference by parsing all your test method names? Something like:
````markdown
## Matrix Creation
- `zeros[T](rows: Int, cols: Int): Mat[T]` - Create zero matrix
- `ones[T](rows: Int, cols: Int): Mat[T]` - Create ones matrix
- `eye[T](n: Int): Mat[T]` - Create identity matrix
- `rand(rows: Int, cols: Int): Mat[Double]` - Random uniform [0,1)
- `randn(rows: Int, cols: Int): Mat[Double]` - Random normal N(0,1)
...
````


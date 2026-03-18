# Mat Quick Start Guide

A NumPy-compatible matrix library for Scala with exact reproducibility and comprehensive linear algebra support.

## Installation
```scala
//> using dep org.vastblue:uni_3:0.11.0
```

## Basic Usage

### Creating Matrices
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.0

import uni.data.Mat
import uni.data.Mat.*

// From literal values (tuple syntax)
val m = Mat[Double]((1, 2, 3), (4, 5, 6))  // 2x3 matrix

// Column vector from scalars
val v = Mat(1.0, 2.0, 3.0)  // 3x1 column vector

// Common constructors
val zeros = Mat.zeros[Double](3, 4)         // All zeros
val ones = Mat.ones[Double](2, 5)           // All ones
val identity = Mat.eye[Double](3)           // Identity matrix
val filled = Mat.full[Double](3, 3, 7.0)    // All 7.0
val range = Mat.arange[Double](0, 10)       // [0..9] as column vector
val spaced = Mat.linspace(0, 1, 50)         // 50 points from 0 to 1

// Random matrices (NumPy-compatible)
Mat.setSeed(42)                             // Reproducible randomness
val uniform = Mat.rand(10, 10)              // Uniform [0, 1)
val normal = Mat.randn(10, 10)              // Standard normal N(0,1)
val randInt = Mat.randint(0, 100, 5, 5)     // Random integers [0, 100)
```

### Indexing & Slicing
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.0

import uni.data.Mat
import uni.data.Mat.*

val m = Mat[Double]((1, 2, 3), (4, 5, 6))

// Basic indexing
val value = m(0, 1)                   // Element at row 0, col 1
val value2 = m(-1, -1)                // Last element (negative indexing)

// Slicing (:: means "all")
val row = m(0, ::)                    // First row
val col = m(::, 2)                    // Third column
val sub = m(0 until 2, 1 until 3)     // Submatrix [rows 0-1, cols 1-2]

// Fancy indexing with arrays
val indices = Array(0, 2, 1)
val reordered = m(indices, ::)        // Select and reorder rows

// Boolean masking
val mask = m > 5.0
val filtered = m(mask)                // Elements > 5.0
m(mask) = 0.0                         // Set matching elements to 0
```

### Arithmetic Operations
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.0

import uni.data.Mat
import uni.data.Mat.*

val A = Mat[Double]((1, 2), (3, 4))
val B = Mat[Double]((5, 6), (7, 8))

// Element-wise operations (with broadcasting)
val sum = A + B                       // Element-wise addition
val diff = A - B                      // Element-wise subtraction
val prod = A * B                      // Element-wise multiplication
val quot = A / B                      // Element-wise division

// Scalar operations
val scaled = A * 2.0                  // Multiply all elements by 2
val shifted = A + 10.0                // Add 10 to all elements

// Matrix multiplication
val matmul = A *@ B                   // or A.dot(B)

// Broadcasting with vectors
val rowVec = Mat.row[Double](1, 2)
val result = A + rowVec               // Adds rowVec to each row
```

### Linear Algebra
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.0

import uni.data.Mat
import uni.data.Mat.*

val A = Mat[Double]((1, 2), (3, 4))
val v = Mat.col[Double](1.0, 2.0, 3.0)

// Basic operations
val AT = A.T                          // Transpose
val inv = A.inverse                   // Matrix inverse
val det = A.determinant               // Determinant
val tr = A.trace                      // Trace (sum of diagonal)

// Solve Ax = b
val b = Mat[Double](1, 2)
val x = A.solve(b)                    // Returns solution x

// Decompositions
val (Q, R) = A.qrDecomposition        // QR decomposition
val (U, s, Vt) = A.svd                // Singular Value Decomposition
val (vals, valsImag, vecs) = A.eig    // Eigenvalues and eigenvectors

// Norms and distances
val norm = v.norm                     // L2 norm of vector
val frobNorm = A.norm("fro")          // Frobenius norm
```

### Statistics
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.0

import uni.data.Mat
import uni.data.Mat.*

val m = Mat.randn[Double](5, 4)

// Reductions
val min = m.min                       // Minimum element
val max = m.max                       // Maximum element
val total = m.sum                     // Sum of all elements
val avg = m.mean                      // Average
val stdDev = m.std                    // Standard deviation
val med = m.median                    // Median value

// Axis-wise operations
val colSums = m.sum(axis = 0)         // Sum each column (returns 1×cols)
val rowMeans = m.mean(axis = 1)       // Mean of each row (returns rows×1)
val colMax = m.max(axis = 0)          // Max of each column

// Index of extremes
val (minRow, minCol) = m.argmin       // Position of minimum
val (maxRow, maxCol) = m.argmax       // Position of maximum

// Distribution functions
val p50 = m.percentile(50)            // 50th percentile (median)
val p90 = m.percentile(90)            // 90th percentile
```

### Element-wise Math Functions
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.0

import uni.data.Mat
import uni.data.Mat.*

val m  = Mat.randn[Double](5, 4)
val m1 = Mat.randn[Double](5, 4)
val m2 = Mat.randn[Double](5, 4)

// Basic math
val absM = m.abs                      // Absolute value
val sqrtM = m.sqrt                    // Square root
val expM = m.exp                      // e^x
val logM = m.log                      // Natural log
val log10M = m.log10                  // Base-10 log

// Trigonometric
val sinM = m.sin                      // Sine
val cosM = m.cos                      // Cosine
val tanhM = m.tanh                    // Hyperbolic tangent

// Rounding
val rounded = m.round(decimals = 2)   // Round to 2 decimals
val floored = m.floor                 // Floor
val ceiled = m.ceil                   // Ceiling
val truncated = m.trunc               // Truncate toward zero

// Clipping and bounds
val clipped = m.clip(0.0, 1.0)        // Clamp to [0, 1]
val maxElems = m1.maximum(m2)         // Element-wise max
val minElems = m1.minimum(m2)         // Element-wise min
```

### Machine Learning Functions
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.0

import uni.data.Mat
import uni.data.Mat.*

val m = Mat.randn[Double](5, 4)

// Activation functions
val sigmoid = m.sigmoid               // σ(x) = 1/(1+e^-x)
val relu = m.relu                     // max(0, x)
val leaky = m.leakyRelu(alpha = 0.01) // Leaky ReLU
val softmax = m.softmax(axis = 1)     // Softmax probabilities
val gelu = m.gelu                     // GELU activation

// Training utilities
val dropped = m.dropout(p = 0.5, training = true)  // Dropout with 50% rate

// Custom distributions
val custom = Mat.normal(mean = 5.0, std = 2.0, rows = 100, cols = 10)
```

### Data Manipulation
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.0

import uni.data.Mat
import uni.data.Mat.*

val m  = Mat.randn[Double](9, 6)    // 9 rows, 6 cols (54 elements)
val m1 = Mat.randn[Double](3, 6)
val m2 = Mat.randn[Double](3, 6)
val m3 = Mat.randn[Double](3, 6)

// Reshaping
val reshaped = m.reshape(6, 9)        // Change shape (must have same size: 9×6 = 6×9)
val flat = m.flatten                  // Convert to 1D array
val rowVec = m.toRowVec               // Reshape to 1×n
val colVec = m.toColVec               // Reshape to n×1

// Combining matrices
val vstacked = Mat.vstack(m1, m2, m3) // Stack vertically (rows)
val hstacked = Mat.hstack(m1, m2)     // Stack horizontally (cols)

// Splitting matrices
val parts = m.vsplit(Array(3, 7))     // Split at rows 3 and 7
val thirds = m.hsplit(3)              // Split into 3 equal column groups

// Repeating and tiling
val repeated = m.repeat(3, axis = 0)  // Repeat each row 3 times
val tiled = m.tile(2, 3)              // Tile 2 rows × 3 cols
```

### Comparison & Boolean Operations
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.0

import uni.data.Mat
import uni.data.Mat.*

val m = Mat.randn[Double](5, 4)

// Comparisons return Boolean matrices
val mask1 = m > 5.0                   // Greater than
val mask2 = m.lte(10.0)               // Less than or equal
val mask3 = m :== 0.0                 // Equal to

// Boolean reductions
val allPositive = (m > 0.0).all       // All elements > 0?
val hasNegative = (m < 0.0).any       // Any element < 0?
val colsAllPos = (m > 0.0).all(axis = 0)  // Which columns are all positive?

// NaN and infinity checks
val hasNaN = m.isnan                  // Boolean mask of NaN values
val hasInf = m.isinf                  // Boolean mask of ±∞ values
val finite = m.isfinite               // Boolean mask of finite values
val cleaned = m.nanToNum(nan = 0.0)   // Replace NaN with 0
```

### Display & Formatting
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.0

import uni.data.Mat
import uni.data.Mat.*

val m = Mat.randn[Double](5, 7)

// Default display (auto-formatted)
println(m.show)
// 5x7 Mat[Double]:
//  (-0.117777,  2.514160,  0.548743, ..., 0.637903,  0.542394),
//  ( 0.357531, -0.524160, -1.372789, ..., -0.733720,  0.074211),
//  ...

// Custom format
println(m.show("%.2f"))               // 2 decimal places

// Configure display options
Mat.setPrintOptions(
  edgeItems = 5,                      // Show 5 rows/cols on each edge
  maxRows = 20,                       // Truncate after 20 rows
  maxCols = 20                        // Truncate after 20 cols
)
```

### Data Loading & Persistence

`uni.data` provides seamless CSV integration via extension methods on both `java.nio.file.Path` and `Mat`.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.0

import uni.*
import uni.data.*

// Paths.get() is portable across Windows/MSYS2/Cygwin/Linux/WSL/MacOS
val path = Paths.get("data.csv")

// 1. Saving from Mat (write first so the file exists for reading)
val m = MatD.randn(10, 5)
m.writeCsv(path)                 // Save as comma-separated

// 2. Loading from Path
val m1: MatD = path.readCsv      // Load as Mat[Double] (alias for MatD)
val m2: MatB = path.readCsvB     // Load as Mat[Big]    (alias for MatB)
val m3: MatF = path.readCsvF     // Load as Mat[Float]  (alias for MatF)

// 3. Tab-separated
m.writeCsv(path, sep = "\t")     // Save as tab-separated
```

### Working with Different Types
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.0

import uni.data.*

// Mat works with Double, Float, and Big (BigDecimal)
val doubles = Mat[Double]((1.5, 2.5), (3.5, 4.5))
val floats = Mat[Float]((1.5f, 2.5f), (3.5f, 4.5f))
val bigs = Mat[Big]((Big(1.5), Big(2.5)), (Big(3.5), Big(4.5)))

// Automatic Int → Double conversion
val m = Mat[Double]((1, 2), (3, 4))   // Ints promoted to Double
```

### NumPy Translation Examples
```python
# NumPy
import numpy as np
np.random.seed(42)
X = np.random.randn(100, 10)
y = np.random.randn(100, 1)
weights = np.linalg.lstsq(X, y)[0]
pred = X @ weights
```
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.0

import uni.data.Mat
import uni.data.Mat.*

Mat.setSeed(42)
val X = Mat.randn(100, 10)
val y = Mat.randn(100, 1)
val (weights, _, _, _) = X.lstsq(y)
val pred = X *@ weights
```

## Key Differences from NumPy

1. **Type parameters**: `Mat[Double]` vs NumPy's inferred types
2. **Matrix multiplication**: `*@` or `.dot()` vs NumPy's `@`
3. **Ranges**: `0 until 5` vs NumPy's `0:5`
4. **All syntax**: `::` vs NumPy's `:`
5. **Equality**: `:==` vs NumPy's `==` (to avoid conflicts with Scala's ==)

## Next Steps

- See the [Full API Reference](ReferenceGuide.md) for a complete method list.
- Check the [README](../README.md) for a high-level overview.
- Check the test suite for advanced usage examples.
- Use `numpy2mat.sc` translator for automatic NumPy→Scala conversion.

## Performance Tips

1. Use `*@` for matrix multiplication (BLAS-optimized for Double/Float)
2. Avoid unnecessary `reshape` - use views when possible
3. Set seed once at program start for reproducibility
4. Use broadcasting instead of explicit loops
5. Chain operations for better performance (fewer intermediate copies)

---

**1461 comprehensive tests** • **~99% NumPy API coverage** • **Exact reproducibility**
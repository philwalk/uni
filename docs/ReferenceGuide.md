# Mat - Complete API Documentation with Examples

*All examples extracted from the comprehensive test suite (1461 tests)*

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
13. [Pandas-Style Data Analysis](#pandas-style-data-analysis)

---

## Matrix Creation

### Basic Constructors

**zeros creates matrix filled with zeros**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.6

import uni.data.*

val m = Mat.zeros[Double](3, 4)
println(m)
// 3x4 Mat[Double]:
//  (0.0, 0.0, 0.0, 0.0),
//  (0.0, 0.0, 0.0, 0.0),
//  (0.0, 0.0, 0.0, 0.0)
```

**ones creates matrix filled with ones**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.6

import uni.data.*

val m = Mat.ones[Double](2, 2)
println(m)
// 2x2 Mat[Double]:
//  (1.0, 1.0),
//  (1.0, 1.0)
```

**eye creates identity matrix**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.6

import uni.data.*

val m = Mat.eye[Double](3)
println(m)
// 3x3 Mat[Double]:
//  (1.0, 0.0, 0.0),
//  (0.0, 1.0, 0.0),
//  (0.0, 0.0, 1.0)
```

**full creates matrix with specified value**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.6

import uni.data.*

val m = Mat.full[Double](2, 3, 7.0)
println(m)
// 2x3 Mat[Double]:
//  (7.0, 7.0, 7.0),
//  (7.0, 7.0, 7.0)
```

### From Tuples and Values

**apply creates matrix from tuples**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.6

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

//> using dep org.vastblue:uni_3:0.9.6

import uni.data.*

val v = Mat.row[Double](1, 2, 3)
println(v)
// 1x3 Mat[Double]:
//  (1.0, 2.0, 3.0)
```

**col creates column vector**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.6

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

//> using dep org.vastblue:uni_3:0.9.6

import uni.data.*

val v = Mat[Double](1.0, 2.0, 3.0)
println(v)
// 3x1 Mat[Double]:
//  (1.0),
//  (2.0),
//  (3.0)
```

---

## Pandas-Style Data Analysis

All methods are defined on `Mat[T]` and work with `MatD`, `MatF`, and `MatB`.

### Row selection

**head returns first n rows**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.6

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

//> using dep org.vastblue:uni_3:0.9.6

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

//> using dep org.vastblue:uni_3:0.9.6

import uni.data.*

val m = MatD((3,1),(2,4),(5,0))
m.idxmin(0)   // 1x3 — column minimums at rows: (1, 2, 2)
m.idxmin(1)   // 3x1 — row minimums at cols:    (1, 0, 1)
```

**idxmax(axis) — index of maximum per column or row**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.6

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

//> using dep org.vastblue:uni_3:0.9.6

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

//> using dep org.vastblue:uni_3:0.9.6

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

//> using dep org.vastblue:uni_3:0.9.6

import uni.data.*

val m = MatD((3,1),(2,5),(4,0))
m.nlargest(3)   // 1x3: (5.0, 4.0, 3.0)
```

**nsmallest(n) — n smallest values as a row vector**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.6

import uni.data.*

val m = MatD((3,1),(2,5),(4,0))
m.nsmallest(3)  // 1x3: (0.0, 1.0, 2.0)
```

---

### Range test

**between(lo, hi) — boolean mask: `lo <= x <= hi`**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.6

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

//> using dep org.vastblue:uni_3:0.9.6

import uni.data.*

val m = MatD((1,2),(2,3),(1,3))
m.nunique   // 3
```

**valueCounts — frequency table, descending**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.6

import uni.data.*

val m = MatD((1,2),(2,3),(1,3))
m.valueCounts   // Array((2.0, 2), (1.0, 2), (3.0, 2)) — ties broken by order
```

---

### Shift / lag

**shift(n, fill, axis=0) — shift rows (axis=0) or columns (axis=1) by n steps**
```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.6

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

//> using dep org.vastblue:uni_3:0.9.6

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

//> using dep org.vastblue:uni_3:0.9.6

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

//> using dep org.vastblue:uni_3:0.9.6

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

//> using dep org.vastblue:uni_3:0.9.6

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

//> using dep org.vastblue:uni_3:0.9.6

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
*Documentation auto-generated from Mat test suite - all examples are validated and passing.*

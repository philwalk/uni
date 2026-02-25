# Mat - NumPy-Compatible Matrix Library for Scala

A comprehensive matrix library with 99% NumPy API compatibility, exact random number reproducibility, and production-ready linear algebra operations.

## Documentation

- **[Quick Start Guide](Linear-Algebra-With-uni.data-Quick-Start-Guide.md)** - Get started with common operations
- **[Complete API Reference](Linear-Algebra-With-uni.data-Reference-Guide.md)** - Detailed documentation with examples from 1461 tests

## Features

- ✅ **NumPy-compatible API** - Familiar syntax for NumPy users
- ✅ **Exact reproducibility** - Bit-for-bit match with NumPy's PCG64 RNG
- ✅ **BLAS-optimized** - Fast linear algebra operations
- ✅ **Type-safe** - Scala's type system for compile-time safety
- ✅ **Comprehensive** - 1461 tests covering all operations

## Quick Example
```scala
import uni.data.Mat
import uni.data.Mat.*

// Create matrices
val A = Mat[Double]((1, 2), (3, 4))
val B = Mat.randn(10, 10)

// Linear algebra
val inv = A.inverse
val (U, s, Vt) = B.svd

// Broadcasting
val v = Mat.row[Double](1, 2, 3)
val result = A + v  // Broadcasts to each row
```

## Installation
```scala
libraryDependencies += "org.someorg" %% "mat" % "0.9.0"
```

## Table of Contents

- [Quick Start Guide](Linear-Algebra-With-uni.data-Quick-Start-Guide.md)
  - Creating Matrices
  - Indexing & Slicing
  - Arithmetic Operations
  - Linear Algebra
  - Statistics
  - Machine Learning Functions
  - Random Number Generation
  
- [Complete API Reference](Linear-Algebra-With-uni.data-Reference-Guide.md)
  - Matrix Creation (87 tests)
  - Indexing and Slicing (34 tests)
  - Broadcasting (23 tests)
  - Linear Algebra (123 tests)
  - Statistical Functions (56 tests)
  - Element-wise Math (45 tests)
  - Machine Learning (18 tests)
  - And more...

## Links

- [NumPy Translation Guide](Linear-Algebra-With-uni.data-Quick-Start-Guide.md#numpy-translation-guide)
- [GitHub Repository](https://github.com/yourorg/mat)
- [API Scaladoc](https://javadoc.io/doc/org.someorg/mat_3)

## License

Apache 2.0

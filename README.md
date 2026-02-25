# Scala Data Scripting Library

A comprehensive Scala library for data processing, and scientific computing.

## Overview

A scala toolkit for linear algebra, with a NumPy-inspired API.

## Key Features

- 📊 **Data Import & Processing** - CSV parsing, date/time handling, data cleaning
- 🔢 **Linear Algebra** - NumPy-compatible matrix operations with 99% API coverage
- ⚡ **Performance** - BLAS-optimized operations, zero-copy views, efficient broadcasting
- ✅ **Reliability** - 1461+ comprehensive tests ensuring correctness
- 🖥️ **Portable Programming** - works everywhere, regardless of platform (Windows, macOS, Linux)

## Documentation

### 📥 [Importing Data](docs/Importing-Data.md)

Design to simplify working with data.

#### [NumPy-Inspired Linear Algebra](docs/Importing-Data.md#numpy-inspired-linear-algebra)
- Load matrices from CSV/TSV files
- Direct NumPy `.npy` file import
- Integration with Mat library
- Automatic type conversion

#### [Date and Time String Conversion](docs/Importing-Data.md#date-and-time-string-conversion)
- Parse various date/time formats to `LocalDateTime`
- Handle ISO 8601, RFC 3339, custom formats
- Timezone-aware parsing
- Flexible format detection

#### [Comma-Separated Files (CSV)](docs/Importing-Data.md#comma-separated-files)
- Fast CSV parsing with delimiter auto-detection
- Header detection and custom column names
- Type inference and conversion
- Memory-efficient streaming

### 🚀 [Portable Programming](docs/Portable-Programming.md)

- Cross-platform file operations

### 🔢 Linear Algebra

Complete NumPy-compatible matrix operations:

- **[Quick Start Guide](Linear-Algebra-With-uni.data-Quick-Start-Guide.md)** - Common operations and examples
- **[Complete API Reference](Linear-Algebra-With-uni.data-Reference-Guide.md)** - Detailed documentation with 1461 test examples

**Quick Example:**
```scala
import uni.data.Mat
import uni.data.Mat.*

// Create and manipulate matrices
val A = Mat[Double]((1, 2, 3), (4, 5, 6))
val B = Mat.randn(100, 10)

// NumPy-style operations
val inv = A.T @@ A.inverse
val (U, s, Vt) = B.svd
val masked = B(B > 0.5)
```

## Installation

### SBT
```scala
libraryDependencies += "org.someorg" %% "uni" % "0.9.0"
```

### Scala CLI
```scala
//> using dep org.someorg::uni:0.9.0
```

### Mill
```scala
ivy"org.someorg::uni:0.9.0"
```

## Quick Examples

### Portable File Operations
```scala
import uni.io.*

// Works on Windows, macOS, Linux
val home = os.home
val config = home / ".myapp" / "config.json"
os.write(config, """{"version": "1.0"}""")
```

### CSV Import with Date Parsing
```scala
import uni.csv.*
import uni.time.*

val data = CSV.read("sales.csv")
val dates = data.column("date").map(parseDateTime)
val sales = data.column("amount").map(_.toDouble)
```

### Linear Algebra Pipeline
```scala
import uni.data.Mat
import uni.data.Mat.*

// Load data
val X = Mat.fromCSV("features.csv")
val y = Mat.fromCSV("labels.csv")

// Train model
Mat.setSeed(42)
val (weights, _, _, _) = X.lstsq(y)

// Predict
val predictions = X @@ weights
```

## Module Overview

| Module | Description | Key Features |
|--------|-------------|--------------|
| `uni.io` | File I/O and OS operations | Cross-platform paths, file operations |
| `uni.csv` | CSV/TSV parsing | Fast parsing, type inference, streaming |
| `uni.time` | Date/time handling | Flexible parsing, timezone support |
| `uni.data` | Linear algebra (Mat) | NumPy-compatible, BLAS-optimized |
| `uni.cli` | Command-line tools | Argument parsing, validation |

## Comparison with Other Libraries

| Feature | uni | Breeze | ND4J | NumPy |
|---------|-----|--------|------|-------|
| NumPy API compatibility | ✅ 99% | ⚠️ Partial | ❌ No | ✅ 100% |
| Exact RNG reproducibility | ✅ Yes | ❌ No | ❌ No | ✅ Yes |
| Type safety | ✅ Yes | ✅ Yes | ✅ Yes | ❌ No |
| CSV import | ✅ Built-in | ❌ External | ❌ External | ✅ Built-in |
| Cross-platform scripting | ✅ Yes | ❌ No | ❌ No | ⚠️ Limited |
| Performance | ✅ BLAS | ✅ BLAS | ✅ Native | ✅ Native |

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## Testing

The library has comprehensive test coverage:
- **1461+ tests** for Mat (linear algebra)
- **500+ tests** for CSV parsing
- **200+ tests** for date/time handling
- **300+ tests** for portable I/O

Run tests:
```bash
sbt test
```

## Performance

Benchmarks on typical workloads (M1 Mac, 2021):
- Matrix multiplication (1000×1000): ~15ms (BLAS-optimized)
- CSV parsing (1M rows): ~1.2s (streaming)
- SVD decomposition (500×500): ~45ms

## License

Apache License 2.0 - see [LICENSE](LICENSE) for details.

## Acknowledgments

- NumPy team for API design inspiration
- Breeze contributors for Scala linear algebra foundations
- scala-cli team for excellent scripting experience

## Links

- [Documentation](docs/)
- [GitHub Repository](https://github.com/yourorg/uni)
- [API Documentation](https://javadoc.io/doc/org.someorg/uni_3)
- [Issue Tracker](https://github.com/yourorg/uni/issues)
- [Changelog](CHANGELOG.md)

---

**Version 0.9.0** | Built with Scala 3.7 | Tested on Windows, macOS, Linux

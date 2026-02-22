# Universal Scripting Library

A comprehensive Scala library for portable scripting, data processing, and scientific computing.

## Overview

The Universal Scripting Library (uni) provides a complete toolkit for writing portable, cross-platform scripts in Scala. Whether you're processing data files, performing linear algebra operations, or building command-line tools, uni offers a familiar, NumPy-inspired API with the safety and performance of Scala.

## Key Features

- 🖥️ **Portable Programming** - Write once, run anywhere (Windows, macOS, Linux)
- 📊 **Data Import & Processing** - CSV parsing, date/time handling, data cleaning
- 🔢 **Linear Algebra** - NumPy-compatible matrix operations with 99% API coverage
- ⚡ **Performance** - BLAS-optimized operations, zero-copy views, efficient broadcasting
- ✅ **Reliability** - 1461+ comprehensive tests ensuring correctness

## Documentation

### 🚀 [Portable Programming](docs/Portable-Programming.md)

Write scripts that work seamlessly across all platforms:
- Cross-platform file paths and operations
- Environment variable handling
- Process execution and piping
- Platform-specific conditionals
- Resource management

### 🎯 [Command Line Parsing](docs/Command-Line-Parsing.md)

Build professional command-line tools:
- Argument parsing with type safety
- Flag and option handling
- Subcommand support
- Automatic help generation
- Validation and error handling

### 📥 [Importing Data](docs/Importing-Data.md)

Comprehensive data import capabilities:

#### [Date and Time String Conversion](docs/Importing-Data.md#date-and-time-string-conversion)
- Parse various date/time formats to `LocalDateTime`
- Handle ISO 8601, RFC 3339, custom formats
- Timezone-aware parsing
- Flexible format detection

#### [Comma-Separated Files (CSV)](docs/Importing-Data.md#comma-separated-files)
- Fast CSV parsing with configurable delimiters
- Header detection and custom column names
- Type inference and conversion
- Handle quoted fields, escaped characters
- Memory-efficient streaming

#### [NumPy-Inspired Linear Algebra](docs/Importing-Data.md#numpy-inspired-linear-algebra)
- Load matrices from CSV/TSV files
- Direct NumPy `.npy` file import
- Integration with Mat library
- Automatic type conversion

### 🔢 Linear Algebra

Complete NumPy-compatible matrix operations:

- **[Quick Start Guide](Linear-Algebra-With-uni.data-Quick-Start-Guide.md)** - Common operations and examples
- **[Complete API Reference](Linear-Algebra-With-uni.data-Reference-Guide.md)** - Detailed documentation with 1461 test examples

**Quick Example:**
```scala
TODO
```
## Documentation

### 📚 [Quick Start Guide](Linear-Algebra-With-uni.data-Quick-Start-Guide.md)

Essential operations to get started:
- [Installation](Linear-Algebra-With-uni.data-Quick-Start-Guide.md#installation)
- [Creating Matrices](Linear-Algebra-With-uni.data-Quick-Start-Guide.md#creating-matrices)
- [Indexing & Slicing](Linear-Algebra-With-uni.data-Quick-Start-Guide.md#indexing--slicing)
- [Linear Algebra](Linear-Algebra-With-uni.data-Quick-Start-Guide.md#linear-algebra)
- [NumPy Translation Examples](Linear-Algebra-With-uni.data-Quick-Start-Guide.md#numpy-translation-examples)

### 📖 [Complete API Reference](Linear-Algebra-With-uni.data-Reference-Guide.md)

Comprehensive documentation with examples from all 1461 tests:
- [Matrix Creation](Linear-Algebra-With-uni.data-Reference-Guide.md#matrix-creation) - 87 tests
- [Linear Algebra](Linear-Algebra-With-uni.data-Reference-Guide.md#linear-algebra) - 123 tests
- [Statistical Functions](Linear-Algebra-With-uni.data-Reference-Guide.md#statistical-functions) - 56 tests
- [Machine Learning](Linear-Algebra-With-uni.data-Reference-Guide.md#machine-learning) - 18 tests
- [And more...](Linear-Algebra-With-uni.data-Reference-Guide.md#table-of-contents)

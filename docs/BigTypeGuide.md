# uni.data.Big Guide

The ```Big``` type in ```uni.Mat``` is a high-precision numeric type designed to provide the accuracy of ```BigDecimal``` with the convenience of standard numeric types. It is implemented as a Scala 3 **Opaque Type**, ensuring zero-overhead at runtime while providing a safe, custom API.

## Key Concept: BigNaN (The BigDecimal NaN)

Standard ```BigDecimal``` does not have a concept of "Not a Number" (NaN). If you divide by zero or encounter an invalid string, it throws an exception. ```uni.data.Big``` introduces ```BigNaN```:

* **Safe Arithmetic:** Operations involving ```BigNaN``` result in ```BigNaN``` (poisoning), similar to how ```Double.NaN``` works.
* **Division Safety:** Dividing a ```Big``` value by zero returns ```BigNaN``` instead of crashing your application.
* **Validation:** Use ```n.isNaN``` or ```n.isNotNaN``` to check the validity of a result.

## Usage

### 1. Creation and Conversions

You can create ```Big``` instances from strings, integers, longs, doubles, or via implicit conversion.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.2

import uni.data.*

val b1 = big("123.456")  // From String via factory function
val b2 = 100.asBig       // Int extension method
val b3 = 42.0.asBig      // Double extension method
val b4 = "99.9".asBig    // String extension method
val b5: Big = 42.0       // Implicit conversion (Conversion[Double, Big])

// Extraction
val d: Double = b1.toDouble // Returns Double.NaN if b1 is BigNaN
```

### 2. Arithmetic & Operators

```Big``` supports all standard operators. If any operand is ```BigNaN```, the result is ```BigNaN```.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.2

import uni.data.*

val b1 = big("123.456")
val b2 = 100.asBig
val b3: Big = 42.0

val result = (b1 + b2) * 2 / b3

// Power operator (~^)
val squared = b1 ~^ 2
val root = b1 ~^ 0.5     // Fractional exponents use Double precision
val preciseRoot = b1.sqrt // Precise BigDecimal square root
```

### 3. Comparison

Comparisons are "BigNaN-aware." Any comparison against a ```BigNaN``` returns ```false```.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.2

import uni.data.*

val b1 = big("123.456")
val b2 = 100.asBig

if (b1 > b2) {
  println("Greater than")
}
```

## API Reference

### Construction
| Method | Description |
| :--- | :--- |
| ```Big("1.23")``` | Safe parsing (returns ```BigNaN``` on failure) |
| ```big("1.23")``` | Lowercase factory, same safe parsing |
| ```big(1.23)``` / ```big(42)``` / ```big(99L)``` | Factory from `Double`, `Int`, `Long` |
| ```"1.23".asBig``` | `String` extension method |
| ```42.asBig``` / ```1.5.asBig``` / ```99L.asBig``` | Extension method for `Int`, `Double`, `Long` |
| ```val b: Big = 42.0``` | Implicit conversion (`given Conversion[Double, Big]` etc.) |

### Safety Checks and Pattern Matching
| Method | Description |
| :--- | :--- |
| ```n.isNaN``` | Returns true if the value is ```BigNaN``` |
| ```n.isNotNaN``` | Returns true if the value is a valid number |
| ```case Big(v) => ...``` | Value extraction via `unapply` |
| ```case b: Big => ...``` | Type pattern matching via `TypeTest[Any, Big]` |

### Precision & Formatting
| Method | Description |
| :--- | :--- |
| ```n.setScale(s, mode)``` | Adjusts decimal places with a ```RoundingMode``` |
| ```n.toPlainString``` | Formats without scientific notation |
| ```n.sqrt``` | High-precision square root using ```DECIMAL128``` context |

## Why use Big instead of Double?

Choose ```Big``` for domains where **precision is critical** (e.g., finance or high-precision weights). It avoids floating-point rounding errors while preserving an intuitive, "safety-first" coding style.

---
© 2026 VastBlue.
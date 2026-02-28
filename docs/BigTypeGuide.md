# uni.data.Big Guide

The ```Big``` type in ```uni.Mat``` is a high-precision numeric type designed to provide the accuracy of ```BigDecimal``` with the convenience of standard numeric types. It is implemented as a Scala 3 **Opaque Type**, ensuring zero-overhead at runtime while providing a safe, custom API.

## Key Concept: BadNum (The BigDecimal NaN)

Standard ```BigDecimal``` does not have a concept of "Not a Number" (NaN). If you divide by zero or encounter an invalid string, it throws an exception. ```uni.data.Big``` introduces ```BadNum```:

* **Safe Arithmetic:** Operations involving ```BadNum``` result in ```BadNum``` (poisoning), similar to how ```Double.NaN``` works.
* **Division Safety:** Dividing a ```Big``` value by zero returns ```BadNum``` instead of crashing your application.
* **Validation:** Use ```n.isNaN``` or ```n.isNotNaN``` to check the validity of a result.

## Usage

### 1. Creation and Conversions

You can create ```Big``` instances from strings, integers, longs, or doubles.

```scala
import uni.data.Big
import uni.data.Big.*

val b1 = big("123.456")  // From String
val b2 = 100.asBig       // Extension method
val b3: Big = 42.0       // Implicit conversion

// Extraction
val d: Double = b1.toDouble // Returns Double.NaN if b1 is BadNum
```

### 2. Arithmetic & Operators

```Big``` supports all standard operators. If any operand is ```BadNum```, the result is ```BadNum```.

```scala
val result = (b1 + b2) * 2 / b3

// Power operator (~^)
val squared = b1 ~^ 2
val root = b1 ~^ 0.5     // Fractional exponents use Double precision
val preciseRoot = b1.sqrt // Precise BigDecimal square root
```

### 3. Comparison

Comparisons are "BadNum-aware." Any comparison against a ```BadNum``` returns ```false```.

```scala
if (b1 > b2) {
  println("Greater than")
}
```

## API Reference

### Construction
| Method | Description |
| :--- | :--- |
| ```Big("1.23")``` | Safe parsing (returns ```BadNum``` on failure) |
| ```big(1.23)``` | Lowercase factory method |
| ```.asBig``` | Extension method for ```Int```, ```Long```, and ```Double``` |

### Safety Checks
| Method | Description |
| :--- | :--- |
| ```n.isNaN``` | Returns true if the value is ```BadNum``` |
| ```n.isNotNaN``` | Returns true if the value is a valid number |
| ```unapply(n)``` | Allows pattern matching: ```case Big(value) => ...``` |

### Precision & Formatting
| Method | Description |
| :--- | :--- |
| ```n.setScale(s, mode)``` | Adjusts decimal places with a ```RoundingMode``` |
| ```n.toPlainString``` | Formats without scientific notation |
| ```n.sqrt``` | High-precision square root using ```DECIMAL128``` context |

## Why use Big instead of Double?

Use ```Big``` when **precision is non-negotiable** (e.g., Financial calculations, high-precision weights, or any domain where floating-point rounding errors are unacceptable), but you still want the "safety-first" coding style of the NumPy/Mat ecosystem.

---
© 2026 VastBlue.

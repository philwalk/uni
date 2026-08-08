# uni.data.Big — High-Precision Number Guide

`Big` is a Scala 3 **opaque type** alias for `BigDecimal`, defined inside `object Big`.
It provides `BigDecimal`-level precision with two practical additions: a `BigNaN` sentinel
(mirroring `Double.NaN`) and a comprehensive NaN-propagating arithmetic API.
At runtime there is no wrapping — a `Big` value *is* a `BigDecimal`.

```scala
import uni.data.*   // brings in Big, big, BigNaN, MatB, and all conversions
```

---

## The BigNaN Sentinel

Standard `BigDecimal` has no NaN — dividing by zero or parsing a bad string throws.
`Big` avoids this with a private sentinel value and propagation rules that mirror `Double.NaN`:

- Any arithmetic involving `BigNaN` returns `BigNaN`.
- Any comparison against `BigNaN` returns `false` (`compare` returns `0`).
- `toDouble` / `toFloat` return `Double.NaN` / `Float.NaN` for `BigNaN`.

```scala
val bad = BigNaN
val x   = bad + 1.0    // BigNaN
val y   = x > 0        // false
val d   = bad.toDouble // Double.NaN
```

---

## Construction

| Expression | Notes |
| :--- | :--- |
| `Big("1,234.56")` | Safe — strips `$` and `,`; returns `BigNaN` on parse failure |
| `big("1,234.56")` | Identical to `Big(str)` |
| `Big(d: Double)` / `big(d)` | Direct — no NaN guard |
| `Big(i: Int)` / `big(i)` | Direct |
| `Big(l: Long)` / `big(l)` | Direct |
| `Big(bd: BigDecimal)` / `big(bd)` | Wraps an existing `BigDecimal` |
| `42.asBig` / `1.5.asBig` / `99L.asBig` | Inline extension on `Int`, `Double`, `Long` — no NaN guard |
| `"99.9".asBig` | Calls `BigDecimal(s)` directly — **throws** on bad input, unlike `Big("str")` |
| `val b: Big = 42.0` | Implicit `Conversion[Double, Big]`; `NaN`/`Infinite` → `BigNaN` |
| `val b: Big = 42` | Implicit `Conversion[Int, Big]` |
| `val b: Big = 99L` | Implicit `Conversion[Long, Big]` |
| `val b: Big = 1.5f` | Implicit `Conversion[Float, Big]`; `NaN`/`Infinite` → `BigNaN` |

The implicit conversions for `Double` and `Float` are NaN-aware; `.asBig` extensions
and `Big(d: Double)` are not — prefer `Big("str")` / `big("str")` when input may be
malformed.

---

## Constants

All constants are available after `import uni.data.*`:

| Name | Value |
| :--- | :--- |
| `BigNaN` | Sentinel "not a number" |
| `zero` | `Big(0)` |
| `one` / `BigOne` | `Big(1)` |
| `ten` | `Big(10)` |
| `hundred` | `Big(100)` |

---

## Pattern Matching

```scala
// Value extractor — does NOT match BigNaN (unapply returns None)
n match
  case Big(v) => println(s"valid: $v")
  case _      => println("was BigNaN")

// Type pattern — matches any Big, including BigNaN
(x: Any) match
  case b: Big => println(s"some Big: $b")
  case _      => println("not a Big")
```

The `TypeTest[Any, Big]` given instance makes the type pattern work at runtime
without casting or reflection overhead.

---

## Arithmetic

All operators propagate `BigNaN` — if any operand is `BigNaN` the result is `BigNaN`.
Every binary operator accepts `Big`, `Int`, `Long`, and `Double` on the right-hand side.

| Operator | Notes |
| :--- | :--- |
| `+`, `-`, `*` | All numeric RHS types |
| `/` | All numeric RHS types; zero or non-finite denominator → `BigNaN` |
| `unary_-` | Negation; `BigNaN`-safe |

**Left-hand scalar operators:** `Int`, `Long`, `Float`, and `Double` support `* Big`,
`+ Big`, `- Big`, and `/ Big` without relying on implicit conversions:

```scala
val x = big("10.0")
val a = 3     * x    // Int    * Big
val b = 2.0   * x    // Double * Big
val c = 100L  / x    // Long   / Big
```

**Power and roots:**

```scala
val squared   = b ~^ 2        // integer exponent — full BigDecimal precision
val cubeRoot  = b ~^ (1.0/3)  // fractional exponent — Double precision fallback
val sqrtExact = b.sqrt         // square root via DECIMAL128 (always high-precision)
```

**Other numeric methods:**

| Method | Description |
| :--- | :--- |
| `abs` | Absolute value; `BigNaN`-safe |
| `max(that: Big)` | Larger of two values; `BigNaN`-safe |
| `min(that: Big)` | Smaller of two values; `BigNaN`-safe |
| `signum` | `−1`, `0`, or `1` |

---

## Comparisons

All comparisons return `false` if either operand is `BigNaN`.
The right-hand side can be `Big`, `Double`, `Long`, or `Int`.

```scala
b1 < b2         // Big vs Big
b1 >= 100.0     // Big vs Double
b1 > 0          // Big vs Int
b1.compare(b2)  // Int: negative/0/positive; 0 if either is BigNaN
```

---

## NaN Checks

| Method / Function | Description |
| :--- | :--- |
| `n.isNaN` | `true` if `n` is `BigNaN` |
| `n.isNotNaN` | `true` if `n` is a valid number |
| `BigUtils.isBad(n)` | Same as `n.isNaN` |
| `BigUtils.orBad(opt)` | Unwrap `Option[Big]`; default to `BigNaN` |

---

## Conversions Out

| Method | Returns | `BigNaN` behaviour |
| :--- | :--- | :--- |
| `n.toDouble` | `Double` | `Double.NaN` |
| `n.toFloat` | `Float` | `Float.NaN` |
| `n.toInt` | `Int` | Not `BigNaN`-safe — do not call on `BigNaN` |
| `n.toLong` | `Long` | Not `BigNaN`-safe — do not call on `BigNaN` |
| `n.value` / `n.toBigDecimal` | `BigDecimal` | Underlying sentinel `BigDecimal` |
| `BigUtils.big2double(n)` | `Double` | `Double.NaN` |

---

## Formatting and Precision

| Method / Function | Description |
| :--- | :--- |
| `n.toPlainString` | String without scientific notation |
| `n.setScale(scale, roundingMode)` | Adjust decimal places |
| `n.isValidInt` | `true` if representable as `Int` |
| `n.isValidLong` | `true` if representable as `Long` |
| `BigUtils.numStr(n, fmt)` | Column-aligned string; `BigNaN` → `"N/A"` |
| `BigUtils.numStrPct(n, fmt)` | Percentage format; `BigNaN` → `"N/A"` |
| `BigUtils.num2string(n, dec, factor)` | Simple decimal + scale format |

**`NumFormat`** controls `numStr` / `numStrPct` output:

| Preset | Description |
| :--- | :--- |
| `NumFormat.Default` | 9 columns, 2 decimal places, no suffix |
| `NumFormat.Abbrev` | Abbreviates with `B` / `M` suffix for large values |
| `NumFormat.Percent` | Multiplies by 100, appends `%` |
| `NumFormat.IntPercent` | Integer percentage (3 columns, 0 decimal places) |

Custom `NumFormat`:

```scala
val fmt = NumFormat(colWidth = 12, dec = 4, factor = 100.0, suffix = "%")
println(BigUtils.numStr(big("0.1234"), fmt))  // "      12.3400%"
```

---

## Data Ingestion (`BigUtils`)

`BigUtils` is exported by `import uni.data.*` and provides tolerant parsing designed
for messy CSV/spreadsheet data.

```scala
import uni.data.*

getMostSpecificType("$1,234.56")  // Big(1234.56)
getMostSpecificType("(500.00)")   // Big(-500.00)   ← accounting negative
getMostSpecificType("7.5K")       // Big(7500)
getMostSpecificType("12.5%")      // Big(0.125)
getMostSpecificType("2024-01-15") // DateTime(...)
getMostSpecificType("foo")        // "foo"           ← String passthrough
```

| Function | Signature | Description |
| :--- | :--- | :--- |
| `getMostSpecificType` | `String → String \| Big \| DateTime` | Promote raw cell to most specific type |
| `isNumeric` | `String → Boolean` | Whether a string is parseable as a number |
| `str2num` | `String → Big` | Parse numeric string; `BigNaN` on failure |

`getMostSpecificType` handles: plain numbers, parenthesised negatives `(500)`,
currency prefix `$`, thousands separator `,`, percentage `%`,
scale suffixes `K` / `M` / `B`, and date strings (delegated to `uni.time.parseDate`).

---

## `Mat[Big]` — MatB

`MatB = Mat[Big]` is the high-precision matrix type. All `MatD` operations are
available on `MatB`. Element display uses `toPlainString`; CSV output maps
`BigNaN` to `"N/A"`.

```scala
import uni.data.*

val m: MatB = MatB.zeros(3, 3)
val v: MatB = MatB.col(Big("1.5"), Big("2.5"), Big("3.5"))

// CSV round-trip
val p = "/tmp/precision.csv".asPath
p.writeCsv(m)
val m2: MatB = p.readCsvB
```

---

## Type Class Integration

`object Big` provides `given Fractional[Big]`, making `Big` usable with all
standard Scala numeric abstractions (`sum`, `sorted`, `Numeric`-based APIs, etc.).
NaN propagation is honoured in all `Fractional` operations.

---

## When to Use Big Instead of Double

| | `Double` | `Big` |
| :--- | :--- | :--- |
| Precision | 15–17 significant digits | Arbitrary; `sqrt` uses DECIMAL128 |
| NaN safety | `Double.NaN` propagates | `BigNaN` propagates |
| Parse safety | throws or returns `NaN` | `Big("str")` returns `BigNaN` |
| Division by zero | returns `±Infinity` | returns `BigNaN` |
| Performance | hardware float | software arithmetic — much slower |

Use `Big` when accumulated rounding error matters — financial totals, high-precision
regression coefficients, or anywhere you need reproducible exact decimal arithmetic.
Use `Double` for large-scale numerical computation where throughput matters more than
the last few digits of precision.

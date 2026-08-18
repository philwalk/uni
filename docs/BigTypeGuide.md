# uni.data.Big — High-Precision Number Guide

`Big` is a Scala 3 **opaque type** alias for `BigDecimal`, defined inside `object Big`.
It provides `BigDecimal`-level precision with two practical additions: a `BigNaN` sentinel
(mirroring `Double.NaN`) and a comprehensive NaN-propagating arithmetic API.
At runtime there is no wrapping — a `Big` value *is* a `BigDecimal`.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.17.0

import uni.data.*   // brings in Big, big, BigNaN, MatB, and all conversions

val price: Big  = Big("19.99")
val qty         = big(3)              // `big` builds from Int/Long/Double/String too
val nan         = BigNaN
val m: MatB     = MatB.zeros(2, 2)
println(s"${price * qty} ${nan == BigNaN} ${m.shape}")
```

---

## The BigNaN Sentinel

Standard `BigDecimal` has no NaN — dividing by zero or parsing a bad string throws.
`Big` avoids this with a private sentinel value and propagation rules that mirror `Double.NaN`:

- Any arithmetic involving `BigNaN` returns `BigNaN`.
- Any comparison against `BigNaN` returns `false` (`compare` returns `0`).
- `toDouble` / `toFloat` return `Double.NaN` / `Float.NaN` for `BigNaN`.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.17.0

import uni.data.*

val bad = BigNaN
val x   = bad + 1.0    // BigNaN
val y   = x > 0        // false
val d   = bad.toDouble // Double.NaN
println(s"${x == BigNaN} $y ${d.isNaN}")
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
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.17.0

import uni.data.*

def describe(n: Big): String =
  // Value extractor — does NOT match BigNaN (unapply returns None)
  n match
    case Big(v) => s"valid: $v"
    case _      => "was BigNaN"

def kind(x: Any): String =
  // Type pattern — matches any Big, including BigNaN
  x match
    case b: Big => s"some Big: $b"
    case _      => "not a Big"

println(describe(Big("2.5")))   // valid: 2.5
println(describe(BigNaN))       // was BigNaN
println(kind(BigNaN))           // some Big: …
println(kind("text"))           // not a Big
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
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.17.0

import uni.data.*

val x = big("10.0")
val a = 3     * x    // Int    * Big
val b = 2.0   * x    // Double * Big
val c = 100L  / x    // Long   / Big
println(s"$a $b $c")   // 30.0 20.00 1E+1  (BigDecimal keeps the quotient's scale)
```

**Power and roots:**

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.17.0

import uni.data.*

val b = Big("2.5")
val squared   = b ~^ 2        // integer exponent — full BigDecimal precision
val cubeRoot  = b ~^ (1.0/3)  // fractional exponent — Double precision fallback
val sqrtExact = b.sqrt         // square root via DECIMAL128; BigNaN for a negative
println(s"$squared $cubeRoot $sqrtExact ${Big(-1).sqrt == BigNaN}")
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

The ordering operators (`<`, `<=`, `>`, `>=`) return `false` if either operand is `BigNaN`;
`compare` ranks the sentinel above every number and equal to itself, as `Double.compare`
does for NaN, so sorting, `min` and `max` behave as for doubles.
The right-hand side can be `Big`, `Double`, `Long`, or `Int`.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.17.0

import uni.data.*

val b1 = Big("12.5"); val b2 = Big("100")
println(b1 < b2)         // Big vs Big
println(b1 >= 100.0)     // Big vs Double
println(b1 > 0)          // Big vs Int
println(b1.compare(b2))  // Int: negative/0/positive; BigNaN ranks above every number
println(BigNaN > b1)     // false: ordering operators are false against the sentinel
println(BigNaN.compare(b1) > 0 && BigNaN.compare(BigNaN) == 0)   // …but compare orders it last
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
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.17.0

import uni.data.*

val fmt = NumFormat(colWidth = 12, dec = 4, factor = 100.0, suffix = "%")
println(numStr(big("0.1234"), fmt))  // "     12.3400%"
```

---

## Data Ingestion (`BigUtils`)

`BigUtils` is exported by `import uni.data.*` and provides tolerant parsing designed
for messy CSV/spreadsheet data.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.17.0

import uni.data.*

println(getMostSpecificType("$1,234.56"))  // 1234.56  (Big)
println(getMostSpecificType("(500.00)"))   // -500.00  (Big) ← accounting negative
println(getMostSpecificType("7.5K"))       // 7500.0   (Big)
println(getMostSpecificType("12.5%"))      // 0.125    (Big)
println(getMostSpecificType("2024-01-15")) // 2024-01-15T00:00 (DateTime)
println(getMostSpecificType("foo"))        // foo      (String passthrough)
```

| Function | Signature | Description |
| :--- | :--- | :--- |
| `getMostSpecificType` | `String → String \| Big \| DateTime` | Promote raw cell to most specific type |
| `isNumeric` | `String → Boolean` | Exactly "`str2num` can parse this" — one delegated definition (0.16.0), so the two cannot disagree. Accepts currency and grouping (`$1,234.56`); the decorated shapes `getMostSpecificType` handles (`5K`, `(100)`) are **not** `isNumeric` |
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
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.17.0

import uni.*
import uni.data.*

val m: MatB = MatB.zeros(3, 3)
val v: MatB = MatB.col(Big("1.5"), Big("2.5"), Big("3.5"))

// CSV round-trip
val p = (sys.props("java.io.tmpdir") + "/precision.csv").asPath
p.writeCsv(v)
val m2: MatB = p.readCsvB
println(s"${m.shape} ${m2.toArray.toList == v.toArray.toList}")
p.delete()
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

## Rust counterpart

**`rust/src/udata/big.rs`** ports the type with no dependency: an arbitrary-precision decimal
on base-10^9 limbs with `java.math.BigDecimal` semantics, chosen for accounting exactness.

The semantics worth knowing, identical in both languages and pinned by
`test-data/big-parity/` (335 recorded answers, `java.math.BigDecimal` as the oracle):

- **Construction never rounds.** A 35-digit literal keeps 35 digits and carries
  `MathContext(35, HALF_EVEN)` -- the context is `max(34, literal digits)`. Operators apply
  the **left operand's** context to the exact result, so 34-digit values round to 34 while a
  wider literal keeps its width. This is `scala.math.BigDecimal`'s actual behaviour, measured;
  it is not what a reading of `MathContext.DECIMAL128` suggests.
- `+`, `-`, `*` are exact up to that context; `/` and `sqrt` round, with Java's
  preferred-scale and trailing-zero rules (`2.50 / 0.25` is `10`).
- The BigNaN sentinel is recognised by numeric equality and survives every operation by
  short-circuit; it orders above every number (`compare`, like `Double.compare` for NaN), so
  a `MatB` sorts it last, `min` skips it and `max` returns it, while `<`/`>` against it are
  false; it is also the missing-cell value for the `Big` CSV loaders (`loadMatBig`,
  `loadMatB`, `loadSmartBig`, `readCsvB`, all ported).
- `toString` follows Java's notation rules, so scale is part of the rendering contract.

On `MatB` (and `MatD` alike) the ordering masks `gt`/`lt`/`gte`/`lte` are **false against
the sentinel**, while `:==`/`hasNaN` recognise it — so `m.gt(Big(0))` never selects a NaN
cell and `m :== BigNaN` finds them; `matmul` and every reduction propagate it.

`sqrt` of a negative and a negative `pow` exponent answer **BigNaN in both languages** as of
0.16.0 — Scala used to throw and adopted the port's answer, since a sentinel travelling as
data is the house style (compare BadDate, BadPath, `str2num`), and the fixture now pins those
rows. `Mat[Big]` is ported as well (`MatB` in the crate, on the same generic `Mat<T>` core
as `MatD`): the arithmetic, reductions, axis family, masks, `matmul`, LU `inverse`/
`determinant`/`solve`, `sort`, CSV text — pinned digit-for-digit (`toString`, scale
included) by 293 fixture rows.

Regenerate the fixture with `sbt "runMain uni.apps.BigParityGen"`, only when the change in
answers is intended.

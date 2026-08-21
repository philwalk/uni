# uni.time: Smart Date-Time Parsing

The `uni.time` package provides a robust, "smart" date-time parser designed to handle the messy, inconsistent date formats often found in real-world datasets. It eliminates the typical ETL step of writing format-specific regex cleaners before ingestion.

This is the reference for `uni.time` in client projects. For paths, subprocesses and argument parsing see [UniScriptingTools.md](UniScriptingTools.md).

## Key Features

- **Format Autodetection**: Automatically identifies dozens of common formats (ISO-8601, RFC-2822, US/European numeric, textual months, etc.) without requiring a format string.
- **Ambiguity Resolution**: Configurable handling of ambiguous numeric dates (e.g., `01/02/2024` as Jan 2nd vs Feb 1st), including strict modes that reject the other reading outright.
- **Thread-Safe Scoping**: Use `withDMY`, `withMDY` or `withDateOrder` to set parsing preferences for specific blocks of code or threads.
- **Resilient Tokenization**: Gracefully handles varied delimiters (hyphens, slashes, dots, spaces) and extraneous text like weekdays or timezone abbreviations.
- **No date-library dependency**: the date type is uni's own, so the parser carries no `java.time` coupling.

## The date type

`parseDate(String)` returns a **`UniDateTime`**, which is also available under the alias **`DateTime`**. Annotate with `DateTime` and your code follows uni's choice automatically.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.19.1

import uni.time.*

// Standard ISO
val d1: DateTime = parseDate("2024-03-14T15:30:00")

// Common US format
val d2 = parseDate("03/14/2024 3:30 PM")

// Textual month
val d3 = parseDate("14-Mar-2024")

// RFC-style with weekday and timezone
val d4 = parseDate("Thu, 14 Mar 2024 15:30:00 -0700")
```

`UniDateTime` is a plain case class of integer fields (`year`, `month`, `day`, `hour`, `minute`, `second`, `nano`, plus an optional `offsetMinutes`). Constructing one goes through `UniDateTime.of`, which validates — February 30th yields `BadDate` rather than throwing.

**Interop with `java.time` is implicit.** A `UniDateTime` converts to a `LocalDateTime` wherever one is expected, and back again, so passing a parsed date to a `java.time` API needs no ceremony:

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.19.1

import uni.time.*

val ldt: java.time.LocalDateTime = parseDate("2024-03-14")   // conversion applies
val back: DateTime = java.time.LocalDateTime.now()           // and in reverse
println(s"$ldt ${back.isValid}")   // 2024-03-14T00:00 true
```

Applying the conversion emits a *feature warning* under `-feature`, never an error. To avoid it, prefer uni's own constructors — `DateTime.of(y, m, d)` rather than `java.time.LocalDateTime.of(y, m, d, 0, 0, 0)`.

### The companion

The alias carries a matching object, so the familiar static calls work:

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.19.1

import uni.time.*

val instant = java.time.Instant.parse("2024-03-14T15:30:00Z")
val zone    = java.time.ZoneId.of("UTC")
DateTime.now()                       // current moment
DateTime.of(2024, 3, 14)             // time fields default to zero
DateTime.of(2024, 3, 14, 15, 30, 0)
DateTime.ofInstant(instant, zone)
DateTime.parse("Aug 18 22:29:47 2018")   // uni's lenient parser, not ISO-only
println(s"${DateTime.of(2024, 3, 14)} ${DateTime.of(2024, 3, 14, 15, 30, 0)} ${DateTime.ofInstant(instant, zone)} ${DateTime.parse("Aug 18 22:29:47 2018")}")
// 2024-03-14T00:00 2024-03-14T15:30 2024-03-14T15:30 2018-08-18T22:29:47
```

`DateTime.of` yields `BadDate` where `java.time.LocalDateTime.of` would throw. `TimeUtils.now` is also available unqualified as `now` (no parens — that spelling is historical).

## Sentinels instead of exceptions

`parseDate` is total. Unparseable input yields `BadDate`; empty input yields `EmptyDate`. That is what makes it usable across a CSV column without wrapping every cell in a `Try`.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.19.1

import uni.time.*

def handleBadRow(): Unit = println("bad row")
def use(d: DateTime): Unit = println(s"using $d")

for cell <- Seq("2024-03-14", "not a date") do
  val d = parseDate(cell)
  if d == BadDate then handleBadRow() else use(d)
  // or
  if !d.isValid then handleBadRow()
```

Three properties worth knowing:

**They are dates that cannot exist.** Both sentinels carry **day 0**, which occurs in no month. Since `of` validates, no parsed date can ever equal a sentinel — so a data file containing a real `1900-01-02` produces an ordinary date, not a false parse failure. (It used to be exactly that value, and 1900-01-02 is a common placeholder in financial data.)

**They print as markers.** `BadDate.toString` is `<BadDate>`, not a date-shaped string. A sentinel in a log line or a printed column is visible rather than reading as a genuine moment from 1900.

**But the pattern formatters still yield digits.** `fmt`, `ymd` and `toString(fmt)` feed filenames, CSV keys and SQL binds, so they keep producing digits — `BadDate.fmt("yyyyMMdd")` is `19000100`. Day zero makes that self-evidently not a date, but if you are building such a string, guard with `isValid` first.

**Arithmetic absorbs them**, the way `Double.NaN` does: `BadDate.plusDays(1)` is `BadDate`, and `BadDate.toEpochDay` is `Long.MinValue`.

## Formatting

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.19.1

import uni.time.*

val d = parseDate("2024-03-14 15:30:45")
println(d.ymd)                      // 2024-03-14
println(d.ymdhms)                   // 2024-03-14 15:30:45
println(d.fmt("yyyyMMdd"))          // 20240314
println(d.toString("MMM d, yyyy"))  // Mar 14, 2024
println(d.toString)                 // 2024-03-14T15:30:45   (ISO, seconds omitted when zero)
```

Pattern letters: `y` year, `M` month, `d` day, `H` hour (0-23), `h` hour (1-12), `m` minute, `s` second, `S` fraction, `a` AM/PM, `E` day of week. Repeat count sets width as in Java (`M`=3, `MM`=03, `MMM`=Mar, `MMMM`=March). Text in single quotes is literal.

Two deliberate differences from `DateTimeFormatter.ofPattern`:

- Month and weekday names are always **English**. `ofPattern` follows the JVM's default FORMAT locale, which makes output machine-dependent.
- Capital **`Y`** is **rejected** with an error rather than accepted. It is the locale-dependent week-based year, which differs from `y` only for a few days each December — so a typo silently renders a date a year out. Use lowercase `y`.

## Arithmetic

The usual shifts are available and return a `DateTime`, so results compose:

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.19.1

import uni.time.*

val d    = parseDate("2024-03-14 15:30:45")   // a Thursday
val inst = java.time.Instant.parse("2024-03-14T15:30:00Z")
d.plusDays(7); d.minusWeeks(2); d.plusMonths(1); d.plusYears(1)
d.withDayOfMonth(1); d.withHour(0)
d.atStartOfDay(); d.lastDayOfMonth
d.withDayOfWeek(java.time.DayOfWeek.FRIDAY)   // the NEXT such day
d.dayOfWeekNum                                 // 1 = Monday .. 7 = Sunday
d.dayOfWeekName                                // "Thu"    (three-letter, always English)
d.dayOfWeekFull                                // "Thursday"
d.toEpochDay                                   // days since 1970-01-01
DateTime.ofInstant(inst)
UniDateTime.ofEpochDay(19800)
println(s"${d.plusDays(7).ymd} ${d.lastDayOfMonth.ymd} ${d.withDayOfWeek(java.time.DayOfWeek.FRIDAY).ymd} ${d.dayOfWeekNum} ${d.dayOfWeekName} ${d.dayOfWeekFull} ${d.toEpochDay} ${UniDateTime.ofEpochDay(19800).ymd}")
// 2024-03-21 2024-03-31 2024-03-15 4 Thu Thursday 19796 2024-03-18
```

Month arithmetic clamps to the shorter month, as `java.time` does: January 31st plus one month is February 28th or 29th. An impossible result yields `BadDate` rather than throwing.

Comparison uses local fields: `<`, `<=`, `>`, `>=`, `isBefore`, `isAfter`, and an `Ordering[UniDateTime]` so `Seq[DateTime].sorted` works.

`dayOfWeekName` is the form most code actually wants, and replaces a hand-rolled
`getDisplayName(TextStyle.SHORT, Locale.ENGLISH)`. The explicit `Locale` in that idiom is
load-bearing: without it `getDisplayName` follows the JVM default and the same script yields
`lun.` on a French machine. These are English by construction, so there is nothing to get
wrong — and nothing to make the output depend on the host.

Interval helpers take dates in either representation: `daysBetween`, `secondsBetween`, `minutesBetween`, `hoursBetween`, `elapsedDays`, `getDuration`, `endOfMonth`.

## Configuring Ambiguous Dates

When a date like `02/11/2009` is encountered, it could be **February 11th** (MDY) or **November 2nd** (DMY). By default, `uni.time` prefers **MDY**.

You can override this behavior using scoped configuration blocks:

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.19.1

import uni.time.*

// Default (MDY)
parseDate("02/11/2009") // => 2009-02-11

// Explicitly Day-Month-Year
withDMY {
  parseDate("02/11/2009") // => 2009-11-02
}

// Scopes can be nested
withDMY {
  // DMY logic here...
  withMDY {
    // MDY logic here...
  }
}
```

Because these scopes use `scala.util.DynamicVariable`, they are **thread-safe**. You can have multiple threads parsing different datasets with different conventions simultaneously without interference.

### Strict order, when a column has one convention

`withDMY`/`withMDY` decide only the genuinely *ambiguous* case. An unambiguous date still overrides them — under `withMDY`, `04/12/1992` reads as April 12 while `13/12/1992` reads as December 13. For an internally consistent European column that yields a mix, with the wrong reading on every row whose day is 12 or less.

`withDateOrder` holds to one convention instead:

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.19.1

import uni.time.*

withDateOrder(DateOrder.DayFirst) {
  println(parseDate("04/12/1992").ymd)   // 1992-12-04, day-first even though unambiguous
  println(parseDate("12/04/1992").ymd)   // 1992-04-12
}
```

- `DateOrder.Auto` — unambiguous input wins, ambiguous input uses the MDY/DMY preference. **The default**, so nothing has to be declared.
- `DateOrder.MonthFirst` — month first always; a date only readable day-first yields `BadDate`.
- `DateOrder.DayFirst` — day first always; a date only readable month-first yields `BadDate`.

The strict modes make a wrong-convention row a *visible* `BadDate` rather than a plausible wrong date, which is usually what you want when ingesting a file whose convention you know.

## Integration with BigData Prep

The date parser is integrated into `uni.data.BigUtils.getMostSpecificType`. This means when you load a CSV or a raw sequence of strings, dates are automatically promoted to `DateTime` objects while numbers are promoted to `Big`.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.19.1

import uni.data.*   // exports BigUtils; importing both makes the names ambiguous

val raw = Seq("2024-03-14", "$1,234.56", "7.5K")
val typed = raw.map(getMostSpecificType)
// => Seq(UniDateTime(2024, 3, 14...), Big(1234.56), Big(7500))
```

The `CVD` cell-value union is `UniDateTime | Big | Option[Int] | String | Int`, and `toStr` renders a date member as `yyyy-MM-dd`.

## Supported Patterns (Examples)

`SmartParse` classifies tokens and is the only parser — an earlier `ChronoParse` fallback was measured against the project's date corpus, rescued nothing `SmartParse` failed, and was removed. The two formats it alone handled (bare `yyyyMMdd`, and a leading time) are folded into normalization.

| Example String | Interpretation |
| :--- | :--- |
| `2024-03-14T15:30:00Z` | ISO-8601 |
| `03/14/2024` | MDY |
| `14/03/2024` | DMY (Autodetected by >12 rule) |
| `Mar 14, 2024` | Month Name |
| `14 Mar 2024` | Day Month Name |
| `Apr12-11` | Squashed Month/Day |
| `20240314` | Bare compact date |
| `2009/03/24 21:48:25.0` | YMD with fractional seconds |
| `Fri Jan 10 2014 2:34:17 PM EST` | Full textual with AM/PM and TZ |

A stated UTC offset (`-0700`, or a trailing `Z`) is captured in `offsetMinutes` rather than discarded. It is *not* applied to the local fields, and `toString` omits it — `UniDateTime` records the offset, it does not shift by it. Named abbreviations like `MST` are deliberately not mapped, since `CST` alone is three different offsets and resolving them needs a timezone database.

## Pitfalls

**`==` across the two date types is silently `false`.** `==` takes `Any`, so no conversion applies and the compiler says nothing:

```text
val d: DateTime = parseDate(s)
if d == java.time.LocalDateTime.of(1900, 1, 2, 3, 4, 5) then ...   // always false
```

Keep both sides on `DateTime`. Comparing against `BadDate`/`EmptyDate` is safe because those are `UniDateTime` too. In tests, munit's `assertEquals` requires a `Compare` instance and so catches the mistake at compile time — plain `==` does not.

**A `java.time.LocalDateTime` written in a generic position will not convert.** A conversion applies to a value, never through a type constructor:

```text
val ok: Seq[java.time.LocalDateTime] = lines.map(parseDate)   // fine: inference pushes inward
val xs = lines.map(parseDate)
val no: Seq[java.time.LocalDateTime] = xs                     // error: Seq[UniDateTime]
```

The same applies to `Option[LocalDateTime]`, an explicit `Ordering[LocalDateTime]`, and a `case d: LocalDateTime` test. Each is a compile error rather than a silent difference; write `DateTime` so the annotation tracks the alias.

**Mixing date types in one expression infers a union.** If one branch yields a `LocalDateTime` and another a `DateTime`, Scala 3 infers `LocalDateTime | UniDateTime`, which satisfies neither position and which no conversion repairs. Keep a given expression on one type.

## Implementation Details

- **No Exceptions**: `parseDate` returns `BadDate` instead of throwing, making it safe for use in data pipelines. `quikDate` is the strict counterpart for compact `yyyyMMdd` — it throws on malformed input.
- **Normalization**: Input strings are pre-normalized to remove Unicode punctuation and standardize whitespace before tokenization.
- **Formatting** goes through `uni.time.DateFormat`, which works on the field values rather than a `java.time` object. That is what decouples the date type from `java.time`.

## Rust counterpart

**`rust/src/utime/`** ports the date type and its formatter:

| Scala | Rust |
| :--- | :--- |
| `uni.time.UniDateTime` | `utime::datetime::UniDateTime` |
| `uni.time.DateFormat` | `utime::format` |
| `uni.time.SmartParse`, `parseDate` | `utime::smartparse` |
| `Path.lastModifiedTime`, `.lastMod*Ago` | `upath::times` |

**Method names match the Scala spelling** — `plusDays`, `dayOfWeekName`, `lastModifiedTime` —
not snake_case, so a script kept in both languages needs no mental translation. Internal
helpers stay snake_case, which means the case tells you whether a Scala counterpart exists.
See `docs/PathIOReference.md` for the full rule.

File timestamps are **UTC** on both sides, with no zone or offset parameter. That was the one
place the two languages could not have matched: `std` has no timezone database, so a
system-local `lastModifiedTime` was unreproducible. Defining it as UTC removed the problem
rather than working around it, and incidentally fixed a Scala inconsistency — `epoch2DateTime`
had always defaulted to UTC while `lastModifiedTime` used the system zone.

Same fields, same validation, same sentinels (day 0, `<BadDate>`), same epoch-day arithmetic —
and **no date-library dependency**, which was the point of decoupling the Scala type in the
first place. The alternative would have been a crate plus a translation layer from Java's
pattern letters to strftime codes.

`SmartParse` is ported as `utime::smartparse` -- `parseDateSmart(s)` reads a date string of
unknown format or answers `BAD_DATE`, exactly as in Scala, and `classify` / `numericDateOrder`
come with it. Two adaptations, both visible in the signatures rather than the behaviour:

- **Configuration is a parameter.** The Scala consults the dynamically-scoped `timeConfig`;
  Rust has no dynamic scope, so `parseDateSmartWith(s, TimeConfig { monthFirst, order })` takes
  it explicitly and `parseDateSmart` fixes the default (`monthFirst = true`, `order = Auto`).
- **The fuzzy month scan has a defined order.** A typo within edit distance 1 of *two* months
  (`jum`) resolves by `Map` iteration order in Scala, which is unspecified; the Rust scans
  January to December. The parity fixture pins only unambiguous typos, on purpose.

Pinned by `test-data/smartparse-parity/` -- 539 recorded answers over the shared date corpus
plus one input per documented trap (the RFC 2822 layout, trailing `-0700` offsets, compact
`yyyyMMdd HHmm`, AM/PM edge cases, 2-digit-year expansion, fuzzy month words), under all four
day/month-order configurations for the ambiguous cases. Regenerate with
`sbt "Test/runMain uni.apps.SmartParseParityGen"`.

One difference in the arithmetic, and it matters for trust: the Scala delegates to `java.time`
for calendar math, while the Rust has nothing to delegate to and writes it out — Hinnant's
civil-calendar algorithms for epoch days, explicit month-end clamping, explicit carry for time
shifts. `test-data/date-parity/` pins the two together across 4020 cases, which makes
`java.time` the transitive oracle for the hand-rolled version. It earned its keep immediately:
it caught the port returning **2000-03-01 for 2000-02-29**, an off-by-one in the day-of-era
divisor that shows up only on the last day of a 400-year era.

Regenerate the fixture with `sbt "runMain uni.apps.DateParityGen"`, and only when the reference
is meant to move — both `uni.time.DateParitySuite` and `rust/tests/date_parity.rs` check
against it, so a change to either language fails until the other agrees.

The crate is named `uni` (`rust/Cargo.toml`), mirroring the Scala library; `utime` and
`upath` are modules within it.
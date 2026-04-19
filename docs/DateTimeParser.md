# uni.time: Smart Date-Time Parsing

The `uni.time` package provides a robust, "smart" date-time parser designed to handle the messy, inconsistent date formats often found in real-world datasets. It eliminates the typical ETL step of writing format-specific regex cleaners before ingestion.

## Key Features

- **Format Autodetection**: Automatically identifies dozens of common formats (ISO-8601, RFC-2822, US/European numeric, textual months, etc.) without requiring a format string.
- **Ambiguity Resolution**: Configurable handling of ambiguous numeric dates (e.g., `01/02/2024` as Jan 2nd vs Feb 1st).
- **Thread-Safe Scoping**: Use `withDMY` or `withMDY` to set parsing preferences for specific blocks of code or threads.
- **Resilient Tokenization**: Gracefully handles varied delimiters (hyphens, slashes, dots, spaces) and extraneous text like weekdays or timezone abbreviations.

## Basic Usage

The primary entry point is `parseDate(String)`, which returns a `java.time.LocalDateTime`.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.12.3

import uni.time.*

// Standard ISO
val d1 = parseDate("2024-03-14T15:30:00")

// Common US format
val d2 = parseDate("03/14/2024 3:30 PM")

// Textual month
val d3 = parseDate("14-Mar-2024")

// RFC-style with weekday and timezone
val d4 = parseDate("Thu, 14 Mar 2024 15:30:00 -0700")
```

## Configuring Ambiguous Dates

When a date like `02/11/2009` is encountered, it could be **February 11th** (MDY) or **November 2nd** (DMY). By default, `uni.time` prefers **MDY**.

You can override this behavior using scoped configuration blocks:

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.12.3

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

## Integration with BigData Prep

The date parser is integrated into `uni.data.BigUtils.getMostSpecificType`. This means when you load a CSV or a raw sequence of strings, dates are automatically promoted to `LocalDateTime` objects while numbers are promoted to `Big`.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.12.3

import uni.data.*
import uni.data.BigUtils.*

val raw = Seq("2024-03-14", "$1,234.56", "7.5K")
val typed = raw.map(getMostSpecificType)
// => Seq(LocalDateTime(2024, 3, 14...), Big(1234.56), Big(7500))
```

## Supported Patterns (Examples)

The parser uses a two-stage approach:
1. **SmartParse**: A fast, token-based classifier for standard patterns.
2. **ChronoParse**: A flexible fallback parser for more complex or non-standard arrangements.

| Example String | Interpretation |
| :--- | :--- |
| `2024-03-14T15:30:00Z` | ISO-8601 |
| `03/14/2024` | MDY |
| `14/03/2024` | DMY (Autodetected by >12 rule) |
| `Mar 14, 2024` | Month Name |
| `14 Mar 2024` | Day Month Name |
| `Apr12-11` | Squashed Month/Day |
| `2009/03/24 21:48:25.0` | YMD with fractional seconds |
| `Fri Jan 10 2014 2:34:17 PM EST` | Full textual with AM/PM and TZ |

## Implementation Details

- **No Exceptions**: `parseDate` returns a special `BadDate` constant (`1900-01-02T03:04:05`) instead of throwing exceptions, making it safe for use in data pipelines.
- **Normalization**: Input strings are pre-normalized to remove Unicode punctuation and standardize whitespace before tokenization.
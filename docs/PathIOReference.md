# Path & I/O Reference

Complete reference for all non-deprecated extension methods added to `java.nio.file.Path`, `java.io.File` (`JFile`), and `String` by `import uni.*`.

`java.io.File` (aliased as `JFile`) exposes the same extension methods as `Path` unless noted otherwise.

---

## String extensions

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.asPath` | `Path` | MSYS2-aware conversion via `uni.Paths` |
| `.absPath` | `Path` | absolute normalised `Path` |
| `.toFile` | `JFile` | convert to `java.io.File` |
| `.posx` | `String` | forward-slash form of the path string |
| `.posix` | `String` | absolute forward-slash form |
| `.local` | `String` | MSYS2-aware POSIX→Windows conversion |
| `.lc` | `String` | lowercase alias (`str.toLowerCase`) |
| `.uc` | `String` | uppercase alias (`str.toUpperCase`) |
| `.dropSuffix` | `String` | strip trailing `.ext`; hidden files (dot-first) unchanged |
| `.startsWithIgnoreCase(prefix)` | `Boolean` | case-insensitive prefix check |
| `.stripPrefix(prefix)` | `String` | strip prefix if present, otherwise return unchanged |
| `.readCsv` | `MatD` | load CSV from file path or HTTP/HTTPS URL as `Mat[Double]` |
| `.readCsvB` | `MatB` | load CSV as `Mat[Big]` |
| `.readCsvF` | `MatF` | load CSV as `Mat[Float]` |
| `.writeCsv(m)` | `Unit` | write `Mat[T]` to CSV (comma-separated) |
| `.writeCsv(m, sep)` | `Unit` | write `Mat[T]` to CSV with custom separator |

---

## Path extensions

### Existence and type

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.exists` | `Boolean` | true if the path exists |
| `.isFile` | `Boolean` | true if a regular file |
| `.isDirectory` | `Boolean` | true if a directory |
| `.isSymbolicLink` | `Boolean` | true if a symbolic link |
| `.isSameFile(other)` | `Boolean` | true if both paths refer to the same inode |
| `.newerThan(other)` | `Boolean` | true if this file was modified more recently than `other` |
| `.olderThan(other)` | `Boolean` | true if this file was modified less recently than `other` |

### Size and permissions

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.length` | `Long` | file size in bytes (0 if not found) |
| `.isEmpty` | `Boolean` | true if file size is 0 |
| `.nonEmpty` | `Boolean` | true if file size is > 0 |
| `.canRead` | `Boolean` | true if the file is readable |
| `.canExecute` | `Boolean` | true if the file is executable |

### Name and extension

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.last` | `String` | filename including extension (os-lib compatible) |
| `.baseName` | `String` | filename without extension (os-lib compatible) |
| `.ext` | `String` | extension without leading dot (os-lib compatible) |
| `.dotsuffix` | `String` | extension with leading dot, e.g. `".scala"` |
| `.extension` | `Option[String]` | extension without dot, or `None` if absent |

### Path string representations

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.posx` | `String` | forward-slash path string |
| `.posix` | `String` | absolute forward-slash path string |
| `.abs` | `String` | absolute normalised path with forward slashes |
| `.abspath` | `Path` | absolute normalised path as a `Path` |
| `.stdpath` | `String` | standardised path string |
| `.relpath` | `String` | path relative to the current working directory |
| `.relativePath` | `Path` | relative path to CWD as a `Path` |
| `.localpath` | `String` | native path (backslashes on Windows, forward slashes elsewhere) |
| `.local` | `String` | native path — alias for `.localpath` |
| `.dospath` | `String` | native Windows path format |
| `.noDrive` | `String` | path string without Windows drive letter (`C:/foo` → `/foo`) |

### Path segments and navigation

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.segments` | `IndexedSeq[String]` | all path name elements as strings |
| `.reversePath` | `String` | path segments in reverse order, joined with `/` |
| `.parent` | `Path` | absolute parent directory |
| `.parentPath` | `Path` | parent, resolving to absolute if needed |
| `.getParentPath` | `Path` | alias for `.parentPath` |
| `.getParentNonNull` | `Path` | parent, or self if parent is null |
| `.parentFile` | `JFile` | parent directory as `java.io.File` |
| `.asFile` | `JFile` | this path as `java.io.File` |
| `.realPath` | `Path` | canonical path resolving symlinks and mount points |

### Reading file contents

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.lines` | `Seq[String]` | all lines; UTF-8 with Latin-1 fallback; empty if not a file |
| `.lines(charset)` | `Seq[String]` | all lines with explicit charset name |
| `.linesStream` | `Iterator[String]` | lazy streaming lines; file handle closed on EOF; use `withLines` for partial reads |
| `.linesStream(charset)` | `Iterator[String]` | lazy streaming lines with explicit charset name |
| `.withLines(f)` | `A` | resource-safe bracket: opens iterator, calls `f`, closes on return or exception |
| `.withLines(charset)(f)` | `A` | resource-safe bracket with explicit charset name |

> **`withLines` matters less in the Rust port.** Scala's `linesStream` returns an
> `Iterator[String] & AutoCloseable` and leaks the file handle unless the caller closes it or runs
> it to exhaustion, which is why `withLines` exists beside it. The port's `LineStream` owns its
> reader, so the handle closes when the iterator drops — exhausted or abandoned.
> `withLines` is offered there for symmetry and scoping, not as a safety net.
| `.eachLine(f)` | `Unit` | calls `f` once per line; streaming, resource-safe |
| `.eachLine(charset)(f)` | `Unit` | per-line callback with explicit charset name |
| `.firstLine` | `String` | first line only (empty string if file is empty or absent) |
| `.contentAsString` | `String` | entire file as a string; UTF-8 with Latin-1 fallback |
| `.contentAsString(charset)` | `String` | entire file as a string with explicit `Charset` |
| `.byteArray` | `Array[Byte]` | raw file content as bytes |

> **A line-oriented reader never yields a carriage return.**
>
> `lines`, `linesStream`, `withLines`, `eachLine` and `firstLine` remove **every** `\r` from a
> line, not merely one before the `\n`. A residual carriage return inside a line is one of the
> larger classes of hard-to-diagnose bug: invisible in most output, it survives comparison and
> breaks a string match for no visible reason.
>
> Splitting on `\n` and discarding `\r` also makes the result independent of where a file came
> from. `\r\n` and `\n` land on the same lines, which matters because Windows emits either, so
> the same content read on Linux and Windows yields identical lines.
>
> **`contentAsString` and `byteArray` are the escape hatch** and keep the bytes exactly. When
> carriage returns are the point — inspecting line endings, or a quoted CSV field that
> genuinely contains one — take the content and split it by whatever rule the context
> calls for. That is deliberately the only way to see a `\r` through this API.
>
> Stricter than either host language: Java's `BufferedReader.readLine` and Rust's
> `BufRead::lines` each strip only the terminator.

### CSV

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.csvRows` | `Seq[Seq[String]]` | all parsed CSV rows |
| `.csvRowsStream` | `Iterator[Seq[String]]` | streaming CSV rows; suitable for large files |
| `.csvRowsAsync` | `Iterator[Seq[String]]` | async streaming variant |
| `.csvRows(onRow)` | `Unit` | callback-per-row variant |
| `.delim` | `String` | auto-detect column separator (comma, tab, semicolon, pipe); empty if undetected |

### Matrix loading and saving

Requires `import uni.data.*` for the return types (`MatD`, `MatB`, `MatF`).

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.readCsv` | `MatD` | parse CSV into a `Mat[Double]` |
| `.readCsvB` | `MatB` | parse CSV into a `Mat[Big]` |
| `.readCsvF` | `MatF` | parse CSV into a `Mat[Float]` |
| `.writeCsv(m)` | `Unit` | write `Mat[T]` to CSV (comma-separated) |
| `.writeCsv(m, sep)` | `Unit` | write `Mat[T]` to CSV with custom separator |
| `.loadMatD` | `MatD` | parse CSV into a `Mat[Double]` (alias) |
| `.loadMatBig` | `MatB` | parse CSV into a `Mat[Big]` |
| `.loadMatB` | `MatB` | alias for `.loadMatBig` |
| `.loadMatF` | `MatF` | parse CSV into a `Mat[Float]` |

> **These are header-aware.** `readCsv` is `loadMatD`, which is `loadSmart(p, map).mat` — and
> `loadSmart` detects a header row and excludes it from `mat`. A two-data-row file with a header
> yields a 2-row matrix, not 3. Nothing is lost for a headerless file: row 0 is dropped only when
> it looks like a header.
>
> Use `loadSmartD` (or `loadSmartBig`) when the column names are wanted as well — it returns a
> `MatResult` carrying `headers` alongside `mat`.
>
> Note `MatB` is `Mat[Big]`, **not** `Mat[Boolean]`, so `readCsvB`/`loadMatB` are the arbitrary-
> precision loaders. See `docs/BigTypeGuide.md`.
| `.loadSmartD` | `MatResult[Double]` | parse CSV, returning column headers and matrix |
| `.loadSmartBig` | `MatResult[Big]` | parse CSV, returning column headers and matrix |

### Directory listing

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.paths` | `Seq[Path]` | immediate directory contents as paths |
| `.pathsIter` | `Iterator[Path]` | immediate contents as a path iterator |
| `.files` | `Seq[JFile]` | immediate directory contents as `File` objects |
| `.filesIter` | `Iterator[JFile]` | immediate contents as a `File` iterator |
| `.subdirs` | `Seq[Path]` | immediate child directories |
| `.subfiles` | `Seq[Path]` | immediate child regular files |
| `.pathsTree` | `Seq[Path]` | recursive tree, pre-order, **including this path itself** |
| `.pathsTreeIter` | `Iterator[Path]` | recursive tree as a lazy iterator |
| `.walk` | `Iterator[Path]` | alias for `.pathsTreeIter` |

> **Two things about the tree walk that are easy to miss.** It **includes the starting path**,
> because `Files.walk(p)` yields `p` first — so every count is off by one if you assume
> otherwise. And it does **not follow symlinks**: a symlinked directory is listed but not
> descended into, which is also what keeps a cyclic link from hanging the traversal.
>
> **Sibling order is unspecified.** Neither `File.listFiles` nor `Files.walk` promises one, and
> it varies by filesystem — roughly alphabetical on NTFS, arbitrary on ext4. Sort if you need
> an order. Pre-order (a directory before its contents) *is* guaranteed.

### File writing

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.write(text)` | `Unit` | write a string to the file (overwrites) |
| `.writeLines(lines)` | `Unit` | write each line followed by `\n` |
| `.withWriter(charset, append)(func)` | `Unit` | write via a `PrintWriter` callback; charset defaults to `"UTF-8"`, append defaults to `false` |

### File operations

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.copyTo(dest, overwrite, copyAttributes)` | `Path` | copy file to destination; **`overwrite` is required**, `copyAttributes` defaults to `false` |
| `.renameTo(other, overwrite)` | `Boolean` | rename/move file; returns `false` on failure |
| `.renameToOpt(other, overwrite)` | `Option[Path]` | rename/move; returns `Some(dest)` on success, `None` on failure |
| `.renameViaCopy(dest, overwrite)` | `Int` | rename by copy+delete, works across filesystems; 0 on success, -1 on failure |
| `.delete()` | `Boolean` | delete file if it exists; `true` if deleted |
| `.mkdirs` | `Boolean` | create directory and all missing parents |

> **`copyTo` requires `overwrite`; the rename family does not.**
>
> `copyTo` used to default it to `true`, so `copyTo(dest)` clobbered silently while
> `renameTo(dest)` — defaulting to `false` — did not. That asymmetry ran in the dangerous
> direction and no reader could guess it. Flipping the default would have changed behaviour at
> every call site with nothing to catch it at build time, so the parameter is required instead
> and the compiler names each one.
>
> Measured across `jsrc`, `apps` and `vast`: 19 call sites, of which **6 wanted `false`** — and
> in every one of those the surrounding code already guarded against clobbering, so the old
> default had been working against the author's intent.
>
> `renameTo`, `renameToOpt` and `renameViaCopy` keep `overwrite = false`. That default is
> already the safe answer and every existing call site means it, so requiring it there would
> have been ~24 edits for no change in behaviour. `copyAttributes` keeps its default too: the
> line is that a flag which can destroy data must be stated, one which cannot may default.

### Timestamps

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.lastModified` | `Long` | last-modified time as epoch milliseconds |
| `.lastModMillisAgo` | `Long` | milliseconds elapsed since last modification |
| `.lastModSecondsAgo` | `Double` | seconds since last modification |
| `.lastModMinutesAgo` | `Double` | minutes since last modification |
| `.lastModHoursAgo` | `Double` | hours since last modification |
| `.lastModDaysAgo` | `Double` | days since last modification |
| `.lastModSeconds` | `Double` | alias for `.lastModSecondsAgo` |
| `.lastModMinutes` | `Double` | alias for `.lastModMinutesAgo` |
| `.lastModHours` | `Double` | alias for `.lastModHoursAgo` |
| `.lastModDays` | `Double` | alias for `.lastModDaysAgo` |
| `.ageInDays` | `Double` | alias for `.lastModDaysAgo` |
| `.lastModifiedYMD` | `String` | last-modified time as `"yyyy-MM-dd HH:mm:ss"`, in **UTC** |
| `.lastModifiedTime` | `DateTime` | last-modified time, in **UTC** (a `uni.time.UniDateTime`; converts implicitly to `java.time.LocalDateTime`) |
| `.weekDay` | `Int` | day of the week of last modification, 1 = Monday .. 7 = Sunday |
| `.weekDayName` | `String` | three-letter weekday abbreviation, `Mon` .. `Sun` |
| `.epoch2DateTime(epoch, timezone)` | `DateTime` | convert an epoch-millisecond value to a date in the given `ZoneId` (default `UTC`) |

> **File timestamps are UTC, and no zone or offset appears in these signatures.**
>
> `lastModifiedTime` used `ZoneId.systemDefault()`, which cannot be reproduced outside the
> JVM — Rust's `std` has no timezone database and no way to read the local offset. The
> alternative was an offset parameter on every call, a value almost no caller has an opinion
> about: a file timestamp is used either for *comparison*, where any consistent offset
> cancels out, or for *display*, where being machine-independent is an advantage.
>
> It also removed an inconsistency that was already here: `epoch2DateTime` has always
> defaulted to `UTC`, so `p.epoch2DateTime(p.lastModified)` and `p.lastModifiedTime`
> disagreed by the local offset for the same file. They now agree, and a test asserts it.
>
> Local time needs no API of its own — it is one explicit call:
> `p.epoch2DateTime(p.lastModified, ZoneId.systemDefault())`.
>
> `weekDay` also moved off `java.time.DayOfWeek` to a plain `Int` with the same numbering,
> which makes it portable. It had no callers in the reference script corpus.

### Hashing and checksums

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.hash64` | `String` | streaming 64-bit hash of file contents |
| `.md5` | `String` | MD5 digest as a hex string |
| `.sha256` | `String` | SHA-256 digest as a hex string |
| `.cksum` | `(Long, Long)` | POSIX-style checksum and byte count |

---

## JFile extensions

`java.io.File` (aliased `JFile`) mirrors all `Path` extension methods above, with the following differences:

- **Only on `JFile`:** `.diff(other: JFile): Seq[String]` — diff two files via shell `diff`, returning output lines.
- **Only on `JFile`:** `.filesTree: Seq[JFile]` and `.filesTreeIter: Iterator[JFile]` — recursive tree returning `JFile` values (`.pathsTree` / `.pathsTreeIter` are also available on `JFile` and return `Path` values).
- **Not on `JFile`:** `.canRead`, `.canExecute` (use `java.io.File`'s own methods directly).
- **Not on `JFile`:** `.write(text)`, `.withWriter(...)` (use `Path` or `uni.io.FileOps` directly).

## Rust counterparts

Where per-row or per-file overhead dominates, this repo carries a Rust port of the
performance-sensitive parts under `rust/src/upath/`:

| Scala | Rust module | covers |
| :--- | :--- | :--- |
| `uni.io.FastCsv`, `.csvRows` | `upath::csv` | row parsing, quoting, the writer |
| `Delimiter.detect` | `upath::delim` | delimiter sniffing |
| `loadSmart` into a matrix | `upath::matcsv` | CSV to a typed table |
| `.cksum` `.md5` `.sha256` `.hash64` | `upath::hash` | the four hashes |
| `.posix` `.relpath` `.stdpath`, mount handling | `upath::resolve`, `upath::mount`, `upath::ext` | path translation |
| `String` extensions (`.lc`, `.posx`, ...) | `upath::strext` | string helpers |
| `uni.time.UniDateTime`, `DateFormat` | `utime::datetime`, `utime::format` | the date type, its arithmetic and pattern formatting |
| `.lastModified`, `.lastMod*Ago`, `.lastModifiedTime` | `upath::times` | file timestamps and file age |
| `.exists` `.isFile` `.isDirectory` `.length` `.realPath` `.isSameFile` | `upath::meta` | metadata predicates |
| `.paths` `.files` `.subdirs` `.subfiles` `.pathsTree` `.walk` | `upath::walk` | listing and tree walking |
| `.copyTo` `.renameTo` `.delete` `.mkdirs` `.withWriter` | `upath::mutate` | copy, move, delete, create |
| `.lines` `.linesStream` `.withLines` `.eachLine` `.contentAsString` | `upath::io` | reading, and the carriage-return policy above |
| `.readCsv` `.loadMatD` `.loadMatF` `.loadSmartD` `.csvRowsAsync` | `upath::matcsv`, `upath::csv` | the `Double`/`Float` loaders |

Most Rust modules are checked against a committed fixture generated from the Scala
implementation, so a divergence fails a test rather than appearing at runtime. Two are covered
by unit tests on each side instead, for reasons worth knowing:

- `upath::mutate` — a fixture would have to perform the mutation to record it, so the
  expected values would be whatever the last run happened to do. The semantics that matter
  (does `overwrite = false` refuse, does `delete` distinguish absent from failed, does
  `mkdirs` report `false` for an occupied name) are asserted directly.
- `upath::meta` — likewise for `canRead`/`canExecute`, whose answers depend on the host's
  permissions rather than on committed data.

`upath::walk` *is* fixture-backed, and pins the order as well as the contents: both languages
sort by the same key, so `test-data/walk-parity/` records the order the API returns rather than
sorting it away. It used to sort both sides, back when neither language promised an order.

Still **not** ported: the `Big`-typed loaders (`loadMatBig`, `loadMatB`, `loadSmartBig`,
`readCsvB`) and the parent-name variants. `SmartParse` — the date *parser*, as distinct from
the date type — is also absent, so a Rust script needing to read arbitrary date text must do
that itself.

The `Big` loaders are blocked on `Big` itself, which is a project rather than a method: an opaque
`BigDecimal` with `MathContext.DECIMAL128`, plus a NaN sentinel recognised by equality and the
propagation contract that goes with it. `std` has no decimal type, so this would be the first
real dependency the port takes. `loadMatD`/`loadMatF` cover the `Double` and `Float` cases.

A few methods are present under one name rather than two. `files` and `paths` coincide, Rust
having no second path type; the `*Iter` variants are covered by `paths` and `walkIter`; and
`asFile` is the **identity**, kept so a line written against the Scala ports unchanged.

The `lastMod*Ago` aliases (`lastModSeconds`, `lastModMinutes`, `lastModHours`, `lastModDays`,
`ageInDays`, `lastModSecondsDbl`) are deliberately absent from the Rust: they add names, not
capability. Use the `*Ago` spelling, or `ago()` for all four values from a single clock read.

### Method names match Scala, deliberately

The Rust API uses the **Scala spelling** — `baseName`, `plusDays`, `lastModifiedTime` — rather
than snake_case, under a module-scoped `#![allow(non_snake_case)]`. Same reason `windows-rs`
spells Win32 functions `SetEvent` rather than `set_event`: the documented API is the contract,
and here that contract is the Scala one, for anyone maintaining a script in both languages.

Internal helpers and Rust trait contracts (`Display::fmt`, `cmp`, `to_string`) stay
snake_case, so **the case tells you whether a Scala counterpart exists**. Where Rust cannot
overload or default an argument, one Scala member becomes several with a suffix — `ofFull`,
`ofYmd`, `isValidFields` for `UniDateTime.of` and `isValid` — and those keep CamelCase, since
they are API rather than internals.

The crate is currently named `t3prf` (`rust/Cargo.toml`); `upath` is a module inside it,
not a separately published crate.

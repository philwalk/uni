# uni for Rust — Scripting Guide

Paths, files, CSV tables, dates and exact decimals — the scripting half of `uni`, in Rust.
It parallels [`docs/UniScriptingTools.md`](../../docs/UniScriptingTools.md),
[`docs/PathIOReference.md`](../../docs/PathIOReference.md),
[`docs/DateTimeParser.md`](../../docs/DateTimeParser.md) and
[`docs/BigTypeGuide.md`](../../docs/BigTypeGuide.md); every block is a complete program that
`checkRustDocs.sh` compiles in CI and before every release.

The naming rule holds throughout: `baseName`, `lastModifiedTime`, `parseDateSmart` — the
Scala spelling, so a script kept in both languages needs no mental translation. Where Rust
needs a variant (`Result` instead of an exception, an explicit config instead of a dynamic
scope), the extra form is snake_case: `try_write`, `as_path`, `parseDateSmartWith`.

---

## Portable Path Handling

`UPath` is the crate's path type. Strings in any convention — Windows drive paths, MSYS2/
Cygwin `/c/…` and `/cygdrive/c/…`, WSL, POSIX — resolve to the same `UPath`, through the same
mount table the Scala `Paths.get` consults, so a script that says `"/c/Users/me/data.csv"`
means the same file on every platform it runs on.

```rust
use uni::upath::StrPathExts;

fn main() {
    // A string becomes a path with as_path() (Scala: "…".asPath / Paths.get(...))
    let here = ".".as_path().expect("cwd resolves");
    let src = "src/lib.rs".as_path().expect("a relative path resolves against cwd");

    // The path in every spelling
    println!("posix    {}", src.posx());              // forward slashes, MSYS2 form on Windows
    println!("local    {}", src.local());             // the OS-native spelling
    println!("absolute {}", src.abspath().posx());
    println!("relative {}", src.relpath());
    println!("norm     {}", src.normalized().posx());

    // Structure
    println!("name {}  base {}  ext {:?}", src.last().unwrap_or(""), src.baseName().unwrap_or(""), src.extension().ok().flatten());
    println!("parent {}  segments {:?}", src.parent().posx(), src.segments());
    println!("cwd is a dir: {}  exists: {}", here.isDirectory(), src.exists());
}
```

Every accessor that can fail on an ill-formed path returns a `Result` (`last`, `baseName`,
`ext`, `extension`) or a `Result`-free sentinel form: a string that names no path resolves to
a **BadPath** (`isBadPath()`), the way an unparseable date is a `BadDate` — a value that
travels rather than an exception, and `badPathString()` recovers the original text.

### Tested environments
Windows 11 (MSYS2 UCRT64, Git Bash), WSL2 Ubuntu, Ubuntu 24.04, macOS (Apple Silicon) — the
same operating systems the Scala side runs on; `jsrc/pairProbe.sc` ↔ `rust/examples/pair_probe.rs` runs
~50 path operations on a fixed tree in both languages and diffs the output.

---

## Files: reading, writing, walking

```rust
use uni::upath::StrPathExts;

fn main() {
    let dir = std::env::temp_dir().join("uni-scripting-guide");
    std::fs::create_dir_all(&dir).expect("temp dir");
    let p = dir.join("notes.txt").to_string_lossy().as_path().expect("path");

    // Write (whole file, or lines); false when it failed — Scala's writeText / writeLines
    assert!(p.write("alpha\nbeta\n"));
    assert!(p.writeLines(&["one", "two", "three"]));

    // Read
    let text = p.contentAsString();                 // whole file
    let lines = p.lines();                          // Vec<String>, CR-optional line ends
    let first = p.firstLine();
    let mut count = 0;
    p.eachLine(|_l| count += 1);                    // streamed, no Vec

    // Metadata (timestamps are UTC on both sides)
    let meta = (p.exists(), p.isFile(), p.isDirectory(), p.length(), p.lastModifiedTime().ymd());

    // Directory listing and walking
    let d = dir.to_string_lossy().as_path().expect("dir");
    let files = d.files();                          // direct children that are files
    let all = d.pathsTree();                        // the whole tree, depth first
    let iter_count = d.walkIter().count();          // lazily

    // Mutation: mkdirs, copyTo (Option: None when refused), renameTo, delete
    let sub = dir.join("sub").to_string_lossy().as_path().expect("path");
    assert!(sub.mkdirs());
    let copy = dir.join("sub/notes-copy.txt").to_string_lossy().as_path().expect("path");
    let copied = p.copyTo(&copy, false, false);     // Some(dest) on success
    assert!(copy.delete() && sub.delete() && p.delete());

    println!("{} {:?} {first} {count} {:?} {} {} {} {}", text.len(), lines, meta, files.len(), all.len(), iter_count, copied.is_some());
    let _ = std::fs::remove_dir_all(&dir);
}
```

| Scala (`uni.*` Path extensions) | Rust (`UPath`) |
|---|---|
| `p.contentAsString`, `p.lines`, `p.firstLine`, `p.eachLine(f)` | same names |
| `p.writeText(s)`, `p.writeLines(seq)`, `p.withWriter { w => … }` | `p.write(&s)`, `p.writeLines(&[…])`, `p.withWriter(charset, append, \|w\| …)` |
| `p.exists`, `p.isFile`, `p.isDirectory`, `p.length`, `p.canRead` | same names |
| `p.lastModifiedTime`, `p.lastModDaysAgo`, `p.ago` | same names (UTC) |
| `p.paths`, `p.files`, `p.subdirs`, `p.pathsTree`, `p.walkIter` | same names |
| `p.mkdirs`, `p.delete`, `p.copyTo(dest)`, `p.renameTo(dest)` | `mkdirs()`, `delete()`, `copyTo(&dest, overwrite, copyAttributes)` → `Option`, `renameTo(&dest, overwrite)` |
| exception on failure | `bool` or `Option`; the `try_*` forms return `io::Result` |

---

## CSV

Three levels, as in Scala: raw rows, typed matrices, and named tables.

```rust
use ndarray::Array2;
use uni::udata::{Big, MatB, MatD};
use uni::upath::{AggOp, CsvTable, JoinType, StrPathExts};

fn main() {
    let dir = std::env::temp_dir().join("uni-scripting-csv");
    std::fs::create_dir_all(&dir).expect("temp dir");
    let p = dir.join("prices.csv").to_string_lossy().as_path().expect("path");
    assert!(p.write("sector,price,vol\n2,10.50,1\n1,20,2\n2,30.25,3\n1,40,4\n"));

    // 1. Raw rows (RFC 4180 quoting; header row included)
    let rows: Vec<Vec<String>> = p.csvRows();
    let streamed = p.csvRowsStream().count();

    // 2. Typed matrices — the header row is detected and dropped
    let d: Array2<f64> = p.loadMatD();              // Scala: p.loadMatD / p.readCsv
    let m = MatD::fromArray2(&d);
    let b: Array2<Big> = p.loadMatBig();            // exact decimals of the text ("10.50" stays 10.50)
    let mb = MatB::fromArray2(&b);

    // 3. Named tables — headers travel with the data (Scala: MatResult, loadSmartD)
    let t: CsvTable<f64> = p.loadSmartD();
    let price = t.col("price").expect("header");
    let by_sector = t.groupBy("sector", AggOp::Mean);
    let sums = t.groupByOps("sector", &[("vol", AggOp::Sum), ("price", AggOp::Max)]);
    let joined = t.merge(&t, "sector", JoinType::Inner);

    // Writing: any Mat, Java number text, so the Scala side reads it byte for byte
    let out = dir.join("out.csv").to_string_lossy().as_path().expect("path");
    assert!(m.writeCsv(&out));
    assert!(out.writeCsv(&[vec!["a", "b"], vec!["1", "2"]]));   // rows of strings

    println!("{} {} {:?} {:?} {}", rows.len(), streamed, m.shape(), mb.at(0, 1).toString(), price.len());
    println!("{:?} {:?} {:?}", by_sector.headers, sums.headers, joined.rows());
    let _ = std::fs::remove_dir_all(&dir);
}
```

Cell classification lives with `Big`: `str2num("$1,200")` reads the messy real-world
spellings (currency, thousands separators, parentheses for negatives, percentages) and
`isNumeric` says whether it can (see the exact-decimal section below).

---

## Command-Line Arguments

`eachArg` walks the arguments with a context that consumes option values, and `showUsage`
prints the usage block naming the program and exits — the Scala `ArgsParser` idiom.

```rust
use uni::cli::{eachArg, showUsage};

fn main() {
    let usage = |m: &str| showUsage(m, &["-n <count>   ; how many", "-v           ; verbose"]);
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut n = 1i64;
    let mut verbose = false;
    eachArg(&args, &usage, |ctx, arg| match arg {
        "-n" => n = ctx.nextLong(),
        "-v" => verbose = true,
        other => ctx.usage(&format!("unrecognized arg [{other}]")),
    });
    println!("n={n} verbose={verbose}");
}
```

---

## Dates: `UniDateTime`

The same date type as `uni.time.UniDateTime` — same fields, same validation, same
sentinels (`BAD_DATE`, `EMPTY_DATE` render as `<BadDate>` / `<EmptyDate>`), same epoch-day
arithmetic, **no date-library dependency**. `test-data/date-parity/` pins 4,020 cases against
`java.time`; `test-data/smartparse-parity/` pins the format-detecting parser.

```rust
use uni::utime::UniDateTime;
use uni::utime::{daysBetween, endOfMonth, getDuration, parseDateSmart};
use uni::utime::smartparse::{parseDateSmartWith, DateOrder, TimeConfig};

fn main() {
    // Parsing: any recognisable layout; BAD_DATE (never an exception) when none is
    let d1 = parseDateSmart("2024-05-12 14:30:45");
    let d2 = parseDateSmart("11 Aug 2026");
    let bad = parseDateSmart("not a date");
    println!("{d1} {d2} {bad} valid: {} {} {}", d1.isValid(), d2.isValid(), bad.isValid());

    // Ambiguous numeric dates: the config is an explicit parameter (Scala: a dynamic scope)
    let us = parseDateSmartWith("03/04/2025", TimeConfig { monthFirst: true, order: DateOrder::Auto });
    let eu = parseDateSmartWith("03/04/2025", TimeConfig { monthFirst: false, order: DateOrder::Auto });
    println!("US {}  EU {}", us.ymd(), eu.ymd());

    // Fields, rendering
    println!("{} {} {} {} {}", d1.year(), d1.month(), d1.day(), d1.dayOfWeekName(), d1.toEpochDay());
    println!("{} | {} | {}", d1.ymd(), d1.ymdhms(), d1.fmt("EEEE, dd MMM yyyy HH:mm"));

    // Arithmetic (month-end clamping and carries as java.time does them)
    let later = d1.plusMonths(9).plusDays(3).withHour(9);
    println!("{}  days between {}  end of month {}", later.ymdhms(), daysBetween(&d1, &d2), endOfMonth(&d1).ymd());
    let (days, hours, minutes, seconds) = getDuration(&d1, &d2);
    println!("{days}d {hours}h {minutes}m {seconds}s");

    // Construction from fields and the epoch
    let y2k = UniDateTime::ofYmd(2000, 2, 29);
    println!("{} {}", y2k.ymd(), UniDateTime::ofEpochDay(y2k.toEpochDay()).ymd());
}
```

Where the Scala consults `timeConfig` (a dynamically scoped default for day/month order),
Rust takes it as an argument: `parseDateSmart` fixes the default (`monthFirst = true`,
`order = Auto`), `parseDateSmartWith` says which. File timestamps (`lastModifiedTime`) are
UTC on both sides.

---

## Exact Decimals: `Big`

`Big` is `java.math.BigDecimal`'s arithmetic — the left operand's `MathContext` (34 digits,
HALF_EVEN) applied by every operator, Java's preferred-scale and trailing-zero rules, Java's
`toString`/`toPlainString` — with the `BigNaN` sentinel that travels through arithmetic
instead of an exception. `test-data/big-parity/` pins it (633 rows); `bigcalc` is the demo
pair.

```rust
use uni::udata::big::RoundingMode;
use uni::udata::{Big, MatB, NumFormat, isBad, isNumeric, numStr, numStrPct, str2num};

fn main() {
    // Parse, arithmetic, rounding — exact
    let a = Big::parse("12.34");
    let b = Big::parse("5.678");
    println!("{} {} {} {}", a.add(&b).toString(), a.mul(&b).toString(), a.div(&b).toString(), a.sub(&b).toString());
    println!("{} {}", Big::parse("2.345").setScale(2, RoundingMode::HalfEven).toString(), Big::from_i64(2).sqrt().toString());
    println!("{} {}", Big::from_f64(0.1).toPlainString(), Big::parse("1.23E+4").toPlainString());

    // Messy real-world strings
    println!("{} {} {} {}", str2num("$1,234.56").toString(), str2num("(300)").toString(), str2num("12%").toString(), isNumeric("not-a-number"));

    // Formatting for reports
    println!("[{}] [{}] [{}]", numStr(&Big::parse("1234.5"), &NumFormat::default()), numStr(&Big::parse("12345678901.5"), &NumFormat::Abbrev()), numStrPct(&Big::parse("0.1234"), &NumFormat::Percent()));

    // The sentinel: absorbed, tested, never thrown
    let nan = Big::from_i64(-1).sqrt();
    println!("{} {} {}", isBad(&nan), isBad(&nan.add(&Big::from_i64(5))), numStr(&nan, &NumFormat::default()).trim());

    // A whole matrix of them: MatB (see the cheat sheet)
    let inv = MatB::parseRows(&[&["19.99", "3"], &["4.15", "7"]]);
    let lines = inv.applyAllCol(0).mul(&inv.applyAllCol(1));
    println!("{:?} total {}", lines.flatten().iter().map(Big::toString).collect::<Vec<_>>(), lines.sum().toString());
}
```

---

## Where things live

| Area | Scala | Rust |
|---|---|---|
| Paths | `uni.*` (`Paths.get`, `asPath`, `Path` extensions) | `uni::upath` — `UPath`, `StrPathExts` |
| Line / text I/O, CSV rows | `uni.io`, `Path` extensions | `uni::upath::{io, csv}` on `UPath` |
| Matrices from CSV, named tables | `loadMatD`, `loadSmartD`, `MatResult` | `uni::upath::matcsv` — `loadMatD`, `loadSmartD`, `CsvTable`; `matresult` — `groupBy`, `merge` |
| Dates | `uni.time` | `uni::utime` — `UniDateTime`, `smartparse`, `timeutils` |
| Exact decimals | `uni.data.Big`, `BigUtils` | `uni::udata::{Big, bigutils}` |
| Matrices | `uni.data.Mat*` | `uni::udata::{MatD, MatF, MatB, MatBool}` — see the cheat sheet |
| Argument parsing | `ArgsParser` | `uni::cli` |

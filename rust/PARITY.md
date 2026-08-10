# API parity: Scala `uni` ⇄ Rust `uni`

A module-by-module inventory of everything the Scala library exposes, what the Rust
crate covers today, and — because the goal is to make porting code between the two
languages mechanical — which gaps are porting candidates, in what order, and what is
deliberately out of scope. Method counts are from the 0.16.0 sources.

Naming contract (unchanged): public CamelCase names mirror the Scala spelling;
snake_case marks an internal helper or a `try_*` Result variant.

## At parity today (fixture/test-verified)

| Scala | Rust | Coverage |
| :--- | :--- | :--- |
| `uni.ext.PathExts` (188 defs: Path + JFile mirrors) | `upath` (174 pub fns) | the audited 92-method surface: paths, metadata, traversal, mutation, line I/O, charsets, CSV, hashes (`cksum`/`md5`/`sha256`/`hash64` + `try_*`), `delim` detection |
| `UniDateTime` (57 methods), `DateFormat`, `SmartParse` | `utime` (47 fns) | field arithmetic, plus/minus/with families, epoch-day, pattern formatting, smart parsing incl. `parseDateSmartWith(config)` |
| `Big` + its CSV loaders | `udata` (30 fns) | full arithmetic, HALF_EVEN contexts, `loadMatBig`/`loadSmartBig` |
| `NumPyRNG` | `numpy_rng` | bit-identical `uniform`/`randn`/`next_*` |
| `Tprf3` core | `t3prf` | `t3prf_core`, `estimate_3prf_is_full`/`oos_cv`/`oos_rec`, `ols_solve`, `standardize_columns` |
| `StringExts` (partial) | `StrExts`/`StrPathExts` | `lc uc posx dropSuffix startsWithIgnoreCase stripPrefix asPath absPath posix` |

Nine committed fixtures under `../test-data/*-parity/` pin these; see `README.md`.

## Gaps inside already-ported modules (Tier 1 — cheapest wins, fixtures exist)

1. **`t3prf`: the closed-form entry points.** ~~Scala added `tprfClosedForm`,
   `plsClosedForm`, `pls1Fit` and `forecast3prf`; the Rust module predates them.~~
   **Done (2026-08-10):** all four ported, pinned by new `closed`/`pls` rows in the
   `tprf3-parity` fixture (615 rows appended; the pre-existing rows kept their
   original platform-generated values).
2. **`utime`: no clock, no between/duration family.** **Done (2026-08-10):**
   `utime::timeutils` ports `now`, `yesterday`, `nowUTC`, `secondsBetween`,
   `secondsSince`, `minutesBetween`, `hoursBetween`, `daysBetween`, `daysRounded`,
   `elapsedDays`, `getDuration`, `endOfMonth`, `quikDate`, `quikDateTime`,
   `monthAbbrev2Number`, `whenModified`, `ageInMinutes`, `ageInDays` — the pure parts
   pinned by new `between`/`duration`/`eom`/`quikdate`/`quikdt`/`mon2num` rows in
   `date-parity`. The difference family is zone-free **on both sides**: the Scala
   `ZoneId` parameters these functions once carried were judged misguided (a
   difference between two local datetimes is field arithmetic no zone can affect)
   and removed in 0.16.0, so these are exact ports. `whenModified` is UTC on both
   sides too, and `getMillis` (fields → epoch millis, at UTC — the exact inverse of
   `epoch2DateTime`) is ported with `getmillis` fixture rows. The clock divergence is
   closed: `localOffsetMinutes()` reads the machine's current UTC offset from one
   platform call (`GetTimeZoneInformation` / `localtime_r`'s `tm_gmtoff`, no tzdb, no
   new dependency), so `now` is local wall time exactly as in Scala, with a UTC
   fallback on platforms that cannot say. Remaining documented differences: `nowZoned`
   has no port (zone *resolution* needs a tzdb), and the `quik*` parsers answer
   `BAD_DATE` where Scala throws. `epoch2DateTime` was already ported
   (`upath::times`).
3. **`udata`: Big is ported, BigUtils is not.** `numStr`/`numStrPct`/`num2string` +
   `NumFormat`, `str2num`, `isNumeric`, `isBad`/`orBad` — and `Big.round(MathContext)`,
   which real accounting code (`QuaxMerge`) needed and had to work around. `round`
   belongs on **both** sides, with rows added to the `big-parity` fixture.
   (`getMostSpecificType: String | Big | DateTime` and `toStr(CVD)` also live here but
   depend on dates and vectors — see inference and Mat below.)
4. **String sugar:** `local`, `readCsv`/`readCsvB`/`readCsvF`/`writeCsv` on String —
   one-line delegation to the existing UPath loaders.
5. **`uni` misc, already half-present:** `tmpDir`, `pwd`, `isWinshell`, byte-array
   `cksum` overloads.

## New modules (Tier 2 — the porting-ergonomics play)

6. **`Proc` → new `uproc` module.** `run → ProcResult{status, stdout, stderr, cmd}` +
   `ok`, `execLines` (spawn + lazy line reader), the `proc` builder, `where` /
   `whereInPath`, `bashExe`/`pythonExe`/`unameExe`, `uname`, `osType`, `isWsl`,
   `hostname`. Pure `std::process`, zero new dependencies. The single biggest enabler
   for porting `jsrc` scripts — the pallet migration leaned on `execLines`/`run`/
   `bashExe` at ~15 sites.
7. **`cli.ArgsParser` → new `ucli` module.** `eachArg`/`showUsage`/`progName`/
   `progPath`/`consumeNext`/`peekNext`/`nextInt`/`nextLong`/`nextDouble`. Every
   script `main` uses this shape; a Rust mirror (closure over a `Ctx`, `progName`
   from `argv[0]`) makes script ports line-for-line.
8. **`HereDoc.DATA/END`** — portable as a `data!()` macro using `file!()` to locate
   the source file; works for rust-script-style single files. Small; low priority.
9. **`inferType(path, scanRows)` + `getMostSpecificType`** — column type inference
   over String | Big | date. Both ingredients are already ported; only the classifier
   is missing.

## The big-ticket item (Tier 3): the Mat/Vec stack, ~590 public methods

`Mat` 319 · `VecExts` 77 · `MatFacades` 63 · `MatDOps` 36 · `MatMathOps` 23 ·
`MatPandasOps` 22 (`describe rolling shift diff percentile percentileOf valueCounts
nlargest nsmallest nunique idxmax idxmin median histogram between unique sort argsort
pct_change …`) · `MatElem` 15 · `BlasCrossover` 9 · `MatSignalOps` 4 · `MatFormat` 5,
plus `AggOp`/`JoinType`/`matResultOps` groupBy/join.

Two facts make this more tractable than the count suggests: the crate **already
depends on ndarray + rayon with an optional BLAS feature** (the "no dependencies"
story is specifically no JNI / no date crate / no decimal crate / no regex engine),
and `matcsv` already has a minimal matrix type with `nrows`/`ncols`/`dim`/indexing
that the loaders return. Realistic phasing:

- (a) `MatD`/`CVecD`/`RVecD` core over `Array2<f64>` — construction, slicing,
  transpose, broadcast arithmetic — with NumPy fidelity as the oracle, exactly as on
  the Scala side;
- (b) reductions/stats (`MatDOps`);
- (c) pandas ops;
- (d) `leastSquares` + `BlasCrossover` routing (the `blas` feature already exists);
- (e) `matResultOps` join/groupBy last.

`Mat[Big]` matrix algebra stays deferred — no consumer yet (documented decision).
This is the only multi-session item; everything above it is hours, not days.

## Intentionally no Rust analog (documented, not forgotten)

- `plot` — JVM graphics backend.
- `cli.Caller` macros — `file!()`/`line!()`/`column!()` are native.
- `showLimitedStack`/`getLimitedStackTrace` — Rust errors are `Result`; backtraces
  are a different mechanism.
- `LocalDateTime`/`Instant` extension shims — java.time interop only.
- `given Conversion` Int/Long/Float/Double→Big — Rust `From` impls cover it; the
  Scala conversions don't participate in `==`/overload resolution anyway.
- `withTimeConfig`/`withDMY`/`withMDY` thread-local config — Rust's explicit
  `parseDateSmartWith(config)` is the documented equivalent.
- `failFast`/`boundary` — the `?` operator.
- `eprint`/`eprintln`/`eprintf` — macros.
- `LcLookupOrderedMap` — internal to the mount tables on both sides.
- `posixAbs`/`posixRel` — deprecated on the Scala side; not ported.
- `FileOps.loadSmartUrl` — HTTP; would need a network dependency. Feature-gate later
  if ever.

## Recommended order

1. `t3prf` closed-form four — closes a real hole in a module the README calls ported.
2. `utime` clock + between/duration family — direct script-porting friction,
   fixture-able against `java.time`.
3. BigUtils formatting + `Big.round` on both sides — accounting fidelity.
4. `uproc`, then `ucli` — the two modules that make script ports mechanical.
5. String sugar + misc, opportunistically.
6. Mat phases (a)–(b), then reassess before pandas ops.

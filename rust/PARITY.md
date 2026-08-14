# API parity: Scala `uni` ⇄ Rust `uni`

A module-by-module inventory of everything the Scala library exposes, what the Rust
crate covers today, and — because the goal is to make porting code between the two
languages mechanical — which gaps are porting candidates, in what order, and what is
deliberately out of scope. Method counts are from the 0.16.0 sources.

Naming contract (unchanged): public CamelCase names mirror the Scala spelling;
snake_case marks an internal helper or a `try_*` Result variant.

## At parity today (fixture/test-verified, as of 2026-08-10)

| Scala | Rust | Coverage |
| :--- | :--- | :--- |
| `uni.ext.PathExts` (188 defs: Path + JFile mirrors) | `upath` (174 pub fns) | the audited 92-method surface: paths, metadata, traversal, mutation, line I/O, charsets, CSV, hashes (`cksum`/`md5`/`sha256`/`hash64` + `try_*`), `delim` detection — case-folding now a context property, all relatives absolutise |
| `uni.BadPath` + `isBadPath`/`badPathString` | `upath::badpath` + `UPath::isBadPath`/`badPathString` | total `Paths.get`/`resolve`: unrepresentable strings become `<absent-drive>:/__uni-BadPath__/<PUA payload>` (cygwin `U+F000+c` mapping, `/`→U+F02F uni extension); byte-exact `badPathString` recovery; `exists`/`isFile`/`isDirectory`/`mkdirs` short-circuit; design: `docs/PathProviderDesignNote.md` |
| `UniDateTime` (57 methods), `DateFormat`, `SmartParse`, `TimeUtils` | `utime` (67 pub fns) | field arithmetic, plus/minus/with families, epoch-day, pattern formatting, smart parsing incl. `parseDateSmartWith(config)`; the clock (local wall time via `localOffsetMinutes`, no tzdb), the zone-free between/duration family, `getMillis`, `endOfMonth`, `quik*`, `whenModified`/`ageIn*` |
| `Big`, `BigUtils` + the CSV loaders | `udata` (43 pub fns) | full arithmetic incl. `round(precision, mode)`, HALF_EVEN contexts, `loadMatBig`/`loadSmartBig`; `numStr`/`NumFormat` (Java `%f` fidelity), `str2num`, `isNumeric`, `isBad`/`orBad` |
| `NumPyRNG` | `numpy_rng` (7 fns) | bit-identical `uniform`/`randn`/`next_*` |
| `Mat[Double]` reductions (Tier 3 milestone 1) | `udata::mat::MatD` | construction, elementwise `+ - *` against scalar and same-shape matrix (incl. scalar-on-the-left), `abs` `power` `cumsum` `mean` `sum` `toArray` `head` `tail` `at` — **bit-identical**, not merely close: `sumD`'s 8-way unrolled combine tree and its pinned 16-chunk decomposition are reproduced exactly, and `abs` keeps `-0.0` where `f64::abs` would not. Broadcasting, slicing, transpose and `CVecD`/`RVecD` are still to come |
| `Tprf3`, complete | `t3prf` (13 pub fns) | `t3prf_core`, `estimate_3prf_is_full`/`oos_cv`/`oos_rec`, `ols_solve`, `standardize_columns`, and the closed forms: `tprfClosedForm`, `plsClosedForm`, `pls1Fit`, `forecast3prf` |
| `StringExts` (partial) | `StrExts`/`StrPathExts` | `lc uc posx dropSuffix startsWithIgnoreCase stripPrefix asPath absPath posix` |
| `uni.cli.ArgsParser` | `cli` | `eachArg`/`showUsage` + cursor helpers (`thisArg consumeNext peekNext nextInt nextLong nextDouble`); prog name from the caller's source file (`#[track_caller]` mirroring the Scala macro) |

**Demo pairs** (each `jsrc/<name>.sc` ↔ `examples/<name>.rs`, byte-identical output —
end-to-end parity checks that read as documentation): `pairProbe` (metadata +
mutation semantics), `treestat` (traversal, stats, hashes, seeded sampling, CSV),
`pathshow` (every path rendering + mount-table resolution + BadPath + String
extensions), `datecalc` (the utime surface on fixed dates — portable output),
`forecast` (NumPyRNG randn → byte-identical CSV → `loadMatBig` → the 3PRF
closed forms + `forecast3prf` incl. the OOS procedures), `bigcalc` (the whole
udata surface — str2num/isNumeric, arithmetic via the `std::ops` overloads,
every rounding mode, numStr/numStrPct variants, the BigNaN sentinel — framed
as an exact-decimal invoice; fixed inputs, portable output).

Ten committed fixtures under `../test-data/*-parity/` pin these — roughly 19,880 rows
(path 10,537 — incl. 38 `badpath`/`badpayload` rows · date 4,173 · tprf3 3,039 · big 629 · smartparse 539 · csv 458 ·
numpy-rng 378 · mat 78 · walk 30 · hash 23) — plus the byte-identical pair probe
(`jsrc/pairProbe.sc` / `examples/pair_probe.rs`). See `README.md`.

`mat-parity` is small by row count and unusually load-bearing: 13 sizes × 6 reductions,
compared as raw IEEE-754 bit patterns rather than decimal text, over a corpus of
alternating 1e6/1e-6 magnitudes drawn from `NumPyRNG` (not from `math.sin`, which the JVM
and Rust may round differently in the last ulp). The sizes straddle the 8-way unroll
boundary and *both* chunking thresholds — 4096, where summation goes parallel, and 65536,
where the `MaxSumChunks` cap starts binding. Both test suites additionally assert that a
naive left fold **disagrees** with `sumD` on that corpus, so a fixture that stopped
discriminating would fail loudly rather than pass vacuously.

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
3. **`udata`: Big is ported, BigUtils is not.** **Done (2026-08-10):** `udata::bigutils`
   ports `numStr`/`numStrPct`/`num2string` + `NumFormat`, `str2num`, `isNumeric`
   (the four Scala regexes as character scans; two authoring accidents in them were
   repaired Scala-first in 0.16.0 — pattern 3's missing `(?i)`, pattern 4's unescaped
   dot — and the repaired behavior is what both sides pin), `isBad`/`orBad` and
   `big2double`; `Big.round` landed on **both** sides (`round(MathContext)` in Scala,
   `round(precision, mode)` here — `QuaxMerge` now calls it directly). 286 rows added
   to `big-parity`, including the significant-digit carry cases and Java's `%f`
   shortest-digits-half-up formatting quirks. (`getMostSpecificType: String | Big |
   DateTime` and `toStr(CVD)` still wait on inference and Mat — see below.)
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
7. **`cli.ArgsParser` → new `cli` module.** **Done (2026-08-11):** `eachArg`
   (closure over an explicit `ArgCtx`, since Rust has neither partial functions nor
   dynamic scoping), `showUsage`, and the cursor family `thisArg`/`consumeNext`/
   `peekNext`/`nextInt`/`nextLong`/`nextDouble`. Named `cli`, not `ucli`, to match the
   Scala package. The program name comes from the caller's *source* file via
   `#[track_caller]` + `Location::caller().file()` — NOT `argv[0]` as this item
   originally proposed: Scala names the source file (scala-cli properties, then a
   macro), so `treestat.rs` beside `treestat.sc` is the faithful mirror while an
   executable name would not be. Demonstrated by the `treestat` pair, which exercises
   every cursor method.
8. **`HereDoc.DATA/END`** — portable as a `data!()` macro using `file!()` to locate
   the source file; works for rust-script-style single files. Small; low priority.
9. **`inferType(path, scanRows)` + `getMostSpecificType`** — column type inference
   over String | Big | date. Both ingredients are already ported; only the classifier
   is missing.

## The big-ticket item (Tier 3): the Mat/Vec stack, ~590 public methods

`Mat` 319 (the core type — construction, shape, broadcast arithmetic, AND the whole
reduction/stat family: `sum mean std variance min max argmin argmax cumsum abs power
norm` plus their `axis` variants) · `VecExts` 77 (thin `CVec`/`RVec` facade; nearly
every method delegates via `.asMat`) · `MatFacades` 63 · `MatDOps` 36 (Double-
specialised **indexing**: 18 `apply` + 18 `update` overloads over `Range`, `Int`,
`::`, `Mat[Boolean]`, `Array[Int]` — not reductions) · `MatMathOps` 23 (elementwise
math and activations: `sin cos tanh floor ceil sigmoid relu softmax logSoftmax gelu
elu dropout log10 log2 trunc …`) · `MatPandasOps` 22 (`describe rolling shift diff
percentile percentileOf valueCounts nlargest nsmallest nunique idxmax idxmin median
histogram between unique sort argsort pct_change …`) · `MatElem` 15 ·
`BlasCrossover` 9 · `MatSignalOps` 4 · `MatFormat` 5, plus `AggOp`/`JoinType`/
`matResultOps` groupBy/join.

Two facts make this more tractable than the count suggests: the crate **already
depends on ndarray + rayon with an optional BLAS feature** (the "no dependencies"
story is specifically no JNI / no date crate / no decimal crate / no regex engine),
and `matcsv` already has a minimal matrix type with `nrows`/`ncols`/`dim`/indexing
that the loaders return. Realistic phasing:

- (a) `MatD`/`CVecD`/`RVecD` core over `Array2<f64>` — construction, slicing,
  transpose, broadcast arithmetic — with NumPy fidelity as the oracle, exactly as on
  the Scala side;
- (b) the reduction/stat family (in `Mat` itself, not `MatDOps`): `sum mean std
  variance min max argmin argmax cumsum abs power norm` + `axis` variants;
- (c) the `MatDOps` indexing surface and `MatMathOps` elementwise math;
- (d) pandas ops;
- (e) `leastSquares` + `BlasCrossover` routing (the `blas` feature already exists);
- (f) `matResultOps` join/groupBy last.

Two constraints that shape (a)/(b), recorded so they are not rediscovered late:

**Rust cannot overload, and `Index` cannot slice.** `MatDOps`'s 36 `apply`/`update`
overloads are distinguished purely by argument type, which has no Rust analog; and
`std::ops::Index` must return a `&Self::Output`, so `m[(0..5, 2)]` cannot construct a
new vector. Only scalar element access can use bracket syntax.

**Decision: distinct named methods, CamelCase, resolved by the naming contract above
in two steps.** (1) Where Scala already has a *named* method for the operation, mirror
it exactly — `at`, `slice`, `head`, `tail`. (2) Where the operation exists only as an
`apply`/`update` overload, use CamelCase with a suffix naming the argument pattern, so
the pieces mirror `apply`/`update` collectively — the established `ofFull`/`ofYmd`
precedent: `applyRowsCol`, `applyRowCols`, `applyAllCol`, `applyRowsAll`, `applyMask`,
`applyRowsIdx`, `updateRowsCols`, … Add `Index<(usize, usize)>` for scalar reads as
Rust sugar over `at`.

Do **not** spell these `rows_range`/`select_rows`: snake_case is reserved for internal
helpers, Rust idioms and `try_*` variants, so a snake_case public name would falsely
signal that no Scala counterpart exists. Note also that `row`/`col` are unavailable as
accessor names — in Scala they are the varargs *constructors* `Mat.row(1,2,3)` /
`Mat.col(1,2,3)`, and must stay constructors here.

**Reductions must be bit-exact, not merely correct.** `Mat.sumD` is not a naive fold:
`sumRange` accumulates into 8 unrolled accumulators combined as
`((s0+s1)+(s2+s3))+((s4+s5)+(s6+s7))`, and above `ParallelThreshold` (4096) the array
is split into `min(MaxSumChunks, n/4096)` chunks (`MaxSumChunks` = 16, pinned to a
constant precisely so the result is machine-independent), each summed by `sumRange`,
partials combined sequentially. A Rust `iter().sum()` lands on a different last ulp.
`mean`/`std`/`variance` all build on it, so `sumD` is the single thing to get exactly
right. Its fixture must straddle **both** thresholds — sizes below 4096, between 4096
and 65536, and above 65536 — since each region exercises a different chunk count.

`Mat[Big]` and a generic `Mat<T>` stay deferred — no consumer yet, and little is lost
by waiting: `pub type MatD = Mat<f64>` generalises later without moving call sites,
while bit-exact `sumD` is inherently f64-specific and would need an f64 specialisation
under any generic scheme. This is the only multi-session item; everything above it is
hours, not days.

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
- `posixAbs`/`posixRel` — deprecated on the Scala side, headed for `private[uni]`
  (never removal: they power `posix`/`relpath`). Not ported as public API; the same
  machinery lives internally in `upath::resolve`. Its two long-documented quirks
  were repaired in 0.16.0, both sides together: case-folding became a context
  property (fold on Windows/macOS, exact on Linux — the unconditional fold
  mis-relativised case-twin paths there), and every relative form now absolutises
  (the `a/b`-vs-`bare.txt` asymmetry is gone).
- `FileOps.loadSmartUrl` — HTTP; would need a network dependency. Feature-gate later
  if ever.

## Recommended order

1. `t3prf` closed-form four — closes a real hole in a module the README calls ported.
2. `utime` clock + between/duration family — direct script-porting friction,
   fixture-able against `java.time`.
3. BigUtils formatting + `Big.round` on both sides — accounting fidelity.
4. `uproc`, then `ucli` — the two modules that make script ports mechanical.
5. String sugar + misc, opportunistically.
6. Mat phases (a)–(b), scoped by a real consumer rather than by API completeness:
   the first milestone is the ~14 operations `jsrc/marketSim.sc` needs (construct
   from slice, `+`/`-`/`*` against scalar and vector, `abs`, `power(2)`, `cumsum`,
   `mean`, `sum`, `toArray`, `head`, `tail`), which lands a byte-identical demo pair
   and pins `sumD` bit-exactness against a live workload. Then reassess before the
   `MatDOps` indexing surface and pandas ops.

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
| `Mat[Double]` core — Tier 3 phases (a) + (b) | `udata::mat::MatD`, `udata::mataxis`, `udata::vecexts::{CVecD, RVecD}` | **the strided view model** (`transpose`/`T`, `slice`, `broadcastTo`, and the fragmented-layout materialisation `Internal.create` performs), broadcasting `+ - * /`, `Neg`, the `apply*` gather family, `reshape` `ravel` `matCopy` `copy` `item` `flatten`; reductions `sum` `mean` `min` `max` `argmin` `argmax` `std` `variance` `norm`; axis family `sumAxis` `meanAxis` `minAxis` `maxAxis` `stdAxis` `cumsumAxis` `cummax` `cummin` + `rowSums`/`colSums`/`rowMeans`/`colMeans`; elementwise `abs` `power` `exp` `log` `sqrt` `clip` `cumsum`. **Bit-identical except `exp`/`log`** (see below): `sumD`'s 8-way unrolled combine tree and its pinned 16-chunk decomposition are reproduced exactly, `abs` keeps `-0.0` where `f64::abs` would not, `power` is repeated multiplication rather than `powi`, `cumsum` returns `1×n` whatever the input shape — and, critically, **which summation algorithm a matrix gets is a function of its layout in both languages alike** (see the view-model note below). Phase (c) — `MatMut` writes, `MatBool` masks, fancy indexing, `MatMathOps` (`sin`…`tanh`, `floor`/`ceil`/`trunc`, `log10`/`log2`, `sigmoid`/`relu`/`leakyRelu`/`elu`/`gelu`, `softmax`/`logSoftmax`, `dropout`) — and phase (e)'s `matmul` (`hstack`/`vstack` with it) are in (see below). Phase (e)'s linear algebra is in too — `udata::linalg` (`diagonal` `trace` `normOrd` `determinant` `inverse` `solve` `qrDecomposition` `outer` `cross` `kron` `tril` `triu` `fillna` `cov` `corrcoef`, all bit-identical ports of the Scala loops; `svd` `lstsq`/`leastSquares` `matrixRank` `pinv` `cholesky` as the crate's own Jacobi SVD / Cholesky, pinned to the JVM's LAPACK on a 2^-20 grid) and `udata::eig` (`eig`/`eigenvalues`: EISPACK `orthes`+`hqr2` as JAMA spells it, spectra pinned sorted on the same grid; eigenvectors are a basis, `A·v = λ·v` the only contract). The utility remainder is `udata::matutil`: `maximum`/`minimum` (+`Scalar`; `Ordering[Double]`, i.e. `java_double_compare`, so `minimum(NaN, 1) = 1` as in Scala, not NumPy), `sign` `round` `powerF` `hadamard` `allclose` `containsNaN` `exists` `wherePred` `nanToNum` `ndim` `toContiguous` `zipMap`, `mapRows` `mapCols` `filterRows` `applyAlongAxis` (closures over 1×n / n×1 views), the named broadcast helpers `addToEachRow`… `divEachCol`, `scale`, `repeat`/`repeatAxis`/`tile`, `vsplit`/`hsplit`/`split` (+`N`), `csvText`/`saveCSV`/`writeCsv` (Java `Double.toString` cells). Deliberately not ported: the print configuration (`show`, `precision`, `edgeItems`, `suppressScientific`, `snapshot`, `threshold`, `maxRows`/`maxCols`) — `Debug` prints; `iterator`/`foreach`/`eachRow`/`eachCol`/`typeName`/`shapes`/`isWeirdLayout` — Rust idiom or aliases. Phase (d) is `udata::pandas` — `idxmin idxmax sort argsort nlargest nsmallest between unique nunique valueCounts diff diffAxis shift pct_change percentile median (+Axis) describe rolling(window).{mean sum min max std} histogram histogramEdges`, every ordering under `java_double_compare` and every sort stable, so bits agree — and `udata::signal` — `polyval convolve correlate` (bit-identical), `polyfit` (through `lstsq`, 2^-20 grid). Still to come: `Mat[Big]` |
| `MatResult` `groupBy` / `merge` | `upath::matresult` on `CsvTable<f64>` | group keys by `doubleToLongBits` in first-appearance order, aggregates through `MatD` (`sum mean min max std`), joins emit left rows in order then per-row right matches; `_x`/`_y` suffixes and NaN gaps as Scala. Aggregated columns come out in header order on both sides |
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
as an exact-decimal invoice; fixed inputs, portable output), `marketSim` /
`market_sim` (the odd one out: not a tour of an API but a real 1,300-line workload —
200 paths × 100 years of price formation on a seeded `NumPyRng`, then `MatD`
reductions, drawdown episodes, exposure rules and four report modes. It is the
consumer that drove Tier 3 milestone 1, and the pair that proved the transcendental
question above is closable), `tprfRunner` / `tprf_runner` (`TprfRunner.data_generator`:
the `update` recurrences via `MatMut`, `hstack`, the pinned matmul, the noise blend —
every cell of `X` printed, so the matmul is on the record).

Ten committed fixtures under `../test-data/*-parity/` pin these — roughly 21,664 rows
(path 10,537 — incl. 38 `badpath`/`badpayload` rows · date 4,173 · tprf3 3,039 · big 629 · smartparse 539 · csv 458 ·
mat 1,864 · numpy-rng 378 · walk 30 · hash 23) — plus the byte-identical pair probe
(`jsrc/pairProbe.sc` / `examples/pair_probe.rs`). See `README.md`.

**`exp` is the one operation in the port that is not bit-identical, and every claim below
is measured, not assumed.** On a 200,000-value corpus:

| pair | differ | max |
| :--- | ---: | ---: |
| Rust `f64::exp` vs system C `exp` (`extern "C"`) | **0** | — |
| Rust `f64::exp` vs **NumPy** `np.exp` | **0** | — |
| Rust `f64::exp` vs JVM `Math.exp` | 1,122 (0.561%) | 1 ulp |
| system C `exp` vs JVM `Math.exp` | 1,122 (0.561%) | 1 ulp |
| JVM `StrictMath.exp` (fdlibm) vs JVM `Math.exp` | 19,340 (9.67%) | 1 ulp |
| Rust `libm` crate (pure-Rust musl/fdlibm) vs JVM `StrictMath.exp` | **0** | — |
| libm `log` vs JVM `Math.log` | 470 (0.235%) | 1 ulp |

Rust, the platform C library and NumPy are byte-identical to each other; the JVM stands
apart. But **the JVM is the more accurate of the two**, which is the fact that decides
what to do about it. Against exact arithmetic (60-digit decimal, 20,000 values):

| implementation | correctly rounded | off by 1 ulp |
| :--- | ---: | ---: |
| JVM `Math.exp` | **99.74%** | 0.26% |
| C / Rust / NumPy `exp` | 99.43% | 0.575% |

So matching NumPy here would mean deliberately adopting *its* last-ulp rounding errors.
That is worth separating from real NumPy fidelity: matching NumPy's **conventions**
(percentile interpolation, `str2num` formatting, summation order) is a genuine contract;
matching its `exp` noise is importing someone else's error, at a cost in both accuracy and
speed.

**Decision: both sides keep their native fast path.** Rust's `f64::exp` already equals
NumPy exactly and is the fastest option there; Scala's `Math.exp` is both the fastest
(a HotSpot assembly intrinsic, no call boundary) and the most accurate. Three alternatives
were measured and rejected:

- **`extern "C"` from Rust** — changes nothing: `f64::exp` already *is* that symbol on
  targets with a system libm.
- **fdlibm on both sides** (`StrictMath.exp` + the `libm` crate, which are bit-identical,
  verified above) — would give exact cross-language agreement for one line and one
  pure-Rust dependency, but fdlibm is the least accurate of all the candidates and the
  furthest from NumPy, losing on both stated priorities.
- **FFM/Panama on the Scala side** — worst performance of all: `exp` is one native call
  per element, so unlike BLAS (O(n³) work per O(n²) copy, hence the `blasThreshold = 216`
  crossover) there is nothing to amortize, and it replaces an inlined intrinsic with an
  opaque downcall that also blocks loop optimization.

`exp` is therefore pinned on a 2^-40 grid, exactly as `randn` is for its `log1p` tail, and
the contract is stated as "agrees to ~40 bits" rather than pretending to an exactness that
does not exist. Everything else in `mat-parity`, `min`/`max`/`cummax` included, stays
bit-for-bit.

**The same holds for `log`**, measured the same way: the JVM's `Math.log` differs from
libm's on **0.235%** of a 200,000-value corpus, never by more than 1 ulp. Treat every
transcendental as agreeing to ~1 ulp rather than exactly, and assume nothing about any one
of them without measuring it.

Consequence for consumers, and how it actually plays out — `jsrc/marketSim.sc` and
`examples/market_sim.rs` are the worked example. A 1-ulp difference is invisible at any
normal print precision, so it can only surface where a printed column's true value is
**identically zero** and the rounding noise is the only thing left in it. In marketSim
that is exactly one column: the always-invested rule against buy-and-hold, where
`eq(end) - eq(peak)` and `log(price(end)/price(peak))` are mathematically equal because
the cumulative sum telescopes. There the printed sign was a coin flip between the two
languages.

The fix is not a tolerance, and not a workaround: **a rendering whose digits are all zero
carries no sign.** `-0.0` and `+0.0` both print as ` 0.0`. That is the correct rendering
on its own merits — in a signed column, a blanked sign says "zero, direction not
resolvable at this precision", where `-0.0` would report noise as direction — and it is
already uni's convention in `numStr`. Note it must act on the *rendered string*: the
underlying value is a tiny non-zero (~1e-14) that merely rounds to `-0.0`, so normalising
signed zeros at the value level would miss it.

With that in place, marketSim is byte-identical across `-emit`, `-validate`, `-buffer`,
`-power` and `-strategies` at every size tried. So the practical rule for a new script is:
transcendental noise is only a parity risk in identically-zero columns, and blanking the
sign there removes it.

`mat-parity` is small by row count and unusually load-bearing: 13 sizes × 10 reductions,
compared as raw IEEE-754 bit patterns rather than decimal text, over a corpus of
alternating 1e6/1e-6 magnitudes drawn from `NumPyRNG` (not from `math.sin`, which the JVM
and Rust may round differently in the last ulp). The sizes straddle the 8-way unroll
boundary and *both* chunking thresholds — 4096, where summation goes parallel, and 65536,
where the `MaxSumChunks` cap starts binding. Both test suites additionally assert that a
naive left fold **disagrees** with `sumD` on that corpus, so a fixture that stopped
discriminating would fail loudly rather than pass vacuously.

## Porting checklist: where Scala and Rust quietly disagree

The goal of this document is that porting be *mechanical*. These are the places it is not —
each one found by byte-diffing a demo pair against its twin, mostly during the `marketSim`
port, and each one silent until it reached a printed digit. Read this before starting a
port, not after the diff fails.

**1. `.sum` has a different identity.** Rust's `Iterator::sum` for floats folds from
`-0.0`; Scala's `.sum` folds from `Numeric[Double].zero`, i.e. `+0.0`. `-0.0 + x == x` for
every `x`, so the two agree on every non-empty sum and disagree only on an **empty** one —
which then prints as `-0.0` on one side and `0.0` on the other. Fold explicitly:
`it.fold(0.0, |a, b| a + b)`. Found in a share-of-time column where no episode cleared the
threshold; it affected 18 call sites at once.

**2. `signum` differs at zero.** Scala's `Double.sign` returns `0.0` there (preserving the
zero's sign); Rust's `f64::signum` returns `±1.0` and never `0.0`. Any branch of the form
`a.sign == b.sign` changes meaning. Write the three-way test out.

**3. Sorting and the order statistics are total-order, and `total_cmp` is not quite it.**
Scala's `.sorted`/`.sortBy`/`.min`/`.max` on `Double` use `Ordering.Double.TotalOrdering`,
i.e. `java.lang.Double.compare`. Two rules follow that `<`/`>` do not have, and they are
easy to port wrongly because a random corpus never exercises them:

- **NaN ranks above every number**, so `max` of a lane containing NaN is NaN. With `>` it
  is the largest ordinary value, since every comparison against NaN is false.
- **`-0.0 < 0.0`**, so `min(0.0, -0.0)` is `-0.0` and `argmin` moves off index 0.

The near-miss is worse than the obvious miss: `f64::total_cmp` gets both of those right
and is still not `Double.compare`, because it implements the IEEE totalOrder predicate
and places **-NaN below -Infinity**, where `doubleToLongBits` canonicalises every NaN so
the JVM treats -NaN and +NaN as equal and both as the maximum. Use
`udata::mat::java_double_compare`. This cost a real bug: the Rust port shipped `<`/`>`
here and all 427 fixture rows passed, because `uniform(-1e6, 1e6)` produces no NaN and no
signed zero — which is why the fixture now carries 330 `adv/` rows, `negnan` among them.
Both languages' sorts are stable, so `sortBy(-x)` ties keep source order in both.

**4. Float formatting is not the same rounding.** Java's `%f` rounds the *shortest decimal
representation* half-up; Rust's `{:.n}` rounds the *exact binary value* half-to-even. They
disagree whenever the two straddle the boundary — rare per value, certain across a report
printing thousands. Use `udata::java_format_f`. Three further Java behaviours it does not
cover on its own: `%+w.df` joins the sign to the number *then* pads to width; `%-w.df`
left-justifies (`java_format_f` only right-justifies); and both leave NaN unsigned.

**5. `%n` is the platform line separator.** In a Java/Scala format string it is `\r\n` on
Windows, `\n` elsewhere — so a report using it is not even byte-stable against *itself*
across hosts. Use `\n`.

**6. Transcendentals agree to ~1 ulp, not exactly.** Measured above: `exp` differs on
0.561% of values, `log` on 0.235%, always by 1 ulp, because the JVM uses hand-written
intrinsics where Rust uses the platform libm. This is invisible at any normal print
precision and can only surface where a printed column's true value is **identically zero**
and the noise is all that remains. The fix is a rendering rule, not a tolerance: **a
rendering whose digits are all zero carries no sign.** Note it must act on the formatted
string — the underlying value is typically a tiny non-zero that merely *rounds* to `-0.0`,
so normalising signed zeros at the value level misses it.

**7. Elementwise `f64` methods are not always the Scala expression.** `f64::abs` maps
`-0.0` to `+0.0`, where Scala's `if x < zero then -x else x` leaves it alone; `powi` is
repeated squaring where a Scala loop is repeated multiplication; `f64::max`/`min` propagate
NaN differently from `math.max`/`math.min`. Port the *expression*, not the intent.

**8. `!(a..=b).contains(&x)` is not `x < a || x > b`.** They differ on NaN: `contains`
returns false for NaN, so the negation is true, where the explicit comparison chain is
false. Clippy suggests the rewrite; refuse it wherever NaN can reach the test.

**9. A view is not a copy, and the difference is arithmetic.** This is the one that would
have been easiest to "simplify" away, and the most expensive. `sumD` — the chunked, 8-way
unrolled sum — is only ever reached with a **contiguous array whose length is exactly
`rows * cols`**: four call sites (`sum`, `mean`, `std`, `variance`), each guarded that
way. `transpose`/`slice`/`broadcastTo` return zero-copy strided views that fail the guard
and are summed by the SAME algorithm over a different sequence. As of 0.17.0 one rule
covers every layout: **8 unrolled accumulators over the logical row-major sequence,
`min(MaxSumChunks, rows/8)` row blocks above 65536 elements, partials combined in block
order.** What differs between a matrix and its transpose is the sequence, not the
algorithm — so `m.sum` ≠ `m.T.sum` in the last ulp, and both are chunked.

Before 0.17.0 a strided view was one sequential accumulator, which made `m.T.sum` 3.3x
slower than NumPy with no way to improve it that did not move the last ulp. Both
languages changed together and the fixture was regenerated; `sum` on a view therefore
differs from what pre-0.17.0 produced. The contiguous path was not touched.

Two thresholds are part of that contract rather than tuning knobs, because each selects
between one block and many: `ParallelThreshold` (4096) for the contiguous `sumD` and
`StridedSumParallelThreshold` (65536) for the strided form. Change either and the answer
moves.

Most of the family spells the guard as the shared `fastD` predicate, but not all of it,
and the exceptions are worth knowing before porting: `sum` writes the test out inline in
*three* branches (`sumD`; a stride-indexed loop over the raw array when the view is
contiguous at offset 0 but shares a longer parent buffer; the general `Numeric` loop),
and `min`/`max`/`argmin`/`argmax`/`cummax`/`cummin` have no fast path at all. The two
loops inside `sum`'s Double case are the same arithmetic in the same order — the middle
branch holds `offset == 0`, so its stride equation *is* `at(i, j)` — which is why the
Rust port collapses them without changing a bit. Verify that kind of collapse; do not
assume it. Three consequences,
all reproduced in `mat.rs` rather than tidied up:

- Materialising every derived matrix into a fresh contiguous copy — the obvious Rust
  simplification — silently picks the *chunked* order where Scala picks the sequential
  one.
- `Internal.create` materialises a *fragmented* view back into a copy, and its test
  compares the major stride against the row count. Dropping a column therefore copies on
  a wide matrix and stays a view on a tall one: the same logical slice, two different
  algorithms, decided by aspect ratio.
- `std(axis)` goes further and uses two different *mean* algorithms — a plain fold when
  contiguous, `sumD` when not — so a matrix and its transpose report different standard
  deviations for the same lanes.

The `<rows>x<cols>` half of `test-data/mat-parity/` exists for exactly this, and both
suites assert the corpus actually distinguishes the two paths, so the rows cannot pass
vacuously.

The method that catches all of these is the same: a demo pair whose two halves print
byte-identical output, diffed at more than one size. Single-size agreement is weak evidence
— several of the above appeared only at small sample counts, where an empty collection or
an identically-zero column becomes reachable.

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

- (a) ~~`MatD`/`CVecD`/`RVecD` core — construction, slicing, transpose, broadcast
  arithmetic~~ **done**, though *not* over `Array2<f64>`: `MatD` is a flat
  `Arc<[f64]>` plus Scala's own `(transposed, offset, rs, cs)` descriptor. `Array2`
  forces a `Result` and an `Option` into paths that cannot fail, in a crate free of
  `unwrap`/`expect`/`panic!`, and it cannot express Scala's stride-0 broadcast or its
  fragmented-layout rule — which are load-bearing, not incidental (see checklist item
  9). `ArrayView2::from_shape` over the same buffer stays available, zero-copy, when
  matmul and the BLAS crossover arrive;
- (b) ~~the reduction/stat family (in `Mat` itself, not `MatDOps`): `sum mean std
  variance min max argmin argmax cumsum abs power norm` + `axis` variants~~ **done**;
- (c) ~~the `MatDOps` indexing surface: the `apply` gather family, mask indexing, and the
  `update` write family; `MatMathOps` elementwise math~~ **done**;
- (d) ~~pandas ops~~ **done** — `udata::pandas` (`MatPandasOps` + `RollingWindow`) and `udata::signal` (`MatSignalOps`), 469 `pd.*`/`sg.*` fixture rows;
- (e) ~~`matmul` and the BLAS question; `leastSquares` and the decomposition family~~ **done** — see "matmul" and "linear algebra" below;
- (f) ~~`matResultOps` join/groupBy~~ **done** — `upath::matresult`: `groupBy`/`groupByOps`/`merge` on `CsvTable<f64>` (Scala's `MatResult`), `AggOp`, `JoinType`; the two suites (`MatResultOpsSuite`, `matresult::tests`) run the same tables to the same layouts.

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

**As applied in (a)/(b).** Two additions the contract did not anticipate:

- **An `Axis` suffix for the axis overloads**, since `sum`/`sum(axis)` collide the same
  way `apply` overloads do: `sumAxis`, `meanAxis`, `minAxis`, `maxAxis`, `stdAxis`,
  `cumsumAxis`. `cummax`/`cummin` keep their bare names — Scala has no non-axis form of
  them, so there is nothing to disambiguate.
- **`slice` and `applyRowsCols` are different operations and must not be conflated.**
  Scala's `m.slice(rows, cols)` is a zero-copy *view*; `m(rows, cols)` gathers a fresh
  contiguous *copy*. They differ in more than allocation — the copy lands on the fast
  summation path and the view does not — so the port keeps both, and `head`/`tail` build
  on the copying one exactly as Scala's do.

**As applied in (c).** Three decisions the contract did not anticipate:

- **Mutation is a separate type reached by a panicking conversion.** `MatD::intoMut()`
  consumes the matrix and returns a `MatMut`, panicking if anything else holds the
  buffer. Scala and NumPy each have one always-mutable type, and writing through a view
  mutates the parent; no safe Rust design reproduces that, so the divergence is made
  loud rather than silent. It panics rather than returning a `Result` because a `Result`
  propagates — a ported function that mutates a matrix it received would return
  `Result<…>` where its Scala original returns a plain value, and every caller up the
  chain would inherit it, changing the shape of the port rather than one call site.
  Copy-on-write was the third candidate and is the worst: it reads exactly like Scala
  while inverting the semantics, since the view would stop tracking its parent.
  `MatD::zeros(r, c).intoMut()` is the one construction route, mirroring Scala's single
  `MatD.zeros`; `examples/tprf_runner.rs` is the reference call site.
- **`MatBool` is a real type, not `&[bool]`** — and not `MatB`, which is Scala's alias
  for `Mat[Big]` and stays reserved for that port. Scala's `Mat[Boolean]` supports `&&`/`||`/`!`,
  `all`/`any` with axis forms, and `where`; every one needs the shape. Rust cannot
  overload `&&`, so those are `BitAnd`/`BitOr`/`Not`, and `where` is a keyword, so
  `Mat.where(cond, x, y)` becomes `cond.whereMat(x, y)` / `cond.whereScalar(x, y)`.
  `:==`/`:!=` are not Rust identifiers either and become `eqTo`/`neTo`.
- **The mask family is IEEE in both languages, as of 0.17.0.** `gt`/`lt`/`gte`/`lte`/
  `eqTo`/`neTo` are false at every NaN and treat `-0.0 == 0.0`. Scala previously routed
  them through `Ordering[Double]` — `TotalOrdering`, which ranks NaN above every number
  and `-0.0` below `0.0` — and so disagreed with NumPy; the Scala side moved to match.
  `TotalOrdering` remains correct for `min`/`max`/`sort`/`argmax`, which are order
  statistics; a mask is not one. Note this is the *opposite* direction from the ordering
  rule the reductions follow, and the two are easy to conflate.

**`MatMathOps` is formula-pinned, and only its exact members are bit-pinned.** Every
method is a per-element function, so the contract is the formula as Scala spells it —
`relu` as `Math.max` (NaN survives, `-0.0` → `+0.0`, unlike Rust's `f64::max`), `log2` as
`ln x / ln 2`, `gelu`'s association, `softmax`'s plain-fold sum after max-subtraction —
and the port copies each choice. `floor ceil trunc relu leakyRelu dropout` are exact and
the fixture holds them bit for bit (`math.*` rows, 275 of them). The transcendental
members inherit the `exp` situation above; measured on the bounded corpus, JVM vs Rust:

| function | inputs that differ | max ulps |
| :--- | ---: | ---: |
| `sin cos tan asin acos atan atan2 log10 exp` | 0.1–21% | 1 |
| `sinh cosh tanh log2 sigmoid` | 0.1–28% | 2 |
| `elu` | 0.2% | 64 |
| `gelu` | 7% | 292,378 |

The last two are the formula's conditioning, not a divergence: `gelu` at x = −5 computes
`0.5·x·(1 + tanh(−8.45))` with `1 + tanh ≈ 4.5e-8`, so one ulp of `tanh` is 2e-9 of the
result while its absolute error stays ~1e-16. That is why those rows are pinned on an
**absolute** grid — 2⁻³², coarser than `exp`'s 2⁻⁴⁰ because outputs like `sinh(−5) ≈ 74`
leave 2⁻⁴⁰ only ~64 ulps wide (a 2-ulp libm disagreement then crosses a grid line once per
few thousand elements — which is exactly how the first regeneration failed); `softmax`/
`logSoftmax` on 2⁻²⁴, since every element of a lane inherits a 400-term sum's drift. The
corpus is bounded so every output is O(1) (`tan` on `m/4`, `arcsin arccos sinh cosh` on
`m/5`, logs on `|m|+1`). `dropout` takes the generator explicitly — the crate has no
global RNG — with Scala's draw order, so a seeded call agrees exactly.

**matmul: `matmulPure` is the pinned loop on both sides; `*@` in Scala is BLAS by
default (`os-best`), Rust's `matmul` is the pinned loop.** Decided on measurement (this machine, 24 threads, all BLAS columns OpenBLAS; ms/call,
best of runs; re-measure with `sbt "runMain uni.apps.MatmulProbe"`,
`cargo run --release --bin bench_matmul`, `python py/bench_matmul.py`):

| shape | Scala pure | Scala BLAS | Rust pure | Rust `matrixmultiply` | Rust OpenBLAS | NumPy |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| 64³ | 0.056 | 0.010 | 0.028 | 0.030 | 0.0084 | 0.0090 |
| 128³ | 0.18 | 0.089 | 0.12 | 0.15 | 0.094 | 0.083 |
| 256³ | 0.77 | 0.20 | 0.44 | 0.72 | 0.35 | 0.32 |
| 512³ | 4.7 | 0.7–1.25 | 2.4 | 4.3 | 1.08 | 0.91 |
| 512×8×512 | 0.23 | 0.21 | 0.47 | 0.41 | 0.36 | 0.36 |
| 512×512×8 | 0.16 | 0.085 | 0.10 | 0.16 | 0.094 | 0.085 |

Three facts decided it. The pure tiled loop — `TILE = 32`, parallel over row tiles, a
**sequential k-sum from 0.0 per cell** — is reproduced bit for bit by the port (22
fixture rows, `mmfnv`/`tmmfnv`, second operand a transposed view) and, since neither
runtime fuses `x*y + z`, is **independent of the machine**; nothing else in the table
is — an optimised BLAS and `matrixmultiply` pick kernels by CPU feature and thread
count. (The *reference* BLAS is the exception that proves the rule: its `dgemm` is a
sequential k-loop per cell and reproduces the pinned bits exactly, as it does on the Linux
CI box — which is why the sensitivity tests assert against a deliberately reassociated
kernel rather than against "BLAS differs".) For
Rust, pure-parallel already beats `matrixmultiply` above 128³, so the pin costs nothing
against the dependency-free alternative; OpenBLAS is 1.3–2.2× faster and needs the system
library — a build-time feature a library cannot switch on for its users, so the Rust
default stays pinned. Scala can choose at runtime, and does what NumPy does: `*@` goes to
a native BLAS by default (2–4× on large dense products, level with NumPy on every box),
and reproducibility is the opt-in — `-Duni.mat.blas=pure` for a program, `matmulPure` per
call. Every fixture generator and demo pair calls `matmulPure` explicitly, so the parity
contract never depends on either language's default.

The flag: Scala `-Duni.mat.blas=os-best|bundled|system|pure` or env `UNI_MAT_BLAS`
(property wins, read once; no programmatic setter; the Linux one-OpenBLAS-per-process
rule and the netlib-LAPACK path under `system` are documented on `Mat.blasChoice`); Rust
`--features blas`, which is already the act that links OpenBLAS. Both sides expose
`matmulPure` (pinned, whatever the mode) and `matmulBlas` (BLAS, whatever the mode; Rust:
only under the feature, so a call in a non-BLAS build is a compile error). `blasThreshold`
is tuning, not contract: products under it take the pinned loop in every mode, so it
decides which algorithm runs and moves last ulps.

**`Tprf3` multiplies through `*@`** — BLAS under the Scala default, the pinned loop under
`pure` and in Rust; its parity fixture compares to `rtol 1e-9`, so either mode passes.
Under `pure` the cost turned out
to be larger than the skinny-shape row above suggested — IS Full 0.25 → 0.67 ms at first
— because `Tprf3` multiplies narrow outputs (`*@ y`, `*@ β`: one to three columns) and
transposed-view left operands (`X'`), neither of which the square benchmarks exercise.
The answer was to make the pure loop fast on *those* shapes rather than to exempt one
consumer from the contract: `MatmulPure` is now a 4×4 register-accumulator microkernel
over packed panels (`B` 4-wide, `A` k-major when its k-stride is not 1), with a streaming
saxpy path for short K and narrow outputs, and a 2-D parallel split so few-row products
still fill the pool. Bit-identical throughout — every candidate was checked against the
fixture. Result on the large `Tprf3` rows: within ~1.2–1.9× of BLAS (launch variance on
these sub-ms rows is ±30%), pure ahead on the small rows; 512³ 6.5 → 1.4 ms. The Rust
kernel took the same microkernel (8-wide panels, since LLVM vectorises the lane loop):
512³ 2.4 → 1.7 ms, the skinny `512×512×8` 0.10 → 0.03, the middle sizes a wash — LLVM
was already vectorising the old loop, so the JVM's 4× is not repeatable there. What
remains between it and `matrixmultiply` single-threaded (14 vs 4.3 ms) is ISA: the crate
targets baseline x86-64 while `matrixmultiply` dispatches AVX2 at runtime, and closing
that needs `#[target_feature]` behind an `unsafe` call, which this crate reserves for FFI.

**Linear algebra: two contracts, stated per method.** The pure-JVM methods (`diagonal`
`trace` `norm(ord)` `determinant` `inverse` `solve` `qrDecomposition` `outer` `cross`
`kron` `tril` `triu` `fillna` `cov` `corrcoef`) are loops with a fixed association order,
so the port reproduces them operation for operation and the fixture pins their bits
(`la.*` rows, raw). The LAPACK-backed ones cannot be pinned that way: LAPACK's blocked
kernels reassociate, and the bundled and system OpenBLAS differ from each other in the
last ulps. The crate computes them itself — one-sided Jacobi (Hestenes) SVD behind
`svd`/`lstsq`/`pinv`/`matrixRank`, Cholesky–Banachiewicz behind `cholesky`, EISPACK
`orthes` + `hqr2` (the JAMA spelling) behind `eig`/`eigenvalues` — and the fixture pins
singular values, solutions, ranks and sorted spectra on a 2^-20 grid, on the shapes whose
conditioning keeps a 1e-13 kernel difference inside a grid cell (`MatParityGen.laTol`:
small, or far from square; the symmetric spectrum is scaled by its trace first).
Eigenvectors and singular vectors are a basis, not a canonical one — LAPACK's
normalisation is reproduced (unit norm, largest component real for complex pairs), signs
are not a contract, and only `A·v = λ·v` / `U·S·Vᵀ = A` are asserted. `dgesdd`'s
argument checker in netlib sizes `u` as m×m whatever `jobz` says, which is why the JVM's
`system`-mode path uses `dgesvd`; the Rust side has no such constraint. Two things Scala
throws for come back as `Err(Error::SingularMatrix)`: an exactly-zero LU pivot
(`determinant`/`inverse`/`solve`; NumPy's `det` would return 0) and a non-positive-definite
`cholesky`.

The Vector API was ruled out for this: `jdk.incubator.vector` must be added to the
module graph by every JVM that runs the library, and most scala-cli scripts do not, so a
hard dependency in `Mat` would break them at class-load time. A reflective kernel behind
a fallback is the next step if the remaining gap ever matters.

`slice` also takes `Range<i64>` rather than `Range<usize>`, because Scala reads a
negative *start* as an offset from the end (`-2 until 0`). Only the start is adjusted
there, and the length stays the range's own; that asymmetry is mirrored, not corrected.

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
6. ~~Mat phases (a)–(b), scoped by a real consumer rather than by API completeness:
   the first milestone is the ~14 operations `jsrc/marketSim.sc` needs, which lands a
   byte-identical demo pair and pins `sumD` bit-exactness against a live workload.~~
   **Done.** The consumer-first scoping worked: `marketSim` drove milestone 1, and the
   view model that followed was verified by re-running that same pair (still byte-
   identical across twelve configurations) alongside a 297-row 2-D fixture. Phase (c)
   followed: the aliasing decision `update` forces is settled (`MatMut`, reached by a
   panicking conversion), and mask indexing brought `MatBool`. Then matmul, which made
   `tprfRunner` / `tprf_runner` the eighth demo pair, and `MatMathOps`, which closed
   phase (c); the decomposition family closed phase (e), pandas and signal ops phase (d),
   `groupBy`/`merge` phase (f). What remains of `Mat` is `Mat[Big]`.

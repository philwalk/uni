## v0.14.0 — unreleased

**Bug fixes — view correctness (`Mat.scala`)**

Several operations read the raw backing array (`m.tdata`) directly, which for a
zero-copy view (e.g. a full-width row slice, `offset > 0`) is the *parent's* storage —
producing silently wrong results (wrong elements and wrong length). All now use
stride/offset-aware access:

- `gt`, `lt`, `gte`, `lte`, `:==`, `:!=` — rewritten via `m.map`
- `isnan`, `isinf`, `isfinite` — rewritten via `m.map`
- `std`, `variance` (general, non-Double path) — sum of squares now accumulates
  stride-aware instead of folding over the raw array
- `filterRows` — replaced `System.arraycopy` (which assumed column stride 1 and
  produced garbage for transposed matrices) with a stride-aware element copy

**Bug fixes — semantics**

- `containsNaN`: now checks `Numeric.toDouble(v).isNaN` (correct for Double, Float,
  and the `BigNaN` sentinel) instead of per-element `toString == "NaN"`
- `linspace(start, stop, num = 1)`: no longer truncates a fractional `start`
  (previously returned `start.toInt`)
- `power(n: Int)`: negative exponents are rejected before computing instead of
  throwing mid-loop after wasted work
- Tuple factory `Mat[T]((…), (…))`: `Float`, `Double`, `BigDecimal`, and `Long`
  elements are now *converted* to the target element type instead of cast —
  previously `MatD((1.0, 2.5f))` threw `ClassCastException`, `MatB((1, 2.5))`
  threw `ArrayStoreException`, and `Long` elements were rejected outright
- `saveCSV` on `MatB`: `BigNaN` now honors the `nanAs` parameter like Double/Float
  NaN (default `"NaN"`) — previously it was hard-coded to `"N/A"` regardless of
  `nanAs`, so a `loadMatB`/`saveCSV` round-trip silently changed the NaN spelling

**API additions**

- `<`, `<=`, `>`, `>=` operator aliases for `lt` / `lte` / `gt` / `gte` (scalar `T`
  and `Int` overloads, returning `Mat[Boolean]`), so NumPy-style `m > 0.0` now works.
  Equality remains `:==` / `:!=` — `==` and `!=` are defined on `Any` and cannot
  return `Mat[Boolean]`
- New `MatElem[T]` type class (`MatElem.scala`, exported from `uni`): bundles the
  Double→T conversion, precision-aware sqrt, and NaN sentinel, with givens for
  Double, Float, Big, and plain BigDecimal. Methods that previously dispatched on
  `ClassTag.runtimeClass` (sqrt, norm, std, round, power, percentile/median,
  nanToNum, corrcoef, rolling aggregations, arange/linspace, scale, describe,
  pct_change, qrDecomposition, eigenvalues, ~^, the tuple factory) now take
  `using MatElem[T]` — resolved silently from companion scope for normal callers;
  unsupported element types become compile-time errors instead of runtime throws.
  16 of 26 runtime-dispatch blocks deleted; the rest are per-type kernel selection
  and Double-only LAPACK gates

**API changes (breaking) — continued**

- `m.svd` returns **economy** shapes: U is nRows×p and Vt is p×nCols
  (p = min(nRows, nCols)) instead of full nRows×nRows / nCols×nCols — full U was
  O(n²) memory for tall-skinny regression matrices and no consumer (lstsq, pinv,
  matrixRank) ever read past column p. Reconstruction is `U *@ diag(s) *@ Vt`
  with a p×p sigma
- `round` / `power(Double)` / `nanToNum` on `MatB`: a non-finite Double result now
  becomes `BigNaN` instead of throwing `NumberFormatException` from
  `BigDecimal(Double.NaN)`
- `scale()` now standardizes with the **sample** standard deviation (ddof = 1,
  matching R's `scale()` and the inference convention, and consistent with
  `Tprf3.nanStdCols`) instead of the population std — values shrink by
  √(n/(n−1)). `m.std` itself is unchanged (population, NumPy `ddof=0` parity);
  the doc comment spells out both conventions
- Exception types are now consistent across the API — `IllegalArgumentException`
  (via `require`) for bad arguments/bounds, `UnsupportedOperationException` for
  unsupported element types, `ArithmeticException` for numerical failure
  (singular matrix, LAPACK `info ≠ 0`). Changed: `slice(rows, cols)` out of
  bounds threw `IndexOutOfBoundsException`, `power(n < 0)` threw
  `UnsupportedOperationException` — both now `IllegalArgumentException`

**Performance — continued**

- `Tprf3`: all K&P `J(k) = I − (1/k)·1·1'` products rewritten as row/column
  centering (`J(T) *@ M` ≡ subtract column means) instead of building a dense
  T×T matrix and multiplying — O(T·N) instead of O(T²·N). The always-on IS Full
  alpha block's `Xn' *@ J(T)` was the dominant cost of the whole benchmark and
  hypersensitive to BLAS/threading state (9.7 vs 75 ms across machine states for
  identical code). After: IS Full Large (T=650, N=40) ≈ **2.5 ms** without avar
  (was 56 ms same-machine), ≈ 21 ms with avar (was 138 ms). Affected sites:
  `estimate3prf` alpha and avar blocks, `tprfClosedForm`, `degreesOfFreedom`;
  `jMat` deleted, `centerRows` helper added. Validated bit-equivalent against
  the MATLAB K&P references (`jsrc/verifyTprfResults.sc`: max diff ≤ 4.4e-16).
  For benchmark fairness the same rewrite was applied to `py/tprf3fast.py`
  (verified against its explicit-J formulas at ≤ 4e-13): Python IS Full Large
  drops 10.0 → 1.8 ms, making IS Full roughly a tie — the honest result, since
  both sides are dominated by the same two batch solves. Also fixed a latent
  NumPy 2.x incompatibility in its avar loop (`float(1-element array)` was
  removed in NumPy 2.0; now `.item()`)
- `eigenvalues()` on `Mat[Double]` routes through LAPACK `dgeev` (eigenvalues-only,
  no eigenvector computation) instead of 500 unshifted QR iterations — O(n³) once
  with exact results (and correct real parts for non-symmetric input); the QR
  fallback remains for Float/Big
- Plain `*` (element-wise multiply) now takes the same contiguous-Double fast path
  as `*:*` — previously it always used the boxed generic loop
- New N×1 column-broadcast fast path in `+`, `-`, `*`, `*:*`, `/`
  (e.g. `m - m.mean(axis = 1)`); previously only 1×N row broadcasts had one and
  column broadcasts fell to the boxed path

**Internals**

- TASTY transparency fix: `object CVec` / `object RVec` moved from inside
  `object Mat` to package level (VecExts.scala), constructing through new
  `private[data]` cast bridges `Mat.mkCVec` / `Mat.mkRVec`. Inside `object Mat`
  the opaque types are transparent (`CVec[T] = Mat[T]`), so every method
  declared there recorded `Mat[T]` return types in TASTY, breaking typed
  dispatch for external callers; at package level the signatures stay
  `CVec[T]`/`RVec[T]`. The companions' extension methods (accessors,
  converters, scalar ops, `update`) moved into `VecOps` alongside the existing
  `.T`/`*@` overloads and now simply delegate via `asMat` — the
  inside-object-Mat recursion hazards (and the raw `MatData` field access they
  forced) no longer apply. Because the package-level companions are no longer
  in the opaque types' implicit scope, `uni/package.scala` now forwards
  `export uni.data.VecOps.*` so `import uni.*`-only clients keep (and gain
  TASTY-correct versions of) the full vector API — verified from an external
  scala-cli script (`jsrc/tastyCheck.sc`): `val rv: RVecD = cv.T`,
  typed `*@` dot/outer products, `show` labels, and `CVec(…)` factories all
  dispatch correctly against the published jar
- The MatD/MatB/MatF facades (~80 near-identical forwarders × 3) now share one
  `private[data] trait MatFacade[T: ClassTag: Fractional: MatElem]`; the only
  per-type members left are an abstract `fromBig` (CSV element conversion),
  MatD's Double-only extras (leastSquares, signal processing, `zeros(n)`/
  `ones(n)`/`randn(n)`/`rnorm(n)`, specialized RNG fills), and one-line
  `object MatB` / `object MatF` declarations. Behavioral nuance: facade
  Double-argument conversions now go through `MatElem.fromDouble`, so e.g.
  `MatB.full(r, c, Double.NaN)` produces `BigNaN` cells instead of throwing
  from `BigDecimal(NaN)` (consistent with the v0.14.0 MatB non-finite policy)
- File split (first step): the facades, `MatFacade`, `LeastSquaresResult`, and
  the `MatD`/`MatB`/`MatF`/vector type aliases moved from Mat.scala to a new
  `MatFacades.scala` (Mat.scala: 5,352 → 4,860 lines). Pure relocation of
  package-level definitions — no dispatch or API change

- The ~25 hand-copied Double fast-path guards
  (`runtimeClass == classOf[Double] && isContiguous && offset == 0 && …`) are now
  one pair of inline helpers, `fastD` / `fastD2`; `+`, `-`, `*:*`, `/` share a
  single `fastBinOp` kernel (also removing the mid-expression `return`s)
- ~350 lines of identical trig/activation loops (sin, cos, tan, arcsin, arccos,
  arctan, sinh, cosh, tanh, floor, ceil, log10, log2, trunc, leakyRelu, elu, gelu)
  collapsed to one-liners over a shared stride-aware `mapToDouble` kernel — which
  also gains the parallel contiguous-Double fast path these methods never had
- Thread safety: `globalRNG` is `@volatile` and every fill captures the generator
  once, so a concurrent `setSeed` cannot interleave two generators within one
  fill (NumPyRNG itself remains single-thread-reproducible, same contract as
  NumPy's global generator); print options are an immutable `PrintConfig` swapped
  atomically and `formatMatrix` reads one consistent snapshot
- Source hygiene in `Mat.scala`: removed first-person scaffolding comments
  ("Phase 0", "Choke Point", "your legacy code", "exactly like your tests"),
  a stray debug `println`, and commented-out code; `@annotation.unused` removed
  from public API methods (`svd`, `lstsq`, `matrixRank`, `eig`, `pinv`,
  `cholesky`, `arange`, `apply(unit)`, `RVec.zeros`) — where the `Fractional`
  evidence is intentionally unused (Double-only LAPACK kernels) the annotation
  now sits on the parameter, with a comment explaining it restricts the API to
  numeric element types

**API changes (breaking)**

- `MatD(1.0, 2.0, …)` / `MatB(…)` / `MatF(…)` flat varargs now build a **column**
  vector (n×1), matching `Mat(…)`, `CVec(…)`, every vector-producing factory
  (`randn`, `arange`, `linspace`, `fromSeq`), and Breeze's `DenseVector(…)` —
  previously these were the lone row-vector outliers. The `ReferenceGuide`
  examples written against column semantics now behave as documented. For an
  explicit row vector use `MatD.row(…)` / `Mat.row[T](…)` / `RVec(…)`
- `m ~^ 0.0` is now element-wise all-ones, matching `m ~^ 0` and NumPy `**`
  semantics — previously it returned the identity matrix (matrix-power semantics
  leaking into an otherwise element-wise operator)
- Removed `cloneMat` — it copied the entire parent array and dropped the view
  offset (wrong data for views); use `copy` / `matCopy`
- Removed `inspect` — never produced meaningful output
- `def data` is now `private[data]` — it exposed the raw backing array (the
  *parent's* storage for views), bypassing all layout invariants; use `toArray`
  or `flatten` for a flat copy
- Relaxed constraints: slicing (`m(::)`, `m(r, ::)`, `m(rows, cols)`, …), `head`,
  `tail`, `filterRows`, `applyAlongAxis`, `vsplit`, `hsplit`, and `split` no longer
  require `Fractional[T]` — they now work for any element type (e.g. `Mat[Int]`)

**Performance**

- New shared helpers `fillD` / `sumD` apply a uniform 4096-element threshold before
  going parallel across all Double fast paths (`+`, `-`, `*:*`, `/`, scalar ops,
  `unary_-`, `sigmoid`, `relu`, `sum`, `mean`, `std`, `variance`). Previously only
  scalar `/` had the threshold; every other op paid fork/join overhead on small
  matrices. Confirmed on Tprf3Bench Large: IS Full 9.7 ms vs 13 ms documented for
  v0.13.4 (the avar block does ~1,300 small-matrix ops per call)
- `sumD` rewritten from `DoubleStream.sum` to a chunked parallel sum over an
  8-accumulator unrolled kernel (independent accumulators break the FP-add latency
  chain; fixed chunk boundaries keep results deterministic). NumPy 2.4.1's
  SIMD reductions had overtaken the old implementation; measured on the
  bench.sc 1000×1000 suite (JVM 21):
  `sum` 0.45 → **0.09 ms**, `mean` 0.45 → **0.09 ms**, `std` 1.46 → **1.12 ms**
  (NumPy 2.4.1: 0.25 / 0.27 / 3.28 ms — both reductions flip from ~1.8× losses
  back to ~2.8× wins). Note: plain summation replaces DoubleStream's compensated
  summation, so last-ulp rounding of `sum`/`mean`/`std`/`variance`/`cov` may
  differ from v0.13.x; all 2,219 tests pass unchanged

**Tests**

- `ViewOpsSuite`: regression suite running every fixed operation against an offset
  row-slice view and a transposed matrix, compared against `matCopy` results
- `MatSemanticsSuite`: pins `~^` element-wise semantics, the `power` guard,
  `linspace(num=1)`, `containsNaN` (Double and BigNaN), `Fractional`-free
  slicing/splitting on `Mat[Int]`, the comparison-operator aliases (including
  offset-view correctness and precedence vs arithmetic), and tuple-factory
  element conversion for all numeric element types

**Build**

- `Compile / run / fork := true` (with `connectInput` and logger-routed output so
  `sbt --client` sessions see it): benchmarks now run in a fresh JVM instead of the
  long-lived sbt server, whose accumulated heap/JIT state inflated allocation-heavy
  timings (IS Full Large measured 29–67 ms in-server vs 9.7 ms forked), and the
  Vector API `javaOptions` (`--add-modules=jdk.incubator.vector`) now actually
  apply to `run` — previously they were silently ignored
- Tprf3Bench (Large, forked, this machine): IS Full 9.7 ms, OOS Rec 24 ms,
  OOS CV 61 ms — all at or better than the v0.13.4 documented numbers

**Docs**

- Refreshed all benchmark tables in `README.md` and `docs/MatDCheatSheet.md` with
  same-day, same-machine measurements of uni 0.14.0 vs NumPy 2.4.1 vs Breeze 2.1.0
  (JVM 21): MatD wins 9/9 scored ops vs NumPy and wins or ties 9/9 vs Breeze
  (geomean ~6.2×); 3PRF tables updated to the post-centering numbers with both
  implementations optimized (loops=5 OOS measurements): IS Full 1.2/1.3 ms tied,
  OOS Recursive 270/42 ms (6.4×), OOS Cross Val 720/77 ms (9.4×)
- `docs/ReferenceGuide.md`: added the eleven sections its table of contents promised
  but never delivered (Indexing and Slicing, Arithmetic, Broadcasting, Linear Algebra,
  Statistics, Element-wise Math, Machine Learning, RNG, Data Manipulation,
  Comparison/Boolean, Display), plus the v0.14.0 column-vararg factories and the
  `MatD(3, 4)`-is-zeros vs `MatD(3.0, 4.0)`-is-a-vector gotcha
- Fixed examples that never compiled: `docs/QuickStartGuide.md` used nonexistent
  `m > 5.0` / `m < 0.0` comparison operators (Mat has only `gt`/`lt`/`gte`/`lte`)
  and `Mat.randn[Double](r, c)` (randn is not generic); `docs/MatDCheatSheet.md`
  listed nonexistent `MatD.from(arr, r, c)` (now `MatD(r, c, arr)`)
- Corrected `.norm` (vector-only; matrices need `norm("fro")`), `cov` (NumPy
  rows-as-variables convention), and `sort()` (flattens by default) descriptions
- New `jsrc/docCheck.sc` compile-checks and runs every ReferenceGuide/QuickStart
  snippet against the published library — this is what caught the errors above

---

## v0.13.4 — 2026-05-23

**Performance — Double unboxing elimination (`Mat.scala`)**

Profiling of the 3PRF benchmark revealed that generic `Numeric[T]` / `Fractional[T]`
dispatch was boxing every `Double` operand.  All high-frequency operations on contiguous
`MatD` arrays now have dedicated fast paths that cast `tdata` to `Array[Double]` and use
raw arithmetic, bypassing the typeclass layer entirely.

Operations that gained fast paths:
- `+`, `-`, `*:*` (Hadamard element-wise): same-shape parallel and 1×N broadcast
- `unary_-`: parallel negation
- Scalar `+(T)`, `-(T)`, `*(T)`, `/(T)`: parallel scalar broadcast (all four operators)
- `sum(axis=0/1)`: direct while-loop accumulation, no `Numeric.plus` dispatch
- `std(axis=0/1)`: two-pass direct loop (mean then variance), no `Array.tabulate` boxing
- `/(other: Mat[T])`: same-shape parallel and 1×N broadcast division

**Performance — 3PRF (`Tprf3.scala`)**

`nanStdCols`, `nanMeanCols`, `nanMean`, `nanOls`: replaced `.collect`/`.filter`/`.map`/`.sum`
chains on ranges (which produce `Seq[Double]` and box every element) with explicit `while`
loops accumulating into `Double` locals and `Array.newBuilder[Int]`.

**Bug fix — nested-parallelism regression in scalar `/(T)`**

The initial `/(T)` fast path used `parallelSetAll` unconditionally, which caused severe
fork/join pool thrashing when called inside the OOS parallel loop on small vectors
(e.g. the 1×N result of `sum(axis=0)` divided by row count in `centerColumns`).
Fixed by using a sequential while loop for arrays smaller than 4096 elements, matching
the approach used for threshold-based dispatch in the other scalar ops.

**Measured improvements (Windows 11, JVM 17, T=650 N=40 L=2):**

| Operation | v0.13.3 | v0.13.4 | Speedup |
| :--- | ---: | ---: | ---: |
| 3PRF OOS Recursive | 159 ms | 27 ms | **5.9×** |
| 3PRF OOS Cross Val | 375 ms | 66 ms | **5.7×** |
| 3PRF IS Full | 19 ms | 13 ms | **1.5×** |

MatD now wins **8/8** core operations vs NumPy on Windows (was 7/8; `sum` flipped from
a marginal loss to 1.8× win). Geometric mean vs Breeze improved from 3.1× to 3.3×.

**New: `uni.io.Cksum`**

- `Cksum.cksum(bytes: Array[Byte]): (Long, Long)` — POSIX `cksum`-compatible CRC32/length
- `Cksum.cksum(bytes: Iterator[Byte]): (Long, Long)` — streaming variant
- `Cksum.cksum(path: Path): (Long, Long)` — file variant
- `HashSuite`: new test suite covering CRC32 correctness and streaming equivalence

**Benchmarks**

- `bench.sc`, `benchBreeze.sc`, `py/bench.py`: added `mean(1000×1000)` and `std(1000×1000)`
  rows to all three benchmark scripts
- Updated all documented benchmark numbers in `README.md` and `docs/MatDCheatSheet.md`
  to reflect results measured on Windows 11 with the current optimisations

---

## v0.13.3 — 2026-05-10

**Bug fixes — Linux BLAS**
- Fixed bytedeco/OpenBLAS native library loading failure on Linux that prevented LAPACK operations
  (SVD, eigenvalues, Cholesky) from initialising correctly
- `Mat.matMultiply`: detect at runtime whether netlib loaded its native JNIBLAS implementation;
  fall back to bytedeco/OpenBLAS matmul only when the native path is unavailable, restoring
  optimal performance on Linux systems where `libblas.so.3` is present

**Bug fixes — Windows path handling**
- `Resolver.classify`: `C://ghcup/bin` (double slash after drive letter) was incorrectly classified
  as `Invalid` because the old guard was `p.contains("://")`; changed to `p.indexOf("://") > 1`
  so single-character drive letters are not mistaken for URI schemes
- `Proc.whereInPath`: replaced `sys.env.get("PATH")` (Scala's case-sensitive `Map`) with
  `System.getenv("PATH")` (case-insensitive on Windows) so the variable is found regardless
  of whether the OS names it `PATH` or `Path`
- `Proc.whereInPath`: replaced `Files.isExecutable` with `Files.exists` on Windows —
  `isExecutable` is unreliable for system executables (ACL check can return `false` for
  files that are plainly runnable)
- `Proc.whereInPath`: switched from `java.nio.file.Paths.get` to `uni.Paths.get` so that
  POSIX-style PATH entries emitted by MSYS2/Git Bash shells resolve correctly on Windows

**CI — Windows support**
- Added `windows-latest` to the CI test matrix
- Added `.gitattributes` enforcing LF line endings for `*.scala`, `*.sc`, `*.sbt`, `*.yml`,
  `*.md` — prevents Git's `core.autocrlf` from injecting `\r` into triple-quoted string
  literals, which broke `assertEquals` comparisons on Windows runners
- `RootRelativeTest`: added `assume` guard to skip drive-letter tests when the MSYS/Git Bash
  root (`C:`) is on a different drive than the workspace (`D:`) — a common CI runner layout
- `MatCoverageSuite`: raised `munitTimeout` to 120 s — Windows Defender scans bytedeco's
  extracted native DLL on first use, which can exceed MUnit's default 30 s timeout on CI
- Release workflow: publish step now requires tests to pass on all three platforms
  (ubuntu, macos, windows) before Sonatype upload proceeds

**Tests**
- `BlasDiagSuite`: new suite verifying BLAS backend selection logic on Linux
- `UniRootCoverageSuite`: regression test asserting that `C://ghcup/bin` and similar
  double-slash Windows paths are classified as `Absolute`, not `Invalid`

**Benchmarks (internal)**
- Cleaned up and extended matmul benchmark scripts (`bench.sc`, `benchBreeze.sc`,
  `benchMatmulNetlib.sc`, `benchMatmulBytedeco.sc`)

---

## v0.13.2

**Subprocess API — deadlock fix and cleanup**
- `Proc.run(cmd*)`: replaced `LazyList`/queue-backed lazy streams with eager draining threads —
  a dedicated daemon thread drains each output queue into a `ListBuffer` concurrently with
  `process.waitFor()`, eliminating the bounded-queue (64-slot) deadlock on long-running commands
- `ProcResult.lines` / `.errLines` now return `Seq[String]` (eagerly collected) instead of lazy `LazyList`
- Streaming `run(cmd*)(out, err)` overload no longer requires an implicit `ExecutionContext` —
  switched from `Future`-based readers to plain daemon `Thread`s
- Added `Int` extensions (moved from `PathsUtils` to `ProcUtils`, now package-level):
  - `status !! msg` — log to stderr on non-zero exit; chainable
  - `status orElse f` — invoke callback with error description on failure
  - `status orFail msg` — short-circuit a `failFast` block on non-zero exit
- Added `failFast { ... }` block: any `.orFail` call inside short-circuits the block and returns the failing status

**Path I/O — safe resource management**
- Added `p.withLines[A](f: Iterator[String] => A): A` — bracket pattern using `Using.resource`;
  guarantees stream close even on partial reads or exceptions; also available with a `charset` overload
- Added `p.eachLine(f: String => Unit)` — convenience wrapper around `withLines`; also available with a `charset` overload
- `firstLine`, `lines`, `lines(charset)` reimplemented via `withLines` (safe resource management)
- All new methods also available on `java.io.File` extension block
- `streamLines` made `private`; now wraps input in `BufferedInputStream` for better performance
- Removed unreliable `finalize()` safety net from `streamLines` iterator

**New app: `SourceTimestamps`**
- `src/main/scala/apps/SourceTimestamps.scala`: lists Scala source files whose filesystem mtime
  is newer than the last git commit timestamp; emits `touch -d` commands to sync them

**`updateVersion.sc` improvements**
- Added `-v` (verbose), `--` (report version and exit) flags
- Now accepts explicit file/directory arguments to scope the version update

**Tests**
- `PathExtsSuite`: 12 new tests covering `eachLine` and `withLines` for both `Path` and `java.io.File`
- `ProcSuite`: 8 new tests including a deadlock regression test (>64 output lines), nested streaming,
  `failFast`/`orFail`, `orElse`, and `toOption` composition patterns
- `StreamLinesSuite`: refactored to use `p.linesStream` instead of internal `streamLines` calls directly

## v0.13.1

**Bug fixes**
- `Paths`: dotfile paths (e.g. `.gitignore`, `.env`) were incorrectly resolved relative to
  `userdir` with the leading dot stripped — now preserved correctly
- `Big`: percentage strings (`"75%"`) now parse to `0.75` instead of `BigNaN`
- `PathExts`: corrected stale deprecation messages (`asFast` → `asFile`, `toPath` → `asPath`)

**Subprocess API**
- Extract `object Proc` to dedicated `ProcUtils.scala`
- Add fluent `proc(cmd*)` builder with `.cwd()`, `.env()`, `.stdin()`, `.timeout()`, `.run()`, `.stream()`
- `run(cmd*)` stdout/stderr backed by lazy `LinkedBlockingQueue` + daemon reader threads
- `ProcResult` extends `IndexedSeq[String]`; add `orElse`, `headOnly`, `takeOnly(n)`
- Add `Proc.whereInPath(prog): Option[String]`

**Matrix API**
- Add `Mat.eachCol` / `Mat.eachRow` — named alternatives to `m(::, *).map(f)` / `m(*, ::).map(f)`;
  avoids `*` import conflict when mixing `uni.data.*` and `breeze.linalg.*`

**Documentation**
- `SubprocessAPI.md`: document `proc` builder, full `ProcResult` API, environment utilities
- `MatDCheatSheet.md`: add "Migrating from Breeze" table; add reshape/flatten/ravel/size rows;
  show all equivalent MatD spellings for column/row mapping
- `README.md`: add reshape and per-axis mapping rows to NumPy mapping table

## v0.13.0

- See release commit for full change list

## v0.12.1
- Windows: netlib 3.2.0 publication JNIBLAS ->  Accelerate framework (always present, zero user setup)
- match on Opaque type `Big` at runtime
- Path extension methods return type implicit in method names
  - docs/UniScriptingTools.md details updated to match implementation
  - linesStream: Iterator[String]
  - lines: Seq[String])    | all lines; UTF-8 with Latin-1 fallback
  - linesStream: Iterator[String] | streaming lines; suitable for large files
  - firstLine: String | first line from Iterator
  - contentAsString: String | entire file as a string
  - byteArray: Array[Byte] | raw file content as bytes

**CSV**

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.csvRows` | `Seq[Seq[String]]` | all parsed CSV rows |
| `.csvRowsStream` | `Iterator[Seq[String]]` | streaming CSV rows; suitable for large files |
| `.csvRowsAsync` | `Iterator[Seq[String]]` | async variant |
| `.csvRows(onRow)` | `Unit` | callback-per-row variant |


## v0.11.2
- Add `foreach` for `RowsView` and `ColsView` (enables `for (row <- m(*, ::))` and `for (col <- m(::, *))`)
- Fall back to bytedeco/OpenBLAS matmul if netlib fails to load its fast native implementation
- Align `threePrfUni.sc` and `threePrfBreeze.sc` RNG streams for reproducible cross-validation
- Add `release-and-publish.sh` release script

## v0.11.0
- Switch matrix multiply backend from bytedeco/OpenBLAS to `dev.ludovic.netlib:blas:3.1.1`
  - macOS: JNIBLAS → Accelerate framework (always present, zero user setup)
  - Linux: JNIBLAS → `libblas.so.3` (install `libopenblas0` for best performance)
  - Windows: falls back to VectorBLAS (Java Vector API SIMD) pending netlib 3.2.0 publication
- Add opaque types `CVec[T]` / `RVec[T]` with full `*@` dispatch table
- Rename matrix multiply operator `~@` → `*@`
- Add `.item` to extract scalar from a 1×1 matrix
- Add `uni.plot` package with `pairs()` scatterplot matrix
- Add CSV read/write extension methods
- macOS added to CI matrix

## v0.10.2
- Last release using bytedeco/OpenBLAS as the matrix multiply backend
- Add `uni.plot` package (XChart-based visualization)

## v0.10.1 and earlier
- added Windows filetype enum and classifier
- refactor Paths.scala removing unrelated code
- switch tests to munit
- add more test cases
- dropped all third-party deps except test harness
- fast LinesIterator for streaming / large files 
- fast csv streaming with delimiter-detection
- performance refactor + csv extension methods
- update README
- lots of bug fixes as a side effect of refactor
- from-scratch rewrite of org.vastblue:pallet

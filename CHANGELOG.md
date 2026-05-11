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

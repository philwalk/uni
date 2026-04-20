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

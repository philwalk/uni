# uni (Rust)

A standalone Rust port of the [uni](https://github.com/philwalk/uni) Scala library's
scripting surface and numerics — no JNI, no date crate, no decimal crate, no regex engine.
It exists so the same script logic runs outside the JVM, and so each implementation is the
other's independent second opinion.

## The contract

**Public method names match the Scala spelling** (`baseName`, `plusDays`, `parseDateSmart`),
so a line written against the Scala compiles here unchanged. CamelCase marks a uni API
mirror; snake_case marks an internal helper or a `try_*` Result variant — the case tells you
whether a Scala counterpart exists.

Where the languages must differ, the difference is loud, never silent: this crate never
panics where Scala throws, and a charset it has no tables for is an `Unsupported` error
rather than a silent UTF-8 misread. Several of those differences have since been resolved in
Scala's favour of this port's answer rather than papered over: a refused `copyTo` is `None`
in both languages, and `sqrt` of a negative and a negative `pow` exponent are `BigNaN` in
both (as of 0.16.0), with fixture rows pinning them.

## Documentation

Three guides, each paralleling its Scala counterpart section for section so the two can be
read side by side, every example compiled:

- [`docs/RustQuickStart.md`](docs/RustQuickStart.md) — the matrix library from creation to
  `lstsq`, with the Scala `QuickStartGuide.md`'s sections.
- [`docs/RustCheatSheet.md`](docs/RustCheatSheet.md) — every operation in three columns: uni
  Scala | uni Rust | NumPy; ends in one program that calls every table row.
- [`docs/RustScriptingGuide.md`](docs/RustScriptingGuide.md) — paths, files, CSV tables,
  command-line arguments, dates and exact decimals.
- API reference: `cargo doc --open`; what is pinned to the JVM and how: [`PARITY.md`](PARITY.md).

Every ```rust block with a `fn main` in this file and `docs/` is compiled by
`checkRustDocs.sh` (CI, and release gate 6d), so the examples cannot rot.

## Modules

| module | ports | notes |
| :--- | :--- | :--- |
| `upath` | all 92 `uni.ext.PathExts` Path extensions | paths, metadata, traversal, mutation, line I/O, CSV, hashes |
| `utime` | `UniDateTime`, `DateFormat`, `SmartParse` | field arithmetic, pattern formatting, format-autodetecting parsing |
| `udata` | `Big` and its CSV loaders; all of `Mat[Double]` as `MatD`/`CVecD`/`RVecD` — `mat`/`mataxis` (view model, reductions), `matmut`/`matbool` (writes, masks, fancy indexing), `matmath`, `matmul`, `linalg`/`eig` (decompositions), `matutil`, `pandas`, `signal`; `Mat[Big]` as `MatB` (`matbig`) and `Mat[Float]` as `MatF` (`matf`) on the same generic `Mat<T>` core | `java.math.BigDecimal` semantics on base-10⁹ limbs; matrices carry Scala's own stride descriptor, so `transpose`/`slice`/`broadcastTo` stay zero-copy views — which is what keeps the reductions bit-identical; `MatB` reproduces the JVM's exact decimals to the last digit and scale |
| `upath::matresult` | `matResultOps.groupBy` / `merge` on `CsvTable<f64>` | group and join layouts identical to the Scala |
| `numpy_rng` | `uni.data.NumPyRNG` | bit-identical to `np.random.default_rng` |
| `t3prf` | `uni.stats.Tprf3` | the crate's original content, and its former name |

## Parity

Two mechanisms, catching different things.

**Fixtures — per-method values.** Ten files under `../test-data/*-parity/` hold ~20,600 rows
of the Scala implementation's own answers — `java.time`, `java.math.BigDecimal` and NumPy are
the transitive oracles. A Scala suite and a Rust test check each independently, so neither
language needs the other installed. Because `PathContext` takes `is_windows` as *data*, this
side verifies the **Windows** rule set from Linux and macOS as well; the Scala suite cannot,
since its `isWin` comes from `os.name`.

Regenerating a fixture rewrites the very values the tests check: do it only when the change
in answers is intended (each generator's header names its `sbt runMain` command), and review
the diff.

**Demo pairs — composition.** Each `examples/<name>.rs` has a twin at `../jsrc/<name>.sc`,
and the two print byte-identical output:

| pair | covers |
| :--- | :--- |
| `pair_probe` | ~50 metadata and mutation operations on identical trees |
| `treestat` | traversal, listings, size and age statistics, the hash family, seeded sampling, CSV, the full `cli` cursor API |
| `pathshow` | every path rendering, mount resolution, drive-relative forms, the BadPath family, `String` extensions |
| `datecalc` | the `utime` surface on fixed inputs — output is identical on any machine, any day |
| `bigcalc` | the `udata` surface as an exact-money invoice, scalar `Big` and then the same invoice as a `MatB` (elementwise, `matmul`, folds, masks, exact-decimal LU) — output is identical on any machine |
| `forecast` | seeded `randn` → byte-identical CSV → `loadMatBig` → the 3PRF closed forms and `forecast3prf` |
| `market_sim` | not a tour of an API but a 1,300-line workload: seeded price formation over 200 paths × 100 years, `MatD` reductions, drawdown episodes, exposure rules, and five report modes — the consumer that drove the `MatD` port |
| `tprf_runner` | `TprfRunner.data_generator`: the `MatMut` recurrences, `hstack`, the pinned matmul (`matmulPure` on the Scala side, `matmul` here), the noise blend — every cell of `X` printed, identical on any machine |

```bash
cargo build --example bigcalc
./target/debug/examples/bigcalc > rust.out       # run the exe, not `cargo run`:
scala-cli run ../jsrc/bigcalc.sc > scala.out     # cargo's pipe can eat blank lines
diff scala.out rust.out
```

Details, the determinism techniques, and how to add a pair: `../docs/DemoPairs.md`.

## Build

```bash
make all                       # test, fmt, clippy, file-size check
cargo test
cargo build --release --bin bench_mat --bin bench_tprf3
```

The two `bench_*` binaries are the Rust columns of the cross-language tables in the Scala
docs. They print one `[Rust] <label>  <ms> ms/call` line per row and a `config:` line;
`sbt "runMain uni.apps.BenchAll"` (run from the repo root) drives them alongside NumPy and
Scala and emits the finished markdown. Running one alone gives just that column.

Details and per-method tables live with the Scala docs: `../docs/PathIOReference.md`,
`../docs/DateTimeParser.md`, `../docs/BigTypeGuide.md`.

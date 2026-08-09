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
panics where Scala throws (a refused `copyTo` is `None` in both languages as of 0.16.0; a
negative `sqrt` is `BigNaN` here where Scala throws), and a charset it has no tables for is
an `Unsupported` error rather than a silent UTF-8 misread.

## Modules

| module | ports | notes |
| :--- | :--- | :--- |
| `upath` | all 92 `uni.ext.PathExts` Path extensions | paths, metadata, traversal, mutation, line I/O, CSV, hashes |
| `utime` | `UniDateTime`, `DateFormat`, `SmartParse` | field arithmetic, pattern formatting, format-autodetecting parsing |
| `udata` | `Big` and its CSV loaders | `java.math.BigDecimal` semantics on base-10⁹ limbs |
| `numpy_rng` | `uni.data.NumPyRNG` | bit-identical to `np.random.default_rng` |
| `t3prf` | `uni.stats.Tprf3` | the crate's original content, and its former name |

## Parity

Nine committed fixtures under `../test-data/*-parity/` hold the Scala implementation's own
answers — `java.time`, `java.math.BigDecimal` and NumPy are the transitive oracles — and a
Scala suite and a Rust test check each independently, so neither language needs the other
installed. `examples/pair_probe.rs` and `../jsrc/pairProbe.sc` are a matched probe pair
whose outputs diff byte-identical, covering composition where fixtures pin single methods.

Regenerating a fixture rewrites the very values the tests check: do it only when the change
in answers is intended (each generator's header names its `sbt runMain` command), and review
the diff.

## Build

```bash
make all                       # test, fmt, clippy, file-size check
cargo test
cargo run --release --bin bench_tprf3
```

Details and per-method tables live with the Scala docs: `../docs/PathIOReference.md`,
`../docs/DateTimeParser.md`, `../docs/BigTypeGuide.md`.

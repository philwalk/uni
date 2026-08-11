# Matched demo pairs: examples that are also parity tests

Every demo in `jsrc/` has a twin in `rust/examples/` with the same base name, written to
read line-for-line alike. The two print **byte-identical output**, so `diff` is a test:

```bash
scala-cli run jsrc/bigcalc.sc                      > scala.out
cargo build --manifest-path rust/Cargo.toml --example bigcalc
rust/target/debug/examples/bigcalc                 > rust.out
diff scala.out rust.out          # expect no output
```

On Windows, run the built executable rather than `cargo run` when redirecting: cargo's
output plumbing can swallow blank lines, which shows up as a spurious diff.

## Why they exist alongside the fixtures

The nine `test-data/*-parity/` fixtures pin **one method at a time** against recorded
values. That leaves two gaps a demo closes:

- **A method with no fixture row is pinned by nothing.** The first `pairProbe` run caught
  `canRead`/`canExecute` answering `false` for every directory on Windows, and a `mkdirs`
  that threw on one side — neither had a fixture.
- **Sequences are pinned by nothing.** Composition is where ports drift: each call agrees,
  the pipeline does not. `forecast` chains seeded random numbers → CSV text → `loadMatBig` →
  three estimators; any single-step divergence changes the final line.

They also serve as documentation that cannot rot, because CI-adjacent verification fails the
moment a doc example stops matching reality.

## The pairs

| pair | demonstrates | portability of the output |
| :--- | :--- | :--- |
| `pairProbe` | ~50 metadata and mutation operations on identical trees: existence and permission predicates, timestamps, copy/rename/delete collision answers and status codes, write/read round trips | same machine |
| `treestat` | a genuinely useful tool: recursive walk with `pathsTree`, immediate listings (`paths`/`subdirs`/`subfiles`), size aggregation by extension, age buckets, digests of the largest file (`hash64`, `cksum`, `md5`, `sha256`), deterministic sampling, CSV round trip with delimiter detection, and the whole `eachArg` cursor family | same machine |
| `pathshow` | the conversion layer: `posx`/`localpath`/`dospath`/`noDrive`/`relpath`/`stdpath`/`posix`, `segments`/`baseName`/`ext`/`reversePath`, mount-table resolution of `/c/…` and `/usr/…`, tilde and dot expansion, drive-relative `C:`/`C:foo`, the BadPath family, `isSameFile`, and the `String` extensions | same machine |
| `datecalc` | the date surface: `parseDateSmart` (with the month-first/day-first split), `quikDate`/`quikDateTime`, pattern formatting, field accessors, epoch-day round trips, calendar helpers and month-end clamping, the `plus*`/`minus*` and `with*` families, the between/duration family, `getMillis`/`epoch2DateTime`, the BadDate sentinel absorbing arithmetic | **any machine, any day** |
| `bigcalc` | the decimal surface, framed as an exact-money invoice: parse/`toPlainString`, `str2num`/`isNumeric`, arithmetic through the operator overloads, `sqrt`, integer and fractional `pow`, all seven rounding modes plus `round(MathContext)`, `numStr`/`numStrPct` variants, and the BigNaN sentinel | **any machine** |
| `forecast` | numerics end to end: seeded `randn` → CSV written through `Big`'s rendering (the files match byte for byte too) → `loadMatBig` → `tprfClosedForm`, `plsClosedForm`, `pls1Fit`, and `forecast3prf` in-sample and both OOS procedures | same machine |

## How byte-identical output is achieved

Determinism is designed in, not hoped for:

- **Every float prints through `Big` + `numStr`**, whose rendering is itself fixture-pinned,
  so no platform's float formatting can leak into the comparison.
- **Ages measure against a date (midnight), never an instant**, so two runs on the same day
  agree.
- **Listings sort on the root-relative path**, since neither `Files.walk` nor `read_dir`
  promises sibling order.
- **Sampling is seeded** — `NumPyRng` reproduces `uni.data.NumPyRNG` draw for draw.
- **Fixed inputs** in `datecalc` and `bigcalc` (no filesystem, no clock) make those two
  portable across machines and time, so they work as acceptance tests anywhere.

## What they have caught

- `canRead`/`canExecute` reporting `false` for directories on Windows
- `mkdirs` throwing on one side where the other returned `false`
- `UPath::is_absolute` applying the Windows root rule under POSIX rules, which made
  `to_absolute` prefix the working directory twice (found on the first Linux run)
- a fixture row that had pinned NTFS directory-enumeration order as if it were API
- `"OOS CV"` not being a procedure name — identically in both languages, which is its own
  small parity testimonial
- two real API divergences, since **fixed rather than documented**: `sqrt` of a negative and
  a negative `pow` exponent now answer `BigNaN` in both languages (Scala used to throw), and
  those rows are pinned
- an incoherence in `isNumeric`, which rejected `$1,234.56` while `str2num` parsed it
  happily — now one delegated definition

## Adding a pair

1. Write the Scala half in `jsrc/`, with the run recipe and the feature list in a header
   comment. Shadow `println` inside the object (`def println(s: String = ""): Unit =
   print(s"$s\n")`) so no carriage returns reach the output.
2. Write the Rust twin in `rust/examples/`, mirroring statement for statement — the shared
   API is camelCase on both sides precisely so this stays mechanical.
3. Make any floating-point output go through `numStr`, and remove every other source of
   run-to-run variation.
4. `diff` the two. Then keep them diffing: a pair that is never run is a pair that is
   already wrong.

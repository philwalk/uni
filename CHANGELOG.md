## Unreleased

**0.16.0, not 0.15.2.** `versionScheme := semver-spec` is declared, and under 0.x the
MINOR position acts as MAJOR -- so a release that removes public API and changes output
bumps it. This one does both: `ChronoParse` and `parseDateChrono` are gone; `hash64`
returns a different value for any file of 64 bytes or more; `relpath` returns a relative
path where it returned an absolute one; `toString` rejects a pattern it used to accept
(capital `Y`); `posx` strips every trailing separator rather than one; `standardizePath`
resolves against the config's working directory rather than the JVM's; `DateFormat`
renders month names in English regardless of locale; file timestamps render in UTC where
they rendered in system-local time; `Path.weekDay` returns an `Int` rather than a
`java.time.DayOfWeek`; every line reader preserves an interior carriage return where it
used to discard it; the six `lastMod*` alias spellings are gone; the `*Iter` traversal
methods are lazy and no longer sorted; `newerThan`/`olderThan` compare in the direction their
names say; and `copyTo` requires its `overwrite` argument and returns `Option[Path]`, with
`File.copyTo(dest)` removed. Bigger than any of those: the date type moved off `java.time`
(below).

Of those, `copyTo` is the only one that fails to *compile* rather than changing behaviour
quietly, which is why it was done that way.

`posixAbs`/`posixRel` are **deprecated as of** this release -- `"0.16.0"` is the `since`
argument of `@deprecated`, not a removal target. They still work; removal is a later
release.


**FIXED — divergences caught by the new cross-language pair probe**

- `jsrc/pairProbe.sc` + `rust/examples/pair_probe.rs`: both halves build the same tree with the
  same fixed mtimes, run the same ~50 operations, and print diffable `op/key/value` lines. This is
  the "matched pair of scripts" idea -- fixtures pin one method at a time, so a method without a
  fixture was pinned by nothing, and that is exactly where all three catches came from.
- **RUST BUG -- `canRead` and `canExecute` answered `false` for every directory.** Java's checks
  are ACL checks, true for a readable, traversable directory; the port asked `File::open` (fails
  on a Windows directory) and `isFile()`. Now: directories answer via `read_dir`, and `canExecute`
  on Windows answers `exists()` -- an approximation Java's ACL check can beat only for a file
  whose ACL explicitly denies execute.
- **BREAKING (Scala) -- `mkdirs` over a name occupied by a file returns `false` instead of
  throwing.** `Files.createDirectories` threw `FileAlreadyExistsException` there, which made the
  `Boolean` decorative: success returned `true` and every failure threw, so `false` was
  unreachable. The Rust port already had the re-check semantics; its doc claimed the Scala did
  too, and the probe showed the claim false. The Scala now matches its own documentation.
- **Documented, not changed:** on a refused overwrite `copyTo` throws in Scala and returns `None`
  in Rust -- the port's error shape is values, not panics. The probe pins this as its one designed
  difference.
- The probe also **confirmed** parity where nothing had: `lastModifiedYMD`/`weekDay`/
  `weekDayName`/`epoch2DateTime` render identically, `renameViaCopy` status codes, `renameToOpt`
  collision handling, `realPath` on a missing child, `length`/`isEmpty` on missing files, and
  `write`/`writeLines` roundtrips -- 53 of 54 lines byte-identical.

**RUST — the audit table is closed: 87 of 92, and nothing silent remains**

- Five alias spellings added so a line ported from Scala compiles untranslated: `parentPath` and
  `parentFile` (≡ `getParentPath`; Rust has one path type), `local` (≡ `localpath`), `abspath`
  (`toAbsolutePath.normalize`, distinct from `abs` which consults the filesystem and returns a
  string), and `pathsTreeIter` (≡ `walkIter`). Each is a one-line forward under the
  CamelCase-means-parity rule.
- **The last silent divergence is now loud.** `Charset::by_name` returned UTF-8 for *any*
  unrecognised name, so `lines("Shift_JIS")` decoded properly in Scala and silently mis-read as
  UTF-8 in Rust. It now returns `ErrorKind::Unsupported` for a name it recognises as a real
  charset it has no tables for -- the Shift_JIS/EUC/GB families, Big5, UTF-32, ISO-8859-2..16,
  windows-125x, KOI8, EBCDIC, ISO-2022 -- and keeps the UTF-8 fallback only where the Scala has
  it too, for names `Charset.forName` would also reject. Free of breakage: `by_name` had no
  production callers, the Rust API taking `Charset` values directly.
- What remains unported is exactly: the four `Big` loaders (blocked on `Big`), `eachPath`
  (Scala-only by design; Rust's `DirIter` closes on drop), and `SmartParse`.

**BREAKING — `copyTo` returns `Option[Path]`; a refused overwrite is `None`, not an exception**

- `Some(dest)` when the copy happened, `None` when `overwrite = false` and `dest` exists -- which
  is the shape `renameToOpt` already had, and the answer the Rust port already gave, so the two
  languages now agree and the pair probe's outputs are **byte-identical** with no designed
  difference left.
- Nearly free in practice: every production call site in the corpus (20 across `apps`, `jsrc`,
  `vast`) discards the return value; the only binding uses were one test line and the probe.
- Detected by catching `FileAlreadyExistsException` rather than by an `exists` pre-check, so
  there is no TOCTOU window -- the same reasoning as the Rust side's `create_new`.
- Real I/O failures (permissions, disk) still throw in Scala. In Rust they fold into `None` with
  `try_copyTo` carrying the reason; that residual is inherent to a port that never panics, and it
  is now the *only* error-shape difference in the mutation family.

**BREAKING — `newerThan`/`olderThan` now compare in the direction their names say**

- `a.newerThan(b)` was true when **b** had the larger `lastModified` -- inverted, in both
  languages, with the Rust port faithfully reproducing the inversion. Flipped together, so
  `a.newerThan(b)` now asks whether *a* is newer, and `olderThan` likewise.
- The caller survey is why the flip was safe: the only extension callers in the corpus
  (`manifestClasspath`, twice: `val ojStale = ij.newerThan(oj)`) read as if the name were true,
  so they were silently backwards and are silently corrected. The other two candidates
  (`RouteTripReport`, `sclaunch.sc`) had each written their **own local `newerThan`** with the
  name's semantics rather than use the extension -- independent evidence for which direction is
  the expected one.
- Direction was never pinned in either language, which is how it survived: the only tests
  asserted `false` for non-files, which both directions satisfy. Now pinned by a mirrored pair --
  `PathExtsSuite` and `rust/tests/upath_times.rs` -- and by the `newerThan`/`olderThan` lines of
  the pair probe.

**RUST — the method named `parent` was implementing a different Scala method**

- `UPath::parent` was documented, in its own doc comment, as `PathExts.getParentNonNull`. So the
  API audit matched it to Scala's `parent` **by name** and reported the pair as ported while the
  semantics differed -- and `PathParityGen` records no parent method at all, so nothing tested it.
  A name match counted as parity for the whole life of the port.
- Renamed to `getParentNonNull`, with `parent` and `getParentPath` added beside it. Free of charge:
  there were **zero** `UPath::parent()` call sites -- the one apparent hit was
  `std::path::Path::parent` on a `&Path` cursor inside `realPath`.
- **The three then turn out to coincide in Rust, structurally.** Scala's three differ only for a
  *relative* `java.nio.file.Path`: `getParentNonNull` falls back to self, `parent` absolutises,
  `getParentPath` absolutises only as a fallback. A `UPath` is resolved against its context when
  constructed and is therefore always absolute, so no relative input exists to distinguish them.
  All three names are kept so a ported line reads unchanged.
- That was found by writing the test, not by reading: the first version asserted the Scala
  behaviour and failed, because `resolve(ctx, "loneName.txt")` yields
  `/home/tester/loneName.txt`. The doc comments written minutes earlier claimed a difference that
  cannot occur, and were corrected.
- Pinned by `rust/tests/upath_parent.rs`, which asserts the agreement rather than leaving it
  implied -- including the premise that a `UPath` is always absolute.

**BREAKING — the six `lastMod*` alias spellings are removed**

- Gone: `lastModSeconds`, `lastModMinutes`, `lastModHours`, `lastModDays`, `ageInDays` and the
  already-deprecated `lastModSecondsDbl`, from both the `Path` and `JFile` extensions. Each was a
  one-line forward to the `*Ago` method of the same name.
- Use `lastModSecondsAgo`, `lastModMinutesAgo`, `lastModHoursAgo`, `lastModDaysAgo`. `ageInDays`
  was `lastModDaysAgo` under a name kept for pallet compatibility.
- **Nothing is lost**: no alias had behaviour of its own, which is also why the Rust port never
  carried them. Removing them closes that group of parity exceptions by deleting the difference
  rather than by porting names -- 6 of the 18 unported methods were this one issue.
- `uni.time.TimeUtils.ageInDays(File)` is **untouched**: a standalone function taking an argument,
  not one of these extensions.
- 41 call sites rewritten across 13 files -- 4 in `/opt/ue/apps`, 6 `/opt/ue/jsrc` scripts, 2 in
  `/opt/ue/vast`, and this repo's own suite. Verified: `/opt/ue` compiles (200 `vast` + 303 `apps`
  sources) and all six edited scripts compile under scala-cli, which matters because they pin
  `uni_3:0.16.0` and therefore see the removal.

**BREAKING — the `*Iter` traversal methods are now genuinely lazy**

- `filesIter` was `files.iterator` and `pathsIter` was `paths.iterator`, over an already-listed,
  already-sorted `Seq`. `pathsTreeIter` was `Files.walk(p).iterator.asScala.toSeq.sortBy(...)`.
  All three were eager collections wearing an `Iterator` type, so the name promised something it
  did not deliver.
- That is not cosmetic. On a USB or network directory with thousands of entries an eager listing
  yields nothing until the last entry arrives, which is the difference between usable and not.
- Now backed by `Files.newDirectoryStream` and a lazy `Files.walk` stream, yielding as the
  filesystem returns entries. **They no longer sort**, because sorting requires the complete
  listing -- the two properties are mutually exclusive, and now each spelling provides one:
  `paths`/`files`/`pathsTree` for canonical order, `pathsIter`/`filesIter`/`pathsTreeIter` for
  latency.
- They return `Iterator[Path] & AutoCloseable` and hold an open handle. Exhausting closes it;
  abandoning does not. **New `eachPath(f)`** applies `f` to each entry and closes even if `f`
  throws -- the safe form, as `withLines` is beside `linesStream`.
- `paths` is now built on the same `newDirectoryStream` iterator instead of `File.listFiles`,
  which materialised a `File[]` only for every element to be converted straight back to a
  `Path`. One pass and one collection, then the sort. `paths`/`files` keep `Seq` and their
  canonical order -- a sorted listing has to be complete, so lazy access stays with the
  `Iter` spellings.
- `pathsTree` keeps its sorted contract, now as `Using.resource(pathsTreeIter)(_.toSeq.sortBy(...))`.
  Parent-before-descendant never needed the sort: `Files.walk` is depth-first pre-order, so the
  sort only ever ordered siblings, contrary to what the old comment claimed.
- **RUST:** new `pathsIter`/`filesIter` over `read_dir`, lazy and handle-releasing on drop.
  `walk()` changed from an alias of `pathsTree` (sorted `Vec`) to an alias of `walkIter` (lazy),
  matching Scala -- otherwise the same line means different things in the two languages.
- `test-data/walk-parity/` was unaffected: it pins `paths`, `subdirs`, `subfiles` and `pathsTree`,
  all of which keep their order. Laziness is pinned separately by `uni.LazyTraversalSuite` and
  `rust/tests/upath_lazy_walk.rs`.

**FIXED — a charset wider than one byte garbled every line it read**

- `lines("UTF-16BE")` returned the raw bytes with embedded NULs, not text. The byte-oriented
  reader splits on `0x0A` before decoding; in UTF-16 that byte is half of a code unit, so each
  line arrived with an odd byte count, the strict decoder rejected it, and the reader's Latin-1
  fallback handed back the bytes. `contentAsString(UTF_16BE)` was always correct, which is what
  made the reader look fine.
- Wide charsets now decode the whole file and split the text. Selected by a general predicate --
  does a newline encode to the single byte `0x0A` in this charset -- so UTF-32 and the EBCDIC
  family are covered by the same branch rather than by a list of names.
- **The cost is laziness**, unavoidably: a byte-level split cannot find a terminator that is not a
  whole byte. Single-byte and UTF-8 charsets still stream exactly as before.
- **RUST:** `Charset` gains `Utf16`, `Utf16Le` and `Utf16Be`, with `Utf16` taking its byte order
  from a BOM and consuming it, defaulting to big-endian -- matching the JVM. `encode` emits a
  big-endian BOM for `Utf16` and none for the explicit forms, again as Java does. No dependency:
  `String::from_utf16` and `str::encode_utf16` are in `std`.
- Pinned by `uni.CharsetSuite` and `rust/tests/upath_charset.rs`, mirrored case for case.
- **Still divergent, and now documented as such:** the Scala honours every charset the JVM knows,
  while Rust supports UTF-8, Latin-1 and UTF-16 and falls back to UTF-8 for the rest. The Rust doc
  previously claimed `uni` "does the same" -- true only for names the JVM also rejects. This is the
  one silent parity gap left in the port.
- Also corrected: `upath::mutate` still said `copyTo`'s `overwrite` was "not defaulted, unlike the
  Scala where it defaults to `true`". The Scala default was removed earlier in this same release,
  so the comment documented a divergence that no longer existed.

**BREAKING — line readers split on `\r?\n`; an interior carriage return is preserved**

- Earlier in this release cycle the rule was written as "a line-oriented reader removes **every**
  `\r`". That is not the same rule as the split on `\r?\n` the docs also described, and the two
  disagree on two cases: an **interior** CR (`a\rb`) and a **trailing** CR at end-of-file,
  neither of which has a `\n` to pair with.
- The pattern is the rule that shipped, because it is the self-consistent one. Splitting on
  `\r?\n` is what makes lines immune to the OS that wrote the file -- that was always the
  justification -- and it reaches only the CR a newline consumed. Discarding an interior CR is a
  separate act with a separate cost: it destroys data, silently, in the one API that is supposed to
  be the faithful one.
- **Scala behaviour change.** `PathExts.readNextLine` dropped every `0x0D` byte in a line; it now
  drops one only when the newline consumed it. So `a\rb\nc\r\nd\re\n` reads as
  `[a\rb, c, d\re]` where it previously read as `[ab, c, de]`. `lines`, `linesStream`,
  `withLines`, `eachLine` and `firstLine` are all affected, being one reader.
- Rust matches, and is now simpler for it: the UTF-8 path is plain `BufRead::lines`, whose rule this
  already is, and `strip_cr` is gone.
- Unchanged: a CRLF terminator still loses its CR, so CRLF, LF and a missing final newline still
  yield identical lines; `contentAsString` and `byteArray` still keep the bytes exactly.
- Pinned on both sides, case for case, by `uni.CarriageReturnSuite` and
  `rust/tests/upath_lines.rs`. **Scala had no test for the CR rule at all** before this -- the full
  2546-test suite passed both before and after a change to what every line reader returns, which is
  the sharpest example yet of a suite that looks like coverage.

**RUST — `Path.delim` ported**

- `UPath::delim` reports the sniffed CSV delimiter, or **empty** when nothing was found. It is
  not the same question as the delimiter the parser uses: `csv_delimiter` must name a byte to
  parse with, so it falls back to a comma, while `delim` reports whether a delimiter was found
  at all -- and substituting a comma there would turn "no" into a confident wrong answer.
- Samples 50 rows, matching `Delimiter.detect(p, 50)` rather than `CsvConfig::sample_rows`, and
  returns `String` rather than `Option<char>` because the Scala callers test for emptiness.
- Pinned against the Scala's own answers for seven inputs, including the three that surprise:
  a single-column file, an empty file and a prose file all yield empty rather than a comma.

**RUST BUG — `readCsv` counted the header row as data**

- `upath::matcsv`'s `try_read_csv` took every row as data (its own doc said so), while Scala's
  `readCsv` **is** `loadMatD`, which **is** `loadSmart(p, map).mat` -- and `loadSmart` excludes a
  detected header row. So a CSV with headers came back one row taller than the Scala's, topped
  with a row of `missing` cells.
- Fixed to go through the smart path. Nothing is lost for a headerless file: `loadSmart` drops
  row 0 only when it looks like a header.
- **`csv-parity` did not catch it.** The fixture exercises `csvRows` and `loadSmart` directly and
  so never compared Scala's `readCsv` against Rust's. That is the second divergence this release
  found hiding behind a fixture that looked like coverage -- the first was the carriage-return
  policy, where the `lone-cr` case tested CR as a line *separator* and never inside a line. Both
  surfaced only from writing a test that used the method the way a caller would.

**RUST — the `Double`/`Float` matrix loaders and `csvRowsAsync` ported**

- `loadMatD`, `loadMatF`, `readCsvF` and `loadSmartD` as named wrappers over the generic
  `readCsv::<T>`/`read_csv_smart::<T>`. Thin, but they are why a line ported from Scala needs no
  edit -- the same reasoning as the CamelCase naming and `asFile`.
- `csvRowsAsync` is a real prefetching reader, not an alias: a thread plus a bounded `mpsc`
  channel, no dependency. Bounded so a slow consumer applies backpressure rather than letting the
  reader pull the file into memory; abandoning the iterator closes the channel and ends the
  thread. Tested to yield exactly what `csvRowsStream` yields, in the same order -- including the
  final row, whose loss when the channel closes is the classic failure -- and to release the file
  when abandoned.
- The four `Big`-typed loaders (`loadMatBig`, `loadMatB`, `loadSmartBig`, `readCsvB`) remain
  unported, blocked on `Big`: an opaque `BigDecimal` with `MathContext.DECIMAL128` plus a NaN
  sentinel recognised by equality. `std` has no decimal type, so it would be the port's first real
  dependency.
- Worth recording, since it nearly produced a wrong port: **`MatB` is `Mat[Big]`, not
  `Mat[Boolean]`** (`MatFacades.scala:23`).

**RUST — the carriage-return policy is now enforced, and reads are ported**

- uni's line-oriented readers were taken to remove **every** `\r` from a line, and the Rust port
  was made to match. **That rule was superseded within this same release** -- see "line readers
  split on `\r?\n`" above, which is the rule that shipped. What stands from this entry is that
  Rust's line handling was brought under the Scala's control rather than left to
  `BufRead::lines` by default.
- The Rust port **did not** do this. `split_lines` used `strip_suffix('\r')` and the UTF-8 path
  used `BufRead::lines`, which strips only the terminator. So `a\rb\nc\r\nd\re\n` gave
  `[a\rb, c, d\re]` in Rust against `[ab, c, de]` in Scala. Fixed; both now agree.
- `csv-parity` did not catch it. It has a `lone-cr` case, but that exercises CR as a line
  *separator* -- never inside a line. The fixture looked like coverage and was not. Now pinned by
  `rust/tests/upath_lines.rs`, which also asserts that CRLF, LF and a missing final newline all
  yield identical lines, and that `contentAsString`/`byteArray` keep the bytes intact -- they are
  the escape hatch, and deliberately the only way to see a `\r` through this API.
- `linesStream` and `withLines` ported. Reads with `read_until` on bytes rather than
  `BufRead::lines`, so a non-UTF-8 charset is decoded by `Charset` instead of rejected.
- One place the port is stronger, and it is documented rather than claimed as parity: Scala's
  `linesStream` returns an `Iterator & AutoCloseable` that leaks the handle unless closed or
  exhausted -- which is *why* `withLines` exists beside it. `LineStream` owns its reader, so the
  handle closes on drop whether the caller exhausts it or abandons it. There is a test for the
  abandoned case, observable on Windows where an open handle blocks deletion.
- `asFile` is the **identity** in Rust rather than absent. Scala converts `Path` to
  `java.io.File`; Rust has one path type, so there is nothing to convert to -- but Scala's `File`
  extensions mirror the `Path` ones, so `p.asFile.lastModifiedYMD` ports unchanged. Earlier notes
  called this unportable, which confused the return type with the method.
- `withWriter` **honoured its charset**, having previously accepted and discarded it (`let _ =
  charset`), so a caller asking for Latin-1 silently got UTF-8. Added `Charset::encode` and a
  `TextWriter` that takes text, mirroring the `PrintWriter` Scala hands its body -- deliberately
  not an `io::Write`, which takes bytes and would let a caller bypass the encoding again.
- `isSameFile` **folds case on Windows and macOS**, matching Scala's `samePathString`. It was
  case-sensitive everywhere, so `C:/x/File.txt` and `C:/x/file.txt` -- one file on Windows --
  compared unequal when neither existed and `canonicalize` could not help.

**BEHAVIOUR — directory listings have a specified order, in both languages**

- `paths`, `files`, `subdirs`, `subfiles`, `pathsTree` and `walk` now sort: case-insensitive
  first, case-sensitive as a tiebreak. `listFiles`, `Files.walk` and `read_dir` all promise no
  sibling order, so the same script listed a directory differently on Linux and Windows and no
  fixture could pin it.
- Deliberately **not** `Seq[Path].sorted`, whose `Path.compareTo` is case-insensitive on Windows
  and case-sensitive on Linux. The Rust port could have matched that per-platform -- its
  `is_windows` is data, and `isSameFile` folds case exactly that way -- so this is a choice: one
  platform-independent order means a script lists a directory identically everywhere, and one
  parity fixture instead of the per-platform pair `test-data/path-parity/` needs.
- `Locale.ROOT` on the Scala side, since `toLowerCase()` without a locale maps `I` to a dotless
  `i` under a Turkish locale, which would make listing order depend on where the machine thinks
  it is.
- `walkIter` stays unsorted: sorting needs the whole listing, which is what its laziness avoids.

**API — `copyTo` requires `overwrite`; the rename family does not**

- `Path.copyTo(dest, overwrite, copyAttributes = false)` — `overwrite` has **no default**. It
  used to default to `true`, so `copyTo(dest)` clobbered silently while `renameTo(dest)` —
  defaulting to `false` — did not. The asymmetry ran in the dangerous direction and no reader
  could guess it.
- Flipping the default was considered and rejected: it changes behaviour at every call site with
  **nothing to catch it at build time**. Requiring the parameter makes the compiler name each
  one. `File.copyTo(dest)` is gone for the same reason — that overload *was* the old default.
- Measured across `jsrc`, `apps` and `vast` (1745 sources): **19 call sites**, 1 already
  explicit, 18 needing a decision. Of those, **6 wanted `false`** — and in every one the
  surrounding code already guarded against clobbering, so the old default had been working
  against the author's intent. `bankfileNorm` throws `"avoiding clobber"` three lines above its
  copy; `fnameDate` builds its destination with `nextUniquePath`; `fyoSourceCopy` is only reached
  in the branch where the backup is absent.
- The 13 `true` sites preserve the previous behaviour exactly, so they cannot introduce data
  loss. The 6 `false` sites are the only behaviour change, and the failure mode if a reading was
  wrong is a thrown `FileAlreadyExistsException` — loud, never silent.
- `renameTo`, `renameToOpt` and `renameViaCopy` keep `overwrite = false`: already the safe
  answer, meant by every existing call site, and requiring it would have been ~24 edits for no
  behavioural change. `copyAttributes` keeps its default on the same principle — a flag that can
  destroy data must be stated, one that cannot may default.

**RUST — metadata, traversal and mutation ported**

- `upath::meta` — `exists`, `isDirectory`, `length`, `isEmpty`, `nonEmpty`, `canRead`,
  `canExecute`, `isSymbolicLink`, `isSameFile`, `realPath`. This module *does* use `cfg(unix)`,
  for a permission bit, and that is not a departure from the "everything is data" rule in
  `upath`: that rule is about the path *algorithm*, where `cfg` would make the Windows rules
  untestable off Windows. Here the question is what the host filesystem provides, and the answer
  genuinely differs per platform. **`cfg` for what the OS provides, data for what the path rules
  are.**
- `upath::walk` — `paths`, `files`, `subdirs`, `subfiles`, `pathsTree`, `walk`, and `walkIter`
  (a lazy stack-based `TreeWalk`, so a deep tree cannot overflow and a caller can stop early).
  Fixture-backed via `test-data/walk-parity/`, which **sorts both sides**: neither language
  promises sibling order, so it pins what is traversed and not the order. Pre-order — a directory
  before its contents — is guaranteed and is asserted by unit tests on each side instead.
- `upath::mutate` — `copyTo`, `renameTo`, `renameToOpt`, `renameViaCopy`, `delete`, `mkdirs`,
  `withWriter`, each with a `try_*` form that keeps the outcomes Scala distinguishes. `delete` is
  the example: Scala returns `true` deleted, `false` absent, and *throws* on a real failure;
  `try_delete() -> io::Result<bool>` keeps all three, and `delete() -> bool` collapses them for
  callers who do not care.
- No-clobber copy is **atomic**: `create_new` (O_EXCL / CREATE_NEW) rather than checking
  `exists()` and then calling `fs::copy`, which always overwrites and would leave a TOCTOU window.
  `fs::rename` has no non-replacing variant, so `renameTo` checks first — which matches Java's
  `Files.move` on Unix, race included.
- Semantics pinned that are easy to get wrong: `pathsTree` includes the starting path; the walk
  does not follow symlinks (which also stops a cyclic link hanging it); `subfiles` filters on
  *regular* files, so a device or socket is excluded; a file lists empty but walks to itself.
- `files` and `paths` coincide in Rust — in Scala they differ only in `java.io.File` versus
  `Path`, and there is no second path type here. `files` is kept as an alias.

**BEHAVIOUR — file timestamps are now UTC, and timezone is gone from that API**

- `lastModifiedTime` and `lastModifiedYMD` render in **UTC**. They used
  `ZoneId.systemDefault()`, which no port outside the JVM can reproduce: Rust's `std` has no
  timezone database and no way to read the local offset. The alternative was an offset
  parameter on every call — a value almost no caller has an opinion about, since a file
  timestamp is used either for *comparison*, where any consistent offset cancels, or for
  *display*, where machine-independence is an advantage.
- **This changes printed output.** Measured across the 166-script corpus: `lastModifiedYMD` and
  `weekDay` had **zero** callers, and of eight `lastModifiedTime` callers three are pure
  comparisons (`parquetTableStatus` `maxByOption`, `fyoSourceCopy`'s `sd`/`dd`) where the offset
  cancels. So **three** sites print a value shifted by the local offset: `fidgrab.sc` (an
  `_HH-mm` filename label) and `fidPdf2txt.sc` twice.
- It also removed an inconsistency already present: `epoch2DateTime` has always defaulted to
  `UTC`, so `p.epoch2DateTime(p.lastModified)` and `p.lastModifiedTime` disagreed by the local
  offset for the same file, in the same extension block. They now agree, and a parity test
  asserts it.
- Local time needs no API — it is one explicit call:
  `p.epoch2DateTime(p.lastModified, ZoneId.systemDefault())`.
- `lastModifiedYMD` also stopped constructing a `SimpleDateFormat` per call. That class is not
  thread-safe, and it was the reason this rendered local time in the first place.

**API — weekday off `java.time`, and the abbreviation it was missing**

- `Path.weekDay` returns `Int` (1 = Monday .. 7 = Sunday) rather than `java.time.DayOfWeek`.
  Same numbering, so nothing is lost, and it becomes portable. Zero corpus callers.
- New: `UniDateTime.dayOfWeekName` (`"Mon"`..`"Sun"`), `dayOfWeekFull` (`"Monday"`..), and
  `Path.weekDayName`. This is the form client code was hand-rolling as
  `getDisplayName(TextStyle.SHORT, Locale.ENGLISH)` — several scripts wrap exactly that in a
  local `dow` helper. The explicit `Locale` there is load-bearing: without it the same script
  yields `lun.` on a French machine. These are English by construction.
- Two vacuous assertions surfaced when the type changed: `assert(p.weekDay != null)` was always
  true for a `DayOfWeek`. Now range checks.
- Fixed a comment in `DateFormat` claiming `dayNames` was Sunday-first; the data has always
  been Monday-first, which is what the `dayOfWeek - 1` indexing requires.

**RUST — file timestamps ported to `upath::times`**

- `lastModified`, `lastModMillisAgo`, the four `lastMod*Ago` values, `lastModifiedTime`,
  `lastModifiedYMD`, `weekDay`, `weekDayName`, `newerThan`, `olderThan`, plus `epoch2DateTime`
  and `agoFromMillis`.
- The `Ago` arithmetic is split from the clock read, which is what makes it testable. It also
  had to be transcribed *exactly*: the Scala rounds at every step on an already-rounded value,
  so `daysAgo` is not `round(millis / 86_400_000, 6)`. Measured over 200k realistic file ages,
  the chained and direct forms disagree for **2.0% of inputs**, always by one unit in the sixth
  decimal — and since these read the clock, no fixture could catch a regression.
- Fixed a sign bug found while documenting: `SystemTime::duration_since` returns `Err` when the
  receiver is *earlier*, so the obvious `.ok().unwrap_or(0)` reported a pre-1970 file as the
  epoch. Java returns a negative value there. Now handled by `millisOf`, which measures the
  other way on the error branch.
- The `lastMod*Ago` aliases are deliberately absent: they add names, not capability.

**RUST — public method names now match the Scala spelling**

- `baseName`, `plusDays`, `lastModifiedTime` rather than snake_case, under a **module-scoped**
  `#![allow(non_snake_case)]` — no crate-root allow, so `t3prf` and `numpy_rng` keep the lint.
  The precedent is `windows-rs`, which spells Win32 functions `SetEvent` rather than
  `set_event`: the documented API is the contract, and here that contract is the Scala one.
- 63 methods CamelCase, 326 snake_case. Internal helpers and Rust trait contracts
  (`Display::fmt`, `cmp`) stay snake_case, so **the case says whether a Scala counterpart
  exists**. Where Rust cannot overload or default an argument, one Scala member becomes several
  with a suffix — `ofFull`, `ofYmd`, `isValidFields` — and those keep CamelCase, being API
  rather than internals.
- `is_file` was the only name colliding with a `std` method, so it was renamed by hand:
  `UPath::isFile` now sits directly above a preserved `self.as_std_path().is_file()`.

**RUST — `uni.time` ported to `rust/src/utime/`**

- `utime::datetime::UniDateTime` and `utime::format` mirror the Scala type and formatter:
  same fields, same validation, same day-0 sentinels and `<BadDate>` rendering, same
  epoch-day arithmetic, **no date-library dependency**. That last part is what decoupling the
  Scala type from `java.time` bought.
- `SmartParse` is not ported; only the date type and its formatter are.
- The Scala arithmetic delegates to `java.time`, which the port cannot, so the calendar math
  is written out — Hinnant's civil-calendar algorithms, month-end clamping, time-shift carry.
  `test-data/date-parity/` pins the two languages across 4020 cases, making `java.time` the
  transitive oracle. It caught a real defect on the first run: the port returned
  **2000-03-01 for 2000-02-29**, from using 146097 rather than 146096 as the day-of-era
  divisor — visible only on the last day of a 400-year era.
- Checked from both sides (`uni.time.DateParitySuite`, `rust/tests/date_parity.rs`) so
  regenerating the fixture cannot quietly rewrite the expectations.

**API — the date type is now `uni.time.UniDateTime`, not `java.time.LocalDateTime`**

- `type DateTime` and `parseDate`/`parseDateSmart` now yield `UniDateTime`: a plain case
  class of integer fields. The parser therefore carries no `java.time` coupling and ports
  to Rust as a struct with **no date-library dependency** -- the alternative was `jiff`
  plus a Java-pattern-to-strftime translation layer.
- Source compatibility comes from implicit conversions **both ways**, so a parsed date
  still flows into a `java.time` API and back. All 166 client scripts in the reference
  corpus compile.
- **Everything that produces or shifts a date moved with it**, and had to: `now`,
  `yesterday`, `nowZoned`, `quikDate`, `quikDateTime`, `NullDate`, `whenModified`,
  `epoch2DateTime`, `Path`/`File`.`lastModifiedTime`, `endOfMonth`, the
  `elapsed*`/`*Between` family, `getDuration`, `TimeArith`'s `+`/`-`, and a full
  `plusX`/`minusX`/`withX`/`atStartOfDay`/`lastDayOfMonth` surface on `UniDateTime`.
  Leaving any of them on `LocalDateTime` makes a script that mixes the two infer the union
  `LocalDateTime | UniDateTime`, which satisfies neither position and which no conversion
  repairs. That union was the single largest source of breakage while converting.
- The overloaded `secondsBetween` and `getDuration` gained explicit `UniDateTime`
  alternatives, because **implicit conversions are not applied during overload
  resolution** -- Scala reports "none of the overloaded alternatives match" without ever
  retrying with a conversion in hand.
- `uni.data.CVD` is now `UniDateTime | Big | Option[Int] | String | Int`. A union cannot be
  entered through a conversion, so a `LocalDateTime` member would have been unreachable
  from uni's own parser and `toStr` would have thrown `MatchError` on parsed dates.
- What does **not** survive: a `java.time.LocalDateTime` written in a generic position --
  assigning an existing `Seq[UniDateTime]` to a `Seq[LocalDateTime]`, an explicit
  `Ordering[LocalDateTime]`, or `case d: LocalDateTime`. Each is a compile error, never a
  silent difference. Note `val xs: Seq[LocalDateTime] = lines.map(parseDate)` *does* still
  compile, since the expected element type propagates into `map`.
- Caution: `==` across the two types compiles, warns nothing, and is always `false`. That
  is why the sentinels and the parser's return type had to move in one change rather than
  one at a time.

**BEHAVIOUR — `BadDate`/`EmptyDate` are dates that cannot exist, and print as markers**

- Both now carry **day 0**, which occurs in no month. `of` validates, so no parsed date can
  equal a sentinel: a file containing a real `1900-01-02` yields an ordinary date instead of
  a false parse failure. It previously *was* that value, and 1900-01-02 is a common
  placeholder in financial data -- so the collision was not hypothetical.
- `BadDate.toString` is now `<BadDate>` (`<EmptyDate>` likewise) rather than
  `1900-01-02T03:04:05`, which read as real data in every log line and printed column.
- The pattern formatters still yield digits -- `BadDate.fmt("yyyyMMdd")` is `19000100` --
  because they feed filenames, CSV keys and SQL binds, where a marker would produce
  valid-looking output that quietly matches nothing. Guard those paths with `isValid`.
- `toLocalDateTime` projects each sentinel onto the moment it historically was, so every
  arithmetic helper and every conversion behaves exactly as before.
- Sentinels now absorb arithmetic and `withOffsetMinutes`, as `Double.NaN` does;
  `toEpochDay` yields `Long.MinValue`.

**BUG — `BigNaN` was destroyed by three operations (`Big.scala`)**

`BigNaN` is meant to behave as `Double.NaN` and never participate in arithmetic. Three
operations broke that, and because the sentinel is recognised by *equality*, the loss was
silent:

| operation | before | now |
|---|---|---|
| `BigNaN.setScale(2, HALF_UP)` | `0.00`, `isNaN` **false** | propagates |
| `BigNaN ~^ 2` (integer exponent) | `1.52…E-16`, `isNaN` **false** | propagates |
| `BigNaN ~^ 0.5` (fractional) | threw `NumberFormatException` | propagates |

`setScale` is the serious one: the sentinel is a tiny magnitude, so rounding it to any
ordinary scale produced an unremarkable zero. **A NaN reaching a report formatter emerged
as `0.00`.** All other arithmetic, comparison and conversion operations were already
correct.

The sentinel literal also went from 19 to 28 significant digits, keeping deliberate slack
below `MathContext.DECIMAL128`'s 34 -- an operation that rounded it would corrupt it, which
is exactly the failure just fixed. A test asserts the headroom.

**API — `where` fails loudly, and `whereInPath` is exported (`ProcUtils.scala`)**

- `Proc.where` returned a **multi-line** string on Windows: `where.exe` prints every match
  and `ProcResult.toOption` joins stdout with newlines, so a tool present in both msys and
  System32 came back as two paths. Unusable as a command, and misleading to a caller
  screening the result with `.contains("Windows")`. It now takes the first (PATH-first)
  match.
- Absence now raises `sys.error(s"prog [$prog] not in PATH")` instead of returning `prog`
  unchanged. The old fallback deferred the failure to the launch, where nothing pointed at
  the PATH any more. Use `whereInPath` when absence is a case to handle.
- `whereInPath` is now in the package export, so `import uni.*` reaches it unqualified.
- `unameExe` deliberately uses `whereInPath`, since a Windows host without msys
  legitimately has no `uname` and `where` now throws.
- Worth knowing on Windows: neither function predicts what a *bare* name would launch.
  `ProcessBuilder` goes through `CreateProcess`, which searches the system directory
  **before** the PATH, so `C:\Windows\System32\find.exe` wins over an msys `find.exe`
  however the PATH is ordered. Passing the absolute path is the only way to reach the tool
  the PATH nominates.

**DOCS**

- `docs/DateTimeParser.md` rewritten as the client reference for `uni.time`: the date type
  and its companion, sentinels, formatting, arithmetic, strict `DateOrder`, and the
  pitfalls above. It had documented `ChronoParse` as a fallback parser, which no longer
  exists, and pinned `0.15.2`. Its "BigData Prep" example also did not compile --
  `import uni.data.*` and `import uni.data.BigUtils.*` together make `getMostSpecificType`
  ambiguous.
- `UniScriptingTools.md` and `PathIOReference.md` corrected for the new date type, and
  `PathIOReference.md` gained a table of which subsystems have Rust counterparts.
- `MatDCheatSheet.md` verified rather than read: every MatD expression in its API tables was
  extracted and compiled (148/148 type-check, 6/6 code blocks compile). That found three
  errors -- its "From rows (Seq)" row invited `Seq[Seq[Double]]`, which `toMat`/`fromRows`
  do **not** accept (only `Seq[Array[T]]`); `show` returns the rendering rather than printing
  it, so the documented `m.show` on its own line outputs nothing; and the documented
  `println(m)` cannot compile in a script that shadows `println` to a `String`-only signature,
  as the CR-free output convention requires. All three now documented, with `println(m.show)`
  and `println(s"$m")` as the working forms.
- `PathIOReference.md` and `DateTimeParser.md` updated for the UTC timestamp decision, the
  weekday changes, `upath::times`, and the Rust CamelCase naming convention.

**BUG — ragged CSV rows were silently discarded, sometimes almost all of them (`FileOps.scala`)**

- `loadSmart` took the column count from the *first* row and dropped every row that
  did not match it. A single malformed line lost that line; a malformed **first**
  line lost nearly the whole file:

  | input | matrix before | matrix now |
  |---|---|---|
  | short row in the middle | 2x2 | 3x2 |
  | long row in the middle | 2x2 | 3x3 |
  | **first** row short | **1x1** | 4x2 |
  | **first** row long | 1x3 | 3x3 |

- Rows are now padded to the widest rather than filtered. Widest rather than
  first-row width on purpose: keying off the first row means truncating anything
  longer, which is the same data loss wearing a different hat. A padded cell is
  `""`, which already reads back as NaN — no new concept
- `csvRows` and `loadSmart` now share `FastCsv.rectangular`, because the two must
  not disagree about which rows a file contains. They previously did: `csvRows`
  returned every row while `loadSmart` dropped the mismatched ones
- The streaming readers cannot pad to a true maximum — the width is unknown until
  the last row — so each buffers a 100-row window, takes the widest row in it, and
  streams the rest padded to that. A row wider than the window is emitted at its own
  width rather than truncated
- Reusing the widths `Delimiter.detect` already tallies looked free and does not
  work: `detect` stops as soon as a candidate dominates, which for a row over its
  100-character check interval happens partway through the **first** row. Every row
  is then recorded as truncated, `rowCounts` comes back empty, and nothing is padded
  — on most real files. The width is now measured by the reader, from rows the real
  parser produced, so it is exact for the window rather than an undercount
- `eachRow` — which backs the callback overload of `PathExts.csvRows` — had neither
  the blank-row filter nor padding, so `p.csvRows` and `p.csvRows(fn)` disagreed
  about a file's contents. Now aligned, and pinned by a shared fixture

**BUG — `FastCsv.rowsAsync` hung when `hasNext` was called twice after exhaustion**

- The producer thread puts exactly one end-of-stream marker. `hasNext` took from the
  queue on every call, so a second call after the marker blocked forever on a
  producer that had already exited. Any caller checking twice hung outright
- `hasNext` is now idempotent

**BUG - `SmartParse` lost the time whenever the year came before it**

- `parseMonthDayYear` / `parseDayMonthYear` took the time to be the numbers *between*
  the day and the year. That holds for `Aug 18 22:29:47 2018` and little else:

  | input | before | now |
  |---|---|---|
  | `01 Jan 2001 12:34:56 -0700` | `2001-01-01T00:00` | `12:34:56` |
  | `Sun, 12 May 2024 14:30:00 GMT` | `2024-05-12T00:00` | `14:30` |
  | `May 12, 2024 2:30 PM` | `2024-05-12T12:00` | `14:30` |

- The first two are RFC 2822, i.e. every email header. The third read `2:30 PM` as
  noon because the PM adjustment was applied to an hour of zero. The time is now
  whatever numbers remain once day and year are accounted for, with positional
  validation so a trailing `-0700` cannot fill the seconds slot.
- Measured against the project's 132-date corpus, disagreements with `ChronoParse`
  fell from 24 to 11, and every remaining one is `ChronoParse` being wrong.

**BUG - `parseDateSmart` could throw instead of returning `BadDate`**

- `12:34 -0700` put 700 in the seconds field and `LocalDateTime.of` threw
  `DateTimeException`. The sentinel is the whole reason this is usable for a CSV
  column without wrapping every cell in a `Try`, so the boundary now catches.

**NEW - enforceable day/month order (`DateOrder`, `inferDateOrder`)**

- `monthFirst` only ever decided the *ambiguous* case; an unambiguous date overrode
  it. So a consistent European column parsed as a mixture under the default -- rows
  with a day of 12 or less read month-first, the rest day-first -- and nothing looked
  wrong. The file was internally consistent; the parse was not.
- `DateOrder.Auto` is that behaviour and remains the default, so nothing has to be
  declared. `MonthFirst` / `DayFirst` enforce, turning a contradicting date into
  `BadDate` instead of silently reinterpreting it.
- `withDateOrder(order) { ... }` is dynamically scoped and thread-local, like
  `withTimeConfig`. It derives from the enclosing config, so `Auto` keeps an outer
  `monthFirst` rather than resetting it.
- `inferDateOrder(column)` reads the convention off the unambiguous rows: `Some(order)`
  when one is proved, `Some(Auto)` when nothing is decisive, and **`None`** when rows
  prove both -- a genuinely mixed column, reported rather than guessed at.

**CHANGED - `ChronoParse` deprecated; two of its formats folded into `SmartParse`**

- `parseDate` is now `parseDateSmart` alone. Across the 132-date corpus `ChronoParse`
  rescued **zero** inputs that `SmartParse` failed, and where both succeeded and
  disagreed it is the wrong one: `12:00:00 AM` as noon, `2nd Jan 2020` as February 1st,
  `19 Jun.2023` as 2026.
- The two formats it did handle alone are folded in, as `preNormalize` rewrites so the
  existing ISO/YMD path does the parsing: bare `yyyyMMdd` (with `HHmm`/`HHmmss`, since
  the compact time is part of the same format) and a leading time
  (`13:45 2020-01-02`). Guarded: `99999999` and `20201345` stay `BadDate`.
- `parseDateChrono` is `@deprecated`, not removed -- `TimeUtils` still uses its
  `monthAbbrev2Number`, and deleting 900 lines is a separate step.

**BUG - `SmartParse` silently dropped part of the time when a number collided with the year**

- `parseMonthDayYear` and `parseDayMonthYear` found the year's *value*, then searched
  the number list for its *position* by value, allowing a two-digit expansion. That
  matched whichever number expanded to the same year, and the slice between day and
  year then cut the time at the wrong point:

  | input | before | now |
  |---|---|---|
  | `Aug 18 22:29:47 2018` | `2018-08-18T00:00` | `2018-08-18T22:29:47` |
  | `Aug 1 22:29:47 2022` | `2022-08-01T00:00` | `2022-08-01T22:29:47` |
  | `Aug 1 22:29:47 2029` | `2029-08-01T22:00` | `2029-08-01T22:29:47` |

- Three separate collisions, one per field: the day `18` expands to 2018, the hour
  `22` to 2022, the minute `29` to 2029. Nothing threw and nothing returned `BadDate`
  -- the results were plausible timestamps -- and it struck roughly one row in a
  hundred. For the CSV date-column use case that is the worst available failure mode.
- The year and its index are now determined together, preferring an unambiguous
  4-digit token and falling back to the 2-digit reading at its fixed position. A
  regression suite sweeps 28 days x 46 years through both parsers: 0 wrong, from 56.
- `Aug 18 22:29:47 2018` is the standard `ls -l` / syslog timestamp, so this was not
  an exotic input.

**BUG - `noTrailingSlash` stripped only one trailing separator**

- It used `stripSuffix("/")`, so `/usr/bin//` came back as `/usr/bin/` -- still
  trailing, from a function whose name says otherwise. It now strips all of them.
  `/` keeps its slash, and a bare `C:` must not *gain* one: `C:/` is the drive root
  while `C:` is drive-relative, and turning one into the other changes which
  directory is meant.
- Affects `joinPosix` and `toPosixAbs` as well as `posx`, all of which route through
  it. Mirrored in the Rust port.
- `posx` itself is unchanged in meaning: it converts `\` to `/` and nothing more.
  Interior runs of separators are deliberately *not* collapsed -- `"a//b".posx` stays
  `a//b`. Collapsing duplicates is what `Paths.get` does, so code wanting that should
  go through a path rather than a string.

**BUG - `Path.relpath` never returned a relative path, and mixed two working directories**

- It was `standardizePath(relativePathToCwd(p))`. The first computed the relative
  form; the second immediately threw it away by calling `toAbsolutePath`:

  | call | before | now |
  |---|---|---|
  | `Paths.get(cwd + "/src/main").relpath` | `/Users/philwalk/workspace/uni/src/main` | `src/main` |
  | `Paths.get(cwd).relpath` | `/Users/philwalk/workspace/uni` | `.` |
  | `Paths.get("/tmp").relpath` | `/tmp` | `/tmp` |

- The worse half: the two halves disagreed about which working directory they meant.
  `relativePathToCwd` relativised against the *config's* `pwd` while
  `standardizePath` re-absolutised against the *JVM's* `user.dir`. Under an injected
  user those differ, so the answer named a different file -- `C:/munit/test/foo` came
  back as `/c/Users/philwalk/workspace/uni/foo`
- `relpath` is now the `Path`-facing form of `posixRel`, which is what that method's
  own deprecation note already promised ("Use `Path.relpath`"). The two returned
  different answers for the same input; `PathSpec` had already worked around it by
  calling `posixRel` directly

**BUG - the mount root mapped to the empty string, and a sibling matched it (`toPosixAbs`)**

- Found while fixing `relpath`, which surfaced the divergence. Two defects in one
  expression, `cygMixed.startsWithIgnoreCase(config.cygRoot)`:

  | input | before | now |
  |---|---|---|
  | `toPosixAbs("C:/msys64")` (the root) | `""` | `/` |
  | `Paths.get("/").posix` | `""` | `/` |
  | `toPosixAbs("C:/msys64extra")` | `extra` | `/c/msys64extra` |

- Dropping the whole prefix left nothing, so the root of the filesystem came back as
  the empty string. And the prefix test had no segment boundary, so a *sibling*
  directory whose name merely starts with the mount root was rewritten as though it
  were inside -- returning a relative string for an absolute path. Same defect
  `standardizePath` had; both now go through `Resolver.findPrefix`
- Fixed identically in the Rust port (`upath::resolve::windows_to_posix`), which had
  faithfully reproduced both. The parity fixture missed them because it had no
  mount-root input; `C:/msys64`, `C:/msys64/`, `C:/msys64/usr`, `C:/msys64extra` and
  `C:/msys64extra/x` are now among its cases

**Windows path rules are now testable on Linux and macOS**

- `isWin` came straight from `scala.util.Properties` (i.e. `os.name`), so the Windows
  rules could only be exercised on Windows. `PathsConfig` gained an injectable
  `isWindows`, defaulted from the real platform and settable through
  `withMountLines`. Nine rule-selection sites read it: the main gate in
  `resolvePathstr`, two in `parseMountLines`, plus `safeAbsolutePath`, `toPosixAbs`,
  the drive-letter guard, `PathExts.localpath`, `PathExts.dospath` and
  `StringExts.local`
- Windows-only tests dropped from ~117 to 13. `PosixFmtSuite`'s 66 mount-translation
  cases and all 17 of `SyntheticMountsSuite` now run everywhere
- `PathParitySuite` used to *fail* off Windows rather than skip: it required
  `scala-reference-$platform.txt` and only the Windows file was committed.
  `PathParityGen` now emits both blocks from one run on any host, and the suite
  checks both (9 tests -> 18). The Rust test already looped over both platforms
- What stays host-bound, and why: anything reaching `java.nio.file.Path` renders
  through the *host's* FileSystem, which no flag can override --
  `Paths.get("/munit/test").toString` is backslashed on Windows regardless, and
  `Paths.get("F:/")` is a root there but a relative name on Linux. So the fixture
  records `classify`/`win`/`posixabs` for both platforms and the extension-method
  rows only for the generating host. Environment probes (`mount.exe`, `cygpath.exe`,
  `File.listRoots`) still read the real platform, as they must
- Four tests were gated for no reason at all: `winAbsToPosixAbs` is pure string work
  with no `isWin` in it, and `UniRootCoverageSuite`'s `resolveWindowsPathstr` cases
  were reading the *ambient* MSYS mount table. Those now use an injected table, so
  they no longer depend on a particular developer's install

**BUG - drive-relative paths (`C:`, `C:foo`) resolved wrongly, and `C:foo` threw**

- `Resolver.resolvePathstr` calls `applyTildeAndDots` *before* `classify`, and
  `applyTildeAndDots` claimed any two-character drive string for itself. So the
  `DriveRel` branch that exists to handle these -- `resolveDriveRelPathstr`, the one
  place that knows the per-drive working directory -- never saw them:

  | input | before | now |
  |---|---|---|
  | `Paths.get("C:")` | `C:\...\uni\.` | `C:\...\uni` |
  | `Paths.get("C:foo")` | **throws** `InvalidPathException` | `C:\...\uni\foo` |
  | `Paths.get("C:foo/bar")` | `C:\...\uni\.\foo\bar` | `C:\...\uni\foo\bar` |

- `C:foo` contains no `/`, so it fell into the bare-filename branch and was appended
  to the user directory as `C:/Users/.../uni/C:foo` -- a colon buried mid-path, which
  `java.nio` rejects. `C:foo/bar` escaped that only by containing a slash
- The stray `.` came from `driveCwd` building its answer from the string `C:.`:
  `Paths.get("C:.").toAbsolutePath` keeps the dot, `Paths.get("C:").toAbsolutePath`
  does not. `toAbsolutePath` is what asks Windows for the drive's own cwd
- Expectations are asserted against `java.nio.file.Paths` rather than written by
  hand, so they cannot drift from real Windows behaviour
- The committed path-parity reference had recorded the broken values, including
  POSIX paths with backslashes in them (`posixabs "C:"` was `/c\munit\test`, and
  under a `cygdrive` mount it ignored the prefix entirely). Regenerated; the diff is
  36 lines, all of them the `C:` and `F:` cases
- Fixed identically in the Rust port (`upath::resolve::expand_bare`), which had
  faithfully reproduced the same defect. Both fixes are guarded so that off Windows
  `C:foo` stays an ordinary filename containing a colon

**BUG — `hash64` ignored bytes 32–63 of every 64-byte block (`Hash64.scala`)**

- `processChunk` advanced 64 bytes per chunk but mixed only offsets 0, 8, 16 and 24,
  so half of every block never reached the hash. Two 128-byte inputs differing in 64
  of their bytes hashed identically — for a duplicate-file finder, a false positive
  on entirely different files
- Fixed by mixing both 32-byte stripes. A test now flips every byte position in a
  128-byte file and requires the hash to change
- **`hash64` values recorded before this change do not match values produced after
  it.** Only inputs of 64 bytes or more are affected; shorter files never reached
  `processChunk` and are unchanged
- `md5`, `sha256` and `cksum` are unaffected
- Blank header labels become canonical `colN`, numbered by position so `col3` is
  the third column. Padding a short header row would otherwise produce columns that
  cannot be looked up by name — trading a lost row for an unreachable column. A
  file with no header row still reports no headers
- Six regression tests, including that `csvRows.length` equals `readCsv.rows` for
  every ragged shape

**BUG — `FastCsv.rowsAsync` discarded every row of a single-column file (`FastCsv.scala`)**

- The two readers filtered rows differently: `rowsPulled` dropped rows with no
  non-blank field, `rowsAsync` dropped rows with fewer than two fields. Both
  carried the same comment — "discard if empty or text-with-no-delimiter" — and
  neither implemented it; they had drifted into opposite halves of it
- Consequence: `rowsAsync` on a one-column CSV returned an empty iterator. The two
  also disagreed on blank rows (kept by async, dropped by pulled) and on ragged
  rows (dropped by async, kept by pulled)

  | input | `rowsPulled` | `rowsAsync` |
  |---|---|---|
  | `alpha
beta
gamma` | 3 rows | **none** |
  | `a,b` / ` , ` / `c,d` | 2 rows | 3 rows |
  | `a,b` / `solo` / `c,d` | 3 rows | 2 rows |

- Unified on `isContentRow` — at least one field with non-whitespace content —
  named once so the two cannot drift again. A blank row is dropped; a single-field
  row is kept, because one column is a legitimate CSV and dropping it silently is
  worse than keeping a delimiter-less line
- Two regression tests: one asserting the readers agree across all four shapes, one
  pinning the single-column case on `rowsAsync` specifically
- Found while scoping the Rust CSV port, which will now reproduce one rule rather
  than choosing between two

**NEW — Rust file I/O (`rust/src/upath/io.rs`)**

Tier 3, first half: the text read/write surface.

- Every operation exists twice. `uni`'s I/O is deliberately total — a missing file
  gives an empty result and nothing throws — which is good for scripting but would
  make failures permanently unreportable if that were the only form. So `lines()`
  returns `vec![]` and `try_lines()` returns the `io::Error`, with the total form a
  one-line wrapper over the fallible one
- Ported: `byteArray`, `contentAsString` (both arities), `lines`, `linesStream`,
  `firstLine`, `eachLine`, `write`, `writeLines`
- `Charset` covers UTF-8 and Latin-1, which is all `uni` actually uses —
  `contentAsString` tries UTF-8 then falls back to Latin-1. `Charset::by_name`
  resolves anything unrecognised to UTF-8, matching `Charset.forName` being caught
  and defaulted. `#[non_exhaustive]`, so a real encoding table can arrive later
  without breaking callers and without a dependency today
- `writeLines` keeps its trailing newline, and always emits LF — the Scala comment
  says it "ensures the file isn't missing a newline at EOF", so it is intent rather
  than an accident to tidy away. Both are pinned by tests
- `as_std_path()` is the bridge to `std::fs`. No wrappers for `exists`, `len`,
  `read_dir` and the rest: they are one-liners on the far side of that call
- 8 round-trip tests against a real temp directory, covering CRLF input, the
  trailing newline, invalid UTF-8 falling back to Latin-1 while strict UTF-8
  errors, and the total-vs-fallible split on a missing file

**NEW — Rust path-string conversions (`rust/src/upath/ext.rs`)**

Tier 2 of the `uni.Paths` port: the `Path` extension methods scripts actually call.

- `UPath` reproduces what `java.nio.file.Path` contributes, which is the reason
  this needed a type rather than a handful of string functions. Measured, not
  assumed: `Paths.get` collapses duplicate separators (`a//b` → `a/b`) and drops
  trailing ones (`a/b/` → `a/b`), but does **not** resolve dot segments — `a/./b`
  and `a/../b` survive intact, since only `normalize()` touches those
- Ported: `posx`, `local`/`localpath`, `dospath`, `posix`, `noDrive`, `last`,
  `baseName`, `ext`, `dotsuffix`, `extension`, `segments`, `reversePath`, `parent`,
  `stdpath`, `relpath`, `abs`/`abspath`, plus `normalize`, `toAbsolutePath`,
  `relativize` and `isAbsolute` as the supporting Path machinery
- `abs`, `stdpath` and `relpath` are covered by unit tests rather than the shared
  fixture, because each consults the machine: `abs` branches on `Files.exists`, and
  the other two reach `toAbsolutePath`, which resolves against the JVM's real
  `user.dir` — Java knows nothing about an injected config. Pinning them would have
  committed this checkout's own paths (it briefly did: 126 lines)
- `segments` yields Java's *name* elements only, so `C:/Users` has one and `C:/`
  has none; `last` on a root-only path is an error, where the Scala throws on a
  null `getFileName`
- `dospath` is deliberately not `localpath`: Java renders a root with its trailing
  separator (`\server\share\`), which `posx` strips from the UNC form

**Path fixture now pins the extension methods (`test-data/path-parity/`)**

- Previously only `classify`, `resolvePathstr` and `posixAbs` were pinned — the
  internals. Scripts call `.posx`/`.posix`/`.dospath`, which differ from those
  precisely because `Paths.get` normalises on the way in, so the two `posixAbs`
  could agree while the two `.posix` diverged
- 11 extension fields added across all 9 mount tables, plus inputs covering
  interior `.`, `..` and doubled separators. 4,127 cases, up from 638
- It immediately earned its keep: `Paths.get` special-cases `file://`
  (`Paths.scala:26-31`), routing URIs through `resolvePath(uri)` rather than
  rejecting them, so `Paths.get("file:///c/tmp").posx` is `C:/tmp` while
  `Resolver.resolvePathstr` on the same string throws. Nothing pinned that
  asymmetry before, and the Rust port did not have it
- Both harnesses had the same encode/decode bug — re-encoding an empty result as
  `!empty` when the fixture side had already decoded it to `""`. The generator
  still writes `!empty`, which keeps the committed file unambiguous
- The Scala suite evaluates these fields via `PathParityGen.extFields`, so the
  suite and the generator cannot disagree about what a field means

**Found — `relpath` never returns a relative path, and mixes two working directories**

Not fixed; recorded because it needs a decision rather than a patch.

- `relpath` is `standardizePath(relativePathToCwd(p))`. The first half relativises
  against the working directory, the second half calls `toAbsolutePath` and
  absolutises it again — so the result is always absolute, despite the name and
  despite `docs/PathIOReference.md` describing it as "path relative to the current
  working directory"
- Worse, the two halves disagree about *which* directory is current:
  `relativePathToCwd` reads `config.userdir`, which `withMountLines` can inject,
  while `standardizePath` falls through to Java's real `user.dir`. Visible in one
  pair of values under an injected user — `stdpath "."` gives the injected
  `/c/munit/test`, `relpath "."` gives the real checkout path
- So the earlier `pwd` fix only reached half of it. The Rust port has both halves
  agreeing, which makes the relativise-then-absolutise round trip an exact no-op
  and `relpath` identical to `stdpath` — arguably revealing that the relativisation
  step earns nothing as written

**BUG — `standardizePath` matched mount prefixes without a segment boundary (`PathsUtils.scala`)**

- It scanned the mount keys itself —
  `keys.filter(pstr.startsWithIgnoreCase).sortBy(-_.length).headOption` —
  longest-first, but a plain string prefix. So `C:/msys64extra/x` matched the
  `c:/msys64` root mount and `.stdpath` returned the bare remainder `extra/x`: a
  relative string standing in for an absolute path
- Now uses `Resolver.findPrefix`, which requires the next character to be `/` or
  `:` and is the matcher the rest of resolution already uses. `C:/msys64extra/x`
  falls through to the drive mount and yields `/c/msys64extra/x`; the genuine child
  `C:/msys64/usr/bin` still resolves to `/usr/bin`
- This was the third hand-rolled prefix scan in the codebase, after the two in
  `String.local`. All three are now the one matcher
- Covered by a new test in `PathsUtilsCoverageSuite`, verified to fail against the
  old scan

**BUG — `String.local` produced garbage on Windows (`StringExts.scala`)**

- Every Windows result was wrong: `/tmp/x` came back as `Tmp/x`, `/c/Users` as
  `C/Users`, `/usr/bin` as `Usr/bin`. Two faults in one expression:
  - `winSeq.head` was taken from a `posix2win` value, but that map is
    `LcLookupMap[String]` — `.head` was the first *character* of the Windows path,
    not the path. The variable name suggests `win2posix` (`Seq[String]`) was meant,
    which maps the other direction
  - `collectFirst` over an unordered map returned an arbitrary matching prefix with
    no segment-boundary check, so the synthetic drive mount `/t` beat the real
    `/tmp` — the stray `T` in `Tmp/x`
- Now routed through `Resolver`, which already does longest-prefix matching with a
  boundary check and is covered by the cross-language parity fixtures
- It survived because the Windows assertion in `StringExtsSuite` was
  `assert(result.nonEmpty)` — true of garbage. Replaced with value assertions over
  a synthetic mount table, plus an equivalence check against `Path.localpath` that
  cannot drift with the machine's mounts
- Non-POSIX input is still returned untouched, which is what makes `String.local`
  differ from `Path.localpath`; that distinction is now documented and tested

**`Path.local` now yields the native form (`PathExts.scala`)**

- It was `normalizePosix(p.toString)` — character-for-character identical to
  `.posx`, which made the pair pointless. The intended split is that `.posx`
  always gives forward slashes and `.local` gives whatever the platform uses, so
  `.local` is now an alias for `.localpath`: `C:\tmp\x` on Windows, unchanged
  elsewhere
- **Behaviour change** for callers of `.local` on Windows. `docs/PathIOReference.md`
  documented the old aliasing and has been corrected

**`pwd` tracks the active config (`PathsUtils.scala`, `Paths.scala`)**

- `pwd` was a top-level `lazy val`, so it captured `config.userdir` at first access
  and never saw a later `withMountLines`. Correct in production — the JVM's
  `user.dir` is fixed for the process — but it meant an injected user was ignored,
  so `relativePathToCwd` and `Path.relpath` relativised against the wrong directory
  and could not be covered by the synthetic-mount suites
- Now a `def` reading `PathsConfig.pwdPath`, a `lazy val` on the config itself. The
  cache lives where the lifetime does: one instance per `withMountLines`, so it is
  invalidated exactly when the user changes, and `pwd` stays allocation-free at the
  call site. No observable change in production

**BUG — the RNG parity fixture was never tracked (`.gitignore`)**

- `.gitignore` has `test-data/*` with a single `!test-data/tprf3-parity/`
  exception, so `test-data/numpy-rng-parity/` — added last commit — was silently
  ignored and never committed. `rust/tests/numpy_rng_parity.rs` and
  `uni.data.NumPyRngParitySuite` both pass on the machine that generated it,
  because the file is simply there, and both fail everywhere else with a missing
  fixture. The failure mode is invisible to the author by construction
- Exceptions added for `numpy-rng-parity/` and `path-parity/`, and the comment now
  says that each new fixture directory needs one

**Deprecated — `posixAbs` and `posixRel` (`PathsUtils.scala`)**

- Both are plumbing behind `Path.posix`, `String.posix` and `Path.relpath`, are
  undocumented, and have no callers outside the library. They will become
  `private[uni]` in a later release; deprecating first rather than narrowing
  outright, since narrowing is source-breaking for a published artifact
- Each is now a one-line delegate to a `private[uni]` `toPosixAbs` / `toPosixRel`
  holding the implementation. That keeps the library's own call sites
  warning-free, and when the narrowing happens the deprecated wrapper simply
  disappears rather than needing the body moved
- The only remaining callers of the deprecated names are the suites that test
  them by name (`PathsUtilsCoverageSuite`, `PosixFmtSuite`, `UniRootCoverageSuite`,
  `PathSpec`). Those warn on a fresh compile, deliberately — the warning is the
  reminder that the narrowing is still pending

**BUG — `.posix` threw on any relative path containing a separator (`PathsUtils.scala`)**

- `Paths.get("a/b").posix` raised `IllegalArgumentException`, while
  `Paths.get("bare.txt").posix` returned `/c/munit/test/bare.txt`. `posixRel`
  delegates to `posixAbs`, so it threw too
- Cause: `applyTildeAndDots` absolutises a bare filename but deliberately leaves
  anything containing a slash alone. Such a value survived resolution untouched,
  matched no mount prefix, and reached `winAbsToPosixAbs`, whose
  `require(cygMixed(1) == ':')` fails for a path with no drive letter
- `posixAbs` now returns a relative result unchanged, matching `cygpath -u a/b`,
  which likewise preserves it. The `bare.txt` / `a/b` asymmetry in
  `applyTildeAndDots` is left as-is — narrowing that is a semantics change rather
  than a bug fix, and `cygpath` would preserve both

**BUG — four defects in mount-table parsing and resolution (`Paths.scala`)**

All four are latent behind "if uni is ever handed an fstab": production parses
`mount.exe` output, which has no comments and no `none` line, and on MSYS2 the
default cygdrive prefix `/` happens to be right. The Rust port needs an fstab
fallback — there is no `mount.exe` on non-MSYS Windows, and fstab is the only
source on Linux and macOS — which is how all four surfaced.

- **Comment lines were parsed as mounts.** MSYS2's shipped `/etc/fstab` has 16
  comment lines, several of them commented-out example mounts written with no
  space after the `#`: `#C:/cygwin64 / ntfs binary,noacl,auto` splits into
  `("#C:/cygwin64", "/")`. Those became live entries whose Windows side starts
  with `#`, so resolution built strings like `#C://Users` and `JPaths.get` threw
  `InvalidPathException` — `/c/Users` and `/etc/fstab` failed outright. A
  comment-derived `/c` entry also satisfied `isRealDrive` and suppressed the
  synthetic `C:` drive that should have mapped it
- **`none` was treated as a device.** It is a directive; the shipped line's own
  comment reads *"It removes cygdrive prefix from path"*. Read as a mount it made
  `msysRoot` the literal string `none`, so `/` resolved to `none`
- **cygdrive derivation only examined the first entry.** The `collectFirst`
  pattern `case (win, posix)` is total, so it was satisfied by entry 0 and never
  scanned on; the `else null` shows the intent was to keep looking. The prefix
  silently defaulted to `/` unless the declaring line came first. Measured on
  tables differing only in line order: marker first → `/cygdrive/` and
  `/cygdrive/c/tmp` → `C:/tmp`; marker second → `/` and `/cygdrive/c/tmp` →
  **`none/c/tmp`**. That is the one case where a valid POSIX path yielded a wrong
  `Path`, and it is the normal situation on Cygwin, whose `mount` output does not
  list the drive-root line first. Fixed with guards on the cases
- **A bare-drive mount produced a doubled separator.** `/c/Users` resolved to
  `C://Users`, because a target ending in `:` had `/` appended while the suffix
  already began with one. `JPaths.get` collapses the pair, which is why it went
  unnoticed — but the raw string is what other implementations must reproduce, and
  Rust has no such collapser

Verified against `cygpath -m` on 28 real POSIX paths: the only remaining
differences are a trailing slash on `/`, cygpath's `.exe` suffixing, and
`/proc/cpuinfo`, which has no Windows equivalent.

**NEW — Rust port of mount-aware path resolution (`rust/src/upath/`)**

Tier 1 of the `uni.Paths` port: the mount-dependent POSIX↔Windows conversion, so
Rust client code can be written once for Windows, Linux and macOS. `std::path`
understands drive letters and UNC but nothing about MSYS2/Cygwin mount tables, so
`/database-backups/x` had no way to reach its Windows location.

- `MountMaps::parse` handles both `mount.exe` output and `/etc/fstab` columns,
  derives the cygdrive prefix, and synthesises the `X: -> /x` entries for unmapped
  drives plus a root entry when the table lacks one — which is what makes `/q/file`
  resolve to `Q:/file` on a machine whose fstab never mentions Q:
- `classify` covers all seven path shapes and `find_prefix` does **longest-prefix**
  matching with a segment-boundary check, so overlapping mounts resolve to the
  deeper target: with `/opt` and `/opt/ue` both mounted, `/opt/ue/src` is `D:/ue/src`
- `resolve_pathstr`, `posix_abs`, `posix_rel` and `apply_tilde_and_dots` ported from
  `Resolver` and `PathsUtils`, including the hidden-file carve-out that keeps
  `.gitignore`'s leading dot
- **`is_windows` is data, not `cfg!(windows)`.** Rust's is compile-time, unlike the
  JVM's runtime `os.name`, so gating on it would make the Windows rules
  uncompilable — and untestable — off Windows. One live `cfg!(windows)` remains, in
  `PathContext::from_env`. This is strictly better than the Scala, whose path suites
  are `if isWin`-gated and skip entirely on Linux
- `drive_cwd` needs no `windows` crate and no subprocess. Windows keeps the
  per-drive working directory as **per-process** state in hidden `=X:` environment
  slots, inherited by children; `GetFullPathNameW("X:.")` — what the JVM's
  `toAbsolutePath` calls — is defined in terms of those, so reading them agrees with
  the Scala by construction. A drive never visited has no slot and Windows itself
  answers with the root, so that is the correct answer rather than a fallback
- Two places where fidelity beat instinct, both caught by the fixtures: a bare `C:`
  input keeps Windows **backslashes**, because `applyTildeAndDots` interpolates the
  `Path` verbatim while `resolveDriveRelPathstr` normalises separately; and
  `posix_abs` **fails** for a relative path containing a slash, because it reaches
  the cygdrive fallback with no drive letter, exactly as `winAbsToPosixAbs`'s
  `require` does

**Path parity fixtures (`test-data/path-parity/`)**

- `uni.apps.PathParityGen` writes a per-platform reference over six synthetic mount
  tables × 32 inputs × 3 fields, plus derived facts and `driveCwd` — 618 cases.
  Checked independently by `uni.PathParitySuite` and `rust/tests/path_parity.rs`
- Tables are chosen to pin one thing each: minimal root, drive mounts, cygdrive
  style, **overlapping** mounts, **one-to-many** reverse mapping, and fstab format
  with no root entry
- Every case is driven by `withMountLines` and a fake user, so nothing depends on
  the host's drives. The fixture is tagged with the platform that produced it,
  because Scala can only record the rules of the host it runs on; the Rust test
  takes `is_windows` from the tag and so verifies Windows semantics from any host.
  Regenerate on a second platform to add its block
- 15 Rust unit tests cover the pieces directly, including that the Windows rules
  run with `is_windows = true` regardless of host

**BUG — `NumPyRNG.randn` tail draws were displaced by 0.2115 (`NumPyRNG.scala`)**

- `ZIGNOR_R`, the Ziggurat's right edge, was `3.442619855899` — Marsaglia &
  Tsang's constant for *their* 256-level table, not NumPy's `3.6541528853610088`.
  It was also inconsistent with the file's own `ziggurat_nor_inv_r`, which is
  NumPy's and is supposed to be its reciprocal
- `ZIGNOR_R` is read in exactly one place, the tail sampler's `R + xx` return, and
  in no comparison. So the effect was confined to draws beyond the ziggurat edge —
  about 1 in 3900, or 0.026% — each returned 0.2115 too far from zero, with the
  other 99.97% of draws bit-exact against NumPy. That is invisible to a
  mean/variance check (it moves a 10,000-sample mean by ~6e-5) and to every
  reproducibility test, since it was deterministic
- **Impact:** anything reading the far tail — extreme quantiles, VaR/expected
  shortfall, tail-dependence, rare-event Monte Carlo — was drawing from a
  slightly wrong distribution. Ordinary uses of `Mat.randn`/`normal` were not
  measurably affected
- `nextLong`, `nextInt`, `nextDouble`, `uniform` and `nextBoundedInt` were never
  affected: they do not touch the Ziggurat
- Now verified against NumPy 2.4.6 draw-for-draw, and guarded three ways: the
  generator refuses to emit tables whose `R` is not `1/inv_r`, and both test
  suites pin specific tail draws to NumPy's exact bit patterns — an absolute
  check, so regenerating the fixtures cannot bless a wrong constant
- Found by the Rust port below. Building a second implementation of the same
  algorithm and demanding the two streams agree bit-for-bit is what exposed it:
  the mismatch was 266 draws in a million, all displaced by an identical
  0.2115, which is what identified `ZIGNOR_R` as the single constant involved.
  Nothing weaker would have caught it — the draws are correctly distributed
  apart from a shifted tail, so every distributional and reproducibility test in
  the suite passed both before and after

**NEW — Rust port of `NumPyRNG` (`rust/src/numpy_rng.rs`)**

- `NumPyRng` reproduces PCG64 XSL RR 128 and NumPy's SeedSequence expansion,
  giving the same stream as the Scala class for the same seed. The 128-bit state
  is a plain `u128` rather than the Scala version's two-`Long` split, which the
  JVM needs and Rust does not
- Exact by construction for `next_u64`, `next_i32`, `next_f64`, `uniform` and
  `next_bounded_u32`: integer arithmetic plus one exactly-representable division
  by 2^53
- `randn` agrees with NumPy bit-for-bit, and with the JVM on all but ~2.5 tail
  draws per million, where `Math.log1p` and C's `log1p` round differently by one
  ulp. Never a control-flow divergence: over 6M draws the wedge's `exp`
  comparison never disagreed, so the streams stay aligned rather than
  desynchronizing. The parity fixture snaps `randn` to a 2^-40 grid for this
  reason and this reason only
- Ziggurat tables are generated from the Scala source by `py/gen_ziggurat_rs.py`
  rather than transcribed, so the 768 constants cannot drift apart
- Cross-checked by `uni.data.NumPyRngParitySuite` and
  `rust/tests/numpy_rng_parity.rs` against one committed fixture
  (`test-data/numpy-rng-parity/`), covering 6 seeds × 7 draw patterns; neither
  side needs the other language installed. One pattern interleaves the methods,
  which is the only way to pin the half-draw `nextBoundedInt` carries in state

**Benchmark inputs are now identical across all three languages (`bench_tprf3.rs`)**

- `rust/src/bin/bench_tprf3.rs` drew its inputs from `StdRng` seeded with 42 and
  a Box–Muller transform, while `jsrc/tprf3Bench.sc` and `py/bench_tprf3.py` both
  seed 0 and draw `randn`/`standard_normal` for X, y, Z in order. The Rust column
  of the `uni.apps.Tprf3Bench` table was therefore measured on different matrices
  than the other two — a confound in a table whose whole purpose is comparison
- It now uses `NumPyRng::new(0)` with a row-major fill, verified element-for-element
  against NumPy: `Mat`, NumPy and `ndarray` are all row-major, so the same value
  lands at the same `(r, c)` in all three
- Rust timings from before this change are not comparable with ones after it. No
  committed table carried Rust numbers, so nothing published needed revising
- Drops the `rand` dependency, which the benchmark was its only user of
- `print_config` in the bench now documents what the `blas` feature is worth:
  ~2.3× on both large OOS rows, enough to turn OOS Recursive from a 2.2× loss
  against Scala into a 1.2× win, since Scala runs on JNIBLAS-backed OpenBLAS and a
  default pure-Rust build is not the like-for-like baseline. It inverts at the
  small size, where BLAS call overhead exceeds the kernel win

**BUG — `tprf3.py` autoproxy returned no OOS forecasts at all (`py/tprf3.py`)**

- `mintrain` (OOS Recursive) and `min_nona` (OOS Rolling) were passed down as
  `_t3prf`'s `min_obs`, which also gated **pass 2** and the **out-of-sample
  fit** — both cross-sectional regressions whose observation count is N, the
  predictor count, not T. With the default `mintrain = T/2 = 45` and N = 12,
  `_ols` demanded 45 valid rows out of 12 and returned NaN for every one, so
  `Sigma` came out all-NaN and every forecast with it
- Symptom was a clean cliff at `mintrain = N`: with N = 12, mintrain 12 produced
  77 forecasts and mintrain 13 produced **zero**. Affected autoproxy OOS
  Recursive and OOS Rolling; Cross Val was spared only because its call site
  never passed `min_obs`
- Both sites now take the identification floor — one more observation than the
  design has parameters — leaving `min_obs` to gate pass 1, where a time-series
  threshold belongs. Restores 44 and 59 forecasts on a T=90/N=12 case, matching
  `tprf3fast.py` to 6e-13
- This is the same defect v0.15.1 fixed in `Tprf3.scala` (`pass2MinObs = L + 1`).
  Scala had corrected *both* its pass 2 and its OOS fit; Python had neither, and
  fixing only pass 2 left the point forecast NaN while `yhat` came out correct —
  so the visible symptom survived the first half of the fix
- Bit-identical on all twelve fixture/procedure combinations in
  `test-data/tprf3-parity/`, which all have N ≥ 10 and so never tripped it

**`estimate3prf_fast` now rejects NaN input instead of returning all-NaN (`py/tprf3fast.py`)**

- The vectorized passes batch every per-column and per-row regression into one
  solve, which cannot carry the per-regression NaN masks `tprf3.py` applies. A
  single NaN therefore poisoned `AᵀA` and the function returned an all-NaN
  series, giving a caller no way to distinguish an unsupported input from a model
  that fitted nothing
- Raises `ValueError` naming the offending array and its NaN count, and pointing
  at `tprf3.estimate3prf` for data with missing values. NaN support is not
  implemented rather than broken — the Rust port declines the same way
- `tprf3.py` is unchanged here and still filters NaN rows per regression

**NumPy 3PRF gets the v0.15.1 OOS optimizations (`py/tprf3fast.py`)**

- v0.15.1 tuned the Scala OOS windows only, which made the published Py/Scala OOS
  ratios a comparison between differently-optimized algorithms rather than between
  implementations. All three techniques are now in `tprf3fast.py`, so the
  comparison is like-for-like again:
  - **Pass-1 cross products by downdate.** Every OOS Recursive and Cross Val
    window keeps all rows but one contiguous block, so `dZᵀdZ` and `dZᵀX` are the
    full-sample ones minus that block's contribution — O(drop·N·L) rather than
    O(keep·N·L). For a Cross Val window dropping one row that is O(N·L) instead
    of O(T·N·L)
  - **Column std by downdate**, via
    `Σ_kept(x−m_keep)² = (Σ_all(x−μ)² − Σ_drop(x−μ)²) − keep·(m_keep−μ)²`, with
    the same majority-kept guard and direct-recompute fallback as Scala and Rust
  - **Scaling folded onto the (L+1)-column operand.** `Zᵀ(X·D⁻¹) == (ZᵀX)·D⁻¹`
    and `(X·D⁻¹)·P == X·(D⁻¹·P)`, so the normalised window is never materialised —
    which also removes the per-window keep×N gather and divide
  - **Pass 2 in natural order.** `dpᵀ·Xᵀ == (X·dp)ᵀ`, so the small keep×(L+1)
    product is formed and transposed instead of taking products against Xᵀ. For
    Cross Val, pass 2 is a cross-sectional regression and therefore independent
    per row, so running it over all T rows yields the held-out row's design as a
    by-product: the forecast costs nothing and the only per-window gather is
    `L+1` wide
- Measured at T=650/N=40/L=2: OOS Cross Val 84.3 → 38.6 ms (2.2×), OOS Recursive
  30.6 → 21.7 ms (1.4×). Scala's lead on Cross Val halves as a result — 13.6×
  against the un-ported NumPy, 6.7× against the ported one — which is the measure
  of how much of the previously published gap was algorithm rather than language.
  At T=200/N=30: OOS Cross Val 13.1 → 8.5 ms, OOS Recursive 5.4 → 4.7 ms
- IS Full is untouched and bit-identical. The OOS forecasts move by ~1e-16 from
  reassociation, well inside the 1e-12/1e-9 tolerance the cross-language fixtures
  already use; verified against both `test-data/tprf3-parity/`'s Scala golden
  values and `py/tprf3.py`, the loop-based reference, on 2424 recorded quantities
- Unchanged where the downdate does not apply, matching where Scala and Rust draw
  the same line: autoproxy rebuilds the proxies each inner iteration, the
  downdates have no NaN-aware form, and OOS Rolling's kept set is the short window
  itself, so a direct pass is already cheaper

**Docs — Rust crate and its 3PRF numbers (`README.md`, `docs/MatDCheatSheet.md`)**

- New "Rust companion crate" section near the top of the README, covering the
  bit-identical NumPy RNG, `randn`'s one-ulp JVM caveat, the 3PRF procedures and
  the parity fixtures that pin them
- The Windows 3PRF table now carries a Rust column, and the cheat sheet gains a
  three-way section with both data sizes. Both state the build configuration,
  because it decides which language wins
- Python and Scala figures come from a single `uni.apps.Tprf3Bench` run so those
  ratios are internally consistent; the Rust column is a `--features blas` build
  measured separately, cross-checked by its pure-Rust baseline landing within 5% of
  the pure-Rust column the same run produced. The previously published median-of-3
  two-way numbers are retained in prose. The Linux and macOS tables are untouched
  and carry no Rust column — the crate has not been benchmarked there

## v0.15.1 — 2026-08-05

*v0.15.0 was built and published to the local Ivy repository but never released
to Maven Central, so this entry covers everything since v0.14.1. Same convention
as the unpublished v0.14.0 folded into v0.14.1.*

**BREAKING — `MatD(rows, cols)` removed (`MatFacades.scala`)**

- Two `Int` arguments used to select a `(rows, cols)` zero-matrix constructor.
  That was the one place where Ints meant *dimensions*; at every other arity —
  and for Doubles at every arity — Ints mean *values*, NumPy-style
  (`MatD(1, 2, 3)` is a 3×1 column of `1.0, 2.0, 3.0`, as `np.array([1, 2, 3])`
  is). Applies to `MatD`, `MatF` and `MatB` alike, via the shared `MatFacade`
- **Migration:** `MatD.zeros(r, c)` for a zero matrix, `MatD.empty` for 0×0, or
  `MatD(3.0, 4.0)` for a 2×1 column of values
- The overload is retained as a `@compileTimeOnly` **tombstone**. Deleting it
  outright would have left `MatD(3, 4)` compiling and silently resolving to the
  Double varargs overload — a 2×1 column instead of a 3×4 matrix. The tombstone
  turns that into a compile error naming the replacements. It should be deleted
  in a later release, once downstream code has migrated
- Note this cannot be covered by a `compileErrors` unit test: munit's
  `compileErrors` runs the typer only, while `@compileTimeOnly` is enforced in a
  later phase. See `src/test/resources/tombstone-check.md`
- NumPy draws the same line by naming rather than by argument type:
  `np.array([1, 2])` vs `np.zeros((3, 4))`

**Fix — `Mat.scale(center = false, doScale = true)` (`Mat.scala`)**

- The divisor is now the root-mean-square of the (possibly centered) values with
  Bessel's correction, `sqrt(Σc²/(n−1))`, computed from the data actually being
  scaled. Previously it always divided by the CENTERED std, even when
  `center = false`
- `center = true` (the default) is **unchanged** — centered data has zero mean,
  so its RMS is exactly the sample std. Only the uncentered case moves
- Why: dividing uncentered data by a statistic that measures only variation
  leaves the result unbounded when the mean dwarfs the sd. A column of
  1000 ± 0.001 scaled to ≈1e6 instead of ≈1, inflating the condition number of
  any design built from it. The RMS bounds every scaled column to unit RMS, and
  it is one rule that specializes to the sample std when centered rather than
  two cases
- This also matches R's `scale(x, center = FALSE, scale = TRUE)`, which the
  method's docstring already claimed to follow. Note sklearn's
  `StandardScaler(with_mean = False)` uses the centered std instead, so uni
  differs from sklearn here

**Array → Mat / CVec / RVec conversions (`Mat.scala`, `VecExts.scala`, `MatFacades.scala`)**

- New extension methods on arrays: `arr.toCVec` (n×1), `arr.toRVec` (1×n),
  `arr.toMat` (column), and `.toMat` on `Array[Array[T]]` and `Seq[Array[T]]`
  (row-major). A 1-D array becomes a column, matching `MatD(…)` / `CVec(…)`
- New `Mat.fromRows` (`Array[Array[T]]` and `Seq[Array[T]]` overloads) — the
  first 2-D factory in the library; also surfaced as `MatD.fromRows`. Previously
  the only route was `MatD(rows, cols, arr2d.flatten)`
- New `apply` overloads taking arrays on `CVec`, `RVec`, `Mat`, and the
  `MatD`/`MatB`/`MatF` facades
- **Fixes a wrong-shape result:** `CVec(arr)` / `RVec(arr)` previously bound the
  varargs overload with `T := Array[Double]`, and `Mat(arr)` / `Mat(arr2d)` bound
  the scalar lift — all four produced a **1×1 matrix holding the array object**
  rather than a vector or matrix of its elements. The scalar lift `Mat(42.0)` →
  1×1 is unchanged
- `Mat.apply(Array)` carries `@targetName` because `Array[T]` and a bare `T`
  erase to the same signature
- New `Mat.wrap(arr, rows, cols)`: explicit zero-copy wrapping. All the new
  conversions **copy**, since `Mat` is mutable (`m(r,c) = v` writes into the
  backing array) and aliasing would make the matrix and the caller's array share
  state in both directions. `Mat.create` and `MatD(rows, cols, data)` still
  alias — unchanged, now documented

**Fixes (`Tprf3.scala`)**

- `estimate3prf(…, pls = true)` returned **all-NaN whenever N < 10**. Pass 2 is a
  cross-sectional regression whose observation count is N, but it was gated on
  `minObs` (a time-series guard, default 10). Now gated on `L + 1`, the
  identification floor. Every real predictor count downstream is below 10, so
  this affected essentially every call
- Fixing that made the pls OOS forecast branch reachable for the first time and
  exposed a latent transpose bug: `xt - colMeans.T` subtracted an N×1 from a 1×N,
  broadcasting to N×N. Now `xt - colMeans`
- `pls1Fit` no longer aliases the caller's `y` array

**Additions (`Tprf3.scala`)**

- `plsClosedForm(y, X)`: vectorized closed form of the PLS-variant 3PRF
  (autoproxy L=1, no intercept in passes 1–2). Both passes collapse to
  projections, replacing the N+T separate OLS solves — 4–6× faster to fit than
  the iterative path. Requires NaN-free input
- `pls1Fit(x: Array[Array[Double]], y: Array[Double])`: array-friendly wrapper
- `Pls3prfModel`: retains phi/sigma/beta *and* the column mean/scale, so
  `predict(rawRow)` takes an un-normalized row (`Tprf3Result.estimateYhat`
  silently requires a pre-scaled one)

**Performance — 3PRF out-of-sample procedures (`Tprf3.scala`)**

- The OOS windows no longer materialize the scaled predictor block. Column
  scaling commutes with both products the filter takes against X —
  `Z'·(X·D⁻¹) == (Z'·X)·D⁻¹` and `(X·D⁻¹)·P == X·(D⁻¹·P)` — so the scaling now
  lands on a matrix with `L+1` columns and `X·D⁻¹` is never built. OOS Recursive
  passes a plain slice view and allocates nothing window-sized for X
- Pass 2 runs in natural order: `B2' = X·designPhi·PtPinv'` yields Sigma
  directly, where the previous `designPhi.T *@ X.T` read **both** operands
  through transpose views
- Each window's pass-1 cross products are now downdated from full-sample ones
  (`FullSample.pass1Downdated`) rather than recomputed: the Cross Val window
  drops an interior block, the Recursive window the suffix `[end, T)`, so both
  cost O(drop·N·L) instead of O(keep·N·L). Only valid when the proxy matrix is
  the same for every window, so autoproxy and the pls branch keep the direct path
- `yhat` is computed lazily (`TailResult`): only IS Full reads the fitted series,
  while every OOS window reads just the point forecast
- Net on Windows (T=650, N=40, L=2): OOS Recursive 3.6 → 1.8 ms, OOS Cross Val
  7.4 → 7.1 ms. **The NumPy twin was not tuned this round**, so the published
  ratios are no longer like-for-like algorithm work — see the README note

**Additions — Rust port (`rust/`)**

- `t3prf` crate: a Rust implementation of the three IS/OOS procedures, matching
  the Scala results to ~3e-15 relative. Carries the same optimizations plus a
  Cross Val specialisation that avoids gathering X at all — `X_kept·dps` is
  `X_full·dps` with the dropped rows skipped, so the only per-window copy is
  `L+1` wide rather than `N` wide
- Optional `blas` feature routes ndarray's matmul through a system OpenBLAS,
  worth ~3× on the OOS paths, whose gemms are skinny. Set `OPENBLAS_NUM_THREADS=1`:
  the window loop is already parallel, and nesting BLAS threads inside it costs ~10%
- Fixes carried over from reviewing the port against the Scala reference: the
  out-of-sample R² is measured against the prevailing training-window mean
  (`rollfore`) rather than the full-sample mean, the recursive training window is
  `[0, t-1)`, the `min_obs` guard is honoured, and the Cross Val drop block is
  clipped with signed arithmetic

**Additions — cross-language parity test**

- `test-data/tprf3-parity/`: committed input fixtures at 17 significant digits
  plus `scala-reference.txt`, 2424 reference values across 3 sizes × 4 procedures
- `uni.stats.Tprf3ParitySuite` (MUnit) and `rust/tests/scala_parity.rs` both check
  against those files, so neither test needs the other language installed — a
  change that moves one implementation fails on that side alone, localizing drift
- Regenerate with `sbt "runMain uni.apps.Tprf3ParityGen"`, and only when the
  values are meant to move

**Benchmark harness (`jsrc/tprf3Bench.sc`, `uni.apps.Tprf3Bench`)**

- Reports Rust alongside Python and Scala when a release binary is already built,
  gated on its presence rather than invoking cargo — so the Scala/Python
  comparison still runs on a machine with no Rust toolchain. Both Rust builds land
  at the same path and differ ~3× on the OOS rows, so the binary reports its own
  configuration and the table states which one produced the numbers. Warns when
  the binary is older than `rust/src`
- Ratio cells within 5% now render `≈ tied` instead of `1.0× slower`, which
  presented run-to-run noise as a directional result
- IS Full is sampled at least 200 times with the heap settled first. At `loops`
  sized for the OOS procedures it saw only a few ms of measurement, and GC pauses
  from the neighbouring OOS sections moved its median by 2× (Large read 0.56 ms
  against 0.25 ms measured standalone)
- Python discovery prefers WinPython installs found under `/opt/winPython`,
  globbed rather than hard-coded, and probes candidates with `import numpy`
  instead of `--version` — the previously preferred install has a broken numpy
  that passed the old check and then failed inside the bench script
- The documented `-python <exe>` override was parsed but never read; it now works,
  with MSYS2-style paths resolved. Added `-norust`
- The NumPy BLAS name in the section header is read from `show_config('dicts')`;
  the old text scan returned `?` on builds that emit JSON-ish output

## v0.14.1 — 2026-07-10

*(v0.14.0 was tagged but never published; this entry covers everything since
v0.13.4, including all changes originally staged for v0.14.0.)*

**Performance — 3PRF OOS hot path (`Tprf3.scala`, `Mat.scala`)**

- NaN presence is now detected once per `estimate3prf` call (`anyNan` over X, y, Z)
  and gates NaN-free fast paths throughout: `stdCols`/`colMean` skip the
  per-element NaN test and per-column observation counts, and `nanOls` skips the
  row scan and `selectRows` copies entirely (with no NaNs every row is kept, so
  the filtered fit equals the direct one)
- `nanStdCols` / `nanMeanCols` rewritten as row-major sweeps — the same
  per-column accumulation order (bit-identical results) but sequential memory
  access over the row-major backing array instead of striding cols×8 bytes/step
- OOS Cross Val: the dropped window is a contiguous block [lo, hi), so the
  `setdiff` Set build + `selectRows` copies are replaced by
  `standardizeDropRows`, which fuses the row drop, the column std, and the
  normalize into one pass-set with a single output allocation. Clean (NaN-free)
  inputs go further via `standardizeDropRowsInc`: full-data column stats are
  computed once per call and each window's std becomes an O(drop·N) downdate
  instead of an O(keep·N) recompute, with a cancellation guard that recomputes
  directly when the kept set is not the majority. Downdated stds drift ≤ ~1e-13
  from the two-pass form (not bit-identical)
- OOS Recursive / Rolling: training windows are read through zero-copy slice
  views — `standardize` reads the view stride-safely and emits one fresh
  contiguous matrix, so no intermediate window copy is materialized
- `withIntercept` writes `[1 | X]` into a single buffer instead of allocating a
  `ones` column and routing through `hstack`
- `Mat`: `fastBinOp`'s broadcast branches (1×N row, N×1 column, and the
  strided-view forms) now run in parallel above a dedicated 64K-element
  threshold (`bcastRows`) — previously single-threaded at any size. The
  threshold is higher than the usual 4096 because `IntStream.parallel()` setup
  (~100 µs measured) makes medium-sized broadcasts slower in parallel
- Measured (`jsrc/tprf3Bench.sc` side-by-side, Windows 11, netlib Java11BLAS,
  vs Python 3.14.6 / NumPy on OpenBLAS; medians of 25 timed calls per OOS
  procedure after explicit warm-up):

  | Scenario | Python | Scala | Ratio |
  | :--- | ---: | ---: | :--- |
  | IS Full (Small: T=200, N=30, L=2) | 0.12 ms | 0.06 ms | 2.1× |
  | OOS Recursive (Small) | 5.47 ms | 1.00 ms | 5.5× |
  | OOS Cross Val (Small) | 13.89 ms | 1.40 ms | 9.9× |
  | IS Full (Large: T=650, N=40, L=2) | 0.36 ms | 0.34 ms | ~tie |
  | OOS Recursive (Large) | 32.59 ms | 3.60 ms | 9.1× |
  | OOS Cross Val (Large) | 87.65 ms | 7.37 ms | 11.9× |

**Benchmarks — measurement fairness and stability**

- `Tprf3Bench` / `jsrc/tprf3Bench.sc` / `py/bench_tprf3.py`: each OOS procedure
  is explicitly warmed before timing (previously they were timed cold — JIT and
  ForkJoin/OpenBLAS spin-up landed in the samples) and readings are the median
  of 25 individually-timed runs instead of a mean over 5 (which showed 5–55 ms
  spreads for identical code). The Scala driver now captures the Python output
  and prints a side-by-side markdown comparison table (Python vs Scala with
  ratio) in the MatDCheatSheet style
- `py/tprf3fast.py` (the benchmark-fairness twin): gains the same once-per-call
  NaN detection gating `np.std` vs the slower `nanstd` machinery; the three
  `lstsq` passes are replaced by `_ols` normal-equations solves — the 3PRF
  designs have only L+1 columns, so AᵀA is tiny and `solve` beats the SVD
  driver several-fold, with a rank-revealing `lstsq` fallback if AᵀA is
  singular; OOS Cross Val drops the contiguous block directly instead of
  `setdiff` indexing
- `jsrc/tprf3Bench.sc`: python interpreter discovery no longer relies on a
  hard-coded WinPython install path — it scans every `python3`/`python` on
  PATH and picks the first that is runnable *and* has numpy (skipping broken
  shims such as the Microsoft Store app-execution alias, and looking past a
  numpy-less `/usr/bin/python3` to a numpy-capable homebrew python). New
  `-python <exe>` flag overrides discovery (validated rather than silently
  falling back)

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
- `proc(...).timeout(ms).run()` / `.stream(...)` now return promptly when the timeout
  fires instead of blocking until the child process exits. On a forced-timeout kill,
  orphaned grandchildren (e.g. bash's `sleep`) can keep the stdout/stderr pipes open,
  so the reader threads never saw EOF and the drain-thread joins blocked for the
  child's full lifetime (a 200 ms timeout on `sleep 10` took ~10 s, longer with an
  orphaned child). `awaitProcess` now reports whether it timed out, and `run`/`stream`
  bound the drain joins in that case (the drain/reader threads are daemons, so
  abandoning them is safe). Status is unchanged (`-1` on timeout)

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
- `Double` autoboxing eliminated from the remaining `Mat[Double]` hot paths,
  driven by JFR profiling of `jsrc/tprf3Bench.sc` (boxed `java.lang.Double`
  allocation samples 188 → 0). `MatData._tdata: Array[T]` erases to `Object`, so
  the generic element accessor and `Numeric`/`Fractional` dispatch boxed every
  read; each site now branches to a primitive `Array[Double]` path under a
  `ClassTag == Double` guard (correct for views via the stride equation). Sites:
  row/column extraction (`m(i, ::)`, `m(::, j)`), `m(Range, Range)` slicing,
  `inverse`/`luDecompose` (shared primitive-LU helper), `zeros`/`ones`/`eye`,
  `randn`/`normal`/`uniform` (primitive fills preserving RNG call order), `power`
  (both overloads), `hstack`, `map`/`zipMap`, and the `Tprf3` OOS hot path
  (`atD` reads, `java.lang.Double.isNaN`, the rsq post-processing loops). Result
  (Large, T=650 N=40 L=2, JVM 21): OOS Recursive 42 → 21 ms, OOS Cross Val
  77 → 56 ms, IS Full 1.3 → 0.55 ms.
- Client-facing element boxing eliminated for `MatD`/`MatF`: the ordinary `m(i, j)`
  read and `m(i, j) = v` write (and the rest of the indexing family) on a
  `Mat[Double]`/`Mat[Float]` no longer allocate a `java.lang.Double`/`Float` for
  external (`import uni.data.*`) callers — no special accessor needed. The generic
  `apply(i,j): T` / `update(i,j,v: T)` box because `Array[T]` erases to `Object[]`;
  a new package-level facade (`MatDOps.scala`) re-supplies the **complete** family —
  scalar access/assignment, slices, boolean masks, fancy `Array[Int]` indexing,
  slice assignment, and `at` — specialized to `Mat[Double]` and reading the primitive
  backing array directly. (Scala 3 extension overloads don't merge across receiver
  specificity, so adding any `Mat[Double]` `apply` shadows the entire generic family
  for that receiver — hence the whole family is re-supplied.) These win by specificity
  for a `Mat[Double]` receiver; in-package `import Mat.*` sites keep the generic path.
  The `Mat[Float]` twin (`MatFOps.scala`) is generated from `MatDOps.scala` by a
  `build.sbt` sourceGenerator (generated into `sourceManaged`, never checked in) so the
  two cannot drift. Verified at the bytecode level (`daload`/`faload`, no `*.valueOf`)
  and by JFR allocation profiling (zero boxed-scalar samples on the element path).

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
  from `BigDecimal(NaN)` (consistent with this release's MatB non-finite policy)
- File split (first step): the facades, `MatFacade`, `LeastSquaresResult`, and
  the `MatD`/`MatB`/`MatF`/vector type aliases moved from Mat.scala to a new
  `MatFacades.scala` (Mat.scala: 5,352 → 4,860 lines). Pure relocation of
  package-level definitions — no dispatch or API change
- File split (completed): three method groups with no companion-dispatch
  constraint moved out of `object Mat` into sibling files, re-exported inside
  `object Mat` (`export MatXxxOps.*`) so they remain companion members —
  implicit-scope and `import Mat.*` dispatch are byte-for-byte unchanged for
  clients (verified by the full suite plus `jsrc/docCheck.sc` /
  `jsrc/tastyCheck.sc` against the published jar):
  - `MatMathOps.scala` — trig/hyperbolic/floor/ceil/log10/log2/trunc and the
    ML activations (sigmoid, relu, leakyRelu, softmax, logSoftmax, elu, gelu,
    dropout) plus their shared `mapToDouble` kernel
  - `MatPandasOps.scala` — sort/argsort/nlargest/nsmallest/between/unique/
    nunique/valueCounts, diff/shift/pct_change, percentile/median (+ the
    private `percentileOf` kernel), describe, idxmin/idxmax, histogram, and
    the `rolling` entry point (`class RollingWindow` itself stays in Mat.scala)
  - `MatSignalOps.scala` — polyfit/polyval/convolve/correlate companion methods
  Mat.scala: 4,860 → 4,025 lines. Internals widened to `private[data]` for the
  new files: `fastD`, `fillD`, `nanFill`. One subtlety the new files must
  respect: the opaque type must be imported as a direct member
  (`import Mat.Mat`) — the package-level forwarder (`export Mat.{Mat, …}`)
  cannot be elaborated while `object Mat` is mid-completion with the
  `export MatXxxOps.*` clauses (cyclic; manifests as "Not found: type Mat")

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

- Windows benchmark tables in `README.md` and `docs/MatDCheatSheet.md` re-measured
  once more at release time, all on the same (faster) machine: NumPy 2.4.6 /
  Python 3.14.6 vs uni 0.14.1 / JVM 21, min times. MatD wins 8/9 scored ops vs
  NumPy — the matmul row is now an honest fallback-vs-native comparison (netlib
  JNIBLAS could not load `libopenblas.dll` on that machine, so MatD/Breeze matmul
  ran pure-JVM while NumPy used native OpenBLAS; level where JNIBLAS loads) — and
  6 wins + 1 tie over Breeze's 7 scored ops (geomean ~4.3×). 3PRF tables updated
  with the v0.14.1-tuned numbers (IS Full ≈ tied, OOS Recursive 9.1×,
  OOS Cross Val 11.9×). The Linux/macOS tables are current as measured on their
  own machines. Version references across the docs now cite v0.14.1 (v0.14.0 was
  never published)
- Refreshed all benchmark tables in `README.md` and `docs/MatDCheatSheet.md` with
  same-day, same-machine measurements of uni 0.14.x vs NumPy 2.4.1 vs Breeze 2.1.0
  (JVM 21): MatD wins 9/9 scored ops vs NumPy and wins or ties 9/9 vs Breeze
  (geomean ~6.2×); 3PRF tables updated to the post-centering numbers with both
  implementations optimized (loops=5 OOS measurements): IS Full 1.2/1.3 ms tied,
  OOS Recursive 270/42 ms (6.4×), OOS Cross Val 720/77 ms (9.4×)
- `docs/ReferenceGuide.md`: added the eleven sections its table of contents promised
  but never delivered (Indexing and Slicing, Arithmetic, Broadcasting, Linear Algebra,
  Statistics, Element-wise Math, Machine Learning, RNG, Data Manipulation,
  Comparison/Boolean, Display), plus the new column-vararg factories and the
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

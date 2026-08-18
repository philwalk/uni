# Design Note: BadPath Family and a Future uni FileSystemProvider

Status: design note, not implemented. Written 2026-08 during 0.16.0 release prep.

## Context

`uni.Paths.get` is a string-rewrite front end over the JVM default filesystem:
it classifies the input string (absolute, UNC, drive-relative, posix-mounted,
directory-relative), rewrites it against the MSYS2 mount table, and finishes
with a bare `java.nio.file.Paths.get(result)`. Two consequences follow:

1. Input the Windows JVM cannot represent (`"a:b:c"`, `"a<b"`, embedded NUL)
   throws `InvalidPathException` from that final call. This breaks the
   canonical uni idiom, which must never throw on arbitrary strings:

   ```scala
   if ("some-file".asPath.isFile then ...   // must work for ANY string
   ```

2. Nothing between classification and the final call may probe the OS with an
   unvalidated drive letter: `toAbsolutePath` on a nonexistent drive throws
   `java.io.IOError`, and on a mapped-but-disconnected network drive can hang
   inside `GetFullPathName` for minutes. Drive letters must be checked against
   `Internals.rootDrives` (a cheap `GetLogicalDrives` bitmask read, refreshed
   per call so hot-plugged USB drives are seen) *before* any JVM call.

## Lineage: this is a revival, not an invention

The precursor library (`vastblue.unifile`, `Platform.scala`) already solved
problem 1 with an input-preserving sentinel. `pathsGetWindows` classified via
an ADT — `PathAbs | PathDrv | PathRel | PathPsx | PathBad` — and the bad arm
returned an *ordinary* `Path` carrying the original input:

```text
def BadPath(psxStr: String) = JPaths.get(s"BadPath-$psxStr")
```

Properties of that original design:

- **Total-looking API**: `Paths.get` itself returned the sentinel; callers
  never saw an exception for malformed input.
- **Input-preserving**: `println(s"bad path [$p]")` printed `BadPath-a:b:c` —
  the diagnostic survived.
- **Ordinary `Path`**: no wrapper type, no ceremony; `exists` is simply false.
- **Latent flaw — not actually total**: the constructor prefixes the raw
  input, so illegal characters survive into `JPaths.get("BadPath-a:b:c")`,
  which itself throws on Windows (colon at index > 1). The sentinel failed
  for exactly the inputs it existed to absorb.
- **Fragile recognition**: `BadPath-` is a string prefix on a *relative*
  path. Absolutisation buries it mid-string; a legitimate file literally
  named `BadPath-report` is a false positive.

Neither the ADT nor `BadPath` was ported when uni was created (2025-12-04).
The other half of the original validate-first design — the drive-letter guard
(`canExist` / `safeAbsolutePath`) — *was* ported on day one, then decayed:
the guarded `oldget` branch was deleted in the 2025-12-08 performance
refactor, and the 2026-01-20 refactor introduced `driveCwd` with an eager,
unguarded `toAbsolutePath` while the guarded `safeAbsolutePath` sat dead in
the same file. The synthetic test harness introduced by the same commit stubs
`driveCwd` itself, which is why no test ever exercised the unguarded probe.
The guard was restored inside `driveCwd` in 0.16.0.

## Near-term: the ordinary-Path BadPath family

The revived design keeps the original architecture and fixes its two flaws.
Unrepresentable input maps to an ordinary default-filesystem `Path` of shape:

```
Q:/__uni-BadPath__/<pua-encoded-original-input>     (Windows; Q: = chosen absent drive)
  /__uni-BadPath__/<pua-encoded-original-input>     (Linux/macOS)
```

- **Cygwin-style PUA encoding — total construction, exact round-trips,
  readable display**: each character the Windows path parser rejects maps to
  its Unicode Private Use Area counterpart at `U+F000 + char` — the same
  convention MSYS2/cygwin uses on disk (`:` → U+F03A, `<` → U+F03C, ...).
  The alphabet is cygwin's (`" * : < > ? |`, backslash, controls < 0x20,
  component-trailing space) plus one deliberate extension: `/` → U+F02F,
  which cygwin never needs (it is the POSIX separator) but exact recovery
  does — unescaped, the JVM collapses doubled slashes and merges a leading
  slash into the marker root. With it, the entire original string is a
  single name element, so a BadPath is always exactly two elements: marker
  + payload. In MSYS2-ecosystem fonts the PUA characters render as
  lookalikes of the originals, so even a raw leaked `toString` reads almost
  like the offending input. Known ambiguity, accepted with cygwin
  precedent: input that already contains U+F0XX characters decodes back to
  their low-byte originals.
- **`badPathString` recovers the original input**: the designated decoder
  and display form — decode is `U+F0XX → 0xXX` for low bytes in the escape
  alphabet. Named for what it returns: the full originally-specified path
  string (slashes, drive colons and all), not merely a basename — the
  encoded payload happens to be one name element, but the decoded string
  can be an entire path.

  ```scala
  def badPathString: String =
    if p.isBadPath then puaDecode(p.getFileName.toString)
    else p.posx   // ordinary paths degrade to their posix string: total
  ```

  The diagnostic idiom is `println(s"bad path [${p.badPathString}]")`,
  which prints the originally specified string exactly.
- **Component-based recognition survives normalization**: `isBadPath` tests
  that the path has exactly two name elements and the first equals
  `__uni-BadPath__`. `normalize`, `absPath`, and `resolve` cannot destroy a
  name element, and collision with real files requires a directory literally
  named `__uni-BadPath__`.
- **Boundary**: `uni.Paths.get` and every extension that delegates to it
  (`.asPath`, `.toFile`) are total, identically — `asPath` is a one-line
  delegation and client code assumes the two are interchangeable, so their
  semantics must never diverge. The loud alternative is
  `java.nio.file.Paths.get`, which uni does not touch.
- **Rooted on a nonexistent drive: a filesystem-level write guard.** On
  Windows the sentinel roots at a drive letter chosen per construction from
  the complement of `Internals.rootDrives` (scanning from `Z` downward —
  avoiding `A:`/`B:` legacy floppy handling), e.g.
  `Q:/__uni-BadPath__/<payload>`. With no such drive, *every* create —
  `Files.write`, `Files.createFile`, `Files.copy`, even
  `Files.createDirectories` — fails at the driver level; there is nothing a
  client could pre-create to defeat it. Safety notes: construction is a
  pure string parse (already absolute, so no `toAbsolutePath`, no
  `GetFullPathName`, no drive-cwd consult); mapped-but-disconnected network
  drives still appear in the `GetLogicalDrives` bitmask, so complement
  letters are genuinely unmapped and raw probes fail fast rather than
  hanging; recognition never depends on the letter (the marker component is
  the test), so hot-plug changing the chosen letter between constructions
  is harmless. The `IOException` from attempted I/O is compatible with the
  never-throw rule, which governs construction and queries, not I/O.
  Layered fallbacks: if a drive later materializes at a chosen letter, the
  never-created marker directory (a hard invariant) is the backstop; if all
  26 letters exist, fall back to current-drive rooting where the marker
  invariant alone holds. On Linux/macOS there are no drive letters —
  `/__uni-BadPath__/` plus the marker invariant is the whole story. uni's
  own `exists`/`isFile`/`isDirectory` extensions short-circuit on
  `isBadPath` to false with zero OS contact, so the canonical idiom never
  pays even the fast-fail probe.

## Rendering namespaces (decided 2026-08-11)

MSYS2 shows the same file two ways: the posix world (`ls`, `cygpath -u`)
decodes PUA back to the original characters; the windows world
(`cygpath -m`/`-w`, the on-disk name) shows the raw lookalikes. uni's
renderings now follow the same split — for **BadPath family members only**:
`posix`/`stdpath`/`relpath` decode the payload, `posx`/`localpath`/`dospath`
stay raw. Narrow by design: a real file whose name genuinely holds PUA
characters keeps raw renderings everywhere, because rendering strings get
handed to Windows programs and the raw form is the one that works there — a
BadPath has no working consumers to break. Aligning *real* PUA-named files
with `ls` is deferred to the parse-side question below (cygwin-style
re-encoding), where render and parse can change together instead of opening a
one-way render→parse trapdoor.

## Long-term: a uni FileSystemProvider

The NIO.2 provider mechanism (precedent: the JDK's zipfs, Google's jimfs) is
the only way to intercept `Files.*` without caller ceremony: every static
`Files` method dispatches through `path.getFileSystem().provider()`, so a
`UniPath` backed by a uni provider gets custom behavior from unmodified
client code — `Files.write(badPath, ...)` can *refuse*, which the ordinary-
Path family cannot do.

What a provider could unlock beyond BadPath refusal:

- **Cygwin colon interop**: transparently map `:` in filenames to the U+F03A
  private-use lookalike that MSYS2 writes, restoring round-trips for files
  cygwin created.
- **Per-drive cwd contexts**: honor the `=X:` hidden environment variables
  (the Windows 26-slot drive-cwd model) without touching process state.
- **Access-time platform contexts**: a simulated-platform filesystem for the
  test harness, replacing the string-layer/host-bound-tail split with real
  `Path` objects under foreign rules.

Known seams, none fatal but all real:

- `compareTo` across providers throws `ClassCastException` *by spec* —
  sorting a mixed `List[Path]` explodes. Mitigation: uni-supplied ordering.
- `resolve`/`relativize` across providers throw `ProviderMismatchException`.
  Mitigation: uni extension methods convert at the boundary.
- `java.io.File` does not dispatch through providers; `path.toFile` on a
  non-default provider throws `UnsupportedOperationException`. Since `toFile`
  on strings/paths is a *uni extension*, uni can convert to an ordinary
  marker path first — the leak is confined to third-party code calling
  `.toFile` on a `Path` uni returned.
- Effort class: jimfs-scale — a real project, not a patch.

## Convergence with the Rust port

Rust's `UPath` is already a custom type wrapping resolution logic — the Rust
port has the provider architecture today. A Scala provider would converge the
two implementations instead of the current asymmetry (Scala: string rewriting
in front of a foreign type; Rust: owned type).

## Staged plan

1. **Now (0.16.x)**: ordinary-Path BadPath family. The marker, escape
   alphabet, and recognition predicate are provider-forward: a later
   `UniPath` keeps the same marker and semantics, so nothing is thrown away.
2. **Later, if justified**: provider under the same public API. Extensions
   (`asPath`, `paths.get`) switch construction; recognition and diagnostics
   carry over verbatim.

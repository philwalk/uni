package uni

import java.nio.file.{Path, Paths as JPaths}

/** The BadPath family: total construction for path strings the host filesystem
 *  cannot represent.
 *
 *  `uni.Paths.get` (and therefore `.asPath`, a one-line delegation that must
 *  never diverge from it) returns a member of this family instead of throwing
 *  when given input the host's path parser rejects. A family member is an
 *  ordinary `java.nio.file.Path` of shape
 *
 *      Q:/__uni-BadPath__/<PUA-encoded original input>     (Windows)
 *        /__uni-BadPath__/<PUA-encoded original input>     (Linux/macOS)
 *
 *  - The drive letter is chosen per construction from the letters absent from
 *    `Internals.rootDrives`, scanning Z down to C (A:/B: get legacy floppy
 *    treatment). A nonexistent drive makes every create -- `Files.write`,
 *    `createFile`, even `createDirectories` -- fail at the driver level, so no
 *    stray file can appear even through JDK calls outside uni's control.
 *    Mapped-but-disconnected network drives still occupy their letter in the
 *    GetLogicalDrives bitmask, so a complement letter is genuinely unmapped:
 *    raw probes fail fast rather than hanging. If all letters are taken
 *    (rare), the path roots on the current drive and the never-created marker
 *    directory is the remaining guard -- creating that directory is the one
 *    way a user can opt out of the guarantee.
 *  - Construction is a pure string parse. The result is already absolute (or
 *    current-drive rooted), so neither construction nor later `toAbsolutePath`
 *    consults GetFullPathName or per-drive cwd state.
 *  - The payload uses the cygwin/MSYS2 on-disk convention: each rejected
 *    character maps to its Unicode Private Use Area counterpart at
 *    `U+F000 + char` (`:` -> U+F03A, `<` -> U+F03C, ...), plus one uni
 *    extension: `/` -> U+F02F, so the entire original string is a single name
 *    element and leading/doubled/trailing slashes survive the round trip.
 *    `Path.badPathString` decodes the payload back exactly. Known ambiguity,
 *    accepted with cygwin precedent: input that already contains U+F0xx
 *    characters decodes to their low-byte originals.
 *
 *  Recognition is structural (`Path.isBadPath`): exactly two name elements
 *  with the first equal to `Marker`. That survives `normalize` and
 *  `toAbsolutePath` and is independent of which drive letter was chosen.
 *
 *  The loud alternative remains `java.nio.file.Paths.get`, which uni does not
 *  touch. Design rationale and lineage: docs/PathProviderDesignNote.md.
 */
private[uni] object BadPath:

  val Marker = "__uni-BadPath__"

  private val hostIsWin: Boolean = scala.util.Properties.isWin

  /** True when the HOST path parser would reject `s` -- the membership
   *  predicate for the family. Host rules rather than `config` rules: this
   *  guards the final `JPaths.get`, which always speaks the running JVM's
   *  dialect (see the TEST-HARNESS BOUNDARY note in Paths.scala).
   */
  def isUnrepresentable(s: String): Boolean =
    if hostIsWin then
      (0 until s.length).exists { i =>
        val c = s.charAt(i)
        c < 0x20
        || c == '"' || c == '*' || c == '<' || c == '>' || c == '?' || c == '|'
        || (c == ':' && !(i == 1 && isAsciiLetter(s.charAt(0))))
      }
    else
      s.indexOf('\u0000') >= 0

  private def isAsciiLetter(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')

  /** Build the family member carrying `input` -- the raw string as the caller
   *  supplied it, never an absolutised or mount-rewritten form.
   */
  def apply(input: String): Path =
    JPaths.get(s"$badRoot/$Marker/${encode(input)}")

  // Chosen fresh per call: `rootDrives` is a cheap GetLogicalDrives bitmask
  // read, hot-plug can change the free set between calls, and recognition
  // never depends on the letter chosen.
  private def badRoot: String =
    if !hostIsWin then ""
    else
      val taken = Internals.rootDrives.map(_.head.toUpper).toSet
      "ZYXWVUTSRQPONMLKJIHGFEDC".find(letter => !taken.contains(letter)) match
        case Some(letter) => s"$letter:"
        case None         => "" // all letters in use: current-drive rooting

  private inline val PuaBase = 0xF000

  // The cygwin alphabet (characters Windows rejects in names) plus '/'.
  // Backslash is included: once a string is unrepresentable the whole of it is
  // opaque data, and encoding the backslash keeps the round trip byte-exact.
  private def mapsToPua(c: Char): Boolean =
    c < 0x20 || (c match
      case '"' | '*' | ':' | '<' | '>' | '?' | '|' | '/' | '\\' => true
      case _                                                    => false)

  def encode(s: String): String =
    s.map(c => if mapsToPua(c) then (c + PuaBase).toChar else c)

  def decode(s: String): String =
    s.map { c =>
      val low = (c - PuaBase).toChar
      if c >= PuaBase && c < PuaBase + 0x100 && mapsToPua(low) then low else c
    }

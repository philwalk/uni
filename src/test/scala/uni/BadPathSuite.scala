package uni

import munit.FunSuite
import java.nio.file.Files

/** The BadPath family: total `uni.Paths.get` / `.asPath` for strings the host
 *  filesystem cannot represent. Design: docs/PathProviderDesignNote.md
 *
 *  Membership follows HOST parser rules (the predicate guards the final
 *  `JPaths.get`, which speaks the running JVM's dialect), so the Windows-only
 *  inputs are exercised only on a Windows host; the NUL byte is the one string
 *  every host rejects and keeps the suite meaningful cross-platform.
 */
class BadPathSuite extends FunSuite:

  // strings the Windows parser rejects; on posix hosts these are ordinary names
  private val winBad = Seq(
    "a:b:c",
    "q:pics:x",
    "1:foo",
    ":",
    "::",
    "a<b",
    "a>b",
    "wild*card",
    "quo\"te",
    "pi|pe",
    "what?",
    "tab\tchar",
    "C:/ok/until:here",
    "/lead<ing",
    "//doubled//slash<es//",
    "trail<ing/",
    "back\\slash<mix",
  )

  private val nulBad = "nul\u0000byte"

  private def hostBad: Seq[String] =
    if isWin then winBad :+ nulBad else Seq(nulBad)

  test("asPath is total: no unrepresentable input throws") {
    for s <- hostBad do
      val p = s.asPath // must not throw
      assert(p.isBadPath, s"expected BadPath for [$s], got [$p]")
  }

  test("uni.Paths.get and .asPath agree exactly on bad input") {
    for s <- hostBad do
      assertEquals(Paths.get(s), s.asPath)
  }

  test("badPathString round-trips the exact original input") {
    for s <- hostBad do
      assertEquals(s.asPath.badPathString, s)
  }

  test("BadPath members answer false to exists/isFile/isDirectory") {
    for s <- hostBad do
      val p = s.asPath
      assert(!p.exists, s"exists must be false: [$s]")
      assert(!p.isFile, s"isFile must be false: [$s]")
      assert(!p.isDirectory, s"isDirectory must be false: [$s]")
  }

  test("mkdirs refuses to create a BadPath") {
    for s <- hostBad do
      assert(!s.asPath.mkdirs, s"mkdirs must refuse: [$s]")
  }

  test("recognition and recovery survive normalize and toAbsolutePath") {
    for s <- hostBad do
      val p = s.asPath
      assert(p.normalize.isBadPath, s"normalize broke recognition: [$s]")
      // already absolute (or current-drive rooted), so no drive-cwd probe here
      assert(p.toAbsolutePath.isBadPath, s"toAbsolutePath broke recognition: [$s]")
      assertEquals(p.toAbsolutePath.badPathString, s)
  }

  test("Windows: BadPath roots on a drive letter absent from rootDrives") {
    if isWin then
      val p = "a:b:c".asPath
      assert(p.isAbsolute, s"expected an absolute BadPath: $p")
      val letter = p.getRoot.toString.take(1).toUpperCase
      // with (nearly) all 26 letters in use the fallback rooting is legitimate
      if Internals.rootDrives.length < 24 then
        assert(
          !Internals.rootDrives.contains(s"$letter:"),
          s"BadPath rooted on an existing drive: $p (roots: ${Internals.rootDrives.mkString(",")})"
        )
  }

  test("Files.write on a BadPath cannot create a file") {
    val p = hostBad.head.asPath
    // absent drive (Windows) / missing marker directory (posix): the parent can
    // never exist, so even raw JDK writes fail rather than creating anything
    intercept[java.io.IOException] {
      Files.write(p, "never".getBytes("UTF-8"))
    }
    assert(!Files.exists(p))
  }

  test("ordinary paths are untouched") {
    val p = "build.sbt".asPath
    assert(!p.isBadPath)
    assertEquals(p.badPathString, p.posx)
    assert(p.isFile, s"build.sbt should exist through the guarded isFile: $p")
  }

  test("posix renderings decode family members; windows renderings stay raw") {
    // The MSYS2 model: the posix world (ls, cygpath -u) shows decoded names,
    // the windows world (cygpath -m/-w) shows the on-disk PUA form. NARROW: only
    // family members decode -- a real PUA-named file keeps raw renderings, so
    // strings handed to Windows programs keep working.
    if isWin then
      val p = "a:b:c".asPath
      assert(p.posix.endsWith("/a:b:c"), s"posix should decode: ${p.posix}")
      assert(p.stdpath.endsWith("/a:b:c"), s"stdpath should decode: ${p.stdpath}")
      assert(p.relpath.endsWith("/a:b:c"), s"relpath should decode: ${p.relpath}")
      assert(p.posx.contains(''), s"posx must stay raw: ${p.posx}")
      val real = "dir/fd".asPath // ordinary path, PUA is genuine content
      assert(!real.isBadPath)
      assert(real.posix.contains('') && !real.posix.contains(':'),
        s"real PUA names stay raw in posix renderings: ${real.posix}")
  }

  test("legal input containing PUA characters is not a BadPath") {
    // U+F03A is a legal filename character everywhere; only genuinely
    // unrepresentable strings enter the family
    assert(!"a\uF03Ab".asPath.isBadPath)
  }

  test("PUA lookalikes inside bad input: documented cygwin-precedent ambiguity") {
    if isWin then
      // genuinely bad ('<') AND already containing U+F03A: decode cannot tell
      // the pre-existing lookalike from an encoded colon, so both come back ':'
      assertEquals("x<y\uF03Az".asPath.badPathString, "x<y:z")
  }

  test("encode/decode are exact inverses over the escape alphabet") {
    val alphabet = "\"*:<>?|/" + "\\" + (0 until 0x20).map(_.toChar).mkString
    assertEquals(BadPath.decode(BadPath.encode(alphabet)), alphabet)
    assertEquals(BadPath.encode("plain-name_1.txt"), "plain-name_1.txt")
  }

  test("malformed file:// URI string is total via BadPath") {
    val s = "file://bad uri" // space: URI.create throws IllegalArgumentException
    val p = Paths.get(s)
    assert(p.isBadPath, s"expected BadPath for malformed URI [$s], got [$p]")
    assertEquals(p.badPathString, s)
  }

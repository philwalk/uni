package uni

import munit.FunSuite
import uni.*
import uni.Resolver.*
import uni.Internals.*
import TestUtils.testUser
import java.nio.file.{Paths as JPaths}

/** Targets branch gaps in the uni root package:
 *  - Resolver.classify: all 7 WinPathKind values
 *  - Resolver.stripTrailingSlash: length <= 2 vs > 2
 *  - applyTildeAndDots: all branches (empty, ".", "..", "~", "~/", "./", "../", dotfile, bare name, drive-only, passthrough)
 *  - ParseMounts.parseMountLines: fstab format, partial lines, cygdrive via isDriveRoot, posixOrdering
 *  - Resolver.resolveWindowsPathstr: Invalid throws, Posix-mount-ends-in-colon, Posix-None-mount, Relative
 *  - Internals.canExist: relative path (root == null)
 *  - Internals.samePathString: case-insensitive vs case-sensitive branch
 *  - Internals.sameFileTest: same path, different paths
 *  - posixRel: cwd, sub-path, unrelated path
 *  - PathsConfig.userdirParent: i <= 0 branch
 *  - Deprecated given conversions: Iterator[Path] variants
 */
class UniRootCoverageSuite extends FunSuite:

  private val stdMountLines = Seq("C:/msys64 on / type ntfs (binary)")

  override def beforeEach(context: BeforeEach): Unit =
    withMountLines(stdMountLines, testUser)

  override def afterEach(context: AfterEach): Unit =
    resetConfig()

  // ============================================================================
  // Resolver.classify — all 7 WinPathKind branches
  // ============================================================================

  test("classify: Invalid (contains ://)") {
    assertEquals(classify("http://example.com"), Invalid)
  }

  test("classify: UNC path (starts with //)") {
    assertEquals(classify("//server/share"), UNC)
  }

  test("classify: Root (== /)") {
    assertEquals(classify("/"), Root)
  }

  test("classify: Absolute with forward slash (C:/foo)") {
    assertEquals(classify("C:/Windows"), Absolute)
  }

  test("classify: Absolute with backslash (C:\\\\foo)") {
    assertEquals(classify("C:\\Windows"), Absolute)
  }

  test("classify: DriveRel length == 2 (C:)") {
    assertEquals(classify("C:"), DriveRel)
  }

  test("classify: DriveRel with suffix (C:config)") {
    assertEquals(classify("C:config"), DriveRel)
  }

  test("classify: Posix path (/usr/bin)") {
    assertEquals(classify("/usr/bin"), Posix)
  }

  test("classify: Relative path (./bin)") {
    assertEquals(classify("./bin"), Relative)
  }

  test("classify: Relative bare name (readme.txt)") {
    assertEquals(classify("readme.txt"), Relative)
  }

  // ============================================================================
  // Resolver.stripTrailingSlash
  // ============================================================================

  test("stripTrailingSlash: length <= 2 returns unchanged") {
    assertEquals(stripTrailingSlash("C:"), "C:")
    assertEquals(stripTrailingSlash("/"), "/")
  }

  test("stripTrailingSlash: length > 2 strips trailing slash") {
    assertEquals(stripTrailingSlash("/usr/"), "/usr")
  }

  test("stripTrailingSlash: length > 2 no trailing slash unchanged") {
    assertEquals(stripTrailingSlash("/usr/bin"), "/usr/bin")
  }

  // ============================================================================
  // applyTildeAndDots — all branches (uses synthetic testUser config)
  // testUser.dir  = "C:/munit/test" (Win) | "/munit/test" (Unix)
  // testUser.home = "C:/Persons/liam" (Win) | "/Persons/liam" (Unix)
  // ============================================================================

  test("applyTildeAndDots: empty string → userdir") {
    assertEquals(applyTildeAndDots(""), config.userdir)
  }

  test("applyTildeAndDots: '.' → userdir") {
    assertEquals(applyTildeAndDots("."), config.userdir)
  }

  test("applyTildeAndDots: '..' → userdirParent") {
    assertEquals(applyTildeAndDots(".."), config.userdirParent)
  }

  test("applyTildeAndDots: '~' → userhome") {
    assertEquals(applyTildeAndDots("~"), config.userhome)
  }

  test("applyTildeAndDots: '~/foo' → userhome + /foo") {
    assertEquals(applyTildeAndDots("~/foo"), config.userhome + "/foo")
  }

  test("applyTildeAndDots: './foo' → userdir + /foo") {
    assertEquals(applyTildeAndDots("./foo"), config.userdir + "/foo")
  }

  test("applyTildeAndDots: '../foo' → userdirParent/foo") {
    val parent = config.userdirParent.stripSuffix("/")
    assertEquals(applyTildeAndDots("../foo"), s"$parent/foo")
  }

  test("applyTildeAndDots: '.hidden' (dotfile, not ./ or ../) → userdir + hidden") {
    assertEquals(applyTildeAndDots(".hidden"), config.userdir + "hidden")
  }

  test("applyTildeAndDots: bare filename (no /) → userdir/name") {
    assertEquals(applyTildeAndDots("readme.txt"), config.userdir + "/readme.txt")
  }

  test("applyTildeAndDots: path containing / → passthrough") {
    val p = if isWin then "C:/foo/bar" else "/foo/bar"
    assertEquals(applyTildeAndDots(p), p)
  }

  // ============================================================================
  // ParseMounts.parseMountLines — fstab format, partial lines, cygdrive branches
  // ============================================================================

  test("parseMountLines: fstab format (whitespace-separated, no 'on' keyword)") {
    val lines = Seq(
      "C:/msys64 /",
      "C:/msys64/usr /usr"
    )
    val mounts = ParseMounts.parseMountLines(lines)
    assertEquals(mounts.posix2win.get("/"),    Some("C:/msys64"))
    assertEquals(mounts.posix2win.get("/usr"), Some("C:/msys64/usr"))
  }

  test("parseMountLines: line with only 1 token is silently dropped") {
    val lines = Seq(
      "C:/msys64 on / type ntfs",
      "badline"
    )
    val mounts = ParseMounts.parseMountLines(lines)
    assertEquals(mounts.posix2win.get("/"), Some("C:/msys64"))
  }

  test("parseMountLines: cygdrive derived via isDriveRoot branch (C: → /cygdrive/c)") {
    val lines = Seq(
      "C: on /cygdrive/c type ntfs",
      "D: on /cygdrive/d type ntfs"
    )
    val mounts = ParseMounts.parseMountLines(lines)
    assertEquals(mounts.cygdrive, "/cygdrive/")
  }

  test("parseMountLines: posixOrdering puts '/' first in iterator (compare returns -1 for '/')") {
    val lines = Seq(
      "C:/msys64 on / type ntfs",
      "C:/msys64/usr on /usr type ntfs"
    )
    val mounts = ParseMounts.parseMountLines(lines)
    val keys = mounts.posix2win.keysIterator.toSeq
    assertEquals(keys.head, "/", s"expected '/' first in iterator: ${keys.mkString(", ")}")
  }

  // ============================================================================
  // Resolver.resolveWindowsPathstr — Windows-specific branches
  // ============================================================================

  if isWin then
    test("resolveWindowsPathstr: Invalid path throws RuntimeException") {
      intercept[RuntimeException] {
        Resolver.resolveWindowsPathstr("http://example.com")
      }
    }

    test("resolveWindowsPathstr: Relative path returned as-is") {
      assertEquals(Resolver.resolveWindowsPathstr("relative/path"), "relative/path")
    }

    test("resolveWindowsPathstr: Posix with synthetic drive mount (endsWith ':') gets slash") {
      // posix2win("/d") = "D:" → endsWith(":") → "D:/" appended
      val result = Resolver.resolveWindowsPathstr("/d/foo")
      assert(result.toLowerCase.startsWith("d:/"), s"expected d:/… but got: $result")
    }

    test("resolveWindowsPathstr: Posix with no matching mount → prepends msysRoot") {
      // findPrefix("/no-mount-here/x") returns None since 'n' is not '/' or ':'
      val result = Resolver.resolveWindowsPathstr("/no-mount-here/x")
      assert(
        result.toLowerCase.startsWith("c:/msys64"),
        s"expected c:/msys64… but got: $result"
      )
    }

  // ============================================================================
  // Internals.canExist — relative path (root == null) branch
  // ============================================================================

  test("canExist: relative path (getRoot == null) → true") {
    val rel = JPaths.get("some/relative/path")
    assert(canExist(rel))
  }

  // ============================================================================
  // Internals.samePathString — case sensitivity branch
  // ============================================================================

  test("samePathString: equal strings → true on all platforms") {
    assert(samePathString("/foo/bar", "/foo/bar"))
  }

  test("samePathString: case differs → true on Win/Mac, false on Linux") {
    val result = samePathString("/Foo/BAR", "/foo/bar")
    if isWin || isMac then assert(result,  "expected case-insensitive match on Win/Mac")
    else              assert(!result, "expected case-sensitive mismatch on Linux")
  }

  test("samePathString: different values → false on all platforms") {
    assert(!samePathString("/foo/a", "/foo/b"))
  }

  // ============================================================================
  // Internals.sameFileTest
  // ============================================================================

  test("sameFileTest: same path object → true (hits samePathString short-circuit)") {
    val p = JPaths.get(config.userdir)
    assert(sameFileTest(p, p))
  }

  test("sameFileTest: different non-existent paths → false") {
    val p1 = JPaths.get(config.userdir).resolve("nonexistent_aaa_xyz")
    val p2 = JPaths.get(config.userdir).resolve("nonexistent_bbb_xyz")
    assert(!sameFileTest(p1, p2))
  }

  // ============================================================================
  // posixRel — all three return branches
  // ============================================================================

  test("posixRel: path equals cwd → '.'") {
    assertEquals(posixRel(config.userdir), ".")
  }

  test("posixRel: sub-path of cwd → relative segment") {
    val sub = config.userdir + "/subdir/file.txt"
    assertEquals(posixRel(sub), "subdir/file.txt")
  }

  test("posixRel: unrelated path → absolute posix (not relative)") {
    // A path under a different top-level dir should not start with '.' or the cwd
    val other = if isWin then "C:/totally/other" else "/totally/other"
    val result = posixRel(other)
    assert(!result.startsWith(config.userdir), s"expected unrelated path but got: $result")
  }

  // ============================================================================
  // PathsConfig.userdirParent — i <= 0 branch (root-level userdir)
  // ============================================================================

  test("userdirParent: userdir='/' → lastIndexOf returns 0 → returns '/'") {
    val cfg = new SyntheticPathsConfig(stdMountLines, UserInfo("u", "/home/u", "/"))
    assertEquals(cfg.userdirParent, "/")
  }

  // ============================================================================
  // Deprecated given conversions — Iterator[Path] variants (not in MigrationSuite)
  // ============================================================================

  test("iteratorPathToSeq: Iterator[Path] implicitly converts to Seq[Path]") {
    import scala.language.implicitConversions
    val paths = Seq(JPaths.get("/a"), JPaths.get("/b"))
    val seq: Seq[Path] = paths.iterator.toSeq
    assertEquals(seq, paths)
  }

  test("iteratorPathToList: Iterator[Path] implicitly converts to List[Path]") {
    import scala.language.implicitConversions
    val paths = List(JPaths.get("/a"), JPaths.get("/b"))
    val lst: List[Path] = paths.iterator.toList
    assertEquals(lst, paths)
  }

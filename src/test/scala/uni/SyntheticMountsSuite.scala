package uni

import munit.*
import uni.*
import uni.fs.*
import TestUtils.testUser

final class SyntheticMountsSuite extends FunSuite {
// uncomment the next 2 lines to disable timeout
// import scala.concurrent.duration.*
// override def munitTimeout: Duration = Duration.Inf

  override def beforeAll(): Unit = uni.resetConfig()

  override def afterEach(context: AfterEach): Unit =
    resetConfig()

  if isWin then
    // Minimal root mount
    test("synthetic: root mount resolves /usr/bin correctly") {
      withMountLines(Seq(
        "C:/msys64 on / type ntfs (binary)"
      ), testUser)

      val pathstr = "/usr/bin/bash"
      val p = Paths.get(pathstr)
      val lcposix = p.posx
      val expect = if isWin then
        "C:/msys64/usr/bin/bash"
      else
        pathstr

      assertEqualsIgnoreCase(
        lcposix,
        expect,
        clues(
          s"posix = ${lcposix}\n" +
          s"expect = ${p.posx}"
        )
      )
    }

    // drive mounts (/c, /d)
    test("synthetic: drive mounts /c and /d resolve to correct Windows roots") {
      withMountLines(Seq(
        "C:/msys64 on / type ntfs (binary)",
        "C:/ on /c type ntfs (binary)",
        "D:/ on /d type ntfs (binary)"
      ), testUser)

      assertEqualsIgnoreCase(Paths.get("/c/Users").posx, "C:/users")
      assertEqualsIgnoreCase(Paths.get("/d/tmp").posx, "D:/tmp")
    }

    // longest-prefix-wins: /usr vs /usr/local
    test("synthetic: longest-prefix-wins for /usr vs /usr/local") {
      withMountLines(Seq(
        "C:/msys64 on / type ntfs (binary)",
        "C:/msys64/usr on /usr type ntfs (binary)",
        "C:/msys64/usr/local on /usr/local type ntfs (binary)"
      ), testUser)
      val pstr1 = Paths.get("/usr/bin").posx
      val pstr2 = Paths.get("/usr/local/bin").posx
      assertEqualsIgnoreCase(pstr1, "C:/msys64/usr/bin")
      assertEqualsIgnoreCase(pstr2, "C:/msys64/usr/local/bin")
    }

    // drive-relative POSIX paths (/Users)
    test("synthetic: /Users treated as drive-relative when no mount exists") {
      withMountLines(Seq(
        "C:/msys64 on / type ntfs (binary)"
      ), testUser)

      val p = Paths.get("/Users")
      assert(
        p.posx.toLowerCase.endsWith("/users"),
        clues(s"stdpath = ${p.posx}")
      )
    }

    // UNC mount support
    test("synthetic: UNC mount resolves correctly") {
      withMountLines(Seq(
        """\\server\share on /mnt/share type smbfs (binary)"""
      ), testUser)

      val p = Paths.get("/mnt/share/docs")
      assertEqualsIgnoreCase(
        p.posx,
        """//server/share/docs"""
      )
    }

    // mixed mounts: root + drive + custom
    test("synthetic: mixed mounts resolve correctly") {
      withMountLines(Seq(
        "C:/msys64 on / type ntfs (binary)",
        "C:/ on /c type ntfs (binary)",
        "D:/data on /data type ntfs (binary)"
      ), testUser)

      assert(Paths.get("/bin/bash").posx.toLowerCase.startsWith("c:/msys64"))
      assertEqualsIgnoreCase(Paths.get("/c/Windows").posx, "C:/Windows")
      assertEqualsIgnoreCase(Paths.get("/data/projects").posx, "D:/data/projects")
    }

    // URI resolution
    test("synthetic: URI resolution uses synthetic mount map") {
      withMountLines(Seq(
        "C:/msys64 on / type ntfs (binary)"
      ), testUser)

      val uri = new java.net.URI("file:///usr/bin/bash")
      val p = Paths.get(uri)

      assertEqualsIgnoreCase(
        p.posx,
        "C:/msys64/usr/bin/bash",
        clues(s"posix = ${p.posx}")
      )
    }

    // cygdrive extraction
    test("synthetic: cygdrive extracted correctly") {
      withMountLines(Seq(
        "C:/msys64 on / type ntfs (binary)",
        "C:/msys64/usr/bin on /usr/bin type ntfs (binary)"
      ), testUser)
      assertEqualsIgnoreCase(config.cygdrive, "/")
    }

    // prefix ordering: ensure posix2winKeys sorted longest-first
    test("synthetic: posix2winKeys sorted longest-first") {
      withMountLines(Seq(
        "C:/msys64 on / type ntfs (binary)",
        "C:/msys64/usr on /usr type ntfs (binary)",
        "C:/msys64/usr/local on /usr/local type ntfs (binary)"
      ), testUser)

      val keys = config.posix2winKeys.toSeq
      assert(keys.head.length >= keys.last.length)
      assert(keys.head == "/usr/local")
    }

    // resolver must not confuse /bin with /b
    test("synthetic: /b must not shadow /bin") {
      withMountLines(Seq(
        "C:/msys64 on / type ntfs (binary)",
        "C:/msys64/bin on /bin type ntfs (binary)",
        "C:/msys64/b on /b type ntfs (binary)" // the default mapping except when B: is mapped to /b
      ), testUser)

      assertEqualsIgnoreCase(Paths.get("/bin/bash").posx, "C:/msys64/bin/bash")
      val bfoo = Paths.get("/b/foo")
      assertEqualsIgnoreCase(bfoo.posx, "C:/msys64/b/foo")
    }

    // Windows absolute vs drive-relative
    test("synthetic: absolute Windows paths bypass mount table") {
      withMountLines(Seq(
        "C:/msys64 on / type ntfs (binary)"
      ), testUser)

      val p = Paths.get("C:/Windows/System32")
      assertEqualsIgnoreCase(p.posx, "C:/Windows/System32")
    }

    // synthetic round-trip: posix → win → posix
    test("synthetic: round-trip posix→win→posix is stable") {
      withMountLines(Seq(
        "C:/msys64 on / type ntfs (binary)"
      ), testUser)

      val p1 = Paths.get("/usr/bin/bash")
      val p2 = Paths.get(p1.posx)
      val p2stdpath = p2.stdpath
      assertEqualsIgnoreCase(p2stdpath, "/usr/bin/bash")
    }

    test("synthetic drive entries: default prefix '/'") {
      val lines = Seq(
        // no drive mounts, no root mount
        "C:/msys64 on / type ntfs"
      )

      val mounts = ParseMounts.parseMountLines(lines)

      assertEquals(mounts.cygdrive, "/")

      // synthetic drives must exist
      val fwd = mounts.win2posix

      assertEquals(fwd("c:"), Seq("/c"))
      assertEquals(fwd("C:"), Seq("/c"))
      assertEquals(fwd("D:"), Seq("/d"))
      assertEquals(fwd("Z:"), Seq("/z"))

      // reverse map preserves Windows casing

      val rev = mounts.posix2win
      assertEquals(rev("/c"), "C:")
      assertEquals(rev("/z"), "Z:")
    }

    test("synthetic drive entries: custom prefix '/mnt/'") {
      val lines = Seq(
        "none /mnt cygdrive",
        "C: on /mnt/c type ntfs"
      )

      val mounts = ParseMounts.parseMountLines(lines)

      assertEquals(mounts.cygdrive, "/mnt/")

      val fwd = mounts.win2posix
      assertEquals(fwd("c:"), Seq("/mnt/c"))
      assertEquals(fwd("d:"), Seq("/mnt/d"))

      val rev = mounts.posix2win
      assertEquals(rev("/mnt/c"), "C:")
      assertEquals(rev("/mnt/d"), "D:")
    }

    test("synthetic root entry added when missing") {
      val lines = Seq(
        // no root mount, no drive mounts
        "X:/foo on /foo type ntfs"
      )

      val mounts = ParseMounts.parseMountLines(lines)

      // root must be synthesized
      val rev = mounts.posix2win
      assertEquals(rev("/"), "C:/msys64") // default if undefined msys root
    }

    test("existing drive mounts override synthetic ones") {
      val lines = Seq(
        "C: on /c type ntfs",
        "D: on /d type ntfs"
      )

      val mounts = ParseMounts.parseMountLines(lines)

      val fwd = mounts.win2posix

      // real entries win
      assertEquals(fwd("c:"), Seq("/c"))
      assertEquals(fwd("d:"), Seq("/d"))

      // synthetic entries still exist for unmapped drives
      assertEquals(fwd("e:"), Seq("/e"))
      assertEquals(fwd("z:"), Seq("/z"))
    }

    test("posix → win preserves original Windows casing") {
      val lines = Seq(
        "C:/MSYS64 on / type ntfs",
        "F:/Weekly on /weekly type ntfs"
      )

      val mounts = ParseMounts.parseMountLines(lines)
      val rev = mounts.posix2win

      assertEquals(rev("/"), "C:/MSYS64")
      assertEquals(rev("/weekly"), "F:/Weekly")
    }

  /** Case-insensitive equality with optional custom clues.
    * If `extra` is omitted, only the default actual/expected lines appear.
    * If `extra` is provided, ONLY the extra clues appear.
    */
  def assertEqualsIgnoreCase(
      actual: String,
      expected: String,
      extra: => munit.Clue[?] | Null = null
  )(using loc: munit.Location): Unit =

    val clueText =
      if extra == null then
        s"actual   = [$actual]\nexpected = [$expected]"
      else
        String.valueOf(extra.value)

    assert(
      actual.equalsIgnoreCase(expected),
      clues(clueText)
    )

  /** Semantic path comparison:
    * - case-insensitive
    * - compares stdpath, posx, and isSameFile
    */
  def comparePaths(
      p1: Path,
      p2: Path,
      extra: => munit.Clue[?] | Null = null
  )(using loc: munit.Location): Unit =

    val same =
      p1.stdpath.equalsIgnoreCase(p2.stdpath) ||
      p1.posx.equalsIgnoreCase(p2.posx) ||
      p1.isSameFile(p2)

    val clueText =
      if extra == null then
        PathDebug(p1, p2)
      else
        String.valueOf(extra.value)

    assert(same, clues(clueText))

  /** Pretty-printer for debugging path resolution. */
  def PathDebug(p: Path): String =
    f"""|Path Debug:
        |  raw        = ${p.toString}
        |  posx       = ${p.posx}
        |  stdpath    = ${p.stdpath}
        |  dospath    = ${p.dospath}
        |  abs        = ${p.toAbsolutePath}
        |  exists     = ${p.exists}
        |""".stripMargin

  /** Pretty-printer for comparing two paths. */
  def PathDebug(p1: Path, p2: Path): String =
    f"""|Path Comparison:
        |  p1.raw     = ${p1.toString}
        |  p1.posx    = ${p1.posx}
        |  p1.stdpath = ${p1.stdpath}
        |  p1.exists  = ${p1.exists}
        |
        |  p2.raw     = ${p2.toString}
        |  p2.posx    = ${p2.posx}
        |  p2.stdpath = ${p2.stdpath}
        |  p2.exists  = ${p2.exists}
        |""".stripMargin

  /** Fluent extension methods for strings and paths. */
  extension (actual: String)
    def assertEqIgnoreCase(
        expected: String,
        extra: => munit.Clue[?] | Null = null
    )(using loc: munit.Location): Unit =
      assertEqualsIgnoreCase(actual, expected, extra)

  extension (p: Path)
    def assertSameAs(
        other: Path,
        extra: => munit.Clue[?] | Null = null
    )(using loc: munit.Location): Unit =
      comparePaths(p, other, extra)

}

package uni

import munit.FunSuite
import TestUtils.testUser

/** Covers public functions in PathsUtils.scala not exercised by other suites:
 *  - winAbsToPosixAbs: drive-letter casing, root path, various letters
 *  - tmpDir: non-empty, forward slashes, absolute
 *  - stringAbs: ~, dotfile, bare name, absolute passthrough
 *  - quikResolve: dotfile, tilde, bare name, absolute passthrough
 *  - applyTildeAndDots: drive-only "C:" branch (Windows only)
 *  - posixAbs: trailing-slash stripping on non-Windows
 */
class PathsUtilsCoverageSuite extends FunSuite:

  // Guaranteed cleanup: an injected synthetic config must not leak into later
  // suites -- since 0.16.0 a relative Paths.get absolutises against config.userdir
  // at construction, so a leak sends other suites' fixtures to C:/munit/test.
  override def afterAll(): Unit = resetConfig()

  private val mountLines = Seq("C:/msys64 on / type ntfs (binary)")

  override def beforeEach(context: BeforeEach): Unit =
    if isWin then withMountLines(mountLines, testUser)
    else         resetConfig()

  override def afterEach(context: AfterEach): Unit =
    resetConfig()

  // ============================================================================
  // winAbsToPosixAbs
  // ============================================================================

  // Never needed a platform gate: `winAbsToPosixAbs` is pure string work with no
  // config and no `isWin` in it. The gate was simply inherited from its
  // neighbours.
  test("winAbsToPosixAbs: uppercase drive letter is lowercased") {
    val result = winAbsToPosixAbs("C:/Windows/System32")
    assert(result.startsWith("/c/"), s"expected /c/… but got: $result")
  }

  test("winAbsToPosixAbs: lowercase drive letter stays lowercase") {
    val result = winAbsToPosixAbs("c:/Windows/System32")
    assert(result.startsWith("/c/"), s"expected /c/… but got: $result")
  }

  test("winAbsToPosixAbs: upper and lower drive letters produce identical result") {
    assertEquals(winAbsToPosixAbs("F:/data/logs"), winAbsToPosixAbs("f:/data/logs"))
  }

  test("winAbsToPosixAbs: drive root C:/ → /c/") {
    val result = winAbsToPosixAbs("C:/")
    assertEquals(result, "/c/")
  }

  test("winAbsToPosixAbs: non-C drive letter (D:)") {
    val result = winAbsToPosixAbs("D:/data")
    assert(result.startsWith("/d/"), s"expected /d/… but got: $result")
  }

  test("winAbsToPosixAbs: deep path preserves segments") {
    val result = winAbsToPosixAbs("C:/Program Files/Git/bin")
    assertEquals(result, "/c/Program Files/Git/bin")
  }

  test("winAbsToPosixAbs: requires drive-letter path — throws on POSIX input") {
    intercept[IllegalArgumentException] {
      winAbsToPosixAbs("/usr/bin")
    }
  }

  // ============================================================================
  // tmpDir
  // ============================================================================

  test("tmpDir: returns non-empty string") {
    assert(tmpDir.nonEmpty, "tmpDir should not be empty")
  }

  test("tmpDir: contains no backslashes") {
    assert(!tmpDir.contains('\\'), s"tmpDir should use forward slashes: $tmpDir")
  }

  test("tmpDir: returns an absolute path") {
    val d = tmpDir
    val isAbsolute = d.startsWith("/") || (d.length >= 2 && d(1) == ':')
    assert(isAbsolute, s"tmpDir should be absolute: $d")
  }

  // ============================================================================
  // stringAbs (wrapper around Resolver.resolvePathstr)
  // ============================================================================

  test("stringAbs: tilde expands to non-empty absolute path") {
    val result = stringAbs("~")
    assert(result.nonEmpty)
    val isAbsolute = result.startsWith("/") || (result.length >= 2 && result(1) == ':')
    assert(isAbsolute, s"stringAbs('~') should be absolute: $result")
  }

  test("stringAbs: dotfile resolves with leading dot preserved") {
    val result = stringAbs(".gitignore")
    assert(result.endsWith("/.gitignore"), s"leading dot should be preserved: $result")
  }

  test("stringAbs: bare filename resolves to absolute path") {
    val result = stringAbs("readme.txt")
    val isAbsolute = result.startsWith("/") || (result.length >= 2 && result(1) == ':')
    assert(isAbsolute, s"stringAbs('readme.txt') should be absolute: $result")
    assert(result.endsWith("/readme.txt"), s"filename should be preserved: $result")
  }

  test("stringAbs: absolute path passes through unchanged") {
    val abs = if isWin then "C:/opt/ue/bin" else "/opt/ue/bin"
    val result = stringAbs(abs)
    assert(result.replace('\\', '/').equalsIgnoreCase(abs.replace('\\', '/')),
      s"absolute path should pass through: $result vs $abs")
  }

  // ============================================================================
  // quikResolve
  // ============================================================================

  test("quikResolve: '.' resolves to existing directory") {
    // Against the DEFAULT config: under the suite's synthetic one, '.' resolves
    // to the fake user dir (C:/munit/test), which only exists on machines where
    // a past leak created it -- green locally, red on every clean CI runner.
    resetConfig()
    val p = quikResolve(".")
    assert(p.toFile.isDirectory, s"quikResolve('.') should point to a directory: $p")
  }

  test("quikResolve: '~' resolves to non-null absolute path") {
    val p = quikResolve("~")
    assert(p.isAbsolute, s"quikResolve('~') should be absolute: $p")
  }

  test("quikResolve: dotfile preserves leading dot in result") {
    val p = quikResolve(".gitignore")
    assert(p.toString.endsWith(".gitignore") || p.getFileName.toString == ".gitignore",
      s"quikResolve('.gitignore') should end with .gitignore: $p")
  }

  test("quikResolve: bare filename resolves to absolute path") {
    val p = quikResolve("build.sbt")
    assert(p.isAbsolute, s"quikResolve('build.sbt') should be absolute: $p")
    assert(p.toString.endsWith("build.sbt"), s"filename should be preserved: $p")
  }

  // ============================================================================
  // applyTildeAndDots: drive-only "C:" branch (Windows only)
  // ============================================================================

  test("applyTildeAndDots: a drive-only path is passed through, not resolved here") {
    // It used to resolve `C:` itself via `driveCwd`, which left a `.` component in
    // the answer, and `C:foo` -- having no '/' -- fell through to the bare-filename
    // branch and became `<userdir>/C:foo`. `classify` owns drive letters now, so
    // both come back untouched and `resolveDriveRelPathstr` does the work.
    //
    // Runs on any host: `config.isWindows` is injected rather than read from
    // `os.name`, so the Windows rule is reachable from Linux and macOS.
    withMountLines(mountLines, TestUtils.windowsTestUser, isWindows = true)
    assertEquals(applyTildeAndDots("C:"), "C:")
    assertEquals(applyTildeAndDots("C:foo"), "C:foo")
    assertEquals(applyTildeAndDots("C:foo/bar"), "C:foo/bar")
  }

  // ============================================================================
  // posixAbs: trailing-slash stripping (non-Windows is the simplest branch)
  // ============================================================================

  if !isWin then
    test("posixAbs: trailing slash is stripped on non-Windows") {
      val result = posixAbs("/usr/bin/")
      assert(!result.endsWith("/"), s"posixAbs should strip trailing slash: $result")
      assertEquals(result, "/usr/bin")
    }

    test("posixAbs: root '/' is returned unchanged") {
      assertEquals(posixAbs("/"), "/")
    }

    test("posixAbs: path without trailing slash unchanged") {
      val result = posixAbs("/usr/bin")
      assertEquals(result, "/usr/bin")
    }

  // ============================================================================
  // standardizePath — mount prefix matching must respect segment boundaries
  // ============================================================================

  // Still Windows-only, and not for want of an injectable flag: `standardizePath`
  // starts from `p.toFile.getAbsolutePath`, so its input is whatever the *host*
  // filesystem says. On Linux `"C:/msys64extra/x"` is a relative name and picks up
  // the real cwd, which no amount of rule injection can change. The Rust port covers
  // the same prefix-boundary rule portably, in `resolve::find_prefix`.
  if isWin then
    test("standardizePath: a mount prefix only matches on a segment boundary") {
      withMountLines(Seq(
        "C:/msys64 on / type ntfs (binary)",
        "C:/ on /c type ntfs (binary)"), testUser)

      // `C:/msys64extra` merely starts with the `c:/msys64` mount as a string; it
      // is not under it. The old scan used a plain startsWith, matched the root
      // mount, and rewrote this to the bare remainder "extra/x" — a relative
      // string standing in for an absolute path. It must fall through to the
      // drive mount instead.
      assertEquals("C:/msys64extra/x".asPath.stdpath, "/c/msys64extra/x")

      // The genuine child still resolves through the root mount.
      assertEquals("C:/msys64/usr/bin".asPath.stdpath, "/usr/bin")
    }

  // ============================================================================
  // pwd must follow the active config
  // ============================================================================

  test("pwd follows an injected config, even after it has already been read") {
    // `pwd` was a top-level `lazy val`, so the first read anywhere in the JVM froze
    // it and no later `withMountLines` was seen. Reading it here *before* injecting
    // is what makes this fail against that version rather than depending on which
    // test happened to run first.
    resetConfig()
    val real = pwd.posx

    withMountLines(Seq("C:/msys64 on / type ntfs (binary)"), testUser)
    assertEquals(pwd.posx, testUser.dir, "pwd should report the injected user's dir")

    resetConfig()
    assertEquals(pwd.posx, real, "and should revert with the config")
  }

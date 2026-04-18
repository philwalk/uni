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

  private val mountLines = Seq("C:/msys64 on / type ntfs (binary)")

  override def beforeEach(context: BeforeEach): Unit =
    if isWin then withMountLines(mountLines, testUser)
    else         resetConfig()

  override def afterEach(context: AfterEach): Unit =
    resetConfig()

  // ============================================================================
  // winAbsToPosixAbs
  // ============================================================================

  if isWin then
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

  if isWin then
    test("applyTildeAndDots: drive-only 'C:' resolves to cwd of that drive") {
      val result = applyTildeAndDots("C:")
      assert(result.nonEmpty, "drive-only path should resolve to something")
      assert(result.startsWith("C:") || result.startsWith("c:") || result.startsWith("/c"),
        s"drive-only 'C:' should resolve to C drive path: $result")
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

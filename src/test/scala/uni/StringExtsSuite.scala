package uni

import munit.FunSuite
import java.io.File as JFile
import java.nio.file.Files
import TestUtils.testUser

/** Covers `stringExts` methods that were not reached by other suites. */
class StringExtsSuite extends FunSuite:

  private val mountLines = Seq("C:/msys64 on / type ntfs (binary)")

  override def beforeEach(context: BeforeEach): Unit =
    if isWin then withMountLines(mountLines, testUser)
    else         resetConfig()

  override def afterEach(context: AfterEach): Unit =
    resetConfig()

  // ============================================================================
  // lc / uc
  // ============================================================================

  test("lc: converts to lowercase") {
    assertEquals("Hello WORLD".lc, "hello world")
  }

  test("uc: converts to uppercase") {
    assertEquals("Hello World".uc, "HELLO WORLD")
  }

  // ============================================================================
  // toFile
  // ============================================================================

  test("toFile: returns JFile for a temp path") {
    val p = Files.createTempFile("stringexts-", ".txt")
    p.toFile.deleteOnExit()
    val f = p.toString.toFile
    assert(f.isInstanceOf[JFile])
    assert(f.exists())
  }

  // ============================================================================
  // absPath
  // ============================================================================

  test("absPath: '.' resolves to absolute path") {
    val p = ".".absPath
    assert(p.isAbsolute, s"absPath('.') should be absolute: $p")
  }

  test("absPath: bare filename resolves absolutely") {
    val p = "build.sbt".absPath
    assert(p.isAbsolute, s"absPath should be absolute: $p")
  }

  // ============================================================================
  // dropSuffix
  // ============================================================================

  test("dropSuffix: strips last extension") {
    assertEquals("foo.bar".dropSuffix, "foo")
  }

  test("dropSuffix: multi-dot keeps all but last extension") {
    assertEquals("archive.tar.gz".dropSuffix, "archive.tar")
  }

  test("dropSuffix: hidden dotfile (dot at index 0) returned unchanged") {
    assertEquals(".gitignore".dropSuffix, ".gitignore")
  }

  test("dropSuffix: no extension returned unchanged") {
    assertEquals("Makefile".dropSuffix, "Makefile")
  }

  // ============================================================================
  // local — non-Windows: always returns str unchanged
  //         Windows: converts POSIX path to Windows path via mount table
  // ============================================================================

  test("local: non-Windows returns string unchanged") {
    if !isWin then
      val s = "/usr/bin/bash"
      assertEquals(s.local, s)
  }

  test("local: Windows POSIX path returns non-empty result") {
    if isWin then
      val result = "/usr/bin".local
      assert(result.nonEmpty, "local should return a non-empty string")
  }

  test("local: Windows non-POSIX string returns unchanged") {
    if isWin then
      val s = "relative/path"
      assertEquals(s.local, s)
  }

  // ============================================================================
  // Paths.get("file://...") — the URI string branch in Paths.get
  // The synthetic mount maps "/usr/bin" → somewhere under C:/msys64 on Windows,
  // and on non-Windows it resolves directly to /usr/bin.
  // The important thing is that the "file://" prefix triggers the URI overload code path.
  // ============================================================================

  test("Paths.get: 'file:///' URI string routes through URI overload and returns absolute path") {
    val uriStr = "file:///usr/bin"
    val p = Paths.get(uriStr)
    assert(p.isAbsolute, s"Paths.get('$uriStr') should return an absolute path: $p")
  }

  // ============================================================================
  // Internals.exists
  // ============================================================================

  test("Internals.exists: existing directory → true") {
    import uni.Internals.exists
    val tmp = Files.createTempDirectory("internals-exists-")
    tmp.toFile.deleteOnExit()
    assert(exists(tmp.toString))
  }

  test("Internals.exists: non-existing path → false") {
    import uni.Internals.exists
    assert(!exists("/nonexistent/internals-xyz-nope"))
  }

  // ============================================================================
  // Internals.safeAbsolutePath
  // ============================================================================

  test("Internals.safeAbsolutePath: regular path → absolute") {
    import uni.Internals.safeAbsolutePath
    import java.nio.file.{Paths as JPaths}
    val p = JPaths.get("build.sbt")
    val abs = safeAbsolutePath(p)
    assert(abs.isAbsolute, s"safeAbsolutePath should be absolute: $abs")
  }

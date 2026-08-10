package uni

import munit.FunSuite
import java.io.File as JFile
import java.nio.file.Files
import TestUtils.testUser

/** Covers `stringExts` methods that were not reached by other suites. */
class StringExtsSuite extends FunSuite:

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

  // `assert(result.nonEmpty)` used to be the whole Windows assertion here, which is
  // why a thoroughly broken conversion survived: it returned `Tmp/x` for `/tmp/x`
  // and `C/Users` for `/c/Users` — non-empty every time. These pin the value.

  test("local: String.local agrees with Path.localpath for POSIX input") {
    // Machine-independent: for a path it actually converts, String.local is the
    // native form of the same conversion, so the two cannot drift apart whatever
    // the mount table says. They differ only for non-POSIX input, where
    // String.local is a deliberate no-op — see the test below.
    for s <- Seq("/usr/bin", "/tmp", "/c/Users") do
      assertEquals(s.local, s.asPath.localpath, s"local mismatch for [$s]")
  }

  test("local: Windows POSIX path converts via the mount table") {
    if isWin then
      withMountLines(Seq(
        "C:/msys64 on / type ntfs (binary)",
        "C:/tmp on /tmp type ntfs (binary)",
        "C:/ on /c type ntfs (binary)"), TestUtils.testUser)
      // A synthetic drive mount `/t` exists for the unmapped T: drive, so the
      // longest-prefix rule is what keeps `/tmp/x` off it.
      assertEquals("/tmp/x".local, """C:\tmp\x""")
      assertEquals("/c/Users".local, """C:\Users""")
      assertEquals("/usr/bin".local, """C:\msys64\usr\bin""")
      resetConfig()
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

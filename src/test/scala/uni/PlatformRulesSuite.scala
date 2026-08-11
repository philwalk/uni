package uni

import munit.FunSuite
import TestUtils.windowsTestUser
import TestUtils.unixTestUser

/**
 * Proves `config.isWindows` selects path *rules* in both directions from a single
 * host, which is what lets the Windows-rule tests run on Linux and macOS.
 *
 * Before this, `isWin` came straight from `scala.util.Properties` (i.e. `os.name`),
 * so 39 tests -- all 17 of `SyntheticMountsSuite` among them -- could only run on
 * Windows, and `PathParitySuite` demanded a separate fixture per platform. The Rust
 * port took `is_windows` as a `PathContext` field from the start, which is why
 * `rust/tests/path_parity.rs` checks the Windows rules from any host; this closes the
 * same gap on the Scala side.
 *
 * Assertions target `Resolver.resolvePathstr` -- the string layer -- rather than the
 * `Path` objects `uni.Paths.get` returns. That is deliberate and it is a real limit:
 * a `java.nio.file.Path` renders through the *host's* `FileSystem`, so its `toString`
 * is backslashed on Windows and slashed on Linux no matter what this flag says. Only
 * the string layer is genuinely platform-independent.
 *
 * `PathExts.localpath` and `StringExts.local` *are* covered here despite starting
 * from a host-rendered string, because `normalizePosix` converts backslashes to
 * slashes before the rule is applied, erasing the host difference first.
 */
class PlatformRulesSuite extends FunSuite:

  // Guaranteed cleanup: an injected synthetic config must not leak into later
  // suites -- since 0.16.0 a relative Paths.get absolutises against config.userdir
  // at construction, so a leak sends other suites' fixtures to C:/munit/test.
  override def afterAll(): Unit = resetConfig()

  override def afterEach(context: AfterEach): Unit = resetConfig()

  private def windowsRules(): Unit =
    withMountLines(Seq("C:/msys64 on / type ntfs (binary)"), windowsTestUser,
      isWindows = true)

  private def posixRules(): Unit =
    withMountLines(Nil, unixTestUser, isWindows = false)

  test("the injected flag actually changes behaviour, not just a field") {
    // The load-bearing assertion: if these ever matched, every test below would be
    // passing for the wrong reason.
    windowsRules()
    val win = Resolver.resolvePathstr("/usr/bin/bash")
    posixRules()
    val posix = Resolver.resolvePathstr("/usr/bin/bash")
    assertNotEquals(win, posix, "isWindows made no difference")
    assertEquals(win, "C:/msys64/usr/bin/bash")
    assertEquals(posix, "/usr/bin/bash")
  }

  test("a mount table is applied under Windows rules on any host") {
    windowsRules()
    assertEquals(Resolver.resolvePathstr("/usr/bin/bash"), "C:/msys64/usr/bin/bash")
    assertEquals(Resolver.resolvePathstr("/"), "C:/msys64")
  }

  test("a mount table is ignored under POSIX rules on any host") {
    // Same table, opposite flag: POSIX paths pass through untranslated.
    withMountLines(Seq("C:/msys64 on / type ntfs (binary)"), unixTestUser,
      isWindows = false)
    assertEquals(Resolver.resolvePathstr("/usr/bin/bash"), "/usr/bin/bash")
  }

  // ---------------------------------------------------------------------------
  // Drive-relative resolution -- the portable half of DriveRelativeSuite, which
  // can only check against the real java.nio on Windows
  // ---------------------------------------------------------------------------

  test("a bare drive resolves to that drive's working directory") {
    windowsRules()
    assertEquals(Resolver.classify("C:"), Resolver.DriveRel)
    assertEquals(Resolver.resolvePathstr("C:"), windowsTestUser.dir)
  }

  test("a single-segment drive-relative path resolves") {
    // The form that used to be mangled to `<userdir>/C:foo` and then rejected by
    // java.nio: having no '/', it was claimed by the bare-filename branch.
    windowsRules()
    assertEquals(Resolver.classify("C:foo"), Resolver.DriveRel)
    assertEquals(Resolver.resolvePathstr("C:foo"), s"${windowsTestUser.dir}/foo")
  }

  test("a multi-segment drive-relative path resolves the same way") {
    windowsRules()
    assertEquals(Resolver.resolvePathstr("C:foo/bar"), s"${windowsTestUser.dir}/foo/bar")
  }

  test("a drive-relative path never keeps a backslash or a dot component") {
    windowsRules()
    for input <- Seq("C:", "C:foo", "C:foo/bar") do
      for out <- Seq(Resolver.resolvePathstr(input), toPosixAbs(input)) do
        assert(!out.contains('\\'), s"$input produced backslashes: $out")
        assert(!out.endsWith("/."), s"$input left a trailing dot: $out")
        assert(!out.contains("/./"), s"$input left an embedded dot: $out")
  }

  test("a drive-absolute path is left alone") {
    windowsRules()
    assertEquals(Resolver.classify("C:/foo"), Resolver.Absolute)
    assertEquals(Resolver.resolvePathstr("C:/foo"), "C:/foo")
  }

  test("off Windows a colon-bearing name is an ordinary relative") {
    // POSIX rules follow the posix host oracle (`java.nio.file.Paths.get` there):
    // a colon is an ordinary character, so `C:foo` absolutises like any other
    // relative. Restored after 0.16.0 briefly passed drive shapes through under
    // both rule sets -- see the branch comment in `applyTildeAndDots`.
    posixRules()
    assertEquals(Resolver.resolvePathstr("C:foo"), s"${unixTestUser.dir}/C:foo")
    assertEquals(Resolver.resolvePathstr("./C:foo"), s"${unixTestUser.dir}/C:foo")
  }

  test("applyTildeAndDots passes drive letters through untouched") {
    // It must not resolve them itself; `classify` owns that decision.
    windowsRules()
    for input <- Seq("C:", "C:foo", "C:foo/bar", "C:/foo") do
      assertEquals(applyTildeAndDots(input), input, s"$input was rewritten")
  }

  // ---------------------------------------------------------------------------
  // The separator-rule methods: PathExts.localpath / dospath, StringExts.local
  // ---------------------------------------------------------------------------
  //
  // These read `config.isWindows` too, which is what makes them testable here. They
  // are portable despite starting from `p.toString` -- a host-rendered string --
  // because `normalizePosix` converts backslashes to slashes first, erasing the
  // difference before the rule is applied.

  // Construction happens once, under Windows rules, where "C:/foo/bar" classifies
  // Absolute and is preserved; only the *rendering* rule is flipped afterwards.
  // Constructing under POSIX rules would be testing the wrong thing since 0.16.0:
  // there a drive-lettered string is an ordinary relative name (the colon has no
  // meaning) and resolves against userdir like any other relative path.

  test("localpath emits the injected platform's separator") {
    windowsRules()
    val p = Paths.get("C:/foo/bar")
    assertEquals(p.localpath, "C:\\foo\\bar")
    posixRules()
    assertEquals(p.localpath, "C:/foo/bar")
  }

  test("posx always emits forward slashes, under either rule set") {
    // The intended split: `posx` is always POSIX, `local` follows the platform.
    windowsRules()
    val p = Paths.get("C:/foo/bar")
    for setup <- Seq(() => windowsRules(), () => posixRules()) do
      setup()
      assertEquals(p.posx, "C:/foo/bar")
  }

  test("String.local translates a POSIX path only under Windows rules") {
    windowsRules()
    assertEquals("/usr/bin".local, "C:\\msys64\\usr\\bin")
    posixRules()
    assertEquals("/usr/bin".local, "/usr/bin")
  }

  test("String.local leaves a non-absolute string alone under either rule set") {
    for setup <- Seq(() => windowsRules(), () => posixRules()) do
      setup()
      assertEquals("foo/bar".local, "foo/bar")
  }

  test("dospath passes a long path through under POSIX rules") {
    // Only this branch is portable: the short-string branches consult `rootDrives`
    // and `toAbsolutePath`, which read the real filesystem.
    windowsRules()
    val p = Paths.get("C:/foo/bar") // constructed where the drive form is Absolute
    posixRules()
    assertEquals(p.dospath, p.toString)
  }


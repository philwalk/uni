package uni

import munit.FunSuite
import java.nio.file.{Files, Paths => JPaths}

/**
 * Drive-relative Windows paths (`C:`, `C:foo`), checked against `java.nio` rather
 * than against hand-written expectations.
 *
 * `java.nio.file.Paths` already resolves these correctly -- it asks Windows for the
 * per-drive working directory, which is what a drive-relative path means -- so it is
 * the reference, and the assertions cannot drift from real Windows behaviour the way
 * a literal expectation would.
 *
 * What was wrong before:
 *   - `Paths.get("C:")` returned `C:\...\uni\.` -- a stray `.` component, because
 *     `driveCwd` built its answer from the string `C:.`
 *   - `Paths.get("C:foo")` threw `InvalidPathException`. `applyTildeAndDots` claimed
 *     the string before `classify` could route it, and since `C:foo` contains no
 *     `/` it was treated as a bare filename and appended to the user directory,
 *     giving `C:/Users/.../uni/C:foo` with a colon buried inside it
 *   - `Paths.get("C:foo/bar")` worked only by accident: containing a `/`, it missed
 *     the bare-filename branch. It still carried the stray `.`
 */
class DriveRelativeSuite extends FunSuite:

  private val driveForms = Seq("C:", "C:foo", "C:foo/bar", "C:/", "C:/foo", "C:/foo/bar")

  test("driveCwd: a drive letter absent from rootDrives answers its root") {
    // The test the git history shows was missing. `DefaultPathsConfig.driveCwd` is
    // the one method the synthetic harness stubs, so no synthetic test can reach
    // it -- and its unguarded JVM probe threw `java.io.IOError` for an absent
    // drive (and can hang on a mapped-but-disconnected network drive). The guard
    // makes the absent-drive branch pure: drive not in `rootDrives` means the
    // `X:/` fallback, no OS touch.
    //
    // Windows-only: the fallback is returned as a host `Path`, and a posix JVM
    // renders `Paths.get("A:/")` as the bare relative name `A:` -- the first
    // Linux run caught this asserting on the host rendering.
    assume(isWin, "driveCwd returns host Paths; drive letters are a Windows-host concept")
    resetConfig()
    val absent = ('A' to 'Z').find(c => !Internals.rootDrives.contains(s"$c:"))
      .getOrElse(fail("all 26 drive letters exist on this machine?"))
    val got = normalizePosix(DefaultPathsConfig.driveCwd(absent).toString)
    assertEquals(got, s"$absent:/")
    // and a drive that certainly exists still resolves through the JVM
    if isWin then
      val sysDrive = realUser.dir.take(1).head.toUpper
      val cwd = DefaultPathsConfig.driveCwd(sysDrive)
      assert(Files.exists(cwd), s"driveCwd($sysDrive) should name a real directory: $cwd")
  }

  test("drive-relative paths resolve the way java.nio resolves them") {
    assume(isWin, "drive letters are a Windows concept")
    resetConfig()
    for s <- driveForms do
      val expected = JPaths.get(s).toAbsolutePath.normalize
      assertEquals(Paths.get(s), expected, s"uni.Paths.get($s)")
  }

  test("a drive-relative path never keeps a dot component") {
    assume(isWin)
    resetConfig()
    for s <- driveForms do
      val posix = Paths.get(s).posx
      assert(!posix.endsWith("/."), s"$s left a trailing dot: $posix")
      assert(!posix.contains("/./"), s"$s left an embedded dot: $posix")
  }

  test("posx never emits a backslash, including for drive-relative input") {
    assume(isWin)
    resetConfig()
    for s <- driveForms do
      assert(!Paths.get(s).posx.contains('\\'), s"$s produced backslashes in posx")
  }

  test("quikResolve agrees with java.nio on drive-relative input") {
    // Shares `applyTildeAndDots`, so it inherited the same defect: `C:foo` threw.
    assume(isWin)
    resetConfig()
    for s <- driveForms do
      assertEquals(quikResolve(s), JPaths.get(s).toAbsolutePath.normalize, s"quikResolve($s)")
  }

  test("a colon-bearing filename stays a filename off Windows") {
    // The reason the new branch is guarded by `isWin`: on Linux and macOS `C:foo`
    // is an ordinary relative filename, not a drive reference.
    assume(!isWin, "posix-only")
    resetConfig()
    assertEquals(Paths.get("C:foo").toString, s"${DefaultPathsConfig.userdir}/C:foo")
  }

  test("driveCwd reports a directory, not a dotted path") {
    assume(isWin)
    resetConfig()
    val cwd = DefaultPathsConfig.driveCwd('c')
    assertEquals(cwd, cwd.normalize, s"driveCwd is not normalized: $cwd")
    assertEquals(cwd, JPaths.get("C:").toAbsolutePath.normalize)
  }

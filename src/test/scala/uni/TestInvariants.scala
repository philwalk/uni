package uni

import munit.FunSuite
import TestUtils.{noisy}

class TestInvariants extends FunSuite {
  // Helpers
  def hereDrive: String =
    if (isWin)
      new java.io.File("/").getAbsolutePath.take(2)
    else
      ""

  def cwd: java.nio.file.Path =
    Paths.get(".").toAbsolutePath.normalize

  def cwdDrive: String =
    if (isWin)
      cwd.getRoot.toString.take(2)
    else
      ""

  // Tests

  test("invariants: hereDrive should match OS expectations") {
    val hd = hereDrive
    noisy(s"hereDrive = [$hd]")

    if (isWin) {
      assert(hd.matches("[A-Za-z]:"), clues(s"hereDrive=$hd"))
    } else {
      assert(hd.isEmpty, clues(s"hereDrive=$hd"))
    }
  }

  test("invariants: cwd should be an absolute path") {
    val c = cwd
    noisy(s"cwd = $c")
    assert(c.isAbsolute, clues(s"cwd=$c"))
  }

  test("invariants: cwd drive should match OS expectations") {
    val drive = cwdDrive
    noisy(s"cwdDrive = [$drive]")

    if (isWin) {
      assert(drive.matches("[A-Za-z]:"), clues(s"cwdDrive=$drive"))
    } else {
      assert(drive.isEmpty, clues(s"cwdDrive=$drive"))
    }
  }

  test("invariants: root path should behave correctly for OS") {
    val root = Paths.get("/").toAbsolutePath.normalize
    noisy(s"root = $root")

    // In all cases, "/" should resolve to some absolute path
    assert(root.isAbsolute, clues(s"root=$root isAbsolute=${root.isAbsolute}"))

    if (!isWin) {
      // On POSIX, "/" is the canonical root
      assertEquals(root.toString, "/", clues(s"root=$root"))
    } else {
      // On Windows, we don't assert a specific shape:
      // it may be a drive root, a mapped root, or something
      // defined by your mount map. We only require it to be absolute.
      ()
    }
  }

  test("invariants: mount map keys should be POSIX-style paths") {
    val mounts = config.posix2win.keySet.toSeq
    noisy(s"mount map keys = $mounts")

    // Only run if mount map exists
    assume(mounts.nonEmpty, "No mount map entries available")

    mounts.foreach { key =>
      assert(
        key.startsWith("/"),
        clues(s"mount key must be POSIX-style: key=$key")
      )
    }
  }

  test("invariants: mount map values should be valid Windows paths") {
    val mounts = config.posix2win.toSeq
    noisy(s"mount map entries = $mounts")

    assume(isWin, "Windows-only test")
    assume(mounts.nonEmpty, "No mount map entries available")

    mounts.foreach { case (posix, win) =>
      val p = java.nio.file.Paths.get(win)

      val isAbsolute = p.isAbsolute
      val isDriveRelative =
        win.matches("^[A-Za-z]:$") // e.g. "C:"

      assert(
        isAbsolute || isDriveRelative,
        clues(s"posix=$posix win=$win absolute=$isAbsolute driveRelative=$isDriveRelative")
      )
    }
  }
}

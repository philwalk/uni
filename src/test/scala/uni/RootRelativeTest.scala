package uni

import munit.FunSuite
import uni.*
import uni.fs.*
import TestUtils.*

class RootRelativeTest extends FunSuite {
  // current working directory is fixed at JVM startup time
  val workingDir = Paths.get(".").toAbsolutePath
  val cwdDrive   = workingDir.getRoot.toString.take(2)

  // ---------------------------------------
  // basic environment info
  // ---------------------------------------
  if isWin then
    test("env: print cwd and drive info") {
      println(s"cwd: $workingDir")
      println(s"cwdDrive: $cwdDrive")
      assert(workingDir.toString.take(2) == cwdDrive)
    }

  // ---------------------------------------
  // Windows root-relative path resolution tests
  // ---------------------------------------
  val testdirs = Seq("/opt", "/OPT", "/$RECYCLE.BIN", "/Program Files", "/etc")

  if isWin then
    testdirs.foreach { testdir =>
      test(s"root-relative: resolve Windows path [$testdir]") {
        val mounts = config.posix2win.keySet.toArray
        val testDirPath = Paths.get(testdir)

        val mounted =
          mounts.find((dir: String) => testDirPath.isSameFile(Paths.get(dir)))

        val thisPath =
          mounted match {
            case Some(str) => config.posix2win(str)
            case None      => testdir
          }

        val jf = Paths.get(thisPath)
        noisy(s"[${jf.posx}]: exists [${jf.exists}]")

        val sameDriveLetter =
          jf.toString.take(2).equalsIgnoreCase(cwdDrive)

        if (mounted.isEmpty && !sameDriveLetter)
          hook += 1

        assert(
          mounted.nonEmpty || sameDriveLetter,
          clues(s"testdir=$testdir thisPath=$thisPath cwdDrive=$cwdDrive")
        )
      }
    }

  // ---------------------------------------
  // mountMap application tests
  // ---------------------------------------
  test("mountMap: print PATH and java.library.path") {
    noisy(envpath)
    noisy(jvmpath)
  }

  if (isWin) {
    val testdirs = Seq("/opt", "/optx")

    testdirs.foreach { dir =>
      test(s"mountMap: mapping correctness for [$dir]") {

        val mounts = config.posix2win.keySet.toArray
        assume(mounts.nonEmpty, "No mount map entries available")

        val dirPath = Paths.get(dir)

        val mounted =
          mounts.find((s: String) => dirPath.isSameFile(Paths.get(s)))

        val thisPath =
          mounted match {
            case Some(str) => config.posix2win(str)
            case None      => dir
          }

        val jf = java.nio.file.Paths.get(thisPath)
        noisy(s"[${jf.toString}]: exists [${jf.toFile.exists}]")

        val testdir = java.nio.file.Paths.get(dir)

        if (mounted.nonEmpty != testdir.toFile.exists)
          hook += 1

        assertEquals(
          mounted.nonEmpty,
          testdir.toFile.exists,
          clues(s"dir=$dir thisPath=$thisPath mounted=$mounted")
        )
      }
    }
  }

  // ---------------------------------------
  // Helpers
  // ---------------------------------------
  def envpath: String = {
    val psep = java.io.File.pathSeparator
    val entries =
      Option(System.getenv("PATH")).getOrElse("")
        .split(psep)
        .map(_.toString)
        .toList

    entries.map(_.replace('\\', '/').toLowerCase).distinct.mkString(";")
  }

  def jvmpath: String = {
    val psep = java.io.File.pathSeparator
    val entries =
      sys.props("java.library.path")
        .split(psep)
        .map(_.toString)
        .toList

    entries.map(_.replace('\\', '/').toLowerCase).distinct.mkString(";")
  }
}

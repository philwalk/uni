package uni

import uni.*
import uni.fs.*
import org.scalatest.BeforeAndAfter
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

private var hook = 0

class RootRelativeTest extends AnyFunSpec with Matchers with BeforeAndAfter {
  val verbose      = Option(System.getenv("VERBOSE_TESTS")).nonEmpty

  // current working directory is fixed at JVM startup time
  val workingDir = Paths.get(".").toAbsolutePath
  val cwdDrive   = workingDir.getRoot.toString.take(2)

  describe("Root-relative paths") {
    printf("cwd: %s\n", workingDir)
    printf("cwdDrive: %s\n", cwdDrive)
    if (cwdDrive.contains(":")) {
      // windows os
      val testdirs = Seq("/opt", "/OPT", "/$RECYCLE.BIN", "/Program Files", "/etc")

      for (testdir <- testdirs) {
        it(s"should correctly resolve Windows rootRelative path [$testdir]") {
          val mounts  = config.posix2win.keySet.toArray
          val testDirPath = Paths.get(testdir)
          val mounted = mounts.find((dir: String) => testDirPath.isSameFile(Paths.get(dir)))

          val thisPath = mounted match {
          case Some(str) =>
            config.posix2win(str)
          case None =>
            testdir
          }
          val jf = Paths.get(thisPath)
          printf("[%s]: exists [%s]\n", jf.posx, jf.exists)
          val sameDriveLetter = jf.toString.take(2).equalsIgnoreCase(cwdDrive)
          if (mounted.isEmpty && !sameDriveLetter) {
            hook += 1
          }
          // if path is not affected by mount map, drive letters must match
          assert(mounted.nonEmpty || sameDriveLetter)
        }
      }
    }

    it("should correctly apply mountMap") {
      printf("%s\n", envpath)
      printf("%s\n", jvmpath)
      if (isWin) {
        val mounts = config.posix2win.keySet.toArray
        if (mounts.nonEmpty) {
          val testdirs = Seq("/opt", "/optx")
          for (dir <- testdirs) {
            val dirPath = Paths.get(dir)
            val mounted = mounts.find((s: String) => dirPath.isSameFile(Paths.get(s)))
            val thisPath = mounted match {
              case Some(str) =>
                config.posix2win(str)
              case None =>
                dir
            }
            val jf = java.nio.file.Paths.get(thisPath)
            printf("[%s]: exists [%s]\n", jf.posx, jf.exists)
            val testdir = java.nio.file.Paths.get(dir)
            if (mounted.nonEmpty != testdir.exists) {
              hook += 1
            }
            assert(mounted.nonEmpty == testdir.exists)
          }
        }
      }
    }
  }

  def envpath: String = {
    val psep = java.io.File.pathSeparator
    val entries: List[String] =
      Option(System.getenv("PATH")).getOrElse("").split(psep).map { _.toString }.toList
    val path: String = entries.map { _.replace('\\', '/').toLowerCase }.distinct.mkString(";")
    path
  }
  def jvmpath: String = {
    val psep                  = java.io.File.pathSeparator
    val entries: List[String] = sys.props("java.library.path").split(psep).map { _.toString }.toList
    val path: String          = entries.map { _.replace('\\', '/').toLowerCase }.distinct.mkString(";")
    path
  }
}

package uni

import uni.*
import uni.fs.*
import uni.Internals.*
import org.scalatest.BeforeAndAfter
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class FnameTest extends AnyFunSpec with Matchers with BeforeAndAfter {
  var hook = 0

  def nativePathString(p: Path): String = {
    p.normalize.toString match {
    case "" | "." => "."
    case s        => s.replace('\\', '/')
    }
  }

  lazy val TMP = {
    val gdir = Paths.get("/g")
    gdir.isDirectory && gdir.paths.nonEmpty match {
    case true =>
      "/g/tmp"
    case false =>
      "/tmp"
    }
  }
  val testfilenames = Seq(
    s"${TMP}/Canada's_Border.mp3"
    // ,s"${TMP}/ï"
    ,
    s"${TMP}/Canada&s_Border.mp3",
    s"${TMP}/Canada=s_Border.mp3",
    s"${TMP}/Canada!s_Border.mp3",
    s"${TMP}/philosophy&chapter=all",
    s"${TMP}/_2&chapter=all",
    s"${TMP}/_3&chapter=all"
  )

  val testpath = "./bin"
  val testPaths: Seq[String] = Seq(
    testpath.path.toString,
    nativePathString(testpath.path),
    nativePathString(relativePath(testpath.path)),
    testpath.path.relpath.posx,
    testpath.path.relpath,
    testpath.path.stdpath,
    testpath.path.posx,
    testpath.path.localpath,
  )
  describe("file paths") {
    for ((str, i) <- testPaths.zipWithIndex) {
      it(s"path [$str] should be correct for os type [$_osType] output index $i") {
        if (!isWin) {
          if (str.contains(":")) {
            hook += 1
          }
          assert(!str.contains(":"))
        }
      }
    }
  }
  describe("special-chars") {
    for (testfilename <- testfilenames) {
      it(s"should correctly handle filename [$testfilename] ") {
        val testfile = Paths.get(testfilename)
        val testPossible = testfile.getParent match {
        case dir if dir.isDirectory =>
          true
        case _ =>
          false
        }
        if (!testPossible) {
          hook += 1
        } else {
          if (!testfile.exists) {
            // create dummy test file
            withFileWriter(testfile) { w =>
              w.printf("abc\n")
            }
          }
          printf("[%s]\n", testfile.stdpath)
        }
      }
    }
  }
}

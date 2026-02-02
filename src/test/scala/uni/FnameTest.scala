package uni


import uni.*
import uni.io.*
import uni.Internals.{_osType}
import TestUtils.*

import munit.FunSuite
class FnameTest extends FunSuite {
  override def beforeAll(): Unit = uni.resetConfig()

  def nativePathString(p: Path): String =
    p.normalize.toString match {
      case "" | "." => "."
      case s        => s.replace('\\', '/')
    }

  lazy val TMP: String = {
    val gdir = Paths.get("/g")
    if (gdir.isDirectory && gdir.paths.nonEmpty)
      "/g/tmp"
    else
      "/tmp"
  }

  val testfilenames = Seq(
    s"$TMP/Canada's_Border.mp3",
    s"$TMP/Canada&s_Border.mp3",
    s"$TMP/Canada=s_Border.mp3",
    s"$TMP/Canada!s_Border.mp3",
    s"$TMP/philosophy&chapter=all",
    s"$TMP/_2&chapter=all",
    s"$TMP/_3&chapter=all"
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
    testpath.path.localpath
  )

  //
  // ------------------------------------------------------------
  // File path tests (each path gets its own named test)
  // ------------------------------------------------------------
  //
  testPaths.zipWithIndex.foreach { case (str, i) =>
    test(s"file paths: variant #$i [$str] should be correct for os type [$_osType]") {
      if (!isWin) {
        assert(!str.contains(":"), clues(s"path [$str] index $i"))
      }
    }
  }

  // -----------------------
  // filename encoding tests
  // -----------------------
  testfilenames.foreach { testfilename =>
    test(s"non-ascii encoded filename and content [$testfilename]") {
      val testfile = Paths.get(testfilename)

      val testPossible =
        testfile.getParent match {
          case dir if dir.isDirectory => true
          case _                      => false
        }

      if (testPossible) {
        val namestr = testfile.toString.trim
        withFileWriter(testfile) { w =>
          w.printf(namestr)
        }
        noisy(s"[${testfile.stdpath}]")
        val contents = testfile.contentAsString.trim
        assert(contents == namestr, s"contents[$contents]\nfilename[$namestr]")
      }
    }
  }
}

package uni

import munit.FunSuite

final class PosixFmtSuite extends FunSuite {
// uncomment the next 2 lines to disable timeout
  import scala.concurrent.duration.*
  override def munitTimeout: Duration = Duration.Inf

  //private var hook = 0
  // import the extension under test
  import uni.fs.*

  test("String.posix respects forwardMap mount translations"):
    // Suppose forwardMap contains: "C:/msys64/usr/bin"   -> "/usr/bin"
    val expected = "/usr/bin/bash.exe"

    val win = if isWin then
      "C:/msys64/usr/bin/bash.exe"
    else
      expected

    val actual = win.posix
    assertEquals(actual, expected)

  lazy val cygpathExe = if !isWin then "" else Proc.call("where.exe", "cygpath.exe").getOrElse("")

  //given Conversion[String, Seq[String]] with def apply(s: String): Seq[String] = Seq(s)
  def cygpathU(s: String): String = {
    if cygpathExe.isEmpty then
      ""
    else {
      val exe = cygpathExe
      val abs = s.toAbsSlash
      fixit(Proc.call(exe, "-u", abs).getOrElse(""))
    }
  }

  if isWin then
    withMountLines(Seq(
      "C:/msys64 on / type ntfs (binary)",
      "C:/Users on /Users type ntfs (binary,user)",
      "C:/Users on /home type ntfs (binary,user)",
    ))
    printf("windowsPath, expectedAbs, posixAbs, cygpath\n")
    for (win, expect) <- windowsCases do
      test(s"String.posix converts $win to $expect"):
        val winposix = win.posix
        val cygunix: String = cygpathU(win)
        val actual = winposix
        if cygunix != actual then
          hook += 1
//        if cygunix.nonEmpty then assert(cygunix == actual)
          printf("%s, %s, %s, %s\n", win, expect, fixit(actual), cygunix)
        /*
        assertEquals(
          actual,
          expect,
          s"input: $win\nresult: $actual\nexpect: $expect"
        )
        */
  else
    for (pathstr, expect: String) <- nonWinCases do
      test(s"String.posix maps $pathstr to $pathstr"):
        if (pathstr.contains("~")) {
          hook += 1
        }
        val actual = posixAbs(pathstr)
        val expected = unixAbs(pathstr)
        if (actual != expected) {
          hook += 1
        }
        assertEquals(actual, expected)


  resetConfig() // undo `withMountLines` above

  // Representative Windows paths
  lazy val windowsCases: Seq[(String, String)] = Seq(
    // --- Absolute drive-letter paths
    s"$testHome/biz"             -> s"$testHomePosix/biz",
    "C:/"                            -> "/c",
    "Z:/"                            -> "/z",
    "C:/Users/liam/project"          -> "/c/Users/liam/project",
    "D:/data/logs/today.txt"         -> "/d/data/logs/today.txt",
    "E:/tmp"                         -> "/e/tmp",
    "C:/Program Files/Git/bin"       -> "/c/Program Files/Git/bin",
    "C:/Users/liam/AppData/Local"    -> "/c/Users/liam/AppData/Local",

    "C:/Users/liam/project"          -> "/c/Users/liam/project",
    "D:/data/logs/today.txt"         -> "/d/data/logs/today.txt",
    "E:/tmp"                         -> "/e/tmp",
    "C:/Program Files/Git/bin"       -> "/c/Program Files/Git/bin",
    "C:/Users/liam/AppData/Local"    -> "/c/Users/liam/AppData/Local",

    // --- Forward-fwdSlash Windows paths (legal but uncommon)
    "C:/Windows/System32"            -> "/c/Windows/System32",
    "D:/data/logs"                   -> "/d/data/logs",

    // --- Mixed slashes
    "C:/Users/liam/Documents"        -> "/c/Users/liam/Documents",
    "D:/data/logs/today.txt"         -> "/d/data/logs/today.txt",

    // --- Drive-relative paths
    // Meaning: relative to current directory on drive C:
    testDir                      -> testDirPosix,
    testHome                     -> testHomePosix,
    "C:folder/sub"                   -> s"$testDirPosix/folder/sub",
    "C:folder/file.txt"              -> s"$testDirPosix/folder/file.txt",
    "C:folder/sub"                   -> s"$testDirPosix/folder/sub",

    // --- Relative paths
    "./file.txt"                     -> s"$testDirPosix/file.txt",
    "../file.txt"                    -> s"$testParentDirPosix/file.txt",

    // --- Home-relative (not Windows-native, but users type them)
    "~/"                             -> testHomePosix,
    "~"                              -> testHomePosix,
    "~/Documents/notes.txt"          -> s"$testHomePosix/Documents/notes.txt",

    // --- UNC paths
    "//server/share/folder/file.txt" -> "//server/share/folder/file.txt",
    "//server/share/folder/file.txt" -> "//server/share/folder/file.txt",

    // --- Bare filenames
    "file.txt"                       -> s"$testDirPosix/file.txt",
    "notes.md"                       -> s"$testDirPosix/notes.md",

    // --- Paths with spaces
    "C:/My Projects/scala/test"      -> "/c/My Projects/scala/test",
    "D:/Data Sets/2024/report.csv"   -> "/d/Data Sets/2024/report.csv",

    // --- Trailing slashes
    "C:/home/liam/"                  -> "/c/home/liam",
    "D:/data/logs/"                  -> "/d/data/logs",

    // --- miscellaneous
    "/usr/bin"                       -> "/usr/bin",
    "/c/home/liam"                   -> "/c/home/liam",
    "/home/liam"                     -> "/home/liam",
    "./script.sh"                    -> s"$testDirPosix/script.sh",
    "~/script.sh"                    -> s"$testHomePosix/script.sh",
  )

  lazy val nonWinCases: Seq[(String, String)] = for {
    (pathstr, list) <- windowsCases
  } yield (pathstr.replaceFirst("^[a-zA-Z]:", "") -> list)


  lazy val testDirPosix: String = {
    winAbsToPosixAbs(fixit(testDir).toAbsSlash)
  }
  lazy val testHomePosix: String = {
    winAbsToPosixAbs(testHome)
  }
  lazy val (testParentDir: String, testParentDirPosix: String) = {
    val dir = fixit("..".toAbsSlash)
    val posix = winAbsToPosixAbs(dir)
    (dir, posix)
  }
  def fixit(s: String): String = {
    s.replaceAll(realName, testName)
      .replaceAll(realHome, testHome)
      .replaceAll(realDir, testDir)
  }

  lazy val testName: String = "liam"
  lazy val realName: String = sys.props("user.name").fwdSlash
  lazy val realHome: String = sys.props("user.home").fwdSlash
  lazy val realDir: String  = sys.props("user.dir").fwdSlash

  lazy val testHome = realHome.replaceAll(realName, testName) // already absolute
  lazy val testDir  = realDir.replaceAll(realName, testName).toAbsSlash

  extension(s: String) {
    def fwdSlash: String = s.replace('\\', '/')
    def toAbsSlash: String = {
      import java.nio.file.Paths
      val str = s.replace('\\', '/')
      if str.length >= 3 && str.charAt(1) == ':' && str.charAt(2) == '/' then
        str
      else if str.length >= 2 && str.charAt(1) == ':' then
        str
      else
        Paths.get(str).toAbsolutePath.normalize.toString.replace('\\', '/')
    }
  }
}


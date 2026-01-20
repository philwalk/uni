package uni

import munit.FunSuite
import uni.TestUtils.*
import java.nio.file.{Paths as JPaths}

final class PosixFmtSuite extends FunSuite {
// uncomment the next 2 lines to disable timeout
  import scala.concurrent.duration.*
  override def munitTimeout: Duration = Duration.Inf

  override def beforeAll(): Unit = uni.resetConfig()

  override def afterAll(): Unit = {
    resetConfig() // don't contaminate other test suites
  }

  // import the extension under test
  import uni.fs.*
  var testsTotal = 0
  var runsTotal = 0

  test("String.posix respects forwardMap mount translations"):
    // Suppose forwardMap contains: "C:/msys64/usr/bin"   -> "/usr/bin"
    val expected = "/usr/bin/bash.exe"
    val win = if isWin then
      "C:/msys64/usr/bin/bash.exe"
    else
      expected

    val actual = win.posix
    assertEquals(actual, expected)

  test("verify UserInfo mechanism") {
    withMountLines(Nil, testUser)
    val userDir  = normalizePosix(quikResolve("."))
    val userHome = normalizePosix(quikResolve("~"))
    assertEquals(userDir, testUser.dir)
    assertEquals(userHome, testUser.home)
  }

  if isWin then
    // NOTE: when multiple posix paths are mounted to directory (e.g., C:/Users)
    // the tie-breaker rule for `cygpath -u C:/Users` differs between Msys and Cygwin:
    //    Cygwin reports the chronologically newer (most recently mounted) posix path;
    //    Msys reports the lexicographically greater posix path.

    // Shared test runner for all Windows mount configurations.
    def runWinTests(expectsAndMounts: ExpectsGivenMounts): Unit = {
      val mountLines: Seq[String] = expectsAndMounts.mounts
      val expected: Seq[(String, String)] = expectsAndMounts.expects

      for ((win, expect), i) <- expected.zipWithIndex do {
        if i == 0 then runsTotal += 1
        test(s"$i: String.posix converts $win to $expect"):
          // Install mount table
          withMountLines(mountLines, testUser)
          val posixPath = posixAbs(win)
          if posixPath != expect then
            posixAbs(win) // hook
          assertEquals(
            posixPath,
            expect,
            s"input: $win, result: $posixPath, expect: $expect"
          )
      }
    }

    runWinTests(expectsAndMountsMisc) // affected by actual test server mount table
    runWinTests(expectsAndMountsA)    // C:/Uzers -> $mount1
    runWinTests(expectsAndMountsB)    // C:/Uzers -> $mount1 and $mount2
    runWinTests(expectsAndMountsC)    // C:/Uzers unmounted

  else
    // Non-Windows tests: the only affect on the input path
    // should be to convert it to an absolute normalized path.
    for (pathstr, expect: String) <- nonWinCases do
      test(s"String.posix maps $pathstr to $pathstr"):
        if pathstr.contains("~") then hook += 1
        val actual   = posixAbs(pathstr)
        val expected = JPaths.get(pathstr).toAbsolutePath.normalize.toString
        if actual != expected then hook += 1
        assertEquals(actual, expected)


  lazy val mountLinesMisc = Seq(
    "C:/msys64 on / type ntfs (binary)",
  )

  private lazy val expectsAndMountsMisc = {
    withMountLines(mountLinesMisc, testUser)
    ExpectsGivenMounts(
      s"no additional mounts",
      mountLinesMisc,
      Seq(
        testDir                   -> testDirPosix,
        testHome                  -> testHomePosix,
        "C:folder/sub"            -> s"$testDirPosix/folder/sub",
        "C:folder/file.txt"       -> s"$testDirPosix/folder/file.txt",
        "C:folder/sub"            -> s"$testDirPosix/folder/sub",
        "./file.txt"              -> s"$testDirPosix/file.txt",
        "../file.txt"             -> s"$testParentDirPosix/file.txt",
        "~/"                      -> testHomePosix,
        "~"                       -> testHomePosix,
        "~/Documents/notes.txt"   -> s"$testHomePosix/Documents/notes.txt",
        "./script.sh"             -> s"$testDirPosix/script.sh",
        "~/script.sh"             -> s"$testHomePosix/script.sh",
        "file.txt"                -> s"$testDirPosix/file.txt",
        "notes.md"                -> s"$testDirPosix/notes.md",
      )
    )
  }

  // Representative Windows paths with C:/Uzers => /homedir
  private lazy val expectsAndMountsA = ExpectsGivenMounts(
    s"${uzers} => $mount1",
    Seq(
      "C:/msys64 on / type ntfs (binary)",
      s"${uzers} on $mount1 type ntfs (binary,user)"
    ), Seq(
      s"$mount1/liam/"                 -> s"$mount1/liam",
      s"$mount1/liam"                  -> s"$mount1/liam",
      s"/c$mount1/liam"                -> s"/c$mount1/liam",
      s"C:$mount1/liam/"               -> s"/c$mount1/liam",
      "/usr/bin"                       -> "/usr/bin",
      s"$uzers/liam/"                  -> s"$mount1/liam",
      s"$uzers/liam"                   -> s"$mount1/liam",
      s"$uzers/liam/AppData/Local"     -> s"$mount1/liam/AppData/Local",
      s"$uzers/liam/Documents"         -> s"$mount1/liam/Documents",
      "C:/Program Files/Git/bin"       -> "/c/Program Files/Git/bin",
      "C:/Windows/System32"            -> "/c/Windows/System32",
      "D:/data/logs"                   -> "/d/data/logs",
      "D:/data/logs/"                  -> "/d/data/logs",
      "D:/data/logs/today.txt"         -> "/d/data/logs/today.txt",
      "D:/Data Sets/2024/report.csv"   -> "/d/Data Sets/2024/report.csv",
      "C:/My Projects/scala/test"      -> "/c/My Projects/scala/test",
      "//server/share/folder/file.txt" -> "//server/share/folder/file.txt",
      "C:/"                            -> "/c",
      "Z:/"                            -> "/z",
    )
  )

  // Representative Windows paths with C:/Uzers => $mount1 and $mount2
  private lazy val expectsAndMountsB = ExpectsGivenMounts(
    s"$uzers -> $mount1 and $mount2",
    Seq(
      "C:/msys64 on / type ntfs (binary)",
      s"$uzers on $mount1 type ntfs (binary,user)",
      s"$uzers on $mount2 type ntfs (binary,user)",
    ), Seq(
      s"$mount1/liam/"                 -> s"$mount1/liam",
      s"$mount1/liam"                  -> s"$mount1/liam",
      s"/c$mount1/liam"                -> s"/c$mount1/liam",
      s"C:$mount1/liam/"               -> s"/c$mount1/liam",
      s"$uzers/liam/"                  -> s"$mount1/liam",
      s"$uzers/liam"                   -> s"$mount1/liam",
      s"$uzers/liam/AppData/Local"     -> s"$mount1/liam/AppData/Local",
      s"$uzers/liam/Documents"         -> s"$mount1/liam/Documents",
      "C:/Program Files/Git/bin"       -> "/c/Program Files/Git/bin",
      "C:/Windows/System32"            -> "/c/Windows/System32",
      "D:/data/logs"                   -> "/d/data/logs",
      "D:/data/logs/"                  -> "/d/data/logs",
      "D:/data/logs/today.txt"         -> "/d/data/logs/today.txt",
      "D:/Data Sets/2024/report.csv"   -> "/d/Data Sets/2024/report.csv",
      "C:/My Projects/scala/test"      -> "/c/My Projects/scala/test",
      "//server/share/folder/file.txt" -> "//server/share/folder/file.txt",
      "C:/"                            -> "/c",
      "Z:/"                            -> "/z",
    )
  )

  // Representative Windows paths with C:/Uzers not mounted
  private lazy val expectsAndMountsC = ExpectsGivenMounts(
    s"$uzers not mounted",
    Seq(
      "C:/msys64 on / type ntfs (binary)",
    ), Seq(
      "/usr/bin"                       -> "/usr/bin",
      "C:/Uzers/liam/"                 -> "/c/Uzers/liam",
      "C:/Uzers/liam"                  -> "/c/Uzers/liam",
      "C:/Uzers/liam/AppData/Local"    -> "/c/Uzers/liam/AppData/Local",
      "C:/Uzers/liam/Documents"        -> "/c/Uzers/liam/Documents",
      "C:/Program Files/Git/bin"       -> "/c/Program Files/Git/bin",
      "C:/Windows/System32"            -> "/c/Windows/System32",
      "D:/data/logs"                   -> "/d/data/logs",
      "D:/data/logs/"                  -> "/d/data/logs",
      "D:/data/logs/today.txt"         -> "/d/data/logs/today.txt",
      "D:/Data Sets/2024/report.csv"   -> "/d/Data Sets/2024/report.csv",
      "C:/My Projects/scala/test"      -> "/c/My Projects/scala/test",
      "//server/share/folder/file.txt" -> "//server/share/folder/file.txt",
      "C:/"                            -> "/c",
      "Z:/"                            -> "/z",
    )
  )

  case class ExpectsGivenMounts(tag: String, mounts: Seq[String], expects: Seq[(String, String)])

  def winAbsToCygdrive(raw: String): String =
    if raw.length >= 3 &&
       raw.charAt(1) == ':' &&
       raw.charAt(2) == '/' &&
       raw.charAt(0).isLetter
    then
      val drive = raw.charAt(0).toLower
      s"${config.cygdrive}$drive${raw.substring(2)}"
    else
      raw

  extension(abs: String) {
    def posixDrive: String = {
      assert(abs.take(2).last == ':')
      winAbsToCygdrive(abs)
    }
  }

  private lazy val nonWinCases: Seq[(String, String)] = {
    val expects: Seq[(String, String)] = expectsAndMountsA.expects

    val cases: Seq[(String, String)] = 
      for {
        (pathstr: String, expect: String) <- expects
        barePath = pathstr.replaceFirst("^[a-zA-Z]:", "")
        bareExpect = expect.replaceFirst("/[a-z]/?", "/")
      } yield (barePath, bareExpect)
    cases
  }

//  private lazy val realName: String = sys.props("user.name").fwdSlash
//  private lazy val realHome: String = sys.props("user.home").fwdSlash
//  private lazy val realDir: String  = sys.props("user.dir").fwdSlash
//  private lazy val realTestHome = realHome.replaceAll(realName, testUsername) // already absolute
//  private lazy val realTestDir  = realDir.replaceAll(realName, testUsername).toAbsSlash

  private lazy val mount1     = "/mount1"
  private lazy val mount2     = "/mount2"
  private lazy val uzers      = "C:/Uzers"

  def testDir      = testUser.dir
  def testUsername = testUser.name
  def testHome     = testUser.home

  def testDirPosix       = config.userdir.posix
  def testHomePosix      = config.userhome.posix
  def testParentDirPosix = config.userdirParent.posix

}

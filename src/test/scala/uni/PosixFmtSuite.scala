package uni

import munit.FunSuite

final class PosixFmtSuite extends FunSuite {
// uncomment the next 2 lines to disable timeout
  import scala.concurrent.duration.*
  override def munitTimeout: Duration = Duration.Inf

  //private var hook = 0
  // import the extension under test
  import uni.pwd
  import uni.fs.*

  test("String.posix respects forwardMap mount translations"):
    // Suppose forwardMap contains: "C:/msys64/usr/bin"   -> Seq("/usr/bin")
    val expected = "/usr/bin/bash.exe"

    val win = if isWin then
      "C:/msys64/usr/bin/bash.exe"
    else
      expected

    val actual = win.posix
    assertEquals(actual, expected)

  given Conversion[String, Seq[String]] with
    def apply(s: String): Seq[String] = Seq(s)

  if isWin then
    withMountLines(Seq(
      "C:/msys64 on / type ntfs (binary)",
      "C:/Users on /c/home type ntfs (binary)",
    ))
    for (win, allowedPosix: Seq[String]) <- windowsCases do
      test(s"String.posix converts $win to ${allowedPosix.mkString(" or ")}"):
        val actual = win.posix
        val expectedPosix = if allowedPosix.contains(actual) then
          actual
        else
          allowedPosix.head // fail

        assertEquals(
          actual,
          expectedPosix,
          clues(s"input: $win\nresult: $actual\nexpect: $expectedPosix")
        )
  else
    for (pathstr, list: Seq[String]) <- nonWinCases do
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
  lazy val windowsCases: Seq[(String, Seq[String])] = Seq(
    // --- Absolute drive-letter paths
    s"${pwd.toString}/biz"           -> Seq(s"$cwd1/biz", s"$cwd2/biz"),
    "C:/"                            -> "/c",
    "Z:/"                            -> "/z",
    "C:/Users/liam/project"          -> Seq("/c/Users/liam/project", "/c/home/liam/project"),
    "D:/data/logs/today.txt"         -> "/d/data/logs/today.txt",
    "E:/tmp"                         -> "/e/tmp",
    "C:/Program Files/Git/bin"       -> "/c/Program Files/Git/bin",
    "C:/Users/liam/AppData/Local"    -> Seq("/c/Users/liam/AppData/Local", "/c/home/liam/AppData/Local"),

    "C:/Users/liam/project"         -> Seq("/c/Users/liam/project", "/c/home/liam/project"),
    "D:/data/logs/today.txt"        -> "/d/data/logs/today.txt",
    "E:/tmp"                        -> "/e/tmp",
    "C:/Program Files/Git/bin"      -> "/c/Program Files/Git/bin",
    "C:/Users/liam/AppData/Local"   -> Seq("/c/Users/liam/AppData/Local", "/c/home/liam/AppData/Local"),

    // --- Forward-slash Windows paths (legal but uncommon)
    "C:/Windows/System32"            -> "/c/Windows/System32",
    "D:/data/logs"                   -> "/d/data/logs",

    // --- Mixed slashes
    "C:/Users/liam/Documents"        -> Seq("/c/Users/liam/Documents", "/c/home/liam/Documents"),
    "D:/data/logs/today.txt"         -> "/d/data/logs/today.txt",

    // --- Drive-relative paths
    // Meaning: relative to current directory on drive C:
    "C:folder/file.txt"              -> Seq(s"$cwd1/folder/file.txt", s"$cwd2/folder/file.txt"),
    "C:folder/sub"                   -> Seq(s"$cwd2/folder/sub", s"$cwd2/folder/sub"),

    // --- Relative paths
    "./file.txt"                     -> Seq(s"$cwd1/file.txt", s"$cwd2/file.txt"),
    "../file.txt"                    -> Seq(s"$parent1/file.txt", s"$parent2/file.txt"),

    // --- Home-relative (not Windows-native, but users type them)
    "~/"                             -> Seq(home1, home2),
    "~"                              -> Seq(home1, home2),
    "~/Documents/notes.txt"          -> Seq(s"$home1/Documents/notes.txt", s"$home2/Documents/notes.txt"),

    // --- UNC paths
    "//server/share/folder/file.txt" -> "//server/share/folder/file.txt",
    "//server/share/folder/file.txt" -> "//server/share/folder/file.txt",

    // --- Bare filenames
    "file.txt"                       -> Seq(s"$cwd1/file.txt", s"$cwd2/file.txt"),
    "notes.md"                       -> Seq(s"$cwd1/notes.md", s"$cwd2/notes.md"),

    // --- Paths with spaces
    "C:/My Projects/scala/test"      -> "/c/My Projects/scala/test",
    "D:/Data Sets/2024/report.csv"   -> "/d/Data Sets/2024/report.csv",

    // --- Trailing slashes
    "C:/Users/liam/"                 -> Seq("/c/Users/liam", "/c/home/liam"),
    "D:/data/logs/"                  -> "/d/data/logs",

    // --- miscellaneous
    "/usr/bin"                       -> "/usr/bin",
    "/c/Users/liam"                  -> Seq("/c/Users/liam", "/c/home/liam"),
    "./script.sh"                    -> Seq(s"$cwd1/script.sh", s"$cwd2/script.sh"),
  )
  lazy val nonWinCases: Seq[(String, Seq[String])] = for {
    (pathstr, list) <- windowsCases
  } yield (pathstr.replaceFirst("^[a-zA-Z]:", "") -> list)

  lazy val (cwd1: String, cwd2: String) = {
    val dir1 = toPosixDriveLetter(Paths.get(".").toAbsolutePath.normalize.toString.replace('\\', '/'))
    val dir2 = dir1.replaceAll("(?i)/Users", "/home")
    (dir1, dir2)
  }
  lazy val (parent1: String, parent2: String) = {
    val dir1 = toPosixDriveLetter(Paths.get("..").toAbsolutePath.normalize.toString.replace('\\', '/'))
    val dir2 = dir1.replaceAll("(?i)/Users", "/home")
    (dir1, dir2)
  }
  lazy val (home1: String, home2: String) = {
    import java.nio.file.Paths
    val dir1 = toPosixDriveLetter(Paths.get(sys.props("user.home")).toAbsolutePath.normalize.toString.replace('\\', '/'))
    val dir2 = dir1.replaceAll("(?i)/Users", "/home")
    (dir1, dir2)
  }
}


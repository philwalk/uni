package uni

import uni.*
import uni.fs.*
import uni.Internals.*
import java.nio.file.{Paths as JPaths}

object TestUtils {
  val verbose = Option(System.getenv("VERBOSE_TESTS")).nonEmpty
  def noisy(s: String): Unit = if verbose then  System.err.print(s+"\n")
  def prmsg(s: String): Unit = print(s+"\n")

  lazy val windowsTestUser: UserInfo = UserInfo(
    "liam",                // username
    "C:/Persons/liam",     // userhome
    "C:/munit/test"        // userdir
  )
  lazy val unixTestUser: UserInfo = UserInfo(
    windowsTestUser.name,
    windowsTestUser.home.drop(2),
    windowsTestUser.dir.drop(2)
  )
  lazy val testUser: UserInfo = if isWin then windowsTestUser else unixTestUser

  def procFiles = Seq(
    "/proc/cpuinfo",
    "/proc/devices",
    "/proc/filesystems",
//    "/proc/loadavg", // too slow
    "/proc/meminfo",
    "/proc/misc",
    "/proc/partitions",
    "/proc/stat",
    "/proc/swaps",
    "/proc/uptime",
    "/proc/version",
  )

  def showTestInputs(): Unit = {
    prmsg(s"cdrive.exists:         ${cdrive.exists}")
    prmsg(s"cdrive.isDirectory:    ${cdrive.isDirectory}")
    prmsg(s"cdrive.isRegularFile:  ${cdrive.isDirectory}")
    prmsg(s"cdrive.isSymbolicLink: ${cdrive.isSymbolicLink}")

    prmsg(s"fdrive.exists:         ${fdrive.exists}")
    prmsg(s"fdrive.isDirectory:    ${fdrive.isDirectory}")
    prmsg(s"fdrive.isRegularFile:  ${fdrive.isDirectory}")
    prmsg(s"fdrive.isSymbolicLink: ${fdrive.isSymbolicLink}")

    prmsg(s"gdrive.exists:         ${gdrive.exists}")
    prmsg(s"gdrive.isDirectory:    ${gdrive.isDirectory}")
    prmsg(s"gdrive.isRegularFile:  ${gdrive.isDirectory}")
    prmsg(s"gdrive.isSymbolicLink: ${gdrive.isSymbolicLink}")
  }
  def getVariants(p: Path): Seq[Path] = {
    val pstr = p.toString.toLowerCase
    val stdpathToo = if (nonCanonicalDefaultDrive) Nil else Seq(p.stdpath)
    val pposx = p.posx
    val ptoStr = p.toString
    val plocal = p.localpath
    val pdos = p.dospath
    val variants: Seq[String] = Seq(
      pposx,
      ptoStr,
      plocal,
      pdos
    ) ++ stdpathToo // stdpath fails round-trip test when default drive != C:

    val vlist = variants.distinct.map { s =>
      uni.Paths.get(s)
    }
    vlist.distinct
  }

  lazy val maxLines      = 10
  lazy val testDataLines = (0 until maxLines).toList.map { _.toString }

  lazy val homeDirTestFile = "~/shellExecFileTest.out"

  lazy val dosHomeDir: String   = sys.props("user.home")
  lazy val posixHomeDir: String = {
    val dhd = dosHomeDir.path
    dhd.stdpath
  }

  lazy val cdrive = Paths.get("c:/")
  lazy val gdrive = Paths.get("g:/")
  lazy val fdrive = Paths.get("f:/")

  // cygdrive describes how to translate `driveRelative` like this:
  //     /cygdrive/c          # if cygdrive == '/cygdrive'
  //     /c                   # if cygdrive == '/'
  lazy val cygdrive: String = config.cygdrive match {
  case str if str.endsWith("/") => str
  case str                      => s"$str/"
  }

  lazy val gdriveTests = List(
    (s"${cygdrive}g", "g:\\"),
    (s"${cygdrive}g/", "g:\\")
  )

  lazy val pathDospathPairs = {
    var pairs = List(
      (".", "."),
      (hereDrive, here),         // jvm treats bare "C:" as pwd for that drive
      (s"${cygdrive}q/", "q:\\"), // assumes /etc/fstab mounts /cygdrive to /
      (s"${cygdrive}q", "q:\\"),  // assumes /etc/fstab mounts /cygdrive to /
      (s"${cygdrive}c", "c:\\"),
      (s"${cygdrive}c/", "c:\\"),
      ("~", dosHomeDir),
      ("~/", dosHomeDir),
      (s"${cygdrive}g", "g:\\"),
      (s"${cygdrive}g/", "g:\\"),
      (s"${cygdrive}c/data/", "c:\\data")
    ) ::: gdriveTests
    pairs = pairs.distinct

    val empty = pairs.find { case ((a: String, b: String)) =>
      b.trim == ""
    }
    pairs
  }.distinct

  lazy val nonCanonicalDefaultDrive = {
    val dru = driveRoot.toUpperCase
    dru != "C:"
  }

  lazy val username = sys.props("user.name").toLowerCase

  lazy val toStringPairs = List(
    ("C:/opt",  "/opt"),
    ("/etc",  s"${cygdrive}etc"),
    (".", uhere),
    (s"${cygdrive}q/", s"${cygdrive}q"),
    (s"${cygdrive}q/file", s"${cygdrive}q/file"), // assumes there is no Q: drive
    (hereDrive, uhere),                         // jvm: bare drive == cwd
    (s"${cygdrive}c/", s"${cygdrive}c"),
    ("~", posixHomeDir),
    ("~/", posixHomeDir),
    (s"${cygdrive}g", s"${cygdrive}g"),
    (s"${cygdrive}g/", s"${cygdrive}g"),
    (s"${cygdrive}c/data/", "/data")
  )
  lazy val TMP: String = {
    val dl = "f"
    val driveRoot   = s"${cygdrive}${dl}"
    if (canExist(driveRoot.path)) {
      val tmpdir = Paths.get(driveRoot)
      // val str = tmpdir.localpath
      tmpdir.isDirectory && tmpdir.paths.contains("/tmp") match {
      case true =>
        s"${cygdrive}${dl}/tmp"
      case false =>
        "/tmp"
      }
    } else {
      "/tmp"
    }
  }
  lazy val distinctKeys: Seq[String] = {
    val pairs: Seq[String] = (toStringPairs.toMap.keySet ++ pathDospathPairs.toMap.keySet).toList.distinct.sorted
    for (pair <- pairs) {
      printf("pair: [%s]\n", pair)
    }
    pairs
  }

  lazy val testPwd: Path = java.nio.file.Paths.get(".").toAbsolutePath.normalize

  def isPwd(p: Path): Boolean = isPwd(p.toString)

  def isPwd(s: String): Boolean = s match {
    case "" | "." =>
      true
    case s =>
      JPaths.get(s).isSameFile(testPwd)
  }

  /** similar to gnu 'touch <filename>' */
  def touch(p: Path): Int = {
    var exitCode = 0
    try {
      p.toFile.createNewFile()
    } catch {
      case _: Exception =>
        exitCode = 17
    }
    exitCode
  }
  def touch(fname: String): Int = {
    touch(Paths.get(fname))
  }

  lazy val cygpathExe = if !isWin then "" else Proc.call("where.exe", "cygpath.exe").getOrElse("")

  //given Conversion[String, Seq[String]] with def apply(s: String): Seq[String] = Seq(s)
  def cygpathU(s: String): String = {
    if cygpathExe.isEmpty then
      ""
    else {
      val exe = cygpathExe
      val abs = s
      Proc.call(exe, "-u", abs).getOrElse("")
    }
  }

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

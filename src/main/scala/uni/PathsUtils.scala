//#!/usr/bin/env -S scala-cli --cli-version nightly shebang -deprecation -q
package uni

import java.io.{File as JFile}
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{Path, Files, Paths as JPaths, StandardCopyOption}
import java.util.Locale
import scala.collection.immutable.SortedMap
import scala.sys.process.*
import scala.util.Try
import scala.util.Properties
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.RoundingMode
import StandardCharsets.{UTF_8, ISO_8859_1 as Latin1}
import Internals.*
import uni.fs.*

export scala.util.Properties.{isWin, isMac, isLinux}

def progName(mainObject: AnyRef) = Option(sys.props("scala.source.names")).getOrElse {
  // usage: progName(this) from the main object.
  mainObject.getClass.getName
    .replaceAll(".*[.]", "")   // drop package
    .replaceAll("[$].*", "")   // drop Scala object suffix
}

/**
 * Print a filtered stack trace.
 */
private def withFilteredStack(e: Throwable)(p: StackTraceElement => Boolean): Unit = {
  val original = e.getStackTrace
  val filtered = original.filter(p)
  e.setStackTrace(filtered)
  e.printStackTrace()
  e.setStackTrace(original)
}

/*
 * Print a less verbose stack trace.
 */
def showLimitedStack(e: Throwable): Unit = {
  withFilteredStack(e){ elem =>
    val cls = elem.getClassName
    !cls.startsWith("java.") &&
    !cls.startsWith("javax.") &&
    !cls.startsWith("jdk.") &&
    !cls.startsWith("sun.") &&
    !cls.startsWith("oracle.") &&
    !cls.startsWith("scala.")
  }
}

/*
 * Only show stack trace elements of caller object.
 * Usage: showMinimalStack(e, this)
 */
def showMinimalStack(e: Exception, ref: AnyRef): Unit = {
  val lcMain = progName(ref).toLowerCase
  withFilteredStack(e) { elem =>
    elem.toString.toLowerCase.contains(lcMain)
  }
}

lazy val userHome = sys.props("user.home").replace('\\', '/')
private var hook = 0

// Minimal process helpers for portability
object Proc {

  import scala.sys.process.*

  // Returns first stdout line if command succeeds
  def call(cmd: String, arg: String): Option[String] = {
    val buf = scala.collection.mutable.ListBuffer.empty[String]
    val status = Seq(cmd, arg).!(ProcessLogger(line => buf += line, _ => ()))
    if (status == 0 && buf.nonEmpty) Some(buf.head) else None
  }

  // Returns all stdout lines
  def lazyLines(cmd: String): Seq[String] = {
    val buf = scala.collection.mutable.ListBuffer.empty[String]
    val status = Seq(cmd).!(ProcessLogger(line => buf += line, _ => ()))
    if (status == 0) buf.toList else Nil
  }
}

lazy val pwd: Path = JPaths.get("").toAbsolutePath

object Internals {
  import fs.*

  lazy val exe: String = if isWin then ".exe" else ""

  case class Proc(status: Int, stdout: Seq[String], stderr: Seq[String])

  def spawn(cmd: String *): Proc = {
    import scala.collection.mutable.ListBuffer
    val (stdout, stderr) = (ListBuffer.empty[String], ListBuffer.empty[String])
    val cmdArray = cmd.toArray.updated(0, cmd.head.stripSuffix(exe) + exe)
    try {
      val status = cmdArray.toSeq ! ProcessLogger(stdout append _, stderr append _)
      Proc(status, stdout.toSeq, stderr.toSeq)
    } catch {
      case e: Exception =>
        Proc(-1, stdout.toSeq, (stderr append e.getMessage).toSeq)
    }
  }

  def call(cmd: String *): Option[String] = {
    try {
      val ret = spawn(cmd *)
      if (ret.status != 0){
        None
      } else {
        ret.stdout.mkString("\n").trim match {
          case s if s.nonEmpty =>
            Some(s)
          case _ =>
            None
        }
      }
    } catch {
      case _: Throwable =>
        None
    }
  }

  def realpathWindows(path: String): String = {
    if (!isWin) {
      path
    } else {
      def reparseTest(path: String): String = {
        Try {
          // this line throws an exception if path is not a Windows reparse point (symlink)
          val output = Seq("fsutil", "reparsepoint", "query", path).!!.linesIterator.toList

          // Collect hex dump lines
          val hexLines = output.filter(_.matches("""^\s*[0-9A-Fa-f]{4}:.*"""))
          val hexPairs = hexLines.flatMap(_.drop(6).trim.split("\\s+").filter(_.nonEmpty))
          val bytes    = hexPairs.map(Integer.parseInt(_, 16).toByte).toArray

          val decoded  = new String(bytes, StandardCharsets.UTF_16LE).trim
          val parts = decoded.split("\\?\\?\\\\")
          val printName: String = parts.lastOption.getOrElse(decoded) // user-friendly
          printName
        }.getOrElse(path)
      }

      def loop(p: Path): String = {
        if (p == null) {
          path
        } else {
          val resolved = reparseTest(p.toString)
          if resolved != p.toString then resolved
          else loop(p.getParent)
        }
      }
      loop(JPaths.get(path))
    }
  }

  def realWhere(jpath: Path): Path = {
    realWhere(jpath.toString.replace('\\', '/'))
  }

  def realWhere(mightBeSymlinkToExecutable: String): Path = {
    try {
      if (!isWin) {
        val cmd = Seq("bash", "-c", s"""realpath "`command -v ${mightBeSymlinkToExecutable}`" """.trim)
        val real: String = call(cmd *).getOrElse(mightBeSymlinkToExecutable)
        JPaths.get(real)
      } else {
        val real = realpathWindows(mightBeSymlinkToExecutable)
        JPaths.get(real)
      }
    } catch {
      case e: Exception =>
        JPaths.get(mightBeSymlinkToExecutable) 
    }
  }

  def relativePathToCwd(p: Path): Path = {
    val candidate =
      if !p.isAbsolute && p.getRoot != null then {
        val driveRoot = pwd.getRoot
        driveRoot.resolve(p.toString.substring(1))
      } else {
        p
      }

    if candidate.isAbsolute then {
      try {
        val rel = pwd.relativize(candidate)
        if !rel.toString.startsWith("..") then {
          rel
        } else {
          candidate
        }
      } catch {
        case _: IllegalArgumentException => candidate
      }
    } else {
      candidate
    }
  }

  lazy val defaultDrive: String = defaultDriveLetter+":"

  def defaultDriveLetter: String = {
    if (isWin) new JFile("/").getAbsolutePath.take(1) else ""
  }

  def showMountMaps(): Unit = {
    printf("%s\n", "Forward Map:")
    config.win2posix.foreach { case (k, v) =>
      val row = "%-44s -> %s".format(k, v.mkString(","))
      printf("%s\n", row)
    }

    printf("\n%s\n", "Reverse Map:")
    config.posix2win.foreach { case (k, v) =>
      val row = "%-44s -> %s".format(k, v)
      printf("%s\n", row)
    }
  }

  def samePathString(s1: String ,s2: String): Boolean = {
    if (isWin || isMac) {
      s1 equalsIgnoreCase s2
    } else {
      s1 == s2
    }
  }

  def sameFileTest(p1: Path, p2: Path): Boolean = {
    try {
      val (p1str, p2str) = (p1.toFile.getAbsolutePath, p2.toFile.getAbsolutePath)
      // even files that !canExist() can be the same file
      // if path strings are an exact path
      samePathString(p1str, p2str) || {
        canExist(p1) && canExist(p2) && {
          Files.isSameFile(p1, p2)
        }
      }
    } catch {
      case _: Exception =>
        false
    }
  }

  def exists(fname: String): Boolean = Files.exists(Paths.get(fname))

  def standardizePath(p: Path): String = {
    if p.toString.contains("~") then
      hook += 1
    val winPath: String = if canExist(p) then
      p.toAbsolutePath.normalize.toString
    else
      p.toFile.getAbsolutePath // is this adequate?

    val pathstr = winPath
    if (!isWin) {
      pathstr
    } else {
      val pstr = pathstr.replace('\\', '/') match {
      case "/" => "/"
      case s => s.stripSuffix("/") // no trailing slash
      }

      // First check explicit mounts
      val w2pm = config.win2posix
      val maybeMount = w2pm.keys
        .filter(pstr.startsWithIgnoreCase)
        .toList
        .sortBy(-_.length)
        .headOption

      maybeMount match {
        case Some(winRoot) =>
          // Replace with mapped POSIX mount
          val posixRoots = w2pm(winRoot)
          val post = pstr.drop(winRoot.length)
          posixRoots.head match {
            case "/" =>
              post
            case root =>
              s"$root$post"
          }

        case None =>
          if (pstr.length >= 2 && pstr(1) == ':') {
            // Drive letter path
            val drive = pstr(0).toLower
            val post = pstr.drop(2)
            s"${config.cygdrive}$drive$post"
          } else if (pstr.startsWith("//")) {
            // UNC path
            val unc = pstr.drop(2)
            s"${config.cygdrive}unc/$unc"
          } else {
            // Relative path
            pstr
          }
      }
    }
  }

  def asPosixDrive(dl: String, path: String): String = {
    val root = config.cygdrive
    val cygified = s"$root${dl.take(1).toLowerCase(Locale.ROOT)}$path"
    cygified
  }
  lazy val driveRoot: String = JPaths.get("").toAbsolutePath.getRoot.toString.take(2)

  def _osName: String = sys.props("os.name")

  lazy val _osType: String = _osName.toLowerCase(Locale.ROOT) match {
  case s if s.contains("windows")  => "windows"
  case s if s.contains("linux")    => "linux"
  case s if s.contains("mac os x") => "darwin"
  case other =>
    sys.error(s"osType is [$other]")
  }
 
  import java.io.{FileWriter, OutputStreamWriter, PrintWriter}
  def withFileWriter(p: Path, charsetName: String = "UTF-8", append: Boolean = false)(func: PrintWriter => Any): Unit = {
    val jfile  = p.toFile
    val lcname = jfile.getName.toLowerCase(Locale.ROOT)
    if (lcname != "stdout") {
      Option(jfile.getParentFile) match {
      case Some(parent) =>
        if (!parent.exists) {
          throw new IllegalArgumentException(s"parent directory not found [${parent}]")
        }
      case None =>
        throw new IllegalArgumentException(s"no parent directory")
      }
    }
    val writer = lcname match {
    case "stdout" =>
      new PrintWriter(new OutputStreamWriter(System.out, charsetName), true)
    case _ =>
      new PrintWriter(new FileWriter(jfile, append))
    }
    try {
      val _: Any = func(writer)
    } finally {
      writer.flush()
      if (lcname != "stdout") {
        // don't close stdout!
        writer.close()
      }
    }
  }
  def shellRoot: String = if isWin then call("cygpath.exe", "-m", "/").getOrElse("") else ""

  def isWinshell: Boolean = Properties.propOrNone("MSYSTEM").nonEmpty
  lazy val here  = pwd.toAbsolutePath.normalize.toString.toLowerCase(Locale.ROOT).replace('\\', '/')
  lazy val uhere = here.replaceFirst("^[a-zA-Z]:", "")
  def hereDrive: String = {
    if (isWin) new JFile("/").getAbsolutePath.take(2).mkString else ""
  }

  def canExist(p: Path): Boolean = {
    val root = p.getRoot
    if (root == null) {
      true
    } else {
      val rootDrive = root.toFile.toString.toUpperCase.take(2)
      rootDrives.contains(rootDrive)
    }
  }

  def rootDrives: Array[String] = java.io.File.listRoots().map( (f: JFile) =>
    f.getAbsolutePath.take(2) // discard trailing backslashes
  )
  /*
  def driveLetters: Seq[Char] = java.io.File.listRoots().map( (f: JFile) =>
    f.getAbsolutePath.head // discard trailing backslashes
  )
  */

  def safeAbsolutePath(p: Path): Path = {
    if (!isWin) {
      p.toAbsolutePath
    } else {
      val pstr = p.toString
      if (pstr.matches("^[A-Za-z]:$")) {
        val drive = pstr.charAt(0)
        if (java.io.File.listRoots().exists(_.getPath.startsWith(s"$drive:")))
          p.toAbsolutePath // drive exists, delegate
        else
          JPaths.get(s"$drive:/") // convention: root of drive
      } else {
        p.toAbsolutePath
      }
    }
  }

  // maps lookup is by lowercase
  extension [V](m: SortedMap[String, V]) {
    def getLower(key: String): Option[V] =
      m.get(key.toLowerCase(Locale.ROOT))

    def get(key: String): Option[V] =
      m.get(key.toLowerCase(Locale.ROOT))

    def getLowerOrElse(key: String, default: => V): V =
      m.getOrElse(key.toLowerCase(Locale.ROOT), default)

    def getOrElse(key: String, default: => V): V =
      m.getOrElse(key.toLowerCase(Locale.ROOT), default)
  }
}

private val driveLetterPattern =
  java.util.regex.Pattern.compile("^([A-Za-z]):")

/** joined string normalized to never have trailing slash unless == "/" */
inline def joinPosix(prefix: String, suffix: String): String =
  val pre  = prefix.stripSuffix("/")
  val post = s"/${suffix.stripPrefix("/")}"
  s"$pre$post" match {
    case "/" => "/"
    case s => s.stripSuffix("/")
  }

/*
 * This method only converts if `isWin`, otherwise it's a pass-through.
 * Output is a POSIX-style String.
 * In Windows:
 *   convert rawstr path to `cygpath -u` format
 *   in some cases java sees a different path than cygpath; defer to java.
 */
def posixAbs(raw: String): String = {
  if !isWin then {
    Paths.get(raw).toAbsolutePath.normalize.toString
  } else {
    val cygMixed = Paths.get(raw).toAbsolutePath.normalize.toString.replace('\\', '/')
    val absPosix = {
      if (cygMixed.startsWithIgnoreCase(config.cygRoot)) {
        cygMixed.drop(config.cygRoot.length)
      } else {
        Resolver.findPrefix(cygMixed, config.win2posixKeys) match {
          case Some(winPrefix) =>
            val posixSeq = config.win2posix(winPrefix)
            val suffix   = cygMixed.drop(winPrefix.length).stripSuffix("/")
            joinPosix(posixSeq.head, suffix)
          case None =>
            toPosixDriveLetter(cygMixed)
        }
      }
    }
    absPosix
  }
}

// leverage posixAbs to deal with ~, trailing slash, etc.
def posixRel(raw: String): String = {
  val cwd = posixAbs(".")
  val abs = posixAbs(raw)
  val rel =
    if abs.startsWithIgnoreCase(cwd) then
      abs.drop(cwd.length)
    else
      abs   // fallback: cannot relativize, return absolute
  rel.toString.replace('\\', '/')
}

private inline def toPosixDriveLetter(winPath: String): String = {
  val cyg = config.cygdrive.stripSuffix("/") // "/" reduces to empty string, "/cygpath" unaffected
  if isDriveLetterPath(winPath) then
    val driveLower = winPath.charAt(0).toLower.toString
    s"$cyg/$driveLower${winPath.substring(2)}"
  else
    winPath
}
private inline def isDriveLetterPath(s: String): Boolean = {
  s.length >= 2 && s.charAt(1) == ':' && {
    val c = s.charAt(0)
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
  }
}

/** Extension methods */
object stringExts {
  extension (str: String) {
    def path: Path = Paths.get(str)
    def posx: String = str.replace('\\', '/')
    def posix: String = posixAbs(str)

    def startsWithIgnoreCase(prefix: String): Boolean = startsWithUncased(str, prefix)
    def stripPrefixIgnoreCase(prefix: String): String = stripPrefixUncased(str, prefix)
    def stripPrefix(prefix: String): String =
      if str.startsWith(prefix) then str.substring(prefix.length)
      else str

    def local: String = {
      val forward = str.replace('\\', '/')
      val isPosix = forward.startsWith("/") //|| !forward.matches("(?i)^[a-z]:/.*")
      if (!isWin || !isPosix) {
        str
      } else {
        config.posix2win.collectFirst {
          case (posix, winSeq) if posix != "/" && str.startsWithIgnoreCase(posix) =>
            s"${winSeq.head}${str.stripPrefixIgnoreCase(posix)}"
        }.getOrElse {
          s"${config.msysRoot}$str"
        }
      }
    }
  }

  def startsWithUncased(str: String, prefix: String): Boolean = {
    str.regionMatches(true, 0, prefix, 0, prefix.length)
  }

  def stripPrefixUncased(str: String, prefix: String): String = {
    if startsWithUncased(str, prefix) then str.substring(prefix.length) else str
  }

}

object pathExts {
  extension (p: Path) {
    def exists: Boolean = Files.exists(p)
    def isDirectory: Boolean = Files.isDirectory(p)
    def isFile: Boolean = Files.isRegularFile(p)
    def name: String = p.getFileName.toString
    def basename: String = {
      val n = p.getFileName.toString
      val i = n.lastIndexOf('.')
      if i == -1 then n else n.substring(0, i)
    }
    def relativePath: Path = relativePathToCwd(p)
    def relpath: String = {
      val rp: Path = relativePathToCwd(p)
      standardizePath(rp)
    }

    def abs: String =
      if (java.nio.file.Files.exists(p))
        p.toAbsolutePath.normalize.toString.replace('\\', '/')
      else
        p.normalize.toString.replace('\\', '/')

    def firstline: String = lines.nextOption.getOrElse("")
    def lines: Iterator[String] = {
      try {
        Files.readAllLines(p, UTF_8).asScala.iterator
      } catch {
      case m: java.nio.charset.MalformedInputException =>
         Files.readAllLines(p, Latin1).asScala.iterator
      }
    }
    def csvRows: Iterator[Seq[String]] = {
      uni.io.FastCsv.rowsAsync(p)
    }
    def csvRows(onRow: Seq[String] => Unit): Unit = {
      uni.io.FastCsv.eachRow(p){ (row: Seq[String]) =>
        onRow(row)
      }
    }
    def lines(charset: Charset = UTF_8): Iterator[String] = {
      scala.io.Source.fromFile(p.toFile).getLines
    }
    def contentAsString(charset: Charset = UTF_8): String = Files.readString(p, charset)
    def contentAsString: String = {
      try {
        Files.readString(p, UTF_8)
      } catch {
      case m: java.nio.charset.MalformedInputException =>
        Files.readString(p, Latin1)
      }
    }
    def isSymbolicLink: Boolean = Files.isSymbolicLink(p)
    def stdpath: String = standardizePath(p)
    def getParentNonNull: Path = Option(p.getParent).getOrElse(p) // dirname convention: `dirname :/` == /
    def getParentPath: Path = Option(p.getParent).getOrElse(p.toAbsolutePath.normalize.getParent)
    def parent: Path = p.toAbsolutePath.getParent
    def isSameFile(other: Any): Boolean = {
      try {
        other match {
          case otherPath: Path =>
            sameFileTest(p, otherPath)
          case _ =>
            false
        }
      } catch {
        case _: Exception => false
      }
    }
    def localpath: String = p.toString.replace('\\', '/')

    def dospath: String = {
      val pstr = p.toString
      pstr match {
        case "." => "."
        case s if !isWin || s.length > 2 =>
          s
        case s if s.endsWith(":") =>
          if rootDrives.contains(s.toUpperCase) then
            p.toAbsolutePath.toString
          else
            p.toFile.getAbsolutePath.toString
        case s =>
          s
      }
    }
    def files: Iterator[JFile] = Option(p.toFile.listFiles) match {
      case Some(arr) => arr.iterator
      case None      => Iterator.empty
    }
    def paths: Iterator[Path] = files.map(_.toPath)
    def posx: String = {
      if (p == null) {
        hook += 1
      }
      p.toString.replace('\\', '/')
    }
    def posix: String = posixAbs(p.toString)

    def local: String = {
      p.toString.replace('\\', '/')
    }
    def dotsuffix: String = {
      val name = p.getFileName.toString
      val idx  = name.lastIndexOf('.')
      if idx > 0 then name.substring(idx) else ""
    }
    def suffix: String = {
      val ext = dotsuffix
      if ext.nonEmpty then ext.drop(1) else ""
    }
    def newerThan(other: Path): Boolean = {
      p.isFile && other.isFile && other.lastModified > p.lastModified
    }
    def lastModified: Long = p.toFile.lastModified
    def lastModMillisAgo: Long = System.currentTimeMillis - p.toFile.lastModified

    def lastModSecondsAgo: Double   = lastModMillisAgo / 1000.0
    def lastModMinutesAgo: Double   = fs.round(lastModSecondsAgo / 60.0)
    def lastModHoursAgo: Double     = fs.round(lastModMinutesAgo / 60.0)
    def lastModDaysAgo: Double      = fs.round(lastModHoursAgo / 24.0)

    def lastModSeconds: Double = lastModSecondsAgo // alias
    def lastModMinutes: Double = lastModMinutesAgo // alias
    def lastModHours: Double   = lastModHoursAgo   // alias
    def lastModDays: Double    = lastModDaysAgo    // alias

    def lastModifiedYMD: String = {
      def lastModified = p.toFile.lastModified
      val date         = new java.util.Date(lastModified)
      val ymdHms       = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      ymdHms.format(date)
    }

    /** Copy this Path to the given destination Path.
      * By default overwrites if the target exists.
      */
    def copyTo(dest: Path, overwrite: Boolean = true, copyAttributes: Boolean = false): Path = {
      val options =
        if (overwrite && copyAttributes)
          Array(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        else if (overwrite)
          Array(StandardCopyOption.REPLACE_EXISTING)
        else if (copyAttributes)
          Array(StandardCopyOption.COPY_ATTRIBUTES)
        else
          Array.empty[StandardCopyOption]

      Files.copy(p, dest, options*)
      dest
    }
    /** Recursively iterate all files and directories under this Path. */
    def pathsTree: Iterator[Path] =
      if Files.exists(p) then
        Files.walk(p).iterator().asScala
      else
        Iterator.empty
  }
}

object fs {
  export pathExts.*
  export stringExts.*

  def round(number: Double, scale: Int = 6): Double =
    BigDecimal(number).setScale(scale, RoundingMode.HALF_UP).toDouble


  def isValidWindowsPath(s: String): Boolean = {
    val t = s.trim

    val driveAbs   = "^[A-Za-z]:[\\\\/].*".r
    val unc        = "^\\\\\\\\[^\\\\]+\\\\[^\\\\]+.*".r
    val device     = "^\\\\\\\\[.?]\\\\.*".r
    val winRel     = "^[^/]*\\\\.*".r
    val embedded   = ".*[A-Za-z]:[\\\\/].*".r

    val valid = t match
      case driveAbs()  => true
      case unc()       => true
      case device()    => true
      case winRel()    => true
      case embedded()  => true
      case _           => false

    valid // single exit
  }

  def isValidMsysPath(s: String): Boolean = {
    val t = s.trim

    val tildeUser = "^~[A-Za-z0-9._-]+/.*".r
    val msysDrive = "^/[A-Za-z]/.*".r
    val optValue  = """.*=[^=]*[/~].*""".r

    // 1. Tilde paths
    val valid = if t == "~" then true
    else if t.startsWith("~/") then true
    else if tildeUser.matches(t) then true

    // 2. Rooted POSIX paths
    else if t.startsWith("/") && !t.startsWith("//") then true

    // 3. MSYS drive paths (/c/foo)
    else if msysDrive.matches(t) then true

    // 4. Relative POSIX paths containing '/'
    else if t.contains("/") && !isValidWindowsPath(t) then true

    // 5. Option-value forms (--foo=/bar, --foo=~/baz)
    else if optValue.matches(t) && !isValidWindowsPath(t) then true

    else false

    valid // single exit
  }

  export Internals.call as call
  export Internals.spawn as spawn
  export System.err.printf as eprintf
  export scala.util.Properties.{isLinux}
}

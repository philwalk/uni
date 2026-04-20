//#!/usr/bin/env -S scala-cli --cli-version nightly shebang -deprecation -q
package uni

import java.io.{File as JFile}
import java.nio.file.{Path, Files, Paths as JPaths}
import java.util.Locale
import scala.collection.immutable.SortedMap
import scala.util.Properties
import uni.data.*
import uni.time.*

import scala.concurrent.ExecutionContext
given ExecutionContext = ExecutionContext.global

export scala.util.Properties.{isWin, isMac, isLinux}
export Proc.{ProcResult, ProcBuilder, run, proc, bashExe, pythonExe, unameExe, uname, osType, where, isWsl, hostname}
export System.err.print as eprint // returns Unit
def eprintln(s: String): Unit = System.err.print(s"$s\n")
def withFileWriter(p: Path, charsetName: String = "UTF-8", append: Boolean = false)(func: java.io.PrintWriter => Any): Unit =
  uni.io.FileOps.withFileWriter(p, charsetName, append)(func)

lazy val verboseUni: Boolean = Option(System.getenv("VERBOSE_UNI")).nonEmpty

val userhome: String = System.getProperty("user.home").replace('\\', '/')

def tmpDir: String =
  Seq("/f/tmp", "/g/tmp", "/tmp")
    .find { s => java.nio.file.Files.isDirectory(java.nio.file.Paths.get(s)) }
    .getOrElse(System.getProperty("java.io.tmpdir"))
    .replace('\\', '/')

// wrapper method better than `export System.err.printf as eprintf` due to `Unit` return.
def eprintf(format: String, args: Any*): Unit =
  System.err.printf(format, args*)

import scala.util.boundary, boundary.break

extension (status: Int)
  /** Log msg to stderr if status != 0; return status. Chainable. */
  def !!(msg: String): Int =
    if status != 0 then eprintln(s"$msg [$status]")
    status
  /** Invoke f with error description if status != 0; return status. */
  infix def orElse(f: String => Unit): Int =
    if status != 0 then f(s"exit status: $status")
    status
  /** Within failFast { }, break out of the block on non-zero status. */
  infix def orFail(msg: String)(using label: boundary.Label[Int]): Int =
    if status != 0 then
      eprintln(s"$msg [$status]")
      break(status)
    status

/** Run body; any .orFail call inside short-circuits the block on failure. */
def failFast(body: boundary.Label[Int] ?=> Int): Int = boundary(body)

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
def showLimitedStack(e: Throwable = new RuntimeException("limited-stack")): Unit = {
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
inline def showMinimalStack(e: Exception = new RuntimeException("showMinimalStack")): Unit = {
  val lcMain = progName.toLowerCase
  withFilteredStack(e) { elem =>
    elem.toString.toLowerCase.contains(lcMain)
  }
}

lazy val userHome = sys.props("user.home").replace('\\', '/')

// object Proc lives in ProcUtils.scala

lazy val pwd: Path = JPaths.get(config.userdir)

def isWinshell: Boolean = isWin && Properties.propOrNone("MSYSTEM").nonEmpty

private[uni] object Internals {

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

  private def defaultDriveLetter: String = {
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
      s1.equalsIgnoreCase(s2)
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

  def exists(fname: String): Boolean = Files.exists(JPaths.get(fname))

  def standardizePath(p: Path): String = {
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

  private def _osName: String = sys.props("os.name")

  lazy val _osType: String = _osName.toLowerCase(Locale.ROOT) match {
  case s if s.contains("windows")  => "windows"
  case s if s.contains("linux")    => "linux"
  case s if s.contains("mac os x") => "darwin"
  case other =>
    sys.error(s"osType is [$other]")
  }
 
  //def shellRoot: String = if isWin then call("cygpath.exe", "-m", "/").getOrElse("") else ""

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

  private[uni] def rootDrives: Seq[String] = 
    Option(java.io.File.listRoots())
      .fold(Seq.empty[String])(_.map(_.getAbsolutePath.take(2)).toSeq)

  def safeAbsolutePath(p: Path): Path =
    if !isWin then
      p.toAbsolutePath
    else
      val s = p.toString

      // Detect drive-only path like "X:"
      val isDriveOnly =
        s.length == 2 &&
        s(1) == ':' &&
        s(0).isLetter

      if isDriveOnly then
        val drive = s(0)
        val root = new java.io.File(s"$drive:/")
        if root.exists() then
          p.toAbsolutePath
        else
          Paths.get(s"$drive:/")   // canonical absolute root
      else
        p.toAbsolutePath

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

private def noTrailingSlash(p: String): String =
  if p == "/" then
    "/"
  else if p.length >= 3 && p(1) == ':' && p(2) == '/' then
    if p.length > 3 then
      p.stripSuffix("/")
    else
      p
  else
    p.stripSuffix("/")

private def normalizePosix(p: Path): String =
  normalizePosix(p.toString)

private def normalizePosix(p: String): String =
  if p.trim.matches("C:[\\/]") then
    hook += 1
  val str = p.replace('\\', '/')
  if str == "/" then "/"
  else noTrailingSlash(str)

/** joined string normalized to never have trailing slash unless == "/" */
private def joinPosix(prefix: String, suffix: String): String =
  val pre  = prefix.stripSuffix("/")
  val post = s"/${suffix.stripPrefix("/")}"
  noTrailingSlash(s"$pre$post")


def stringAbs(raw: String): String = {
  Resolver.resolvePathstr(raw)
}

def applyTildeAndDots(raw: String): String = {
  require(!raw.contains('\\'))
  if raw.isEmpty || raw == "." then
    config.userdir

  else if raw == ".." then
    config.userdirParent

  else
    raw(0) match
      case '~' =>
        // user home
        if raw.length == 1 then
          config.userhome
        else
          config.userhome + raw.substring(1)

      case '.' =>
        // handle ./foo and ../foo
        if raw.startsWith("./") then
          config.userdir + raw.substring(1)

        else if raw.startsWith("../") then
          val parent = config.userdirParent.stripSuffix("/")
          val suffix = raw.substring(2).stripPrefix("/")
          s"$parent/$suffix"

        else
          // preserve the leading dot of hidden files like ".gitignore"
          if (raw.startsWith(".")) {
             s"${config.userdir.stripSuffix("/")}/$raw"
          } else {
             config.userdir + raw.substring(1)
          }

      case _ =>
        // treat only true bare filenames as relative
        if raw.length == 2 && raw(1) == ':' then
          s"${config.driveCwd(raw(0))}"
        else if !raw.contains('/') then
          s"${config.userdir}/$raw"
        else
          raw
}

def quikResolve(raw: String): Path = {
  val s = applyTildeAndDots(raw)
  JPaths.get(s).toAbsolutePath.normalize
}

inline private def parentDirOf(s: String): String =
  val i = s.lastIndexOf('/')
  if i <= 0 then "/" else s.substring(0, i)


/*
 * This method only converts if `isWin`, otherwise it's almost a pass-through.
 * Output is a POSIX-style String.
 * In Windows:
 *   convert rawstr path to `cygpath -u` format
 *   in some cases java sees a different path than cygpath; defer to java.
 */
def posixAbs(raw: String): String = {
  if !isWin then
    Resolver.resolvePathstr(raw) match {
    case "/" => "/"
    case s   => s.stripSuffix("/")
    }
    
  else if raw.startsWith("/") then
    noTrailingSlash(raw)
  else {
    if raw == "file.txt" then
      hook += 1
    val cygMixed = Resolver.resolvePathstr(raw)
    val absPosix =
      if cygMixed.startsWithIgnoreCase(config.cygRoot) then
        cygMixed.drop(config.cygRoot.length)
      else {
//        val win2posx = config.win2posix.toSeq // TODO: remove IDE helper vals
//        val posx2win = config.posix2win.toSeq
        Resolver.findPrefix(cygMixed, config.win2posixKeys) match
          case Some(winPrefix) =>
            val suffix = cygMixed.drop(winPrefix.length).stripSuffix("/")
            config.win2posix.get(winPrefix) match
              case Some(posixSeq) =>
                joinPosix(posixSeq.head, suffix)
              case None =>
                winAbsToPosixAbs(cygMixed)

          case None =>
            // No matching Windows prefix at all → cygdrive fallback
            winAbsToPosixAbs(cygMixed)
      }
    absPosix
  }
}

// leverage posixAbs to deal with ~, trailing slash, etc.
def posixRel(raw: String): String =
  val cwd = posixAbs(config.userdir)
  val abs = posixAbs(raw)

  if abs.equalsIgnoreCase(cwd) then
    "."
  else if abs.startsWithIgnoreCase(cwd + "/") then
    abs.substring(cwd.length + 1)   // skip the slash
  else
    abs

def winAbsToPosixAbs(cygMixed: String): String =
  require(cygMixed.length > 1 && cygMixed(1) == ':', s"not a Windows abs path [$cygMixed]")
  val drive = cygMixed.take(1).toLowerCase
  val path  = cygMixed.drop(2)  // drop "C:"
  s"/$drive$path"

private inline def isDriveLetterPath(s: String): Boolean = {
  s.length >= 2 && s(1) == ':' && {
    val c = s(0)
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
  }
}

/**
 * Scans a CSV to determine the best Mat[T] specialization.
 * @param path File to scan
 * @param scanRows Number of rows to inspect (default 10)
 * @return A function (String => Any) tuned for that file's data
 */
def inferType(path: Path, scanRows: Int = 10): String => Any = {
  val rows = path.csvRowsStream.take(scanRows + 1).toVector // +1 for potential header
  val dataRows = if (rows.size > 1) rows.tail else rows
  
  if (dataRows.isEmpty) then
    (s: String) => s
  else
    // Sample a high-value column (or the first one) to check type
    // In a multi-column Mat, we usually pick the most common type across all samples
    val samples = for {
      row <- dataRows
      cell <- row.take(1) // Just testing the first column for this example
    } yield getMostSpecificType(cell)

    val hasDates = samples.exists(_.isInstanceOf[DateTime])
    val hasBigs  = samples.exists(_.isInstanceOf[BigDecimal])

    if (hasBigs)  (s: String) => str2num(s)
    else if (hasDates) (s: String) => parseDate(s)
    else (s: String) => s // Default to String
}

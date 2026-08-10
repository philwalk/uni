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
export Proc.{ProcResult, ProcBuilder, run, proc, execLines, bashExe, pythonExe, unameExe, uname, osType, where, whereInPath, isWsl, hostname}
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

private def isClientFrame(elem: StackTraceElement): Boolean = {
  val cls = elem.getClassName
  !cls.startsWith("java.") &&
  !cls.startsWith("javax.") &&
  !cls.startsWith("jdk.") &&
  !cls.startsWith("sun.") &&
  !cls.startsWith("oracle.") &&
  !cls.startsWith("scala.")
}

/*
 * Print a less verbose stack trace.
 */
def showLimitedStack(e: Throwable = new RuntimeException("limited-stack")): Unit = {
  withFilteredStack(e)(isClientFrame)
}

/*
 * The less verbose stack trace as a String; the `using` parameter matches the
 * vastblue.file.Util.getLimitedStackTrace signature, so `getLimitedStackTrace(using e)`
 * call sites port unchanged.
 */
def getLimitedStackTrace(using e: Throwable): String =
  (e.toString +: e.getStackTrace.filter(isClientFrame).map(elem => s"  at $elem").toSeq)
    .mkString("\n")

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

/** Working directory as the *current* config sees it.
 *
 *  A `def` so it tracks `withMountLines`, but the Path itself is cached per config
 *  instance (`PathsConfig.pwdPath`) — this is called often enough that rebuilding
 *  it each time would be waste, and the config is what invalidates it.
 */
def pwd: Path = config.pwdPath

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
    // Absolutise against the *config's* working directory, not the JVM's.
    // `toAbsolutePath` and `File.getAbsolutePath` both consult `user.dir`, which
    // `withMountLines` cannot change -- so a relative input was resolved against one
    // directory while the rest of resolution used another. In production they
    // coincide, which is why it went unnoticed; under an injected user `stdpath("a/b")`
    // named a file in the real working directory. Same defect that made `relpath`
    // point at the wrong file.
    val abs: Path = if p.isAbsolute then p else pwd.resolve(p)
    val winPath: String = if canExist(abs) then
      abs.normalize.toString
    else
      abs.toFile.getAbsolutePath // is this adequate?

    val pathstr = winPath
    if (!isWin) {
      pathstr
    } else {
      val pstr = pathstr.replace('\\', '/') match {
      case "/" => "/"
      case s => s.stripSuffix("/") // no trailing slash
      }

      // First check explicit mounts.
      //
      // Via `Resolver.findPrefix`, not a local scan. The scan this replaces was
      // `keys.filter(pstr.startsWithIgnoreCase).sortBy(-_.length).headOption` —
      // longest-first, but a plain string prefix with no segment boundary, so
      // `C:/msys64extra/x` matched the `c:/msys64` mount and was rewritten as if it
      // lived under it. `findPrefix` requires the next character to be '/' or ':',
      // and is the same matcher the rest of resolution uses.
      val w2pm = config.win2posix
      val maybeMount = Resolver.findPrefix(pstr, config.win2posixKeys)

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

  // `def`s for the same reason as `pwd`, which these derive from.
  def here  = pwd.toAbsolutePath.normalize.toString.toLowerCase(Locale.ROOT).replace('\\', '/')
  def uhere = here.replaceFirst("^[a-zA-Z]:", "")
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
    if !config.isWindows then
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

/** Removes trailing separators. `/` and a bare drive root keep theirs.
 *
 *  All of them, not one: this used `stripSuffix("/")`, so `/usr/bin//` came back as
 *  `/usr/bin/` -- still trailing, from a function whose name says otherwise.
 */
private def noTrailingSlash(p: String): String =
  if p == "/" then "/"
  else
    val bare = p.replaceAll("/+$", "")
    // All separators (`//`, `///`) reduces to the root; an *empty* input stays empty.
    // Conflating them turned `""` into `/`, which then resolved as the msys root.
    if bare.isEmpty then (if p.isEmpty then "" else "/")
    // `C:/` is the drive root and keeps its separator; bare `C:` is drive-relative and
    // must not gain one. Preserve, never add.
    else if bare.length == 2 && bare(1) == ':' && p.length > 2 then s"$bare/"
    else bare

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
        if config.isWindows && raw.length >= 2 && raw(1) == ':' then
          // A drive letter belongs to `Resolver.classify`, which routes it to
          // `resolveDriveRelPathstr` -- the one place that knows the per-drive
          // working directory. Resolving it here got both drive-relative forms
          // wrong: bare `C:` came back with a `.` component still attached
          // (`driveCwd` built it from `C:.`), and single-segment `C:foo` -- having
          // no '/' -- fell into the bare-filename branch below and was glued onto
          // userdir as `.../uni/C:foo`, which java.nio rejected outright.
          //
          // Guarded by `isWin` because on Linux and macOS `C:foo` is an ordinary
          // filename that happens to contain a colon, and must stay relative to
          // userdir. `C:/foo` reaches the same branch and is equally fine to pass
          // through -- classify calls it Absolute and leaves it alone.
          raw
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
/** Plumbing behind `Path.posix` and `String.posix`. Prefer those. */
@deprecated("Use `Path.posix` or `String.posix`; this becomes private[uni]", "0.16.0")
def posixAbs(raw: String): String = toPosixAbs(raw)

/** True when `pathstr` is the mount root itself, or lies beneath it.
 *
 *  A plain `startsWith` is not enough: `C:/msys64extra` starts with the `C:/msys64`
 *  root as a *string* while living somewhere else entirely, and rewriting it as
 *  though it were inside produced `extra` -- a relative string standing in for an
 *  absolute path. `Resolver.findPrefix` is the matcher the rest of resolution uses:
 *  it requires the next character to be '/' or ':', and treats an exact match as a
 *  match. `standardizePath` had the same defect and was routed here first.
 */
private inline def isUnderRoot(pathstr: String, root: String): Boolean =
  Resolver.findPrefix(pathstr, Array(root.toLowerCase(java.util.Locale.ROOT))).nonEmpty

/** Implementation of [[posixAbs]], callable from inside the library without
 *  tripping the deprecation warning. When `posixAbs` is finally narrowed, this
 *  becomes the only form and the wrapper above just disappears. */
private[uni] def toPosixAbs(raw0: String): String = {
  // Compare the POSIX form. The extension methods pass `Path.toString`, which on
  // Windows renders UNC as `\server\share` -- that failed the `startsWith("/")`
  // test below, fell through to the drive-letter fallback and threw. So
  // `Paths.get("//server/share").posix` failed while `posixAbs("//server/share")` --
  // the same path, forward slashes -- succeeded. `normalizePosix` swaps separators
  // and strips trailing slashes; interior ones are untouched, so UNC survives.
  val raw = normalizePosix(raw0)
  if !config.isWindows then
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
    if Resolver.classify(cygMixed) == Resolver.Relative then
      // A relative path has no absolute POSIX form, and resolution deliberately
      // leaves it alone: `applyTildeAndDots` absolutises a bare filename but
      // treats anything containing a slash as already a path. Such a value used to
      // fall through to the cygdrive branch and fail `winAbsToPosixAbs`'s
      // `require(cygMixed(1) == ':')`, so `Paths.get("a/b").posix` threw while
      // `Paths.get("bare.txt").posix` succeeded. Preserve it instead, which is
      // what `cygpath -u a/b` does.
      normalizePosix(cygMixed)
    else if isUnderRoot(cygMixed, config.cygRoot) then
      val rest = cygMixed.drop(config.cygRoot.length)
      // The root itself maps to "/", not to "". Dropping the whole prefix leaves
      // nothing, so `toPosixAbs("C:/msys64")` -- and therefore `Paths.get("/").posix`
      // -- returned the empty string for the root of the filesystem.
      if rest.isEmpty || rest == "/" then "/" else rest
    else
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
}

/** Plumbing behind `Path.relpath`. Prefer that. */
@deprecated("Use `Path.relpath`; this becomes private[uni]", "0.16.0")
def posixRel(raw: String): String = toPosixRel(raw)

/** Implementation of [[posixRel]]; see [[toPosixAbs]] for why the pair exists.
 *  Leverages `toPosixAbs` to deal with `~`, trailing slashes and the mount map. */
private[uni] def toPosixRel(raw: String): String =
  val cwd = toPosixAbs(config.userdir)
  val abs = toPosixAbs(raw)

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

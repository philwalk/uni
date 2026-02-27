package uni

import java.nio.file.{Files, Paths as JPaths, Path}
import java.net.URI
import java.util.{Arrays, Comparator, Locale}
import scala.collection.immutable.SortedMap

//export java.nio.file.Path
export java.io.File as JFile

/* This library wraps calls to java.nio.file.Paths.get() for the purpose of providing a `uni.Paths.get`
 * with support for adding msys2 and cygwin paths support (based on /etc/fstab mount maps).
 * Calls to `uni.Paths.get` convert path strings to `java.nio.file.Path` objects.
 * If a path string is not already a legal Windows path string,
 * this library is responsible for converting such msys2 / cygwin path strings to valid Windows equivalent
 * prior to calling `java.nio.file.Paths.get(...)`.
 * Therefore, every such Path value produced is based on a legal Windows path string.
 *
 * The applied conversion is almost exactly equivalent to `cygpath -m <pathstring>`, although there are
 * exceptions:
 */

object Paths {

  // API same as java.nio.file.Paths.get
  def get(first: String, more: String*): Path =
    if first.startsWith("file://") && more.isEmpty then
      // explicit URI semantics
      get(java.net.URI.create(first))
    else
      config.get(first, more *)

  def get(uri: URI): Path =
    config.get(uri)

}

@volatile private[uni] var config: PathsConfig = DefaultPathsConfig // mutable test seam
def shellRoot: String = config.msysRoot

type Win2posixMap = LcLookupMap[Seq[String]]
type Posix2winMap = LcLookupMap[String]

// Config contract
trait PathsConfig {
  def get(first: String, more: String*): Path
  def get(uri: URI): Path
  def cygdrive: String
  def win2posix: Win2posixMap
  def posix2win: Posix2winMap
  def msysRoot: String
  def cygRoot = msysRoot // alias

  def username: String
  def userhome: String
  def userdir: String

  lazy val userdirParent: String =
    val i = userdir.lastIndexOf('/')
    if i <= 0 then "/" else userdir.substring(0, i)

  lazy val posix2winKeys: Array[String] = keysArray(posix2win)
  lazy val win2posixKeys: Array[String] = keysArray(win2posix)

  private def keysArray(map: Posix2winMap | Win2posixMap): Array[String] =
    val arr = map.keysIterator.map(_.toLowerCase).toArray
    Arrays.sort(arr, Comparator.comparingInt[String](_.length).reversed())
    arr

  def driveCwd(drive: Char): Path
}

// Default config: spawns mount.exe and parses stdout lazily
object DefaultPathsConfig extends PathsConfig {
  private lazy val mountInfo: MountMaps = ParseMounts.parseMountLines(MountExe.lines())
  def msysRoot: String = mountInfo.msysRoot
  def cygdrive: String = mountInfo.cygdrive
  def win2posix: Win2posixMap = mountInfo.win2posix
  def posix2win: Posix2winMap = mountInfo.posix2win
  def get(first: String, more: String*): Path = Resolver.resolvePath(first, more)
  def get(uri: URI): Path = Resolver.resolvePath(uri)
  def username: String = realUserName
  def userhome: String = realUserHome
  def userdir: String  = realUserDir
  def driveCwd(drive: Char): Path =
    val upper = drive.toUpper
    require(upper.isLetter, s"Not a valid drive letter: $drive")
    // Query the JVM for the drive’s working directory
    val p = java.nio.file.Paths.get(s"$upper:.")
    if Files.exists(p) then
      p.toAbsolutePath
    else
      java.nio.file.Paths.get(s"$upper:/")
}

private lazy val realUserName: String = sys.props("user.name")
private lazy val realUserHome: String = normalizePosix(sys.props("user.home"))
private lazy val realUserDir: String  = normalizePosix(sys.props("user.dir"))

case class UserInfo(name: String, home: String, dir: String)
lazy val realUser: UserInfo = UserInfo(realUserName, realUserHome, realUserDir)

// Synthetic config: uses injected mount lines
final class SyntheticPathsConfig(mountLines: Seq[String], val user: UserInfo) extends PathsConfig {
  private val mountInfo: MountMaps = ParseMounts.parseMountLines(mountLines)
  def msysRoot: String = mountInfo.msysRoot
  def cygdrive: String = mountInfo.cygdrive
  def win2posix: Win2posixMap = mountInfo.win2posix
  def posix2win: Posix2winMap = mountInfo.posix2win
  def get(first: String, more: String*): Path = Resolver.resolvePath(first, more)
  def get(uri: URI): Path = Resolver.resolvePath(uri)
  def username: String = user.name
  def userhome: String = user.home
  def userdir: String = user.dir
  def driveCwd(drive: Char): Path =
    val upper = drive.toUpper
    require(upper.isLetter, s"Not a valid drive letter: $drive")
    // if `userdir` && drive letter matches `drive`, then
    if userdir(0).toUpper == upper then
      Paths.get(userdir)
    else
      // otherwise tests should assume this is the drive root
      Paths.get(s"$upper:/")
}

// inject mount lines for testing
private[uni] def withMountLines(mountLines: Seq[String], testUser: UserInfo): Unit = {
  config = new SyntheticPathsConfig(mountLines, testUser)
}

// restore default config
private[uni] def resetConfig(): Unit = config = DefaultPathsConfig

// canonical map container
final case class MountMaps(cygdrive: String, win2posix: Win2posixMap, posix2win: Posix2winMap) {
  val msysRoot = posix2win.getOrElse("/", "")
}

object Resolver {
  /* Five Windows path types:
   absolute:            F:/...
   drive-relative:      F:config/bin
   relative             ./bin
   Windows UNC path     //server/share or \\\\server\\\share
   msys-mounted:        /usr/bin
   */
  def resolvePath(first: String, more: Seq[String]): Path =
    val result = resolvePathstr(first, more)
    try {
      JPaths.get(result)
    } catch
      case e: Throwable =>
        hook += 1
        throw e

  enum WinPathKind:
    case Root, Absolute, UNC, Posix, Relative, DriveRel, Invalid

  export WinPathKind.*

  def classify(p: String): WinPathKind = {
    if p.contains("://") then Invalid
    else if p.startsWith("//") then UNC
    else if p == "/" then Root
    else if p.length >= 2 && p(1) == ':' then
      if p.length == 2 then
        DriveRel
      else if p(2) == '/' || p(2) == '\\' then
        Absolute
      else
        DriveRel
    else if p.startsWith("/") then Posix
    else Relative
  }

  /** Convert to a valid Windows path string */
  def resolvePathstr(first: String, more: Seq[String] = Seq.empty): String = {
    val pstr =
      val fname = (first +: more).mkString("/").replace('\\', '/')
      applyTildeAndDots(fname) // real or test user

    if !isWin then
      pstr
    else {
      resolveWindowsPathstr(pstr)
    }
  }

  // resolve to a syntactically valid Windows path string, not necessarily absolute.
  def resolveWindowsPathstr(pstr: String): String = {
    val pathType = classify(pstr)
    pathType match
      case Invalid =>
        sys.error(s"invalid path type: $pstr")
      case Absolute | UNC | Relative =>
        pstr // ok as-is
      case Root  =>
        config.msysRoot
      case Posix =>
        // get longest matching mount prefix
        val maybeMount = Resolver.findPrefix(pstr, config.posix2winKeys)
        maybeMount match {
          case Some(mountKey) =>
            val mountedWinPath = config.posix2win(mountKey) match
              case s if s.endsWith(":") =>
                s"$s/" // msys mounts are not drive-relative
              case s =>
                s
            val pstrTrim = stripTrailingSlash(pstr)
            val postPrefix = pstrTrim.drop(mountKey.length)
            s"$mountedWinPath$postPrefix"
          case None =>
            val root = config.posix2win("/")
            if pstr.startsWith(root) then pstr else s"$root$pstr"
        }
      case DriveRel =>
        resolveDriveRelPathstr(pstr)
  }

  def resolveDriveRelPathstr(pstr: String): String = {
    val drive   = pstr.charAt(0).toLower
    val cwd     = config.driveCwd(drive)
    val dir     = cwd.toString.replace('\\', '/')
    val dirbare = dir.stripSuffix("/")
    val suffix = pstr.substring(2)
    val pathstr = if suffix.isEmpty then dir else s"$dirbare/$suffix"
    pathstr
  }

  def stripTrailingSlash(pathstr: String): String =
    if pathstr.length <= 2 then pathstr
    else pathstr.stripSuffix("/")

  extension(s: String) {
    def stripLastSlash: String = stripTrailingSlash(s)
  }

  def resolvePath(uri: URI): Path = {
    val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
    val path: String = uri.getPath

    if (scheme.isEmpty || scheme == "file") && path != null && path.startsWith("/") then
      resolvePath(path, Nil)
    else
      JPaths.get(uri)
  }
  export PrefixFinder.findPrefix

  object PrefixFinder {

    /** get longest mount prefix from `win2posixKeys` or `posix2winKeys` */
    def findPrefix(pathstr: String, keys: Array[String]): Option[String] =
      val str = stripTrailingSlash(pathstr).toLowerCase(Locale.ROOT)
      mountPrefix(str, keys)

    // find the longest matching prefix in `keys`
    private inline def mountPrefix(s: String, keys: Array[String]): Option[String] = {
      @annotation.tailrec
      def loop(i: Int, best: String | Null): Option[String] =
        if i >= keys.length then
          Option(best)
        else {
          val h = keys(i)

          // Fast prefix check
          val matches =
            s.startsWith(h) &&
              (s.length == h.length || {
                val next = s.charAt(h.length)
                next == '/' || next == ':'
              })

          val newBest =
            if matches && (best == null || h.length > best.length) then h
            else best

          loop(i + 1, newBest)
        }

      loop(0, null)
    }
  }
}

/** Parsing /etc/fstab entries */
object ParseMounts {
  def parseMountLines(lines: Seq[String]): MountMaps = {
    // parse raw entries
    val rawEntries: Seq[(String, String)] =
      lines.flatMap { line =>
        if line.contains(" on ") then
          // mount.exe format
          val parts = line.split(" on | type ").map(_.trim)
          if parts.length >= 2 then Some(parts(0) -> parts(1)) else None
        else
          // fstab format
          val parts = line.trim.split("\\s+")
          if parts.length >= 2 then Some(parts(0) -> parts(1)) else None
      }

    // normalize windows + POSIX paths
    def stripSlash(s: String): String = s.replace('\\', '/') match
      case "/" => "/" // don't strip THIS suffix!
      case s   => s.stripSuffix("/")

    val entries: Seq[(String, String)] =
      rawEntries.map { case (w, p) =>
        stripSlash(w) -> stripSlash(p)
      }

    def isDriveRoot(s: String): Boolean = s.matches("^[A-Za-z]:$")

    // derive cygdrive
    val cygdrive: String = {
      entries.collectFirst {
        case (win, posix) => {
          if win == "none" then
            s"${posix.stripSuffix("/")}/"
          else if isDriveRoot(win) &&
            posix.startsWith("/") &&
            posix.length >= 3 &&
            posix.charAt(posix.length - 2) == '/'
          then
            posix.substring(0, posix.length - 1)
          else
            null
        }
      }.collectFirst { case p if p != null => p }
        .getOrElse("/")
    }

    def isRealDrive(posix: String): Boolean =
      posix == s"$cygdrive${posix.last}" &&
        posix.length == cygdrive.length + 1

    val posixDriveRefs: Set[Char] =
      entries.collect {
        case (_, posix) if isRealDrive(posix) =>
          posix.last.toLower
      }.toSet

    // add synthetic entries for all unmapped drive letters
    def missingDrives(cygdrive: String, real: Set[Char]): Seq[(String, String)] =
      ('A' to 'Z').flatMap { d =>
        val lower = d.toLower
        if real(lower) then None
        else Some(s"$d:" -> s"$cygdrive$lower")
      }

    val syntheticDrives =
      if isWin then missingDrives(cygdrive, posixDriveRefs) else Nil

    val hasRoot: Boolean = entries.exists(_._2 == "/")

    // synthesize root entry (if missing)
    val syntheticRoot: Seq[(String, String)] =
      if isWin && !hasRoot then
        Seq(MountExe.defaultMsysRoot -> "/")
      else
        Nil

    // --- combine all entries
    val allEntries: Seq[(String, String)] =
      (entries ++ syntheticDrives ++ syntheticRoot)
        .map { case (w, p) => stripSlash(w) -> stripSlash(p) }
        .distinct

    // --- build maps
    val winOrdering: Ordering[String] =
      Ordering.by[String, Int](_.length).orElse(Ordering.String)

    val posixOrdering: Ordering[String] =
      new Ordering[String] {
        def compare(a: String, b: String): Int = {
          if a == "/" && b == "/" then 0
          else if a == "/" then -1
          else if b == "/" then 1
          else {
            val lenCmp = a.length.compare(b.length)
            if lenCmp != 0 then lenCmp else a.compareTo(b)
          }
        }
      }

    val forwardMap: Win2posixMap =
      val grouped =
        allEntries.groupMap { case (win, _) =>
          win.toLowerCase(Locale.ROOT)
        } { case (_, posix) =>
          posix
        }
      val base = SortedMap.from(grouped)(using winOrdering)
      new LcLookupMap(base) // for case-insensitive lookups

    val reverseMap: Posix2winMap =
      val pairs = allEntries.map { case (win, posix) =>
        posix.toLowerCase(Locale.ROOT) -> win
      }
      val base = SortedMap.from(pairs)(using posixOrdering)
      new LcLookupMap(base) // for case-insensitive lookups

    MountMaps(cygdrive, forwardMap, reverseMap)
  }
}

// MountExe locator + stdout reader (production path)
object MountExe {
  val defaultMsysRoot: String = "C:/msys64"
  val defaultMountExe: String = s"$defaultMsysRoot/usr/bin/mount.exe"

  // Return mount.exe mountExe or empty string
  lazy val mountExe: String = {
    if !isWin then
      ""
    else
      Proc.call("where.exe", "mount.exe").getOrElse {
        // msys2, cygwin, Git-bash supported
        val p = JPaths.get(defaultMountExe)
        if (Files.exists(p)) defaultMountExe else ""
      }
  }

  // Spawn and capture lines, or Nil if unavailable
  def lines(): Seq[String] = if mountExe.nonEmpty then Proc.lazyLines(mountExe) else Nil
}

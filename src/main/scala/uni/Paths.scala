package uni

import java.nio.file.{Files, Paths as JPaths}
import java.net.URI
import java.util.{Arrays, Comparator, Locale}
import scala.collection.immutable.SortedMap

export java.io.File as JFile
type Path = java.nio.file.Path

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

  /** Whether Windows path rules apply.
   *
   *  A config field rather than a read of `scala.util.Properties.isWin`, so a test
   *  can exercise the Windows rules on Linux and macOS. That is not hypothetical
   *  tidiness: `isWin` came straight from `os.name`, which left 39 tests -- all of
   *  `SyntheticMountsSuite` among them -- unable to run anywhere but Windows, and
   *  made `PathParitySuite` demand a per-platform fixture. The Rust port already
   *  takes `is_windows` as data for exactly this reason, and its path-parity test
   *  checks the Windows rules from any host.
   *
   *  This selects *rules* only. Probing the actual machine -- `mount.exe`,
   *  `cygpath.exe`, `File.listRoots` -- still reads the real platform, and is
   *  already replaced wholesale by `SyntheticPathsConfig`.
   */
  def isWindows: Boolean

  lazy val userdirParent: String =
    val i = userdir.lastIndexOf('/')
    if i <= 0 then "/" else userdir.substring(0, i)

  /** `userdir` as a Path, built once per config instance.
   *
   *  Cached here rather than at top level because the config is the thing whose
   *  lifetime matters: `withMountLines` installs a new instance, so the cache is
   *  invalidated exactly when the user changes. A process-wide `lazy val` instead
   *  froze whichever value was seen first — correct in production, where the JVM's
   *  `user.dir` cannot change, but it meant an injected user was ignored and
   *  `Path.relpath` relativised against the wrong directory under test.
   */
  private[uni] lazy val pwdPath: Path = JPaths.get(userdir)

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
  private lazy val mountInfo: MountMaps = ParseMounts.parseMountLines(MountExe.lines(), isWindows)
  def msysRoot: String = mountInfo.msysRoot
  def cygdrive: String = mountInfo.cygdrive
  def win2posix: Win2posixMap = mountInfo.win2posix
  def posix2win: Posix2winMap = mountInfo.posix2win
  def get(first: String, more: String*): Path = Resolver.resolvePath(first, more)
  def get(uri: URI): Path = Resolver.resolvePath(uri)
  def username: String = realUserName
  def userhome: String = realUserHome
  def userdir: String  = realUserDir
  def isWindows: Boolean = scala.util.Properties.isWin
  def driveCwd(drive: Char): Path =
    val upper = drive.toUpper
    require(upper.isLetter, s"Not a valid drive letter: $drive")
    // Query the JVM for the drive's working directory. `C:` is drive-relative, so
    // `toAbsolutePath` resolves it against the current directory Windows keeps for
    // that drive.
    //
    // Built from `C:` and not `C:.` -- the dot survives resolution:
    // `Paths.get("C:.").toAbsolutePath` is `C:\dir\.` while
    // `Paths.get("C:").toAbsolutePath` is `C:\dir`. That stray component was
    // reaching callers. `normalize` is belt-and-braces for a cwd holding dot
    // segments of its own.
    val p = java.nio.file.Paths.get(s"$upper:").toAbsolutePath.normalize
    if Files.exists(p) then
      p
    else
      java.nio.file.Paths.get(s"$upper:/")
}

private lazy val realUserName: String = sys.props("user.name")
private lazy val realUserHome: String = normalizePosix(sys.props("user.home"))
private lazy val realUserDir: String  = normalizePosix(sys.props("user.dir"))

case class UserInfo(name: String, home: String, dir: String)
lazy val realUser: UserInfo = UserInfo(realUserName, realUserHome, realUserDir)

// Synthetic config: uses injected mount lines
final class SyntheticPathsConfig(
  mountLines: Seq[String],
  val user: UserInfo,
  val isWindows: Boolean = scala.util.Properties.isWin
) extends PathsConfig {
  // Passed rather than read back from `this`: `parseMountLines` runs while the
  // config is still being constructed, so `isWindows` is not reachable through the
  // usual field access yet.
  private val mountInfo: MountMaps = ParseMounts.parseMountLines(mountLines, isWindows)
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
private[uni] def withMountLines(
  mountLines: Seq[String],
  testUser: UserInfo,
  isWindows: Boolean = scala.util.Properties.isWin
): Unit = {
  if verboseUni then
    // PathSpec
    print(s"============== set SyntheticPathsConfig for mountMap[${mountLines.mkString("\n")}] and testUser [${testUser}]")
  config = new SyntheticPathsConfig(mountLines, testUser, isWindows)
}

// restore default config
private[uni] def resetConfig(): Unit = config = {
  if verboseUni then
    print("================ reset SyntheticPathsConfig\n")
  DefaultPathsConfig
}

// canonical map container
final case class MountMaps(cygdrive: String, win2posix: Win2posixMap, posix2win: Posix2winMap) {
  val msysRoot = posix2win.getOrElse("/", "")
}

private[uni] object Resolver {
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
    if p.indexOf("://") > 1 then Invalid  // URI scheme (file://, https://); drive letter is 1 char so C:// is Absolute
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

    if !config.isWindows then
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
            val target     = config.posix2win(mountKey)
            val pstrTrim   = stripTrailingSlash(pstr)
            val postPrefix = pstrTrim.drop(mountKey.length)
            if postPrefix.isEmpty then
              // A bare drive target must keep its separator to stay absolute:
              // `C:` alone is drive-relative, `C:/` is the drive root.
              if target.endsWith(":") then s"$target/" else target
            else
              // postPrefix already begins with '/', so appending one to a bare
              // drive target duplicated it — `/c/Users` produced `C://Users`.
              // JPaths.get collapses that, which is why it went unnoticed, but
              // the raw string is what other implementations have to reproduce.
              s"$target$postPrefix"
          case None =>
            val root = config.posix2win("/")
            if pstr.startsWith(root) then pstr else s"$root$pstr"
        }
      case DriveRel =>
        resolveDriveRelPathstr(pstr)
  }

  private def resolveDriveRelPathstr(pstr: String): String = {
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

  private object PrefixFinder {

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
  def parseMountLines(lines: Seq[String], isWindows: Boolean): MountMaps = {
    // Drop comments and blanks before anything else.
    //
    // Only fstab has them, but it has a lot: the shipped MSYS2 file carries 16
    // comment lines, several of which are commented-out *example mounts written
    // without a space after the `#`* —
    //   #C:/cygwin64 / ntfs binary,noacl,auto
    //   #C:/cygwin64/usr/bin /bin ntfs binary,noacl,auto
    // Split on whitespace those yield ("#C:/cygwin64", "/") and friends, i.e. live
    // mount entries whose Windows side starts with '#'. Resolution then built
    // strings like `#C://Users`, and JPaths.get threw InvalidPathException because
    // the colon was no longer at index 1 — ordinary inputs such as /c/Users failed
    // outright. A comment-derived `/c` entry also satisfied isRealDrive and so
    // suppressed the synthetic C: drive that should have mapped it.
    val uncommented: Seq[String] =
      lines.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#"))

    // parse raw entries
    val rawEntries: Seq[(String, String)] =
      uncommented.flatMap { line =>
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

    // Derive cygdrive from the first entry that declares it: either a `none`
    // device (the shipped `none / cygdrive ...` directive, whose own comment says
    // "It removes cygdrive prefix from path") or a drive-root mount such as
    // `C: on /cygdrive/c`.
    //
    // The guards matter. Without them the pattern `case (win, posix)` matches
    // every tuple, so collectFirst is satisfied by entry 0 and never scans on —
    // the prefix then silently defaulted to "/" unless the declaring line
    // happened to come first. On Cygwin, where `mount` does not list the
    // drive-root line first, that left `/cygdrive/...` resolving through the
    // leftover `none` entry and emitting the device name literally.
    val cygdrive: String =
      entries.collectFirst {
        case (win, posix) if win == "none" =>
          s"${posix.stripSuffix("/")}/"
        case (win, posix) if isDriveRoot(win) &&
          posix.startsWith("/") &&
          posix.length >= 3 &&
          posix.charAt(posix.length - 2) == '/' =>
          posix.substring(0, posix.length - 1)
      }.getOrElse("/")

    // `none` is a directive, not a device: it declares the cygdrive prefix above
    // and mounts nothing. Having informed `cygdrive`, it must not reach the maps —
    // otherwise the shipped `none / cygdrive ...` line makes msysRoot the literal
    // string "none", and `/` resolves to it.
    val mountable: Seq[(String, String)] = entries.filterNot(_._1 == "none")

    def isRealDrive(posix: String): Boolean =
      posix == s"$cygdrive${posix.last}" &&
        posix.length == cygdrive.length + 1

    val posixDriveRefs: Set[Char] =
      mountable.collect {
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
      if isWindows then missingDrives(cygdrive, posixDriveRefs) else Nil

    val hasRoot: Boolean = mountable.exists(_._2 == "/")

    // synthesize root entry (if missing)
    val syntheticRoot: Seq[(String, String)] =
      if isWindows && !hasRoot then
        Seq(MountExe.defaultMsysRoot -> "/")
      else
        Nil

    // --- combine all entries
    val allEntries: Seq[(String, String)] =
      (mountable ++ syntheticDrives ++ syntheticRoot)
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
  private val defaultMountExe: String = s"$defaultMsysRoot/usr/bin/mount.exe"

  // Return mount.exe mountExe or empty string
  private lazy val mountExe: String = {
    if !isWin then
      ""
    else
      run("where.exe", "mount.exe").lines.headOption.getOrElse {
        // msys2, cygwin, Git-bash supported
        val p = JPaths.get(defaultMountExe)
        if (Files.exists(p)) defaultMountExe else ""
      }
  }

  // Spawn and capture lines, or Nil if unavailable
  def lines(): Seq[String] = if mountExe.nonEmpty then Proc.lazyLines(mountExe) else Nil
}

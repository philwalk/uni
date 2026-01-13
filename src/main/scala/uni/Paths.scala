package uni

import uni.fs.applyTilde

import java.nio.file.{Files, Paths as JPaths}
import java.net.URI
import java.util.{Arrays, Comparator, Locale}
import scala.collection.immutable.SortedMap

export java.nio.file.Path
export java.io.File as JFile

/* java.nio.file.Paths wrapper adds msys2 path support. */
object Paths {
  // API same as java.nio.file.Paths.get
  def get(first: String, more: String*): Path = config.get(first, more*)
  def get(uri: URI): Path = config.get(uri)
}

@volatile private[uni] var config: PathsConfig = DefaultPathsConfig // mutable test seam

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

  lazy val posix2winKeys: Array[String] = keysArray(posix2win)
  lazy val win2posixKeys: Array[String] = keysArray(win2posix)

  private def keysArray(map: Posix2winMap | Win2posixMap): Array[String] =
    val arr = map.keysIterator.map(_.toLowerCase).toArray
    Arrays.sort(arr, Comparator.comparingInt[String](_.length).reversed())
    arr
}

// Default config: spawns mount.exe and parses stdout lazily
object DefaultPathsConfig extends PathsConfig {
  private lazy val mountInfo: MountMaps = ParseMounts.parseMountLines(MountExe.lines())
  def msysRoot: String = mountInfo.msysRoot
  def cygdrive: String = mountInfo.cygdrive
  def win2posix: Win2posixMap = mountInfo.win2posix
  def posix2win: Posix2winMap = mountInfo.posix2win
  def get(first: String, more: String*): Path = Resolver.resolvePath(first, more, mountInfo)
  def get(uri: URI): Path = Resolver.resolvePath(uri, mountInfo)
}

// Synthetic config: uses injected mount lines
final class SyntheticPathsConfig(mountLines: Seq[String]) extends PathsConfig {
  private lazy val mountInfo: MountMaps = ParseMounts.parseMountLines(mountLines)
  def msysRoot: String = mountInfo.msysRoot
  def cygdrive: String = mountInfo.cygdrive
  def win2posix: Win2posixMap = mountInfo.win2posix
  def posix2win: Posix2winMap = mountInfo.posix2win
  def get(first: String, more: String*): Path = Resolver.resolvePath(first, more, mountInfo)
  def get(uri: URI): Path = Resolver.resolvePath(uri, mountInfo)
}

// inject mount lines for testing
private[uni] def withMountLines(mountLines: Seq[String]): Unit = config = new SyntheticPathsConfig(mountLines)

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
  inline def resolvePath(first: String, more: Seq[String], mountInfo: MountMaps): Path =
    val result = resolvePathstr(first, more, mountInfo)
    JPaths.get(result)

  enum WinPathKind:
    case Root, Absolute, UNC, Posix, Relative, DriveRel

  import WinPathKind.*

  private def classify(p: String): WinPathKind =
    if p.startsWith("//") then UNC
    else if p == "/" then Root
    else if p.length >= 2 && p(1) == ':' then
      if p.length >= 3 && p(2) == '/' then Absolute
      else DriveRel
    else if p.startsWith("/") then Posix
    else Relative

  /** Convert to a valid Windows path string */
  def resolvePathstr(first: String, more: Seq[String], mountInfo: MountMaps): String = {
    val pstr =
      val fname = (first +: more).mkString("/").replace('\\', '/')
      applyTilde(fname)

    if !isWin then
      pstr
    else {
      val pathType = classify(pstr)
      pathType match
        case Absolute | DriveRel | UNC | Relative =>
          pstr // ok as-is
        case Root  => config.msysRoot
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
    }
  }

  def stripTrailingSlash(pathstr: String): String =
    if pathstr.length <= 2 then pathstr
    else pathstr.stripSuffix("/")

  def resolvePath(uri: URI, mountInfo: MountMaps): Path = {
    val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
    val path: String = uri.getPath

    if (scheme.isEmpty || scheme == "file") && path != null && path.startsWith("/") then
      resolvePath(path, Nil, mountInfo)
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
        val parts = line.split(" on | type ").map(_.trim)
        if parts.length >= 2 then Some(parts(0) -> parts(1)) else None
      }

    // normalize windows + POSIX paths
    def fixup(s: String): String = s.replace('\\', '/') match
      case "/" => "/" // don't strip THIS suffix!
      case s   => s.stripSuffix("/")

    val entries: Seq[(String, String)] =
      rawEntries.map { case (w, p) =>
        fixup(w) -> fixup(p)
      }

    def isDriveRoot(s: String): Boolean = s.matches("^[A-Za-z]:$")

    // derive cygdrive
    val cygdrive: String = {
      entries.collectFirst {
        case (win, posix) => {
          if isDriveRoot(win) &&
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
        .map { case (w, p) => fixup(w) -> fixup(p) }
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

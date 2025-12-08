//#!/usr/bin/env -S scala-cli --cli-version nightly shebang -deprecation -q
package uni

import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{Files, Path, Paths as JPaths, StandardCopyOption}
import java.net.URI
import java.util.Locale
import scala.collection.immutable.SortedMap
import scala.sys.process.*
import scala.util.Try
import scala.util.Properties
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.RoundingMode

export java.nio.file.Path
export java.io.File as JFile
export scala.util.Properties.{isWin, isMac, isLinux}

// A scala msys2-cygpath-aware rendition of Paths.get
object Paths {
  // Public API: same as java.nio.file.Paths.get
  def get(first: String, more: String*): Path = {
    config.get(first, more: _*)
  }

  // Public API: same as java.nio.file.Paths.get
  def get(uri: URI): Path = {
    config.get(uri)
  }
}

@volatile private[uni] var config: PathsConfig = DefaultPathsConfig
// Cache the candidate keys once, lowercased for comparison
@volatile private[uni] var posix2winKeys: List[String] = posix2winKeysCurrent

private[uni] def posix2winKeysCurrent: List[String] = config.posix2win.keysIterator.map(_.toLowerCase).toList
def updateKeys = {
  posix2winKeys = posix2winKeysCurrent
}


type Win2posixMap = Map[String, Seq[String]]
type Posix2winMap = SortedMap[String, String]

// Config contract
trait PathsConfig {
  def get(first: String, more: String*): Path
  def get(uri: URI): Path

  def cygPrefix: String
  def win2posix: Win2posixMap
  def posix2win: Posix2winMap
  def msysRoot: String
  def p2wm: SortedMap[String, String]
}

// Testing seam: inject synthetic mount lines (clients normally won't call this)
private[uni] def withMountLines(mountLines: Seq[String]): Unit = {
  config = new SyntheticPathsConfig(mountLines)
  updateKeys
}

// Restore default config (e.g., after tests)
private[uni] def resetConfig(): Unit = {
  config = DefaultPathsConfig
}

// Canonical map container
final case class MountMaps(
  cygPrefix: String,
  win2posix: Win2posixMap,
  posix2win: Posix2winMap
) {
  val msysRoot = posix2win.getOrElse("/", "")
  val p2wm = posix2win.removed("/")
}

// Default config: spawns mount.exe and parses stdout lazily
object DefaultPathsConfig extends PathsConfig {
  private lazy val mountInfo: MountMaps = {
    val lines: Seq[String] = MountExe.lines()
    ParseMounts.parseMountLines(lines)
  }

  def msysRoot: String = mountInfo.msysRoot
  def cygPrefix: String = mountInfo.cygPrefix
  def win2posix: Win2posixMap = mountInfo.win2posix
  def posix2win: Posix2winMap = mountInfo.posix2win
  def p2wm: SortedMap[String, String] = mountInfo.p2wm

  // Paths interface method implementations
  def get(first: String, more: String*): Path = {
    Resolver.resolvePath(first, more, mountInfo)
  }

  def get(uri: URI): Path = {
    Resolver.resolvePath(uri, mountInfo)
  }
}

// Synthetic config: uses injected mount lines
final class SyntheticPathsConfig(mountLines: Seq[String]) extends PathsConfig {
  private lazy val mountInfo: MountMaps = {
    ParseMounts.parseMountLines(mountLines)
  }

  def msysRoot: String = mountInfo.msysRoot
  def cygPrefix: String = mountInfo.cygPrefix
  def win2posix: Win2posixMap = mountInfo.win2posix
  def posix2win: Posix2winMap = mountInfo.posix2win
  def p2wm: SortedMap[String, String] = mountInfo.p2wm

  def get(first: String, more: String*): Path = {
    Resolver.resolvePath(first, more, mountInfo)
  }

  def get(uri: URI): Path = {
    Resolver.resolvePath(uri, mountInfo)
  }
}

object Resolver {

  /** Normalize and convert a string path to a java.nio.file.Path */
  /*
   * The crux of the biscuit:
   * In every OS except Windows, there are 2 types of Path:  absolute and pwd-relative
   * In Windows, there are 5 types of path:
        // absolute:                  drive letter followed by slash or backslash, e.g.       "F:/"
        // Windows UNC path           \\server\share
        // drive-relative:            drive letter not followed by slash or backslash, e.g.   "F:config/bin"
        // posix-absolute (MSYS2:     any path beginning with a single "/", e.g.,             "/usr/bin"
        // directory-relative         no drive letter, no leading slash/backslash             "./bin"
   *
   * This method must produce the correct java.nio.file.Path object for each of these.
   */
  def resolvePath(first: String, more: Seq[String], mountInfo: MountMaps): Path = {
    val fname = (first +: more).mkString("/")
    val pstr = if (fname.contains("~")) {
      fname.replaceFirst("~", userHome)
    } else {
      fname
    }
    val result: String = {
      if (!isWin) {
        pstr
      } else {
        if pstr == "/" then
          config.msysRoot
        else if (pstr.length >= 2 && pstr(1) == ':') {
          assert(Character.isLetter(pstr(0)))
          // Windows absolute or drive-relative
          pstr
        } else if (pstr.startsWith("\\\\")) {
          // Windows UNC path
          pstr
        } else if (pstr.startsWith("/")) {
          // find longest matching mount prefix
          val pstrTrim = pstr.stripSuffix("/")
          val pstrlc = pstrTrim.toLowerCase(Locale.ROOT)
          val maybeMount = PrefixResolverRecursive.mountPrefix(pstrlc)
          maybeMount match {
            case Some(mountKey) =>
              val mountedWinPath = config.posix2win(mountKey) match {
                case s if s.endsWith(":") => s"$s/"
                case s => s
              }
              val postPrefix = pstrTrim.drop(mountKey.length)
              s"$mountedWinPath$postPrefix"
            case None =>
              val root = config.posix2win("/")
              s"$root$pstr"
          }
        } else {
          // Directory-relative path
          pstr
        }
      }
    }
    JPaths.get(result)
  }

  /** Overload for java.net.URI */
  def resolvePath(uri: URI, mountInfo: MountMaps): Path = {
    val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
    val path: String = uri.getPath

    if ((scheme.isEmpty || scheme == "file") && path != null && path.startsWith("/")) {
      // Delegate to our string-based get
      resolvePath(path, Nil, mountInfo)
    } else {
      // Otherwise, fall back to java.nio.file.Paths
      JPaths.get(uri)
    }
  }

  object PrefixResolverSinglePass {
    // Cache the candidate keys once, lowercased for comparison
    //private lazy val candidates: List[String] = config.posix2win.keysIterator.map(_.toLowerCase).toList

    def mountPrefix(path: String): Option[String] = {
      val s = path.stripSuffix("/").toLowerCase(Locale.ROOT)

      // Scan once, track longest match
      var best: String = null
      var bestLen = -1

      for (k <- posix2winKeys) {
        if (s.startsWith(k) &&
            (s.length == k.length || {
              val next = s.charAt(k.length)
              next == '/' || next == '\\' || next == ':'
            })) {
          if (k.length > bestLen) {
            best = k
            bestLen = k.length
          }
        }
      }

      if (best != null) Some(best) else None
    }
  }

  object PrefixResolverRecursive {
    // Cache candidates once, lazily
    //private lazy val candidates: List[String] = config.posix2win.keysIterator.map(_.toLowerCase).toList

    def mountPrefix(path: String): Option[String] = {
      val s = path.stripSuffix("/").toLowerCase(Locale.ROOT)

      import scala.annotation.tailrec
      @tailrec def loop(best: Option[String], rest: List[String]): Option[String] = rest match {
        case Nil => best
        case h :: t =>
          val nb =
            if (s.startsWith(h) &&
                (s.length == h.length || {
                  val next = s.charAt(h.length)
                  next == '/' || next == '\\' || next == ':'
                })) {
              best match {
                case Some(b) => if (h.length > b.length) Some(h) else best
                case None    => Some(h)
              }
            } else best
          loop(nb, t)
      }

      loop(None, posix2winKeys)
    }
  }
}

// Parsing logic (adapted from your snippet, with braces and explicit vals)
object ParseMounts {

  def parseMountLines(lines: Seq[String]): MountMaps = {
    val pairsAndPrefix: (String, Seq[(String, String)]) = {
      val entries: Seq[(String, String)] = lines.flatMap { line =>
        // Expected format: "C:/msys64 on / type ntfs"
        val parts = line.split(" on | type ").map(_.trim)
        if (parts.length >= 2) Some(parts(0) -> parts(1)) else None
      }.toSeq

      val cygPrefix: String = entries.find { case (a, _) => a.endsWith(":") } match {
        case Some((a, b)) if a.length == 2 => b.dropRight(2)
        case _ => ""
      }

      def syntheticDriveEntries(prefix: String): Seq[(String, String)] = {
        ('A' to 'Z').map { d =>
          val winKey = s"$d:".toLowerCase(Locale.ROOT)
          val posixVal = {
            val lower = d.toLower
            if (prefix.isEmpty) s"/$lower" else s"$prefix/$lower"
          }
          winKey -> posixVal
        }
      }.toSeq

      val syntheticEntries: Seq[(String, String)] = {
        if (!isWin) Nil else syntheticDriveEntries(cygPrefix)
      }

      val entriesPlus: Seq[(String, String)] = entries ++ syntheticEntries
      (cygPrefix, entriesPlus.distinct)
    }

    val cygPrefix: String = pairsAndPrefix._1
    val pairs: Seq[(String, String)] = pairsAndPrefix._2

    // Ordering for Windows-style keys: by length, then lexicographic
    val winOrdering: Ordering[String] = {
      Ordering.by[String, Int](_.length).orElse(Ordering.String)
    }

    // Ordering for Posix-style keys: root first, then length, then lexicographic
    val posixOrdering: Ordering[String] = new Ordering[String] {
      def compare(a: String, b: String): Int = {
        if (a == "/" && b == "/") 0
        else if (a == "/") -1
        else if (b == "/") 1
        else {
          val lenCmp = a.length.compare(b.length)
          if (lenCmp != 0) lenCmp else a.compareTo(b)
        }
      }
    }

    // Forward map (Windows → Seq[Posix]), lowercase keys
    val forwardMap: Win2posixMap = {
      val grouped: Map[String, Seq[String]] =
        pairs.groupMap { case (win, _) => win.toLowerCase(Locale.ROOT) } { case (_, posix) => posix }
      SortedMap.from(grouped)(winOrdering)
    }

    // Reverse map (Posix → Windows), lowercase keys/values
    val reverseMap: Posix2winMap = {
      val entries: Seq[(String, String)] = pairs.map { case (win, posix) =>
        posix.toLowerCase(Locale.ROOT) -> win.toLowerCase(Locale.ROOT)
      }
      SortedMap.from(entries)(posixOrdering)
    }

    MountMaps(cygPrefix, forwardMap, reverseMap)
  }
}

// MountExe locator + stdout reader (production path)
object MountExe {
  private val defaultMsysRoot: String = "c:/msys64"
  private val defaultMountExe: String = s"$defaultMsysRoot/usr/bin/mount.exe"

  // Return mount.exe mountExePath or empty string
  lazy val mountExePath: String = {
    if (!isWin) {
      ""
    } else {
      // cygwin version of /etc/fstab is never evaluated unless we check this first
      val mountExe = Proc.call("where.exe", "mount.exe").getOrElse("")
      if (mountExe.nonEmpty) {
        mountExe
      } else {
        val nio = JPaths.get(defaultMountExe)
        if (Files.exists(nio)) defaultMountExe else ""
      }
    }
  }

  // Spawn and capture lines, or Nil if unavailable
  def lines(): Seq[String] = {
    if (mountExePath.nonEmpty) {
      Proc.lazyLines(mountExePath)
    } else {
      Nil
    }
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

object Internals {
  import fs.*

  lazy val pwd: Path = JPaths.get("").toAbsolutePath

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

  def parseMountLines(lines: Seq[String]): MountMaps = {
    val (cygPrefix: String, pairs: Seq[(String, String)]) = {
      val entries = lines.flatMap { line =>
        val parts = line.split(" on | type ").map(_.trim)
        if (parts.length >= 2) Some(parts(0) -> parts(1)) else None
      }.toSeq

      val cygPrefix: String = entries.find { case (a, b) => a.endsWith(":") } match {
        case Some((a: String, b: String)) if a.length == 2 =>
          b.dropRight(2)
        case _ =>
          ""
      }

      def syntheticDriveEntries(cygPrefix: String): Seq[(String, String)] = {
        ('A' to 'Z').map { d =>
          val winKey   = s"${d}:".toLowerCase(Locale.ROOT)
          val posixVal =
            if cygPrefix.isEmpty then s"/${d.toLower}"
            else s"$cygPrefix/${d.toLower}"
          winKey -> posixVal
        }
      }.toSeq

      val syntheticEntries = if !isWin then Nil else syntheticDriveEntries(cygPrefix)
      val entriesPlus: Seq[(String, String)] = entries ++ syntheticEntries
      (cygPrefix, entriesPlus.distinct)
    }

    // Ordering for Windows-style keys
    given winOrdering: Ordering[String] =
      Ordering.by[String, Int](_.length).orElse(Ordering.String)

    // Ordering for Posix-style keys (root first, then length, then lexicographic)
    given posixOrdering: Ordering[String] with
      def compare(a: String, b: String): Int =
        (a, b) match
          case ("/", "/") => 0
          case ("/", _)   => -1
          case (_, "/")   => 1
          case _ =>
            val lenCmp = a.length.compare(b.length)
            if lenCmp != 0 then lenCmp else a.compareTo(b)

    // Forward map (Windows → Seq[Posix])
    val forwardMap: SortedMap[String, Seq[String]] =
      // Lowercase keys
      SortedMap.from(pairs.groupMap(_. _1.toLowerCase(Locale.ROOT))(_. _2))(using winOrdering)


    // Reverse map (Posix → Windows)
    val reverseMap: SortedMap[String, String] =
      SortedMap.from(pairs.map { case (win, posix) =>
        posix.toLowerCase(Locale.ROOT) -> win.toLowerCase(Locale.ROOT)
      })(using posixOrdering)

    MountMaps(cygPrefix, forwardMap, reverseMap)
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
    println("Forward Map:")
    config.win2posix.foreach { case (k, v) =>
      val row = "%-44s -> %s".format(k, v.mkString(","))
      println(row)
    }

    println("\nReverse Map:")
    config.posix2win.foreach { case (k, v) =>
      val row = "%-44s -> %s".format(k, v)
      println(row)
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

    val pathstr = winPath.toString
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
          s"${posixRoots.head}${post}"

        case None =>
          if (pstr.length >= 2 && pstr(1) == ':') {
            // Drive letter path
            val drive = pstr(0).toLower
            val post = pstr.drop(2)
            s"${config.cygPrefix}$drive$post"
          } else if (pstr.startsWith("//")) {
            // UNC path
            val unc = pstr.drop(2)
            s"${config.cygPrefix}unc/$unc"
          } else {
            // Relative path
            pstr
          }
      }
    }
  }

  def asPosixDrive(dl: String, path: String): String = {
    val root = config.cygPrefix
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

object fs {
  def round(number: Double, scale: Int = 6): Double =
    BigDecimal(number).setScale(scale, RoundingMode.HALF_UP).toDouble

  import StandardCharsets.{UTF_8, ISO_8859_1 as Latin1}
  import Internals.*

  /** Extension methods */
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
    def abs: String = p.toAbsolutePath.normalize.toString.posx
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
      Files.readAllLines(p, charset).asScala.iterator
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
    def localpath: String = p.toString.posx
    def dospath: String = {
      val pstr = p.toString
      pstr match {
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
      p.toString.posx
    }
    def posix: String = {
      p.toString.posix
    }
    def local: String = {
      p.toString.posx
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
    def lastModMinutesAgo: Double   = round(lastModSecondsAgo / 60.0)
    def lastModHoursAgo: Double     = round(lastModMinutesAgo / 60.0)
    def lastModDaysAgo: Double      = round(lastModHoursAgo / 24.0)

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
    def pathsTree: Iterator[Path] = {
      if (Files.exists(p)) {
        Files.walk(p).iterator().asScala
      } else {
        Iterator.empty
      }
    }
  }
  extension (str: String) {
    def path: Path = Paths.get(str)
    def posx: String = str.replace('\\', '/')
    def posix: String = {
      if (!isWin) {
        str
      } else {
        val forward = str.replace('\\', '/')
        config.win2posix.collectFirst {
          case (win, posixSeq) if forward.startsWithIgnoreCase(win) =>
            s"${posixSeq.head}${forward.stripPrefixIgnoreCase(win)}"
        }.getOrElse {
          forward
        }
      }
    }.replaceFirst("^//", "/")

    def local: String = {
      val forward = str.replace('\\', '/')
      val isPosix = forward.startsWith("/") //|| !forward.matches("(?i)^[a-z]:/.*")
      if (!isWin || !isPosix) {
        str
      } else {
        config.p2wm.collectFirst {
          case (posix, winSeq) if str.startsWithIgnoreCase(posix) =>
            s"${winSeq.head}${str.stripPrefixIgnoreCase(posix)}"
        }.getOrElse {
          s"${config.msysRoot}$str"
        }
      }
    }
    def startsWithIgnoreCase(prefix: String): Boolean = {
      str.regionMatches(true, 0, prefix, 0, prefix.length)
    }

    def stripPrefixIgnoreCase(prefix: String): String = {
      if str.startsWithIgnoreCase(prefix) then str.substring(prefix.length) else str
    }
  }

  export Internals.call as call
  export Internals.spawn as spawn
  export System.err.printf as eprintf
  export scala.util.Properties.{isLinux}
}

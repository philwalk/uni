//#!/usr/bin/env -S scala-cli --cli-version nightly shebang -deprecation -q
package uni

import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{Files, Path, Paths as JPaths}
import java.net.URI
import java.util.Locale
import scala.collection.immutable.SortedMap
import scala.sys.process.*
import scala.util.Try
import scala.util.Properties
import scala.jdk.CollectionConverters.*
import scala.math.BigDecimal.RoundingMode

export java.io.File as JFile
export java.nio.file.Path
export scala.util.Properties.isWin

// A scala cygpath-aware rendition of Paths.get
object Paths {
  import Internals.*
  import file.*

  /** Normalize and convert a string path to a java.nio.file.Path */
  def get(segs: String *): Path = {
    val fname = segs.mkString("/")
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
          msysRoot
        else if (pstr.length >= 2 && pstr(1) == ':') {
          // Windows absolute or drive-relative
          pstr
        } else if (pstr.startsWith("\\\\")) {
          // Windows UNC path
          pstr
        } else if (pstr.startsWith("/")) {
          // find longest matching mount prefix
          val keys = posix2winMounts.keys
          val pstrTrim = pstr.stripSuffix("/")
          def isMounted(rootedPath: String): Option[String] = {
            keys
              .filter { key =>
                val k = key.toLowerCase
                val s = rootedPath.toLowerCase
                s.startsWith(k) &&
                  (s.length == k.length || {
                    val next = s.charAt(k.length)
                    next == '/' || next == '\\' || next == ':'
                  })
              }
              .toList
              .sortBy(-_.length)
              .headOption
          }
          val maybeMount = isMounted(pstrTrim)
          maybeMount match {
            case Some(mountKey) =>
              val mount = posix2winMounts(mountKey) match {
                case s if s.endsWith(":") => s"$s/"
                case s => s
              }
              val postPrefix = pstrTrim.drop(mountKey.length)
              s"$mount$postPrefix"
            case None =>
              val root = posix2winMounts("/")
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

  def oldget(input: String): Path = {
    val result: String = {
      if (!isWin) {
        input
      } else {
        input match {
        case "" | "." =>
          input
        case dl if dl.matches("[a-zA-Z]:") =>
          val absDl = JPaths.get(dl)
          if canExist(absDl) then
            absDl.toAbsolutePath.toString.replace('\\', '/')
          else
            dl
        case _ =>
          var forward = input.replaceFirst("~", userHome).replace('\\', '/')
          if (input.startsWith(cygPrefix) && input.length == cygPrefix.length + 2) {
            // "/mnt/c" is NOT == "C:"
            // JVM treats a bare drive "C:" as the current working directory on that drive
            // msys2 interprets "/mnt/c" as "c:/"
            forward = s"$forward/"
          }
          val isPosix = forward.startsWith("/") //|| !forward.matches("(?i)^[a-z]:/.*")
          val local = if (isPosix) {
            p2wm.collectFirst {
            case (posix, winSeq) if forward.startsWithIgnoreCase(posix) =>
              val left = winSeq
              val rite = forward.stripPrefixIgnoreCase(posix+"/")
              if (left.endsWith("/") || rite.startsWith("/")) {
                hook += 1
              }
              val lr = s"$left/$rite"
              val normalized = if lr.length >= 4 then lr.stripSuffix("/") else lr
              normalized
            }.getOrElse {
              s"$rootEntry$forward"
            }
          } else {
            forward
          }
          local
        }
      }
    }
    val r = JPaths.get(result)
    r
  }

  /** Overload for JFile */
  def get(file: JFile)(using posix2winMounts: Win2posixMap): Path = {
    get(file.getPath)
  }

  /** Overload for java.net.URI */
  def get(uri: URI)(using posix2winMounts: Win2posixMap): Path = {
    JPaths.get(uri)
  }

  /** Overload for java.nio.file.Path (identity) */
  def get(path: Path): Path = path
}

object Internals {
  import file.*

  lazy val pwd: Path = JPaths.get("").toAbsolutePath

  def showMountMaps(): Unit = {
    println("Forward Map:")
    win2posixMounts.foreach { case (k, v) =>
      val row = "%-44s -> %s".format(k, v.mkString(","))
      println(row)
    }

    println("\nReverse Map:")
    posix2winMounts.foreach { case (k, v) =>
      val row = "%-44s -> %s".format(k, v)
      println(row)
    }
  }

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

  def realWhere(jpath: java.nio.file.Path): Path = {
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
  lazy val userHome = {
    val uh = sys.props("user.home").replace('\\', '/')
    uh
  }

  def defaultMsysRoot = "c:/msys64"
  def defaultMountExe = s"$defaultMsysRoot/usr/bin/mount.exe"

  lazy val mount: String = if (!isWin) {
    ""
  } else {
    // cygwin /etc/fstab is not evaluated unless we check this first
    val mountExe = call("where.exe", "mount.exe").getOrElse("")
    if (mountExe.nonEmpty) {
      mountExe
    } else if (Files.exists(JPaths.get(defaultMountExe))) {
      defaultMountExe
    } else {
      ""
    }
  }
  type Win2posixMap = Map[String, Seq[String]]
  type Posix2winMap = SortedMap[String, String]

  var hook = 0
  def parseMountLines(lines: Seq[String]): (String, Win2posixMap, Posix2winMap) = {
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

    (cygPrefix, forwardMap, reverseMap)
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

  lazy val mounts: Seq[String] = if (mount.nonEmpty) {
    Seq(mount).lazyLines_!.toList
  } else {
    Nil
  }

  lazy val (cygPrefix, win2posixMounts: Win2posixMap, posix2winMounts: Posix2winMap) =
    parseMountLines(mounts)

  lazy val (rootEntry: String, p2wm: SortedMap[String, String]) = {
    val (rootPair, p2wm) = posix2winMounts.partition(_._1 == "/")
    val rootEntry = rootPair("/")
    (rootEntry, p2wm)
  }

  // simplified by the fact that entry is guaranteed to be a Windows absolute path with drive letter
  def posixPathWithDrive(entry: String): String = {
    // assumes cygdrive = "/"
    val dl = entry.substring(0, 1)
    s"/$dl${entry.substring(2).replace('\\', '/')}"
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
  /*
  def isSameFile(p1: Path, p2: Path): Boolean = {
    try {
      val (p1str, p2str) = (p1.toString, p2.toString)
      // even files that !canExist() can be the same file
      p1str == p2str || {
        canExist(p1) && canExist(p2) && {
          Files.isSameFile(p1, p2)
        }
      }
    } catch {
      case _: Exception =>
        false
    }
  }
  */
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
      val w2pm = win2posixMounts
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
            s"$cygPrefix$drive$post"
          } else if (pstr.startsWith("//")) {
            // UNC path
            val unc = pstr.drop(2)
            s"${cygPrefix}unc/$unc"
          } else {
            // Relative path
            pstr
          }
      }
    }
  }

  def asPosixDrive(dl: String, path: String): String = {
    val root = cygdrive
    val cygified = s"$root${dl.take(1).toLowerCase(Locale.ROOT)}$path"
    cygified
  }
  lazy val driveRoot: String = JPaths.get("").toAbsolutePath.getRoot.toString.take(2)

  // default cygdrive values, if /etc/fstab is not customized
  //    cygwin: "/cygdrive",
  //    msys:   "/"
  lazy val msysRoot = posix2winMounts("/").mkString
  lazy val cygdrive: String = cygPrefix
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
}

object file {
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
            Files.isSameFile(p, otherPath)
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
    def posixDl: String = {
      if (isWin) posixPathWithDrive(p.toString) else p.toString
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

    def round(number: Double, scale: Int = 6): Double =
      BigDecimal(number).setScale(scale, RoundingMode.HALF_UP).toDouble

    /** Copy this Path to the given destination Path.
      * By default overwrites if the target exists.
      */
    def copyTo(dest: Path, overwrite: Boolean = true, copyAttributes: Boolean = false): Path = {
      import java.nio.file.StandardCopyOption
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
    def posixDl: String = {
      if (isWin) posixPathWithDrive(str) else str
    }
    def posix: String = {
      if (!isWin) {
        str
      } else {
        val forward = str.replace('\\', '/')
        win2posixMounts.collectFirst {
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
        p2wm.collectFirst {
          case (posix, winSeq) if str.startsWithIgnoreCase(posix) =>
            s"${winSeq.head}${str.stripPrefixIgnoreCase(posix)}"
        }.getOrElse {
          s"$rootEntry$str"
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

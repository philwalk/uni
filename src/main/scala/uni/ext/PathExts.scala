package uni

import java.nio.charset.{Charset, StandardCharsets}
import java.io.{File as JFile, PrintWriter}
import java.nio.file.{Path, Files, StandardCopyOption}
import java.time.ZoneId
import uni.time.UniDateTime
import StandardCharsets.{UTF_8, ISO_8859_1 as Latin1}
import scala.jdk.CollectionConverters.*
import uni.*
import uni.data.*
import uni.io.FileOps.*
import uni.Internals.*
import scala.reflect.ClassTag
import scala.util.Using

/** Path Extension methods */
object pathExts {

  extension (@annotation.unused p: Path) {
    def exists: Boolean      = Files.exists(p)
    def isDirectory: Boolean = Files.isDirectory(p)
    def isFile: Boolean      = Files.isRegularFile(p)

    // ---- os-lib compatible names (primary) ----
    /** Last path segment (filename). os-lib: p.last */
    def last: String = p.getFileName.toString

    /** Filename without extension. os-lib: p.baseName */
    def baseName: String = {
      val n = p.getFileName.toString
      val i = n.lastIndexOf('.')
      if i == -1 then n else n.substring(0, i)
    }

    /** File extension without leading dot. os-lib: p.ext */
    def ext: String = {
      val ds = dotsuffix
      if ds.nonEmpty then ds.drop(1) else ""
    }

    // ---- deprecated in favour of os-lib names ----

    // ---- path segments ----
    /** All path name elements as an IndexedSeq of strings. os-lib: p.segments */
    def segments: IndexedSeq[String] = p.iterator.asScala.map(_.toString).toIndexedSeq

    // ---- size / permissions ----
    def length: Long       = if Files.exists(p) then Files.size(p) else 0L
    def isEmpty: Boolean   = length == 0L
    def nonEmpty: Boolean  = length != 0L
    def canRead: Boolean   = p.toFile.canRead
    def canExecute: Boolean = p.toFile.canExecute

    // ---- path forms ----
    def relativePath: Path = relativePathToCwd(p)
    /** This path relative to the working directory: `.` when it *is* that directory,
     *  a relative path when it is below it, and its absolute POSIX form otherwise.
     *
     *  Was `standardizePath(relativePathToCwd(p))`, which never returned anything
     *  relative -- `relativePathToCwd` computed the relative form and
     *  `standardizePath` immediately threw it away by calling `toAbsolutePath`.
     *
     *  Worse, the two disagreed about which working directory they meant:
     *  `relativePathToCwd` relativised against the *config's* `pwd` while
     *  `standardizePath` re-absolutised against the *JVM's* `user.dir`. Under an
     *  injected user those are different directories, so the result named a
     *  different file -- `C:/munit/test/foo` came back as
     *  `/c/Users/philwalk/workspace/uni/foo`.
     *
     *  Now the Path-facing form of `posixRel`, which is what that method's own
     *  deprecation note already promised ("Use `Path.relpath`"). One working
     *  directory, `config.userdir`, and pure string work throughout. */
    def relpath: String = toPosixRel(p.toString)

    def abs: String =
      if (java.nio.file.Files.exists(p))
        normalizePosix(p.toAbsolutePath.normalize.toString)
      else
        normalizePosix(p.normalize.toString)

    def abspath: Path    = p.toAbsolutePath.normalize
    def stdpath: String  = standardizePath(p)
    def posx: String     = normalizePosix(p.toString)
    def posix: String    = toPosixAbs(p.toString)

    /** Native form: backslashes on Windows, forward slashes elsewhere.
     *
     *  Was identical to `posx`, which made the pair pointless — the intended split
     *  is that `posx` always yields forward slashes and `local` yields whatever the
     *  platform uses. Same as [[localpath]]. */
    def local: String    = localpath

    def localpath: String = {
      val s = normalizePosix(p.toString)
      // `config.isWindows` rather than the global `isWin`, so a test can exercise
      // the Windows separator rule from Linux or macOS.
      if config.isWindows then s.replace('/', '\\') else s
    }

    def dospath: String = {
      val pstr = p.toString
      pstr match {
        case "." => "."
        case s if !config.isWindows || s.length > 2 =>
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

    /** Strip Windows drive letter (e.g. C:/foo → /foo). No-op on POSIX. */
    def noDrive: String = p.posx match {
      case s if s.length >= 2 && s(1) == ':' => s.drop(2)
      case s                                  => s
    }

    // ---- suffix / extension ----
    def dotsuffix: String = {
      val n   = p.getFileName.toString
      val idx = n.lastIndexOf('.')
      if idx > 0 then n.substring(idx) else ""
    }

    def extension: Option[String] = {
      val ds = dotsuffix
      if ds.nonEmpty then Some(ds.drop(1)) else None
    }

    // ---- parent / file ----
    def getParentNonNull: Path = Option(p.getParent).getOrElse(p)
    def getParentPath: Path    = Option(p.getParent).getOrElse(p.toAbsolutePath.normalize.getParent)
    def parent: Path           = p.toAbsolutePath.getParent
    def parentPath: Path       = getParentPath
    def parentFile: JFile      = p.getParentPath.toFile
    def asFile: JFile          = p.toFile


    // ---- directory listing ----
    /** Sort key giving these listings a **specified** order.
     *
     *  `listFiles` and `Files.walk` promise none -- roughly alphabetical on NTFS, arbitrary on
     *  ext4 -- so the same script listed a directory differently on Linux and Windows and no
     *  test could pin it. Sorting here makes the order part of the contract.
     *
     *  Case-insensitive first, then case-sensitive as a tiebreak: `(a.txt, B.txt)` rather than
     *  `(B.txt, a.txt)`, which is what a reader expects, while the second component keeps the
     *  order total where two names differ only in case (possible on Linux, not on Windows).
     *
     *  `Locale.ROOT`, not the default locale: `toLowerCase` is locale-sensitive, and under a
     *  Turkish locale `I` lowercases to a dotless `ı`, which would reorder listings by where the
     *  machine thinks it is.
     *
     *  And deliberately *not* `Seq[Path].sorted`, whose `Path.compareTo` is case-insensitive on
     *  Windows and case-sensitive on Linux. The Rust port could match that per-platform -- its
     *  `is_windows` is data, and `isSameFile` already folds case that way -- so this is a choice
     *  rather than a limitation. One platform-independent order means a script lists a directory
     *  identically on Linux and Windows, so generated files and diffs compare across machines; and
     *  it means one parity fixture instead of the per-platform pair `test-data/path-parity/` needs.
     */
    private def listOrder(path: Path): (String, String) =
      val s = path.posx
      (s.toLowerCase(java.util.Locale.ROOT), s)

    /** This directory, lazily, in **filesystem order**.
      *
      *  Yields the first entry as soon as the filesystem returns it, rather than waiting for the
      *  last. On a USB or network directory holding thousands of files [[paths]] is unusably slow
      *  for that reason: it cannot produce anything until the whole listing has arrived and been
      *  sorted.
      *
      *  The order therefore differs from [[paths]]. That is the trade the name records -- an
      *  ordered listing has to be complete before it can be ordered, so laziness and a canonical
      *  order are mutually exclusive, and each spelling gives one of them.
      *
      *  Holds an open directory handle. Exhausting it closes it; abandoning it early does not, so
      *  prefer [[eachPath]] or close it yourself -- the same contract as `linesStream` beside
      *  `withLines`.
      */
    def pathsIter: Iterator[Path] & AutoCloseable = lazyDirIter(p)

    /** As [[pathsIter]], yielding `JFile`. Lazy, filesystem order, holds a handle. */
    def filesIter: Iterator[JFile] & AutoCloseable = mapClosable(lazyDirIter(p))(_.toFile)

    /** Applies `f` to each entry lazily, closing the handle even if `f` throws. */
    def eachPath(f: Path => Unit): Unit = Using.resource(lazyDirIter(p))(_.foreach(f))

    def files: Seq[JFile]          = paths.map(_.toFile)
    /** This directory in canonical order. Eager by necessity -- see [[pathsIter]].
      *
      *  Built on the same `newDirectoryStream` iterator as [[pathsIter]] rather than on
      *  `File.listFiles`, which materialises a `File[]` only for every element to be converted
      *  straight back to a `Path`. One pass, one collection, then the sort.
      */
    def paths: Seq[Path]           = Using.resource(lazyDirIter(p))(_.toSeq).sortBy(listOrder)
    def subdirs: Seq[Path]         = paths.filter(Files.isDirectory(_))
    def subfiles: Seq[Path]        = paths.filter(Files.isRegularFile(_))

    def reversePath: String = p.iterator.asScala.map(_.toString).toList.reverse.mkString("/")

    // ---- tree walk ----

    /** Alias for [[pathsTreeIter]]: lazy, `Files.walk` order. */
    def walk: Iterator[Path] & AutoCloseable = pathsTreeIter

    /** The tree rooted here, **including this path**, in a specified order.
     *
     *  Sorted by the same key as [[paths]]. A full sort of the flattened walk keeps every parent
     *  before its descendants -- a path is a prefix of its children, so it compares smaller --
     *  which is the one ordering guarantee `Files.walk` made and which callers rely on.
     */
    def pathsTree: Seq[Path] = Using.resource(pathsTreeIter)(_.toSeq.sortBy(listOrder))

    /** The tree rooted here, lazily, in `Files.walk` order.
      *
      *  Depth-first pre-order, so a parent still precedes its descendants -- that guarantee comes
      *  from the walk itself, not from the sort [[pathsTree]] applies, which only orders siblings.
      *
      *  Yields as it descends instead of draining the tree first, which is the point on a slow
      *  filesystem. Holds open directory handles; exhausting it closes them, abandoning it early
      *  does not.
      */
    def pathsTreeIter: Iterator[Path] & AutoCloseable =
      if !Files.exists(p) then emptyClosable
      else
        import scala.jdk.CollectionConverters.*
        try
          val stream = Files.walk(p)
          closableIter(stream.iterator().asScala, () => stream.close())
        catch case _: Exception => emptyClosable

    // ---- read content ----
    def linesStream: Iterator[String] = if isFile then streamLines(p) else Iterator.empty
    def linesStream(charset: String): Iterator[String] =
      if !isFile then Iterator.empty
      else if charset.isEmpty then streamLines(p)
      else
        val cs = try Charset.forName(charset) catch case _: Exception => UTF_8
        streamLines(p, cs)

    def withLines[A](f: Iterator[String] => A): A =
      if isFile then Using.resource(streamLines(p))(f) else f(Iterator.empty)

    def withLines[A](charset: String)(f: Iterator[String] => A): A =
      if !isFile then f(Iterator.empty)
      else if charset.isEmpty then Using.resource(streamLines(p))(f)
      else
        val cs = try Charset.forName(charset) catch case _: Exception => UTF_8
        Using.resource(streamLines(p, cs))(f)

    def eachLine(f: String => Unit): Unit =
      if isFile then Using.resource(streamLines(p))(_.foreach(f))

    def eachLine(charset: String)(f: String => Unit): Unit =
      if isFile then withLines(charset)(_.foreach(f))

    def firstLine: String = withLines(_.nextOption.getOrElse(""))

    def lines: Seq[String] = withLines(_.toSeq)

    def lines(charset: String): Seq[String] = withLines(charset)(_.toSeq)

    def contentAsString(charset: Charset = UTF_8): String =
      if isFile then
        try Files.readString(p, charset) catch case _: Exception => ""
      else ""

    def contentAsString: String =
      if isFile then
        try {
          Files.readString(p, UTF_8)
        } catch {
          case _: Exception =>
            try {
              Files.readString(p, Latin1)
            } catch {
              case _: Exception => ""
            }
        }
      else ""


    def byteArray: Array[Byte] = if isFile then (try Files.readAllBytes(p) catch case _: Exception => Array.empty[Byte]) else Array.empty[Byte]

    // ---- CSV ----
    def csvRowsAsync:  Iterator[Seq[String]] = if isFile then uni.io.FastCsv.rowsAsync(p) else Iterator.empty
    def csvRowsStream: Iterator[Seq[String]] = if isFile then uni.io.FastCsv.rowsPulled(p) else Iterator.empty
    /** All rows, padded to a common width so the result is rectangular.
     *
     *  Matches `loadSmart`, which needs rectangular data and now pads for it too —
     *  the two must not disagree about which rows a file contains.
     *
     *  The streaming forms above pad too, but only to the widest row in the
     *  delimiter-sniffing sample -- they cannot see the whole file. This one reads
     *  it all, so it is the only form guaranteed rectangular. */
    def csvRows:       Seq[Seq[String]]      = uni.io.FastCsv.rectangular(csvRowsStream.toSeq)
    def csvRows(onRow: Seq[String] => Unit): Unit =
      if isFile then
        uni.io.FastCsv.eachRow(p) { (row: IterableOnce[String]) =>
          onRow(row.iterator.to(Seq))
        }

    // ---- existence / link ----
    def isSymbolicLink: Boolean = Files.isSymbolicLink(p)
    def isSameFile(other: Any): Boolean =
      try {
        other match {
          case otherPath: Path => sameFileTest(p, otherPath)
          case _               => false
        }
      } catch { case _: Exception => false }

    // ---- timestamps ----
    def lastModified: Long       = p.toFile.lastModified
    def lastModMillisAgo: Long   = System.currentTimeMillis - p.toFile.lastModified


    def lastModSecondsAgo: Double = lastModMillisAgo / 1000.0
    def lastModMinutesAgo: Double = round(lastModSecondsAgo / 60.0)
    def lastModHoursAgo: Double   = round(lastModMinutesAgo / 60.0)
    def lastModDaysAgo: Double    = round(lastModHoursAgo / 24.0)


    /** The file's last-modified time as `yyyy-MM-dd HH:mm:ss`, in **UTC**.
     *
     *  See [[lastModifiedTime]] on why UTC. Also no longer goes through `SimpleDateFormat`,
     *  which is not thread-safe and was being constructed per call.
     */
    def lastModifiedYMD: String = lastModifiedTime.ymdhms

    /** The file's last-modified time, in **UTC**.
     *
     *  A [[uni.time.UniDateTime]], in step with the rest of the date surface: a file timestamp
     *  gets compared and combined with parsed dates constantly, and two date types meeting in
     *  one expression infers the union `LocalDateTime | UniDateTime`, which no position accepts
     *  and no conversion repairs.
     *
     *  # Why UTC rather than system-local
     *
     *  This used `ZoneId.systemDefault()`, which cannot be reproduced outside the JVM: Rust's
     *  `std` has no timezone database and no way to read the local offset. The alternative was
     *  an offset parameter on every call -- a value nearly every caller does not care about,
     *  since a file timestamp is used for *comparison* (where any consistent offset cancels)
     *  or for *display* (where being machine-independent is an advantage, not a cost).
     *
     *  It also removes an inconsistency that was already here: `epoch2DateTime` has always
     *  defaulted to `UTC`, so `p.epoch2DateTime(p.lastModified)` and `p.lastModifiedTime`
     *  disagreed by the local offset for the same file. They now agree.
     *
     *  Local time is still one explicit call away and needs no API of its own:
     *  {{{ p.epoch2DateTime(p.lastModified, ZoneId.systemDefault()) }}}
     */
    def lastModifiedTime: UniDateTime = epoch2DateTime(p.toFile.lastModified)

    /** Day of the week of last modification, in UTC: 1 = Monday .. 7 = Sunday.
     *
     *  An `Int` rather than a `java.time.DayOfWeek`. That is a deliberate move off `java.time`
     *  — the numbering is the same, so nothing is lost, and the method becomes portable. Safe
     *  to change: measured across the 166-script corpus, `weekDay` had zero callers.
     */
    def weekDay: Int = lastModifiedTime.dayOfWeekNum

    /** Three-letter weekday abbreviation of last modification, in UTC: `Mon` .. `Sun`. */
    def weekDayName: String = lastModifiedTime.dayOfWeekName

    def epoch2DateTime(epoch: Long, timezone: ZoneId = UTC): UniDateTime = {
      UniDateTime.ofInstant(java.time.Instant.ofEpochMilli(epoch), timezone)
    }

    // ---- age comparisons ----
    /** True when this file was modified more recently than `other`, and both are files.
      *
      *  The comparison reads as the name does: `a.newerThan(b)` asks whether **a** is newer.
      *  Before 0.16.0 it was inverted -- true when *b* was newer -- and the two known callers in
      *  the wild read as if the name were true, so they were silently backwards until this flip.
      */
    def newerThan(other: Path): Boolean =
      p.isFile && other.isFile && p.lastModified > other.lastModified

    /** True when this file was modified less recently than `other`, and both are files. */
    def olderThan(other: Path): Boolean =
      p.isFile && other.isFile && p.lastModified < other.lastModified

    // ---- copy / move / delete ----
    /** Copies to `dest`, returning `dest`.
     *
     *  `overwrite` has **no default, on purpose**, and this is the only method here without one.
     *  It used to default to `true`, so `copyTo(dest)` clobbered silently while
     *  `renameTo(dest)` -- defaulting to `false` -- did not: an asymmetry no reader could guess,
     *  and the dangerous direction of it. Flipping the default would have changed behaviour at
     *  every existing call site with nothing to catch it at build time, so the parameter is
     *  required instead and the compiler names each one.
     *
     *  The rename family keeps its `overwrite = false` default deliberately. That default is
     *  already the safe answer, every existing call site means it, and requiring it there would
     *  have meant ~24 mechanical edits across `jsrc`, `apps` and `vast` for no change in
     *  behaviour.
     *
     *  `copyAttributes` keeps its default, and that line is drawn on purpose: a flag that can
     *  destroy data must be stated, one that cannot may default.
     *
     *  Returns `Some(dest)` when the copy happened, `None` when `overwrite = false` and `dest`
     *  already exists -- a refused overwrite is the answer to a question, not an exceptional
     *  event, and it is the same answer the Rust port gives, so the two languages agree on the
     *  case a caller can actually plan for. Real I/O failures (permissions, disk) still throw.
     *  Detected by catching `FileAlreadyExistsException` rather than by a pre-check, so there is
     *  no window in which another process creates `dest` and the copy clobbers it anyway.
     */
    def copyTo(dest: Path, overwrite: Boolean, copyAttributes: Boolean = false): Option[Path] = {
      val options =
        if (overwrite && copyAttributes)
          Array(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        else if (overwrite)
          Array(StandardCopyOption.REPLACE_EXISTING)
        else if (copyAttributes)
          Array(StandardCopyOption.COPY_ATTRIBUTES)
        else
          Array.empty[StandardCopyOption]
      try
        Files.copy(p, dest, options*)
        Some(dest)
      catch case _: java.nio.file.FileAlreadyExistsException => None
    }

    /** Rename by copy+delete — works across filesystems. Returns 0 on success, -1 on failure. */
    def renameViaCopy(newFile: Path, overwrite: Boolean = false): Int =
      try
        if !Files.exists(p) || (Files.exists(newFile) && !overwrite) then
          -1
        else
          Files.copy(p, newFile, StandardCopyOption.REPLACE_EXISTING)
          Files.delete(p)
          0
      catch case _: Exception => -1

    def renameTo(other: Path, overwrite: Boolean = false): Boolean =
      renameToOpt(other, overwrite).isDefined

    def renameToOpt(other: Path, overwrite: Boolean = false): Option[Path] =
      if Files.exists(p) && (overwrite || !Files.exists(other)) then
        import java.nio.file.CopyOption
        import java.nio.file.StandardCopyOption.REPLACE_EXISTING
        val opts: Array[CopyOption] =
          if overwrite then Array(REPLACE_EXISTING) else Array.empty[CopyOption]
        try Some(Files.move(p, other, opts*))
        catch case _: Exception => None
      else
        None

    /** Deletes the file if it exists.
      * @return true if deleted, false if it did not exist.
      * @throws java.io.IOException if deletion fails for real (permissions, locks, etc.)
      */
    def delete(): Boolean = Files.deleteIfExists(p)

    /** Creates this directory and any missing parents, reporting whether it is now a directory.
      *
      *  The result is the re-check, not `createDirectories`' word for it: a name already occupied
      *  by a *file* reports `false` rather than throwing. Before 0.16.0 that case threw
      *  `FileAlreadyExistsException`, which made the `Boolean` decorative -- success returned
      *  `true` and every failure threw, so `false` was unreachable.
      */
    def mkdirs: Boolean = {
      try Files.createDirectories(p) catch case _: Exception => ()
      p.toFile.isDirectory
    }

    // ---- hashes / checksums ----
    /** Write to this path via a PrintWriter callback. */
    def withWriter(charsetName: String = "UTF-8", append: Boolean = false)(func: PrintWriter => Any): Unit =
      uni.io.FileOps.withFileWriter(p, charsetName, append)(func)

    /** Guess the CSV column delimiter (comma, tab, semicolon, pipe). Empty string if none detected. */
    def delim: String =
      if !p.isFile then ""
      else
        try
          val state = uni.io.Delimiter.detect(p, 50)
          if state.score > 0 then state.delimiterChar.toString else ""
        catch case _: Exception => ""

    def hash64: String = {
      val (hashstr: String, _) = uni.io.Hash64.hash64(p.toFile)
      hashstr
    }
    def cksum: (Long, Long) = uni.io.cksum(p)
    def md5: String         = uni.io.md5(p)
    def sha256: String      = uni.io.sha256(p)

    // ---- realpath ----
    def realPath: Path = {
      val existing =
        Iterator.iterate(p)(_.getParent)
          .takeWhile(_ != null)
          .find(Files.exists(_))

      val remaining: Option[Path] =
        existing match
          case Some(prefix) =>
            val prefixCount = prefix.getNameCount
            val pCount      = p.getNameCount
            if prefixCount < pCount then
              Some(p.subpath(prefixCount, pCount))
            else
              None
          case None =>
            None

      val resolvedPrefix =
        existing.map(_.toRealPath()).getOrElse(p.toAbsolutePath())

      val finalPath =
        resolvedPrefix.resolve(remaining.mkString("/")).normalize()

      Paths.get(finalPath.toString.replace('\\', '/'))
    }

    // ---- matrix loading ----
    private def loadMatInternal[T: ClassTag](map: Big => T): Mat[T] =
      loadSmart(p, map).mat

    def loadMatBig: Mat[Big]            = loadMatInternal(identity)
    def loadMatD: MatD                  = loadMatInternal(_.toDouble)
    def loadSmartBig: MatResult[Big]    = loadSmart(p)
    def loadSmartD: MatResult[Double]   = loadSmart(p, _.toDouble)
    def loadMatB: Mat[Big]              = loadMatBig
    def loadMatF: MatF                  = loadMatInternal(_.toDouble.toFloat)

    def readCsv: MatD                                    = loadMatD
    def readCsvB: MatB                                   = loadMatBig
    def readCsvF: MatF                                   = loadMatF
    def writeCsv[T](m: Mat[T], sep: String = ","): Unit = m.saveCSV(p, sep)

    def writeLines(lines: Seq[String]): Unit =
      // Adding the trailing newline ensures the file isn't "missing a newline at EOF"
      uni.io.FileOps.withFileWriter(p){ w => 
        lines.foreach { line => 
          w.write(line)
          w.write("\n")
        }
      }

    def write(text: String): Unit = 
      uni.io.FileOps.withFileWriter(p){ w => w.write(text) }
  }

  // ---------------------------------------------------------------------------

  extension (f: JFile) {
    // ---- os-lib compatible names (primary) ----
    def last: String     = f.getName
    def baseName: String = { val n = f.getName; val i = n.lastIndexOf('.'); if i == -1 then n else n.substring(0, i) }
    def ext: String      = f.toPath.ext

    // ---- suffix / extension ----
    def dotsuffix: String         = f.toPath.dotsuffix
    def extension: Option[String] = f.toPath.extension

    // ---- deprecated in favour of os-lib names ----

    // ---- path forms ----

    def posx: String     = f.toPath.posx
    def abs: String      = f.toPath.abs
    def abspath: Path    = f.toPath.abspath
    def stdpath: String  = standardizePath(f.toPath)
    def noDrive: String  = f.toPath.noDrive
    def segments: IndexedSeq[String] = f.toPath.segments

    def dospath: String           = f.toPath.dospath
    def localpath: String         = f.toPath.localpath
    def posix: String             = f.toPath.posix
    def local: String             = f.toPath.local
    def relpath: String           = f.toPath.relpath
    def relativePath: Path        = f.toPath.relativePath

    // ---- size ----
    def isEmpty: Boolean  = f.length == 0L
    def nonEmpty: Boolean = f.length != 0L

    // ---- parent ----
    def parent: Path      = f.toPath.parent
    def parentPath: Path  = f.toPath.parentPath
    def parentFile: JFile = f.toPath.parentFile

    def reversePath: String = f.toPath.reversePath
    def delim: String       = f.toPath.delim

    // ---- existence / link ----
    def isSymbolicLink: Boolean         = Files.isSymbolicLink(f.toPath)
    def isSameFile(other: Any): Boolean = f.toPath.isSameFile(other)
    def diff(other: JFile): Seq[String] = run("diff", f.toPath.posx, other.toPath.posx).lines

    // ---- directory listing ----
    def filesIter: Iterator[JFile] & AutoCloseable  = f.toPath.filesIter
    def files: Seq[JFile]           = f.toPath.files
    def pathsIter: Iterator[Path] & AutoCloseable   = f.toPath.pathsIter
    def paths: Seq[Path]            = f.toPath.paths
    def subdirs: Seq[Path]          = f.toPath.subdirs
    def subfiles: Seq[Path]         = f.toPath.subfiles

    // ---- tree walk ----
    /** The `Path` view of this file: the symmetric partner of `Path.asFile`, and what the
      * migration rule `.path` -> `.asPath` lands on for a `File` receiver. */
    def asPath: Path = f.toPath

    def filesTree: Seq[JFile]          = filesTreeIter.toSeq
    def filesTreeIter: Iterator[JFile] = f.toPath.pathsTreeIter.map(_.toFile)
    def pathsTree: Seq[Path]           = f.toPath.pathsTree
    def pathsTreeIter: Iterator[Path] & AutoCloseable  = f.toPath.pathsTreeIter

    // ---- read content ----
    def linesStream: Iterator[String]                                  = f.toPath.linesStream
    def linesStream(charset: String): Iterator[String]                 = f.toPath.linesStream(charset)
    def withLines[A](fn: Iterator[String] => A): A                    = f.toPath.withLines(fn)
    def withLines[A](charset: String)(fn: Iterator[String] => A): A   = f.toPath.withLines(charset)(fn)
    def eachLine(fn: String => Unit): Unit                             = f.toPath.eachLine(fn)
    def eachLine(charset: String)(fn: String => Unit): Unit            = f.toPath.eachLine(charset)(fn)
    def firstLine: String                              = f.toPath.firstLine
    def lines: Seq[String]                             = f.toPath.lines
    def lines(charset: String): Seq[String]            = f.toPath.lines(charset)
    def lines(charset: Charset): Seq[String]           = f.toPath.lines(charset.name)
    def contentAsString(charset: Charset): String      = f.toPath.contentAsString(charset)
    def contentAsString: String                        = f.toPath.contentAsString
    def byteArray: Array[Byte]                         = f.toPath.byteArray

    // ---- CSV ----
    def csvRowsAsync:  Iterator[Seq[String]]       = f.toPath.csvRowsAsync
    def csvRowsStream: Iterator[Seq[String]]       = f.toPath.csvRowsStream
    def csvRows:      Seq[Seq[String]]            = f.toPath.csvRows
    def csvRows(onRow: Seq[String] => Unit): Unit = f.toPath.csvRows(onRow)

    // ---- timestamps ----
    def lastModMillisAgo: Long    = f.toPath.lastModMillisAgo
    def lastModSecondsAgo: Double = f.toPath.lastModSecondsAgo
    def lastModMinutesAgo: Double = f.toPath.lastModMinutesAgo
    def lastModHoursAgo: Double   = f.toPath.lastModHoursAgo
    def lastModDaysAgo: Double    = f.toPath.lastModDaysAgo
    def lastModifiedYMD: String   = f.toPath.lastModifiedYMD
    def lastModifiedTime: UniDateTime = f.toPath.lastModifiedTime
    def weekDay: Int              = f.toPath.weekDay
    def weekDayName: String       = f.toPath.weekDayName

    // ---- age comparisons ----
    def newerThan(other: Path): Boolean = f.toPath.newerThan(other)
    def olderThan(other: Path): Boolean = f.toPath.olderThan(other)

    // ---- copy / move ----
    // `copyTo` has no single-argument form: that overload *was* the old `overwrite = true`
    // default wearing a different hat, so keeping it would have left `f.copyTo(dest)` clobbering
    // silently while the Path version required the flag. The rename forms keep theirs -- their
    // default is `false`, which is already the safe answer.
    def copyTo(dest: Path, overwrite: Boolean): Option[Path]        = f.toPath.copyTo(dest, overwrite)
    def copyTo(dest: Path, overwrite: Boolean, copyAttributes: Boolean): Option[Path] = f.toPath.copyTo(dest, overwrite, copyAttributes)
    def renameTo(other: Path): Boolean                  = f.toPath.renameTo(other)
    def renameTo(other: Path, overwrite: Boolean): Boolean = f.toPath.renameTo(other, overwrite)
    def renameToOpt(other: Path): Option[Path]          = f.toPath.renameToOpt(other)
    def renameToOpt(other: Path, overwrite: Boolean): Option[Path] = f.toPath.renameToOpt(other, overwrite)

    // ---- hashes / checksums ----
    def hash64: String      = f.toPath.hash64
    def cksum: (Long, Long) = f.toPath.cksum
    def md5: String         = f.toPath.md5
    def sha256: String      = f.toPath.sha256

    // ---- realpath ----
    def realPath: Path = f.toPath.realPath

    // ---- matrix loading ----
    def loadMatBig: Mat[Big]          = f.toPath.loadMatBig
    def loadMatD: MatD                = f.toPath.loadMatD
    def loadSmartBig: MatResult[Big]  = f.toPath.loadSmartBig
    def loadSmartD: MatResult[Double] = f.toPath.loadSmartD
    def loadMatB: Mat[Big]            = f.toPath.loadMatB
    def loadMatF: MatF                = f.toPath.loadMatF

    def readCsv: MatD                              = f.toPath.readCsv
    def readCsvB: MatB                             = f.toPath.readCsvB
    def readCsvF: MatF                             = f.toPath.readCsvF
    def writeCsv[T](m: Mat[T]): Unit               = f.toPath.writeCsv(m)
    def writeCsv[T](m: Mat[T], sep: String): Unit  = f.toPath.writeCsv(m, sep)

    def writeLines(lines: Seq[String]): Unit = f.toPath.writeLines(lines)

    def write(text: String): Unit = f.toPath.write(text)
  }

  lazy val UTC: ZoneId = java.time.ZoneId.of("UTC")

  import java.nio.charset.{StandardCharsets, CodingErrorAction}
  import java.io.InputStream

  /** Wraps a lazy iterator so exhausting it releases the resource, and `close` is idempotent.
    *
    * The mutable `closed` flag is local to this instance and never escapes -- the alternative,
    * threading a state value through an `Iterator`, cannot be done behind that interface.
    */
  private def closableIter[A](under: Iterator[A], closer: () => Unit): Iterator[A] & AutoCloseable =
    new Iterator[A] with AutoCloseable:
      private var closed = false
      def hasNext: Boolean =
        if closed then false
        else if under.hasNext then true
        else
          close()
          false
      def next(): A = under.next()
      def close(): Unit =
        if !closed then
          closed = true
          closer()

  private def emptyClosable[A]: Iterator[A] & AutoCloseable =
    closableIter(Iterator.empty, () => ())

  /** Maps a closable iterator, keeping the close obligation attached to the result. */
  private def mapClosable[A, B](it: Iterator[A] & AutoCloseable)(f: A => B): Iterator[B] & AutoCloseable =
    closableIter(it.map(f), () => it.close())

  /** One directory, lazily. Empty rather than throwing when `p` is not a readable directory,
    * matching `listFiles` returning `null` there.
    */
  private def lazyDirIter(p: Path): Iterator[Path] & AutoCloseable =
    if !Files.isDirectory(p) then emptyClosable
    else
      import scala.jdk.CollectionConverters.*
      try
        val ds = Files.newDirectoryStream(p)
        closableIter(ds.iterator().asScala, () => ds.close())
      catch case _: Exception => emptyClosable

  /** True when a newline encodes to the single byte 0x0A in this charset.
    *
    * The byte-oriented reader splits on that byte before decoding, which is only sound when the
    * byte cannot occur inside a character. False for UTF-16 and UTF-32, where 0x0A is half of a
    * code unit: splitting there cuts a character in two, the decode fails, and the reader's
    * Latin-1 fallback hands back the raw bytes with embedded NULs. Measured, not assumed.
    */
  private def newlineIsOneByte(cs: Charset): Boolean =
    try "\n".getBytes(cs).sameElements(Array('\n'.toByte))
    catch case _: Exception => false

  /** Decode the whole file, then split. Used for charsets where a byte-level split cannot find
    * the terminator, so laziness is not available at any price. Same split rule as the streaming
    * reader, and the same empty-on-failure behaviour as `contentAsString`.
    */
  private def decodedLines(p: Path, cs: Charset): Iterator[String] & AutoCloseable =
    val text  = try Files.readString(p, cs) catch case _: Exception => ""
    val parts = text.split("\r?\n", -1).toIndexedSeq
    val lines = if parts.nonEmpty && parts.last.isEmpty then parts.dropRight(1) else parts
    new Iterator[String] with AutoCloseable:
      private val under = lines.iterator
      def hasNext: Boolean = under.hasNext
      def next(): String   = under.next()
      def close(): Unit    = () // nothing held open; the file was read and released

  private def streamLines(p: Path, cs: Charset = StandardCharsets.UTF_8): Iterator[String] & AutoCloseable =
    if !newlineIsOneByte(cs) then decodedLines(p, cs)
    else new Iterator[String] with AutoCloseable {
    private val in: InputStream = new java.io.BufferedInputStream(Files.newInputStream(p))
    // Use a reusable BAOS to avoid constant ArrayBuffer re-allocations
    private val bos = new java.io.ByteArrayOutputStream(128)
    private var nextLine: String | Null = null
    private var isClosed = false

    private val decoder = cs.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)

    override def hasNext: Boolean = {
      if (nextLine != null) true
      else if (isClosed) false
      else {
        nextLine = readNextLine()
        if (nextLine == null) { close(); false } else true
      }
    }

    override def next(): String = {
      if (!hasNext) throw new NoSuchElementException()
      val s = nextLine.nn
      nextLine = null
      s
    }

    private def readNextLine(): String | Null = {
      bos.reset() // Reuse existing memory
      var b = in.read()
      if (b == -1) return null

      while (b != -1 && b != '\n'.toInt) {
        bos.write(b)
        b = in.read()
      }

      // Exactly a split on "\r?\n": the only CR removed is the one the newline
      // consumed. An interior CR is data, and so is a trailing CR at end-of-file, where
      // no newline followed it to pair with.
      val sawNewline = b == '\n'.toInt
      val raw = bos.toByteArray
      val bytes =
        if (sawNewline && raw.length > 0 && raw(raw.length - 1) == '\r'.toByte)
          raw.slice(0, raw.length - 1)
        else raw
      try {
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString
      } catch {
        case _: Exception => new String(bytes, StandardCharsets.ISO_8859_1)
      }
    }

    override def close(): Unit = {
      if (!isClosed) {
        isClosed = true
        in.close()
      }
    }
  }
}

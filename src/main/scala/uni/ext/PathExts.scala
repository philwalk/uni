package uni

import java.nio.charset.{Charset, MalformedInputException, StandardCharsets}
import java.io.{File as JFile, PrintWriter}
import java.nio.file.{Path, Files, StandardCopyOption}
import java.time.{DayOfWeek, LocalDateTime, ZoneId}
import StandardCharsets.{UTF_8, ISO_8859_1 as Latin1}
import scala.jdk.CollectionConverters.*
import uni.*
import uni.data.*
import uni.io.FileOps.*
import uni.Internals.*
import scala.reflect.ClassTag

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
    @deprecated("Use `last`", "uni")        def name: String       = last
    @deprecated("Use `baseName`", "uni")    def basename: String   = baseName
    @deprecated("Use `baseName.lc`", "uni") def lcbasename: String = baseName.toLowerCase
    @deprecated("Use `last.lc`", "uni")     def lcname: String     = last.toLowerCase
    @deprecated("Use `ext.lc`", "uni")      def lcsuffix: String   = ext.toLowerCase
    @deprecated("Use `ext`", "uni")         def suffix: String     = ext

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
    def relpath: String = {
      val rp: Path = relativePathToCwd(p)
      standardizePath(rp)
    }

    def abs: String =
      if (java.nio.file.Files.exists(p))
        normalizePosix(p.toAbsolutePath.normalize.toString)
      else
        normalizePosix(p.normalize.toString)

    def abspath: Path    = p.toAbsolutePath.normalize
    def stdpath: String  = standardizePath(p)
    def posx: String     = normalizePosix(p.toString)
    def posix: String    = posixAbs(p.toString)
    def local: String    = normalizePosix(p.toString)

    def localpath: String = {
      val s = normalizePosix(p.toString)
      if isWin then s.replace('/', '\\') else s
    }

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
    def file: JFile            = p.toFile

    // ---- directory listing ----
    def filesIter: Iterator[JFile] = Option(p.toFile.listFiles) match {
      case Some(arr) => arr.iterator
      case None      => Iterator.empty
    }
    def files: Seq[JFile]          = filesIter.toSeq
    private def pathsIter: Iterator[Path]  = filesIter.map(_.toPath)
    def paths: Seq[Path]           = pathsIter.toSeq
    def subdirs: Seq[Path]         = pathsIter.filter(Files.isDirectory(_)).toSeq
    def subfiles: Seq[Path]        = pathsIter.filter(Files.isRegularFile(_)).toSeq

    def reversePath: String = p.iterator.asScala.map(_.toString).toList.reverse.mkString("/")

    // ---- tree walk ----

    def walk: Iterator[Path] = pathsTreeIter // alias

    def pathsTree: Seq[Path] = pathsTreeIter.toSeq
    def pathsTreeIter: Iterator[Path] =
      if Files.exists(p) then
        Files.walk(p).iterator().asScala
      else
        Iterator.empty

    // ---- read content ----
    def linesStream: Iterator[String] = streamLines(p)
    def firstLine: String = streamLines(p).nextOption.getOrElse("")

    def lines: Iterator[String] = {
      try {
        Files.readAllLines(p, UTF_8).asScala.iterator
      } catch {
        case _: java.nio.charset.MalformedInputException =>
          Files.readAllLines(p, Latin1).asScala.iterator
      }
    }

    def lines(charset: String = ""): Iterator[String] =
      if charset.isEmpty then streamLines(p) else
      scala.io.Source.fromFile(p.toFile).getLines

    def contentAsString(charset: Charset = UTF_8): String = Files.readString(p, charset)
    def contentAsString: String = {
      try {
        Files.readString(p, UTF_8)
      } catch {
        case _: java.nio.charset.MalformedInputException =>
          Files.readString(p, Latin1)
      }
    }

    @deprecated("Use `contentAsString`", "uni") def text: String           = contentAsString
    @deprecated("Use `lines`", "uni")            def trimmedLines: Seq[String] = lines

    def byteArray: Array[Byte] = Files.readAllBytes(p)

    // ---- CSV ----
    def csvRowsAsync: Iterator[Seq[String]] = uni.io.FastCsv.rowsAsync(p)
    def csvRows: Seq[Seq[String]]           = uni.io.FastCsv.rowsPulled(p).toSeq
    def csvRows(onRow: Seq[String] => Unit): Unit =
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

    @deprecated("Use `lastModSecondsAgo`", "uni")
    def lastModSecondsDbl: Double = lastModSecondsAgo

    def lastModSecondsAgo: Double = lastModMillisAgo / 1000.0
    def lastModMinutesAgo: Double = round(lastModSecondsAgo / 60.0)
    def lastModHoursAgo: Double   = round(lastModMinutesAgo / 60.0)
    def lastModDaysAgo: Double    = round(lastModHoursAgo / 24.0)

    def lastModSeconds: Double = lastModSecondsAgo  // alias
    def lastModMinutes: Double = lastModMinutesAgo  // alias
    def lastModHours: Double   = lastModHoursAgo    // alias
    def lastModDays: Double    = lastModDaysAgo     // alias

    /** Alias for lastModDaysAgo. pallet compat: p.ageInDays */
    def ageInDays: Double = lastModDaysAgo

    def lastModifiedYMD: String = {
      val date   = new java.util.Date(p.toFile.lastModified)
      val ymdHms = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      ymdHms.format(date)
    }

    def lastModifiedTime: LocalDateTime = {
      LocalDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(p.toFile.lastModified),
        ZoneId.systemDefault()
      )
    }

    def weekDay: DayOfWeek = lastModifiedTime.getDayOfWeek

    def epoch2DateTime(epoch: Long, timezone: ZoneId = UTC): LocalDateTime = {
      LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epoch), timezone)
    }

    // ---- age comparisons ----
    def newerThan(other: Path): Boolean =
      p.isFile && other.isFile && other.lastModified > p.lastModified
    def olderThan(other: Path): Boolean =
      p.isFile && other.isFile && other.lastModified < p.lastModified

    // ---- copy / move / delete ----
    /** Copy to dest. Overwrites by default. */
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

    /** Rename by copy+delete — works across filesystems. Returns 0 on success, -1 on failure. */
    def renameViaCopy(newFile: Path, overwrite: Boolean = false): Int =
      try
        if !Files.exists(p) then return -1
        if Files.exists(newFile) && !overwrite then return -1
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

    def mkdirs: Boolean = {
      Files.createDirectories(p)
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
    @deprecated("Use `last`", "uni")        def name: String       = last
    @deprecated("Use `baseName`", "uni")    def basename: String   = baseName
    @deprecated("Use `baseName.lc`", "uni") def lcbasename: String = baseName.toLowerCase
    @deprecated("Use `ext`", "uni")         def suffix: String     = ext
    @deprecated("Use `last.lc`", "uni")     def lcname: String     = last.toLowerCase
    @deprecated("Use `ext.lc`", "uni")      def lcsuffix: String   = ext.toLowerCase

    // ---- path forms ----
    def posx: String     = f.toPath.posx
    def path: Path       = f.toPath
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
    def diff(other: JFile): Seq[String] = shellExec(s"diff '${f.toPath.posx}' '${other.toPath.posx}'")

    // ---- directory listing ----
    def filesIter: Iterator[JFile]  = Option(f.listFiles).map(_.iterator).getOrElse(Iterator.empty)
    def files: Seq[JFile]           = filesIter.toSeq
    def pathsIter: Iterator[Path]   = filesIter.map(_.toPath)
    def paths: Seq[Path]            = pathsIter.toSeq
    def subdirs: Seq[Path]          = f.toPath.subdirs
    def subfiles: Seq[Path]         = f.toPath.subfiles

    // ---- tree walk ----
    def filesTree: Seq[JFile] = filesTreeIter.toSeq
    def filesTreeIter: Iterator[JFile] =
      if f.exists() then Files.walk(f.toPath).iterator().asScala.map(_.toFile)
      else Iterator.empty
    def pathsTree: Seq[Path]          = f.toPath.pathsTree
    def pathsTreeIter: Iterator[Path] = f.toPath.pathsTreeIter

    // ---- read content ----
    def linesStream: Iterator[String]             = streamLines(f.toPath)
    def firstLine: String                         = streamLines(f.toPath).nextOption.getOrElse("")
    def lines: Iterator[String]                   = f.toPath.lines
    def lines(charset: Charset): Iterator[String] = scala.io.Source.fromFile(f, charset.name).getLines
    def contentAsString(charset: Charset): String = Files.readString(f.toPath, charset)
    def contentAsString: String                   = f.toPath.contentAsString
    def byteArray: Array[Byte]                    = f.toPath.byteArray

    // ---- CSV ----
    def csvRowsAsync: Iterator[Seq[String]]       = uni.io.FastCsv.rowsAsync(f.toPath)
    def csvRows: Seq[Seq[String]]                 = uni.io.FastCsv.rowsPulled(f.toPath).toSeq
    def csvRows(onRow: Seq[String] => Unit): Unit = f.toPath.csvRows(onRow)

    // ---- timestamps ----
    def lastModMillisAgo: Long    = System.currentTimeMillis - f.lastModified
    def lastModSecondsAgo: Double = lastModMillisAgo / 1000.0
    def lastModMinutesAgo: Double = round(lastModSecondsAgo / 60.0)
    def lastModHoursAgo: Double   = round(lastModMinutesAgo / 60.0)
    def lastModDaysAgo: Double    = round(lastModHoursAgo / 24.0)
    def lastModSeconds: Double    = lastModSecondsAgo
    def lastModMinutes: Double    = lastModMinutesAgo
    def lastModHours: Double      = lastModHoursAgo
    def lastModDays: Double       = lastModDaysAgo
    def ageInDays: Double         = lastModDaysAgo
    def lastModifiedYMD: String   = f.toPath.lastModifiedYMD
    def lastModifiedTime: LocalDateTime = f.toPath.lastModifiedTime
    def weekDay: DayOfWeek        = f.toPath.weekDay

    // ---- age comparisons ----
    def newerThan(other: Path): Boolean = f.toPath.newerThan(other)
    def olderThan(other: Path): Boolean = f.toPath.olderThan(other)

    // ---- copy / move ----
    def copyTo(dest: Path): Path                                    = f.toPath.copyTo(dest)
    def copyTo(dest: Path, overwrite: Boolean): Path                = f.toPath.copyTo(dest, overwrite)
    def copyTo(dest: Path, overwrite: Boolean, copyAttributes: Boolean): Path = f.toPath.copyTo(dest, overwrite, copyAttributes)
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

    def writeLines(lines: Seq[String]): Unit = f.toPath.writeLines(lines)

    def write(text: String): Unit = f.toPath.write(text)
  }

  lazy val UTC: ZoneId = java.time.ZoneId.of("UTC")

  import java.nio.charset.{StandardCharsets, CodingErrorAction}
  import java.io.InputStream
  import scala.collection.mutable.ArrayBuffer
  import java.nio.ByteBuffer

  def streamLines(p: Path): Iterator[String] =
    new Iterator[String]:
      private val in: InputStream = Files.newInputStream(p)
      private val buf = ArrayBuffer.empty[Byte]
      private var nextLine: String | Null = null
      private var closed = false

      val utf8Decoder =
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)

      override def hasNext: Boolean =
        if nextLine != null then true
        else if closed then false
        else
          nextLine = readNextLine()
          nextLine != null

      override def next(): String =
        if !hasNext then throw new NoSuchElementException("next on empty iterator")
        val s = nextLine
        nextLine = null
        s.nn

      private def readNextLine(): String | Null =
        buf.clear()

        var b = in.read()
        if b == -1 then
          close()
          return null

        while b != -1 && b != '\n' do
          if b != '\r' then buf += b.toByte
          b = in.read()

        val bytes = buf.toArray

        try
          utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString
        catch
          case _: MalformedInputException =>
            new String(bytes, StandardCharsets.ISO_8859_1)

      private def close(): Unit =
        if !closed then
          closed = true
          in.close()
}

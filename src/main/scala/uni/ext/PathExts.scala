package uni.ext

import java.nio.charset.{Charset, MalformedInputException, StandardCharsets}
import java.io.{File as JFile}
import java.nio.file.{Path, Files, StandardCopyOption}
import StandardCharsets.{UTF_8, ISO_8859_1 as Latin1}
import scala.jdk.CollectionConverters.*
import uni.*
import uni.time.*
import uni.Internals.*
import java.time.LocalDateTime
import java.time.ZoneId

/** Path Extension methods */
object pathExts {
  extension (p: Path) {
    def exists: Boolean = Files.exists(p)
    def isDirectory: Boolean = Files.isDirectory(p)
    def isFile: Boolean = Files.isRegularFile(p)
    def name: String = p.getFileName.toString
    def length: Long = if Files.exists(p) then Files.size(p) else 0L

    def basename: String = {
      val n = p.getFileName.toString
      val i = n.lastIndexOf('.')
      if i == -1 then n else n.substring(0, i)
    }
    def lcbasename: String = basename.toLowerCase

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

    def linesStream: Iterator[String] = streamLines(p)

    def firstLine: String = streamLines(p).nextOption.getOrElse("")

    def lines: Seq[String] = {
      try {
        Files.readAllLines(p, UTF_8).asScala.toSeq
      } catch {
      case m: java.nio.charset.MalformedInputException =>
         Files.readAllLines(p, Latin1).asScala.toSeq
      }
    }

    def csvRowsAsync: Iterator[IterableOnce[String]] = {
      uni.io.FastCsv.rowsAsync(p)
    }
    def csvRows: Iterator[Seq[String]] = {
      uni.io.FastCsv.rowsPulled(p)
    }
    def csvRows(onRow: Seq[String] => Unit): Unit = {
      uni.io.FastCsv.eachRow(p){ (row: IterableOnce[String]) =>
        onRow(row.iterator.to(Seq))
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
    def localpath: String = normalizePosix(p.toString)

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
    def filesIter: Iterator[JFile] = Option(p.toFile.listFiles) match {
      case Some(arr) => arr.iterator
      case None      => Iterator.empty
    }
    def files: Seq[JFile] = filesIter.toSeq
    def pathsIter: Iterator[Path] = filesIter.map(_.toPath)
    def paths: Seq[Path] = pathsIter.toSeq

    def posx: String = {
      normalizePosix(p.toString)
    }
    def posix: String = posixAbs(p.toString)

    def local: String = {
      normalizePosix(p.toString)
    }
    def dotsuffix: String = {
      val name = p.getFileName.toString
      val idx  = name.lastIndexOf('.')
      if idx > 0 then name.substring(idx) else ""
    }
    def extension: Option[String] = {
      val ext = dotsuffix
      if ext.nonEmpty then Some(ext.drop(1)) else None
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

    def lastModifiedTime: LocalDateTime = {
      import java.time.*
      LocalDateTime.ofInstant(
        Instant.ofEpochMilli(p.toFile.lastModified),
        MountainTime
      )
    }

    def epoch2DateTime(epoch: Long, timezone: java.time.ZoneId = UTC): LocalDateTime = {
      val instant = java.time.Instant.ofEpochMilli(epoch)
      LocalDateTime.ofInstant(instant, timezone)
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
    def pathsTree: Seq[Path] = pathsTreeIter.toSeq

    def pathsTreeIter: Iterator[Path] =
      if Files.exists(p) then
        Files.walk(p).iterator().asScala
      else
        Iterator.empty

    def hash64: String = {
      val (hashstr: String, throwOpt: Option[Exception]) = uni.io.Hash64.hash64(p.toFile)
      hashstr
    }

    def cksum: (Long, Long) =
      uni.io.cksum(p)

    def md5: String =
      uni.io.md5(p)

    def mkdirs: Boolean =
      Files.createDirectories(p)
      p.toFile.isDirectory

    def renameTo(other: Path, overwrite: Boolean = false): Boolean = 
      renameToOpt(other, overwrite).isDefined

    def renameToOpt(other: Path, overwrite: Boolean = false): Option[Path] =
      if Files.exists(p) && (overwrite || !Files.exists(other)) then
        import java.nio.file.CopyOption
        import java.nio.file.StandardCopyOption.REPLACE_EXISTING
        val opts: Array[CopyOption] =
          if overwrite then Array(REPLACE_EXISTING)
          else Array.empty[CopyOption]
        try Some(Files.move(p, other, opts*))
        catch case _: Exception => None
      else
        None

    /** Deletes the file if it exists.
      * @return true if deleted, false if it did not exist.
      * @throws java.io.IOException if deletion fails for real (permissions, locks, etc.)
      */
    def delete: Boolean =
      Files.deleteIfExists(p)

    def realPath: Path = {
      // Find deepest existing parent
      val existing =
        Iterator.iterate(p)(_.getParent)
          .takeWhile(_ != null)
          .find(Files.exists(_))

      // Compute the remaining tail BEFORE canonicalizing the prefix
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

      // Canonicalize the prefix
      val resolvedPrefix =
        existing.map(_.toRealPath()).getOrElse(p.toAbsolutePath())

      // Reattach and normalize
      val finalPath =
        resolvedPrefix.resolve(remaining.mkString("/")).normalize()

      Paths.get(finalPath.toString.replace('\\', '/'))
    }
  }

  extension(f: JFile) {
    def posx: String = f.toPath.posx
    def name: String = f.getName
    def basename: String = {
      val n = f.getName
      val i = n.lastIndexOf('.')
      if i == -1 then n else n.substring(0, i)
    }
    def lcbasename: String = basename.toLowerCase
    def path: Path = f.toPath
    def abs: String = f.toPath.abs
    def stdpath: String = standardizePath(f.toPath)
    def filesTree: Seq[JFile] = filesTreeIter.toSeq
    def filesTreeIter: Iterator[JFile] =
      if f.exists() then
        Files.walk(f.toPath).iterator().asScala.map(_.toFile)
      else
        Iterator.empty
  }

  lazy val UTC: ZoneId          = java.time.ZoneId.of("UTC")
  //lazy val EasternTime: ZoneId  = java.time.ZoneId.of("America/New_York")
  //lazy val MountainTime: ZoneId = java.time.ZoneId.of("America/Denver")

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

      // Strict UTF-8 decoder: throws on malformed input
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

        // accumulate until newline or EOF
        while b != -1 && b != '\n' do
          if b != '\r' then buf += b.toByte
          b = in.read()

        val bytes = buf.toArray

        try
          // Try strict UTF-8 decoder first
          utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString
        catch
          case _: MalformedInputException =>
            // Fallback per line
            new String(bytes, StandardCharsets.ISO_8859_1)

      private def close(): Unit =
        if !closed then
          closed = true
          in.close()
}

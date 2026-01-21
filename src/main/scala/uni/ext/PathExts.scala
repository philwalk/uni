package uni.ext

import java.io.{File as JFile}
import java.nio.file.{Path, Files, StandardCopyOption}
import java.nio.charset.{Charset, StandardCharsets}
import StandardCharsets.{UTF_8, ISO_8859_1 as Latin1}
//import helpers.*
import uni.*
import uni.Internals.*
import scala.jdk.CollectionConverters.*

/** Path Extension methods */
object pathExts {
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

    def abs: String =
      if (java.nio.file.Files.exists(p))
        normalizePosix(p.toAbsolutePath.normalize.toString)
      else
        normalizePosix(p.normalize.toString)

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
    def files: Iterator[JFile] = Option(p.toFile.listFiles) match {
      case Some(arr) => arr.iterator
      case None      => Iterator.empty
    }
    def paths: Iterator[Path] = files.map(_.toPath)
    def posx: String = {
      if (p == null) {
        hook += 1
      }
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
    def pathsTree: Iterator[Path] =
      if Files.exists(p) then
        Files.walk(p).iterator().asScala
      else
        Iterator.empty
  }
}

#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.0

import uni.*
import uni.io.Hash64.hash64

import java.io.{BufferedInputStream, IOException}
import java.nio.file.{Files, SimpleFileVisitor, FileVisitResult}
import java.nio.file.attribute.BasicFileAttributes

object FastHash64 {
  
  def usage(m: String = ""): Nothing = {
    if m.nonEmpty then System.err.printf("%s\n", m)
    System.err.print(s"usage: ${progName(this)} [-r] <dir-or-file> [<dir-or-file> ...]\n")
    sys.exit(1)
  }

  var recurse: Boolean = false
  var options = Seq.empty[String]
  var filenames = Seq.empty[String]
  var totalBytes = 0L

  def main(args: Array[String]): Unit = {
    parseArgs(args)

    for (fname <- filenames) {
      val start = Paths.get(fname)
      if !Files.exists(start) then
        print(s"not found: $fname\n")
      else if (Files.isRegularFile(start)) {
        processFile(start.toFile)
      } else {
        if (!recurse) {
          start.toFile.listFiles.foreach { f =>
            processFile(f)
          }
        } else {
          Files.walkFileTree(
            start,
            new SimpleFileVisitor[Path] {
              override def visitFile(file: Path, attrs: BasicFileAttributes) =
                if attrs.isRegularFile then processFile(file.toFile)
                FileVisitResult.CONTINUE

              override def visitFileFailed(file: Path, exc: IOException) =
                // skip unreadable files but keep walking
                FileVisitResult.CONTINUE
            }
          )
        }
      }
    }
    printf("total: %1.3f Gb\n", totalBytes.toDouble/1e9)
  }

  def parseArgs(args: Array[String]): Unit = {
    if (args.isEmpty) {
      usage()
    }
    val (options, files) = args.partition(_.startsWith("-"))
    options.foreach { 
      case "-r" => recurse = true
      case arg => usage(s"unrecognized arg [$arg]")
    }
    filenames = files.toIndexedSeq
  }

  inline def processFile(file: java.io.File): Unit = {
    file.toString.replace('\\', '/') match {
    case "c:/swapfile.sys" | "c:/hiberfil.sys" | "c:/pagefile.sys" | "c:/DumpStack.log.tmp" =>
      // ignore
    case fname =>
      totalBytes += file.length
      val (hex, optEx) = hash64(file)
      optEx match {
      case None =>
        //print(s"$hex $total $f\n")
        print(s"$hex ${fname}\n")
      case Some(e) =>
        e.getMessage match {
        case msg if msg.contains("Access is denied") =>
          // ignore
        case msg =>
          System.err.print(s"$msg\n")
        }
      }
    }
  }

}
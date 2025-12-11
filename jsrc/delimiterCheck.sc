#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
//package uni

//> using dep org.vastblue:uni_3:0.5.0

import uni.*
import uni.fs.*
import uni.io.*

object DelimiterCheck {
  var concise = false
  var verbose = false
  var csvfiles = Vector.empty[String]

  def usage(m: String = ""): Nothing = {
    if m.nonEmpty then printf("%s\n", m)
    val progname = Option(sys.props("scala.source.names")).getOrElse("delimiterCheck.sc")
    printf("usage: %s [<file1> [<file2> ...]]\n", progname)
    printf("if no files on command line, filenames piped from STDIN\n", progname)
    sys.exit(1)
  }

  def main(args: Array[String]): Unit = {
    args.foreach { arg =>
      arg match {
      case "-c" =>
        concise = true
      case fname if fname.path.isFile =>
        csvfiles :+= fname
      case _ =>
        usage(s"unrecognized arg / file-not-found: [$arg]")
      }
    }

    // filenames can also be piped in from STDIN
    if (csvfiles.isEmpty) {
      import LinesIterator.*
      iterateLines(None){ (raw: String, w: PrintWriter) =>
        if raw.path.isFile then
          csvfiles :+= raw
        else
          usage(s"file not found: $raw")
      }
    }

    val paths: Seq[Path] = csvfiles.map(Paths.get(_))
    for ((path, i) <- paths.zipWithIndex) {
      if (!path.isFile) {
        printf("not found: %s\n", path.posx)
      } else {
        if (concise) {
          printf("%9s: ", "file-"+(i+1))
        } else {
          printf("%9s: ", path.posx)
        }
        val res = Delimiter.detect(path, maxRows = 20)
        printf("%s\n", res)
      }
    }
  }

  def parseArgs(args: Array[String]): Option[Path] = {
    var inputPathOpt: Option[Path] = None
    args.foreach { arg =>
      arg match {
      case "-v" => verbose = true
      case f if Paths.get(f).toFile.isFile =>
        inputPathOpt = Some(Paths.get(f))
      case _ =>
        usage(s"unrecognized arg [$arg]")
      }
    }
    inputPathOpt
  }
}


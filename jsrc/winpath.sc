#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.8.2

import uni.*
//import uni.fs.*

object Winpath {
  private var proxiedExeName: String = ""
  private var proxiedArgs: Seq[String] = Seq.empty

  def usage(msg: String = ""): Nothing = {
    if msg.nonEmpty then eprintf("%s\n", msg)
    eprintf("%s\n", s"usage: ${progName(this)} <proxiedExe> <arg1> [<arg2> ...]")
    sys.exit(1)
  }

  def main(args: Array[String]): Unit = {
    if args.isEmpty then usage("missing proxied executable name")

    args.foreach { arg =>
      if proxiedExeName.isEmpty then {
        proxiedExeName = arg
      } else if isValidWindowsPath(arg) then {
        proxiedArgs = proxiedArgs :+ arg
      } else if isValidMsysPath(arg) then {
        val p = Paths.get(arg)
        val converted =
          if requiresBs(proxiedExeName) then p.toString
          else p.posx
        proxiedArgs = proxiedArgs :+ converted
      } else {
        // neither Windows nor MSYS → pass through unchanged
        proxiedArgs = proxiedArgs :+ arg
      }
    }

    // For now, print the rewritten command line
    if (Option(System.getenv("WINPATH_ECHO")).getOrElse("").nonEmpty) {
      printf("%s %s\n", proxiedExeName, proxiedArgs.mkString(" "))
    } else {
      val pb = new ProcessBuilder((proxiedExeName +: proxiedArgs)*)
      pb.inheritIO()
      val p = pb.start()
      val code = p.waitFor()
      sys.exit(code)
    }
  }

  // ---------------------------------------------------------------------------

  /** Whether this proxied executable requires backslashes in paths. */
  def requiresBs(exeName: String): Boolean = {
    config.get(exeName.toLowerCase).getOrElse(false)
  }

  /** Map of proxied executable → requires backslash paths. */
  lazy val config: Map[String, Boolean] = {
    if winpathConfig.exists then {
      winpathConfig.csvRows.zipWithIndex.map { (cols, idx) =>
        val prox = Proxied(idx + 1, cols*)
        prox.name.toLowerCase -> prox.useBs
      }.toMap
    } else {
      Map.empty[String, Boolean]
    }
  }

  case class Proxied(name: String, useBs: Boolean = false)

  object Proxied {
    def apply(line: Int, cols: String*): Proxied = {
      cols.toList match {
        case Nil =>
          usage(s"error: line $line of ${winpathConfig.posx}")

        case name :: useBsVal :: _ =>
          val ubs = useBsVal.toLowerCase match {
            case "true" | "yes" | "on" => true
            case _                     => false
          }
          Proxied(name, ubs)

        case name :: _ =>
          Proxied(name, false)
      }
    }
  }

  lazy val winpathConfig: Path = {
    Paths.get("~/.winpath.cfg")
  }

}
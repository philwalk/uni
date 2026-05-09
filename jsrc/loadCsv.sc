#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.13.3

import uni.*

object LoadCsv {
 def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "<filename>    ; input",
    )
  }

  var verbose = false
  var infile = ""
  def main(args: Array[String]): Unit = {
    eachArg(args.toSeq, usage) {
    case "-v" => verbose = true
    case f if f.asPath.isFile =>
      infile = f
    case arg =>
      usage(s"unrecognized arg [$arg]")
    }
    if infile.isEmpty then
      usage()
    val p = infile.asPath
    if !p.isFile then
      usage(s"not found: ${p.posx}")

    val data = MatB.readCsv(p)
    println(data)
  }
}
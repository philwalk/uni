#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.2

import uni.*

def usage(m: String = ""): Nothing = {
  showUsage(m, "",
    "<dirname>    ; hash all files below directory",
  )
}

var verbose = false
var dirname = ""
eachArg(args.toSeq, usage) {
case "-v" => verbose = true
case dir if dir.path.isDirectory =>
  dirname = dir
case arg =>
  usage(s"unrecognized arg [$arg]")
}
if dirname.isEmpty then
  usage()

dirname.path.paths.foreach { p =>
  if p.isFile then
    println(s"${p.hash64} ${p.posx}")
}
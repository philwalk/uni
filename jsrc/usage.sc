#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.8.1

import uni.*

def usage(m: String = ""): Nothing = {
  showUsage(m, "",
    "<filename>    ; input",
  )
}

usage("yo!")
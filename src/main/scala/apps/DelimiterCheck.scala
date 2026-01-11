//#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
package uni

//> using dep org.vastblue:uni_3:0.5.2

import uni.*
import uni.fs.*
import uni.io.*

object DelimiterCheck {
  def main(args: Array[String]): Unit = {
    val paths: Seq[Path] = args.toSeq.map(Paths.get(_))
    if (paths.isEmpty) {
      def prog = Option(sys.props("scala.source.names")).getOrElse("delimiterCheck.sc")
      printf("usage: %s <csvFilePath> [<csv2path> ...]\n", prog)
      sys.exit(1)
    }
    for (path <- paths) {
      if (!path.isFile) {
        printf("not found: %s\n", path.posx)
      } else {
        printf("%-56s", path.posx)
        val res = Delimiter.detect(path, maxRows = 3)
        printf("%s\n", res)
      }
    }
  }
}

//#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
package apps

//> using dep org.vastblue:uni_3:0.9.1

import uni.*

object MatrixFromCsv {
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
    case f if f.path.isFile =>
      infile = f
    case arg =>
      usage(s"unrecognized arg [$arg]")
    }
    if infile.isEmpty then
      usage()
    val p = infile.path
    if !p.isFile then
      usage(s"not found: ${p.posx}")

    /*
    import uni.data.*
    val data = p.loadMat(big(_))
    val filtered = data.filterRows(_.isNotNaN.any)
    println(filtered)
    */
  }
}

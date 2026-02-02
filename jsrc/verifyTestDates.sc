#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.7.0

import uni.*
import uni.time.*

object VerifyTestDates {
  def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "<filename>    ; input",
      " [-s]         ; smart parse",
      " [-c]         ; chrono parse",
      " [-f]         ; smart with fallback",
    )
  }

  var parseType = ""
  def main(args: Array[String]): Unit = {
    eachArg(args.toSeq, usage) {
    case "-s" | "-c" | "-f" =>
      parseType = thisArg
    case arg =>
      usage(s"unrecognized arg [$arg]")
    }
    if parseType.isEmpty then
      usage()

    val expectedVersusInput = "data/generatedTestdates.csv".path
    val pairs = expectedVersusInput.csvRows.drop(1).toSeq
      
    def parse(target: String): java.time.LocalDateTime = {
      parseType match {
      case "-s" => parseDateSmart(target)
      case "-c" => parseDateChrono(target)
      case "-f" => parseDate(target)
      case _ => usage(s"bad parseType [$parseType]")
      }
    }
    val compFmt = "yyyy-MM-dd HH:mm:ss"
    for ((Seq(expect, target), i) <- pairs.zipWithIndex) {
      try {
        val test: java.time.LocalDateTime = parse(target)
        val testiso = test.toString(compFmt)
        if expect != testiso then
          printf("%3d, %s, %s # %s\n", i+2, expect, testiso, target)
      } catch {
        case e: Exception =>
          System.err.printf("%s\n", e.getMessage)
      }
    }
  }

}

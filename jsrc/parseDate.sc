#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.21.0

import uni.*
import uni.time.*

object ParseDate {
  def usage(m: String = ""): Nothing = {
    showUsage(m, "<date-time-string> [options]",
      " [-s]         ; smart parse",
      " [-f]         ; smart with fallback",
    )
  }

  var parseType = ""
  var datestr = ""
  def main(args: Array[String]): Unit = {
    eachArg(args.toSeq, usage) {
    case s if datestr.isEmpty =>
      datestr = s
    case "-s" | "-c" | "-f" =>
      parseType = thisArg
    case arg =>
      usage(s"unrecognized arg [$arg]")
    }
    if parseType.isEmpty then
      parseType = "-s"

    val compFmt = "yyyy-MM-dd HH:mm:ss"
    try {
      val test: java.time.LocalDateTime = parse(datestr)
      val testiso = test.toString(compFmt)
      printf("%s | %s # %s\n", test, testiso, datestr)
    } catch {
      case e: Exception =>
        System.err.printf("%s\n", e.getMessage)
    }
    def parse(target: String): java.time.LocalDateTime = {
      parseType match {
      case "-s" => parseDateSmart(target)
      // `-c` removed with ChronoParse in 0.16.0; `-s` and `-f` are the same parser now.
      case "-f" => uni.time.TimeUtils.parseDate(target)
      case _ => usage(s"bad parseType [$parseType]")
      }
    }
  }


}

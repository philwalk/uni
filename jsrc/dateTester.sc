#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.19.3

import uni.*
import uni.time.*

object DateTester {
  def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "<filename>    ; input",
      " [-s]         ; smart parse",
      " [-c]         ; chrono parse",
      " [-f]         ; smart with fallback",
    )
  }

  var testdate = ""
  var verbose = false
  def main(args: Array[String]): Unit = {
    eachArg(args.toSeq, usage) {
    case "-v" =>
      verbose = true
    case s if s.matches(".*[0-9].*") =>
      testdate = s
    case arg =>
      usage(s"unrecognized arg [$arg]")
    }
    if testdate.nonEmpty then
      val smartr = parseDateSmart(testdate)
      // Was a three-way smart/chrono/fallback comparison. ChronoParse is gone as of
      // 0.16.0 and `parseDate` is now `parseDateSmart`, so there is one answer to show.
      if verbose || smartr == BadDate then
        printf("# [%s]\n", testdate)
        printf("  smartr: %s\n", smartr)

    else
      val expectedVersusInput = "data/generatedTestdates.csv".asPath
      val pairs = expectedVersusInput.csvRows.drop(1).toSeq
        
      val compFmt = "yyyy-MM-dd HH:mm:ss"
      for ((row, i) <- pairs.zipWithIndex) {
        row match {
        case Seq(expect, target) =>
          try {
            val test: java.time.LocalDateTime = parseDate(target)
            val testiso = test.toString(compFmt)
            if expect != testiso then
              printf("%3d, %s, %s # %s\n", i+2, expect, testiso, target)
          } catch {
            case e: Exception =>
              System.err.printf("%s\n", e.getMessage)
          }
        case _ =>
          // A refutable pattern in the `for` would skip this row silently; a bad
          // fixture line must fail the verification, not shrink it.
          System.err.printf("%3d: malformed fixture row (%d fields): %s\n",
            i+2, row.size, row.mkString(","))
        }
      }
  }

}

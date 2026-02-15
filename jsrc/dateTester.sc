#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.8.1

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
      val chrono = parseDateChrono(testdate)
      val fallbk = parseDate(testdate)
      if verbose || chrono != smartr then
        printf("# [%s]\n", testdate)
        printf("  smartr: %s\n", smartr)
        printf("  chrono: %s\n", chrono)
        printf("  fallbk: %s\n", fallbk)

    else
      val expectedVersusInput = "data/generatedTestdates.csv".path
      val pairs = expectedVersusInput.csvRows.drop(1).toSeq
        
      val compFmt = "yyyy-MM-dd HH:mm:ss"
      for ((Seq(expect, target), i) <- pairs.zipWithIndex) {
        try {
          val test: java.time.LocalDateTime = parseDate(target)
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
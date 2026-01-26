#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.7.0

import uni.*
import uni.time.*

object VerifyTestDates {
  def usage(m: String = ""): Nothing = {
    showUsage(m, "",
      "<filename>    ; input",
    )
  }

  def main(args: Array[String]): Unit = {
    val expectedVersusInput = "data/testdates.csv".path
    if !expectedVersusInput.isFile then
      usage(s"not found: ${expectedVersusInput.posx}")

    val pairs = expectedVersusInput.csvRows.drop(1)
    for ((Seq(expect, target), i) <- pairs.zipWithIndex) {
      val test = parseDate(target).toString("yyyy-MM-dd HH:mm:ss")
      if expect != test then
        printf("%d:\n  %s\n  %s\n", i+2, expect, test)
    }
  }

}

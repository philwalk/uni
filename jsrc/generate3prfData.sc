#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.2

import uni.*
import uni.data.*

object Generate3prfData {
  def main(args: Array[String]): Unit = {
    exportTestData()
  }
  // Export test data so MATLAB can load it
  def exportTestData(): Unit =
    import java.io.PrintWriter
    def writeCsv(path: String, m: MatD): Unit =
      val pw = PrintWriter(path)
      for r <- 0 until m.rows do
        pw.println((0 until m.cols).map(c => m(r, c)).mkString(","))
      pw.close()

    MatD.setSeed(1234L)
    val T = 50; val N = 6; val L = 2
    val Z = MatD.randn(T, L)
    val X = MatD.randn(T, N)
    val y = MatD.randn(T, 1)
    writeCsv("ref_X.csv", X)
    writeCsv("ref_y.csv", y)
    writeCsv("ref_Z.csv", Z)
}
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.5

import uni.*
import uni.data.*
import uni.stats.Tprf3.*

object VerifyTprfResults {
  def main(args: Array[String]): Unit = {
    // 1. Load the exact same data Octave used
    val X = "ref_X.csv".path.loadMatD
    val y = "ref_y.csv".path.loadMatD
    val Z = "ref_Z.csv".path.loadMatD

    // 2. Run the Scala Tprf3 estimation
    // Assuming Tprf3.estimate follows the same signature
    // 1. Load the exact same data Octave used

    // 2. Call the Scala version (corresponds to 'IS Full' in Octave)
    // Per TprfSuite.scala: estimate3prf(y, X, Right(Z))
    val result = estimate3prf(y, X, Right(Z))

    // 3. Save results for comparison
    result.forecasts.saveCSV("scala_yhat.csv".path)
    result.alpha.foreach(_.saveCSV("scala_alpha.csv".path))
  
    // Create a 1x1 matrix for R-Square to keep CSV format consistent
    val rsqMat = MatD.zeros(1,1)
    rsqMat(0,0) = result.rSquared
    rsqMat.saveCSV("scala_rsquare.csv".path)

    println(s"Scala Run Complete. R-Square: ${result.rSquared}")
  }

  def readCsv(filePath: String): MatD = 
    filePath.path.loadMatD // Standard uni_3 utility

  def saveCsv(outputFname: String, m: MatD): Unit = {
    m.saveCSV(outputFname.path)
  }
}

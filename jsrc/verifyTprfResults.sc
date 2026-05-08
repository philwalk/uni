#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.13.2

import uni.*
import uni.data.*
import uni.stats.Tprf3.*

object VerifyTprfResults {
  def main(args: Array[String]): Unit = {
    // 1. Load the exact same data Octave used
    val X = "t3prf-validation/ref_X.csv".asPath.loadMatD
    val y = "t3prf-validation/ref_y.csv".asPath.loadMatD
    val Z = "t3prf-validation/ref_Z.csv".asPath.loadMatD

    // 2. Run the Scala Tprf3 estimation
    // Assuming Tprf3.estimate follows the same signature
    // 1. Load the exact same data Octave used

    // 2. Call the Scala version (corresponds to 'IS Full' in Octave)
    // Per TprfSuite.scala: estimate3prf(y, X, Right(Z))
    val result = estimate3prf(y, X, Right(Z))

    // 3. Save results for comparison
    val scalaYhatPath = "t3prf-validation/scala_yhat.csv".asPath
    val scalaAlphaPath = "t3prf-validation/scala_alpha.csv".asPath
    val scalaRsqPath = "t3prf-validation/scala_rsquare.csv".asPath

    result.forecasts.saveCSV(scalaYhatPath)
    result.alpha.foreach(_.saveCSV(scalaAlphaPath))
  
    val rsqMat = MatD.zeros(1,1)
    rsqMat(0,0) = result.rSquared
    rsqMat.saveCSV(scalaRsqPath)

    println(s"Scala Run Complete. R-Square: ${result.rSquared}")

    // 4. Verify results against MATLAB (KP) outputs
    val kpYhatPath = "t3prf-validation/kp_yhat.csv".asPath
    val kpAlphaPath = "t3prf-validation/kp_alpha.csv".asPath
    val kpRsqPath = "t3prf-validation/kp_rsquare.csv".asPath

    if (kpYhatPath.exists && kpAlphaPath.exists && kpRsqPath.exists) {
      println("\nVerifying against MATLAB (KP) references...")
      
      val kpYhat = kpYhatPath.loadMatD
      val kpAlpha = kpAlphaPath.loadMatD
      val kpRsq = kpRsqPath.loadMatD(0, 0)

      val yhatDiff = (result.forecasts - kpYhat).abs.max
      val alphaDiff = result.alpha.map(a => (a - kpAlpha).abs.max).getOrElse(0.0)
      val rsqDiff = Math.abs(result.rSquared - kpRsq)

      val tol = 1e-10
      println(f"Yhat Max Diff:  $yhatDiff%.2e")
      println(f"Alpha Max Diff: $alphaDiff%.2e")
      println(f"RSq Diff:       $rsqDiff%.2e")

      if (yhatDiff < tol && alphaDiff < tol && rsqDiff < tol) {
        println("\nSUCCESS: Scala results match MATLAB (KP) references within tolerance.")
      } else {
        println("\nFAILURE: Scala results deviate from MATLAB (KP) references.")
        sys.exit(1)
      }
    } else {
      println("\nWARNING: MATLAB (KP) reference files not found for comparison.")
    }
  }
}
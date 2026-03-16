//#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
package uni.apps

//> using java-home /opt/jdk17
//> using dep org.vastblue:uni_3:0.10.1
//> using dep com.github.darrenjw::scala-glm:0.9

import uni.data.*
import uni.plot.*

object AirfoilNoise {
  val url = "http://archive.ics.uci.edu/ml/machine-learning-databases/00291/airfoil_self_noise.dat"
  val fileName = "airfoil_self_noise.csv"

  def main(args: Array[String]): Unit = {
    // download the file if not present
    val file = new java.io.File(fileName)
    if (!file.exists) {
      val s = new java.io.PrintWriter(file)
      val data = scala.io.Source.fromURL(url).getLines
      data.foreach(l => s.write(l.trim.
        split('\t').filter(_ != "").
        mkString("", ",", "\n")))
      s.close
    }

    //Once we have a CSV file on disk, we can load it up and look at it.
    val mat = MatD.readCsv(fileName)
    println("Dim: " + mat.rows + " " + mat.cols)
    mat.pairs(labels = Seq("Freq", "Angle", "Chord", "Velo", "Thick", "Sound"))
  }
}
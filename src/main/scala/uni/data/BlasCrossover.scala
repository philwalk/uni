//#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
package uni.data
//> using dep org.vastblue:uni_3:0.8.0
//> using dep "org.bytedeco:openblas-platform:0.3.30-1.5.12"
//> using javaOpt "-Dorg.bytedeco.javacpp.cachedir=C:\\tmp\\javacpp"

import uni.data.*
import scala.util.Random

object BlasCrossover {
  def main(args: Array[String]): Unit = {
    org.bytedeco.javacpp.Loader.load(classOf[org.bytedeco.openblas.global.openblas])

    println("=== Double crossover ===")
    warmupDouble()
    scanCrossoverDouble()

    println("\n=== Float crossover ===")
    warmupFloat()
    scanCrossoverFloat()
  }

  // ============================================================
  // Warmup - get JIT hot before measuring
  // ============================================================

  def warmupDouble(): Unit = {
    print("Warming up JIT (Double)...")
    val w = Mat.tabulate[Double](64, 64)((_, _) => Random.nextDouble())
    for _ <- 0 until 2000 do
      w.multiplyDouble(w)
      w.multiplyDoubleBLAS(w)
    println(" done.")
  }

  def warmupFloat(): Unit = {
    print("Warming up JIT (Float)...")
    val w = Mat.tabulate[Float](64, 64)((_, _) => Random.nextFloat())
    for _ <- 0 until 2000 do
      w.multiplyFloat(w)
      w.multiplyFloatBLAS(w)
    println(" done.")
  }

  // ============================================================
  // Measurement helpers
  // ============================================================

  // Scale iterations inversely with work to keep each measurement ~10-50ms total
  def iterationsFor(size: Int): Int = 
    val work = size.toLong * size * size
    val iters = (5_000_000_000L / work.max(1)).toInt
    iters.max(5).min(50000)

  // Measure median of `runs` timing samples
  def medianMs(runs: Int)(f: => Unit): Double =
    val samples = Array.tabulate(runs) { _ =>
      val t0 = System.nanoTime()
      f
      (System.nanoTime() - t0) / 1e6
    }
    val sorted = samples.sorted
    sorted(runs / 2)  // median

  // ============================================================
  // Double scan
  // ============================================================

  def scanCrossoverDouble(): Unit = {
    val sizes = 
      (2 to 30 by 1) ++
      (32 to 60 by 2) ++
      (64 to 128 by 8) ++
      (128 to 512 by 32)

    println(f"  ${"size"}%6s  ${"iters"}%7s  ${"BLAS ms"}%10s  ${"Pure ms"}%10s  ${"ratio"}%7s  ${"winner"}%6s")
    println(f"  ${"------"}%6s  ${"-----"}%7s  ${"-------"}%10s  ${"-------"}%10s  ${"-----"}%7s  ${"------"}%6s")

    var lastWinner = ""
    var crossover = -1

    for size <- sizes do
      val iters = iterationsFor(size)
      val m1 = Mat.tabulate[Double](size, size)((_, _) => Random.nextDouble())
      val m2 = Mat.tabulate[Double](size, size)((_, _) => Random.nextDouble())

      // median of 7 runs
      val blasMs = medianMs(7)(m1.multiplyDoubleBLAS(m2))
      val pureMs = medianMs(7)(m1.multiplyDouble(m2))

      val ratio  = pureMs / blasMs
      val winner = if blasMs < pureMs then "BLAS" else "Pure"

      // Detect crossover transition
      if winner == "BLAS" && lastWinner == "Pure" && crossover == -1 then
        crossover = size

      println(f"  $size%6d  $iters%7d  $blasMs%10.4f  $pureMs%10.4f  $ratio%7.2f  $winner%6s")
      lastWinner = winner

    if crossover > 0 then
      println(s"\nCrossover point: ~${crossover}x${crossover}")
      println(s"Recommended shouldUseBLAS threshold: ${crossover.toLong * crossover * crossover}")
    else
      println("\nNo clear crossover found in scanned range")
  }

  // ============================================================
  // Float scan
  // ============================================================

  def scanCrossoverFloat(): Unit = {
    val sizes =
      (2 to 30 by 1) ++
      (32 to 60 by 2) ++
      (64 to 128 by 8) ++
      (128 to 512 by 32)

    println(f"  ${"size"}%6s  ${"iters"}%7s  ${"BLAS ms"}%10s  ${"Pure ms"}%10s  ${"ratio"}%7s  ${"winner"}%6s")
    println(f"  ${"------"}%6s  ${"-----"}%7s  ${"-------"}%10s  ${"-------"}%10s  ${"-----"}%7s  ${"------"}%6s")

    var lastWinner = ""
    var crossover = -1

    for size <- sizes do
      val iters = iterationsFor(size)
      val m1 = Mat.tabulate[Float](size, size)((_, _) => Random.nextFloat())
      val m2 = Mat.tabulate[Float](size, size)((_, _) => Random.nextFloat())

      val blasMs = medianMs(7)(m1.multiplyFloatBLAS(m2))
      val pureMs = medianMs(7)(m1.multiplyFloat(m2))

      val ratio  = pureMs / blasMs
      val winner = if blasMs < pureMs then "BLAS" else "Pure"

      if winner == "BLAS" && lastWinner == "Pure" && crossover == -1 then
        crossover = size

      println(f"  $size%6d  $iters%7d  $blasMs%10.4f  $pureMs%10.4f  $ratio%7.2f  $winner%6s")
      lastWinner = winner

    if crossover > 0 then
      println(s"\nCrossover point: ~${crossover}x${crossover}")
      println(s"Recommended shouldUseBLAS threshold: ${crossover.toLong * crossover * crossover}")
    else
      println("\nNo clear crossover found in scanned range")
  }
}

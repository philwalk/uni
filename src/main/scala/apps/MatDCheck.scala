//#!/usr/bin/env -S scala-cli shebang -deprecation
package apps

//> using dep org.vastblue:uni_3:0.9.1

import uni.data.*
import uni.data.Mat.*

object MatDCheck {
  def main(args: Array[String]): Unit = {
    val m              = Mat.zeros[Double](3, 4) // .asInstanceOf[Mat[Double]]
    val n: Mat[Double] = MatD(3, 4) // .asInstanceOf[Mat[Double]]
    println(s"m: $m")
    println(s"n: $n")
    val col = m(::, 0)
    println(s"col: $col")

    val v = MatD.row(1, 2, 3, 4)
    println(s"v: $v")
    val w = MatD.ones(2, 2)
    println(s"w: $w")
    val eye = MatD.eye(2)
    println(s"eye: $eye")
    val r = MatD.arange(0, 10, 2)
    println(s"r: $r")
    val ls = MatD.linspace(0, 1, 5)
    println(s"ls: $ls")
    val result = m + v
    println(s"result: $result")
    val diff = w - eye
    println(s"diff: $diff")
    val prod = w * 2
    println(s"prod: $prod")
    val powr = w ~^ 2
    println(s"powr: $powr")
    val matMult = powr ~@ w
    println(s"matMult: $matMult")
    val div = w / 2
    println(s"div: $div")
    val mat_prod = w * eye
    println(s"mat_prod: $mat_prod")
    val sq = m.sqrt
    println(s"sq: $sq")
    val ex = v.exp
    println(s"ex: $ex")
    val lg = v.log
    println(s"lg: $lg")
    val ab = v.abs
    println(s"ab: $ab")
    val cl = v.clip(0, 5)
    println(s"cl: $cl")
    val s = m.sum
    println(s"s: $s")
    val s0 = m.sum(0)
    println(s"s0: $s0")
    val s1 = m.sum(1)
    println(s"s1: $s1")
    val mn = m.mean
    println(s"mn: $mn")
    val mx = m.max
    println(s"mx: $mx")
    val A = MatD((1, 2), (3, 4))
    println(s"A: $A")
    val b = MatD.row(5, 6)
    println(s"b: $b")
    val x = A.solve(b)
    println(s"x: $x")
    val inv_A = A.inverse
    println(s"inv_A: $inv_A")
    val det_A = A.determinant
    println(s"det_A: $det_A")
    val _us2vt = A.svd
    println(s"_us2vt: $_us2vt")
    val U = _us2vt._1
    println(s"U: $U")
    val s2 = _us2vt._2
    println(s"s2: ${s2.mkString(",")}")
    val Vt = _us2vt._3
    println(s"Vt: $Vt")
    val _qr = A.qrDecomposition
    println(s"_qr: $_qr")
    val Q = _qr._1
    println(s"Q: $Q")
    val R = _qr._2
    println(s"R: $R")
    val mask = v.gt(1)
    println(s"mask: $mask")
    val filtered = v(mask)
    println(s"filtered: $filtered")
    v(v.lt(0)) = 0
    println(s"v: $v")
    val sub = m(0 until 2, ::)
    println(s"sub: $sub")
    val col_ = m(::, 0)
    println(s"col_: $col_")
    val row_ = m(0, ::)
    println(s"row_: $row_")
    val step = m(0 until m.rows by 2, ::)
    println(s"step: $step")
    val flat = m.flatten
    println(s"flat: ${flat.mkString(",")}")
    val reshaped = m.reshape(2, 6)
    println(s"reshaped: $reshaped")
    val transposed = m.T
    println(s"transposed: $transposed")
    val stacked = MatD.vstack(w, eye)
    println(s"stacked: $stacked")
    val hstacked = MatD.hstack(w, eye)
    println(s"hstacked: $hstacked")
    val result2 = MatD.where(v.gt(0), v, -v)
    println(s"result2: $result2")
    val rand_m = MatD.rand(3, 3)
    println(s"rand_m: $rand_m")
    val randn_m = MatD.randn(3, 3)
    println(s"randn_m: $randn_m")
    val med = v.median
    println(s"med: $med")
    val pct = v.percentile(75)
    println(s"pct: $pct")

    def normalize(x: MatD): MatD = {
      (x - x.mean) / x.std
    }
    m :+= 1
    println(s"m: ${m.show}")
    val biz = normalize(rand_m * 2)
    println(s"biz: $biz")
    for i <- 0 until 3 do
      m(i, ::) = i * 2
    m :*= 2
    println(s"m: ${m.show}")
  }
}

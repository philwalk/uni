#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
//> using dep org.vastblue:uni_3:0.19.2

// One half of the cross-language demo pair; `rust/examples/matdcalc.rs` is the other.
// Both run the same MatD program on the same seeded matrix and print byte-identical
// output on any machine — the matrix core end to end: creation, views, broadcasting,
// reductions, the pinned matmul, masks, exact linear algebra, pandas-style ops,
// stacking, CSV text. The two files are written to read line for line alike.
//
//   scala-cli run jsrc/matdcalc.sc > scala.out
//   cargo run --release --manifest-path rust/Cargo.toml --example matdcalc > rust.out
//   diff scala.out rust.out
//
// Matrices print through Double.toString (the CSV cell rendering both ports share);
// scalars that could differ in the last ulp between LAPACK-free ports (svd) print
// through Big + numStr at four decimals, whose rendering is parity-pinned.
object Matdcalc {
  def println(s: String = ""): Unit = print(s"$s\n")

  import uni.data.*

  def cells(m: MatD): String = m.toArray.map(_.toString).mkString(", ")
  def num(d: Double): String = numStr(Big(d), NumFormat(colWidth = 1, dec = 4)).trim

  def main(args: Array[String]): Unit = {
    Mat.setSeed(20260818)
    val m = MatD.rand(4, 3)
    println(s"m ${m.rows}x${m.cols}: [${cells(m)}]")
    println(s"m(1, 2) = ${m(1, 2)}   m(-1, -1) = ${m(-1, -1)}")
    println()

    println("views and slices:")
    println(s"  m.T shape ${m.T.rows}x${m.T.cols}")
    println(s"  m(1, ::)            [${cells(m(1, ::))}]")
    println(s"  m(::, 2)            [${cells(m(::, 2))}]")
    println(s"  m(0 until 2, 1 until 3) [${cells(m(0 until 2, 1 until 3))}]")
    println(s"  m(Array(3, 0), ::)  [${cells(m(Array(3, 0), ::))}]")
    println()

    println("arithmetic and broadcasting:")
    println(s"  m * 2.0 + 1.0       [${cells(m * 2.0 + 1.0)}]")
    println(s"  m - m.mean(0)       [${cells(m - m.mean(0))}]")
    println(s"  m / m.sum(1)        [${cells(m / m.sum(1))}]")
    println(s"  m.exp.log ~ m       ${(m.exp.log - m).abs.max < 1e-12}")
    println()

    println("reductions:")
    println(s"  sum ${m.sum}   mean ${m.mean}   std ${m.std}   min ${m.min}   max ${m.max}")
    println(s"  argmax ${m.argmax}   argmin ${m.argmin}")
    println(s"  sum(0)  [${cells(m.sum(0))}]")
    println(s"  mean(1) [${cells(m.mean(1))}]")
    println(s"  cumsum  [${cells(m.cumsum)}]")
    println()

    println("matmul (the pinned loop, bit-identical in both ports):")
    val g = m.T.matmulPure(m)
    println(s"  m.T *@ m 3x3 [${cells(g)}]")
    println(s"  trace ${g.trace}   norm(fro) ${num(g.norm("fro"))}")
    println()

    println("masks:")
    val mask = m > 0.5
    println(s"  (m > 0.5).count ${mask.sum}   any ${mask.any}   all ${mask.all}")
    println(s"  m(mask)         [${cells(m(mask))}]")
    println(s"  where(mask,1,0) [${cells(Mat.where(mask, 1.0, 0.0))}]")
    println(s"  between(0.2, 0.6).count ${m.between(0.2, 0.6).sum}")
    println()

    println("linear algebra (exact ports):")
    val a = MatD((4.0, 2.0), (1.0, 3.0))
    println(s"  A [${cells(a)}]   det ${a.determinant}")
    println(s"  A.inverse [${cells(a.inverse)}]")
    println(s"  A.solve([1, 2]) [${cells(a.solve(MatD.col(1.0, 2.0)))}]")
    val (_, s, _) = a.svd
    println(s"  singular values ${s.map(num).mkString(", ")}")
    println(s"  A *@ A.inverse ~ I ${(a.matmulPure(a.inverse) - MatD.eye(2)).abs.max < 1e-12}")
    println()

    println("pandas-style:")
    val c0 = m(::, 0)
    println(s"  col0 sorted     [${cells(c0.sort())}]")
    println(s"  col0 argsort    [${c0.argsort().toArray.mkString(", ")}]")
    println(s"  col0 median ${c0.median}   percentile(25) ${c0.percentile(25)}")
    println(s"  col0 rolling(2).mean [${cells(c0.rolling(2).mean)}]")
    println(s"  col0 diff       [${cells(c0.diff)}]")
    println(s"  m.nlargest(2)   [${cells(m.nlargest(2))}]")
    println()

    println("stacking and CSV:")
    val stacked = Mat.vstack(m, m.mean(0))
    println(s"  vstack(m, m.mean(0)) ${stacked.rows}x${stacked.cols}   hstack(m, m.sum(1)) ${Mat.hstack(m, m.sum(1)).rows}x${Mat.hstack(m, m.sum(1)).cols}")
    val row0 = m(0, ::)
    println(s"  csv row 0: ${row0.toArray.map(_.toString).mkString(",")}")
  }
}

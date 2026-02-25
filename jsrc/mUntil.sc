#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.8.2

import uni.data.*
import Mat.*

object MUntil {
  def main(args: Array[String]): Unit =
    val m = Mat[Double](
      (1, 2, 3, 4, 5, 6),
      (4, 5, 6, 7, 8, 9),
      (2, 4, 6, 8, 9, -1),
      (0, 4, 1, 3, 5, 2),
      (9, 1, 2, 1, 5, 8),
      (2, 1, 3, 4, 2, 8),
    )
    // NumPy: m[:, 1]
    val col = m(::, 1)
    print(s"col:${col.show}\n")
    val x = m(0 until 2, ::) 
    print(s"x:${x.show}\n")
    val y = m(0 until 6 by 2, ::)
    print(s"y:${y.show}\n")
    val z = m(::, 0 until 6 by 2)
    print(s"z:${z.show}\n")
}
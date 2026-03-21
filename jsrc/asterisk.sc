#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.1

import uni.data.*

object Bat {
  val * = ()
  
  def main(args: Array[String]): Unit =
    apply(*, ::)
    apply(::, *)

  def apply(arg1: Any, arg2: Any) =
    (arg1, arg2) match {
    case ((), ::) =>
      printf("broadcast rows\n")
    case (::, ()) =>
      printf("broadcast cols\n")
    case (a, b) =>
      printf("%s, %s\n", arg1, arg2)
    }

}

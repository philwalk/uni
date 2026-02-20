#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.8.2

import uni.data.*

Mat.setSeed(42)
println((0 until 5).map(_ => Mat.nextRandLong))
// expected:
// [383329928, 3324115917, 2811363265, 1884968545, 1859786276]

Mat.setSeed(42) // need to re-seed!
val expected = Array(51, 92, 14, 71, 60)
val actual = (0 until 5).map(_ => Mat.randint(0, 100))
printf("expect:\n%s\n", expected.mkString(", "))
printf("actual:\n%s\n", actual.mkString(", "))

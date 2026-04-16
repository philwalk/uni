#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.12.1
import uni.data.*
import uni.plot.*
val m = MatD.randn(100, 3)
m.plot(title = "randn 100x3")
m.scatter(0, 1, title = "col0 vs col1")
m.hist(bins = 30, title = "histogram")
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.12.3

import uni.data.*

var m = MatD.zeros(3, 3)
println(s"m: $m")
var col_ = m(::, 0)
var sub = m(0 until 2, ::)
var row_ = m(0, ::)
var step = m(0 until m.rows by 2, ::)
var flat = m.flatten
var reshaped = m.reshape(3, 3)
var transposed = m.T
val w = MatD.ones(3, 3)
val e = MatD.eye(3)
var stacked = MatD.vstack(w, e)
var hstacked = MatD.hstack(w, e)
val v = MatD.arange(1.0, 4.0)
var result = MatD.where(v.gt(0), v, -v)
var rand_m = MatD.rand(3, 3)
var randn_m = MatD.randn(3, 3)
var med = v.median
var pct = v.percentile(75)
def normalize(x: MatD): MatD =
  (x - x.mean) / x.std
for i <- 0 until 3 do
  m(i, ::) = i * 2
m :+= 1
m :*= 2
print(result.show)

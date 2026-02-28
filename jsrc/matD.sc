#!/usr/bin/env -S scala-cli shebang -deprecation

//> using dep org.vastblue:uni_3:0.9.2

import uni.data.*
//import uni.data.Mat.*

var m = MatD.zeros(3, 4)
println(s"m: $m")
var col_ = m(::, 0)
var sub = m(0 until 2, ::)
var row_ = m(0, ::)
var step = m(0 until m.rows by 2, ::)
var flat = m.flatten
var reshaped = m.reshape(2, 6)
var transposed = m.T
var stacked = MatD.vstack(w, eye)
var hstacked = MatD.hstack(w, eye)
var result2 = MatD.where(v.gt(0), v, -v)
var rand_m = MatD.rand(3, 3)
var randn_m = MatD.randn(3, 3)
var med = v.median
var pct = v.percentile(75)
def normalize(x: MatD): MatD = {
  x - x.mean / x.std
}
for i <- 0 until 3 do
  m(i, ::) = i * 2
m :+= 1
m :*= 2
print(result.show)
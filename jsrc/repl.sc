#!/usr/bin/env -S scala-cli repl
//> using dep org.vastblue:uni_3:0.9.2
import uni.*
import uni.time.*
import uni.data.*
import uni.data.Mat
import uni.data.Mat.*

Mat.setSeed(0)
var scflag = false
var numfmt = "%1.12f"
var zmat = Mat.randn(5, 3)
var xmat = Mat.randn(5, 4)
var mmat = zmat.T ~@ xmat
print(zmat.show+"\n")
print(xmat.show+"\n")
print(mmat.show+"\n")
var m = Mat[Double]((1, 2), (3, 4))
printf("%s\n", m.show)
m = Mat[Double]((1, -0.2), (0.0003, 4001.000004))
val str = m.show("%1.2f")
printf("%s\n", str)

m = Mat.empty[Double]
printf("%s\n", m.show)
var mf = Mat[Float]((1, 2), (3, 4))
printf("%s\n", mf.show)

mf = Mat.empty[Float]
printf("%s\n", mf.show)
var mb = Mat[Big]((1, 2), (3, 4))
printf("%s\n", mb.show)

mb = Mat.empty[Big]
printf("%s\n", m.show)
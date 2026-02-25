#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.0

import uni.data.Mat

val m = Mat[Double](
  (10.0, 20.0),
  (30.0, 40.0)
)

// Create a mask where values > 25
// Note: Assuming your '>' operator returns 1.0 for true, 0.0 for false
val mask = m > 25.0 

// Use the mask to pull values
val result = m(mask) 

println(s"Original:\n${m.show}")
println(s"Mask (m > 25):\n${mask.show}")
println(s"Filtered Result: ${result.show}") 
// Expected: Mat(30.0, 40.0)

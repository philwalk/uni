#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.13.3

import uni.data.*
import uni.plot.*
import java.awt.Color


val cols = Seq("A", "B", "C", "D")

val df = MatD.of(
  2.431645, 1.248688, 0.267648, 0.613826,
  0.809296, 1.671020, 1.564420, 0.347662,
  1.501939, 1.126518, 0.702019, 1.596048,
  0.137160, 0.147368, 1.504663, 0.202822,
  0.134540, 3.708104, 0.309097, 1.641090
).reshape(5, 4)
printf("%s\n", df)

// df.corr() in pandas = correlation between columns = corrcoef of transposed
val corr = df.T.corrcoef

corr.heatmap(
  title     = "Correlation Heatmap",
  rowLabels = cols,
  colLabels = cols,
  style     = PlotStyle(
    width        = 600,
    height       = 600,
    seriesColors = Seq(Color.RED, Color.WHITE, Color.BLUE)
  ))
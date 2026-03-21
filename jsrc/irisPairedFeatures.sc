#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.1

import uni.data.*
import uni.plot.*
import java.awt.{Color, Font}

// Fisher Iris dataset — 4 features, 150 samples, 3 species
// Columns: sepal_length(0), sepal_width(1), petal_length(2), petal_width(3)
val iris = MatD.readCsv("datasets/iris.csv")

iris.pairs(
  title        = "Iris: scatterplot matrix",
  labels       = Seq("sepal_l", "sepal_w", "petal_l", "petal_w"),
  bins         = 10,
  dotSize      = 9,
  scatterAlpha = 100,
  color        = Color(31, 119, 180),
  labelStyle   = Font.BOLD,
  style = PlotStyle(width = 1500, height = 900)
)
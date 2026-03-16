#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.10.0
import uni.data.*
import uni.plot.*
import java.awt.Color

val iris = MatD.readCsv("datasets/iris.csv")
// columns: sepal_length(0), sepal_width(1), petal_length(2), petal_width(3)

// Base style shared across all three charts — consistent 900×600 window
val base = PlotStyle(width = 900, height = 600)

// Scatter: steel-blue points
iris.scatter(2, 3,
  title = "Iris: petal length vs petal width",
  style = base.copy(seriesColors = Seq(new Color(31, 119, 180))))

// Histogram: warm amber fill
iris.hist(bins = 20,
  title = "Iris: sepal length distribution",
  style = base.copy(seriesColors = Seq(new Color(255, 127, 14))))

// Line plot: matplotlib tab10 palette, one colour per feature
iris.plot(
  title  = "Iris: all 4 features",
  labels = Seq("sepal length", "sepal width", "petal length", "petal width"),
  style  = base.copy(seriesColors = Seq(
    new Color( 31, 119, 180),   // sepal length — blue
    new Color(255, 127,  14),   // sepal width  — orange
    new Color( 44, 160,  44),   // petal length — green
    new Color(214,  39,  40),   // petal width  — red
  )))

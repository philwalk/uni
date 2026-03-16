#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

// Generates docs/images/iris-*.png for the README.
// Run from project root: scala-cli jsrc/gen-images.sc

//> using dep org.vastblue:uni_3:0.10.0
import uni.*
import uni.plot.*
import java.awt.Color

val base = PlotStyle(width = 900, height = 600)
//java.nio.file.Files.createDirectories(Paths.get("docs/images"))

val iris = MatD.readCsv("datasets/iris.csv")
// columns: sepal_length(0), sepal_width(1), petal_length(2), petal_width(3)

iris.scatter(2, 3,
  title  = "Iris: petal length vs petal width",
  saveTo = "docs/images/iris-scatter",
  style = base.copy(seriesColors = Seq(new Color(31, 119, 180))))

iris.hist(bins = 20,
  title  = "Iris: sepal length distribution",
  saveTo = "docs/images/iris-hist",
  style = base.copy(seriesColors = Seq(new Color(255, 127, 14))))

iris.plot(
  title  = "Iris: all 4 features",
  labels = Seq("sepal length", "sepal width", "petal length", "petal width"),
  saveTo = "docs/images/iris-lines",
  style  = base.copy(seriesColors = Seq(
    new Color( 31, 119, 180),   // sepal length — blue
    new Color(255, 127,  14),   // sepal width  — orange
    new Color( 44, 160,  44),   // petal length — green
    new Color(214,  39,  40),   // petal width  — red
  )))

println("Saved docs/images/iris-scatter.png, iris-hist.png, iris-lines.png")

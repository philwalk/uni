#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

// Generates docs/images/iris-*.png for the README.
// Run from project root: scala-cli jsrc/gen-images.sc

//> using dep org.vastblue:uni_3:0.10.0
import uni.data.*
import uni.plot.*
import java.awt.Color

val iris = MatD.readCsv("datasets/iris.csv")
// columns: sepal_length(0), sepal_width(1), petal_length(2), petal_width(3)
// rows 0–49: setosa (0),  50–99: versicolor (1),  100–149: virginica (2)

// ── augment with species label column (0 / 1 / 2) ────────────────────────────
val species = MatD.col((Array.fill(50)(0.0) ++ Array.fill(50)(1.0) ++ Array.fill(50)(2.0))*)
val irisExt = MatD.hstack(iris, species)
// irisExt: sepal_l(0) sepal_w(1) petal_l(2) petal_w(3) species(4)

val features = Seq("sepal_l", "sepal_w", "petal_l", "petal_w")
val tab10    = Seq(
  new Color( 31, 119, 180),  // blue
  new Color(255, 127,  14),  // orange
  new Color( 44, 160,  44),  // green
  new Color(214,  39,  40),  // red
)
val uniform = PlotStyle(width = 900, height = 600)
val sq      = PlotStyle(width = 700, height = 700)

// 1. Line plot — all 4 features over sample index
iris.plot(
  title  = "Iris: all 4 features",
  labels = features,
  saveTo = "docs/images/iris-lines",
  style  = uniform.copy(xLabel = "sample index", yLabel = "cm", seriesColors = tab10))

// 2. Grouped scatter — petal length vs petal width, coloured by species
irisExt.scatter(xCol = 2, yCol = 3, groupCol = 4,
  title  = "Iris: petal length vs petal width (by species)",
  saveTo = "docs/images/iris-scatter-grouped",
  style  = sq.copy(xLabel = "petal length (cm)", yLabel = "petal width (cm)"))

// 3. Plain scatter (kept for backward compatibility)
iris.scatter(2, 3,
  title  = "Iris: petal length vs petal width",
  saveTo = "docs/images/iris-scatter",
  style  = sq.copy(seriesColors = Seq(new Color(31, 119, 180))))

// 4. Histogram — sepal length distribution
iris.hist(bins = 20,
  title  = "Iris: sepal length distribution",
  saveTo = "docs/images/iris-hist",
  style  = uniform.copy(xLabel = "sepal length (cm)", yLabel = "count",
    seriesColors = Seq(new Color(255, 127, 14))))

// 5. Bar chart — mean value per feature
val featureMeans = MatD.col(
  (0 until iris.cols).map(c => iris(::, c).flatten.sum / iris.rows)*)
featureMeans.bar(
  title  = "Iris: mean feature values  (0=sepal_l 1=sepal_w 2=petal_l 3=petal_w)",
  saveTo = "docs/images/iris-bar",
  style  = uniform.copy(xLabel = "feature index", yLabel = "cm",
    seriesColors = Seq(new Color(31, 119, 180))))

// 6. Correlation heatmap — computed via corrcoef (NumPy convention: rows = variables)
val corr = iris.T.corrcoef
corr.heatmap(
  title     = "Iris: feature correlation matrix",
  rowLabels = features,
  colLabels = features,
  saveTo    = "docs/images/iris-corr",
  style     = PlotStyle(width = 720, height = 680))

// 7. Box plot — per-feature distribution (median, IQR, outliers)
iris.boxPlot(
  title  = "Iris: feature distributions",
  labels = features,
  saveTo = "docs/images/iris-box",
  style  = uniform.copy(yLabel = "cm"))

println("Saved docs/images/:")
for name <- Seq("iris-lines", "iris-scatter-grouped", "iris-scatter",
                "iris-hist",  "iris-bar",              "iris-corr", "iris-box") do
  println(s"  $name.png")

#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

// Iris correlation demo — pops up three windows.
// Run from project root:  scala-cli jsrc/corr.sc
//
// Window 1 — heatmap of the 4×4 Pearson correlation matrix
// Window 2 — petal_length vs petal_width (r ≈ 0.96, very strong)
// Window 3 — sepal_width  vs petal_length (r ≈ -0.43, weak negative)
//
// The Iris dataset is ideal for this demo:
//   petal_l ↔ petal_w   r ≈  0.96  (nearly perfect linear relationship)
//   sepal_l ↔ petal_l   r ≈  0.87  (strong positive)
//   sepal_w ↔ petal_l   r ≈ -0.43  (weak negative — sepal width is nearly independent)
//   sepal_w ↔ sepal_l   r ≈ -0.12  (no meaningful relationship)

//> using dep org.vastblue:uni_3:0.10.1
import uni.data.*
import uni.plot.*

val iris = MatD.readCsv("datasets/iris.csv")
// columns: sepal_length(0), sepal_width(1), petal_length(2), petal_width(3)
// rows 0–49: setosa,  50–99: versicolor,  100–149: virginica

// ── species label column (0 = setosa, 1 = versicolor, 2 = virginica) ─────────
val species = MatD.col((Array.fill(50)(0.0) ++ Array.fill(50)(1.0) ++ Array.fill(50)(2.0))*)
val irisExt = MatD.hstack(iris, species)

val features = Seq("sepal_l", "sepal_w", "petal_l", "petal_w")

// ── correlation matrix (NumPy convention: rows = variables, cols = observations)
val corr = iris.T.corrcoef

// ── print to console ──────────────────────────────────────────────────────────
println("\nIris feature correlation matrix (Pearson r):\n")
val pad = 10
print(" " * (pad + 2))
features.foreach(f => print(s"%-${pad}s  ".format(f)))
println()
for i <- 0 until corr.rows do
  print(s"  %-${pad}s".format(features(i)))
  for j <- 0 until corr.cols do
    print(f"  ${corr(i, j)}%+.3f     ")
  println()
println()

// ── window 1: correlation heatmap ─────────────────────────────────────────────
corr.heatmap(
  title     = "Iris: feature correlation matrix",
  rowLabels = features,
  colLabels = features,
  style     = PlotStyle(width = 720, height = 680))

// ── window 2: strongly correlated — petal_length vs petal_width ───────────────
irisExt.scatter(xCol = 2, yCol = 3, groupCol = 4,
  title = "Iris: petal_l vs petal_w   [r ≈ 0.96 — very strong positive]",
  style = PlotStyle(width = 700, height = 700,
    xLabel = "petal length (cm)", yLabel = "petal width (cm)"))

// ── window 3: weakly correlated — sepal_width vs petal_length ─────────────────
irisExt.scatter(xCol = 1, yCol = 2, groupCol = 4,
  title = "Iris: sepal_w vs petal_l   [r ≈ −0.43 — weak negative]",
  style = PlotStyle(width = 700, height = 700,
    xLabel = "sepal width (cm)", yLabel = "petal length (cm)"))
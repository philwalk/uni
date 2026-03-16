#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.10.0
import uni.data.*
import uni.plot.*
import java.awt.Color

// Anscombe's Quartet: four datasets with identical summary statistics,
// wildly different shapes — the classic argument for always plotting your data.

val xs  = MatD.col(10, 8, 13, 9, 11, 14,  6,  4, 12,  7,  5)   // shared x for I–III
val xs4 = MatD.col( 8, 8,  8, 8,  8,  8,  8, 19,  8,  8,  8)   // x for IV

def ds(x: MatD, ys: Double*): MatD = MatD.hstack(x, MatD.col(ys*))

val I   = ds(xs,  8.04, 6.95,  7.58, 8.81, 8.33,  9.96, 7.24,  4.26, 10.84, 4.82, 5.68)
val II  = ds(xs,  9.14, 8.14,  8.74, 8.77, 9.26,  8.10, 6.13,  3.10,  9.13, 7.26, 4.74)
val III = ds(xs,  7.46, 6.77, 12.74, 7.11, 7.81,  8.84, 6.08,  5.39,  8.15, 6.42, 5.73)
val IV  = ds(xs4, 6.58, 5.76,  7.71, 8.84, 8.47,  7.04, 5.25, 12.50,  5.56, 7.91, 6.89)

// Consistent 700×700 square windows — scatter plots read better when axes are equal scale
// Each dataset gets a distinct matplotlib tab10 colour
val base = PlotStyle(width = 700, height = 700)

I.scatter(title   = "Anscombe I — linear",
  style = base.copy(seriesColors = Seq(new Color( 31, 119, 180))))  // blue

II.scatter(title  = "Anscombe II — curved",
  style = base.copy(seriesColors = Seq(new Color(255, 127,  14))))  // orange

III.scatter(title = "Anscombe III — outlier",
  style = base.copy(seriesColors = Seq(new Color(214,  39,  40))))  // red

IV.scatter(title  = "Anscombe IV — vertical cluster",
  style = base.copy(seriesColors = Seq(new Color(148, 103, 189))))  // purple

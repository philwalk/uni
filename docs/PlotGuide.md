# uni.plot — Plot Guide

`uni.plot` adds interactive and file-based charting directly on `MatD` via XChart (GGPlot2 theme).

```scala
import uni.data.*
import uni.plot.*
```

---

## Methods

### `.plot()` — line chart

One series per column; x-axis = row indices.

```scala
m.plot(
  title  = "my chart",               // window / PNG title
  labels = Seq("col0", "col1"),      // series labels (defaults: "col0", "col1", …)
  saveTo = "out/chart",              // saves out/chart.png; omit to show window
  style  = PlotStyle(width = 900, height = 600),
)
```

### `.scatter()` — scatter plot

Two columns plotted against each other.

```scala
m.scatter(
  xCol   = 0,                        // column index for x-axis (default 0)
  yCol   = 1,                        // column index for y-axis (default 1)
  title  = "x vs y",
  saveTo = "out/scatter",
  style  = PlotStyle(width = 700, height = 700),
)
```

### `.hist()` — histogram

All values in the matrix binned and plotted as an area chart.

```scala
m.hist(
  bins   = 20,                       // number of bins (default 20)
  title  = "distribution",
  saveTo = "out/hist",
  style  = PlotStyle(width = 800, height = 500),
)
```

---

## `PlotStyle`

All fields are optional — omit any field to keep the GGPlot2 theme default.

| Field | Type | Default | Effect |
| :--- | :--- | :--- | :--- |
| `width` | `Int` | method-specific | chart width in pixels |
| `height` | `Int` | method-specific | chart height in pixels |
| `background` | `Option[Color]` | `None` | outer chart background |
| `plotBackground` | `Option[Color]` | `None` | inner plot-area background |
| `foreground` | `Option[Color]` | `None` | axis labels and title colour |
| `seriesColors` | `Seq[Color]` | `Nil` | per-series colours in order |

### Named presets

| Preset | Value | Use case |
| :--- | :--- | :--- |
| `PlotStyle.uniform` | `PlotStyle(width = 800, height = 500)` | Consistent dimensions for saved images |

### Examples

**Resize only:**
```scala
m.scatter(style = PlotStyle(width = 1400, height = 900))
```

**Dark background:**
```scala
import java.awt.Color
m.hist(style = PlotStyle(
  background     = Some(Color.BLACK),
  plotBackground = Some(new Color(30, 30, 30)),
  foreground     = Some(Color.WHITE),
))
```

**Custom series colours:**
```scala
import java.awt.Color
m.plot(style = PlotStyle(
  seriesColors = Seq(Color.RED, Color.BLUE, Color.GREEN),
))
```

**Uniform export size for documentation:**
```scala
m.scatter(2, 3, saveTo = "docs/images/iris-scatter", style = PlotStyle.uniform)
m.hist(bins = 20, saveTo = "docs/images/iris-hist",  style = PlotStyle.uniform)
// → both PNGs are exactly 800×500
```

---

## Demo scripts

| Script | Description |
| :--- | :--- |
| [`jsrc/iris.sc`](../jsrc/iris.sc) | Interactive: scatter, histogram, and line plot on the Fisher Iris dataset |
| [`jsrc/anscombe.sc`](../jsrc/anscombe.sc) | Interactive: Anscombe's Quartet — four scatter plots with identical statistics |
| [`jsrc/gen-images.sc`](../jsrc/gen-images.sc) | Headless: regenerates all `docs/images/iris-*.png` at uniform size |

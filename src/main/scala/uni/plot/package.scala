package uni.plot

import uni.data.MatD
import java.awt.{Color, Font}
import java.nio.file.{Files, Path}

/** Display and export styling for uni.plot charts.
 *
 *  Pass to any plot method; `None` fields defer to the theme default (white chart, grey
 *  plot area with white gridlines, a ten-colour series palette).
 *  Use [[PlotStyle.uniform]] for consistent dimensions when saving to files.
 *
 *  @param width          chart width in pixels
 *  @param height         chart height in pixels
 *  @param background     outer chart background (`None` = theme default)
 *  @param plotBackground inner plot-area background (`None` = theme default)
 *  @param foreground     axis labels and title colour (`None` = theme default)
 *  @param seriesColors   per-series colours in order (`Nil` = theme default); on a heatmap,
 *                        two or more become the gradient stops
 *  @param xLabel         x-axis label (`""` = no label)
 *  @param yLabel         y-axis label (`""` = no label)
 *  @param xLog           logarithmic x-axis (`plot`, `scatter`)
 *  @param yLog           logarithmic y-axis (`plot`, `scatter`)
 */
case class PlotStyle(
  width:          Int           = 800,
  height:         Int           = 500,
  background:     Option[Color] = None,
  plotBackground: Option[Color] = None,
  foreground:     Option[Color] = None,
  seriesColors:   Seq[Color]    = Nil,
  xLabel:         String        = "",
  yLabel:         String        = "",
  xLog:           Boolean       = false,
  yLog:           Boolean       = false,
)

object PlotStyle:
  /** Uniform 800×500 size — use for `saveTo` calls so all README/doc
   *  images share the same dimensions and aspect ratio. */
  val uniform = PlotStyle(width = 800, height = 500)

/**
 * Where a rendered chart goes. Every `uni.plot` method renders to SVG text (the `*Svg`
 * twin returns it) and then either saves it or shows it:
 *
 *   - `saveTo` non-empty: written to `saveTo` when that ends in `.svg` or `.html`,
 *     otherwise to `saveTo + ".svg"`; directories are created.
 *   - `saveTo` empty: an HTML page around the SVG is written to a temp file and opened
 *     in the default browser (`rundll32 url.dll,FileProtocolHandler` on Windows, `open`
 *     on macOS, `xdg-open` elsewhere). With `UNI_PLOT_NO_OPEN` set, or when no opener
 *     can be started, the path is printed instead — so a headless run never fails.
 */
private[plot] object Deliver:
  def apply(svg: String, title: String, saveTo: String): Unit =
    if saveTo.nonEmpty then { save(svg, title, saveTo); () }
    else
      val tmp = Files.createTempFile("uni-plot-", ".html")
      Files.writeString(tmp, Svg.html(svg, title))
      if sys.env.contains("UNI_PLOT_NO_OPEN") || !openInBrowser(tmp) then
        print(s"uni.plot: $tmp\n")

  /** The file actually written (`saveTo` with the extension rule applied). */
  def save(svg: String, title: String, saveTo: String): Path =
    val target =
      if saveTo.endsWith(".svg") || saveTo.endsWith(".html") then saveTo else s"$saveTo.svg"
    val path = uni.Paths.get(target)
    Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
    val body = if target.endsWith(".html") then Svg.html(svg, title) else svg
    Files.writeString(path, body)
    path

  def openInBrowser(path: Path): Boolean =
    val p  = path.toAbsolutePath.toString
    val os = sys.props.getOrElse("os.name", "").toLowerCase
    val cmd =
      if os.contains("win") then Seq("rundll32", "url.dll,FileProtocolHandler", p)
      else if os.contains("mac") then Seq("open", p)
      else Seq("xdg-open", p)
    try
      new ProcessBuilder(cmd*).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
      true
    catch case scala.util.control.NonFatal(_) => false

// ── extension methods ─────────────────────────────────────────────────────────

extension (m: MatD)

  /** Line plot — one series per column, x-axis = row indices. */
  def plot(
      title: String = "",
      labels: Seq[String] = Nil,
      saveTo: String = "",
      style: PlotStyle = PlotStyle(width = 900, height = 600)
  ): Unit = Deliver(plotSvg(title, labels, style), title, saveTo)

  /** The SVG text [[plot]] renders. */
  def plotSvg(
      title: String = "",
      labels: Seq[String] = Nil,
      style: PlotStyle = PlotStyle(width = 900, height = 600)
  ): String = Svg.plot(m, title, labels, style)

  /** Scatter plot of two columns (default: col 0 vs col 1).
   *  If `groupCol >= 0`, points are split into per-group series, one series per
   *  distinct value in that column (useful for colour-by-class plots). */
  def scatter(xCol: Int = 0, yCol: Int = 1, title: String = "", saveTo: String = "",
              groupCol: Int = -1,
              style: PlotStyle = PlotStyle(width = 700, height = 700)): Unit =
    Deliver(scatterSvg(xCol, yCol, title, groupCol, style), title, saveTo)

  /** The SVG text [[scatter]] renders. */
  def scatterSvg(xCol: Int = 0, yCol: Int = 1, title: String = "", groupCol: Int = -1,
                 style: PlotStyle = PlotStyle(width = 700, height = 700)): String =
    Svg.scatter(m, xCol, yCol, groupCol, title, style)

  /** Histogram of all values in the matrix. */
  def hist(bins: Int = 20, title: String = "", saveTo: String = "",
           style: PlotStyle = PlotStyle(width = 800, height = 500)): Unit =
    Deliver(histSvg(bins, title, style), title, saveTo)

  /** The SVG text [[hist]] renders. */
  def histSvg(bins: Int = 20, title: String = "",
              style: PlotStyle = PlotStyle(width = 800, height = 500)): String =
    Svg.hist(m, bins, title, style)

  /** Bar chart — one bar per row in `col`.
   *  X-axis labels come from `labelCol` if given, otherwise row indices are used. */
  def bar(col: Int = 0, labelCol: Int = -1, title: String = "", saveTo: String = "",
          style: PlotStyle = PlotStyle(width = 800, height = 500)): Unit =
    Deliver(barSvg(col, labelCol, title, style), title, saveTo)

  /** The SVG text [[bar]] renders. */
  def barSvg(col: Int = 0, labelCol: Int = -1, title: String = "",
             style: PlotStyle = PlotStyle(width = 800, height = 500)): String =
    Svg.bar(m, col, labelCol, title, style)

  /** Heatmap — renders the matrix as a colour grid with a colour bar.
   *  Useful for correlation matrices and confusion matrices.
   *  `rowLabels` / `colLabels` replace numeric axis ticks when provided. */
  def heatmap(title: String = "", rowLabels: Seq[String] = Nil, colLabels: Seq[String] = Nil,
              saveTo: String = "",
              style: PlotStyle = PlotStyle(width = 800, height = 700)): Unit =
    Deliver(heatmapSvg(title, rowLabels, colLabels, style), title, saveTo)

  /** The SVG text [[heatmap]] renders. */
  def heatmapSvg(title: String = "", rowLabels: Seq[String] = Nil, colLabels: Seq[String] = Nil,
                 style: PlotStyle = PlotStyle(width = 800, height = 700)): String =
    Svg.heatmap(m, title, rowLabels, colLabels, style)

  /** Box plot — one box per column, showing median, quartiles, and outliers.
   *  `labels` names the boxes; defaults to `"col0"`, `"col1"`, … */
  def boxPlot(title: String = "", labels: Seq[String] = Nil, saveTo: String = "",
              style: PlotStyle = PlotStyle(width = 800, height = 600)): Unit =
    Deliver(boxPlotSvg(title, labels, style), title, saveTo)

  /** The SVG text [[boxPlot]] renders. */
  def boxPlotSvg(title: String = "", labels: Seq[String] = Nil,
                 style: PlotStyle = PlotStyle(width = 800, height = 600)): String =
    Svg.boxPlot(m, title, labels, style)

  /** Scatterplot matrix — p×p grid: histograms on the diagonal,
   *  scatter plots off-diagonal.  Equivalent to R `pairs()` / seaborn `pairplot`.
   *
   *  {{{
   *  mat.pairs()
   *  mat.pairs(labels = Seq("Freq", "Angle", "Chord", "Velo", "Thick", "Sound"))
   *  mat.pairs(saveTo = "docs/images/airfoil-pairs")
   *  }}}
   */
  def pairs(
      title: String = "Scatterplot matrix",
      labels: Seq[String] = Nil,
      bins: Int = 10,
      dotSize: Int = 3,
      color: Color = new Color(31, 119, 180),
      scatterAlpha: Int = 80,
      labelStyle: Int = Font.BOLD,
      saveTo: String = "",
      style: PlotStyle = PlotStyle(width = 1400, height = 600)
  ): Unit = Deliver(pairsSvg(title, labels, bins, dotSize, color, scatterAlpha, labelStyle, style), title, saveTo)

  /** The SVG text [[pairs]] renders. */
  def pairsSvg(
      title: String = "Scatterplot matrix",
      labels: Seq[String] = Nil,
      bins: Int = 10,
      dotSize: Int = 3,
      color: Color = new Color(31, 119, 180),
      scatterAlpha: Int = 80,
      labelStyle: Int = Font.BOLD,
      style: PlotStyle = PlotStyle(width = 1400, height = 600)
  ): String = Svg.pairs(m, title, labels, bins, dotSize, color, scatterAlpha, labelStyle, style)

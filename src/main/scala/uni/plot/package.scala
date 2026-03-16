package uni.plot

import org.knowm.xchart.*
import org.knowm.xchart.style.Styler
import org.knowm.xchart.XYSeries.XYSeriesRenderStyle
import org.knowm.xchart.BitmapEncoder.BitmapFormat
import uni.data.MatD
import scala.jdk.CollectionConverters.*
import java.awt.{Color, Font, RenderingHints}
import java.awt.image.BufferedImage

/** Display and export styling for uni.plot charts.
 *
 *  Pass to any plot method; `None` fields defer to the GGPlot2 theme default.
 *  Use [[PlotStyle.uniform]] for consistent dimensions when saving to files.
 *
 *  @param width          chart width in pixels
 *  @param height         chart height in pixels
 *  @param background     outer chart background (`None` = theme default)
 *  @param plotBackground inner plot-area background (`None` = theme default)
 *  @param foreground     axis labels and title colour (`None` = theme default)
 *  @param seriesColors   per-series colours in order (`Nil` = theme default)
 */
case class PlotStyle(
  width:          Int           = 800,
  height:         Int           = 500,
  background:     Option[Color] = None,
  plotBackground: Option[Color] = None,
  foreground:     Option[Color] = None,
  seriesColors:   Seq[Color]    = Nil,
)

object PlotStyle:
  /** Uniform 800×500 size — use for `saveTo` calls so all README/doc
   *  images share the same dimensions and aspect ratio. */
  val uniform = PlotStyle(width = 800, height = 500)

// ── internals ────────────────────────────────────────────────────────────────

private def applyStyle(chart: XYChart, style: PlotStyle): Unit =
  style.background    .foreach(chart.getStyler.setChartBackgroundColor)
  style.plotBackground.foreach(chart.getStyler.setPlotBackgroundColor)
  style.foreground    .foreach(chart.getStyler.setChartFontColor)
  if style.seriesColors.nonEmpty then
    chart.getStyler.setSeriesColors(style.seriesColors.toArray)

private def uniIcon(size: Int = 32): BufferedImage =
  val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
  val g   = img.createGraphics()
  g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
  g.setColor(new Color(45, 45, 48))
  g.fillRoundRect(0, 0, size, size, 8, 8)
  g.setColor(new Color(220, 220, 220))
  g.setFont(Font("SansSerif", Font.BOLD, (size * 0.44).toInt))
  val fm = g.getFontMetrics
  val x  = (size - fm.stringWidth("uni")) / 2
  val y  = (size + fm.getAscent - fm.getDescent) / 2
  g.drawString("uni", x, y)
  g.dispose()
  img

private def display(chart: XYChart): Unit =
  val frame = new SwingWrapper(chart).displayChart()
  frame.setIconImage(uniIcon())
  frame.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE)

// ── extension methods ─────────────────────────────────────────────────────────

extension (m: MatD)

  /** Line plot — one series per column, x-axis = row indices. */
  def plot(title: String = "", labels: Seq[String] = Nil, saveTo: String = "",
           style: PlotStyle = PlotStyle(width = 900, height = 600)): Unit =
    val chart = XYChartBuilder().theme(Styler.ChartTheme.GGPlot2)
      .width(style.width).height(style.height).title(title).build()
    applyStyle(chart, style)
    chart.getStyler.setDefaultSeriesRenderStyle(XYSeriesRenderStyle.Line)
    chart.getStyler.setMarkerSize(0)
    val xs = Array.tabulate(m.rows)(_.toDouble)
    for col <- 0 until m.cols do
      val label = if labels.isDefinedAt(col) then labels(col) else s"col$col"
      chart.addSeries(label, xs, m(::, col).flatten)
    if saveTo.nonEmpty then BitmapEncoder.saveBitmap(chart, saveTo, BitmapFormat.PNG)
    else display(chart)

  /** Scatter plot of two columns (default: col 0 vs col 1). */
  def scatter(xCol: Int = 0, yCol: Int = 1, title: String = "", saveTo: String = "",
              style: PlotStyle = PlotStyle(width = 700, height = 700)): Unit =
    val chart = XYChartBuilder().theme(Styler.ChartTheme.GGPlot2)
      .width(style.width).height(style.height).title(title).build()
    applyStyle(chart, style)
    chart.getStyler.setDefaultSeriesRenderStyle(XYSeriesRenderStyle.Scatter)
    chart.addSeries("data", m(::, xCol).flatten, m(::, yCol).flatten)
    if saveTo.nonEmpty then BitmapEncoder.saveBitmap(chart, saveTo, BitmapFormat.PNG)
    else display(chart)

  /** Histogram of all values in the matrix. */
  def hist(bins: Int = 20, title: String = "", saveTo: String = "",
           style: PlotStyle = PlotStyle(width = 800, height = 500)): Unit =
    val chart = XYChartBuilder().theme(Styler.ChartTheme.GGPlot2)
      .width(style.width).height(style.height).title(title).build()
    applyStyle(chart, style)
    chart.getStyler.setDefaultSeriesRenderStyle(XYSeriesRenderStyle.Area)
    chart.getStyler.setPlotGridLinesVisible(false)
    val jdata: java.util.List[java.lang.Double] =
      m.flatten.map(java.lang.Double.valueOf).toList.asJava
    val h = Histogram(jdata, bins)
    chart.addSeries("frequency", h.getxAxisData, h.getyAxisData)
    if saveTo.nonEmpty then BitmapEncoder.saveBitmap(chart, saveTo, BitmapFormat.PNG)
    else display(chart)

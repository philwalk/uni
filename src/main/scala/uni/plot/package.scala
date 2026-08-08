package uni.plot

import org.knowm.xchart.*
import org.knowm.xchart.style.Styler
import org.knowm.xchart.XYSeries.XYSeriesRenderStyle
import org.knowm.xchart.BitmapEncoder.BitmapFormat
import org.knowm.xchart.CategorySeries.CategorySeriesRenderStyle
import uni.data.MatD
import scala.jdk.CollectionConverters.*
import java.awt.{Color, Font, RenderingHints}
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

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
 *  @param xLabel         x-axis label (`""` = no label)
 *  @param yLabel         y-axis label (`""` = no label)
 *  @param xLog           logarithmic x-axis (XY charts only)
 *  @param yLog           logarithmic y-axis (XY charts only)
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

// ── internals ────────────────────────────────────────────────────────────────

private def applyStyle(chart: XYChart, style: PlotStyle): Unit =
  style.background    .foreach(chart.getStyler.setChartBackgroundColor)
  style.plotBackground.foreach(chart.getStyler.setPlotBackgroundColor)
  style.foreground    .foreach(chart.getStyler.setChartFontColor)
  if style.seriesColors.nonEmpty then chart.getStyler.setSeriesColors(style.seriesColors.toArray)
  if style.xLabel.nonEmpty then chart.setXAxisTitle(style.xLabel)
  if style.yLabel.nonEmpty then chart.setYAxisTitle(style.yLabel)
  if style.xLog then chart.getStyler.setXAxisLogarithmic(true)
  if style.yLog then chart.getStyler.setYAxisLogarithmic(true)

private def applyStyle(chart: CategoryChart, style: PlotStyle): Unit =
  style.background    .foreach(chart.getStyler.setChartBackgroundColor)
  style.plotBackground.foreach(chart.getStyler.setPlotBackgroundColor)
  style.foreground    .foreach(chart.getStyler.setChartFontColor)
  if style.seriesColors.nonEmpty then chart.getStyler.setSeriesColors(style.seriesColors.toArray)
  if style.xLabel.nonEmpty then chart.setXAxisTitle(style.xLabel)
  if style.yLabel.nonEmpty then chart.setYAxisTitle(style.yLabel)

private def applyStyle(chart: BoxChart, style: PlotStyle): Unit =
  style.background    .foreach(chart.getStyler.setChartBackgroundColor)
  style.plotBackground.foreach(chart.getStyler.setPlotBackgroundColor)
  style.foreground    .foreach(chart.getStyler.setChartFontColor)
  if style.seriesColors.nonEmpty then chart.getStyler.setSeriesColors(style.seriesColors.toArray)
  if style.xLabel.nonEmpty then chart.setXAxisTitle(style.xLabel)
  if style.yLabel.nonEmpty then chart.setYAxisTitle(style.yLabel)

private def applyStyle(chart: HeatMapChart, style: PlotStyle): Unit =
  style.background    .foreach(chart.getStyler.setChartBackgroundColor)
  style.plotBackground.foreach(chart.getStyler.setPlotBackgroundColor)
  style.foreground    .foreach(chart.getStyler.setChartFontColor)
  if style.seriesColors.nonEmpty then chart.getStyler.setRangeColors(style.seriesColors.toArray)
  if style.xLabel.nonEmpty then chart.setXAxisTitle(style.xLabel)
  if style.yLabel.nonEmpty then chart.setYAxisTitle(style.yLabel)

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

private def withIcon(frame: javax.swing.JFrame): Unit =
  frame.setIconImage(uniIcon())
  frame.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE)

private def niceTicks(mn: Double, mx: Double, n: Int = 4): Array[Double] =
  if mx <= mn then return Array(mn)
  val step0 = (mx - mn) / n
  val mag   = math.pow(10, math.floor(math.log10(step0)))
  val step  = Array(1.0, 2.0, 5.0, 10.0).map(_ * mag).minBy(s => math.abs(s - step0))
  val start = math.ceil(mn / step) * step
  if start > mx then Array.empty
  else Array.iterate(start, n + 3)(_ + step).takeWhile(_ <= mx + step * 0.01)

private def fmtTick(v: Double): String =
  try
    val s = java.math.BigDecimal(v.toString)
      .setScale(3, java.math.RoundingMode.HALF_UP)
      .stripTrailingZeros.toPlainString
    if s.length > 8 then f"$v%.2e" else s
  catch case _: Exception => v.toString

private def renderPairsImage(m: MatD, p: Int, cellW: Int, cellH: Int,
                              bins: Int, dotSize: Int, color: Color, scatterAlpha: Int,
                              labelStyle: Int,
                              lbl: Int => String,
                              totalW: Int, totalH: Int): BufferedImage =
  val img  = BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB)
  val g2   = img.createGraphics()
  g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
  g2.setColor(new Color(235, 235, 235))
  g2.fillRect(0, 0, totalW, totalH)

  val colData     = Array.tabulate(p)(c => m(::, c).flatten)
  val colMin      = colData.map(_.min)
  val colMax      = colData.map(_.max)
  val solid       = new Color(color.getRed, color.getGreen, color.getBlue)
  val transparent = new Color(color.getRed, color.getGreen, color.getBlue, scatterAlpha)

  val padL       = 44; val padR = 4; val padT = 18; val padB = 32
  val tickLen    = 3
  val gridColor  = new Color(220, 220, 220)
  val axisColor  = new Color(160, 160, 160)
  val tickFontSz = math.max(7, math.min(10, cellH / 14))
  val lblFontSz  = math.max(8, math.min(13, cellW / 14))
  val tickFont   = Font("SansSerif", Font.PLAIN,  tickFontSz)
  val lblFont    = Font("SansSerif", labelStyle,  lblFontSz)
  val titleFont  = Font("SansSerif", labelStyle,  tickFontSz)

  for i <- 0 until p; j <- 0 until p do
    val x0 = j * cellW; val y0 = i * cellH
    g2.setClip(x0, y0, cellW, cellH)
    g2.setColor(Color.WHITE)
    g2.fillRect(x0 + 1, y0 + 1, cellW - 2, cellH - 2)

    val px0 = x0 + padL; val py0 = y0 + padT
    val pw  = cellW - padL - padR
    val ph  = cellH - padT - padB

    if i == j then
      val data     = colData(i)
      val mn       = colMin(i); val mx = colMax(i)
      val binSize  = if mx == mn then 1.0 else (mx - mn) / bins
      val counts   = Array.fill(bins)(0)
      for v <- data do counts(math.min(((v - mn) / binSize).toInt, bins - 1)) += 1
      val maxCount = counts.max.toDouble
      val bw       = math.max(1, pw / bins - 1)
      // y gridlines
      g2.setColor(gridColor)
      for t <- niceTicks(0, maxCount, 4) do
        val ty = py0 + ph - (t / maxCount * ph).toInt
        if ty >= py0 && ty <= py0 + ph then g2.drawLine(px0, ty, px0 + pw, ty)
      // bars
      g2.setColor(solid)
      for b <- 0 until bins do
        val barH = ((counts(b) / maxCount) * ph).toInt
        g2.fillRect(px0 + b * pw / bins, py0 + ph - barH, bw, barH)
      // axes
      g2.setColor(axisColor)
      g2.drawLine(px0, py0, px0, py0 + ph)
      g2.drawLine(px0, py0 + ph, px0 + pw, py0 + ph)
      // x-axis ticks + labels
      g2.setFont(tickFont)
      val tfm = g2.getFontMetrics
      for t <- niceTicks(mn, mx, 4) do
        val tx = px0 + ((t - mn) / (mx - mn) * pw).toInt
        if tx >= px0 && tx <= px0 + pw then
          g2.setColor(axisColor)
          g2.drawLine(tx, py0 + ph, tx, py0 + ph + tickLen)
          val s = fmtTick(t)
          g2.setColor(Color.DARK_GRAY)
          g2.drawString(s, tx - tfm.stringWidth(s) / 2, py0 + ph + tickLen + tfm.getAscent)
      // variable label
      g2.setFont(lblFont)
      val lfm = g2.getFontMetrics
      g2.setColor(Color.DARK_GRAY)
      g2.drawString(lbl(i), x0 + (cellW - lfm.stringWidth(lbl(i))) / 2, y0 + padT - 3)
    else
      val xs     = colData(j); val ys   = colData(i)
      val xmn    = colMin(j);  val xmx  = colMax(j)
      val ymn    = colMin(i);  val ymx  = colMax(i)
      val xrng   = if xmx == xmn then 1.0 else xmx - xmn
      val yrng   = if ymx == ymn then 1.0 else ymx - ymn
      val xTicks = niceTicks(xmn, xmx, 4)
      val yTicks = niceTicks(ymn, ymx, 4)
      // gridlines
      g2.setColor(gridColor)
      for t <- xTicks do
        val tx = px0 + ((t - xmn) / xrng * pw).toInt
        if tx >= px0 && tx <= px0 + pw then g2.drawLine(tx, py0, tx, py0 + ph)
      for t <- yTicks do
        val ty = py0 + ph - ((t - ymn) / yrng * ph).toInt
        if ty >= py0 && ty <= py0 + ph then g2.drawLine(px0, ty, px0 + pw, ty)
      // dots
      g2.setColor(transparent)
      for k <- xs.indices do
        val px = px0 + ((xs(k) - xmn) / xrng * pw).toInt
        val py = py0 + ph - ((ys(k) - ymn) / yrng * ph).toInt
        g2.fillRect(px, py, dotSize, dotSize)
      // axes
      g2.setColor(axisColor)
      g2.drawLine(px0, py0, px0, py0 + ph)
      g2.drawLine(px0, py0 + ph, px0 + pw, py0 + ph)
      // x-axis ticks + labels
      g2.setFont(tickFont)
      val tfm = g2.getFontMetrics
      for t <- xTicks do
        val tx = px0 + ((t - xmn) / xrng * pw).toInt
        if tx >= px0 && tx <= px0 + pw then
          g2.setColor(axisColor)
          g2.drawLine(tx, py0 + ph, tx, py0 + ph + tickLen)
          val s = fmtTick(t)
          g2.setColor(Color.DARK_GRAY)
          g2.drawString(s, tx - tfm.stringWidth(s) / 2, py0 + ph + tickLen + tfm.getAscent)
      // y-axis ticks + labels
      for t <- yTicks do
        val ty = py0 + ph - ((t - ymn) / yrng * ph).toInt
        if ty >= py0 && ty <= py0 + ph then
          g2.setColor(axisColor)
          g2.drawLine(px0 - tickLen, ty, px0, ty)
          val s = fmtTick(t)
          g2.setColor(Color.DARK_GRAY)
          val sw = tfm.stringWidth(s)
          g2.drawString(s, px0 - tickLen - sw - 1, ty + tfm.getAscent / 2 - 1)
      // axis variable name labels
      g2.setFont(titleFont)
      val ntfm   = g2.getFontMetrics
      g2.setColor(Color.DARK_GRAY)
      val xTitle = lbl(j)
      g2.drawString(xTitle, px0 + (pw - ntfm.stringWidth(xTitle)) / 2, y0 + cellH - 3)
      val origT  = g2.getTransform
      g2.translate(x0 + 9.0, (py0 + ph / 2).toDouble)
      g2.rotate(-math.Pi / 2)
      val yTitle = lbl(i)
      g2.drawString(yTitle, -ntfm.stringWidth(yTitle) / 2, ntfm.getAscent / 2)
      g2.setTransform(origT)

  g2.setClip(null)
  g2.dispose()
  img

// ── extension methods ─────────────────────────────────────────────────────────

extension (m: MatD)

  /** Line plot — one series per column, x-axis = row indices. */
  def plot(
      title: String = "",
      labels: Seq[String] = Nil,
      saveTo: String = "",
      style: PlotStyle = PlotStyle(width = 900, height = 600)
  ): Unit =
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
    else withIcon(new SwingWrapper(chart).displayChart())

  /** Scatter plot of two columns (default: col 0 vs col 1).
   *  If `groupCol >= 0`, points are split into per-group series, one series per
   *  distinct value in that column (useful for colour-by-class plots). */
  def scatter(xCol: Int = 0, yCol: Int = 1, title: String = "", saveTo: String = "",
              groupCol: Int = -1,
              style: PlotStyle = PlotStyle(width = 700, height = 700)): Unit =
    val chart = XYChartBuilder().theme(Styler.ChartTheme.GGPlot2)
      .width(style.width).height(style.height).title(title).build()
    applyStyle(chart, style)
    chart.getStyler.setDefaultSeriesRenderStyle(XYSeriesRenderStyle.Scatter)
    if groupCol < 0 then
      chart.addSeries("data", m(::, xCol).flatten, m(::, yCol).flatten)
    else
      val groups = m(::, groupCol).flatten.distinct.sorted
      for g <- groups do
        val rows = (0 until m.rows).filter(r => m(r, groupCol) == g)
        chart.addSeries(g.toString,
          rows.map(r => m(r, xCol)).toArray,
          rows.map(r => m(r, yCol)).toArray)
    if saveTo.nonEmpty then BitmapEncoder.saveBitmap(chart, saveTo, BitmapFormat.PNG)
    else withIcon(new SwingWrapper(chart).displayChart())

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
    else withIcon(new SwingWrapper(chart).displayChart())

  /** Bar chart — one bar per row in `col`.
   *  X-axis labels come from `labelCol` if given, otherwise row indices are used. */
  def bar(col: Int = 0, labelCol: Int = -1, title: String = "", saveTo: String = "",
          style: PlotStyle = PlotStyle(width = 800, height = 500)): Unit =
    val chart = CategoryChartBuilder().theme(Styler.ChartTheme.GGPlot2)
      .width(style.width).height(style.height).title(title).build()
    applyStyle(chart, style)
    chart.getStyler.setDefaultSeriesRenderStyle(CategorySeriesRenderStyle.Bar)
    chart.getStyler.setLegendVisible(false)
    val xLabels: java.util.List[String] =
      if labelCol >= 0 then (0 until m.rows).map(r => m(r, labelCol).toString).asJava
      else (0 until m.rows).map(_.toString).asJava
    val yData: java.util.List[java.lang.Double] =
      (0 until m.rows).map(r => java.lang.Double.valueOf(m(r, col))).asJava
    chart.addSeries("data", xLabels, yData)
    if saveTo.nonEmpty then BitmapEncoder.saveBitmap(chart, saveTo, BitmapFormat.PNG)
    else withIcon(new SwingWrapper(chart).displayChart())

  /** Heatmap — renders the matrix as a colour grid.
   *  Useful for correlation matrices and confusion matrices.
   *  `rowLabels` / `colLabels` replace numeric axis ticks when provided. */
  def heatmap(title: String = "", rowLabels: Seq[String] = Nil, colLabels: Seq[String] = Nil,
              saveTo: String = "",
              style: PlotStyle = PlotStyle(width = 800, height = 700)): Unit =
    val chart = HeatMapChartBuilder()
      .width(style.width).height(style.height).title(title).build()
    applyStyle(chart, style)
    // XChart heatData format: each Number[3] is a triplet (xIndex, yIndex, value)
    val xData: java.util.List[String] =
      (0 until m.cols).map(c => if colLabels.isDefinedAt(c) then colLabels(c) else c.toString).asJava
    val yData: java.util.List[String] =
      (0 until m.rows).map(r => if rowLabels.isDefinedAt(r) then rowLabels(r) else r.toString).asJava
    val heatData: java.util.List[Array[Number]] =
      (for c <- 0 until m.cols; r <- 0 until m.rows yield
        Array[Number](Integer.valueOf(c), Integer.valueOf(r), java.lang.Double.valueOf(m(r, c)))
      ).asJava
    chart.addSeries("data", xData, yData, heatData)
    if saveTo.nonEmpty then BitmapEncoder.saveBitmap(chart, saveTo, BitmapFormat.PNG)
    else withIcon(new SwingWrapper(chart).displayChart())

  /** Box plot — one box per column, showing median, quartiles, and outliers.
   *  `labels` names the boxes; defaults to `"col0"`, `"col1"`, … */
  def boxPlot(title: String = "", labels: Seq[String] = Nil, saveTo: String = "",
              style: PlotStyle = PlotStyle(width = 800, height = 600)): Unit =
    val chart = BoxChartBuilder().theme(Styler.ChartTheme.GGPlot2)
      .width(style.width).height(style.height).title(title).build()
    applyStyle(chart, style)
    for col <- 0 until m.cols do
      val label = if labels.isDefinedAt(col) then labels(col) else s"col$col"
      val jData: java.util.List[java.lang.Double] =
        m(::, col).flatten.map(java.lang.Double.valueOf).toList.asJava
      chart.addSeries(label, jData)
    if saveTo.nonEmpty then BitmapEncoder.saveBitmap(chart, saveTo, BitmapFormat.PNG)
    else withIcon(new SwingWrapper(chart).displayChart())

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
  ): Unit =
    val p     = m.cols
    val cellW = style.width  / p
    val cellH = style.height / p
    def lbl(k: Int) = if labels.isDefinedAt(k) then labels(k) else s"col$k"
    if saveTo.nonEmpty then
      val img = renderPairsImage(m, p, cellW, cellH, bins, dotSize, color, scatterAlpha, labelStyle, lbl, style.width, style.height)
      ImageIO.write(img, "PNG", new java.io.File(s"$saveTo.png"))
    else
      val panel = new javax.swing.JPanel {
        private var lastW = 0; private var lastH = 0
        private var cached: BufferedImage = null.asInstanceOf[BufferedImage]
        override def paintComponent(g: java.awt.Graphics): Unit =
          super.paintComponent(g)
          val w = getWidth; val h = getHeight
          if w != lastW || h != lastH then
            lastW = w; lastH = h
            cached = renderPairsImage(m, p, w / p, h / p, bins, dotSize, color, scatterAlpha, labelStyle, lbl, w, h)
          g.drawImage(cached, 0, 0, null)
      }
      panel.setPreferredSize(java.awt.Dimension(style.width, style.height))
      val frame = javax.swing.JFrame(title)
      frame.add(panel)
      frame.setIconImage(uniIcon())
      frame.setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE)
      frame.pack()
      frame.setVisible(true)

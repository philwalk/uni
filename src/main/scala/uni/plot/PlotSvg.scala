package uni.plot

import uni.data.MatD
import java.awt.Color

/**
 * The pure core of `uni.plot`: a matrix and its options in, SVG text out. No AWT, no
 * display, no file — everything here is a function of its arguments, and the Rust port
 * (`rust/src/uplot/`) implements the same functions to the byte, which is what the `pl.`
 * rows of `test-data/mat-parity/scala-reference.txt` pin.
 *
 * Determinism rules — the port depends on every one of them:
 *   - coordinates go through [[Svg.num]]: `floor(x·100 + 0.5)` then decimal text, never a
 *     locale- or language-specific float formatter;
 *   - tick labels go through [[Svg.fmtTick]], integer arithmetic on the scaled value, with
 *     a hand-rolled scientific form (Java's `%e` and Rust's `{:e}` disagree on the exponent);
 *   - tick spacing ([[Svg.niceTicks]]) finds the decade by repeated ×10 / ÷10, not `log10`
 *     — the JVM and libm may differ in the last ulp there;
 *   - no font metrics: text is anchored (`start`/`middle`/`end`), widths are estimated as
 *     7 px per character where a box must be sized;
 *   - series colours, layout margins and font sizes are constants below;
 *   - non-finite values are dropped before any range is taken;
 *   - the logarithm behind a log axis is [[Svg.log10]], this file's own (bit-exact
 *     exponent split, an odd-power series for the mantissa, literal constants), because
 *     `Math.log10` and libm's `log10` may differ in the last ulp — so no libm call is
 *     made anywhere in the renderer.
 */
private[plot] object Svg:

  // ── theme constants ────────────────────────────────────────────────────────
  val Palette: IndexedSeq[String] = IndexedSeq(
    "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd",
    "#8c564b", "#e377c2", "#7f7f7f", "#bcbd22", "#17becf")
  val HeatStops: IndexedSeq[String] = IndexedSeq("#2166ac", "#f7f7f7", "#b2182b")
  val ChartBg  = "#ffffff"
  val PlotBg   = "#ebebeb"
  val GridLine = "#ffffff"
  val TextFill = "#333333"
  val AxisLine = "#999999"
  val TitleSize = 16
  val LabelSize = 13
  val TickSize  = 11

  // ── text primitives ────────────────────────────────────────────────────────

  /** A coordinate: rounded half-up to 2 decimals, trailing zeros dropped, `-0` never
   *  produced. `floor(x·100+0.5)` is IEEE-identical in both languages. */
  def num(x: Double): String =
    if !java.lang.Double.isFinite(x) then "0"
    else
      val v   = math.floor(x * 100.0 + 0.5)
      val neg = v < 0
      val a   = math.abs(v).toLong
      val ip  = a / 100
      val fp  = a % 100
      val s =
        if fp == 0 then ip.toString
        else if fp % 10 == 0 then s"$ip.${fp / 10}"
        else if fp < 10 then s"$ip.0$fp"
        else s"$ip.$fp"
      if neg && a != 0 then "-" + s else s

  /** Escape the five XML specials for attribute and text content. */
  def esc(s: String): String =
    val sb = StringBuilder()
    for c <- s do c match
      case '&'  => sb ++= "&amp;"
      case '<'  => sb ++= "&lt;"
      case '>'  => sb ++= "&gt;"
      case '"'  => sb ++= "&quot;"
      case '\'' => sb ++= "&apos;"
      case _    => sb += c
    sb.toString

  /** `#rrggbb` — alpha is carried separately as an opacity attribute. */
  def hex(c: Color): String = f"#${c.getRed}%02x${c.getGreen}%02x${c.getBlue}%02x"

  /** ` fill-opacity="0.31"` when the colour is translucent, else nothing. */
  def opacityAttr(alpha: Int, attr: String = "fill-opacity"): String =
    if alpha >= 255 then "" else s""" $attr="${num(alpha / 255.0)}""""

  /** 10^k by repeated multiplication (exact for the range that matters, and identical
   *  in both languages, unlike `pow`). */
  def pow10(k: Int): Double =
    var v = 1.0
    if k >= 0 then
      var i = 0
      while i < k do { v *= 10.0; i += 1 }
    else
      var i = 0
      while i < -k do { v /= 10.0; i += 1 }
    v

  // ln 2 split Cody–Waite style (fdlibm's pair): e·Ln2Hi is exact for |e| ≤ 1074, so the
  // exponent's contribution costs no rounding; ln 10 is the correctly rounded value. Bit
  // patterns, so the Scala and Rust constants cannot drift.
  private val Ln2Hi = java.lang.Double.longBitsToDouble(0x3fe62e42fee00000L)   // 6.93147180369123816490e-01
  private val Ln2Lo = java.lang.Double.longBitsToDouble(0x3dea39ef35793c76L)   // 1.90821492927058770002e-10
  private val Ln10  = java.lang.Double.longBitsToDouble(0x40026bb1bbb55516L)   // 2.302585092994046

  /** log10 with the same operations in both languages: `x = m·2^e` split from the bits
   *  (exact), `m` folded into [√½, √2), `ln m = 2·(s + s³/3 + … + s²¹/21)` with
   *  `s = (m−1)/(m+1)`, then `((e·ln2ʰⁱ + ln m) + e·ln2ˡᵒ)/ln10`; a result within an ulp of an integer
   *  `k` with `pow10(k) == x` snaps to `k`, so decades label exactly. NaN for x ≤ 0 or
   *  NaN, +∞ for +∞. Absolute error is a few 1e-17 — as accurate as libm, and identical. */
  def log10(x: Double): Double =
    if java.lang.Double.isNaN(x) || x <= 0.0 then Double.NaN
    else if x == Double.PositiveInfinity then Double.PositiveInfinity
    else
      var v = x; var e = 0
      if v < 2.2250738585072014e-308 then { v *= 18014398509481984.0; e -= 54 }   // subnormal: ×2^54
      val bits = java.lang.Double.doubleToRawLongBits(v)
      e += ((bits >>> 52) & 0x7ffL).toInt - 1023
      var m = java.lang.Double.longBitsToDouble((bits & 0x000fffffffffffffL) | 0x3ff0000000000000L)  // [1,2)
      if m > 1.4142135623730951 then { m *= 0.5; e += 1 }
      val s  = (m - 1.0) / (m + 1.0)
      val s2 = s * s
      var term = s; var sum = s; var k = 3
      while k <= 21 do
        term *= s2
        sum += term / k
        k += 2
      val r = ((e * Ln2Hi + 2.0 * sum) + e * Ln2Lo) / Ln10
      val kk = math.floor(r + 0.5)
      if math.abs(r - kk) < 1e-12 && kk >= -308.0 && kk <= 308.0 && pow10(kk.toInt) == x then kk else r

  /** Tick label: up to 3 decimals, trailing zeros stripped, for 1e-3 ≤ |v| < 1e7;
   *  outside that a two-decimal mantissa with a plain exponent (`1.50e6`, `2.5e-4`). */
  def fmtTick(v: Double): String =
    if !java.lang.Double.isFinite(v) then "0"
    else if v == 0.0 then "0"
    else
      val a = math.abs(v)
      val sign = if v < 0 then "-" else ""
      if a >= 1e-3 && a < 1e7 then
        val n  = math.floor(a * 1000.0 + 0.5).toLong
        val ip = n / 1000
        val fp = n % 1000
        if fp == 0 then sign + ip.toString
        else
          var digits = f"$fp%03d"
          while digits.endsWith("0") do digits = digits.dropRight(1)
          s"$sign$ip.$digits"
      else
        var m = a; var e = 0
        while m >= 10.0 do { m /= 10.0; e += 1 }
        while m < 1.0  do { m *= 10.0; e -= 1 }
        var mant = math.floor(m * 100.0 + 0.5).toLong   // 100..1000
        if mant >= 1000 then { mant = 100; e += 1 }
        val ip = mant / 100; val fp = mant % 100
        val fps = if fp < 10 then s"0$fp" else fp.toString
        s"$sign$ip.${fps}e$e"

  /** Tick positions: a 1/2/5 step nearest to `(mx-mn)/n`, from the first multiple at or
   *  above `mn` to the last at or below `mx`. Empty when no multiple falls inside. */
  def niceTicks(mn: Double, mx: Double, n: Int): Array[Double] =
    if !(mx > mn) then Array(mn)
    else
      val step0 = (mx - mn) / n
      var mag = 1.0
      while mag * 10.0 <= step0 do mag *= 10.0
      while mag > step0 do mag /= 10.0
      val cands = Array(1.0 * mag, 2.0 * mag, 5.0 * mag, 10.0 * mag)
      var step = cands(0); var best = math.abs(cands(0) - step0)
      var i = 1
      while i < cands.length do
        val d = math.abs(cands(i) - step0)
        if d < best then { best = d; step = cands(i) }
        i += 1
      val start = math.ceil(mn / step) * step
      if start > mx then Array.empty[Double]
      else
        val out = scala.collection.mutable.ArrayBuffer.empty[Double]
        var t = start; var k = 0
        val limit = mx + step * 0.01
        while t <= limit && k < n + 3 do
          out += t; k += 1
          t = start + k * step       // not accumulated: identical rounding on both sides
        out.toArray

  // ── scales ────────────────────────────────────────────────────────────────

  final case class Domain(lo: Double, hi: Double)

  /** Data range padded 5 % each side; a degenerate range widens by 0.5; no data → [0,1]. */
  def domain(values: Array[Double]): Domain =
    var lo = Double.PositiveInfinity; var hi = Double.NegativeInfinity
    var i = 0
    while i < values.length do
      val v = values(i)
      if java.lang.Double.isFinite(v) then
        if v < lo then lo = v
        if v > hi then hi = v
      i += 1
    if lo > hi then Domain(0.0, 1.0)
    else if lo == hi then Domain(lo - 0.5, hi + 0.5)
    else
      val pad = (hi - lo) * 0.05
      Domain(lo - pad, hi + pad)

  /** Like [[domain]] but for a logarithmic axis: the domain of `log10` over the positive
   *  finite values (non-positive values are dropped by the callers too). */
  def logDomain(values: Array[Double]): Domain =
    domain(values.filter(v => java.lang.Double.isFinite(v) && v > 0.0).map(log10))

  /** A linear or log axis mapped onto `[p0, p0+len]` (y axes pass `flip = true`). */
  final case class Axis(dom: Domain, log: Boolean, p0: Double, len: Double, flip: Boolean):
    private val span = dom.hi - dom.lo
    def pos(v: Double): Double =
      val t0 = if log then log10(v) else v
      val t  = (t0 - dom.lo) / span
      if flip then p0 + len - t * len else p0 + t * len
    /** Tick values in DATA units (powers of ten on a log axis). */
    def ticks: Array[Double] =
      if log then
        val k0 = math.ceil(dom.lo).toInt; val k1 = math.floor(dom.hi).toInt
        (k0 to k1).map(pow10).toArray
      else niceTicks(dom.lo, dom.hi, 5)
    def contains(v: Double): Boolean =
      val t = if log then log10(v) else v
      t >= dom.lo && t <= dom.hi

  // ── layout ────────────────────────────────────────────────────────────────

  final case class Frame(w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int):
    val px0: Int = left
    val py0: Int = top
    val pw: Int  = math.max(1, w - left - right)
    val ph: Int  = math.max(1, h - top - bottom)

  def frame(style: PlotStyle, title: String, extraRight: Int = 0): Frame =
    Frame(style.width, style.height,
      left   = 64,
      top    = if title.nonEmpty then 44 else 20,
      right  = 20 + extraRight,
      bottom = if style.xLabel.nonEmpty then 58 else 42)

  final case class Theme(chartBg: String, plotBg: String, text: String, palette: IndexedSeq[String]):
    def color(i: Int): String = palette(i % palette.length)

  def theme(style: PlotStyle): Theme =
    Theme(
      chartBg = style.background.map(hex).getOrElse(ChartBg),
      plotBg  = style.plotBackground.map(hex).getOrElse(PlotBg),
      text    = style.foreground.map(hex).getOrElse(TextFill),
      palette = if style.seriesColors.nonEmpty then style.seriesColors.map(hex).toIndexedSeq else Palette)

  // ── SVG building blocks ───────────────────────────────────────────────────

  final class Out:
    private val sb = StringBuilder()
    def line(s: String): Out = { sb ++= s; sb += '\n'; this }
    override def toString: String = sb.toString

  def open(out: Out, w: Int, h: Int, chartBg: String): Unit =
    out.line(s"""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h" font-family="sans-serif">""")
    out.line(s"""<rect x="0" y="0" width="$w" height="$h" fill="$chartBg"/>""")

  def close(out: Out): Unit = out.line("</svg>")

  def text(out: Out, x: Double, y: Double, s: String, size: Int, anchor: String, fill: String,
           extra: String = ""): Unit =
    out.line(s"""<text x="${num(x)}" y="${num(y)}" font-size="$size" text-anchor="$anchor" fill="$fill"$extra>${esc(s)}</text>""")

  def title(out: Out, f: Frame, t: String, th: Theme): Unit =
    if t.nonEmpty then text(out, f.w / 2.0, 28.0, t, TitleSize, "middle", th.text)

  def axisLabels(out: Out, f: Frame, style: PlotStyle, th: Theme): Unit =
    if style.xLabel.nonEmpty then
      text(out, f.px0 + f.pw / 2.0, f.h - 10.0, style.xLabel, LabelSize, "middle", th.text)
    if style.yLabel.nonEmpty then
      val cx = 16.0; val cy = f.py0 + f.ph / 2.0
      text(out, cx, cy, style.yLabel, LabelSize, "middle", th.text,
        extra = s""" transform="rotate(-90 ${num(cx)} ${num(cy)})"""")

  def plotArea(out: Out, f: Frame, th: Theme): Unit =
    out.line(s"""<rect x="${f.px0}" y="${f.py0}" width="${f.pw}" height="${f.ph}" fill="${th.plotBg}"/>""")

  /** Gridlines, tick marks and tick labels for a numeric x axis along the plot bottom. */
  def xAxis(out: Out, f: Frame, ax: Axis, th: Theme): Unit =
    for t <- ax.ticks if ax.contains(t) do
      val x = num(ax.pos(t))
      out.line(s"""<line x1="$x" y1="${f.py0}" x2="$x" y2="${f.py0 + f.ph}" stroke="$GridLine" stroke-width="1"/>""")
      out.line(s"""<line x1="$x" y1="${f.py0 + f.ph}" x2="$x" y2="${f.py0 + f.ph + 4}" stroke="$AxisLine" stroke-width="1"/>""")
      text(out, ax.pos(t), f.py0 + f.ph + 16.0, fmtTick(t), TickSize, "middle", th.text)

  /** Gridlines, tick marks and tick labels for a numeric y axis along the plot left. */
  def yAxis(out: Out, f: Frame, ax: Axis, th: Theme): Unit =
    for t <- ax.ticks if ax.contains(t) do
      val y = num(ax.pos(t))
      out.line(s"""<line x1="${f.px0}" y1="$y" x2="${f.px0 + f.pw}" y2="$y" stroke="$GridLine" stroke-width="1"/>""")
      out.line(s"""<line x1="${f.px0 - 4}" y1="$y" x2="${f.px0}" y2="$y" stroke="$AxisLine" stroke-width="1"/>""")
      text(out, f.px0 - 7.0, ax.pos(t) + 4.0, fmtTick(t), TickSize, "end", th.text)

  final case class LegendEntry(label: String, color: String, kind: String)  // "line" | "dot" | "rect"

  /** Legend box in the plot's top-right corner; width estimated at 7 px per character. */
  def legend(out: Out, f: Frame, entries: Seq[LegendEntry], th: Theme): Unit =
    if entries.nonEmpty then
      val maxLen = entries.map(_.label.length).max
      val bw = 30 + maxLen * 7
      val bh = entries.length * 18 + 8
      val bx = f.px0 + f.pw - 8 - bw
      val by = f.py0 + 8
      out.line(s"""<rect x="$bx" y="$by" width="$bw" height="$bh" fill="#ffffff" fill-opacity="0.85" stroke="#cccccc"/>""")
      for (e, i) <- entries.zipWithIndex do
        val cy = by + 4 + i * 18 + 9
        e.kind match
          case "line" => out.line(s"""<line x1="${bx + 6}" y1="$cy" x2="${bx + 20}" y2="$cy" stroke="${e.color}" stroke-width="2"/>""")
          case "dot"  => out.line(s"""<circle cx="${bx + 13}" cy="$cy" r="3.5" fill="${e.color}"/>""")
          case _      => out.line(s"""<rect x="${bx + 6}" y="${cy - 6}" width="14" height="12" fill="${e.color}"/>""")
        text(out, bx + 26.0, cy + 4.0, e.label, TickSize, "start", th.text)

  def column(m: MatD, j: Int): Array[Double] = Array.tabulate(m.rows)(i => m(i, j))

  def label(labels: Seq[String], k: Int): String = if labels.isDefinedAt(k) then labels(k) else s"col$k"

  // ── charts ────────────────────────────────────────────────────────────────

  /** Line plot — one polyline per column, x = row index. */
  def plot(m: MatD, title: String, labels: Seq[String], style: PlotStyle): String =
    val th  = theme(style)
    val f   = frame(style, title)
    val out = Out()
    open(out, f.w, f.h, th.chartBg)
    Svg.title(out, f, title, th)
    plotArea(out, f, th)
    val xs   = Array.tabulate(m.rows)(_.toDouble)
    val xdom = if style.xLog then logDomain(xs) else domain(xs)
    val all  = Array.tabulate(m.rows * m.cols)(k => m(k / m.cols, k % m.cols))
    val ydom = if style.yLog then logDomain(all) else domain(all)
    val ax = Axis(xdom, style.xLog, f.px0, f.pw, flip = false)
    val ay = Axis(ydom, style.yLog, f.py0, f.ph, flip = true)
    xAxis(out, f, ax, th); yAxis(out, f, ay, th)
    for j <- 0 until m.cols do
      val pts = StringBuilder()
      var i = 0
      while i < m.rows do
        val x = xs(i); val y = m(i, j)
        val ok = java.lang.Double.isFinite(y) && (!style.yLog || y > 0.0) && (!style.xLog || x > 0.0)
        if ok then
          if pts.nonEmpty then pts += ' '
          pts ++= num(ax.pos(x)); pts += ','; pts ++= num(ay.pos(y))
        i += 1
      out.line(s"""<polyline points="$pts" fill="none" stroke="${th.color(j)}" stroke-width="1.5"/>""")
    if m.cols > 1 || labels.nonEmpty then
      legend(out, f, (0 until m.cols).map(j => LegendEntry(label(labels, j), th.color(j), "line")), th)
    axisLabels(out, f, style, th)
    close(out)
    out.toString

  /** Scatter of two columns; `groupCol >= 0` colours by the distinct values of that column. */
  def scatter(m: MatD, xCol: Int, yCol: Int, groupCol: Int, title: String, style: PlotStyle): String =
    val th  = theme(style)
    val f   = frame(style, title)
    val out = Out()
    open(out, f.w, f.h, th.chartBg)
    Svg.title(out, f, title, th)
    plotArea(out, f, th)
    val xs = column(m, xCol); val ys = column(m, yCol)
    val ax = Axis(if style.xLog then logDomain(xs) else domain(xs), style.xLog, f.px0, f.pw, flip = false)
    val ay = Axis(if style.yLog then logDomain(ys) else domain(ys), style.yLog, f.py0, f.ph, flip = true)
    xAxis(out, f, ax, th); yAxis(out, f, ay, th)
    def dots(rows: IndexedSeq[Int], color: String): Unit =
      for i <- rows do
        val x = xs(i); val y = ys(i)
        val ok = java.lang.Double.isFinite(x) && java.lang.Double.isFinite(y) &&
                 (!style.xLog || x > 0.0) && (!style.yLog || y > 0.0)
        if ok then
          out.line(s"""<circle cx="${num(ax.pos(x))}" cy="${num(ay.pos(y))}" r="3" fill="$color" fill-opacity="0.8"/>""")
    if groupCol < 0 then dots(0 until m.rows, th.color(0))
    else
      val gs     = column(m, groupCol)
      val groups = gs.filter(java.lang.Double.isFinite).distinct.sorted
      for (g, gi) <- groups.zipWithIndex do
        dots((0 until m.rows).filter(i => gs(i) == g), th.color(gi))
      legend(out, f, groups.toIndexedSeq.zipWithIndex.map((g, gi) => LegendEntry(fmtTick(g), th.color(gi), "dot")), th)
    axisLabels(out, f, style, th)
    close(out)
    out.toString

  /** Histogram of every finite value in the matrix, `bins` equal-width bins over [min, max]. */
  def hist(m: MatD, bins: Int, title: String, style: PlotStyle): String =
    val th  = theme(style)
    val f   = frame(style, title)
    val out = Out()
    open(out, f.w, f.h, th.chartBg)
    Svg.title(out, f, title, th)
    plotArea(out, f, th)
    val data = Array.tabulate(m.rows * m.cols)(k => m(k / m.cols, k % m.cols)).filter(java.lang.Double.isFinite)
    val nb   = math.max(1, bins)
    val (mn, mx) =
      if data.isEmpty then (0.0, 1.0)
      else
        val lo = data.min; val hi = data.max
        if lo == hi then (lo - 0.5, hi + 0.5) else (lo, hi)
    val binSize = (mx - mn) / nb
    val counts  = Array.fill(nb)(0)
    for v <- data do
      val b = math.min(((v - mn) / binSize).toInt, nb - 1)
      counts(b) += 1
    val ax = Axis(domain(Array(mn, mx)), false, f.px0, f.pw, flip = false)
    val ay = Axis(domain(Array(0.0, counts.max.toDouble)), false, f.py0, f.ph, flip = true)
    xAxis(out, f, ax, th); yAxis(out, f, ay, th)
    val y0 = ay.pos(0.0)
    for b <- 0 until nb do
      val x0 = ax.pos(mn + b * binSize); val x1 = ax.pos(mn + (b + 1) * binSize)
      val yt = ay.pos(counts(b).toDouble)
      out.line(s"""<rect x="${num(x0)}" y="${num(yt)}" width="${num(x1 - x0)}" height="${num(y0 - yt)}" fill="${th.color(0)}" stroke="$GridLine" stroke-width="1"/>""")
    axisLabels(out, f, style, th)
    close(out)
    out.toString

  /** Bar chart — one bar per row of `col`; labels from `labelCol` or the row index. */
  def bar(m: MatD, col: Int, labelCol: Int, title: String, style: PlotStyle): String =
    val th  = theme(style)
    val f   = frame(style, title)
    val out = Out()
    open(out, f.w, f.h, th.chartBg)
    Svg.title(out, f, title, th)
    plotArea(out, f, th)
    val ys = column(m, col)
    val ay = Axis(domain(ys :+ 0.0), false, f.py0, f.ph, flip = true)
    yAxis(out, f, ay, th)
    val n    = math.max(1, m.rows)
    val band = f.pw.toDouble / n
    val y0   = ay.pos(0.0)
    for i <- 0 until m.rows do
      val v = ys(i)
      if java.lang.Double.isFinite(v) then
        val yv = ay.pos(v)
        val top = math.min(y0, yv); val hgt = math.abs(y0 - yv)
        out.line(s"""<rect x="${num(f.px0 + i * band + band * 0.1)}" y="${num(top)}" width="${num(band * 0.8)}" height="${num(hgt)}" fill="${th.color(0)}"/>""")
      val lbl = if labelCol >= 0 then fmtTick(m(i, labelCol)) else i.toString
      text(out, f.px0 + i * band + band / 2.0, f.py0 + f.ph + 16.0, lbl, TickSize, "middle", th.text)
    out.line(s"""<line x1="${f.px0}" y1="${num(y0)}" x2="${f.px0 + f.pw}" y2="${num(y0)}" stroke="$AxisLine" stroke-width="1"/>""")
    axisLabels(out, f, style, th)
    close(out)
    out.toString

  /** Linear interpolation between gradient stops, `t` in [0,1]; channels rounded half-up. */
  def gradient(stops: IndexedSeq[String], t: Double): String =
    def ch(s: String, k: Int): Int = Integer.parseInt(s.substring(1 + 2 * k, 3 + 2 * k), 16)
    val tt = if t < 0.0 then 0.0 else if t > 1.0 then 1.0 else t
    val seg = tt * (stops.length - 1)
    val i0  = math.min(math.floor(seg).toInt, stops.length - 2)
    val u   = seg - i0
    val a = stops(i0); val b = stops(i0 + 1)
    def mix(k: Int): Int = math.floor(ch(a, k) + (ch(b, k) - ch(a, k)) * u + 0.5).toInt
    f"#${mix(0)}%02x${mix(1)}%02x${mix(2)}%02x"

  /** Heatmap — a colour cell per element, value text when the cell is big enough, colour
   *  bar at the right. `seriesColors` (2+) override the gradient stops. */
  def heatmap(m: MatD, title: String, rowLabels: Seq[String], colLabels: Seq[String], style: PlotStyle): String =
    val th    = theme(style)
    val stops = if style.seriesColors.length >= 2 then style.seriesColors.map(hex).toIndexedSeq else HeatStops
    val f     = frame(style, title, extraRight = 60)
    val out   = Out()
    open(out, f.w, f.h, th.chartBg)
    Svg.title(out, f, title, th)
    val all = Array.tabulate(m.rows * m.cols)(k => m(k / m.cols, k % m.cols)).filter(java.lang.Double.isFinite)
    val (mn, mx) = if all.isEmpty then (0.0, 1.0) else { val lo = all.min; val hi = all.max; if lo == hi then (lo - 0.5, hi + 0.5) else (lo, hi) }
    val cw = f.pw.toDouble / math.max(1, m.cols)
    val chh = f.ph.toDouble / math.max(1, m.rows)
    for i <- 0 until m.rows; j <- 0 until m.cols do
      val v = m(i, j)
      val x = f.px0 + j * cw; val y = f.py0 + i * chh
      if java.lang.Double.isFinite(v) then
        val t = (v - mn) / (mx - mn)
        out.line(s"""<rect x="${num(x)}" y="${num(y)}" width="${num(cw)}" height="${num(chh)}" fill="${gradient(stops, t)}" stroke="$GridLine" stroke-width="1"/>""")
        if cw >= 36.0 && chh >= 16.0 then
          val fill = if t < 0.25 || t > 0.75 then "#ffffff" else "#000000"
          text(out, x + cw / 2.0, y + chh / 2.0 + 4.0, fmtTick(v), TickSize, "middle", fill)
      else
        out.line(s"""<rect x="${num(x)}" y="${num(y)}" width="${num(cw)}" height="${num(chh)}" fill="${th.plotBg}" stroke="$GridLine" stroke-width="1"/>""")
    for j <- 0 until m.cols do
      val lbl = if colLabels.isDefinedAt(j) then colLabels(j) else j.toString
      text(out, f.px0 + j * cw + cw / 2.0, f.py0 + f.ph + 16.0, lbl, TickSize, "middle", th.text)
    for i <- 0 until m.rows do
      val lbl = if rowLabels.isDefinedAt(i) then rowLabels(i) else i.toString
      text(out, f.px0 - 7.0, f.py0 + i * chh + chh / 2.0 + 4.0, lbl, TickSize, "end", th.text)
    // colour bar: 20 bands, max at the top
    val bx = f.px0 + f.pw + 16; val bwid = 14
    val bands = 20
    val bandH = f.ph.toDouble / bands
    for k <- 0 until bands do
      val t = 1.0 - (k + 0.5) / bands
      out.line(s"""<rect x="$bx" y="${num(f.py0 + k * bandH)}" width="$bwid" height="${num(bandH)}" fill="${gradient(stops, t)}"/>""")
    text(out, bx + bwid + 4.0, f.py0 + 10.0, fmtTick(mx), TickSize, "start", th.text)
    text(out, bx + bwid + 4.0, f.py0 + f.ph.toDouble, fmtTick(mn), TickSize, "start", th.text)
    axisLabels(out, f, style, th)
    close(out)
    out.toString

  /** NumPy's linear-interpolation quantile on a sorted array (`percentileOf`'s rule). */
  def quantile(sorted: Array[Double], p: Double): Double =
    val n = sorted.length
    if n == 1 then sorted(0)
    else
      val idx = p * (n - 1)
      val lo  = idx.toInt
      val hi  = math.min(lo + 1, n - 1)
      val fr  = idx - lo
      sorted(lo) + fr * (sorted(hi) - sorted(lo))

  /** Box plot — one box per column: quartiles, 1.5·IQR whiskers, outliers as dots. */
  def boxPlot(m: MatD, title: String, labels: Seq[String], style: PlotStyle): String =
    val th  = theme(style)
    val f   = frame(style, title)
    val out = Out()
    open(out, f.w, f.h, th.chartBg)
    Svg.title(out, f, title, th)
    plotArea(out, f, th)
    val cols = (0 until m.cols).map(j => column(m, j).filter(java.lang.Double.isFinite).sorted)
    val all  = cols.flatten.toArray
    val ay   = Axis(domain(all), false, f.py0, f.ph, flip = true)
    yAxis(out, f, ay, th)
    val n    = math.max(1, m.cols)
    val band = f.pw.toDouble / n
    for j <- 0 until m.cols do
      val cx = f.px0 + j * band + band / 2.0
      val s  = cols(j)
      if s.nonEmpty then
        val q1 = quantile(s, 0.25); val q2 = quantile(s, 0.5); val q3 = quantile(s, 0.75)
        val iqr = q3 - q1
        val loFence = q1 - 1.5 * iqr; val hiFence = q3 + 1.5 * iqr
        val inside = s.filter(v => v >= loFence && v <= hiFence)
        val wlo = if inside.isEmpty then q1 else inside.min
        val whi = if inside.isEmpty then q3 else inside.max
        val bw  = band * 0.5
        val x0  = cx - bw / 2.0
        val color = th.color(j)
        out.line(s"""<line x1="${num(cx)}" y1="${num(ay.pos(wlo))}" x2="${num(cx)}" y2="${num(ay.pos(q1))}" stroke="$TextFill" stroke-width="1"/>""")
        out.line(s"""<line x1="${num(cx)}" y1="${num(ay.pos(q3))}" x2="${num(cx)}" y2="${num(ay.pos(whi))}" stroke="$TextFill" stroke-width="1"/>""")
        out.line(s"""<line x1="${num(cx - bw / 4.0)}" y1="${num(ay.pos(wlo))}" x2="${num(cx + bw / 4.0)}" y2="${num(ay.pos(wlo))}" stroke="$TextFill" stroke-width="1"/>""")
        out.line(s"""<line x1="${num(cx - bw / 4.0)}" y1="${num(ay.pos(whi))}" x2="${num(cx + bw / 4.0)}" y2="${num(ay.pos(whi))}" stroke="$TextFill" stroke-width="1"/>""")
        out.line(s"""<rect x="${num(x0)}" y="${num(ay.pos(q3))}" width="${num(bw)}" height="${num(ay.pos(q1) - ay.pos(q3))}" fill="$color" fill-opacity="0.6" stroke="$TextFill" stroke-width="1"/>""")
        out.line(s"""<line x1="${num(x0)}" y1="${num(ay.pos(q2))}" x2="${num(x0 + bw)}" y2="${num(ay.pos(q2))}" stroke="$TextFill" stroke-width="2"/>""")
        for v <- s if v < loFence || v > hiFence do
          out.line(s"""<circle cx="${num(cx)}" cy="${num(ay.pos(v))}" r="3" fill="none" stroke="$TextFill" stroke-width="1"/>""")
      text(out, cx, f.py0 + f.ph + 16.0, label(labels, j), TickSize, "middle", th.text)
    axisLabels(out, f, style, th)
    close(out)
    out.toString

  /** Scatterplot matrix — histograms on the diagonal, scatters off it, per-cell axes. */
  def pairs(m: MatD, title: String, labels: Seq[String], bins: Int, dotSize: Int, color: Color,
            scatterAlpha: Int, labelStyle: Int, style: PlotStyle): String =
    val p     = math.max(1, m.cols)
    val w     = style.width; val h = style.height
    val cellW = w / p; val cellH = h / p
    val out   = Out()
    open(out, w, h, "#ebebeb")
    val colData = Array.tabulate(p)(c => column(m, c))
    val colMin  = colData.map(a => domainMin(a))
    val colMax  = colData.map(a => domainMax(a))
    val solid   = hex(color)
    val alpha   = opacityAttr(scatterAlpha)
    val padL = 44; val padR = 4; val padT = 18; val padB = 32
    val tickLen = 3
    val gridColor = "#dcdcdc"; val axisColor = "#a0a0a0"; val textColor = "#404040"
    val tickFontSz = math.max(7, math.min(10, cellH / 14))
    val lblFontSz  = math.max(8, math.min(13, cellW / 14))
    val weight = if (labelStyle & java.awt.Font.BOLD) != 0 then """ font-weight="bold"""" else ""
    val slant  = if (labelStyle & java.awt.Font.ITALIC) != 0 then """ font-style="italic"""" else ""
    val lblAttrs = weight + slant
    val nb = math.max(1, bins)
    for i <- 0 until p; j <- 0 until p do
      val x0 = j * cellW; val y0 = i * cellH
      out.line(s"""<rect x="${x0 + 1}" y="${y0 + 1}" width="${cellW - 2}" height="${cellH - 2}" fill="#ffffff"/>""")
      val px0 = x0 + padL; val py0 = y0 + padT
      val pw  = cellW - padL - padR
      val ph  = cellH - padT - padB
      if i == j then
        val data = colData(i).filter(java.lang.Double.isFinite)
        val mn = colMin(i); val mx = colMax(i)
        val binSize = if mx == mn then 1.0 else (mx - mn) / nb
        val counts = Array.fill(nb)(0)
        for v <- data do counts(math.min(((v - mn) / binSize).toInt, nb - 1)) += 1
        val maxCount = math.max(1, counts.max).toDouble
        val bw = math.max(1, pw / nb - 1)
        for t <- niceTicks(0, maxCount, 4) do
          val ty = py0 + ph - (t / maxCount * ph).toInt
          if ty >= py0 && ty <= py0 + ph then
            out.line(s"""<line x1="$px0" y1="$ty" x2="${px0 + pw}" y2="$ty" stroke="$gridColor" stroke-width="1"/>""")
        for b <- 0 until nb do
          val barH = ((counts(b) / maxCount) * ph).toInt
          out.line(s"""<rect x="${px0 + b * pw / nb}" y="${py0 + ph - barH}" width="$bw" height="$barH" fill="$solid"/>""")
        out.line(s"""<line x1="$px0" y1="$py0" x2="$px0" y2="${py0 + ph}" stroke="$axisColor" stroke-width="1"/>""")
        out.line(s"""<line x1="$px0" y1="${py0 + ph}" x2="${px0 + pw}" y2="${py0 + ph}" stroke="$axisColor" stroke-width="1"/>""")
        val rng = if mx == mn then 1.0 else mx - mn
        for t <- niceTicks(mn, mx, 4) do
          val tx = px0 + ((t - mn) / rng * pw).toInt
          if tx >= px0 && tx <= px0 + pw then
            out.line(s"""<line x1="$tx" y1="${py0 + ph}" x2="$tx" y2="${py0 + ph + tickLen}" stroke="$axisColor" stroke-width="1"/>""")
            text(out, tx, py0 + ph + tickLen + tickFontSz, fmtTick(t), tickFontSz, "middle", textColor)
        text(out, x0 + cellW / 2.0, y0 + padT - 3.0, label(labels, i), lblFontSz, "middle", textColor, extra = lblAttrs)
      else
        val xs = colData(j); val ys = colData(i)
        val xmn = colMin(j); val xmx = colMax(j)
        val ymn = colMin(i); val ymx = colMax(i)
        val xrng = if xmx == xmn then 1.0 else xmx - xmn
        val yrng = if ymx == ymn then 1.0 else ymx - ymn
        val xTicks = niceTicks(xmn, xmx, 4)
        val yTicks = niceTicks(ymn, ymx, 4)
        for t <- xTicks do
          val tx = px0 + ((t - xmn) / xrng * pw).toInt
          if tx >= px0 && tx <= px0 + pw then
            out.line(s"""<line x1="$tx" y1="$py0" x2="$tx" y2="${py0 + ph}" stroke="$gridColor" stroke-width="1"/>""")
        for t <- yTicks do
          val ty = py0 + ph - ((t - ymn) / yrng * ph).toInt
          if ty >= py0 && ty <= py0 + ph then
            out.line(s"""<line x1="$px0" y1="$ty" x2="${px0 + pw}" y2="$ty" stroke="$gridColor" stroke-width="1"/>""")
        var k = 0
        while k < xs.length do
          if java.lang.Double.isFinite(xs(k)) && java.lang.Double.isFinite(ys(k)) then
            val px = px0 + ((xs(k) - xmn) / xrng * pw).toInt
            val py = py0 + ph - ((ys(k) - ymn) / yrng * ph).toInt
            out.line(s"""<rect x="$px" y="$py" width="$dotSize" height="$dotSize" fill="$solid"$alpha/>""")
          k += 1
        out.line(s"""<line x1="$px0" y1="$py0" x2="$px0" y2="${py0 + ph}" stroke="$axisColor" stroke-width="1"/>""")
        out.line(s"""<line x1="$px0" y1="${py0 + ph}" x2="${px0 + pw}" y2="${py0 + ph}" stroke="$axisColor" stroke-width="1"/>""")
        for t <- xTicks do
          val tx = px0 + ((t - xmn) / xrng * pw).toInt
          if tx >= px0 && tx <= px0 + pw then
            out.line(s"""<line x1="$tx" y1="${py0 + ph}" x2="$tx" y2="${py0 + ph + tickLen}" stroke="$axisColor" stroke-width="1"/>""")
            text(out, tx, py0 + ph + tickLen + tickFontSz, fmtTick(t), tickFontSz, "middle", textColor)
        for t <- yTicks do
          val ty = py0 + ph - ((t - ymn) / yrng * ph).toInt
          if ty >= py0 && ty <= py0 + ph then
            out.line(s"""<line x1="${px0 - tickLen}" y1="$ty" x2="$px0" y2="$ty" stroke="$axisColor" stroke-width="1"/>""")
            text(out, px0 - tickLen - 2.0, ty + tickFontSz / 2.0 - 1.0, fmtTick(t), tickFontSz, "end", textColor)
        text(out, px0 + pw / 2.0, y0 + cellH - 3.0, label(labels, j), tickFontSz, "middle", textColor, extra = lblAttrs)
        val cx = x0 + 9.0; val cy = py0 + ph / 2.0
        text(out, cx, cy, label(labels, i), tickFontSz, "middle", textColor,
          extra = lblAttrs + s""" transform="rotate(-90 ${num(cx)} ${num(cy)})"""")
    if title.nonEmpty then () // the window/page title carries it; the grid has no room
    close(out)
    out.toString

  private def domainMin(a: Array[Double]): Double =
    val f = a.filter(java.lang.Double.isFinite); if f.isEmpty then 0.0 else f.min
  private def domainMax(a: Array[Double]): Double =
    val f = a.filter(java.lang.Double.isFinite); if f.isEmpty then 1.0 else f.max

  /** A minimal page around the SVG, for the browser. */
  def html(svg: String, title: String): String =
    s"""<!doctype html>
       |<html><head><meta charset="utf-8"><title>${esc(if title.isEmpty then "uni.plot" else title)}</title>
       |<style>body{margin:0;background:#ffffff;display:flex;justify-content:center;align-items:flex-start;padding:12px}svg{max-width:100%;height:auto}</style>
       |</head><body>
       |$svg</body></html>
       |""".stripMargin

package uni.plot

import munit.FunSuite
import uni.data.*
import java.awt.Color

/** The SVG renderer's primitives, pinned by value (these are the numbers the Rust port
 *  must reproduce), and each chart type checked for shape and determinism. */
class PlotSvgSuite extends FunSuite:

  test("num: half-up to 2 decimals, trailing zeros dropped, no -0") {
    assertEquals(Svg.num(1.0), "1")
    assertEquals(Svg.num(1.5), "1.5")
    assertEquals(Svg.num(1.25), "1.25")
    assertEquals(Svg.num(1.005), "1")         // 1.005·100 is 100.49999999999999 in binary
    assertEquals(Svg.num(0.125), "0.13")
    assertEquals(Svg.num(-0.004), "0")
    assertEquals(Svg.num(-2.345), "-2.35")
    assertEquals(Svg.num(3.04), "3.04")
    assertEquals(Svg.num(Double.NaN), "0")
  }

  test("fmtTick: plain up to 3 decimals, scientific outside 1e-3..1e7") {
    assertEquals(Svg.fmtTick(0.0), "0")
    assertEquals(Svg.fmtTick(2.0), "2")
    assertEquals(Svg.fmtTick(2.5), "2.5")
    assertEquals(Svg.fmtTick(0.125), "0.125")
    assertEquals(Svg.fmtTick(0.1234), "0.123")
    assertEquals(Svg.fmtTick(-7.5), "-7.5")
    assertEquals(Svg.fmtTick(1234567.0), "1234567")
    assertEquals(Svg.fmtTick(1.5e7), "1.50e7")
    assertEquals(Svg.fmtTick(2.5e-4), "2.50e-4")
    assertEquals(Svg.fmtTick(9.999e9), "1.00e10")
  }

  test("log10: exact decades, libm-close elsewhere, and bit-pinned for the Rust port") {
    for k <- -20 to 20 do assertEquals(Svg.log10(Svg.pow10(k)), k.toDouble, s"10^$k")
    assertEquals(Svg.log10(0.0).isNaN, true); assertEquals(Svg.log10(-1.0).isNaN, true)
    assertEquals(Svg.log10(Double.PositiveInfinity), Double.PositiveInfinity)
    val rng = new scala.util.Random(11)
    for _ <- 1 to 20000 do
      val x = math.exp((rng.nextDouble() - 0.5) * 1400.0)
      assert(math.abs(Svg.log10(x) - math.log10(x)) <= 1e-15 * math.max(1.0, math.abs(math.log10(x))), s"x=$x")
    val sub = java.lang.Double.longBitsToDouble(0x0000000000000abcL)   // subnormal
    assert(math.abs(Svg.log10(sub) - math.log10(sub)) < 1e-13)
    // bits the Rust unit test pins too — the same operations must give the same word
    val pinned = Seq(2.0, 3.0, 7.5, 0.3, 123456.789, 1e-5, 6.02e23, sub).map(v => f"${java.lang.Double.doubleToRawLongBits(Svg.log10(v))}%016x")
    assertEquals(pinned, List("3fd34413509f79fe", "3fde8927964fd5fd", "3fec00807a8870fe", "bfe0bb6c34d81501",
      "40145db61a282512", "c014000000000000", "4037c793a2ba0758", "c073fde00ba7964f"))
  }

  test("niceTicks: 1/2/5 steps, first multiple inside, no accumulation drift") {
    assertEquals(Svg.niceTicks(0.0, 1.0, 5).toList, List(0.0, 0.2, 0.4, 0.6000000000000001, 0.8, 1.0))
    assertEquals(Svg.niceTicks(-3.0, 3.0, 5).toList, List(-3.0, -2.0, -1.0, 0.0, 1.0, 2.0, 3.0))
    assertEquals(Svg.niceTicks(0.0, 100.0, 4).toList, List(0.0, 20.0, 40.0, 60.0, 80.0, 100.0))
    assertEquals(Svg.niceTicks(2.0, 2.0, 4).toList, List(2.0))
    assertEquals(Svg.niceTicks(0.31, 0.39, 4).toList, List(0.32, 0.34, 0.36, 0.38))
  }

  test("domain pads 5%, widens a point, defaults when empty") {
    assertEquals(Svg.domain(Array(0.0, 10.0)), Svg.Domain(-0.5, 10.5))
    assertEquals(Svg.domain(Array(4.0, 4.0)), Svg.Domain(3.5, 4.5))
    assertEquals(Svg.domain(Array.empty[Double]), Svg.Domain(0.0, 1.0))
    assertEquals(Svg.domain(Array(Double.NaN, 1.0, 3.0)), Svg.Domain(0.9, 3.1))
  }

  test("gradient endpoints and midpoint") {
    assertEquals(Svg.gradient(Svg.HeatStops, 0.0), "#2166ac")
    assertEquals(Svg.gradient(Svg.HeatStops, 1.0), "#b2182b")
    assertEquals(Svg.gradient(Svg.HeatStops, 0.5), "#f7f7f7")
    assertEquals(Svg.gradient(IndexedSeq("#000000", "#ffffff"), 0.5), "#808080")
  }

  test("esc escapes the XML specials") {
    assertEquals(Svg.esc("""a<b&"c'"""), "a&lt;b&amp;&quot;c&apos;")
  }

  private def balanced(svg: String): Unit =
    val opens  = "<(svg|text|rect|line|circle|polyline|polygon)\\b".r.findAllIn(svg).size
    val closes = "(</svg>|</text>|/>)".r.findAllIn(svg).size
    assertEquals(opens, closes, "every element closes")
    assert(svg.startsWith("<svg xmlns=") && svg.trim.endsWith("</svg>"))

  private val m = MatD.tabulate(20, 3)((i, j) => (i + 1) * (j + 1) * 0.5 - 4.0)

  test("plot renders one polyline per column and a legend when cols > 1") {
    val svg = m.plotSvg(title = "t", labels = Seq("a", "b"))
    balanced(svg)
    assertEquals("<polyline".r.findAllIn(svg).size, 3)
    assert(svg.contains(">a</text>") && svg.contains(">col2</text>"))
    assertEquals(svg, m.plotSvg(title = "t", labels = Seq("a", "b")), "deterministic")
  }

  test("scatter groups by column value") {
    val g = MatD.tabulate(12, 3)((i, j) => if j == 2 then (i % 3).toDouble else i * 0.7 + j)
    val svg = g.scatterSvg(0, 1, groupCol = 2)
    balanced(svg)
    assertEquals("<circle".r.findAllIn(svg).size, 12 + 3, "12 points and 3 legend dots")
  }

  test("hist is an area series through the bin centres") {
    val svg = m.histSvg(bins = 6)
    balanced(svg)
    assertEquals("<polygon".r.findAllIn(svg).size, 1)
    assertEquals("<polyline".r.findAllIn(svg).size, 1)
    assertEquals("<rect".r.findAllIn(svg).size, 2, "chart bg, plot bg")
  }

  test("bar draws one bar per row and a baseline") {
    val b = MatD((1.0, 3.0), (2.0, -1.5), (3.0, 4.0))
    val svg = b.barSvg(col = 1, labelCol = 0)
    balanced(svg)
    assertEquals("<rect".r.findAllIn(svg).size, 2 + 3)
    assert(svg.contains(">1</text>") && svg.contains(">3</text>"))
  }

  test("heatmap draws a cell per element and 20 colour-bar bands") {
    val h = MatD.tabulate(3, 4)((i, j) => i - j.toDouble)
    val svg = h.heatmapSvg(rowLabels = Seq("r0"), colLabels = Seq("c0"))
    balanced(svg)
    assertEquals("<rect".r.findAllIn(svg).size, 1 + 12 + 20)
    assert(svg.contains(">r0</text>") && svg.contains(">c0</text>") && svg.contains(">2</text>"))
  }

  test("boxPlot draws a box per column with outliers as hollow circles") {
    val data = MatD.tabulate(30, 2)((i, j) => if i == 29 && j == 0 then 100.0 else i.toDouble + j)
    val svg = data.boxPlotSvg(labels = Seq("x", "y"))
    balanced(svg)
    assertEquals("<rect".r.findAllIn(svg).size, 2 + 2)
    assertEquals("<circle".r.findAllIn(svg).size, 1)
  }

  test("pairs draws a p×p grid") {
    val svg = m.pairsSvg(labels = Seq("a", "b", "c"), style = PlotStyle(width = 600, height = 600))
    balanced(svg)
    assertEquals(""" y="1" width="198" height="198" fill="#ffffff"""".r.findAllIn(svg).size, 3, "top row of cells")
  }

  test("style: colours, labels and log axes reach the SVG") {
    val st = PlotStyle(width = 400, height = 300, background = Some(Color.BLACK),
      foreground = Some(Color.WHITE), seriesColors = Seq(Color.RED), xLabel = "x", yLabel = "y", yLog = true)
    val pos = MatD.tabulate(10, 1)((i, _) => Svg.pow10(i))
    val svg = pos.plotSvg(style = st)
    balanced(svg)
    assert(svg.contains("""fill="#000000""""), "background")
    assert(svg.contains("""stroke="#ff0000""""), "series colour")
    assert(svg.contains(">x</text>") && svg.contains(">y</text>"))
    assert(svg.contains(">1.00e7</text>"), "log ticks are powers of ten")
  }

  test("Deliver.save applies the extension rule and writes html on request") {
    val dir = java.nio.file.Files.createTempDirectory("uni-plot-test")
    val p1 = Deliver.save("<svg/>", "t", s"$dir/a")
    val p2 = Deliver.save("<svg/>", "t", s"$dir/b.html")
    assert(p1.toString.endsWith("a.svg") && java.nio.file.Files.exists(p1))
    assert(java.nio.file.Files.readString(p2).startsWith("<!doctype html>"))
  }

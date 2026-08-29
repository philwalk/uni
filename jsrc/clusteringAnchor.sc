#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.22.0

// Measures the volatility-clustering anchor for marketSim's `clustering lag 1` and `lag 20`
// fidelity targets, from a real daily series.
//
// ---------------------------------------------------------------------------------------------
// TL;DR — point it at a file of daily market data and read the last section:
//
//     scala-cli run jsrc/clusteringAnchor.sc -- F-F_Research_Data_Factors_daily.CSV
//
// It works out for itself which column is the date, which holds the numbers, whether those are
// prices or returns, and how many header lines to skip.  It prints every one of those decisions so
// you can check them, and it prints the two lines to paste into `FitTargets` at the end.
// ---------------------------------------------------------------------------------------------
//
// WHY THIS EXISTS.  Of marketSim's fifteen fidelity anchors, `clustering lag 1` (0.27) and
// `lag 20` (0.20) are the only ones with no recorded source, window OR CONVENTION.  That last one
// is the problem: "volatility clustering" names several different statistics and they disagree —
// on one model path, lag-20 autocorr(|r|) reads 0.266 where autocorr(r^2) reads 0.219, 21% apart.
// So nobody currently knows whether the model's clustering being 20% high is a real defect or a
// units mismatch, and a band built on an unverified anchor would freeze whatever error is in it.
// The `return per vol` anchor arrived 10% wrong from exactly this cause and only a recomputation
// caught it.
//
// WHAT DOES NOT MATTER, so that it is not asked.  Autocorrelation is SCALE-INVARIANT, so returns
// in percent and returns as fractions give identical answers.  Log versus simple returns differ
// in the fourth decimal at daily frequency.  And with Ken French's file, using the excess return
// `Mkt-RF` rather than the total market return `Mkt-RF + RF` moves the answer by 0.001 — so point
// it at the `Mkt-RF` column and do not bother reconstructing the total.  None of these three is
// a decision anyone has to make here, which is why none of them is a flag.
//
// WHAT DOES MATTER, and is therefore printed: |r| versus r^2, the window, and the block spread.
//
// IT DELEGATES.  The lag statistic comes from `uni.apps.MarketSim.autocorrAbs` — the very function
// the model grades itself with — so it cannot drift from it.  Restating the formula here would let
// a mistake in one be confirmed by a matching mistake in the other, the failure `MatParitySuite`
// avoids by calling the generator instead of re-deriving its definitions.

import uni.*
import uni.time.*
import uni.apps.MarketSim

object ClusteringAnchor {
  def println(s: String = ""): Unit = print(s"$s\n")
  def eprintln(s: String = ""): Unit = System.err.print(s"$s\n")

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "<file>        ; a CSV/TSV of DAILY market data, one row per session.  Everything below is",
    "              ;   optional: the columns, the header size and prices-vs-returns are detected",
    "              ;   from the file and echoed back for you to check.",
    "",
    "  overrides, for when the detection guesses wrong (it says what it guessed):",
    "-col N        ; 1-based column holding the numbers",
    "-datecol N    ; 1-based column holding the dates",
    "-price        ; force: the numbers are a price level (differences are taken)",
    "-return       ; force: the numbers are already per-period returns",
    "-skip N       ; force: this many leading lines are header/preamble",
    "",
    "  what to measure:",
    "-anchor A:B   ; window the TARGET is taken from, YYYY-MM-DD:YYYY-MM-DD.  Default: the whole",
    "              ;   file.  Use 1954-01-01:2026-12-31 to match marketSim's other equity anchors.",
    "-block Y      ; block length in years for the spread that sets the BAND (default 20)",
    "-lags A,B     ; lags to report (default 1,20 — the two the model targets)",
  )

  /** Trading days per year, matching the model's own `DaysPerYear`. */
  val DaysPerYear = 252

  def intOr(flag: String, v: String): Int =
    v.toIntOption.getOrElse(usage(s"$flag wants an integer, got [$v]"))

  /** autocorr of SQUARED returns — deliberately not what the model grades. Printed beside the
    * real statistic so the convention question is answered by this run rather than left open. */
  def autocorrSq(r: Array[Double], lag: Int): Double =
    val sq = r.map(x => x * x)
    val m  = sq.sum / sq.length
    val z  = sq.map(_ - m)
    val den = z.map(x => x * x).sum
    if den <= 0 || r.length <= lag then Double.NaN
    else (0 until r.length - lag).map(i => z(i) * z(i + lag)).sum / den

  /** True for a cell that is a date in any form this is likely to meet, INCLUDING the bare
    * `YYYYMMDD` that Ken French's files use — which `parseDate` alone would read as a number. */
  def looksLikeDate(c: String): Boolean =
    val t = c.trim
    if t.length == 8 && t.forall(_.isDigit) then
      val y = t.take(4).toInt
      val m = t.slice(4, 6).toInt
      val d = t.slice(6, 8).toInt
      y >= 1800 && y <= 2100 && m >= 1 && m <= 12 && d >= 1 && d <= 31
    else t.exists(ch => ch == '-' || ch == '/') && parseDate(t) != BadDate

  /** `YYYYMMDD` and everything else, normalised to `yyyy-MM-dd` so windows and labels are uniform. */
  def normDate(c: String): String =
    val t = c.trim
    if t.length == 8 && t.forall(_.isDigit) then s"${t.take(4)}-${t.slice(4, 6)}-${t.slice(6, 8)}"
    else
      val d = parseDate(t)
      if d == BadDate then "" else d.ymd

  def isNum(c: String): Boolean = c.trim.toDoubleOption.isDefined

  def report(label: String, r: Array[Double], lags: Vector[Int]): Unit =
    if r.length < 500 then
      println(f"  $label%-22s ${r.length}%6d obs   too short to be worth reporting")
    else
      val cells = lags.map(l => f"${MarketSim.autocorrAbs(r, l)}%9.3f${autocorrSq(r, l)}%9.3f").mkString
      println(f"  $label%-22s ${r.length}%6d obs$cells%s")

  def main(args: Array[String]): Unit = {
    var file = ""; var colOv = 0; var dateColOv = 0; var skipOv = -1
    var forcePrice = false; var forceReturn = false
    var anchor = ""; var blockYears = 20
    var lags = Vector(1, 20)
    eachArg(args.toSeq, usage) {
      case "-col"     => colOv = intOr("-col", consumeNext)
      case "-datecol" => dateColOv = intOr("-datecol", consumeNext)
      case "-skip"    => skipOv = intOr("-skip", consumeNext)
      case "-price"   => forcePrice = true
      case "-return"  => forceReturn = true
      case "-anchor"  => anchor = consumeNext
      case "-block"   => blockYears = intOr("-block", consumeNext)
      case "-lags"    => lags = consumeNext.split(",").map(s => intOr("-lags", s.trim)).toVector
      case f if file.isEmpty && f.asPath.isFile => file = f
      case a          => usage(s"unrecognized arg [$a]")
    }
    if file.isEmpty then usage("no input file — give it a CSV/TSV of daily market data")
    if forcePrice && forceReturn then usage("-price and -return are mutually exclusive")
    if blockYears < 1 then usage(s"-block wants at least 1 year, got $blockYears")

    val all = file.asPath.csvRows.filter(_.exists(_.trim.nonEmpty))
    if all.isEmpty then usage(s"[$file] carried no rows")

    // ---- work out the shape of the file, and SAY SO -----------------------------------------
    // Every one of these is a guess that could be wrong, and a wrong guess that stayed silent
    // would produce a confident wrong anchor — the exact failure this exercise exists to prevent.
    // So each is echoed, and each has an override flag.
    val firstData = if skipOv >= 0 then skipOv else all.indexWhere(r => r.count(isNum) >= 1)
    if firstData < 0 then usage(s"[$file] has no row with a numeric cell — is it the right file?")
    val rows = all.drop(firstData)
    val width = rows.head.length

    val dateCol =
      if dateColOv > 0 then dateColOv
      else (1 to width).find(c => rows.take(200).count(r => r.length >= c && looksLikeDate(r(c - 1))) > 150)
                       .getOrElse(0)
    val valCol =
      if colOv > 0 then colOv
      else (1 to width).find(c => c != dateCol &&
                                  rows.take(200).count(r => r.length >= c && isNum(r(c - 1))) > 150)
                       .getOrElse(usage(s"[$file] — no column looks numeric; name one with -col N"))

    var dropped = 0
    val parsed = rows.flatMap { r =>
      if r.length < valCol then { dropped += 1; None }
      else r(valCol - 1).trim.toDoubleOption match
        case None    => dropped += 1; None
        case Some(v) =>
          val d = if dateCol > 0 && r.length >= dateCol then normDate(r(dateCol - 1)) else ""
          Some((d, v))
    }
    if parsed.length < 2 then usage(s"[$file] yielded ${parsed.length} usable rows")

    val values = parsed.map(_._2).toArray
    // A price level is positive by construction; a daily return series over any real span goes
    // negative. That is the whole discriminator, and it does not care about scale.
    val isPrice = if forcePrice then true else if forceReturn then false else !values.exists(_ <= 0.0)

    val rets: Array[Double] =
      if isPrice then Array.tabulate(values.length - 1)(i => math.log(values(i + 1) / values(i)))
      else values
    // Returns belong to the LATER date of each pair, so a window filter selects returns that
    // happened inside it rather than one straddling the boundary.
    val retDates = if isPrice then parsed.map(_._1).drop(1) else parsed.map(_._1)
    val dated = retDates.headOption.exists(_.nonEmpty)

    println("VOLATILITY-CLUSTERING ANCHOR — for marketSim's `clustering lag 1` / `lag 20` targets.")
    println()
    println("WHAT I READ FROM THE FILE (override any of it with the flags in -h):")
    println(f"  file                 ${file.asPath.getFileName}%s")
    println(f"  header lines skipped $firstData%d")
    println(f"  date column          ${if dateCol > 0 then dateCol.toString else "none found"}%s")
    println(f"  numbers column       $valCol%d")
    println(f"  read as              ${if isPrice then "PRICE LEVELS (log differences taken)"
                                       else "RETURNS (used as-is)"}%s")
    println(f"  usable observations  ${rets.length}%d" +
            (if dropped > 0 then s"   ($dropped rows dropped as unparseable)" else ""))
    if dated then println(f"  spanning             ${retDates.head}%s .. ${retDates.last}%s")
    println()
    println("Scale does not matter: autocorrelation is scale-invariant, so percent and fractional")
    println("returns give identical answers.  |r| vs r^2 DOES matter, so both are shown.  The |r|")
    println("column is uni.apps.MarketSim.autocorrAbs — the model's own function, not a copy of it.")
    println()
    println(f"  ${"window"}%-22s ${"n"}%6s    " +
            lags.map(l => f"${s"|r| L$l"}%9s${s"r^2 L$l"}%9s").mkString)
    report("whole file", rets, lags)

    // ---- the target ---------------------------------------------------------------------------
    var targetRets = rets
    var targetLabel = "whole file"
    if anchor.nonEmpty then
      if !dated then usage("-anchor needs a date column; none was found (name one with -datecol N)")
      val Array(a, b) = anchor.split(":"): @unchecked
      val lo = parseDate(a.trim)
      val hi = parseDate(b.trim)
      if lo == BadDate || hi == BadDate then
        usage(s"-anchor wants YYYY-MM-DD:YYYY-MM-DD, got [$anchor]")
      val sel = retDates.zip(rets).filter { (d, _) =>
        val dt = parseDate(d)
        dt != BadDate && !dt.isBefore(lo) && !dt.isAfter(hi)
      }.map(_._2).toArray
      if sel.length < 500 then usage(s"-anchor $anchor selected only ${sel.length} observations")
      targetRets = sel
      targetLabel = s"ANCHOR ${a.trim.take(4)}..${b.trim.take(4)}"
      report(targetLabel, sel, lags)

    // ---- the band -----------------------------------------------------------------------------
    // Non-overlapping blocks, the protocol the `return per vol` band used. Overlapping windows
    // would understate the spread and yield a band too tight to be honest.
    val blockLen = blockYears * DaysPerYear
    val nBlocks = rets.length / blockLen
    val blockVals = scala.collection.mutable.Map.empty[Int, Vector[Double]]
    if nBlocks >= 2 then
      println()
      println(s"  non-overlapping $blockYears-year blocks — this spread is what sets the BAND:")
      for b <- 0 until nBlocks do
        val seg = rets.slice(b * blockLen, (b + 1) * blockLen)
        val lbl =
          if dated then s"${retDates(b * blockLen).take(4)}..${retDates((b + 1) * blockLen - 1).take(4)}"
          else s"block ${b + 1}"
        report(lbl, seg, lags)
        for l <- lags do
          blockVals(l) = blockVals.getOrElse(l, Vector.empty) :+ MarketSim.autocorrAbs(seg, l)

    // ---- what to do with it -------------------------------------------------------------------
    println()
    println("WHAT TO DO WITH THIS")
    println()
    for l <- lags do
      val t = MarketSim.autocorrAbs(targetRets, l)
      val sq = autocorrSq(targetRets, l)
      val gap = if sq != 0.0 then math.abs(t - sq) / math.abs(sq) * 100.0 else Double.NaN
      println(f"  lag $l%d — target ${t}%.3f from $targetLabel%s")
      if !gap.isNaN && gap > 5.0 then
        println(f"     the r^2 convention reads ${sq}%.3f here, ${gap}%.0f%% away.  The existing target must")
        println( "     therefore state WHICH it is, or the next person will re-derive the wrong one.")
      blockVals.get(l) match
        case Some(vs) if vs.size >= 2 =>
          println(f"     blocks ran ${vs.min}%.3f to ${vs.max}%.3f — a band of about ${vs.min - 0.02}%.2f-${vs.max + 0.02}%.2f")
        case _ =>
          println(s"     not enough whole $blockYears-year blocks here to set a band")
    println()
    println("  Set the band from the block RANGE, not from block percentiles.  The model's value is")
    println("  a population statistic over 20,000 path-years and carries no sampling error, so a band")
    println("  drawn from the sampling spread of one window would be too wide ever to bind — which is")
    println("  the defect the current realism band (0.10-0.40 against a real 0.27) already has.")
  }
}

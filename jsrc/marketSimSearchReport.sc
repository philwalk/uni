#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation -q

//> using scala 3.7.0
//> using dep org.vastblue:uni_3:0.24.2

// IS THE SEARCH STILL BUYING ANYTHING?  A read-only report on a running or finished calibration
// search, from the files `market_sim_search` writes -- so it can be run at any time, as often as
// you like, without touching the run.
//
//   jsrc/marketSimSearchReport.sc search-0.24.2          one screen
//   jsrc/marketSimSearchReport.sc search-0.24.2 -full    every block, every row
//
// THE QUESTION IT EXISTS TO ANSWER is when to stop, and the best score is the wrong thing to watch
// for that.  The product of this search is the ARCHIVE, a set of worlds that all match the record
// and differ in ways a strategy can feel; the best member is one row of it.  A search whose best
// score has not moved for fifty generations may still be filling gaps in the set, and a search
// still nudging its best down may have stopped adding anything else.
//
// THREE TRACES, because each is blind where the others see.
//
// DRAW is what the mutation operator produced, compared with nothing.  No bias, and if its
// quantiles are still sinking the search is still learning where to look.
//
// PRESSURE is how many candidates entered the archive.  Under spread-keeping admission it does
// NOT fall as the search converges: a feasible candidate that improves on its nearest neighbour
// enters, and late in a run about a third of them do, replacing members INSIDE the spread.  So
// it says whether the search is alive, not whether it is buying anything.
//
// SPREAD is what it is buying: the span of the descriptor columns over everything admitted so far,
// each descriptor against its final span, averaged.  The product is the set, and the set is worth
// its spread; when two blocks add under a percent of it, more generations buy replacements, not
// coverage.  The VERDICT reads this trace.  A member trimmed later still counts, so the trace is
// an upper bound that stops growing when the archive does.

import uni.*
import uni.apps.MarketSim

object MarketSimSearchReport:
  def println(s: String = ""): Unit = print(s"$s\n")

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "DIR           ; a search output directory (default ./search)",
    "-full         ; every block of every trend, and the deciding-row breakdown",
    "-ascii        ; draw the trend lines with ASCII rather than block characters, for a",
    "              ;   console that is not in UTF-8 (on Windows: chcp.com 65001)",
    "-block N      ; generations per block under -full (default: the run / 8, min 10)",
  )

  /** One candidate as `log.tsv` records it -- written for every candidate the search evaluates,
    * including the rejected majority, which is what makes these trends possible at all. */
  final case class Row(gen: Int, feasible: Boolean, score: Double, raw: Double,
                       worst: String, secs: Double, desc: Vector[Double],
                       admitted: Option[Boolean])

  /** The log's fixed columns; every other column is a behaviour descriptor, whatever the
    * harness names them. */
  val Fixed = Set("gen", "eval", "parent", "feasible", "score", "raw", "worstRow", "seconds",
                  "gateFail", "admitted")

  /** BY HEADER NAME, never by position: the log gained `gateFail` once and `admitted` since,
    * and a reader that counts columns reads the wrong one the next time that happens. */
  def readLog(dir: String): (Vector[String], Vector[Row]) =
    val p = s"$dir/log.tsv".asPath
    if !p.exists then usage(s"no $dir/log.tsv")
    val ls = p.lines.toVector
    val head = ls.headOption.getOrElse(usage(s"$dir/log.tsv is empty")).split("\t").toVector
    val at = head.zipWithIndex.toMap
    def col(name: String): Int =
      at.getOrElse(name, usage(s"$dir/log.tsv has no `$name` column"))
    val (cg, cf, cs, cr, cw, ct) =
      (col("gen"), col("feasible"), col("score"), col("raw"), col("worstRow"), col("seconds"))
    val descCols = head.zipWithIndex.filterNot((n, _) => Fixed(n))
    // `admitted` arrived after the first runs; absent means the proxy, and the report says so
    val ca = at.get("admitted")
    val rows = ls.drop(1).filter(_.trim.nonEmpty).flatMap { l =>
      val f = l.split("\t")
      if f.length <= ct then None
      else
        for
          g <- f(cg).toIntOption
          s <- f(cs).toDoubleOption
          r <- f(cr).toDoubleOption
          t <- f(ct).toDoubleOption
        yield Row(g, f(cf) == "true", s, r, f(cw), t,
                  descCols.map((_, i) => f.lift(i).flatMap(_.toDoubleOption).getOrElse(Double.NaN)),
                  ca.filter(f.length > _).map(f(_) == "true"))
    }
    (descCols.map(_._1), rows)

  def readState(dir: String): Map[String, String] =
    val p = s"$dir/state.tsv".asPath
    if !p.exists then Map.empty
    else p.lines.toVector.drop(1).flatMap(_.split("\t", 2) match
      case Array(k, v) => Some(k -> v)
      case _           => None).toMap

  final case class Archive(names: Vector[String], scores: Vector[Double],
                           descNames: Vector[String], desc: Vector[Vector[Double]])

  def readArchive(dir: String): Archive =
    val p = s"$dir/archive.tsv".asPath
    if !p.exists then Archive(Vector.empty, Vector.empty, Vector.empty, Vector.empty)
    else
      val ls = p.lines.toVector.filter(_.trim.nonEmpty)
      val head = ls.head.split("\t").toVector
      // the descriptor columns are whatever follows the dials; the dial count comes from the jar,
      // so this keeps working when a dial is added
      val nDials = MarketSim.CalibrateRanges.length
      val dNames = head.drop(4 + nDials)
      val rows = ls.drop(1).map(_.split("\t").toVector)
      Archive(rows.map(_(0)), rows.map(_(1).toDouble), dNames,
              rows.map(r => dNames.indices.toVector.map(i =>
                r.lift(4 + nDials + i).flatMap(_.toDoubleOption).getOrElse(Double.NaN))))

  /** A trend line in one row.  Eight levels, scaled to the series' OWN range, because what these
    * lines are for is DIRECTION -- the numbers beside them carry the level.  A gap is a block the
    * run has no candidates in. */
  val Blocks = "\u2581\u2582\u2583\u2584\u2585\u2586\u2587\u2588"
  val Plain  = "_.-=+*#%"

  /** Each descriptor's range over the rows, NaN where none reads. */
  def descSpan(rows: Vector[Row], n: Int): Vector[Double] =
    (0 until n).toVector.map { j =>
      val xs = rows.map(_.desc(j)).filter(x => !x.isNaN)
      if xs.isEmpty then Double.NaN else xs.max - xs.min
    }

  def spark(v: Vector[Double], ramp: String): String =
    val f = v.filter(x => !x.isNaN)
    if f.isEmpty then ""
    else
      val (lo, hi) = (f.min, f.max)
      v.map { x =>
        if x.isNaN then ' '
        else if hi == lo then ramp(ramp.length / 2)
        else ramp(math.min(ramp.length - 1,
                           ((x - lo) / (hi - lo) * (ramp.length - 1) + 0.5).toInt))
      }.mkString

  def hms(secs: Double): String =
    val h = (secs / 3600).toInt
    val m = ((secs - h * 3600) / 60).toInt
    if h > 0 then f"${h}h ${m}%02dm" else f"${m}m"

  def main(args: Array[String]): Unit =
    var dir = ""
    var block = 0
    var full = false
    var ascii = false
    eachArg(args.toSeq, usage) {
      case "-full"  => full = true
      case "-ascii" => ascii = true
      case "-block" => block = intOrDie(consumeNext)
      case a if !a.startsWith("-") && dir.isEmpty => dir = a
      case a => usage(s"unrecognized arg [$a]")
    }
    if dir.isEmpty then dir = "search"

    val (descNames, log) = readLog(dir)
    if log.isEmpty then usage(s"$dir/log.tsv has no candidates yet")
    val st   = readState(dir)
    val arc  = readArchive(dir)
    val gens = log.map(_.gen).max + 1
    val blk  = if block > 0 then block else math.max(10, gens / 8)
    val feas = log.filter(_.feasible)
    def mean(v: Vector[Double]): Double = if v.isEmpty then Double.NaN else v.sum / v.length
    def inGen(lo: Int, hi: Int) = log.filter(r => r.gen >= lo && r.gen < hi)

    println(s"$dir   $gens gen, ${log.length} candidates, " +
            s"${st.getOrElse("paths", "?")}x${st.getOrElse("years", "?")}y " +
            s"x${st.getOrElse("reps", "?")}, ${st.getOrElse("anchors", "?")}" +
            st.get("transport").filter(_ != "(none)").map(t => s", transport $t").getOrElse(""))

    println(f"COST      ${mean(log.map(_.secs))}%.2f s/candidate " +
            f"(feasible ${mean(feas.map(_.secs))}%.2f, rejected " +
            f"${mean(log.filterNot(_.feasible).map(_.secs))}%.2f)   " +
            s"${hms(log.map(_.secs).sum)} spent   100 gen = " +
            hms(mean(log.map(_.secs)) * 100 * log.length / gens))

    println(f"YIELD     ${100.0 * feas.length / log.length}%.0f%% feasible")

    feas.minByOption(_.raw).foreach { b =>
      println(f"BEST      raw ${b.raw}%.4f at gen ${b.gen}%d (${gens - 1 - b.gen}%d ago)")
    }

    // THE TREND LINES.  Enough buckets to see a shape, few enough to stay on one row.
    val ramp = if ascii then Plain else Blocks
    val nb   = math.min(24, math.max(4, gens))
    val wide = gens.toDouble / nb
    def bucket(i: Int) = ((i * wide).toInt, math.max(((i + 1) * wide).toInt, (i * wide).toInt + 1))
    def series(f: Vector[Row] => Double): Vector[Double] =
      (0 until nb).toVector.map { i =>
        val (lo, hi) = bucket(i)
        val g = inGen(lo, hi)
        if g.isEmpty then Double.NaN else f(g)
      }
    def ends(v: Vector[Double]): (Double, Double) =
      val f = v.filter(x => !x.isNaN)
      if f.isEmpty then (Double.NaN, Double.NaN) else (f.head, f.last)

    val dBest = series(g => { val f = g.filter(_.feasible).map(_.score)
                              if f.isEmpty then Double.NaN else f.min })
    val dMed  = series(g => { val f = g.filter(_.feasible).map(_.score).sorted
                              if f.isEmpty then Double.NaN else MarketSim.pctile(f, 0.5) })
    // BEST-SO-FAR rather than each bucket's own best: a minimum over a few dozen draws is a
    // high-variance statistic and its trace reads as noise.  The running figure is monotone, so
    // the line is the staircase of actual progress and a flat tail is the thing to look for.
    val dRun = dBest.scanLeft(Double.NaN)((acc, x) =>
      if x.isNaN then acc else if acc.isNaN then x else math.min(acc, x)).drop(1)
    val (b0, b1) = ends(dRun)
    val (m0, m1) = ends(dMed)
    println(f"DRAW      best   ${spark(dRun, ramp)}  ${b0}%.4f -> ${b1}%.4f   (flat tail = done)")
    println(f"          median ${spark(dMed, ramp)}  ${m0}%.4f -> ${m1}%.4f   (each block's own)")

    val yld = series(g => 100.0 * g.count(_.feasible) / g.length)
    val (y0, y1) = ends(yld)
    println(f"YIELD     feas   ${spark(yld, ramp)}  ${y0}%.0f%% -> ${y1}%.0f%%")

    val logged = log.exists(_.admitted.isDefined)
    if logged || arc.scores.nonEmpty then
      // THE REAL COUNT when the log carries it.  The old proxy -- feasible and scoring better
      // than the archive's worst member -- SATURATES under spread-keeping, which admits a poor
      // scorer for its behaviour: at a worst of 0.906 against a best of 0.070 it counts every
      // feasible candidate, and its verdict then says "still turning over" forever.
      val worst = if arc.scores.isEmpty then Double.MaxValue else arc.scores.max
      val pres = series(g =>
        if logged then g.count(_.admitted.contains(true)).toDouble
        else g.count(r => r.feasible && r.score < worst).toDouble)
      val (p0, p1) = ends(pres)
      println(f"PRESSURE  ${if logged then "admit" else "beats"}%-6s ${spark(pres, ramp)}  " +
              f"${p0}%.0f -> ${p1}%.0f a block" +
              (if logged then "" else "   (PROXY: no `admitted` column; early blocks read low)"))

    // SPREAD: the descriptor span over everything that entered the archive, cumulative to each
    // generation, each descriptor as a fraction of its final span, averaged.  Without an
    // `admitted` column every feasible candidate counts, which over-reads the early blocks.
    val entered = if logged then log.filter(_.admitted.contains(true)) else feas
    val spanEnd = descSpan(entered, descNames.length)
    def spreadAt(g: Int): Double =
      val r = descSpan(entered.filter(_.gen < g), descNames.length).zip(spanEnd)
                .collect { case (a, b) if !a.isNaN && b > 0 => a / b }
      if r.isEmpty then Double.NaN else mean(r)
    if descNames.nonEmpty then
      val spr = (0 until nb).toVector.map(i => 100.0 * spreadAt(bucket(i)._2))
      val (s0, s1) = ends(spr)
      println(f"SPREAD    span   ${spark(spr, ramp)}  ${s0}%.0f%% -> ${s1}%.0f%% of final   (flat tail = done)")

    if logged || arc.scores.nonEmpty then
      println(f"ARCHIVE   ${arc.names.length}%d members, score ${arc.scores.min}%.3f..${arc.scores.max}%.3f; " +
              "from " + arc.names.groupBy(identity).view.mapValues(_.length).toVector
                .sortBy(-_._2).map((n, c) => s"$n $c").mkString(", "))
      if arc.descNames.nonEmpty then
        val cells = arc.descNames.indices.map { i =>
          val xs = arc.desc.map(_(i)).filter(x => !x.isNaN)
          if xs.isEmpty then "" else f"${arc.descNames(i)}%-9s ${xs.min}%7.3f..${xs.max}%-8.3f"
        }
        cells.grouped(3).foreach(g => println("          " + g.mkString("  ")))

    // ---- the verdict ---------------------------------------------------------------------
    // Deliberately crude, and it says which numbers it read: a stopping rule nobody can check is
    // worse than no stopping rule.  It reads SPREAD, in points of the final span added over the
    // last two blocks against the two before, because admissions never fall under spread-keeping
    // and the best score is one row of the product.  Two blocks, because one block of a search
    // this noisy proves nothing.
    if descNames.nonEmpty && gens >= 4 * blk then
      val last  = 100.0 * (spreadAt(gens) - spreadAt(gens - 2 * blk))
      val prior = 100.0 * (spreadAt(gens - 2 * blk) - spreadAt(gens - 4 * blk))
      val added = f"the last ${2 * blk}%d gen added ${last}%.1f%% of the spread, the ${2 * blk}%d " +
                  f"before ${prior}%.1f%%"
      val verdict =
        if last < 2.0 then s"CLOSED -- $added. Run -holdout, then -prune."
        else if last < prior / 2 then s"CLOSING -- $added. Finish, do not extend."
        else s"STILL GROWING -- $added."
      println(s"VERDICT   $verdict")

    if full then
      println()
      println("YIELD, PRESSURE and DRAW by block:")
      println("   block            feasible   beats archive   draw best   draw median")
      val worst = if arc.scores.nonEmpty then arc.scores.max else Double.PositiveInfinity
      for b <- 0 until gens by blk do
        val g  = inGen(b, b + blk)
        val gf = g.filter(_.feasible).map(_.score).sorted
        if g.nonEmpty then
          println(f"   gen ${b}%4d-${math.min(b + blk, gens) - 1}%-4d    " +
                  f"${100.0 * g.count(_.feasible) / g.length}%3.0f%%   " +
                  f"${g.count(r => r.feasible && r.score < worst)}%5d of ${g.length}%-5d " +
                  (if gf.isEmpty then "       --          --"
                   else f"${gf.head}%9.4f   ${MarketSim.pctile(gf, 0.5)}%9.4f"))
      println()
      val recent = log.takeRight(math.min(log.length, 8 * blk))
      println(s"The row deciding a score, last ${recent.length} candidates:")
      recent.map(_.worst).groupBy(identity).toVector.sortBy(-_._2.length).take(6)
        .foreach((nm, g) => println(f"   ${g.length}%4d  $nm"))
      val arms = recent.map(r => if r.worst.contains(':') then r.worst.takeWhile(_ != ':') else "primary")
      if arms.exists(_ != "primary") then
        println("   arms: " + arms.groupBy(identity).view.mapValues(_.length).toVector
          .sortBy(-_._2).map((a, c) => s"$a $c").mkString(", "))

  def intOrDie(v: String): Int = v.toIntOption.getOrElse(usage(s"-block wants an integer, got [$v]"))


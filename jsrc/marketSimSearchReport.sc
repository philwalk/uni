#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

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
// TWO TRACES, because each is blind where the other sees.
//
// DRAW is what the mutation operator produced, compared with nothing.  No bias, and if its
// quantiles are still sinking the search is still learning where to look.
//
// PRESSURE is how many candidates would enter TODAY's archive.  Falling to zero means the archive
// has closed and further generations buy only a lucky draw.  It understates the true admission
// rate -- a candidate also enters by beating the nearest member within `-sep`, which needs the
// archive's history to see -- and it is BIASED AGAINST EARLY BLOCKS, which are judged against an
// archive better than the one they faced.  So a RISING pressure trace is partly an artifact; a
// FALLING one is real, because the comparison only gets harder.

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
                       worst: String, secs: Double)

  def readLog(dir: String): Vector[Row] =
    val p = s"$dir/log.tsv".asPath
    if !p.exists then usage(s"no $dir/log.tsv")
    p.lines.toVector.drop(1).filter(_.trim.nonEmpty).flatMap { l =>
      val f = l.split("\t")
      if f.length < 8 then None
      else
        for
          g <- f(0).toIntOption
          s <- f(4).toDoubleOption
          r <- f(5).toDoubleOption
          t <- f(7).toDoubleOption
        yield Row(g, f(3) == "true", s, r, f(6), t)
    }

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

    val log = readLog(dir)
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

    if arc.scores.nonEmpty then
      val worst = arc.scores.max
      val pres = series(g => g.count(r => r.feasible && r.score < worst).toDouble)
      val (p0, p1) = ends(pres)
      println(f"PRESSURE  beats  ${spark(pres, ramp)}  ${p0}%.0f -> ${p1}%.0f a block" +
              "   (early blocks read low)")

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
    // worse than no stopping rule.  Two blocks, because one block of a search this noisy proves
    // nothing.
    if arc.scores.nonEmpty && gens >= 3 * blk then
      val worst = arc.scores.max
      def pressure(lo: Int, hi: Int) = inGen(lo, hi).count(r => r.feasible && r.score < worst)
      val last  = pressure(gens - 2 * blk, gens)
      val prior = pressure(gens - 4 * blk, gens - 2 * blk)
      val since = feas.minByOption(_.raw).map(b => gens - 1 - b.gen).getOrElse(0)
      val verdict =
        if last == 0 && since > 2 * blk then
          f"CLOSED -- nothing beat the archive in ${2 * blk}%d gen and the best is $since%d old. " +
          "Run -holdout, then -prune."
        else if prior > 0 && last < prior / 2 then
          f"CLOSING -- $last%d beat the archive lately against $prior%d before. Finish, do not extend."
        else
          f"STILL TURNING OVER -- $last%d beat the archive lately against $prior%d before, " +
          f"best is $since%d gen old."
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

MarketSimSearchReport.main(args.toArray)

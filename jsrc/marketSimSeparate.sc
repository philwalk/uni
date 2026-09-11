#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using scala 3.7.0
//> using dep org.vastblue:uni_3:0.24.1

// WHICH STATISTIC WOULD RULE THESE WORLDS OUT.
//
// The archive holds worlds that agree on every statistic the gate measures and disagree with each
// other internally.  That disagreement is a map of what the record cannot currently pin down, and
// it is directly useful: a candidate statistic on which the archive SCATTERS is one that would
// separate them, so measuring it on the real record would rule most of them out.  A candidate on
// which the archive agrees adds nothing as an anchor no matter how appealing it looks.
//
// So: simulate every archive member on THE SAME SEEDS, compute a battery of candidates per path,
// and rank them by
//
//     separation = sd across world means / standard error of one world's mean
//
// A ratio near 1 means the spread between worlds is the spread you would get by re-running one
// world, which is no information.  A large ratio means the worlds genuinely differ on it.
//
// EVERY CANDIDATE IS COMPUTABLE FROM A DAILY PRICE SERIES ALONE, because a statistic that needs
// the model's internal state can never be measured on the record and so can never become an
// anchor.  No `liq`, no `fundamental`, no oracle columns.

import uni.*
import uni.apps.MarketSim
import uni.apps.MarketSim.World

object MarketSimSeparate:
  def println(s: String = ""): Unit = print(s"$s\n")
  def eprintln(s: String = ""): Unit = System.err.print(s"$s\n")

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "-in DIR       ; the search directory holding archive.tsv (default search)",
    "-paths N      ; paths per world, all on the same seeds (default 6)",
    "-years Y      ; years per path (default 80)",
    "-seed S       ; base seed (default 20260813)",
    "-out F        ; per-path readings, one row per world/path/statistic (default separate.tsv)",
  )

  def intOr(flag: String, v: String): Int =
    v.toIntOption.getOrElse(usage(s"$flag wants an integer, got [$v]"))

  // ---- the candidate battery ----------------------------------------------------------------

  def mean(x: Array[Double]): Double = if x.isEmpty then Double.NaN else x.sum / x.length

  def sd(x: Array[Double]): Double =
    if x.length < 2 then Double.NaN
    else
      val m = mean(x)
      math.sqrt(x.map(v => (v - m) * (v - m)).sum / (x.length - 1))

  def moment(x: Array[Double], k: Int): Double =
    val m = mean(x); val s = sd(x)
    if !(s > 0.0) then Double.NaN else mean(x.map(v => math.pow((v - m) / s, k.toDouble)))

  def autocorr(x: Array[Double], lag: Int): Double =
    if x.length <= lag + 2 then Double.NaN
    else
      val a = x.take(x.length - lag); val b = x.drop(lag)
      val ma = mean(a); val mb = mean(b)
      val num = a.indices.map(i => (a(i) - ma) * (b(i) - mb)).sum
      val da = math.sqrt(a.map(v => (v - ma) * (v - ma)).sum)
      val db = math.sqrt(b.map(v => (v - mb) * (v - mb)).sum)
      if da > 0.0 && db > 0.0 then num / (da * db) else Double.NaN

  /** h-period returns from a log-price series, non-overlapping. */
  def periodReturns(lp: Array[Double], h: Int): Array[Double] =
    if lp.length <= h then Array.emptyDoubleArray
    else Array.tabulate((lp.length - 1) / h)(k => lp((k + 1) * h) - lp(k * h))

  /** Var of h-period returns over h x Var of one-period, the standard variance ratio.  One horizon
    * is anchored today; the TERM STRUCTURE is not, and it is the shape a trend or vol-timing rule
    * actually reads. */
  def varianceRatio(r: Array[Double], lp: Array[Double], h: Int): Double =
    val v1 = sd(r); val vh = sd(periodReturns(lp, h))
    if v1 > 0.0 && !vh.isNaN then (vh * vh) / (h.toDouble * v1 * v1) else Double.NaN

  /** The drawdown state at each session, as a log distance below the running peak. */
  def drawdown(lp: Array[Double]): Array[Double] =
    val out = new Array[Double](lp.length)
    var peak = lp(0)
    var i = 0
    while i < lp.length do
      if lp(i) > peak then peak = lp(i)
      out(i) = lp(i) - peak
      i += 1
    out

  /** Mean sessions from first crossing `depth` below the peak to regaining that peak; NaN when no
    * episode completed.  A recovery-shape statistic no current row reads. */
  def recoverySessions(lp: Array[Double], depth: Double): Double =
    val dd = drawdown(lp)
    val spans = scala.collection.mutable.ArrayBuffer.empty[Int]
    var i = 0
    while i < dd.length do
      if dd(i) <= -depth then
        val start = i
        while i < dd.length && dd(i) < 0.0 do i += 1
        if i < dd.length then spans += (i - start)
      else i += 1
    if spans.isEmpty then Double.NaN else spans.map(_.toDouble).sum / spans.length

  /** Mean length of consecutive same-sign sessions. */
  def runLength(r: Array[Double], up: Boolean): Double =
    val runs = scala.collection.mutable.ArrayBuffer.empty[Int]
    var cur = 0
    var i = 0
    while i < r.length do
      val hit = if up then r(i) > 0.0 else r(i) < 0.0
      if hit then cur += 1
      else if cur > 0 then { runs += cur; cur = 0 }
      i += 1
    if cur > 0 then runs += cur
    if runs.isEmpty then Double.NaN else runs.map(_.toDouble).sum / runs.length

  /** Every candidate, named.  Price series only. */
  def candidates(px: Array[Double]): Vector[(String, Double)] =
    val lp = px.map(math.log)
    val r = Array.tabulate(lp.length - 1)(i => lp(i + 1) - lp(i))
    val ar = r.map(math.abs)
    val dd = drawdown(lp)
    val deep = r.indices.filter(i => dd(i) <= -0.10).toArray
    val calm = r.indices.filter(i => dd(i) > -0.10).toArray
    def sub(idx: Array[Int]) = idx.map(r)
    val inDd = sub(deep); val outDd = sub(calm)
    Vector(
      // the variance-ratio TERM STRUCTURE: 60d is anchored, the shape is not
      "vr5"          -> varianceRatio(r, lp, 5),
      "vr20"         -> varianceRatio(r, lp, 20),
      "vr60"         -> varianceRatio(r, lp, 60),
      "vr125"        -> varianceRatio(r, lp, 125),
      "vr250"        -> varianceRatio(r, lp, 250),
      // the CLUSTERING term structure past the two anchored lags
      "absAc1"       -> autocorr(ar, 1),
      "absAc5"       -> autocorr(ar, 5),
      "absAc20"      -> autocorr(ar, 20),
      "absAc60"      -> autocorr(ar, 60),
      "absAc120"     -> autocorr(ar, 120),
      "absAc250"     -> autocorr(ar, 250),
      // signed persistence
      "retAc1"       -> autocorr(r, 1),
      "retAc5"       -> autocorr(r, 5),
      "retAc20"      -> autocorr(r, 20),
      // shape at several horizons
      "skew1"        -> moment(r, 3),
      "skew5"        -> moment(periodReturns(lp, 5), 3),
      "skew21"       -> moment(periodReturns(lp, 21), 3),
      "skew63"       -> moment(periodReturns(lp, 63), 3),
      "kurt1"        -> moment(r, 4),
      "kurt5"        -> moment(periodReturns(lp, 5), 4),
      "kurt21"       -> moment(periodReturns(lp, 21), 4),
      // CONDITIONAL on the drawdown state -- what a defensive rule actually reads
      "volInDd"      -> (if inDd.length > 30 then sd(inDd) / sd(r) else Double.NaN),
      "skewInDd"     -> (if inDd.length > 250 then moment(inDd, 3) else Double.NaN),
      "kurtInDd"     -> (if inDd.length > 250 then moment(inDd, 4) else Double.NaN),
      "ddTimeShare"  -> (deep.length.toDouble / r.length),
      "volRatioDd"   -> (if inDd.length > 30 && outDd.length > 30 then sd(inDd) / sd(outDd) else Double.NaN),
      // recovery shape and run structure
      "recover10"    -> recoverySessions(lp, 0.10),
      "recover20"    -> recoverySessions(lp, 0.20),
      "runUp"        -> runLength(r, true),
      "runDown"      -> runLength(r, false),
      "runRatio"     -> (runLength(r, true) / runLength(r, false)),
    )

  def main(args: Array[String]): Unit =
    var in = "search"; var paths = 6; var years = 80; var base = 20260813L
    var out = "separate.tsv"
    eachArg(args.toSeq, usage) {
      case "-in"    => in = consumeNext
      case "-paths" => paths = intOr("-paths", consumeNext)
      case "-years" => years = intOr("-years", consumeNext)
      case "-seed"  => base = consumeNext.toLong
      case "-out"   => out = consumeNext
      case a        => usage(s"unrecognized arg [$a]")
    }

    val ap = s"$in/archive.tsv".asPath
    if !ap.exists then usage(s"no $in/archive.tsv")
    val lines = ap.lines.toVector.filter(_.trim.nonEmpty)
    val header = lines.head.split("\t").toVector
    val dialNames = header.drop(4)
    val rows = lines.drop(1).map(_.split("\t").toVector)
    println(s"${rows.length} worlds, $paths paths x ${years}y each, all on the same seeds")

    // the dial setters, by name, so a row rebuilds the world it describes
    val setters: Map[String, (World, Double) => World] =
      MarketSim.CalibrateRanges.map((nm, _, _, set) => nm -> set).toMap
    val named = ("current", MarketSim.Defaults) +:
                (MarketSim.Releases.map((v, w) => (v, w)) ++ MarketSim.Recipes.map((n, w, _) => (n, w)))
    val byName = named.toMap

    // ONE SEED SET FOR EVERY WORLD: a difference between worlds must not be a difference between
    // draws, which is the whole point of the comparison.
    val seeds = (0 until paths).toVector.map(k => base + k * 7919L)

    val readings = rows.zipWithIndex.flatMap { (row, wi) =>
      val parent = row(0)
      val w0 = byName.getOrElse(parent, MarketSim.Defaults)
      val w = dialNames.zipWithIndex.foldLeft(w0) { case (acc, (nm, i)) =>
        setters.get(nm).map(_(acc, row(4 + i).toDouble)).getOrElse(acc)
      }
      eprintln(s"  world $wi ($parent)")
      seeds.zipWithIndex.flatMap { (s, pi) =>
        val p = MarketSim.simulate(w, years, s)
        candidates(p.price).map((nm, v) => s"$wi\t$parent\t$pi\t$nm\t$v")
      }
    }
    out.asPath.writeLines("world\tparent\tpath\tstat\tvalue" +: readings)
    println(s"wrote ${readings.length} readings to $out")

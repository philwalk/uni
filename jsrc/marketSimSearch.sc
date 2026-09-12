#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using scala 3.7.0
//> using dep org.vastblue:uni_3:0.24.2

// A CALIBRATION SEARCH for the market simulator, built to run for days and to be killed at any
// moment.  NOT SHIPPED, and it adds NOTHING to the library: the simulator and its whole runtime
// (netlib, the bundled OpenBLAS, the parallel collections) come from the published jar, so this
// is a standalone script with no build step and no publishLocal in the loop.
//
//   jsrc/marketSimSearch.sc -out search
//
// It therefore searches the PUBLISHED model.  To search unreleased model changes, publish and
// bump the `using dep` above -- the same trade `jsrc/marketSim.sc` documents, and the reason the
// search's own parameter table lives HERE rather than in the library.
//
// WHAT IT PRODUCES is an ARCHIVE, not a champion.  Distinct worlds match the record equally well
// -- 28 searched dials against ~45 graded rows that are not independent -- and a strategy
// interacts with the mechanism rather than with the summary statistic, so a verdict from one
// best-fit world carries the same over-confidence that weakens a backtest.  A verdict that holds
// across every world consistent with the record does not.
//
// MEASURED FACTS IT IS BUILT ON (2026-09-10, all through the shipped CLI):
//   - 1 uniform draw in 60 is feasible, so the search is SEEDED from the frozen releases and
//     recipes rather than started cold.  16 of the 25 pass today's gate; the rest were adopted
//     against an earlier one, so membership is re-checked here rather than assumed.
//   - the feasible region is FAT LOCALLY: a Gaussian mutation of 5-10% of each dial's range keeps
//     about four children in five feasible, and breaks down by 20%.  So plain rejection handles
//     the constraint, and no constraint-handling library is earned.
//   - the objective's seed noise is a smooth sd of 0.074 PLUS a 0.5 quantum whenever a row near
//     its band edge flips, which happens on roughly one seed in six.  One seed cannot decide
//     feasibility, so every `-reps` seed must pass.
//   - one evaluation costs ~13s at the frozen ensemble, which is why this checkpoints.
//
// THE OBJECTIVE IS LEXICOGRAPHIC and fixes no target value.  Feasibility first, as a hard gate
// never priced into a scalar, so a failed row cannot be bought with a gain elsewhere.  Then the
// WORST row's distance from the record, with a DEAD ZONE at one anchor sd: inside the record's own
// sampling error there is no gradient to climb, which is what stops a long search optimising
// noise.  Minimising the worst row rather than a sum is what stops one row being sacrificed.

import uni.*
import uni.apps.MarketSim
import uni.apps.MarketSim.World
import uni.data.NumPyRNG

object MarketSimSearch:
  def println(s: String = ""): Unit = print(s"$s\n")
  def eprintln(s: String = ""): Unit = System.err.print(s"$s\n")

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "-out DIR      ; where the archive, log and checkpoint live (default ./search)",
    "-anchors A    ; sp500 or nasdaq (default sp500)",
    "-seeds S      ; comma-separated release/recipe names to seed from; default every one that",
    "              ;   passes today's gate.  Non-searched dials (the channels, the macro panel)",
    "              ;   come from the seed, so seeding from a recipe searches THAT configuration",
    "-paths N      ; ensemble paths per evaluation (default 60)",
    "-years Y      ; years per path (default 80)",
    "-reps K       ; seeds a candidate must pass feasibility on, all of them (default 2)",
    "-sigma S      ; mutation sd as a fraction of each dial's range (default 0.07; 0.20 breaks)",
    "-keep N       ; archive size cap (default 40)",
    "-sep D        ; minimum separation between members in normalised dial space (default 0.12)",
    "-pop P        ; candidates per generation, checkpointed after each (default 8)",
    "-gens G       ; generations to run; 0 runs until killed (default 0)",
    "-dead D       ; dead zone in anchor sds; a row inside it scores 0 (default 0.5).  At 1.0 a",
    "              ;   calibrated world scores 0 on every row and the objective goes flat",
    "-seed S       ; base seed (default 20260813)",
    "-holdout K    ; re-score every archive member on K seeds the search never selected on, at",
    "              ;   the recorded ensemble, and exit.  A member that passes on the training",
    "              ;   stream and fails here was fitted to its own test rather than to the record",
    "-prune        ; keep only the members that passed BOTH arms of the last -holdout, moving",
    "              ;   the rest to dropped.tsv rather than deleting them, and exit",
    "-force        ; resume even though the recorded settings differ from the flags given",
    "-export F     ; write the archive as a worlds JSON and exit",
  )

  def intOr(flag: String, v: String): Int =
    v.toIntOption.getOrElse(usage(s"$flag wants an integer, got [$v]"))

  def numOr(flag: String, v: String): Double =
    v.toDoubleOption.getOrElse(usage(s"$flag wants a number, got [$v]"))

  /** One archive member: which seed world carries the non-searched dials, the searched dials
    * themselves, and the reading that admitted it. */
  final case class Member(name: String, dials: Vector[Double], score: Double, raw: Double,
                          worst: String, desc: Vector[Double])

  /** THE SEARCH SPACE, owned by the tool rather than by the library.  It mirrors the jar's
    * `CalibrateRanges` -- `check` below refuses to start if the dial NAMES have drifted apart --
    * but it carries its own BOUNDS, for two reasons.  The search is not part of the model, so a
    * change here must not require a publish.  And the published bounds excluded shipped worlds:
    * `margin` has been 0.006 since 0.19.1 against a jar ceiling of 0.004, `depth` 8.4 in the
    * 0.24.0 Nasdaq recipes against a floor of 10.0, `jumpRate` 0.005 in 0.22.x and `volOfVol`
    * 0.011 in 0.19.x outside theirs.  Clamping a seed to a box that excludes it would move every
    * world off its own value before the search began. */
  val ranges: Vector[(String, Double, Double, (World, Double) => World, World => Double)] = Vector(
    ("depth",            8.0,  26.0,  (w, x) => w.copy(depth = x),           _.depth),
    ("trendShare",       0.05,  0.70, (w, x) => w.copy(trendShare = x),      _.trendShare),
    ("drift",            0.06,  0.16, (w, x) => w.copy(drift = x),           _.drift),
    ("fundVol",          0.03,  0.16, (w, x) => w.copy(fundVol = x),         _.fundVol),
    ("crowdImpact",      0.01,  0.20, (w, x) => w.copy(crowdImpact = x),     _.crowdImpact),
    ("stress",           2.0,   6.0,  (w, x) => w.copy(stress = x),          _.stress),
    ("valuePull",        0.010, 0.070,(w, x) => w.copy(valuePull = x),       _.valuePull),
    ("recoveryDrag",     0.0,  20.0,  (w, x) => w.copy(recoveryDrag = x),    _.recoveryDrag),
    ("recoveryFloor",    0.05,  1.0,  (w, x) => w.copy(recoveryFloor = x),   _.recoveryFloor),
    ("disasterRate",     0.0,   1.5,  (w, x) => w.copy(disasterRate = x),    _.disasterRate),
    ("disasterSize",     0.5,   2.5,  (w, x) => w.copy(disasterSize = x),    _.disasterSize),
    ("disasterRecover",  0.0,   0.9,  (w, x) => w.copy(disasterRecover = x), _.disasterRecover),
    ("beliefShare",      0.0,   0.97, (w, x) => w.copy(beliefShare = x),     _.beliefShare),
    ("capYears",         0.0,   4.0,  (w, x) => w.copy(capYears = x),        _.capYears),
    ("volOfVol",         0.010, 0.030,(w, x) => w.copy(volOfVol = x),        _.volOfVol),
    ("jumpVar",          0.0,   0.20, (w, x) => w.copy(jumpVar = x),         _.jumpVar),
    ("jumpRate",         0.0,   0.006,(w, x) => w.copy(jumpRate = x),        _.jumpRate),
    ("leverage",         0.0,   0.15, (w, x) => w.copy(leverage = x),        _.leverage),
    ("downShock",        0.0,   0.05, (w, x) => w.copy(downShock = x),       _.downShock),
    ("jumpSkew",         0.0,   1.40, (w, x) => w.copy(jumpSkew = x),        _.jumpSkew),
    ("newsRate",         0.0,   3.00, (w, x) => w.copy(newsRate = x),        _.newsRate),
    ("newsSize",         0.0,   0.05, (w, x) => w.copy(newsSize = x),        _.newsSize),
    ("refugeDays",       0.0,   3.00, (w, x) => w.copy(refugeDays = x),      _.refugeDays),
    ("easing",           0.0,   0.09, (w, x) => w.copy(easing = x),          _.easing),
    ("refuge",           0.0,   0.20, (w, x) => w.copy(refuge = x),          _.refuge),
    ("inflSize",         0.03,  0.12, (w, x) => w.copy(inflSize = x),        _.inflSize),
    ("discount",         3.0,  10.0,  (w, x) => w.copy(discount = x),        _.discount),
    ("margin",           0.0,   0.008,(w, x) => w.copy(margin = x),          _.margin),
  )
  val names: Vector[String] = ranges.map(_._1)

  /** Refuse to start on a stale table.  The jar's dial list is the model's statement of what is
    * searchable; if a release adds or drops one, this tool must be updated rather than quietly
    * search the wrong space. */
  def checkAgainstJar(): Unit =
    val jar = MarketSim.CalibrateRanges.map(_._1)
    if jar.toSet != names.toSet then
      usage(s"the jar searches [${jar.mkString(", ")}] and this tool searches " +
            s"[${names.mkString(", ")}] -- update the table in this file")

  /** The dead zone in the units `fitness` scores rows in.  `wgt` makes one anchor sd worth
    * `SdRelRef` there, so `-dead 1.0` means "inside the record's own sampling error, no gradient".
    * A dial rather than a constant because at 1.0 a calibrated world scores 0 on every row: the
    * objective goes flat and the search random-walks.  The raw worst is reported beside the
    * scored one so the width is chosen from what the rows actually read. */
  def deadZone(sds: Double): Double = sds * MarketSim.SdRelRef

  def dialsOf(w: World): Vector[Double] = ranges.map((_, _, _, _, get) => get(w))

  def worldOf(base: World, d: Vector[Double]): World =
    ranges.zip(d).foldLeft(base) { case (w, ((_, _, _, set, _), x)) => set(w, x) }

  /** Distance in normalised dial space, max over dials -- the same reading the seed pool's spread
    * was measured with, so `-sep` is comparable to it. */
  def apart(a: Vector[Double], b: Vector[Double]): Double =
    ranges.indices.map(i => math.abs(a(i) - b(i)) / (ranges(i)._3 - ranges(i)._2)).max

  def clamped(i: Int, x: Double): Double =
    math.max(ranges(i)._2, math.min(ranges(i)._3, x))

  /** WHAT A WORLD DOES, recorded beside what it is set to.  Never graded, never optimised: the
    * archive keeps worlds apart by DIAL distance, which is a proxy for behavioural difference
    * rather than a measurement of it, so without these the archive's spread is assumed.  Two
    * members far apart in dials can produce markets a strategy cannot tell apart, and the reverse
    * is the case worth keeping both of.
    *
    * `retAc1` leads because it is what the record cannot pin down: a variance ratio constrains a
    * weighted SUM of the first q-1 autocorrelations and the clustering rows read |r|, so signed
    * lag-1 is unconstrained by anything in the target set, and across a 32-world archive it spread
    * further than any other candidate statistic measured.  Finding that took a separate study
    * after the run; as a column it would have been a sort.
    *
    * MIRRORED IN THE RUST HARNESS, whose `DESC_NAMES` must match: the archive is a format the two
    * share, and `-holdout` in either re-scores the other's file. */
  val descNames: Vector[String] =
    Vector("retAc1", "vr250", "clus20", "kurt", "epPerPath", "depthMed")

  def descOf(st: MarketSim.WorldStats): Vector[Double] =
    Vector(st.retAc1, st.vr250, st.ac20, st.kurt, st.epPerPath, st.depthMed)

  /** Feasible on every rep, and the worst row's distance past the dead zone.  The reading is the
    * WORST across reps, never the mean: a world that passes only on a lucky seed has not passed.
    * The DESCRIPTORS are the mean instead -- they describe the world, where `raw` deliberately
    * describes its worst seed. */
  final case class Read(feasible: Boolean, score: Double, raw: Double, worst: String,
                        desc: Vector[Double],
                        /** How far this world's reading MOVED across its own repetitions -- max
                          * minus min of `raw`.  Free to compute and it is the noise scale the
                          * archive needs: a score difference smaller than this is a seed draw,
                          * not a better world.  Zero at `-reps 1`, which is honest. */
                        spread: Double = 0.0,
                        /** WHICH GATE ROWS FAILED, empty when feasible.  Recorded because the
                          * log's `worstRow` is a FITNESS row while feasibility is decided by the
                          * GATE -- two different sets -- so a rejected candidate's line said
                          * nothing about why it was rejected. */
                        gateFail: Vector[String] = Vector.empty)

  /** One seed's reading.  THREE THINGS IT DOES NOT DO, each worth more than anything else here,
    * and each mirrored in the Rust harness.
    *
    * It does not simulate twice when it does not have to.  The extreme row is read at its anchor's
    * own horizon -- 100 years for the S&P set -- so a run at `-years 100` was asking for the same
    * paths from the same seed a second time, and that second ensemble is the larger half of an
    * evaluation.  At the extreme horizon the main ensemble IS that ensemble.
    *
    * It does not pay for the second ensemble at all once the gate has failed.  Feasibility comes
    * from `gateChecks`, which reads only the pooled statistics, so a candidate that fails is
    * rejected whatever its score.  The rows that need the second ensemble are then dropped from
    * the worst-row search rather than scored as unmeasurable, so a rejected candidate's `worstRow`
    * still names something that was actually measured.
    *
    * And `evaluate` stops at the first seed that fails, because feasibility needs every seed. */
  def oneRead(w: World, anchors: MarketSim.Anchors, paths: Int, years: Int,
              s: Long, dead: Double): Read =
    val main = MarketSim.simPaths(w, paths, years, s)
    val st   = MarketSim.measure(main, years)
    val bad  = MarketSim.gateChecks(anchors, st)
      .count((_, ok, cls) => !ok && MarketSim.GateDefault.contains(cls))
    val feasible = bad == 0
    val ex =
      if !feasible then Map.empty[String, Double]
      else if MarketSim.extremeHorizons(anchors) == Vector(years) then
        MarketSim.extremeScoreStatsFrom(anchors, main, years)
      else MarketSim.extremeScoreStats(anchors, paths, s, w)
    val rows = MarketSim.fitness(anchors, st, ex)._2
      .filter((nm, _, _, _) => feasible || !MarketSim.extremeTargetNames.contains(nm))
    val raw = rows.map((nm, _, _, term) => (term, nm)).max
    val gateFail =
      if feasible then Vector.empty
      else MarketSim.GateDefault.toVector.flatMap(cls => MarketSim.failedIn(anchors, st, cls))
    Read(feasible, math.max(0.0, raw._1 - dead), raw._1, raw._2, descOf(st), gateFail = gateFail)

  def evaluate(w: World, anchors: MarketSim.Anchors, paths: Int, years: Int,
               seeds: Vector[Long], dead: Double): Read =
    // takeWhile-with-the-failure: every seed up to and including the first infeasible one
    val reads = seeds.foldLeft(Vector.empty[Read]) { (acc, s) =>
      if acc.nonEmpty && !acc.last.feasible then acc
      else acc :+ oneRead(w, anchors, paths, years, s, dead)
    }
    val hardest = reads.maxBy(_.raw)
    val desc = descNames.indices.toVector.map(i => reads.map(_.desc(i)).sum / reads.length)
    val spread = reads.map(_.raw).max - reads.map(_.raw).min
    Read(reads.forall(_.feasible), hardest.score, hardest.raw, hardest.worst, desc, spread,
         hardest.gateFail)

  // ---- checkpoint ---------------------------------------------------------------------------
  // Plain TSV, one member per row, dial columns in `CalibrateRanges` order.  A days-long run that
  // cannot be killed and resumed is a run nobody will start.

  /** THE SETTINGS ARE PART OF THE CHECKPOINT.  An archive says which worlds were kept; without
    * the ensemble and the dials that judged them it does not say what "kept" meant, cannot be
    * reproduced, and silently mixes standards if a resume changes a flag.  Key/value so a reader
    * and a later version can both cope with a row they do not recognise. */
  def writeArchive(dir: String, arc: Vector[Member], gen: Int, evals: Int,
                   noise: (Double, Int),
                   cfg: Vector[(String, String)]): Unit =
    val header = (Vector("name", "score", "raw", "worstRow") ++ names ++ descNames).mkString("\t")
    val rows = arc.map(m => (Vector(m.name, f"${m.score}%.6f", f"${m.raw}%.6f", m.worst) ++
                             m.dials.map(x => f"$x%.8g") ++
                             m.desc.map(x => f"$x%.8g")).mkString("\t"))
    s"$dir/archive.tsv".asPath.writeLines(header +: rows)
    // THE SEED-NOISE ACCUMULATOR IS RESUMABLE STATE, not a setting, so it is written here and
    // not compared by the resume guard.  It is the threshold `admit` replaces on: a resume
    // that rebuilt it from one reading would admit on differences inside the noise for the
    // first generations back -- the same failure as resetting it per generation, one process
    // boundary out.  `%.8g` so both twins write and read one text.
    val body = (("gen", gen.toString) +: ("evals", evals.toString) +:
                ("noiseSum", f"${noise._1}%.8g") +: ("noiseN", noise._2.toString) +: cfg)
                 .map((k, v) => s"$k\t$v")
    s"$dir/state.tsv".asPath.writeLines("key\tvalue" +: body)

  def readState(dir: String): Map[String, String] =
    val sp = s"$dir/state.tsv".asPath
    if !sp.exists then Map.empty
    else
      val ls = sp.lines.toVector.filter(_.trim.nonEmpty)
      // the first format was a two-column `gen\tevals` header with one row under it; read it so
      // an archive written before the settings landed still resumes
      if ls.headOption.exists(_.startsWith("gen\t")) then
        val v = ls.drop(1).headOption.getOrElse("0\t0").split("\t")
        Map("gen" -> v(0), "evals" -> v(1))
      else
        ls.drop(1).map(_.split("\t", 2)).collect { case Array(k, v) => k -> v }.toMap

  def readArchive(dir: String): Vector[Member] =
    val ap = s"$dir/archive.tsv".asPath
    if !ap.exists then Vector.empty
    else ap.lines.toVector.drop(1).filter(_.trim.nonEmpty).map { l =>
      val f = l.split("\t").toVector
      // exactly the dials, then whatever descriptors are present: an archive written before
      // they landed simply has none
      Member(f(0), f.slice(4, 4 + ranges.length).map(_.toDouble), f(1).toDouble, f(2).toDouble,
             f(3), f.slice(4 + ranges.length, 4 + ranges.length + descNames.length).map(_.toDouble))
    }

  /** Append, never rewrite: the log of a multi-day run outlives any one process, and `\n`
    * explicitly because a PrintWriter's println would emit CRLF here. */
  def appendLog(dir: String, lines: Seq[String]): Unit =
    s"$dir/log.tsv".asPath.withWriter("UTF-8", append = true) { w =>
      lines.foreach(l => w.print(s"$l\n"))
    }

  /** The archive as a worlds JSON in the sidecar's own key format -- what a consumer runs a
    * strategy across. */
  def exportWorlds(dir: String, file: String, seedFor: String => World): Unit =
    val arc = readArchive(dir)
    if arc.isEmpty then usage(s"no archive in $dir to export")
    val bodies = arc.zipWithIndex.map { (m, k) =>
      val w = worldOf(seedFor(m.name), m.dials)
      s"""  {\n    "member": $k,\n    "seededFrom": "${m.name}",\n""" +
      f"""    "score": ${m.score}%.6f,\n    "worstRow": "${m.worst}",\n    "world": {\n""" +
      // AT THE ARCHIVE'S OWN WIDTH, not the report's: this block is what a consumer
      // reconstructs a world from, and re-rendering already-truncated dials at six decimals
      // dropped two to three significant digits from 87% of them.
      // `,\n`, as the sidecar joins it: `worldJsonBody` returns the fields WITHOUT
      // separators, and joining them on a bare newline wrote an archive no JSON parser
      // accepts.  Both twins did it, identically, which is how parity missed it.
      MarketSim.worldJsonBody(w, x => f"$x%.8g").mkString(",\n") + "\n    }\n  }"
    }
    file.asPath.writeLines(Seq("[", bodies.mkString(",\n"), "]"))
    println(s"wrote ${arc.length} worlds to $file")

  /** The member whose removal costs the least behavioural spread: of the closest pair in
    * normalised descriptor space, the one that scores worse.  `None` when no member carries
    * descriptors, which is only an archive written before they existed. */
  def leastDistinct(arc: Vector[Member]): Option[Int] =
    val k = arc.map(_.desc.length).maxOption.getOrElse(0)
    if arc.length < 2 || k == 0 then None
    else
      // each descriptor's range across the archive, so none dominates on units alone
      val span = (0 until k).toVector.map { j =>
        val xs = arc.flatMap(_.desc.lift(j)).filter(_.isFinite)
        if xs.isEmpty then 0.0 else xs.max - xs.min
      }
      def dist(a: Member, b: Member): Double =
        val terms = (0 until k).toVector.flatMap { j =>
          if span(j) <= 0.0 then None
          else (a.desc.lift(j), b.desc.lift(j)) match
            case (Some(x), Some(y)) if x.isFinite && y.isFinite =>
              Some(math.pow((x - y) / span(j), 2))
            case _ => None
        }
        math.sqrt(terms.sum)
      val pairs = for i <- arc.indices; j <- (i + 1) until arc.length yield (dist(arc(i), arc(j)), i, j)
      val (_, i, j) = pairs.minBy(_._1)
      Some(if arc(i).score > arc(j).score then i else j)

  /** Admit a feasible candidate.
    *
    * TWO RULES, BOTH FIXED 2026-09-12 after a 4317-generation run demonstrated what the old ones
    * do.
    *
    * REPLACING NEEDS A REAL MARGIN.  A candidate takes the place of the member nearest it in dial
    * space only if it beats that member by more than a seed draw moves a reading.  Without that
    * margin the old rule replaced on any improvement whatever, and after thousands of generations
    * the archive's whole score range was 0.018 against a seed-noise sd of 0.0441 -- every member
    * statistically indistinguishable from every other, and the search still sorting them.  The
    * dead zone does not help: it discounts each ROW's distance from the record and says nothing
    * about comparing two worlds' totals.
    *
    * TRIMMING DROPS THE LEAST DISTINCT, not the worst-scoring.  Sorting by score and truncating is
    * an optimiser, and it showed: over that run the archive's signed lag-1 range fell from 0.057
    * to 0.032 while the operator's own output narrowed the same way.  FEASIBILITY is the
    * membership test and score was never meant to be a second one, so when the archive overflows
    * the member to lose is the one whose removal costs the least behavioural ground.
    *
    * Measured A/B at identical settings and seed over 40 generations: signed lag-1 range 0.043 ->
    * 0.074, variance ratio 250 0.59 -> 1.79, clustering lag 20 0.026 -> 0.068, crashes per path
    * 2.1 -> 7.9.  The price is a wider SCORE range, since members further from the record are now
    * kept when they are behaviourally distinct -- they pass the gate, which is the claim the
    * archive makes about them. */
  /** Returns the archive AND WHETHER THE CANDIDATE ENTERED IT.  A reader cannot recover that
    * from the archive -- a replacement leaves the member count unchanged -- and without it
    * admission pressure can only be proxied by "scores better than the worst member", which
    * SATURATES once spread-keeping starts admitting a poor scorer for its behaviour: on a
    * 471-generation run the worst member scored 0.906 against a best of 0.070, so the proxy
    * counted every feasible candidate and its verdict read "still turning over" throughout. */
  def admit(arc: Vector[Member], m: Member, sep: Double, keep: Int,
            noise: Double): (Vector[Member], Boolean) =
    val near = arc.indexWhere(o => apart(o.dials, m.dials) < sep)
    val (grown, took) =
      if near < 0 then (arc :+ m, true)
      else if m.score < arc(near).score - noise then (arc.updated(near, m), true)
      else (arc, false)
    @annotation.tailrec
    def trim(v: Vector[Member]): Vector[Member] =
      if v.length <= keep then v
      else leastDistinct(v) match
        case Some(i) => trim(v.patch(i, Nil, 1))
        case None    => v.sortBy(_.score).take(keep)   // no descriptors: the old rule
    (trim(grown), took)

  def main(args: Array[String]): Unit =
    var out = "search"; var anchorSpec = "sp500"; var seedSpec = ""
    var paths = 60; var years = 80; var reps = 2; var sigma = 0.07
    var keep = 40; var sep = 0.12; var pop = 8; var gens = 0
    var base = 20260813L; var exportTo = ""; var dead = 0.5
    var holdout = 0; var force = false; var prune = false
    eachArg(args.toSeq, usage) {
      case "-out"     => out = consumeNext
      case "-anchors" => anchorSpec = consumeNext
      case "-seeds"   => seedSpec = consumeNext
      case "-paths"   => paths = intOr("-paths", consumeNext)
      case "-years"   => years = intOr("-years", consumeNext)
      case "-reps"    => reps = intOr("-reps", consumeNext)
      case "-sigma"   => sigma = numOr("-sigma", consumeNext)
      case "-keep"    => keep = intOr("-keep", consumeNext)
      case "-sep"     => sep = numOr("-sep", consumeNext)
      case "-pop"     => pop = intOr("-pop", consumeNext)
      case "-gens"    => gens = intOr("-gens", consumeNext)
      case "-dead"    => dead = numOr("-dead", consumeNext)
      case "-seed"    => base = consumeNext.toLong
      case "-holdout" => holdout = intOr("-holdout", consumeNext)
      case "-prune"   => prune = true
      case "-force"   => force = true
      case "-export"  => exportTo = consumeNext
      case a          => usage(s"unrecognized arg [$a]")
    }
    if reps < 1 then usage("-reps wants at least 1")
    if sigma <= 0.0 then usage("-sigma wants a positive fraction")
    if pop < 1 then usage("-pop wants at least 1")
    checkAgainstJar()
    val _ = out.asPath.mkdirs

    val anchors = MarketSim.anchorsNamed(anchorSpec)
    // `Releases` stops at the last FROZEN row, so the jar's own current default -- the newest
    // and most complete world, and the one a search is usually about -- is not in it.  Added
    // first so it is always a seed.
    //
    // A RECIPE CARRIES THE ANCHOR SET IT WAS VERIFIED AGAINST, and a seed graded against the
    // wrong ruler is worse than no seed: the Nasdaq recipes read `equity vol %` 0.5 past the dead
    // zone against S&P anchors and would drag an S&P archive toward a target they were never
    // built for.  So the pool is restricted to seeds whose anchor set is the one being searched,
    // which also means an S&P archive and a Nasdaq archive are separate products, as they should
    // be.
    val named = ("current", MarketSim.Defaults) +:
                (MarketSim.Releases.map((v, w) => (v, w)) ++ MarketSim.Recipes.map((n, w, _) => (n, w)))
    val all = named.filter { (n, _) =>
      val spec = MarketSim.namedWorld(n).flatMap(_._2).getOrElse("sp500")
      spec == anchorSpec
    }
    if all.isEmpty then usage(s"no frozen world is anchored to [$anchorSpec]")
    val pool =
      if seedSpec.isEmpty then all
      else
        val want = seedSpec.split(",").map(_.trim).toVector
        val got = all.filter((n, _) => want.contains(n))
        if got.length != want.length then
          usage(s"-seeds names [${want.filterNot(n => got.exists(_._1 == n)).mkString(", ")}], " +
                "which is not a release or recipe")
        got
    val seedWorld = pool.toMap
    def worldFor(n: String): World = seedWorld.getOrElse(n, MarketSim.Defaults)

    val settings = Vector("paths" -> paths.toString, "years" -> years.toString,
                          "reps" -> reps.toString, "sigma" -> f"$sigma%.4f",
                          "dead" -> f"$dead%.4f", "keep" -> keep.toString,
                          "sep" -> f"$sep%.4f", "pop" -> pop.toString,
                          "anchors" -> anchorSpec, "seed" -> base.toString,
                          "seeds" -> (if seedSpec.isEmpty then "(all)" else seedSpec),
                          // ALWAYS "(none)" here: the Rust harness can judge a candidate on the
                          // worse of two anchor sets and this one cannot, so recording the key is
                          // what makes the difference a REFUSAL rather than a silent half-score.
                          // Re-scoring a transport archive here would read the primary arm only
                          // and call members feasible that were never judged that way.
                          // the ADMISSION RULES are recorded: a resume under different ones puts
                          // two standards in one archive, the failure the ensemble settings guard
                          "admit" -> "spread-keeping",
                          "transport" -> "(none)")
    val prior = readState(out)
    val loaded = readArchive(out)
    val gen0 = prior.getOrElse("gen", "0").toInt
    val evals0 = prior.getOrElse("evals", "0").toInt
    val nsum0 = prior.getOrElse("noiseSum", "0").toDouble
    val nn0 = prior.getOrElse("noiseN", "0").toInt
    // A resume that changes how a candidate is judged puts two standards in one archive and says
    // nothing about it.  Refuse, unless told the change is deliberate.
    if loaded.nonEmpty then
      val drift = settings.filter((k, v) => prior.get(k).exists(_ != v))
      val missing = settings.map(_._1).filterNot(prior.contains)
      if missing.nonEmpty then
        eprintln(s"warning: $out was written before the settings were recorded; " +
                 s"adopting the flags given for [${missing.mkString(", ")}]")
      if drift.nonEmpty && !force then
        usage(s"$out was built with " +
              drift.map((k, _) => s"$k=${prior(k)}").mkString(", ") +
              s"; this run says " + drift.map((k, v) => s"$k=$v").mkString(", ") +
              ". Re-run with those, use a different -out, or pass -force to mix them.")
    val startArc =
      if loaded.nonEmpty then
        println(s"resumed: ${loaded.length} members, generation $gen0, $evals0 evaluations")
        loaded
      else
        // Membership is re-checked, never inherited: the older releases were adopted against an
        // earlier gate and several no longer pass the rows the model has since grown.
        println(s"seeding from ${pool.length} frozen worlds at $anchorSpec, $paths x ${years}y " +
                s"x $reps reps")
        val seeded = pool.flatMap { (nm, w) =>
          val r = evaluate(w, anchors, paths, years,
                           (0 until reps).toVector.map(k => base + k * 1000003L), deadZone(dead))
          println(f"  $nm%-24s ${if r.feasible then "feasible" else "REJECTED"}%-9s " +
                  f"score ${r.score}%7.3f  raw ${r.raw}%7.3f  ${r.worst}")
          if r.feasible then Some(Member(nm, dialsOf(w), r.score, r.raw, r.worst, r.desc))
          else None
        }
        if seeded.isEmpty then usage("no seed world passes today's gate; nothing to search from")
        seeded

    if exportTo.nonEmpty then
      exportWorlds(out, exportTo, worldFor)
      sys.exit(0)

    // PRUNE to what the holdout kept.  `holdout.tsv` is POSITIONAL against `archive.tsv` -- both
    // are written by this tool and holdout mode never touches the archive -- so the row count
    // having changed means the two no longer describe the same set, and matching them up would be
    // a guess.  The dropped members are moved rather than deleted: a member that fails today's
    // ensemble is evidence about the ensemble as much as about the member.
    if prune then
      val hp = s"$out/holdout.tsv".asPath
      if !hp.exists then usage(s"no $out/holdout.tsv; run -holdout first")
      if loaded.isEmpty then usage(s"no archive in $out to prune")
      val hr = hp.lines.toVector.drop(1).filter(_.trim.nonEmpty).map(_.split("\t").toVector)
      if hr.length != loaded.length then
        usage(s"$out/holdout.tsv has ${hr.length} rows against the archive's ${loaded.length}: " +
              "re-run -holdout, the two no longer describe the same set")
      val keptBoth = loaded.zip(hr).filter((_, r) => r(1) == "true" && r(3) == "true")
      val gone = loaded.zip(hr).filterNot((_, r) => r(1) == "true" && r(3) == "true")
      val header =
        (Vector("name", "score", "raw", "worstRow") ++ names ++ descNames).mkString("\t")
      s"$out/dropped.tsv".asPath.writeLines(
        (header + "\tpassA\tpassB\trawB\tworstB") +:
        gone.map((m, r) => (Vector(m.name, f"${m.score}%.6f", f"${m.raw}%.6f", m.worst) ++
                            m.dials.map(x => f"$x%.8g") ++ m.desc.map(x => f"$x%.8g") ++
                            Vector(r(1), r(3), r(4), r(5))).mkString("\t")))
      writeArchive(out, keptBoth.map(_._1), gen0, evals0, (nsum0, nn0), settings)
      println(s"archive pruned to ${keptBoth.length}; ${gone.length} moved to $out/dropped.tsv")
      sys.exit(0)

    // THE HOLDOUT.  Every member earned its place on seeds the search itself chose, and a world
    // sitting near a band edge flips on roughly one draw in six, so some members are in on a lucky
    // pair.  Re-score each one TWICE at the same ensemble, on TWO INDEPENDENT STREAMS.
    //
    // NEITHER STREAM SELECTED THE MUTATIONS.  The search draws a candidate's seeds from
    // `base + (evals + j) * 7919`; stream A here is `base + k * 1000003`, which is what the initial
    // POOL was judged on, and stream B is that shifted by 991.  So for the frozen worlds stream A
    // is the one they entered on, and for everything the search produced -- nearly the whole
    // archive -- both streams are new.
    //
    // That makes this a SEED-SENSITIVITY test rather than a train-versus-test split, and it is
    // still the test worth running: a member that passes one stream and fails the other was
    // admitted by a draw, not by the record.  The columns are named for what they are, because the
    // old names said train and test and someone would eventually build an argument on that.
    if holdout > 0 then
      if loaded.isEmpty then usage(s"no archive in $out to re-score")
      val train = (0 until holdout).toVector.map(k => base + k * 1000003L)
      val fresh = (0 until holdout).toVector.map(k => base + 991L + k * 1000003L)
      println(s"re-scoring ${loaded.length} members on two independent streams of $holdout " +
              s"and $holdout seeds at $paths x ${years}y, $anchorSpec; neither selected the " +
              "mutations")
      val out2 = loaded.map { m =>
        val w = worldOf(worldFor(m.name), m.dials)
        val a = evaluate(w, anchors, paths, years, train, deadZone(dead))
        val b = evaluate(w, anchors, paths, years, fresh, deadZone(dead))
        println(f"  ${m.name}%-22s stream A ${if a.feasible then "pass" else "FAIL"}%-4s " +
                f"raw ${a.raw}%6.3f   stream B ${if b.feasible then "pass" else "FAIL"}%-4s " +
                f"raw ${b.raw}%6.3f   ${b.worst}")
        (m, a, b)
      }
      // ---- THE NULL CONTROL ----------------------------------------------------------------
      // Every member against THE WORLD IT WAS SEEDED FROM, on the same fresh stream.
      //
      // The seed worlds are hand-tuned, some over many releases, against these same anchors.  A
      // days-long automated search against the same objective should land where that work already
      // is.  Three outcomes, meaning different things:
      //
      //   nothing beats its seed             the objective is at its limit, and the archive's
      //                                      worth is its SPREAD rather than a better world
      //   something beats it, and it holds   the hand-tuning left something on the table
      //   something beats it, and it does not   THE OBJECTIVE HAS A HOLE, and a search running for
      //                                      days will find any hole its scoring function has
      //
      // The third is what this exists to catch, and it is the one nobody goes looking for.
      //
      // AGAINST ITS OWN SEED, not a single global default, because the gate is not the same for
      // every lineage: channel rows only fire when that channel is on, so a basket world faces
      // rows a plain one never does.  Comparing across lineages compares scores earned under
      // different standards.  Worlds that share their SEARCHED dials read alike here whatever
      // their channels, because no channel row is in the loss.
      //
      // THE THRESHOLD IS MEASURED, not assumed, and POOLED ACROSS LINEAGES.  Each seed world is
      // read on each fresh seed on its own; the deviations from each world's own mean are pooled
      // and their spread is the noise scale.
      //
      // Pooled, because the noise belongs to the OBJECTIVE and the ensemble, not to a world.  A
      // per-world range over three draws is a terrible estimator and behaves accordingly: on the
      // first real archive it gave 0.0049 for one lineage and 0.1102 for another, a 22-fold
      // difference in how hard it was to raise a flag, and flagged three members whose margins were
      // 0.006 to 0.017 -- all far inside the objective's own seed noise.
      //
      // AND THE CONTROL STATES ITS OWN RESOLUTION, because a control that cannot see a difference
      // must not be read as evidence there is none.  Seed noise here is large: Phase 0 measured a
      // smooth sd of 0.074 plus a half-point jump whenever a row near its band edge flips, on about
      // one seed in six.  Detecting a difference of 0.05 therefore needs of order a dozen holdout
      // seeds, not three.
      println("\nNULL CONTROL   each member against the world it was seeded from")
      if holdout < 3 then
        println(s"   -holdout $holdout gives $holdout readings a seed world, so the spread below " +
                "is thin; 3 or more makes it mean something")
      // pass one: read every seed world on every fresh seed, and pool the deviations
      val seedReads = loaded.map(_.name).distinct.sorted.map { nm =>
        val w = worldFor(nm)
        (nm, fresh.map(sd => evaluate(w, anchors, paths, years, Vector(sd), deadZone(dead)).raw))
      }
      val devs = seedReads.flatMap { (_, per) =>
        val m = per.sum / per.length
        per.map(_ - m)
      }
      val dof = math.max(1.0, devs.length.toDouble - seedReads.length)
      val sd  = math.sqrt(devs.map(d => d * d).sum / dof)
      val threshold = 2.0 * sd
      println(f"   seed noise sd ${sd}%.4f pooled over ${devs.length}%d readings; " +
              f"a member must beat its seed by ${threshold}%.4f")
      println(f"   RESOLUTION: differences under ${threshold}%.4f are invisible here, " +
              "whatever their sign")
      println("   seed world              its raw   members beating it by more")
      val flagged = seedReads.flatMap { (nm, per) =>
        val hi   = per.max
        val mine = out2.filter((m, _, _) => m.name == nm)
        val beat = mine.filter((_, _, b) => b.feasible && hi - b.raw > threshold)
        println(f"   $nm%-22s ${hi}%7.4f   ${beat.length}%4d of ${mine.length}%-4d")
        beat.map((m, _, b) => (m.name, b.worst, hi - b.raw))
      }
      if flagged.isEmpty then
        println("   PASSES: no member beats its seed by more than this control can resolve.")
        println("   The archive's value is its spread, which is what it was built for.")
      else
        println(s"   ${flagged.length} members beat their seed by more than its own spread. " +
                "Before believing it,")
        println("   look at WHICH ROW moved -- the objective minimises the WORST row, so a world can")
        println("   degrade every other row freely while that one improves -- and at the descriptor")
        println("   columns, which carry the statistics nothing grades:")
        for (nm, worst, by) <- flagged.take(8) do
          println(f"     $nm%-22s better by ${by}%6.4f, now worst on $worst")
      println()

      val kept = out2.count((_, a, b) => a.feasible && b.feasible)
      val lucky = out2.count((_, a, b) => a.feasible && !b.feasible)
      val both = out2.count((_, a, b) => !a.feasible && !b.feasible)
      s"$out/holdout.tsv".asPath.writeLines(
        "name\tpassA\trawA\tpassB\trawB\tworstB" +:
        out2.map((m, a, b) => f"${m.name}\t${a.feasible}\t${a.raw}%.6f\t${b.feasible}\t" +
                              f"${b.raw}%.6f\t${b.worst}"))
      println(f"\nsurvives both: $kept%3d of ${loaded.length}%d")
      println(f"seed-sensitive: $lucky%3d  (passed one stream, failed the other)")
      println(f"fails both    : $both%3d  (rejected on both streams)")
      println(s"wrote $out/holdout.tsv")
      sys.exit(0)

    writeArchive(out, startArc, gen0, evals0, (nsum0, nn0), settings)
    // THE HEADER IS A CONTRACT, and it is written only when the log is absent, so a resume
    // after a column was added would append wider rows under the narrower header and quietly
    // ragged the file.  Refuse instead: the run that wrote those rows read a different log.
    val logHeader =
      (Vector("gen", "eval", "parent", "feasible", "score", "raw", "worstRow", "seconds") ++
       descNames ++ Vector("gateFail", "admitted")).mkString("\t")
    val logPath = s"$out/log.tsv".asPath
    if !logPath.exists then logPath.writeLines(Seq(logHeader))
    else
      val had = logPath.lines.headOption.getOrElse("")
      if had != logHeader then
        usage(s"$out/log.tsv was written with different columns; move it aside\n" +
              s"  it has [$had]\n  this binary writes [$logHeader]")

    /** One generation: `pop` mutations of members drawn from the archive, then a checkpoint.  The
      * archive is passed and returned rather than mutated, and a kill between generations loses
      * at most one generation's work. */
    def generation(arc: Vector[Member], g: Int, evals: Int,
                   nsum0: Double, nn0: Int): (Vector[Member], Int, Double, Int) =
      // THE SAME GENERATOR AS THE REST OF THE PROGRAM, and the same one the Rust harness uses:
      // `NumPyRNG`'s `randn` and `nextBoundedInt` are gated bit-identical across the twins, so
      // both harnesses propose the SAME candidates from one `-seed`.  `-calibrate` was moved off
      // `scala.util.Random` for this reason and the search was the last place it survived.  The
      // mask keeps the derived seed non-negative, because the two languages type it differently.
      val rng = new NumPyRNG((base ^ (g.toLong * 0x9e3779b9L)) & 0x7fffffffffffffffL)
      // A RUNNING NOISE ESTIMATE, from the spread each candidate showed across its own
      // repetitions.  Measured rather than declared, and it costs nothing: the readings are there.
      // carried ACROSS generations, not reset per generation: more readings, better estimate
      val start = (arc, evals, Vector.empty[String], nsum0, nn0)
      val (next, used, log, nsum, nn) =
        (0 until pop).foldLeft(start) { case ((acc, ev, lg, nsum, nn), k) =>
        val parent = acc(rng.nextBoundedInt(acc.length))
        val child = parent.dials.indices.toVector.map { i =>
          clamped(i, parent.dials(i) + rng.randn() * sigma * (ranges(i)._3 - ranges(i)._2))
        }
        val t0 = System.nanoTime()
        val r = evaluate(worldOf(worldFor(parent.name), child), anchors, paths, years,
                         (0 until reps).toVector.map(j => base + (ev + j) * 7919L), deadZone(dead))
        val secs = (System.nanoTime() - t0) / 1e9
        val nsum2 = if r.feasible then nsum + r.spread else nsum
        val nn2   = if r.feasible then nn + 1 else nn
        // admitted BEFORE the line is built, because the line records the answer
        val (grown, took) =
          if r.feasible then
            admit(acc, Member(parent.name, child, r.score, r.raw, r.worst, r.desc),
                  sep, keep, nsum2 / nn2) // nn2 >= 1: incremented on this path
          else (acc, false)
        val line = f"$g\t${ev + k}\t${parent.name}\t${r.feasible}\t${r.score}%.6f\t${r.raw}%.6f" +
                   f"\t${r.worst}\t$secs%.3f\t" + r.desc.map(x => f"$x%.8g").mkString("\t") +
                   "\t" + r.gateFail.mkString("; ") + s"\t$took"
        (grown, ev + reps, lg :+ line, nsum2, nn2)
      }
      appendLog(out, log)
      writeArchive(out, next, g + 1, used, (nsum, nn), settings)
      println(f"gen $g%5d  archive ${next.length}%3d  best ${next.map(_.score).min}%7.3f  " +
              f"median ${MarketSim.pctile(next.map(_.score), 0.5)}%7.3f  " +
              f"raw ${next.map(_.raw).min}%7.3f  evals $used%6d")
      (next, used, nsum, nn)

    // `-gens 0` runs until killed; the checkpoint after every generation is what makes that safe.
    var arc = startArc; var g = gen0; var evals = evals0
    var nsum = nsum0; var nn = nn0
    while gens == 0 || g < gen0 + gens do
      val (a2, e2, s2, n2) = generation(arc, g, evals, nsum, nn)
      arc = a2; evals = e2; nsum = s2; nn = n2; g += 1

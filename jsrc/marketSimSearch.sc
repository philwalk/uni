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
    "              ;   the recorded ensemble, and exit",
    "-prune        ; keep only the members that passed BOTH arms of the last -holdout, moving",
    "              ;   the rest to dropped.tsv rather than deleting them, and exit",
    "-force        ; resume even though the recorded settings differ from the flags given",
    "-transport N  ; a counterpart recipe in the OTHER anchor set (e.g. 0.24.1-nasdaq).  Every",
    "              ;   candidate is then judged on the WORSE of its two markets, carrying its own",
    "              ;   values on every searched dial except the ones that counterpart moved away",
    "              ;   from the default, which stay at the counterpart's.  Roughly doubles the cost",
    "              ;   an evaluation",
    "-export F     ; write the archive as a worlds JSON and exit",
    "-fidelity L   ; comma-separated PxY ensembles (e.g. 20x40,30x60).  Score the frozen pool at",
    "              ;   -paths/-years and at each of these, report how well each RANKS the worlds",
    "              ;   against the reference and what it costs, and exit.  Measured against 60x80:",
    "              ;   30x80 is 2.0x for a rank correlation of 0.993 and no feasibility",
    "              ;   disagreement.  Paths govern feasibility agreement and years govern ranking;",
    "              ;   20 paths is a floor, below which the worst-crash row stops resolving",
  )

  def intOr(flag: String, v: String): Int =
    v.toIntOption.getOrElse(usage(s"$flag wants an integer, got [$v]"))

  def numOr(flag: String, v: String): Double =
    v.toDoubleOption.getOrElse(usage(s"$flag wants a number, got [$v]"))

  /** One archive member: which seed world carries the non-searched dials, the searched dials
    * themselves, and the reading that admitted it. */
  final case class Member(name: String, dials: Vector[Double], score: Double, raw: Double,
                          worst: String, desc: Vector[Double])

  /** THE SEARCH SPACE IS THE LIBRARY'S, not a copy, as it is for the Rust harness.  The copy
    * this replaced was checked against the jar by NAME SET only -- the check that passed while
    * the twins' tables were permuted at positions 15-23 and drew different worlds from one seed.
    * Order is part of the sampler, so there is one table, pinned by `CalibrateDialOrder`. */
  val ranges = MarketSim.CalibrateRanges
  val names: Vector[String] = ranges.map(_._1)

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

  // ---- cheap fidelity -------------------------------------------------------------------------

  /** Average ranks, so ties do not invent an ordering the readings do not have. */
  def ranks(xs: Vector[Double]): Vector[Double] =
    val idx = xs.indices.toVector.sortWith((a, b) => java.lang.Double.compare(xs(a), xs(b)) < 0)
    // runs of equal values, each compared with the run's FIRST member as the Rust twin does
    val runs = idx.foldLeft(Vector.empty[Vector[Int]]) { (acc, k) =>
      if acc.nonEmpty && xs(acc.last.head) == xs(k) then acc.init :+ (acc.last :+ k)
      else acc :+ Vector(k)
    }
    runs.foldLeft((Vector.fill(xs.length)(0.0), 0)) { case ((o, start), run) =>
      val r = (start + start + run.length - 1).toDouble / 2.0 + 1.0
      (run.foldLeft(o)((acc, k) => acc.updated(k, r)), start + run.length)
    }._1

  /** Spearman: Pearson on the ranks.  What a cheap ensemble has to preserve is the ORDER of
    * worlds, not their losses -- the search only ever compares candidates. */
  def spearman(a: Vector[Double], b: Vector[Double]): Double =
    val (ra, rb) = (ranks(a), ranks(b))
    val n = ra.length.toDouble
    val (ma, mb) = (ra.sum / n, rb.sum / n)
    val (num, da, db) = ra.indices.foldLeft((0.0, 0.0, 0.0)) { case ((nu, x, y), i) =>
      val (p, q) = (ra(i) - ma, rb(i) - mb)
      (nu + p * q, x + p * p, y + q * q)
    }
    if da == 0.0 || db == 0.0 then Double.NaN else num / math.sqrt(da * db)

  /** `PxY` pairs: `20x40,30x60`. */
  def parseEnsembles(spec: String): Vector[(Int, Int)] =
    spec.split(",").map(_.trim).filter(_.nonEmpty).toVector.map { e =>
      e.split("[xX]", 2) match
        case Array(p, y) =>
          (p.trim.toIntOption, y.trim.toIntOption) match
            case (Some(pp), Some(yy)) => (pp, yy)
            case _ => usage(s"-fidelity wants PxY pairs of integers, got [$e]")
        case _ => usage(s"-fidelity wants PxY pairs, got [$e]")
    }

  // ---- transport ------------------------------------------------------------------------------

  /** SELECTING FOR TRANSPORT rather than testing it afterwards: a candidate is judged on the worse
    * of its two markets, so a world that fits the S&P by doing something the Nasdaq will not
    * tolerate never enters the archive.
    *
    * One dial vector cannot pass both anchor sets, and it is not supposed to.  The S&P volatility
    * band is 14 to 18 and the Nasdaq's 23.5 to 30.3; a world reading both is not a market.  What
    * transports is the MECHANISM, while the dials that say which market this is get re-solved,
    * which is exactly the structure the shipped recipes already have: `0.24.1-nasdaq` is the
    * default world with six searched dials moved.
    *
    * So the transport arm is the counterpart recipe carrying the candidate's values on every
    * searched dial EXCEPT the ones the counterpart itself moved away from the default.  Those stay
    * at the counterpart's values.  The set is derived from the two worlds rather than listed here,
    * and printed at startup, because it includes both dials deliberately re-solved for that market
    * and any the recipe simply has not tracked since the default moved -- and which is which is a
    * judgement no code should make silently. */
  final case class Transport(name: String, anchors: MarketSim.Anchors, world: World,
                             pinned: Vector[Boolean], spec: String)

  def transportOf(name: String, primarySpec: String): Transport =
    val (world, specOpt) = MarketSim.namedWorld(name).getOrElse(
      usage(s"-transport names [$name], which is not a release or recipe"))
    val spec = specOpt.getOrElse("sp500")
    if spec == primarySpec then
      usage(s"-transport $name is anchored to [$spec], the set already being searched; " +
            "a transport arm has to be the OTHER market")
    val pinned = ranges.map((_, _, _, _, get) => get(world) != get(MarketSim.Defaults))
    Transport(name, MarketSim.anchorsNamed(spec), world, pinned, spec)

  def transportWorld(t: Transport, dials: Vector[Double]): World =
    ranges.indices.foldLeft(t.world) { (w, i) =>
      if t.pinned(i) then w else ranges(i)._4(w, dials(i))
    }

  /** One candidate's reading: the primary arm alone, or the WORSE of the two arms when a
    * transport counterpart is set.  Feasible means feasible in both.  The descriptors stay the
    * primary world's: they describe the world the archive holds, and the transport arm is a
    * different world by construction. */
  def judge(base: World, dials: Vector[Double], anchors: MarketSim.Anchors,
            t: Option[Transport], paths: Int, years: Int, seeds: Vector[Long],
            dead: Double): Read =
    val a = evaluate(worldOf(base, dials), anchors, paths, years, seeds, dead)
    t match
      case None => a
      // a candidate that fails its primary market is rejected whatever the other one says, and
      // the transport arm is a whole second evaluation
      case Some(_) if !a.feasible => a
      case Some(tr) =>
        val b = evaluate(transportWorld(tr, dials), tr.anchors, paths, years, seeds, dead)
        val (score, raw, worst) =
          if b.raw > a.raw then (b.score, b.raw, s"${tr.spec}: ${b.worst}")
          else (a.score, a.raw, a.worst)
        // the transport arm's failures carry their market, as `worst` does, so a row that only
        // exists there -- the macro rows, when the counterpart runs the panel -- is not read as a
        // primary-market failure
        Read(a.feasible && b.feasible, score, raw, worst, a.desc,
             spread = math.max(a.spread, b.spread),
             gateFail = if a.gateFail.isEmpty then b.gateFail.map(r => s"${tr.spec}: $r")
                        else a.gateFail)

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
    // THE NEAREST member inside `sep`, not the first one found.  Archive order is insertion
    // order, so "first" was an arbitrary neighbour: a candidate could be refused against one
    // member while beating the one it was actually closest to.  Strict `<` keeps the earliest on
    // a tie, as the Rust twin's fold does.
    val near = arc.indices.foldLeft(Option.empty[(Int, Double)]) { (best, i) =>
      val d = apart(arc(i).dials, m.dials)
      if d >= sep then best
      else best match
        case Some((_, bd)) if bd <= d => best
        case _                        => Some((i, d))
    }.map(_._1)
    val (grown, placed) = near match
      case None                                      => (arc :+ m, true)
      case Some(i) if m.score < arc(i).score - noise => (arc.updated(i, m), true)
      case Some(_)                                   => (arc, false)
    @annotation.tailrec
    def trim(v: Vector[Member]): Vector[Member] =
      if v.length <= keep then v
      else leastDistinct(v) match
        case Some(i) => trim(v.patch(i, Nil, 1))
        case None    => v.sortBy(_.score).take(keep)   // no descriptors: the old rule
    val kept = trim(grown)
    // ADMITTED MEANS STILL THERE AFTER THE TRIM.  An appended candidate that is itself the worse
    // half of the closest behavioural pair is evicted in the same step, and the flag used to say
    // "admitted" of a world the archive never held.  Its dials identify it: a candidate is only
    // appended when no member lies within `sep` of it.
    (kept, placed && kept.exists(_.dials == m.dials))

  def main(args: Array[String]): Unit =
    var out = "search"; var anchorSpec = "sp500"; var seedSpec = ""
    var paths = 60; var years = 80; var reps = 2; var sigma = 0.07
    var keep = 40; var sep = 0.12; var pop = 8; var gens = 0
    var base = 20260813L; var exportTo = ""; var dead = 0.5
    var holdout = 0; var force = false; var prune = false
    var transportName = ""; var fidelity = ""
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
      case "-transport" => transportName = consumeNext
      case "-fidelity"  => fidelity = consumeNext
      case a          => usage(s"unrecognized arg [$a]")
    }
    if reps < 1 then usage("-reps wants at least 1")
    if sigma <= 0.0 then usage("-sigma wants a positive fraction")
    if pop < 1 then usage("-pop wants at least 1")
    val _ = out.asPath.mkdirs

    val anchors = MarketSim.anchorsNamed(anchorSpec)
    val transport = Option.when(transportName.nonEmpty)(transportOf(transportName, anchorSpec))
    transport.foreach { t =>
      val held = ranges.zip(t.pinned).collect { case (r, true) => r._1 }
      println(s"transport arm: ${t.name} (${t.spec}), holding ${held.length} of ${ranges.length} " +
              s"dials at its own values [${held.mkString(", ")}]")
    }
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

    // CHEAP FIDELITY.  The search only ever compares candidates, so a smaller ensemble is good
    // enough exactly when it RANKS worlds the way the reference does -- the losses need not agree.
    // This scores the frozen pool at the reference and at each candidate ensemble and reports the
    // rank correlation, how often the two disagree on feasibility, and what each costs.  PATHS
    // govern whether the two agree on FEASIBILITY and YEARS govern how faithfully the cheap
    // ensemble RANKS; the measured table is in the Rust twin beside the same block.
    if fidelity.nonEmpty then
      val ens = parseEnsembles(fidelity)
      val seeds = (0 until reps).toVector.map(k => base + k * 1000003L)
      println(s"cheap fidelity at $anchorSpec, ${pool.length} frozen worlds x $reps reps")
      def scoreAll(p: Int, y: Int): Vector[Read] =
        pool.map((_, w) => judge(w, dialsOf(w), anchors, transport, p, y, seeds, deadZone(dead)))
      val t0 = System.nanoTime()
      val reference = scoreAll(paths, years)
      val refSecs = (System.nanoTime() - t0) / 1e9 / pool.length
      println(f"reference $paths x ${years}y: $refSecs%.3f s an evaluation\n")
      println("  ensemble      rank corr   feasibility agrees   s/eval   speedup")
      val refRaw = reference.map(_.raw)
      val rows = ens.flatMap { (p, y) =>
        val t = System.nanoTime()
        val got = scoreAll(p, y)
        val secs = (System.nanoTime() - t) / 1e9 / pool.length
        val agree = got.zip(reference).count((a, b) => a.feasible == b.feasible)
        println(f"  $p%3d x $y%3dy      ${spearman(got.map(_.raw), refRaw)}%9.3f   " +
                f"$agree%10d of ${pool.length}%-6d   $secs%6.3f   ${refSecs / secs}%5.1fx")
        pool.indices.map { i =>
          f"$p\t$y\t${pool(i)._1}\t${got(i).feasible}\t${got(i).raw}%.6f\t" +
          f"${reference(i).feasible}\t${reference(i).raw}%.6f"
        }
      }
      s"$out/fidelity.tsv".asPath.writeLines(
        "paths\tyears\tworld\tfeasible\traw\trefFeasible\trefRaw" +: rows)
      println(s"\nwrote $out/fidelity.tsv")
      sys.exit(0)


    val settings = Vector("paths" -> paths.toString, "years" -> years.toString,
                          "reps" -> reps.toString, "sigma" -> f"$sigma%.4f",
                          "dead" -> f"$dead%.4f", "keep" -> keep.toString,
                          "sep" -> f"$sep%.4f", "pop" -> pop.toString,
                          "anchors" -> anchorSpec, "seed" -> base.toString,
                          "seeds" -> (if seedSpec.isEmpty then "(all)" else seedSpec),
                          // the ADMISSION RULES are recorded: a resume under different ones puts
                          // two standards in one archive, the failure the ensemble settings guard.
                          // `-nearest` since the replacement rule compares the NEAREST member
                          // inside `sep`; an archive built on the first-found one refuses to resume.
                          "admit" -> "spread-keeping-nearest",
                          "transport" -> (if transportName.isEmpty then "(none)" else transportName))
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
        // SEED SLOTS, not evaluations: every candidate is allotted `reps` seeds whether or not it
        // stops at its first infeasible one, which keeps its seeds independent of how earlier
        // candidates fared
        println(s"resumed: ${loaded.length} members, generation $gen0, $evals0 seed slots")
        loaded
      else
        // Membership is re-checked, never inherited: the older releases were adopted against an
        // earlier gate and several no longer pass the rows the model has since grown.
        println(s"seeding from ${pool.length} frozen worlds at $anchorSpec, $paths x ${years}y " +
                s"x $reps reps")
        val seeded = pool.flatMap { (nm, w) =>
          val r = judge(w, dialsOf(w), anchors, transport, paths, years,
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
        val a = judge(worldFor(m.name), m.dials, anchors, transport, paths, years, train,
                      deadZone(dead))
        val b = judge(worldFor(m.name), m.dials, anchors, transport, paths, years, fresh,
                      deadZone(dead))
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
        (nm, fresh.map(sd =>
          judge(w, dialsOf(w), anchors, transport, paths, years, Vector(sd), deadZone(dead)).raw))
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
        (0 until pop).foldLeft(start) { case ((acc, ev, lg, nsum, nn), _) =>
        val parent = acc(rng.nextBoundedInt(acc.length))
        val child = parent.dials.indices.toVector.map { i =>
          clamped(i, parent.dials(i) + rng.randn() * sigma * (ranges(i)._3 - ranges(i)._2))
        }
        val t0 = System.nanoTime()
        val r = judge(worldFor(parent.name), child, anchors, transport, paths, years,
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
        // `ev`, the SEED BASE this candidate drew from (seeds are base + (ev + j) * 7919), so a log
        // line reproduces its candidate; `ev + k` advanced by reps + 1 and was neither that nor a
        // candidate number
        val line = f"$g\t$ev\t${parent.name}\t${r.feasible}\t${r.score}%.6f\t${r.raw}%.6f" +
                   f"\t${r.worst}\t$secs%.3f\t" + r.desc.map(x => f"$x%.8g").mkString("\t") +
                   "\t" + r.gateFail.mkString("; ") + s"\t$took"
        (grown, ev + reps, lg :+ line, nsum2, nn2)
      }
      appendLog(out, log)
      writeArchive(out, next, g + 1, used, (nsum, nn), settings)
      println(f"gen $g%5d  archive ${next.length}%3d  best ${next.map(_.score).min}%7.3f  " +
              f"median ${MarketSim.pctile(next.map(_.score), 0.5)}%7.3f  " +
              f"raw ${next.map(_.raw).min}%7.3f  slots $used%6d")
      (next, used, nsum, nn)

    // `-gens 0` runs until killed; the checkpoint after every generation is what makes that safe.
    var arc = startArc; var g = gen0; var evals = evals0
    var nsum = nsum0; var nn = nn0
    while gens == 0 || g < gen0 + gens do
      val (a2, e2, s2, n2) = generation(arc, g, evals, nsum, nn)
      arc = a2; evals = e2; nsum = s2; nn = n2; g += 1

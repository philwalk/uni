#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using scala 3.7.0
//> using dep org.vastblue:uni_3:0.24.4

// A CALIBRATION SEARCH for the market simulator, built to run for days and to be killed at any
// moment.  NOT SHIPPED, and it adds nothing to the library: the simulator and its runtime come
// from the published jar, so this searches the PUBLISHED model -- to search unreleased changes,
// publish and bump the `using dep` above.  The Rust twin, `market_sim_search`, is 7.7x faster and
// produces the same files.
//
//   jsrc/marketSimSearch.sc -out search
//
// WHAT IT PRODUCES is an ARCHIVE, not a champion: 34 searched dials against ~45 graded rows that
// are not independent means distinct worlds match the record equally well, and a strategy feels
// the mechanism, not the summary statistic.
//
// THE OBJECTIVE IS LEXICOGRAPHIC.  Feasibility first, a hard gate never priced into a scalar, so a
// failed row cannot be bought.  Then the SUM over rows of each row's distance from the record past
// a DEAD ZONE at the record's own sampling error: inside it no gradient, so a long search cannot
// optimise noise; outside it every row counts, so no row drifts while another binds.
//
// SETTINGS THAT MEASUREMENT SET: 1 uniform draw in 60 is feasible, so the search is seeded from
// the frozen worlds; a Gaussian mutation of 5-10% of a dial's range keeps four children in five
// feasible and 20% breaks, so plain rejection handles the constraint; seed noise is a smooth sd of
// 0.074 plus a 0.5 jump when a band-edge row flips (one seed in six), so every `-reps` seed must
// pass.
//
// EVERYTHING IS PORTABLE BETWEEN THE TWO HARNESSES, `-transport` and `-fidelity` included, and a
// capability added to one is added to the other.  Feasibility comes from `gateChecks`, whose
// statistics are byte-identical across the twins, so a Rust `-holdout` re-scores this archive
// exactly.  The mutation stream too: both draw from `NumPyRNG`, gated bit-identical, where a
// language-native Gaussian is not.

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
    "-bar M        ; THE QUALITY BAR: a feasible candidate enters only if its score is at most M",
    "              ;   times its seed world's, judged the same way (default 1.0: at least as",
    "              ;   consistent with the record as the world it was seeded from; 0 = no bar,",
    "              ;   distance alone admits).  Members above it are dropped on resume",
    "-pop P        ; candidates per generation, checkpointed after each (default 8)",
    "-gens G       ; generations to run; 0 runs until the spread closes, or until killed (default 0)",
    "-close P      ; stop when the last two blocks of generations added under P points of the",
    "              ;   archive's spread, the report's CLOSED rule, a block being an eighth of the",
    "              ;   run and at least 75 generations, so never before generation 300",
    "              ;   (default 2; 0 = never stop on its own)",
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

  /** THE SEARCH SPACE IS THE LIBRARY'S table, never a copy: the sampler draws one uniform per dial
    * in table order, so order is part of it, and `CalibrateDialOrder` pins it in both twins. */
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

  /** WHAT A WORLD DOES, beside what it is set to.  Never graded, never optimised: the archive keeps
    * worlds apart by dial distance, a proxy for behavioural difference; these measure it.  `retAc1`
    * leads because nothing in the target set constrains it -- variance ratios read a weighted sum of
    * autocorrelations and the clustering rows read |r| -- and it spread furthest across an archive.
    * Written for EVERY candidate into `log.tsv`: the rejected majority is gone when the run ends.
    * Both harnesses share this list; the archive is a format they share. */
  val descNames: Vector[String] =
    Vector("retAc1", "vr250", "clus20", "kurt", "epPerPath", "depthMed")

  def descOf(st: MarketSim.WorldStats): Vector[Double] =
    Vector(st.retAc1, st.vr250, st.ac20, st.kurt, st.epPerPath, st.depthMed)

  /** Feasible on every rep, and the SUM over rows of each row's distance past the dead zone.  The
    * reading is the WORST across reps, never the mean: a world that passes only on a lucky seed
    * has not passed.  The DESCRIPTORS are the mean instead -- they describe the world, where `raw`
    * deliberately describes its worst seed. */
  final case class Read(feasible: Boolean,
                        /** THE OBJECTIVE: `sum(max(0, term - dead))` over every row of every arm.
                          * Inside the record's own error a row contributes nothing; outside it
                          * every row counts, and a row cannot be bought below the dead zone
                          * because its excess is paid in full.  The worst row alone put no
                          * pressure on any other, and an archive built on it drifted out to the
                          * binding row's level on every row (median member 5 rows past the dead
                          * zone against the default's 1). */
                        score: Double,
                        /** The worst row's term, and its name: what a member is furthest from,
                          * for the reports. */
                        raw: Double, worst: String,
                        desc: Vector[Double],
                        /** THE WHOLE SIGNATURE: `fitness`'s summed loss over every row, both arms
                          * when a transport counterpart is set, as a mean over the reps.  `raw` is
                          * one row, and a world can improve that row by degrading every other;
                          * this is what says whether it did.  Meaningful only for a feasible
                          * reading -- an infeasible one never measured its extreme rows. */
                        total: Double,
                        /** How far this world's SCORE moved across its own repetitions, max
                          * minus min.  Free to compute and it is the noise scale the archive
                          * needs: a score difference smaller than this is a seed draw, not a
                          * better world.  Zero at `-reps 1`, which is honest. */
                        spread: Double = 0.0,
                        /** WHICH GATE ROWS FAILED, empty when feasible: `worstRow` is a fitness
                          * row and says nothing about rejection. */
                        gateFail: Vector[String] = Vector.empty,
                        /** Every rep and both arms ran.  False when the quality bar stopped the
                          * evaluation early: the score is the maximum over the reps plus the
                          * transport arm, so once the reps so far exceed the bar nothing later
                          * can bring it back under, and the rest is not run.  Such a reading is
                          * rejected as before, but its `spread` and `desc` are partial and must
                          * not feed the noise estimate. */
                        complete: Boolean = true)

  /** One seed's reading.  The extreme row is read at its anchor's own horizon, so when `-years` is
    * that horizon the main ensemble serves and nothing is simulated twice.  An infeasible candidate
    * never pays for the extreme ensemble: feasibility reads only the pooled statistics, and the rows
    * needing that ensemble are dropped from the worst-row search rather than scored as unmeasurable,
    * so a rejected candidate's `worstRow` still names something measured.
    *
    * `evaluate` stops at the first seed that fails, because feasibility needs every seed. */
  def oneRead(w: World, anchors: MarketSim.Anchors, paths: Int, years: Int,
              s: Long, dead: Double): Read =
    val main = MarketSim.simPaths(w, paths, years, s)
    val st   = MarketSim.measure(main, years)
    val checks = MarketSim.gateChecks(anchors, st)
    val bad  = checks.count((_, ok, cls) => !ok && MarketSim.GateDefault.contains(cls))
    val feasible = bad == 0
    // THE FIDELITY BANDS THAT ARE NOT FITNESS ROWS -- the macro panel's, the channels', the
    // variance-ratio profile, the bond's -- were invisible to the search: neither gated (a
    // fidelity band flips on a seed at 60 paths, and feasibility has to hold on every seed) nor
    // scored (no fitness row reads them).  An archive built blind to them had 139 of 145 members
    // failing one, and a recipe has to pass every class.  Each failed band now costs one dead
    // zone, the one priced term in the score: a flip on one seed is a nudge, a band a member sits
    // outside on every seed is a row's worth of excess.  Named in `gateFail` for a feasible
    // candidate, so the log says which.
    val fidFail = checks.collect { case (nm, false, MarketSim.GateClass.Fidelity) => s"fidelity: $nm" }
    val ex =
      if !feasible then Map.empty[String, Double]
      else if MarketSim.extremeHorizons(anchors) == Vector(years) then
        MarketSim.extremeScoreStatsFrom(anchors, main, years)
      else MarketSim.extremeScoreStats(anchors, paths, s, w)
    val (total, allRows) = MarketSim.fitness(anchors, st, ex)
    val rows = allRows.filter((nm, _, _, _) => feasible || !MarketSim.extremeTargetNames.contains(nm))
    val raw = rows.map((nm, _, _, term) => (term, nm)).max
    // in row order, a plain left fold, as the Rust harness's `sum` is; then the fidelity bands
    val score = rows.map((_, _, _, term) => math.max(0.0, term - dead)).sum + dead * fidFail.length
    val gateFail =
      if feasible then fidFail
      else MarketSim.GateDefault.toVector.flatMap(cls => MarketSim.failedIn(anchors, st, cls))
    Read(feasible, score, raw._1, raw._2, descOf(st), total, gateFail = gateFail)

  def evaluate(w: World, anchors: MarketSim.Anchors, paths: Int, years: Int,
               seeds: Vector[Long], dead: Double, bar: Option[Double] = None): Read =
    // takeWhile-with-the-failure: every seed up to and including the first infeasible one, or
    // up to the first that puts the running maximum over the bar: the rest cannot change the
    // answer
    def over(r: Read): Boolean = r.feasible && bar.exists(b => r.score > b)
    val reads = seeds.foldLeft(Vector.empty[Read]) { (acc, s) =>
      if acc.nonEmpty && (!acc.last.feasible || over(acc.last)) then acc
      else acc :+ oneRead(w, anchors, paths, years, s, dead)
    }
    val cut = reads.length < seeds.length && over(reads.last)
    val hardest = reads.maxBy(_.raw)
    val desc = descNames.indices.toVector.map(i => reads.map(_.desc(i)).sum / reads.length)
    val spread = reads.map(_.score).max - reads.map(_.score).min
    val total  = reads.map(_.total).sum / reads.length
    // the worst seed's score, as the worst seed's row is the reported one
    Read(reads.forall(_.feasible), reads.map(_.score).max, hardest.raw, hardest.worst, desc, total,
         spread, hardest.gateFail, complete = !cut)

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

  /** SELECTING FOR TRANSPORT rather than testing it afterwards: a candidate is judged on the WORSE of
    * its two markets, so a world that fits the S&P by doing something the Nasdaq will not tolerate
    * never enters.  One dial vector cannot pass both sets (equity vol bands 14-18 and 23.5-30.3); the
    * MECHANISM transports and the market dials re-solve, which is the structure of the shipped
    * recipes.  So the arm is the counterpart world carrying the candidate's values on every searched
    * dial EXCEPT the market dials (`MarketDials`), which stay at the counterpart's, in either
    * direction. */
  final case class Transport(name: String, anchors: MarketSim.Anchors, world: World, spec: String)

  def transportOf(name: String, primarySpec: String): Transport =
    val (world, specOpt) = MarketSim.namedWorld(name).getOrElse(
      usage(s"-transport names [$name], which is not a release or recipe"))
    val spec = specOpt.getOrElse("sp500")
    if spec == primarySpec then
      usage(s"-transport $name is anchored to [$spec], the set already being searched; " +
            "a transport arm has to be the OTHER market")
    Transport(name, MarketSim.anchorsNamed(spec), world, spec)

  /** THE MARKET DIALS: the searched dials that say which market a world is rather than how its
    * mechanism works, held at the counterpart's values on the transport arm.  Named, not derived:
    * the set used to be "the dials on which the counterpart differs from the seed world", which
    * produced this list for every hand-built recipe and all thirty for `0.24.2-nasdaq`, a recipe
    * the search itself re-solved -- an arm holding everything judges the counterpart, not the
    * candidate.  Every name must be a searched dial; the harness refuses to start otherwise. */
  val MarketDials: Vector[String] =
    Vector("depth", "drift", "stress", "volOfVol", "jumpVar", "refuge", "slowShare")

  /** Which searched dials the transport arm holds, in table order. */
  def held: Vector[Boolean] = ranges.map((nm, _, _, _, _) => MarketDials.contains(nm))

  def transportWorld(t: Transport, dials: Vector[Double]): World =
    val h = held
    ranges.indices.foldLeft(t.world) { (w, i) =>
      if h(i) then w else ranges(i)._4(w, dials(i))
    }

  /** One candidate's reading: the primary arm alone, or both arms when a transport counterpart
    * is set -- the scores add, feasible means feasible in both, and the worst row is the worse
    * arm's.  The descriptors stay the primary world's: they describe the world the archive holds,
    * and the transport arm is a different world by construction. */
  def judge(base: World, dials: Vector[Double], anchors: MarketSim.Anchors,
            t: Option[Transport], paths: Int, years: Int, seeds: Vector[Long],
            dead: Double, bar: Option[Double] = None): Read =
    val a = evaluate(worldOf(base, dials), anchors, paths, years, seeds, dead, bar)
    t match
      case None => a
      // a candidate that fails its primary market is rejected whatever the other one says, and
      // the transport arm is a whole second evaluation; so is one whose primary arm alone is
      // over the bar, because the arm's scores add
      case Some(_) if !a.feasible => a
      case Some(_) if bar.exists(b => a.score > b) => a.copy(complete = false)
      case Some(tr) =>
        val b = evaluate(transportWorld(tr, dials), tr.anchors, paths, years, seeds, dead, bar)
        val score = a.score + b.score
        val (raw, worst) =
          if b.raw > a.raw then (b.raw, s"${tr.spec}: ${b.worst}")
          else (a.raw, a.worst)
        // the transport arm's failures carry their market, as `worst` does, so a row that only
        // exists there -- the macro rows, when the counterpart runs the panel -- is not read as a
        // primary-market failure
        Read(a.feasible && b.feasible, score, raw, worst, a.desc, a.total + b.total,
             spread = math.max(a.spread, b.spread),
             gateFail = if a.gateFail.isEmpty then b.gateFail.map(r => s"${tr.spec}: $r")
                        else a.gateFail,
             complete = a.complete && b.complete)

  /** the OBJECTIVE, as a digest of every row a candidate is judged on -- each set's name, then each
    * row's name, target and weight, for the primary set and the transport arm's. Recorded with the
    * settings so a resume refuses an archive scored under different weights: re-freezing one
    * spread changes the loss every member was admitted on, and nothing else in the checkpoint
    * would show it. FNV-1a over the rows as text, numbers at eight significant digits, so both
    * twins write the same digest. */
  def objectiveDigest(anchors: MarketSim.Anchors, transport: Option[Transport]): String =
    val text = (anchors +: transport.map(_.anchors).toVector).flatMap { a =>
      a.name +: MarketSim.fitTargets(a).map((name, _, target, weight) =>
        f"$name|$target%.8g|$weight%.8g")
    }.map(_ + "\n").mkString
    val h = text.getBytes("UTF-8").foldLeft(0xcbf29ce484222325L) { (acc, b) =>
      (acc ^ (b & 0xffL)) * 0x100000001b3L
    }
    f"$h%016x"

  // ---- checkpoint ---------------------------------------------------------------------------
  // Plain TSV, one member per row, dial columns in `CalibrateRanges` order.  A days-long run that
  // cannot be killed and resumed is a run nobody will start.

  /** THE SETTINGS ARE PART OF THE CHECKPOINT: without the ensemble and rules that judged them the
    * members cannot be reproduced, and a resume that changed a flag would mix standards silently.
    * Key/value so a later version copes with a row it does not recognise. */
  def writeArchive(dir: String, arc: Vector[Member], gen: Int, evals: Int,
                   noise: (Double, Int),
                   cfg: Vector[(String, String)]): Unit =
    val header = (Vector("name", "score", "raw", "worstRow") ++ names ++ descNames).mkString("\t")
    val rows = arc.map(m => (Vector(m.name, f"${m.score}%.6f", f"${m.raw}%.6f", m.worst) ++
                             m.dials.map(x => f"$x%.8g") ++
                             m.desc.map(x => f"$x%.8g")).mkString("\t"))
    s"$dir/archive.tsv".asPath.writeLines(header +: rows)
    // The seed-noise accumulator is resumable STATE, not a setting: the resume guard ignores it, and
    // a resume that restarted it would admit inside the noise for its first generations.  `%.8g` so both
    // twins write one text.
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
    else
      // THE HEADER IS THE CONTRACT: the dial columns are read by position, so an archive written
      // with a different dial table -- fewer dials, or the same count in another order -- must be
      // refused by name, not by width.  A row-width check let a 28-dial archive read as 30 with
      // two descriptors taken for dials.
      val expect = (Vector("name", "score", "raw", "worstRow") ++ names ++ descNames).mkString("\t")
      val ls = ap.lines.toVector
      val head = ls.headOption.getOrElse("")
      if head != expect then
        usage(s"$dir/archive.tsv was written with different columns; it has [$head]\n" +
              s"  this binary reads [$expect]")
      val want = 4 + ranges.length + descNames.length
      ls.drop(1).filter(_.trim.nonEmpty).map { l =>
        val f = l.split("\t").toVector
        if f.length != want then
          usage(s"$dir/archive.tsv has a row of ${f.length} fields where $want are expected")
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
      // at the archive's own width, not the report's six decimals -- this block is what a consumer
      // reconstructs a world from -- and joined on `,\n`, since `worldJsonBody` returns the fields
      // without separators
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

  /** Admit a feasible candidate.  Returns the archive and whether the candidate is IN IT AFTER THE
    * TRIM -- a replacement leaves the count unchanged, so the log needs the flag.
    *
    * REPLACING NEEDS A MARGIN: a candidate takes the place of the NEAREST member inside `sep` only
    * when it beats that member by more than the running seed noise.  Without one a 4317-generation
    * run sorted an archive whose whole score range, 0.018, sat inside one seed sd of 0.044.  The dead
    * zone does not help: it discounts a row, not a comparison of two worlds.
    *
    * TRIMMING DROPS THE LEAST DISTINCT, never the worst-scoring: of the closest pair in descriptor
    * space, each descriptor normalised by its range across the archive, the worse scorer goes.
    * Trimming by score is an optimiser and narrows the spread the archive exists to keep (signed
    * lag-1 range 0.057 -> 0.032 over that run; 0.043 -> 0.074 for spread-keeping in a 40-generation
    * A/B).  Feasibility is the membership test; score was never meant to be a second one. */
  def admit(arc: Vector[Member], m: Member, sep: Double, keep: Int,
            noise: Double): (Vector[Member], Boolean) =
    // the nearest member inside `sep`, not the first found; strict `<` keeps the earliest on a tie,
    // as the Rust twin's fold does
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
    // in the archive after the trim: an appended candidate can be the worse half of the closest pair
    (kept, placed && kept.exists(_.dials == m.dials))

  /** THE STOPPING RULE, the report's: the spread is the descriptor span over everything admitted
    * so far, each descriptor as a fraction of its span over the whole run, averaged; the points
    * of it added over the last two blocks, a block being an eighth of the run and at least
    * `BlockFloor` generations.  Deliberately crude, and it says which numbers it read: a
    * stopping rule nobody can check is worse than no stopping rule.  It reads the spread because
    * admissions never fall under spread-keeping and the best score is one row of the product;
    * two blocks, because one block of a search this noisy proves nothing.  None until the run is
    * four blocks long or while no descriptor has any span.
    *
    * THE FLOOR IS 75 BECAUSE THE RULER IS THE SPAN SO FAR.  The report reads a finished run
    * against its final span; live, only the span so far exists, and it is still small while the
    * archive is young, so a short flat stretch reads as closed.  Replayed over three archived
    * searches, a floor of 10 stopped them at generation 54-84 and a floor of 50 forfeited a
    * tenth of one set's final spread; 75 stops at 496-516 keeping 96-97% of it, and cannot fire
    * before generation 300. */
  val BlockFloor = 75

  def spreadAdded(trace: Vector[(Int, Vector[Double])], gens: Int): Option[(Double, Int)] =
    val blk = math.max(BlockFloor, gens / 8)
    if gens < 4 * blk || trace.isEmpty then None
    else
      val nd = trace.head._2.length
      def span(before: Int): Vector[Double] =
        (0 until nd).toVector.map { j =>
          val xs = trace.collect { case (g, d) if g < before && !d(j).isNaN => d(j) }
          if xs.isEmpty then Double.NaN else xs.max - xs.min
        }
      val end = span(Int.MaxValue)
      def at(before: Int): Option[Double] =
        val r = span(before).zip(end).collect { case (a, b) if !a.isNaN && b > 0 => a / b }
        if r.isEmpty then None else Some(r.sum / r.length)
      for a <- at(gens); b <- at(gens - 2 * blk) yield (100.0 * (a - b), blk)

  def main(args: Array[String]): Unit =
    var out = "search"; var anchorSpec = "sp500"; var seedSpec = ""
    var paths = 60; var years = 80; var reps = 2; var sigma = 0.07
    var keep = 40; var sep = 0.12; var pop = 8; var gens = 0; var close = 2.0
    var base = 20260813L; var exportTo = ""; var dead = 0.5
    var holdout = 0; var force = false; var prune = false; var bar = 1.0
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
      case "-close"   => close = numOr("-close", consumeNext)
      case "-dead"    => dead = numOr("-dead", consumeNext)
      case "-seed"    => base = consumeNext.toLong
      case "-holdout" => holdout = intOr("-holdout", consumeNext)
      case "-bar"     => bar = numOr("-bar", consumeNext)
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
    // `Releases` stops at the last frozen row, so the current default is added first.  A seed is graded
    // against the anchor set it was verified against -- the Nasdaq recipes read equity vol 0.5 past
    // the dead zone on S&P anchors -- so the pool is restricted to the set being searched: an S&P
    // archive and a Nasdaq archive are separate products.
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
    for t <- transport do
      val missing = MarketDials.filterNot(names.contains)
      if missing.nonEmpty then
        usage(s"the market dials name [${missing.mkString(", ")}], which is not a searched dial")
      println(s"transport arm: ${t.name} (${t.spec}), holding the ${MarketDials.length} market " +
              s"dials of ${ranges.length} at its own values [${MarketDials.mkString(", ")}]")

    // CHEAP FIDELITY: a smaller ensemble is good enough when it RANKS worlds as the reference does;
    // the losses need not agree.  Measured on the frozen pool at the S&P set against 60 x 80:
    //
    //     ensemble    rank corr   feasibility agrees   speedup
    //     20 x 40y        0.945           17 of 19        3.9x
    //     30 x 60y        0.952           19 of 19        2.2x
    //     30 x 80y        0.993           19 of 19        2.0x
    //     40 x 80y        1.000           19 of 19        1.5x
    //
    // Paths govern feasibility agreement and years govern ranking.  `-years` saves less than it looks:
    // the worst-crash row reads at its anchor's horizon, 100 years for the S&P, whatever `-years`
    // says.  20 paths is a floor, below which that row cannot place a record inside its band.
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
                          "bar" -> f"$bar%.4f",
                          "seeds" -> (if seedSpec.isEmpty then "(all)" else seedSpec),
                          // the admission rules and the objective are recorded with the ensemble: a resume under different
                          // ones puts two standards in one archive
                          "admit" -> "spread-keeping-nearest",
                          "score" -> "sum-excess",
                          // the weights and targets the loss applies: see `objectiveDigest`
                          "objective" -> objectiveDigest(anchors, transport),
                          "transport" -> (if transportName.isEmpty then "(none)" else transportName))
    val loaded = readArchive(out)
    // a checkpoint without `score` was scored on the worst row alone; a resume must refuse, not
    // adopt, since its members' scores are not comparable to the sum
    val prior =
      val p = readState(out)
      if loaded.nonEmpty && !p.contains("score") then p + ("score" -> "worst-row") else p
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
    // THE SEED WORLDS' OWN READINGS, on the pool's seeds, whenever a search will run: the archive
    // is seeded from them on a fresh start, and the quality bar is set from them either way.
    // Membership is re-checked, never inherited: the older releases were adopted against an
    // earlier gate and several no longer pass the rows the model has since grown.
    val searchMode = holdout == 0 && !prune && exportTo.isEmpty
    val seedReads: Vector[(String, World, Read)] =
      if !searchMode then Vector.empty
      else
        println(s"seed worlds at $anchorSpec, $paths x ${years}y x $reps reps")
        pool.map { (nm, w) =>
          val r = judge(w, dialsOf(w), anchors, transport, paths, years,
                        (0 until reps).toVector.map(k => base + k * 1000003L), deadZone(dead))
          println(f"  $nm%-24s ${if r.feasible then "feasible" else "REJECTED"}%-9s " +
                  f"score ${r.score}%7.3f  raw ${r.raw}%7.3f  ${r.worst}")
          (nm, w, r)
        }
    // THE QUALITY BAR.  Spread-keeping admits on distance: a feasible candidate far enough from
    // every member entered whatever its score, and a set built that way read a median summed
    // excess of 1.14 against its seed's 0.65, with a third of it well outside "consistent with
    // the record".  A candidate now also has to score no worse than `bar` times the world it was
    // seeded from, judged the same way -- so with the default 1.0 every member fits the record
    // at least as well as the shipped world its lineage started from, which is the world the
    // consumer already trusts.  Per lineage, as the null control compares, because the gate is
    // not the same for every seed world.
    val barFor: Map[String, Double] = seedReads.map((nm, _, r) => nm -> bar * r.score).toMap
    def aboveBar(name: String, score: Double): Boolean =
      bar > 0.0 && barFor.get(name).exists(score > _)
    val startArc =
      if loaded.nonEmpty then
        // SEED SLOTS, not evaluations: every candidate is allotted `reps` seeds whether or not it stops
        // at its first infeasible one, so its seeds are independent of how earlier candidates fared
        val kept = loaded.filterNot(m => aboveBar(m.name, m.score))
        println(s"resumed: ${loaded.length} members, generation $gen0, $evals0 seed slots; " +
                s"${loaded.length - kept.length} above the bar dropped")
        if searchMode && kept.isEmpty then
          usage("every resumed member is above the bar; nothing to search from")
        kept
      else
        val seeded = seedReads.collect { case (nm, w, r) if r.feasible =>
          Member(nm, dialsOf(w), r.score, r.raw, r.worst, r.desc) }
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

    // THE HOLDOUT: every member earned its place on seeds the search chose, and a band-edge world
    // flips on one draw in six.  Re-score each member at the same ensemble on TWO INDEPENDENT
    // STREAMS, neither of which selected the mutations (the search draws `base + (evals + j) * 7919`;
    // stream A is `base + k * 1000003`, the pool's own seeds, stream B that shifted by 991).  A
    // SEED-SENSITIVITY test, not train against test, and the columns say so: a member that passes one
    // stream and fails the other was admitted by a draw, not by the record.
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
      // ---- THE NULL CONTROL: each member against the world it was seeded from, on the fresh stream.
      // The seed worlds are hand-tuned against these anchors, so a member that beats its seed means the
      // tuning left something on the table or the objective has a hole; the hole is what this exists to
      // catch.  Against its OWN seed because channel rows fire only when the channel is on, so lineages
      // face different gates.
      //
      // THE THRESHOLD is twice the seed-noise sd, pooled across lineages from each seed world's
      // deviations from its own mean over the fresh seeds (a per-world range over three draws varied
      // 22-fold between lineages).  The control prints its own resolution: seed noise is a smooth sd of
      // 0.074 plus a 0.5 jump when a band-edge row flips, so resolving 0.05 needs a dozen seeds.
      //
      // A BETTER WORLD BEATS THE WORST ROW WITHOUT PAYING FOR IT ELSEWHERE.  The score pays nothing
      // inside the dead zone, so a member can lower the worst row while every other drifts (under
      // the worst-row objective one took 0.05 off it for three times the summed loss).  A member is
      // flagged only when it also reads no worse than its seed on the WHOLE SIGNATURE, the summed
      // loss over every row of both arms; the ones that paid are counted separately, as the
      // objective's hole.
      println("\nNULL CONTROL   each member against the world it was seeded from")
      if holdout < 3 then
        println(s"   -holdout $holdout gives $holdout readings a seed world, so the spread below " +
                "is thin; 3 or more makes it mean something")
      // pass one: read every seed world on every fresh seed, and pool the deviations
      val seedReads = loaded.map(_.name).distinct.sorted.map { nm =>
        val w = worldFor(nm)
        val reads = fresh.map(sd =>
          judge(w, dialsOf(w), anchors, transport, paths, years, Vector(sd), deadZone(dead)))
        (nm, reads.map(_.raw), reads.map(_.total).sum / reads.length)
      }
      val devs = seedReads.flatMap { (_, per, _) =>
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
      println("   seed world              its raw   its loss   beat it   paid elsewhere")
      val (flagged, paid) = seedReads.foldLeft(
          (Vector.empty[(String, String, Double, Double, Double)],
           Vector.empty[(String, String, Double, Double, Double)])) {
        case ((fl, pd), (nm, per, seedTotal)) =>
          val hi   = per.max
          val mine = out2.filter((m, _, _) => m.name == nm)
          val beat = mine.filter((_, _, b) => b.feasible && hi - b.raw > threshold)
          val (held, bought) = beat.partition((_, _, b) => b.total <= seedTotal)
          println(f"   $nm%-22s ${hi}%7.4f   ${seedTotal}%8.3f   ${held.length}%4d of " +
                  f"${mine.length}%-4d  ${bought.length}%4d")
          (fl ++ held.map((m, _, b) => (m.name, b.worst, hi - b.raw, b.total, seedTotal)),
           pd ++ bought.map((m, _, b) => (m.name, b.worst, hi - b.raw, b.total, seedTotal)))
      }
      if flagged.isEmpty then
        println("   PASSES: no member beats its seed by more than this control can resolve")
        println("   without paying for it on the other rows. The archive's value is its")
        println("   spread, which is what it was built for.")
      else
        println(s"   ${flagged.length} members beat their seed by more than its own spread AND " +
                "read no worse on")
        println("   the whole signature. Before believing it, look at WHICH ROW moved and at the")
        println("   descriptor columns, which carry the statistics nothing grades:")
        for (nm, worst, by, total, seedTotal) <- flagged.take(8) do
          println(f"     $nm%-22s better by ${by}%6.4f, now worst on $worst; " +
                  f"loss ${total}%.3f vs ${seedTotal}%.3f")
      if paid.nonEmpty then
        println(s"   ${paid.length} beat the worst row by PAYING FOR IT ELSEWHERE -- the " +
                "objective's hole, not a")
        println("   better world; the summed loss over every row is worse than the seed's:")
        for (nm, worst, by, total, seedTotal) <- paid.take(8) do
          println(f"     $nm%-22s worst row better by ${by}%6.4f on $worst; " +
                  f"loss ${total}%.3f vs ${seedTotal}%.3f")
      println()

      val kept = out2.count((_, a, b) => a.feasible && b.feasible)
      val lucky = out2.count((_, a, b) => a.feasible != b.feasible)
      val both = out2.count((_, a, b) => !a.feasible && !b.feasible)
      s"$out/holdout.tsv".asPath.writeLines(
        "name\tpassA\trawA\tpassB\trawB\tworstB\tlossA\tlossB" +:
        out2.map((m, a, b) => f"${m.name}\t${a.feasible}\t${a.raw}%.6f\t${b.feasible}\t" +
                              f"${b.raw}%.6f\t${b.worst}\t${a.total}%.6f\t${b.total}%.6f"))
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
    def generation(arc: Vector[Member], g: Int, evals: Int, nsum0: Double, nn0: Int,
                   trace0: Vector[(Int, Vector[Double])])
        : (Vector[Member], Int, Double, Int, Vector[(Int, Vector[Double])]) =
      // The same `NumPyRNG` as the rest of the program and as the Rust harness: `randn` and `nextBoundedInt`
      // are gated bit-identical across the twins, so one `-seed` proposes the same candidates in both.
      // The mask keeps the derived seed non-negative in both languages.
      val rng = new NumPyRNG((base ^ (g.toLong * 0x9e3779b9L)) & 0x7fffffffffffffffL)
      // a running seed-noise estimate from each candidate's spread across its own reps, carried across generations
      val start = (arc, evals, Vector.empty[String], nsum0, nn0, trace0)
      val (next, used, log, nsum, nn, trace) =
        (0 until pop).foldLeft(start) { case ((acc, ev, lg, nsum, nn, tr), _) =>
        val parent = acc(rng.nextBoundedInt(acc.length))
        val child = parent.dials.indices.toVector.map { i =>
          clamped(i, parent.dials(i) + rng.randn() * sigma * (ranges(i)._3 - ranges(i)._2))
        }
        val t0 = System.nanoTime()
        // the lineage's bar, so an evaluation stops as soon as it is over it
        val r = judge(worldFor(parent.name), child, anchors, transport, paths, years,
                      (0 until reps).toVector.map(j => base + (ev + j) * 7919L), deadZone(dead),
                      bar = if bar > 0.0 then barFor.get(parent.name) else None)
        val secs = (System.nanoTime() - t0) / 1e9
        // a candidate above the bar still feeds the noise estimate when it ran to the end: its
        // spread is a reading of the objective's own noise whatever its level.  One the bar cut
        // short does not: a partial spread is not that reading
        val nsum2 = if r.feasible && r.complete then nsum + r.spread else nsum
        val nn2   = if r.feasible && r.complete then nn + 1 else nn
        // admitted BEFORE the line is built, because the line records the answer
        val (grown, took) =
          if r.feasible && !aboveBar(parent.name, r.score) then
            // nn2 >= 1: a candidate under the bar ran to the end, so it was counted above
            admit(acc, Member(parent.name, child, r.score, r.raw, r.worst, r.desc),
                  sep, keep, nsum2 / nn2)
          else (acc, false)
        // `ev` is the SEED BASE this candidate drew from (seeds are base + (ev + j) * 7919), so a
        // log line reproduces its candidate
        val line = f"$g\t$ev\t${parent.name}\t${r.feasible}\t${r.score}%.6f\t${r.raw}%.6f" +
                   f"\t${r.worst}\t$secs%.3f\t" + r.desc.map(x => f"$x%.8g").mkString("\t") +
                   "\t" + r.gateFail.mkString("; ") + s"\t$took"
        (grown, ev + reps, lg :+ line, nsum2, nn2, if took then tr :+ (g, r.desc) else tr)
      }
      appendLog(out, log)
      writeArchive(out, next, g + 1, used, (nsum, nn), settings)
      println(f"gen $g%5d  archive ${next.length}%3d  best ${next.map(_.score).min}%7.3f  " +
              f"median ${MarketSim.pctile(next.map(_.score), 0.5)}%7.3f  " +
              f"raw ${next.map(_.raw).min}%7.3f  slots $used%6d")
      (next, used, nsum, nn, trace)

    // `-gens 0` runs until the spread closes or the run is killed; the checkpoint after every
    // generation is what makes that safe.
    // THE SPREAD TRACE the stopping rule reads: the descriptors of every candidate admitted so
    // far, by generation, rebuilt from the log on a resume so the rule reads the whole run
    val trace0: Vector[(Int, Vector[Double])] =
      logPath.lines.drop(1).toVector.flatMap { l =>
        val f = l.split('\t')
        if f.length < 10 + descNames.length || f.last != "true" then None
        else f(0).toIntOption.map(at =>
          (at, f.slice(8, 8 + descNames.length).toVector.map(x => x.toDoubleOption.getOrElse(Double.NaN))))
      }
    var arc = startArc; var g = gen0; var evals = evals0
    var nsum = nsum0; var nn = nn0; var trace = trace0
    var closed = false
    while !closed && (gens == 0 || g < gen0 + gens) do
      val (a2, e2, s2, n2, t2) = generation(arc, g, evals, nsum, nn, trace)
      arc = a2; evals = e2; nsum = s2; nn = n2; trace = t2; g += 1
      if close > 0.0 then
        spreadAdded(trace, g).foreach { (added, blk) =>
          if added < close then
            println(f"CLOSED -- the last ${2 * blk}%d gen added $added%.1f%% of the spread; stopping. Run -holdout, then -prune.")
            closed = true
        }

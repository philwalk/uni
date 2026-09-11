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
import scala.util.Random

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
                          worst: String)

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

  /** Feasible on every rep, and the worst row's distance past the dead zone.  The reading is the
    * WORST across reps, never the mean: a world that passes only on a lucky seed has not passed. */
  final case class Read(feasible: Boolean, score: Double, raw: Double, worst: String)

  def evaluate(w: World, anchors: MarketSim.Anchors, paths: Int, years: Int,
               seeds: Vector[Long], dead: Double): Read =
    val reads = seeds.map { s =>
      val st = MarketSim.measure(MarketSim.simPaths(w, paths, years, s), years)
      val ex = MarketSim.extremeScoreStats(anchors, paths, s, w)
      val bad = MarketSim.gateChecks(anchors, st)
        .count((_, ok, cls) => !ok && MarketSim.GateDefault.contains(cls))
      val rows = MarketSim.fitness(anchors, st, ex)._2
      val raw = rows.map((nm, _, _, term) => (term, nm)).max
      Read(bad == 0, math.max(0.0, raw._1 - dead), raw._1, raw._2)
    }
    val hardest = reads.maxBy(_.raw)
    Read(reads.forall(_.feasible), hardest.score, hardest.raw, hardest.worst)

  // ---- checkpoint ---------------------------------------------------------------------------
  // Plain TSV, one member per row, dial columns in `CalibrateRanges` order.  A days-long run that
  // cannot be killed and resumed is a run nobody will start.

  /** THE SETTINGS ARE PART OF THE CHECKPOINT.  An archive says which worlds were kept; without
    * the ensemble and the dials that judged them it does not say what "kept" meant, cannot be
    * reproduced, and silently mixes standards if a resume changes a flag.  Key/value so a reader
    * and a later version can both cope with a row they do not recognise. */
  def writeArchive(dir: String, arc: Vector[Member], gen: Int, evals: Int,
                   cfg: Vector[(String, String)]): Unit =
    val header = (Vector("name", "score", "raw", "worstRow") ++ names).mkString("\t")
    val rows = arc.map(m => (Vector(m.name, f"${m.score}%.6f", f"${m.raw}%.6f", m.worst) ++
                             m.dials.map(x => f"$x%.8g")).mkString("\t"))
    s"$dir/archive.tsv".asPath.writeLines(header +: rows)
    val body = (("gen", gen.toString) +: ("evals", evals.toString) +: cfg).map((k, v) => s"$k\t$v")
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
      Member(f(0), f.drop(4).map(_.toDouble), f(1).toDouble, f(2).toDouble, f(3))
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
      MarketSim.worldJsonBody(w).mkString("\n") + "\n    }\n  }"
    }
    file.asPath.writeLines(Seq("[", bodies.mkString(",\n"), "]"))
    println(s"wrote ${arc.length} worlds to $file")

  /** Admit a feasible candidate: it replaces the nearest member inside `sep` when it scores
    * better, otherwise it joins, and the archive is trimmed worst-first.  Nearness is decided in
    * DIAL space, because two mechanisms that read alike are exactly the pair worth keeping both
    * of. */
  def admit(arc: Vector[Member], m: Member, sep: Double, keep: Int): Vector[Member] =
    val near = arc.indexWhere(o => apart(o.dials, m.dials) < sep)
    val next =
      if near < 0 then arc :+ m
      else if m.score < arc(near).score then arc.updated(near, m)
      else arc
    if next.length <= keep then next else next.sortBy(_.score).take(keep)

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
                          "seeds" -> (if seedSpec.isEmpty then "(all)" else seedSpec))
    val prior = readState(out)
    val loaded = readArchive(out)
    val gen0 = prior.getOrElse("gen", "0").toInt
    val evals0 = prior.getOrElse("evals", "0").toInt
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
          if r.feasible then Some(Member(nm, dialsOf(w), r.score, r.raw, r.worst)) else None
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
      val header = (Vector("name", "score", "raw", "worstRow") ++ names).mkString("\t")
      s"$out/dropped.tsv".asPath.writeLines(
        (header + "\ttrainPass\tfreshPass\tfreshRaw\tfreshWorst") +:
        gone.map((m, r) => (Vector(m.name, f"${m.score}%.6f", f"${m.raw}%.6f", m.worst) ++
                            m.dials.map(x => f"$x%.8g") ++ Vector(r(1), r(3), r(4), r(5))).mkString("\t")))
      writeArchive(out, keptBoth.map(_._1), gen0, evals0, settings)
      println(s"archive pruned to ${keptBoth.length}; ${gone.length} moved to $out/dropped.tsv")
      sys.exit(0)

    // THE HOLDOUT.  Every member earned its place on seeds the search itself chose, and a world
    // sitting near a band edge flips on roughly one draw in six, so some members are in on a
    // lucky pair.  Re-score each one TWICE at the same ensemble: once on the training stream, as
    // a control that isolates the ensemble, and once on a stream selection never touched.  A
    // member that passes the first and fails the second was fitted to its own test.
    if holdout > 0 then
      if loaded.isEmpty then usage(s"no archive in $out to re-score")
      val train = (0 until holdout).toVector.map(k => base + k * 1000003L)
      val fresh = (0 until holdout).toVector.map(k => base + 991L + k * 1000003L)
      println(s"re-scoring ${loaded.length} members on $holdout training and $holdout fresh " +
              s"seeds at $paths x ${years}y, $anchorSpec")
      val out2 = loaded.map { m =>
        val w = worldOf(worldFor(m.name), m.dials)
        val a = evaluate(w, anchors, paths, years, train, deadZone(dead))
        val b = evaluate(w, anchors, paths, years, fresh, deadZone(dead))
        println(f"  ${m.name}%-22s train ${if a.feasible then "pass" else "FAIL"}%-4s " +
                f"raw ${a.raw}%6.3f   fresh ${if b.feasible then "pass" else "FAIL"}%-4s " +
                f"raw ${b.raw}%6.3f   ${b.worst}")
        (m, a, b)
      }
      val kept = out2.count((_, a, b) => a.feasible && b.feasible)
      val lucky = out2.count((_, a, b) => a.feasible && !b.feasible)
      val both = out2.count((_, a, b) => !a.feasible && !b.feasible)
      s"$out/holdout.tsv".asPath.writeLines(
        "name\ttrainPass\ttrainRaw\tfreshPass\tfreshRaw\tfreshWorst" +:
        out2.map((m, a, b) => f"${m.name}\t${a.feasible}\t${a.raw}%.6f\t${b.feasible}\t" +
                              f"${b.raw}%.6f\t${b.worst}"))
      println(f"\nsurvives both: $kept%3d of ${loaded.length}%d")
      println(f"lucky seeds   : $lucky%3d  (passed the training stream, failed a fresh one)")
      println(f"fails both    : $both%3d  (the ensemble here differs from the one that built it)")
      println(s"wrote $out/holdout.tsv")
      sys.exit(0)

    writeArchive(out, startArc, gen0, evals0, settings)
    if !s"$out/log.tsv".asPath.exists then
      s"$out/log.tsv".asPath.writeLines(Seq("gen\teval\tparent\tfeasible\tscore\traw\tworstRow\tseconds"))

    /** One generation: `pop` mutations of members drawn from the archive, then a checkpoint.  The
      * archive is passed and returned rather than mutated, and a kill between generations loses
      * at most one generation's work. */
    def generation(arc: Vector[Member], g: Int, evals: Int): (Vector[Member], Int) =
      val rng = new Random(base ^ (g.toLong * 0x9e3779b9L))
      val start = (arc, evals, Vector.empty[String])
      val (next, used, log) = (0 until pop).foldLeft(start) { case ((acc, ev, lg), k) =>
        val parent = acc(rng.nextInt(acc.length))
        val child = parent.dials.indices.toVector.map { i =>
          clamped(i, parent.dials(i) + rng.nextGaussian() * sigma * (ranges(i)._3 - ranges(i)._2))
        }
        val t0 = System.nanoTime()
        val r = evaluate(worldOf(worldFor(parent.name), child), anchors, paths, years,
                         (0 until reps).toVector.map(j => base + (ev + j) * 7919L), deadZone(dead))
        val secs = (System.nanoTime() - t0) / 1e9
        val line = f"$g\t${ev + k}\t${parent.name}\t${r.feasible}\t${r.score}%.6f\t${r.raw}%.6f" +
                   f"\t${r.worst}\t$secs%.1f"
        val grown = if r.feasible then
                      admit(acc, Member(parent.name, child, r.score, r.raw, r.worst), sep, keep)
                    else acc
        (grown, ev + reps, lg :+ line)
      }
      appendLog(out, log)
      writeArchive(out, next, g + 1, used, settings)
      println(f"gen $g%5d  archive ${next.length}%3d  best ${next.map(_.score).min}%7.3f  " +
              f"median ${MarketSim.pctile(next.map(_.score), 0.5)}%7.3f  " +
              f"raw ${next.map(_.raw).min}%7.3f  evals $used%6d")
      (next, used)

    // `-gens 0` runs until killed; the checkpoint after every generation is what makes that safe.
    var arc = startArc; var g = gen0; var evals = evals0
    while gens == 0 || g < gen0 + gens do
      val (a2, e2) = generation(arc, g, evals)
      arc = a2; evals = e2; g += 1

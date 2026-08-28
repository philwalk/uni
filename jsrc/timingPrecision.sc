#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.21.0

// TIMING PRECISION — is a rotation schedule's timing better than nearby timing, or is it luck?
//
//     scala-cli run jsrc/timingPrecision.sc -- -dir <emitted-paths> -mode all
//
// The question is narrow and the name says which one it is.  Given funds AA, BB, CC and rotation
// times T1, T2, this asks whether THOSE MOMENTS beat moments a few sessions either side.  It does
// not ask whether rotating beat staying invested (folio's untimed twin asks that, by pinning every
// decision at maximum risk), and it does not ask whether the RULE has skill, because the rule is
// never re-run against the perturbed data.  Three different nulls, three different questions; the
// only way they stay distinguishable is by not sharing a name.
//
// WHAT IS BEING SELECTED.  "Jitter the boundaries and compensate the exposure" is a FAMILY of
// estimators, not one.  Rescale a shortened segment or slide a fixed-length window; reject a
// crossing draw or clamp it; jitter each boundary independently or shift them together.  Several
// are defensible on paper and the differences are empirical, so this harness is the selection
// mechanism rather than a check applied afterwards:
//
//   -mode check   the arithmetic identities every variant must satisfy.  A calibration failure
//                 otherwise cannot be told from a coding error.
//   -mode calib   p-values on marketSim paths.  With `-sched random` the boundaries are drawn
//                 without looking at the series, so nothing can make them better or worse than
//                 their neighbours and p MUST be uniform.  This kills variants that are biased.
//                 With `-sched momentum` it is NOT a calibration test and must not be read as one:
//                 marketSim carries momentum and mean reversion, so a rule keyed to them can time
//                 genuinely badly, and a lean is then a reading about the rule.  "Run the real rule
//                 on synthetic data and p must still be uniform" sounds right and is wrong -- it
//                 holds only where the world has no structure the rule responds to.
//   -mode power   p-values as a known amount of skill is blended in.  This ranks the survivors.
//   -mode invar   does the compensation actually hold exposure constant, and does the null total
//                 still covary with how long the high-drift fund was held?
//
// THE ALGEBRA WORTH KNOWING BEFORE READING THE OUTPUT.  Write L_f for a fund's cumulative log NAV.
// For the uncompensated null the difference between the actual and a perturbed schedule collapses
// to a sum over the interior boundaries alone:
//
//     G - G' = SUM_i [ (L_after(T_i') - L_after(T_i)) - (L_before(T_i') - L_before(T_i)) ]
//
// -- over each CONTESTED interval, the return of the fund you moved into minus the fund you moved
// out of.  Two consequences the harness measures rather than assumes.  The comparison is already
// exposure-neutral in the difference, so with a symmetric jitter the drift-exposure effect enters
// the null's VARIANCE and not its mean; the mean bias the compensation is aimed at appears only
// when the jitter becomes asymmetric, which is exactly what rejecting crossing draws does near a
// tight rotation.  And `contested` gives a per-boundary attribution the total cannot.
//
// WHAT THE SELECTION FOUND.  Measured over 1200-1600 windows of marketSim paths at the 0.21.0
// defaults, and again at `-drift 0.16` for the wider fund-to-fund drift gap.
//
//   1. THE REFERENCE SET MUST NOT BE CENTRED ON THE OBSERVATION.  Jittering symmetrically about the
//      observed boundaries -- the obvious construction -- cannot produce a uniform p and no choice
//      of compensation rescues it: p<.05 reads 0.023-0.035 against 0.05.  Randomizing the anchor
//      fixes every kind at once.  This axis outranks every other choice here.
//   2. REJECTING CROSSING DRAWS IS ANTI-CONSERVATIVE.  It looks like the careful option and is the
//      dangerous one.  With rotations tight enough to discard 42% of draws it reads p<.05 at 0.082
//      against 0.05 -- false skill, not missed skill -- while clamping stays uniform.  Truncating
//      the jitter distribution asymmetrically is what does it, so the failure grows exactly where
//      rotations are closest together and an honest answer matters most.
//   3. THE EXPOSURE COMPENSATION IS NOT NEEDED, AND COSTS POWER.  It does what it claims: the
//      correlation between the null's total and its day count in the high-drift fund falls from
//      0.20 to 0.00.  But the DIFFERENCE between the actual and a null is already exposure-neutral
//      (see the decomposition above), so with a symmetric jitter there is no mean bias to remove,
//      and rescaling a shortened holding by nominal/actual amplifies that holding's noise.  Raw
//      detects injected skill at 0.47 where Rescale reads 0.29, and the gap WIDENS as the drift gap
//      grows -- the case the compensation exists for is the case it loses by most.
//
// SELECTED: Raw + clamp + randomized, for power.  Its 5% tail reads 0.060 (about 2 sampling sd
// high), so re-measure the tail before resting a decision on p exactly at 0.05.  Slide + randomized
// is the conservative alternative -- tail exact at 0.050, roughly 60% of the power, and immune to
// the crossing rule because fixed-length holdings cannot reorder.
//
// THE THREE FUNDS come from an emitted marketSim path and need no API change: AA = `price`,
// BB = `bond` (its own market, its own drift and volatility, the flight-to-safety correlation),
// CC = cash accumulated from `rate` as log1p(rate)/252 -- the model's own `Safe.Cash` convention,
// so this harness and the simulator cannot disagree about what cash is.  Generate them with
//
//     market_sim -emitall -paths 200 -years 20 -emit <dir>/p

import uni.*
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

object TimingPrecision {
  def println(s: String = ""): Unit = print(s"$s\n")
  def eprintln(s: String = ""): Unit = System.err.print(s"$s\n")

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "-dir D        ; directory of emitted marketSim paths (the TSVs, not the sidecars)",
    "-mode M       ; check | calib | power | invar | all   (default all)",
    "",
    "  the window and the schedule under test:",
    "-window W     ; sessions per test window; each path is cut into non-overlapping ones",
    "              ;   (default 756, i.e. 3 years)",
    "-rotations R  ; rotation times per window, so R+1 holdings (default 2, i.e. AA/BB/CC)",
    "-sched S      ; random | momentum   how the rotation times are chosen (default random).",
    "              ;   random is the only one that makes -mode calib a calibration test; momentum",
    "              ;   measures a rule against this world and can lean for real reasons",
    "-lookback L   ; trailing window the momentum rule ranks funds over (default 60)",
    "-minseg N     ; shortest admissible holding, in sessions (default 20)",
    "",
    "  the null:",
    "-jitter J     ; boundaries move uniformly on [-J, +J] sessions (default 20)",
    "-draws B      ; null draws per window; p is (1 + #{null >= actual}) / (kept + 1)",
    "              ;   (default 399, so p lands on a 1/400 grid)",
    "-seed S       ; RNG seed for jitters and random schedules (default 20260828)",
    "-paths N      ; use only the first N files in -dir (default: all)",
    "",
    "  -mode power only:",
    "-radius R     ; how far the oracle may move a boundary when skill is injected (default 30)",
    "-alphas A,B,C ; skill blend fractions to sweep (default 0,0.25,0.5,0.75,1)",
  )

  /** Trading days per year, matching the model's own `DaysPerYear`. */
  val DaysPerYear = 252

  def intOr(flag: String, v: String): Int =
    v.toIntOption.getOrElse(usage(s"$flag wants an integer, got [$v]"))

  def numOr(flag: String, v: String): Double =
    v.toDoubleOption.getOrElse(usage(s"$flag wants a number, got [$v]"))

  // ---- the panel ------------------------------------------------------------------------------

  /** One fund's cumulative log NAV, rebased so `cum(0) == 0`.  Holding it cumulatively rather than
    * as returns is what makes B jitter draws cheap: a holding's log return over `[from, until)` is
    * one subtraction regardless of how long the holding is. */
  final case class Fund(name: String, cum: Vector[Double]):
    def ret(from: Int, until: Int): Double = cum(until) - cum(from)

  /** `n` is the number of RETURN PERIODS, so a boundary is any index in `0..n`. */
  final case class Panel(funds: Vector[Fund], n: Int):
    def window(from: Int, len: Int): Panel =
      Panel(funds.map(f => Fund(f.name, f.cum.slice(from, from + len + 1).map(_ - f.cum(from)))), len)

  def cumOf(level: Vector[Double]): Vector[Double] =
    level.sliding(2).map(w => math.log(w(1) / w(0))).toVector.scanLeft(0.0)(_ + _)

  /** AA/BB/CC from one emitted path.  Cash uses the simulator's own convention. */
  def loadPanel(file: String): Panel =
    val rows = file.asPath.lines.toVector.drop(1).map(_.split("\t", -1)).filter(_.length >= 4)
    val eq   = cumOf(rows.map(_(1).toDouble))
    val bd   = cumOf(rows.map(_(2).toDouble))
    val csh  = rows.map(_(3).toDouble).init.map(r => math.log1p(r) / DaysPerYear).scanLeft(0.0)(_ + _)
    Panel(Vector(Fund("AA equity", eq), Fund("BB bond", bd), Fund("CC cash", csh)), eq.length - 1)

  def windowsOf(p: Panel, len: Int): Vector[Panel] =
    (0 until (p.n / len)).map(k => p.window(k * len, len)).toVector

  // ---- the schedule ---------------------------------------------------------------------------

  /** `order(j)` is held on `[bound(j), bound(j+1))`; `bound` runs from 0 to the window's end. */
  final case class Schedule(bound: Vector[Int], order: Vector[Int]):
    def segs: Int = order.length
    def nominalLen(j: Int): Int = bound(j + 1) - bound(j)

  /** The outer bounds are never jittered, so admissibility is only about ordering and length. */
  def strictlyOk(b: Vector[Int], minSeg: Int): Boolean =
    b.sliding(2).forall(w => w(1) - w(0) >= minSeg)

  /** Left-to-right clamp: each interior boundary is pushed just far enough to keep every holding,
    * including the ones still to come, at least `minSeg` long. */
  def clampBounds(raw: Vector[Int], minSeg: Int): Vector[Int] =
    val k = raw.length - 1
    val (lo, hi) = (raw.head, raw.last)
    raw.indices.foldLeft(Vector.empty[Int]) { (acc, i) =>
      if i == 0 then acc :+ lo
      else if i == k then acc :+ hi
      else acc :+ math.max(acc.last + minSeg, math.min(hi - (k - i) * minSeg, raw(i)))
    }

  /** The trailing-`lookback` leader at each session, and the sessions where it changes.  A real
    * rotation rule places its boundaries at non-random points in the series -- which is the whole
    * reason to test with it as well as with random times: an estimator can be uniform on boundaries
    * spread evenly and biased on boundaries that cluster after drawdowns. */
  def momentumSchedule(p: Panel, lookback: Int, rotations: Int, minSeg: Int): Option[Schedule] =
    if p.n <= lookback + (rotations + 1) * minSeg then None
    else
      val leadAt = (lookback to p.n).map(i =>
        p.funds.indices.maxBy(f => p.funds(f).cum(i) - p.funds(f).cum(i - lookback)))
      val start = (lookback, leadAt.head, Vector.empty[Int], Vector(leadAt.head))
      val (_, _, bs, os) = leadAt.indices.foldLeft(start) { case (acc @ (last, cur, bnds, ords), k) =>
        val i = lookback + k
        val lead = leadAt(k)
        val room = i - last >= minSeg && p.n - i >= (rotations - bnds.length) * minSeg
        if bnds.length < rotations && lead != cur && room then (i, lead, bnds :+ i, ords :+ lead)
        else acc
      }
      if bs.length < rotations then None else Some(Schedule(0 +: bs :+ p.n, os))

  def randomSchedule(p: Panel, rotations: Int, minSeg: Int, funds: Int,
                     rng: scala.util.Random): Option[Schedule] =
    if p.n < (rotations + 1) * minSeg then None
    else
      val slack = p.n - (rotations + 1) * minSeg
      val cuts  = Vector.fill(rotations)(rng.nextInt(slack + 1)).sorted
      val bnds  = cuts.zipWithIndex.map((c, j) => c + (j + 1) * minSeg)
      val ords  = (0 to rotations).foldLeft(Vector.empty[Int]) { (acc, _) =>
        acc :+ Iterator.continually(rng.nextInt(funds)).find(f => !acc.lastOption.contains(f)).get
      }
      Some(Schedule(0 +: bnds :+ p.n, ords))

  // ---- the estimator family -------------------------------------------------------------------

  /** What a variant does with a draw that would reorder the boundaries or make a holding too short.
    * Rejecting looks harmless and is not: it truncates the jitter distribution asymmetrically
    * wherever two rotations are close together, which is precisely where an honest answer matters
    * most.  Both are measured rather than argued about. */
  enum Crossing:
    case Reject, Clamp

  /** WHERE THE REFERENCE SET SITS, and the axis that decides whether any of this is a test at all.
    *
    * `Centered` is the construction the design starts from: jitter symmetrically about the observed
    * boundaries.  It cannot be uniform, and no choice of compensation rescues it.  The orbit
    * `{T + t}` is centred on T, so wherever the local landscape is monotone -- which is most of the
    * time, over a few sessions -- the observed lands at its own orbit's MEDIAN by construction.
    * Measured: mean p 0.50 with p<.05 at 0.023-0.035 against the 0.05 it must be.  Conservative, so
    * it under-detects rather than over-detects, but it is not a valid p-value.
    *
    * `Randomized` fixes it by drawing a per-boundary anchor u ~ U[-J, J] once per window and
    * drawing the nulls from U[-J-u, J-u].  The observed offset 0 is still inside that set, but its
    * POSITION in the set is uniform rather than always central, which is exactly the exchangeability
    * the rank argument needs. */
  enum Anchor:
    case Centered, Randomized

  enum Kind:
    /** No compensation.  Present as the control: it must FAIL the exposure test. */
    case Raw
    /** Tiling kept, each holding's log return scaled by nominal/actual length. */
    case Rescale
    /** Each holding keeps its nominal LENGTH and slides.  Exposure is constant by construction and
      * nothing is rescaled, at the price of a schedule that gaps and overlaps -- real returns on a
      * timeline nobody could have traded, where Rescale is a real timeline with scaled returns. */
    case Slide
    /** One jitter applied to every boundary, so interior holdings keep their length exactly.  Asks
      * whether the schedule's PHASE is right rather than each boundary independently. */
    case CommonShift

  final case class Hold(fund: Int, from: Int, until: Int, weight: Double):
    def days: Int = until - from

  final case class Variant(kind: Kind, crossing: Crossing, anchor: Anchor):
    def name: String =
      val c = if kind == Kind.Slide then "-" else crossing.toString.toLowerCase
      f"${kind.toString}%-11s $c%-6s ${anchor.toString.toLowerCase}%-10s"

  val Variants: Vector[Variant] =
    val shapes = Vector(Kind.Raw, Kind.Rescale, Kind.CommonShift).flatMap(k =>
      Vector(Crossing.Reject, Crossing.Clamp).map(c => (k, c))) :+ ((Kind.Slide, Crossing.Reject))
    Vector(Anchor.Centered, Anchor.Randomized).flatMap(a => shapes.map((k, c) => Variant(k, c, a)))

  def effectiveJit(k: Kind, jit: Vector[Int]): Vector[Int] = k match
    case Kind.CommonShift => Vector.fill(jit.length)(jit.headOption.getOrElse(0))
    case _                => jit

  /** The holdings a variant produces from the nominal schedule and one jitter draw.  `None` means
    * the draw is inadmissible and is discarded -- counted, so the rejection rate is visible. */
  def holds(v: Variant, s: Schedule, jit: Vector[Int], n: Int, minSeg: Int): Option[Vector[Hold]] =
    val eff = effectiveJit(v.kind, jit)
    val raw = s.bound.indices.map(i =>
      if i == 0 || i == s.segs then s.bound(i) else s.bound(i) + eff(i - 1)).toVector
    v.kind match
      case Kind.Slide =>
        // A holding slides by the mean of its two boundary jitters, keeping its nominal length.
        // The schedule's outer bounds are not jittered, so the first and last holdings slide by
        // half as much -- and they slide OFF the schedule, into the margin the panel carries for
        // exactly this reason.  Sliding cannot cross, since lengths are fixed; it can only run out
        // of data, which is what the margin prevents and this guard still checks.
        val hs = s.order.indices.map { j =>
          val lo = if j == 0 then 0 else eff(j - 1)
          val hi = if j == s.segs - 1 then 0 else eff(j)
          val at = s.bound(j) + math.round((lo + hi) / 2.0).toInt
          Hold(s.order(j), at, at + s.nominalLen(j), 1.0)
        }.toVector
        if hs.forall(h => h.from >= 0 && h.until <= n) then Some(hs) else None
      case _ =>
        val b = v.crossing match
          case Crossing.Clamp  => clampBounds(raw, minSeg)
          case Crossing.Reject => raw
        if !strictlyOk(b, minSeg) then None
        else Some(s.order.indices.map { j =>
          val len = b(j + 1) - b(j)
          val wt  = if v.kind == Kind.Raw then 1.0 else s.nominalLen(j).toDouble / len.toDouble
          Hold(s.order(j), b(j), b(j + 1), wt)
        }.toVector)

  def total(p: Panel, hs: Vector[Hold]): Double =
    hs.map(h => h.weight * p.funds(h.fund).ret(h.from, h.until)).sum

  /** The per-boundary decomposition of the UNCOMPENSATED difference: over each contested interval,
    * the fund moved into minus the fund moved out of.  Exact, so `-mode check` uses it to confirm
    * the Raw variant rather than to replace it. */
  def contested(p: Panel, s: Schedule, jit: Vector[Int]): Vector[Double] =
    (1 until s.segs).map { i =>
      val t = s.bound(i); val u = t + jit(i - 1)
      val after = p.funds(s.order(i)); val before = p.funds(s.order(i - 1))
      (after.cum(u) - after.cum(t)) - (before.cum(u) - before.cum(t))
    }.toVector

  // ---- p-values -------------------------------------------------------------------------------

  final case class PRes(p: Double, actual: Double, nulls: Vector[Double], days: Vector[Vector[Int]],
                        drawn: Int)

  def drawJit(segs: Int, jitter: Int, rng: scala.util.Random): Vector[Int] =
    Vector.fill(segs - 1)(rng.nextInt(2 * jitter + 1) - jitter)

  /** One null draw, offset by this window's anchor so the observed schedule's position in the
    * reference set is uniform rather than always central. */
  def drawAt(anchor: Vector[Int], jitter: Int, rng: scala.util.Random): Vector[Int] =
    anchor.map(u => rng.nextInt(2 * jitter + 1) - jitter - u)

  /** `(1 + #{null >= actual}) / (kept + 1)`, never `#/kept`: the second can return exactly zero and
    * is anti-conservative, while the first is exactly uniform on the grid it can reach. */
  def pvalue(v: Variant, p: Panel, s: Schedule, jitter: Int, draws: Int, minSeg: Int,
             rng: scala.util.Random): Option[PRes] =
    val anchor = v.anchor match
      case Anchor.Centered   => Vector.fill(s.segs - 1)(0)
      case Anchor.Randomized => drawJit(s.segs, jitter, rng)
    holds(v, s, Vector.fill(s.segs - 1)(0), p.n, minSeg).map(total(p, _)).flatMap { act =>
      val kept = (0 until draws).flatMap(_ =>
        holds(v, s, drawAt(anchor, jitter, rng), p.n, minSeg)).toVector
      if kept.length < draws / 2 then None
      else
        val tot  = kept.map(total(p, _))
        val dys  = p.funds.indices.map(f => kept.map(_.filter(_.fund == f).map(_.days).sum)).toVector
        Some(PRes((1 + tot.count(_ >= act)).toDouble / (tot.length + 1), act, tot, dys, draws))
    }

  // ---- summary statistics ---------------------------------------------------------------------

  def mean(v: Vector[Double]): Double = if v.isEmpty then Double.NaN else v.sum / v.length

  def sd(v: Vector[Double]): Double =
    if v.length < 2 then Double.NaN
    else
      val m = mean(v)
      math.sqrt(v.map(x => (x - m) * (x - m)).sum / (v.length - 1))

  def corr(a: Vector[Double], b: Vector[Double]): Double =
    val (sa, sb) = (sd(a), sd(b))
    if sa.isNaN || sb.isNaN || sa <= 0.0 || sb <= 0.0 then Double.NaN
    else
      val (ma, mb) = (mean(a), mean(b))
      a.zip(b).map((x, y) => (x - ma) * (y - mb)).sum / ((a.length - 1) * sa * sb)

  /** Kolmogorov-Smirnov distance from U(0,1).  The 5% critical value is 1.36/sqrt(N). */
  def ksUniform(ps: Vector[Double]): Double =
    if ps.isEmpty then Double.NaN
    else
      val s = ps.sorted; val n = s.length.toDouble
      s.zipWithIndex.map((x, i) => math.max((i + 1) / n - x, x - i / n)).max

  def share(ps: Vector[Double], t: Double): Double =
    if ps.isEmpty then Double.NaN else ps.count(_ <= t).toDouble / ps.length

  // ---- run configuration ----------------------------------------------------------------------

  final case class Cfg(dir: String, mode: String, window: Int, rotations: Int, sched: String,
                       lookback: Int, minSeg: Int, jitter: Int, draws: Int, seed: Long,
                       paths: Int, radius: Int, alphas: Vector[Double]):
    /** Sessions of data carried either side of the schedule, so a holding can slide without
      * running out of series.  A real portfolio's funds exist before and after its own window;
      * without the margin here, Slide would reject three draws in four and look untestable when
      * the only thing wrong was the fixture. */
    def margin: Int = jitter
    def panelLen: Int = window + 2 * margin

  def files(cfg: Cfg): Vector[String] =
    val all = Files.list(cfg.dir.asPath).iterator.asScala.toVector.map(_.toString)
      .filter(f => !f.endsWith(".json")).sorted
    if cfg.paths <= 0 then all else all.take(cfg.paths)

  /** The schedule is chosen on the inner window only -- the margin exists for the null to move
    * into, and a rule allowed to see it would be choosing boundaries the actual run could not. */
  def scheduleFor(cfg: Cfg, w: Panel, rng: scala.util.Random): Option[Schedule] =
    val inner = w.window(cfg.margin, cfg.window)
    val s =
      if cfg.sched == "random" then randomSchedule(inner, cfg.rotations, cfg.minSeg, w.funds.length, rng)
      else momentumSchedule(inner, cfg.lookback, cfg.rotations, cfg.minSeg)
    s.map(sc => Schedule(sc.bound.map(_ + cfg.margin), sc.order))

  /** Every test window in the run, with the schedule under test on it. */
  def cases(cfg: Cfg, rng: scala.util.Random): Vector[(Panel, Schedule)] =
    files(cfg).flatMap(f => windowsOf(loadPanel(f), cfg.panelLen))
      .flatMap(w => scheduleFor(cfg, w, rng).map((w, _)))

  def header(cfg: Cfg, n: Int): Unit =
    val how = if cfg.sched == "random" then "random times" else s"momentum(${cfg.lookback})"
    eprintln(s"$n windows of ${cfg.window} sessions (+/-${cfg.margin} margin), " +
             s"${cfg.rotations} rotations, $how, jitter +/-${cfg.jitter}, ${cfg.draws} draws")

  // ---- mode: check ----------------------------------------------------------------------------

  /** The arithmetic every variant must satisfy, checked before any p-value is believed.  Without
    * this a calibration failure cannot be told from a coding error, and the usual outcome is
    * discarding a sound design because of a bug -- or keeping an unsound one because two bugs
    * cancelled. */
  def runCheck(cfg: Cfg, cs: Vector[(Panel, Schedule)]): Unit =
    val rng = new scala.util.Random(cfg.seed)
    println("IDENTITIES — the arithmetic, before any p-value is believed.")
    println()
    val sample = cs.take(200)

    val zeroDev = sample.flatMap { (p, s) =>
      val ref = total(p, holds(Variant(Kind.Raw, Crossing.Reject, Anchor.Centered), s, Vector.fill(s.segs - 1)(0),
                               p.n, cfg.minSeg).get)
      Variants.map(v => math.abs(total(p, holds(v, s, Vector.fill(s.segs - 1)(0), p.n,
                                                cfg.minSeg).get) - ref))
    }
    report("zero jitter reproduces the actual schedule, in every variant", zeroDev.max, 1e-12)

    val contestDev = sample.map { (p, s) =>
      val jit = drawJit(s.segs, cfg.jitter, rng)
      holds(Variant(Kind.Raw, Crossing.Reject, Anchor.Centered), s, jit, p.n, cfg.minSeg) match
        case None => 0.0
        case Some(h) =>
          val act = total(p, holds(Variant(Kind.Raw, Crossing.Reject, Anchor.Centered), s,
                                   Vector.fill(s.segs - 1)(0), p.n, cfg.minSeg).get)
          math.abs((act - total(p, h)) - contested(p, s, jit).sum)
    }
    report("Raw's difference equals the per-boundary contested decomposition", contestDev.max, 1e-10)

    val expDev = sample.map { (p, s) =>
      val jit = drawJit(s.segs, cfg.jitter, rng)
      holds(Variant(Kind.Rescale, Crossing.Reject, Anchor.Centered), s, jit, p.n, cfg.minSeg) match
        case None => 0.0
        case Some(h) => h.indices.map(j => math.abs(h(j).weight * h(j).days - s.nominalLen(j))).max
    }
    report("Rescale's weighted exposure equals nominal, per holding", expDev.max, 1e-9)

    val slideDev = sample.map { (p, s) =>
      val jit = drawJit(s.segs, cfg.jitter, rng)
      holds(Variant(Kind.Slide, Crossing.Reject, Anchor.Centered), s, jit, p.n, cfg.minSeg) match
        case None => 0.0
        case Some(h) => h.indices.map(j => math.abs(h(j).days - s.nominalLen(j)).toDouble).max
    }
    report("Slide's holdings keep their nominal length exactly", slideDev.max, 0.0)

    val symDev = sample.map { (p, s) =>
      val jit = drawJit(s.segs, cfg.jitter, rng)
      val v = Variant(Kind.Raw, Crossing.Reject, Anchor.Centered)
      (holds(v, s, jit, p.n, cfg.minSeg), holds(v, s, jit.map(-_), p.n, cfg.minSeg)) match
        case (Some(a), Some(b)) =>
          val act = total(p, holds(v, s, Vector.fill(s.segs - 1)(0), p.n, cfg.minSeg).get)
          math.abs((total(p, a) - act) + (total(p, b) - act) -
                   (contested(p, s, jit).sum * -1.0 - contested(p, s, jit.map(-_)).sum))
        case _ => 0.0
    }
    report("mirrored jitters agree with the mirrored decomposition", symDev.max, 1e-10)
    println()

  def report(what: String, dev: Double, tol: Double): Unit =
    val ok = if dev <= tol then "OK  " else "FAIL"
    println(f"  $ok  $what%-62s  max dev ${dev}%.3e")

  // ---- mode: calib ----------------------------------------------------------------------------

  def runCalib(cfg: Cfg, cs: Vector[(Panel, Schedule)]): Unit =
    if cfg.sched == "random" then
      println("CALIBRATION — boundaries drawn without looking at the series, so nothing can make the")
      println("actual schedule better or worse than its neighbours.  p MUST be uniform here; a variant")
      println("that leans is biased, and nothing it measured on real data would be readable.")
    else
      println(s"MEASUREMENT — momentum(${cfg.lookback}) boundaries.  This is NOT a calibration test:")
      println("marketSim worlds carry momentum and mean reversion, so a rule keyed to them can time")
      println("genuinely well or genuinely badly.  A lean here is a reading about the RULE.  Use")
      println("-sched random for the calibration test, where no such reading is possible.")
    println()
    println(f"  ${"variant"}%-19s ${"mean p"}%7s ${"p<.05"}%7s ${"p<.10"}%7s ${"p<.25"}%7s " +
            f"${"p<.50"}%7s ${"KS"}%7s ${"kept"}%6s  verdict")
    val crit = 1.36 / math.sqrt(cs.length.toDouble)
    Variants.foreach { v =>
      val rng = new scala.util.Random(cfg.seed)
      val rs  = cs.flatMap((p, s) => pvalue(v, p, s, cfg.jitter, cfg.draws, cfg.minSeg, rng))
      val ps  = rs.map(_.p)
      val ks  = ksUniform(ps)
      val kept = mean(rs.map(r => r.nulls.length.toDouble / r.drawn))
      val verdict =
        if ps.isEmpty then "NO DATA"
        else if ks <= crit then "uniform"
        else if cfg.sched == "random" then "BIASED"
        else "leans"
      println(f"  ${v.name}%-19s ${mean(ps)}%7.3f ${share(ps, 0.05)}%7.3f ${share(ps, 0.10)}%7.3f " +
              f"${share(ps, 0.25)}%7.3f ${share(ps, 0.50)}%7.3f $ks%7.3f ${kept}%6.2f  $verdict")
    }
    println(f"%n  KS is the distance from uniform; the 5%% critical value at ${cs.length} windows " +
            f"is ${crit}%.3f.")
    println("  `kept` is the share of draws that survived the crossing rule — a low value means the")
    println("  jitter distribution was truncated, which is how a rejection rule becomes a bias.")
    println()

  // ---- mode: power ----------------------------------------------------------------------------

  /** The locally best place to put each boundary, within `radius`.  Moving a boundary from t to u
    * hands the interval between them from one fund to the other, so the gain is
    * `(L_before(u) - L_before(t)) - (L_after(u) - L_after(t))` for either sign of the move. */
  def oracleBounds(p: Panel, s: Schedule, radius: Int, minSeg: Int): Vector[Int] =
    s.bound.indices.map { i =>
      if i == 0 || i == s.segs then s.bound(i)
      else
        val t = s.bound(i)
        val before = p.funds(s.order(i - 1)); val after = p.funds(s.order(i))
        val lo = math.max(s.bound(i - 1) + minSeg, t - radius)
        val hi = math.min(s.bound(i + 1) - minSeg, t + radius)
        if lo > hi then t
        else (lo to hi).maxBy(u =>
          (before.cum(u) - before.cum(t)) - (after.cum(u) - after.cum(t)))
    }.toVector

  def blended(p: Panel, s: Schedule, alpha: Double, radius: Int, minSeg: Int): Schedule =
    val orc = oracleBounds(p, s, radius, minSeg)
    val mixed = s.bound.indices.map(i =>
      math.round(s.bound(i) + alpha * (orc(i) - s.bound(i))).toInt).toVector
    Schedule(clampBounds(mixed, minSeg), s.order)

  def runPower(cfg: Cfg, cs: Vector[(Panel, Schedule)]): Unit =
    println("POWER — the same windows with a KNOWN amount of skill blended into the boundaries.")
    println("alpha 0 is the rule untouched (no skill); alpha 1 puts every boundary at the locally")
    println(s"best point within ${cfg.radius} sessions.  Cells are the share of windows with p < 0.05.")
    println()
    println(f"  ${"variant"}%-19s " + cfg.alphas.map(a => f"a=$a%-5.2f").mkString(" "))
    Variants.foreach { v =>
      val cells = cfg.alphas.map { a =>
        val rng = new scala.util.Random(cfg.seed)
        val ps = cs.flatMap { (p, s) =>
          pvalue(v, p, blended(p, s, a, cfg.radius, cfg.minSeg), cfg.jitter, cfg.draws,
                 cfg.minSeg, rng).map(_.p)
        }
        share(ps, 0.05)
      }
      println(f"  ${v.name}%-19s " + cells.map(c => f"$c%-7.3f").mkString(" "))
    }
    println()
    println("  The alpha-0 column must match the calibration table's p<.05; a variant that already")
    println("  rejects there is reading its own bias.  Read the rest as the detection floor: how")
    println("  much of the available local edge a schedule must actually capture to be seen.")
    println()

  // ---- mode: invar ----------------------------------------------------------------------------

  def runInvar(cfg: Cfg, cs: Vector[(Panel, Schedule)]): Unit =
    println("EXPOSURE — does the null total still covary with how long each fund was held?")
    println("Correlation across draws between the null's total log gain and its raw day count in")
    println("each fund, averaged over windows.  AA (equity) carries the most drift, so an")
    println("uncompensated null must show a large positive AA figure and a compensated one ~0.")
    println()
    val names = cs.head._1.funds.map(_.name)
    println(f"  ${"variant"}%-19s " + names.map(n => f"$n%11s").mkString(" ") + f"  ${"max|r|"}%7s")
    Variants.foreach { v =>
      val rng = new scala.util.Random(cfg.seed)
      val rs  = cs.flatMap((p, s) => pvalue(v, p, s, cfg.jitter, cfg.draws, cfg.minSeg, rng))
      val cols = names.indices.map(f =>
        mean(rs.map(r => corr(r.nulls, r.days(f).map(_.toDouble))).filterNot(_.isNaN))).toVector
      println(f"  ${v.name}%-19s " + cols.map(c => f"$c%11.3f").mkString(" ") +
              f"  ${cols.map(math.abs).max}%7.3f")
    }
    println()
    println("  Slide reports no correlation by construction: its day counts do not vary at all.")
    println("  A residual figure under Rescale is the part of the exposure effect that is not")
    println("  proportional to holding length — which is what the compensation cannot reach.")
    println()

  // ---- main -----------------------------------------------------------------------------------

  def main(args: Array[String]): Unit =
    var dir = ""; var mode = "all"; var window = 756; var rotations = 2
    var sched = "random"; var lookback = 60; var minSeg = 20
    var jitter = 20; var draws = 399; var seed = 20260828L; var paths = 0
    var radius = 30; var alphas = Vector(0.0, 0.25, 0.5, 0.75, 1.0)
    eachArg(args.toSeq, usage) {
      case "-dir"       => dir = consumeNext
      case "-mode"      => mode = consumeNext
      case "-window"    => window = intOr("-window", consumeNext)
      case "-rotations" => rotations = intOr("-rotations", consumeNext)
      case "-sched"     => sched = consumeNext
      case "-lookback"  => lookback = intOr("-lookback", consumeNext)
      case "-minseg"    => minSeg = intOr("-minseg", consumeNext)
      case "-jitter"    => jitter = intOr("-jitter", consumeNext)
      case "-draws"     => draws = intOr("-draws", consumeNext)
      case "-seed"      => seed = intOr("-seed", consumeNext).toLong
      case "-paths"     => paths = intOr("-paths", consumeNext)
      case "-radius"    => radius = intOr("-radius", consumeNext)
      case "-alphas"    => alphas = consumeNext.split(",").toVector.map(s => numOr("-alphas", s.trim))
      case d if dir.isEmpty && d.asPath.isDirectory => dir = d
      case a            => usage(s"unrecognized arg [$a]")
    }
    if dir.isEmpty then usage("no -dir: point it at a directory of emitted marketSim paths")
    if !dir.asPath.isDirectory then usage(s"-dir [$dir] is not a directory")
    if !Set("check", "calib", "power", "invar", "all").contains(mode) then
      usage(s"-mode [$mode] is not check, calib, power, invar or all")
    if !Set("random", "momentum").contains(sched) then usage(s"-sched [$sched] is not random or momentum")
    if rotations < 1 then usage(s"-rotations wants at least 1, got $rotations")
    if jitter < 1 then usage(s"-jitter wants at least 1 session, got $jitter")
    if draws < 20 then usage(s"-draws wants at least 20, got $draws")
    if window < (rotations + 1) * minSeg then
      usage(s"-window $window cannot hold ${rotations + 1} holdings of -minseg $minSeg")

    val cfg = Cfg(dir, mode, window, rotations, sched, lookback, minSeg, jitter, draws, seed,
                  paths, radius, alphas)
    val cs = cases(cfg, new scala.util.Random(seed))
    if cs.isEmpty then usage(s"no test windows: ${files(cfg).length} files gave no usable schedule")
    header(cfg, cs.length)
    println()
    if mode == "check" || mode == "all" then runCheck(cfg, cs)
    if mode == "calib" || mode == "all" then runCalib(cfg, cs)
    if mode == "invar" || mode == "all" then runInvar(cfg, cs)
    if mode == "power" || mode == "all" then runPower(cfg, cs)
}

TimingPrecision.main(args)

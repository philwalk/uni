#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.23.1

// LEVERED FUND — a daily-rebalanced kx fund (SSO/QLD at k=2, UPRO/TQQQ at k=3) built from an
// emitted marketSim path, plus the tail substitution the underlying needs before leverage means
// anything.
//
//     scala-cli run jsrc/leveredFund.sc -- -dir <emitted-paths> -k 3
//     scala-cli run jsrc/leveredFund.sc -- -dir <emitted-paths> -k 3 -emit <dir>   # feed the
//                                                                                 # timing harness
//
// ---------------------------------------------------------------------------------------------
// PART 1 — THE FUND.  Exact, and verified against the real funds rather than asserted:
//
//     NAV[t+1]/NAV[t] = 1 + k*r[t] - (k-1)*(rate[t] + spread)/252 - expense/252 - cost[t]
//
// Daily compounding of `k*r` carries volatility drag exactly -- there is no drag term to add, and
// adding one would double-count.  `cost[t] = k(k-1)*|r[t]|*slip*liq[t]` is the daily rebalance: a
// kx fund must trade k(k-1)|r| of notional per unit NAV every session to restore its exposure, so
// its trading cost rises with BOTH the size of the move and the market's illiquidity.  `liq` is
// marketSim's own per-session equity slippage multiplier, which is why this is the one friction
// that gets more expensive in exactly the regime that hurts.
//
// VERIFIED against Yahoo adjusted closes (2026-08-26) by regressing the fund's daily return on its
// underlying's: slope 2.957 (TQQQ/QQQ), 1.983 (QLD/QQQ), 2.989 (UPRO/SPY), 1.959 (SSO/SPY), all at
// R^2 0.991-0.998; and the financing coefficient recovers k-1 (2.12, 1.12, 2.30, 1.17) when the
// drag is regressed on the T-bill rate.  Volatility multiple, the statistic that matters most here,
// reproduces to within 1%: model 3.05x against UPRO's 3.02x, model 1.98x against SSO's 1.97x.
//
// WHICH FUND YOU ARE ACTUALLY SIMULATING.  marketSim's default world is S&P-like -- 16.5%
// volatility -- so k=3 gives UPRO, not TQQQ.  TQQQ's underlying (Nasdaq-100) runs 21-27%, and
// reaching it means a different world, not a different k.  The equity anchor fixture already
// carries a QQQ row (w2001: 23.3% vol, 12.30% return, depth 0.503/0.348/0.163) to calibrate one
// against.  Simulating TQQQ by raising k on an S&P-like world gets the leverage right and the
// asset wrong.
//
// ---------------------------------------------------------------------------------------------
// PART 2 — THE TAIL, and why the fund model is useless without it.
//
// marketSim's daily kurtosis reads 12.7-16.1 against a 28.0 anchor (CRSP 1926-2026, the century --
// NOT the 33-year windows, where SPY reads 14.4 and QQQ 9.6, and against which the model is nearly
// right).  Unlevered that is a moderate fidelity miss.  At k=3 it is the whole question, because a
// -20% session takes a 3x fund down 60% and a -33% session ends it, and the model produced ZERO
// sessions below -20% in 4,000 path-years.
//
// THE SUBSTITUTION IS VARIANCE-PRESERVING, and that is the entire trick.  The model does not need
// MORE crash magnitude -- it already runs crashes/century at 1.36x and worst crash at 1.54x of
// their anchors.  It needs the magnitude it already has delivered in fewer, more violent sessions.
// So a fraction `phi` of the return's variance is moved out of the diffusion and into a compensated
// jump, leaving total variance untouched:
//
//     r'[t] = sqrt(1-phi)*(r[t] - rbar) + rbar + J[t] - E[J]
//     J[t]  = (t_nu / sd(t_nu) - asym) * sqrt(phi*var(r)/lambdaBar)   with prob lambda[t]
//     lambda[t] = lambda0 * (trailing 20-session vol / its mean)^gamma
//
// Jump intensity rises with the local volatility state, so extreme sessions CLUSTER inside crises
// instead of scattering uniformly.  That is what a levered fund actually dies of -- consecutive
// violent sessions compounding -- and an unconditional jump process misses it entirely.
//
// nu = 5 rather than a fatter tail because t(4) has an INFINITE fourth moment: its sample kurtosis
// never converges, so a kurtosis target fitted against it is not a calibration.  Measured across
// three seeds over 4,000 path-years, nu=5 holds kurtosis to +/-1.15 where nu=6 swung +/-5.12.
//
// MEASURE IT THE WAY THE MODEL GRADES IT.  `measure` takes the MEDIAN OF PER-PATH kurtosis, not
// the kurtosis of the pooled returns, and the two are far apart here: a 20-year path rarely holds
// a jump big enough to move its own fourth moment, while the pooled statistic is dominated by the
// few largest across all paths.  At phi 0.05 pooled reads 25-31 and per-path median reads 16.9.
// Quoting the pooled number against a target the model grades per-path overstates the fix by half.
//
// WHAT IT MOVES, on the model's own statistics, as ratios to their anchors (200 paths x 20 years):
//
//     phi     vol    kurtosis   lag 1   lag 20   sessions <-20%/century
//     0.00    0.95     0.43      1.08     1.04        0.00
//     0.05    0.95     0.60      1.02     0.99        0.10
//     0.10    0.96     0.97      0.96     0.92        0.85      <-- closes it
//     0.15    0.95     1.51      0.89     0.84        1.85
//     0.20    0.95     2.37      0.83     0.78        2.70
//
// THIS CONTRADICTS THE RECORDED SCOPE DECISION, and the model's own note says why it can.  The
// header of MarketSim.scala parks the kurtosis miss as needing "a slow valuation cycle", and the
// provenance note is sharper: KURTOSIS AND CLUSTERING CANNOT BOTH BE RIGHT -- `stress` 7.5 reaches
// kurtosis 26.4 and clustering 1.67, failing its realism band.  That is true of `stress`, which is
// the only tail channel the model has.  It is not true in general, and the same note says so: "the
// cycle is why there is no SECOND channel for tails, not why this one cannot reach them."
//
// A variance-preserving jump IS that second channel, and it does not trade against clustering --
// at phi 0.10 kurtosis goes 0.43 -> 0.97 while clustering IMPROVES from 1.08/1.04 to 0.96/0.92,
// because moving variance out of the diffusion shortens the persistence the clamped volatility
// process was over-supplying.  Three graded statistics improve at once and volatility does not move.
//
// STILL UNCHECKED before calling the miss closed: crashes/century, median depth, worst crash and
// the three depth rungs all use marketSim's own episode detection, which lives in `measure` and
// cannot be reached from here.  This tool's `median max drawdown` proxy barely moves (-40.5% ->
// -42.1% at phi 0.05), which is encouraging and is not the same as having checked them.
//
// `-phi` now defaults to 0: since 0.21.0 the MODEL carries this channel at jumpVar 0.10, and
// substituting again on top of it would double-count.  The table above is what led to that value.
//
// WHAT THE FIX BUYS -- one qualitative change and almost nothing else:
//
//                        k=2               k=3
//     worst session   -32.3% -> -55.0%   -48.6% -> -82.6%   total loss becomes POSSIBLE
//     volatility       33.0%    33.2%     49.6%    50.2%
//     median max DD   -68.0%   -69.8%    -84.7%   -86.1%
//     P(DD > 95%)       4.5%     5.5%     19.0%    21.5%
//
// Read that before deciding it was not worth doing.  In this model a levered fund's TYPICAL ruin
// comes from the grinding multi-year decline, which was already calibrated; the gap session changes
// only the tail of the tail.  But a gap session is precisely the event no timing rule can act on --
// you cannot exit inside it -- so for grading a rotation strategy that promises to step aside
// before disaster, it is the only part of the distribution that tests the promise.
//
// USED FOR THAT, via `-emit`, it changed nothing about the estimator selection in
// jsrc/timingPrecision.sc: on 3x paths all seven randomized variants stay uniform (KS 0.025-0.036
// against a 0.039 critical value at 1200 windows), and Raw still out-powers Rescale by the same
// margin it does unlevered (0.144 vs 0.123 at alpha 0.75, against 0.165 vs 0.139).  Leverage was
// the case where the exposure compensation should have earned its keep, and it does not.
//
// KNOWN, and inherited rather than introduced: median max drawdown -85.5% against UPRO's realized
// -76.8%.  marketSim's own `worst crash %` sits at 1.54x its anchor, and leverage multiplies that
// error along with everything else.  Fixing it is a marketSim change, not a change here.
//
// SUPERSEDED, and the default now says so.  0.21.0 put this channel in `simulate` itself, on a
// separate RNG stream, as `jumpVar`/`jumpRate` -- properly, with feedback into stress, liquidity and
// crowding that a post-process cannot have, and calibrated against the model's own median-per-path
// kurtosis rather than the pooled statistic quoted below.  `-phi` now defaults to 0 and REFUSES to
// run on a path whose sidecar carries `jumpVar`, because substituting again on a world that already
// has the channel double-counts it silently.  What remains here is for paths emitted by a pre-0.21
// binary, and as the record of how the parameters were first found.
//
// ORIGINAL NOTE, kept because the reasoning is what carried over:  The substitution is a post-process because 0.21.0 is
// release-ready and a draw taken inside `simulate`'s loop shifts the RNG stream and moves every
// calibrated statistic in the program.  Done properly it goes in `simulate` on a SEPARATE stream
// (the pattern `-calibrate` already uses), so `phi = 0` reproduces today byte-for-byte.  Post-
// processing costs one thing that matters: the jumps cannot feed back into stress, crowding or
// liquidity, so a jump session does not trigger the spiral a real one would.  Intensity keyed to
// trailing volatility is a proxy for that feedback, not a replacement.

import uni.*
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

object LeveredFund {
  def println(s: String = ""): Unit = print(s"$s\n")
  def eprintln(s: String = ""): Unit = System.err.print(s"$s\n")

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "-dir D        ; directory of emitted marketSim paths (the TSVs, not the sidecars)",
    "-k K          ; leverage: 2 (SSO/QLD) or 3 (UPRO/TQQQ).  1 reports the underlying (default 3)",
    "-paths N      ; use only the first N files in -dir (default: all)",
    "",
    "  the fund's frictions (defaults are the real funds' published numbers):",
    "-expense E    ; annual expense ratio (default 0.0084, TQQQ; UPRO 0.0091, QLD 0.0095, SSO 0.0089)",
    "-spread S     ; financing spread over the path's own short rate (default 0.004)",
    "-slip S       ; slippage per unit of rebalance turnover, scaled by the path's liq (default 0.0002)",
    "",
    "  the tail substitution (see the header; -phi 0 disables it entirely):",
    "-phi P        ; share of return VARIANCE moved from diffusion into jumps (default 0: the",
    "              ;   MODEL carries the channel since 0.21.0, so this is only for paths emitted",
    "              ;   by an older binary.  Refuses to stack on a path whose sidecar has jumpVar)",
    "-lambda L     ; base jump intensity per session (default 0.001)",
    "-gamma G      ; how sharply intensity tracks trailing volatility (default 2.0)",
    "-nu N         ; jump tail index; must exceed 4 or kurtosis does not converge (default 5)",
    "-asym A       ; downward shift of the jump, in jump sds (default 0.4)",
    "-seed S       ; jump RNG seed (default 20260828)",
    "",
    "  output:",
    "-emit D       ; also write each path to D/ in the SAME 9-column emit schema with `price`",
    "              ;   replaced by the fund's NAV, so jsrc/timingPrecision.sc reads it unchanged",
  )

  val DaysPerYear = 252

  def intOr(flag: String, v: String): Int =
    v.toIntOption.getOrElse(usage(s"$flag wants an integer, got [$v]"))

  def numOr(flag: String, v: String): Double =
    v.toDoubleOption.getOrElse(usage(s"$flag wants a number, got [$v]"))

  // ---- the emitted path -----------------------------------------------------------------------

  /** Only the four columns this needs, plus the raw lines so `-emit` can rewrite one column and
    * leave the other eight byte-identical. */
  final case class Emitted(header: String, rows: Vector[String], price: Vector[Double],
                           rate: Vector[Double], liq: Vector[Double])

  def load(file: String): Emitted =
    val all  = file.asPath.lines.toVector
    val body = all.drop(1).filter(_.nonEmpty)
    val cols = body.map(_.split("\t", -1))
    Emitted(all.head, body, cols.map(_(1).toDouble), cols.map(_(3).toDouble), cols.map(_(5).toDouble))

  // ---- the tail substitution ------------------------------------------------------------------

  final case class Tail(phi: Double, lambda: Double, gamma: Double, nu: Double, asym: Double)

  /** Trailing RMS return over `w` sessions, back-filled over the first window so the series keeps
    * its length.  This is the state the jump intensity tracks. */
  def trailingVol(r: Vector[Double], w: Int): Vector[Double] =
    val c = r.map(x => x * x).scanLeft(0.0)(_ + _)
    val v = (w to r.length).map(i => math.sqrt(math.max((c(i) - c(i - w)) / w, 1e-12))).toVector
    Vector.fill(math.min(w, r.length))(v.head) ++ v.drop(1)

  /** A standardised Student-t draw: t_nu / sqrt(nu/(nu-2)), so it carries unit variance and the
    * jump's scale is set by `phi` alone. */
  def stdT(nu: Double, rng: scala.util.Random): Double =
    val z = rng.nextGaussian()
    val chi = (1 to nu.toInt).map(_ => { val g = rng.nextGaussian(); g * g }).sum
    z / math.sqrt(chi / nu) / math.sqrt(nu / (nu - 2.0))

  /** Move `phi` of the variance out of the diffusion and into a volatility-clustered compensated
    * jump.  Total variance and mean are both preserved, so `equity vol %` and the drift do not
    * move; what changes is the shape of a single session. */
  def substitute(r: Vector[Double], t: Tail, rng: scala.util.Random): Vector[Double] =
    if t.phi <= 0.0 then r
    else
      val mean = r.sum / r.length
      val vr   = r.map(x => (x - mean) * (x - mean)).sum / r.length
      val sv   = trailingVol(r, 20)
      val svm  = sv.sum / sv.length
      val lam  = sv.map(s => math.min(0.2, t.lambda * math.pow(s / svm, t.gamma)))
      val lamB = lam.sum / lam.length
      val scale = math.sqrt(t.phi * vr / lamB)
      val j = lam.map(l => if rng.nextDouble() < l then (stdT(t.nu, rng) - t.asym) * scale else 0.0)
      val jm = j.sum / j.length
      r.indices.map(i => math.sqrt(1.0 - t.phi) * (r(i) - mean) + mean + j(i) - jm).toVector

  // ---- the fund -------------------------------------------------------------------------------

  final case class Fund(k: Double, expense: Double, spread: Double, slip: Double)

  /** Daily log returns of the fund, from the underlying's daily log returns and the path's own
    * short rate and slippage.  Floored just above total loss: a kx fund whose NAV would go
    * negative is wound up, and log of a negative number is not a smaller loss. */
  def navReturns(f: Fund, r: Vector[Double], rate: Vector[Double], liq: Vector[Double]): Vector[Double] =
    r.indices.map { i =>
      val rs   = math.expm1(r(i))
      val fin  = (f.k - 1.0) * (rate(i) + f.spread) / DaysPerYear
      val cost = f.k * (f.k - 1.0) * math.abs(rs) * f.slip * liq(i)
      math.log1p(math.max(f.k * rs - fin - f.expense / DaysPerYear - cost, -0.999))
    }.toVector

  // ---- statistics -----------------------------------------------------------------------------

  def mean(v: Vector[Double]): Double = v.sum / v.length

  def moment(v: Vector[Double], p: Int): Double =
    val m = mean(v)
    val sd = math.sqrt(v.map(x => (x - m) * (x - m)).sum / v.length)
    v.map(x => math.pow((x - m) / sd, p.toDouble)).sum / v.length

  def annVol(v: Vector[Double]): Double =
    val m = mean(v)
    math.sqrt(v.map(x => (x - m) * (x - m)).sum / v.length * DaysPerYear) * 100.0

  /** Deepest peak-to-trough, as a simple return. */
  def maxDrawdown(r: Vector[Double]): Double =
    val (_, worst) = r.foldLeft((0.0, 0.0)) { case ((cum, w), x) =>
      val c = cum + x
      (c, math.min(w, c))
    }
    // the fold above tracks the running sum against zero; redo against the running peak
    val cum = r.scanLeft(0.0)(_ + _)
    val (_, dd) = cum.foldLeft((Double.NegativeInfinity, 0.0)) { case ((pk, d), c) =>
      val p = math.max(pk, c); (p, math.min(d, c - p))
    }
    math.expm1(math.min(dd, worst - worst))

  def pctile(v: Vector[Double], q: Double): Double =
    val s = v.sorted
    s(math.min(s.length - 1, math.max(0, math.round(q * (s.length - 1)).toInt)))

  def share(v: Vector[Double], f: Double => Boolean): Double =
    v.count(f).toDouble / v.length * 100.0

  // ---- report ---------------------------------------------------------------------------------

  final case class Case(under: Vector[Double], fund: Vector[Double])

  def underlyingRow(label: String, all: Vector[Double], perPath: Vector[Vector[Double]]): Unit =
    val s = all.map(math.expm1)
    val cy = all.length.toDouble / DaysPerYear / 100.0
    println(f"  $label%-14s ${annVol(all)}%7.2f%% ${moment(all, 4)}%8.2f ${moment(all, 3)}%7.2f " +
            f"${s.min * 100}%9.2f%% ${s.count(_ < -0.10) / cy}%9.2f ${s.count(_ < -0.20) / cy}%9.2f " +
            f"${pctile(perPath.map(maxDrawdown), 0.5) * 100}%9.1f%%")

  def fundRow(label: String, all: Vector[Double], perPath: Vector[Vector[Double]]): Unit =
    val dds = perPath.map(maxDrawdown)
    val cagr = perPath.map(p => math.expm1(p.sum / (p.length.toDouble / DaysPerYear)))
    println(f"  $label%-14s ${annVol(all)}%7.2f%% ${math.expm1(all.min) * 100}%10.2f%% " +
            f"${pctile(cagr, 0.5) * 100}%9.2f%% ${pctile(dds, 0.5) * 100}%9.1f%% " +
            f"${pctile(dds, 0.05) * 100}%9.1f%% ${share(dds, _ < -0.95)}%8.1f%% ${share(dds, _ < -0.99)}%8.1f%%")

  /** Measured 2026-08-28 from Yahoo adjusted closes through 2026-08-26, each over its own full
    * history.  Quoted rather than recomputed here so this file has no data dependency; the fund
    * windows differ, so read the VOLATILITY MULTIPLE (levered / underlying), which is comparable
    * across them, rather than the levels. */
  def realTable(): Unit =
    println("  REAL FUNDS, for scale — Yahoo adj close through 2026-08-26, each over its own history:")
    println("    fund   k  underlying   idx vol   lev vol   multiple   worst day    max DD    years")
    println("    SSO    2         SPY     19.4%     38.3%      1.97x      -23.4%    -84.7%     20.1")
    println("    UPRO   3         SPY     17.1%     51.7%      3.02x      -34.9%    -76.8%     17.1")
    println("    QLD    2         QQQ     22.1%     44.1%      1.99x      -24.3%    -83.1%     20.1")
    println("    TQQQ   3         QQQ     20.7%     61.7%      2.98x      -34.5%    -81.7%     16.5")

  // ---- main -----------------------------------------------------------------------------------

  def main(args: Array[String]): Unit =
    var dir = ""; var k = 3.0; var paths = 0; var emit = ""
    var expense = 0.0084; var spread = 0.004; var slip = 0.0002
    var phi = 0.0; var lambda = 0.001; var gamma = 2.0; var nu = 5.0; var asym = 0.4
    var seed = 20260828L
    eachArg(args.toSeq, usage) {
      case "-dir"     => dir = consumeNext
      case "-k"       => k = numOr("-k", consumeNext)
      case "-paths"   => paths = intOr("-paths", consumeNext)
      case "-expense" => expense = numOr("-expense", consumeNext)
      case "-spread"  => spread = numOr("-spread", consumeNext)
      case "-slip"    => slip = numOr("-slip", consumeNext)
      case "-phi"     => phi = numOr("-phi", consumeNext)
      case "-lambda"  => lambda = numOr("-lambda", consumeNext)
      case "-gamma"   => gamma = numOr("-gamma", consumeNext)
      case "-nu"      => nu = numOr("-nu", consumeNext)
      case "-asym"    => asym = numOr("-asym", consumeNext)
      case "-seed"    => seed = intOr("-seed", consumeNext).toLong
      case "-emit"    => emit = consumeNext
      case d if dir.isEmpty && d.asPath.isDirectory => dir = d
      case a          => usage(s"unrecognized arg [$a]")
    }
    if dir.isEmpty then usage("no -dir: point it at a directory of emitted marketSim paths")
    if !dir.asPath.isDirectory then usage(s"-dir [$dir] is not a directory")
    if k < 1.0 || k > 5.0 then usage(s"-k wants 1 to 5, got $k")
    if phi < 0.0 || phi >= 1.0 then usage(s"-phi is a variance SHARE, wants [0, 1), got $phi")
    if phi > 0.0 && nu <= 4.0 then
      usage(s"-nu $nu has an infinite fourth moment, so kurtosis never converges; use more than 4")
    if lambda <= 0.0 || lambda > 0.5 then usage(s"-lambda wants (0, 0.5], got $lambda")

    val files = Files.list(dir.asPath).iterator.asScala.toVector.map(_.toString)
      .filter(f => !f.endsWith(".json")).sorted
    val use = if paths <= 0 then files else files.take(paths)
    if use.isEmpty then usage(s"[$dir] holds no emitted paths")

    // Refuse to stack the substitution on a world that already carries the channel.  The sidecar
    // is the only thing that knows, and a silent double-count would look like a working run.
    if phi > 0.0 then
      val side = use.map(f => (f + ".json").asPath).find(_.exists)
      side.foreach { j =>
        val declared = j.lines.find(_.contains("\"jumpVar\""))
        declared.foreach { line =>
          val v = line.split(":").last.trim.stripSuffix(",").toDoubleOption.getOrElse(0.0)
          if v > 0.0 then
            usage(s"these paths already carry the model's own jump channel (sidecar jumpVar $v); " +
                  "-phi would substitute a second time. Use -phi 0, or emit from a pre-0.21 binary.")
        }
      }

    val tail = Tail(phi, lambda, gamma, nu, asym)
    val fund = Fund(k, expense, spread, slip)
    val rng  = new scala.util.Random(seed)

    val cases = use.map { f =>
      val e = load(f)
      val raw = e.price.sliding(2).map(w => math.log(w(1) / w(0))).toVector
      val sub = substitute(raw, tail, rng)
      (e, raw, sub, navReturns(fund, sub, e.rate.tail, e.liq.tail))
    }
    val years = cases.head._2.length.toDouble / DaysPerYear
    eprintln(f"${use.length} paths x $years%.0f years, k=$k%.0f, " +
             (if phi > 0 then f"tail phi $phi%.3f lambda $lambda%.4f gamma $gamma%.1f nu $nu%.0f"
              else "tail substitution OFF"))
    println()

    println("UNDERLYING — the substitution preserves variance, so only the SHAPE of a session moves.")
    println(f"  ${"series"}%-14s ${"vol"}%8s ${"kurtosis"}%8s ${"skew"}%7s ${"worst day"}%10s " +
            f"${"<-10%/cy"}%9s ${"<-20%/cy"}%9s ${"med maxDD"}%10s")
    underlyingRow("as emitted", cases.flatMap(_._2), cases.map(_._2))
    if phi > 0.0 then underlyingRow("substituted", cases.flatMap(_._3), cases.map(_._3))
    println("  anchors: vol 16.00, kurtosis 28.00 (CRSP 1926-2026, the CENTURY — SPY 1993-2026")
    println("  reads 14.4 and QQQ 9.6, and the model is nearly right against those).")
    println()

    println(f"FUND — daily-rebalanced ${k}%.0fx, expense ${expense * 100}%.2f%%, financing = path rate " +
            f"+ ${spread * 100}%.2f%%,")
    println(f"  rebalance ${k}%.0f*${k - 1}%.0f*|r| of turnover at ${slip * 1e4}%.0f bp x the path's own liq.")
    println(f"  ${"fund"}%-14s ${"vol"}%8s ${"worst day"}%11s ${"med CAGR"}%10s ${"med maxDD"}%10s " +
            f"${"p5 maxDD"}%10s ${"DD>95%"}%9s ${"DD>99%"}%9s")
    fundRow(f"k=${k}%.0f", cases.flatMap(_._4), cases.map(_._4))
    println()
    realTable()
    println()
    println("  Compare the MULTIPLE, not the level — the fund windows differ and none is this world.")
    println("  At 16.5% volatility this world is S&P-like, so k=2 is SSO and k=3 is UPRO; QLD and")
    println("  TQQQ need a Nasdaq-like underlying, which is a different world rather than a larger k.")
    println("  Max drawdown runs deeper than the real funds' because marketSim's own `worst crash %`")
    println("  sits at 1.54x its anchor, and leverage multiplies that error along with everything")
    println("  else — inherited, not introduced here.")

    if emit.nonEmpty then
      val out = emit.asPath
      if !out.isDirectory then Files.createDirectories(out)
      use.zip(cases).foreach { case (f, (e, _, _, lev)) =>
        // Rebuild `price` as the fund's NAV, rebased to the path's own first price so downstream
        // scale is unchanged, and leave the other eight columns exactly as emitted.
        val nav = lev.scanLeft(e.price.head)((p, r) => p * math.exp(r))
        val rows = e.rows.indices.map { i =>
          val c = e.rows(i).split("\t", -1)
          (c.take(1) :+ f"${nav(i)}%.6f") ++ c.drop(2)
        }.map(_.mkString("\t"))
        (out.toString + "/" + f.asPath.getFileName.toString).asPath.writeLines(e.header +: rows.toVector)
      }
      eprintln(s"wrote ${use.length} levered path(s) to $emit in the emit schema")
}

LeveredFund.main(args)

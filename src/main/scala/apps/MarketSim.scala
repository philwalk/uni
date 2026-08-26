//#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
package uni.apps

//> using scala 3.7.2
//> using dep org.vastblue:uni_3:0.20.0

// MARKET SIMULATOR — a testbed for COMPARING exposure strategies over long horizons.
//
// WHY THIS EXISTS: every conclusion about exit and re-entry rules on real data collapses on sample
// size (the book: 2 drawdowns of 15%+ in 21 years; the S&P: 15 in 72).  Cross-validated selection
// over those flips on which side of the split 2008 lands.  This needs more independent crashes than
// history supplies.  It is NOT a forecast and NOT a way to select a strategy: a market built from
// value and trend agents rewards value and trend rules by construction (measured: the trend rule's
// edge is near-linear in the assumed trend-follower share).  Defensible outputs: ruin rates, cost
// breakevens, fragility, refuge mechanics, crash-type conditioning — as CURVES over world
// parameters, never points.
//
// ARCHITECTURE (v3): ONE price-formation mechanism, instantiated per traded asset.
//   Market = value demand toward a fair-value anchor + external flow + noise, amplified when that
//   market's OWN liquidity has withdrawn after one-sided selling.  Equity and the bond are both
//   Markets.  v2 priced the bond off the rate by formula, so every amplifier (spiral, crowd,
//   panic) lived on the equity side only — any shock big enough to hurt bonds had already been
//   amplified into an implausible equity collapse (measured: bonds -1% with equities -39%, or
//   bonds -63% with equities -91%; reality 2022: bonds -31%, equities -25%).  Giving the bond its
//   own market fixes the asymmetry AT THE MECHANISM, not by tuning.
//   equity fair value = base fundamental marked down by the rate (discount channel: a rate rise
//     hits equities and bonds at the same time — the whole of 2022).
//   bond fair value   = rate-implied: accrues carry, loses duration times the rate move.
//   the RATE carries policy: it chases rateMean + inflation pressure MINUS an ACCOMMODATION
//     STOCK -- eased in fast under equity stress, capped at `easing` rate points, suppressed by
//     inflation, and withdrawn slowly at `unwind`.  The cap and the slow exit are both
//     load-bearing.  v3 cut the rate at an uncapped SPEED, which drove it 4.2% -> 0% -> 4.2%
//     inside a quarter, thirteen times a century (real holds: 7 years 2008-15, 2 years 2020-22).
//     Each round trip marked a bond peak ~65% above the normal level that then took a decade of
//     carry to regain: 75% of the bond's time below its running peak was spent recovering from
//     one, with no inflation involved at all.
//   refuge bid: flight-to-quality demand into a bond that is itself still orderly.  The bond needs
//     a NON-RATE source of crash performance -- capping the cut fixes the depth profile and, on
//     its own, removes the crash rally entirely (the rally WAS the spike that set the peak).
//   margin coupling: when BOTH markets are stressed, forced selling hits the bond too.
//
// SCOPE DECISION (recorded, not hidden): daily kurtosis (~16 vs real 28) stays a MISS and is
// deliberately not fixed -- closing it needs a slow valuation cycle (no bubbles, no 1929-1954
// era), which is a new mechanism.  Conclusions that depend on tail-day magnitudes must not be
// drawn from this model.  Crash frequency LEFT this bucket in 0.19.1: it is carried by market
// depth, not by the valuation cycle, and at 1.2x real it now sits inside the sampling error of
// its own anchor (15 episodes in 72 years, sd ~3, so ~0.8 sigma).
//
// EVERY MECHANISM SHIPS WITH (the recurring failure class here is one-sided checks and knobs that
// silently do not bind — it recurred even inside fixes for previous instances):
//   1. a BINDING diagnostic printed in the output (realized trend share, bond-spiral engagement,
//      clamp counts, pinned share),
//   2. a TWO-SIDED acceptance bound where a plausible range exists,
//   3. an OFF-world in the sweep (no spiral, no refuge channel, no margin coupling).
//
// FROZEN CONSTANTS (deliberately not swept; every other number is a World field or CLI flag):
//   equity noise sigmaN 0.007 (~11% annualised alone) | momentum lookback 60 sessions,
//   saturation tanh(m/0.12), momentum-crowd strength kTrend 0.0045 | reallocation kAdapt 0.010,
//   kHome 0.020, perf decay 0.99, choice-intensity cap +-50 | stress index decay 0.96 gain 0.04,
//   E[max(0,-z)] = 0.399, slow scale EWMA 0.995/0.005 (~140-session half-life) | stochastic-vol
//   normalisation volNorm = stationary VARIANCE (level-preserving; s2/2 preserved only the mean
//   multiplier and inflated volatility) | bond market kValueB 0.05 (deviations from rate-implied
//   fair arbitraged over ~20 sessions), bond idiosyncratic noise sigmaNB 0.002 | daily return
//   clamp +-50%, pure numerical guard (counted; gate <0.02%) | no-trade band 0.05 | burn-in 756 sessions (slowest state ~600) |
//   regime spacing 250 + U(0,2500) sessions, drift shock sd 0.04/yr | rate noise 0.01/yr |
//   inflation accommodation-suppression scale 0.005 | accommodation ease-in 6.0/yr (~2 months
//   to the cap; only the CAP and the WITHDRAWAL are worlds -- how fast a central bank can cut in
//   a panic is not the uncertain quantity) | rate-news multiplier 1+25*inflPress (rate
//   uncertainty rises with inflation pressure; source of the correlation flip).
//
// CONVENTIONS: trend windows are CALENDAR days (converted to sessions, 200d ~ 138).  Exposure
// decided at close i-1 applies to day i — ONE session between information and position; rules read
// state at close i, evaluate() applies e(i-1).  Episode statistics INCLUDE unrecovered drawdowns.
// Cost is charged where it occurs at cost*liq(i) — slippage scales with the model's own liquidity
// state.  Gross figures add back exactly what was paid.

import uni.*
import uni.data.*   // NumPyRNG, and MatD vector ops for the MEASUREMENT layer only -- the
                    // simulate() loop is a stateful recurrence and stays scalar BY DESIGN: its
                    // step-by-step causality is the model's documentation.
                    // NOTE: vec*vec / vec/vec / cummax(0) need the uni_3 0.16.0 build of
                    // 2026-08-13 or later (union-typed scalar ops); the RELEASED 0.16.0 of
                    // 2026-08-07 requires the *:* spelling instead
import uni.time.*   // UniDateTime for the -emit date column; java.time stays inside uni

object MarketSim:
  def println(s: String = ""): Unit = print(s"$s\n")
  def eprintln(s: String = ""): Unit = System.err.print(s"$s\n")

  /** Which release this run is, from build.sbt at compile time.  Never a literal: a stale jar or
    * a script pinned to an old `//> using dep` cannot report a version it was not built from,
    * which is the whole point of the `-version` flag and of the sidecar's `version` field.  The
    * Rust twin reads `env!("CARGO_PKG_VERSION")`, which cargo fills the same way, and the two
    * agree because `release-and-publish.sh` refuses to publish unless the two build files carry
    * one version. */
  val Version: String = BuildInfo.version

  /** The sidecar format this build writes.  Bump it whenever the sidecar's SHAPE changes — a key
    * added, removed or renamed, or a value's meaning changed — so a reader can tell "I cannot parse
    * this" from "I parsed it and the world differs".  Deliberately NOT derived from `Version`: most
    * releases move the world and leave the format alone, and a schema that tracked the release
    * would tell a reader nothing.
    *
    * `EmitSidecarKeys` is the contract that goes with it, and the writer does NOT read it — that is
    * the point.  `EmitSidecarSuite` compares the keys actually emitted against this list, so adding
    * a key without touching this line fails the build at the moment the discrepancy is created,
    * next to the schema number that then has to be decided about.  A test cannot force the bump; it
    * can force the decision to be conscious, which is what this pair is for. */
  val EmitSchema: Int = 2

  val EmitSidecarKeys: Vector[String] =
    Vector("generator", "version", "schema", "file", "columns", "header", "path", "world",
           "gate", "fidelity")

  // Numeric arguments fail LOUDLY.  `toInt` alone dies with a raw NumberFormatException, and the
  // Rust twin's old parse-or-default silently substituted the default — `-emitpath -1` emitted
  // path 0 with exit 0, a plausible file for an index nobody asked for.
  def intOr(flag: String, v: String): Int =
    v.toIntOption.getOrElse(usage(s"$flag wants an integer, got [$v]"))
  def longOr(flag: String, v: String): Long =
    v.toLongOption.getOrElse(usage(s"$flag wants an integer, got [$v]"))
  def numOr(flag: String, v: String): Double =
    v.toDoubleOption.getOrElse(usage(s"$flag wants a number, got [$v]"))
  def intListOr(flag: String, v: String): Vector[Int] =
    val parts = v.split(",").map(_.trim).filter(_.nonEmpty).toVector
    if parts.isEmpty then usage(s"$flag wants a comma-separated list of integers, got [$v]")
    parts.map(p => p.toIntOption.getOrElse(usage(s"$flag wants integers, got [$p]")))

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "-version      ; print the version this simulator was built from, and exit",
    s"-paths N      ; independent price paths (default ${DefaultPaths})",
    s"-years Y      ; years per path (default ${DefaultYears})",
    s"-seed S       ; base random seed (default ${DefaultSeed})",
    "-emit F       ; write one path as a full-state TSV, plus a provenance sidecar F.json",
    "-emitpath N   ; which path index -emit writes (default 0); path k uses seed + k*7919",
    "-emitall      ; -emit every path of the run to F-000.tsv, F-001.tsv, ... with sidecars",
    "-emitstart D  ; date the emitted path starts on, YYYY-MM-DD, stepping by WEEKDAYS so the",
    "              ;   file joins a real dated series (default: 1900-01-02 by 365/252 days)",
    "-emitgate P   ; paths in the ensemble that decides the emitted path's gate verdict",
    s"              ;   (default ${DefaultEmitGate}; 0 = judge the world by the emitted sample itself)",
    "-gate C,...   ; which gate classes a world must pass to be admissible: realism (is this a",
    "              ;   market), mechanism (is this mechanism engaged), fidelity (can this",
    "              ;   quantity's LEVEL be read), or all.  Default realism,mechanism; realism is",
    "              ;   always required — a non-market cannot be admitted by configuration",
    "-validate     ; stylised-fact gate + fidelity report; exit non-zero on gate failure",
    "-noise        ; per-target sampling error: the spread of single histories at each anchor's",
    "              ;   own horizon, plus the scoring ensemble's seed noise (~12 ensembles;",
    "              ;   ignores -years)",
    "-crossasset   ; ONE mechanism across the bond duration ladder, graded only on the two bands",
    "              ;   fitted across real funds, and only where those funds have data; plus every",
    "              ;   equity target re-read at the volatility anchor (~18 full ensembles)",
    "-releases     ; every fidelity ratio at every published default, plus the world this",
    "              ;   invocation describes — what a candidate costs against the whole history,",
    "              ;   not just the previous release",
    "-fitness      ; print the scalar calibration loss and its components, then exit",
    "-calibrate N  ; random-search N parameter samples against the fitness loss; scores the",
    "              ;   best few again on a HELD-OUT seed; prints, does not modify defaults",
    "-power        ; estimator power: how much history each grading statistic needs before its",
    "              ;   own answer stops being noise (hit rate and n* per statistic per horizon)",
    s"-powerarms N,N; with -power, which rules to contrast, as 1-based indices into the rule list",
    s"              ;   the report's legend names (default ${PowerArmsDefault.mkString(",")})",
    s"-poweryears L ; with -power, history lengths in years, comma-separated (default",
    s"              ;   ${PowerYearsDefault.mkString(",")})",
    "-buffer       ; distribution of REAL underwater-stretch length and depth at exhaustion —",
    "              ;   the cash-buffer question, as a distribution instead of one episode",
    "-strategies   ; exposure rules across a world sweep: stability, paired stats, breakevens,",
    "              ;   flight-to-safety decomposition, refuge-severity curve, crash types",
    "-single       ; with -strategies/-power/-buffer, baseline world only (skip the world sweep)",
    s"-cost X       ; calm-market cost per unit of exposure changed (default ${DefaultCost} = 10bp)",
    s"-trendshare X ; mandate level for trend-follower capital (default ${Defaults.trendShare}; a spring,",
    "              ;   not a wall — realized share and pinned fraction are reported)",
    s"-depth X      ; equity market depth; impact scales as 12/depth (default ${Defaults.depth})",
    s"-stress X     ; liquidity-withdrawal amplification, shared by BOTH markets (default",
    s"              ;   ${Defaults.stress}); 0 = off",
    s"-beta X       ; intensity of capital switching between agent types (default ${Defaults.beta})",
    s"-volpersist X ; persistence of volatile stretches (default ${Defaults.volPersist})",
    s"-volofvol X   ; size of shocks to volatility itself (default ${Defaults.volOfVol})",
    s"-value X      ; pull toward equity fair value, per day (default ${Defaults.valuePull})",
    "-crowd K      ; momentum (default), trendNNN, or volscaled — the last two make the crowd",
    "              ;   run the RULE UNDER TEST, closing the reflexive loop",
    s"-crowdimpact X; price pressure per unit of exposure the crowd trades (default ${Defaults.crowdImpact});",
    "              ;   scales the momentum crowd too via crowdImpact/0.06; before 0.19.1 it",
    "              ;   scaled only the trendNNN and volscaled crowds",
    s"-panic X      ; stress-accelerated capital reallocation (default ${Defaults.panic} = symmetric flows)",
    s"-drift X      ; fundamental drift per year; no dividend, so this IS total return (default",
    s"              ;   ${Defaults.drift})",
    s"-ratemean X   ; long-run mean of the short rate (default ${Defaults.rateMean})",
    s"-duration X   ; bond duration in years (default ${Defaults.duration}, a long-Treasury refuge)",
    s"-easing X     ; CAP on policy accommodation under equity stress, in rate points, suppressed",
    s"              ;   by inflation (default ${Defaults.easing} = one full real easing cycle)",
    s"-unwind X     ; how fast accommodation is withdrawn, per year (default ${Defaults.unwind}, a",
    s"              ;   ~2-year half-life)",
    s"-refuge X     ; flight-to-quality bid into the bond when equities are stressed and the bond",
    s"              ;   is not; scales with duration (default ${Defaults.refuge})",
    s"-inflprob X   ; chance a regime shift starts an inflation regime (default ${Defaults.inflProb})",
    s"-inflsize X   ; rate pressure target in one, per year (default ${Defaults.inflSize})",
    s"-inflspeed X  ; how fast pressure ramps, per session (default ${Defaults.inflSpeed})",
    s"-ratespeed X  ; how fast the short rate chases its target, per year (default ${Defaults.rateSpeed})",
    s"-discount X   ; equity fair-value sensitivity to the rate, % per pp (default ${Defaults.discount})",
    s"-margin X     ; forced bond selling when BOTH markets are stressed (default ${Defaults.margin})",
  )

  /** What the non-value crowd trades on.  Momentum is the generic extrapolator; the other two run
    * the SAME RULE being tested, so its de-risking moves the price it reacts to. */
  enum Crowd:
    case Momentum
    case Trend(calDays: Int)
    case VolScaled

  final case class World(
    trendShare: Double, depth: Double, stress: Double, beta: Double,
    drift: Double,      // fundamental drift per year; no dividend, so this IS total return
    fundVol: Double, rateMean: Double, volPersist: Double, volOfVol: Double, valuePull: Double,
    crowd: Crowd, crowdImpact: Double, panic: Double,
    duration: Double,   // bond duration: sensitivity of its fair value to the rate
    easing: Double,     // CAP on policy accommodation under equity stress, in rate points
    unwind: Double,     // how fast that accommodation is withdrawn, per year
    refuge: Double,     // flight-to-quality bid into the bond, per unit of equity stress
    inflProb: Double, inflSize: Double, inflSpeed: Double, rateSpeed: Double,
    discount: Double,   // equity fair-value markdown per pp of rate above its long-run mean
    margin: Double,     // joint-stress forced selling pressure on the bond
  )

  final case class Path(price: Array[Double], rate: Array[Double], fundamental: Array[Double],
                        liq: Array[Double],      // per-session slippage multiplier (equity market)
                        bliq: Array[Double],     // the same, for the BOND market: an arm that trades
                                                 // the bond is charged its own market's slippage,
                                                 // not the equity book's
                        bond: Array[Double],     // flight-to-safety asset price (its own Market)
                        inflPress: Array[Double],// inflation pressure, for regime classification
                        cpi: Array[Double],      // realized price level, deterministic from pressure
                        meanTrendShare: Double,  // BINDING diagnostic for the population knob
                        trendPinned: Double,     // share of sessions on the numerical guard rails
                        targetSat: Double,       // share of sessions the choice target saturated
                        clampedDays: Int,        // both markets, post-burn-in
                        meanBondStress: Double,  // BINDING diagnostic for the bond spiral
                        pctBondStress: Double,   // share of sessions bond stress index > 0.5
                        duration: Double,        // the world's bond duration, carried so the gate can
                                                 // judge bond volatility RELATIVE to it; a fixed
                                                 // absolute band can only ever fit one bond
                        meanCrowdFlow: Double)   // BINDING diagnostic for the reflexive channel:
                                                 // mean |crowd flow| per session, post burn-in.
                                                 // Its ABSENCE is why -crowdimpact sat dead in the
                                                 // default world across four releases.

  /** THE shipped world.  `main` seeds its mutable CLI vars from this and `usage` interpolates its
    * numbers, so every default is written in exactly one place.  Help text that restates a constant
    * is a second copy of it — the failure class PARITY.md documents — and this one had already gone
    * wrong three times before it was centralised.  A mismatch between the twins is caught directly
    * by the `-emit` sidecar, which names every field: bare `-emit` writes THIS world. */
  val Defaults = World(
    trendShare = 0.07, depth = 16.1, stress = 5.6, beta = 3.0, drift = 0.123, fundVol = 0.13,
    rateMean = 0.042, volPersist = 0.99, volOfVol = 0.014, valuePull = 0.0145,
    crowd = Crowd.Momentum, crowdImpact = 0.07, panic = 0.0, duration = 13.5,
    easing = 0.046, unwind = 0.35, refuge = 0.11,
    inflProb = 0.20, inflSize = 0.10, inflSpeed = 0.010, rateSpeed = 3.0, discount = 5.0,
    margin = 0.006)
  val DefaultPaths = 200
  val DefaultYears = 100
  val DefaultSeed = 20260813L
  val DefaultEmitGate = 200
  val DefaultCost = 0.0010
  /** The default world as it shipped at each published release, so a candidate can be compared
    * against EVERY shipped version rather than only its immediate predecessor -- the reading under
    * which five individually-acceptable trades accumulate invisibly.
    *
    * The worlds are historical; the MEASUREMENT is current.  This therefore answers "how has the
    * default moved", NOT "what did that version report" -- the mechanism moved too, and conflating
    * those would be its own error.  A `World` field added after a release takes today's value in
    * that release's row, because an older world genuinely has no value for it.  A field REMOVED
    * by a mechanism change is the same case read backwards: 0.17.0-0.19.0 shipped `flight = 0.38`,
    * an uncapped cut speed for which the capped accommodation has no equivalent value, so those
    * rows carry today's `easing`/`unwind`.  The row still answers the question the report asks.
    *
    * 0.17.0 through 0.19.0 share one world: the default did not move for three releases, and
    * 0.19.3 shipped the 0.19.2 world unchanged — it added version reporting, not a world change.
    *
    * Historical rows chain from `V0_19_2`, a FULL literal, never from the live `Defaults`:
    * derived from `Defaults`, every field a past release shipped unchanged would silently
    * take the current value the moment the default moves -- which 0.20.0's recalibration was
    * the first to do. */
  private val V0_19_2 = World(
    trendShare = 0.06, depth = 16.6, stress = 5.1, beta = 3.0, drift = 0.117, fundVol = 0.13,
    rateMean = 0.042, volPersist = 0.99, volOfVol = 0.011, valuePull = 0.013,
    crowd = Crowd.Momentum, crowdImpact = 0.088, panic = 0.0, duration = 13.5,
    easing = 0.045, unwind = 0.35, refuge = 0.08,
    inflProb = 0.20, inflSize = 0.10, inflSpeed = 0.010, rateSpeed = 3.0, discount = 3.35,
    margin = 0.006)
  private val PreV1901 = V0_19_2.copy(
    trendShare = 0.30, depth = 12.0, stress = 3.4, volOfVol = 0.028, valuePull = 0.015,
    crowdImpact = 0.06, drift = 0.100, duration = 13.5, inflSize = 0.07,
    discount = 4.0, margin = 0.0008)
  private val PreV1902 = V0_19_2.copy(depth = 16.3, stress = 5.4)
  val Releases: Vector[(String, World)] = Vector(
    ("0.17.0", PreV1901), ("0.18.0", PreV1901), ("0.19.0", PreV1901),
    ("0.19.1", PreV1902), ("0.19.2", V0_19_2), ("0.19.3", V0_19_2), ("0.20.0", Defaults))

  /** `-power`'s default contrast arms, as 1-based indices into `Rules`, and its default history
    * lengths.  Named here rather than inside the report so `usage` states them and `main` seeds
    * from them — the same one-source rule the world's defaults follow.
    * 21 = the traded book's span; 72 = the S&P record used for calibration; the ends bracket them. */
  val PowerArmsDefault  = Vector(2, 6, 9, 8)
  val PowerYearsDefault = Vector(21, 40, 72, 100)

  val DaysPerYear = 252
  /** Sessions discarded so paths start from the stationary distribution (slowest state ~600). */
  val BurnIn = 756
  // Treasuries incorporate rate news SAME-DAY — at 0.05 the bond market smeared a fair-value move
  // over ~20 sessions, which crushed the daily stock-bond correlation (the flip read +0.05) and
  // halved every crash-window bond response.  0.7 = near-immediate tracking, with flows and the
  // spiral acting as short-lived deviations on top, which is what bond-market dysfunction is.
  val KValueBond = 0.7
  /** Bond idiosyncratic noise AT THE REFERENCE DURATION.  It scales with duration in `simulate`,
    * and must: a zero-duration bond is cash.  Five real iShares Treasury funds spanning 1.80 to
    * 14.89 years of duration (SHY, IEI, IEF, TLH, TLT, 20-24 years each) fit
    *     vol = -0.07 + 0.937 * duration
    * -- an intercept of zero to within a rounding error.  Held FIXED, this term was a 5.11%
    * volatility floor: the model read 1.10x real at TLT's duration, where it was calibrated, and
    * 4.01x at SHY's, so the whole short half of the bond universe was unreachable by construction
    * rather than by parameter choice.  `DurationRef` is the shipped default, so the ratio is a
    * bit-exact 1.0 there and the default world is unchanged. */
  val SigmaNBond = 0.002
  /** How fast policy reaches the accommodation the stress level calls for, per year: ~2 months to
    * the cap, which is what an easing cycle takes.  Frozen, not a World field: the uncertain
    * quantities are HOW FAR policy can go (`easing`) and HOW LONG it stays (`unwind`), not how
    * quickly a central bank can cut in a panic -- that one the record answers the same way every
    * time. */
  val EaseInSpeed = 6.0
  val DurationRef = 13.5
  /** Bond volatility is measured over NON-OVERLAPPING windows of this many years, even when the
    * paths are longer.  Every other statistic is measured over the whole path.
    *
    * The asymmetry is deliberate and it is not free, so it is stated here, in the row's own label
    * (`bond vol % (24y)`) and in the report's anchor header.  Bond volatility is the one statistic
    * that is strongly horizon-DEPENDENT in this model -- 12.57% over 24 years against 17.12% over
    * 100, because a longer window samples more rate-regime variation -- while its anchor can only
    * come from fund data, and the longest clean bond-fund series run 24 years.  Scoring a 100-year
    * reading against a 24-year anchor reported a ratio of 1.32 where the horizon-matched answer is
    * 0.89, which is the same mistake the clustering anchor carried before it was re-measured.
    *
    * Measured, for the record: the other three bond statistics do NOT need this.  Over 24 against
    * 100 years the depth rung moves 1.02x, growth-crash 1.12x and inflation-crash 0.90x, so they
    * stay on the whole-path protocol and the split is confined to one row. */
  val BondVolYears = 24
  /** Equity idiosyncratic noise, ~11% annualised alone.  Top-level beside its bond counterpart so
    * the crowd-flow diagnostic can state the reflexive channel as a share of it. */
  val SigmaN = 0.007
  /** `crowdImpact` at which the momentum crowd reproduces the frozen `kTrend` exactly.  The ratio
    * is what enters the flow, so the default divides to a bit-exact 1.0 and the shipped world is
    * unchanged; every other setting scales the reflexive channel that used to have no dial at all. */
  val CrowdImpactRef = 0.06

  /** ONE price-formation mechanism for every traded asset: value demand toward `fair`, plus
    * external flow and noise, amplified when THIS market's liquidity has withdrawn after one-sided
    * selling (measured against a slowly-adapting scale, so symmetric turbulence of any size leaves
    * the index flat — E[max(0,-z)] = 0.399 regardless of scale). */
  final class Market(kValue: Double, stressK: Double, impact: Double):
    var logP = 0.0
    var stressIdx = 0.0
    var lastLiq = impact
    var clamps = 0
    private var scaleVar = 0.01 * 0.01
    def step(fair: Double, flowPlusNoise: Double): Double =
      val scale = math.sqrt(scaleVar)
      val amp   = 1.0 + stressK * stressIdx
      lastLiq   = amp * impact
      // amplification applies to FLOW AND NOISE, not to the value-arbitrage pull: thin liquidity
      // makes any ORDER move price further, but amplifying the arbitrage itself sets a feedback
      // gain of kValue*amp, which for a fast-tracking market (bond, kValue 0.7) exceeded 1 and
      // OSCILLATED — 86% bond volatility from the market fighting its own fair value.
      val raw   = (kValue * (fair - logP) + flowPlusNoise * amp) * impact
      // Numerical guard ONLY, and verified to be exactly that: at ±0.25 vs ±0.50 every statistic in
      // every gate-passing world is BIT-IDENTICAL (the clamp consumes no draws and never binds
      // there).  In a far out-of-gate world (40% volatility) it bound on 0.075% of sessions and
      // was silently shaping the tail — kurtosis 26.8 at ±0.25 vs 35.8 at ±0.50 — so it sits at
      // ±0.50, far from any plausible daily move (worst real S&P day ~ -23% log), and the gate
      // below rejects any world where it engages enough to matter.
      val ret   = math.max(-0.50, math.min(0.50, raw))
      if ret != raw then clamps += 1
      logP += ret
      scaleVar  = 0.995 * scaleVar + 0.005 * ret * ret
      stressIdx = math.max(0.0, 0.96 * stressIdx + 0.04 * (math.max(0.0, -ret) / scale - 0.399))
      ret

  /** One independent history.  Local mutable state only — nothing escapes this method. */
  def simulate(w: World, years: Int, seed: Long): Path =
    val n    = years * DaysPerYear
    val tot  = n + BurnIn
    val rng  = new NumPyRNG(seed)
    val px   = new Array[Double](tot)
    val fv   = new Array[Double](tot)
    val rt   = new Array[Double](tot)
    val lq   = new Array[Double](tot)
    val bq   = new Array[Double](tot)
    val bp   = new Array[Double](tot)
    val ip   = new Array[Double](tot)
    val cp   = new Array[Double](tot)
    val dt   = 1.0 / DaysPerYear
    val sqdt = math.sqrt(dt)

    val eqM = new Market(w.valuePull, w.stress, 12.0 / w.depth)
    val bdM = new Market(KValueBond, w.stress, 1.0)

    var logVbase = 0.0
    var rate = w.rateMean
    var inflPress = 0.0; var inflTarget = 0.0
    var acc = 0.0                              // policy accommodation in force, in rate points
    var driftNow = w.drift
    var regimeCountdown = 250 + rng.nextBoundedInt(2500)
    var fairB = 0.0
    // realized inflation: baseline plus the same pressure that drives the rate.  DELIBERATELY
    // noise-free — it consumes no random draws, so adding it left every calibrated statistic
    // bit-identical.  piBase 0.025 makes rateMean 4.2% a ~1.7% real rate, and long-run inflation
    // lands near the 1954-2026 CPI average (~3.6%/yr) once regime pressure is included.
    val piBase = 0.025
    var logCpi = 0.0
    var wTrend = w.trendShare; var wTrendSum = 0.0
    var pinnedCnt = 0; var satCnt = 0
    var perfV = 0.0; var perfT = 0.0
    val kTrend = 0.0045
    val kAdapt = 0.010; val kHome = 0.020
    var logVol = 0.0
    val volNorm = (w.volOfVol * w.volOfVol) / math.max(1e-9, 1.0 - w.volPersist * w.volPersist)
    val crowdWin = w.crowd match
      case Crowd.Trend(d) => math.max(2, math.round(d * 252.0 / 365.25).toInt)
      case _              => 0
    var crowdE = 1.0; var crowdPrev = 1.0; var maSum = 0.0
    var crowdRv = 0.01 * 0.01; var crowdAnchor = 0.0
    var bondStressSum = 0.0; var bondStressHi = 0
    var crowdFlowSum = 0.0
    var clampsAtBurn = 0

    var i = 0
    while i < tot do
      // ---- exogenous layer: regimes, fundamental, the policy rate ---------------------------
      regimeCountdown -= 1
      if regimeCountdown <= 0 then
        inflTarget = if rng.nextDouble() < w.inflProb then math.abs(rng.randn()) * w.inflSize else 0.0
        driftNow = w.drift + rng.randn() * 0.04
        regimeCountdown = 250 + rng.nextBoundedInt(2500)
      logVbase += driftNow * dt + w.fundVol * sqdt * rng.randn()
      inflPress += w.inflSpeed * (inflTarget - inflPress)
      // policy: chase rateMean + pressure MINUS accommodation, and accommodation is a CAPPED
      // STOCK rather than a cut speed -- eased in within ~2 months, withdrawn over years.  As a
      // speed it was unbounded, so a stress episode took the rate to the floor and the same
      // `rateSpeed` pulled it straight back; the bond's peak was set by that spike.  Inflation
      // suppresses the easing, which is what ties policy's hands in 2022-like regimes.
      val accWant = w.easing * eqM.stressIdx * math.exp(-inflPress / 0.005)
      acc = if accWant > acc then acc + EaseInSpeed * (accWant - acc) * dt
            else math.max(0.0, acc - w.unwind * acc * dt)
      val rOld = rate
      // rate UNCERTAINTY rises with inflation pressure (2022: MOVE elevated all year).  This is what
      // makes stocks and bonds co-move in an inflation regime: both are priced off the same rate,
      // so more rate news = more shared-factor variance = the correlation flip.  A constant rate
      // noise produced a flip of only +0.05 — present but too weak to pass its own gate.
      rate = math.max(0.0, rate + w.rateSpeed * ((w.rateMean + inflPress - acc) - rate) * dt
                              + 0.01 * (1.0 + 25.0 * inflPress) * sqdt * rng.randn())
      // bond fair value: carry minus duration times the realised rate move
      fairB += rate * dt - w.duration * (rate - rOld)
      // The discount markdown applies to the OBSERVED equity price directly — same-day, like the
      // bond's duration response — because equities reprice discount-rate news immediately.
      // Routing it through the slow value channel (the previous form) smeared rate news over ~40
      // sessions on the equity side while the bond moved same-day, so the two assets shared no
      // same-day factor and the correlation flip could not appear at any parameter setting.
      val markdown = w.discount * (rate - w.rateMean)

      // ---- crowd target, from information strictly before this session ----------------------
      if i > 0 then
        val pPrev = px(i - 1)
        w.crowd match
          case Crowd.Trend(_) =>
            maSum += pPrev
            if i > crowdWin then maSum -= px(i - 1 - crowdWin)
            val tgt = if pPrev >= maSum / math.min(i, crowdWin) then 1.0 else 0.0
            if math.abs(tgt - crowdE) > Band then crowdE = tgt
          case Crowd.VolScaled =>
            val r = math.log(pPrev / px(math.max(i - 2, 0)))
            crowdRv = 0.94 * crowdRv + 0.06 * r * r
            val v = math.sqrt(crowdRv * DaysPerYear)
            crowdAnchor = if crowdAnchor == 0.0 then v else 0.999 * crowdAnchor + 0.001 * v
            val tgt = math.max(0.0, math.min(1.0, if v > 0 then crowdAnchor / v else 1.0))
            if math.abs(tgt - crowdE) > Band then crowdE = tgt
          case Crowd.Momentum => ()

      // ---- demand flows ----------------------------------------------------------------------
      val logPobs = eqM.logP - markdown                 // what everyone actually sees and trades
      val mispricingPre = logVbase - eqM.logP           // value agents arb the traded component
      val lookback = 60
      val past = if i >= lookback then math.log(px(i - lookback)) else logPobs
      val momentum = logPobs - past
      val trendPos = math.tanh(momentum / 0.12)
      val eqFlow = w.crowd match
        case Crowd.Momentum => kTrend * (w.crowdImpact / CrowdImpactRef) * wTrend * trendPos
        case _              => w.crowdImpact * wTrend * (crowdE - crowdPrev)
      crowdPrev = crowdE
      logVol = w.volPersist * logVol + w.volOfVol * rng.randn()
      val dNoise = SigmaN * math.exp(logVol - volNorm) * rng.randn()

      // ---- both markets step through the SAME mechanism --------------------------------------
      val retE = eqM.step(logVbase, eqFlow + dNoise)
      // joint-stress margin selling: when both markets are stressed, the bond gets dumped too --
      // and against it the refuge bid, flight-to-quality into a bond that is itself still orderly.
      // DURATION-SCALED, like the bond's own noise: an absolute bid gave a 5-year bond the same
      // crash rally as a 20-year one, which no duration-relative band can then fit.
      val bondFlow = -w.margin * eqM.stressIdx * bdM.stressIdx +
                     w.refuge * (w.duration / DurationRef) * eqM.stressIdx *
                       math.max(0.0, 1.0 - bdM.stressIdx)
      val retB = bdM.step(fairB, bondFlow + SigmaNBond * (w.duration / DurationRef) * rng.randn())
      val _ = retB

      px(i) = math.exp(eqM.logP - markdown)
      fv(i) = math.exp(logVbase - markdown)
      rt(i) = rate
      lq(i) = eqM.lastLiq
      bq(i) = bdM.lastLiq
      bp(i) = math.exp(bdM.logP)
      ip(i) = inflPress
      logCpi += (piBase + inflPress) * dt
      cp(i) = math.exp(logCpi)

      // ---- capital reallocation: spring, scored on positions actually held -------------------
      perfV = 0.99 * perfV + 0.01 * (mispricingPre * retE) * 100.0
      val crowdPos = w.crowd match
        case Crowd.Momentum => trendPos
        case _              => crowdE - 1.0
      perfT = 0.99 * perfT + 0.01 * (crowdPos * retE) * 100.0
      val eT = math.exp(math.min(50.0, w.beta * perfT))
      val eV = math.exp(math.min(50.0, w.beta * perfV))
      val target = eT / (eT + eV)
      val kNow = kAdapt * (1.0 + w.panic * eqM.stressIdx)   // redemptions fast, subscriptions slow
      wTrend += kNow * (target - wTrend) + kHome * (w.trendShare - wTrend)
      wTrend = math.max(0.02, math.min(0.95, wTrend))       // numerical guard; binding is REPORTED
      if i >= BurnIn then
        wTrendSum += wTrend
        if wTrend <= 0.02 + 1e-9 || wTrend >= 0.95 - 1e-9 then pinnedCnt += 1
        if target < 0.02 || target > 0.98 then satCnt += 1
        bondStressSum += bdM.stressIdx
        crowdFlowSum += math.abs(eqFlow)
        if bdM.stressIdx > 0.5 then bondStressHi += 1
      if i == BurnIn then clampsAtBurn = eqM.clamps + bdM.clamps
      i += 1

    Path(px.drop(BurnIn), rt.drop(BurnIn), fv.drop(BurnIn), lq.drop(BurnIn), bq.drop(BurnIn),
         bp.drop(BurnIn), ip.drop(BurnIn), cp.drop(BurnIn),
         wTrendSum / n, pinnedCnt.toDouble / n, satCnt.toDouble / n,
         eqM.clamps + bdM.clamps - clampsAtBurn,
         bondStressSum / n, bondStressHi.toDouble / n, w.duration, crowdFlowSum / n)

  // ---- stylised-fact measurements ------------------------------------------------------------
  def dailyReturns(px: Array[Double]): Array[Double] =
    Array.tabulate(px.length - 1)(i => math.log(px(i + 1) / px(i)))

  /** mean(z^4) / mean(z^2)^2 for z = r - mean(r) -- written as the formula it implements. */
  def kurtosis(r: Array[Double]): Double =
    val z  = MatD(r) - MatD(r).mean
    val m2 = z.power(2).mean
    if m2 <= 0 then Double.NaN else z.power(4).mean / (m2 * m2)

  /** sum(z_t * z_(t+lag)) / sum(z_t^2) for z = |r| - mean|r| -- volatility clustering. */
  def autocorrAbs(r: Array[Double], lag: Int): Double =
    val a = MatD(r).abs
    val z = a - a.mean
    val den = z.power(2).sum
    if den <= 0 || r.length <= lag then Double.NaN
    else (z(0 until r.length - lag, 0) * z(lag until r.length, 0)).sum / den

  /** cov(a,b) / (sigma_a * sigma_b), in unnormalised sums -- written as the formula. */
  def pearson(a: Array[Double], b: Array[Double]): Double =
    if a.length < 50 then Double.NaN
    else
      val za = MatD(a) - MatD(a).mean
      val zb = MatD(b) - MatD(b).mean
      val den = math.sqrt(za.power(2).sum * zb.power(2).sum)
      if den <= 0 then Double.NaN else (za * zb).sum / den

  /** recovered < 0 marks an episode still under water at path end: depth known, shape not. */
  final case class Episode(peak: Int, trough: Int, recovered: Int, depthPct: Double):
    def censored: Boolean = recovered < 0
    def fallDays: Int     = trough - peak
    def reboundDays: Int  = recovered - trough
    def shape: Double = if censored || reboundDays <= 0 then Double.NaN else fallDays.toDouble / reboundDays

  def episodes(px: Array[Double], minDecPct: Double): Vector[Episode] =
    val out = scala.collection.mutable.ArrayBuffer.empty[Episode]
    var pk = px(0); var pkI = 0; var i = 1
    while i < px.length do
      if px(i) >= pk then { pk = px(i); pkI = i; i += 1 }
      else
        var j = i; var tro = i
        while j < px.length && px(j) < pk do
          if px(j) < px(tro) then tro = j
          j += 1
        val dec = (px(tro) / pk - 1.0) * 100.0
        if dec <= -minDecPct then
          out += Episode(pkI, tro, if j < px.length then j else -1, dec)   // censored INCLUDED
        if j < px.length then { pkI = j; pk = px(j); i = j + 1 } else i = px.length
    out.toVector

  /** Share of sessions spent more than 5%, 10% and 20% below the running peak — the DEPTH
    * DISTRIBUTION, which volatility, maximum drawdown and underwater fraction between them do not
    * pin.  Two series can agree on all three of those and still differ here: one drifts far below
    * its peak and stays, the other hugs it and makes new highs.  Every rule that reads distance
    * from a running peak is a different rule on the two.
    *
    * Computed on prices directly rather than through `drawdownSeries`' log/exp round trip.  The
    * ratio is exact in both languages, so a threshold comparison cannot land on opposite sides of
    * a 1-ulp `log` gap; a count is the one reduction where that would show up as a whole session.
    * One pass for all three depths. */
  def depthShares(px: Array[Double]): (Double, Double, Double) =
    var pk = px(0); var n5 = 0; var n10 = 0; var n20 = 0; var i = 0
    while i < px.length do
      if px(i) > pk then pk = px(i)
      val d = 1.0 - px(i) / pk
      if d > 0.05 then n5 += 1
      if d > 0.10 then n10 += 1
      if d > 0.20 then n20 += 1
      i += 1
    val n = px.length.toDouble
    (n5 / n, n10 / n, n20 / n)

  /** The five real Treasury funds' fit of time-spent-more-than-10%-under-water against volatility:
    * `d10 = BondD10Slope * vol% + BondD10Intercept`, floored at zero.  Named rather than written
    * inline because `-crossasset` needs the line's zero crossing, and a second literal for it would
    * be a number free to drift away from the line it describes. */
  val BondD10Slope = 0.0397
  val BondD10Intercept = -0.0785

  /** Volatility (%) at which the line above reaches zero.  Below it the relation has NO VALUE -- a
    * ratio against a non-positive prediction -- which makes its usable range narrower than the
    * 1.44-14.12% range it was fitted across.  Real funds at that end read `d10 = 0.000` exactly,
    * and 0/0 is not agreement. */
  val BondD10Zero: Double = -BondD10Intercept / BondD10Slope

  /** The `bond depth vs vol` band, shared by the acceptance gate and `-crossasset` so the two cannot
    * drift apart.  1.0 +- 0.35 is WIDER than the five Treasuries' own scatter (0.79-1.04) on
    * purpose: the band is a SCOPE decision as much as a tolerance.  It admits the Aggregate (1.06)
    * and investment grade (0.71) and excludes high yield (0.50), which this model has no channel
    * for. */
  val BondD10Band = (0.65, 1.35)

  /** The `bond vol x duration` band, shared for the same reason.  Treasuries run 0.798-0.973 and
    * investment grade 0.824; high yield's 2.001 is deliberately outside. */
  val BondVolPerYearBand = (0.70, 1.10)

  /** The range the anchor funds actually covered, per driving variable: Treasury durations in
    * years, and Treasury annualised volatility in %.  Outside these an anchor-fitted band is
    * arithmetic with nothing behind it, so BOTH graders -- the acceptance gate and `-crossasset` --
    * refuse to grade there rather than manufacture agreement or a defect.  `BondAnchorSuite` pins
    * each pair to the min/max of the fixture's Treasury rows, so a re-measured fund moves them or
    * fails the build. */
  val BondDurSupport = (1.80, 14.89)
  val BondVolSupport = (1.44, 14.12)

  // ---- world statistics and the ONE acceptance predicate -------------------------------------
  final case class WorldStats(vol: Double, kurt: Double, ac1: Double, ac20: Double, annRet: Double,
                              nEpisodes: Int, epPerPath: Double, depthMed: Double, worstDepth: Double,
                              vCount: Int, midCount: Int, uCount: Int, nShapes: Int, censored: Int,
                              clampPct: Double, trendShare: Double, yearsPerPath: Double,
                              trendPinned: Double, targetSat: Double,
                              bondVol: Double, bondGrowth: Double, bondInfl: Double,
                              corrCalm: Double, corrInfl: Double,
                              meanBondStress: Double, pctBondStress: Double, crowdFlow: Double,
                              duration: Double,
                              inflAnn: Double,
                              // depth profile: median share of sessions more than 5/10/20% below
                              // the running peak, equity leg then bond leg
                              ddEq5: Double, ddEq10: Double, ddEq20: Double,
                              ddBd5: Double, ddBd10: Double, ddBd20: Double):
    /** Return per unit volatility, in the units this report already prints: `annRet` is a LOG
      * return in %/yr and `vol` is a fraction.  An arithmetic-mean anchor is higher by about
      * sigma/2 (0.08 at 16% vol) and has to be restated before it can be compared with this. */
    def retVol: Double = if vol <= 0.0 then Double.NaN else annRet / (vol * 100.0)

    /** Bond volatility per year of duration.  Real funds, 19-24 years each: Treasuries 0.798 (SHY)
      * to 0.973 (IEF), the US Aggregate 0.745, investment-grade credit 0.824, high yield 2.001 --
      * credit is the only thing that breaks the relationship, and this model has no credit channel.
      * Judging bond volatility on this ratio rather than an absolute band is what lets one gate
      * cover every duration instead of only the one the anchor was built from. */
    def bondVolPerYear: Double = if duration <= 0.0 then Double.NaN else bondVol * 100.0 / duration

    /** Time spent >10% below the running peak, RELATIVE to what this bond's own volatility implies.
      * The five real Treasury funds fit `d10 = 0.0397 * vol - 0.0785` (floored at zero) across a
      * 1.44-14.12% volatility range; 1.0 means the bond is under water as long as a real bond of
      * the same volatility.  Replaces a fixed 0.510, which was TLT's number and false for every
      * other bond -- the real range across eight funds is 0.000 to 0.499. */
    def bondDepthVsVol: Double =
      val expected = math.max(0.0, BondD10Slope * (bondVol * 100.0) + BondD10Intercept)
      if expected <= 0.0 then Double.NaN else ddBd10 / expected

  def measure(sims: Vector[Path], years: Int): WorldStats =
    val rets = sims.map(s => dailyReturns(s.price))
    def med(v: Seq[Double]) = { val f = v.filter(x => !x.isNaN); if f.isEmpty then Double.NaN else f.sorted.apply(f.size / 2) }
    val epsBy  = sims.map(s => s -> episodes(s.price, 15.0))   // once per path (was recomputed 3x)
    val ddEq   = sims.map(s => depthShares(s.price))
    val ddBd   = sims.map(s => depthShares(s.bond))
    val eps    = epsBy.flatMap(_._2)
    val shapes = eps.map(_.shape).filter(x => !x.isNaN)
    val days   = sims.map(_.price.length.toLong).sum.toDouble
    def bondInWindows(inflRegime: Boolean): Double = med(epsBy.flatMap { (sp, es) =>
      es.filter { ep =>
        val infl = (ep.peak to ep.trough).map(sp.inflPress).sum / math.max(1, ep.trough - ep.peak + 1)
        (infl > 0.005) == inflRegime
      }.map(ep => math.log(sp.bond(ep.trough) / sp.bond(ep.peak)) * 100.0)
    })
    def corrIn(inflRegime: Boolean): Double = med(sims.map { sp =>
      val idx = (1 until sp.price.length).filter(i => (sp.inflPress(i) > 0.005) == inflRegime).toArray
      pearson(idx.map(i => math.log(sp.price(i) / sp.price(i - 1))),
              idx.map(i => math.log(sp.bond(i) / sp.bond(i - 1))))
    })
    WorldStats(
      vol  = med(rets.map(r => math.sqrt(MatD(r).power(2).mean * DaysPerYear))),
      kurt = med(rets.map(kurtosis)),
      ac1  = med(rets.map(r => autocorrAbs(r, 1))),
      ac20 = med(rets.map(r => autocorrAbs(r, 20))),
      annRet = med(sims.map(s => math.log(s.price.last / s.price.head) / years * 100.0)),
      nEpisodes = eps.size, epPerPath = eps.size.toDouble / sims.size,
      depthMed = med(eps.map(_.depthPct)), worstDepth = eps.map(_.depthPct).minOption.getOrElse(Double.NaN),
      vCount = shapes.count(_ > 1.5), midCount = shapes.count(x => x >= 0.67 && x <= 1.5),
      uCount = shapes.count(_ < 0.67), nShapes = shapes.size, censored = eps.count(_.censored),
      clampPct = sims.map(_.clampedDays.toLong).sum / days * 100.0,
      trendShare = sims.map(_.meanTrendShare).sum / sims.size, yearsPerPath = years.toDouble,
      trendPinned = sims.map(_.trendPinned).sum / sims.size,
      targetSat = sims.map(_.targetSat).sum / sims.size,
      // Median over non-overlapping BondVolYears windows, pooled across paths -- see BondVolYears
      // for why this row alone is windowed.  A path shorter than one window contributes itself, so
      // a short run still reports something rather than nothing.
      bondVol = med(sims.flatMap { s =>
        val r = dailyReturns(s.bond)
        val w = BondVolYears * DaysPerYear
        val nw = r.length / w
        val segs = if nw < 1 then Vector(r) else (0 until nw).toVector.map(k => r.slice(k * w, (k + 1) * w))
        segs.map(seg => math.sqrt(MatD(seg).power(2).mean * DaysPerYear))
      }),
      bondGrowth = bondInWindows(false), bondInfl = bondInWindows(true),
      corrCalm = corrIn(false), corrInfl = corrIn(true),
      meanBondStress = sims.map(_.meanBondStress).sum / sims.size,
      pctBondStress = sims.map(_.pctBondStress).sum / sims.size,
      crowdFlow = sims.map(_.meanCrowdFlow).sum / sims.size,
      duration = sims.head.duration,
      inflAnn = med(sims.map(s => math.log(s.cpi.last / s.cpi.head) / years * 100.0)),
      ddEq5  = med(ddEq.map(_._1)), ddEq10 = med(ddEq.map(_._2)), ddEq20 = med(ddEq.map(_._3)),
      ddBd5  = med(ddBd.map(_._1)), ddBd10 = med(ddBd.map(_._2)), ddBd20 = med(ddBd.map(_._3)))

  /** The gate answers three different questions and used to report one verdict.  Each class names
    * what a failure costs, and a report declares which classes it requires (`-gate`).
    *
    * REALISM asks "is this world a market at all".  Its checks are unconditional distributional
    * properties of the whole sample, and a failure invalidates every conclusion drawn here.
    *
    * MECHANISM asks "is this mechanism engaged in this world".  Its checks are all conditional on
    * crash or inflation EPISODES, and a failure invalidates only conclusions that lean on the named
    * mechanism.  A world can be a perfectly good market with an inert bond spiral — the duration-6y
    * world is exactly that, and a single verdict discarded it from every pooled panel.
    *
    * FIDELITY asks "can this quantity's LEVEL be read here".  A failure invalidates only
    * conclusions that read a level off the named quantity — a time-out-of-market, a percentile
    * threshold, a drawdown-conditioned hazard — and leaves rank comparisons, cost breakevens, ruin
    * rates and refuge mechanics untouched.  It exists because a world can pass every realism band
    * and every mechanism check while a statistic those bands do not pin sits far from reality: the
    * default world's bond spends 84% of sessions more than 10% below its running peak where a real
    * long Treasury spends 51%, and a 10%-drawdown gate REVERSES SIGN between them.
    *
    * The realism/mechanism split also explains the export-time false alarm: the four conditional
    * statistics cannot be measured from one short path, so `-emit` takes its verdict from an
    * ensemble (`-emitgate`). */
  enum GateClass:
    case Realism, Mechanism, Fidelity

  /** How far a depth share may sit from the real one and still have a readable level.  The plan's
    * acceptance for W9 is that "a drawdown-rule arm's %out lands within a few points of the same
    * rule's %out on a real series"; ten percentage points is that, made two-sided and concrete.
    * ABSOLUTE, not relative: the quantity being compared — a rule's share of sessions out of the
    * market — is itself a share, so a point is the same size at every rung. */
  val DepthTol = 0.10

  /** TWO-SIDED wherever a plausible range exists.  History of this gate: a one-sided version
    * passed a 35%-volatility world (the one reversing the ranking); a "bonds fail" check written
    * as bondInfl < bondGrowth passed while bonds still RALLIED +2.8; crash frequency shipped
    * without an upper bound WHILE the one-sided lesson was being applied elsewhere in this file. */
  def gateChecks(st: WorldStats): Vector[(String, Boolean, GateClass)] =
    import GateClass.*
    val base = Vector(
      bandCheck("equity vol",       st.vol * 100.0, 8.0, 25.0, Realism, dp = 0, unit = "%"),
      bandCheck("kurtosis",         st.kurt, 4.0, 30.0, Realism, dp = 0),
      ("clustering 0.10-0.40",      st.ac1 > 0.10 && st.ac1 < 0.40 && st.ac20 > 0.03, Realism),
      ("crash rate 8-45/century",   st.epPerPath >= 1.0 && {
          val pc = st.epPerPath * 100.0 / st.yearsPerPath; pc >= 8.0 && pc <= 45.0 }, Realism),
      // max(1, _) is load-bearing.  nShapes / 10 is INTEGER division, so below ten shapes both
      // clauses read ">= 0" and the check passes with NEITHER shape present -- measured at
      // -drift 0.9, which produced V=0, balanced=1, U=0 and passed a check named "both
      // recovery shapes".  It degenerated exactly where episodes are scarce, which is where
      // shape evidence is weakest and the check matters most.  Requiring at least one of each
      // makes too-few-shapes FAIL: a run that has not demonstrated both shapes has not
      // demonstrated both shapes, and a gate that passes on no evidence reads as verification.
      ("both recovery shapes",      st.nShapes > 0
                                     && st.vCount >= math.max(1, st.nShapes / 10)
                                     && st.uCount >= math.max(1, st.nShapes / 10), Realism),
      ("no runaway drift",          st.annRet.abs < 30.0, Realism),
      // 0.02% ~ one clamped session per 20 path-years.  The old bound (0.5%) would have passed a
      // world where the clamp was already reshaping kurtosis by a third.
      ("clamp rarely binds",        st.clampPct < 0.02, Realism),
      // RELATIVE to duration, not absolute.  The old 7-20% band was TLT's: of eight real funds it
      // admitted one, and asserted of the US Aggregate (4.24%) that it is not a market.  0.5-2.5
      // per year of duration admits every fund measured, high yield at 2.001 included, and still
      // catches a bond whose volatility bears no relation to what it is.
      bandCheck("bond vol", st.bondVolPerYear, 0.5, 2.5, Realism, dp = 1, unit = "x duration"),
      ("bonds rally in growth shocks",    st.bondGrowth > 3.0, Mechanism),
      ("bonds LOSE in inflation regimes", st.bondInfl < -3.0, Mechanism),
      ("corr flips positive under inflation",
          !st.corrInfl.isNaN && !st.corrCalm.isNaN &&
          st.corrInfl > st.corrCalm + 0.15 && st.corrInfl > 0.0 && st.corrCalm < 0.35, Mechanism),
      ("bond spiral engages, not always", st.pctBondStress > 0.002 && st.pctBondStress < 0.5, Mechanism),
      bandCheck("inflation",        st.inflAnn, 1.0, 6.0, Realism, dp = 0, unit = "%/yr"),
      // LEVEL bands, not realism.  A 12%-volatility market is still a market, and realism is
      // ALWAYS required — either band placed there would make the sweep's own OFF-worlds
      // inadmissible in every report ("no liquidity spiral" runs at 12.6% vol, "low growth" at
      // 0.34).  Class does not weaken them as a search constraint: the calibration loss counts
      // 0.5 per failed check whatever the class.  Volatility keeps its realism band as well —
      // 8-25% answers "is this a market", 14-18% answers "can its level be read".
      bandCheck("equity vol",       st.vol * 100.0, 14.0, 18.0, Fidelity, dp = 0, unit = "%"),
      // 0.50 clears the 1926-2026 reading (0.55) downward; 0.85 sits above the 1954-2026 anchor
      // (0.69) and below the most favourable non-overlapping 20-year block the record produced
      // (0.93).  A world may be as favourable as a long-horizon market, not as favourable as its
      // luckiest two decades.  The 20-year block SPREAD (0.47-0.93) is deliberately NOT the band:
      // that is sampling variation in a 20-year window, and this statistic is a population value
      // over 20,000 path-years -- a band drawn from it would readmit worlds at 0.91.
      bandCheck("return per vol",   st.retVol, 0.50, 0.85, Fidelity),
      // Only the rungs with a measured real anchor are gated.  The bond's >5% and >20% shares are
      // reported everywhere but targeted nowhere: interpolating them would manufacture an anchor.
      depthCheck("equity >5% below peak",  st.ddEq5,  0.447),
      depthCheck("equity >10% below peak", st.ddEq10, 0.315),
      depthCheck("equity >20% below peak", st.ddEq20, 0.169),
    )
    // The two anchor-fitted bands are graded ONLY where their anchors have data -- the same
    // refusal `-crossasset` applies, because these ARE its relations.  A world outside the funds'
    // range used to print FAIL here while the ladder printed n/a for the same statistic,
    // conflating "the level is wrong" with "there is no anchor to compare against".  A skipped
    // check is disclosed by `unanchoredIn` -- in `-validate` and the sidecar -- never silently
    // absent.
    val depthBand =
      if anchored(st.bondVol * 100.0, BondVolSupport, st.bondDepthVsVol) then
        // Against what this bond's OWN volatility implies, not against TLT's 0.510 -- see
        // `bondDepthVsVol`.  The +-0.35 is the real fit's own scatter (credit funds sit below the
        // Treasury line); the default reads 1.24, so it uses about two thirds of it.
        Vector(bandCheck("bond depth vs its vol", st.bondDepthVsVol, BondD10Band._1, BondD10Band._2, Fidelity))
      else Vector.empty
    val volBand =
      if anchored(st.duration, BondDurSupport, st.bondVolPerYear) then
        // Treasuries run 0.798-0.973 and investment grade 0.745-0.824; high yield (2.001) is out
        // of scope until there is a credit channel, so the upper bound deliberately excludes it.
        Vector(bandCheck("bond vol", st.bondVolPerYear, BondVolPerYearBand._1, BondVolPerYearBand._2, Fidelity, unit = "x duration"))
      else Vector.empty
    base ++ depthBand ++ volBand

  /** Whether an anchor-fitted band can be graded here: its driving variable inside the range the
    * anchor funds covered, and the statistic defined.  Mirrors `Relation.grade`'s refusal. */
  def anchored(driver: Double, support: (Double, Double), got: Double): Boolean =
    driver >= support._1 && driver <= support._2 && !got.isNaN

  /** The anchor-fitted fidelity bands `gateChecks` did NOT grade here, each with its reason:
    * driving variable outside the anchors' range, or the relation undefined at this volatility.
    * Disclosed -- printed by `-validate`, recorded in the sidecar's `fidelityUnanchored` -- rather
    * than failed, because "no anchor to compare against" and "the level is wrong" are different
    * findings and only one of them is about the model. */
  def unanchoredIn(st: WorldStats): Vector[String] =
    val vol = st.bondVol * 100.0
    val depth =
      if anchored(vol, BondVolSupport, st.bondDepthVsVol) then Vector.empty
      else
        val why = if vol < BondVolSupport._1 || vol > BondVolSupport._2 then
          f"bond vol $vol%.2f%% outside the anchors' ${BondVolSupport._1}%.2f-${BondVolSupport._2}%.2f%%"
        else f"no fitted value below $BondD10Zero%.2f%% bond vol"
        Vector(s"bond depth vs its vol ($why)")
    val volPer =
      if anchored(st.duration, BondDurSupport, st.bondVolPerYear) then Vector.empty
      else Vector(f"bond vol x duration (duration ${st.duration}%.2fy outside the anchors' ${BondDurSupport._1}%.2f-${BondDurSupport._2}%.2fy)")
    depth ++ volPer

  /** A gate whose printed name is DERIVED from the bounds its predicate tests, so the two cannot
    * drift apart — the failure mode where a gate reads as bounds it does not enforce.  Every
    * two-sided band that can go through here does: a hand-written "0.65-1.35" inside a name is
    * the same defect this helper exists to prevent, wherever it is written.
    *
    * `dp` is printed PRECISION, not tolerance: the depth rungs read 0.215-0.415 and are quoted at
    * that precision in the CHANGELOG and the upgrade plan, while the duration ratios read
    * 0.70-1.10.  `unit` is whatever follows the band in the name.  A caller whose printed units
    * differ from the statistic's passes the CONVERTED value (`st.vol * 100` against 8-25), so the
    * band and the value compared against it are in the same units by construction.
    *
    * Two bands stay hand-written, because the name would stop describing the predicate if they
    * came through here: `clustering` also enforces an ac20 floor and `crash rate` also requires at
    * least one episode.  Both are two-sided with visible bounds; what they are not is one clause. */
  def bandCheck(name: String, got: Double, lo: Double, hi: Double, cls: GateClass,
                dp: Int = 2, unit: String = ""): (String, Boolean, GateClass) =
    val fmt = s"%.${dp}f"
    (s"$name ${fmt.format(lo)}-${fmt.format(hi)}$unit", got > lo && got < hi, cls)

  /** A depth rung's band is the real anchor plus or minus `DepthTol`, so only the anchor is
    * written down and the two bounds cannot be given independently. */
  def depthCheck(name: String, got: Double, real: Double): (String, Boolean, GateClass) =
    bandCheck(name, got, real - DepthTol, real + DepthTol, GateClass.Fidelity, dp = 3)

  def failedIn(st: WorldStats, cls: GateClass): Vector[String] =
    gateChecks(st).collect { case (n, false, c) if c == cls => n }

  /** Heading and what a failure costs, printed in this order.  Kept beside the enum so a new class
    * cannot be added without saying out loud which conclusions it kills. */
  val GateSections: Vector[(GateClass, String, String)] = Vector(
    (GateClass.Realism,   "realism bands",        "a failure here means this world is not a market"),
    (GateClass.Mechanism, "mechanism engagement", "a failure here means only that mechanism is inert"),
    (GateClass.Fidelity,  "level fidelity",       "a failure here means only that quantity's LEVEL cannot be read"),
  )

  /** Admissibility under the classes a report has declared it requires.  A class not required is a
    * class whose failures are disclosed and tolerated, which is the whole point of the split. */
  def gateOk(st: WorldStats, required: Set[GateClass]): Boolean =
    gateChecks(st).forall((_, ok, c) => ok || !required.contains(c))

  /** The historical binary verdict: a market with its mechanisms live.  Level fidelity is NOT in
    * it, so every report keeps the admissibility it had before the depth profile was measured —
    * a consumer that reads levels asks for `fidelity` explicitly. */
  val GateDefault = Set(GateClass.Realism, GateClass.Mechanism)

  /** Realism is ALWAYS in the result: its failure means the world is not a market, which no report
    * can declare itself indifferent to.  Without this, `-gate fidelity` on a realism-failing world
    * exits 0 — an admissibility check that can be configured into admitting non-markets. */
  def parseGate(spec: String): Set[GateClass] =
    val classes = spec.toLowerCase.split(",").map(_.trim).filter(_.nonEmpty).flatMap {
      case "realism"   => Vector(GateClass.Realism)
      case "mechanism" => Vector(GateClass.Mechanism)
      case "fidelity"  => Vector(GateClass.Fidelity)
      case "all" | "full" => GateClass.values.toVector
      case other => usage(s"unknown -gate class [$other]; use realism, mechanism, fidelity or all")
    }.toSet
    if classes.isEmpty then usage(s"-gate got no classes in [$spec]; use realism, mechanism, fidelity or all")
    classes + GateClass.Realism

  /** Scalar calibration loss: weighted |log(model/target)| over the fidelity targets, a penalty of
    * 2 for a wrong sign, and 0.5 per failed gate check.  Exists so calibration is a SEARCH against
    * a fixed objective instead of eyeballing console output — eyeball tuning at 60 years produced a
    * -99% world at 100 years. */
  /** Reference relative sd for the precision factor below: a weight of `judgment` means "as
    * measurable as a target whose single-history sd is 20% of its anchor" -- near the median of
    * the measured set, and chosen so the weights SUM to about what the equal-precision
    * objective's did (12.2 against 12.5), which keeps the 0.5-per-failed-gate penalty at its
    * established bite. */
  val SdRelRef = 0.20

  /** A fidelity weight: JUDGMENT x measured PRECISION.
    *
    * `judgment` carries what a number cannot: redundancy (the three depth rungs are one
    * distribution read three times), scope (kurtosis is a recorded exclusion), and importance.
    * `sdRel` is the target's single-history sd over its anchor, measured by `-noise` at the
    * anchor's OWN horizon -- 2026-08-25, 200 paths, the default world -- and FROZEN here exactly
    * as the anchors themselves are.  Frozen is load-bearing: computed live, a candidate world
    * under `-calibrate` that widens its own spread would down-weight its own misses.  Re-measure
    * by running `-noise`, then change these literals deliberately.
    *
    * No cap on the precision factor: the measurement says equity vol is the best-pinned target in
    * the set (sd/real 0.10), and capping its weight would re-smuggle the equal-measurability
    * assumption this function exists to remove. */
  def wgt(judgment: Double, sdRel: Double): Double = judgment * (SdRelRef / sdRel)

  val FitTargets: Vector[(String, WorldStats => Double, Double, Double)] = Vector(
    ("equity vol %",       st => st.vol * 100,                              16.0,  wgt(1.0, 0.10)),
    // Ken French F-F_Research_Data_Factors, US total market (Mkt-RF + RF), measured in the units
    // this row is compared in: annualised LOG return over sqrt(mean(r^2) * 252) on DAILY data.
    // Both conversions matter -- a CAGR read as a simple rate and a monthly-derived volatility
    // each inflate the ratio, and together they turned a 0.69 anchor into 0.76.
    //   1954-2026 (the window of the rows around this one)  10.82%/yr over 15.68%  =  0.69
    //   1926-2026 (the only 100-year sample there is)        9.38%/yr over 17.14%  =  0.55
    // The target stays on the anchor window so the target set is internally consistent, NOT
    // because 0.55 is the wrong reading for a generator scored on 100-year paths; the gate band
    // below admits it rather than legislating it away.
    ("return per vol",     st => st.retVol,                                  0.69, wgt(1.0, 0.20)),
    ("kurtosis",           st => st.kurt,                                   28.0,  wgt(0.5, 0.14)),
    // Ken French / CRSP value-weighted US market, daily, 1926-07-01..2026-06-30 -- the FULL
    // century, and deliberately NOT the 1954-2026 window the rows above use.  The model's
    // clustering is horizon-INDEPENDENT (0.320 at 20 years, 0.330 at 150) while the real statistic
    // is not (0.271 over 72 years, 0.299 over 100, and 0.175-0.311 across non-overlapping 20-year
    // blocks), because a longer window spans more regimes.  The model is scored on 100-year paths,
    // so a 72-year anchor compares a 100-year model reading against a 72-year real one and reports
    // 1.22 where the horizon-matched answer is 1.07.
    //
    // CONVENTION, stated because its absence is what blocked this for a release: autocorrelation of
    // |r| about its mean, normalised by the FULL-series sum of squares -- `autocorrAbs` itself.
    // `jsrc/clusteringAnchor.sc` calls that same function to measure the anchor, so the two cannot
    // drift.  On this data autocorr(r^2) reads 0.108 at lag 20 against 0.208 for |r|, 92% apart: a
    // re-derivation using the wrong one would conclude the model is 2.2x too high rather than 1.07.
    //
    // The 20-year block spread is wide enough that an honestly derived BAND (about 0.16-0.33 at
    // lag 1) would not exclude the model.  Real clustering varies by nearly two-to-one between
    // eras; a band tight enough to fail this world would have to exclude two of the five real
    // 20-year eras, which is a band chosen to produce a verdict rather than derived from a record.
    ("clustering lag 1",   st => st.ac1,                                    0.299, wgt(1.0, 0.09)),
    ("clustering lag 20",  st => st.ac20,                                   0.225, wgt(0.5, 0.11)),
    ("crashes/century",    st => st.epPerPath * 100.0 / st.yearsPerPath,    20.7,  wgt(1.0, 0.22)),
    ("median depth %",     st => st.depthMed,                              -27.1,  wgt(1.0, 0.15)),
    // Judgment 0.5, DOWN from 1.0, on `-noise`'s finding: graded at 100 years against a
    // 72-year anchor this ratio is mostly a max-order-statistic horizon artifact (at the
    // anchor's own horizon the record sits at the model's 61st percentile).  Until the target
    // is horizon-matched, weighting it fully would push `-calibrate` to close an artifact.
    ("worst crash %",      st => st.worstDepth,                            -56.8,  wgt(0.5, 0.17)),
    // The "(24y)" is load-bearing, not decoration: this row is measured on a different horizon
    // from every other, and the label is the only part that travels when the number is quoted.
    ("bond vol % (24y)",   st => st.bondVol * 100,                          13.0,  wgt(1.0, 0.51)),
    ("bond growth-crash",  st => st.bondGrowth,                             20.0,  wgt(1.0, 0.39)),
    // The judgment stays at 1.5 -- inflation-crash behaviour is why the bond refuge exists --
    // and the measured precision crushes the weight to ~0.13 anyway: sd/real 2.89, and only
    // 95 of 200 24-year histories produce a reading at all.  The old 1.5 was the largest
    // weight in the loss on the least measurable target in the set.
    ("bond infl-crash",    st => st.bondInfl,                              -25.0,  wgt(1.5, 2.89)),
    // DEPTH PROFILE.  Real equity anchors are SPY 1993-01-29..2026-08-20 (8447 sessions) — a
    // different window from the 1954-2026 record behind the rows above, and named as such in the
    // report, because this is a TIME SHARE rather than a max order statistic: it is horizon-stable
    // where maximum drawdown is not (measured: the model's >10% share is 0.464 at both 20 and 100
    // years), so the two windows are comparable in a way maxDD's would not be.
    //
    // HORIZON-stable is not WINDOW-stable, and the difference is large enough to matter.  The real
    // 10% rung reads 0.269 over 1954-2026, 0.315 over 1993-2026 and 0.386 over 1926-2026.  The
    // +-0.10 gate bands span that spread, which is part of why they pass; do not read a passing
    // depth rung as agreement with a particular window.
    //
    // Validated against CRSP value-weighted, 33-year windows inside 1954-2026 — a series the
    // calibration never used FOR THIS STATISTIC.  Model 0.49 / 0.33 / 0.13 against a real median of
    // 0.451 / 0.291 / 0.151, ranges 0.405-0.507 / 0.219-0.346 / 0.084-0.184: all three rungs land
    // inside the observed real range, shallow ones ~10% high, the deep one ~14% low.  A LEVEL bias
    // in both directions, and it survives the gate.
    //   NOT fully independent: `return per vol` is anchored on CRSP 1954-2026, and r/v is the
    //   strongest single driver of the depth profile (d(10% rung)/d(vol) = 0.71 for `drift` against
    //   0.024 for `depth`).  Independent in drawdown SHAPE, not in the level of the quantity that
    //   most determines it.  SPY cannot serve at all: its rungs ARE the targets.
    // The bond anchor is a clean iShares TLT total-return series over 24 years, and only the 10%
    // rung of it has been measured.  The other two bond rungs are REPORTED, not targeted: filling
    // them in by interpolation would manufacture a calibration anchor out of nothing.
    ("equity >5% below pk", st => st.ddEq5,                                  0.447, wgt(0.5, 0.22)),
    ("equity >10% below pk",st => st.ddEq10,                                 0.315, wgt(1.0, 0.34)),
    ("equity >20% below pk",st => st.ddEq20,                                 0.169, wgt(0.5, 0.55)),
    ("bond depth vs vol",   st => st.bondDepthVsVol,                          1.00, wgt(0.5, 0.33)),
  )
  def fitness(st: WorldStats): (Double, Vector[(String, Double, Double, Double)]) =
    val rows = FitTargets.map { (name, get, target, weight) =>
      val m = get(st)
      val term =
        if m.isNaN then weight * 4.0
        else if m.sign != target.sign && target != 0.0 then
          weight * (2.0 + math.abs(math.log(math.abs(m).max(1e-6) / math.abs(target))))
        else weight * math.abs(math.log(math.abs(m).max(1e-6) / math.abs(target)))
      (name, m, target, term)
    }
    val gatePenalty = gateChecks(st).count(!_._2) * 0.5
    (rows.map(_._4).sum + gatePenalty, rows)

  // ---- exposure rules ------------------------------------------------------------------------
  val Band = 0.05

  def banded(target: Array[Double]): Array[Double] =
    val out = new Array[Double](target.length)
    var held = 1.0
    var i = 0
    while i < target.length do
      if math.abs(target(i) - held) > Band then held = target(i)
      out(i) = held
      i += 1
    out

  def trailingMean(px: Array[Double], win: Int): Array[Double] =
    val out = new Array[Double](px.length)
    var s = 0.0
    var i = 0
    while i < px.length do
      s += px(i)
      if i >= win then s -= px(i - win)
      out(i) = s / math.min(i + 1, win)
      i += 1
    out

  def sessionsFor(calDays: Int): Int = math.max(2, math.round(calDays * 252.0 / 365.25).toInt)

  final class Indicators(val px: Array[Double]):
    private val maCache = scala.collection.mutable.HashMap.empty[Int, Array[Double]]
    def ma(sessions: Int): Array[Double] = maCache.getOrElseUpdate(sessions, trailingMean(px, sessions))
    lazy val volRatio: Array[Double] =
      val n  = px.length
      val rv = new Array[Double](n)
      var ew = 0.01 * 0.01
      var i  = 1
      while i < n do
        val r = math.log(px(i) / px(i - 1))
        ew = 0.94 * ew + 0.06 * r * r
        rv(i) = math.sqrt(ew * DaysPerYear)
        i += 1
      val lower = scala.collection.mutable.PriorityQueue.empty[Double]
      val upper = scala.collection.mutable.PriorityQueue.empty[Double](using Ordering[Double].reverse)
      val out = new Array[Double](n)
      out(0) = 1.0
      i = 1
      while i < n do
        if i > 260 then
          val x = rv(i)
          if lower.isEmpty || x <= lower.head then lower.enqueue(x) else upper.enqueue(x)
          if lower.size > upper.size + 1 then upper.enqueue(lower.dequeue())
          else if upper.size > lower.size then lower.enqueue(upper.dequeue())
          out(i) = if rv(i) > 0 then lower.head / rv(i) else 1.0
        else out(i) = 1.0
        i += 1
      out

  final case class Rule(name: String, expose: Indicators => Array[Double])

  def trendRule(calDays: Int, floor: Double) =
    Rule(f"trend $calDays%dd, floor ${floor * 100}%.0f%%", ind =>
      val ma = ind.ma(sessionsFor(calDays))
      banded(Array.tabulate(ind.px.length)(i => if ind.px(i) >= ma(i) then 1.0 else floor)))

  def drawdownRule(pct: Double, floor: Double) =
    Rule(f"cut below -${pct}%.0f%%, floor ${floor * 100}%.0f%%", ind =>
      val px = ind.px
      val out = new Array[Double](px.length)
      var pk = 0.0
      var i = 0
      while i < px.length do
        pk = math.max(pk, px(i))
        out(i) = if px(i) < pk * (1.0 - pct / 100.0) then floor else 1.0
        i += 1
      banded(out))

  def volRule(floor: Double) =
    Rule(f"volatility-scaled, floor ${floor * 100}%.0f%%", ind =>
      banded(ind.volRatio.map(r => math.max(floor, math.min(1.0, r)))))

  def comboRule(calDays: Int, floor: Double) =
    Rule(f"volatility + trend $calDays%dd, floor ${floor * 100}%.0f%%", ind =>
      val ma = ind.ma(sessionsFor(calDays))
      banded(Array.tabulate(ind.px.length) { i =>
        val v = math.min(1.0, math.max(0.0, ind.volRatio(i)))
        val t = if ind.px(i) >= ma(i) then 1.0 else 0.0
        math.max(floor, math.min(v, t))
      }))

  val Rules: Vector[Rule] = Vector(
    Rule("always fully invested", ind => Array.fill(ind.px.length)(1.0)),
    volRule(0.4),                       // production analog — the paired-comparison reference
    volRule(0.0),
    trendRule(150, 0.0),
    trendRule(200, 0.4),
    trendRule(200, 0.0),
    trendRule(250, 0.0),
    drawdownRule(10, 0.0),
    comboRule(200, 0.0),
  )
  val RefIdx = 1

  // ---- evaluation ----------------------------------------------------------------------------
  // NOTE ON FRAMES: differences of annual returns (vsFlat, bonds-minus-cash, the decomposition)
  // are DEFLATOR-INVARIANT — subtracting the same inflation from both sides cancels.  What real
  // grading changes is the PATH: real drawdowns, real ruin, and the sustainable withdrawal rate.
  // Those are the quantities the 1970s bond story lives in (flat nominal, catastrophic real), and
  // swr is the pipeline's own decision lens for the cash-vehicle question.
  final case class Outcome(annG: Double, ann: Double, maxDD: Double, realDD: Double, meanE: Double,
                           vsFlatG: Double, vsFlat: Double, churn: Double, effChurn: Double,
                           swr: Double, swrLo: Double):
    def slipMult: Double = if churn > 1e-9 then effChurn / churn else 1.0

  /** Highest constant REAL withdrawal (% of starting balance/yr, inflation-adjusted monthly) the
    * REAL equity path could sustain for 30 years without exhaustion; closed-form, evaluated at
    * every rolling monthly start via prefix sums.  Returns (median start, worst start). */
  def swrStats(realLogEq: Array[Double]): (Double, Double) =
    val monthly = Array.tabulate(realLogEq.length / 21)(m => realLogEq(m * 21))
    val window = 360                                       // 30 years of monthly withdrawals
    if monthly.length < window + 1 then (Double.NaN, Double.NaN)
    else
      // closed form: the window starting at st survives withdrawal w iff
      //   w <= 1 / sum over the window of exp(C_st - C_t),  C = cumulative real log growth
      val rel    = MatD(monthly) - monthly(0)
      val pref   = (rel * -1.0).exp.cumsum.toArray         // pref(t) = sum of exp(-C_u), u <= t
      val expRel = rel.exp.toArray
      val rates = (0 until monthly.length - window).map { st =>
        1200.0 / (expRel(st) * (pref(st + window) - pref(st)))
      }
      (pctile(rates, 0.5), rates.min)

  enum Safe:
    case Cash, Bond

  def fundamentalLed(p: Path, ep: Episode): Boolean =
    val pd = math.log(p.price(ep.trough) / p.price(ep.peak))
    val fd = math.log(p.fundamental(ep.trough) / p.fundamental(ep.peak))
    fd <= 0.5 * pd

  final case class ArmPath(logEq: Array[Double], realLogEq: Array[Double], steps: Array[Double],
                           meanE: Double, churn: Double, effChurn: Double, costPaid: Double,
                           eqRetSum: Double, safeRetSum: Double)

  /** What ONE arm actually earned: its log-equity path, the real counterpart, the daily steps, and
    * the trading totals.  Everything that grades an arm reads this, so no two reports can disagree
    * about what the arm did (the same single-source rule as vast.invest.EtfBasket.weightedReturn).
    * The arm is given as an exposure ARRAY, not as a Rule, because the exposure-matched constant
    * twin is not a rule -- it is derived from the rule it controls for. */
  def armPath(p: Path, e: Array[Double], cost: Double, safe: Safe): ArmPath =
    val n = p.price.length
    // day i earns: exposure e(i-1) times the asset return, the remainder times the safe return,
    // minus |exposure change| * cost * that session's liquidity state -- written as that arithmetic
    val eqRets   = MatD(dailyReturns(p.price))
    val safeRets = safe match
      case Safe.Cash => MatD(Array.tabulate(n - 1)(k => math.log1p(p.rate(k)) / DaysPerYear))
      case Safe.Bond => MatD(dailyReturns(p.bond))
    val eHeld = MatD(e).head(n - 1)
    val dE    = MatD(Array.tabulate(n - 1)(k => math.abs(e(k + 1) - e(k))))
    // tail is end-anchored where the old copyOfRange(p.liq, 1, n) was start-anchored; these
    // agree because every Path series is allocated at `tot` and dropped by BurnIn together
    val liqT  = MatD(p.liq).tail(n - 1)
    val costs = dE * cost * liqT
    val steps = eHeld * eqRets + (1.0 - eHeld) * safeRets - costs
    val eq    = new Array[Double](n)
    System.arraycopy(steps.cumsum.toArray, 0, eq, 1, n - 1)
    val realEq = (MatD(eq) - MatD(Array.tabulate(n)(k => math.log(p.cpi(k) / p.cpi(0))))).toArray
    ArmPath(eq, realEq, steps.toArray, e.sum / e.length,
            dE.sum, (dE * liqT).sum, costs.sum, eqRets.sum, safeRets.sum)

  /** vsFlat is the advantage over a CONSTANT portfolio holding this rule's own average exposure IN
    * THE SAME TWO ASSETS — with safe = Bond it is timing versus a static equity/bond mix, the house
    * matched-constant control applied at the two-asset level. */
  def evaluate(p: Path, eps: Vector[Episode], fundLed: Vector[Boolean], rule: Rule,
               ind: Indicators, cost: Double, years: Int,
               safe: Safe): (Outcome, Vector[(Boolean, Double, Double)]) =
    val n  = p.price.length
    val ap = armPath(p, rule.expose(ind), cost, safe)
    val eq = ap.logEq
    val churn = ap.churn; val effChurn = ap.effChurn; val costPaid = ap.costPaid
    // maximum drawdown IS this formula; the scalar scan existed only because cummax was missing
    val eqV     = MatD(eq)
    val realEqV = MatD(ap.realLogEq)
    val mdd  = 1.0 - (eqV - eqV.cummax(0)).exp.min
    val mddR = 1.0 - (realEqV - realEqV.cummax(0)).exp.min
    val (swrMed, swrLow) = swrStats(ap.realLogEq)
    val me = ap.meanE
    // the constant twin never trades, so its return is exactly linear in the two totals
    val annN = eq(n - 1) / years * 100.0
    val annG = (eq(n - 1) + costPaid) / years * 100.0
    val flat = (me * ap.eqRetSum + (1.0 - me) * ap.safeRetSum) / years * 100.0
    val perEp = eps.zip(fundLed).map { (ep, fl) =>
      val end = if ep.recovered >= 0 then ep.recovered else n - 1
      (fl, eq(end) - eq(ep.peak), math.log(p.price(end) / p.price(ep.peak)))
    }
    (Outcome(annG, annN, mdd * 100.0, mddR * 100.0, me, annG - flat, annN - flat,
             churn / years, effChurn / years, swrMed, swrLow), perEp)

  // ---- candidate grading statistics ----------------------------------------------------------
  // The pipeline's deferred decisions are all stuck behind the same sentence: "the paired CI spans
  // zero".  That is a statement about the MEASURING INSTRUMENT as much as about the arms, and the
  // instrument can be characterised here in a way one real history cannot characterise it.

  /** The exposure-matched constant twin of a rule ON THIS PATH: the same average exposure, held
    * flat, in the same two assets.  It is the house control — against it a rule can only win by
    * TIMING, never by posture, which is the confound that makes arms with different exposure
    * floors incomparable. */
  def matchedConstant(e: Array[Double]): Array[Double] =
    val m = e.sum / e.length     // bound FIRST: Array.fill's element is by-name, so an inline
    Array.fill(e.length)(m)      // e.sum here would recompute the mean n times -- O(n^2)

  /** Depth below the running peak, session by session — the series every depth measure reduces. */
  def drawdownSeries(logEq: Array[Double]): Array[Double] =
    val out = new Array[Double](logEq.length)
    var pk = logEq(0)
    var i = 0
    while i < logEq.length do
      pk = math.max(pk, logEq(i))
      out(i) = 1.0 - math.exp(logEq(i) - pk)
      i += 1
    out

  /** An underwater stretch: from a running peak until the path regains it.  A stretch still under
    * water at path end is INCLUDED at its length so far — censoring the unfinished episode is the
    * error the drawdown episodes already had to have fixed, and it is the LONG stretches that the
    * buffer question is about, so censoring them would remove exactly the evidence. */
  final case class Underwater(peak: Int, end: Int, worstDepth: Double):
    def sessions: Int = end - peak

  def underwater(logEq: Array[Double]): Vector[Underwater] =
    val out = scala.collection.mutable.ArrayBuffer.empty[Underwater]
    var pk = logEq(0); var pkI = 0; var i = 1
    while i < logEq.length do
      if logEq(i) >= pk then { pk = logEq(i); pkI = i; i += 1 }
      else
        var j = i; var worst = 0.0
        while j < logEq.length && logEq(j) < pk do
          worst = math.max(worst, 1.0 - math.exp(logEq(j) - pk))
          j += 1
        out += Underwater(pkI, j, worst)
        if j < logEq.length then { pk = logEq(j); pkI = j; i = j + 1 } else i = logEq.length
    out.toVector

  /** Worst depth reached only AFTER a stretch has outlasted a cash buffer of `bufSessions`.  NaN
    * when the stretch never exhausts the buffer — such an episode forces no sale and costs nothing,
    * so entering it as a zero would flatter the average with episodes that never happened. */
  def depthAtExhaustion(logEq: Array[Double], u: Underwater, bufSessions: Int): Double =
    val from = u.peak + bufSessions
    if from >= u.end then Double.NaN
    else
      val pk = logEq(u.peak)
      (from until u.end).map(k => 1.0 - math.exp(logEq(k) - pk)).max

  /** The candidate grading statistics for one arm, NAMED AT THE SOURCE so no report can mislabel a
    * column.  maxDD, Calmar and max-years-under are single order statistics and are here precisely
    * so the power report can price what reading one extremum costs; Ulcer is here because it was
    * measured on real data and rejected, and a second instrument should agree. */
  def gradingStats(ap: ArmPath, years: Int): Vector[(String, Double)] =
    val eq      = ap.logEq
    val n       = eq.length
    val depths  = drawdownSeries(eq)
    val depthsR = drawdownSeries(ap.realLogEq)
    val mu      = ap.steps.sum / ap.steps.length
    val sd      = math.sqrt(MatD(ap.steps).power(2).mean - mu * mu)
    val maxDD   = depths.max * 100.0
    val annRet  = eq(n - 1) / years * 100.0
    val longest = underwater(ap.realLogEq).map(_.sessions).maxOption.getOrElse(0)
    Vector(
      "annRet %/yr"        -> annRet,
      "Sharpe"             -> (if sd > 0 then mu / sd * math.sqrt(DaysPerYear) else Double.NaN),
      "maxDD %"            -> maxDD,
      "Calmar"             -> (if maxDD > 0 then annRet / maxDD else Double.NaN),
      "frac under (nom)"   -> depths.count(_ > 0).toDouble / n,
      "frac under (real)"  -> depthsR.count(_ > 0).toDouble / n,
      "Ulcer %"            -> math.sqrt(MatD(depths).power(2).mean) * 100.0,
      "max yrs under"      -> longest / DaysPerYear.toDouble,
      "real 30y SWR %"     -> swrStats(ap.realLogEq)._1,
    )

  val StatNames: Vector[String] = gradingStats(
    ArmPath(Array(0.0, 0.0), Array(0.0, 0.0), Array(0.0), 1.0, 0, 0, 0, 0, 0), 1).map(_._1)

  /** `%+w.df`, except that a rendering whose digits are ALL ZERO carries no sign.  The quantity
    * is zero to the precision shown, so a leading '-' there reports rounding NOISE as direction;
    * uni's own `numStr` blanks the sign for the same reason.  It matters here beyond tidiness: a
    * column whose true value is identically zero (the always-invested rule against buy-and-hold)
    * has nothing left in it but the last-ulp gap between the JVM's Math.log and libm's, which is
    * ~0.2% of calls at 1 ulp -- so without this the sign printed there is a coin flip, and the
    * Rust twin in rust/examples/market_sim.rs cannot agree with it. */
  def pm(x: Double, w: Int, d: Int): String =
    val wpart = if w > 0 then w.toString else ""
    val s = String.format(s"%+$wpart.${d}f", Double.box(x))
    if s.exists(_.isDigit) && s.forall(c => !c.isDigit || c == '0')
    then s.map(c => if c == '+' || c == '-' then ' ' else c)
    else s

  def pctile(v: Seq[Double], q: Double): Double =
    if v.isEmpty then Double.NaN else v.sorted.apply(math.min((v.size * q).toInt, v.size - 1))

  def simPaths(w: World, paths: Int, years: Int, seed: Long): Vector[Path] =
    java.util.stream.IntStream.range(0, paths).parallel()
      .mapToObj(k => simulate(w, years, seed + k.toLong * 7919L)).toArray()
      .toVector.map(_.asInstanceOf[Path])

  // ---- calibration search --------------------------------------------------------------------
  def calibrate(nSamples: Int, base: World, seed: Long): Unit =
    // depth, trendShare, drift and crowdImpact are in the search because they are the strongest
    // levers on the
    // two defects the eight below cannot reach.  depth carries crash frequency (at fixed stress,
    // 12 -> 24 takes it from 35 to 13 per century) but moves volatility in lockstep with it.
    // drift is the ONLY knob that moves the depth profile at constant volatility -- which is why
    // it cannot be searched without the return-per-vol band above, or the search buys the depth
    // rungs with a Sharpe no 20-year stretch of the real record produced.  Their CLI flags are
    // inert under -calibrate, exactly like the eight below.
    val ranges: Vector[(String, Double, Double, (World, Double) => World)] = Vector(
      ("depth",       10.0,  26.0, (w, x) => w.copy(depth = x)),
      ("trendShare",  0.05,  0.70, (w, x) => w.copy(trendShare = x)),
      ("drift",       0.06,  0.16, (w, x) => w.copy(drift = x)),
      ("crowdImpact", 0.01,  0.20, (w, x) => w.copy(crowdImpact = x)),
      ("stress",       2.0,   6.0, (w, x) => w.copy(stress = x)),
      ("valuePull",  0.010, 0.035, (w, x) => w.copy(valuePull = x)),
      ("volOfVol",   0.012, 0.030, (w, x) => w.copy(volOfVol = x)),
      ("easing",       0.0,  0.09, (w, x) => w.copy(easing = x)),
      ("refuge",       0.0,  0.20, (w, x) => w.copy(refuge = x)),
      ("duration",     8.0,  18.0, (w, x) => w.copy(duration = x)),
      ("inflSize",    0.03,  0.12, (w, x) => w.copy(inflSize = x)),
      ("discount",     3.0,  10.0, (w, x) => w.copy(discount = x)),
      ("margin",       0.0, 0.004, (w, x) => w.copy(margin = x)),
    )
    // the only RNG in the program that was not already NumPyRNG.  uniform(lo, hi) IS
    // lo + nextDouble() * (hi - lo), the expression written inline below, so the swap is 1:1 --
    // but the STREAM differs, so a previously recorded "best world" from -calibrate will not
    // reproduce.  Accepted: -calibrate is a search procedure, not a reported statistic.
    val sr = new NumPyRNG(seed ^ 0x5ca1ab1eL)
    val trainSeed = seed; val holdSeed = seed + 7777777L
    def score(w: World, s: Long): Double =
      // scored at 100-year paths: an 80-year protocol missed a worst-crash blowup that only
      // appears at the horizon actually used — tune at the scale you evaluate at
      fitness(measure(simPaths(w, 50, 100, s), 100))._1
    eprintln(s"calibrate: $nSamples samples, 50 paths x 100 years each; holdout re-score of top 5")
    val scored = (0 until nSamples).map { k =>
      val (w, desc) = ranges.foldLeft((base, List.empty[String])) { case ((wAcc, d), (nm, lo, hi, set)) =>
        val x = sr.uniform(lo, hi)
        (set(wAcc, x), f"$nm%s=$x%.4f" :: d)
      }
      val f = score(w, trainSeed)
      eprintln(f"  sample $k%3d  train loss $f%7.3f")
      (f, w, desc.reverse.mkString(" "))
    }.sortBy(_._1)
    println(f"top 5 of $nSamples%d, re-scored on the HELD-OUT seed:")
    scored.take(5).foreach { (f, w, d) =>
      val h = score(w, holdSeed)
      println(f"  train $f%7.3f   holdout $h%7.3f   $d%s")
    }
    println(f"current defaults: train ${score(base, trainSeed)}%.3f   holdout ${score(base, holdSeed)}%.3f")

  // ---- the world sweep -----------------------------------------------------------------------
  // The base world is the one main() built from the CLI — a rebuilt copy here silently ignored
  // every world flag in sweep mode once before (the dead-knob class, again).
  /** The world sweep, in one place: the strategy report, the power report and the buffer report all
    * have to be judged over the SAME worlds, or "it survives the sweep" means something different
    * in each of them. */
  /** Every world is tagged CHARACTER (false) or REFLEXIVE (true).  A character world varies what
    * the market is like; a reflexive world changes WHO IS TRADING, by handing the crowd a rule to
    * run.  They answer different questions and must never be averaged together — see
    * `runStrategySweep`, where the ranks are computed over each set separately.
    *
    * `withReflexive` is false for `-power` and `-buffer`: reflexivity is the point in the
    * rank-stability table, and a second-order effect on dispersion and crash dynamics elsewhere. */
  def sweepWorlds(base: World, single: Boolean, withReflexive: Boolean): Vector[(String, World, Boolean)] =
      if single then Vector(("baseline", base, false))
      else Vector(
        ("baseline",                   base, false),
        // RELATIVE, not absolute.  Absolute perturbation points are silently invalidated by a
        // change of defaults: at 0.19.1 the old pairs stopped bracketing the baseline entirely --
        // "few trend followers" (0.15) had 2.5x the baseline's trend followers and "deep market"
        // (15.0) was shallower than it.  A multiplier below 1 and one above cannot stop straddling
        // the base, so the property is structural instead of a thing to remember to re-check.
        // (A base of exactly 0 collapses both arms onto it; that is true of the existing relative
        // arms too, and `-stress 0` already has it.)
        // The mandate is a spring, so the REALIZED share moves far less than the mandate: these
        // arms span 0.19-0.30 realized against the baseline's 0.22.
        ("few trend followers",        base.copy(trendShare = base.trendShare / 3.0), false),
        ("many trend followers",       base.copy(trendShare = base.trendShare * 3.0), false),
        ("no liquidity spiral",        base.copy(stress = 0.0), false),
        ("severe liquidity spiral",    base.copy(stress = base.stress * 1.5), false),
        ("weak value anchor",          base.copy(valuePull = base.valuePull * 0.6), false),
        ("calm volatility",            base.copy(volOfVol = base.volOfVol * 0.5), false),
        ("turbulent volatility",       base.copy(volOfVol = base.volOfVol * 2.0), false),
        ("sticky capital",             base.copy(beta = 1.0), false),
        ("fickle capital",             base.copy(beta = 6.0), false),
        ("low growth",                 base.copy(drift = 0.060), false),
        ("high growth",                base.copy(drift = 0.140), false),
        ("shallow market",             base.copy(depth = base.depth * 0.8), false),
        ("deep market",                base.copy(depth = base.depth * 1.25), false),
        // NOT "cash leg only" any more: in v4 the rate level sets bond carry, and the zero floor
        // binds at low rates (an emergent zero-lower-bound) — the v2 label survived the refactor
        // that falsified it.  These now double as carry-level probes (low ~ 2022, high ~ 1970s).
        ("low rates / low carry",      base.copy(rateMean = 0.01), false),
        ("high rates / high carry",    base.copy(rateMean = 0.07), false),
        // OFF-world: refuge.  BOTH channels, because either alone leaves the bond a refuge by the
        // other route and the world stops being the off-switch it is labelled as.
        ("no refuge channel",          base.copy(easing = 0.0, refuge = 0.0), false),
        ("no margin coupling",         base.copy(margin = 0.0), false),          // OFF-world: margin
        ("double inflation severity",  base.copy(inflSize = base.inflSize * 2.0), false),
      ) ++ (if !withReflexive then Vector.empty else Vector(
        // TWO AXES, not two modes.  Before the momentum crowd got a strength dial there was only
        // one dimension here, so "which crowd" was the whole question; now a mode entry that does
        // not state a strength silently picks the default, which is not the interesting value.
        ("reflexive: crowd runs a vol rule",  base.copy(crowd = Crowd.VolScaled), true),
        // 0.12 is the stress case: admissible, where 0.25 fails the gate.
        ("reflexive: crowd pressed hard",     base.copy(crowdImpact = 0.12), true),
      ))

  /** One world's evaluation: per path, per arm (cash leg then bond leg), the `Outcome` plus its
    * per-crash-window entries `(fundamental-led?, rule log return, buy-and-hold log return)`.
    * Mirrors the Rust twin's `Evald`. */
  type Evald = Vector[Vector[(Outcome, Vector[(Boolean, Double, Double)])]]

  def runStrategySweep(paths: Int, years: Int, seed: Long, cost: Double, single: Boolean,
                       base: World, gateReq: Set[GateClass]): Unit =
    val worlds = sweepWorlds(base, single, withReflexive = true)
    eprintln(s"${worlds.size} worlds x $paths paths x $years years, ${Rules.size} rules x {cash,bond}")
    val results = worlds.map { (wname, w, reflexive) =>
      val sims = simPaths(w, paths, years, seed)
      val st = measure(sims, years)
      val ok = gateOk(st, gateReq)
      val evald = java.util.stream.IntStream.range(0, sims.size).parallel().mapToObj { k =>
        val s   = sims(k)
        val ind = new Indicators(s.price)
        val eps = episodes(s.price, 15.0)
        val fl  = eps.map(ep => fundamentalLed(s, ep))
        Rules.map(r => evaluate(s, eps, fl, r, ind, cost, years, Safe.Cash)) ++
        Rules.map(r => evaluate(s, eps, fl, r, ind, cost, years, Safe.Bond))
      }.toArray().toVector.map(_.asInstanceOf[Vector[(Outcome, Vector[(Boolean, Double, Double)])]])
      (wname, ok, st, evald, reflexive)
    }

    println("Worlds failing the acceptance gate are marked and EXCLUDED from rank stability; their")
    println("detail stays visible so the exclusion is auditable.  vsFlat = advantage over a constant")
    println("portfolio at the rule's own average exposure IN THE SAME ASSETS; g/n = gross/net of")
    println("liquidity-scaled trading costs.  ruin = share of paths with a loss worse than 50%.")
    for (wname, ok, st, evald, reflexive) <- results do
      println(f"\nWORLD: $wname%-34s ${if reflexive then "[REFLEXIVE] " else ""}%s${if ok then "" else "*** OUT OF RANGE — excluded from ranks ***"}%s")
      println(f"  inflation ${st.inflAnn}%.1f%%/yr   eq vol ${st.vol * 100}%.1f%%  kurt ${st.kurt}%.1f  clus ${st.ac1}%.2f/${st.ac20}%.2f  " +
              f"crashes/path ${st.epPerPath}%.1f  depth ${st.depthMed}%.1f%%  censored ${st.censored}%d  " +
              f"trend share ${st.trendShare}%.2f  clamp ${st.clampPct}%.3f%%")
      println(f"  bond vol ${st.bondVol * 100}%.1f%%  growth-crash ${pm(st.bondGrowth, 0, 1)}%s  infl-crash ${pm(st.bondInfl, 0, 1)}%s  " +
              f"corr ${pm(st.corrCalm, 0, 2)}%s/${pm(st.corrInfl, 0, 2)}%s  bond spiral ${st.pctBondStress * 100}%.1f%% of sessions")
      println(f"  ${"rule"}%-34s ${"ret/yr"}%8s ${"worst5%"}%8s ${"maxDD"}%7s ${"realDD"}%7s ${"ruin"}%5s " +
              f"${"vsFlat g"}%9s ${"vsFlat n"}%9s ${"swr"}%6s ${"churn"}%6s ${"slip x"}%7s ${"beats ref"}%9s")
      for j <- Rules.indices do
        val outs = evald.map(_(j)._1)
        val ref  = evald.map(_(RefIdx)._1)
        val ann  = outs.map(_.ann)
        val ruin = outs.count(_.maxDD > 50.0).toDouble / outs.size * 100.0
        val win  = outs.zip(ref).count((o, r) => o.ann > r.ann).toDouble / outs.size * 100.0
        val winTxt = if j == RefIdx then "ref" else f"$win%.0f%%"
        println(f"  ${Rules(j).name}%-34s ${pctile(ann, 0.5)}%7.2f%% ${pctile(ann, 0.05)}%7.2f%% " +
                f"${pctile(outs.map(_.maxDD), 0.5)}%6.1f%% ${pctile(outs.map(_.realDD), 0.5)}%6.1f%% $ruin%4.0f%% " +
                f"${pm(pctile(outs.map(_.vsFlatG), 0.5), 9, 2)}%s ${pm(pctile(outs.map(_.vsFlat), 0.5), 9, 2)}%s " +
                f"${pctile(outs.map(_.swr), 0.5)}%6.2f " +
                f"${outs.map(_.churn).sum / outs.size}%6.2f ${outs.map(_.slipMult).sum / outs.size}%7.2f $winTxt%9s")

    // Character and reflexive worlds are ranked SEPARATELY and never pooled.  A character world
    // varies what the market is like; a reflexive world changes who is trading.  One pooled "stable
    // across 21 worlds" that concealed an inversion in the two worlds most able to produce one
    // would be worse than not running them, so the split is structural, not presentational.
    val character = results.filter(t => t._2 && !t._5)
    val reflexive = results.filter(t => t._2 && t._5)
    val valid = character
    println(f"\n\nRANK STABILITY — ${valid.size}%d of ${results.count(t => !t._5)}%d CHARACTER worlds pass the gate; ranks use only those.")
    println("Rank stability is the WEAK form of robustness: magnitudes vary far more than ranks.")
    if !single then
      println("These ranks hold the crowd FIXED AND NON-REACTIVE; the reflexive panel below varies it.")
    // An empty admissible set is a RESULT, not a table to print anyway: a rank over no worlds has
    // no best and no worst.  Printing it as zeros reads as "every rule tied", which is a claim.
    if valid.isEmpty then
      println("\n  no world in this sweep passes the required gate classes — nothing to rank.")
      println("  Widen the requirement with -gate, or fix the world; do not read the tables below")
      println("  as pooled over market-like worlds, because there are none.")
    else
      for (metricName, get) <- Vector(("median net return", (o: Outcome) => o.ann),
                                      ("median GROSS edge vs the fixed twin", (o: Outcome) => o.vsFlatG)) do
        println(f"\n  ranked by $metricName%s   (1 = best)")
        val ranks = Rules.indices.map { j =>
          j -> valid.map { (_, _, _, evald, _) =>
            val med = Rules.indices.map(k => k -> pctile(evald.map(_(k)._1).map(get), 0.5)).sortBy(-_._2)
            med.indexWhere(_._1 == j) + 1
          }
        }
        for (j, rs) <- ranks do
          println(f"  ${Rules(j).name}%-34s ${rs.map(r => f"$r%2d").mkString(" ")}%s   best ${rs.min}%d  worst ${rs.max}%d")

    // ---- reflexivity: the qualifier the character ranks carry, made visible --------------------
    if !single then
      println(f"\n\nREFLEXIVITY: ${reflexive.size}%d of ${results.count(t => t._5)}%d reflexive worlds pass the gate.")
      println("The ranks above hold the crowd fixed and non-reactive.  These worlds hand the crowd a")
      println("rule to run, so its de-risking moves the price it reacts to: they change WHO IS TRADING")
      println("rather than the market's character.  They are NOT pooled with the ranks above, and the")
      println("flight-to-safety and refuge tables below exclude them for the same reason.")
      if reflexive.isEmpty then
        println("\n  no reflexive world passes the required gate classes; the qualifier stands untested.")
      else
        for (metricName, get) <- Vector(("median net return", (o: Outcome) => o.ann),
                                        ("median GROSS edge vs the fixed twin", (o: Outcome) => o.vsFlatG)) do
          println(f"\n  ranked by $metricName%s   (1 = best)   ${reflexive.map(_._1).mkString(" | ")}%s")
          for j <- Rules.indices do
            def rankIn(set: Vector[(String, Boolean, WorldStats, Evald, Boolean)]): Vector[Int] =
              set.map { (_, _, _, evald, _) =>
                val med = Rules.indices.map(k => k -> pctile(evald.map(_(k)._1).map(get), 0.5)).sortBy(-_._2)
                med.indexWhere(_._1 == j) + 1
              }
            val rs  = rankIn(reflexive)
            val chr = rankIn(valid)
            // ANY reflexive world outside the character range is the finding, not all of them.
            // The two reflexive worlds vary different axes and routinely disagree -- a vol-scaling
            // crowd ranks trend rules last where a pressed momentum crowd ranks them first -- so a
            // test requiring the whole reflexive SPAN to clear the range flagged nothing in exactly
            // the case worth flagging.
            val inverts = chr.nonEmpty && rs.exists(r => r < chr.min || r > chr.max)
            println(f"  ${Rules(j).name}%-34s ${rs.map(r => f"$r%2d").mkString(" ")}%s" +
                    f"   character ${if chr.isEmpty then 0 else chr.min}%d-${if chr.isEmpty then 0 else chr.max}%d" +
                    f"${if inverts then "   <-- MOVES OUTSIDE THE CHARACTER RANGE" else ""}%s")

    // ---- flight to safety, DECOMPOSED so carry cannot masquerade as timing --------------------
    //   total  = bond-refuge net return minus cash-refuge net return
    //   static = what a CONSTANT mix at the same average exposure gains just from holding bonds
    //   timing = the change in the rule's edge over its own constant twin when the twin also holds
    //            bonds — the only part attributable to timed flight.  total = static + timing per
    //            path; medians per column need not sum exactly.
    val pooled = valid.flatMap(_._4)
    println(f"\nFLIGHT TO SAFETY — de-risking into BONDS instead of cash, pooled over the market-like")
    println("worlds.  Return columns are net pp/yr and DEFLATOR-INVARIANT (the same inflation cancels")
    println("from both sides).  What real grading adds is the WITHDRAWAL column: dSwr = paired median")
    println("change in the 30-year sustainable REAL withdrawal rate from choosing the bond refuge —")
    println("the cash-vehicle decision metric, and the axis on which 1970s-style bonds look worst.")
    println(f"  ${"rule"}%-34s ${"total"}%7s ${"static"}%8s ${"timing"}%8s ${"swr cash"}%9s ${"swr bond"}%9s ${"dSwr"}%7s")
    for j <- Rules.indices do
      val tot = pooled.map(v => v(Rules.size + j)._1.ann - v(j)._1.ann)
      val sta = pooled.map(v => (v(Rules.size + j)._1.ann - v(Rules.size + j)._1.vsFlat)
                              - (v(j)._1.ann - v(j)._1.vsFlat))
      val tim = pooled.map(v => v(Rules.size + j)._1.vsFlat - v(j)._1.vsFlat)
      val swC = pooled.map(v => v(j)._1.swr).filter(x => !x.isNaN)
      val swB = pooled.map(v => v(Rules.size + j)._1.swr).filter(x => !x.isNaN)
      val dSw = pooled.map(v => v(Rules.size + j)._1.swr - v(j)._1.swr).filter(x => !x.isNaN)
      println(f"  ${Rules(j).name}%-34s ${pm(pctile(tot, 0.5), 7, 2)}%s ${pm(pctile(sta, 0.5), 8, 2)}%s ${pm(pctile(tim, 0.5), 8, 2)}%s " +
              f"${pctile(swC, 0.5)}%9.2f ${pctile(swB, 0.5)}%9.2f ${pm(pctile(dSw, 0.5), 7, 2)}%s")

    // ---- refuge severity curve: the conclusion as a CURVE, not a point ------------------------
    println(f"\nREFUGE SEVERITY CURVE — the same decomposition as inflation severity is dialed; where")
    println("the timing column crosses zero is where timed flight stops paying.  Baseline world")
    println("otherwise; severity multiplies inflSize.")
    println(f"  ${"severity"}%-9s ${"rule"}%-34s ${"total"}%7s ${"static"}%8s ${"timing"}%8s ${"dSwr"}%7s ${"infl-crash bond"}%16s")
    for mult <- Vector(0.5, 1.0, 1.5, 2.5) do
      val w = base.copy(inflSize = base.inflSize * mult)
      val sims = simPaths(w, math.min(paths, 120), years, seed)
      val st = measure(sims, years)
      // gated AT USE TIME, like every other conclusion path: a retrospective "the gate passed for
      // the worlds used so far" protects nothing about the next world someone dials up
      val okSev = gateOk(st, gateReq)
      val ev = java.util.stream.IntStream.range(0, sims.size).parallel().mapToObj { k =>
        val s = sims(k); val ind = new Indicators(s.price)
        val eps = episodes(s.price, 15.0); val fl = eps.map(ep => fundamentalLed(s, ep))
        Vector(RefIdx, Rules.size - 1).flatMap(j =>
          Vector(evaluate(s, eps, fl, Rules(j), ind, cost, years, Safe.Cash)._1,
                 evaluate(s, eps, fl, Rules(j), ind, cost, years, Safe.Bond)._1))
      }.toArray().toVector.map(_.asInstanceOf[Vector[Outcome]])
      for (j, off) <- Vector((RefIdx, 0), (Rules.size - 1, 2)) do
        val tot = ev.map(v => v(off + 1).ann - v(off).ann)
        val sta = ev.map(v => (v(off + 1).ann - v(off + 1).vsFlat) - (v(off).ann - v(off).vsFlat))
        val tim = ev.map(v => v(off + 1).vsFlat - v(off).vsFlat)
        val dSw = ev.map(v => v(off + 1).swr - v(off).swr).filter(x => !x.isNaN)
        println(f"  x$mult%-8.1f ${Rules(j).name}%-34s ${pm(pctile(tot, 0.5), 7, 2)}%s ${pm(pctile(sta, 0.5), 8, 2)}%s " +
                f"${pm(pctile(tim, 0.5), 8, 2)}%s ${pm(pctile(dSw, 0.5), 7, 2)}%s ${pm(st.bondInfl, 15, 1)}%s" +
                f"${if okSev then "" else "   *** OUT OF GATE ***"}%s")

    // ---- cost breakeven ------------------------------------------------------------------------
    println(f"\nCOST BREAKEVEN — the calm-market per-unit cost at which the rule's gross edge over its")
    println("fixed twin reaches zero; liquidity-weighted churn in the denominator.  The flat-rate")
    println("column is what a constant fee would have implied.")
    println(f"  ${"rule"}%-34s ${"breakeven"}%12s ${"5th pct"}%9s ${"flat-rate"}%11s")
    for j <- Rules.indices do
      val os = pooled.map(_(j)._1).filter(_.churn > 0.05)
      if os.isEmpty then println(f"  ${Rules(j).name}%-34s (does not trade)")
      else
        val be   = os.map(o => o.vsFlatG * 100.0 / o.effChurn)
        val flat = os.map(o => o.vsFlatG * 100.0 / o.churn)
        println(f"  ${Rules(j).name}%-34s ${pctile(be, 0.5)}%9.0f bp ${pctile(be, 0.05)}%7.0f bp ${pctile(flat, 0.5)}%9.0f bp")

    // ---- crash-type decomposition --------------------------------------------------------------
    println(f"\nCRASH TYPES — rule return minus buy-and-hold over each crash window, by whether the")
    println("fundamental fell at least half as far as price.  Log points x 100.")
    for j <- Rules.indices do
      val entries = pooled.flatMap(_(j)._2)
      val f = entries.filter(_._1).map(e => (e._2 - e._3) * 100.0)
      val l = entries.filterNot(_._1).map(e => (e._2 - e._3) * 100.0)
      println(f"  ${Rules(j).name}%-34s fund-led ${pm(pctile(f, 0.5), 7, 1)}%s (n=${f.size}%d)   " +
              f"liq-led ${pm(pctile(l, 0.5), 7, 1)}%s (n=${l.size}%d)")

  // ---- estimator power -----------------------------------------------------------------------

  def ruleNamed(nm: String): Rule =
    Rules.find(_.name == nm).getOrElse(sys.error(s"report names a rule not in Rules: [$nm]"))

  def nStarStr(x: Double): String =
    // n* counts histories, so it floors at 1 -- rounding 0.4 to "0" would read as "none needed"
    if x.isNaN then "  n/a" else if x > 9999.0 then ">9999" else f"${math.max(1.0, x)}%5.0f"

  /** ESTIMATOR POWER — a property of the MEASURING INSTRUMENT, not a ranking of strategies.
    *
    * Every deferred decision in this pipeline is parked behind one sentence: "the paired CI spans
    * zero".  That sentence has two readings — the arms are the same, or the statistic cannot tell
    * them apart on the data available — and the real record cannot separate them, because it is ONE
    * history.  Here it can be separated: for each candidate statistic S and history length L, ask
    * how often a single independent L-year history recovers the SIGN of the long-run difference at
    * that same length.  Nothing about which arm is better is asserted; only how much history each
    * statistic needs before its own answer stops being noise.
    *
    * n* = (1.96 / d)^2 with d = |mean paired difference| / sd across histories: the number of
    * independent L-year histories a 95% interval on the difference would need to exclude zero.  The
    * real record supplies ONE, so n* is read as "how far out of reach is this".
    *
    * The safe leg is CASH, matching what the production research assumed for the un-invested
    * fraction.  Statistics are compared only WITHIN a contrast; comparing d across contrasts would
    * be comparing different questions.
    *
    * TWO-SIDED CONTROL: the last contrast pairs an arm with ITSELF measured on an independent path,
    * so the true difference is zero by construction.  Every statistic must land near 50% there with
    * n* blowing up; one that looks decisive on the null is reading an artifact, not a difference. */
  /** Every fidelity ratio at every published default, plus the world this invocation describes.
    * Exists because the natural comparison -- candidate against its immediate predecessor -- is
    * exactly the reading under which a sequence of individually-acceptable trades accumulates with
    * nothing ever showing it.  The `worse than best` column is the accumulation detector: it names
    * the release whose default read closer to real than the current one does. */
  def runReleaseReport(paths: Int, years: Int, seed: Long, base: World): Unit =
    val cols = Releases :+ ("current", base)
    eprintln(s"${cols.size} worlds x $paths paths x $years years")
    val stats = cols.map((v, w) => (v, measure(simPaths(w, paths, years, seed), years)))
    println("CROSS-RELEASE FIDELITY — every target at every published default, and at the world this")
    println("invocation describes.  The WORLDS are historical; the MEASUREMENT is current, so this shows")
    println("how the DEFAULT has moved, not what each version reported — the mechanism moved too.  A")
    println("World field added after a release -- or REMOVED by a mechanism change, as 0.19.2's")
    println("rate cut was -- takes today's value in that release's row.")
    println()
    println(f"  ${"target"}%-22s" + cols.map((v, _) => f"$v%8s").mkString +
            f"   ${"best"}%7s   worse than best")
    var bestTotal = 0.0
    for (name, get, want, _) <- FitTargets do
      val rs = stats.map((_, st) => get(st) / want)
      val errs = rs.map(r => math.abs(r - 1.0))
      val cur = errs.last
      val bestIdx = errs.indices.minBy(errs)
      bestTotal += errs(bestIdx)
      val flag = if bestIdx != errs.size - 1 && errs(bestIdx) < cur - 0.005 then
                   f"<-- ${cols(bestIdx)._1}%s was ${rs(bestIdx)}%.2f" else ""
      println(f"  $name%-22s" + rs.map(r => f"$r%8.2f").mkString + f"   ${rs(bestIdx)}%7.2f   $flag%s")
    println()
    println(f"  ${"AGGREGATE |ratio-1|"}%-22s" +
            stats.map((_, st) => FitTargets.map((_, get, want, _) => math.abs(get(st) / want - 1.0)).sum)
                 .map(t => f"$t%8.2f").mkString +
            f"   ${bestTotal}%7.2f   best achievable per row, across all releases")
    println()
    println("  A flagged row is one where some published default read CLOSER to real than the current")
    println("  world does.  That is not automatically wrong — a trade may have been worth making — but")
    println("  it is the thing no predecessor-only comparison can show.")

  // ---- the cross-asset report -----------------------------------------------------------------

  /** Duration rungs, in years.  MEASURED, never fitted: a rung moves when a real fund's duration is
    * re-measured or a new anchor is added, never to make a cell pass.  A ladder whose rungs are
    * chosen after seeing the results is not a test of anything.
    *
    * 1.80 is the short end of the five iShares Treasury funds the bond relations were fitted across
    * (SHY, IEI, IEF, TLH, TLT -- see `SigmaNBond`), 13.50 is `DurationRef` so one rung is the world
    * every other report describes, and 5.70 is the Aggregate-like intermediate recorded as
    * admissible at 0.19.2.  25.00 is DELIBERATELY past the fund span: a ladder whose every rung sits
    * inside the fitted range cannot show the difference between agreeing with the data and
    * extrapolating it. */
  val DurationLadder = Vector(1.80, 5.70, 13.50, 25.00)

  /** Why a cell is or is not graded.  The two ungraded cases are NOT the same finding and must not
    * print alike: `Extrap` says the ladder went past the funds, `Undefined` says the relation has no
    * value to compare at a rung the funds do cover.  The second is a statement about the RELATION --
    * the depth line predicts non-positive time-under-water below ~1.98% volatility, so its usable
    * range is narrower than the range it was fitted across, and no ladder can widen it. */
  enum Cell:
    case Graded(ok: Boolean)
    /** Within one sampling sd of a band edge, on EITHER side.  A hard verdict there is a seed
      * draw wearing a verdict's clothes -- measured: the d=5.70 depth cell flips PASS/FAIL
      * across seeds at 200 paths under both the 0.19.2 and 0.20.0 defaults, because the world
      * genuinely sits at the band floor.  EDGE says "not resolvable at this ensemble size"
      * instead of resolving it by luck. */
    case Edge
    case Extrap
    case Undefined

  /** A scale-free relation: a band measured across real funds, plus the range of the driving
    * variable those funds actually covered.
    *
    * `support` is what makes the ladder a test rather than an assertion.  Both bands come from
    * fitting a line across a handful of funds, and outside the range they covered the line is
    * arithmetic with nothing behind it.  A cell there is disclosed, never scored -- grading it would
    * manufacture agreement or manufacture a defect, and there is no way to tell which.
    *
    * `get` is scale-free, which is why it can cross assets at all: a level target is a statement
    * about ONE fund, a ratio is a statement about the mechanism.  `driverOf` takes the duration too,
    * because one relation's driver is a world parameter rather than a measured statistic. */
  final case class Relation(name: String, get: WorldStats => Double, lo: Double, hi: Double,
                            driver: String, driverOf: (WorldStats, Double) => Double,
                            support: (Double, Double)):
    /** `sd` is this cell's own sampling noise, estimated by the caller from quarter-ensemble
      * spread; NaN disables the EDGE test (tiny ensembles), leaving the hard threshold. */
    def grade(st: WorldStats, dur: Double, sd: Double): Cell =
      val d = driverOf(st, dur)
      // Boundary counts as inside: the support's endpoints are fund readings, not a gap.
      if d < support._1 || d > support._2 then Cell.Extrap
      else
        val v = get(st)
        if v.isNaN then Cell.Undefined
        else if !sd.isNaN && (math.abs(v - lo) <= sd || math.abs(v - hi) <= sd) then Cell.Edge
        else Cell.Graded(v > lo && v < hi)

  /** Verdict for the ladder.  A relation that graded nothing was not tested, and "the test did not
    * run" must not print as the test passing -- a PASS resting on zero cells is the vacuous fixture
    * this repo has been burned by before.  An in-support miss outranks empty coverage.  Pure, so
    * the three-way branch is testable without running an ensemble. */
  def crossAssetVerdict(outsideBand: Int, edge: Int,
                        relGraded: Vector[(String, Int)]): (String, Boolean) =
    if outsideBand > 0 then ("FAIL", false)
    else if relGraded.exists(_._2 == 0) then ("INCONCLUSIVE", false)
    else if edge > 0 then ("EDGE", false)
    else ("PASS", true)

  /** The two bond relations that already carry a real-fund band, and only those.  Every other
    * fidelity target is a level calibrated to a single fund -- grading those here would re-assert
    * TLT's numbers at four durations and call the agreement evidence. */
  val BondRelations = Vector(
    Relation("bond vol x duration", _.bondVolPerYear, BondVolPerYearBand._1, BondVolPerYearBand._2,
             "duration y", (_, dur) => dur, BondDurSupport),
    Relation("bond depth vs vol", _.bondDepthVsVol, BondD10Band._1, BondD10Band._2,
             "bond vol %", (st, _) => st.bondVol * 100.0, BondVolSupport))

  /** The equity-leg fidelity targets, in report order.  Membership is a DECISION, not a
    * derivation: the partition test (`MarketSimContractSuite`) requires every fidelity target to be
    * classified as equity or bond, so a target added or renamed fails the build until someone
    * places it.  The failure being prevented is a target silently absent from the equity section --
    * a shorter table reads as a shorter list of concerns, not as a bug. */
  val EquityTargets = Vector(
    "equity vol %", "return per vol", "kurtosis", "clustering lag 1", "clustering lag 20",
    "crashes/century", "median depth %", "worst crash %",
    "equity >5% below pk", "equity >10% below pk", "equity >20% below pk")

  /** The other half of the partition.  Read only by the partition test -- the report has no bond
    * section to drive; the list exists so a new fidelity target cannot land unclassified. */
  val BondTargets = Vector(
    "bond vol % (24y)", "bond growth-crash", "bond infl-crash", "bond depth vs vol")

  /** Bisection bracket for the depth solve, and how many halvings.  Ten steps over this bracket
    * leaves the depth uncertain by 16/1024 ~ 0.016, worth about 0.02 points of volatility -- far
    * inside the sampling noise of any ensemble that could be run here.  Each step is a full
    * ensemble, so this is the cost knob: twelve ensembles in total, including the bracket probes. */
  val DepthBracket = (10.0, 26.0)
  val VolSolveSteps = 10

  /** Solve `depth` for a target equity volatility.  Volatility DECREASES with depth (impact scales
    * as `12/depth`), so the low end of the bracket is the high-volatility end.
    *
    * `None` where the bracket cannot reach the target -- refused rather than clamped, for the same
    * reason a cell outside a band's support is refused rather than graded: an endpoint returned as
    * if it were a solution would put every row below it at a volatility nobody asked for. */
  def depthForVol(base: World, target: Double, paths: Int, years: Int, seed: Long): Option[Double] =
    def volAt(d: Double): Double =
      measure(simPaths(base.copy(depth = d), paths, years, seed), years).vol * 100.0
    var (lo, hi) = DepthBracket
    if volAt(lo) < target || volAt(hi) > target then None
    else
      for _ <- 0 until VolSolveSteps do
        val mid = (lo + hi) / 2.0
        if volAt(mid) > target then lo = mid else hi = mid
      Some((lo + hi) / 2.0)

  /** Every equity target re-read with volatility put ON its anchor.
    *
    * `depth` moves volatility and drawdown together, so grading a drawdown statistic while the
    * model sits 10% below its own volatility anchor mixes two errors and reports one.  This section
    * removes the volatility miss and shows what the others then read: one identity parameter, set
    * from one measured statistic, nothing else touched.
    *
    * DIAGNOSTIC ONLY -- it does not touch the exit code.  The equity leg has no cross-index bands
    * yet (rung 2b), so there is nothing here to pass or fail against; what it has is a pair of
    * ratios and the difference between them. */
  def runEquityAtAnchor(paths: Int, years: Int, seed: Long, base: World): Unit =
    val target = FitTargets.find(_._1 == "equity vol %").map(_._3)
      .getOrElse(usage("no `equity vol %` fidelity target to anchor volatility on"))
    println()
    println("EQUITY — every equity target re-read with volatility ON ITS ANCHOR.  Diagnostic: this")
    println("section does not affect the exit code.")
    println()
    println("`depth` moves volatility and drawdown TOGETHER, so a drawdown statistic graded while the")
    println("model sits below its own volatility anchor mixes two errors and reports one.  Here depth is")
    println("solved so volatility sits on the anchor and every other target is re-read: 1 identity")
    println("parameter, set from 1 measured statistic, nothing else touched.")
    println()
    println("The anchors come from DIFFERENT WINDOWS, and this does not fix that -- it removes the")
    println("model's own volatility miss, which was compounding with it.  Read a ratio here as")
    println("\"conditional on hitting the volatility anchor\", not as \"window-matched\".")
    println()
    depthForVol(base, target, paths, years, seed) match
      case None =>
        println(f"  cannot reach $target%.2f %% volatility with depth in ${DepthBracket._1}%.1f-${DepthBracket._2}%.1f: this world's volatility is set by")
        println("  something other than depth, and the section has nothing to say about it.")
      case Some(solved) =>
        val stDef = measure(simPaths(base, paths, years, seed), years)
        val stAnc = measure(simPaths(base.copy(depth = solved), paths, years, seed), years)
        println(f"  solved: depth $solved%.2f gives the anchored volatility (bisection, $VolSolveSteps steps over depth ${DepthBracket._1}%.1f-${DepthBracket._2}%.1f); the world's own depth is ${base.depth}%.2f")
        println()
        println(f"  ${"statistic"}%-22s${"default"}%10s${"at anchor"}%11s${"real"}%10s${"ratio def"}%11s${"ratio anc"}%11s")
        for name <- EquityTargets do
          val (_, get, want, _) = FitTargets.find(_._1 == name)
            .getOrElse(usage(s"EquityTargets names [$name], which is not a fidelity target"))
          val (d, a)   = (get(stDef), get(stAnc))
          val (rd, ra) = (d / want, a / want)
          // The point of the section: the rows where putting volatility on its anchor CHANGES the
          // verdict.  A row that reads the same either way was never distorted by the miss.  Judge
          // a flagged move against `-noise`'s seed-noise section before reading it as real; the
          // two columns share one seed, so 2 sd there is the conservative bound on this difference.
          val flag = if math.abs(ra - rd) > 0.05 then f"<-- moves ${ra - rd}%.2f" else ""
          println(f"  $name%-22s$d%10.2f$a%11.2f$want%10.2f$rd%11.2f$ra%11.2f   $flag%s")

  def crossAssetPreamble(): Unit =
    println("CROSS-ASSET — ONE mechanism across the duration ladder.  1 identity parameter: 0 FITTED,")
    println("1 MEASURED.  Every mechanism parameter is frozen at the world this invocation describes;")
    println("only `duration` moves, and it moves to values real funds have.  That is the whole test: a")
    println("band holds across the ladder, or the mechanism is duration-specific.")
    println()
    println("Each band was fitted across real funds, so each carries a SUPPORT — the range of its own")
    println("driving variable those funds covered.  A rung outside it reads EXTRAP: disclosed, and")
    println("excluded from the verdict, because a line evaluated past its data can neither pass nor")
    println("fail honestly.")
    println()
    println("WHAT THIS DOES NOT PROVE.  The bands were fitted on Treasury funds and these are Treasury")
    println("durations, so this is a CONSISTENCY check: it cannot detect a mechanism that is wrong in a")
    println("way every Treasury shares.  That needs an asset class the bands did not come from.")
    println()

  /** One mechanism, every rung of the duration ladder, graded only where the bands have data. */
  def runCrossAssetReport(paths: Int, years: Int, seed: Long, base: World): Boolean =
    eprintln(s"${DurationLadder.size} durations x $paths paths x $years years")
    // Per rung: the full-ensemble reading, plus four quarter-ensemble readings for the in-run
    // noise estimate.  The quarters reuse the SAME simulated paths -- the estimate costs four
    // extra measure() calls and zero extra simulation.  sd(full) is taken as sd(quarters)/2;
    // approximate for a median-based statistic, and stated as an estimate.
    val stats: Vector[(Double, WorldStats, Vector[WorldStats])] =
      DurationLadder.map { d =>
        val sims = simPaths(base.copy(duration = d), paths, years, seed)
        val quarters =
          if paths >= 8 then
            val g = paths / 4
            (0 until 4).toVector.map(k => measure(sims.slice(k * g, (k + 1) * g), years))
          else Vector.empty
        (d, measure(sims, years), quarters)
      }
    crossAssetPreamble()
    val hdr = f"  ${"relation"}%-22s" + stats.map((d, _, _) => f"${f"d=$d%.2f"}%10s").mkString
    println(hdr + "   band          support")
    var graded = 0
    var extrap = 0
    var undef = 0
    var edge = 0
    var failed = Vector.empty[String]
    var edges = Vector.empty[String]
    var relGraded = Vector.empty[(String, Int)]
    for rel <- BondRelations do
      var mine = 0
      val cells = stats.map { (d, st, quarters) =>
        val qs = quarters.map(rel.get).filter(x => !x.isNaN)
        val sd =
          if qs.size >= 2 then
            val mean = qs.sum / qs.size
            math.sqrt(qs.map(x => (x - mean) * (x - mean)).sum / (qs.size - 1)) / math.sqrt(qs.size.toDouble)
          else Double.NaN
        rel.grade(st, d, sd) match
          case Cell.Extrap    => extrap += 1; "EXTRAP"
          case Cell.Undefined => undef += 1; "n/a"
          case Cell.Edge =>
            graded += 1
            mine += 1
            edge += 1
            edges = edges :+ f"${rel.name}%s at d=$d%.2f (${rel.get(st)}%.2f within $sd%.2f of the band)"
            f"${rel.get(st)}%.2f~"
          case Cell.Graded(ok) =>
            graded += 1
            mine += 1
            if !ok then failed = failed :+ f"${rel.name}%s at d=$d%.2f"
            f"${rel.get(st)}%.2f"
      }
      relGraded = relGraded :+ (rel.name, mine)
      println(f"  ${rel.name}%-22s" + cells.map(c => f"$c%10s").mkString +
              f"   ${rel.lo}%.2f-${rel.hi}%.2f   ${rel.driver}%s ${rel.support._1}%.2f-${rel.support._2}%.2f")
    // The drivers themselves, ungraded: without them an EXTRAP cell says only "out of range" and
    // not how far out, which is the difference between a near miss and a different asset.
    val drv = f"  ${"(bond vol %)"}%-22s" + stats.map((_, st, _) => f"${f"${st.bondVol * 100.0}%.2f"}%10s").mkString
    println()
    println(drv + "   driver of the depth relation, ungraded")
    println()
    val (word, ok) = crossAssetVerdict(failed.size, edge, relGraded)
    println(s"  verdict: $word  — $graded graded, ${failed.size} outside band, " +
            s"$edge at edge, $extrap EXTRAP, $undef n/a")
    if failed.nonEmpty then println(s"    outside: ${failed.mkString(", ")}")
    if edges.nonEmpty then
      println(s"    edge (~): ${edges.mkString(", ")}")
      println("    a ~ cell sits within one estimated sampling sd of a band edge, on either side: the")
      println("    verdict cannot resolve it at this ensemble size, and a hard PASS or FAIL there")
      println("    would be a seed draw wearing a verdict's clothes.")
    if word == "INCONCLUSIVE" then
      val empty = relGraded.filter(_._2 == 0).map(_._1)
      println(s"    INCONCLUSIVE: [${empty.mkString(", ")}] graded ZERO cells — every rung EXTRAP or n/a — so the ladder")
      println("    tested nothing for it, and \"the test did not run\" must not print as PASS.")
    if undef > 0 then
      println("    n/a = the relation has no value at that rung, INSIDE its support: the depth line")
      println(f"    predicts non-positive time-under-water below $BondD10Zero%.2f %% volatility, so its usable range")
      println("    is narrower than the range it was fitted across.  A property of the relation, not")
      println("    of this ladder — widening the ladder cannot reach those rungs.")
    runEquityAtAnchor(paths, years, seed, base)
    ok

  // ---- the anchor-noise report ---------------------------------------------------------------

  /** Each fidelity anchor's own measurement horizon, in years, and the targets read over it.  The
    * windows are the ones the fidelity header names -- S&P/CRSP 1954-2026, the CRSP century for
    * clustering, SPY 1993-2026 for the depth rungs, the clean 24-year TLT series for the bond --
    * because sampling error depends on the length of the record actually behind each number, not
    * on the horizon the model is scored at.  The contract test pins this to `FitTargets` as a
    * partition, so a new target cannot land without a declared horizon. */
  val AnchorGroups: Vector[(String, Int, Vector[String])] = Vector(
    ("S&P / CRSP 1954-2026", 72,
     Vector("equity vol %", "return per vol", "kurtosis", "crashes/century",
            "median depth %", "worst crash %")),
    ("CRSP 1926-2026, the century", 100,
     Vector("clustering lag 1", "clustering lag 20")),
    ("SPY 1993-2026", 33,
     Vector("equity >5% below pk", "equity >10% below pk", "equity >20% below pk")),
    ("clean TLT, 24y", 24,
     Vector("bond vol % (24y)", "bond growth-crash", "bond infl-crash", "bond depth vs vol")))

  /** Replicates for the seed-noise section, and the seed stride between them.  1_000_003 is not a
    * multiple of the 7919 path stride (1_000_003 mod 7919 = 2209), so within the replicate count
    * used here no path seed recurs across replicates. */
  val NoiseReplicates = 8
  val NoiseSeedStride = 1000003L

  /** What one history can pin down, per fidelity target -- and what one seed can, per ensemble.
    *
    * Every fidelity target is a POINT read from one historical record.  Section 1 asks the model
    * what spread of readings independent histories of that anchor's own length produce, and where
    * the real record falls in that spread.  Section 2 measures the seed-to-seed noise of the
    * scoring ensemble itself, which is what licenses reading a ratio difference -- in `-releases`,
    * or between `-crossasset`'s equity columns -- as a change rather than a draw.
    *
    * MODEL-IMPLIED, and the circularity is stated in the report: the spreads come from this
    * model's own dynamics, so where the model is known biased the spread is too.  There is no
    * other estimate -- the record is one draw. */
  def runNoiseReport(paths: Int, seed: Long, base: World): Unit =
    println("ANCHOR NOISE — what one history can pin down.  Every fidelity target is a POINT read from")
    println("one historical record; this report asks the model what spread of readings independent")
    println("histories of that anchor's OWN length would produce, and where the real record falls.")
    println()
    println("MODEL-IMPLIED, circularity stated: the spreads come from this model's own dynamics, so")
    println("where the model is known biased (clustering 1.06x real) the spread is biased with it.")
    println("There is no other estimate — the record is one draw.")
    println()
    println("Read `real@` as the share of model histories at or below the real anchor: near 50% the")
    println("record is a typical history of this model, near 0/100% the model cannot produce")
    println("record-like histories on that statistic.  `sd/real` beside `wt` is the mis-weighting")
    println("check: equal weight with unequal sd/real grades two targets as equally measurable, and")
    println("they are not.  `p50` vs `real` is the HORIZON-MATCHED reading; -fitness grades a")
    println("100-year model reading against these mixed-horizon anchors, so its ratios fold a horizon")
    println("artifact into targets like worst crash %.")
    for (label, years, targets) <- AnchorGroups do
      eprintln(s"$paths paths x ${years}y — $label")
      val sims = simPaths(base, paths, years, seed)
      val sts  = sims.map(p => measure(Vector(p), years))
      println()
      println(s"  $label — $years-year single histories:")
      println(f"  ${"target"}%-22s${"real"}%8s${"p5"}%8s${"p50"}%8s${"p95"}%8s${"real@"}%7s${"n"}%5s${"sd/real"}%8s${"wt"}%5s")
      for name <- targets do
        val (_, get, want, weight) = FitTargets.find(_._1 == name)
          .getOrElse(usage(s"anchor group names [$name], not a fidelity target"))
        val xs = sts.map(get).filter(x => !x.isNaN).sorted
        val n  = xs.size
        if n == 0 then
          println(f"  $name%-22s${want}%8.2f${"n/a"}%8s${"n/a"}%8s${"n/a"}%8s${"-"}%7s$n%5d${"n/a"}%8s${weight}%5.1f")
        else
          def p(q: Int) = xs((n - 1) * q / 100)
          val mean  = xs.sum / n
          val sd    = if n > 1 then math.sqrt(xs.map(x => (x - mean) * (x - mean)).sum / (n - 1)) else Double.NaN
          val below = xs.count(_ <= want)
          val ps    = s"${100 * below / n}%"
          println(f"  $name%-22s${want}%8.2f${p(5)}%8.2f${p(50)}%8.2f${p(95)}%8.2f$ps%7s$n%5d${sd / math.abs(want)}%8.2f${weight}%5.1f")
    eprintln(s"$NoiseReplicates replicates x $paths paths x 100y — seed noise")
    val reps = (0 until NoiseReplicates).toVector
      .map(k => measure(simPaths(base, paths, 100, seed + (k + 1) * NoiseSeedStride), 100))
    println()
    println(s"  seed noise of the SCORING ensemble — $NoiseReplicates replicates of $paths paths x 100 years.  -releases rows,")
    println("  -crossasset's equity ratios and any candidate-vs-default comparison are readings of")
    println("  this configuration: a ratio difference below ~2 sd is a seed draw, not a change.")
    println("  (-crossasset's two equity columns share one seed, so their DIFFERENCE is less noisy")
    println("  than two independent readings; 2 sd is the conservative bound.)")
    println()
    println(f"  ${"target"}%-22s${"ratio mean"}%11s${"ratio sd"}%11s${"2 sd"}%11s")
    for (name, get, want, _) <- FitTargets do
      val rs   = reps.map(st => get(st) / want)
      val mean = rs.sum / rs.size
      val sd   = math.sqrt(rs.map(x => (x - mean) * (x - mean)).sum / (rs.size - 1))
      println(f"  $name%-22s$mean%11.3f$sd%11.3f${2.0 * sd}%11.3f")

  def runPowerReport(paths: Int, seed: Long, cost: Double, single: Boolean, base: World,
                     gateReq: Set[GateClass], armIdx: Vector[Int], horizons: Vector[Int]): Unit =
    // Arms and horizons are the CALLER's, so the consumer's own question — these two arms, at the
    // length of history I possess — is answerable without a code change.  The defaults reproduce
    // the report this had before it took either.
    val focus = armIdx.map(i => Rules(i - 1))
    val alwaysFn: Indicators => Array[Double] = ind => Array.fill(ind.px.length)(1.0)
    val arms: Vector[Indicators => Array[Double]] =
      focus.flatMap(r => Vector(r.expose, (i: Indicators) => matchedConstant(r.expose(i)))) :+ alwaysFn
    val alwaysIdx = arms.size - 1
    val pairs: Vector[(String, Int, Int, Boolean)] =
      focus.indices.toVector.flatMap { k =>
        Vector((s"${focus(k).name}  vs its exposure-matched constant", 2 * k, 2 * k + 1, false),
               (s"${focus(k).name}  vs always fully invested",         2 * k, alwaysIdx, false))
      } :+ (s"NULL — ${focus(0).name}  vs ITSELF on an independent path", 0, 0, true)

    /** per contrast, per statistic: (hit rate, n*).  Gate verdict travels with the numbers. */
    def power(w: World, L: Int, sd: Long): (Boolean, Vector[Vector[(Double, Double)]]) =
      val sims  = simPaths(w, paths, L, sd)
      val ok    = gateOk(measure(sims, L), gateReq)
      val stats = java.util.stream.IntStream.range(0, sims.size).parallel().mapToObj { k =>
        val p   = sims(k)
        val ind = new Indicators(p.price)
        arms.map(fn => gradingStats(armPath(p, fn(ind), cost, Safe.Cash), L).map(_._2))
      }.toArray().toVector.map(_.asInstanceOf[Vector[Vector[Double]]])
      val np = stats.size
      val res = pairs.map { (_, ia, ib, isNull) =>
        StatNames.indices.toVector.map { j =>
          // the null pairs the first half of the paths against the second, giving genuinely
          // independent differences; pairing every path with a shifted partner would force the mean
          // to zero and the hit rate to 50% ARITHMETICALLY, which is a rigged control, not a check
          val d = (if isNull then (0 until np / 2).map(k => stats(k)(ia)(j) - stats(k + np / 2)(ib)(j))
                   else (0 until np).map(k => stats(k)(ia)(j) - stats(k)(ib)(j)))
                  .filter(x => !x.isNaN).toVector
          if d.size < 8 then (Double.NaN, Double.NaN)
          else
            // truth from one half, hit rate scored on the OTHER: reading both off the same sample
            // would grade the estimator against a target it helped define
            val h     = d.size / 2
            val truth = d.take(h).sum / h
            val test  = d.drop(h)
            val hit   = test.count(x => x.sign == truth.sign).toDouble / test.size
            val mu    = d.sum / d.size
            val sdv   = math.sqrt(d.map(x => (x - mu) * (x - mu)).sum / d.size)
            (hit, if sdv <= 0.0 || mu == 0.0 then Double.NaN else math.pow(1.96 * sdv / math.abs(mu), 2))
        }
      }
      (ok, res)

    println("ESTIMATOR POWER — what each grading statistic can and cannot resolve from ONE history.")
    println("Cells are  hit%/n*:  hit% = share of single L-year histories whose measured difference has")
    println("the same sign as the long-run difference at that length (50% = coin flip); n* = independent")
    println("L-year histories a 95% paired interval would need to exclude zero.  The real record has 1.")
    println("Safe leg is CASH.  Read DOWN a column (statistics against each other); across columns the")
    println("question changes.")
    println()
    pairs.zipWithIndex.foreach { case ((lbl, _, _, _), j) => println(f"  C${j + 1}%-3d $lbl%s") }
    for L <- horizons do
      val (ok, res) = power(base, L, seed + L.toLong * 1000003L)
      val verdict = if ok then "gate PASS" else "gate FAIL — read nothing from this block"
      println(f"\n  L = $L%3d years   ($paths%d independent histories, $verdict%s)")
      println(f"  ${"statistic"}%-19s" + pairs.indices.map(j => f"   C${j + 1}%-8d").mkString)
      for j <- StatNames.indices do
        println(f"  ${StatNames(j)}%-19s" + pairs.indices.map { c =>
          val (hit, ns) = res(c)(j)
          if hit.isNaN then "       n/a" else f"  ${hit * 100}%3.0f/${nStarStr(ns)}%s"
        }.mkString)

    if !single then
      val L = horizons.head
      println(f"\n  ACROSS THE WORLD SWEEP at L = $L%d years, contrast C1 — a measurement conclusion has")
      println("  to hold in every world the gate admits, or it is a property of one parameter setting.")
      val perWorld = sweepWorlds(base, single = false, withReflexive = false).map { (nm, w, _) => (nm, power(w, L, seed + 31L)) }
      val passing  = perWorld.filter(_._2._1).map(_._2._2)
      println(f"  ${passing.size}%d of ${perWorld.size}%d worlds pass the gate")
      println(f"  ${"statistic"}%-19s ${"min n*"}%8s ${"median"}%8s ${"max n*"}%8s ${"median hit%"}%12s")
      for j <- StatNames.indices do
        val ns = passing.map(_(0)(j)._2).filter(x => !x.isNaN).sorted
        val hs = passing.map(_(0)(j)._1).filter(x => !x.isNaN)
        val hm = if hs.isEmpty then "n/a" else f"${pctile(hs, 0.5) * 100}%.0f%%"
        println(f"  ${StatNames(j)}%-19s ${nStarStr(ns.headOption.getOrElse(Double.NaN))}%8s " +
                f"${nStarStr(if ns.isEmpty then Double.NaN else pctile(ns, 0.5))}%8s " +
                f"${nStarStr(ns.lastOption.getOrElse(Double.NaN))}%8s ${hm}%12s")

  // ---- the buffer question -------------------------------------------------------------------

  /** HOW LONG DOES A REAL UNDERWATER STRETCH RUN?  The real record answers with roughly one episode
    * per era, which is why the recorded figure ("~15 years real") has to be read as "much more than
    * 3" rather than as a number.  Pooled over independent histories the answer is a DISTRIBUTION,
    * and the decision quantity is the far quantile, not the median: a cash buffer is a promise about
    * the worst stretch you will meet, not the typical one.
    *
    * Everything here is REAL (CPI-deflated) and the safe leg is the BOND, so the matched-constant
    * arms are static equity/bond mixes — the analog of the 50/50 real series the 15-year figure came
    * from.  Depth AT EXHAUSTION excludes stretches that never outlast the buffer: those force no
    * sale and cost nothing, so entering them as zeros would flatter the average with episodes that
    * never happened. */
  def runBufferReport(paths: Int, years: Int, seed: Long, cost: Double, single: Boolean,
                      base: World, gateReq: Set[GateClass]): Unit =
    // 15% is the repo's existing episode threshold (episodes(px, 15.0)); reusing it keeps this
    // report from introducing a new arbitrary constant.  Without it the distribution is drowned:
    // every one-session dip is a stretch, so the median stretch is 0.0 years and says nothing.
    val MaterialDepth = 0.15
    val focus = Vector(("vol-scaled 40%", "volatility-scaled, floor 40%"),
                       ("vol+trend 200d", "volatility + trend 200d, floor 0%"))
    val arms: Vector[(String, Indicators => Array[Double])] =
      ("100% equity", (ind: Indicators) => Array.fill(ind.px.length)(1.0)) +:
      focus.flatMap { (short, nm) =>
        val r = ruleNamed(nm)
        Vector((short, r.expose),
               (s"static mix @ $short", (i: Indicators) => matchedConstant(r.expose(i))))
      }
    val buffers   = Vector(5, 15)
    val overruns  = Vector(3.0, 5.0, 10.0, 15.0)
    val pathYears = paths.toDouble * years

    /** per arm: (material-stretch lengths in years, ALL stretch lengths, depth at exhaustion per
      * buffer).  ALL stretches are kept for the time-share column, because a buffer policy is
      * chosen before knowing which stretch you land in. */
    def bufferStats(w: World): (Boolean, Vector[(Vector[Double], Vector[Double], Vector[Vector[Double]])]) =
      val sims = simPaths(w, paths, years, seed)
      val ok   = gateOk(measure(sims, years), gateReq)
      val per  = java.util.stream.IntStream.range(0, sims.size).parallel().mapToObj { k =>
        val p   = sims(k)
        val ind = new Indicators(p.price)
        arms.map { (_, fn) =>
          val ap  = armPath(p, fn(ind), cost, Safe.Bond)
          val us  = underwater(ap.realLogEq)
          val yrs = us.map(_.sessions / DaysPerYear.toDouble)
          (us.zip(yrs).filter(_._1.worstDepth >= MaterialDepth).map(_._2), yrs,
           buffers.map(b => us.map(u => depthAtExhaustion(ap.realLogEq, u, b * DaysPerYear))
                              .filter(x => !x.isNaN)))
        }
      }.toArray().toVector.map(_.asInstanceOf[Vector[(Vector[Double], Vector[Double], Vector[Vector[Double]])]])
      (ok, arms.indices.toVector.map { j =>
        (per.flatMap(_(j)._1), per.flatMap(_(j)._2),
         buffers.indices.toVector.map(b => per.flatMap(_(j)._3(b))))
      })

    val (ok, res) = bufferStats(base)
    println("THE BUFFER QUESTION — length of REAL (CPI-deflated) underwater stretches, pooled over")
    println(f"$paths%d independent ${years}%d-year histories = ${pathYears.toLong}%d path-years.  Safe leg is the BOND,")
    println("so a 'static mix' arm is a constant equity/bond portfolio at that rule's own average")
    println("exposure.  Stretches still under water at path end are INCLUDED at their length so far.")
    println(f"  baseline world: ${if ok then "gate PASS" else "gate FAIL — read nothing below"}%s")
    println()
    println(f"  material stretches (real depth >= ${MaterialDepth * 100}%.0f%%)        share of ALL calendar time spent inside a")
    println("                                                     stretch that ends up running longer than")
    println(f"  ${"arm"}%-28s ${"n"}%7s ${"med"}%6s ${"90th"}%6s ${"99th"}%6s ${"worst"}%6s  " +
            overruns.map(b => f"${f"$b%.0fy"}%7s").mkString)
    for j <- arms.indices do
      val (mat, all, _) = res(j)
      def share(y: Double): Double = all.filter(_ > y).sum / pathYears * 100.0
      println(f"  ${arms(j)._1}%-28s ${mat.size}%7d ${pctile(mat, 0.5)}%6.1f ${pctile(mat, 0.90)}%6.1f " +
              f"${pctile(mat, 0.99)}%6.1f ${mat.maxOption.getOrElse(Double.NaN)}%6.1f  " +
              overruns.map(b => f"${share(b)}%6.1f%%").mkString(" "))

    println(f"\n  DEPTH AT EXHAUSTION — how often a buffer of B years is outlasted, and how deep it has")
    println("  got by then.  Stretches that never outlast the buffer force no sale and are EXCLUDED;")
    println("  entering them as zeros would average in episodes that cost nothing.")
    println(f"  ${"arm"}%-28s" + buffers.map(b => f"    ${f"B=${b}y per century"}%18s ${"median"}%7s ${"worst"}%7s").mkString)
    for j <- arms.indices do
      println(f"  ${arms(j)._1}%-28s" + buffers.indices.map { b =>
        val e = res(j)._3(b)
        val perCentury = e.size * 100.0 / pathYears
        if e.isEmpty then f"    ${perCentury}%18.2f ${"n/a"}%7s ${"n/a"}%7s"
        else f"    ${perCentury}%18.2f ${pctile(e, 0.5) * 100}%6.1f%% ${e.max * 100}%6.1f%%"
      }.mkString)

    if !single then
      println(f"\n  ACROSS THE WORLD SWEEP — gate-passing worlds only.  A buffer number that moves with")
      println("  the world parameters is a property of one parameter setting, not a planning figure.")
      val perWorld = sweepWorlds(base, single = false, withReflexive = false).map { (nm, w, _) => (nm, bufferStats(w)) }
      val passing  = perWorld.filter(_._2._1).map(_._2._2)
      println(f"  ${passing.size}%d of ${perWorld.size}%d worlds pass the gate")
      println(f"  ${"arm"}%-28s ${"99th pct material stretch, yrs"}%32s   ${"share of time in a >10y stretch"}%s")
      println(f"  ${""}%-28s ${"min"}%10s ${"median"}%10s ${"max"}%10s   ${"min"}%9s ${"median"}%9s ${"max"}%9s")
      for j <- arms.indices do
        val q = passing.map(r => pctile(r(j)._1, 0.99)).sorted
        val t = passing.map(r => r(j)._2.filter(_ > 10.0).sum / pathYears * 100.0).sorted
        println(f"  ${arms(j)._1}%-28s ${q.head}%10.1f ${pctile(q, 0.5)}%10.1f ${q.last}%10.1f   " +
                f"${t.head}%8.1f%% ${pctile(t, 0.5)}%8.1f%% ${t.last}%8.1f%%")

  // ---- export: the full state, named, dated and provenanced -----------------------------------
  //
  // An emitted path is the whole external interface: a consumer grades its own rules on it without
  // importing either twin.  Three properties make that work, and all three were missing.
  //   1. EVERY series the model knows, not just price and bond.  A rule that de-risks to cash is
  //      mis-scored without `rate`; a real-terms question is unanswerable without `cpi`; slippage
  //      cannot be charged the way armPath charges it without `liq`/`bliq`; and `fundamental` is an
  //      oracle label (fundamental-led vs liquidity-led decline) that no real series can supply.
  //   2. A NAMED path.  `seed + k*7919` makes the family reproducible, but nothing in the output
  //      said which (world, seed, k) produced a file, so an ensemble could not be inventoried and
  //      the same paths could be re-drawn and counted twice as independent evidence.
  //   3. A verdict measured on the WORLD, not on the sample.  The four mechanism checks are
  //      conditional on crash episodes, so one short path cannot measure them and every export
  //      carried a false alarm -- worse than no warning.  See `-emitgate`.

  val EmitColumns = Vector("date", "price", "bond", "rate", "cpi", "liq", "bliq",
                           "fundamental", "inflPress")

  /** `%.6f`, with negative zero folded to positive.  Emitted columns are levels rather than
    * differences, so the signed-zero trap PARITY.md documents is remote here -- but `rate` is
    * floored at zero and `inflPress` starts there, and IEEE-754 guarantees (-0.0) + 0.0 = +0.0 in
    * both languages, so the fold costs nothing and removes the last way the two writers could
    * disagree on a byte. */
  def ef(x: Double): String = f"${if x == 0.0 then 0.0 else x}%.6f"

  def jsonStr(s: String): String =
    val esc = s.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case c    => c.toString
    }
    "\"" + esc + "\""

  def crowdName(c: Crowd): String = c match
    case Crowd.Momentum  => "momentum"
    case Crowd.Trend(d)  => s"trend$d"
    case Crowd.VolScaled => "volscaled"

  /** Session dates.  Empty `startYmd` keeps the historical synthetic calendar: 1900-01-02 stepping
    * 365/252 days, which lands on weekends and so can never be joined to a real dated series.  A
    * date instead steps by WEEKDAYS (no holiday calendar -- recorded, not hidden), which is what
    * lets an emitted path through a normal dated loader untouched. */
  def sessionDates(n: Int, startYmd: String): Vector[String] =
    if startYmd.isEmpty then
      val start = UniDateTime.of(1900, 1, 2)
      // .ymd, never bare interpolation: UniDateTime.toString is isoString and would render
      // 1900-01-02T00:00.  The old spelling got the date-only form only because sb.append(anyRef)
      // reached LocalDate.toString -- a JDK shape nothing here pinned.
      Vector.tabulate(n)(i => start.plusDays((i * 365L) / DaysPerYear).ymd)
    else
      // Validate BEFORE building the date.  uni's sentinel invariant is that an invalid
      // UniDateTime propagates itself — plusDays returns the same date — so feeding one into the
      // weekday recurrence below is an infinite loop, not an error.  The guard lives here, with
      // the consumer, exactly as the sentinel contract requires.
      val f = startYmd.split("-")
      if f.length != 3 then usage(s"-emitstart wants YYYY-MM-DD, got [$startYmd]")
      val y = intOr("-emitstart", f(0)); val m = intOr("-emitstart", f(1)); val dd = intOr("-emitstart", f(2))
      def leap(y: Int) = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
      if y < 1 || y > 9999 || m < 1 || m > 12 then
        usage(s"-emitstart [$startYmd] is not a calendar date")
      val dim = m match
        case 2            => if leap(y) then 29 else 28
        case 4 | 6 | 9 | 11 => 30
        case _            => 31
      if dd < 1 || dd > dim then usage(s"-emitstart [$startYmd] is not a calendar date")
      def nextWeekday(d: UniDateTime): UniDateTime =
        if d.dayOfWeekNum <= 5 then d else nextWeekday(d.plusDays(1))
      // a stateful recurrence written as one, like simulate(): each session is the next weekday
      // strictly after the previous one
      val out = Vector.newBuilder[String]
      var d = nextWeekday(UniDateTime.of(y, m, dd))
      var i = 0
      while i < n do
        out += d.ymd
        d = nextWeekday(d.plusDays(1))
        i += 1
      out.result()

  /** `foo.tsv` -> `foo.json`; a name with no extension just gains one. */
  def sidecarName(file: String): String =
    val cut = file.lastIndexOf('.')
    val sep = math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'))
    if cut > sep then file.substring(0, cut) + ".json" else file + ".json"

  /** `foo.tsv` -> `foo-007.tsv`, so an ensemble sorts in path order. */
  def indexedName(file: String, k: Int): String =
    val cut = file.lastIndexOf('.')
    val sep = math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'))
    val tag = f"-$k%03d"
    if cut > sep then file.substring(0, cut) + tag + file.substring(cut) else file + tag

  /** The TSV and its sidecar.  `gateSt` is measured on the gate ensemble, which is a different and
    * usually much larger sample than the one path being written. */
  def writeEmitted(file: String, p: Path, k: Int, w: World, years: Int, seed: Long,
                   startYmd: String, gateSt: WorldStats, gatePaths: Int): Unit =
    val dates = sessionDates(p.price.length, startYmd)
    writeEmitTsv(file, p, dates)
    writeEmitSidecar(file, p, k, w, years, seed, startYmd, dates, gateSt, gatePaths)

  def writeEmitTsv(file: String, p: Path, dates: Vector[String]): Unit =
    val rows = EmitColumns.mkString("\t") +: Vector.tabulate(dates.length) { i =>
      s"${dates(i)}\t${ef(p.price(i))}\t${ef(p.bond(i))}\t${ef(p.rate(i))}\t${ef(p.cpi(i))}\t" +
      s"${ef(p.liq(i))}\t${ef(p.bliq(i))}\t${ef(p.fundamental(i))}\t${ef(p.inflPress(i))}"
    }
    file.asPath.writeLines(rows)

  /** Every `World` field, in declaration order, as the indented body of a JSON object.  A world
    * that reaches a consumer without its parameters cannot be re-simulated. */
  def worldJsonBody(w: World): Vector[String] =
    Vector(
      ("trendShare", ef(w.trendShare)), ("depth", ef(w.depth)), ("stress", ef(w.stress)),
      ("beta", ef(w.beta)), ("drift", ef(w.drift)), ("fundVol", ef(w.fundVol)),
      ("rateMean", ef(w.rateMean)), ("volPersist", ef(w.volPersist)),
      ("volOfVol", ef(w.volOfVol)), ("valuePull", ef(w.valuePull)),
      ("crowd", jsonStr(crowdName(w.crowd))), ("crowdImpact", ef(w.crowdImpact)),
      ("panic", ef(w.panic)), ("duration", ef(w.duration)),
      ("easing", ef(w.easing)), ("unwind", ef(w.unwind)), ("refuge", ef(w.refuge)),
      ("inflProb", ef(w.inflProb)), ("inflSize", ef(w.inflSize)),
      ("inflSpeed", ef(w.inflSpeed)), ("rateSpeed", ef(w.rateSpeed)),
      ("discount", ef(w.discount)), ("margin", ef(w.margin)),
    ).map((nm, v) => s"""    ${jsonStr(nm)}: $v""")

  /** Everything that licenses the TSV: which (world, seed, path) produced it, on what calendar,
    * and what the world's two gate verdicts and fidelity ratios were.  A warning printed to stderr
    * at export time does not survive the file being moved; this does.
    *
    * `schema` and `version` answer different questions and neither substitutes for the other:
    * `schema` says whether a reader can parse the file, `version` says which release's simulator
    * wrote it.  The default world moved at 0.19.1 and again at 0.19.2, so two files with identical
    * columns and identical schema can still be incomparable — a consumer that pins its calibration
    * to a release checks `version`, and one that needs the exact parameters reads `world` below.
    * `schema` went 1 -> 2 when `version` was added, so its absence is detectable rather than
    * ambiguous. */
  def writeEmitSidecar(file: String, p: Path, k: Int, w: World, years: Int, seed: Long,
                       startYmd: String, dates: Vector[String], gateSt: WorldStats,
                       gatePaths: Int): Unit =
    val n            = p.price.length
    val realismBad   = failedIn(gateSt, GateClass.Realism)
    val mechanismBad = failedIn(gateSt, GateClass.Mechanism)
    val fidelityBad  = failedIn(gateSt, GateClass.Fidelity)
    def strList(v: Vector[String]): String = v.map(jsonStr).mkString("[", ", ", "]")
    def num(x: Double): String = if x.isNaN then "null" else ef(x)
    val fidelity = FitTargets.map { (nm, get, want, _) =>
      val got   = get(gateSt)
      val ratio = if want != 0.0 then got / want else Double.NaN
      val miss  = ratio > 1.5 || ratio < 0.667
      s"""    { "name": ${jsonStr(nm)}, "model": ${num(got)}, "real": ${num(want)}, """ +
      s""""ratio": ${num(ratio)}, "miss": $miss }"""
    }
    val json = Vector(
      "{",
      """  "generator": "market_sim",""",
      s"""  "version": ${jsonStr(Version)},""",
      s"""  "schema": $EmitSchema,""",
      s"""  "file": ${jsonStr(file)},""",
      s"""  "columns": ${strList(EmitColumns)},""",
      """  "header": true,""",
      """  "path": {""",
      s"""    "index": $k,""",
      s"""    "baseSeed": $seed,""",
      """    "seedStride": 7919,""",
      s"""    "pathSeed": ${seed + k.toLong * 7919L},""",
      s"""    "years": $years,""",
      s"""    "sessions": $n,""",
      s"""    "burnIn": $BurnIn,""",
      s"""    "sessionsPerYear": $DaysPerYear,""",
      s"""    "calendar": ${jsonStr(if startYmd.isEmpty then "synthetic-365-252" else "weekday")},""",
      s"""    "startDate": ${jsonStr(dates.head)},""",
      s"""    "endDate": ${jsonStr(dates.last)}""",
      "  },",
      """  "world": {""",
      worldJsonBody(w).mkString(",\n"),
      "  },",
      """  "gate": {""",
      s"""    "ensemblePaths": $gatePaths,""",
      s"""    "ensembleYears": $years,""",
      s"""    "realism": ${jsonStr(if realismBad.isEmpty then "PASS" else "FAIL")},""",
      s"""    "mechanism": ${jsonStr(if mechanismBad.isEmpty then "PASS" else "FAIL")},""",
      s"""    "fidelity": ${jsonStr(if fidelityBad.isEmpty then "PASS" else "FAIL")},""",
      s"""    "realismFailed": ${strList(realismBad)},""",
      s"""    "mechanismFailed": ${strList(mechanismBad)},""",
      s"""    "fidelityFailed": ${strList(fidelityBad)},""",
      // Bands the anchors could not grade in this world, with the reason.  Without this a path
      // emitted from (say) a 1.8-year-duration world shows fidelity PASS and nothing says the
      // depth level was never graded at all -- a consumer would read levels off it.
      s"""    "fidelityUnanchored": ${strList(unanchoredIn(gateSt))}""",
      "  },",
      """  "fidelity": [""",
      fidelity.mkString(",\n"),
      "  ]",
      "}")
    sidecarName(file).asPath.writeLines(json)

  // ---- entry point ---------------------------------------------------------------------------
  def main(args: Array[String]): Unit =
    var paths = DefaultPaths; var years = DefaultYears; var seed = DefaultSeed
    var emit = ""; var validate = false; var strategies = false; var single = false
    var emitPath = 0; var emitAll = false; var emitStart = ""; var emitGate = DefaultEmitGate
    var gateReq = GateDefault
    var fitnessOnly = false; var calibrateN = 0
    var powerReport = false; var bufferReport = false; var releaseReport = false
    var crossAsset = false; var noiseReport = false
    var powerArms = PowerArmsDefault; var powerYears = PowerYearsDefault
    var cost = DefaultCost
    // defaults = a random search against the fitness loss, scored at 100-year paths, lightly
    // rounded.  Reachable ONLY because depth, trendShare, drift and crowdImpact are in the search;
    // held fixed, as all four were until 0.19.1, no sample gets here.  Loss 3.13-3.57 across five
    // scoring seeds against the pre-0.19.1 defaults' 5.77-6.11.  Those figures are under the
    // equal-precision objective that search ran against.  The 0.20.0 defaults come from a
    // re-search under the measured-precision objective (see `wgt` and the CHANGELOG): the loss
    // now prices clustering at 2.2x, which is why `stress` could move UP to 5.6 with the
    // clustering regression bought knowingly (1.08) instead of blindly — the guard below is
    // HISTORY explaining the 0.19.1/0.19.2 choices, not a description of the current trade.
    //
    // `stress` IS NOT AT THE OBJECTIVE'S MINIMUM, deliberately, and has now been moved DOWN twice
    // for the same reason.  The liquidity spiral is a single amplifier producing volatility, fat
    // tails AND volatility clustering together -- `stress` alone moves ac1 from 0.160 at 3.4 to
    // 0.420 at 7.0 -- so buying tails always buys clustering with them, and clustering above 1.0
    // means volatility is more forecastable here than in the record, which flatters every rule
    // that forecasts it.  0.19.1 chose 5.4 over the then-minimum 5.9 on that trade; 0.19.2 chose
    // 5.1 over 5.4 on the same one, because capping the rate cut (see `easing`) removed a discount-
    // channel cushion in crashes and pushed clustering from 1.08 to 1.13 at unchanged `stress`.
    // 5.1 with depth 16.6 returns clustering to 1.06 and costs kurtosis 0.46 -> 0.42, which is a
    // recorded scope exclusion either way.  Do not "optimise" `stress` upward without re-reading
    // this: the objective does not weigh the clustering regression heavily enough to see it.
    //   `depth` moved 16.3 -> 16.6 in the same step and for a different reason: the same lost
    //   cushion raised the crash rate from 1.20 to 1.38, and depth is the dial that carries crash
    //   frequency.  It buys back a third of it (1.32).  The rest is the mechanism's price, stated
    //   in the CHANGELOG rather than tuned away.
    //   The clustering figures here are against the CENTURY anchor.  Measured against the 72-year
    //   one this shipped with, the same worlds read 0.90 / 1.20 / 1.33 -- the horizon mismatch, not
    //   a change in the model.
    //
    // KURTOSIS AND CLUSTERING CANNOT BOTH BE RIGHT.  stress 7.5 reaches kurtosis 26.4 against a real
    // 28 -- and clustering 1.67, failing the realism band.  That is the measured reason the kurtosis
    // MISS stands, more precise than "no slow valuation cycle": the cycle is why there is no SECOND
    // channel for tails, not why this one cannot reach them.
    //
    // THREE KNOWN BIAS DIRECTIONS, netted away nowhere else: clustering at 1.06 makes volatility
    // more predictable here than in the record, which flatters any rule that forecasts it; worst
    // crash at 1.44 puts index paths near -82% against a real -56.8%, which no levered fund
    // survives, so ruin rates for levered sleeves are UPPER BOUNDS, not estimates; and crashes
    // arrive 1.32x too often, so any per-crash hazard read off this model is over-sampled.
    var trendShare = Defaults.trendShare; var depth = Defaults.depth
    var stress = Defaults.stress; var beta = Defaults.beta
    var volPersist = Defaults.volPersist; var volOfVol = Defaults.volOfVol
    var valuePull = Defaults.valuePull
    var crowdName = "momentum"; var crowdImpact = Defaults.crowdImpact; var panic = Defaults.panic
    var drift = Defaults.drift; var rateMean = Defaults.rateMean
    var duration = Defaults.duration
    var easing = Defaults.easing; var unwind = Defaults.unwind; var refuge = Defaults.refuge
    var inflProb = Defaults.inflProb; var inflSize = Defaults.inflSize
    var inflSpeed = Defaults.inflSpeed; var rateSpeed = Defaults.rateSpeed
    var discount = Defaults.discount; var margin = Defaults.margin
    eachArg(args.toSeq, usage) {
      // Bare version on stdout and nothing else, so a caller can gate on it without parsing:
      // `[ "$(marketSim.sc -version)" = "$want" ] || exit 1`.  Handled where it is seen, so it
      // answers before any other flag is validated.
      case "-version"    => println(Version); System.exit(0)
      case "-paths"      => paths = intOr("-paths", consumeNext)
      case "-years"      => years = intOr("-years", consumeNext)
      case "-seed"       => seed = longOr("-seed", consumeNext)
      case "-emit"       => emit = consumeNext
      case "-emitpath"   => emitPath = intOr("-emitpath", consumeNext)
      case "-emitall"    => emitAll = true
      case "-emitstart"  => emitStart = consumeNext
      case "-emitgate"   => emitGate = intOr("-emitgate", consumeNext)
      case "-gate"       => gateReq = parseGate(consumeNext)
      case "-validate"   => validate = true
      case "-fitness"    => fitnessOnly = true
      case "-calibrate"  => calibrateN = intOr("-calibrate", consumeNext)
      case "-strategies" => strategies = true
      case "-power"      => powerReport = true
      case "-releases"   => releaseReport = true
      case "-crossasset" => crossAsset = true
      case "-noise"      => noiseReport = true
      case "-powerarms"  => powerArms = intListOr("-powerarms", consumeNext)
      case "-poweryears" => powerYears = intListOr("-poweryears", consumeNext)
      case "-buffer"     => bufferReport = true
      case "-single"     => single = true
      case "-cost"       => cost = numOr("-cost", consumeNext)
      case "-trendshare" => trendShare = numOr("-trendshare", consumeNext)
      case "-depth"      => depth = numOr("-depth", consumeNext)
      case "-stress"     => stress = numOr("-stress", consumeNext)
      case "-beta"       => beta = numOr("-beta", consumeNext)
      case "-volpersist" => volPersist = numOr("-volpersist", consumeNext)
      case "-volofvol"   => volOfVol = numOr("-volofvol", consumeNext)
      case "-value"      => valuePull = numOr("-value", consumeNext)
      case "-crowd"      => crowdName = consumeNext
      case "-crowdimpact"=> crowdImpact = numOr("-crowdimpact", consumeNext)
      case "-panic"      => panic = numOr("-panic", consumeNext)
      case "-drift"      => drift = numOr("-drift", consumeNext)
      case "-ratemean"   => rateMean = numOr("-ratemean", consumeNext)
      case "-duration"   => duration = numOr("-duration", consumeNext)
      case "-easing"     => easing = numOr("-easing", consumeNext)
      case "-unwind"     => unwind = numOr("-unwind", consumeNext)
      case "-refuge"     => refuge = numOr("-refuge", consumeNext)
      // Rejected, not silently reinterpreted: -flight was a rate cut SPEED per year and -easing is
      // a cut CAP in rate points, so every recorded -flight value is wrong by two orders of
      // magnitude under the new mechanism and would still have produced a plausible-looking run.
      case "-flight"     => usage("-flight is gone: the rate cut is now a CAPPED, slowly unwound " +
                                  "accommodation. Use -easing (cap, rate points) and -unwind " +
                                  "(withdrawal per year). No -flight value carries over.")
      case "-inflprob"   => inflProb = numOr("-inflprob", consumeNext)
      case "-inflsize"   => inflSize = numOr("-inflsize", consumeNext)
      case "-inflspeed"  => inflSpeed = numOr("-inflspeed", consumeNext)
      case "-ratespeed"  => rateSpeed = numOr("-ratespeed", consumeNext)
      case "-discount"   => discount = numOr("-discount", consumeNext)
      case "-margin"     => margin = numOr("-margin", consumeNext)
      case a             => usage(s"unrecognized arg [$a]")
    }
    // Bounds that make the run meaningful.  -paths 0 -emitall crashed on `written.head`;
    // -years 0 crashed in measure; a negative seed has no NumPy counterpart.
    if paths < 1 then usage(s"-paths must be at least 1, got $paths")
    if years < 1 then usage(s"-years must be at least 1, got $years")
    if seed < 0 then usage(s"-seed must be non-negative, got $seed")
    if emitPath < 0 then usage(s"-emitpath must be non-negative, got $emitPath")
    if emitGate < 0 then usage(s"-emitgate must be non-negative, got $emitGate")
    // A bad index here is the one place the rule list has to be discoverable: the report names
    // the rules but not their numbers, and the numbers are what the flag takes.
    if powerArms.exists(i => i < 1 || i > Rules.size) then
      usage(s"-powerarms indices must be 1-${Rules.size}; the rules are:\n" +
            Rules.zipWithIndex.map((r, i) => f"  ${i + 1}%d  ${r.name}%s").mkString("\n"))
    if powerYears.exists(_ < 1) then
      usage(s"-poweryears wants year counts of at least 1, got [${powerYears.mkString(",")}]")
    val crowd = crowdName.toLowerCase match
      case "momentum"  => Crowd.Momentum
      case "volscaled" => Crowd.VolScaled
      case t if t.startsWith("trend") =>
        Crowd.Trend(t.drop(5).toIntOption.filter(_ > 0).getOrElse(
          usage(s"unknown -crowd [$crowdName]; use momentum, trendNNN, or volscaled")))
      case other => usage(s"unknown -crowd [$other]; use momentum, trendNNN, or volscaled")
    val w = World(trendShare, depth, stress, beta, drift = drift, fundVol = Defaults.fundVol,
                  rateMean = rateMean,
                  volPersist = volPersist, volOfVol = volOfVol, valuePull = valuePull,
                  crowd = crowd, crowdImpact = crowdImpact, panic = panic,
                  duration = duration, easing = easing, unwind = unwind, refuge = refuge,
                  inflProb = inflProb, inflSize = inflSize,
                  inflSpeed = inflSpeed, rateSpeed = rateSpeed, discount = discount, margin = margin)

    if calibrateN > 0 then
      calibrate(calibrateN, w, seed)
      return
    if fitnessOnly then
      val st = measure(simPaths(w, 60, 80, seed), 80)
      val (loss, rows) = fitness(st)
      println(f"fitness loss $loss%.3f  (lower is better; includes 0.5 per failed gate check)")
      rows.foreach((n, m, t, term) => println(f"  $n%-22s model $m%8.2f   target $t%8.2f   term $term%6.3f"))
      gateChecks(st).filter(!_._2).foreach((n, _, _) => println(f"  FAILED GATE: $n%s  (+0.500)"))
      return
    if releaseReport then
      runReleaseReport(paths, years, seed, w)
      return
    if crossAsset then
      // Exits non-zero on an in-support miss, or when a relation graded nothing (INCONCLUSIVE)
      // — an EXTRAP cell alone is disclosed, not fatal.
      if !runCrossAssetReport(paths, years, seed, w) then System.exit(1)
      return
    if noiseReport then
      // -years is ignored deliberately: the horizons come from the anchors themselves, and the
      // seed-noise section from the scoring configuration.
      runNoiseReport(paths, seed, w)
      return
    if strategies then
      runStrategySweep(paths, years, seed, cost, single, w, gateReq)
      return
    if powerReport then
      runPowerReport(paths, seed, cost, single, w, gateReq, powerArms, powerYears)
      return
    if bufferReport then
      runBufferReport(paths, years, seed, cost, single, w, gateReq)
      return

    eprintln(s"simulating $paths paths x $years years")
    val sims = simPaths(w, paths, years, seed)
    val st = measure(sims, years)

    if emit.nonEmpty then
      // The verdict is a property of the WORLD, so it is measured on an ensemble large enough for
      // the conditional mechanism statistics to exist.  Judging the world by the one path being
      // written made every short export raise all four mechanism failures — a guaranteed false
      // alarm, which trains a consumer to ignore the warning entirely.
      val (gateSt, gatePaths) =
        if emitGate > paths then (measure(simPaths(w, emitGate, years, seed), years), emitGate)
        else (st, paths)
      val realismBad   = failedIn(gateSt, GateClass.Realism)
      val mechanismBad = failedIn(gateSt, GateClass.Mechanism)
      val fidelityBad  = failedIn(gateSt, GateClass.Fidelity)
      if realismBad.nonEmpty then
        eprintln("WARNING: this world FAILS the realism bands " + realismBad.mkString("[", ", ", "]") +
                 " — the emitted path is not market-like")
      if mechanismBad.nonEmpty then
        eprintln("NOTE: mechanisms inert in this world " + mechanismBad.mkString("[", ", ", "]") +
                 " — conclusions that lean on them are not supported here")
      if fidelityBad.nonEmpty then
        eprintln("NOTE: levels not readable in this world " + fidelityBad.mkString("[", ", ", "]") +
                 " — rank comparisons survive, anything reading a level off these does not")
      // path k is a function of (world, years, seed, k) alone, so an index past the report
      // ensemble is simulated directly rather than forcing a larger run
      def pathAt(k: Int): Path =
        if k < sims.length then sims(k) else simulate(w, years, seed + k.toLong * 7919L)
      val written =
        if emitAll then
          for k <- 0 until paths yield
            val f = indexedName(emit, k)
            writeEmitted(f, sims(k), k, w, years, seed, emitStart, gateSt, gatePaths)
            f
        else
          val p = pathAt(emitPath)
          writeEmitted(emit, p, emitPath, w, years, seed, emitStart, gateSt, gatePaths)
          Vector(emit)
      val sessions = pathAt(if emitAll then 0 else emitPath).price.length
      eprintln(s"wrote ${written.size} path(s), ${EmitColumns.size} columns x $sessions sessions, " +
               s"to ${written.head}${if written.size > 1 then s" .. ${written.last}" else ""} " +
               s"(+ sidecar ${sidecarName(written.head)})")

    val allRets = sims.map(s => dailyReturns(s.price))
    val annVol  = allRets.map(r => math.sqrt(r.map(x => x * x).sum / r.length * DaysPerYear))
    val annRet  = sims.map(s => math.log(s.price.last / s.price.head) / years * 100.0)

    println(f"paths $paths%d x $years%d years   ${paths * years}%d simulated years")
    println()
    println(f"  annualised return      median ${st.annRet}%6.2f%%   5th ${pctile(annRet, 0.05)}%6.2f%%   95th ${pctile(annRet, 0.95)}%6.2f%%")
    println(f"  annualised volatility  median ${st.vol * 100}%6.2f%%   5th ${pctile(annVol, 0.05) * 100}%6.2f%%   95th ${pctile(annVol, 0.95) * 100}%6.2f%%")
    println(f"  daily return kurtosis  median ${st.kurt}%6.2f")
    println(f"  volatility clustering  lag  1 ${st.ac1}%6.3f   lag 20 ${st.ac20}%6.3f")
    println()
    println(f"  drawdowns of 15%%+      ${st.nEpisodes}%d, ${st.epPerPath}%.1f per path; ${st.censored}%d unrecovered at path end (included in depth)")
    println(f"  their depth            median ${st.depthMed}%6.1f%%   worst ${st.worstDepth}%6.1f%%")
    println(f"  recovery shape         V ${st.vCount}%d   balanced ${st.midCount}%d   U ${st.uCount}%d")
    println(f"  bond refuge            vol ${st.bondVol * 100}%.1f%% (24y windows)   growth-crash ${pm(st.bondGrowth, 0, 1)}%s   infl-crash ${pm(st.bondInfl, 0, 1)}%s")
    println(f"  stock-bond correlation calm ${pm(st.corrCalm, 0, 2)}%s   inflation regime ${pm(st.corrInfl, 0, 2)}%s")
    println(f"  realized inflation     ${st.inflAnn}%.2f%%/yr median (deterministic from regime pressure; no draws consumed)")
    println(f"  depth profile          share of sessions below the running peak, median path")
    println(f"    equity               >5%% ${st.ddEq5}%.3f   >10%% ${st.ddEq10}%.3f   >20%% ${st.ddEq20}%.3f" +
            f"      real SPY 0.447 / 0.315 / 0.169")
    println(f"    bond                 >5%% ${st.ddBd5}%.3f   >10%% ${st.ddBd10}%.3f   >20%% ${st.ddBd20}%.3f" +
            f"      real TLT   -   / 0.510 /   -")
    println(f"  binding diagnostics    trend share ${st.trendShare}%.2f (pinned ${st.trendPinned * 100}%.1f%%, " +
            f"target saturated ${st.targetSat * 100}%.1f%%)   bond spiral ${st.pctBondStress * 100}%.1f%% of sessions   " +
            f"clamped ${st.clampPct}%.3f%%")
    println(f"                         crowd flow ${st.crowdFlow * 1e4}%.2f bp/session " +
            f"(${st.crowdFlow / SigmaN * 100}%.1f%% of the noise term) — the reflexive channel")

    println()
    // The anchors do NOT share one window, and a single-window label invites a reader to re-derive
    // them from it and conclude the model has drifted.  Measured over 1954-2026, the equity depth
    // rungs read 0.436 / 0.269 / 0.126 against the 0.447 / 0.315 / 0.169 targeted here.
    println("  fidelity against targets, by anchor (each row is against the window named for it):")
    println("    equity S&P 1954-2026   |   depth rungs SPY 1993-2026   |   return per vol CRSP 1954-2026")
    println("    clustering CRSP 1926-2026 (a CENTURY: the statistic is horizon-dependent and the")
    println("      model is scored on 100-year paths)   |   refuge long Treasury   |   bond depth")
    println("      rung clean TLT, 24y")
    println("    NOTE: bond volatility alone is measured over 24-YEAR windows, not the whole path —")
    println("      it is the one horizon-dependent statistic whose anchor can only come from fund")
    println("      data, and no clean bond-fund series runs longer.  Every other row is whole-path.")
    FitTargets.foreach { (n, get, want, _) =>
      val got = get(st)
      val ratio = if want != 0 then got / want else Double.NaN
      val flag  = if ratio > 1.5 || ratio < 0.667 then "  <-- MISS" else ""
      println(f"     $n%-22s model ${got}%8.2f   real ${want}%8.2f   ratio ${ratio}%5.2f$flag%s")
    }

    if validate then
      val checks = gateChecks(st)
      val bad    = GateClass.values.map(c => c -> failedIn(st, c)).toMap
      def verdict(c: GateClass) = if bad(c).isEmpty then "PASS" else "FAIL"
      println()
      println("  acceptance gate:")
      val una = unanchoredIn(st)
      for (cls, banner, cost) <- GateSections do
        println(f"    $banner%s — $cost%s:")
        checks.filter(_._3 == cls).foreach((n, ok, _) =>
          println(f"     ${if ok then "PASS" else "FAIL"}%-5s $n%s"))
        // A band whose anchors cannot grade this world is disclosed where it would have
        // appeared, not silently absent -- n/a, never PASS or FAIL.
        if cls == GateClass.Fidelity then
          una.foreach(n => println(f"     ${"n/a"}%-5s $n%s"))
      println(f"    verdict: realism ${verdict(GateClass.Realism)}%s   " +
              f"mechanism ${verdict(GateClass.Mechanism)}%s   fidelity ${verdict(GateClass.Fidelity)}%s")
      if bad(GateClass.Mechanism).nonEmpty then
        println(bad(GateClass.Mechanism).mkString("      inert: ", ", ", ""))
      if bad(GateClass.Fidelity).nonEmpty then
        println(bad(GateClass.Fidelity).mkString("      levels not readable: ", ", ", ""))
      if una.nonEmpty then
        println(una.mkString("      no anchor: ", ", ", ""))
      // exit code follows the classes this run declared it requires, nothing more
      val blocking = GateClass.values.filter(c => gateReq.contains(c) && bad(c).nonEmpty)
      if blocking.nonEmpty then
        eprintln(s"acceptance gate FAILED for required ${blocking.map(_.toString.toLowerCase).mkString(", ")}" +
                 blocking.flatMap(bad).mkString(" [", ", ", "]") +
                 s" — required classes are ${gateReq.toVector.map(_.toString.toLowerCase).sorted.mkString(",")}; " +
                 "change them with -gate")
        System.exit(1)

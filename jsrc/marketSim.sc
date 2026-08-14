#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using scala 3.7.2
//> using dep org.vastblue:uni_3:0.16.0

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
//   the RATE carries policy: it chases rateMean + inflation pressure, and is cut when equity
//     stress is high (flight/policy response) UNLESS inflation pressure ties its hands.
//   margin coupling: when BOTH markets are stressed, forced selling hits the bond too.
//
// SCOPE DECISION (recorded, not hidden): daily kurtosis (~8 vs real 28) and crash frequency
// (~1.6-1.9x real) both trace to the absence of a slow valuation cycle (no bubbles, no 1929-1954
// era).  Fixing that is a new mechanism, deliberately NOT attempted here; the two MISS flags stay
// visible in every fidelity report.  Conclusions that depend on tail-day magnitudes or on
// crash-arrival spacing must not be drawn from this model.
//
// EVERY MECHANISM SHIPS WITH (the recurring failure class here is one-sided checks and knobs that
// silently do not bind — it recurred even inside fixes for previous instances):
//   1. a BINDING diagnostic printed in the output (realized trend share, bond-spiral engagement,
//      clamp counts, pinned share),
//   2. a TWO-SIDED acceptance bound where a plausible range exists,
//   3. an OFF-world in the sweep (no spiral, no flight, no margin coupling).
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
//   inflation flight-suppression scale 0.005 | rate-news multiplier 1+25*inflPress (rate
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

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "-paths N      ; independent price paths (default 200)",
    "-years Y      ; years per path (default 100)",
    "-seed S       ; base random seed (default 20260813)",
    "-emit F       ; write path 0 as date/price/bond TSV",
    "-validate     ; stylised-fact gate + fidelity report; exit non-zero on gate failure",
    "-fitness      ; print the scalar calibration loss and its components, then exit",
    "-calibrate N  ; random-search N parameter samples against the fitness loss; scores the",
    "              ;   best few again on a HELD-OUT seed; prints, does not modify defaults",
    "-power        ; estimator power: how much history each grading statistic needs before its",
    "              ;   own answer stops being noise (hit rate and n* per statistic per horizon)",
    "-buffer       ; distribution of REAL underwater-stretch length and depth at exhaustion —",
    "              ;   the cash-buffer question, as a distribution instead of one episode",
    "-strategies   ; exposure rules across a world sweep: stability, paired stats, breakevens,",
    "              ;   flight-to-safety decomposition, refuge-severity curve, crash types",
    "-single       ; with -strategies/-power/-buffer, baseline world only (skip the world sweep)",
    "-cost X       ; calm-market cost per unit of exposure changed (default 0.0010 = 10bp)",
    "-trendshare X ; mandate level for trend-follower capital (spring, not a wall; realized",
    "              ;   share and pinned fraction are reported)",
    "-depth X      ; equity market depth; impact scales as 12/depth (default 12)",
    "-stress X     ; liquidity-withdrawal amplification, shared by BOTH markets; 0 = off",
    "-beta X       ; intensity of capital switching between agent types (default 3.0)",
    "-volpersist X ; persistence of volatile stretches (default 0.99)",
    "-volofvol X   ; size of shocks to volatility itself",
    "-value X      ; pull toward equity fair value, per day",
    "-crowd K      ; momentum (default), trendNNN, or volscaled — the last two make the crowd",
    "              ;   run the RULE UNDER TEST, closing the reflexive loop",
    "-crowdimpact X; price pressure per unit of exposure the crowd trades (default 0.06)",
    "-panic X      ; stress-accelerated capital reallocation; 0 = symmetric flows (default)",
    "-duration X   ; bond duration in years (default 15, a long-Treasury refuge)",
    "-flight X     ; policy/flight rate cut under equity stress, suppressed by inflation",
    "-inflprob X   ; chance a regime shift starts an inflation regime (default 0.20)",
    "-inflsize X   ; rate pressure target in one, per year (default 0.06)",
    "-inflspeed X  ; how fast pressure ramps, per session (default 0.010)",
    "-ratespeed X  ; how fast the short rate chases its target, per year (default 3.0)",
    "-discount X   ; equity fair-value sensitivity to the rate, % per pp (default 6)",
    "-margin X     ; forced bond selling when BOTH markets are stressed (default 0.0008)",
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
    flight: Double,     // policy/flight rate response to equity stress (inflation-suppressed)
    inflProb: Double, inflSize: Double, inflSpeed: Double, rateSpeed: Double,
    discount: Double,   // equity fair-value markdown per pp of rate above its long-run mean
    margin: Double,     // joint-stress forced selling pressure on the bond
  )

  final case class Path(price: Array[Double], rate: Array[Double], fundamental: Array[Double],
                        liq: Array[Double],      // per-session slippage multiplier (equity market)
                        bond: Array[Double],     // flight-to-safety asset price (its own Market)
                        inflPress: Array[Double],// inflation pressure, for regime classification
                        cpi: Array[Double],      // realized price level, deterministic from pressure
                        meanTrendShare: Double,  // BINDING diagnostic for the population knob
                        trendPinned: Double,     // share of sessions on the numerical guard rails
                        targetSat: Double,       // share of sessions the choice target saturated
                        clampedDays: Int,        // both markets, post-burn-in
                        meanBondStress: Double,  // BINDING diagnostic for the bond spiral
                        pctBondStress: Double)   // share of sessions bond stress index > 0.5

  val DaysPerYear = 252
  /** Sessions discarded so paths start from the stationary distribution (slowest state ~600). */
  val BurnIn = 756
  // Treasuries incorporate rate news SAME-DAY — at 0.05 the bond market smeared a fair-value move
  // over ~20 sessions, which crushed the daily stock-bond correlation (the flip read +0.05) and
  // halved every crash-window bond response.  0.7 = near-immediate tracking, with flows and the
  // spiral acting as short-lived deviations on top, which is what bond-market dysfunction is.
  val KValueBond = 0.7
  val SigmaNBond = 0.002

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
    val kTrend = 0.0045; val sigmaN = 0.007
    val kAdapt = 0.010; val kHome = 0.020
    var logVol = 0.0
    val volNorm = (w.volOfVol * w.volOfVol) / math.max(1e-9, 1.0 - w.volPersist * w.volPersist)
    val crowdWin = w.crowd match
      case Crowd.Trend(d) => math.max(2, math.round(d * 252.0 / 365.25).toInt)
      case _              => 0
    var crowdE = 1.0; var crowdPrev = 1.0; var maSum = 0.0
    var crowdRv = 0.01 * 0.01; var crowdAnchor = 0.0
    var bondStressSum = 0.0; var bondStressHi = 0
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
      // policy: chase rateMean+pressure; cut on equity stress UNLESS inflation ties its hands
      val flightCut = w.flight * eqM.stressIdx * math.exp(-inflPress / 0.005)
      val rOld = rate
      // rate UNCERTAINTY rises with inflation pressure (2022: MOVE elevated all year).  This is what
      // makes stocks and bonds co-move in an inflation regime: both are priced off the same rate,
      // so more rate news = more shared-factor variance = the correlation flip.  A constant rate
      // noise produced a flip of only +0.05 — present but too weak to pass its own gate.
      rate = math.max(0.0, rate + w.rateSpeed * ((w.rateMean + inflPress) - rate) * dt
                              - flightCut * dt + 0.01 * (1.0 + 25.0 * inflPress) * sqdt * rng.randn())
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
        case Crowd.Momentum => kTrend * wTrend * trendPos
        case _              => w.crowdImpact * wTrend * (crowdE - crowdPrev)
      crowdPrev = crowdE
      logVol = w.volPersist * logVol + w.volOfVol * rng.randn()
      val dNoise = sigmaN * math.exp(logVol - volNorm) * rng.randn()

      // ---- both markets step through the SAME mechanism --------------------------------------
      val retE = eqM.step(logVbase, eqFlow + dNoise)
      // joint-stress margin selling: when both markets are stressed, the bond gets dumped too
      val bondFlow = -w.margin * eqM.stressIdx * bdM.stressIdx
      val retB = bdM.step(fairB, bondFlow + SigmaNBond * rng.randn())
      val _ = retB

      px(i) = math.exp(eqM.logP - markdown)
      fv(i) = math.exp(logVbase - markdown)
      rt(i) = rate
      lq(i) = eqM.lastLiq
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
        if bdM.stressIdx > 0.5 then bondStressHi += 1
      if i == BurnIn then clampsAtBurn = eqM.clamps + bdM.clamps
      i += 1

    Path(px.drop(BurnIn), rt.drop(BurnIn), fv.drop(BurnIn), lq.drop(BurnIn),
         bp.drop(BurnIn), ip.drop(BurnIn), cp.drop(BurnIn),
         wTrendSum / n, pinnedCnt.toDouble / n, satCnt.toDouble / n,
         eqM.clamps + bdM.clamps - clampsAtBurn,
         bondStressSum / n, bondStressHi.toDouble / n)

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

  // ---- world statistics and the ONE acceptance predicate -------------------------------------
  final case class WorldStats(vol: Double, kurt: Double, ac1: Double, ac20: Double, annRet: Double,
                              nEpisodes: Int, epPerPath: Double, depthMed: Double, worstDepth: Double,
                              vCount: Int, midCount: Int, uCount: Int, nShapes: Int, censored: Int,
                              clampPct: Double, trendShare: Double, yearsPerPath: Double,
                              trendPinned: Double, targetSat: Double,
                              bondVol: Double, bondGrowth: Double, bondInfl: Double,
                              corrCalm: Double, corrInfl: Double,
                              meanBondStress: Double, pctBondStress: Double,
                              inflAnn: Double)

  def measure(sims: Vector[Path], years: Int): WorldStats =
    val rets = sims.map(s => dailyReturns(s.price))
    def med(v: Seq[Double]) = { val f = v.filter(x => !x.isNaN); if f.isEmpty then Double.NaN else f.sorted.apply(f.size / 2) }
    val epsBy  = sims.map(s => s -> episodes(s.price, 15.0))   // once per path (was recomputed 3x)
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
      bondVol = med(sims.map(s => math.sqrt(MatD(dailyReturns(s.bond)).power(2).mean * DaysPerYear))),
      bondGrowth = bondInWindows(false), bondInfl = bondInWindows(true),
      corrCalm = corrIn(false), corrInfl = corrIn(true),
      meanBondStress = sims.map(_.meanBondStress).sum / sims.size,
      pctBondStress = sims.map(_.pctBondStress).sum / sims.size,
      inflAnn = med(sims.map(s => math.log(s.cpi.last / s.cpi.head) / years * 100.0)))

  /** TWO-SIDED wherever a plausible range exists.  History of this gate: a one-sided version
    * passed a 35%-volatility world (the one reversing the ranking); a "bonds fail" check written
    * as bondInfl < bondGrowth passed while bonds still RALLIED +2.8; crash frequency shipped
    * without an upper bound WHILE the one-sided lesson was being applied elsewhere in this file. */
  def gateChecks(st: WorldStats): Vector[(String, Boolean)] = Vector(
    ("equity vol 8-25%",          st.vol > 0.08 && st.vol < 0.25),
    ("kurtosis 4-30",             st.kurt > 4.0 && st.kurt < 30.0),
    ("clustering 0.10-0.40",      st.ac1 > 0.10 && st.ac1 < 0.40 && st.ac20 > 0.03),
    ("crash rate 8-45/century",   st.epPerPath >= 1.0 && {
        val pc = st.epPerPath * 100.0 / st.yearsPerPath; pc >= 8.0 && pc <= 45.0 }),
    ("both recovery shapes",      st.nShapes > 0 && st.vCount >= st.nShapes / 10 && st.uCount >= st.nShapes / 10),
    ("no runaway drift",          st.annRet.abs < 30.0),
    // 0.02% ~ one clamped session per 20 path-years.  The old bound (0.5%) would have passed a
    // world where the clamp was already reshaping kurtosis by a third.
    ("clamp rarely binds",        st.clampPct < 0.02),
    ("bond vol 7-20%",            st.bondVol > 0.07 && st.bondVol < 0.20),
    ("bonds rally in growth shocks",    st.bondGrowth > 3.0),
    ("bonds LOSE in inflation regimes", st.bondInfl < -3.0),
    ("corr flips positive under inflation",
        !st.corrInfl.isNaN && !st.corrCalm.isNaN &&
        st.corrInfl > st.corrCalm + 0.15 && st.corrInfl > 0.0 && st.corrCalm < 0.35),
    ("bond spiral engages, not always", st.pctBondStress > 0.002 && st.pctBondStress < 0.5),
    ("inflation 1-6%/yr",         st.inflAnn > 1.0 && st.inflAnn < 6.0),
  )

  /** Scalar calibration loss: weighted |log(model/target)| over the fidelity targets, a penalty of
    * 2 for a wrong sign, and 0.5 per failed gate check.  Exists so calibration is a SEARCH against
    * a fixed objective instead of eyeballing console output — eyeball tuning at 60 years produced a
    * -99% world at 100 years. */
  val FitTargets: Vector[(String, WorldStats => Double, Double, Double)] = Vector(
    ("equity vol %",       st => st.vol * 100,                              16.0,  1.0),
    ("kurtosis",           st => st.kurt,                                   28.0,  0.5),
    ("clustering lag 1",   st => st.ac1,                                     0.27, 1.0),
    ("clustering lag 20",  st => st.ac20,                                    0.20, 0.5),
    ("crashes/century",    st => st.epPerPath * 100.0 / st.yearsPerPath,    20.7,  1.0),
    ("median depth %",     st => st.depthMed,                              -27.1,  1.0),
    ("worst crash %",      st => st.worstDepth,                            -56.8,  1.0),
    ("bond vol %",         st => st.bondVol * 100,                          13.0,  1.0),
    ("bond growth-crash",  st => st.bondGrowth,                             20.0,  1.0),
    ("bond infl-crash",    st => st.bondInfl,                              -25.0,  1.5),
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
    val ranges: Vector[(String, Double, Double, (World, Double) => World)] = Vector(
      ("stress",    2.0,   6.0,   (w, x) => w.copy(stress = x)),
      ("valuePull", 0.010, 0.035, (w, x) => w.copy(valuePull = x)),
      ("volOfVol",  0.012, 0.030, (w, x) => w.copy(volOfVol = x)),
      ("flight",    0.2,   1.6,   (w, x) => w.copy(flight = x)),
      ("duration",  8.0,  18.0,   (w, x) => w.copy(duration = x)),
      ("inflSize",  0.03,  0.12,  (w, x) => w.copy(inflSize = x)),
      ("discount",  3.0,  10.0,   (w, x) => w.copy(discount = x)),
      ("margin",    0.0,   0.004, (w, x) => w.copy(margin = x)),
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
  def sweepWorlds(base: World, single: Boolean): Vector[(String, World)] =
      if single then Vector(("baseline", base))
      else Vector(
        ("baseline",                   base),
        ("few trend followers",        base.copy(trendShare = 0.15)),
        ("many trend followers",       base.copy(trendShare = 0.50)),
        ("no liquidity spiral",        base.copy(stress = 0.0)),
        ("severe liquidity spiral",    base.copy(stress = base.stress * 1.5)),
        ("weak value anchor",          base.copy(valuePull = base.valuePull * 0.6)),
        ("calm volatility",            base.copy(volOfVol = 0.010)),
        ("turbulent volatility",       base.copy(volOfVol = 0.030)),
        ("sticky capital",             base.copy(beta = 1.0)),
        ("fickle capital",             base.copy(beta = 6.0)),
        ("low growth",                 base.copy(drift = 0.060)),
        ("high growth",                base.copy(drift = 0.140)),
        ("shallow market",             base.copy(depth = 10.0)),
        ("deep market",                base.copy(depth = 15.0)),
        // NOT "cash leg only" any more: in v4 the rate level sets bond carry, and the zero floor
        // binds at low rates (an emergent zero-lower-bound) — the v2 label survived the refactor
        // that falsified it.  These now double as carry-level probes (low ~ 2022, high ~ 1970s).
        ("low rates / low carry",      base.copy(rateMean = 0.01)),
        ("high rates / high carry",    base.copy(rateMean = 0.07)),
        ("no flight bid",              base.copy(flight = 0.0)),          // OFF-world: refuge
        ("no margin coupling",         base.copy(margin = 0.0)),          // OFF-world: margin
        ("double inflation severity",  base.copy(inflSize = base.inflSize * 2.0)),
      )

  def runStrategySweep(paths: Int, years: Int, seed: Long, cost: Double, single: Boolean,
                       base: World): Unit =
    val worlds = sweepWorlds(base, single)
    eprintln(s"${worlds.size} worlds x $paths paths x $years years, ${Rules.size} rules x {cash,bond}")
    val results = worlds.map { (wname, w) =>
      val sims = simPaths(w, paths, years, seed)
      val st = measure(sims, years)
      val ok = gateChecks(st).forall(_._2)
      val evald = java.util.stream.IntStream.range(0, sims.size).parallel().mapToObj { k =>
        val s   = sims(k)
        val ind = new Indicators(s.price)
        val eps = episodes(s.price, 15.0)
        val fl  = eps.map(ep => fundamentalLed(s, ep))
        Rules.map(r => evaluate(s, eps, fl, r, ind, cost, years, Safe.Cash)) ++
        Rules.map(r => evaluate(s, eps, fl, r, ind, cost, years, Safe.Bond))
      }.toArray().toVector.map(_.asInstanceOf[Vector[(Outcome, Vector[(Boolean, Double, Double)])]])
      (wname, ok, st, evald)
    }

    println("Worlds failing the acceptance gate are marked and EXCLUDED from rank stability; their")
    println("detail stays visible so the exclusion is auditable.  vsFlat = advantage over a constant")
    println("portfolio at the rule's own average exposure IN THE SAME ASSETS; g/n = gross/net of")
    println("liquidity-scaled trading costs.  ruin = share of paths with a loss worse than 50%.")
    for (wname, ok, st, evald) <- results do
      println(f"\nWORLD: $wname%-28s ${if ok then "" else "*** OUT OF RANGE — excluded from ranks ***"}%s")
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

    val valid = results.filter(_._2)
    println(f"\n\nRANK STABILITY — ${valid.size}%d of ${results.size}%d worlds pass the gate; ranks use only those.")
    println("Rank stability is the WEAK form of robustness: magnitudes vary far more than ranks.")
    for (metricName, get) <- Vector(("median net return", (o: Outcome) => o.ann),
                                    ("median GROSS edge vs the fixed twin", (o: Outcome) => o.vsFlatG)) do
      println(f"\n  ranked by $metricName%s   (1 = best)")
      val ranks = Rules.indices.map { j =>
        j -> valid.map { (_, _, _, evald) =>
          val med = Rules.indices.map(k => k -> pctile(evald.map(_(k)._1).map(get), 0.5)).sortBy(-_._2)
          med.indexWhere(_._1 == j) + 1
        }
      }
      for (j, rs) <- ranks do
        println(f"  ${Rules(j).name}%-34s ${rs.map(r => f"$r%2d").mkString(" ")}%s   best ${rs.min}%d  worst ${rs.max}%d")

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
      val okSev = gateChecks(st).forall(_._2)
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
  def runPowerReport(paths: Int, seed: Long, cost: Double, single: Boolean, base: World): Unit =
    // 21 = the traded book's span; 72 = the S&P record used for calibration; the ends bracket them
    val horizons = Vector(21, 40, 72, 100)
    val focus = Vector("volatility-scaled, floor 40%", "trend 200d, floor 0%",
                       "volatility + trend 200d, floor 0%", "cut below -10%, floor 0%").map(ruleNamed)
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
      val ok    = gateChecks(measure(sims, L)).forall(_._2)
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
      val perWorld = sweepWorlds(base, single = false).map { (nm, w) => (nm, power(w, L, seed + 31L)) }
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
                      base: World): Unit =
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
      val ok   = gateChecks(measure(sims, years)).forall(_._2)
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
      val perWorld = sweepWorlds(base, single = false).map { (nm, w) => (nm, bufferStats(w)) }
      val passing  = perWorld.filter(_._2._1).map(_._2._2)
      println(f"  ${passing.size}%d of ${perWorld.size}%d worlds pass the gate")
      println(f"  ${"arm"}%-28s ${"99th pct material stretch, yrs"}%32s   ${"share of time in a >10y stretch"}%s")
      println(f"  ${""}%-28s ${"min"}%10s ${"median"}%10s ${"max"}%10s   ${"min"}%9s ${"median"}%9s ${"max"}%9s")
      for j <- arms.indices do
        val q = passing.map(r => pctile(r(j)._1, 0.99)).sorted
        val t = passing.map(r => r(j)._2.filter(_ > 10.0).sum / pathYears * 100.0).sorted
        println(f"  ${arms(j)._1}%-28s ${q.head}%10.1f ${pctile(q, 0.5)}%10.1f ${q.last}%10.1f   " +
                f"${t.head}%8.1f%% ${pctile(t, 0.5)}%8.1f%% ${t.last}%8.1f%%")

  // ---- entry point ---------------------------------------------------------------------------
  def main(args: Array[String]): Unit =
    var paths = 200; var years = 100; var seed = 20260813L
    var emit = ""; var validate = false; var strategies = false; var single = false
    var fitnessOnly = false; var calibrateN = 0
    var powerReport = false; var bufferReport = false
    var cost = 0.0010
    // defaults = best of a 50-sample random search against the fitness loss, scored at 100-year
    // paths (train 3.43, holdout 3.44 — indistinguishable, so not seed-fit), lightly rounded
    var trendShare = 0.30; var depth = 12.0; var stress = 3.4; var beta = 3.0
    var volPersist = 0.99; var volOfVol = 0.028; var valuePull = 0.015
    var crowdName = "momentum"; var crowdImpact = 0.06; var panic = 0.0
    var duration = 13.5; var flight = 0.38; var inflProb = 0.20; var inflSize = 0.07
    var inflSpeed = 0.010; var rateSpeed = 3.0; var discount = 4.0; var margin = 0.0008
    eachArg(args.toSeq, usage) {
      case "-paths"      => paths = consumeNext.toInt
      case "-years"      => years = consumeNext.toInt
      case "-seed"       => seed = consumeNext.toLong
      case "-emit"       => emit = consumeNext
      case "-validate"   => validate = true
      case "-fitness"    => fitnessOnly = true
      case "-calibrate"  => calibrateN = consumeNext.toInt
      case "-strategies" => strategies = true
      case "-power"      => powerReport = true
      case "-buffer"     => bufferReport = true
      case "-single"     => single = true
      case "-cost"       => cost = consumeNext.toDouble
      case "-trendshare" => trendShare = consumeNext.toDouble
      case "-depth"      => depth = consumeNext.toDouble
      case "-stress"     => stress = consumeNext.toDouble
      case "-beta"       => beta = consumeNext.toDouble
      case "-volpersist" => volPersist = consumeNext.toDouble
      case "-volofvol"   => volOfVol = consumeNext.toDouble
      case "-value"      => valuePull = consumeNext.toDouble
      case "-crowd"      => crowdName = consumeNext
      case "-crowdimpact"=> crowdImpact = consumeNext.toDouble
      case "-panic"      => panic = consumeNext.toDouble
      case "-duration"   => duration = consumeNext.toDouble
      case "-flight"     => flight = consumeNext.toDouble
      case "-inflprob"   => inflProb = consumeNext.toDouble
      case "-inflsize"   => inflSize = consumeNext.toDouble
      case "-inflspeed"  => inflSpeed = consumeNext.toDouble
      case "-ratespeed"  => rateSpeed = consumeNext.toDouble
      case "-discount"   => discount = consumeNext.toDouble
      case "-margin"     => margin = consumeNext.toDouble
      case a             => usage(s"unrecognized arg [$a]")
    }
    val crowd = crowdName.toLowerCase match
      case "momentum"  => Crowd.Momentum
      case "volscaled" => Crowd.VolScaled
      case t if t.startsWith("trend") => Crowd.Trend(t.drop(5).toInt)
      case other => usage(s"unknown -crowd [$other]; use momentum, trendNNN, or volscaled")
    val w = World(trendShare, depth, stress, beta, drift = 0.100, fundVol = 0.13, rateMean = 0.042,
                  volPersist = volPersist, volOfVol = volOfVol, valuePull = valuePull,
                  crowd = crowd, crowdImpact = crowdImpact, panic = panic,
                  duration = duration, flight = flight, inflProb = inflProb, inflSize = inflSize,
                  inflSpeed = inflSpeed, rateSpeed = rateSpeed, discount = discount, margin = margin)

    if calibrateN > 0 then
      calibrate(calibrateN, w, seed)
      return
    if fitnessOnly then
      val st = measure(simPaths(w, 60, 80, seed), 80)
      val (loss, rows) = fitness(st)
      println(f"fitness loss $loss%.3f  (lower is better; includes 0.5 per failed gate check)")
      rows.foreach((n, m, t, term) => println(f"  $n%-22s model $m%8.2f   target $t%8.2f   term $term%6.3f"))
      gateChecks(st).filter(!_._2).foreach((n, _) => println(f"  FAILED GATE: $n%s  (+0.500)"))
      return
    if strategies then
      runStrategySweep(paths, years, seed, cost, single, w)
      return
    if powerReport then
      runPowerReport(paths, seed, cost, single, w)
      return
    if bufferReport then
      runBufferReport(paths, years, seed, cost, single, w)
      return

    eprintln(s"simulating $paths paths x $years years")
    val sims = simPaths(w, paths, years, seed)
    val st = measure(sims, years)

    if emit.nonEmpty then
      // an exported path can end up inside the real-data harnesses with no memory of where it came
      // from, so the gate verdict travels with the export — loudly, at export time
      if !gateChecks(st).forall(_._2) then
        eprintln("WARNING: this world FAILS the acceptance gate " +
                 gateChecks(st).filter(!_._2).map(_._1).mkString("[", ", ", "]") +
                 " — the emitted path is not market-like")
      val p = sims.head
      val start = UniDateTime.of(1900, 1, 2)
      // .ymd, never bare interpolation: UniDateTime.toString is isoString and would render
      // 1900-01-02T00:00.  The old spelling got the date-only form only because sb.append(anyRef)
      // reached LocalDate.toString -- a JDK shape nothing here pinned.
      val rows = Vector.tabulate(p.price.length) { i =>
        val d = start.plusDays((i * 365L) / DaysPerYear).ymd
        f"${d}\t${p.price(i)}%.6f\t${p.bond(i)}%.6f"
      }
      emit.asPath.writeLines(rows)
      eprintln(s"wrote path 0 (price, bond) to $emit (${p.price.length} sessions)")

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
    println(f"  bond refuge            vol ${st.bondVol * 100}%.1f%%   growth-crash ${pm(st.bondGrowth, 0, 1)}%s   infl-crash ${pm(st.bondInfl, 0, 1)}%s")
    println(f"  stock-bond correlation calm ${pm(st.corrCalm, 0, 2)}%s   inflation regime ${pm(st.corrInfl, 0, 2)}%s")
    println(f"  realized inflation     ${st.inflAnn}%.2f%%/yr median (deterministic from regime pressure; no draws consumed)")
    println(f"  binding diagnostics    trend share ${st.trendShare}%.2f (pinned ${st.trendPinned * 100}%.1f%%, " +
            f"target saturated ${st.targetSat * 100}%.1f%%)   bond spiral ${st.pctBondStress * 100}%.1f%% of sessions   " +
            f"clamped ${st.clampPct}%.3f%%")

    println()
    println("  fidelity against targets (S&P 1954-2026 equity; long-Treasury refuge):")
    FitTargets.foreach { (n, get, want, _) =>
      val got = get(st)
      val ratio = if want != 0 then got / want else Double.NaN
      val flag  = if ratio > 1.5 || ratio < 0.667 then "  <-- MISS" else ""
      println(f"     $n%-22s model ${got}%8.2f   real ${want}%8.2f   ratio ${ratio}%5.2f$flag%s")
    }

    if validate then
      val checks = gateChecks(st)
      println()
      println("  acceptance gate:")
      checks.foreach((n, ok) => println(f"     ${if ok then "PASS" else "FAIL"}%-5s $n%s"))
      if checks.exists(!_._2) then
        eprintln("acceptance gate FAILED — this world is not fit to compare strategies in")
        System.exit(1)

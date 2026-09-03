//#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
package uni.apps

//> using scala 3.7.2
//> using dep org.vastblue:uni_3:0.23.0

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
// SCOPE: daily kurtosis LEFT this bucket in 0.21.0.  It had been a recorded exclusion for four
// releases, parked as needing a slow valuation cycle; what it actually needed was a SECOND tail
// channel, and `jumpVar` is one.  The model now reads 1.00 of its CRSP-century anchor, and the
// clustering it was supposed to trade against improved with it (1.11 -> 1.03).  Tail-day
// magnitudes are readable here; tail-day DEPTH is a separate question, reported as a PERCENTILE
// rather than a ratio -- see `ExtremeTargets` -- and it is a MISS: over the century the record's
// worst decline is -84.1% and only ~1% of model centuries reach it, so the model's century-scale
// tail is too SHALLOW.  Meanwhile the ensemble MINIMUM is deeper than the record, because it is
// drawn from 20,000 market-years; a levered consumer must not read that as a worst case.
// Crash frequency left this bucket in 0.19.1: it is carried by market depth, not by the valuation
// cycle, and at 1.3x real it now sits near the sampling error of its own anchor (15 episodes in
// 72 years, sd ~3).
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
//   saturation tanh(m/0.12), crowd impact per unit of exposure TRADED | reallocation kAdapt 0.010,
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
  // 4 -> 5: `world.crowdImpact` is a different quantity.  It was price pressure per unit of exposure
  // HELD by the momentum crowd (and per unit TRADED by the other two, on a scale 13x larger); it is
  // now per unit TRADED, one rule for every crowd.  A reader that reconstructs a `World` from a
  // schema-4 sidecar and runs it here gets a different market with no error -- exactly what the
  // schema number exists to prevent.
  // 5 -> 6: each `fidelity` row gained `aggregation` and `horizonYears`, and `ratio` became
  // nullable, paired with a new `percentile`.  A schema-5 reader that treats `ratio` as always
  // present breaks loudly on the null rather than dividing two incomparable statistics in silence,
  // which is the whole reason the field is null and not a number.  `world` also gained the five
  // disaster dials; a reader that reconstructs a `World` from a schema-5 sidecar and runs it here
  // gets a market without the century-tail channel.
  // 6 -> 7: `world` gained the valuation cycle's four dials (`beliefShare`, `beliefYears`,
  // `capYears`, `capWindow`) and the asymmetry three (`leverage`, `downShock`, `jumpSkew` -- the
  // last a dialised constant, 0.4 in every prior release).  A reader that reconstructs a `World`
  // from a schema-6 sidecar and runs it here gets a market whose perceived fair value never
  // leaves the fundamental.
  // 7 -> 8: `world` gained the satellite leg's two dials (`satBeta`, `satIdio`), and the TSV a
  // `logSat` column -- present ONLY when `satBeta > 0`, the NATURAL LOG of the satellite price.
  // Log, not a level, deliberately: a level near 1e6 rendered at %.6f puts the twins' 1-ulp
  // transcendental latitude (PARITY.md §6) within reach of a rounding tie -- measured at ~100
  // cross-language print flips per 40 century paths -- where the log sits nine orders under the
  // printed digit.  A reader that reconstructs a `World` from a schema-7 sidecar loses nothing:
  // the dials were 0 in every world such a sidecar could describe.
  // 8 -> 9: `gate` gained `anchors` (which ruler graded this world -- a `-anchors nasdaq` run
  // was otherwise indistinguishable from an S&P one in its own provenance record) plus
  // `gradedSeries` and `ungradedChannelSeries`, which say in the artifact itself that the verdict is
  // about `price` and `bond` and NOT about any emitted channel column.  `world` gained the bar
  // channels' dials (`rangeScale`, `rangeDown`, `volIdio`), and the TSV the
  // columns `logHigh`/`logLow` (present ONLY when `rangeScale > 0` -- log prices of the sampled
  // intra-bar extremes; the bar's open is the prior close, the model has no overnight) and
  // `logVolume` (present ONLY when `volIdio > 0` -- a mean-free log turnover index; apply your
  // own detrend convention as you would to a real series).  Log columns for the same tie reason
  // as `logSat`.  A bars-off schema-9 file is byte-identical to its schema-8 counterpart except
  // the schema number and the two new (zero) world fields.
  // 9 -> 10: the gate GRADES the channel columns (fifteen `satellite *` / `bar *` rows, present
  // exactly when their channel ran), so `gradedSeries` lists `logSat`, `logHigh`/`logLow` and
  // `logVolume` whenever those rows exist and `ungradedChannelSeries` is empty by construction
  // -- a schema-9 reader that took `gradedSeries` as fixed at `["price", "bond"]` misreports the
  // verdict's scope.  Two `world` dials changed MEANING: `satIdio` is the leg's idio sd as a
  // FRACTION of the primary's realized volatility (was an absolute per-year sd at unit
  // vol-state; anchored 0.074 -> 0.77) and `rangeScale` multiplies the session scale re-levelled
  // onto the world's realized volatility (was the diffusion scale alone; anchored 1.1 -> 0.63).
  // A reader that reconstructs a `World` from a schema-9 sidecar with either dial on and runs it
  // here gets a different leg or bar with no error -- the `crowdImpact` case again.  A top-level
  // `channels` block carries the readings those rows grade (`satellite`, `barRange`,
  // `barVolume`, each present exactly when its channel ran, led by the world `level` they were
  // sampled at), so a channel FAIL can be sized from the file alone -- `fidelityFailed` names a
  // band, not a value.
  // 10 -> 11: the dividend stream.  `world` gained `divYield` (the world's mean yield); the TSV
  // gained `logTraded` (present ONLY when `divYield > 0` -- the natural log of the traded price,
  // the total-return `price` deflated by the yield accrued each session, so `price` keeps its
  // meaning) and `divYield` (the session yield in %/yr, a level: nothing here is near the tie
  // magnitude); `channels.level` gained `kDiv`, the world's mean fundamental/price the yield was
  // normalized by, and `channels.dividend` the mean yield read.  A dividends-off schema-11 file
  // is byte-identical to its schema-10 counterpart except the schema number and one zero world
  // field.
  val EmitSchema: Int = 11

  val EmitSidecarKeys: Vector[String] =
    Vector("generator", "version", "schema", "file", "columns", "header", "path", "world",
           "gate", "channels", "fidelity")

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
    "-emitfrom K   ; with -emitall, write indices K..K+paths-1 rather than 0..paths-1, so one",
    "              ;   batch can be split across invocations without repeating a path.  Padding",
    "              ;   follows the highest index written, so chunks either side of 1000 differ in",
    "              ;   width -- sort numerically, or emit the batch in one invocation",
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
    "-atrelease V  ; seed every dial from release V's frozen world (the -releases rows, or the",
    "              ;   current version) so a pinned consumer takes binary fixes without taking a",
    "              ;   recalibration; explicit dial flags override it wherever they appear.  Or a",
    "              ;   named recipe, which also selects its -anchors: 0.23.0-nasdaq is the",
    "              ;   channel-emitting Nasdaq world at the anchored dials.  The",
    "              ;   gate still grades with the CURRENT rulers — a world predating a mechanism",
    "              ;   fails that mechanism's rows honestly; pair with -gate realism to require",
    "              ;   only what it claims, and read the rest as disclosure",
    "-fitness      ; print the scalar calibration loss and its components, then exit",
    "              ;   (scores the frozen 60x80 ensemble, plus 60 single histories at each",
    "              ;   extreme anchor's own horizon; refuses -paths/-years)",
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
    "-ddshape      ; drawdown SHAPE against the anchor set's references (CRSP century + SPY;",
    "              ;   NDX + QQQ): decline and recovery duration, and how much of a decline",
    "              ;   arrives in its single worst session.  Diagnostic, never gated",
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
    s"-jumpvar X    ; share of the equity shock's VARIANCE carried by jumps rather than diffusion",
    s"              ;   (default ${Defaults.jumpVar}; 0 turns the channel off, consuming no draws, so",
    "              ;   nothing else in the path moves)",
    s"-jumprate X   ; jumps per session at average volatility (default ${Defaults.jumpRate}); with",
    s"-leverage X   ; the leverage effect: a decline raises the NEXT session's diffusive",
    s"              ;   volatility by exp(X * declines-in-sds), saturated at 4 sds; an equal rally",
    s"              ;   raises nothing (default ${Defaults.leverage}; 0 off, consuming no draws)",
    s"-downshock X  ; sign-dependent news response: a negative equity shock is scaled by (1+X),",
    s"              ;   a positive one by 1/(1+X) -- contemporaneous downside dispersion.  Costs",
    s"              ;   vr60 about +0.02 per 0.01 (default ${Defaults.downShock}; 0 off)",
    s"-jumpskew X   ; how far each jump is shifted down, in units of its own sd; variance-",
    s"              ;   normalised, so deeper skew means smaller jumps, not fatter tails",
    s"              ;   (default ${Defaults.jumpSkew}, the constant every release before this compiled in)",
    s"              ;   -jumpvar it sets the SIZE -- rarer jumps of the same variance are bigger",
    s"-newsrate X   ; fair-value news jumps per year: permanent down-jumps the price reprices the",
    s"              ;   SAME session, gap-invariant -- the downside-asymmetry channel, whose",
    s"              ;   variance DISPLACES diffusive noise rather than stacking on it",
    s"              ;   (default ${Defaults.newsRate}; 0 off, consuming no draws)",
    s"-newssize X   ; log decline per news event (default ${Defaults.newsSize}; 0.033 = a -3.3% day).",
    "              ;   Rarer-larger events buy more asymmetry and kurtosis per unit of variance",
    "              ;   With -newsrate R, needs R*X^2 < 0.0123 (X below 0.097 at the default rate):",
    "              ;   past it there is no diffusion left to displace, and the run is refused",
    "-satbeta X    ; SATELLITE EQUITY LEG: a second, higher-beta market (the Nasdaq to the",
    "              ;   default world's S&P) whose return is X times the primary's observed",
    "              ;   return plus idio noise on the primary's own vol state.  When on, -emit",
    "              ;   adds a logSat column (NATURAL LOG of the leg's price).  Default 0 = off,",
    "              ;   consuming no draws; anchored 1.2 on SPY-QQQ 1999-2026",
    "-satidio X    ; the leg's idiosyncratic vol as a FRACTION of the primary's realized vol",
    "              ;   (anchored 0.77 = corr 0.853 on SPY-QQQ; holds at any world's volatility)",
    "-jointemit P  ; dev tap: per-path logPrice/logSat TSVs (no sidecar) for grading the leg",
    "              ;   against test-data/equity-anchors/joint-coupling-2026-08-31.tsv",
    "-rangescale X ; INTRA-BAR RANGE: high/low sampled per session from the exact Brownian-",
    "              ;   bridge extremes at the session's own vol state re-levelled onto the",
    "              ;   world's realized volatility, times X — the one disclosed identification",
    "              ;   dial (anchored 0.63 on SPY/QQQ OHLCV; holds across worlds).  Default",
    "              ;   0 = off, consuming no draws",
    "-rangedown X  ; same-session sign<->vol coupling on the bar: down sessions get (1+X) the",
    "              ;   bridge sigma, up sessions 1/(1+X) — anchored 0.09, landing BOTH channels'",
    "              ;   down/up asymmetry on the intraday rulers.  Requires -rangescale > 0",
    "-volidio X    ; VOLUME: log turnover index riding the range — elasticity 0.59 to the",
    "              ;   range's deviation from its slow normal plus a two-component persistent",
    "              ;   idio whose TOTAL sd is X (anchored 0.34).  Requires -rangescale > 0.",
    "              ;   Default 0 = off, consuming no draws",
    "-divyield Y   ; DIVIDENDS: the world's mean yield, %/yr; the session yield is Y x",
    "              ;   fundamental/price over the world's mean of it, and -emit gains logTraded",
    "              ;   (the total-return price deflated by the accrued yield; price itself is",
    "              ;   unchanged) and divYield.",
    "              ;   Anchored 2.95 (S&P) / 0.78 (Nasdaq); an identity parameter.  Default 0 = off",
    "-barsemit P   ; dev tap: per-path logPrice/logHigh/logLow[/logVolume] TSVs for grading",
    "              ;   the bar channels against the bars anchors",
    s"-refugedays X ; half-life in sessions of the settled stress the refuge bid reads, which",
    s"              ;   excludes the current session -- kills the same-day stock-bond coupling the",
    s"              ;   tail hedge corr row grades while the crisis rally keeps the stress LEVEL",
    s"              ;   (default ${Defaults.refugeDays}; 0 reads live stress; the rally dies near 10)",
    s"-value X      ; pull toward equity fair value, per day (default ${Defaults.valuePull}).  With",
    s"              ;   the recovery drag below this governs SHALLOW water; deep drawdowns are set",
    s"              ;   by the drag instead",
    s"-anchors A    ; which real index the fidelity targets describe: sp500 or nasdaq",
    s"              ;   (default sp500).  Only the EQUITY rows move; the bond targets and the",
    s"              ;   depth rungs are asset-independent",
    s"-recoverydrag X ; how fast value arbitrage weakens as a drawdown deepens past 10%",
    s"              ;   (default ${Defaults.recoveryDrag}; 0 restores the symmetric pull 0.20.0 had)",
    s"-recoveryfloor X ; weakest that pull may become, as a share of full strength",
    s"              ;   (default ${Defaults.recoveryFloor}; 1.0 with -recoverydrag 0 is the",
    "              ;   symmetric pull, not the 0.20.0 world -- price formation moved in 0.22.0)",
    s"-disasterrate X ; macro disasters per century: rare multi-year collapses of the real",
    s"              ;   fundamental, the channel that carries the century-scale tail (default",
    s"              ;   ${Defaults.disasterRate}; 0 turns it off, consuming no draws, so nothing else moves)",
    s"-disastersize X ; total log decline of the fundamental per disaster (default ${Defaults.disasterSize})",
    s"-disasterlen Y ; years from onset to trough (default ${Defaults.disasterLen})",
    s"-disasterrecover X ; share of the decline that reverses after the trough (default",
    s"              ;   ${Defaults.disasterRecover}; the rest is permanent)",
    s"-disasterreclen Y ; years that recovery is spread over (default ${Defaults.disasterRecLen})",
    s"-beliefshare X ; the slow valuation cycle: how far PERCEIVED fair value drifts toward",
    s"              ;   realized prices (default ${Defaults.beliefShare}; 0 pins perception to the",
    s"              ;   fundamental, bit for bit; must stay below 1 or nothing anchors the price)",
    s"-beliefyears Y ; half-life of that belief adaptation (default ${Defaults.beliefYears})",
    s"-capyears X   ; years of the fundamental's recent EXCESS growth beliefs capitalize into",
    s"              ;   perceived fair -- the mania half of the cycle (default ${Defaults.capYears}; 0 off)",
    s"-capwindow Y  ; years of EWMA that growth is read through (default ${Defaults.capWindow})",
    s"-haltlimit X  ; equity trading halt: largest ONE-session decline the market prints, as a",
    s"              ;   simple fraction, with the unfilled pressure DEFERRED to the next session.",
    s"              ;   0.20 is the US Level 3 breaker, which closes the day at -20%.  0 disables",
    s"              ;   the mechanism and leaves the bare numerical guard (default ${Defaults.haltLimit})",
    "-crowd K      ; momentum (default), trendNNN, volscaled, or drawdownNN — all but the first",
    "              ;   run the RULE UNDER TEST, closing the reflexive loop",
    s"-crowdimpact X; price pressure per unit of exposure the crowd TRADES in a session (default",
    s"              ;   ${Defaults.crowdImpact}); one rule for every crowd, so the number means the same",
    "              ;   thing whichever -crowd is running",
    s"-panic X      ; stress-accelerated capital reallocation (default ${Defaults.panic} = symmetric flows)",
    s"-drift X      ; fundamental drift per year; no dividend, so this IS total return (default",
    s"              ;   ${Defaults.drift})",
    s"-fundvol X    ; fundamental volatility per year (default ${Defaults.fundVol}).  Sets time under",
    "              ;   water almost independently of measured volatility — the value channel",
    "              ;   passes only a few percent of a fundamental move into any one session, so",
    "              ;   this accumulates into drawdown depth without moving daily return scale",
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
    /** exposure keyed to distance from the running peak -- folio's CDAP family as a crowd, so
      * "does a drawdown rule survive a crowd running a drawdown rule" is finally posable.  The
      * parameter is the cut threshold in PERCENT below the peak (drawdown10 = de-risk past
      * -10%), reading `px(i-1)` alone like the other banded crowds. */
    case Drawdown(pct: Int)

  final case class World(
    trendShare: Double, depth: Double, stress: Double, beta: Double,
    drift: Double,      // fundamental drift per year; no dividend, so this IS total return
    fundVol: Double, rateMean: Double, volPersist: Double, volOfVol: Double,
    jumpVar: Double,    // share of the equity flow's VARIANCE carried by jumps rather than
                        // diffusion.  0 disables the channel and reproduces pre-0.21 behaviour
                        // byte for byte -- the draws come from their own stream, so nothing else
                        // in the path shifts.
    jumpRate: Double,   // unconditional jump intensity per session.  With jumpVar it fixes the
                        // size: rarer jumps of the same total variance are larger ones.
    leverage: Double = 0.0,   // THE LEVERAGE EFFECT: how hard a decline raises the NEXT session's
                              // diffusive volatility, where an equal rally raises nothing --
                              // EGARCH's signed term, fed by the same decline signal the spiral's
                              // stressIdx reads (max(-ret,0)/scale, centred at 0.399, SATURATED
                              // at 4 realized sds), applied as a transient one-session multiplier
                              // on the noise, never into the persistent logVol (fed there it
                              // self-excites: vol 16% -> 45% at the first anchor-reaching
                              // setting, measured).  Consumes no draws; 0 is bit-identical off.
    downShock: Double = 0.0,  // SIGN-DEPENDENT NEWS RESPONSE, the contemporaneous half of the
                              // asymmetry pair: the equity news term is scaled by (1 + downShock)
                              // when negative, its reciprocal when positive.  Applied to the
                              // SHOCK only, never the crowd's flows.  Pays vr60 ~+0.02 per 0.01
                              // -- an amplified TRANSITORY down-shock must be arbitraged back and
                              // the recovery IS trend -- which is what bounds it near 0.03.
                              // Consumes no draws; 0 is bit-identical off.
    newsRate: Double = 0.0,   // FAIR-VALUE NEWS JUMPS, events per YEAR (contrast disasterRate,
                              // per century): rare permanent DOWN-jumps of the fundamental that
                              // the price reprices the SAME session, gap-invariant -- logVbase
                              // and logP drop together, so the value channel, the belief EWMA and
                              // the mispricing all see nothing and there is no rebound to
                              // arbitrage back: a pure random-walk step, which is what lets this
                              // channel move DOWNSIDE variance without the vr60 tax that bounds
                              // `downShock` (measured leak ~+0.02 at full effect vs ~+0.10 by the
                              // transitory route).  Its variance DISPLACES diffusive noise
                              // (see `newsDamp`) instead of stacking on top, the jumpVar budget
                              // rule.  Draws from a dedicated stream; 0 is bit-identical off.
    newsSize: Double = 0.0,   // log decline per news event (positive; 0.033 = a -3.3% day).
                              // Deterministic size -- rarer-larger events buy more asymmetry and
                              // more kurtosis per unit of variance than frequent-small ones
                              // (measured 1.5x0.04 vs 6x0.02: dx 3.4 vs 1.3 at equal variance).
                              // Bounded with newsRate by the diffusion budget it displaces --
                              // newsRate*newsSize^2 < 252*SigmaN^2 (0.0123; size below 0.097 at
                              // the default rate) -- and refused at the CLI past it.
                              // The drift cost newsRate*newsSize is returned deterministically on
                              // BOTH legs, so the dial does not move expected return.
    jumpSkew: Double = 0.4,   // how far each jump is shifted DOWN, in units of its own sd -- a
                              // dialised constant (0.4 in every release since jumps arrived), so
                              // 0.4 is this dial's off-position, not 0.  Variance-normalised in
                              // `jumpScale`, so a deeper skew makes each jump smaller rather than
                              // the tail heavier -- which is also why it is a WEAK skew lever
                              // (0.4 -> 1.4 moved the downside excess by +1.5, measured).
    valuePull: Double,
    recoveryDrag: Double,  // how fast value arbitrage WEAKENS as the drawdown deepens.  0 is the
                           // symmetric pull every release before 0.21.0 had, bit for bit.
    recoveryFloor: Double, // the residual arbitrage that never goes away, as a share of full
                           // strength.  1.0 with drag 0 is the old behaviour exactly.
    beliefShare: Double = 0.0,   // THE SLOW VALUATION CYCLE: how far the market's PERCEIVED fair
                                 // value drifts toward realized prices.  Value capital arbs the
                                 // gap to what it BELIEVES fair is, and after years of elevated
                                 // prices it believes them ("this time is different"); after years
                                 // depressed, the pessimism is as sticky.  Splits reversion by
                                 // FREQUENCY: daily pull unchanged (beliefs barely move in 60
                                 // sessions, so the variance-ratio band is untouched), multi-year
                                 // reversion weakened to (1 - beliefShare) of the pull -- which is
                                 // where CAPE-scale valuation swings live.  Consumes no draws;
                                 // 0 is bit-identical off.
    beliefYears: Double = 2.5,   // half-life of belief adaptation, in years
    capWindow: Double = 6.0,     // years of EWMA through which beliefs read that growth: the
                                 // narrative horizon.  Short windows pass fundVol noise into the
                                 // term capYears-fold (at 1y, vr60 read 2.3-5.2, measured) --
                                 // the window must sit between the noise and the ~6-year regime.
    capYears: Double = 0.0,      // THE MANIA HALF of the cycle: how many years of the fundamental's
                                 // RECENT excess growth beliefs capitalize into perceived fair
                                 // value -- "this growth is the new normal", priced.  The
                                 // fundamental's drift regime (`driftNow`, redrawn every 1-11
                                 // years) is what beliefs extrapolate, so booms carry perceived
                                 // fair -- and the price that arbs toward it -- above the true
                                 // fundamental, and a regime ending on a re-draw is a valuation
                                 // crash with the fundamental FINE: the 2000 shape.  Growth is
                                 // read through a one-year EWMA (`CapEwmaYears`); 0 is off, bit
                                 // for bit, no draws consumed.
    crowd: Crowd, crowdImpact: Double, panic: Double,
    haltLimit: Double = 0.0,  // equity trading halt: the largest ONE-session decline the market
                              // will print, as a simple fraction, with the unfilled pressure
                              // deferred to the next session.  0 disables it, which is what the
                              // frozen release rows below inherit -- correctly, since no release
                              // before this one had the mechanism.
    disasterRate: Double = 0.0,  // macro disasters per CENTURY: rare multi-year collapses of the
                                 // real fundamental (1929-32, not 1987) -- the Barro-Rietz
                                 // channel.  Rare is what lets it deepen the century-scale tail
                                 // without touching daily volatility or the 60d variance ratio,
                                 // which fence off every CONTINUOUS extra-variance channel.
                                 // 0 disables it; draws come from their own stream, so the frozen
                                 // release rows inherit pre-disaster behaviour bit for bit.
                                 // SCOPE: it shifts deep crashes toward FUNDAMENTAL-led (>35%
                                 // crashes: 31% -> 40% on the -strategies classifier; the rest
                                 // stay spiral dislocations), and every deep crash still starts
                                 // from a peak AT fair value (p/f 0.96-1.19 measured).  The model
                                 // has no mania channel, so the 1929/2000 shape -- a collapse
                                 // from a peak far ABOVE fair value, multiples doing the falling
                                 // -- cannot occur.  Price-path statistics cannot tell; anything
                                 // reading the emitted `fundamental` column or `-strategies`'
                                 // crash-type conditioning can.
    disasterSize: Double = 2.0,  // total log decline of the fundamental per disaster
    disasterLen: Double = 2.5,   // years from onset to trough; the decline is spread evenly
    disasterRecover: Double = 0.5, // share of the decline that REVERSES after the trough --
                                   // Barro's cross-country estimate is about half.  Without it a
                                   // disaster century spends decades >20% underwater and the deep
                                   // depth rung runs far past even the real 1929 century's share.
    disasterRecLen: Double = 4.0,  // years the recovery is spread over

    duration: Double,   // bond duration: sensitivity of its fair value to the rate
    easing: Double,     // CAP on policy accommodation under equity stress, in rate points
    unwind: Double,     // how fast that accommodation is withdrawn, per year
    refuge: Double,     // flight-to-quality bid into the bond, per unit of equity stress
    refugeDays: Double = 0.0, // BOND DECOUPLING: half-life in SESSIONS of the settled-stress EWMA
                              // the refuge bid reads, which EXCLUDES the current session --
                              // flight-to-quality follows the stress investors went home with,
                              // not the move printing right now.  The calm-day stock-bond
                              // correlation the `tail hedge corr` row grades is carried almost
                              // entirely by the SAME-session stress delta, while the anchored
                              // crisis behaviour (growth-crash rally) rides the stress LEVEL,
                              // which a short lag keeps: at 1 the calm-day corr falls -0.50 ->
                              // -0.23 with the rally intact, and by 10 the rally dies (measured;
                              // the mechanism gate fails there).  `margin` keeps reading live
                              // stress: a margin call does not wait overnight.  Draw-free;
                              // 0 reads live stress, bit-identical off.
    inflProb: Double, inflSize: Double, inflSpeed: Double, rateSpeed: Double,
    discount: Double,   // equity fair-value markdown per pp of rate above its long-run mean
    margin: Double,     // joint-stress forced selling pressure on the bond
    satBeta: Double = 0.0, // SATELLITE EQUITY LEG (prototype): a second, higher-beta equity market
                           // -- the Nasdaq to the default world's S&P -- derived from the primary
                           // leg rather than agent-simulated.  Its session return is `satBeta`
                           // times the primary's OBSERVED log return (markdown and news included
                           // -- they are shared factors) plus an idiosyncratic term whose
                           // volatility rides the SAME vol state as the primary's diffusion.  That
                           // state-sharing is the measured constraint, not a convenience: SPY-QQQ
                           // correlation is state-FLAT (0.853 calm vs 0.852 stressed) BECAUSE
                           // idiosyncratic vol triples with the shared state (7.7 -> 23.7%/yr);
                           // constant idio noise would manufacture a stress-correlation kick the
                           // record does not have.  Draws come from a dedicated stream, read only
                           // when `satBeta > 0`, so 0 is bit-identical off.  Anchors (SPY/QQQ
                           // 1999-2026): beta 1.20, corr 0.853, rolling-252d beta p5/med/p95
                           // 0.90/1.18/1.92.
    satIdio: Double = 0.0, // idiosyncratic volatility of the satellite leg as a FRACTION of the
                           // primary's own realized volatility, riding the primary's vol state
                           // (`deriveChannels`).  Dimensionless so the anchored coupling transports: an
                           // absolute per-year sd read relatively smaller at a higher-vol primary,
                           // and stacked on the Nasdaq recipe the leg's correlation climbed to
                           // 0.93 against the anchored 0.85 with nothing to catch it.
    rangeScale: Double = 0.0, // INTRA-BAR RANGE (prototype): high/low sampled per session from
                           // the EXACT Brownian-bridge extreme distributions, endpoints at the
                           // observed open (= prior close; no overnight) and close, diffusion
                           // scale the session's OWN noise sd -- news damp, vol state, leverage
                           // kick, jump mixing, spiral amplification, all as the price itself
                           // received them.  The range therefore scales with the session's
                           // noise, NOT with |return|: the record's corr(lnH/L, |r|) is only
                           // 0.70-0.72, and a bar derived as a multiple of |r| is detectably
                           // fake (`bars_anchor` conventions, SPY/QQQ OHLCV).  This dial is the
                           // one disclosed free parameter: a multiplier on that session scale
                           // AFTER it is re-levelled onto the world's realized close-to-close
                           // volatility (`deriveChannels`), so it is a bar-to-ccvol ratio that holds
                           // across worlds -- on the diffusion scale alone the same dial read
                           // 1.115 at the default and 1.264 at the Nasdaq recipe, because the
                           // share of variance the diffusion carries is a property of the world.
                           // Absorbs the real intraday's sub-BM compression plus the model's
                           // session/day identification.  Two draws per session from a dedicated
                           // stream, read only when > 0, so 0 is bit-identical off.
    rangeDown: Double = 0.0, // SAME-SESSION SIGN<->VOL COUPLING for the bar: a down session
                           // gets more intraday breadth per unit of net move -- the bridge
                           // sigma is multiplied by (1 + rangeDown) when the session's return
                           // is negative and divided by it otherwise, the `downShock` shape.
                           // The record's down/up range ratio at the intraday-sign ruler is
                           // 1.11-1.14 where the model's cross-session channels deliver only
                           // 1.03; this is the conditional-distribution statement that closes
                           // it (the realized sign informs the day's breadth -- no feedback
                           // into the price).  ONE root serving two channels: volume's down-up
                           // gap rides this through VolSlope with no volume-side change, and
                           // at the anchored 0.09 BOTH land on the intraday rulers (range
                           // 1.125 vs 1.109-1.142, volume gap 0.096 vs 0.094-0.098).
                           // Draw-free; 0 is bit-identical off.
    volIdio: Double = 0.0, // VOLUME (prototype): a log turnover index riding the RANGE -- the
                           // record says volume follows the day's travel, not its net move
                           // (corr with lnH/L 0.54-0.55 vs 0.40-0.44 with |r|), with elasticity
                           // ~0.6 to the range's deviation from its slow normal and a PERSISTENT
                           // residual (acf5/acf1 = 0.84: a slow component near AR-0.97 carrying
                           // ~55% of residual variance, plus white noise).  Structural constants
                           // frozen from the SPY/QQQ regression (VolSlope 0.59, VolDown 0.045
                           // from the residual's +0.036 down-up, VolPhi 0.97, slow share 0.55);
                           // this dial is the TOTAL idiosyncratic sd, the one anchored free
                           // parameter (record residual sd 0.29-0.38).  Requires the range
                           // channel -- volume without a range to ride is refused at the CLI.
                           // Two draws per session from a dedicated stream, read only when > 0,
                           // so 0 is bit-identical off.
    divYield: Double = 0.0 // DIVIDENDS: the world's MEAN dividend yield, %/yr.  The session
                           // yield is divYield x (fundamental/price) / the world's mean
                           // fundamental/price (`worldLevel`'s kDiv -- a world constant: the
                           // ensemble's mean gap sits well below fair, 2.1x at the default and
                           // 2.3x on the Nasdaq recipe, and a per-path mean would leak the
                           // path's future), so a rich session yields less, as the record's
                           // does, and the ensemble's pooled mean yield is the dial -- the
                           // reported median path's mean reads ~0.9x of it (2.62 at 2.95),
                           // valuation epochs skewing the path means; the traded price is the
                           // total-return index (`price`,
                           // unchanged) deflated by the yield accrued each session -- decisions
                           // read the traded series and accounting the adjusted one, the
                           // two-series convention a consumer enforces and could test only on
                           // real data.  Derived after the price loop, draw-free, reaching no
                           // price; 0 = off, no columns, bit-identical.  Anchored 2.95 (Shiller
                           // D/P 1954-2023) for the S&P set and 0.78 (QQQ 2005-2026) for the
                           // Nasdaq set -- `dividend-2026-09-02.tsv`; an identity parameter,
                           // never searched.  The record's yield also moved with the payout
                           // level across eras, which this does not model.
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
                        eqFloorDays: Int,        // EQUITY sessions held off the downward guard,
                                                 // post-burn-in; the equity leg alone because that
                                                 // is the series a tail consumer reads
                        eqTailDays: Int,         // equity sessions past `TailRef`, the denominator
                        eqHaltDays: Int,         // equity sessions the trading halt bound, the
                                                 // BINDING diagnostic for that mechanism
                        meanBondStress: Double,  // BINDING diagnostic for the bond spiral
                        pctBondStress: Double,   // share of sessions bond stress index > 0.5
                        duration: Double,        // the world's bond duration, carried so the gate can
                                                 // judge bond volatility RELATIVE to it; a fixed
                                                 // absolute band can only ever fit one bond
                        meanCrowdFlow: Double,   // BINDING diagnostic for the reflexive channel:
                                                 // mean |crowd flow| per session, post burn-in.
                                                 // Its ABSENCE is why -crowdimpact sat dead in the
                                                 // default world across four releases.
                        disasters: Int,          // BINDING diagnostic for the disaster channel:
                                                 // collapses begun post burn-in on this path.
                        sat: Array[Double],      // satellite equity leg price (empty when
                                                 // `satBeta` is 0)
                        logHi: Array[Double],    // intra-bar LOG high/low (empty when
                        logLo: Array[Double],    // `rangeScale` is 0).  Log, not a level,
                                                 // unlike price/sat: born in log space and
                                                 // emitted in log space; a level round-trip
                                                 // would only add transcendental noise
                        logVolume: Array[Double],// log turnover index (empty when `volIdio`
                                                 // is 0); mean-free by construction, the
                                                 // consumer's detrend convention applies
                        divYield: Array[Double] = Array.emptyDoubleArray, // session yield, %/yr,
                                                 // and the traded price LEVEL (both empty when
                        traded: Array[Double] = Array.emptyDoubleArray,   // `divYield` is 0);
                                                 // emitted as a log, like `sat`
                        chanK: Double = 0.0,     // the world's channel level the bars and the
                        chanKSat: Double = 0.0,  // satellite were sampled at (`worldLevel`),
                                                 // carried into the sidecar so the emitted data's
                                                 // scale is auditable; 0 / 0 when both are off
                        chanKDiv: Double = 0.0)  // the world's mean fundamental/price the
                                                 // dividend yield was normalized by; 0 when off

  /** THE shipped world.  `main` seeds its mutable CLI vars from this and `usage` interpolates its
    * numbers, so every default is written in exactly one place.  Help text that restates a constant
    * is a second copy of it — the failure class PARITY.md documents — and this one had already gone
    * wrong three times before it was centralised.  A mismatch between the twins is caught directly
    * by the `-emit` sidecar, which names every field: bare `-emit` writes THIS world. */
  val Defaults = World(
    trendShare = 0.055, depth = 17.4, stress = 5.15, beta = 3.0, drift = 0.122, fundVol = 0.070,
    rateMean = 0.042, volPersist = 0.992, volOfVol = 0.022,
    jumpVar = 0.14, jumpRate = 0.0035, leverage = 0.12, downShock = 0.0, jumpSkew = 0.7,
    // The asymmetry adoption, 0.23.0: the leverage kick (0.12, news-coupled), fair-value news
    // jumps (1.3/yr x -3.3%, variance-displacing) with the transitory downShock retired at 0,
    // jumpSkew 0.7 with the jump channel rarer-larger (0.14 var at 0.0035), and the refuge bid
    // reading settled stress (refugeDays 1, refuge 0.115; easing re-solved to 0.052, the BOTTOM
    // of the real easing-cycle range, so its anchor holds — and the -crossasset short-duration
    // rung sits back above its floor).  Verified at 200x100 on
    // four seeds: downside vol excess +3.05 vs the record's +3.06, leverage corr -0.089 vs
    // -0.0926, calm-day tail hedge -0.24 vs -0.273, bond growth-crash 6.9 vs 6.6, with the
    // seed-7 vr60 failure unchanged from the prior world.  stress/volOfVol/volPersist/valuePull/
    // recoveryDrag/drift re-tuned to hold the rest; the two rows that give ground are clustering
    // lag 20 (0.214 -> 0.197 vs anchor 0.225) and valuation dispersion (0.230 -> 0.215 vs target
    // 0.30), disclosed in the CHANGELOG.
    newsRate = 1.3, newsSize = 0.033, refugeDays = 1.0,
    valuePull = 0.056,
    recoveryDrag = 8.5, recoveryFloor = 0.10, haltLimit = 0.25,
    // The disaster channel, ADOPTED 0.22.1: rate 0.6/century, total log decline 2.0 over 2.5
    // years, half reversing over 4.  Chosen on the tail loss term at 60 histories and verified at
    // 200x100 on four seeds (all three gate classes PASS; the record's century-worst moves from
    // the 1st percentile of model centuries to the 16-23rd).  `drift` 0.113 -> 0.118 compensates
    // the expected-return cost of the unreversed half (~0.6%/yr), putting return per vol back on
    // its anchor (0.71 vs 0.69).
    disasterRate = 0.6, disasterSize = 2.0, disasterLen = 2.5,
    disasterRecover = 0.5, disasterRecLen = 4.0,
    // The slow valuation cycle, ADOPTED 0.23.0 and RETUNED against the mania anchors
    // (`mania_anchor` conventions, Shiller 1881-2023): gap-beliefs at share 0.95 with a 1.5y
    // half-life carry the dispersion, growth-capitalization at 1.5 years read through a 6-year
    // window carries the upper wing, and `drift` 0.118 -> 0.120 compensates the cycle's return
    // cost.  SHORTER belief half-life and HIGHER share are the amplitude dials — the sweep
    // INVERTED the naive direction (12y reads dispersion 0.115; 1.5y reads 0.26 at share 0.9) —
    // and share is the cheap currency: years is what pays the depth rungs (0.5y fails d10
    // outright).  At 1.5/0.95 the per-path cycle reads sd 0.33, half-life 7.9y, both wings ~23%
    // past 0.25 log (record: 0.415, 11.5y, ~27.5%) — roughly half the record's cycle, from a
    // fifth of it — with dispersion 0.33, vr60 1.12, frozen loss 0.955 -> 0.820, four-seed
    // pattern unchanged (seed-7 vr60-only, 1.15 from 1.17), and -crossasset PASS with no easing
    // re-solve.  Priced: d10 1.34 -> 1.39, d20 2.79 -> 2.90.  The record's 5y autocorrelation
    // (0.55) stays out of reach from ABOVE (model 0.84 at every setting): the model cycle is
    // more regular than the record's — disclosed, not anchored.
    beliefShare = 0.95, beliefYears = 1.5, capYears = 1.5, capWindow = 6.0,
    crowd = Crowd.Momentum, crowdImpact = 0.030, panic = 0.0, duration = 13.5,
    easing = 0.052, unwind = 0.35, refuge = 0.115,
    inflProb = 0.20, inflSize = 0.10, inflSpeed = 0.010, rateSpeed = 3.0, discount = 5.73,
    margin = 0.006)
  val DefaultPaths = 200
  val DefaultYears = 100
  val DefaultSeed = 20260813L

  /** Frozen structural constants of the volume channel -- see the `volIdio` field.  Measured
    * from the SPY/QQQ volume-on-range regression (`bars-2026-09-01.tsv`, whose rows
    * `BarsAnchorSuite` asserts these against): elasticity of detrended log volume to the
    * range's log-deviation from its slow normal; the down-day loading calibrated to the
    * RESIDUAL +0.036 down-up; the slow idio component's persistence and variance share
    * (acf5/acf1 = 0.84 rules out a single AR(1)). */
  val VolSlope = 0.51
  /** Yesterday's range, still moving today's volume: participation decays over days rather than
    * resetting with the bar.  Identified as the second term of a distributed lag (`v_t ~ rx_t +
    * rx_{t-1}`, which drops the contemporaneous slope 0.59 -> 0.51 because the single-lag fit
    * absorbed this through the range's own autocorrelation; lag 2 adds 0.04 and is dropped).
    * WITHOUT it the volume residual is independent of the range by construction, the record's
    * cross-term corr(rx_t, resid_{t+1}) = +0.14/+0.19 reads ~0 in the model, and total volume
    * autocorrelation falls to the variance-share BLEND of its parts (0.48) where the record's
    * exceeds it (0.64/0.70). */
  val VolLag = 0.145
  val VolDown = 0.045
  val VolPhi = 0.97
  val VolSlowShare = 0.55
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
    rateMean = 0.042, volPersist = 0.99, volOfVol = 0.011,
    jumpVar = 0.0, jumpRate = 0.0, valuePull = 0.013, recoveryDrag = 0.0, recoveryFloor = 1.0,
    crowd = Crowd.Momentum, crowdImpact = 0.088, panic = 0.0, duration = 13.5,
    easing = 0.045, unwind = 0.35, refuge = 0.08,
    inflProb = 0.20, inflSize = 0.10, inflSpeed = 0.010, rateSpeed = 3.0, discount = 3.35,
    margin = 0.006)
  private val PreV1901 = V0_19_2.copy(
    trendShare = 0.30, depth = 12.0, stress = 3.4, volOfVol = 0.028, valuePull = 0.015,
    crowdImpact = 0.06, drift = 0.100, duration = 13.5, inflSize = 0.07,
    discount = 4.0, margin = 0.0008)
  private val PreV1902 = V0_19_2.copy(depth = 16.3, stress = 5.4)
  /** 0.20.0's world, frozen for the same reason `V0_19_2` is: 0.21.0 moved the default off it, and
    * a row that read `Defaults` would restate today's world under yesterday's version number. */
  private val V0_20_0 = World(
    trendShare = 0.07, depth = 16.1, stress = 5.6, beta = 3.0, drift = 0.123, fundVol = 0.13,
    rateMean = 0.042, volPersist = 0.99, volOfVol = 0.014,
    jumpVar = 0.0, jumpRate = 0.0, valuePull = 0.0145, recoveryDrag = 0.0, recoveryFloor = 1.0,
    crowd = Crowd.Momentum, crowdImpact = 0.07, panic = 0.0, duration = 13.5,
    easing = 0.046, unwind = 0.35, refuge = 0.11,
    inflProb = 0.20, inflSize = 0.10, inflSpeed = 0.010, rateSpeed = 3.0, discount = 5.0,
    margin = 0.006)
  /** 0.22.1's world, frozen for the same reason `V0_20_0` is: the valuation cycle moved the
    * default off it. */
  private val V0_22_1 = World(
    trendShare = 0.055, depth = 17.4, stress = 5.37, beta = 3.0, drift = 0.118, fundVol = 0.070,
    rateMean = 0.042, volPersist = 0.99, volOfVol = 0.027,
    jumpVar = 0.17, jumpRate = 0.0050, valuePull = 0.045,
    recoveryDrag = 10.0, recoveryFloor = 0.10, haltLimit = 0.25,
    disasterRate = 0.6, disasterSize = 2.0, disasterLen = 2.5,
    disasterRecover = 0.5, disasterRecLen = 4.0,
    crowd = Crowd.Momentum, crowdImpact = 0.030, panic = 0.0, duration = 13.5,
    easing = 0.060, unwind = 0.35, refuge = 0.11,
    inflProb = 0.20, inflSize = 0.10, inflSpeed = 0.010, rateSpeed = 3.0, discount = 5.73,
    margin = 0.006)
  /** 0.22.0's world, frozen for the same reason `V0_20_0` is: the disaster channel moved the
    * default off it. */
  private val V0_22_0 = World(
    trendShare = 0.055, depth = 17.4, stress = 5.37, beta = 3.0, drift = 0.113, fundVol = 0.070,
    rateMean = 0.042, volPersist = 0.99, volOfVol = 0.027,
    jumpVar = 0.17, jumpRate = 0.0050, valuePull = 0.045,
    recoveryDrag = 10.0, recoveryFloor = 0.10, haltLimit = 0.25,
    crowd = Crowd.Momentum, crowdImpact = 0.030, panic = 0.0, duration = 13.5,
    easing = 0.060, unwind = 0.35, refuge = 0.11,
    inflProb = 0.20, inflSize = 0.10, inflSpeed = 0.010, rateSpeed = 3.0, discount = 5.73,
    margin = 0.006)
  /** 0.21.0's world, frozen for the same reason `V0_20_0` is: the variance-ratio row moved the
    * default off it. */
  private val V0_21_0 = World(
    trendShare = 0.055, depth = 16.94, stress = 5.37, beta = 3.0, drift = 0.113, fundVol = 0.041,
    rateMean = 0.042, volPersist = 0.99, volOfVol = 0.027,
    jumpVar = 0.10, jumpRate = 0.0010, valuePull = 0.045,
    recoveryDrag = 10.0, recoveryFloor = 0.10, haltLimit = 0.25,
    crowd = Crowd.Momentum, crowdImpact = 0.07, panic = 0.0, duration = 13.5,
    easing = 0.052, unwind = 0.35, refuge = 0.11,
    inflProb = 0.20, inflSize = 0.10, inflSpeed = 0.010, rateSpeed = 3.0, discount = 5.73,
    margin = 0.006)
  /** 0.23.0's world, frozen when 0.23.1 development opened: the asymmetry adoption and the
    * valuation cycle moved the default onto it.  Identical to `Defaults` until a default moves;
    * the `-atrelease` contract test pins the equality for as long as the version is 0.23.0. */
  private val V0_23_0 = World(
    trendShare = 0.055, depth = 17.4, stress = 5.15, beta = 3.0, drift = 0.122, fundVol = 0.070,
    rateMean = 0.042, volPersist = 0.992, volOfVol = 0.022,
    jumpVar = 0.14, jumpRate = 0.0035, leverage = 0.12, downShock = 0.0, jumpSkew = 0.7,
    newsRate = 1.3, newsSize = 0.033, refugeDays = 1.0,
    valuePull = 0.056,
    recoveryDrag = 8.5, recoveryFloor = 0.10, haltLimit = 0.25,
    disasterRate = 0.6, disasterSize = 2.0, disasterLen = 2.5,
    disasterRecover = 0.5, disasterRecLen = 4.0,
    beliefShare = 0.95, beliefYears = 1.5, capYears = 1.5, capWindow = 6.0,
    crowd = Crowd.Momentum, crowdImpact = 0.030, panic = 0.0, duration = 13.5,
    easing = 0.052, unwind = 0.35, refuge = 0.115,
    inflProb = 0.20, inflSize = 0.10, inflSpeed = 0.010, rateSpeed = 3.0, discount = 5.73,
    margin = 0.006)
  val Releases: Vector[(String, World)] = Vector(
    ("0.17.0", PreV1901), ("0.18.0", PreV1901), ("0.19.0", PreV1901),
    ("0.19.1", PreV1902), ("0.19.2", V0_19_2), ("0.19.3", V0_19_2), ("0.20.0", V0_20_0),
    ("0.21.0", V0_21_0), ("0.22.0", V0_22_0), ("0.22.1", V0_22_1), ("0.23.0", V0_23_0))

  /** The world a release shipped, for `-atrelease`: the current version's default, or a frozen row
    * of the `-releases` table.  `None` for anything else -- the CLI dies naming what exists.  The
    * frozen rows reproduce their release's world under the current binary because every mechanism
    * added since is dial-gated to bit-inertness at zero (the contract tests pin that); paths
    * reproduce statistically, and bit-for-bit only back to 0.23.0 (`expDet` moved `trendPos` off
    * the native tanh). */
  def releaseWorld(version: String): Option[World] =
    if version == Version then Some(Defaults)
    else Releases.find(_._1 == version).map(_._2)

  /** Named worlds `-atrelease` resolves beside the version rows: a recipe VERIFIED at a release
    * and frozen with the anchor set it was graded against, so a consumer can name a
    * channel-emitting or non-S&P world without carrying its flags.  Built on the frozen row,
    * never on `Defaults`, so a later defaults change cannot move it.  Deliberately not
    * `-releases` rows: that table grades every world against ONE anchor set, and a Nasdaq world
    * under S&P rulers is not a reading. */
  val Recipes: Vector[(String, World, String)] = Vector(
    // The channel-emitting Nasdaq world of MarketSimWorlds.md ("A Nasdaq world that passes the
    // gate") at the ANCHORED channel dials: realism, mechanism and fidelity PASS with all six
    // series graded (verified at 0.23.0: satellite corr 0.846, beta 1.20, vol ratio 1.42; range
    // vs cc vol 1.12, down/up 1.12).
    ("0.23.0-nasdaq",
     V0_23_0.copy(depth = 10.0, drift = 0.105, jumpVar = 0.02, fundVol = 0.06,
                  satBeta = 1.2, satIdio = 0.77, rangeScale = 0.63, rangeDown = 0.09,
                  volIdio = 0.34),
     "nasdaq"))

  /** What `-atrelease NAME` seeds from: a release's world, anchors untouched, or a recipe with
    * the anchor set it was verified against -- which an explicit `-anchors` still overrides. */
  def namedWorld(name: String): Option[(World, Option[String])] =
    releaseWorld(name).map(w => (w, None))
      .orElse(Recipes.find(_._1 == name).map((_, w, a) => (w, Some(a))))

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
  /** POLICY ACCOMMODATION's cap is an ANCHOR, not a fitted number.  `usage` interpolates
    * `Defaults.easing` and asserts it IS one full real easing cycle, which makes the value a claim
    * the program makes about itself.  Real full cycles: 2007-08 took the target 5.25 -> 0.125 (5.1
    * points), 2001-03 took 6.50 -> 1.00 (5.5), 1989-92 took 9.81 -> 3.00 (6.8).  The 0.046 shipped
    * through 0.20.0 was 4.6 points -- BELOW every one of them, so the help text was slightly false.
    * 0.052 is 5.2 points: the BOTTOM of the range (2007-08's cycle), and the anchor is a RANGE, so
    * where in it the value sits is the ladder's to choose.
    *
    * The ladder ROTATES on this dial, and the window MOVES WITH THE EQUITY WORLD -- which is the
    * part worth carrying forward.  At the 0.21.0 world the window was 0.050-0.056; cutting
    * `crowdImpact` in 0.22.0 shifted it up to 0.060; the settled-stress refuge (`refugeDays`,
    * 0.23.0) shifted it back DOWN: at this world 0.060 fails the shipped duration's own depth band
    * outright (1.37 on the gate seed), 0.045 converges the d=5.70 rung below its 0.65 floor (0.64
    * at 400 paths), and 0.052 passes both ends (0.70 / 1.30, `-crossasset` verdict PASS).  A bond
    * dial cannot be settled once and left; re-run `-crossasset` after any equity-side change. */
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
    * the crowd-flow diagnostic can state the reflexive channel as a share of it.
    *
    * STAYS FROZEN, and now for a measured reason rather than an untested convention.  It was
    * promoted to a `World` field and swept 0.005-0.013 to ask the obvious question: does it raise
    * volatility WITHOUT raising crash frequency, which `depth` cannot?  It does not.  Volatility
    * moves 0.85 -> 1.68 of anchor while crashes move 0.94 -> 2.64, an elasticity of 1.5 -- milder
    * than `depth`'s 1.9 and nowhere near the 0 that "separates" would mean.  The coupling is the
    * same mechanism in both: more noise trips the liquidity spiral more often.
    *
    * The sweep also LOOKS like it fixes the shallow median crash (0.81 -> 0.95 as sigmaN rises),
    * and that reading is an artifact.  Hold volatility and crash rate constant by raising `depth`
    * and easing `stress` to compensate, and median depth comes out at 0.80-0.82 -- WORSE than the
    * 0.85 default.  The apparent gain was every drawdown being bigger at higher volatility, not a
    * new degree of freedom.  A dial swept alone can look like it moves a statistic it only
    * co-moves with; the test is whether it still moves it with the co-movers pinned. */
  val SigmaN = 0.007

  /** THE SECOND TAIL CHANNEL.  Daily kurtosis was a recorded scope exclusion for four releases,
    * parked as needing "a slow valuation cycle".  The provenance note gives the sharper reason:
    * KURTOSIS AND CLUSTERING CANNOT BOTH BE RIGHT through `stress`, which reaches kurtosis 26.4
    * only at clustering 1.67, outside its realism band.  That is a statement about `stress` -- the
    * only tail channel this model had -- and the same note says so: the missing cycle "is why
    * there is no SECOND channel for tails, not why this one cannot reach them."
    *
    * This is that second channel, and it is a jump rather than a valuation cycle.  A share
    * `jumpVar` of the equity flow's variance moves out of the diffusion and into a compensated
    * jump, so TOTAL flow variance is unchanged and `equity vol %` does not move.  The model does
    * not need more crash magnitude -- it already runs crashes and worst-crash depth ABOVE their
    * anchors -- it needs the magnitude it has arriving in fewer, more violent sessions.
    *
    * The jump is a FLOW, not a return: it goes through `Market.step` like every other shock, so a
    * jump into a thin market moves the price further than the same jump into a deep one, and the
    * stress, liquidity and crowd machinery all see it.  That feedback is the whole reason this
    * belongs in the model rather than in a post-process over emitted paths.
    *
    * Three shape constants, deliberately not dials:
    *
    * `JumpNu` MUST exceed 4.  A Student-t with four or fewer degrees of freedom has an INFINITE
    * fourth moment, so its sample kurtosis never converges and a kurtosis target fitted against it
    * is not a calibration.  Measured over 4,000 path-years, nu 5 held pooled kurtosis to +/-1.15
    * across seeds where nu 6 swung +/-5.12.
    *
    * `JumpGamma = 2` is not a taste.  Intensity scales with the volatility state as `m^gamma`
    * where `m = exp(logVol - volNorm)` and `logVol` is Gaussian with variance `volNorm`, so
    * `E[m^gamma] = exp(volNorm * (gamma^2/2 - gamma))`, which is exactly 1 at gamma = 2 and at no
    * other positive value.  Only there does `jumpRate` mean the unconditional intensity it claims
    * to be; anywhere else the realised rate drifts with `volOfVol` and the dial lies.
    *
    * `jumpSkew` (a World dial since the leverage change; 0.4 through 0.22.1) shifts the jump down
    * by that many of its own sd, which is what carries the negative skew a symmetric jump cannot
    * (real equity skew is about -0.25 on SPY 1993-2026). */
  val JumpNu    = 5
  val JumpGamma = 2.0

  /** Jump size, from the share of variance it carries and how often it fires.  `1 + jumpSkew^2` is
    * the shift's own contribution to the second moment; without it the channel would overshoot the
    * variance it is supposed to be borrowing, and `equity vol %` would drift with `jumpVar` -- and
    * it is why a deeper skew at fixed `jumpVar` makes each jump smaller rather than the tail
    * heavier. */
  def jumpScale(w: World): Double =
    SigmaN * math.sqrt(w.jumpVar / (w.jumpRate * (1.0 + w.jumpSkew * w.jumpSkew)))

  /** The diffusion damp the news channel applies.  News variance DISPLACES diffusive noise instead
    * of stacking on top of it, the same budget rule `jumpVar` enforces with its (1 - jumpVar)
    * factor: the record's 16% already contains its bad-news days, so a world calibrated without
    * them must yield generic variance when the channel turns on -- added instead, the channel
    * taxed equity vol and the crash rate ~5 seed-sd and no amplifier dial could pay it back.  Sized
    * against SigmaN's own per-session variance; the vol-state factor is centred at 1 by `volNorm`,
    * so the unconditional budget is the right ruler.  1.0 when the channel is off.  0 once the news
    * variance has consumed the whole budget -- a price running on jumps alone, whose bar channels
    * have no diffusion sd to level on (`worldLevel` divides by the MEAN diffusion sd);
    * `newsBudgetRefusal` turns that world away at the CLI rather than letting it reach a NaN bar. */
  def newsDampAt(newsRate: Double, newsSize: Double): Double =
    if newsRate > 0.0 then
      math.sqrt(math.max(1.0 - (newsRate / DaysPerYear) * newsSize * newsSize / (SigmaN * SigmaN), 0.0))
    else 1.0

  /** The CLI's refusal text for a news channel past the diffusion budget -- `newsRate * newsSize^2`
    * must stay below `252 * SigmaN^2` -- stated at the caller's rate as the largest admissible
    * size; `None` inside the budget or with the channel off. */
  def newsBudgetRefusal(newsRate: Double, newsSize: Double): Option[String] =
    if newsRate > 0.0 && newsDampAt(newsRate, newsSize) <= 0.0 then
      val budget  = DaysPerYear * SigmaN * SigmaN
      val maxSize = math.sqrt(budget / newsRate)
      Some(f"-newsrate $newsRate -newssize $newsSize leave no diffusion to displace: " +
        f"newsRate*newsSize^2 must stay below 252*SigmaN^2 = $budget%.5f " +
        f"(at -newsrate $newsRate, -newssize below $maxSize%.4f)")
    else None

  /** ONE price-formation mechanism for every traded asset: value demand toward `fair`, plus
    * external flow and noise, amplified when THIS market's liquidity has withdrawn after one-sided
    * selling (measured against a slowly-adapting scale, so symmetric turbulence of any size leaves
    * the index flat — E[max(0,-z)] = 0.399 regardless of scale). */
  /** Drawdown at which recovery drag reaches its stated strength.  0.10 keeps it inert in ordinary
    * sessions, so it shapes recoveries from real drawdowns and nothing else. */
  val DrawdownRef = 0.10
  /** Bound on the growth-capitalization term, in log units: perceived fair may ride at most
    * this far from the fundamental on extrapolated growth alone (tanh-squashed).  0.80 log is
    * a 2.2x valuation, past the record's worst manias (CAPE 44 = 2.7x its mean including the
    * gap channel's share).  FROZEN: it is a guard on the term's DOMAIN, not a tuning surface. */
  val CapSpan = 0.80

  /** DETERMINISTIC exp: Cody-Waite range reduction with fdlibm's split ln2, a fixed Horner
    * Taylor to r^12 on the reduced argument, and 2^k built from raw exponent bits.  Every
    * operation is IEEE-exact-or-fixed, so the twins agree TO THE BIT by construction -- which no
    * native libm call guarantees: the momentum crowd's tanh diverged from Rust's by one ulp on a
    * cycle-world input after four releases of input luck, and rebuilding tanh from the NATIVE exp
    * only moved the divergence into exp's own wide-argument ulps (both measured 2026-08-30, the
    * PARITY.md `log` class).  Accuracy ~2 ulp, which a behavioural squash cannot see; |y| is
    * bounded by `tanhP`'s cutoff so the 2^k construction stays in range.  Use it for any future
    * transcendental that must match across the twins. */
  def expDet(y: Double): Double =
    // fdlibm's split ln2, as BIT PATTERNS so the twins' constants are identical by inspection.
    val Ln2Hi = java.lang.Double.longBitsToDouble(0x3FE62E42FEE00000L)
    val Ln2Lo = java.lang.Double.longBitsToDouble(0x3DEA39EF35793C76L)
    // floor(x + 0.5), written out: Java's round and Rust's differ on negative halves.  The
    // multiplier is log2(e), the same double Rust's f64::consts::LOG2_E holds.
    val k     = math.floor(y * 1.4426950408889634 + 0.5).toInt
    val r     = (y - k * Ln2Hi) - k * Ln2Lo
    // Taylor e^r to r^12 in fixed Horner order; |r| <= 0.3466 puts truncation near 3e-15.
    var p = 1.0 / 479001600.0                              // 1/12!
    p = p * r + 1.0 / 39916800.0
    p = p * r + 1.0 / 3628800.0
    p = p * r + 1.0 / 362880.0
    p = p * r + 1.0 / 40320.0
    p = p * r + 1.0 / 5040.0
    p = p * r + 1.0 / 720.0
    p = p * r + 1.0 / 120.0
    p = p * r + 1.0 / 24.0
    p = p * r + 1.0 / 6.0
    p = p * r + 0.5
    p = p * r + 1.0
    p = p * r + 1.0
    p * java.lang.Double.longBitsToDouble((k.toLong + 1023L) << 52)

  /** tanh from `expDet` via (e^2x - 1)/(e^2x + 1), so the twins agree to the bit; past +-20 the
    * guard returns the sign exactly (1 - tanh(20) ~ 8e-18, below one ulp of 1.0).  Both squash
    * sites use it -- the cap term and the momentum crowd's `trendPos`. */
  def tanhP(x: Double): Double =
    if x > 20.0 then 1.0
    else if x < -20.0 then -1.0
    else
      val e2 = expDet(2.0 * x)
      (e2 - 1.0) / (e2 + 1.0)

  /** What counts as the DEEP tail for the guard's own accounting: a session losing more than 0.20
    * in log terms, about -18% simple.  The real record holds roughly one such session per century,
    * so this is the region where a consumer reading worst-case behaviour is reading a handful of
    * events -- and where a guard that binds at all determines what the worst one WAS.
    *
    * Cut at 0.10 first and the statistic read 1.1-1.4% in every world tried, against a guard that
    * was authoring every one of the ten worst sessions: the shallower threshold buries the signal
    * in two orders of magnitude of ordinary bad days, and a band drawn there cannot fail. */
  val TailRef = 0.20

  /** TRADING HALT -- a market-structure floor on one session's decline, with the unfilled pressure
    * DEFERRED to the next session rather than discarded.
    *
    * Why a mechanism and not a wider guard.  The numerical guard truncates; whatever wanted to
    * happen past it is thrown away, so the worst session a world can produce is the guard's own
    * value and the sessions just past it pile against that wall.  A halt is what a real market does
    * instead: US market-wide breakers close the day at a 20% decline, and the selling that could not
    * be filled arrives the NEXT session.  That is the whole difference -- a halt defers, it does not
    * cancel -- and it is why the tail comes out as a multi-session cascade rather than one
    * impossible day.  The worst real S&P session is -20.5%, in 1987, before breakers existed.
    *
    * `haltLimit` is a SIMPLE decline fraction so it can be read against the breaker level directly:
    * 0.20 is the Level 3 close.  DECLINE-ONLY, because that is the real asymmetry; large advances
    * keep the numerical guard, which is the job that guard was written for.
    *
    * WHY THE DEFAULT IS 0.25 AND NOT THE BREAKER'S OWN 0.20.  A floor must admit the record it is
    * calibrated against, and the worst real S&P session is -20.5% (1987-10-19) -- a day that
    * PRE-DATES the breaker system it would be excluded by.  These worlds span a century, most of
    * which had no market-wide breaker at all, so 0.25 sits above the empirical worst and below the
    * no-structure-at-all of a bare guard.  It also costs nothing: the guard's grip on the tail goes
    * 10.9% -> 0.0% while kurtosis holds at 27.50 and every other calibrated statistic moves in the
    * third decimal.  `-haltlimit 0.20` gives the strict post-1988 world for anyone who wants it, at
    * a stated price -- kurtosis 0.98 -> 0.86, because at that level the halt starts removing the
    * sessions the kurtosis anchor is made of.
    *
    * At 0.0 the mechanism is absent and `carry` never leaves zero, so every earlier world is
    * reproduced BIT-IDENTICALLY -- the halt consumes no random draws. */
  final class Market(kValue: Double, stressK: Double, impact: Double,
                     recoveryDrag: Double = 0.0, recoveryFloor: Double = 1.0,
                     haltLimit: Double = 0.0):
    private val floorLog = if haltLimit <= 0.0 then Double.NegativeInfinity
                           else math.log(1.0 - haltLimit)
    private var carry = 0.0
    var haltDays = 0
    var logP = 0.0
    var peak = 0.0
    var stressIdx = 0.0
    var lastLiq = impact
    var clamps = 0
    /** Sessions on the DOWNWARD guard, and sessions in the tail at all.  Counted separately from
      * `clamps` because the question the gate has to answer is not how often the guard binds --
      * it binds on almost nothing -- but what share of the extreme tail it SHAPES. */
    var floorDays = 0
    var tailDays = 0
    private[apps] var scaleVar = 0.01 * 0.01
    def step(fair: Double, flowPlusNoise: Double): Double =
      val scale = math.sqrt(scaleVar)
      val amp   = 1.0 + stressK * stressIdx
      lastLiq   = amp * impact
      // amplification applies to FLOW AND NOISE, not to the value-arbitrage pull: thin liquidity
      // makes any ORDER move price further, but amplifying the arbitrage itself sets a feedback
      // gain of kValue*amp, which for a fast-tracking market (bond, kValue 0.7) exceeded 1 and
      // OSCILLATED — 86% bond volatility from the market fighting its own fair value.
      // ASYMMETRIC RECOVERY.  Value arbitrage is WEAKER, not stronger, when the market is far
      // below its own peak: the capital that closes a gap is most depleted exactly when the gap is
      // largest.  One-sided -- it touches the pull only while it points UP and only past
      // `DrawdownRef` -- so declines are unaffected and recoveries grind.
      //
      // What it fixes, measured: the model spends HALF the time below 15% that the real record does
      // (d15 0.115 against SPY's 0.240) while crossing 15% 40% MORE often, so each excursion lasts a
      // third as long (0.395 against 1.148).  Its deep drawdowns recover far too fast.  Median
      // fall-to-rise ratio reads 1.02 here against 1.44 for SPY and 1.28 for QQQ.
      //
      // `recoveryFloor` is the residual arbitrage that is always present: unbounded, the pull falls
      // to a seventeenth of strength at a 30% drawdown, which is capital switched off rather than
      // depleted, and the deepest drawdowns run away.  Both defaults reproduce the symmetric pull
      // of every earlier release BIT-IDENTICALLY -- the multiplier is exactly 1.0.
      //
      // A pull made CONVEX in the mispricing was tried first and has the sign backwards: it cut
      // crash count but drained d5, d10 and kurtosis with it, because a stronger pull cannot tell a
      // deep drawdown from an ordinary one, and a market can sit 10% under fair while 3% under peak.
      val gap   = fair - logP
      val drop  = peak - logP
      val damp  = if recoveryDrag <= 0.0 || gap <= 0.0 || drop <= DrawdownRef then 1.0
                  else math.max(recoveryFloor,
                                1.0 / (1.0 + recoveryDrag * (drop - DrawdownRef) / DrawdownRef))
      val raw   = (kValue * gap * damp + flowPlusNoise * amp) * impact
      // Numerical guard ONLY, and verified to be exactly that: at ±0.25 vs ±0.50 every statistic in
      // every gate-passing world is BIT-IDENTICAL (the clamp consumes no draws and never binds
      // there).  In a far out-of-gate world (40% volatility) it bound on 0.075% of sessions and
      // was silently shaping the tail — kurtosis 26.8 at ±0.25 vs 35.8 at ±0.50 — so it sits at
      // ±0.50, far from any plausible daily move (worst real S&P day ~ -23% log), and the gate
      // below rejects any world where it engages enough to matter.
      // Deferred pressure from a halted session arrives here, ahead of this session's own bound.
      val rawC  = raw + carry
      val halted = rawC < floorLog
      if halted then { haltDays += 1; carry = rawC - floorLog } else carry = 0.0
      val bound = if halted then floorLog else rawC
      val ret   = math.max(-0.50, math.min(0.50, bound))
      if ret != bound then clamps += 1
      if ret != bound && bound < 0.0 then floorDays += 1
      if ret < -TailRef then tailDays += 1
      logP += ret
      if logP > peak then peak = logP
      scaleVar  = 0.995 * scaleVar + 0.005 * ret * ret
      stressIdx = math.max(0.0, 0.96 * stressIdx + 0.04 * (math.max(0.0, -ret) / scale - 0.399))
      ret

  /** Per-session inputs the derived channels read, recorded by `simulate`'s price loop: the
    * observed log price (markdown and news included -- the return a consumer measures), the
    * session diffusion sd as the price received it (`sessSigma` x spiral amplification), the
    * satellite's state factor (vol state x spiral amplification over the base impact), and
    * `scaleVar` after the step -- the volume down-term's realized scale, one session fresher than
    * the leverage signal's (stated, and mirrored). */
  final case class ChannelInputs(px: Array[Double], d: Array[Double], state: Array[Double],
                                 scaleVar: Array[Double]):
    /** This path's contribution to the world's level: sums of the observed squared return, the
      * session diffusion sd and the squared satellite state factor from the second session (the
      * first has no return), plus the count -- in session order, which is part of the
      * cross-language contract. */
    def levelSums: (Double, Double, Double, Double) =
      val tot = px.length
      var sR2 = 0.0; var sD = 0.0; var sSt = 0.0
      var i = 1
      while i < tot do
        val r = px(i) - px(i - 1)
        sR2 += r * r
        sD += d(i)
        sSt += state(i) * state(i)
        i += 1
      (sR2, sD, sSt, (tot - 1).toDouble)

  /** The world's channel level: `k` re-levels the session diffusion sd onto the world's realized
    * close-to-close sd, `kSat` the satellite's state factor onto it in root-mean-square. */
  final case class ChannelLevel(k: Double, kSat: Double, kDiv: Double = 0.0)

  /** The fixed ensemble the level is solved on.  Small on purpose: the level is a mean over ~200k
    * sessions, so its sampling error is under 1% even at kurtosis 60, and it is solved once per
    * `simPaths` call. */
  val LevelPaths: Int  = 8
  val LevelYears: Int  = 100
  val LevelSeed: Long  = 0x1e7e1000L

  /** THE WORLD'S CHANNEL LEVEL, pooled over `LevelPaths` x `LevelYears` at `LevelSeed` -- a
    * function of the world alone, so path k of (world, seed) stays reproducible from its sidecar
    * and every path of a world is sampled at one scale.  Sums run in path order then session
    * order in both twins.  0 / 0 when both channels are off -- never read. */
  def worldLevel(w: World): ChannelLevel =
    val chOn  = w.rangeScale > 0.0 || w.satBeta > 0.0
    val divOn = w.divYield > 0.0
    if !(chOn || divOn) then ChannelLevel(0.0, 0.0, 0.0)
    else
      val sums = java.util.stream.IntStream.range(0, LevelPaths).parallel()
        .mapToObj { k =>
          val pr = priceLoop(w, LevelYears, LevelSeed + k.toLong * 7919L)
          // the channel inputs are recorded only when a channel ran; the dividend level needs
          // none of them
          (if chOn then pr.inputs.levelSums else (0.0, 0.0, 0.0, 0.0), fairOverPriceSum(pr.path))
        }
        .toArray()
      var sR2 = 0.0; var sD = 0.0; var sSt = 0.0; var m = 0.0; var sFp = 0.0; var nFp = 0.0
      var i = 0
      while i < sums.length do
        val ((a, b, c, n), (fp, nf)) =
          sums(i).asInstanceOf[((Double, Double, Double, Double), (Double, Double))]
        sR2 += a; sD += b; sSt += c; m += n; sFp += fp; nFp += nf
        i += 1
      ChannelLevel(if chOn then math.sqrt(sR2 / m) / (sD / m) else 0.0,
                   if chOn then math.sqrt(sR2 / sSt) else 0.0,
                   if divOn then sFp / nFp else 0.0)

  /** The dividend level's input: one path's sum of fundamental/price over its sessions, in
    * session order (part of the cross-language contract), and the count.  A world constant for
    * the same reason the bar level is: read off the path being emitted, a mean over the whole
    * path leaks its future into every session's yield. */
  def fairOverPriceSum(p: Path): (Double, Double) =
    var sFp = 0.0
    var i = 0
    while i < p.price.length do
      sFp += p.fundamental(i) / p.price(i)
      i += 1
    (sFp, p.price.length.toDouble)

  /** One path's derived channels: the satellite leg's price and the sampled bars. */
  final case class Channels(sat: Array[Double], logHi: Array[Double], logLo: Array[Double],
                            logVolume: Array[Double])

  /** THE DERIVED CHANNELS, sampled in a second pass from the price loop's recorded inputs.  They
    * are OBSERVATIONAL -- nothing here reaches a price -- which is what licenses the second pass,
    * and the second pass is what licenses the level.  Each channel rides the session's own state
    * (the diffusion sd as the price received it; the satellite's vol-state x spiral factor)
    * re-levelled onto the WORLD's realized close-to-close volatility -- `worldLevel`, k =
    * realized sd / mean diffusion sd, solved once per world from a fixed ensemble and shared by
    * every path.  The level is a constant of the world, and no estimator read off the path itself
    * is clean.  A causal one is noisy, biased or both: an EWMA saturated at four sds (so that one
    * 10-sd session cannot lift it 50% for months) has a TRUNCATED variance as its fixed point,
    * and the truncated share is a property of the tail -- 0.92 at kurtosis 13, 0.56 at 96;
    * unsaturated, slow or cumulative, it re-learns the level from every extreme session and mints
    * idio kurtosis 31 / 23 against the record's 17; frozen at end of burn-in it spans 0.60-2.08
    * of the path's own sd.  And the path's whole-path variance LEAKS: a constant says nothing
    * about when, but it carries the whole path's level, so years 1-10 of a bar series predicted
    * the log volatility of years 11-100 at +0.81 against +0.06 in a channel-free control, and
    * pinned the graded range/ccvol row by its own definition (cross-path sd 0.016 against 0.061).
    * Both are the consumer's measurements.  A world constant carries nothing a path could not
    * already know.  This is what makes `rangeScale` a bar-to-realized-vol ratio and `satIdio` an
    * idio-to-primary-vol fraction that hold across worlds: on the diffusion scale alone the same
    * range dial read bar/ccvol 1.115 at the default and 1.264 at the Nasdaq recipe, because the
    * share of variance the diffusion carries is a property of the world.  The satellite's state
    * factor is the vol state times the spiral's amplification over the base impact, WITHOUT the
    * leverage kick and news damp the range carries (on those the leg's kurtosis outran its
    * primary's, ratio 1.2-1.4 against the record's 0.55-1.12), re-levelled by its own root mean
    * square so the idio's realized sd is `satIdio` times the primary's whatever shape the state
    * takes -- the correlation then transports by construction.  Each channel reads its own
    * dedicated stream -- constructed from the path's seed, read only when the channel is on, so 0
    * is bit-identical off; the range takes two uniforms per session, max then min, the volume two
    * normals, slow innovation then white, and that draw ORDER is part of the cross-language
    * contract.  The first session's return-from-zero is absorbed by burn-in as before. */
  def deriveChannels(w: World, x: ChannelInputs, level: ChannelLevel, seed: Long): Channels =
    val srng    = new NumPyRNG(seed ^ 0x5a7e1117L)
    val rrng    = new NumPyRNG(seed ^ 0xca9d1e00L)
    val vrng    = new NumPyRNG(seed ^ 0xd011a5e5L)
    val tot     = x.px.length
    val satOn   = w.satBeta > 0.0
    val rangeOn = w.rangeScale > 0.0
    val volOn   = rangeOn && w.volIdio > 0.0
    val sat = if satOn then new Array[Double](tot) else Array.emptyDoubleArray
    val hi  = if rangeOn then new Array[Double](tot) else Array.emptyDoubleArray
    val lo  = if rangeOn then new Array[Double](tot) else Array.emptyDoubleArray
    val vv  = if volOn then new Array[Double](tot) else Array.emptyDoubleArray
    if !(satOn || rangeOn) then Channels(sat, hi, lo, vv)
    else
      val k  = level.k
      val kS = level.kSat
      // SATELLITE LEG state: its log price and the primary's observed log price last session.
      var satLogP = 0.0; var satPrevPx = 0.0
      // RANGE state: the bar's open (the prior close -- no overnight).  Independent of the
      // satellite's tracker on purpose: the channels must not couple through bookkeeping.
      var barPrevPx = 0.0
      // VOLUME state: the slow AR component, the EWMA of ln(range) that defines the range's
      // "normal" (half-life 126 sessions -- the grading convention's rolling-median window,
      // centred), and its first-session initialization flag.
      var volSlow = 0.0; var volRxPrev = 0.0; var volEwma = 0.0; var volEwmaSet = false
      val volEwmaMu    = 1.0 - math.exp(-math.log(2.0) / 126.0)
      val volSlowInnov = w.volIdio * math.sqrt(VolSlowShare) * math.sqrt(1.0 - VolPhi * VolPhi)
      val volWhiteSd   = w.volIdio * math.sqrt(1.0 - VolSlowShare)
      var i = 0
      while i < tot do
        val logPx = x.px(i)
        // SATELLITE LEG: beta times the primary's observed log return, plus idio noise at
        // `satIdio` times the re-levelled state factor.  The spiral's share of that factor is
        // load-bearing: on log-vol alone the residual's stress/calm vol ratio read 1.13 against
        // the anchored 3.1, and the missing state manufactured a +0.30 stress-correlation kick
        // the record does not have.  Reads `srng` only.
        if satOn then
          val idio = w.satIdio * (x.state(i) * kS) * srng.randn()
          satLogP += w.satBeta * (logPx - satPrevPx) + idio
          satPrevPx = logPx
          sat(i) = math.exp(satLogP)
        // RANGE CHANNEL: high/low of a Brownian bridge from the bar's open (prior close) to its
        // close, at the re-levelled session scale times the disclosed compression dial.  Exact
        // inverse transforms for the one-sided extremes, max drawn first then min; sampling the
        // pair independently is the stated approximation (their joint law is not independent --
        // a joint sampler was measured and rejected, see the docs).  A jump day's range is >=
        // |ret| by construction -- the extremes bracket both endpoints.  The uniforms are floored
        // at 1e-300 so a zero draw cannot mint an infinite bar; the floor is part of the
        // cross-language contract.  Reads `rrng` only.
        if rangeOn then
          val rS   = logPx - barPrevPx
          val sig0 = (x.d(i) * k) * w.rangeScale
          // The sign coupling -- see the `rangeDown` field.  Applied to the bridge sigma BEFORE
          // the draws, consuming nothing; the near-reciprocal pair leaves the mean breadth at
          // ~(1 + x^2/2), which `rangeScale` absorbs.
          val sig =
            if w.rangeDown > 0.0 then
              if rS < 0.0 then sig0 * (1.0 + w.rangeDown) else sig0 / (1.0 + w.rangeDown)
            else sig0
          val sig2 = sig * sig
          val u1   = math.max(rrng.nextDouble(), 1e-300)
          val u2   = math.max(rrng.nextDouble(), 1e-300)
          hi(i) = barPrevPx + (rS + math.sqrt(rS * rS - 2.0 * sig2 * math.log(u1))) / 2.0
          lo(i) = barPrevPx + (rS - math.sqrt(rS * rS - 2.0 * sig2 * math.log(u2))) / 2.0
          barPrevPx = logPx
          // VOLUME: elasticity VolSlope to the range's log-deviation from its slow normal, a
          // down-day term shaped like the stress innovation (VolDown calibrated to the record's
          // RESIDUAL +0.036 -- most of the raw +0.12 flows THROUGH the range), plus the
          // two-component idio.  Reads `vrng` only; requires the range.
          if volOn then
            val lnx = math.log(math.max(hi(i) - lo(i), 1e-300))
            if !volEwmaSet then
              volEwma = lnx
              volEwmaSet = true
            val rx = lnx - volEwma
            volEwma += volEwmaMu * (lnx - volEwma)
            val down = math.max(0.0, -rS) / math.sqrt(x.scaleVar(i))
            volSlow = VolPhi * volSlow + volSlowInnov * vrng.randn()
            vv(i) = VolSlope * rx + VolLag * volRxPrev + VolDown * down + volSlow +
                    volWhiteSd * vrng.randn()
            volRxPrev = rx
        i += 1
      Channels(sat, hi, lo, vv)

  /** The price loop and the derived channels of one path; the channel arrays of `path` are
    * empty until `simulateAt` fills them. */
  final case class Priced(path: Path, inputs: ChannelInputs)

  /** THE DIVIDEND STREAM, derived from the finished path: the session yield `divYield` x
    * (fundamental/price) / kDiv in %/yr -- kDiv the world's mean fundamental/price from
    * `worldLevel`, so the dial is the world's MEAN yield and a rich session yields less -- and
    * the traded price as the total-return index deflated by the yield accrued each session,
    * S_t = S_{t-1} (P_t/P_{t-1} - y_t/100/DaysPerYear), S_0 = P_0.  Observational: reaches no
    * price and consumes no draw, so 0 is bit-identical off.  IEEE-exact arithmetic only,
    * evaluated left to right in both twins. */
  def deriveDividends(w: World, px: Array[Double], fv: Array[Double], kDiv: Double): (Array[Double], Array[Double]) =
    if !(w.divYield > 0.0) then (Array.emptyDoubleArray, Array.emptyDoubleArray)
    else
      val n = px.length
      val y = Array.tabulate(n)(i => w.divYield * (fv(i) / px(i)) / kDiv)
      val t = new Array[Double](n)
      t(0) = px(0)
      var i = 1
      while i < n do
        t(i) = t(i - 1) * (px(i) / px(i - 1) - y(i) / 100.0 / DaysPerYear)
        i += 1
      (y, t)

  /** One independent history: the price loop, then the derived channels at the given world
    * level. */
  def simulateAt(w: World, years: Int, seed: Long, level: ChannelLevel): Path =
    val pr   = priceLoop(w, years, seed)
    val chan = deriveChannels(w, pr.inputs, level, seed)
    val div  = deriveDividends(w, pr.path.price, pr.path.fundamental, level.kDiv)
    pr.path.copy(
      divYield  = div._1,
      traded    = div._2,
      sat       = if w.satBeta > 0.0 then chan.sat.drop(BurnIn) else Array.emptyDoubleArray,
      logHi     = if w.rangeScale > 0.0 then chan.logHi.drop(BurnIn) else Array.emptyDoubleArray,
      logLo     = if w.rangeScale > 0.0 then chan.logLo.drop(BurnIn) else Array.emptyDoubleArray,
      logVolume = if w.volIdio > 0.0 then chan.logVolume.drop(BurnIn) else Array.emptyDoubleArray,
      chanK     = level.k,
      chanKSat  = level.kSat,
      chanKDiv  = level.kDiv)

  /** `simulateAt` at the world's own level, solved here per call -- `simPaths` solves it once
    * for the whole ensemble, so prefer that for more than one path. */
  def simulate(w: World, years: Int, seed: Long): Path = simulateAt(w, years, seed, worldLevel(w))

  /** One independent history's PRICE LOOP, with the channels' per-session inputs recorded for the
    * second pass.  Local mutable state only — nothing escapes this method. */
  def priceLoop(w: World, years: Int, seed: Long): Priced =
    val n    = years * DaysPerYear
    val tot  = n + BurnIn
    val rng  = new NumPyRNG(seed)
    // The jump channel's own stream.  Separate BECAUSE the alternative is not survivable: a draw
    // taken from `rng` shifts every subsequent value and moves all sixteen calibrated statistics,
    // so the channel could not be added without re-searching the world.  Constructed
    // unconditionally -- it costs one allocation and touches nothing -- and read only when
    // `jumpVar > 0`.
    val jrng = new NumPyRNG(seed ^ 0x1eaf7a11L)
    // The disaster channel's own stream, for the same survivability reason as `jrng` above:
    // constructed unconditionally, read only when `disasterRate > 0`, so rate 0 is bit-identical.
    val drng = new NumPyRNG(seed ^ 0xd15a57e5L)
    // The news channel's own stream, same survivability contract as `jrng`/`drng`:
    // constructed unconditionally, read only when `newsRate > 0`, so rate 0 is bit-identical.
    val nrng = new NumPyRNG(seed ^ 0x0bad2e15L)
    // The channels' own streams are constructed in `deriveChannels` from this same seed.
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

    // The halt is an EQUITY market-structure rule.  The bond leg keeps the bare guard: there is no
    // market-wide breaker on Treasuries, and inventing one would be a fudge wearing a mechanism's
    // name.
    val eqM = new Market(w.valuePull, w.stress, 12.0 / w.depth, w.recoveryDrag, w.recoveryFloor,
                         w.haltLimit)
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
    val kAdapt = 0.010; val kHome = 0.020
    var logVol = 0.0
    // The leverage term's signal from the PREVIOUS session: max(-ret,0)/scale - 0.399, the same
    // decline reading `stressIdx` consumes, centred so the vol level does not drift with the
    // dial.  Draw-free; both its update and its use sit behind `leverage > 0`, so 0 is
    // bit-identical off.
    var levSig = 0.0
    // Settled equity stress for the refuge bid (see `refugeDays`); draw-free, and both its use
    // and its update sit behind `refugeDays > 0`, so 0 is bit-identical off.
    var settledStress = 0.0
    val settleMu = if w.refugeDays > 0.0 then 1.0 - math.exp(-math.log(2.0) / w.refugeDays) else 0.0
    val volNorm = (w.volOfVol * w.volOfVol) / math.max(1e-9, 1.0 - w.volPersist * w.volPersist)
    // News variance DISPLACES diffusive noise (see `newsDampAt`); 1.0 when the channel is off.
    val newsDamp = newsDampAt(w.newsRate, w.newsSize)
    val crowdWin = w.crowd match
      case Crowd.Trend(d) => math.max(2, math.round(d * 252.0 / 365.25).toInt)
      case _              => 0
    // The crowd starts where its own target starts, so the first session is not a trade it never
    // made.  The banded crowds begin fully invested (1.0); the momentum crowd's target IS
    // `trendPos`, which is 0 while there is no history to measure momentum over.
    val crowdInit = w.crowd match
      case Crowd.Momentum => 0.0
      case _              => 1.0
    var crowdE = crowdInit; var crowdPrev = crowdInit; var maSum = 0.0
    // BELIEF state for the slow valuation cycle: the EWMA of the price/fair gap that perceived
    // fair value has absorbed.  Updated from information strictly before this session.
    var belief = 0.0
    val beliefMu = if w.beliefYears <= 0.0 then 0.0
                   else 1.0 - math.exp(-math.log(2.0) / (w.beliefYears * DaysPerYear))
    // Growth-extrapolation state: EWMA of the fundamental's per-session log change, annualized in
    // the perceived-fair term.  Seeded at the unconditional drift so burn-in starts neutral.
    var gEwma = w.drift * dt
    val gMu   = if w.capWindow <= 0.0 then 0.0
                else 1.0 - math.exp(-math.log(2.0) / (w.capWindow * DaysPerYear))
    var vPrev = 0.0
    var crowdRv = 0.01 * 0.01; var crowdAnchor = 0.0
    // The drawdown crowd's running peak of the prior session's emitted price; draw-free.
    var crowdPeak = 0.0
    var bondStressSum = 0.0; var bondStressHi = 0
    // MACRO DISASTER state: sessions left in the current collapse, its per-session decrement, and
    // the post-burn-in onset count -- the channel's BINDING diagnostic.
    var disLeft = 0; var disStep = 0.0; var disasterCount = 0
    var recLeft = 0; var recStep = 0.0
    val disProb = w.disasterRate / (100.0 * DaysPerYear)
    // THE CHANNELS' INPUTS, recorded per session and sampled AFTER the loop by `deriveChannels`
    // (see it for why the level is a world constant, never read off the path being emitted): the
    // observed log price, the session diffusion sd as the price received it, the satellite's
    // state factor, and the post-step realized scale the volume's down-term reads.  Empty when
    // both channels are off; draw-free either way, so off worlds stay bit-identical.
    val chOn    = w.rangeScale > 0.0 || w.satBeta > 0.0
    val chPx    = if chOn then new Array[Double](tot) else Array.emptyDoubleArray
    val chD     = if chOn then new Array[Double](tot) else Array.emptyDoubleArray
    val chState = if chOn then new Array[Double](tot) else Array.emptyDoubleArray
    val chSv    = if chOn then new Array[Double](tot) else Array.emptyDoubleArray
    var crowdFlowSum = 0.0
    var clampsAtBurn = 0
    var eqFloorAtBurn = 0; var eqTailAtBurn = 0; var eqHaltAtBurn = 0

    var i = 0
    while i < tot do
      // ---- exogenous layer: regimes, fundamental, the policy rate ---------------------------
      regimeCountdown -= 1
      if regimeCountdown <= 0 then
        inflTarget = if rng.nextDouble() < w.inflProb then math.abs(rng.randn()) * w.inflSize else 0.0
        driftNow = w.drift + rng.randn() * 0.04
        regimeCountdown = 250 + rng.nextBoundedInt(2500)
      // MACRO DISASTER: a rare multi-year collapse of the real fundamental.  One uniform draw
      // per session from the channel's own stream while armed; onset starts a decline of
      // `disasterSize` log spread evenly over `disasterLen` years, which the price then tracks
      // through the ordinary value channel -- the crash is fundamental-led, like 1929-32, and the
      // spiral and recovery drag shape it downstream.  No new disaster starts while one runs.
      if disProb > 0.0 then
        if disLeft > 0 then
          logVbase -= disStep; disLeft -= 1
          // trough reached: the RECOVERY leg arms, spreading `disasterRecover` of the decline
          // back over `disasterRecLen` years.  What does NOT reverse is permanent.
          if disLeft == 0 && w.disasterRecover > 0.0 then
            recLeft = math.max(1, (w.disasterRecLen * DaysPerYear).toInt)
            recStep = w.disasterRecover * w.disasterSize / recLeft
        else
          if recLeft > 0 then { logVbase += recStep; recLeft -= 1 }
          if drng.nextDouble() < disProb then
            disLeft = math.max(1, (w.disasterLen * DaysPerYear).toInt)
            disStep = w.disasterSize / disLeft
            if i >= BurnIn then disasterCount += 1
      logVbase += driftNow * dt + w.fundVol * sqdt * rng.randn()
      // FAIR-VALUE NEWS JUMP: a permanent markdown repriced the SAME session -- the fundamental
      // and the price take the full drop together, so the price/fair gap, and with it the value
      // channel, the belief EWMA and the mispricing, are untouched: a pure random-walk step with
      // nothing for value capital to buy back.  Morning news, placed before the demand-flows read
      // of logP, so the momentum crowd trades on it this session the way it trades on `markdown`.
      // The compensator is deterministic and returns the expected drift cost on BOTH legs.
      var newsJ = 0.0
      if w.newsRate > 0.0 then
        val comp = w.newsRate * w.newsSize / DaysPerYear
        logVbase += comp
        eqM.logP += comp
        if nrng.nextDouble() < w.newsRate / DaysPerYear then
          logVbase -= w.newsSize
          eqM.logP -= w.newsSize
          newsJ = w.newsSize
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
          case Crowd.Drawdown(d) =>
            if pPrev > crowdPeak then crowdPeak = pPrev
            val tgt = if pPrev >= crowdPeak * (1.0 - d.toDouble / 100.0) then 1.0 else 0.0
            if math.abs(tgt - crowdE) > Band then crowdE = tgt
          case Crowd.Momentum => ()

      // ---- demand flows ----------------------------------------------------------------------
      val logPobs = eqM.logP - markdown                 // what everyone actually sees and trades
      val mispricingPre = logVbase - eqM.logP           // value agents arb the traded component
      val lookback = 60
      val past = if i >= lookback then math.log(px(i - lookback)) else logPobs
      val momentum = logPobs - past
      // `tanhP`, not `math.tanh`, since 0.23.0: the native tanh survived four releases on input
      // luck and then disagreed with Rust's by one ulp at a session the valuation cycle's path
      // reaches (see `tanhP`).  Pre-0.23.0 paths therefore reproduce STATISTICALLY, not bit for
      // bit, at any dial setting -- the one cross-release compatibility this swap spends.
      val trendPos = tanhP(momentum / 0.12)
      // The momentum crowd's desired exposure, set here rather than in the block above because
      // `trendPos` needs this session's `logPobs` -- and `logPobs` carries this session's
      // `markdown`, so this crowd reacts to the rate move being priced in the SAME session, where
      // `Crowd.Trend` and `Crowd.VolScaled` read `px(i-1)` alone.  Two live consequences: `-crowd`
      // varies information timing along with crowd type, and `perfT` below pairs a position
      // holding -discount*dRate with a return holding the same term, a product that is
      // structurally positive and tilts the capital spring toward the trend crowd by arithmetic
      // rather than trading.  `trendShare` is calibrated, so the calibration has absorbed it;
      // whether the crowd should act one session later instead is a MECHANISM question, and
      // changing it moves every calibrated statistic.  It is continuous where the other crowds'
      // targets are banded, and deliberately
      // unbanded: the 0.05 band exists to stop a BINARY target flip-flopping across a moving
      // average, and a continuous target has nothing to flip-flop about.
      w.crowd match
        case Crowd.Momentum => crowdE = trendPos
        case _              => ()
      // ONE price-impact rule for every crowd: pressure comes from the exposure TRADED this
      // session, never from the exposure held.  A crowd that has been long for a month and is still
      // long is not buying, and a market it is not buying does not rise because of it.
      val eqFlow = w.crowdImpact * wTrend * (crowdE - crowdPrev)
      crowdPrev = crowdE
      logVol = w.volPersist * logVol + w.volOfVol * rng.randn()
      // TRANSIENT, deliberately: the kick multiplies THIS session's diffusive noise and never
      // enters `logVol` -- fed into the 0.99-persistent state it self-excites (log-vol responds
      // per session while the normalising `scale` EWMA lags ~140, so every expansion reads as
      // fresh declines and pumps itself; measured: vol 16% -> 45% at the setting that first
      // reaches the anchor).  The lag-1 form is also the statistic the `leverage corr` row
      // grades; the multi-session persistence of real post-decline volatility is the spiral's
      // job, and the clustering rows hold the total.
      val dNoise0 = newsDamp * SigmaN * math.exp(logVol - volNorm) * rng.randn()
      val dNoise  = if w.leverage > 0.0 then dNoise0 * math.exp(w.leverage * levSig) else dNoise0
      // The session's DIFFUSION SCALE, recorded for the range and satellite channels exactly
      // as the noise term above is built -- news damp, vol state, leverage kick (read
      // BEFORE this session's update, like `dNoise` itself) -- plus the jump branch's
      // sqrt(1 - jumpVar) mixing.  Draw-free; 0.0 when both channels are off.
      val sessSigma =
        if w.rangeScale > 0.0 || w.satBeta > 0.0 then
          val levMult = if w.leverage > 0.0 then math.exp(w.leverage * levSig) else 1.0
          val jvMult  = if w.jumpVar > 0.0 then math.sqrt(1.0 - w.jumpVar) else 1.0
          newsDamp * SigmaN * math.exp(logVol - volNorm) * levMult * jvMult
        else 0.0

      // The jump channel.  Its draws come from `jrng`, NOT `rng`, so `jumpVar = 0` takes the
      // untouched branch below and moves NOTHING ELSE in the path -- the failure mode a shared
      // stream would have caused is not a risk that was reasoned about, it is one the branch
      // removes.  (Through 0.21.0 that also made `-jumpvar 0` reproduce the pre-jump world bit for
      // bit; 0.22.0 changed the price-impact law, so the isolation claim now holds only WITHIN a
      // release.)  `volMult` is this session's volatility state, so jumps CLUSTER
      // inside a stressed stretch instead of scattering uniformly, which is what turns a fat tail
      // into a survivable-or-not sequence for anything levered.
      val eqShock =
        if w.jumpVar <= 0.0 then dNoise
        else
          val volMult  = math.exp(logVol - volNorm)
          val lamNow   = math.min(0.25, w.jumpRate * math.pow(volMult, JumpGamma))
          val scale    = jumpScale(w)
          // The compensator is deterministic and consumes no draw: it removes the mean the
          // downward shift would otherwise add, so `jumpVar` moves the tail without moving drift.
          val compens  = w.jumpRate * w.jumpSkew * scale
          val fired    = jrng.nextDouble() < lamNow
          val jump =
            if !fired then 0.0
            else
              // Student-t with JumpNu degrees of freedom, standardised to unit variance, so the
              // size is set by `scale` alone.  Drawn as z / sqrt(chi2(nu)/nu) -- the draw ORDER
              // here is part of the cross-language contract, not an implementation detail.
              val z = jrng.randn()
              var chi = 0.0
              var k = 0
              while k < JumpNu do
                val g = jrng.randn()
                chi += g * g
                k += 1
              val t = z / math.sqrt(chi / JumpNu) / math.sqrt(JumpNu / (JumpNu - 2.0))
              (t - w.jumpSkew) * scale
          dNoise * math.sqrt(1.0 - w.jumpVar) + jump + compens
      // The shock, not the crowd's flows -- see the `downShock` field for the measured reason.
      val eqShockA =
        if w.downShock > 0.0 then
          if eqShock < 0.0 then eqShock * (1.0 + w.downShock) else eqShock / (1.0 + w.downShock)
        else eqShock

      // ---- both markets step through the SAME mechanism --------------------------------------
      // THE SLOW VALUATION CYCLE: value capital arbs the gap to PERCEIVED fair, and perception
      // drifts toward realized prices with a `beliefYears` half-life.  At 60 sessions the belief
      // has moved ~5% of a gap, so daily reversion -- and the variance-ratio band -- are
      // untouched; over years the effective pull on a PERSISTENT gap falls to (1 - beliefShare)
      // of full strength, which is what lets CAPE-scale swings build and is why no dial could buy
      // dispersion without breaking the 60d band (measured 2026-08-30: the whole vr60 budget
      // bought +0.01 of sd).  A collapsing fundamental still transmits at full strength -- the
      // belief lags it by years.  Updated from the PREVIOUS session's gap, consumes no draws,
      // and at beliefShare 0 the perceived fair IS the fundamental, bit for bit.
      // The mania term: beliefs capitalize `capYears` of the fundamental's recent EXCESS growth
      // (read through a one-year EWMA) into the fair value the pull aims at.  During a high-drift
      // regime perceived fair rides above the fundamental and the price follows; the regime
      // ending on its re-draw is a valuation decline with the fundamental untouched.
      if w.capYears > 0.0 then
        if i > 0 then gEwma += gMu * ((logVbase - vPrev) - gEwma)
        vPrev = logVbase
      val perceivedFair =
        if w.beliefShare <= 0.0 && w.capYears <= 0.0 then logVbase
        else
          var pf = logVbase
          if w.beliefShare > 0.0 then
            belief += beliefMu * ((eqM.logP - logVbase) - belief)
            pf += w.beliefShare * belief
          if w.capYears > 0.0 then
            // tanh-squashed at CapSpan: extrapolated growth prices a mania, never an infinity --
            // a lucky regime draw must not walk perceived fair past anything the record holds.
            pf += CapSpan * tanhP(w.capYears * (gEwma * DaysPerYear - w.drift) / CapSpan)
          pf
      val sPre = if w.leverage > 0.0 then math.sqrt(eqM.scaleVar) else 0.0
      val retE = eqM.step(perceivedFair, eqFlow + eqShockA)
      if w.leverage > 0.0 then
        // SATURATED at four realized sds, and the cap is a priced trade, not a free guard:
        // uncapped, a jump day mints a 2.6x next-session multiplier and the kurtosis ceiling
        // flips on seed draws; capped, roughly a third of the graded correlation goes with those
        // co-extreme pairs (-0.09 -> -0.06 at leverage 0.05, measured) and the dial is sized
        // about 2x larger to buy it back.  Real vol responses saturate; uncapped ones let one
        // draw author the tail.
        // The decline the signal reads INCLUDES this session's news jump: a bad-news day is
        // exactly the day real volatility responds to, and the external repricing bypasses
        // `retE` (it never passes through `step`).  `newsJ` is 0 whenever the channel is off,
        // so the pre-news leverage behaviour is untouched bit for bit.
        levSig = math.min(math.max(newsJ - retE, 0.0) / sPre, 4.0) - 0.399
      // joint-stress margin selling: when both markets are stressed, the bond gets dumped too --
      // and against it the refuge bid, flight-to-quality into a bond that is itself still orderly.
      // DURATION-SCALED, like the bond's own noise: an absolute bid gave a 5-year bond the same
      // crash rally as a 20-year one, which no duration-relative band can then fit.
      // The stress the REFUGE bid reads: settled (through yesterday) when `refugeDays` is on,
      // live otherwise -- see the `refugeDays` field for why the same-session delta is the whole
      // calm-day correlation and the level is the whole crisis behaviour.  The EWMA is updated
      // AFTER this use, so today's equity move never reaches today's bond bid.
      val eqStressForRefuge = if w.refugeDays > 0.0 then settledStress else eqM.stressIdx
      val bondFlow = -w.margin * eqM.stressIdx * bdM.stressIdx +
                     w.refuge * (w.duration / DurationRef) * eqStressForRefuge *
                       math.max(0.0, 1.0 - bdM.stressIdx)
      if w.refugeDays > 0.0 then settledStress += settleMu * (eqM.stressIdx - settledStress)
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
      if chOn then
        chPx(i) = eqM.logP - markdown
        chD(i) = sessSigma * eqM.lastLiq
        chState(i) = math.exp(logVol - volNorm) * eqM.lastLiq * w.depth / 12.0
        chSv(i) = eqM.scaleVar

      // ---- capital reallocation: spring, scored on positions actually held -------------------
      perfV = 0.99 * perfV + 0.01 * (mispricingPre * retE) * 100.0
      // POSITION HELD, where the price impact above is position TRADED -- both are correct and
      // they are different questions.  A crowd earns or loses on what it is holding; it moves the
      // price by what it is buying or selling.  Conflating the two is the defect that shipped
      // through 0.21.0.
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
      if i == BurnIn then
        clampsAtBurn = eqM.clamps + bdM.clamps
        eqFloorAtBurn = eqM.floorDays; eqTailAtBurn = eqM.tailDays
        eqHaltAtBurn = eqM.haltDays
      i += 1

    val path = Path(px.drop(BurnIn), rt.drop(BurnIn), fv.drop(BurnIn), lq.drop(BurnIn), bq.drop(BurnIn),
         bp.drop(BurnIn), ip.drop(BurnIn), cp.drop(BurnIn),
         wTrendSum / n, pinnedCnt.toDouble / n, satCnt.toDouble / n,
         eqM.clamps + bdM.clamps - clampsAtBurn,
         eqM.floorDays - eqFloorAtBurn, eqM.tailDays - eqTailAtBurn,
         eqM.haltDays - eqHaltAtBurn,
         bondStressSum / n, bondStressHi.toDouble / n, w.duration, crowdFlowSum / n,
         disasterCount,
         Array.emptyDoubleArray, Array.emptyDoubleArray, Array.emptyDoubleArray,
         Array.emptyDoubleArray)
    Priced(path, ChannelInputs(chPx, chD, chState, chSv))

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

  /** Var(sum of q consecutive returns) / (q * Var(r)) on SIGNED returns: 1.0 under no serial
    * dependence at that horizon, above 1 for trend, below for mean reversion.  The two `clustering`
    * rows measure |r| and are blind to this — a world can cluster its volatility exactly right
    * while manufacturing a trend no market has, and one did for four releases.
    *
    * WHY A VARIANCE RATIO AND NOT AN AUTOCORRELATION.  A signed autocorrelation at any single lag
    * cannot see this defect: the shipped-0.21.0 world reads about +0.01 at every lag out to 60,
    * which is inside the sampling noise of a 100-year path and would pass a per-lag check at every
    * lag separately.  They are all the SAME SIGN, so they accumulate — the 60-session variance is
    * 52% above iid while no single lag looks unusual.  A crowd trading a 60-session signal is
    * visible here and nowhere else.
    *
    * CONVENTION, stated for the same reason `clustering lag 1` states one, because "variance ratio"
    * names several estimators that disagree in small samples: NON-OVERLAPPING q-blocks, sample
    * variances (n-1), the series truncated to a whole number of blocks.  Overlapping blocks
    * estimate the same population quantity with lower variance and a different finite-sample value;
    * `jsrc/clusteringAnchor.sc` calls THIS function to measure the anchor, so the two cannot drift.
    */
  def varianceRatio(r: Array[Double], q: Int): Double =
    val n = r.length / q * q
    if q < 2 || n < 2 * q then Double.NaN
    else
      def sampleVar(x: Array[Double]): Double =
        val z = MatD(x) - MatD(x).mean
        z.power(2).sum / (x.length - 1)
      val daily  = r.take(n)
      val blocks = Array.tabulate(n / q)(k => daily.slice(k * q, (k + 1) * q).sum)
      val vDaily = sampleVar(daily)
      if vDaily <= 0.0 then Double.NaN else sampleVar(blocks) / (q * vDaily)

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

  /** Real equity funds' time under water, stated against the part a random walk already explains.
    *
    * For a geometric random walk the share of sessions more than `rung` below the running peak has
    * a closed form: `exp(-2 * (mu/sigma^2) * ln(1/(1-rung)))`, which in the units this report
    * already carries is `exp(-2 * retVol * ln(1/(1-rung)) / vol)`.  It is EXACT, not fitted, and
    * that is what makes it the right carrier for the return dependence.  The model runs at a return
    * per unit volatility near 0.8 while the anchor funds span 0.20-0.53, so a fitted `rv`
    * coefficient evaluated at the model's own operating point is arithmetic with nothing behind it.
    * A closed form is not.
    *
    * What real markets add is that they make new highs SOONER than chance, and increasingly so the
    * calmer they are.  That correction is the fitted part: linear in volatility, one pair per rung,
    * from `test-data/equity-anchors` (35 instruments, 2001-2026, peaks seeded from full prior
    * history).  The reason to believe the FORM rather than just the fit: all three rungs
    * independently reach 1.00 at the top of the real volatility range.  The most volatile equity
    * markets spend random-walk time under water; a market at 14% volatility spends about half of it.
    *
    * Fitted by least squares on the LOG ratio, because the quantity is graded AS a ratio.  On OLS
    * over the raw ratio the deep rung's line is pulled up by a two-instrument dot-com tail (XLK,
    * QQQ) until the median real instrument sits at 0.91 of it -- and a target of 1.00 against that
    * line would once again ask the model to be deeper than a typical real fund, which is the defect
    * this relation exists to remove.  On the log fit every rung's median real row is 1.00. */
  val EquityD5Corr  = (0.4003, 0.01628)
  val EquityD10Corr = (0.1861, 0.02196)
  val EquityD20Corr = (-0.0544, 0.02759)

  /** Share of sessions more than `rung` below the running peak for a geometric random walk with
    * this volatility and return per unit volatility.  Closed form; nothing here is fitted. */
  def gbmDepthShare(rung: Double, volPct: Double, retVol: Double): Double =
    if volPct <= 0.0 then Double.NaN
    else math.exp(-2.0 * retVol * math.log(1.0 / (1.0 - rung)) / (volPct / 100.0))

  /** What a real equity fund of this volatility and return spends more than `rung` below its peak.
    * NaN where the correction is non-positive -- below ~2% volatility for the deep rung, far under
    * any equity this relation was fitted from, but a ratio against a non-positive prediction is not
    * a finding and must not print as one. */
  def equityDepthExpected(rung: Double, corr: (Double, Double), volPct: Double,
                          retVol: Double): Double =
    val c = corr._1 + corr._2 * volPct
    if c <= 0.0 then Double.NaN else c * gbmDepthShare(rung, volPct, retVol)

  /** The volatility range the anchor instruments covered, in %.  Outside it the correction is a
    * line extended past its evidence, so both graders refuse rather than manufacture a verdict --
    * the same refusal the bond relations already make.  `EquityAnchorSuite` pins this to the
    * fixture's own min and max. */
  val EquityVolSupport = (14.3, 37.4)

  /** Bands for the two graded rungs, shared by the acceptance gate and `-crossasset`.  Each is the
    * observed residual-ratio range over BOTH windows, rounded outward to the nearest 0.05 -- 0.785
    * (DIA) to 1.254 (EWJ) at the 5% rung, 0.719 (XLY) to 1.520 (XLK) at the 10% -- because these
    * funds ARE the scope, unlike the bond bands where the range is a scope decision that excludes
    * high yield.  A band that excluded one of them would be calling a real equity fund unrealistic.
    *
    * The 20% rung is deliberately NOT gated.  Its relation does not transport (R^2 0.25-0.41 to the
    * independent window, against 0.66-0.73 for the other two) and a band admitting every real
    * instrument would have to span 0.35-2.60, which cannot fail: that is a check that reads as
    * verification while testing nothing.  It stays a fit target and a reported number. */
  val EquityD5Band  = (0.75, 1.30)
  val EquityD10Band = (0.70, 1.55)

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

  /** The horizon the variance ratio is graded at, in sessions — three months.  The choice is not
    * free and it is not the flattering one: q = 20 would let this world through (its reading
    * overlaps the CRSP century's 1.166), and q = 252 is too noisy to band (real readings run
    * 0.27-1.45).  At 60 the real record is tight and this model is outside all of it.
    *
    * It is also the momentum crowd's own lookback, which is the mechanism the row exists to hold
    * accountable.  That is the direction that matters: a horizon chosen to spare the mechanism
    * would be a longer or shorter one, and both were available. */
  val VarRatioQ = 60
  /** The ladder `-validate` prints and the profile row grades -- the four horizons of
    * `persistence-2026-09-02.tsv`.  `VarRatioQ` is the rung the loss row reads. */
  val VarRatioLadder: Vector[Int] = Vector(20, 60, 120, 250)

  /** The variance-ratio envelopes, from `test-data/equity-anchors/persistence-2026-09-02.tsv`:
    * 18 real equity funds over their full histories and over the depth cross-section's own
    * 2001-2026 window, plus the CRSP value-weighted market opening in 1926, 1954 and 1990, at
    * four horizons.  At 60 sessions the 39 readings span 0.547 (XLV, 2001-2026) to 1.175 (the
    * CRSP century), and each envelope is its rung's range rounded outward to the nearest 0.05.
    * `PersistenceAnchorSuite` re-derives every bound from the file by that rule, so a band cannot
    * be widened to admit a world without a real market moving first.
    *
    * SHARED across anchor sets rather than carried per asset, unlike the two bands in `Anchors`.
    * What separates these readings is the ERA, not the index: QQQ reads 0.720 against SPY's 0.705
    * over their full histories, while the same market reads 1.14 over the century and 0.82 since
    * 1990.  A per-asset band would encode a difference the record does not show. */
  /** Per-rung envelopes of the real cross-section -- 39 readings, 18 instruments over two windows
    * and three CRSP eras -- the observed range rounded outward to 0.05, re-derived by
    * `PersistenceAnchorSuite`.  The two long rungs cannot discriminate: at 250 sessions the
    * record itself spans 0.24-1.56.  They are graded anyway, inside ONE profile row with the
    * slopes below, so a world clears the ladder as a shape and never rung by rung. */
  val VarRatioBands: Vector[(Int, Double, Double)] =
    Vector((20, 0.65, 1.20), (60, 0.50, 1.20), (120, 0.40, 1.35), (250, 0.20, 1.60))
  /** Adjacent-rung slopes vr(60)-vr(20) and vr(120)-vr(60), the cross-section's range rounded
    * outward: the profile's SHAPE, which four boxes cannot see -- a world at 0.70 and 1.15 on the
    * two short rungs sits inside both boxes and outside every real profile.  The 120->250 slope
    * spans -0.75..+0.71 in the record and grades nothing. */
  val VarRatioSlopeBands: Vector[(Int, Int, Double, Double)] =
    Vector((20, 60, -0.25, 0.10), (60, 120, -0.30, 0.20))
  /** Admissible sd of log(price/fair): the record's CAPE-proxy windows read 0.24-0.41, the floor
    * carries the stated proxy haircut, and the ceiling is past the century with room.  See the
    * `valuation dispersion` gate row and valuation-2026-08-30.tsv. */
  val ValDispBand = (0.15, 0.55)

  // ---- world statistics and the ONE acceptance predicate -------------------------------------
  /** The SATELLITE leg's own statistics, as RATIOS to the primary leg's -- present only when the
    * leg ran.  Ratios, not levels, because the satellite is a coupled second leg at this world's
    * own scale and is not claimed to BE any index: what a second, higher-beta leg must satisfy is
    * a RELATION to its primary, the same doctrine the depth rungs use when they grade each world
    * at its own volatility.  The record's relation is QQQ against SPY over their shared window. */
  final case class SatStats(corr: Double, absCorr: Double, beta: Double, volRatio: Double,
                            kurtRatio: Double, ac1Ratio: Double, ac20Ratio: Double,
                            d5Ratio: Double, d10Ratio: Double, crashRatio: Double)

  /** The BAR channels' statistics -- present only when the range channel ran.  `vol*` are NaN
    * unless the volume channel ran too.  Graded against `bars-2026-09-01.tsv`, whose rows the
    * build-time suites already assert; these carry the same readings into the RUNTIME gate. */
  final case class BarStats(rangeOverCcvol: Double, rangeAcf1: Double, rangeDownup: Double,
                            volSd: Double, volCorrRange: Double)

  final case class WorldStats(vol: Double, kurt: Double, ac1: Double, ac20: Double,
                              vr20: Double, vr60: Double,   // SIGNED-return persistence at each
                              vr120: Double, vr250: Double, // rung of `VarRatioLadder` -- `varianceRatio`
                              annRet: Double,
                              nEpisodes: Int, epPerPath: Double, depthMed: Double, worstDepth: Double,
                              vCount: Int, midCount: Int, uCount: Int, nShapes: Int, censored: Int,
                              clampPct: Double,
                              haltPct: Double,       // share of equity sessions the halt bound
                              tailFloorPct: Double,  // share of EQUITY tail sessions sitting ON the
                                                     // downward guard: the guard's grip on the tail,
                                                     // which `clampPct` cannot see
                              trendShare: Double, yearsPerPath: Double,
                              trendPinned: Double, targetSat: Double,
                              bondVol: Double, bondGrowth: Double, bondInfl: Double,
                              corrCalm: Double, corrInfl: Double,
                              meanBondStress: Double, pctBondStress: Double, crowdFlow: Double,
                              disPerCentury: Double,
                              valDisp: Double,    // median per-path sd of log(price/fundamental):
                                                  // the valuation-gap dispersion the record proxies
                                                  // with CAPE (valuation-2026-08-30.tsv)
                              maxOver: Double,    // median per-path MAX log overvaluation -- the
                                                  // mania a century produces
                              semiExcess: Double, // median per-path 100*(sqrt(sum r^2|r<0 / sum
                                                  // r^2|r>0) - 1), tau = 0: how much more the
                                                  // downside disperses (asymmetry-2026-08-31.tsv)
                              levCorr: Double,    // median per-path corr(r_t, r^2_{t+1}) -- the
                                                  // leverage effect at daily lag; the sharper
                                                  // signed-half regression CANNOT anchor on
                                                  // close-only data (era-split, same fixture)
                              tailHedge: Double,  // median per-path stock-bond corr on CALM
                                                  // sessions with r_eq below its calm q10 --
                                                  // calm-conditioned because the record window
                                                  // (TLT's history) is one disinflation era
                              duration: Double,
                              inflAnn: Double,
                              // depth profile: median share of sessions more than 5/10/20% below
                              // the running peak, equity leg then bond leg
                              ddEq5: Double, ddEq10: Double, ddEq20: Double,
                              ddBd5: Double, ddBd10: Double, ddBd20: Double,
                              // None when no satellite leg / no range channel ran -- the gate then
                              // carries no such rows at all, which is what keeps a channels-off
                              // world's verdict byte-identical.
                              sat: Option[SatStats] = None,
                              bars: Option[BarStats] = None,
                              // median across paths of the per-path mean session yield, %/yr;
                              // NaN when the dial is off, and the gate then carries no row
                              divYieldMean: Double = Double.NaN):
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

    /** Time spent more than a rung below the running peak, RELATIVE to what a real equity fund of
      * this world's OWN volatility and return per unit volatility spends -- see `EquityD10Corr`.
      * 1.0 means the market is under water as long as a real one it could be mistaken for.
      *
      * Replaces three absolute levels that were SPY's, measured at SPY's operating point (18.6%
      * volatility, 0.55 return per vol) while the same target set asks this model to run at 16% and
      * 0.69.  Real funds at THAT point spend 1.11x / 1.33x / 1.64x less time under water than
      * SPY's levels demanded, so the old targets could only be met by a market too deep for its own
      * volatility -- and were. */
    def eqDepthVsReal(rung: Double, corr: (Double, Double), got: Double): Double =
      val expected = equityDepthExpected(rung, corr, vol * 100.0, retVol)
      if expected.isNaN || expected <= 0.0 then Double.NaN else got / expected
    def eqD5VsReal: Double  = eqDepthVsReal(0.05, EquityD5Corr, ddEq5)
    def eqD10VsReal: Double = eqDepthVsReal(0.10, EquityD10Corr, ddEq10)
    def eqD20VsReal: Double = eqDepthVsReal(0.20, EquityD20Corr, ddEq20)

  /** Median over paths, dropping non-finite -- the same rule `measure`'s local `med` applies. */
  private def medOf(v: Seq[Double]): Double =
    val f = v.filter(_.isFinite)
    if f.isEmpty then Double.NaN else f.sorted.apply(f.size / 2)

  /** The satellite leg's statistics as ratios to the primary's -- `None` when no leg ran, so a
    * satellite-off world produces exactly the rows it always did.
    *
    * Every ratio is a MEDIAN over paths of that path's own ratio, not a ratio of pooled medians:
    * the two differ when the legs' dispersions differ, and the per-path form is the one the
    * record's single history is a draw from. */
  def satStats(sims: Vector[Path]): Option[SatStats] =
    if sims.isEmpty || sims.head.sat.isEmpty then None
    else
      val per = sims.map { s =>
        val rp = dailyReturns(s.price); val rs = dailyReturns(s.sat)
        val mp = rp.sum / rp.length; val ms = rs.sum / rs.length
        var cov = 0.0; var varP = 0.0; var varS = 0.0
        var i = 0
        while i < rp.length do
          cov += (rp(i) - mp) * (rs(i) - ms)
          varP += (rp(i) - mp) * (rp(i) - mp)
          varS += (rs(i) - ms) * (rs(i) - ms)
          i += 1
        val (p5, p10, _) = depthShares(s.price)
        val (s5, s10, _) = depthShares(s.sat)
        val ep = episodes(s.price, 15.0).size.toDouble
        val es = episodes(s.sat, 15.0).size.toDouble
        (pearson(rp, rs), pearson(rp.map(math.abs), rs.map(math.abs)), cov / varP,
         math.sqrt(varS / varP), kurtosis(rs) / kurtosis(rp),
         autocorrAbs(rs, 1) / autocorrAbs(rp, 1), autocorrAbs(rs, 20) / autocorrAbs(rp, 20),
         s5 / p5, s10 / p10, if ep > 0.0 then es / ep else Double.NaN)
      }
      Some(SatStats(medOf(per.map(_._1)), medOf(per.map(_._2)), medOf(per.map(_._3)),
                    medOf(per.map(_._4)), medOf(per.map(_._5)), medOf(per.map(_._6)),
                    medOf(per.map(_._7)), medOf(per.map(_._8)), medOf(per.map(_._9)),
                    medOf(per.map(_._10))))

  /** The bar channels' statistics -- `None` when no range channel ran. */
  def barStats(sims: Vector[Path]): Option[BarStats] =
    if sims.isEmpty || sims.head.logHi.isEmpty then None
    else
      val per = sims.map { s =>
        val r = dailyReturns(s.price)
        val x = Array.tabulate(s.logHi.length)(i => s.logHi(i) - s.logLo(i))
        val mx = x.sum / x.length
        val mr = r.sum / r.length
        val sr = math.sqrt(r.map(v => (v - mr) * (v - mr)).sum / r.length)
        // The bar's return is measured over the SAME window the bar spans (open = prior close),
        // so the sign that conditions the range is `r` shifted by one: bar i spans price i-1..i.
        val dn = (1 until x.length).filter(i => r(i - 1) < 0.0).map(i => x(i))
        val up = (1 until x.length).filter(i => r(i - 1) > 0.0).map(i => x(i))
        val du = if dn.nonEmpty && up.nonEmpty then (dn.sum / dn.size) / (up.sum / up.size)
                 else Double.NaN
        val (vsd, vcx) =
          if s.logVolume.isEmpty then (Double.NaN, Double.NaN)
          else
            val mv = s.logVolume.sum / s.logVolume.length
            (math.sqrt(s.logVolume.map(v => (v - mv) * (v - mv)).sum / s.logVolume.length),
             pearson(s.logVolume, x))
        (mx / sr, pearson(x.dropRight(1), x.drop(1)), du, vsd, vcx)
      }
      Some(BarStats(medOf(per.map(_._1)), medOf(per.map(_._2)), medOf(per.map(_._3)),
                    medOf(per.map(_._4)), medOf(per.map(_._5))))

  def measure(sims: Vector[Path], years: Int): WorldStats =
    val rets = sims.map(s => dailyReturns(s.price))
    // `isFinite`, not `!isNaN`: an infinite path is no more a datum than a NaN one, and `pctile`
    // drops the same set, so a median and the percentiles printed beside it describe the same paths.
    def med(v: Seq[Double]) = { val f = v.filter(_.isFinite); if f.isEmpty then Double.NaN else f.sorted.apply(f.size / 2) }
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
    // POOLED, not a median of per-path shares: most paths hold no tail session at all, so a median
    // would read 0 forever and the check built on it could not fail.
    val tailSessions = sims.map(_.eqTailDays.toLong).sum
    val tailFloorShare =
      if tailSessions <= 0L then 0.0
      else sims.map(_.eqFloorDays.toLong).sum * 100.0 / tailSessions

    WorldStats(
      vol  = med(rets.map(r => math.sqrt(MatD(r).power(2).mean * DaysPerYear))),
      kurt = med(rets.map(kurtosis)),
      ac1  = med(rets.map(r => autocorrAbs(r, 1))),
      ac20 = med(rets.map(r => autocorrAbs(r, 20))),
      vr20  = med(rets.map(r => varianceRatio(r, 20))),
      vr60  = med(rets.map(r => varianceRatio(r, VarRatioQ))),
      vr120 = med(rets.map(r => varianceRatio(r, 120))),
      vr250 = med(rets.map(r => varianceRatio(r, 250))),
      annRet = med(sims.map(s => math.log(s.price.last / s.price.head) / years * 100.0)),
      sat = satStats(sims), bars = barStats(sims),
      divYieldMean = med(sims.map(s => if s.divYield.isEmpty then Double.NaN
                                      else s.divYield.sum / s.divYield.length)),
      nEpisodes = eps.size, epPerPath = eps.size.toDouble / sims.size,
      depthMed = med(eps.map(_.depthPct)), worstDepth = eps.map(_.depthPct).minOption.getOrElse(Double.NaN),
      vCount = shapes.count(_ > 1.5), midCount = shapes.count(x => x >= 0.67 && x <= 1.5),
      uCount = shapes.count(_ < 0.67), nShapes = shapes.size, censored = eps.count(_.censored),
      clampPct = sims.map(_.clampedDays.toLong).sum / days * 100.0,
      haltPct = sims.map(_.eqHaltDays.toLong).sum / days * 100.0,
      tailFloorPct = tailFloorShare,
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
      disPerCentury = sims.map(_.disasters.toDouble).sum / sims.size / years * 100.0,
      valDisp = med(sims.map { sp =>
        val g = Array.tabulate(sp.price.length)(i => math.log(sp.price(i) / sp.fundamental(i)))
        val m = g.sum / g.length
        math.sqrt(g.map(x => (x - m) * (x - m)).sum / (g.length - 1))
      }),
      maxOver = med(sims.map { sp =>
        var mx = Double.MinValue; var i = 0
        while i < sp.price.length do
          val v = math.log(sp.price(i) / sp.fundamental(i)); if v > mx then mx = v; i += 1
        mx
      }),
      semiExcess = med(sims.map { sp =>
        val r = dailyReturns(sp.price)
        val d = r.filter(_ < 0.0).map(x => x * x).sum
        val u = r.filter(_ > 0.0).map(x => x * x).sum
        if u > 0.0 then (math.sqrt(d / u) - 1.0) * 100.0 else Double.NaN
      }),
      levCorr = med(sims.map { sp =>
        val r = dailyReturns(sp.price)
        pearson(r.dropRight(1), r.drop(1).map(x => x * x))
      }),
      tailHedge = med(sims.map { sp =>
        val idx = (1 until sp.price.length).filter(i => sp.inflPress(i) <= 0.005)
        val re  = idx.map(i => math.log(sp.price(i) / sp.price(i - 1))).toArray
        val rb  = idx.map(i => math.log(sp.bond(i) / sp.bond(i - 1))).toArray
        val q   = pctile(re.toIndexedSeq, 0.10)
        val ta  = re.zip(rb).filter(_._1 < q)
        // A tail too small to correlate is unmeasurable, not zero -- the same rule the 24-year
        // bond windows apply.
        if ta.length < 30 then Double.NaN else pearson(ta.map(_._1), ta.map(_._2))
      }),
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

  /** The horizon the verdict ensemble runs at: every band and anchor weight was calibrated on
    * 100-year ensembles, and several graded statistics move with the measurement window -- the
    * valuation gap's dispersion is the sample sd of a near-integrated process (0.11 at 30 years,
    * 0.21 at 100, against floors set from the 100-year record), and the depth shares and
    * clustering carry the century's regime mix.  A fixed band read at the caller's `-years`
    * grades the horizon, not the world.  The report section still describes the caller's
    * ensemble; only the verdict is pinned. */
  val GateYears = 100

  /** The (paths, years) the verdict -- gate classes, fidelity table, every emitted sidecar -- is
    * measured on: `GateYears` always, on the larger of the report and `-emitgate` ensembles.
    * `-emitgate 0` is the caller's explicit request to grade the emitted ensemble itself,
    * caller's horizon and all.  Equal to (paths, years) exactly when the report ensemble already
    * is the verdict ensemble -- which at the defaults it is: same seed, same draws. */
  def verdictSpec(emitting: Boolean, emitGate: Int, paths: Int, years: Int): (Int, Int) =
    if emitting && emitGate == 0 then (paths, years)
    else if emitting && emitGate > paths then (emitGate, GateYears)
    else (paths, GateYears)

  /** TWO-SIDED wherever a plausible range exists.  History of this gate: a one-sided version
    * passed a 35%-volatility world (the one reversing the ranking); a "bonds fail" check written
    * as bondInfl < bondGrowth passed while bonds still RALLIED +2.8; crash frequency shipped
    * without an upper bound WHILE the one-sided lesson was being applied elsewhere in this file. */
  def gateChecks(a: Anchors, st: WorldStats): Vector[(String, Boolean, GateClass)] =
    import GateClass.*
    val base = Vector(
      // MEASURED, not assumed.  8-25% was the S&P's shape and it asserted of 17 of the 35 real
      // equity instruments in `test-data/equity-anchors` that they are not markets -- QQQ (26.9%),
      // Taiwan, Brazil, semiconductors, energy and most of Europe.  That is the same failure the
      // bond band below already records ("of eight real funds it admitted one").  A REALISM band
      // answers "is this a market at all", so it must admit every market anyone has measured: the
      // 35 instruments span 15.2-37.4% over the clean w1996 window, and 8-40 rounds outward from
      // that.  The FIDELITY band -- now `Anchors.volBand`, 14-18% for the S&P and 24-30% for the
      // Nasdaq -- is what answers "is this THIS market", and it stayed narrow.
      bandCheck("equity vol",       st.vol * 100.0, 8.0, 40.0, Realism, dp = 0, unit = "%"),
      bandCheck("kurtosis",         st.kurt, 4.0, 30.0, Realism, dp = 0),
      ("clustering 0.10-0.40",      st.ac1 > 0.10 && st.ac1 < 0.40 && st.ac20 > 0.03, Realism),
      // Widened from 8-45 for the same reason as the volatility band above: 45 excluded two of
      // the 35 real instruments (EWA, EWW), which read 49.4 and 46.6 over the clean w1996 window
      // against a cross-section range of 13.2-49.4.  A band that calls a real market unreal is
      // not a realism check.
      ("crash rate 8-55/century",   st.epPerPath >= 1.0 && {
          val pc = st.epPerPath * 100.0 / st.yearsPerPath; pc >= 8.0 && pc <= 55.0 }, Realism),
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
      // THE DENOMINATOR IS THE POINT.  `clampPct` measures the guard against ALL sessions, where it
      // is negligible by construction and passes in worlds whose worst sessions are ENTIRELY its
      // doing.  This measures it against the tail it actually touches.  Both are kept: one says the
      // guard is not distorting the body, the other that it is not authoring the tail.
      ("clamp shapes no tail",      st.tailFloorPct < 2.0, Realism),
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
      // Two-sided like the spiral's: the channel must strike, and disasters that arrive more than
      // a few times a century are not disasters -- they are a second volatility regime wearing the
      // name.  An off-world (rate 0) fails this row, which is what a mechanism row MEANS.
      ("macro disasters strike, not every decade",
        st.disPerCentury > 0.05 && st.disPerCentury < 4.0, Mechanism),
      // The valuation cycle's engagement row.  The floor fails a world without the mechanism (the
      // disaster-only default read 0.095); the ceiling is the unmoored guard -- a dispersion past
      // 0.70 means perceived fair has lost the fundamental (the -beliefshare domain refuses >= 1
      // for the same reason at the CLI).
      ("valuation cycle engages, not unmoored",
        st.valDisp > 0.13 && st.valDisp < 0.70, Mechanism),
      bandCheck("inflation",        st.inflAnn, 1.0, 6.0, Realism, dp = 0, unit = "%/yr"),
      // LEVEL bands, not realism.  A 12%-volatility market is still a market, and realism is
      // ALWAYS required — either band placed there would make the sweep's own OFF-worlds
      // inadmissible in every report ("no liquidity spiral" runs at 12.6% vol, "low growth" at
      // 0.34).  Class does not weaken them as a search constraint: the calibration loss counts
      // 0.5 per failed check whatever the class.  Volatility keeps its realism band as well —
      // 8-40% answers "is this a market", the anchor's own band "can its level be read".
      bandCheck("equity vol", st.vol * 100.0, a.volBand._1, a.volBand._2, Fidelity, dp = 0, unit = "%"),
      // 0.50 clears the 1926-2026 reading (0.55) downward; 0.85 sits above the 1954-2026 anchor
      // (0.69) and below the most favourable non-overlapping 20-year block the record produced
      // (0.93).  A world may be as favourable as a long-horizon market, not as favourable as its
      // luckiest two decades.  The 20-year block SPREAD (0.47-0.93) is deliberately NOT the band:
      // that is sampling variation in a 20-year window, and this statistic is a population value
      // over 20,000 path-years -- a band drawn from it would readmit worlds at 0.91.
      bandCheck("return per vol",   st.retVol, a.retVolBand._1, a.retVolBand._2, Fidelity),
      // SIGNED persistence at three months.  FIDELITY and not realism, for the reason stated
      // above: `-crowdimpact 0.12` is one of the sweep's own OFF-worlds — pressing the reflexive
      // channel hard is what it is FOR — and a realism band would make it inadmissible in every
      // report rather than describing it.  What a failure here costs is specific and large: every
      // trailing-window statistic read off this world is read against the wrong null.  A momentum
      // rule's information coefficient, a p-value calibrated on synthetic paths, a
      // drawdown-conditioned hazard — all of them inherit the trend this row measures, and none of
      // the other fifteen targets can see it.
      varRatioProfileCheck(st),
      // Anchored on the record's CAPE dispersion (valuation-2026-08-30.tsv: 0.24-0.41 across
      // windows).  A BAND, never a point ratio: the record has no observable fair value and CAPE
      // is a proxy, so the floor sits a stated haircut below the calmest window -- far enough
      // that only the mechanism's absence fails it, close enough that it discriminates (the
      // 0.22.1 world reads 0.095-0.102 and FAILS).
      bandCheck("valuation dispersion", st.valDisp, ValDispBand._1, ValDispBand._2, Fidelity),
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
    // The equity depth relation is anchor-fitted too, so it refuses outside its anchors' volatility
    // range for the same reason the two above do.  That range starts at 14.3%, so the sweep's own
    // calm off-worlds are disclosed rather than failed -- "no fund this quiet was measured" is not
    // the same finding as "this market's drawdowns are wrong".
    val eqDepthBands =
      if anchored(st.vol * 100.0, EquityVolSupport, st.eqD10VsReal) then
        Vector(bandCheck("equity d5 vs real",  st.eqD5VsReal,  EquityD5Band._1,  EquityD5Band._2,  Fidelity),
               bandCheck("equity d10 vs real", st.eqD10VsReal, EquityD10Band._1, EquityD10Band._2, Fidelity))
      else Vector.empty
    // THE SATELLITE LEG, graded -- present only when a leg ran, so a satellite-off world's gate
    // is byte-identical to what it always was.  Bit-identical-off is what makes a channel safe to
    // add and is ALSO what makes it invisible to a verdict computed from the primary alone; these
    // rows are the answer to that, so an emitted `logSat` is covered by the gate that travels
    // beside it rather than merely disclosed as uncovered.
    //
    // RATIOS to the primary leg, never levels.  The satellite is a coupled second leg at this
    // world's own scale and is not claimed to be any index, so what can be graded is the RELATION
    // a higher-beta second leg holds to its primary -- the same doctrine the depth rungs use when
    // they grade each world at its own volatility.  Anchors are QQQ against SPY over their shared
    // 1999-2026 window, and every band is that record reading widened to the spread its own
    // 5-year blocks show, because one history pins a ratio far more loosely than it pins a level.
    val satBands = st.sat match
      case None => Vector.empty
      case Some(sd) => Vector(
        bandCheck("satellite corr", sd.corr, 0.75, 0.95, Fidelity),
        bandCheck("satellite |r| corr", sd.absCorr, 0.65, 0.90, Fidelity),
        bandCheck("satellite beta", sd.beta, 1.00, 1.45, Fidelity),
        bandCheck("satellite vol ratio", sd.volRatio, 1.20, 1.60, Fidelity),
        // The record's 5y blocks read 0.55-1.12 on this ratio and QQQ's kurtosis is LOWER than
        // SPY's over the shared window (9.6 vs 14.3) -- a wide band because the record is wide,
        // not because the model needs room.
        bandCheck("satellite kurtosis ratio", sd.kurtRatio, 0.45, 1.20, Fidelity),
        bandCheck("satellite clustering-1 ratio", sd.ac1Ratio, 0.85, 1.20, Fidelity),
        bandCheck("satellite clustering-20 ratio", sd.ac20Ratio, 0.85, 1.40, Fidelity),
        bandCheck("satellite d5 ratio", sd.d5Ratio, 1.00, 1.70, Fidelity),
        bandCheck("satellite d10 ratio", sd.d10Ratio, 0.70, 2.20, Fidelity),
        // DISCLOSED TENSION, not a pass by construction: the model's leg opens ~1.6 crash
        // episodes per primary episode against the record's 1.17.  One history cannot resolve
        // this ratio at all -- SPY and QQQ show ~6 and ~7 episodes in 27 years, and the 5-year
        // blocks read 1.00-2.00 -- so the band admits the model while the central tendency stays
        // high.  It is here to catch a leg that crashes several times as often as its primary.
        bandCheck("satellite crash ratio", sd.crashRatio, 0.80, 2.40, Fidelity))
    // THE BAR CHANNELS, graded -- same reasoning, and the same bands the build-time suites
    // already assert from `bars-2026-09-01.tsv`, carried into the RUNTIME gate.
    val barBands = st.bars match
      case None => Vector.empty
      case Some(b) =>
        Vector(bandCheck("bar range vs cc vol", b.rangeOverCcvol, 1.00, 1.20, Fidelity),
               bandCheck("bar range clustering", b.rangeAcf1, 0.57, 0.77, Fidelity),
               // Against the INTRADAY ruler (1.109-1.142): the model has no overnight, so the
               // record's close-to-close down/up of 1.175-1.205 carries conditioning this bar
               // cannot have.
               bandCheck("bar range down/up", b.rangeDownup, 1.00, 1.30, Fidelity)) ++
        (if b.volSd.isFinite then
           Vector(bandCheck("bar volume sd", b.volSd, 0.40, 0.60, Fidelity),
                  bandCheck("bar volume vs range", b.volCorrRange, 0.44, 0.64, Fidelity))
         else Vector.empty)
    // THE DIVIDEND LEVEL, graded when the dial is on: the yield at fair value is an identity
    // parameter, so this row can only catch a dial set outside what the record's own annual
    // means span -- `dividend-2026-09-02.tsv`.
    val divBand =
      if st.divYieldMean.isFinite then
        Vector(bandCheck("dividend yield %", st.divYieldMean, a.divYieldBand._1, a.divYieldBand._2,
                         Fidelity, 1))
      else Vector.empty
    base ++ eqDepthBands ++ depthBand ++ volBand ++ satBands ++ barBands ++ divBand

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
    val eqVol = st.vol * 100.0
    val eqDepth =
      if anchored(eqVol, EquityVolSupport, st.eqD10VsReal) then Vector.empty
      else if eqVol < EquityVolSupport._1 || eqVol > EquityVolSupport._2 then
        Vector(f"equity d5 and d10 vs real (equity vol $eqVol%.2f%% outside the anchors' " +
               f"${EquityVolSupport._1}%.1f-${EquityVolSupport._2}%.1f%%)")
      else Vector("equity d5 and d10 vs real (no fitted value at this volatility)")
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
    eqDepth ++ depth ++ volPer

  /** A gate whose printed name is DERIVED from the bounds its predicate tests, so the two cannot
    * drift apart — the failure mode where a gate reads as bounds it does not enforce.  Every
    * two-sided band that can go through here does: a hand-written "0.65-1.35" inside a name is
    * the same defect this helper exists to prevent, wherever it is written.
    *
    * `dp` is printed PRECISION, not tolerance: the depth rungs read 0.215-0.415 and are quoted at
    * that precision in the CHANGELOG and the upgrade plan, while the duration ratios read
    * 0.70-1.10.  `unit` is whatever follows the band in the name.  A caller whose printed units
    * differ from the statistic's passes the CONVERTED value (`st.vol * 100` against 8-40), so the
    * band and the value compared against it are in the same units by construction.
    *
    * Two bands stay hand-written, because the name would stop describing the predicate if they
    * came through here: `clustering` also enforces an ac20 floor and `crash rate` also requires at
    * least one episode.  Both are two-sided with visible bounds; what they are not is one clause. */
  def bandCheck(name: String, got: Double, lo: Double, hi: Double, cls: GateClass,
                dp: Int = 2, unit: String = ""): (String, Boolean, GateClass) =
    val fmt = s"%.${dp}f"
    (s"$name ${fmt.format(lo)}-${fmt.format(hi)}$unit", got > lo && got < hi, cls)

  def vrOf(st: WorldStats, q: Int): Double = q match
    case 20  => st.vr20
    case 60  => st.vr60
    case 120 => st.vr120
    case 250 => st.vr250
    case _   => throw IllegalArgumentException(s"no variance-ratio rung at $q sessions")

  /** The variance-ratio ladder as ONE fidelity row: every rung inside its envelope and both short
    * slopes inside theirs.  The name is derived from the bounds, as `bandCheck`'s is, so it cannot
    * read as bounds it does not enforce; the report's `trend persistence` lines show which rung
    * or slope failed. */
  def varRatioProfileCheck(st: WorldStats): (String, Boolean, GateClass) =
    val rungs  = VarRatioBands.map((q, lo, hi) =>
      (f"${q}%dd $lo%.2f-$hi%.2f", vrOf(st, q) > lo && vrOf(st, q) < hi))
    val slopes = VarRatioSlopeBands.map { (a, b, lo, hi) =>
      val sl = vrOf(st, b) - vrOf(st, a)
      (f"$a%d->$b%d $lo%+.2f..$hi%+.2f", sl > lo && sl < hi)
    }
    (s"variance-ratio profile ${rungs.map(_._1).mkString(" ")}, slopes ${slopes.map(_._1).mkString(" ")}",
     (rungs ++ slopes).forall(_._2), GateClass.Fidelity)

  def failedIn(a: Anchors, st: WorldStats, cls: GateClass): Vector[String] =
    gateChecks(a, st).collect { case (n, false, c) if c == cls => n }

  /** Heading and what a failure costs, printed in this order.  Kept beside the enum so a new class
    * cannot be added without saying out loud which conclusions it kills. */
  val GateSections: Vector[(GateClass, String, String)] = Vector(
    (GateClass.Realism,   "realism bands",        "a failure here means this world is not a market"),
    (GateClass.Mechanism, "mechanism engagement", "a failure here means only that mechanism is inert"),
    (GateClass.Fidelity,  "level fidelity",       "a failure here means only that quantity's LEVEL cannot be read"),
  )

  /** Admissibility under the classes a report has declared it requires.  A class not required is a
    * class whose failures are disclosed and tolerated, which is the whole point of the split. */
  def gateOk(a: Anchors, st: WorldStats, required: Set[GateClass]): Boolean =
    gateChecks(a, st).forall((_, ok, c) => ok || !required.contains(c))

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
    * distribution read three times), scope, and importance.  `kurtosis` keeps the 0.5 it was
    * given as a recorded exclusion: it is no longer excluded, but it is still ONE number
    * summarising a whole tail, and the judgment was never only about scope.
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

  /** WHICH REAL ASSET a world is being graded against.
    *
    * Every equity fidelity target was the S&P's, hard-coded, so a world calibrated to any other
    * index failed the target set for BEING that other index — it could be run but not graded, and
    * `-calibrate` could not search for one at all.  This makes the asset a parameter.
    *
    * Only the EQUITY rows vary.  The bond targets stay literal in `fitTargets`: the refuge asset is
    * the same Treasury whatever the equity index is.  The three depth rungs are already RATIOS
    * against a relation evaluated at the world's own volatility and return, so they read 1.00 for
    * any asset by construction — which is exactly why 0.21.0 restated them that way.
    *
    * `judgment` is NOT here.  It says what a target is worth given redundancy and importance, which
    * is a property of the statistic, not of the index; only the measured level and its sampling
    * spread are asset-specific.
    *
    * The realism bands are not here either.  `equity vol 8-40%` and `kurtosis 4-30` say "is this a
    * market at all", and a Nasdaq is still a market.  The two FIDELITY bands are, because they say
    * "is this THIS market". */
  final case class Anchors(
    name: String,
    equityWindow: String, equityYears: Int,      // the window the level rows below were read from
    retVolWindow: String,                       // window for the return-per-volatility anchor.
                                                // Its own field because it is NOT the equity
                                                // window: the S&P set takes r/v from CRSP
                                                // 1954-2026 where its levels come from the S&P,
                                                // and the Nasdaq set takes it from QQQ.  The
                                                // report header printed "CRSP 1954-2026" as a
                                                // literal and so mislabelled every Nasdaq run.
    clusterWindow: String, clusterYears: Int,    // clustering is horizon-sensitive and reads its own
    // The TAIL reads its own window too, and for a sharper reason than horizon-sensitivity: the
    // deepest episode is the one statistic a window can DELETE.  Across the committed fixture the
    // median depth swings 11% between windows and the crash rate 30%, while the worst swings 54% --
    // -84.1% over the century against -54.6% from 1954, because 1954 opens after the crash that set
    // it.  A tail graded on a window chosen to exclude the record's worst extreme cannot fail on
    // the thing it exists to test.  Never fold this back into `equityWindow`: the two coincide in
    // neither shipped set for the same reason, and coinciding today is not a reason to share a field.
    tailWindow: String, tailYears: Int,
    vol: Double,        volSd: Double,
    retVol: Double,     retVolSd: Double,
    kurt: Double,       kurtSd: Double,
    ac1: Double,        ac1Sd: Double,
    ac20: Double,       ac20Sd: Double,
    crashes: Double,    crashesSd: Double,
    medDepth: Double,   medDepthSd: Double,
    worstDepth: Double, worstDepthSd: Double,
    volBand: (Double, Double),                   // the two asset-specific FIDELITY bands
    retVolBand: (Double, Double),
    // 100*(sdRatio - 1) from `asymmetry-2026-08-31.tsv` -- the raw model/real quotient of sdRatio
    // itself sits so near 1 by construction that no miss could ever fire; the EXCESS is the
    // phenomenon (positive everywhere the record was measured).
    semiExcess: Double, semiExcessSd: Double,
    // corr(r_t, r^2_{t+1}) from the same fixture -- the one leverage statistic that is stable
    // across every CRSP era and all 18 funds on close-only data.
    levCorr: Double, levCorrSd: Double,
    // Left-tail stock-bond correlation from `tailcorr-2026-08-31.tsv` (the equity leg's own pair
    // against TLT).
    tailHedge: Double, tailHedgeSd: Double,
    // Sampling spreads for the rows whose LEVEL is not asset-specific -- the theory-valued depth
    // rungs, the valuation proxy and the bond rows -- but whose spread is: measured by `-noise` at
    // the set's own world (the S&P default; the 0.23.0-nasdaq recipe), 200 paths, and frozen like
    // the spreads above.  Carried inline through 0.23.0, so the Nasdaq loss weighted these rows
    // with the S&P world's spreads.
    valDispSd: Double, d5Sd: Double, d10Sd: Double, d20Sd: Double,
    bondVolSd: Double, bondGrowthSd: Double, bondInflSd: Double, bondDepthSd: Double,
    // Drawdown-SHAPE references for `-ddshape`, the first the primary the ratios read against;
    // `ddshape-2026-09-02.tsv`, on the model's own episode definition and median.
    ddRefs: Vector[DdReference],
    // The dividend yield at fair value (%/yr) and the band its level is graded against when the
    // `divYield` dial is on -- `dividend-2026-09-02.tsv`: the window's annual means rounded out.
    divYield: Double, divYieldBand: (Double, Double))

  /** One real drawdown-shape reference: a series over a window, and per threshold (thr, episodes,
    * per year, median depth %, median decline, median recovery, median underwater, median
    * worst-day share) -- every median `pctile(.., 0.5)`, the model rows' own.  Windows of the
    * century at SPY's own length carry the spread one SPY-length history can show. */
  final case class DdReference(series: String, window: String, years: Double,
                               rows: Vector[(Double, Int, Double, Double, Int, Int, Int, Double)])

  val DdRefsSp500: Vector[DdReference] = Vector(
    //                                                thr  eps   /yr   depth%  decl recov undw  worst-day
    DdReference("CRSP", "1926-2026", 100.00, Vector((0.10, 31, 0.310, -20.2,  78,  84,  196, 0.153),
                                                    (0.20, 16, 0.160, -27.7, 235, 234,  434, 0.143))),
    DdReference("CRSP", "1926-1959",  33.50, Vector((0.10,  7, 0.209, -12.8, 125,  67,  196, 0.191),
                                                    (0.20,  3, 0.090, -28.3, 273, 722,  994, 0.133))),
    DdReference("CRSP", "1960-1993",  33.07, Vector((0.10, 14, 0.423, -18.7, 135, 103,  269, 0.140),
                                                    (0.20,  7, 0.212, -27.7, 167, 233,  368, 0.131))),
    DdReference("CRSP", "1993-2026",  33.42, Vector((0.10, 10, 0.299, -20.4,  65,  94,  145, 0.236),
                                                    (0.20,  6, 0.180, -25.6, 235, 296,  530, 0.153))),
    DdReference("SPY",  "1993-2026",  33.59, Vector((0.10, 12, 0.357, -18.8,  64,  75,  131, 0.290),
                                                    (0.20,  4, 0.119, -33.7, 355, 869, 1223, 0.158))))

  val DdRefsNasdaq: Vector[DdReference] = Vector(
    DdReference("NDX",  "1990-2026",  36.66, Vector((0.10, 32, 0.873, -13.2,  34,  37,   74, 0.302),
                                                    (0.20,  7, 0.191, -28.0,  62,  78,  142, 0.181))),
    DdReference("QQQ",  "1999-2026",  27.48, Vector((0.10, 21, 0.764, -12.0,  20,  44,   61, 0.304),
                                                    (0.20,  5, 0.182, -28.6,  80,  75,  154, 0.181))))

  /** The S&P/CRSP set.  The LEVELS are the ones every release before 0.21.0 hard-coded, moved
    * rather than re-measured (except the two the 0.22 releases re-anchored -- `medDepth` and
    * `worstDepth` -- each re-derived from a committed fixture).  The SPREADS were re-frozen from
    * `-noise -paths 200` at the adopted 0.23.0 valuation-cycle world, 2026-08-30, as the
    * defaults-change rule requires; `-noise`'s `sd/real` column agrees with the `wt` beside it,
    * which is the whole point of printing them together. */
  val SP500Anchors = Anchors(
    name = "S&P 500 / CRSP",
    equityWindow = "S&P / CRSP 1954-2026", equityYears = 72,
    retVolWindow = "CRSP 1954-2026",
    clusterWindow = "CRSP 1926-2026, the century", clusterYears = 100,
    tailWindow = "CRSP 1926-2026, the century", tailYears = 100,
    vol = 16.0,          volSd = 0.13,
    retVol = 0.69,       retVolSd = 0.27,
    kurt = 28.0,         kurtSd = 1.17,
    ac1 = 0.299,         ac1Sd = 0.11,
    ac20 = 0.225,        ac20Sd = 0.19,
    crashes = 20.7,      crashesSd = 0.26,
    medDepth = -21.4,    medDepthSd = 0.17,
    // RE-ANCHORED in 0.22.1, same error class as `median depth %` in 0.22.0: -56.8 was the
    // 2007-09 episode, the worst of the 1954-2026 window, used where the model computes the worst
    // over a whole history.  1954 opens AFTER the crash that set the record's worst, so the anchor
    // graded the tail against a window with the tail removed.  Over the century, on the model's own
    // 15% threshold, the record reads -84.1% (`episodes-2026-08-29.tsv`, w1926) -- the 1929-32
    // decline, which every threshold in that window agrees on because it is one episode.
    // `tailYears` moves to 100 with it, so the percentile is read at the window's own length.
    // sd RE-MEASURED with the window: 0.24 was the spread of 72-year readings, 0.19 the spread
    // of 100-year readings at the adopted 0.23.0 world (`-noise -paths 200`, 2026-08-30).
    worstDepth = -84.1,  worstDepthSd = 0.19,
    volBand = (14.0, 18.0),
    retVolBand = (0.50, 0.85),
    // CRSP c1954 rows of asymmetry-2026-08-31.tsv; the tail hedge is SPY/TLT.  Spreads frozen
    // from `-noise -paths 200` at the adopted 0.23.0 asymmetry world, 2026-08-31: a single
    // 72-year history barely pins the semivariance excess (one crash day swings it), and the
    // record now reads as a TYPICAL history of this model on all three rows -- 42nd percentile
    // (semivariance), 46th (leverage corr), 26th (tail hedge).
    semiExcess = 3.06, semiExcessSd = 1.54,
    levCorr = -0.0926, levCorrSd = 0.44,
    tailHedge = -0.273, tailHedgeSd = 0.24,
    valDispSd = 0.64, d5Sd = 0.19, d10Sd = 0.45, d20Sd = 2.38,
    bondVolSd = 0.52, bondGrowthSd = 1.48, bondInflSd = 1.99, bondDepthSd = 0.36,
    ddRefs = DdRefsSp500,
    divYield = 2.95, divYieldBand = (1.1, 5.8))

  /** The Nasdaq-100 set, measured 2026-08-28 from QQQ daily adjusted closes over its own full
    * history, 1999-03-10 to 2026-08-20 (27.4 years).
    *
    * THAT WINDOW IS A DECISION, not a default.  Drawdown-episode counts swing 1.7x on the
    * measurement convention alone: the same QQQ data reads 24.1 episodes per century with the
    * running peak seeded from prior history, 40.1 with a fresh start on a window opening
    * 2001-08-27 (mid dot-com bear, which resets the peak ~60% down and MANUFACTURES episodes on
    * the recovery), and 25.6 fresh-start from QQQ's own inception.  The model measures each path
    * fresh from its own start, so a fresh start is the matching convention -- but only on a window
    * that OPENS near a high, or the reset does the manufacturing.  QQQ's inception in March 1999
    * is such a window.  The equity-anchor fixture already states this rule for `w1996` ("opens
    * mid-bull, so its peak seed is clean by construction") and warns against grading a model
    * ensemble on the mid-bear `w2001` block.
    *
    * Control: the same pipeline on SPY 1993-01-29 reproduces the committed w1993 fixture row
    * exactly (18.57 / 10.31 / 0.447 / 0.315 / 0.169 against 18.6 / 10.30 / 0.447 / 0.315 / 0.169),
    * so these readings are on the fixture's own definitions.
    *
    * THE SAMPLING SPREADS ARE NOW THE NASDAQ WORLD'S OWN, re-frozen 2026-09-01 from
    * `-noise -anchors nasdaq -depth 10 -drift 0.105 -jumpvar 0.02 -fundvol 0.06 -paths 200` --
    * the gate-passing recipe this set describes, which did not exist when they were first
    * carried over from the S&P.  The assumption that carried values were "approximately right
    * because both assets' statistics have similar relative spreads" was FALSE where the two
    * worlds differ most: `medDepthSd` read 0.10 against a measured 0.37, a 3.7x OVERWEIGHT on
    * the heaviest row in this set's loss (weight is `SdRelRef / sdRel`), and `semiExcessSd`
    * 1.54 against 3.57.  Re-measure whenever the recipe moves; a spread is model-implied, so it
    * belongs to the world, not the index.
    *
    * STILL SHARED, and disclosed: the sds passed inline to `wgt` (variance ratio, valuation
    * dispersion, the depth rungs, and every bond row) are per-TARGET constants rather than
    * per-anchor fields, so they stay at the S&P world's readings for both anchor sets.  Measured
    * at this recipe they would be vr 0.33, valuation dispersion 0.53, d5/d10/d20 0.12/0.19/0.30
    * -- the depth rungs differ most.  Moving them into `Anchors` is the fix; it is a structural
    * change, not a re-freeze.
    *
    * The two fidelity bands are the S&P bands' proportional widths around the Nasdaq levels
    * (+/-12.5% on volatility, -28%/+23% on return per volatility), for the same reason. */
  val NasdaqAnchors = Anchors(
    name = "Nasdaq-100 / QQQ",
    equityWindow = "QQQ 1999-2026", equityYears = 27,
    retVolWindow = "QQQ 1999-2026",
    clusterWindow = "QQQ 1999-2026", clusterYears = 27,
    tailWindow = "QQQ 1999-2026", tailYears = 27,
    vol = 26.90,         volSd = 0.10,
    retVol = 0.38,       retVolSd = 0.49,
    kurt = 9.55,         kurtSd = 1.07,
    ac1 = 0.293,         ac1Sd = 0.17,
    ac20 = 0.249,        ac20Sd = 0.15,
    crashes = 25.6,      crashesSd = 0.49,
    medDepth = -22.8,    medDepthSd = 0.37,
    worstDepth = -83.0,  worstDepthSd = 0.19,
    volBand = (23.5, 30.3),
    retVolBand = (0.27, 0.47),
    // QQQ wfull row of asymmetry-2026-08-31.tsv; the tail hedge is QQQ/TLT.  Spreads measured
    // at the recipe world (2026-09-01), like every spread in this set.
    semiExcess = 1.13, semiExcessSd = 3.57,
    levCorr = -0.1073, levCorrSd = 0.43,
    tailHedge = -0.236, tailHedgeSd = 0.24,
    // `-noise -anchors nasdaq` at the 0.23.0-nasdaq recipe, 200 paths, 2026-09-02.  d20's spread
    // is a fraction of the S&P world's (0.30 against 2.38): at Nasdaq volatility the deep rung is
    // pinned where the S&P default leaves it unreadable, so the row carries real weight here.
    valDispSd = 0.53, d5Sd = 0.12, d10Sd = 0.19, d20Sd = 0.30,
    bondVolSd = 0.52, bondGrowthSd = 0.91, bondInflSd = 1.71, bondDepthSd = 0.36,
    ddRefs = DdRefsNasdaq,
    divYield = 0.78, divYieldBand = (0.3, 1.5))

  val AnchorSets: Vector[Anchors] = Vector(SP500Anchors, NasdaqAnchors)

  def anchorsNamed(spec: String): Anchors = spec match
    case "sp500" | "sp" | "spx" => SP500Anchors
    case "nasdaq" | "ndx" | "qqq" => NasdaqAnchors
    case other => usage(s"unknown -anchors [$other]; use sp500 or nasdaq")

  def fitTargets(a: Anchors): Vector[(String, WorldStats => Double, Double, Double)] = Vector(
    ("equity vol %",       st => st.vol * 100,                              a.vol,  wgt(1.0, a.volSd)),
    // Ken French F-F_Research_Data_Factors, US total market (Mkt-RF + RF), measured in the units
    // this row is compared in: annualised LOG return over sqrt(mean(r^2) * 252) on DAILY data.
    // Both conversions matter -- a CAGR read as a simple rate and a monthly-derived volatility
    // each inflate the ratio, and together they turned a 0.69 anchor into 0.76.
    //   1954-2026 (the window of the rows around this one)  10.82%/yr over 15.68%  =  0.69
    //   1926-2026 (the only 100-year sample there is)        9.38%/yr over 17.14%  =  0.55
    // The target stays on the anchor window so the target set is internally consistent, NOT
    // because 0.55 is the wrong reading for a generator scored on 100-year paths; the gate band
    // below admits it rather than legislating it away.
    ("return per vol",     st => st.retVol,                                  a.retVol, wgt(1.0, a.retVolSd)),
    // kurtosis's sdRel moved 0.14 -> 2.65 in 0.21.0, and the 19x is not a re-measurement of the
    // same thing: the jump channel makes single-history kurtosis as variable as it really is.  One
    // 72-year window reads 8.8 at the 5th percentile and 205 at the 95th, because a window either
    // contains its 1987 or does not -- SPY 1993-2026 reads 14.4 where the CRSP century reads 28.
    // Weighting by measurability therefore drops this target to 0.04, and that is correct rather
    // than unfortunate: one history barely pins it.  What now pins `jumpVar` is CLUSTERING, at a
    // combined weight of 3.1 and an sdRel a tenth of this one -- turning the channel off moves
    // clustering 1.03 -> 1.11 and 1.05 -> 1.15, which the loss sees clearly.  A mechanism whose
    // only defender is its least measurable target is a mechanism a search will quietly discard.
    ("kurtosis",           st => st.kurt,                                   a.kurt,  wgt(0.5, a.kurtSd)),
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
    ("clustering lag 1",   st => st.ac1,                                    a.ac1, wgt(1.0, a.ac1Sd)),
    ("clustering lag 20",  st => st.ac20,                                   a.ac20, wgt(0.5, a.ac20Sd)),
    // SIGNED persistence, the axis the two rows above cannot see -- they are |r|, and a world can
    // cluster its volatility exactly right while its price trends.  See `varianceRatio` for why
    // this is not a per-lag autocorrelation and `VarRatioBands` for the cross-section behind it.
    //
    // 1.00 IS A THEORY VALUE, DELIBERATELY, and it is the one row in this table that is not a
    // reading off a record.  The real cross-section sits BELOW it -- 0.74 median at 2001-2026 --
    // because modern equity indices mean-revert mildly at three months, and this model has no
    // mean-reversion channel to reproduce that with.  Targeting 0.74 would ask a search to close a
    // gap with the only dials it has, which are the trend dials, and it would close it by removing
    // the reflexive channel entirely.  The target says "do not manufacture a trend"; the BAND is
    // where the record's own spread lives, and it admits every reading in the fixture.
    //
    // NOT redundant with `crashes/century` or the depth rungs even though the same dial moves all
    // four: across the crowdImpact sweep corr(vr60, equity d20 vs real) is 0.98, which is the
    // finding, not an argument for dropping a row.  The depth rungs said the world was too deep
    // and named no cause; this row names it.
    ("variance ratio 60d", st => st.vr60,                                    1.00,  wgt(1.0, 0.35)),
    // THE THIRD ASYMMETRY AXIS the rows above cannot see: clustering is |r| (sign-blind), vr60 is
    // the signed MEAN's persistence -- this pair is the signed SECOND moment.  Graded as the
    // EXCESS because the raw down/up ratio sits so near 1 that its model/real quotient could
    // never miss.  Anchored on CRSP 1954-2026 (the equity window); the record reads +2.8 to +3.1
    // on every CRSP era and positive on 15 of 18 funds (asymmetry-2026-08-31.tsv).  NO GATE BAND
    // yet -- first-cycle rows, disclosure before enforcement, the d20 precedent.
    ("downside vol excess %", st => st.semiExcess,                     a.semiExcess,  wgt(0.5, a.semiExcessSd)),
    // The leverage effect, graded by the one statistic that survives close-only data:
    // corr(r_t, r^2_{t+1}) reads -0.09 on every CRSP era and negative on all 18 funds.  The
    // sharper Patton-Sheppard signed-half regression was measured and CANNOT anchor here --
    // era-split with the sign flipping (c1926 -0.20, c1990 +0.34), the same negative result
    // longhorizon-2026-08-30.tsv records for long variance ratios -- and the fixture keeps its
    // columns so it stays settled.
    ("leverage corr",      st => st.levCorr,                              a.levCorr,  wgt(0.5, a.levCorrSd)),
    // The record proxy (sd log CAPE) reads 0.24-0.41 across windows; 0.30 is the judgment centre
    // and the LITERAL is shared by both anchor sets -- one Shiller record, no QQQ equivalent.
    // Judgment 0.5 for the proxy commensurability stated in valuation-2026-08-30.tsv.
    ("valuation dispersion", st => st.valDisp,                               0.30,  wgt(0.5, a.valDispSd)),
    ("crashes/century",    st => st.epPerPath * 100.0 / st.yearsPerPath,    a.crashes,  wgt(1.0, a.crashesSd)),
    // RE-MEASURED in 0.22.0, and the old value was not this statistic.  `-27.1` shipped through
    // 0.21.0 with no recorded convention; the model measures every peak-to-trough decline of 15% or
    // worse, and NO window of the record produces -27.1% at that threshold.  A 20% threshold does
    // (-26.6% over 1954-2026, -28.0% over the century), so the model was graded against a statistic
    // it does not compute and pushed toward crashes deeper than the record's for its own definition.
    //
    // Measured with `episodes` itself on the same CRSP total-return control the two rows above use:
    // -21.4% over 1954-2026, -23.7% over the century, -21.9% since 1990.  The anchor set's own
    // window wins, as it does for `equity vol %` and `crashes/century`.  The century reading is
    // deeper because it contains 1929-32 and 1937, and it is recorded in
    // `test-data/equity-anchors/episodes-2026-08-29.tsv` beside this one; `EpisodeAnchorSuite`
    // re-derives the shipped value from that file.
    //
    // The two sibling anchors survive the same check, which is why only this one moved:
    // `crashes/century` 20.7 sits between the record's 19.2 (century) and 24.9 (1954-2026), and
    // `worst crash %` did NOT survive it and was re-anchored in 0.22.1 -- see its own entry.
    ("median depth %",     st => st.depthMed,                              a.medDepth,  wgt(1.0, a.medDepthSd)),
    // Scored by the MEDIAN of single-history worsts at the anchor's own horizon -- `fitness`
    // swaps the statistic in by name, supplied from `extremeScoreStats` -- never by the pooled
    // ensemble minimum this statFn computes.  The minimum's distance from a one-history anchor
    // tracks the ensemble size (the frozen scoring ensemble's happens to sit 0.004 from the
    // anchor, a "perfect" reading for a tail `-validate` puts at the record's 1st percentile);
    // the median converges, is the centre of the distribution the report's percentile is read
    // from, and pulling it toward the anchor and pulling the percentile toward 50 are the same
    // act.  The statFn stays the pooled minimum because the REPORTS read it as a level.
    // Judgment 0.5: one draw of a max, partially redundant with the crash-rate and depth rows.
    // sdRel 0.15 measured at the 100-year horizon (2026-08-30).
    ("worst crash %",      st => st.worstDepth,                            a.worstDepth,  wgt(0.5, a.worstDepthSd)),
    // The "(24y)" is load-bearing, not decoration: this row is measured on a different horizon
    // from every other, and the label is the only part that travels when the number is quoted.
    ("bond vol % (24y)",   st => st.bondVol * 100,                          13.0,  wgt(1.0, a.bondVolSd)),
    // RE-MEASURED in 0.22.0, same error class as `median depth %` above: `20.0` is 2008 ALONE, the
    // largest of the five growth-shock episodes in the record, and this row is a MEDIAN across
    // episodes.  Measured the way `measure` measures it -- SPY drawdowns of 15%+, TLT's log return
    // over the same peak-to-trough span -- the record reads +6.6%, from episodes of
    // +6.6 / +22.4 / +4.4 / +13.3 / +0.8.  The model was therefore read as UNDERSTATING a bond
    // rally it in fact overstates.  Six episodes is the honest limit here and `-noise` prices it in.
    // `test-data/bond-anchors/crash-response-2026-08-29.tsv`; `BondCrashSuite` re-derives both rows.
    ("bond growth-crash",  st => st.bondGrowth,                              6.6,  wgt(1.0, a.bondGrowthSd)),
    // The judgment stays at 1.5 -- inflation-crash behaviour is why the bond refuge exists --
    // and the measured precision crushes the weight to ~0.13 anyway: sd/real 2.89, and only
    // 95 of 200 24-year histories produce a reading at all.  The old 1.5 was the largest
    // weight in the loss on the least measurable target in the set.
    // RE-MEASURED with it: `-25.0` was a rounding of the ONE inflation-regime drawdown the record
    // has, which reads -34.7% (SPY 2022-01-03..2022-10-12, TLT over the same span).  A median of one
    // is that one, so the anchor is the episode -- but rounded 28% toward zero, which is not a
    // convention, it is an error.
    ("bond infl-crash",    st => st.bondInfl,                              -34.7,  wgt(1.5, a.bondInflSd)),
    // Does the refuge hold exactly where it is needed -- stock-bond correlation on calm sessions
    // with the equity return below its own calm q10, against the pair's own record
    // (tailcorr-2026-08-31.tsv).  Calm-conditioned on BOTH sides by construction: the TLT window
    // is a disinflation era throughout, and the model's calm mask is the same one `corrCalm`
    // uses.  What it currently discloses: the model's refuge is about twice too good in the left
    // tail (-0.56 against -0.27) while its full-sample calm correlation sits 0.35 too high --
    // day-frequency dependence is concentrated in the tail rather than spread across the sample.
    ("tail hedge corr",    st => st.tailHedge,                        a.tailHedge,  wgt(0.5, a.tailHedgeSd)),
    // DEPTH PROFILE, stated RELATIVE to what a real fund of the same volatility and return spends
    // under water rather than as three absolute levels -- see `EquityD10Corr` for the relation and
    // `eqDepthVsReal` for what the ratio means.  A level target is a statement about one fund; a
    // ratio is a statement about the mechanism, which is the same reason `bond depth vs vol` is
    // written this way.
    //
    // The absolute levels this replaces were SPY's over 1993-2026 (0.447 / 0.315 / 0.169), and they
    // were internally inconsistent with the two rows at the top of this table.  SPY produced them at
    // 18.6% volatility and 0.554 return per vol; `equity vol %` and `return per vol` ask this model
    // to run at 16.0 and 0.69, and 35 real instruments at THAT operating point spend 1.11x / 1.33x /
    // 1.64x less time under water than SPY's numbers demanded.  The target set was asking for a
    // market that is calmer than SPY and better-returning than SPY and yet under water as long as
    // SPY, which no real fund is.  The only way to satisfy it was an over-hot fundamental, and the
    // search duly bought one -- see the `fundVol` range below.
    //
    // Anchor provenance is unchanged in kind and wider in coverage: 35 broad, sector and country
    // equity funds over 2001-2026 (`test-data/equity-anchors`, peaks seeded from full prior history),
    // with a 17-instrument 1996-2026 block as the independent transport check.  SPY is one row of it
    // and no longer sets the level, which also retires the old caveat that SPY could never serve as
    // validation because its rungs WERE the targets.
    //
    // Only two of the three rungs are gated; the 20% rung's relation does not transport well enough
    // for a band that could fail anything.  It stays a fit target -- the loss is a continuous
    // quantity, not a verdict, and its weight already carries the redundancy discount.
    // The bond anchor is a clean iShares TLT total-return series over 24 years, and only the 10%
    // rung of it has been measured.  The other two bond rungs are REPORTED, not targeted: filling
    // them in by interpolation would manufacture a calibration anchor out of nothing.
    // Re-measured by `-noise` when the rungs became ratios, and again at the 0.21.0 defaults these
    // are frozen from: a ratio compounds the depth share's own sampling error with the volatility
    // and return sampling that enters its denominator, so these are NOT the absolute rungs'
    // 0.22 / 0.34 / 0.55.  The deep rung's 0.99 still holds its weight near 0.10 -- the measurement
    // saying one 25-year record barely pins the 20% rung's ratio, which is also why it carries no
    // gate band.
    //
    // The same run is the fix's own evidence.  At the 0.20.0 world the real value sat at the 14th,
    // 7th and 4th percentile of the model-implied spread -- the record was in the model's tail, on
    // all three rungs at once.  At this world it sits at the 63rd, 65th and 55th: the anchors can
    // no longer tell this model from the cross-section they were measured from, which is a stronger
    // statement than any ratio near 1.00, because it is made against the spread rather than the
    // point.
    ("equity d5 vs real",   st => st.eqD5VsReal,                             1.00,  wgt(0.5, a.d5Sd)),
    ("equity d10 vs real",  st => st.eqD10VsReal,                            1.00,  wgt(1.0, a.d10Sd)),
    // d20's sdRel moved 0.99 -> 1.56 in the 0.21.0 recovery-drag change, and like kurtosis's move
    // it is a re-measurement of a statistic that genuinely became more variable, not a correction:
    // slowing recovery from deep drawdowns makes time spent DEEP swing much harder between
    // histories (p5 0.19, p95 4.35 over 25 years).  Weighting by measurability drops it to 0.06.
    // No other target's sdRel moved beyond its own noise, so none were churned.
    ("equity d20 vs real",  st => st.eqD20VsReal,                            1.00,  wgt(0.5, a.d20Sd)),
    ("bond depth vs vol",   st => st.bondDepthVsVol,                          1.00, wgt(0.5, a.bondDepthSd)),
  )

  /** Targets whose model statistic is an EXTREME order statistic over the pooled ensemble rather
    * than a per-path central value.  `worstDepth` is the minimum over every episode in the run, so
    * it deepens without bound as the ensemble grows: on one world with every dial fixed it reads
    * 1.28x its anchor at 1 path and 1.58x at 400.  A ratio that moves with `-paths` grades the
    * SAMPLE SIZE, not the model, and the anchor it is divided by is the deepest episode of ONE
    * 72-year history against the deepest of ~4,400.
    *
    * These rows are reported as the anchor's PERCENTILE among single histories of the anchor's own
    * length -- `-noise`'s `real@`, which converges -- and carry no ratio at all.  A median survives
    * pooling and a minimum does not; that is the whole distinction.  `MarketSimContractSuite`
    * requires every name here to be a fidelity target. */
  val ExtremeTargets: Set[String] = Set("worst crash %")

  /** The admissible interval for a per-path fidelity ratio, and the admissible percentile band for
    * an `ExtremeTargets` row.  Stated ONCE: the report, the sidecar and the tests read the same
    * pair, so a consumer's `miss` and a reader's `<-- MISS` cannot drift apart.
    *
    * Outside 5-95 is the condition `-noise`'s header already names -- the model cannot produce
    * record-like histories on that statistic -- and it is the honest analogue of a ratio miss:
    * both say "this level cannot be read off this world", neither says how far off it is. */
  val FidelityRatioBand: (Double, Double) = (0.667, 1.5)
  val ExtremePctBand: (Int, Int) = (5, 95)

  /** Fewest single histories that can place a record within `ExtremePctBand`.  One history reads
    * 0% or 100% and neither is a measurement; in general the resolution is `100/n` percentile
    * points, so resolving a 5-point band edge needs 20.  Below this the row reports `n/a` and a
    * MISS -- "too few histories to place the record" and "the model cannot produce record-like
    * histories" are different findings, and only the second is about the model, but neither is a
    * clean bill of health in the one field a consumer reads to decide whether to trust the file. */
  val ExtremeMinHistories: Int = 100 / ExtremePctBand._1

  /** `extremeStats`: the median single-history reading per `ExtremeTargets` row, from
    * `extremeScoreStats` -- the loss must never price the pooled minimum those rows' statFn
    * computes, so the caller supplies the converging statistic explicitly and a missing entry
    * prices as unmeasurable rather than silently falling back. */
  def fitness(a: Anchors, st: WorldStats,
              extremeStats: Map[String, Double]): (Double, Vector[(String, Double, Double, Double)]) =
    val rows = fitTargets(a).map { (name, get, target, weight) =>
      val m = if ExtremeTargets.contains(name) then extremeStats.getOrElse(name, Double.NaN)
              else get(st)
      val term =
        if m.isNaN then weight * 4.0
        else if m.sign != target.sign && target != 0.0 then
          weight * (2.0 + math.abs(math.log(math.abs(m).max(1e-6) / math.abs(target))))
        else weight * math.abs(math.log(math.abs(m).max(1e-6) / math.abs(target)))
      (name, m, target, term)
    }
    val gatePenalty = gateChecks(a, st).count(!_._2) * 0.5
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

  /** NON-FINITE ENTRIES ARE DROPPED, the same rule `measure`'s `med` applies, because
    * `Ordering[Double]` ranks NaN ABOVE every number: an unfiltered sort parks them in the top
    * slots and biases every quantile DOWNWARD rather than propagating the NaN.  A contaminated
    * ensemble read a 6.17% median volatility against a 15.7% baseline that way.  A quantile is the
    * wrong place to LEARN that an ensemble was contaminated -- the reports count that directly. */
  def pctile(v: Seq[Double], q: Double): Double =
    val f = v.filter(_.isFinite)
    if f.isEmpty then Double.NaN else f.sorted.apply(math.min((f.size * q).toInt, f.size - 1))

  def simPaths(w: World, paths: Int, years: Int, seed: Long): Vector[Path] =
    simPathRange(w, 0, paths, years, seed)

  /** Paths `from until from + count`.  Path k is a function of (world, years, seed, k) alone, so a
    * range taken from the middle is byte-identical to the same indices of a run that started at
    * zero -- which is what lets `-emitfrom` split one batch across invocations. */
  def simPathRange(w: World, from: Int, count: Int, years: Int, seed: Long): Vector[Path] =
    val level = worldLevel(w)
    java.util.stream.IntStream.range(from, from + count).parallel()
      .mapToObj(k => simulateAt(w, years, seed + k.toLong * 7919L, level)).toArray()
      .toVector.map(_.asInstanceOf[Path])

  // ---- calibration search --------------------------------------------------------------------
  /** Parameters that say WHICH ASSET is being simulated, not how a market behaves.  Each is a real
    * fund's published number: MEASURED once and then held, never fitted.  `-calibrate` must not
    * search one, for two reasons that are separate.  A duration chosen to reduce loss describes no
    * bond anyone can buy, so the fitted world stops being a claim about a real asset.  And
    * `-crossasset` grades the bond relations by MOVING duration across the values real funds have
    * -- if the shipped duration were itself fitted, that grader would be scoring the search's
    * choice against bands the same search was free to accommodate, which is circular.
    *
    * Enforced by `MarketSimContractSuite` against `CalibrateRanges`, not by this comment: the
    * 0.20.0 re-search proposed `duration = 11.1` and was refused by hand, and a rule that lives in
    * someone's memory of that refusal is one range row away from being lost. */
  val IdentityParams: Vector[String] = Vector("duration", "divYield")

  /** What `-calibrate` samples, and the ONLY place a searchable parameter is declared.  Named
    * rather than inline so the identity-parameter rule above can be tested against it. */
  val CalibrateRanges: Vector[(String, Double, Double, (World, Double) => World)] = Vector(
    ("depth",       10.0,  26.0, (w, x) => w.copy(depth = x)),
    ("trendShare",  0.05,  0.70, (w, x) => w.copy(trendShare = x)),
    ("drift",       0.06,  0.16, (w, x) => w.copy(drift = x)),
    // The depth profile's second axis, and the one no sweep could reach before 0.21: the value
    // channel passes only a few percent of a fundamental move into any one session, so fundamental
    // variance accumulates into time under water without moving daily return scale.  It is in the
    // search only now that the depth targets are stated against a real relation -- against SPY's
    // absolute levels a search free to raise it would have closed them by making the fundamental
    // hotter still, which is how the world it replaces was reached.
    ("fundVol",     0.03,  0.16, (w, x) => w.copy(fundVol = x)),
    ("crowdImpact", 0.01,  0.20, (w, x) => w.copy(crowdImpact = x)),
    ("stress",       2.0,   6.0, (w, x) => w.copy(stress = x)),
    // Widened from 0.010-0.035 in 0.21.0: with the recovery drag the base pull governs SHALLOW
    // water only, so its useful range moved up.  The old ceiling would have excluded the shipped
    // value, which is the `fundVol` failure mode -- a search that cannot reach the answer.
    ("valuePull",  0.010, 0.070, (w, x) => w.copy(valuePull = x)),
    // Both in the ranges from the release they arrive in, for the same reason.
    ("recoveryDrag",  0.0, 20.0, (w, x) => w.copy(recoveryDrag = x)),
    ("recoveryFloor", 0.05, 1.0, (w, x) => w.copy(recoveryFloor = x)),
    ("disasterRate",  0.0, 1.5, (w, x) => w.copy(disasterRate = x)),
    ("disasterSize",  0.5, 2.5, (w, x) => w.copy(disasterSize = x)),
    ("disasterRecover", 0.0, 0.9, (w, x) => w.copy(disasterRecover = x)),
    ("beliefShare",   0.0, 0.97, (w, x) => w.copy(beliefShare = x)),
    ("capYears",      0.0, 4.0, (w, x) => w.copy(capYears = x)),
    ("volOfVol",   0.012, 0.030, (w, x) => w.copy(volOfVol = x)),
    // In the ranges from the release it arrived in.  `fundVol` sat outside them for four releases
    // and that is exactly why its defect survived four releases of one-knob-at-a-time sweeps; a
    // mechanism the search cannot reach is a mechanism nobody will find the wrong value of.
    ("jumpVar",     0.00,  0.20, (w, x) => w.copy(jumpVar = x)),
    ("jumpRate",  0.0004, 0.0040, (w, x) => w.copy(jumpRate = x)),
    // The asymmetry pair and the jump shift, in the ranges the hand sweeps mapped: leverage
    // reaches the `leverage corr` anchor near 0.10 under the saturation cap, downShock pays vr60
    // ~+0.02 per 0.01 so the band bounds it near 0.03, and the best hand candidate
    // (0.10 / 0.015 / jumpVar 0.12 / drift 0.124) missed a four-seed gate PASS only on
    // `bond depth vs vol` -- the search has the bond dials in its hands where a hand sweep does
    // not.
    ("leverage",    0.00,  0.15, (w, x) => w.copy(leverage = x)),
    ("downShock",   0.00,  0.05, (w, x) => w.copy(downShock = x)),
    ("jumpSkew",    0.00,  1.40, (w, x) => w.copy(jumpSkew = x)),
    ("newsRate",    0.00,  3.00, (w, x) => w.copy(newsRate = x)),
    ("newsSize",    0.00,  0.05, (w, x) => w.copy(newsSize = x)),
    ("refugeDays",  0.00,  3.00, (w, x) => w.copy(refugeDays = x)),
    ("easing",       0.0,  0.09, (w, x) => w.copy(easing = x)),
    ("refuge",       0.0,  0.20, (w, x) => w.copy(refuge = x)),
    ("inflSize",    0.03,  0.12, (w, x) => w.copy(inflSize = x)),
    ("discount",     3.0,  10.0, (w, x) => w.copy(discount = x)),
    ("margin",       0.0, 0.004, (w, x) => w.copy(margin = x)),
  )

  def calibrate(a: Anchors, nSamples: Int, base: World, seed: Long): Unit =
    // depth, trendShare, drift and crowdImpact are in the search because they are the strongest
    // levers on the
    // two defects the eight below cannot reach.  depth carries crash frequency (at fixed stress,
    // 12 -> 24 takes it from 35 to 13 per century) but moves volatility in lockstep with it.
    // drift is the ONLY knob that moves the depth profile at constant volatility -- which is why
    // it cannot be searched without the return-per-vol band above, or the search buys the depth
    // rungs with a Sharpe no 20-year stretch of the real record produced.  Their CLI flags are
    // inert under -calibrate, exactly like the eight below.
    val ranges = CalibrateRanges
    // the only RNG in the program that was not already NumPyRNG.  uniform(lo, hi) IS
    // lo + nextDouble() * (hi - lo), the expression written inline below, so the swap is 1:1 --
    // but the STREAM differs, so a previously recorded "best world" from -calibrate will not
    // reproduce.  Accepted: -calibrate is a search procedure, not a reported statistic.
    val sr = new NumPyRNG(seed ^ 0x5ca1ab1eL)
    val trainSeed = seed; val holdSeed = seed + 7777777L
    def score(w: World, s: Long): Double =
      // scored at 100-year paths: an 80-year protocol missed a worst-crash blowup that only
      // appears at the horizon actually used — tune at the scale you evaluate at.  The extreme
      // rows' median ensemble rides along at the same 50 histories, so a candidate is priced on
      // the same statistic every report reads.
      fitness(a, measure(simPaths(w, 50, 100, s), 100), extremeScoreStats(a, 50, s, w))._1
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
        ("no macro disasters",         base.copy(disasterRate = 0.0), false),    // OFF-world: disasters
        ("no valuation cycle",         base.copy(beliefShare = 0.0, capYears = 0.0), false),
        ("double inflation severity",  base.copy(inflSize = base.inflSize * 2.0), false),
      ) ++ (if !withReflexive then Vector.empty else Vector(
        // TWO AXES, not two modes.  Before the momentum crowd got a strength dial there was only
        // one dimension here, so "which crowd" was the whole question; now a mode entry that does
        // not state a strength silently picks the default, which is not the interesting value.
        // 0.20 rather than the default 0.030, and the two numbers are NOT comparable as strengths:
        // since 0.22.0 one impact law covers every crowd, and this crowd's target moves in small
        // continuous steps where the momentum crowd's swings across a saturating tanh.  0.20 is the
        // largest setting that stays a market -- 0.30 fails the kurtosis realism band -- and it
        // still only reaches 2.3% of the noise term against the default crowd's 5.2%.  THAT IS THE
        // FINDING: a crowd selling into volatility destabilises the market faster than a crowd
        // buying trends, so it cannot be run as hard.  Left at the default it would be inert
        // (1.2%), which is the dead-knob defect this entry exists to avoid.
        ("reflexive: crowd runs a vol rule",  base.copy(crowd = Crowd.VolScaled, crowdImpact = 0.20), true),
        // 0.12 is the stress case: 4x the default, admissible on realism and mechanism, and outside
        // the persistence band -- which is what pressing a trend crowd hard is SUPPOSED to look
        // like, and is disclosed rather than hidden.
        ("reflexive: crowd pressed hard",     base.copy(crowdImpact = 0.12), true),
      ))

  /** One world's evaluation: per path, per arm (cash leg then bond leg), the `Outcome` plus its
    * per-crash-window entries `(fundamental-led?, rule log return, buy-and-hold log return)`.
    * Mirrors the Rust twin's `Evald`. */
  type Evald = Vector[Vector[(Outcome, Vector[(Boolean, Double, Double)])]]

  def runStrategySweep(a: Anchors, paths: Int, years: Int, seed: Long, cost: Double, single: Boolean,
                       base: World, gateReq: Set[GateClass]): Unit =
    val worlds = sweepWorlds(base, single, withReflexive = true)
    eprintln(s"${worlds.size} worlds x $paths paths x $years years, ${Rules.size} rules x {cash,bond}")
    val results = worlds.map { (wname, w, reflexive) =>
      val sims = simPaths(w, paths, years, seed)
      val st = measure(sims, years)
      val ok = gateOk(a, st, gateReq)
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
              f"trend share ${st.trendShare}%.2f  clamp ${st.clampPct}%.3f%% " +
              f"(tail ${st.tailFloorPct}%.1f%%)")
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
      val okSev = gateOk(a, st, gateReq)
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
  def runReleaseReport(a: Anchors, paths: Int, years: Int, seed: Long, base: World): Unit =
    val cols = Releases :+ ("current", base)
    eprintln(s"${cols.size} worlds x $paths paths x $years years")
    val stats = cols.map((v, w) => (v, measure(simPaths(w, paths, years, seed), years)))
    println("CROSS-RELEASE FIDELITY — every target at every published default, and at the world this")
    println("invocation describes.  The WORLDS are historical; the MEASUREMENT is current, so this shows")
    println("how the DEFAULT has moved, not what each version reported — the mechanism moved too.  A")
    println("World field added after a release -- or REMOVED by a mechanism change, as 0.19.2's")
    println("rate cut was -- takes today's value in that release's row.")
    if a.name != SP500Anchors.name then
      println()
      println("  NOTE: every frozen release world was calibrated against the S&P set; this run grades")
      println(s"  them with ${a.name} anchors.  The columns are still comparable to EACH OTHER, but a row's")
      println("  distance from 1.00 is not a defect of that release -- it was never fitted here.")
    println()
    println(f"  ${"target"}%-22s" + cols.map((v, _) => f"$v%8s").mkString +
            f"   ${"best"}%7s   worse than best")
    var bestTotal = 0.0
    for (name, get, want, _) <- fitTargets(a) do
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
            stats.map((_, st) => fitTargets(a).map((_, get, want, _) => math.abs(get(st) / want - 1.0)).sum)
                 .map(t => f"$t%8.2f").mkString +
            f"   ${bestTotal}%7.2f   best achievable per row, across all releases")
    println()
    println("  A flagged row is one where some published default read CLOSER to real than the current")
    println("  world does.  That is not automatically wrong — a trade may have been worth making — but")
    println("  it is the thing no predecessor-only comparison can show.")
    // Kept as a ratio here, and ONLY here, because every column shares one ensemble size: the
    // divergence that makes the level meaningless cancels in a world-to-world comparison, so the
    // MOVEMENT across columns is real even though no column's value is a fidelity judgement.
    // `-validate` reports these rows as a percentile; a reader who carries a level across from
    // this table to that one is comparing two different things.
    println()
    println(s"  ROWS THAT ARE NOT FIDELITY RATIOS: ${ExtremeTargets.toVector.sorted.mkString(", ")}.")
    println("  These are extremes over the pooled ensemble, so the LEVEL grades the ensemble size —")
    println("  read them across columns (which world is deeper), never against 1.00.  The AGGREGATE")
    println("  row includes them, and is the old equal-measurability objective's opinion regardless.")

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
    "variance ratio 60d", "downside vol excess %", "leverage corr", "valuation dispersion",
    "crashes/century", "median depth %",
    "worst crash %", "equity d5 vs real", "equity d10 vs real", "equity d20 vs real")

  /** The other half of the partition.  Read only by the partition test -- the report has no bond
    * section to drive; the list exists so a new fidelity target cannot land unclassified. */
  val BondTargets = Vector(
    "bond vol % (24y)", "bond growth-crash", "bond infl-crash", "bond depth vs vol",
    "tail hedge corr")

  /** Bisection bracket for the depth solve, and how many halvings.  Ten steps over this bracket
    * leaves the depth uncertain by 21/1024 ~ 0.021, worth about 0.03 points of volatility -- far
    * inside the sampling noise of any ensemble that could be run here.  Each step is a full
    * ensemble, so this is the cost knob: twelve ensembles in total, including the bracket probes.
    *
    * The low end reaches BELOW the Nasdaq recipe's own `depth 10`: volatility falls as depth
    * rises, so a 26.9%-volatility anchor needs a thinner market than that recipe runs, and a
    * bracket starting at 10.0 refused the solve outright -- the equity-at-anchor section simply
    * declined for every Nasdaq world. */
  val DepthBracket = (5.0, 26.0)
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
    * The confound this removes is now narrower than it was.  The three depth rungs are graded
    * against a relation evaluated at each world's OWN volatility and return, so a volatility miss
    * no longer distorts them -- moving depth moves the prediction with the measurement, and their
    * two columns should read alike.  What the section still isolates is the ABSOLUTE targets:
    * kurtosis, clustering, crash rate, median and worst crash depth are levels, and reading a level
    * while the model sits below its own volatility anchor mixes two errors and reports one.
    *
    * A depth rung that DOES move here is worth reading: it says the relation and the model
    * disagree about how time under water responds to volatility, which is exactly the defect the
    * relation was introduced to expose.
    *
    * DIAGNOSTIC ONLY -- it does not touch the exit code. */
  def runEquityAtAnchor(a: Anchors, paths: Int, years: Int, seed: Long, base: World): Unit =
    val target = fitTargets(a).find(_._1 == "equity vol %").map(_._3)
      .getOrElse(usage("no `equity vol %` fidelity target to anchor volatility on"))
    println()
    println("EQUITY — every equity target re-read with volatility ON ITS ANCHOR.  Diagnostic: this")
    println("section does not affect the exit code.")
    println()
    println("A LEVEL read while the model sits below its own volatility anchor mixes two errors and")
    println("reports one.  Here depth is solved so volatility sits on the anchor and every equity target")
    println("is re-read: 1 identity parameter, set from 1 measured statistic, nothing else touched.")
    println()
    println("The three depth rungs are graded against a relation evaluated at each world's OWN")
    println("volatility and return, so they should read ALIKE in both columns -- solving depth moves")
    println("their prediction with their measurement.  A rung that moves anyway is reporting that the")
    println("model and the real cross-section disagree about how time under water responds to")
    println("volatility, which is the one thing this pair of columns can still show about them.")
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
          val (_, get, want, _) = fitTargets(a).find(_._1 == name)
            .getOrElse(usage(s"EquityTargets names [$name], which is not a fidelity target"))
          val (d, at)  = (get(stDef), get(stAnc))
          val (rd, ra) = (d / want, at / want)
          // The point of the section: the rows where putting volatility on its anchor CHANGES the
          // verdict.  A row that reads the same either way was never distorted by the miss.  Judge
          // a flagged move against `-noise`'s seed-noise section before reading it as real; the
          // two columns share one seed, so 2 sd there is the conservative bound on this difference.
          val flag = if math.abs(ra - rd) > 0.05 then f"<-- moves ${ra - rd}%.2f" else ""
          // Both columns share one ensemble size, so the MOVE is readable on every row; the LEVEL
          // is not, on the extremes -- see the note below and `-validate`'s percentile.
          val kind = if ExtremeTargets.contains(name) then " *" else ""
          println(f"  $name%-22s$d%10.2f$at%11.2f$want%10.2f$rd%11.2f$ra%11.2f   $flag%s$kind%s")
        if EquityTargets.exists(ExtremeTargets.contains) then
          println()
          println("  * an extreme over the pooled ensemble, not a per-path value: the MOVE between the two")
          println("    columns is real, the LEVEL grades the ensemble size.  -validate reports it as a")
          println("    percentile among single histories instead; do not carry a level across.")

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
  def runCrossAssetReport(a: Anchors, paths: Int, years: Int, seed: Long, base: World): Boolean =
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
    runEquityAtAnchor(a, paths, years, seed, base)
    ok

  // ---- the anchor-noise report ---------------------------------------------------------------

  /** Each fidelity anchor's own measurement horizon, in years, and the targets read over it.  The
    * windows are the ones the fidelity header names -- S&P/CRSP 1954-2026, the CRSP century for
    * clustering, SPY 1993-2026 for the depth rungs, the clean 24-year TLT series for the bond --
    * because sampling error depends on the length of the record actually behind each number, not
    * on the horizon the model is scored at.  The contract test pins this to `FitTargets` as a
    * partition, so a new target cannot land without a declared horizon. */
  def anchorGroups(a: Anchors): Vector[(String, Int, Vector[String])] = Vector(
    (a.equityWindow, a.equityYears,
     Vector("equity vol %", "return per vol", "kurtosis", "crashes/century",
            "median depth %", "downside vol excess %", "leverage corr")),
    (a.clusterWindow, a.clusterYears,
     Vector("clustering lag 1", "clustering lag 20")),
    // Its own group because its own window -- see `Anchors.tailWindow`.  For both shipped sets this
    // is the instrument's whole history, which is the only window that cannot have deleted the
    // deepest episode.
    (a.tailWindow, a.tailYears, Vector("worst crash %")),
    // The Shiller record is one series shared by every anchor set, at its own century horizon.
    ("Shiller CAPE 1881-2023", 100, Vector("valuation dispersion")),
    // 18 equity funds and three CRSP windows, the shortest of them 24.9 years -- see
    // `VarRatioBands`.  The horizon is one instrument's record, as it is for the depth rungs, and
    // the target this group carries is a theory value rather than a reading, so `real@` here says
    // where 1.00 falls in the model's own spread of 25-year readings, not where a record does.
    ("equity funds + CRSP, 25y", 25, Vector("variance ratio 60d")),
    // 35 equity funds over 2001-2026; the horizon is one instrument's record, because that is what
    // each residual ratio in the fit was measured from.
    ("equity funds, 25y", 25,
     Vector("equity d5 vs real", "equity d10 vs real", "equity d20 vs real")),
    ("clean TLT, 24y", 24,
     Vector("bond vol % (24y)", "bond growth-crash", "bond infl-crash", "bond depth vs vol",
            "tail hedge corr")))

  /** One fidelity row AS REPORTED.  A per-path target carries a ratio; an `ExtremeTargets` row
    * carries the anchor's percentile among single histories instead, and no ratio.  The two are
    * different judgements and a consumer must be able to tell them apart from the data alone --
    * the whole defect this type exists to prevent is a reader dividing two numbers that are not
    * the same statistic and reading the quotient as a bias.
    *
    * `horizonYears` is the length of the record the anchor was read over, from `anchorGroups`; it
    * is carried on EVERY row, not just the extreme ones, because a per-path ratio still folds a
    * horizon mismatch a reader cannot otherwise see. */
  final case class FidelityRow(name: String, model: Double, real: Double, ratio: Option[Double],
                               pctile: Option[Int], horizonYears: Int, nHistories: Int):
    /** Stated as the admissible interval and NEGATED, so an unmeasurable row reports a miss rather
      * than a clean bill of health -- a `NaN` ratio fails both outward comparisons, and an extreme
      * row whose ensemble produced no reading has nothing to stand on either. */
    def miss: Boolean = ratio match
      case Some(r) => !(r >= FidelityRatioBand._1 && r <= FidelityRatioBand._2)
      case None    => !pctile.exists(p => p >= ExtremePctBand._1 && p <= ExtremePctBand._2)
    def aggregation: String = if ExtremeTargets.contains(name) then "ensemble-extreme" else "per-path"

  /** The horizon each target's anchor was read over, inverted from `anchorGroups` -- which the
    * contract test already pins as a partition of the fidelity targets, so every target has one. */
  def anchorHorizons(a: Anchors): Map[String, Int] =
    anchorGroups(a).flatMap((_, yrs, names) => names.map(_ -> yrs)).toMap

  /** Where an anchor falls among model readings, as a percentage.  `-noise`'s `real@` column and
    * the extreme rows' `record@` are the SAME number and are computed here so they stay so: two
    * reports disagreeing about one world would replace the confusion being fixed with a new one. */
  def anchorPctile(xs: Vector[Double], want: Double): Int = 100 * xs.count(_ <= want) / xs.size

  /** Each extreme target's per-single-history readings at its OWN horizon, from one ensemble.
    * This is the distribution behind BOTH the report's percentile and the loss's median -- one
    * function, so the two judgements cannot be read off different ensembles, and the same
    * measurement `-noise` prints as `real@`.  One extra ensemble per distinct horizon, and only
    * `ExtremeTargets` need it, so at the shipped anchor sets that is exactly one. */
  def extremeReadings(a: Anchors, paths: Int, seed: Long, w: World): Map[String, Vector[Double]] =
    anchorGroups(a)
      .map((_, yrs, names) => (yrs, names.filter(ExtremeTargets.contains)))
      .filter(_._2.nonEmpty)
      .flatMap { (yrs, names) =>
        val sts = simPaths(w, paths, yrs, seed).map(p => measure(Vector(p), yrs))
        names.map { nm =>
          val (_, get, _, _) = fitTargets(a).find(_._1 == nm)
            .getOrElse(usage(s"ExtremeTargets names [$nm], which is not a fidelity target"))
          nm -> sts.map(get).filter(x => !x.isNaN)
        }
      }.toMap

  /** What the LOSS grades an extreme row by: the median of the single-history readings.  A median
    * of extremes converges as histories are added, where the pooled minimum deepens without
    * bound.  NaN where the ensemble produced no finite reading, which `fitness` prices as
    * unmeasurable rather than as agreement. */
  def extremeScoreStats(a: Anchors, histories: Int, seed: Long, w: World): Map[String, Double] =
    // the same median rule `measure`'s local `med` applies: non-finite dropped, NaN on empty
    extremeReadings(a, histories, seed, w).map { (nm, xs) =>
      val f = xs.filter(_.isFinite)
      nm -> (if f.isEmpty then Double.NaN else f.sorted.apply(f.size / 2))
    }

  /** Every fidelity row as the report and the sidecar both read it.  Built ONCE per invocation so
    * the printed table and the emitted JSON cannot describe the same world differently. */
  def fidelityRows(a: Anchors, st: WorldStats, paths: Int, seed: Long, w: World): Vector[FidelityRow] =
    val pcts = if fitTargets(a).exists((n, _, _, _) => ExtremeTargets.contains(n))
               then extremeReadings(a, paths, seed, w) else Map.empty
    val hz   = anchorHorizons(a)
    fitTargets(a).map { (name, get, want, _) =>
      val got = get(st)
      if ExtremeTargets.contains(name) then
        val xs = pcts.getOrElse(name, Vector.empty)
        val p  = if xs.size < ExtremeMinHistories then None else Some(anchorPctile(xs, want))
        FidelityRow(name, got, want, None, p, hz.getOrElse(name, 0), xs.size)
      else
        FidelityRow(name, got, want, Some(if want != 0.0 then got / want else Double.NaN),
                    None, hz.getOrElse(name, 0), 1)
    }

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
  def runNoiseReport(a: Anchors, paths: Int, seed: Long, base: World): Unit =
    println("ANCHOR NOISE — what one history can pin down.  Every fidelity target is a POINT read from")
    println("one historical record; this report asks the model what spread of readings independent")
    println("histories of that anchor's OWN length would produce, and where the real record falls.")
    println()
    println("MODEL-IMPLIED, circularity stated: the spreads come from this model's own dynamics, so")
    println("where the model is known biased (the deep drawdown rung, 1.7x real) the spread is too.")
    println("There is no other estimate — the record is one draw.")
    println()
    println("Read `real@` as the share of model histories at or below the real anchor: near 50% the")
    println("record is a typical history of this model, near 0/100% the model cannot produce")
    println("record-like histories on that statistic.  `sd/real` beside `wt` is the mis-weighting")
    println("check: equal weight with unequal sd/real grades two targets as equally measurable, and")
    println("they are not.  `p50` vs `real` is the HORIZON-MATCHED reading; -fitness grades the")
    println("extreme rows on it (the median of these single histories), and the per-path rows on the")
    println("100-year scoring ensemble against these mixed-horizon anchors.")
    // Merged for the REPORT only, in first-appearance order: `anchorGroups` keeps one entry per
    // anchor because the windows are separate DECISIONS that happen to coincide in both shipped
    // sets, and printing one header and running one ensemble per distinct (window, horizon) is what
    // a reader wants from that.  Merging the field would be the coupling; merging the display is not.
    val noiseGroups = anchorGroups(a).foldLeft(Vector.empty[(String, Int, Vector[String])]) {
      case (acc, (label, years, names)) =>
        acc.indexWhere((l, y, _) => l == label && y == years) match
          case -1 => acc :+ (label, years, names)
          case i  => acc.updated(i, (label, years, acc(i)._3 ++ names))
    }
    for (label, years, targets) <- noiseGroups do
      eprintln(s"$paths paths x ${years}y — $label")
      val sims = simPaths(base, paths, years, seed)
      val sts  = sims.map(p => measure(Vector(p), years))
      println()
      println(s"  $label — $years-year single histories:")
      println(f"  ${"target"}%-22s${"real"}%8s${"p5"}%8s${"p50"}%8s${"p95"}%8s${"real@"}%7s${"n"}%5s${"sd/real"}%8s${"wt"}%5s")
      for name <- targets do
        val (_, get, want, weight) = fitTargets(a).find(_._1 == name)
          .getOrElse(usage(s"anchor group names [$name], not a fidelity target"))
        val xs = sts.map(get).filter(x => !x.isNaN).sorted
        val n  = xs.size
        if n == 0 then
          println(f"  $name%-22s${want}%8.2f${"n/a"}%8s${"n/a"}%8s${"n/a"}%8s${"-"}%7s$n%5d${"n/a"}%8s${weight}%5.1f")
        else
          def p(q: Int) = xs((n - 1) * q / 100)
          val mean  = xs.sum / n
          val sd    = if n > 1 then math.sqrt(xs.map(x => (x - mean) * (x - mean)).sum / (n - 1)) else Double.NaN
          val ps    = s"${anchorPctile(xs, want)}%"
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
    for (name, get, want, _) <- fitTargets(a) do
      val rs   = reps.map(st => get(st) / want)
      val mean = rs.sum / rs.size
      val sd   = math.sqrt(rs.map(x => (x - mean) * (x - mean)).sum / (rs.size - 1))
      println(f"  $name%-22s$mean%11.3f$sd%11.3f${2.0 * sd}%11.3f")

  def runPowerReport(a: Anchors, paths: Int, seed: Long, cost: Double, single: Boolean, base: World,
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
      val ok    = gateOk(a, measure(sims, L), gateReq)
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
  // ---- drawdown SHAPE: how a decline is delivered, not how deep it gets -----------------------
  //
  // A SECOND episode definition, and the difference from the model's own is the whole point.
  // `measure` counts a crash as a 15%-below-peak excursion that re-arms once price is back within
  // 2% -- a definition built for COUNTING crashes.  This one is peak-to-trough-to-FULL-recovery,
  // built for SHAPE: how long a decline takes and how much of it arrives in one session.  The two
  // answer different questions and must not be mixed.
  //
  // It is reported here rather than left in a consumer's own script because a second copy of a
  // definition is a copy free to drift from this one -- the failure class this file already guards
  // against for constants.
  //
  // NOTHING HERE IS GATED.  The real reference is ONE history: 12 episodes at the 10% threshold and
  // 4 at the 20%.  A band drawn off four episodes could not fail, so these are disclosed
  // diagnostics and the ratios are for reading.
  final case class DdEpisode(depth: Double, decline: Int, recovery: Option[Int], underwater: Int,
                             worstDayShare: Double)

  /** Peak-to-trough-to-recovery episodes deeper than `threshold`.  An episode still underwater at
    * the end is CENSORED: its depth and decline count, its recovery does not.
    *
    * `worstDayShare` is the fraction of the peak-to-trough LOG decline delivered by its single
    * worst session -- low means the decline ground down, high means it gapped.  The leg starts at
    * the session BEFORE the first underwater bar, because that is the session the fall began on. */
  def ddEpisodes(px: Array[Double], threshold: Double): Vector[DdEpisode] =
    val n     = px.length
    val peak  = px.scanLeft(Double.NegativeInfinity)(math.max).tail
    val under = Array.tabulate(n)(i => px(i) / peak(i) - 1.0)
    val spans = Vector.newBuilder[(Int, Int, Boolean)]
    var start = -1
    var i = 0
    while i < n do
      val below = under(i) < -1e-12
      if below && start < 0 then start = i
      else if !below && start >= 0 then
        spans += ((start, i - 1, false)); start = -1
      i += 1
    if start >= 0 then spans += ((start, n - 1, true))
    spans.result().flatMap { (lo, hi, censored) =>
      val depth = (lo to hi).map(under).min
      if depth > -threshold then None
      else
        val trough = (lo to hi).minBy(under)
        val base   = math.max(lo - 1, 0)
        val total  = math.log(px(trough) / px(base))
        val legs   = (math.max(lo, 1) to trough).map(k => math.log(px(k) / px(k - 1)))
        val worst  = if legs.isEmpty then 0.0 else legs.min
        Some(DdEpisode(depth, trough - lo + 1, if censored then None else Some(hi - trough + 1),
                       hi - lo + 1, if total < 0.0 then worst / total else Double.NaN))
    }

  def runDrawdownShape(a: Anchors, paths: Int, years: Int, seed: Long, base: World): Unit =
    eprintln(s"$paths paths x $years years")
    val sims = simPaths(base, paths, years, seed)
    val pYrs = sims.size.toDouble * years
    val refs = a.ddRefs
    println("DRAWDOWN SHAPE — how a decline is DELIVERED: how long it takes, and how much of it")
    println("arrives in its single worst session.  This is a SECOND episode definition on purpose:")
    println("the model's own crash count is a 15%-below-peak excursion re-arming at 2%, built for")
    println("counting; these are peak-to-trough-to-FULL-recovery, built for shape.  Do not mix them.")
    println()
    println(s"References for the ${a.name} set, every median on the model's own pctile(.., 0.5); the")
    println(f"ratio reads against ${refs.head.series} ${refs.head.window} (${refs.head.years}%.0f years) and the min/max rows span every")
    println("reference.  NOTHING HERE IS GATED: the episode counts are printed so nobody reads a")
    println("median of four as a population value, and the ratios are for reading, not for passing.")
    println()
    println(f"  ${"series"}%-15s ${"thr"}%4s ${"eps"}%5s ${"eps/yr"}%7s ${"depth"}%8s ${"decline"}%8s " +
            f"${"recovery"}%9s ${"underwtr"}%9s ${"worst-day"}%10s")
    for thr <- Vector(0.10, 0.20) do
      val pct   = (thr * 100).toInt
      val eps   = sims.flatMap(p => ddEpisodes(p.price, thr))
      val recov = eps.flatMap(_.recovery).map(_.toDouble)
      def m(f: DdEpisode => Double) = pctile(eps.map(f), 0.5)
      val rows  = refs.flatMap(r => r.rows.find(_._1 == thr).map(row => (r, row)))
      for (r, (_, rEps, rYr, rDepth, rDecl, rRecov, rUndw, rWds)) <- rows do
        println(f"  ${s"${r.series} ${r.window}"}%-15s $pct%3d%% $rEps%5d $rYr%7.2f $rDepth%7.1f%% $rDecl%8d " +
                f"$rRecov%9d $rUndw%9d ${rWds * 100}%9.1f%%")
      def ext(pick: Vector[Double] => Double): (Double, Double, Double, Double, Double, Double) =
        (pick(rows.map(_._2._3)), pick(rows.map(_._2._4)), pick(rows.map(_._2._5.toDouble)),
         pick(rows.map(_._2._6.toDouble)), pick(rows.map(_._2._7.toDouble)), pick(rows.map(_._2._8)))
      for (label, (xYr, xDepth, xDecl, xRecov, xUndw, xWds)) <- Vector(("refs min", ext(_.min)), ("refs max", ext(_.max))) do
        println(f"  $label%-15s $pct%3d%% ${""}%5s $xYr%7.2f $xDepth%7.1f%% $xDecl%8.0f " +
                f"$xRecov%9.0f $xUndw%9.0f ${xWds * 100}%9.1f%%")
      val (_, _, rYr, rDepth, rDecl, rRecov, rUndw, rWds) = rows.head._2
      println(f"  ${"model"}%-15s $pct%3d%% ${eps.size}%5d ${eps.size / pYrs}%7.2f " +
              f"${m(_.depth) * 100}%7.1f%% ${m(_.decline.toDouble)}%8.0f ${pctile(recov, 0.5)}%9.0f " +
              f"${m(_.underwater.toDouble)}%9.0f ${m(_.worstDayShare) * 100}%9.1f%%")
      println(f"  ${"ratio"}%-15s $pct%3d%% ${""}%5s ${eps.size / pYrs / rYr}%7.2f " +
              f"${m(_.depth) * 100 / rDepth}%8.2f ${m(_.decline.toDouble) / rDecl}%8.2f " +
              f"${pctile(recov, 0.5) / rRecov}%9.2f ${m(_.underwater.toDouble) / rUndw}%9.2f " +
              f"${m(_.worstDayShare) / rWds}%10.2f")
      println()
    println("  A LOW worst-day ratio means the model's declines GRIND where the real one GAPPED.")
    println("  Read it beside the decline column: a decline taking twice as long dilutes its worst")
    println("  session by construction, so the two move together -- and read both against the")
    println("  min/max rows before the ratio: the references disagree with each other by more than")
    println("  most model/real ratios here.")
    println()
    println("  Medians here are `pctile(.., 0.5)`: the UPPER of the two middle elements of an")
    println("  ascending sort on an even count (a depth reads the shallower, a duration the longer),")
    println("  where NumPy averages them.  The reference rows are on the same median, so a")
    println("  consumer reproducing them with NumPy lands one element away on a four-episode")
    println("  statistic and is right.")

  def runBufferReport(a: Anchors, paths: Int, years: Int, seed: Long, cost: Double, single: Boolean,
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
      val ok   = gateOk(a, measure(sims, years), gateReq)
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
    case Crowd.Momentum    => "momentum"
    case Crowd.Trend(d)    => s"trend$d"
    case Crowd.VolScaled   => "volscaled"
    case Crowd.Drawdown(d) => s"drawdown$d"

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

  /** Zero-padding width for `indexedName`, from the highest index a batch writes.  Floored at 3 so
    * every ensemble of 1000 or fewer keeps the names it has always had; a larger one widens rather
    * than losing the sort order the padding exists to give. */
  def indexWidth(lastIndex: Int): Int = math.max(3, lastIndex.toString.length)

  /** `foo.tsv` -> `foo-007.tsv`, so an ensemble sorts in path order. */
  def indexedName(file: String, k: Int, width: Int): String =
    val cut = file.lastIndexOf('.')
    val sep = math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'))
    val tag = s"-${k.toString.reverse.padTo(width, '0').reverse}"
    if cut > sep then file.substring(0, cut) + tag + file.substring(cut) else file + tag

  /** The TSV and its sidecar.  `gateSt` is measured on the gate ensemble -- a different, usually
    * much larger and (per `GateYears`) usually longer sample than the one path being written --
    * and `gateRows` are built from it once per batch, because building them simulates the extreme
    * rows' own-horizon ensemble. */
  def writeEmitted(a: Anchors, file: String, p: Path, k: Int, w: World, years: Int, seed: Long,
                   startYmd: String, gateSt: WorldStats, gatePaths: Int, gateYears: Int,
                   gateRows: Vector[FidelityRow]): Unit =
    // A non-finite path is refused, not written -- a file whose every row reads NaN is not data.
    // The CLI's clean refusal (message + exit 2) lives at the emit sites in `main`, which pre-check
    // before calling; here it THROWS, because this is also API and a `System.exit` in a library
    // method takes a test harness down whole rather than failing one test.
    require(p.price.forall(_.isFinite) && p.sat.forall(_.isFinite) &&
            p.logHi.forall(_.isFinite) && p.logLo.forall(_.isFinite) &&
            p.logVolume.forall(_.isFinite) && p.divYield.forall(_.isFinite) &&
            p.traded.forall(_.isFinite),
            s"path $k holds a non-finite value; refusing $file")
    val dates = sessionDates(p.price.length, startYmd)
    writeEmitTsv(file, p, dates)
    writeEmitSidecar(a, file, p, k, w, years, seed, startYmd, dates, gateSt, gatePaths, gateYears,
                     gateRows)

  def writeEmitTsv(file: String, p: Path, dates: Vector[String]): Unit =
    // The optional columns, present only when their channel ran -- a channels-off file is
    // byte-identical to its predecessor schema's.  LOG columns throughout: see the 7 -> 8 and
    // 8 -> 9 notes at `EmitSchema`.
    val header = (EmitColumns
      ++ (if p.sat.isEmpty then Vector() else Vector("logSat"))
      ++ (if p.logHi.isEmpty then Vector() else Vector("logHigh", "logLow"))
      ++ (if p.logVolume.isEmpty then Vector() else Vector("logVolume"))
      ++ (if p.traded.isEmpty then Vector() else Vector("logTraded", "divYield"))).mkString("\t")
    val rows = header +: Vector.tabulate(dates.length) { i =>
      val base =
        s"${dates(i)}\t${ef(p.price(i))}\t${ef(p.bond(i))}\t${ef(p.rate(i))}\t${ef(p.cpi(i))}\t" +
        s"${ef(p.liq(i))}\t${ef(p.bliq(i))}\t${ef(p.fundamental(i))}\t${ef(p.inflPress(i))}"
      val s1 = if p.sat.isEmpty then base else s"$base\t${ef(math.log(p.sat(i)))}"
      val s2 = if p.logHi.isEmpty then s1 else s"$s1\t${ef(p.logHi(i))}\t${ef(p.logLo(i))}"
      val s3 = if p.logVolume.isEmpty then s2 else s"$s2\t${ef(p.logVolume(i))}"
      if p.traded.isEmpty then s3 else s"$s3\t${ef(math.log(p.traded(i)))}\t${ef(p.divYield(i))}"
    }
    file.asPath.writeLines(rows)

  /** Every `World` field, in declaration order, as the indented body of a JSON object.  A world
    * that reaches a consumer without its parameters cannot be re-simulated. */
  def worldJsonBody(w: World): Vector[String] =
    Vector(
      ("trendShare", ef(w.trendShare)), ("depth", ef(w.depth)), ("stress", ef(w.stress)),
      ("beta", ef(w.beta)), ("drift", ef(w.drift)), ("fundVol", ef(w.fundVol)),
      ("rateMean", ef(w.rateMean)), ("volPersist", ef(w.volPersist)),
      ("volOfVol", ef(w.volOfVol)), ("leverage", ef(w.leverage)),
      ("downShock", ef(w.downShock)), ("jumpSkew", ef(w.jumpSkew)), ("jumpVar", ef(w.jumpVar)),
      ("jumpRate", ef(w.jumpRate)), ("newsRate", ef(w.newsRate)), ("newsSize", ef(w.newsSize)),
      ("valuePull", ef(w.valuePull)),
      ("recoveryDrag", ef(w.recoveryDrag)), ("recoveryFloor", ef(w.recoveryFloor)),
      ("haltLimit", ef(w.haltLimit)),
      ("disasterRate", ef(w.disasterRate)), ("disasterSize", ef(w.disasterSize)),
      ("disasterLen", ef(w.disasterLen)), ("disasterRecover", ef(w.disasterRecover)),
      ("disasterRecLen", ef(w.disasterRecLen)),
      ("beliefShare", ef(w.beliefShare)), ("beliefYears", ef(w.beliefYears)),
      ("capYears", ef(w.capYears)), ("capWindow", ef(w.capWindow)),
      ("crowd", jsonStr(crowdName(w.crowd))), ("crowdImpact", ef(w.crowdImpact)),
      ("panic", ef(w.panic)), ("duration", ef(w.duration)),
      ("easing", ef(w.easing)), ("unwind", ef(w.unwind)), ("refuge", ef(w.refuge)),
      ("refugeDays", ef(w.refugeDays)),
      ("satBeta", ef(w.satBeta)), ("satIdio", ef(w.satIdio)),
      ("rangeScale", ef(w.rangeScale)), ("rangeDown", ef(w.rangeDown)),
      ("volIdio", ef(w.volIdio)), ("divYield", ef(w.divYield)),
      ("inflProb", ef(w.inflProb)), ("inflSize", ef(w.inflSize)),
      ("inflSpeed", ef(w.inflSpeed)), ("rateSpeed", ef(w.rateSpeed)),
      ("discount", ef(w.discount)), ("margin", ef(w.margin)),
    ).map((nm, v) => s"""    ${jsonStr(nm)}: $v""")

  /** The channel readings the `satellite *` / `bar *` gate rows grade, as DATA: `fidelityFailed`
    * names a band, and a reader that never sees the report could not size a channel FAIL from
    * it.  Each object is present exactly when its channel ran (`satStats`/`barStats` return
    * Some); `{}` when none did, else led by the world level they were sampled at
    * (`worldLevel`).  NaN prints as null, the `fidelity` rows' rule. */
  def channelReadingsBlock(st: WorldStats, p: Path): String =
    def num(x: Double): String = if x.isNaN then "null" else ef(x)
    val level =
      if st.sat.isDefined || st.bars.isDefined || st.divYieldMean.isFinite then
        Vector(s"""    "level": { "k": ${num(p.chanK)}, "kSat": ${num(p.chanKSat)}, "kDiv": ${num(p.chanKDiv)} }""")
      else Vector.empty
    val sat = st.sat.toVector.map { sd =>
      s"""    "satellite": { "corr": ${num(sd.corr)}, "absCorr": ${num(sd.absCorr)}, """ +
      s""""beta": ${num(sd.beta)}, "volRatio": ${num(sd.volRatio)}, "kurtRatio": ${num(sd.kurtRatio)}, """ +
      s""""ac1Ratio": ${num(sd.ac1Ratio)}, "ac20Ratio": ${num(sd.ac20Ratio)}, "d5Ratio": ${num(sd.d5Ratio)}, """ +
      s""""d10Ratio": ${num(sd.d10Ratio)}, "crashRatio": ${num(sd.crashRatio)} }"""
    }
    val bars = st.bars.toVector.flatMap { b =>
      Vector(s"""    "barRange": { "rangeOverCcvol": ${num(b.rangeOverCcvol)}, """ +
             s""""rangeAcf1": ${num(b.rangeAcf1)}, "rangeDownup": ${num(b.rangeDownup)} }""") ++
        (if b.volSd.isFinite then
           Vector(s"""    "barVolume": { "volSd": ${num(b.volSd)}, "volCorrRange": ${num(b.volCorrRange)} }""")
         else Vector.empty)
    }
    val div =
      if st.divYieldMean.isFinite then Vector(s"""    "dividend": { "meanYield": ${num(st.divYieldMean)} }""")
      else Vector.empty
    val blocks = level ++ sat ++ bars ++ div
    if blocks.isEmpty then """  "channels": {},"""
    else "  \"channels\": {\n" + blocks.mkString(",\n") + "\n  },"

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
  def writeEmitSidecar(a: Anchors, file: String, p: Path, k: Int, w: World, years: Int, seed: Long,
                       startYmd: String, dates: Vector[String], gateSt: WorldStats,
                       gatePaths: Int, gateYears: Int, gateRows: Vector[FidelityRow]): Unit =
    val n            = p.price.length
    val realismBad   = failedIn(a, gateSt, GateClass.Realism)
    val mechanismBad = failedIn(a, gateSt, GateClass.Mechanism)
    val fidelityBad  = failedIn(a, gateSt, GateClass.Fidelity)
    def strList(v: Vector[String]): String = v.map(jsonStr).mkString("[", ", ", "]")
    def num(x: Double): String = if x.isNaN then "null" else ef(x)
    // `aggregation` and `horizonYears` are the terms of the comparison, and they are in the DATA
    // because prose does not travel: a consumer holding this file has no access to the report's
    // note, and an `ensemble-extreme` row divided by its anchor gives a quotient that grades the
    // ensemble size.  Such a row carries `ratio: null` and a `percentile` instead -- where the
    // record falls among single histories of its own length -- so the division cannot be made by
    // accident.  `miss` is the admissible interval NEGATED for both kinds, so a row that could not
    // be measured reports a miss rather than a clean bill of health.
    val fidelity = gateRows.map { r =>
      s"""    { "name": ${jsonStr(r.name)}, "model": ${num(r.model)}, "real": ${num(r.real)}, """ +
      s""""aggregation": ${jsonStr(r.aggregation)}, "horizonYears": ${r.horizonYears}, """ +
      s""""ratio": ${r.ratio.fold("null")(num)}, """ +
      s""""percentile": ${r.pctile.fold("null")(_.toString)}, "miss": ${r.miss} }"""
    }
    val json = Vector(
      "{",
      """  "generator": "market_sim",""",
      s"""  "version": ${jsonStr(Version)},""",
      s"""  "schema": $EmitSchema,""",
      s"""  "file": ${jsonStr(file)},""",
      s"""  "columns": ${strList(EmitColumns
        ++ (if p.sat.isEmpty then Vector() else Vector("logSat"))
        ++ (if p.logHi.isEmpty then Vector() else Vector("logHigh", "logLow"))
        ++ (if p.logVolume.isEmpty then Vector() else Vector("logVolume"))
        ++ (if p.traded.isEmpty then Vector() else Vector("logTraded", "divYield")))},""",
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
      s"""    "ensembleYears": $gateYears,""",
      // WHICH RULER, and WHICH SERIES.  Without the first, a `-anchors nasdaq` run's verdict is
      // indistinguishable from an S&P one in its own provenance record.  Without the second, a
      // PASS would sit beside emitted columns it never examined, and `logSat` is exactly the
      // column a consumer would take as their second index.  `gradedSeries` is the
      // authoritative half: the verdict is computed from THOSE series -- `price` and `bond`
      // always, and each channel column exactly when its `satellite *` / `bar *` rows ran,
      // which is the same condition under which the column exists (`satStats`/`barStats`
      // return Some exactly when these columns are non-empty).  Same doctrine as
      // `fidelityUnanchored` below -- name what was graded, in the artifact that carries the
      // verdict.
      s"""    "anchors": ${jsonStr(a.name)},""",
      s"""    "gradedSeries": ${strList(Vector("price", "bond")
        ++ (if p.sat.isEmpty then Vector() else Vector("logSat"))
        ++ (if p.logHi.isEmpty then Vector() else Vector("logHigh", "logLow"))
        ++ (if p.logVolume.isEmpty then Vector() else Vector("logVolume"))
        ++ (if p.traded.isEmpty then Vector() else Vector("logTraded", "divYield")))},""",
      // EMPTY BY CONSTRUCTION today, and the field earns its place anyway: `logSat` is covered
      // by the `satellite *` gate rows and the bar columns by the `bar *` rows, so there is
      // nothing left to disclose -- but the next channel to arrive is ungraded until someone
      // anchors it, and this is the field that has to say so rather than a doc nobody reads
      // beside the data.
      s"""    "ungradedChannelSeries": ${strList(Vector.empty)},""",
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
      channelReadingsBlock(gateSt, p),
      """  "fidelity": [""",
      fidelity.mkString(",\n"),
      "  ]",
      "}")
    sidecarName(file).asPath.writeLines(json)

  // ---- entry point ---------------------------------------------------------------------------
  def main(args: Array[String]): Unit =
    // Usage errors exit 2 on BOTH twins -- the Rust side's `cli_die` convention, distinct from the
    // verdict exits' 1 (gate failure, a -crossasset miss).  `showUsage` exits 1 for every uni app;
    // this seam redirects only this process, not the library.
    uni.cli.ArgsParser.exitFn = _ => sys.exit(2)
    var paths = DefaultPaths; var years = DefaultYears; var seed = DefaultSeed
    var pathsGiven = false; var yearsGiven = false
    var anchorSpec = "sp500"
    var ddShape = false
    var emit = ""; var validate = false; var strategies = false; var single = false
    var emitPath = 0; var emitAll = false; var emitStart = ""; var emitGate = DefaultEmitGate
    var emitFrom = 0
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
    // 0.21.0 re-searched again with `fundVol` in the ranges for the first time and the depth rungs
    // stated against a real relation (see `EquityD10Corr`).  Two search results were declined by
    // hand, both for reasons the loss cannot see.  `crowdImpact` was pushed to its 0.01 range
    // floor, which reads 0.9% of the noise term on `meanCrowdFlow` — the reflexive channel
    // switched off, which is the defect that diagnostic exists to catch; pinned back at 0.07 it
    // reads 6.7%, and the pin also BOUGHT volatility (16.03 against 15.38) and crash depth.
    // `refuge` was raised 0.11 -> 0.159, which took bond volatility to 1.12x duration, outside its
    // band; returned to 0.11 it reads 1.03 and the equity side does not move at all.  And `easing`
    // was cut 0.046 -> 0.037, which is not a tuning question: `usage` interpolates this field and
    // asserts it IS one full real easing cycle, and real cycles run about 5 rate points
    // (2008: 5.25 -> 0.25; 2001: 6.5 -> 1.0).  At 0.037 the help text states something false, so
    // the value is anchored the way `duration` is and the search does not get to move it.
    //   `inflSize` was cut 0.10 -> 0.084 and reverted, for the SECOND time and the same reason:
    // 0.20.0's search proposed the same cut and it was reverted then because it breaks the d=5.70
    // rung of the `-crossasset` bond ladder, which no version of the loss can see.  Measured here:
    // 0.084 puts that rung over its floor on 1 seed of 4, 0.10 on 3 of 4.  The cost is `bond
    // infl-crash` 1.08 -> 1.28, on the row whose own `-noise` measurement says one 24-year record
    // barely produces a reading.  A parameter the search keeps proposing to cut and that keeps
    // having to be put back is a candidate for the identity list; it has not been promoted yet
    // because unlike `duration` it names no single published number.
    //
    // Scored on the MEDIAN of three seeds, not one: a single-seed refinement here found a 1.687
    // that was a 2.15 median over five seeds.  Depth-rung agreement is cheap to overfit because
    // the relation's denominator moves with the sample.
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
    // KURTOSIS AND CLUSTERING COULD NOT BOTH BE RIGHT THROUGH `stress`: at stress 7.5 kurtosis
    // reached 26.4 against a real 28 and clustering hit 1.67, failing its realism band.  That was
    // the measured reason the kurtosis MISS stood, and the note it replaced was more precise than
    // "no slow valuation cycle" -- the cycle is why there was no SECOND channel for tails, not why
    // that one could not reach them.
    //
    // 0.21.0 ADDED THE SECOND CHANNEL and the trade-off disappeared with it.  `jumpVar` 0.10 moves
    // a tenth of the equity flow's variance from diffusion into a volatility-clustered compensated
    // jump; kurtosis goes 0.45 -> 1.00 and clustering IMPROVES, 1.11 -> 1.03 and 1.15 -> 1.05,
    // because variance taken out of the diffusion shortens the persistence the clamped volatility
    // process was over-supplying.  Volatility, return per vol and crash rate all improved too, and
    // the calibration loss fell 1.947 -> 1.575 with no other parameter touched -- almost all of it
    // from CLUSTERING, since kurtosis's own weight collapsed once its sdRel was re-measured.  The
    // channel is defended by the target it was not aimed at.  The lesson is not
    // about jumps: an "X and Y cannot both be right" finding is a statement about the CHANNEL that
    // was tried, and stays one until someone tries a different channel.
    //
    // ASYMMETRIC RECOVERY closed the crash-rate and shallow-median misses TOGETHER, because they
    // were one defect.  The model spent HALF the real record's time below 15% (d15 0.115 against
    // SPY's 0.240) while crossing 15% 40% MORE often -- its deep drawdowns recovered three times too
    // fast.  `recoveryDrag` weakens value arbitrage as a drawdown deepens, which is what depleted
    // capital does; `crashes/century` goes 1.32 -> 1.13 and `median depth %` 0.84 -> 0.95, and
    // `-noise` moves the real anchors from the 4th and 6th percentiles of the model-implied spread
    // to the 33rd and 30th.  Five mechanisms were tried first and all failed -- see the CHANGELOG;
    // the one that worked keys on distance below the PEAK, which is what the statistic is about,
    // where a pull convex in the gap to FAIR VALUE cannot tell a deep drawdown from an ordinary one.
    //
    // THE MACRO-DISASTER CHANNEL (0.22.1) is the "channel that deepens a crash without adding
    // low-frequency variance" this note used to call for and attribute to the absent valuation
    // cycle -- it buys the exemption through RARITY, not through a cycle, and it carried the
    // CENTURY tail: the record's -84.1% moved from the 1st percentile of model centuries to the
    // 18th.  What a valuation cycle alone could still add is documented at `disasterRate`'s
    // World field and in docs/MarketSimWorlds.md: valuation-LED deep crashes (2000-02: multiples
    // collapse, earnings fine) and peaks that sit far above fair value before they fall.  Every
    // deep crash here starts from a peak AT fair value, and a consumer reading the emitted
    // `fundamental` column or `-strategies`' crash-type conditioning sees the shifted mix.
    //
    // ONE KNOWN BIAS DIRECTION, netted away nowhere else: the DEEP drawdown rung reads 2.36 (d20),
    // partly the drag's cost -- a slower climb out of a deep hole is more time deep -- and, since
    // 0.22.1, partly the RULER's: the relation is fitted on 2001-2026 funds, a window with no
    // depression in it, while the model's own share of sessions >20% under water (0.126, median
    // path) sits BELOW a rough reading of the real century's (~0.15-0.20).  Rules keyed to a deep
    // distance from peak inherit the model number; the shallow rungs read 0.98 and 1.13.
    // Ruin rates for levered sleeves read off the ensemble MINIMUM remain UPPER BOUNDS, not
    // estimates -- 20,000 market-years of worst case, and no fund lives that long.
    // `-atrelease` swaps the BASE the dials seed from -- the frozen world of a past release, so a
    // pinned consumer can take binary fixes without taking a recalibration.  Resolved before the
    // flag loop on purpose: explicit dial flags override the base wherever they sit on the
    // command line, where a base applied mid-loop would clobber the flags before it.  The gate
    // still grades with the CURRENT rulers -- a pre-0.23.0 world has no valuation cycle and
    // honestly fails the valuation mechanism row AND the valuation dispersion band; pair with
    // `-gate realism` to require only what such a world claims, and read the rest as disclosure.
    val (dw, recipeAnchors) =
      args.indexOf("-atrelease") match
        case -1 => (Defaults, None)
        case i  =>
          if args.indexOf("-atrelease", i + 1) >= 0 then usage("-atrelease given twice")
          if i + 1 >= args.length then usage("-atrelease wants a version or a recipe name")
          namedWorld(args(i + 1)).getOrElse(usage(
            s"-atrelease ${args(i + 1)} names no release or recipe this binary can reproduce; " +
            s"it has ${Releases.map(_._1).mkString("[", ", ", "]")}, $Version and " +
            s"${Recipes.map(_._1).mkString("[", ", ", "]")}"))
    // A recipe carries the anchor set it was verified against; `-anchors` in the loop overrides.
    recipeAnchors.foreach(a => anchorSpec = a)
    var trendShare = dw.trendShare; var depth = dw.depth
    var stress = dw.stress; var beta = dw.beta
    var volPersist = dw.volPersist; var volOfVol = dw.volOfVol
    var jumpVar = dw.jumpVar; var jumpRate = dw.jumpRate
    var leverage = dw.leverage; var downShock = dw.downShock; var jumpSkew = dw.jumpSkew
    var newsRate = dw.newsRate; var newsSize = dw.newsSize
    var recoveryDrag = dw.recoveryDrag; var recoveryFloor = dw.recoveryFloor
    var disasterRate = dw.disasterRate; var disasterSize = dw.disasterSize
    var disasterLen = dw.disasterLen
    var disasterRecover = dw.disasterRecover; var disasterRecLen = dw.disasterRecLen
    var beliefShare = dw.beliefShare; var beliefYears = dw.beliefYears
    var capYears = dw.capYears; var capWindow = dw.capWindow
    var haltLimit = dw.haltLimit
    var valuePull = dw.valuePull
    var crowdName = "momentum"; var crowdImpact = dw.crowdImpact; var panic = dw.panic
    var drift = dw.drift; var fundVol = dw.fundVol; var rateMean = dw.rateMean
    var duration = dw.duration
    var easing = dw.easing; var unwind = dw.unwind; var refuge = dw.refuge
    var refugeDays = dw.refugeDays
    var satBeta = dw.satBeta
    var satIdio = dw.satIdio
    var rangeScale = dw.rangeScale
    var rangeDown = dw.rangeDown
    var volIdio = dw.volIdio; var divYield = dw.divYield
    var jointEmit = ""
    var barsEmit = ""
    var inflProb = dw.inflProb; var inflSize = dw.inflSize
    var inflSpeed = dw.inflSpeed; var rateSpeed = dw.rateSpeed
    var discount = dw.discount; var margin = dw.margin
    eachArg(args.toSeq, usage) {
      // Bare version on stdout and nothing else, so a caller can gate on it without parsing:
      // `[ "$(marketSim.sc -version)" = "$want" ] || exit 1`.  Handled where it is seen, so it
      // answers before any other flag is validated.
      case "-version"    => println(Version); System.exit(0)
      case "-paths"      => paths = intOr("-paths", consumeNext); pathsGiven = true
      case "-years"      => years = intOr("-years", consumeNext); yearsGiven = true
      case "-seed"       => seed = longOr("-seed", consumeNext)
      case "-emit"       => emit = consumeNext
      case "-emitpath"   => emitPath = intOr("-emitpath", consumeNext)
      case "-emitall"    => emitAll = true
      case "-emitfrom"   => emitFrom = intOr("-emitfrom", consumeNext)
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
      case "-ddshape"    => ddShape = true
      case "-single"     => single = true
      case "-cost"       => cost = numOr("-cost", consumeNext)
      case "-trendshare" => trendShare = numOr("-trendshare", consumeNext)
      case "-depth"      => depth = numOr("-depth", consumeNext)
      case "-stress"     => stress = numOr("-stress", consumeNext)
      case "-beta"       => beta = numOr("-beta", consumeNext)
      case "-volpersist" => volPersist = numOr("-volpersist", consumeNext)
      case "-volofvol"   => volOfVol = numOr("-volofvol", consumeNext)
      case "-jumpvar"    => jumpVar = numOr("-jumpvar", consumeNext)
      case "-jumprate"   => jumpRate = numOr("-jumprate", consumeNext)
      case "-leverage"   => leverage = numOr("-leverage", consumeNext)
      case "-downshock"  => downShock = numOr("-downshock", consumeNext)
      case "-jumpskew"   => jumpSkew = numOr("-jumpskew", consumeNext)
      case "-newsrate"   => newsRate = numOr("-newsrate", consumeNext)
      case "-newssize"   => newsSize = numOr("-newssize", consumeNext)
      case "-value"      => valuePull = numOr("-value", consumeNext)
      case "-anchors"    => anchorSpec = consumeNext
      // Applied in the pre-scan that seeded `dw`; consumed here so the loop does not reject it
      // as unknown.
      case "-atrelease"  => val _ = consumeNext
      case "-recoverydrag"  => recoveryDrag = numOr("-recoverydrag", consumeNext)
      case "-recoveryfloor" => recoveryFloor = numOr("-recoveryfloor", consumeNext)
      case "-disasterrate"  => disasterRate = numOr("-disasterrate", consumeNext)
      case "-disastersize"  => disasterSize = numOr("-disastersize", consumeNext)
      case "-disasterlen"   => disasterLen = numOr("-disasterlen", consumeNext)
      case "-disasterrecover" => disasterRecover = numOr("-disasterrecover", consumeNext)
      case "-disasterreclen"  => disasterRecLen = numOr("-disasterreclen", consumeNext)
      case "-beliefshare"     => beliefShare = numOr("-beliefshare", consumeNext)
      case "-beliefyears"     => beliefYears = numOr("-beliefyears", consumeNext)
      case "-capyears"        => capYears = numOr("-capyears", consumeNext)
      case "-capwindow"       => capWindow = numOr("-capwindow", consumeNext)
      case "-haltlimit"  => haltLimit = numOr("-haltlimit", consumeNext)
      case "-crowd"      => crowdName = consumeNext
      case "-crowdimpact"=> crowdImpact = numOr("-crowdimpact", consumeNext)
      case "-panic"      => panic = numOr("-panic", consumeNext)
      case "-drift"      => drift = numOr("-drift", consumeNext)
      case "-fundvol"    => fundVol = numOr("-fundvol", consumeNext)
      case "-ratemean"   => rateMean = numOr("-ratemean", consumeNext)
      case "-duration"   => duration = numOr("-duration", consumeNext)
      case "-easing"     => easing = numOr("-easing", consumeNext)
      case "-unwind"     => unwind = numOr("-unwind", consumeNext)
      case "-refuge"     => refuge = numOr("-refuge", consumeNext)
      case "-refugedays" => refugeDays = numOr("-refugedays", consumeNext)
      case "-satbeta"    => satBeta = numOr("-satbeta", consumeNext)
      case "-satidio"    => satIdio = numOr("-satidio", consumeNext)
      case "-rangescale" => rangeScale = numOr("-rangescale", consumeNext)
      case "-rangedown"  => rangeDown = numOr("-rangedown", consumeNext)
      case "-volidio"    => volIdio = numOr("-volidio", consumeNext)
      case "-divyield"   => divYield = numOr("-divyield", consumeNext)
      case "-jointemit"  => jointEmit = consumeNext
      case "-barsemit"   => barsEmit = consumeNext
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
    if emitFrom < 0 then usage(s"-emitfrom must be non-negative, got $emitFrom")
    // Refused rather than ignored: silently writing 0..paths-1 under a flag that asked for a
    // different range is how a chunked batch ends up with every chunk holding path 0.
    if emitFrom > 0 && !emitAll then usage("-emitfrom applies to -emitall; use -emitpath for one path")
    // A bad index here is the one place the rule list has to be discoverable: the report names
    // the rules but not their numbers, and the numbers are what the flag takes.
    if powerArms.exists(i => i < 1 || i > Rules.size) then
      usage(s"-powerarms indices must be 1-${Rules.size}; the rules are:\n" +
            Rules.zipWithIndex.map((r, i) => f"  ${i + 1}%d  ${r.name}%s").mkString("\n"))
    if powerYears.exists(_ < 1) then
      usage(s"-poweryears wants year counts of at least 1, got [${powerYears.mkString(",")}]")
    // DOMAINS for the world dials.  Out of domain they do not fail on their own: `-jumprate 0` with
    // a positive `-jumpvar` divides by zero in `jumpScale` and emitted a file of NaN at exit 0, and
    // `-recoveryfloor 3` inverts asymmetric recovery -- arbitrage STRONGER in a deep drawdown, the
    // documented mechanism run backwards -- into a world that then PASSES the acceptance gate.  A
    // clamp that cannot throw is worse than one that can: what you get is a certified world, not a
    // stack trace.
    //
    // Reject what the mechanism cannot express, never what merely looks unusual -- an over-tight
    // bound breaks a sweep script for no defect.  Every value recorded anywhere in this repo is
    // admitted, `-jumpvar 0` and `-haltlimit 0` (the documented disable values) included, as is
    // every range `calibrate` sweeps.  Written as `!(x >= lo ...)` so a NaN literal -- `toDouble`
    // accepts "nan" -- is refused here rather than reaching the model.
    def share(flag: String, x: Double): Unit =
      if !(x >= 0.0 && x <= 1.0) then usage(s"$flag wants a share in 0..1, got $x")
    def belowOne(flag: String, x: Double): Unit =
      if !(x >= 0.0 && x < 1.0) then usage(s"$flag wants at least 0 and below 1, got $x")
    def nonNeg(flag: String, x: Double): Unit =
      if !(x >= 0.0) then usage(s"$flag wants a non-negative number, got $x")
    def positive(flag: String, x: Double): Unit =
      if !(x > 0.0) then usage(s"$flag wants a positive number, got $x")
    share("-trendshare", trendShare); share("-jumpvar", jumpVar); share("-jumprate", jumpRate)
    share("-leverage", leverage); share("-downshock", downShock)
    // A 2-sd shift is already past every fitted setting; negative would skew jumps UP.
    if jumpSkew < 0.0 || jumpSkew > 2.0 then
      usage(s"-jumpskew $jumpSkew out of range; needs 0 <= skew <= 2")
    share("-recoveryfloor", recoveryFloor); share("-inflprob", inflProb)
    share("-inflspeed", inflSpeed)
    belowOne("-volpersist", volPersist); belowOne("-haltlimit", haltLimit)
    positive("-depth", depth); positive("-duration", duration)
    nonNeg("-stress", stress); nonNeg("-beta", beta); nonNeg("-volofvol", volOfVol)
    nonNeg("-value", valuePull); nonNeg("-recoverydrag", recoveryDrag)
    nonNeg("-newsrate", newsRate); nonNeg("-newssize", newsSize); nonNeg("-refugedays", refugeDays)
    newsBudgetRefusal(newsRate, newsSize).foreach(why => usage(why))
    nonNeg("-satbeta", satBeta); nonNeg("-satidio", satIdio); nonNeg("-rangescale", rangeScale)
    nonNeg("-rangedown", rangeDown)
    if rangeDown > 0.0 && rangeScale <= 0.0 then
      usage("-rangedown requires -rangescale > 0: it shapes the sampled bar")
    nonNeg("-volidio", volIdio); nonNeg("-divyield", divYield)
    if volIdio > 0.0 && rangeScale <= 0.0 then
      usage("-volidio requires -rangescale > 0: volume rides the range")
    nonNeg("-disasterrate", disasterRate); nonNeg("-disastersize", disasterSize)
    share("-disasterrecover", disasterRecover)
    // beliefShare 1.0 would unmoor perceived fair from the fundamental entirely -- the pull
    // chases its own shadow and nothing anchors the price level.  Strictly below 1.
    if beliefShare < 0.0 || beliefShare >= 1.0 then
      usage(s"-beliefshare $beliefShare out of range; needs 0 <= share < 1")
    if beliefShare > 0.0 && beliefYears <= 0.0 then
      usage(s"-beliefshare $beliefShare needs -beliefyears above 0")
    nonNeg("-capyears", capYears)
    if capYears > 0.0 && capWindow <= 0.0 then
      usage(s"-capyears $capYears needs -capwindow above 0")
    if disasterRate > 0.0 && (disasterSize <= 0.0 || disasterLen <= 0.0) then
      usage(s"-disasterrate $disasterRate needs -disastersize and -disasterlen above 0")
    if disasterRecover > 0.0 && disasterRecLen <= 0.0 then
      usage(s"-disasterrecover $disasterRecover needs -disasterreclen above 0")
    nonNeg("-crowdimpact", crowdImpact); nonNeg("-panic", panic); nonNeg("-fundvol", fundVol)
    nonNeg("-ratemean", rateMean); nonNeg("-easing", easing); nonNeg("-unwind", unwind)
    nonNeg("-refuge", refuge); nonNeg("-inflsize", inflSize); nonNeg("-ratespeed", rateSpeed)
    nonNeg("-discount", discount); nonNeg("-margin", margin); nonNeg("-cost", cost)
    // `-drift` carries no domain: a negative fundamental drift is a world, not an error.
    // The PAIR is what no per-dial check can see -- `jumpScale` divides by `jumpRate`.
    if jumpVar > 0.0 && jumpRate <= 0.0 then
      usage(s"-jumpvar $jumpVar needs -jumprate above 0: the jump size is set by jumpVar/jumpRate")
    // The loss is only comparable on the ensemble the -noise weights were frozen from, so -fitness
    // pins 60x80 -- and REFUSES rather than ignores an explicit override, the same rule -emitfrom
    // follows.  Accepted-then-ignored is how "the loss improved" gets read off a different sample.
    if fitnessOnly && (pathsGiven || yearsGiven) then
      usage("-fitness scores the frozen 60x80 ensemble; -paths/-years do not apply")
    val crowd = crowdName.toLowerCase match
      case "momentum"  => Crowd.Momentum
      case "volscaled" => Crowd.VolScaled
      case t if t.startsWith("trend") =>
        Crowd.Trend(t.drop(5).toIntOption.filter(_ > 0).getOrElse(
          usage(s"unknown -crowd [$crowdName]; use momentum, trendNNN, volscaled, or drawdownNN")))
      case t if t.startsWith("drawdown") =>
        Crowd.Drawdown(t.drop(8).toIntOption.filter(d => d > 0 && d < 100).getOrElse(
          usage(s"unknown -crowd [$crowdName]; use momentum, trendNNN, volscaled, or drawdownNN")))
      case other => usage(s"unknown -crowd [$other]; use momentum, trendNNN, volscaled, or drawdownNN")
    val anchors = anchorsNamed(anchorSpec)
    val w = World(trendShare, depth, stress, beta, drift = drift, fundVol = fundVol,
                  rateMean = rateMean,
                  volPersist = volPersist, volOfVol = volOfVol,
                  jumpVar = jumpVar, jumpRate = jumpRate, leverage = leverage,
                  downShock = downShock, jumpSkew = jumpSkew,
                  newsRate = newsRate, newsSize = newsSize, valuePull = valuePull,
                  recoveryDrag = recoveryDrag, recoveryFloor = recoveryFloor,
                  haltLimit = haltLimit,
                  disasterRate = disasterRate, disasterSize = disasterSize,
                  disasterLen = disasterLen,
                  disasterRecover = disasterRecover, disasterRecLen = disasterRecLen,
                  beliefShare = beliefShare, beliefYears = beliefYears,
                  capYears = capYears, capWindow = capWindow,
                  crowd = crowd, crowdImpact = crowdImpact, panic = panic,
                  duration = duration, easing = easing, unwind = unwind, refuge = refuge,
                  refugeDays = refugeDays,
                  inflProb = inflProb, inflSize = inflSize,
                  inflSpeed = inflSpeed, rateSpeed = rateSpeed, discount = discount, margin = margin,
                  satBeta = satBeta, satIdio = satIdio, rangeScale = rangeScale,
                  rangeDown = rangeDown, volIdio = volIdio, divYield = divYield)

    // SATELLITE PROTOTYPE: write per-path primary+satellite LOG prices for grading against the
    // SPY-QQQ coupling anchors (the joint_anchor conventions, graded python-side).  Deliberately
    // OUTSIDE the -emit interface: no sidecar, no schema claim -- a measurement tap, not a
    // consumer surface.  LOG prices, not levels: the twins' transcendentals carry a 1-ulp
    // latitude (PARITY.md §6), and a level near 1e6 rendered at %.6f puts that latitude within
    // ~1e-4 of a rounding tie -- a handful of cross-language print flips per 40 paths, measured.
    // A log near 13 puts the same latitude nine orders under the printed digit: a rendering
    // rule, not a tolerance.
    if jointEmit.nonEmpty then
      if satBeta <= 0.0 then usage("-jointemit requires -satbeta > 0")
      for k <- 0 until paths do
        val p = simulate(w, years, seed + k.toLong * 7919L)
        val rows = "logPrice\tlogSat" +: Vector.tabulate(p.price.length) { i =>
          s"${ef(math.log(p.price(i)))}\t${ef(math.log(p.sat(i)))}"
        }
        f"$jointEmit-$k%03d.tsv".asPath.writeLines(rows)
      return
    // RANGE PROTOTYPE: per-path log price/high/low for grading against the bars anchors
    // (the bars_anchor conventions, graded python-side).  Same contract as -jointemit: a
    // measurement tap outside the -emit interface, LOG columns per the parity lesson.
    if barsEmit.nonEmpty then
      if rangeScale <= 0.0 then usage("-barsemit requires -rangescale > 0")
      for k <- 0 until paths do
        val p = simulate(w, years, seed + k.toLong * 7919L)
        val header = if volIdio > 0.0 then "logPrice\tlogHigh\tlogLow\tlogVolume"
                     else "logPrice\tlogHigh\tlogLow"
        val rows = header +: Vector.tabulate(p.price.length) { i =>
          val base = s"${ef(math.log(p.price(i)))}\t${ef(p.logHi(i))}\t${ef(p.logLo(i))}"
          if volIdio > 0.0 then s"$base\t${ef(p.logVolume(i))}" else base
        }
        f"$barsEmit-$k%03d.tsv".asPath.writeLines(rows)
      return
    if calibrateN > 0 then
      calibrate(anchors, calibrateN, w, seed)
      return
    if fitnessOnly then
      val st = measure(simPaths(w, 60, 80, seed), 80)
      val (loss, rows) = fitness(anchors, st, extremeScoreStats(anchors, 60, seed, w))
      println(f"fitness loss $loss%.3f  (lower is better; includes 0.5 per failed gate check)")
      rows.foreach((n, m, t, term) => println(f"  $n%-22s model $m%8.2f   target $t%8.2f   term $term%6.3f"))
      gateChecks(anchors, st).filter(!_._2).foreach((n, _, _) => println(f"  FAILED GATE: $n%s  (+0.500)"))
      // The model column for these rows is a DIFFERENT statistic from -validate's: said here
      // because a reader comparing the two tables would otherwise take the disagreement for a bug.
      if rows.exists((n, _, _, _) => ExtremeTargets.contains(n)) then
        println(s"  NOTE: ${ExtremeTargets.toVector.sorted.mkString(", ")} — the model value scored (and shown")
        println("    above) is the MEDIAN of single histories at the anchor's own horizon, the")
        println("    converging centre of the distribution -validate's percentile reads.  The pooled")
        println("    ensemble minimum is never scored: its distance from a one-history anchor")
        println("    tracks the ensemble size.")
      return
    if releaseReport then
      runReleaseReport(anchors, paths, years, seed, w)
      return
    if crossAsset then
      // Exits non-zero on an in-support miss, or when a relation graded nothing (INCONCLUSIVE)
      // — an EXTRAP cell alone is disclosed, not fatal.
      if !runCrossAssetReport(anchors, paths, years, seed, w) then System.exit(1)
      return
    if noiseReport then
      // -years is ignored deliberately: the horizons come from the anchors themselves, and the
      // seed-noise section from the scoring configuration.
      runNoiseReport(anchors, paths, seed, w)
      return
    if strategies then
      runStrategySweep(anchors, paths, years, seed, cost, single, w, gateReq)
      return
    if powerReport then
      runPowerReport(anchors, paths, seed, cost, single, w, gateReq, powerArms, powerYears)
      return
    if ddShape then
      runDrawdownShape(anchors, paths, years, seed, w)
      return
    if bufferReport then
      runBufferReport(anchors, paths, years, seed, cost, single, w, gateReq)
      return

    eprintln(s"simulating $paths paths x $years years")
    val sims = simPaths(w, paths, years, seed)
    val st = measure(sims, years)

    // The verdict is a property of the WORLD, so it is measured on an ensemble large enough for
    // the conditional mechanism statistics to exist AND at the horizon the bands were calibrated
    // at.  Judging the world by the one path being written made every short export raise all four
    // mechanism failures; judging it at a short `-years` failed fixed bands on horizon-growing
    // statistics the same way (`GateYears`).  The rows are built ONCE: the printed table and
    // every sidecar render these same rows, so the extreme rows' own-horizon ensemble -- the
    // expensive part -- runs once per invocation, not once per emitted path.
    val (verdictPaths, verdictYears) = verdictSpec(emit.nonEmpty, emitGate, paths, years)
    val verdictSt =
      if (verdictPaths, verdictYears) == (paths, years) then st
      else measure(simPaths(w, verdictPaths, verdictYears, seed), verdictYears)
    val verdictRows = fidelityRows(anchors, verdictSt, verdictPaths, seed, w)

    if emit.nonEmpty then
      val realismBad   = failedIn(anchors, verdictSt, GateClass.Realism)
      val mechanismBad = failedIn(anchors, verdictSt, GateClass.Mechanism)
      val fidelityBad  = failedIn(anchors, verdictSt, GateClass.Fidelity)
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
      // REFUSED, not warned about.  Every other gate verdict is advisory because an unrealistic
      // world is still a world; a path holding a non-finite price is not data at all.  The dial
      // domains close the routes reachable from the command line; this closes the file.
      def refuseNonFinite(p: Path, k: Int, f: String): Unit =
        if !p.price.forall(_.isFinite) || !p.sat.forall(_.isFinite) ||
           !p.logHi.forall(_.isFinite) || !p.logLo.forall(_.isFinite) ||
           !p.logVolume.forall(_.isFinite) then
          eprintln(s"REFUSED: path $k holds a non-finite value; nothing written to $f")
          System.exit(2)
      val written =
        if emitAll then
          // At the default offset this IS the report ensemble; shifted, the range is re-simulated
          // (in parallel, not one at a time through `pathAt`) because the report and the gate stay
          // measured on 0 until paths -- the verdict describes the WORLD, not the chunk.
          val batch = if emitFrom == 0 then sims else simPathRange(w, emitFrom, paths, years, seed)
          val width = indexWidth(emitFrom + paths - 1)
          for k <- emitFrom until emitFrom + paths yield
            val f = indexedName(emit, k, width)
            refuseNonFinite(batch(k - emitFrom), k, f)
            writeEmitted(anchors, f, batch(k - emitFrom), k, w, years, seed, emitStart, verdictSt,
                         verdictPaths, verdictYears, verdictRows)
            f
        else
          val p = pathAt(emitPath)
          refuseNonFinite(p, emitPath, emit)
          writeEmitted(anchors, emit, p, emitPath, w, years, seed, emitStart, verdictSt,
                       verdictPaths, verdictYears, verdictRows)
          Vector(emit)
      val sessions = pathAt(if emitAll then emitFrom else emitPath).price.length
      eprintln(s"wrote ${written.size} path(s), ${EmitColumns.size + (if w.satBeta > 0.0 then 1 else 0)
        + (if w.rangeScale > 0.0 then 2 else 0) + (if w.volIdio > 0.0 then 1 else 0)
        + (if w.divYield > 0.0 then 2 else 0)} columns x $sessions sessions, " +
               s"to ${written.head}${if written.size > 1 then s" .. ${written.last}" else ""} " +
               s"(+ sidecar ${sidecarName(written.head)})")

    val allRets = sims.map(s => dailyReturns(s.price))
    val annVol  = allRets.map(r => math.sqrt(r.map(x => x * x).sum / r.length * DaysPerYear))
    val annRet  = sims.map(s => math.log(s.price.last / s.price.head) / years * 100.0)

    println(f"paths $paths%d x $years%d years   ${paths * years}%d simulated years")
    // `med` and `pctile` both drop non-finite paths, so summarising the survivors in silence is how
    // a contaminated ensemble reads as an ordinary world.  The count is stated where the medians it
    // excludes are read.
    val nonFinitePaths = sims.count(s => !s.price.forall(_.isFinite))
    if nonFinitePaths > 0 then
      println(f"  WARNING: $nonFinitePaths%d of ${sims.size}%d paths hold a non-finite price and are EXCLUDED from")
      println("           every median and percentile below -- this world is not simulable as dialled")
    println()
    println(f"  annualised return      median ${st.annRet}%6.2f%%   5th ${pctile(annRet, 0.05)}%6.2f%%   95th ${pctile(annRet, 0.95)}%6.2f%%")
    println(f"  annualised volatility  median ${st.vol * 100}%6.2f%%   5th ${pctile(annVol, 0.05) * 100}%6.2f%%   95th ${pctile(annVol, 0.95) * 100}%6.2f%%")
    println(f"  daily return kurtosis  median ${st.kurt}%6.2f")
    println(f"  volatility clustering  lag  1 ${st.ac1}%6.3f   lag 20 ${st.ac20}%6.3f")
    // The line above is |r| and the line below is r, which is the whole reason both are printed:
    // they are different axes and a world can be right on one and wrong on the other.
    println("  trend persistence      variance ratio " +
            VarRatioLadder.map(q => f"${q}%dd ${vrOf(st, q)}%.3f").mkString("  ") +
            "   (1.0 = no serial dependence)")
    println("                         envelopes " +
            VarRatioBands.map((q, lo, hi) => f"${q}%dd $lo%.2f-$hi%.2f").mkString("  ") + "; slopes " +
            VarRatioSlopeBands.map { (a, b, lo, hi) =>
              f"$a%d->$b%d ${vrOf(st, b) - vrOf(st, a) + 0.0}%+.3f ($lo%+.2f..$hi%+.2f)" }.mkString("  "))
    println()
    println(f"  drawdowns of 15%%+      ${st.nEpisodes}%d, ${st.epPerPath}%.1f per path; ${st.censored}%d unrecovered at path end (included in depth)")
    println(f"  their depth            median ${st.depthMed}%6.1f%%   worst ${st.worstDepth}%6.1f%%")
    println(f"  recovery shape         V ${st.vCount}%d   balanced ${st.midCount}%d   U ${st.uCount}%d")
    println(f"  bond refuge            vol ${st.bondVol * 100}%.1f%% (24y windows)   growth-crash ${pm(st.bondGrowth, 0, 1)}%s   infl-crash ${pm(st.bondInfl, 0, 1)}%s")
    println(f"  stock-bond correlation calm ${pm(st.corrCalm, 0, 2)}%s   inflation regime ${pm(st.corrInfl, 0, 2)}%s")
    println(f"  realized inflation     ${st.inflAnn}%.2f%%/yr median (deterministic from regime pressure; no draws consumed)")
    // The channel readings the gate rows grade, printed so a channel FAIL can be sized; absent
    // when the channel is off, like the rows themselves.
    st.sat.foreach { sd =>
      println(f"  satellite leg          corr ${sd.corr}%.3f   |r| corr ${sd.absCorr}%.3f   beta ${sd.beta}%.3f   " +
              f"vol ratio ${sd.volRatio}%.3f   kurtosis ratio ${sd.kurtRatio}%.3f")
      println(f"                         clustering ratio lag 1 ${sd.ac1Ratio}%.3f   lag 20 ${sd.ac20Ratio}%.3f   " +
              f"d5 ratio ${sd.d5Ratio}%.3f   d10 ratio ${sd.d10Ratio}%.3f   crash ratio ${sd.crashRatio}%.3f")
    }
    st.bars.foreach { b =>
      println(f"  bar range              vs cc vol ${b.rangeOverCcvol}%.3f   clustering ${b.rangeAcf1}%.3f   down/up ${b.rangeDownup}%.3f")
      if b.volSd.isFinite then
        println(f"  bar volume             sd ${b.volSd}%.3f   vs range ${b.volCorrRange}%.3f")
    }
    if st.divYieldMean.isFinite then
      println(f"  dividend yield         mean ${st.divYieldMean}%.2f%%/yr   (the dial, varying with fundamental/price; " +
              f"anchor ${anchors.divYield}%.2f, band ${anchors.divYieldBand._1}%.1f-${anchors.divYieldBand._2}%.1f)")
    println(f"  depth profile          share of sessions below the running peak, median path")
    // Against the relation at THIS world's own volatility and return, not against SPY's levels:
    // SPY produced 0.447 / 0.315 / 0.169 at 18.6% volatility and 0.554 return per vol, so printing
    // them beside a world at a different operating point invites exactly the comparison the rungs
    // were restated to stop -- and would show a correct world as a large miss.
    val eqVolPct = st.vol * 100.0
    println(f"    equity               >5%% ${st.ddEq5}%.3f   >10%% ${st.ddEq10}%.3f   >20%% ${st.ddEq20}%.3f" +
            f"      real funds at this vol/return " +
            f"${equityDepthExpected(0.05, EquityD5Corr, eqVolPct, st.retVol)}%.3f / " +
            f"${equityDepthExpected(0.10, EquityD10Corr, eqVolPct, st.retVol)}%.3f / " +
            f"${equityDepthExpected(0.20, EquityD20Corr, eqVolPct, st.retVol)}%.3f")
    println(f"    bond                 >5%% ${st.ddBd5}%.3f   >10%% ${st.ddBd10}%.3f   >20%% ${st.ddBd20}%.3f" +
            f"      real TLT   -   / 0.510 /   -")
    println(f"  binding diagnostics    trend share ${st.trendShare}%.2f (pinned ${st.trendPinned * 100}%.1f%%, " +
            f"target saturated ${st.targetSat * 100}%.1f%%)   bond spiral ${st.pctBondStress * 100}%.1f%% of sessions   " +
            f"clamped ${st.clampPct}%.3f%% of all sessions, " +
            f"${st.tailFloorPct}%.1f%% of tail sessions   " +
            f"halts ${st.haltPct}%.3f%%")
    println(f"                         crowd flow ${st.crowdFlow * 1e4}%.2f bp/session " +
            f"(${st.crowdFlow / SigmaN * 100}%.1f%% of the noise term) — the reflexive channel   " +
            f"macro disasters ${st.disPerCentury}%.2f/century")
    println(f"  valuation gap          sd log(p/fair) ${st.valDisp}%.3f   century max +${st.maxOver * 100}%.0f%% over fair" +
            f"   (record proxy: sd log CAPE 0.24-0.41, peaks +70-100%%)")

    println()
    // The anchors do NOT share one window, and a single-window label invites a reader to re-derive
    // them from it and conclude the model has drifted.  The depth rungs are the exception by
    // construction: they are graded against a RELATION evaluated at this world's own volatility and
    // return, so they carry no window of their own to be compared at.
    // The anchor SET is named because the equity rows are asset-specific: the same world graded
    // against a different index is a different verdict, and a report that does not say which index
    // it used cannot be read six months later.
    println(s"  fidelity against ${anchors.name} targets, by anchor (each row is against the " +
            "window named for it):")
    // Named whenever the two differ, so a reader cannot take the verdict for a reading of the
    // ensemble described above it.
    if (verdictPaths, verdictYears) != (paths, years) then
      println(s"    graded on $verdictPaths paths x $verdictYears years — the calibration horizon; " +
              s"the report above describes $paths x $years")
    println(s"    equity ${anchors.equityWindow}   |   depth rungs 35 equity funds 2001-2026, vs each world's")
    println(s"      OWN volatility and return   |   return per vol ${anchors.retVolWindow}")
    println(s"    clustering ${anchors.clusterWindow} (horizon-dependent: the statistic moves with the")
    println("      model is scored on 100-year paths)   |   refuge long Treasury   |   bond depth")
    println("      rung clean TLT, 24y")
    println("    NOTE: bond volatility alone is measured over 24-YEAR windows, not the whole path —")
    println("      it is the one horizon-dependent statistic whose anchor can only come from fund")
    println("      data, and no clean bond-fund series runs longer.  Every other row is whole-path.")
    println("    NOTE: a row whose model statistic is an EXTREME over the ensemble carries no ratio —")
    println("      the deepest of ~4,400 pooled episodes over the deepest of ONE history grades the")
    println("      sample size, not the model, and deepens without bound as -paths grows.  Those rows")
    println("      report where the record falls among single histories of its own length instead;")
    println("      near 50% the record is a typical history of this model.  Same reading as -noise.")
    verdictRows.foreach { r =>
      val flag = if r.miss then "  <-- MISS" else ""
      val judgement = (r.ratio, r.pctile) match
        case (Some(x), _)    => f"ratio $x%5.2f"
        case (None, Some(p)) => f"record@ $p%3d%% of ${r.horizonYears}%dy histories (n=${r.nHistories}%d)"
        case (None, None)    => f"record@  n/a — ${r.nHistories}%d histories, needs $ExtremeMinHistories%d"
      println(f"     ${r.name}%-22s model ${r.model}%8.2f   real ${r.real}%8.2f   $judgement%s$flag%s")
    }

    if validate then
      val checks = gateChecks(anchors, verdictSt)
      val bad    = GateClass.values.map(c => c -> failedIn(anchors, verdictSt, c)).toMap
      def verdict(c: GateClass) = if bad(c).isEmpty then "PASS" else "FAIL"
      println()
      println("  acceptance gate:")
      val una = unanchoredIn(verdictSt)
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

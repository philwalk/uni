//#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
package uni.apps

//> using scala 3.7.2
//> using dep org.vastblue:uni_3:0.24.4

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
//   clamp +-50%, pure numerical guard (counted; gate <0.02%) | no-trade band 0.05 | burn-in 7056 sessions (28 years; the slowest state, the valuation gap, takes ~20) |
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
  // intra-bar extremes; the bar's open is the prior close unless `overnight` > 0) and
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
  // normalized by, and `channels.dividend` the mean yield read.  THE OPEN, same release: `world`
  // gained `overnight`, the TSV `logOpen` (present ONLY when `overnight > 0` -- the bar's open as
  // a log; `logHigh`/`logLow` then bracket the open and the close rather than the prior close and
  // the close), and `channels.open` the share readings.  THE BASKET, same release: `world` gained
  // `basket` and its four dials, the TSV `logBasket` (the equal-weight aggregate, log) and
  // `logName1..N` (present ONLY when `basket > 0`; N from the header), and `channels.basket` the
  // three levels' readings.  A channels-off schema-11 file is byte-identical to its schema-10
  // counterpart except the schema number and the new zero world fields.
  // 11 -> 12: THE MACRO PANEL.  `world` gained `macro` (the flag's name, like every dial's key;
  // the Scala field is `macroPanel` because `macro` is reserved there); the TSV gained
  // `macroSpread`, `macroSlope`, `macroCond`, `macroIvol` (present ONLY when `macro > 0` -- levels in their
  // counterparts' units, BAA10Y / T10Y2Y / NFCILEVERAGE / VIXCLS, each the value an agency would
  // MEASURE that session: cadence, release lag and revisions are the consumer's point-in-time
  // layer, applied to an emitted column exactly as to the real series); and `channels.macro` the
  // readings the macro rows grade, naming each column's counterpart and natural cadence so a
  // consumer's loader can route it through the table its FRED name would get.  A panel-off
  // schema-12 file is byte-identical to its schema-11 counterpart except the schema number and
  // the new zero world field.
  // 12 -> 13: THE PANEL'S TWO NEW MEMBERS.  The TSV gained `macroYield10` and `macroCredit`
  // (present ONLY when `macro > 0`, like the first four -- the 10-year yield in pp against DGS10,
  // and the borrowing stock read as credit over output in percent against TOTBKCR/GDP), and
  // `channels.macro` their member blocks: a schema-12 reader that took the column list as fixed
  // at four, or indexed the members positionally, misroutes both.  EVERY POOLED PANEL STATISTIC
  // also gained a `perPath` object beside it -- `[p5, p50, p95]` of the per-path readings, the
  // width of the null a single path sits in -- at the panel level and inside each member, so a
  // reader that took `channels.macro` as flat numbers finds objects.  THE VOL RESPONSE, same
  // schema: `world` gained `volResp`, `volRespPhi`, `volRespCap`, `volRespAttack`, `jumpResp` and
  // `stressAdapt`, and the item-12 cascade gained `noiseAsymPhi` and `noiseAsymCap`.  THE SLOW
  // REPRICING CHANNEL, same schema again: `world` gained `slowShare`, `slowVol`, `slowLev`,
  // `slowPhi`, `slowPerm` and `slowBeta`.  A reader
  // that reconstructs a `World` from a schema-12 sidecar and runs it here gets no volatility
  // response and the slow amplifier scale -- a different market, the `crowdImpact` case again.
  // A panel-off schema-13 file differs from its schema-12 counterpart in the schema number and
  // the eight new world fields, which are NOT all zero: `volRespPhi`, `volRespCap`, `stressAdapt`
  // and `noiseAsymPhi` carry their off values.
  // 13 -> 14: THE PANEL'S POLICY RATE.  The TSV gained `macroPolicy` (present ONLY when
  // `macro > 0` -- the overnight rate in pp against DFF: the loop's own policy rate re-set at a
  // meeting to the nearest quarter point and held, which is how the record's target is published)
  // and `channels.macro` its member block.  The rate itself has always been the `rate` column, in
  // DECIMAL and unpublished; nothing about it changed, and a consumer reading DFF wants the pp
  // staircase beside the credit spread and the term spread, in the units its thresholds are in.
  // A panel-off schema-14 file is byte-identical to its schema-13 counterpart except the schema
  // number.
  // 14 -> 15: THE CREDIT SYSTEM SPLIT.  The TSV gained `macroBankCredit` (TOTBKCR) and
  // `macroOutput` (GDP) -- output an INDEX at 100 on the first emitted session, bank credit the
  // ratio times it, so it starts at the ratio's first value -- and `channels.macro` their member
  // blocks; `macroCredit` is the ratio they imply and its VALUES CHANGE, because it
  // is now its own slow stock rather than the leverage cycle read in percent -- a schema-14
  // reader gets the same column meaning a materially different series, and one that turns over
  // decades rather than every four years.  A panel-off schema-15 file is byte-identical to its
  // schema-14 counterpart except the schema number.
  // 15 -> 16: THE IMPLIED-VOL LEVEL.  `macroIvol`'s VALUES CHANGE: the member is re-levelled in
  // the premium's own statistic (`level.kIv`, now in the sidecar's `level` block), so its log
  // premium over forward realized vol is the record's in every world; the old level counted the
  // slow channel's variance twice.  A panel-off schema-16 file is byte-identical to its schema-15
  // counterpart except the schema number and the `kIv` key.
  // 16 -> 17: THE BUST SWING.  `world` gained `bustAmp` (0 in every shipped world); a schema-17
  // file is byte-identical to its schema-16 counterpart except the schema number and that key.
  // 17 -> 18: THE VALUATION CYCLE AS A STATE.  `world` gained `cycleSd`, `cycleYears` and
  // `beliefLeak`, and the news, body, regime and bond dials `newsLev`, `newsRevert`, `newsScale`,
  // `newsBond`, `newsBondSkip`, `creditRegime`, `creditRegimeRate`, `slowBondInfl`, `noiseSkew`
  // and `newsFlip` (0 in every shipped world); a schema-18 file is byte-identical to its
  // schema-17 counterpart except the schema number and those keys.  Where the cycle is on,
  // `price` starts on it rather than at `fundamental`.
  // In the same schema each `gate.fidelity` row gained `target`, `recordBand` and `recordPercentile`,
  // and `real` became the record, read the model's way, on every row that carries a band; such a
  // row's `model` is read on paths as long as its record (`recordBandYears`), not the verdict's,
  // and its `horizonYears` is that length.
  // `recordBand` is the record's JOINT band (`recordBandJoint`): a set's banded rows all fall
  // inside theirs together on 90% of the record's resamples, and `miss` is true outside it.
  val EmitSchema: Int = 18

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
    "-worldset F   ; seed every dial from a member of an exported calibration archive, the",
    "              ;   file jsrc/marketSimSearch.sc -export writes: a SET of worlds all",
    "              ;   consistent with the record, so a verdict can be formed across it",
    "              ;   rather than from one best fit.  Seeded as -atrelease is, and explicit",
    "              ;   dial flags override it wherever they appear.  Refuses a file whose",
    "              ;   dials are not exactly this binary's -- an omitted dial would take the",
    "              ;   shipped default and be a different world under a member's name",
    "-worldindex K ; which member of -worldset's file, by its own `member` number; default 0",
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
    "-newslev X    ; NEWS THAT FOLLOWS LEVERAGE: scale the news intensity by 1 + X x the credit",
    "              ;   stock's rise over its trailing-year average, clipped to [0, 2] so the",
    "              ;   average rate is kept, with the drift paid on the same intensity.  Default 0",
    "-newsrevert S ; the share of each news markdown that does not reach the fundamental, in",
    "              ;   [0, 1]: value capital buys it back over the following sessions.  Default 0",
    "-newsscale S  ; the share of each news markdown that scales with the session's conditional",
    "              ;   vol (the log-vol state, the leverage kick, the vol response), in [0, 1].",
    "              ;   Default 0",
    "-noiseskew D  ; THE SKEWED BODY: the diffusion's unit shock as a mean-zero skew-normal with its",
    "              ;   long tail on the left, skew D in [0, 1) (0.9 moves the up-day share about",
    "              ;   1.5 points).  Default 0",
    "-newsflip S   ; NEWS PAID BY THE BODY: the share of the price's news compensator paid by",
    "              ;   turning moderate down diffusion shocks up rather than a steady lift, in",
    "              ;   [0, 1].  Default 0",
    "-newsbond B   ; THE BOND LEG OF NEWS: the bond's fair and price rise B x the markdown x",
    "              ;   (duration / 13.5) the session it lands, decaying over half a year, on top",
    "              ;   of the bond's own noise.  Default 0",
    "-creditregime A ; THE CREDIT-TRIGGERED VOL REGIME: turbulent spells that open at credit highs,",
    "              ;   vol x exp(A) held half a year, then decaying.  Default 0",
    "-creditregimerate L ; the regime's onsets per year per sd of credit growth over half an sd",
    "-slowbondinfl S ; the share of the slow channel's bond leg that reverses in an inflation",
    "              ;   regime, in [0, 1].  Default 0",
    "-newsbondskip S ; THE BOND ANSWERS SOME NEWS: the share of news events the bond leg skips,",
    "              ;   the rest scaled by 1 / (1 - S) to keep its mean, in [0, 1).  Default 0",
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
    "-overnight X  ; THE OPEN: the overnight share of the session's diffusive variance (0 <= X",
    "              ;   < 1); jumps and news land overnight whole, the bar runs from the open,",
    "              ;   and -emit gains logOpen.  Record share 0.33 (SPY) / 0.28 (QQQ) of daily",
    "              ;   variance, graded when on.  Default 0 = the open is the prior close",
    "-basket N     ; THE BASKET: N single names, each the shared SECTOR leg (-basketbeta on the",
    "              ;   primary's observed return + -basketsector idio riding state x spiral) plus",
    "              ;   its own idio (-basketidio, on the vol state alone -- so correlation rises",
    "              ;   in stress) and its own gaps (-basketgaps per year, t-jumps).  The aggregate",
    "              ;   is the sector.  Anchored N 8 on folio's semis under SMH.  Default 0 = off",
    "-basketdrift X; cross-sectional DRIFT DISPERSION: sd of the names' own annual log-drift",
    "              ;   offsets as a fraction of the primary's vol, centred so the sector is",
    "              ;   untouched.  ANCHORED AT 0: among the eight the spread of realized drift",
    "              ;   (0.068) is what a 14.6-year window alone generates (0.070), and survivors",
    "              ;   truncate the left tail, so 0 is a floor from biased data.  Their time",
    "              ;   below peak is the COMMON drift (+0.304 vs the shared leg's +0.117), not",
    "              ;   this.  At 0 every name has the SAME expected drift, so the basket is a null",
    "              ;   world for a rule that ranks names: sweep the dial for its detection floor",
    "-macro 1      ; THE MACRO PANEL: nine observables DERIVED from the model's own state, in",
    "              ;   their counterparts' units -- macroSpread (BAA10Y: equity + bond stress),",
    "              ;   macroSlope (T10Y2Y: the 10y-2y expectation the rate process implies; the",
    "              ;   one anchored-scale member), macroCond (NFCILEVERAGE: crowd share +",
    "              ;   valuation gap, raw), macroIvol (VIXCLS: the conditional sd x the record's",
    "              ;   variance risk premium) -- each a persistent-noise read sized to the record's",
    "              ;   predictive R^2, so a rank rule sees what it sees on the record -- and five",
    "              ;   DRAW-FREE levels: macroYield10 (DGS10), macroPolicy (DFF: the loop's own",
    "              ;   policy rate in pp, re-set at a meeting to the nearest quarter point and",
    "              ;   held), and the credit system -- macroBankCredit (TOTBKCR) and macroOutput",
    "              ;   (GDP) as indices, with macroCredit (TOTBKCR/GDP, %) the ratio they imply.",
    "              ;   Cadence, release lag and revisions are the consumer's point-in-time layer;",
    "              ;   the sidecar names each counterpart.  Reaches no price.  Default 0 = off",
    "-levpersist P ; THE PERSISTENT KICK: the leverage kick's own memory.  At 0 a decline raises",
    "              ;   only the NEXT session's diffusive noise; at P it raises the following ones",
    "              ;   too, through weights that sum to 1 -- the integrated response per decline is",
    "              ;   unchanged, only its shape.  The record's vol after a fall stays elevated for",
    "              ;   2-20 sessions; 0 = bit-identical",
    "-noiseasym T  ; THE ASYMMETRIC NOISE VOL: the diffusive noise times exp(g), g decaying at",
    "              ;   -noiseasymphi and driven by MINUS the session's own diffusive draw -- the",
    "              ;   EGARCH asymmetry term on the one input the price cannot inflate;",
    "              ;   0 = bit-identical",
    "-noiseasymphi P ; the cascade's DECAY, default 0.96 -- a 17-session half-life; higher carries",
    "              ;   its response out to lag 20.  A persistence in [0, 1)",
    "-noiseasymcap C ; the cascade's CAP, 0 = uncapped: the largest log multiplier it may apply.",
    "              ;   Its profile gain comes from the state's BODY and its kurtosis from the right",
    "              ;   tail, so a cap buys the one without the other",
    "-volresp V    ; THE VOL RESPONSE: the diffusive noise times exp(V * S), S the session's",
    "              ;   decline in units of the conditional sd THAT GENERATED IT, accumulated at",
    "              ;   -volrespphi.  One decline's response is a PLATEAU, not an integral divided",
    "              ;   over the sessions after it -- the record's vol after a fall stays elevated",
    "              ;   for ~20 sessions.  Draw-free; 0 = bit-identical",
    "-volrespphi P ; the vol response state's DECAY, a persistence in [0, 1)",
    "-volrespattack A ; the vol response's ATTACK, 0 = none: the standardized decline through a",
    "              ;   fast EWMA before it accumulates, so the response BUILDS over 2-5 sessions",
    "              ;   instead of peaking at lag 1.  A persistence in [0, 1)",
    "-volrespcap C ; the vol response state's CEILING.  The state is unnormalized, so a stretch of",
    "              ;   saturated readings compounds where the spiral amplifies -- the cap bounds",
    "              ;   the multiplier at exp(V * C) and leaves the plateau intact below it",
    "-jumpresp J   ; THE JUMP RESPONSE: the jump INTENSITY times exp(J * S), the same state",
    "              ;   -volresp reads, so the extra variance lands where the kurtosis budget and",
    "              ;   the skew already are.  Its compensator turns conditional with it.  Consumes",
    "              ;   no draw; 0 = bit-identical",
    "-bustamp A    ; THE BUST SWING: when a 0.2-log drawdown opens under a peak 0.5 log or more",
    "              ;   over the gap's 20-year mean, a months-long swing of amplitude A is repriced",
    "              ;   in the price and in perceived fair while the unwind makes new lows, the",
    "              ;   recovery drag relieved and the amplifier blind to it -- the record's mania",
    "              ;   bust: NDX 2000-02 spent 2.5 years at 53% vol in five legs and rallies.",
    "              ;   The swing never carries the price nearer than 0.10 log to the running",
    "              ;   peak (a mania's unwind never re-attains its high), and the state is cut",
    "              ;   to 0 once the unwind is over.  Own stream; 0 = bit-identical",
    "-beliefleak L ; THE BELIEFS' OWN FADE toward the fundamental, per year: the belief share",
    "              ;   stays -beliefshare at the daily scale and falls to share x mu/(mu + L) in",
    "              ;   the long run, which is what keeps the gap stationary.  0 = bit-identical",
    "-cyclesd S    ; THE VALUATION CYCLE AS A STATE: a slow stationary AR(1) in perceived fair",
    "              ;   with stationary sd S (log), started from its own law so paths begin",
    "              ;   stationary; beliefs track the gap net of it.  Own stream; 0 = bit-identical",
    s"-cycleyears Y ; its half-life in years (default ${Defaults.cycleYears}; the record's CAPE 11.5)",
    "-stressadapt M ; the equity spiral's SCALE speed: the EWMA weight on ret^2 that standardizes",
    "              ;   the decline the spiral's stress index reads.  0.005, a ~140-session memory,",
    "              ;   reads a persistently volatile stretch as continuous stress; faster lets the",
    "              ;   spiral tell volatile from stressed.  An EWMA weight in (0, 1); 0.005 is",
    "              ;   bit-identical, and the index the rest of the world reads is untouched",
    "-slowshare S  ; THE SLOW REPRICING CHANNEL: S of the diffusive VARIANCE leaves the order-flow",
    "              ;   channel and reprices the fundamental and the price TOGETHER, like the news",
    "              ;   jump -- so the value channel has nothing to arbitrage and the move never",
    "              ;   passes through the liquidity spiral.  Its volatility has LONG memory the",
    "              ;   amplifier cannot steepen, which is what puts the |r| autocorrelation",
    "              ;   profile back on the record's SHAPE.  A share in [0, 1); 0 = bit-identical",
    "-slowvol V    ; the channel's scale, a multiple of the session's base diffusive scale at this",
    "              ;   depth.  NOT derived from -slowshare: the order-flow channel reaches price",
    "              ;   multiplied by the spiral's gain and this one does not",
    "-slowlev L    ; the channel's OWN leverage effect, in units of its state's stationary sd, and",
    "              ;   the only thing driving that state.  A symmetric component is strictly worse",
    "-slowphi P    ; the state's persistence, in [0, 1)",
    "-slowperm M   ; the share of the repricing that reaches the FUNDAMENTAL, in [0, 1].  The rest",
    "              ;   opens a gap the value channel closes; at 1 the move is permanent and the",
    "              ;   momentum crowd chases it into a variance ratio (1.14 against 1.00)",
    "-slowbeta B   ; the BOND's loading on the same repricing, opposite sign -- a flight-to-quality",
    "              ;   factor.  At 0 the channel is equity-only and the worst equity days have no",
    "              ;   bond response, which costs the tail hedge and the growth-shock rally",
    "-stressscale E ; THE AMPLIFIER STUDY: the spiral's excess gain scaled by (depth/17.4)^E, so a",
    "              ;   thinner market's liquidity event is not proportionally larger than the",
    "              ;   reference world's (the record's crash count is volatility-flat across a",
    "              ;   cross-section; the model's rises at 1.4-1.8).  0 = today; 1 = the same",
    "              ;   absolute event everywhere.  The default world is unchanged at any E",
    "-levgain X    ; THE LEVERAGE CYCLE: declines that follow leverage.  A borrowing stock swings",
    "              ;   over years as a credit cycle of its own and is paid down under stress; the",
    "              ;   spiral's gain is multiplied by 1 + X(the stock's rise over its trailing",
    "              ;   year), so an ordinary shock cascades into a 20% decline where leverage has",
    "              ;   been building.  Record: a 20% peak within a quarter is 2.0-2.7x as likely",
    "              ;   with the leverage index in its top decile, ~1x for 10% dips.  0 = the",
    s"              ;   amplification is bit-identical (the 0.23.1 world).  Default ${Defaults.levGain}",
    "-macronull 1  ; THE NULL PANEL: the four macro columns come from a SIBLING path -- the same",
    "              ;   world at another seed -- so their marginals and persistence are this",
    "              ;   world's and their coupling to this path's price is nil: the no-edge",
    "              ;   comparison for a rule that reads them.  The macro rows do not grade a",
    "              ;   null panel and the sidecar lists its columns as ungraded.  Needs -macro 1;",
    "              ;   one extra price loop per path.  Default 0 = the path's own panel",
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
                              // arbitrage back (unless newsRevert returns part of the step): a
                              // pure random-walk step, which is what lets this
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
    newsLev: Double = 0.0,    // NEWS THAT FOLLOWS LEVERAGE: the news intensity is newsRate x
                              // min(2, max(0, 1 + newsLev x (the credit stock's rise over its
                              // trailing-year average))) -- the signal levGain reads -- held under
                              // 0.25 a session.  The clip is symmetric about 1 and the growth is
                              // centred by construction, so the coupling moves news in time
                              // without adding any: clipped only below, the multiplier's mean ran
                              // over 1, and at 50 it lifted the S&P default's vol 16.6 -> 18.3
                              // through its rare large news alone.  The compensator follows the
                              // same intensity, so the drift a session is owed is the drift its
                              // own news risk costs and no trend is manufactured.  The record goes up the stairs and down
                              // the elevator: in its own volatility's units a session is centred
                              // at +0.10 sigma with the heavier tail on the left, which frequent
                              // moderate markdowns paid for by the drift reproduce (the up-day
                              // share 52.2 -> 55.0 on the Nasdaq recipe at 20/yr x 2%) -- but
                              // independent ones start declines no leverage preceded and cost the
                              // macro build-up (0.87 -> 0.67).  Tied to the credit stock they land
                              // late in the cycle, where the record's declines start.  0 is
                              // bit-identical: one draw a session either way.
    newsRevert: Double = 0.0, // NEWS THAT PARTLY REVERTS: the share of each news markdown that
                              // does NOT reach the fundamental.  The price takes the whole step
                              // and the fundamental 1 - newsRevert of it, so the rest opens a gap
                              // value capital buys back over the following sessions; the
                              // compensator is split the same way, so the gap has no drift of its
                              // own.  The record bounces after its down days where a permanent
                              // step keeps going, and permanent steps displacing the diffusion's
                              // transient noise lift the 60-day variance ratio past the record's
                              // (the Nasdaq recipe's 0.85 reads 1.08 with 20/yr x 2% news, 0.83
                              // at a revert share of 0.6).  A share in [0, 1]; 0 is bit-identical.
    newsScale: Double = 0.0,  // NEWS AT THE SESSION'S OWN VOLATILITY: the share of each news
                              // markdown, and of its compensator, that scales with the
                              // conditional-vol multiplier the diffusion's noise carries -- the
                              // log-vol state, the leverage kick and the vol response, as they
                              // stood before the session (`newsVolMultiplier`).  Fixed-size
                              // markdowns leave the variance after a fall to the diffusion alone,
                              // and news that has displaced most of the diffusion takes the
                              // volatility response with it: on the Nasdaq recipe at 22/yr x 2%
                              // the whole share lifts vol 22.8 -> 25.1, the upper wing 6.1 -> 7.5
                              // and the up-day share 53.7 -> 55.2, and costs the tail hedge
                              // (-0.20 -> -0.12), where the bottom decile's sessions move into
                              // turbulent stretches.  A share in [0, 1]; 0 is bit-identical.
    newsBond: Double = 0.0,   // THE BOND LEG OF NEWS: growth news moves yields, so the bond's fair
                              // value and price rise newsBond x the markdown x (duration /
                              // DurationRef) the session it lands, with the compensator on both,
                              // and the fair leg decays at `NewsBondDecay`.  In an inflation
                              // regime (`InflRegimeEdge`) the leg reverses: there bad equity news
                              // is rate news and the bond falls with the stock, as it did through
                              // the 1970s; a leg that rallied the bond there took the inflation-
                              // crash row from -25.4 to -22.9 against the record's -34.7.  The leg
                              // adds to the bond's own noise, so its variance is the bond-vol
                              // row's to police.  Without it the sessions news drives carry no
                              // bond response: at 22/yr x 2% the growth-crash rally reads 3.1
                              // against the record's 6.6.  0 is bit-identical.
    newsBondSkip: Double = 0.0, // THE BOND ANSWERS SOME NEWS: the share of news events the bond
                              // leg (`newsBond`) skips, from its own stream; the events it answers
                              // carry the leg scaled by 1 / (1 - skip), so the leg's mean, and
                              // with it the growth-crash rally, is kept.  Not every growth scare
                              // is rate news.  A leg that answers every markdown ties the bond to
                              // the stock's worst calm sessions, where the record's tie is loose
                              // (tail hedge -0.24): at a 0.42 leg on 12/yr x 2% Nasdaq news, 0.3
                              // reads tail hedge -0.263 -> -0.245 with the growth-crash rally
                              // 4.30 -> 4.33 and bond vol 14.1 -> 14.3.  In [0, 1); 0 is
                              // bit-identical and draws nothing.
    creditRegime: Double = 0.0, // THE CREDIT-TRIGGERED VOL REGIME: a turbulent spell that begins at a
                              // credit high.  Outside one (its level under `CreditRegimeRearm`),
                              // each session starts one with probability creditRegimeRate x the
                              // credit growth gap's excess over `CreditRegimeTheta` sds
                              // (`CreditGrowthSd`) / 252, from its own stream; its level is then
                              // held at 1 for `CreditRegimeHold` sessions and decays at
                              // `CreditRegimeDecay`, and the diffusive noise, the session's sd and
                              // the vol state the macro panel and the channels read all take
                              // exp(creditRegime x level).  The record's big down days sit inside
                              // such spells (2000-02 held 40%+ for two and a half years, late 2008
                              // for six months), each opening at a credit peak; the spiral's credit
                              // gain put them in calm markets as cascades, which is where the
                              // kurtosis over the long-lag clustering came from.  Carrying the onset
                              // at credit highs, it lets `levGain` fall: on the item-29 Nasdaq world
                              // at 0.8 and rate 10 with levGain 9 -> 2 and stress 4.5 -> 3.2,
                              // kurtosis 10.9 -> 9.8, lag-20 clustering 0.155 -> 0.200, lag-1 0.28
                              // -> 0.30, the build-up and hazard gates held.  Needs the credit
                              // cycle, which it switches on.  0 is bit-identical and draws nothing.
    creditRegimeRate: Double = 0.0, // the credit regime's ONSET RATE: starts per year per sd of
                              // credit growth over the threshold.
    slowBondInfl: Double = 0.0, // THE SLOW BOND LEG IN AN INFLATION REGIME: the share of the slow
                              // channel's bond leg (`slowBeta`) that reverses once inflation
                              // pressure is past `InflRegimeEdge`, the news leg's rule -- there a
                              // slow repricing is rate news and the bond falls with the stock.  At 1
                              // the leg reverses whole: with slowBeta 0.75 the inflation-crash row
                              // reads -24.9 -> -25.5 against the record's -34.7, where the
                              // unreversed leg rallied the bond through inflation crashes.  In
                              // [0, 1]; 0 is bit-identical.
    noiseSkew: Double = 0.0,  // THE SKEWED BODY: the diffusion's unit shock as a mean-zero,
                              // unit-variance skew-normal with its long tail on the LEFT,
                              // (sqrt(1 - d^2) z - d (|u| - sqrt(2/pi))) / sqrt(1 - 2 d^2 / pi), u
                              // from its own stream (`skewedShock`).  The record's session in its
                              // own vol units is centred at +0.10 sigma with 3.4% of sessions
                              // under -2 sigma against 2.5% over +2 sigma: a COUNT asymmetry,
                              // which a scale on the down moves cannot carry (`downShock` moved
                              // the squares and the variance ratio instead).  At 0.9 on
                              // 0.24.3-nasdaq the up-day share reads 51.8 -> 53.3 with every
                              // other row within its seed noise, and the S&P default's 53.8 ->
                              // 54.5, inside its band.  In [0, 1); 0 is bit-identical and draws
                              // nothing.
    newsFlip: Double = 0.0,   // NEWS PAID BY THE DAY FLIP: the share of the price's news compensator
                              // paid by reflecting a small DOWN DAY into an equal up day, not by a
                              // deterministic lift.  The day's would-be return t (the session's
                              // repricings plus `Market.predict` of the step, which is affine in
                              // its input) flips sign with probability q0 exp(-(t / sd)^2 / 2), q0
                              // set so the expected gain equals the share (`FlipGain`), from its
                              // own stream; what the flips cannot pay at q0 = 1 is a lift, so the
                              // mean holds.  The record's up days outnumber its down days in calm
                              // and middle vol states with the down/up energy near even, which no
                              // shock-level asymmetry buys: a flipped noise shock is outvoted by
                              // the day's other moves, and news or skew charge 3-4 points of
                              // downside excess for a point of up-day share where the day flip
                              // charges 1-1.5 (the lift it replaces is what it costs).  A share in
                              // [0, 1]; 0 is bit-identical and draws nothing.
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
    beliefLeak: Double = 0.0,    // THE BELIEFS' OWN FADE (item 27), per year: what the price has
                                 // taught the market is unlearned at this rate, so the belief
                                 // share is `beliefShare` at the daily scale and
                                 // beliefShare x mu / (mu + leak) in the long run (mu the
                                 // adaptation rate).  Without it the beliefs absorbed every crash
                                 // markdown for good and the gap walked under the fundamental for
                                 // a century from the fair-value start; a lower share fixes that
                                 // and breaks the variance-ratio profile, the leak fixes it and
                                 // leaves the profile alone (0.2 on the default: drift -0.52 ->
                                 // -0.06, VR250 1.08 -> 1.12).  0 is bit-identical off.
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
    cycleSd: Double = 0.0,       // THE VALUATION CYCLE AS A STATE (item 27): a slow, stationary
                                 // AR(1) in log added to perceived fair -- the discount-rate /
                                 // sentiment cycle whose stationary sd this is -- STARTED FROM ITS
                                 // OWN LAW, so a path's first session is a draw from the same
                                 // distribution as its thousandth.  Before it, the only slow
                                 // valuation variation was the beliefs' near-random walk from the
                                 // fair-value start every path was given, and the gap fell for 40
                                 // (Nasdaq) to 120 (S&P) years before settling: the wing,
                                 // dispersion and tail rows were reading that transient.  The
                                 // beliefs now track the gap NET of the cycle.  Own stream; 0 is
                                 // bit-identical off.
    cycleYears: Double = 11.5,   // the cycle's half-life in years (Shiller's CAPE reads 11.5)
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
                           // observed open (the prior close, or the sampled open when
                           // `overnight` > 0) and close, diffusion
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
    divYield: Double = 0.0, // DIVIDENDS: the world's MEAN dividend yield, %/yr.  The session
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
    overnight: Double = 0.0, // THE OPEN: the overnight share of the session's DIFFUSIVE variance
                           // (0 <= x < 1).  The open is the bridge point at that share of the
                           // session -- w x the non-jump move plus sqrt(w(1-w)) x the session
                           // sd x one normal from a dedicated stream -- with the session's news
                           // jump and jump-channel move landing overnight WHOLE, since they are
                           // the news that arrives while the market is closed; when the gap
                           // overshoots the session on its own side the whole move is the gap.
                           // The bar's bridge then runs from the open over the remaining
                           // (1-w) of the variance, and the sign coupling reads the intraday
                           // return.  0 = the open is the prior close, bit-identical off; the
                           // record's overnight share of daily variance is 0.33 (SPY) / 0.28
                           // (QQQ), `bars-2026-09-01.tsv`, and the graded share includes the
                           // jumps, so the dial sits below it.
    basket: Int = 0,       // THE BASKET: N single names as observational second-pass instances
                           // of the primary -- name_i = the SECTOR leg + its own idio + its own
                           // gaps.  The sector leg is the satellite construction (beta on the
                           // primary's observed return plus idio riding the re-levelled state
                           // factor) and is shared by every name: the model has no sector index,
                           // so the basket's equal-weight aggregate IS the sector, graded against
                           // SMH's own relation to SPY.  A name's idio rides the VOL STATE only,
                           // not the spiral's amplification, so in stress the shared variance
                           // dominates and pairwise correlation rises -- the mechanism the record
                           // shows (0.60 on SPY's worst decile vs 0.28 mid).  Own gaps: a
                           // per-name Student-t jump (JumpNu, the primary's skew) at `basketGaps`
                           // per year past ~10%.  Reaches no price; 0 = off, no columns,
                           // bit-identical.  Anchored on folio's eight semis under SMH
                           // (`basket-2026-09-02.tsv`): N = 8.
    basketBeta: Double = 0.0,   // sector leg: beta on the primary's observed return (anchored
                                // 1.56, the basket's beta on SPY)
    basketSector: Double = 0.0, // sector leg: idio sd as a FRACTION of the primary's realized
                                // vol, riding the vol state x spiral (the satellite's `satIdio`
                                // construction)
    basketIdio: Double = 0.0,   // per-name idio sd as a FRACTION of the primary's realized vol,
                                // riding the vol state WITHOUT the spiral
    basketGaps: Double = 0.0,   // per-name gap intensity, jumps per year; each a standardized
                                // t(JumpNu) x `BasketGapSize` (log), SYMMETRIC -- the index's
                                // down-skew is the index's and reaches every name through the
                                // shared leg, while the record's own name-level gaps past 10%
                                // run 41 up to 32 down with mean +0.011
                                // (`basket-drift-2026-09-03.tsv`).  A shifted own-gap channel
                                // imposes drift nothing compensates -- at these rates the
                                // primary's 0.7 skew is about -0.2/yr of log drift, more than the
                                // shared leg supplies, so every name's expected drift goes
                                // negative
    macroPanel: Int = 0,   // THE MACRO PANEL: 1 emits nine observables DERIVED from the model's
                           // own state -- macroSpread / macroSlope / macroCond / macroIvol /
                           // macroYield10 / macroCredit / macroPolicy / macroBankCredit /
                           // macroOutput, the counterparts of BAA10Y / T10Y2Y / NFCILEVERAGE /
                           // VIXCLS / DGS10 / TOTBKCR-over-GDP / DFF / TOTBKCR / GDP -- see
                           // `deriveMacro` and `MacroK`.  Observational: reaches no price, draws
                           // from a dedicated stream read only when on, so 0 is bit-identical.
    levPersist: Double = 0.0, // THE PERSISTENT KICK (item 12): the leverage kick's own memory.  At
                           // 0 the kick raises only the NEXT session's diffusive noise; at P it
                           // raises the following ones too, through an EWMA whose weights sum to 1
                           // -- the integrated response per decline is unchanged and only its
                           // SHAPE moves.  SHIPPED AT 0 after the measurement: preserving the
                           // integral divides the per-lag amplitude, so at 0.95 the clustering
                           // hump closes (lag 5 within 0.017 of lag 1, against 0.048 today) and
                           // lag-1 clustering lands on the record's 0.298, but the lag-1 leverage
                           // correlation falls -0.09 -> -0.04 against a -0.093 anchor and the
                           // downside excess with it.  The record's response is a PLATEAU, not a
                           // spread integral.  Must stay below 1; 0 = bit-identical.
    noiseAsymPhi: Double = 0.96, // the cascade's DECAY (item 14 probe): 0.96 is a 17-session
                           // half-life; higher carries the response to lag 20.  A LITERAL, not
                           // `NoiseAsymPhi`: `Defaults` is constructed earlier in this object
                           // than that val is initialized, so a default reading it would be 0.0.
    volResp: Double = 0.0, // THE VOL RESPONSE (item 14 probe): the diffusive noise multiplied by
                           // exp(volResp * S), S the session's decline in units of the
                           // conditional sd THAT GENERATED IT, accumulated at `volRespPhi`.  Two
                           // things separate it from the item-12 forms: the denominator is the
                           // session's own sd rather than a trailing scale (so the state is
                           // scale-free instantly and cannot self-excite), and the accumulation
                           // is UNNORMALIZED, so one decline's response is a PLATEAU of height
                           // `volResp` decaying at `volRespPhi` -- not an integral divided over
                           // the following sessions, which is what halved the per-lag amplitude
                           // in item 12.  Draw-free: 0 is bit-identical.
    volRespPhi: Double = 0.98,
    volRespCap: Double = 40.0, // the vol response state's ceiling (item 14 probe)
    volRespAttack: Double = 0.0, // the vol response's ATTACK (item 14 probe), 0 = none: the
                           // standardized decline through a fast EWMA before it accumulates, so
                           // the response BUILDS over two to five sessions instead of peaking at
                           // lag 1.  The record's profile humps at lag 2; with the attack off the
                           // model's peaks at lag 1 and dips, which is the whole residual gap
                           // once the accumulation has matched lags 6-40.
    jumpResp: Double = 0.0, // THE JUMP RESPONSE (item 14 probe): the jump INTENSITY multiplied by
                           // exp(jumpResp * S), the same persistent decline state `volResp` reads.
                           // The record's bad news arrives in clusters after a fall; routing the
                           // response through the jump channel puts the extra variance where the
                           // kurtosis budget already is, and jumps are SKEWED, so unlike a
                           // multiplicative noise state this should carry the downside excess
                           // rather than dilute it.  Consumes no extra draw.
    stressAdapt: Double = 0.005, // the equity spiral's SCALE speed (item 14 probe): the EWMA
                           // weight on ret^2 that standardizes the decline `stressIdx` reads.
                           // 0.005 is a ~140-session memory, so a stretch a persistent vol
                           // mechanism has genuinely made volatile reads as continuous STRESS and
                           // the spiral mints spikes out of it -- the measured blocker on every
                           // form tried.  Faster lets the spiral tell volatile from stressed.
    bustAmp: Double = 0.0,   // THE BUST SWING (item 25): a mania's unwind is two and a half years
                           // of legs and rallies at 40-60% vol (NDX 2000-02), where the model's
                           // was a crash and a calm underwater spell.  When a 0.2-log drawdown
                           // opens under a peak that stood `BustArm` or more over the gap's
                           // 20-year mean, a state s arms (full strength `BustRamp` above the
                           // arm) and, while the unwind makes new lows, a months-long
                           // unit-variance swing is repriced the same session in the price and
                           // in perceived fair at amplitude bustAmp x s -- a STATIONARY swing
                           // about the falling centre, so no gap opens for the pull to close
                           // and the price does not race to its trough; the recovery drag is
                           // relieved and the amplifier reads declines in ordinary units by
                           // (1 + 2s), so the legs keep their ordinary cascade and the rallies
                           // exist.  Sixteen forms of the bust's vol were measured before this
                           // one (PLAN item 25's ledger): every diffusive-noise form shortened
                           // and deepened the bust.  Own stream; 0 = bit-identical; the S&P
                           // default keeps 0.
    noiseAsymCap: Double = 0.0, // the cascade's CAP (item 14 probe), 0 = uncapped: the largest log
                           // multiplier the cascade may apply.  Its profile gain comes from the
                           // state's BODY and its kurtosis from the right tail, so a cap buys the
                           // one without the other -- the leverage kick's saturation, one level up.
    slowShare: Double = 0.0, // THE SLOW REPRICING CHANNEL (item 15): the share of the diffusive
                           // VARIANCE taken out of the order-flow channel, which reappears as a
                           // repricing that moves the fundamental and the price TOGETHER -- like
                           // the news jump, so the value channel has nothing to arbitrage and the
                           // move never passes through `step` and its spiral.  Its volatility has
                           // LONG memory the amplifier cannot steepen, which is what puts the |r|
                           // autocorrelation profile back on the record's shape.  Own RNG stream,
                           // and this dial is the SWITCH: 0 = bit-identical.
    slowVol: Double = 0.894, // the channel's scale, as a multiple of the session's base diffusive
                           // scale at this depth.  NOT derived from `slowShare`: the order-flow
                           // channel reaches price multiplied by the spiral's gain and this one
                           // does not, so equal variance shares are not equal price shares.
    slowLev: Double = 1.1, // the channel's OWN leverage effect, in units of its state's stationary
                           // sd -- EGARCH-style on its own draw, and the only thing driving the
                           // state.  A symmetric component was measured and is strictly worse:
                           // variance moved out of the amplifier then loses the leverage profile
                           // the amplifier was supplying.
    slowPhi: Double = 0.996, // the state's persistence.  Both inputs are scaled by sqrt(1 - phi^2)
                           // so `slowLev` stays in stationary units; unscaled its variance runs
                           // 11x nominal and volatility reaches 200%.
    slowPerm: Double = 0.30, // the share of the repricing that reaches the FUNDAMENTAL.  The rest
                           // opens a gap the value channel closes over its own horizon.  At 1.0
                           // the move is permanent and nothing arbitrages it, which the momentum
                           // crowd chases into a variance ratio: 1.0 reads 1.14 against the
                           // record's 1.00, 0.30 reads 1.09.
    slowBeta: Double = 0.55, // the BOND's loading on the same repricing, opposite sign -- a
                           // flight-to-quality factor.  Without it the channel is equity-only and
                           // the worst equity days have no bond response at all: the tail hedge
                           // correlation reads -0.239 against a record of -0.270, and the bond's
                           // growth-shock rally 5.64 against 6.60.
    noiseAsym: Double = 0.0, // THE ASYMMETRIC NOISE VOL (item 12): the diffusive noise multiplied
                           // by exp(g - Var(g)), g a CASCADE of the session's own diffusive DRAW:
                           // -z through a fast attack into a slow decay (`NoiseAsymAttack`,
                           // `NoiseAsymPhi`), so the response BUILDS over two to five sessions and
                           // persists for twenty, the shape the record's profiles show.  The draw
                           // is a unit normal by construction, so the state cannot be inflated by
                           // the price it helps set -- every price-standardized form of this
                           // self-excites, measured.  Level-preserving.  SHIPPED AT 0 after the
                           // measurement: it is the one form that moves the profile the right way
                           // (the vol response at lag 5 reaches the record's -0.05..-0.08 from
                           // -0.042 at 0.05-0.10) but the model's clustering already peaks at lag
                           // 1 from the spiral, so the hump does not close, and every setting that
                           // holds the other rows pays 5-40 points of kurtosis or the downside
                           // excess: 0.04 with the kick off reads kurtosis 27.8 and downside 1.5
                           // against 3.26, 0.10 at a lower spiral gain reads kurtosis 36.
                           // 0 = bit-identical.
    stressScale: Double = 0.0, // THE AMPLIFIER STUDY (item 11): the spiral's excess gain scaled by
                           // (depth / DepthRef)^stressScale.  0 = the spiral's size proportional
                           // to the world's everyday move (the record's crash count is
                           // volatility-FLAT across a fresh-start cross-section, the model's
                           // rises at 1.4-1.8); 1 = a liquidity event of the same absolute size
                           // in every market.  The reference world is unchanged at any value.
    levGain: Double = 0.0, // THE LEVERAGE CYCLE: declines that follow leverage.  A borrowing
                           // stock swings over years as a credit cycle of its own (a damped
                           // oscillator on a dedicated stream, `MacroK.Lev*`), is paid down under
                           // stress, and the spiral's gain is multiplied by 1 + levGain x (the
                           // stock's rise over its trailing-year average), so an ordinary shock
                           // cascades into a 20% decline where leverage has been building and
                           // not where it has not.  The ratio the panel's conditions index reads
                           // is that stock over the equity securing it, raised by the drawdown.
                           // The record's target (`macro-*.tsv`, NFCILEVERAGE): a 20% peak within
                           // a quarter 2.0-2.7x as likely with the index in its top decile, ~1x
                           // for 10% dips; the index at rank 0.92-0.94 through the quarter before
                           // the peak.  0 = the amplification is bit-identical (the stock still
                           // runs for the panel).
    macroNull: Int = 0,    // THE NULL PANEL: 1 takes the four columns from a SIBLING path (the
                           // same world at seed ^ `MacroK.NullSeed`), so their marginals and
                           // persistence are this world's and their coupling to this path's
                           // price is nil -- the no-edge comparison for a rule that reads them.
                           // The macro rows do not grade a null panel and the sidecar lists its
                           // columns as ungraded.  Needs `macroPanel`; one extra price loop per
                           // path; 0 = the path's own panel, bit-identical.
    basketDrift: Double = 0.0   // CROSS-SECTIONAL DRIFT DISPERSION: the sd of the names' own
                                // annual log-drift offsets, as a FRACTION of the primary's
                                // realized annualized vol (`ChannelLevel.kDr`), so it transports.
                                // Drawn once per name per path and centred EXACTLY, so the
                                // equal-weight sector's log drift is untouched and only the
                                // cross-section moves.  0 = off, bit-identical; needs N >= 2.
                                // ANCHORED AT 0 by `basket-drift-2026-09-03.tsv`: among folio's
                                // eight the spread of realized drift (0.068) is entirely
                                // accounted for by what a 14.6-year window generates from their
                                // own idio vol (0.070), so no true dispersion is detectable, and
                                // selecting survivors truncates the left tail -- 0 is a FLOOR
                                // from biased data, not a measurement.  The names' time below
                                // peak is NOT what this dial fixes: that gap is the eight's
                                // COMMON drift (+0.304 against the shared leg's +0.117 over the
                                // same horizon), which is the
                                // survivorship the basket fixture discloses.
                                //
                                // WHAT IT IS FOR: at 0 every name has the same expected drift BY
                                // CONSTRUCTION (shared sector leg; idio and gaps share a mean), so
                                // the basket is a NULL WORLD for cross-sectional selection -- no
                                // name is better than another and any ranking edge a rule shows on
                                // it is noise.  Set the dial and there is a real edge of known
                                // size to find.  Sweeping it gives a ranking rule's detection
                                // threshold and the history it needs there.
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
                        bustCeilDays: Int,       // BINDING diagnostic for the bust swing's ceiling:
                                                 // post-burn-in sessions on which it held the
                                                 // swing under the running peak (0 at bustAmp 0)
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
                        logOpen: Array[Double] = Array.emptyDoubleArray,  // the bar's open, log
                                                 // (empty when `overnight` is 0)
                        names: Vector[Array[Double]] = Vector.empty,      // the basket's names,
                                                 // LOG prices (empty when `basket` is 0)
                        chanK: Double = 0.0,     // the world's channel level the bars and the
                        chanKSat: Double = 0.0,  // satellite were sampled at (`worldLevel`),
                                                 // carried into the sidecar so the emitted data's
                                                 // scale is auditable; 0 / 0 when both are off
                        chanKDiv: Double = 0.0,  // the world's mean fundamental/price the
                                                 // dividend yield was normalized by; 0 when off
                        chanKVs: Double = 0.0,   // the basket idio's level (realized sd over
                                                 // the vol state's rms); 0 when no channel ran
                        chanKIv: Double = 0.0,   // the implied-vol member's level
                                                 // (`ChannelLevel.kIv`); 0 when no channel ran
                        chanKDr: Double = 0.0,   // the primary's realized annualized vol, what
                                                 // `basketDrift` is a fraction of; 0 when off
                        macroPanel: Option[MacroPanel] = None):  // the macro panel (None when
                                                 // `macroPanel` is 0), in its counterparts' units
    /** The path's first `years` of sessions, for the rows read at a record's length: every series
      * cut to them, and the channels and the macro panel -- read over the whole path, and by no
      * banded or extreme row -- dropped; the scalar summaries stay the whole path's.  A fresh
      * `years`-long path is this one's first `years` bit for bit (paths start stationary, and
      * nothing in the loop reads its own length), so `horizonReadings` reads a horizon shorter
      * than the ensemble's off it instead of simulating it again. */
    def head(years: Int): Path =
      val n = math.min(years * DaysPerYear, price.length)
      copy(price = price.take(n), rate = rate.take(n), fundamental = fundamental.take(n),
           liq = liq.take(n), bliq = bliq.take(n), bond = bond.take(n), inflPress = inflPress.take(n),
           cpi = cpi.take(n), sat = Array.emptyDoubleArray, logHi = Array.emptyDoubleArray,
           logLo = Array.emptyDoubleArray, logVolume = Array.emptyDoubleArray,
           divYield = Array.emptyDoubleArray, traded = Array.emptyDoubleArray,
           logOpen = Array.emptyDoubleArray, names = Vector.empty, macroPanel = None)

  /** THE shipped world.  `main` seeds its mutable CLI vars from this and `usage` interpolates its
    * numbers, so every default is written in exactly one place.  Help text that restates a constant
    * is a second copy of it — the failure class PARITY.md documents — and this one had already gone
    * wrong three times before it was centralised.  A mismatch between the twins is caught directly
    * by the `-emit` sidecar, which names every field: bare `-emit` writes THIS world. */
  val Defaults = World(
    trendShare = 0.055, depth = 17.4, stress = 5.0, beta = 3.0, drift = 0.122, fundVol = 0.060,
    rateMean = 0.042, volPersist = 0.982, volOfVol = 0.028,
    jumpVar = 0.16, jumpRate = 0.0035, leverage = 0.10, downShock = 0.0, jumpSkew = 0.65,
    // THE VOL RESPONSE, 0.24.1: a persistent vol state driven by the session's decline in units
    // of the conditional sd THAT GENERATED IT, and a faster spiral scale so the spiral can tell
    // a volatile stretch from a stressed one.  The record's volatility after a fall stays
    // elevated for twenty sessions; 0.24.0 read 55-62% of the record's leverage-effect profile at
    // every lag past 1, this world reads 77-135% from lag 6 out and reaches the record at lag 20.
    // `stress` 4.7 -> 5.3 and `volPersist` 0.993 -> 0.982 re-solve around the two mechanisms (the
    // faster scale costs spiral volatility, and the decline-driven state replaces exogenous vol
    // memory), `jumpVar` 0.11 -> 0.12 holds the downside excess.  Verified at 400x100 on sixteen
    // seeds: lag-5 response 0.52 -> 0.71 of the record and lag-20 0.58 -> 0.95, the crash count
    // 0.96 -> 0.98, time spent 20% underwater 3.11 -> 2.86, every gate row green.  Kurtosis does
    // NOT move on net (0.97 either side): the lower `volPersist` costs it -- 0.57 with the
    // response off -- and the response restores it.  PRICED, and disclosed, each on 16 of 16
    // seeds: lag-1 clustering 1.08 -> 1.15 of the record, the lag-1 leverage correlation
    // 0.98 -> 1.08 (the response adds to a channel already AT the record), and lag-20 clustering
    // 0.83 -> 0.78.  That last is the release's real trade, SYMMETRIC persistence for asymmetric:
    // `volPersist` 0.993 -> 0.982 takes the |r| autocorrelation at lag 20 from 0.188 to 0.113
    // against a record of 0.230, and the decline-driven state returns it only to 0.175.
    volResp = 0.021, volRespPhi = 0.992, volRespAttack = 0.5, stressAdapt = 0.036,
    // THE SLOW REPRICING CHANNEL, 0.24.1: a fifth of the diffusive variance leaves the order-flow
    // channel and reprices the fundamental and the price together, so it never passes the spiral.
    // It is what flattens the |r| autocorrelation profile toward the record's SHAPE: lag-20 over
    // lag-1 reads 0.594 against the record's 0.753, where the vol response alone read 0.527
    // (400x100, default seed).
    // `stress` 5.3 -> 5.0, `volOfVol` 0.022 -> 0.028, `jumpVar` 0.12 -> 0.16, `jumpSkew` 1.0 ->
    // 0.65 and `volResp` 0.019 -> 0.021 re-solve around it, holding kurtosis and the crash count
    // and putting the downside excess on the record.  Verified at 400x100 on sixteen seeds and
    // scored on 32: the calibration loss falls 0.102 +/- 0.040 against the channel-free world,
    // better on 29 of 32 seeds.  PRICED, and disclosed: equity volatility 5% over the record
    // against 3% before, and the return per unit of volatility that follows (0.94 against 0.96).
    // The band keeps 10.8 seed-sd of headroom at 400x100 and 3.9 at 60x80.
    slowShare = 0.20,
    // THE LEVERAGE CYCLE, 0.24.0: levGain 6, with six dials re-solved so that every row the
    // 0.23.1 world read stays where it was.  The cycle's cascades supply tail the jump channel
    // and the spiral's base gain used to (`stress` 5.15 -> 4.7, `jumpVar` 0.14 -> 0.11 with
    // `jumpSkew` 0.7 -> 1.0 holding the downside excess, `leverage` 0.12 -> 0.10 holding the
    // leverage correlation and lag-1 clustering); the cycle's fragile phases lengthen time under
    // water and its cascades shorten vol memory, so `fundVol` 0.070 -> 0.060 puts d10 back on
    // 1.38 and `volPersist` 0.992 -> 0.993 (with the stock's paydown at 0.007) puts lag-20
    // clustering back on 0.19.  Verified at 200x100 on four seeds: a 20% peak within a quarter
    // 1.61-1.69x as likely with the conditions index in its top decile (record 1.45-2.74, a
    // decoupled series 0.94), the index at rank 0.90-0.92 through the quarter before the peak
    // (record 0.92-0.94), kurtosis 25.4-26.2, crashes 19.4-20.0/century, median depth -22.2 to
    // -22.7, downside excess 2.2-3.3 (record 3.06), every gate row green, `-crossasset` PASS.
    // A level-read multiplier could not do it: see the loop.
    levGain = 6.0,
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
    // THE START (item 27, 0.24.4): the beliefs' fade and a small valuation cycle make the paths
    // stationary from the first session -- the gap used to fall for a century from the
    // fair-value start (drift -0.52 -> -0.06).  Every other dial is the 0.24.1 world's; the
    // dispersion and lower wing the walk supplied go with it (0.29 -> 0.18, 11.8 -> 3.3), and
    // no larger cycle passes the variance-ratio profile on every seed (0.12 and 0.15 each fail
    // it on one of three): the S&P's upper wing stays the disclosed miss it was.
    beliefLeak = 0.2, cycleSd = 0.1, cycleYears = 15.0,
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
  /** 0.23.0's world, which 0.23.1 shipped unchanged (its releases row points here): the
    * asymmetry adoption and the valuation cycle moved the default onto it, and 0.24.0's leverage
    * cycle moved it off (`levGain`, and the six dials re-solved around it). */
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
  /** 0.24.0's world: the leverage cycle's, before 0.24.1's vol response moved `stress`,
    * `volPersist` and `jumpVar` around the two new mechanisms.  The item-14 dials are absent, so
    * they take their off values and this row reproduces 0.24.0 bit for bit. */
  private val V0_24_0 = World(
    trendShare = 0.055, depth = 17.4, stress = 4.7, beta = 3.0, drift = 0.122, fundVol = 0.060,
    rateMean = 0.042, volPersist = 0.993, volOfVol = 0.022,
    jumpVar = 0.11, jumpRate = 0.0035, leverage = 0.10, downShock = 0.0, jumpSkew = 1.0,
    levGain = 6.0,
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
  /** 0.24.1's world, which 0.24.2 shipped unchanged and 0.24.3 ships unchanged: the vol
    * response and the slow repricing channel on the leverage cycle, every dial spelled out so the
    * row cannot follow a later `Defaults` move.  Frozen 2026-09-16 from the 0.24.3 default's
    * sidecar block; `-atrelease 0.24.1` / `0.24.2` name it. */
  private val V0_24_1 = World(
    trendShare = 0.055, depth = 17.4, stress = 5.0, beta = 3.0, drift = 0.122, fundVol = 0.06,
    rateMean = 0.042, volPersist = 0.982, volOfVol = 0.028, leverage = 0.1, downShock = 0.0,
    jumpSkew = 0.65, jumpVar = 0.16, jumpRate = 0.0035, newsRate = 1.3, newsSize = 0.033,
    valuePull = 0.056, recoveryDrag = 8.5, recoveryFloor = 0.1, haltLimit = 0.25,
    disasterRate = 0.6, disasterSize = 2.0, disasterLen = 2.5, disasterRecover = 0.5,
    disasterRecLen = 4.0, beliefShare = 0.95, beliefYears = 1.5, capYears = 1.5,
    capWindow = 6.0, crowd = Crowd.Momentum, crowdImpact = 0.03, panic = 0.0, duration = 13.5,
    easing = 0.052, unwind = 0.35, refuge = 0.115, refugeDays = 1.0, satBeta = 0.0,
    satIdio = 0.0, rangeScale = 0.0, rangeDown = 0.0, volIdio = 0.0, divYield = 0.0,
    overnight = 0.0, basket = 0, basketBeta = 0.0, basketSector = 0.0, basketIdio = 0.0,
    basketGaps = 0.0, basketDrift = 0.0, macroPanel = 0, levGain = 6.0, stressScale = 0.0,
    levPersist = 0.0, noiseAsym = 0.0, noiseAsymPhi = 0.96, noiseAsymCap = 0.0,
    volResp = 0.021, volRespPhi = 0.992, volRespCap = 40.0, volRespAttack = 0.5,
    jumpResp = 0.0, stressAdapt = 0.036, bustAmp = 0.0, slowShare = 0.2, slowVol = 0.894,
    slowLev = 1.1, slowPhi = 0.996, slowPerm = 0.3, slowBeta = 0.55, macroNull = 0,
    inflProb = 0.2, inflSize = 0.1, inflSpeed = 0.01, rateSpeed = 3.0, discount = 5.73,
    margin = 0.006)
  val Releases: Vector[(String, World)] = Vector(
    ("0.17.0", PreV1901), ("0.18.0", PreV1901), ("0.19.0", PreV1901),
    ("0.19.1", PreV1902), ("0.19.2", V0_19_2), ("0.19.3", V0_19_2), ("0.20.0", V0_20_0),
    ("0.21.0", V0_21_0), ("0.22.0", V0_22_0), ("0.22.1", V0_22_1), ("0.23.0", V0_23_0),
    ("0.23.1", V0_23_0), ("0.24.0", V0_24_0), ("0.24.1", V0_24_1), ("0.24.2", V0_24_1),
    ("0.24.3", V0_24_1))

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
  val Recipes0231: Vector[(String, World, String)] = Vector(
    // The channel-emitting Nasdaq world of MarketSimWorlds.md ("A Nasdaq world that passes the
    // gate") at the ANCHORED channel dials: realism, mechanism and fidelity PASS with all six
    // series graded (verified at 0.23.0: satellite corr 0.846, beta 1.20, vol ratio 1.42; range
    // vs cc vol 1.12, down/up 1.12).
    ("0.23.0-nasdaq",
     V0_23_0.copy(depth = 10.0, drift = 0.105, jumpVar = 0.02, fundVol = 0.06,
                  satBeta = 1.2, satIdio = 0.77, rangeScale = 0.63, rangeDown = 0.09,
                  volIdio = 0.34),
     "nasdaq"),
    // The same world with THE OPEN on, at the bar dials re-anchored for it, and the dividend
    // stream at its Nasdaq anchor -- every 0.23.1 channel a consumer of this world wants on: the intraday bridge
    // carries (1-w) of the variance, so the range dial rises from 0.63 to 0.78 (0.63/sqrt(0.67))
    // and the sign coupling, now read on the intraday return, from 0.09 to 0.13.  Verified at
    // 200x100: overnight share 0.279 (record 0.28), range vs cc vol 1.104, down/up 1.136,
    // clustering 0.704, the satellite untouched; realism, mechanism and fidelity PASS.  A new
    // name rather than a moved one: `0.23.0-nasdaq` keeps reproducing its bars byte for byte.
    ("0.23.1-nasdaq",
     V0_23_0.copy(depth = 10.0, drift = 0.105, jumpVar = 0.02, fundVol = 0.06,
                  satBeta = 1.2, satIdio = 0.77, rangeScale = 0.78, rangeDown = 0.13,
                  volIdio = 0.34, overnight = 0.22, divYield = 0.78),
     "nasdaq"),
    // The S&P default with THE BASKET on at its anchored dials and the dividend stream at its
    // S&P anchor (`basket-2026-09-02.tsv`: folio's eight semis under SMH).  Verified at 200x100:
    // names vol 2.49x, gaps 2.13/yr; aggregate corr 0.793, beta 1.562, vol 1.97x; pairwise 0.575,
    // idio share 0.374, tail coincidence 0.547, pairwise on the worst decile 0.682 vs 0.212 mid;
    // the primary untouched.  Time below peak 0.548, a disclosed reading.
    ("0.23.1-basket",
     V0_23_0.copy(basket = 8, basketBeta = 1.56, basketSector = 1.1, basketIdio = 0.9,
                  basketGaps = 6.0, divYield = 2.95),
     "sp500"),
    // The Nasdaq world with THE BASKET on, anchored on the SAME eight names read against QQQ
    // instead of SPY.  The dials are NOT the S&P set's: the eight's basket correlates 0.837 with
    // QQQ against 0.770 with SPY, so the shared leg carries more and the sector's own noise less
    // -- `basketSector` 1.2 -> 0.75, `basketBeta` the anchor's 1.365 -> 1.37.  `basketGaps` rises
    // 3.0 -> 3.5 because the level-3 rows need per-name tails the shared leg cannot supply (with
    // gaps off the level-3 rows go outside).  Verified at 200x100: names vol 2.04x, gaps 3.78/yr;
    // aggregate corr 0.853, beta 1.371, vol 1.61x; pairwise 0.567, idio share 0.380, tail
    // coincidence 0.537, pairwise on the worst decile 0.636 vs 0.140 mid.  Time below peak 0.710,
    // a disclosed reading.  The exception among the graded rows is the gap RATE -- see below.
    //
    // THE GAP RATE IS HIGH BY CONSTRUCTION, not by dial: level 1 grades the name's vol as a RATIO
    // to the primary, and the ratio's anchor is the eight against QQQ over 2012-2026 (20.6% vol)
    // while the model's primary is anchored to QQQ over 1999-2026 (24.9% model, 26.9% real -- the
    // dot-com bust is in the second window and not the first).  A name at the right RATIO is
    // therefore a fifth more volatile than the eight were, and clears 10% correspondingly more
    // often.  With own gaps off entirely the rate still reads 2.14/yr against the eight's mean of
    // 1.86, so no dial reaches it.  The row passes on the eight's own range (0.4-5.1, AMD at the
    // top); read the rate as a level, not as a match.
    ("0.23.1-nasdaq-basket",
     V0_23_0.copy(depth = 10.0, drift = 0.105, jumpVar = 0.02, fundVol = 0.06,
                  satBeta = 1.2, satIdio = 0.77, rangeScale = 0.78, rangeDown = 0.13,
                  volIdio = 0.34, overnight = 0.22, divYield = 0.78,
                  basket = 8, basketBeta = 1.37, basketSector = 0.7, basketIdio = 0.85,
                  basketGaps = 8.0),
     "nasdaq"))

  /** THE MACRO PANEL's recipes: the 0.24.0 default with `-macro 1`, and each 0.23.1 recipe with
    * the panel and the leverage cycle -- the dials 0.24.0 moved taken from `Defaults` for the S&P
    * worlds, so no dial is restated, and the two that are the Nasdaq's own re-solved (`stress`
    * 4.4, `jumpVar` 0: its jump share was 0.02, and at its deeper dial the cycle's tails need the
    * lower base gain to hold kurtosis on four seeds; its `fundVol` was 0.06 already).  Verified
    * at 200x100 on four seeds: the conditions index concentrates a 20% peak within a quarter
    * 1.61-1.69x (S&P) / 1.47-1.50x (Nasdaq, six seeds) into its top decile, builds to rank 0.90-0.92 /
    * 0.83-0.85 through the quarter before the peak, and realism, mechanism and fidelity PASS on
    * every seed. */
  val MacroRecipes: Vector[(String, World, String)] =
    def base(name: String): World =
      Recipes0231.find(_._1 == name).map(_._2).getOrElse(sys.error(s"no base recipe $name"))
    // V0_24_0, not `Defaults`: these rows carry 0.24.0's name and must keep 0.24.0's world when
    // the default moves.  0.24.1's are below.
    val d = V0_24_0
    def sp(w: World): World =
      w.copy(stress = d.stress, jumpVar = d.jumpVar, jumpSkew = d.jumpSkew, leverage = d.leverage,
             volPersist = d.volPersist, fundVol = d.fundVol, levGain = d.levGain, macroPanel = 1)
    // THE AMPLIFIER's gain scale (item 11) on the Nasdaq worlds: at `stressScale` 0.5 the
    // spiral's absolute size is 0.71 of the reference world's, which takes daily kurtosis 24 ->
    // 16 (record 9.6 at its own horizon) and lag-1 clustering 0.38 -> 0.31 (record 0.29); the
    // volatility it no longer supplies comes back through `depth` 10 -> 8.4 (24.1-24.5% on six
    // seeds against the band's floor, then 23.5%: 8.7 sat on it and failed it on a consumer's
    // seed), the bond's rally through `refuge` 0.115 -> 0.15, the hazard through `levGain` 8.
    // The crash count does not move at the band (35 -> 37/century against 25.6: diffusion alone
    // at this volatility crosses 15% thirty times a century) and lag-20 clustering gives 0.22 ->
    // 0.18 (record 0.25) -- both disclosed.
    def nq(w: World): World =
      w.copy(stress = 4.4, jumpVar = 0.0, jumpSkew = d.jumpSkew, leverage = d.leverage,
             volPersist = d.volPersist, levGain = 8.0, macroPanel = 1,
             stressScale = 0.5, depth = 8.4, refuge = 0.15)
    Vector(("0.24.0-macro", V0_24_0.copy(macroPanel = 1), "sp500"),
           ("0.24.0-nasdaq", nq(base("0.23.1-nasdaq")), "nasdaq"),
           ("0.24.0-basket", sp(base("0.23.1-basket")), "sp500"),
           ("0.24.0-nasdaq-basket", nq(base("0.23.1-nasdaq-basket")), "nasdaq"))

  /** THE VOL RESPONSE's recipes (0.24.1): each 0.24.0 recipe with the two new mechanisms.  The
    * S&P worlds take the moved dials from `Defaults`, so no dial is restated.  The Nasdaq's are
    * its own, re-solved: `stressAdapt` 0.015 rather than the default's 0.036 (its spiral is doing
    * different work at `stressScale` 0.5), `volResp` 0.008, and the three dials that pay for them
    * -- `depth` 8.4 -> 10.0 and `stress` 4.4 -> 4.2 hold the crash count while the response
    * supplies the volatility, `levGain` 8 -> 9 holds the conditions index's build-up.  Verified at
    * 200x100 on four seeds, every class PASS on both sets: the vol response at lag 5 reaches 0.77
    * of the record from 0.62 and at lag 20 0.60 from 0.45.  Priced: lag-20 clustering 0.71 -> 0.65
    * of the record and lag-1 1.08 -> 1.13.
    *
    * A jump channel here (`jumpVar` 0.06, with `levGain` 10 and `stress` 4.0 around it) would put
    * the recipe's downside excess back on the record's SIGN -- 0.24.0 turned its jumps off and
    * nothing else in it carries skew, so the row reads -1.06 of the record -- but it costs 21% of
    * daily kurtosis on a row already at 1.7x.  Measured, not taken; the sign stays disclosed. */
  val Recipes0241: Vector[(String, World, String)] =
    def base(name: String): World =
      Recipes0231.find(_._1 == name).map(_._2).getOrElse(sys.error(s"no base recipe $name"))
    // the frozen 0.24.1 row, not `Defaults`: these are released names and the default moves
    val d = V0_24_1
    def sp(w: World): World =
      w.copy(stress = d.stress, jumpVar = d.jumpVar, jumpSkew = d.jumpSkew, leverage = d.leverage,
             volPersist = d.volPersist, fundVol = d.fundVol, levGain = d.levGain, macroPanel = 1,
             volResp = d.volResp, volRespPhi = d.volRespPhi, volRespAttack = d.volRespAttack,
             stressAdapt = d.stressAdapt, volOfVol = d.volOfVol, slowShare = d.slowShare,
             slowVol = d.slowVol, slowLev = d.slowLev, slowPhi = d.slowPhi,
             slowPerm = d.slowPerm, slowBeta = d.slowBeta)
    def nq(w: World): World =
      w.copy(stress = 4.2, jumpVar = 0.0, jumpSkew = d.jumpSkew, leverage = d.leverage,
             volPersist = V0_24_0.volPersist, levGain = 9.0, macroPanel = 1,
             stressScale = 0.5, depth = 10.0, refuge = 0.15,
             volResp = 0.008, volRespPhi = d.volRespPhi, volRespAttack = d.volRespAttack,
             stressAdapt = 0.015)
    Vector(("0.24.1-macro", V0_24_1.copy(macroPanel = 1), "sp500"),
           ("0.24.1-nasdaq", nq(base("0.23.1-nasdaq")), "nasdaq"),
           ("0.24.1-basket", sp(base("0.23.1-basket")), "sp500"),
           ("0.24.1-nasdaq-basket", nq(base("0.23.1-nasdaq-basket")), "nasdaq"))

  /** THE SEARCHED NASDAQ (0.24.2): the 0.24.1 recipe re-solved by the calibration search with the
    * slow repricing channel's share and scale among the thirty searched dials, under the inflation
    * regime's ceiling, judged on both markets -- member 6 of search-v11, the member with the best
    * and steadiest four-seed loss of the fourteen that pass every class on every seed at 200 paths.
    * Every searched dial moved; the literals are the archive's, at its eight significant digits, so
    * `-atrelease 0.24.2-nasdaq` reproduces the member byte for byte. Against 0.24.1-nasdaq at 200
    * paths: crashes/century 39.7 -> 29.0 (record 25.6), lag-20 clustering 0.16 -> 0.19 (0.25), the
    * 60-day variance ratio 0.81 -> 0.96, the record's worst crash at the 14th percentile of the
    * model's 27-year worsts from the 12th, the downside excess -1.3 -> -0.3 (its sign still wrong);
    * paid in kurtosis 15.8 -> 17.3 (record 9.6), lag-1 clustering 0.32 -> 0.33 (0.29) and equity
    * vol 24.0 against 26.9. Fitness loss 1.20-1.24 on four seeds. The channel at 0.24 is what
    * carries the long-lag clustering; jumpVar is 0; the un-searched dials are the 0.24.1 recipe's. */
  val Recipes0242: Vector[(String, World, String)] =
    val b = Recipes0241.find(_._1 == "0.24.1-nasdaq").map(_._2)
      .getOrElse(sys.error("no base recipe 0.24.1-nasdaq"))
    Vector(("0.24.2-nasdaq",
            b.copy(depth = 11.7205, trendShare = 0.05, drift = 0.078012823, fundVol = 0.03, crowdImpact = 0.048391393, stress = 4.8298039, valuePull = 0.052523421, recoveryDrag = 9.0363421, recoveryFloor = 0.05, disasterRate = 0.46874687, disasterSize = 1.948638, disasterRecover = 0.56444827, beliefShare = 0.84568138, capYears = 2.9900737, volOfVol = 0.018951116, jumpVar = 0.0, jumpRate = 0.00482868, leverage = 0.069007581, downShock = 0.0073220361, jumpSkew = 0.48665602, newsRate = 0.63064016, newsSize = 0.039154222, refugeDays = 0.60052388, easing = 0.018171647, refuge = 0.11311295, inflSize = 0.11254528, discount = 7.3886613, margin = 0.008, slowShare = 0.2392292, slowVol = 0.862505),
            "nasdaq"))

  /** THE SEARCHED NASDAQ (0.24.3): the 0.24.2 recipe re-solved by the calibration search under the
    * typical-year and wing rows, with the bust swing, the belief half-life and the slow channel's
    * bond leg and permanent share among the thirty-four searched dials -- member 53 of search-v18,
    * the steadiest of the nine members that pass every class on four seeds at 200 paths. The
    * literals are the archive's, so `-atrelease 0.24.3-nasdaq` reproduces the member byte for byte.
    * Against 0.24.2-nasdaq on the same four seeds: loss 1.50-1.89 from 1.58-2.61; the upper wing
    * 2.5 -> 7.2 (record 7.6), the lower 14.0 -> 10.7 (6.7), the downside excess 0.3 -> 0.1 (1.1);
    * paid in the worst crash (-66 -> -63 against -83) and crashes/century 30.8 -> 31.4 (25.6);
    * equity vol 24.4 against 26.9 and kurtosis 16.4 (9.6) as before. The bust swing runs at the
    * archive's 0.014, which the archive left there because the bust's shape is no graded row;
    * `0.24.4-nasdaq` is this world at the measured amplitude. The un-searched dials are the
    * 0.24.2 recipe's. */
  val Recipes0243: Vector[(String, World, String)] =
    val b = Recipes0242.find(_._1 == "0.24.2-nasdaq").map(_._2)
      .getOrElse(sys.error("no base recipe 0.24.2-nasdaq"))
    Vector(("0.24.3-nasdaq",
            b.copy(depth = 11.378441, trendShare = 0.091321869, drift = 0.085311578, fundVol = 0.03, crowdImpact = 0.030261189, stress = 5.2350165, valuePull = 0.059293455, recoveryDrag = 6.7192147, recoveryFloor = 0.064560211, disasterRate = 0.42211827, disasterSize = 2.1563503, disasterRecover = 0.61330999, beliefShare = 0.7281211, capYears = 4.4211681, volOfVol = 0.019282161, jumpVar = 0.0, jumpRate = 0.005674479, leverage = 0.049527937, downShock = 0.010033667, jumpSkew = 0.46293234, newsRate = 1.1896798, newsSize = 0.042578621, refugeDays = 0.88502808, easing = 0.034326342, refuge = 0.12391532, inflSize = 0.10614338, discount = 6.4992686, margin = 0.0066204344, slowShare = 0.21615067, slowVol = 0.9349886, slowBeta = 0.57226776, slowPerm = 0.024347201, beliefYears = 0.7730648, bustAmp = 0.01448313),
            "nasdaq"))

  /** THE NASDAQ RE-SOLVED FOR THE DAILY RETURN'S SHAPE (0.24.4): the 0.24.3 recipe with its
    * kurtosis and up-day share brought inside their record bands, which 0.24.3-nasdaq misses on 29
    * and on all of 32 seeds, and its lag-1 clustering onto the record.  Four mechanisms carry it: news
    * that is frequent, small and credit-coupled, half its compensator paid by the day flip
    * (`newsRate` 16.7 x 1.5% at a fixed size, `newsLev`, `newsRevert`, `newsFlip` 0.54, with a bond
    * leg); a shock skewed long tail left (`noiseSkew`); a credit-triggered vol regime
    * (`creditRegime` 0.66 at onset rate 17) that carries the kurtosis the spiral's credit gain did;
    * and the slow bond leg's reversal in an inflation regime (`slowBondInfl`), its beta at 0.8 for
    * room under the bond's vol gate.  The un-searched dials are the 0.24.3 recipe's.  At 200 paths x
    * 100 years on 32 seeds against 0.24.3-nasdaq on the same seeds no row sits further from its
    * record past tolerance and 15 sit nearer: kurtosis 13.8 -> 9.5 (record 9.55), lag-1 0.32 ->
    * 0.29 (0.29), lag-20 0.17 -> 0.19 (0.25), the up-day share 51.9 -> 54.8 (54.8), the downside
    * excess -0.0 -> 1.0 (1.07), the wings 11.0 / 11.9 -> 6.3 / 8.2 (7.6 / 6.7), d20 1.24 -> 1.15;
    * equity vol 23.4 against 26.9 and the typical year 20.4 against 20.0, as 0.24.3.  Every class
    * on all 32 seeds, where 0.24.3-nasdaq passes on 29.  The basket world is the same world with THE BASKET on at the dials anchored on the
    * eight names under QQQ (beta 1.37, sector 0.8, idio 0.85, gaps 8.0). */
  val Recipes0244: Vector[(String, World, String)] =
    val b = Recipes0243.find(_._1 == "0.24.3-nasdaq").map(_._2)
      .getOrElse(sys.error("no base recipe 0.24.3-nasdaq"))
    val nq = b.copy(depth = 12.23494, trendShare = 0.1567742, drift = 0.094781179, fundVol = 0.039632939, crowdImpact = 0.030639325, stress = 3.308124, valuePull = 0.068439631, recoveryDrag = 8.0164577, recoveryFloor = 0.078974799, disasterRate = 0.49766283, disasterSize = 2.0541844, disasterRecover = 0.63853766, beliefShare = 0.62873317, capYears = 6.4817353, volOfVol = 0.021122274, jumpVar = 0.01356148, jumpRate = 0.0054048114, leverage = 0.1096042, downShock = 0.010266898, jumpSkew = 0.48317695, newsRate = 16.656172, newsSize = 0.014663505, refugeDays = 1.5015834, easing = 0.012, refuge = 0.15719021, inflSize = 0.095, discount = 6.6668779, margin = 0.0067990002, slowShare = 0.2395246, slowVol = 1.0210931, slowBeta = 0.8, slowPerm = 0.089307732, beliefYears = 0.95044563, bustAmp = 0.13817382, cycleSd = 0.0, cycleYears = 11.628, beliefLeak = 0.10974615, newsLev = 46.054668, newsRevert = 0.54545066, newsScale = 0.0, newsBond = 0.31140013, noiseSkew = 0.1356407, newsFlip = 0.5369486, newsBondSkip = 0.10892382, levGain = 2.5965862, creditRegime = 0.66159052, creditRegimeRate = 17.42129, slowBondInfl = 0.9384087)
    Vector(("0.24.4-nasdaq", nq, "nasdaq"),
           ("0.24.4-nasdaq-basket",
            nq.copy(basket = 8, basketBeta = 1.37, basketSector = 0.8, basketIdio = 0.85,
                    basketGaps = 8.0),
            "nasdaq"))

  val Recipes: Vector[(String, World, String)] =
    Recipes0231 ++ MacroRecipes ++ Recipes0241 ++ Recipes0242 ++ Recipes0243 ++ Recipes0244

  /** A recipe name's version, the leading digits and dots (`0.24.1-nasdaq-basket` -> `0.24.1`);
    * empty for a name that carries none.  Compares as a string, which orders these versions. */
  def recipeVersion(name: String): String = name.takeWhile(c => c.isDigit || c == '.')

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
  /** Sessions discarded so paths start from the stationary distribution.  The slowest state is the
    * valuation gap: every path starts at the fundamental, and the gap takes about 20 years to reach
    * its long-run mean and spread (the Nasdaq recipe's -0.17 and 0.45, the S&P default's -0.15 and
    * 0.24), with the bust swing's 20-year average of it behind (`gapMean`).  At 3 years the Nasdaq
    * recipe's first decade read 0.76 points of vol under decades 5-10 and the gap's first-decade
    * spread 0.27-0.37 under its later half's; at 28 years both read inside their seed noise, as they
    * do at 63. */
  val BurnIn = 756 + 252 * 25
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
  /** THE INFLATION REGIME'S CEILING, in rate units: an inflation regime's target is a half-normal
    * of `inflSize`, capped here.  Uncapped, a two-sigma regime held the policy rate near 26% for
    * the regime's one to eleven years and a century put 1.7% of sessions above 20% where the
    * record's 1954-2026 put 0.11%, its maximum 22.4% (1981) and its longest run above 20% four
    * sessions.  Capped at 0.12 the target's ceiling is `rateMean` + 12 = 16.2%, the record's
    * 1980-81 plateau, and the rate's own noise carries the spike above it.  A cap consumes no
    * draw, so a path whose regimes never reach it is bit-identical to the uncapped model. */
  val InflCap     = 0.12
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
  /** The most a session's news probability may reach once `newsLev` scales it -- the jump
    * channel's own intensity cap.  Binding only in the steepest credit booms, where it keeps
    * the probability a probability. */
  val NewsPCap = 0.25
  /** The grid the credit stock's growth is read on before it scales the news: 2^-20.  The stock
    * carries ulp-level noise between the twins -- the leverage stream's normal draws are pinned,
    * not exact, in their tail -- which nothing read closely enough to matter until the news did:
    * read raw, it reached the price through the draw and the compensator, and the feedback carried
    * it to the sixth decimal within 10,000 sessions.  On the grid the twins agree unless the growth
    * sits within an ulp of a grid midpoint, about 1e-10 a session. */
  val NewsLevGrid = 1048576.0
  /** A basket name's gap size, log, per standardized t draw: 0.09 puts a 1-sd gap at ~9% and the
    * per-year count past 10% at roughly half the intensity dial.  Frozen, like the jump family's
    * shape constants; `basketGaps` is the one anchored parameter. */
  val BasketGapSize = 0.09

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

  /** The share of the diffusion budget a searched news channel may take.  The ranges' corner
    * (`newsRate` 30, `newsSize` 0.05) is six times past the budget, so the size is pulled back
    * rather than the range narrowed: frequent news needs small jumps and rare news allows big ones,
    * and no box holds both.  0.9 leaves a third of the diffusion sd for the bar channels to level
    * on. */
  val NewsBudgetSearch = 0.9

  /** A proposed news size, pulled back to `NewsBudgetSearch` of the diffusion budget at the
    * proposed rate; unchanged inside it or with the channel off.  Every search path applies it to
    * its proposal, so no search scores a world the CLI would refuse. */
  def newsSizeWithinBudget(newsRate: Double, newsSize: Double): Double =
    val cap = NewsBudgetSearch * DaysPerYear * SigmaN * SigmaN
    if newsRate > 0.0 && newsRate * newsSize * newsSize > cap then math.sqrt(cap / newsRate)
    else newsSize

  /** The per-session decay of the bond's fair-value leg of news (`newsBond`): 0.5^(1/126), half a
    * year's half-life, the horizon over which growth news keeps rate expectations down.  Frozen:
    * at a quarter's the rally a long crash earns early has decayed before its trough (growth-crash
    * 4.1 -> 4.3 on the Nasdaq world at a 0.32 leg).  A literal rather than a `math.pow`, so the
    * twins cannot differ in its last bit. */
  val NewsBondDecay = 0.9945139356168285

  /** THE INFLATION REGIME's edge: inflation pressure above it is an inflation regime.  The bond
    * rows split the crashes on it (`bondGrowth` / `bondInfl`), the calm mask is its complement, and
    * the news bond leg reverses across it (`newsBond`): in an inflation regime bad equity news is
    * rate news, and the bond falls with the stock. */
  val InflRegimeEdge = 0.005

  /** THE CREDIT-TRIGGERED VOL REGIME's constants (see `creditRegime`).  The credit growth gap
    * (`borrow - levSlow`) has a stationary sd of 0.104 on the Nasdaq recipes (measured over 45
    * 300-year paths); an onset needs the gap half an sd over zero; the plateau holds half a year and
    * then decays at a quarter's half-life, 0.5^(1/63) as a literal so the twins cannot differ in its
    * last bit; a new spell can start once the level is back under 0.2. */
  val CreditGrowthSd     = 0.104
  val CreditRegimeTheta  = 0.5
  val CreditRegimeHold   = 126
  val CreditRegimeDecay  = 0.9890579681360733
  val CreditRegimeRearm  = 0.2

  /** A day flip's expected gain per unit of the day's sd at `q0` = 1:
    * 2 E[|z| exp(-z^2 / 2); z < 0] = 1 / sqrt(2 pi), for a unit normal `z`.  See `newsFlip`. */
  val FlipGain = 0.3989422804014327

  /** sqrt(2/pi), a unit normal's mean absolute value: what centres `skewedShock`'s half-normal
    * part.  A literal, so the twins cannot differ in its last bit. */
  val HalfNormalMean = 0.7978845608028654

  /** The skew-normal unit shock `noiseSkew` gives the diffusion: `z` the diffusion's own normal,
    * `u` its stream's, `d` the skew, `a` = sqrt(1 - d^2) and `b` = sqrt(1 - 2 d^2 / pi) the per-world
    * constants that make it mean zero and unit variance with the long tail on the left. */
  def skewedShock(z: Double, u: Double, d: Double, a: Double, b: Double): Double =
    (a * z - d * (math.abs(u) - HalfNormalMean)) / b

  /** The conditional-vol multiplier the diffusion's noise carries, read from the states as they
    * stood before the session: the log-vol state's level-preserving factor, the leverage kick and
    * the vol response, each only when its dial is on.  `newsScale` sizes news by it. */
  def newsVolMultiplier(w: World, logVol: Double, volNorm: Double, kickS: Double,
                        volRespS: Double): Double =
    val m0 = math.exp(logVol - volNorm)
    val m1 = if w.leverage > 0.0 then m0 * math.exp(w.leverage * kickS) else m0
    if w.volResp > 0.0 then m1 * math.exp(w.volResp * volRespS) else m1

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

  /** THE BUST SWING's constants (see `bustAmp`), frozen like `CapSpan`: the gap's slow mean, the
    * peak level over it that arms the state, the level range over which the state ramps to full
    * strength, the swing's per-session persistence (a 50-session memory), the state's decay while
    * the unwind makes new lows and after a year without one (as per-session factors, written as
    * literals so both twins carry the same bits), and the drag relief / amplifier blindness at
    * full strength (1 + BustRelief x s). */
  val BustMeanYears = 20.0
  val BustArm = 0.5
  val BustRamp = 0.15
  val BustPhi = 0.98
  val BustDecayNear = 0.9990835588389924
  val BustDecayAfter = 0.9945139356168285
  val BustRelief = 2.0
  /** THE UNWIND'S CEILING (see `bustAmp`): the swing may not carry the price nearer than this to
    * the running peak, in log.  A mania's unwind never re-attains its high (the NDX's 2000 high
    * stood until 2015, the Dow's 1929 high until 1954): the nearest its rallies came was 0.14
    * under it (September 2000) and 0.20 (October 1929), and no deep episode on the record came
    * nearer than 0.08.  Without it the swing's rallies re-attained the high a month after the
    * bust opened and minted 20% peaks whose quarter sat inside the bust, which failed the
    * macro build-up band from amplitude 0.10.  Binds only while armed and within reach of the
    * peak, so every path where it never binds is bit-identical. */
  val BustCeil = 0.10
  /** The state below which the unwind is over and the state is cut to exactly 0 (a geometric
    * decay never reaches it): the swing, the drag relief and the ceiling are then inert until
    * the next mania arms, so a stretch with no mania is bit-identical to the dial off and
    * `bustCeilDays` counts a live unwind only.  At full amplitude 0.3 the cut swing is under
    * 1e-4 log. */
  val BustOff = 1e-4
  /** Once the price regains the running peak while the state is armed the unwind is over (no
    * mania's unwind on the record re-attained its high before it was over), and the state decays
    * by this factor a session from then on -- a 23-session half-life -- on top of the ordinary
    * decay.  Without it the swing ran on for up to a year after a full recovery, and its downward
    * moves minted 20% peaks under a still-depressed conditions index: the residual that failed the
    * build-up band from amplitude 0.20 under the ceiling.  A fresh arming clears it. */
  val BustDecayOver = 0.97
  /** THE STATIONARITY ROW's band (see `cycleSd` and `gapDriftOf`): the pooled valuation gap may
    * not drift more than this, in log, between a path's first decade and its later half.  A
    * stationary world reads within +-0.05 at 60 paths; the transient worlds read -0.15 (Nasdaq)
    * and -0.6 (S&P). */
  val GapDriftBand = 0.15
  /** THE SPREAD STATIONARITY ROW's band (see `gapSpreadOf`): the pooled valuation gap's sd over a
    * path's first decade may differ from its sd over the later half by at most this share.  With
    * the 3-year warm-up both recipes passed the mean row and read -0.16 to -0.42 here (the gap
    * starts at the fundamental, with no spread); with `BurnIn`'s 28 years they read -0.07 to
    * +0.15 at 60 paths and -0.03 to +0.10 at 200, on four seeds each. */
  val GapSpreadBand = 0.20

  /** DETERMINISTIC exp: Cody-Waite range reduction with fdlibm's split ln2, a fixed Horner
    * Taylor to r^12 on the reduced argument, and 2^k built from raw exponent bits.  Every
    * operation is IEEE-exact-or-fixed, so the twins agree TO THE BIT by construction -- which no
    * native libm call guarantees: the momentum crowd's tanh diverged from Rust's by one ulp on a
    * cycle-world input after four releases of input luck, and rebuilding tanh from the NATIVE exp
    * only moved the divergence into exp's own wide-argument ulps (both measured 2026-08-30, the
    * PARITY.md `log` class).  Accuracy ~2 ulp, which a behavioural squash cannot see; |y| is
    * bounded by `tanhP`'s cutoff so the 2^k construction stays in range.  Use it for any future
    * transcendental that must match across the twins.
    *
    * THE RANGE IS THE CALLER'S: `2^k` is built from raw exponent bits with no guard, so past about
    * +-709 the shift wraps and the result is nonsense rather than 0 or infinity.  A caller whose
    * argument can leave that range tests for it (a Gaussian kernel's far term, say, is 0 there). */
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

  /** DETERMINISTIC natural log, `expDet`'s partner: `x = m 2^e` split from the bits (exact), `m`
    * folded into [sqrt(1/2), sqrt(2)), ln m = 2 (s + s^3/3 + ... + s^21/21) with
    * s = (m - 1) / (m + 1), then (e ln2hi + ln m) + e ln2lo with fdlibm's split ln2 -- `Svg.log10`'s
    * series without the change of base, operation for operation in both twins, so they agree TO
    * THE BIT.  NaN for x <= 0 or NaN, +inf for +inf. */
  def lnDet(x: Double): Double =
    if x.isNaN || x <= 0.0 then Double.NaN
    else if x == Double.PositiveInfinity then Double.PositiveInfinity
    else
      val Ln2Hi = java.lang.Double.longBitsToDouble(0x3FE62E42FEE00000L)
      val Ln2Lo = java.lang.Double.longBitsToDouble(0x3DEA39EF35793C76L)
      // a subnormal is scaled by 2^54 first, so the exponent field below is a normal's
      val sub = x < java.lang.Double.MIN_NORMAL
      val v = if sub then x * 18014398509481984.0 else x
      val bits = java.lang.Double.doubleToRawLongBits(v)
      val e0 = ((bits >>> 52) & 0x7ffL).toInt - 1023 - (if sub then 54 else 0)
      val m0 = java.lang.Double.longBitsToDouble((bits & 0x000fffffffffffffL) | 0x3ff0000000000000L)
      val (m, e) = if m0 > 1.4142135623730951 then (m0 * 0.5, e0 + 1) else (m0, e0)
      val s  = (m - 1.0) / (m + 1.0)
      val s2 = s * s
      var term = s
      var sum = s
      var k = 3
      while k <= 21 do
        term *= s2
        sum += term / k
        k += 2
      (e * Ln2Hi + 2.0 * sum) + e * Ln2Lo

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
  /** The depth the spiral's gain was calibrated at, the reference `stressScale` scales from -- a
    * literal, not `Defaults.depth`: a later depth move must not silently rescale the law -- and
    * the asymmetric noise vol's persistence (half-life 17 sessions, the ~20 the record's vol
    * response runs for; `noiseAsym` sizes it). */
  val DepthRef      = 17.4
  /** THE ASYMMETRIC NOISE VOL's two timescales (item 12; `noiseAsym` sizes the response, these
    * shape it).  The record's vol after a decline BUILDS over two to five sessions and stays
    * elevated for about twenty -- its |r| autocorrelation humps at lags 2-5 and its
    * leverage-effect profile still reads -0.08 at lag 5 -- so the driver is a cascade: the
    * session's draw through a fast attack (`Attack`, ~3 sessions) into a slow decay (`Phi`, a
    * 17-session half-life).  One exponential alone peaks at lag 1 and makes no hump, measured. */
  val NoiseAsymPhi    = 0.96
  val NoiseAsymAttack = 0.50
  /** Var(g) in closed form, for the level-preserving centring: the cascade's impulse response is
    * T(1-A)(A^{k+1} - phi^{k+1})/(A - phi), and this is the sum of its squares. */
  def noiseAsymVar(t: Double, ph: Double = NoiseAsymPhi): Double =
    val a = NoiseAsymAttack
    if t <= 0.0 then 0.0
    else
      val k = t * (1.0 - a) / (a - ph)
      k * k * (a * a / (1.0 - a * a) - 2.0 * a * ph / (1.0 - a * ph) + ph * ph / (1.0 - ph * ph))
  final class Market(kValue: Double, stressK: Double, impact: Double,
                     recoveryDrag: Double = 0.0, recoveryFloor: Double = 1.0,
                     haltLimit: Double = 0.0, scaleMu: Double = 0.005):
    private val floorLog = if haltLimit <= 0.0 then Double.NegativeInfinity
                           else math.log(1.0 - haltLimit)
    private var carry = 0.0
    var haltDays = 0
    var logP = 0.0
    var peak = 0.0
    var stressIdx = 0.0
    /** THE LEVERAGE CYCLE's multiplier on the spiral's gain, set by the loop each session from
      * the leverage stock (`levGain`); exactly 1.0 with the dial off, so the amplification is
      * bit-identical there. */
    var levMult = 1.0
    /** THE AMPLIFIER STUDY's gain scale (item 11), exactly inert at 1: the spiral's excess gain
      * scaled by (depth / DepthRef)^stressScale, set by the loop, so a thinner market's liquidity
      * event is not proportionally larger than the reference world's. */
    var gainMult = 1.0
    var lastLiq = impact
    /** THE BUST SWING's two per-session hooks (see `bustAmp`), set by the loop and exactly 1.0
      * otherwise: the recovery drag times `dragMult`, and the amplifier's stress reading declines
      * divided by `stressDiv`. */
    var dragMult = 1.0
    var stressDiv = 1.0
    /** THE VALUATION CYCLE's hook (see `cycleSd`): the drawdown the recovery drag reads, set by
      * the loop to the drawdown of the price WITHOUT the cycle while the cycle is on; NaN (the
      * price's own drawdown) otherwise, so the dial off is bit-identical. */
    var dropOverride = Double.NaN
    var clamps = 0
    /** Sessions on the DOWNWARD guard, and sessions in the tail at all.  Counted separately from
      * `clamps` because the question the gate has to answer is not how often the guard binds --
      * it binds on almost nothing -- but what share of the extreme tail it SHAPES. */
    var floorDays = 0
    var tailDays = 0
    private[apps] var scaleVar = 0.01 * 0.01
    /** The SPIRAL's own scale, at `scaleMu`, and the stress index built from it.  Separate from
      * `scaleVar`/`stressIdx` on purpose: `stressAdapt` is the amplifier's dial, and the index
      * the rest of the world reads -- policy easing, the refuge bid, joint-stress margin selling,
      * the leverage stock's paydown, the macro panel's spread and conditions members -- was
      * calibrated against the SLOW one.  Letting the dial move both shrank the equity stress the
      * bond's crisis behaviour reads: measured, the growth-shock rally fell 6.7 -> 4.2 against a
      * record of 6.6 and `-crossasset` failed its short-duration rung.  At `scaleMu` 0.005 the
      * two are identical, so the dial is bit-identical off. */
    private var scaleVarAmp = 0.01 * 0.01
    private var stressAmp = 0.0
    /** The liquidity multiplier this session's step will apply to flow and noise, read before
      * the step: `lastLiq`'s expression. */
    def liquidity: Double = (1.0 + stressK * stressAmp * levMult * gainMult) * impact

    /** The return `step` would make of `flowPlusNoise`, halts and clamps aside: affine in the
      * input, with slope `liquidity`.  What the day flip reads before the step. */
    def predict(fair: Double, flowPlusNoise: Double): Double =
      val amp  = 1.0 + stressK * stressAmp * levMult * gainMult
      val gap  = fair - logP
      val drop = if dropOverride.isNaN then peak - logP else dropOverride
      val damp = if recoveryDrag <= 0.0 || gap <= 0.0 || drop <= DrawdownRef then 1.0
                 else math.max(recoveryFloor,
                               1.0 / (1.0 + recoveryDrag * dragMult * (drop - DrawdownRef) / DrawdownRef))
      (kValue * gap * damp + flowPlusNoise * amp) * impact + carry

    def step(fair: Double, flowPlusNoise: Double): Double =
      val scale = math.sqrt(scaleVar)
      val scaleA = math.sqrt(scaleVarAmp)
      val amp   = 1.0 + stressK * stressAmp * levMult * gainMult
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
      // the drawdown the drag reads: the price's own, or the price's without the valuation
      // cycle when the loop supplies it (value capital is depleted by a crash, not by a slow
      // re-rating; a drag that read the cycle's down-swings as drawdowns manufactured a lagged
      // recovery and lifted the 250-day variance ratio past its profile)
      val drop  = if dropOverride.isNaN then peak - logP else dropOverride
      val damp  = if recoveryDrag <= 0.0 || gap <= 0.0 || drop <= DrawdownRef then 1.0
                  else math.max(recoveryFloor,
                                1.0 / (1.0 + recoveryDrag * dragMult * (drop - DrawdownRef) / DrawdownRef))
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
      scaleVarAmp = (1.0 - scaleMu) * scaleVarAmp + scaleMu * (ret / stressDiv) * (ret / stressDiv)
      stressAmp = math.max(0.0, 0.96 * stressAmp + 0.04 * (math.max(0.0, -ret / stressDiv) / scaleA - 0.399))
      ret

  /** Per-session inputs the derived channels read, recorded by `simulate`'s price loop: the
    * observed log price (markdown and news included -- the return a consumer measures), the
    * session diffusion sd as the price received it (`sessSigma` x spiral amplification), the
    * satellite's state factor (vol state x spiral amplification over the base impact), and
    * `scaleVar` after the step -- the volume down-term's realized scale, one session fresher than
    * the leverage signal's (stated, and mirrored). */
  final case class ChannelInputs(px: Array[Double], d: Array[Double], state: Array[Double],
                                 scaleVar: Array[Double],
                                 jump: Array[Double],    // the session's jump-channel move as the
                                                        // market delivered it (x its liquidity)
                                                        // plus the news repricing; 0 on a
                                                        // jump-free session
                                 volState: Array[Double], // exp(logVol - volNorm): the vol state
                                                        // WITHOUT the spiral, the basket idio's
                                                        // driver
                                 amp: Array[Double]):    // the spiral's amplification this
                                                        // session, `lastLiq` over the base impact
                                                        // -- what the implied-vol member prices a
                                                        // share of
    /** Session `i`'s record, written by the price loop after both markets have stepped: `logPx`
      * the observed log price, `vs` exp(logVol - volNorm), `bustMove` / `cycMove` the bust swing's
      * and the valuation cycle's same-session repricings (0 when off).  The satellite's and the
      * sector's state is the primary's conditional vol -- the diffusive state at its share beside
      * the slow channel's variance, as the implied-vol member reads it -- times the spiral's
      * amplification: without the channel's term a world carrying its long-lag clustering in the
      * channel gave the satellite none of it, and the satellite's clustering-20 ratio left its
      * band on every seed.  The branches keep a channel-off world bit-identical: sqrt(x * x) is
      * not always x.  Here, not inline, to keep `priceLoop` under the JIT's method limit. */
    def record(i: Int, w: World, eqM: Market, logPx: Double, sessSigma: Double, vs: Double,
               volRespM: Double, regimeM: Double, mix: Double, slowVar: Double, jumpNow: Double,
               newsJ: Double, bustMove: Double, cycMove: Double): Unit =
      px(i) = logPx
      d(i) = sessSigma * eqM.lastLiq
      val st = if w.slowShare > 0.0 then math.sqrt(vs * mix * vs * mix + slowVar) else vs
      state(i) = st * eqM.lastLiq * w.depth / 12.0
      scaleVar(i) = eqM.scaleVar
      jump(i) = if w.bustAmp > 0.0 || w.cycleSd > 0.0 then jumpNow * eqM.lastLiq - newsJ + bustMove + cycMove
                else jumpNow * eqM.lastLiq - newsJ
      val vb = vs * volRespM * regimeM * mix
      volState(i) = math.sqrt(vb * vb + slowVar)
      amp(i) = eqM.lastLiq * w.depth / 12.0

    /** This path's contribution to the world's level: sums of the observed squared return, the
      * session diffusion sd and the squared satellite state factor from the second session (the
      * first has no return), plus the count -- in session order, which is part of the
      * cross-language contract. */
    def levelSums: (Double, Double, Double, Double, Double) =
      val tot = px.length
      var sR2 = 0.0; var sD = 0.0; var sSt = 0.0; var sVs = 0.0
      var i = 1
      while i < tot do
        val r = px(i) - px(i - 1)
        sR2 += r * r
        sD += d(i)
        sSt += state(i) * state(i)
        sVs += volState(i) * volState(i)
        i += 1
      (sR2, sD, sSt, sVs, (tot - 1).toDouble)

    /** This path's contribution to the implied-vol level: the sums of log(vol state x the
      * forward-looking amplification) and of log forward-21-session realized vol, over the
      * post-burn-in sessions that have one, and their count -- in session order.  The same window
      * and the same factor the premium reads, so the level is taken in the premium's own
      * statistic: a root-mean-square level put the premium 0.2 high on a thin market, whose
      * forward realized vol is dominated by rare stretches. */
    def ivolLevelSums: (Double, Double, Double) =
      val n  = px.length
      val r2 = new Array[Double](n)
      var i = 1
      while i < n do
        val d = px(i) - px(i - 1)
        r2(i) = r2(i - 1) + d * d
        i += 1
      val h = 21
      var sLiv = 0.0; var sLrv = 0.0; var c = 0.0
      i = BurnIn
      while i < n do
        if i + h < n then
          val rv = 100.0 * math.sqrt(DaysPerYear.toDouble * (r2(i + h) - r2(i)) / h)
          val f  = volState(i) * (1.0 + MacroK.IvolAmpShare * (amp(i) - 1.0))
          if rv > 0.0 && f > 0.0 then
            sLiv += math.log(f)
            sLrv += math.log(rv)
            c += 1.0
        i += 1
      (sLiv, sLrv, c)

  object ChannelInputs:
    /** `tot` sessions per column when a derived channel reads them, empty columns otherwise. */
    def sized(on: Boolean, tot: Int): ChannelInputs =
      def col = if on then new Array[Double](tot) else Array.emptyDoubleArray
      ChannelInputs(col, col, col, col, col, col, col)

  /** The world's channel level: `k` re-levels the session diffusion sd onto the world's realized
    * close-to-close sd, `kSat` the satellite's state factor onto it in root-mean-square. */
  final case class ChannelLevel(k: Double, kSat: Double, kDiv: Double = 0.0, kVs: Double = 0.0,
                                // the primary's realized ANNUALIZED volatility -- what
                                // `basketDrift` is a fraction of, so the dial transports
                                kDr: Double = 0.0,
                                // the implied-vol member's level: exp of the mean over sessions of
                                // log forward-21-session realized vol minus log(vol state x
                                // forward-looking amplification), in annualized %, so the member's
                                // log premium over realized is the record's by construction in
                                // every world; 0 when no channel ran
                                kIv: Double = 0.0)

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
    val chOn  = w.rangeScale > 0.0 || w.satBeta > 0.0 || w.overnight > 0.0 || w.basket > 0 ||
                w.macroPanel > 0   // the implied-vol member reads `kVs`
    val divOn = w.divYield > 0.0
    if !(chOn || divOn) then ChannelLevel(0.0, 0.0, 0.0, 0.0)
    else
      val sums = java.util.stream.IntStream.range(0, LevelPaths).parallel()
        .mapToObj { k =>
          val pr = priceLoop(w, LevelYears, LevelSeed + k.toLong * 7919L)
          // the channel inputs are recorded only when a channel ran; the dividend level needs
          // none of them
          (if chOn then pr.inputs.levelSums else (0.0, 0.0, 0.0, 0.0, 0.0),
           if chOn then pr.inputs.ivolLevelSums else (0.0, 0.0, 0.0),
           fairOverPriceSum(pr.path))
        }
        .toArray()
      var sR2 = 0.0; var sD = 0.0; var sSt = 0.0; var sVs = 0.0; var m = 0.0; var sFp = 0.0; var nFp = 0.0
      var sLiv = 0.0; var sLrv = 0.0; var nIv = 0.0
      var i = 0
      while i < sums.length do
        val ((a, b, c, vs, n), (liv, lrv, niv), (fp, nf)) =
          sums(i).asInstanceOf[((Double, Double, Double, Double, Double), (Double, Double, Double),
                                (Double, Double))]
        sR2 += a; sD += b; sSt += c; sVs += vs; m += n; sLiv += liv; sLrv += lrv; nIv += niv
        sFp += fp; nFp += nf
        i += 1
      ChannelLevel(if chOn then math.sqrt(sR2 / m) / (sD / m) else 0.0,
                   if chOn then math.sqrt(sR2 / sSt) else 0.0,
                   if divOn then sFp / nFp else 0.0,
                   // the basket idio's level: realized sd over the vol state's rms, so a fraction
                   // dial is a fraction of the primary's realized vol whatever shape the state takes
                   if chOn then math.sqrt(sR2 / sVs) else 0.0,
                   // the drift dispersion's level: the primary's realized ANNUALIZED vol
                   if chOn then math.sqrt(sR2 / m) * math.sqrt(DaysPerYear.toDouble) else 0.0,
                   // `expDet`, as the OU factors use: the twins' libm exps differ in the last bit
                   if chOn && nIv > 0.0 then expDet((sLrv - sLiv) / nIv) else 0.0)

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
                            logVolume: Array[Double], logOpen: Array[Double],
                            names: Vector[Array[Double]])   // the basket's log prices, N of them

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
    val orng    = new NumPyRNG(seed ^ 0x09e7a11eL)
    val brng    = new NumPyRNG(seed ^ 0xba5ce700L)
    val mrng    = new NumPyRNG(seed ^ 0xd1f75eadL)
    val tot     = x.px.length
    val satOn   = w.satBeta > 0.0
    val rangeOn = w.rangeScale > 0.0
    val volOn   = rangeOn && w.volIdio > 0.0
    val openOn  = w.overnight > 0.0
    val bskOn   = w.basket > 0
    val sat = if satOn then new Array[Double](tot) else Array.emptyDoubleArray
    val hi  = if rangeOn then new Array[Double](tot) else Array.emptyDoubleArray
    val lo  = if rangeOn then new Array[Double](tot) else Array.emptyDoubleArray
    val vv  = if volOn then new Array[Double](tot) else Array.emptyDoubleArray
    val op  = if openOn then new Array[Double](tot) else Array.emptyDoubleArray
    val nm  = if bskOn then Vector.fill(w.basket)(new Array[Double](tot)) else Vector.empty
    if !(satOn || rangeOn || openOn || bskOn) then Channels(sat, hi, lo, vv, op, nm)
    else
      val k  = level.k
      val kS = level.kSat
      val kV = level.kVs
      // BASKET state: the shared sector leg's log price and the primary's observed log price
      // last session (its own tracker, like the satellite's), and each name's log price.
      var secPrevPx = 0.0
      val nameLogP = new Array[Double](w.basket)
      val gapProb  = w.basketGaps / DaysPerYear
      // DRIFT DISPERSION -- see the `basketDrift` field.  One draw per name from `mrng`, taken
      // BEFORE the session loop, then centred exactly so the sector's log drift is untouched:
      // only the cross-section moves.  Rescaled by sqrt(N/(N-1)) because centring N draws costs
      // exactly that much sample sd, so the dial delivers the sd it names.  A single name has no
      // cross-section to disperse, so N < 2 is a no-op.
      val nameMu =
        if bskOn && w.basketDrift > 0.0 && w.basket >= 2 then
          val z = Array.fill(w.basket)(mrng.randn())
          val zb = z.sum / z.length
          val sc = w.basketDrift * level.kDr * math.sqrt(z.length / (z.length - 1.0)) / DaysPerYear
          z.map(v => (v - zb) * sc)
        else new Array[Double](w.basket)
      // SATELLITE LEG state: its log price and the primary's observed log price last session.
      var satLogP = 0.0; var satPrevPx = 0.0
      // RANGE state: the bar's open (the prior close; the sampled open when `overnight` > 0).
      // Independent of the
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
        // THE BASKET -- see the `basket` field.  The shared sector leg first (its idio like the
        // satellite's, riding state x spiral), then per name: the sector's move + own idio on the
        // vol state alone + own gap.  Reads `brng` only: one normal for the sector, then per
        // name one normal, one uniform, and on a gap session one normal and JumpNu normals for
        // the t -- that draw ORDER is part of the cross-language contract.
        if bskOn then
          val secIdio = w.basketSector * (x.state(i) * kS) * brng.randn()
          val secRet  = w.basketBeta * (logPx - secPrevPx) + secIdio
          secPrevPx = logPx
          var q = 0
          while q < w.basket do
            val idio = w.basketIdio * (x.volState(i) * kV) * brng.randn()
            val gap  =
              if brng.nextDouble() < gapProb then
                val z = brng.randn()
                var chi = 0.0
                var kk = 0
                while kk < JumpNu do
                  val g = brng.randn()
                  chi += g * g
                  kk += 1
                val t = z / math.sqrt(chi / JumpNu) / math.sqrt(JumpNu / (JumpNu - 2.0))
                // SYMMETRIC, unlike the primary's jumps -- see `basketGaps`.  The index's
                // down-skew is the INDEX's and already reaches every name through the shared
                // leg; a name's own large moves are earnings and idiosyncratic news, and the
                // record shows those are not skewed down.
                t * BasketGapSize
              else 0.0
            nameLogP(q) += secRet + idio + gap + nameMu(q)
            nm(q)(i) = nameLogP(q)
            q += 1
        // THE OPEN -- see the `overnight` field.  The bridge point at share w of the session's
        // diffusive variance, jumps landing overnight whole, one normal from `orng` per session
        // read only when the dial is on; then the bar runs from it.
        val rC = logPx - barPrevPx
        val openPx =
          if openOn then
            val j  = x.jump(i)
            val n  = rC - j
            val s0 = x.d(i) * k
            val b  = math.sqrt(w.overnight * (1.0 - w.overnight)) * s0
            val o0 = j + w.overnight * n + b * orng.randn()
            val o  = if (rC < 0.0 && o0 < rC) || (rC > 0.0 && o0 > rC) then rC else o0
            // Clamped, the open IS the close: assign it exactly.  `barPrevPx + rC` leaves a
            // rounding residual of a few ulps whose sign would pick the range coupling's branch,
            // and the twins' log prices differ at that level.
            op(i) = if o == rC then logPx else barPrevPx + o
            op(i)
          else barPrevPx
        if rangeOn then
          val rS   = logPx - openPx
          // the intraday bridge carries the remaining (1-w) of the variance; x 1.0 when the open
          // is off, which is exact
          val intraday = if openOn then math.sqrt(1.0 - w.overnight) else 1.0
          val sig0 = (x.d(i) * k) * intraday * w.rangeScale
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
          hi(i) = openPx + (rS + math.sqrt(rS * rS - 2.0 * sig2 * math.log(u1))) / 2.0
          lo(i) = openPx + (rS - math.sqrt(rS * rS - 2.0 * sig2 * math.log(u2))) / 2.0
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
        if rangeOn || openOn then barPrevPx = logPx
        i += 1
      Channels(sat, hi, lo, vv, op, nm)

  /** The price loop and the derived channels of one path; the channel arrays of `path` are
    * empty until `simulateAt` fills them. */
  final case class Priced(path: Path, inputs: ChannelInputs, macroIn: MacroInputs)

  /** THE MACRO PANEL's inputs: per-session states the price loop already carries, recorded when
    * `macro` is on and read AFTER the loop by `deriveMacro`.  Nothing is computed for the panel's
    * sake and nothing here reaches a price.  Empty when off; draw-free either way. */
  final case class MacroInputs(stress: Array[Double],  // equity `stressIdx` after the session's step
                               bStress: Array[Double], // bond `stressIdx`
                               volState: Array[Double], // exp(logVol - volNorm), the vol state
                               amp: Array[Double],     // the spiral's amplification, lastLiq / impact
                               acc: Array[Double],     // the policy accommodation stock
                               wTrend: Array[Double],  // the trend crowd's capital share ENTERING the session
                               lev: Array[Double],     // the leverage ratio, read before the session's step
                               borrow: Array[Double],  // the borrowing stock itself, the credit cycle's level
                               rate: Array[Double],    // the policy rate, decimal
                               infl: Array[Double],    // inflation pressure, decimal
                               logCpi: Array[Double],  // the log price level, for nominal output
                               logFund: Array[Double]): // the fundamental's log base, BEFORE the
                                                       // rate markdown: the model's real activity
    /** Session `i`'s record, written by the price loop after both markets have stepped.  `vb` is
      * the vol state TIMES the vol response's and the credit regime's multipliers at the diffusive
      * share, and the column adds the slow channel's variance: the implied vol reads the price
      * process's own conditional variance, and a member that misses a real vol component reads
      * CALM while returns are turbulent, which drops the variance risk premium out of its band
      * -- measured, two macro rows fail without it.  Here, not inline, to keep `priceLoop` under
      * the JIT's method limit. */
    def record(i: Int, eqM: Market, bondStress: Double, vb: Double, slowVar: Double,
               depth: Double, accNow: Double, wTrendNow: Double, levNow: Double,
               borrowNow: Double, rateNow: Double, inflNow: Double, logCpiNow: Double,
               logFundNow: Double): Unit =
      stress(i)   = eqM.stressIdx
      bStress(i)  = bondStress
      volState(i) = math.sqrt(vb * vb + slowVar)
      amp(i)      = eqM.lastLiq * depth / 12.0
      acc(i)      = accNow
      wTrend(i)   = wTrendNow
      lev(i)      = levNow
      borrow(i)   = borrowNow
      rate(i)     = rateNow
      infl(i)     = inflNow
      logCpi(i)   = logCpiNow
      logFund(i)  = logFundNow

  object MacroInputs:
    /** `tot` sessions per column when the panel is on, empty columns otherwise. */
    def sized(on: Boolean, tot: Int): MacroInputs =
      def col = if on then new Array[Double](tot) else Array.emptyDoubleArray
      MacroInputs(col, col, col, col, col, col, col, col, col, col, col, col)

  /** The nine emitted counterparts, one value per session, in the counterpart's units. */
  final case class MacroPanel(spread: Array[Double], slope: Array[Double], cond: Array[Double],
                              ivol: Array[Double], yield10: Array[Double], credit: Array[Double],
                              policy: Array[Double], bank: Array[Double], output: Array[Double],
                              sibling: Boolean):  // a sibling path's panel (`-macronull`),
                                                  // decoupled from this path's price
    def drop(k: Int): MacroPanel =
      MacroPanel(spread.drop(k), slope.drop(k), cond.drop(k), ivol.drop(k), yield10.drop(k),
                 credit.drop(k), policy.drop(k), bank.drop(k), output.drop(k), sibling)
    def member(j: Int): Array[Double] = j match
      case 0 => spread
      case 1 => slope
      case 2 => cond
      case 3 => ivol
      case 4 => yield10
      case 5 => credit
      case 6 => policy
      case 7 => bank
      case _ => output

  /** The panel's FIXED maps.  No scale dials: every consumer vote is a percentile rank against
    * trailing history or a sign, so a column's scale is invisible to it, and each map is a
    * literal in its counterpart's units, disclosed as unanchored (the `-ddshape` precedent).  The
    * slope is the exception -- rate units the model anchors -- and its inversion share is graded,
    * which is what `TermPremium` is solved against.  The measurement components (`*Phi`, `*Sd`)
    * are PERSISTENT, an AR(1) per member: a real spread carries its own market's factors, which a
    * white error could not mimic without collapsing the level's autocorrelation (0.999 in the
    * record), and they are sized so the member's predictive R^2 for forward 60-session returns
    * matches the record's (`macro-2026-09-06.tsv`, <= 0.02) -- the oracle-leak guard. */
  object MacroK:
    val T2  = 2.0
    val T10 = 10.0
    val TermPremium  = 0.010                        // 10y over 2y at neutral, decimal
    val RegimeYears  = 1500.0 / DaysPerYear         // the regime countdown's mean, 250 + U(0, 2500)
    val SpreadBase   = 1.3;  val SpreadStress = 1.0; val SpreadBond = 1.0; val SpreadFloor = 0.5
    val SpreadSlow   = 12.0; val SlowMu = 0.00173  // the credit cycle: stress EWMA'd at a
                                                    // ~400-session half-life, 1 - 0.5^(1/400)
    val SpreadPhi    = 0.999; val SpreadSd = 0.012 // a credit-market factor as slow as the level
                                                    // itself (record ac1 0.999), sd ~0.27 pp
    // THE CONDITIONS INDEX: the leverage ratio over its mean, scaled to the record's spread (sd
    // about one index unit, p50 near the record's -0.24), plus the trend crowd's share over its
    // home.  The valuation gap is NOT a member: it is a price-level reading, and with the
    // leverage cycle carrying the coupling it only diluted the build-up (0.85 -> 0.81, measured)
    val CondBase     = -0.7; val CondLev = 10.0;   val CondCrowd = 2.0
    // THE LEVERAGE CYCLE's constants (see `levGain`).  The stock is a damped oscillator whose
    // period (four years) and damping ratio (0.2) put the record's NFCILEVERAGE shape on it --
    // weekly changes autocorrelated over a quarter, the level's autocorrelation 0.42 at a year
    // and ~0 at two, rank spells of ~four months -- around a mean of 0.75 with the level's
    // stationary sd 0.15 (`LevSd` is the velocity innovation that yields it).  Paid down per
    // session per unit of the stress index at 0.007: the paydown sets how long a cascade keeps
    // its gain, and with it lag-20 clustering (0.17 at 0.01, 0.19 at 0.007, the record's 0.225)
    // against kurtosis.  The fragility the spiral reads is the stock's rise over its
    // trailing-year average, so the multiplier is centred by construction; the ratio's drawdown
    // is smoothed over 21 sessions, a balance sheet not a tape; the multiplier is floored.
    val LevPeriod    = 1008.0
    val LevZeta      = 0.2
    val LevOmega     = 2.0 * math.Pi / LevPeriod
    val LevSpring    = LevOmega * LevOmega
    val LevDamp      = 2.0 * LevZeta * LevOmega
    val LevLevelSd   = 0.15
    val LevSd        = LevLevelSd * math.sqrt(4.0 * LevZeta * LevOmega * LevOmega * LevOmega)
    val LevMean      = 0.75
    val LevPaydown   = 0.007
    val LevSlowK     = 1.0 / 252.0
    val LevDdK       = 1.0 / 21.0
    val LevMultFloor = 0.25
    val CondPhi      = 0.995; val CondSd = 0.02
    val VrpMult      = 1.32                         // e^0.28: the record's log variance risk premium
    val IvolPhi      = 0.97; val IvolSd = 0.02;    val IvolFloor = 5.0
    val IvolAmpShare = 0.15                         // well below the 21-session average of the
                                                    // spiral's decay (0.69): the record's implied
                                                    // vol persists past what the spiral does, and
                                                    // the leverage cycle's spikes are sharper
                                                    // still -- 0.35 read the VIX's persistence
                                                    // at 0.69, on the band's floor
    val NullSeed     = 0x51b11a60L                  // the sibling path's seed offset (`-macronull`)
    val Columns      = Vector("macroSpread", "macroSlope", "macroCond", "macroIvol", "macroYield10",
                              "macroCredit", "macroPolicy", "macroBankCredit", "macroOutput")
    val Counterparts = Vector("BAA10Y", "T10Y2Y", "NFCILEVERAGE", "VIXCLS", "DGS10", "TOTBKCR/GDP",
                              "DFF", "TOTBKCR", "GDP")
    val Cadence      = Vector("daily", "daily", "weekly", "daily", "daily", "weekly", "daily",
                              "weekly", "quarterly")
    // THE CREDIT-TO-OUTPUT RATIO IS A STOCK OF ITS OWN.  It used to be the leverage cycle read in
    // percent, and one state cannot be two record series: the leverage cycle `macroCond` reads
    // turns every four years (weekly autocorrelation 0.42 at a year, -0.07 at two) where bank
    // credit over output turns over decades (0.97, 0.93), so the ratio changed 18pp a year against
    // the record's 2.1 and its rank was a four-year clock.  This stock ACCUMULATES, and every term
    // is a state the loop already carries, so it stays draw-free:
    // The stock grows at the smooth nominal rate output does, plus a deepening term that fades at
    // the ceiling, minus a paydown under stress, plus the borrowing cycle's deviation; the ratio
    // is what the two levels imply, so a depression raises it the way the record's rose in 2020.
    // ANCHORED ON THE RECORD DETRENDED, because the record's window carries a secular rise
    // (43 to 63 over 1990-2026, +0.72pp a year) that no century-long stationary world can hold and
    // that dominates the raw persistence rows: against the residual the model reads 0.72 / 0.42 at
    // one and two years (record 0.75 / 0.47), a p10-p90 spread of 7.6pp (6.6), a median 55.9
    // (57.3) and a year-over-year change of 2.0pp (2.1).  The secular rise itself is disclosed,
    // not fitted.
    val CreditScale  = 100.0
    val CreditMax    = 72.0    // the ceiling deepening fades at, percent of output
    val CreditDeepen = 0.1322  // how much faster than output credit grows at ratio 0, per year,
                               // fading linearly to nothing at the ceiling
    val CreditPay    = 0.45    // paydown per year at full equity stress, as a growth rate
    val CreditLev    = 0.18    // the borrowing cycle's deviation as a growth rate per unit
    val CreditStart  = 57.3    // the record's median, where the stock starts
    // NOMINAL OUTPUT, the denominator made explicit so `macroBankCredit` (TOTBKCR) and
    // `macroOutput` (GDP) can be read apart.  Real output grows at `OutReal` and takes `OutShare`
    // of the fundamental's EXCESS growth -- the fundamental is the model's real activity, and a
    // macro disaster is its depression -- and the price level makes it nominal.  An INDEX: the
    // model has no anchor for the size of its economy, so the base is `OutBase` at the first
    // EMITTED session (`logBasket`'s convention) and every consumer question about it -- growth,
    // the ratio, credit relative to output -- is scale-free.
    val OutReal      = 0.024   // real output trend per year, the record's 1990-2026 rate.  NOMINAL
                               // growth is this plus the model's own inflation, which runs 4.0%/yr
                               // where the record's window ran 2.5 -- so emitted output grows
                               // 6.4%/yr against the record's 4.85, disclosed, not fitted: the
                               // price level is anchored in the price model, not here
    val OutShare     = 0.12    // share of the fundamental's excess growth output takes: earnings
                               // swing ~10%/yr where output swings ~2.6%
    val OutBase      = 100.0
    // THE POLICY RATE is the loop's own rate read the way policy PUBLISHES it: a target set at a
    // meeting, quantized to a quarter point, held until the next one.  The loop's rate is a
    // diffusion -- it carries the rate uncertainty that makes stocks and bonds co-move in an
    // inflation regime -- so read raw it moves every session where the record's overnight rate is
    // unchanged on 42% of weekdays, and 0.32pp over a quarter against the record's 0.13pp.  The
    // staircase is the same state, published: it consumes no draw, so a world's other members are
    // unchanged by it.
    val PolicyStep    = 0.25                        // the record's move size, percentage points
    val PolicyMeeting = 32                          // sessions between meetings; 8 a year is 31.5

  /** THE MACRO PANEL, derived from the finished loop's recorded state.  Each member is a fixed
    * map of states the loop already carries plus its own persistent measurement component, so the
    * coupling to price is causal by construction and the read is lossy by construction:
    *   spread  BAA10Y-like, pp: base + equity stress + bond stress, floored
    *   slope   T10Y2Y-like, pp: the 10y minus the 2y yield the rate process implies -- each the
    *           OU-expected average of the short rate over its horizon, the rate decaying to the
    *           policy target at `rateSpeed`, the target's inflation term at the regime's mean
    *           life and its accommodation term at `unwind` -- plus a term premium.  The same path
    *           drives the bond, so the slope cannot contradict the `bond` column, and it inverts
    *           when policy is tight against neutral: derived, never synthesized.  No noise: it is
    *           an expectation, and the one anchored-scale member
    *   cond    NFCILEVERAGE-like, raw index: the trend crowd's share over its home plus the
    *           valuation gap -- the model's build-then-unwind state
    *   ivol    VIXCLS-like, annualized %: the session's conditional sd x the record's variance
    *           risk premium, floored
    *   yield10 DGS10-like, pp: the slope's long leg, the same expectation at ten years
    *   credit  TOTBKCR/GDP-like, %: the ratio the two levels below imply -- its own slow stock,
    *           deepening toward a ceiling, paid down under stress, over an output that a
    *           depression cuts
    *   policy  DFF-like, pp: the loop's own policy rate, re-set at a meeting to the nearest
    *           quarter point and held -- the target as policy publishes it
    *   bank    TOTBKCR-like, index: the credit stock, 100 at the first emitted session
    *   output  GDP-like, index: nominal output, 100 at the first emitted session
    * The last five are DRAW-FREE: states the loop already carries, read in the counterpart's
    * units, so adding one leaves every other member of a world bit-identical.
    * Publication -- cadence, release lag, revisions -- is the consumer's point-in-time layer, so
    * every member is the value an agency would MEASURE that session.  Three normals per session
    * from a dedicated stream, spread then cond then ivol (the draw order is part of the
    * cross-language contract); the OU factors go through `expDet`; everything else is IEEE-exact
    * arithmetic in fixed order. */
  def deriveMacro(w: World, m: MacroInputs, seed: Long, k: Double, base: Int): Option[MacroPanel] =
    if w.macroPanel <= 0 then None
    else
      val rng = new NumPyRNG(seed ^ 0x3ac20c0dL)
      val n   = m.stress.length
      val dtY = 1.0 / DaysPerYear
      // the T-year average of a deviation decaying at speed k: (1 - e^{-kT}) / (kT), 1 at k = 0
      def phi(k: Double, t: Double): Double =
        val kt = k * t
        if kt <= 0.0 then 1.0 else (1.0 - expDet(-kt)) / kt
      val dR = phi(w.rateSpeed, MacroK.T10) - phi(w.rateSpeed, MacroK.T2)
      val dA = phi(w.unwind, MacroK.T10) - phi(w.unwind, MacroK.T2)
      val dI = phi(1.0 / MacroK.RegimeYears, MacroK.T10) - phi(1.0 / MacroK.RegimeYears, MacroK.T2)
      // the 10-year yield's own factors: the level the slope is a difference of
      val pR10 = phi(w.rateSpeed, MacroK.T10)
      val pA10 = phi(w.unwind, MacroK.T10)
      val pI10 = phi(1.0 / MacroK.RegimeYears, MacroK.T10)
      // `k` is the world's implied-vol level (`ChannelLevel.kIv`): forward realized vol over the
      // very factor read below, as a mean of logs, so the member's log premium over forward
      // realized vol is the record's by construction in every world and only the premium's R^2
      // and persistence read the world.  Re-levelling the DIFFUSIVE sd instead counted the slow
      // channel's variance twice -- a world with the channel at half share read 0.55 against the
      // record's 0.29, and the shipped default with the panel on read 0.44.
      val kIvol  = k * MacroK.VrpMult
      val spread = new Array[Double](n); val slope = new Array[Double](n)
      val cond   = new Array[Double](n); val ivol  = new Array[Double](n)
      val yield10 = new Array[Double](n); val credit = new Array[Double](n)
      val policy  = new Array[Double](n)
      val bank    = new Array[Double](n); val output = new Array[Double](n)
      var eS = 0.0; var eC = 0.0; var eV = 0.0; var slow = 0.0
      var target25 = 0.0
      var ratio    = MacroK.CreditStart
      var logOut   = 0.0
      var i = 0
      while i < n do
        eS = MacroK.SpreadPhi * eS + MacroK.SpreadSd * rng.randn()
        eC = MacroK.CondPhi * eC + MacroK.CondSd * rng.randn()
        eV = MacroK.IvolPhi * eV + MacroK.IvolSd * rng.randn()
        slow += MacroK.SlowMu * (m.stress(i) - slow)
        spread(i) = math.max(MacroK.SpreadFloor, MacroK.SpreadBase + MacroK.SpreadStress * m.stress(i) +
                                                 MacroK.SpreadSlow * slow + MacroK.SpreadBond * m.bStress(i) + eS)
        val target = w.rateMean + m.infl(i) - m.acc(i)
        slope(i) = 100.0 * (m.infl(i) * dI - m.acc(i) * dA + (m.rate(i) - target) * dR + MacroK.TermPremium)
        // THE 10-YEAR YIELD (DGS10-like, pp): the OU-expected average of the short rate over ten
        // years -- the neutral rate, the regime's inflation term, the accommodation term and the
        // rate's own gap decaying at their speeds -- plus the term premium.  The slope above is
        // this less the 2-year's, so the level cannot contradict it.  No noise: an expectation.
        yield10(i) = 100.0 * (w.rateMean + m.infl(i) * pI10 - m.acc(i) * pA10 + (m.rate(i) - target) * pR10 + MacroK.TermPremium)
        // NOMINAL OUTPUT (GDP-like, index): the real trend and the price level -- the SMOOTH
        // nominal rate credit also grows at -- plus `OutShare` of the fundamental's excess growth,
        // which is the model's real activity and carries its disasters as depressions.
        val dNom    = MacroK.OutReal * dtY + (if i > 0 then m.logCpi(i) - m.logCpi(i - 1) else 0.0)
        val dExcess = if i > 0 then MacroK.OutShare * ((m.logFund(i) - m.logFund(i - 1)) - w.drift * dtY)
                      else 0.0
        logOut += dNom + dExcess
        output(i) = expDet(logOut)
        // THE CREDIT-TO-OUTPUT RATIO (TOTBKCR/GDP-like, percent).  The STOCK grows at the smooth
        // nominal rate plus its own three terms, so the ratio is what the two levels imply:
        //   deepening   credit outgrows output by `CreditDeepen` at ratio 0, fading to nothing at
        //               the ceiling -- the secular half of the record's window, stationary
        //   paydown     `CreditPay` a year at full equity stress -- itself a state that decays
        //               over weeks, so this is a crisis's deleveraging and not one session's --
        //               and the LEVEL then carries that crisis until deepening pulls it back,
        //               which is what makes the ratio's own history readable
        //   appetite    the borrowing cycle's deviation from its mean, so the ratio still moves
        //               with the model's own credit state at an amplitude that leaves the
        //               four-year period a ripple rather than the signal
        // and the DENOMINATOR moves it too: output's excess growth is subtracted, so a depression
        // raises the ratio the way the record's rose in 2020.  Euler on a per-session step of
        // ~1e-4, so no exponential is needed and the twins share the arithmetic exactly.
        credit(i) = ratio
        ratio += ratio * ((MacroK.CreditDeepen * (1.0 - ratio / MacroK.CreditMax)
                           - MacroK.CreditPay * m.stress(i)
                           + MacroK.CreditLev * (m.borrow(i) - MacroK.LevMean)) * dtY - dExcess)
        ratio = math.max(0.0, ratio)
        // THE POLICY RATE (DFF-like, pp): the loop's own rate, published as policy publishes it --
        // re-set at a meeting to the nearest quarter point and held until the next.  Draw-free.
        // `floor(x + 0.5)`, never `rint`: half-way values must round the same way in both twins,
        // and Scala's rint rounds half to EVEN where Rust's round rounds half AWAY from zero.
        if i % MacroK.PolicyMeeting == 0 then
          target25 = MacroK.PolicyStep * math.floor(100.0 * m.rate(i) / MacroK.PolicyStep + 0.5)
        policy(i) = target25
        // the leverage ratio -- the state the record's index measures and, through `levGain`,
        // the state the model's big declines follow -- over its mean, plus the crowd's share
        cond(i) = MacroK.CondBase + MacroK.CondLev * (m.lev(i) - MacroK.LevMean) +
                  MacroK.CondCrowd * (m.wTrend(i) - w.trendShare) + eC
        // a forward-looking vol prices the amplification it expects over its horizon, not the
        // session's: the spiral's stress index decays at 0.96 a session, and its 21-session
        // average is `IvolAmpShare` of today's
        ivol(i) = math.max(MacroK.IvolFloor,
                           kIvol * m.volState(i) * (1.0 + MacroK.IvolAmpShare * (m.amp(i) - 1.0)) * (1.0 + eV))
        i += 1
      // THE INDEX BASE: `OutBase` at the first EMITTED session, so both levels are indices a
      // consumer reads as growth and as a ratio.  The credit stock is built from the rescaled
      // output, so `macroBankCredit / macroOutput x 100` reproduces `macroCredit`: to 1e-6 here,
      // and to 2e-6 pp read back from the emitted text, which is the columns' own six-decimal
      // rounding -- `logBasket`'s convention.
      val f = MacroK.OutBase / output(math.min(math.max(base, 0), n - 1))
      var q = 0
      while q < n do
        output(q) = output(q) * f
        bank(q)   = credit(q) * output(q) / MacroK.CreditScale
        q += 1
      Some(MacroPanel(spread, slope, cond, ivol, yield10, credit, policy, bank, output,
                      sibling = w.macroNull > 0))

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
    // THE NULL PANEL (`-macronull`): the panel of a SIBLING path -- the same world at another
    // seed, its own price loop and its own measurement stream -- so the columns keep this
    // world's marginals and persistence and their coupling to this path's price is nil.  One
    // extra price loop per path, only when on.
    val (macroIn, macroSeed) =
      if w.macroNull > 0 then
        val sib = seed ^ MacroK.NullSeed
        (priceLoop(w, years, sib).macroIn, sib)
      else (pr.macroIn, seed)
    pr.path.copy(
      divYield  = div._1,
      traded    = div._2,
      sat       = if w.satBeta > 0.0 then chan.sat.drop(BurnIn) else Array.emptyDoubleArray,
      logHi     = if w.rangeScale > 0.0 then chan.logHi.drop(BurnIn) else Array.emptyDoubleArray,
      logLo     = if w.rangeScale > 0.0 then chan.logLo.drop(BurnIn) else Array.emptyDoubleArray,
      logVolume = if w.volIdio > 0.0 then chan.logVolume.drop(BurnIn) else Array.emptyDoubleArray,
      logOpen   = if w.overnight > 0.0 then chan.logOpen.drop(BurnIn) else Array.emptyDoubleArray,
      names     = if w.basket > 0 then chan.names.map(_.drop(BurnIn)) else Vector.empty,
      chanK     = level.k,
      chanKSat  = level.kSat,
      chanKDiv  = level.kDiv,
      chanKVs   = level.kVs,
      chanKIv   = level.kIv,
      chanKDr   = if w.basketDrift > 0.0 then level.kDr else 0.0,
      macroPanel = deriveMacro(w, macroIn, macroSeed, level.kIv, BurnIn).map(_.drop(BurnIn)))

  /** `simulateAt` at the world's own level, solved here per call -- `simPaths` solves it once
    * for the whole ensemble, so prefer that for more than one path. */
  def simulate(w: World, years: Int, seed: Long): Path = simulateAt(w, years, seed, worldLevel(w))

  /** THE BUST SWING's state (item 25, see `bustAmp`): the gap's slow mean, the level at the
    * running peak, the armed state, the episode's low and the sessions since it, the swing and
    * its own stream.  One per price loop and never shared; the stream is drawn only by `advance`,
    * which the loop calls only while the dial is on, so 0 is draw-free.
    *
    * A class of its own because `priceLoop` must stay under the JIT's 8000-byte method limit:
    * past it HotSpot never compiles the loop at all. */
  private final class BustSwing(amp: Double, seed: Long):
    private val rng   = new NumPyRNG(seed ^ 0xb057c0deL)
    private val gapMu = 1.0 / (BustMeanYears * DaysPerYear)
    private var gapMean  = 0.0
    private var peakLvl  = 0.0
    private var armedS   = 0.0
    private var epLow    = Double.PositiveInfinity
    private var sinceLow = 0
    private var swing    = 0.0
    private var over     = false
    /** the level the swing has priced in, which perceived fair carries too */
    var news = 0.0
    /** this session's repricing, which the derived channels see like a news jump */
    var move = 0.0
    /** sessions after the burn-in on which the ceiling held the swing */
    var ceilDays = 0

    /** One session, ahead of the step.  The level is the pre-step price against the fundamental
      * as this session left it -- information strictly before the step, like every crowd's --
      * and the slow mean advances after the read.  The state arms once per peak, off a drawdown
      * of 0.2 log under a peak `BustArm` over the mean; the unwind is in progress while its lows
      * are under a year old (the NDX made one every six months through 2000-02) and over once a
      * year has passed without one (2003-06 was calm 70% under the 2000 peak).  The swing is
      * repriced here, and perceived fair carries it too, so the step sees no gap from it. */
    def advance(eqM: Market, logVbase: Double, i: Int): Unit =
      val gapNow = eqM.logP - logVbase
      val lvl = gapNow - gapMean
      // a running mean until its window fills, so the average starts on the gap's own history
      // rather than at 0 -- the gap's long-run mean is not 0 (see `BurnIn`)
      gapMean += math.max(gapMu, 1.0 / (i + 1)) * (gapNow - gapMean)
      if eqM.logP >= eqM.peak then
        peakLvl = lvl
        // the unwind is over once the high is regained (see `BustDecayOver`)
        if armedS > 0.0 then over = true
      if peakLvl > BustArm && eqM.peak - eqM.logP > 0.2 then
        val armed = math.min((peakLvl - BustArm) / BustRamp, 1.0)
        if armed > armedS then
          armedS = armed
          epLow = eqM.logP
          sinceLow = 0
          over = false
        peakLvl = 0.0
      if eqM.logP < epLow then
        epLow = eqM.logP
        sinceLow = 0
      else sinceLow += 1
      armedS *= (if sinceLow <= DaysPerYear then BustDecayNear else BustDecayAfter)
      if over then armedS *= BustDecayOver
      if armedS < BustOff then
        armedS = 0.0
        over = false
      val m = 1.0 + BustRelief * armedS
      eqM.stressDiv = m
      eqM.dragMult = 1.0 / m
      swing = BustPhi * swing + math.sqrt(1.0 - BustPhi * BustPhi) * rng.randn()
      // THE CEILING (see `BustCeil`): the level the swing carries is held to BustCeil under the
      // running peak, measured from the price without it (the centre); once the centre is
      // already nearer, the swing can only subtract
      val raw  = amp * armedS * swing
      val room = math.max(eqM.peak - BustCeil - (eqM.logP - news), 0.0)
      val priced =
        if raw > room then
          if i >= BurnIn then ceilDays += 1
          room
        else raw
      move = priced - news
      eqM.logP += move
      news = priced

  /** A fired jump's size: Student-t with JumpNu degrees of freedom, standardised to unit
    * variance, so the size is set by `scale` alone.  Drawn as z / sqrt(chi2(nu)/nu) -- the draw
    * ORDER here is part of the cross-language contract, not an implementation detail. */
  private def jumpDraw(jrng: NumPyRNG, skew: Double, scale: Double): Double =
    val z = jrng.randn()
    var chi = 0.0
    var k = 0
    while k < JumpNu do
      val g = jrng.randn()
      chi += g * g
      k += 1
    val t = z / math.sqrt(chi / JumpNu) / math.sqrt(JumpNu / (JumpNu - 2.0))
    (t - skew) * scale

  /** One independent history's PRICE LOOP, with the channels' per-session inputs recorded for the
    * second pass.  Local mutable state only — nothing escapes this method. */
  def priceLoop(w: World, years: Int, seed: Long): Priced =
    // java.lang.Math throughout (`Math.exp`, `max`, `min`), NOT scala.math.  This method is past
    // what C2 will inline into -- it compiled with under 9 kB of callees inlined -- so scala.math's
    // forwarding call stayed a real call on every session (min and max alone were 13% of a
    // search's CPU).  The java.lang versions are intrinsics, compiled in place whatever the
    // budget, and they are the functions scala.math forwards to, so no result moves.
    import java.lang.Math.{max, min}
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
    // THE SKEWED BODY's stream and constants (see `noiseSkew`)
    val skewRng = new NumPyRNG(seed ^ 0x05ce3a11L)
    // THE DAY FLIP's stream, what this session's flips owe and the last session's markdown (see
    // `newsFlip`)
    val flipRng = new NumPyRNG(seed ^ 0x0f119e00L)
    var flipOwed = 0.0
    var markdownPrev = 0.0
    val skewA   = Math.sqrt(1.0 - w.noiseSkew * w.noiseSkew)
    val skewB   = Math.sqrt(1.0 - 2.0 * w.noiseSkew * w.noiseSkew / math.Pi)
    // The leverage cycle's own stream, same contract: read only when the stock is evolved.
    val lrng = new NumPyRNG(seed ^ 0xc2ed17c7L)
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
    val sqdt = Math.sqrt(dt)

    // The halt is an EQUITY market-structure rule.  The bond leg keeps the bare guard: there is no
    // market-wide breaker on Treasuries, and inventing one would be a fudge wearing a mechanism's
    // name.
    val eqM = new Market(w.valuePull, w.stress, 12.0 / w.depth, w.recoveryDrag, w.recoveryFloor,
                         w.haltLimit, w.stressAdapt)
    val bdM = new Market(KValueBond, w.stress, 1.0)
    // THE AMPLIFIER STUDY's gain scale: the equity market's alone (the bond's impact IS its
    // reference).  Exact forms at 1 and 0.5; anything else goes through `expDet` on a log, which
    // the twins' parity run guards.
    if w.stressScale > 0.0 then
      val ratio = w.depth / DepthRef
      eqM.gainMult = if w.stressScale == 1.0 then ratio
                     else if w.stressScale == 0.5 then Math.sqrt(ratio)
                     else expDet(w.stressScale * Math.log(ratio))

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
    // ITEM 12's two states, both exactly inert at their dial's 0: `kickS` is the persistent
    // kick's own EWMA of the same saturated decline signal (at 0 it IS `levSig`, so the
    // multiplier is the shipped one bit for bit), `asymG` the asymmetric noise vol's log
    // multiplier, driven by the DIFFUSIVE DRAW rather than by any price-derived quantity.
    var kickS = 0.0
    var volRespS = 0.0
    // THE BUST SWING's state (see `BustSwing`).  Draw-free at 0: it advances only while the dial
    // is on.
    val bust = new BustSwing(w.bustAmp, seed)
    // THE VALUATION CYCLE's state (see `cycleSd`): a stationary AR(1) drawn from its own stream
    // and started from its stationary law; the price and its running peak start ON the cycle,
    // so the first session is stationary too.  Draw-free at 0: the stream is only drawn while
    // the dial is on, and the price then starts at the fundamental as before.
    val cycRng  = new NumPyRNG(seed ^ 0xc7c1e0deL)
    val cycPhi  = if w.cycleYears <= 0.0 then 0.0
                  else Math.exp(-Math.log(2.0) / (w.cycleYears * DaysPerYear))
    val cycInno = w.cycleSd * Math.sqrt(1.0 - cycPhi * cycPhi)
    var cyc = 0.0
    // the running peak of the price WITHOUT the cycle, for the drag's drawdown read
    var cycPeakEx = 0.0
    if w.cycleSd > 0.0 then
      cyc = w.cycleSd * cycRng.randn()
      eqM.logP = cyc
      eqM.peak = cyc
    var volRespA = 0.0
    var asymG = 0.0
    var asymA = 0.0
    val asymNorm = noiseAsymVar(w.noiseAsym, w.noiseAsymPhi)
    // Settled equity stress for the refuge bid (see `refugeDays`); draw-free, and both its use
    // and its update sit behind `refugeDays > 0`, so 0 is bit-identical off.
    var settledStress = 0.0
    val settleMu = if w.refugeDays > 0.0 then 1.0 - Math.exp(-Math.log(2.0) / w.refugeDays) else 0.0
    // THE LEVERAGE CYCLE's stock (see `levGain`), evolved whenever the mechanism or the macro
    // panel reads it, on its own stream, and reaching the price only through `levMult`, which
    // stays exactly 1.0 with the dial off.  `lev` is the session's ratio, read before the step;
    // `levSlow` the stock's trailing-year average the growth is read against; `ddS` the
    // drawdown the ratio reads.
    val levOn  = w.levGain > 0.0 || w.macroPanel > 0 || w.newsLev > 0.0 || w.creditRegime > 0.0
    var borrow = MacroK.LevMean
    var levVel = 0.0
    var levSlow = MacroK.LevMean
    var ddS    = 0.0
    var lev    = 0.0
    val volNorm = (w.volOfVol * w.volOfVol) / max(1e-9, 1.0 - w.volPersist * w.volPersist)
    // THE SLOW REPRICING CHANNEL (item 15).  `slowShare` of the diffusive variance leaves the
    // order-flow channel and reappears as a repricing that moves the fundamental and the price
    // TOGETHER, the way the news jump does, so the value channel has nothing to arbitrage and the
    // move never passes through `step` and its spiral.  Its state is driven ONLY by its own
    // leverage term, so the variance it carries is long-memoried AND asymmetric -- a symmetric
    // component was measured and is strictly worse, because variance moved out of the amplifier
    // then loses the leverage profile the amplifier was supplying.  Own RNG stream, and
    // `slowShare` is the switch: at 0 the block never runs and `mix` is exactly 1.
    val slowRng = new NumPyRNG(seed ^ 0x510ec0deL)
    var slowG = 0.0
    var slowB = 0.0
    // scaled by sqrt(1 - phi^2) on input, so `slowLev` is in units of the state's STATIONARY sd
    // and the centring is its variance; unscaled it runs 11x nominal and volatility reaches 200%.
    val slowK = Math.sqrt(1.0 - w.slowPhi * w.slowPhi)
    val slowNorm = w.slowLev * w.slowLev
    val slowScale = SigmaN * w.slowVol * (12.0 / w.depth)
    val mix = Math.sqrt(1.0 - w.slowShare)
    // News variance DISPLACES diffusive noise (see `newsDampAt`); 1.0 when the channel is off.
    val newsDamp = newsDampAt(w.newsRate, w.newsSize)
    // the bond leg of news (`newsBond`): its state, and the stream that picks the news it answers
    // (`newsBondSkip`)
    var newsB       = 0.0
    val bondSkipRng = new NumPyRNG(seed ^ 0x0b0d7a11L)
    // THE CREDIT-TRIGGERED VOL REGIME (see `creditRegime`): its level, the sessions its plateau still
    // holds, and the stream that draws its onsets
    var regimeR     = 0.0
    var regimeHeld  = 0
    val regimeRng   = new NumPyRNG(seed ^ 0x0c4e91a1L)
    val crowdWin = w.crowd match
      case Crowd.Trend(d) => max(2, math.round(d * 252.0 / 365.25).toInt)
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
                   else 1.0 - Math.exp(-Math.log(2.0) / (w.beliefYears * DaysPerYear))
    val leakMu = w.beliefLeak / DaysPerYear
    // Growth-extrapolation state: EWMA of the fundamental's per-session log change, annualized in
    // the perceived-fair term.  Seeded at the unconditional drift so burn-in starts neutral.
    var gEwma = w.drift * dt
    val gMu   = if w.capWindow <= 0.0 then 0.0
                else 1.0 - Math.exp(-Math.log(2.0) / (w.capWindow * DaysPerYear))
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
    val chOn    = w.rangeScale > 0.0 || w.satBeta > 0.0 || w.overnight > 0.0 || w.basket > 0 ||
                  w.macroPanel > 0
    val chIn    = ChannelInputs.sized(chOn, tot)
    // THE MACRO PANEL's inputs, recorded per session and read after the loop by `deriveMacro`;
    // empty when the dial is off, draw-free either way.
    val mcOn    = w.macroPanel > 0
    val mcIn    = MacroInputs.sized(mcOn, tot)
    var crowdFlowSum = 0.0
    var clampsAtBurn = 0
    var eqFloorAtBurn = 0; var eqTailAtBurn = 0; var eqHaltAtBurn = 0

    var i = 0
    while i < tot do
      val logPOpen = eqM.logP
      // ---- exogenous layer: regimes, fundamental, the policy rate ---------------------------
      regimeCountdown -= 1
      if regimeCountdown <= 0 then
        inflTarget = if rng.nextDouble() < w.inflProb then Math.min(InflCap, Math.abs(rng.randn()) * w.inflSize) else 0.0
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
            recLeft = max(1, (w.disasterRecLen * DaysPerYear).toInt)
            recStep = w.disasterRecover * w.disasterSize / recLeft
        else
          if recLeft > 0 then { logVbase += recStep; recLeft -= 1 }
          if drng.nextDouble() < disProb then
            disLeft = max(1, (w.disasterLen * DaysPerYear).toInt)
            disStep = w.disasterSize / disLeft
            if i >= BurnIn then disasterCount += 1
      logVbase += driftNow * dt + w.fundVol * sqdt * rng.randn()
      // FAIR-VALUE NEWS JUMP: a permanent markdown repriced the SAME session -- the fundamental
      // and the price take the full drop together, so the price/fair gap, and with it the value
      // channel, the belief EWMA and the mispricing, are untouched: a pure random-walk step with
      // nothing for value capital to buy back -- unless `newsRevert` keeps part of it off the
      // fundamental, a gap value capital then buys back.  Morning news, placed before the
      // demand-flows read of logP, so the momentum crowd trades on it this session the way it
      // trades on `markdown`.
      // The compensator is deterministic and returns the expected drift cost on BOTH legs.  With
      // `newsLev` on, the intensity reads the credit stock's growth as it stood before this
      // session, and the compensator reads the same intensity (see `newsLev`).
      var newsJ = 0.0; var jumpNow = 0.0
      if w.newsRate > 0.0 then
        val pNews =
          if w.newsLev > 0.0 then
            // on the grid: see `NewsLevGrid`
            val g = Math.floor((borrow - levSlow) * NewsLevGrid + 0.5) / NewsLevGrid
            Math.min(w.newsRate * Math.min(Math.max(1.0 + w.newsLev * g, 0.0), 2.0) / DaysPerYear, NewsPCap)
          else w.newsRate / DaysPerYear
        // the off branch keeps the released expression, so 0 is bit-identical
        val comp0 = if w.newsLev > 0.0 then pNews * w.newsSize else w.newsRate * w.newsSize / DaysPerYear
        // at the session's own volatility (see `newsScale`); the off branch keeps the released
        // expressions
        // two scalars, not a pair: a tuple here is an allocation on every session
        val newsM =
          if w.newsScale > 0.0 then
            1.0 - w.newsScale + w.newsScale * newsVolMultiplier(w, logVol, volNorm, kickS, volRespS)
          else 1.0
        val size = if w.newsScale > 0.0 then w.newsSize * newsM else w.newsSize
        val comp = if w.newsScale > 0.0 then comp0 * newsM else comp0
        // the fundamental takes the permanent share of the step and of its compensator (see
        // `newsRevert`); a factor of exactly 1.0 when it is off
        val perm = 1.0 - w.newsRevert
        logVbase += perm * comp
        // the price's lift, less the share the body's flips will pay (see `newsFlip`)
        if w.newsFlip > 0.0 then
          flipOwed = w.newsFlip * comp
          eqM.logP += comp - flipOwed
        else eqM.logP += comp
        // the bond's leg, fair and price together, so its value channel has nothing to undo
        // reversed in an inflation regime, read as it stood before the session
        val bk =
          if inflPress > InflRegimeEdge then -w.newsBond * (w.duration / DurationRef)
          else w.newsBond * (w.duration / DurationRef)
        if w.newsBond > 0.0 then
          newsB = NewsBondDecay * newsB - bk * comp
          bdM.logP -= bk * comp
        if nrng.nextDouble() < pNews then
          logVbase -= perm * size
          eqM.logP -= size
          if w.newsBond > 0.0 then
            // the news it answers carries the leg scaled to keep its mean (see `newsBondSkip`);
            // the off branch draws nothing
            val leg =
              if w.newsBondSkip > 0.0 then
                if bondSkipRng.nextDouble() < w.newsBondSkip then 0.0
                else bk * size / (1.0 - w.newsBondSkip)
              else bk * size
            newsB += leg
            bdM.logP += leg
          newsJ = size
      // the channel's share of THIS session's conditional variance, for the implied-vol member;
      // 0 when the channel is off, so that member is unchanged.
      var slowVar = 0.0
      if w.slowShare > 0.0 then
        val zs = slowRng.randn()
        val smul = Math.exp(slowG - slowNorm)
        slowVar = w.slowVol * w.slowVol * smul * smul
        val sm = slowScale * smul * zs
        // only `slowPerm` of it reaches the fundamental: the rest opens a gap the value channel
        // closes, which is what keeps the momentum crowd from chasing the whole move into a
        // variance ratio.  The bond takes the same repricing with the opposite sign.
        logVbase += w.slowPerm * sm
        eqM.logP += sm
        // a YIELD repricing, so the bond's move scales with its duration like every other bond
        // flow (`SigmaNBond`, the refuge); the ratio is a bit-exact 1.0 at the shipped duration
        // in an inflation regime `slowBondInfl` of the leg reverses (see the field); a factor of
        // exactly 1.0 when it is off
        val inflSign = if w.slowBondInfl > 0.0 && inflPress > InflRegimeEdge then 1.0 - 2.0 * w.slowBondInfl else 1.0
        val bm = -w.slowBeta * sm * (w.duration / DurationRef) * inflSign
        bdM.logP += bm
        slowB += w.slowPerm * bm
        slowG = w.slowPhi * slowG - w.slowLev * slowK * zs
      inflPress += w.inflSpeed * (inflTarget - inflPress)
      // policy: chase rateMean + pressure MINUS accommodation, and accommodation is a CAPPED
      // STOCK rather than a cut speed -- eased in within ~2 months, withdrawn over years.  As a
      // speed it was unbounded, so a stress episode took the rate to the floor and the same
      // `rateSpeed` pulled it straight back; the bond's peak was set by that spike.  Inflation
      // suppresses the easing, which is what ties policy's hands in 2022-like regimes.
      val accWant = w.easing * eqM.stressIdx * Math.exp(-inflPress / 0.005)
      acc = if accWant > acc then acc + EaseInSpeed * (accWant - acc) * dt
            else max(0.0, acc - w.unwind * acc * dt)
      val rOld = rate
      // rate UNCERTAINTY rises with inflation pressure (2022: MOVE elevated all year).  This is what
      // makes stocks and bonds co-move in an inflation regime: both are priced off the same rate,
      // so more rate news = more shared-factor variance = the correlation flip.  A constant rate
      // noise produced a flip of only +0.05 — present but too weak to pass its own gate.
      rate = max(0.0, rate + w.rateSpeed * ((w.rateMean + inflPress - acc) - rate) * dt
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
            val tgt = if pPrev >= maSum / min(i, crowdWin) then 1.0 else 0.0
            if Math.abs(tgt - crowdE) > Band then crowdE = tgt
          case Crowd.VolScaled =>
            val r = Math.log(pPrev / px(max(i - 2, 0)))
            crowdRv = 0.94 * crowdRv + 0.06 * r * r
            val v = Math.sqrt(crowdRv * DaysPerYear)
            crowdAnchor = if crowdAnchor == 0.0 then v else 0.999 * crowdAnchor + 0.001 * v
            val tgt = max(0.0, min(1.0, if v > 0 then crowdAnchor / v else 1.0))
            if Math.abs(tgt - crowdE) > Band then crowdE = tgt
          case Crowd.Drawdown(d) =>
            if pPrev > crowdPeak then crowdPeak = pPrev
            val tgt = if pPrev >= crowdPeak * (1.0 - d.toDouble / 100.0) then 1.0 else 0.0
            if Math.abs(tgt - crowdE) > Band then crowdE = tgt
          case Crowd.Momentum => ()

      // ---- demand flows ----------------------------------------------------------------------
      val logPobs = eqM.logP - markdown                 // what everyone actually sees and trades
      val mispricingPre = logVbase - eqM.logP           // value agents arb the traded component
      val lookback = 60
      val past = if i >= lookback then Math.log(px(i - lookback)) else logPobs
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
      // THE ASYMMETRIC NOISE VOL (`noiseAsym`): `z` is this session's diffusive draw, a unit
      // normal BY CONSTRUCTION, which is why the state it drives cannot be inflated by the
      // price the way a realized-scale or return-standardized input is (measured: those forms
      // self-excite -- see PLAN item 11's map).  Read before its own update, like the kick.
      val z       = rng.randn()
      // THE SKEWED BODY (see `noiseSkew`): the noise's own shock; the state below keeps reading
      // `z`, the normal it is built on.  The off branch draws nothing.
      val zBody   = if w.noiseSkew > 0.0 then skewedShock(z, skewRng.randn(), w.noiseSkew, skewA, skewB) else z
      // Level-preserving, the same convention `volNorm` applies to the vol state: g is centred at
      // minus its own stationary variance, so the noise's VARIANCE is what it was and the dial
      // buys shape rather than volatility.
      val asymM   =
        if w.noiseAsym <= 0.0 then 1.0
        else if w.noiseAsymCap > 0.0 then Math.exp(min(asymG - asymNorm, w.noiseAsymCap))
        else Math.exp(asymG - asymNorm)
      val dNoise0 = newsDamp * SigmaN * Math.exp(logVol - volNorm) * zBody * asymM * mix
      // read BEFORE this session's update, like the kick: the response is to PAST declines
      val volRespM = if w.volResp > 0.0 then Math.exp(w.volResp * volRespS) else 1.0
      val dNoise0k = if w.leverage > 0.0 then dNoise0 * Math.exp(w.leverage * kickS) else dNoise0
      val dNoiseR = if w.volResp > 0.0 then dNoise0k * volRespM else dNoise0k
      // THE CREDIT-TRIGGERED VOL REGIME (see `creditRegime`): the plateau holds, then decays;
      // outside a spell an onset is drawn against the credit growth gap as it stood before the
      // session.  The noise and the session's sd both take it, so the vol response's state stays
      // scale-free; the off branch draws nothing
      val regimeM =
        if w.creditRegime > 0.0 then
          if regimeHeld > 0 then regimeHeld -= 1 else regimeR *= CreditRegimeDecay
          val excess = (borrow - levSlow) / CreditGrowthSd - CreditRegimeTheta
          if regimeR < CreditRegimeRearm && excess > 0.0 &&
             regimeRng.nextDouble() < w.creditRegimeRate * excess / DaysPerYear then
            regimeR = 1.0
            regimeHeld = CreditRegimeHold
          Math.exp(w.creditRegime * regimeR)
        else 1.0
      val dNoise  = if w.creditRegime > 0.0 then dNoiseR * regimeM else dNoiseR
      // THE BUST SWING (see `BustSwing.advance`), repriced here, ahead of the step
      if w.bustAmp > 0.0 then bust.advance(eqM, logVbase, i)
      if w.noiseAsym > 0.0 then
        asymA = NoiseAsymAttack * asymA - (1.0 - NoiseAsymAttack) * z
        asymG = w.noiseAsymPhi * asymG + w.noiseAsym * asymA
      // The session's DIFFUSION SCALE, recorded for the range and satellite channels exactly
      // as the noise term above is built -- news damp, vol state, leverage kick (read
      // BEFORE this session's update, like `dNoise` itself) -- plus the jump branch's
      // sqrt(1 - jumpVar) mixing.  Draw-free; 0.0 when both channels are off.
      val sessSigma =
        if w.rangeScale > 0.0 || w.satBeta > 0.0 || w.overnight > 0.0 || w.basket > 0 ||
           w.macroPanel > 0 || w.volResp > 0.0 || w.jumpResp > 0.0 then
          val levMult = if w.leverage > 0.0 then Math.exp(w.leverage * kickS) else 1.0
          val jvMult  = if w.jumpVar > 0.0 then Math.sqrt(1.0 - w.jumpVar) else 1.0
          newsDamp * SigmaN * Math.exp(logVol - volNorm) * levMult * jvMult * asymM * volRespM * regimeM *
            mix
        else 0.0

      // The jump channel.  Its draws come from `jrng`, NOT `rng`, so `jumpVar = 0` takes the
      // untouched branch below and moves NOTHING ELSE in the path -- the failure mode a shared
      // stream would have caused is not a risk that was reasoned about, it is one the branch
      // removes.  (Through 0.21.0 that also made `-jumpvar 0` reproduce the pre-jump world bit for
      // bit; 0.22.0 changed the price-impact law, so the isolation claim now holds only WITHIN a
      // release.)  `volMult` is this session's volatility state, so jumps CLUSTER
      // inside a stressed stretch instead of scattering uniformly, which is what turns a fat tail
      // into a survivable-or-not sequence for anything levered.
      // THE LEVERAGE CYCLE's multiplier on the spiral's gain, set here rather than beside the step
      // so `liquidity` below is the step's own: it reads the credit stock's growth, which nothing
      // between here and the step moves, so the step is bit-identical wherever this line sits.
      if levOn && w.levGain > 0.0 then
        eqM.levMult = max(MacroK.LevMultFloor, 1.0 + w.levGain * (borrow - levSlow))
      val eqShock =
        if w.jumpVar <= 0.0 then dNoise
        else
          val volMult  = Math.exp(logVol - volNorm)
          val lamJ     = if w.jumpResp > 0.0 then Math.exp(w.jumpResp * volRespS) else 1.0
          val lamNow   = min(0.25, w.jumpRate * Math.pow(volMult, JumpGamma) * lamJ)
          val scale    = jumpScale(w)
          // The compensator is deterministic and consumes no draw: it removes the mean the
          // downward shift would otherwise add, so `jumpVar` moves the tail without moving drift.
          // With `jumpResp` on it must be CONDITIONAL -- the intensity then correlates with past
          // declines, and an unconditional compensator would leave a systematic post-decline
          // return, i.e. manufactured TREND rather than the volatility response asked for
          // (measured: the 60-day variance ratio 1.07 -> 2.65).  Off, `lamJ` is 1 and this is the
          // shipped constant times the volatility state's own factor, which the jump channel has
          // always carried in `lamNow`.
          val compens  = if w.jumpResp > 0.0 then lamNow * w.jumpSkew * scale
                         else w.jumpRate * w.jumpSkew * scale
          val fired    = jrng.nextDouble() < lamNow
          val jump = if fired then jumpDraw(jrng, w.jumpSkew, scale) else 0.0
          jumpNow = jump
          dNoise * Math.sqrt(1.0 - w.jumpVar) + jump + compens
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
      // THE VALUATION CYCLE (see `cycleSd`) advances ahead of the read, like the fundamental, and
      // its move is REPRICED THE SAME SESSION in the price and in perceived fair -- the bust
      // swing's convention: discount-rate news is absorbed at once, so no gap opens for the pull
      // to close and no return autocorrelation is manufactured (tracked through the pull, the
      // cycle failed the variance-ratio profile).  The derived channels see the move as a
      // repricing, like the swing's.
      var cycMove = 0.0
      if w.cycleSd > 0.0 then
        val cycNew = cycPhi * cyc + cycInno * cycRng.randn()
        cycMove = cycNew - cyc
        cyc = cycNew
        eqM.logP += cycMove
        // the drag reads the drawdown of the price without the cycle (see `Market.step`)
        val ex = eqM.logP - cyc
        if ex > cycPeakEx then cycPeakEx = ex
        eqM.dropOverride = cycPeakEx - ex
      val perceivedFair =
        if w.beliefShare <= 0.0 && w.capYears <= 0.0 && w.cycleSd <= 0.0 then logVbase
        else
          // the cycle is the slow component of perceived fair; the beliefs absorb the gap NET of
          // it, so the two do not compound (a belief tracking the whole gap would feed the cycle
          // back into the target at 1 / (1 - beliefShare))
          val fairC = if w.cycleSd > 0.0 then logVbase + cyc else logVbase
          var pf = fairC
          if w.beliefShare > 0.0 then
            belief += beliefMu * ((eqM.logP - fairC) - belief)
            if w.beliefLeak > 0.0 then belief -= leakMu * belief
            pf += w.beliefShare * belief
          if w.capYears > 0.0 then
            // tanh-squashed at CapSpan: extrapolated growth prices a mania, never an infinity --
            // a lucky regime draw must not walk perceived fair past anything the record holds.
            pf += CapSpan * tanhP(w.capYears * (gEwma * DaysPerYear - w.drift) / CapSpan)
          pf + bust.news
      val sPre = if w.leverage > 0.0 then Math.sqrt(eqM.scaleVar) else 0.0
      if levOn then
        // The ratio the index reads: borrowing over the equity securing it, the log drawdown
        // from the running peak -- smoothed, a balance sheet not a tape -- standing in for the
        // equity's fall.  The spiral reads the stock's GROWTH over its trailing year: the
        // record's index rises into every classic peak and credit growth is what predicts the
        // crisis.  A level or the ratio put the gain inside the drawdown, where the spiral
        // already amplifies, and deepened crashes instead of starting them (measured: hazard
        // 1.1-1.3 at kurtosis 50-150; the growth reads 1.6-1.7 at the calibrated 28).
        // the spiral's multiplier was set from the same growth ahead of the flips (see `newsFlip`)
        ddS += MacroK.LevDdK * ((eqM.peak - eqM.logP) - ddS)
        lev = borrow * (1.0 + ddS)
      // THE DAY FLIP (see `newsFlip`): the day's would-be return, reflected when the draw fires;
      // the debt the flips cannot pay at q0 = 1 is a lift
      val stepIn =
        if flipOwed > 0.0 then
          val x0    = eqFlow + eqShockA
          val slope = eqM.liquidity
          val day   = (eqM.logP - logPOpen) + eqM.predict(perceivedFair, x0) - (markdown - markdownPrev)
          val sdDay = newsDamp * SigmaN * newsVolMultiplier(w, logVol, volNorm, kickS, volRespS) * asymM *
            regimeM * slope
          val cap   = FlipGain * sdDay
          if flipOwed > cap then eqM.logP += flipOwed - cap
          val q0    = Math.min(flipOwed / cap, 1.0)
          val zDay  = day / sdDay
          flipOwed = 0.0
          if day < 0.0 && flipRng.nextDouble() < q0 * Math.exp(-0.5 * zDay * zDay) then x0 - 2.0 * day / slope
          else x0
        else eqFlow + eqShockA
      markdownPrev = markdown
      val retE = eqM.step(perceivedFair, stepIn)
      if w.volResp > 0.0 || w.jumpResp > 0.0 then
        // The REALIZED decline, in units of the sd that generated it, saturated at four like the
        // kick's and centred at a normal's E[max(-z,0)] so the state has mean zero and the
        // multiplier does not move the vol LEVEL.  `sessSigma` carries this session's own
        // multiplier, so a stretch the state has already made volatile reads no larger here:
        // scale-free by construction, where a trailing-scale denominator lags ~140 sessions and
        // self-excites.  The numerator is the realized return rather than the shock on purpose:
        // the spiral's amplification is part of what real volatility responds to, and a
        // shock-only driver loses the skew the downside excess is measured from (2.85% -> 1.15%).
        val vrU = min(max(newsJ - retE, 0.0) / max(1e-12, sessSigma), 4.0) - 0.399
        // the ATTACK stage: at 0 the state receives the session's reading whole, which peaks the
        // response at lag 1; above 0 it receives a fast EWMA of it, which is what makes the
        // record's lag-2 hump.
        if w.volRespAttack > 0.0 then
          volRespA = w.volRespAttack * volRespA + (1.0 - w.volRespAttack) * vrU
        val vrIn = if w.volRespAttack > 0.0 then volRespA else vrU
        // SATURATED, and the cap is what makes the accumulation safe rather than a nicety.  The
        // state is unnormalized so that one decline's response is a plateau rather than a divided
        // integral, which means a stretch of saturated readings COMPOUNDS: the realized return
        // carries the spiral's amplification while `sessSigma` does not, so in a thin market the
        // state raises volatility, the spiral amplifies harder, and the reading grows again.  The
        // S&P default is stable without a cap; the Nasdaq recipe at depth 8.4 ran to 49%
        // volatility and 70 crashes a century.  The cap bounds the multiplier at
        // exp(volResp * volRespCap) and leaves the plateau intact below it.
        volRespS = min(w.volRespPhi * volRespS + vrIn, w.volRespCap)
      if levOn then
        // THE CREDIT CYCLE: a damped oscillator in the stock (its velocity persists for a quarter,
        // its level swings over years -- the record's NFCILEVERAGE shape), driven by its own
        // innovation and paid down under the stress the step just read; then the trailing-year
        // average the growth is read against
        levVel += -MacroK.LevDamp * levVel - MacroK.LevSpring * (borrow - MacroK.LevMean) + MacroK.LevSd * lrng.randn()
        borrow  = max(0.0, borrow + levVel - MacroK.LevPaydown * eqM.stressIdx * borrow)
        levSlow += MacroK.LevSlowK * (borrow - levSlow)
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
        val levSig = min(max(newsJ - retE, 0.0) / sPre, 4.0) - 0.399
        // THE PERSISTENT KICK (`levPersist`): the same signal through an EWMA whose weights sum
        // to 1, so the integrated log-multiplier per unit decline is what it is today and only
        // its SHAPE over the following sessions changes -- built over 2-5 sessions by the
        // attack, decaying over ~1/(1-P).  At 0 this is `levSig` itself.
        kickS = w.levPersist * kickS + (1.0 - w.levPersist) * levSig
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
                       max(0.0, 1.0 - bdM.stressIdx)
      if w.refugeDays > 0.0 then settledStress += settleMu * (eqM.stressIdx - settledStress)
      // the news leg's fair (see `newsBond`); the off branch keeps the released expression
      val bondFair  = if w.newsBond > 0.0 then fairB + slowB + newsB else fairB + slowB
      val bondNoise = SigmaNBond * (w.duration / DurationRef) * rng.randn()
      val retB = bdM.step(bondFair, bondFlow + bondNoise)
      val _ = retB

      px(i) = Math.exp(eqM.logP - markdown)
      fv(i) = Math.exp(logVbase - markdown)
      rt(i) = rate
      lq(i) = eqM.lastLiq
      bq(i) = bdM.lastLiq
      bp(i) = Math.exp(bdM.logP)
      ip(i) = inflPress
      logCpi += (piBase + inflPress) * dt
      cp(i) = Math.exp(logCpi)
      if chOn then
        // the bust swing's and the cycle's same-session moves are repricings the derived
        // channels must see, like the news jump
        chIn.record(i, w, eqM, eqM.logP - markdown, sessSigma, Math.exp(logVol - volNorm),
                    volRespM, regimeM, mix, slowVar, jumpNow, newsJ, bust.move, cycMove)
      if mcOn then
        mcIn.record(i, eqM, bdM.stressIdx, Math.exp(logVol - volNorm) * volRespM * regimeM * mix,
                    slowVar, w.depth, acc, wTrend, lev, borrow, rate, inflPress, logCpi, logVbase)

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
      val eT = Math.exp(min(50.0, w.beta * perfT))
      val eV = Math.exp(min(50.0, w.beta * perfV))
      val target = eT / (eT + eV)
      val kNow = kAdapt * (1.0 + w.panic * eqM.stressIdx)   // redemptions fast, subscriptions slow
      wTrend += kNow * (target - wTrend) + kHome * (w.trendShare - wTrend)
      wTrend = max(0.02, min(0.95, wTrend))       // numerical guard; binding is REPORTED
      if i >= BurnIn then
        wTrendSum += wTrend
        if wTrend <= 0.02 + 1e-9 || wTrend >= 0.95 - 1e-9 then pinnedCnt += 1
        if target < 0.02 || target > 0.98 then satCnt += 1
        bondStressSum += bdM.stressIdx
        crowdFlowSum += Math.abs(eqFlow)
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
         disasterCount, bust.ceilDays,
         Array.emptyDoubleArray, Array.emptyDoubleArray, Array.emptyDoubleArray,
         Array.emptyDoubleArray)
    Priced(path, chIn, mcIn)

  // ---- stylised-fact measurements ------------------------------------------------------------
  def dailyReturns(px: Array[Double]): Array[Double] =
    val out = new Array[Double](math.max(px.length - 1, 0))
    var i = 0
    while i < out.length do
      out(i) = math.log(px(i + 1) / px(i))
      i += 1
    out

  /** mean(z^4) / mean(z^2)^2 for z = r - mean(r) -- written as the formula it implements. */
  def kurtosis(r: Array[Double]): Double =
    // ON MatD, NOT A LOOP, here and in `pearson` and `autocorrsAbs`.  MatD folds a contiguous array
    // in eight lanes and up to sixteen parallel chunks, and its `abs` keeps -0.0 where `math.abs`
    // does not, so a loop that looks equivalent moves the last bit and the twins stop agreeing.
    // Only the duplicate work went: `MatD(r)` copies `r`, and this used to make that copy twice.
    val m  = MatD(r)
    val z  = m - m.mean
    val m2 = z.power(2).mean
    if m2 <= 0 then Double.NaN else z.power(4).mean / (m2 * m2)

  /** sum(z_t * z_(t+lag)) / sum(z_t^2) for z = |r| - mean|r| -- volatility clustering. */
  /** THE LEVERAGE-EFFECT PROFILE (item 12): corr(r_t, |r_{t+lag}|) -- how much of the next
    * sessions' volatility a decline predicts.  The signed sibling of `autocorrAbs`, and the
    * statistic `amplifier-2026-09-07.tsv` measures on the record: -0.09 / -0.11 / -0.08 / -0.06
    * at lags 1 / 2 / 5 / 10 on the CRSP century, where a market whose vol responds only to the
    * NEXT session reads its lag-1 value and then nothing. */
  def levAbs(r: Array[Double], lag: Int): Double =
    if r.length <= lag then Double.NaN
    else
      // |r[lag, n)| built in one pass, where `drop` then `map` made two copies of it
      val k = math.max(lag, 0)
      val b = new Array[Double](r.length - k)
      var i = 0
      while i < b.length do
        b(i) = math.abs(r(k + i))
        i += 1
      pearson(java.util.Arrays.copyOf(r, b.length), b)

  def autocorrAbs(r: Array[Double], lag: Int): Double = autocorrsAbs(r, Vector(lag))(0)

  /** `autocorrAbs` at several lags, sharing what does not depend on the lag -- |r|, its centring
    * and the denominator -- which a caller asking for four lags otherwise builds four times. */
  private[apps] def autocorrsAbs(r: Array[Double], lags: Vector[Int]): Vector[Double] =
    val a = MatD(r).abs
    val z = a - a.mean
    val den = z.power(2).sum
    lags.map { lag =>
      if den <= 0 || r.length <= lag then Double.NaN
      else (z(0 until r.length - lag, 0) * z(lag until r.length, 0)).sum / den
    }

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
    // PHASE-AVERAGED over every block offset.  Non-overlapping blocks have to start somewhere, and
    // on a century of daily data that arbitrary choice is worth as much as the statistic: the
    // record's own q = 60 reading spans 1.06 to 1.33 across the 60 possible offsets, q = 250 spans
    // 0.96 to 1.61, and the shipped rows sat at or near the top of that range on three rungs of
    // four.  One observation of phase is what separated two vintages of `persistence-*.tsv`.
    // Averaging over all q offsets removes a free parameter nobody chose deliberately; it costs a
    // factor of q in arithmetic on an O(n) statistic and nothing in interpretation, because each
    // offset estimates the same quantity.
    val len = r.length
    val n   = len / q * q
    if q < 2 || n < 2 * q then Double.NaN
    else
      // PREFIX SUMS, so averaging over all q offsets costs ONE pass rather than q.  Written
      // naively it was q passes per rung, which across the four rungs is 450 passes where the
      // unaveraged form took 4: a measured 1.6x on the whole gate, and the same on every
      // evaluation a search makes.  With running sums of r and r^2 every block sum and every
      // slice's variance is O(1), so the phase average costs what one phase used to.
      val s  = new Array[Double](len + 1)
      val s2 = new Array[Double](len + 1)
      var i = 0
      while i < len do
        s(i + 1)  = s(i) + r(i)
        s2(i + 1) = s2(i) + r(i) * r(i)
        i += 1
      /** Sample variance of `r[a until b]` from the running sums. */
      def sliceVar(a: Int, b: Int): Double =
        val m = b - a
        if m < 2 then Double.NaN
        else
          val mu = (s(b) - s(a)) / m
          ((s2(b) - s2(a)) - m * mu * mu) / (m - 1)
      def at(off: Int): Double =
        val m = (len - off) / q * q
        if m < 2 * q then Double.NaN
        else
          val nb = m / q
          // Two left folds over the block sums, each starting from the FIRST block as
          // `Array.sum` reduces -- no block array, no squared copy.  Not the telescoped
          // `(s(end) - s(off)) / nb`: that is the same number on paper and a different double.
          var bSum = s(off + q) - s(off)
          var k = 1
          while k < nb do
            bSum += s(off + (k + 1) * q) - s(off + k * q)
            k += 1
          val bMu = bSum / nb
          val d0  = (s(off + q) - s(off)) - bMu
          var bSq = d0 * d0
          k = 1
          while k < nb do
            val d = (s(off + (k + 1) * q) - s(off + k * q)) - bMu
            bSq += d * d
            k += 1
          val bVar = bSq / (nb - 1)
          val vDaily = sliceVar(off, off + m)
          // positive test rather than a negated one: a NaN daily variance falls to the NaN arm
          if vDaily > 0.0 then bVar / (q * vDaily) else Double.NaN
      // fixed order, so the twins sum the same doubles in the same sequence
      val vs = (0 until q).toVector.map(at).filter(_.isFinite)
      if vs.isEmpty then Double.NaN else vs.sum / vs.length

  /** cov(a,b) / (sigma_a * sigma_b), in unnormalised sums -- written as the formula. */
  def pearson(a: Array[Double], b: Array[Double]): Double =
    if a.length < 50 then Double.NaN
    else
      val ma = MatD(a)
      val mb = MatD(b)
      val za = ma - ma.mean
      val zb = mb - mb.mean
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
    * `persistence-2026-09-11.tsv`.  `VarRatioQ` is the rung the loss row reads. */
  val VarRatioLadder: Vector[Int] = Vector(20, 60, 120, 250)

  /** The variance-ratio envelopes, from `test-data/equity-anchors/persistence-2026-09-11.tsv`:
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
    Vector((20, 0.70, 1.15), (60, 0.55, 1.20), (120, 0.45, 1.20), (250, 0.45, 1.30))
  /** Adjacent-rung slopes vr(60)-vr(20) and vr(120)-vr(60), the cross-section's range rounded
    * outward: the profile's SHAPE, which four boxes cannot see -- a world at 0.70 and 1.15 on the
    * two short rungs sits inside both boxes and outside every real profile.  Both tightened when
    * the rungs became phase-averaged, the 60->120 slope from -0.30..0.20 to -0.15..0.15: a third
    * of the record's apparent shape variation was block alignment. */
  /** The record's lag-1 signed autocorrelation, quoted in the report: the three CRSP eras, then
    * the modern funds' range.  Not derived from anything here -- these are
    * `persistence-2026-09-11.tsv`'s own `ac1` readings, and `PersistenceAnchorSuite` checks they
    * still are.  A printed claim that no longer follows from the file is worse than no claim,
    * which is the same reason the envelope row carries its own bounds in its name. */
  val RetAc1Record: (Double, Double, Double, Double, Double) =
    (0.0471, 0.0232, -0.0577, -0.1058, -0.0180)

  val VarRatioSlopeBands: Vector[(Int, Int, Double, Double)] =
    Vector((20, 60, -0.20, 0.10), (60, 120, -0.15, 0.15))
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

  /** THE BASKET's readings, medians across paths, at the three levels of `basket-2026-09-02.tsv`.
    * Level 1 per name, pooled over names and paths: vol as a ratio to the primary's, sessions
    * past 10% per year, the share of sessions >20% below the running peak.  Level 2 the
    * equal-weight aggregate against the primary: correlation and beta, and vol ratio.  Level 3:
    * mean pairwise correlation, idio share (1 - R^2 of a name on the aggregate), same-day tail
    * coincidence, and the mechanism -- mean pairwise correlation on the primary's worst decile
    * against its middle decile.  The equal-weight basket is the mean of simple returns, as a log
    * series, the fixture's convention. */
  final case class BasketStats(nameVolRatio: Double, nameGaps: Double, nameD20: Double,
                               aggCorr: Double, aggBeta: Double, aggVolRatio: Double,
                               pairCorr: Double, idioShare: Double, tailCoincidence: Double,
                               pairCorrWorst: Double, pairCorrMid: Double,
                               // the SPREAD of time-below-peak across the names (max - min),
                               // which is what `basketDrift` moves; the eight span 0.53
                               nameD20Spread: Double)

  def basketStats(sims: Vector[Path]): Option[BasketStats] =
    if sims.isEmpty || sims.head.names.isEmpty then None
    else
      val per = parMap(sims) { s =>
        val rp  = dailyReturns(s.price)
        val n   = rp.length
        val rn  = s.names.map(lp => Array.tabulate(n)(t => lp(t + 1) - lp(t)))
        val agg = Array.tabulate(n)(t => math.log(rn.map(r => math.exp(r(t))).sum / rn.size))
        def sdOf(r: Array[Double]) =
          val m = r.sum / r.length
          math.sqrt(r.map(v => (v - m) * (v - m)).sum / (r.length - 1))
        val sdP = sdOf(rp)
        val volRatios = rn.map(r => sdOf(r) / sdP)
        val gaps = rn.map(r => r.count(v => math.abs(v) > 0.10).toDouble / (n / DaysPerYear.toDouble))
        val d20s = s.names.map(lp => depthShares(lp.map(math.exp))._3)
        val mp = rp.sum / n; val ma = agg.sum / n
        var cov = 0.0; var varP = 0.0
        for t <- 0 until n do
          cov += (rp(t) - mp) * (agg(t) - ma); varP += (rp(t) - mp) * (rp(t) - mp)
        val pairs = for a <- rn.indices; b <- rn.indices if a < b yield pearson(rn(a), rn(b))
        val idio = rn.map { r =>
          val ba = pearson(r, agg); 1.0 - ba * ba }
        val cuts = rn.map(r => pctile(r.toVector, 0.01))
        val aggCut = pctile(agg.toVector, 0.01)
        val worstAgg = (0 until n).filter(t => agg(t) <= aggCut)
        val coinc =
          if worstAgg.isEmpty then Double.NaN
          else worstAgg.map(t => rn.indices.count(q => rn(q)(t) < cuts(q)).toDouble / rn.size).sum / worstAgg.size
        val order = (0 until n).sortBy(rp(_))
        def pairCorrOn(idx: IndexedSeq[Int]) =
          val sub = rn.map(r => idx.map(r(_)).toArray)
          val ps = for a <- sub.indices; b <- sub.indices if a < b yield pearson(sub(a), sub(b))
          ps.sum / ps.size
        val dec = n / 10
        (medOf(volRatios), gaps.sum / gaps.size, medOf(d20s),
         pearson(rp, agg), cov / varP, sdOf(agg) / sdP,
         pairs.sum / pairs.size, medOf(idio), coinc,
         pairCorrOn(order.take(dec)), pairCorrOn(order.slice(n / 2 - dec / 2, n / 2 + dec / 2)),
         d20s.max - d20s.min)
      }
      Some(BasketStats(medOf(per.map(_._1)), medOf(per.map(_._2)), medOf(per.map(_._3)),
                       medOf(per.map(_._4)), medOf(per.map(_._5)), medOf(per.map(_._6)),
                       medOf(per.map(_._7)), medOf(per.map(_._8)), medOf(per.map(_._9)),
                       medOf(per.map(_._10)), medOf(per.map(_._11)), medOf(per.map(_._12))))

  /** The open's readings, medians across paths: the overnight share of close-to-close variance
    * (sample variances), and the regression share of the overnight in the session -- sum(o r) /
    * sum(r^2) -- over the worst 1% of sessions and over all of them.  The record's largest declines
    * open with the larger part of the day already gone. */
  final case class OpenStats(overnightShare: Double, worstGapShare: Double, allGapShare: Double)

  def openStats(sims: Vector[Path]): Option[OpenStats] =
    if sims.isEmpty || sims.head.logOpen.isEmpty then None
    else
      val per = parMap(sims) { s =>
        val lp = s.price.map(math.log)
        val n  = lp.length - 1
        val o  = Array.tabulate(n)(t => s.logOpen(t + 1) - lp(t))
        val r  = Array.tabulate(n)(t => lp(t + 1) - lp(t))
        def sv(x: Array[Double]) =
          val m = x.sum / x.length
          x.map(v => (v - m) * (v - m)).sum / (x.length - 1)
        def regShare(idx: IndexedSeq[Int]) =
          var so = 0.0; var sr = 0.0
          for t <- idx do
            so += o(t) * r(t); sr += r(t) * r(t)
          if sr > 0.0 then so / sr else Double.NaN
        val worst = r.indices.sortBy(r(_)).take(math.max(1, n / 100))
        (sv(o) / sv(r), regShare(worst), regShare(r.indices))
      }
      Some(OpenStats(medOf(per.map(_._1)), medOf(per.map(_._2)), medOf(per.map(_._3))))

  /** One macro member's readings on the ruler's statistics (`macro-2026-09-06.tsv`): the level's
    * autocorrelation at 1 and K observations (K = 20 sessions, or 4 weekly readings for the
    * conditions index), its predictive R^2 for the forward 60-session log return, the WARNING
    * SHARE -- over the 20% episodes pooled across paths, the median fraction of the peak-to-trough
    * log decline still ahead when the member first fires in [peak - lookback, trough], 0 if it
    * never fires -- with the share of episodes it fired in at all, the FIRING LAG -- sessions from
    * the peak to that first firing, negative before it, median over the episodes it fired in, the
    * timing statistic that is invariant to how fast the decline runs -- and the level's
    * percentiles.  Medians across paths except the pooled episode statistics. */
  final case class MacroMember(ac1: Double, acK: Double, r2fwd60: Double, warn: Double,
                               warnFired: Double, lag: Double,
                               lag10: Double, fired10: Double, // the same lag and fired share over
                                                               // the 10% episodes: more events
                                                               // behind the timing, REPORTED
                               prePeak: Double,   // THE BUILD-UP, the coupling statistic: the mean
                                                  // trailing rank over the quarter before the peak
                                                  // (the slope: the share inverted), median over
                                                  // the 20% episodes.  A decoupled series reads
                                                  // its unconditional level there
                               lvl10: Double, lvl50: Double, lvl90: Double)

  /** The panel's readings: the members in `MacroK.Columns` order, the slope's inversion
    * share and mean spell length (observations, pooled), the implied-vol member's variance risk
    * premium (mean log ivol - log forward-21-session realized vol) and their R^2, and the pooled
    * 20% episode count the warning shares are medians of. */
  /** A graded statistic's PER-PATH spread across the ensemble: p5 / p50 / p95 of the per-path
    * readings (`pctile`, the twins' upper-middle convention).  The gate grades pooled statistics
    * and a consumer runs one path; this is the width of the null that path sits in, so a report
    * can state it without re-deriving it. */
  final case class Spread(p5: Double, p50: Double, p95: Double)
  def spreadOf(xs: Vector[Double]): Spread =
    val f = xs.filter(x => !x.isNaN)
    if f.isEmpty then Spread(Double.NaN, Double.NaN, Double.NaN)
    else Spread(pctile(f, 0.05), pctile(f, 0.5), pctile(f, 0.95))

  final case class MacroStats(members: Vector[MacroMember], invShare: Double, invDur: Double,
                              vrp: Double, r2rv: Double, episodes: Int,
                              // per-path spreads: each member's forward-return R^2, build-up and
                              // 20% firing lag; the world's slope inversion share, vol premium,
                              // its R^2 against forward realized vol, and the quarter hazard
                              memberSpread: Vector[(Spread, Spread, Spread)],
                              invShareSpread: Spread, vrpSpread: Spread, r2rvSpread: Spread,
                              hazardSpread: Spread,
                              sibling: Boolean,  // the panel is a sibling path's (`-macronull`):
                                                 // the readings are the no-edge level and the
                                                 // rows do not grade them
                              // THE HAZARD: how much likelier a 20% peak is within a quarter /
                              // a year, and a 10% peak within a quarter, when the conditions
                              // index sits in its top decile -- the mechanism's target (record
                              // 2.0-2.7 / ~1.2 / ~1 on the S&P) -- and the unconditional
                              // quarter probability of a 20% peak
                              hazard20q: Double, hazard20y: Double, hazard10q: Double, p20q: Double)

  /** The rank rule a consumer's vote reads: the member's trailing-`win` percentile rank, the
    * share of the window (this reading included) at or below it; NaN until the window fills. */
  def trailingRank(x: Array[Double], win: Int): Array[Double] =
    if win < 1 then trailingRankScan(x, win)
    else
      // A SORTED WINDOW: each reading costs a binary search and one shift instead of a rescan of
      // `win` values, which was a tenth of a search's CPU and a fifth of its single-threaded time.
      // It counts the same thing.  Values enter as `x + 0.0`, which turns -0.0 into 0.0 -- a
      // pair `<=` cannot tell apart anyway -- and NaN sorts last under `Double.compare`, where the
      // count below never reaches it, just as `<=` never counts a NaN.
      val n   = x.length
      val out = new Array[Double](n)
      val buf = new Array[Double](win)
      /** First index in `buf[0, len)` holding a value ABOVE `v` in `Double.compare` order. */
      def above(len: Int, v: Double): Int =
        var lo = 0; var hi = len
        while lo < hi do
          val mid = (lo + hi) >>> 1
          if java.lang.Double.compare(buf(mid), v) > 0 then hi = mid else lo = mid + 1
        lo
      /** First index in `buf[0, len)` holding a value NOT BELOW `v`: where `v` itself sits. */
      def atOrAbove(len: Int, v: Double): Int =
        var lo = 0; var hi = len
        while lo < hi do
          val mid = (lo + hi) >>> 1
          if java.lang.Double.compare(buf(mid), v) >= 0 then hi = mid else lo = mid + 1
        lo
      var i = 0
      while i < n do
        val v = x(i) + 0.0
        // after this step `buf` holds the window ending at i, sorted; `rank` is how many of it
        // compare at or below `v`, which is where `v` landed plus one
        val rank =
          if i < win then
            val p = above(i, v)
            System.arraycopy(buf, p, buf, p + 1, i - p)
            buf(p) = v
            p + 1
          else
            val r = atOrAbove(win, x(i - win) + 0.0)   // the reading leaving the window
            val p = above(win, v)
            if p <= r then
              System.arraycopy(buf, p, buf, p + 1, r - p)
              buf(p) = v
              p + 1
            else
              System.arraycopy(buf, r + 1, buf, r, p - 1 - r)
              buf(p - 1) = v
              p
        out(i) =
          if i < win - 1 then Double.NaN
          else (if v.isNaN then 0 else rank).toDouble / win
        i += 1
      out

  /** The rescan `trailingRank` replaced, kept for a window below 1, where it is the definition. */
  private def trailingRankScan(x: Array[Double], win: Int): Array[Double] =
    Array.tabulate(x.length) { i =>
      if i < win - 1 then Double.NaN
      else
        var c = 0; var k = i - win + 1
        while k <= i do
          if x(k) <= x(i) then c += 1
          k += 1
        c.toDouble / win
    }

  /** Pearson correlation over the finite pairs. */
  def pearsonFinite(x: Array[Double], y: Array[Double]): Double =
    var n = 0; var sx = 0.0; var sy = 0.0
    var i = 0
    while i < x.length do
      if x(i).isFinite && y(i).isFinite then
        n += 1; sx += x(i); sy += y(i)
      i += 1
    if n < 3 then Double.NaN
    else
      val mx = sx / n; val my = sy / n
      var sxx = 0.0; var syy = 0.0; var sxy = 0.0
      i = 0
      while i < x.length do
        if x(i).isFinite && y(i).isFinite then
          val dx = x(i) - mx; val dy = y(i) - my
          sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        i += 1
      if sxx <= 0.0 || syy <= 0.0 then Double.NaN else sxy / math.sqrt(sxx * syy)

  def levelAutocorr(x: Array[Double], k: Int): Double =
    if x.length - k < 3 then Double.NaN else pearsonFinite(x.take(x.length - k), x.drop(k))

  /** R^2 as the squared correlation, r * r rather than pow(r, 2) so the twins print the same digit. */
  def r2Of(x: Array[Double], y: Array[Double]): Double =
    val r = pearsonFinite(x, y)
    r * r

  /** The forward h-session log return from each session; NaN where the path ends first. */
  def fwdReturn(lp: Array[Double], h: Int): Array[Double] =
    Array.tabulate(lp.length)(i => if i + h < lp.length then lp(i + h) - lp(i) else Double.NaN)

  /** Annualized realized volatility in % over the h sessions AFTER each session. */
  def fwdRealizedVol(lp: Array[Double], h: Int): Array[Double] =
    val n  = lp.length
    val r2 = new Array[Double](n)
    var i = 1
    while i < n do
      val d = lp(i) - lp(i - 1)
      r2(i) = r2(i - 1) + d * d
      i += 1
    Array.tabulate(n)(i => if i + h < n then 100.0 * math.sqrt(DaysPerYear * (r2(i + h) - r2(i)) / h)
                           else Double.NaN)

  /** One episode's warning: the share of the log decline still ahead at the first firing (0 if
    * never), and the firing LAG -- sessions from the peak to that firing, negative before it
    * (None if never). */
  final case class Warning(share: Double, lag: Option[Int])

  /** The warnings of one path's episodes: see `MacroMember`. */
  def warnShares(lp: Array[Double], spans: Vector[DdSpan], fired: Array[Boolean], lookback: Int): Vector[Warning] =
    spans.map { s =>
      val base = math.max(s.lo - 1, 0)
      val from = math.max(base - lookback, 0)
      val hit  = Range.inclusive(from, s.trough).find(fired)
      hit match
        case None    => Warning(0.0, None)
        case Some(t) =>
          val tot = lp(base) - lp(s.trough)
          Warning(math.max(0.0, math.min(1.0, (lp(t) - lp(s.trough)) / tot)), Some(t - base))
    }

  /** The rank window: an episode whose peak falls inside the first `RankWindow` sessions has no
    * trailing rank to fire on and is excluded from the warning statistics, on the record and on
    * the model alike. */
  val RankWindow = 252

  /** The BUILD-UP of one path's episodes: the mean of `rank` over the quarter before each
    * episode's peak, [peak - q, peak]. */
  def prePeakRanks(rank: Array[Double], spans: Vector[DdSpan], q: Int = 63): Vector[Double] =
    spans.flatMap { s =>
      val base = math.max(s.lo - 1, 0)
      val w    = rank.slice(math.max(base - q, 0), base + 1).filter(_.isFinite)
      if w.isEmpty then None else Some(w.sum / w.length)
    }

  /** Lengths of the runs of `true`. */
  def runLengths(mask: Array[Boolean]): Vector[Int] =
    val out = Vector.newBuilder[Int]
    var run = 0
    var i = 0
    while i < mask.length do
      if mask(i) then run += 1
      else if run > 0 then
        out += run; run = 0
      i += 1
    if run > 0 then out += run
    out.result()

  def macroStats(sims: Vector[Path]): Option[MacroStats] =
    if sims.isEmpty || sims.head.macroPanel.isEmpty then None
    else
      val WeeklyStride = 5
      val per = parMap(sims) { s =>
        val m     = s.macroPanel.get
        val lp    = s.price.map(math.log)
        val fwd60 = fwdReturn(lp, 60)
        // the 20% episodes the rows grade, and the 10% ones -- more events, mostly not macro
        // ones on a Nasdaq-like world -- whose lag and fired share are reported beside them
        val spans   = ddSpans(s.price, 0.20).filter(_.lo - 1 >= RankWindow)
        val spans10 = ddSpans(s.price, 0.10).filter(_.lo - 1 >= RankWindow)
        def levels(x: Array[Double]) =
          // sorted ONCE for all three quantiles; it was three boxed sorts of the whole series
          val f = finiteSorted(x)
          (pctileOf(f, 0.1), pctileOf(f, 0.5), pctileOf(f, 0.9))
        // a daily rank member (spread, ivol): rank >= 0.90 in the quarter before the peak
        def rankMember(x: Array[Double]) =
          val rk    = trailingRank(x, 252)
          val fired = rk.map(r => r.isFinite && r >= 0.90)
          (levelAutocorr(x, 1), levelAutocorr(x, 20), r2Of(x, fwd60),
           warnShares(lp, spans, fired, 63), warnShares(lp, spans10, fired, 63), levels(x),
           prePeakRanks(rk, spans))
        // the slope: inverted in the 18 months before the peak; its build-up is the share of the
        // quarter before the peak spent inverted
        val slopeM =
          val x     = m.slope
          val fired = x.map(_ < 0.0)
          (levelAutocorr(x, 1), levelAutocorr(x, 20), r2Of(x, fwd60),
           warnShares(lp, spans, fired, 378), warnShares(lp, spans10, fired, 378), levels(x),
           prePeakRanks(fired.map(b => if b then 1.0 else 0.0), spans))
        // the conditions index, READ WEEKLY like its counterpart: the last session of each
        // five, ranked over 52 readings, each reading held until the next
        val condW  = Array.tabulate(m.cond.length / WeeklyStride)(t => m.cond(t * WeeklyStride + WeeklyStride - 1))
        val condAt = Array.tabulate(condW.length)(t => t * WeeklyStride + WeeklyStride - 1)
        val condRk = trailingRank(condW, 52)
        val condFired = new Array[Boolean](lp.length)
        val condHeld  = Array.fill(lp.length)(Double.NaN)   // the weekly rank held to the next reading
        var t = 0
        while t < condW.length do
          val next = if t + 1 < condW.length then condAt(t + 1) else lp.length
          var k = condAt(t)
          while k < next do
            condHeld(k) = condRk(t)
            if condRk(t).isFinite && condRk(t) >= 0.90 then condFired(k) = true
            k += 1
          t += 1
        val condM =
          (levelAutocorr(condW, 1), levelAutocorr(condW, 4), r2Of(condW, condAt.map(fwd60)),
           warnShares(lp, spans, condFired, 63), warnShares(lp, spans10, condFired, 63), levels(condW),
           prePeakRanks(condHeld, spans))
        // THE HAZARD's counts, pooled across paths by the caller: sessions in the index's top
        // decile with a peak inside the next h, over all such sessions, and the same for every
        // session with a finite rank -- the ratio is how much likelier a peak is soon when
        // leverage is high
        def hazardCounts(sp: Vector[DdSpan], h: Int): (Int, Int, Int, Int) =
          val n     = lp.length
          val ahead = new Array[Boolean](n)
          for s <- sp do
            val base = math.max(s.lo - 1, 0)
            var k = math.max(base - h, 0)
            while k < base do
              ahead(k) = true
              k += 1
          var hitTop = 0; var nTop = 0; var hitAll = 0; var nAll = 0
          var u = 0
          while u < n - h do
            if condHeld(u).isFinite then
              nAll += 1
              if ahead(u) then hitAll += 1
              if condHeld(u) >= 0.90 then
                nTop += 1
                if ahead(u) then hitTop += 1
            u += 1
          (hitTop, nTop, hitAll, nAll)
        val hz = Vector(hazardCounts(spans, 63), hazardCounts(spans, 252), hazardCounts(spans10, 63))
        val inv   = m.slope.map(_ < 0.0)
        val rv    = fwdRealizedVol(lp, 21)
        val lIv   = m.ivol.map(math.log)
        val lRv   = rv.map(math.log)
        val diffs = Array.tabulate(lp.length)(i => if lRv(i).isFinite then lIv(i) - lRv(i) else Double.NaN)
        val dOk   = diffs.filter(_.isFinite)
        (Vector(rankMember(m.spread), slopeM, condM, rankMember(m.ivol), rankMember(m.yield10),
                rankMember(m.credit), rankMember(m.policy), rankMember(m.bank),
                rankMember(m.output)),
         inv.count(identity).toDouble / inv.length, runLengths(inv),
         if dOk.isEmpty then Double.NaN else dOk.sum / dOk.length,
         r2Of(lIv, lRv), hz)
      }
      // the hazards, pooled: (sum of hits in the top decile / its sessions) over (the same over
      // every session); NaN where nothing qualified
      def hazardRatio(j: Int): (Double, Double) =
        val (hitTop, nTop, hitAll, nAll) = per.map(_._6(j)).foldLeft((0, 0, 0, 0)) { case ((a, b, c, d), (e, f, g, h)) =>
          (a + e, b + f, c + g, d + h) }
        val pAll = if nAll > 0 then hitAll.toDouble / nAll else Double.NaN
        val pTop = if nTop > 0 then hitTop.toDouble / nTop else Double.NaN
        (if pAll > 0.0 then pTop / pAll else Double.NaN, pAll)
      val members = (0 to 8).toVector.map { j =>
        val ws     = per.flatMap(_._1(j)._4)
        val ws10   = per.flatMap(_._1(j)._5)
        val lags   = ws.flatMap(_.lag).map(_.toDouble)
        val lags10 = ws10.flatMap(_.lag).map(_.toDouble)
        def firedShare(v: Vector[Warning]) =
          if v.isEmpty then Double.NaN else v.count(_.share > 0.0).toDouble / v.size
        MacroMember(medOf(per.map(_._1(j)._1)), medOf(per.map(_._1(j)._2)), medOf(per.map(_._1(j)._3)),
                    pctile(ws.map(_.share), 0.5), firedShare(ws), pctile(lags, 0.5),
                    pctile(lags10, 0.5), firedShare(ws10),
                    pctile(per.flatMap(_._1(j)._7), 0.5),
                    medOf(per.map(_._1(j)._6._1)), medOf(per.map(_._1(j)._6._2)), medOf(per.map(_._1(j)._6._3)))
      }
      val spells = per.flatMap(_._3)
      // the per-path spreads: one reading per path -- its own median over its episodes where the
      // statistic is per episode, its own hazard ratio from its own counts (NaN where a path has
      // no top-decile session or no episode ahead)
      val memberSpread = (0 to 8).toVector.map { j =>
        (spreadOf(per.map(_._1(j)._3)),
         spreadOf(per.map(pp => pctile(pp._1(j)._7, 0.5))),
         spreadOf(per.map(pp => pctile(pp._1(j)._4.flatMap(_.lag).map(_.toDouble), 0.5))))
      }
      val hazardSpread = spreadOf(per.map { pp =>
        val (hitTop, nTop, hitAll, nAll) = pp._6(0)
        if nTop > 0 && nAll > 0 && hitAll > 0 then (hitTop.toDouble / nTop) / (hitAll.toDouble / nAll)
        else Double.NaN
      })
      Some(MacroStats(members, medOf(per.map(_._2)),
                      if spells.isEmpty then Double.NaN else spells.sum.toDouble / spells.size,
                      medOf(per.map(_._4)), medOf(per.map(_._5)),
                      per.map(_._1(0)._4.size).sum,
                      memberSpread, spreadOf(per.map(_._2)), spreadOf(per.map(_._4)),
                      spreadOf(per.map(_._5)), hazardSpread,
                      sims.head.macroPanel.get.sibling,
                      hazardRatio(0)._1, hazardRatio(1)._1, hazardRatio(2)._1, hazardRatio(0)._2))

  final case class WorldStats(vol: Double, kurt: Double, ac1: Double, ac20: Double,
                              // THE TYPICAL YEAR (item 24): the median calendar-year vol, the
                              // row that separates an ordinary year from an episode.  Defaulted
                              // like the profile rows below.
                              yearVol: Double = Double.NaN,
                              // THE WINGS (item 25): the share of sessions the valuation level
                              // spends more than 0.5 log above / below its own 20-year mean
                              // (`wingsOf`); the record's CAPE reads 0.076 / 0.067.
                              wingUp: Double = Double.NaN, wingDown: Double = Double.NaN,
                              // THE STATIONARITY ROW (item 27): the pooled valuation gap's mean
                              // over the paths' later half minus over their first decade
                              // (`gapDriftOf`), in log.  0 for a stationary world.
                              gapDrift: Double = Double.NaN,
                              // THE SPREAD STATIONARITY ROW: the pooled valuation gap's sd over
                              // the paths' first decade over its sd over their later half, minus
                              // 1 (`gapSpreadOf`).  0 for a stationary world; a world whose paths
                              // start at the fundamental reads a first decade with too little
                              // spread.
                              gapSpreadDrift: Double = Double.NaN,
                              // THE VOL-RESPONSE PROFILE (item 12), reported beside the two
                              // graded clustering lags: |r| autocorrelation at 5 and 60, and the
                              // leverage-effect profile at 1, 5 and 20.  Defaulted so a caller
                              // that builds a `WorldStats` by hand need not know about them.
                              ac5: Double = Double.NaN, ac60: Double = Double.NaN,
                              lev1: Double = Double.NaN, lev5: Double = Double.NaN,
                              lev20: Double = Double.NaN,
                              vr20: Double, vr60: Double,   // SIGNED-return persistence at each
                              vr120: Double, vr250: Double, // rung of `VarRatioLadder` -- `varianceRatio`
                              // SIGNED lag-1 autocorrelation, the one horizon the ladder cannot
                              // see: a variance ratio constrains a weighted SUM of the first q-1
                              // autocorrelations, so a world can hold vr60 at 1.0 with a positive
                              // first term paid for by negatives further out, and `ac1` above
                              // reads |r| and is blind to sign.  REPORTED, never graded: the
                              // record's own sign flips by era (CRSP +0.047 over the century,
                              // +0.023 from 1954, -0.058 from 1990, every modern fund negative),
                              // so there is no one value to grade against.  Defaulted, like the
                              // vol-response profile beside it.
                              retAc1: Double = Double.NaN,
                              annRet: Double,
                              macroPanel: Option[MacroStats], // the macro panel's readings when it ran
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
                              upShare: Double,    // median per-path share of moving sessions that
                                                  // rise, in percent (`upShareOf`): the COUNT half
                                                  // of the asymmetry, which `semiExcess` cancels
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
                              open: Option[OpenStats] = None,   // None when no open ran
                              basket: Option[BasketStats] = None, // None when no basket ran
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
    val f = finiteSorted(v.toArray)
    if f.isEmpty then Double.NaN else f(f.length / 2)

  /** The satellite leg's statistics as ratios to the primary's -- `None` when no leg ran, so a
    * satellite-off world produces exactly the rows it always did.
    *
    * Every ratio is a MEDIAN over paths of that path's own ratio, not a ratio of pooled medians:
    * the two differ when the legs' dispersions differ, and the per-path form is the one the
    * record's single history is a draw from. */
  def satStats(sims: Vector[Path]): Option[SatStats] =
    if sims.isEmpty || sims.head.sat.isEmpty then None
    else
      val per = parMap(sims) { s =>
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
      val per = parMap(sims) { s =>
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

  /** Everything `measure` takes a median of from ONE path.  Computed in a single parallel pass per
    * ensemble: as separate passes -- one per statistic, about twenty-five over the same paths --
    * each carried too little work to pay for its own fork and join (the Rust twin measured a 60-path
    * ensemble on 24 cores at only 4.5 times one path at a time).  Every field is the expression
    * `measure` computed per path, so no median moves. */
  private final case class PathRead(
    episodes: Vector[Episode], ddEq: (Double, Double, Double), ddBd: (Double, Double, Double),
    vol: Double, kurt: Double,
    yearVol: Double,         // the typical year: `yearVolOf`
    ac: Vector[Double],      // clustering at lags 1, 20, 5 and 60
    lev: Vector[Double],     // the leverage profile at lags 1, 5 and 20
    vr: Vector[Double],      // variance ratios at q = 20, VarRatioQ, 120 and 250
    retAc1: Double, annRet: Double, divYield: Double,
    bondVol: Vector[Double],
    bondGrowth: Vector[Double], bondInfl: Vector[Double],   // the bond over each episode, by regime
    corrCalm: Double, corrInfl: Double,
    valDisp: Double, maxOver: Double, semiExcess: Double, upShare: Double, levCorr: Double,
    tailHedge: Double,
    wingUp: Double, wingDown: Double, wingN: Double,   // the cycle's wings about its 20-year mean, as COUNTS over `wingsOf`'s sessions
    gapEarly: Double, gapEarlyN: Double, gapLate: Double, gapLateN: Double,   // `gapDriftOf`'s sums and counts
    gapEarly2: Double, gapLate2: Double,   // `gapSpreadOf`'s sums of squares
    inflAnn: Double)

  private def pathRead(sp: Path, years: Int): PathRead =
    val r   = dailyReturns(sp.price)
    val eps = episodes(sp.price, 15.0)   // once per path (was recomputed 3x)
    def bondInWindows(inflRegime: Boolean): Vector[Double] =
      eps.filter { ep =>
        val infl = (ep.peak to ep.trough).map(sp.inflPress).sum / math.max(1, ep.trough - ep.peak + 1)
        (infl > InflRegimeEdge) == inflRegime
      }.map(ep => math.log(sp.bond(ep.trough) / sp.bond(ep.peak)) * 100.0)
    def corrIn(inflRegime: Boolean): Double =
      // the regime's sessions in order, counted then filled, instead of a boxed index sequence
      val n = sp.price.length
      def inRegime(i: Int) = (sp.inflPress(i) > InflRegimeEdge) == inflRegime
      var cnt = 0
      var i = 1
      while i < n do
        if inRegime(i) then cnt += 1
        i += 1
      val a = new Array[Double](cnt)
      val b = new Array[Double](cnt)
      var j = 0
      i = 1
      while i < n do
        if inRegime(i) then
          a(j) = math.log(sp.price(i) / sp.price(i - 1))
          b(j) = math.log(sp.bond(i) / sp.bond(i - 1))
          j += 1
        i += 1
      pearson(a, b)
    val valDisp =
      val len = sp.price.length
      if len == 0 then math.sqrt(0.0 / (len - 1))   // what the empty folds gave
      else
        val g = new Array[Double](len)
        var i = 0
        while i < len do
          g(i) = math.log(sp.price(i) / sp.fundamental(i))
          i += 1
        // left folds from the first element, as `Array.sum` reduces; no squared copy
        var sum = g(0)
        i = 1
        while i < len do
          sum += g(i)
          i += 1
        val m  = sum / len
        var sq = (g(0) - m) * (g(0) - m)
        i = 1
        while i < len do
          sq += (g(i) - m) * (g(i) - m)
          i += 1
        math.sqrt(sq / (len - 1))
    val maxOver =
      var mx = Double.MinValue; var i = 0
      while i < sp.price.length do
        val v = math.log(sp.price(i) / sp.fundamental(i)); if v > mx then mx = v; i += 1
      mx
    val tailHedge =
      // counted then filled, in session order, where a boxed index sequence, two mapped copies and
      // a zip of tuples built the same two series
      val n = sp.price.length
      var cnt = 0
      var i = 1
      while i < n do
        if sp.inflPress(i) <= InflRegimeEdge then cnt += 1
        i += 1
      val re = new Array[Double](cnt)
      val rb = new Array[Double](cnt)
      var j = 0
      i = 1
      while i < n do
        if sp.inflPress(i) <= InflRegimeEdge then
          re(j) = math.log(sp.price(i) / sp.price(i - 1))
          rb(j) = math.log(sp.bond(i) / sp.bond(i - 1))
          j += 1
        i += 1
      val q = pctileOf(finiteSorted(re), 0.10)
      var t = 0
      j = 0
      while j < cnt do
        if re(j) < q then t += 1
        j += 1
      // A tail too small to correlate is unmeasurable, not zero -- the same rule the 24-year bond
      // windows apply.
      if t < 30 then Double.NaN
      else
        val x = new Array[Double](t)
        val y = new Array[Double](t)
        var k = 0
        j = 0
        while j < cnt do
          if re(j) < q then
            x(k) = re(j)
            y(k) = rb(j)
            k += 1
          j += 1
        pearson(x, y)
    val wings = wingsOf(sp.price, sp.fundamental)
    val gd = gapDriftOf(sp.price, sp.fundamental)
    val gs = gapSpreadOf(sp.price, sp.fundamental)
    PathRead(
      episodes = eps, ddEq = depthShares(sp.price), ddBd = depthShares(sp.bond),
      vol  = math.sqrt(MatD(r).power(2).mean * DaysPerYear),
      yearVol = yearVolOf(r),
      kurt = kurtosis(r),
      // the four clustering lags share |r|, its centring and its denominator
      ac   = autocorrsAbs(r, Vector(1, 20, 5, 60)),
      lev  = Vector(levAbs(r, 1), levAbs(r, 5), levAbs(r, 20)),
      vr   = Vector(varianceRatio(r, 20), varianceRatio(r, VarRatioQ), varianceRatio(r, 120),
                    varianceRatio(r, 250)),
      retAc1 = levelAutocorr(r, 1),
      annRet = math.log(sp.price.last / sp.price.head) / years * 100.0,
      divYield = if sp.divYield.isEmpty then Double.NaN else sp.divYield.sum / sp.divYield.length,
      // Median over non-overlapping BondVolYears windows, pooled across paths -- see BondVolYears
      // for why this row alone is windowed.  A path shorter than one window contributes itself, so
      // a short run still reports something rather than nothing.
      bondVol = {
        val rb = dailyReturns(sp.bond)
        val w = BondVolYears * DaysPerYear
        val nw = rb.length / w
        val segs = if nw < 1 then Vector(rb) else (0 until nw).toVector.map(k => rb.slice(k * w, (k + 1) * w))
        segs.map(seg => math.sqrt(MatD(seg).power(2).mean * DaysPerYear))
      },
      bondGrowth = bondInWindows(false), bondInfl = bondInWindows(true),
      corrCalm = corrIn(false), corrInfl = corrIn(true),
      // the path's own returns, through the same functions a record is read with
      valDisp = valDisp, maxOver = maxOver, semiExcess = semiExcessOf(r), upShare = upShareOf(r),
      levCorr = levCorrOf(r),
      wingUp = wings._1, wingDown = wings._2, wingN = wings._3,
      gapEarly = gd._1, gapEarlyN = gd._2, gapLate = gd._3, gapLateN = gd._4,
      gapEarly2 = gs._1, gapLate2 = gs._2,
      tailHedge = tailHedge,
      inflAnn = math.log(sp.cpi.last / sp.cpi.head) / years * 100.0)

  def measure(sims: Vector[Path], years: Int): WorldStats =
    // THE PER-PATH STATISTICS, ACROSS CORES AND IN ONE PASS: `pathRead` computes each path's
    // readings and `parMap` keeps path order, so every median below reads what it always did.
    val per = parMap(sims)(s => pathRead(s, years))
    // `isFinite`, not `!isNaN`: an infinite path is no more a datum than a NaN one, and `pctile`
    // drops the same set, so a median and the percentiles printed beside it describe the same paths.
    def med(v: Seq[Double]) = { val f = finiteSorted(v.toArray); if f.isEmpty then Double.NaN else f(f.length / 2) }
    val eps    = per.flatMap(_.episodes)
    val shapes = eps.map(_.shape).filter(x => !x.isNaN)
    val days   = sims.map(_.price.length.toLong).sum.toDouble
    // POOLED, not a median of per-path shares: most paths hold no tail session at all, so a median
    // would read 0 forever and the check built on it could not fail.
    val tailSessions = sims.map(_.eqTailDays.toLong).sum
    val tailFloorShare =
      if tailSessions <= 0L then 0.0
      else sims.map(_.eqFloorDays.toLong).sum * 100.0 / tailSessions

    WorldStats(
      vol  = med(per.map(_.vol)),
      yearVol = med(per.map(_.yearVol)),
      // POOLED, not a median of per-path shares: a spell past +0.5 is a once-in-decades event,
      // so most paths hold none and a median would read 0 forever (the tail-floor share's rule).
      wingUp = pooledShare(per.map(_.wingUp), per.map(_.wingN)),
      wingDown = pooledShare(per.map(_.wingDown), per.map(_.wingN)),
      gapDrift = pooledShare(per.map(_.gapLate), per.map(_.gapLateN)) -
                 pooledShare(per.map(_.gapEarly), per.map(_.gapEarlyN)),
      gapSpreadDrift = pooledSd(per.map(p => (p.gapEarly, p.gapEarly2, p.gapEarlyN))) /
                       pooledSd(per.map(p => (p.gapLate, p.gapLate2, p.gapLateN))) - 1.0,
      kurt = med(per.map(_.kurt)),
      ac1  = med(per.map(_.ac(0))),
      ac20 = med(per.map(_.ac(1))),
      ac5  = med(per.map(_.ac(2))),
      ac60 = med(per.map(_.ac(3))),
      lev1  = med(per.map(_.lev(0))),
      lev5  = med(per.map(_.lev(1))),
      lev20 = med(per.map(_.lev(2))),
      vr20  = med(per.map(_.vr(0))),
      vr60  = med(per.map(_.vr(1))),
      vr120 = med(per.map(_.vr(2))),
      vr250 = med(per.map(_.vr(3))),
      retAc1 = med(per.map(_.retAc1)),
      annRet = med(per.map(_.annRet)),
      sat = satStats(sims), bars = barStats(sims), open = openStats(sims),
      basket = basketStats(sims), macroPanel = macroStats(sims),
      divYieldMean = med(per.map(_.divYield)),
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
      bondVol = med(per.flatMap(_.bondVol)),
      bondGrowth = med(per.flatMap(_.bondGrowth)), bondInfl = med(per.flatMap(_.bondInfl)),
      corrCalm = med(per.map(_.corrCalm)), corrInfl = med(per.map(_.corrInfl)),
      meanBondStress = sims.map(_.meanBondStress).sum / sims.size,
      pctBondStress = sims.map(_.pctBondStress).sum / sims.size,
      crowdFlow = sims.map(_.meanCrowdFlow).sum / sims.size,
      disPerCentury = sims.map(_.disasters.toDouble).sum / sims.size / years * 100.0,
      valDisp = med(per.map(_.valDisp)),
      maxOver = med(per.map(_.maxOver)),
      semiExcess = med(per.map(_.semiExcess)),
      upShare = med(per.map(_.upShare)),
      levCorr = med(per.map(_.levCorr)),
      tailHedge = med(per.map(_.tailHedge)),
      duration = sims.head.duration,
      inflAnn = med(per.map(_.inflAnn)),
      ddEq5  = med(per.map(_.ddEq._1)), ddEq10 = med(per.map(_.ddEq._2)), ddEq20 = med(per.map(_.ddEq._3)),
      ddBd5  = med(per.map(_.ddBd._1)), ddBd10 = med(per.map(_.ddBd._2)), ddBd20 = med(per.map(_.ddBd._3)))

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

  /** The REALISM bands on statistics a FIDELITY row also targets, as data.  The gate reads them
    * and so does the contract that no edge sits inside a target's own noise -- a test that
    * restated the literals would pass forever after someone moved one here. */
  val RealismVol     = (8.0, 40.0)     // equity vol, % a year
  val RealismKurt    = (4.0, 40.0)     // daily kurtosis
  val RealismAc1     = (0.10, 0.40)    // lag-1 clustering
  val RealismCrashes = (8.0, 55.0)     // 20% declines a century

  /** The (paths, years) the verdict -- gate classes, fidelity table, every emitted sidecar -- is
    * measured on: `GateYears` always, on the larger of the report and `-emitgate` ensembles.
    * `-emitgate 0` is the caller's explicit request to grade the emitted ensemble itself,
    * caller's horizon and all.  Equal to (paths, years) exactly when the report ensemble already
    * is the verdict ensemble -- which at the defaults it is: same seed, same draws.  A row carrying
    * a record band, and every gate class's reading of the same quantity, is read at its record's
    * horizon instead, with these paths (`horizonReadings`, `gateChecksAt`). */
  def verdictSpec(emitting: Boolean, emitGate: Int, paths: Int, years: Int): (Int, Int) =
    if emitting && emitGate == 0 then (paths, years)
    else if emitting && emitGate > paths then (emitGate, GateYears)
    else (paths, GateYears)

  /** TWO-SIDED wherever a plausible range exists.  History of this gate: a one-sided version
    * passed a 35%-volatility world (the one reversing the ranking); a "bonds fail" check written
    * as bondInfl < bondGrowth passed while bonds still RALLIED +2.8; crash frequency shipped
    * without an upper bound WHILE the one-sided lesson was being applied elsewhere in this file. */
  def gateChecks(a: Anchors, st: WorldStats): Vector[(String, Boolean, GateClass)] =
    gateChecksAt(a, st, Map.empty)

  /** `gateChecks` with every quantity the fidelity table also grades read where the table reads
    * it: a row carrying a `RecordBand` at its record's horizon (`horizonReadings`' `banded`, or
    * `bandedOf` a table already built), `st` where `banded` has none.  A report or a sidecar then
    * carries one value per quantity -- its equity vol, kurtosis, clustering and crash rate are the
    * table's in every class.  The loss's gate penalty reads its own ensemble (`gateChecks`). */
  def gateChecksAt(a: Anchors, st: WorldStats,
                   banded: Map[String, Double]): Vector[(String, Boolean, GateClass)] =
    gateChecksWith(a, st, banded, (_, _, _, _) => ())

  /** THE GATES THAT GRADE A TABLE READING (`gateChecksAt`'s `banded`), by the name each prints
    * ahead of its band.  Every other gate reads `st` alone, so it fails at the table's readings
    * exactly when it fails without them, and a caller can reject on one before paying for
    * `horizonReadings`. */
  val TableGates: Vector[String] =
    Vector("equity vol", "kurtosis", "clustering", "crash rate", "typical-year vol", "return per vol")

  /** Does the gate `gateChecksAt` prints under this name read the table (`TableGates`)? */
  def gateReadsTable(gate: String): Boolean = TableGates.exists(p => gate.startsWith(p + " "))

  /** One gate that is a band on one reading, as `gateBandsAt` reports it: its name as
    * `gateChecksAt` prints it, band included, the reading, the open band it passes strictly
    * inside, and its class. */
  final case class GateBand(name: String, reading: Double, band: (Double, Double), cls: GateClass)

  /** Every gate `gateChecksAt` grades as a band on one reading, in gate order, with that reading
    * and band: what a tool stepping a world must hold inside.  A gate that combines readings or
    * counts (`clustering` with its ac20 floor, `crash rate` with its one episode, the mechanism
    * rows) is not here; `gateChecksAt` still grades it. */
  def gateBandsAt(a: Anchors, st: WorldStats, banded: Map[String, Double]): Vector[GateBand] =
    val out = Vector.newBuilder[GateBand]
    gateChecksWith(a, st, banded, (name, reading, band, cls) => out += GateBand(name, reading, band, cls))
    out.result()

  /** `gateChecksAt`, telling `seen` each band gate's name, reading, band and class as it grades it. */
  private def gateChecksWith(a: Anchors, st: WorldStats, banded: Map[String, Double],
                             seen: (String, Double, (Double, Double), GateClass) => Unit)
      : Vector[(String, Boolean, GateClass)] =
    import GateClass.*
    // every band gate below goes through these two, so `seen` hears each in gate order
    def bandCheck(name: String, got: Double, lo: Double, hi: Double, cls: GateClass,
                  dp: Int = 2, unit: String = ""): (String, Boolean, GateClass) =
      val g = MarketSim.bandCheck(name, got, lo, hi, cls, dp, unit)
      seen(g._1, got, (lo, hi), cls)
      g
    def lagCheck(name: String, got: Double, band: (Double, Double)): (String, Boolean, GateClass) =
      val g = MarketSim.lagCheck(name, got, band)
      seen(g._1, got, band, g._3)
      g
    def lv(name: String, verdict: Double): Double = banded.getOrElse(name, verdict)
    val vol = lv("equity vol %", st.vol * 100.0)
    val ac1 = lv("clustering lag 1", st.ac1)
    val ac20 = lv("clustering lag 20", st.ac20)
    val base = Vector(
      // MEASURED, not assumed.  8-25% was the S&P's shape and it asserted of 17 of the 35 real
      // equity instruments in `test-data/equity-anchors` that they are not markets -- QQQ (26.9%),
      // Taiwan, Brazil, semiconductors, energy and most of Europe.  That is the same failure the
      // bond band below already records ("of eight real funds it admitted one").  A REALISM band
      // answers "is this a market at all", so it must admit every market anyone has measured: the
      // 35 instruments span 15.2-37.4% over the clean w1996 window, and 8-40 rounds outward from
      // that.  The FIDELITY band -- now `Anchors.volBand`, 14-18% for the S&P and 22.2-31.5% for
      // the Nasdaq, never narrower than the record's own 5th-95th -- is what answers "is this THIS
      // market", and it stayed narrow.
      bandCheck("equity vol",       vol, RealismVol._1, RealismVol._2, Realism, dp = 0,
                unit = "%"),
      // WIDENED from 4-30 for the same reason as the volatility band above, and it is the same
      // failure: 30 sits two points above the S&P FIDELITY target of 28.0, so the band called
      // the actual S&P century not a market.  `measure` reads kurtosis as a MEDIAN over paths
      // and a single century of it has a relative sd of 0.97, so the median's own spread is
      // 1.9 at the 200-path scoring ensemble and 2.8 at a 60-path search ensemble -- the record
      // failed on roughly a seed in four, which made this one row a THIRD of a calibration
      // search's rejections and biased the surviving set light-tailed.  40 clears 28.0 by three
      // of those spreads, the margin `MarketSimContractSuite` now asserts for every row graded
      // in both classes.  The cross-section would be the better ruler, as it is for volatility,
      // but `test-data/equity-anchors` carries no kurtosis column.  The FIDELITY target is
      // untouched at 28.0 / 9.55: that is the row that answers "is this THIS market".
      bandCheck("kurtosis",         lv("kurtosis", st.kurt), RealismKurt._1, RealismKurt._2, Realism, dp = 0),
      (f"clustering ${RealismAc1._1}%.2f-${RealismAc1._2}%.2f",
        ac1 > RealismAc1._1 && ac1 < RealismAc1._2 && ac20 > 0.03, Realism),
      // Widened from 8-45 for the same reason as the volatility band above: 45 excluded two of
      // the 35 real instruments (EWA, EWW), which read 49.4 and 46.6 over the clean w1996 window
      // against a cross-section range of 13.2-49.4.  A band that calls a real market unreal is
      // not a realism check.
      (f"crash rate ${RealismCrashes._1}%.0f-${RealismCrashes._2}%.0f/century", st.epPerPath >= 1.0 && {
          val pc = lv("crashes/century", st.epPerPath * 100.0 / st.yearsPerPath)
          pc >= RealismCrashes._1 && pc <= RealismCrashes._2 }, Realism),
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
      // THE STATIONARITY ROW (item 27): the paths must be stationary from the first session --
      // the valuation gap's pooled mean may not drift between a path's first decade and its later
      // half.  A world whose slow valuation state starts off its own law (the fair-value start
      // every world had before the cycle) fails it, which is what a mechanism row MEANS.
      ("valuation stationary from the first session",
        !st.gapDrift.isNaN && math.abs(st.gapDrift) < GapDriftBand, Mechanism),
      // and its SPREAD: a gap started at the fundamental has the right mean long before it has the
      // right spread, and every mechanism that reads the gap's level against its own history --
      // the bust swing -- runs quiet until it does
      ("valuation spread stationary from the first session",
        !st.gapSpreadDrift.isNaN && math.abs(st.gapSpreadDrift) < GapSpreadBand, Mechanism),
      bandCheck("inflation",        st.inflAnn, 1.0, 6.0, Realism, dp = 0, unit = "%/yr"),
      // LEVEL bands, not realism.  A 12%-volatility market is still a market, and realism is
      // ALWAYS required — either band placed there would make the sweep's own OFF-worlds
      // inadmissible in every report ("no liquidity spiral" runs at 12.6% vol, "low growth" at
      // 0.34).  Class does not weaken them as a search constraint: the calibration loss counts
      // 0.5 per failed check whatever the class.  Volatility keeps its realism band as well —
      // 8-40% answers "is this a market", the anchor's own band "can its level be read".
      bandCheck("equity vol", vol, a.volBand._1, a.volBand._2, Fidelity, dp = 0, unit = "%"),
      bandCheck("typical-year vol", lv("typical-year vol %", st.yearVol * 100.0), a.yearVolBand._1,
                a.yearVolBand._2, Fidelity, dp = 0, unit = "%"),
      // 0.50 clears the 1926-2026 reading (0.55) downward; 0.85 sits above the 1954-2026 anchor
      // (0.69) and below the most favourable non-overlapping 20-year block the record produced
      // (0.93).  A world may be as favourable as a long-horizon market, not as favourable as its
      // luckiest two decades.  The 20-year block SPREAD (0.47-0.93) is deliberately NOT the band:
      // that is sampling variation in a 20-year window, and this statistic is a population value
      // over 20,000 path-years -- a band drawn from it would readmit worlds at 0.91.
      bandCheck("return per vol",   lv("return per vol", st.retVol), a.retVolBand._1, a.retVolBand._2, Fidelity),
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
    // The equity depth relation is anchor-fitted too, so it refuses outside its anchors' volatility
    // range for the same reason the two below do.  That range starts at 14.3%, so the sweep's own
    // calm off-worlds are disclosed rather than failed -- "no fund this quiet was measured" is not
    // the same finding as "this market's drawdowns are wrong".  Defined in the order the gates
    // print, so `gateBandsAt` hears them in that order too.
    val eqDepthBands =
      if anchored(st.vol * 100.0, EquityVolSupport, st.eqD10VsReal) then
        Vector(bandCheck("equity d5 vs real",  st.eqD5VsReal,  EquityD5Band._1,  EquityD5Band._2,  Fidelity),
               bandCheck("equity d10 vs real", st.eqD10VsReal, EquityD10Band._1, EquityD10Band._2, Fidelity))
      else Vector.empty
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
               // Against the INTRADAY ruler (1.109-1.142): with `overnight` off the bar has no
               // overnight, so the record's close-to-close down/up of 1.175-1.205 carries
               // conditioning it cannot have; with it on the coupling reads the intraday return.
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
    // THE OPEN, graded when it ran: the overnight share against the record's (0.33 SPY / 0.28
    // QQQ, `bars-2026-09-01.tsv`, tol 0.10 like the other bar rows), and the mechanism the
    // record shows -- its worst sessions open with more of the day already gone.
    val openBands = st.open match
      case None => Vector.empty
      case Some(os) =>
        Vector(bandCheck("bar overnight share", os.overnightShare, 0.23, 0.43, Fidelity),
               ("overnight gap share rises on the worst sessions",
                os.worstGapShare > os.allGapShare, Mechanism))
    // THE BASKET, graded when it ran -- `basket-2026-09-02.tsv`, the eight semis under SMH:
    // level 1 as a POPULATION (the names' vol 1.9-3.5x SPY's or 1.5-2.8x QQQ's, gaps 0.4-5.1/yr
    // -- the eight's ranges rounded outward, graded on the pooled median), level 2 the aggregate
    // against the set's primary (the eight's basket on SPY: corr 0.77, beta 1.56, vol 2.0x; on
    // QQQ: 0.84, 1.37, 1.63x; +-0.10 / +-0.25 / +-0.3), level 3 the
    // structure a basket rule reads (pairwise 0.59, idio share 0.37, tail coincidence 0.48), and
    // the mechanism: pairwise correlation on the primary's worst decile above its middle decile
    // (0.60 vs 0.28).  The names' d20 is REPORTED, not graded: the eight's 0.08-0.61 is the time
    // below peak of names selected today as winners (the survivorship the fixture discloses), and
    // a name at the sector's drift and 2.6x the index's volatility spends most of a century more
    // than 20% below its peak, as a real name of that drift would.
    val basketBands = st.basket match
      case None => Vector.empty
      case Some(b) =>
        // level 2 bands from the set's anchor: corr +-0.10 at the row's 0.01 (the anchor carries a
        // third decimal the row does not print), beta +-0.25 and vol ratio +-0.3 rounded outward
        // to 0.1 -- the satellite's tolerances
        def at2(x: Double) = math.round(x * 100) / 100.0
        def out(lo: Double, hi: Double) = (math.floor(lo * 10) / 10, math.ceil(hi * 10) / 10)
        val (betaLo, betaHi) = out(a.basketBeta - 0.25, a.basketBeta + 0.25)
        val (volLo, volHi)   = out(a.basketVolRatio - 0.30, a.basketVolRatio + 0.30)
        Vector(bandCheck("basket name vol ratio", b.nameVolRatio, a.basketNameVolBand._1, a.basketNameVolBand._2, Fidelity),
               bandCheck("basket name gaps/yr", b.nameGaps, 0.40, 5.10, Fidelity),
               bandCheck("basket corr", b.aggCorr, at2(a.basketCorr - 0.10), at2(a.basketCorr + 0.10), Fidelity),
               bandCheck("basket beta", b.aggBeta, betaLo, betaHi, Fidelity),
               bandCheck("basket vol ratio", b.aggVolRatio, volLo, volHi, Fidelity),
               bandCheck("basket pair corr", b.pairCorr, 0.42, 0.86, Fidelity),
               bandCheck("basket idio share", b.idioShare, 0.26, 0.60, Fidelity),
               bandCheck("basket tail coincidence", b.tailCoincidence, 0.35, 0.60, Fidelity),
               ("basket pair corr rises on the worst decile", b.pairCorrWorst > b.pairCorrMid, Mechanism))
    // THE MACRO PANEL, graded when it ran -- `macro-2026-09-06.tsv`.  Mechanism: the conditions
    // index BUILDS before the peak -- its mean trailing rank over the quarter before a 20% peak
    // clear of what a decoupled series reads there (the null panel: 0.49).  This is the coupling
    // test; "fires in most episodes" is not one -- a null panel fires in 0.70-0.77 of them, since
    // a persistent series crosses its top decile somewhere in a quarter-plus-decline window
    // regardless -- so the fired shares are reported.  The LEVEL of that build-up is a fidelity
    // row against the record's span (0.82-0.94 across the references: the leverage index sits in
    // the top decile of its year for most of the quarter before every classic peak), which the
    // model MISSES at ~0.63: its 20% declines are not late-cycle events the way the record's are,
    // and no map of its recorded states reaches the record (the gap 0.65, the crowd share 0.54,
    // the crowd's accumulated P&L 0.55; the run-up itself 0.6-0.7 on record and model alike) --
    // a price-model limit, disclosed.  Fidelity also: the FIRING LAG of the spread and the conditions index,
    // sessions from the peak to the first firing -- the record's conditions index leads the peak
    // by about two months on every reference's classic episodes and its spread trails it by one
    // to three weeks, and the lag is invariant to how fast the decline runs; each member's
    // persistence at K observations (record +-0.08); the ORACLE BOUND (no member predicts the
    // forward 60-session return better than the record's counterparts do, <= 0.02 there); the
    // slope's inversion share -- rate units the model anchors -- and the implied-vol member's
    // variance risk premium.  REPORTED, not graded: every WARNING SHARE (at one and the same lag
    // it reads lower on an index that runs up harder into its peaks and falls less deep, so it
    // grades the index's price dynamics as much as the signal), the spread's and the implied
    // vol's build-up (the record's spread is tight and its VIX calm before a top; the model's
    // vol state is elevated, a CAUSE of its declines where VIX is a response), the implied-vol
    // member's lag, the slope's (never: the model's inversions are regime-length) and the
    // inversion spell length, and the ivol's R^2 against forward realized vol (the
    // unforecastable jump share of the model's realized variance) -- each disclosed in
    // MarketSimWorlds.md.
    val macroBands = st.macroPanel match
      case None => Vector.empty
      // a decoupled panel grades nothing: its readings are the no-edge level, by construction
      case Some(ms) if ms.sibling => Vector.empty
      case Some(ms) =>
        // the oracle bound is the noise-sizing guard for the four measured members; the two
        // draw-free levels (10-year, credit ratio) are reported -- the record's own reach 0.038
        // on the QQQ window, above the bound
        val r2Max = ms.members.take(4).map(_.r2fwd60).max
        Vector(("macro cond builds before the peak: rank above a decoupled series' 0.49",
                ms.members(2).prePeak > MacroBands.CondPrePeakNull, Mechanism),
               (f"macro cond concentrates the big peaks: a 20%% peak within a quarter above ${MacroBands.HazardMin}%.1fx as likely",
                ms.hazard20q > MacroBands.HazardMin, Mechanism),
               bandCheck("macro cond build-up", ms.members(2).prePeak,
                         MacroBands.CondPrePeak._1, MacroBands.CondPrePeak._2, Fidelity),
               lagCheck("macro spread lag", ms.members(0).lag, MacroBands.SpreadLag),
               lagCheck("macro cond lag", ms.members(2).lag, MacroBands.CondLag),
               bandCheck("macro spread persistence", ms.members(0).acK, MacroBands.SpreadAcK._1, MacroBands.SpreadAcK._2, Fidelity),
               bandCheck("macro cond persistence", ms.members(2).acK, MacroBands.CondAcK._1, MacroBands.CondAcK._2, Fidelity),
               bandCheck("macro ivol persistence", ms.members(3).acK, MacroBands.IvolAcK._1, MacroBands.IvolAcK._2, Fidelity),
               bandCheck("macro oracle bound r2", r2Max, -1e-12, MacroBands.OracleR2, Fidelity, 3),
               bandCheck("macro inversion share", ms.invShare, MacroBands.InvShare._1, MacroBands.InvShare._2, Fidelity),
               bandCheck("macro vol premium", ms.vrp, MacroBands.Vrp._1, MacroBands.Vrp._2, Fidelity))
    base ++ eqDepthBands ++ depthBand ++ volBand ++ satBands ++ barBands ++ divBand ++ openBands ++ basketBands ++ macroBands

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

  /** A signed band in sessions, `lo..hi`, since `-84--4` reads as nothing. */
  def lagCheck(name: String, got: Double, band: (Double, Double)): (String, Boolean, GateClass) =
    (f"$name ${band._1}%.0f..${band._2}%.0f sessions", got > band._1 && got < band._2, GateClass.Fidelity)

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
    failedInAt(a, st, Map.empty, cls)

  /** `failedIn` with the table's readings (`gateChecksAt`). */
  def failedInAt(a: Anchors, st: WorldStats, banded: Map[String, Double], cls: GateClass): Vector[String] =
    gateChecksAt(a, st, banded).collect { case (n, false, c) if c == cls => n }

  /** A built table's banded readings, for `gateChecksAt`: each row carrying a record band, at the
    * horizon the table read it. */
  def bandedOf(rows: Vector[FidelityRow]): Map[String, Double] =
    rows.filter(_.recordBand.isDefined).map(r => r.name -> r.model).toMap

  /** Heading and what a failure costs, printed in this order.  Kept beside the enum so a new class
    * cannot be added without saying out loud which conclusions it kills. */
  val GateSections: Vector[(GateClass, String, String)] = Vector(
    (GateClass.Realism,   "realism bands",        "a failure here means this world is not a market"),
    (GateClass.Mechanism, "mechanism engagement", "a failure here means only that mechanism is inert"),
    (GateClass.Fidelity,  "level fidelity",       "a failure here means only that quantity's LEVEL cannot be read"),
  )

  /** Admissibility under the classes a report has declared it requires.  A class not required is a
    * class whose failures are disclosed and tolerated, which is the whole point of the split. */
  def gateOk(a: Anchors, st: WorldStats, banded: Map[String, Double], required: Set[GateClass]): Boolean =
    gateChecksAt(a, st, banded).forall((_, ok, c) => ok || !required.contains(c))

  /** `gateOk` for an ensemble a report holds (`sims`, simulated at `seed`, measured as `st`), graded
    * as the verdict grades it: every quantity the fidelity table reads at its record's horizon
    * (`horizonReadings`), cut from `sims` where that horizon is no longer than `years` and simulated
    * once where it is.  One world then has one admissibility, whichever report asks. */
  def gateOkOf(a: Anchors, st: WorldStats, sims: Vector[Path], years: Int, seed: Long, w: World,
               required: Set[GateClass]): Boolean =
    val banded = horizonReadings(a, st, Some(sims), years, sims.length, seed, w, extremeToo = false).banded
    gateOk(a, st, banded, required)

  /** The historical binary verdict: a market with its mechanisms live.  Level fidelity is NOT in
    * it, so every report keeps the admissibility it had before the depth profile was measured —
    * a consumer that reads levels asks for `fidelity` explicitly. */
  val GateDefault = Set(GateClass.Realism, GateClass.Mechanism)

  /** The classes as a checkpoint records them, in the verdict's printed order, so both twins write
    * the same text whatever order the flag named them in. */
  def gateClassesLabel(classes: Set[GateClass]): String =
    GateClass.values.toVector.filter(classes.contains).map(_.toString.toLowerCase).mkString(",")

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
    * The realism bands are not here either.  `equity vol 8-40%` and `kurtosis 4-40` say "is this a
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
    // THE TYPICAL YEAR (item 24): the equity window's median-year vol averaged over all 252 block
    // phases, `recordbands-2026-09-18.tsv`'s `record` (`yearVolPhaseMean`).  The pooled `vol`
    // cannot tell an ordinary year from an episode; this can, and it is what keeps a search from
    // closing the pooled row by making every year more volatile.  Calendar years, which it read
    // before 0.24.4, are one phase, and on QQQ the one at the bottom of the range.
    yearVol: Double,    yearVolSd: Double,
    retVol: Double,     retVolSd: Double,
    kurt: Double,       kurtSd: Double,
    ac1: Double,        ac1Sd: Double,
    ac20: Double,       ac20Sd: Double,
    crashes: Double,    crashesSd: Double,
    medDepth: Double,   medDepthSd: Double,
    worstDepth: Double, worstDepthSd: Double,
    volBand: (Double, Double),                   // the two asset-specific FIDELITY bands
    yearVolBand: (Double, Double),               // the typical year's: one sd of the row's own
                                                 // single-history spread (0.11 at 72y, 0.18 at 27y)
    retVolBand: (Double, Double),
    // 100*(sdRatio - 1) from `asymmetry-2026-08-31.tsv` -- the raw model/real quotient of sdRatio
    // itself sits so near 1 by construction that no miss could ever fire; the EXCESS is the
    // phenomenon (positive everywhere the record was measured).
    semiExcess: Double, semiExcessSd: Double,
    // THE UP-DAY SHARE, in percent of moving sessions: the record's `upShareOf`, from
    // `recordbands-2026-09-18.tsv`.  REPORTED, NOT GRADED -- its fit target carries weight 0 until a
    // mechanism reaches it; the verdict still judges it against the record's band.
    upShare: Double, upShareSd: Double,
    // corr(r_t, r^2_{t+1}) from the same fixture -- the one leverage statistic that is stable
    // across every CRSP era and all 18 funds on close-only data.
    levCorr: Double, levCorrSd: Double,
    // Left-tail stock-bond correlation from `tailcorr-2026-08-31.tsv` (the equity leg's own pair
    // against TLT).
    tailHedge: Double, tailHedgeSd: Double,
    // THE WINGS (item 25), in percent of sessions: the valuation level's time more than 0.5 log
    // above and below its own 20-year mean, from Shiller's CAPE (`mania-2026-09-15.tsv`, w1901,
    // one series shared by both sets like the dispersion row).  The record is symmetric; the
    // model's cycle had the record's amplitude with the wrong sign (2.5% above, 13.7% below on
    // the Nasdaq recipe) until these rows gave the search a reason to care.  THE SPREAD IS THE
    // RECORD'S OWN, not `-noise`'s: a world that never makes the upper wing reads a spread of
    // 0.07 for it (the default) and one that makes it rarely 0.79 (the recipe) -- neither is a
    // sampling error of the statistic.  A moving-block bootstrap of the record's level series
    // (20-year blocks, 4000 resamples; the fixture's bootSd rows) puts the share's relative sd
    // at 0.60 for both wings -- one spell, 1995-2003, is most of the upper share.
    wingUp: Double, wingUpSd: Double, wingDown: Double, wingDownSd: Double,
    // Sampling spreads for the rows whose LEVEL is not asset-specific -- the theory-valued depth
    // rungs, the 60-session variance ratio, the valuation proxy and the bond rows -- but whose
    // spread is: measured by `-noise` at the set's own world, 200 paths, and frozen like the
    // spreads above.  A spread passed inline to `wgt` weights both sets' rows with one world's
    // reading; the variance ratio's 0.35 did, where the two worlds read 0.28 and 0.24.
    valDispSd: Double, vr60Sd: Double, d5Sd: Double, d10Sd: Double, d20Sd: Double,
    bondVolSd: Double, bondGrowthSd: Double, bondInflSd: Double, bondDepthSd: Double,
    // Drawdown-SHAPE references for `-ddshape`, the first the primary the ratios read against;
    // `ddshape-2026-09-02.tsv`, on the model's own episode definition and median.
    ddRefs: Vector[DdReference],
    // Each `RecordBandRows` row's record, read the model's way, and that record's own sampling
    // spread: what the verdict's `real`, `recordBand`, `recordPercentile` and `miss` read.
    recordBands: Vector[RecordBand],
    // The dividend yield at fair value (%/yr) and the band its level is graded against when the
    // `divYield` dial is on -- `dividend-2026-09-02.tsv`: the window's annual means rounded out.
    divYield: Double, divYieldBand: (Double, Double),
    // THE BASKET's relation to this set's primary -- `basket-2026-09-02.tsv`: the equal-weight
    // eight on SPY / on QQQ (corr, beta, vol ratio), and the eight's vol as a ratio to the
    // primary's, rounded outward.  Level 3 of that fixture is a property of the names among
    // themselves and stays shared.
    basketCorr: Double, basketBeta: Double, basketVolRatio: Double,
    basketNameVolBand: (Double, Double))

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

  /** The S&P set's `RecordBand`s: `recordbands-2026-09-18.tsv`, CRSP total return
    * 1954-2026 and, for the two clustering rows, the century -- each row on the window the
    * set reads it over. */
  val RecordBandsSp500: Vector[RecordBand] = Vector(
    RecordBand("equity vol %", 15.676352,
      Vector(12.225522, 13.554540, 14.098651, 14.418655, 14.645319, 14.839507, 14.996511, 15.139559,
             15.273133, 15.405282, 15.528511, 15.651989, 15.778938, 15.911952, 16.044293, 16.194243,
             16.354227, 16.533317, 16.744801, 17.011904, 17.439124, 18.221814, 20.485377), 0.495075, (13.369892, 18.470317)),
    RecordBand("typical-year vol %", 12.481326,
      Vector(10.430590, 11.275418, 11.662812, 11.876182, 11.959549, 12.042027, 12.115706, 12.197218,
             12.274618, 12.331730, 12.396422, 12.485338, 12.560784, 12.683060, 12.768444, 12.879745,
             12.939449, 13.045397, 13.148558, 13.288156, 13.806844, 14.641141, 15.981653), 0.495075, (11.160791, 14.963517)),
    RecordBand("return per vol", 0.689806,
      Vector(0.091803, 0.357546, 0.449244, 0.498831, 0.533227, 0.558743, 0.582127, 0.602338,
             0.622021, 0.641492, 0.658761, 0.677024, 0.694098, 0.712407, 0.731306, 0.750821,
             0.772914, 0.797044, 0.825311, 0.861199, 0.918055, 1.022803, 1.251306), 0.495075, (0.324442, 1.062731)),
    RecordBand("kurtosis", 21.781759,
      Vector(6.091702, 7.655249, 10.196171, 11.937134, 13.213876, 14.372872, 15.505583, 16.660463,
             17.896602, 18.950341, 19.870825, 20.719651, 21.538490, 22.358080, 23.309753, 24.513472,
             25.808621, 27.297538, 28.948845, 31.075336, 34.379212, 40.993913, 64.166988), 0.495075, (7.260982, 43.722955)),
    RecordBand("clustering lag 1", 0.298940,
      Vector(0.195715, 0.233437, 0.251197, 0.261301, 0.268135, 0.273322, 0.277749, 0.281922,
             0.285360, 0.288922, 0.292196, 0.295562, 0.298809, 0.302274, 0.305731, 0.309295,
             0.313035, 0.317185, 0.322266, 0.328697, 0.337914, 0.355216, 0.395036), 0.495575, (0.224900, 0.362972)),
    RecordBand("clustering lag 20", 0.223792,
      Vector(0.117690, 0.147091, 0.163521, 0.172530, 0.178866, 0.183698, 0.187619, 0.191246,
             0.194761, 0.197856, 0.200982, 0.203894, 0.206830, 0.209742, 0.212829, 0.215849,
             0.219220, 0.222825, 0.227041, 0.232271, 0.239720, 0.253162, 0.284810), 0.495575, (0.140848, 0.259745)),
    RecordBand("variance ratio 60d", 1.007037,
      Vector(0.746797, 0.851681, 0.894355, 0.916336, 0.932559, 0.945317, 0.956854, 0.967279,
             0.977029, 0.986345, 0.995939, 1.004917, 1.014074, 1.023669, 1.034073, 1.044440,
             1.055878, 1.069562, 1.085471, 1.105368, 1.135944, 1.199247, 1.310759), 0.495075, (0.837322, 1.220240)),
    RecordBand("downside vol excess %", 3.067352,
      Vector(-6.469303, -3.149884, -1.413525, -0.501105, 0.119703, 0.646366, 1.090301, 1.497923,
             1.891690, 2.263146, 2.621224, 2.961804, 3.300304, 3.672815, 4.081361, 4.495622,
             4.917591, 5.442710, 6.003845, 6.773708, 7.923641, 10.117275, 17.160698), 0.495075, (-3.717917, 10.998699)),
    RecordBand("up-day share %", 54.982059,
      Vector(52.568777, 53.599426, 54.002429, 54.198895, 54.332928, 54.438066, 54.532406, 54.612832,
             54.685430, 54.754677, 54.825190, 54.893523, 54.960821, 55.031481, 55.100353, 55.175835,
             55.254013, 55.348272, 55.456201, 55.581331, 55.766681, 56.128141, 56.952728), 0.495075, (53.454184, 56.270718)),
    RecordBand("leverage corr", -0.092620,
      Vector(-0.143941, -0.120180, -0.112390, -0.108314, -0.105496, -0.103267, -0.101267, -0.099481,
             -0.097840, -0.096306, -0.094801, -0.093383, -0.091925, -0.090467, -0.088930, -0.087265,
             -0.085550, -0.083659, -0.081498, -0.078808, -0.074682, -0.066495, -0.041726), 0.495075, (-0.122827, -0.062796)),
    RecordBand("crashes/century", 24.862969,
      Vector(8.287656, 15.194036, 17.956588, 19.337865, 20.719141, 20.719141, 22.100417, 22.100417,
             23.481693, 23.481693, 24.862969, 24.862969, 24.862969, 26.244245, 26.244245, 27.625521,
             27.625521, 29.006797, 29.006797, 30.388073, 31.769349, 35.913177, 45.582109), 0.495075, (13.812760, 35.913177)),
    RecordBand("median depth %", -20.795378,
      Vector(-43.522128, -33.114246, -30.256618, -27.715937, -26.819929, -25.785801, -24.953529,
             -24.177300, -23.355401, -22.569043, -22.116532, -21.919901, -21.919901, -21.553083,
             -20.956817, -20.795378, -20.651400, -20.446132, -20.430505, -20.261105, -19.557597,
             -18.661378, -16.070895), 0.495075, (-34.216281, -18.661378)))

  /** The Nasdaq set's `RecordBand`s: `recordbands-2026-09-18.tsv`, QQQ
    * 1999-03-11..2026-08-20. */
  val RecordBandsNasdaq: Vector[RecordBand] = Vector(
    RecordBand("equity vol %", 26.901577,
      Vector(16.978047, 20.483348, 22.226065, 23.163107, 23.809259, 24.323458, 24.768747, 25.187080,
             25.575214, 25.933833, 26.295109, 26.644488, 27.006345, 27.360215, 27.761212, 28.142000,
             28.557865, 29.048477, 29.609975, 30.314636, 31.408602, 33.344443, 38.171744), 0.494225, (20.063767, 33.906038)),
    RecordBand("typical-year vol %", 19.966715,
      Vector(13.846252, 16.530688, 17.397075, 17.811595, 18.258322, 18.684506, 18.960346, 19.291191,
             19.432684, 19.564056, 19.701465, 19.854891, 19.941719, 20.137238, 20.451540, 20.913609,
             21.279249, 21.646618, 22.343644, 22.857755, 23.444980, 24.674651, 34.834227), 0.494225, (16.227852, 25.330411)),
    RecordBand("return per vol", 0.380553,
      Vector(-0.403388, -0.129890, 0.005006, 0.082489, 0.131686, 0.172962, 0.208764, 0.243087,
             0.272005, 0.298493, 0.328610, 0.356969, 0.387892, 0.416830, 0.447033, 0.480692,
             0.515387, 0.552254, 0.597192, 0.653093, 0.741078, 0.899654, 1.220561), 0.494225, (-0.163457, 0.946650)),
    RecordBand("kurtosis", 9.554069,
      Vector(4.711033, 6.775459, 7.594679, 7.971117, 8.252677, 8.487016, 8.688466, 8.854247,
             9.015825, 9.168515, 9.321957, 9.484207, 9.639491, 9.806465, 9.972342, 10.149984,
             10.336813, 10.564895, 10.827960, 11.171723, 11.720816, 12.734690, 16.207453), 0.494225, (6.490642, 13.090064)),
    RecordBand("clustering lag 1", 0.292770,
      Vector(0.105963, 0.200378, 0.232372, 0.246570, 0.255755, 0.262517, 0.268262, 0.272928,
             0.277223, 0.281235, 0.285201, 0.288836, 0.292467, 0.295870, 0.299372, 0.303210,
             0.307276, 0.311634, 0.316689, 0.322959, 0.332084, 0.349405, 0.393203), 0.494225, (0.189180, 0.353353)),
    RecordBand("clustering lag 20", 0.248803,
      Vector(0.046058, 0.124548, 0.160759, 0.177523, 0.187920, 0.195603, 0.201787, 0.207376,
             0.212243, 0.216785, 0.220706, 0.224759, 0.228608, 0.232376, 0.236302, 0.240176,
             0.244481, 0.249020, 0.254205, 0.260078, 0.268727, 0.284755, 0.335159), 0.494225, (0.115236, 0.289004)),
    RecordBand("variance ratio 60d", 0.831558,
      Vector(0.514834, 0.632326, 0.689445, 0.719287, 0.739068, 0.754811, 0.768352, 0.778939,
             0.789056, 0.798778, 0.808300, 0.816910, 0.825511, 0.834846, 0.844108, 0.853960,
             0.864269, 0.875894, 0.889135, 0.906583, 0.931117, 0.982527, 1.086121), 0.494225, (0.614922, 0.993502)),
    RecordBand("downside vol excess %", 1.072223,
      Vector(-8.375022, -3.473217, -1.911703, -1.065323, -0.514075, -0.108740, 0.247915, 0.554157,
             0.835950, 1.092732, 1.361124, 1.615989, 1.863066, 2.119029, 2.363603, 2.629444,
             2.909271, 3.216147, 3.553364, 3.997832, 4.626134, 5.727797, 8.339008), 0.494225, (-3.899970, 5.995217)),
    RecordBand("up-day share %", 54.777163,
      Vector(50.867980, 52.901721, 53.463614, 53.774410, 53.982816, 54.143003, 54.283217, 54.407693,
             54.521625, 54.631518, 54.740061, 54.840588, 54.944574, 55.048812, 55.155316, 55.270821,
             55.395579, 55.526431, 55.678509, 55.874636, 56.166181, 56.691423, 58.066860), 0.494225, (52.713744, 56.831078)),
    RecordBand("leverage corr", -0.107111,
      Vector(-0.195601, -0.165954, -0.148922, -0.139764, -0.133214, -0.128037, -0.123638, -0.119485,
             -0.115678, -0.111994, -0.108432, -0.104824, -0.101165, -0.097354, -0.093575, -0.089341,
             -0.084973, -0.080282, -0.074603, -0.067438, -0.056780, -0.037189, 0.001315), 0.494225, (-0.170229, -0.030831)),
    RecordBand("crashes/century", 25.550406,
      Vector(3.650058, 3.650058, 10.950174, 18.250290, 18.250290, 21.900348, 25.550406, 25.550406,
             29.200463, 29.200463, 32.850521, 32.850521, 32.850521, 36.500579, 36.500579, 40.150637,
             40.150637, 43.800695, 43.800695, 47.450753, 51.100811, 58.400927, 73.001159), 0.494225, (3.650058, 62.050985)),
    RecordBand("median depth %", -22.796671,
      Vector(-98.929999, -79.792922, -49.366815, -36.464957, -32.654551, -28.633866, -28.559349,
             -28.469599, -25.233404, -24.944541, -23.318058, -22.796671, -22.796671, -22.768300,
             -22.768300, -21.764737, -21.285594, -19.542980, -18.285694, -17.266643, -16.104390,
             -15.859033, -15.000029), 0.494225, (-88.691868, -15.609315)))

  /** The S&P/CRSP set.  The LEVELS are the ones every release before 0.21.0 hard-coded, moved
    * rather than re-measured (except the two the 0.22 releases re-anchored -- `medDepth` and
    * `worstDepth` -- each re-derived from a committed fixture).  The SPREADS were re-frozen from
    * `-noise -paths 200` at the adopted 0.24.1 vol-response world, 2026-09-12, as the
    * defaults-change rule requires.  The same command at the outgoing 0.24.0 world reproduces 19
    * of the 20 previous literals exactly, so every move below is the world's; the twentieth, the
    * variance ratio's, had been a constant shared with the Nasdaq set and never measured.
    * `-noise`'s `sd/real` column agrees with the `wt` beside it, which is the whole point of
    * printing them together. */
  val SP500Anchors = Anchors(
    name = "S&P 500 / CRSP",
    equityWindow = "S&P / CRSP 1954-2026", equityYears = 72,
    retVolWindow = "CRSP 1954-2026",
    clusterWindow = "CRSP 1926-2026, the century", clusterYears = 100,
    tailWindow = "CRSP 1926-2026, the century", tailYears = 100,
    vol = 16.0,          volSd = 0.12,
    // CRSP 1954-2026 over all 252 block phases (`recordbands-2026-09-18.tsv`): 12.48, where
    // calendar years read 12.87 (`yearvol-2026-09-15.tsv`, w1954); the S&P index's own daily
    // record is not in the fixture, and CRSP is the series the r/v row reads too.
    yearVol = 12.5,      yearVolSd = 0.11,
    retVol = 0.69,       retVolSd = 0.22,
    kurt = 28.0,         kurtSd = 0.95,
    ac1 = 0.299,         ac1Sd = 0.16,
    ac20 = 0.225,        ac20Sd = 0.21,
    crashes = 20.7,      crashesSd = 0.24,
    medDepth = -21.4,    medDepthSd = 0.17,
    // RE-ANCHORED in 0.22.1, same error class as `median depth %` in 0.22.0: -56.8 was the
    // 2007-09 episode, the worst of the 1954-2026 window, used where the model computes the worst
    // over a whole history.  1954 opens AFTER the crash that set the record's worst, so the anchor
    // graded the tail against a window with the tail removed.  Over the century, on the model's own
    // 15% threshold, the record reads -84.1% (`episodes-2026-08-29.tsv`, w1926) -- the 1929-32
    // decline, which every threshold in that window agrees on because it is one episode.
    // `tailYears` moves to 100 with it, so the percentile is read at the window's own length.
    // Its sd is read at the same 100 years: a 72-year history's spread of the worst decline is
    // not a century's.
    worstDepth = -84.1,  worstDepthSd = 0.21,
    volBand = (14.0, 18.0),
    // the old band's relative width, -12.4% / +12.4%, around the phase-averaged anchor
    yearVolBand = (10.9, 14.1),
    retVolBand = (0.50, 0.85),
    // CRSP c1954 rows of asymmetry-2026-08-31.tsv; the tail hedge is SPY/TLT.  A single 72-year
    // history barely pins the semivariance excess (one crash day swings it), and the record reads
    // as a TYPICAL history of this model on all three rows -- the 51st percentile (semivariance),
    // 39th (leverage corr), 39th (tail hedge).
    semiExcess = 3.06, semiExcessSd = 1.30,
    // CRSP 1954-2026: 54.98% of moving sessions rise
    upShare = 55.0, upShareSd = 0.01,
    levCorr = -0.0926, levCorrSd = 0.43,
    tailHedge = -0.273, tailHedgeSd = 0.37,
    wingUp = 7.6, wingUpSd = 0.60, wingDown = 6.7, wingDownSd = 0.61,
    valDispSd = 0.23, vr60Sd = 0.32, d5Sd = 0.18, d10Sd = 0.47, d20Sd = 2.27,
    bondVolSd = 0.43, bondGrowthSd = 1.58, bondInflSd = 1.53, bondDepthSd = 0.36,
    ddRefs = DdRefsSp500,
    recordBands = RecordBandsSp500,
    divYield = 2.95, divYieldBand = (1.1, 5.8),
    basketCorr = 0.770, basketBeta = 1.557, basketVolRatio = 2.023, basketNameVolBand = (1.9, 3.5))

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
    * THE SAMPLING SPREADS ARE THE NASDAQ WORLD'S OWN, frozen from
    * `-noise -paths 200 -atrelease 0.24.4-nasdaq -anchors nasdaq`, the recipe this set describes;
    * the run is seeded, so the same command reproduces every literal exactly but the wings', which
    * are the record's own.  The recipe's daily shape moved the largest ones: kurtosis 1.93 -> 1.02,
    * the downside excess 4.26 -> 2.87, the leverage correlation 0.53 -> 0.37.  They were first carried
    * over from the S&P, and the assumption that carried values
    * were "approximately right
    * because both assets' statistics have similar relative spreads" was FALSE where the two
    * worlds differ most: `medDepthSd` read 0.10 against a measured 0.37, a 3.7x OVERWEIGHT on
    * the heaviest row in this set's loss (weight is `SdRelRef / sdRel`), and `semiExcessSd`
    * 1.54 against 3.57.  Re-measure whenever the recipe moves; a spread is model-implied, so it
    * belongs to the world, not the index.
    *
    * The two fidelity bands are the S&P bands' proportional widths around the Nasdaq levels
    * (+/-12.5% on volatility, -28%/+23% on return per volatility), for the same reason. */
  val NasdaqAnchors = Anchors(
    name = "Nasdaq-100 / QQQ",
    equityWindow = "QQQ 1999-2026", equityYears = 27,
    retVolWindow = "QQQ 1999-2026",
    clusterWindow = "QQQ 1999-2026", clusterYears = 27,
    tailWindow = "QQQ 1999-2026", tailYears = 27,
    vol = 26.90,         volSd = 0.13,
    // QQQ 1999-2026 over all 252 block phases (`recordbands-2026-09-18.tsv`): 19.97, where calendar
    // years read 18.26 (`yearvol-2026-09-15.tsv`, w1999) -- the bottom of the 18.2-21.5 phase range.
    // Either way it is well under the pooled 26.9: the window's vol is 2000-02 at 58 / 55 / 42%.
    yearVol = 20.0,      yearVolSd = 0.17,
    retVol = 0.38,       retVolSd = 0.48,
    kurt = 9.55,         kurtSd = 1.02,
    ac1 = 0.293,         ac1Sd = 0.20,
    ac20 = 0.249,        ac20Sd = 0.20,
    crashes = 25.6,      crashesSd = 0.50,
    medDepth = -22.8,    medDepthSd = 0.39,
    worstDepth = -83.0,  worstDepthSd = 0.21,
    // QQQ's own 5th-95th over its resamples (22.23-31.41, recordbands-2026-09-18.tsv), rounded
    // outward: a level gate no narrower than what the record's own history produces
    volBand = (22.2, 31.5),
    // one sd of the row's own 27-year spread, +-18%, around the phase-averaged anchor
    yearVolBand = (16.4, 23.6),
    retVolBand = (0.27, 0.47),
    // QQQ wfull row of asymmetry-2026-08-31.tsv; the tail hedge is QQQ/TLT.
    semiExcess = 1.13, semiExcessSd = 2.87,
    // QQQ 1999-2026: 54.78% of moving sessions rise
    upShare = 54.8, upShareSd = 0.02,
    levCorr = -0.1073, levCorrSd = 0.37,
    tailHedge = -0.236, tailHedgeSd = 0.51,
    wingUp = 7.6, wingUpSd = 0.60, wingDown = 6.7, wingDownSd = 0.61,
    // d20's spread is a fraction of the S&P world's (0.53 against 2.27): at Nasdaq volatility the
    // deep rung is pinned where the S&P default leaves it unreadable, so the row carries real
    // weight here.
    valDispSd = 0.32, vr60Sd = 0.29, d5Sd = 0.13, d10Sd = 0.24, d20Sd = 0.53,
    bondVolSd = 0.42, bondGrowthSd = 1.30, bondInflSd = 1.26, bondDepthSd = 0.26,
    ddRefs = DdRefsNasdaq,
    recordBands = RecordBandsNasdaq,
    divYield = 0.78, divYieldBand = (0.3, 1.5),
    basketCorr = 0.837, basketBeta = 1.365, basketVolRatio = 1.630, basketNameVolBand = (1.5, 2.8))

  val AnchorSets: Vector[Anchors] = Vector(SP500Anchors, NasdaqAnchors)

  /** THE MACRO PANEL's bands, US-wide so shared by both sets -- `macro-2026-09-06.tsv`: the
    * FIRING LAG of the spread and the conditions index, +-40 sessions (the episode-to-episode
    * spread of the record's own lags) around the upper-middle of the four references' medians
    * (spread +7 / +7 / +16 / +16, conditions -48 / -44 / -49 / +101 -- QQQ's is a four-episode
    * median with two late firings); the level's autocorrelation at 20 sessions (4 weekly readings
    * for the conditions index) +-0.08 around the record's; the oracle bound above the record's
    * largest predictive R^2 (0.019); the slope's inversion share around the record's 0.115; and
    * the variance risk premium around the record's 0.28-0.29 (log).  `MacroPanelSuite` /
    * `macro_panel_tests` re-derive each from the fixture. */
  /** THE VOL RESPONSE TO A FALL (item 12), REPORTED and not graded -- the `equity d20` precedent
    * for a statistic the model cannot currently reach.  The record's |r| autocorrelation RISES
    * from lag 1 to a hump at lags 2-5 (+0.01 to +0.06 on every reference) and its leverage-effect
    * profile still reads -0.07 to -0.08 at lag 5; the model's profiles peak at lag 1 and are half
    * the record's by lag 5, because every channel that makes volatility here fires on the session
    * after the shock.  `-noiseasym` and `-levpersist` are the two mechanisms that move it, both
    * shipped at 0: what they buy and what they cost is in their field comments and in
    * `MarketSimWorlds.md`.  `amplifier-2026-09-07.tsv` carries the record's profiles. */

  object MacroBands:
    val CondPrePeak = (0.82, 1.00)   // the four references' upper-middle 0.943 less 0.12, to 1
    val CondPrePeakNull = 0.55       // engaged: clear of the null panel's 0.49 (+-0.02 on 500
                                     // episodes); the model read 0.63 before the leverage
                                     // cycle and reads 0.92 with it
    val HazardMin = 1.4              // the quarter-horizon 20% hazard: the four references'
                                     // minimum (QQQ 1.45) floored to 0.1; CRSP 2.7, SPY 2.2, NDX
                                     // 2.0; a decoupled series 1.0
    val SpreadLag = (-24.0, 56.0)
    val CondLag   = (-84.0, -4.0)
    val SpreadAcK = (0.88, 1.00)
    val CondAcK   = (0.90, 1.00)
    val IvolAcK   = (0.69, 0.85)
    val OracleR2  = 0.03
    val InvShare  = (0.05, 0.25)
    val Vrp       = (0.15, 0.40)

  def anchorsNamed(spec: String): Anchors = spec match
    case "sp500" | "sp" | "spx" => SP500Anchors
    case "nasdaq" | "ndx" | "qqq" => NasdaqAnchors
    case other => usage(s"unknown -anchors [$other]; use sp500 or nasdaq")

  def fitTargets(a: Anchors): Vector[(String, WorldStats => Double, Double, Double)] = Vector(
    ("equity vol %",       st => st.vol * 100,                              a.vol,  wgt(1.0, a.volSd)),
    // THE TYPICAL YEAR (item 24): the median calendar-year vol, so the pooled row above cannot be
    // closed by making every year more volatile.  QQQ 1999-2026 reads 18.3 against a pooled 26.9
    // -- the gap is 2000-02 -- where QQQ from 2007, SPY and CRSP from 1954 all read 0.82-0.83 of
    // pooled, as the model does on both worlds (0.81).  The pooled miss that remains on the
    // Nasdaq is the bust's, and only a mania closes it.
    ("typical-year vol %",  st => st.yearVol * 100,                          a.yearVol, wgt(1.0, a.yearVolSd)),
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
    ("variance ratio 60d", st => st.vr60,                                    1.00,  wgt(1.0, a.vr60Sd)),
    // THE THIRD ASYMMETRY AXIS the rows above cannot see: clustering is |r| (sign-blind), vr60 is
    // the signed MEAN's persistence -- this pair is the signed SECOND moment.  Graded as the
    // EXCESS because the raw down/up ratio sits so near 1 that its model/real quotient could
    // never miss.  Anchored on CRSP 1954-2026 (the equity window); the record reads +2.8 to +3.1
    // on every CRSP era and positive on 15 of 18 funds (asymmetry-2026-08-31.tsv).  NO GATE BAND
    // yet -- first-cycle rows, disclosure before enforcement, the d20 precedent.
    ("downside vol excess %", st => st.semiExcess,                     a.semiExcess,  wgt(0.5, a.semiExcessSd)),
    // THE COUNT HALF of the same asymmetry, which the row above cancels by construction: the record
    // rises on 54.8% (QQQ) and 55.0% (CRSP 1954-2026) of its moving sessions, in smaller steps than
    // it falls, and one history pins it -- QQQ's resamples read 53.5 to 56.2.  REPORTED, NOT GRADED:
    // judgment 0, so the loss does not see it, while the verdict judges it against the record's band
    // like every banded row.  The weight moves off 0 when a mechanism reaches the record.
    ("up-day share %",     st => st.upShare,                                a.upShare,  wgt(0.0, a.upShareSd)),
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
    // THE WINGS (item 25): the same cycle's time far above and far below its own 20-year mean.
    // Dispersion alone is sign-blind, and the model reached the record's amplitude with crashes
    // below and no manias above; these two rows are what a mania-capable world has to read.
    ("upper wing months %", st => st.wingUp * 100,                           a.wingUp,   wgt(0.5, a.wingUpSd)),
    ("lower wing months %", st => st.wingDown * 100,                         a.wingDown, wgt(0.5, a.wingDownSd)),
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

  /** The admissible interval for a per-path fidelity ratio on a row WITHOUT a `RecordBand`, and the
    * admissible percentile band for an `ExtremeTargets` row.  Stated ONCE: the report, the sidecar
    * and the tests read the same pair, so a consumer's `miss` and a reader's `<-- MISS` cannot drift
    * apart.  A row with a record band is judged by that band instead: one width for every row
    * flagged the downside excess on every pin, inside a record whose own band spans zero, and
    * passed a lag-1 clustering of 1.23x the record, past its band's 1.14.
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
    * Rust twin in rust/src/market_sim.rs cannot agree with it. */
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
  def pctile(v: Seq[Double], q: Double): Double = pctileOf(finiteSorted(v.toArray), q)

  /** `pctile`'s index rule on a series `finiteSorted` has already prepared, so a caller that wants
    * several quantiles of one series sorts it once. */
  private[apps] def pctileOf(sorted: Array[Double], q: Double): Double =
    if sorted.isEmpty then Double.NaN
    else sorted(math.min((sorted.length * q).toInt, sorted.length - 1))

  /** The finite entries, sorted, as a PRIMITIVE array.  `java.util.Arrays.sort` orders a
    * `double[]` by `Double.compare`, the total order `Ordering[Double]` sorts boxed values by, so
    * every index holds the double the boxed sort put there -- and nothing is boxed.  The boxed
    * sort was a fifth of a search's CPU, nearly all of it on one thread, in the macro panel's
    * per-member quantiles. */
  /** THE TYPICAL YEAR (item 24): the median over whole `DaysPerYear`-session blocks of each
    * block's own annualised RMS vol -- `vol`, read one year at a time -- so the row separates the
    * ordinary year from the episodes the pooled row cannot tell apart.  Whole blocks from the
    * path's start; shorter than a block, the pooled vol.  Sequential sums, so the twins agree bit
    * for bit. */
  private[apps] def yearVolOf(r: Array[Double]): Double =
    val w  = DaysPerYear
    val nb = r.length / w
    if nb < 1 then math.sqrt(MatD(r).power(2).mean * DaysPerYear)
    else
      val v = new Array[Double](nb)
      var k = 0
      while k < nb do
        var s = 0.0
        var i = k * w
        while i < (k + 1) * w do
          s += r(i) * r(i)
          i += 1
        v(k) = math.sqrt(s / w * DaysPerYear)
        k += 1
      val f = finiteSorted(v)
      if f.isEmpty then Double.NaN else f(f.length / 2)

  /** `yearVolOf` averaged over every one of its `DaysPerYear` block phases, in percent.  What a
    * SINGLE record's typical year is: on one series the phase is a free parameter worth as much as
    * the gap it measures -- QQQ's median year reads 18.2 to 21.5 across the 252 phases, and calendar
    * years (18.3) sit at the bottom of that range.  A model ensemble averages phases across its
    * paths already, which is why `yearVolOf` reads a path from its first session. */
  def yearVolPhaseMean(r: Array[Double]): Double =
    val n = math.min(DaysPerYear, r.length)
    var s = 0.0
    var off = 0
    while off < n do
      s += yearVolOf(java.util.Arrays.copyOfRange(r, off, r.length))
      off += 1
    s / n * 100.0

  /** 100*(sqrt(sum r^2 | r<0 / sum r^2 | r>0) - 1): how much more the downside disperses than the
    * upside BY SQUARED RETURN.  The day counts cancel out of the quotient, so a market that rises on
    * more sessions in smaller steps than it falls reads here as symmetric: see `upShareOf`.  One
    * pass, no filtered copies; each sum starts at 0.0 and adds its squares in order, the double
    * `filter.map.sum` reduces to, since a square is never -0.0. */
  private[apps] def semiExcessOf(r: Array[Double]): Double =
    var d = 0.0
    var u = 0.0
    var i = 0
    while i < r.length do
      val x = r(i)
      if x < 0.0 then d += x * x else if x > 0.0 then u += x * x
      i += 1
    if u > 0.0 then (math.sqrt(d / u) - 1.0) * 100.0 else Double.NaN

  /** THE UP-DAY SHARE: rising sessions as a percent of the sessions that moved.  The count half of
    * the return asymmetry, and the half the record pins: QQQ rises on 54.8% of its moving sessions,
    * in smaller steps than it falls, and one-year-block resamples of it read 53.5 to 56.2.
    * Zero-return sessions are excluded rather than counted as falls: a record priced in ticks has
    * some (38 of QQQ's), and a model path has none. */
  private[apps] def upShareOf(r: Array[Double]): Double =
    var up = 0
    var down = 0
    var i = 0
    while i < r.length do
      if r(i) > 0.0 then up += 1 else if r(i) < 0.0 then down += 1
      i += 1
    if up + down == 0 then Double.NaN else up * 100.0 / (up + down)

  /** corr(r_t, r^2_{t+1}): the leverage effect at daily lag. */
  private[apps] def levCorrOf(r: Array[Double]): Double =
    val sq = new Array[Double](math.max(r.length - 1, 0))
    var i = 0
    while i < sq.length do
      sq(i) = r(i + 1) * r(i + 1)
      i += 1
    pearson(java.util.Arrays.copyOf(r, sq.length), sq)

  /** THE RECORD BANDS' rows: every fidelity target whose model reading is a per-path statistic of
    * the equity price alone, so the same function reads a model path, a record and a resample of
    * the record.  In `seriesReadings`' order, which the fixture and `RecordBand` literals follow. */
  val RecordBandRows: Vector[String] = Vector(
    "equity vol %", "typical-year vol %", "return per vol", "kurtosis", "clustering lag 1",
    "clustering lag 20", "variance ratio 60d", "downside vol excess %", "up-day share %",
    "leverage corr", "crashes/century", "median depth %")

  /** The banded rows whose record is the set's CLUSTER window (`Anchors.clusterYears`, the
    * century on the S&P); every other banded row's record is its EQUITY window -- the variance
    * ratio too, whose loss target is read over 25-year fund records while its band is the set's
    * own index (`recordbands-2026-09-18.tsv`). */
  val RecordBandClusterRows: Set[String] = Set("clustering lag 1", "clustering lag 20")

  /** The length in years of the record a row's `RecordBand` was read from: the horizon
    * `horizonReadings` reads the model at, so a band is compared with histories as long as the
    * one it is the spread of. */
  def recordBandYears(a: Anchors, name: String): Int =
    if RecordBandClusterRows.contains(name) then a.clusterYears else a.equityYears

  /** The percentiles a `RecordBand` carries: every 5th, and the 1st and 99th so a reading past the
    * band edge is placed against something steadier than the resamples' extremes. */
  val RecordBandPcts: Vector[Int] =
    Vector(0, 1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 99, 100)

  /** The positions of the band's edges, 5th and 95th, in `RecordBandPcts`. */
  private val RecordBandLo = 2
  private val RecordBandHi = 20

  /** ONE RECORD'S OWN SAMPLING SPREAD for one fidelity row (`recordbands-2026-09-18.tsv`): the
    * record's reading, taken the way the model reads a path, and where that statistic lands over
    * moving one-year-block resamples of the record.  A row carrying one is judged by where the model
    * falls against the record's joint band (`band`), not by the ratio band every other row shares: a ratio band
    * of fixed width is too narrow for a statistic one history barely pins (the downside excess,
    * whose band spans zero) and too wide for one it pins tightly (lag-1 clustering, 0.79 to 1.14).
    * `record` is the record's reading, the typical year's `yearVolPhaseMean`; `q` the resampled
    * readings at `RecordBandPcts`. */
  final case class RecordBand(name: String, record: Double, q: Vector[Double],
                              // the joint band's half-width in rank (`recordBandJoint`), and its
                              // edges: the row's resamples at 1/2 - c and 1/2 + c
                              jointC: Double, joint: (Double, Double)):
    /** THE BAND A READING MISSES OUTSIDE: the joint band, which a world the record cannot tell from
      * its own history clears on every row of its set at once 90% of the time, where each row's own
      * 5th-95th band held the QQQ record's resamples all together 42.7% of the time. */
    def band: (Double, Double) = joint

    /** The 5th to 95th percentile of the record's resamples: the row's own sampling spread, the
      * scale a band term is priced in. */
    def spread: (Double, Double) = (q(RecordBandLo), q(RecordBandHi))

    /** Where a reading falls among the record's resamples, in percent: linear between the carried
      * percentiles, `floor(x + 0.5)`, 0 below the smallest resample and 100 past the largest -- and
      * never on the other side of a band edge from the reading, so a reading just past the joint
      * band's top reads above it rather than rounding back inside the band it missed.  `None` for
      * a reading that is not a number. */
    def percentile(x: Double): Option[Int] =
      if !x.isFinite then None
      else
        val last = q.length - 1
        val p =
          if x < q(0) then 0
          else if x > q(last) then 100
          else
            // the first segment whose top reaches x
            var i = 0
            while x > q(i + 1) do i += 1
            val p0 = RecordBandPcts(i).toDouble
            val p1 = RecordBandPcts(i + 1).toDouble
            val frac = if q(i + 1) > q(i) then (x - q(i)) / (q(i + 1) - q(i)) else 1.0
            math.floor(p0 + (p1 - p0) * frac + 0.5).toInt
        val (lo, hi) = band
        // the whole percentiles inside the joint band: its edges sit at 50 -+ 100 c
        val pLo = math.ceil(100.0 * (0.5 - jointC)).toInt
        val pHi = math.floor(100.0 * (0.5 + jointC)).toInt
        Some(if x > hi then math.max(p, pHi + 1) else if x < lo then math.min(p, math.max(pLo - 1, 0))
             else math.min(math.max(p, pLo), pHi))

    /** Where a reading falls among the record's resamples, in percent, unrounded: linear between
      * the carried percentiles, and past the smallest or largest continued along the nearest
      * segment that is not flat (a discrete statistic ties its extreme resamples), so a reading far
      * outside the band keeps a slope.  What `recordDistances` prices a banded row on; NaN for a
      * reading that is not a number, 0 or 100 past a band with no spread at all. */
    def percentileExact(x: Double): Double =
      def pct(k: Int): Double = RecordBandPcts(k).toDouble
      val last = q.length - 1
      if !x.isFinite then Double.NaN
      else if x > q(last) then
        // the largest carried percentile strictly under the top
        (0 until last).reverse.find(j => q(j) < q(last)) match
          case Some(j) => 100.0 + (x - q(last)) * (100.0 - pct(j)) / (q(last) - q(j))
          case None    => 100.0
      else if x < q(0) then
        (1 to last).find(j => q(j) > q(0)) match
          case Some(j) => -(q(0) - x) * pct(j) / (q(j) - q(0))
          case None    => 0.0
      else
        // the first segment whose top reaches x, as `percentile` reads it
        var i = 0
        while x > q(i + 1) do i += 1
        if q(i + 1) > q(i) then pct(i) + (pct(i + 1) - pct(i)) * (x - q(i)) / (q(i + 1) - q(i))
        else pct(i + 1)

    /** Outside the band, or not a number: the verdict's `miss` for a row that carries one. */
    def misses(x: Double): Boolean =
      val (lo, hi) = band
      !(x >= lo && x <= hi)

  /** Every `RecordBandRows` reading of ONE series of daily log returns, as the model reads it off
    * one path: the functions `pathRead` applies, and the aggregation `measure` and `WorldStats`
    * apply to them, on the price the returns trace.  Two readings are taken directly rather than
    * through the price: the annual return is the returns' own sum, and the price is rebuilt with
    * `expDet`, so the twins' episodes agree to the bit. */
  def seriesReadings(r: Array[Double]): Vector[Double] =
    val years = r.length.toDouble / DaysPerYear
    val vol   = math.sqrt(MatD(r).power(2).mean * DaysPerYear)
    var sum = 0.0
    var i = 0
    while i < r.length do
      sum += r(i)
      i += 1
    val annRet = sum / years * 100.0
    val ac = autocorrsAbs(r, Vector(1, 20))
    val px = new Array[Double](r.length + 1)
    var c = 0.0
    px(0) = expDet(c)
    i = 0
    while i < r.length do
      c += r(i)
      px(i + 1) = expDet(c)
      i += 1
    val eps = episodes(px, 15.0)
    val depths = finiteSorted(eps.map(_.depthPct).toArray)
    Vector(
      vol * 100.0,
      yearVolOf(r) * 100.0,
      if vol <= 0.0 then Double.NaN else annRet / (vol * 100.0),   // `WorldStats.retVol`
      kurtosis(r), ac(0), ac(1), varianceRatio(r, VarRatioQ),
      semiExcessOf(r), upShareOf(r), levCorrOf(r),
      eps.size * 100.0 / years,
      if depths.isEmpty then Double.NaN else depths(depths.length / 2))

  /** The moving-block bootstrap behind a `RecordBand`: `resamples` series of the record's own
    * length, each whole `DaysPerYear`-session blocks of it laid end to end from uniformly drawn
    * starts and cut to length, each read by `seriesReadings`.  Every start is drawn before any
    * series is read, from one `NumPyRNG`, so the readings are the same on any number of cores and
    * in either twin. */
  def recordResamples(r: Array[Double], resamples: Int, seed: Long): Vector[Vector[Double]] =
    val n = r.length
    val l = DaysPerYear
    require(n > l, "a record shorter than one block cannot be resampled in blocks")
    val blocks = (n + l - 1) / l
    val rng = new NumPyRNG(seed)
    val starts = new Array[Array[Int]](resamples)
    var k = 0
    while k < resamples do
      val st = new Array[Int](blocks)
      var b = 0
      while b < blocks do
        st(b) = rng.nextBoundedInt(n - l + 1)
        b += 1
      starts(k) = st
      k += 1
    parMap(starts.toVector) { st =>
      val x = new Array[Double](n)
      var pos = 0
      var b = 0
      while b < st.length && pos < n do
        val len = math.min(l, n - pos)
        System.arraycopy(r, st(b), x, pos, len)
        pos += len
        b += 1
      seriesReadings(x)
    }

  /** One row's resampled readings at `RecordBandPcts`, by `pctile`'s own index rule. */
  def recordBandQuantiles(readings: Seq[Double]): Vector[Double] =
    val s = finiteSorted(readings.toArray)
    RecordBandPcts.map(p => pctileOf(s, p.toDouble / 100.0))

  /** THE JOINT RECORD BAND for the rows one record window is read on (`recordResamples`'
    * readings; `rows` indexes `RecordBandRows`): per resample, the largest distance of any of those
    * rows' mid-ranks from the middle; `c`, that distance's `1 - alpha` quantile by `pctile`'s rule;
    * and each row's resamples at 1/2 - c and 1/2 + c.  A world the record cannot tell from its own
    * history then sits inside every row's band at once with probability 1 - alpha, where each
    * row's own 5th-95th band holds it with 0.9 alone: twelve such bands held the QQQ record's
    * resamples together 42.7% of the time.  Tied readings share their run's middle rank, so a
    * discrete statistic's ties do not decide the band.  Returns `c` and each row's `(lo, hi)`, in
    * `rows` order.  Local arrays, the Rust twin's operation order. */
  def recordBandJoint(reads: Vector[Vector[Double]], rows: Vector[Int],
                      alpha: Double): (Double, Vector[(Double, Double)]) =
    val n = reads.length
    val nf = n.toDouble
    val dmax = new Array[Double](n)
    val cols = rows.map: k =>
      val col = reads.map(_(k)).toArray
      // a stable sort on Double.compare: every NaN -- a resample the row cannot be read on --
      // ranks above every number and ties the others (the Rust twin's java_double_compare)
      val order = (0 until n).sortWith((a, b) => java.lang.Double.compare(col(a), col(b)) < 0).toArray
      def same(a: Double, b: Double): Boolean = a == b || (a.isNaN && b.isNaN)
      var s = 0
      while s < n do
        var e = s + 1
        while e < n && same(col(order(e)), col(order(s))) do e += 1
        val mid = (s + e - 1).toDouble / 2.0
        val d = math.abs((mid + 0.5) / nf - 0.5)
        var j = s
        while j < e do
          if d > dmax(order(j)) then dmax(order(j)) = d
          j += 1
        s = e
      finiteSorted(col)
    val c = pctileOf(finiteSorted(dmax), 1.0 - alpha)
    (c, cols.map(s => (pctileOf(s, 0.5 - c), pctileOf(s, 0.5 + c))))

  /** THE WINGS (item 25): the share of sessions the valuation level -- log(price / fundamental)
    * minus its own EWMA with `BustMeanYears`' time constant, started at the first reading, the
    * bust swing's reference -- spends more than 0.5 above and more than 0.5 below its mean, after
    * the reference's first `BustMeanYears`.  The record's CAPE about the same reference
    * (`mania-2026-09-15.tsv`, w1901) reads 0.076 / 0.067: symmetric.  Returned as COUNTS over the
    * sessions read, (0, 0, 0) on a path shorter than the burn-in, so `measure` can pool them. */
  private[apps] def wingsOf(price: Array[Double], fund: Array[Double]): (Double, Double, Double) =
    val n = price.length
    val burn = (BustMeanYears * DaysPerYear).toInt
    if n <= burn then (0.0, 0.0, 0.0)
    else
      val mu = 1.0 / (BustMeanYears * DaysPerYear)
      var ref = math.log(price(0) / fund(0))
      var up = 0
      var down = 0
      var i = 0
      while i < n do
        val g = math.log(price(i) / fund(i))
        if i >= burn then
          val lvl = g - ref
          if lvl > 0.5 then up += 1
          else if lvl < -0.5 then down += 1
        ref += mu * (g - ref)
        i += 1
      (up.toDouble, down.toDouble, (n - burn).toDouble)

  /** THE STATIONARITY ROW's reading (item 27): the valuation gap log(price / fundamental) summed
    * over a path's first decade and over its later half -- sessions [0, 10y) and [40y, n), or
    * the two halves of a path under 50 years -- with the session counts, so `measure` can pool them and read the
    * drift between the two.  Every path used to start at the fundamental and drift under it for
    * decades; a world whose paths are stationary from the first session reads 0 here. */
  private[apps] def gapDriftOf(price: Array[Double], fund: Array[Double]): (Double, Double, Double, Double) =
    val n = price.length
    val (earlyEnd, lateFrom) = gapWindows(n)
    var e = 0.0
    var l = 0.0
    var i = 0
    while i < n do
      val g = math.log(price(i) / fund(i))
      if i < earlyEnd then e += g
      else if i >= lateFrom then l += g
      i += 1
    (e, earlyEnd.toDouble, l, (n - lateFrom).toDouble)

  /** The stationarity rows' two windows for a path of `n` sessions: the first decade ends, and the
    * later half starts, at (10y, 40y); a path shorter than 50 years reads its first half against
    * its second. */
  private[apps] def gapWindows(n: Int): (Int, Int) =
    if n >= 50 * DaysPerYear then (10 * DaysPerYear, 40 * DaysPerYear) else (n / 2, n / 2)

  /** THE SPREAD STATIONARITY ROW's reading: the valuation gap's squares summed over the same two
    * windows as `gapDriftOf`, so `measure` can pool each window's spread.  A path started at the
    * fundamental has no spread in its first decade, and the gap takes about 20 years to reach its
    * own; the mean alone cannot see that (see `GapSpreadBand`). */
  private[apps] def gapSpreadOf(price: Array[Double], fund: Array[Double]): (Double, Double) =
    val n = price.length
    val (earlyEnd, lateFrom) = gapWindows(n)
    var e2 = 0.0
    var l2 = 0.0
    var i = 0
    while i < n do
      val g = math.log(price(i) / fund(i))
      if i < earlyEnd then e2 += g * g
      else if i >= lateFrom then l2 += g * g
      i += 1
    (e2, l2)

  /** A spread pooled over paths from each path's (sum, sum of squares, count): sqrt(E[g^2] -
    * E[g]^2) over every session of every path, NaN when nothing was read.  Left folds in path
    * order, like every pooled sum here. */
  private[apps] def pooledSd(parts: Seq[(Double, Double, Double)]): Double =
    var s1 = 0.0
    var s2 = 0.0
    var n = 0.0
    var i = 0
    while i < parts.length do
      s1 += parts(i)._1
      s2 += parts(i)._2
      n += parts(i)._3
      i += 1
    if n > 0.0 then
      val m = s1 / n
      math.sqrt(s2 / n - m * m)
    else Double.NaN

  /** A share pooled over paths: the counts summed over the sessions summed, NaN when nothing was
    * read.  Left folds in path order, like every pooled sum here. */
  private[apps] def pooledShare(counts: Seq[Double], sessions: Seq[Double]): Double =
    var c = 0.0
    var n = 0.0
    var i = 0
    while i < counts.length do
      c += counts(i)
      n += sessions(i)
      i += 1
    if n > 0.0 then c / n else Double.NaN

  private[apps] def finiteSorted(v: Array[Double]): Array[Double] =
    val out = new Array[Double](v.length)
    var n = 0
    var i = 0
    while i < v.length do
      if v(i).isFinite then
        out(n) = v(i)
        n += 1
      i += 1
    val f = if n == out.length then out else java.util.Arrays.copyOf(out, n)
    java.util.Arrays.sort(f)
    f

  /** `xs.map(f)` across cores, in order.  For the per-path statistics: each reading is a pure
    * function of its own path, so which core computes it cannot move a bit, and the result keeps
    * index order.  They were the SINGLE-THREADED half of every evaluation -- 42% of a search's
    * CPU samples sat on the main thread while the price loops ran on all of the others. */
  private[apps] def parMap[A, B](xs: Vector[A])(f: A => B): Vector[B] =
    java.util.stream.IntStream.range(0, xs.length).parallel()
      .mapToObj(i => f(xs(i)).asInstanceOf[AnyRef]).toArray()
      .toVector.map(_.asInstanceOf[B])

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

  /** THE SEARCHED DIALS STEPPED AND MEASURED IN LOG COORDINATES, ln(1 + x), by the calibration
    * search and the reachability check alike.  A news rate's range runs 0-30 a year while the
    * recipes sit at 1-16, and at the search's default sigma a step a fixed share of that range is
    * 2/yr wide: at a recipe near 1.4 a quarter of the children switched the channel off.  In
    * ln(1 + rate) a step is a share of the rate itself -- one sd spans 0.9-2.1 at 1.4 and 12-21 at
    * 16 -- and 0 stays reachable.  The credit regime's onset rate (0-30) and the spiral's credit
    * gain (0-15, recipes at 2-9) have the same shape. */
  val SearchLogDials: Vector[String] = Vector("newsRate", "creditRegimeRate", "levGain")

  /** A searched dial's value in the coordinates a search steps it in (`SearchLogDials`), through
    * `lnDet`, so the twins agree to the bit. */
  def searchCoord(dial: String, x: Double): Double =
    if SearchLogDials.contains(dial) then lnDet(1.0 + x) else x

  /** `searchCoord`'s inverse, through `expDet`. */
  def fromSearchCoord(dial: String, y: Double): Double =
    if SearchLogDials.contains(dial) then expDet(y) - 1.0 else y

  /** What `-calibrate` samples, and the ONLY place a searchable parameter is declared.  Named
    * rather than inline so the identity-parameter rule above can be tested against it.
    *
    * EVERY BOUND CONTAINS EVERY FROZEN WORLD, and `MarketSimContractSuite` asserts it.  Four did
    * not: `margin` shipped at 0.006 from 0.19.1 onward against a ceiling of 0.004, so for eleven
    * releases the search could not propose the value the model itself uses and every
    * candidate-vs-default line it printed was across a boundary the candidate could not cross;
    * `depth` 8.4 (the 0.24.0 Nasdaq recipes) sat under a floor of 10.0, `jumpRate` 0.005 (0.22.x)
    * and 0 (through 0.20.0) outside 0.0004 .. 0.004, `volOfVol` 0.011 (0.19.x) under 0.012.  This
    * is the `fundVol` failure the note below already records, and the test is what stops it
    * recurring: a range that excludes a shipped world is a search that cannot reach the model.
    * Bounds are rounded OUTWARD past the extreme so a value at the edge can still be explored. */
  /** One searchable dial: its name, its bounds, the setter the search writes through and the
    * getter the containment test reads back. */
  type DialRange = (String, Double, Double, (World, Double) => World, World => Double)

  /** THE ORDER IS A CROSS-TWIN CONTRACT, restated here so a reorder fails a build rather than a
    * diff.  `-calibrate` draws one uniform per dial from a single stream in table order, so a
    * permutation hands every draw to a different dial: the twins sampled different worlds from
    * the same seed for as long as their tables disagreed, and the loss gap that showed up
    * downstream read like a rounding divergence in the scoring path.  It was this.  A search
    * archive's columns are in this order too, so the order is also the archive's format.  Same
    * shape as `EmitSchema` / `EMIT_SCHEMA`: the literal is in the model, checked by each twin's
    * own contract test, and changing one twin without the other cannot pass. */
  val CalibrateDialOrder: Vector[String] = Vector(
    "depth", "trendShare", "drift", "fundVol", "crowdImpact", "stress", "valuePull",
    "recoveryDrag", "recoveryFloor", "disasterRate", "disasterSize", "disasterRecover",
    "beliefShare", "capYears", "volOfVol", "jumpVar", "jumpRate", "leverage", "downShock",
    "jumpSkew", "newsRate", "newsSize", "refugeDays", "easing", "refuge", "inflSize",
    "discount", "margin", "slowShare", "slowVol", "slowBeta", "slowPerm", "beliefYears", "bustAmp",
    "cycleSd", "cycleYears", "beliefLeak", "newsLev", "newsRevert", "newsScale", "newsBond",
    "noiseSkew", "newsFlip", "newsBondSkip", "creditRegime", "creditRegimeRate", "slowBondInfl",
    "levGain")

  val CalibrateRanges: Vector[DialRange] = Vector(
    ("depth",       8.0,  26.0, (w, x) => w.copy(depth = x), _.depth),
    ("trendShare",  0.05,  0.70, (w, x) => w.copy(trendShare = x), _.trendShare),
    ("drift",       0.06,  0.16, (w, x) => w.copy(drift = x), _.drift),
    // The depth profile's second axis, and the one no sweep could reach before 0.21: the value
    // channel passes only a few percent of a fundamental move into any one session, so fundamental
    // variance accumulates into time under water without moving daily return scale.  It is in the
    // search only now that the depth targets are stated against a real relation -- against SPY's
    // absolute levels a search free to raise it would have closed them by making the fundamental
    // hotter still, which is how the world it replaces was reached.
    ("fundVol",     0.03,  0.16, (w, x) => w.copy(fundVol = x), _.fundVol),
    ("crowdImpact", 0.01,  0.20, (w, x) => w.copy(crowdImpact = x), _.crowdImpact),
    ("stress",       2.0,   6.0, (w, x) => w.copy(stress = x), _.stress),
    // Widened from 0.010-0.035 in 0.21.0: with the recovery drag the base pull governs SHALLOW
    // water only, so its useful range moved up.  The old ceiling would have excluded the shipped
    // value, which is the `fundVol` failure mode -- a search that cannot reach the answer.
    ("valuePull",  0.010, 0.070, (w, x) => w.copy(valuePull = x), _.valuePull),
    // Both in the ranges from the release they arrive in, for the same reason.
    ("recoveryDrag",  0.0, 20.0, (w, x) => w.copy(recoveryDrag = x), _.recoveryDrag),
    ("recoveryFloor", 0.05, 1.0, (w, x) => w.copy(recoveryFloor = x), _.recoveryFloor),
    ("disasterRate",  0.0, 1.5, (w, x) => w.copy(disasterRate = x), _.disasterRate),
    ("disasterSize",  0.5, 2.5, (w, x) => w.copy(disasterSize = x), _.disasterSize),
    ("disasterRecover", 0.0, 0.9, (w, x) => w.copy(disasterRecover = x), _.disasterRecover),
    ("beliefShare",   0.0, 0.97, (w, x) => w.copy(beliefShare = x), _.beliefShare),
    ("capYears",      0.0, 8.0, (w, x) => w.copy(capYears = x), _.capYears),
    ("volOfVol",   0.010, 0.030, (w, x) => w.copy(volOfVol = x), _.volOfVol),
    // In the ranges from the release it arrived in.  `fundVol` sat outside them for four releases
    // and that is exactly why its defect survived four releases of one-knob-at-a-time sweeps; a
    // mechanism the search cannot reach is a mechanism nobody will find the wrong value of.
    ("jumpVar",     0.00,  0.20, (w, x) => w.copy(jumpVar = x), _.jumpVar),
    ("jumpRate",  0.0, 0.006, (w, x) => w.copy(jumpRate = x), _.jumpRate),
    // The asymmetry pair and the jump shift, in the ranges the hand sweeps mapped: leverage
    // reaches the `leverage corr` anchor near 0.10 under the saturation cap, downShock pays vr60
    // ~+0.02 per 0.01 so the band bounds it near 0.03, and the best hand candidate
    // (0.10 / 0.015 / jumpVar 0.12 / drift 0.124) missed a four-seed gate PASS only on
    // `bond depth vs vol` -- the search has the bond dials in its hands where a hand sweep does
    // not.
    ("leverage",    0.00,  0.15, (w, x) => w.copy(leverage = x), _.leverage),
    ("downShock",   0.00,  0.05, (w, x) => w.copy(downShock = x), _.downShock),
    ("jumpSkew",    0.00,  1.40, (w, x) => w.copy(jumpSkew = x), _.jumpSkew),
    ("newsRate",    0.00, 30.00, (w, x) => w.copy(newsRate = x), _.newsRate),
    ("newsSize",    0.00,  0.05, (w, x) => w.copy(newsSize = x), _.newsSize),
    ("refugeDays",  0.00,  3.00, (w, x) => w.copy(refugeDays = x), _.refugeDays),
    ("easing",       0.0,  0.09, (w, x) => w.copy(easing = x), _.easing),
    ("refuge",       0.0,  0.20, (w, x) => w.copy(refuge = x), _.refuge),
    ("inflSize",    0.03,  0.12, (w, x) => w.copy(inflSize = x), _.inflSize),
    ("discount",     3.0,  10.0, (w, x) => w.copy(discount = x), _.discount),
    ("margin",       0.0, 0.008, (w, x) => w.copy(margin = x), _.margin),
    // THE SLOW REPRICING CHANNEL's share and scale, searched from 0.24.2: the channel is what
    // carries the |r| autocorrelation past lag 20 (the S&P reads 0.117 at lag 60 with it and the
    // Nasdaq recipe, which runs it at 0, reads 0.036 against a record of 0.175), and no searched
    // dial could reach that row -- measured on a 113-member archive, the one row no member
    // reached.  `slowVol` beside `slowShare` because the channel bypasses the spiral: the share
    // takes volatility out and the scale gives it back without thinning the market (0.5 / 1.5 on
    // the Nasdaq recipe reads vol 27.2, lag-20 0.27, lag-60 0.175 against 26.9 / 0.25 / 0.175).
    ("slowShare",    0.0,  0.80, (w, x) => w.copy(slowShare = x), _.slowShare),
    ("slowVol",      0.5,  2.50, (w, x) => w.copy(slowVol = x), _.slowVol),
    // THE CHANNEL'S BOND LEG AND ITS PERMANENT SHARE, searched from 0.24.3.  The bond takes
    // `slowBeta` x the equity's repricing, so with `slowBeta` fixed every step the search took in
    // `slowVol` moved Treasury vol with it: at 0.5 / 1.5 on the Nasdaq recipe the bond leaves
    // its 0.70-1.10x-duration band, and the archive could not grow the equity's channel without
    // growing the bond's.  Held at the recipe's product (slowBeta x slowVol), 0.4 / 1.3 passes
    // every class at vol 25.1 with lag-20 0.23 at the recipe's loss.  `slowPerm` because the share that
    // reaches the fundamental sets what the value channel has to close, which is the channel's
    // variance-ratio and drawdown footprint; both dials ship at one value in every frozen world.
    ("slowBeta",     0.0,  1.20, (w, x) => w.copy(slowBeta = x), _.slowBeta),
    ("slowPerm",     0.0,  1.00, (w, x) => w.copy(slowPerm = x), _.slowPerm),
    // THE BELIEF HALF-LIFE, searched from 0.24.3 beside `beliefShare` and `capYears`: the
    // 0.23.0 sweep found 1.0 / 0.95 puts the cycle's persistence on Shiller's where 1.5 / 0.85
    // (the Nasdaq recipe) does not, and nothing graded the wings until item 25's rows.
    ("beliefYears",  0.5,  4.00, (w, x) => w.copy(beliefYears = x), _.beliefYears),
    // THE BUST SWING's amplitude, searched from 0.24.3: 0.14 reads the record's mania bust on the
    // Nasdaq recipe (item 25); the wings and the typical-year row are what it trades against.
    ("bustAmp",      0.0,  0.30, (w, x) => w.copy(bustAmp = x), _.bustAmp),
    // THE VALUATION CYCLE (item 27): its stationary sd and half-life; the record's CAPE reads
    // 0.415 about its mean at a half-life of 11.5 years, and the dispersion / wing rows are what
    // the pair trades against the beliefs' share.
    ("cycleSd",      0.0,  0.60, (w, x) => w.copy(cycleSd = x), _.cycleSd),
    ("cycleYears",   3.0, 20.00, (w, x) => w.copy(cycleYears = x), _.cycleYears),
    // THE BELIEFS' FADE (item 27): the residual gap's stationarity at a high belief share
    ("beliefLeak",   0.0,  0.60, (w, x) => w.copy(beliefLeak = x), _.beliefLeak),
    // NEWS THAT FOLLOWS LEVERAGE (item 29), with `newsRate` widened to 30 a year beside it: the
    // record's body is centred at +0.10 sigma with the heavier tail on the left, which frequent
    // moderate markdowns reproduce only when they are tied to the credit stock.
    ("newsLev",      0.0, 60.00, (w, x) => w.copy(newsLev = x), _.newsLev),
    ("newsRevert",   0.0,  1.00, (w, x) => w.copy(newsRevert = x), _.newsRevert),
    ("newsScale",    0.0,  1.00, (w, x) => w.copy(newsScale = x), _.newsScale),
    ("newsBond",     0.0,  1.00, (w, x) => w.copy(newsBond = x), _.newsBond),
    ("noiseSkew",    0.0,  0.95, (w, x) => w.copy(noiseSkew = x), _.noiseSkew),
    ("newsFlip",     0.0,  1.00, (w, x) => w.copy(newsFlip = x), _.newsFlip),
    ("newsBondSkip", 0.0,  0.90, (w, x) => w.copy(newsBondSkip = x), _.newsBondSkip),
    ("creditRegime", 0.0,  1.20, (w, x) => w.copy(creditRegime = x), _.creditRegime),
    ("creditRegimeRate", 0.0, 30.0, (w, x) => w.copy(creditRegimeRate = x), _.creditRegimeRate),
    ("slowBondInfl", 0.0,  1.00, (w, x) => w.copy(slowBondInfl = x), _.slowBondInfl),
    ("levGain",      0.0, 15.00, (w, x) => w.copy(levGain = x), _.levGain),
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
      val drawn = ranges.foldLeft(base)((wAcc, r) => r._4(wAcc, sr.uniform(r._2, r._3)))
      // the size pulled inside the news budget, and the description read off the world scored,
      // so a printed world is the one that was scored
      val w = drawn.copy(newsSize = newsSizeWithinBudget(drawn.newsRate, drawn.newsSize))
      val desc = ranges.map((nm, _, _, _, get) => f"$nm%s=${get(w)}%.4f").mkString(" ")
      val f = score(w, trainSeed)
      eprintln(f"  sample $k%3d  train loss $f%7.3f")
      (f, w, desc)
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
      val ok = gateOkOf(a, st, sims, years, seed, w, gateReq)
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
      val okSev = gateOkOf(a, st, sims, years, seed, w, gateReq)
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
    "equity vol %", "typical-year vol %", "return per vol", "kurtosis", "clustering lag 1", "clustering lag 20",
    "variance ratio 60d", "downside vol excess %", "up-day share %", "leverage corr",
    "valuation dispersion", "upper wing months %", "lower wing months %",
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
     Vector("equity vol %", "typical-year vol %", "return per vol", "kurtosis", "crashes/century",
            "median depth %", "downside vol excess %", "up-day share %", "leverage corr")),
    (a.clusterWindow, a.clusterYears,
     Vector("clustering lag 1", "clustering lag 20")),
    // Its own group because its own window -- see `Anchors.tailWindow`.  For both shipped sets this
    // is the instrument's whole history, which is the only window that cannot have deleted the
    // deepest episode.
    (a.tailWindow, a.tailYears, Vector("worst crash %")),
    // The Shiller record is one series shared by every anchor set, at its own century horizon.
    ("Shiller CAPE 1881-2023", 100, Vector("valuation dispersion", "upper wing months %", "lower wing months %")),
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
    * `horizonYears` is the length of the record the row is read against: the record's own
    * (`recordBandYears`) where the row carries a `RecordBand`, which is the length `model` and
    * `real` were both read over, and the anchor's, from `anchorGroups`, elsewhere.  It
    * is carried on EVERY row, not just the extreme ones, because a per-path ratio still folds a
    * horizon mismatch a reader cannot otherwise see.
    *
    * `real` IS THE RECORD, read the way the model reads a path, wherever the row has a
    * `RecordBand`, and the row's anchor elsewhere; `target` is what the loss grades against.  The
    * two differ where the target is a theory value (the variance ratio's 1.00), a literal older than
    * its record (four S&P rows), or an earlier vintage of the same series -- and a consumer dividing
    * by a `real` that was a target read a theory value as a bias.  `recordBand` is the record's
    * joint resampling band (`RecordBand.band`) and `recordPctile` where the model falls among those
    * resamples: the reverse of `pctile`, which places the record among the model's histories. */
  final case class FidelityRow(name: String, model: Double, real: Double, target: Double,
                               ratio: Option[Double], pctile: Option[Int],
                               recordBand: Option[(Double, Double)], recordPctile: Option[Int],
                               horizonYears: Int, nHistories: Int):
    /** Stated as the admissible interval and NEGATED, so an unmeasurable row reports a miss rather
      * than a clean bill of health -- a `NaN` reading fails every outward comparison, and an
      * extreme row whose ensemble produced no reading has nothing to stand on either.  A row with a
      * record band is judged by it alone. */
    def miss: Boolean = recordBand match
      case Some((lo, hi)) => !(model >= lo && model <= hi)
      case None => ratio match
        case Some(r) => !(r >= FidelityRatioBand._1 && r <= FidelityRatioBand._2)
        case None    => !pctile.exists(p => p >= ExtremePctBand._1 && p <= ExtremePctBand._2)
    /** The interval `miss` admits `model` in, lower edge first: the record band where the row has
      * one, else the ratio band times `real`.  None for an extreme row, graded by a percentile, and
      * for a zero record, which no ratio grades. */
    def interval: Option[(Double, Double)] = recordBand match
      case Some(b) => Some(b)
      case None =>
        ratio.flatMap: _ =>
          if real > 0.0 then Some((FidelityRatioBand._1 * real, FidelityRatioBand._2 * real))
          else if real < 0.0 then Some((FidelityRatioBand._2 * real, FidelityRatioBand._1 * real))
          else None
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
  /** The horizons an `ExtremeTargets` row is read at -- the anchor group's own window length,
    * never the caller's `-years`.  Exposed so a caller running AT one of them can simulate once
    * instead of twice: see `extremeReadingsFrom`. */
  def extremeHorizons(a: Anchors): Vector[Int] =
    anchorGroups(a).filter((_, _, names) => names.exists(ExtremeTargets.contains)).map(_._2)

  /** The fidelity rows read as a MEDIAN OF SINGLE HISTORIES rather than off the pooled ensemble.
    * Exposed so a caller that has skipped the second ensemble knows which rows it therefore has
    * no reading for, instead of scoring them as unmeasurable. */
  def extremeTargetNames: Set[String] = ExtremeTargets

  /** The single-history readings for every `ExtremeTargets` row whose group is read at `yrs`, from
    * an ensemble the CALLER already holds.
    *
    * Split out of `extremeReadings` for one reason: the extreme row costs a SECOND ensemble at its
    * own horizon, and that is over half of an evaluation -- 2.89 s against the main reading's
    * 2.28 s at 60 x 80 on the shipped world.  A caller running at the extreme horizon is
    * simulating exactly the same paths from exactly the same seed twice.  Nothing here changes
    * what is computed.
    *
    * Each path is measured on its own, IN PARALLEL: `measure` is pure and the order is preserved,
    * so the readings and their median are what they were. */
  /** ONE PATH'S READING of an `ExtremeTargets` row, taken directly instead of through `measure`.
    * Each of these rows reads a single statistic of each path, and `measure` on a one-path ensemble
    * computed every other statistic too -- the macro panel and the channels included -- and dropped
    * them: most of the extreme ensemble's cost.  `None` for a row with no direct reading, which
    * then takes the full path.  A contract test holds each direct reading to `measure`'s own, bit
    * for bit. */
  private[apps] def extremeReading(nm: String, p: Path): Option[Double] = nm match
    // `measure`'s `worstDepth` for this path alone: its smallest episode depth, NaN without one
    case "worst crash %" => Some(episodes(p.price, 15.0).map(_.depthPct).minOption.getOrElse(Double.NaN))
    case _               => None

  def extremeReadingsFrom(a: Anchors, sims: Vector[Path], yrs: Int): Map[String, Vector[Double]] =
    // `measure` per path only for a row with no direct reading, and then only once
    lazy val full = parMap(sims)(p => measure(Vector(p), yrs))
    anchorGroups(a)
      .filter((_, gy, names) => gy == yrs && names.exists(ExtremeTargets.contains))
      .flatMap((_, _, names) => names.filter(ExtremeTargets.contains).map { nm =>
        val (_, get, _, _) = fitTargets(a).find(_._1 == nm)
          .getOrElse(usage(s"ExtremeTargets names [$nm], which is not a fidelity target"))
        val direct = parMap(sims)(p => extremeReading(nm, p))
        val vals   = if direct.forall(_.isDefined) then direct.flatten else full.map(get)
        nm -> vals.filter(x => !x.isNaN)
      }).toMap

  /** The median of `extremeReadingsFrom`, for an ensemble the caller already holds. */
  def extremeScoreStatsFrom(a: Anchors, sims: Vector[Path], yrs: Int): Map[String, Double] =
    extremeReadingsFrom(a, sims, yrs).map((nm, xs) => nm -> extremeMedian(xs))

  /** The loss's reading of an extreme row's single histories: `measure`'s own median rule,
    * non-finite dropped and NaN on empty. */
  private def extremeMedian(xs: Vector[Double]): Double =
    val f = xs.filter(_.isFinite)
    if f.isEmpty then Double.NaN else f.sorted.apply(f.size / 2)

  def extremeReadings(a: Anchors, paths: Int, seed: Long, w: World): Map[String, Vector[Double]] =
    extremeHorizons(a)
      .flatMap(yrs => extremeReadingsFrom(a, simPaths(w, paths, yrs, seed), yrs))
      .toMap

  /** What the LOSS grades an extreme row by: the median of the single-history readings.  A median
    * of extremes converges as histories are added, where the pooled minimum deepens without
    * bound.  NaN where the ensemble produced no finite reading, which `fitness` prices as
    * unmeasurable rather than as agreement. */
  def extremeScoreStats(a: Anchors, histories: Int, seed: Long, w: World): Map[String, Double] =
    extremeReadings(a, histories, seed, w).map((nm, xs) => nm -> extremeMedian(xs))

  /** What a read's own ensemble leaves out (`horizonReadings`): every banded row's reading at its
    * record's horizon, and every extreme row's single-history readings at its anchor's. */
  final case class HorizonReadings(banded: Map[String, Double],
                                   extreme: Map[String, Vector[Double]]):
    /** The extreme rows as the loss reads them (`extremeScoreStats`). */
    def extremeScores: Map[String, Double] = extreme.map((nm, xs) => nm -> extremeMedian(xs))

  object HorizonReadings:
    val empty: HorizonReadings = HorizonReadings(Map.empty, Map.empty)

  /** THE READINGS A READ'S OWN ENSEMBLE LEAVES OUT, from ONE ensemble per horizon they need.
    *
    * Every row that carries a `RecordBand` is read at its record's length (`recordBandYears`): a
    * band is the spread of a history that long, and read off a longer ensemble a statistic that
    * grows with the window grades the horizon, not the model -- the Nasdaq recipe reads kurtosis
    * 21-24 over 100 years against 17-18 over the record's 27 (60 paths, three seeds), and the band
    * is 7.6 to 11.7.  With
    * `extremeToo`, every extreme row's single histories are read at its anchor's horizon
    * (`extremeHorizons`) as well.
    *
    * `st` serves the banded rows at `years`, and `main`, the caller's ensemble when it holds one,
    * the extreme rows there; any other horizon is simulated once for both, since at the shipped
    * sets the tail horizon is a banded one.  One horizon at a time, so no two extra ensembles are
    * held at once.  Every reading is what a separate ensemble per row would read: the paths are
    * `simPaths(w, paths, h, seed)` either way. */
  def horizonReadings(a: Anchors, st: WorldStats, main: Option[Vector[Path]], years: Int,
                      paths: Int, seed: Long, w: World, extremeToo: Boolean): HorizonReadings =
    val bandedAt = fitTargets(a).filter((n, _, _, _) => a.recordBands.exists(_.name == n))
      .groupBy((n, _, _, _) => recordBandYears(a, n))
    val extremeAt = if extremeToo then extremeHorizons(a).toSet else Set.empty[Int]
    (bandedAt.keySet ++ extremeAt).toVector.sorted.foldLeft(HorizonReadings.empty): (acc, h) =>
      // the caller's own ensemble at its own horizon; a shorter horizon cut from it (`Path.head`),
      // bit for bit the ensemble a simulation would give; a longer one, or any without the
      // caller's ensemble, simulated once for both
      lazy val sims = main match
        case Some(m) if h == years => m
        case Some(m) if h < years  => m.map(_.head(h))
        case _                      => simPaths(w, paths, h, seed)
      val banded = bandedAt.get(h).fold(Map.empty[String, Double]): rows =>
        val s = if h == years then st else measure(sims, h)
        rows.map((n, get, _, _) => n -> get(s)).toMap
      val extreme = if extremeAt.contains(h) then extremeReadingsFrom(a, sims, h) else Map.empty
      HorizonReadings(acc.banded ++ banded, acc.extreme ++ extreme)

  /** The banded rows alone, each at its record's horizon (`horizonReadings`). */
  def bandedReadings(a: Anchors, st: WorldStats, years: Int, paths: Int, seed: Long,
                     w: World): Map[String, Double] =
    horizonReadings(a, st, None, years, paths, seed, w, extremeToo = false).banded

  /** Every fidelity row as the report and the sidecar both read it.  Built ONCE per invocation so
    * the printed table and the emitted JSON cannot describe the same world differently.  `st` is
    * the verdict ensemble, `years` its path length; a banded row is read at its record's own
    * horizon instead (`horizonReadings`). */
  def fidelityRows(a: Anchors, st: WorldStats, main: Option[Vector[Path]], years: Int, paths: Int,
                   seed: Long, w: World): Vector[FidelityRow] =
    val extremeToo = fitTargets(a).exists((n, _, _, _) => ExtremeTargets.contains(n))
    val readings = horizonReadings(a, st, main, years, paths, seed, w, extremeToo)
    val pcts   = readings.extreme
    val hz     = anchorHorizons(a)
    val banded = readings.banded
    fitTargets(a).map { (name, get, want, _) =>
      val got = banded.getOrElse(name, get(st))
      if ExtremeTargets.contains(name) then
        val xs = pcts.getOrElse(name, Vector.empty)
        val p  = if xs.size < ExtremeMinHistories then None else Some(anchorPctile(xs, want))
        FidelityRow(name, got, want, want, None, p, None, None, hz.getOrElse(name, 0), xs.size)
      else
        // the record, read the model's way, where the row has a band; the anchor elsewhere
        val band = a.recordBands.find(_.name == name)
        val real = band.fold(want)(_.record)
        // a banded row's horizon is its record's, the length `model` and `real` were read over
        val horizon = if band.isDefined then recordBandYears(a, name) else hz.getOrElse(name, 0)
        FidelityRow(name, got, real, want, Some(if real != 0.0 then got / real else Double.NaN),
                    None, band.map(_.band), band.flatMap(_.percentile(got)), horizon, 1)
    }

  /** THE RECORD BANDS AS A SEARCH TERM.  Each banded row's excess past its joint band's nearer
    * edge (`RecordBand.band`), in the row's own sd, (p95 - p5) / 3.29, the width of a normal
    * spread.  It is priced at
    * `SdRelRef` per sd, the unit `fitness` prices one anchor sd in.  Zero inside the band.  An
    * unmeasurable reading costs four sd, as an unmeasurable fitness row does.  `banded` comes from
    * `bandedReadings`, so each row is read at its record's own horizon; `fitness` reads the
    * search's horizon and weighs kurtosis at a few hundredths, so it cannot see the band.  In
    * `recordBands` order. */
  def recordBandTerms(a: Anchors, banded: Map[String, Double]): Vector[(String, Double)] =
    a.recordBands.map { b =>
      val (lo, hi) = b.band
      val (s5, s95) = b.spread
      val sd = (s95 - s5) / 3.29
      val x  = banded.getOrElse(b.name, Double.NaN)
      // non-finite is unmeasurable: an infinite reading has no distance to price
      val excess = if !x.isFinite then 4.0 * sd else math.max(math.max(lo - x, x - hi), 0.0)
      (b.name, SdRelRef * excess / sd)
    }

  /** Percentile points per anchor sd on a banded row: the resamples' 5th and 95th percentiles sit
    * 45 points from the middle, where a normal's are 1.645 sd out. */
  val PctPerSd = 45.0 / 1.645

  /** ONE ROW'S DISTANCE FROM ITS RECORD IN THE JUDGE'S UNITS: percentile points from the band's
    * middle on a row with a record band, `|ln(model / target)|` on any other -- what a set or a
    * recipe is graded on row by row, where `recordDistances` reads the loss's units.  `None` for
    * an extreme row, graded by where the record falls among single histories, and for a name that
    * is no row of this set.  An unmeasurable reading is the furthest a row can be: 50 points, or
    * infinity. */
  def judgeDistance(a: Anchors, name: String, st: WorldStats, banded: Map[String, Double]): Option[Double] =
    if ExtremeTargets.contains(name) then None
    else a.recordBands.find(_.name == name) match
      case Some(b) =>
        Some(banded.get(name) match
          case Some(v) if v.isFinite => math.abs(b.percentileExact(v) - 50.0)
          case _                     => 50.0)
      case None =>
        fitTargets(a).find(_._1 == name).map { (_, get, target, _) =>
          val ratio = get(st) / target
          // `lnDet`, not the platform's log: a search holds a row to a bar on this number, and
          // the twins must agree on which side of it a candidate falls
          if ratio.isFinite && ratio > 0.0 then math.abs(lnDet(ratio)) else Double.PositiveInfinity
        }

  /** Every fitness row's distance from its RECORD, in the loss's units (`SdRelRef` per anchor
    * sd): a banded row's distance from the middle of the record's resamples, |percentile - 50|
    * over `PctPerSd`, the reading at the record's own horizon (`banded`, from `bandedReadings`;
    * unmeasurable costs four sd) -- the percentile, not |reading - record|, because it is what the
    * verdict publishes and a band need not be symmetric about its record; any other row's
    * `fitness` term, whose target IS its record.  What the release rule compares a candidate with
    * the outgoing recipe on, row by row, so a search can price "further from the record than the
    * recipe it would replace".  In `fitness` row order. */
  def recordDistances(a: Anchors, fitnessRows: Vector[(String, Double, Double, Double)],
                      banded: Map[String, Double]): Vector[(String, Double)] =
    fitnessRows.map { (name, _, _, term) =>
      val d = a.recordBands.find(_.name == name) match
        case Some(b) =>
          banded.get(name) match
            // non-finite is unmeasurable: `percentileExact` has no place for it
            case Some(v) if v.isFinite => SdRelRef * math.abs(b.percentileExact(v) - 50.0) / PctPerSd
            case _                   => 4.0 * SdRelRef
        case None => term
      (name, d)
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
      val ok    = gateOkOf(a, measure(sims, L), sims, L, sd, w, gateReq)
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
    ddSpans(px, threshold).map { s =>
      val base   = math.max(s.lo - 1, 0)
      val total  = math.log(px(s.trough) / px(base))
      val legs   = (math.max(s.lo, 1) to s.trough).map(k => math.log(px(k) / px(k - 1)))
      val worst  = if legs.isEmpty then 0.0 else legs.min
      DdEpisode(s.depth, s.trough - s.lo + 1, if s.censored then None else Some(s.hi - s.trough + 1),
                s.hi - s.lo + 1, if total < 0.0 then worst / total else Double.NaN)
    }

  /** An underwater span deeper than a threshold: `lo` the first underwater bar (the peak is the
    * session before it), `trough` its deepest, `hi` its last, `depth` px/peak - 1 at the trough. */
  final case class DdSpan(lo: Int, trough: Int, hi: Int, depth: Double, censored: Boolean)

  /** The spans `ddEpisodes` and the macro panel's warning share share, so the two read the
    * same episodes. */
  def ddSpans(px: Array[Double], threshold: Double): Vector[DdSpan] =
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
      else Some(DdSpan(lo, (lo to hi).minBy(under), hi, depth, censored))
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
      val ok   = gateOkOf(a, measure(sims, years), sims, years, seed, w, gateReq)
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

  /** `foo.tsv` -> `foo.json`; a name with no extension just gains one.  The null device (`nul` in
    * any directory and any case, `/dev/null`) is its own sidecar, so an `-emit` run made for the
    * verdict alone leaves no `nul.json` behind. */
  def sidecarName(file: String): String =
    val cut = file.lastIndexOf('.')
    val sep = math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'))
    if file == "/dev/null" || file.substring(sep + 1).equalsIgnoreCase("nul") then file
    else if cut > sep then file.substring(0, cut) + ".json" else file + ".json"

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
            p.traded.forall(_.isFinite) && p.logOpen.forall(_.isFinite) &&
            p.names.forall(_.forall(_.isFinite)) &&
            p.macroPanel.forall(m => (0 to 8).forall(j => m.member(j).forall(_.isFinite))),
            s"path $k holds a non-finite value; refusing $file")
    val dates = sessionDates(p.price.length, startYmd)
    writeEmitTsv(file, p, dates)
    writeEmitSidecar(a, file, p, k, w, years, seed, startYmd, dates, gateSt, gatePaths, gateYears,
                     gateRows)

  /** The basket's optional columns: the aggregate, then one per name. */
  def basketColumns(p: Path): Vector[String] =
    if p.names.isEmpty then Vector.empty
    else "logBasket" +: p.names.indices.map(q => s"logName${q + 1}").toVector

  /** The equal-weight aggregate of the names as a LOG price series: the mean of the names'
    * price levels relative to their common start, the fixture's convention (equal weights held
    * from the first session, never rebalanced). */
  def basketAggregate(names: Vector[Array[Double]]): Array[Double] =
    Array.tabulate(names.head.length)(i => math.log(names.map(lp => math.exp(lp(i) - lp(0))).sum / names.size))

  def writeEmitTsv(file: String, p: Path, dates: Vector[String]): Unit =
    // The optional columns, present only when their channel ran -- a channels-off file is
    // byte-identical to its predecessor schema's.  LOG columns throughout: see the 7 -> 8 and
    // 8 -> 9 notes at `EmitSchema`.
    val header = (EmitColumns
      ++ (if p.sat.isEmpty then Vector() else Vector("logSat"))
      ++ (if p.logHi.isEmpty then Vector() else Vector("logHigh", "logLow"))
      ++ (if p.logVolume.isEmpty then Vector() else Vector("logVolume"))
      ++ (if p.traded.isEmpty then Vector() else Vector("logTraded", "divYield"))
      ++ (if p.logOpen.isEmpty then Vector() else Vector("logOpen"))
      ++ basketColumns(p)
      ++ (if p.macroPanel.isEmpty then Vector() else MacroK.Columns)).mkString("\t")
    val basketAgg = if p.names.isEmpty then Array.emptyDoubleArray else basketAggregate(p.names)
    val rows = header +: Vector.tabulate(dates.length) { i =>
      val base =
        s"${dates(i)}\t${ef(p.price(i))}\t${ef(p.bond(i))}\t${ef(p.rate(i))}\t${ef(p.cpi(i))}\t" +
        s"${ef(p.liq(i))}\t${ef(p.bliq(i))}\t${ef(p.fundamental(i))}\t${ef(p.inflPress(i))}"
      val s1 = if p.sat.isEmpty then base else s"$base\t${ef(math.log(p.sat(i)))}"
      val s2 = if p.logHi.isEmpty then s1 else s"$s1\t${ef(p.logHi(i))}\t${ef(p.logLo(i))}"
      val s3 = if p.logVolume.isEmpty then s2 else s"$s2\t${ef(p.logVolume(i))}"
      val s4 = if p.traded.isEmpty then s3 else s"$s3\t${ef(math.log(p.traded(i)))}\t${ef(p.divYield(i))}"
      val s5 = if p.logOpen.isEmpty then s4 else s"$s4\t${ef(p.logOpen(i))}"
      val s6 = if p.names.isEmpty then s5
               else s5 + "\t" + ef(basketAgg(i)) + p.names.map(lp => "\t" + ef(lp(i))).mkString
      p.macroPanel match
        case None    => s6
        case Some(m) => s"$s6\t${ef(m.spread(i))}\t${ef(m.slope(i))}\t${ef(m.cond(i))}\t" +
                        s"${ef(m.ivol(i))}\t${ef(m.yield10(i))}\t${ef(m.credit(i))}\t" +
                        s"${ef(m.policy(i))}\t${ef(m.bank(i))}\t${ef(m.output(i))}"
    }
    file.asPath.writeLines(rows)

  /** Member `index` of an exported calibration archive, as ARGUMENTS.
    *
    * The `world` block is keyed by FLAG NAME by design -- `macro`, never the field's `macroPanel`
    * -- so a loader has only to lowercase each key.  Turning the block into arguments and letting
    * the ordinary flag loop set them keeps ONE rule where a second copy of 76 setters would be a
    * parity surface, and it gives the member `-atrelease`'s precedence for free: a dial flag after
    * `-worldset` still overrides it.
    *
    * The expected keys ARE `worldJsonBody`'s, read off its own output, so a dial added to the
    * world cannot be silently skipped here.  A block that does not carry exactly them is REFUSED:
    * a missing dial would take the shipped default, which is a different world wearing a member's
    * name. */
  def worldSetArgs(file: String, index: Int): Seq[String] =
    val MemberLine = """^"member":\s*(\d+),?$""".r
    val FieldLine  = """^"([A-Za-z][A-Za-z0-9]*)":\s*(.+?),?$""".r
    val p = file.asPath
    if !p.exists then usage(s"-worldset $file does not exist")
    // The file this program writes: one `"key": value` a line inside a member's `world` block.  A
    // general JSON parser would buy nothing here and cost a dependency -- a foreign file fails the
    // key check below, which is the check that matters.
    val ls = p.lines.toVector.map(_.trim)
    val opens = ls.indices.filter(i => ls(i) == "\"world\": {").toVector
    if opens.isEmpty then
      usage(s"-worldset $file holds no world block; -export writes the file this reads")
    val members = opens.map { i =>
      val end = ls.indexWhere(_ == "}", i + 1)
      if end < 0 then usage(s"-worldset $file: the world block at line ${i + 1} never closes")
      val id = ls.take(i).reverse.collectFirst { case MemberLine(k) => k.toInt }
      (id.getOrElse(usage(s"-worldset $file: the world block at line ${i + 1} has no member number")),
       ls.slice(i + 1, end))
    }
    val block = members.collectFirst { case (k, b) if k == index => b }.getOrElse(usage(
      s"-worldindex $index is not in $file: it holds ${members.length} members, " +
      s"${members.map(_._1).min} to ${members.map(_._1).max}"))
    val fields = block.map {
      case FieldLine(k, v) => (k, v)
      case l => usage(s"-worldset $file member $index: cannot read [$l] as a world field")
    }
    val want = worldJsonBody(Defaults).map(_.trim).collect { case FieldLine(k, _) => k }
    if fields.map(_._1).sorted != want.sorted then
      val missing = want.filterNot(fields.map(_._1).contains)
      val extra = fields.map(_._1).filterNot(want.contains)
      usage(s"-worldset $file member $index does not carry this binary's dials" +
            (if missing.nonEmpty then s"; missing [${missing.mkString(", ")}]" else "") +
            (if extra.nonEmpty then s"; unknown [${extra.mkString(", ")}]" else ""))
    fields.flatMap { (k, v) =>
      // a quoted value is a MODE name (`crowd`), which its flag takes unquoted
      Seq("-" + k.toLowerCase, if v.startsWith("\"") then v.drop(1).dropRight(1) else v)
    }

  /** Every `World` field, in declaration order, as the indented body of a JSON object.  A world
    * that reaches a consumer without its parameters cannot be re-simulated.
    * `num` renders every dial, so a caller needing a different width than the report's asks for
    * one here rather than keeping a second copy of the key list: the calibration search exports
    * its archive at the archive's own width, because a consumer RECONSTRUCTS a world from this
    * block and the report's six decimals drop digits the archive holds. */
  def worldJsonBody(w: World, num: Double => String = ef): Vector[String] =
    Vector(
      ("trendShare", num(w.trendShare)), ("depth", num(w.depth)), ("stress", num(w.stress)),
      ("beta", num(w.beta)), ("drift", num(w.drift)), ("fundVol", num(w.fundVol)),
      ("rateMean", num(w.rateMean)), ("volPersist", num(w.volPersist)),
      ("volOfVol", num(w.volOfVol)), ("leverage", num(w.leverage)),
      ("downShock", num(w.downShock)), ("jumpSkew", num(w.jumpSkew)), ("jumpVar", num(w.jumpVar)),
      ("jumpRate", num(w.jumpRate)), ("newsRate", num(w.newsRate)), ("newsSize", num(w.newsSize)),
      ("newsLev", num(w.newsLev)), ("newsRevert", num(w.newsRevert)),
      ("newsScale", num(w.newsScale)), ("newsBond", num(w.newsBond)),
      ("newsBondSkip", num(w.newsBondSkip)), ("creditRegime", num(w.creditRegime)),
      ("creditRegimeRate", num(w.creditRegimeRate)), ("slowBondInfl", num(w.slowBondInfl)),
      ("noiseSkew", num(w.noiseSkew)),
      ("newsFlip", num(w.newsFlip)),
      ("valuePull", num(w.valuePull)),
      ("recoveryDrag", num(w.recoveryDrag)), ("recoveryFloor", num(w.recoveryFloor)),
      ("haltLimit", num(w.haltLimit)),
      ("disasterRate", num(w.disasterRate)), ("disasterSize", num(w.disasterSize)),
      ("disasterLen", num(w.disasterLen)), ("disasterRecover", num(w.disasterRecover)),
      ("disasterRecLen", num(w.disasterRecLen)),
      ("beliefShare", num(w.beliefShare)), ("beliefYears", num(w.beliefYears)),
      ("beliefLeak", num(w.beliefLeak)),
      ("capYears", num(w.capYears)), ("capWindow", num(w.capWindow)),
      ("cycleSd", num(w.cycleSd)), ("cycleYears", num(w.cycleYears)),
      ("crowd", jsonStr(crowdName(w.crowd))), ("crowdImpact", num(w.crowdImpact)),
      ("panic", num(w.panic)), ("duration", num(w.duration)),
      ("easing", num(w.easing)), ("unwind", num(w.unwind)), ("refuge", num(w.refuge)),
      ("refugeDays", num(w.refugeDays)),
      ("satBeta", num(w.satBeta)), ("satIdio", num(w.satIdio)),
      ("rangeScale", num(w.rangeScale)), ("rangeDown", num(w.rangeDown)),
      ("volIdio", num(w.volIdio)), ("divYield", num(w.divYield)), ("overnight", num(w.overnight)),
      ("basket", w.basket.toString), ("basketBeta", num(w.basketBeta)),
      ("basketSector", num(w.basketSector)), ("basketIdio", num(w.basketIdio)),
      ("basketGaps", num(w.basketGaps)), ("basketDrift", num(w.basketDrift)),
      // the flag's name, as every dial's key is: the FIELD is `macroPanel` only because `macro`
      // is a reserved word in Scala, and a consumer reconstructing a world from this block passes
      // `-macro`
      ("macro", w.macroPanel.toString), ("levGain", num(w.levGain)), ("stressScale", num(w.stressScale)),
      ("levPersist", num(w.levPersist)), ("noiseAsym", num(w.noiseAsym)),
      ("noiseAsymPhi", num(w.noiseAsymPhi)), ("noiseAsymCap", num(w.noiseAsymCap)),
      ("volResp", num(w.volResp)), ("volRespPhi", num(w.volRespPhi)),
      ("volRespCap", num(w.volRespCap)), ("volRespAttack", num(w.volRespAttack)),
      ("jumpResp", num(w.jumpResp)), ("stressAdapt", num(w.stressAdapt)),
      ("bustAmp", num(w.bustAmp)),
      ("slowShare", num(w.slowShare)), ("slowVol", num(w.slowVol)), ("slowLev", num(w.slowLev)),
      ("slowPhi", num(w.slowPhi)), ("slowPerm", num(w.slowPerm)), ("slowBeta", num(w.slowBeta)),
      ("macroNull", w.macroNull.toString),
      ("inflProb", num(w.inflProb)), ("inflSize", num(w.inflSize)),
      ("inflSpeed", num(w.inflSpeed)), ("rateSpeed", num(w.rateSpeed)),
      ("discount", num(w.discount)), ("margin", num(w.margin)),
    ).map((nm, v) => s"""    ${jsonStr(nm)}: $v""")

  /** The channel readings the `satellite *` / `bar *` gate rows grade, as DATA: `fidelityFailed`
    * names a band, and a reader that never sees the report could not size a channel FAIL from
    * it.  Each object is present exactly when its channel ran (`satStats`/`barStats` return
    * Some); `{}` when none did, else led by the world level they were sampled at
    * (`worldLevel`).  NaN prints as null, the `fidelity` rows' rule. */
  def channelReadingsBlock(st: WorldStats, p: Path): String =
    def num(x: Double): String = if x.isNaN then "null" else ef(x)
    val level =
      if st.sat.isDefined || st.bars.isDefined || st.open.isDefined || st.basket.isDefined || st.divYieldMean.isFinite ||
         st.macroPanel.isDefined then
        Vector(s"""    "level": { "k": ${num(p.chanK)}, "kSat": ${num(p.chanKSat)}, "kDiv": ${num(p.chanKDiv)}, "kVs": ${num(p.chanKVs)}, "kIv": ${num(p.chanKIv)}""" +
               (if p.chanKDr > 0.0 then s""", "kDr": ${num(p.chanKDr)}""" else "") + " }")
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
    val open = st.open.toVector.map { os =>
      s"""    "open": { "overnightShare": ${num(os.overnightShare)}, "worstGapShare": ${num(os.worstGapShare)}, "allGapShare": ${num(os.allGapShare)} }"""
    }
    val bsk = st.basket.toVector.map { b =>
      s"""    "basket": { "nameVolRatio": ${num(b.nameVolRatio)}, "nameGaps": ${num(b.nameGaps)}, """ +
      s""""nameD20": ${num(b.nameD20)}, "aggCorr": ${num(b.aggCorr)}, "aggBeta": ${num(b.aggBeta)}, """ +
      s""""aggVolRatio": ${num(b.aggVolRatio)}, "pairCorr": ${num(b.pairCorr)}, "idioShare": ${num(b.idioShare)}, """ +
      s""""tailCoincidence": ${num(b.tailCoincidence)}, "pairCorrWorst": ${num(b.pairCorrWorst)}, "pairCorrMid": ${num(b.pairCorrMid)}, """ +
      s""""nameD20Spread": ${num(b.nameD20Spread)} }"""
    }
    // The macro panel's readings, each member naming its counterpart and natural cadence -- the
    // routing a consumer's point-in-time loader needs, in the data rather than in prose.
    // A per-path spread beside each pooled statistic: `[p5, p50, p95]` of the per-path readings,
    // the width of the null a single path sits in.
    def sp(x: Spread) = s"[${num(x.p5)}, ${num(x.p50)}, ${num(x.p95)}]"
    val mac = st.macroPanel.toVector.map { ms =>
      val members = ms.members.indices.map { j =>
        val m = ms.members(j); val (r2S, preS, lagS) = ms.memberSpread(j)
        s"""      { "column": ${jsonStr(MacroK.Columns(j))}, "counterpart": ${jsonStr(MacroK.Counterparts(j))}, """ +
        s""""cadence": ${jsonStr(MacroK.Cadence(j))}, "ac1": ${num(m.ac1)}, "acK": ${num(m.acK)}, """ +
        s""""r2fwd60": ${num(m.r2fwd60)}, "warn20": ${num(m.warn)}, "fired": ${num(m.warnFired)}, "lag20": ${num(m.lag)}, """ +
        s""""lag10": ${num(m.lag10)}, "fired10": ${num(m.fired10)}, "prePeak": ${num(m.prePeak)},\n""" +
        s"""        "perPath": { "r2fwd60": ${sp(r2S)}, "prePeak": ${sp(preS)}, "lag20": ${sp(lagS)} } }"""
      }
      s"""    "macro": { "null": ${ms.sibling}, "episodes": ${ms.episodes}, "invShare": ${num(ms.invShare)}, "invDur": ${num(ms.invDur)}, """ +
      s""""vrp": ${num(ms.vrp)}, "r2rv": ${num(ms.r2rv)}, "hazard20q": ${num(ms.hazard20q)}, "hazard20y": ${num(ms.hazard20y)}, """ +
      s""""hazard10q": ${num(ms.hazard10q)}, "p20q": ${num(ms.p20q)},\n""" +
      s"""      "perPath": { "invShare": ${sp(ms.invShareSpread)}, "vrp": ${sp(ms.vrpSpread)}, "r2rv": ${sp(ms.r2rvSpread)}, "hazard20q": ${sp(ms.hazardSpread)} },\n""" +
      s"""      "members": [\n""" +
      members.mkString(",\n") + "\n      ] }"
    }
    val blocks = level ++ sat ++ bars ++ div ++ open ++ bsk ++ mac
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
    val gateBanded   = bandedOf(gateRows)
    val realismBad   = failedInAt(a, gateSt, gateBanded, GateClass.Realism)
    val mechanismBad = failedInAt(a, gateSt, gateBanded, GateClass.Mechanism)
    val fidelityBad  = failedInAt(a, gateSt, gateBanded, GateClass.Fidelity)
    def strList(v: Vector[String]): String = v.map(jsonStr).mkString("[", ", ", "]")
    def num(x: Double): String = if x.isNaN then "null" else ef(x)
    // `aggregation` and `horizonYears` are the terms of the comparison, and they are in the DATA
    // because prose does not travel: a consumer holding this file has no access to the report's
    // note, and an `ensemble-extreme` row divided by its anchor gives a quotient that grades the
    // ensemble size.  Such a row carries `ratio: null` and a `percentile` instead -- where the
    // record falls among single histories of its own length -- so the division cannot be made by
    // accident.  `miss` is the admissible interval NEGATED for both kinds, so a row that could not
    // be measured reports a miss rather than a clean bill of health.  `real` is the record read the
    // model's way on every row with a `recordBand`, and `target` what the loss grades against;
    // `recordPercentile` places the MODEL among the record's resamples, the reverse of `percentile`,
    // so the two never share a field.
    val fidelity = gateRows.map { r =>
      s"""    { "name": ${jsonStr(r.name)}, "model": ${num(r.model)}, "real": ${num(r.real)}, """ +
      s""""target": ${num(r.target)}, """ +
      s""""aggregation": ${jsonStr(r.aggregation)}, "horizonYears": ${r.horizonYears}, """ +
      s""""ratio": ${r.ratio.fold("null")(num)}, """ +
      s""""percentile": ${r.pctile.fold("null")(_.toString)}, """ +
      s""""recordBand": ${r.recordBand.fold("null")((lo, hi) => s"[${num(lo)}, ${num(hi)}]")}, """ +
      s""""recordPercentile": ${r.recordPctile.fold("null")(_.toString)}, "miss": ${r.miss} }"""
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
        ++ (if p.traded.isEmpty then Vector() else Vector("logTraded", "divYield"))
        ++ (if p.logOpen.isEmpty then Vector() else Vector("logOpen"))
        ++ basketColumns(p)
        ++ (if p.macroPanel.isEmpty then Vector() else MacroK.Columns))},""",
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
        ++ (if p.traded.isEmpty then Vector() else Vector("logTraded", "divYield"))
        ++ (if p.logOpen.isEmpty then Vector() else Vector("logOpen"))
        ++ basketColumns(p)
        ++ (if p.macroPanel.exists(!_.sibling) then MacroK.Columns else Vector()))},""",
      // The field that says a column reached the file UNGRADED: `logSat` is covered by the
      // `satellite *` rows and the bar columns by the `bar *` rows, and the one case today is a
      // NULL macro panel (`-macronull`), whose four columns are a sibling path's and grade
      // nothing by construction -- said here, in the artifact, rather than in a doc nobody reads
      // beside the data.
      s"""    "ungradedChannelSeries": ${strList(if p.macroPanel.exists(_.sibling) then MacroK.Columns else Vector.empty)},""",
      s"""    "realism": ${jsonStr(if realismBad.isEmpty then "PASS" else "FAIL")},""",
      s"""    "mechanism": ${jsonStr(if mechanismBad.isEmpty then "PASS" else "FAIL")},""",
      s"""    "fidelity": ${jsonStr(if fidelityBad.isEmpty then "PASS" else "FAIL")},""",
      s"""    "realismFailed": ${strList(realismBad)},""",
      s"""    "mechanismFailed": ${strList(mechanismBad)},""",
      s"""    "fidelityFailed": ${strList(fidelityBad)},""",
      // Bands the anchors could not grade in this world, with the reason.  Without this a path
      // emitted from (say) a 1.8-year-duration world shows fidelity PASS and nothing says the
      // depth level was never graded at all -- a consumer would read levels off it.
      s"""    "fidelityUnanchored": ${strList(unanchoredIn(gateSt))},""",
      // THE PROFILE ROW'S READINGS.  `fidelity` below carries the loss rows, and the
      // variance-ratio profile is a gate row over four rungs, not a loss row: without this a
      // consumer learns the profile passed and cannot read it.
      s"""    "varianceRatio": { ${VarRatioLadder.map(q => s""""$q": ${num(vrOf(gateSt, q))}""").mkString(", ")} }""",
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
    // `-worldset F -worldindex K` runs member K of an exported archive.  Pre-scanned as
    // `-atrelease` is, but it arrives as ARGUMENTS placed BEFORE the command line's own, so a
    // dial flag after it still overrides the member.
    val worldSet: Seq[String] =
      args.indexOf("-worldset") match
        case -1 =>
          if args.contains("-worldindex") then usage("-worldindex wants -worldset")
          Seq.empty
        case i =>
          if args.indexOf("-worldset", i + 1) >= 0 then usage("-worldset given twice")
          if args.indexOf("-atrelease") >= 0 then
            usage("-worldset and -atrelease each name a whole world; give one")
          if i + 1 >= args.length then usage("-worldset wants an exported archive file")
          val k = args.indexOf("-worldindex") match
            case -1 => 0
            case j  =>
              if j + 1 >= args.length then usage("-worldindex wants a member number")
              intOr("-worldindex", args(j + 1))
          worldSetArgs(args(i + 1), k)
    var trendShare = dw.trendShare; var depth = dw.depth
    var stress = dw.stress; var beta = dw.beta
    var volPersist = dw.volPersist; var volOfVol = dw.volOfVol
    var jumpVar = dw.jumpVar; var jumpRate = dw.jumpRate
    var leverage = dw.leverage; var downShock = dw.downShock; var jumpSkew = dw.jumpSkew
    var newsRate = dw.newsRate; var newsSize = dw.newsSize; var newsLev = dw.newsLev
    var newsRevert = dw.newsRevert
    var newsScale = dw.newsScale
    var newsBond = dw.newsBond
    var newsBondSkip = dw.newsBondSkip
    var creditRegime = dw.creditRegime
    var creditRegimeRate = dw.creditRegimeRate
    var slowBondInfl = dw.slowBondInfl
    var noiseSkew = dw.noiseSkew
    var newsFlip = dw.newsFlip
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
    var volIdio = dw.volIdio; var divYield = dw.divYield; var overnight = dw.overnight
    var basket = dw.basket; var basketBeta = dw.basketBeta; var basketSector = dw.basketSector
    var basketIdio = dw.basketIdio; var basketGaps = dw.basketGaps
    var basketDrift = dw.basketDrift; var macroPanel = dw.macroPanel; var macroNull = dw.macroNull
    var levGain = dw.levGain; var stressScale = dw.stressScale
    var levPersist = dw.levPersist; var noiseAsym = dw.noiseAsym
    var noiseAsymPhi = dw.noiseAsymPhi
    var volResp = dw.volResp; var volRespPhi = dw.volRespPhi
    var volRespAttack = dw.volRespAttack; var volRespCap = dw.volRespCap
    var noiseAsymCap = dw.noiseAsymCap
    var jumpResp = dw.jumpResp; var stressAdapt = dw.stressAdapt
    var bustAmp = dw.bustAmp
    var cycleSd = dw.cycleSd; var cycleYears = dw.cycleYears
    var beliefLeak = dw.beliefLeak
    var slowShare = dw.slowShare; var slowVol = dw.slowVol; var slowLev = dw.slowLev
    var slowPhi = dw.slowPhi; var slowPerm = dw.slowPerm; var slowBeta = dw.slowBeta
    var jointEmit = ""
    var barsEmit = ""
    var inflProb = dw.inflProb; var inflSize = dw.inflSize
    var inflSpeed = dw.inflSpeed; var rateSpeed = dw.rateSpeed
    var discount = dw.discount; var margin = dw.margin
    eachArg(worldSet ++ args.toSeq, usage) {
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
      case "-newslev"    => newsLev = numOr("-newslev", consumeNext)
      case "-newsrevert" => newsRevert = numOr("-newsrevert", consumeNext)
      case "-newsscale"  => newsScale = numOr("-newsscale", consumeNext)
      case "-newsbond"   => newsBond = numOr("-newsbond", consumeNext)
      case "-noiseskew"  => noiseSkew = numOr("-noiseskew", consumeNext)
      case "-newsflip"   => newsFlip = numOr("-newsflip", consumeNext)
      case "-newsbondskip" => newsBondSkip = numOr("-newsbondskip", consumeNext)
      case "-creditregime" => creditRegime = numOr("-creditregime", consumeNext)
      case "-creditregimerate" => creditRegimeRate = numOr("-creditregimerate", consumeNext)
      case "-slowbondinfl" => slowBondInfl = numOr("-slowbondinfl", consumeNext)
      // `-valuepull` names the DIAL, which is the key `worldJsonBody` writes and so the flag
      // `-worldset` synthesizes; `-value` is kept because it shipped.
      case a @ ("-value" | "-valuepull") => valuePull = numOr(a, consumeNext)
      case "-anchors"    => anchorSpec = consumeNext
      // Applied in the pre-scan that seeded `dw`; consumed here so the loop does not reject it
      // as unknown.
      case "-atrelease"  => val _ = consumeNext
      // Likewise: the pre-scan already turned the member into the arguments ahead of these.
      case "-worldset"   => val _ = consumeNext
      case "-worldindex" => val _ = consumeNext
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
      case "-overnight"  => overnight = numOr("-overnight", consumeNext)
      case "-basket"     => basket = intOr("-basket", consumeNext)
      case "-basketbeta" => basketBeta = numOr("-basketbeta", consumeNext)
      case "-basketsector" => basketSector = numOr("-basketsector", consumeNext)
      case "-basketidio" => basketIdio = numOr("-basketidio", consumeNext)
      case "-basketgaps" => basketGaps = numOr("-basketgaps", consumeNext)
      case "-basketdrift" => basketDrift = numOr("-basketdrift", consumeNext)
      case "-macro"      => macroPanel = intOr("-macro", consumeNext)
      case "-macronull"  => macroNull = intOr("-macronull", consumeNext)
      case "-levgain"    => levGain = numOr("-levgain", consumeNext)
      case "-stressscale" => stressScale = numOr("-stressscale", consumeNext)
      case "-levpersist" => levPersist = numOr("-levpersist", consumeNext)
      case "-noiseasym" => noiseAsym = numOr("-noiseasym", consumeNext)
      case "-noiseasymphi" => noiseAsymPhi = numOr("-noiseasymphi", consumeNext)
      case "-noiseasymcap" => noiseAsymCap = numOr("-noiseasymcap", consumeNext)
      case "-jumpresp"     => jumpResp = numOr("-jumpresp", consumeNext)
      case "-stressadapt"  => stressAdapt = numOr("-stressadapt", consumeNext)
      case "-bustamp"    => bustAmp = numOr("-bustamp", consumeNext)
      case "-cyclesd"    => cycleSd = numOr("-cyclesd", consumeNext)
      case "-cycleyears" => cycleYears = numOr("-cycleyears", consumeNext)
      case "-beliefleak" => beliefLeak = numOr("-beliefleak", consumeNext)
      case "-slowshare"  => slowShare = numOr("-slowshare", consumeNext)
      case "-slowvol"    => slowVol = numOr("-slowvol", consumeNext)
      case "-slowlev"    => slowLev = numOr("-slowlev", consumeNext)
      case "-slowphi"    => slowPhi = numOr("-slowphi", consumeNext)
      case "-slowperm"   => slowPerm = numOr("-slowperm", consumeNext)
      case "-slowbeta"   => slowBeta = numOr("-slowbeta", consumeNext)
      case "-volresp"    => volResp = numOr("-volresp", consumeNext)
      case "-volrespphi" => volRespPhi = numOr("-volrespphi", consumeNext)
      case "-volrespattack" => volRespAttack = numOr("-volrespattack", consumeNext)
      case "-volrespcap" => volRespCap = numOr("-volrespcap", consumeNext)

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
    nonNeg("-newslev", newsLev)
    newsBudgetRefusal(newsRate, newsSize).foreach(why => usage(why))
    nonNeg("-newsbond", newsBond)
    if !(newsBondSkip >= 0.0 && newsBondSkip < 1.0) then usage("-newsbondskip is a share in [0, 1)")
    nonNeg("-creditregime", creditRegime); nonNeg("-creditregimerate", creditRegimeRate)
    if !(slowBondInfl >= 0.0 && slowBondInfl <= 1.0) then usage("-slowbondinfl is a share in [0, 1]")
    nonNeg("-satbeta", satBeta); nonNeg("-satidio", satIdio); nonNeg("-rangescale", rangeScale)
    nonNeg("-rangedown", rangeDown)
    if rangeDown > 0.0 && rangeScale <= 0.0 then
      usage("-rangedown requires -rangescale > 0: it shapes the sampled bar")
    nonNeg("-volidio", volIdio); nonNeg("-divyield", divYield); nonNeg("-overnight", overnight)
    if basket < 0 then usage(s"-basket $basket: the name count cannot be negative")
    nonNeg("-basketbeta", basketBeta); nonNeg("-basketsector", basketSector)
    nonNeg("-basketidio", basketIdio); nonNeg("-basketgaps", basketGaps)
    nonNeg("-basketdrift", basketDrift)
    if macroPanel != 0 && macroPanel != 1 then usage(s"-macro $macroPanel: 0 (off) or 1 (the panel)")
    if macroNull != 0 && macroNull != 1 then usage(s"-macronull $macroNull: 0 (the path's own panel) or 1 (a sibling's)")
    if macroNull > 0 && macroPanel == 0 then usage("-macronull needs -macro 1: it is the panel's null, not a panel")
    nonNeg("-levgain", levGain)
    nonNeg("-stressscale", stressScale)
    nonNeg("-noiseasym", noiseAsym)
    nonNeg("-noiseasymcap", noiseAsymCap)
    nonNeg("-volresp", volResp); nonNeg("-volrespcap", volRespCap); nonNeg("-jumpresp", jumpResp)
    nonNeg("-bustamp", bustAmp)
    nonNeg("-cyclesd", cycleSd)
    nonNeg("-beliefleak", beliefLeak)
    if cycleSd > 0.0 && cycleYears <= 0.0 then
      usage(s"-cyclesd $cycleSd needs -cycleyears above 0")
    // WORD FOR WORD the Rust twin's messages: the two CLIs must refuse the same worlds, and
    // `-stressadapt 0` is the one that would otherwise pass -- the spiral's scale never updates,
    // so its stress index reads every session against the seed value.  NEGATED comparisons, like
    // `nonNeg`: NaN parses, and `x < 0 || x >= 1` admits it where the Rust twin's range test
    // refuses it.
    if !(levPersist >= 0.0 && levPersist < 1.0) then
      usage("-levpersist is a persistence in [0, 1)")
    if !(noiseAsymPhi >= 0.0 && noiseAsymPhi < 1.0) then
      usage("-noiseasymphi is a persistence in [0, 1)")
    if !(volRespPhi >= 0.0 && volRespPhi < 1.0) then
      usage("-volrespphi is a persistence in [0, 1)")
    if !(volRespAttack >= 0.0 && volRespAttack < 1.0) then
      usage("-volrespattack is a persistence in [0, 1)")
    if !(stressAdapt > 0.0 && stressAdapt < 1.0) then
      usage("-stressadapt is an EWMA weight in (0, 1)")
    nonNeg("-slowvol", slowVol); nonNeg("-slowlev", slowLev); nonNeg("-slowbeta", slowBeta)
    if !(slowShare >= 0.0 && slowShare < 1.0) then usage("-slowshare is a share in [0, 1)")
    if !(slowPhi >= 0.0 && slowPhi < 1.0) then usage("-slowphi is a persistence in [0, 1)")
    if !(slowPerm >= 0.0 && slowPerm <= 1.0) then usage("-slowperm is a share in [0, 1]")
    if !(newsRevert >= 0.0 && newsRevert <= 1.0) then usage("-newsrevert is a share in [0, 1]")
    if !(newsScale >= 0.0 && newsScale <= 1.0) then usage("-newsscale is a share in [0, 1]")
    if !(noiseSkew >= 0.0 && noiseSkew < 1.0) then usage("-noiseskew is a skew in [0, 1)")
    if !(newsFlip >= 0.0 && newsFlip <= 1.0) then usage("-newsflip is a share in [0, 1]")

    if basket > 0 && basketBeta <= 0.0 then
      usage("-basket requires -basketbeta > 0: a name with no sector leg is not a member of anything")
    if overnight >= 1.0 then
      usage(s"-overnight $overnight leaves the intraday session no variance to run the bridge on; it must be below 1")
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
                  newsRate = newsRate, newsSize = newsSize, newsLev = newsLev, newsRevert = newsRevert,
                  newsScale = newsScale, newsBond = newsBond, noiseSkew = noiseSkew,
                  newsFlip = newsFlip, newsBondSkip = newsBondSkip, creditRegime = creditRegime,
                  creditRegimeRate = creditRegimeRate, slowBondInfl = slowBondInfl,
                  valuePull = valuePull,
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
                  rangeDown = rangeDown, volIdio = volIdio, divYield = divYield,
                  overnight = overnight, basket = basket, basketBeta = basketBeta,
                  basketSector = basketSector, basketIdio = basketIdio, basketGaps = basketGaps,
                  basketDrift = basketDrift, macroPanel = macroPanel, macroNull = macroNull,
                  levGain = levGain, stressScale = stressScale, levPersist = levPersist,
                  noiseAsymPhi = noiseAsymPhi, volResp = volResp, volRespPhi = volRespPhi,
                  volRespAttack = volRespAttack, volRespCap = volRespCap,
                  noiseAsymCap = noiseAsymCap, jumpResp = jumpResp, stressAdapt = stressAdapt,
                  bustAmp = bustAmp, cycleSd = cycleSd, cycleYears = cycleYears,
                  beliefLeak = beliefLeak,
                  slowShare = slowShare, slowVol = slowVol, slowLev = slowLev,
                  slowPhi = slowPhi, slowPerm = slowPerm, slowBeta = slowBeta,
                  noiseAsym = noiseAsym)

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
    // the verdict's own ensemble, which its shorter record horizons are cut from
    val verdictMain =
      if (verdictPaths, verdictYears) == (paths, years) then sims
      else simPaths(w, verdictPaths, verdictYears, seed)
    val verdictSt =
      if (verdictPaths, verdictYears) == (paths, years) then st else measure(verdictMain, verdictYears)
    val verdictRows = fidelityRows(anchors, verdictSt, Some(verdictMain), verdictYears, verdictPaths, seed, w)
    val verdictBanded = bandedOf(verdictRows)

    if emit.nonEmpty then
      val realismBad   = failedInAt(anchors, verdictSt, verdictBanded, GateClass.Realism)
      val mechanismBad = failedInAt(anchors, verdictSt, verdictBanded, GateClass.Mechanism)
      val fidelityBad  = failedInAt(anchors, verdictSt, verdictBanded, GateClass.Fidelity)
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
        + (if w.divYield > 0.0 then 2 else 0) + (if w.overnight > 0.0 then 1 else 0)
        + (if w.basket > 0 then w.basket + 1 else 0)} columns x $sessions sessions, " +
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
    println(f"  volatility clustering  lag  1 ${st.ac1}%6.3f   lag 20 ${st.ac20}%6.3f" +
            f"   (lag 5 ${st.ac5}%.3f   lag 60 ${st.ac60}%.3f)")
    println(f"  vol response to a fall corr(r, |r+k|)  k=1 ${st.lev1}%6.3f   k=5 ${st.lev5}%6.3f" +
            f"   k=20 ${st.lev20}%6.3f   (record -0.09 / -0.08 / -0.04)")
    // The line above is |r| and the line below is r, which is the whole reason both are printed:
    // they are different axes and a world can be right on one and wrong on the other.
    println("  trend persistence      variance ratio " +
            VarRatioLadder.map(q => f"${q}%dd ${vrOf(st, q)}%.3f").mkString("  ") +
            "   (1.0 = no serial dependence)")
    // The rung the ladder cannot see.  REPORTED, never graded: the record's sign flips by era, so
    // the cross-section carries no one value to grade against -- see persistence-2026-09-11.tsv.
    val (rc26, rc54, rc90, rfLo, rfHi) = RetAc1Record
    println(f"                         lag-1 signed  ${st.retAc1}%+.4f   (record: CRSP $rc26%+.3f" +
            f" century, $rc54%+.3f from 1954, $rc90%+.3f from 1990;" +
            f" every modern fund $rfLo%+.3f..$rfHi%+.3f)")
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
    st.basket.foreach { b =>
      println(f"  basket names           vol ratio ${b.nameVolRatio}%.2f   gaps/yr ${b.nameGaps}%.2f   d20 ${b.nameD20}%.3f (spread ${b.nameD20Spread}%.3f)")
      println(f"  basket aggregate       corr ${b.aggCorr}%.3f   beta ${b.aggBeta}%.3f   vol ratio ${b.aggVolRatio}%.2f")
      println(f"  basket structure       pair corr ${b.pairCorr}%.3f   idio share ${b.idioShare}%.3f   tail coincidence ${b.tailCoincidence}%.3f   pair corr worst/mid ${b.pairCorrWorst}%.3f/${b.pairCorrMid}%.3f")
    }
    st.open.foreach { os =>
      println(f"  bar open               overnight share ${os.overnightShare}%.3f   gap share worst-1%% ${os.worstGapShare}%.3f vs all ${os.allGapShare}%.3f")
    }
    st.macroPanel.foreach { ms =>
      println(f"  macro panel            ${ms.episodes}%d pooled 20%% episodes; warning share = median fraction of the log decline still ahead at the first firing")
      if ms.sibling then
        println("    NULL PANEL: the columns are a sibling path's, decoupled from this price -- these readings are the")
        println("    no-edge level, and the macro rows do not grade them")
      println(f"    ${"member"}%-12s ${"ac1"}%7s ${"acK"}%7s ${"r2fwd60"}%8s ${"warn20"}%7s ${"fired"}%6s ${"lag"}%5s ${"lag10"}%6s ${"fired10"}%8s ${"pre"}%5s ${"p10"}%8s ${"p50"}%8s ${"p90"}%8s")
      for j <- ms.members.indices do
        val m = ms.members(j)
        println(f"    ${MacroK.Columns(j)}%-12s ${m.ac1}%7.4f ${m.acK}%7.4f ${m.r2fwd60}%8.4f ${m.warn}%7.3f ${m.warnFired}%6.2f ${m.lag}%5.0f ${m.lag10}%6.0f ${m.fired10}%8.2f ${m.prePeak}%5.2f ${m.lvl10}%8.2f ${m.lvl50}%8.2f ${m.lvl90}%8.2f")
      println(f"    leverage hazard        20%% peak within a quarter x${ms.hazard20q}%.2f (unconditional ${ms.p20q}%.3f)   within a year x${ms.hazard20y}%.2f   10%% within a quarter x${ms.hazard10q}%.2f")
      println(f"    per path (p5 / p50 / p95)  hazard x${ms.hazardSpread.p5}%.2f / ${ms.hazardSpread.p50}%.2f / ${ms.hazardSpread.p95}%.2f   slope inverted ${ms.invShareSpread.p5}%.2f / ${ms.invShareSpread.p50}%.2f / ${ms.invShareSpread.p95}%.2f   cond r2fwd60 ${ms.memberSpread(2)._1.p5}%.3f / ${ms.memberSpread(2)._1.p50}%.3f / ${ms.memberSpread(2)._1.p95}%.3f   cond build-up ${ms.memberSpread(2)._2.p5}%.2f / ${ms.memberSpread(2)._2.p50}%.2f / ${ms.memberSpread(2)._2.p95}%.2f")
      println(f"    slope inverted         share ${ms.invShare}%.3f   mean spell ${ms.invDur}%.1f sessions")
      println(f"    ivol premium           vrp ${ms.vrp}%.3f (log)   r2 vs forward realized ${ms.r2rv}%.3f")
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
            f"   (record proxy: sd log CAPE 0.24-0.41, peaks +70-100%%)   drift late-early ${st.gapDrift}%.3f" +
            f"   spread early/late ${st.gapSpreadDrift}%.3f")

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
    println("    NOTE: `real` is the record, read the way the model reads a path.  A row with a record")
    println("      band is judged by where the model falls among one-year-block resamples of that")
    println("      record (`model@`, against its joint band); `target` is printed where the loss")
    println("      grades against something else.")
    verdictRows.foreach { r =>
      val flag = if r.miss then "  <-- MISS" else ""
      val judgement = (r.ratio, r.pctile) match
        case (Some(x), _) =>
          val band = r.recordBand.fold("") { (lo, hi) =>
            val at = r.recordPctile.fold("n/a")(p => f"$p%3d%%")
            f"   model@ $at%s of $lo%.2f..$hi%.2f"
          }
          // printed only where it differs past rounding: vintage noise is not a difference
          val target =
            if math.abs(r.target - r.real) > 0.01 * math.max(math.abs(r.real), math.abs(r.target))
            then f"   target ${r.target}%.2f"
            else ""
          f"ratio $x%5.2f$band%s$target%s"
        case (None, Some(p)) => f"record@ $p%3d%% of ${r.horizonYears}%dy histories (n=${r.nHistories}%d)"
        case (None, None)    => f"record@  n/a — ${r.nHistories}%d histories, needs $ExtremeMinHistories%d"
      println(f"     ${r.name}%-22s model ${r.model}%8.2f   real ${r.real}%8.2f   $judgement%s$flag%s")
    }

    if validate then
      val checks = gateChecksAt(anchors, verdictSt, verdictBanded)
      val bad    = GateClass.values.map(c => c -> failedInAt(anchors, verdictSt, verdictBanded, c)).toMap
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

## v0.19.2 — unreleased

**CHANGED — `marketSim`/`market_sim`: the bond's refuge mechanism is rebuilt; every published number
moves. `-flight` is removed and is rejected, not reinterpreted.**

The bond spent 87% of sessions more than 10% below its running peak where a real bond of the same
volatility spends 45%. Measured cause: **75% of that time was spent recovering from a spike**, in
episodes where inflation pressure never rose and the rate never left 4.6-4.8%. The policy rate ran
4.2% → 0% → 4.2% inside a quarter, 23 times a century (real holds: 7.0 years in 2008-15, 2.0 in
2020-22, ~15 in 1932-47). `flight` was a cut *speed* with no bound, so a stress episode drove the
rate to the floor and the same `rateSpeed` pulled it straight back, marking a bond peak ~65% above
the normal level that then took a decade of carry to regain.

The rate cut is now a capped accommodation *stock* — `-easing` (cap, in rate points), eased in over
~2 months, withdrawn at `-unwind` — and the bond gains `-refuge`, a duration-scaled flight-to-quality
bid. Both are needed: capping the cut alone fixes the drawdown profile and removes the crash rally
entirely, because the rally *was* the spike.

```
                                    0.19.1 + W7a       0.19.2             real
  bond >10% below pk               0.868 (1.91 FAIL)  0.553 (1.24)        0.510 (TLT)
  bond infl-crash                -29.47 (1.18)      -23.95 (0.96)       -25.00
  bond growth-crash               13.32 (0.67 MISS)   8.41 (0.42 MISS)   20.00
  crashes/century                 24.85 (1.20)       27.40 (1.32)        20.70
  clustering lag 1 / lag 20         1.08 / 1.07 x      1.06 / 1.08 x      0.299 / 0.225
  equity >5 / >10 / >20 below pk   0.50  0.34  0.16   0.48  0.32  0.15   0.447 0.315 0.169
  worst crash %                  -84.43 (1.49)      -81.61 (1.44)       -56.80
  equity vol %                    14.73 (0.92)       14.39 (0.90)        16.00
  kurtosis                        12.78 (0.46 MISS)  11.84 (0.42 MISS)   28.00
```

**The world clears all three gate classes — realism, mechanism and fidelity — the first default to
do so.** It does at duration 5.7 and 25.0 as well, so an Aggregate-like bond is representable, which
is what this line of work was opened for.

**Default dials moved with the mechanism, not for their own sake.** Capping the cut removed a
discount-channel cushion the equity leg was getting in crashes; at unchanged dials the crash rate
went 1.20 → 1.38 and clustering 1.08 → 1.13. `depth` 16.3 → 16.6 buys back a third of the crash
rate; `stress` 5.4 → 5.1 returns clustering to 1.06 — the same trade 0.19.1 made against the same
dial — at a cost of kurtosis 0.46 → 0.42, a recorded scope exclusion either way. The remaining crash
regression is the mechanism's price and is not tuned away.

The default is not the calibration search's optimum and does not need to be: across 8,000 samples
the best training loss re-scored at 3.120 on the held-out seed, against the default's **2.588**, the
best holdout figure in the run.

**Who this affects.**

*Improves* — anything that reads a bond's distance from its running peak, which was unreadable
before (the gate said so) and is now in band at every duration tested; bond behaviour conditional on
inflation regimes (1.18 → 0.96); studies of aggregate or intermediate bond funds, which had no
admissible world at all; the two shallow equity depth rungs and the worst-crash overstatement, all
slightly closer.

*Degrades* — crash-frequency and crash-conditioned work: crashes arrive 1.32x too often against
1.20x, so any per-crash hazard is **over-sampled**. Refuge sizing: the bond's growth-shock rally
falls 13.3% → 8.4% against a 20.0 anchor, so a refuge sleeve modelled on this will look **weaker**
than a long Treasury did in 2008 — though 8.4% sits nearer TLT's six-episode median of 9.65% than
the old 13.3% did. Absolute volatility thresholds: the equity level errs 10% **low** against 8%
before, so such a threshold is crossed even less often than it should be. Tail-day magnitudes:
kurtosis 0.46 → 0.42, already excluded from scope.

*Unchanged* — rank comparisons between rules, which the world sweep governs rather than the default.
A ranking that survives `-strategies` did not depend on this move. `-power` and `-buffer` read the
same world but ask questions the move does not bear on directly.

**Migration.** `-flight X` now exits with an error naming its replacements. It is not silently
reinterpreted: it was a cut speed per year and `-easing` is a cut cap in rate points, so every
recorded `-flight` value is wrong by two orders of magnitude under the new mechanism and would still
have produced a plausible-looking run. `-emit` sidecars gain `easing`, `unwind` and `refuge` and
lose `flight`; `-calibrate` now searches thirteen parameters. `-releases` gains a 0.19.2 row.

**A gate gap this found, recorded because it is not fixed.** Nothing in the acceptance gate
constrains the policy rate's own distribution. A one-line variant reached the bond-depth band exactly
while putting the rate below 1% for half the century, against a real ~19%, and reported PASS. The
fix shipped here does not do that — the rate's median is 3.3% and it is below 1% on 1.5% of
sessions — but the gate would not have caught it either way.

## v0.19.1 — 2026-08-21

**CHANGED — `marketSim`/`market_sim`: new default world; every published number moves**

The calibration search sampled eight parameters and could not vary `depth`, `trendShare`, `drift` or
`crowdImpact` — the four strongest levers on the defects the other eight cannot reach. The defaults
inherited a blind spot rather than a frontier. With all four in the search:

```
                                    old default        new default        real
  crashes/century                 35.30 (1.71 MISS)  24.85 (1.20)        20.70
  equity >5 / >10 / >20 below pk   0.64  0.46  0.22   0.50  0.34  0.16   0.447 0.315 0.169
  return per vol                   0.59 (0.86)        0.79 (1.14)         0.69
  kurtosis                         7.71 (0.28 MISS)  12.78 (0.46 MISS)   28.00
  median depth %                 -24.83 (0.92)      -25.16 (0.93)       -27.10
  bond growth-crash                7.81 (0.39 MISS)  13.32 (0.67 MISS)   20.00
  bond infl-crash                -16.44 (0.66 MISS) -29.47 (1.18)       -25.00
  equity vol %                    16.62 (1.04)       14.73 (0.92)        16.00
  clustering lag 1 / lag 20        0.24 / 0.18        0.32 / 0.24         0.27 / 0.20
  worst crash %                  -79.26 (1.40)      -84.43 (1.49)       -56.80
  bond vol %                      14.78 (1.14)       17.12 (1.32)        13.00
  bond >10% below pk               0.83 (1.63 MISS)   0.87 (1.70 MISS)    0.510
```

Eight of fifteen rows read closer to real, six further, one unchanged; aggregate |ratio−1| 5.17 →
3.68. The equity leg passes every fidelity band it has, and the verdict's only failure is the bond's
10% depth rung. Three MISS flags remain — kurtosis, `bond growth-crash` and the bond depth rung —
and `bond infl-crash` left the set.

**`stress` is deliberately off the objective's minimum.** The loss minimises at `-stress 5.9`
(3.128 against 3.280, about 0.13 across five scoring seeds). The shipped 5.4 buys back a regression
the objective does not weigh heavily enough to see: the liquidity spiral is one amplifier producing
volatility, fat tails *and* volatility clustering together — `stress` alone moves lag-1 clustering
from 0.160 at 3.4 to 0.420 at 7.0 — so raising it to fix kurtosis drove clustering from an
almost-exact 0.90 to 1.33. At 5.4 the split is: clustering 1.20, the 10% rung 1.06, crash rate 1.20,
worst crash 1.49 (back under the MISS threshold); paid for with kurtosis 0.46, equity volatility
0.92, return per vol 1.14, median depth 0.93 and the 20% rung 0.92.

**Kurtosis and clustering cannot both be right.** `-stress 7.5` reaches kurtosis 26.4 against a real
28 — and clustering 1.65, failing the realism band. That is the measured reason the kurtosis MISS
stands, and it is more precise than the header's old "no slow valuation cycle": the missing cycle is
why there is no *second* channel for tails, not why this one cannot reach them.

**Who this affects.**

*Improves* — rules keyed to a shallow distance from a running peak (the three equity depth rungs
move into band, so the level is readable for the first time); crash-frequency and crash-conditioned
work (1.71 → 1.20); levered tail estimates, which were flattered by kurtosis at 0.28; and bond
behaviour conditional on inflation regimes (0.66 → 1.18).

*Degrades* — anything that forecasts volatility, because clustering at 1.20 makes volatility more
predictable here than in the record; rules keyed to an absolute volatility threshold, because the
level now errs 8% **low** and such a threshold is crossed less often than it should be; and any
reading of bond volatility (1.14 → 1.32) or the bond depth rung (1.63 → 1.70).

*Unchanged* — rank comparisons between rules, which the world sweep governs rather than the default.
A ranking that survives `-strategies` did not depend on this move.

**Two known bias directions, recorded because nothing else nets them away, and they point opposite
ways.** Volatility clustering at 1.20 makes volatility more predictable here than in the record,
which flatters any rule that forecasts it. Against that, worst crash at 1.49 puts index paths near
−84% where the real worst is −56.8%, and no levered fund survives those: ruin rates for levered
sleeves read off this model are **upper bounds, not estimates** — usable and conservative, but not
quotable as levels.

The previous world is reproducible in full: `-depth 12 -trendshare 0.30 -drift 0.100 -stress 3.4
-value 0.015 -volofvol 0.028 -flight 0.38 -duration 13.5 -inflsize 0.07 -discount 4.0
-margin 0.0008 -crowdimpact 0.06`.

**ADDED — return per unit volatility, as a fidelity target and a two-sided band**

Volatility was gated at 8-25% — half to 1.5x the real value, a band that never bound inside the
search space — and return per unit volatility was not measured at all. Between them they left the
depth profile buyable: `drift` is the only knob that moves it at constant volatility, so a search
free to raise drift buys the depth rungs with a Sharpe no 20-year stretch of the real record
produced, and no check notices, because `no runaway drift` only asks `|annRet| < 30%`.

`return per vol` targets 0.69 — Ken French US total market, 1954-2026, restated into this report's
own units (annualised LOG return over the no-mean-subtraction volatility, on daily data; a CAGR read
as a simple rate and a monthly-derived volatility each inflate the ratio). The band is 0.50-0.85:
below the 1926-2026 reading of 0.55, above the anchor, and below the most favourable non-overlapping
20-year block the record produced. Volatility gains a second band at 14-18%.

Both are `fidelity`, not `realism`. Realism is unconditionally required, so a band there would make
the model's own OFF-worlds inadmissible in every report — `low growth` runs at 0.35 and
`shallow market` at 19.6% volatility, both deliberate. Class costs nothing as a search constraint:
the calibration loss counts 0.5 per failed check whatever the class. The band is not decorative — a
world at 0.92 placed third on the held-out seed in the search that produced these defaults, and is
refused.

**ADDED — `depth`, `trendShare`, `drift` and `crowdImpact` in the `-calibrate` search**

Twelve parameters, not eight. `depth` carries crash frequency (at fixed stress, 12 to 24 takes it
from 35 to 13 per century) but moves volatility in lockstep; `drift` moves the depth profile at
constant volatility, which is why it could not be searched before the band above existed; and
`crowdImpact` could not usefully be searched at all until it stopped being inert in the default
world — it is the parameter that opens the corner the shipped defaults sit in.

**FIXED — three sweep arms had stopped bracketing their own baseline**

The world sweep's job is to turn every conclusion into a curve over a parameter, which requires the
two arms to straddle the baseline. Moving the defaults silently broke that for three pairs, and
nothing in the output said so: `few trend followers` (0.15 against a baseline of 0.06) had **2.5x the
baseline's trend followers**, `deep market` (15.0 against 16.3) was **shallower** than the baseline,
and `calm volatility` (0.010 against 0.011) had become a near-copy of it.

Those three pairs are now relative — `trendShare` /3 and x3, `depth` x0.8 and x1.25, `volOfVol` x0.5
and x2 — joining `stress`, `valuePull` and `inflSize`, which already were. A multiplier below 1 and
one above cannot stop straddling the base, so bracketing is structural rather than a thing to
re-check after every recalibration. `beta`, `drift` and `rateMean` keep absolute points: they name
economic conditions rather than perturbation sizes, and all three still bracket.

Worth knowing when reading the trend arms: the mandate is a spring, so the **realized** share moves
far less than the mandate. The new arms span 0.19-0.30 realized against the baseline's 0.22.

**ADDED — reflexive worlds in the `-strategies` sweep, ranked in their own panel**

`sweepWorlds` varied nineteen parameters and not one of them was the crowd, so every rank-stability
result ever produced was really "ranks are stable across market character, *holding the crowd fixed
and non-reactive*" — with the second half invisible. That qualifier matters here rather than being
pedantry: the existing crowd evidence says a vol-scaling crowd turns every trend rule's edge
negative, which is a rank inversion, and it is the one axis the sweep never varied.

Two worlds, varying **different axes** — a separation only possible now that the momentum crowd has
a strength dial, since a mode entry that states no strength silently picks one:

```
reflexive: crowd runs a vol rule   mode      -crowd volscaled at the default strength
reflexive: crowd pressed hard      strength  -crowdimpact 0.12 under momentum (0.25 fails the gate)
```

**They are ranked separately and never pooled.** A character world varies what the market is like;
a reflexive world changes who is trading. One "stable across 21 worlds" that concealed an inversion
in the two worlds most able to produce one would be worse than not running them, so the split is
structural: `RANK STABILITY` counts character worlds only, `REFLEXIVITY` reports the reflexive ranks
beside the character range and flags any rule that moves outside it, and the flight-to-safety and
refuge tables stay on character worlds. Per-world detail marks reflexive worlds `[REFLEXIVE]`.

Scoped to `-strategies`. `-power` and `-buffer` pass `withReflexive = false` and are unchanged —
reflexivity is the point in the rank table and a second-order effect on dispersion elsewhere.

**FIXED — the momentum crowd had no strength dial (`-crowdimpact` was inert)**

`Crowd::Momentum` — the default — never read `crowdImpact`. `-crowdimpact 0.01` and `-crowdimpact
0.50` produced byte-identical output, so the one experiment this simulator offers that resampling a
real series cannot ("what happens when the crowd runs my rule") had no strength control in the world
every report defaults to. The momentum flow now scales by `crowdImpact / 0.06`; the ratio is what
enters, so the default divides to a bit-exact 1.0 and no shipped number moves.

It survived four releases because the crowd had no binding diagnostic, which the header requires of
every mechanism — a dead knob is indistinguishable from a live one with a small effect. `-validate`
now prints crowd flow in bp/session and as a share of the noise term: 0.60 bp (0.9%) at
`-crowdimpact 0.01`, 4.09 bp (5.8%) at the default, 42.18 bp (60.3%) at 0.25.

Related, and measured rather than assumed: on the new default world **all seven crowd modes pass
realism and mechanism** — `momentum`, `trend60/100/200/300/500` and `volscaled` — where the plan
recorded every `trendNNN` world as gate-failing. The crowding result is quotable in seven admissible
worlds instead of one.

**FIXED — the fidelity table named one window for anchors drawn from four**

The header read `S&P 1954-2026 equity; long-Treasury refuge`. The three equity depth rungs are SPY
1993-2026, and `return per vol` is CRSP 1954-2026. Measured over the *stated* window the depth rungs
read 0.436 / 0.269 / 0.126 against the 0.447 / 0.315 / 0.169 targeted — so anyone re-deriving the
anchors from the label would get materially different numbers at the 10% and 20% rungs and conclude
the model had drifted. The anchors are defensible; the label was not. Each anchor group is now named.

Recorded beside the targets while fixing it: the real depth profile is strongly window-dependent —
the 10% rung reads 0.269 over 1954-2026, 0.315 over 1993-2026 and 0.386 over 1926-2026. The ±0.10
gate bands span that spread, which is part of why they pass, so a passing depth rung is not
agreement with any particular window.

**ADDED — the depth profile validated against a series the calibration never saw**

The SPY rungs are fit targets, so agreement with SPY is a calibration check, not evidence. Against
CRSP value-weighted, 33-year windows inside 1954-2026: model 0.49 / 0.33 / 0.13 against a real
median of 0.451 / 0.291 / 0.151 and real ranges of 0.405-0.507 / 0.219-0.346 / 0.084-0.184. **All
three rungs land inside the range of real 33-year windows** — the shallow ones about 10% above the
median, the deep one about 14% below. A level bias in both directions, surviving the gate, inherited
by any rule scored on a distance-from-peak threshold.

The check is not fully independent: `return per vol` is anchored on CRSP 1954-2026, and
return-per-unit-volatility is the strongest single driver of the depth profile. It is independent in
the *shape* of the drawdown distribution, not in the level of the quantity that most determines it.
SPY cannot serve at all — its rungs are the targets.

**FIXED — help text is generated from the defaults, and every flag now states one**

Three `-help` defaults were stale on arrival (`-inflsize` read 0.06 against 0.07, `-discount` 6
against 4, `-duration` 15 against 13.5) and six flags stated none at all. Hand-maintained help that
restates a default is a second copy of a constant — the same failure class PARITY.md documents — so
adding six more copies would have enlarged the surface rather than fixing it. There is now one
source, `Defaults`, a `World` value that `main` seeds its CLI variables from and that `usage`
interpolates. Every flag states its default and none can drift.

The cross-twin half of that check already existed and is worth naming: bare `-emit` writes the
**default** world, and its sidecar lists every `World` field. A constant updated in one twin and not
the other shows up there as `"flight": 0.480000` against `"flight": 0.380000` — named, not inferred
from the fourth decimal of a statistic. Byte-diff `-emit` first; the statistical modes show the
consequence, the sidecar shows the cause.

## v0.19.0 — 2026-08-21

**ADDED — `marketSim`/`market_sim`: `-emit` exports the full model state, named and provenanced**

An emitted path is the whole external interface — a consumer grades its own rules on it without
importing either twin — and it carried three columns. It now carries nine: `date price bond rate
cpi liq bliq fundamental inflPress`, with a header row. Without `rate` a rule that de-risks to cash
is mis-scored; without `cpi` a real-terms question is unanswerable; `bliq` is the bond market's own
slippage multiplier, computed since the two-market rewrite and discarded until now; `fundamental` is
an oracle label (fundamental-led versus liquidity-led decline) that no real series can supply.

```
-emitpath N     which path index (default 0); path k is seed + k*7919 and needs no larger run
-emitall        every path to F-000.tsv, F-001.tsv, ... each with its sidecar
-emitstart D    YYYY-MM-DD (validated as a real calendar date), stepping by WEEKDAYS so the
                file joins a real dated series
-emitgate P     paths in the ensemble that decides the verdict (default 200; 0 = the sample)
```

`-emit F` also writes `F.json`: the world's every field, the base seed and stride, the path index
and its derived seed, the calendar, the gate verdicts with their named failures, and the fidelity
ratios. A warning printed to stderr does not survive the file being moved, and an ensemble that
cannot be named cannot be inventoried.

Default dates step `365/252` days from 1900-01-02 and land on weekends, so an emitted path could
never be joined to a real dated series. `-emitstart` steps weekdays instead — no holiday calendar.

**ADDED — the simulator ships in both published artifacts**

`uni.apps.MarketSim` is compiled into the jar — run it from the published library with
`scala-cli run --jar uni_3-0.19.0.jar --main-class uni.apps.MarketSim -- -validate`. The crate now
packages `examples/` (the nine cross-language demo pairs; they were excluded), so
`cargo install vastblue-uni --example market_sim` builds the simulator from crates.io. The two
shipped forms are byte-identical in output, and a test (`ScriptTwinSuite`) pins the packaged Scala
object to `jsrc/marketSim.sc` — they may differ only in the two-line shebang/package header.

**CHANGED — the acceptance gate reports three verdicts, and `-gate` takes a set of classes**

"bond vol 7-20%" says *this world is not a market*; "bond spiral engages, not always" says *a
mechanism is inert here*; "equity >10% below peak 0.215-0.415" says *this quantity's level cannot be
read here*. The first invalidates every conclusion, the second only conclusions that lean on the
named mechanism, the third only conclusions that read a level — a time-out-of-market, a percentile
threshold, a drawdown-conditioned hazard. Under one verdict the duration-6y world, which passes
every realism band, was excluded from every pooled panel.

`-validate` prints the three groups separately, then a `verdict:` line naming any inert mechanism
and any unreadable level. `-gate` takes the set of classes a report requires and sets admissibility
for both the `-validate` exit code and the world sweeps behind `-strategies`, `-power` and
`-buffer`:

```
-gate realism             is this a market
-gate realism,mechanism   ...with its mechanisms live      (the default; unchanged admissibility)
-gate realism,fidelity    ...and this quantity's LEVEL readable
-gate all                 everything
```

Realism is always implied — `-gate fidelity` means `realism,fidelity` — so no configuration admits
a non-market. The default is `realism,mechanism`, the historical verdict, so every existing report
keeps its output and its exit code.

**ADDED — the drawdown-depth profile is measured, targeted and gated**

The share of sessions spent more than 5%, 10% and 20% below the running peak, on both markets.
Volatility, maximum drawdown and underwater fraction can all match a real series while this does
not, and nothing noticed: one series drifts far below its peak and stays, the other hugs it and
makes new highs. Every rule that reads distance from a running peak is a different rule on the two.

Four of the six rungs carry a measured real anchor and enter the fidelity targets and the gate —
three equity rungs from SPY 1993-2026, the bond's 10% rung from a clean TLT total-return series. The
bond's 5% and 20% rungs are reported but not targeted; interpolating them would manufacture an
anchor. The four targets also enter the `-fitness`/`-calibrate` objective (weighted log-ratio terms
plus 0.5 per failed gate check, like every other target), so a `-calibrate` re-run now searches
toward the depth profile. `-drift` and `-ratemean` join the CLI, so every `World` field the sweep
moves is now dialable.

Measured, 19 worlds × 60 paths × 33 years: every world is too deep at the rungs peak-relative rules
use, and **no world passes `-gate realism,fidelity`**. `drawdownRule(10, 0.0)` cuts exposure exactly
when depth exceeds 10%, so its share of sessions out of the market IS the gated quantity: 0.454
against a real 0.315. The liquidity spiral is the mechanism — `-stress 0` puts the bond at 0.546
against a real 0.510 — but turning it down drops volatility to 12.3% and kurtosis to 3.39, failing
the `kurtosis 4-30` band. Depth trades directly against the tail calibration.

**FIXED — every short `-emit` warned that the world had failed the gate**

`-paths 1 -years 20 -emit` reported all four mechanism checks failed for a world that passes every
check at 200 paths: they are conditional on crash episodes, which one short path cannot measure.
Every export carried a false alarm, which is worse than no warning. The verdict now comes from an
ensemble of the same world (`-emitgate`, default 200 paths); `-emitgate 0` judges by the sample.

**FIXED — malformed CLI input crashed, or silently substituted a default**

Rust's parse-or-default argument handling silently substituted the default for any unparseable
number — `-paths 1O0` ran 200 paths with exit 0 — and Scala died on the same inputs with raw
exceptions. Both twins now reject every malformed numeric argument with a named error, and bound
`-paths`/`-years` at 1 (0 crashed both), `-seed`/`-emitpath`/`-emitgate` at 0, `-crowd trendN` at
N > 0. Error exit codes keep each twin's convention (Rust 2, Scala 1); success outputs are the
byte-parity surface.

**FIXED — a sweep with no admissible world threw in Scala and printed zeros in Rust**

`-strategies` with zero worlds past the gate raised `empty.min` on one side and an all-zeros rank
table on the other, which reads as "every rule tied". Both now say no world qualified and skip the
ranks. Reachable before (`-strategies -single -crowd trend200`), routine with `-gate ...,fidelity`.

**CHANGED — `csvRows` and the streaming readers return rows as parsed; padding removed**

0.16.0–0.18.x padded every short row to a common width. A padded cell is `""`, which no
caller can tell from a genuinely empty one, and the padding erased per-row arity — the
channel that distinguishes header, data and footer rows in a ragged file (a Fidelity
export: header 16 cells, data 17 via a trailing comma, quoted disclaimers at 1). All four
readers — `csvRows`, `csvRowsStream`, `csvRowsAsync`, `csvRows(onRow)` — now report rows
exactly as parsed, and agree row for row. Blank-row dropping is unchanged.

Rectangular output is opt-in: `FastCsv.rectangular(p.csvRows)` reproduces the old output.
The matrix loaders are unchanged and still rectangular — `loadSmart` already padded for
itself, and `loadCSV` now does too (to the file's widest, where it previously took a width
from the first 100 rows and truncated anything wider that came later).

Same change in Rust (`upath::csv`): `csvRows` no longer wraps `rectangular`, the streaming
width window is gone, and `matcsv` pads for itself.

**ADDED — `p.csvSchema` / `FastCsv.schema`: what shape is this file?**

An arity histogram plus the modal arity (`CsvSchema(widths, arity)`; ties resolve wider, so
no field is voted away). A report, never a transformation: when the modal call is wrong, the
outvoted widths are there in `widths` and no row was altered. `p.csvSchema` streams — one
pass, constant memory, safe on files too large for `csvRows` — and `schema` takes any
`IterableOnce`. `UPath::csvSchema` in Rust. Both languages' modal call is pinned by a
committed `schema` reading in the parity fixture.

**FIXED — a mid-read I/O failure no longer masquerades as end-of-file**

`csvRowsAsync` swallowed a producer-thread exception and delivered a clean but truncated
stream; it now rethrows on the consumer, agreeing with `csvRowsStream`. Rust's streaming
reader flushed the parser's partial row on a read *error* as though the file ended there,
fabricating a torn half-row; it now ends the stream without it (`try_csv_rows` still
reports the error).

**ADDED — a ninth demo pair, `matdcalc`: the `MatD` core in both languages, byte-identical**

`jsrc/matdcalc.sc` ↔ `rust/examples/matdcalc.rs`, written to read line for line alike: one
seeded 4×3 matrix through creation, views and gathers, broadcasting, reductions, the pinned
matmul, masks and `where`, exact `inverse`/`determinant`/`solve`, `svd`,
`sort`/`argsort`/`percentile`/`rolling`/`diff`/`nlargest`, stacking, CSV — the same output on
any machine (`diff` is the test; see `docs/DemoPairs.md`).

**ADDED — README version badges**

Maven Central (`org.vastblue:uni_3`) and crates.io (`vastblue-uni`) badges under the title,
served by shields.io from the repositories themselves — so they show the released version
even while Scaladex's index lags (its listing stopped at 0.14.1; scalacenter/scaladex#1769).

## v0.18.0 — 2026-08-18

**ADDED — Rust: `Mat[Big]` (`MatB`), and `Mat<T>` — the port of `Mat` is complete**

`rust/src/udata/mat.rs` is generic now: `Mat<T>` carries the descriptor, views, slicing,
transpose, stacking and gathers, `MatD = Mat<f64>` keeps every numeric method exactly as
it was (the fixture did not move a bit), and `MatB = Mat<Big>` (`udata::matbig`) adds
the exact-decimal numerics: `+ − × ÷` (matrix, scalar, broadcasting), `neg abs power sqrt
exp log`, `sum mean std variance norm cumsum min max argmin argmax` and the axis family,
the masks (`gt lt gte lte eqTo neTo hasNaN`, `applyMask`), `matmul`, LU
`inverse`/`determinant`/`solve`, `trace`/`diagonal`, `sort`/`argsort`, `toMatD`/`fromMatD`,
`fromArray2` (from the loaders), `csvText`. Every one is the JVM's sequential fold in
`Big` arithmetic, so results agree to the last decimal digit and scale; 293 `bm.*` fixture
rows pin them as `BigDecimal.toString` text, a NaN-injected variant alongside.

**ADDED — Rust: `uni::uproc`, the subprocess API — nothing is Scala-only any more**

`run(&cmd) → ProcResult` (`text lines ok toOption orElse headOnly takeOnly cmd`, `orLog`
for Scala's `!!`, `orFail → Result<_, i32>` for `failFast`), `runLines`/`runStream`
(callbacks on the caller's thread), `proc(&cmd).cwd().env().stdin().timeout().run()/stream()`,
`execLines` as a lazy iterator, and the tool family `bashExe pythonExe unameExe uname isWsl
osType hostname whereInPath` — with the routing table that is the API's reason to exist:
`.sh` through bash on every OS, `.py`/`.sc`/`.bat`/`.ps1` through their interpreters and
`.exe` appended on Windows (`route_cmd(cmd, is_windows)`, both platforms unit-tested
anywhere). A program that cannot start is status −1 with the message on `stderr`; a
timeout is −1. Scala's `where` is `whereExe` (a Rust keyword) returning `Option`.
`SubprocessAPI.md` gains the Scala → Rust table and a compiled example; the Rust scripting
guide a Subprocesses section; `checkRustDocs.sh` now also builds and runs the Rust blocks
the Scala guides under `docs/` carry.

**CHANGED — `uni.plot` renders SVG itself and opens the browser; the Rust crate has the same charts, byte for byte**

`plot`, `scatter`, `hist`, `bar`, `heatmap`, `boxPlot` and `pairs` keep their names and
parameters but no longer go through XChart or AWT: the library draws the SVG (axes, 1/2/5
ticks, bins, quartiles, layout) and either writes it — `saveTo` now yields `<name>.svg`, or
a page when it ends in `.html` — or writes a temp page and shows it in a **standalone
window** of the default browser: the OS's registered HTML handler (Windows URL association,
macOS LaunchServices, `xdg-settings`), launched in app mode for the Chromium family or with
`-new-window` for Firefox; the PATH is probed, Edge last, when the default cannot be read.
`UNI_PLOT_WINDOW=tab` gives a browser tab, `UNI_PLOT_BROWSER=<name-or-path>` picks the
browser. Each method has a `*Svg` twin returning the text (`plotSvg`, `histSvg`, …).
Headless runs never fail: with no opener, or `UNI_PLOT_NO_OPEN` set, the page's path is
printed. `PlotStyle` is
unchanged (`seriesColors` on a heatmap become the gradient stops); log axes apply to `plot`
and `scatter`. The XChart renderer remains for one release as `uni.plot.xchart` (PNG,
Swing windows) and will be removed with its dependency.

The Rust crate gains `uni::uplot` — `MatD::{plot, scatter, hist, bar, heatmap, boxPlot,
pairs}` and the `*Svg` twins, `PlotStyle`, `Color`, `Font`, and one option struct per chart
(`PlotOpts` … with `Default`) in place of Scala's named parameters — producing the **same
SVG bytes** for the same matrix: coordinates through `floor(x·100+0.5)`, tick labels
through integer arithmetic, decades by repeated ×10, no font metrics, no `pow`, and the
log axis through the renderer's own `log10` (bit-exact exponent split, odd-power series,
Cody–Waite ln 2), so no libm call is made on either side. 98 new
`pl.` fixture rows (14 charts × 7 shapes, FNV of the SVG text) pin it in
`MatParitySuite`/`mat_parity`; `-Duni.plot.dump=<dir>` (Scala) and `UNI_PLOT_DUMP=<dir>`
(Rust) write every SVG for a diff. `PlotSvgSuite` and the Rust unit tests pin the primitives
by value. Docs: `PlotGuide.md` rewritten for the new delivery, Charts sections in both quick
starts and the cheat sheet, `PARITY.md` row; `plot` leaves the "no Rust analog" list.

**CHANGED — docs split: `docs/MatDBenchmarks.md` and `docs/BreezeComparison.md`**

The migration table, the MatD | Breeze column pair for every cheat-sheet section (97 rows),
and the three benchmark tables against Breeze now live in one page for readers coming
from Breeze. The NumPy | Scala | Rust tables and the regeneration recipe move to
`docs/MatDBenchmarks.md`, so `MatDCheatSheet.md` is the cheat sheet from its first line —
MatD | NumPy | R | MATLAB; the README's core-operations section carries the current
three-language geometric means and points at all three pages.

**CHANGED — Scala docs: every ```scala block is a complete script, and the doc gate runs them**

README.md and docs/*.md carried 74 code fragments — snippets that named values set up in
the prose and could not be pasted into a terminal. Every one is now a complete
scala-cli script (134 in total; the two `using dep`/`libraryDependencies` lines are the
only ```scala blocks that are not), or a ```text block where it was a signature listing or
a deliberately non-compiling snippet. `checkDocScripts.sh` now RUNS each script after
compiling it (125 of the 134; `// doc: compile-only` marks the nine that need a display,
python, or would mutate a repository, `// doc: args` feeds CLI-style examples), in CI and
as release gate 6c. Running found what compiling could not: `QuickStartGuide` fancy-indexed
row 2 of a 2-row matrix; its "extract a scalar" example called `.item` on a `Double`
(`diagonal.sum` returns a scalar); `BigTypeGuide` said `compare` returns 0 against `BigNaN`
(it ranks the sentinel highest); the `loadSmart` examples read a `data.csv` that did not
exist (they now write it first) — the harness verified every `// expected` comment against
the actual output. The plot examples save PNGs instead of opening windows, so they run
headless. The README opens with an eight-row Scala | Rust | NumPy excerpt and a Documentation
table, cheat sheet first (`rust/docs/RustCheatSheet.md`, retitled as the three-language
sheet; `docs/CheatSheet.md` points at it); cheat-sheet cells that showed two equivalent
expressions joined by ` / ` now stack them, so the slash is never read as division.

**ADDED — Rust documentation: quick start, cheat sheet, scripting guide — every example compiled**

The crate had 85 lines of README against the Scala library's 4,600 lines of guides. Three
guides now parallel the Scala set section for section: `rust/docs/RustQuickStart.md`
(the twenty sections of `QuickStartGuide.md`, every block a complete program),
`rust/docs/RustCheatSheet.md` (three columns — uni Scala | uni Rust | NumPy — section for
section with `MatDCheatSheet.md`, ending in one compiled program that calls every table
row) and `rust/docs/RustScriptingGuide.md` (paths, files, CSV at three levels, argument
parsing, `UniDateTime`, `Big` — parallel to `UniScriptingTools`, `PathIOReference`,
`DateTimeParser`, `BigTypeGuide`). `checkRustDocs.sh` extracts every ```rust block with a `fn main` from
`rust/docs/*.md` and `rust/README.md` into the `uni-doc-examples` crate and builds them
with warnings denied — in CI (`rust.yml`) and as release gate 6d — the Rust twin of
`checkDocScripts.sh`. `MatD` gained the constructors the guides needed to say the same
things as the Scala's: `full eye eyeK diag arange linspace tabulate zerosLike onesLike
fullLike randn rand normal uniform` (the random ones take the `NumPyRng` explicitly) and
`fromArray2` (`MatF` too), the bridge from the CSV loaders' `Array2`.

**ADDED — Rust: `MatF` (`Mat[Float]`); the facade trio `MatD`/`MatF`/`MatB` is complete in both languages**

`udata::matf`: `MatF = Mat<f32>` on the generic core, with the numerics as Scala's
`Mat[Float]` runs them — sequential single-precision folds (`sum mean std variance norm
cumsum` and the axis family), `Float.compare` ordering for `min max argmin argmax sort
argsort` (`java_float_compare`: NaN highest, `-0.0 < 0.0`), IEEE masks widened through
`f64`, `sqrt` through `f64` (`MatElem[Float].sqrtT`), `matmul` as the sequential k-sum
`multiplyFloat` produces, LU `inverse`/`determinant`/`solve`, `toMatD`/`fromMatD`. 286
`mf.*` fixture rows pin them as `floatToIntBits`, NaN-injected variant included; every
one agreed on the first run.

**CHANGED (speed) — Rust elementwise maps stop oversubscribing the memory bus**

`map_elems`/`bin_op` let rayon split a 1M-element map into small pieces across every
thread; a memory-bound streaming pass wants long contiguous runs instead — on the 24-core
Windows machine `add`/`abs`/`exp` read 1.05–1.14 ms that way. A parallel task now takes at
least 64K elements (`ELEMENTWISE_MIN_LEN`), so a 1M map is 16 contiguous chunks: `add`
1.05 → 0.53 ms, `abs` 1.07 → 0.54, `exp` 1.14 → 0.62, `relu` 1.10 → 0.55 — ahead of the
JVM on those rows now. Above ~4M elements the pass is at memory bandwidth and the
granularity no longer matters (2000², 4000² unchanged), so this needs no core count and
is neutral on few-core machines. Bits are unchanged (elementwise). The allocator was tried
and ruled out (mimalloc: ~5%).

**ADDED — `MatB` and `MatD` as each other's second opinion: `MatBigVsDoubleSuite` / `matb_vs_matd`**

Every fixture pins each element type against its own Scala reference; nothing had ever
asserted that `Mat[Big]` and `Mat[Double]` agree with each other. Now, in both languages,
every benchmarked operation — elementwise, reductions, the axis family, `matmul`, LU,
`sort`/`argsort`, the masks — runs on the same seeded 60×60 input in both types and must
agree to `rtol 1e-9` (Double's own precision), land on the same `argmin`/`argmax` cell,
and place NaN identically (`Double.NaN` vs `BigNaN`: folds, `min`/`max`, `sort`, masks,
`isnan`/`hasNaN`). The one intended difference is recorded in the suite: `Big` has no
signed zero. Both suites passed on their first run.

**ADDED — `MatB.col(Big*)` / `MatB.row(Big*)`; the `bigcalc` demo pair covers `MatB`**

The facade's `col`/`row` take Doubles, so `MatB.col(Big("1.5"), …)` — the form
`BigTypeGuide.md` showed — did not compile; `object MatB` now takes `Big` values directly.
`jsrc/bigcalc.sc` ↔ `rust/examples/bigcalc.rs` gained the same invoice as a `MatB`
(elementwise ops, `matmul`, folds, masks, `csvText`, exact-decimal `inverse`/`solve`),
still byte-identical.

**FIXED — Rust `Big::sqrt`: trailing zeros and single rounding, as `BigDecimal.sqrt(mc)`**

Found by the `Mat[Big]` fixture, never by the scalar one: the result now strips trailing
zeros down to the preferred scale (`this.scale()/2`) as Java does, and an inexact root is
rounded once on the most significant dropped digit — it used to round digit by digit,
which turned a `…49` tail into `…5` and then up.

**FIXED — `Mat[Big]`: `matmul` through the stride equation and the guarded arithmetic; masks false against `BigNaN`**

`multiplyBig` read its operands' raw buffers with only the transposed flag, so a sliced or
offset `Mat[Big]` view multiplied wrong, and it summed raw `BigDecimal`s, so a `BigNaN`
cell leaked the sentinel's digits into the product; it now reads through `m(i, k)` and
`Fractional[Big]`, and a NaN operand makes its cell `BigNaN`. The ordering masks
`gt`/`lt`/`gte`/`lte` on `Mat[Big]` are false against `BigNaN` (the guarded operators, as
the IEEE masks are for `Double` NaN); `:==` still recognises the sentinel.

**CHANGED — `BigNaN` orders above every number, as `Double.NaN` does**

`Big.compare` and `Fractional[Big].compare` used to return 0 whenever either operand was
the sentinel — a non-transitive comparator: `MatB.min`/`max` depended on where the NaN
sat, `sort` left it in place, and Java's TimSort is entitled to reject such a comparator.
`BigNaN` now ranks highest and equal to itself, which is exactly `Double.compare`'s rule,
so `MatB` sorts, `min`s and `max`es as `MatD` does (NaN last; `min` skips it, `max`
returns it). Scalar `Big.max`/`min` still propagate the sentinel; `<`/`>` are still false
against it. Both languages; two `cmp` rows of the `big-parity` fixture moved.

**FIXED — `exp` and `log` on `MatF` and `MatB`**

Their generic branch cast a `Double` to `T`, which compiles under erasure and then dies at
the first store: `ClassCastException` on `MatF`, `ArrayStoreException` on `MatB`. They go
back through `MatElem.fromDouble` now (so on `MatB` a non-finite result is `BigNaN`), and
`MatActivationSuite`'s two "documented limitation" tests became value tests.

## v0.17.0 — 2026-08-17

The Rust port of `Mat[Double]` is complete: every phase of the Tier 3 checklist in
`rust/PARITY.md` — the view model, reductions, indexing and masks, `MatMathOps`, matmul,
the linear-algebra and decomposition family, pandas-style and signal ops, `groupBy`/`merge`
— is in, pinned to the JVM by a 3,371-row fixture. `Mat[Big]` is the remaining item.
Matrix products go to a native BLAS by default, as NumPy's do (`-Duni.mat.blas=os-best`),
with `matmulPure` and `-Duni.mat.blas=pure` as the reproducibility opt-in; the mask family is IEEE like NumPy's; the Linux OpenBLAS/LAPACKE
crash is fixed at the root. Breaking in the 0.x sense: `posixAbs`/`posixRel` are
`private[uni]`, and the Rust `MatB` is now `MatBool`.

**CHANGED — one apples-to-apples benchmark table per platform; `runBenchAll.sh`**

`uni.apps.BenchAll` prints an executive summary and then a single table with five numeric
columns — `NumPy | Scala·pure | Rust·pure | Scala·BLAS | Rust·BLAS` — over every MatD
row, every layout row and the six 3PRF rows. Each column is a named configuration: the
pinned loop on each side, and each side's BLAS. The summary compares the languages like
for like (BLAS on all three where BLAS applies, since NumPy has no other mode; and the
pinned loop in both ports), then the as-shipped defaults, labelled as a comparison of
configurations — Rust's default build is the pure loop because its BLAS is a build-time
feature, which is a packaging constraint and never the number that represents Rust.
Every line is a geometric mean of per-row speedups with the median beside it. The BLAS
columns carry a number only on the rows BLAS can change and `·` elsewhere. `runBenchAll.sh`
builds both Rust flavours (the BLAS one in its own target dir), runs everything, and
writes `bench-logs/benchAll-<os>-<host>-<stamp>.out`; `-blas <mode>` picks the Scala
BLAS mode measured. Found by its first runs: `MatD::matmulBlas` copied both operands
into owned `Array2`s and spent more time copying than multiplying (1.9 vs 0.9 ms at 512³)
— it now lends views.

**FIXED (Linux) — the OpenBLAS/LAPACKE crash; `-Duni.mat.blas=os-best|bundled|system|pure`**

Ubuntu's `libopenblas0` is built without LAPACKE and shares bytedeco's bundled OpenBLAS's
SONAME; two OpenBLAS instances in one JVM interpose on each other and kill the process
(`undefined symbol: LAPACKE_dgeev`, or `SIGSEGV` in `dgemm_oncopy`, by load order). One
BLAS per process is now the rule, and the flag says which:

| `-Duni.mat.blas=` (or `UNI_MAT_BLAS`) | meaning |
|---|---|
| unset, `os-best`, `true`, `1` | **default.** `system` wherever netlib binds a native BLAS — Accelerate on macOS, the OS OpenBLAS on Linux/Windows — else `bundled` |
| `system` | netlib → the OS BLAS. On Linux `eig`/`eigenvalues`/`svd`/`cholesky` then go through netlib's LAPACK (the OS `liblapack.so.3`, or its pure-Java fallback) instead of bytedeco's LAPACKE, so the bundled copy is never mapped |
| `bundled` | bytedeco's OpenBLAS for everything, no OS packages involved (2–4.5× slower than the system OpenBLAS at 512³) |
| `pure`, `false`, `0` | no BLAS: every `*@` is the pinned pure loop |

Read once per JVM; no programmatic setter; `matmulPure` / `matmulBlas` override per call.
The remedy in force until now — purge `libopenblas0` — is withdrawn: install it, it also
restores an optimised BLAS to an apt-installed NumPy on the same box. `BlasDiagSuite`
covers both Linux modes and `LapackNetlibSuite` proves the netlib LAPACK path against
bytedeco's on every platform. New dependency: `dev.ludovic.netlib:lapack`.

**ADDED — Rust: the linear-algebra family (Tier 3 phase (e) complete)**

`udata::linalg`: `diagonal trace normOrd determinant inverse solve qrDecomposition outer
cross kron tril triu fillna cov corrcoef` as bit-identical ports of the Scala loops, and
`svd lstsq leastSquares matrixRank pinv cholesky` computed by the crate itself (one-sided
Jacobi SVD, Cholesky–Banachiewicz); `udata::eig`: `eig` / `eigenvalues` (EISPACK
`orthes` + `hqr2`). Scala takes the second group from LAPACK, whose bits no pure loop
reproduces, so the fixture pins those on a 2^-20 grid — singular values, solutions,
ranks, sorted spectra — and the first group as raw bits: 236 `la.*` rows added to
`test-data/mat-parity/`, none moved. Singular matrices and non-positive-definite input
are `Err(Error::SingularMatrix)` where Scala throws.

**ADDED — Rust: `groupBy` / `merge` on named tables (Tier 3 phase (f) complete)**

`upath::matresult`: `CsvTable<f64>::groupBy(key, op)`, `groupByOps(key, &[(col, op)])`
and `merge(right, on, how)` with `AggOp` and `JoinType` — Scala's `matResultOps` on
`MatResult`, same group order (first appearance), same join layout, same `_x`/`_y`
suffixes and NaN gaps; aggregates go through `MatD` so they carry the JVM's association
order. `MatResultOpsSuite` and the Rust tests run the same tables to the same layouts.

**FIXED — `groupBy` with per-column ops emits the aggregated columns in header order**

`MatResult.groupBy(keyCol, aggOps: Map[…])` used to lay the output out in the `Map`'s
iteration order — insertion order up to four entries, hash order beyond — so a five-column
aggregation's layout depended on the column names. It is now the header order, and an
aggregated column that is not a header is rejected up front (`IllegalArgumentException`)
instead of failing on lookup.

**ADDED — Rust: pandas-style and signal ops (Tier 3 phase (d) complete)**

`udata::pandas`: `idxmin idxmax sort argsort nlargest nsmallest between unique nunique
valueCounts diff diffAxis shift pct_change percentile median percentileAxis medianAxis
describe rolling(window).{mean sum min max std} histogram histogramEdges` — every
ordering under `Ordering[Double]` semantics and every sort stable, as in Scala, so the
results agree bit for bit. `udata::signal`: `polyval convolve correlate` (bit-identical)
and `polyfit` (via `lstsq`). 469 `pd.*`/`sg.*` fixture rows added.

**ADDED — Rust: the `Mat` utility remainder**

`udata::matutil`: `maximum`/`minimum` (matrix and scalar; `Ordering[Double]` semantics, as
in Scala), `sign round powerF hadamard allclose containsNaN exists wherePred nanToNum
ndim toContiguous zipMap`, `mapRows`/`mapCols`/`filterRows`/`applyAlongAxis` over row and
column views, `addToEachRow` … `divEachCol`, `scale`, `repeat`/`repeatAxis`/`tile`,
`vsplit`/`hsplit`/`split` (index and count forms), and `saveCSV`/`writeCsv` through
Java's `Double.toString`. Exact ports; 206 `ut.*` fixture rows pin them bit for bit.

**ADDED — Rust: `MatMathOps` (Tier 3 phase (c) complete)**

`sin cos tan arcsin arccos arctan arctan2 sinh cosh tanh floor ceil trunc log10 log2
sigmoid relu leakyRelu elu gelu softmax logSoftmax dropout` on `MatD`, each spelled as
the Scala spells it — `relu` with `Math.max` semantics (NaN survives, `-0.0` → `+0.0`),
`log2` as `ln x / ln 2`, `softmax` summing with a plain fold after max-subtraction.
`dropout` takes the generator explicitly (`dropout(&mut rng, p)`); the crate has no
global RNG. The exact members are pinned bit for bit in `test-data/mat-parity/` (275
`math.*` rows added, none moved); the transcendental ones on an absolute grid, since the
JVM's intrinsics and Rust's libm differ by 1–2 ulps on up to a quarter of inputs — the
measured table is in `rust/PARITY.md`. The `sigmoid` and `relu` benchmark rows now have a
Rust column.

**CHANGED (numeric, and speed) — `matmulPure` is pinned across languages; `*@` is BLAS by default, like NumPy**

`a.matmulPure(b)` on `Mat[Double]` and `Mat[Float]` is the tiled pure loop whose per-cell
association order is a sequential k-sum from 0.0: **bit-identical to the Rust port and
independent of the machine** — neither runtime fuses `x*y + z`, and the fixture pins 22
matmul rows including transposed-view operands. No BLAS offers that; every one selects
kernels by CPU feature and thread count. It is what fixture generators and the demo pairs
(`jsrc/tprfRunner.sc` ↔ `rust/examples/tprf_runner.rs`) call.

`*@` / `matmul` / `dot` follow `-Duni.mat.blas`, and the default is `os-best`: large
products go to a native BLAS, as NumPy's do (the match-NumPy rule), so their last ulps
depend on the library, its threading and the CPU. `-Duni.mat.blas=pure` makes every `*@`
the pinned loop instead; per call, `matmulBlas` is BLAS whatever the mode. Products below
`uni.mat.blasThreshold` / `blasThinThreshold` (216 / 384 ops; macOS 1728 / 768) take the
pure loop in every mode — the thresholds decide which algorithm runs, so they move last
ulps and are tuning, not contract. Native BLAS is loaded lazily, on the first BLAS call.

The pure loop is not the old one. It is a register-blocked microkernel (`MatmulPure`:
4×4 accumulator blocks over packed panels, a streaming path for short K and narrow
outputs, a 2-D parallel split), tuned on the shapes `Tprf3` actually multiplies rather
than on square benchmarks, and bit-identical to the loop it replaced — the same
sequential k-sum per cell, verified against the fixture at every step. 512³ went
6.5 → 1.4 ms, and the `Tprf3` large rows sit within ~1.2–1.9× of BLAS instead of 2–3×.
Views cost no copy: both operands are read through their strides.

Rust: `MatD::matmul` / `dot` are the pinned loop (BLAS is a build-time feature there,
`--features blas`, which a library cannot switch on for its users), plus `matmulPure`,
`matmulBlas` and `hstack`/`vstack`. Its kernel is the same microkernel with 8-wide panels
— 512³ 2.4 → 1.7 ms, `512×512×8` 0.10 → 0.03 — and reads views in place through their
strides, as the Scala does.

**CHANGED (behaviour) — the mask family is IEEE, matching NumPy**

`gt` `lt` `gte` `lte` `:==` `:!=` on `Mat[Double]` and `Mat[Float]` now compare with the
primitive operators instead of `Ordering`. Two results change:

- **Every comparison involving NaN is false.** `MatD.row(1.0, Double.NaN).gt(0.0)` was
  `[true, true]` and is now `[true, false]`; `m :== Double.NaN` is false everywhere.
- **`-0.0 == 0.0`.** `MatD.row(-0.0) :== 0.0` was false and is now true, and `lt(0.0)`
  on `-0.0` was true and is now false.

Both were artifacts of the default `Ordering[Double]`/`Ordering[Float]` being the
`TotalOrdering`, which ranks NaN above every number and `-0.0` below `0.0`. That is the
right contract for `min`/`max`/`sort`/`argmin`/`argmax` — unchanged — and the wrong one
for a mask, which mirrors NumPy's operators; `jsrc/numpy2mat.sc` already translates
Python's `>` straight to `gt`. Element type decides: `Mat[T]` for other `T` still uses
its `Ordering`.

Masks built from finite values are unaffected, which is every use in the test corpus.

`test-data/mat-parity/` grew 810 mask rows — 27 cases over the ten adversarial arrays
(NaN, −NaN, ±0.0, ±inf, ties) in three orientations including a transposed view — so the
two implementations are now pinned to each other and not only to NumPy independently. No
existing row moved. The rows sit beside ordering rows built from the same inputs, and the
two record opposite rules deliberately: a port that routes masks through its ordering
comparator passes every ordering row and fails every mask row.

**ADDED — Rust: the `MatDOps` indexing surface (Tier 3 phase (c))**

Mask indexing, the `apply` gather family and the `update` write family are ported.

- `MatBool` mirrors `Mat[Boolean]` (not `MatB`, which in Scala is `Mat[Big]`):
  `gt`/`lt`/`gte`/`lte`/`eqTo`/`neTo` and
  `isnan`/`isinf`/`isfinite` produce one; `&`/`|`/`!` combine them (Rust cannot overload
  `&&`); `all`/`any` reduce, with `allAxis`/`anyAxis` keeping the numeric axis
  orientation; `whereMat`/`whereScalar` are `Mat.where`, which is a Rust keyword.
- `m.applyMask(&mask)` reads the true cells as a `1×k` row, as Scala's `m(mask)` does.
- `MatD::intoMut()` returns a writable `MatMut` and **panics if the buffer is shared**,
  which includes any live view; `MatMut::freeze()` returns the `MatD`. `updateAt`, `updateRowAll`,
  `updateRowsCols`, `updateMask`, the `*From` matrix-source writes and `applyRowsIdx`/
  `applyIdxCols`/`applyIdxIdx` complete the surface.

Writing through a view to mutate its parent — legal in Scala and NumPy — is not
reproducible in safe Rust and is recorded in `PARITY.md` as an intentional divergence.
The conversion panics rather than returning a `Result` so that a ported function keeps
the signature its Scala original has.

**CHANGED (output-visible) — LF is the default line terminator, and `uni.println` exists**

`import uni.*` now brings in a package-level `println` that terminates with `\n` rather
than the platform separator, alongside the `eprintln` that was already there. On Windows
this changes the bytes a client's `println` emits.

It shadows `scala.Predef.println` without ambiguity or call-site changes — the parameter
is `Any` with a default, so `println("s")`, `println(42)`, `println()` and `println(a, b)`
all still compile. A file that defines its own local `println` still wins over the import,
so the ~38 scripts carrying that two-line shadow are unaffected until they drop it.

Only the implicitly appended terminator changes. `print` is untouched: `print("a\r\n")`
still emits CR LF, and a progress-bar `\r` still works.

Two supporting changes, since `println` alone does not finish the job:

- `%n` is gone from the sources (19 occurrences across six files, including
  `uni.stats.Tprf3`, which is library code and so emitted CRLF for downstream users
  whatever their own code did). `%n` *is* the platform separator, so only not using it
  fixes it.
- `build.sbt` passes `-Dline.separator=\n` to every forked JVM, converting `println`,
  `%n`, `Console.println`, `PrintWriter.println` and `System.lineSeparator()` while
  leaving an explicitly written `\r` verbatim.

A scala-cli script launched from a `#!/usr/bin/env -S` shebang cannot receive that flag —
there is no way to put a literal LF on that line — so scripts depend on `uni.println` and
on not using `%n`.

**CHANGED (numeric) — a strided `sum` is chunked; views' sums move in the last ulp**

`sum` on a view — transpose, offset slice, stride-0 broadcast — was one sequential
accumulator. It is now the same shape as the contiguous `sumD`: **8 unrolled accumulators
over the logical row-major sequence, `min(MaxSumChunks, rows/8)` row blocks above 65536
elements, partials combined in block order.** One rule now covers every layout; what
differs between a matrix and its transpose is the sequence, not the algorithm.

**A view's sum therefore differs from what earlier versions produced**, and `mean`/`std`
on views inherit that. The contiguous path is untouched, so an ordinary matrix's sum is
unchanged. Both languages changed together and `test-data/mat-parity/` was regenerated;
21 rows moved, all `tsum`/`tmean`/`slicesum`, by 1-2 ulp.

Deliberate, not incidental: the old order could not be improved without moving it, and it
left `sum` on a view 2-3.3x behind NumPy. Now 1.8-6.1x ahead of it, and 10.8-25.8x faster
than before at 2000x2000.

Two thresholds are part of the numeric contract rather than tuning knobs, since each
selects between one block and many: `ParallelThreshold` (4096) for the contiguous form
and `StridedSumParallelThreshold` (65536) for the strided one.

**CHANGED (numeric) — `std(axis)` takes its per-lane mean the way `mean(axis)` does**

The `std` family carried three mean algorithms: whole-matrix `std` took its mean from
`sum`, a contiguous `std(axis)` used a per-lane fold, and a strided `std(axis)`
materialised each lane and routed through `sumD`. The same data gave three answers
depending on shape and layout. One rule now: **the mean `std(axis)` uses is the mean
`mean(axis)` returns.**

A strided `std(axis)` therefore differs from what earlier versions produced; contiguous is
unchanged. Six fixture rows moved, all `tstd1fnv`. No performance cost — the strided path
also stopped materialising a lane per column, so it got faster.

The `std0fnv`/`tstd1fnv` rows existed to pin that difference, which meant both sensitivity
guards were asserting the wart still existed and failed the moment it was fixed. They now
assert the opposite and stronger property: `m.std(0)` and `m.T.std(1)` reduce the SAME
lanes — row k of the transpose is column k of the original — so they must agree bit for
bit whatever the layout.

**PERFORMANCE — both languages now beat NumPy on every benchmarked `Mat` operation**

Nothing in `docs/MatDCheatSheet.md` trails NumPy in either language except `matmul`,
which is a BLAS question rather than a `Mat` one and has no Rust column yet. Nine rows
did, by up to 23x.

On the Scala side:

- `sum`, `mean`, `std`, `variance`, `sum(axis)`, `min(axis)`, `max(axis)`, the order
  statistics and the elementwise family (`abs` `sqrt` `exp` `log`) gained unboxed
  branches that read a `Mat[Double]` of **any** layout through the stride equation.
  Previously only contiguous-at-offset-0 matrices avoided `Numeric`/`Ordering` dispatch
  on erased `T`. `min(axis)`/`max(axis)` were the worst of them, boxing on every layout
  including contiguous.
- The elementwise maps went parallel. That was the larger half: `m + 1.0` was already 6x
  faster than `m.abs` on identical data purely because one used `fillD` and the other did
  not.
- The order statistics went parallel, split by row block.
- The axis family — `sum(axis)`, `min(axis)`, `max(axis)` — splits by LANE (columns for
  axis 0, rows for axis 1), and the column forms hold eight accumulators in registers
  across the row sweep rather than doing a read-modify-write per element.
- `std(axis = 0)` walks two row-major sweeps, means then squared deviations, instead of
  one column-at-a-time double sweep through row-major storage. 8.3x, the largest single
  win here.

A `Rust vs Scala` column was added to the benchmark output for this, because a gap
between the two ports is a signal that one is doing more work rather than that a language
is slower — and the NumPy baseline hides it. `stdAxis` read 2.8x faster than NumPy while
being 6.6x behind its own sibling port; blocking and the lane split closed that to
parity (0.79 -> 0.095 ms).

Ported to Rust, whose `scan`, `sumAxis`, `minAxis`/`maxAxis`, `cummax`/`cummin` and
`stdAxis` were all still sequential or allocating: `scan` splits by row block with the
same order-preserving combine, the axis family splits by lane with the same eight-wide
blocking, `cummax`/`cummin` on axis 0 carry an accumulator array so `out` is written in
row-major order instead of jumping `cols` elements per store, and `stdAxis` reads each
lane in place rather than materialising it into a `Vec` first.

**All of these are bit-preserving**, so `test-data/mat-parity/` is unchanged by them —
unlike the two numeric changes above, which move values on purpose. Two properties do the
work: an extremum is associative and commutative under a total order, so chunking cannot
move it, and the block combine runs in block order replacing only on a strictly better
value, which preserves first-occurrence tie-breaking for `argmin`/`argmax`. Splitting the
axis sums by lane leaves each lane's accumulation order intact; splitting them the other
way would not. Verified byte-identical over 1896 cells spanning four element types, five
layouts, eight shapes, NaN/signed-zero/infinity values and empty shapes with their
exception types.

Two behavioural edges are preserved rather than optimized away: the fast path declines
when the caller supplied an `Ordering` other than `TotalOrdering`, and empty matrices
fall through to the general path so their asymmetric exception behaviour is unchanged.

Against NumPy at 1000x1000, worst rows before and after:

| row | Scala | Rust |
| :--- | :--- | :--- |
| `sum0@bcast` | 23.0x slower → 2.3x faster | 3.2x slower → 2.8x faster |
| `sum0@transposed` | 18.7x slower → at parity | 4.1x slower → 4.4x faster |
| `max1` | 9.3x slower → 1.7x faster | 2.3x slower → 2.7x faster |
| `max` | 8.6x slower → 1.6x faster | 2.4x slower → 2.6x faster |
| `min0` | 6.6x slower → 1.7x faster | 2.9x slower → 1.9x faster |
| `argmax` | 3.4x slower → 2.0x faster | 2.0x slower → 2.8x faster |
| `sqrt` | 2.4x slower → 3.8x faster | already ahead |
| `std0` | 2.3x → 18.8x faster | 1.6x → 2.8x faster |
| `cummax0` | already ahead | 1.3x → 3.4x faster |

Five thresholds now exist across the two languages and are not interchangeable. Two are
NUMERIC CONTRACT, because each selects between one block and many and so changes the
answer: `ParallelThreshold` (4096) for contiguous sums and `StridedSumParallelThreshold`
(65536) for strided ones, both mirrored in the Rust port. Three are tuning and may be
retuned per language: 65536 for elementwise maps and the order statistics, 524288 for the
axis-sum lane split, where a thread gets a strided slice rather than a contiguous run and
has to repay the lost locality in cores.

**FIXED — the Rust port's order statistics follow `Ordering[Double]`**

`min`/`max`/`argmin`/`argmax`/`cummax`/`cummin` in the Rust `MatD` compared with `<`/`>`.
`Ordering[Double]` is `TotalOrdering`, i.e. `java.lang.Double.compare`: **NaN ranks above
every number**, so `max` of a lane containing NaN is NaN rather than the largest ordinary
value, and **`-0.0 < 0.0`**, so `min(0.0, -0.0)` is `-0.0` and argmin moves off index 0.

`NumPyRNG.uniform(-1e6, 1e6)` produces neither value, so all 427 parity rows passed while
this was wrong. The fixture gained 330 `adv/` rows over ten adversarial arrays in three
orientations, and both suites assert that count separately.

Use `udata::mat::java_double_compare`, not `f64::total_cmp`: the latter implements IEEE
totalOrder and places **-NaN below -Infinity**, where `doubleToLongBits` canonicalises
every NaN so the JVM ranks them all highest. The `negnan` rows separate the two.

**ADDED — Tier 3 phases (a) and (b) of the Rust port: the `Mat` core**

`udata::mat::MatD` gains the **strided view model** (`transpose`/`T`, `slice`,
`broadcastTo`, and the fragmented-layout materialisation `Internal.create` performs),
broadcasting arithmetic, the `apply*` gather family, `reshape`/`ravel`/`matCopy`; plus
`udata::mataxis` (`sumAxis` `meanAxis` `minAxis` `maxAxis` `stdAxis` `cumsumAxis` `cummax`
`cummin` and the four `rowSums`-style shorthands) and `udata::vecexts` (`CVecD`/`RVecD` as
newtypes over `MatD` with `Deref`, standing in for Scala's `<: Mat[T]` opaque-type bound).

`MatD` carries Scala's own `(transposed, offset, rs, cs)` descriptor rather than
materialising views, because the layout selects the summation algorithm: a contiguous
matrix gets the chunked `sumD`, a view gets a sequential fold, and the two differ in the
last ulp. Materialising would silently pick the chunked order where Scala picks the
sequential one. The parity fixture grew from 130 to 757 rows to pin this, in aspect-ratio
pairs, since whether a slice is materialised flips with the parent's shape.

**ADDED — one command regenerates every benchmark table**

```bash
cd rust && cargo build --release --bin bench_mat --bin bench_tprf3 && cd ..
sbt "runMain uni.apps.BenchAll"
```

`uni.apps.BenchAll` runs `MatBench` and `Tprf3Bench` across NumPy, Scala and Rust and
prints finished markdown for all four tables. New: `BenchRunner` (shared interpreter
discovery, binary staleness checks, output parsing, table emission), `MatBench`,
`BenchAll` and `rust/src/bin/bench_mat.rs`; `py/bench.py` rewritten to match; `make bench`
gained `--release`.

All three halves must keep the same row labels, sizes, warmup/iteration counts and input
generator — the label is the join key. The previous two-script manual merge had them
drawing inputs from different generators (`MatD.setSeed` is PCG64, `np.random.seed` is the
legacy MT19937) with different iteration counts.

Coverage grew past the original list — `log`, `sqrt`, `argmax`, `mean0`, `min0`, `max1`,
`std0`, `cumsum1`, `cummin1` — and gained a second table over **layouts**. Widening it
paid immediately: `min(axis)`/`max(axis)` had never appeared in any table and turned out
to be 6.6x and 9.3x behind NumPy, boxing on every layout. NumPy's `M.T` and `M[1:]` are views exactly as
`MatD`'s are, and NumPy's reductions are flat across them (`sum` 0.127 → 0.128 ms) where
ours are not (0.040 → 0.422 Scala, 0.033 → 0.424 Rust); `sum(axis=0)` on a transposed view
is 19× behind NumPy on the Scala side. Rows the Rust port lacks print "—", so the table
doubles as a coverage report against `rust/PARITY.md`.

**BREAKING — `posixAbs` and `posixRel` are `private[uni]`, the deprecation removed**

v0.16.0 deprecated them with the note that `private[uni]` was the destination and removal
never was. This is that narrowing. They stay alive underneath the public API forever, since
they are the engine behind `Path.posix`/`Path.relpath` and the resolution machinery -- what
changes is only that the outside can no longer name them.

Source-breaking in principle, but a survey of the working script corpus and of `jsrc/` found
**no caller outside the library**; every in-repo user is `package uni`, which `private[uni]`
still admits. Side benefit: the twelve deprecation warnings that appeared on every compile
are gone, which was most of the noise in the build output.

## v0.16.0 — 2026-08-11

**0.16.0, not 0.15.2.** `versionScheme := semver-spec` is declared, and under 0.x the
MINOR position acts as MAJOR -- so a release that removes public API and changes output
bumps it. This one does both: `ChronoParse` and `parseDateChrono` are gone; `hash64`
returns a different value for any file of 64 bytes or more; `relpath` returns a relative
path where it returned an absolute one; `toString` rejects a pattern it used to accept
(capital `Y`); `posx` strips every trailing separator rather than one; `standardizePath`
resolves against the config's working directory rather than the JVM's; `DateFormat`
renders month names in English regardless of locale; file timestamps render in UTC where
they rendered in system-local time; `Path.weekDay` returns an `Int` rather than a
`java.time.DayOfWeek`; every line reader preserves an interior carriage return where it
used to discard it; the six `lastMod*` alias spellings are gone; the `*Iter` traversal
methods are lazy and no longer sorted; `newerThan`/`olderThan` compare in the direction their
names say; and `copyTo` requires its `overwrite` argument and returns `Option[Path]`, with
`File.copyTo(dest)` removed. Bigger than any of those: the date type moved off `java.time`
(below).

Of those, `copyTo` is the only one that fails to *compile* rather than changing behaviour
quietly, which is why it was done that way.

`posixAbs`/`posixRel` are **deprecated as of** this release -- `"0.16.0"` is the `since`
argument of `@deprecated`, not a removal target. They are never to be removed: the plan
is `private[uni]` -- out of the public API, permanently alive underneath it, since they
are the engine behind `Path.posix`/`Path.relpath` and the resolution machinery.


**ADDED — the BadPath family: `Paths.get`/`asPath` are total**

A path string the host filesystem cannot represent (`"a:b:c"`, `"a<b"`, an embedded NUL on
POSIX) no longer throws. It comes back as a member of the **BadPath family**: an ordinary
`java.nio.file.Path` of shape `<absent-drive>:/__uni-BadPath__/<payload>`, where the payload
is the original string encoded with cygwin/MSYS2's own on-disk convention (each rejected
character mapped to its Private Use Area counterpart at `U+F000 + char`, plus `/` → U+F02F so
the whole input stays one name element). `Path.badPathString` decodes it back exactly, which
makes the canonical idiom safe on arbitrary input:

```scala
if "some-file".asPath.isFile then ...          // never throws, whatever the string
val p = "f:/weekly".asPath
if p.isBadPath then println(s"bad path [${p.badPathString}]")
```

`uni.Paths.get` and `.asPath` are total *identically* — the latter is a one-line delegation
and client code treats them as interchangeable. The loud alternative is
`java.nio.file.Paths.get`, which uni does not touch.

The family cannot be written to: the root is a drive letter chosen at runtime from the
letters absent from `GetLogicalDrives`, so `Files.write`, `createFile`, `copy` and even
`createDirectories` fail at the driver level — no stray file can appear, even through JDK
calls outside uni's control. Construction is a pure string parse (no `GetFullPathName`, no
drive-cwd probe), and uni's own `exists`/`isFile`/`isDirectory` short-circuit to `false` with
zero OS contact.

Renderings follow MSYS2's namespace split: the POSIX-namespace forms (`posix`, `stdpath`,
`relpath`) decode the payload, as `ls` and `cygpath -u` do; the Windows-namespace forms
(`posx`, `localpath`, `dospath`) stay raw, as `cygpath -m`/`-w` do. Only family members
decode — a real file whose name genuinely holds PUA characters keeps raw renderings, because
those strings get handed to Windows programs and the raw form is the one that works there.
Design, lineage (this revives `vastblue.unifile`'s `PathBad`, fixing its two flaws) and the
deferred parse-side question: `docs/PathProviderDesignNote.md`.

**ADDED — `uni.cli` in Rust, and six matched demo pairs that are also parity tests**

The Rust port gains `eachArg`/`showUsage` and the argument cursor (`thisArg`, `consumeNext`,
`peekNext`, `nextInt`, `nextLong`, `nextDouble`), with the program name derived from the
caller's *source* file via `#[track_caller]` — the structural analogue of the Scala macro, so
usage messages read `treestat.rs` beside `treestat.sc` rather than naming an executable.

Alongside it, five new demo pairs join `pairProbe`: `treestat`, `pathshow`, `datecalc`,
`bigcalc`, `forecast`. Each is one program written twice — `jsrc/<name>.sc` and
`rust/examples/<name>.rs` — printing **byte-identical output**, so `diff` is a test that
covers what per-method fixtures cannot: composition, and any method with no fixture row.
`datecalc` and `bigcalc` use fixed inputs and no filesystem, so their output is identical on
any machine on any day. Catalogue and technique: `docs/DemoPairs.md`.

They immediately earned it, finding an `is_absolute` that applied the Windows root rule under
POSIX rules, a walk fixture row that had pinned NTFS enumeration order as if it were API, and
the two `Big` divergences resolved below.

**FIXED — `sqrt` of a negative and a negative `pow` exponent answer BigNaN in both languages**

`Big(-1).sqrt` and `x ~^ -2` used to throw `ArithmeticException` in Scala while the no-panic
Rust port answered `BigNaN`; neither answer was fixture-pinned, so the divergence was live and
invisible. Scala now adopts `BigNaN` — unrepresentable input travelling as data is the house
style (compare `BadDate`, `BadPath`, `str2num`) — and both cases are recorded in the
big-parity fixture, since an error case nobody records is an error case that can drift.

**FIXED — `isNumeric` is now exactly "`str2num` can parse this"**

It answered a third question that matched neither of its callers: `validNumChar` admitted `$`
and `,`, which routed `"$1,234.56"` into a strict branch whose patterns could not accept a
currency-with-grouping shape, so it reported `false` for a string `str2num` parses happily —
while the CSV loaders' own type sniffing stripped the `$` and called the same string numeric.
`isNumeric` now delegates (`!isBad(str2num(col))`), so the two cannot disagree, and
`getMostSpecificType` gets its own predicate for the decorated shapes it alone parses (`5K`,
`(100)`, trailing `%`). The `NumPattern1..4` regex set that only ever approximated `str2num`
is gone, along with its hand-rolled Rust equivalent. Fixture rows move accordingly: currency
and grouping shapes to `true`, the decorated shapes to `false`.

**BREAKING — path comparisons fold case only where the platform folds, and every
relative form absolutises**

Three related repairs to the path machinery, all mirrored in the Rust port and
pinned by the regenerated `path-parity` fixture:

- **Case-folding is a context property** (`PathsConfig.caseFold` /
  `PathContext::case_fold`), not a hard-coded behavior: Windows and macOS fold
  (their default filesystems are case-insensitive), Linux compares exactly, and
  synthetic configs follow the *simulated* platform so fixtures stay deterministic
  on any host. The unconditional fold in `relpath`'s cwd test relativised
  `/home/Phil/x` against a cwd of `/home/phil` on Linux — a different, legal
  directory — silently pointing callers at the wrong tree. `samePathString` and
  `UPath::relativize` consult the same flag (Java's own `relativize` is
  case-insensitive on `WindowsPath` and exact on `UnixPath`).
- **Every relative form resolves against the config working directory**, not just
  bare filenames: `a/b` staying relative while `bare.txt` absolutised was an
  asymmetry with no niche (`posx` normalises without absolutising), and it made
  `Paths.get("a/b")` the one relative-returning constructor — diverging from the
  Rust port's always-absolute `UPath`.
- **Drive-lettered shapes (`X:...`) pass through under either rule set** (the
  pass-through used to be Windows-rules-only). Under POSIX rules a colon is an
  ordinary character, but such strings denote host-absolute paths in every real
  corpus, and resolving them against the working directory produced
  `/munit/test/C:/...` — unparseable on the very hosts that create them. A genuine
  POSIX name like `C:x` is reachable explicitly as `./C:x`.

The pair probe (`jsrc/pairProbe.sc` / `rust/examples/pair_probe.rs`) remains
byte-identical across the change.

**FIXED — six defects repaired Scala-first, mirrored in Rust, all fixture-pinned**

The parity audit turned up quirks that were at first ported bug-for-bug; on review all
were judged genuinely wrong and repaired on the Scala side, with the Rust port moving
in lockstep:

- `str2num`: the leading-junk strip made `"%50"` parse as `50` (the `%` went as junk,
  so no percent divide) while `"50%"` was `0.5`, and let `"E5"` read as `5`; the
  global `%`-removal made `"5%5"` parse as `55`. Now: `$`/`,` are decoration anywhere,
  `%` is one *trailing* suffix, a leading `+` parses as a sign, anything else is BigNaN.
- `NumPattern2` gained the `(?i)` the other suffix patterns have: `1.5e5%` classifies
  like `1.5E5%` (`str2num` could always parse both, so the two disagreed).
- `getDuration` carries its sign on the largest **nonzero** unit: −5 hours used to
  report `(0, 5, 0, 0)` — indistinguishable from +5 hours.
- `numStr` blanks the minus of ANY all-zero rendering, before the suffix: `-0.000`
  (dec 3) and `-0.00%` no longer keep a sign on a zero. And abbreviation is by
  magnitude, so `-2.5e9` renders `-2.50B` instead of full-width.
- `getMostSpecificType`: the `length < 7` guard meant `"1/2/24"` could never be a
  date (now `< 6`) — and a regression from the SmartParse migration is fixed: with
  `parseDate` no longer throwing, the vestigial `Try(..).getOrElse` returned the
  **BadDate sentinel as a DateTime** for every unparseable longer string; the String
  is restored explicitly.

**ADDED — `Big.round(MathContext)` on both sides, and the BigUtils surface in Rust**

`Big` gains `round(mc)` — `java.math.BigDecimal.round` semantics, BigNaN-propagating
like `setScale` — closing the gap accounting code (`QuaxMerge`) had to work around
with `toBigDecimal`. The Rust `Big::round(precision, mode)` matches, including the
carry case (`999.9` at precision 3 is `1.00E+3`, not `1000`). `udata::bigutils` (new
Rust module) ports `numStr`/`numStrPct`/`num2string` + `NumFormat`, `str2num`,
`isNumeric`, `isBad`/`orBad` and `big2double`; `numStr` reproduces Java's
`%f` contract exactly (shortest decimal digits of the double, rounded half-up), and
`isNumeric`'s four regexes are ported as character scans. Two authoring accidents in
those regexes are **repaired** (Scala-first, then mirrored): pattern 3 gains the `(?i)`
pattern 1 always had, so a single-digit `5k` classifies like `5K`; and pattern 4's
decimal point is escaped — the bare `.` matched *any* character, so `12,34E+5` and
`12$34E+5` read as numeric. The dead `'/'` half of the double-minus guard is also
gone ('/' was never a validNumChar). Columns whose cells include lone
lowercase-suffixed digits may now infer as numeric where they inferred as text.
289 new `big-parity` rows pin all of it — parity item 3.

**BREAKING — the difference family is zone-free: the `ZoneId` parameters are removed**

A difference between two local datetimes is field arithmetic: no timezone can
legitimately affect it. `secondsBetween`, `minutesBetween`, `hoursBetween`,
`daysRounded`, `elapsedDays`, `secondsSince` and `getDuration` nevertheless routed
their operands through `atZone(zone).toInstant`, which imported DST artifacts into
results the arguments fully determine: wall-clock 01:30 and 03:30 on a US
spring-forward day answered one hour apart instead of two — a fact about Denver, not
about the datetimes. The `zone` parameters (and the zone-taking overloads) are gone;
every function now computes over the local fields. Behaviour changes only for
system-zone calls whose operands straddled a DST transition, and only by the
transition amount. Callers who mean instant arithmetic should use the `Instant`
overload of `secondsBetween`, which is unchanged. `whenModified` also moves from the
system zone to UTC, consistent with `Path.lastModifiedTime` (UTC since earlier in
this release) and the Rust port. No caller in uni or the `/opt/ue` corpus passed a
zone; the date-parity fixture values are unchanged (they were generated at UTC,
which the naive arithmetic equals by construction).

`getMillis()` moves the same way: it read the fields at the system zone, now at UTC,
making it the exact inverse of `epoch2DateTime(_, UTC)` (new `getmillis` fixture
rows pin it, and the Rust port gains the same function). Callers that meant "the
physical instant of this local wall time" must now say so —
`d.toLocalDateTime.atZone(ZoneId.systemDefault).toInstant.toEpochMilli` — because
that conversion genuinely depends on the zone. The corpus had two such callers
(fidPdf2txt stamping mtimes, grafByDate feeding a locally-rendered JFreeChart axis),
both updated to the explicit form; ordering-only uses are unaffected (the shift is
constant).

**RUST — the `TimeUtils` surface is ported: the clock, the between/duration family, `quikDate`, and file age**

`utime::timeutils` (new module) ports `now`, `yesterday`, `nowUTC`, `secondsBetween`,
`secondsSince`, `minutesBetween`, `hoursBetween`, `daysBetween`, `daysRounded`,
`elapsedDays`, `getDuration`, `endOfMonth`, `quikDate`, `quikDateTime`,
`monthAbbrev2Number`, `whenModified`, `ageInMinutes` and `ageInDays` — parity item 2.
The pure parts are pinned by 71 new `date-parity` rows (between/duration at UTC with
both directions, sub-second floor-vs-truncate cases, a <24h cross-midnight span, leap
and epoch crossings, and a US DST gap that at UTC must not matter; every pre-existing
row regenerated byte-identical). Differences are documented rather than silent: the
functions are zone-naive (`std` has no tzdb) and so match Scala's explicit-UTC forms;
the clock is UTC where Scala's `now` is local wall time; `nowZoned` has no port; and
the strict `quik*` parsers answer `BAD_DATE` where Scala throws.

**RUST — the closed-form Tprf3 entry points are ported: `tprfClosedForm`, `plsClosedForm`, `pls1Fit`, `forecast3prf`**

The `t3prf` module predated Scala's closed-form additions; the parity review
(`rust/PARITY.md`, new) caught the gap. All four are now ported with the Scala
spellings (the crate naming contract), `Pls3prfModel.predict` takes a raw row and
returns NaN on a wrong-length row where Scala throws, and the `tprf3-parity` fixture
grew `closed`/`pls` rows — appended, with every pre-existing row keeping its
original platform-generated value (a full regeneration on Windows would have
re-stamped 634 OOS lines with ~1e-15 drift). `Error` gained an `InvalidInput`
variant for the NaN-input and unknown-procedure refusals.

**ADDED — two pallet-era globals join the scripting surface**

Migrating the `/opt/ue` corpus off `vastblue.pallet`/`vastblue.unifile` surfaced two
functions that scripts call constantly and uni had no spelling for:

- `getLimitedStackTrace(using e: Throwable): String` -- the string-returning sibling of
  `showLimitedStack`, with the same client-frames-only filter. The `using` parameter
  matches the `vastblue.file.Util` signature, so `catch { case e: Exception =>
  getLimitedStackTrace(using e) }` ports unchanged.
- `execLines(cmd: String*): LazyList[String]` -- already implemented in `Proc`, was
  `private[uni]`; now public and exported from `uni.*`. Same lazy semantics as the
  pallet-era global of the same name.

Neither touches the Rust parity surface: `ProcUtils` and stack-trace helpers were never
part of the ported 92.

**FIXED — divergences caught by the new cross-language pair probe**

- `jsrc/pairProbe.sc` + `rust/examples/pair_probe.rs`: both halves build the same tree with the
  same fixed mtimes, run the same ~50 operations, and print diffable `op/key/value` lines. This is
  the "matched pair of scripts" idea -- fixtures pin one method at a time, so a method without a
  fixture was pinned by nothing, and that is exactly where all three catches came from.
- **RUST BUG -- `canRead` and `canExecute` answered `false` for every directory.** Java's checks
  are ACL checks, true for a readable, traversable directory; the port asked `File::open` (fails
  on a Windows directory) and `isFile()`. Now: directories answer via `read_dir`, and `canExecute`
  on Windows answers `exists()` -- an approximation Java's ACL check can beat only for a file
  whose ACL explicitly denies execute.
- **BREAKING (Scala) -- `mkdirs` over a name occupied by a file returns `false` instead of
  throwing.** `Files.createDirectories` threw `FileAlreadyExistsException` there, which made the
  `Boolean` decorative: success returned `true` and every failure threw, so `false` was
  unreachable. The Rust port already had the re-check semantics; its doc claimed the Scala did
  too, and the probe showed the claim false. The Scala now matches its own documentation.
- **Documented, not changed:** on a refused overwrite `copyTo` throws in Scala and returns `None`
  in Rust -- the port's error shape is values, not panics. The probe pins this as its one designed
  difference.
- The probe also **confirmed** parity where nothing had: `lastModifiedYMD`/`weekDay`/
  `weekDayName`/`epoch2DateTime` render identically, `renameViaCopy` status codes, `renameToOpt`
  collision handling, `realPath` on a missing child, `length`/`isEmpty` on missing files, and
  `write`/`writeLines` roundtrips -- 53 of 54 lines byte-identical.

**DOCS — the Rust crate gets its introduction**

- The README's "Rust companion crate" section was three releases stale: it still named the
  crate `t3prf` and described only the numerics, while the port had grown to the whole
  path/time/decimal surface. Rewritten as the crate's actual introduction -- the naming
  contract, module coverage, the nine parity fixtures and the probe pair -- with every count
  verified against the audit rather than asserted.
- New `rust/README.md` for anyone landing in the crate directly: the contract, a module
  table, the parity methodology and its regeneration warning, build lines, and pointers back
  to the per-area Scala docs.

**BREAKING — the generic-named deprecated members are removed too**

- Gone: `Path.name`, `Path.file`, `Path.text`, `JFile.name`, `JFile.path`, `String.path` and
  `String.toPath`. Replacements: `last`, `asFile`, `contentAsString`, `asPath`. The compiler
  had already proven all 503 `vast`+`apps` sources clean of them (a `-deprecation` clean
  compile shows zero warnings), and grep could not: it counted 598 "uses" of `.name`, every
  one a different receiver.
- **New `JFile.asPath`** (and its Rust identity alias), completing the conversion square with
  `Path.asFile` and `String.asPath` -- also what the migration rule `.path -> .asPath` needs
  to land on for a `File` receiver.
- The `jsrc` script corpus (935 uni_3 scripts) is migrated per-script by
  `applyMigrations.sc`, whose rule table gained the missing rules: `.lcname`, `.trimmedLines`,
  `.toPath`, and `.text` (the last with an XML caveat -- `NodeSeq.text` is not a file read).
  Scripts still using the removed members fail loudly at compile and migrate on touch, which
  is the corpus's established mechanism.

**BREAKING — the 0.9-era deprecated members are removed**

- Gone: `basename`, `lcbasename`, `lcname`, `lcsuffix`, `suffix`, `trimmedLines` (both the
  `Path` and `JFile` extensions), `Big.BadNum`, `Big.BigZero`, and
  `FastCsv.autoDetectDelimiter` with its tests. Replacements: `baseName`, `baseName.lc`,
  `last.lc`, `ext.lc`, `ext`, `lines`, `BigNaN`, `Big.zero`, `uni.io.Delimiter.detect`.
- None of these exist in the Rust port -- deliberately -- so their removal makes the two API
  surfaces genuinely identical rather than Scala carrying a deprecated surplus.
- **Two measurement findings from the sweep, worth more than the sweep:**
  - A clean `-deprecation` compile of the entire `vast`+`apps` corpus produced **zero**
    warnings for any uni-deprecated member: every apparent grep hit was a *different* API --
    pallet's (`vastblue.*`), a local extension (`TaxFile.lcname`, `ScalaSource.suffix`), or a
    class's own member (`Link.basename`). Grep counted 598 "uses" of `.name`; the compiler
    counted zero.
  - **Scala 3 `export` aliases silently drop `@deprecated`.** `BadNum` was re-exported from
    `object Big`, so its 20+ real call sites never produced a single deprecation warning in
    the years it was deprecated. An export-aliased deprecation reaches nobody.
- 24 call sites rewritten across 15 files (1 in `apps`, 14 `jsrc` scripts), each first
  verified to resolve to uni -- scripts on `pallet_3`/`unifile_3`, files with local shadows,
  and the self-referential `applyMigrations.sc` rule table were excluded. Two rewrites were
  applied and then reverted when compilation proved the receivers were vast's own types.
- Not removed: the class-2 generic names (`name`, `file`, `text`, `path`, `toPath` --
  compiler-proven unused in the sbt corpus but unmeasured in scripts), and
  `posixAbs`/`posixRel`, whose planned end state is `private[uni]`, not removal.

**RUST — the crate is `uni` now, not `t3prf`**

- With the port complete, the old name described one module of six. `Cargo.toml` renames the
  package and lib to `uni`, every test/example/bench import moves from `use t3prf::` to
  `use uni::`, and the docs drop their "currently named" apology. Done **before** any release
  or external consumer exists, which is the only cheap moment to do it.
- The `t3prf` *module* keeps its name -- it genuinely is the Kelly-Pruitt 3PRF estimator, and
  `uni::t3prf::...` reads correctly. Nothing about its API changed.

**RUST — `Big` ported, and with it the last four loaders: the audit reads 92 of 92**

- `udata::big` is a hand-rolled arbitrary-precision decimal with `java.math.BigDecimal`
  semantics -- base-10^9 limbs, no dependency, which was the choice made for accounting
  exactness over convenience. `loadMatBig`, `loadMatB`, `loadSmartBig` and `readCsvB` follow
  as thin wrappers, with `CsvCell for Big` making the BigNaN sentinel the missing-cell value
  (unlike `i64`'s lossy zero). `eachPath` followed as an alias of `pathsIter().for_each` --
  in Rust it scopes nothing (the iterator closes on drop), but a ported line compiles
  unchanged, the same contract as `asFile`. **The audit reads 92 of 92: every `Path`
  extension method has a Rust counterpart under the same name.**
- **The rule nobody documents, found by probing rather than reading:**
  `scala.math.BigDecimal(String)` never rounds at construction -- a 35-digit literal keeps 35
  digits and carries `MathContext(35, HALF_EVEN)`, i.e. `max(34, literal digits)` -- and every
  operator applies the **left operand's** context to the exact Java result. The first
  implementation assumed textbook DECIMAL128-everywhere and failed 22 of 335 fixture rows;
  one probe printing `.mc` exposed the real rule, and encoding it fixed all 22 at once.
- What the fixture pins beyond arithmetic: division's preferred-scale and trailing-zero
  stripping (`2.50 / 0.25` is `10`, not `10.00...`), `sqrt`'s exact-result scale (`2.25` ->
  `1.5`), `toString`'s scientific-notation thresholds, `toInt`/`toLong`'s low-order-bits
  truncation (`1e40.toLong` is `-5047021154770878464`, ported because callers compiled
  against it), Java's `Double.toString` layout on the `from_f64` path, the `$`/`,`/`%%`
  parse grammar, and the sentinel surviving every operation by short-circuit. 335 rows,
  `java.math.BigDecimal` as transitive oracle, generated by `BigParityGen`.
- The committed loader CSV deliberately contains `$2,000.00` **unquoted**, so the comma
  splits it -- padding, missing cells and the currency grammar are pinned in one file.
- Divergences, documented and loud rather than silent: Scala throws on `sqrt` of a negative
  and on a negative `pow` exponent; the no-panic port answers BigNaN, and the fixture records
  neither. What is NOT ported: the wider `Mat[Big]` arithmetic surface -- this is the type and
  the loaders, not the matrix algebra.

**RUST — `SmartParse` ported: the whole of `uni.time` now has a Rust counterpart**

- `utime::smartparse` -- `parseDateSmart` reads a date string of unknown format or answers
  `BAD_DATE`, total by contract, with `classify`, `numericDateOrder`, `parseDate` and the
  `*With` configured forms beside it. No regex engine and no date crate: the Scala's five
  regexes became character scans, `java.text.Normalizer` became a whitespace-and-fullwidth
  fold, and `DateTimeFormatter.ISO_DATE_TIME` a hand ISO reader.
- **Ported faithfully, warts included.** The ISO fast path is dead in both languages -- the
  dispatcher never answers `ISO8601`, so an ISO string travels the YMD token path and
  fractional seconds drop to `nano = 0`. The 2-digit-year split at 30 and the edit-distance-1
  month fuzzing are reproduced, not repaired. One divergence is documented rather than hidden:
  a typo within distance 1 of two months resolves by unspecified `Map` order in Scala and by
  calendar order in Rust; the fixture pins only unambiguous typos.
- The dynamically-scoped `timeConfig` becomes an explicit `TimeConfig` parameter -- Rust has no
  dynamic scope, and an implicit global would un-Rust the API for no fidelity gain.
- Pinned by `test-data/smartparse-parity/`: **539 recorded answers**, generated by
  `SmartParseParityGen` (in the *test* tree, uniquely -- its corpus is `TestDates`) from the
  shared date corpus plus one input per documented trap, under all four day/month-order
  configurations for the ambiguous cases. `!bad` refusals are recorded too: what does not
  parse is pinned as hard as what does. Checked by `SmartParseParitySuite` (Scala) and
  `smartparse_parity.rs` (Rust); the Rust side reproduced all 539 on its first run.

**RUST — the audit table is closed: 87 of 92, and nothing silent remains**

- Five alias spellings added so a line ported from Scala compiles untranslated: `parentPath` and
  `parentFile` (≡ `getParentPath`; Rust has one path type), `local` (≡ `localpath`), `abspath`
  (`toAbsolutePath.normalize`, distinct from `abs` which consults the filesystem and returns a
  string), and `pathsTreeIter` (≡ `walkIter`). Each is a one-line forward under the
  CamelCase-means-parity rule.
- **The last silent divergence is now loud.** `Charset::by_name` returned UTF-8 for *any*
  unrecognised name, so `lines("Shift_JIS")` decoded properly in Scala and silently mis-read as
  UTF-8 in Rust. It now returns `ErrorKind::Unsupported` for a name it recognises as a real
  charset it has no tables for -- the Shift_JIS/EUC/GB families, Big5, UTF-32, ISO-8859-2..16,
  windows-125x, KOI8, EBCDIC, ISO-2022 -- and keeps the UTF-8 fallback only where the Scala has
  it too, for names `Charset.forName` would also reject. Free of breakage: `by_name` had no
  production callers, the Rust API taking `Charset` values directly.
- What remains unported is exactly: the four `Big` loaders (blocked on `Big`), `eachPath`
  (Scala-only by design; Rust's `DirIter` closes on drop), and `SmartParse`.

**BREAKING — `copyTo` returns `Option[Path]`; a refused overwrite is `None`, not an exception**

- `Some(dest)` when the copy happened, `None` when `overwrite = false` and `dest` exists -- which
  is the shape `renameToOpt` already had, and the answer the Rust port already gave, so the two
  languages now agree and the pair probe's outputs are **byte-identical** with no designed
  difference left.
- Nearly free in practice: every production call site in the corpus (20 across `apps`, `jsrc`,
  `vast`) discards the return value; the only binding uses were one test line and the probe.
- Detected by catching `FileAlreadyExistsException` rather than by an `exists` pre-check, so
  there is no TOCTOU window -- the same reasoning as the Rust side's `create_new`.
- Real I/O failures (permissions, disk) still throw in Scala. In Rust they fold into `None` with
  `try_copyTo` carrying the reason; that residual is inherent to a port that never panics, and it
  is now the *only* error-shape difference in the mutation family.

**BREAKING — `newerThan`/`olderThan` now compare in the direction their names say**

- `a.newerThan(b)` was true when **b** had the larger `lastModified` -- inverted, in both
  languages, with the Rust port faithfully reproducing the inversion. Flipped together, so
  `a.newerThan(b)` now asks whether *a* is newer, and `olderThan` likewise.
- The caller survey is why the flip was safe: the only extension callers in the corpus
  (`manifestClasspath`, twice: `val ojStale = ij.newerThan(oj)`) read as if the name were true,
  so they were silently backwards and are silently corrected. The other two candidates
  (`RouteTripReport`, `sclaunch.sc`) had each written their **own local `newerThan`** with the
  name's semantics rather than use the extension -- independent evidence for which direction is
  the expected one.
- Direction was never pinned in either language, which is how it survived: the only tests
  asserted `false` for non-files, which both directions satisfy. Now pinned by a mirrored pair --
  `PathExtsSuite` and `rust/tests/upath_times.rs` -- and by the `newerThan`/`olderThan` lines of
  the pair probe.

**RUST — the method named `parent` was implementing a different Scala method**

- `UPath::parent` was documented, in its own doc comment, as `PathExts.getParentNonNull`. So the
  API audit matched it to Scala's `parent` **by name** and reported the pair as ported while the
  semantics differed -- and `PathParityGen` records no parent method at all, so nothing tested it.
  A name match counted as parity for the whole life of the port.
- Renamed to `getParentNonNull`, with `parent` and `getParentPath` added beside it. Free of charge:
  there were **zero** `UPath::parent()` call sites -- the one apparent hit was
  `std::path::Path::parent` on a `&Path` cursor inside `realPath`.
- **The three then turn out to coincide in Rust, structurally.** Scala's three differ only for a
  *relative* `java.nio.file.Path`: `getParentNonNull` falls back to self, `parent` absolutises,
  `getParentPath` absolutises only as a fallback. A `UPath` is resolved against its context when
  constructed and is therefore always absolute, so no relative input exists to distinguish them.
  All three names are kept so a ported line reads unchanged.
- That was found by writing the test, not by reading: the first version asserted the Scala
  behaviour and failed, because `resolve(ctx, "loneName.txt")` yields
  `/home/tester/loneName.txt`. The doc comments written minutes earlier claimed a difference that
  cannot occur, and were corrected.
- Pinned by `rust/tests/upath_parent.rs`, which asserts the agreement rather than leaving it
  implied -- including the premise that a `UPath` is always absolute.

**BREAKING — the six `lastMod*` alias spellings are removed**

- Gone: `lastModSeconds`, `lastModMinutes`, `lastModHours`, `lastModDays`, `ageInDays` and the
  already-deprecated `lastModSecondsDbl`, from both the `Path` and `JFile` extensions. Each was a
  one-line forward to the `*Ago` method of the same name.
- Use `lastModSecondsAgo`, `lastModMinutesAgo`, `lastModHoursAgo`, `lastModDaysAgo`. `ageInDays`
  was `lastModDaysAgo` under a name kept for pallet compatibility.
- **Nothing is lost**: no alias had behaviour of its own, which is also why the Rust port never
  carried them. Removing them closes that group of parity exceptions by deleting the difference
  rather than by porting names -- 6 of the 18 unported methods were this one issue.
- `uni.time.TimeUtils.ageInDays(File)` is **untouched**: a standalone function taking an argument,
  not one of these extensions.
- 41 call sites rewritten across 13 files -- 4 in `/opt/ue/apps`, 6 `/opt/ue/jsrc` scripts, 2 in
  `/opt/ue/vast`, and this repo's own suite. Verified: `/opt/ue` compiles (200 `vast` + 303 `apps`
  sources) and all six edited scripts compile under scala-cli, which matters because they pin
  `uni_3:0.16.0` and therefore see the removal.

**BREAKING — the `*Iter` traversal methods are now genuinely lazy**

- `filesIter` was `files.iterator` and `pathsIter` was `paths.iterator`, over an already-listed,
  already-sorted `Seq`. `pathsTreeIter` was `Files.walk(p).iterator.asScala.toSeq.sortBy(...)`.
  All three were eager collections wearing an `Iterator` type, so the name promised something it
  did not deliver.
- That is not cosmetic. On a USB or network directory with thousands of entries an eager listing
  yields nothing until the last entry arrives, which is the difference between usable and not.
- Now backed by `Files.newDirectoryStream` and a lazy `Files.walk` stream, yielding as the
  filesystem returns entries. **They no longer sort**, because sorting requires the complete
  listing -- the two properties are mutually exclusive, and now each spelling provides one:
  `paths`/`files`/`pathsTree` for canonical order, `pathsIter`/`filesIter`/`pathsTreeIter` for
  latency.
- They return `Iterator[Path] & AutoCloseable` and hold an open handle. Exhausting closes it;
  abandoning does not. **New `eachPath(f)`** applies `f` to each entry and closes even if `f`
  throws -- the safe form, as `withLines` is beside `linesStream`.
- `paths` is now built on the same `newDirectoryStream` iterator instead of `File.listFiles`,
  which materialised a `File[]` only for every element to be converted straight back to a
  `Path`. One pass and one collection, then the sort. `paths`/`files` keep `Seq` and their
  canonical order -- a sorted listing has to be complete, so lazy access stays with the
  `Iter` spellings.
- `pathsTree` keeps its sorted contract, now as `Using.resource(pathsTreeIter)(_.toSeq.sortBy(...))`.
  Parent-before-descendant never needed the sort: `Files.walk` is depth-first pre-order, so the
  sort only ever ordered siblings, contrary to what the old comment claimed.
- **RUST:** new `pathsIter`/`filesIter` over `read_dir`, lazy and handle-releasing on drop.
  `walk()` changed from an alias of `pathsTree` (sorted `Vec`) to an alias of `walkIter` (lazy),
  matching Scala -- otherwise the same line means different things in the two languages.
- `test-data/walk-parity/` was unaffected: it pins `paths`, `subdirs`, `subfiles` and `pathsTree`,
  all of which keep their order. Laziness is pinned separately by `uni.LazyTraversalSuite` and
  `rust/tests/upath_lazy_walk.rs`.

**FIXED — a charset wider than one byte garbled every line it read**

- `lines("UTF-16BE")` returned the raw bytes with embedded NULs, not text. The byte-oriented
  reader splits on `0x0A` before decoding; in UTF-16 that byte is half of a code unit, so each
  line arrived with an odd byte count, the strict decoder rejected it, and the reader's Latin-1
  fallback handed back the bytes. `contentAsString(UTF_16BE)` was always correct, which is what
  made the reader look fine.
- Wide charsets now decode the whole file and split the text. Selected by a general predicate --
  does a newline encode to the single byte `0x0A` in this charset -- so UTF-32 and the EBCDIC
  family are covered by the same branch rather than by a list of names.
- **The cost is laziness**, unavoidably: a byte-level split cannot find a terminator that is not a
  whole byte. Single-byte and UTF-8 charsets still stream exactly as before.
- **RUST:** `Charset` gains `Utf16`, `Utf16Le` and `Utf16Be`, with `Utf16` taking its byte order
  from a BOM and consuming it, defaulting to big-endian -- matching the JVM. `encode` emits a
  big-endian BOM for `Utf16` and none for the explicit forms, again as Java does. No dependency:
  `String::from_utf16` and `str::encode_utf16` are in `std`.
- Pinned by `uni.CharsetSuite` and `rust/tests/upath_charset.rs`, mirrored case for case.
- **Still divergent, and now documented as such:** the Scala honours every charset the JVM knows,
  while Rust supports UTF-8, Latin-1 and UTF-16 and falls back to UTF-8 for the rest. The Rust doc
  previously claimed `uni` "does the same" -- true only for names the JVM also rejects. This is the
  one silent parity gap left in the port.
- Also corrected: `upath::mutate` still said `copyTo`'s `overwrite` was "not defaulted, unlike the
  Scala where it defaults to `true`". The Scala default was removed earlier in this same release,
  so the comment documented a divergence that no longer existed.

**BREAKING — line readers split on `\r?\n`; an interior carriage return is preserved**

- Earlier in this release cycle the rule was written as "a line-oriented reader removes **every**
  `\r`". That is not the same rule as the split on `\r?\n` the docs also described, and the two
  disagree on two cases: an **interior** CR (`a\rb`) and a **trailing** CR at end-of-file,
  neither of which has a `\n` to pair with.
- The pattern is the rule that shipped, because it is the self-consistent one. Splitting on
  `\r?\n` is what makes lines immune to the OS that wrote the file -- that was always the
  justification -- and it reaches only the CR a newline consumed. Discarding an interior CR is a
  separate act with a separate cost: it destroys data, silently, in the one API that is supposed to
  be the faithful one.
- **Scala behaviour change.** `PathExts.readNextLine` dropped every `0x0D` byte in a line; it now
  drops one only when the newline consumed it. So `a\rb\nc\r\nd\re\n` reads as
  `[a\rb, c, d\re]` where it previously read as `[ab, c, de]`. `lines`, `linesStream`,
  `withLines`, `eachLine` and `firstLine` are all affected, being one reader.
- Rust matches, and is now simpler for it: the UTF-8 path is plain `BufRead::lines`, whose rule this
  already is, and `strip_cr` is gone.
- Unchanged: a CRLF terminator still loses its CR, so CRLF, LF and a missing final newline still
  yield identical lines; `contentAsString` and `byteArray` still keep the bytes exactly.
- Pinned on both sides, case for case, by `uni.CarriageReturnSuite` and
  `rust/tests/upath_lines.rs`. **Scala had no test for the CR rule at all** before this -- the full
  2546-test suite passed both before and after a change to what every line reader returns, which is
  the sharpest example yet of a suite that looks like coverage.

**RUST — `Path.delim` ported**

- `UPath::delim` reports the sniffed CSV delimiter, or **empty** when nothing was found. It is
  not the same question as the delimiter the parser uses: `csv_delimiter` must name a byte to
  parse with, so it falls back to a comma, while `delim` reports whether a delimiter was found
  at all -- and substituting a comma there would turn "no" into a confident wrong answer.
- Samples 50 rows, matching `Delimiter.detect(p, 50)` rather than `CsvConfig::sample_rows`, and
  returns `String` rather than `Option<char>` because the Scala callers test for emptiness.
- Pinned against the Scala's own answers for seven inputs, including the three that surprise:
  a single-column file, an empty file and a prose file all yield empty rather than a comma.

**RUST BUG — `readCsv` counted the header row as data**

- `upath::matcsv`'s `try_read_csv` took every row as data (its own doc said so), while Scala's
  `readCsv` **is** `loadMatD`, which **is** `loadSmart(p, map).mat` -- and `loadSmart` excludes a
  detected header row. So a CSV with headers came back one row taller than the Scala's, topped
  with a row of `missing` cells.
- Fixed to go through the smart path. Nothing is lost for a headerless file: `loadSmart` drops
  row 0 only when it looks like a header.
- **`csv-parity` did not catch it.** The fixture exercises `csvRows` and `loadSmart` directly and
  so never compared Scala's `readCsv` against Rust's. That is the second divergence this release
  found hiding behind a fixture that looked like coverage -- the first was the carriage-return
  policy, where the `lone-cr` case tested CR as a line *separator* and never inside a line. Both
  surfaced only from writing a test that used the method the way a caller would.

**RUST — the `Double`/`Float` matrix loaders and `csvRowsAsync` ported**

- `loadMatD`, `loadMatF`, `readCsvF` and `loadSmartD` as named wrappers over the generic
  `readCsv::<T>`/`read_csv_smart::<T>`. Thin, but they are why a line ported from Scala needs no
  edit -- the same reasoning as the CamelCase naming and `asFile`.
- `csvRowsAsync` is a real prefetching reader, not an alias: a thread plus a bounded `mpsc`
  channel, no dependency. Bounded so a slow consumer applies backpressure rather than letting the
  reader pull the file into memory; abandoning the iterator closes the channel and ends the
  thread. Tested to yield exactly what `csvRowsStream` yields, in the same order -- including the
  final row, whose loss when the channel closes is the classic failure -- and to release the file
  when abandoned.
- The four `Big`-typed loaders (`loadMatBig`, `loadMatB`, `loadSmartBig`, `readCsvB`) remain
  unported, blocked on `Big`: an opaque `BigDecimal` with `MathContext.DECIMAL128` plus a NaN
  sentinel recognised by equality. `std` has no decimal type, so it would be the port's first real
  dependency.
- Worth recording, since it nearly produced a wrong port: **`MatB` is `Mat[Big]`, not
  `Mat[Boolean]`** (`MatFacades.scala:23`).

**RUST — the carriage-return policy is now enforced, and reads are ported**

- uni's line-oriented readers were taken to remove **every** `\r` from a line, and the Rust port
  was made to match. **That rule was superseded within this same release** -- see "line readers
  split on `\r?\n`" above, which is the rule that shipped. What stands from this entry is that
  Rust's line handling was brought under the Scala's control rather than left to
  `BufRead::lines` by default.
- The Rust port **did not** do this. `split_lines` used `strip_suffix('\r')` and the UTF-8 path
  used `BufRead::lines`, which strips only the terminator. So `a\rb\nc\r\nd\re\n` gave
  `[a\rb, c, d\re]` in Rust against `[ab, c, de]` in Scala. Fixed; both now agree.
- `csv-parity` did not catch it. It has a `lone-cr` case, but that exercises CR as a line
  *separator* -- never inside a line. The fixture looked like coverage and was not. Now pinned by
  `rust/tests/upath_lines.rs`, which also asserts that CRLF, LF and a missing final newline all
  yield identical lines, and that `contentAsString`/`byteArray` keep the bytes intact -- they are
  the escape hatch, and deliberately the only way to see a `\r` through this API.
- `linesStream` and `withLines` ported. Reads with `read_until` on bytes rather than
  `BufRead::lines`, so a non-UTF-8 charset is decoded by `Charset` instead of rejected.
- One place the port is stronger, and it is documented rather than claimed as parity: Scala's
  `linesStream` returns an `Iterator & AutoCloseable` that leaks the handle unless closed or
  exhausted -- which is *why* `withLines` exists beside it. `LineStream` owns its reader, so the
  handle closes on drop whether the caller exhausts it or abandons it. There is a test for the
  abandoned case, observable on Windows where an open handle blocks deletion.
- `asFile` is the **identity** in Rust rather than absent. Scala converts `Path` to
  `java.io.File`; Rust has one path type, so there is nothing to convert to -- but Scala's `File`
  extensions mirror the `Path` ones, so `p.asFile.lastModifiedYMD` ports unchanged. Earlier notes
  called this unportable, which confused the return type with the method.
- `withWriter` **honoured its charset**, having previously accepted and discarded it (`let _ =
  charset`), so a caller asking for Latin-1 silently got UTF-8. Added `Charset::encode` and a
  `TextWriter` that takes text, mirroring the `PrintWriter` Scala hands its body -- deliberately
  not an `io::Write`, which takes bytes and would let a caller bypass the encoding again.
- `isSameFile` **folds case on Windows and macOS**, matching Scala's `samePathString`. It was
  case-sensitive everywhere, so `C:/x/File.txt` and `C:/x/file.txt` -- one file on Windows --
  compared unequal when neither existed and `canonicalize` could not help.

**BEHAVIOUR — directory listings have a specified order, in both languages**

- `paths`, `files`, `subdirs`, `subfiles`, `pathsTree` and `walk` now sort: case-insensitive
  first, case-sensitive as a tiebreak. `listFiles`, `Files.walk` and `read_dir` all promise no
  sibling order, so the same script listed a directory differently on Linux and Windows and no
  fixture could pin it.
- Deliberately **not** `Seq[Path].sorted`, whose `Path.compareTo` is case-insensitive on Windows
  and case-sensitive on Linux. The Rust port could have matched that per-platform -- its
  `is_windows` is data, and `isSameFile` folds case exactly that way -- so this is a choice: one
  platform-independent order means a script lists a directory identically everywhere, and one
  parity fixture instead of the per-platform pair `test-data/path-parity/` needs.
- `Locale.ROOT` on the Scala side, since `toLowerCase()` without a locale maps `I` to a dotless
  `i` under a Turkish locale, which would make listing order depend on where the machine thinks
  it is.
- `walkIter` stays unsorted: sorting needs the whole listing, which is what its laziness avoids.

**API — `copyTo` requires `overwrite`; the rename family does not**

- `Path.copyTo(dest, overwrite, copyAttributes = false)` — `overwrite` has **no default**. It
  used to default to `true`, so `copyTo(dest)` clobbered silently while `renameTo(dest)` —
  defaulting to `false` — did not. The asymmetry ran in the dangerous direction and no reader
  could guess it.
- Flipping the default was considered and rejected: it changes behaviour at every call site with
  **nothing to catch it at build time**. Requiring the parameter makes the compiler name each
  one. `File.copyTo(dest)` is gone for the same reason — that overload *was* the old default.
- Measured across `jsrc`, `apps` and `vast` (1745 sources): **19 call sites**, 1 already
  explicit, 18 needing a decision. Of those, **6 wanted `false`** — and in every one the
  surrounding code already guarded against clobbering, so the old default had been working
  against the author's intent. `bankfileNorm` throws `"avoiding clobber"` three lines above its
  copy; `fnameDate` builds its destination with `nextUniquePath`; `fyoSourceCopy` is only reached
  in the branch where the backup is absent.
- The 13 `true` sites preserve the previous behaviour exactly, so they cannot introduce data
  loss. The 6 `false` sites are the only behaviour change, and the failure mode if a reading was
  wrong is a thrown `FileAlreadyExistsException` — loud, never silent.
- `renameTo`, `renameToOpt` and `renameViaCopy` keep `overwrite = false`: already the safe
  answer, meant by every existing call site, and requiring it would have been ~24 edits for no
  behavioural change. `copyAttributes` keeps its default on the same principle — a flag that can
  destroy data must be stated, one that cannot may default.

**RUST — metadata, traversal and mutation ported**

- `upath::meta` — `exists`, `isDirectory`, `length`, `isEmpty`, `nonEmpty`, `canRead`,
  `canExecute`, `isSymbolicLink`, `isSameFile`, `realPath`. This module *does* use `cfg(unix)`,
  for a permission bit, and that is not a departure from the "everything is data" rule in
  `upath`: that rule is about the path *algorithm*, where `cfg` would make the Windows rules
  untestable off Windows. Here the question is what the host filesystem provides, and the answer
  genuinely differs per platform. **`cfg` for what the OS provides, data for what the path rules
  are.**
- `upath::walk` — `paths`, `files`, `subdirs`, `subfiles`, `pathsTree`, `walk`, and `walkIter`
  (a lazy stack-based `TreeWalk`, so a deep tree cannot overflow and a caller can stop early).
  Fixture-backed via `test-data/walk-parity/`, which **sorts both sides**: neither language
  promises sibling order, so it pins what is traversed and not the order. Pre-order — a directory
  before its contents — is guaranteed and is asserted by unit tests on each side instead.
- `upath::mutate` — `copyTo`, `renameTo`, `renameToOpt`, `renameViaCopy`, `delete`, `mkdirs`,
  `withWriter`, each with a `try_*` form that keeps the outcomes Scala distinguishes. `delete` is
  the example: Scala returns `true` deleted, `false` absent, and *throws* on a real failure;
  `try_delete() -> io::Result<bool>` keeps all three, and `delete() -> bool` collapses them for
  callers who do not care.
- No-clobber copy is **atomic**: `create_new` (O_EXCL / CREATE_NEW) rather than checking
  `exists()` and then calling `fs::copy`, which always overwrites and would leave a TOCTOU window.
  `fs::rename` has no non-replacing variant, so `renameTo` checks first — which matches Java's
  `Files.move` on Unix, race included.
- Semantics pinned that are easy to get wrong: `pathsTree` includes the starting path; the walk
  does not follow symlinks (which also stops a cyclic link hanging it); `subfiles` filters on
  *regular* files, so a device or socket is excluded; a file lists empty but walks to itself.
- `files` and `paths` coincide in Rust — in Scala they differ only in `java.io.File` versus
  `Path`, and there is no second path type here. `files` is kept as an alias.

**BEHAVIOUR — file timestamps are now UTC, and timezone is gone from that API**

- `lastModifiedTime` and `lastModifiedYMD` render in **UTC**. They used
  `ZoneId.systemDefault()`, which no port outside the JVM can reproduce: Rust's `std` has no
  timezone database and no way to read the local offset. The alternative was an offset
  parameter on every call — a value almost no caller has an opinion about, since a file
  timestamp is used either for *comparison*, where any consistent offset cancels, or for
  *display*, where machine-independence is an advantage.
- **This changes printed output.** Measured across the 166-script corpus: `lastModifiedYMD` and
  `weekDay` had **zero** callers, and of eight `lastModifiedTime` callers three are pure
  comparisons (`parquetTableStatus` `maxByOption`, `fyoSourceCopy`'s `sd`/`dd`) where the offset
  cancels. So **three** sites print a value shifted by the local offset: `fidgrab.sc` (an
  `_HH-mm` filename label) and `fidPdf2txt.sc` twice.
- It also removed an inconsistency already present: `epoch2DateTime` has always defaulted to
  `UTC`, so `p.epoch2DateTime(p.lastModified)` and `p.lastModifiedTime` disagreed by the local
  offset for the same file, in the same extension block. They now agree, and a parity test
  asserts it.
- Local time needs no API — it is one explicit call:
  `p.epoch2DateTime(p.lastModified, ZoneId.systemDefault())`.
- `lastModifiedYMD` also stopped constructing a `SimpleDateFormat` per call. That class is not
  thread-safe, and it was the reason this rendered local time in the first place.

**API — weekday off `java.time`, and the abbreviation it was missing**

- `Path.weekDay` returns `Int` (1 = Monday .. 7 = Sunday) rather than `java.time.DayOfWeek`.
  Same numbering, so nothing is lost, and it becomes portable. Zero corpus callers.
- New: `UniDateTime.dayOfWeekName` (`"Mon"`..`"Sun"`), `dayOfWeekFull` (`"Monday"`..), and
  `Path.weekDayName`. This is the form client code was hand-rolling as
  `getDisplayName(TextStyle.SHORT, Locale.ENGLISH)` — several scripts wrap exactly that in a
  local `dow` helper. The explicit `Locale` there is load-bearing: without it the same script
  yields `lun.` on a French machine. These are English by construction.
- Two vacuous assertions surfaced when the type changed: `assert(p.weekDay != null)` was always
  true for a `DayOfWeek`. Now range checks.
- Fixed a comment in `DateFormat` claiming `dayNames` was Sunday-first; the data has always
  been Monday-first, which is what the `dayOfWeek - 1` indexing requires.

**RUST — file timestamps ported to `upath::times`**

- `lastModified`, `lastModMillisAgo`, the four `lastMod*Ago` values, `lastModifiedTime`,
  `lastModifiedYMD`, `weekDay`, `weekDayName`, `newerThan`, `olderThan`, plus `epoch2DateTime`
  and `agoFromMillis`.
- The `Ago` arithmetic is split from the clock read, which is what makes it testable. It also
  had to be transcribed *exactly*: the Scala rounds at every step on an already-rounded value,
  so `daysAgo` is not `round(millis / 86_400_000, 6)`. Measured over 200k realistic file ages,
  the chained and direct forms disagree for **2.0% of inputs**, always by one unit in the sixth
  decimal — and since these read the clock, no fixture could catch a regression.
- Fixed a sign bug found while documenting: `SystemTime::duration_since` returns `Err` when the
  receiver is *earlier*, so the obvious `.ok().unwrap_or(0)` reported a pre-1970 file as the
  epoch. Java returns a negative value there. Now handled by `millisOf`, which measures the
  other way on the error branch.
- The `lastMod*Ago` aliases are deliberately absent: they add names, not capability.

**RUST — public method names now match the Scala spelling**

- `baseName`, `plusDays`, `lastModifiedTime` rather than snake_case, under a **module-scoped**
  `#![allow(non_snake_case)]` — no crate-root allow, so `t3prf` and `numpy_rng` keep the lint.
  The precedent is `windows-rs`, which spells Win32 functions `SetEvent` rather than
  `set_event`: the documented API is the contract, and here that contract is the Scala one.
- 63 methods CamelCase, 326 snake_case. Internal helpers and Rust trait contracts
  (`Display::fmt`, `cmp`) stay snake_case, so **the case says whether a Scala counterpart
  exists**. Where Rust cannot overload or default an argument, one Scala member becomes several
  with a suffix — `ofFull`, `ofYmd`, `isValidFields` — and those keep CamelCase, being API
  rather than internals.
- `is_file` was the only name colliding with a `std` method, so it was renamed by hand:
  `UPath::isFile` now sits directly above a preserved `self.as_std_path().is_file()`.

**RUST — `uni.time` ported to `rust/src/utime/`**

- `utime::datetime::UniDateTime` and `utime::format` mirror the Scala type and formatter:
  same fields, same validation, same day-0 sentinels and `<BadDate>` rendering, same
  epoch-day arithmetic, **no date-library dependency**. That last part is what decoupling the
  Scala type from `java.time` bought.
- `SmartParse` is not ported; only the date type and its formatter are.
- The Scala arithmetic delegates to `java.time`, which the port cannot, so the calendar math
  is written out — Hinnant's civil-calendar algorithms, month-end clamping, time-shift carry.
  `test-data/date-parity/` pins the two languages across 4020 cases, making `java.time` the
  transitive oracle. It caught a real defect on the first run: the port returned
  **2000-03-01 for 2000-02-29**, from using 146097 rather than 146096 as the day-of-era
  divisor — visible only on the last day of a 400-year era.
- Checked from both sides (`uni.time.DateParitySuite`, `rust/tests/date_parity.rs`) so
  regenerating the fixture cannot quietly rewrite the expectations.

**API — the date type is now `uni.time.UniDateTime`, not `java.time.LocalDateTime`**

- `type DateTime` and `parseDate`/`parseDateSmart` now yield `UniDateTime`: a plain case
  class of integer fields. The parser therefore carries no `java.time` coupling and ports
  to Rust as a struct with **no date-library dependency** -- the alternative was `jiff`
  plus a Java-pattern-to-strftime translation layer.
- Source compatibility comes from implicit conversions **both ways**, so a parsed date
  still flows into a `java.time` API and back. All 166 client scripts in the reference
  corpus compile.
- **Everything that produces or shifts a date moved with it**, and had to: `now`,
  `yesterday`, `nowZoned`, `quikDate`, `quikDateTime`, `NullDate`, `whenModified`,
  `epoch2DateTime`, `Path`/`File`.`lastModifiedTime`, `endOfMonth`, the
  `elapsed*`/`*Between` family, `getDuration`, `TimeArith`'s `+`/`-`, and a full
  `plusX`/`minusX`/`withX`/`atStartOfDay`/`lastDayOfMonth` surface on `UniDateTime`.
  Leaving any of them on `LocalDateTime` makes a script that mixes the two infer the union
  `LocalDateTime | UniDateTime`, which satisfies neither position and which no conversion
  repairs. That union was the single largest source of breakage while converting.
- The overloaded `secondsBetween` and `getDuration` gained explicit `UniDateTime`
  alternatives, because **implicit conversions are not applied during overload
  resolution** -- Scala reports "none of the overloaded alternatives match" without ever
  retrying with a conversion in hand.
- `uni.data.CVD` is now `UniDateTime | Big | Option[Int] | String | Int`. A union cannot be
  entered through a conversion, so a `LocalDateTime` member would have been unreachable
  from uni's own parser and `toStr` would have thrown `MatchError` on parsed dates.
- What does **not** survive: a `java.time.LocalDateTime` written in a generic position --
  assigning an existing `Seq[UniDateTime]` to a `Seq[LocalDateTime]`, an explicit
  `Ordering[LocalDateTime]`, or `case d: LocalDateTime`. Each is a compile error, never a
  silent difference. Note `val xs: Seq[LocalDateTime] = lines.map(parseDate)` *does* still
  compile, since the expected element type propagates into `map`.
- Caution: `==` across the two types compiles, warns nothing, and is always `false`. That
  is why the sentinels and the parser's return type had to move in one change rather than
  one at a time.

**BEHAVIOUR — `BadDate`/`EmptyDate` are dates that cannot exist, and print as markers**

- Both now carry **day 0**, which occurs in no month. `of` validates, so no parsed date can
  equal a sentinel: a file containing a real `1900-01-02` yields an ordinary date instead of
  a false parse failure. It previously *was* that value, and 1900-01-02 is a common
  placeholder in financial data -- so the collision was not hypothetical.
- `BadDate.toString` is now `<BadDate>` (`<EmptyDate>` likewise) rather than
  `1900-01-02T03:04:05`, which read as real data in every log line and printed column.
- The pattern formatters still yield digits -- `BadDate.fmt("yyyyMMdd")` is `19000100` --
  because they feed filenames, CSV keys and SQL binds, where a marker would produce
  valid-looking output that quietly matches nothing. Guard those paths with `isValid`.
- `toLocalDateTime` projects each sentinel onto the moment it historically was, so every
  arithmetic helper and every conversion behaves exactly as before.
- Sentinels now absorb arithmetic and `withOffsetMinutes`, as `Double.NaN` does;
  `toEpochDay` yields `Long.MinValue`.

**BUG — `BigNaN` was destroyed by three operations (`Big.scala`)**

`BigNaN` is meant to behave as `Double.NaN` and never participate in arithmetic. Three
operations broke that, and because the sentinel is recognised by *equality*, the loss was
silent:

| operation | before | now |
|---|---|---|
| `BigNaN.setScale(2, HALF_UP)` | `0.00`, `isNaN` **false** | propagates |
| `BigNaN ~^ 2` (integer exponent) | `1.52…E-16`, `isNaN` **false** | propagates |
| `BigNaN ~^ 0.5` (fractional) | threw `NumberFormatException` | propagates |

`setScale` is the serious one: the sentinel is a tiny magnitude, so rounding it to any
ordinary scale produced an unremarkable zero. **A NaN reaching a report formatter emerged
as `0.00`.** All other arithmetic, comparison and conversion operations were already
correct.

The sentinel literal also went from 19 to 28 significant digits, keeping deliberate slack
below `MathContext.DECIMAL128`'s 34 -- an operation that rounded it would corrupt it, which
is exactly the failure just fixed. A test asserts the headroom.

**API — `where` fails loudly, and `whereInPath` is exported (`ProcUtils.scala`)**

- `Proc.where` returned a **multi-line** string on Windows: `where.exe` prints every match
  and `ProcResult.toOption` joins stdout with newlines, so a tool present in both msys and
  System32 came back as two paths. Unusable as a command, and misleading to a caller
  screening the result with `.contains("Windows")`. It now takes the first (PATH-first)
  match.
- Absence now raises `sys.error(s"prog [$prog] not in PATH")` instead of returning `prog`
  unchanged. The old fallback deferred the failure to the launch, where nothing pointed at
  the PATH any more. Use `whereInPath` when absence is a case to handle.
- `whereInPath` is now in the package export, so `import uni.*` reaches it unqualified.
- `unameExe` deliberately uses `whereInPath`, since a Windows host without msys
  legitimately has no `uname` and `where` now throws.
- Worth knowing on Windows: neither function predicts what a *bare* name would launch.
  `ProcessBuilder` goes through `CreateProcess`, which searches the system directory
  **before** the PATH, so `C:\Windows\System32\find.exe` wins over an msys `find.exe`
  however the PATH is ordered. Passing the absolute path is the only way to reach the tool
  the PATH nominates.

**DOCS**

- `docs/DateTimeParser.md` rewritten as the client reference for `uni.time`: the date type
  and its companion, sentinels, formatting, arithmetic, strict `DateOrder`, and the
  pitfalls above. It had documented `ChronoParse` as a fallback parser, which no longer
  exists, and pinned `0.15.2`. Its "BigData Prep" example also did not compile --
  `import uni.data.*` and `import uni.data.BigUtils.*` together make `getMostSpecificType`
  ambiguous.
- `UniScriptingTools.md` and `PathIOReference.md` corrected for the new date type, and
  `PathIOReference.md` gained a table of which subsystems have Rust counterparts.
- `MatDCheatSheet.md` verified rather than read: every MatD expression in its API tables was
  extracted and compiled (148/148 type-check, 6/6 code blocks compile). That found three
  errors -- its "From rows (Seq)" row invited `Seq[Seq[Double]]`, which `toMat`/`fromRows`
  do **not** accept (only `Seq[Array[T]]`); `show` returns the rendering rather than printing
  it, so the documented `m.show` on its own line outputs nothing; and the documented
  `println(m)` cannot compile in a script that shadows `println` to a `String`-only signature,
  as the CR-free output convention requires. All three now documented, with `println(m.show)`
  and `println(s"$m")` as the working forms.
- `PathIOReference.md` and `DateTimeParser.md` updated for the UTC timestamp decision, the
  weekday changes, `upath::times`, and the Rust CamelCase naming convention.

**BUG — ragged CSV rows were silently discarded, sometimes almost all of them (`FileOps.scala`)**

- `loadSmart` took the column count from the *first* row and dropped every row that
  did not match it. A single malformed line lost that line; a malformed **first**
  line lost nearly the whole file:

  | input | matrix before | matrix now |
  |---|---|---|
  | short row in the middle | 2x2 | 3x2 |
  | long row in the middle | 2x2 | 3x3 |
  | **first** row short | **1x1** | 4x2 |
  | **first** row long | 1x3 | 3x3 |

- Rows are now padded to the widest rather than filtered. Widest rather than
  first-row width on purpose: keying off the first row means truncating anything
  longer, which is the same data loss wearing a different hat. A padded cell is
  `""`, which already reads back as NaN — no new concept
- `csvRows` and `loadSmart` now share `FastCsv.rectangular`, because the two must
  not disagree about which rows a file contains. They previously did: `csvRows`
  returned every row while `loadSmart` dropped the mismatched ones
- The streaming readers cannot pad to a true maximum — the width is unknown until
  the last row — so each buffers a 100-row window, takes the widest row in it, and
  streams the rest padded to that. A row wider than the window is emitted at its own
  width rather than truncated
- Reusing the widths `Delimiter.detect` already tallies looked free and does not
  work: `detect` stops as soon as a candidate dominates, which for a row over its
  100-character check interval happens partway through the **first** row. Every row
  is then recorded as truncated, `rowCounts` comes back empty, and nothing is padded
  — on most real files. The width is now measured by the reader, from rows the real
  parser produced, so it is exact for the window rather than an undercount
- `eachRow` — which backs the callback overload of `PathExts.csvRows` — had neither
  the blank-row filter nor padding, so `p.csvRows` and `p.csvRows(fn)` disagreed
  about a file's contents. Now aligned, and pinned by a shared fixture

**BUG — `FastCsv.rowsAsync` hung when `hasNext` was called twice after exhaustion**

- The producer thread puts exactly one end-of-stream marker. `hasNext` took from the
  queue on every call, so a second call after the marker blocked forever on a
  producer that had already exited. Any caller checking twice hung outright
- `hasNext` is now idempotent

**BUG - `SmartParse` lost the time whenever the year came before it**

- `parseMonthDayYear` / `parseDayMonthYear` took the time to be the numbers *between*
  the day and the year. That holds for `Aug 18 22:29:47 2018` and little else:

  | input | before | now |
  |---|---|---|
  | `01 Jan 2001 12:34:56 -0700` | `2001-01-01T00:00` | `12:34:56` |
  | `Sun, 12 May 2024 14:30:00 GMT` | `2024-05-12T00:00` | `14:30` |
  | `May 12, 2024 2:30 PM` | `2024-05-12T12:00` | `14:30` |

- The first two are RFC 2822, i.e. every email header. The third read `2:30 PM` as
  noon because the PM adjustment was applied to an hour of zero. The time is now
  whatever numbers remain once day and year are accounted for, with positional
  validation so a trailing `-0700` cannot fill the seconds slot.
- Measured against the project's 132-date corpus, disagreements with `ChronoParse`
  fell from 24 to 11, and every remaining one is `ChronoParse` being wrong.

**BUG - `parseDateSmart` could throw instead of returning `BadDate`**

- `12:34 -0700` put 700 in the seconds field and `LocalDateTime.of` threw
  `DateTimeException`. The sentinel is the whole reason this is usable for a CSV
  column without wrapping every cell in a `Try`, so the boundary now catches.

**NEW - enforceable day/month order (`DateOrder`, `inferDateOrder`)**

- `monthFirst` only ever decided the *ambiguous* case; an unambiguous date overrode
  it. So a consistent European column parsed as a mixture under the default -- rows
  with a day of 12 or less read month-first, the rest day-first -- and nothing looked
  wrong. The file was internally consistent; the parse was not.
- `DateOrder.Auto` is that behaviour and remains the default, so nothing has to be
  declared. `MonthFirst` / `DayFirst` enforce, turning a contradicting date into
  `BadDate` instead of silently reinterpreting it.
- `withDateOrder(order) { ... }` is dynamically scoped and thread-local, like
  `withTimeConfig`. It derives from the enclosing config, so `Auto` keeps an outer
  `monthFirst` rather than resetting it.
- `inferDateOrder(column)` reads the convention off the unambiguous rows: `Some(order)`
  when one is proved, `Some(Auto)` when nothing is decisive, and **`None`** when rows
  prove both -- a genuinely mixed column, reported rather than guessed at.

**CHANGED - `ChronoParse` deprecated; two of its formats folded into `SmartParse`**

- `parseDate` is now `parseDateSmart` alone. Across the 132-date corpus `ChronoParse`
  rescued **zero** inputs that `SmartParse` failed, and where both succeeded and
  disagreed it is the wrong one: `12:00:00 AM` as noon, `2nd Jan 2020` as February 1st,
  `19 Jun.2023` as 2026.
- The two formats it did handle alone are folded in, as `preNormalize` rewrites so the
  existing ISO/YMD path does the parsing: bare `yyyyMMdd` (with `HHmm`/`HHmmss`, since
  the compact time is part of the same format) and a leading time
  (`13:45 2020-01-02`). Guarded: `99999999` and `20201345` stay `BadDate`.
- `parseDateChrono` is `@deprecated`, not removed -- `TimeUtils` still uses its
  `monthAbbrev2Number`, and deleting 900 lines is a separate step.

**BUG - `SmartParse` silently dropped part of the time when a number collided with the year**

- `parseMonthDayYear` and `parseDayMonthYear` found the year's *value*, then searched
  the number list for its *position* by value, allowing a two-digit expansion. That
  matched whichever number expanded to the same year, and the slice between day and
  year then cut the time at the wrong point:

  | input | before | now |
  |---|---|---|
  | `Aug 18 22:29:47 2018` | `2018-08-18T00:00` | `2018-08-18T22:29:47` |
  | `Aug 1 22:29:47 2022` | `2022-08-01T00:00` | `2022-08-01T22:29:47` |
  | `Aug 1 22:29:47 2029` | `2029-08-01T22:00` | `2029-08-01T22:29:47` |

- Three separate collisions, one per field: the day `18` expands to 2018, the hour
  `22` to 2022, the minute `29` to 2029. Nothing threw and nothing returned `BadDate`
  -- the results were plausible timestamps -- and it struck roughly one row in a
  hundred. For the CSV date-column use case that is the worst available failure mode.
- The year and its index are now determined together, preferring an unambiguous
  4-digit token and falling back to the 2-digit reading at its fixed position. A
  regression suite sweeps 28 days x 46 years through both parsers: 0 wrong, from 56.
- `Aug 18 22:29:47 2018` is the standard `ls -l` / syslog timestamp, so this was not
  an exotic input.

**BUG - `noTrailingSlash` stripped only one trailing separator**

- It used `stripSuffix("/")`, so `/usr/bin//` came back as `/usr/bin/` -- still
  trailing, from a function whose name says otherwise. It now strips all of them.
  `/` keeps its slash, and a bare `C:` must not *gain* one: `C:/` is the drive root
  while `C:` is drive-relative, and turning one into the other changes which
  directory is meant.
- Affects `joinPosix` and `toPosixAbs` as well as `posx`, all of which route through
  it. Mirrored in the Rust port.
- `posx` itself is unchanged in meaning: it converts `\` to `/` and nothing more.
  Interior runs of separators are deliberately *not* collapsed -- `"a//b".posx` stays
  `a//b`. Collapsing duplicates is what `Paths.get` does, so code wanting that should
  go through a path rather than a string.

**BUG - `Path.relpath` never returned a relative path, and mixed two working directories**

- It was `standardizePath(relativePathToCwd(p))`. The first computed the relative
  form; the second immediately threw it away by calling `toAbsolutePath`:

  | call | before | now |
  |---|---|---|
  | `Paths.get(cwd + "/src/main").relpath` | `/Users/philwalk/workspace/uni/src/main` | `src/main` |
  | `Paths.get(cwd).relpath` | `/Users/philwalk/workspace/uni` | `.` |
  | `Paths.get("/tmp").relpath` | `/tmp` | `/tmp` |

- The worse half: the two halves disagreed about which working directory they meant.
  `relativePathToCwd` relativised against the *config's* `pwd` while
  `standardizePath` re-absolutised against the *JVM's* `user.dir`. Under an injected
  user those differ, so the answer named a different file -- `C:/munit/test/foo` came
  back as `/c/Users/philwalk/workspace/uni/foo`
- `relpath` is now the `Path`-facing form of `posixRel`, which is what that method's
  own deprecation note already promised ("Use `Path.relpath`"). The two returned
  different answers for the same input; `PathSpec` had already worked around it by
  calling `posixRel` directly

**BUG - the mount root mapped to the empty string, and a sibling matched it (`toPosixAbs`)**

- Found while fixing `relpath`, which surfaced the divergence. Two defects in one
  expression, `cygMixed.startsWithIgnoreCase(config.cygRoot)`:

  | input | before | now |
  |---|---|---|
  | `toPosixAbs("C:/msys64")` (the root) | `""` | `/` |
  | `Paths.get("/").posix` | `""` | `/` |
  | `toPosixAbs("C:/msys64extra")` | `extra` | `/c/msys64extra` |

- Dropping the whole prefix left nothing, so the root of the filesystem came back as
  the empty string. And the prefix test had no segment boundary, so a *sibling*
  directory whose name merely starts with the mount root was rewritten as though it
  were inside -- returning a relative string for an absolute path. Same defect
  `standardizePath` had; both now go through `Resolver.findPrefix`
- Fixed identically in the Rust port (`upath::resolve::windows_to_posix`), which had
  faithfully reproduced both. The parity fixture missed them because it had no
  mount-root input; `C:/msys64`, `C:/msys64/`, `C:/msys64/usr`, `C:/msys64extra` and
  `C:/msys64extra/x` are now among its cases

**Windows path rules are now testable on Linux and macOS**

- `isWin` came straight from `scala.util.Properties` (i.e. `os.name`), so the Windows
  rules could only be exercised on Windows. `PathsConfig` gained an injectable
  `isWindows`, defaulted from the real platform and settable through
  `withMountLines`. Nine rule-selection sites read it: the main gate in
  `resolvePathstr`, two in `parseMountLines`, plus `safeAbsolutePath`, `toPosixAbs`,
  the drive-letter guard, `PathExts.localpath`, `PathExts.dospath` and
  `StringExts.local`
- Windows-only tests dropped from ~117 to 13. `PosixFmtSuite`'s 66 mount-translation
  cases and all 17 of `SyntheticMountsSuite` now run everywhere
- `PathParitySuite` used to *fail* off Windows rather than skip: it required
  `scala-reference-$platform.txt` and only the Windows file was committed.
  `PathParityGen` now emits both blocks from one run on any host, and the suite
  checks both (9 tests -> 18). The Rust test already looped over both platforms
- What stays host-bound, and why: anything reaching `java.nio.file.Path` renders
  through the *host's* FileSystem, which no flag can override --
  `Paths.get("/munit/test").toString` is backslashed on Windows regardless, and
  `Paths.get("F:/")` is a root there but a relative name on Linux. So the fixture
  records `classify`/`win`/`posixabs` for both platforms and the extension-method
  rows only for the generating host. Environment probes (`mount.exe`, `cygpath.exe`,
  `File.listRoots`) still read the real platform, as they must
- Four tests were gated for no reason at all: `winAbsToPosixAbs` is pure string work
  with no `isWin` in it, and `UniRootCoverageSuite`'s `resolveWindowsPathstr` cases
  were reading the *ambient* MSYS mount table. Those now use an injected table, so
  they no longer depend on a particular developer's install

**BUG - drive-relative paths (`C:`, `C:foo`) resolved wrongly, and `C:foo` threw**

- `Resolver.resolvePathstr` calls `applyTildeAndDots` *before* `classify`, and
  `applyTildeAndDots` claimed any two-character drive string for itself. So the
  `DriveRel` branch that exists to handle these -- `resolveDriveRelPathstr`, the one
  place that knows the per-drive working directory -- never saw them:

  | input | before | now |
  |---|---|---|
  | `Paths.get("C:")` | `C:\...\uni\.` | `C:\...\uni` |
  | `Paths.get("C:foo")` | **throws** `InvalidPathException` | `C:\...\uni\foo` |
  | `Paths.get("C:foo/bar")` | `C:\...\uni\.\foo\bar` | `C:\...\uni\foo\bar` |

- `C:foo` contains no `/`, so it fell into the bare-filename branch and was appended
  to the user directory as `C:/Users/.../uni/C:foo` -- a colon buried mid-path, which
  `java.nio` rejects. `C:foo/bar` escaped that only by containing a slash
- The stray `.` came from `driveCwd` building its answer from the string `C:.`:
  `Paths.get("C:.").toAbsolutePath` keeps the dot, `Paths.get("C:").toAbsolutePath`
  does not. `toAbsolutePath` is what asks Windows for the drive's own cwd
- Expectations are asserted against `java.nio.file.Paths` rather than written by
  hand, so they cannot drift from real Windows behaviour
- The committed path-parity reference had recorded the broken values, including
  POSIX paths with backslashes in them (`posixabs "C:"` was `/c\munit\test`, and
  under a `cygdrive` mount it ignored the prefix entirely). Regenerated; the diff is
  36 lines, all of them the `C:` and `F:` cases
- Fixed identically in the Rust port (`upath::resolve::expand_bare`), which had
  faithfully reproduced the same defect. Both fixes are guarded so that off Windows
  `C:foo` stays an ordinary filename containing a colon

**BUG — `hash64` ignored bytes 32–63 of every 64-byte block (`Hash64.scala`)**

- `processChunk` advanced 64 bytes per chunk but mixed only offsets 0, 8, 16 and 24,
  so half of every block never reached the hash. Two 128-byte inputs differing in 64
  of their bytes hashed identically — for a duplicate-file finder, a false positive
  on entirely different files
- Fixed by mixing both 32-byte stripes. A test now flips every byte position in a
  128-byte file and requires the hash to change
- **`hash64` values recorded before this change do not match values produced after
  it.** Only inputs of 64 bytes or more are affected; shorter files never reached
  `processChunk` and are unchanged
- `md5`, `sha256` and `cksum` are unaffected
- Blank header labels become canonical `colN`, numbered by position so `col3` is
  the third column. Padding a short header row would otherwise produce columns that
  cannot be looked up by name — trading a lost row for an unreachable column. A
  file with no header row still reports no headers
- Six regression tests, including that `csvRows.length` equals `readCsv.rows` for
  every ragged shape

**BUG — `FastCsv.rowsAsync` discarded every row of a single-column file (`FastCsv.scala`)**

- The two readers filtered rows differently: `rowsPulled` dropped rows with no
  non-blank field, `rowsAsync` dropped rows with fewer than two fields. Both
  carried the same comment — "discard if empty or text-with-no-delimiter" — and
  neither implemented it; they had drifted into opposite halves of it
- Consequence: `rowsAsync` on a one-column CSV returned an empty iterator. The two
  also disagreed on blank rows (kept by async, dropped by pulled) and on ragged
  rows (dropped by async, kept by pulled)

  | input | `rowsPulled` | `rowsAsync` |
  |---|---|---|
  | `alpha
beta
gamma` | 3 rows | **none** |
  | `a,b` / ` , ` / `c,d` | 2 rows | 3 rows |
  | `a,b` / `solo` / `c,d` | 3 rows | 2 rows |

- Unified on `isContentRow` — at least one field with non-whitespace content —
  named once so the two cannot drift again. A blank row is dropped; a single-field
  row is kept, because one column is a legitimate CSV and dropping it silently is
  worse than keeping a delimiter-less line
- Two regression tests: one asserting the readers agree across all four shapes, one
  pinning the single-column case on `rowsAsync` specifically
- Found while scoping the Rust CSV port, which will now reproduce one rule rather
  than choosing between two

**NEW — Rust file I/O (`rust/src/upath/io.rs`)**

Tier 3, first half: the text read/write surface.

- Every operation exists twice. `uni`'s I/O is deliberately total — a missing file
  gives an empty result and nothing throws — which is good for scripting but would
  make failures permanently unreportable if that were the only form. So `lines()`
  returns `vec![]` and `try_lines()` returns the `io::Error`, with the total form a
  one-line wrapper over the fallible one
- Ported: `byteArray`, `contentAsString` (both arities), `lines`, `linesStream`,
  `firstLine`, `eachLine`, `write`, `writeLines`
- `Charset` covers UTF-8 and Latin-1, which is all `uni` actually uses —
  `contentAsString` tries UTF-8 then falls back to Latin-1. `Charset::by_name`
  resolves anything unrecognised to UTF-8, matching `Charset.forName` being caught
  and defaulted. `#[non_exhaustive]`, so a real encoding table can arrive later
  without breaking callers and without a dependency today
- `writeLines` keeps its trailing newline, and always emits LF — the Scala comment
  says it "ensures the file isn't missing a newline at EOF", so it is intent rather
  than an accident to tidy away. Both are pinned by tests
- `as_std_path()` is the bridge to `std::fs`. No wrappers for `exists`, `len`,
  `read_dir` and the rest: they are one-liners on the far side of that call
- 8 round-trip tests against a real temp directory, covering CRLF input, the
  trailing newline, invalid UTF-8 falling back to Latin-1 while strict UTF-8
  errors, and the total-vs-fallible split on a missing file

**NEW — Rust path-string conversions (`rust/src/upath/ext.rs`)**

Tier 2 of the `uni.Paths` port: the `Path` extension methods scripts actually call.

- `UPath` reproduces what `java.nio.file.Path` contributes, which is the reason
  this needed a type rather than a handful of string functions. Measured, not
  assumed: `Paths.get` collapses duplicate separators (`a//b` → `a/b`) and drops
  trailing ones (`a/b/` → `a/b`), but does **not** resolve dot segments — `a/./b`
  and `a/../b` survive intact, since only `normalize()` touches those
- Ported: `posx`, `local`/`localpath`, `dospath`, `posix`, `noDrive`, `last`,
  `baseName`, `ext`, `dotsuffix`, `extension`, `segments`, `reversePath`, `parent`,
  `stdpath`, `relpath`, `abs`/`abspath`, plus `normalize`, `toAbsolutePath`,
  `relativize` and `isAbsolute` as the supporting Path machinery
- `abs`, `stdpath` and `relpath` are covered by unit tests rather than the shared
  fixture, because each consults the machine: `abs` branches on `Files.exists`, and
  the other two reach `toAbsolutePath`, which resolves against the JVM's real
  `user.dir` — Java knows nothing about an injected config. Pinning them would have
  committed this checkout's own paths (it briefly did: 126 lines)
- `segments` yields Java's *name* elements only, so `C:/Users` has one and `C:/`
  has none; `last` on a root-only path is an error, where the Scala throws on a
  null `getFileName`
- `dospath` is deliberately not `localpath`: Java renders a root with its trailing
  separator (`\server\share\`), which `posx` strips from the UNC form

**Path fixture now pins the extension methods (`test-data/path-parity/`)**

- Previously only `classify`, `resolvePathstr` and `posixAbs` were pinned — the
  internals. Scripts call `.posx`/`.posix`/`.dospath`, which differ from those
  precisely because `Paths.get` normalises on the way in, so the two `posixAbs`
  could agree while the two `.posix` diverged
- 11 extension fields added across all 9 mount tables, plus inputs covering
  interior `.`, `..` and doubled separators. 4,127 cases, up from 638
- It immediately earned its keep: `Paths.get` special-cases `file://`
  (`Paths.scala:26-31`), routing URIs through `resolvePath(uri)` rather than
  rejecting them, so `Paths.get("file:///c/tmp").posx` is `C:/tmp` while
  `Resolver.resolvePathstr` on the same string throws. Nothing pinned that
  asymmetry before, and the Rust port did not have it
- Both harnesses had the same encode/decode bug — re-encoding an empty result as
  `!empty` when the fixture side had already decoded it to `""`. The generator
  still writes `!empty`, which keeps the committed file unambiguous
- The Scala suite evaluates these fields via `PathParityGen.extFields`, so the
  suite and the generator cannot disagree about what a field means

**Found — `relpath` never returns a relative path, and mixes two working directories**

Not fixed; recorded because it needs a decision rather than a patch.

- `relpath` is `standardizePath(relativePathToCwd(p))`. The first half relativises
  against the working directory, the second half calls `toAbsolutePath` and
  absolutises it again — so the result is always absolute, despite the name and
  despite `docs/PathIOReference.md` describing it as "path relative to the current
  working directory"
- Worse, the two halves disagree about *which* directory is current:
  `relativePathToCwd` reads `config.userdir`, which `withMountLines` can inject,
  while `standardizePath` falls through to Java's real `user.dir`. Visible in one
  pair of values under an injected user — `stdpath "."` gives the injected
  `/c/munit/test`, `relpath "."` gives the real checkout path
- So the earlier `pwd` fix only reached half of it. The Rust port has both halves
  agreeing, which makes the relativise-then-absolutise round trip an exact no-op
  and `relpath` identical to `stdpath` — arguably revealing that the relativisation
  step earns nothing as written

**BUG — `standardizePath` matched mount prefixes without a segment boundary (`PathsUtils.scala`)**

- It scanned the mount keys itself —
  `keys.filter(pstr.startsWithIgnoreCase).sortBy(-_.length).headOption` —
  longest-first, but a plain string prefix. So `C:/msys64extra/x` matched the
  `c:/msys64` root mount and `.stdpath` returned the bare remainder `extra/x`: a
  relative string standing in for an absolute path
- Now uses `Resolver.findPrefix`, which requires the next character to be `/` or
  `:` and is the matcher the rest of resolution already uses. `C:/msys64extra/x`
  falls through to the drive mount and yields `/c/msys64extra/x`; the genuine child
  `C:/msys64/usr/bin` still resolves to `/usr/bin`
- This was the third hand-rolled prefix scan in the codebase, after the two in
  `String.local`. All three are now the one matcher
- Covered by a new test in `PathsUtilsCoverageSuite`, verified to fail against the
  old scan

**BUG — `String.local` produced garbage on Windows (`StringExts.scala`)**

- Every Windows result was wrong: `/tmp/x` came back as `Tmp/x`, `/c/Users` as
  `C/Users`, `/usr/bin` as `Usr/bin`. Two faults in one expression:
  - `winSeq.head` was taken from a `posix2win` value, but that map is
    `LcLookupMap[String]` — `.head` was the first *character* of the Windows path,
    not the path. The variable name suggests `win2posix` (`Seq[String]`) was meant,
    which maps the other direction
  - `collectFirst` over an unordered map returned an arbitrary matching prefix with
    no segment-boundary check, so the synthetic drive mount `/t` beat the real
    `/tmp` — the stray `T` in `Tmp/x`
- Now routed through `Resolver`, which already does longest-prefix matching with a
  boundary check and is covered by the cross-language parity fixtures
- It survived because the Windows assertion in `StringExtsSuite` was
  `assert(result.nonEmpty)` — true of garbage. Replaced with value assertions over
  a synthetic mount table, plus an equivalence check against `Path.localpath` that
  cannot drift with the machine's mounts
- Non-POSIX input is still returned untouched, which is what makes `String.local`
  differ from `Path.localpath`; that distinction is now documented and tested

**`Path.local` now yields the native form (`PathExts.scala`)**

- It was `normalizePosix(p.toString)` — character-for-character identical to
  `.posx`, which made the pair pointless. The intended split is that `.posx`
  always gives forward slashes and `.local` gives whatever the platform uses, so
  `.local` is now an alias for `.localpath`: `C:\tmp\x` on Windows, unchanged
  elsewhere
- **Behaviour change** for callers of `.local` on Windows. `docs/PathIOReference.md`
  documented the old aliasing and has been corrected

**`pwd` tracks the active config (`PathsUtils.scala`, `Paths.scala`)**

- `pwd` was a top-level `lazy val`, so it captured `config.userdir` at first access
  and never saw a later `withMountLines`. Correct in production — the JVM's
  `user.dir` is fixed for the process — but it meant an injected user was ignored,
  so `relativePathToCwd` and `Path.relpath` relativised against the wrong directory
  and could not be covered by the synthetic-mount suites
- Now a `def` reading `PathsConfig.pwdPath`, a `lazy val` on the config itself. The
  cache lives where the lifetime does: one instance per `withMountLines`, so it is
  invalidated exactly when the user changes, and `pwd` stays allocation-free at the
  call site. No observable change in production

**BUG — the RNG parity fixture was never tracked (`.gitignore`)**

- `.gitignore` has `test-data/*` with a single `!test-data/tprf3-parity/`
  exception, so `test-data/numpy-rng-parity/` — added last commit — was silently
  ignored and never committed. `rust/tests/numpy_rng_parity.rs` and
  `uni.data.NumPyRngParitySuite` both pass on the machine that generated it,
  because the file is simply there, and both fail everywhere else with a missing
  fixture. The failure mode is invisible to the author by construction
- Exceptions added for `numpy-rng-parity/` and `path-parity/`, and the comment now
  says that each new fixture directory needs one

**Deprecated — `posixAbs` and `posixRel` (`PathsUtils.scala`)**

- Both are plumbing behind `Path.posix`, `String.posix` and `Path.relpath`, are
  undocumented, and have no callers outside the library. They will become
  `private[uni]` in a later release; deprecating first rather than narrowing
  outright, since narrowing is source-breaking for a published artifact
- Each is now a one-line delegate to a `private[uni]` `toPosixAbs` / `toPosixRel`
  holding the implementation. That keeps the library's own call sites
  warning-free, and when the narrowing happens the deprecated wrapper simply
  disappears rather than needing the body moved
- The only remaining callers of the deprecated names are the suites that test
  them by name (`PathsUtilsCoverageSuite`, `PosixFmtSuite`, `UniRootCoverageSuite`,
  `PathSpec`). Those warn on a fresh compile, deliberately — the warning is the
  reminder that the narrowing is still pending

**BUG — `.posix` threw on any relative path containing a separator (`PathsUtils.scala`)**

- `Paths.get("a/b").posix` raised `IllegalArgumentException`, while
  `Paths.get("bare.txt").posix` returned `/c/munit/test/bare.txt`. `posixRel`
  delegates to `posixAbs`, so it threw too
- Cause: `applyTildeAndDots` absolutises a bare filename but deliberately leaves
  anything containing a slash alone. Such a value survived resolution untouched,
  matched no mount prefix, and reached `winAbsToPosixAbs`, whose
  `require(cygMixed(1) == ':')` fails for a path with no drive letter
- `posixAbs` now returns a relative result unchanged, matching `cygpath -u a/b`,
  which likewise preserves it. The `bare.txt` / `a/b` asymmetry in
  `applyTildeAndDots` is left as-is — narrowing that is a semantics change rather
  than a bug fix, and `cygpath` would preserve both

**BUG — four defects in mount-table parsing and resolution (`Paths.scala`)**

All four are latent behind "if uni is ever handed an fstab": production parses
`mount.exe` output, which has no comments and no `none` line, and on MSYS2 the
default cygdrive prefix `/` happens to be right. The Rust port needs an fstab
fallback — there is no `mount.exe` on non-MSYS Windows, and fstab is the only
source on Linux and macOS — which is how all four surfaced.

- **Comment lines were parsed as mounts.** MSYS2's shipped `/etc/fstab` has 16
  comment lines, several of them commented-out example mounts written with no
  space after the `#`: `#C:/cygwin64 / ntfs binary,noacl,auto` splits into
  `("#C:/cygwin64", "/")`. Those became live entries whose Windows side starts
  with `#`, so resolution built strings like `#C://Users` and `JPaths.get` threw
  `InvalidPathException` — `/c/Users` and `/etc/fstab` failed outright. A
  comment-derived `/c` entry also satisfied `isRealDrive` and suppressed the
  synthetic `C:` drive that should have mapped it
- **`none` was treated as a device.** It is a directive; the shipped line's own
  comment reads *"It removes cygdrive prefix from path"*. Read as a mount it made
  `msysRoot` the literal string `none`, so `/` resolved to `none`
- **cygdrive derivation only examined the first entry.** The `collectFirst`
  pattern `case (win, posix)` is total, so it was satisfied by entry 0 and never
  scanned on; the `else null` shows the intent was to keep looking. The prefix
  silently defaulted to `/` unless the declaring line came first. Measured on
  tables differing only in line order: marker first → `/cygdrive/` and
  `/cygdrive/c/tmp` → `C:/tmp`; marker second → `/` and `/cygdrive/c/tmp` →
  **`none/c/tmp`**. That is the one case where a valid POSIX path yielded a wrong
  `Path`, and it is the normal situation on Cygwin, whose `mount` output does not
  list the drive-root line first. Fixed with guards on the cases
- **A bare-drive mount produced a doubled separator.** `/c/Users` resolved to
  `C://Users`, because a target ending in `:` had `/` appended while the suffix
  already began with one. `JPaths.get` collapses the pair, which is why it went
  unnoticed — but the raw string is what other implementations must reproduce, and
  Rust has no such collapser

Verified against `cygpath -m` on 28 real POSIX paths: the only remaining
differences are a trailing slash on `/`, cygpath's `.exe` suffixing, and
`/proc/cpuinfo`, which has no Windows equivalent.

**NEW — Rust port of mount-aware path resolution (`rust/src/upath/`)**

Tier 1 of the `uni.Paths` port: the mount-dependent POSIX↔Windows conversion, so
Rust client code can be written once for Windows, Linux and macOS. `std::path`
understands drive letters and UNC but nothing about MSYS2/Cygwin mount tables, so
`/database-backups/x` had no way to reach its Windows location.

- `MountMaps::parse` handles both `mount.exe` output and `/etc/fstab` columns,
  derives the cygdrive prefix, and synthesises the `X: -> /x` entries for unmapped
  drives plus a root entry when the table lacks one — which is what makes `/q/file`
  resolve to `Q:/file` on a machine whose fstab never mentions Q:
- `classify` covers all seven path shapes and `find_prefix` does **longest-prefix**
  matching with a segment-boundary check, so overlapping mounts resolve to the
  deeper target: with `/opt` and `/opt/ue` both mounted, `/opt/ue/src` is `D:/ue/src`
- `resolve_pathstr`, `posix_abs`, `posix_rel` and `apply_tilde_and_dots` ported from
  `Resolver` and `PathsUtils`, including the hidden-file carve-out that keeps
  `.gitignore`'s leading dot
- **`is_windows` is data, not `cfg!(windows)`.** Rust's is compile-time, unlike the
  JVM's runtime `os.name`, so gating on it would make the Windows rules
  uncompilable — and untestable — off Windows. One live `cfg!(windows)` remains, in
  `PathContext::from_env`. This is strictly better than the Scala, whose path suites
  are `if isWin`-gated and skip entirely on Linux
- `drive_cwd` needs no `windows` crate and no subprocess. Windows keeps the
  per-drive working directory as **per-process** state in hidden `=X:` environment
  slots, inherited by children; `GetFullPathNameW("X:.")` — what the JVM's
  `toAbsolutePath` calls — is defined in terms of those, so reading them agrees with
  the Scala by construction. A drive never visited has no slot and Windows itself
  answers with the root, so that is the correct answer rather than a fallback
- Two places where fidelity beat instinct, both caught by the fixtures: a bare `C:`
  input keeps Windows **backslashes**, because `applyTildeAndDots` interpolates the
  `Path` verbatim while `resolveDriveRelPathstr` normalises separately; and
  `posix_abs` **fails** for a relative path containing a slash, because it reaches
  the cygdrive fallback with no drive letter, exactly as `winAbsToPosixAbs`'s
  `require` does

**Path parity fixtures (`test-data/path-parity/`)**

- `uni.apps.PathParityGen` writes a per-platform reference over six synthetic mount
  tables × 32 inputs × 3 fields, plus derived facts and `driveCwd` — 618 cases.
  Checked independently by `uni.PathParitySuite` and `rust/tests/path_parity.rs`
- Tables are chosen to pin one thing each: minimal root, drive mounts, cygdrive
  style, **overlapping** mounts, **one-to-many** reverse mapping, and fstab format
  with no root entry
- Every case is driven by `withMountLines` and a fake user, so nothing depends on
  the host's drives. The fixture is tagged with the platform that produced it,
  because Scala can only record the rules of the host it runs on; the Rust test
  takes `is_windows` from the tag and so verifies Windows semantics from any host.
  Regenerate on a second platform to add its block
- 15 Rust unit tests cover the pieces directly, including that the Windows rules
  run with `is_windows = true` regardless of host

**BUG — `NumPyRNG.randn` tail draws were displaced by 0.2115 (`NumPyRNG.scala`)**

- `ZIGNOR_R`, the Ziggurat's right edge, was `3.442619855899` — Marsaglia &
  Tsang's constant for *their* 256-level table, not NumPy's `3.6541528853610088`.
  It was also inconsistent with the file's own `ziggurat_nor_inv_r`, which is
  NumPy's and is supposed to be its reciprocal
- `ZIGNOR_R` is read in exactly one place, the tail sampler's `R + xx` return, and
  in no comparison. So the effect was confined to draws beyond the ziggurat edge —
  about 1 in 3900, or 0.026% — each returned 0.2115 too far from zero, with the
  other 99.97% of draws bit-exact against NumPy. That is invisible to a
  mean/variance check (it moves a 10,000-sample mean by ~6e-5) and to every
  reproducibility test, since it was deterministic
- **Impact:** anything reading the far tail — extreme quantiles, VaR/expected
  shortfall, tail-dependence, rare-event Monte Carlo — was drawing from a
  slightly wrong distribution. Ordinary uses of `Mat.randn`/`normal` were not
  measurably affected
- `nextLong`, `nextInt`, `nextDouble`, `uniform` and `nextBoundedInt` were never
  affected: they do not touch the Ziggurat
- Now verified against NumPy 2.4.6 draw-for-draw, and guarded three ways: the
  generator refuses to emit tables whose `R` is not `1/inv_r`, and both test
  suites pin specific tail draws to NumPy's exact bit patterns — an absolute
  check, so regenerating the fixtures cannot bless a wrong constant
- Found by the Rust port below. Building a second implementation of the same
  algorithm and demanding the two streams agree bit-for-bit is what exposed it:
  the mismatch was 266 draws in a million, all displaced by an identical
  0.2115, which is what identified `ZIGNOR_R` as the single constant involved.
  Nothing weaker would have caught it — the draws are correctly distributed
  apart from a shifted tail, so every distributional and reproducibility test in
  the suite passed both before and after

**NEW — Rust port of `NumPyRNG` (`rust/src/numpy_rng.rs`)**

- `NumPyRng` reproduces PCG64 XSL RR 128 and NumPy's SeedSequence expansion,
  giving the same stream as the Scala class for the same seed. The 128-bit state
  is a plain `u128` rather than the Scala version's two-`Long` split, which the
  JVM needs and Rust does not
- Exact by construction for `next_u64`, `next_i32`, `next_f64`, `uniform` and
  `next_bounded_u32`: integer arithmetic plus one exactly-representable division
  by 2^53
- `randn` agrees with NumPy bit-for-bit, and with the JVM on all but ~2.5 tail
  draws per million, where `Math.log1p` and C's `log1p` round differently by one
  ulp. Never a control-flow divergence: over 6M draws the wedge's `exp`
  comparison never disagreed, so the streams stay aligned rather than
  desynchronizing. The parity fixture snaps `randn` to a 2^-40 grid for this
  reason and this reason only
- Ziggurat tables are generated from the Scala source by `py/gen_ziggurat_rs.py`
  rather than transcribed, so the 768 constants cannot drift apart
- Cross-checked by `uni.data.NumPyRngParitySuite` and
  `rust/tests/numpy_rng_parity.rs` against one committed fixture
  (`test-data/numpy-rng-parity/`), covering 6 seeds × 7 draw patterns; neither
  side needs the other language installed. One pattern interleaves the methods,
  which is the only way to pin the half-draw `nextBoundedInt` carries in state

**Benchmark inputs are now identical across all three languages (`bench_tprf3.rs`)**

- `rust/src/bin/bench_tprf3.rs` drew its inputs from `StdRng` seeded with 42 and
  a Box–Muller transform, while `jsrc/tprf3Bench.sc` and `py/bench_tprf3.py` both
  seed 0 and draw `randn`/`standard_normal` for X, y, Z in order. The Rust column
  of the `uni.apps.Tprf3Bench` table was therefore measured on different matrices
  than the other two — a confound in a table whose whole purpose is comparison
- It now uses `NumPyRng::new(0)` with a row-major fill, verified element-for-element
  against NumPy: `Mat`, NumPy and `ndarray` are all row-major, so the same value
  lands at the same `(r, c)` in all three
- Rust timings from before this change are not comparable with ones after it. No
  committed table carried Rust numbers, so nothing published needed revising
- Drops the `rand` dependency, which the benchmark was its only user of
- `print_config` in the bench now documents what the `blas` feature is worth:
  ~2.3× on both large OOS rows, enough to turn OOS Recursive from a 2.2× loss
  against Scala into a 1.2× win, since Scala runs on JNIBLAS-backed OpenBLAS and a
  default pure-Rust build is not the like-for-like baseline. It inverts at the
  small size, where BLAS call overhead exceeds the kernel win

**BUG — `tprf3.py` autoproxy returned no OOS forecasts at all (`py/tprf3.py`)**

- `mintrain` (OOS Recursive) and `min_nona` (OOS Rolling) were passed down as
  `_t3prf`'s `min_obs`, which also gated **pass 2** and the **out-of-sample
  fit** — both cross-sectional regressions whose observation count is N, the
  predictor count, not T. With the default `mintrain = T/2 = 45` and N = 12,
  `_ols` demanded 45 valid rows out of 12 and returned NaN for every one, so
  `Sigma` came out all-NaN and every forecast with it
- Symptom was a clean cliff at `mintrain = N`: with N = 12, mintrain 12 produced
  77 forecasts and mintrain 13 produced **zero**. Affected autoproxy OOS
  Recursive and OOS Rolling; Cross Val was spared only because its call site
  never passed `min_obs`
- Both sites now take the identification floor — one more observation than the
  design has parameters — leaving `min_obs` to gate pass 1, where a time-series
  threshold belongs. Restores 44 and 59 forecasts on a T=90/N=12 case, matching
  `tprf3fast.py` to 6e-13
- This is the same defect v0.15.1 fixed in `Tprf3.scala` (`pass2MinObs = L + 1`).
  Scala had corrected *both* its pass 2 and its OOS fit; Python had neither, and
  fixing only pass 2 left the point forecast NaN while `yhat` came out correct —
  so the visible symptom survived the first half of the fix
- Bit-identical on all twelve fixture/procedure combinations in
  `test-data/tprf3-parity/`, which all have N ≥ 10 and so never tripped it

**`estimate3prf_fast` now rejects NaN input instead of returning all-NaN (`py/tprf3fast.py`)**

- The vectorized passes batch every per-column and per-row regression into one
  solve, which cannot carry the per-regression NaN masks `tprf3.py` applies. A
  single NaN therefore poisoned `AᵀA` and the function returned an all-NaN
  series, giving a caller no way to distinguish an unsupported input from a model
  that fitted nothing
- Raises `ValueError` naming the offending array and its NaN count, and pointing
  at `tprf3.estimate3prf` for data with missing values. NaN support is not
  implemented rather than broken — the Rust port declines the same way
- `tprf3.py` is unchanged here and still filters NaN rows per regression

**NumPy 3PRF gets the v0.15.1 OOS optimizations (`py/tprf3fast.py`)**

- v0.15.1 tuned the Scala OOS windows only, which made the published Py/Scala OOS
  ratios a comparison between differently-optimized algorithms rather than between
  implementations. All three techniques are now in `tprf3fast.py`, so the
  comparison is like-for-like again:
  - **Pass-1 cross products by downdate.** Every OOS Recursive and Cross Val
    window keeps all rows but one contiguous block, so `dZᵀdZ` and `dZᵀX` are the
    full-sample ones minus that block's contribution — O(drop·N·L) rather than
    O(keep·N·L). For a Cross Val window dropping one row that is O(N·L) instead
    of O(T·N·L)
  - **Column std by downdate**, via
    `Σ_kept(x−m_keep)² = (Σ_all(x−μ)² − Σ_drop(x−μ)²) − keep·(m_keep−μ)²`, with
    the same majority-kept guard and direct-recompute fallback as Scala and Rust
  - **Scaling folded onto the (L+1)-column operand.** `Zᵀ(X·D⁻¹) == (ZᵀX)·D⁻¹`
    and `(X·D⁻¹)·P == X·(D⁻¹·P)`, so the normalised window is never materialised —
    which also removes the per-window keep×N gather and divide
  - **Pass 2 in natural order.** `dpᵀ·Xᵀ == (X·dp)ᵀ`, so the small keep×(L+1)
    product is formed and transposed instead of taking products against Xᵀ. For
    Cross Val, pass 2 is a cross-sectional regression and therefore independent
    per row, so running it over all T rows yields the held-out row's design as a
    by-product: the forecast costs nothing and the only per-window gather is
    `L+1` wide
- Measured at T=650/N=40/L=2: OOS Cross Val 84.3 → 38.6 ms (2.2×), OOS Recursive
  30.6 → 21.7 ms (1.4×). Scala's lead on Cross Val halves as a result — 13.6×
  against the un-ported NumPy, 6.7× against the ported one — which is the measure
  of how much of the previously published gap was algorithm rather than language.
  At T=200/N=30: OOS Cross Val 13.1 → 8.5 ms, OOS Recursive 5.4 → 4.7 ms
- IS Full is untouched and bit-identical. The OOS forecasts move by ~1e-16 from
  reassociation, well inside the 1e-12/1e-9 tolerance the cross-language fixtures
  already use; verified against both `test-data/tprf3-parity/`'s Scala golden
  values and `py/tprf3.py`, the loop-based reference, on 2424 recorded quantities
- Unchanged where the downdate does not apply, matching where Scala and Rust draw
  the same line: autoproxy rebuilds the proxies each inner iteration, the
  downdates have no NaN-aware form, and OOS Rolling's kept set is the short window
  itself, so a direct pass is already cheaper

**Docs — Rust crate and its 3PRF numbers (`README.md`, `docs/MatDCheatSheet.md`)**

- New "Rust companion crate" section near the top of the README, covering the
  bit-identical NumPy RNG, `randn`'s one-ulp JVM caveat, the 3PRF procedures and
  the parity fixtures that pin them
- The Windows 3PRF table now carries a Rust column, and the cheat sheet gains a
  three-way section with both data sizes. Both state the build configuration,
  because it decides which language wins
- Python and Scala figures come from a single `uni.apps.Tprf3Bench` run so those
  ratios are internally consistent; the Rust column is a `--features blas` build
  measured separately, cross-checked by its pure-Rust baseline landing within 5% of
  the pure-Rust column the same run produced. The previously published median-of-3
  two-way numbers are retained in prose. The Linux and macOS tables are untouched
  and carry no Rust column — the crate has not been benchmarked there

## v0.15.1 — 2026-08-05

*v0.15.0 was built and published to the local Ivy repository but never released
to Maven Central, so this entry covers everything since v0.14.1. Same convention
as the unpublished v0.14.0 folded into v0.14.1.*

**BREAKING — `MatD(rows, cols)` removed (`MatFacades.scala`)**

- Two `Int` arguments used to select a `(rows, cols)` zero-matrix constructor.
  That was the one place where Ints meant *dimensions*; at every other arity —
  and for Doubles at every arity — Ints mean *values*, NumPy-style
  (`MatD(1, 2, 3)` is a 3×1 column of `1.0, 2.0, 3.0`, as `np.array([1, 2, 3])`
  is). Applies to `MatD`, `MatF` and `MatB` alike, via the shared `MatFacade`
- **Migration:** `MatD.zeros(r, c)` for a zero matrix, `MatD.empty` for 0×0, or
  `MatD(3.0, 4.0)` for a 2×1 column of values
- The overload is retained as a `@compileTimeOnly` **tombstone**. Deleting it
  outright would have left `MatD(3, 4)` compiling and silently resolving to the
  Double varargs overload — a 2×1 column instead of a 3×4 matrix. The tombstone
  turns that into a compile error naming the replacements. It should be deleted
  in a later release, once downstream code has migrated
- Note this cannot be covered by a `compileErrors` unit test: munit's
  `compileErrors` runs the typer only, while `@compileTimeOnly` is enforced in a
  later phase. See `src/test/resources/tombstone-check.md`
- NumPy draws the same line by naming rather than by argument type:
  `np.array([1, 2])` vs `np.zeros((3, 4))`

**Fix — `Mat.scale(center = false, doScale = true)` (`Mat.scala`)**

- The divisor is now the root-mean-square of the (possibly centered) values with
  Bessel's correction, `sqrt(Σc²/(n−1))`, computed from the data actually being
  scaled. Previously it always divided by the CENTERED std, even when
  `center = false`
- `center = true` (the default) is **unchanged** — centered data has zero mean,
  so its RMS is exactly the sample std. Only the uncentered case moves
- Why: dividing uncentered data by a statistic that measures only variation
  leaves the result unbounded when the mean dwarfs the sd. A column of
  1000 ± 0.001 scaled to ≈1e6 instead of ≈1, inflating the condition number of
  any design built from it. The RMS bounds every scaled column to unit RMS, and
  it is one rule that specializes to the sample std when centered rather than
  two cases
- This also matches R's `scale(x, center = FALSE, scale = TRUE)`, which the
  method's docstring already claimed to follow. Note sklearn's
  `StandardScaler(with_mean = False)` uses the centered std instead, so uni
  differs from sklearn here

**Array → Mat / CVec / RVec conversions (`Mat.scala`, `VecExts.scala`, `MatFacades.scala`)**

- New extension methods on arrays: `arr.toCVec` (n×1), `arr.toRVec` (1×n),
  `arr.toMat` (column), and `.toMat` on `Array[Array[T]]` and `Seq[Array[T]]`
  (row-major). A 1-D array becomes a column, matching `MatD(…)` / `CVec(…)`
- New `Mat.fromRows` (`Array[Array[T]]` and `Seq[Array[T]]` overloads) — the
  first 2-D factory in the library; also surfaced as `MatD.fromRows`. Previously
  the only route was `MatD(rows, cols, arr2d.flatten)`
- New `apply` overloads taking arrays on `CVec`, `RVec`, `Mat`, and the
  `MatD`/`MatB`/`MatF` facades
- **Fixes a wrong-shape result:** `CVec(arr)` / `RVec(arr)` previously bound the
  varargs overload with `T := Array[Double]`, and `Mat(arr)` / `Mat(arr2d)` bound
  the scalar lift — all four produced a **1×1 matrix holding the array object**
  rather than a vector or matrix of its elements. The scalar lift `Mat(42.0)` →
  1×1 is unchanged
- `Mat.apply(Array)` carries `@targetName` because `Array[T]` and a bare `T`
  erase to the same signature
- New `Mat.wrap(arr, rows, cols)`: explicit zero-copy wrapping. All the new
  conversions **copy**, since `Mat` is mutable (`m(r,c) = v` writes into the
  backing array) and aliasing would make the matrix and the caller's array share
  state in both directions. `Mat.create` and `MatD(rows, cols, data)` still
  alias — unchanged, now documented

**Fixes (`Tprf3.scala`)**

- `estimate3prf(…, pls = true)` returned **all-NaN whenever N < 10**. Pass 2 is a
  cross-sectional regression whose observation count is N, but it was gated on
  `minObs` (a time-series guard, default 10). Now gated on `L + 1`, the
  identification floor. Every real predictor count downstream is below 10, so
  this affected essentially every call
- Fixing that made the pls OOS forecast branch reachable for the first time and
  exposed a latent transpose bug: `xt - colMeans.T` subtracted an N×1 from a 1×N,
  broadcasting to N×N. Now `xt - colMeans`
- `pls1Fit` no longer aliases the caller's `y` array

**Additions (`Tprf3.scala`)**

- `plsClosedForm(y, X)`: vectorized closed form of the PLS-variant 3PRF
  (autoproxy L=1, no intercept in passes 1–2). Both passes collapse to
  projections, replacing the N+T separate OLS solves — 4–6× faster to fit than
  the iterative path. Requires NaN-free input
- `pls1Fit(x: Array[Array[Double]], y: Array[Double])`: array-friendly wrapper
- `Pls3prfModel`: retains phi/sigma/beta *and* the column mean/scale, so
  `predict(rawRow)` takes an un-normalized row (`Tprf3Result.estimateYhat`
  silently requires a pre-scaled one)

**Performance — 3PRF out-of-sample procedures (`Tprf3.scala`)**

- The OOS windows no longer materialize the scaled predictor block. Column
  scaling commutes with both products the filter takes against X —
  `Z'·(X·D⁻¹) == (Z'·X)·D⁻¹` and `(X·D⁻¹)·P == X·(D⁻¹·P)` — so the scaling now
  lands on a matrix with `L+1` columns and `X·D⁻¹` is never built. OOS Recursive
  passes a plain slice view and allocates nothing window-sized for X
- Pass 2 runs in natural order: `B2' = X·designPhi·PtPinv'` yields Sigma
  directly, where the previous `designPhi.T *@ X.T` read **both** operands
  through transpose views
- Each window's pass-1 cross products are now downdated from full-sample ones
  (`FullSample.pass1Downdated`) rather than recomputed: the Cross Val window
  drops an interior block, the Recursive window the suffix `[end, T)`, so both
  cost O(drop·N·L) instead of O(keep·N·L). Only valid when the proxy matrix is
  the same for every window, so autoproxy and the pls branch keep the direct path
- `yhat` is computed lazily (`TailResult`): only IS Full reads the fitted series,
  while every OOS window reads just the point forecast
- Net on Windows (T=650, N=40, L=2): OOS Recursive 3.6 → 1.8 ms, OOS Cross Val
  7.4 → 7.1 ms. **The NumPy twin was not tuned this round**, so the published
  ratios are no longer like-for-like algorithm work — see the README note

**Additions — Rust port (`rust/`)**

- `t3prf` crate: a Rust implementation of the three IS/OOS procedures, matching
  the Scala results to ~3e-15 relative. Carries the same optimizations plus a
  Cross Val specialisation that avoids gathering X at all — `X_kept·dps` is
  `X_full·dps` with the dropped rows skipped, so the only per-window copy is
  `L+1` wide rather than `N` wide
- Optional `blas` feature routes ndarray's matmul through a system OpenBLAS,
  worth ~3× on the OOS paths, whose gemms are skinny. Set `OPENBLAS_NUM_THREADS=1`:
  the window loop is already parallel, and nesting BLAS threads inside it costs ~10%
- Fixes carried over from reviewing the port against the Scala reference: the
  out-of-sample R² is measured against the prevailing training-window mean
  (`rollfore`) rather than the full-sample mean, the recursive training window is
  `[0, t-1)`, the `min_obs` guard is honoured, and the Cross Val drop block is
  clipped with signed arithmetic

**Additions — cross-language parity test**

- `test-data/tprf3-parity/`: committed input fixtures at 17 significant digits
  plus `scala-reference.txt`, 2424 reference values across 3 sizes × 4 procedures
- `uni.stats.Tprf3ParitySuite` (MUnit) and `rust/tests/scala_parity.rs` both check
  against those files, so neither test needs the other language installed — a
  change that moves one implementation fails on that side alone, localizing drift
- Regenerate with `sbt "runMain uni.apps.Tprf3ParityGen"`, and only when the
  values are meant to move

**Benchmark harness (`jsrc/tprf3Bench.sc`, `uni.apps.Tprf3Bench`)**

- Reports Rust alongside Python and Scala when a release binary is already built,
  gated on its presence rather than invoking cargo — so the Scala/Python
  comparison still runs on a machine with no Rust toolchain. Both Rust builds land
  at the same path and differ ~3× on the OOS rows, so the binary reports its own
  configuration and the table states which one produced the numbers. Warns when
  the binary is older than `rust/src`
- Ratio cells within 5% now render `≈ tied` instead of `1.0× slower`, which
  presented run-to-run noise as a directional result
- IS Full is sampled at least 200 times with the heap settled first. At `loops`
  sized for the OOS procedures it saw only a few ms of measurement, and GC pauses
  from the neighbouring OOS sections moved its median by 2× (Large read 0.56 ms
  against 0.25 ms measured standalone)
- Python discovery prefers WinPython installs found under `/opt/winPython`,
  globbed rather than hard-coded, and probes candidates with `import numpy`
  instead of `--version` — the previously preferred install has a broken numpy
  that passed the old check and then failed inside the bench script
- The documented `-python <exe>` override was parsed but never read; it now works,
  with MSYS2-style paths resolved. Added `-norust`
- The NumPy BLAS name in the section header is read from `show_config('dicts')`;
  the old text scan returned `?` on builds that emit JSON-ish output

## v0.14.1 — 2026-07-10

*(v0.14.0 was tagged but never published; this entry covers everything since
v0.13.4, including all changes originally staged for v0.14.0.)*

**Performance — 3PRF OOS hot path (`Tprf3.scala`, `Mat.scala`)**

- NaN presence is now detected once per `estimate3prf` call (`anyNan` over X, y, Z)
  and gates NaN-free fast paths throughout: `stdCols`/`colMean` skip the
  per-element NaN test and per-column observation counts, and `nanOls` skips the
  row scan and `selectRows` copies entirely (with no NaNs every row is kept, so
  the filtered fit equals the direct one)
- `nanStdCols` / `nanMeanCols` rewritten as row-major sweeps — the same
  per-column accumulation order (bit-identical results) but sequential memory
  access over the row-major backing array instead of striding cols×8 bytes/step
- OOS Cross Val: the dropped window is a contiguous block [lo, hi), so the
  `setdiff` Set build + `selectRows` copies are replaced by
  `standardizeDropRows`, which fuses the row drop, the column std, and the
  normalize into one pass-set with a single output allocation. Clean (NaN-free)
  inputs go further via `standardizeDropRowsInc`: full-data column stats are
  computed once per call and each window's std becomes an O(drop·N) downdate
  instead of an O(keep·N) recompute, with a cancellation guard that recomputes
  directly when the kept set is not the majority. Downdated stds drift ≤ ~1e-13
  from the two-pass form (not bit-identical)
- OOS Recursive / Rolling: training windows are read through zero-copy slice
  views — `standardize` reads the view stride-safely and emits one fresh
  contiguous matrix, so no intermediate window copy is materialized
- `withIntercept` writes `[1 | X]` into a single buffer instead of allocating a
  `ones` column and routing through `hstack`
- `Mat`: `fastBinOp`'s broadcast branches (1×N row, N×1 column, and the
  strided-view forms) now run in parallel above a dedicated 64K-element
  threshold (`bcastRows`) — previously single-threaded at any size. The
  threshold is higher than the usual 4096 because `IntStream.parallel()` setup
  (~100 µs measured) makes medium-sized broadcasts slower in parallel
- Measured (`jsrc/tprf3Bench.sc` side-by-side, Windows 11, netlib Java11BLAS,
  vs Python 3.14.6 / NumPy on OpenBLAS; medians of 25 timed calls per OOS
  procedure after explicit warm-up):

  | Scenario | Python | Scala | Ratio |
  | :--- | ---: | ---: | :--- |
  | IS Full (Small: T=200, N=30, L=2) | 0.12 ms | 0.06 ms | 2.1× |
  | OOS Recursive (Small) | 5.47 ms | 1.00 ms | 5.5× |
  | OOS Cross Val (Small) | 13.89 ms | 1.40 ms | 9.9× |
  | IS Full (Large: T=650, N=40, L=2) | 0.36 ms | 0.34 ms | ~tie |
  | OOS Recursive (Large) | 32.59 ms | 3.60 ms | 9.1× |
  | OOS Cross Val (Large) | 87.65 ms | 7.37 ms | 11.9× |

**Benchmarks — measurement fairness and stability**

- `Tprf3Bench` / `jsrc/tprf3Bench.sc` / `py/bench_tprf3.py`: each OOS procedure
  is explicitly warmed before timing (previously they were timed cold — JIT and
  ForkJoin/OpenBLAS spin-up landed in the samples) and readings are the median
  of 25 individually-timed runs instead of a mean over 5 (which showed 5–55 ms
  spreads for identical code). The Scala driver now captures the Python output
  and prints a side-by-side markdown comparison table (Python vs Scala with
  ratio) in the MatDCheatSheet style
- `py/tprf3fast.py` (the benchmark-fairness twin): gains the same once-per-call
  NaN detection gating `np.std` vs the slower `nanstd` machinery; the three
  `lstsq` passes are replaced by `_ols` normal-equations solves — the 3PRF
  designs have only L+1 columns, so AᵀA is tiny and `solve` beats the SVD
  driver several-fold, with a rank-revealing `lstsq` fallback if AᵀA is
  singular; OOS Cross Val drops the contiguous block directly instead of
  `setdiff` indexing
- `jsrc/tprf3Bench.sc`: python interpreter discovery no longer relies on a
  hard-coded WinPython install path — it scans every `python3`/`python` on
  PATH and picks the first that is runnable *and* has numpy (skipping broken
  shims such as the Microsoft Store app-execution alias, and looking past a
  numpy-less `/usr/bin/python3` to a numpy-capable homebrew python). New
  `-python <exe>` flag overrides discovery (validated rather than silently
  falling back)

**Bug fixes — view correctness (`Mat.scala`)**

Several operations read the raw backing array (`m.tdata`) directly, which for a
zero-copy view (e.g. a full-width row slice, `offset > 0`) is the *parent's* storage —
producing silently wrong results (wrong elements and wrong length). All now use
stride/offset-aware access:

- `gt`, `lt`, `gte`, `lte`, `:==`, `:!=` — rewritten via `m.map`
- `isnan`, `isinf`, `isfinite` — rewritten via `m.map`
- `std`, `variance` (general, non-Double path) — sum of squares now accumulates
  stride-aware instead of folding over the raw array
- `filterRows` — replaced `System.arraycopy` (which assumed column stride 1 and
  produced garbage for transposed matrices) with a stride-aware element copy

**Bug fixes — semantics**

- `containsNaN`: now checks `Numeric.toDouble(v).isNaN` (correct for Double, Float,
  and the `BigNaN` sentinel) instead of per-element `toString == "NaN"`
- `linspace(start, stop, num = 1)`: no longer truncates a fractional `start`
  (previously returned `start.toInt`)
- `power(n: Int)`: negative exponents are rejected before computing instead of
  throwing mid-loop after wasted work
- Tuple factory `Mat[T]((…), (…))`: `Float`, `Double`, `BigDecimal`, and `Long`
  elements are now *converted* to the target element type instead of cast —
  previously `MatD((1.0, 2.5f))` threw `ClassCastException`, `MatB((1, 2.5))`
  threw `ArrayStoreException`, and `Long` elements were rejected outright
- `saveCSV` on `MatB`: `BigNaN` now honors the `nanAs` parameter like Double/Float
  NaN (default `"NaN"`) — previously it was hard-coded to `"N/A"` regardless of
  `nanAs`, so a `loadMatB`/`saveCSV` round-trip silently changed the NaN spelling
- `proc(...).timeout(ms).run()` / `.stream(...)` now return promptly when the timeout
  fires instead of blocking until the child process exits. On a forced-timeout kill,
  orphaned grandchildren (e.g. bash's `sleep`) can keep the stdout/stderr pipes open,
  so the reader threads never saw EOF and the drain-thread joins blocked for the
  child's full lifetime (a 200 ms timeout on `sleep 10` took ~10 s, longer with an
  orphaned child). `awaitProcess` now reports whether it timed out, and `run`/`stream`
  bound the drain joins in that case (the drain/reader threads are daemons, so
  abandoning them is safe). Status is unchanged (`-1` on timeout)

**API additions**

- `<`, `<=`, `>`, `>=` operator aliases for `lt` / `lte` / `gt` / `gte` (scalar `T`
  and `Int` overloads, returning `Mat[Boolean]`), so NumPy-style `m > 0.0` now works.
  Equality remains `:==` / `:!=` — `==` and `!=` are defined on `Any` and cannot
  return `Mat[Boolean]`
- New `MatElem[T]` type class (`MatElem.scala`, exported from `uni`): bundles the
  Double→T conversion, precision-aware sqrt, and NaN sentinel, with givens for
  Double, Float, Big, and plain BigDecimal. Methods that previously dispatched on
  `ClassTag.runtimeClass` (sqrt, norm, std, round, power, percentile/median,
  nanToNum, corrcoef, rolling aggregations, arange/linspace, scale, describe,
  pct_change, qrDecomposition, eigenvalues, ~^, the tuple factory) now take
  `using MatElem[T]` — resolved silently from companion scope for normal callers;
  unsupported element types become compile-time errors instead of runtime throws.
  16 of 26 runtime-dispatch blocks deleted; the rest are per-type kernel selection
  and Double-only LAPACK gates

**API changes (breaking) — continued**

- `m.svd` returns **economy** shapes: U is nRows×p and Vt is p×nCols
  (p = min(nRows, nCols)) instead of full nRows×nRows / nCols×nCols — full U was
  O(n²) memory for tall-skinny regression matrices and no consumer (lstsq, pinv,
  matrixRank) ever read past column p. Reconstruction is `U *@ diag(s) *@ Vt`
  with a p×p sigma
- `round` / `power(Double)` / `nanToNum` on `MatB`: a non-finite Double result now
  becomes `BigNaN` instead of throwing `NumberFormatException` from
  `BigDecimal(Double.NaN)`
- `scale()` now standardizes with the **sample** standard deviation (ddof = 1,
  matching R's `scale()` and the inference convention, and consistent with
  `Tprf3.nanStdCols`) instead of the population std — values shrink by
  √(n/(n−1)). `m.std` itself is unchanged (population, NumPy `ddof=0` parity);
  the doc comment spells out both conventions
- Exception types are now consistent across the API — `IllegalArgumentException`
  (via `require`) for bad arguments/bounds, `UnsupportedOperationException` for
  unsupported element types, `ArithmeticException` for numerical failure
  (singular matrix, LAPACK `info ≠ 0`). Changed: `slice(rows, cols)` out of
  bounds threw `IndexOutOfBoundsException`, `power(n < 0)` threw
  `UnsupportedOperationException` — both now `IllegalArgumentException`

**Performance — continued**

- `Tprf3`: all K&P `J(k) = I − (1/k)·1·1'` products rewritten as row/column
  centering (`J(T) *@ M` ≡ subtract column means) instead of building a dense
  T×T matrix and multiplying — O(T·N) instead of O(T²·N). The always-on IS Full
  alpha block's `Xn' *@ J(T)` was the dominant cost of the whole benchmark and
  hypersensitive to BLAS/threading state (9.7 vs 75 ms across machine states for
  identical code). After: IS Full Large (T=650, N=40) ≈ **2.5 ms** without avar
  (was 56 ms same-machine), ≈ 21 ms with avar (was 138 ms). Affected sites:
  `estimate3prf` alpha and avar blocks, `tprfClosedForm`, `degreesOfFreedom`;
  `jMat` deleted, `centerRows` helper added. Validated bit-equivalent against
  the MATLAB K&P references (`jsrc/verifyTprfResults.sc`: max diff ≤ 4.4e-16).
  For benchmark fairness the same rewrite was applied to `py/tprf3fast.py`
  (verified against its explicit-J formulas at ≤ 4e-13): Python IS Full Large
  drops 10.0 → 1.8 ms, making IS Full roughly a tie — the honest result, since
  both sides are dominated by the same two batch solves. Also fixed a latent
  NumPy 2.x incompatibility in its avar loop (`float(1-element array)` was
  removed in NumPy 2.0; now `.item()`)
- `eigenvalues()` on `Mat[Double]` routes through LAPACK `dgeev` (eigenvalues-only,
  no eigenvector computation) instead of 500 unshifted QR iterations — O(n³) once
  with exact results (and correct real parts for non-symmetric input); the QR
  fallback remains for Float/Big
- Plain `*` (element-wise multiply) now takes the same contiguous-Double fast path
  as `*:*` — previously it always used the boxed generic loop
- New N×1 column-broadcast fast path in `+`, `-`, `*`, `*:*`, `/`
  (e.g. `m - m.mean(axis = 1)`); previously only 1×N row broadcasts had one and
  column broadcasts fell to the boxed path
- `Double` autoboxing eliminated from the remaining `Mat[Double]` hot paths,
  driven by JFR profiling of `jsrc/tprf3Bench.sc` (boxed `java.lang.Double`
  allocation samples 188 → 0). `MatData._tdata: Array[T]` erases to `Object`, so
  the generic element accessor and `Numeric`/`Fractional` dispatch boxed every
  read; each site now branches to a primitive `Array[Double]` path under a
  `ClassTag == Double` guard (correct for views via the stride equation). Sites:
  row/column extraction (`m(i, ::)`, `m(::, j)`), `m(Range, Range)` slicing,
  `inverse`/`luDecompose` (shared primitive-LU helper), `zeros`/`ones`/`eye`,
  `randn`/`normal`/`uniform` (primitive fills preserving RNG call order), `power`
  (both overloads), `hstack`, `map`/`zipMap`, and the `Tprf3` OOS hot path
  (`atD` reads, `java.lang.Double.isNaN`, the rsq post-processing loops). Result
  (Large, T=650 N=40 L=2, JVM 21): OOS Recursive 42 → 21 ms, OOS Cross Val
  77 → 56 ms, IS Full 1.3 → 0.55 ms.
- Client-facing element boxing eliminated for `MatD`/`MatF`: the ordinary `m(i, j)`
  read and `m(i, j) = v` write (and the rest of the indexing family) on a
  `Mat[Double]`/`Mat[Float]` no longer allocate a `java.lang.Double`/`Float` for
  external (`import uni.data.*`) callers — no special accessor needed. The generic
  `apply(i,j): T` / `update(i,j,v: T)` box because `Array[T]` erases to `Object[]`;
  a new package-level facade (`MatDOps.scala`) re-supplies the **complete** family —
  scalar access/assignment, slices, boolean masks, fancy `Array[Int]` indexing,
  slice assignment, and `at` — specialized to `Mat[Double]` and reading the primitive
  backing array directly. (Scala 3 extension overloads don't merge across receiver
  specificity, so adding any `Mat[Double]` `apply` shadows the entire generic family
  for that receiver — hence the whole family is re-supplied.) These win by specificity
  for a `Mat[Double]` receiver; in-package `import Mat.*` sites keep the generic path.
  The `Mat[Float]` twin (`MatFOps.scala`) is generated from `MatDOps.scala` by a
  `build.sbt` sourceGenerator (generated into `sourceManaged`, never checked in) so the
  two cannot drift. Verified at the bytecode level (`daload`/`faload`, no `*.valueOf`)
  and by JFR allocation profiling (zero boxed-scalar samples on the element path).

**Internals**

- TASTY transparency fix: `object CVec` / `object RVec` moved from inside
  `object Mat` to package level (VecExts.scala), constructing through new
  `private[data]` cast bridges `Mat.mkCVec` / `Mat.mkRVec`. Inside `object Mat`
  the opaque types are transparent (`CVec[T] = Mat[T]`), so every method
  declared there recorded `Mat[T]` return types in TASTY, breaking typed
  dispatch for external callers; at package level the signatures stay
  `CVec[T]`/`RVec[T]`. The companions' extension methods (accessors,
  converters, scalar ops, `update`) moved into `VecOps` alongside the existing
  `.T`/`*@` overloads and now simply delegate via `asMat` — the
  inside-object-Mat recursion hazards (and the raw `MatData` field access they
  forced) no longer apply. Because the package-level companions are no longer
  in the opaque types' implicit scope, `uni/package.scala` now forwards
  `export uni.data.VecOps.*` so `import uni.*`-only clients keep (and gain
  TASTY-correct versions of) the full vector API — verified from an external
  scala-cli script (`jsrc/tastyCheck.sc`): `val rv: RVecD = cv.T`,
  typed `*@` dot/outer products, `show` labels, and `CVec(…)` factories all
  dispatch correctly against the published jar
- The MatD/MatB/MatF facades (~80 near-identical forwarders × 3) now share one
  `private[data] trait MatFacade[T: ClassTag: Fractional: MatElem]`; the only
  per-type members left are an abstract `fromBig` (CSV element conversion),
  MatD's Double-only extras (leastSquares, signal processing, `zeros(n)`/
  `ones(n)`/`randn(n)`/`rnorm(n)`, specialized RNG fills), and one-line
  `object MatB` / `object MatF` declarations. Behavioral nuance: facade
  Double-argument conversions now go through `MatElem.fromDouble`, so e.g.
  `MatB.full(r, c, Double.NaN)` produces `BigNaN` cells instead of throwing
  from `BigDecimal(NaN)` (consistent with this release's MatB non-finite policy)
- File split (first step): the facades, `MatFacade`, `LeastSquaresResult`, and
  the `MatD`/`MatB`/`MatF`/vector type aliases moved from Mat.scala to a new
  `MatFacades.scala` (Mat.scala: 5,352 → 4,860 lines). Pure relocation of
  package-level definitions — no dispatch or API change
- File split (completed): three method groups with no companion-dispatch
  constraint moved out of `object Mat` into sibling files, re-exported inside
  `object Mat` (`export MatXxxOps.*`) so they remain companion members —
  implicit-scope and `import Mat.*` dispatch are byte-for-byte unchanged for
  clients (verified by the full suite plus `jsrc/docCheck.sc` /
  `jsrc/tastyCheck.sc` against the published jar):
  - `MatMathOps.scala` — trig/hyperbolic/floor/ceil/log10/log2/trunc and the
    ML activations (sigmoid, relu, leakyRelu, softmax, logSoftmax, elu, gelu,
    dropout) plus their shared `mapToDouble` kernel
  - `MatPandasOps.scala` — sort/argsort/nlargest/nsmallest/between/unique/
    nunique/valueCounts, diff/shift/pct_change, percentile/median (+ the
    private `percentileOf` kernel), describe, idxmin/idxmax, histogram, and
    the `rolling` entry point (`class RollingWindow` itself stays in Mat.scala)
  - `MatSignalOps.scala` — polyfit/polyval/convolve/correlate companion methods
  Mat.scala: 4,860 → 4,025 lines. Internals widened to `private[data]` for the
  new files: `fastD`, `fillD`, `nanFill`. One subtlety the new files must
  respect: the opaque type must be imported as a direct member
  (`import Mat.Mat`) — the package-level forwarder (`export Mat.{Mat, …}`)
  cannot be elaborated while `object Mat` is mid-completion with the
  `export MatXxxOps.*` clauses (cyclic; manifests as "Not found: type Mat")

- The ~25 hand-copied Double fast-path guards
  (`runtimeClass == classOf[Double] && isContiguous && offset == 0 && …`) are now
  one pair of inline helpers, `fastD` / `fastD2`; `+`, `-`, `*:*`, `/` share a
  single `fastBinOp` kernel (also removing the mid-expression `return`s)
- ~350 lines of identical trig/activation loops (sin, cos, tan, arcsin, arccos,
  arctan, sinh, cosh, tanh, floor, ceil, log10, log2, trunc, leakyRelu, elu, gelu)
  collapsed to one-liners over a shared stride-aware `mapToDouble` kernel — which
  also gains the parallel contiguous-Double fast path these methods never had
- Thread safety: `globalRNG` is `@volatile` and every fill captures the generator
  once, so a concurrent `setSeed` cannot interleave two generators within one
  fill (NumPyRNG itself remains single-thread-reproducible, same contract as
  NumPy's global generator); print options are an immutable `PrintConfig` swapped
  atomically and `formatMatrix` reads one consistent snapshot
- Source hygiene in `Mat.scala`: removed first-person scaffolding comments
  ("Phase 0", "Choke Point", "your legacy code", "exactly like your tests"),
  a stray debug `println`, and commented-out code; `@annotation.unused` removed
  from public API methods (`svd`, `lstsq`, `matrixRank`, `eig`, `pinv`,
  `cholesky`, `arange`, `apply(unit)`, `RVec.zeros`) — where the `Fractional`
  evidence is intentionally unused (Double-only LAPACK kernels) the annotation
  now sits on the parameter, with a comment explaining it restricts the API to
  numeric element types

**API changes (breaking)**

- `MatD(1.0, 2.0, …)` / `MatB(…)` / `MatF(…)` flat varargs now build a **column**
  vector (n×1), matching `Mat(…)`, `CVec(…)`, every vector-producing factory
  (`randn`, `arange`, `linspace`, `fromSeq`), and Breeze's `DenseVector(…)` —
  previously these were the lone row-vector outliers. The `ReferenceGuide`
  examples written against column semantics now behave as documented. For an
  explicit row vector use `MatD.row(…)` / `Mat.row[T](…)` / `RVec(…)`
- `m ~^ 0.0` is now element-wise all-ones, matching `m ~^ 0` and NumPy `**`
  semantics — previously it returned the identity matrix (matrix-power semantics
  leaking into an otherwise element-wise operator)
- Removed `cloneMat` — it copied the entire parent array and dropped the view
  offset (wrong data for views); use `copy` / `matCopy`
- Removed `inspect` — never produced meaningful output
- `def data` is now `private[data]` — it exposed the raw backing array (the
  *parent's* storage for views), bypassing all layout invariants; use `toArray`
  or `flatten` for a flat copy
- Relaxed constraints: slicing (`m(::)`, `m(r, ::)`, `m(rows, cols)`, …), `head`,
  `tail`, `filterRows`, `applyAlongAxis`, `vsplit`, `hsplit`, and `split` no longer
  require `Fractional[T]` — they now work for any element type (e.g. `Mat[Int]`)

**Performance**

- New shared helpers `fillD` / `sumD` apply a uniform 4096-element threshold before
  going parallel across all Double fast paths (`+`, `-`, `*:*`, `/`, scalar ops,
  `unary_-`, `sigmoid`, `relu`, `sum`, `mean`, `std`, `variance`). Previously only
  scalar `/` had the threshold; every other op paid fork/join overhead on small
  matrices. Confirmed on Tprf3Bench Large: IS Full 9.7 ms vs 13 ms documented for
  v0.13.4 (the avar block does ~1,300 small-matrix ops per call)
- `sumD` rewritten from `DoubleStream.sum` to a chunked parallel sum over an
  8-accumulator unrolled kernel (independent accumulators break the FP-add latency
  chain; fixed chunk boundaries keep results deterministic). NumPy 2.4.1's
  SIMD reductions had overtaken the old implementation; measured on the
  bench.sc 1000×1000 suite (JVM 21):
  `sum` 0.45 → **0.09 ms**, `mean` 0.45 → **0.09 ms**, `std` 1.46 → **1.12 ms**
  (NumPy 2.4.1: 0.25 / 0.27 / 3.28 ms — both reductions flip from ~1.8× losses
  back to ~2.8× wins). Note: plain summation replaces DoubleStream's compensated
  summation, so last-ulp rounding of `sum`/`mean`/`std`/`variance`/`cov` may
  differ from v0.13.x; all 2,219 tests pass unchanged

**Tests**

- `ViewOpsSuite`: regression suite running every fixed operation against an offset
  row-slice view and a transposed matrix, compared against `matCopy` results
- `MatSemanticsSuite`: pins `~^` element-wise semantics, the `power` guard,
  `linspace(num=1)`, `containsNaN` (Double and BigNaN), `Fractional`-free
  slicing/splitting on `Mat[Int]`, the comparison-operator aliases (including
  offset-view correctness and precedence vs arithmetic), and tuple-factory
  element conversion for all numeric element types

**Build**

- `Compile / run / fork := true` (with `connectInput` and logger-routed output so
  `sbt --client` sessions see it): benchmarks now run in a fresh JVM instead of the
  long-lived sbt server, whose accumulated heap/JIT state inflated allocation-heavy
  timings (IS Full Large measured 29–67 ms in-server vs 9.7 ms forked), and the
  Vector API `javaOptions` (`--add-modules=jdk.incubator.vector`) now actually
  apply to `run` — previously they were silently ignored
- Tprf3Bench (Large, forked, this machine): IS Full 9.7 ms, OOS Rec 24 ms,
  OOS CV 61 ms — all at or better than the v0.13.4 documented numbers

**Docs**

- Windows benchmark tables in `README.md` and `docs/MatDCheatSheet.md` re-measured
  once more at release time, all on the same (faster) machine: NumPy 2.4.6 /
  Python 3.14.6 vs uni 0.14.1 / JVM 21, min times. MatD wins 8/9 scored ops vs
  NumPy — the matmul row is now an honest fallback-vs-native comparison (netlib
  JNIBLAS could not load `libopenblas.dll` on that machine, so MatD/Breeze matmul
  ran pure-JVM while NumPy used native OpenBLAS; level where JNIBLAS loads) — and
  6 wins + 1 tie over Breeze's 7 scored ops (geomean ~4.3×). 3PRF tables updated
  with the v0.14.1-tuned numbers (IS Full ≈ tied, OOS Recursive 9.1×,
  OOS Cross Val 11.9×). The Linux/macOS tables are current as measured on their
  own machines. Version references across the docs now cite v0.14.1 (v0.14.0 was
  never published)
- Refreshed all benchmark tables in `README.md` and `docs/MatDCheatSheet.md` with
  same-day, same-machine measurements of uni 0.14.x vs NumPy 2.4.1 vs Breeze 2.1.0
  (JVM 21): MatD wins 9/9 scored ops vs NumPy and wins or ties 9/9 vs Breeze
  (geomean ~6.2×); 3PRF tables updated to the post-centering numbers with both
  implementations optimized (loops=5 OOS measurements): IS Full 1.2/1.3 ms tied,
  OOS Recursive 270/42 ms (6.4×), OOS Cross Val 720/77 ms (9.4×)
- `docs/ReferenceGuide.md`: added the eleven sections its table of contents promised
  but never delivered (Indexing and Slicing, Arithmetic, Broadcasting, Linear Algebra,
  Statistics, Element-wise Math, Machine Learning, RNG, Data Manipulation,
  Comparison/Boolean, Display), plus the new column-vararg factories and the
  `MatD(3, 4)`-is-zeros vs `MatD(3.0, 4.0)`-is-a-vector gotcha
- Fixed examples that never compiled: `docs/QuickStartGuide.md` used nonexistent
  `m > 5.0` / `m < 0.0` comparison operators (Mat has only `gt`/`lt`/`gte`/`lte`)
  and `Mat.randn[Double](r, c)` (randn is not generic); `docs/MatDCheatSheet.md`
  listed nonexistent `MatD.from(arr, r, c)` (now `MatD(r, c, arr)`)
- Corrected `.norm` (vector-only; matrices need `norm("fro")`), `cov` (NumPy
  rows-as-variables convention), and `sort()` (flattens by default) descriptions
- New `jsrc/docCheck.sc` compile-checks and runs every ReferenceGuide/QuickStart
  snippet against the published library — this is what caught the errors above

---

## v0.13.4 — 2026-05-23

**Performance — Double unboxing elimination (`Mat.scala`)**

Profiling of the 3PRF benchmark revealed that generic `Numeric[T]` / `Fractional[T]`
dispatch was boxing every `Double` operand.  All high-frequency operations on contiguous
`MatD` arrays now have dedicated fast paths that cast `tdata` to `Array[Double]` and use
raw arithmetic, bypassing the typeclass layer entirely.

Operations that gained fast paths:
- `+`, `-`, `*:*` (Hadamard element-wise): same-shape parallel and 1×N broadcast
- `unary_-`: parallel negation
- Scalar `+(T)`, `-(T)`, `*(T)`, `/(T)`: parallel scalar broadcast (all four operators)
- `sum(axis=0/1)`: direct while-loop accumulation, no `Numeric.plus` dispatch
- `std(axis=0/1)`: two-pass direct loop (mean then variance), no `Array.tabulate` boxing
- `/(other: Mat[T])`: same-shape parallel and 1×N broadcast division

**Performance — 3PRF (`Tprf3.scala`)**

`nanStdCols`, `nanMeanCols`, `nanMean`, `nanOls`: replaced `.collect`/`.filter`/`.map`/`.sum`
chains on ranges (which produce `Seq[Double]` and box every element) with explicit `while`
loops accumulating into `Double` locals and `Array.newBuilder[Int]`.

**Bug fix — nested-parallelism regression in scalar `/(T)`**

The initial `/(T)` fast path used `parallelSetAll` unconditionally, which caused severe
fork/join pool thrashing when called inside the OOS parallel loop on small vectors
(e.g. the 1×N result of `sum(axis=0)` divided by row count in `centerColumns`).
Fixed by using a sequential while loop for arrays smaller than 4096 elements, matching
the approach used for threshold-based dispatch in the other scalar ops.

**Measured improvements (Windows 11, JVM 17, T=650 N=40 L=2):**

| Operation | v0.13.3 | v0.13.4 | Speedup |
| :--- | ---: | ---: | ---: |
| 3PRF OOS Recursive | 159 ms | 27 ms | **5.9×** |
| 3PRF OOS Cross Val | 375 ms | 66 ms | **5.7×** |
| 3PRF IS Full | 19 ms | 13 ms | **1.5×** |

MatD now wins **8/8** core operations vs NumPy on Windows (was 7/8; `sum` flipped from
a marginal loss to 1.8× win). Geometric mean vs Breeze improved from 3.1× to 3.3×.

**New: `uni.io.Cksum`**

- `Cksum.cksum(bytes: Array[Byte]): (Long, Long)` — POSIX `cksum`-compatible CRC32/length
- `Cksum.cksum(bytes: Iterator[Byte]): (Long, Long)` — streaming variant
- `Cksum.cksum(path: Path): (Long, Long)` — file variant
- `HashSuite`: new test suite covering CRC32 correctness and streaming equivalence

**Benchmarks**

- `bench.sc`, `benchBreeze.sc`, `py/bench.py`: added `mean(1000×1000)` and `std(1000×1000)`
  rows to all three benchmark scripts
- Updated all documented benchmark numbers in `README.md` and `docs/MatDCheatSheet.md`
  to reflect results measured on Windows 11 with the current optimisations

---

## v0.13.3 — 2026-05-10

**Bug fixes — Linux BLAS**
- Fixed bytedeco/OpenBLAS native library loading failure on Linux that prevented LAPACK operations
  (SVD, eigenvalues, Cholesky) from initialising correctly
- `Mat.matMultiply`: detect at runtime whether netlib loaded its native JNIBLAS implementation;
  fall back to bytedeco/OpenBLAS matmul only when the native path is unavailable, restoring
  optimal performance on Linux systems where `libblas.so.3` is present

**Bug fixes — Windows path handling**
- `Resolver.classify`: `C://ghcup/bin` (double slash after drive letter) was incorrectly classified
  as `Invalid` because the old guard was `p.contains("://")`; changed to `p.indexOf("://") > 1`
  so single-character drive letters are not mistaken for URI schemes
- `Proc.whereInPath`: replaced `sys.env.get("PATH")` (Scala's case-sensitive `Map`) with
  `System.getenv("PATH")` (case-insensitive on Windows) so the variable is found regardless
  of whether the OS names it `PATH` or `Path`
- `Proc.whereInPath`: replaced `Files.isExecutable` with `Files.exists` on Windows —
  `isExecutable` is unreliable for system executables (ACL check can return `false` for
  files that are plainly runnable)
- `Proc.whereInPath`: switched from `java.nio.file.Paths.get` to `uni.Paths.get` so that
  POSIX-style PATH entries emitted by MSYS2/Git Bash shells resolve correctly on Windows

**CI — Windows support**
- Added `windows-latest` to the CI test matrix
- Added `.gitattributes` enforcing LF line endings for `*.scala`, `*.sc`, `*.sbt`, `*.yml`,
  `*.md` — prevents Git's `core.autocrlf` from injecting `\r` into triple-quoted string
  literals, which broke `assertEquals` comparisons on Windows runners
- `RootRelativeTest`: added `assume` guard to skip drive-letter tests when the MSYS/Git Bash
  root (`C:`) is on a different drive than the workspace (`D:`) — a common CI runner layout
- `MatCoverageSuite`: raised `munitTimeout` to 120 s — Windows Defender scans bytedeco's
  extracted native DLL on first use, which can exceed MUnit's default 30 s timeout on CI
- Release workflow: publish step now requires tests to pass on all three platforms
  (ubuntu, macos, windows) before Sonatype upload proceeds

**Tests**
- `BlasDiagSuite`: new suite verifying BLAS backend selection logic on Linux
- `UniRootCoverageSuite`: regression test asserting that `C://ghcup/bin` and similar
  double-slash Windows paths are classified as `Absolute`, not `Invalid`

**Benchmarks (internal)**
- Cleaned up and extended matmul benchmark scripts (`bench.sc`, `benchBreeze.sc`,
  `benchMatmulNetlib.sc`, `benchMatmulBytedeco.sc`)

---

## v0.13.2

**Subprocess API — deadlock fix and cleanup**
- `Proc.run(cmd*)`: replaced `LazyList`/queue-backed lazy streams with eager draining threads —
  a dedicated daemon thread drains each output queue into a `ListBuffer` concurrently with
  `process.waitFor()`, eliminating the bounded-queue (64-slot) deadlock on long-running commands
- `ProcResult.lines` / `.errLines` now return `Seq[String]` (eagerly collected) instead of lazy `LazyList`
- Streaming `run(cmd*)(out, err)` overload no longer requires an implicit `ExecutionContext` —
  switched from `Future`-based readers to plain daemon `Thread`s
- Added `Int` extensions (moved from `PathsUtils` to `ProcUtils`, now package-level):
  - `status !! msg` — log to stderr on non-zero exit; chainable
  - `status orElse f` — invoke callback with error description on failure
  - `status orFail msg` — short-circuit a `failFast` block on non-zero exit
- Added `failFast { ... }` block: any `.orFail` call inside short-circuits the block and returns the failing status

**Path I/O — safe resource management**
- Added `p.withLines[A](f: Iterator[String] => A): A` — bracket pattern using `Using.resource`;
  guarantees stream close even on partial reads or exceptions; also available with a `charset` overload
- Added `p.eachLine(f: String => Unit)` — convenience wrapper around `withLines`; also available with a `charset` overload
- `firstLine`, `lines`, `lines(charset)` reimplemented via `withLines` (safe resource management)
- All new methods also available on `java.io.File` extension block
- `streamLines` made `private`; now wraps input in `BufferedInputStream` for better performance
- Removed unreliable `finalize()` safety net from `streamLines` iterator

**New app: `SourceTimestamps`**
- `src/main/scala/apps/SourceTimestamps.scala`: lists Scala source files whose filesystem mtime
  is newer than the last git commit timestamp; emits `touch -d` commands to sync them

**`updateVersion.sc` improvements**
- Added `-v` (verbose), `--` (report version and exit) flags
- Now accepts explicit file/directory arguments to scope the version update

**Tests**
- `PathExtsSuite`: 12 new tests covering `eachLine` and `withLines` for both `Path` and `java.io.File`
- `ProcSuite`: 8 new tests including a deadlock regression test (>64 output lines), nested streaming,
  `failFast`/`orFail`, `orElse`, and `toOption` composition patterns
- `StreamLinesSuite`: refactored to use `p.linesStream` instead of internal `streamLines` calls directly

## v0.13.1

**Bug fixes**
- `Paths`: dotfile paths (e.g. `.gitignore`, `.env`) were incorrectly resolved relative to
  `userdir` with the leading dot stripped — now preserved correctly
- `Big`: percentage strings (`"75%"`) now parse to `0.75` instead of `BigNaN`
- `PathExts`: corrected stale deprecation messages (`asFast` → `asFile`, `toPath` → `asPath`)

**Subprocess API**
- Extract `object Proc` to dedicated `ProcUtils.scala`
- Add fluent `proc(cmd*)` builder with `.cwd()`, `.env()`, `.stdin()`, `.timeout()`, `.run()`, `.stream()`
- `run(cmd*)` stdout/stderr backed by lazy `LinkedBlockingQueue` + daemon reader threads
- `ProcResult` extends `IndexedSeq[String]`; add `orElse`, `headOnly`, `takeOnly(n)`
- Add `Proc.whereInPath(prog): Option[String]`

**Matrix API**
- Add `Mat.eachCol` / `Mat.eachRow` — named alternatives to `m(::, *).map(f)` / `m(*, ::).map(f)`;
  avoids `*` import conflict when mixing `uni.data.*` and `breeze.linalg.*`

**Documentation**
- `SubprocessAPI.md`: document `proc` builder, full `ProcResult` API, environment utilities
- `MatDCheatSheet.md`: add "Migrating from Breeze" table; add reshape/flatten/ravel/size rows;
  show all equivalent MatD spellings for column/row mapping
- `README.md`: add reshape and per-axis mapping rows to NumPy mapping table

## v0.13.0

- See release commit for full change list

## v0.12.1
- Windows: netlib 3.2.0 publication JNIBLAS ->  Accelerate framework (always present, zero user setup)
- match on Opaque type `Big` at runtime
- Path extension methods return type implicit in method names
  - docs/UniScriptingTools.md details updated to match implementation
  - linesStream: Iterator[String]
  - lines: Seq[String])    | all lines; UTF-8 with Latin-1 fallback
  - linesStream: Iterator[String] | streaming lines; suitable for large files
  - firstLine: String | first line from Iterator
  - contentAsString: String | entire file as a string
  - byteArray: Array[Byte] | raw file content as bytes

**CSV**

| Method | Returns | Description |
| :--- | :--- | :--- |
| `.csvRows` | `Seq[Seq[String]]` | all parsed CSV rows |
| `.csvRowsStream` | `Iterator[Seq[String]]` | streaming CSV rows; suitable for large files |
| `.csvRowsAsync` | `Iterator[Seq[String]]` | async variant |
| `.csvRows(onRow)` | `Unit` | callback-per-row variant |


## v0.11.2
- Add `foreach` for `RowsView` and `ColsView` (enables `for (row <- m(*, ::))` and `for (col <- m(::, *))`)
- Fall back to bytedeco/OpenBLAS matmul if netlib fails to load its fast native implementation
- Align `threePrfUni.sc` and `threePrfBreeze.sc` RNG streams for reproducible cross-validation
- Add `release-and-publish.sh` release script

## v0.11.0
- Switch matrix multiply backend from bytedeco/OpenBLAS to `dev.ludovic.netlib:blas:3.1.1`
  - macOS: JNIBLAS → Accelerate framework (always present, zero user setup)
  - Linux: JNIBLAS → `libblas.so.3` (install `libopenblas0` for best performance)
  - Windows: falls back to VectorBLAS (Java Vector API SIMD) pending netlib 3.2.0 publication
- Add opaque types `CVec[T]` / `RVec[T]` with full `*@` dispatch table
- Rename matrix multiply operator `~@` → `*@`
- Add `.item` to extract scalar from a 1×1 matrix
- Add `uni.plot` package with `pairs()` scatterplot matrix
- Add CSV read/write extension methods
- macOS added to CI matrix

## v0.10.2
- Last release using bytedeco/OpenBLAS as the matrix multiply backend
- Add `uni.plot` package (XChart-based visualization)

## v0.10.1 and earlier
- added Windows filetype enum and classifier
- refactor Paths.scala removing unrelated code
- switch tests to munit
- add more test cases
- dropped all third-party deps except test harness
- fast LinesIterator for streaming / large files 
- fast csv streaming with delimiter-detection
- performance refactor + csv extension methods
- update README
- lots of bug fixes as a side effect of refactor
- from-scratch rewrite of org.vastblue:pallet

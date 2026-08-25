# marketSim upgrade plan

Every item below was written after a real consumer study hit the limitation it describes: the
designated-sleeve trend gate in the `ue` pipeline (hold a sleeve only while its own trailing
12-month total return is positive) was evaluated for its lookback, its form and its cadence, using
`market_sim.exe` as a path generator. Nothing here is speculative ergonomics. Each item names the
evidence, the change, the parity consequence, and a falsifiable acceptance test.

The study is also the model for how the simulator gets *used* from outside: `-emit` with the seed
sequence `seed + k*7919` reproduces the model's own path family, so a consumer can grade arbitrary
rules on marketSim paths without importing or modifying either twin. Several items below simply make
that pattern complete instead of accidental.

## Constraints any change must respect

- **Two twins, byte-identical.** `jsrc/marketSim.sc` and `rust/examples/market_sim.rs` agree exactly
  across `-emit`, `-validate`, `-buffer`, `-power` and `-strategies`. Every item lands in both, in
  one change, and the output diff is the test.
- **Signed zeros.** Any printed column whose true value can be identically zero must render through
  `pm`, which blanks the sign when every digit is zero. Without it the JVM/libm 1-ulp `log` gap makes
  the printed sign a coin flip. This is the only class of parity failure marketSim has produced.
- **Frozen constants stay frozen unless promoted deliberately.** The header lists them. Promoting one
  to a `World` field is a legitimate change (item W7 proposes exactly that) but it must be recorded
  there, not done silently.
- **The recorded scope exclusion stands.** Daily kurtosis (~13 against ~28 real) traces to the
  absent slow valuation cycle and is deliberately not fixed. Nothing here reopens that.
  **Crash frequency left this bucket in 0.19.1** and the attribution it carried was wrong: it is
  driven by market depth, not by the valuation cycle. It reads 1.32x real in 0.19.2, about 1.2 sigma
  against the sampling error of its own anchor (15 episodes in 72 years) — up from 1.20x, the price
  of W7d's rate-cut cap, and partly bought back with `depth`.
- **A default move states who it affects, in the CHANGELOG, in three parts.** *Improves* — which
  consumer questions become answerable or better-founded. *Degrades* — which become worse, with the
  DIRECTION of the error, because "volatility now reads 8% low" and "8% high" have opposite
  consequences for a threshold rule. *Unchanged* — usually the rank comparisons, and saying so stops
  a reader assuming the move touched everything. An aggregate loss that improved is not this: five
  individually-acceptable trades can accumulate underneath one, and only the per-consumer reading
  shows it. `-releases` produces the raw material.
- **A sweep arm must bracket its baseline, and nothing checks that it still does.** The sweep
  exists to make conclusions curves rather than points, which requires the two arms to straddle the
  base. Moving the defaults in 0.19.1 silently broke three pairs — one arm named "few trend
  followers" carried 2.5x the baseline's, and "deep market" was shallower than baseline. Perturbation
  points are therefore RELATIVE wherever they are perturbation sizes rather than named economic
  conditions, so the property holds by construction.
- **A series adopted as a fit target is spent as a validation series, permanently.** W9 adopted a
  consumer's SPY 1993-2026 depth measurement as three fidelity targets; comparing the model to SPY
  on those rungs is now a calibration check and cannot be evidence again. Before adopting any
  measurement as a target, name what will validate the result afterwards — and record it, because
  the disqualification is invisible in the output and the comparison keeps looking meaningful.
- **A fix that passes a gate by wrecking an unmeasured quantity has not passed anything.** W7d
  found a one-line change reaching the bond-depth band exactly — while putting the policy rate below
  1% for **half the century** against a real ~19%, at a 1.0% median against ~3.4%. Nothing in the
  gate looks at the rate's own distribution, so the run reported PASS. Before accepting any fix,
  measure the quantity it works THROUGH, not only the quantity it was aimed at; if the gate does not
  already constrain that quantity, say so.
- **Widening an acceptance band to admit a world you want is not on the table.** Item W2 exists
  precisely because the alternative — relaxing the bond-volatility band so calm bonds pass — would
  make the gate stop meaning anything.

## How the simulator ships (0.19.3)

The simulator is public API in both published artifacts, not a repo-only tool:

- **Rust**: the crate packages `examples/market_sim.rs` (with the other eight demo pairs), so
  `cargo install vastblue-uni --example market_sim` builds the binary from crates.io.
- **Scala**: `uni.apps.MarketSim` is compiled into the jar —
  `scala-cli run --jar uni_3-0.19.3.jar --main-class uni.apps.MarketSim -- -validate`.
- `src/main/scala/apps/MarketSim.scala` is the only Scala copy of the model; `jsrc/marketSim.sc` is
  a thin launcher that dispatches into it, so the version its sidecar stamps
  (`uni.BuildInfo.version`) and the code that runs come from one artifact. `ScriptTwinSuite` fails
  the build if the launcher stops dispatching or is overwritten with a copy. Between releases the
  launcher runs the last *published* jar, not the working tree — `sbt publishLocal` (from a fresh
  sbt, not a stale `--client` server) is what moves it forward. Both shipped forms are
  byte-identical to the Rust binary across every mode.

## Two consumers, one plan

A second consumer — folio, a levered-ETF book whose core strategy family reads distance from a
running peak — grades these items differently, and its ordering governs where a shared item lands.
It holds **no bond ticker of any kind**, so W2 and W7 buy it nothing; it uses the Rust binary only,
so W0 does not apply; and it will not add rules to `rules()`, so W4 is evidence rather than code.
Against that, it needs W1 for a reason this plan did not name: every folio exposure rule de-risks to
*cash*, and its backtest engine credits idle cash at a dated rate, so an emitted path without `rate`
silently understates the de-risked leg of every timing rule.

folio's order is **W1 → W3 → W5 → W9 → W8**, and it reverses this plan's W8-before-W9 because W9 is
measured to fail on the equity leg too (see W9), not merely anticipated. W7 stays last for both.

## Status

| item | state |
|---|---|
| W0 | superseded: the model's one Scala copy is `src/main/scala/apps/MarketSim.scala` (see below) |
| W1, W2, W3, W9, W6a, W6b | **landed**, both twins, byte-identical across every mode |
| W5, W7a, W7b, W7d | **landed (0.19.2)**, both twins |
| W4, W7c, W8 | open |

W6 was split in 0.19.1; its original framing — "make the crowd *window* usable" — was retired by
measurement, see below.

## W0 — Re-sync the stale consumer copy (housekeeping, do first)

`/opt/ue/jsrc/marketSim.sc` is 14 lines behind `uni/jsrc/marketSim.sc`: it predates the `pm` helper
and still prints seven signed columns through `%+.Nf`. The difference is rendering-only — no
numeric path differs — but a consumer running the stale Scala copy will disagree with the Rust twin
in exactly the columns PARITY.md documents as the known failure mode, and will conclude the twins
have drifted numerically. Copy the uni version over, or record which copy is canonical.

**Acceptance**: `diff` of the two files is empty, or a note in PARITY.md names the canonical copy and
the consumer's obligation to pull from it.

**Resolved by the second route, then superseded.** The interim resolution named
`uni/jsrc/marketSim.sc` canonical with the fork obliged to pull from it. The stronger arrangement
replaced it: the model has ONE Scala copy, `src/main/scala/apps/MarketSim.scala` in the published
jar, and `uni/jsrc/marketSim.sc` is a launcher that dispatches into it. A consumer fork should
adopt the launcher — there is no full-source script left to pull, and a fork that keeps its old
copy disagrees with both twins in every item below.

---

# Tier 1 — cheap, and they unblock external use immediately

## W1 — Widen `-emit` to the full state, and to any path — **LANDED**

**Problem.** `-emit` writes date, price and bond, for path 0 only. Everything else the model knows —
the short rate, the CPI level, both markets' liquidity multipliers, the equity fundamental, the
inflation-pressure state — stays inside. A consumer grading rules on emitted paths is therefore
forced into metrics that are invariant to the missing series.

**Evidence.** The sleeve study had to restrict itself to `vsFlat` (advantage over a constant position
at the arm's own average exposure), which is exactly cash-rate-invariant, and to report nominal
drawdowns with a flat trading cost. Those were the right choices given what was available, but they
cost the study its ability to compare a gated arm against a fully-invested one on total return, and
its ability to charge liquidity-scaled slippage the way `armPath` does internally.

**Change.** Emit `rate`, `cpi`, `liq`, `bliq` (see W2 — the bond market's `lastLiq` is currently
computed and discarded), `fundamental` and `inflPress` alongside price and bond. Add `-emitpath N`,
or a prefix form that writes every path.

**Parity.** Additive columns in one text writer; both twins must produce identical text. Low risk —
no new arithmetic.

**Acceptance.** An external consumer, given only the emitted file, reproduces marketSim's own
`-strategies` row for one rule to the last printed digit. Today the closest achievable is the gross
`vsFlat` column, which matched at +4.26 against the simulator's +4.24 — close enough to validate a
harness, not close enough to be a parity test.

**Effort.** Small.

### As shipped

```
-version        the release this binary was built from, alone on stdout; exit 0
-emit F         one path as a full-state TSV, plus the sidecar F.json
-emitpath N     which path index (default 0); path k is seed + k*7919 and needs no larger run
-emitall        every path of the run to F-000.tsv, F-001.tsv, ... each with its sidecar
-emitstart D    YYYY-MM-DD, stepping by WEEKDAYS, so the file joins a real dated series
-emitgate P     paths in the ensemble that decides the verdict (default 200; 0 = the sample)
```

Columns: `date price bond rate cpi liq bliq fundamental inflPress`, with a header row.

Three properties beyond the item as written, each closing a gap a consumer hit:

- **Provenance is a sidecar, not a stderr line.** `F.json` carries the generator's `version`, the
  world's every field, the base seed, the stride, the path index and its derived seed, the calendar,
  both W3 verdicts with the named failures, and the fidelity ratios including the known misses. A warning printed at export
  does not survive the file being moved; an ensemble that cannot be named cannot be inventoried, and
  re-drawing the same paths in two campaign rounds would otherwise count as independent evidence.
- **Dates can be real and weekday-aligned.** The default calendar steps `365/252` days from
  1900-01-02 and lands on weekends, so an emitted path could never be joined to a real dated series
  (FRED rates, `^VIX`, a credit-spread brake) and always needed a special case. `-emitstart` steps
  weekdays instead. No holiday calendar — recorded, not hidden.
- **The verdict is measured on the world, not on the emitted sample.** `-paths 1 -years 20 -emit`
  used to print all four mechanism failures for a world that passes every check at 200 paths: they
  are conditional-on-episode statistics one short path cannot measure. Every export therefore
  carried a false alarm, which is worse than no warning. The verdict now comes from a `-emitgate`
  ensemble of the same world; `-emitgate 0` restores judging by the sample.

Signed zeros: `rate` is floored at zero and `inflPress` starts there, so every emitted column is
folded through `(-0.0) + 0.0 = +0.0` before formatting.

## W2 — Expose the bond market's liquidity series — **LANDED**

**Problem.** `Path.liq` is the equity market's slippage multiplier. The bond market computes its own
`lastLiq` in `Market.step` and throws it away, so any arm that trades the bond is either charged
equity slippage or a flat cost.

**Change.** Carry `bliq` on `Path`, filled the same way `lq` is.

**Parity.** Additive; changes `-emit` only if W1 lands with it, which it should.

**Acceptance.** A bond-side arm's `slip x` multiplier differs from the equity-side one in a world
where the bond spiral engages, and equals it in a world where `-stress 0`.

**Effort.** Trivial. Bundle with W1.

## W3 — Split the acceptance gate into realism bands and mechanism-engagement checks — **LANDED**

**Problem.** `gateChecks` mixes two kinds of statement and reports one verdict. "bond vol 7–20%" says
*this world is not a market*. "bond spiral engages, not always" says *a mechanism is inert here*. The
first invalidates every conclusion; the second invalidates only conclusions that depend on that
mechanism.

**Evidence.** The duration-6y world — the closest admissible analogue of an aggregate bond fund, and
the single most decision-relevant world in the whole sleeve study — passes every realism band (bond
vol 7.8%, inside 7–20) and fails only "bond spiral engages, not always". Under the current binary
verdict it was excluded from every pooled panel, and the study lost its most relevant frame.

**Change.** Tag each check with its class. Print two verdicts. Let a report declare which class it
requires; keep `-validate`'s exit code driven by realism failures, with a flag deciding whether
mechanism failures are also fatal.

**Parity.** Output text changes in `-validate` and `-strategies` headers. Expected diff; regenerate.

**Acceptance.** The duration-6y world reports `realism PASS / mechanism FAIL` and names the mechanism.
No world that currently passes changes verdict.

**Effort.** Small.

### As shipped

The split falls on a real seam: the nine **realism** checks are unconditional distributional
properties of the whole sample; the four **mechanism** checks — bonds rally in growth shocks, bonds
lose in inflation regimes, corr flips positive under inflation, bond spiral engages — are every one
of them conditional on a crash or inflation episode. That is the same four `-emit` used to
false-alarm on, which is corroboration that the seam is the model's and not an editorial choice.

`-validate` prints both lists and a `verdict:` line naming any inert mechanism. `-gate realism|full`
decides admissibility, and it governs both the `-validate` exit code and which worlds enter the
pooled panels of `-strategies`, `-power` and `-buffer`.

**The default is `full`**, which departs from the item's "keep `-validate`'s exit code driven by
realism failures". Every existing report therefore keeps its exact output and exit code, and the
widening is opt-in; flipping the default is a one-line change if a consumer would rather have it.

Measured, 19 worlds at 12 paths x 30 years: 18 worlds out of range under `full`, **2** under
`-gate realism`. The duration-6y world reports `realism PASS   mechanism FAIL   inert: bond spiral
engages, not always` and exits 0 under `-gate realism`.

---

# Tier 2 — make the consumer pipeline's actual rules native

## W4 — Add the three rule shapes the consumer had to build outside

**Problem.** The `Rules` library is equity-shaped: moving-average, volatility-scaled and drawdown
rules, all applied to `p.price`, all re-decided daily. The production rules being studied are
*return-sign* rules applied to *diversifier* sleeves at *quarterly* cadence.

**Evidence.** All three gaps had to be reimplemented in an external harness:

1. **Return-sign form.** `trendRule` compares price to the *mean* of the window. The rule under test
   compares the window's two *endpoints*. These are different rules, and in the study they differed
   by ~1 pp/yr and ~6 points of maximum drawdown at the same window length, at n\* = 4 — i.e. the
   distinction is resolvable, not cosmetic.
2. **A cadence combinator.** Holding a decision between decision dates. Production can only act at a
   quarterly rebalance. In the study, cadence turned out to interact with lookback so strongly that
   neither is interpretable alone: at a 364-day window, daily-versus-annual re-decision is worth ~0;
   at a 60-day window it is worth +9 pp/yr.
3. **A risky-asset selector.** So an arm can trade the bond with cash as the safe leg. Every ticker
   the consumer's gate governs is a diversifier, not the equity book.

**Change.** A `signGate` alongside `trendRule`; a `sampled(e, every)` combinator; a `Risky` selector
threaded through `armPath`. Roughly 30 lines each side.

**Parity.** Adding rows to `Rules` changes every `-strategies` line. Expected diff; regenerate
fixtures. The combinator introduces no new arithmetic.

**Acceptance.** `-strategies` gains a return-sign row at quarterly cadence on the bond, and its
numbers match the external harness that produced the sleeve study to the last printed digit. That
is a real cross-implementation check, not a self-consistency check.

**Effort.** Moderate.

## W5 — Parameterized power, with a hit-rate column

**Problem.** `-power` answers one fixed question: how much history do these four preset arms need,
against these preset contrasts, at these four horizons. The question a consumer actually has is *for
these two specific arms, at the length of history I actually possess, what fraction of single
histories shows the right sign?*

**Evidence.** That question arose three times in the sleeve study and had to be added externally as
a `hit%` column. It was decisive: at 100-year histories the contrast of interest was resolvable from
one history, and at 19 years — the real sample length — the same contrast was resolvable only in the
high-volatility worlds. Without that number the real-data result looks like a contradiction of the
simulated one. With it, the two agree.

**Change.** Let `-power` take arm selectors and a horizon list. Expose `hit%` per contrast. The
machinery already computes the hit rate; it is the arm selection that is hard-coded.

**Parity.** Output format changes; arithmetic unchanged.

**Acceptance.** Given its current arms and horizons, the new `-power` reproduces the current table
byte-for-byte. Given a new pair, it answers without a code change.

**Effort.** Small-to-moderate.

## W6a — Give the reflexive channel a strength dial — **LANDED (0.19.1)**

**Problem.** `Crowd::Momentum`, the default, never read `crowdImpact`: its flow was
`kTrend * wTrend * trendPos` with `kTrend` frozen at 0.0045. `-crowdimpact 0.01` and `-crowdimpact
0.50` produced **bit-identical** output. The one experiment this simulator offers that no
resampling of a real series can — "what happens when the crowd runs my rule" — had no strength
control in the world every report defaults to.

**Root cause, and the reason it survived four releases.** No binding diagnostic. The header requires
one for every mechanism ("a BINDING diagnostic printed in the output"); the crowd had none, so a
dead knob looked exactly like a live one with a small effect.

**Change (landed).** `kTrend * (w.crowdImpact / CrowdImpactRef) * wTrend * trendPos`, with
`CrowdImpactRef = 0.06`. The *ratio* enters the flow, so the default divides to a bit-exact `1.0`
and the shipped world is unchanged — verified by diffing the patched Scala twin against the
unpatched Rust one, which differed only by the new diagnostic line. The report now prints crowd
flow in bp/session and as a share of the noise term.

**Acceptance (met).** Default output byte-identical before and after, both twins. The dial moves
monotonically and binds:

| `-crowdimpact` | 0.01 | 0.03 | 0.06 (default) | 0.12 | 0.25 |
|---|---|---|---|---|---|
| crowd flow, bp/session | 0.60 | 1.89 | 4.09 | 10.40 | 42.18 |
| share of the noise term | 0.9% | 2.7% | 5.8% | 14.9% | 60.3% |
| equity vol | 14.23% | 14.41% | 14.80% | 16.48% | 25.73% |

**Retired by measurement.** The original item was titled "make the crowd-*window* control usable"
and proposed promoting the 60-session momentum window to a `World` field. Both halves are wrong:

- *The window is not the sensitive parameter.* `trend60` through `trend500` differ by 1.5
  crashes/century and 0.01 on the 10% rung. Strength is what matters; the window is nearly inert.
- *`trendNNN` worlds are no longer inadmissible.* The claim was "every `trendNNN` world tested fails
  the acceptance gate (they break bonds rally in growth shocks)". On the 0.19.1 default, **all seven
  crowd modes pass realism and mechanism** — `momentum`, `trend60/100/200/300/500`, `volscaled` —
  with the bond depth rung the only failure in every one. It was already partly stale before the
  default moved: `trend300` passed on the old default. Nobody noticed because the claim carried no
  date.

The artifact control the original item wanted is therefore available now, in seven admissible
worlds instead of one. What it still cannot answer is whether the *rule ranking* moves with the
crowd's window — aggregate statistics being insensitive does not settle that — and that is a study,
not a code change.

## W6b — Put reflexive worlds in the sweep — **LANDED (0.19.1)**

**Problem.** `sweepWorlds` varies nineteen parameters and **not one of them is the crowd**. Every
`-strategies`, `-power` and `-buffer` report has therefore been produced with the crowd held fixed
and non-reactive. The rank-stability table's real claim is "ranks are stable across market
character, *holding the crowd fixed and non-reactive*", and that qualifier is invisible in the
output.

**Why it is not pedantry.** The existing crowd evidence says a vol-scaling crowd turns every trend
rule's edge negative. That is a rank inversion, not a perturbation — the axis most likely to break
rank stability is the one axis the sweep has never varied.

**Change.** Add two worlds, and **scope the first pass to `-strategies` only**. Reflexivity is the
point in the rank-stability table, which is where the invisible qualifier lives; in `-power` and
`-buffer` it is a second-order effect on dispersion and crash dynamics. Doing all three at once
reflows every pooled panel for a benefit concentrated in one of them. Extend if the `-strategies`
result warrants it.

The two entries vary **different axes**, which W6a made possible and which the item must not
conflate:

| entry | varies | why this one |
|---|---|---|
| `reflexive: crowd runs a vol rule` | *mode* — `-crowd volscaled` at the default strength | the mode that turned every trend rule's edge negative; the consumer most exposed to it holds 13 volatility-keyed sleeves against a smaller trend family |
| `reflexive: crowd pressed hard` | *strength* — `-crowdimpact 0.12` under `momentum` | same mechanism, no mode change, so the two entries are separable. 0.12 is the stress case: admissible, while 0.25 fails the gate |

Before W6a there was only one dimension to vary, so "which crowd" was the whole question. Now
`crowdImpact` is a `World` field in the calibrate ranges, and a mode entry that does not state a
strength silently picks one — the default 0.088, which is not obviously the interesting value.

**Design constraint, and the way this item can go wrong.** Do **not** pool the new worlds into the
same average as the nineteen. The existing worlds are one-knob sensitivity on market *character*; a
reflexive world changes *who is trading*. A pooled "stable across 21 worlds" that conceals "unstable
across the 2 that matter" is strictly worse than not adding them at all. Report them as their own
panel, or tag them, so the qualifier survives the change.

**Acceptance.** The `-strategies` rank-stability output states which worlds are reflexive, and a
reader can see the reflexive result separately from the character result. If the pooled panels
reflow, **the reflow is the finding**: it shows rank stability held only because reflexivity was
absent.

**Parity.** No new state; two more worlds in one `Vector`/`vec!`. Cheap in code, and it changes
every `-strategies` report in both twins at once.

**Effort.** Small in code, moderate in output review — every pooled `-strategies` panel moves.
`-power` and `-buffer` are untouched by the first pass.

# Tier 3 — structural, and they change what the model is allowed to claim

## W7 — Make an aggregate bond fund representable (W7a, W7b, W7d landed; W7c open)

**Problem.** The admissible bond-volatility band is 7–20%. Measured annualized volatility of the
sleeves the consumer's gate actually governs:

| sleeve | ann. vol | inside the band |
|---|---|---|
| BNDX | 3.9% | no |
| BND | 5.2% | no |
| SCHP | 5.6% | no |
| DBMF | 12.4% | yes |
| FNV | 40.1% | no — above the model's *equity*, 14.7% |

Duration is the only knob, and dialing it to 4 years yields a 6.3%-volatility bond that already
**fails** the gate for being too calm — still more volatile than any bond sleeve in the book.

**Consequence, stated plainly: the simulator has nothing admissible to say about aggregate bond
funds**, which are most of what the consumer's designated-sleeve machinery holds. The sleeve study
found a large, well-controlled effect on the lookback axis and could not apply it, because the
model's own duration trend extrapolates that effect toward zero at the volatilities in question and
no admissible world sits there.

**What is NOT wrong with the bond model.** Graded at a *matched horizon* — 24-year paths against 24
years of a clean iShares TLT total-return series, built from NAV plus reinvested distributions — the
default 13.5-year world reproduces a real long Treasury closely: 3.46%/yr against 3.46%, maximum
drawdown 47.1% against 49.5%, underwater fraction 0.97 against 0.97, volatility ~14.5% against
14.1%. Compare 100-year simulated paths against 24 years of history instead and the same model looks
badly wrong (maximum drawdown 78%), because maximum drawdown is a max order statistic and grows with
sample length. **Match the horizon before judging any path statistic against a real series.**

The defect is therefore localized: the bond is sound where it is calibrated and has no
representation at all below ~7% volatility. This item is an extension, not a repair.

**Measured, 2026-08-22.** Eight iShares funds, 19-24 years each, total returns from NAV plus
distributions (the same source and method as the TLT anchor, which this pipeline reproduces to
14.12% against the recorded 14.1%):

| fund | kind | duration | ann vol | >5% | >10% | >20% |
|---|---|---|---|---|---|---|
| SHY | Treasury | 1.80 | 1.44 | 0.007 | 0.000 | 0.000 |
| IEI | Treasury | 4.22 | 4.02 | 0.152 | 0.064 | 0.000 |
| IEF | Treasury | 6.84 | 6.65 | 0.387 | 0.181 | 0.019 |
| TLH | Treasury | 11.60 | 10.27 | 0.566 | 0.314 | 0.223 |
| TLT | Treasury | 14.89 | 14.12 | 0.709 | 0.499 | 0.226 |
| AGG | blend | 5.70 | 4.24 | 0.152 | 0.095 | 0.000 |
| LQD | IG credit | 7.72 | 6.36 | 0.221 | 0.123 | 0.011 |
| HYG | HY credit | 3.02 | 6.04 | 0.199 | 0.080 | 0.027 |

The five Treasuries fit `vol = -0.07 + 0.937 x duration`: **an intercept of zero**, as it must be,
since a zero-duration bond is cash.

**W7b — the volatility floor — LANDED (0.19.2).** `SigmaNBond` was duration-INDEPENDENT, so the
model read `vol = 5.11 + 0.696 x duration`. The two errors cancelled at TLT's duration, where the
bond was calibrated (1.10x real), and compounded as duration fell (4.01x at SHY's). The short half
of the bond universe was unreachable *by construction*: nothing below 5.11% volatility existed at
any parameter setting. The noise now scales as `duration / 13.5`, giving `vol = +0.12 + 1.044 x
duration`; 13.5 is the shipped default, so the ratio is a bit-exact 1.0 there and the default world
is byte-identical. At AGG's 5.70-year duration the model now produces 5.99% volatility and the ONLY
remaining gate failure is `bond vol 7-20%`.

The residual is a uniform 11% slope overstatement (1.044 against 0.937). Left alone deliberately:
correcting it means retuning rate volatility, which reaches the equity leg through the discount
channel and would forfeit the default's invariance. A uniform scale error is also a far better
failure mode than an additive floor.

**W7a — the anchors and checks hardcoded long Treasury — LANDED (0.19.2).** Three separate faults,
all of which made the gate false for every bond except the one it was built from:

- *A horizon mismatch.* `bond vol` is the one bond statistic that is strongly horizon-dependent
  (12.57% over 24 years against 17.12% over 100, as a longer window samples more rate-regime
  variation), and it was scored on 100-year paths against a 24-year fund anchor. It now measures
  over non-overlapping 24-year windows. The ratio was 1.32; horizon-matched it is **1.03**. Measured:
  the other three bond statistics move 1.02x, 1.12x and 0.90x across that range, so the split is
  confined to one row — and because a differing protocol is easy to misread, the horizon travels in
  the row's own label (`bond vol % (24y)`), the gate check's name, the summary line and the report's
  anchor header.
- *An absolute volatility band.* `bond vol 7-20%` admitted one of the eight real funds and asserted
  of the US Aggregate that it is not a market. Now `0.5-2.5x duration` (realism) and `0.70-1.10x
  duration` (fidelity): Treasuries measure 0.798-0.973, the Aggregate 0.745, investment grade 0.824.
  High yield at 2.001 is admitted as a market but excluded from the fidelity band, since credit is
  out of scope until W7c.
- *A TLT depth anchor.* 0.510 was TLT's, against a real range of 0.000-0.499 across eight funds. The
  row is now `bond depth vs vol`, measured against what the bond's own volatility implies
  (`d10 = 0.0397 * vol - 0.0785`, fitted on the five Treasuries over a 1.44-14.12% range).

`WorldStats` gains `duration` for this, carried through `Path`, so the gate can judge a bond
relative to what it is.

**What this exposed, which the hardcoding had been hiding.** With the anchors correct the bond spent
**1.7x to 6x** longer below its running peak than a real bond of the same volatility (1.7x at TLT's
volatility, 4.1x at IEI's, 6.0x at HYG's) — the same "sits below its peak too long" defect the
equity leg carried before 0.19.1. The reading was 1.91 at the default duration, 3.31 at 5.7 years,
and 1.02 at 25 years: worst at the SHORT end, not uniform. Nobody had to notice it; the gate stated
it. Fixed in W7d.

**Still hardcoded, on thin evidence: `bond growth-crash 20.0`.** That is close to TLT's single 2008
episode (+22.3). Across the six real 15%+ equity drawdowns since 2002 TLT's median is +9.65, and the
model reads 13.32. Six episodes is too few to re-anchor on, and comparing a six-sample median to the
model's many-sample one is its own error. Left as a recorded MISS rather than replaced with a number
that is thin in a different way.

**W7d — the bond's drawdown profile — LANDED (0.19.2).** The defect W7a exposed, traced and fixed.

*Diagnosis.* **75% of the bond's time below its running peak was spent in episodes where inflation
pressure never rose at all and the rate never left 4.6-4.8%.** Inflation regimes accounted for 25%.
Every one of those episodes began at a peak set when the policy rate touched zero, and the rate did
that in 23 episodes per century of **median length 0.14 years, maximum 0.45** — against real holds
of 7.0 years (2008-15), 2.0 years (2020-22) and ~15 years (1932-47). A 4.8pp round trip against 13.5
years of duration is a 65% log spike; at 5.5%/yr carry it takes twelve years to earn back. The bond
made new highs on 2.5% of sessions, in 172 runs of median 2 days. It was not too deep; it was
permanently recovering from a spike.

The cause was `flight`: an uncapped rate cut SPEED (0.48/yr at full stress, ~11x the fastest real
easing cycle) integrated into the rate and then pulled back by the same fast `rateSpeed`.

*Ruled out by measurement, so that nobody repeats them.* Horizon: 24-year windows read 0.857 against
0.860 whole-path, so unlike bond volatility this statistic is horizon-free. Inflation severity: a
minority contributor. Four candidate fixes, none of which reach the band — a symmetric slowdown
(`-ratespeed 0.5`, 1.45, and it over-deepens the inflation crash to 1.41x); a slow near-unit-root
second rate factor (1.66 at best, and it raises bond volatility); a refuge flow on top of the
uncapped cut (1.82, **worse** — a flow-driven rally is also a spike); and an asymmetric hold below
the mean, which reaches 1.27 but only by wrecking the rate distribution (see the standing constraint
above), and stops at 1.59 once the rate is defensible again.

*The fix, in two parts, both load-bearing.* `flight` is replaced by an accommodation STOCK: capped
at `easing` rate points, eased in at a frozen 6.0/yr (~2 months), withdrawn at `unwind` (0.35/yr, a
~2-year half-life), and suppressed by inflation exactly as before. Capping it alone fixes the depth
profile and **removes the crash rally outright** — the rally WAS the spike that set the peak — so
the bond also gains `refuge`, a flight-to-quality bid that scales with duration and bids for a bond
whose own market is still orderly.

They are not separately necessary, and the asymmetry is recorded rather than smoothed over: at
`-refuge 0` the mechanism check "bonds rally in growth shocks" FAILS (growth-crash 0.03x), so refuge
is load-bearing for the gate. `-easing 0` passes all three classes and reads 0.93 on the depth row
against the default's 1.24 — *better*, the target being 1.00. Easing is kept on the objective, not
the gate: it moves growth-crash 0.34 → 0.42 and inflation-crash 0.87 → 0.96 for a loss of
3.27 → 2.76, and the depth row is what pays. A consumer whose question is bond drawdown should run
`-easing 0`.

| duration | 0.19.1 + W7a | 0.19.2 |
|---|---|---|
| 5.7 (Aggregate-like) | 3.31 | 0.66 |
| 13.5 (default) | 1.91 **FAIL** | 1.24 |
| 25.0 | 1.02 | 0.93 |

All three now clear realism, mechanism AND fidelity — including the Aggregate-like 5.7-year world,
which is what W7 was opened for.

*The price, stated rather than tuned away.* Capping the cut also removed a discount-channel cushion
the equity leg was getting in crashes, so at unchanged dials the crash rate went 1.20 -> 1.38 and
clustering 1.08 -> 1.13. The default absorbs part of that — `depth` 16.3 -> 16.6 (crash 1.32) and
`stress` 5.4 -> 5.1 (clustering 1.06, the same trade 0.19.1 made against the same dial) — at a cost
of kurtosis 0.46 -> 0.42, which is a recorded scope exclusion either way. `bond growth-crash` falls
0.67 -> 0.42 against its thin 20.0 anchor, though it moves TOWARD TLT's six-episode median of 9.65.
The world clears **all three gate classes**, the first default to do so, and holds the best
held-out fitness score in an 8,000-sample search (2.588 against the best sample's 3.120).

**W7c — the credit channel — OPEN, lower value.** HYG at 3.02 years carries 6.04% volatility where
its duration implies ~2.8, and in 2020 LQD returned -16.0 where IEF returned +6.2. So credit is a
real second dimension, but it is a small correction for investment grade and does not block
anything; it is a fidelity improvement, not an unlock.

**Not pursued: a blend of two durations.** The fair-value update is linear in duration, so a blend
is arithmetically a single duration at the weighted average. It is a reparameterisation, not a
mechanism.

**Parity.** W7b: one expression, no new state, no `-emit` change. W7a: band and target edits only.

**Effort.** W7b was small. W7a is small. W7c is moderate.

## W8 — Calibrate against the term structure of return autocorrelation

**Problem.** This is the deepest item and the reason the sleeve study's central finding is careful
rather than conclusive. `FitTargets` constrains volatility, kurtosis, return clustering at lags 1
and 20, crash frequency and depth, and four bond behaviours. **None of them constrains the horizon
at which trends persist.** So when the model produces a monotone lookback curve — shorter windows
better, monotonically, across every admissible world — that shape is a free consequence of mechanism
design (a 60-session crowd momentum window, a value anchor with a ~67-day pull) rather than something
disciplined by data.

**Evidence.** The finding survived every control available: a circular-shift placebo (real spread ~7×
the placebo's, in all 13 admissible worlds), the crowd-window sweep, the weak-value-anchor world, and
a duration sweep from 4 to 20 years. "Robust to every knob I could turn" is genuinely stronger than
nothing. It is still weaker than "matched to a measured statistic", and no statistic in the gate
pins it.

**Change.** Add variance-ratio targets — VR(k) for k ≈ 1, 3, 6, 12, 24 months on both the equity and
bond series — to `FitTargets`, with real targets measured from the historical record used for the
rest of the calibration, and corresponding bands in `gateChecks`.

**Parity.** Additive reductions. If a new summation shape is introduced, it needs its own fixture
rows under `test-data/*-parity` per the standing rule that a new numeric surface gets pinned.

**Acceptance.**
- The current default world's VR profile is reported in the fidelity table, whatever it turns out
  to be.
- **At least one world in the existing sweep fails the new check** — a check that passes everywhere
  on the first run is not yet known to bind, and this repo has been bitten by that before.
- `-calibrate` can move the defaults toward the VR targets without pushing any existing fidelity
  term out of band, or the trade-off is recorded.

**Effort.** Large. But this is the item that converts every trend-rule conclusion the simulator will
ever produce from suggestive into load-bearing, and trend rules are what its consumers keep asking
it about.

## W9 — Calibrate against the drawdown-depth distribution — **LANDED**

> Every figure in this section was **measured against the pre-0.19.1 default world** and is kept as
> the record of why the item was raised, not as a current reading. Against the 0.19.1 default the
> three equity rungs read 0.500 / 0.335 / 0.156 and all three PASS. Do not quote these numbers as
> the model's behaviour today.

**Problem.** Volatility, maximum drawdown and underwater fraction can all match a real series while
the *depth* distribution of drawdowns does not, and nothing in the gate notices. At the matched
24-year horizon above, the default bond world and clean TLT agree on all four headline statistics —
and the model's bond spends **81%** of its sessions more than 10% below its running peak where TLT
spends **51%**. The model's bond drifts far below its peak and stays there; the real one hugs its
peak and makes new highs far more often. Same volatility, same worst case, same time-under-water in
the any-depth sense, different shape.

**Consequence.** Any rule that reads distance from a running peak is degenerate in the model and
functional in reality. In the sleeve study a 10%-drawdown gate sat out 81% of sessions and scored
−2.02 in the model, against 51% and +1.81 on the real series — a sign reversal, on the arm that was
serving as the *mechanism control*. Conclusions of the form "the gain comes from the trend signal,
not from de-risking speed" therefore cannot currently be drawn here on the bond side, and one that
was drawn had to be withdrawn.

**It is not a bond-only defect.** The equity leg fails the same way, at nearly the same magnitude —
measured by the second consumer, matched on horizon (33 years against SPY 1993-01-29 to 2026-08-20,
8447 sessions) *and* on volatility. Volatility was matched at the **world** level with
`-depth 10.5`, which yielded 18.7% median realized volatility against SPY's 18.6%; selecting the
sim's own high-volatility paths instead would select on the outcome being measured. 60 independent
paths:

> **The `-depth 10.5` hook is dead and must not be re-derived.** Against the 0.19.1 default it
> produces 24.3% volatility, not 18.6%. Recomputing the depth that matches SPY today would fix the
> arithmetic and still be the wrong test, for the reason in the box below the table.

| below peak | REAL SPY | sim default | sim vol-matched | ratio | real's percentile in the ensemble |
|---|---|---|---|---|---|
| >2% | 0.607 | 0.778 | 0.804 | 1.33x | 0th |
| >5% | 0.447 | 0.635 | 0.666 | 1.49x | 0th |
| >7.5% | 0.373 | 0.528 | 0.580 | 1.55x | 0th |
| >10% | 0.315 | 0.451 | 0.493 | 1.56x | 1.7th |
| >15% | 0.240 | 0.335 | 0.374 | 1.56x | 11.7th |
| >20% | 0.169 | 0.203 | 0.239 | 1.42x | 28.3th |

Not sampling noise: real SPY sits below the 2nd percentile of the ensemble at every depth the
consumer's rules key on. Not a volatility artifact either: the *default* world is already too deep
at 16.5% volatility, below SPY's 18.6%, and a more volatile series should spend *more* time below
peak, not less — matching volatility only widens the gap. Worst exactly in the shallow region, 2% to
15%, where peak-relative rules live; deep-drawdown rules are the least affected.

This is the item's own acceptance test run in advance, on the equity leg: a 10% drawdown gate sits
out **49%** of sessions in the vol-matched world against **32%** on real SPY.

Reproduce (historical; `-depth 10.5` no longer matches SPY's volatility — see the note above):

```
market_sim.exe -paths 60 -years 33 -depth 10.5 -emitall -emitstart 1993-01-29 -emit spy.tsv
# per series: dd = price / price.cummax() - 1; (dd < -X).mean() for X in .02 .05 .075 .10 .15 .20
```

> **SPY is spent, permanently.** Its 5/10/20% rungs became fit targets in W9, so any later
> comparison of this model's depth profile against SPY 1993-2026 is a calibration check, not
> evidence — however the volatility is matched, and no matter who runs it. This is a standing
> consequence of adopting the measurement, not a one-off to be tidied up.
>
> Validation of the depth profile goes through a series the calibration never saw. Currently that
> is CRSP value-weighted (33-year windows inside 1954-2026: real median 0.451 / 0.291 / 0.151
> against the model's 0.49 / 0.33 / 0.13, all three inside the real range).
>
> **CRSP is only partly independent, and the caveat matters.** `return per vol` is anchored on CRSP
> 1954-2026, and return-per-unit-volatility is the strongest single driver of the depth profile
> (measured: d(10% rung)/d(vol) is 0.71 for `drift` against 0.024 for `depth`). So the model's r/v
> was fit to CRSP's r/v, and the depth profile it produces inherits that. The CRSP check is
> independent in the *shape* of the drawdown distribution but not in the level of the quantity that
> most determines it. A fully independent check needs a series that anchors nothing here — a
> non-US market, or a held-out period.

**Change.** Add fidelity targets for the share of sessions spent more than X% below the running
peak, X = 5, 10, 20, on both markets, with real targets measured from the same historical record as
the rest of the calibration, and corresponding bands in `gateChecks`.

**Parity.** Additive reductions over a series `drawdownSeries` already produces on both sides. Low
risk, no new numeric surface.

**Acceptance.**
- Both default markets report their depth profiles in the fidelity table.
- **At least one world in the existing sweep fails the new check** — same standing requirement as
  W8; a check that passes everywhere on its first run is not yet known to bind.
- A drawdown-rule arm's `%out` in a gate-passing world lands within a few points of the same rule's
  `%out` on a real series of matching volatility. That is the falsifiable form of "peak-relative
  rules can be graded here now".

**Effort.** Moderate — materially smaller than W7 or W8, and it unblocks a whole class of
conclusions the same way they do. If only one Tier 3 item gets done, this is the cheapest one that
changes what the model may claim.

### As shipped

Every report and every `-emit` sidecar now carries the depth profile — the share of sessions more
than 5%, 10% and 20% below the running peak, median path, on **both** legs:

```
  depth profile          share of sessions below the running peak, median path
    equity               >5% 0.636   >10% 0.454   >20% 0.206      real SPY 0.447 / 0.315 / 0.169
    bond                 >5% 0.902   >10% 0.843   >20% 0.650      real TLT   -   / 0.510 /   -
```

Four of the six rungs carry a real anchor and enter `FitTargets` and the gate: the three equity
rungs from SPY 1993-01-29..2026-08-20, and the bond's 10% rung from a clean TLT total-return series.
The bond's 5% and 20% rungs are **reported but not targeted** — interpolating them would manufacture
a calibration anchor out of nothing. The equity anchors come from a different window than the
1954-2026 record behind the other fidelity rows; that is sound *for this statistic only*, because a
time share is horizon-stable where a max order statistic is not.

**A third gate class, `fidelity`.** The check could not go in `realism` without claiming that a world
whose depth profile is off is not a market at all, which would invalidate cost breakevens, ruin
rates and refuge mechanics along with it. So the gate now answers three questions, and `-gate` takes
the set of classes a report requires:

```
-gate realism             is this a market
-gate realism,mechanism   ...with its mechanisms live      (the default; unchanged admissibility)
-gate realism,fidelity    ...and this quantity's LEVEL readable
-gate all                 everything
```

Realism is always implied: `-gate fidelity` means `realism,fidelity`, because a report cannot
declare itself indifferent to whether the world is a market at all.

A fidelity failure invalidates only conclusions that read a **level** off the named quantity — a
time-out-of-market, a percentile threshold, a drawdown-conditioned hazard. Rank comparisons survive.

The fidelity rows also appear in the fidelity table with the ratio-based `<-- MISS` flag every row
carries. The two bands are deliberately different: MISS is the report convention (ratio outside
0.667–1.5x), the gate is the plan's own acceptance criterion (±10 points absolute), and a rung can
fail the gate while escaping the MISS flag — equity >10% at 0.454/0.315 is ratio 1.44. The gate
line is the authority on admissibility.

**The band is the acceptance test.** `drawdownRule(10, 0.0)` sets exposure to its floor exactly when
depth exceeds 10%, so its `%out` **is** `ddEq10`. The check `equity >10% below peak 0.215-0.415` is
therefore a restatement of this item's own acceptance criterion — "within a few points of the same
rule's `%out` on a real series" — made two-sided and enforced. `DepthTol = 0.10` is that "few
points", absolute rather than relative because the quantity compared is itself a share.

### Measured, and the trade-off this item asked to have recorded

19 worlds x 60 paths x 33 years. **Every world is too deep at the rungs peak-relative rules use.**
Equity `>10%` spans 0.335 (`high growth`) to 0.593 (`low growth`) against a real 0.315; `>5%` spans
0.518 to 0.742 against 0.447. Bond `>10%` spans 0.304 (`no flight bid`) to 0.861 (`severe liquidity
spiral`) against 0.510, straddling it. **0 of 19 worlds pass `realism,fidelity`** — the check binds
about as hard as a check can, and it binds two-sided: `no flight bid` fails the bond band from
*below*.

It is not a check that can never pass. `-stress 0` puts the bond at 0.546 (real 0.510) and equity
`>10%` at 0.383, passing three of the four rungs.

**The mechanism is the liquidity spiral**, which answers the standing question of what makes the
model sit below its peak half again as long as reality:

| `-stress` | eq >5/10/20 | bond >5/10/20 | vol | kurtosis |
|---|---|---|---|---|
| 0.0 | 0.582 0.383 0.137 | 0.780 **0.546** 0.185 | 12.3% | 3.39 |
| 1.7 | 0.604 0.413 0.160 | 0.860 0.738 0.387 | 13.8% | 4.00 |
| 3.4 (default) | 0.636 0.454 0.206 | 0.902 0.843 0.650 | 16.5% | 7.39 |
| real | 0.447 0.315 0.169 | — 0.510 — | 16.0% (S&P) | 28.0 |

**And it cannot be calibrated away.** Turning `stress` down to reach the real depth profile drops
volatility to 12.3% (target 16.0) and kurtosis to 3.39, which fails the `kurtosis 4-30` realism band
— a band already passing only because the model's tails are a quarter of reality's. Depth trades
directly against the two terms the tail calibration is holding up.

The other apparent route is worse. `-drift 0.140` passes all three equity rungs (0.515 / 0.313 /
0.112) but buys them with a return/volatility ratio of 0.82 against the default's 0.57 — a world
that earns far more per unit of risk than the one being modelled. **Do not read that world's passing
fidelity verdict as a fix.** The realism gate does not currently notice, because its only drift
constraint is `no runaway drift` (|annRet| < 30%); a two-sided band on return-per-unit-volatility is
the obvious follow-up, and is not in this plan.

**Fixed alongside**: with no admissible world, `-strategies` threw `empty.min` in Scala and printed
an all-zeros rank table in Rust — a divergence, and in Rust a table that reads as "every rule tied",
which is a claim. Both now say so and skip the ranks. Reachable before W9 (`-strategies -single
-crowd trend200`), unavoidable after it.

---

# Sequencing

| order | items | why here |
|---|---|---|
| ~~1~~ | ~~W0~~ | Done: canonical copy recorded (since superseded — the script is now a launcher into the jar's `MarketSim`) |
| ~~2~~ | ~~W1 + W2~~ | Done: a consumer can now reproduce internal numbers exactly |
| ~~3~~ | ~~W3~~ | Done: worlds that were being discarded are admissible under `-gate realism` |
| ~~4~~ | ~~W9~~ | Done: the depth profile is measured, targeted and gated |
| ~~4b~~ | ~~W6a~~ | Done: the reflexive channel has a dial and a binding diagnostic |
| ~~5~~ | ~~W6b~~ | Done: reflexive worlds are in the `-strategies` sweep, ranked in their own panel |
| 6 | W5 | Both consumers' actual question: is this contrast resolvable from the history I possess |
| 7 | W4 | Make one consumer's rules native |
| 8 | W8 | Extends what the model is allowed to claim about horizons |
| 9 | W7 | Extends the model to an asset class only one consumer holds |

W9 moved ahead of W5 and W8 because it was the only open item with a *measured* failure rather than
an anticipated one, and it mis-states the level of every peak-relative quantity rather than biasing
a choice between windows. W7 moved last because the consumer that needs it is the one already
served, and the other holds no bond ticker at all.

**What W9 landing does NOT do is fix the model.** It makes the defect measured, named, gated and
carried in every export. Closing it needs a mechanism that lets the market make new highs as often
as the real one does without giving up the tails, and the two candidates the measurement leaves
standing are the value anchor's pull and the absent slow valuation cycle — the scope exclusion this
plan declines to reopen. Until then, `-gate realism,fidelity` returns no worlds, and that is the
correct answer to "can I read a level off a peak-relative quantity here".

W7, W8 and W9 are independent of each other and of everything above, so any of them can start early
if someone wants the hard problem first; W9 is the one with the best ratio of unblocked conclusions
to effort. W4's acceptance test is much stronger once W1 has landed, because the external harness
can then be compared digit-for-digit rather than approximately.

# Not in this plan, and a second consumer needs them

Three gaps sit outside the ten items and stop most of one consumer's book from being evaluated here
at all. They are recorded rather than scheduled.

- **There is no cross-section.** market_sim has exactly two assets, so cross-sectional dispersion,
  rank stability and any correlation-regime component cannot be evaluated on an emitted path at all
  — and 45 of the consumer's sleeves rank a basket of individual names. The minimal fix is N equity
  names sharing the market factor plus idiosyncratic fundamental and liquidity, so correlation rises
  endogenously in crashes. `Market` is already generic and `simulate` already runs two instances, so
  this is nearer W4 in cost than W7, and it unlocks that consumer's largest strategy class. **This
  is its W7.**
- **Bars are close-only.** Rules that read dollar turnover or intra-bar range have nothing to read.
  The model is well placed to fix this honestly rather than by a fudge: `stressIdx`/`liq` is a
  natural volume driver, and the within-step flow-versus-noise split is a natural intra-bar range
  driver, so H/L would come from the mechanism. Scaling a whole bar by one factor leaves `ln(H/L)`
  invariant, so a range-reading detector silently passes any robustness test built without it.
- **Rebalance cadence is untested downstream.** Not a change here — a claim to carry over. W4's
  finding that cadence and lookback interact so strongly that neither is interpretable alone (worth
  ~0 at a 364-day window, +9 pp/yr at a 60-day one) is a direct warning to any consumer that holds
  its rebalance cadence fixed across every sweep.

# How to know the upgrade worked

The falsifiable end state: **re-run the sleeve-gate study natively** — `-strategies` with return-sign
rules at quarterly cadence on a composition bond calibrated to a 4–6% volatility, in a world whose
autocorrelation term structure is pinned — and see whether the lookback conclusion survives. Three
outcomes, all informative:

- The monotone curve persists at aggregate-bond volatility → the consumer has an actionable finding
  it currently cannot act on.
- The curve flattens → the current configuration is confirmed optimal-by-indifference, and a
  standing question closes.
- The curve inverts → the original finding was a volatility artifact, and the model has just earned
  its keep by catching one.

The second end state, for W9 alone, is now a gate check rather than a manual comparison: the
drawdown-gate arm's `%out` **is** the `equity >10% below peak` rung, and the band is "within ten
points of the real posture".

**This condition flipped in 0.19.1 and the blocker it described is gone on the equity leg.** It read
FAIL at 0.454 against a real 0.315; on the 0.19.1 default it reads **PASS at 0.335**, and all three
equity rungs are in band. What remains is the bond rung, at 0.87 against 0.510 — so the restriction
narrows from "cannot referee trend-versus-de-risking-speed" to "cannot referee it **on the bond
side**". A consumer holding no bond ticker is no longer blocked here at all.

**Scope the claim carefully, because the equity rungs are fit targets.** Agreement with SPY
1993-2026 is a calibration check, not validation. Against CRSP value-weighted — a series the
calibration never saw for this statistic — 33-year windows inside 1954-2026 give a real median of
0.451 / 0.291 / 0.151, ranges 0.405-0.507 / 0.219-0.346 / 0.084-0.184, against the model's
0.49 / 0.33 / 0.13. **All three rungs land inside the range of real 33-year windows**, the shallow
ones ~10% above the median and the deep one ~14% below. The honest statement is therefore
**gradeable with a known level bias of about 10% in each direction**, not "gradeable" flat. It is a
level claim, not a rank one, so it has to travel with any rule scored on a distance-from-peak
threshold.

# What not to do

- Do not widen the bond-volatility band to admit calm bonds. That converts a realism check into a
  formality. W7 is the honest version of the same wish.
- Do not add knobs whose only justification is that a conclusion comes out differently.
- Do not treat "more paths" or "more worlds" as an upgrade. The sleeve study's binding constraint was
  never simulation budget; it was that the admissible worlds did not contain the consumer's assets.
- Do not reopen the recorded scope exclusions (daily kurtosis, crash arrival spacing). They trace to
  an absent slow valuation cycle, they are disclosed in every fidelity report, and the conclusions
  that depend on them are already ruled out by the header.

# Choosing your own world

`marketSim` ships one default world. It is a starting point, not a recommendation, and for most
questions you should not be using a single world at all.

The model's own doctrine, from its header: defensible outputs are **"ruin rates, cost breakevens,
fragility, refuge mechanics, crash-type conditioning — as CURVES over world parameters, never
points."** A conclusion that holds only at the default is a conclusion about the default.

This page is for the consumer who has read that and wants to know which dials exist, what each one
actually moves, and when the choice matters.

## The short version

Every world parameter is a CLI flag. There is no separate configuration mechanism:

```
market_sim.exe -validate -stress 6.1
market_sim.exe -strategies -depth 14 -drift 0.13
```

`-emit` writes a sidecar naming every field of the world that produced the path, so a study cannot
lose track of which world it used:

```
"version": "<the release that wrote this file>",
"world": { "trendShare": 0.055000, "depth": 17.400000, "stress": 5.370000, ... }
```

The `version` beside it says which release wrote the file, because the defaults themselves move
between releases. `market_sim.exe -version` answers the same question about a binary before it is
run.

The full flag list with each default is the Scala twin's usage text: `marketSim -badflag` prints
it (any unrecognized flag does). The Rust binary deliberately carries no usage text — it prints
`unrecognized arg` and exits 2 — and its dial defaults are all recorded in any sidecar's `world`
block.

## Verifying what produced your data

Two checks, with different jobs and different lifetimes. Use both.

**Before invoking — `-version`.** Prints the release the binary was built from, alone on stdout,
exit 0. Nothing to parse, so a caller can assert on it without depending on any other output:

```
[ "$(market_sim.exe -version)" = "0.23.0" ] || { echo "wrong simulator" >&2; exit 1; }
```

This catches the wrong binary, and it is the only check available *before* you spend the run. It
stops working the moment you are handed a file instead of a command.

**After the fact — the sidecar.** `-emit F` writes a sidecar beside the TSV — `F` with its
extension replaced by `.json`, so `paths.tsv` → `paths.json` (a bare name gets `.json` appended) —
and that is the only provenance that survives the file being moved. Four fields answer four
different questions:

| field | question |
|---|---|
| `schema` | can I parse this file? |
| `version` | which release's simulator wrote it? |
| `world` | with which parameters? |
| `gate` | was that world even admissible? |

Each `gate.fidelity` row carries `miss`, true when the ratio falls outside 0.667-1.5 **or cannot be
computed** — a `null` model value reads `miss: true`, not `false`. A path holding a non-finite price
is refused outright: `-emit` exits 2 and writes nothing, where every other gate verdict only warns.

**`schema` and `version` do not substitute for each other.** The default world moved at 0.19.1 and
again at 0.19.2, so two files with identical columns and identical `schema` can still be
incomparable. `schema` went 1 → 2 when `version` was added, which makes the absence of `version`
detectable rather than ambiguous — **schema-1 files exist**, so read `schema` first and treat a
missing `version` as "schema 1", not as a malformed file. `schema` went 2 → 3 at 0.21.0, when the
`world` block gained `jumpVar` and `jumpRate`: a reader that reconstructs a `World` from a schema-3
sidecar using schema-2 field names gets one with no tail channel and no error. It went 5 → 6 at
0.22.1, when each `fidelity` row gained `aggregation` and `horizonYears`, `ratio` became nullable
beside a new `percentile` (see [Reading an extreme](#reading-an-extreme)), and the `world` block
gained the five `disaster*` dials — the same no-tail-channel trap as schema 3, one level deeper.
It went 6 → 10 at 0.23.0 (7 through 9 were never in a release). The `world` block gained the four
valuation-cycle dials, the six asymmetry dials (`leverage`, `downShock`, `jumpSkew`, `newsRate`,
`newsSize`, `refugeDays`), the satellite leg's `satBeta`/`satIdio` and the bar channels'
`rangeScale`/`rangeDown`/`volIdio`. `gate` gained `ensembleYears` (the verdict horizon), `anchors`
(which ruler graded this world — a `-anchors nasdaq` run was otherwise indistinguishable from an
S&P one in its own provenance record), and `gradedSeries`, which lists every series the verdict was
computed from — `price` and `bond`, plus `logSat`, `logHigh`/`logLow` and `logVolume` exactly when
their channel ran — beside `ungradedChannelSeries`. A top-level `channels` block carries the
readings the channel rows grade, led by the world level the channels were sampled at, so a channel
FAIL can be sized from the file alone — `fidelityFailed` names a band, not a value. The TSV gained
columns that exist only when their channel is on: `logSat` (`satBeta > 0`), `logHigh`/`logLow`
(`rangeScale > 0` — the sampled intra-bar extremes; the bar's open is the prior close, the model
has no overnight) and `logVolume` (`volIdio > 0` — a mean-free log turnover index; apply your own
detrend convention as you would to a real series), and since schema 11 `logTraded` and `divYield`
(`divYield > 0` — the traded price as a log, the total-return `price` deflated by the yield accrued
each session, beside the session yield in %/yr; `price` keeps its meaning, so a consumer runs its
decisions on the traded series and its accounting on `price`; `channels.level.kDiv` is the world's
mean fundamental/price the yield was normalized by). Every log column carries a NATURAL
LOG rather than a
level, because a level near 10^6 rendered at six decimals sits within reach of a cross-language
rounding tie the twins' byte-parity checks would trip on. A channels-off schema-10 file differs
from its schema-6 counterpart in the schema number, the new zero-valued world fields and the new
`gate` fields, so a schema-6 reader that ignores unknown columns and fields keeps working.

**Every emitted channel is graded, and the verdict names its own scope.** `ungradedChannelSeries`
is **empty in every world the model can currently emit**: the satellite leg is graded by the
`satellite *` rows and the sampled bars by the `bar *` rows, so a `"fidelity": "PASS"` beside a
`logSat` column is a verdict *about* that column too. It stays in the schema because the next
channel to arrive is ungraded until someone anchors it, and that has to be said beside the data
rather than in a document nobody opens. A channel that is bit-identical off cannot perturb the
graded price series, which is what makes it safe to add — and is exactly why a verdict computed
from the primary leg alone could not see it. Disclosure would be the cheap answer; grading is the
right one.

**A version check alone does not pin behaviour.** A run made with `-depth 0.5` or a non-default
`-crowd` looks like a default run to any version check. A consumer that calibrated against the
default world should compare the sidecar's `world` block field-by-field against what it calibrated
on; that catches both a moved default and a deliberate flag, which no label can. And a file whose
`gate` records a failure is not evidence about a market — read the verdicts rather than assuming
them, remembering they come from an `-emitgate` ensemble, not from the single emitted sample. That
ensemble is graded at the calibration horizon — `gate.ensembleYears` reads 100 whatever `-years`
the emitted paths carry, because several graded statistics grow with the measurement window and
the bands were set from century records — so a short export is not judged against bands its
horizon could never satisfy. `-emitgate 0` opts out and grades the emitted ensemble itself,
caller's horizon and all.

**Do not let `PATH` choose the binary for a pinned consumer.** `~/.cargo/bin/market_sim.exe` is
whichever version was installed last, and on Windows a running executable cannot be replaced in
place, so a failed reinstall silently leaves the previous one there. Install to a versioned root and
invoke the absolute path, so the path itself carries the assertion:

```
cargo install vastblue-uni@0.23.0 --example market_sim --root ~/.local/uni-0.23.0
~/.local/uni-0.23.0/bin/market_sim.exe -version
```

Exe-versus-library mismatch is not a risk — the example links the library from the same crate. The
only mismatch is the exe against what the consumer expected.

**A pinned world without a pinned binary — `-atrelease`.** A consumer calibrated against a past
release's world does not have to hold the old binary to keep it: `-atrelease 0.22.0` seeds every
dial from that release's frozen world (any `-releases` row, or the current version), so binary
fixes arrive without a recalibration. Explicit dial flags override the base wherever they appear
on the command line. A **recipe** resolves the same way and also selects its anchor set:
`-atrelease 0.23.0-nasdaq` is the channel-emitting Nasdaq world of the recipes section, at the
anchored channel dials and graded against the QQQ anchors, so a consumer names it without carrying
nine flags; an explicit `-anchors` still overrides. Recipes are built on the frozen release row,
never on the current default, and they are not `-releases` rows, since that table grades every
world against one anchor set. Two things `-atrelease` deliberately does not do: paths reproduce *statistically*, not
bit-for-bit, across 0.23.0 (`expDet` moved the twins off the native tanh); and the gate still
grades with the *current* rulers, so a world predating a mechanism fails that mechanism's rows
honestly — pair with `-gate realism` to require only what such a world claims, and read the rest
as disclosure. The sidecar's `world` block records the frozen dials and `version` records the
binary that wrote the file, which together are the whole provenance:

```
market_sim.exe -atrelease 0.22.0 -gate realism -paths 2000 -years 33 -emitall -emit rung.tsv
```

## Generating an ensemble

`-emitall` writes every path of the run from one invocation, to `F-000.tsv`, `F-001.tsv`, … each
with its sidecar. Prefer it to a per-path invocation loop: it pays the process start, the report
ensemble and the verdict ensembles once rather than per path — ~45 ms marginal per path at 33
years against ~1.3 s for a whole single-path invocation, so a 40-path batch runs in under 3 s.
The marginal cost is formatting a path's eight columns, which no batching removes.

```
market_sim.exe -paths 2000 -years 33 -emitall -emit rung.tsv     # rung-0000.tsv .. rung-1999.tsv
```

The index is zero-padded to the width of the highest index in the batch, floored at three — so a
2000-path batch pads to four and a 200-path batch reads exactly as it always has.

To size a job: a consumer generating 16,000 paths this way, across parallel invocations, measured
12 minutes end to end — about 45 ms per path, which is the formatting cost and not something more
batching removes.

`-emitfrom K` writes indices `K..K+paths-1`, so a batch can be split across invocations for a
resume or across machines. A chunk is byte-identical to the same indices of the whole run, sidecar
included, because path k depends only on (world, years, seed, k):

```
market_sim.exe -paths 500 -years 33 -emitall -emitfrom 500 -emit rung.tsv   # rung-500 .. rung-999
```

Two things to know when chunking. The report and the gate are always measured on `0..paths`, so
each chunk's sidecar carries the same verdict — except under `-emitgate 0`, which asks for the run's
own sample to be the judge and therefore gives each chunk its own, smaller, noisier verdict,
measured at the caller's `-years` rather than the calibration horizon. And the
padding follows the highest index of that invocation, so chunks either side of 1000 differ in width:
sort numerically, or emit the batch in one invocation.

## When the choice matters, and when it does not

| your claim | example | does the world matter? |
|---|---|---|
| **rank** | "the 200-day trend rule beats the volatility rule" | barely — check it survives the sweep |
| **level** | "this rule is out of the market 32% of the time" | **yes** — the world sets the level |

For rank claims, run `-strategies` and read the rank-stability table: it already varies nineteen
parameters, including `stress` at 0, 5.1 and 7.65. A ranking that survives that range does not need
anyone to agree on the default.

### What the sweep does and does not cover

The sweep is **one knob at a time from the default**. "Survives stress 0 to 8.1" therefore means
"survives that range with everything else held at its default value" — weak-form robustness, not a
joint search. Two knobs moving together can still break a ranking that each one alone leaves intact.

It also separates two kinds of world, and the separation is load-bearing:

- **character worlds** (19) vary what the market is *like* — volatility, depth, drift, rates, the
  spiral. These produce the `RANK STABILITY` table.
- **reflexive worlds** (2) vary *who is trading*, by handing the crowd a rule to run so its
  de-risking moves the price it reacts to. These produce a separate `REFLEXIVITY` panel and are
  never pooled with the character ranks.

That split exists because a reflexive world can invert a ranking outright rather than perturb it —
the crowd running a volatility rule turns every trend rule's edge negative. Averaging the two sets
would hide exactly the case worth finding, so the panel reports reflexive ranks beside the character
range and flags any rule that moves outside it:

```
  ranked by median net return   (1 = best)   reflexive: crowd runs a vol rule | reflexive: crowd pressed hard
  always fully invested               1  8   character 6-9   <-- MOVES OUTSIDE THE CHARACTER RANGE
  trend 150d, floor 0%                9  1   character 1-1   <-- MOVES OUTSIDE THE CHARACTER RANGE
  trend 200d, floor 40%               4  5   character 4-5
  trend 200d, floor 0%                7  2   character 2-2   <-- MOVES OUTSIDE THE CHARACTER RANGE
```

Read that: `trend 150d` is the **best** rule of nine when the crowd is a pressed momentum crowd and
the **ninth** when the crowd runs a volatility rule, against a character range of 1-1. Buy-and-hold
does the reverse. Neither ordering is wrong; the ranking is simply a statement about who else is
trading, and no amount of character-world sweeping would have revealed it.

### When a rank does not survive

A ranking that holds across the sweep is the reassuring case, and the one people plan for. The
interesting case is the other one, and it is **a finding, not a failure**.

If rule A beats rule B at `stress 5.4` and loses at `8.1`, the honest output is not a verdict about
A and B. It is: *this comparison is a bet on liquidity-spiral strength* — name the parameter, give
the value where the ranking flips, and let the reader decide whether they believe their market sits
on one side of it. The same applies to a flip between the character ranks and the reflexivity panel,
where the bet is on how reflexive the market is rather than how deep.

Two things not to do with an inversion. Do not pick the world where your preferred rule wins and
report that as the result. Do not average across worlds until the flip disappears — an average over
a set containing an inversion is a number with no referent.

For level claims — time out of market, absolute volatility thresholds, ruin rates, drawdown-
conditioned hazards — the world is the answer, and you should also require the fidelity gate:

```
market_sim.exe -validate -gate fidelity
```

A non-zero exit means at least one quantity's *level* cannot be read in that world, and the
`verdict:` line names which.

## Does the mechanism generalise? — `-crossasset`

The fidelity gate answers "can this quantity's level be read *in this world*." It does not answer
"does this mechanism hold away from the fund it was calibrated on" — and most targets cannot answer
it, because they are levels measured from one series.

Two can, because their bands were fitted across five real Treasury funds rather than one:
`bond vol x duration` and `bond depth vs vol`. `-crossasset` runs both across the duration ladder
with every mechanism parameter frozen:

```
market_sim.exe -crossasset
```

Only `duration` moves, and only to values real funds have — 0 parameters fitted, 1 measured. That
accounting is the point: a relation that survives while nothing is being tuned to make it survive
is evidence; the same relation after a re-fit is not.

Three cell states, and they mean different things:

| cell | meaning |
|---|---|
| a number | graded against the band |
| `EXTRAP` | the rung is past the range the band was fitted across — disclosed, excluded from the verdict |
| `n/a` | the relation has no value at that rung, *inside* its support — a property of the relation |
| `0.65~` | `EDGE`: within one estimated sampling sd of a band edge, either side — not resolvable at this ensemble size |

Exit is non-zero when an in-support cell misses its band beyond its noise, when a relation graded
zero cells (`INCONCLUSIVE` — a test that never ran did not pass), or when any cell is `EDGE`. The
per-cell sd is estimated in-run from quarter-ensemble spread; a hard PASS/FAIL within it would be
a seed draw wearing a verdict's clothes.

**Cleared in 0.21.0, and what it was hiding is worth knowing.** `bond depth vs vol` at d=5.70 sat on
its 0.65 floor from 0.19.2, reporting `EDGE` at the default seed — but across five seeds it read
0.62–0.66 and reported **`FAIL` on two of them**. The standing EDGE was the favourable draw.

It was also not resolvable by ensemble size, which is what an `EDGE` verdict invites you to try. At
400, 800, 1600 and 3200 paths the cell converges to 0.64–0.65 while its sampling spread falls from
0.04 to 0.01: the value sits *on* the floor, so more paths sharpen the failure rather than clearing
it. **If a cell reads EDGE, check where the point estimate converges before spending an afternoon on
paths.**

The fix was `easing` 0.046 → 0.052, which is an anchor correction rather than a tune: `usage`
asserts the value IS one full real easing cycle, and real cycles run 5.1 to 6.8 rate points
(2007-08, 2001-03, 1989-92) where 0.046 is 4.6.

**And the window moves with the EQUITY world, which is the part to carry forward.** 0.22.0 moved
the equity side four times and the ladder followed every time — the price-impact change, the
recalibration, the jump channel and the anchor corrections each shifted it, in both directions;
0.23.0's settled-stress refuge moved it again, downward this time (0.060, clear at that world,
now fails the shipped duration's own depth band at 1.37). The equity crowd reaches the bond leg
through the correlation channel, so a bond dial settled once is not settled. The shipped 0.052 —
5.2 rate points, the bottom of the three real cycles — reads d=5.70 at 0.70 and d=13.50 at 1.30,
verdict PASS. **Re-run `-crossasset` after any world change, not only after a bond one.**

**The calibration loss cannot see this rung.** `fitness` scores a single `WorldStats` and the ladder
re-simulates at other durations, so a re-search optimises the bond depth relation at the *shipped*
duration — where it reads 1.30, in band — and is free to spend every other rung. The
0.21.0 search duly proposed an `inflSize` cut that pushed d=5.70 clear of the line, the same cut
0.20.0's search proposed and that was reverted then for the same reason. **Run `-crossasset` after
any recalibration — and after any world change at all, equity-side included; the loss will
not warn you.**

**The ladder is rotated, not shifted, and that is now measured rather than asserted.** Every dial
that lifts d=5.70 also pushes d=13.50 toward its 1.35 ceiling. At the 0.21.0 world the admissible
`easing` window was roughly 0.050–0.056: 0.046 put the short rung on its floor, 0.058 put the long
rung on its ceiling. At 0.22.0's world it sat near 0.058–0.064; at 0.23.0's it is roughly 0.048–0.056
(0.045 converges the short rung below its floor at 400 paths, 0.060 fails the long rung's own
band), and it is narrow enough that no single value clears every seed with margin — the two
rungs' seed spreads are each about ±0.05 and they move in opposite directions. Re-derive it
rather than assuming it, and expect one seed to sit on an edge.

The acceptance gate applies the same refusal: `-validate` prints an anchor-fitted band it cannot
grade as `n/a` with the reason (and the sidecar records it under `gate.fidelityUnanchored`),
instead of failing a world for sitting where the anchor funds have no data.

Cost: the full report runs ~18 ensembles at the invocation's `-paths`/`-years` — four ladder rungs
plus a bisection solve for the equity section — so scale `-paths` down for a quick look.

## How a decline is delivered — `-ddshape`

Depth is not shape. `-ddshape` reports median decline duration, recovery duration, time underwater
and **worst-day share** — how much of a peak-to-trough decline arrives in its single worst session —
at the 10% and 20% thresholds, against the anchor set's own references: the CRSP century, its three
33-year windows and SPY for the S&P set; the Nasdaq-100 index from 1990 and QQQ for the Nasdaq set
(`ddshape-2026-09-02.tsv`). The ratio reads against the longest history, and the min/max rows show
how far the references disagree with each other — at 10% the century's windows put the median
decline anywhere from 65 to 135 sessions and the worst-day share from 14% to 29%, which is wider
than most model/real ratios in the table.

It uses a **second episode definition on purpose**: the model's own crash count is a 15%-below-peak
excursion that re-arms at 2%, built for counting; these are peak-to-trough-to-full-recovery
episodes, built for shape. Do not mix the two.

Nothing in it is gated. Even the century holds only 16 episodes at 20%, SPY four, and a band off
four episodes could not fail. Every median, real and model, is the twins' own `pctile(.., 0.5)`:
the upper of the two middle elements of an ascending sort, where NumPy averages them — at four
episodes the two conventions differ by up to a third of a statistic, so reproduce the reference
rows with the model's median or expect to land one element away.

At the shipped world against the century (200 paths × 100 years): 10% declines are 0.79× as long,
recover in 0.94× the time and deliver 1.6× as much of themselves in their worst session; 20%
declines are 0.51× as long and recover in 0.83× the time. Against SPY alone the 10% row reads
0.97× on duration and 0.85× on worst-day share. The miss that survives every reference is the
**deep decline's duration**: a 20% decline takes half as long as any real one in the table. On the
`0.23.0-nasdaq` recipe the shape runs the other way — 10% declines 1.26× and recoveries 1.43× the
Nasdaq-100's, 20% recoveries 1.67× — so a rule keyed on time underwater reads a world that stays
down too long at Nasdaq volatility and gets up too fast at the S&P's.

## The worst session — `-haltlimit`

The largest one-session decline the market will print, as a simple fraction, with the pressure it
could not fill **deferred to the next session**. Default 0.25.

Before this existed, the worst session a world could produce was set by the `+/-0.50` numerical
guard on log returns — `exp(-0.50) - 1 = -39.35%` — and the worst sessions piled against that wall
instead of tailing off. If you read worst-case behaviour off an emitted path, you were reading the
guard.

A halt is not a wider guard. A guard truncates and throws away the remainder; a halt defers it, so a
decline that cannot be filled in one session arrives as a **multi-session cascade**. That is what a
real market does — US market-wide breakers close the day at −20%. It is decline-only, because that
is the real asymmetry.

**What to set it to.** Leave it at 0.25 unless you have a reason. It admits the worst session in the
record — the S&P's −20.5% on 1987-10-19, a day that pre-dates the breaker system that would have
stopped it — and these worlds span a century, most of it without any market-wide breaker.

- `-haltlimit 0.20` — the strict post-1988 breaker world. Costs kurtosis: 0.98 → 0.86, because at
  that level the halt starts removing the sessions the kurtosis anchor is made of.
- `-haltlimit 0` — no halt, the pre-0.21.0 behaviour bit for bit. It fails `clamp shapes no tail`,
  which is the point: at the default world the guard bound on 10.9% of deep-tail sessions and
  produced every one of the ten worst.

**If you are reading tails, read the two clamp diagnostics together.** `clamped X% of all sessions`
says the guard is not distorting the body; `Y% of tail sessions` says it is not authoring the tail.
The first passed at 0.000% in a world where the second read 10.9%.

## The satellite leg is a coupled second leg, not a calibrated Nasdaq

`-satbeta`/`-satidio` are anchored on the *coupling* — correlation, beta, volatility ratio, and
the state-flatness that makes them hold in stress. Those four rows are graded against
`joint-coupling-2026-08-31.tsv`. **Its marginals are graded too — as RATIOS to the primary leg**,
not as levels: kurtosis, both clustering lags, the 5% and 10% depth shares, and the crash rate,
each against what QQQ holds to SPY over their shared window. That is the same doctrine the depth
rungs use when they grade each world at its own volatility, and it is the honest one here, because
the satellite is a second leg at *this* world's scale and is not claimed to be any index. Measured
against QQQ's absolute levels it would miss on kurtosis in both directions, which is why the level
comparison is the wrong question to ask of it. Use the satellite when you need a *second correlated
equity leg*; use the `-anchors nasdaq` recipe when you need a world graded against Nasdaq levels.

One row carries a stated tension rather than a clean pass: the model's leg opens about 1.6 crash
episodes per primary episode against the record's 1.17. One history cannot resolve that ratio —
SPY and QQQ show roughly six and seven episodes in 27 years, and five-year blocks read 1.00 to
2.00 — so the band is wide enough to admit the model, and the central tendency is disclosed here
rather than hidden inside it.

**The dials hold across worlds.** `-satidio` is a fraction of the primary's own realized
volatility and `-rangescale` a multiple of the session's vol state re-levelled onto it — the level
is solved once per WORLD from a fixed ensemble, never read off the path being emitted, because a
path's own whole-path variance leaks its future into every window (years 1–10 of a bar series
predicted the log volatility of years 11–100 at +0.81, against +0.06 in a control whose level was
computed causally, session by session) — so the same values read the same coupling at any
admissible world: on the `-anchors nasdaq` recipe the leg measures correlation 0.85 and volatility
ratio 1.41, as on the default world. Whether a
second leg at 1.4x a 25%-volatility primary is the pair you want is a modelling choice, not a
calibration one; if you want two indices at index-like volatility, run the satellite on the
default world.

## The slow valuation cycle — `-beliefshare` / `-capyears`

Value capital in this model arbs the gap to what it *believes* fair value is, and beliefs move.
Two draw-free channels, adopted 0.23.0, both bit-identical off at 0:

- **`-beliefshare`** (0.95): perceived fair drifts toward realized prices with a `-beliefyears`
  half-life (1.5). It splits reversion by *frequency* — daily pull untouched (beliefs barely move
  in 60 sessions), multi-year reversion weakened to `1 − share` of the pull — which is where
  CAPE-scale valuation swings live, and why no ordinary dial could buy dispersion: every
  continuous channel paid the 60-day variance-ratio band first (the full sweep bought +0.01 of sd
  for the whole vr60 budget). SHORTER half-life and HIGHER share are the amplitude directions —
  the naive long-memory intuition inverts, measured: 12 years reads dispersion 0.115 where 1.5
  reads 0.26 at share 0.9 — and share is the cheap currency: the half-life is what pays the depth
  rungs (0.5 years fails d10 outright).
- **`-capyears`** (1.5): the mania half — beliefs capitalize the fundamental's recent excess
  growth (read through a `-capwindow` 6-year EWMA, bounded by a frozen 0.80-log tanh span), so a
  growth regime carries perceived fair above the true fundamental and a regime ending on its
  re-draw is a valuation decline with the fundamental fine.

What it is graded on: the `valuation dispersion` fidelity band (0.15–0.55, from the Shiller CAPE
proxy in `valuation-2026-08-30.tsv` — a *band*, never a point ratio, because the record has no
observable fair value) and the `valuation cycle engages, not unmoored` mechanism row. The shipped
world reads sd log(p/fair) 0.33 — inside the proxy windows' own 0.24–0.41 — with a per-path cycle
of half-life 7.9 years and both wings near 23% of months past ±0.25 log, against the record's
11.5 years and ~27.5% (log CAPE about its mean, 1881–2023): roughly half the record's cycle on
every axis, from a fifth of it before the retune. Two shapes remain disclosed: the ensemble
century max stays near +11% over FAIR (the ensemble's mean gap sits below fair; the cycle is
two-winged about its own mean, like the record's), and the model's cycle is more *regular* than
the record's (5-year autocorrelation 0.84 against 0.55 — out of reach from above at every
setting). A collapsing fundamental still transmits at full strength — beliefs lag it by years —
which is why the disaster channel's tail *deepened* under the cycle.

`-beliefshare` must stay below 1: at 1 the pull chases its own shadow and nothing anchors the
price level (the CLI refuses it; the mechanism row's 0.70 ceiling is the same guard one level up).

## How tight are the anchors? — `-noise`

Every fidelity target is a point read from one historical record. `-noise` reports, per target, the
spread of readings independent model histories of that anchor's *own* length would produce, where
the real record falls in that spread (`real@` — near 50% means the record is a typical history of
this model; near 0/100% means it is not), and the seed-to-seed noise of the scoring ensemble.

Use it for two decisions. Whether a fidelity miss is a defect or a draw: a ratio outside the band
but with `real@` mid-distribution says the *anchor* cannot distinguish the two. And whether a
change between two runs is real: a ratio difference below the seed-noise section's `2 sd` for that
target is a seed draw, not a change.

The spreads are model-implied — the record is one draw, so there is no other estimate — and the
report states this. Ignores `-years`: the horizons come from the anchors themselves.

### Reading an extreme

Most fidelity rows compare a per-path central value against a point read from the record, and their
ratio means something. **`worst crash %` does not.** The model's number is the deepest episode in
the *whole ensemble* — ~4,400 episodes at the default 200 x 100 — while the anchor is the deepest of
roughly 20 in one history. That quotient grades the sample size, and it never converges:

```
   paths   market-years   worst crash %   record@
      20           2000         -92.47       30%
     200          20000         -99.52       35%
     400          40000         -99.52       37%
     800          80000         -99.52       40%
```

Same world, same seed, every dial fixed: the level falls 7 points and then saturates only because
the price itself runs out of room near −99%, while the published reading moves within its own
sampling noise (±4 points at n=200). At the pre-disaster 0.22.0 world, where nothing capped it, the
level ran −80 → −94 over the same range and its old ratio's MISS verdict flipped between 20 paths
and 200. So `-validate` and the `-emit` sidecar report these rows as the record's **percentile
among single histories of the anchor's own length**, and carry no ratio at all:

```
 worst crash %   model   -99.52   real   -84.10   record@  35% of 100y histories (n=200)
```

Read it as `-noise`'s `real@`, because it is the same measurement: near 50% the record is a typical
history of this model, near 0 or 100 it is not. It needs at least 20 histories to place a record at
all — below that the row reports `n/a` and a MISS, since one history reads 0% or 100% and neither is
a measurement.

In the sidecar such a row carries `"aggregation": "ensemble-extreme"`, `"ratio": null` and a
`"percentile"`, so a consumer cannot make the division by accident. `"horizonYears"` is on every row.

**An extreme also needs its own window**, and that is a separate decision from its own horizon. The
deepest episode is the one statistic a window can delete: across the committed fixture, median depth
swings 11% between windows and the crash rate 30%, while the worst swings **54%** — −84.1% over the
century against −54.6% from 1954, because 1954 opens after the decline that set it. `Anchors` carries
`tailWindow` / `tailYears` separately from the equity window for exactly this reason. If you add an
extreme target, ask not only *is this anchor the same statistic?* but *could a longer window of the
same record produce a more extreme value?*

`-releases` and `-crossasset` keep the ratio for these rows because every column there shares one
ensemble size, so the *movement* between worlds is real even though no column's level is; both
reports mark the row and say so. **`-fitness` and `-calibrate` score them by the median of the
single-history readings** at the anchor's own horizon — the converging centre of the distribution
the percentile is read from, in the standard loss form — never by the pooled minimum, which is
centred on an arbitrary ensemble size. Each scoring evaluation pays one extra single-history
ensemble per extreme anchor (60 histories at the frozen configuration; `-calibrate` roughly doubles
per candidate). The `-fitness` model column for these rows is that median, and the report says so.

Read the result as a consistency check, not a falsification test. The bands came from Treasury
funds and the ladder walks Treasury durations, so it cannot detect a mechanism that is wrong in a
way every Treasury shares. That needs an asset class the bands did not come from.

### The equity section: ratios at the volatility anchor

`-crossasset` also re-reads every equity target with volatility put **on its anchor**. This matters
because `depth` moves volatility and drawdown *together*: a world sitting below its volatility
anchor grades every drawdown statistic with two errors folded into one. It was this section that
showed the 0.19.2 default's crash excess was really ~1.50 at matched volatility, not the 1.32 it
printed — the diagnosis behind the 0.20.0 recalibration. At the 0.20.0 defaults volatility is on
the anchor, so the two columns nearly coincide and the section mostly guards non-default worlds.

Since 0.21.0 the three depth rungs are graded against a **relation** — what a real equity fund of
this world's own volatility and return spends below its peak, fitted across 35 funds — rather than
against SPY's levels. So solving depth moves their prediction along with their measurement and the
two columns should agree for them; a rung that moves anyway is saying the model and the real
cross-section disagree about how time under water responds to volatility. The absolute targets
(kurtosis, clustering, crash rate, crash depth) are what the section still isolates.

It remains **diagnostic and does not affect the exit code**. The remaining window mismatch is among
the absolute anchors (`equity vol %` from S&P 1954–2026, `return per vol` from CRSP 1954–2026);
this section does not fix that, it removes the model's own volatility miss which was compounding
with it.

## The dials worth your attention

| flag | what it is | default |
|---|---|---|
| `-stress` | liquidity-spiral gain: how much a run of down days amplifies later moves | 5.15 |
| `-depth` | market depth; price impact scales as `12/depth`, so higher = calmer | 17.4 |
| `-drift` | fundamental drift per year; no dividend, so this IS total return | 0.122 |
| `-fundvol` | fundamental volatility per year — sets time under water, not daily return scale | 0.070 |
| `-jumpvar` | share of the equity shock's **variance** carried by jumps rather than diffusion; 0 turns the tail channel off | 0.14 |
| `-jumprate` | jumps per session at average volatility; with `-jumpvar` it sets the **size** | 0.0035 |
| `-jumpskew` | how far each jump is shifted down, in its own sds; variance-normalised, so deeper skew means smaller jumps, not fatter tails | 0.7 |
| `-leverage` | the leverage effect: a decline raises the NEXT session's diffusive volatility by `exp(leverage * decline-in-sds)`, saturated at 4 sds; a rally raises nothing. Reads the same session's news jump | 0.12 |
| `-newsrate` | fair-value news jumps per year — permanent down-jumps the price reprices the same session, gap-invariant; the downside-asymmetry channel, variance-DISPLACING | 1.3 |
| `-newssize` | log decline per news event (0.033 = a −3.3% day); rarer-larger buys more asymmetry and kurtosis per unit of variance. Bounded with `-newsrate`: rate × size² must stay below 0.0123 (size below 0.097 at the default rate) — past it there is no diffusion left to displace, and the CLI refuses the world | 0.033 |
| `-downshock` | transitory sign asymmetry on the equity shock; retired as a default by the news channel — pays vr60 ~+0.02 per 0.01, its recovery IS trend | 0 |
| `-trendshare` | mandate level for trend-following capital (a spring, not a wall) | 0.055 |
| `-crowdimpact` | price pressure per unit of exposure the crowd **trades** in a session — one rule for every crowd | 0.030 |
| `-value` | pull toward fair value per day. With the drag below, this governs **shallow** water only | 0.056 |
| `-recoverydrag` | how fast value arbitrage weakens as a drawdown deepens past 10%; 0 restores 0.20.0's symmetric pull | 8.5 |
| `-recoveryfloor` | weakest that pull may become, as a share of full strength | 0.10 |
| `-disasterrate` | macro disasters per century — rare multi-year collapses of the real fundamental, the channel that carries the century-scale tail; 0 turns it off bit-for-bit | 0.6 |
| `-disastersize` | total log decline of the fundamental per disaster | 2.0 |
| `-disasterlen` | years from onset to trough | 2.5 |
| `-disasterrecover` | share of the decline that reverses after the trough; the rest is permanent | 0.5 |
| `-disasterreclen` | years that recovery is spread over | 4.0 |
| `-beliefshare` | the slow valuation cycle: how far PERCEIVED fair value drifts toward realized prices; 0 pins perception to the fundamental, bit for bit, and must stay below 1 | 0.95 |
| `-beliefyears` | half-life of that belief adaptation — SHORTER is the amplitude direction, and it is the dial that pays the depth rungs | 1.5 |
| `-capyears` | years of the fundamental's recent excess growth beliefs capitalize into perceived fair — the mania half; 0 off | 1.5 |
| `-capwindow` | years of EWMA that growth is read through | 6.0 |
| `-anchors` | which real index the **equity** fidelity targets describe: `sp500` or `nasdaq` | sp500 |
| `-easing` | **cap** on the policy rate cut under equity stress, in rate points — an anchor, re-solved to the BOTTOM of the real easing-cycle range (2007-08 ran 5.1 points): higher fails the shipped duration's depth band, lower drops the `-crossasset` short rung through its floor | 0.052 |
| `-unwind` | how fast that cut is withdrawn, per year (0.35 is a ~2-year half-life) | 0.35 |
| `-refuge` | flight-to-quality bid into the bond, scaled by its duration | 0.115 |
| `-refugedays` | half-life in sessions of the settled stress the refuge bid reads — excludes the current session, which kills the same-day stock-bond coupling while the crisis rally keeps the level; 0 reads live stress | 1 |
| `-satbeta` | the satellite equity leg (the Nasdaq to the default world's S&P): beta on the primary's OBSERVED return, plus idio noise riding the primary's full vol state — which is what keeps the pair's correlation state-flat, as the record's is. When on, `-emit` adds a `logSat` column. Anchored 1.2 on SPY–QQQ 1999–2026; NOT searchable, like `-duration` | 0 (off) |
| `-satidio` | the leg's idiosyncratic vol as a FRACTION of the primary's realized volatility, riding the primary's vol state; the anchored 0.77 lands correlation 0.853 and vol ratio 1.41 on the default world, on the `-anchors nasdaq` recipe, and on a kurtosis-61 world alike | 0 |
| `-rangescale` | intra-bar high/low, sampled per session from the exact Brownian-bridge extremes at the session's own vol state re-levelled onto the world's realized volatility, times this dial — the range scales with the session's noise, not with \|return\|, which is what makes it detectably real (record corr(lnH/L, \|r\|) is only 0.70–0.72). Anchored 0.63 on SPY/QQQ OHLCV, and it reads the same bar-to-ccvol ratio at any world; adds `logHigh`/`logLow` to `-emit`. NOT searchable | 0 (off) |
| `-rangedown` | same-session sign↔vol coupling on the bar: down sessions get (1+X) the bridge sigma, up sessions 1/(1+X). Anchored 0.09, which puts BOTH the range's down/up (1.14 vs 1.109–1.142) and volume's down-up gap (0.097 vs 0.094–0.098) on the intraday rulers. Requires `-rangescale` | 0 |
| `-volidio` | log turnover index riding the range: elasticity 0.59 to the range's deviation from its slow normal (frozen from the measured regression) plus a two-component persistent idio whose total sd is this dial (anchored 0.34). Requires `-rangescale`; adds `logVolume` to `-emit`. NOT searchable | 0 |
| `-divyield` | DIVIDENDS: the world's mean dividend yield, %/yr. The session yield is Y × fundamental/price over the world's mean of it (a world constant solved on the same fixed ensemble as the bar level — the ensemble's mean fundamental/price is 2.06 at the default and 2.30 on the Nasdaq recipe, and a per-path mean would leak the path's future), so a rich session yields less and the ensemble's pooled mean yield is the dial; the reported median path's mean reads about 0.9× of it (2.62 at 2.95, 0.69 at 0.78), valuation epochs skewing the path means; `-emit` gains `logTraded` (the total-return `price` deflated by the accrued yield — `price` itself is unchanged) and `divYield`. Anchored 2.95 on Shiller's S&P 1954–2023 and 0.78 on QQQ 2005–2026 (`dividend-2026-09-02.tsv`); the level is graded when on. An identity parameter, never searched | 0 (off) |
| `-inflsize` | size of an inflation regime's rate-pressure target | 0.10 |

`-easing` is a cap, not a speed. The flag it replaced, `-flight`, was a cut *rate* per year, so it
is rejected rather than reinterpreted: no `-flight` value carries over. Together these two are what
makes the bond a refuge, but they are not load-bearing in the same way, and the difference is worth
knowing before turning either off.

**`-refuge` is necessary.** At `-refuge 0` the bond stops rallying in crashes at all (growth-crash
−0.27x real) and the mechanism check "bonds rally in growth shocks" FAILS.

**`-easing` is not, and the loss cannot see what it buys.** At `-easing 0` the world still passes
realism, mechanism *and* fidelity; the bond depth row is *better* (1.10 against the default's
1.30, where 1.00 is the target) and the loss barely worse (1.02 against 0.95). What easing buys
is the `-crossasset` ladder, which the loss cannot see at all: it is the dial that positions the
short-duration depth rung against its floor, and its admissible window MOVES with the equity
world — at this world 0.060 fails the shipped duration's own depth band, 0.045 converges the
d=5.70 rung below its floor, and the shipped 0.052 passes both ends. If the question is bond
drawdown at the shipped duration specifically, `-easing 0` is the better world and the default is
not.

## Every dial moves several things at once

This is the part to read before changing anything. Columns are **ratios to the real anchor**, so
1.00 is on target. Starred rows are the defaults.

```
setting                       vol  kurt  clus    vr crash   d10  bdep   r/v  tshare  cflow  gate
------------------------------------------------------------------------------------------------
DEFAULT                      1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P

-stress 2.0                  0.83  0.39  0.57  1.16  0.59  2.12  1.04  1.22   0.20   3.22  P/F/F
-stress 5.15 *               1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-stress 8.0                  1.32  1.83  1.50  1.02  1.37  1.04  1.45  0.73   0.20   3.51  F/P/F

-depth 12                    1.32  1.16  1.17  0.98  1.53  0.96  1.30  0.75   0.20   4.01  F/P/F
-depth 17.4 *                1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-depth 22                    0.89  0.72  0.96  1.19  0.74  1.74  1.30  1.12   0.20   3.14  P/F/P

-drift 0.08                  1.03  0.98  1.05  1.10  0.85  1.48  1.30  0.59   0.20   3.53  P/P/F
-drift 0.122 *               1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-drift 0.15                  1.00  0.93  1.03  1.12  0.98  1.39  1.29  1.24   0.20   3.36  P/P/F

-fundvol 0.041               1.00  0.94  1.04  1.02  0.89  1.13  1.29  1.00   0.20   3.47  P/P/P
-fundvol 0.070 *             1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-fundvol 0.10                1.02  0.92  1.04  1.24  1.05  1.74  1.31  0.97   0.20   3.38  P/P/F

-jumpvar 0                   1.02  0.53  1.10  1.09  0.99  1.39  1.30  0.98   0.20   3.54  P/P/P
-jumpvar 0.14 *              1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-jumpvar 0.23                1.00  1.54  1.00  1.13  0.95  1.38  1.31  0.99   0.20   3.34  F/P/P

-jumprate 0.002              1.00  0.97  1.03  1.12  0.99  1.41  1.29  0.99   0.20   3.42  P/P/P
-jumprate 0.0035 *           1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-jumprate 0.012              1.01  0.71  1.04  1.13  0.97  1.41  1.29  0.98   0.20   3.46  P/P/P

-leverage 0                  0.97  0.74  0.82  1.13  0.88  1.50  1.24  1.03   0.20   3.42  P/P/P
-leverage 0.12 *             1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-leverage 0.20               1.06  1.32  1.21  1.10  1.04  1.27  1.34  0.93   0.20   3.43  F/P/P

-newsrate 0                  1.01  1.00  1.14  1.08  0.95  1.21  1.30  0.97   0.20   3.47  P/P/P
-newsrate 1.3 *              1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-newsrate 2.5                1.01  0.87  0.95  1.15  1.00  1.47  1.30  0.98   0.20   3.39  P/P/P

-refugedays 0                1.01  0.93  1.04  1.12  0.97  1.39  1.25  0.98   0.20   3.43  P/P/P
-refugedays 1 *              1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-refugedays 5                1.01  0.93  1.04  1.12  0.97  1.39  1.32  0.98   0.20   3.43  P/P/P

-haltlimit 0                 1.02  0.93  1.03  1.11  0.97  1.37  1.30  0.97   0.20   3.43  F/P/P
-haltlimit 0.25 *            1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-haltlimit 0.40              1.02  0.93  1.03  1.11  0.97  1.37  1.30  0.97   0.20   3.43  F/P/P

-disasterrate 0              1.01  0.91  1.04  1.04  1.04  1.28  1.29  1.04   0.20   3.49  P/F/P
-disasterrate 0.6 *          1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-disasterrate 1.2            1.01  0.96  1.04  1.20  0.90  1.50  1.30  0.91   0.20   3.38  P/P/F

-beliefshare 0               1.01  0.92  1.04  1.17  1.10  1.29  1.30  1.04   0.21   3.51  P/F/F
-beliefshare 0.95 *          1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-beliefshare 0.99            1.01  0.91  1.04  1.12  0.95  1.40  1.30  0.92   0.20   3.40  P/P/P

-beliefyears 0.75            1.01  0.91  1.03  1.10  0.86  1.59  1.29  0.98   0.20   3.40  P/P/F
-beliefyears 1.5 *           1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-beliefyears 5               1.01  0.92  1.04  1.14  1.06  1.32  1.30  1.01   0.20   3.47  P/P/P

-capyears 0                  1.00  0.91  1.03  1.03  0.99  1.12  1.29  1.00   0.20   3.49  P/P/P
-capyears 1.5 *              1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-capyears 3                  1.01  0.93  1.04  1.20  0.94  1.69  1.30  0.97   0.20   3.38  P/P/F

-trendshare 0.02             1.01  0.92  1.03  1.11  0.96  1.40  1.29  0.98   0.18   3.03  P/P/P
-trendshare 0.055 *          1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-trendshare 0.30             1.03  0.95  1.07  1.14  1.05  1.34  1.32  0.95   0.36   6.24  P/P/P

-crowdimpact 0.010           1.00  0.91  1.03  1.09  0.91  1.41  1.28  1.00   0.20   1.15  P/P/P
-crowdimpact 0.030 *         1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-crowdimpact 0.12            1.10  1.00  1.22  1.24  1.34  1.29  1.38  0.88   0.20  13.74  P/P/F

-value 0.020                 1.02  1.03  1.06  1.12  0.89  1.57  1.33  0.90   0.19   3.33  P/P/F
-value 0.056 *               1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-value 0.070                 1.01  0.91  1.03  1.10  0.97  1.36  1.28  0.99   0.20   3.46  P/P/P

-recoverydrag 0              0.99  0.79  0.97  0.94  1.00  1.21  1.22  1.04   0.20   3.53  P/P/P
-recoverydrag 8.5 *          1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-recoverydrag 20             1.01  0.93  1.05  1.13  0.97  1.42  1.30  0.97   0.20   3.42  P/P/P

-recoveryfloor 0.05          1.01  0.92  1.05  1.14  0.92  1.47  1.29  0.96   0.20   3.40  P/P/P
-recoveryfloor 0.10 *        1.01  0.93  1.04  1.12  0.97  1.39  1.29  0.98   0.20   3.43  P/P/P
-recoveryfloor 0.50          1.00  0.87  1.00  1.01  1.04  1.24  1.26  1.03   0.20   3.49  P/P/P
```

200 paths x 100 years, seed 20260813 — the scoring ensemble. `vol` equity volatility · `kurt` daily kurtosis ·
`clus` volatility clustering (lag 1) · `vr` the 60-session variance ratio, where 1.00 is no signed
serial dependence and the envelope is 0.50–1.20 (the ladder's 60 rung) · `crash` crashes per century · `d10` time spent >10% below
peak, against what real equity funds of the same volatility and return spend · `bdep` the bond's
equivalent, against what its own volatility implies · `r/v` return per unit volatility ·
`tshare` **realized** trend-follower share · `cflow` crowd flow, bp/session ·
`gate` realism/mechanism/fidelity.

Eight things that table is trying to tell you:

- **The valuation cycle is a PAIR, and each half alone fails a different gate.** `-beliefshare 0`
  (cap term alone) pushes the variance ratio to 1.17 — re-climbs toward extrapolated fair are
  60-day trends — and the cycle's own mechanism row with it; `-capyears 0` (beliefs alone) fails
  the same two and reads all lower wing. The belief half REFUNDS variance ratio (its
  perceived-fair tracking cuts medium-horizon drift-chasing), which is exactly the budget the cap
  half spends: together at the defaults they read vr 1.12, dispersion 0.33.
- **`disasterrate` is the century-tail dial and almost nothing else.** Off, the world fails the
  mechanism gate (the channel is inert) and the record's century-worst returns to the far tail of
  what the model can produce; at 1.2 the variance ratio reads 1.21 and the fidelity band FAILS,
  because more multi-year declines is more signed persistence. Daily volatility, kurtosis and
  clustering barely move at any setting — rarity is what buys that.

- **`crowdimpact` is a mechanism dial again, not a trend dial.** Since 0.22.0 the crowd's price
  pressure comes from the exposure it TRADES in a session rather than the exposure it holds, so
  running it harder no longer manufactures drift. `-crowdimpact 0.12` is four times the default and
  reaches 14.3 bp/session of crowd flow — 20% of the noise term — while `vr` only reaches 1.28. Under
  the old law the shipped default was already at 1.52 on 4.7 bp. What running it hard DOES buy is
  crash frequency (1.43) and clustering (1.22), which is why the default is not there.
- **`stress` is not a volatility dial.** It is one amplifier producing volatility, fat tails *and*
  volatility clustering together. Raising it 5.15 → 8.0 takes volatility from 1.01 to 1.32 and
  clustering from 1.04 to 1.50 — and fails the realism gate. You cannot buy tails *here* without
  buying clustering.
- **`recoverydrag` and `value` are one pair, and neither reads correctly alone.** The base pull
  governs shallow water; the drag governs deep drawdowns. Turn the drag off and leave the pull at
  its shipped 0.056 and `d10` falls to 1.19 with a mechanism row failing. Weaken the pull instead
  and `d10` runs to 1.54. Both also move `vr` — the drag off reads 0.94, the weak pull 1.14 — so
  a world tuned for time under water on this pair alone lands its serial persistence somewhere it
  did not choose.
- **`jumpvar` and `jumprate` are ONE dial with two handles, and that is what makes the tail
  reachable.** `-jumpvar 0` is this world with the tail channel off and nothing else moved: the jump
  draws come from their own RNG stream, so no other statistic shifts by construction. Turning it on
  moves `kurt` 0.53 → 0.93 at the shipped pair — and the leverage kick now carries a comparable
  share of the tail, so the tail budget is split where 0.22.1's was all jumps. **The ceiling is the realism band, not the target**,
  and the band is what `jumprate` buys headroom against: at the old rate of 0.0010, `-jumpvar 0.12`
  passed at the default seed and read kurtosis 35.2 on another, against a ceiling of 30. Raising the
  rate to 0.0030 means the same jump variance arrives as more, smaller jumps, so no seed runs away —
  the five tried read 19.5 to 27.6 — and the *median* kurtosis rises rather than the extremes.
  Rarer jumps are not fatter tails; they are noisier ones. Set the pair by the band, on more than one
  seed.
- **`fundvol` is the dial for time under water, and it is not free.** Raising `-fundvol` 0.041 →
  0.10 moves `d10` from 1.08 to 1.68 while volatility and return-per-volatility sit still — the
  fundamental accumulates into drawdown depth without reaching daily return scale. It pays in trend:
  `vr` goes 1.04 → 1.27. The shipped 0.070 is where the two meet, and it is the dial that carries the
  drawdown rungs now that the crowd no longer does.
- **The two bond dials are almost orthogonal to the equity leg, but the reverse is not true.**
  `-easing` and `-refuge` move `bdep` and leave volatility, kurtosis and clustering untouched. The
  equity world nonetheless moves the bond ladder through the correlation channel — 0.22.0 had to
  re-solve `easing` twice while the equity side settled. `-crossasset` is the only thing that shows
  this; run it after any change, not only a bond one.
- **The asymmetry trio reads exactly as designed, and `-leverage 0` shows what the kick now
  carries.** Off, clustering falls to 0.82 and kurtosis to 0.74 — the transient multiplier is a
  third of both — while `-newsrate 0` pushes clustering back UP to 1.14 (serially-independent
  news days are what dilute it) and drains the downside excess. `-refugedays` moves `bdep` a
  little and nothing else in this table; its real work, the calm-day tail hedge, is a row the
  table does not carry.
- **Some settings leave the admissible region.** `-stress 8.0`, `-jumpvar 0.23`, `-leverage 0.20`,
  `-depth 12` and `-haltlimit` at 0 or 0.40 fail realism (clustering, kurtosis, or the tail-shape
  checks); `-stress 2.0`, `-depth 22`, `-recoverydrag 0` and `-capyears 0` fail a mechanism row;
  `-drift` at either end and `-crowdimpact 0.12` fail only fidelity — the level of one quantity
  stops being readable; `-depth 22` and `-newsrate 2.5` cleared fidelity when the 60-session
  envelope became the ladder's 0.50-1.20 (they read 1.19 and 1.15). Always re-run `-validate` after changing a dial.

## A worked example — when the target is not the statistic

The most useful thing 0.22.0 found was not a model defect. It was that **three of the sixteen
fidelity targets were not the statistics the model computes**, and every conclusion drawn from those
rows had the wrong sign.

`median depth %` is the median of every peak-to-trough decline of 15% or worse. The anchor it was
graded against, −27.1%, is the record's median at a **20%** threshold. `bond growth-crash` is the
median bond return across growth-shock drawdowns; its anchor, +20.0%, is **2008 alone** — the largest
of the five such episodes in the record, whose median is +6.6%. `bond infl-crash` was the one
inflation-regime drawdown, rounded 28% toward zero.

```
target               was       measured the way the model measures it      what the old value was
median depth %     -27.1       -21.4% (1954-2026), -23.7% (century)        the median at a 20% threshold
bond growth-crash  +20.0       +6.6% (median of +6.6/+22.4/+4.4/+13.3/+0.8) 2008 alone, the largest
bond infl-crash    -25.0       -34.7% (the one inflation drawdown)          that episode, rounded
```

**What it cost while it stood.** The model was pushed toward crashes deeper than the record's for
its own definition — 0.21.0's crash depth reads 18% too deep on the corrected anchor where it read
7% too shallow on the old one. This page told you for four releases that the bond's crash rally is
understated and not to size a refuge sleeve on it; the model's bond in fact rallies about 45% MORE
than the record's median. And during this release a `refuge` increase aimed at the phantom shortfall
broke the bond-volatility band on one seed before the measurement caught it. **Tuning against a
mis-specified target makes the model worse while every report says it is getting better.**

**The check, which you can run on any target here.** Take the model's own statistic function, apply
it to the control series, and see whether the anchor comes out of it. It discriminates rather than
re-anchoring everything to taste: `crashes/century` 20.7 sits between the record's 19.2 (century) and
24.9 (1954-2026), reconciles, and was left alone. One of the five episode-family anchors survived.

`worst crash %` reconciled here in 0.22.0 and **was re-anchored in 0.22.1 anyway**, because the check
above answers a narrower question than it looks like. −56.8 *is* the 2007-09 episode, which the
control reads at −54.6% — so the anchor names a real episode measured the model's way, and passes.
What it does not ask is whether the WINDOW that episode is the worst of can host the statistic. It
cannot: 1954 opens after the 1929-32 decline, and the worst episode is the one row a window can
delete outright. Over the century the record reads **−84.1%**. Add that question to the check —
*could a longer window of the same record produce a more extreme value on this statistic?* — and
apply it to any target whose statistic is a max or a min.

Both corrections are backed by committed measurements — `test-data/equity-anchors/episodes-2026-08-29.tsv`
and `test-data/bond-anchors/crash-response-2026-08-29.tsv` — which the test suites re-derive the
shipped targets from, and which also record what the old anchors were, so the account above is
checkable rather than asserted.

**An anchor with no recorded convention is the failure mode.** Every one of the three had a window
named and no statement of what statistic it was, and that is exactly what let a 20%-threshold median
and a single 2008 print sit in a target set for four releases. When you add a target, record the
function, the window and the convention, and commit the measurement it came from.

## Grading against a different index — `-anchors`

Every equity fidelity target used to be the S&P's, so a world calibrated to any other index failed
the target set for *being* that other index. `-anchors nasdaq` swaps in a QQQ vector measured over
1999-03-10 to 2026-08-20: volatility 26.90%, return per volatility 0.38, kurtosis 9.55, 25.6 crashes
per century, median depth −22.8%, worst −83.0%.

Only the equity rows move. The bond targets are the same Treasury whatever the equity index is, and
the three depth rungs are already ratios against a relation evaluated at each world's own volatility
and return, so they read 1.00 for any asset. The realism bands do not move *with the anchor* — they
ask whether this is a market at all, and a Nasdaq is one. (Two of them were separately found to be
the S&P's shape and widened; see below.) The two *fidelity* bands do move with it.

**The window is a decision, and it is the part to read before trusting the numbers.** Drawdown
episode counts swing 1.7× on convention alone. The same QQQ data reads 24.1 per century with the
running peak seeded from prior history, **40.1** with a fresh start on a window opening 2001-08-27 —
mid dot-com bear, which resets the peak about 60% down and manufactures episodes on the way back up
— and **25.6** fresh-start from QQQ's own inception. The model measures each path fresh from its own
start, so fresh start is the matching convention, but only on a window that opens near a high. That
is the same rule the equity-anchor fixture states for `w1996`, and the reason it warns against
grading a model ensemble on the mid-bear `w2001` block.

**A Nasdaq world that passes the gate:**

```
market_sim -anchors nasdaq -depth 10 -drift 0.105 -jumpvar 0.02 -fundvol 0.06
```

Realism PASS, mechanism PASS, fidelity PASS at the gate's own ensemble (200 paths × 100 years),
with margin rather than on a band edge — `equity d10 vs real` reads 0.85 against a 0.70 floor.
Against the QQQ anchors: volatility 0.92, return per volatility 0.97, median depth 1.07, depth rungs
0.92 / 0.85 / 0.80.

**Read what it passes with.** Four fidelity misses are disclosed rather than gated: kurtosis 2.2,
crashes per century 1.5, `worst crash %`, where the record sits at the **13th percentile** of
27-year model histories — QQQ's −83.0% is deeper than seven in eight of them, so this world sits on
the shallow side of the Nasdaq tail at the record's own horizon — and the downside volatility
excess, which reads −1.0% against QQQ's +1.1%: the sign is inverted, this world's declines carry
*less* volatility than its advances where QQQ's carry more. The first two are the
volatility-to-crash elasticity gap — the model's 1.44 against a real cross-section of about 0.50 —
surfacing at Nasdaq volatility. Clustering at lag 1 reads 0.39 against its 0.40 realism ceiling.
This world is admissible for **relative** work at Nasdaq-like volatility; it is not a calibrated
Nasdaq, and a per-crash hazard read off it is over-sampled by half.

The channels ride along at their anchored dials: `-rangescale 0.63 -rangedown 0.09 -volidio 0.34`
on this recipe reads range vs cc vol 1.11 and `-satbeta 1.2 -satidio 0.77` correlation 0.85 — the
same readings as on the default world, because both dials are relative to the world's own realized
volatility. `-atrelease 0.23.0-nasdaq` names exactly this world with every channel on at those
dials and selects the Nasdaq anchors: realism, mechanism and fidelity PASS with all six series
graded.

The Nasdaq set's sampling spreads are measured at this recipe (`-noise -anchors nasdaq`, 200
paths), not carried from the S&P's — every spread, since 0.23.1 including the depth rungs, the
valuation proxy and the bond rows, which through 0.23.0 read the S&P world's inline constants for
both sets. The deep rung is where it matters: d20's spread at this recipe is 0.30 against the S&P
default's 2.38, so the row carries real weight here where the S&P loss all but ignores it. The
loss at this recipe reads 2.020 under its own spreads, so a `-calibrate -anchors nasdaq` result
from any earlier release optimised a different function.

- **Signed persistence is graded as a four-rung profile since 0.23.1, not one rung.** `-validate`
  prints the variance ratio at 20, 60, 120 and 250 sessions against the real cross-section's
  envelopes (0.65-1.20, 0.50-1.20, 0.40-1.35, 0.20-1.60: 18 instruments over two windows and three
  CRSP eras, `persistence-2026-09-02.tsv`) and the two short slopes against theirs (vr60-vr20
  -0.25..+0.10, vr120-vr60 -0.30..+0.20), as ONE fidelity row. The long rungs cannot discriminate
  — the record itself spans 0.24-1.56 at 250 sessions — so the row binds at the short rungs and
  on the shape; a world at 0.70 and 1.15 on the two short rungs sits inside both boxes and outside
  every real profile, which is what the slopes are for. The shipped world reads 1.07 / 1.12 / 1.12
  / 1.33; the `0.23.0-nasdaq` recipe 0.95 / 0.88 / 0.77 / 0.72, mean-reverting at every horizon
  where the S&P default trends. The loss still reads the 60 rung alone, at its theory value.

## Biases you inherit whatever you choose

These are properties of the model, not of the default, and they do not go away by changing dials.
The ratios are the scoring ensemble's, 200 paths x 100 years averaged over 8 seeds, and `real@` is
from `-noise`: where the real record falls among model histories of that anchor's own length. **The
list is shorter than it was**, because three of the targets it used to describe were not the
statistics the model computes — see the worked example above.

- **The depth rungs read 1.10 / 1.40 / 2.90 against a relation fitted on a window with no
  depression in it — and the index itself sides with the model.** The relation comes from 35 funds
  over 2001-2026; the CRSP index's own shares, measured with the model's definition
  (`depth-shares-2026-08-30.tsv`), run **1.3 / 2.3** times that relation post-1954 and **1.8 /
  4.6** over the century at d10/d20. The model's shipped shares (0.31 / 0.175 of sessions, median
  path) sit between the post-war index and the century on both deep rungs — the persistence
  retune's slow epochs bought underwater time on the way to the record's dispersion (d10 1.34 →
  1.39, priced and disclosed). d10's band (0.70-1.55) accommodates the reading with margin;
  d20's band is deliberately absent and its weight is 0.03 — one 25-year record cannot pin it
  (`-noise`: 0.09 to 4.89).
- **Valuation dispersion sits inside the record proxy's own windows since the persistence
  retune** (0.33 against sd log CAPE 0.24-0.41), with a per-path cycle at half the record's
  half-life and both wings symmetric — the record's own wings are (27.3% of months past +0.25
  log, 27.9% past -0.25, 1881-2023), so a mania was never a missing upper-wing channel, just
  amplitude this cycle lacked. What remains disclosed: the ensemble century MAX over FAIR stays
  near +11% where the record's manias peaked at 2.0-2.7x the mean valuation (Sep-1929 CAPE
  32.6, Dec-1999 44.2) — the ensemble's mean gap sits below fair, so a collapse from a 2x-fair
  peak remains out of reach and the led-mix is not a calibrated quantity. Pushing harder is
  fenced by the depth rungs and the variance-ratio ceiling (`-capyears 3` reads vr 1.20 and
  d10 1.69; `-beliefyears 0.75` fails d10).
- **The century-scale tail is carried by the macro-disaster channel, and its anchor is one draw.**
  `-disasterrate 0.6` per century, each a 2.0-log fundamental collapse over 2.5 years with half
  reversing over 4 — the Barro-Rietz channel, adopted 0.22.1 after every dial sweep left the median
  century-worst at −58..−61 against the record's −84.1. With the valuation cycle and the
  asymmetry channels on top the record sits at the **38th percentile** of model centuries (it was
  the 1st before 0.22.1, the 18th before the cycle): sticky post-crash pessimism deepens the
  declines the disasters start. The
  remaining stance is a judgment call the dials expose: the record is one draw, other national
  markets' centuries ran deeper, and `-disasterrate 0` removes the channel bit-for-bit if you want
  the disaster-free counterfactual.
  Ruin rates for levered sleeves computed off an ensemble minimum remain **upper bounds, not
  estimates**: that minimum is drawn from 20,000 market-years and no fund lives that long.
  This is a DRAWDOWN, accumulated over sessions; the worst single SESSION is a separate question and
  is set by `-haltlimit` rather than by a numerical constant.
- **A century path is mildly trending (vr60 1.12), because its disasters and its valuation epochs
  are.** The variance-ratio anchor (1.00, envelope 0.50-1.20) was measured on modern, disaster-free
  windows; the CRSP century itself — the window with 1929-32 in it — reads **1.175** in the
  committed persistence fixture, and its five-year VR is era-split beyond use as an anchor
  (`longhorizon-2026-08-30.tsv`: 0.730 for the century, 1.152 post-1954, ±30-40% block noise on
  both). A multi-year collapse IS signed serial dependence; the model without these channels read
  1.00 on century paths, which real disaster-bearing centuries do not.
- **The bond's crash rally is on its anchor since the settled-stress refuge** (1.09 against the
  record's median across its growth-shock drawdowns; it read 1.39-1.48 through 0.23.0's own
  development). The model's bond gains about 7% where the record's median is 6.6% and its best was
  22.4%. Size a refuge sleeve on the distribution rather than on this row, which `-noise` puts the
  record at the 31st percentile of.
- **The bond spends longer below its peak than its own volatility implies** (1.30). This is the one
  bond row with a band fitted across eight real funds rather than one, so it is the most transportable
  of them — and it is also the one the `-crossasset` ladder grades at four durations.
- **Every session past −18% comes from the liquidity spiral**, in every world measured — a news
  jump is −3.3% and cannot reach that range alone. The jump channel sets how OFTEN those sessions
  arrive; it does not set how large they are. If you are studying tail magnitude, `-stress` and
  `-depth` are the dials, not `-jumpvar`.
- **Crash frequency is on its anchor** (0.97; 20.1 per century against a real 20.7 — the disaster
  channel consolidated episodes and the valuation cycle's slow epochs finished the job; the news
  channel's episode inflation is displaced back out of the noise budget). The record sits at the
  52nd percentile of model histories.
- **Single-history kurtosis is wild here and the median sits just under anchor** (0.87 on the
  scoring ensemble, 0.93 at the default seed — the rarer-larger jump pair and the leverage kick
  split the tail budget that was all jumps before). A 72-year window reads 10.6 at the 5th
  percentile and 93 at the 95th, because a window either contains its 1987 or does not. Do not
  read kurtosis off one path — and the 30 realism ceiling is still what binds when you reach for
  tails, not the target.
- **The three asymmetries are on anchor since 0.23.0's adoption** (downside excess 1.06, leverage
  corr 0.95, tail hedge 0.87 at the default seed), and `-noise` reads the record as a typical
  single history of this model on most rows (vol 50th, crashes 52nd, median depth 63rd, variance
  ratio 50th, valuation dispersion 39th — the persistence retune moved it from the 78th — and
  the asymmetries 42nd/46th/26th). The rows where it can still tell the difference are the
  release's disclosed misses: clustering lag 20 (72nd), the depth rungs d10/d20 (34th), and the
  tail hedge's remaining edge (26th).

## If you are calibrating rather than choosing

`-calibrate N` random-searches thirteen parameters against the fitness loss and reports the best few
re-scored on a held-out seed. It prints; it does not modify defaults. Note that the loss has no
notion of *not breaking what is already right* — it will happily spend an accurate row to improve an
inaccurate one, so read the whole fidelity table after any recalibration, not just the loss.

The current default is not the search's own optimum and does not need to be. Two things the loss
cannot see were corrected by hand when 0.21.0 was calibrated: the search drove `-crowdimpact` to its
range floor, which switches the reflexive channel off — visible only as `cflow` collapsing to 0.9%
of the noise term, not as a worse loss — and it raised `-refuge` until bond volatility left its
band. Read the binding diagnostics and the whole fidelity table after any recalibration, not just
the loss.

**Score candidates on more than one seed.** A single-seed refinement here found a world scoring
1.687 that was a 2.15 median across five seeds. Depth-rung agreement is especially cheap to overfit,
because the relation those rungs are graded against moves with the sample it is evaluated on.

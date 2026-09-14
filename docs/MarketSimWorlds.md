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
[ "$(market_sim.exe -version)" = "0.24.2" ] || { echo "wrong simulator" >&2; exit 1; }
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
(`rangeScale > 0` — the sampled intra-bar extremes; the bar's open is the prior close unless
`overnight > 0`) and `logVolume` (`volIdio > 0` — a mean-free log turnover index; apply your own
detrend convention as you would to a real series), and since schema 11 `logTraded` and `divYield`
(`divYield > 0` — the traded price as a log, the total-return `price` deflated by the yield accrued
each session, beside the session yield in %/yr; `price` keeps its meaning, so a consumer runs its
decisions on the traded series and its accounting on `price`; `channels.level.kDiv` is the world's
mean fundamental/price the yield was normalized by), and `logOpen` (`overnight > 0` — the bar's
open; `logHigh`/`logLow` then bracket the open and the close rather than the prior close and the
close, and `channels.open` carries the overnight share and the gap shares), and `logBasket` plus
`logName1..N` (`basket > 0` — the equal-weight aggregate and the names, all logs; N from the header;
the aggregate is BUY-AND-HOLD, equal weights held from the first EMITTED session and never rebalanced -- exactly `logBasket(t) = log(mean_i exp(logName_i(t) - logName_i(0)))`, which a reader reproduces from the emitted names to the sixth decimal (the columns' own rounding); a rebalanced index of the names correlates 0.999 with it and is a different series -- so it
reconstructs exactly as `ln(mean_j exp(logName_j - logName_j[0]))` — a daily-rebalanced mean of the
same names is a different series (session returns correlate about 0.98 with it, and the levels
diverge by the dispersion drag);
`channels.basket` carries the three levels' readings plus `nameD20Spread`, the spread of time below
peak across the names). `world.basketDrift` records the cross-sectional drift dispersion dial, and
when it is on `channels.level.kDr` carries the primary's realized annualized volatility the dial is
a fraction of; a file with the dial off has neither `kDr` nor any other new key. Since schema 12,
`macroSpread`, `macroSlope`, `macroCond` and `macroIvol`, since 13 `macroYield10` and
`macroCredit`, since 14 `macroPolicy` and since 15 `macroBankCredit` and `macroOutput`
(`world.macro > 0` — the nine observables of
[the macro panel](#the-macro-panel--macro), LEVELS in their counterparts' units rather than logs,
none near a rounding tie; `channels.macro` names each column's FRED `counterpart` and natural
`cadence` beside the readings the macro rows grade, so a consumer's point-in-time loader can route
the column through the same release-lag table its counterpart gets). Every log column carries a NATURAL
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
cargo install vastblue-uni@0.24.2 --root ~/.local/uni-0.24.2
~/.local/uni-0.24.2/bin/market_sim.exe -version
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

**A set of worlds, not a best fit — `-worldset`.** Thirty searched dials against some forty-five
graded rows that are not independent means several distinct worlds match the record equally
well, and a strategy interacts with the mechanism rather than with the summary statistic. A
verdict formed on one best-fit world therefore carries the same over-confidence that weakens a
backtest: it is conditional on a single realisation. The calibration search
(`market_sim_search`, or `jsrc/marketSimSearch.sc`, which produce identical files) keeps an
**archive** of every world it finds that is *feasible* — passes every realism and mechanism gate
row on every one of its seeds, on both the S&P and the Nasdaq anchor sets — and spreads them
across the behaviours the record cannot pin down. Fidelity rows are scored, not gated: members
differ from the record inside its own sampling error, which is where the set's value is. Each
member is then re-scored on seed streams the search never selected on, and the ones that fail
there are dropped before the set is exported:

```
market_sim_search -out search -paths 60 -years 80 -reps 3 -keep 300 -transport 0.24.1-nasdaq -bar 1
market_sim_search -out search ... -holdout 12
market_sim_search -out search ... -prune
market_sim_search -out search ... -export worlds.json
```

`worlds.json` is a JSON array of members, each with its `member` number, the world it was seeded
from, its score and worst row, and a `world` block in the sidecar's own key format at the
archive's full precision. The release ships two, built this way and pruned by their holdouts:
`test-data/worlds/0.24.2-sp500.json` (173 members, seeded from the default; pass `-anchors sp500`
or nothing) and `test-data/worlds/0.24.2-nasdaq.json` (191 members, seeded from `0.24.2-nasdaq`;
pass `-anchors nasdaq`, since a member names no anchor set). To run one:

```
market_sim.exe -worldset test-data/worlds/0.24.2-nasdaq.json -worldindex 38 -anchors nasdaq -paths 200 -years 40 -emitall -emit m38.tsv
```

`-worldindex` addresses a member by its own number, defaulting to 0. The member seeds every dial
exactly as `-atrelease` does, explicit flags after it override, and a file whose dials are not
exactly this binary's is refused rather than filled in — an omitted dial would take the shipped
default and be a different world under the member's name. `-worldset` and `-atrelease` each name
a whole world, so giving both is refused.

**How to read a ranking across the set.** Run the strategy on every member and rank it on each.
A ranking that holds across every member is the claim the set exists to support: it holds across
every world consistent with the record, which is stronger than holding on the one path history
provides. A ranking that flips between members is not a failure of the set; it says the verdict
depends on a mechanism the record does not pin down, and the members it flips between name which
one — their `world` blocks differ, and the descriptor columns of `archive.tsv` (signed lag-1
autocorrelation, the 250-session variance ratio, lag-20 clustering, kurtosis, crashes per path,
median crash depth) say how the markets they produce differ. That is a finding about the strategy,
not noise to average away.

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
5.2 rate points, the bottom of the three real cycles — reads d=5.70 at 0.73 and d=13.50 at 1.32 at
400 paths, verdict PASS (EDGE at 200: the 13.50 cell sits 0.02 under its ceiling). **Re-run
`-crossasset` after any world change, not only after a bond one.** And every flow the bond takes
must scale with duration: the slow repricing channel's bond leg shipped in 0.24.1 as a price shock
of one size at every duration, and the 1.80-year rung read it as 1.52 on bond vol × duration until
0.24.2 made it a yield move.

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
| `-stress` | liquidity-spiral gain: how much a run of down days amplifies later moves | 5.0 |
| `-volresp` | THE VOL RESPONSE: the diffusive noise times exp(V × S), S the session's decline in units of the conditional sd that GENERATED it, accumulated at `-volrespphi` and fed through `-volrespattack` so the response builds over two to five sessions rather than peaking at lag 1. One decline's response is a plateau, not an integral divided over the sessions after it. Draw-free, `-volrespcap` bounds the state, and 0 is bit-identical — [below](#the-vol-response-to-a-fall) | 0.021 |
| `-stressadapt` | the liquidity spiral's SCALE speed: the EWMA weight on r² that standardises the decline its own stress index reads. At 0.005 (a ~140-session memory) a stretch that a persistent vol mechanism has genuinely made volatile reads as continuous STRESS and the spiral mints spikes out of it, which blocked every form of the response tried. The index the rest of the world reads — policy easing, the refuge bid, margin selling, the credit stock's paydown — keeps the slow scale | 0.036 |
| `-slowshare` | THE SLOW REPRICING CHANNEL: this share of the diffusive variance leaves the order-flow channel and reprices the fundamental and the price TOGETHER, like a news jump, so the value channel has nothing to arbitrage and the move never passes through the spiral. Its volatility is long-memoried and asymmetric, which is what puts the abs-r profile back on the record's SHAPE. `-slowvol` sets its scale, `-slowlev` its own leverage effect, `-slowphi` its persistence, `-slowperm` how much of each move is permanent, `-slowbeta` the bond's opposite-sign loading. 0 is bit-identical — [below](#the-vol-response-to-a-fall) | 0.20 |
| `-noiseasym` | the item-12 cascade: the diffusive noise times exp(g − Var g), g a cascade of the session's own diffusive draw — a fast attack into a decay at `-noiseasymphi`, capped by `-noiseasymcap`. Ships at 0: at every setting that closes part of what is left, something graded gives way — [below](#the-vol-response-to-a-fall) | 0 |
| `-levpersist` | the leverage kick's own memory: at P the kick raises the following sessions too, through weights that sum to 1, so only the shape of the response moves. Closes the clustering hump and puts lag-1 clustering on the record's 0.298, at the cost of the lag-1 leverage correlation — [below](#the-vol-response-to-a-fall) | 0 |
| `-stressscale` | THE AMPLIFIER's gain scale: the spiral's excess gain multiplied by (depth / 17.4)^E, so a thinner market's liquidity event is not proportionally larger than the reference world's. The record's crash count is volatility-flat across a fresh-start cross-section (`amplifier-2026-09-07.tsv`) where the model's `depth` sweep reads 1.8; the default world is unchanged at any E, and `0.24.0-nasdaq` runs at 0.5 | 0 |
| `-levgain` | THE LEVERAGE CYCLE: a borrowing stock swings over years as a credit cycle of its own and is paid down under stress; the spiral's gain is multiplied by 1 + levgain × (the stock's rise over its trailing year), so an ordinary shock cascades into a 20% decline where leverage has been building and not where it has not. 0 restores 0.23.1's amplification bit for bit | 6 |
| `-depth` | market depth; price impact scales as `12/depth`, so higher = calmer | 17.4 |
| `-drift` | fundamental drift per year; no dividend, so this IS total return | 0.122 |
| `-fundvol` | fundamental volatility per year — sets time under water, not daily return scale | 0.060 |
| `-jumpvar` | share of the equity shock's **variance** carried by jumps rather than diffusion; 0 turns the tail channel off | 0.16 |
| `-jumprate` | jumps per session at average volatility; with `-jumpvar` it sets the **size** | 0.0035 |
| `-jumpskew` | how far each jump is shifted down, in its own sds; variance-normalised, so deeper skew means smaller jumps, not fatter tails | 0.65 |
| `-leverage` | the leverage effect: a decline raises the NEXT session's diffusive volatility by `exp(leverage * decline-in-sds)`, saturated at 4 sds; a rally raises nothing. Reads the same session's news jump. With the bar channels on, `-leverage 0` reads range clustering at 0.56–0.57 against the 0.57 floor, seed-dependent: the kick is what carries a decline's width into the next session's bar, and a world without it produces bars that cluster less than real bars | 0.10 |
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
| `-rangescale` | intra-bar high/low, sampled per session from the exact Brownian-bridge extremes at the session's own vol state re-levelled onto the world's realized volatility, times this dial — the range scales with the session's noise, not with \|return\|, which is what makes it detectably real (record corr(lnH/L, \|r\|) is only 0.70–0.72). Anchored 0.63 on SPY/QQQ OHLCV (0.78 with `-overnight` on, the intraday haircut made explicit), and it reads the same bar-to-ccvol ratio at any world; adds `logHigh`/`logLow` to `-emit`. NOT searchable | 0 (off) |
| `-rangedown` | same-session sign↔vol coupling on the bar: down sessions get (1+X) the bridge sigma, up sessions 1/(1+X). Anchored 0.09 (0.13 with `-overnight` on, where it reads the intraday return), which puts BOTH the range's down/up (1.14 vs 1.109–1.142) and volume's down-up gap (0.097 vs 0.094–0.098) on the intraday rulers. Requires `-rangescale` | 0 |
| `-volidio` | log turnover index riding the range: elasticity 0.59 to the range's deviation from its slow normal (frozen from the measured regression) plus a two-component persistent idio whose total sd is this dial (anchored 0.34). Requires `-rangescale`; adds `logVolume` to `-emit`. NOT searchable | 0 |
| `-divyield` | DIVIDENDS: the world's mean dividend yield, %/yr. The session yield is Y × fundamental/price over the world's mean of it (a world constant solved on the same fixed ensemble as the bar level — the ensemble's mean fundamental/price is 2.06 at the default and 2.30 on the Nasdaq recipe, and a per-path mean would leak the path's future), so a rich session yields less and the ensemble's pooled mean yield is the dial; the reported median path's mean reads about 0.9× of it (2.62 at 2.95, 0.69 at 0.78), valuation epochs skewing the path means; `-emit` gains `logTraded` (the total-return `price` deflated by the accrued yield — `price` itself is unchanged) and `divYield`. Anchored 2.95 on Shiller's S&P 1954–2023 and 0.78 on QQQ 2005–2026 (`dividend-2026-09-02.tsv`); the level is graded when on. An identity parameter, never searched | 0 (off) |
| `-overnight` | THE OPEN: the overnight share of the session's diffusive variance (0 ≤ X < 1). The open is the bridge point at that share of the session, with the session's news jump and jump-channel move landing overnight whole and the whole move becoming the gap when it overshoots the session on its own side; the bar then runs from the open over the remaining variance and the sign coupling reads the intraday return. `-emit` gains `logOpen`, and `logHigh`/`logLow` bracket the open and the close. Anchored 0.20 on the S&P default and 0.22 on the Nasdaq recipe against the record's overnight variance shares 0.33 / 0.28 (`bars-2026-09-01.tsv`, graded when on); the bar dials re-anchor with it, `-rangescale 0.78 -rangedown 0.13`, since the intraday bridge carries less of the session | 0 (open = prior close) |
| `-basket` | THE BASKET: N single names as observational second-pass instances of the primary — each the shared sector leg (`-basketbeta` on the primary's observed return plus `-basketsector` idio riding the vol state × spiral, the satellite's construction) plus its own idio (`-basketidio`, riding the vol state WITHOUT the spiral, so shared variance dominates in stress and pairwise correlation rises) and its own gaps (`-basketgaps` per year, Student-t jumps of a frozen 9% size, SYMMETRIC — the down-skew belongs to the index and reaches names through the shared leg). The equal-weight aggregate (buy-and-hold, never rebalanced) is the sector, graded against the eight's basket on the set's own primary; `-emit` gains `logBasket` and `logName1..N`. Anchored N 8, beta 1.56, sector 1.1, idio 0.9, gaps 6.0 on folio's eight semiconductor names under SMH 2012–2026 (`basket-2026-09-02.tsv`); `-atrelease 0.24.0-basket` names the default with it on (`0.23.1-basket` the 0.23.1 world). The dials do NOT transport to the Nasdaq set — 8 / 1.37 / 0.7 / 0.85 / 8.0 there, which `-atrelease 0.24.0-nasdaq-basket` names | 0 (off) |
| `-basketdrift` | CROSS-SECTIONAL DRIFT DISPERSION: the sd of the names' own annual log-drift offsets, as a fraction of the primary's realized volatility, drawn once per name per path and centred exactly so the sector's log drift is untouched. Moves the SPREAD of time below peak across names, not its median. **Anchored at 0** and off in every recipe: the record cannot supply a positive value (below) | 0 (off) |
| `-macro` | THE MACRO PANEL: 1 emits seven observables derived from the model's own state after the price loop — `macroSpread` (BAA10Y: equity + bond stress, fast and credit-cycle slow), `macroSlope` (T10Y2Y: the 10y−2y expectation the rate process implies; the one anchored-scale member), `macroCond` (NFCILEVERAGE: the leverage cycle's ratio + the crowd share, raw), `macroIvol` (VIXCLS: the conditional sd re-levelled onto the world's realized vol, × the record's variance risk premium) — each a persistent-noise read sized to the record's predictive R² — and five draw-free levels, `macroYield10` (DGS10: the 10-year the slope is a difference of), `macroPolicy` (DFF: the loop's own policy rate, re-set at a meeting to the nearest quarter point and held), and the credit system: `macroBankCredit` (TOTBKCR) and `macroOutput` (GDP) as indices with `macroCredit` (TOTBKCR/GDP, percent) the ratio they imply. No scale dials: a rank-reading consumer cannot see scale. Cadence, release lag and revisions are the consumer's point-in-time layer. Reaches no price; graded when on ([below](#the-macro-panel--macro)) | 0 (off) |
| `-macronull` | THE NULL PANEL: 1 takes the four macro columns from a SIBLING path — the same world at another seed — so their marginals and persistence are this world's and their coupling to this path's price is nil: the no-edge comparison for a rule that reads them. The macro rows do not grade a null panel; the sidecar lists its columns as ungraded. Needs `-macro 1`; one extra price loop per path | 0 (the path's own panel) |
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
setting                        vol  kurt  clus    vr crash   d10  bdep   r/v  tshare  cflow  gate
-------------------------------------------------------------------------------------------------
DEFAULT                       1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P

-stress 2.0                   0.83  0.34  0.58  1.10  0.55  1.86  1.06  1.22   0.20   3.26  P/F/F
-stress 4.7 *                1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-stress 8.0                   1.33  2.47  1.59  0.99  1.36  1.05  1.45  0.72   0.20   3.47  F/P/F

-levgain 0                    0.97  0.62  0.93  1.07  0.87  1.36  1.26  1.03   0.20   3.45  P/F/P
-levgain 6 *                 1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-levgain 10                   1.09  1.70  1.28  1.06  1.07  1.25  1.36  0.90   0.20   3.43  F/P/F

-depth 12                     1.31  1.23  1.23  0.94  1.49  0.95  1.31  0.76   0.20   4.02  F/P/F
-depth 17.4 *                1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-depth 22                     0.88  0.73  0.99  1.16  0.72  1.72  1.30  1.13   0.20   3.13  P/F/P

-drift 0.08                   1.01  0.91  1.10  1.08  0.85  1.46  1.31  0.60   0.20   3.52  P/P/F
-drift 0.122 *               1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-drift 0.15                   1.00  0.94  1.09  1.09  0.96  1.36  1.30  1.25   0.20   3.36  P/P/F

-fundvol 0.041                1.00  0.91  1.09  1.03  0.92  1.21  1.30  1.00   0.20   3.46  P/P/P
-fundvol 0.060 *             1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-fundvol 0.10                 1.02  0.91  1.10  1.27  1.06  1.80  1.31  0.97   0.20   3.36  P/P/F

-jumpvar 0                    1.01  0.67  1.12  1.06  0.96  1.35  1.30  0.99   0.20   3.50  P/P/P
-jumpvar 0.11 *              1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-jumpvar 0.23                 0.99  1.54  1.05  1.10  0.95  1.37  1.29  1.00   0.20   3.33  F/P/P

-jumpskew 0.7                 1.00  0.96  1.09  1.08  0.96  1.38  1.31  0.99   0.20   3.43  P/P/P
-jumpskew 1.0 *              1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-jumpskew 1.3                 1.00  0.90  1.09  1.08  0.97  1.38  1.30  0.99   0.20   3.44  P/P/P

-jumprate 0.002               1.00  0.98  1.10  1.08  0.96  1.35  1.30  0.99   0.20   3.42  P/P/P
-jumprate 0.0035 *           1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-jumprate 0.012               1.01  0.81  1.10  1.07  0.97  1.36  1.31  0.98   0.20   3.46  P/P/P

-leverage 0                   0.97  0.76  0.89  1.08  0.90  1.46  1.26  1.03   0.20   3.43  P/P/P
-leverage 0.10 *             1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-leverage 0.20                1.07  1.74  1.29  1.06  1.02  1.22  1.35  0.92   0.20   3.42  F/P/F

-volpersist 0.990             1.00  0.90  1.07  1.08  0.95  1.39  1.29  0.99   0.20   3.44  P/P/P
-volpersist 0.993 *          1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-volpersist 0.996             1.02  1.00  1.12  1.08  0.96  1.32  1.31  0.98   0.20   3.41  P/P/P

-newsrate 0                   1.01  1.04  1.19  1.04  0.93  1.22  1.31  0.99   0.20   3.47  P/P/P
-newsrate 1.3 *              1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-newsrate 2.5                 1.00  0.90  0.99  1.12  0.99  1.47  1.29  0.99   0.20   3.39  P/P/P

-refugedays 0                 1.00  0.93  1.09  1.08  0.97  1.38  1.26  0.99   0.20   3.43  P/P/P
-refugedays 1 *              1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-refugedays 5                 1.00  0.93  1.09  1.08  0.97  1.38  1.32  0.99   0.20   3.43  P/F/P

-haltlimit 0                  1.00  0.93  1.08  1.07  0.97  1.37  1.31  0.99   0.20   3.43  F/P/P
-haltlimit 0.25 *            1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-haltlimit 0.40               1.00  0.93  1.08  1.07  0.97  1.37  1.31  0.99   0.20   3.43  F/P/P

-disasterrate 0               1.00  0.94  1.09  1.00  1.04  1.26  1.30  1.05   0.20   3.49  P/F/P
-disasterrate 0.6 *          1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-disasterrate 1.2             1.00  0.96  1.09  1.17  0.90  1.53  1.31  0.91   0.20   3.37  P/P/F

-beliefshare 0                1.00  0.94  1.09  1.12  1.09  1.24  1.30  1.05   0.21   3.51  P/F/F
-beliefshare 0.95 *          1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-beliefshare 0.99             1.00  0.92  1.09  1.07  0.94  1.39  1.30  0.92   0.20   3.40  P/P/P

-beliefyears 0.75             1.00  0.95  1.09  1.07  0.86  1.53  1.30  0.99   0.20   3.40  P/P/P
-beliefyears 1.5 *           1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-beliefyears 5                1.00  0.92  1.09  1.10  1.05  1.27  1.31  1.02   0.20   3.48  P/P/P

-capyears 0                   1.00  0.92  1.09  1.00  0.98  1.11  1.30  1.01   0.20   3.49  P/P/P
-capyears 1.5 *              1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-capyears 3                   1.00  0.94  1.09  1.17  0.92  1.64  1.31  0.98   0.20   3.39  P/P/F

-trendshare 0.02              1.00  0.91  1.08  1.07  0.96  1.38  1.30  0.99   0.18   3.03  P/P/P
-trendshare 0.055 *          1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-trendshare 0.30              1.03  1.03  1.16  1.11  1.04  1.33  1.32  0.96   0.36   6.24  P/P/P

-crowdimpact 0.010            0.98  0.88  1.06  1.06  0.90  1.39  1.28  1.01   0.20   1.15  P/P/P
-crowdimpact 0.030 *         1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-crowdimpact 0.12             1.12  1.57  1.34  1.21  1.29  1.26  1.39  0.87   0.20  13.62  F/P/F

-value 0.020                  1.00  1.00  1.11  1.10  0.88  1.58  1.33  0.91   0.19   3.31  P/P/F
-value 0.056 *               1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-value 0.070                  1.00  0.95  1.09  1.07  0.97  1.33  1.30  1.01   0.20   3.46  P/P/P

-recoverydrag 0               0.99  0.84  1.06  0.91  0.99  1.15  1.26  1.04   0.20   3.53  P/P/P
-recoverydrag 8.5 *          1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-recoverydrag 20              1.00  0.92  1.10  1.09  0.98  1.41  1.30  0.99   0.20   3.42  P/P/P

-recoveryfloor 0.05           1.00  0.96  1.09  1.09  0.91  1.47  1.31  0.96   0.20   3.40  P/P/P
-recoveryfloor 0.10 *        1.00  0.93  1.09  1.08  0.97  1.38  1.31  0.99   0.20   3.43  P/P/P
-recoveryfloor 0.50           1.00  0.90  1.08  0.98  1.03  1.19  1.29  1.02   0.20   3.50  P/P/P
```

200 paths x 100 years, seed 20260813 — the scoring ensemble. `vol` equity volatility · `kurt` daily kurtosis ·
`clus` volatility clustering (lag 1) · `vr` the 60-session variance ratio, where 1.00 is no signed
serial dependence and the envelope is 0.50–1.20 (the ladder's 60 rung) · `crash` crashes per century · `d10` time spent >10% below
peak, against what real equity funds of the same volatility and return spend · `bdep` the bond's
equivalent, against what its own volatility implies · `r/v` return per unit volatility ·
`tshare` **realized** trend-follower share · `cflow` crowd flow, bp/session ·
`gate` realism/mechanism/fidelity.

Nine things that table is trying to tell you:

- **`levgain` is where the tail now comes from, and it has a ceiling of its own.** Off, kurtosis
  falls to 0.62 and clustering to 0.93, and the mechanism gate fails on the conditions index's
  hazard row — a world whose 20% declines do not follow leverage; at 10 the fragile phases run
  away — kurtosis 1.70, clustering 1.28, realism fails. 6 is where the cycle's cascades supply
  what the jump channel gave up (`-jumpvar` 0.14 → 0.11) with the tail budget unmoved.
- **The valuation cycle is a PAIR, and each half alone fails a different gate.** `-beliefshare 0`
  (cap term alone) pushes the variance ratio to 1.12 — re-climbs toward extrapolated fair are
  60-day trends — and the cycle's own mechanism row with it; `-capyears 0` (beliefs alone) reads
  all lower wing (vr 1.00, d10 1.11). The belief half REFUNDS variance ratio (its perceived-fair
  tracking cuts medium-horizon drift-chasing), which is exactly the budget the cap half spends:
  together at the defaults they read vr 1.08, dispersion 0.31.
- **`disasterrate` is the century-tail dial and almost nothing else.** Off, the world fails the
  mechanism gate (the channel is inert) and the record's century-worst returns to the far tail of
  what the model can produce; at 1.2 the variance ratio reads 1.17 and the fidelity band FAILS,
  because more multi-year declines is more signed persistence. Daily volatility, kurtosis and
  clustering barely move at any setting — rarity is what buys that.

- **`crowdimpact` is a mechanism dial again, not a trend dial.** Since 0.22.0 the crowd's price
  pressure comes from the exposure it TRADES in a session rather than the exposure it holds, so
  running it harder no longer manufactures drift. `-crowdimpact 0.12` is four times the default and
  reaches 13.6 bp/session of crowd flow — 20% of the noise term — while `vr` only reaches 1.21. Under
  the old law the shipped default was already at 1.52 on 4.7 bp. What running it hard DOES buy is
  crash frequency (1.29), clustering (1.34) and kurtosis (1.57), which is why the default is not there.
- **`stress` is not a volatility dial.** It is one amplifier producing volatility, fat tails *and*
  volatility clustering together. Raising it 4.7 → 8.0 takes volatility from 1.00 to 1.33 and
  clustering from 1.09 to 1.59 — and fails the realism gate. You cannot buy tails *here* without
  buying clustering.
- **`recoverydrag` and `value` are one pair, and neither reads correctly alone.** The base pull
  governs shallow water; the drag governs deep drawdowns. Turn the drag off and leave the pull at
  its shipped 0.056 and `d10` falls to 1.15. Weaken the pull instead and `d10` runs to 1.58 with a
  fidelity row failing. Both also move `vr` — the drag off reads 0.91, the weak pull 1.10 — so
  a world tuned for time under water on this pair alone lands its serial persistence somewhere it
  did not choose.
- **`jumpvar` and `jumprate` are ONE dial with two handles, and that is what makes the tail
  reachable.** `-jumpvar 0` is this world with the tail channel off and nothing else moved: the jump
  draws come from their own RNG stream, so no other statistic shifts by construction. Turning it on
  moves `kurt` 0.67 → 0.93 at the shipped pair — the leverage cycle's cascades and the leverage
  kick carry the rest of the tail budget, which was all jumps in 0.22.1. **The ceiling is the realism band, not the target**,
  and the band is what `jumprate` buys headroom against: at the old rate of 0.0010, `-jumpvar 0.12`
  passed at the default seed and read kurtosis 35.2 on another, against a ceiling of 30. Raising the
  rate to 0.0030 means the same jump variance arrives as more, smaller jumps, so no seed runs away —
  the five tried read 19.5 to 27.6 — and the *median* kurtosis rises rather than the extremes.
  Rarer jumps are not fatter tails; they are noisier ones. Set the pair by the band, on more than one
  seed.
- **`fundvol` is the dial for time under water, and it is not free.** Raising `-fundvol` 0.041 →
  0.10 moves `d10` from 1.21 to 1.80 while volatility and return-per-volatility sit still — the
  fundamental accumulates into drawdown depth without reaching daily return scale. It pays in trend:
  `vr` goes 1.03 → 1.27. The shipped 0.060 is where the two meet (0.070 before the leverage cycle,
  whose fragile phases lengthen time under water on their own), and it is the dial that carries the
  drawdown rungs now that the crowd no longer does.
- **The two bond dials are almost orthogonal to the equity leg, but the reverse is not true.**
  `-easing` and `-refuge` move `bdep` and leave volatility, kurtosis and clustering untouched. The
  equity world nonetheless moves the bond ladder through the correlation channel — 0.22.0 had to
  re-solve `easing` twice while the equity side settled. `-crossasset` is the only thing that shows
  this; run it after any change, not only a bond one.
- **The asymmetry trio reads exactly as designed, and `-leverage 0` shows what the kick now
  carries.** Off, clustering falls to 0.89 and kurtosis to 0.76 — the transient multiplier is a
  fifth of both — and with the bar channels on, range clustering sits on its 0.57 floor
  (0.56–0.57 across seeds; the sweep table was run with the channels off) — while `-newsrate 0` pushes clustering back UP to 1.19 (serially-independent
  news days are what dilute it) and drains the downside excess. `-refugedays` moves `bdep` a
  little and nothing else in this table; its real work, the calm-day tail hedge, is a row the
  table does not carry.
- **Some settings leave the admissible region.** `-stress 8.0`, `-levgain 10`, `-jumpvar 0.23`,
  `-leverage 0.20`, `-depth 12`, `-crowdimpact 0.12` and `-haltlimit` at 0 or 0.40 fail realism
  (clustering, kurtosis, or the tail-shape checks); `-stress 2.0`, `-levgain 0`, `-depth 22`,
  `-refugedays 5`, `-disasterrate 0` and `-beliefshare 0` fail a mechanism row; `-drift` at either
  end, `-fundvol 0.10`, `-disasterrate 1.2`, `-capyears 3` and `-value 0.020` fail only fidelity —
  the level of one quantity stops being readable. Always re-run `-validate` after changing a dial.

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
graded. `-atrelease 0.23.1-nasdaq` is the same world with the open on (`-overnight 0.22`) at the
bar dials re-anchored for it (`-rangescale 0.78 -rangedown 0.13`) and the dividend stream at its
Nasdaq anchor (`-divyield 0.78`): overnight share 0.279 against
the record's 0.28, range vs cc vol 1.104, down/up 1.136, clustering 0.704, the satellite
untouched, all three classes PASS. `-atrelease 0.24.0-nasdaq` is that world with the macro panel,
the leverage cycle and the amplifier's gain scale on: `-stressscale 0.5` makes the spiral's
absolute size 0.71 of the S&P world's, which the record asks for — crash count is
volatility-flat across the fresh-start cross-section, slope 0.01, where the model's `depth` sweep
reads 1.8 — and it takes daily kurtosis from 24 to 16 (the record's 9.6 at its own horizon) and
lag-1 clustering from 0.38 to 0.31 (0.29), with `depth` 8.4 giving back the volatility the spiral
no longer supplies (the band's floor is 23.5%), `refuge` 0.15 the bond's rally and `levGain` 8
the hazard (1.47–1.50 on six seeds, build-up 0.83–0.85); `stress` 4.4 and `jumpVar` 0 as before.
All three classes PASS on six seeds, volatility 24.1–24.5% against the band's 23.5% floor. What the scale does not buy, disclosed: the crash count
stays at 36–37 per century against 25.6, because diffusion alone at this volatility crosses 15%
thirty times a century (`-stress 0.01` reads 29.5) and the volatility band forces the depth
that buys them; and lag-20 clustering gives 0.22 → 0.18 against the record's 0.25 — the model's
|r| autocorrelation decays from lag 1 where the record's rises to a hump at lags 2–5 and holds
0.13–0.17 at lags 60–120 on both references (the profile rows of `amplifier-2026-09-07.tsv`,
reported), a persistent asymmetric vol response the amplifier's single timescale cannot give
without paying kurtosis. On the S&P default the same open reads 0.328 at
`-overnight 0.20` with the same bar dials (range vs cc vol 1.097, down/up 1.135).

The Nasdaq set's sampling spreads are measured at the current recipe (`-noise -atrelease
0.24.0-nasdaq`, 200 paths), not carried from the S&P's — every spread, since 0.23.1 including the depth rungs, the
valuation proxy and the bond rows, which through 0.23.0 read the S&P world's inline constants for
both sets. The deep rung is where it matters: d20's spread at this recipe is 0.30 against the S&P
default's 2.38, so the row carries real weight here where the S&P loss all but ignores it. The
loss at this recipe reads 2.020 under its own spreads, so a `-calibrate -anchors nasdaq` result
from any earlier release optimised a different function.

- **Signed persistence is graded as a four-rung profile since 0.23.1, not one rung.** `-validate`
  prints the variance ratio at 20, 60, 120 and 250 sessions against the real cross-section's
  envelopes (0.70-1.15, 0.55-1.20, 0.45-1.20, 0.45-1.30: 18 instruments over two windows and three
  CRSP eras, `persistence-2026-09-11.tsv`) and the two short slopes against theirs (vr60-vr20
  -0.20..+0.10, vr120-vr60 -0.15..+0.15), as ONE fidelity row. The long rungs cannot discriminate
  — the record itself spans 0.24-1.56 at 250 sessions — so the row binds at the short rungs and
  on the shape; a world at 0.70 and 1.15 on the two short rungs sits inside both boxes and outside
  every real profile, which is what the slopes are for. The shipped world reads 1.07 / 1.12 / 1.12
  / 1.33; the `0.23.0-nasdaq` recipe 0.95 / 0.88 / 0.77 / 0.72, mean-reverting at every horizon
  where the S&P default trends. The loss still reads the 60 rung alone, at its theory value.

  Every rung is PHASE-AVERAGED over its q block offsets since 2026-09-11. Non-overlapping blocks
  have to start somewhere, and on one historical series that choice was worth as much as the
  statistic: the CRSP century reads 1.175 at q=60 from offset zero against a span of 1.057-1.333
  across the 60 offsets, and one observation of phase is the whole difference between the two
  previous vintages of the fixture on the same data. Averaging it away tightened every envelope,
  the 250 rung's cross-section from 0.240-1.564 to 0.474-1.255, so the gate is stricter on
  evidence that did not change. A model ensemble already averages phases across its paths, so the
  model side barely moved.

- **The lag-1 rung is REPORTED, never graded, and it is the one the ladder cannot see.** A
  variance ratio constrains a weighted SUM of the first q-1 autocorrelations, so a world can hold
  vr60 at 1.0 with a positive first term paid for by negatives further out; the clustering rows
  read |r| and are blind to sign. `-validate` now prints the signed lag-1 autocorrelation beside
  the ladder. It is not graded because the record has no one value to grade against: CRSP reads
  +0.047 over the century, +0.023 from 1954 and -0.058 from 1990, and all 18 modern funds revert,
  spanning -0.106 to -0.018. Only 2 of the 39 readings are positive, and both are the long CRSP
  windows. The shipped world reads +0.039, inside the long-window record and above the entire
  modern cross-section. A rule that reads one-day reversal will find the model's sign wrong for
  the modern era and right for the century it is scored on; the numbers the report quotes are the
  fixture's own, checked by the anchor suites so the printed claim cannot drift from the file.

## A basket of names — `-basket`

Forty-five of one consumer's sleeves rank a basket of single names, and a two-asset path cannot
evaluate any of them: cross-sectional dispersion, rank stability, a per-name tail count and a
"decoupling from the sector" gate all read across names. `-basket N` adds N names as observational
second-pass instances of the primary — nothing here reaches a price, and 0 is bit-identical — each
the shared **sector leg** (beta on the primary's observed return plus idio riding the vol state and
the spiral, the satellite's construction) plus its **own idio**, riding the vol state alone, plus
its **own gaps**, a Student-t jump stream of its own. The model has no sector index, so the
basket's equal-weight aggregate *is* the sector, and it is graded against SMH's relation to SPY.

The ruler is a population of real names, not an index (`basket-2026-09-02.tsv`: folio's eight
semiconductor names under SMH, 2012–2026, every number reproducing their published ones). Three
levels, graded when the channel runs:

The record column below is the eight read against **SPY**; the Nasdaq anchor set grades the same
names against QQQ, at its own bands and its own dials (below).

| level | rows | record | model at the anchored dials, 200 × 100 |
|---|---|---|---|
| per name | vol ratio to the primary; sessions past 10% per year | 1.9–3.4×; 0.4–5.1 | 2.49×; 2.13 |
| the aggregate vs the primary | corr; beta; vol ratio | 0.77; 1.56; 2.0× | 0.793; 1.562; 1.97× |
| the cross-section | pairwise corr; idio share; same-day tail coincidence | 0.59; 0.37; 0.48 | 0.575; 0.374; 0.547 |

The mechanism row is what a beta-plus-noise leg cannot pass: pairwise correlation on the primary's
worst decile of days must exceed its middle decile (record 0.60 vs 0.28; model 0.682 vs 0.212),
which the split between shared and idiosyncratic variance produces because only the shared part
rides the spiral. The names' time more than 20% below their running peak is **reported, not
graded**: the eight read 0.08–0.61 because they are names selected today as winners — the
survivorship the fixture discloses — where a name at the sector's drift and 2.5× the index's
volatility spends most of a century below that line (the model reads 0.94), as a real name of that
drift would. Level 1 as a proper population needs point-in-time membership, which no cache here
holds; until then the eight's ranges are the bands and the reading is disclosed.

`-atrelease 0.23.1-basket` names the 0.23.1 world with the basket on at the anchored dials and the
dividend stream at its S&P anchor (`-divyield 2.95`), `0.24.0-basket` the current default with the
same and the macro panel; `-atrelease 0.23.1-nasdaq-basket` and `0.24.0-nasdaq-basket` name the
Nasdaq worlds of the section above with the basket on, graded against the same eight names read
under QQQ instead of SPY. The basket's rows read the same on the 0.24.0 worlds as on their bases:
the names are observational, and the leverage cycle reaches them only through the primary.

**The basket dials do not transport between anchor sets.** The eight correlate more with QQQ
(0.837) than with SPY (0.770), so the shared leg has to carry more and the sector's own noise
less: `-basketsector` falls 1.1 → 0.7 and `-basketbeta` follows its anchor, 1.56 → 1.37. The
higher-volatility primary also wants a lower `-basketidio` (0.9 → 0.85) and more own gaps
(6.0 → 8.0), because the cross-section rows need per-name tails the shared leg cannot supply. The
Nasdaq recipe reads:

| level | statistic | the eight, under QQQ | model |
|---|---|---|---|
| a name | vol ratio to the primary; sessions past ±10%/yr | 2.0×; 1.9 | 2.04×; 3.78 |
| the aggregate vs the primary | corr; beta; vol ratio | 0.84; 1.37; 1.63× | 0.853; 1.371; 1.61× |
| the cross-section | pairwise corr; idio share; same-day tail coincidence | 0.59; 0.37; 0.48 | 0.567; 0.380; 0.537 |

**The gap rate is high by construction, not by dial.** Level 1 grades a name's volatility as a
*ratio* to the primary, and that ratio's anchor is the eight against QQQ over 2012–2026 (20.6%
volatility) while the model's primary is anchored to QQQ over 1999–2026 (24.9% model against 26.9%
real — the dot-com bust is in the second window and not the first). A name at the right ratio is
therefore a fifth more volatile than the eight actually were, and clears 10% correspondingly more
often, and the tail-coincidence row wants more own gaps still. The row passes on the eight's own
range (0.4–5.1, AMD at the top); read the rate as a level, not as a match. The same effect makes
`nameD20` read 0.710 here against 0.548 on the S&P side, and it is reported either way.

### Why the names sit below their peaks, and what `-basketdrift` does about it

The names spend more of their time than the record's do more than 20% below their running peak —
0.548 on the S&P recipe and 0.710 on the Nasdaq, where folio's eight read 0.084–0.610 with a
median of 0.236. That row is **reported, not graded**. What is in it is the **common drift**, and
that is survivorship.

Measured over the eight's own window (`basket-drift-2026-09-03.tsv`, 2012–2026, T = 14.6 years):

| | the eight | the 26-name population |
|---|---|---|
| spread of realized log drift across names | 0.068 | 0.075 |
| noise that a 14.6-year window generates on its own | 0.070 | 0.085 |
| true dispersion left over | **0** | **0** |
| their common drift | +0.304 | +0.229 |

A name's realized drift over a finite window is its true drift plus estimation noise of
sd (idio vol)/√T, so a cross-section is dispersed even when every true drift is identical. For
both groups the observed spread is *below* that floor: over this window, among these names, no
true drift dispersion is detectable at all. And both groups are selected today — survivors — which
truncates the left tail and biases the estimate downward, so 0 is a floor from biased data rather
than a measurement of the world.

The gap that is left is the level: +0.304 a year for the eight against the shared leg's +0.117 over
the same horizon. These are the names that won. Dispersion around the right centre cannot close
that, and the dial shows it — at 200 × 100 the spread of time below peak runs 0.14 (off, pure
estimation noise) → 0.52 at 0.6 → 0.67 at 0.9, while the median moves only 0.528 → 0.557 and the
aggregate's correlation, beta and volatility ratio do not move at all. Raising the centre to +0.304
would close the row and would be calibrating the model to names selected for having won, so it is
not on offer.

Two mechanism properties keep that reading honest, and both are pinned by
`BasketDriftSuite` / `basket_drift_tests`. A name's **own gaps are symmetric**, so the gap channel
imposes no drift of its own: the down-skew belongs to the index, which already reaches every name
through the shared leg (across the eight, own moves past 10% run 41 up to 32 down with mean +0.011,
while SMH itself reads 1 up to 3 down at skew −0.27). And the **drift offsets are centred exactly**,
so turning the dial up moves the cross-section without moving the sector.

So `-basketdrift` ships at 0 and no recipe turns it on. The model agrees with the record there:
run the same decomposition on the model's own names over a 15-year window and the spread of
realized drift is 0.090 against a noise floor of 0.081, a ratio of 1.11 where the eight read 0.97 —
both say estimation noise explains what you see. (The naive floor understates a fat-tailed,
clustered series, which is why the model's ratio sits slightly above 1; it biases both readings the
same way and the record's conclusion is the conservative one.)

### The dial's real use: a null world for a rule that ranks names

At `-basketdrift 0` every name has the same expected drift **by construction** — the sector leg is
shared, and the idio and gap terms have the same mean for each name. So the basket at the shipped
anchor is a **null world** for cross-sectional selection: no name is better than another in
expectation, and any ranking edge a rule shows on it is noise. That is the property that makes it
useful. Set the dial and there is a real edge of known size to find, so the pair answers a question
no single world can:

> how much dispersion must exist, and how much history must a rule see, before its ranking edge is
> distinguishable from luck?

Sweep `-basketdrift` from 0 upward and measure the rule's hit rate at each level; the level where
it separates from its reading at 0 is the rule's detection threshold, and the history it needs
there is its sample-size requirement. This is the basket analogue of `-power`'s question for the
single-series statistics, and it is what the dial is in the binary for. Read `nameD20Spread` in
`channels.basket` to confirm the treatment arm is doing what you set it to.

For the day a point-in-time universe — including the names that left it — can anchor a real value,
the dial is also the mechanism that would carry it. Note that real cross-sectional drift dispersion
arrives mostly through failure and delisting, which a symmetric offset does not model; a future
anchored version will likely need a name that can leave.

**For a rule that reads mean drawdown across names:** the model's level is the unbiased one for
names drifting at the sector's rate, and the record's is not, so a threshold calibrated on real
survivors will sit in the wrong place on simulated names. Calibrate such a flag against its own
rolling quantile rather than an absolute level.

## The macro panel — `-macro`

A consumer's macro-reading rules — a credit gate on BAA10Y's percentile rank, a six-vote
"fragility" score over seven FRED series, a systemic brake — were unevaluable on simulated paths,
and no statistical generator fitted outside the simulator can change that: a VAR, a bootstrap or
a copula couples macro series to prices by assumption, so a rule that reads them scores well
exactly to the degree the assumed coupling is right. `-macro 1` derives nine observables from
the model's **own** state instead — stress, liquidity, the policy rate, crowd positioning and the
leverage cycle already unfold inside it, so the coupling to price is causal by construction —
each the counterpart of one series, in its units. Nothing here reaches a price and 0 is
bit-identical.

| column | counterpart | reads | draws |
|---|---|---|---|
| `macroSpread` | BAA10Y, pp | equity and bond stress: the fast index plus a ~400-session credit cycle, plus a credit-market factor as slow as the level itself | one normal per session |
| `macroSlope` | T10Y2Y, pp | the 10y minus the 2y yield the rate process implies — each the OU-expected average of the short rate over its horizon, decaying to the policy target at `rateSpeed`, the target's inflation term at the regime's mean life and its accommodation at `unwind`, plus a 1.0 pp term premium. The same path prices the bond, so the slope cannot contradict the `bond` column, and it inverts when policy is tight against neutral: derived, never synthesized | none |
| `macroCond` | NFCILEVERAGE, raw index | the leverage cycle's ratio — the borrowing stock over the equity securing it, the drawdown smoothed over 21 sessions — over its mean, plus the trend crowd's capital share over its home | one normal |
| `macroIvol` | VIXCLS, annualized % | the session's conditional sd (vol state, the spiral's amplification discounted to its 21-session average) re-levelled onto the world's realized volatility by the bar channels' `k`, times the record's variance risk premium e^0.28 | one normal |
| `macroYield10` | DGS10, pp | the 10-year yield the slope's long leg already is: the OU-expected average of the short rate over ten years plus the term premium, so `macroSlope` is this less the 2-year and the level cannot contradict it | none |
| `macroCredit` | TOTBKCR/GDP, % | the ratio the two levels below imply, in percent. Its own slow stock, NOT the leverage cycle `macroCond` reads: bank credit grows at the smooth nominal rate output does, plus a deepening term that fades toward a 72% ceiling, minus a paydown at 0.45 a year under full equity stress, plus the borrowing cycle's deviation — and output's excess growth is subtracted, so a depression raises the ratio the way the record's rose in 2020 | none |
| `macroBankCredit` | TOTBKCR, index | the credit stock itself, 100 at the first emitted session. An INDEX: the model has no anchor for the size of its economy, so growth and the ratio are the readable questions and the level is not | none |
| `macroOutput` | GDP, index | nominal output, 100 at the first emitted session: a 2.4%/yr real trend plus 0.12 of the fundamental's excess growth — the fundamental is the model's real activity, and a macro disaster is its depression — carried to nominal by the price level | none |
| `macroPolicy` | DFF, pp | the loop's own policy rate, published as policy publishes it: a target re-set every 32 sessions (8 a year) to the nearest quarter point and held between meetings. The rate itself is the `rate` column, in decimal — a diffusion, because rate uncertainty is what makes stocks and bonds co-move in an inflation regime — so the raw read moves every session where the record's is unchanged on 42% of weekdays. Published, the model holds 98% of sessions and moves a quarter point when it moves. THE ZERO BOUND is the gap that remains, and it is the rate process's rather than the publication's: `easing` caps accommodation and inflation suppresses it, so the model reaches the floor only in brief episodes (0.000 of a century's sessions below 0.5%, 0.011 at the widest path) where the record spent 28% of 1990–2026 there. Its high end transports (0.19 of sessions above 5% against 0.27), and inside a quarter it wanders further, a median 63-session move of 0.25 against 0.13 | none |

Three design decisions carry the rest. **No scale dials**: every consumer vote is a percentile
rank against trailing history or a sign, so a column's scale is invisible to it, and each map is
a literal in its counterpart's units, disclosed as unanchored the way `-ddshape` ships ungated;
the slope, in rate units the model anchors, is the exception and is graded absolutely.
**Lossy by design**: `liq`, `bliq` and `fundamental` remain oracle columns no rule may read; each
noisy member carries a persistent measurement component (an AR(1) — a real spread carries its
own market's factors, which a white error could not mimic without collapsing the level's
autocorrelation of 0.999) sized so no member predicts the forward 60-session return better than
the record's counterparts do, the ORACLE BOUND below. **Publication is the consumer's layer**:
every column is the value an agency would MEASURE that session; release lag, cadence (`macroCond`
is a weekly series) and revisions are applied to an emitted column exactly as to the real one, by
the loader that already owns those rules — the sidecar's `channels.macro` names each column's
counterpart for that routing. `macroCond` is emitted raw because its counterpart is standardized
over the full sample, and a fixed threshold on that published level reads the future.

The ruler is the record itself (`macro-2026-09-06.tsv`: FRED 1990–2026 joined as-of to CRSP and
SPY for the S&P set, to NDX and QQQ for the Nasdaq set), read over the 20% drawdown episodes (on
`ddEpisodes`' spans, excluding an episode whose peak falls inside the first year — no trailing
rank exists there to fire). A rank member *fires* when its trailing-252 percentile rank crosses
90 in the quarter before the peak or during the decline, the slope when inverted in the 18 months
before. Two statistics of that firing. The **firing lag** — sessions from the peak to the first
firing, negative before it, the median over the episodes it fired in — is the mechanism's timing,
and it is invariant to how fast the decline runs; it is what the rows grade. The **warning
share** — the fraction of the peak-to-trough log decline still ahead at that firing, 0 if it never
fires, the median over episodes — is the consumer's number, and it is reported rather than graded
for a reason the record makes plain: at one and the same lag the share reads lower on an index
that runs up harder into its peaks and falls less deep, so it grades the index's price dynamics
as much as the signal's timing (2020: the leverage index fired 44 sessions before both peaks, with
0.82 of the S&P's decline ahead and 0.59 of the Nasdaq's). Model readings at 200 × 100 — the
default gate ensemble, which is also what an emitted path's `channels.macro` carries unless
`-emitgate` or `-paths` changed it — against the record's two references per set:

| row | record, S&P (CRSP / SPY) | model, `0.24.0-macro` | record, Nasdaq (NDX / QQQ) | model, `0.24.0-nasdaq` |
|---|---|---|---|---|
| conditions HAZARD (mechanism): a 20% peak within the next quarter, with the index in its top decile, as a multiple of the unconditional chance; within a year and for 10% dips reported | 2.74 / 2.21; 1.24 / 1.18; 1.64 / 1.27 | **1.68**; 1.39; 1.35 | 2.05 / 1.45; 0.80 / 0.40; 1.11 / 0.91 | **1.47**; 1.23; 1.14 |
| conditions BUILD-UP: mean trailing rank over the quarter before the peak (mechanism: clear of a decoupled 0.48; fidelity: the record's level 0.82–1.00) | 0.94 / 0.94 | 0.92 | 0.92 / 0.82 | 0.84 |
| build-up (reported): spread, implied vol, slope's share inverted | 0.60 / 0.16, 0.41 / 0.41, 0 / 0 | 0.45, 0.64, 0.00 | 0.16 / 0.16, 0.42 / 0.44, 0 / 0 | 0.48, 0.66, 0.00 |
| firing lag, sessions (fidelity): spread, conditions | +7 / +7, −48 / −44 | −6, −63 | +16 / +16, −49 / +101 | −14, −63 |
| firing lag (reported): implied vol, slope | +12 / −12, −37 / −36 | −33, never | +6 / −12, −38 / −37 | −34, never |
| fires in most episodes (reported): spread, conditions | 1.00 / 1.00, 1.00 / 1.00 | 0.96, 0.88 | 1.00 / 1.00, 0.83 / 0.80 | 0.91, 0.84 |
| warning share (reported): spread, conditions, implied vol | 0.83 / 0.92, 0.82 / 0.92, 0.89 / 0.96 | 0.721, 0.705, 0.742 | 0.64 / 0.64, 0.59 / 0.38, 0.83 / 0.83 | 0.611, 0.601, 0.664 |
| persistence at 20 sessions: spread, conditions (4 weekly), implied vol | 0.962, 0.985, 0.771 | 0.912, 0.992, 0.750 | shared | 0.918, 0.991, 0.783 |
| oracle bound, largest predictive R² | ≤ 0.019 | 0.005 | shared | 0.005 |
| slope: share inverted; mean spell (sessions) | 0.115; 42 | 0.130; 482 (reported) | shared | 0.130; 505 |
| implied vol: log premium; R² vs forward realized | 0.28–0.29; 0.55–0.57 | 0.27; 0.19 (reported) | shared | 0.28; 0.30 |

The one genuinely leading signal in the record is the leverage index, and what it leads is a
**hazard**: with NFCILEVERAGE in the top decile of its trailing year, a 20% peak falls within the
next quarter 2.0–2.7× as often as unconditionally on CRSP, SPY and NDX (1.45× on QQQ's four
episodes), fading to ~1.2× at a year and ~1× for 10% dips — leverage concentrates the big peaks,
it does not cause ordinary corrections — and its **build-up**, the mean trailing rank over the
quarter before a 20% peak, is 0.92–0.94: rising into 1998, 2000, 2007, 2018 and 2020 alike and
still rising into the decline, where a decoupled series reads its unconditional level (the null
panel: 0.48). That is not the run-up itself — the trailing-year return's own rank before those
peaks is 0.46–0.78 — and no map of the model's other states reaches it (the valuation gap 0.65
alone, the crowd share 0.54): it needs declines that **follow** leverage, which is what
`-levgain` puts in the price model (on in the default world; the dials section has it). Its
borrowing stock is a damped oscillator with the record's shape — weekly changes autocorrelated
over a quarter, a level that swings over years, rank spells of about four months (the model's
median 11 weeks, the record's 15; the index fires 32% of the time against 26%) — paid down under
stress, and the spiral's gain rises with the stock's growth over its trailing year, the
Schularick–Taylor reading: credit growth is what precedes the crisis. Read on the emitted
`macroCond` with the record's own statistics, the model's index is in its top decile at 0.51 of
its 20% peaks (the record: five of six), its median pre-peak rank is 0.93, and the two rows
grade it — mechanism, the quarter hazard above 1.4× (the four references' floor; the model reads
1.68 on the S&P and 1.47 on the Nasdaq, a decoupled panel 0.94), and fidelity, the build-up at
the record's level (0.92 and 0.84 against 0.82–1.00). The gap that remains — 1.7× against the
record's 2.0–2.7× — is the half of the model's 20% declines that still start from jumps,
disasters and valuation unwinds at any point of the cycle. The firing lags, which are
speed-invariant, hold on both worlds: the record's conditions index leads the peak by about two
months on every reference's classic episodes (QQQ's +101 is a four-episode median with two late
firings) and the model's index is already firing when the quarter before the peak opens (−63,
the lookback's edge); its spread trails the peak by one to three weeks and the model's leads it
by one to three; both lag bands are shared by the two sets, ±40 sessions around the four
references' median. Both recipes pass realism, mechanism and fidelity on four seeds.
The 10% episodes, reported beside the graded rows as `lag10` / `fired10`, put more events behind
the timing on the S&P: the record's leverage index fires 44 sessions before the peak over the
8 fired episodes on each of CRSP and SPY (of 10 and 12), its spread 4 after over 9, and the model
reads −63 and −9, firing in 0.67 and 0.78 of its 10% episodes. On the Nasdaq the 10% set is
mostly not macro events — the record's members fire in 30–56% of them and their lags scatter —
so it corroborates nothing there.

How much of a warning is chance, the null panel says (`-macronull 1` on `0.24.0-macro`, 200 × 100):
a decoupled panel still fires in 0.68 / 0.64 / 0.76 of the 20% episodes (spread / conditions /
implied vol) against the coupled panel's 0.96 / 0.88 / 0.96, at a median lag of −18 / −53 / −31
against −6 / −63 / −33, with 0.60 / 0.54 / 0.68 of the decline ahead against 0.72 / 0.71 / 0.74.
A persistent series that sits in its top decile a tenth of the time crosses somewhere in a
window spanning a quarter before the peak and the decline itself, and a firing lag near −35 is
that geometry's, coupled or not. What separates the coupled panel from its null is the hazard
(1.68 against 0.94) and the build-up (0.92 against 0.48); by the predictive R² the two are barely
apart (0.002–0.005 against 0.001), as the record's own series are (0.0006–0.019) — a hazard
concentrated in a tenth of the sessions is not a forward-return regression. The fired shares are
reported for that reason: they say a member is alive, not that it is coupled. The gap between the
coupled readings and the null's is the size of effect a macro gate has to beat.

What is disclosed rather than graded is what the model does differently. Its implied vol fires a
month **before** the peak (−33 / −36) where the record's VIX is coincident (±12): in the model a
high vol state is a *cause* of declines, in the record VIX is a *response* — the same fact read as
a warning share is the model's lower 0.74 against 0.89–0.96, the cost of firing during the final
run-up rather than a slow member's late arrival. The slope never leads: the model's inversions
are regime-length — 482 sessions against the record's 42 — the curve inverts for the life of an
inflation regime, and nothing in the model makes tightening cause the next downturn. Its implied
vol forecasts its own forward realized vol at R² 0.19 / 0.32 against VIX's 0.55, the
unforecastable jump and cascade share of the model's realized variance (its disclosed kurtosis
budget).

The policy rate is disclosed on its LEVELS, not its timing. Published as a staircase it holds
0.976 of sessions and moves a quarter point when it moves, against a record that holds 0.42 of
weekdays and moves 0.05 — the record's is the EFFECTIVE rate, which carries money-market
increments the model's target has no counterpart for, and DFEDTARU is the closer comparison for
both. Its persistence lands (0.9916 at 20 sessions against 0.9934) and so does its high end (0.19
of a century's sessions above 5% against the record's 0.27). What is missing is the ZERO BOUND:
the record spent 28% of 1990–2026 below 0.5% and the model reaches the floor only in brief
episodes, because `easing` caps accommodation at one easing cycle and inflation suppresses it —
so a vote whose rate leg is a fixed low threshold fires on the record and almost never here, while
a rank leg transports. Its upper tail is held to the record's: an inflation regime's target is
capped 12 points over the mean rate, so a century puts 0.02% of sessions above 20% (the record's
1954–2026: 0.11%, its maximum 22.4%, its longest run above 20% four sessions) and 3.1% above 15%
against 1.6% — the model's high-rate regimes are plateaus of a year or more where the record's
was a spike, so a fixed threshold between 15 and 20 fires here about twice as often. Its firing lag (−30 / −32) reads like the 10-year's and the conditions
index's, all three slow members firing at the lookback's edge.

The credit system is anchored on the record DETRENDED. Bank credit over output rose 43 to 63 over
1990–2026, +0.72pp a year, and no century-long stationary world reproduces a secular rise; that
trend also dominates the raw persistence rows, which is why the ruler carries the same statistics
on the residual (`trend`, `sdDetrend`, `ac52d`, `ac104d`, `lvl10d`, `lvl90d`, `d52sd`). Against
those, on 12 × 100:

| the ratio | model, `0.24.0-macro` | model, `0.24.0-nasdaq` | record, detrended |
|---|---|---|---|
| autocorrelation at a year / two years | 0.72 / 0.42 | 0.78 / 0.53 | 0.75 / 0.47 |
| p10–p90 spread, pp | 7.3 | 7.5 | 6.6 |
| year-over-year change, sd in pp | 1.99 | 1.77 | 2.14 |
| median level, % | 55.9 | 54.8 | 57.3 |

DISCLOSED: nominal growth runs hot. Emitted output grows 6.4%/yr against the record's 4.85 and its
year-over-year growth has an sd of 3.3 against 2.6, and bank credit inherits both (4.97 against
3.05) — the model's price level runs 4.0%/yr where the record's window ran 2.5, and the price
level is anchored in the price model rather than here. A rule reading the RATIO, or real growth,
or a rank of either, is unaffected; one reading a nominal growth threshold is not. DISCLOSED
also: both levels TREND, so their trailing-year rank sits in the top decile almost always — the
`warn`, `prePeak` and `lag` columns of the `macroBankCredit` and `macroOutput` rows read near 1
and say nothing, exactly as the record's own levels would. The ratio is the member to rank.

The gate grades pooled statistics; a consumer runs one path. Over a 40-year path the slope's
inverted share runs from 0 to 0.56 — typically one regime-length spell, against the record's 25
spells of 42 sessions over 37 years — and the oracle bound is an ensemble property: a single
40-year path's forward-return R² on the conditions index reaches about 0.1 (median 0.02), because
the panel is a coupled world by construction. The no-edge comparison for any one path is the
sibling-path pairing below, never the path's own panel. The sidecar carries that width: beside
each pooled macro statistic a `perPath` block gives `[p5, p50, p95]` across the ensemble of the
per-path readings — the hazard, the slope's inversion share, the vol premium and its R², and each
member's forward R², build-up and firing lag — and the report prints one line of it, so a
consumer running one path states the null's width without re-deriving it.

Levels are unanchored and printed for reading only — the median across paths of each 100-year
path's p10 / p50 / p90 on `0.24.0-macro`, the record's in parentheses: spread 1.65 / 2.09 / 2.64
(1.57 / 2.14 / 3.15), implied vol 11.8 / 15.5 / 21.0 (12.2 / 17.6 / 28.5), slope −0.71 / 1.41 /
1.86 (−0.05 / 0.84 / 2.32), conditions −2.04 / −0.15 / 2.31 (−1.03 / −0.24 / 0.74), policy
2.25 / 3.25 / 9.00 (0.09 / 2.91 / 5.69), 10-year 4.65 / 4.94 / 7.62 (1.82 / 4.19 / 6.92). The conditions
index is wider than its counterpart — its p10–p90 spans 4.3 index units against the record's 1.8
— because its drawdown term rises with every decline the path takes; a rule reading a rank over a
trailing window sees none of this. A rule reading a fixed level should know where the level sits:
a threshold inside the record's interquartile range transports roughly (the spread's p10 and p50
are on the record's), one in the upper tail fires less often in the model — its spread tops out
near 3 where the record reached 6 in 2008, and its implied vol's p90 is 21 against 28.5 — because
no map constant creates a tail the world does not have.

**The null is a sibling path — `-macronull 1`.** A no-edge world with the panel's exact marginals
and persistence is another path's panel, and the dial writes one into the path's own file: the
nine columns come from a sibling path — the same world at another seed, its own price loop and
its own measurement stream — so a consumer's loader takes one file per path and the columns'
coupling to that file's `price` is nil. The macro rows do not grade a null panel (its readings,
printed, are the no-edge level) and the sidecar lists its columns in `ungradedChannelSeries` with
`channels.macro.null` true. It costs one extra price loop per path; pairing path *k*'s `price`
with path *j*'s columns from the same ensemble by hand is the same null. This is the distribution
a minimum-detectable-effect calculation runs on — the number that decides whether a macro gate is
worth keeping, given how few stress episodes any record holds.

`-atrelease 0.24.0-macro` names the S&P default with the panel on; `0.24.0-nasdaq`,
`0.24.0-basket` and `0.24.0-nasdaq-basket` are their 0.23.1 bases with the panel and the leverage
cycle — the S&P worlds take the dials 0.24.0 moved from the default, the Nasdaq worlds re-solve
`stress` (4.4) and `jumpVar` (0) for their own depth.

`0.24.1-macro`, `0.24.1-basket` and `0.24.1-nasdaq-basket` are the same set at 0.24.1's world.
**The slow repricing channel is on in the S&P recipes and off in the Nasdaq ones**: those carry
their own dials for their own depth and have not been re-solved against it, so a Nasdaq world
reads 0.24.0's clustering shape. `0.24.1-nasdaq` carries the vol response only, at its own
re-solve — `depth` 10.0, `stress` 4.2, `levGain` 9, `stressAdapt` 0.015, `volResp` 0.008 — where
`0.24.0-nasdaq` runs `depth` 8.4, `stress` 4.4 and `levGain` 8.

**`0.24.2-nasdaq` is the searched Nasdaq**: `0.24.1-nasdaq` re-solved by the calibration search
of the `-worldset` section, with the slow repricing channel's share and scale among the thirty
searched dials, under the inflation regime's ceiling, judged on both markets and picked as the
steadiest of the members that pass every class on four seeds at 200 paths. Every searched dial
moved and the recipe's literals are the archive's own, so `-atrelease 0.24.2-nasdaq` reproduces
the archive member byte for byte. Against `0.24.1-nasdaq`: crashes per century 39.7 → 29.0
(record 25.6), lag-20 clustering 0.16 → 0.19 (0.25), the 60-day variance ratio 0.81 → 0.96, the
record's worst crash at the 14th percentile of the model's 27-year worsts from the 12th; paid in
kurtosis 15.8 → 17.3 (record 9.6), lag-1 clustering 0.32 → 0.33 (0.29) and equity vol 24.0
against 26.9. `jumpVar` is 0: the slow channel carries the long-lag clustering. The Nasdaq anchor
set's spreads are frozen at this world. The un-searched dials are the 0.24.1 recipe's, so it
carries the vol response and the macro panel.

## The vol response to a fall

The record's volatility after a decline **builds** for two to five sessions and stays elevated for
about twenty. Through 0.24.0 the model's fired on the next session and faded, because every
channel that made volatility here responded on the session after the shock. Two mechanisms
adopted in 0.24.1 close most of that gap. The record's rows are `amplifier-2026-09-07.tsv`; the
model's are 400 × 100 at the default seed, which `-paths 400 -years 100` reproduces.

| lag k | 1 | 5 | 20 | 60 |
|---|---|---|---|---|
| abs-r autocorrelation, CRSP century | 0.298 | 0.309 | 0.225 | 0.139 |
| abs-r autocorrelation, model 0.24.0 | 0.325 | 0.272 | 0.188 | 0.077 |
| abs-r autocorrelation, model 0.24.1 | 0.323 | 0.278 | 0.192 | 0.113 |
| corr(r, abs r at k), CRSP century | −0.092 | −0.079 | −0.038 | — |
| corr(r, abs r at k), model 0.24.0 | −0.090 | −0.041 | −0.024 | — |
| corr(r, abs r at k), model 0.24.1 | −0.084 | −0.052 | −0.036 | — |

**`-volresp` supplies the persistence.** A state driven by the session's decline in units of the
conditional sd that generated it, accumulated at 0.992 and attacked at 0.5, multiplies the
diffusive noise. Measuring the decline against the sd that produced it rather than against a
trailing scale is what keeps it from self-exciting, and leaving the accumulation unnormalised is
what makes one decline's response a plateau instead of an integral divided over the sessions that
follow. It needed `-stressadapt`: at the spiral's old 140-session scale, a stretch the response
had genuinely made volatile read as continuous stress and the spiral minted spikes out of it.

**`-slowshare` supplies the shape.** A fifth of the diffusive variance leaves the order-flow
channel and reprices the fundamental and the price together, so it never passes through the
spiral, and its own volatility is long-memoried and asymmetric. The spiral is a fast channel: with
all the variance running through it the profile is too steep, abs-r autocorrelation at lag 20 over
lag 1 reading 0.527 against the record's 0.753. Moving a fifth of the variance to a channel the
amplifier cannot steepen reads 0.594, against 0.578 for 0.24.0.
Only 0.30 of each repricing is permanent — at 1.0 the momentum crowd chases a move nothing
arbitrages, and the 60-day variance ratio reads 1.14 against the record's 1.00 — and the bond
takes the same repricing with the opposite sign, without which the worst equity days have no bond
response and the tail hedge correlation falls to −0.239 against a record of −0.270.

**What is left** is the hump. The record's profile rises ABOVE its own lag 1 at lags 2 to 5 and
the model's does not, so the lag-5 response reads two thirds of the record's rather than the
record's. `-noiseasym` and `-levpersist` are the two dials that move it and both still ship at 0:
the first drives the noise from the session's own draw through a cascade peaking at lag 3 to 5,
and at any setting that closes part of the hump it takes kurtosis past 45 or the downside excess
to half the record's; the second spreads the existing leverage kick but preserves its integral,
so the per-lag amplitude divides and the lag-1 leverage correlation falls to −0.04.

**What it costs.** Equity volatility runs 5% over the record against 3% before, and the return per
unit of volatility 0.94 against 0.96. The volatility fidelity band is 14–18%: at 400 × 100 the
world sits 10.8 seed-sd inside it and no seed of 32 came within a point of the edge, at the 60 × 80
ensemble 3.9 sd. Below that the statistic's own spread, not its level, decides — at 8 × 40 the
band is unreliable for every world, 0.24.0 included.

**What it buys a consumer.** A 5 to 60 session volatility forecast, a vol-targeted sleeve or an
option-priced hedge now reads a world whose volatility decays at roughly the record's rate after a
shock rather than about twice too fast. Ranks and thresholds on the level were unaffected either
way.

## Biases you inherit whatever you choose

These are properties of the model, not of the default, and they do not go away by changing dials.
The ratios are the scoring ensemble's, 200 paths x 100 years averaged over 8 seeds, and `real@` is
from `-noise`: where the real record falls among model histories of that anchor's own length. **The
list is shorter than it was**, because three of the targets it used to describe were not the
statistics the model computes — see the worked example above.

- **The depth rungs read 1.08 / 1.38 / 2.87 against a relation fitted on a window with no
  depression in it — and the index itself sides with the model.** The relation comes from 35 funds
  over 2001-2026; the CRSP index's own shares, measured with the model's definition
  (`depth-shares-2026-08-30.tsv`), run **1.3 / 2.3** times that relation post-1954 and **1.8 /
  4.6** over the century at d10/d20. The model's shipped shares (0.30 / 0.17 of sessions, median
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
  asymmetry channels on top the record sits at the **36th percentile** of model centuries (it was
  the 1st before 0.22.1, the 18th before the cycle): sticky post-crash pessimism deepens the
  declines the disasters start, and since 0.24.0 the leverage cycle's cascades carry a share of
  the century tail the disasters carried alone (with the channel off the loss's tail term rises by
  a fifth where it rose by half). The
  remaining stance is a judgment call the dials expose: the record is one draw, other national
  markets' centuries ran deeper, and `-disasterrate 0` removes the channel bit-for-bit if you want
  the disaster-free counterfactual.
  Ruin rates for levered sleeves computed off an ensemble minimum remain **upper bounds, not
  estimates**: that minimum is drawn from 20,000 market-years and no fund lives that long.
  This is a DRAWDOWN, accumulated over sessions; the worst single SESSION is a separate question and
  is set by `-haltlimit` rather than by a numerical constant.
- **A century path is mildly trending (vr60 1.08), because its disasters and its valuation epochs
  are.** The variance-ratio anchor (1.00, envelope 0.50-1.20) was measured on modern, disaster-free
  windows; the CRSP century itself — the window with 1929-32 in it — reads **1.175** in the
  committed persistence fixture, and its five-year VR is era-split beyond use as an anchor
  (`longhorizon-2026-08-30.tsv`: 0.730 for the century, 1.152 post-1954, ±30-40% block noise on
  both). A multi-year collapse IS signed serial dependence; the model without these channels read
  1.00 on century paths, which real disaster-bearing centuries do not.
- **The bond's crash rally is on its anchor since the settled-stress refuge** (1.00 against the
  record's median across its growth-shock drawdowns; it read 1.39-1.48 through 0.23.0's own
  development). The model's bond gains 6.6% where the record's median is 6.6% and its best was
  22.4%. Size a refuge sleeve on the distribution rather than on this row, which `-noise` puts the
  record at the 37th percentile of.
- **The bond spends longer below its peak than its own volatility implies** (1.31). This is the one
  bond row with a band fitted across eight real funds rather than one, so it is the most transportable
  of them — and it is also the one the `-crossasset` ladder grades at four durations.
- **Every session past −18% comes from the liquidity spiral**, in every world measured — a news
  jump is −3.3% and cannot reach that range alone. The jump channel sets how OFTEN those sessions
  arrive; it does not set how large they are. If you are studying tail magnitude, `-stress` and
  `-depth` are the dials, not `-jumpvar`.
- **Crash frequency is on its anchor** (0.97; 20.0 per century against a real 20.7 — the disaster
  channel consolidated episodes and the valuation cycle's slow epochs finished the job; the news
  channel's episode inflation is displaced back out of the noise budget). The record sits at the
  54th percentile of model histories. Since 0.24.0 about half of the 20% declines start where the
  leverage cycle is fragile (the panel's hazard row), the rest from jumps, disasters and valuation
  unwinds at any point of it.
- **Single-history kurtosis is wild here and the median sits just under anchor** (0.96 on the
  scoring ensemble, 0.93 at the default seed — the leverage cycle's cascades, the rarer-larger jump
  pair and the leverage kick split the tail budget that was all jumps before). A 72-year window
  reads 10.1 at the 5th percentile and 94 at the 95th, because a window either contains its 1987
  or does not. Do not read kurtosis off one path — and the 30 realism ceiling is still what binds
  when you reach for tails, not the target.
- **The three asymmetries are on anchor since 0.23.0's adoption** (downside excess 1.07, leverage
  corr 1.02, tail hedge 0.96 at the default seed), and `-noise` reads the record as a typical
  single history of this model on most rows (vol 52nd, crashes 54th, median depth 58th, variance
  ratio 54th, valuation dispersion 45th, the asymmetries 46th/51st/39th). The rows where it can
  still tell the difference are the release's disclosed misses: clustering lag 20 (78th — 0.19
  against 0.225; the leverage cycle's cascades are sharper than the spiral's alone, and the
  stock's paydown rate is where that trades against kurtosis) and the depth rungs d10/d20
  (39th/35th).

## If you are calibrating rather than choosing

`-calibrate N` random-searches thirty parameters against the fitness loss and reports the best few
re-scored on a held-out seed. It prints; it does not modify defaults. Note that the loss has no
notion of *not breaking what is already right* — it will happily spend an accurate row to improve an
inaccurate one, so read the whole fidelity table after any recalibration, not just the loss. For
calibration work proper, the archive search of the `-worldset` section above is the tool: it gates
feasibility instead of pricing it, scores only the excess past each row's own sampling error, and
keeps a set rather than a champion.

The current default is not the search's own optimum and does not need to be. Two things the loss
cannot see were corrected by hand when 0.21.0 was calibrated: the search drove `-crowdimpact` to its
range floor, which switches the reflexive channel off — visible only as `cflow` collapsing to 0.9%
of the noise term, not as a worse loss — and it raised `-refuge` until bond volatility left its
band. Read the binding diagnostics and the whole fidelity table after any recalibration, not just
the loss.

**Score candidates on more than one seed.** A single-seed refinement here found a world scoring
1.687 that was a 2.15 median across five seeds. Depth-rung agreement is especially cheap to overfit,
because the relation those rungs are graded against moves with the sample it is evaluated on.

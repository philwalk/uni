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

Run `market_sim.exe -badflag` for the full flag list with each default.

## Verifying what produced your data

Two checks, with different jobs and different lifetimes. Use both.

**Before invoking — `-version`.** Prints the release the binary was built from, alone on stdout,
exit 0. Nothing to parse, so a caller can assert on it without depending on any other output:

```
[ "$(market_sim.exe -version)" = "0.21.0" ] || { echo "wrong simulator" >&2; exit 1; }
```

This catches the wrong binary, and it is the only check available *before* you spend the run. It
stops working the moment you are handed a file instead of a command.

**After the fact — the sidecar.** `-emit F` writes `F.json` beside the TSV, and that is the only
provenance that survives the file being moved. Four fields answer four different questions:

| field | question |
|---|---|
| `schema` | can I parse this file? |
| `version` | which release's simulator wrote it? |
| `world` | with which parameters? |
| `gate` | was that world even admissible? |

**`schema` and `version` do not substitute for each other.** The default world moved at 0.19.1 and
again at 0.19.2, so two files with identical columns and identical `schema` can still be
incomparable. `schema` went 1 → 2 when `version` was added, which makes the absence of `version`
detectable rather than ambiguous — **schema-1 files exist**, so read `schema` first and treat a
missing `version` as "schema 1", not as a malformed file. `schema` went 2 → 3 at 0.21.0, when the
`world` block gained `jumpVar` and `jumpRate`: a reader that reconstructs a `World` from a schema-3
sidecar using schema-2 field names gets one with no tail channel and no error.

**A version check alone does not pin behaviour.** A run made with `-depth 0.5` or a non-default
`-crowd` looks like a default run to any version check. A consumer that calibrated against the
default world should compare the sidecar's `world` block field-by-field against what it calibrated
on; that catches both a moved default and a deliberate flag, which no label can. And a file whose
`gate` records a failure is not evidence about a market — read the verdicts rather than assuming
them, remembering they come from an `-emitgate` ensemble (default 200 paths), not from the single
emitted sample.

**Do not let `PATH` choose the binary for a pinned consumer.** `~/.cargo/bin/market_sim.exe` is
whichever version was installed last, and on Windows a running executable cannot be replaced in
place, so a failed reinstall silently leaves the previous one there. Install to a versioned root and
invoke the absolute path, so the path itself carries the assertion:

```
cargo install vastblue-uni@0.21.0 --example market_sim --root ~/.local/uni-0.21.0
~/.local/uni-0.21.0/bin/market_sim.exe -version
```

Exe-versus-library mismatch is not a risk — the example links the library from the same crate. The
only mismatch is the exe against what the consumer expected.

## Generating an ensemble

`-emitall` writes every path of the run from one invocation, to `F-000.tsv`, `F-001.tsv`, … each
with its sidecar. Prefer it to a per-path invocation loop: it pays the process start, the report
ensemble and the 200-path gate ensemble once rather than per path, which is worth about 5x
(~47 ms/path against ~245 ms at 33 years). The remainder is the cost of formatting a path's eight
columns, which no batching removes.

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
own sample to be the judge and therefore gives each chunk its own, smaller, noisier verdict. And the
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
recalibration, the jump channel and the anchor corrections each shifted it, in both directions. The
equity crowd reaches the bond leg through the correlation channel, so a bond dial settled once is not
settled. The shipped 0.060 — 6.0 rate points, the middle of the three real cycles — reads d=5.70 at
0.68–0.73 and d=13.50 at 1.28–1.29 across five seeds, four PASS and one EDGE on the floor.
**Re-run `-crossasset` after any world change, not only after a bond one.**

**The calibration loss cannot see this rung.** `fitness` scores a single `WorldStats` and the ladder
re-simulates at other durations, so a re-search optimises the bond depth relation at the *shipped*
duration — where it reads 1.22, comfortably in band — and is free to spend every other rung. The
0.21.0 search duly proposed an `inflSize` cut that pushed d=5.70 clear of the line, the same cut
0.20.0's search proposed and that was reverted then for the same reason. **Run `-crossasset` after
any recalibration — and after any world change at all, equity-side included; the loss will
not warn you.**

**The ladder is rotated, not shifted, and that is now measured rather than asserted.** Every dial
that lifts d=5.70 also pushes d=13.50 toward its 1.35 ceiling. At the 0.21.0 world the admissible
`easing` window was roughly 0.050–0.056: 0.046 put the short rung on its floor, 0.058 put the long
rung on its ceiling. At 0.22.0's world it sits near 0.058–0.064, and it is narrow enough that no
single value clears all five seeds with margin — the two rungs' seed spreads are each about
±0.05 and they move in opposite directions. Re-derive it rather than assuming it, and expect one
seed to sit on an edge.

The acceptance gate applies the same refusal: `-validate` prints an anchor-fitted band it cannot
grade as `n/a` with the reason (and the sidecar records it under `gate.fidelityUnanchored`),
instead of failing a world for sitting where the anchor funds have no data.

Cost: the full report runs ~18 ensembles at the invocation's `-paths`/`-years` — four ladder rungs
plus a bisection solve for the equity section — so scale `-paths` down for a quick look.

## How a decline is delivered — `-ddshape`

Depth is not shape. `-ddshape` reports median decline duration, recovery duration, time underwater
and **worst-day share** — how much of a peak-to-trough decline arrives in its single worst session —
at the 10% and 20% thresholds, against SPY 1993–2026.

It uses a **second episode definition on purpose**: the model's own crash count is a 15%-below-peak
excursion that re-arms at 2%, built for counting; these are peak-to-trough-to-full-recovery
episodes, built for shape. Do not mix the two.

Nothing in it is gated. The reference is one history — 12 episodes at 10%, four at 20% — and a band
off four episodes could not fail.

At the shipped world, 10% declines take 1.42× as long as SPY's and deliver 0.61× as much of
themselves in their worst session; at 20% they are 0.34× as long and recover 0.34× as fast.
**Kurtosis is not the explanation** — it has sat on its anchor since 0.21.0 while the worst-day ratio
barely moved. Read the worst-day column beside the decline column: a decline that grinds twice as
long dilutes its worst session by construction. The finding is about duration, not tails.

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
| `-stress` | liquidity-spiral gain: how much a run of down days amplifies later moves | 5.37 |
| `-depth` | market depth; price impact scales as `12/depth`, so higher = calmer | 17.4 |
| `-drift` | fundamental drift per year; no dividend, so this IS total return | 0.113 |
| `-fundvol` | fundamental volatility per year — sets time under water, not daily return scale | 0.070 |
| `-jumpvar` | share of the equity shock's **variance** carried by jumps rather than diffusion; 0 turns the tail channel off | 0.17 |
| `-jumprate` | jumps per session at average volatility; with `-jumpvar` it sets the **size** | 0.0050 |
| `-trendshare` | mandate level for trend-following capital (a spring, not a wall) | 0.055 |
| `-crowdimpact` | price pressure per unit of exposure the crowd **trades** in a session — one rule for every crowd | 0.030 |
| `-value` | pull toward fair value per day. With the drag below, this governs **shallow** water only | 0.045 |
| `-recoverydrag` | how fast value arbitrage weakens as a drawdown deepens past 10%; 0 restores 0.20.0's symmetric pull | 10.0 |
| `-recoveryfloor` | weakest that pull may become, as a share of full strength | 0.10 |
| `-anchors` | which real index the **equity** fidelity targets describe: `sp500` or `nasdaq` | sp500 |
| `-easing` | **cap** on the policy rate cut under equity stress, in rate points — an anchor, not a fitted number: one full real easing cycle | 0.060 |
| `-unwind` | how fast that cut is withdrawn, per year (0.35 is a ~2-year half-life) | 0.35 |
| `-refuge` | flight-to-quality bid into the bond, scaled by its duration | 0.11 |
| `-inflsize` | size of an inflation regime's rate-pressure target | 0.10 |

`-easing` is a cap, not a speed. The flag it replaced, `-flight`, was a cut *rate* per year, so it
is rejected rather than reinterpreted: no `-flight` value carries over. Together these two are what
makes the bond a refuge, but they are not load-bearing in the same way, and the difference is worth
knowing before turning either off.

**`-refuge` is necessary.** At `-refuge 0` the bond stops rallying in crashes at all (growth-crash
−0.04x real) and the mechanism check "bonds rally in growth shocks" FAILS.

**`-easing` is not, and it no longer earns its place on the objective either.** At `-easing 0` the
world still passes realism, mechanism *and* fidelity; the bond depth row is *better* (1.00 against
the default's 1.25, where 1.00 is the target) and the fitness loss is *lower*, 1.302 against 1.334.
What easing buys is the crash rally (growth-crash 0.42 → 0.50) and the `-crossasset` ladder, which
no version of the loss can see: easing is the dial that lifts the short-duration depth rung off its
floor. If the question is bond drawdown at the shipped duration specifically, `-easing 0` is the
better world and the default is not.

## Every dial moves several things at once

This is the part to read before changing anything. Columns are **ratios to the real anchor**, so
1.00 is on target. Starred rows are the defaults.

```
setting                      vol  kurt  clus    vr crash   d10  bdep   r/v  tshare  cflow  gate
---------------------------------------------------------------------------------------------
DEFAULT                     1.00  0.99  0.99  1.00  1.13  1.02  1.26  1.00   0.20   3.59  P/P/P

-stress 2.0                 0.81  0.44  0.58  0.97  0.55  1.33  1.00  1.24   0.20   3.35  P/P/F
-stress 5.37 *              1.00  0.99  0.99  1.00  1.13  1.02  1.26  1.00   0.20   3.59  P/P/P
-stress 8.0                 1.26  1.75  1.39  1.02  1.61  0.89  1.36  0.80   0.20   3.70  F/P/F

-depth 12                   1.33  1.24  1.08  0.94  1.86  0.81  1.28  0.75   0.20   4.22  F/P/F
-depth 17.4 *               1.00  0.99  0.99  1.00  1.13  1.02  1.26  1.00   0.20   3.59  P/P/P
-depth 22                   0.86  0.73  0.94  1.05  0.82  1.30  1.25  1.17   0.21   3.27  P/P/F

-drift 0.08                 1.01  0.99  0.99  0.99  1.15  1.13  1.28  0.69   0.20   3.64  P/P/F
-drift 0.113 *              1.00  0.99  0.99  1.00  1.13  1.02  1.26  1.00   0.20   3.59  P/P/P
-drift 0.15                 0.99  0.96  0.99  1.01  1.06  1.02  1.25  1.36   0.21   3.52  P/P/F

-fundvol 0.041              0.99  0.95  0.98  0.94  1.03  0.84  1.26  1.01   0.20   3.63  P/P/P
-fundvol 0.070 *            1.00  0.99  0.99  1.00  1.13  1.02  1.26  1.00   0.20   3.59  P/P/P
-fundvol 0.10               1.01  1.00  0.99  1.11  1.23  1.34  1.26  0.99   0.21   3.55  P/P/P

-jumpvar 0                  1.01  0.42  1.07  0.98  1.17  1.05  1.28  0.99   0.20   3.74  P/P/P
-jumpvar 0.17 *             1.00  0.99  0.99  1.00  1.13  1.02  1.26  1.00   0.20   3.59  P/P/P
-jumpvar 0.23               1.00  1.37  0.96  1.01  1.10  1.05  1.26  1.01   0.20   3.53  F/P/P

-jumprate 0.002             0.99  1.39  0.96  1.02  1.09  1.03  1.26  1.01   0.20   3.58  F/P/P
-jumprate 0.0050 *          1.00  0.99  0.99  1.00  1.13  1.02  1.26  1.00   0.20   3.59  P/P/P
-jumprate 0.012             1.01  0.80  0.97  1.00  1.12  1.00  1.24  1.00   0.20   3.62  P/P/P

-haltlimit 0                1.00  1.00  0.99  0.99  1.13  1.02  1.27  1.00   0.20   3.59  F/P/P
-haltlimit 0.25 *           1.00  0.99  0.99  1.00  1.13  1.02  1.26  1.00   0.20   3.59  P/P/P
-haltlimit 0.40             1.00  1.00  0.99  0.99  1.13  1.02  1.27  1.00   0.20   3.59  F/P/P

-trendshare 0.02            1.00  0.98  0.98  0.99  1.12  1.02  1.26  1.01   0.18   3.18  P/P/P
-trendshare 0.055 *         1.00  0.99  0.99  1.00  1.13  1.02  1.26  1.00   0.20   3.59  P/P/P
-trendshare 0.30            1.02  0.99  1.03  1.05  1.23  1.03  1.29  0.98   0.37   6.47  P/P/P

-crowdimpact 0.010          0.99  0.96  0.96  0.96  1.06  1.03  1.24  1.02   0.20   1.20  P/P/P
-crowdimpact 0.030 *        1.00  0.99  0.99  1.00  1.13  1.02  1.26  1.00   0.20   3.59  P/P/P
-crowdimpact 0.12           1.10  1.10  1.19  1.18  1.59  1.03  1.36  0.91   0.21  14.38  F/P/F

-value 0.020                1.01  1.00  1.01  1.10  1.15  1.26  1.30  1.00   0.20   3.54  P/P/P
-value 0.045 *              1.00  0.99  0.99  1.00  1.13  1.02  1.26  1.00   0.20   3.59  P/P/P
-value 0.070                1.00  0.92  0.96  0.95  1.08  0.94  1.24  1.01   0.20   3.63  P/P/P

-recoverydrag 0             0.98  0.91  0.93  0.82  1.08  0.87  1.19  1.03   0.20   3.65  P/P/P
-recoverydrag 10.0 *        1.00  0.99  0.99  1.00  1.13  1.02  1.26  1.00   0.20   3.59  P/P/P
-recoverydrag 20            1.00  1.00  0.99  1.01  1.14  1.05  1.27  1.00   0.20   3.59  P/P/P

-recoveryfloor 0.05         1.00  0.98  0.99  1.03  1.11  1.10  1.27  1.00   0.20   3.59  P/P/P
-recoveryfloor 0.10 *       1.00  0.99  0.99  1.00  1.13  1.02  1.26  1.00   0.20   3.59  P/P/P
-recoveryfloor 0.50         0.99  0.94  0.95  0.91  1.12  0.86  1.23  1.02   0.20   3.62  P/P/P
```

60 paths x 100 years, seed 20260813. `vol` equity volatility · `kurt` daily kurtosis ·
`clus` volatility clustering (lag 1) · `vr` the 60-session variance ratio, where 1.00 is no signed
serial dependence and the band is 0.50–1.15 · `crash` crashes per century · `d10` time spent >10% below
peak, against what real equity funds of the same volatility and return spend · `bdep` the bond's
equivalent, against what its own volatility implies · `r/v` return per unit volatility ·
`tshare` **realized** trend-follower share · `cflow` crowd flow, bp/session ·
`gate` realism/mechanism/fidelity.

Six things that table is trying to tell you:

- **`crowdimpact` is a mechanism dial again, not a trend dial.** Since 0.22.0 the crowd's price
  pressure comes from the exposure it TRADES in a session rather than the exposure it holds, so
  running it harder no longer manufactures drift. `-crowdimpact 0.12` is four times the default and
  reaches 14.6 bp/session of crowd flow — 21% of the noise term — while `vr` only reaches 1.19. Under
  the old law the shipped default was already at 1.52 on 4.7 bp. What running it hard DOES buy is
  crash frequency (1.60) and clustering (1.19), which is why the default is not there.
- **`stress` is not a volatility dial.** It is one amplifier producing volatility, fat tails *and*
  volatility clustering together. Raising it 5.37 → 8.0 takes volatility from 1.00 to 1.27 and
  clustering from 1.03 to 1.43 — and fails the realism gate. You cannot buy tails *here* without
  buying clustering.
- **`recoverydrag` and `value` are one pair, and neither reads correctly alone.** The base pull
  governs shallow water; the drag governs deep drawdowns. Turn the drag off and leave the pull at its
  shipped 0.045 and `d10` collapses to 0.83. Weaken the pull instead and `d10` runs to 1.31. Both
  also move `vr` — the drag off reads 0.81, the weak pull 1.11 — so a world tuned for time under
  water on this pair alone lands its serial persistence somewhere it did not choose.
- **`jumpvar` and `jumprate` are ONE dial with two handles, and that is what makes the tail
  reachable.** `-jumpvar 0` is this world with the tail channel off and nothing else moved: the jump
  draws come from their own RNG stream, so no other statistic shifts by construction. Turning it on
  moves `kurt` 0.42 → 0.99 at the shipped pair. **The ceiling is the realism band, not the target**,
  and the band is what `jumprate` buys headroom against: at the old rate of 0.0010, `-jumpvar 0.12`
  passed at the default seed and read kurtosis 35.2 on another, against a ceiling of 30. Raising the
  rate to 0.0030 means the same jump variance arrives as more, smaller jumps, so no seed runs away —
  the five tried read 19.5 to 27.6 — and the *median* kurtosis rises rather than the extremes.
  Rarer jumps are not fatter tails; they are noisier ones. Set the pair by the band, on more than one
  seed.
- **`fundvol` is the dial for time under water, and it is not free.** Raising `-fundvol` 0.041 →
  0.10 moves `d10` from 0.87 to 1.34 while volatility and return-per-volatility sit still — the
  fundamental accumulates into drawdown depth without reaching daily return scale. It pays in trend:
  `vr` goes 0.94 → 1.11. The shipped 0.070 is where the two meet, and it is the dial that carries the
  drawdown rungs now that the crowd no longer does.
- **The two bond dials are almost orthogonal to the equity leg, but the reverse is not true.**
  `-easing` and `-refuge` move `bdep` and leave volatility, kurtosis and clustering untouched. The
  equity world nonetheless moves the bond ladder through the correlation channel — 0.22.0 had to
  re-solve `easing` twice while the equity side settled. `-crossasset` is the only thing that shows
  this; run it after any change, not only a bond one.
- **Some settings leave the admissible region.** `-stress 8.0` and `-jumpvar 0.16` fail realism
  (clustering and kurtosis); `-depth 12`, `-depth 22`, `-drift` at either end and `-crowdimpact 0.12`
  fail only fidelity — the level of one quantity stops being readable. Always re-run `-validate`
  after changing a dial.

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
24.9 (1954-2026) and was left alone; `worst crash %` −56.8 is the same 2007-09 episode the control
reads at −54.6%. Two of the five episode-family anchors survived; three did not.

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

Realism PASS, mechanism PASS, fidelity PASS on every seed tried, with margin rather than on a band
edge — `equity d10 vs real` reads 0.76–0.78 against a 0.70 floor and the seed spread is ±0.01.
Against the QQQ anchors: volatility 0.98, return per volatility 1.04, median depth 1.28, worst crash
1.16, depth rungs 0.88 / 0.77 / 0.71.

**Read what it passes with.** Two large fidelity misses are disclosed rather than gated: kurtosis
1.98 and crashes per century 1.78. Both are the volatility-to-crash elasticity gap — the model's
1.44 against a real cross-section of about 0.50 — surfacing at Nasdaq volatility. Clustering also
sits at 0.378 against its 0.40 realism ceiling. This world is admissible for **relative** work at
Nasdaq-like volatility; it is not a calibrated Nasdaq, and a per-crash hazard read off it is
over-sampled by nearly a factor of two.

Two things about the Nasdaq set are carried over rather than measured, and are marked as such in the
code: its sampling spreads (an `sdRel` is model-implied, so an honest set needs `-noise -anchors
nasdaq` at a Nasdaq-calibrated world, which does not exist yet) and the two fidelity bands.

## Biases you inherit whatever you choose

These are properties of the model, not of the default, and they do not go away by changing dials.
The ratios are the scoring ensemble's, 200 paths x 100 years averaged over 8 seeds, and `real@` is
from `-noise`: where the real record falls among model histories of that anchor's own length. **The
list is shorter than it was**, because three of the targets it used to describe were not the
statistics the model computes — see the worked example above.

- **The deep drawdown rung runs long** (1.89 against the relation, where the 5% and 10% rungs sit at
  0.97 and 1.06). Rules keyed to a *deep* distance from peak inherit it; the shallow ones are
  accurate. Its band is deliberately absent and its weight is 0.1, because one 25-year record barely
  pins it — `-noise` puts the record's own reading anywhere between 0.06 and 3.79 — and no setting
  fixes it without giving back the shallow rungs: `-recoveryfloor 0.40` takes it down and `d10` to
  0.89 with it.
- **The worst crash is overstated** (1.60; index paths near −91% against a real −56.8%, and mostly a
  horizon artifact — at the anchor's own 72 years the record sits mid-distribution, per `-noise`). No
  levered fund survives those, so ruin rates for levered sleeves are **upper bounds, not estimates**.
  This is a DRAWDOWN, accumulated over sessions; the worst single SESSION is a separate question and
  is set by `-haltlimit` rather than by a numerical constant.
- **The bond's crash rally is overstated** (1.39 against the record's median across its growth-shock
  drawdowns). This reversed in 0.22.0 and the reversal is a correction, not a change in the model:
  the old target was 2008 alone, the best of five, so the same behaviour was reported as a shortfall.
  The model's bond gains about 9% where the record's median is 6.6% and its best was 22.4%. Size a
  refuge sleeve on the distribution rather than on this row, which `-noise` puts the record at the
  22nd percentile of.
- **The bond spends longer below its peak than its own volatility implies** (1.21). This is the one
  bond row with a band fitted across eight real funds rather than one, so it is the most transportable
  of them — and it is also the one the `-crossasset` ladder grades at four durations.
- **Every session past −18% comes from the liquidity spiral**, in every world measured. The jump
  channel sets how OFTEN those sessions arrive; it does not set how large they are. If you are
  studying tail magnitude, `-stress` and `-depth` are the dials, not `-jumpvar`.
- **Crashes arrive slightly too often** (1.07; 22 per century against a real 20.7). The record sits
  at the 35th percentile of model histories, which is inside what the anchor can distinguish from a
  draw.
- **Single-history kurtosis is wild here and the median is on anchor** (0.94 on the scoring ensemble,
  0.99 at the default seed). A 72-year window reads 9.8 at the 5th percentile and 121 at the 95th,
  because a window either contains its 1987 or does not. Do not read kurtosis off one path. Note also
  that a world sitting on the 28.0 anchor is close to the 30 realism ceiling by construction, so the
  band is what binds when you reach for tails, not the target.
- **Time under water, crash depth, crash frequency and serial persistence are all on anchor**
  (`d5` 0.97, `d10` 1.06, median depth 1.03, crashes 1.07, variance ratio 1.02, clustering 0.96/0.95),
  and `-noise` puts the real record between the 35th and 70th percentile of model histories on every
  one of them. That is the stronger statement: the anchors can no longer tell this model from the
  record they were measured from.

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

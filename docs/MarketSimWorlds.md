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
"world": { "trendShare": 0.055000, "depth": 16.940000, "stress": 5.370000, ... }
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
(2007-08, 2001-03, 1989-92) where 0.046 is 4.6. The ladder now reads `PASS` on five of six seeds,
with d=5.70 at 0.71 and d=13.50 at 1.29.

**The calibration loss cannot see this rung.** `fitness` scores a single `WorldStats` and the ladder
re-simulates at other durations, so a re-search optimises the bond depth relation at the *shipped*
duration — where it reads 1.24, comfortably in band — and is free to spend every other rung. The
0.21.0 search duly proposed an `inflSize` cut that pushed d=5.70 clear of the line, the same cut
0.20.0's search proposed and that was reverted then for the same reason. **Run `-crossasset` after
any recalibration; the loss will not warn you.**

**The ladder is rotated, not shifted, and that is now measured rather than asserted.** Every dial
that lifts d=5.70 also pushes d=13.50 toward its 1.35 ceiling. On `easing` the admissible window is
roughly 0.050–0.056: at 0.046 the short rung reads 0.66 and edges on its floor, at 0.058 the long
rung reads 1.34 and edges on its ceiling. There is a middle, but it is narrow — do not expect margin
on both ends, and re-check both rungs after moving any bond dial.

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
| `-depth` | market depth; price impact scales as `12/depth`, so higher = calmer | 16.94 |
| `-drift` | fundamental drift per year; no dividend, so this IS total return | 0.113 |
| `-fundvol` | fundamental volatility per year — sets time under water, not daily return scale | 0.041 |
| `-jumpvar` | share of the equity shock's **variance** carried by jumps rather than diffusion; 0 turns the tail channel off | 0.10 |
| `-jumprate` | jumps per session at average volatility; with `-jumpvar` it sets the **size** | 0.0010 |
| `-trendshare` | mandate level for trend-following capital (a spring, not a wall) | 0.055 |
| `-crowdimpact` | how hard the crowd's trading pushes price — the reflexive channel's strength | 0.07 |
| `-value` | pull toward fair value per day. With the drag below, this governs **shallow** water only | 0.045 |
| `-recoverydrag` | how fast value arbitrage weakens as a drawdown deepens past 10%; 0 restores 0.20.0's symmetric pull | 10.0 |
| `-recoveryfloor` | weakest that pull may become, as a share of full strength | 0.10 |
| `-anchors` | which real index the **equity** fidelity targets describe: `sp500` or `nasdaq` | sp500 |
| `-easing` | **cap** on the policy rate cut under equity stress, in rate points — an anchor, not a fitted number: one full real easing cycle | 0.052 |
| `-unwind` | how fast that cut is withdrawn, per year (0.35 is a ~2-year half-life) | 0.35 |
| `-refuge` | flight-to-quality bid into the bond, scaled by its duration | 0.11 |
| `-inflsize` | size of an inflation regime's rate-pressure target | 0.10 |

`-easing` is a cap, not a speed. The flag it replaced, `-flight`, was a cut *rate* per year, so it
is rejected rather than reinterpreted: no `-flight` value carries over. Together these two are what
makes the bond a refuge, but they are not load-bearing in the same way, and the difference is worth
knowing before turning either off.

**`-refuge` is necessary.** At `-refuge 0` the bond stops rallying in crashes at all (growth-crash
0.03x real) and the mechanism check "bonds rally in growth shocks" FAILS.

**`-easing` is not.** At `-easing 0` the world still passes realism, mechanism *and* fidelity, and
the bond depth row is *better* — 0.93 against the default's 1.24, where 1.00 is the target. Easing
earns its place on the objective rather than on the gate: it buys the two bond crash responses
(growth-crash 0.34 → 0.42, inflation-crash 0.87 → 0.96) for a fitness loss of 3.27 → 2.76, and pays
with the depth row and a little crash rate. If the question is bond drawdown specifically,
`-easing 0` is the better world and the default is not.

## Every dial moves several things at once

This is the part to read before changing anything. Columns are **ratios to the real anchor**, so
1.00 is on target. Starred rows are the defaults.

```
setting                      vol  kurt  clus crash   d10  bdep   r/v tshare  cflow  gate
-----------------------------------------------------------------------------------------
DEFAULT                    1.04  0.99  1.06  1.13  1.03  1.21  0.97   0.21   4.74  P/P/P

-stress 2.0                0.80  0.45  0.56  0.55  1.19  0.93  1.25   0.21   3.96  P/F/F
-stress 5.37 *             1.04  0.99  1.06  1.13  1.03  1.21  0.97   0.21   4.74  P/P/P
-stress 8.0                1.38  2.06  1.45  1.46  0.86  1.41  0.73   0.21   5.46  F/P/F

-depth 12                  1.38  1.55  1.16  1.74  0.78  1.27  0.73   0.21   5.56  F/P/F
-depth 16.94 *             1.04  0.99  1.06  1.13  1.03  1.21  0.97   0.21   4.74  P/P/P
-depth 22                  0.88  0.70  1.00  0.81  1.25  1.20  1.14   0.21   4.25  P/P/P

-drift 0.08                1.06  0.98  1.06  1.18  1.05  1.23  0.67   0.21   4.69  P/P/F
-drift 0.113 *             1.04  0.99  1.06  1.13  1.03  1.21  0.97   0.21   4.74  P/P/P
-drift 0.15                1.03  0.91  1.06  1.04  1.11  1.20  1.31   0.21   4.88  P/P/F

-fundvol 0.02              1.03  0.93  1.05  1.08  0.95  1.20  0.98   0.21   4.62  P/P/P
-fundvol 0.041 *           1.04  0.99  1.06  1.13  1.03  1.21  0.97   0.21   4.74  P/P/P
-fundvol 0.10              1.06  0.96  1.07  1.33  1.50  1.26  0.95   0.21   5.36  P/P/P

-jumpvar 0                 1.05  0.52  1.13  1.16  1.00  1.23  0.96   0.21   4.78  P/P/P
-jumpvar 0.10 *            1.04  0.99  1.06  1.13  1.03  1.21  0.97   0.21   4.74  P/P/P
-jumpvar 0.16              1.02  1.71  1.02  1.11  1.06  1.21  0.98   0.21   4.72  F/P/P

-jumprate 0.0004           1.04  0.91  1.08  1.12  1.00  1.23  0.97   0.21   4.72  P/P/P
-jumprate 0.0010 *         1.04  0.99  1.06  1.13  1.03  1.21  0.97   0.21   4.74  P/P/P
-jumprate 0.004            1.04  0.96  1.07  1.14  1.02  1.18  0.96   0.21   4.75  P/P/P

-trendshare 0.02           1.03  0.97  1.06  1.12  1.03  1.19  0.98   0.18   4.11  P/P/P
-trendshare 0.055 *        1.04  0.99  1.06  1.13  1.03  1.21  0.97   0.21   4.74  P/P/P
-trendshare 0.30           1.13  1.09  1.19  1.33  1.18  1.38  0.90   0.38  10.36  F/P/F

-crowdimpact 0.01          1.00  0.78  0.99  0.98  0.86  1.13  1.01   0.20   0.58  P/P/P
-crowdimpact 0.07 *        1.04  0.99  1.06  1.13  1.03  1.21  0.97   0.21   4.74  P/P/P
-crowdimpact 0.15          1.22  1.42  1.27  1.36  1.20  1.45  0.83   0.23  15.44  F/P/F

-value 0.020               1.06  1.11  1.10  1.20  1.53  1.27  0.95   0.21   5.21  F/P/P
-value 0.045 *             1.04  0.99  1.06  1.13  1.03  1.21  0.97   0.21   4.74  P/P/P
-value 0.070               1.02  1.07  1.01  1.01  0.85  1.18  0.98   0.21   4.35  P/P/P

-recoverydrag 0            0.99  0.81  0.97  1.10  0.76  1.09  1.01   0.20   4.11  P/P/P
-recoverydrag 10.0 *       1.04  0.99  1.06  1.13  1.03  1.21  0.97   0.21   4.74  P/P/P
-recoverydrag 20           1.04  1.01  1.06  1.16  1.08  1.21  0.97   0.21   4.78  P/P/P

-recoveryfloor 0.05        1.05  1.05  1.08  1.07  1.22  1.22  0.97   0.21   4.79  P/P/P
-recoveryfloor 0.10 *      1.04  0.99  1.06  1.13  1.03  1.21  0.97   0.21   4.74  P/P/P
-recoveryfloor 0.50        1.01  0.96  1.02  1.16  0.81  1.14  1.00   0.21   4.30  P/P/P
```

60 paths x 100 years, seed 20260813. `vol` equity volatility · `kurt` daily kurtosis ·
`clus` volatility clustering (lag 1) · `crash` crashes per century · `d10` time spent >10% below
peak, against what real equity funds of the same volatility and return spend · `bdep` the bond's
equivalent, against what its own volatility implies · `r/v` return per unit volatility ·
`tshare` **realized** trend-follower share · `cflow` crowd flow, bp/session ·
`gate` realism/mechanism/fidelity.

Five things that table is trying to tell you:

- **`stress` is not a volatility dial.** It is one amplifier producing volatility, fat tails *and*
  volatility clustering together. Raising it 5.37 → 8.0 takes volatility from 1.04 to 1.38 and
  clustering from 1.06 to 1.45 — and fails the realism gate. You cannot buy tails *here* without
  buying clustering.
- **`recoverydrag` and `value` are one pair, and neither reads correctly alone.** The base pull
  governs shallow water; the drag governs deep drawdowns. Turn the drag off and leave the pull at
  its shipped 0.045 and `d10` collapses to 0.76 — the market climbs out of everything too fast.
  Weaken the pull instead and `d10` runs to 1.53. The shipped pair sits at 1.03 because the two
  halves cover different depths. If you move one, move the other.
- **`jumpvar` is the tail dial that `stress` never was, and the two rows prove it.** `-jumpvar 0` is
  the pre-0.21 world exactly — 1.04 / 0.47 / 1.13 / 1.41, the old default row, because the jump
  draws come from their own RNG stream and nothing else in a path shifts. Turning it on moves
  `kurt` 0.47 → 1.04 and moves clustering the *right* way, 1.13 → 1.04, because variance taken out
  of the diffusion shortens the persistence the clamped volatility process was over-supplying. The
  ceiling is the realism band, not the target: `-jumpvar 0.16` reaches `kurt` 1.67 and fails
  realism. Set it by the band.
- **`jumprate` shapes the tail at fixed total variance, and both directions cost you.** Rarer jumps
  of the same variance are bigger ones. At 0.0004 they are too rare for a 100-year path to sample
  (`kurt` 0.80); at 0.004 they are too small to be tails (0.82). The default sits at the maximum
  and the curve is flat near it, which is what a dial should look like.
- **`fundvol` is the clean dial for time under water, and `drift` is the clean dial for return.**
  Raising `-fundvol` 0.02 → 0.10 moves `d10` from 0.89 to 1.29 while volatility and
  return-per-volatility sit still — the fundamental accumulates into drawdown depth without
  reaching daily return scale. `-drift` is its complement: across 0.08 → 0.15 it moves `r/v` from
  0.68 to 1.31 and leaves `d10` alone. Before 0.21 these looked like one dial, because `d10` was
  graded against a fixed level and drift moved the market past it; graded against a relation that
  moves with return, drift moves the prediction and the measurement together and cancels.
- **`crowdimpact` and `trendshare` do nearly the same thing.** Both scale the crowd's price
  pressure; watch `cflow` rather than the flag — `-trendshare 0.30` and `-crowdimpact 0.15` push
  the same statistics in the same direction, and differ mainly in how much `cflow` they buy.
- **The two bond dials are almost orthogonal to the equity leg.** `-easing` and `-refuge` move
  `bdep` from 1.07 to 1.44 and leave volatility, kurtosis and clustering essentially untouched.
  Nothing else in the table is that clean — which is the point of having them as separate dials
  rather than as one "refuge strength". Note that `bdep` here is the bond at the *shipped*
  duration; what these dials do across the duration ladder is a different question, and
  `-crossasset` is the only thing that answers it.
- **Some settings leave the admissible region.** `-stress 8.0`, `-depth 12`, `-jumpvar 0.16` and
  `-crowdimpact 0.15` fail realism (clustering, crash rate, kurtosis and clustering respectively).
  The volatility and crash-rate realism bands were widened in 0.21.0 to 8–40% and 8–55/century,
  because at 8–25% and 8–45 they excluded 17 and 2 of the 35 real equity instruments respectively —
  a realism band that rejects markets you can buy is measuring the wrong thing; `-stress 2.0` and `-refuge 0.0` fail mechanism
  (the spiral never engages; the bond stops rallying in crashes). Most of the rest fail only
  fidelity — the level of one quantity stops being readable. Always re-run `-validate` after
  changing a dial.

## A worked example

The 0.21.0 recalibration took the drawdown trade. Cooling the fundamental (`-fundvol` 0.13 → 0.041)
put time under water onto what real funds of the same volatility and return actually show, and paid
for it in crash *depth*: the median crash is now 0.85 of the real −27.1% where the 0.20.0 world held
0.97. The two cannot both be right — every world that fixes the rungs lands crash depth at 0.78-0.85,
because cooling the fundamental makes drawdowns shorter *and* shallower together.

A book that would rather have the old balance — realistic crash depth, but a third too long
underwater — can run the 0.20.0 world explicitly; its every field is in `-releases` and in any
0.20.0 sidecar. There is no free version. Pick deliberately, record the choice in the sidecar, and
state the direction of the bias you accepted.

```
                       d10  dMed  crash  kurt   realism
DEFAULT (0.21.0)      1.00  0.85   1.40  0.47   PASS
the 0.20.0 world      1.55  0.97   1.42  0.42   PASS
```

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

These are properties of the model, not of the default, and they do not go away by changing dials:

- **Volatility clustering runs slightly high** (1.05 at the default, down from 1.11 before the
  jump channel). Volatility is more predictable here than in the record, so volatility-forecasting
  rules are flattered — but only mildly now, and the
  anchor is horizon-sensitive: the real statistic reads 0.271 over 72 years and 0.299 over a
  century, so the target is the century figure to match the 100-year paths the model is scored on.
  Against the 72-year reading the same world would show higher still.
- **Crashes arrive a little too often** (1.08; 22 per century against a real 20.7, down from 1.32
  before the recovery drag). Any hazard rate conditioned on "a crash happened" is mildly
  over-sampled. `-noise` now puts the real anchor at the 33rd percentile of model histories, where
  it sat at the 4th.
- **The worst crash is overstated** (1.61; index paths near −92% against a real −56.8%, and
  mostly a horizon artifact — at the anchor's own 72 years the record sits mid-distribution, per
  `-noise`). No levered fund survives those, so ruin rates for levered sleeves are **upper bounds,
  not estimates**.
- **The median crash is now close** (0.94; −25.6% against a real −27.1%, up from 0.84). The
  recovery drag fixed this and the crash rate together — they were one defect, deep drawdowns
  recovering too fast, not two.
- **The deep drawdown rung runs long, and got longer** (1.96 against the relation, from 1.38,
  where the 5% and 10% rungs sit at 0.92 and 1.02). This is what the recovery drag costs: slowing
  the climb out of a deep hole means more time deep. Rules keyed to a *deep* distance from peak
  inherit it; the shallow ones are accurate. Its band is deliberately absent and its weight is 0.06
  — one 25-year record barely pins it, and the drag made it more variable still (p5 0.19, p95 4.35).
- **Kurtosis is no longer a scope exclusion** (0.97 as of 0.21.0, from 0.45). Tail-day magnitudes
  are readable. It had been reachable only through `-stress`, which bought it at clustering 1.53 and
  failed realism; `jumpVar` reaches it through a second channel that costs nothing — clustering
  *improved* alongside it. Tail-day DEPTH is a separate matter and still overstated: see the worst
  crash row above. One caveat that is not a defect: single-history kurtosis is now genuinely wild
  here — a 72-year window reads 8.8 at the 5th percentile and 205 at the 95th — because a window
  either contains its 1987 or does not, exactly as SPY 1993-2026 reads 14.4 where the CRSP century
  reads 28. Do not read kurtosis off one path.
- **The bond's loss in inflation regimes is overstated** (1.57; −39% against a real −25%). Read this
  one with `-noise` beside it: over 24-year histories the model produces readings from −181% to
  +14%, and the real −25% sits at the 60th percentile of that spread. The point ratio carries almost
  no information, which is why the target's weight is 0.1.
- **The bond's crash rally is understated** (0.50 against long-Treasury 2008). The model's bond
  gains ~10.1% in an equity growth shock where TLT gained 22.3% in 2008 — though TLT's median across
  its six real drawdowns is 9.65%, so the anchor is the outlier and the model is nearer the median
  than the ratio suggests. Do not size a refuge sleeve on this row.

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

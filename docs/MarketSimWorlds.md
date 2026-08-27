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
"world": { "trendShare": 0.070000, "depth": 16.100000, "stress": 5.600000, ... }
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
[ "$(market_sim.exe -version)" = "0.20.0" ] || { echo "wrong simulator" >&2; exit 1; }
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
missing `version` as "schema 1", not as a malformed file.

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
cargo install vastblue-uni@0.20.0 --example market_sim --root ~/.local/uni-0.20.0
~/.local/uni-0.20.0/bin/market_sim.exe -version
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
a seed draw wearing a verdict's clothes. The default world carries one EDGE cell — `bond depth vs
vol` at d=5.70 sits at the band floor under the 0.19.2 and 0.20.0 defaults alike, a recorded miss:
real funds of comparable volatility read 0.98–1.06.

The acceptance gate applies the same refusal: `-validate` prints an anchor-fitted band it cannot
grade as `n/a` with the reason (and the sidecar records it under `gate.fidelityUnanchored`),
instead of failing a world for sitting where the anchor funds have no data.

Cost: the full report runs ~18 ensembles at the invocation's `-paths`/`-years` — four ladder rungs
plus a bisection solve for the equity section — so scale `-paths` down for a quick look.

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
| `-stress` | liquidity-spiral gain: how much a run of down days amplifies later moves | 5.6 |
| `-depth` | market depth; price impact scales as `12/depth`, so higher = calmer | 16.1 |
| `-drift` | fundamental drift per year; no dividend, so this IS total return | 0.123 |
| `-trendshare` | mandate level for trend-following capital (a spring, not a wall) | 0.07 |
| `-crowdimpact` | how hard the crowd's trading pushes price — the reflexive channel's strength | 0.07 |
| `-value` | pull toward fair value per day: how fast mispricing is arbitraged away | 0.0145 |
| `-easing` | **cap** on the policy rate cut under equity stress, in rate points | 0.046 |
| `-unwind` | how fast that cut is withdrawn, per year (0.35 is a ~2-year half-life) | 0.35 |
| `-refuge` | flight-to-quality bid into the bond, scaled by its duration | 0.11 |

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
DEFAULT                     1.03  0.42  1.08  1.43  1.03  1.24  1.07   0.22   5.64  P/P/P

-stress 2.0                 0.77  0.16  0.42  0.95  0.87  0.94  1.43   0.22   4.97  P/F/F
-stress 5.6 *               1.03  0.42  1.08  1.43  1.03  1.24  1.07   0.22   5.64  P/P/P
-stress 8.0                 1.33  0.88  1.48  1.61  1.18  1.42  0.83   0.22   6.03  F/P/F

-depth 12                   1.31  0.47  1.13  1.98  1.29  1.23  0.84   0.22   6.33  P/P/F
-depth 16.1 *               1.03  0.42  1.08  1.43  1.03  1.24  1.07   0.22   5.64  P/P/P
-depth 22                   0.81  0.37  1.00  0.99  0.79  1.23  1.35   0.22   4.94  P/P/F

-drift 0.08                 1.04  0.43  1.09  1.28  1.49  1.25  0.68   0.22   5.52  P/P/F
-drift 0.123 *              1.03  0.42  1.08  1.43  1.03  1.24  1.07   0.22   5.64  P/P/P
-drift 0.15                 1.02  0.43  1.06  1.42  0.84  1.23  1.32   0.22   5.75  P/P/F

-easing 0.0                 1.03  0.43  1.08  1.39  1.08  1.09  1.07   0.22   5.69  P/P/P
-easing 0.046 *             1.03  0.42  1.08  1.43  1.03  1.24  1.07   0.22   5.64  P/P/P
-easing 0.09                1.02  0.42  1.08  1.44  1.02  1.50  1.08   0.22   5.60  P/P/F

-refuge 0.0                 1.03  0.42  1.08  1.43  1.03  1.31  1.07   0.22   5.64  P/F/P
-refuge 0.11 *              1.03  0.42  1.08  1.43  1.03  1.24  1.07   0.22   5.64  P/P/P
-refuge 0.20                1.03  0.42  1.08  1.43  1.03  1.46  1.07   0.22   5.64  P/P/F

-trendshare 0.02            1.01  0.40  1.05  1.38  0.99  1.21  1.09   0.19   4.62  P/P/P
-trendshare 0.07 *          1.03  0.42  1.08  1.43  1.03  1.24  1.07   0.22   5.64  P/P/P
-trendshare 0.30            1.12  0.53  1.22  1.66  1.27  1.40  0.98   0.39  11.30  P/P/F

-crowdimpact 0.02           0.97  0.34  0.96  1.22  0.84  1.12  1.14   0.22   1.39  P/P/P
-crowdimpact 0.07 *         1.03  0.42  1.08  1.43  1.03  1.24  1.07   0.22   5.64  P/P/P
-crowdimpact 0.15           1.24  0.60  1.34  1.82  1.48  1.49  0.90   0.25  17.57  F/P/F

-value 0.008                1.05  0.46  1.12  1.36  1.07  1.30  1.06   0.22   5.81  P/P/P
-value 0.0145 *             1.03  0.42  1.08  1.43  1.03  1.24  1.07   0.22   5.64  P/P/P
-value 0.05                 0.99  0.32  0.94  1.49  0.96  1.12  1.12   0.22   5.27  P/P/P
```

`vol` equity volatility · `kurt` daily kurtosis · `clus` volatility clustering (lag 1) ·
`crash` crashes per century · `d10` share of sessions >10% below peak · `bdep` bond time below
peak, against what its own volatility implies · `r/v` return per unit volatility ·
`tshare` **realized** trend-follower share · `cflow` crowd flow, bp/session ·
`gate` realism/mechanism/fidelity.

Five things that table is trying to tell you:

- **`stress` is not a volatility dial.** It is one amplifier producing volatility, fat tails *and*
  volatility clustering together. Raising it 5.6 → 8.0 takes volatility from 1.03 to 1.33 and
  clustering from 1.08 to 1.48 — and fails the realism gate. You cannot buy tails here without
  buying clustering.
- **`drift` is the only clean single-axis dial.** Across 0.08 → 0.15 volatility, kurtosis and
  clustering barely move, while the depth profile goes 1.49 → 0.84. It is the only way to change how
  long the market sits below its peak without changing what the market feels like day to day. It
  costs return-per-unit-volatility (0.68 → 1.32), which is why that has its own band.
- **`crowdimpact` and `trendshare` do nearly the same thing.** Both scale the crowd's price
  pressure; watch `cflow` rather than the flag — `-trendshare 0.30` and `-crowdimpact 0.15` push
  the same statistics in the same direction, and differ mainly in how much `cflow` they buy.
- **The two bond dials are almost orthogonal to the equity leg.** `-easing` and `-refuge` move
  `bdep` from 1.09 to 1.50 and leave volatility, kurtosis and clustering essentially untouched.
  Nothing else in the table is that clean — which is the point of having them as separate dials
  rather than as one "refuge strength".
- **Some settings leave the admissible region.** `-stress 8.0` and `-crowdimpact 0.15` fail
  realism; `-stress 2.0` and `-refuge 0.0` fail mechanism (the spiral never engages; the bond
  stops rallying in crashes). Most of the rest fail only fidelity — the level of one quantity
  stops being readable. Always re-run `-validate` after changing a dial.

## A worked example

The 0.20.0 recalibration took the volatility trade: the default now sits on the 16% anchor (1.03),
paying crashes 1.32 → 1.43 and clustering 1.06 → 1.08. A book that would rather have the old
balance — volatility 10% low, but fewer synthetic crashes and the lowest clustering any default has
had — can run the 0.19.2 world explicitly; its every field is in `-releases` and in any 0.19.2
sidecar. There is no free version. Pick deliberately, record the choice in the sidecar, and state
the direction of the bias you accepted.

```
                       vol   clus  crash  kurt   realism
DEFAULT (0.20.0)      1.03   1.08   1.43  0.42   PASS
the 0.19.2 world      0.90   1.06   1.32  0.42   PASS
```

## Biases you inherit whatever you choose

These are properties of the model, not of the default, and they do not go away by changing dials:

- **Volatility clustering runs high** (1.08 at the default). Volatility is more predictable here
  than in the record, so volatility-forecasting rules are flattered — but only mildly, and the
  anchor is horizon-sensitive: the real statistic reads 0.271 over 72 years and 0.299 over a
  century, so the target is the century figure to match the 100-year paths the model is scored on.
  Against the 72-year reading the same world would show 1.17.
- **Crashes arrive too often** (1.43; 29.7 per century against a real 20.7 — and the part of
  this the old defaults hid inside a low volatility is now visible: at matched volatility the
  0.19.2 world already read ~1.50). Any hazard rate
  conditioned on "a crash happened" is over-sampled here. `-depth` is the dial that carries it, and
  raising it costs volatility.
- **The worst crash is overstated** (1.52; index paths near −86% against a real −56.8%, and
  mostly a horizon artifact — at the anchor's own 72 years the record sits at the model's 64th
  percentile, per `-noise`). No levered
  fund survives those, so ruin rates for levered sleeves are **upper bounds, not estimates**.
- **The 20% drawdown rung runs about 10% shallow** (0.88) against an independent series, while the
  5% and 10% rungs are accurate (1.10, 1.03). Rules keyed to a deep distance from peak inherit that
  level bias.
- **Kurtosis is a recorded scope exclusion** (0.42). Tail-day magnitudes must not be read off this
  model. It is reachable — `-stress 7.5` gets kurtosis to 0.91 — but only by pushing clustering to
  1.53 and volatility to 1.22, which fails realism. The two cannot both be right.
- **The bond's crash rally is understated** (0.47 against long-Treasury 2008). The model's bond
  gains ~8.4% in an equity growth shock where TLT gained 22.3% in 2008 — though TLT's median across
  its six real drawdowns is 9.65%, so the anchor is the outlier and the model is nearer the median
  than the ratio suggests. Do not size a refuge sleeve on this row.

## If you are calibrating rather than choosing

`-calibrate N` random-searches thirteen parameters against the fitness loss and reports the best few
re-scored on a held-out seed. It prints; it does not modify defaults. Note that the loss has no
notion of *not breaking what is already right* — it will happily spend an accurate row to improve an
inaccurate one, so read the whole fidelity table after any recalibration, not just the loss.

The current default is not the search's own optimum and does not need to be: across 8,000 samples
the best training loss was 2.603, which re-scored at 3.120 on the held-out seed, against the
default's 3.003 / **2.588** — the best holdout figure in the run. A world that wins on the training
seed and loses on the holdout has fitted the seed.

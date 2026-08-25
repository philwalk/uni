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
market_sim.exe -validate -stress 5.6
market_sim.exe -strategies -depth 14 -drift 0.13
```

`-emit` writes a sidecar naming every field of the world that produced the path, so a study cannot
lose track of which world it used:

```
"version": "<the release that wrote this file>",
"world": { "trendShare": 0.060000, "depth": 16.600000, "stress": 5.100000, ... }
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
[ "$(market_sim.exe -version)" = "0.19.3" ] || { echo "wrong simulator" >&2; exit 1; }
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
cargo install vastblue-uni@0.19.2 --example market_sim --root ~/.local/uni-0.19.2
~/.local/uni-0.19.2/bin/market_sim.exe -version
```

Exe-versus-library mismatch is not a risk — the example links the library from the same crate. The
only mismatch is the exe against what the consumer expected.

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

## The dials worth your attention

| flag | what it is | default |
|---|---|---|
| `-stress` | liquidity-spiral gain: how much a run of down days amplifies later moves | 5.1 |
| `-depth` | market depth; price impact scales as `12/depth`, so higher = calmer | 16.6 |
| `-drift` | fundamental drift per year; no dividend, so this IS total return | 0.117 |
| `-trendshare` | mandate level for trend-following capital (a spring, not a wall) | 0.06 |
| `-crowdimpact` | how hard the crowd's trading pushes price — the reflexive channel's strength | 0.088 |
| `-value` | pull toward fair value per day: how fast mispricing is arbitraged away | 0.013 |
| `-easing` | **cap** on the policy rate cut under equity stress, in rate points | 0.045 |
| `-unwind` | how fast that cut is withdrawn, per year (0.35 is a ~2-year half-life) | 0.35 |
| `-refuge` | flight-to-quality bid into the bond, scaled by its duration | 0.08 |

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
DEFAULT                     0.90  0.42  1.06  1.32  1.02  1.24  1.17   0.22   6.87  P/P/P

-stress 2.0                 0.67  0.13  0.29  0.91  0.86  0.95  1.58   0.21   5.90  F/P/F
-stress 5.1 *               0.90  0.42  1.06  1.32  1.02  1.24  1.17   0.22   6.87  P/P/P
-stress 8.0                 1.28  1.01  1.59  1.49  1.18  1.36  0.81   0.22   7.68  F/P/F

-depth 12                   1.22  0.45  1.11  1.94  1.34  1.23  0.86   0.22   8.10  P/P/F
-depth 16.6 *               0.90  0.42  1.06  1.32  1.02  1.24  1.17   0.22   6.87  P/P/P
-depth 22                   0.70  0.38  0.98  0.93  0.77  1.23  1.50   0.21   5.89  P/P/F

-drift 0.08                 0.91  0.42  1.07  1.22  1.43  1.25  0.78   0.22   6.75  P/P/F
-drift 0.117 *              0.90  0.42  1.06  1.32  1.02  1.24  1.17   0.22   6.87  P/P/P
-drift 0.15                 0.89  0.42  1.03  1.30  0.77  1.23  1.53   0.22   7.03  P/P/F

-easing 0.0                 0.90  0.42  1.06  1.30  1.06  0.93  1.17   0.22   6.92  P/P/P
-easing 0.045 *             0.90  0.42  1.06  1.32  1.02  1.24  1.17   0.22   6.87  P/P/P
-easing 0.09                0.90  0.42  1.05  1.34  1.00  1.54  1.17   0.22   6.83  P/P/F

-refuge 0.0                 0.90  0.42  1.06  1.32  1.02  1.31  1.17   0.22   6.87  P/F/P
-refuge 0.08 *              0.90  0.42  1.06  1.32  1.02  1.24  1.17   0.22   6.87  P/P/P
-refuge 0.20                0.90  0.42  1.06  1.32  1.02  1.47  1.17   0.22   6.87  P/P/F

-trendshare 0.02            0.88  0.40  1.02  1.26  0.97  1.21  1.19   0.19   5.78  P/P/P
-trendshare 0.06 *          0.90  0.42  1.06  1.32  1.02  1.24  1.17   0.22   6.87  P/P/P
-trendshare 0.30            1.04  0.56  1.27  1.63  1.36  1.45  1.01   0.40  15.46  P/P/F

-crowdimpact 0.02           0.82  0.31  0.87  1.00  0.74  1.06  1.27   0.21   1.25  P/P/F
-crowdimpact 0.088 *        0.90  0.42  1.06  1.32  1.02  1.24  1.17   0.22   6.87  P/P/P
-crowdimpact 0.15           1.05  0.56  1.28  1.63  1.38  1.46  1.00   0.24  16.02  P/P/F

-value 0.008                0.91  0.47  1.09  1.26  1.07  1.29  1.16   0.22   7.08  P/P/P
-value 0.013 *              0.90  0.42  1.06  1.32  1.02  1.24  1.17   0.22   6.87  P/P/P
-value 0.05                 0.85  0.30  0.88  1.38  0.91  1.09  1.23   0.21   6.28  P/P/F
```

`vol` equity volatility · `kurt` daily kurtosis · `clus` volatility clustering (lag 1) ·
`crash` crashes per century · `d10` share of sessions >10% below peak · `bdep` bond time below
peak, against what its own volatility implies · `r/v` return per unit volatility ·
`tshare` **realized** trend-follower share · `cflow` crowd flow, bp/session ·
`gate` realism/mechanism/fidelity.

Five things that table is trying to tell you:

- **`stress` is not a volatility dial.** It is one amplifier producing volatility, fat tails *and*
  volatility clustering together. Raising it 5.1 → 8.0 takes volatility from 0.90 to 1.28 and
  clustering from 1.06 to 1.59 — and fails the realism gate. You cannot buy tails here without
  buying clustering.
- **`drift` is the only clean single-axis dial.** Across 0.08 → 0.15 volatility, kurtosis and
  clustering barely move, while the depth profile goes 1.43 → 0.77. It is the only way to change how
  long the market sits below its peak without changing what the market feels like day to day. It
  costs return-per-unit-volatility, which is why that has its own band.
- **`crowdimpact` and `trendshare` do nearly the same thing.** Both scale the crowd's price
  pressure; watch `cflow` rather than the flag. `-trendshare 0.30` and `-crowdimpact 0.15` produce
  almost identical worlds.
- **The two bond dials are almost orthogonal to the equity leg.** `-easing` and `-refuge` move
  `bdep` from 0.93 to 1.54 and leave volatility, kurtosis and clustering untouched to two decimals.
  Nothing else in the table is that clean — which is the point of having them as separate dials
  rather than as one "refuge strength".
- **Some settings leave the admissible region.** `-stress 2.0` and `-stress 8.0` fail realism;
  `-refuge 0.0` fails mechanism (the bond stops rallying in crashes). Most of the rest fail only
  fidelity — the level of one quantity stops being readable. Always re-run `-validate` after
  changing a dial.

## A worked example

A levered book wants volatility closer to the 16% anchor than the default's 0.90 ratio.

The obvious move, `-stress 5.6`, gets volatility to 0.96 — and takes clustering from 1.06 to 1.18.
Clustering above 1.0 means volatility is *more forecastable in the model than in the record*, which
flatters every rule that forecasts volatility. If the book is full of volatility-targeting sleeves,
that trade buys a better volatility level at the cost of systematically over-rating the strategies
that key on it.

`-depth 15` is the other route: volatility 0.99, with almost no clustering cost — but the crash rate
goes from 1.32 to 1.49, and crashes already arrive too often. There is no free version. Pick
deliberately, record the choice in the sidecar, and state the direction of the bias you accepted.

```
                 vol   clus  crash  kurt   realism
DEFAULT         0.90   1.06   1.32  0.42   PASS
-stress 5.6     0.96   1.18   1.37  0.53   PASS
-depth 15       0.99   1.07   1.49  0.44   PASS
```

## Biases you inherit whatever you choose

These are properties of the model, not of the default, and they do not go away by changing dials:

- **Volatility clustering runs high** (1.06 at the default). Volatility is more predictable here
  than in the record, so volatility-forecasting rules are flattered — but only mildly, and the
  anchor is horizon-sensitive: the real statistic reads 0.271 over 72 years and 0.299 over a
  century, so the target is the century figure to match the 100-year paths the model is scored on.
  Against the 72-year reading the same world would show 1.17.
- **Crashes arrive too often** (1.32; 27.4 per century against a real 20.7). Any hazard rate
  conditioned on "a crash happened" is over-sampled here. `-depth` is the dial that carries it, and
  raising it costs volatility.
- **The worst crash is overstated** (1.44; index paths near −82% against a real −56.8%). No levered
  fund survives those, so ruin rates for levered sleeves are **upper bounds, not estimates**.
- **The 20% drawdown rung runs about 10% shallow** (0.89) against an independent series, while the
  5% and 10% rungs are accurate (1.08, 1.02). Rules keyed to a deep distance from peak inherit that
  level bias.
- **Kurtosis is a recorded scope exclusion** (0.42). Tail-day magnitudes must not be read off this
  model. It is reachable — `-stress 7.5` gets kurtosis to 0.91 — but only by pushing clustering to
  1.53 and volatility to 1.22, which fails realism. The two cannot both be right.
- **The bond's crash rally is understated** (0.42 against long-Treasury 2008). The model's bond
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

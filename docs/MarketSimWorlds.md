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
market_sim.exe -validate -stress 5.9
market_sim.exe -strategies -depth 14 -drift 0.13
```

`-emit` writes a sidecar naming every field of the world that produced the path, so a study cannot
lose track of which world it used:

```
"world": { "trendShare": 0.060000, "depth": 16.300000, "stress": 5.400000, ... }
```

Run `market_sim.exe -badflag` for the full flag list with each default.

## When the choice matters, and when it does not

| your claim | example | does the world matter? |
|---|---|---|
| **rank** | "the 200-day trend rule beats the volatility rule" | barely — check it survives the sweep |
| **level** | "this rule is out of the market 32% of the time" | **yes** — the world sets the level |

For rank claims, run `-strategies` and read the rank-stability table: it already varies nineteen
parameters, including `stress` at 0, 5.4 and 8.1. A ranking that survives that range does not need
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
  ranked by median net return   reflexive: crowd runs a vol rule | reflexive: crowd pressed hard
  always fully invested               1  6   character 4-7   <-- MOVES OUTSIDE THE CHARACTER RANGE
  trend 150d, floor 0%                8  1   character 1-3   <-- MOVES OUTSIDE THE CHARACTER RANGE
  trend 200d, floor 40%               5  5   character 3-6
  trend 200d, floor 0%                9  2   character 1-3   <-- MOVES OUTSIDE THE CHARACTER RANGE
```

Read that: `trend 150d` is the **best** rule of nine when the crowd is a pressed momentum crowd and
the **eighth** when the crowd runs a volatility rule, against a character range of 1-3. Buy-and-hold
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
| `-stress` | liquidity-spiral gain: how much a run of down days amplifies later moves | 5.4 |
| `-depth` | market depth; price impact scales as `12/depth`, so higher = calmer | 16.3 |
| `-drift` | fundamental drift per year; no dividend, so this IS total return | 0.117 |
| `-trendshare` | mandate level for trend-following capital (a spring, not a wall) | 0.06 |
| `-crowdimpact` | how hard the crowd's trading pushes price — the reflexive channel's strength | 0.088 |
| `-value` | pull toward fair value per day: how fast mispricing is arbitraged away | 0.013 |

## Every dial moves several things at once

This is the part to read before changing anything. Columns are **ratios to the real anchor**, so
1.00 is on target. Starred rows are the defaults.

```
setting                      vol  kurt  clus crash   d10   r/v tshare cflow  gate
---------------------------------------------------------------------------------
DEFAULT                     0.92  0.46  1.08  1.20  1.06  1.14   0.22  6.49  P/P/F

-stress 2.0                 0.67  0.13  0.29  0.68  0.77  1.55   0.21  5.44  F/P/F
-stress 5.4 *               0.92  0.46  1.08  1.20  1.06  1.14   0.22  6.49  P/P/F
-stress 8.0                 1.25  1.05  1.56  1.47  1.41  0.84   0.22  7.40  F/P/F

-depth 12                   1.24  0.50  1.15  1.84  1.35  0.85   0.22  7.67  P/P/F
-depth 16.3 *               0.92  0.46  1.08  1.20  1.06  1.14   0.22  6.49  P/P/F
-depth 22                   0.70  0.39  1.00  0.76  0.84  1.49   0.21  5.54  P/F/F

-drift 0.08                 0.94  0.46  1.10  1.11  1.50  0.76   0.22  6.35  P/P/F
-drift 0.117 *              0.92  0.46  1.08  1.20  1.06  1.14   0.22  6.49  P/P/F
-drift 0.15                 0.91  0.45  1.06  1.16  0.80  1.49   0.22  6.67  P/P/F

-trendshare 0.02            0.91  0.43  1.05  1.15  1.03  1.16   0.19  5.48  P/P/F
-trendshare 0.06 *          0.92  0.46  1.08  1.20  1.06  1.14   0.22  6.49  P/P/F
-trendshare 0.30            1.06  0.64  1.30  1.47  1.35  0.99   0.39 14.45  P/P/F

-crowdimpact 0.02           0.86  0.34  0.93  0.93  0.86  1.21   0.21  1.23  P/P/F
-crowdimpact 0.088 *        0.92  0.46  1.08  1.20  1.06  1.14   0.22  6.49  P/P/F
-crowdimpact 0.15           1.07  0.64  1.30  1.47  1.34  0.99   0.23 14.87  P/P/F

-value 0.008                0.94  0.49  1.11  1.14  1.14  1.13   0.22  6.67  P/P/F
-value 0.013 *              0.92  0.46  1.08  1.20  1.06  1.14   0.22  6.49  P/P/F
-value 0.05                 0.88  0.32  0.91  1.21  0.97  1.19   0.21  6.02  P/F/F
```

`vol` equity volatility · `kurt` daily kurtosis · `clus` volatility clustering (lag 1) ·
`crash` crashes per century · `d10` share of sessions >10% below peak · `r/v` return per unit
volatility · `tshare` **realized** trend-follower share · `cflow` crowd flow, bp/session ·
`gate` realism/mechanism/fidelity.

Four things that table is trying to tell you:

- **`stress` is not a volatility dial.** It is one amplifier producing volatility, fat tails *and*
  volatility clustering together. Raising it 5.4 → 8.0 takes volatility from 0.92 to 1.25 and
  clustering from 1.08 to 1.56 — and fails the realism gate. You cannot buy tails here without
  buying clustering.
- **`drift` is the only clean single-axis dial.** Across 0.08 → 0.15 volatility, kurtosis and
  clustering barely move, while the depth profile goes 1.50 → 0.80. It is the only way to change how
  long the market sits below its peak without changing what the market feels like day to day. It
  costs return-per-unit-volatility, which is why that has its own band.
- **`crowdimpact` and `trendshare` do nearly the same thing.** Both scale the crowd's price
  pressure; watch `cflow` rather than the flag. `-trendshare 0.30` and `-crowdimpact 0.15` produce
  almost identical worlds.
- **Some settings leave the admissible region.** `-stress 2.0` and `-stress 8.0` fail realism;
  `-depth 22` and `-value 0.05` fail mechanism. Always re-run `-validate` after changing a dial.

## A worked example

A levered book wants volatility closer to the 16% anchor than the default's 0.92 ratio.

The obvious move, `-stress 5.9`, gets volatility to 0.98 — and takes clustering from 1.08 to 1.20.
Clustering above 1.0 means volatility is *more forecastable in the model than in the record*, which
flatters every rule that forecasts volatility. If the book is full of volatility-targeting sleeves,
that trade buys a better volatility level at the cost of systematically over-rating the strategies
that key on it.

`-depth 14` is the other route: volatility 1.06 (overshooting rather than undershooting), with a
smaller clustering cost at 1.12 — but the crash rate goes from 1.20 to 1.49. There is no free
version. Pick deliberately, record the choice in the sidecar, and state the direction of the bias
you accepted.

```
                 vol   clus  crash  kurt   realism
DEFAULT         0.92   1.08   1.20  0.46   PASS
-stress 5.9     0.98   1.20   1.26  0.58   PASS
-depth 14       1.06   1.12   1.49  0.49   PASS
```

## Biases you inherit whatever you choose

These are properties of the model, not of the default, and they do not go away by changing dials:

- **Volatility clustering runs high** (1.08 at the default). Volatility is more predictable here
  than in the record, so volatility-forecasting rules are flattered — but only mildly, and the
  anchor is horizon-sensitive: the real statistic reads 0.271 over 72 years and 0.299 over a
  century, so the target is the century figure to match the 100-year paths the model is scored on.
  Against the 72-year reading the same world would show 1.20.
- **The worst crash is overstated** (1.49; index paths near −84% against a real −56.8%). No levered
  fund survives those, so ruin rates for levered sleeves are **upper bounds, not estimates**.
- **Shallow drawdown rungs run about 10% deep** against an independent series, so rules keyed to a
  2–10% distance from peak inherit that level bias. The 20% rung is accurate.
- **Kurtosis is a recorded scope exclusion** (0.46). Tail-day magnitudes must not be read off this
  model. It is reachable — `-stress 7.5` gets kurtosis to 0.94 — but only by pushing clustering to
  1.65 and volatility to 1.18, which fails realism. The two cannot both be right.

## If you are calibrating rather than choosing

`-calibrate N` random-searches twelve parameters against the fitness loss and reports the best few
re-scored on a held-out seed. It prints; it does not modify defaults. Note that the loss has no
notion of *not breaking what is already right* — it will happily spend an accurate row to improve an
inaccurate one, so read the whole fidelity table after any recalibration, not just the loss.

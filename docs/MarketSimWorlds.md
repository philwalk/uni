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
[ "$(market_sim.exe -version)" = "0.24.6" ] || { echo "wrong simulator" >&2; exit 1; }
```

This catches the wrong binary, and it is the only check available *before* you spend the run. It
stops working the moment you are handed a file instead of a command.

A version names the code, not the recipe: before a release is published, a recipe can be re-solved
under its name. `-digest` prints the digest of the world the other flags select, the same one the
sidecar carries as `worldDigest`. `-buildid` prints the version, `+`, and a digest of every world
`-atrelease` resolves. Key a cache of paths on the version, the world digest, the seed, the path
index and the length together. The digest is a 64-bit FNV-1a over the `world` block with each dial
written as its bit pattern, so it sees a change the block's six significant digits cannot, and both
twins print the same digest.

```
market_sim.exe -atrelease 0.24.5-nasdaq -digest      # dc877ff8e1198f5f
```

**After the fact — the sidecar.** `-emit F` writes a sidecar beside the TSV — `F` with its
extension replaced by `.json`, so `paths.tsv` → `paths.json` (a bare name gets `.json` appended) —
and that is the only provenance that survives the file being moved. Six fields answer six
different questions:

| field | question |
|---|---|
| `schema` | can I parse this file? |
| `version` | which release's simulator wrote it? |
| `world` | with which parameters? |
| `worldDigest` | exactly which world, to the bit? (schema 28) |
| `gate` | was that world even admissible? |
| `reportedRows` | how does it read against records the set does not grade on? (schema 29) |

Each `gate.fidelity` row carries `real`, `target` and `miss`. `real` is the record, read the way the
model reads a path, on every row with a `recordBand`; `target` is what the loss grades against, and
the two differ where the target is a theory value or older than its record (see
[Reading a row against its record](#reading-a-row-against-its-record)). On a banded row `miss` is
true when the model falls outside the record's own joint resampling band, and
`recordPercentile` says where it falls; on every other row `miss` is true when the ratio falls
outside 0.667-1.5. Either way a row that **cannot be computed** reads `miss: true` — a `null` model
value is not a pass. A row's `miss` is that row's own disclosure, not the verdict: the fidelity
class (`gate.fidelity`, `gate.fidelityFailed`) is decided by the gate rows, so a row can read
`miss: true` beside a fidelity PASS, as the Nasdaq's `bubble coupling 3y` does on most seeds. A path holding a non-finite price is refused outright: `-emit` exits 2 and
writes nothing, where every other gate verdict only warns.

`reportedRows` holds rows read off records the set does not grade on, each shaped as a `fidelity`
row and led by its `window`. They never enter a verdict; their `miss` says only where the record
would fall. The Nasdaq set reports its timing rows against CRSP's century and the NDX from 1990,
its bubble coupling against the NDX from 1990, and its multi-year rows against the NDX from 1990
and QQQ (see [Each set on its own record](#each-set-on-its-own-record)); the S&P set reports
nothing.

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
`channels.basket` carries, against a ruler, the three levels' readings plus `nameD20Spread`, the
spread of time below peak across the names, `graded: true` and the ruler; without one,
`{ "ruler": null, "graded": false }`; a run with a ruler omits `logBasket`). `world.basketDrift` records the cross-sectional drift dispersion dial, and
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
`gate` fields, so a schema-6 reader that ignores unknown columns and fields keeps working. Schema 18
gave each `fidelity` row `target`, `recordBand` and `recordPercentile`, and made `real` the record
on every banded row: `model / real` on the variance ratio is now the model against QQQ's 0.83, not
against the loss's theory value of 1.00.

**Every emitted channel is graded, and the verdict names its own scope.** `ungradedChannelSeries`
is **empty in every world the model can currently emit**: the satellite leg is graded by the
`satellite *` rows and the sampled bars by the `bar *` rows, so a `"fidelity": "PASS"` beside a
`logSat` column is a verdict *about* that column too. It stays in the schema because the next
channel to arrive is ungraded until someone anchors it, and that has to be said beside the data
rather than in a document nobody opens. A channel that is bit-identical off cannot perturb the
graded price series, which is what makes it safe to add — and is exactly why a verdict computed
from the primary leg alone could not see it. Disclosure would be the cheap answer; grading is the
right one.

**Every 20%+ decline of the path is labelled with its shape** (schema 21). The sidecar's `episodes`
block lists the path's declines of 20% or more from the trailing-year (252-session) high, each measured
to the first regain of its peak, the next allowed to start at the next such high after the trough
(so a 2008 counts inside a 2000's unregained spell), on `logTraded` when the file carries it and on
the log of `price` otherwise (`series` says which). Each row: `peak`, `trough`, `regain` (row indices
of the TSV; `regain` null when the peak stands unregained at the path's end), `sessionsToTrough`,
`sessionsToRegain`, `runUp3y` (log, null inside the first three years), `depth` (log),
`volPeakOverMedian` (the peak 21-session realized vol inside the decline over the path's median),
`worstSession` and `worst20` (the worst log return over one and twenty sessions inside it),
`rateChange` (the rate a year past the trough less at the peak, decimal) and `spreadRise` (the macro
spread's high by the trough plus a quarter less at the peak, pp; null without the panel). What it
is for: how often a bust comes is not knowable from one record, so a rule's average over histories
rests on that unknown; conditioned on the label, the histories holding a decline shaped like 2000,
or like 2008, or none, a rule's reading needs no opinion about frequency.

**Every verdict grades every channel and the macro panel, whatever the file carries** (schema 19).
The verdict is a property of the world, and the derived series are functions of its state, so the
verdict ensemble runs every channel the caller left off at the anchor set's dials (the basket aside:
it grades only against a client's ruler) — the S&P set's
are `0.24.4-sp500-channels`', the Nasdaq set's `0.24.4-nasdaq-basket`'s — and grades their rows
beside the primary's. A default's or a set member's PASS therefore implies the bundle's; before
this a channel's rows graded only when its dial was on, and the S&P default's `macro cond build-up`
slid onto its gate's edge unseen. A channel the caller turned on is graded at the caller's dials,
because that is what the file carries; a null panel (`-macronull`) is graded as a real one and the
emitted columns stay declared ungraded. The emitted file is unchanged — a default still writes
`price` and `bond` — and `gate.verdictSeries` lists everything the verdict graded while
`gate.verdictChannels` carries each channel's dials as the verdict ran it, `emitted` or
`anchored` (the basket's `emitted` or `off`, with `graded` saying whether a ruler graded it). The
`channels` block is present on every file, led by the level the verdict's channels
were sampled at, which is a function of the primary alone and so the file's own level wherever the
file has one. The search's feasibility and `-fitness`'s gate penalty read the same verdict world.
The primary is bit-identical, so every row the caller's own ensemble reads is what it was.

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
cargo install vastblue-uni@0.24.6 --root ~/.local/uni-0.24.6
~/.local/uni-0.24.6/bin/market_sim.exe -version
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

**A set of worlds, not a best fit — `-worldset`.** Forty-eight searched dials against some forty-five
graded rows that are not independent means several distinct worlds match the record equally
well, and a strategy interacts with the mechanism rather than with the summary statistic. A
verdict formed on one best-fit world therefore carries the same over-confidence that weakens a
backtest: it is conditional on a single realisation. The calibration search
(`market_sim_search`, or `jsrc/marketSimSearch.sc`, which produce identical files) keeps an
**archive** of every world it finds that is *feasible* — passes every realism and mechanism gate
row on every one of its seeds, on both the S&P and the Nasdaq anchor sets — and spreads them
across the behaviours the record cannot pin down. Fidelity rows are scored, not gated: members
differ from the record inside its own sampling error, which is where the set's value is. A row
the daily record reads directly (the twelve that carry a record band) is scored against that band
at the record's own horizon, 27 years for QQQ: a miss costs what a failed fidelity band costs, plus
its distance past the edge in the band's own sd. `-noregress R` prices the release rule itself:
every row a candidate holds further from its record than recipe R does costs the difference (R read
at the search's own ensemble, the mean of six reads), which a dead zone alone lets every row drift
inside. `-gap ROWS` makes named graded rows a feasibility condition on the primary arm: a
candidate that misses one on any of its reads is rejected, as a realism failure is, because a
priced near miss is traded for the regressions it saves. A row with a record band is judged by that
band and a row without one by its ratio to the anchor's target, which is the verdict's own miss
either way. `-gate C` says which classes feasibility reads — the default `realism,mechanism` is the
verdict's own, and `all` adds fidelity, which is what a calibration set needs, every member of one
having to pass every class: priced instead, a failed band is one dead zone against a row's worth of
gain, and an archive fills with members that buy a row by leaving a band. `-hold ROW<=D,...` holds
a named row within D of its record on every read, D in the units a set is judged in (percentile
points from the band's middle on a banded row, |ln(model/target)| on any other): a set is graded on
each row's median member distance, and a row the loss only prices is traded away, so an archive
ends where the loss pulls it whatever it was seeded from. The second market's arm holds its own magnitudes
(depth, drift, stress, vol of vol, `jumpVar`, refuge, `slowShare`, the news rate and size) and
its own valuation (`beliefShare`, `capYears`, `beliefLeak`, `beliefYears`, the cycle, `bustAmp`),
and carries the candidate's mechanism dials. The seed worlds are not held to `-gap`: they root
the lineages, and `-holdout` holds every member to it. Each
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
archive's full precision. The release ships four, built this way and pruned by their holdouts:
`test-data/worlds/0.24.4-nasdaq.json` (62 members, seeded from the `0.24.4-nasdaq` recipe and
searched under `-gate all` with the daily-shape and bond crash rows under `-gap` and equity vol, the
tail hedge, bond depth, d10, d20 and a margin on the bond's vol under `-hold`, the transport arm the
S&P default; its holdout is the verdict's own read, 200 paths × 100 years on four seeds: every
member passes every class on all four and misses no row on three of them, which 73 of the 104
searched did -- the bond-vol margin (`-hold "bond vol % (24y)<=0.092"`, the recipe's own level
under that gate) is what survives a seed: a third to a half passed four seeds without it, and on
four fresh seeds 54 of the 62 pass every class on all four, none failing the bond's vol gate, and
on a seed whose bond vol draws 2 sd high 12 fail it (37 of the 58 did at 0.105); member 28 is the recipe itself, byte for byte; pass
`-anchors nasdaq`, since a member names no anchor set), `0.24.3-nasdaq.json`
(173 members, seeded from `0.24.2-nasdaq`, the set `0.24.3-nasdaq` was picked from -- member 53),
and the two 0.24.2 sets, `0.24.2-sp500.json` (150 members, seeded from the
default; pass `-anchors sp500` or nothing) and `0.24.2-nasdaq.json` (174, seeded from
`0.24.2-nasdaq`), searched before the typical-year and wing rows, so their `score` and
`worstRow` are that objective's readings. Every member of the three older sets starts at fair
value, which the stationarity row now refuses, and those with a nonzero `bustAmp` run under the
swing's ceiling and recovery rule; their `score` and `worstRow` predate all of it. The 0.24.4 set
was searched under it. **The 0.24.6 sets** are the two to draw from now: `0.24.6-nasdaq.json` and
`0.24.6-sp500.json`, 30 members each, member 0 the recipe itself (the 0.24.5 sets, which ran low on
return per vol, stay as published). The members sample the long-run drift's uncertainty given the
record: one history pins the drift only to its own sampling width, and a set whose members shared
one drift would tell a consumer the long-run return is known exactly. Members 1-29 are drawn from a
Markov-chain sample over every searched dial under the membership rule and the record's
return-per-vol likelihood -- a world's drift free over its prior range, the other dials moving with
it -- seeded from the previous set, judged on seeds 1-4 and chosen one per quantile of the sample's
drift. Their return per vol runs 0.17-0.82 on the Nasdaq (QQQ 0.38; drift 0.097-0.243) and
0.52-0.83 on the S&P (CRSP 1954-2026 0.69; drift 0.113-0.167). Every member's `coverageWeight` is
1/30: the set is an equal-weight sample with the recipe one member of it. The return-per-vol gate is the record's own joint band (Nasdaq -0.18-0.97, S&P
0.31-1.09), so it refuses only what the record rules out. A member's `seededFrom` names the chain
particle it was drawn from (run id, founder index), its `score` is the chain's log-likelihood of the
record's return per vol under that member, not a search score, and its `worstRow` is a placeholder.
The membership test is
the verdict's own read on four fresh seeds at 200 paths × 100 years with every derived series and
the macro panel graded: every class passes on all four, and no row misses on three or more of them
unless the recipe misses it too, where 9 of the 150 members of `0.24.2-sp500.json` pass every class
on all four under the same verdict (it predates the channels' grading; keep it for its worlds, not
as a set). As a set, on every graded row the recipe does not miss, the record's position in the
set's pooled predictive (the mean over members of the record's percentile less 50, or of the log
ratio) is no further from the middle than the outgoing set's beyond 5 points or 0.02, and the
spread of member positions left after removing the drift's linear effect is at most 1.45 times the
outgoing set's, plus the same tolerance. A median member's distance cannot test a sampled dial: it
rewards members bunched at the record, and it passed a set whose members all leaned one way. The
members' source worlds were searched at that same read, 200 × 100 with every class gated: a search
at fewer paths or under 100 years, or gating realism and mechanism alone, admits mostly members the
test rejects (under 100 years the 250-session rung grades another era). The set-level reading is
each row's median over members and seeds, its range across reads, beside the record: on the S&P set
return per vol 0.70 (0.51-0.84; 0.69), the avoided share 52.4% (43.8-61.1; 64.7), kurtosis 24.8
(21.8), equity vol 16.7% (15.7%), the median decline 0.71 years (0.66), the 3-year variance ratio
1.22 (0.75), the wings 6.6 / 7.6 (7.6 / 6.7), the floor share 10.2% (14.6%), leverage corr -0.07,
0.17 misses a read; on the Nasdaq set return per vol 0.37 (0.16-0.86; 0.38), the avoided share
39.3% (29.8-49.3; the splice's 59.8), kurtosis 9.8 (9.6), equity vol 24.3% (26.9%), the bubble
coupling +0.14 (+1.10, missed on 82 reads in 120), the wings 6.5 / 6.7 (7.6 / 6.7), the floor
share 31% (37%), 0.91 misses a read. To run one:

```
market_sim.exe -worldset test-data/worlds/0.24.6-nasdaq.json -worldindex 3 -anchors nasdaq -paths 200 -years 40 -emitall -emit m3.tsv
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

### Streaming thousands of paths

`-emitf32 F` writes the paths `-emit` would write as ONE binary file plus one sidecar for the chunk,
named as a TSV's is: the file's extension replaced by `.json` (`chunk-0000.f32` → `chunk-0000.json`). Cells are little-endian IEEE-754 f32, path-major: path, then column, then session, so
column c of the chunk's j-th path starts at byte `(j × columns + c) × sessions × 4`. `-emitall`,
`-emitfrom` and `-paths` pick the paths as they do for `-emit`. Paths are simulated and written a
batch at a time, so a chunk of thousands never sits in memory, and a path's bytes do not depend on
the chunk that holds it.

```
market_sim.exe -atrelease 0.24.6-nasdaq-basket -anchors nasdaq -macronull 2 -years 56   -emitall -emitfrom 0 -paths 200 -emitf32 chunk-0000.f32   -emitcols price,logTraded,bond,rate,liq,logSat,logOpen,logHigh,logLow,logVolume,logName1,...
```

`-emitcols` lists the columns in the order to write them; the default is every column the TSV
carries except `date`. A named column whose channel did not run in this world is listed under
`columnsAbsent` and not written; a name no world emits is refused. The `nullMacro*` columns need
`-macronull 2`.

The chunk's sidecar is the TSV's with `format` (`"f32le"`), `layout` (`["path", "column",
"session"]`), `columnsAbsent` and a `paths` block (`first`, `count`, `baseSeed`, `seedStride` and
the calendar) in place of `header` and `path`; path k's seed is `baseSeed + k × seedStride`, and
its dates follow from `calendar` and `startDate`. `episodes.paths` holds each path's index and
rows. `gate.gradedSeries` and `gate.ungradedChannelSeries` list only columns in the file.

The verdict is the `-emitgate` ensemble's at the calibration horizon, whatever the chunk, so every
chunk of a bundle carries the same `gate`, `channels`, `fidelity` and `reportedRows`. No report is
printed; `-validate` and `-emitgate 0` are refused.

Values are the TSV's at single precision (the TSV's six decimals are about f32's precision), with
negative zero written as positive. The Scala twin's chunk matches the Rust one except about one
cell in three million, which differs by one f32 ulp: the twins' doubles differ in their last bits.
200 paths of 56 years in 36 columns make a 406 MB chunk, written in 1.7 s on 24 cores with the
verdict.

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
`joint-coupling-2026-10-03.tsv`, measured from Yahoo Finance's SPY and QQQ adjusted closes over
1999–2026. **Its marginals are graded too — as RATIOS to the primary leg**,
not as levels: kurtosis, both clustering lags, the 5% and 10% depth shares, and the crash rate,
each against what QQQ holds to SPY over their shared window. That is the same doctrine the depth
rungs use when they grade each world at its own volatility, and it is the honest one here, because
the satellite is a second leg at *this* world's scale and is not claimed to be any index. Measured
against QQQ's absolute levels it would miss on kurtosis in both directions, which is why the level
comparison is the wrong question to ask of it. Use the satellite when you need a *second correlated
equity leg*; use the `-anchors nasdaq` recipe when you need a world graded against Nasdaq levels.

**The depth rows read both legs at the record's drift.** The 5% and 10% depth shares and the crash
rate depend on drift as well as on the coupling: read raw, the d5 ratio rises 0.035 for every 0.01
of the world's drift, so a band drawn from one 27-year window would cap the world's long-run drift
with that window's. These rows therefore replace each leg's own realized drift with SPY's and QQQ's
over the window (8.24% and 10.26% a year) before reading them; the d5 ratio then moves 0.007 per
0.01 of drift and still answers to the coupling (satellite beta 1.0 → 1.4 moves it 1.34 → 1.42).
Their bands are the window and its five 5-year blocks, read the same way, rounded outward: d5
1.0–1.7, d10 1.1–2.2. Taking the drift out entirely is not the alternative it looks like — a
driftless century-long leg sits more than 5% below its peak almost always, so both ratios pin near 1
whatever the coupling. Re-drifting sets aside the relative drift between the legs, which the
`satellite rel-trend` rows grade.

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

The slow swing itself is a state since the cycle landed: `-cyclesd` carries it, started from its
own law, the beliefs fade toward the fundamental (`-beliefleak`) and carry only what the price has
absorbed net of it — [below](#the-valuation-cycle-as-a-state--cyclesd). The readings above are the
pre-cycle world's; the shipped default now reads dispersion 0.17 and wings 0.0 / 3.3, the
stationary walk having been most of the 0.33.

## The valuation cycle as a state — `-cyclesd`

Every path used to start at fair value, and the valuation gap then fell for decades: to −0.3 log
on the Nasdaq recipe after about forty years, to −1.0 on the S&P default after about a hundred
and twenty. At the calibrated belief share the pull sees 5-27% of the fundamental, so after a
disaster the price tracked the decline and never the recovery leg (the S&P's gap sat 0.86 log
under its onset level four years after the trough and 0.88 twenty years after, with the
fundamental fully regained and growing), and every spiral crash did the same in miniature. The
slow valuation variation the wing and dispersion rows graded was that walk plus its transient; a
mania could not arm in a path's first two decades (0.3-0.7 mania-led episodes per
century-equivalent against 4-8 later), so the 27-year tail row never saw the mania channel; and a
consumer's 40-year path carried a 0.25-0.40 log downtrend in price over fair from its first
session. Read in the stationary state, the shipped worlds sat off their own calibration in
opposite directions: the Nasdaq recipe's wings 6.4 / 10.7 → 12.4 / 12.0, the S&P's upper wing
0 → 2.0 and its century worst −83 → −67.

`-cyclesd S` makes the slow swing a state: a stationary AR(1) in log added to perceived fair,
half-life `-cycleyears` (11.5 years by default, Shiller's CAPE), stationary sd S, drawn from its
own stream and started from its stationary law, with the price and its running peak starting on
it — the first session is a draw from the same distribution as the thousandth. Its move is
repriced the same session in the price, the bust swing's convention: tracked through the pull it
manufactured a lagged response and failed the variance-ratio profile. The beliefs track the gap
net of the cycle, so the two do not compound, and the derived channels see the move as a
repricing through the news-jump input. 0 is bit-identical off.

The row that refuses the old start is `valuation stationary from the first session`, a mechanism
row: the pooled gap's mean over the paths' later half minus over their first decade must sit
within 0.15 log (a path under 50 years reads its two halves). A stationary world reads within
±0.05 at 60 paths; the pre-cycle S&P default reads −0.5 to −0.7 and the pre-cycle Nasdaq recipe
−0.05 to −0.23. The report's `valuation gap` line prints the reading as `drift late-early`.
The recovery drag reads the drawdown of the price *without* the cycle: value capital is depleted
by a crash, not by a slow re-rating. A drag that read the cycle's down-swings as drawdowns
weakened the pull for years at a time and manufactured a lagged recovery, which lifted the 250-day
variance ratio past the profile's 1.30 at the 25-year horizon on the S&P (1.17-1.22 at amplitudes
0.2-0.4, 1.10 with the drag reading the price net of the cycle; drag 0 reads 1.08). What the cycle
still costs, measured on the S&P at 15 years: each 0.1 of amplitude adds about a point of pooled
vol and thins the tails (kurtosis 24 → 14 from 0.2 to 0.4), which the re-solve pays for in depth
and the tail dials.

The residual gap — what the beliefs absorb net of the cycle — has its own fix, `-beliefleak L`:
the beliefs fade toward the fundamental at L per year, so the belief share stays `-beliefshare`
at the daily scale (the variance-ratio profile reads the same) and falls to share × mu/(mu + L)
in the long run (mu the adaptation rate), where the walk under the fundamental lived. A lower
share alone does the second and breaks the first. On the shipped S&P default 0.2 reads drift
−0.52 → −0.06 with every other row inside noise of its old reading except the two the walk was
supplying: the valuation dispersion 0.29 → 0.15 and the lower wing 11.8 → 3.1, which the cycle
is there to carry.

The shipped S&P default is the 0.24.1 world with the fade at 0.2 and the cycle at 0.1: its paths
start stationary (drift −0.06 on four seeds) and every class passes; kurtosis 32 → 27.5, d10 and
d20 down a little, the lower wing 11.8 → 3.3, at the cost of the dispersion (0.29 → 0.17) and the
typical year (13.0 → 13.4). No larger cycle passes the variance-ratio profile on every seed
(0.12 and 0.15 each fail it on one of three), so the S&P's upper wing stays the disclosed miss it
was; three searches under the row (the cycle with a lower belief share, the fade, the fade with the
drag net of the cycle) found no world that improves on it without moving other rows down. The
Nasdaq recipe re-solved under the row (`0.24.4-nasdaq`) reaches its wings through the
growth-extrapolation term with the cycle near 0 — see its recipe paragraph.

## The sector rows — the record ruler of a sector channel

A rotation rule needs sector legs that persist the way the record's industries do, and the record
has to say how much that is before any dial is set. `record_bands -sectors <data>` reads Ken
French's value-weighted monthly industry portfolios (`10_Industry_Portfolios.CSV`,
`49_Industry_Portfolios.CSV`, 1926-07 on) beside the market (`Mkt-RF + RF`) and the bill rate
(`F-F_Research_Data_Factors.CSV`), the files as the library publishes them, and prints
`sectors-2026-09-30.tsv`: three rows, since
rotation to cash mixes two persistences and a cross-section shape.

- **momentum** — each month the industries are ranked by their cumulative return over the eleven
  months ending two months back (12-1; the 6-1 form uses five) in excess of the market's over the
  same months; long the top 3 of 10 (10 of 49), short the bottom 3 (10), equal-weighted, held one
  month. The mean monthly long-minus-short return, its t-statistic and the share of positive
  months, with a 5-95 band from 12-month block resamples of the spread series. 12-1 reads +0.39% a
  month on 10 industries (t 3.4; +0.63% on 49) and holds from 1963; 6-1 reads +0.14% on 10 and is
  null from 1963, so the world is graded on 12-1.
- **trend** — per industry, the mean next-month return over the bill rate after a positive signal
  minus after a negative one (the trailing twelve months over the bill rate, or the price index over
  its ten-month mean, read at the end of the month before), averaged over the industries. This is
  what rotation *to cash* exploits, and it is a different persistence from the cross-section's:
  +0.55% (12-month sign) and +0.37% (SMA) on 10 industries, the second's band spanning zero.
- **shape** — the mean cross-sectional sd of the industries' monthly returns (3.1%), the median
  pairwise correlation (0.70), and the same correlation on the market's worst-decile months (0.56)
  and its middle decile (−0.03): the rows a sector channel's beta spread and idio share are solved
  on, the basket channel's mechanism row on sectors.

An independent implementation of the same definitions (the fixture's header states them)
reproduces every value to six decimals. The sector channel below is graded on the 10-industry rows
and never on a rotation rule.

## The sector channel — `-sectors`

`-sectors K` adds K sector legs as observational second-pass instances of the primary, the
basket's construction with one addition. Each leg is its beta times the primary's observed return
(betas drawn once per path around 1 at the record's cross-sectional dispersion, 0.216 across the
ten industries, centred exactly so the equal-weight aggregate's beta is 1), plus its own idio on
the vol state (`-sectoridio`, a fraction of the primary's realized volatility), plus a **slow
relative drift**: an AR(1) state per leg with sd `-sectordriftsd` (a fraction of the primary's
realized annualized volatility, so it transports) and half-life `-sectordrifthalf` years, centred
across the legs each session so the aggregate keeps the primary's drift. That state is what
carries the 12-1 relative momentum's persistence; nothing else in the channel does. Reaches no
price; 0 is bit-identical off. `-emit` gains `logSector1..K`, the legs' log prices, after the
basket's columns.

The legs are graded on the ten-industry ruler above, read the ruler's way on the legs aggregated to
calendar months (the primary as the market, the rate compounded as the bill), in the forms that
carry across primaries of different volatility: the 12-1 momentum spread and the two trend
readings over the cross-sectional sd, at the record's 5-95 block-bootstrap bands over its own; the
cross-sectional sd over the market's monthly sd at ±0.10; the pairwise correlations at ±0.10 whole
and ±0.15 on the market's deciles; and the mechanism, pairwise correlation on the market's middle
decile below its worst decile's. One setting holds both sets: K 10, idio 0.7, drift 0.25 with a
2-year half-life, in `0.24.5-sp500` and `0.24.5-nasdaq-basket`, and the verdict grades every world
at it when the caller leaves the channel off.

| row | record | `0.24.5-sp500`, seeds 5-8 | `0.24.5-nasdaq-basket` |
|---|---|---|---|
| momentum 12-1 spread / xs sd | 0.127 (0.069-0.178) | 0.126-0.131 | 0.110-0.120 |
| trend 12m / xs sd | 0.180 (0.007-0.344) | **−0.012 to 0.002, a miss** | 0.110-0.128 |
| trend sma10 / xs sd | 0.119 (−0.079-0.298) | −0.090 to −0.071, the edge | 0.006-0.021 |
| xs sd / market sd | 0.581 | 0.590-0.600 | 0.626-0.630 |
| pair corr; worst; middle decile | 0.70; 0.56; −0.03 | 0.67-0.68; 0.57-0.62; 0.00-0.01 | 0.64-0.65; 0.47-0.48; 0.00-0.01 |

Two things to read off that table. Part of the momentum spread is the betas' alone: after an
up-year the top-ranked legs are the high-beta ones and the market's mean is positive, so with the
drift state off the S&P world still reads about 0.2% a month; the dial lifts it to the record's.
And the S&P world **misses the 12-month trend row**, not through the channel but through its
primary: the per-sector trend is the market's own carried into every leg, the record's market
reads +0.50% a month after a positive trailing year against a negative one, and the S&P world's
reads −0.44%. The Nasdaq world's booms carry it (+0.5%). The channel discloses a primary defect the
existing rows never saw, the market's one-year time-series momentum, which is the next primary
item; the row stays graded.

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

**`bubble coupling 3y`** is the second extreme row, for the same reason and one more: no block
resampling keeps the structure it measures. Per path it is the mean 3-year log run-up into the peaks
of the 40%+ declines minus the mean 3-year run-up at every all-time high, so a positive reading says
the deep declines followed larger run-ups than a typical high did. The record is the set's long
window, `bubblebust-2026-09-24.tsv`: for the Nasdaq set the 1971-2026 splice of the Composite and
the NDX price indexes (+1.10; QQQ's record starts nine years too late for a 3-year run-up into the
2000 peak), for the S&P the CRSP century (+0.11). `0.24.5-nasdaq` reads a median near +0.1 and
places the splice at the 97th percentile, a miss; `0.24.5-sp500` places CRSP at the 73rd. The
worlds' deep declines start from spirals, jumps and unwinds at any point of the cycle more often
than from the top of a run-up.

Read it as `-noise`'s `real@`, because it is the same measurement: near 50% the record is a typical
history of this model, near 0 or 100 it is not. It needs at least 20 histories to place a record at
all — below that the row reports `n/a` and a MISS, since one history reads 0% or 100% and neither is
a measurement.

In the sidecar such a row carries `"aggregation": "ensemble-extreme"`, `"ratio": null` and a
`"percentile"`, so a consumer cannot make the division by accident. `"horizonYears"` is on every
row: the length `model` was read over on a row with a record band, the anchor's record elsewhere.

**The multi-year rows** grade what a year or more of a series is one observation of. Eight
statistics, each a row against the set's equity window and again, with `long` appended to its name,
against the set's long window (the bubble coupling's: the CRSP century; the Nasdaq splice,
1971-2026); `multiyear-2026-09-29.tsv` carries the records. The Nasdaq set grades its long window
alone and reports QQQ's rows and the NDX's from 1990: both windows lie inside the one the
consumer's rules were selected on.

| row | statistic |
|---|---|
| `annual autocorr` | the lag-1 correlation of successive annual log changes |
| `variance ratio 3y`, `variance ratio 5y` | the variance of k-year log changes over k times the variance of the annual changes across the same span; 1 without serial dependence |
| `3y p95 excess` | the 95th percentile of the log return over every 756-session window, less the series' own mean 3-year return |
| `decline gap p90 y` | the 90th percentile of the years between the peaks of successive declines of 20% or more |
| `decline length p50 y`, `decline length max y` | the peak-to-trough length in years of the regained declines of 20% or more, their median and their longest; with the depth rungs, what a withdrawal schedule started just before a decline survives |
| `under water 20% %` | sessions more than 20% under the running peak, in percent |

Block statistics are averaged over twelve phases spread evenly across the block's own length. Two
windows, because the record's eras disagree: CRSP's annual autocorrelation reads +0.03 over the
century and −0.13 from 1954, its time under water 25.5% and 12.6%.

Each row reports the record's percentile among the world's single histories of the window's length,
as an extreme row does: a one-year block resample has no multi-year structure left to read, so
these rows carry no record band. A row MISSES when the record falls outside the family's joint band
of those histories, printed as `within`. The band's ranks are set so that a world the record is a
typical history of clears all six rows of a window at once 95% of the time and both windows 90%; at
200 histories its edges sit near the 1st and 99th percentiles. The graded variance-ratio rows carry
judgment 0.5 each in the loss, at the spread of their logs across single histories; the other
rows weigh 0.

```
 variance ratio 3y   model   1.32   real   0.75   record@   3% of 72y histories (n=200)   within 0.66..1.91
```

On 32 fresh seeds at 200 × 100, `0.24.5-sp500` reads the record from 1954 at the 12th, 8th, 15th,
13th and 7th percentile on the first five rows and the century at the 30th, 19th, 21st, 30th and
19th, no row outside its band on any seed: the worlds' returns persist over two to five years
(3-year variance ratio 1.21) where the record's since 1954 reverse (0.75). Time under water reads
the 24th and 67th. `0.24.5-nasdaq` reads QQQ and the NDX between the 14th and 95th percentile, no
row outside its band on any seed.

In the sidecar a multi-year row carries `"aggregation": "single-history"`, `"ratio": null`, a
`"percentile"` and `"historyBand"`, the joint band its `miss` reads the record against.

**`largest 3y run-up`**, **`longest calm stretch`** and **`equity d20 vs real`** are reported
beneath the table and in the sidecar's `gate.reported`, and not graded. The first two are one number
from one history each: the century's run-up (log 0.872) sits at the 1st percentile of its own years
resampled in one-year blocks, so a world with the record's structure reads it as a miss. The fund
relation behind the third reads the CRSP index itself at 2.3 from 1954 (12.6% of sessions under
water against the relation's 5.5%); `under water 20% %` grades the rung against the index.

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

### Reading a row against its record

`-noise` asks where the record falls among the model's histories. The record bands ask the reverse:
where the model falls among the histories the record itself could have produced. Every row one
daily record can be read the model's way — eighteen on each set: volatility, the typical year, return
per volatility, kurtosis, both clustering lags, the variance ratio at 60, 120 and 250 sessions, the
downside excess, the up-day share, leverage corr, the crash rate, median depth, the volatility-timing
edge, the short rate's mean and time at the floor, and the bond's underwater share against its
volatility — carries
the record's reading and its spread over 20,000 moving one-year-block resamples of that record
(`test-data/equity-anchors/recordbands-2026-09-26.tsv`: QQQ 1999-2026 for the Nasdaq set, CRSP
1954-2026 for the S&P set, the century for its clustering rows, the federal funds rate over each
set's own window for the rate rows, and TLT's 24 years for the bond row). `-validate` prints where the model
falls among the resamples and the row's band, and the row misses outside it:

```
 variance ratio 60d     model     0.89   real     0.83   ratio  1.07   model@  85% of 0.61..0.99   target 1.00
 up-day share %         model    52.37   real    54.78   ratio  0.96   model@   0% of 52.71..56.83  <-- MISS
```

The bands are JOINT: a set's rows all fall inside theirs together on 90% of the record's
resamples, so a history drawn like the record misses no row nine times in ten. That puts each
row's edges near its 0.6th and 99.4th percentiles (`jointLo`/`jointHi` in the fixture); twelve
per-row 5th-95th bands held QQQ's resamples together only 42.7% of the time.

Each licensed record ends on the date its fixture states. Releases re-read only Ken French's
library and FRED's public-domain series, never Yahoo Finance or the Moody's and Cboe series FRED
redistributes. A record continues past its last date on a published proxy spliced after it
(`record_bands -splice FILE -at DATE`, repeatable): TLT on the 20-year constant-maturity Treasury
built from FRED's DGS20 yields (weekly correlation 0.975 over 2002–2026, `bond depth vs vol` 1.16
against TLT's 1.03, inside TLT's own band), QQQ and the NDX on Ken French's daily HiTec industry
(weekly correlation 0.977 with QQQ over 1999–2026, every QQQ record-band row inside QQQ's band;
0.94 with the NDX since 1971). The proxies extend a record; they do not replace it, since the
HiTec industry misses three of the splice's thirteen 20% declines and reads the bubble coupling at
0.40 against 1.10.

A band per row replaces a ratio band of one width for all, which was wrong both ways: too narrow
for a statistic one history barely pins (the downside excess, whose band spans zero, read MISS on
every recent Nasdaq world) and too wide for one it pins tightly (lag-1 clustering's band is
0.65-1.21 times the record, where the ratio band ran to 1.5). Rows no single daily record reads —
the depth rungs, the wings, the valuation dispersion, the bond rows — keep the ratio band.

`real` is the record, so where the loss grades against something else the report prints `target`
beside it:

| row | target | record | why |
|---|---|---|---|
| variance ratio 60d | 1.00 | QQQ 0.83, CRSP 1.01 | a theory value: the model has no mean-reversion channel |
| variance ratio 120d, 250d | 1.00 | QQQ 0.92, 1.11; CRSP 1.03, 1.03 | the same theory value at three times the 60-day row's weight; the verdict reads each against its record band |
| S&P equity vol % | 16.0 | 15.68 | a literal older than the fixture |
| S&P kurtosis | 28 | 21.8 | the century's, on a row read over 1954-2026 |
| S&P crashes/century | 20.7 | 24.9 | between the century's 19.2 and 1954-2026's 24.9 |
| S&P median depth % | −21.4 | −20.8 | the mean of the two middle of 18 episodes; the model's median takes the upper |
| Nasdaq downside vol excess % | 1.13 | 1.07 | an earlier vintage of the same series |

The targets are unchanged. Re-anchoring the four S&P rows moves the S&P calibration, and is its own
decision.

**The up-day share** — `up-day share %`, the share of moving sessions that rise — is the count half
of the return asymmetry, which the downside excess cancels by construction. The record pins it: QQQ
rises on 54.8% of its sessions, in smaller steps than it falls (band 52.7-56.8), and CRSP 1954-2026
on 55.0% (53.5-56.3). Both shipped worlds read it low — the Nasdaq recipe's 52.4% misses, and the
S&P default's 53.8% sits at the 3rd percentile of CRSP's resamples — and `-noise` agrees from the
other side, with the record above 98% of the model's own histories on both. The row is reported at
weight 0 in the loss until a mechanism reaches it.

**The volatility-timing edge** — `vol-timing edge pts/yr` — is the first *conditional* row: what
follows a stretch of high realized volatility, which is the statistic a volatility-timing rule
lives on where every other row grades an unconditional one. It is the consumer's canonical rule with
nothing fitted: hold the index when its 24-session realized vol is below the series' own 60th
percentile of that vol, cash at 0 at or above the 80th, keep the position between, decided on the
vol through a session and held over the next; the reading is the rule's log growth minus
buy-and-hold's, points a year. The thresholds are each series' own percentiles, so the row asks
whether stepping out of the top fifth of volatility stretches pays at any volatility level, and a
world path is read exactly as the record is. QQQ 1999-2026 reads +1.4 against a band of −9.7 to
+13.6; CRSP 1954-2026 −4.2 against −7.3 to −0.3; both shipped worlds sit inside. The reward on QQQ
is the 1999-2002 window's (the NDX price index from 1990 reads −2.1). The verdict judges the row
against the record's band; the loss grades it at judgment 3.0 on the Nasdaq set, the row the consumer's
production gap turns on, and at 0 on the S&P set.

**The volatility exit** — `vol-exit timing pts/yr` and `vol-exit 3x interaction pts/yr` — reads the
consumer's simple exit at the absolute thresholds it trades, written down completely: the sample sd
of the last 24 daily simple returns of the printed close; hold below 1.5%, cash at or above 2.0%,
keep the position between, start holding; decided at a close, filled at the next, earning from
there; cash at the short rate over 252; the 3x leg reset daily at three times the total return, less
twice the rate plus 0.60% over the session's calendar days over 360, less a 0.86% expense ratio over
252. The timing row is the rule at 1x less buy-and-hold, points a year of log growth; the
interaction row is the rule on the 3x leg less the leg held, less the timing row. A world reads its
printed close (`logTraded`), its total-return price, its own rate and the synthetic calendar's days.
QQQ 1999-2026 reads +4.1 and +23.2, against bands of −2.7 to +13.0 and +2.2 to +50.4 (the two rows'
own joint band at 0.05, `volexit-2026-10-03.tsv`); CRSP 1954-2026, which has no printed close and
reads its total return for both, −0.5 and +1.1 (−1.7 to +1.1, −1.8 to +5.7). The verdict judges both
rows against those bands; the loss weighs them 0. On seeds 1-4 at 200 × 100 `0.24.6-nasdaq` reads
+0.1 to +0.7 and +9 to +11, the 11th-18th percentile of QQQ's resamples, and `0.24.6-sp500` −1.1 and
+0.1 to +0.4, the 14th-16th and 33rd-39th; every member of both 0.24.6 sets reads inside on every
seed. QQQ's record sits inside the 5th-95th of the Nasdaq worlds' 27-year histories on both rows,
whose 95th reads about +6.0 and +29 to +34; its window is the one the consumer's rule was selected
on (see [Each set on its own record](#each-set-on-its-own-record)).

**The timing rows** read what a 10-month moving-average exit does, the property a timing arm's
withdrawal objective turns on, and the market's own one-year trend, against each set's own index at
its month-end closes (`timing-2026-09-30.tsv`, from `record_bands -timing`): CRSP's with dividends
over 1926-2026 for the S&P, the 1971-2026 splice for the Nasdaq (see
[Each set on its own record](#each-set-on-its-own-record)). At each month's end the rule is in
equity for the next month when the level is above the mean of the last ten month-end levels, else
out. Four rows, graded like the multi-year rows by where the record falls among the world's own
histories of the record's length, inside their joint band at 0.05:

| row | statistic | S&P record | Nasdaq record |
|---|---|---|---|
| `sma10 decline avoided %` | over the declines of 20% or more (peak to trough), the share of each decline's log fall the rule was out for, averaged | 64.7 | 59.8 |
| `sma10 false-exit return %` | the index's cumulative return over each false exit (an out-period no month of which lies in a decline's peak-to-trough window), averaged | 5.82 | 7.66 |
| `sma10 exits per year` | the out-periods a year | 0.75 | 0.81 |
| `market sign12 trend %/mo` | the index's mean next-month return after a positive trailing twelve months less after a negative one | 0.66 | 1.10 |

The record is month-end closes because the model's are. Shiller's monthly S&P, the natural
longer record, is a monthly average of daily prices, and averaging smooths what the rule sees: on
the same CRSP series the averages read a 4.0% false-exit return against the month ends' 5.8% and
0.55 exits a year against 0.75, so two of the four rows would be artefacts of the series, not the
rule. Shiller's own series reads 73 / 3.6 / 0.57 / 0.77 over 1926-2023 for comparison
(`-shiller`).

What it shows, on 32 seeds at 200 × 100 since the slow-decline re-solve: `0.24.5-sp500` sits inside all four bands, the exit avoiding 57.9% of a decline's fall against the record's 64.7 (the record at the worlds' 74th percentile; 94th before the re-solve), a false exit forgoing 6.0% against 5.8 (44th), 0.69 exits a year against 0.75 (76th), and the one-year trend +1.00% a month against 0.66 (24th). Before the re-solve, on seed 5: the exit avoided 42.7% of a decline's fall (the record at the worlds' 94th percentile), a false exit forgoes 6.7% against 5.8 (20th), exits run 0.75 a year against 0.75 (50th), and the one-year trend reads +0.08% a month against 0.66 (75th) across histories that span −1.5 to +1.9. `0.24.5-nasdaq` since its slow-decline re-solve reads the avoided share 43.6% (the record at the worlds' 97th percentile, inside; 34.8% and 99th, a miss on 72% of seeds, before), 7.2% false exits (16th), 0.89 exits a year (7th) and the trend +0.85 (38th): a slide large enough to reach the record lifts the 60-to-120-session rise of the variance ratio past every real series. The one-year trend is a weak discriminator at a century, a history's reading spanning three points a month; the avoided share is the row that bites. The worlds' declines arrive too fast for a ten-month average to step aside from: the valuation-led slow decline, which the decline length rows point at from the other side.

**The short rate** — `short rate %` and `rate floor share %` — is the rate path's mean, in
percent, and the share of its sessions under 0.50%, both against the daily effective federal funds
rate over the set's own window: 4.6% and 15% over 1954-2026, 2.1% and 37% over 1999-2026. It is
what the bond's carry, the equity's markdown and a levered fund's financing all read, and the
carry gap a consumer measured on the Nasdaq worlds (a 3x fund paying about 7 points a year more than
TQQQ did) is this row. The shipped worlds read 4.8% (S&P) and 5.6% (Nasdaq) with no session at
the floor: the rate cannot go below zero, but chasing a 4.2% mean it never gets near it. The
floor share is priced as an additive row, since a log ratio of a share that reads zero prices
nothing but the zero. `0.24.5-nasdaq` reads 2.3% and 25% (inside both bands on every one of 32
seeds) with the floor held while the market is in its drawdown (`-floorhold`); `0.24.5-sp500` reads 4.1% and
8.7% (records 4.6% and 14.6%, inside both bands on every one of 32 seeds) with the floor held and
the rate mean at 5.7%; the shipped S&P default's rate level is inside and its floor time is not.

**The rate after a decline** — `post-trough rate %` and `post-trough floor share %` — is the same
two readings over the two years after each 20% decline's trough, the union of those windows: what
a refuge earns holding cash after an exit, which the two rows above leave open, since a world can
hold the record's mean and floor share with its floor spells anywhere. The records are the federal
funds rate on the equity window's own sessions, 4.2% and 15% over 1954-2026, 2.4% and 23% over
1999-2026 (`rateafter-2026-09-30.tsv`, from `record_bands -rateafter`), banded by paired block
resamples: the returns and the rates cut from the same starts, so a resample keeps each decline
beside its rates. Both shipped recipes sit inside without a re-solve, `0.24.5-sp500` at 4.0-4.3%
and 13-15% across seeds, `0.24.5-nasdaq` at 1.9-2.2% and 22-32%. The S&P worlds' floor time is
post-trough time: their unconditional floor share, 8-10% against the record's 14.6%, is the
record's 2011-15 spell, two to six years after the 2009 trough, which no post-trough window holds.

**The bond's underwater share** — `bond depth vs vol` — is the share of sessions the bond spends
more than 10% under its running peak, over the share its own volatility implies across real
Treasury funds. Its record is TLT's own 24 years and its band that record's resamples (0.65 to 1.79
at the 5th and 95th percentiles): a world whose rate spends a third of its time at zero hikes
several times a century and sinks its bond for years each time, and its single 24-year histories
spread from 0.6 to 2.4 on this row with TLT's at their 26th percentile. The fixed 0.65-1.35 band
that graded it through 0.24.4 is the cross-fund scatter `-crossasset` still reads.


**Read the two spreads together.** Resampling a record's years cannot produce a session worse than
its worst, so the record bands run narrow on tail statistics; the model's own spread runs wide
wherever its tail is too heavy. On kurtosis they disagree for that reason — the Nasdaq recipe sits
above every resample of QQQ, while `-noise` puts QQQ at the recipe's 12th percentile. Where both
agree, as on the up-day share, the miss is the model's.

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
| `-bustamp` | THE BUST SWING: when a 0.2-log drawdown opens under a peak that stood 0.5 log or more over the gap's 20-year mean, a months-long stationary swing of this amplitude is repriced the same session in the price and in perceived fair while the unwind keeps making new lows, with the recovery drag relieved and the amplifier blind to it — the record's mania bust, NDX 2000-02: two and a half years at 53% vol in five legs and rallies. The swing never carries the price nearer than 0.10 log to the running peak (a mania's unwind never re-attains its high), the unwind ends once the high is regained, and the state is cut to 0 once it is over. Own stream; 0 is bit-identical; the S&P default keeps 0 — [below](#the-bust-swing--bustamp) | 0 |
| `-beliefleak` | THE BELIEFS' OWN FADE toward the fundamental, per year: the belief share stays `-beliefshare` at the daily scale and falls to share × mu/(mu + L) in the long run, which is what holds the residual gap stationary at a high share (0.2 on the default: drift −0.52 → −0.06, the variance-ratio profile untouched). 0 is bit-identical — [below](#the-valuation-cycle-as-a-state--cyclesd) | 0.2 |
| `-cyclesd` / `-cycleyears` | THE VALUATION CYCLE AS A STATE: a slow stationary AR(1) in perceived fair with stationary sd S (log) and half-life Y (years), started from its own law so paths begin stationary, its move repriced the same session in the price; the beliefs track the gap net of it. Own stream; 0 is bit-identical — [below](#the-valuation-cycle-as-a-state--cyclesd) | 0.1 / 15 |
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
| `-newslev` | NEWS THAT FOLLOWS LEVERAGE: the news intensity × (1 + X × the credit stock's rise over its trailing-year average), clipped to [0, 2] so the average rate is kept, held under 0.25 a session, the compensator paid on the same intensity. Frequent moderate markdowns give the record's up-day share and left tail, and tied to the credit stock they land late in the cycle, where the record's declines start; independent ones start declines no leverage preceded and break the macro build-up | 0 |
| `-newsrevert` | the share of each news markdown that does NOT reach the fundamental, in [0, 1]: the price takes the whole step and value capital buys the rest back, as the record bounces after its down days. Permanent steps displacing the diffusion's transient noise lift the 60-day variance ratio past the record's; the reverting share holds it | 0 |
| `-newsscale` | the share of each news markdown, and of its compensator, that scales with the session's conditional vol (the log-vol state, the leverage kick, the vol response), in [0, 1]. News that has displaced most of the diffusion otherwise takes the volatility response with it: fixed-size markdowns leave the variance after a fall to the diffusion alone | 0 |
| `-noiseskew` | THE SKEWED BODY: the diffusion's unit shock as a mean-zero, unit-variance skew-normal with its long tail on the left, skew in [0, 1). The record's session is centred right of zero with more sessions far below than far above — a count asymmetry the variance cannot carry. 0.9 moves the up-day share about 1.5 points on the Nasdaq recipe and 0.7 on the S&P default, every other row within its seed noise | 0 |
| `-newsflip` | THE DAY FLIP: the share of the price's news compensator paid by reflecting a small down day into an equal up day rather than a steady lift, in [0, 1]. The day's would-be return is known before the step (the step is affine in its input), so the reflection is exact; a point of up-day share costs 1-1.5 points of downside excess where news or skew charge 3-4 | 0 |
| `-newsbond` | THE BOND LEG OF NEWS: the bond's fair value and price rise this × the markdown × (duration / 13.5) the session it lands, decaying at half a year's half-life, on top of the bond's own noise; reversed in an inflation regime, where bad equity news is rate news. Without it the sessions news drives carry no bond response and the growth-crash rally falls short | 0 |
| `-creditregime` | THE CREDIT-TRIGGERED VOL REGIME: turbulent spells that open at credit highs. Outside one, a session starts one with probability `-creditregimerate` × the credit growth gap's excess over half an sd / 252; the diffusive noise, the session's sd and the implied-vol state take exp(this), held half a year, then decaying at a quarter's half-life. Carries the credit onset of big declines without the spiral's spikes, so `-levgain` can fall | 0 |
| `-creditregimerate` | the credit regime's onsets per year per sd of credit growth over half an sd | 0 |
| `-slowbondinfl` | the share of the slow channel's bond leg that reverses in an inflation regime, as the news leg does, in [0, 1] | 0 |
| `-newsbondskip` | THE BOND ANSWERS SOME NEWS: the share of news events the bond leg skips, the rest scaled by 1 / (1 − this) so the leg's mean and the growth-crash rally are kept. A leg that answers every markdown ties the bond to the stock's worst calm sessions tighter than the record (tail hedge −0.24): at a 0.42 leg on 12/yr × 2% Nasdaq news, 0.3 reads −0.263 → −0.245. In [0, 1) | 0 |
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
| `-boomrate` | THE BOOM REGIME: booms per century — rare multi-year melt-ups of PERCEIVED fair value, the disaster channel mirrored. The record's deep declines follow large run-ups (NDX ×5.75 over the three years into 2000-03, CRSP ×2.25 into 1929-09); the model's followed ordinary ones, because the hazard of a 40%+ decline read flat in the valuation level and its manias were slow drifts (×2.0 over three years at mania-armed peaks). Poisson on the channel's own stream; onset starts a rise of `-boomsize` log in perceived fair spread evenly over `-boomlen` years, repriced the same session in the price like the valuation cycle (no gap opens, so the pull manufactures no trend); after the build the level fades at a one-year half-life, which is the drawdown that arms the bust swing — the unwind's legs and rallies are `-bustamp`'s, so a world without the swing (the S&P default) fails the variance-ratio profile under a boom. No boom starts while an unwind or a disaster runs. On the Nasdaq recipe 2.7 booms a century at 1.4 log over 2.5 years put a third of the 40%+ declines at mania-armed peaks with a ×5.7 run-up and the record's bubble coupling at the 94th percentile of 37-year histories (from above every one); the cost is underwater time (lower wing 8.4 → 10.2, d20 1.13 → 1.40). 0 turns it off, consuming no draws; searched | 0 (off) |
| `-boomsize` | total log rise of perceived fair per boom | 1.0 |
| `-boomlen` | years from onset to the peak; the rise is spread evenly | 2.5 |
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
| `-satcyclesd` | THE SATELLITE'S RELATIVE CYCLE: a persistent relative drift (innovations this many log a session, from the cycle's own stream; half-life `-satdrifthalf` years) integrated into a relative level that reverts at `-satlevelhalf` years, added to the leg's return. Read as the `satellite rel-trend` rows: the autocorrelation of successive non-overlapping 63-, 126- and 252-session changes of log(satellite/primary), QQQ/SPY 1999-2026 +0.05, +0.18, −0.29; bands the record's own one-year-block resamples. At 0.0003 / 0.2 / 0.5 (`0.24.5-sp500`, `0.24.5-nasdaq-basket`, the verdict's anchored leg) the record sits at the worlds' 20th, 79th and 18th percentiles on 80 record-length paths, against the 73rd, 91st and 5th with beta-plus-noise alone; the leg's other rows are unmoved. 0 is off and bit-identical | 0 (off) |
| `-satdrifthalf` | the relative drift's half-life in years; inert at `-satcyclesd` 0 | 0.25 |
| `-satlevelhalf` | the relative level's half-life in years; inert at `-satcyclesd` 0 | 0.8 |
| `-rangescale` | intra-bar high/low, sampled per session from the exact Brownian-bridge extremes at the session's own vol state re-levelled onto the world's realized volatility, times this dial — the range scales with the session's noise, not with \|return\|, which is what makes it detectably real (record corr(lnH/L, \|r\|) is only 0.70–0.72). Anchored 0.63 on SPY/QQQ OHLCV (0.78 with `-overnight` on, the intraday haircut made explicit), and it reads the same bar-to-ccvol ratio at any world; adds `logHigh`/`logLow` to `-emit`. NOT searchable | 0 (off) |
| `-rangedown` | same-session sign↔vol coupling on the bar: down sessions get (1+X) the bridge sigma, up sessions 1/(1+X). Anchored 0.09 (0.13 with `-overnight` on, where it reads the intraday return), which puts BOTH the range's down/up (1.14 vs 1.109–1.142) and volume's down-up gap (0.097 vs 0.094–0.098) on the intraday rulers. Requires `-rangescale` | 0 |
| `-volidio` | log turnover index riding the range: elasticity 0.59 to the range's deviation from its slow normal (frozen from the measured regression) plus a two-component persistent idio whose total sd is this dial (anchored 0.34). Requires `-rangescale`; adds `logVolume` to `-emit`. NOT searchable | 0 |
| `-divyield` | DIVIDENDS: the world's mean dividend yield, %/yr. The session yield is Y × fundamental/price over the world's mean of it (a world constant solved on the same fixed ensemble as the bar level — the ensemble's mean fundamental/price is 2.06 at the default and 2.30 on the Nasdaq recipe, and a per-path mean would leak the path's future), so a rich session yields less and the ensemble's pooled mean yield is the dial; the reported median path's mean reads about 0.9× of it (2.62 at 2.95, 0.69 at 0.78), valuation epochs skewing the path means; `-emit` gains `logTraded` (the total-return `price` deflated by the accrued yield — `price` itself is unchanged) and `divYield`. Anchored 2.95 on Shiller's S&P 1954–2023 and 0.78 on QQQ 2005–2026 (`dividend-2026-09-02.tsv`); the level is graded when on. An identity parameter, never searched | 0 (off) |
| `-overnight` | THE OPEN: the overnight share of the session's diffusive variance (0 ≤ X < 1). The open is the bridge point at that share of the session, with the session's news jump and jump-channel move landing overnight whole and the whole move becoming the gap when it overshoots the session on its own side; the bar then runs from the open over the remaining variance and the sign coupling reads the intraday return. `-emit` gains `logOpen`, and `logHigh`/`logLow` bracket the open and the close. Anchored 0.20 on the S&P default and 0.22 on the Nasdaq recipe against the record's overnight variance shares 0.33 / 0.28 (`bars-2026-09-01.tsv`, graded when on); the bar dials re-anchor with it, `-rangescale 0.78 -rangedown 0.13`, since the intraday bridge carries less of the session | 0 (open = prior close) |
| `-basket` | THE BASKET, a null world of N exchangeable names: each the shared sector leg (`-basketbeta` on the primary's observed return plus `-basketsector` idio riding the vol state × spiral, the satellite's construction) plus its own idio (`-basketidio`, riding the vol state WITHOUT the spiral, so shared variance dominates in stress and pairwise correlation rises) and its own gaps (`-basketgaps` per year, Student-t jumps of a frozen 9% size, SYMMETRIC — the down-skew belongs to the index and reaches names through the shared leg). The equal-weight aggregate (buy-and-hold, never rebalanced) is the sector; `-emit` gains `logBasket` and `logName1..N`. Graded only against a client's ruler (`-basketruler`); about 0.2 ms and 0.23 MB a name per 100-year path | 0 (off) |
| `-basketdrift` | CROSS-SECTIONAL DRIFT DISPERSION: the sd of the names' own annual log-drift offsets, as a fraction of the primary's realized volatility, drawn once per name per path and centred exactly so the sector's log drift is untouched. Moves the SPREAD of time below peak across names, not its median. **Anchored at 0** and off in every recipe: the record cannot supply a positive value (below) | 0 (off) |
| `-basketruler` | THE CLIENT'S RULER: a file `record_bands -basket` measured from the client's closes. Grades the basket rows and the mechanism row, read under the ruler's coverage; sets N to its names and the four dials to its fitted ones (a `-basket*` flag overrides one); `-basket` other than its N, and a ruler without fitted dials, are refused; the sidecar carries the ruler (`channels.basket.ruler`) and `logBasket` leaves the file | none |
| `-solvebasket` | With `-basketruler`: fits the four basket dials to the ruler by coordinate descent at 12 × 30 from the ruler's (or the world's) dials, confirms them at `-paths` × `-years`, prints both, and writes them into the ruler's `dials` group with the solve's version, seed and primary. The fit depends on `-seed`, and the same seed reproduces it | off |
| `-sectors` | THE SECTOR CHANNEL: K sector legs as observational second-pass instances of the primary — each its beta on the primary's observed return (betas drawn once per path at the record's dispersion, centred), its own idio on the vol state (`-sectoridio`, a fraction of the primary's realized volatility) and a slow relative drift, an AR(1) state of sd `-sectordriftsd` (a fraction of the primary's annualized volatility) and half-life `-sectordrifthalf` years, centred across the legs. `-emit` gains `logSector1..K`. Graded on the ten-industry ruler as ratios that carry across primaries ([below](#the-sector-channel--sectors)). Anchored K 10, idio 0.7, drift 0.25, half-life 2 on both sets | 0 (off) |
| `-macro` | THE MACRO PANEL: 1 emits seven observables derived from the model's own state after the price loop — `macroSpread` (BAA10Y: equity + bond stress, fast and credit-cycle slow), `macroSlope` (T10Y2Y: the 10y−2y expectation the rate process implies; the one anchored-scale member), `macroCond` (NFCILEVERAGE: the leverage cycle's ratio + the crowd share, raw), `macroIvol` (VIXCLS: the conditional sd re-levelled onto the world's realized vol, × the record's variance risk premium) — each a persistent-noise read sized to the record's predictive R² — and five draw-free levels, `macroYield10` (DGS10: the 10-year the slope is a difference of), `macroPolicy` (DFF: the loop's own policy rate, re-set at a meeting to the nearest quarter point and held), and the credit system: `macroBankCredit` (TOTBKCR) and `macroOutput` (GDP) as indices with `macroCredit` (TOTBKCR/GDP, percent) the ratio they imply. No scale dials but the spread's drawdown term (`-spreaddd`, read off the record): a rank-reading consumer cannot see scale. Cadence, release lag and revisions are the consumer's point-in-time layer. Reaches no price; graded when on ([below](#the-macro-panel--macro)) | 0 (off) |
| `-macronull` | THE NULL PANEL: 1 takes the four macro columns from a SIBLING path — the same world at another seed — so their marginals and persistence are this world's and their coupling to this path's price is nil: the no-edge comparison for a rule that reads them. The macro rows do not grade a null panel; the sidecar lists its columns as ungraded. 2 is THE PAIRED CONTROL (consumer request 5): the path's own panel as at 0, graded, and the sibling's beside it as nine `nullMacro*` columns, ungraded, so the no-edge comparison rides in the same file instead of a second emit of every world. Needs `-macro 1`; one extra price loop per path at 1 or 2 | 0 (the path's own panel) |
| `-inflsize` | size of an inflation regime's rate-pressure target | 0.10 |
| `-ratemean` | the level the policy rate chases between regimes, as a decimal; the realised mean adds the inflation pressure's mean and subtracts the accommodation's, and the floor at zero binds once it is low. Searched, graded by `short rate %` and `rate floor share %` against the set's own federal-funds window | 0.042 |
| `-floorhold` | THE FLOOR HOLDS: while the equity's drawdown from its running peak exceeds this (log units), the policy accommodation does not unwind, so a rate the easing took to zero stays there until the market has recovered. The record held the floor for 7 and 2 years after 2008 and 2020 and lifted off 1.5-2.5 years after the pre-crash high was regained; at 0 the accommodation unwinds at `-unwind` from the moment stress fades and the floor is touched for days. Draw-free; the Nasdaq re-solve runs 0.05 with `-easing 0.08` | 0 |
| `-recessrate` | THE RECESSION: a fundamental decline in the disaster's shape started by stress. Each session no recession, disaster or boom runs, one begins with probability this x the stress index / 252 (per year at unit stress). The record's declines that raised volatility kept going for a year or more because earnings collapsed after the crash (2000-02, 2008); every decline in the model that raises volatility is otherwise bought back within weeks. The price falls with the earnings the same session, so the recession opens no gap for the value pull. Own stream; 0 is off | 0 |
| `-recesssize` | the recession's fall in log, spread evenly over `-recesslen` years | 1.0 |
| `-recessreprice` | the share of each recession step, and of the recovery's, the price takes the same session as the fundamental; the rest is a gap the value channel trades through the step, so the decline arrives as the market's own legs and rallies and the spiral can amplify it. 1 is the smooth repricing; searched | 1 |
| `-recessbase` | THE RECESSION'S CALM ONSET: added to the stress index in the onset hazard, so a recession can begin in a calm market and the stress arrives through the slide (`-recessvol`, `-recessnews`) rather than before it. The record's long declines open quietly: the first quarter of a 15-30 month decline carries a fifth of the fall, the last quarter half. 0 is off; searched | 0 |
| `-recessshape` | THE RECESSION'S PROFILE: the slide's sessions take shares of `-recesssize` growing as (t/T)^k, a slow start and a steep end; `-recessvol`'s multiplier follows the same share, so the turbulence peaks where the fall is steepest. 0 is the even slide; searched | 0 |
| `-recesscredit` | THE RECESSION'S CREDIT ONSET: added to the onset hazard's stress index, this x the credit growth gap the credit regime's onset reads (its excess over 0.5 sds, positive part), so a recession follows a credit expansion (2000, 2007). A calm onset with no macro antecedent dilutes the conditions index's concentration of 20% peaks (1.84x to 1.45x against the 1.4x gate); started by credit, the concentration holds. Inert where the credit stock is not evolved. 0 is off; searched | 0 |
| `-recessinfl` | THE RECESSION'S INFLATION ONSET: the same for inflation pressure above the regime edge, in units of the edge, so a recession follows tightening into inflation (1973, 2022). At 3 the bond's loss in inflation-regime crashes reads a third of the record's; searched | 0 |
| `-recessrecmult` | the recovery leg's length as a multiple of `-recesslen`: at 1 the share is regained over the decline's own length, the record's V (1933, 2009), which turns the slide-and-regain into a reversal inside the year. Searched | 2 |
| `-overshoot` | THE OVERSHOOT: the share of the spiral's amplification of a session's flow and noise that is transient — liquidity moved the price further than the order warranted, and the excess is given back over the following sessions at `-overshootrate` a session. Inside the record's declines the daily path mean-reverts (variance ratio 0.75 at 250 days, 1954-2026); the model's trended (1.68) because the asymmetric recovery freezes the buy-back in a drawdown. The overshoot is the churn a slow decline is made of, and leaves the asymmetric recovery in place (switching that off instead misses both wings). 0 is off; searched | 0 |
| `-overshootrate` | the overshoot's give-back per session. Searched | 0.1 |
| `-recesslen` | the recession's length in years; the recovery leg regains `-recessrecover` of the fall over twice this | 1.5 |
| `-recessrecover` | the share of the recession's fall regained; the rest is permanent | 0.5 |
| `-recessnews` | the news channel's event rate, and its compensator with it, multiplied while a recession runs (zero-mean; the news stream's draws are unchanged). 1 is off; probed to no gain | 1 |
| `-recessvol` | THE RECESSION'S VOL: the log of the multiplier the diffusive noise, the session's sd and the vol state the channels and the macro panel read all take while a recession runs, compounded on the credit regime's: the earnings decline runs inside a turbulent spell, as 2000-02 (40%+ for two and a half years) and 2008 did, so the decline sits in the sessions a volatility rule is in cash. 0 is off and bit-identical; searched | 0 (off) |
| `-regimedrift` | THE REGIME'S DRIFT: while a credit-regime spell runs, the real fundamental and the price fall together by this times the spell's level per session, repriced the same session so the value pull does not buy it back. The regime was a zero-mean turbulent spell; the record's turbulent spells (2000-02, 2008, 2022) were declines, which is what a rule that steps out on volatility earns from. The return it costs is re-solved by `-drift`, the floor time it adds by `-ratemean`. 0 is off and bit-identical; searched | 0 (off) |
| `-driftsd` | THE DRIFT REGIME'S SPREAD: the sd, a year, of the drift the fundamental redraws every 1-11 years. A spell of growth a few points off the mean is a trend at the 3- and 5-year horizon, and `-capyears` capitalizes it into the price; the draw is consumed at any value, so no other stream moves with the dial. Searched | 0.04 |
| `-boomfade` | THE BOOM'S FADE: the half-life in years of a boom's level after its build. At 1 the level is two thirds gone a year after the peak; a fade of years holds the level as a plateau. Searched | 1 |
| `-delevrate` | THE DELEVERAGING: forced selling starts at this rate a year for each year the price has gone without a 20% drawdown beyond `-delevfrom`, times the credit stock's growth over its trailing year in sds (floored at 0). The selling is a flow through the market's own step, so stress, the spiral and the bond's refuge bid read it and the value pull buys it back; the fundamental does not move. Own stream; 0 is off, bit for bit. Searched | 0 (off) |
| `-delevfrom` | years without a 20% drawdown before the expansion's age starts to count. Searched | 2 |
| `-delevsize` | log of return the selling takes off the price. Searched | 0.2 |
| `-delevlen` | years the selling is spread over. Searched | 0.1 |
| `-spreaddd` | THE MACRO SPREAD'S DRAWDOWN TERM: `macroSpread` carries this times the log drawdown from the trailing-year high, pp per unit — the persistent level a credit spread holds while equity is under water. Read off the record per recipe, not solved through a row: BAA10Y's level over its window median inside drawdown bins against the recipe's own index reads 0.55 / 0.77 / 2.06 / 3.44 pp at 10-20 / 20-30 / 30-50 / 50%+ on the S&P 1990-2026 (BAA-AAA monthly 1926-2026: 0.40 / 0.49 / 0.97 / 2.72) and 0.09 / 0.44 / 0.84 / 1.42 on the NDX, where the stress terms alone read a third of that; the fast response to realized vol was already the record's (convexity 4.0 against the worlds' 3.4 [1.4, 6.4]). `0.24.5-sp500` 3.0, `0.24.5-nasdaq` 1.5, at which every bin reads inside the worlds' 5th-95th; the term is smoothed at a 10-session half-life because the record's spread FOLLOWS the price: after a 10-session fall of 10% BAA10Y has risen 0.03 pp the same session, 0.14 five sessions on, 0.21 at ten, 0.29 at twenty and 0.24 at forty (S&P), and its 10-session change correlates most with the return ending five sessions earlier (-0.44 at -5, -0.29 at -10); with the smoothing the worlds' shock response reads 0.16 / 0.28 / 0.34 / 0.37 / 0.25 and the change's price-driven share 0.40 against the record's 0.22 (0.58 unsmoothed, 0.32 without the term). Reaches no price; 0 is off and bit-identical; read off the record, held there in a search with `-fix` | 0 (off) |
| `-disasteranticipate` | THE DISASTER'S ANTICIPATION: from a disaster's trough on, the value pull's target, the beliefs' growth read and the value crowd's gap read the fundamental plus this share of the recovery still to come, so the price turns at the trough and rebounds ahead of the fundamental. Read off Shiller's earnings 1871-2023, real and nominal alike: the price bottomed before the earnings in seven of ten collapses of 30%+ and regained before them, while in 1929-32, the collapse this channel stands for, it fell 0.8 of the earnings' decline, so the decline is priced as it comes. Anticipating the decline too took the S&P century's worst crash to the 2nd-8th percentile of histories. At 1 with `-disasterrecover` 0.77 the S&P recipe's annual-return autocorrelation reads +0.09 against +0.12 (record −0.07): the worlds' momentum is mostly the disaster's decline leg, 2.0 log spread evenly over 2.5 years against 1929's 1.1-1.4. `0.24.5-sp500` carries 1; 0 is off and bit-identical; read off the record, held there in a search with `-fix` | 0 (off) |
| `-disasterovershoot` | THE DISASTER'S OVERSHOOT, the valuation leg: while a disaster declines the market sees the fundamental less this share of the decline so far, so the multiple compresses as the collapse deepens and the price falls further than the fundamental; at the trough the overshoot is gone and the price rebounds ahead of it (further with `-disasteranticipate`). Read off 1929-32, the collapse the channel stands for: against trend, nominal earnings fell about 1.5 log over three years evenly and half came back ahead of trend within five, while the price (CRSP daily) fell 1.84, 0.47 further than earnings, and took back 0.40 of its fall within a year of its low. At those readings (`-disastersize 1.5 -disasterlen 3 -disasterrecover 0.5 -disasterreclen 5 -disasterovershoot 0.31 -disasteranticipate 1`) the S&P recipe's disasters read a price fall of 1.92 and 0.41 regained within a year, the century's worst crash at the 25th-35th percentile of histories, and the annual-return autocorrelation +0.08 (+0.12; record −0.07); the form alone lowers valuation dispersion (0.205 to 0.18, record 0.30), which the rest of the recipe re-solved around it lifts back: `0.24.5-sp500` carries the form at these readings and reads 0.31. 0 is off and bit-identical; read off the record, held there in a search with `-fix` | 0 (off) |
| `-volpull` | THE REBOUND WAITS FOR VOL TO SUBSIDE: the value pull's damp is divided by 1 + this x (the spiral's fast realized sd over its four-year level, less 1, floored at 0), so a gap opened in a high-volatility decline is bought back as volatility settles. What a volatility-timing rule's cash sessions inside a recovery leg return: +18 bp on the record, +27 in the worlds. 0 is off | 0 |
| `-discountlag` | the rate the equity's markdown reads is an EWMA of the policy rate at this horizon in years. 0 is off, and the right setting: a lag removes the equity's fall WITH a rate rise, and with it the inflation-regime episodes the bond's inflation crash is read in | 0 |
| `-discountref` | the level the markdown measures the rate from is an EWMA of the rate at this horizon in years instead of `-ratemean`. 0 is off; probed to no effect on the wings | 0 |

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
setting                        vol  kurt  clus    vr crash   d10  bdep   r/v tshare  cflow  gate
-------------------------------------------------------------------------------------------------
DEFAULT                       1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P

-stress 2.0                   0.88  0.52  0.70  1.09  0.67  1.63  1.11  1.19   0.21   3.28  P/F/F
-stress 5 *                   1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-stress 8.0                   1.43  1.86  1.51  1.02  1.68  0.91  1.46  0.72   0.20   3.90  F/P/F

-levgain 0                    0.99  0.65  0.84  1.06  0.97  1.28  1.25  1.06   0.20   3.56  P/P/P
-levgain 6 *                  1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-levgain 10                   1.13  1.45  1.17  1.05  1.21  1.11  1.40  0.93   0.20   3.64  F/P/F

-depth 12                     1.73  0.98  1.30  0.89  2.34  0.76  1.33  0.59   0.20   4.73  P/P/F
-depth 17.4 *                 1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-depth 22                     0.86  0.73  0.84  1.16  0.71  1.76  1.31  1.22   0.21   3.16  P/P/F

-drift 0.08                   1.07  0.90  1.04  1.04  1.01  1.27  1.33  0.61   0.20   3.73  P/P/F
-drift 0.122 *                1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-drift 0.15                   1.01  0.94  0.97  1.07  1.02  1.27  1.32  1.28   0.21   3.49  P/P/F

-fundvol 0.041                1.02  0.94  0.97  1.01  0.99  1.14  1.32  1.01   0.20   3.59  P/P/P
-fundvol 0.06 *               1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-fundvol 0.10                 1.07  0.94  1.00  1.22  1.21  1.53  1.35  0.97   0.21   3.57  P/P/F

-jumpvar 0                    1.01  0.58  1.04  1.05  1.01  1.25  1.31  1.03   0.20   3.65  P/P/P
-jumpvar 0.16 *               1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-jumpvar 0.23                 1.05  1.30  0.97  1.07  1.08  1.23  1.34  1.00   0.20   3.55  P/P/P

-jumpskew 0.7                 1.04  0.92  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-jumpskew 0.65 *              1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-jumpskew 1.3                 1.04  0.90  1.00  1.07  1.08  1.21  1.33  1.00   0.20   3.60  P/P/P

-jumprate 0.002               1.04  1.16  1.00  1.07  1.07  1.23  1.33  1.00   0.20   3.57  P/P/P
-jumprate 0.0035 *            1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-jumprate 0.012               1.04  0.70  1.00  1.06  1.09  1.21  1.31  1.00   0.20   3.65  P/P/P

-leverage 0                   1.01  0.80  0.87  1.06  1.00  1.28  1.29  1.03   0.20   3.58  P/P/P
-leverage 0.1 *               1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-leverage 0.20                1.08  1.34  1.14  1.05  1.15  1.16  1.37  0.96   0.20   3.60  P/P/F

-volpersist 0.990             1.04  0.99  1.00  1.07  1.06  1.21  1.34  1.00   0.20   3.59  P/P/P
-volpersist 0.982 *           1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-volpersist 0.996             1.06  1.09  1.04  1.08  1.06  1.19  1.34  0.99   0.20   3.56  P/P/P

-newsrate 0                   1.02  0.96  1.07  1.04  1.00  1.15  1.33  1.01   0.20   3.56  P/P/P
-newsrate 1.3 *               1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-newsrate 2.5                 1.06  0.86  0.94  1.08  1.13  1.29  1.34  0.99   0.21   3.61  P/P/P

-refugedays 0                 1.04  0.94  1.00  1.06  1.06  1.23  1.27  1.00   0.20   3.59  P/P/P
-refugedays 1 *               1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-refugedays 5                 1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P

-haltlimit 0                  1.04  0.94  0.97  1.05  1.06  1.23  1.33  1.00   0.20   3.59  F/P/P
-haltlimit 0.25 *             1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-haltlimit 0.40               1.04  0.94  0.97  1.05  1.06  1.23  1.33  1.00   0.20   3.59  F/P/P

-disasterrate 0               1.03  0.97  0.97  0.98  1.11  1.12  1.32  1.06   0.20   3.63  P/F/F
-disasterrate 0.6 *           1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-disasterrate 1.2             1.05  0.96  1.00  1.14  1.00  1.30  1.33  0.94   0.20   3.56  P/P/F

-beliefshare 0                1.04  0.93  0.97  1.10  1.17  1.16  1.33  1.01   0.21   3.61  P/F/F
-beliefshare 0.95 *           1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-beliefshare 0.99             1.04  0.94  1.00  1.05  1.05  1.23  1.33  1.00   0.20   3.59  P/P/P

-beliefyears 0.75             1.04  0.91  1.00  1.03  0.97  1.30  1.33  1.00   0.20   3.57  P/P/P
-beliefyears 1.5 *            1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-beliefyears 5                1.04  0.94  0.97  1.09  1.13  1.19  1.33  1.01   0.21   3.61  P/P/F

-beliefleak 0                 1.04  0.96  1.00  1.06  1.01  1.26  1.33  0.94   0.20   3.53  P/F/P
-beliefleak 0.2 *             1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-beliefleak 0.5               1.04  0.97  0.97  1.07  1.09  1.21  1.33  1.01   0.21   3.60  P/P/P

-capyears 0                   1.03  0.93  0.97  1.00  1.05  1.08  1.32  1.01   0.20   3.62  P/F/F
-capyears 1.5 *               1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-capyears 3                   1.05  0.93  1.00  1.13  1.06  1.39  1.34  0.99   0.21   3.56  P/P/P

-cyclesd 0                    1.02  1.00  1.04  1.06  1.03  1.24  1.33  1.01   0.20   3.50  P/P/P
-cyclesd 0.1 *                1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-cyclesd 0.4                  1.27  0.47  0.60  1.05  1.62  1.26  1.33  0.81   0.20   4.46  P/F/F

-cycleyears 5                 1.07  0.85  0.90  1.06  1.14  1.22  1.33  0.97   0.20   3.75  P/P/P
-cycleyears 15 *              1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-cycleyears 20                1.03  0.95  1.00  1.06  1.05  1.23  1.33  1.00   0.20   3.57  P/P/P

-trendshare 0.02              1.03  0.92  0.97  1.05  1.04  1.24  1.33  1.00   0.18   3.18  P/P/P
-trendshare 0.055 *           1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-trendshare 0.30              1.07  0.95  1.07  1.10  1.17  1.21  1.35  0.97   0.37   6.50  P/P/F

-crowdimpact 0.010            1.01  0.90  0.97  1.03  0.97  1.27  1.31  1.03   0.20   1.19  P/P/P
-crowdimpact 0.03 *           1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-crowdimpact 0.12             1.21  1.24  1.27  1.23  1.62  1.11  1.43  0.86   0.21  14.71  P/P/F

-value 0.020                  1.03  1.00  1.00  1.12  0.96  1.43  1.36  1.00   0.20   3.50  P/P/F
-value 0.056 *                1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-value 0.070                  1.04  0.95  0.97  1.05  1.07  1.19  1.32  1.00   0.20   3.62  P/P/P

-recoverydrag 0               1.03  0.88  0.97  0.84  1.10  1.04  1.27  1.01   0.20   3.69  P/F/F
-recoverydrag 8.5 *           1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-recoverydrag 20              1.03  0.92  1.00  1.07  1.06  1.27  1.33  1.00   0.20   3.58  P/P/P

-recoveryfloor 0.05           1.04  0.93  1.00  1.09  1.01  1.28  1.33  0.99   0.20   3.58  P/P/P
-recoveryfloor 0.1 *          1.04  0.94  1.00  1.06  1.06  1.23  1.33  1.00   0.20   3.59  P/P/P
-recoveryfloor 0.50           1.03  0.92  0.97  0.94  1.13  1.09  1.31  1.01   0.20   3.65  P/F/F
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
  falls to 0.65 and clustering to 0.84 (the conditions index's hazard row survives at this
  ensemble, where it used to fail); at 10 the fragile phases run away — kurtosis 1.45, clustering
  1.17, realism fails. 6 is where the cycle's cascades supply what the jump channel gave up with
  the tail budget unmoved.
- **The valuation cycle is a PAIR, and each half alone fails a different gate.** `-beliefshare 0`
  (cap term alone) pushes the variance ratio to 1.10 — re-climbs toward extrapolated fair are
  60-day trends — and the cycle's own mechanism row with it; `-capyears 0` (beliefs alone) reads
  all lower wing (vr 1.00, d10 1.08) and fails the same row. The belief half REFUNDS variance ratio
  (its perceived-fair tracking cuts medium-horizon drift-chasing), which is exactly the budget the
  cap half spends: together at the defaults they read vr 1.06.
- **`disasterrate` is the century-tail dial and almost nothing else.** Off, the world fails the
  mechanism gate (the channel is inert) and the record's century-worst returns to the far tail of
  what the model can produce; at 1.2 the variance ratio reads 1.14 and a fidelity band FAILS,
  because more multi-year declines is more signed persistence. Daily volatility, kurtosis and
  clustering barely move at any setting — rarity is what buys that.
- **`crowdimpact` is a mechanism dial again, not a trend dial.** Since 0.22.0 the crowd's price
  pressure comes from the exposure it TRADES in a session rather than the exposure it holds, so
  running it harder no longer manufactures drift. `-crowdimpact 0.12` is four times the default and
  reaches 14.7 bp/session of crowd flow — a fifth of the noise term — while `vr` only reaches 1.23.
  Under the old law the shipped default was already at 1.52 on 4.7 bp. What running it hard DOES
  buy is crash frequency (1.62), clustering (1.27) and kurtosis (1.24), which is why the default is
  not there.
- **`stress` is not a volatility dial.** It is one amplifier producing volatility, fat tails *and*
  volatility clustering together. Raising it 5.0 → 8.0 takes volatility from 1.04 to 1.43 and
  clustering from 1.00 to 1.51 — and fails the realism gate. You cannot buy tails *here* without
  buying clustering.
- **`recoverydrag` and `value` are one pair, and neither reads correctly alone.** The base pull
  governs shallow water; the drag governs deep drawdowns. Turn the drag off and leave the pull at
  its shipped 0.056 and `d10` falls to 1.04 with a mechanism row failing. Weaken the pull instead
  and `d10` runs to 1.43 with a fidelity row failing. Both also move `vr` — the drag off reads
  0.84, the weak pull 1.12 — so a world tuned for time under water on this pair alone lands its
  serial persistence somewhere it did not choose.
- **`jumpvar` and `jumprate` are ONE dial with two handles, and that is what makes the tail
  reachable.** `-jumpvar 0` is this world with the tail channel off and nothing else moved: the jump
  draws come from their own RNG stream, so no other statistic shifts by construction. Turning it on
  moves `kurt` 0.58 → 0.94 at the shipped pair — the leverage cycle's cascades and the leverage
  kick carry the rest of the tail budget, which was all jumps in 0.22.1. **The ceiling is the realism band, not the target**,
  and the band is what `jumprate` buys headroom against: at the old rate of 0.0010, `-jumpvar 0.12`
  passed at the default seed and read kurtosis 35.2 on another, against a ceiling of 30. Raising the
  rate to 0.0030 means the same jump variance arrives as more, smaller jumps, so no seed runs away —
  the five tried read 19.5 to 27.6 — and the *median* kurtosis rises rather than the extremes.
  Rarer jumps are not fatter tails; they are noisier ones. Set the pair by the band, on more than one
  seed.
- **`fundvol` is the dial for time under water, and it is not free.** Raising `-fundvol` 0.041 →
  0.10 moves `d10` from 1.14 to 1.53 while volatility and return-per-volatility sit still — the
  fundamental accumulates into drawdown depth without reaching daily return scale. It pays in trend:
  `vr` goes 1.01 → 1.22. The shipped 0.060 is where the two meet (0.070 before the leverage cycle,
  whose fragile phases lengthen time under water on their own), and it is the dial that carries the
  drawdown rungs now that the crowd no longer does.
- **The two bond dials are almost orthogonal to the equity leg, but the reverse is not true.**
  `-easing` and `-refuge` move `bdep` and leave volatility, kurtosis and clustering untouched. The
  equity world nonetheless moves the bond ladder through the correlation channel — 0.22.0 had to
  re-solve `easing` twice while the equity side settled. `-crossasset` is the only thing that shows
  this; run it after any change, not only a bond one.
- **The asymmetry trio reads exactly as designed, and `-leverage 0` shows what the kick now
  carries.** Off, clustering falls to 0.87 and kurtosis to 0.80 — the transient multiplier is a
  fifth of both — and with the bar channels on, range clustering sits on its 0.57 floor
  (0.56–0.57 across seeds; the sweep table was run with the channels off) — while `-newsrate 0`
  pushes clustering back UP to 1.07 (serially-independent news days are what dilute it) and drains
  the downside excess. `-refugedays` moves `bdep` a little and nothing else in this table; its real
  work, the calm-day tail hedge, is a row the table does not carry.
- **The start is a dial pair too, and `-beliefleak 0` is the only setting here that fails the
  stationarity row alone.** With the fade off the world is the 0.24.1 one, every level inside its
  band, and the valuation gap walks under the fundamental for a century: the mechanism gate fails
  on `valuation stationary from the first session` and nothing else moves (r/v 0.94, d10 1.26).
  The cycle is cheap at 0.1 and ruinous at 0.4: volatility 1.27, kurtosis 0.47, clustering 0.60,
  crashes 1.62, two gates failing — each 0.1 of amplitude is a point of pooled vol and a thinner
  tail, which is why the S&P default carries 0.1 and its upper wing stays a disclosed miss.
- **Some settings leave the admissible region.** `-stress 8.0`, `-levgain 10` and `-haltlimit` at
  0 or 0.40 fail realism (clustering, kurtosis, or the tail-shape checks); `-stress 2.0`,
  `-disasterrate 0`, `-beliefshare 0`, `-capyears 0`, `-recoverydrag 0`, `-recoveryfloor 0.50`,
  `-beliefleak 0` and `-cyclesd 0.4` fail a mechanism row; `-drift` at either end, `-depth` at
  either end, `-fundvol 0.10`, `-leverage 0.20`, `-disasterrate 1.2`, `-beliefyears 5`,
  `-trendshare 0.30`, `-crowdimpact 0.12` and `-value 0.020` fail only fidelity — the level of one
  quantity stops being readable. Always re-run `-validate` after changing a dial.
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
and `test-data/bond-anchors/crash-response-2026-09-25.tsv` — which the test suites re-derive the
shipped targets from, so the account above is checkable rather than asserted. The bond rows are
read at the model bond's 13.5-year duration: each TLT episode is scaled by 13.5 over the fund's
empirical duration on that span, the footing `bond vol % (24y)` is on (+7.0 growth, −27.9 inflation).
The report prints a longer reference under them, reported and never graded: over the market's 17
declines of 15% or more since 1962, a 20-year Treasury built from FRED's DGS20 yields read +9.1 on
the ten growth episodes and −6.3 on the seven inflation ones (inflation when CPI rose over the
decline and stood at 4% or more at the trough; `crash-response-1962-2026-10-05.tsv`). The 1960s–80s
inflation episodes cost the bond 3–16 points where 2022 cost 29, so the graded target stays the 2022
episode the model's own regime names, and a reader sees what a longer record would have said.

**An anchor with no recorded convention is the failure mode.** Every one of the three had a window
named and no statement of what statistic it was, and that is exactly what let a 20%-threshold median
and a single 2008 print sit in a target set for four releases. When you add a target, record the
function, the window and the convention, and commit the measurement it came from.

## Grading against a different index — `-anchors`

Every equity fidelity target used to be the S&P's, so a world calibrated to any other index failed
the target set for *being* that other index. `-anchors nasdaq` swaps in a QQQ vector measured over
1999-03-10 to 2026-08-20: volatility 26.90%, return per volatility 0.38, kurtosis 9.55, 25.6 crashes
per century, median depth −22.8%, worst −83.0%.

**The typical year — `typical-year vol %`.** Pooled volatility cannot tell an ordinary year from an
episode, so beside it both sets grade the median-year vol, averaged over all 252 block phases
(`recordbands-2026-09-26.tsv`): 20.0% for QQQ, 12.5% for CRSP from 1954, each with a gate band one
sd of the row's own single-history spread wide (16.4-23.6 and 10.9-14.1). One series' median year
depends on where its years start — QQQ's reads 18.2 to 21.5 across the phases, and calendar years
sit at the bottom (18.3) — while a model ensemble averages the phase away, so the anchor has to as
well. The two rows read the Nasdaq record differently on purpose. On calendar years QQQ's median
year is 0.68 of its pooled vol where QQQ from 2007, SPY and CRSP all read 0.82-0.83
(`yearvol-2026-09-15.tsv`), because the 1999-2026 window's volatility is one episode — 2000, 2001
and 2002 at 58, 55 and 42%. The Nasdaq recipe's typical year, 19.4%, sits at the 34th percentile of
QQQ's resamples, so its pooled miss (24-25% against 26.9%) is the bust's; the S&P default's 13.2%
sits at the 86th percentile of CRSP's, an ordinary year about 5% hot. The row exists so that a
search cannot close the pooled row by making every year more volatile: a world that reaches 26.9%
pooled with a typical year well above 20% has spread the bust across its calm years, which no
window of the record does.

**The wings — `upper wing months %` / `lower wing months %`.** The valuation cycle's time far
above and far below its own 20-year mean, on both sets from one series: Shiller's CAPE spends 7.6%
of its months more than +0.5 log over that mean and 6.7% more than −0.5 under it
(`mania-2026-09-15.tsv`; four spells past +0.5 in 123 years: 1929, 1937, 1999, 2021). The rows are
pooled over paths, because a spell past +0.5 is a once-in-decades event and a median of per-path
shares would read zero. The model's cycle has the record's amplitude (sd 0.36 against 0.375) with
the wrong sign: the Nasdaq recipe reads 2.5% above and 14% below, the S&P default 0.2% and 13%.
Crashes take the price far under its mean and nothing takes it far over. `valuation dispersion` is
sign-blind and could not see this; these two rows are what a mania-capable world has to read, and
the search re-solves `beliefYears` beside `beliefShare` and `capYears` for them. The spread frozen
for both is the record's own, a moving-block bootstrap of the level series that puts the share's
relative sd at 0.60, because a world that never makes a wing reads no sampling spread for it.

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
no longer supplies (against the band's floor, then 23.5%), `refuge` 0.15 the bond's rally and `levGain` 8
the hazard (1.47–1.50 on six seeds, build-up 0.83–0.85); `stress` 4.4 and `jumpVar` 0 as before.
All three classes PASS on six seeds, volatility 24.1–24.5% against the band's floor, then 23.5%. What the scale does not buy, disclosed: the crash count
stays at 36–37 per century against 25.6, because diffusion alone at this volatility crosses 15%
thirty times a century (`-stress 0.01` reads 29.5) and the volatility band forces the depth
that buys them; and lag-20 clustering gives 0.22 → 0.18 against the record's 0.25 — the model's
|r| autocorrelation decays from lag 1 where the record's rises to a hump at lags 2–5 and holds
0.13–0.17 at lags 60–120 on both references (the profile rows of `amplifier-2026-09-07.tsv`,
reported), a persistent asymmetric vol response the amplifier's single timescale cannot give
without paying kurtosis. On the S&P default the same open reads 0.328 at
`-overnight 0.20` with the same bar dials (range vs cc vol 1.097, down/up 1.135).

The Nasdaq set's sampling spreads are measured at the current recipe (`-noise -atrelease
0.24.4-nasdaq`, 200 paths), not carried from the S&P's — every spread, since 0.23.1 including the depth rungs, the
valuation proxy and the bond rows, which through 0.23.0 read the S&P world's inline constants for
both sets; the wings' alone are the record's own block bootstrap. The deep rung is where it
matters: d20's spread at this recipe is 0.53 against the S&P default's 2.27, so the row carries
real weight here where the S&P loss all but ignores it. The loss at this recipe reads 1.161 under
its own spreads, so a `-calibrate -anchors nasdaq` result from any earlier release optimised a
different function, and the 0.24.4 set's `score` and `worstRow` are the search's readings under
the spreads of the recipe before it.

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

  The 250 rung is graded against A RECORD OF THE SET'S OWN INDEX, not that envelope. The
  envelope's top, 1.30, is one reading rounded up — the CRSP century's 1.255 — and the same market
  reads 1.034 from 1954 and 0.796 from 1990: at 250 sessions the era is the axis. The rung passes
  when the set's era nearest the run's years falls inside the 5-95th percentile of the world's
  histories of that era's length, cut from the run's paths, the rule the single-history rows
  follow: for the S&P the CRSP reading over 1926-2026, 1954-2026 or 1990-2026, for the Nasdaq the
  1971-2026 splice (1.204) at any run length. Under 20 paths the record cannot be placed and the
  rung keeps the envelope. A slow-decline world reading 1.33 on century paths sits beside the
  century's 1.255, not outside the record.

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

### Each set on its own record

A row that reads the reward a timing rule earns cannot test whether that reward lasts when it is
read off the window the rule was selected on. The Nasdaq consumer's rules were selected on the NDX
from 1990, so the Nasdaq set grades its timing rows, its bubble coupling, its long-window
multi-year rows and the variance-ratio profile's 250-session rung on the 1971-2026 splice: the
Nasdaq Composite's daily returns through 1985-10-01 and the Nasdaq-100's after, both price indexes
read from Yahoo Finance's ^IXIC and ^NDX histories
(`record_bands -yahoo IXIC.csv -splice NDX.csv -at 1985-10-01`). The same rows read off the NDX
from 1990, QQQ and CRSP's century are reported, not graded (`reportedRows`). The unconditional rows
— volatility, tails, the up-day share — stay on QQQ 1999-2026: they set the world's
scale, and the traded fund has no longer record. The S&P set grades CRSP throughout.

| row | splice 1971-2026 | NDX 1990-2026 | CRSP 1926-2026 |
|---|---|---|---|
| `sma10 decline avoided %` | 59.8 | 52.6 | 64.7 |
| `sma10 false-exit return %` | 7.66 | 7.67 | 5.82 |
| `sma10 exits per year` | 0.81 | 0.82 | 0.75 |
| `market sign12 trend %/mo` | 1.10 | 1.53 | 0.66 |
| `bubble coupling 3y` | +1.10 | +1.04 | |
| `variance ratio 3y long`, `5y long` | 0.95, 0.91 | 0.98, 0.96 | |
| `decline gap p90 y long` | 8.0 | 18.4 | |
| `under water 20% % long` | 39.6 | 41.9 | |
| variance ratio 250d (the profile's rung) | 1.20 | 1.06 | 1.26 |

The splice and the NDX are price indexes; CRSP is total return.

`0.24.6-nasdaq` on seeds 1-4 at 200 × 100 passes every class and reads every graded splice row
inside its band but one: the avoided share's record at the 88th-90th percentile of the worlds'
56-year histories (98th-100th against CRSP's century, outside its band on two seeds), the
long-window multi-year rows between the 2nd and 38th, the 250-session rung at the 34th-39th. The
bubble coupling misses on three seeds of four, the record at the 95th-97th percentile (93rd-95th
against the NDX from 1990). All 30 members of `0.24.6-nasdaq.json` pass the set rule; their avoided
share reads 39.6% and their coupling +0.14.

## A basket of names — `-basket`

**The basket is a null world.** `-basket N` adds N anonymous, exchangeable names: each is a shared
leg on the primary plus its own idio and its own symmetric jumps, all at the same expected drift.
No name persists ahead of another and no drift is dispersed, so a rule that ranks names has zero
expected edge here. What the basket measures for such a rule is its false-positive rate, and, with
`-basketdrift` swept upward, the dispersion it would need before it could see anything. A client's
own closes decide only *whose* co-movement the names reproduce. A world carries one basket: a
cross-asset cohort (bonds, gold, a volatility index) is not a basket of exchangeable equity names.

Nothing here reaches a price, and `-basket 0` is bit-identical. Each name is the shared **sector
leg** plus its **own idio** plus its **own gaps**:

- **Sector leg** (shared by every name): beta on the primary's observed return plus idio riding the
  vol state and the spiral, the satellite's construction.
- **Own idio**: rides the vol state alone, so shared variance dominates in stress and pairwise
  correlation rises.
- **Own gaps**: a Student-t jump stream of the name's own.

The model has no sector index, so the basket's equal-weight aggregate *is* the sector. The cost is
about 0.2 ms and 0.23 MB a name per 100-year path: a 200 × 100 verdict reads in 1.2 s and 2.1 GB at
8 names and in 2.4 s and 3.8 GB at 45, and a 200-path, 56-year f32 chunk with every column grows
from 549 to 947 MB.

### The client's ruler

**No set grades a basket by default.** The basket rows grade only against a **ruler** the client
measures from its own closes and names on the run:

1. **The closes.** One wide CSV per universe: `date`, then the index's column named by its ticker,
   then one column per name. Use adjusted closes, dividends reinvested, one row a session. Leave a
   name's cell empty where it has no close, before its listing or after a delisting.
2. **The ruler.** `record_bands -basket -closes WIDE.csv -closesdate D [-from D -to D] -out
   RULER.tsv` (`jsrc/recordBands.sc` is its Scala twin, byte-identical). `-closesdate` records
   when the closes were taken, since an adjusted series is recomputed on every later distribution.
3. **The dials.** `market_sim -basketruler RULER.tsv -solvebasket` fits the four dials (`-basketbeta`,
   `-basketsector`, `-basketidio`, `-basketgaps`) by coordinate descent at 12 × 30, confirms them at
   the run's `-paths` × `-years`, and writes them into the ruler's `dials` group. The group also
   records the solve's version, seed and the primary it was solved on. The descent reads small,
   noisy ensembles, so the fitted dials depend on the solve's `-seed`; the same seed reproduces
   them exactly, and fits from different seeds can differ while each passes its rows. Solve on the world the basket
   will run in; a run on another primary prints a note and grades the transport.
4. **The runs.** `-basketruler RULER.tsv` grades the basket rows and the mechanism row. It sets N
   to the ruler's names and the dials to the fitted ones. A `-basket*` flag overrides one dial;
   `-basket` other than the ruler's N, 0 included, is refused, and so is a ruler without fitted
   dials. The dials resolve into the sidecar's `world` block and the world digest like any other
   dial.

`channels.basket.ruler` makes a bundle self-describing without the file. It carries the file and
its digest, the index, the basis, the closes' date, the window, each name's coverage, the rows with
their records and bands, the mechanism, and the dials. Without a ruler, a basket is emitted
ungraded (`"ruler": null, "graded": false`, the names in `ungradedChannelSeries`). With one,
`logBasket` leaves the file: the rows read the names, never the aggregate column, and an `-emitf32`
run's `-emitcols logBasket` brings it back. The `*-basket` recipes keep their dials and 8 names,
ungraded without a ruler. Survivorship and point-in-time membership are the client's choice of
names, and the sidecar records them.

**Every row is read the same way on the record and on the model** (`basket_read`). The record's
aggregate holds the names listed at each point of its window, and the rows built on it move with how
many it holds. Diversification goes as 1/N: at pair correlation 0.3, an aggregate of 10 names
carries about 8% more volatility than one of 45. So the model reads its basket under the ruler's
**coverage**:

- Name k counts from its listing's share of the window onward on each path, and stops at its
  delisting's, in the aggregate, the idio share, the tail coincidence and the pair correlations.
- The emitted names keep every session: the coverage governs the reading, not the simulation.
- With names listing over time, the aggregate's worst 1% of sessions fall mostly in the early,
  thinner, higher-volatility years, so the tail-coincidence row reads mostly those years, on both
  sides alike.
- A name with fewer than 252 sessions (`minSessions`) stays in the aggregate and the tail
  coincidence. It is left out of the per-name ranges and of every pair; a pair also needs its spans
  to overlap that long.
- The per-name and cross-section bands are the universe's own ranges, so a dispersed universe
  grades loosely: a 45-name cyclical sleeve's gap rates span 0.5–30.4 a year. That is the honest
  reading of such a universe, not a defect of the band.

| level | rows | band from the ruler |
|---|---|---|
| per name | vol ratio to the index over the name's own span; sessions past 10% per year | the graded names' range, outward to 0.1 |
| the aggregate vs the index | corr; beta; vol ratio | ±0.10 at 0.01; ±0.25 and ±0.30 outward to 0.1 |
| the cross-section | pairwise corr; idio share (1 − R² on the aggregate); same-day tail coincidence | the pairs' and names' ranges, outward to 0.01; −0.13 / +0.12 |

The mechanism row is what a beta-plus-noise leg cannot pass: pairwise correlation on the index's
worst decile of days must exceed its central decile's. The split between shared and idiosyncratic
variance produces it, because only the shared part rides the spiral. A ruler whose own record fails
that premise is refused, since the row could not mean anything against it.

### The example ruler

`basket-ruler-smh8-qqq-2026-10-04.tsv` is an **example**, not a default: SMH's eight largest
holdings as of 2026-10-02 (NVDA, TSM, AMD, AVGO, MU, INTC, AMAT, KLAC) against QQQ, 2012-01-04 to
2026-08-31, from Yahoo Finance's adjusted closes. Its dials are `-solvebasket`'s on
`0.24.6-nasdaq-basket`: beta 1.345, sector 0.8, idio 1.025, gaps 7.0. At 200 × 100:

| level | rows | record | band | model |
|---|---|---|---|---|
| per name | vol ratio; gaps per year | 1.93; 2.03 | 1.5–2.8; 0.4–5.1 | 2.09; 4.03 |
| the aggregate vs QQQ | corr; beta; vol ratio | 0.840; 1.345; 1.60 | 0.74–0.94; 1.0–1.6; 1.3–2.0 | 0.822; 1.346; 1.64 |
| the cross-section | pair corr; idio share; tail coincidence | 0.554; 0.414; 0.446 | 0.39–0.85; 0.25–0.53; 0.32–0.57 | 0.562; 0.384; 0.482 |
| mechanism | pair corr, worst decile vs middle | 0.511 vs 0.205 | worst above middle | 0.500 vs 0.184 |

The gap rate reads high by construction. At a name's volatility, diffusive moves past 10% already
supply about 2.3 a year with no own gaps at all. The own-gap dial trades the remainder against the
pair correlation, the idio share and the tail coincidence, so the solve settles where every row is
inside its band, not where the gap rate matches. The names' time more than 20% below their running
peak (0.77 here, against the eight's 0.08–0.61) is **reported, not graded**, for the reason below.

### Why the names sit below their peaks, and what `-basketdrift` does about it

The names spend more of their time than the record's do more than 20% below their running peak —
0.77 at the example ruler's fitted dials, where its eight read 0.084–0.610 with a median of 0.331. That row is **reported, not graded**. What is in it is the **common drift**, and
that is survivorship.

Measured over the eight's own window (`basket-drift-2026-10-03.tsv`, 2012–2026, T = 14.6 years):

| | the eight | without INTC |
|---|---|---|
| spread of realized log drift across names | 0.095 | 0.065 |
| noise that a 14.6-year window generates on its own | 0.072 | 0.071 |
| true dispersion left over | 0.062 | **0** |
| their common drift | +0.297 | |

A name's realized drift over a finite window is its true drift plus estimation noise of
sd (idio vol)/√T, so a cross-section is dispersed even when every true drift is identical. The
eight's spread exceeds that floor by 0.062 a year, and all of it is one name: INTC's +0.114 against
the other seven's +0.262 to +0.447. And the list is selected today — survivors — which truncates
the left tail. One name in a survivor sample does not calibrate a dial.

The gap that is left is the level: +0.297 a year for the eight against the shared leg's +0.117 over
the same horizon. These are the names that won. Dispersion around the right centre cannot close
that, and the dial shows it — at 200 × 100 the spread of time below peak runs 0.14 (off, pure
estimation noise) → 0.52 at 0.6 → 0.67 at 0.9, while the median moves only 0.528 → 0.557 and the
aggregate's correlation, beta and volatility ratio do not move at all. Raising the centre to +0.297
would close the row and would be calibrating the model to names selected for having won, so it is
not on offer.

Two mechanism properties keep that reading honest, and both are pinned by
`BasketDriftSuite` / `basket_drift_tests`. A name's **own gaps are symmetric**, so the gap channel
imposes no drift of its own: the down-skew belongs to the index, which already reaches every name
through the shared leg (across the eight, own moves past 10% run 43 up to 37 down with mean +0.007,
while SMH itself reads 1 up to 3 down at skew −0.28). And the **drift offsets are centred exactly**,
so turning the dial up moves the cross-section without moving the sector.

So `-basketdrift` ships at 0, ungraded, and no recipe turns it on. Run the same decomposition on
the model's own names over a 15-year window and the spread of realized drift is 0.090 against a
noise floor of 0.081, a ratio of 1.11; the eight read 1.32 with INTC and 0.92 without it. (The
naive floor understates a fat-tailed, clustered series, which is why the model's ratio sits
slightly above 1.)

### The dial's real use: a null world for a rule that ranks names

At `-basketdrift 0` every name has the same expected drift **by construction** — the sector leg is
shared, and the idio and gap terms have the same mean for each name. So the basket at 0 is a
**null world** for cross-sectional selection: no name is better than another in
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
| `macroSpread` | BAA10Y, pp | equity and bond stress: the fast index plus a ~400-session credit cycle, plus the drawdown from the trailing-year high times `-spreaddd` (the level a spread holds while equity is under water, read off the record), plus a credit-market factor as slow as the level itself | one normal per session |
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
the slope, in rate units the model anchors, is the exception and is graded absolutely, and the
spread's drawdown term (`-spreaddd`) is read off the record rather than left to a literal.
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
| conditions HAZARD (mechanism): a 20% peak within the next quarter, with the index in its top decile, as a multiple of the unconditional chance; within a year and for 10% dips reported | 2.72 / 2.20; 1.24 / 1.18; 1.63 / 1.26 | **1.68**; 1.39; 1.35 | 2.04 / 1.44; 0.79 / 0.40; 1.10 / 0.90 | **1.47**; 1.23; 1.14 |
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
next quarter 2.0–2.7× as often as unconditionally on CRSP, SPY and NDX (1.44× on QQQ's four
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

**What a warning is worth** (reported, never graded). Read the index's top-decile stretches as
warnings, with runs fewer than a quarter apart merged into one. The record has had 20 since 1990,
16 over QQQ's window.

- **False alarms:** most warnings are false. No 20% peak followed within a quarter of the warning's
  end for 75% of them on CRSP, 85% on SPY, 80% on the NDX and 88% on QQQ. The worlds read 79%
  (`0.24.6-sp500`) and 75% (`0.24.6-nasdaq`).
- **The all-clear:** the chance of a 20% peak within a quarter, from the quarter after a warning
  ends, over the unconditional chance. The record reads 0.28× to 0.38× and the worlds 1.0×. But the
  record's figure rests on one peak, March 2000, and on none over QQQ's window, so one history
  cannot say whether leaving the top decile is a real all-clear.

`channels.macro` carries `warnings`, `falseAlarm` and `allClear`, and the report prints them under
the hazard. The record's rows are in `macro-2026-09-06.tsv`.
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
of a century's sessions above 5% against the record's 0.27). What is missing is TIME AT THE ZERO
BOUND: the rate floors at zero, but the record spent 28% of 1990–2026 below 0.5% and the model,
chasing a 4.2% mean, reaches the floor only in brief episodes, because `easing` caps accommodation
at one easing cycle and inflation suppresses it — so a vote whose rate leg is a fixed low threshold
fires on the record and almost never here, while a rank leg transports. The gap is graded now
(`short rate %`, `rate floor share %`) and `-ratemean` is searched under it. Its upper tail is held to the record's: an inflation regime's target is
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
against 26.9. `jumpVar` is 0: the slow channel carries the long-lag clustering. The un-searched
dials are the 0.24.1 recipe's, so it carries the vol response and the macro panel.

**`0.24.3-nasdaq` is the recipe re-solved under the typical-year and wing rows**: `0.24.2-nasdaq`
re-solved by the calibration search with the bust swing, the belief half-life and the slow
channel's bond leg and permanent share among the thirty-four searched dials, and picked as the
steadiest of the nine members of `test-data/worlds/0.24.3-nasdaq.json` that pass every class on
four seeds at 200 paths (member 53). Its literals are the archive's own, so `-atrelease
0.24.3-nasdaq` reproduces the member byte for byte. Against `0.24.2-nasdaq` on the same four
seeds: fitness loss 1.50-1.89 from 1.58-2.61; the upper wing 2.5 → 7.2 (record 7.6), the lower
14.0 → 10.7 (6.7), the downside excess 0.3 → 0.1 (1.1); paid in the worst crash (−66 → −63
against −83) and crashes per century 30.8 → 31.4 (25.6); equity vol 24.4 against 26.9 and
kurtosis 16.4 (9.6) as before. The bust swing runs at the archive's 0.014, which the search left
there because the bust's shape is no graded row. The un-searched dials are the 0.24.2 recipe's.

**`0.24.4-nasdaq` is the recipe re-solved for the daily return's shape**: the 0.24.3 recipe with
its kurtosis and up-day share inside their record bands, which `0.24.3-nasdaq` misses on 29 and on
all of 32 seeds, and its lag-1 clustering on the record. Four mechanisms carry it, on the 0.24.3
recipe's un-searched dials: frequent, small, credit-coupled news, half its compensator paid by the
day flip (`newsRate` 16.7 × 1.5% at a fixed size, `newsLev`, `newsRevert`, `newsFlip` 0.54, with a
bond leg); a shock skewed long tail left (`noiseSkew`); a credit-triggered vol regime
(`creditRegime` 0.66 at onset rate 17), which carries the kurtosis the spiral's credit gain did;
and the slow bond leg reversed in an inflation regime (`slowBondInfl`), its beta at 0.8 for room
under the bond's vol gate. At 200 paths × 100 years on 32 seeds against `0.24.3-nasdaq` on the
same seeds no row sits further from its record past tolerance (5 percentile points on a banded
row, 0.02 log on any other) and 15 sit nearer: kurtosis 13.8 → 9.5 (9.55), lag-1 clustering 0.32
→ 0.29 (0.29), lag-20 0.17 → 0.19 (0.25), the up-day share 51.9 → 54.8 (54.8), the downside
excess −0.0 → 1.0 (1.07), the wings 11.0 / 11.9 → 6.3 / 8.2 (7.6 / 6.7), return per vol 0.35 →
0.40 (0.38), d20 1.24 → 1.15, the bond's growth-crash rally 4.2 → 4.5 (6.6); equity vol 23.4
against 26.9 and the typical year 20.4 against 20.0, as `0.24.3-nasdaq`. Every class on all 32
seeds, where `0.24.3-nasdaq` passes on 29. By the vol state before the day (the 20-session vol,
the series' own terciles) its up days are 54.9 / 55.4 / 53.6% of sessions, calm / middle /
turbulent, against QQQ's 56.5 / 56.0 / 51.8, each inside the record's resampling band; the
calm-minus-turbulent gradient (1.0 against 4.75, band from 0.98) and the calm state's down/up
energy (−1.7% against +5.5, band from 0.2) sit at or under their bands' lower edges, disclosed.
`-crossasset` reads the d=5.70 bond-depth rung at 0.53
(0.55 on `0.24.3-nasdaq`; band 0.65-1.35), the pre-existing miss. The Nasdaq anchor set's spreads
are frozen at this world. It is member 28 of
`test-data/worlds/0.24.4-nasdaq.json`, the set searched from it, byte for byte.

**The 0.24.4 set is graded against the 0.24.3 set, member for member**: each row's median member
distance from its record (percentile points from the band's middle on a banded row,
|ln(model/record)| on any other), seed by seed on four seeds at 200 paths × 100 years, against the
members of `0.24.3-nasdaq.json` that pass every class on the same four seeds (28 of 173). The 62
members sit nearer on eleven rows (kurtosis, lag-1 clustering, the downside excess, the up-day
share, the leverage correlation, valuation dispersion, the lower wing, bond vol, both bond crash
rows, bond depth against its vol), within tolerance on eleven and further on none; equity vol and
d20 are unresolved, past tolerance on two seeds of four and on one. Coverage weights' effective
sample 36.9.

**`0.24.4-nasdaq-basket`** is the same world with the basket on, anchored on the eight names
under QQQ: `basketSector` 0.8 (corr 0.837-0.841, beta 1.37, vol ratio 1.63-1.64 on four seeds at
200 paths against the anchors' 0.837 / 1.365 / 1.630; pairwise 0.57, idio share 0.38, tail
coincidence 0.46, worst-decile pair corr 0.51 against 0.19 mid; names 2.07x, gaps 3.3/yr; time
below peak 0.70, disclosed), every class passing. It is the Nasdaq basket world to pin in place of
`0.24.1-nasdaq-basket`.

**`0.24.4-sp500-channels`** is the S&P default emitting everything a bundle reads, graded on the
S&P anchors: the satellite, bars with the open, dividends, the basket and the macro panel, at the
dials each channel's section anchors, with two moved. `-levgain` runs at 8: the default's 6 leaves
the panel's `macro cond build-up` on its gate's lower edge (0.83-0.85 against 0.82-1.00), so the
default with the panel on passes every class on 19 of 32 seeds at 200 paths x 100 years, where the
recipe reads 0.86-0.92 and passes on 29 and the default with the panel off on 30. `-overnight` runs
at 0.14, which centres the overnight share on the record (0.32-0.34 against 0.33; 0.20 reads
0.37-0.40 on this world). Against the default on the same seeds no row is further from its record
on every seed; d10 (1.27 → 1.21) and d20 (2.59 → 2.27) are nearer on every one, and bond depth
against its vol (1.25 → 1.29) is further on 28. Range vs close-to-close vol 1.13-1.15, down/up
1.15; basket corr 0.81-0.82, beta 1.56, vol ratio 1.90-1.93, names 2.4x, gaps 2.4-2.6/yr. It is
the S&P world to pin in place of `0.24.1-macro` and `0.24.1-basket`.

**`0.24.5-nasdaq`** is the recipe re-solved around the bubble coupling. The outgoing recipe missed
it on every seed: 98% of its 36-year histories held a 40%+ decline from a high and 47% held three
or more, where the NDX from 1990 holds one, and they started at ordinary highs, so a mania's bust
was one deep decline among several; the spiral, the recession and the credit regime together
carried them. The recipe is re-solved by search from a world with those three cut back, booms of
1.5 log and the deleveraging (`-delevrate`) as the source of ordinary declines, with the coupling,
valuation dispersion and both wings gap rows; then the rate level set by hand (`-ratemean` 0.045,
`-inflsize` 0.10, `-slowbondinfl` 0.85) for the short rate and the bond's inflation crash. It
carries 1.8 booms a century of 1.8 log over 1.4 years fading at a 2.1-year half-life (`-boomfade`),
deleveragings at 1.04 a year per year of calm beyond 3.3 of 0.27 log over 0.11 years, the spiral at
3.7, the recession at 0.07 a year and the credit regime at 0.6. On 32 fresh seeds at 200 paths ×
100 years every class passes on 29 and no row misses on any: the bubble coupling at the record's
91st percentile (the outgoing recipe's 100th, a miss on every seed), the up-day share 55.1% (record
54.8%), the wings 6.9% / 7.1% (7.6% / 6.7%), the short rate 2.3% and the floor share 26% (2.1% / 37%), the 60-, 120- and 250-day variance ratios 0.92 / 0.92 / 1.08 (0.83 / 0.92 / 1.11), equity vol
24.6% (26.9%), kurtosis 10.3 (9.6), crashes 27 a century (25.6), the bond's growth rally 5.9 (7.0)
and inflation crash −25.0 (−27.9), the timing row −0.7 (record +1.4, inside; the outgoing recipe
+0.6). Toward their bands' edges and inside: leverage corr −0.06 (−0.11; band −0.17 to −0.03),
valuation dispersion 0.41 (0.30; ratio 1.37 against an edge of 1.5), d20 1.40 (edge 1.5). Seed 29's bond vol reads a hair over its 1.10x-duration band and seeds 25 and 34 the 250-day variance ratio over its 1.30. Against the outgoing recipe on the same seeds
the coupling closed and clustering lag 1, the up-day share, the lower wing and the short rate moved
nearer their records; the three rows above moved further from theirs inside their bands; the
largest 3-year run-up (reported) reads 2.26 against the record's 1.75 (was 1.77). The Nasdaq anchor set's spreads are re-frozen at this world (`-noise`, 200 paths). It is member 0 of `test-data/worlds/0.24.5-nasdaq.json`,
the set searched from it, byte for byte.

**`0.24.5-nasdaq-basket`** is `0.24.5-nasdaq` with the basket at `0.24.4-nasdaq-basket`'s dials and
the satellite's relative cycle (`-satcyclesd` 0.0003, `-satdrifthalf` 0.2, `-satlevelhalf` 0.5);
the Nasdaq basket world to pin in place of `0.24.4-nasdaq-basket`.

**`0.24.5-sp500`** is the S&P world re-solved around the 1929 disaster: the disaster at 1929-32's
trend-free readings off Shiller's earnings and the CRSP daily index (`-disastersize 1.5
-disasterlen 3 -disasterrecover 0.5 -disasterreclen 5`), the valuation leg's overshoot 0.31 and
the anticipated recovery 1, and the spread's drawdown term 3.0, all read off the record and held
fixed; the other dials re-solved by search with the multi-year variance ratios in the loss and
valuation dispersion and both wings required inside their bands, and the satellite's relative
cycle at the Nasdaq basket's dials. The drift regime's spread is 0.005 (`-driftsd`) and growth is
not capitalized (`capYears` 0.05); the wings come from 1.4 booms a century of 1.0 log over 12
years, fading at a 1.7-year half-life (`-boomfade`). On 32 fresh seeds at 200 × 100 every class
passes on all 32 and no row misses on any:
the upper wing 6.8% (record 7.6%), the lower 7.6% (6.7%), valuation dispersion 0.32 (0.30), equity
vol 17.2% (15.7%), the up-day share 54.4% (55.0%), the 60-, 120- and 250-day variance ratios 1.03 /
1.00 / 1.04 (1.01 / 1.03 / 1.03), the short rate 4.1% and the floor share 8.7% (4.6% / 14.6%), the
bond's growth rally 8.8 (7.0) and inflation crash −31.2 (−27.9), tail hedge corr −0.30 (−0.27).
Kurtosis 29.7 (21.8) and the downside excess 6.3 (3.1) sit at their bands' 86th and 87th percentile.
It is member 0 of `test-data/worlds/0.24.5-sp500.json`
and the S&P world to pin in place of `0.24.4-sp500-channels`.

**`0.24.6-nasdaq`, `0.24.6-nasdaq-basket` and `0.24.6-sp500`** are the 0.24.5 recipes at the drift
that holds return per vol at the record, every other dial unchanged: the Nasdaq's drift 0.1512
(0.1354), return per vol 0.38 against QQQ's 0.38 (0.30); the S&P's 0.1475 (0.1364), return per vol
0.69 against CRSP 1954-2026's 0.69 (0.61); seeds 1-4 at 200 × 100. 0.24.5's search let the loss
slide the drift down while return per vol stayed inside its band, so every world's return ran under
the record's. Against its 0.24.5 recipe on 24 fresh seeds, paired, `0.24.6-sp500` passes every class
and misses nothing on all 24, as the old one does, and sits 0.51 closer to the record in summed
distance a seed (t −5.2); `0.24.6-nasdaq` misses 0.88 rows a seed against 0.71 (worse on 5 seeds,
better on 2; sign test p 0.23) and sits 0.09 closer. Each is member 0 of its `0.24.6` set, and the
worlds to pin in place of the 0.24.5 recipes.

## The deleveraging — `-delevrate`

The worlds' 20% declines arrived as a Poisson process: the 90th-percentile gap between the peaks
of successive ones read 17 years on the S&P world against the record's 8.6 from 1954 (11.8 over
the century), and the longest calm stretch twice the century's. No existing dial moves it. The
deleveraging is a hazard rising with the expansion's age — `-delevrate` a year for each year the
price has gone without a 20% drawdown beyond `-delevfrom` — times the credit stock's growth over
its trailing year in sds, floored at 0, so no episode starts while credit contracts. Timed by age
alone the onsets are ones no macro member precedes and the leverage member's hazard ratio fell
1.83 → 1.50; through the credit stock it reads 1.9. The selling is a flow through the market's
own step, `-delevsize` log of return spread evenly over `-delevlen` years: the stress index, the
spiral and the bond's refuge bid read it and the value pull buys it back, and the fundamental
does not move. A directly repriced decline never registers as stress, so the bond does not rally
into it, and a slide of ten sessions or more fails the 20-day variance ratio. None starts while
a disaster, a recession or another runs. Own stream; 0 is off, bit for bit.

The S&P world does not carry it: the search around it kept the volatility gates only by
stretching the episodes, and the gap came back to 13-15 years. `0.24.5-nasdaq` carries it as the
source of its ordinary declines, which is what lets its deep declines be its manias' busts.

## The bust swing — `-bustamp`

A mania's unwind is not a crash. The NDX fell 83% from March 2000 to October 2002 in five legs
with four rallies of 20% or more between them, at 53% annualised vol for two and a half years,
while SPY read 24%; 1929-32 was the same shape at 34%. The model's deep episodes are a spiral
crash and a calm underwater spell: 33% vol, two rallies, whatever the peak they start from.

`-bustamp A` adds the unwind. The valuation level is the price's log gap to the fundamental
above its own 20-year mean (the model's reading of "the multiple over its long mean", the same
statistic `mania-2026-09-15.tsv` reads on Shiller's CAPE). When a drawdown of 0.2 log opens under
a peak that stood +0.5 or more, a state arms, at full strength +0.65 and above. While it is armed
a months-long unit-variance swing (a 50-session memory, its own stream) is repriced the same
session in the price and in perceived fair at amplitude A times the state, so no gap opens for
the value channel to close and the price does not race to its trough: the bust is a stationary
swing about a slowly falling centre. The move is a same-session repricing the derived channels
(bars, the satellite, the basket) see through the same input as the news jump. The recovery drag is relieved and the spiral's amplifier
reads declines in ordinary units by 1 + 2 × state, so the legs keep their ordinary cascade and the
rallies exist. The state decays with a 3-year half-life while the unwind keeps making new lows
(the NDX made one every six months through 2000-02) and a 6-month one once a year has passed
without one (2003-06 was calm 70% under the 2000 peak). Once the price regains the running peak
while the state is armed the unwind is over and the state fades with a one-month half-life on top:
no mania's unwind on the record re-attained its high before it was over, and without the rule
the swing ran on for up to a year after a full recovery, its downward moves minting 20% peaks
under a still-depressed conditions index (two such peaks a century at amplitude 0.20, against 0.9
with the swing off; a relief that fades near the peak and beliefs blind to the swing left them
untouched; the rule cuts them to 1.4 and the build-up band holds on every seed to 0.25). The
state is cut to exactly 0 once it has decayed below 1e-4, so the block is inert between manias.

The swing has a ceiling: it never carries the price nearer than 0.10 log to the running peak,
measured from the price without it, and once the price is already nearer it can only
subtract. A mania's unwind never re-attains its high -- the NDX's 2000 high stood until 2015,
the Dow's 1929 high until 1954; the nearest their rallies came was 0.14 (September 2000) and
0.20 (October 1929) under it, and no deep episode on the record came nearer than 0.08. An
unbounded swing re-attained the high a month after the bust opened, three times a century at
amplitude 0.14, minting 20% peaks whose quarter sat inside the bust and read a conditions rank
of 0.52 where every other peak reads 0.85: that is what failed the macro build-up band from
amplitude 0.10. Under the ceiling every amplitude to 0.20 passes every class on four seeds.
`bustCeilDays` on a path counts the sessions a live unwind was held; 1.7% of the recipe's.

What it reads, at A 0.20 on the 0.24.3 recipe over 120 centuries: the busts of
2000 size that start from a mania peak run 3.0 years at 46% vol (p10 30, p90 88) with four rallies
of 20% and two of 30% (the world at 0.014: 3.7 years, 32.5%, two and one; at 0.14: 4.3 years, 36%;
NDX 2000-02: 2.5 years, 53%, five and three), the other deep episodes are untouched (30%), the
typical year moves 20.3 → 20.5 (band 15.0-21.6), and the four-seed loss stays inside the world's
own; 0.25 passes every class but its busts run 1.4 years at 57%. Sixteen forms were measured before
this one: every form that multiplies the diffusive noise inside a bust — by the multiple, by a
bust state, with the spiral compensated or blind — shortened and deepened the bust instead of
lengthening it, because diffusive noise under a one-way restoring force races the price to a
deeper trough; relieving the drag alone gave gentle rallies and no vol; the crowd's flow is
too small; a swing in perceived fair alone is smoothed away by the pull. The stationary swing
repriced the same session is the one form whose extra variance does not diffuse.

The dial ships at 0 in every world before `0.24.3-nasdaq`, which carries its archive's 0.014;
`0.24.4-nasdaq` runs it at its own archive's 0.24 (its mania-led busts 1.7 years at 43% with three
rallies of 20%). It is bounded by how often the model makes the mania it arms on: the record spends
7.6% of its months more than +0.5 over its 20-year mean, the 0.24.4 Nasdaq recipe 5.5%, and that gap
is the cycle's upper wing (PLAN item 25), not this dial's.

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
- **Valuation dispersion sits below the record proxy's windows since the start became
  stationary** (0.17 against sd log CAPE 0.24-0.41; 0.33 before 0.24.4). The 0.33 was the
  beliefs' near-random walk from the fair-value start every path was given — a transient of a
  century on this world, read as a cycle — and the lower wing with it (11.8% of months past −0.5
  against the record's 6.7, now 3.3). The cycle that replaces the walk (`-cyclesd`) is held at
  0.1 by the variance-ratio profile, which every larger amplitude fails on some seed, so the S&P
  reads no upper wing (0.0 against 7.6; the record's manias peaked at 2.0-2.7x the mean
  valuation, Sep-1929 CAPE 32.6, Dec-1999 44.2) and a collapse from a 2x-fair peak remains out
  of reach; the led-mix is not a calibrated quantity. The Nasdaq recipe reaches its wings through
  the growth-extrapolation term. Pushing the cap term harder is fenced by the depth rungs and the
  variance-ratio ceiling (`-capyears 3` reads vr 1.13 and d10 1.39).
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

`-calibrate N` random-searches thirty-four parameters against the fitness loss and reports the best few
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

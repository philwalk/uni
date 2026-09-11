---
name: uni-numerics
description: What the uni library already provides for random numbers, linear algebra, statistics, dataframe operations, exact decimals and plotting, in both Scala and Rust, and which of it is bit-identical across the two. Read this BEFORE writing any numerical routine, any random draw, any transcendental, or any code whose output the twins must agree on.
---

# uni's numerical surface, and what the twins agree on

Two rules first, because they are the ones that get broken.

**Do not hand-roll what is here.** Random draws, a matrix decomposition, a percentile,
a rolling window, a deterministic exponential: all of it already exists in both
languages, already parity-gated. Reaching for `scala.util.Random`, `rand`, `f64::ln` or
a fresh Gaussian is how a twin divergence gets introduced.

**Do not assume parity; look it up.** Most of this library is bit-identical between
Scala and Rust by construction. Some of it is pinned to a grid instead, and the
difference decides whether a rank, a sign or a gate verdict is reproducible.

`rust/PARITY.md` is the authority and carries the full module inventory. This is the
lookup table.

## Random numbers — `NumPyRNG` / `NumPyRng`

`uni.data.NumPyRNG` and `uni::NumPyRng`. PCG64 with NumPy's own stream, so a seed
reproduces NumPy's sequence, and **bit-identical across the twins**.

| need | Scala | Rust |
| :--- | :--- | :--- |
| uniform in [0,1) | `nextDouble()` | `next_f64()` |
| uniform in [lo,hi) | `uniform(lo, hi)` | `uniform(lo, hi)` |
| standard normal | `randn()` | `randn()` |
| integer in [0,bound) | `nextBoundedInt(b)` | `next_bounded_u32(b)` |
| raw bits | `nextLong()` / `nextInt()` | `next_u64()` / `next_i32()` |

**Never use a language-native generator in code that has a twin.** `scala.util.Random`
is `java.util.Random`, whose `nextGaussian` scales by `StrictMath.log`; Rust's `rand` is
a different algorithm entirely. `-calibrate` and the calibration search were both moved
onto `NumPyRNG` for this reason.

`randn` is a ziggurat. The rectangular path, which is the overwhelming majority of
draws, is integer arithmetic and exact. The wedge's accept test calls `exp` and the
tail calls `log1p`, so those two branches are pinned rather than exact — a returned
value can differ in the last place in the tail, and an accept decision could in
principle flip where two doubles sit within an ulp of each other. Seed a generator with
a non-negative value: Scala types it `Long` and Rust `u64`.

## Linear algebra and matrices — `Mat` / `MatD`

`uni.data.Mat` and `uni::udata`. A strided view model on both sides, so `transpose`,
`slice` and `broadcastTo` are views, and **which summation algorithm a matrix gets is a
function of its layout in both languages alike**.

- **Core**: broadcasting arithmetic, the `apply*` gather family, `reshape` `ravel`
  `flatten` `item`.
- **Reductions**: `sum` `mean` `min` `max` `argmin` `argmax` `std` `variance` `norm`,
  and the axis family `sumAxis` `meanAxis` `stdAxis` `cumsumAxis` `cummax` `cummin`
  plus `rowSums`/`colSums`/`rowMeans`/`colMeans`.
- **Elementwise**: `abs` `power` `exp` `log` `sqrt` `clip` `cumsum`, and in
  `MatMathOps` the trig, `floor`/`ceil`/`trunc`, `log10`/`log2`, `sigmoid`/`relu`/
  `gelu`, `softmax`/`logSoftmax`.
- **`linalg`**: `diagonal` `trace` `normOrd` `determinant` `inverse` `solve`
  `qrDecomposition` `outer` `cross` `kron` `tril` `triu` `fillna` `cov` `corrcoef` —
  bit-identical. `svd` `lstsq`/`leastSquares` `matrixRank` `pinv` `cholesky` and
  `eig`/`eigenvalues` — pinned on a 2^-20 grid, not exact.
- **`matmul` / `*@`**: routes to a native BLAS above a size crossover.

### BLAS, and the one trap

`-Duni.mat.blas=…` or `UNI_MAT_BLAS`, read once per JVM. `os-best` (the default),
`bundled`, `system`, or `pure`. **`pure` is the only setting that is bit-identical to
the Rust port and machine-independent**; use it whenever reproducibility outranks
speed. One BLAS per process on Linux: the system and bundled OpenBLAS interpose on each
other and a process holding both segfaults.

## Statistics and dataframes

- **`udata::pandas` / `MatPandasOps`**: `idxmin` `idxmax` `sort` `argsort` `nlargest`
  `nsmallest` `between` `unique` `nunique` `valueCounts` `diff` `shift` `pct_change`
  `percentile` `median` `describe` `rolling(window).{mean sum min max std}`
  `histogram`. Every ordering is `Double.compare` and every sort stable, so the bits
  agree.
- **`udata::signal` / `MatSignalOps`**: `polyval` `convolve` `correlate` bit-identical;
  `polyfit` through `lstsq`, so on the 2^-20 grid.
- **`MatResult`**: `groupBy` and `merge`, first-appearance key order.
- **`uni.stats.Tprf3` / `t3prf`**: the three-pass regression filter and its closed
  forms — `tprfClosedForm`, `plsClosedForm`, `pls1Fit`, `forecast3prf`, plus the
  in-sample and out-of-sample procedures. Complete on both sides.

**`Ordering[Double]` is `Double.compare`, not IEEE**: NaN ranks highest, `-0.0 < 0.0`,
and `minimum(NaN, 1) == 1` as in Scala rather than as in NumPy. Rust's `total_cmp` is
the near-miss that looks right and is not.

## Exact decimals — `Big`

`uni.data.Big` / `udata`. Full arithmetic including `round(precision, mode)` and
HALF_EVEN contexts, `loadMatBig`/`loadSmartBig`, `numStr`/`NumFormat` at Java `%f`
fidelity, `str2num`, `isNumeric`. `Mat[Big]` / `MatB` carries the matrix surface over
it. `BigNaN` is an equality-recognised sentinel: every operation must guard it or it
vanishes silently.

## Plotting — `uni.plot` / `uplot`

`plot scatter hist bar heatmap boxPlot pairs`. **Byte-identical SVG**: both sides draw
the chart themselves from constants and `floor(x·100+0.5)` coordinates, with no
charting library, no font metrics, no `pow`, and their own bit-identical `log10` that
makes no libm call at all. That `log10` is the worked example of how to get a
transcendental to agree across the twins.

## Transcendentals — the actual parity boundary

This is where twin divergence comes from, so it has its own rules.

The JVM's `Math.exp` is correctly rounded on 99.74% of a 200,000-value corpus and
C/Rust/NumPy's on 99.43%; `Math.log` differs from libm's on **0.235%**, never by more
than 1 ulp. Both sides therefore keep their native fast path, and `exp`, `log` and
`randn`'s tail are contracted as **"agrees to ~40 bits"** rather than exactly.

- A 1-ulp difference is invisible at any normal print precision. It surfaces only where
  a printed column's true value is **identically zero** and the rounding noise is all
  that is left. The fix is a rendering whose digits are all zero carries no sign, not a
  tolerance.
- **Never branch on a residual.** A comparison or a rank selection on a quantity whose
  true value is near zero is a coin flip between the languages.
- When you need a transcendental that must agree **to the bit**, write it: Cody-Waite
  range reduction with fdlibm's split ln2 as bit patterns, a fixed Horner polynomial,
  and powers of two built from raw exponent bits. `MarketSim.expDet` / `exp_det` and
  `tanhP` / `tanh_p` are the pattern, and `PlotSvg.log10` / `uplot::svg::log10` is the
  same thing for a logarithm.
- `floor(x + 0.5)`, written out. Java's `round` and Rust's differ on negative halves,
  and `rint` is banker's rounding.

## Before you write numerical code

1. Is it in the table above? Use it.
2. Does it draw a random number? `NumPyRNG`, never the language's own.
3. Does it call a transcendental in something the twins must match? Check whether the
   result can reach a comparison, a rank or an identically-zero column. If it can, use
   a deterministic implementation.
4. Is it a table something consumes **positionally** — an RNG stream, a file's columns,
   an index? Its order needs a contract restated as a literal and checked by each
   twin's own test. A set-equality check on its names is not one.
5. Does it change output? Run both gates: `sbt --client test`, and `make lint && make
   test` in `rust/`. Never both at once — they kill each other's servers.

## Where this file lives

One copy, in the uni repo at `skills/uni-numerics/SKILL.md`, so it is reviewed and
versioned with the code it describes — `.claude/` is gitignored here, which is why it
does not live there. `~/.claude/skills/uni-numerics` is an NTFS symlink to this
directory, and that is what makes it visible to every session on this machine.

Do not make a second copy; edit this one. When the library gains a capability,
`rust/PARITY.md` gets the detail and this file gets the one-line pointer.

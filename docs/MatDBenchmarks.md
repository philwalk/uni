# MatD Benchmarks

`uni.MatD` (Scala), the Rust crate and NumPy on the same operations, sizes and inputs, on
Windows, Linux and macOS — one table per operating system, one column set, apples to
apples — plus the command that regenerates every table. The API itself is in
[MatDCheatSheet.md](MatDCheatSheet.md); the Breeze numbers are in
[BreezeComparison.md](BreezeComparison.md).

The column set:

| NumPy | Scala·pure | Rust·pure | Scala·BLAS | Rust·BLAS |

NumPy multiplies through OpenBLAS — it has no other mode. Each other column is a named
configuration: `·pure` is the pinned matmul on each side (`matmulPure`; bit-identical
between the two ports and across machines) with `matrixmultiply` in `t3prf`, and `·BLAS`
is each side's BLAS (`-Duni.mat.blas=os-best`, the Scala default; `--features blas`, a
build-time opt-in in Rust). The BLAS columns carry a number only on the rows BLAS can
change — `matmul` and the 3PRF rows — with `·` elsewhere: every other row is identical
code in both modes. MatD rows are min-of-60 single calls on a 1000×1000 (matmul 512³);
3PRF rows are medians of 25 (IS Full 200) after warm-up. Each run opens with an executive
summary: the languages like for like (BLAS on all three; the pinned loop in both ports),
then the as-shipped defaults, labelled as a comparison of configurations.

## Regenerating every table

```bash
./runBenchAll.sh          # builds both Rust flavours side by side, then runs uni.apps.BenchAll
```

`runBenchAll.sh` builds `bench_mat`/`bench_tprf3` twice — default, then `--features blas`
in its own target dir — keeps both as `*_pure` / `*_blas`, and runs
[`BenchAll`](../src/main/scala/apps/BenchAll.scala), which measures the Scala matmul cells
and 3PRF rows in child JVMs pinned to `-Duni.mat.blas=pure` and to the BLAS mode
(`-blas <mode>`, default `os-best`; the mode is read once per JVM), runs [`py/bench.py`](../py/bench.py)
and [`py/bench_tprf3.py`](../py/bench_tprf3.py), and runs the four Rust binaries. A
missing binary drops its column rather than the run. The BLAS build needs an OpenBLAS the
`openblas-src` crate can find (`libopenblas-dev` / `brew install openblas` /
`pacman -S mingw-w64-ucrt-x86_64-openblas` + `OPENBLAS_LIB_DIR=/ucrt64/lib`). All three
halves keep the same row labels, sizes and input generator — the label is the join key.

**Keep the provenance lines when pasting a table in.** They say what each column is —
JVM, numpy and its BLAS, which Rust build, which BLAS on which platform — and without them
a reader cannot tell a property of the implementation from a property of the machine.

## Across the three operating systems

**Languages, like for like — BLAS on all three.** Geometric-mean speedup over all rows;
NumPy is always OpenBLAS, and here Scala and Rust use theirs too (on the 7 rows BLAS
affects; every other row is identical code in both modes). Above 1× means the first-named
is faster.

| OS | Scala vs NumPy | Rust vs NumPy | Rust vs Scala |
|---|---:|---:|---:|
| Windows 11 | 3.15× | 3.79× | 1.30× |
| Linux (WSL2, same machine) | 1.80× | 1.66× | 0.99× |
| macOS | 1.38× | 2.09× | 1.65× |

**The pinned loop, both ports.** `matmulPure` on each side — bit-identical results, no BLAS:

| OS | Rust·pure vs Scala·pure (all rows) | … (matmul + 3PRF only) |
|---|---:|---:|
| Windows 11 | 1.15× | 1.84× |
| Linux (WSL2, same machine) | 1.02× | 3.00× |
| macOS | 1.80× | 4.35× |

**Configurations, as shipped.** Scala's default is BLAS (`os-best`); Rust's default build
is the pinned loop, because its BLAS is a build-time feature a library cannot switch on for
its users — so the Rust column here is the price of that packaging constraint, not a
statement about the language:

| OS | Scala (default: BLAS) vs NumPy | Rust (default: pure) vs NumPy |
|---|---:|---:|
| Windows 11 | 3.15× | 3.36× |
| Linux (WSL2, same machine) | 1.80× | 1.67× |
| macOS | 1.38× | 2.10× |

**`matmul` 512³, ms/call** — the row the BLAS choice hangs on:

| OS | NumPy | Scala·pure | Rust·pure | Scala·BLAS | Rust·BLAS |
|---|---:|---:|---:|---:|---:|
| Windows 11 | 0.78 | 1.42 | 1.68 | 0.67 | 0.81 |
| Linux (WSL2, same machine) | 0.48 | 1.59 | 1.43 | 0.66 | 0.49 |
| macOS | 2.13 | 6.13 | 2.47 | 1.06 | 2.13 |

Reading them together:

- **Like for like, both ports are faster than NumPy on every OS** — Scala 1.4–3.2×, Rust
  1.7–3.8× — and Rust and Scala are within 1.0–1.65× of each other. The 3PRF rows drive
  much of that.
- **The pinned loop trails OpenBLAS by 2–3× on this shape everywhere** (Scala·pure 1.8× /
  3.3× / 2.9× behind NumPy; Rust·pure 2.1× / 3.0× / 1.2× — on aarch64 NEON is baseline, so
  LLVM vectorises the microkernel fully; on x86-64 the crate targets SSE2). Scala·BLAS
  closes it on every OS — 0.67 / 0.66 / 1.06 ms against NumPy's 0.78 / 0.48 / 2.13 —
  which is why BLAS is the Scala default. On Linux that number is the OS OpenBLAS via
  netlib (`os-best` → `system` when `libopenblas0` is installed); bytedeco's bundled build
  read 2.98 ms on the same machine, *slower than the pinned loop* on 24 threads.
- **BLAS buys Rust nothing on the 3PRF rows** (Rust·BLAS vs Rust·pure 0.92–0.99×): those
  gemms are skinny and run one BLAS thread inside rayon-parallel windows, where the pinned
  microkernel already wins. Scala·BLAS trails Scala·pure on the small 3PRF rows for the
  same reason and wins on the large ones (1.2–1.8× over the 7 rows).
- **NumPy's OpenBLAS is 1.6× faster on Linux than on Windows on the same silicon** (0.48 vs
  0.78 ms), and Scala·pure / Rust·pure are within 10% across the two — the JVM and the
  Rust binary carry their speed across the OS boundary; the BLAS build does not.
- **Reductions beat NumPy everywhere** (2–3× on `sum`/`mean`, `argmax` 2–6×), Rust and Scala
  alike; the elementwise maps are where the two ports diverge — Rust 3–6× ahead of Scala
  on Linux and macOS, level or behind on Windows (allocator and cache-residency behaviour
  on a 1000×1000 output).
- `vectorize` is `np.vectorize`, a Python loop, against a compiled parallel map of a user
  closure; it has no Rust row and is the one row that would drag an arithmetic mean, which
  is why every summary is a geometric mean with the median beside it.

## Windows 11 (zx, 24 threads)

#### Executive summary
Speedup = (second-named ms) ÷ (first-named ms), geometric mean over the rows named, with the
median beside it; above 1× means the first-named is faster.

**Languages, BLAS on all three — the like-for-like comparison.**
NumPy is always OpenBLAS; here Scala and Rust use theirs too on the 7 rows BLAS affects (every
other row is the same code in both modes). Rust's BLAS is a build-time feature, so its DEFAULT
build is the pure loop — a packaging constraint, not a language result; this block is Rust's
fair number.

| comparison | geomean | median | rows |
|---|---:|---:|---:|
| Scala vs NumPy | 3.15× | 2.71× | 49 |
| Rust vs NumPy | 3.79× | 2.75× | 48 |
| Rust vs Scala | 1.30× | 1.21× | 48 |

**The pinned loop, both ports — bit-identical results, no BLAS anywhere.**

| comparison | geomean | median | rows |
|---|---:|---:|---:|
| Rust·pure vs Scala·pure | 1.15× | 1.13× | 48 |
| Rust·pure vs Scala·pure (matmul + 3PRF only) | 1.84× | 1.33× | 7 |

**Configurations, as shipped — what a user gets without flags.**
Scala's default is BLAS (`os-best`); Rust's default build is the pure loop (`--features blas`
opts in). Against NumPy's BLAS this is the price of each default, not a statement about the
languages.

| comparison | geomean | median | rows |
|---|---:|---:|---:|
| Scala (default: BLAS) vs NumPy | 3.15× | 2.71× | 49 |
| Rust (default: pure) vs NumPy | 3.36× | 2.75× | 48 |
| Rust (default: pure) vs Scala (default: BLAS) | 1.15× | 1.16× | 48 |

**What BLAS buys each port, over the 7 rows it affects.**

| comparison | geomean | median | rows |
|---|---:|---:|---:|
| Scala·BLAS vs Scala·pure | 0.99× | 1.17× | 7 |
| Rust·BLAS vs Rust·pure | 2.28× | 2.06× | 7 |

#### Every row, every configuration — ms/call (lower is better)
The three ratio columns are like for like: BLAS on all three where BLAS applies.

| Operation | NumPy | Scala·pure | Rust·pure | Scala·BLAS | Rust·BLAS | Scala vs NumPy | Rust vs NumPy | Rust vs Scala |
|---|---:|---:|---:|---:|---:|---|---|---|
| `randn` | 7.2694 ms | 6.7274 ms | 4.7566 ms | · | · | **1.1× faster** | **1.5× faster** | **1.4× faster** |
| `matmul` | 0.7797 ms | 1.4182 ms | 1.6769 ms | 0.6664 ms | 0.8122 ms | **1.2× faster** | **1.0× slower** | **1.2× slower** |
| `sigmoid` | 8.7646 ms | 1.0545 ms | 1.1117 ms | · | · | **8.3× faster** | **7.9× faster** | **1.1× slower** |
| `relu` | 1.2396 ms | 0.6166 ms | 1.1058 ms | · | · | **2.0× faster** | **1.1× faster** | **1.8× slower** |
| `vectorize` | 65.1793 ms | 0.5438 ms | — | · | · | **119.9× faster** | — | — |
| `add` | 1.7032 ms | 0.6213 ms | 1.1034 ms | · | · | **2.7× faster** | **1.5× faster** | **1.8× slower** |
| `mul` | 1.7105 ms | 0.6329 ms | 1.0909 ms | · | · | **2.7× faster** | **1.6× faster** | **1.7× slower** |
| `abs` | 2.1863 ms | 0.8902 ms | 0.9851 ms | · | · | **2.5× faster** | **2.2× faster** | **1.1× slower** |
| `exp` | 2.6644 ms | 0.7008 ms | 1.0622 ms | · | · | **3.8× faster** | **2.5× faster** | **1.5× slower** |
| `log` | 3.0246 ms | 0.7077 ms | 1.0769 ms | · | · | **4.3× faster** | **2.8× faster** | **1.5× slower** |
| `sqrt` | 2.2079 ms | 0.5766 ms | 1.0901 ms | · | · | **3.8× faster** | **2.0× faster** | **1.9× slower** |
| `sum` | 0.1379 ms | 0.0394 ms | 0.0254 ms | · | · | **3.5× faster** | **5.4× faster** | **1.6× faster** |
| `mean` | 0.1401 ms | 0.0318 ms | 0.0214 ms | · | · | **4.4× faster** | **6.5× faster** | **1.5× faster** |
| `std` | 2.3895 ms | 0.4093 ms | 0.4334 ms | · | · | **5.8× faster** | **5.5× faster** | **1.1× slower** |
| `min` | 0.1030 ms | 0.0525 ms | 0.0515 ms | · | · | **2.0× faster** | **2.0× faster** | **1.0× faster** |
| `max` | 0.1036 ms | 0.0690 ms | 0.0431 ms | · | · | **1.5× faster** | **2.4× faster** | **1.6× faster** |
| `argmax` | 0.1336 ms | 0.0732 ms | 0.0373 ms | · | · | **1.8× faster** | **3.6× faster** | **2.0× faster** |
| `sum0` | 0.1062 ms | 0.0495 ms | 0.0440 ms | · | · | **2.1× faster** | **2.4× faster** | **1.1× faster** |
| `sum1` | 0.1366 ms | 0.0656 ms | 0.0693 ms | · | · | **2.1× faster** | **2.0× faster** | **1.1× slower** |
| `mean0` | 0.1093 ms | 0.0576 ms | 0.0422 ms | · | · | **1.9× faster** | **2.6× faster** | **1.4× faster** |
| `min0` | 0.1349 ms | 0.0837 ms | 0.0660 ms | · | · | **1.6× faster** | **2.0× faster** | **1.3× faster** |
| `max1` | 0.1283 ms | 0.0704 ms | 0.0485 ms | · | · | **1.8× faster** | **2.6× faster** | **1.5× faster** |
| `std0` | 2.2148 ms | 0.1263 ms | 0.1064 ms | · | · | **17.5× faster** | **20.8× faster** | **1.2× faster** |
| `cumsum` | 3.6849 ms | 0.7812 ms | 1.4964 ms | · | · | **4.7× faster** | **2.5× faster** | **1.9× slower** |
| `cumsum1` | 3.5789 ms | 0.7799 ms | 1.3604 ms | · | · | **4.6× faster** | **2.6× faster** | **1.7× slower** |
| `cummax0` | 4.9481 ms | 0.9381 ms | 1.5113 ms | · | · | **5.3× faster** | **3.3× faster** | **1.6× slower** |
| `cummin1` | 3.9267 ms | 0.9633 ms | 1.3928 ms | · | · | **4.1× faster** | **2.8× faster** | **1.4× slower** |
| `transpose` | 0.0000 ms | 0.0001 ms | 0.0000 ms | · | · | — | — | — |
| `sum@contig` | 0.1297 ms | 0.0316 ms | 0.0258 ms | · | · | **4.1× faster** | **5.0× faster** | **1.2× faster** |
| `max@contig` | 0.1094 ms | 0.0674 ms | 0.0406 ms | · | · | **1.6× faster** | **2.7× faster** | **1.7× faster** |
| `std@contig` | 2.3956 ms | 0.4132 ms | 0.4326 ms | · | · | **5.8× faster** | **5.5× faster** | **1.0× slower** |
| `sum0@contig` | 0.1002 ms | 0.0450 ms | 0.0400 ms | · | · | **2.2× faster** | **2.5× faster** | **1.1× faster** |
| `sum@transposed` | 0.1277 ms | 0.0665 ms | 0.0590 ms | · | · | **1.9× faster** | **2.2× faster** | **1.1× faster** |
| `max@transposed` | 0.1034 ms | 0.0907 ms | 0.0739 ms | · | · | **1.1× faster** | **1.4× faster** | **1.2× faster** |
| `std@transposed` | 2.3958 ms | 0.5080 ms | 0.5311 ms | · | · | **4.7× faster** | **4.5× faster** | **1.0× slower** |
| `sum0@transposed` | 0.1375 ms | 0.1207 ms | 0.0312 ms | · | · | **1.1× faster** | **4.4× faster** | **3.9× faster** |
| `sum@rowslice` | 0.1251 ms | 0.0376 ms | 0.0238 ms | · | · | **3.3× faster** | **5.3× faster** | **1.6× faster** |
| `max@rowslice` | 0.1034 ms | 0.0695 ms | 0.0470 ms | · | · | **1.5× faster** | **2.2× faster** | **1.5× faster** |
| `std@rowslice` | 1.9205 ms | 0.4127 ms | 0.4340 ms | · | · | **4.7× faster** | **4.4× faster** | **1.1× slower** |
| `sum0@rowslice` | 0.0996 ms | 0.0483 ms | 0.0389 ms | · | · | **2.1× faster** | **2.6× faster** | **1.2× faster** |
| `sum@bcast` | 0.1863 ms | 0.0286 ms | 0.0218 ms | · | · | **6.5× faster** | **8.5× faster** | **1.3× faster** |
| `max@bcast` | 0.1738 ms | 0.0642 ms | 0.0400 ms | · | · | **2.7× faster** | **4.3× faster** | **1.6× faster** |
| `std@bcast` | 2.3928 ms | 0.4016 ms | 0.4293 ms | · | · | **6.0× faster** | **5.6× faster** | **1.1× slower** |
| `sum0@bcast` | 0.0512 ms | 0.0361 ms | 0.0293 ms | · | · | **1.4× faster** | **1.7× faster** | **1.2× faster** |
| `3PRF IS Full (Small)` | 0.1200 ms | 0.1000 ms | 0.0140 ms | 0.0700 ms | 0.0140 ms | **1.7× faster** | **8.6× faster** | **5.0× faster** |
| `3PRF OOS Rec (Small)` | 4.5700 ms | 0.8700 ms | 0.3900 ms | 1.1300 ms | 0.2500 ms | **4.0× faster** | **18.3× faster** | **4.5× faster** |
| `3PRF OOS CV (Small)` | 8.2300 ms | 0.9800 ms | 0.9500 ms | 3.4000 ms | 0.2300 ms | **2.4× faster** | **35.8× faster** | **14.8× faster** |
| `3PRF IS Full (Large)` | 0.3400 ms | 0.3100 ms | 0.0530 ms | 0.2500 ms | 0.0480 ms | **1.4× faster** | **7.1× faster** | **5.2× faster** |
| `3PRF OOS Rec (Large)` | 22.0600 ms | 2.5200 ms | 3.7500 ms | 2.1600 ms | 0.7900 ms | **10.2× faster** | **27.9× faster** | **2.7× faster** |
| `3PRF OOS CV (Large)` | 38.0300 ms | 6.3000 ms | 4.7500 ms | 6.4800 ms | 1.0400 ms | **5.9× faster** | **36.6× faster** | **6.2× faster** |

- **NumPy**: Python 3.14.6 (openblas); OpenBLAS is NumPy's only mode
- **Scala·pure**: jvm=23 N=1000 MM=512 warmup=16 iters=60; matmul via matmulPure; 3PRF in a child JVM with -Duni.mat.blas=pure, medians of 25 (IS Full 200) after warm-up
- **Rust·pure**: blas=off threads=unset N=1000 warmup=16 iters=60 (pure build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)
- **Scala·BLAS**: jvm=23, child JVMs with -Duni.mat.blas=os-best (backend per platform and mode; see the [uni] line above)
- **Rust·BLAS**: blas=on threads=unset N=1000 warmup=16 iters=60 (blas build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)
- **BLAS backend** (`[uni]` lines from the run): BLAS via netlib: dev.ludovic.netlib.blas.JNIBLAS -> using it
- BLAS columns carry a number only on the rows BLAS can change (`matmul`, 3PRF); `·` elsewhere.

## Linux (zx-wsl: WSL2 Ubuntu on the same 24-thread machine)

#### Executive summary
Speedup = (second-named ms) ÷ (first-named ms), geometric mean over the rows named, with the
median beside it; above 1× means the first-named is faster.

**Languages, BLAS on all three — the like-for-like comparison.**
NumPy is always OpenBLAS; here Scala and Rust use theirs too on the 7 rows BLAS affects (every
other row is the same code in both modes). Rust's BLAS is a build-time feature, so its DEFAULT
build is the pure loop — a packaging constraint, not a language result; this block is Rust's
fair number.

| comparison | geomean | median | rows |
|---|---:|---:|---:|
| Scala vs NumPy | 1.80× | 1.54× | 50 |
| Rust vs NumPy | 1.66× | 1.13× | 48 |
| Rust vs Scala | 0.99× | 0.70× | 48 |

**The pinned loop, both ports — bit-identical results, no BLAS anywhere.**

| comparison | geomean | median | rows |
|---|---:|---:|---:|
| Rust·pure vs Scala·pure | 1.02× | 0.70× | 48 |
| Rust·pure vs Scala·pure (matmul + 3PRF only) | 3.00× | 2.79× | 7 |

**Configurations, as shipped — what a user gets without flags.**
Scala's default is BLAS (`os-best`); Rust's default build is the pure loop (`--features blas`
opts in). Against NumPy's BLAS this is the price of each default, not a statement about the
languages.

| comparison | geomean | median | rows |
|---|---:|---:|---:|
| Scala (default: BLAS) vs NumPy | 1.80× | 1.54× | 50 |
| Rust (default: pure) vs NumPy | 1.67× | 1.13× | 48 |
| Rust (default: pure) vs Scala (default: BLAS) | 0.99× | 0.65× | 48 |

**What BLAS buys each port, over the 7 rows it affects.**

| comparison | geomean | median | rows |
|---|---:|---:|---:|
| Scala·BLAS vs Scala·pure | 1.17× | 1.09× | 7 |
| Rust·BLAS vs Rust·pure | 0.96× | 0.93× | 7 |

#### Every row, every configuration — ms/call (lower is better)
The three ratio columns are like for like: BLAS on all three where BLAS applies.

| Operation | NumPy | Scala·pure | Rust·pure | Scala·BLAS | Rust·BLAS | Scala vs NumPy | Rust vs NumPy | Rust vs Scala |
|---|---:|---:|---:|---:|---:|---|---|---|
| `randn` | 6.3627 ms | 6.8589 ms | 4.1782 ms | · | · | **1.1× slower** | **1.5× faster** | **1.6× faster** |
| `matmul` | 0.4798 ms | 1.5894 ms | 1.4314 ms | 0.6589 ms | 0.4882 ms | **1.4× slower** | **1.0× slower** | **1.3× faster** |
| `sigmoid` | 3.4119 ms | 1.1066 ms | 0.3938 ms | · | · | **3.1× faster** | **8.7× faster** | **2.8× faster** |
| `relu` | 0.3000 ms | 0.6796 ms | 0.0923 ms | · | · | **2.3× slower** | **3.3× faster** | **7.4× faster** |
| `vectorize` | 68.0414 ms | 0.5580 ms | — | · | · | **121.9× faster** | — | — |
| `add` | 0.4429 ms | 0.6690 ms | 0.1042 ms | · | · | **1.5× slower** | **4.3× faster** | **6.4× faster** |
| `mul` | 0.4377 ms | 0.6904 ms | 0.1035 ms | · | · | **1.6× slower** | **4.2× faster** | **6.7× faster** |
| `abs` | 0.2614 ms | 0.9744 ms | 0.0929 ms | · | · | **3.7× slower** | **2.8× faster** | **10.5× faster** |
| `exp` | 1.6874 ms | 0.7499 ms | 0.2002 ms | · | · | **2.3× faster** | **8.4× faster** | **3.7× faster** |
| `log` | 1.9246 ms | 0.7715 ms | 0.1981 ms | · | · | **2.5× faster** | **9.7× faster** | **3.9× faster** |
| `sqrt` | 0.5576 ms | 0.6369 ms | 0.1129 ms | · | · | **1.1× slower** | **4.9× faster** | **5.6× faster** |
| `sum` | 0.1360 ms | 0.0513 ms | 0.1217 ms | · | · | **2.6× faster** | **1.1× faster** | **2.4× slower** |
| `mean` | 0.1371 ms | 0.0470 ms | 0.1030 ms | · | · | **2.9× faster** | **1.3× faster** | **2.2× slower** |
| `std` | 0.7056 ms | 0.4325 ms | 0.5081 ms | · | · | **1.6× faster** | **1.4× faster** | **1.2× slower** |
| `min` | 0.1094 ms | 0.0846 ms | 0.1350 ms | · | · | **1.3× faster** | **1.2× slower** | **1.6× slower** |
| `max` | 0.1088 ms | 0.0630 ms | 0.1480 ms | · | · | **1.7× faster** | **1.4× slower** | **2.4× slower** |
| `argmax` | 0.1368 ms | 0.0729 ms | 0.1611 ms | · | · | **1.9× faster** | **1.2× slower** | **2.2× slower** |
| `sum0` | 0.1188 ms | 0.0799 ms | 0.1851 ms | · | · | **1.5× faster** | **1.6× slower** | **2.3× slower** |
| `sum1` | 0.1312 ms | 0.0997 ms | 0.1798 ms | · | · | **1.3× faster** | **1.4× slower** | **1.8× slower** |
| `mean0` | 0.1221 ms | 0.0904 ms | 0.1887 ms | · | · | **1.3× faster** | **1.5× slower** | **2.1× slower** |
| `min0` | 0.1436 ms | 0.1491 ms | 0.2281 ms | · | · | **1.0× slower** | **1.6× slower** | **1.5× slower** |
| `max1` | 0.1231 ms | 0.0952 ms | 0.1697 ms | · | · | **1.3× faster** | **1.4× slower** | **1.8× slower** |
| `std0` | 0.9413 ms | 0.2068 ms | 0.4189 ms | · | · | **4.6× faster** | **2.2× faster** | **2.0× slower** |
| `cumsum` | 1.8716 ms | 0.7825 ms | 1.8640 ms | · | · | **2.4× faster** | **1.0× faster** | **2.4× slower** |
| `cumsum1` | 1.7924 ms | 0.7845 ms | 0.5650 ms | · | · | **2.3× faster** | **3.2× faster** | **1.4× faster** |
| `cummax0` | 3.0926 ms | 0.9568 ms | 0.7823 ms | · | · | **3.2× faster** | **4.0× faster** | **1.2× faster** |
| `cummin1` | 3.0221 ms | 1.0050 ms | 0.7184 ms | · | · | **3.0× faster** | **4.2× faster** | **1.4× faster** |
| `transpose` | 0.0001 ms | 0.0001 ms | 0.0000 ms | · | · | **1.5× slower** | — | — |
| `sum@contig` | 0.1258 ms | 0.0551 ms | 0.2323 ms | · | · | **2.3× faster** | **1.8× slower** | **4.2× slower** |
| `max@contig` | 0.1074 ms | 0.0696 ms | 0.2356 ms | · | · | **1.5× faster** | **2.2× slower** | **3.4× slower** |
| `std@contig` | 0.6691 ms | 0.4378 ms | 0.5874 ms | · | · | **1.5× faster** | **1.1× faster** | **1.3× slower** |
| `sum0@contig` | 0.1171 ms | 0.0903 ms | 0.1756 ms | · | · | **1.3× faster** | **1.5× slower** | **1.9× slower** |
| `sum@transposed` | 0.1255 ms | 0.1066 ms | 0.2352 ms | · | · | **1.2× faster** | **1.9× slower** | **2.2× slower** |
| `max@transposed` | 0.1045 ms | 0.1458 ms | 0.2234 ms | · | · | **1.4× slower** | **2.1× slower** | **1.5× slower** |
| `std@transposed` | 0.6700 ms | 0.5400 ms | 0.7313 ms | · | · | **1.2× faster** | **1.1× slower** | **1.4× slower** |
| `sum0@transposed` | 0.1319 ms | 0.1119 ms | 0.1997 ms | · | · | **1.2× faster** | **1.5× slower** | **1.8× slower** |
| `sum@rowslice` | 0.1262 ms | 0.0460 ms | 0.2026 ms | · | · | **2.7× faster** | **1.6× slower** | **4.4× slower** |
| `max@rowslice` | 0.1078 ms | 0.0699 ms | 0.1991 ms | · | · | **1.5× faster** | **1.8× slower** | **2.8× slower** |
| `std@rowslice` | 0.6703 ms | 0.4410 ms | 0.5685 ms | · | · | **1.5× faster** | **1.2× faster** | **1.3× slower** |
| `sum0@rowslice` | 0.1193 ms | 0.0881 ms | 0.1535 ms | · | · | **1.4× faster** | **1.3× slower** | **1.7× slower** |
| `sum@bcast` | 0.1814 ms | 0.0409 ms | 0.1736 ms | · | · | **4.4× faster** | **1.0× faster** | **4.2× slower** |
| `max@bcast` | 0.1682 ms | 0.0660 ms | 0.2135 ms | · | · | **2.6× faster** | **1.3× slower** | **3.2× slower** |
| `std@bcast` | 0.7389 ms | 0.4254 ms | 0.5449 ms | · | · | **1.7× faster** | **1.4× faster** | **1.3× slower** |
| `sum0@bcast` | 0.0617 ms | 0.0465 ms | 0.1675 ms | · | · | **1.3× faster** | **2.7× slower** | **3.6× slower** |
| `3PRF IS Full (Small)` | 0.1200 ms | 0.1600 ms | 0.0130 ms | 0.0800 ms | 0.0140 ms | **1.5× faster** | **8.6× faster** | **5.7× faster** |
| `3PRF OOS Rec (Small)` | 4.6100 ms | 0.7800 ms | 0.6000 ms | 1.1200 ms | 0.6400 ms | **4.1× faster** | **7.2× faster** | **1.8× faster** |
| `3PRF OOS CV (Small)` | 8.4200 ms | 0.9400 ms | 0.6000 ms | 1.5200 ms | 0.8700 ms | **5.5× faster** | **9.7× faster** | **1.7× faster** |
| `3PRF IS Full (Large)` | 0.2500 ms | 0.3500 ms | 0.0510 ms | 0.3200 ms | 0.0510 ms | **1.3× slower** | **4.9× faster** | **6.3× faster** |
| `3PRF OOS Rec (Large)` | 21.4700 ms | 2.9800 ms | 1.0700 ms | 2.1500 ms | 1.5100 ms | **10.0× faster** | **14.2× faster** | **1.4× faster** |
| `3PRF OOS CV (Large)` | 37.0900 ms | 5.7000 ms | 1.3800 ms | 5.9900 ms | 2.2200 ms | **6.2× faster** | **16.7× faster** | **2.7× faster** |

- **NumPy**: Python 3.14.4 (blas); OpenBLAS is NumPy's only mode
- **Scala·pure**: jvm=21.0.11 N=1000 MM=512 warmup=16 iters=60; matmul via matmulPure; 3PRF in a child JVM with -Duni.mat.blas=pure, medians of 25 (IS Full 200) after warm-up
- **Rust·pure**: blas=off threads=unset N=1000 warmup=16 iters=60 (pure build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)
- **Scala·BLAS**: jvm=21.0.11, child JVMs with -Duni.mat.blas=os-best (backend per platform and mode; see the [uni] line above)
- **Rust·BLAS**: blas=on threads=unset N=1000 warmup=16 iters=60 (blas build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)
- **BLAS backend** (`[uni]` lines from the run): os-best on Linux: netlib is dev.ludovic.netlib.blas.JNIBLAS -> system; BLAS via netlib: dev.ludovic.netlib.blas.JNIBLAS -> using it; LAPACK via dev.ludovic.netlib.lapack.JNILAPACK
- BLAS columns carry a number only on the rows BLAS can change (`matmul`, 3PRF); `·` elsewhere.

## macOS (suemac, Apple Silicon)

#### Executive summary
Speedup = (second-named ms) ÷ (first-named ms), geometric mean over the rows named, with the
median beside it; above 1× means the first-named is faster.

**Languages, BLAS on all three — the like-for-like comparison.**
NumPy is always OpenBLAS; here Scala and Rust use theirs too on the 7 rows BLAS affects (every
other row is the same code in both modes). Rust's BLAS is a build-time feature, so its DEFAULT
build is the pure loop — a packaging constraint, not a language result; this block is Rust's
fair number.

| comparison | geomean | median | rows |
|---|---:|---:|---:|
| Scala vs NumPy | 1.38× | 1.44× | 50 |
| Rust vs NumPy | 2.09× | 1.94× | 48 |
| Rust vs Scala | 1.65× | 1.31× | 48 |

**The pinned loop, both ports — bit-identical results, no BLAS anywhere.**

| comparison | geomean | median | rows |
|---|---:|---:|---:|
| Rust·pure vs Scala·pure | 1.80× | 1.35× | 48 |
| Rust·pure vs Scala·pure (matmul + 3PRF only) | 4.35× | 4.06× | 7 |

**Configurations, as shipped — what a user gets without flags.**
Scala's default is BLAS (`os-best`); Rust's default build is the pure loop (`--features blas`
opts in). Against NumPy's BLAS this is the price of each default, not a statement about the
languages.

| comparison | geomean | median | rows |
|---|---:|---:|---:|
| Scala (default: BLAS) vs NumPy | 1.38× | 1.44× | 50 |
| Rust (default: pure) vs NumPy | 2.10× | 1.94× | 48 |
| Rust (default: pure) vs Scala (default: BLAS) | 1.65× | 1.31× | 48 |

**What BLAS buys each port, over the 7 rows it affects.**

| comparison | geomean | median | rows |
|---|---:|---:|---:|
| Scala·BLAS vs Scala·pure | 1.83× | 1.82× | 7 |
| Rust·BLAS vs Rust·pure | 0.99× | 0.92× | 7 |

#### Every row, every configuration — ms/call (lower is better)
The three ratio columns are like for like: BLAS on all three where BLAS applies.

| Operation | NumPy | Scala·pure | Rust·pure | Scala·BLAS | Rust·BLAS | Scala vs NumPy | Rust vs NumPy | Rust vs Scala |
|---|---:|---:|---:|---:|---:|---|---|---|
| `randn` | 4.9320 ms | 3.4136 ms | 2.3425 ms | · | · | **1.4× faster** | **2.1× faster** | **1.5× faster** |
| `matmul` | 2.1323 ms | 6.1331 ms | 2.4746 ms | 1.0643 ms | 2.1273 ms | **2.0× faster** | **1.0× faster** | **2.0× slower** |
| `sigmoid` | 6.5319 ms | 4.0686 ms | 1.3097 ms | · | · | **1.6× faster** | **5.0× faster** | **3.1× faster** |
| `relu` | 0.3709 ms | 1.0849 ms | 0.1597 ms | · | · | **2.9× slower** | **2.3× faster** | **6.8× faster** |
| `vectorize` | 115.2911 ms | 0.4463 ms | — | · | · | **258.3× faster** | — | — |
| `add` | 0.3308 ms | 1.0509 ms | 0.2782 ms | · | · | **3.2× slower** | **1.2× faster** | **3.8× faster** |
| `mul` | 0.3330 ms | 1.0575 ms | 0.2796 ms | · | · | **3.2× slower** | **1.2× faster** | **3.8× faster** |
| `abs` | 0.1733 ms | 1.0918 ms | 0.1627 ms | · | · | **6.3× slower** | **1.1× faster** | **6.7× faster** |
| `exp` | 5.9470 ms | 3.3990 ms | 1.1248 ms | · | · | **1.7× faster** | **5.3× faster** | **3.0× faster** |
| `log` | 5.2505 ms | 2.8310 ms | 1.0202 ms | · | · | **1.9× faster** | **5.1× faster** | **2.8× faster** |
| `sqrt` | 0.3204 ms | 1.0864 ms | 0.1629 ms | · | · | **3.4× slower** | **2.0× faster** | **6.7× faster** |
| `sum` | 0.1857 ms | 0.0708 ms | 0.0610 ms | · | · | **2.6× faster** | **3.0× faster** | **1.2× faster** |
| `mean` | 0.1876 ms | 0.0607 ms | 0.0603 ms | · | · | **3.1× faster** | **3.1× faster** | **1.0× faster** |
| `std` | 0.7132 ms | 1.0506 ms | 1.0652 ms | · | · | **1.5× slower** | **1.5× slower** | **1.0× slower** |
| `min` | 0.1043 ms | 0.1675 ms | 0.1533 ms | · | · | **1.6× slower** | **1.5× slower** | **1.1× faster** |
| `max` | 0.1043 ms | 0.2321 ms | 0.1077 ms | · | · | **2.2× slower** | **1.0× slower** | **2.2× faster** |
| `argmax` | 0.6839 ms | 0.2622 ms | 0.1200 ms | · | · | **2.6× faster** | **5.7× faster** | **2.2× faster** |
| `sum0` | 0.1668 ms | 0.1145 ms | 0.1169 ms | · | · | **1.5× faster** | **1.4× faster** | **1.0× slower** |
| `sum1` | 0.1895 ms | 0.2069 ms | 0.1979 ms | · | · | **1.1× slower** | **1.0× slower** | **1.0× faster** |
| `mean0` | 0.1693 ms | 0.1292 ms | 0.1195 ms | · | · | **1.3× faster** | **1.4× faster** | **1.1× faster** |
| `min0` | 0.1803 ms | 0.1937 ms | 0.1812 ms | · | · | **1.1× slower** | **1.0× slower** | **1.1× faster** |
| `max1` | 0.1146 ms | 0.1714 ms | 0.1251 ms | · | · | **1.5× slower** | **1.1× slower** | **1.4× faster** |
| `std0` | 0.8468 ms | 0.3048 ms | 0.2877 ms | · | · | **2.8× faster** | **2.9× faster** | **1.1× faster** |
| `cumsum` | 3.4519 ms | 1.1002 ms | 0.9999 ms | · | · | **3.1× faster** | **3.5× faster** | **1.1× faster** |
| `cumsum1` | 3.4800 ms | 1.1626 ms | 1.0057 ms | · | · | **3.0× faster** | **3.5× faster** | **1.2× faster** |
| `cummax0` | 3.5360 ms | 1.3125 ms | 1.0156 ms | · | · | **2.7× faster** | **3.5× faster** | **1.3× faster** |
| `cummin1` | 3.4699 ms | 1.3676 ms | 1.0913 ms | · | · | **2.5× faster** | **3.2× faster** | **1.3× faster** |
| `transpose` | 0.0001 ms | 0.0003 ms | 0.0000 ms | · | · | **2.5× slower** | — | — |
| `sum@contig` | 0.1837 ms | 0.0643 ms | 0.0530 ms | · | · | **2.9× faster** | **3.5× faster** | **1.2× faster** |
| `max@contig` | 0.1042 ms | 0.2248 ms | 0.1201 ms | · | · | **2.2× slower** | **1.2× slower** | **1.9× faster** |
| `std@contig` | 0.7161 ms | 1.0412 ms | 1.0588 ms | · | · | **1.5× slower** | **1.5× slower** | **1.0× slower** |
| `sum0@contig` | 0.1666 ms | 0.1184 ms | 0.1193 ms | · | · | **1.4× faster** | **1.4× faster** | **1.0× slower** |
| `sum@transposed` | 0.1856 ms | 0.1289 ms | 0.0966 ms | · | · | **1.4× faster** | **1.9× faster** | **1.3× faster** |
| `max@transposed` | 0.1038 ms | 0.2816 ms | 0.1281 ms | · | · | **2.7× slower** | **1.2× slower** | **2.2× faster** |
| `std@transposed` | 0.7125 ms | 1.1170 ms | 1.1364 ms | · | · | **1.6× slower** | **1.6× slower** | **1.0× slower** |
| `sum0@transposed` | 0.1896 ms | 0.1449 ms | 0.0772 ms | · | · | **1.3× faster** | **2.5× faster** | **1.9× faster** |
| `sum@rowslice` | 0.1854 ms | 0.0631 ms | 0.0703 ms | · | · | **2.9× faster** | **2.6× faster** | **1.1× slower** |
| `max@rowslice` | 0.1042 ms | 0.2253 ms | 0.1107 ms | · | · | **2.2× slower** | **1.1× slower** | **2.0× faster** |
| `std@rowslice` | 0.7145 ms | 1.0393 ms | 1.0828 ms | · | · | **1.5× slower** | **1.5× slower** | **1.0× slower** |
| `sum0@rowslice` | 0.1665 ms | 0.0983 ms | 0.1174 ms | · | · | **1.7× faster** | **1.4× faster** | **1.2× slower** |
| `sum@bcast` | 0.2684 ms | 0.0427 ms | 0.0660 ms | · | · | **6.3× faster** | **4.1× faster** | **1.5× slower** |
| `max@bcast` | 0.1623 ms | 0.2211 ms | 0.1181 ms | · | · | **1.4× slower** | **1.4× faster** | **1.9× faster** |
| `std@bcast` | 0.8237 ms | 1.0215 ms | 1.0463 ms | · | · | **1.2× slower** | **1.3× slower** | **1.0× slower** |
| `sum0@bcast` | 0.1363 ms | 0.0713 ms | 0.0804 ms | · | · | **1.9× faster** | **1.7× faster** | **1.1× slower** |
| `3PRF IS Full (Small)` | 0.1500 ms | 0.2000 ms | 0.0410 ms | 0.1100 ms | 0.0350 ms | **1.4× faster** | **4.3× faster** | **3.1× faster** |
| `3PRF OOS Rec (Small)` | 5.4800 ms | 3.2600 ms | 0.4400 ms | 1.6500 ms | 0.3900 ms | **3.3× faster** | **14.1× faster** | **4.2× faster** |
| `3PRF OOS CV (Small)` | 10.0600 ms | 1.9000 ms | 0.5000 ms | 2.1500 ms | 0.5500 ms | **4.7× faster** | **18.3× faster** | **3.9× faster** |
| `3PRF IS Full (Large)` | 0.3500 ms | 0.4500 ms | 0.0700 ms | 0.2300 ms | 0.0780 ms | **1.5× faster** | **4.5× faster** | **2.9× faster** |
| `3PRF OOS Rec (Large)` | 26.7500 ms | 7.2500 ms | 2.1700 ms | 4.8900 ms | 2.3500 ms | **5.5× faster** | **11.4× faster** | **2.1× faster** |
| `3PRF OOS CV (Large)` | 48.3800 ms | 13.4500 ms | 3.3100 ms | 10.5300 ms | 4.2500 ms | **4.6× faster** | **11.4× faster** | **2.5× faster** |

- **NumPy**: Python 3.14.6 (openblas); OpenBLAS is NumPy's only mode
- **Scala·pure**: jvm=25.0.2 N=1000 MM=512 warmup=16 iters=60; matmul via matmulPure; 3PRF in a child JVM with -Duni.mat.blas=pure, medians of 25 (IS Full 200) after warm-up
- **Rust·pure**: blas=off threads=unset N=1000 warmup=16 iters=60 (pure build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)
- **Scala·BLAS**: jvm=25.0.2, child JVMs with -Duni.mat.blas=os-best (backend per platform and mode; see the [uni] line above)
- **Rust·BLAS**: blas=on threads=unset N=1000 warmup=16 iters=60 (blas build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)
- **BLAS backend** (`[uni]` lines from the run): BLAS via netlib: dev.ludovic.netlib.blas.JNIBLAS -> using it
- BLAS columns carry a number only on the rows BLAS can change (`matmul`, 3PRF); `·` elsewhere.

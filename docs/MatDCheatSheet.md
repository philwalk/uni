# MatD Cheat Sheet

Side-by-side reference for **uni.MatD**, NumPy, Breeze, R, and MATLAB.

`MatD` = `Mat[Double]` — the standard double-precision matrix type in [uni.Mat](../README.md).

---

## Performance vs NumPy

One table per platform, one column set, apples to apples:

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

### Regenerating every table

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

### Across the three boxes

**Languages, like for like — BLAS on all three.** Geometric-mean speedup over all rows;
NumPy is always OpenBLAS, and here Scala and Rust use theirs too (on the 7 rows BLAS
affects; every other row is identical code in both modes). Above 1× means the first-named
is faster.

| box | Scala vs NumPy | Rust vs NumPy | Rust vs Scala |
|---|---:|---:|---:|
| Windows 11 | 3.15× | 3.79× | 1.30× |
| Linux (WSL2, same box) | 1.80× | 1.66× | 0.99× |
| macOS | 1.38× | 2.09× | 1.65× |

**The pinned loop, both ports.** `matmulPure` on each side — bit-identical results, no BLAS:

| box | Rust·pure vs Scala·pure (all rows) | … (matmul + 3PRF only) |
|---|---:|---:|
| Windows 11 | 1.15× | 1.84× |
| Linux (WSL2, same box) | 1.02× | 3.00× |
| macOS | 1.80× | 4.35× |

**Configurations, as shipped.** Scala's default is BLAS (`os-best`); Rust's default build
is the pinned loop, because its BLAS is a build-time feature a library cannot switch on for
its users — so the Rust column here is the price of that packaging constraint, not a
statement about the language:

| box | Scala (default: BLAS) vs NumPy | Rust (default: pure) vs NumPy |
|---|---:|---:|
| Windows 11 | 3.15× | 3.36× |
| Linux (WSL2, same box) | 1.80× | 1.67× |
| macOS | 1.38× | 2.10× |

**`matmul` 512³, ms/call** — the row the BLAS choice hangs on:

| box | NumPy | Scala·pure | Rust·pure | Scala·BLAS | Rust·BLAS |
|---|---:|---:|---:|---:|---:|
| Windows 11 | 0.78 | 1.42 | 1.68 | 0.67 | 0.81 |
| Linux (WSL2, same box) | 0.48 | 1.59 | 1.43 | 0.66 | 0.49 |
| macOS | 2.13 | 6.13 | 2.47 | 1.06 | 2.13 |

Reading them together:

- **Like for like, both ports are faster than NumPy on every box** — Scala 1.4–3.2×, Rust
  1.7–3.8× — and Rust and Scala are within 1.0–1.65× of each other. The 3PRF rows drive
  much of that.
- **The pinned loop trails OpenBLAS by 2–3× on this shape everywhere** (Scala·pure 1.8× /
  3.3× / 2.9× behind NumPy; Rust·pure 2.1× / 3.0× / 1.2× — on aarch64 NEON is baseline, so
  LLVM vectorises the microkernel fully; on x86-64 the crate targets SSE2). Scala·BLAS
  closes it on every box — 0.67 / 0.66 / 1.06 ms against NumPy's 0.78 / 0.48 / 2.13 —
  which is why BLAS is the Scala default. On Linux that number is the OS OpenBLAS via
  netlib (`os-best` → `system` when `libopenblas0` is installed); bytedeco's bundled build
  read 2.98 ms on the same box, *slower than the pinned loop* on 24 threads.
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

### Windows 11 (zx, 24 threads)

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

### Linux (zx-wsl: WSL2 Ubuntu on the same 24-thread box)

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

### macOS (suemac, Apple Silicon)

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

## Performance vs Breeze

Measured on the same machine: Breeze 2.1.0 vs uni.MatD 0.14.1 / Scala 3.8.2 / JVM 21; min times.
Both ran `matmul` on the same pure-JVM VectorBLAS fallback (JNIBLAS unavailable on this
machine), so that row is a like-for-like comparison. See [`jsrc/benchBreeze.sc`](../jsrc/benchBreeze.sc) to reproduce.

| Operation | MatD | Breeze | Ratio | Notes |
|---|---:|---:|---|---|
| `randn(1000×1000)` | 7.1 ms | 21.6 ms | **3.1× faster** | PCG64 (MatD) vs Gaussian sampler (Breeze) |
| `matmul 512×512` | 5.4 ms | 5.3 ms | **tied** | Same VectorBLAS fallback backend on both; with JNIBLAS both call OpenBLAS at the same latency |
| `sigmoid(1000×1000)` | 0.73 ms | 4.2 ms | **5.7× faster** | Parallel fork/join (MatD) vs sequential UFunc (Breeze) |
| `relu(1000×1000)` | 0.51 ms | 2.9 ms | **5.7× faster** | Parallel fork/join (MatD) vs sequential map (Breeze) |
| `add(1000×1000)` | 0.59 ms | 1.2 ms | **2.0× faster** | Parallel fork/join (MatD) vs sequential element-wise (Breeze) |
| `sum(1000×1000)` | 0.04 ms | 0.37 ms | **8.4× faster** | v0.14.1 chunked multi-accumulator parallel reduction vs sequential loop |
| `mean(1000×1000)` | 0.03 ms | 3.6 ms | **~109× faster** | Same reduction as `sum`; Breeze mean is a slow generic path |
| `std(1000×1000)` | 0.48 ms | 5.4 ms | **11× faster** | Two-pass with the v0.14.1 reduction for the mean pass |
| `transpose(1000×1000)` | ≈0 ms | ≈0 ms | **tied** | O(1) stride-flip in both — no data copy |
| `mapParallel` custom fn | 0.52 ms | 5.9 ms | **11× faster** | Parallel fork/join (MatD) vs sequential map (Breeze) |

**Practical guidance:**
- MatD wins 6 of the 7 operations `benchBreeze.sc` scores and ties the seventh (matmul); geometric mean: MatD is **~4.3×** faster overall (the sub-50 µs `sum`/`mean`/`transpose` rows are excluded from scoring).
- Matmul is tied: both libraries ran the same VectorBLAS fallback here; with netlib JNIBLAS loaded (direct Java array passing, no DoublePointer/DirectBuffer overhead) both call native OpenBLAS at the same latency.
- Element-wise, reduction, and custom-function operations show the largest gaps because MatD parallelizes with fork/join while Breeze processes elements sequentially.

---

## Setup / Import

| | Code |
|---|---|
| **MatD** | `import uni.data.*` |
| **NumPy** | `import numpy as np` |
| **Breeze** | `import breeze.linalg.*` |
| **R** | *(built-in)* |
| **MATLAB** | *(built-in)* |

netlib's BLAS-backend announcement (`INFO: Using dev.ludovic.netlib.blas.JNIBLAS` on
stderr at first MatD use) is suppressed by default; run with `-Duni.blas.verbose=true`
to see it again, or enable uni's generic verbosity mode with `-Duni.verbose=true` or
the `VERBOSE_UNI` env var (for scala-cli: `--java-opt -Duni.blas.verbose=true`).

---

## Matrix Creation

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| All zeros | `MatD.zeros(r, c)` | `np.zeros((r, c))` | `DenseMatrix.zeros[Double](r, c)` | `matrix(0, r, c)` | `zeros(r, c)` |
| All ones | `MatD.ones(r, c)` | `np.ones((r, c))` | `DenseMatrix.ones[Double](r, c)` | `matrix(1, r, c)` | `ones(r, c)` |
| Identity | `MatD.eye(n)` | `np.eye(n)` | `DenseMatrix.eye[Double](n)` | `diag(n)` | `eye(n)` |
| From flat array | `MatD(r, c, arr)` | `np.array(lst).reshape(r, c)` | `new DenseMatrix(r, c, arr)` | `matrix(v, r, c)` | `reshape(v, r, c)` |
| From 2-D array | `arr2d.toMat` / `MatD(arr2d)` | `np.array(lst2d)` | `DenseMatrix(rows: _*)` | `do.call(rbind, l)` | `cell2mat(c)` |
| From rows (`Seq[Array]`) | `rows.toMat` / `MatD.fromRows(rows)` | `np.array(list(rows))` | `DenseMatrix(rows: _*)` | `do.call(rbind, l)` | `cell2mat(c)` |
| From rows (tuples) | `MatD((1,2),(3,4))` | `np.array([[1,2],[3,4]])` | `DenseMatrix((1.0,2.0),(3.0,4.0))` | `rbind(c(1,2),c(3,4))` | `[1 2; 3 4]` |
| Column vector | `MatD(1.0, 2.0, 3.0)` | `np.array([[1],[2],[3]])` | `DenseVector(1.0, 2.0, 3.0)` | `matrix(1:3)` | `[1; 2; 3]` |
| Column from array | `arr.toCVec` / `MatD(arr)` | `np.array(lst)[:,None]` | `DenseVector(arr)` | `matrix(v)` | `v(:)` |
| Row vector | `MatD.row(1, 2, 3)` | `np.array([[1, 2, 3]])` | `DenseVector(1.0, 2.0, 3.0).t` | `t(matrix(1:3))` | `[1 2 3]` |
| Row from array | `arr.toRVec` | `np.array(lst)[None,:]` | `DenseVector(arr).t` | `t(matrix(v))` | `v(:).'` |
| Wrap array, no copy | `Mat.wrap(arr, r, c)` | `np.frombuffer(...)` | `new DenseMatrix(r, c, arr)` | — | — |
| From function | `MatD.tabulate(r,c)((i,j) => f(i,j))` | `np.fromfunction(f, (r,c))` | `DenseMatrix.tabulate(r,c)(f)` | `outer(1:r, 1:c, f)` | `arrayfun(f, I, J)` |
| Diagonal matrix | `MatD.diag(vec)` | `np.diag(v)` | `diag(v)` | `diag(v)` | `diag(v)` |
| Zeros like | `MatD.zerosLike(m)` | `np.zeros_like(m)` | `DenseMatrix.zeros[Double](m.rows, m.cols)` | `matrix(0, nrow(m), ncol(m))` | `zeros(size(m))` |
| Ones like | `MatD.onesLike(m)` | `np.ones_like(m)` | `DenseMatrix.ones[Double](m.rows, m.cols)` | `matrix(1, nrow(m), ncol(m))` | `ones(size(m))` |
| Fill like | `MatD.fullLike(m, v)` | `np.full_like(m, v)` | `DenseMatrix.fill(m.rows, m.cols)(v)` | `matrix(v, nrow(m), ncol(m))` | `repmat(v, size(m))` |

> **Since v0.14.1:** flat varargs `MatD(1.0, 2.0, 3.0)` (also `MatB`, `MatF`) build a **column**
> vector, matching `Mat(…)`, `CVec(…)`, and Breeze's `DenseVector(…)` — previously they built a row.
> Use `MatD.row(…)` for an explicit row vector.
> Integer arguments are **values**, as in NumPy — `MatD(1, 2, 3)` is a 3×1 column of
> `1.0, 2.0, 3.0`, just like `np.array([1, 2, 3])`. This holds at every arity.
>
> **Changed in v0.15.0:** `MatD(rows, cols)` — exactly two Ints — used to be a `(rows, cols)`
> zero-matrix constructor, the one place where Ints meant dimensions instead of values.
> It no longer compiles. Use `MatD.zeros(3, 4)` for a zero matrix, `MatD.empty` for 0×0, or
> `MatD(3.0, 4.0)` for a 2×1 column of values. NumPy separates the two the same way —
> `np.array([1, 2])` vs `np.zeros((3, 4))` — by name rather than by argument type.
> The overload is retained as a compile-time tombstone, so the old spelling gives an error
> naming the replacements rather than silently becoming a 2×1 column.

### Converting arrays

Every conversion below **copies**, so the result shares no storage with the source array.

```scala
val arr   = Array(1.0, 2.0, 3.0)
val arr2d = Array(Array(1.0, 2.0, 3.0), Array(4.0, 5.0, 6.0))

arr.toCVec        // 3×1 column      arr.toRVec   // 1×3 row
arr.toMat         // 3×1 (column is the default)
arr2d.toMat       // 2×3, row-major

Vector(rowA, rowB).toMat        // Seq[Array[T]] works too
List(rowA, rowB).toMat
```

The same shapes are reachable through the companions, so whichever spelling you
reach for first works:

| you write | you get |
|---|---|
| `arr.toCVec`, `CVec(arr)`, `MatD(arr)`, `Mat(arr)` | n×1 column |
| `arr.toRVec`, `RVec(arr)` | 1×n row |
| `arr2d.toMat`, `MatD(arr2d)`, `Mat(arr2d)`, `MatD.fromRows(arr2d)` | row-major matrix |
| `rows.toMat`, `MatD.fromRows(rows)` | row-major matrix from `Seq[Array[T]]` |

2-D input is **row-major**: `arr2d.toMat(0, 1) == 2.0` and `arr2d.toMat(1, 0) == 4.0`.
Ragged input raises `IllegalArgumentException` naming the offending row; empty input gives 0×0.

> **Copy vs alias.** `Mat` is mutable — `m(r, c) = v` writes into the backing array — so the
> conversions above copy to keep the matrix and the source array independent.
> Two entry points do **not** copy and alias the array you hand them:
> `Mat.create(arr, r, c)` and `MatD(r, c, arr)`. After either, writing to the matrix changes
> your array and vice versa. When you want that, prefer `Mat.wrap(arr, r, c)` — same behaviour,
> but the intent is visible at the call site.

> **Before v0.15.0**, `CVec(arr)` bound the varargs overload with `T := Array[Double]` and produced
> a **1×1 holding the array object** rather than a 3×1 column; `Mat(arr)` did the same via the
> scalar lift. Both now do the obvious thing. The scalar lift `Mat(42.0)` → 1×1 is unchanged.

---

## Random Generation

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Set seed | `MatD.setSeed(42)` | `np.random.seed(42)` | `RandBasis.withSeed(42)` | `set.seed(42)` | `rng(42)` |
| Uniform [0,1) | `MatD.rand(r, c)` | `np.random.rand(r, c)` | `DenseMatrix.rand(r, c)` | `matrix(runif(r*c), r, c)` | `rand(r, c)` |
| Standard normal | `MatD.randn(r, c)` | `np.random.randn(r, c)` | `DenseMatrix.randn(r, c)` | `matrix(rnorm(r*c), r, c)` | `randn(r, c)` |
| Uniform [lo,hi) | `MatD.uniform(lo, hi, r, c)` | `np.random.uniform(lo, hi, (r,c))` | `DenseMatrix.rand(r,c,Uniform(lo,hi))` | `matrix(runif(r*c,lo,hi),r,c)` | `lo+(hi-lo)*rand(r,c)` |
| Normal(μ,σ) | `MatD.normal(mu, sd, r, c)` | `np.random.normal(mu, sd, (r,c))` | `DenseMatrix.rand(r,c,Gaussian(mu,sd))` | `matrix(rnorm(r*c,mu,sd),r,c)` | `mu+sd*randn(r,c)` |
| Random ints | `MatD.randint(lo, hi, r, c)` | `np.random.randint(lo, hi, (r,c))` | `DenseMatrix.rand(r,c,...).map(_.toInt)` | `matrix(sample(lo:hi,r*c,T),r,c)` | `randi([lo hi-1],r,c)` |

> **Note:** `MatD.setSeed` / `MatD.rand` / `MatD.randn` / `MatD.uniform` use a 100% faithful PCG64 implementation matching NumPy's output bit-for-bit.

---

## Shape and Info

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Dimensions | `m.shape` | `m.shape` | `(m.rows, m.cols)` | `dim(m)` | `size(m)` |
| Row count | `m.rows`<br>`m.shape._1` | `m.shape[0]` | `m.rows` | `nrow(m)` | `size(m,1)` |
| Col count | `m.cols`<br>`m.shape._2` | `m.shape[1]` | `m.cols` | `ncol(m)` | `size(m,2)` |
| Total elements | `m.size` | `m.size` | `m.size` | `length(m)` | `numel(m)` |
| Reshape | `m.reshape(r, c)` | `m.reshape(r, c)` | `m.reshape(r, c)` | `matrix(m, r, c)` | `reshape(m, r, c)` |
| Flatten to array | `m.flatten` | `m.flatten()` | `m.data` | `as.vector(m)` | `m(:)` |
| Flatten to row vec | `m.ravel` | `m.ravel()` | — | — | — |
| Extract scalar | `m.item`<br>`m(0, 0)` | `m.item()` | `m(0,0)` | `m[1,1]` | `m(1,1)` |

---

## Indexing and Slicing

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Single element | `m(i, j)` | `m[i, j]` | `m(i, j)` | `m[i+1, j+1]` | `m(i+1, j+1)` |
| Row slice (copy) | `m(i, ::)` | `m[i, :]` | `m(i, ::)` | `m[i+1,]` | `m(i+1,:)` |
| Column slice (copy) | `m(::, j)` | `m[:, j]` | `m(::, j)` | `m[,j+1]` | `m(:,j+1)` |
| Zero-copy view | `m.slice(rows, cols)` | `m[r0:r1, c0:c1]` | — | — | — |
| Transpose (O(1)) | `m.T` or `m.transpose` | `m.T` | `m.t` | `t(m)` | `m'` |

> Indexing is **zero-based** with negative-index support. Unlike NumPy, the `m(...)` slice
> forms return independent **copies**; `m.slice(rows, cols)` (and `.T`) are zero-copy views
> that share storage with the parent.

---

## Column / Row Mapping

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Map each column | `m.eachCol.map(f)`<br>`m(::, *).map(f)`<br>`m.mapCols(f)` | `np.apply_along_axis(f, 0, m)` | `X(::, *).map(f)` | `apply(m, 2, f)` | — |
| Map each row | `m.eachRow.map(f)`<br>`m(*, ::).map(f)`<br>`m.mapRows(f)` | `np.apply_along_axis(f, 1, m)` | `X(*, ::).map(f)` | `apply(m, 1, f)` | — |

`f` receives a `ColVec[T]` (n×1) for column mapping, a `RowVec[T]` (1×n) for row mapping, and must return the same shape. All three MatD spellings per row are equivalent.

```scala
// Sort each column independently (Breeze-style sentinel)
m(::, *).map(col => col.sort())

// Sort each column independently (named method — preferred when also importing breeze.linalg.*)
m.eachCol.map(col => col.sort())

// Reverse each row
m.eachRow.map(row => row(::, row.cols-1 to 0 by -1))
```

> **Note:** For broadcasting operations (subtract column means, divide by std) use arithmetic directly — `m - m.mean(axis=0)` is both simpler and faster.

---

## Migrating from Breeze

Key syntax differences for Breeze users. Most idioms carry over directly; the main changes are
operator names and a few method renames.

| Breeze | MatD | Note |
|--------|------|------|
| `X * Y` | `X *@ Y` | Matrix multiply (`*` is element-wise in MatD) |
| `X :* Y` | `X * Y` | Element-wise multiply |
| `X :/ Y` | `X / Y` | Element-wise divide |
| `X ^:^ p` | `X ~^ p` | Element-wise power; `~^` binds tighter than `*@` — write `(X ~^ 2) *@ Y` |
| `X.t` | `X.T` | Transpose |
| `sum(X, Axis._0)` | `X.sum(axis=0)` | Column-wise sum |
| `sum(X, Axis._1)` | `X.sum(axis=1)` | Row-wise sum |
| `X(::, *).map(f)` | `X(::, *).map(f)` or `X.eachCol.map(f)` | Apply f to each column |
| `X(*, ::).map(f)` | `X(*, ::).map(f)` or `X.eachRow.map(f)` | Apply f to each row |
| `X(i, ::)` | `X(i, ::)` | Row slice (identical) |
| `X(::, j)` | `X(::, j)` | Column slice (identical) |
| `svd(X)` | `X.svd` | SVD decomposition |
| `X \ b` | `X.solve(b)` | Least-squares solve |

<sub>The broadcast sentinel `*` is spelled identically in both libraries. In files that import both,
use `X.eachCol` / `X.eachRow` to sidestep the name collision, or rename at import:
`import uni.data.{\`*\` as All}`.</sub>

---

## Element-wise Arithmetic

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Add matrices | `a + b` | `a + b` | `a + b` | `a + b` | `a + b` |
| Subtract | `a - b` | `a - b` | `a - b` | `a - b` | `a - b` |
| Multiply (Hadamard) | `a * b` | `a * b` | `a :* b` | `a * b` | `a .* b` |
| Divide | `a / b` | `a / b` | `a :/ b` | `a / b` | `a ./ b` |
| Add scalar | `a + 2.0` | `a + 2.0` | `a + 2.0` | `a + 2` | `a + 2` |
| Multiply scalar | `a * 3.0` | `a * 3.0` | `a * 3.0` | `a * 3` | `a * 3` |

All four operators work with `CVecD`/`RVecD` operands too, elementwise, and keep the
vector type: `vecA * vecB`, `vecA / vecB`, and `cvec op mat` (a Mat-typed vector
expression such as `eq - eq.cummax(0)`) all return the vector type of the left operand.

Vector idioms for two NumPy 1-D forms:

| NumPy (1-D `v`) | MatD | Notes |
|---|---|---|
| `v[:k]` | `v(0 until k, 0)` (CVec), `r(0, 0 until k)` (RVec) | returns `CVecD` / `RVecD` |
| `np.maximum.accumulate(v)` | `v.cummax(0)` (CVec), `r.cummax(1)` (RVec) | `cummin` likewise |
| max drawdown | `1.0 - (eq - eq.cummax(0)).exp.min` | log-equity curve `eq: CVecD` |

---

## Matrix Multiplication

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Matrix multiply | `a *@ b` | `a @ b` | `a * b` | `a %*% b` | `a * b` |
| Mat × col-vec | `X *@ y` where `y: CVecD` → `CVecD` | `X @ y` | `X * y` | `X %*% y` | `X * y` |
| row-vec × Mat | `r *@ X` where `r: RVecD` → `RVecD` | `r @ X` | `r * X` | `r %*% X` | `r * X` |
| dot product | `y *@ y` (CVec auto-transposes) → `Double` | `y @ y` | `y dot y` | `t(y) %*% y` | `y' * y` |
| outer product | `y *@ y.T` (CVec × RVec) → `MatD` | `np.outer(y, y)` | `y * y.t` | `y %o% y` | `y * y'` |

---

## In-place Operations

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Add scalar in-place | `m :+= 2.0` | `m += 2.0` | `m :+= 2.0` | `m[] <- m + 2` | `m = m + 2` |
| Subtract scalar | `m :-= 1.0` | `m -= 1.0` | `m :-= 1.0` | `m[] <- m - 1` | `m = m - 1` |
| Multiply scalar | `m :*= 3.0` | `m *= 3.0` | `m :*= 3.0` | `m[] <- m * 3` | `m = m * 3` |
| Divide scalar | `m :/= 2.0` | `m /= 2.0` | `m :/= 2.0` | `m[] <- m / 2` | `m = m / 2` |
| Add matrix in-place | `m :+= n` | `m += n` | `m :+= n` | `m[] <- m + n` | `m = m + n` |

---

## Reductions

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Sum all | `m.sum` | `m.sum()` | `sum(m)` | `sum(m)` | `sum(m(:))` |
| Mean all | `m.mean` | `m.mean()` | `mean(m)` | `mean(m)` | `mean(m(:))` |
| Min all | `m.min` | `m.min()` | `min(m)` | `min(m)` | `min(m(:))` |
| Max all | `m.max` | `m.max()` | `max(m)` | `max(m)` | `max(m(:))` |
| Std dev all | `m.std` | `m.std()` | `stddev(m)` | `sd(m)` | `std(m(:))` |
| Variance all | `m.variance` | `m.var()` | `variance(m)` | `var(c(m))` | `var(m(:))` |
| Median all | `m.median` | `np.median(m)` | `median(m)` | `median(m)` | `median(m(:))` |
| Per column | `m.sum(axis=0)` → 1×cols | `m.sum(axis=0)` | `sum(m, Axis._0)` | `colSums(m)` | `sum(m, 1)` |
| Per row | `m.sum(axis=1)` → rows×1 | `m.sum(axis=1)` | `sum(m, Axis._1)` | `rowSums(m)` | `sum(m, 2)` |

---

## Element-wise Math

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Absolute value | `m.map(_.abs)` | `np.abs(m)` | `abs(m)` | `abs(m)` | `abs(m)` |
| Square root | `m.map(math.sqrt)` | `np.sqrt(m)` | `sqrt(m)` | `sqrt(m)` | `sqrt(m)` |
| Exponential | `m.map(math.exp)` | `np.exp(m)` | `exp(m)` | `exp(m)` | `exp(m)` |
| Log | `m.map(math.log)` | `np.log(m)` | `log(m)` | `log(m)` | `log(m)` |
| Power (scalar) | `m ~^ p` | `m ** p` | `m ^:^ p` | `m ^ p` | `m .^ p` |
| Map arbitrary fn | `m.map(f)` | `np.vectorize(f)(m)` | `m.map(f)` | `apply(m, c(1,2), f)` | `arrayfun(f, m)` |

---

## Activation Functions

| Operation | MatD | NumPy / SciPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| ReLU | `m.relu` | `np.maximum(m, 0)` | `max(m, 0.0)` | `pmax(m, 0)` | `max(m, 0)` |
| Sigmoid | `m.sigmoid` | `scipy.special.expit(m)` | `sigmoid(m)` | `1/(1+exp(-m))` | `1./(1+exp(-m))` |
| Softmax | `m.softmax` | `scipy.special.softmax(m)` | `softmax(m)` | `exp(m)/sum(exp(m))` | `exp(m)./sum(exp(m))` |
| Leaky ReLU | `m.leakyRelu` | `np.where(m>0, m, 0.01*m)` | — | `ifelse(m>0, m, 0.01*m)` | `max(0.01*m, m)` |

---

## Boolean / Masking

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Element comparison | `m :== 0.0` → `Mat[Boolean]` | `m == 0` | `m :== 0.0` | `m == 0` | `m == 0` |
| Not equal | `m :!= 0.0` | `m != 0` | `m :!= 0.0` | `m != 0` | `m ~= 0` |
| Greater / less | `m > 2.0`, `m < 2.0` (or `gt`/`lt`) | `m > 2`, `m < 2` | `m >:> 2.0`, `m <:< 2.0` | `m > 2`, `m < 2` | `m > 2`, `m < 2` |
| Greater/less or equal | `m >= 2.0`, `m <= 2.0` (or `gte`/`lte`) | `m >= 2`, `m <= 2` | `m >:= 2.0`, `m <:= 2.0` | `m >= 2`, `m <= 2` | `m >= 2`, `m <= 2` |
| In range | `m.between(lo, hi)` | `(m >= lo) & (m <= hi)` | — | `m >= lo & m <= hi` | `m >= lo & m <= hi` |
| Combine masks | `a && b`, `a \|\| b` | `a & b`, `a \| b` | `a &:& b`, `a \|:\| b` | `a & b`, `a \| b` | `a & b`, `a \| b` |
| Negate mask | `!mask` | `~mask` | `!mask` | `!mask` | `~mask` |
| Count true | `mask.sum` | `mask.sum()` | `sum(mask)` | `sum(mask)` | `sum(mask(:))` |
| Any true | `mask.any` | `mask.any()` | `any(mask)` | `any(mask)` | `any(mask(:))` |
| All true | `mask.all` | `mask.all()` | `all(mask)` | `all(mask)` | `all(mask(:))` |
| Conditional select | `Mat.where(cond, x, y)` | `np.where(cond, x, y)` | `where(cond, x, y)` | `ifelse(cond, x, y)` | `cond.*x + ~cond.*y` |

---

## Stacking and Splitting

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Stack rows | `MatD.vstack(a, b)` | `np.vstack([a, b])` | `DenseMatrix.vertcat(a, b)` | `rbind(a, b)` | `[a; b]` |
| Stack cols | `MatD.hstack(a, b)` | `np.hstack([a, b])` | `DenseMatrix.horzcat(a, b)` | `cbind(a, b)` | `[a, b]` |
| Split rows | `m.vsplit(n)` | `np.vsplit(m, n)` | — | — | `mat2cell(m, repmat(r/n,1,n), c)` |
| Split cols | `m.hsplit(n)` | `np.hsplit(m, n)` | — | — | `mat2cell(m, r, repmat(c/n,1,n))` |

---

## Signal Processing (1-D Vectors)

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Polynomial fit | `MatD.polyfit(x, y, deg)` | `np.polyfit(x, y, deg)` | — | `lm(y ~ poly(x, deg))` | `polyfit(x, y, deg)` |
| Polynomial eval | `MatD.polyval(coeffs, x)` | `np.polyval(coeffs, x)` | — | `predict(fit, ...)` | `polyval(coeffs, x)` |
| Convolve | `MatD.convolve(a, b)` | `np.convolve(a, b)` | `convolve(a, b)` | `convolve(a, b)` | `conv(a, b)` |
| Correlate | `MatD.correlate(a, b)` | `np.correlate(a, b)` | — | `ccf(a, b)` | `xcorr(a, b)` |
| Meshgrid | `MatD.meshgrid(x, y)` | `np.meshgrid(x, y)` | — | `expand.grid(x, y)` | `meshgrid(x, y)` |

---

## Display and Formatting

| Operation | MatD | NumPy | Breeze | R | MATLAB |
|---|---|---|---|---|---|
| Print default | `println(m)` | `print(m)` | `println(m)` | `print(m)` | `disp(m)` |
| Explicit show | `m.show` | `str(m)` | `m.toString` | `print(m)` | `disp(m)` |
| Custom format | `m.show("%.2f")` | `np.set_printoptions(...)` | — | `format(m, digits=2)` | `format short` |
| Set thresholds | `Mat.setPrintOptions(maxRows=20, maxCols=20, edgeItems=5)` | `np.set_printoptions(threshold=...)` | — | `options(max.print=...)` | `format compact` |

> **`show` returns the rendering, it does not print it** — like NumPy's `str(m)`, and unlike
> `disp`. `m.show` alone on a line discards the result and outputs nothing; write
> `println(m.show)`.
>
> The two renderings also differ: `show` is compact (`(1, 2)`) where `toString` pads to a
> fixed width (`(1.00000, 2.00000)`). Use `show` for eyeballing, interpolation for a stable
> shape.
>
> **`println(m)` and the CR-free output convention conflict.** Scripts that shadow `println`
> to avoid carriage returns —
> `def println(s: String = ""): Unit = print(s"$s\n")` — give it a `String`-only signature,
> so `println(m)` no longer compiles for a matrix. Use `println(m.show)` or
> `println(s"$m")`; both reach the shadowed `String` overload.

---

## Pandas-Style Data Analysis

| Operation | MatD | pandas | Notes |
|---|---|---|---|
| First n rows | `m.head(n)` | `df.head(n)` | Returns `Mat[T]` |
| Last n rows | `m.tail(n)` | `df.tail(n)` | Returns `Mat[T]` |
| Index of min per axis | `m.idxmin(axis)` | `df.idxmin(axis)` | Returns `Mat[Int]` |
| Index of max per axis | `m.idxmax(axis)` | `df.idxmax(axis)` | Returns `Mat[Int]` |
| Cumulative max | `m.cummax(axis)` | `df.cummax(axis)` | axis=0 (rows) or 1 (cols) |
| Cumulative min | `m.cummin(axis)` | `df.cummin(axis)` | axis=0 (rows) or 1 (cols) |
| N largest values | `m.nlargest(n)` | `df.nlargest(n, col)` | Returns 1×n row vector |
| N smallest values | `m.nsmallest(n)` | `df.nsmallest(n, col)` | Returns 1×n row vector |
| Range test | `m.between(lo, hi)` | `s.between(lo, hi)` | Returns `Mat[Boolean]` |
| Unique value count | `m.nunique` | `df.nunique()` | `Int` |
| Frequency table | `m.valueCounts` | `s.value_counts()` | `Array[(T, Int)]`, descending |
| Shift / lag | `m.shift(n, fill)` | `df.shift(n)` | fill: sentinel for new cells |
| Shift columns | `m.shift(n, fill, axis=1)` | `df.shift(n, axis=1)` | |
| Percent change | `m.pct_change()` | `df.pct_change()` | First row is NaN |
| Fill NaN | `m.fillna(v)` | `df.fillna(v)` | Replaces NaN/BigNaN |
| Descriptive stats | `m.describe` | `df.describe()` | Returns `(Array[String], Mat[Double])` — 8 rows |
| Rolling mean | `m.rolling(w).mean` | `df.rolling(w).mean()` | First w-1 rows are NaN |
| Rolling sum | `m.rolling(w).sum` | `df.rolling(w).sum()` | |
| Rolling min | `m.rolling(w).min` | `df.rolling(w).min()` | |
| Rolling max | `m.rolling(w).max` | `df.rolling(w).max()` | |
| Rolling std dev | `m.rolling(w).std` | `df.rolling(w).std()` | |
| Column by name | `result("Col")` | `df["Col"]` | From `MatResult`; throws if absent |
| Column option | `result.col("Col")` | `df.get("Col")` | `Option[ColVec[T]]` |

### describe output layout

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.16.1

import uni.data.*

val m = MatD((1,2),(3,4),(5,6))
val (labels, stats) = m.describe
// labels = Array("count","mean","std","min","25%","50%","75%","max")
// stats  = 8 × m.cols  Mat[Double]
```

### MatResult — CSV with named columns

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.16.1

import uni.*
import uni.io.FileOps.*

val r = loadSmart(Paths.get("data.csv"))          // MatResult[Big]  (auto-detects header)
val r2 = loadSmart(Paths.get("data.csv"), _.toDouble) // MatResult[Double]

r("Close")          // ColVec[Big]   — throws NoSuchElementException if absent
r.col("Volume")     // Option[ColVec[Big]]
r.columnIndex       // Map[String, Int]  (pre-computed; free repeated lookups)
```

---

## Quick Reference Card

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.16.1

import uni.data.*

// Create
val a = MatD.zeros(3, 4)
val b = MatD.randn(3, 4)
val c = MatD.eye(3)

// Seed + random (100% NumPy-compatible)
MatD.setSeed(42)
val w = MatD.uniform(-0.1, 0.1, 64, 32)

// Slice (zero-indexed; m(...) forms copy, m.slice/.T are zero-copy views)
val row0 = b(0, ::)   // first row (copy)
val col0 = b(::, 0)   // first column (copy)
val bT   = b.T        // transpose (O(1) view)

// Arithmetic
val d = a + b         // element-wise
val e = a * b         // Hadamard product
val f = c *@ b        // matmul

// In-place
b :*= 0.5
b :+= a

// Activations
val g = f.sigmoid
val h = f.relu

// Reductions
val s = b.sum
val u = b.mean

// Boolean
val mask = (b :== 0.0) || (b :== 1.0)
val inv  = !mask
val hits = mask.sum

// Conditional
val r = Mat.where(mask, 1.0, 0.0)

// Stack / split
val tall = MatD.vstack(a, b)
val halves = tall.vsplit(2)   // Seq[MatD]

// Display
println(b.show("%.4f"))
Mat.setPrintOptions(maxRows = 20, maxCols = 20, edgeItems = 5)
```

---

## Type Aliases Summary

| Alias | Full Type | Factory Object | Notes |
|---|---|---|---|
| `MatD` | `Mat[Double]` | `MatD` | Default, IEEE 754 double |
| `MatF` | `Mat[Float]` | `MatF` | Half the memory of MatD |
| `MatB` | `Mat[Big]` | `MatB` | Arbitrary precision, NaN-safe |
| `CVecD` | `CVec[Double]` | `CVec` | Column vector (n×1); opaque type |
| `RVecD` | `RVec[Double]` | `RVec` | Row vector (1×n); opaque type |

`MatD`, `MatF`, and `MatB` share the same API; factory objects mirror `Mat` methods with appropriate types.
`CVec` / `RVec` provide type-safe `*@` dispatch: `X *@ y` returns `CVecD`, `y.T *@ X` returns `RVecD`, and `y *@ y` returns `Double`.

```scala
val y: CVecD = CVec(1.0, 2.0, 3.0)
val r: RVecD = RVec(4.0, 5.0, 6.0)
val z  = CVec.zeros[Double](5)
val o  = RVec.ones[Double](5)
val s: Double = (y *@ y)           // dot product = 14.0

// from an existing array (copies)
val arr = Array(1.0, 2.0, 3.0)
val c: CVecD = arr.toCVec          // or CVec(arr) / CVec.fromArray(arr)
val w: RVecD = arr.toRVec          // or RVec(arr) / RVec.fromArray(arr)
```
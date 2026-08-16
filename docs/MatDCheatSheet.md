# MatD Cheat Sheet

Side-by-side reference for **uni.MatD**, NumPy, Breeze, R, and MATLAB.

`MatD` = `Mat[Double]` — the standard double-precision matrix type in [uni.Mat](../README.md).

---

## Performance vs NumPy

One table per platform, one column set, apples to apples:

| NumPy | Scala | Rust | Scala·BLAS | Rust·BLAS |

NumPy multiplies through OpenBLAS — it has no other mode. `Scala` and `Rust` are the
default builds: the pinned pure matmul (bit-identical between the two ports and across
machines) and `matrixmultiply` in `t3prf`. `Scala·BLAS` / `Rust·BLAS` are the opt-in on
each side (`-Duni.mat.blas=true`; `--features blas`), and carry a number only on the rows
BLAS can change — `matmul` and the 3PRF rows — with `·` elsewhere: every other row is
identical code in both modes. MatD rows are min-of-60 single calls on a 1000×1000 (matmul
512³); 3PRF rows are medians of 25 (IS Full 200) after warm-up.

### Regenerating every table

```bash
./runBenchAll.sh          # builds both Rust flavours side by side, then runs uni.apps.BenchAll
```

`runBenchAll.sh` builds `bench_mat`/`bench_tprf3` twice — default, then `--features blas`
— keeps both as `*_pure` / `*_blas`, and runs [`BenchAll`](../src/main/scala/apps/BenchAll.scala),
which measures Scala in this JVM (the BLAS 3PRF cells in a child JVM started with
`-Duni.mat.blas=true`, since the mode is read once), runs [`py/bench.py`](../py/bench.py)
and [`py/bench_tprf3.py`](../py/bench_tprf3.py), and runs the four Rust binaries. A
missing binary drops its column rather than the run. The BLAS build needs an OpenBLAS the
`openblas-src` crate can find (`libopenblas-dev` / `brew install openblas` /
`pacman -S mingw-w64-ucrt-x86_64-openblas` + `OPENBLAS_LIB_DIR=/ucrt64/lib`). All three
halves keep the same row labels, sizes and input generator — the label is the join key.

**Keep the provenance lines when pasting a table in.** They say what each column is —
JVM, numpy and its BLAS, which Rust build, which BLAS on which platform — and without them
a reader cannot tell a property of the implementation from a property of the machine.

### Across the three boxes

`matmul` 512³, ms/call — the one row where the default is deliberately not the fastest
path, and the number the pure-versus-BLAS decision hangs on:

| box | NumPy | Scala | Rust | Scala·BLAS | Rust·BLAS |
|---|---:|---:|---:|---:|---:|
| Windows 11 | 0.7934 ms | 1.4540 ms | 1.7010 ms | 0.9093 ms | 0.8687 ms |
| Linux | 1.6485 ms | 14.4610 ms | 8.0967 ms | 6.1667 ms | 1.6836 ms |
| macOS | 2.1425 ms | 6.1667 ms | 2.4863 ms | 1.0511 ms | 2.1244 ms |

Geometric-mean speedup over all rows (BLAS pairs: over the 7 rows BLAS touches):

| box | Scala vs NumPy | Rust vs NumPy | Rust vs Scala | Scala·BLAS vs NumPy | Rust·BLAS vs NumPy |
|---|---:|---:|---:|---:|---:|
| Windows 11 | 3.13× | 3.38× | 1.16× | 2.79× | 7.41× |
| Linux | 1.20× | 1.93× | 1.74× | 2.39× | 9.37× |
| macOS | 1.26× | 2.06× | 1.78× | 2.56× | 5.76× |

Reading them together:

- **The pure default's cost is a function of core count; BLAS's is not.** Scala's pinned
  loop trails NumPy/OpenBLAS by 1.8× on 24 threads, 2.9× on Apple Silicon and 8.8× on
  four cores. Rust's trails by 2.1× / 1.2× / 4.9× — on aarch64 NEON is baseline, so LLVM
  vectorises the microkernel fully; on x86-64 the crate targets SSE2. The opt-in closes
  the gap on Windows and macOS in both languages, and for Rust on Linux — but **Scala·BLAS
  on Linux reads 6.2 ms against NumPy's 1.6**: since 0.16.1 `uni` uses bytedeco's bundled
  OpenBLAS there (never netlib, to stay clear of the LAPACKE-less system copy), and that
  bundled build is evidently not using the box's cores. Rust·BLAS links the *system*
  OpenBLAS and is level with NumPy. Open item: thread count of the bundled OpenBLAS.
- **Reductions and the axis family beat NumPy everywhere**, 2–3× on the few-core boxes
  and more here; the elementwise maps beat it only on the 24-thread box — on four cores
  or few big cores NumPy's SIMD wins 2–4×.
- **Rust vs Scala inverts by platform.** On Windows Rust trails Scala 1.2–1.9× on the
  elementwise maps (allocator and cache-residency behaviour on a 1000×1000 output); on
  Linux and macOS the same rows run 2–5× faster in Rust. Rust 3PRF is 3–16× ahead of
  NumPy on every box.
- `Rust·BLAS vs Rust` is ~1× on Linux and macOS: on those boxes `matrixmultiply` and the
  pure loop are already close to OpenBLAS for the shapes 3PRF uses, so the feature buys
  little there; it buys 1.4× on Windows.
- `vectorize` is `np.vectorize`, a Python loop, against a compiled parallel map of a user
  closure; it has no Rust row and is the one row that drags an arithmetic mean, which is
  why the summary is a geometric mean with the median beside it.

### Windows 11 (zx, 24 threads)

#### Every row, every language, both matmul modes — ms/call (lower is better)
| Operation | NumPy | Scala | Rust | Scala·BLAS | Rust·BLAS | Scala vs NumPy | Rust vs NumPy | Rust vs Scala |
|---|---:|---:|---:|---:|---:|---|---|---|
| `randn` | 7.2599 ms | 6.7224 ms | 4.7556 ms | · | · | **1.1× faster** | **1.5× faster** | **1.4× faster** |
| `matmul` | 0.7934 ms | 1.4540 ms | 1.7010 ms | 0.9093 ms | 0.8687 ms | **1.8× slower** | **2.1× slower** | **1.2× slower** |
| `sigmoid` | 8.7257 ms | 1.0315 ms | 1.0895 ms | · | · | **8.5× faster** | **8.0× faster** | **1.1× slower** |
| `relu` | 1.2247 ms | 0.5953 ms | 1.1183 ms | · | · | **2.1× faster** | **1.1× faster** | **1.9× slower** |
| `vectorize` | 65.1074 ms | 0.5413 ms | — | · | · | **120.3× faster** | — | — |
| `add` | 1.6954 ms | 0.6210 ms | 1.1362 ms | · | · | **2.7× faster** | **1.5× faster** | **1.8× slower** |
| `mul` | 1.7007 ms | 0.6521 ms | 1.1357 ms | · | · | **2.6× faster** | **1.5× faster** | **1.7× slower** |
| `abs` | 2.1698 ms | 0.9082 ms | 1.1104 ms | · | · | **2.4× faster** | **2.0× faster** | **1.2× slower** |
| `exp` | 2.6553 ms | 0.6990 ms | 1.1570 ms | · | · | **3.8× faster** | **2.3× faster** | **1.7× slower** |
| `log` | 3.0209 ms | 0.7143 ms | 1.1803 ms | · | · | **4.2× faster** | **2.6× faster** | **1.7× slower** |
| `sqrt` | 2.2041 ms | 0.5910 ms | 1.0886 ms | · | · | **3.7× faster** | **2.0× faster** | **1.8× slower** |
| `sum` | 0.1365 ms | 0.0322 ms | 0.0236 ms | · | · | **4.2× faster** | **5.8× faster** | **1.4× faster** |
| `mean` | 0.1373 ms | 0.0314 ms | 0.0239 ms | · | · | **4.4× faster** | **5.7× faster** | **1.3× faster** |
| `std` | 2.3917 ms | 0.4102 ms | 0.4307 ms | · | · | **5.8× faster** | **5.6× faster** | **1.0× slower** |
| `min` | 0.1074 ms | 0.0570 ms | 0.0503 ms | · | · | **1.9× faster** | **2.1× faster** | **1.1× faster** |
| `max` | 0.1074 ms | 0.0689 ms | 0.0427 ms | · | · | **1.6× faster** | **2.5× faster** | **1.6× faster** |
| `argmax` | 0.1329 ms | 0.0702 ms | 0.0440 ms | · | · | **1.9× faster** | **3.0× faster** | **1.6× faster** |
| `sum0` | 0.1081 ms | 0.0484 ms | 0.0370 ms | · | · | **2.2× faster** | **2.9× faster** | **1.3× faster** |
| `sum1` | 0.1351 ms | 0.0626 ms | 0.0636 ms | · | · | **2.2× faster** | **2.1× faster** | **1.0× slower** |
| `mean0` | 0.1105 ms | 0.0589 ms | 0.0425 ms | · | · | **1.9× faster** | **2.6× faster** | **1.4× faster** |
| `min0` | 0.1386 ms | 0.0888 ms | 0.0682 ms | · | · | **1.6× faster** | **2.0× faster** | **1.3× faster** |
| `max1` | 0.1285 ms | 0.0820 ms | 0.0441 ms | · | · | **1.6× faster** | **2.9× faster** | **1.9× faster** |
| `std0` | 2.2429 ms | 0.1226 ms | 0.1067 ms | · | · | **18.3× faster** | **21.0× faster** | **1.1× faster** |
| `cumsum` | 3.6831 ms | 0.7739 ms | 1.6406 ms | · | · | **4.8× faster** | **2.2× faster** | **2.1× slower** |
| `cumsum1` | 3.6540 ms | 0.7752 ms | 1.3976 ms | · | · | **4.7× faster** | **2.6× faster** | **1.8× slower** |
| `cummax0` | 4.9229 ms | 0.9386 ms | 1.5083 ms | · | · | **5.2× faster** | **3.3× faster** | **1.6× slower** |
| `cummin1` | 3.9281 ms | 0.9980 ms | 1.3960 ms | · | · | **3.9× faster** | **2.8× faster** | **1.4× slower** |
| `transpose` | 0.0000 ms | 0.0001 ms | 0.0000 ms | · | · | — | — | — |
| `sum@contig` | 0.1266 ms | 0.0440 ms | 0.0264 ms | · | · | **2.9× faster** | **4.8× faster** | **1.7× faster** |
| `max@contig` | 0.1029 ms | 0.0675 ms | 0.0402 ms | · | · | **1.5× faster** | **2.6× faster** | **1.7× faster** |
| `std@contig` | 2.3993 ms | 0.4107 ms | 0.4286 ms | · | · | **5.8× faster** | **5.6× faster** | **1.0× slower** |
| `sum0@contig` | 0.1095 ms | 0.0468 ms | 0.0370 ms | · | · | **2.3× faster** | **3.0× faster** | **1.3× faster** |
| `sum@transposed` | 0.1268 ms | 0.0631 ms | 0.0648 ms | · | · | **2.0× faster** | **2.0× faster** | **1.0× slower** |
| `max@transposed` | 0.1080 ms | 0.0872 ms | 0.0741 ms | · | · | **1.2× faster** | **1.5× faster** | **1.2× faster** |
| `std@transposed` | 2.3937 ms | 0.5038 ms | 0.5312 ms | · | · | **4.8× faster** | **4.5× faster** | **1.1× slower** |
| `sum0@transposed` | 0.1350 ms | 0.1226 ms | 0.0321 ms | · | · | **1.1× faster** | **4.2× faster** | **3.8× faster** |
| `sum@rowslice` | 0.1253 ms | 0.0347 ms | 0.0246 ms | · | · | **3.6× faster** | **5.1× faster** | **1.4× faster** |
| `max@rowslice` | 0.1024 ms | 0.0746 ms | 0.0373 ms | · | · | **1.4× faster** | **2.7× faster** | **2.0× faster** |
| `std@rowslice` | 1.9270 ms | 0.4129 ms | 0.4334 ms | · | · | **4.7× faster** | **4.4× faster** | **1.0× slower** |
| `sum0@rowslice` | 0.1083 ms | 0.0492 ms | 0.0394 ms | · | · | **2.2× faster** | **2.7× faster** | **1.2× faster** |
| `sum@bcast` | 0.1827 ms | 0.0299 ms | 0.0235 ms | · | · | **6.1× faster** | **7.8× faster** | **1.3× faster** |
| `max@bcast` | 0.1734 ms | 0.0740 ms | 0.0408 ms | · | · | **2.3× faster** | **4.3× faster** | **1.8× faster** |
| `std@bcast` | 2.4184 ms | 0.4033 ms | 0.4304 ms | · | · | **6.0× faster** | **5.6× faster** | **1.1× slower** |
| `sum0@bcast` | 0.0793 ms | 0.0339 ms | 0.0295 ms | · | · | **2.3× faster** | **2.7× faster** | **1.1× faster** |
| `3PRF IS Full (Small)` | 0.1200 ms | 0.0941 ms | 0.0140 ms | 0.0700 ms | 0.0140 ms | **1.3× faster** | **8.6× faster** | **6.7× faster** |
| `3PRF OOS Rec (Small)` | 4.5000 ms | 1.3181 ms | 0.3900 ms | 1.1300 ms | 0.5300 ms | **3.4× faster** | **11.5× faster** | **3.4× faster** |
| `3PRF OOS CV (Small)` | 8.2500 ms | 0.8497 ms | 1.0100 ms | 3.3700 ms | 0.8200 ms | **9.7× faster** | **8.2× faster** | **1.2× slower** |
| `3PRF IS Full (Large)` | 0.3700 ms | 0.4816 ms | 0.0500 ms | 0.2900 ms | 0.0510 ms | **1.3× slower** | **7.4× faster** | **9.6× faster** |
| `3PRF OOS Rec (Large)` | 21.3000 ms | 2.4303 ms | 3.9900 ms | 1.7100 ms | 1.4300 ms | **8.8× faster** | **5.3× faster** | **1.6× slower** |
| `3PRF OOS CV (Large)` | 37.6000 ms | 6.1981 ms | 4.9500 ms | 6.5700 ms | 2.2200 ms | **6.1× faster** | **7.6× faster** | **1.3× faster** |

- **NumPy**: Python 3.14.6 (openblas); OpenBLAS is NumPy's only mode
- **Scala**: jvm=23 N=1000 MM=512 warmup=16 iters=60; 3PRF medians of 25 (IS Full 200) after warm-up
- **Rust**: blas=off threads=unset N=1000 warmup=16 iters=60 (pure build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)
- **Scala·BLAS**: jvm=23, matmul via matmulBlas; 3PRF in a child JVM with -Duni.mat.blas=true (netlib/bundled OpenBLAS per platform)
- **Rust·BLAS**: blas=on threads=unset N=1000 warmup=16 iters=60 (blas build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)
- BLAS columns carry a number only on the rows BLAS can change (`matmul`, 3PRF); `·` elsewhere.

#### Summary — speedup of the first-named over the second (geometric mean; median; rows)
| pair | geomean | median | rows |
|---|---:|---:|---:|
| Scala vs NumPy | 3.13× | 2.73× | 49 |
| Rust vs NumPy | 3.38× | 2.92× | 48 |
| Rust vs Scala | 1.16× | 1.15× | 48 |
| Scala·BLAS vs NumPy | 2.79× | 2.45× | 7 |
| Rust·BLAS vs NumPy | 7.41× | 8.57× | 7 |
| Scala·BLAS vs Scala | 1.05× | 1.34× | 7 |
| Rust·BLAS vs Rust | 1.41× | 1.23× | 7 |

### Linux (quadd, Ubuntu 24.04, i5-6500, 4 cores)

#### Every row, every language, both matmul modes — ms/call (lower is better)
| Operation | NumPy | Scala | Rust | Scala·BLAS | Rust·BLAS | Scala vs NumPy | Rust vs NumPy | Rust vs Scala |
|---|---:|---:|---:|---:|---:|---|---|---|
| `randn` | 12.1839 ms | 20.0430 ms | 6.6503 ms | · | · | **1.6× slower** | **1.8× faster** | **3.0× faster** |
| `matmul` | 1.6485 ms | 14.4610 ms | 8.0967 ms | 6.1667 ms | 1.6836 ms | **8.8× slower** | **4.9× slower** | **1.8× faster** |
| `sigmoid` | 10.6323 ms | 5.7189 ms | 3.3627 ms | · | · | **1.9× faster** | **3.2× faster** | **1.7× faster** |
| `relu` | 0.8005 ms | 2.0909 ms | 0.6638 ms | · | · | **2.6× slower** | **1.2× faster** | **3.1× faster** |
| `vectorize` | 198.3078 ms | 1.0902 ms | — | · | · | **181.9× faster** | — | — |
| `add` | 0.9695 ms | 2.2790 ms | 0.9942 ms | · | · | **2.4× slower** | **1.0× slower** | **2.3× faster** |
| `mul` | 0.9801 ms | 2.8213 ms | 1.0027 ms | · | · | **2.9× slower** | **1.0× slower** | **2.8× faster** |
| `abs` | 0.7071 ms | 4.0826 ms | 0.6575 ms | · | · | **5.8× slower** | **1.1× faster** | **6.2× faster** |
| `exp` | 6.2354 ms | 4.2978 ms | 1.5978 ms | · | · | **1.5× faster** | **3.9× faster** | **2.7× faster** |
| `log` | 6.1125 ms | 4.4921 ms | 1.6853 ms | · | · | **1.4× faster** | **3.6× faster** | **2.7× faster** |
| `sqrt` | 0.9066 ms | 2.1845 ms | 0.6588 ms | · | · | **2.4× slower** | **1.4× faster** | **3.3× faster** |
| `sum` | 0.3475 ms | 0.1667 ms | 0.1522 ms | · | · | **2.1× faster** | **2.3× faster** | **1.1× faster** |
| `mean` | 0.3523 ms | 0.1921 ms | 0.1544 ms | · | · | **1.8× faster** | **2.3× faster** | **1.2× faster** |
| `std` | 1.8904 ms | 1.5717 ms | 1.5354 ms | · | · | **1.2× faster** | **1.2× faster** | **1.0× faster** |
| `min` | 0.3104 ms | 0.2502 ms | 0.2801 ms | · | · | **1.2× faster** | **1.1× faster** | **1.1× slower** |
| `max` | 0.3123 ms | 0.3931 ms | 0.2044 ms | · | · | **1.3× slower** | **1.5× faster** | **1.9× faster** |
| `argmax` | 0.4490 ms | 0.4347 ms | 0.2050 ms | · | · | **1.0× faster** | **2.2× faster** | **2.1× faster** |
| `sum0` | 0.3301 ms | 0.3441 ms | 0.3258 ms | · | · | **1.0× slower** | **1.0× faster** | **1.1× faster** |
| `sum1` | 0.3646 ms | 0.3447 ms | 0.3259 ms | · | · | **1.1× faster** | **1.1× faster** | **1.1× faster** |
| `mean0` | 0.3430 ms | 0.4535 ms | 0.3284 ms | · | · | **1.3× slower** | **1.0× faster** | **1.4× faster** |
| `min0` | 0.4270 ms | 0.5862 ms | 0.4856 ms | · | · | **1.4× slower** | **1.1× slower** | **1.2× faster** |
| `max1` | 0.3736 ms | 0.3618 ms | 0.2184 ms | · | · | **1.0× faster** | **1.7× faster** | **1.7× faster** |
| `std0` | 2.0519 ms | 0.8417 ms | 0.8046 ms | · | · | **2.4× faster** | **2.6× faster** | **1.0× faster** |
| `cumsum` | 2.5439 ms | 1.5325 ms | 2.6004 ms | · | · | **1.7× faster** | **1.0× slower** | **1.7× slower** |
| `cumsum1` | 2.5538 ms | 1.5303 ms | 1.5120 ms | · | · | **1.7× faster** | **1.7× faster** | **1.0× faster** |
| `cummax0` | 4.1347 ms | 2.0051 ms | 1.7816 ms | · | · | **2.1× faster** | **2.3× faster** | **1.1× faster** |
| `cummin1` | 3.3751 ms | 1.9277 ms | 1.6008 ms | · | · | **1.8× faster** | **2.1× faster** | **1.2× faster** |
| `transpose` | 0.0002 ms | 0.0005 ms | 0.0000 ms | · | · | **2.3× slower** | — | — |
| `sum@contig` | 0.3483 ms | 0.1639 ms | 0.1500 ms | · | · | **2.1× faster** | **2.3× faster** | **1.1× faster** |
| `max@contig` | 0.3099 ms | 0.4001 ms | 0.2227 ms | · | · | **1.3× slower** | **1.4× faster** | **1.8× faster** |
| `std@contig` | 1.8874 ms | 1.5346 ms | 1.5037 ms | · | · | **1.2× faster** | **1.3× faster** | **1.0× faster** |
| `sum0@contig` | 0.3327 ms | 0.3484 ms | 0.3322 ms | · | · | **1.0× slower** | **1.0× faster** | **1.0× faster** |
| `sum@transposed` | 0.3481 ms | 0.4306 ms | 0.3983 ms | · | · | **1.2× slower** | **1.1× slower** | **1.1× faster** |
| `max@transposed` | 0.3140 ms | 0.5480 ms | 0.4428 ms | · | · | **1.7× slower** | **1.4× slower** | **1.2× faster** |
| `std@transposed` | 1.8972 ms | 2.3289 ms | 2.2136 ms | · | · | **1.2× slower** | **1.2× slower** | **1.1× faster** |
| `sum0@transposed` | 0.3628 ms | 0.4167 ms | 0.1761 ms | · | · | **1.1× slower** | **2.1× faster** | **2.4× faster** |
| `sum@rowslice` | 0.3414 ms | 0.1909 ms | 0.1752 ms | · | · | **1.8× faster** | **1.9× faster** | **1.1× faster** |
| `max@rowslice` | 0.3173 ms | 0.3177 ms | 0.2059 ms | · | · | **1.0× slower** | **1.5× faster** | **1.5× faster** |
| `std@rowslice` | 1.8635 ms | 1.5406 ms | 1.5292 ms | · | · | **1.2× faster** | **1.2× faster** | **1.0× faster** |
| `sum0@rowslice` | 0.3330 ms | 0.3875 ms | 0.3225 ms | · | · | **1.2× slower** | **1.0× faster** | **1.2× faster** |
| `sum@bcast` | 0.3715 ms | 0.0772 ms | 0.1338 ms | · | · | **4.8× faster** | **2.8× faster** | **1.7× slower** |
| `max@bcast` | 0.2833 ms | 0.2967 ms | 0.1658 ms | · | · | **1.0× slower** | **1.7× faster** | **1.8× faster** |
| `std@bcast` | 1.6164 ms | 1.3086 ms | 1.3630 ms | · | · | **1.2× faster** | **1.2× faster** | **1.0× slower** |
| `sum0@bcast` | 0.1541 ms | 0.1674 ms | 0.1502 ms | · | · | **1.1× slower** | **1.0× faster** | **1.1× faster** |
| `3PRF IS Full (Small)` | 0.4600 ms | 0.2295 ms | 0.0370 ms | 0.2100 ms | 0.0440 ms | **2.0× faster** | **12.4× faster** | **6.2× faster** |
| `3PRF OOS Rec (Small)` | 15.1700 ms | 3.9683 ms | 0.5100 ms | 5.8000 ms | 0.6900 ms | **3.8× faster** | **29.7× faster** | **7.8× faster** |
| `3PRF OOS CV (Small)` | 28.1900 ms | 5.6407 ms | 0.7800 ms | 4.6800 ms | 1.0800 ms | **5.0× faster** | **36.1× faster** | **7.2× faster** |
| `3PRF IS Full (Large)` | 0.9700 ms | 1.4014 ms | 0.1340 ms | 0.5100 ms | 0.1520 ms | **1.4× slower** | **7.2× faster** | **10.5× faster** |
| `3PRF OOS Rec (Large)` | 64.6100 ms | 16.7619 ms | 4.8500 ms | 11.0000 ms | 5.3900 ms | **3.9× faster** | **13.3× faster** | **3.5× faster** |
| `3PRF OOS CV (Large)` | 116.7300 ms | 35.9208 ms | 7.3800 ms | 27.1200 ms | 8.2900 ms | **3.2× faster** | **15.8× faster** | **4.9× faster** |

- **NumPy**: Python 3.12.3 (blas); OpenBLAS is NumPy's only mode
- **Scala**: jvm=17.0.19 N=1000 MM=512 warmup=16 iters=60; 3PRF medians of 25 (IS Full 200) after warm-up
- **Rust**: blas=off threads=unset N=1000 warmup=16 iters=60 (pure build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)
- **Scala·BLAS**: jvm=17.0.19, matmul via matmulBlas; 3PRF in a child JVM with -Duni.mat.blas=true (netlib/bundled OpenBLAS per platform)
- **Rust·BLAS**: blas=on threads=unset N=1000 warmup=16 iters=60 (blas build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)
- BLAS columns carry a number only on the rows BLAS can change (`matmul`, 3PRF); `·` elsewhere.

#### Summary — speedup of the first-named over the second (geometric mean; median; rows)
| pair | geomean | median | rows |
|---|---:|---:|---:|
| Scala vs NumPy | 1.20× | 1.05× | 50 |
| Rust vs NumPy | 1.93× | 1.53× | 48 |
| Rust vs Scala | 1.74× | 1.31× | 48 |
| Scala·BLAS vs NumPy | 2.39× | 2.62× | 7 |
| Rust·BLAS vs NumPy | 9.37× | 11.99× | 7 |
| Scala·BLAS vs Scala | 1.42× | 1.32× | 7 |
| Rust·BLAS vs Rust | 1.06× | 0.88× | 7 |

### macOS (suemac, Apple Silicon)

#### Every row, every language, both matmul modes — ms/call (lower is better)
| Operation | NumPy | Scala | Rust | Scala·BLAS | Rust·BLAS | Scala vs NumPy | Rust vs NumPy | Rust vs Scala |
|---|---:|---:|---:|---:|---:|---|---|---|
| `randn` | 4.9320 ms | 3.4183 ms | 2.3916 ms | · | · | **1.4× faster** | **2.1× faster** | **1.4× faster** |
| `matmul` | 2.1425 ms | 6.1667 ms | 2.4863 ms | 1.0511 ms | 2.1244 ms | **2.9× slower** | **1.2× slower** | **2.5× faster** |
| `sigmoid` | 6.5291 ms | 4.0161 ms | 1.3132 ms | · | · | **1.6× faster** | **5.0× faster** | **3.1× faster** |
| `relu` | 0.3747 ms | 0.8348 ms | 0.1758 ms | · | · | **2.2× slower** | **2.1× faster** | **4.7× faster** |
| `vectorize` | 113.4128 ms | 0.4779 ms | — | · | · | **237.3× faster** | — | — |
| `add` | 0.3320 ms | 1.2145 ms | 0.2747 ms | · | · | **3.7× slower** | **1.2× faster** | **4.4× faster** |
| `mul` | 0.3299 ms | 0.9953 ms | 0.2847 ms | · | · | **3.0× slower** | **1.2× faster** | **3.5× faster** |
| `abs` | 0.1812 ms | 1.0308 ms | 0.1718 ms | · | · | **5.7× slower** | **1.1× faster** | **6.0× faster** |
| `exp` | 5.9350 ms | 3.2204 ms | 1.1240 ms | · | · | **1.8× faster** | **5.3× faster** | **2.9× faster** |
| `log` | 5.2520 ms | 2.7173 ms | 1.0175 ms | · | · | **1.9× faster** | **5.2× faster** | **2.7× faster** |
| `sqrt` | 0.3203 ms | 1.0996 ms | 0.1746 ms | · | · | **3.4× slower** | **1.8× faster** | **6.3× faster** |
| `sum` | 0.1868 ms | 0.0764 ms | 0.0617 ms | · | · | **2.4× faster** | **3.0× faster** | **1.2× faster** |
| `mean` | 0.1884 ms | 0.0676 ms | 0.0596 ms | · | · | **2.8× faster** | **3.2× faster** | **1.1× faster** |
| `std` | 0.7145 ms | 1.0567 ms | 1.0682 ms | · | · | **1.5× slower** | **1.5× slower** | **1.0× slower** |
| `min` | 0.1066 ms | 0.1754 ms | 0.1538 ms | · | · | **1.6× slower** | **1.4× slower** | **1.1× faster** |
| `max` | 0.1067 ms | 0.2277 ms | 0.1101 ms | · | · | **2.1× slower** | **1.0× slower** | **2.1× faster** |
| `argmax` | 0.6871 ms | 0.2330 ms | 0.1092 ms | · | · | **2.9× faster** | **6.3× faster** | **2.1× faster** |
| `sum0` | 0.1690 ms | 0.1304 ms | 0.1197 ms | · | · | **1.3× faster** | **1.4× faster** | **1.1× faster** |
| `sum1` | 0.1910 ms | 0.2076 ms | 0.1963 ms | · | · | **1.1× slower** | **1.0× slower** | **1.1× faster** |
| `mean0` | 0.1705 ms | 0.1373 ms | 0.1242 ms | · | · | **1.2× faster** | **1.4× faster** | **1.1× faster** |
| `min0` | 0.1815 ms | 0.2041 ms | 0.1885 ms | · | · | **1.1× slower** | **1.0× slower** | **1.1× faster** |
| `max1` | 0.1179 ms | 0.2060 ms | 0.1352 ms | · | · | **1.7× slower** | **1.1× slower** | **1.5× faster** |
| `std0` | 0.8460 ms | 0.3036 ms | 0.3118 ms | · | · | **2.8× faster** | **2.7× faster** | **1.0× slower** |
| `cumsum` | 3.4520 ms | 1.0944 ms | 1.0050 ms | · | · | **3.2× faster** | **3.4× faster** | **1.1× faster** |
| `cumsum1` | 3.4985 ms | 1.1719 ms | 0.9996 ms | · | · | **3.0× faster** | **3.5× faster** | **1.2× faster** |
| `cummax0` | 3.5204 ms | 1.3195 ms | 0.8555 ms | · | · | **2.7× faster** | **4.1× faster** | **1.5× faster** |
| `cummin1` | 3.4728 ms | 1.3759 ms | 1.0815 ms | · | · | **2.5× faster** | **3.2× faster** | **1.3× faster** |
| `transpose` | 0.0001 ms | 0.0003 ms | 0.0000 ms | · | · | **2.9× slower** | — | — |
| `sum@contig` | 0.1898 ms | 0.0652 ms | 0.0578 ms | · | · | **2.9× faster** | **3.3× faster** | **1.1× faster** |
| `max@contig` | 0.1067 ms | 0.2245 ms | 0.1117 ms | · | · | **2.1× slower** | **1.0× slower** | **2.0× faster** |
| `std@contig` | 0.7149 ms | 1.0593 ms | 1.0653 ms | · | · | **1.5× slower** | **1.5× slower** | **1.0× slower** |
| `sum0@contig` | 0.1690 ms | 0.1292 ms | 0.1180 ms | · | · | **1.3× faster** | **1.4× faster** | **1.1× faster** |
| `sum@transposed` | 0.1868 ms | 0.1310 ms | 0.0908 ms | · | · | **1.4× faster** | **2.1× faster** | **1.4× faster** |
| `max@transposed` | 0.1066 ms | 0.2975 ms | 0.1227 ms | · | · | **2.8× slower** | **1.2× slower** | **2.4× faster** |
| `std@transposed` | 0.7145 ms | 1.1203 ms | 1.1322 ms | · | · | **1.6× slower** | **1.6× slower** | **1.0× slower** |
| `sum0@transposed` | 0.1910 ms | 0.1538 ms | 0.0848 ms | · | · | **1.2× faster** | **2.3× faster** | **1.8× faster** |
| `sum@rowslice` | 0.1866 ms | 0.0656 ms | 0.0682 ms | · | · | **2.8× faster** | **2.7× faster** | **1.0× slower** |
| `max@rowslice` | 0.1067 ms | 0.2181 ms | 0.1100 ms | · | · | **2.0× slower** | **1.0× slower** | **2.0× faster** |
| `std@rowslice` | 0.7137 ms | 1.0469 ms | 1.0907 ms | · | · | **1.5× slower** | **1.5× slower** | **1.0× slower** |
| `sum0@rowslice` | 0.1688 ms | 0.1160 ms | 0.0990 ms | · | · | **1.5× faster** | **1.7× faster** | **1.2× faster** |
| `sum@bcast` | 0.2682 ms | 0.0453 ms | 0.0764 ms | · | · | **5.9× faster** | **3.5× faster** | **1.7× slower** |
| `max@bcast` | 0.1622 ms | 0.2175 ms | 0.1089 ms | · | · | **1.3× slower** | **1.5× faster** | **2.0× faster** |
| `std@bcast` | 0.8203 ms | 1.0337 ms | 1.0588 ms | · | · | **1.3× slower** | **1.3× slower** | **1.0× slower** |
| `sum0@bcast` | 0.1363 ms | 0.1056 ms | 0.0776 ms | · | · | **1.3× faster** | **1.8× faster** | **1.4× faster** |
| `3PRF IS Full (Small)` | 0.1500 ms | 0.2024 ms | 0.0750 ms | 0.1200 ms | 0.0640 ms | **1.3× slower** | **2.0× faster** | **2.7× faster** |
| `3PRF OOS Rec (Small)` | 5.5100 ms | 1.3282 ms | 0.5800 ms | 3.3200 ms | 0.5500 ms | **4.1× faster** | **9.5× faster** | **2.3× faster** |
| `3PRF OOS CV (Small)` | 10.1100 ms | 2.3355 ms | 0.6000 ms | 2.5200 ms | 0.6300 ms | **4.3× faster** | **16.9× faster** | **3.9× faster** |
| `3PRF IS Full (Large)` | 0.3400 ms | 0.4530 ms | 0.0700 ms | 0.1800 ms | 0.0780 ms | **1.3× slower** | **4.9× faster** | **6.5× faster** |
| `3PRF OOS Rec (Large)` | 26.7600 ms | 7.1217 ms | 2.1700 ms | 5.2200 ms | 2.3700 ms | **3.8× faster** | **12.3× faster** | **3.3× faster** |
| `3PRF OOS CV (Large)` | 48.4200 ms | 15.2292 ms | 3.3300 ms | 10.9200 ms | 4.2800 ms | **3.2× faster** | **14.5× faster** | **4.6× faster** |

- **NumPy**: Python 3.14.6 (openblas); OpenBLAS is NumPy's only mode
- **Scala**: jvm=25.0.2 N=1000 MM=512 warmup=16 iters=60; 3PRF medians of 25 (IS Full 200) after warm-up
- **Rust**: blas=off threads=unset N=1000 warmup=16 iters=60 (pure build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)
- **Scala·BLAS**: jvm=25.0.2, matmul via matmulBlas; 3PRF in a child JVM with -Duni.mat.blas=true (netlib/bundled OpenBLAS per platform)
- **Rust·BLAS**: blas=on threads=unset N=1000 warmup=16 iters=60 (blas build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)
- BLAS columns carry a number only on the rows BLAS can change (`matmul`, 3PRF); `·` elsewhere.

#### Summary — speedup of the first-named over the second (geometric mean; median; rows)
| pair | geomean | median | rows |
|---|---:|---:|---:|
| Scala vs NumPy | 1.26× | 1.27× | 50 |
| Rust vs NumPy | 2.06× | 1.92× | 48 |
| Rust vs Scala | 1.78× | 1.48× | 48 |
| Scala·BLAS vs NumPy | 2.56× | 2.04× | 7 |
| Rust·BLAS vs NumPy | 5.76× | 10.02× | 7 |
| Scala·BLAS vs Scala | 1.51× | 1.39× | 7 |
| Rust·BLAS vs Rust | 0.98× | 0.95× | 7 |

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
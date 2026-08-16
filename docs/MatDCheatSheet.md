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

### Windows 11, 24 threads (this run)

#### Every row, every language, both matmul modes — ms/call (lower is better)
| Operation | NumPy | Scala | Rust | Scala·BLAS | Rust·BLAS | Scala vs NumPy | Rust vs NumPy | Rust vs Scala |
|---|---:|---:|---:|---:|---:|---|---|---|
| `randn` | 7.3180 ms | 6.7371 ms | 4.8011 ms | · | · | **1.1× faster** | **1.5× faster** | **1.4× faster** |
| `matmul` | 0.7775 ms | 1.5072 ms | 1.6929 ms | 0.7853 ms | 0.8931 ms | **1.9× slower** | **2.2× slower** | **1.1× slower** |
| `sigmoid` | 8.8499 ms | 1.0678 ms | 1.1409 ms | · | · | **8.3× faster** | **7.8× faster** | **1.1× slower** |
| `relu` | 1.2659 ms | 0.6048 ms | 1.1155 ms | · | · | **2.1× faster** | **1.1× faster** | **1.8× slower** |
| `vectorize` | 66.5569 ms | 0.5766 ms | — | · | · | **115.4× faster** | — | — |
| `add` | 1.7176 ms | 0.6326 ms | 1.1375 ms | · | · | **2.7× faster** | **1.5× faster** | **1.8× slower** |
| `mul` | 1.7219 ms | 0.6246 ms | 1.1829 ms | · | · | **2.8× faster** | **1.5× faster** | **1.9× slower** |
| `abs` | 2.2080 ms | 0.9134 ms | 1.1062 ms | · | · | **2.4× faster** | **2.0× faster** | **1.2× slower** |
| `exp` | 2.6807 ms | 0.7006 ms | 1.1529 ms | · | · | **3.8× faster** | **2.3× faster** | **1.6× slower** |
| `log` | 3.0509 ms | 0.7274 ms | 1.0989 ms | · | · | **4.2× faster** | **2.8× faster** | **1.5× slower** |
| `sqrt` | 2.2276 ms | 0.5923 ms | 1.1128 ms | · | · | **3.8× faster** | **2.0× faster** | **1.9× slower** |
| `sum` | 0.1361 ms | 0.0309 ms | 0.0252 ms | · | · | **4.4× faster** | **5.4× faster** | **1.2× faster** |
| `mean` | 0.1329 ms | 0.0266 ms | 0.0263 ms | · | · | **5.0× faster** | **5.1× faster** | **1.0× faster** |
| `std` | 2.4152 ms | 0.4130 ms | 0.4347 ms | · | · | **5.8× faster** | **5.6× faster** | **1.1× slower** |
| `min` | 0.1025 ms | 0.0574 ms | 0.0577 ms | · | · | **1.8× faster** | **1.8× faster** | **1.0× slower** |
| `max` | 0.1035 ms | 0.0631 ms | 0.0456 ms | · | · | **1.6× faster** | **2.3× faster** | **1.4× faster** |
| `argmax` | 0.1254 ms | 0.0695 ms | 0.0490 ms | · | · | **1.8× faster** | **2.6× faster** | **1.4× faster** |
| `sum0` | 0.1148 ms | 0.0486 ms | 0.0432 ms | · | · | **2.4× faster** | **2.7× faster** | **1.1× faster** |
| `sum1` | 0.1359 ms | 0.0631 ms | 0.0664 ms | · | · | **2.2× faster** | **2.0× faster** | **1.1× slower** |
| `mean0` | 0.1170 ms | 0.0583 ms | 0.0481 ms | · | · | **2.0× faster** | **2.4× faster** | **1.2× faster** |
| `min0` | 0.1413 ms | 0.0892 ms | 0.0755 ms | · | · | **1.6× faster** | **1.9× faster** | **1.2× faster** |
| `max1` | 0.1339 ms | 0.0838 ms | 0.0503 ms | · | · | **1.6× faster** | **2.7× faster** | **1.7× faster** |
| `std0` | 2.2396 ms | 0.1253 ms | 0.1118 ms | · | · | **17.9× faster** | **20.0× faster** | **1.1× faster** |
| `cumsum` | 3.7156 ms | 0.7723 ms | 1.6340 ms | · | · | **4.8× faster** | **2.3× faster** | **2.1× slower** |
| `cumsum1` | 3.6664 ms | 0.7788 ms | 1.4299 ms | · | · | **4.7× faster** | **2.6× faster** | **1.8× slower** |
| `cummax0` | 4.9997 ms | 0.9628 ms | 1.5262 ms | · | · | **5.2× faster** | **3.3× faster** | **1.6× slower** |
| `cummin1` | 3.9617 ms | 1.0199 ms | 1.3771 ms | · | · | **3.9× faster** | **2.9× faster** | **1.4× slower** |
| `transpose` | 0.0000 ms | 0.0001 ms | 0.0000 ms | · | · | — | — | — |
| `sum@contig` | 0.1271 ms | 0.0350 ms | 0.0253 ms | · | · | **3.6× faster** | **5.0× faster** | **1.4× faster** |
| `max@contig` | 0.1033 ms | 0.0699 ms | 0.0432 ms | · | · | **1.5× faster** | **2.4× faster** | **1.6× faster** |
| `std@contig` | 2.4330 ms | 0.4152 ms | 0.4411 ms | · | · | **5.9× faster** | **5.5× faster** | **1.1× slower** |
| `sum0@contig` | 0.1132 ms | 0.0479 ms | 0.0434 ms | · | · | **2.4× faster** | **2.6× faster** | **1.1× faster** |
| `sum@transposed` | 0.1260 ms | 0.0633 ms | 0.0664 ms | · | · | **2.0× faster** | **1.9× faster** | **1.0× slower** |
| `max@transposed` | 0.1031 ms | 0.0889 ms | 0.0792 ms | · | · | **1.2× faster** | **1.3× faster** | **1.1× faster** |
| `std@transposed` | 2.4380 ms | 0.4926 ms | 0.5370 ms | · | · | **4.9× faster** | **4.5× faster** | **1.1× slower** |
| `sum0@transposed` | 0.1348 ms | 0.1258 ms | 0.0320 ms | · | · | **1.1× faster** | **4.2× faster** | **3.9× faster** |
| `sum@rowslice` | 0.1250 ms | 0.0391 ms | 0.0251 ms | · | · | **3.2× faster** | **5.0× faster** | **1.6× faster** |
| `max@rowslice` | 0.1027 ms | 0.0673 ms | 0.0467 ms | · | · | **1.5× faster** | **2.2× faster** | **1.4× faster** |
| `std@rowslice` | 1.9223 ms | 0.4126 ms | 0.4357 ms | · | · | **4.7× faster** | **4.4× faster** | **1.1× slower** |
| `sum0@rowslice` | 0.1126 ms | 0.0518 ms | 0.0398 ms | · | · | **2.2× faster** | **2.8× faster** | **1.3× faster** |
| `sum@bcast` | 0.1838 ms | 0.0287 ms | 0.0229 ms | · | · | **6.4× faster** | **8.0× faster** | **1.3× faster** |
| `max@bcast` | 0.1738 ms | 0.0661 ms | 0.0457 ms | · | · | **2.6× faster** | **3.8× faster** | **1.4× faster** |
| `std@bcast` | 2.4007 ms | 0.4041 ms | 0.4318 ms | · | · | **5.9× faster** | **5.6× faster** | **1.1× slower** |
| `sum0@bcast` | 0.0839 ms | 0.0382 ms | 0.0282 ms | · | · | **2.2× faster** | **3.0× faster** | **1.4× faster** |
| `3PRF IS Full (Small)` | 0.1300 ms | 0.0971 ms | 0.0140 ms | 0.0700 ms | 0.0130 ms | **1.3× faster** | **9.3× faster** | **6.9× faster** |
| `3PRF OOS Rec (Small)` | 4.5300 ms | 1.3467 ms | 0.4000 ms | 1.2100 ms | 0.2300 ms | **3.4× faster** | **11.3× faster** | **3.4× faster** |
| `3PRF OOS CV (Small)` | 8.2200 ms | 0.8658 ms | 0.9400 ms | 1.3200 ms | 0.2400 ms | **9.5× faster** | **8.7× faster** | **1.1× slower** |
| `3PRF IS Full (Large)` | 0.3600 ms | 0.5035 ms | 0.0530 ms | 0.2000 ms | 0.0490 ms | **1.4× slower** | **6.8× faster** | **9.5× faster** |
| `3PRF OOS Rec (Large)` | 21.9100 ms | 2.8518 ms | 3.7100 ms | 1.6100 ms | 0.8100 ms | **7.7× faster** | **5.9× faster** | **1.3× slower** |
| `3PRF OOS CV (Large)` | 38.2300 ms | 6.4459 ms | 4.5600 ms | 6.6600 ms | 1.0800 ms | **5.9× faster** | **8.4× faster** | **1.4× faster** |

- **NumPy**: Python 3.14.6 (openblas); OpenBLAS is NumPy's only mode
- **Scala**: jvm=23 N=1000 MM=512 warmup=16 iters=60; 3PRF medians of 25 (IS Full 200) after warm-up
- **Rust**: blas=off threads=unset N=1000 warmup=16 iters=60 (pure build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)
- **Scala·BLAS**: jvm=23, matmul via matmulBlas; 3PRF in a child JVM with -Duni.mat.blas=true (netlib/bundled OpenBLAS per platform)
- **Rust·BLAS**: blas=on threads=unset N=1000 warmup=16 iters=60 (blas build; 3PRF rows with OPENBLAS_NUM_THREADS=1, see BenchAll)
- BLAS columns carry a number only on the rows BLAS can change (`matmul`, 3PRF); `·` elsewhere.

#### Summary — speedup of the first-named over the second (geometric mean; median; rows)
| pair | geomean | median | rows |
|---|---:|---:|---:|
| Scala vs NumPy | 3.15× | 2.76× | 49 |
| Rust vs NumPy | 3.29× | 2.80× | 48 |
| Rust vs Scala | 1.13× | 1.06× | 48 |
| Scala·BLAS vs NumPy | 3.47× | 3.74× | 7 |
| Rust·BLAS vs NumPy | 12.25× | 19.70× | 7 |
| Scala·BLAS vs Scala | 1.36× | 1.39× | 7 |
| Rust·BLAS vs Rust | 2.25× | 1.90× | 7 |

A speedup above 1× means the first-named is faster. Geometric mean is the fair aggregate for
ratios; the median beside it shows whether one row is carrying the mean. BLAS lines cover
only the rows BLAS touches.

Reading it: both ports beat NumPy on every `Mat` operation with a Rust column except
`matmul` — the one row where the DEFAULT is deliberately not the fastest path — and there
the BLAS opt-in is level with NumPy on both sides. `vectorize` is `np.vectorize`, a
Python loop, against a compiled parallel map of a user closure; it has no Rust row. Rust
trails Scala by 1.2–1.9× on the elementwise maps and `cumsum` on this box only —
allocator and cache-residency behaviour on a 1000×1000 output; on Linux and macOS the
same rows run 2–5× faster in Rust than in Scala (see below).

### Linux (quadd, i5-6500, 4 cores) and macOS (suemac, Apple Silicon)

Pending a `runBenchAll.sh` run on each box in this format. From the previous format
(same commit, no BLAS columns): the pure-default `matmul` at 512³ is 9.0× behind
NumPy/OpenBLAS in Scala on the four-core Linux box and 2.9× on Apple Silicon (Rust: 5.0×
and 1.2×; on aarch64 NEON is baseline, so LLVM vectorises the microkernel fully); the
reduction rows are 1.6–3.3× ahead of NumPy on both, the elementwise rows 2–4× behind on
few big cores, 3PRF OOS 3.1–4.2× ahead. The pure loop's cost scales with core count;
BLAS's does not.

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
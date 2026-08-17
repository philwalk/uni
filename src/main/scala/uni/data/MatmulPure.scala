package uni.data

/**
 * The pinned matmul kernel behind `Mat.multiplyDouble`: `matmulPure`, `*@` under
 * `-Duni.mat.blas=pure`, and every product below the BLAS threshold.
 *
 * # The contract it must keep
 *
 * Every output cell is a sequential k-sum from 0.0 of rounded products,
 * `((0 + a(i,0)*b(0,j)) + a(i,1)*b(1,j)) + …`, with no fused multiply-add. That is what
 * the Rust port reproduces bit for bit and what makes the result independent of the
 * machine, and it is pinned by `test-data/mat-parity/` (`mmfnv`/`tmmfnv`) and by
 * `MatmulModeSuite`. Everything in this file is scheduling around that invariant:
 * which cells share a loaded value, when a partial result touches memory, how work is
 * split across threads. None of it changes the per-cell k-order.
 *
 * # The two schedules
 *
 *  - **Register microkernel** (`micro44`), the main path: a 4×4 block of cells is
 *    accumulated in 16 locals over the whole k range and stored once. Sixteen
 *    independent chains hide the add latency without SIMD, and `C` is never
 *    read-modify-written inside the k loop. `B` is packed 4-wide panel-major first —
 *    the last panel zero-padded, so a 3-column output is one panel with a partial
 *    store — and a 4-row group of `A` is packed k-major when its k-stride is not 1, so
 *    the k loop reads both operands from contiguous buffers whatever their layouts.
 *    Packing copies values and does no arithmetic. Rows past the last 4-row group take
 *    `panelRows`, the same loop with one row's four accumulators.
 *  - **Streaming saxpy** (`saxpyRows`) for short K (< [[MinKForMicro]]) and for
 *    outputs narrower than [[MinColsForMicro]]: with only a handful of k steps the
 *    microkernel cannot amortise its accumulators (512×8×512: streaming 2× faster), and
 *    on a one-column output a padded panel does four times the multiplies.
 *
 * Above [[ParOps]] multiply-adds the work runs on the common ForkJoin pool, split in
 * two dimensions — row blocks × column ranges — so a product with few rows still fills
 * the pool, with no item smaller than [[MinItemOps]] because a fork costs more than
 * that; below it, on the calling thread. Every cell belongs to exactly one work item,
 * so no cell's k-sum is ever split. All of it is tuning, not contract.
 *
 * Measured against the previous tiled loop on a 24-thread machine: 512³ 6.5 → 1.5 ms,
 * `Tprf3`'s 40×650×40 with a transposed left operand 270 → 84 µs, `Tprf3` IS Full
 * 0.67 → 0.32 ms — all bit-identical. Re-measure with `sbt "runMain uni.apps.MatmulProbe"`
 * and `Tprf3Bench`; the shapes `Tprf3` actually runs, and where its matmul time goes,
 * are what decided the thresholds above, not the square benchmark sizes.
 *
 * Why not the Vector API: `jdk.incubator.vector` must be added to the module graph by
 * every JVM that runs the library, and most scala-cli scripts do not — a hard dependency
 * here would break them at class-load time. A reflective kernel behind a fallback is the
 * next step if this ceiling ever matters.
 */
private[data] object MatmulPure:

  /** Row-block height for the parallel split. 16 rows keep enough blocks in flight
   *  (32 at 512 rows) without starving the microkernel of full 4-row groups. */
  private val BlockRows = 16

  /** Below this many multiply-adds the whole product runs on the calling thread, and no
   *  work item is made smaller than this: a fork costs more than ~50k multiply-adds. */
  private val ParOps     = 262144L
  private val MinItemOps = 131072L

  /** K below which the streaming path beats the register microkernel. */
  private val MinKForMicro = 16

  /** Outputs narrower than this take the streaming path too. A one-column product
   *  through a zero-padded panel does 4× the multiplies and packs `A` for them; on
   *  `Tprf3`'s many `*@ y` products that measured 1.9× slower than streaming. */
  private val MinColsForMicro = 4

  private val Pool = java.util.concurrent.ForkJoinPool.getCommonPoolParallelism

  /**
   * `A` (ra×ca) times `B` (ca×cb), both read through their strides — nothing is copied
   * except into the small packed panels the kernel wants.
   */
  def multiply(ad: Array[Double], aOff: Int, aRs: Int, aCs: Int,
               bd: Array[Double], bOff: Int, bRs: Int, bCs: Int,
               ra: Int, ca: Int, cb: Int): Array[Double] =
    val out = new Array[Double](ra * cb)
    if ra == 0 || cb == 0 then return out
    val ops       = ra.toLong * ca * cb
    val rowBlocks = (ra + BlockRows - 1) / BlockRows
    if ca < MinKForMicro || cb < MinColsForMicro then
      // Streaming path: needs B row-major; a view is copied, values unchanged. Columns
      // are split into chunks of at least 4 so a short matrix still fills the pool.
      val b = if bOff == 0 && bRs == cb && bCs == 1 then bd else rowMajor(bd, bOff, bRs, bCs, ca, cb)
      val cSplit = colSplit(ops, rowBlocks, math.max(1, cb / 4))
      val chunk  = ((cb + cSplit - 1) / cSplit + 3) / 4 * 4
      run(ops, rowBlocks * cSplit) { t =>
        val rb = t / cSplit; val cc = t % cSplit
        val jS = cc * chunk; val jE = math.min(jS + chunk, cb)
        if jS < jE then
          saxpyRows(ad, aOff, aRs, aCs, b, out, rb * BlockRows, math.min((rb + 1) * BlockRows, ra), ca, cb, jS, jE)
      }
    else
      val nJB = (cb + 3) / 4
      val bp  = pack4(bd, bOff, bRs, bCs, ca, cb, nJB)
      // 2-D split: row blocks × panel ranges, so a product with few rows (Tprf3's
      // 40×650×40) still spreads across the pool rather than over three threads.
      val cSplit    = colSplit(ops, rowBlocks, nJB)
      val panelsPer = (nJB + cSplit - 1) / cSplit
      run(ops, rowBlocks * cSplit) { t =>
        val rb = t / cSplit; val cc = t % cSplit
        val jbS = cc * panelsPer; val jbE = math.min(jbS + panelsPer, nJB)
        if jbS < jbE then
          microRows(ad, aOff, aRs, aCs, bp, out, rb * BlockRows, math.min((rb + 1) * BlockRows, ra), ca, cb, jbS, jbE)
      }
    out

  /** How many column chunks to split each row block into: enough that
   *  `rowBlocks × chunks` covers the pool about twice over, never so many that an item
   *  falls under [[MinItemOps]] multiply-adds, never more than `maxChunks`, and 1 when
   *  the product runs sequentially anyway. */
  private def colSplit(ops: Long, rowBlocks: Int, maxChunks: Int): Int =
    if ops < ParOps then 1
    else
      val want   = (2 * Pool + rowBlocks - 1) / rowBlocks
      val byWork = (ops / (rowBlocks.toLong * MinItemOps)).toInt
      math.max(1, math.min(maxChunks, math.min(want, byWork)))

  /** `n` work items, on the calling thread below [[ParOps]] multiply-adds. */
  private def run(ops: Long, n: Int)(item: Int => Unit): Unit =
    if ops < ParOps then { var t = 0; while t < n do { item(t); t += 1 } }
    else java.util.stream.IntStream.range(0, n).parallel().forEach(t => item(t))

  /** A strided matrix copied to row-major. */
  private def rowMajor(d: Array[Double], off: Int, rs: Int, cs: Int, rows: Int, cols: Int): Array[Double] =
    val arr = new Array[Double](rows * cols)
    var i = 0
    while i < rows do
      var j = 0
      while j < cols do { arr(i * cols + j) = d(off + i * rs + j * cs); j += 1 }
      i += 1
    arr

  /** `B` as 4-wide column panels, panel-major: `bp(jb*ca*4 + k*4 + x) = b(k, jb*4 + x)`,
   *  read through B's strides; lanes past `cb` in the last panel stay 0.0 and are never
   *  stored. */
  private def pack4(bd: Array[Double], bOff: Int, bRs: Int, bCs: Int, ca: Int, cb: Int, nJB: Int): Array[Double] =
    val bp = new Array[Double](nJB * ca * 4)
    var jb = 0
    while jb < nJB do
      val base = jb * ca * 4; val j0 = jb * 4
      val w = math.min(4, cb - j0)
      var k = 0
      while k < ca do
        val bB = bOff + k * bRs + j0 * bCs; val pB = base + k * 4
        var x = 0
        while x < w do { bp(pB + x) = bd(bB + x * bCs); x += 1 }
        k += 1
      jb += 1
    bp

  /** Rows `iS until iE` of `C`, panels `jbS until jbE`: 4-row groups through the
   *  microkernel, a row remainder through `panelRows`. `A` rows with unit k-stride are
   *  read in place; any other layout is packed k-major into `ap` per group
   *  (`ap(k*4 + r) = a(i+r, k)`), which is what made the transposed-view left operands
   *  `Tprf3` uses stop being latency-bound through their 320-byte k-stride. */
  private def microRows(ad: Array[Double], aOff: Int, aRs: Int, aCs: Int, bp: Array[Double],
                        out: Array[Double], iS: Int, iE: Int, ca: Int, cb: Int, jbS: Int, jbE: Int): Unit =
    val direct = aCs == 1
    val ap = if direct then null else new Array[Double](4 * ca)
    var i = iS
    while i + 3 < iE do
      if !direct then packA(ad, aOff, aRs, aCs, i, 4, ca, ap)
      var jb = jbS
      while jb < jbE do
        val j = jb * 4; val w = math.min(4, cb - j)
        if direct then micro44Direct(ad, aOff + i * aRs, aRs, bp, jb * ca * 4, out, i * cb, cb, j, w, ca)
        else micro44Packed(ap, bp, jb * ca * 4, out, i * cb, cb, j, w, ca)
        jb += 1
      i += 4
    if i < iE then
      val rows = iE - i
      var jb = jbS
      while jb < jbE do
        val j = jb * 4; val w = math.min(4, cb - j)
        panelRows(ad, aOff, aRs, aCs, rows, bp, jb * ca * 4, out, i, ca, cb, j, w)
        jb += 1

  /** `rows` (≤ 4) rows of `A` from `i0`, packed k-major: `ap(k*4 + r) = a(i0+r, k)`. */
  private def packA(ad: Array[Double], aOff: Int, aRs: Int, aCs: Int, i0: Int, rows: Int, ca: Int, ap: Array[Double]): Unit =
    var r = 0
    while r < rows do
      val aB = aOff + (i0 + r) * aRs
      var k = 0; var ka = 0
      while k < ca do { ap(k * 4 + r) = ad(aB + ka); k += 1; ka += aCs }
      r += 1

  /** A 4×4 block of cells — four contiguous-along-k rows of `A` starting at `a0`,
   *  columns `j ..+w` of the panel at `base` — accumulated in locals over the whole k
   *  range from 0.0 and stored once: the contract verbatim, sixteen cells at a time. Its
   *  own method so HotSpot compiles it early and keeps it hot. */
  private def micro44Direct(ad: Array[Double], a0: Int, aRs: Int, bp: Array[Double], base: Int,
                            out: Array[Double], o0: Int, cb: Int, j: Int, w: Int, ca: Int): Unit =
    val a1 = a0 + aRs; val a2 = a1 + aRs; val a3 = a2 + aRs
    var c00 = 0.0; var c01 = 0.0; var c02 = 0.0; var c03 = 0.0
    var c10 = 0.0; var c11 = 0.0; var c12 = 0.0; var c13 = 0.0
    var c20 = 0.0; var c21 = 0.0; var c22 = 0.0; var c23 = 0.0
    var c30 = 0.0; var c31 = 0.0; var c32 = 0.0; var c33 = 0.0
    var k = 0
    while k < ca do
      val pB = base + k * 4
      val b0 = bp(pB); val b1 = bp(pB + 1); val b2 = bp(pB + 2); val b3 = bp(pB + 3)
      val v0 = ad(a0 + k); val v1 = ad(a1 + k); val v2 = ad(a2 + k); val v3 = ad(a3 + k)
      c00 += v0 * b0; c01 += v0 * b1; c02 += v0 * b2; c03 += v0 * b3
      c10 += v1 * b0; c11 += v1 * b1; c12 += v1 * b2; c13 += v1 * b3
      c20 += v2 * b0; c21 += v2 * b1; c22 += v2 * b2; c23 += v2 * b3
      c30 += v3 * b0; c31 += v3 * b1; c32 += v3 * b2; c33 += v3 * b3
      k += 1
    store4(out, o0, cb, j, w, c00, c01, c02, c03, c10, c11, c12, c13, c20, c21, c22, c23, c30, c31, c32, c33)

  /** As [[micro44Direct]], reading the four rows from the k-major pack `ap`. */
  private def micro44Packed(ap: Array[Double], bp: Array[Double], base: Int,
                            out: Array[Double], o0: Int, cb: Int, j: Int, w: Int, ca: Int): Unit =
    var c00 = 0.0; var c01 = 0.0; var c02 = 0.0; var c03 = 0.0
    var c10 = 0.0; var c11 = 0.0; var c12 = 0.0; var c13 = 0.0
    var c20 = 0.0; var c21 = 0.0; var c22 = 0.0; var c23 = 0.0
    var c30 = 0.0; var c31 = 0.0; var c32 = 0.0; var c33 = 0.0
    var k = 0
    while k < ca do
      val pB = base + k * 4; val pA = k * 4
      val b0 = bp(pB); val b1 = bp(pB + 1); val b2 = bp(pB + 2); val b3 = bp(pB + 3)
      val v0 = ap(pA); val v1 = ap(pA + 1); val v2 = ap(pA + 2); val v3 = ap(pA + 3)
      c00 += v0 * b0; c01 += v0 * b1; c02 += v0 * b2; c03 += v0 * b3
      c10 += v1 * b0; c11 += v1 * b1; c12 += v1 * b2; c13 += v1 * b3
      c20 += v2 * b0; c21 += v2 * b1; c22 += v2 * b2; c23 += v2 * b3
      c30 += v3 * b0; c31 += v3 * b1; c32 += v3 * b2; c33 += v3 * b3
      k += 1
    store4(out, o0, cb, j, w, c00, c01, c02, c03, c10, c11, c12, c13, c20, c21, c22, c23, c30, c31, c32, c33)

  /** Store a 4×4 block's first `w` columns. */
  private def store4(out: Array[Double], o0: Int, cb: Int, j: Int, w: Int,
                     c00: Double, c01: Double, c02: Double, c03: Double,
                     c10: Double, c11: Double, c12: Double, c13: Double,
                     c20: Double, c21: Double, c22: Double, c23: Double,
                     c30: Double, c31: Double, c32: Double, c33: Double): Unit =
    val o1 = o0 + cb; val o2 = o1 + cb; val o3 = o2 + cb
    out(o0 + j) = c00; out(o1 + j) = c10; out(o2 + j) = c20; out(o3 + j) = c30
    if w > 1 then { out(o0 + j + 1) = c01; out(o1 + j + 1) = c11; out(o2 + j + 1) = c21; out(o3 + j + 1) = c31 }
    if w > 2 then { out(o0 + j + 2) = c02; out(o1 + j + 2) = c12; out(o2 + j + 2) = c22; out(o3 + j + 2) = c32 }
    if w > 3 then { out(o0 + j + 3) = c03; out(o1 + j + 3) = c13; out(o2 + j + 3) = c23; out(o3 + j + 3) = c33 }

  /** `rows` (fewer than four) rows of `A` from `i0` against one packed panel: four
   *  accumulators per row over the whole k range, first `w` stored. Per cell, k ascending
   *  from 0.0. */
  private def panelRows(ad: Array[Double], aOff: Int, aRs: Int, aCs: Int, rows: Int, bp: Array[Double], base: Int,
                        out: Array[Double], i0: Int, ca: Int, cb: Int, j: Int, w: Int): Unit =
    var r = 0
    while r < rows do
      val oB = (i0 + r) * cb + j; val aB = aOff + (i0 + r) * aRs
      var c0 = 0.0; var c1 = 0.0; var c2 = 0.0; var c3 = 0.0
      var k = 0; var ka = 0
      while k < ca do
        val pB = base + k * 4; val av = ad(aB + ka)
        c0 += av * bp(pB); c1 += av * bp(pB + 1); c2 += av * bp(pB + 2); c3 += av * bp(pB + 3)
        k += 1; ka += aCs
      out(oB) = c0
      if w > 1 then out(oB + 1) = c1
      if w > 2 then out(oB + 2) = c2
      if w > 3 then out(oB + 3) = c3
      r += 1

  /** Rows `iS until iE`, columns `jS until jE`, by streaming saxpy over row-major `b`:
   *  for each k, `C(i, jS..jE) += a(i,k) · B(k, jS..jE)`, four rows sharing each loaded
   *  `B` value. Per cell the k-order is unchanged; the k tile only keeps the `C` rows
   *  warm between passes. */
  private def saxpyRows(ad: Array[Double], aOff: Int, aRs: Int, aCs: Int, b: Array[Double], out: Array[Double],
                        iS: Int, iE: Int, ca: Int, cb: Int, jS: Int, jE: Int): Unit =
    val TK = 16
    var kS = 0
    while kS < ca do
      val kE = math.min(kS + TK, ca)
      var i = iS
      while i + 3 < iE do
        val o0 = i * cb; val o1 = o0 + cb; val o2 = o1 + cb; val o3 = o2 + cb
        val a0 = aOff + i * aRs; val a1 = a0 + aRs; val a2 = a1 + aRs; val a3 = a2 + aRs
        var k = kS
        while k < kE do
          val ka = k * aCs
          val v0 = ad(a0 + ka); val v1 = ad(a1 + ka); val v2 = ad(a2 + ka); val v3 = ad(a3 + ka)
          val bB = k * cb
          var j = jS
          while j < jE do
            val bv = b(bB + j)
            out(o0 + j) += v0 * bv
            out(o1 + j) += v1 * bv
            out(o2 + j) += v2 * bv
            out(o3 + j) += v3 * bv
            j += 1
          k += 1
        i += 4
      while i < iE do
        val oB = i * cb; val aB = aOff + i * aRs
        var k = kS
        while k < kE do
          val av = ad(aB + k * aCs); val bB = k * cb
          var j = jS
          while j < jE do { out(oB + j) += av * b(bB + j); j += 1 }
          k += 1
        i += 1
      kS = kE

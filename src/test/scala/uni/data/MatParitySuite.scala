package uni.data

import uni.*
import scala.concurrent.duration.*

/**
 * Checks Scala's [[uni.data.Mat]] reductions against the committed reference in
 * `test-data/mat-parity/`, the same file `rust/tests/mat_parity.rs` checks the Rust port
 * against.
 *
 * The pair pins both implementations to one association order without either test
 * needing the other language installed: a change that moves Scala's sums fails here, a
 * change that moves only Rust's fails there.
 *
 * What is pinned is not correctness — a naive fold is "correct" — but the floating-point
 * ASSOCIATION ORDER of `Mat.sumD`. Comparisons are on raw IEEE-754 bits, since a
 * tolerance would wave through exactly the drift this exists to catch.
 *
 * Rows keyed `<rows>x<cols>` additionally pin the VIEW MODEL: `transpose`, `slice` and
 * `broadcastTo` return zero-copy strided views, and the layout is what decides whether a
 * reduction runs `sumD` or a plain sequential fold. `m.sum` and `m.T.sum` are therefore
 * not required to agree — and on this corpus they do not.
 *
 * Regenerate with `sbt "runMain uni.apps.MatParityGen"`, and only when the values are
 * meant to move.
 */
class MatParitySuite extends munit.FunSuite:

  // This is a bit-for-bit fixture comparison: if parity breaks it fails on assertEquals,
  // never on the clock, so wall-time here carries no diagnostic value and a tight bound can
  // only produce false failures.  It ran ~11 s warm locally and 32.9 s on a GitHub macOS
  // runner, crossing munit's 30 s default; the same commit passes on a local Mac.  The
  // margin, not the duration, was the defect.  Performance belongs to the benchmarks.
  override val munitTimeout: Duration = 120.seconds

  val fixture: java.nio.file.Path =
    val root = sys.props.getOrElse("user.dir", ".")
    s"$root/test-data/mat-parity/scala-reference.txt".asPath

  def bits(d: Double): Long = java.lang.Double.doubleToRawLongBits(d)

  /**
   * Every recorded case as its raw 64-bit word, delegating to the generator so the two
   * cannot drift apart. Recomputing the definitions here would let a bug in the
   * generator be "confirmed" by a matching bug in the test.
   */
  def word(m: Mat[Double], me: Mat[Double], label: String): Long =
    uni.apps.MatParityGen.cases(m).toMap.get(label).map(bits).orElse(
      uni.apps.MatParityGen.expCases(me).toMap.get(label)
    ).getOrElse(fail(s"unknown case [$label] in fixture — port it or regenerate"))

  /** The 2-D half, likewise delegated to the generator. `math.*`, `la.*` and `sg.*` rows
   *  are computed on the bounded-corpus matrix `me`; everything else on `m`. Each
   *  family's map is computed once per shape and cached: the decomposition rows are
   *  expensive (an SVD per lookup would put the suite past its timeout). */
  private val cache2d = scala.collection.mutable.HashMap.empty[(String, String), Map[String, Long]]

  def word2d(shape: String, m: Mat[Double], me: Mat[Double], label: String): Long =
    val family = label.takeWhile(_ != '.')
    val table = cache2d.getOrElseUpdate((shape, family), family match
      case "math" => uni.apps.MatParityGen.mathCases(me).toMap
      case "la"   => uni.apps.MatParityGen.linalgCases(me).toMap
      case "ut"   => uni.apps.MatParityGen.utilCases(m).toMap
      case "pd"   => uni.apps.MatParityGen.pandasCases(m).toMap
      case "sg"   => uni.apps.MatParityGen.signalCases(me).toMap
      case "bm"   => uni.apps.MatParityGen.bigCases(me).toMap
      case "mf"   => uni.apps.MatParityGen.floatCases(me).toMap
      case "pl"   => uni.apps.MatParityGen.plotCases(m, me).toMap
      case _      =>
        uni.apps.MatParityGen.cases2d(m).toMap.map((k, v) => k -> bits(v)) ++
          uni.apps.MatParityGen.wordCases2d(m).toMap
    )
    table.getOrElse(label, fail(s"unknown 2-D case [$label] in fixture — port it or regenerate"))

  /** The ordering cases over NaN / signed zeros / infinities. */
  def wordAdv(m: Mat[Double], label: String): Long =
    uni.apps.MatParityGen.advCases(m).toMap
      .getOrElse(label, fail(s"unknown adversarial case [$label] in fixture — port it or regenerate"))

  /** Rebuilds the matrix an `adv/<name>/<orientation>` key names. */
  def advMat(key: String): Mat[Double] =
    val Array(_, name, orient) = key.split("/"): @unchecked
    val arr = uni.apps.MatParityGen.adversarial.toMap
      .getOrElse(name, fail(s"unknown adversarial array [$name]"))
    uni.apps.MatParityGen.advShapes(arr).toMap
      .getOrElse(orient, fail(s"unknown orientation [$orient]"))

  /** `12` is an n×1 column over a corpus prefix; `3x5` is a row-major 2-D matrix. */
  def shapeOf(token: String): (Int, Int) =
    token.split("x") match
      case Array(r, c) => (r.toInt, c.toInt)
      case _           => (token.toInt, 1)

  def isAdv(token: String): Boolean = token.startsWith("adv/")
  def is2d(token: String): Boolean  = !isAdv(token) && token.contains("x")

  test("Mat reductions match the committed reference bit for bit") {
    assert(fixture.isFile, s"missing fixture [$fixture] — run MatParityGen")
    val rows = fixture.lines
      .filter(l => !l.startsWith("#") && l.trim.nonEmpty)
      .map { l =>
        val Array(shape, label, hex) = l.split("\\s+"): @unchecked
        (shape, label, java.lang.Long.parseUnsignedLong(hex, 16))
      }
      .toVector

    assert(rows.nonEmpty, "fixture carried no rows")

    // Drawn once to the largest prefix any row needs; each shape takes a prefix, as the
    // generator does. Adversarial rows carry literal values rather than a prefix.
    val maxN = rows.collect {
      case (shape, _, _) if !isAdv(shape) => shapeOf(shape) match { case (r, c) => r * c }
    }.max
    val all    = uni.apps.MatParityGen.corpus(maxN)
    val allExp = uni.apps.MatParityGen.corpusExp(maxN)

    for (shape, label, want) <- rows do
      val got =
        if isAdv(shape) then wordAdv(advMat(shape), label)
        else if is2d(shape) then
          val (r, c) = shapeOf(shape)
          word2d(shape, uni.apps.MatParityGen.mat2d(all, r, c), uni.apps.MatParityGen.mat2d(allExp, r, c), label)
        else
          val n  = shape.toInt
          val m  = MatD(java.util.Arrays.copyOfRange(all, 0, n))
          val me = MatD(java.util.Arrays.copyOfRange(allExp, 0, n))
          word(m, me, label)
      assertEquals(
        got,
        want,
        f"shape=$shape case=$label: got $got%016x, want $want%016x — the reference has moved",
      )

    // Each group is counted separately: they pin different things, and losing one would
    // leave the others looking healthy.
    // These two minima track the actual counts with a few percent of slack, deliberately.  At
    // 1550 and 250 they were DOMINATED: the per-family minima below sum to 2592, so neither
    // could fire before a family assertion did, and between them they would have waved through
    // a regeneration that silently dropped more than half the corpus (3452 rows became "fine"
    // anywhere above 1550).  A count that cannot fail is not coverage.  Raising one of these is
    // a deliberate act; a regeneration that lowers a count should fail here and be explained.
    assert(rows.length >= 3400,
      s"only ${rows.length} rows, against 13 sizes + 11 shapes + 10 adversarial and the " +
      s"per-family minima below; the corpus has shrunk — regenerate or explain")
    val twoD = rows.count((shape, _, _) => is2d(shape))
    assert(twoD >= 2150, s"only $twoD 2-D rows; the view model would go under-checked")
    val masks = rows.count((_, label, _) => label.startsWith("mask."))
    assert(masks >= 800, s"only $masks mask rows; IEEE comparison semantics would go unchecked")
    val adv = rows.count((shape, label, _) => isAdv(shape) && !label.startsWith("mask."))
    assert(adv >= 300, s"only $adv adversarial rows; NaN/signed-zero ordering would go unchecked")
    val matmuls = rows.count((_, label, _) => label.endsWith("mmfnv"))
    assert(matmuls >= 22, s"only $matmuls matmul rows; the pinned matmul path would go unchecked")
    val linalg = rows.count((_, label, _) => label.startsWith("la."))
    assert(linalg >= 180, s"only $linalg linalg rows; the decomposition family would go unchecked")
    val utils = rows.count((_, label, _) => label.startsWith("ut."))
    assert(utils >= 150, s"only $utils util rows; maximum/minimum ordering, round, scale and friends would go unchecked")
    val pandas = rows.count((_, label, _) => label.startsWith("pd.") || label.startsWith("sg."))
    assert(pandas >= 300, s"only $pandas pandas/signal rows; the ordering and statistics family would go unchecked")
    val bigs = rows.count((_, label, _) => label.startsWith("bm."))
    assert(bigs >= 250, s"only $bigs Mat[Big] rows; the exact-decimal matrix would go unchecked")
    val floats = rows.count((_, label, _) => label.startsWith("mf."))
    assert(floats >= 250, s"only $floats Mat[Float] rows; single precision would go unchecked")
    val plots = rows.count((_, label, _) => label.startsWith("pl."))
    assert(plots >= 90, s"only $plots uni.plot rows; the SVG renderer would go unchecked")
    val maths = rows.count((_, label, _) => label.startsWith("math."))
    assert(maths >= 250, s"only $maths MatMathOps rows; the elementwise math formulas would go unchecked")
  }

  test("the matmul rows would catch a reassociated kernel") {
    // The pinned path is a sequential k-sum from 0.0 per cell. Any kernel that
    // reassociates lands on other bits on this corpus; demonstrate with the mildest
    // reassociation there is, two half-sums combined. (Not "BLAS differs": the reference
    // BLAS dgemm IS a sequential k-loop per cell and legitimately reproduces the pinned
    // order bit for bit — as it does on the Linux CI box.)
    val m = uni.apps.MatParityGen.mat2d(uni.apps.MatParityGen.corpus(120000), 300, 400)
    val t = m.T
    val pinned = m.matmulPure(t).toArray
    val k = m.cols
    val split = Array.tabulate(m.rows * t.cols) { i =>
      val r = i / t.cols; val c = i % t.cols
      var lo = 0.0; var hi = 0.0
      var kk = 0
      while kk < k / 2 do { lo += m(r, kk) * t(kk, c); kk += 1 }
      while kk < k do { hi += m(r, kk) * t(kk, c); kk += 1 }
      lo + hi
    }
    assertNotEquals(
      uni.apps.MatParityGen.fnv(pinned),
      uni.apps.MatParityGen.fnv(split),
      "a k-split kernel agrees with the pinned path here; the mmfnv rows pin nothing",
    )
  }

  test("the corpus is sensitive to association order") {
    // A test that cannot fail proves nothing. If a naive left fold ever agrees with
    // sumD on this corpus, the parity test above has stopped being evidence.
    val a       = uni.apps.MatParityGen.corpus(200000)
    val chunked = MatD(a).sum
    val naive   = a.foldLeft(0.0)(_ + _)
    assertNotEquals(
      bits(chunked),
      bits(naive),
      "corpus no longer distinguishes a naive fold from sumD",
    )
  }

  test("the mask rows record IEEE, not the ordering they sit beside") {
    // The adversarial corpus carries both rules at once: `min`/`max`/`argmax` rows use
    // TotalOrdering, the `mask.` rows use IEEE. If the two ever agreed on this corpus the
    // mask rows would be pinning nothing, so assert they still disagree — on NaN, which
    // TotalOrdering ranks above every number, and on -0.0, which it ranks below 0.0.
    val ord  = summon[Ordering[Double]]
    val advs = uni.apps.MatParityGen.adversarial.toMap
    val m    = MatD(advs("nanmid").clone())
    assert(ord.gt(Double.NaN, 0.0), "TotalOrdering must still rank NaN above 0.0")
    assertEquals(
      m.gt(0.0).toArray.toSeq,
      Seq(true, false, false, true),
      "gt must be false at NaN; a port using its ordering comparator would say true",
    )
    val z = MatD(advs("zeros").clone())
    assert(ord.lt(-0.0, 0.0), "TotalOrdering must still rank -0.0 below 0.0")
    assertEquals(z.lt(0.0).toArray.toSeq, Seq(false, false), "-0.0 < 0.0 must be false")
  }

  test("the corpus is sensitive to the view model") {
    // The 2-D half is only evidence if a view really does reduce differently from a
    // contiguous matrix here. Both of these would pass vacuously for an implementation
    // that materialized every view, so assert that they do not.
    val m = uni.apps.MatParityGen.mat2d(uni.apps.MatParityGen.corpus(120000), 300, 400)
    assertNotEquals(
      bits(m.sum),
      bits(m.T.sum),
      "a transposed view sums identically here; the tsum rows pin nothing",
    )
    // The reverse of what this asserted before 0.17.0, deliberately. `m.std(0)` and
    // `m.T.std(1)` reduce the SAME lanes — row k of the transpose is column k of the
    // original — so once std(axis) takes its per-lane mean the one way, the two must
    // agree bit for bit. They did not before: the strided path materialized each lane
    // and routed through sumD. This guards the unification rather than the wart.
    assertEquals(
      uni.apps.MatParityGen.fnv(m.std(0).toArray),
      uni.apps.MatParityGen.fnv(m.T.std(1).toArray),
      "std(axis) disagrees between a matrix and its transpose",
    )
  }

package uni.data

import uni.*

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

  /** The 2-D half, likewise delegated to the generator. */
  def word2d(m: Mat[Double], label: String): Long =
    uni.apps.MatParityGen.cases2d(m).toMap.get(label).map(bits).orElse(
      uni.apps.MatParityGen.wordCases2d(m).toMap.get(label)
    ).getOrElse(fail(s"unknown 2-D case [$label] in fixture — port it or regenerate"))

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
          word2d(uni.apps.MatParityGen.mat2d(all, r, c), label)
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
    assert(rows.length >= 1550, s"only ${rows.length} rows; expected 13 sizes + 11 shapes + 10 adversarial")
    val twoD = rows.count((shape, _, _) => is2d(shape))
    assert(twoD >= 250, s"only $twoD 2-D rows; the view model would go unchecked")
    val masks = rows.count((_, label, _) => label.startsWith("mask."))
    assert(masks >= 800, s"only $masks mask rows; IEEE comparison semantics would go unchecked")
    val adv = rows.count((shape, label, _) => isAdv(shape) && !label.startsWith("mask."))
    assert(adv >= 300, s"only $adv adversarial rows; NaN/signed-zero ordering would go unchecked")
    val matmuls = rows.count((_, label, _) => label.endsWith("mmfnv"))
    assert(matmuls >= 22, s"only $matmuls matmul rows; the pinned matmul path would go unchecked")
  }

  test("the matmul rows would catch a reassociated kernel") {
    // The pinned path is a sequential k-sum from 0.0 per cell; a BLAS reassociates. If
    // BLAS ever agreed bit for bit on this corpus the matmul rows would pin nothing.
    val m = uni.apps.MatParityGen.mat2d(uni.apps.MatParityGen.corpus(120000), 300, 400)
    assertNotEquals(
      uni.apps.MatParityGen.fnv(m.matmulPure(m.T).toArray),
      uni.apps.MatParityGen.fnv(m.matmulBlas(m.T).toArray),
      "BLAS agrees with the pinned matmul here; the mmfnv rows pin nothing",
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
    // The reverse of what this asserted before 0.16.1, deliberately. `m.std(0)` and
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

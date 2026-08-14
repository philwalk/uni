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

  test("Mat reductions match the committed reference bit for bit") {
    assert(fixture.isFile, s"missing fixture [$fixture] — run MatParityGen")
    val rows = fixture.lines
      .filter(l => !l.startsWith("#") && l.trim.nonEmpty)
      .map { l =>
        val Array(n, label, hex) = l.split("\\s+"): @unchecked
        (n.toInt, label, java.lang.Long.parseUnsignedLong(hex, 16))
      }
      .toVector

    assert(rows.nonEmpty, "fixture carried no rows")

    // Drawn once to the largest size; smaller sizes take a prefix, as the generator does.
    val maxN   = rows.map(_._1).max
    val all    = uni.apps.MatParityGen.corpus(maxN)
    val allExp = uni.apps.MatParityGen.corpusExp(maxN)

    for (n, label, want) <- rows do
      val m   = MatD(java.util.Arrays.copyOfRange(all, 0, n))
      val me  = MatD(java.util.Arrays.copyOfRange(allExp, 0, n))
      val got = word(m, me, label)
      assertEquals(
        got,
        want,
        f"n=$n case=$label: got $got%016x, want $want%016x — the reference has moved",
      )

    assert(rows.length >= 120, s"only ${rows.length} rows; expected 13 sizes x 10 cases")
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

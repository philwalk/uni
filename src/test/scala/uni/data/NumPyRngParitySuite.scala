package uni.data

import munit.FunSuite
import uni.*
import uni.apps.NumPyRngParityGen

/**
 * Checks [[NumPyRNG]] against the committed reference in
 * `test-data/numpy-rng-parity/`.
 *
 * The Rust port (`rust/tests/numpy_rng_parity.rs`) checks itself against the
 * same file, so the pair pins both implementations to one stream without either
 * test needing the other language installed. A change that moves Scala's draws
 * fails here; a change that moves only Rust's fails there.
 *
 * The draw definitions come from [[NumPyRngParityGen]] rather than being
 * restated here. The fixture means whatever the generator computed, so a second
 * copy of that logic in the test could only ever drift away from it — and would
 * then be checking the wrong thing while still passing. The Rust side does
 * reimplement them, which is the point: that comparison is between two
 * independent implementations, this one is against a recorded baseline.
 *
 * Regenerate the reference with `sbt "runMain uni.apps.NumPyRngParityGen"` — and
 * only when the values are meant to move.
 */
class NumPyRngParitySuite extends FunSuite:

  private val dir = s"${sys.props.getOrElse("user.dir", ".")}/test-data/numpy-rng-parity"

  /** Verbatim head draws, and the (count, digest) pair, keyed by seed and case. */
  private lazy val reference: (Map[(Long, String), Vector[Long]], Map[(Long, String), (Int, Long)]) =
    val p = s"$dir/scala-reference.txt".asPath
    require(p.isFile,
      s"missing ${p.posx} — regenerate with: sbt \"runMain uni.apps.NumPyRngParityGen\"")
    val records = p.lines.iterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .map(_.split("\\s+").toList)
      .toVector
    val heads = records.collect { case seed :: label :: "head" :: _ :: hex :: Nil =>
      (seed.toLong, label) -> java.lang.Long.parseUnsignedLong(hex, 16)
    }.groupMap(_._1)(_._2).view.mapValues(_.toVector).toMap
    val digests = records.collect { case seed :: label :: "fnv" :: n :: hex :: Nil =>
      (seed.toLong, label) -> (n.toInt, java.lang.Long.parseUnsignedLong(hex, 16))
    }.toMap
    (heads, digests)

  private def hex(v: Long): String = f"$v%016x"

  for seed <- NumPyRngParityGen.seeds; (label, draw, count) <- NumPyRngParityGen.cases do
    test(s"seed $seed case $label matches the parity reference"):
      val (heads, digests) = reference
      val key = (seed, label)
      val (wantCount, wantDigest) = digests.getOrElse(key,
        fail(s"reference has no digest for seed $seed case $label"))
      val wantHead = heads.getOrElse(key,
        fail(s"reference has no head draws for seed $seed case $label"))
      assertEquals(wantCount, count,
        s"reference was generated with a different draw count for $label")

      val (gotHead, gotDigest) = NumPyRngParityGen.summarize(seed, count, draw)

      // Head first: when both fail, the individual draw is the useful message.
      for ((got, want), i) <- gotHead.zip(wantHead).zipWithIndex do
        assertEquals(hex(got), hex(want), s"seed $seed case $label draw $i")
      assertEquals(hex(gotDigest), hex(wantDigest),
        s"seed $seed case $label: the first ${wantHead.length} draws match, but the " +
        s"digest over $count does not — the stream diverges later in the run")

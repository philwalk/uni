package uni.apps

import uni.*
import uni.data.NumPyRNG

/**
 * Regenerates the cross-language parity fixture in `test-data/numpy-rng-parity/`
 * for [[uni.data.NumPyRNG]] and its Rust port (`rust/src/numpy_rng.rs`).
 *
 * Consumed by two tests that must agree with each other:
 *   - uni.data.NumPyRngParitySuite     (Scala, src/test)
 *   - rust/tests/numpy_rng_parity.rs   (Rust)
 *
 * Neither needs the other language installed, because both compare against the
 * committed reference rather than against each other.
 *
 * Unlike the Tprf3 fixtures, comparisons here are on raw bit patterns, never on
 * decimal text. The failure being pinned down is a generator that
 * *desynchronizes* partway through a stream, and a loose numeric comparison
 * would wave that through.
 *
 * Recording a million draws per case verbatim would be absurd, so each case
 * commits the first [[headCount]] values in full — readable when an early
 * divergence needs diagnosing — plus an FNV-1a digest over [[drawCount]] of
 * them, which catches a divergence at any later point for one line of fixture.
 *
 * `randn` is the one case not compared bit-for-bit, and [[quantize]] explains
 * why: its tail calls `log1p`, and the JVM and C round that differently about
 * once in 400,000 draws. Everything else — the seeding, the state advance, every
 * integer method, `nextDouble` and `uniform` — is exact.
 *
 * Run ONLY when the reference is meant to move — regenerating rewrites the very
 * values the tests check, so an unintended run masks a regression instead of
 * catching it. Review the diff before keeping it.
 *
 * Run:  sbt "runMain uni.apps.NumPyRngParityGen"
 */
object NumPyRngParityGen:
  def println(s: String = ""): Unit = print(s"$s\n")

  /** Seeds spanning the interesting shapes of SeedSequence input: zero (which
   *  NumPy special-cases into a single zero word), small one-word values, a
   *  two-word value whose low word is zero, and the largest seed Scala can
   *  express. */
  val seeds: Seq[Long] = Seq(0L, 1L, 42L, 12345L, 8589934592L, Long.MaxValue)

  /** Draws folded into each digest. Large enough to exercise the two `randn`
   *  paths that call into libm, and so the only places the two languages could
   *  disagree: at a million draws the tail branch runs about 256 times (one draw
   *  in 3900) and the wedge branch some 15,000 times. */
  val drawCount: Int = 1_000_000

  /** Shorter for the mixed case: each iteration costs several draws, and the
   *  state interaction it targets shows up immediately or not at all. */
  val mixedCount: Int = 200_000

  /** Draws also recorded verbatim, so an early break is legible in the diff. */
  val headCount: Int = 8

  /** FNV-1a over 64-bit words. Chosen because it is trivially identical in both
   *  languages: one xor and one wrapping multiply per word — no table, no
   *  endianness question, no floating point. */
  private val FnvOffset: Long = 0xcbf29ce484222325L
  private val FnvPrime: Long  = 0x100000001b3L

  /** Every draw is compared as a bit pattern, so `Double`s go in as their
   *  IEEE-754 bits; Rust's `f64::to_bits` reads back the identical word. */
  private def bits(d: Double): Long = java.lang.Double.doubleToRawLongBits(d)

  /** Grid for [[quantize]]: 2^40, leaving ~40 bits of mantissa. */
  private val QuantumScale: Double = 1099511627776.0

  /** Reduces a `randn` draw to a point on a 2^-40 relative grid.
   *
   *  `randn` is the only method whose result is not reproducible bit-for-bit
   *  across the two languages, and the reason is not in either port: its tail
   *  sampler returns a value computed through `log1p`, and the JVM's
   *  `Math.log1p` and the C library's disagree by one ulp on a small fraction of
   *  arguments. Measured over six seeds, the JVM and NumPy differ on about 2.5
   *  draws per million — every one of them a tail draw (|z| > R, about one draw
   *  in 3900), every one of them by exactly one ulp. The Rust port agrees with
   *  NumPy exactly, so the JVM is the odd one out, and neither result is wrong.
   *
   *  A digest cannot express "equal to within an ulp", so the value is snapped
   *  to a grid coarse enough to absorb that and fine enough to catch anything
   *  real: the `ZIGNOR_R` bug this fixture was written alongside displaced tail
   *  draws by 0.2115, some 2^38 times the grid step. A desync — the failure that
   *  actually matters — changes values wholesale and cannot hide here either.
   *
   *  `floor(x + 0.5)` rather than a `round` call: Java rounds a negative half
   *  toward positive infinity and Rust rounds it away from zero, so the two
   *  disagree on exact halves. This form is identical in both.
   *
   *  Residual risk, stated plainly: a one-ulp difference still changes the
   *  result if the two values straddle a grid boundary, which needs them within
   *  2^-40 of one, about a 1-in-4000 chance per already-differing draw. That is
   *  a property of the committed numbers, not a coin flip at test time — these
   *  fixtures are verified to agree, and only a regeneration could introduce
   *  such a straddle. */
  private def quantize(d: Double): Long = Math.floor(d * QuantumScale + 0.5).toLong

  /** A case reduces the i-th draw from a generator to one 64-bit word. The
   *  index is passed in rather than captured, so the cases stay pure functions
   *  of `(i, rng)` and the driver owns all the iteration state. */
  private[uni] type Draw = (Int, NumPyRNG) => Long

  /** Interleaves the methods rather than testing each in isolation.
   *
   *  `nextBoundedInt` splits one 64-bit draw across two calls and carries the
   *  unused half in generator state, so the order in which methods are mixed
   *  changes which raw draw each one sees. A per-method fixture cannot observe
   *  that; this one can. The varying bound also exercises Lemire's multiply at
   *  more than one scale. */
  private val mixed: Draw = (i, rng) =>
    i % 5 match
      case 0 => rng.nextLong()
      case 1 => rng.nextBoundedInt(1 + i % 97).toLong
      case 2 => bits(rng.nextDouble())
      case 3 => quantize(rng.randn())
      case _ => rng.nextBoundedInt(1_000_000).toLong

  /** Label, draw function, and draw count per case. The label is the fixture
   *  key, so renaming one invalidates the committed reference. */
  private[uni] val cases: Seq[(String, Draw, Int)] = Seq(
    ("u64",      (_, rng) => rng.nextLong(),                     drawCount),
    ("i32",      (_, rng) => rng.nextInt().toLong & 0xFFFFFFFFL, drawCount),
    ("f64",      (_, rng) => bits(rng.nextDouble()),             drawCount),
    ("uniform",  (_, rng) => bits(rng.uniform(-2.5, 7.25)),      drawCount),
    ("bounded6", (_, rng) => rng.nextBoundedInt(6).toLong,       drawCount),
    ("randn",    (_, rng) => quantize(rng.randn()),              drawCount),
    ("mixed",    mixed,                                          mixedCount),
  )

  /** `count` draws from a freshly seeded generator: the verbatim head, and the
   *  digest over all of them. */
  private[uni] def summarize(seed: Long, count: Int, draw: Draw): (Seq[Long], Long) =
    val rng    = new NumPyRNG(seed)
    val head   = Array.ofDim[Long](headCount)
    var digest = FnvOffset
    var i      = 0
    while i < count do
      val word = draw(i, rng)
      if i < headCount then head(i) = word
      digest = (digest ^ word) * FnvPrime
      i += 1
    (head.toSeq, digest)

  private def hex(v: Long): String = f"$v%016x"

  private val header: String =
    """|# NumPyRNG (PCG64 XSL RR 128) cross-language parity reference.
       |# Regenerate with: sbt "runMain uni.apps.NumPyRngParityGen"
       |#
       |# Checked by uni.data.NumPyRngParitySuite and rust/tests/numpy_rng_parity.rs.
       |# All values are raw 64-bit patterns in hex. Lines are
       |#   <seed> <case> head <index> <hex>   -- the first draws, in order
       |#   <seed> <case> fnv  <count> <hex>   -- FNV-1a over that many draws
       |#
       |# Integer draws go in verbatim and nextDouble/uniform as their IEEE-754
       |# bits, so those cases are exact. The randn case (and the randn draws
       |# inside `mixed`) are snapped to a 2^-40 grid first: the JVM and C round
       |# log1p differently on about 2.5 tail draws per million, which no digest
       |# can express as a tolerance. See NumPyRngParityGen.quantize.
       |#
       |# The Rust port's Ziggurat tables are generated from the Scala ones by
       |# py/gen_ziggurat_rs.py, so `randn` shares its constants across languages.
       |""".stripMargin

  def main(args: Array[String]): Unit =
    val root = sys.props.getOrElse("user.dir", ".")
    val dir  = s"$root/test-data/numpy-rng-parity"
    java.nio.file.Files.createDirectories(dir.asPath)

    val sb = StringBuilder()
    sb ++= header
    for seed <- seeds do
      for (label, draw, count) <- cases do
        val (head, digest) = summarize(seed, count, draw)
        for (v, i) <- head.zipWithIndex do
          sb ++= s"$seed $label head $i ${hex(v)}\n"
        sb ++= s"$seed $label fnv $count ${hex(digest)}\n"
      println(s"  seed $seed: ${cases.length} cases")

    val out = s"$dir/scala-reference.txt"
    java.nio.file.Files.writeString(out.asPath, sb.toString)
    println(s"wrote $out")
    FixtureGuard.warnIfIgnored(dir.asPath)

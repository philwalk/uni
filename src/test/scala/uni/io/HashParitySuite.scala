package uni.io

import munit.FunSuite
import uni.*

/**
 * Checks `uni`'s four file hashes against the committed reference in
 * `test-data/hash-parity/`, produced by `uni.apps.HashParityGen`.
 *
 * The Rust side (`rust/tests/hash_parity.rs`) checks itself against the same files.
 *
 * `md5`, `sha256` and `cksum` are additionally checked here against published
 * constants -- RFC 1321, FIPS 180-4 and the POSIX utility -- so a corrupted
 * regeneration cannot quietly redefine them for both languages at once.
 *
 * Regenerate with `sbt "runMain uni.apps.HashParityGen"`, and only deliberately.
 */
class HashParitySuite extends FunSuite:

  private case class Row(
    name: String, size: Long, crc: Long, len: Long,
    hash64: String, md5: String, sha256: String)

  private lazy val reference: Seq[Row] =
    val file = Paths.get("test-data/hash-parity/scala-reference.txt")
    assert(file.isFile, s"missing fixture; run: sbt \"runMain uni.apps.HashParityGen\"")
    val rows = file.lines.filterNot(l => l.isEmpty || l.startsWith("#")).map { line =>
      val f = line.split("\t", -1)
      assertEquals(f.length, 7, s"malformed fixture line: $line")
      Row(f(0), f(1).toLong, f(2).toLong, f(3).toLong, f(4), f(5), f(6))
    }.toSeq
    assert(rows.length >= 20, s"only ${rows.length} cases in fixture")
    rows

  private def input(name: String): Path =
    Paths.get(s"test-data/hash-parity/inputs/$name.bin")

  test("every hash matches the committed reference") {
    for row <- reference do
      val p = input(row.name)
      val (crc, len) = p.cksum
      assertEquals(len, row.size, s"[${row.name}] byte count")
      assertEquals(len, row.len, s"[${row.name}] cksum length field")
      assertEquals(crc, row.crc, s"[${row.name}] cksum")
      assertEquals(p.hash64, row.hash64, s"[${row.name}] hash64")
      assertEquals(p.md5, row.md5, s"[${row.name}] md5")
      assertEquals(p.sha256, row.sha256, s"[${row.name}] sha256")
  }

  test("the block-halves pair is separated by every hash") {
    // Two 128-byte inputs differing in 64 of their bytes. `hash64` used to return the
    // same value for both, because `Hash64.processChunk` advanced 64 bytes while
    // mixing only the first 32 -- for a duplicate-file finder, a false positive on
    // entirely different files.
    val a = input("block-halves-a")
    val b = input("block-halves-b")

    assertNotEquals(a.hash64, b.hash64, "hash64 must see the whole block")
    assertNotEquals(a.md5, b.md5)
    assertNotEquals(a.sha256, b.sha256)
    assertNotEquals(a.cksum._1, b.cksum._1)
    assertEquals(a.cksum._2, b.cksum._2, "same length, different content")
  }

  test("the empty-file case agrees with the published vectors") {
    // Independent of anything the generator produced: if a regeneration ever
    // corrupted the fixture, these three constants still hold the line.
    val p = input("pattern-0")
    assertEquals(p.md5, "d41d8cd98f00b204e9800998ecf8427e")
    assertEquals(p.sha256, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    assertEquals(p.cksum._1, 4294967295L)
  }

  test("known-answer vectors for the specified hashes") {
    val fox = input("ascii") // "The quick brown fox jumps over the lazy dog"
    assertEquals(fox.md5, "9e107d9d372bb6826bd81d3542a419d6")
    assertEquals(fox.sha256, "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592")
  }

  test("every byte position affects hash64") {
    // The direct statement of what was broken: a flip anywhere in a 128-byte file
    // must change the hash. Byte 32 onward used to be invisible.
    val p = Paths.get("target/hash64-avalanche.bin")
    val base = Array.tabulate(128)(i => i.toByte)
    java.nio.file.Files.write(p, base)
    val baseline = p.hash64
    for i <- base.indices do
      val v = base.clone()
      v(i) = (v(i) ^ 0xff).toByte
      java.nio.file.Files.write(p, v)
      assertNotEquals(p.hash64, baseline, s"flipping byte $i changed nothing")
    java.nio.file.Files.deleteIfExists(p)
  }


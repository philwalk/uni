package uni.io

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.security.MessageDigest

class HashSuite extends FunSuite:

  // Helper: create a temp file with given byte content and delete on suite teardown
  private def withTempFile(content: Array[Byte])(f: Path => Unit): Unit =
    val p = Files.createTempFile("hash-suite-", ".tmp")
    try
      Files.write(p, content)
      f(p)
    finally
      Files.deleteIfExists(p)
    ()

  private def withTempFile(content: String)(f: Path => Unit): Unit =
    withTempFile(content.getBytes("UTF-8"))(f)

  // Reference digest helpers
  private def refMd5(bytes: Array[Byte]): String =
    MessageDigest.getInstance("MD5").digest(bytes).map("%02x".format(_)).mkString

  private def refSha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  // ============================================================================
  // CsvFormatter.progNameFromClassname via ArgsParser — lives here as a bonus
  // ============================================================================

  // ============================================================================
  // Hash64 — Hasher (streaming, Tier 1)
  // ============================================================================

  test("Hash64: short input (<64 bytes) produces a non-zero hash") {
    val h = Hash64(seed = 0L)
    h.update("hello world".getBytes("UTF-8"), 0, 11)
    val digest = h.digest()
    assertNotEquals(digest, 0L)
  }

  test("Hash64: same input same seed → identical digest") {
    val bytes = "determinism check".getBytes("UTF-8")
    def run() =
      val h = Hash64(seed = 42L)
      h.update(bytes, 0, bytes.length)
      h.digest()
    assertEquals(run(), run())
  }

  test("Hash64: different seeds → different digests for same input") {
    val bytes = "same data".getBytes("UTF-8")
    def run(seed: Long) =
      val h = Hash64(seed = seed)
      h.update(bytes, 0, bytes.length)
      h.digest()
    assertNotEquals(run(0L), run(1L))
  }

  test("Hash64: different inputs → different digests") {
    def run(s: String) =
      val bytes = s.getBytes("UTF-8")
      val h = Hash64(seed = 0L)
      h.update(bytes, 0, bytes.length)
      h.digest()
    assertNotEquals(run("abc"), run("xyz"))
  }

  test("Hash64: long input (>=64 bytes) produces a non-zero hash") {
    val bytes = Array.tabulate[Byte](128)(i => i.toByte)
    val h = Hash64(seed = 0L)
    h.update(bytes, 0, bytes.length)
    assertNotEquals(h.digest(), 0L)
  }

  test("Hash64: incremental update matches single update") {
    val bytes = Array.tabulate[Byte](200)(i => i.toByte)
    val hSingle = Hash64(seed = 0L)
    hSingle.update(bytes, 0, bytes.length)

    val hIncremental = Hash64(seed = 0L)
    hIncremental.update(bytes, 0, 100)
    hIncremental.update(bytes, 100, 100)

    assertEquals(hSingle.digest(), hIncremental.digest())
  }

  test("Hash64: empty input produces a deterministic hash") {
    val h1 = Hash64(seed = 0L)
    h1.update(Array.emptyByteArray, 0, 0)
    val h2 = Hash64(seed = 0L)
    h2.update(Array.emptyByteArray, 0, 0)
    assertEquals(h1.digest(), h2.digest())
  }

  // 64-byte boundary: digest branches on totalLen >= 64
  test("Hash64: 63-byte input uses short-path in digest (seed + PRIME64_5)") {
    val bytes = Array.tabulate[Byte](63)(i => i.toByte)
    val h = Hash64(seed = 0L)
    h.update(bytes, 0, 63)
    assertNotEquals(h.digest(), 0L)
  }

  test("Hash64: 64-byte input uses accumulator path in digest") {
    val bytes = Array.tabulate[Byte](64)(i => i.toByte)
    val h = Hash64(seed = 0L)
    h.update(bytes, 0, 64)
    assertNotEquals(h.digest(), 0L)
  }

  test("Hash64: 63 and 64 byte inputs produce different digests") {
    def run(n: Int) =
      val bytes = Array.tabulate[Byte](n)(i => i.toByte)
      val h = Hash64(seed = 0L)
      h.update(bytes, 0, n)
      h.digest()
    assertNotEquals(run(63), run(64))
  }

  test("Hash64: 65-byte input (one chunk + 1 remainder) is non-zero and distinct from 64") {
    def run(n: Int) =
      val bytes = Array.tabulate[Byte](n)(i => i.toByte)
      val h = Hash64(seed = 0L)
      h.update(bytes, 0, n)
      h.digest()
    assertNotEquals(run(65), 0L)
    assertNotEquals(run(65), run(64))
  }

  test("Hash64: non-zero offset in update is respected") {
    val payload = "xxxxxhello world".getBytes("UTF-8")
    val direct  = "hello world".getBytes("UTF-8")
    def run(bytes: Array[Byte], off: Int, len: Int) =
      val h = Hash64(seed = 0L)
      h.update(bytes, off, len)
      h.digest()
    assertEquals(run(payload, 5, 11), run(direct, 0, 11))
  }

  test("Hash64: zero-length update does not change digest") {
    val bytes = "data".getBytes("UTF-8")
    val hWithNoop = Hash64(seed = 0L)
    hWithNoop.update(bytes, 0, bytes.length)
    hWithNoop.update(Array.emptyByteArray, 0, 0)
    val hDirect = Hash64(seed = 0L)
    hDirect.update(bytes, 0, bytes.length)
    assertEquals(hWithNoop.digest(), hDirect.digest())
  }

  // ============================================================================
  // Hash64.hash64(file) — Tier 2 (file-based)
  // ============================================================================

  test("Hash64.hash64: happy path returns 16-char hex, no error") {
    withTempFile("hello hash64") { p =>
      val (hex, err) = Hash64.hash64(p.toFile)
      assertEquals(err, None)
      assertEquals(hex.length, 16)
      assert(hex.forall(c => "0123456789abcdef".contains(c)), s"not hex: $hex")
    }
  }

  test("Hash64.hash64: deterministic across two calls") {
    withTempFile("same content") { p =>
      val (h1, _) = Hash64.hash64(p.toFile)
      val (h2, _) = Hash64.hash64(p.toFile)
      assertEquals(h1, h2)
    }
  }

  test("Hash64.hash64: different file content → different hash") {
    withTempFile("content A") { p1 =>
      withTempFile("content B") { p2 =>
        val (h1, _) = Hash64.hash64(p1.toFile)
        val (h2, _) = Hash64.hash64(p2.toFile)
        assertNotEquals(h1, h2)
      }
    }
  }

  test("Hash64.hash64: missing file returns empty string and FileNotFoundException") {
    val missing = java.io.File("/nonexistent/path/does-not-exist-xyz.tmp")
    val (hex, err) = Hash64.hash64(missing)
    assertEquals(hex, "")
    assert(err.isDefined)
    assert(err.get.isInstanceOf[java.io.FileNotFoundException])
  }

  test("Hash64.hash64: empty file returns 16-char hex, no error") {
    withTempFile(Array.emptyByteArray) { p =>
      val (hex, err) = Hash64.hash64(p.toFile)
      assertEquals(err, None)
      assertEquals(hex.length, 16)
      assert(hex.forall(c => "0123456789abcdef".contains(c)), s"not hex: $hex")
    }
  }

  test("Hash64.hash64: file larger than 64 KB returns 16-char hex, no error") {
    // exercises the buffer-read loop in hash64 (buffer is 64 KB)
    val content = Array.tabulate[Byte](70 * 1024)(i => i.toByte)
    withTempFile(content) { p =>
      val (hex, err) = Hash64.hash64(p.toFile)
      assertEquals(err, None)
      assertEquals(hex.length, 16)
      assert(hex.forall(c => "0123456789abcdef".contains(c)), s"not hex: $hex")
    }
  }

  // ============================================================================
  // md5 — Tier 2
  // ============================================================================

  test("md5: known content matches reference MessageDigest output") {
    val content = "the quick brown fox"
    withTempFile(content) { p =>
      val expected = refMd5(content.getBytes("UTF-8"))
      assertEquals(md5(p), expected)
    }
  }

  test("md5: empty file returns known MD5 value") {
    withTempFile(Array.emptyByteArray) { p =>
      assertEquals(md5(p), "d41d8cd98f00b204e9800998ecf8427e")
    }
  }

  test("md5: deterministic across two calls") {
    withTempFile("repeat") { p =>
      assertEquals(md5(p), md5(p))
    }
  }

  test("md5: different content → different digest") {
    withTempFile("aaa") { p1 =>
      withTempFile("bbb") { p2 =>
        assertNotEquals(md5(p1), md5(p2))
      }
    }
  }

  // ============================================================================
  // sha256 — Tier 2
  // ============================================================================

  test("sha256: known content matches reference MessageDigest output") {
    val content = "the quick brown fox"
    withTempFile(content) { p =>
      val expected = refSha256(content.getBytes("UTF-8"))
      assertEquals(sha256(p), expected)
    }
  }

  test("sha256: empty file returns known SHA-256 value") {
    withTempFile(Array.emptyByteArray) { p =>
      assertEquals(sha256(p), "e3b0c44298fc1c149afbf4c8996fb924" +
                               "27ae41e4649b934ca495991b7852b855")
    }
  }

  test("sha256: deterministic across two calls") {
    withTempFile("repeat") { p =>
      assertEquals(sha256(p), sha256(p))
    }
  }

  test("sha256: different content → different digest") {
    withTempFile("aaa") { p1 =>
      withTempFile("bbb") { p2 =>
        assertNotEquals(sha256(p1), sha256(p2))
      }
    }
  }

  // ============================================================================
  // cksum — GNU reference values (from: printf 'input' | cksum)
  // ============================================================================

  test("cksum: empty input matches GNU cksum (4294967295, 0)") {
    assertEquals(cksum(Array.emptyByteArray), (4294967295L, 0L))
  }

  test("cksum: 'hello\\n' matches GNU cksum (3015617425, 6)") {
    assertEquals(cksum("hello\n".getBytes("UTF-8")), (3015617425L, 6L))
  }

  test("cksum: 'hello world' matches GNU cksum (1135714720, 11)") {
    assertEquals(cksum("hello world".getBytes("UTF-8")), (1135714720L, 11L))
  }

  test("cksum: 'the quick brown fox' matches GNU cksum (94151300, 19)") {
    assertEquals(cksum("the quick brown fox".getBytes("UTF-8")), (94151300L, 19L))
  }

  // ============================================================================
  // cksum — Tier 2
  // ============================================================================

  test("cksum: length field matches actual content size") {
    val content = "hello cksum"
    withTempFile(content) { p =>
      val (_, len) = cksum(p)
      assertEquals(len, content.getBytes("UTF-8").length.toLong)
    }
  }

  test("cksum: crc is in 32-bit unsigned range [0, 4294967295]") {
    withTempFile("range check") { p =>
      val (crc, _) = cksum(p)
      assert(crc >= 0L && crc <= 0xFFFFFFFFL, s"crc out of range: $crc")
    }
  }

  test("cksum: deterministic across two calls") {
    withTempFile("cksum determinism") { p =>
      assertEquals(cksum(p), cksum(p))
    }
  }

  test("cksum: different content → different crc") {
    withTempFile("content one") { p1 =>
      withTempFile("content two") { p2 =>
        val (c1, _) = cksum(p1)
        val (c2, _) = cksum(p2)
        assertNotEquals(c1, c2)
      }
    }
  }

  test("cksum: empty file has length 0") {
    withTempFile(Array.emptyByteArray) { p =>
      val (_, len) = cksum(p)
      assertEquals(len, 0L)
    }
  }

  // ============================================================================
  // Cksum.apply(Array[Byte]) — Tier 1
  // ============================================================================

  test("cksum(Array): length matches input size") {
    val content = "hello cksum apply".getBytes("UTF-8")
    val (_, len) = cksum(content)
    assertEquals(len, content.length.toLong)
  }

  test("cksum(Array): crc is in 32-bit unsigned range [0, 4294967295]") {
    val content = "range check array".getBytes("UTF-8")
    val (crc, _) = cksum(content)
    assert(crc >= 0L && crc <= 0xFFFFFFFFL, s"crc out of range: $crc")
  }

  test("cksum(Array): deterministic") {
    val content = "determinism array".getBytes("UTF-8")
    assertEquals(cksum(content), cksum(content))
  }

  test("cksum(Array): different content → different crc") {
    assertNotEquals(cksum("abc".getBytes("UTF-8"))._1, cksum("xyz".getBytes("UTF-8"))._1)
  }

  test("cksum(Array): empty array → crc 4294967295, length 0") {
    // computeFinal(0, 0): length loop does not execute → ~0L & 0xFFFFFFFFL = 4294967295
    assertEquals(cksum(Array.emptyByteArray), (4294967295L, 0L))
  }

  // ============================================================================
  // Cksum.apply(Iterator[Byte]) — Tier 1
  // ============================================================================

  test("cksum(Iterator): length matches input size") {
    val content = "hello cksum iterator".getBytes("UTF-8")
    val (_, len) = cksum(content.iterator)
    assertEquals(len, content.length.toLong)
  }

  test("cksum(Iterator): crc is in 32-bit unsigned range [0, 4294967295]") {
    val content = "range check iterator".getBytes("UTF-8")
    val (crc, _) = cksum(content.iterator)
    assert(crc >= 0L && crc <= 0xFFFFFFFFL, s"crc out of range: $crc")
  }

  test("cksum(Iterator): deterministic") {
    val content = "determinism iterator".getBytes("UTF-8")
    assertEquals(cksum(content.iterator), cksum(content.iterator))
  }

  test("cksum(Iterator): different content → different crc") {
    assertNotEquals(cksum("abc".getBytes("UTF-8").iterator)._1, cksum("xyz".getBytes("UTF-8").iterator)._1)
  }

  test("cksum(Iterator): empty iterator → crc 4294967295, length 0") {
    assertEquals(cksum(Iterator.empty[Byte]), (4294967295L, 0L))
  }

  // ============================================================================
  // Cross-consistency: Array vs Iterator vs file
  // ============================================================================

  test("Cksum: Array and Iterator agree on same data") {
    val content = "cross check array vs iterator".getBytes("UTF-8")
    assertEquals(cksum(content), cksum(content.iterator))
  }

  test("Cksum: Array and file-based agree on same data") {
    val content = "cross check array vs file".getBytes("UTF-8")
    withTempFile(content) { p =>
      assertEquals(cksum(content), cksum(p))
    }
  }

  test("Cksum: Iterator and file-based agree on same data") {
    val content = "cross check iterator vs file".getBytes("UTF-8")
    withTempFile(content) { p =>
      assertEquals(cksum(content.iterator), cksum(p))
    }
  }

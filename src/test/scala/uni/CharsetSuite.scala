package uni

import java.nio.file.Files
import java.nio.charset.StandardCharsets
import munit.FunSuite

import uni.*

/** What the charset argument does, for a charset wider than one byte.
  *
  * A byte-oriented line reader splits on `0x0A` before decoding, which is unsound for UTF-16: the
  * byte is half of a code unit there, so the split cuts a character in two. This used to yield the
  * raw bytes with embedded NULs, via the reader's Latin-1 fallback. Wide charsets now decode the
  * whole file and split the text, which costs laziness and is the only way to be correct.
  *
  * Mirrored by `rust/tests/upath_charset.rs`.
  */
class CharsetSuite extends FunSuite:

  private def tmp(name: String, bytes: Array[Byte]) =
    val p = Files.createTempFile(name, ".txt")
    Files.write(p, bytes)
    p

  private val text = "alpha\nbeta\n"

  test("UTF-16BE: lines decode as text, not as bytes") {
    val p = tmp("u16be", text.getBytes(StandardCharsets.UTF_16BE))
    assertEquals(p.lines("UTF-16BE"), Seq("alpha", "beta"))
    assertEquals(p.linesStream("UTF-16BE").toSeq, Seq("alpha", "beta"))
    assertEquals(p.withLines("UTF-16BE")(_.toList), List("alpha", "beta"))
    assertEquals(p.contentAsString(StandardCharsets.UTF_16BE), text)
    // The regression this guards: a NUL inside a line means the bytes were never decoded.
    assert(!p.lines("UTF-16BE").exists(_.contains('\u0000')), "no raw bytes leaking through")
  }

  test("UTF-16LE and UTF-16 with a BOM") {
    val le = tmp("u16le", text.getBytes(StandardCharsets.UTF_16LE))
    assertEquals(le.lines("UTF-16LE"), Seq("alpha", "beta"))
    // Java's UTF-16 encoder emits a big-endian BOM, and its decoder consumes one.
    val bom = tmp("u16bom", text.getBytes(StandardCharsets.UTF_16))
    assertEquals(bom.lines("UTF-16"), Seq("alpha", "beta"))
    assert(!bom.lines("UTF-16").head.startsWith("\ufeff"), "the BOM is consumed, not returned")
  }

  test("single-byte charsets still stream, and are unchanged") {
    val p = tmp("latin1", "caf\u00e9\nbar\n".getBytes(StandardCharsets.ISO_8859_1))
    assertEquals(p.lines("iso-8859-1"), Seq("caf\u00e9", "bar"))
    assertEquals(p.lines("UTF-8").length, 2, "invalid UTF-8 falls back per line rather than failing")
  }

  test("an unknown charset name falls back to UTF-8 rather than throwing") {
    val p = tmp("plain", "one\ntwo\n".getBytes(StandardCharsets.UTF_8))
    assertEquals(p.lines("NoSuchCharsetExists"), Seq("one", "two"))
  }

  test("an odd byte count is empty rather than an exception") {
    // UTF-16 needs pairs; Files.readString raises, and contentAsString maps that to empty.
    val p = tmp("odd", Array[Byte](0x00, 0x61, 0x00))
    assertEquals(p.contentAsString(StandardCharsets.UTF_16BE), "")
    assertEquals(p.lines("UTF-16BE"), Seq.empty[String])
  }

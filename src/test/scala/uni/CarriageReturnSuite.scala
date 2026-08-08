package uni

import java.nio.file.Files
import java.nio.charset.StandardCharsets
import munit.FunSuite

import uni.*

/** The carriage-return rule, pinned on the Scala side.
  *
  * A line-oriented reader here behaves as `split("\r?\n")` does: the only CR removed is the one a
  * newline consumed. An interior CR survives, and so does a trailing CR at end-of-file, neither
  * having a newline to pair with. Stated as a pattern because "remove every CR" is a different rule
  * and disagrees with it -- see docs/PathIOReference.md.
  *
  * This mirrors `rust/tests/upath_lines.rs` case for case, so the two languages cannot drift.
  */
class CarriageReturnSuite extends FunSuite:

  /** Interior CR, a CRLF terminator, then a second interior CR. */
  private val mixedBytes = "a\rb\nc\r\nd\re\n".getBytes(StandardCharsets.UTF_8)
  private val expected   = Seq("a\rb", "c", "d\re")

  private def tmpWith(name: String, bytes: Array[Byte]) =
    val p = Files.createTempFile(name, ".txt")
    Files.write(p, bytes)
    p

  test("lines keeps an interior carriage return, drops a terminator's") {
    val p = tmpWith("mixed", mixedBytes)
    assertEquals(p.lines, expected)
    assertEquals(p.linesStream.toSeq, expected, "linesStream agrees with lines")
    assertEquals(p.firstLine, "a\rb", "firstLine")
  }

  test("eachLine and withLines agree with lines") {
    val p = tmpWith("mixed", mixedBytes)
    val collected = scala.collection.mutable.ArrayBuffer.empty[String]
    p.eachLine(l => collected += l)
    assertEquals(collected.toSeq, expected, "eachLine")
    assertEquals(p.withLines(_.length), 3, "withLines sees three lines")
  }

  test("a trailing carriage return at end-of-file is data") {
    // No newline follows this CR, so the split never consumes it. "Remove every CR" would eat it.
    val p = tmpWith("eofcr", "one\ntwo\r".getBytes(StandardCharsets.UTF_8))
    assertEquals(p.lines, Seq("one", "two\r"))
    // The identical CR, now paired with a newline, is a terminator and goes.
    val q = tmpWith("crlf", "one\ntwo\r\n".getBytes(StandardCharsets.UTF_8))
    assertEquals(q.lines, Seq("one", "two"))
  }

  test("line endings do not change the lines") {
    val unix = tmpWith("unix", "one\ntwo\nthree\n".getBytes(StandardCharsets.UTF_8))
    val dos  = tmpWith("dos", "one\r\ntwo\r\nthree\r\n".getBytes(StandardCharsets.UTF_8))
    assertEquals(unix.lines, Seq("one", "two", "three"))
    assertEquals(dos.lines, unix.lines, "CRLF matches LF")
  }

  test("contentAsString and byteArray keep the bytes exactly") {
    val p = tmpWith("mixed", mixedBytes)
    assertEquals(p.byteArray.toSeq, mixedBytes.toSeq, "byteArray is untouched")
    assert(p.contentAsString.contains('\r'), "contentAsString keeps CR")
  }

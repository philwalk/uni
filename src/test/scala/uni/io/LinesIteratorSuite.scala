package uni.io

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets

class LinesIteratorSuite extends FunSuite:

  // deleteOnExit so Windows MappedByteBuffer locks don't cause "file in use" on cleanup
  private def tempFile(content: String): Path =
    val p = Files.createTempFile("lines-suite-", ".txt")
    p.toFile.deleteOnExit()
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    p

  // ============================================================================
  // FileChannelIterator — basic reading
  // ============================================================================

  test("FileChannelIterator: reads all lines of a simple file") {
    val p = tempFile("alpha\nbeta\ngamma")
    val iter = LinesIterator.FileChannelIterator(p)
    val lines = iter.toList
    iter.close()
    assertEquals(lines, List("alpha", "beta", "gamma"))
  }

  test("FileChannelIterator: trailing newline does not produce extra empty line") {
    val p = tempFile("hello\n")
    val lines = LinesIterator.FileChannelIterator(p).toList
    assertEquals(lines, List("hello"))
  }

  test("FileChannelIterator: empty file yields no lines") {
    val p = tempFile("")
    val iter = LinesIterator.FileChannelIterator(p)
    val lines = iter.toList
    iter.close()
    assert(lines.isEmpty)
  }

  test("FileChannelIterator: single line without trailing newline") {
    val p = tempFile("no-newline")
    val lines = LinesIterator.FileChannelIterator(p).toList
    assertEquals(lines, List("no-newline"))
  }

  test("FileChannelIterator(pathString): reads via string path") {
    val p = tempFile("str\npath\n")
    val lines = LinesIterator.FileChannelIterator(p.toAbsolutePath.toString).toList
    assertEquals(lines, List("str", "path"))
  }

  // ============================================================================
  // FileChannelIterate — memory-mapped variant
  // ============================================================================

  test("FileChannelIterate: reads all lines") {
    val p = tempFile("one\ntwo\nthree\n")
    val iter = LinesIterator.FileChannelIterate(p)
    val lines = iter.toList
    iter.close()
    assertEquals(lines, List("one", "two", "three"))
  }

  test("FileChannelIterate: empty file yields no lines") {
    val p = tempFile("")
    val iter = LinesIterator.FileChannelIterate(p)
    val lines = iter.toList
    iter.close()
    assert(lines.isEmpty)
  }

  test("FileChannelIterate(pathString): reads via string path") {
    val p = tempFile("a\nb\n")
    val lines = LinesIterator.FileChannelIterate(p.toAbsolutePath.toString).toList
    assertEquals(lines, List("a", "b"))
  }

  // ============================================================================
  // normalize
  // ============================================================================

  test("normalize: CRLF → LF") {
    assertEquals(LinesIterator.normalize("a\r\nb"), "a\nb")
  }

  test("normalize: lone CR → LF") {
    assertEquals(LinesIterator.normalize("a\rb"), "a\nb")
  }

  test("normalize: strips trailing whitespace") {
    assertEquals(LinesIterator.normalize("hello   "), "hello")
  }

  test("normalize: plain string is unchanged") {
    assertEquals(LinesIterator.normalize("hello"), "hello")
  }

  test("normalize: empty string") {
    assertEquals(LinesIterator.normalize(""), "")
  }

  // ============================================================================
  // iterateLines — Option[Path] variant
  // ============================================================================

  test("iterateLines(Some(path)): invokes lineProcessor for each line") {
    val p = tempFile("x\ny\nz\n")
    val buf = scala.collection.mutable.ArrayBuffer.empty[String]
    LinesIterator.iterateLines(Some(p)) { (line, _) => buf += line }
    assertEquals(buf.toList, List("x", "y", "z"))
  }

  // ============================================================================
  // iterateLines — String variant
  // ============================================================================

  test("iterateLines(String): invokes lineProcessor for each line") {
    val p = tempFile("p\nq\n")
    val buf = scala.collection.mutable.ArrayBuffer.empty[String]
    LinesIterator.iterateLines(p.toAbsolutePath.toString) { line => buf += line }
    assertEquals(buf.toList, List("p", "q"))
  }

  // ============================================================================
  // StdinIterator — close is a no-op
  // ============================================================================

  test("StdinIterator.close does not throw") {
    val stdin = new LinesIterator.StdinIterator()
    stdin.close()
  }

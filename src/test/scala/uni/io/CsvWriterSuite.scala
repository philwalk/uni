package uni.io

import munit.FunSuite
import java.io.StringWriter

class CsvWriterSuite extends FunSuite:

  private def mkWriter(delim: Char = ',', quote: Char = '"'): (CsvWriter, StringWriter) =
    val sw = new StringWriter()
    (new CsvWriter(sw, delim, quote), sw)

  // ============================================================================
  // writeRow — basic output
  // ============================================================================

  test("writeRow: plain multi-field row") {
    val (cw, sw) = mkWriter()
    cw.writeRow(Seq("hello", "world"))
    assertEquals(sw.toString, "hello,world\n")
  }

  test("writeRow: single field") {
    val (cw, sw) = mkWriter()
    cw.writeRow(Seq("only"))
    assertEquals(sw.toString, "only\n")
  }

  test("writeRow: empty sequence produces bare newline") {
    val (cw, sw) = mkWriter()
    cw.writeRow(Seq.empty)
    assertEquals(sw.toString, "\n")
  }

  // ============================================================================
  // writeField quoting branches (via writeRow)
  // ============================================================================

  test("writeRow: field containing delimiter is quoted") {
    val (cw, sw) = mkWriter()
    cw.writeRow(Seq("a,b"))
    assertEquals(sw.toString, "\"a,b\"\n")
  }

  test("writeRow: field containing quoteChar is quoted with internal doubling") {
    val (cw, sw) = mkWriter()
    cw.writeRow(Seq("say \"hi\""))
    assertEquals(sw.toString, "\"say \"\"hi\"\"\"\n")
  }

  test("writeRow: field containing newline is quoted") {
    val (cw, sw) = mkWriter()
    cw.writeRow(Seq("a\nb"))
    assertEquals(sw.toString, "\"a\nb\"\n")
  }

  test("writeRow: field containing carriage return is quoted") {
    val (cw, sw) = mkWriter()
    cw.writeRow(Seq("a\rb"))
    assertEquals(sw.toString, "\"a\rb\"\n")
  }

  test("writeRow: leading whitespace triggers quoting") {
    val (cw, sw) = mkWriter()
    cw.writeRow(Seq(" leading"))
    assertEquals(sw.toString, "\" leading\"\n")
  }

  test("writeRow: trailing whitespace triggers quoting") {
    val (cw, sw) = mkWriter()
    cw.writeRow(Seq("trailing "))
    assertEquals(sw.toString, "\"trailing \"\n")
  }

  test("writeRow: empty field is not quoted") {
    val (cw, sw) = mkWriter()
    cw.writeRow(Seq("a", "", "b"))
    assertEquals(sw.toString, "a,,b\n")
  }

  // ============================================================================
  // Custom delimiter and quoteChar
  // ============================================================================

  test("writeRow: custom tab delimiter") {
    val (cw, sw) = mkWriter(delim = '\t')
    cw.writeRow(Seq("x", "y", "z"))
    assertEquals(sw.toString, "x\ty\tz\n")
  }

  test("writeRow: custom quoteChar escapes correctly") {
    val (cw, sw) = mkWriter(quote = '\'')
    cw.writeRow(Seq("it's"))
    // field contains '\'' → quoted, internal ' doubled
    assertEquals(sw.toString, "'it''s'\n")
  }

  // ============================================================================
  // Multi-row accumulation, flush, close
  // ============================================================================

  test("writeRow: multiple rows accumulate correctly") {
    val (cw, sw) = mkWriter()
    cw.writeRow(Seq("a", "b"))
    cw.writeRow(Seq("c", "d"))
    assertEquals(sw.toString, "a,b\nc,d\n")
  }

  test("flush and close do not throw") {
    val (cw, _) = mkWriter()
    cw.writeRow(Seq("ok"))
    cw.flush()
    cw.close()
  }

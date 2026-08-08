package uni.io

import munit.FunSuite

class CsvFormatterSuite extends FunSuite:

  // ============================================================================
  // formatRow — basic delimiter and multi-field behaviour
  // ============================================================================

  test("formatRow: plain fields need no quoting") {
    assertEquals(CsvFormatter.formatRow(Seq("hello", "world")), "hello,world")
  }

  test("formatRow: empty sequence returns empty string") {
    assertEquals(CsvFormatter.formatRow(Seq.empty), "")
  }

  test("formatRow: single field, no special chars") {
    assertEquals(CsvFormatter.formatRow(Seq("abc")), "abc")
  }

  test("formatRow: custom delimiter is inserted between fields") {
    assertEquals(CsvFormatter.formatRow(Seq("a", "b", "c"), '\t'), "a\tb\tc")
  }

  // ============================================================================
  // quoteIfNeeded — each branch exercised via formatRow
  // ============================================================================

  test("formatRow: field containing delimiter is quoted") {
    // default delimiter ','
    assertEquals(CsvFormatter.formatRow(Seq("a,b")), "\"a,b\"")
  }

  test("formatRow: field containing double-quote is quoted and quote is doubled") {
    assertEquals(CsvFormatter.formatRow(Seq("say \"hi\"")), "\"say \"\"hi\"\"\"")
  }

  test("formatRow: field containing newline is quoted") {
    assertEquals(CsvFormatter.formatRow(Seq("line1\nline2")), "\"line1\nline2\"")
  }

  test("formatRow: field containing carriage return is quoted") {
    assertEquals(CsvFormatter.formatRow(Seq("line1\rline2")), "\"line1\rline2\"")
  }

  test("formatRow: field with leading space is quoted") {
    assertEquals(CsvFormatter.formatRow(Seq(" leading")), "\" leading\"")
  }

  test("formatRow: field with trailing space is quoted") {
    assertEquals(CsvFormatter.formatRow(Seq("trailing ")), "\"trailing \"")
  }

  test("formatRow: empty field (empty string) needs no quoting") {
    // empty string: nonEmpty is false, so whitespace check is skipped → no quote
    assertEquals(CsvFormatter.formatRow(Seq("")), "")
  }

  test("formatRow: mixed plain and special fields") {
    val row = Seq("plain", "has,comma", "42")
    assertEquals(CsvFormatter.formatRow(row), "plain,\"has,comma\",42")
  }

  test("formatRow: custom delimiter triggers quoting only when field contains that delimiter") {
    // field contains ',' but delimiter is tab — no quoting needed
    assertEquals(CsvFormatter.formatRow(Seq("a,b"), '\t'), "a,b")
    // field contains tab — quoting is needed
    assertEquals(CsvFormatter.formatRow(Seq("a\tb"), '\t'), "\"a\tb\"")
  }

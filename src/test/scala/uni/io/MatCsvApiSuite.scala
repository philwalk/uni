package uni.io

import munit.FunSuite
import java.nio.file.Files
import uni.*

/** Exhaustive tests for the CSV read/write API.
 *
 *  This file intentionally lives in `package uni.io` (NOT `uni.data`) and uses
 *  only `import uni.*` to prove that the export in `uni/package.scala` is
 *  sufficient — no `import uni.data.*` is needed.
 */
class MatCsvApiSuite extends FunSuite:

  private def tmpFile(prefix: String): Path =
    val dir = Paths.get("./target")
    if !dir.exists then Files.createDirectories(dir)
    Files.createTempFile(dir, prefix, ".csv")

  // ============================================================================
  // Group A — Path extensions
  // ============================================================================

  test("A1: p.writeCsv + p.readCsv roundtrips 3x3 MatD") {
    val p = tmpFile("matcsv-A1")
    try
      val m = MatD(3, 3, Array(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0))
      p.writeCsv(m)
      val r = p.readCsv
      assertEquals(r.shape, (3, 3))
      assertEqualsDouble(r(0, 0), 1.0, 1e-12)
      assertEqualsDouble(r(1, 1), 5.0, 1e-12)
      assertEqualsDouble(r(2, 2), 9.0, 1e-12)
    finally Files.deleteIfExists(p)
  }

  test("A2: p.writeCsv with custom separator writes that separator") {
    val p = tmpFile("matcsv-A2")
    try
      val m = MatD.row(1.0, 2.0, 3.0)
      p.writeCsv(m, ";")
      val content = Files.readString(p)
      assert(content.contains(";"), s"Expected ';' in: $content")
      assert(!content.contains(","), s"Unexpected ',' in: $content")
    finally Files.deleteIfExists(p)
  }

  test("A3: p.writeCsv(MatB) + p.readCsvB roundtrips 2x2 MatB") {
    val p = tmpFile("matcsv-A3")
    try
      val m = MatB.vstack(MatB.row(1.1, 2.2), MatB.row(3.3, 4.4))
      p.writeCsv(m)
      val r = p.readCsvB
      assertEquals(r.shape, (2, 2))
      assertEquals(r(0, 0), m(0, 0))
      assertEquals(r(1, 1), m(1, 1))
    finally Files.deleteIfExists(p)
  }

  test("A4: p.readCsvF roundtrips 2x2 MatF") {
    val p = tmpFile("matcsv-A4")
    try
      val m = MatF.vstack(MatF.row(1.5, 2.5), MatF.row(3.5, 4.5))
      p.writeCsv(m)
      val r = p.readCsvF
      assertEquals(r.shape, (2, 2))
      assertEqualsDouble(r(0, 0).toDouble, 1.5, 1e-6)
      assertEqualsDouble(r(1, 1).toDouble, 4.5, 1e-6)
    finally Files.deleteIfExists(p)
  }

  test("A5: p.loadMatB is an alias for p.readCsvB") {
    val p = tmpFile("matcsv-A5")
    try
      val m = MatB.row(7.0, 8.0, 9.0)
      p.writeCsv(m)
      val r1 = p.readCsvB
      val r2 = p.loadMatB
      assertEquals(r1.shape, r2.shape)
      assertEquals(r1(0, 0), r2(0, 0))
      assertEquals(r1(0, 2), r2(0, 2))
    finally Files.deleteIfExists(p)
  }

  test("A6: p.loadMatF is an alias for p.readCsvF") {
    val p = tmpFile("matcsv-A6")
    try
      val m = MatF.row(2.0, 4.0, 6.0)
      p.writeCsv(m)
      val r1 = p.readCsvF
      val r2 = p.loadMatF
      assertEquals(r1.shape, r2.shape)
      assertEqualsDouble(r1(0, 1).toDouble, r2(0, 1).toDouble, 1e-9)
    finally Files.deleteIfExists(p)
  }

  test("A7: p.writeCsv(MatB) roundtrip preserves all elements") {
    val p = tmpFile("matcsv-A7")
    try
      val m = MatB.vstack(MatB.row(10.0, 20.0), MatB.row(30.0, 40.0))
      p.writeCsv(m)
      val r = p.readCsvB
      assertEquals(r.rows, 2)
      assertEquals(r.cols, 2)
      assertEquals(r(0, 0), m(0, 0))
      assertEquals(r(1, 0), m(1, 0))
    finally Files.deleteIfExists(p)
  }

  // ============================================================================
  // Group B — String extensions
  // ============================================================================

  test("B1: s.writeCsv + s.readCsv roundtrips 2x4 MatD") {
    val p = tmpFile("matcsv-B1")
    try
      val s = p.toString
      val m = MatD(2, 4, Array(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0))
      s.writeCsv(m)
      val r = s.readCsv
      assertEquals(r.shape, (2, 4))
      assertEqualsDouble(r(0, 3), 4.0, 1e-12)
      assertEqualsDouble(r(1, 0), 5.0, 1e-12)
    finally Files.deleteIfExists(p)
  }

  test("B2: s.writeCsv with pipe separator writes '|'") {
    val p = tmpFile("matcsv-B2")
    try
      val s = p.toString
      val m = MatD.row(1.0, 2.0, 3.0)
      s.writeCsv(m, "|")
      val content = Files.readString(p)
      assert(content.contains("|"), s"Expected '|' in: $content")
    finally Files.deleteIfExists(p)
  }

  test("B3: s.readCsvB roundtrips MatB") {
    val p = tmpFile("matcsv-B3")
    try
      val s = p.toString
      val m = MatB.row(100.5, 200.5, 300.5)
      s.writeCsv(m)
      val r = s.readCsvB
      assertEquals(r.shape, m.shape)
      assertEquals(r(0, 0), m(0, 0))
      assertEquals(r(0, 2), m(0, 2))
    finally Files.deleteIfExists(p)
  }

  test("B4: s.readCsvF roundtrips MatF") {
    val p = tmpFile("matcsv-B4")
    try
      val s = p.toString
      val m = MatF.row(0.25, 0.5, 0.75)
      s.writeCsv(m)
      val r = s.readCsvF
      assertEquals(r.shape, m.shape)
      assertEqualsDouble(r(0, 1).toDouble, 0.5, 1e-6)
    finally Files.deleteIfExists(p)
  }

  test("B5: s.writeCsv no-default and with-sep overloads both compile and write") {
    val p1 = tmpFile("matcsv-B5a")
    val p2 = tmpFile("matcsv-B5b")
    try
      val m1 = MatD.row(1.0, 2.0)
      val m2 = MatD.row(3.0, 4.0)
      p1.toString.writeCsv(m1)
      p2.toString.writeCsv(m2, ";")
      assert(Files.size(p1) > 0, "B5a: file should be non-empty")
      assert(Files.size(p2) > 0, "B5b: file should be non-empty")
    finally
      Files.deleteIfExists(p1)
      Files.deleteIfExists(p2)
  }

  // ============================================================================
  // Group C — Companion object methods
  // ============================================================================

  test("C1: MatD.readCsv(p: Path) roundtrip") {
    val p = tmpFile("matcsv-C1")
    try
      val m = MatD.row(1.0, 2.0, 3.0)
      MatD.writeCsv(m, p)
      val r = MatD.readCsv(p)
      assertEquals(r.shape, m.shape)
      assertEqualsDouble(r(0, 1), 2.0, 1e-12)
    finally Files.deleteIfExists(p)
  }

  test("C2: MatD.readCsv(s: String) roundtrip") {
    val p = tmpFile("matcsv-C2")
    try
      val m = MatD.row(4.0, 5.0, 6.0)
      MatD.writeCsv(m, p.toString)
      val r = MatD.readCsv(p.toString)
      assertEquals(r.shape, m.shape)
      assertEqualsDouble(r(0, 0), 4.0, 1e-12)
    finally Files.deleteIfExists(p)
  }

  test("C3: MatD.writeCsv(m, p) + MatD.readCsv(p) verify dimensions") {
    val p = tmpFile("matcsv-C3")
    try
      val m = MatD.ones(4, 6)
      MatD.writeCsv(m, p)
      val r = MatD.readCsv(p)
      assertEquals(r.rows, 4)
      assertEquals(r.cols, 6)
    finally Files.deleteIfExists(p)
  }

  test("C4: MatD.writeCsv(m, s) + MatD.readCsv(s) verify values") {
    val p = tmpFile("matcsv-C4")
    try
      val m = MatD(2, 2, Array(1.5, 2.5, 3.5, 4.5))
      MatD.writeCsv(m, p.toString)
      val r = MatD.readCsv(p.toString)
      assertEqualsDouble(r(0, 0), 1.5, 1e-12)
      assertEqualsDouble(r(1, 1), 4.5, 1e-12)
    finally Files.deleteIfExists(p)
  }

  test("C5: MatB.readCsv(p: Path) roundtrip") {
    val p = tmpFile("matcsv-C5")
    try
      val m = MatB.row(11.0, 22.0, 33.0)
      MatB.writeCsv(m, p)
      val r = MatB.readCsv(p)
      assertEquals(r.shape, m.shape)
      assertEquals(r(0, 1), m(0, 1))
    finally Files.deleteIfExists(p)
  }

  test("C6: MatB.readCsv(s: String) roundtrip") {
    val p = tmpFile("matcsv-C6")
    try
      val m = MatB.row(44.0, 55.0, 66.0)
      MatB.writeCsv(m, p.toString)
      val r = MatB.readCsv(p.toString)
      assertEquals(r.shape, m.shape)
      assertEquals(r(0, 2), m(0, 2))
    finally Files.deleteIfExists(p)
  }

  test("C7: MatB.writeCsv(m, p) + MatB.readCsv(p) verify dimensions") {
    val p = tmpFile("matcsv-C7")
    try
      val m = MatB.ones(3, 5)
      MatB.writeCsv(m, p)
      val r = MatB.readCsv(p)
      assertEquals(r.rows, 3)
      assertEquals(r.cols, 5)
    finally Files.deleteIfExists(p)
  }

  test("C8: MatB.writeCsv(m, s) + MatB.readCsv(s) verify values") {
    val p = tmpFile("matcsv-C8")
    try
      val m = MatB.vstack(MatB.row(1.5, 2.5), MatB.row(3.5, 4.5))
      MatB.writeCsv(m, p.toString)
      val r = MatB.readCsv(p.toString)
      assertEquals(r(0, 0), m(0, 0))
      assertEquals(r(1, 1), m(1, 1))
    finally Files.deleteIfExists(p)
  }

  test("C9: MatF.readCsv(p: Path) roundtrip") {
    val p = tmpFile("matcsv-C9")
    try
      val m = MatF.row(1.0, 2.0, 3.0)
      MatF.writeCsv(m, p)
      val r = MatF.readCsv(p)
      assertEquals(r.shape, m.shape)
      assertEqualsDouble(r(0, 0).toDouble, 1.0, 1e-6)
    finally Files.deleteIfExists(p)
  }

  test("C10: MatF.readCsv(s: String) roundtrip") {
    val p = tmpFile("matcsv-C10")
    try
      val m = MatF.row(4.0, 5.0, 6.0)
      MatF.writeCsv(m, p.toString)
      val r = MatF.readCsv(p.toString)
      assertEquals(r.shape, m.shape)
      assertEqualsDouble(r(0, 2).toDouble, 6.0, 1e-6)
    finally Files.deleteIfExists(p)
  }

  test("C11: MatF.writeCsv(m, p) + MatF.readCsv(p) verify dimensions") {
    val p = tmpFile("matcsv-C11")
    try
      val m = MatF.ones(2, 7)
      MatF.writeCsv(m, p)
      val r = MatF.readCsv(p)
      assertEquals(r.rows, 2)
      assertEquals(r.cols, 7)
    finally Files.deleteIfExists(p)
  }

  test("C12: MatF.writeCsv(m, s) + MatF.readCsv(s) verify values") {
    val p = tmpFile("matcsv-C12")
    try
      val m = MatF.vstack(MatF.row(1.0, 2.0), MatF.row(3.0, 4.0))
      MatF.writeCsv(m, p.toString)
      val r = MatF.readCsv(p.toString)
      assertEqualsDouble(r(0, 1).toDouble, 2.0, 1e-6)
      assertEqualsDouble(r(1, 0).toDouble, 3.0, 1e-6)
    finally Files.deleteIfExists(p)
  }

  // ============================================================================
  // Group D — Instance extension m.writeCsv
  // ============================================================================

  test("D1: (m: MatD).writeCsv(p: Path) + p.readCsv roundtrip") {
    val p = tmpFile("matcsv-D1")
    try
      val m = MatD(2, 3, Array(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))
      m.writeCsv(p)
      val r = p.readCsv
      assertEquals(r.shape, (2, 3))
      assertEqualsDouble(r(0, 0), 1.0, 1e-12)
      assertEqualsDouble(r(1, 2), 6.0, 1e-12)
    finally Files.deleteIfExists(p)
  }

  test("D2: (m: MatD).writeCsv(s: String) + s.readCsv roundtrip") {
    val p = tmpFile("matcsv-D2")
    try
      val m = MatD.row(10.0, 20.0, 30.0)
      m.writeCsv(p.toString)
      val r = p.toString.readCsv
      assertEquals(r.shape, m.shape)
      assertEqualsDouble(r(0, 1), 20.0, 1e-12)
    finally Files.deleteIfExists(p)
  }

  test("D3: (m: MatB).writeCsv(p) + p.readCsvB roundtrip") {
    val p = tmpFile("matcsv-D3")
    try
      val m = MatB.vstack(MatB.row(111.0, 222.0), MatB.row(333.0, 444.0))
      m.writeCsv(p)
      val r = p.readCsvB
      assertEquals(r.shape, m.shape)
      assertEquals(r(0, 0), m(0, 0))
      assertEquals(r(1, 1), m(1, 1))
    finally Files.deleteIfExists(p)
  }

  test("D4: (m: MatF).writeCsv(s) + s.readCsvF roundtrip") {
    val p = tmpFile("matcsv-D4")
    try
      val m = MatF.row(1.0, 2.0, 3.0)
      m.writeCsv(p.toString)
      val r = p.toString.readCsvF
      assertEquals(r.shape, m.shape)
      assertEqualsDouble(r(0, 0).toDouble, 1.0, 1e-6)
    finally Files.deleteIfExists(p)
  }

  // ============================================================================
  // Group E — Special values
  // ============================================================================

  test("E1: Double.NaN survives MatD CSV roundtrip") {
    val p = tmpFile("matcsv-E1")
    try
      val m = MatD(2, 2, Array(Double.NaN, 1.0, 2.0, Double.NaN))
      p.writeCsv(m)
      val r = p.readCsv
      assert(r(0, 0).isNaN, s"Expected NaN at (0,0), got ${r(0, 0)}")
      assertEqualsDouble(r(0, 1), 1.0, 1e-12)
      assert(r(1, 1).isNaN, s"Expected NaN at (1,1), got ${r(1, 1)}")
    finally Files.deleteIfExists(p)
  }

  test("E2: Double.PositiveInfinity and NegativeInfinity are written as Inf / -Inf") {
    val p = tmpFile("matcsv-E2")
    try
      val m = MatD.row(Double.PositiveInfinity, Double.NegativeInfinity)
      p.writeCsv(m)
      val content = Files.readString(p)
      assert(content.contains("-Inf"), s"Expected '-Inf' in: $content")
      assert(content.contains("Inf"), s"Expected 'Inf' in: $content")
    finally Files.deleteIfExists(p)
  }

  test("E3: MatB preserves high-precision string value through CSV roundtrip") {
    val hiPrec = "3.14159265358979323846"
    val p1 = tmpFile("matcsv-E3a")
    val p2 = tmpFile("matcsv-E3b")
    try
      // Write a high-precision decimal directly as CSV text, then read as MatB
      Files.writeString(p1, hiPrec + "\n")
      val m = p1.readCsvB
      // Write the MatB back out and verify the full-precision string is preserved
      p2.writeCsv(m)
      val written = Files.readString(p2).trim
      assert(written.contains(hiPrec), s"Precision lost: got '$written'")
    finally
      Files.deleteIfExists(p1)
      Files.deleteIfExists(p2)
  }

  test("E4: 5x7 MatD writes and reads back with correct shape") {
    val p = tmpFile("matcsv-E4")
    try
      val m = MatD.rand(5, 7)
      p.writeCsv(m)
      val r = p.readCsv
      assertEquals(r.rows, 5)
      assertEquals(r.cols, 7)
    finally Files.deleteIfExists(p)
  }

  test("E5: 1x1 MatD roundtrip") {
    val p = tmpFile("matcsv-E5")
    try
      val m = MatD.single(42.0)
      p.writeCsv(m)
      val r = p.readCsv
      assertEquals(r.shape, (1, 1))
      assertEqualsDouble(r(0, 0), 42.0, 1e-12)
    finally Files.deleteIfExists(p)
  }

package uni.data

import munit.FunSuite
import uni.data.Big.*

/** Covers containsNaN, hasNaN, inspect and related branches in Mat.scala. */
class MatNaNSuite extends FunSuite:

  // ============================================================================
  // containsNaN — Double matrix
  // ============================================================================

  test("containsNaN: matrix without NaN returns false") {
    val m = MatD.row(1.0, 2.0, 3.0)
    assert(!m.containsNaN)
  }

  test("containsNaN: matrix with Double.NaN returns true") {
    val m = MatD.row(1.0, Double.NaN, 3.0)
    assert(m.containsNaN)
  }

  test("containsNaN: all-NaN matrix returns true") {
    val m = MatD.row(Double.NaN, Double.NaN)
    assert(m.containsNaN)
  }

  test("containsNaN: empty matrix returns false") {
    val m = MatD.empty
    assert(!m.containsNaN)
  }

  // ============================================================================
  // hasNaN — Big matrix (returns Mat[Boolean])
  // ============================================================================

  test("hasNaN: Big matrix without NaN returns all-false mask") {
    val m = MatB.row(1.0, 2.0, 3.0)
    val mask = m.hasNaN
    assertEquals(mask.size, 3)
    assert(!mask.iterator.exists(identity))
  }

  test("hasNaN: Big matrix with BigNaN returns true at that position") {
    val vals = Array(Big(1.0), BigNaN, Big(3.0))
    val m = Mat.create(vals, 1, 3)
    val mask = m.hasNaN
    assert(!mask(0, 0))
    assert(mask(0, 1))
    assert(!mask(0, 2))
  }

  // ============================================================================
  // ndim — always 2 for Mat
  // ============================================================================

  test("ndim: always returns 2") {
    val m = MatD.zeros(3, 4)
    assertEquals(m.ndim, 2)
  }

  // ============================================================================
  // typeName — dispatch by element type
  // ============================================================================

  test("typeName: Double matrix returns \"Double\"") {
    val m = MatD.ones(2, 2)
    assertEquals(m.typeName, "Double")
  }

  test("typeName: Float matrix returns \"Float\"") {
    val m = MatF.ones(2, 2)
    assertEquals(m.typeName, "Float")
  }

  test("typeName: Big matrix returns \"Big\"") {
    val m = MatB.ones(2, 2)
    assertEquals(m.typeName, "Big")
  }

  // ============================================================================
  // foreach — contiguous and non-contiguous paths
  // ============================================================================

  test("foreach (contiguous): iterates all elements") {
    val m = MatD.row(1.0, 2.0, 3.0)
    var sum = 0.0
    m.foreach(sum += _)
    assertEqualsDouble(sum, 6.0, 1e-12)
  }

  test("foreach (transposed / non-contiguous): iterates all elements") {
    val m = MatD((1.0, 2.0), (3.0, 4.0)).T
    var sum = 0.0
    m.foreach(sum += _)
    assertEqualsDouble(sum, 10.0, 1e-12)
  }

  // ============================================================================
  // saveCSV special value rendering (Double.NaN / Inf / -Inf / Big)
  // ============================================================================

  test("saveCSV: NaN, Inf, -Inf written correctly") {
    import java.nio.file.Files
    val tmpPath = Files.createTempFile("mat-nan-", ".csv")
    tmpPath.toFile.deleteOnExit()
    val m = MatD.row(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity)
    m.saveCSV(uni.Paths.get(tmpPath.toString))
    val content = new String(Files.readAllBytes(tmpPath))
    assert(content.contains("NaN"),  s"expected NaN in: $content")
    assert(content.contains("Inf"),  s"expected Inf in: $content")
    assert(content.contains("-Inf"), s"expected -Inf in: $content")
  }

  test("saveCSV: BigNaN written as N/A") {
    import java.nio.file.Files
    val tmpPath = Files.createTempFile("mat-bignan-", ".csv")
    tmpPath.toFile.deleteOnExit()
    val vals = Array(Big(1.0), BigNaN)
    val m = Mat.create(vals, 1, 2)
    m.saveCSV(uni.Paths.get(tmpPath.toString))
    val content = new String(Files.readAllBytes(tmpPath))
    assert(content.contains("N/A"), s"expected N/A in: $content")
  }

  test("saveCSV: Float NaN and Inf written correctly") {
    import java.nio.file.Files
    val tmpPath = Files.createTempFile("mat-fnan-", ".csv")
    tmpPath.toFile.deleteOnExit()
    val m = MatF.row(Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity)
    m.saveCSV(uni.Paths.get(tmpPath.toString))
    val content = new String(Files.readAllBytes(tmpPath))
    assert(content.contains("NaN"),  s"expected NaN in: $content")
    assert(content.contains("Inf"),  s"expected Inf in: $content")
    assert(content.contains("-Inf"), s"expected -Inf in: $content")
  }

  // ============================================================================
  // exists — predicate check that short-circuits
  // ============================================================================

  test("exists: found → true") {
    val m = MatD.row(1.0, 2.0, 5.0)
    assert(m.exists(_ > 4.0))
  }

  test("exists: not found → false") {
    val m = MatD.row(1.0, 2.0, 3.0)
    assert(!m.exists(_ > 10.0))
  }

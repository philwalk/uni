package uni.data

import munit.FunSuite

/** Tests for mapCols, mapRows, m(::, *).map(f), m(*, ::).map(f),
 *  and arithmetic operators on ColsView / RowsView with auto-orientation. */
class AxisBroadcastSuite extends FunSuite:

  // 3×3 matrix: column means = (4,5,6), row means = (2,5,8)
  val data = MatD(
    (1.0, 2.0, 3.0),
    (4.0, 5.0, 6.0),
    (7.0, 8.0, 9.0),
  )

  def cells(m: MatD): Seq[Double] =
    for i <- 0 until m.rows; j <- 0 until m.cols yield m(i, j)

  // ============================================================================
  // mapCols / mapRows — named method forms
  // ============================================================================

  test("mapCols: sort each column independently") {
    val m = MatD(
      (3.0, 1.0),
      (1.0, 3.0),
      (2.0, 2.0),
    )
    val r = m.mapCols(col => col.sort(0))  // axis=0: sort within the column, preserving cols×1 shape
    assertEquals(r(0, 0), 1.0); assertEquals(r(0, 1), 1.0)
    assertEquals(r(1, 0), 2.0); assertEquals(r(1, 1), 2.0)
    assertEquals(r(2, 0), 3.0); assertEquals(r(2, 1), 3.0)
  }

  test("mapCols: preserves shape") {
    val r = data.mapCols(col => col * 2.0)
    assertEquals(r.rows, data.rows)
    assertEquals(r.cols, data.cols)
  }

  test("mapRows: reverse each row") {
    val m = MatD((1.0, 2.0, 3.0), (4.0, 5.0, 6.0))
    val r = m.mapRows(row => row(::, row.cols - 1 to 0 by -1))
    assertEquals(cells(r).toSeq, Seq(3.0, 2.0, 1.0, 6.0, 5.0, 4.0))
  }

  test("mapRows: preserves shape") {
    val r = data.mapRows(row => row * 2.0)
    assertEquals(r.rows, data.rows)
    assertEquals(r.cols, data.cols)
  }

  test("mapCols on 0-column matrix returns empty") {
    val m = Mat.create(Array.emptyDoubleArray, 0, 0)
    val r = m.mapCols(identity)
    assertEquals(r.rows, 0)
    assertEquals(r.cols, 0)
  }

  test("mapRows on 0-row matrix returns empty") {
    val m = Mat.create(Array.emptyDoubleArray, 0, 0)
    val r = m.mapRows(identity)
    assertEquals(r.rows, 0)
    assertEquals(r.cols, 0)
  }

  // ============================================================================
  // Breeze-style sentinel syntax — m(::, *) and m(*, ::)
  // ============================================================================

  test("m(::, *).map(f) is equivalent to m.mapCols(f)") {
    val m = MatD((3.0, 1.0), (1.0, 3.0), (2.0, 2.0))
    val via1 = m.mapCols(col => col.sort(0))
    val via2 = m(::, *).map(col => col.sort(0))
    assertEquals(cells(via1).toSeq, cells(via2).toSeq)
  }

  test("m(*, ::).map(f) is equivalent to m.mapRows(f)") {
    val m = MatD((1.0, 2.0, 3.0), (4.0, 5.0, 6.0))
    val via1 = m.mapRows(row => row(::, row.cols - 1 to 0 by -1))
    val via2 = m(*, ::).map(row => row(::, row.cols - 1 to 0 by -1))
    assertEquals(cells(via1).toSeq, cells(via2).toSeq)
  }

  // ============================================================================
  // Column centering via data(*, ::) - colMeans
  //   colMeans = (4, 5, 6); expected result: rows (-3,-3,-3), (0,0,0), (3,3,3)
  // ============================================================================

  val colMeansRow = data.mean(axis = 0)    // 1×3 row vector
  val colMeansCol = data.mean(axis = 0).T  // 3×1 col vector

  val expectedColCentered = MatD(
    (-3.0, -3.0, -3.0),
    ( 0.0,  0.0,  0.0),
    ( 3.0,  3.0,  3.0),
  )

  test("data(*, ::) - colMeans (1×cols): centers each column") {
    assertEquals(cells(data(*, ::) - colMeansRow).toSeq, cells(expectedColCentered).toSeq)
  }

  test("data(*, ::) - colMeans.T (cols×1): auto-normalises to same result") {
    assertEquals(cells(data(*, ::) - colMeansCol).toSeq, cells(expectedColCentered).toSeq)
  }

  test("data(*, ::) - colMeans matches plain data - colMeans") {
    assertEquals(cells(data(*, ::) - colMeansRow).toSeq, cells(data - colMeansRow).toSeq)
  }

  // ============================================================================
  // Row centering via data(::, *) - rowMeans
  //   rowMeans = (2, 5, 8); expected result: every row = (-1, 0, 1)
  // ============================================================================

  val rowMeansCol = data.mean(axis = 1)    // 3×1 col vector
  val rowMeansRow = data.mean(axis = 1).T  // 1×3 row vector

  val expectedRowCentered = MatD(
    (-1.0, 0.0, 1.0),
    (-1.0, 0.0, 1.0),
    (-1.0, 0.0, 1.0),
  )

  test("data(::, *) - rowMeans (rows×1): centers each row") {
    assertEquals(cells(data(::, *) - rowMeansCol).toSeq, cells(expectedRowCentered).toSeq)
  }

  test("data(::, *) - rowMeans.T (1×rows): auto-normalises to same result") {
    assertEquals(cells(data(::, *) - rowMeansRow).toSeq, cells(expectedRowCentered).toSeq)
  }

  // ============================================================================
  // Other arithmetic operators on RowsView / ColsView
  // ============================================================================

  test("data(*, ::) + colMeans equals data + colMeans") {
    assertEquals(cells(data(*, ::) + colMeansRow).toSeq, cells(data + colMeansRow).toSeq)
  }

  test("data(*, ::) * colMeans equals data * colMeans") {
    assertEquals(cells(data(*, ::) * colMeansRow).toSeq, cells(data * colMeansRow).toSeq)
  }

  test("data(*, ::) / colMeans equals data / colMeans") {
    val r1 = cells(data(*, ::) / colMeansRow)
    val r2 = cells(data / colMeansRow)
    r1.zip(r2).foreach((a, b) => assertEqualsDouble(a, b, 1e-12))
  }

  test("data(::, *) + rowMeans equals data + rowMeans") {
    assertEquals(cells(data(::, *) + rowMeansCol).toSeq, cells(data + rowMeansCol).toSeq)
  }

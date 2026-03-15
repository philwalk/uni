package uni.data

import munit.FunSuite
import Mat.*
import uni.data.Big.*

class MatPandasSuite extends FunSuite {

  // ---- A: head / tail -------------------------------------------------------

  test("A1: head returns first n rows") {
    val m = MatD.arange(1, 7).reshape(3, 2)
    val h = m.head(2)
    assertEquals(h.rows, 2)
    assertEquals(h.cols, 2)
    assertEquals(h(0, 0), 1.0)
    assertEquals(h(1, 0), 3.0)
  }

  test("A2: head(0) returns empty matrix") {
    val m = MatD.arange(1, 7).reshape(3, 2)
    val h = m.head(0)
    assertEquals(h.rows, 0)
    assertEquals(h.cols, 2)
  }

  test("A3: head(n > rows) returns full matrix") {
    val m = MatD.arange(1, 7).reshape(3, 2)
    val h = m.head(100)
    assertEquals(h.rows, 3)
  }

  test("A4: tail returns last n rows") {
    val m = MatD.arange(1, 7).reshape(3, 2)
    val t = m.tail(2)
    assertEquals(t.rows, 2)
    assertEquals(t(0, 0), 3.0)
    assertEquals(t(1, 0), 5.0)
  }

  test("A5: tail(0) returns empty matrix") {
    val m = MatD.arange(1, 7).reshape(3, 2)
    val t = m.tail(0)
    assertEquals(t.rows, 0)
  }

  test("A6: tail(n > rows) returns full matrix") {
    val m = MatD.arange(1, 7).reshape(3, 2)
    val t = m.tail(100)
    assertEquals(t.rows, 3)
  }

  // ---- B: idxmin / idxmax ---------------------------------------------------

  test("B1: idxmin axis=0 returns row index of min per column") {
    // row0: [3,1], row1: [2,4]
    // col0: min at row1(=2); col1: min at row0(=1)
    val m = MatD(2, 2, Array(3.0, 1.0, 2.0, 4.0))
    val r = m.idxmin(0)
    assertEquals(r.rows, 1)
    assertEquals(r.cols, 2)
    assertEquals(r(0, 0), 1) // col0 min at row 1
    assertEquals(r(0, 1), 0) // col1 min at row 0
  }

  test("B2: idxmax axis=0 returns row index of max per column") {
    val m = MatD(2, 2, Array(3.0, 1.0, 2.0, 4.0))
    val r = m.idxmax(0)
    assertEquals(r(0, 0), 0) // col0 max at row 0
    assertEquals(r(0, 1), 1) // col1 max at row 1
  }

  test("B3: idxmin axis=1 returns col index of min per row") {
    val m = MatD(2, 2, Array(3.0, 1.0, 2.0, 4.0))
    val r = m.idxmin(1)
    assertEquals(r.rows, 2)
    assertEquals(r.cols, 1)
    assertEquals(r(0, 0), 1) // row0: [3,1] -> col1
    assertEquals(r(1, 0), 0) // row1: [2,4] -> col0
  }

  test("B4: idxmax axis=1 returns col index of max per row") {
    val m = MatD(2, 2, Array(3.0, 1.0, 2.0, 4.0))
    val r = m.idxmax(1)
    assertEquals(r(0, 0), 0) // row0: [3,1] -> col0
    assertEquals(r(1, 0), 1) // row1: [2,4] -> col1
  }

  // ---- C: cummax / cummin ---------------------------------------------------

  test("C1: cummax axis=0 accumulates column-wise maximum") {
    // row0: [1,3], row1: [2,4]
    val m = MatD(2, 2, Array(1.0, 3.0, 2.0, 4.0))
    val r = m.cummax(0)
    assertEquals(r(0, 0), 1.0)
    assertEquals(r(1, 0), 2.0)
    assertEquals(r(0, 1), 3.0)
    assertEquals(r(1, 1), 4.0)
  }

  test("C2: cummin axis=0 accumulates column-wise minimum") {
    // row0: [3,1], row1: [2,4]
    val m = MatD(2, 2, Array(3.0, 1.0, 2.0, 4.0))
    val r = m.cummin(0)
    assertEquals(r(0, 0), 3.0)
    assertEquals(r(1, 0), 2.0) // min(3,2) = 2
    assertEquals(r(0, 1), 1.0)
    assertEquals(r(1, 1), 1.0) // min(1,4) = 1
  }

  test("C3: cummax axis=1 accumulates row-wise maximum") {
    // row0: [1,3], row1: [5,2]
    val m = MatD(2, 2, Array(1.0, 3.0, 5.0, 2.0))
    val r = m.cummax(1)
    assertEquals(r(0, 0), 1.0)
    assertEquals(r(0, 1), 3.0) // max(1,3)
    assertEquals(r(1, 0), 5.0)
    assertEquals(r(1, 1), 5.0) // max(5,2)
  }

  test("C4: cummin axis=1 accumulates row-wise minimum") {
    // row0: [1,3], row1: [5,2]
    val m = MatD(2, 2, Array(1.0, 3.0, 5.0, 2.0))
    val r = m.cummin(1)
    assertEquals(r(0, 1), 1.0)
    assertEquals(r(1, 1), 2.0)
  }

  // ---- D: nunique / valueCounts ---------------------------------------------

  test("D1: nunique counts distinct elements") {
    val m = MatD(2, 3, Array(1.0, 2.0, 1.0, 3.0, 2.0, 2.0))
    assertEquals(m.nunique, 3)
  }

  test("D2: valueCounts returns (value, count) sorted descending") {
    val m = MatD(2, 3, Array(1.0, 2.0, 1.0, 3.0, 2.0, 2.0))
    val vc = m.valueCounts
    assertEquals(vc(0)._1, 2.0)
    assertEquals(vc(0)._2, 3)
    assertEquals(vc(1)._2, 2) // 1.0 appears twice
    assertEquals(vc(2)._2, 1) // 3.0 appears once
  }

  // ---- E: nlargest / nsmallest / between -----------------------------------

  test("E1: nlargest returns n largest elements in descending order") {
    val m = MatD(2, 3, Array(3.0, 1.0, 4.0, 1.0, 5.0, 9.0))
    val r = m.nlargest(3)
    assertEquals(r.cols, 3)
    assertEquals(r(0, 0), 9.0)
    assertEquals(r(0, 1), 5.0)
    assertEquals(r(0, 2), 4.0)
  }

  test("E2: nsmallest returns n smallest elements in ascending order") {
    val m = MatD(2, 3, Array(3.0, 1.0, 4.0, 1.0, 5.0, 9.0))
    val r = m.nsmallest(3)
    assertEquals(r(0, 0), 1.0)
    assertEquals(r(0, 1), 1.0)
    assertEquals(r(0, 2), 3.0)
  }

  test("E3: nlargest(n > size) returns all elements sorted") {
    val m = MatD(1, 3, Array(3.0, 1.0, 2.0))
    val r = m.nlargest(100)
    assertEquals(r.cols, 3)
  }

  test("E4: between returns boolean mask") {
    val m = MatD(2, 3, Array(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))
    val b = m.between(2.0, 4.0)
    assertEquals(b(0, 0), false) // 1.0
    assertEquals(b(0, 1), true)  // 2.0
    assertEquals(b(0, 2), true)  // 3.0
    assertEquals(b(1, 0), true)  // 4.0
    assertEquals(b(1, 1), false) // 5.0
    assertEquals(b(1, 2), false) // 6.0
  }

  // ---- F: shift -------------------------------------------------------------

  test("F1: shift(0) is identity") {
    val m = MatD(2, 2, Array(1.0, 2.0, 3.0, 4.0))
    val s = m.shift(0, 0.0)
    assertEquals(s(0, 0), 1.0)
    assertEquals(s(1, 1), 4.0)
  }

  test("F2: shift(1) shifts rows down, first row is fill") {
    val m = MatD(2, 2, Array(1.0, 2.0, 3.0, 4.0))
    val s = m.shift(1, 0.0)
    assertEquals(s(0, 0), 0.0) // fill
    assertEquals(s(0, 1), 0.0) // fill
    assertEquals(s(1, 0), 1.0)
    assertEquals(s(1, 1), 2.0)
  }

  test("F3: shift(-1) shifts rows up, last row is fill") {
    val m = MatD(2, 2, Array(1.0, 2.0, 3.0, 4.0))
    val s = m.shift(-1, 0.0)
    assertEquals(s(0, 0), 3.0)
    assertEquals(s(1, 0), 0.0) // fill
  }

  test("F4: shift(n > rows) fills everything") {
    val m = MatD(2, 2, Array(1.0, 2.0, 3.0, 4.0))
    val s = m.shift(5, -1.0)
    assertEquals(s(0, 0), -1.0)
    assertEquals(s(1, 1), -1.0)
  }

  test("F5: shift axis=1 shifts columns right") {
    val m = MatD(2, 2, Array(1.0, 2.0, 3.0, 4.0))
    val s = m.shift(1, 0.0, axis = 1)
    assertEquals(s(0, 0), 0.0)  // fill
    assertEquals(s(0, 1), 1.0)
    assertEquals(s(1, 0), 0.0)  // fill
    assertEquals(s(1, 1), 3.0)
  }

  // ---- G: pct_change --------------------------------------------------------

  test("G1: pct_change first row is NaN") {
    val m = MatD(2, 2, Array(10.0, 20.0, 15.0, 30.0))
    val r = m.pct_change()
    assert(r(0, 0).isNaN)
    assert(r(0, 1).isNaN)
  }

  test("G2: pct_change subsequent rows are correct ratios") {
    val m = MatD(2, 2, Array(10.0, 20.0, 15.0, 30.0))
    val r = m.pct_change()
    assertEqualsDouble(r(1, 0), 0.5,  1e-10) // (15-10)/10
    assertEqualsDouble(r(1, 1), 0.5,  1e-10) // (30-20)/20
  }

  // ---- H: fillna ------------------------------------------------------------

  test("H1: fillna replaces NaN with given value") {
    val m = MatD(2, 2, Array(1.0, Double.NaN, 3.0, Double.NaN))
    val f = m.fillna(0.0)
    assertEquals(f(0, 0), 1.0)
    assertEquals(f(0, 1), 0.0)
    assertEquals(f(1, 0), 3.0)
    assertEquals(f(1, 1), 0.0)
  }

  test("H2: fillna with no NaNs returns equal matrix") {
    val m = MatD(2, 2, Array(1.0, 2.0, 3.0, 4.0))
    val f = m.fillna(-99.0)
    assertEquals(f(0, 0), 1.0)
    assertEquals(f(1, 1), 4.0)
  }

  // ---- I: describe ----------------------------------------------------------

  test("I1: describe returns 8 rows") {
    val m = MatD(3, 2, Array(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))
    val (labels, stats) = m.describe
    assertEquals(labels.length, 8)
    assertEquals(stats.rows, 8)
    assertEquals(stats.cols, 2)
  }

  test("I2: describe count row equals number of rows") {
    val m = MatD(3, 2, Array(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))
    val (labels, stats) = m.describe
    val countIdx = labels.indexOf("count")
    assertEquals(stats(countIdx, 0), 3.0)
    assertEquals(stats(countIdx, 1), 3.0)
  }

  test("I3: describe mean row is correct") {
    // col0: [1,3,5] mean=3; col1: [2,4,6] mean=4
    val m = MatD(3, 2, Array(1.0, 2.0, 3.0, 4.0, 5.0, 6.0))
    val (labels, stats) = m.describe
    val meanIdx = labels.indexOf("mean")
    assertEqualsDouble(stats(meanIdx, 0), 3.0, 1e-10)
    assertEqualsDouble(stats(meanIdx, 1), 4.0, 1e-10)
  }

  // ---- J: rolling -----------------------------------------------------------

  test("J1: rolling(1).mean equals original") {
    val m = MatD(4, 1, Array(1.0, 2.0, 3.0, 4.0))
    val r = m.rolling(1).mean
    assertEqualsDouble(r(0, 0), 1.0, 1e-10)
    assertEqualsDouble(r(1, 0), 2.0, 1e-10)
    assertEqualsDouble(r(2, 0), 3.0, 1e-10)
    assertEqualsDouble(r(3, 0), 4.0, 1e-10)
  }

  test("J2: rolling window-2 mean: first position is NaN") {
    val m = MatD(4, 1, Array(1.0, 3.0, 5.0, 7.0))
    val r = m.rolling(2).mean
    assert(r(0, 0).isNaN)
    assertEqualsDouble(r(1, 0), 2.0, 1e-10) // (1+3)/2
    assertEqualsDouble(r(2, 0), 4.0, 1e-10) // (3+5)/2
    assertEqualsDouble(r(3, 0), 6.0, 1e-10) // (5+7)/2
  }

  test("J3: rolling window > rows: all NaN") {
    val m = MatD(3, 1, Array(1.0, 2.0, 3.0))
    val r = m.rolling(10).mean
    assert(r(0, 0).isNaN)
    assert(r(1, 0).isNaN)
    assert(r(2, 0).isNaN)
  }

  test("J4: rolling(2).sum") {
    val m = MatD(4, 1, Array(1.0, 2.0, 3.0, 4.0))
    val r = m.rolling(2).sum
    assert(r(0, 0).isNaN)
    assertEqualsDouble(r(1, 0), 3.0, 1e-10)
    assertEqualsDouble(r(2, 0), 5.0, 1e-10)
    assertEqualsDouble(r(3, 0), 7.0, 1e-10)
  }

  test("J5: rolling(2).min") {
    val m = MatD(4, 1, Array(3.0, 1.0, 4.0, 2.0))
    val r = m.rolling(2).min
    assert(r(0, 0).isNaN)
    assertEqualsDouble(r(1, 0), 1.0, 1e-10) // min(3,1)
    assertEqualsDouble(r(2, 0), 1.0, 1e-10) // min(1,4)
    assertEqualsDouble(r(3, 0), 2.0, 1e-10) // min(4,2)
  }

  test("J6: rolling(2).max") {
    val m = MatD(4, 1, Array(3.0, 1.0, 4.0, 2.0))
    val r = m.rolling(2).max
    assert(r(0, 0).isNaN)
    assertEqualsDouble(r(1, 0), 3.0, 1e-10) // max(3,1)
    assertEqualsDouble(r(2, 0), 4.0, 1e-10) // max(1,4)
    assertEqualsDouble(r(3, 0), 4.0, 1e-10) // max(4,2)
  }

  test("J7: rolling window=0 throws IllegalArgumentException") {
    val m = MatD(2, 1, Array(1.0, 2.0))
    intercept[IllegalArgumentException] {
      m.rolling(0)
    }
  }

  // ---- K: MatResult named-column access ------------------------------------

  test("K1: MatResult.col returns Some(colVec) for existing header") {
    import uni.io.FileOps.MatResult
    val mat = MatD(2, 2, Array(1.0, 2.0, 3.0, 4.0))
    val result = MatResult(Vector("a", "b"), mat)
    val colA = result.col("a")
    assert(colA.isDefined)
    assertEquals(colA.get(0, 0), 1.0)
    assertEquals(colA.get(1, 0), 3.0)
  }

  test("K2: MatResult.col returns None for missing header") {
    import uni.io.FileOps.MatResult
    val mat = MatD(2, 2, Array(1.0, 2.0, 3.0, 4.0))
    val result = MatResult(Vector("a", "b"), mat)
    assertEquals(result.col("missing"), None)
  }

  test("K3: MatResult.apply returns column for existing header") {
    import uni.io.FileOps.MatResult
    val mat = MatD(2, 2, Array(1.0, 2.0, 3.0, 4.0))
    val result = MatResult(Vector("a", "b"), mat)
    val colB = result("b")
    assertEquals(colB(0, 0), 2.0)
    assertEquals(colB(1, 0), 4.0)
  }

  test("K4: MatResult.apply throws NoSuchElementException for missing header") {
    import uni.io.FileOps.MatResult
    val mat = MatD(1, 2, Array(1.0, 2.0))
    val result = MatResult(Vector("x", "y"), mat)
    intercept[NoSuchElementException] {
      result("missing")
    }
  }

  test("K5: MatResult.columnIndex maps headers to indices") {
    import uni.io.FileOps.MatResult
    val mat = MatD(2, 2, Array(1.0, 2.0, 3.0, 4.0))
    val result = MatResult(Vector("price", "volume"), mat)
    assertEquals(result.columnIndex("price"), 0)
    assertEquals(result.columnIndex("volume"), 1)
    assert(!result.columnIndex.contains("missing"))
  }

}

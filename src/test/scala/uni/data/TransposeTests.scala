package uni.data

import Mat.*

class TransposeTests extends munit.FunSuite {
  test("element access on transposed matrix") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6))
    val t = m.T
    assertEquals(t(0, 0), 1.0)
    assertEquals(t(1, 0), 2.0)  // was m(0,1)
    assertEquals(t(2, 1), 6.0)  // was m(1,2)
  }
  test("flatten on transposed matrix returns logical order") {
    val m = Mat[Double]((1, 2), (3, 4))
    val flat = m.T.flatten
    assertEquals(flat(0), 1.0)
    assertEquals(flat(1), 3.0)  // column-first in transposed logical order
    assertEquals(flat(2), 2.0)
    assertEquals(flat(3), 4.0)
  }
  test("argmin on transposed matrix") {
    val m = Mat[Double]((5, 1), (3, 9)).T
    assertEquals(m.argmin, (1, 0))  // 1 is at row 1, col 0 of transposed
  }

  test("argmax on transposed matrix") {
    val m = Mat[Double]((5, 1), (3, 9)).T
    assertEquals(m.argmax, (1, 1))  // 9 is at row 1, col 1 of transposed
  }

  test("Mat(()) treats Unit as empty matrix") {
    val m = Mat[Double](())
    assertEquals(m.shape, (0, 0))  // does this pass or fail?
    assert(m.isEmpty)
    val s = m.show
    printf("s[%s]\n", s)
  }
}

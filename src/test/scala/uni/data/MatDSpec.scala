import uni.data.*
// Assumes you've added the top-level export: val MatD = Mat.MatD

class MatDSpec extends munit.FunSuite {

  test("MatD.zeros creates a valid Double matrix") {
    val m = MatD.zeros(2, 3)
    assertEquals(m.rows, 2)
    assertEquals(m.cols, 3)
    // Verify it's actually Double by calling a Double-only method if you have one
    // or checking the type name from your Internal.MatData logic
    assert(m.toString.contains("Double"))
  }

  test("MatD inline creation matches NumPy/Breeze style") {
    val m = MatD((1, 2), (3, 4))
    assertEquals(m(0, 0), 1.0)
    assertEquals(m(1, 1), 4.0)
  }

  test("MatD results support high-precedence operators") {
    val a = MatD.eye(2)
    val b = MatD((2, 0), (0, 2))
    val res = a ~@ b  // Matrix multiplication
    assertEquals(res(0, 0), 2.0)
    assertEquals(res(1, 1), 2.0)
  }

  test("MatD.linspace generates correct ranges") {
    val v = MatD.linspace(0.0, 1.0, 5)
    assertEquals(v.cols, 5)
    assertEquals(v(0, 4), 1.0)
  }

  test("MatD interacts with generic Mat[Double]") {
    val d = MatD.ones(2, 2)
    val g = Mat.ones[Double](2, 2)
    val sum = d + g
    assertEquals(sum(0, 0), 2.0)
  }
}

package uni.data

import munit.FunSuite
import Mat.*

final class MatShowSuite extends FunSuite {
  type D = Double
  type F = Float
  type B = Big

  // ------------------------------------------------------------
  // 1. Small Double matrix
  // ------------------------------------------------------------

  test("show: 2x2 Double (auto)") {
    val m = Mat[D]((1.0, 2.0), (3.0, 4.0))
    val expected =
      """2x2 Mat[Double]:
        | (1, 2),
        | (3, 4)""".stripMargin
    assertEquals(m.show, expected)
  }

  test("show: 2x2 Double (fmt)") {
    val m = Mat[D]((1.0, 2.0), (3.0, 4.0))
    val expected =
      """2x2 Mat[Double]:
        | (1.0000, 2.0000),
        | (3.0000, 4.0000)""".stripMargin
    assertEquals(m.show("%3.4f"), expected)
  }

  // ------------------------------------------------------------
  // 2. Mixed magnitudes (scientific vs fixed)
  // ------------------------------------------------------------

  test("show: mixed magnitudes (auto)") {
    val m = Mat[D]((1.0, -0.2), (0.0, 4001.0))
    val expected =
      """2x2 Mat[Double]:
        | (1,   -0.2),
        | (0, 4001.0)""".stripMargin
    assertEquals(m.show, expected)
  }

  test("show: mixed magnitudes (fmt)") {
    val m = Mat[D]((1.0, -0.2), (0.0, 4001.0))
    val expected =
      """2x2 Mat[Double]:
        | (1.0000,   -0.2000),
        | (0.0000, 4001.0000)""".stripMargin
    assertEquals(m.show("%3.4f"), expected)
  }

  // ------------------------------------------------------------
  // 3. Tall Double matrix
  // ------------------------------------------------------------

  test("show: tall Double (auto)") {
    val m = Mat[D](
      (-0.117776732, 2.514159783,  0.548743057),
      (-0.128448594, 1.013473373,  0.637903063),
      ( 0.542394467, 0.357531172, -0.524160231),
      (-1.37278897 , 1.89639119 , -0.538120904),
      (-0.733719591, 0.074211147, -0.508730941)
    )
    val expected =
      """5x3 Mat[Double]:
        | (-0.117776732, 2.514159783,  0.548743057),
        | (-0.128448594, 1.013473373,  0.637903063),
        | ( 0.542394467, 0.357531172, -0.524160231),
        | (-1.37278897 , 1.89639119 , -0.538120904),
        | (-0.733719591, 0.074211147, -0.508730941)""".stripMargin
    assertEquals(m.show, expected)
  }

  test("show: tall Double (fmt)") {
    val m = Mat[D](
      (-0.117776732, 2.514159783,  0.548743057),
      (-0.128448594, 1.013473373,  0.637903063),
      ( 0.542394467, 0.357531172, -0.524160231),
      (-1.37278897 , 1.89639119 , -0.538120904),
      (-0.733719591, 0.074211147, -0.508730941)
    )
    val expected =
      """5x3 Mat[Double]:
        | (-0.1178, 2.5142,  0.5487),
        | (-0.1284, 1.0135,  0.6379),
        | ( 0.5424, 0.3575, -0.5242),
        | (-1.3728, 1.8964, -0.5381),
        | (-0.7337, 0.0742, -0.5087)""".stripMargin
    assertEquals(m.show("%1.4f"), expected)
  }

  // ------------------------------------------------------------
  // 4. Wide Double matrix
  // ------------------------------------------------------------

  test("show: wide Double (auto)") {
    val m = Mat[D](
      (-0.662037397, -0.356532054, -0.419284595, 0.923356245),
      (-0.231505861, -0.461587026, -0.535281822, 1.056552483),
      (-1.231207672,  1.104467411,  0.046184039, 0.479113371),
      ( 0.865873175,  0.362483616,  1.622141674, 0.427669995),
      (-1.91752875 , -0.731781677,  1.678017792, 1.118948822)
    )
    val expected =
      """5x4 Mat[Double]:
        | (-0.662037397, -0.356532054, -0.419284595, 0.923356245),
        | (-0.231505861, -0.461587026, -0.535281822, 1.056552483),
        | (-1.231207672,  1.104467411,  0.046184039, 0.479113371),
        | ( 0.865873175,  0.362483616,  1.622141674, 0.427669995),
        | (-1.91752875 , -0.731781677,  1.678017792, 1.118948822)""".stripMargin
    assertEquals(m.show, expected)
  }

  test("show: wide Double (fmt)") {
    val m = Mat[D](
      (-0.662037397, -0.356532054, -0.419284595, 0.923356245),
      (-0.231505861, -0.461587026, -0.535281822, 1.056552483),
      (-1.231207672,  1.104467411,  0.046184039, 0.479113371),
      ( 0.865873175,  0.362483616,  1.622141674, 0.427669995),
      (-1.91752875 , -0.731781677,  1.678017792, 1.118948822)
    )
    val expected =
      """5x4 Mat[Double]:
        | (-0.6620, -0.3565, -0.4193, 0.9234),
        | (-0.2315, -0.4616, -0.5353, 1.0566),
        | (-1.2312,  1.1045,  0.0462, 0.4791),
        | ( 0.8659,  0.3625,  1.6221, 0.4277),
        | (-1.9175, -0.7318,  1.6780, 1.1189)""".stripMargin
    assertEquals(m.show("%1.4f"), expected)
  }

  // ------------------------------------------------------------
  // 5. Empty Double matrix
  // ------------------------------------------------------------

  test("show: 0x0 Double (auto)") {
    val m = Mat.empty[Double]
    val expected = "0x0 Mat[Double]:"
    assertEquals(m.show, expected)
  }

  test("show: 0x0 Double (fmt)") {
    val m = Mat.empty[Double]
    val expected = "0x0 Mat[Double]:"
    assertEquals(m.show("%3.4f"), expected)
  }

  // ------------------------------------------------------------
  // 6. Float matrices
  // ------------------------------------------------------------
  test("show: 2x2 Float (auto)") {
    val m = Mat[F]((1.0f, 2.0f), (3.0f, 4.0f))
    val expected =
      """2x2 Mat[Float]:
        | (1, 2),
        | (3, 4)""".stripMargin
    assertEquals(m.show, expected)
  }

  test("show: 2x2 Float (fmt)") {
    val m = Mat[F]((1.0f, 2.0f), (3.0f, 4.0f))
    val expected =
      """2x2 Mat[Float]:
        | (1.0000, 2.0000),
        | (3.0000, 4.0000)""".stripMargin
    assertEquals(m.show("%3.4f"), expected)
  }

  // ------------------------------------------------------------
  // 7. Empty Float matrix
  // ------------------------------------------------------------

  test("show: 0x0 Float (auto)") {
    val m = Mat.empty[Float]
    val expected = "0x0 Mat[Float]:"
    assertEquals(m.show, expected)
  }

  test("show: 0x0 Float (fmt)") {
    val m = Mat.empty[Float]
    val expected = "0x0 Mat[Float]:"
    assertEquals(m.show("%3.4f"), expected)
  }

  // ------------------------------------------------------------
  // 8. Big matrices
  // ------------------------------------------------------------

  test("show: 2x2 Big (auto)") {
    val m = Mat[B]((Big(1), Big(2)), (Big(3), Big(4)))
    val expected =
      """2x2 Mat[Big]:
        | (1, 2),
        | (3, 4)""".stripMargin
    assertEquals(m.show, expected)
  }

  test("show: 2x2 Big (fmt)") {
    val m = Mat[B]((Big(1), Big(2)), (Big(3), Big(4)))
    val expected =
      """2x2 Mat[Big]:
        | (1.0000, 2.0000),
        | (3.0000, 4.0000)""".stripMargin
    assertEquals(m.show("%3.4f"), expected)
  }

  // ------------------------------------------------------------
  // 9. Empty Big matrix
  // ------------------------------------------------------------

  test("show: 0x0 Big (auto)") {
    val m = Mat.empty[Big]
    val expected = "0x0 Mat[Big]:"
    assertEquals(m.show, expected)
  }

  test("show: 0x0 Big (fmt)") {
    val m = Mat.empty[Big]
    val expected = "0x0 Mat[Big]:"
    assertEquals(m.show("%3.4f"), expected)
  }

  // ------------------------------------------------------------
  // 10. Transposed matrix
  // ------------------------------------------------------------

  test("show: transposed (auto)") {
    val m = Mat[D]((1.0, 2.0), (3.0, 4.0)).T
    val expected =
      """2x2 Mat[Double]:
        | (1, 3),
        | (2, 4)""".stripMargin
    assertEquals(m.show, expected)
  }

  test("show: transposed (fmt)") {
    val m = Mat[D]((1.0, 2.0), (3.0, 4.0)).T
    val expected =
      """2x2 Mat[Double]:
        | (1.0000, 3.0000),
        | (2.0000, 4.0000)""".stripMargin
    assertEquals(m.show("%3.4f"), expected)
  }

  // ============================================================================
  // Display Tests
  // ============================================================================
  test("show formats matrix nicely with wider range") {
    val m = Mat[Double]((1, 0.2), (0.0003, 4001.000004))
    val str = m.show("%7.2f")
    val expect = """
    |2x2 Mat[Double]:
    | (1.00,    0.20),
    | (0.00, 4001.00)
    """.trim.stripMargin 

    assertEquals(str, expect)
  }
  
  test("show formats Float matrix nicely") {
    val m = Mat[Float]((1, 2), (3, 4))
    val s = m.show
    assert(s.contains("2x2 Mat[Float]:"))
    assert(s.contains("(1, 2)"))
    assert(s.contains("(3, 4)"))
  }
  
  test("show handles empty matrix of type Float") {
    val m = Mat.empty[Float]
    assertEquals(m.show, "0x0 Mat[Float]:")
  }

  test("show formats Big matrix nicely") {
    val m = Mat[Big]((1, 2), (3, 4))
    val s = m.show
    assert(s.contains("2x2 Mat[Big]:"))
    assert(s.contains("(1, 2)"))
    assert(s.contains("(3, 4)"))
  }
  
  test("show handles empty matrix of type Big") {
    val m = Mat.empty[Big]
    assertEquals(m.show, "0x0 Mat[Big]:")
  }

  test("show uses scientific notation for large values") {
    val m = Mat[Double]((1e7, 2e8), (3e9, 4e10))
    val s = m.show
    assert(s.contains("e+"), s"Expected scientific notation in: $s")
  }

  test("show uses scientific notation for small values") {
    val m = Mat[Double]((1e-5, 2e-6), (3e-7, 4e-8))
    val s = m.show
    assert(s.contains("e-"), s"Expected scientific notation in: $s")
  }

  test("show uses integer format for whole numbers") {
    val m = Mat[Double]((1, 2), (3, 4))
    val s = m.show
    assert(s.contains("(1, 2)"), s"Expected integer format in: $s")
    assert(!s.contains("1.0"),   s"Should not contain decimal in: $s")
  }

  test("show precision reflects spread") {
    // small spread - needs more decimal places
    val m1 = Mat[Double]((0.0001, 0.0002), (0.0003, 0.0004))
    assert(m1.show.contains("0.000"), "small spread needs fine precision")

    // large spread - fewer decimal places needed  
    val m2 = Mat[Double]((1, 200), (3000, 40000))
    val s2 = m2.show
    assert(!s2.contains(".000000"), "large spread should not over-precision")
  }

  test("show respects transposition") {
    val m = Mat[Double]((1, 2, 3), (4, 5, 6)) // 2x3
    val mt = m.T                          // 3x2
    
    val s = mt.show
    // The first row of the string should be [1, 4]
    assert(s.contains("(1, 4)"))
    assert(mt.shape == (3, 2))
  }

}

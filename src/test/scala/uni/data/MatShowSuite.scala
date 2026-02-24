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

  test("formatMatrix truncates large row count with ellipsis") {
    val m = Mat.rand(100, 5)
    val output = m.show
    
    // Should contain ellipsis for rows
    assert(output.contains("..."), "Should show row ellipsis for 100 rows")
    
    // Should show header with full dimensions
    assert(output.startsWith("100x5 Mat[Double]:"), "Header should show full dimensions")
    
    // Count actual data rows (not including ellipsis line)
    val dataRows = output.split("\n").count(_.trim.startsWith("("))
    assertEquals(dataRows, 6, "Should show 3 first + 3 last rows = 6 total")
  }

  test("formatMatrix truncates large column count with ellipsis") {
    val m = Mat.rand(5, 50)
    val output = m.show
    
    // Should contain ellipsis for columns
    assert(output.contains("..."), "Should show column ellipsis for 50 cols")
    
    // Should show header with full dimensions
    assert(output.startsWith("5x50 Mat[Double]:"), "Header should show full dimensions")
    
    // Each row should have exactly 6 displayed values + 1 ellipsis
    val firstRow = output.split("\n")(1)
    val commaCount = firstRow.count(_ == ',')
    assertEquals(commaCount, 7, "Should show 3 first + 3 last cols = 6 commas (7 items including ...)")
  }

  test("formatMatrix truncates both rows and columns") {
    val m = Mat.rand(100, 50)
    val output = m.show
    
    // Should have both row and column ellipsis
    val lines = output.split("\n")
    assert(lines.exists(_.trim == "..."), "Should have row ellipsis line")
    assert(lines.exists(line => line.contains("(") && line.contains("...")), "Should have column ellipsis in data rows")
    
    // Header
    assert(output.startsWith("100x50 Mat[Double]:"), "Header should show full dimensions")
  }

  test("formatMatrix does not truncate small matrices") {
    val m = Mat.rand(5, 5)
    val output = m.show
    
    // Should NOT contain ellipsis
    assert(!output.contains("..."), "Small matrix should not have ellipsis")
    
    // Should have all 5 rows
    val dataRows = output.split("\n").count(_.trim.startsWith("("))
    assertEquals(dataRows, 5, "Should show all 5 rows")
  }

  test("formatMatrix threshold exactly at boundary") {
    // Test exactly at maxRows threshold (10 rows)
    val m1 = Mat.rand(10, 5)
    val output1 = m1.show
    assert(!output1.contains("..."), "10 rows should not truncate (threshold is >10)")
    
    // Test just over threshold (11 rows)
    val m2 = Mat.rand(11, 5)
    val output2 = m2.show
    assert(output2.contains("..."), "11 rows should truncate")
    
    // Same for columns
    val m3 = Mat.rand(5, 10)
    val output3 = m3.show
    assert(!output3.contains("..."), "10 cols should not truncate")
    
    val m4 = Mat.rand(5, 11)
    val output4 = m4.show
    assert(output4.contains("..."), "11 cols should truncate")
  }

  test("formatMatrix empty matrix shows header only") {
    val m = Mat.zeros[Double](0, 0)
    val output = m.show
    
    assertEquals(output, "0x0 Mat[Double]:", "Empty matrix should show header only")
  }

  test("formatMatrix with explicit format and truncation") {
    val m = Mat.rand(100, 50)
    val output = m.show("%.2f")
    
    // Should still truncate
    assert(output.contains("..."), "Should truncate even with explicit format")
    
    // Values should be formatted to 2 decimal places
    val firstRow = output.split("\n")(1)
    // Check that numbers have the right format (this is approximate)
    assert(firstRow.matches(".*\\d\\.\\d{2}.*"), "Should format to 2 decimal places")
  }

  test("formatMatrix preserves alignment with ellipsis") {
    val m = Mat.rand(20, 20)
    val output = m.show
    val lines = output.split("\n").filter(_.trim.startsWith("("))
    
    // All data rows should have similar structure
    // Check that ellipsis appears in consistent position
    val ellipsisPositions = lines.map(_.indexOf("...")).filter(_ >= 0)
    if (ellipsisPositions.nonEmpty) {
      val avgPos = ellipsisPositions.sum / ellipsisPositions.length
      ellipsisPositions.foreach { pos =>
        assert(math.abs(pos - avgPos) < 5, "Ellipsis should be roughly aligned across rows")
      }
    }
  }

  test("formatMatrix row ellipsis appears between edge rows") {
    val m = Mat.rand(50, 5)
    val output = m.show
    val lines = output.split("\n")
    
    // Find the ellipsis line
    val ellipsisLineIdx = lines.indexWhere(_.trim == "...")
    assert(ellipsisLineIdx > 0, "Should have row ellipsis")
    
    // Should appear after first 3 data rows (header + 3 rows = line 4)
    assert(ellipsisLineIdx == 4, s"Row ellipsis should be at line 4, was at $ellipsisLineIdx")
  }

  test("formatMatrix handles various numeric types with truncation") {
    val m1 = Mat.rand(100, 50).asInstanceOf[Mat[Double]]
    assert(m1.show.contains("..."))
    
    val m2 = Mat.full[Big](100, 50, 42)
    assert(m2.show.contains("..."))
    
    val m3 = Mat.full[Float](100, 50, 3.14f)
    assert(m3.show.contains("..."))
  }

  test("setPrintOptions changes edge items displayed") {
    // Save original
    val originalEdge = Mat.PrintOptions.edgeItems
    
    try {
      Mat.setPrintOptions(edgeItems = 5)
      val m = Mat.rand(50, 50)
      val output = m.show
      
      // Count data rows (should be 5 + 5 = 10)
      val dataRows = output.split("\n").count(_.trim.startsWith("("))
      assertEquals(dataRows, 10, "Should show 5 first + 5 last rows = 10 total")
      
    } finally {
      // Restore
      Mat.setPrintOptions(edgeItems = originalEdge)
    }
  }

  test("setPrintOptions changes max rows threshold") {
    val original = Mat.PrintOptions.maxRows
    
    try {
      Mat.setPrintOptions(maxRows = 5)
      
      // 5 rows should not truncate (threshold is >5)
      val m1 = Mat.rand(5, 3)
      assert(!m1.show.contains("..."), "5 rows should not truncate with maxRows=5")
      
      // 6 rows should truncate
      val m2 = Mat.rand(6, 3)
      assert(m2.show.contains("..."), "6 rows should truncate with maxRows=5")
      
    } finally {
      Mat.setPrintOptions(maxRows = original)
    }
  }

  test("setPrintOptions changes max cols threshold") {
    val original = Mat.PrintOptions.maxCols
    
    try {
      Mat.setPrintOptions(maxCols = 5)
      
      val m1 = Mat.rand(3, 5)
      assert(!m1.show.contains("..."), "5 cols should not truncate")
      
      val m2 = Mat.rand(3, 6)
      assert(m2.show.contains("..."), "6 cols should truncate")
      
    } finally {
      Mat.setPrintOptions(maxCols = original)
    }
  }

  test("setPrintOptions threshold controls total element cutoff") {
    val original = Mat.PrintOptions.threshold
    
    try {
      Mat.setPrintOptions(threshold = 100)
      
      // 10x10 = 100 elements (at threshold)
      val m1 = Mat.rand(10, 10)
      val output1 = m1.show
      // Verify it is NOT truncated (does not contain the ellipsis)
      assert(!output1.contains("..."), s"Should not truncate at threshold: \n$output1")
      // Behavior at exact threshold may vary - document current behavior
      
      // 11x11 = 121 elements (over threshold)
      val m2 = Mat.rand(11, 11)
      val output2 = m2.show
      // Verify it IS truncated
      // Should truncate based on maxRows/maxCols even if under element threshold
      assert(output2.contains("..."), s"Should truncate when over threshold: \n$output2")
      
    } finally {
      Mat.setPrintOptions(threshold = original)
    }
  }

  test("setPrintOptions can disable truncation entirely") {
    val originalRows = Mat.PrintOptions.maxRows
    val originalCols = Mat.PrintOptions.maxCols
    
    try {
      Mat.setPrintOptions(maxRows = Int.MaxValue, maxCols = Int.MaxValue)
      
      val m = Mat.rand(20, 20)
      val output = m.show
      
      // Should not truncate
      assert(!output.contains("..."), "Should not truncate with maxRows/maxCols = Int.MaxValue")
      
      // Should have all 20 rows
      val dataRows = output.split("\n").count(_.trim.startsWith("("))
      assertEquals(dataRows, 20, "Should show all 20 rows")
      
    } finally {
      Mat.setPrintOptions(maxRows = originalRows, maxCols = originalCols)
    }
  }

  test("setPrintOptions default values restore original behavior") {
    // Change settings
    Mat.setPrintOptions(edgeItems = 5, maxRows = 20, maxCols = 20)
    
    // Reset to defaults
    Mat.setPrintOptions(edgeItems = 3, maxRows = 10, maxCols = 10)
    
    val m = Mat.rand(50, 50)
    val output = m.show
    
    // Should show 3 edge items
    val dataRows = output.split("\n").count(_.trim.startsWith("("))
    assertEquals(dataRows, 6, "Should show 3 + 3 = 6 rows with default edgeItems=3")
  }

  test("setPrintOptions precision affects decimal places") {
    val original = Mat.PrintOptions.precision
    
    try {
      Mat.setPrintOptions(precision = 2)
      
      val m = Mat[Double]((1.23456789, 2.34567890), (3.45678901, 4.56789012))
      val output = m.show("%.10f")  // Explicit format overrides precision setting
      // Assert that the output actually shows 10 decimal places, not 2
      assert(output.contains("1.2345678900"), s"Output should honor explicit 10-decimal format: \n$output")
    } finally {
      Mat.setPrintOptions(precision = original)
    }
  }

  test("setPrintOptions multiple parameters at once") {
    val origEdge = Mat.PrintOptions.edgeItems
    val origMaxRows = Mat.PrintOptions.maxRows
    val origMaxCols = Mat.PrintOptions.maxCols
    
    try {
      Mat.setPrintOptions(
        edgeItems = 2,
        maxRows = 8,
        maxCols = 8
      )
      
      assertEquals(Mat.PrintOptions.edgeItems, 2)
      assertEquals(Mat.PrintOptions.maxRows, 8)
      assertEquals(Mat.PrintOptions.maxCols, 8)
      
      val m = Mat.rand(20, 20)
      val output = m.show
      
      // Should use new settings
      assert(output.contains("..."), "Should truncate with maxRows=8")
      val dataRows = output.split("\n").count(_.trim.startsWith("("))
      assertEquals(dataRows, 4, "Should show 2 + 2 = 4 rows with edgeItems=2")
      
    } finally {
      Mat.setPrintOptions(
        edgeItems = origEdge,
        maxRows = origMaxRows,
        maxCols = origMaxCols
      )
    }
  }

  test("setPrintOptions thread safety - options are global") {
    // Document that these are global mutable settings
    // Could cause issues in concurrent tests
    val original = Mat.PrintOptions.edgeItems
    
    Mat.setPrintOptions(edgeItems = 7)
    assertEquals(Mat.PrintOptions.edgeItems, 7)
    
    // Any thread/test will see this change
    Mat.setPrintOptions(edgeItems = original)
  }
}

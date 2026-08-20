package uni.data

import munit.FunSuite
import uni.data.MatD
import java.nio.file.Files
import uni.*
import uni.data.*
import uni.io.FileOps.*

class MatCsvSuite extends FunSuite {

  // Temporary file setup for testing I/O
  val tempDir = Paths.get("./target")
  val tempFile: Path = {
    if !tempDir.exists then
      Files.createDirectories(tempDir)
    Files.createTempFile(tempDir, "mat_test", ".csv")
  }
//  override def afterAll(): Unit = {
//    if (tempFile.exists()) tempFile.delete()
//  }

  test("MatD should write and read back a CSV accurately") {
    val original = MatD.row(1.1, 2.2, 3.3, 4.4)

    // Assuming the library provides a .saveCSV or similar method
    original.saveCSV(tempFile)
    
    // Assuming a generic load or specialized MatD.load
    val loaded = tempFile.loadMatD
    
    assertEquals(loaded.rows, original.rows)
    assertEquals(loaded.cols, original.cols)
    // Check first element with a delta for Double precision
    assert((loaded(0, 0) - 1.1).abs < 1e-9)
  }

  test("loadMat should handle different delimiters if supported") {
    // Manually create a semicolon-separated string
    val csvContent = "1.0;2.0\n3.0;4.0"
    Files.write(tempFile, csvContent.getBytes)
    
    val m: MatD = loadMatD(tempFile) // , sep = ";")
    assertEquals(m.rows, 2)
    assertEquals(m.cols, 2)
  }

  test("loadMat should return NaN on malformed data") {
    val badContent = "1.0,abc\n3.0,4.0"
    Files.write(tempFile, badContent.getBytes)
    val m = loadMatD(tempFile)
    println(m)
    assert(m.exists(_.isNaN), "Matrix should contain NaN for malformed data")
  }

  test("MatD.zeros saved to CSV should result in correct dimensions") {
    val m = MatD.zeros(10, 10)
    m.saveCSV(tempFile)
    
    val lines = scala.io.Source.fromFile(tempFile.toFile).getLines().toList
    assertEquals(lines.size, 10)
    assertEquals(lines.head.split(",").size, 10)
  }

  test("Financial data preserves precision") {
    val expected = Big("10.0000000000000001")
    val m = Mat[Big](expected, expected)
    val path: Path = tempFile
    m.saveCSV(path)
    // val path = writeTemp("10.0000000000000001, 20.0")
    val loaded = path.loadMatBig // Ensure we are using the Big matrix
    
    // Compare the Big objects directly
    assertEquals(loaded(0, 0), expected)
  }

  test("CSV Smart Loading: detect header and data correctly") {
    val path = Paths.get("src/test/resources/data_with_header.csv")
    
    // No need to pass 'skipHeader' anymore!
    val result = loadSmart(path) //, _.toDouble)
    
    // Verify Metadata (Persona 2)
    assert(result.headers.contains("Price"))
    assertEquals(result.headers.size, 3)
    
    // Verify Data (Persona 1)
    val m = result.mat
    assertEquals(m.rows, 2)
    assert((m(0, 0) - 1.1).abs < 1e-9)
  }

  test("CSV Smart Loading: handle raw data without header") {
    val path = Paths.get("src/test/resources/raw_numbers.csv")
    val result = loadSmart(path, _.toDouble)
    
    // If no header was detected, headers should be empty
    assert(result.headers.isEmpty)
    // Row 0 should be the first line of the file, not skipped!
    assertEquals(result.mat(0, 0), 1.1) 
  }

  // ---------------------------------------------------------------------------
  // Ragged input: pad rather than drop, and name the columns that padding creates
  // ---------------------------------------------------------------------------

  private def withCsv[A](content: String)(f: uni.Path => A): A =
    val p = java.nio.file.Files.createTempFile("ragged_", ".csv")
    try
      java.nio.file.Files.writeString(p, content)
      f(p)
    finally java.nio.file.Files.deleteIfExists(p)

  test("a short first row no longer collapses the whole file") {
    // The width came from `head`, and every row that missed it was discarded — so
    // one malformed first line reduced this file to a single cell.
    withCsv("9\n1,2\n3,4\n5,6\n") { p =>
      val m = p.readCsv
      assertEquals((m.rows, m.cols), (4, 2))
    }
  }

  test("a long row widens the matrix instead of being dropped") {
    withCsv("1,2\n7,8,9\n5,6\n") { p =>
      val m = p.readCsv
      assertEquals((m.rows, m.cols), (3, 3))
    }
  }

  test("csvRows and readCsv agree on how many rows a file has") {
    // They must not disagree about which rows exist. The loader reshapes rows;
    // neither drops one.
    for content <- Seq("1,2\n3,4\n", "1,2\n9\n5,6\n", "9\n1,2\n3,4\n", "1,2\n7,8,9\n") do
      withCsv(content) { p =>
        assertEquals(p.csvRows.length, p.readCsv.rows, s"row count differs for [$content]")
      }
  }

  test("padded cells read back as NaN") {
    withCsv("1,2,3\n4,5\n") { p =>
      val m = p.readCsv
      assert(m(1, 2).isNaN, s"padded cell should be NaN, got ${m(1, 2)}")
    }
  }

  test("loadCSV pads ragged rows rather than throwing on them") {
    // The readers report rows as parsed, so this loader -- which indexes every row
    // blindly -- has to pad for itself. `big` is the total conversion: a fabricated
    // cell is "" and reads back as NaN, where a strict `_.toDouble` would throw.
    withCsv("1,2,3\n9\n4,5,6\n") { p =>
      val m = uni.io.FileOps.loadCSV(p.toString, skipHeader = false, s => big(s).toDouble)
      assertEquals((m.rows, m.cols), (3, 3))
      assertEquals(m(1, 0), 9.0)
      assert(m(1, 2).isNaN, s"the fabricated cell should be NaN, got ${m(1, 2)}")
    }
  }

  test("loadCSV takes its width from the whole file, not from a sample") {
    // A row wider than everything before it used to be truncated, because the width
    // came from the reader's 100-row window.
    val body = (1 to 120).map(_ => "1,2").mkString("", "\n", "\n")
    withCsv(body + "1,2,3,4\n") { p =>
      val m = uni.io.FileOps.loadCSV(p.toString, skipHeader = false, s => big(s).toDouble)
      assertEquals((m.rows, m.cols), (121, 4))
      assertEquals(m(120, 3), 4.0)
    }
  }

  test("blank header names become canonical colN, by position") {
    withCsv("a,,c\n1,2,3\n") { p =>
      assertEquals(p.loadSmartD.headers, Vector("a", "col2", "c"))
    }
    // A header shorter than the data gets padded, and the new columns are named
    // rather than left unreachable by name.
    withCsv("a,b\n1,2,3\n4,5,6\n") { p =>
      assertEquals(p.loadSmartD.headers, Vector("a", "b", "col3"))
    }
  }

  test("a file with no header row still reports no headers") {
    // Naming blanks must not turn "no header" into a synthesised one.
    withCsv("1,2,3\n4,5,6\n") { p =>
      assertEquals(p.loadSmartD.headers, Vector.empty[String])
    }
  }

}

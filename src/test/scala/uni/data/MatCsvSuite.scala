package apps

import munit.FunSuite
import uni.data.Mat
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
}

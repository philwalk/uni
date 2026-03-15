package uni.test

// Strict test: ONLY import uni.time.*
import uni.time.*
import munit.FunSuite

class TestImports extends FunSuite {
  test("Configuration works with only uni.time.* import") {
    // parseDate is exported via uni.time package object
    // withDMY is a top-level def in uni.time package
    val dateStr = "02/11/2009"
    
    val d1 = parseDate(dateStr)
    assertEquals(d1.getMonthValue, 2)
    
    withDMY {
      val d2 = parseDate(dateStr)
      assertEquals(d2.getMonthValue, 11)
    }
  }
}

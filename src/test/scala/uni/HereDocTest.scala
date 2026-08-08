package uni.data

import uni.*
import uni.data.HereDoc.*
import munit.FunSuite

class HereDocTest extends FunSuite:
  test("uni.progName should be valid in munit tests") {
    val sourcePath: String = uni.progName
    assert(sourcePath.posix.endsWith("HereDocTest.scala"))
  }
  
  test("DATA should read from end of file, not middle sections") {
    // This test file itself has __DATA__ sections
    val lines = DATA.toList
    
    // Should get data from the trailing section only
    assert(lines.contains("real-data-line-1"))
    assert(lines.contains("real-data-line-2"))
    
    // Should NOT get data from middle section
    assert(!lines.exists(_.contains("fake-data")))
  }
  
  test("END should work similarly to DATA") {
    val lines = END.toList
    
    // Should get data from the trailing section only
    assert(lines.contains("real-data-line-1"))
    assert(lines.contains("real-data-line-2"))
    
    // Should NOT get data from middle section
    assert(!lines.exists(_.contains("fake-data")))
  }

/* __DATA__
fake-data-line-1
fake-data-line-2
*/

// More test code could go here to ensure middle sections are ignored

/* __DATA__
real-data-line-1
real-data-line-2
*/

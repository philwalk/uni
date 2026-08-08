package uni.data

import uni.*
import uni.data.HereDoc.*
import munit.FunSuite

class HereDocTestMultiple extends FunSuite:
  
  test("should only read last __DATA__ section when multiple exist") {
    val lines = DATA(progName).toList
    
    assert(lines.contains("last-section"))
    assert(lines.contains("this is the real data"))
    assert(!lines.exists(_.contains("first-section")))
    assert(!lines.exists(_.contains("should-be-ignored")))
    assert(!lines.exists(_.contains("middle-section")))
    assert(!lines.exists(_.contains("also-ignored")))
  }

/* __DATA__
first-section
should be ignored
*/

object Separator {}

/* __DATA__
middle-section  
also ignored
*/

class AnotherClass {}

/* __DATA__
last-section
this is the real data
*/

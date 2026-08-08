package uni.data

import uni.*
import uni.data.HereDoc.*
import munit.FunSuite

class HereDocTestNoData extends FunSuite:
  test("DATA should be empty when no __DATA__ section") {
    val data = DATA(progName)
    val end = END(progName)
    assert(data.isEmpty)
    assert(end.isEmpty)
  }

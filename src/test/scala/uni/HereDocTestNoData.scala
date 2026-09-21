package uni.data

import uni.*
import uni.data.HereDoc.*
import munit.FunSuite
import scala.concurrent.duration.*

class HereDocTestNoData extends FunSuite:
  // the source lookup runs 20 s on an idle host: munit's 30 s default fails on a loaded one
  override val munitTimeout: Duration = 60.seconds

  test("DATA should be empty when no __DATA__ section") {
    val data = DATA(progName)
    val end = END(progName)
    assert(data.isEmpty)
    assert(end.isEmpty)
  }

package uni

import munit.*

import uni.*        // this is what all client code already does
//import uni.given  // this is what we want to avoid forcing clients to do

class MigrationSuite extends FunSuite:

  val textFile =
    if scala.util.Properties.isWin then
      "C:/Windows/System32/drivers/etc/hosts"
    else
      "/etc/hosts"

  val testSeq: Seq[String] = textFile.asPath.lines

  test("Path.lines returns Seq") {
    val p = textFile.asPath
    @annotation.nowarn // no deprecation warning here
    val seq: Seq[String] = p.lines
    assertEquals(seq, testSeq)
  }

  test("Path.lines can be sorted") {
    val p = textFile.asPath
    @annotation.nowarn // no deprecation warning here
    val sorted: Seq[String] = p.lines.sorted
    assertEquals(sorted, testSeq.sorted)
  }

  test("CVec.show and RVec.show work with import uni.* only") {
    val v: CVecD = CVec(1.0, 2.0, 3.0)
    val r: RVecD = RVec(4.0, 5.0, 6.0)
    assert(v.show.startsWith("3x1 CVec[Double]:"))
    assert(r.show.startsWith("1x3 RVec[Double]:"))
  }

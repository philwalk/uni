package clienttest

import munit.*

import uni.*        // this is what all client code already does
//import uni.given  // this is what we want to avoid forcing clients to do

class MigrationSuite extends FunSuite:

  val textFile =
    if scala.util.Properties.isWin then
      "C:/Windows/System32/drivers/etc/hosts"
    else
      "/etc/hosts"

  val testSeq: Seq[String] = textFile.path.lines.toSeq

  test("Iterator[String] converts to Seq") {
    val p = textFile.path
    val seq: Seq[String] = p.lines
    assertEquals(seq, testSeq)
  }

  test("Iterator[String] can be sorted") {
    val p = textFile.path
    val sorted: Seq[String] = p.lines.sorted
    assertEquals(sorted, testSeq.sorted)
  }

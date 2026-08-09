package uni.time

import munit.FunSuite
import uni.*

/** Checks `SmartParse` against `test-data/smartparse-parity/scala-reference.txt`.
  *
  * The reference is generated from this same implementation, so on the Scala side this is
  * a regression pin rather than a proof: it fails when parser behaviour drifts from what
  * was recorded and reviewed. The Rust side (`rust/tests/smartparse_parity.rs`) reads the
  * identical file, which is what makes the two implementations answer to one oracle.
  *
  * Regenerate with `sbt "Test/runMain uni.apps.SmartParseParityGen"` -- only when the
  * change in answers is intended, and review the diff.
  */
class SmartParseParitySuite extends FunSuite:

  private val fixture = Paths.get("test-data/smartparse-parity/scala-reference.txt")

  private def render(d: UniDateTime): String =
    if d == UniDateTime.BadDate then "!bad"
    else if d == UniDateTime.EmptyDate then "!empty"
    else s"${d.year},${d.month},${d.day},${d.hour},${d.minute},${d.second},${d.nano}"

  private def parseUnder(mode: String, s: String): UniDateTime = mode match
    case "Auto"        => withMDY(parseDateSmart(s))
    case "AutoDayPref" => withDMY(parseDateSmart(s))
    case "MonthFirst"  => withDateOrder(DateOrder.MonthFirst)(parseDateSmart(s))
    case "DayFirst"    => withDateOrder(DateOrder.DayFirst)(parseDateSmart(s))
    case other         => fail(s"unknown mode $other")

  test("every fixture row reproduces") {
    require(fixture.isFile, s"missing fixture ${fixture.posx}; run SmartParseParityGen")
    val rows = fixture.lines.filterNot(l => l.isEmpty || l.startsWith("#"))
    // A fixture that shrank is a fixture that stopped checking; see uni-parity-fixture-gaps.
    require(rows.length > 500, s"suspiciously small fixture: ${rows.length} rows")

    val failures = rows.flatMap { row =>
      row.split('\t') match
        case Array("parse", mode, input, expected) =>
          val got = render(parseUnder(mode, input))
          Option.when(got != expected)(s"parse[$mode] '$input': got $got, want $expected")
        case Array("parse", mode, expected) => // the empty-string input
          val got = render(parseUnder(mode, ""))
          Option.when(got != expected)(s"parse[$mode] '': got $got, want $expected")
        case Array("classify", input, expected) =>
          val got = SmartParse.classify(input).toString
          Option.when(got != expected)(s"classify '$input': got $got, want $expected")
        case Array("classify", expected) =>
          val got = SmartParse.classify("").toString
          Option.when(got != expected)(s"classify '': got $got, want $expected")
        case Array("order", input, expected) =>
          val got = SmartParse.numericDateOrder(input).map(_.toString).getOrElse("!none")
          Option.when(got != expected)(s"order '$input': got $got, want $expected")
        case Array("order", expected) =>
          val got = SmartParse.numericDateOrder("").map(_.toString).getOrElse("!none")
          Option.when(got != expected)(s"order '': got $got, want $expected")
        case other =>
          Some(s"unparseable fixture row: $row")
    }
    assertEquals(failures, Seq.empty[String], s"${failures.length} divergence(s)")
  }

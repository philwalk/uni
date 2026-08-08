package uni.time

import munit.FunSuite
import uni.*

/**
 * Checks `UniDateTime`/`DateFormat` against the committed reference in
 * `test-data/date-parity/`, which `rust/tests/date_parity.rs` also checks itself against.
 *
 * # Why this exists on the Scala side too
 *
 * Symmetry is the point. If only Rust checked the fixture, regenerating it would rewrite the
 * expectations and Rust would keep passing — the fixture would record whatever Scala last
 * did rather than pinning anything. With both sides checking, a change to either
 * implementation fails a test until the fixture is regenerated *and* the other side agrees.
 *
 * This suite is therefore deliberately dumb: it re-derives each value and compares strings,
 * with no independent knowledge of what the answer should be. The *semantics* are pinned
 * elsewhere — `UniDateTimeArithSuite` checks the arithmetic against `java.time` directly, and
 * `DateFormatSuite` checks the patterns against `DateTimeFormatter`. This one only guarantees
 * the two languages agree.
 *
 * Regenerate with `sbt "runMain uni.apps.DateParityGen"` — and only when the reference is
 * meant to move.
 */
class DateParitySuite extends FunSuite:

  def unesc(s: String): String =
    // Backslash last, mirroring the generator escaping backslash first.
    s.replace("\\t", "\t").replace("\\n", "\n").replace("\\r", "\r").replace("\\\\", "\\")

  lazy val rows: Seq[Vector[String]] =
    val f = Paths.get("test-data/date-parity/scala-reference.txt")
    assert(f.exists, s"missing fixture ${f.posx}; regenerate with: sbt \"runMain uni.apps.DateParityGen\"")
    f.lines.filterNot(_.startsWith("#")).filter(_.nonEmpty).map(_.split("\t", -1).toVector).toSeq

  /** Rebuilds the moment a row names. `unchecked` is unavailable here, so the sentinels are
   *  recognised by their field values -- day 0 is what makes that unambiguous. */
  def moment(f: Vector[String]): UniDateTime =
    val Vector(y, mo, d, h, mi, s, nano) = f.slice(1, 8).map(_.toInt): @unchecked
    if d == 0 && y == 1900 then BadDate
    else if d == 0 && y == 1800 then EmptyDate
    else UniDateTime.of(y, mo, d, h, mi, s, nano)

  def of(kind: String): Seq[Vector[String]] = rows.filter(_.head == kind)

  test("the fixture is present and has every kind of case") {
    assert(rows.length > 3000, s"only ${rows.length} rows")
    for kind <- Seq("tostr", "fmt", "dow", "epochday", "valid", "dim", "ofepoch", "shift",
                    "unary", "with", "withdow", "sentinel", "offset", "offsettext") do
      assert(of(kind).nonEmpty, s"no [$kind] rows in the fixture")
  }

  test("toString matches the reference") {
    for r <- of("tostr") do
      assertEquals(moment(r).toString, unesc(r(8)), s"toString for ${r.slice(1, 8)}")
  }

  test("formatting matches the reference for every pattern") {
    for r <- of("fmt") do
      val pattern = unesc(r(8))
      assertEquals(moment(r).toString(pattern), unesc(r(9)), s"[$pattern] on ${r.slice(1, 8)}")
  }

  test("dayOfWeek, epochDay and field validity match the reference") {
    for r <- of("dow") do
      val d = moment(r)
      assertEquals(DateFormat.dayOfWeek(d.year, d.month, d.day).toString, r(8), s"dow ${r.slice(1, 8)}")
    for r <- of("epochday") do
      assertEquals(moment(r).toEpochDay.toString, r(8), s"epochDay ${r.slice(1, 8)}")
    for r <- of("valid") do
      val f = r.slice(1, 8).map(_.toInt)
      assertEquals(UniDateTime.isValid(f(0), f(1), f(2), f(3), f(4), f(5), f(6)).toString, r(8),
        s"isValid ${r.slice(1, 8)}")
  }

  test("daysInMonth matches the reference, including out-of-range months") {
    for r <- of("dim") do
      assertEquals(UniDateTime.daysInMonth(r(1).toInt, r(2).toInt).toString, r(3),
        s"daysInMonth(${r(1)}, ${r(2)})")
  }

  test("ofEpochDay matches the reference across era boundaries") {
    for r <- of("ofepoch") do
      assertEquals(UniDateTime.ofEpochDay(r(1).toLong).toString, unesc(r(2)), s"ofEpochDay(${r(1)})")
  }

  test("shift arithmetic matches the reference") {
    val ops = DateParitySuite.shiftOps
    for r <- of("shift") do
      val op = ops.getOrElse(r(8), fail(s"unknown shift op [${r(8)}]"))
      assertEquals(op(moment(r), r(9).toLong).toString, unesc(r(10)),
        s"${r(8)}(${r(9)}) on ${r.slice(1, 8)}")
  }

  test("atStartOfDay and lastDayOfMonth match the reference") {
    for r <- of("unary") do
      val got = r(8) match
        case "atStartOfDay"   => moment(r).atStartOfDay()
        case "lastDayOfMonth" => moment(r).lastDayOfMonth
        case other            => fail(s"unknown unary op [$other]")
      assertEquals(got.toString, unesc(r(9)), s"${r(8)} on ${r.slice(1, 8)}")
  }

  test("withX field replacement matches the reference, out-of-range included") {
    val ops = DateParitySuite.withOps
    for r <- of("with") do
      val op = ops.getOrElse(r(8), fail(s"unknown with op [${r(8)}]"))
      assertEquals(op(moment(r), r(9).toInt).toString, unesc(r(10)),
        s"${r(8)}(${r(9)}) on ${r.slice(1, 8)}")
  }

  test("withDayOfWeek matches the reference") {
    for r <- of("withdow") do
      val dow = java.time.DayOfWeek.of(r(8).toInt)
      assertEquals(moment(r).withDayOfWeek(dow).toString, unesc(r(9)),
        s"withDayOfWeek(${r(8)}) on ${r.slice(1, 8)}")
  }

  test("the sentinels absorb every shift, per the reference") {
    val ops = DateParitySuite.shiftOps
    for r <- of("sentinel") do
      val op = ops.getOrElse(r(8), fail(s"unknown shift op [${r(8)}]"))
      assertEquals(op(moment(r), 1L).toString, unesc(r(9)), s"${r(8)} on ${r.slice(1, 8)}")
  }

  test("offset extraction matches the reference, anchoring included") {
    for r <- of("offset") do
      val text = unesc(r(1))
      val expected = if r(2) == "none" then None else Some(r(2).toInt)
      assertEquals(UniDateTime.offsetMinutesOf(text), expected, s"offsetMinutesOf [$text]")
  }

  test("offset validity and rendering match the reference") {
    for r <- of("offsettext") do
      val o = r(1).toInt
      assertEquals(UniDateTime.isValidOffset(o).toString, r(2), s"isValidOffset($o)")
      val d = UniDateTime.of(2024, 5, 12, 14, 30, offsetMinutes = Some(o))
      assertEquals(d.offsetText, unesc(r(3)), s"offsetText for $o")
      assertEquals(d.toString, unesc(r(4)), s"toString for offset $o")
  }

object DateParitySuite:
  /** Named by the string the fixture carries, so a row reads as the call it pins. */
  val shiftOps: Map[String, (UniDateTime, Long) => UniDateTime] = Map(
    "plusNanos"    -> (_.plusNanos(_)),
    "plusSeconds"  -> (_.plusSeconds(_)),
    "plusMinutes"  -> (_.plusMinutes(_)),
    "plusHours"    -> (_.plusHours(_)),
    "plusDays"     -> (_.plusDays(_)),
    "plusWeeks"    -> (_.plusWeeks(_)),
    "plusMonths"   -> (_.plusMonths(_)),
    "plusYears"    -> (_.plusYears(_)),
    "minusNanos"   -> (_.minusNanos(_)),
    "minusSeconds" -> (_.minusSeconds(_)),
    "minusMinutes" -> (_.minusMinutes(_)),
    "minusHours"   -> (_.minusHours(_)),
    "minusDays"    -> (_.minusDays(_)),
    "minusWeeks"   -> (_.minusWeeks(_)),
    "minusMonths"  -> (_.minusMonths(_)),
    "minusYears"   -> (_.minusYears(_)),
  )

  val withOps: Map[String, (UniDateTime, Int) => UniDateTime] = Map(
    "withYear"       -> (_.withYear(_)),
    "withMonth"      -> (_.withMonth(_)),
    "withDayOfMonth" -> (_.withDayOfMonth(_)),
    "withHour"       -> (_.withHour(_)),
    "withMinute"     -> (_.withMinute(_)),
    "withSecond"     -> (_.withSecond(_)),
    "withNano"       -> (_.withNano(_)),
  )

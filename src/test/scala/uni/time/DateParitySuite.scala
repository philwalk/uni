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
                    "unary", "with", "withdow", "sentinel", "offset", "offsettext",
                    "between", "duration", "eom", "quikdate", "quikdt", "mon2num", "getmillis") do
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
      assertEquals(d.dayOfWeekName, r(9), s"dayOfWeekName ${r.slice(1, 8)}")
      assertEquals(d.dayOfWeekFull, r(10), s"dayOfWeekFull ${r.slice(1, 8)}")
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

  test("round(_, 6) matches the reference") {
    // The primitive the file-age chain is built on. Pinned here because the `lastMod*Ago`
    // methods read the clock and so cannot be pinned themselves.
    for r <- of("round6") do
      assertEquals(round(r(1).toDouble, 6), r(2).toDouble, s"round(${r(1)}, 6)")
  }

  test("epoch2DateTime matches the reference at fixed offsets") {
    // Fixed offsets rather than named zones: a zone name needs a timezone database, which the
    // Rust port cannot consult, and a system-default zone would make the fixture depend on
    // where it was generated.
    for r <- of("epochconv") do
      val millis = r(1).toLong
      val offMin = r(2).toInt
      val z = java.time.ZoneOffset.ofTotalSeconds(offMin * 60)
      val d = TimeUtils.epoch2DateTime(millis, z)
      assertEquals(d.toString, unesc(r(3)), s"epoch2DateTime($millis, $offMin)")
      // The offset is deliberately NOT recorded -- the zone shifts the fields and is then
      // discarded. The Rust port matched this only after the fixture forced the question.
      val expectedOffset = if r(4) == "none" then None else Some(r(4).toInt)
      assertEquals(d.offsetMinutes, expectedOffset, s"offsetMinutes for $millis at $offMin")
  }

  test("the file-reading path matches the reference, at a set mtime") {
    // The mtime is *set* by the generator, which is what makes this deterministic. Reading
    // whatever an arbitrary file happened to carry would not be.
    //
    // No offset anywhere: file timestamps are UTC in both languages. A system-local expected
    // value would have depended on where the fixture was generated.
    for r <- of("mtime") do
      val f = Paths.get(s"test-data/date-parity/inputs/${r(1)}")
      assert(f.exists, s"missing fixture input ${f.posx}")
      // Re-set the mtime from the row before asserting: a disk timestamp survives
      // neither `git clone` (checkout time) nor every transport (macOS received a
      // pre-epoch mtime wrapped to unsigned 32-bit). The row is the authority; the
      // file on disk is just the vehicle. The generator still sets it at creation.
      java.nio.file.Files.setLastModifiedTime(
        f, java.nio.file.attribute.FileTime.fromMillis(r(2).toLong))
      assertEquals(f.lastModified.toString, r(2), s"lastModified of ${r(1)}")
      assertEquals(f.lastModifiedTime.toString, unesc(r(3)), s"lastModifiedTime of ${r(1)}")
      assertEquals(f.lastModifiedYMD, unesc(r(4)), s"lastModifiedYMD of ${r(1)}")
      assertEquals(f.weekDay.toString, r(5), s"weekDay of ${r(1)}")
      assertEquals(f.weekDayName, r(6), s"weekDayName of ${r(1)}")
      // The inconsistency this change removed: these used to disagree by the local offset.
      assertEquals(f.lastModifiedTime, f.epoch2DateTime(f.lastModified),
        s"lastModifiedTime must agree with epoch2DateTime for ${r(1)}")
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

  /** The second moment of a two-moment row (fields at columns 8..14). */
  private def momentB(f: Vector[String]): UniDateTime =
    moment(Vector(f.head) ++ f.slice(8, 15))

  test("the between family matches the reference at UTC") {
    for r <- of("between") do
      val (a, b) = (moment(r), momentB(r))
      val tag = s"${r.slice(1, 8)} -> ${r.slice(8, 15)}"
      assertEquals(TimeUtils.secondsBetween(a, b).toString, r(15), s"seconds $tag")
      assertEquals(TimeUtils.daysBetween(a, b).toString, r(16), s"days $tag")
      assertEquals(TimeUtils.elapsedDays(a, b).toString, r(17), s"elapsed $tag")
      assertEquals(f"${TimeUtils.minutesBetween(a, b)}%.17e", r(18), s"minutes $tag")
      assertEquals(f"${TimeUtils.hoursBetween(a, b)}%.17e", r(19), s"hours $tag")
      assertEquals(f"${TimeUtils.daysRounded(a, b)}%.17e", r(20), s"daysRounded $tag")
  }

  test("getDuration matches the reference, floor semantics included") {
    for r <- of("duration") do
      val (dd, hh, mm, ss) = TimeUtils.getDuration(moment(r), momentB(r))
      assertEquals(Seq(dd, hh, mm, ss).map(_.toString), r.slice(15, 19).toSeq,
        s"getDuration ${r.slice(1, 8)} -> ${r.slice(8, 15)}")
  }

  test("endOfMonth matches the reference") {
    for r <- of("eom") do
      assertEquals(TimeUtils.endOfMonth(moment(r)).toString, unesc(r(8)),
        s"endOfMonth ${r.slice(1, 8)}")
  }

  test("getMillis matches the reference and round-trips through epoch2DateTime") {
    for r <- of("getmillis") do
      val d      = moment(r)
      val millis = d.toLocalDateTime.getMillis()
      assertEquals(millis.toString, r(8), s"getMillis ${r.slice(1, 8)}")
      // exact inverse, once sub-millisecond nanos are dropped
      assertEquals(TimeUtils.epoch2DateTime(millis, TimeUtils.UTC),
        d.withNano(d.nano / 1000000 * 1000000), s"round trip ${r.slice(1, 8)}")
  }

  test("the strict quik parsers match the reference on well-formed input") {
    for r <- of("quikdate") do
      assertEquals(TimeUtils.quikDate(unesc(r(1))).toString, unesc(r(2)), s"quikDate [${r(1)}]")
    for r <- of("quikdt") do
      assertEquals(TimeUtils.quikDateTime(unesc(r(1))).toString, unesc(r(2)), s"quikDateTime [${r(1)}]")
  }

  test("monthAbbrev2Number matches the reference") {
    for r <- of("mon2num") do
      assertEquals(TimeUtils.monthAbbrev2Number(unesc(r(1))).toString, r(2),
        s"monthAbbrev2Number [${r(1)}]")
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

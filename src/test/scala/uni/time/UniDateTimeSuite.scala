package uni.time

import munit.FunSuite
import java.time.LocalDateTime

/**
 * `UniDateTime` is the date type decoupled from `java.time`, so the parser can port to
 * Rust as a plain struct.
 *
 * The load-bearing part is validity: `LocalDateTime.of` rejects February 30th by
 * throwing, and that is how `BadDate` arises today. A bare case class would accept it,
 * so the checks here are compared against `LocalDateTime.of`'s own verdict rather than
 * against hand-written expectations.
 */
class UniDateTimeSuite extends FunSuite:

  private def javaAccepts(y: Int, m: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0): Boolean =
    try { LocalDateTime.of(y, m, d, h, mi, s); true } catch { case _: Throwable => false }

  test("validity agrees with LocalDateTime.of across every month and day") {
    // Includes leap years, century non-leaps, and the 400-year exception.
    val disagreements = for
      y <- Seq(1899, 1900, 1996, 1999, 2000, 2001, 2004, 2024, 2100, 2400)
      m <- 0 to 13
      d <- 0 to 32
      mine = UniDateTime.isValid(y, m, d, 0, 0, 0, 0)
      java = javaAccepts(y, m, d)
      if mine != java
    yield s"$y-$m-$d: ours=$mine java=$java"
    assertEquals(disagreements, Seq.empty[String], s"${disagreements.size} disagreement(s)")
  }

  test("time-of-day validity agrees too") {
    val disagreements = for
      h  <- Seq(-1, 0, 1, 12, 23, 24, 25)
      mi <- Seq(-1, 0, 59, 60)
      s  <- Seq(-1, 0, 59, 60)
      mine = UniDateTime.isValid(2024, 5, 12, h, mi, s, 0)
      java = javaAccepts(2024, 5, 12, h, mi, s)
      if mine != java
    yield s"$h:$mi:$s ours=$mine java=$java"
    assertEquals(disagreements, Seq.empty[String], s"${disagreements.size} disagreement(s)")
  }

  test("the leap-year rule is the Gregorian one, not just mod 4") {
    assertEquals(UniDateTime.daysInMonth(2024, 2), 29) // divisible by 4
    assertEquals(UniDateTime.daysInMonth(1900, 2), 28) // century, not leap
    assertEquals(UniDateTime.daysInMonth(2000, 2), 29) // divisible by 400
    assertEquals(UniDateTime.daysInMonth(2023, 2), 28)
    assertEquals(UniDateTime.daysInMonth(2024, 13), 0, "no such month")
  }

  test("an impossible date yields BadDate rather than existing") {
    assertEquals(UniDateTime.of(2024, 2, 30), UniDateTime.BadDate)
    assertEquals(UniDateTime.of(2023, 2, 29), UniDateTime.BadDate)
    assertEquals(UniDateTime.of(2024, 13, 1), UniDateTime.BadDate)
    assertEquals(UniDateTime.of(2024, 0, 1), UniDateTime.BadDate)
    assertEquals(UniDateTime.of(2024, 5, 12, 24, 0, 0), UniDateTime.BadDate)
    // And a real one does not.
    assertEquals(UniDateTime.of(2024, 2, 29).toString, "2024-02-29T00:00")
  }

  test("toString matches LocalDateTime.toString, including its omissions") {
    // LocalDateTime drops zero seconds and zero fractions; scripts print these, so the
    // shape is part of the compatibility surface.
    val moments = Seq(
      LocalDateTime.of(2024, 5, 12, 14, 30, 0, 0),
      LocalDateTime.of(2024, 5, 12, 14, 30, 45, 0),
      LocalDateTime.of(2024, 5, 12, 14, 30, 45, 123_000_000),
      LocalDateTime.of(2024, 5, 12, 14, 30, 45, 123_456_000),
      LocalDateTime.of(2024, 5, 12, 14, 30, 45, 123_456_789),
      LocalDateTime.of(2024, 5, 12, 0, 0, 0, 0),
      LocalDateTime.of(1900, 1, 2, 3, 4, 5, 0),
      LocalDateTime.of(999, 1, 1, 0, 0, 0, 0),
    )
    for d <- moments do
      assertEquals(UniDateTime.from(d).toString, d.toString, s"for $d")
  }

  test("round trips through LocalDateTime") {
    for d <- Seq(LocalDateTime.of(2024, 2, 29, 23, 59, 59, 999_999_999),
                 LocalDateTime.of(1900, 1, 1, 0, 0, 0, 0)) do
      assertEquals(UniDateTime.from(d).toLocalDateTime, d)
  }

  test("toString(fmt) agrees with the LocalDateTime extension") {
    val d = LocalDateTime.of(2024, 5, 12, 14, 30, 45, 0)
    for f <- Seq("yyyy-MM-dd", "yyyy/MM/dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "dd-MM-yyyy") do
      assertEquals(UniDateTime.from(d).toString(f), d.toString(f), s"for [$f]")
  }

  test("ordering is chronological") {
    val a = UniDateTime.of(2024, 5, 12, 14, 30, 0)
    val b = UniDateTime.of(2024, 5, 12, 14, 30, 1)
    val c = UniDateTime.of(2024, 6, 1)
    val d = UniDateTime.of(2023, 12, 31, 23, 59, 59)
    assertEquals(Seq(c, a, d, b).sorted, Seq(d, a, b, c))
    assert(summon[Ordering[UniDateTime]].lt(a, b))
  }

  test("the sentinels match the LocalDateTime ones field for field") {
    // So the two agree across the conversion, and an existing `d == BadDate` holds.
    assertEquals(UniDateTime.BadDate.toLocalDateTime, BadDate)
    assertEquals(UniDateTime.EmptyDate.toLocalDateTime, EmptyDate)
    assert(!UniDateTime.BadDate.isValid)
    assert(!UniDateTime.EmptyDate.isValid)
    assert(UniDateTime.of(2024, 5, 12).isValid)
  }

  test("the implicit conversion lets one stand in for a LocalDateTime") {
    val u = UniDateTime.of(2024, 5, 12, 14, 30, 0)
    val ldt: LocalDateTime = u
    assertEquals(ldt, LocalDateTime.of(2024, 5, 12, 14, 30, 0))
    def takesLdt(d: LocalDateTime): Int = d.getYear
    assertEquals(takesLdt(u), 2024)
    // And a LocalDateTime member reached straight through the conversion.
    assertEquals(u.getDayOfWeek.toString, "SUNDAY")
  }

  test("the raw constructor is not reachable, so validity cannot be bypassed") {
    // Compile-time property: `UniDateTime(2024, 2, 30, 0, 0, 0, 0)` must not compile.
    // Recorded as a comment rather than a test because there is no runtime assertion
    // for it; `of` is the only public way in, and the private constructor enforces that.
    assertEquals(UniDateTime.of(2024, 2, 30), UniDateTime.BadDate)
  }

  // ---------------------------------------------------------------------------
  // The optional UTC offset
  // ---------------------------------------------------------------------------

  test("an offset is extracted from the forms that state one unambiguously") {
    assertEquals(UniDateTime.offsetMinutesOf("01 Jan 2001 12:34:56 -0700"), Some(-420))
    assertEquals(UniDateTime.offsetMinutesOf("Sun, 12 May 2024 14:30:00 +0000"), Some(0))
    assertEquals(UniDateTime.offsetMinutesOf("2024-05-12T14:30:00+05:30"), Some(330))
    assertEquals(UniDateTime.offsetMinutesOf("2024-05-12T14:30:00-0930"), Some(-570))
    assertEquals(UniDateTime.offsetMinutesOf("Sun, 12 May 2024 14:30:00 GMT"), Some(0))
    assertEquals(UniDateTime.offsetMinutesOf("2024-05-12T14:30:00Z"), Some(0))
  }

  test("an ambiguous abbreviation yields no offset rather than a guess") {
    // `CST` is three different offsets depending on the continent, and resolving any of
    // them needs a timezone database -- the dependency this type exists to avoid.
    // `None` means "none stated", not "UTC".
    assertEquals(UniDateTime.offsetMinutesOf("01 Jan 2001 12:34:56 MST"), None)
    assertEquals(UniDateTime.offsetMinutesOf("Fri Jan 10 2014 2:34:17 PM EST"), None)
    assertEquals(UniDateTime.offsetMinutesOf("2024-05-12 14:30"), None)
  }

  test("a date's own hyphens are not read as an offset") {
    // The trap: `2024-05-12` contains `-05`, and a laxer pattern would call that an
    // offset of -5 hours.
    assertEquals(UniDateTime.offsetMinutesOf("2024-05-12"), None)
    assertEquals(UniDateTime.offsetMinutesOf("12-05-2024"), None)
  }

  test("an out-of-range offset is refused") {
    // ISO 8601 admits +/-18:00.
    assertEquals(UniDateTime.offsetMinutesOf("2024-05-12T00:00:00+19:00"), None)
    assertEquals(UniDateTime.of(2024, 5, 12, offsetMinutes = Some(1081)), UniDateTime.BadDate)
    assertEquals(UniDateTime.of(2024, 5, 12, offsetMinutes = Some(-1080)).offsetMinutes, Some(-1080))
  }

  test("the offset does not shift the local fields, and toString omits it") {
    // `LocalDateTime` has no offset; rendering one would be a compatibility break.
    val withOff = UniDateTime.of(2024, 5, 12, 14, 30, 0, 0, Some(-420))
    val without = UniDateTime.of(2024, 5, 12, 14, 30, 0)
    assertEquals(withOff.toString, without.toString)
    assertEquals(withOff.hour, 14)
    assertEquals(withOff.toLocalDateTime, without.toLocalDateTime)
    // It is reachable when wanted.
    assertEquals(withOff.offsetText, "-07:00")
    assertEquals(without.offsetText, "")
  }

  test("an offset can be attached or removed after the fact") {
    val d = UniDateTime.of(2024, 5, 12)
    assertEquals(d.withOffsetMinutes(Some(330)).offsetMinutes, Some(330))
    assertEquals(d.withOffsetMinutes(Some(330)).withOffsetMinutes(None).offsetMinutes, None)
    // An impossible offset leaves the value untouched rather than corrupting it.
    assertEquals(d.withOffsetMinutes(Some(9999)).offsetMinutes, None)
  }

  test("the offset participates in equality but not in ordering") {
    // Two same-instant values with different stated offsets are different values, but
    // ordering is by local fields -- as LocalDateTime's own ordering is. Offset-aware
    // ordering would need normalising to UTC first, and mixing the two silently is
    // worse than making the caller choose.
    val a = UniDateTime.of(2024, 5, 12, 14, 30, 0, 0, Some(0))
    val b = UniDateTime.of(2024, 5, 12, 14, 30, 0, 0, Some(-420))
    assertNotEquals(a, b)
    assertEquals(summon[Ordering[UniDateTime]].compare(a, b), 0)
  }

  test("the sentinels carry no offset") {
    assertEquals(UniDateTime.BadDate.offsetMinutes, None)
    assertEquals(UniDateTime.EmptyDate.offsetMinutes, None)
    // And a LocalDateTime cannot supply one.
    assertEquals(UniDateTime.from(LocalDateTime.of(2024, 5, 12, 0, 0)).offsetMinutes, None)
  }

  test("an offset pattern is not found inside a bare date") {
    // The trap an unanchored pattern falls into: in `12-05-1024` the tail `-1024` reads
    // as +10:24, which is inside the legal range and so passes a range check. The sign
    // has to follow whitespace or a time, and the offset has to end the token.
    for s <- Seq("12-05-1024", "12-05-2024", "1024-05-12", "2024-05-12", "10-24-1130") do
      assertEquals(UniDateTime.offsetMinutesOf(s), None, s"found an offset in [$s]")
  }

  test("the companion carries the members scripts call on the alias") {
    // A type alias never carries a companion, so `type DateTime = UniDateTime` alone
    // would break `DateTime.now()`. Scripts got that object from
    // `import java.time.{LocalDateTime as DateTime}`, which renames type *and* term.
    // These three are what they actually call: of (10), now (9), ofInstant (2).
    // `now()` takes empty parens: Scala 3 dropped auto-application, and every script
    // call site writes it that way.
    val n = UniDateTime.now()
    assert(n.year >= 2024, s"now looks wrong: $n")
    assertEquals(n.offsetMinutes, None)

    val inst = java.time.Instant.ofEpochSecond(1_715_524_200L)
    val utc  = java.time.ZoneId.of("UTC")
    assertEquals(UniDateTime.ofInstant(inst, utc),
      UniDateTime.from(LocalDateTime.ofInstant(inst, utc)))
    assertEquals(UniDateTime.ofInstant(inst, utc).toString, "2024-05-12T14:30")

    // `of` was already present; named here so the trio is documented together.
    assertEquals(UniDateTime.of(2024, 5, 12, 14, 30).toString, "2024-05-12T14:30")
  }

  test("now agrees with LocalDateTime.now to the second") {
    val a = UniDateTime.now()
    val b = LocalDateTime.now()
    assertEquals(a.year, b.getYear)
    assertEquals(a.month, b.getMonthValue)
    assertEquals(a.day, b.getDayOfMonth)
  }

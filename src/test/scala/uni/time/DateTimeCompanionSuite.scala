package uni.time

import munit.FunSuite

/**
 * The `DateTime` alias needs a term half.
 *
 * `type DateTime = ...` lives in the type namespace only, so `DateTime.now()` resolves
 * against nothing. Scripts had that object via
 * `import java.time.{LocalDateTime as DateTime}`, which renames both halves; removing
 * the import broke them. An alias cannot supply it — `LocalDateTime`'s statics have no
 * value-level identity in Scala — so these delegate.
 *
 * Members and call form are taken from the script corpus: `of` (10 calls), `now` (9),
 * `ofInstant` (2), and `now()` with empty parens, since Scala 3 dropped
 * auto-application.
 */
class DateTimeCompanionSuite extends FunSuite:

  test("DateTime.now() is callable with parens and yields the alias type") {
    val d: DateTime = DateTime.now()
    assert(d.getYear >= 2024, s"$d")
  }

  test("DateTime.of matches LocalDateTime.of") {
    assertEquals(DateTime.of(2024, 5, 12, 14, 30, 45),
      java.time.LocalDateTime.of(2024, 5, 12, 14, 30, 45))
    // Defaulted time fields, as scripts use it.
    assertEquals(DateTime.of(2024, 5, 12), java.time.LocalDateTime.of(2024, 5, 12, 0, 0))
  }

  test("DateTime.ofInstant matches LocalDateTime.ofInstant") {
    val inst = java.time.Instant.ofEpochSecond(1_715_524_200L)
    val utc = java.time.ZoneId.of("UTC")
    assertEquals(DateTime.ofInstant(inst, utc), java.time.LocalDateTime.ofInstant(inst, utc))
    assertEquals(DateTime.ofInstant(inst, utc).toString, "2024-05-12T14:30")
  }

  test("DateTime.parse uses uni's parser, not LocalDateTime.parse") {
    // ISO-only-and-throwing would be the wrong choice here: a script reaching for this
    // in a `uni` context wants the lenient parser and the sentinel.
    assertEquals(DateTime.parse("Aug 18 22:29:47 2018").toString, "2018-08-18T22:29:47")
    assertEquals(DateTime.parse("20200102").toString, "2020-01-02T00:00")
    assertEquals(DateTime.parse("not a date"), BadDate)
  }

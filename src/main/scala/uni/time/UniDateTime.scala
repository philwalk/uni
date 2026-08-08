package uni.time

import java.time.LocalDateTime
import scala.language.implicitConversions

/**
 * A date and time as plain integer fields, independent of `java.time`.
 *
 * # Why
 *
 * `SmartParse` returning a `LocalDateTime` ties the parser to the JVM. Returning this
 * instead lets the same parser port to Rust as a plain struct, with no date-library
 * dependency on either side -- `std` has none, and the alternative was `jiff` plus a
 * Java-pattern-to-strftime translation layer.
 *
 * Source compatibility is kept by [[UniDateTime.given_Conversion_UniDateTime_LocalDateTime]],
 * so `parseDate(s).getYear` and passing one to a `java.time` API keep working. Client
 * scripts need no import: applying the conversion produces a feature *warning*, not an
 * error, and only when `-feature` is on.
 *
 * # Validity is enforced here
 *
 * `LocalDateTime.of` rejects February 30th by throwing, and that is how `BadDate` gets
 * produced today. A bare case class would accept it, so [[UniDateTime.of]] validates
 * and yields [[UniDateTime.BadDate]] instead. The constructor is private to make that
 * the only way in -- an unvalidated `UniDateTime(2024, 2, 30, ...)` would be a hole
 * straight through the type.
 *
 * # The offset is carried but not applied
 *
 * [[UniDateTime.offsetMinutes]] records a stated UTC offset, which `ChronoParse` kept in
 * its `timezone` field and `SmartParse` threw away. It does not shift the local fields
 * and `toString` omits it, so a value with an offset prints exactly as one without --
 * `LocalDateTime` has no offset, and changing what `toString` renders would be a
 * compatibility break for no gain.
 *
 * # Sentinels, not exceptions
 *
 * [[BadDate]] and [[EmptyDate]] carry the same field values as the `LocalDateTime`
 * sentinels in `TimeUtils`, so the two agree across the conversion and an existing
 * `if d == BadDate` keeps working. They are what make this usable for ripping through a
 * CSV column without wrapping every cell in a `Try`.
 */
case class UniDateTime private (
  year: Int, month: Int, day: Int,
  hour: Int, minute: Int, second: Int, nano: Int,
  // No default: the constructor is private and every call site passes this
  // explicitly, so a default only generates an unused accessor.
  offsetMinutes: Option[Int]
):
  /** Formats with a `java.time`-style pattern. See [[DateFormat]] for the subset. */
  def toString(fmt: String): String =
    DateFormat.format(year, month, day, hour, minute, second, nano, fmt)

  /** ISO-8601, matching `LocalDateTime.toString` -- except for the sentinels.
   *
   *  The sentinels deliberately break that shape. Rendering [[UniDateTime.BadDate]] as
   *  `1900-01-02T03:04:05` is the classic silent-sentinel bug: it reads as real data, so an
   *  unparsed value looks like a genuine date from 1900 in every log line, error message
   *  and printed column. The angle brackets cannot be mistaken for a column value.
   *
   *  Only this method changes. The pattern formatters -- [[ymd]], [[fmt]] and
   *  [[toString(fmt:String)*]] -- keep producing digits, because a caller naming
   *  `yyyyMMdd` is building a filename, a CSV key or a SQL bind. A marker there would not
   *  fail loudly; it would yield valid-looking SQL that quietly matches nothing, or a
   *  filename the OS rejects. [[isValid]] is the guard for those paths.
   *
   *  The test is sentinel *identity*, not the field values, and the offset is part of that
   *  identity -- so a value that genuinely represents 1900-01-02T03:04:05 with a stated
   *  offset still renders as a date. It also means this cannot live in [[DateFormat]],
   *  which works on plain ints and is shared with the `LocalDateTime` extension.
   */
  override def toString: String =
    if this == UniDateTime.BadDate then "<BadDate>"
    else if this == UniDateTime.EmptyDate then "<EmptyDate>"
    else isoString

  /** The ISO rendering proper: seconds omitted when zero, fraction when zero --
   *  `2024-05-12T14:30`, not `2024-05-12T14:30:00`. Scripts print these, so the shape is
   *  part of the compatibility surface. Split out so [[toString]] can special-case the
   *  sentinels without burying this. */
  private def isoString: String =
    val date = f"$year%04d-$month%02d-$day%02d"
    val hm   = f"$hour%02d:$minute%02d"
    if nano != 0 then
      // `LocalDateTime` prints 3, 6 or 9 fractional digits, never a trailing group of
      // zeros.
      val frac =
        if nano      % 1000000 == 0 then f"${nano / 1000000}%03d"
        else if nano % 1000    == 0 then f"${nano / 1000}%06d"
        else                             f"$nano%09d"
      f"${date}T$hm:$second%02d.$frac"
    else if second != 0 then f"${date}T$hm:$second%02d"
    else s"${date}T$hm"

  /** This moment with a UTC offset attached, or with it removed by passing `None`.
   *
   *  A sentinel absorbs this, as it absorbs arithmetic. Copying one with a new offset would
   *  otherwise yield a value that is *not* a sentinel yet still names an impossible date --
   *  so `isValid` would answer `true` and `toLocalDateTime` would throw.
   */
  def withOffsetMinutes(offset: Option[Int]): UniDateTime =
    if !isValid then this
    else if offset.forall(UniDateTime.isValidOffset) then copy(offsetMinutes = offset)
    else this

  /** The offset as `+HH:MM`, or empty when there is none. */
  def offsetText: String = offsetMinutes.fold("") { o =>
    val sign = if o < 0 then "-" else "+"
    f"$sign${o.abs / 60}%02d:${o.abs % 60}%02d"
  }

  // ── Shorthands mirroring the `LocalDateTime` extension surface ─────────────
  //
  // These are reachable through the conversion, but defined here so the common
  // formatting and comparison calls stay on the field values: a member always wins over
  // an extension, so no conversion is applied and nothing detours through `java.time`.
  // They are also the four that the Rust port needs, and it has no `java.time` to detour
  // through.

  /** `yyyy-MM-dd`. */
  def ymd: String = toString("yyyy-MM-dd")

  /** `yyyy-MM-dd HH:mm:ss`. */
  def ymdhms: String = toString("yyyy-MM-dd HH:mm:ss")

  /** Formats with a pattern, the same as [[toString(fmt:String)*]]. */
  def fmt(pattern: String): String = toString(pattern)

  /** Chronological by local fields, ignoring the offset -- the same order as
   *  [[UniDateTime.given_Ordering_UniDateTime]], which is `LocalDateTime`'s order too.
   *
   *  Written out field by field rather than through the `Ordering` so a comparison in a
   *  loop over a date column allocates nothing.
   */
  private def cmp(other: UniDateTime): Int =
    if year   != other.year   then year   - other.year
    else if month  != other.month  then month  - other.month
    else if day    != other.day    then day    - other.day
    else if hour   != other.hour   then hour   - other.hour
    else if minute != other.minute then minute - other.minute
    else if second != other.second then second - other.second
    else nano - other.nano

  def isBefore(other: UniDateTime): Boolean = cmp(other) <  0
  def isAfter(other: UniDateTime): Boolean  = cmp(other) >  0
  def <(other: UniDateTime): Boolean        = cmp(other) <  0
  def <=(other: UniDateTime): Boolean       = cmp(other) <= 0
  def >(other: UniDateTime): Boolean        = cmp(other) >  0
  def >=(other: UniDateTime): Boolean       = cmp(other) >= 0

  // ── Calendar arithmetic ────────────────────────────────────────────────────
  //
  // These must return `UniDateTime` rather than being reached through the conversion,
  // and that is not a stylistic preference. Without them, `d.plusWeeks(1)` yields a
  // `LocalDateTime`, so a script that shifts a date in one branch and not the other gets
  // the inferred union `LocalDateTime | UniDateTime` -- which satisfies neither type's
  // position and cannot be rescued by a conversion, because a conversion applies to a
  // value and a union is not one. Measured across the script corpus, that union was the
  // single largest source of breakage when the parser's type moved.
  //
  // The bodies delegate to `java.time` for the calendar math. Month-end clamping and
  // leap years are subtle enough that borrowing a correct implementation beats writing a
  // second one, and the *type* is what needed decoupling: annotations, the parser, and
  // the shape of the Rust struct. Rust replaces these bodies with field arithmetic, one
  // at a time, with this version available as the oracle -- a better position than
  // writing them blind.
  // Two contracts to uphold, both of which the naive delegation broke:
  //
  //  - An impossible result is data, not an exception. `withDayOfMonth(31)` in February
  //    throws `DateTimeException` inside `java.time`, and letting that escape would make
  //    a value obtained from `of` -- which deliberately yields `BadDate` rather than
  //    throwing -- start throwing again one method call later.
  //  - A sentinel absorbs arithmetic, the way `BigNaN` does in `uni.data`. `BadDate` is
  //    "this did not parse"; shifting it by a day would produce 1900-01-03, an ordinary
  //    date that no longer compares equal to `BadDate`, so a parse failure would slip
  //    past the very check that exists to catch it.
  private def lift(f: LocalDateTime => LocalDateTime): UniDateTime =
    if !isValid then this
    else
      try UniDateTime.from(f(toLocalDateTime)).withOffsetMinutes(offsetMinutes)
      catch case _: java.time.DateTimeException => UniDateTime.BadDate

  def plusNanos(n: Long): UniDateTime   = lift(_.plusNanos(n))
  def plusSeconds(n: Long): UniDateTime = lift(_.plusSeconds(n))
  def plusMinutes(n: Long): UniDateTime = lift(_.plusMinutes(n))
  def plusHours(n: Long): UniDateTime   = lift(_.plusHours(n))
  def plusDays(n: Long): UniDateTime    = lift(_.plusDays(n))
  def plusWeeks(n: Long): UniDateTime   = lift(_.plusWeeks(n))
  def plusMonths(n: Long): UniDateTime  = lift(_.plusMonths(n))
  def plusYears(n: Long): UniDateTime   = lift(_.plusYears(n))

  def minusNanos(n: Long): UniDateTime   = lift(_.minusNanos(n))
  def minusSeconds(n: Long): UniDateTime = lift(_.minusSeconds(n))
  def minusMinutes(n: Long): UniDateTime = lift(_.minusMinutes(n))
  def minusHours(n: Long): UniDateTime   = lift(_.minusHours(n))
  def minusDays(n: Long): UniDateTime    = lift(_.minusDays(n))
  def minusWeeks(n: Long): UniDateTime   = lift(_.minusWeeks(n))
  def minusMonths(n: Long): UniDateTime  = lift(_.minusMonths(n))
  def minusYears(n: Long): UniDateTime   = lift(_.minusYears(n))

  def withYear(y: Int): UniDateTime       = lift(_.withYear(y))
  def withMonth(m: Int): UniDateTime      = lift(_.withMonth(m))
  def withDayOfMonth(d: Int): UniDateTime = lift(_.withDayOfMonth(d))
  def withHour(h: Int): UniDateTime       = lift(_.withHour(h))
  def withMinute(m: Int): UniDateTime     = lift(_.withMinute(m))
  def withSecond(sec: Int): UniDateTime   = lift(_.withSecond(sec))
  def withNano(n: Int): UniDateTime       = lift(_.withNano(n))

  def atStartOfDay(): UniDateTime =
    if !isValid then this else copy(hour = 0, minute = 0, second = 0, nano = 0)

  /** The *next* occurrence of `dow`, matching the `LocalDateTime` extension of the same
   *  name -- which uses `TemporalAdjusters.next`, so a date already on `dow` moves a full
   *  week forward rather than staying put. */
  def withDayOfWeek(dow: java.time.DayOfWeek): UniDateTime =
    lift(_.`with`(java.time.temporal.TemporalAdjusters.next(dow)))

  def lastDayOfMonth: UniDateTime =
    if !isValid then this else copy(day = UniDateTime.daysInMonth(year, month))

  /** Days since 1970-01-01, ignoring the time of day.
   *
   *  What `toLocalDate.toEpochDay` gave, without the detour: scripts use epoch days as a
   *  compact integer key for date ranges and gap arithmetic, and reaching it through
   *  `java.time` was the last reason some of them imported `java.time.LocalDate` at all.
   *
   *  A sentinel has no meaningful epoch day; both yield `Long.MinValue` so a caller
   *  comparing or sorting cannot mistake one for a date near the epoch.
   */
  def toEpochDay: Long =
    if !isValid then Long.MinValue else UniDateTime.epochDayOf(year, month, day)

  /** 1 = Monday .. 7 = Sunday, as [[DateFormat.dayOfWeek]] numbers it. */
  def dayOfWeekNum: Int = DateFormat.dayOfWeek(year, month, day)

  /** The three-letter English weekday abbreviation: `Mon` .. `Sun`.
   *
   *  The form client code actually wants, and was hand-rolling as
   *  `getDisplayName(TextStyle.SHORT, Locale.ENGLISH)` — several scripts wrap exactly that in a
   *  local `dow` helper. Naming it here removes the boilerplate and the locale hazard:
   *  `getDisplayName` without an explicit `Locale` follows the JVM default, so the same script
   *  yields `lun.` on a French machine.
   *
   *  Always English, and portable — no `java.time` involved, so the Rust port has it too.
   */
  def dayOfWeekName: String = toString("E")

  /** The full English weekday name: `Monday` .. `Sunday`. */
  def dayOfWeekFull: String = toString("EEEE")

  def dayOfWeek: java.time.DayOfWeek = java.time.DayOfWeek.of(dayOfWeekNum)

  def dayOfMonth: Int = day

  /** The month as an `Int`, which is what the field already is.
   *
   *  Named separately because [[month]] is an `Int` here where the `LocalDateTime`
   *  extension's `month` is a `java.time.Month`. Scripts wanting the enum should say
   *  [[dayOfWeek]]-style explicitly via `toLocalDateTime.getMonth`.
   */
  def monthNum: Int = month

  /** True unless this is one of the sentinels. */
  def isValid: Boolean = this != UniDateTime.BadDate && this != UniDateTime.EmptyDate

  /** Drops the offset, as `LocalDateTime` has nowhere to keep it.
   *
   *  Total, including for the sentinels -- whose fields name no real date, so
   *  `LocalDateTime.of` would throw on them. Each projects onto the moment it historically
   *  *was* (`BadDate` onto 1900-01-02T03:04:05), which keeps every arithmetic helper and
   *  every implicit conversion to a `java.time` API behaving exactly as before this became
   *  an impossible date. Eleven call sites in `uni.time` alone can receive a sentinel.
   */
  def toLocalDateTime: LocalDateTime =
    if this == UniDateTime.BadDate then UniDateTime.badDateLegacy
    else if this == UniDateTime.EmptyDate then UniDateTime.emptyDateLegacy
    else LocalDateTime.of(year, month, day, hour, minute, second, nano)

object UniDateTime:

  /** Days in `month` of `year`, Gregorian leap rule included. */
  def daysInMonth(year: Int, month: Int): Int =
    month match
      case 1 | 3 | 5 | 7 | 8 | 10 | 12 => 31
      case 4 | 6 | 9 | 11               => 30
      case 2 => if year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) then 29 else 28
      case _ => 0

  /** Whether these fields name a real moment.
   *
   *  The check `LocalDateTime.of` performs by throwing. Kept separate so a caller can
   *  ask without constructing, and so the Rust port has one obvious thing to mirror.
   */
  def isValid(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, nano: Int): Boolean =
    month >= 1 && month <= 12 &&
    day >= 1 && day <= daysInMonth(year, month) &&
    hour >= 0 && hour <= 23 &&
    minute >= 0 && minute <= 59 &&
    second >= 0 && second <= 59 &&
    nano >= 0 && nano <= 999999999

  /** Offsets ISO 8601 admits: +/-18:00. */
  def isValidOffset(minutes: Int): Boolean = minutes >= -1080 && minutes <= 1080

  // ── Epoch-day conversion ───────────────────────────────────────────────────
  //
  // Hinnant's civil-calendar algorithms, as plain integer arithmetic on the proleptic
  // Gregorian calendar. Written out rather than delegated to `java.time.LocalDate`
  // because it is the one piece of calendar maths short enough to be obviously correct,
  // it is exhaustively checkable against `LocalDate` (see `UniDateTimeArithSuite`), and
  // it is what the Rust port needs with no dependency available.
  //
  // Both rely on Scala's `/` truncating toward zero, which matches the C++ the algorithms
  // are written in; the explicit negative-branch adjustments are what make them correct
  // for years before the epoch rather than merely usable after it.

  /** Days from 1970-01-01 to the given civil date. */
  def epochDayOf(year: Int, month: Int, day: Int): Long =
    val y0 = year.toLong - (if month <= 2 then 1L else 0L)
    val era = (if y0 >= 0 then y0 else y0 - 399) / 400
    val yoe = y0 - era * 400                                        // [0, 399]
    val doy = (153 * (month + (if month > 2 then -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy                 // [0, 146096]
    era * 146097 + doe - 719468

  /** The civil date `epochDay` days after 1970-01-01, at midnight. */
  def ofEpochDay(epochDay: Long): UniDateTime =
    val z = epochDay + 719468
    val era = (if z >= 0 then z else z - 146096) / 146097
    val doe = z - era * 146097                                      // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365  // [0, 399]
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)                // [0, 365]
    val mp = (5 * doy + 2) / 153                                     // [0, 11]
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt                     // [1, 31]
    val m = (mp + (if mp < 10 then 3 else -9)).toInt                 // [1, 12]
    val y = yoe + era * 400 + (if m <= 2 then 1L else 0L)
    of(y.toInt, m, d)

  /** Builds a value, or [[BadDate]] when the fields do not name a real moment. */
  def of(year: Int, month: Int, day: Int,
         hour: Int = 0, minute: Int = 0, second: Int = 0, nano: Int = 0,
         offsetMinutes: Option[Int] = None): UniDateTime =
    if isValid(year, month, day, hour, minute, second, nano)
      && offsetMinutes.forall(isValidOffset)
    then new UniDateTime(year, month, day, hour, minute, second, nano, offsetMinutes)
    else BadDate

  /** Builds without validating. Private on purpose; the sentinels need it. */
  private def unchecked(year: Int, month: Int, day: Int,
                        hour: Int, minute: Int, second: Int, nano: Int): UniDateTime =
    new UniDateTime(year, month, day, hour, minute, second, nano, None)

  // Anchored on both sides, because an unanchored offset pattern finds one inside a
  // bare date: `12-05-1024` reads `-1024` as +10:24. The sign must follow whitespace
  // or a time, and the offset must end the token -- which is where a real one sits.
  private val TrailingOffset =
    """(?:(?<=\s)|(?<=\d\d:\d\d)|(?<=\d\d:\d\d:\d\d))([+-])(\d{2}):?(\d{2})(?=\s|$)""".r

  /** A trailing `Z`, which is ISO for a zero offset. Must follow a digit so a name
   *  ending in Z is not mistaken for it. */
  private val TrailingZulu = """(?<=\d)[Zz]\s*$""".r

  private val ZuluNames = Set("ut", "utc", "gmt")

  /** Extracts a UTC offset from date text, in minutes east of Greenwich.
   *
   *  The information `ChronoParse` kept in its `timezone` field and `SmartParse`
   *  discarded. `LocalDateTime` has nowhere to put it, which is why it was lost rather
   *  than wrong -- but an RFC 2822 header really does say `-0700`, and a caller may need
   *  to know.
   *
   *  Numeric offsets only, plus the zero-offset names. Abbreviations like `MST` or `EST`
   *  are deliberately not mapped: they are ambiguous (`CST` is three different offsets)
   *  and resolving them needs a timezone database, which is exactly the dependency this
   *  type exists to avoid. `None` means "no offset stated", not "UTC".
   */
  def offsetMinutesOf(text: String): Option[Int] =
    val named = text.split("""[\s()]+""").exists(t => ZuluNames(t.toLowerCase))
    val zulu = named || TrailingZulu.findFirstIn(text).isDefined
    TrailingOffset.findFirstMatchIn(text) match
      case Some(m) =>
        val magnitude = m.group(2).toInt * 60 + m.group(3).toInt
        val signed = if m.group(1) == "-" then -magnitude else magnitude
        Option.when(isValidOffset(signed))(signed)
      case None => Option.when(zulu)(0)

  // ── Companion surface mirroring `LocalDateTime`'s statics ──────────────────
  //
  // A type alias carries the type but never the companion, so `type DateTime =
  // UniDateTime` alone would break every `DateTime.now()`. Scripts reached that object
  // through `import java.time.{LocalDateTime as DateTime}`, which renames both. These
  // are the members they actually call -- measured across the 166 scripts importing
  // `uni.time`: `of` (10), `now` (9), `ofInstant` (2).

  /** Now, to the nanosecond the platform provides. `LocalDateTime.now()`.
   *
   *  Empty parens, not a bare `def`. Scala 3 removed auto-application, so `def now`
   *  cannot be called as `now()` -- and all nine call sites in the script corpus write
   *  `DateTime.now()`, since that is how `LocalDateTime.now()` reads. `TimeUtils.now`
   *  keeps its bare form, which is how scripts call *that* one; the two spellings exist
   *  because the two origins do.
   */
  def now(): UniDateTime = from(LocalDateTime.now())

  /** An instant rendered in `zone`. `LocalDateTime.ofInstant`. */
  def ofInstant(instant: java.time.Instant,
                zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): UniDateTime =
    from(LocalDateTime.ofInstant(instant, zone))

  def from(dt: LocalDateTime): UniDateTime =
    unchecked(dt.getYear, dt.getMonthValue, dt.getDayOfMonth,
      dt.getHour, dt.getMinute, dt.getSecond, dt.getNano)

  // ── Sentinels ──────────────────────────────────────────────────────────────
  //
  // Day **zero**, which exists in no month. That is the point: `of` validates, so no
  // parsed date can ever equal a sentinel, and the in-band-sentinel collision is gone
  // rather than merely improbable. While `BadDate` was a legal 1900-01-02T03:04:05, a data
  // file containing that exact moment produced a value indistinguishable from a parse
  // failure -- and 1900-01-02 is a common placeholder in financial data, so it was not a
  // hypothetical.
  //
  // The year, month and time keep their historical values so that [[toLocalDateTime]] can
  // project each sentinel back onto the moment it always denoted, leaving every
  // `java.time` interop path behaving exactly as before.

  /** Unparseable input. */
  val BadDate: UniDateTime = unchecked(1900, 1, 0, 3, 4, 5, 0)

  /** Empty input, as distinct from unparseable. */
  val EmptyDate: UniDateTime = unchecked(1800, 1, 0, 3, 4, 5, 0)

  /** The legal moment each sentinel projects onto in `java.time`. */
  private[time] val badDateLegacy: LocalDateTime = LocalDateTime.of(1900, 1, 2, 3, 4, 5)
  private[time] val emptyDateLegacy: LocalDateTime = LocalDateTime.of(1800, 1, 2, 3, 4, 5)

  /** Chronological by *local* fields, ignoring the offset.
   *
   *  Correct by construction from the field order, which runs most to least significant.
   *  Offset-aware ordering would need the fields normalised to UTC first, and mixing the
   *  two silently is worse than making the caller choose -- so this is local order, as
   *  `LocalDateTime`'s own ordering is.
   */
  given Ordering[UniDateTime] =
    Ordering.by(d => (d.year, d.month, d.day, d.hour, d.minute, d.second, d.nano))

  /** Lets a `UniDateTime` stand in for a `LocalDateTime`, so existing scripts keep
   *  compiling when the parser's return type changes.
   *
   *  Applying it warns under `-feature` but never errors, and the common members are
   *  defined on `UniDateTime` directly so the conversion is only reached when passing to
   *  a genuine `java.time` API.
   */
  given Conversion[UniDateTime, LocalDateTime] = _.toLocalDateTime

  /** The reverse direction: lets a `java.time` value flow into a `UniDateTime` position.
   *
   *  Needed because the two types meet in both directions once the parser returns this
   *  one. A script that reads a file timestamp (`LocalDateTime`) and assigns it to a var
   *  initialised from `BadDate` (`UniDateTime`) needs this, and so does the reverse of
   *  every helper that still returns a `java.time` value -- `quikDate`, `NullDate`,
   *  `epoch2DateTime`, `lastModifiedTime`.
   *
   *  The pair is deliberate and does not create a cycle: conversions are never chained,
   *  so each is applied at most once at a given position. What it does *not* fix is `==`,
   *  which takes `Any` and so admits no conversion -- a `UniDateTime` and a
   *  `LocalDateTime` with identical fields still compare unequal. That is why the
   *  sentinels and the parser return type had to move together rather than one at a time.
   *
   *  The offset is dropped in this direction because there was never one to carry.
   */
  given Conversion[LocalDateTime, UniDateTime] = from(_)

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
  offsetMinutes: Option[Int] = None
):
  /** Formats with a `java.time`-style pattern. See [[DateFormat]] for the subset. */
  def toString(fmt: String): String =
    DateFormat.format(year, month, day, hour, minute, second, nano, fmt)

  /** ISO-8601, matching `LocalDateTime.toString`.
   *
   *  Which means omitting the seconds when they are zero, and the fraction when it is
   *  -- `2024-05-12T14:30`, not `2024-05-12T14:30:00`. Scripts print these, so the
   *  shape is part of the compatibility surface.
   */
  override def toString: String =
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

  /** This moment with a UTC offset attached, or with it removed by passing `None`. */
  def withOffsetMinutes(offset: Option[Int]): UniDateTime =
    if offset.forall(UniDateTime.isValidOffset) then copy(offsetMinutes = offset) else this

  /** The offset as `+HH:MM`, or empty when there is none. */
  def offsetText: String = offsetMinutes.fold("") { o =>
    val sign = if o < 0 then "-" else "+"
    f"$sign${o.abs / 60}%02d:${o.abs % 60}%02d"
  }

  /** True unless this is one of the sentinels. */
  def isValid: Boolean = this != UniDateTime.BadDate && this != UniDateTime.EmptyDate

  /** Drops the offset, as `LocalDateTime` has nowhere to keep it. */
  def toLocalDateTime: LocalDateTime =
    LocalDateTime.of(year, month, day, hour, minute, second, nano)

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

  def from(dt: LocalDateTime): UniDateTime =
    unchecked(dt.getYear, dt.getMonthValue, dt.getDayOfMonth,
      dt.getHour, dt.getMinute, dt.getSecond, dt.getNano)

  /** Unparseable. Same fields as `TimeUtils.BadDate`, so the two agree across the
   *  conversion and an existing `d == BadDate` still holds. */
  val BadDate: UniDateTime = unchecked(1900, 1, 2, 3, 4, 5, 0)

  /** Empty input, as distinct from unparseable. Same fields as `TimeUtils.EmptyDate`. */
  val EmptyDate: UniDateTime = unchecked(1800, 1, 2, 3, 4, 5, 0)

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

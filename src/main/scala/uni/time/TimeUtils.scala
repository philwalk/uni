package uni.time

//import uni.verboseUni

import java.time.temporal.ChronoUnit
import java.time.Duration
import java.time.{Instant, LocalDateTime, ZoneId}
import java.time.temporal.{TemporalAdjuster, TemporalAdjusters}
import scala.util.matching.Regex

object TimeUtils {
  /** Unparseable input. Same field values as before, now as a [[UniDateTime]].
   *
   *  The type had to move in step with `parseDate`'s return type, not after it: `==`
   *  takes `Any`, so a `UniDateTime` result compared against a `LocalDateTime` sentinel
   *  compiles silently and is always `false`. Every `if (d == BadDate)` in a client script
   *  would have become "never bad", and parse failures would have flowed downstream as
   *  dates in 1900 instead of being caught.
   */
  val BadDate: UniDateTime   = UniDateTime.BadDate

  /** Empty input, as distinct from unparseable. See [[BadDate]] on why the type moved. */
  val EmptyDate: UniDateTime = UniDateTime.EmptyDate

  /** Parses `datestr`, or `BadDate`.
   *
   *  `SmartParse` only, as of the ChronoParse deprecation. The fallback it replaces was
   *  measured against the project's own 132-date corpus: `ChronoParse` rescued **zero**
   *  inputs that `SmartParse` failed, and of the cases where both succeeded and
   *  disagreed, every remaining one has `ChronoParse` wrong (it reads `12:00:00 AM` as
   *  noon). The two formats it genuinely handled alone -- bare `yyyyMMdd` and a
   *  leading time -- are folded into `SmartParse.preNormalize`.
   *
   *  The fallback could only fire on `BadDate`/`EmptyDate` anyway, so it never masked a
   *  wrong answer, only an unparsed one.
   */
  def parseDate(datestr: String): UniDateTime = parseDateSmart(datestr)

  /** Month number from a name or abbreviation, or -1 when it is not one.
   *
   *  Moved here from `ChronoParse` when that stopped being consulted; this was its last
   *  live dependency. Deliberately *not* merged with `SmartParse.monthFromWord`, which is
   *  more capable -- prefix and fuzzy matching -- but **throws** on an unrecognised word.
   *  One of the call sites below is not guarded by a month match, so switching would turn
   *  a -1 into an exception.
   *
   *  `take(3)` rather than `substring(0, 3)`: the original threw
   *  `StringIndexOutOfBoundsException` on any input shorter than three characters, which
   *  the unguarded path could reach.
   */
  def monthAbbrev2Number(name: String): Int =
    name.toLowerCase.take(3) match
      case "jan" => 1
      case "feb" => 2
      case "mar" => 3
      case "apr" => 4
      case "may" => 5
      case "jun" => 6
      case "jul" => 7
      case "aug" => 8
      case "sep" => 9
      case "oct" => 10
      case "nov" => 11
      case "dec" => 12
      case _     => -1


  /** Fast path for 8-character `yyyyMMdd`, and the strict counterpart to `parseDate`:
   *  this one throws on malformed input rather than yielding `BadDate`.
   *
   *  Returns a [[UniDateTime]] because `parseDate` does. When these two disagreed on the
   *  type, a script choosing between them per line -- `if (compact) quikDate(l) else
   *  parseDate(l)` -- inferred the union `LocalDateTime | UniDateTime`, which no position
   *  accepts and no conversion can repair. Two parsers in one API returning two date
   *  types is the defect; the union was only how it surfaced.
   */
  def quikDate(s: String): UniDateTime = {
    assert(s.length >= 8, s"ymd[$s]")
    val ymdtest = s.take(8)
    val ymd = if ymdtest.matches("[0-9]+") then
      ymdtest.take(4)+"-"+ymdtest.drop(4).take(2)+"-"+ymdtest.drop(6)
    else
      s.take(10)
    quikDateTime(ymd)
  }

  def quikDateTime(s: String): UniDateTime = {
    require(s.matches("""[0-9]{4}\D[0-9]{2}\D[0-9]{2}.*"""))
    val ff = s.split("[^0-9]+").filter { _.trim.nonEmpty }.map { _.toInt }
    ff match {
    case Array(y, m, d) =>
      UniDateTime.of(y, m, d, 0, 0, 0)
    case Array(y, m, d, h, mn) =>
      UniDateTime.of(y, m, d, h, mn, 0)
    case Array(y, m, d, h, mn, s) =>
      UniDateTime.of(y, m, d, h, mn, s)
    case Array(y, m, d, h) =>
      UniDateTime.of(y, m, d, h, 0, 0)
    case _ =>
      sys.error(s"bad dateTime: [$s]")
    }
  }

  val EasternTime: ZoneId  = java.time.ZoneId.of("America/New_York")
  val MountainTime: ZoneId = java.time.ZoneId.of("America/Denver")
  val UTC: ZoneId          = java.time.ZoneId.of("UTC")
  private def nowInstant   = Instant.now()

  /** Now. A [[UniDateTime]], in step with `parseDate` and `DateTime.now()`.
   *
   *  Leaving this on `LocalDateTime` was tried and does not work: `if (s == "now") now
   *  else parseDate(s)` is a natural thing to write, and with mismatched types it infers
   *  a union and fails to compile at the next use.
   *
   *  Keeps its bare form -- scripts call `now`, not `now()` -- where `DateTime.now()`
   *  takes parens. The two spellings exist because the two origins do.
   */
  def now: UniDateTime       = UniDateTime.now()
  def yesterday: UniDateTime = now.minusDays(1)

  private[uni] def zoneid     = ZoneId.systemDefault
  private[uni] def zoneOffset = zoneid.getRules().getStandardOffset(nowInstant)

  type DateTimeZone = java.time.ZoneId

  lazy val timeDebug: Boolean = Option(System.getenv("TIME_DEBUG")) match {
  case None => false
  case _    => true
  }
  /** Year zero, used as an initialiser distinct from [[BadDate]]. A [[UniDateTime]] for
   *  the same reason the sentinels are: it gets compared against parsed dates. */
  lazy val NullDate: UniDateTime = UniDateTime.of(0, 1, 1, 0, 0, 0)

  // At object/class level (outside the method)
  private val WeekdayPattern = "(?i)(Sun[day]*|Mon[day]*|Tue[sday]*|Wed[nesday]*|Thu[rsday]*|Fri[day]*|Sat[urday]*),? *".r
  private val MonthPattern = "(?i)[JFMASOND][aerpuco][nbrylgptvc][a-z]*".r
  private val LetterDigitPattern = "([a-zA-Z])([0-9])".r

  def numerifyNames(datestr: String): String = {
    val noWeekday = WeekdayPattern.replaceAllIn(datestr, "")
    
    if !MonthPattern.findFirstIn(noWeekday).isDefined then noWeekday
    else
      val parts = LetterDigitPattern.replaceFirstIn(noWeekday, "$1 $2")
        .split("[-/,\\s]+")
      
      parts.indexWhere(s => MonthPattern.matches(s)) match
        case idx if idx >= 0 =>
          val monthNum = monthAbbrev2Number(parts(idx))
          noWeekday.replaceAll(parts(idx), f"$monthNum%02d")
        
        case _ =>
          val (monthStr, rest) = 
            if parts.head.matches("\\d+") then (parts(1), parts.head +: parts.drop(2))
            else (parts.head, parts.tail)
          
          val month = monthAbbrev2Number(monthStr.take(3))
          
          rest.toList match
            case d :: y :: Nil =>
              f"${y.toInt}%04d-$month%02d-${d.toInt}%02d"
            case d :: y :: ts :: tz :: _ if ts.contains(":") =>
              f"${y.toInt}%04d-$month%02d-${d.toInt}%02d $ts $tz"
            case d :: ts :: y :: _ if ts.contains(":") =>
              f"${y.toInt}%04d-$month%02d-${d.toInt}%02d $ts"
            case d :: y :: ts :: _ =>
              f"${y.toInt}%04d-$month%02d-${d.toInt}%02d $ts"
            case other =>
              sys.error(s"bad date [${other.mkString(", ")}]")
  }

  lazy val mmddyyyyPattern: Regex          = """(\d{1,2})\D(\d{1,2})\D(\d{4})""".r
  lazy val mmddyyyyTimePattern: Regex      = """(\d{1,2})\D(\d{1,2})\D(\d{4})(\D\d\d:\d\d(:\d\d)?)""".r
  lazy val mmddyyyyTimePattern2: Regex     = """(\d{1,2})\D(\d{1,2})\D(\d{4})\D(\d\d):(\d\d)""".r
  lazy val mmddyyyyTimePattern3: Regex     = """(\d{1,2})\D(\d{1,2})\D(\d{4})\D(\d\d):(\d\d):(\d\d)""".r
  lazy val mmddyyyyTimePattern3tz: Regex   = """(\d{1,2})\D(\d{1,2})\D(\d{4})\D(\d\d):(\d\d):(\d\d)\D(-?[0-9]{4})""".r
  lazy val yyyymmddPattern: Regex          = """(\d{4})\D(\d{1,2})\D(\d{1,2})""".r
  lazy val yyyymmddPatternWithTime: Regex  = """(\d{4})\D(\d{1,2})\D(\d{1,2})(\D.+)""".r
  lazy val yyyymmddPatternWithTime2: Regex = """(\d{4})\D(\d{1,2})\D(\d{1,2})\D+(\d{2}):(\d{2})""".r
  lazy val yyyymmddPatternWithTime3: Regex = """(\d{4})\D(\d{1,2})\D(\d{1,2})\D+(\d{2}):(\d{2}):(\d{2})""".r
  lazy val mmddyyyyPatternWithTime3: Regex = """(\d{1,2})\D(\d{1,2})\D(\d{4})\D+(\d{2}):(\d{2}):(\d{2})""".r

  lazy val validYearPattern = """(1|2)\d{3}""" // only consider years between 1000 and 2999

  private[uni] def LastDayAdjuster: TemporalAdjuster = TemporalAdjusters.lastDayOfMonth()

  // ── Differences ─────────────────────────────────────────────────────────────
  //
  // A difference between two local datetimes is field arithmetic: no timezone can
  // legitimately affect it, so none of these take one. Earlier versions routed the
  // operands through `atZone(zone).toInstant`, which imported DST artifacts into
  // results a zone must not change: wall-clock 01:30 and 03:30 on a US spring-forward
  // day are two hours apart *as the datetimes the caller handed us* -- that they were
  // one hour apart in Denver that night is a fact about Denver, available only from a
  // tz database, and callers who mean that should be working in `Instant`s -- which is
  // exactly what the `Instant` overload of [[secondsBetween]] is for.
  //
  // This also makes the family identical to the Rust port (`utime::timeutils`), which
  // could never consult a zone in the first place.

  // signed number of days between specified dates.
  // if date1 > date2, a negative number of days is returned.
  def elapsedDays(d1: UniDateTime, d2: UniDateTime): Long =
    ChronoUnit.DAYS.between(d1.toLocalDateTime, d2.toLocalDateTime)

  /** Seconds between two moments.
   *
   *  Overloaded, which is why the `UniDateTime` alternative is spelled out rather than
   *  left to the conversion: implicit conversions are **not** applied while resolving an
   *  overload. Scala looks for an alternative matching the arguments as given, and reports
   *  "none of the overloaded alternatives match" without ever retrying with a conversion
   *  in hand. So `secondsBetween(uniA, uniB)` failed outright until this existed, even
   *  though `UniDateTime` converts to `LocalDateTime` perfectly well.
   */
  def secondsBetween(d1: UniDateTime, d2: UniDateTime): Long =
    ChronoUnit.SECONDS.between(d1.toLocalDateTime, d2.toLocalDateTime)
  def secondsBetween(d1: LocalDateTime, d2: LocalDateTime): Long = ChronoUnit.SECONDS.between(d1, d2)
  def secondsBetween(d1: Instant, d2: Instant): Long = ChronoUnit.SECONDS.between(d1, d2)

  // age in seconds relative to now
  def secondsSince(date1: UniDateTime): Long = secondsBetween(date1, now)

  def minutesBetween(d1: UniDateTime, d2: UniDateTime): Double = secondsBetween(d1, d2).toDouble / 60.0
  def hoursBetween(d1: UniDateTime, d2: UniDateTime): Double = secondsBetween(d1, d2).toDouble / 3600.0
  def daysRounded(d1: UniDateTime, d2: UniDateTime): Double = secondsBetween(d1, d2).toDouble / (24.0 * 3600.0)
  def daysBetween(d1: UniDateTime, d2: UniDateTime): Long =
    ChronoUnit.DAYS.between(d1.toLocalDateTime, d2.toLocalDateTime)

  def endOfMonth(d: UniDateTime): UniDateTime =
    d.lastDayOfMonth.atStartOfDay()

  /** The file's mtime as a [[UniDateTime]], in UTC -- consistent with
   *  `Path.lastModifiedTime` (UTC as of 0.16.0) and the Rust port. A missing file reads
   *  as mtime `-1` ms: `1969-12-31T23:59:59.999`. */
  def whenModified(f: java.io.File): UniDateTime = {
    val lastmod = f.toPath match {
    case p if java.nio.file.Files.exists(p) =>
      f.lastModified
    case _ =>
      -1
    }
    epoch2DateTime(lastmod, UTC)
  }

  def epoch2DateTime(epoch: Long, timezone: java.time.ZoneId = UTC): UniDateTime = {
    val instant = java.time.Instant.ofEpochMilli(epoch)
    UniDateTime.ofInstant(instant, timezone)
  }

  /**
  * Returns days, hours, minutes, seconds between timestamps.
  */
  def getDuration(date1: UniDateTime, date2: UniDateTime): (Long, Long, Long, Long) =
    getDuration(date1.toLocalDateTime, date2.toLocalDateTime)

  def getDuration(date1: LocalDateTime, date2: LocalDateTime): (Long, Long, Long, Long) =
    // a difference: zone-free field arithmetic, like the whole family above
    val totalSeconds = Duration.between(date1, date2).getSeconds   // signed; floors

    val abs = math.abs(totalSeconds)

    val days    = abs / 86400
    val hours   = (abs % 86400) / 3600
    val minutes = (abs % 3600) / 60
    val seconds = abs % 60

    // sign on the largest NONZERO unit (0.16.0). The old "sign on days" vanished
    // for sub-day negatives: -5 hours reported (0, 5, 0, 0), indistinguishable
    // from +5 hours.
    if totalSeconds >= 0 then (days, hours, minutes, seconds)
    else if days > 0 then (-days, hours, minutes, seconds)
    else if hours > 0 then (0, -hours, minutes, seconds)
    else if minutes > 0 then (0, 0, -minutes, seconds)
    else (0, 0, 0, -seconds)

  def nowZoned(zone: ZoneId = zoneid): UniDateTime =
    UniDateTime.from(LocalDateTime.now(zone))

  def nowUTC: Instant =
    Instant.now()                     // same as above; Instant is always UTC

  def ageInMinutes(f: java.io.File): Double = {
    if (f.exists)
      val diffMillis = nowInstant.toEpochMilli - f.lastModified()
      diffMillis / (60 * 1000.0)
    else
      1e6 // treat non-existent files as VERY old
  }

  def ageInDays(f: java.io.File): Double = {
    ageInMinutes(f) / (24 * 60)
  }
  def ageInDays(fname: String): Double = {
    ageInDays(new java.io.File(fname))
  }

  object sysTimer {
    var begin         = System.currentTimeMillis
    def reset(): Unit = { begin = System.currentTimeMillis }
    def elapsed: Long = System.currentTimeMillis - begin
    def elapsedMillis = elapsed

    def elapsedSeconds: Double = elapsed.toDouble / 1000.0
    def elapsedMinutes: Double = elapsed.toDouble / (60.0 * 1000.0)
    def elapsedHours: Double   = elapsed.toDouble / (60.0 * 60.0 * 1000.0)
    def elapsedDays: Double    = elapsed.toDouble / (24.0 * 60.0 * 60.0 * 1000.0)
  }
}

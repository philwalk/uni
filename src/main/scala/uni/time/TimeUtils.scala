package uni.time

import uni.*

import java.time.temporal.ChronoUnit
import java.time.Duration
import java.time.{Instant, LocalDateTime, ZoneId}
import java.time.temporal.{TemporalAdjuster, TemporalAdjusters}
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

import scala.util.matching.Regex

export TimeUtils.{now, parseDate}

extension (inst: Instant)
  def toString(
    pattern: String,
    zone: ZoneId = ZoneId.systemDefault()
  ): String =
    LocalDateTime
      .ofInstant(inst, zone)
      .format(DateTimeFormatter.ofPattern(pattern))

extension (dt: LocalDateTime)
  def toString(fmt: String): String =
    dt.format(DateTimeFormatter.ofPattern(fmt))

package object TimeUtils {
  def parseDate(datestr: String, format: String = ""): LocalDateTime = {
    try {
      parseSmart(datestr)
    } catch {
      case d: DateTimeParseException =>
        val dtype = classify(datestr)
        System.err.println(s"[$datestr] classified as [$dtype]")
        dateParser(datestr) // return BadDate rather than throwing an exception
        //throw d
    }
  }

  // this depends on uni.time.ChronoParse
  def dateParser(inpDateStr: String): LocalDateTime = {
    if (inpDateStr.trim.isEmpty) {
      BadDate
    } else {
      def isDigit(c: Char): Boolean = c >= '0' && c <= '9'
      val digitcount = inpDateStr.filter { (c: Char) => isDigit(c) }.size
      if (digitcount < 3 || digitcount > 19) {
        BadDate
      } else {
        val flds = uni.time.ChronoParse(inpDateStr) // might return BadDate!
        flds.dateTime 
      }
    }
  }

  val EasternTime: ZoneId  = java.time.ZoneId.of("America/New_York")
  val MountainTime: ZoneId = java.time.ZoneId.of("America/Denver")
  val UTC: ZoneId          = java.time.ZoneId.of("UTC")

  def now        = Instant.now()

  private[uni] def zoneid     = ZoneId.systemDefault
  private[uni] def zoneOffset = zoneid.getRules().getStandardOffset(now)

  type DateTimeZone = java.time.ZoneId

  lazy val timeDebug: Boolean = Option(System.getenv("TIME_DEBUG")) match {
  case None => false
  case _    => true
  }
  lazy val NullDate: LocalDateTime = LocalDateTime.parse("0000-01-01T00:00:00") // .ofInstant(Instant.ofEpochMilli(0))


  def numerifyNames(datestr: String) = {
    val noweekdayName = datestr.replaceAll("(?i)(Sun[day]*|Mon[day]*|Tue[sday]*|Wed[nesday]*|Thu[rsday]*|Fri[day]*|Sat[urday]*),? *", "")
//    val nomonthName = datestr.replaceAll("(?i)(Jan[ury]*|Feb[ruay]*|Mar[ch]*|Apr[il]*|May|Jun[e]*|Jul[y]*|Aug[st]*|Sep[tmbr]*|Oct[ober]*|Nov[embr]*|Dec[mbr]*),? *", "")
//    if (noweekdayName != datestr || nomonthName != datestr){
//      hook += 1
//    }
    noweekdayName match {
    case str if str.matches("(?i).*[JFMASOND][aerpuco][nbrylgptvc][a-z]*.*") =>
      var ff = str.replaceFirst("([a-zA-Z])([0-9])", "$1 $2").split("[-/,\\s]+")
      val monthIndex = ff.indexWhere {(s: String) => s.matches("(?i).*[JFMASOND][aerpuco][nbrylgptvc][a-z]*.*")}
      if (monthIndex >= 0){
        val monthName = ff(monthIndex)
        val month: Int = ChronoParse.monthAbbrev2Number(ff(monthIndex))
        val nwn = noweekdayName.replaceAll(monthName, "%02d ".format(month))
        nwn
      } else {
        // format: off
        if (ff(0).matches("\\d+")) {
          // swap 1st and 2nd fields (e.g., convert "01 Jan" to "Jan 01")
          val tmp = ff(0)
          ff(0) = ff(1)
          ff(1) = tmp
        }
        val mstr = ff.head.take(3)
        if (!mstr.toLowerCase.matches("[a-z]{3}")) {
          hook += 1
        }
        val month = ChronoParse.monthAbbrev2Number(mstr)
        ff = ff.drop(1)
        // format: off
        val (day, year, timestr, tz) = ff.toList match {
        case d :: y :: Nil =>
          (d.toInt, y.toInt, "", "")
        case d :: y :: ts :: tz :: Nil if ts.contains(":") =>
          (d.toInt, y.toInt, " "+ts, " "+tz)
        case d :: ts :: y :: tail if ts.contains(":") =>
          (d.toInt, y.toInt, " "+ts, "")
        case d :: y :: ts :: tail =>
          (d.toInt, y.toInt, " "+ts, "")
        case other => 
          sys.error(s"bad date [$other]")
        }
        // format: on
        "%4d-%02d-%02d%s%s".format(year, month, day, timestr, tz)
      }
    case str =>
      str
    }
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

  // signed number of days between specified dates.
  // if date1 > date2, a negative number of days is returned.
  def daysBetween(d1: LocalDateTime, d2: LocalDateTime, zone: ZoneId): Long = ChronoUnit.DAYS.between(d1.atZone(zone), d2.atZone(zone))

  def secondsBetween(d1: LocalDateTime, d2: LocalDateTime, zone: ZoneId): Long = secondsBetween(d1.atZone(zone).toInstant, d2.atZone(zone).toInstant)
  def secondsBetween(d1: Instant, d2: Instant): Long = ChronoUnit.SECONDS.between(d1, d2)

  // age in seconds relative to now
  def secondsSince(date1: LocalDateTime, zone: ZoneId = MountainTime): Long = ChronoUnit.SECONDS.between(date1.atZone(zone).toInstant, now)

  def minutesBetween(d1: LocalDateTime, d2: LocalDateTime, zone: ZoneId = MountainTime): Double = secondsBetween(d1, d2, zone).toDouble / 60.0

  def endOfMonth(d: LocalDateTime): LocalDateTime = {
    val month: java.time.YearMonth = { java.time.YearMonth.from(d) }
    month.atEndOfMonth.atStartOfDay
  }

  def whenModified(f: java.io.File): LocalDateTime = {
    val lastmod = f.toPath match {
    case p if java.nio.file.Files.exists(p) =>
      f.lastModified
    case _ =>
      -1
    }
    epoch2DateTime(lastmod, MountainTime)
  }

  def epoch2DateTime(epoch: Long, timezone: java.time.ZoneId = UTC): LocalDateTime = {
    val instant = java.time.Instant.ofEpochMilli(epoch)
    java.time.LocalDateTime.ofInstant(instant, timezone)
  }

  /**
  * Returns days, hours, minutes, seconds between timestamps.
  */
  def getDuration(date1: LocalDateTime, date2: LocalDateTime): (Long, Long, Long, Long) =
    getDuration(date1, date2, ZoneId.systemDefault)

  def getDuration(date1: LocalDateTime, date2: LocalDateTime, zone: ZoneId): (Long, Long, Long, Long) =
    val d1 = date1.atZone(zone).toInstant
    val d2 = date2.atZone(zone).toInstant

    val totalSeconds = Duration.between(d1, d2).getSeconds   // signed

    val abs = math.abs(totalSeconds)

    val days    = abs / 86400
    val hours   = (abs % 86400) / 3600
    val minutes = (abs % 3600) / 60
    val seconds = abs % 60

    // preserve sign on the largest unit
    if totalSeconds >= 0 then
      (days, hours, minutes, seconds)
    else
      (-days, hours, minutes, seconds)

  def nowZoned(zone: ZoneId = MountainTime): LocalDateTime =
    LocalDateTime.now(zone)

  def nowUTC: Instant =
    Instant.now()                     // same as above; Instant is always UTC

  def ageInMinutes(f: java.io.File): Double = {
    if (f.exists)
      val diffMillis = now.toEpochMilli - f.lastModified()
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

  lazy val BadDate: LocalDateTime   = dateParser("1900-01-01")
  lazy val EmptyDate: LocalDateTime = dateParser("1800-01-01")

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

////////////////////////////////////////////////////////////////////////
  enum Shape:
    case SquashedMonthDay
    case HyphenDMY
    case SlashMDY
    case SlashDMY
    case SlashYMD
    case MonthCommaYear
    case RFCish
    case RFC
    case ISO8601
    case Unknown

  def classify(s: String): Shape =
    val hasSlash   = s.contains("/")
    val hasHyphen  = s.contains("-")
    val hasComma   = s.contains(",")
    val hasLetters = s.exists(_.isLetter)

    // 1. SquashedMonthDay (Apr12-11)
    if hasLetters && hasHyphen && !hasSlash &&
       s.matches("(?i)^[A-Za-z]{3}\\d{1,2}-\\d{2}$") then
      Shape.SquashedMonthDay

    // 2. HyphenDMY (11-Apr-2016)
    else if hasLetters && hasHyphen &&
            s.matches("(?i)^\\d{1,2}-[A-Za-z]{3}-\\d{2,4}(?:$| .*)") then
      Shape.HyphenDMY

    // 3. RFC-ish (MUST come before MonthCommaYear)
    else if hasLetters && (
            s.matches("(?i)^[A-Za-z]{3}.*") ||     // starts with weekday or month
            s.matches("(?i)^\\d{1,2} [A-Za-z]{3} .*") // starts with day then month
         ) then
      Shape.RFCish

    // 4. MonthCommaYear (May 16,2014)
    else if hasComma && hasLetters then
      Shape.MonthCommaYear

    // 5. Numeric MDY without slash-year
    else if s.matches("^\\d{1,2}/\\d{1,2} .* \\d{4}$") then
      Shape.RFCish

    // 6. SlashYMD
    else if hasSlash &&
            s.matches("^\\d{4}/\\d{1,2}/\\d{1,2}(?:$| .*)") then
      Shape.SlashYMD

    // 7. SlashDMY vs SlashMDY
    else if hasSlash && s.matches("^\\d{1,2}/\\d{1,2}/\\d{2,4}(?:$| .*)") then
      val dateToken = s.takeWhile(ch => ch != ' ' && ch != 'T')
      val parts = dateToken.split("/")
      if parts.length >= 3 then
        val a = parts(0).toInt
        val b = parts(1).toInt
        if      b <= 12 && a > 12 then Shape.SlashDMY
        else if a <= 12 && b > 12 then Shape.SlashMDY
        else Shape.SlashMDY   // ambiguous → MDY
      else Shape.Unknown

    else if s.matches("\\d{4}-\\d{2}-\\d{2}T.*") then
      Shape.ISO8601

    else Shape.Unknown

  val patternsByShape: Map[Shape, List[String]] = Map(

    Shape.SquashedMonthDay -> List(
      "MMMd-yy",
      "MMMdd-yy",
    ),

    Shape.HyphenDMY -> List(
      "d-MMM-yyyy",
      "dd-MMM-yyyy",
      "d-MMM-yyyy HH:mm:ss",
      "dd-MMM-yyyy HH:mm:ss",
      "d-MMM-yyyy HH:mm:ss Z",
      "dd-MMM-yyyy HH:mm:ss Z",
    ),

    Shape.SlashMDY -> List(
      "M/d/yy",
      "MM/dd/yy",
      "M/d/yyyy",
      "MM/dd/yyyy",
      "M/d/yyyy hh:mm:ss a",
      "MM/dd/yyyy hh:mm:ss a",
      "M/d/yyyy hh:mm:ss a z",
      "MM/dd/yyyy hh:mm:ss a z",
      "M/d/yyyy HH:mm:ss",
      "MM/dd/yyyy HH:mm:ss",
      "M/d/yyyy HH:mm:ss Z",
      "MM/dd/yyyy HH:mm:ss Z",
      "M/d/yyyy h:mm a",
      "MM/dd/yyyy h:mm a",
      "M/d/yyyy h:mm:ss a",
      "MM/dd/yyyy h:mm:ss a",
    ),

    Shape.SlashDMY -> List(
      "d/M/yy",
      "dd/MM/yy",
      "d/M/yyyy",
      "dd/MM/yyyy",

      "d/M/yyyy HH:mm:ss",
      "dd/MM/yyyy HH:mm:ss",
      "d/M/yyyy HH:mm:ss Z",
      "dd/MM/yyyy HH:mm:ss Z",
    ),

    Shape.SlashYMD -> List(
      "yyyy/M/d",
      "yyyy/MM/dd",
      "yyyy/MM/dd HH:mm",
      "yyyy/MM/dd HH:mm:ss",
      "yyyy/MM/dd HH:mm:ss.S",
    ),

    Shape.MonthCommaYear -> List(
      "MMM d,yyyy",
      "MMMM d,yyyy",
      "MMM d, yyyy",
      "MMMM d, yyyy",
      "MMM dd,yyyy",
      "MMM d,yyyy",
    ),

    //textual month, textual weekday (optional), time, year, optional timezone
    Shape.RFCish -> List(
      "MM/dd HH:mm:ss yyyy",                // Numeric MDY without slash-year
      "EEE MMM dd HH:mm:ss yyyy",           // Java / syslog style
      "EEE, MMM dd HH:mm:ss yyyy",          // Weekday + comma, no timezone
      "d MMM yyyy HH:mm:ss Z",              // Day-first, offset only
      "d MMM yyyy HH:mm:ss Z z",            // Day-first, offset + bare abbreviation
      "d MMM yyyy HH:mm:ss Z '('z')'",      // Day-first, offset + parenthesized abbreviation
      "EEE, d MMM yyyy HH:mm:ss Z",         // Weekday + comma, offset only
      "EEE, d MMM yyyy HH:mm:ss Z z",       // Weekday + comma, offset + bare abbreviation
      "EEE MMM dd yyyy h:mm:ss a z",
      "EEE, d MMM yyyy HH:mm:ss Z '('z')'", // Weekday + comma, offset + parenthesized abbreviation
      "MMM d,yyyy",                         // Month-name date-only with comma
      "MMM d, yyyy",
      "MMM dd, yyyy",
    ),

    Shape.RFC -> List(
      "EEE MMM dd HH:mm:ss a z yyyy",
      "EEE, dd MMM yyyy HH:mm:ss Z",
      "EEE, dd MMM yyyy HH:mm:ss Z '('z')'",
      "EEE, dd MMM yyyy HH:mm:ss Z z",
      "dd MMM yyyy HH:mm:ss Z",
      "dd MMM yyyy HH:mm:ss Z '('z')'",
    ),

    Shape.ISO8601 -> List(
      "yyyy-MM-dd'T'HH:mm:ss'Z'",
      "yyyy-MM-dd'T'HH:mm:ssXXX",
      "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
      "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
    ),

    Shape.Unknown -> List(
      "yyyy-MM-dd HH:mm:ss",
      "yyyy-MM-dd",
    )
  )

  import java.time.*
  import java.time.format.*
  import java.util.Locale

  def hasTimeFields(p: String): Boolean =
    p.exists(ch => ch == 'H' || ch == 'm' || ch == 's')

  def tryPatterns(s: String, patterns: List[String]): Option[LocalDateTime] =
    patterns.iterator.flatMap { p =>
      try
        val fmt =
          new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(p)
            .toFormatter(Locale.ENGLISH) // you can keep SMART here
            .withResolverStyle(ResolverStyle.LENIENT)

        // Heuristic: does this pattern include time fields?
        val hasTime =
          p.exists(ch => ch == 'H' || ch == 'h' || ch == 'm' || ch == 's' || ch == 'S')

        val dt =
          if hasTime then
            LocalDateTime.parse(s, fmt)
          else
            LocalDate.parse(s, fmt).atStartOfDay()

        // println(s"[TRY] input='$s' pattern='$p' → $dt")
        Some(dt)
      catch
        case _: Exception => None
    }.toSeq.headOption

  def parseSmart(datestr: String): LocalDateTime =
    val clean = datestr
        .replaceAll("\\s+", " ").trim
        .replaceAll("\\s+\\([A-Za-z]{2,5}\\)$", "")  // remove (MST) or similar

    val shape = classify(clean)
    val patterns = patternsByShape(shape)
//  if false then
//    println(s"[SMART] patterns for $shape:")
//    patterns.foreach(p => println(s"  - $p"))

    tryPatterns(clean, patterns)
      .orElse(tryPatterns(clean, patternsByShape(Shape.Unknown)))
      .getOrElse(throw new DateTimeParseException(s"Cannot parse: [$clean] ; shape is [$shape])", clean, 0))
}

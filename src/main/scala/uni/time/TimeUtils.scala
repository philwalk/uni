package uni.time

import uni.verboseUni

import java.time.temporal.ChronoUnit
import java.time.Duration
import java.time.{Instant, LocalDateTime, ZoneId}
import java.time.temporal.{TemporalAdjuster, TemporalAdjusters}
import java.time.format.DateTimeFormatter
import scala.util.matching.Regex
//import java.time.format.DateTimeParseException

export TimeUtils.{parseDate, BadDate, EmptyDate}
export ChronoParse.parseDateChrono
export SmartParse.parseDateSmart

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

object TimeUtils {
  val BadDate: LocalDateTime   = LocalDateTime.of(1900, 1, 2, 3, 4, 5)
  val EmptyDate: LocalDateTime = LocalDateTime.of(1800, 1, 2, 3, 4, 5)

  def parseDate(datestr: String, format: String = ""): LocalDateTime = {
    parseDateSmart(datestr) match {
    case BadDate | EmptyDate =>
      if verboseUni then
        val dtype = SmartParse.classify(datestr)
        System.err.println(s"[$datestr] classified as [$dtype]")
      parseDateChrono(datestr) // return BadDate rather than throwing an exception
    case d: LocalDateTime =>
      d
    case null =>
      throw new IllegalArgumentException(s"null returned when parsing datestr [$datestr]")
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

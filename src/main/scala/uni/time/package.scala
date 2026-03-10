package uni.time

import java.time.{Instant, LocalDateTime, ZoneId, Duration, Period, Month}
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

// Top-level exports (internal package visibility)
export TimeUtils.{parseDate as parseDateTime}
export TimeUtils.*
export ChronoParse.parseDateChrono
export SmartParse.parseDateSmart
export java.time.LocalDateTime

type DateTime = java.time.LocalDateTime // alias used by pallet
type Instant = java.time.Instant
type ZoneId = java.time.ZoneId
type Duration = java.time.Duration


// Extensions
extension (inst: Instant)
  def toString(pattern: String, zone: ZoneId = ZoneId.systemDefault()): String =
    LocalDateTime.ofInstant(inst, zone).format(DateTimeFormatter.ofPattern(pattern))

extension (dt: LocalDateTime)
  // formatting
  def ymd: String                  = dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
  def ymdhms: String               = dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
  def fmt(pattern: String): String = dt.format(DateTimeFormatter.ofPattern(pattern))
  def toString(fmt: String): String = dt.format(DateTimeFormatter.ofPattern(fmt))

  // comparisons
  def >(other: DateTime): Boolean  = dt.isAfter(other)
  def <=(other: DateTime): Boolean = !dt.isAfter(other)
  def <(other: DateTime): Boolean  = dt.isBefore(other)
  def >=(other: DateTime): Boolean = !dt.isBefore(other)

  // field accessors
  def year: Int        = dt.getYear
  def month: Month     = dt.getMonth
  def monthNum: Int    = dt.getMonth.getValue
  def day: Int         = dt.getDayOfMonth
  def dayOfMonth: Int  = dt.getDayOfMonth
  def dayOfYear: Int   = dt.getDayOfYear
  def dayOfWeek: DayOfWeek = dt.getDayOfWeek
  def hour: Int        = dt.getHour
  def minute: Int      = dt.getMinute
  def second: Int      = dt.getSecond

  // millis since epoch
  def getMillis(): Long = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

  // start-of-day
  def atStartOfDay(): LocalDateTime = dt.withHour(0).withMinute(0).withSecond(0).withNano(0)

  // elapsed duration to another DateTime
  def to(other: DateTime): Duration = Duration.between(dt, other)

  // calendar adjustments
  def withDayOfWeek(dow: DayOfWeek): DateTime      = dt.`with`(TemporalAdjusters.next(dow))
  def lastDayOfMonth: LocalDateTime                 = dt.`with`(TemporalAdjusters.lastDayOfMonth())

extension (n: Int)
  def hours: Duration   = Duration.ofHours(n.toLong)
  def minutes: Duration = Duration.ofMinutes(n.toLong)
  def seconds: Duration = Duration.ofSeconds(n.toLong)
  def days: Duration    = Duration.ofDays(n)

/** Seconds between two LocalDateTimes (absolute value). */
def elapsedSeconds(t1: LocalDateTime, t2: LocalDateTime): Long =
  Duration.between(t1, t2).abs.getSeconds

extension (d: Duration)
  def getStandardSeconds: Long = d.getSeconds
  def getStandardMinutes: Long = d.getSeconds / 60
  def getStandardHours: Long   = d.getSeconds / 3600
  def getStandardDays: Long    = d.getSeconds / 86400

extension (dow: DayOfWeek)
  def >=(other: DayOfWeek): Boolean = dow.compareTo(other) >= 0
  def >(other: DayOfWeek): Boolean  = dow.compareTo(other) >  0
  def <=(other: DayOfWeek): Boolean = dow.compareTo(other) <= 0
  def <(other: DayOfWeek): Boolean  = dow.compareTo(other) <  0

/** `+`/`-` extensions for LocalDateTime with Duration/Period.
 *  Re-exported at the package level so `import uni.time.*` provides them.
 */
object TimeArith:
  extension (dt: LocalDateTime)
    def -(d: Duration): LocalDateTime       = dt.minus(d)
    def +(d: Duration): LocalDateTime       = dt.plus(d)
    def -(period: Period): LocalDateTime    = dt.minus(period)
    def +(period: Period): LocalDateTime    = dt.plus(period)

export TimeArith.*



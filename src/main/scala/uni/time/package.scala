package uni.time

import java.time.{Instant, LocalDateTime, ZoneId, Duration, Period, Month}
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

// Top-level exports (internal package visibility)
export TimeUtils.{parseDate as parseDateTime}
export TimeUtils.*
export SmartParse.parseDateSmart
export java.time.LocalDateTime

/** How strictly the day/month order is applied to a *numeric* date.
 *
 *  `monthFirst` alone only decides the genuinely ambiguous case (both numbers 1-12).
 *  An unambiguous date overrides it: with `monthFirst = true`, `04/12/1992` reads as
 *  April 12 while `13/12/1992` reads as December 13 -- so a consistent European column
 *  comes out mixed, with the wrong reading on every row whose day is 12 or less. The
 *  file is internally consistent; the parse is not.
 *
 *  `Auto` is that behaviour, and stays the default: nothing has to be declared, which
 *  is the point. The strict modes let a caller say "this column is one convention" and
 *  have the parser hold to it.
 */
enum DateOrder:
  /** Unambiguous input wins; ambiguous input uses `monthFirst`. The default. */
  case Auto
  /** Month first, always. A date only readable as day-first yields `BadDate`. */
  case MonthFirst
  /** Day first, always. A date only readable as month-first yields `BadDate`. */
  case DayFirst

case class TimeConfig(monthFirst: Boolean = true, order: DateOrder = DateOrder.Auto)

private val _timeConfig = new scala.util.DynamicVariable[TimeConfig](TimeConfig())

/** Global access to the current thread-local time configuration. */
def timeConfig: TimeConfig = _timeConfig.value

/** Run a block of code with a specific TimeConfig. */
def withTimeConfig[T](config: TimeConfig)(thunk: => T): T =
  _timeConfig.withValue(config)(thunk)

/** Run a block of code with DMY (Day-Month-Year) preference for ambiguous dates. */
def withDMY[T](thunk: => T): T =
  withTimeConfig(TimeConfig(monthFirst = false))(thunk)

/** Run a block of code with MDY (Month-Day-Year) preference for ambiguous dates. */
def withMDY[T](thunk: => T): T =
  withTimeConfig(TimeConfig(monthFirst = true))(thunk)

// For backward compatibility or global overrides if needed (less common with DynamicVariable)
private[uni] def withTimeConfigGlobal(config: TimeConfig): Unit = 
  // This is tricky with DynamicVariable. We usually just rely on scoped values.
  // But if the user really wants to change the global default:
  () 

type DateTime = java.time.LocalDateTime // alias used by pallet
type Instant = java.time.Instant
type ZoneId = java.time.ZoneId
type Duration = java.time.Duration


// Extensions
extension (inst: Instant)
  /** Formats this instant in `zone`.
   *
   *  Delegates to the `LocalDateTime` extension so both go through `DateFormat`. While
   *  this used `DateTimeFormatter` directly, a pattern rejected on a `LocalDateTime`
   *  -- capital `Y`, say -- was still quietly accepted here: the sort of split that
   *  makes a formatting bug depend on which type a script happened to be holding.
   */
  def toString(pattern: String, zone: ZoneId = ZoneId.systemDefault()): String =
    LocalDateTime.ofInstant(inst, zone).toString(pattern)

extension (dt: LocalDateTime)
  // formatting
  // Formatting goes through `DateFormat`, which works on the field values rather than
  // a `java.time` object. That is what lets the date type be decoupled from
  // `java.time` later without `toString(fmt)` changing, and it is the same
  // implementation the Rust port uses -- `std` has no date formatting, and translating
  // Java patterns to strftime would have been both more work and more to get wrong.
  //
  // Equivalence with `DateTimeFormatter.ofPattern` is proved rather than assumed: see
  // `DateFormatSuite`, which compares every pattern the repo uses across a range of
  // moments. The one intended difference is that month and weekday names are always
  // English, where `ofPattern` follows the JVM's default FORMAT locale.
  private def formatted(pattern: String): String =
    DateFormat.format(dt.getYear, dt.getMonthValue, dt.getDayOfMonth,
      dt.getHour, dt.getMinute, dt.getSecond, dt.getNano, pattern)

  def ymd: String                  = formatted("yyyy-MM-dd")
  def ymdhms: String               = formatted("yyyy-MM-dd HH:mm:ss")
  def fmt(pattern: String): String = formatted(pattern)
  def toString(fmt: String): String = formatted(fmt)

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



/** Runs `thunk` with the day/month order enforced rather than inferred per string.
 *
 *  Dynamically scoped and thread-local, like `withTimeConfig`: it applies to every
 *  parse made during the block on this thread, nests, and is restored on exit.
 *
 *  Derived from the enclosing config rather than built fresh. Constructing a new
 *  `TimeConfig` here discarded whatever an outer `withTimeConfig` had set, so
 *  `withDateOrder(Auto)` inside a `monthFirst = false` block silently flipped the
 *  ambiguous case back to month-first. `Auto` now keeps the inherited preference;
 *  only the strict modes pin it, since under enforcement the ambiguous case must
 *  follow the declared order too.
 */
def withDateOrder[T](order: DateOrder)(thunk: => T): T =
  val base = timeConfig
  val monthFirst = order match
    case DateOrder.MonthFirst => true
    case DateOrder.DayFirst   => false
    case DateOrder.Auto       => base.monthFirst
  withTimeConfig(base.copy(monthFirst = monthFirst, order = order))(thunk)

/** Infers a column's day/month convention from its unambiguous rows.
 *
 *  The ease-of-use answer to enforcement: a caller need not know which convention a
 *  file uses, only that it uses one. Any row with a number above 12 in the first or
 *  second position settles it; ambiguous rows say nothing and are ignored.
 *
 *  Returns `Auto` when no row is decisive -- every date fits both readings, so there
 *  is nothing to enforce and `monthFirst` decides as before. Returns `None` when rows
 *  disagree, i.e. the column genuinely mixes conventions and no single order can be
 *  right; the caller has to decide what that means rather than be handed a guess.
 */
def inferDateOrder(samples: IterableOnce[String]): Option[DateOrder] =
  val votes = samples.iterator.flatMap(SmartParse.numericDateOrder).toSet
  if votes.size > 1 then None
  else Some(votes.headOption.getOrElse(DateOrder.Auto))

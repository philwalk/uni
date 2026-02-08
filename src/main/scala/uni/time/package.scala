package uni.time

import java.time.{Instant, LocalDateTime, ZoneId, Duration}
import java.time.format.DateTimeFormatter

// Top-level exports (internal package visibility)
export TimeUtils.{parseDate as parseDateTime}
export TimeUtils.*
export ChronoParse.parseDateChrono
export SmartParse.parseDateSmart

type LocalDateTime = java.time.LocalDateTime
type Instant = java.time.Instant
type ZoneId = java.time.ZoneId
type Duration = java.time.Duration

type DateTime = java.time.LocalDateTime // alias used by pallet

// Extensions
extension (inst: Instant)
  def toString(pattern: String, zone: ZoneId = ZoneId.systemDefault()): String =
    LocalDateTime.ofInstant(inst, zone).format(DateTimeFormatter.ofPattern(pattern))

extension (dt: LocalDateTime)
  def toString(fmt: String): String = dt.format(DateTimeFormatter.ofPattern(fmt))
  def >=(other: LocalDateTime): Boolean = dt.compareTo(other) >= 0
  def <=(other: LocalDateTime): Boolean = dt.compareTo(other) <= 0
  def >(other: LocalDateTime): Boolean  = dt.compareTo(other) > 0
  def <(other: LocalDateTime): Boolean  = dt.compareTo(other) < 0

extension (n: Int)
  def days: Duration = Duration.ofDays(n.toLong)
  def hours: Duration = Duration.ofHours(n.toLong)
  def minutes: Duration = Duration.ofMinutes(n.toLong)
  def seconds: Duration = Duration.ofSeconds(n.toLong)

extension (dt: LocalDateTime)
  def -(d: Duration): LocalDateTime =
    dt.minus(d)

  def +(d: Duration): LocalDateTime =
    dt.plus(d)

package uni.data

import uni.*
import uni.time.*
import uni.data.Big
import uni.data.Big.Big
import scala.util.Try
import scala.util.matching.Regex
import scala.math.BigDecimal

export Big.*
export BigUtils.*

object BigUtils:

  // ------------------------------------------------------------
  // Core type & sentinels
  // ------------------------------------------------------------

  private val BadNumLiteral = "-0.00000001234567890123456789"
  val BadNum: Big = Big(BadNumLiteral)

  val BigZero: Big = Big(0)
  val BigOne:  Big = Big(1)
  val Hundred: Big = Big(100)

  private val debug: Boolean =
    Option(System.getenv("DEBUG")).isDefined

  inline def isBad(b: Big): Boolean =
    b == BadNum

  inline def orBad(opt: Option[Big]): Big =
    opt.getOrElse(BadNum)

  // ------------------------------------------------------------
  // Regex patterns (kept from original)
  // ------------------------------------------------------------

  val NumPattern1: Regex = """(?i)([-\(]\s*)?(\d[\.\d,]+)[%\)]?[%KMB]?""".r
  val NumPattern2: Regex = """(-?\s*[\.\d,]+)(E-?\d+)([%\)]?)([%KMB]?)""".r
  val NumPattern3: Regex = """-?(\d+)([%KMB]?)""".r
  val NumPattern4: Regex = """-?(\d+)(.?[0-9]*E[-+][0-9]+)?""".r

  // ------------------------------------------------------------
  // Character validation
  // ------------------------------------------------------------

  def validNumChar(c: Char): Boolean =
    (c >= '0' && c <= '9') ||
    c == '.' || c == '-' || c == 'E' || c == 'e' ||
    c == '+' || c == '%' || c == '$' || c == ','

  // ------------------------------------------------------------
  // String → Big parsing (BadNum sentinel preserved)
  // ------------------------------------------------------------

  def str2num(raw: String): Big =
    val trimmed = raw.trim
    if !trimmed.forall(validNumChar) then
      BadNum
    else
      val cleaned =
        trimmed
          .replaceAll("^[^-\\.\\d]+", "")
          .replaceAll("[$,]", "")

      val normalized =
        if cleaned.startsWith(".") then "0" + cleaned else cleaned

      if normalized.isEmpty || !normalized.forall(validNumChar) then
        BadNum
      else
        val nopct = normalized.replace("%", "")
        val parsed = Try(Big(BigDecimal(nopct))).toOption
        val base   = orBad(parsed)
        if isBad(base) then BadNum
        else if nopct != normalized then base / Hundred
        else base

  // ------------------------------------------------------------
  // Numeric detection
  // ------------------------------------------------------------

  def isNumeric(col: String): Boolean =
    val s = col.trim
    if s.isEmpty then false
    else
      val numsAndSuch = s.filter(validNumChar)
      if s.length != numsAndSuch.length ||
         numsAndSuch.count(c => c == '-' || c == '/') > 1
      then false
      else
        Try(s.toDouble).isSuccess || (s match
          case NumPattern1(_, _)        => true
          case NumPattern2(_, _, _, _)  => true
          case NumPattern3(_, _)        => true
          case NumPattern4(_, _)        => true
          case _                        => false
        )

  // ------------------------------------------------------------
  // Most specific type: String | Big | DateTime
  // ------------------------------------------------------------

  def getMostSpecificType(raw: String): String | Big | DateTime =
    var col = raw.replaceAll("""[\$]""", "").trim

    if debug then println(s"# rawcol[$raw]")

    val value: Any =
      if col.isEmpty then col
      else if isNumeric(col) then
        if debug then println("Numeric match")

        var negative = false
        var percent  = false
        var factor   = BigOne

        col = col.replaceAll(",", "")

        if col.contains("%") then
          percent = true
          col = col.replaceAll("%", "")

        def consumeFactor(multiplier: Big): Unit =
          col = col.dropRight(1)
          factor = multiplier

        col.toLowerCase.lastOption.foreach {
          case 'k' => consumeFactor(Big(1_000))
          case 'm' => consumeFactor(Big(1_000_000))
          case 'b' => consumeFactor(Big(1_000_000_000))
          case _   => ()
        }

        if col.startsWith("(") && col.endsWith(")") then
          negative = true
          col = col.substring(1, col.length - 1).trim
        else if col.startsWith("-") then
          negative = true
          col = col.substring(1).trim

        val base = Try(Big(col)).getOrElse(BadNum)

        val signed =
          if negative && !isBad(base) then -base else base

        val pctAdjusted =
          if percent && !isBad(signed) then signed / Hundred else signed

        if isBad(pctAdjusted) then BadNum
        else pctAdjusted * factor

      else if col.length < 7 then col
      else
        Try(parseDate(col)).getOrElse(col)

    (value: @unchecked) match
      case bd: BigDecimal => Big(bd)
      case str: String    => str
      case dt: DateTime   => dt
      case other          => other.toString

  // ------------------------------------------------------------
  // Big constructors (explicit, minimal)
  // ------------------------------------------------------------

//  def big(str: String): Big = str2num(str)
//  def big(bd: Big): Big = bd
//  def big(d: Double): Big = Big(d)
//  def big(i: Int): Big = Big(i)
//  def big(l: Long): Big = Big(l)

  // ------------------------------------------------------------
  // Formatting DSL core
  // ------------------------------------------------------------

  final case class NumFormat(
    colWidth: Int = 9,
    dec: Int = 2,
    factor: Double = 1.0,
    abbreviate: Boolean = false,
    suffix: String = ""
  )

  object NumFormat:
    val Default: NumFormat =
      NumFormat()

    val Abbrev: NumFormat =
      NumFormat(abbreviate = true)

    val Percent: NumFormat =
      NumFormat(dec = 2, factor = 100.0, suffix = "%")

    val IntPercent: NumFormat =
      NumFormat(colWidth = 3, dec = 0, factor = 100.0, suffix = "%")

  // ------------------------------------------------------------
  // Formatting helpers (BadNum-aware)
  // ------------------------------------------------------------

  def numStr(xx: Big, fmt: NumFormat = NumFormat.Default): String =
    if isBad(xx) then
      " " * (fmt.colWidth - 3) + "N/A"
    else
      val NumFormat(colWidth, dec, factor, abbreviate, suffix) = fmt
      val fmtMain  = s"%${colWidth}.${dec}f"
      val fmtShort = s"%${colWidth - 1}.${dec}f"
      val scaled   = xx * Big(factor)

      val raw =
        if abbreviate && scaled >= Big(1e9) then
          fmtShort.format((scaled / Big(1e9)).toDouble) + "B"
        else if abbreviate && scaled >= Big(1e6) then
          fmtShort.format((scaled / Big(1e6)).toDouble) + "M"
        else
          fmtMain.format(scaled.toDouble)

      val withSuffix = raw + suffix

      withSuffix.trim match
        case "-0.00" => withSuffix.replace("-", " ")
        case _       => withSuffix

  def numStrPct(xx: Big, fmt: NumFormat = NumFormat.Percent): String =
    numStr(xx, fmt)

  def num2string(xx: Big, dec: Int = 2, factor: Double = 1.0): String =
    numStr(xx, NumFormat(dec = dec, factor = factor))

  def big2double(xx: Big): Double =
    if isBad(xx) then Double.NaN else xx.toDouble

package uni.data

import uni.time.*
import scala.util.Try
import scala.util.matching.Regex
import scala.math.BigDecimal

//export BigUtils.getMostSpecificType

object BigUtils:
  import uni.data.Big
  import uni.data.Big.Big
  import uni.data.Big.*

  /** A CSV cell value, once typed. Names [[uni.time.UniDateTime]] because that is what
   *  `parseDate` produces -- a union cannot be entered through an implicit conversion, so
   *  a `LocalDateTime` member here would be unreachable from uni's own parser and would
   *  make `toStr` throw `MatchError` on the very values it exists to render. */
  type CVD = UniDateTime|Big|Option[Int]|String|Int

  // ------------------------------------------------------------
  // Core type & sentinels
  // ------------------------------------------------------------

  inline def isBad(n: Big): Boolean = 
    // Cast to AnyRef to enable reference equality check (eq)
    (n.asInstanceOf[AnyRef] eq BigNaN.asInstanceOf[AnyRef]) || (n == BigNaN)

  inline def orBad(opt: Option[Big]): Big =
    opt.getOrElse(BigNaN)

//  val BigZero: Big = Big(0)
  //val BigOne:  Big = Big.one
  //val Hundred: Big = Big.hundred

  private val debug: Boolean =
    Option(System.getenv("DEBUG")).isDefined


  // ------------------------------------------------------------
  // Regex patterns (kept from original)
  // ------------------------------------------------------------

  // Repaired 0.16.0 (behavior pinned by the big-parity `isnumeric` rows):
  //  - NumPattern3 gained the `(?i)` the other suffix pattern always had, so `5k`
  //    classifies like `5K` instead of depending on the author's shift key;
  //  - NumPattern4's decimal point is now escaped -- the bare `.` matched ANY
  //    character, so `12x34E+5` counted as numeric.
  private val NumPattern1: Regex = """(?i)([-\(]\s*)?(\d[\.\d,]+)[%\)]?[%KMB]?""".r
  //  - NumPattern2 gained `(?i)` with the others (0.16.0): "1.5e5%" classifies like
  //    "1.5E5%" -- str2num could always parse both, so the two disagreed;
  private val NumPattern2: Regex = """(?i)(-?\s*[\.\d,]+)(E-?\d+)([%\)]?)([%KMB]?)""".r
  private val NumPattern3: Regex = """(?i)-?(\d+)([%KMB]?)""".r
  private val NumPattern4: Regex = """-?(\d+)(\.?[0-9]*E[-+][0-9]+)?""".r

  // ------------------------------------------------------------
  // Character validation
  // ------------------------------------------------------------

  def validNumChar(c: Char): Boolean =
    (c >= '0' && c <= '9') ||
    c == '.' || c == '-' || c == 'E' || c == 'e' ||
    c == '+' || c == '%' || c == '$' || c == ','

  // ------------------------------------------------------------
  // String → Big parsing (BigNaN sentinel preserved)
  // ------------------------------------------------------------

  def str2num(raw: String): Big =
    val trimmed = raw.trim
    if !trimmed.forall(validNumChar) then
      BigNaN
    else
      // `$` and `,` are decoration wherever they appear. The old leading-junk strip
      // (^[^-\.\d]+) discarded ANY non-numeric prefix, so "%50" parsed as 50 -- the
      // percent went as junk and never divided -- while "50%" was 0.5, and "E5"
      // read as 5. Repaired 0.16.0; BigDecimal handles a leading '+' natively.
      val cleaned = trimmed.replaceAll("[$,]", "")

      val normalized =
        if cleaned.startsWith(".") then "0" + cleaned else cleaned

      if normalized.isEmpty then
        BigNaN
      else
        // percent is one trailing suffix, not a character to delete globally --
        // the old replace-all made "5%5" parse as 55
        val nopct = normalized.stripSuffix("%")
        val base: Big = orBad(Try(Big(BigDecimal(nopct))).toOption)
        if isBad(base) then BigNaN
        else if nopct != normalized then base / Big.hundred
        else base

  // ------------------------------------------------------------
  // Numeric detection
  // ------------------------------------------------------------

  def isNumeric(col: String): Boolean =
    val s = col.trim
    if s.isEmpty then false
    else
      val numsAndSuch = s.filter(validNumChar)
      // just '-': the old `|| c == '/'` half was dead, since '/' is not a validNumChar
      if numsAndSuch.count(_ == '-') > 1 then false
      else if s.length == numsAndSuch.length then
        // All chars are basic numeric — try direct parse then all regex patterns
        Try(s.toDouble).isSuccess || (s match
          case NumPattern1(_, _)        => true
          case NumPattern2(_, _, _, _)  => true
          case NumPattern3(_, _)        => true
          case NumPattern4(_, _)        => true
          case _                        => false
        )
      else
        // Has chars outside validNumChar (K/M/B, parentheses, etc.) —
        // try the patterns designed to handle them
        s match
          case NumPattern1(_, _) => true
          case NumPattern3(_, _) => true
          case _                 => false

  // ------------------------------------------------------------
  // Most specific type: String | Big | DateTime
  // ------------------------------------------------------------

  def getMostSpecificType(raw: String): String | Big | DateTime =
    var col = raw.replaceAll("""[\$]""", "").trim

    if debug then print(s"# rawcol[$raw]\n")

    val value: Any =
      if col.isEmpty then col
      else if isNumeric(col) then
        if debug then print("Numeric match\n")

        var negative = false
        var percent  = false
        var factor   = Big.one

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

        val base = Try(Big(col)).getOrElse(BigNaN)

        val signed =
          if negative && !isBad(base) then -base else base

        val pctAdjusted =
          if percent && !isBad(signed) then signed / Big.hundred else signed

        if isBad(pctAdjusted) then BigNaN
        else pctAdjusted * factor

      // < 6, not < 7 (0.16.0): "1/2/24" is a date. And `parseDate` answers BadDate
      // rather than throwing since the SmartParse migration, so the old
      // Try(..).getOrElse(col) returned the *sentinel* as a DateTime for every
      // unparseable longer string -- the String has to be restored explicitly.
      else if col.length < 6 then col
      else
        val d = parseDate(col)
        if d == BadDate || d == EmptyDate then col else d

    value match
      case bd: BigDecimal => Big(bd)
      case str: String    => str
      case dt: DateTime   => dt
      case other          => other.toString

  // ------------------------------------------------------------
  // Big constructors (explicit, minimal)
  // ------------------------------------------------------------

  /*
  def big(s: String): Big      = apply(s)
  def big(d: Double): Big      = apply(d)
  def big(i: Int): Big         = apply(i)
  def big(l: Long): Big        = apply(l)
  def big(bd: BigDecimal): Big = apply(bd)
  */

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
  // Formatting helpers (BigNaN-aware)
  // ------------------------------------------------------------

  def numStr(xx: Big, fmt: NumFormat = NumFormat.Default): String =
    if isBad(xx) then
      " " * (fmt.colWidth - 3) + "N/A"
    else
      val NumFormat(colWidth, dec, factor, abbreviate, suffix) = fmt
      val fmtMain  = s"%${colWidth}.${dec}f"
      val fmtShort = s"%${colWidth - 1}.${dec}f"
      val scaled   = xx * Big(factor)
      // abbreviation is by magnitude (0.16.0): -2.5e9 gets its "B" like +2.5e9 does
      val mag      = scaled.abs

      val raw =
        if abbreviate && mag >= Big(1e9) then
          fmtShort.format((scaled / Big(1e9)).toDouble) + "B"
        else if abbreviate && mag >= Big(1e6) then
          fmtShort.format((scaled / Big(1e6)).toDouble) + "M"
        else
          fmtMain.format(scaled.toDouble)

      // ANY all-zero negative rendering blanks its sign (0.16.0) -- the old exact
      // "-0.00" match let "-0.000" (dec 3) and "-0.00%" (checked after the suffix
      // was appended) keep a minus sign on a zero
      val unsigned =
        if raw.trim.matches("-0(\\.0+)?") then raw.replace("-", " ") else raw
      unsigned + suffix

  def numStrPct(xx: Big, fmt: NumFormat = NumFormat.Percent): String =
    numStr(xx, fmt)

  def num2string(xx: Big, dec: Int = 2, factor: Double = 1.0): String =
    numStr(xx, NumFormat(dec = dec, factor = factor))

  def big2double(xx: Big): Double =
    if isBad(xx) then Double.NaN else xx.toDouble

  def toStr(x: CVD): String = {
    (x: @unchecked) match {
    case s: String        => s
    case n: (Int | Long)  => n.toString
    case BigNaN           => "N/A"
    case b: Big           => b.toString
    case d: UniDateTime   => d.toString("yyyy-MM-dd")
    case Some(oi: Int)    => oi.toString
    case None             => ""
    }
  }


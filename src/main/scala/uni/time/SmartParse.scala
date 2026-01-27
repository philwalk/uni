package uni.time

import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatterBuilder
import java.time.*
import java.time.format.*
import java.util.Locale

object SmartParse {

  // ------------------------------------------------------------
  // parseSmart
  // ------------------------------------------------------------
  def parseSmartOld(datestr: String): LocalDateTime = {
    val clean0 =
      datestr
        .replaceAll("\\s+", " ").trim
        .replaceAll("\\s+\\([A-Za-z]{2,5}\\)$", "")     // (MST)
        .replaceAll("\\s+UTC$", "")                     // trailing UTC
        .replaceAll("\\s+GMT[+0-9:]+$", "")             // GMT+0000
        .replaceAll("([0-9]{2}:[0-9]{2}:[0-9]{2})/[0-9:+-]{1,6}$", "$1") // 2016/11/17 10:36:34/81
        .replaceAll("([0-9]{2}:[0-9]{2}:[0-9]{2})\\s*/[0-9:+-]{1,6}$", "$1") // 2011/01/01 04:19:20 /0:00
        .replaceAll("\\s[+-][0-9]{4}$", "")             // -0500, +0700
        .replaceAll("//", "/")                          // 1//1/17 → 1/1/17
        .trim


    // normalize numeric delimiters BEFORE classification
    val clean =
      normalizeNumericDelimiters(clean0)

    val shape = classify(clean)
    val patterns = patternsByShape(shape)
//  if false then
//    println(s"[SMART] patterns for $shape:")
//    patterns.foreach(p => println(s"  - $p"))

    tryPatterns(clean, patterns)
      .orElse(tryPatterns(clean, patternsByShape(Shape.Unknown)))
      .getOrElse(throw new DateTimeParseException(s"Cannot parse: [$clean] ; shape is [$shape])", clean, 0))
  }

  def parseDateSmartOld(datestr: String): LocalDateTime = parseSmartOld(datestr) // alias

  // ------------------------------------------------------------
  // NEW: Normalize delimiters ONLY for numeric-only dates
  // ------------------------------------------------------------
  private def normalizeNumericDelimiters(s: String): String =
    // Guard 1: ISO8601 → leave hyphens alone
    if s.exists(c => c == 'T' || c == 'Z') then s
    // Guard 2: any alphabetic month/weekday → RFCish / HyphenDMY → leave alone
    else if s.exists(_.isLetter) then s
    else
      // Purely numeric date → normalize - and . to /
      s.map {
        case '-' => '/'
        case '.' => '/'
        case c   => c
      }

  // ------------------------------------------------------------
  // Shapes
  // ------------------------------------------------------------
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

  // ------------------------------------------------------------
  // classify
  // ------------------------------------------------------------
  def classify(s: String): Shape = classifyOld(s)

  def classifyByPatterns(s: String): Shape =
    val orderedShapes = List(
      Shape.ISO8601,
      Shape.RFC,
      Shape.RFCish,
      Shape.SlashYMD,
      Shape.SlashMDY,
      Shape.SlashDMY,
      Shape.HyphenDMY,
      Shape.SquashedMonthDay,
      Shape.Unknown
    )

    orderedShapes.find { shape =>
      SmartParse.tryPatterns(s, SmartParse.patternsByShape(shape)).isDefined
    }.getOrElse(Shape.Unknown)

  def classifyOld(s: String): Shape =
    val hasSlash   = s.contains("/")
    val hasHyphen  = s.contains("-")
  //val hasComma   = s.contains(",")
    val hasLetters = s.exists(_.isLetter)

    // 1. SquashedMonthDay (Apr12-11)
    if hasLetters && hasHyphen && !hasSlash && (s.matches("(?i)^[A-Za-z]{3}\\d{1,2}-\\d{2}$") || s.matches("(?i)^[A-Za-z]{3}\\d{1,2}-\\d{2}(?:$| .*)$")) then
      Shape.SquashedMonthDay

    // 2. HyphenDMY (11-Apr-2016)
    else if hasLetters && hasHyphen &&
            s.matches("(?i)^\\d{1,2}-[A-Za-z]{3}-\\d{2,4}(?:$| .*)") then
      Shape.HyphenDMY

    else if hasSlash && hasLetters && s.matches("(?i)^\\d{1,2}/[A-Za-z]{3}/\\d{4}(?:$| .*)") then
      Shape.SlashDMY

    // 3. RFC-ish (weekday/month names)
    else if hasLetters && (
            s.matches("(?i)^[A-Za-z]{3}.*") ||
            s.matches("(?i)^\\d{1,2} [A-Za-z]{3} .*")
         ) then
      Shape.RFCish

    // 4. MonthCommaYear (May 12, 2024 ...)
    else if s.matches("(?i)^[A-Za-z]{3,9} \\d{1,2}, ?\\d{4}(?:$| .*)") then
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

    // 8. ISO8601
    else if s.matches("\\d{4}-\\d{2}-\\d{2}T.*") then
      Shape.ISO8601

    else if s.matches("^\\d{8} \\d{3,4} [A-Za-z]{2,4}$") then
      Shape.RFCish

    else Shape.Unknown



  def hasTimeFields(p: String): Boolean =
    p.exists(ch => ch == 'H' || ch == 'm' || ch == 's')

  def tryPatterns(s: String, patterns: List[String]): Option[LocalDateTime] =
    patterns.iterator.flatMap { p =>
      try
        val fmt =
          new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(p)
            .toFormatter(Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.LENIENT)

        val hasTime =
          p.exists(ch => ch == 'H' || ch == 'h' || ch == 'm' || ch == 's' || ch == 'S')

        val dt =
          if hasTime then LocalDateTime.parse(s, fmt)
          else LocalDate.parse(s, fmt).atStartOfDay()

        Some(dt)
      catch
        case _: Exception => None
    }.toSeq.headOption

  // ------------------------------------------------------------
  // patternsByShape (unchanged)
  // ------------------------------------------------------------
  val patternsByShape: Map[Shape, List[String]] = Map(

    Shape.SquashedMonthDay -> List(
      "MMMd-yy",
      "MMMdd-yy",
      "MMMd-yy HH:mm:ss",
      "MMMdd-yy HH:mm:ss",
    ),

    Shape.HyphenDMY -> List(
      "d-MMM-yy",
      "d-MMM-yyyy",
      "d-MMM-yyyy HH:mm:ss",
      "d-MMM-yyyy HH:mm:ss Z",
      "dd-MMM-yy",
      "dd-MMM-yyyy",
      "dd-MMM-yyyy HH:mm:ss",
      "dd-MMM-yyyy HH:mm:ss Z",
    ),

    Shape.SlashMDY -> List(
      "M/d/yy",
      "M/d/yy a",
      "M/d/yy h:mm a",
      "M/d/yy H:mm",
      "M/d/yy H:mm:ss",
      "M/d/yy hh:mm a",
      "M/d/yy HH:mm",
      "M/d/yy hh:mm:ss a",
      "M/d/yyyy a",
      "M/d/yyyy h:mm a",
      "M/d/yyyy H:mm",
      "M/d/yyyy h:mm:ss a",
      "M/d/yyyy H:mm:ss",
      "M/d/yyyy h:mma",
      "M/d/yyyy HH:mm",
      "M/d/yyyy hh:mm:ss a z",
      "M/d/yyyy hh:mm:ss a",
      "M/d/yyyy HH:mm:ss Z",
      "M/d/yyyy HH:mm:ss",
      "M/d/yyyy",
      "MM/dd/yy a",
      "MM/dd/yy h:mm a",
      "MM/dd/yy H:mm",
      "MM/dd/yy H:mm:ss",
      "MM/dd/yy hh:mm a",
      "MM/dd/yy HH:mm",
      "MM/dd/yy hh:mm:ss a",
      "MM/dd/yy",
      "MM/dd/yyyy a",
      "MM/dd/yyyy h:mm a",
      "MM/dd/yyyy H:mm",
      "MM/dd/yyyy h:mm:ss a",
      "MM/dd/yyyy H:mm:ss",
      "MM/dd/yyyy h:mma",
      "MM/dd/yyyy HH:mm",
      "MM/dd/yyyy hh:mm:ss a z",
      "MM/dd/yyyy hh:mm:ss a",
      "MM/dd/yyyy HH:mm:ss Z",
      "MM/dd/yyyy HH:mm:ss",
      "MM/dd/yyyy",
    ),

    Shape.SlashDMY -> List(
      "d/M/yy H:mm",
      "d/M/yy H:mm:ss",
      "d/M/yy",
      "d/M/yyyy",
      "d/M/yyyy a",
      "d/M/yyyy H:mm",
      "d/M/yyyy H:mm:ss",
      "d/M/yyyy h:mma",
      "d/M/yyyy HH:mm",
      "d/M/yyyy HH:mm:ss Z",
      "d/M/yyyy HH:mm:ss",
      "d/MMM/yyyy",
      "dd/MM/yy H:mm",
      "dd/MM/yy H:mm:ss",
      "dd/MM/yy",
      "dd/MM/yyyy a",
      "dd/MM/yyyy H:mm",
      "dd/MM/yyyy H:mm:ss",
      "dd/MM/yyyy h:mma",
      "dd/MM/yyyy HH:mm",
      "dd/MM/yyyy HH:mm:ss Z",
      "dd/MM/yyyy HH:mm:ss",
      "dd/MM/yyyy",
      "dd/MMM/yyyy",
    ),

    Shape.SlashYMD -> List(
      "yyyy/M/d H:mm",
      "yyyy/M/d H:mm:ss",
      "yyyy/M/d h:mma",
      "yyyy/M/d HH:mm",
      "yyyy/M/d",
      "yyyy/MM/dd a",
      "yyyy/MM/dd H:mm",
      "yyyy/MM/dd H:mm:ss",
      "yyyy/MM/dd h:mma",
      "yyyy/MM/dd HH:mm",
      "yyyy/MM/dd HH:mm:ss",
      "yyyy/MM/dd HH:mm:ss.S",
      "yyyy/MM/dd",
    ),

    Shape.MonthCommaYear -> List(
      "MMM d,yyyy",
      "MMM d, yyyy",
      "MMM dd,yyyy",
      "MMMM d,yyyy",
      "MMMM d, yyyy",
    ),

    //textual month, textual weekday (optional), time, year, optional timezone
    Shape.RFCish -> List(
      "d MMM yyyy HH:mm:ss Z '('z')'",
      "d MMM yyyy HH:mm:ss Z z",
      "d MMM yyyy HH:mm:ss Z",
      "d MMM yyyy HH:mm:ss",
      "d MMM yyyy",
      "dd MMM yyyy HH:mm:ss",
      "dd MMM yyyy",
      "EEE MMM d HH:mm:ss yyyy",
      "EEE MMM d yyyy HH:mm:ss 'GMT'Z",
      "EEE MMM d yyyy HH:mm:ss",
      "EEE MMM dd HH:mm:ss yyyy",
      "EEE MMM dd yyyy h:mm:ss a z",
      "EEE MMM dd yyyy HH:mm:ss 'GMT'Z",
      "EEE MMM dd yyyy HH:mm:ss",
      "EEE, d MMM yyyy HH:mm:ss Z '('z')'",
      "EEE, d MMM yyyy HH:mm:ss Z z",
      "EEE, d MMM yyyy HH:mm:ss Z",
      "EEE, d MMM yyyy HH:mm:ss",
      "EEE, dd MMM yyyy HH:mm:ss",
      "EEE, MMM dd HH:mm:ss yyyy",
      "EEE,MMM d HH:mm:ss yyyy",
      "EEE,MMM dd HH:mm:ss yyyy",
      "EEEE d MMMM yyyy HH:mm:ss a",
      "EEEE d MMMM yyyy HH:mm:ss",
      "EEEE dd MMMM yyyy HH:mm:ss a",
      "EEEE dd MMMM yyyy HH:mm:ss",
      "EEEE, MMMM d, yyyy",
      "MM/dd HH:mm:ss yyyy",
      "MMM d, yyyy h:mm:ss a z",
      "MMM d, yyyy h:mm:ss a",
      "MMM dd, yyyy",
      "MMM-dd-yy",
      "MMM-dd-yyyy",
      "MMMM d, yyyy",
      "yyyyMMdd Hmm z",
      "yyyyMMdd HHmm z",
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
      "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
      "yyyy-MM-dd'T'HH:mm:ss",
      "yyyy-MM-dd'T'HH:mm:ss.S",
      "yyyy-MM-dd'T'HH:mm:ss.SS",
      "yyyy-MM-dd'T'HH:mm:ss.SSS",
      "yyyy-MM-dd'T'HH:mm:ss.SSSS",
      "yyyy-MM-dd'T'HH:mm:ss.SSSSS",
      "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
    ),

    Shape.Unknown -> List(
      "yyyy-MM-dd HH:mm:ss",
      "yyyy-MM-dd HH:mm:ss z",
      "yyyy-MM-dd",
    )
  )

}

package uni.time

import java.time.LocalDateTime
import java.time.format.DateTimeParseException

object SmartParse {

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

  def parseDateSmart(datestr: String): LocalDateTime = parseSmart(datestr) // alias

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

}

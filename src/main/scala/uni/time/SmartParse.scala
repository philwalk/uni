package uni.time

import uni.*
import java.time.format.*
import uni.time.TimeUtils.BadDate
import java.time.LocalDateTime

object SmartParse {
  //  Public API
  def parseDateSmart(datestr: String): LocalDateTime = parseSmart(datestr).getOrElse(BadDate) // alias

  private def parseSmart(s: String): Option[LocalDateTime] = {
    val cleaned  = preNormalize(s)
    val tokens   = {
      val tokenlist = tokenize(cleaned)
      stripWeekday(tokenlist)
    }
    classifyTokens(tokens) match
      case Shape.ISO8601 =>
        parseISO8601(s).orElse(parseISO8601Tokens(tokens))

      case Shape.YMD  => parseYMD(tokens)
      case Shape.MDY  => parseMDY(tokens)
      case Shape.DMY  => parseDMY(tokens)

      case Shape.MonthDayYear =>
        parseMonthDayYear(tokens)

      case Shape.DayMonthYear =>
        parseDayMonthYear(tokens)

      case Shape.MDYWithTime =>
        parseMDYWithTime(tokens)

      case Shape.Unknown =>
        None
  }

  //  Token model
  enum Token:
    case Num(value: Int, raw: String)
    case Word(value: String)
    case AmPm(isPm: Boolean)  // NEW: for AM/PM
    case Other(raw: String)

  //  Shapes
  enum Shape:
    case ISO8601
    case YMD
    case MDY
    case DMY
    case MonthDayYear      // Jan 1 2001
    case DayMonthYear      // 1 Jan 2001
    case MDYWithTime  
    case Unknown

  //  Pre-normalization
  import java.text.Normalizer
  private def preNormalize(s: String): String =
    // normalize Unicode punctuation and whitespace
    val ascii     = Normalizer.normalize(s, Normalizer.Form.NFKC)
    val ws        = ascii.replaceAll("\\p{Z}+", " ").replaceAll("\\s", " ")
    val noParenTz = stripParenTimezone(ws)
    val noT       = noParenTz.replaceAll("(?<=\\d)T(?=\\d)", " ")
    val cleaned   = noT.replaceAll("[^A-Za-z0-9]+$", "").trim
    cleaned

  private val ParenTzRegex =
    """\([A-Za-z0-9+\-: ]+\)""".r

  private def stripParenTimezone(s: String): String =
    ParenTzRegex.replaceAllIn(s, "")

  private val weekdayPrefixes =
    Set("mon","tue","wed","thu","fri","sat","sun")

  private def stripWeekday(tokens: List[Token]): List[Token] =
    tokens match
      case Token.Word(w) :: rest =>
        val prefix = w.toLowerCase.take(3)
        if weekdayPrefixes.contains(prefix) then rest else tokens
      case _ =>
        tokens

  //  Tokenizer
  private val LetterDigit = "([A-Za-z]+)([0-9]+)".r

  private val SplitRegex =
    """(?i)[^A-Za-z0-9]+|(?<=[A-Za-z])(?=\d)|(?<=\d)(?=[A-Za-z])""".r

  private def splitTokens(s: String): List[String] = {
    val b = List.newBuilder[String]
    var last = 0

    for m <- SplitRegex.findAllMatchIn(s) do
      val start = m.start
      val end   = m.end

      // If the match is zero-width, treat it as a boundary
      if start == end then
        if start > last then
          b += s.substring(last, start)
        last = start
      else
        if start > last then
          b += s.substring(last, start)
        last = end

    if last < s.length then
      b += s.substring(last)

    b.result().filter(_.nonEmpty)
  }

  private def tokenize(s: String): List[Token] = {
    val raw = splitTokens(s)
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

    raw.flatMap { t =>
      val stripped = t.stripSuffix(",").stripSuffix(".")
      stripped match
        // Case 1: letters followed by digits → split into Word + Num
        case LetterDigit(letters, digits) =>
          List(
            Token.Word(letters),
            Token.Num(digits.toIntOption.getOrElse(-1), digits)
          )

        // Case 2: pure digits
        case x if x.forall(_.isDigit) =>
          List(Token.Num(x.toIntOption.getOrElse(-1), x))

        // Case 3: pure letters → check for AM/PM or month word
        case x if x.forall(_.isLetter) =>
          val lower = x.toLowerCase
          if lower == "am" then List(Token.AmPm(false))
          else if lower == "pm" then List(Token.AmPm(true))
          else List(Token.Word(x))

        // Case 4: fallback
        case other =>
          List(Token.Other(other))
    }
  }

  enum Pattern(val pattern: String):
    case YMD                  extends Pattern("yyyy M d")
    case MDY                  extends Pattern("M d yyyy")
    case DMY                  extends Pattern("d M yyyy")
    case MonthDayYear         extends Pattern("MMM d yyyy")
    case MonthDayYearWithTime extends Pattern("MMM d HH:mm:ss yyyy")
    case MDYWithTime          extends Pattern("MM/dd HH:mm:ss yyyy")
    case DayMonthYear         extends Pattern("d MMM yyyy")
    case ISO8601Strict        extends Pattern("yyyy-MM-dd'T'HH:mm:ss")

  val patternsByShape: Map[Shape, List[String]] = Map(
    Shape.YMD                  -> List(Pattern.YMD.pattern),
    Shape.MDY                  -> List(Pattern.MDY.pattern),
    Shape.DMY                  -> List(Pattern.DMY.pattern),
    Shape.MonthDayYear         -> List(Pattern.MonthDayYear.pattern, Pattern.MonthDayYearWithTime.pattern),
    Shape.DayMonthYear         -> List(Pattern.DayMonthYear.pattern),
    Shape.ISO8601              -> List(Pattern.ISO8601Strict.pattern),
    Shape.MDYWithTime          -> List(Pattern.MDYWithTime.pattern),
    Shape.Unknown              -> Nil
  )

  //  Classifier
  def classify(s: String): Shape =
    if verboseUni then
      println("RAW:     " + s)
      println("NORMAL:  " + preNormalize(s))
      println("TOKENS:  " + tokenize(preNormalize(s)))
    if looksLikeISO8601Raw(s) then
      Shape.ISO8601
    else
      val norm = preNormalize(s)
      val tokens = tokenize(norm)
      classifyTokens(tokens)

  private[uni] def classifyTokens(tokens: List[Token]): Shape = {
    val nums  = tokens.collect { case Token.Num(v, _) => v }
    val words = tokens.collect { case Token.Word(w)   => w }

    // Word-based patterns FIRST (check actual token order)
    // MonthDayYear: Jan 17 2019 (month word comes first)
    if tokens.headOption.exists {
      case Token.Word(w) => isMonthWord(w)
      case _ => false
    } && nums.length >= 2 && isDay(nums(0)) then
      // Look for 4-digit year first, then 2-digit
      val hasValidYear = nums.drop(1).exists(isYear) || 
                         (nums.length >= 2 && isTwoDigitYear(nums(1)))
      if hasValidYear then Shape.MonthDayYear
      else
        if verboseUni then System.err.print(s"nums[$nums], words[$words]")
        Shape.Unknown

    // DayMonthYear: 17 Jan 2019 (day number comes first)
    else if tokens.headOption.exists {
      case Token.Num(v, _) => isDay(v)
      case _ => false
    } && words.nonEmpty && isMonthWord(words.head) then
      val hasValidYear = nums.drop(1).exists(isYear) || 
                         (nums.length >= 2 && isTwoDigitYear(nums(1)))
      if hasValidYear then Shape.DayMonthYear
      else
        if verboseUni then System.err.print(s"nums[$nums], words[$words]")
        Shape.Unknown

    // Check if first three numbers are a standard date pattern (YMD/MDY/DMY)
    else if nums.length >= 3 then
      val a = nums(0)
      val b = nums(1)
      val c = nums(2)

      // Special case: if we have 5+ numbers, check for MDYWithTime first
      if nums.length >= 5 && isMonth(a) && isDay(b) && c >= 0 && c <= 23 then
        val d = nums(3)
        val e = nums(4)
        // Check if it's MM/DD HH:MM:SS YYYY (6 nums) or MM/DD HH:MM YYYY (5 nums)
        if nums.length >= 6 then
          val year = nums(5)
          if d >= 0 && d <= 59 && (isYear(year) || isTwoDigitYear(year)) then
            return Shape.MDYWithTime
        else if nums.length == 5 then
          if d >= 0 && d <= 59 && (isYear(e) || isTwoDigitYear(e)) then
            return Shape.MDYWithTime

      // Standard patterns
      // YMD with optional time
      if isYear(a) && isMonth(b) && isDay(c) then Shape.YMD
      // MDY with optional time
      else if isMonth(a) && isDay(b) && (isYear(c) || isTwoDigitYear(c)) then Shape.MDY
      // DMY with optional time
      else if isDay(a) && isMonth(b) && (isYear(c) || isTwoDigitYear(c)) then Shape.DMY
      else Shape.Unknown

    else
      Shape.Unknown
  }

  //  Helpers
  private def isMonth(n: Int): Boolean = n >= 1 && n <= 12
  private def isDay(n: Int): Boolean = n >= 1 && n <= 31
  private def isYear(n: Int): Boolean = n >= 1000 && n <= 9999
  private def isTwoDigitYear(y: Int): Boolean = y >= 0 && y <= 99

  // Expand 2-digit year: 0-30 → 2000-2030, 31-99 → 1931-1999
  private def expand2DigitYear(y: Int): Int =
    if y >= 0 && y <= 30 then 2000 + y
    else if y >= 31 && y <= 99 then 1900 + y
    else y

  private val monthWords: Map[String, Int] = Map(
    "jan" -> 1, "january" -> 1,
    "feb" -> 2, "february" -> 2,
    "mar" -> 3, "march" -> 3,
    "apr" -> 4, "april" -> 4,
    "may" -> 5,
    "jun" -> 6, "june" -> 6,
    "jul" -> 7, "july" -> 7,
    "aug" -> 8, "august" -> 8,
    "sep" -> 9, "sept" -> 9, "september" -> 9,
    "oct" -> 10, "october" -> 10,
    "nov" -> 11, "november" -> 11,
    "dec" -> 12, "december" -> 12
  )

  private def isMonthWord(raw: String): Boolean =
    val w = normalizeMonthToken(raw)
    if monthWords.contains(w) then
      // exact match
      true
    else if w.length >= 3 && monthWords.keys.exists(_.startsWith(w)) then
      // prefix match (min length 3)
      true
    else
      // edit distance ≤ 1
      monthWords.keys.exists(full => levenshtein(w, full) <= 1)

  private def monthFromWord(raw: String): Int =
    val w = normalizeMonthToken(raw)
    val exact = monthWords.get(w)
    val prefix = if w.length >= 3 then
      monthWords.keys.find(_.startsWith(w)).map(monthWords.apply)
    else None

    val fuzzy = monthWords.keys
      .find(full => levenshtein(w, full) <= 1)
      .map(monthWords.apply)

    exact.orElse(prefix).orElse(fuzzy).getOrElse(
      throw new RuntimeException(s"Unrecognized month word: $raw")
    )

  private def normalizeMonthToken(w: String): String =
    w.toLowerCase.stripSuffix(",").stripSuffix(".")

  private def levenshtein(a: String, b: String): Int =
    val dp = Array.tabulate(a.length + 1, b.length + 1)((i, j) =>
      if i == 0 then j else if j == 0 then i else 0
    )
    for i <- 1 to a.length; j <- 1 to b.length; do
      val cost = if a(i - 1) == b(j - 1) then 0 else 1
      dp(i)(j) = math.min(
        math.min(dp(i - 1)(j) + 1, dp(i)(j - 1) + 1),
        dp(i - 1)(j - 1) + cost
      )
    dp(a.length)(b.length)

  private def looksLikeISO8601Raw(s: String): Boolean =
    // must contain literal T between date and time
    s.contains("T") &&
    s.count(_ == '-') == 2 &&
    s.exists(_ == ':')

  private def parseISO8601(raw: String): Option[LocalDateTime] =
    try
      Some(LocalDateTime.parse(raw, DateTimeFormatter.ISO_DATE_TIME))
    catch case _: Throwable => None

  private def parseISO8601Tokens(tokens: List[Token]): Option[LocalDateTime] =
    parseYMD(tokens) // fallback: treat as YMD + time tokens


  //  Semantic parsers
  private def parseYMD(tokens: List[Token]): Option[LocalDateTime] =
    val nums = tokens.collect { case Token.Num(v, _) => v }
    val ampm = tokens.collectFirst { case Token.AmPm(isPm) => isPm }

    if nums.length >= 3 && isYear(nums(0)) && isMonth(nums(1)) && isDay(nums(2)) then
      Some(buildDateTimeWithAmPm(nums(0), nums(1), nums(2), nums.drop(3), ampm))
    else None

  private def parseMDY(tokens: List[Token]): Option[LocalDateTime] =
    val nums = tokens.collect { case Token.Num(v, _) => v }
    val ampm = tokens.collectFirst { case Token.AmPm(isPm) => isPm }

    if nums.length >= 3 && isMonth(nums(0)) && isDay(nums(1)) then
      val year = if isYear(nums(2)) then nums(2) else expand2DigitYear(nums(2))
      val timeNums = nums.drop(3)
      Some(buildDateTimeWithAmPm(year, nums(0), nums(1), timeNums, ampm))
    else None

  private def parseDMY(tokens: List[Token]): Option[LocalDateTime] =
    val nums = tokens.collect { case Token.Num(v, _) => v }
    val ampm = tokens.collectFirst { case Token.AmPm(isPm) => isPm }
    
    if nums.length >= 3 && isDay(nums(0)) && isMonth(nums(1)) then
      val year = if isYear(nums(2)) then nums(2) else expand2DigitYear(nums(2))
      val timeNums = nums.drop(3)
      Some(buildDateTimeWithAmPm(year, nums(1), nums(0), timeNums, ampm))
    else None

  private def parseMonthDayYear(tokens: List[Token]): Option[LocalDateTime] = {
    val words = tokens.collect { case Token.Word(w) => w }
    val nums  = tokens.collect { case Token.Num(v, _) => v }
    val ampm  = tokens.collectFirst { case Token.AmPm(isPm) => isPm }

    if words.nonEmpty && isMonthWord(words.head) && nums.length >= 2 && isDay(nums(0)) then
      // Find first 4-digit year, else use second num if it's 2-digit
      val yearValue = nums.drop(1).find(isYear)
        .orElse(if nums.length >= 2 && isTwoDigitYear(nums(1)) then Some(expand2DigitYear(nums(1))) else None)
      
      yearValue.map { year =>
        val month = monthFromWord(words.head)
        val day = nums(0)
        // Find where the year is in the nums array
        val yearIdx = nums.indexWhere(n => n == year || (isTwoDigitYear(n) && expand2DigitYear(n) == year))
        // Time components are between day (index 0) and year (yearIdx)
        // Format: MonthWord Day [Hour Min Sec] Year
        val timeNums = if yearIdx > 1 then nums.slice(1, yearIdx) else Nil
        buildDateTimeWithAmPm(year, month, day, timeNums, ampm)
      }
    else None
  }

  private def parseDayMonthYear(tokens: List[Token]): Option[LocalDateTime] = {
    val words = tokens.collect { case Token.Word(w) => w }
    val nums  = tokens.collect { case Token.Num(v, _) => v }
    val ampm  = tokens.collectFirst { case Token.AmPm(isPm) => isPm }

    if nums.nonEmpty && isDay(nums(0)) && words.nonEmpty && isMonthWord(words.head) then
      // Find first 4-digit year, else use second num if it's 2-digit
      val yearValue = nums.drop(1).find(isYear)
        .orElse(if nums.length >= 2 && isTwoDigitYear(nums(1)) then Some(expand2DigitYear(nums(1))) else None)
      
      yearValue.map { year =>
        val month = monthFromWord(words.head)
        val day = nums(0)
        
        // Find where the year is in the nums array
        val yearIdx = nums.indexWhere(n => n == year || (isTwoDigitYear(n) && expand2DigitYear(n) == year))
        
        // Time components are between day (index 0) and year (yearIdx)
        // Format: Day MonthWord [Hour Min Sec] Year
        val timeNums = if yearIdx > 1 then nums.slice(1, yearIdx) else Nil
        
        buildDateTimeWithAmPm(year, month, day, timeNums, ampm)
      }
    else None
  }

  private def parseMDYWithTime(tokens: List[Token]): Option[LocalDateTime] = {
    val nums = tokens.collect { case Token.Num(v, _) => v }
    val ampm = tokens.collectFirst { case Token.AmPm(isPm) => isPm }
    
    // Format: MM/DD HH:MM:SS YYYY (6 nums) or MM/DD HH:MM YYYY (5 nums)
    if nums.length >= 5 then
      val month = nums(0)
      val day = nums(1)
      val hour = nums(2)
      val minute = nums(3)
      
      // Determine seconds and year based on number count
      val (second, year) = if nums.length >= 6 then
        // 6+ numbers: MM/DD HH:MM:SS YYYY
        val sec = nums(4)
        val yr = if isYear(nums(5)) then nums(5) else expand2DigitYear(nums(5))
        (sec, yr)
      else // nums.length == 5
        // 5 numbers: MM/DD HH:MM YYYY (no seconds)
        val yr = if isYear(nums(4)) then nums(4) else expand2DigitYear(nums(4))
        (0, yr)
      
      if isMonth(month) && isDay(day) && hour >= 0 && hour <= 23 && 
         minute >= 0 && minute <= 59 && second >= 0 && second <= 59 then
        val adjustedHour = ampm match
          case Some(true) if hour < 12 => hour + 12  // PM
          case Some(false) if hour == 12 => 0        // 12 AM = midnight
          case _ => hour
        Some(LocalDateTime.of(year, month, day, adjustedHour, minute, second))
      else None
    else None
  }

  //  Time extraction with AM/PM support
//  private def buildDateTime(year: Int, month: Int, day: Int, rest: List[Int]): LocalDateTime =
//    val hour = rest.lift(0).getOrElse(0)
//    val min  = rest.lift(1).getOrElse(0)
//    val sec  = rest.lift(2).getOrElse(0)
//    LocalDateTime.of(year, month, day, hour, min, sec)

  private def buildDateTimeWithAmPm(year: Int, month: Int, day: Int, rest: List[Int], ampm: Option[Boolean]): LocalDateTime =
    val hour = rest.lift(0).getOrElse(0)
    val min  = rest.lift(1).getOrElse(0)
    val sec  = rest.lift(2).getOrElse(0)
    
    // Adjust hour for AM/PM
    val adjustedHour = ampm match
      case Some(true) if hour < 12 => hour + 12  // PM: add 12 unless already 12
      case Some(false) if hour == 12 => 0        // 12 AM = midnight
      case _ => hour                              // No AM/PM or already 24-hour

      if (year < 1900 || month < 1 || month > 12 || day < 1 || day > 31 || adjustedHour < 0 || adjustedHour > 23 || min < 0 || min > 59 || sec < 0 || sec > 59) {
        hook += 1
      }

    LocalDateTime.of(year, month, day, adjustedHour, min, sec)
}

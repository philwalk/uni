package uni.time

/**
 * Pattern-based date formatting over plain integer fields.
 *
 * A replacement for `DateTimeFormatter.ofPattern`, written against the field values
 * rather than a `java.time` object, so that:
 *
 *   - the date type can be decoupled from `java.time` without losing `toString(fmt)`;
 *   - the same implementation ports to Rust with no dependency. `std` has no date
 *     formatting at all, and the alternative was a translation layer from Java's
 *     patterns to strftime's `%` codes -- more work than this, and one more thing to
 *     get subtly wrong.
 *
 * Verified against `DateTimeFormatter.ofPattern` for every pattern `uni` uses, so
 * existing scripts see no change. See `DateFormatSuite`.
 *
 * # Supported pattern letters
 *
 * `y` year, `M` month, `d` day, `H` hour (0-23), `h` hour (1-12), `m` minute, `s` second, `S` fraction, `a` AM/PM, `E` day of week.
 *
 * Repeat count sets the width, as in Java: `M` is `5`, `MM` is `05`, `MMM` is `May`,
 * `MMMM` is `May` spelled out. Text inside single quotes is literal (`'T'`), `''` is a
 * literal quote, and any non-letter passes through untouched.
 *
 * # Deliberate differences from `DateTimeFormatter`
 *
 * Month and weekday names are always English. `ofPattern` uses the JVM's default
 * FORMAT locale, so `MMM` yields `mai` on a French machine -- machine-dependent output
 * from a library used to write data files. None of `uni`'s own patterns use `MMM` or
 * `EEE`, so nothing in the repo changes.
 *
 * An unsupported letter is rejected rather than guessed at, matching Java, which
 * reserves all ASCII letters in a pattern.
 *
 * Capital `Y` -- the week-based year -- is rejected outright, and that is a divergence
 * on purpose. `ofPattern` accepts it and quietly renders a *different* year near New
 * Year: on 2024-12-30, `YYYY-MM-dd` gives `2025-12-30`. It is worse than it looks,
 * because Java's `Y` follows the *locale's* week definition rather than ISO's -- under
 * `Locale.ENGLISH` weeks start on Sunday, so the answer also depends on where the JVM
 * thinks it is. Every occurrence found in the wild was a typo for `y`, so the letter
 * errors with that advice instead of being implemented or silently aliased.
 */
object DateFormat:

  private val monthNames = Vector(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December")

  /** Sunday-first, matching `java.time`'s `EEE` output for `DayOfWeek`. */
  private val dayNames = Vector(
    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

  /** Day of week for a proleptic Gregorian date: 1 = Monday .. 7 = Sunday.
   *
   *  Sakamoto's method. Needed here for `E`, and by the file-timestamp methods for
   *  `weekDay`, so it lives with the calendar arithmetic rather than being derived from
   *  a `java.time` object.
   */
  def dayOfWeek(year: Int, month: Int, day: Int): Int =
    val t = Vector(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
    val y = if month < 3 then year - 1 else year
    val dow = (y + y / 4 - y / 100 + y / 400 + t(month - 1) + day) % 7
    // Sakamoto yields 0 = Sunday; shift so Monday is 1, as `DayOfWeek` numbers it.
    if dow == 0 then 7 else dow

  private def pad(n: Int, width: Int): String =
    val s = n.abs.toString
    val body = if s.length >= width then s else "0" * (width - s.length) + s
    if n < 0 then s"-$body" else body

  /** Formats the fields according to `fmt`.
   *
   *  @throws IllegalArgumentException on an unsupported pattern letter or an unclosed
   *          quote -- a malformed *pattern* is a programming error, unlike a malformed
   *          date, which is data and yields `BadDate`.
   */
  def format(
    year: Int, month: Int, day: Int,
    hour: Int, minute: Int, second: Int, nano: Int, fmt: String
  ): String =
    val out = new StringBuilder(fmt.length + 8)
    var i = 0
    while i < fmt.length do
      val c = fmt.charAt(i)
      if c == '\'' then
        // '' is a literal quote; otherwise everything to the next quote is literal.
        if i + 1 < fmt.length && fmt.charAt(i + 1) == '\'' then
          out.append('\'')
          i += 2
        else
          val close = fmt.indexOf('\'', i + 1)
          require(close >= 0, s"unclosed quote in pattern [$fmt]")
          out.append(fmt.substring(i + 1, close))
          i = close + 1
      else if c.isLetter then
        var n = 0
        while i + n < fmt.length && fmt.charAt(i + n) == c do n += 1
        out.append(field(c, n, year, month, day, hour, minute, second, nano, fmt))
        i += n
      else
        out.append(c)
        i += 1
    out.result()

  private def field(
    c: Char, n: Int,
    year: Int, month: Int, day: Int,
    hour: Int, minute: Int, second: Int, nano: Int, fmt: String
  ): String =
    c match
      // Two `y`s mean the last two digits; anything else is the year padded to width.
      case 'y' => if n == 2 then pad(year.abs % 100, 2) else pad(year, n)
      // Capital Y is the *week-based* year, which is almost never what anyone means.
      // Rejected with the fix named, rather than either implemented or silently
      // treated as `y` -- see the note on deliberate differences above.
      case 'Y' =>
        throw new IllegalArgumentException(
          s"pattern letter [Y] in [$fmt] is the week-based year, not the calendar " +
          "year; use lowercase [y]. It differs only near New Year, so a typo here " +
          "renders a date a year out for a few days each December.")
      case 'M' =>
        if n >= 4 then monthNames(month - 1)
        else if n == 3 then monthNames(month - 1).take(3)
        else pad(month, n)
      case 'd' => pad(day, n)
      case 'H' => pad(hour, n)
      case 'h' =>
        // 12-hour clock: midnight and noon are both 12, not 0.
        val h = hour % 12
        pad(if h == 0 then 12 else h, n)
      case 'm' => pad(minute, n)
      case 's' => pad(second, n)
      // Fraction of a second, truncated to the requested width rather than rounded,
      // as `DateTimeFormatter` does.
      case 'S' => pad(nano / math.pow(10, 9 - n).toInt, n)
      case 'a' => if hour < 12 then "AM" else "PM"
      case 'E' =>
        val name = dayNames(dayOfWeek(year, month, day) - 1)
        if n >= 4 then name else name.take(3)
      case other =>
        throw new IllegalArgumentException(
          s"unsupported pattern letter [$other] in [$fmt]")

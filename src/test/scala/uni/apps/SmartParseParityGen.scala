package uni.apps

import uni.*
import uni.time.*

/**
 * Regenerates the cross-language parity fixture in `test-data/smartparse-parity/` for
 * `uni.time.SmartParse` and its Rust port (`rust/src/utime/smartparse.rs`).
 *
 * Consumed by two tests that must agree with each other:
 *   - uni.time.SmartParseParitySuite      (Scala, src/test)
 *   - rust/tests/smartparse_parity.rs     (Rust)
 *
 * # Why this fixture exists
 *
 * The parser is 600 lines of tokenising, classification and field selection in each
 * language, with documented traps at every stage: the year-index bug family, the trailing
 * `-0700` that tokenises as a number, the RFC 2822 layout where the year comes last, the
 * all-or-nothing compact-date rewrite. The Rust port reproduces the *behaviour*, warts
 * included (the dead ISO arm, the 2-digit-year split at 30), and only recorded answers can
 * hold it to that.
 *
 * # What is deliberately absent
 *
 * Fuzzy month typos that sit within edit distance 1 of TWO months (`jum`): the Scala
 * answers those by `Map` iteration order, which is unspecified, so pinning one would pin
 * an accident. Only unambiguous typos are recorded.
 *
 * Nothing here is platform-dependent -- the parser consults no clock, locale or
 * filesystem -- so one reference serves every host.
 *
 * Run ONLY when the reference is meant to move: regenerating rewrites the very values the
 * tests check. Review the diff before keeping it.
 *
 * Lives in the test tree, unlike the other generators, because its corpus is
 * `uni.time.TestDates` -- test code.
 *
 * Run:  sbt "Test/runMain uni.apps.SmartParseParityGen"
 */
object SmartParseParityGen:
  def println(s: String = ""): Unit = print(s"$s\n")

  /** Inputs beyond the shared corpus, one for each documented trap or format. */
  val extras: Seq[String] = Seq(
    // RFC 2822 and unix `date` output: weekday prefix, year last, time in the middle
    "Sun, 12 May 2024 14:30:00 GMT",
    "Thu Aug 18 22:29:47 2018",
    "Aug 18 22:29:47 2018",
    "Aug 1 22:29:47 2029",
    "01 Jan 2001 12:34:56 -0700",
    // AM/PM, including both midnight-noon edge cases
    "May 12, 2024 2:30 PM",
    "May 12, 2024 12:15 AM",
    "12/31/1999 11:59 PM",
    "1/1/2000 12:00 AM",
    // compact dates: alone, with time, with seconds, with zone, and the refusals
    "20240512",
    "20240512 1430",
    "20240512 143015",
    "20240512 1430 MST",
    "20241301",       // month 13: not this format
    "20240512 2560",  // valid date, impossible time: all-or-nothing refuses both
    "202405121",      // nine digits are not a compact date
    // leading time rotates to the end
    "13:45 2020-01-02",
    "9:05 12/31/1999",
    // parenthesised timezones vanish
    "2020-01-02 14:30 (MST)",
    "12 May 2024 14:30 (UTC+7)",
    // ISO raw shapes: classify says ISO8601, the parse travels the token path
    "2011-12-03T10:15:30",
    "2024-05-12T14:30",
    "2024-05-12T14:30:00.123456789",
    "2024-05-12T14:30:00Z",
    "2024-05-12T14:30:00+02:00",
    "2024-05-12T14:30:00-07:00", // three dashes: not ISO-raw, token path
    // 2-digit years split at 30
    "1/2/30",
    "1/2/31",
    "1/2/00",
    // month-word matching: exact, prefix >= 3, unambiguous distance-1 typos
    "Sept 4, 2019",
    "Janu 1, 2001",
    "Janury 5, 2020",
    "Febuary 28, 2021",
    "Septmber 9, 2019",
    // weekday variants
    "Tuesday, May 12, 2024",
    "tue 05/12/2024",
    // junk that must refuse
    "",
    "hello world",
    "12345",
    "999",
    "12/34/5678",
    "0/0/0000",
    "February 30, 2021", // classified fine, then the calendar refuses
    "2024-02-30",
  )

  /** The ambiguous / order-sensitive set, recorded under all four configurations. */
  val orderSensitive: Seq[String] = Seq(
    "04/05/2001",  // both readings valid
    "5/6/07",      // both readings valid, 2-digit year
    "13/05/2001",  // day-first only
    "05/13/2001",  // month-first only
    "2001-04-05",  // ISO: order-independent
  )

  private def render(d: UniDateTime): String =
    if d == UniDateTime.BadDate then "!bad"
    else if d == UniDateTime.EmptyDate then "!empty"
    else s"${d.year},${d.month},${d.day},${d.hour},${d.minute},${d.second},${d.nano}"

  /** The four configurations, by the name the fixture uses. */
  private def parseUnder(mode: String, s: String): UniDateTime = mode match
    case "Auto"        => withMDY(parseDateSmart(s))
    case "AutoDayPref" => withDMY(parseDateSmart(s))
    case "MonthFirst"  => withDateOrder(DateOrder.MonthFirst)(parseDateSmart(s))
    case "DayFirst"    => withDateOrder(DateOrder.DayFirst)(parseDateSmart(s))
    case other         => sys.error(s"unknown mode $other")

  def main(args: Array[String]): Unit =
    val corpus = (uni.time.TestDates.all ++ extras).distinct
    require(corpus.forall(s => !s.contains('\t')), "fixture rows are tab-separated")

    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    for s <- corpus do
      out += s"parse\tAuto\t$s\t${render(parseUnder("Auto", s))}"
      out += s"classify\t$s\t${SmartParse.classify(s)}"
      val order = SmartParse.numericDateOrder(s).map(_.toString).getOrElse("!none")
      out += s"order\t$s\t$order"
    for s <- orderSensitive; mode <- Seq("Auto", "AutoDayPref", "MonthFirst", "DayFirst") do
      out += s"parse\t$mode\t$s\t${render(parseUnder(mode, s))}"

    val header = Seq(
      "# Cross-language SmartParse parity reference.",
      "# Generated by uni.apps.SmartParseParityGen. Do not hand-edit.",
      "# parse<TAB>mode<TAB>input<TAB>y,m,d,h,mi,s,nano | !bad | !empty",
      "#   modes: Auto = (monthFirst=true, Auto); AutoDayPref = (monthFirst=false, Auto);",
      "#          MonthFirst / DayFirst = enforced order.",
      "# classify<TAB>input<TAB>Shape   (under the default configuration)",
      "# order<TAB>input<TAB>MonthFirst | DayFirst | !none   (configuration-independent)",
      s"# rows: ${out.length}",
    )
    val file = Paths.get("test-data/smartparse-parity/scala-reference.txt")
    java.nio.file.Files.createDirectories(file.getParent)
    file.writeLines(header ++ out.toSeq)
    println(s"wrote ${out.length} rows -> ${file.posx}")
    FixtureGuard.warnIfIgnored(file.getParent)

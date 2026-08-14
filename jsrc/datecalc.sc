#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
//> using dep org.vastblue:uni_3:0.16.1

// One half of the cross-language demo pair; `rust/examples/datecalc.rs` is the other.
// Both run the same date calculations on FIXED inputs, so the outputs are
// byte-identical on every machine and every day — a portable end-to-end parity
// check of the utime surface:
//
//   scala-cli run jsrc/datecalc.sc > scala.out
//   cargo build --manifest-path rust/Cargo.toml --example datecalc
//   rust/target/debug/examples/datecalc > rust.out
//   diff scala.out rust.out
//
// Exercised, both sides: parseDateSmart (and its month-first/day-first
// configuration), quikDate/quikDateTime, pattern formatting, field accessors,
// epoch-day round trips, calendar helpers (daysInMonth, endOfMonth, month-end
// clamping), the plus/minus shift family, the with* setters, the between/duration
// family, getMillis/epoch2DateTime, the BadDate sentinel absorbing arithmetic,
// and monthAbbrev2Number. Fractional results print through Big + numStr, whose
// rendering is parity-pinned, so no platform float-formatting can drift.
object Datecalc {
  def println(s: String = ""): Unit = print(s"$s\n")

  import uni.*
  import uni.data.*
  import uni.time.*

  def num(d: Double): String = numStr(Big(d), NumFormat(colWidth = 1, dec = 4)).trim

  def main(args: Array[String]): Unit = {
    val d1 = parseDateSmart("2024-05-12 14:30:45")
    val d2 = parseDateSmart("2026-08-11")

    println(s"d1 parsed: $d1 (valid: ${d1.isValid})")
    println(s"d2 parsed: $d2 (valid: ${d2.isValid})")
    println(s"render: ymd ${d1.ymd}   ymdhms ${d1.ymdhms}")
    println(s"fmt(EEEE, dd MMM yyyy): ${d1.fmt("EEEE, dd MMM yyyy")}")
    println(s"fields: year ${d1.year} month ${d1.month} day ${d1.day} " +
      s"dow ${d1.dayOfWeekName}/${d1.dayOfWeekFull} epochDay ${d1.toEpochDay}")
    println(s"round trip: ofEpochDay(${d1.toEpochDay}) -> ${UniDateTime.ofEpochDay(d1.toEpochDay).ymd}")
    println(s"calendar: daysInMonth(2024, 2) ${UniDateTime.daysInMonth(2024, 2)}   " +
      s"endOfMonth ${TimeUtils.endOfMonth(d1).ymd}")
    println()

    println("shifts of d1:")
    println(s"  plusDays 30     -> ${d1.plusDays(30).ymdhms}")
    println(s"  minusMonths 6   -> ${d1.minusMonths(6).ymdhms}")
    println(s"  plusYears 1     -> ${d1.plusYears(1).ymdhms}")
    println(s"  plusHours 36    -> ${d1.plusHours(36).ymdhms}")
    println(s"  minusSeconds 46 -> ${d1.minusSeconds(46).ymdhms}")
    println(s"  clamp: 2024-01-31 plusMonths 1 -> ${parseDateSmart("2024-01-31").plusMonths(1).ymd}")
    println()

    println("with* setters on d1:")
    println(s"  withYear 2000    -> ${d1.withYear(2000).ymdhms}")
    println(s"  withMonth 2      -> ${d1.withMonth(2).ymdhms}")
    println(s"  withDayOfMonth 1 -> ${d1.withDayOfMonth(1).ymdhms}")
    println()

    println("between d1 and d2:")
    println(s"  daysBetween    ${daysBetween(d1, d2)}")
    println(s"  hoursBetween   ${num(hoursBetween(d1, d2))}")
    println(s"  secondsBetween ${secondsBetween(d1, d2)}")
    println(s"  daysRounded    ${num(daysRounded(d1, d2))}")
    println(s"  elapsedDays    ${elapsedDays(d1, d2)}")
    val (dd, hh, mm, ss) = getDuration(d1, d2)
    println(s"  getDuration    ${dd}d ${hh}h ${mm}m ${ss}s")
    println()

    println("epoch:")
    println(s"  getMillis(d1)  ${d1.getMillis()}")
    println(s"  epoch2DateTime(${d1.getMillis()}) -> ${TimeUtils.epoch2DateTime(d1.getMillis()).ymdhms}")
    println()

    println("quik parsers:")
    println(s"  quikDate(20240512)              -> ${quikDate("20240512").ymd}")
    println(s"  quikDateTime(2024-05-12 14:30)  -> ${quikDateTime("2024-05-12 14:30").ymdhms}")
    println()

    println("smart parsing:")
    for s <- Seq("May 12, 2024", "12-May-2024", "2024.05.12", "5/12/2024") do
      println(s"  ${s.padTo(14, ' ')} -> ${parseDateSmart(s).ymd}")
    println(s"  3/4/2024 month-first -> ${parseDateSmart("3/4/2024").ymd}")
    println(s"  3/4/2024 day-first   -> ${withTimeConfig(TimeConfig(monthFirst = false))(parseDateSmart("3/4/2024")).ymd}")
    println()

    println("sentinels:")
    val bad = parseDateSmart("total gibberish")
    println(s"  parseDateSmart(total gibberish) valid: ${bad.isValid}")
    println(s"  BadDate.plusDays(5) still invalid: ${!bad.plusDays(5).isValid}")
    println(s"  monthAbbrev2Number(sep) -> ${monthAbbrev2Number("sep")}")
  }
}
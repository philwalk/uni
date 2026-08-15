//! One half of the cross-language demo pair; `jsrc/datecalc.sc` is the other.
//! Both run the same date calculations on FIXED inputs, so the outputs are
//! byte-identical on every machine and every day — a portable end-to-end parity
//! check of the utime surface. See the Scala twin for the run recipe and the
//! feature list.

#![allow(
    non_snake_case,
    reason = "mirrors the Scala twin line for line; the shared API is camelCase by design"
)]
#![allow(
    clippy::print_stdout,
    clippy::unwrap_used,
    clippy::expect_used,
    reason = "a demo prints its report and dies loudly"
)]

use uni::udata::Big;
use uni::udata::NumFormat;
use uni::udata::numStr;
use uni::upath::epoch2DateTime;
use uni::utime::UniDateTime;
use uni::utime::daysBetween;
use uni::utime::daysRounded;
use uni::utime::elapsedDays;
use uni::utime::endOfMonth;
use uni::utime::getDuration;
use uni::utime::getMillis;
use uni::utime::hoursBetween;
use uni::utime::monthAbbrev2Number;
use uni::utime::parseDateSmart;
use uni::utime::quikDate;
use uni::utime::quikDateTime;
use uni::utime::secondsBetween;
use uni::utime::smartparse::DateOrder;
use uni::utime::smartparse::TimeConfig;
use uni::utime::smartparse::parseDateSmartWith;

fn num(d: f64) -> String {
    let fmt = NumFormat {
        colWidth: 1,
        dec: 4,
        factor: 1.0,
        abbreviate: false,
        suffix: String::new(),
    };
    numStr(&Big::from_f64(d), &fmt).trim().to_owned()
}

fn main() {
    let d1 = parseDateSmart("2024-05-12 14:30:45");
    let d2 = parseDateSmart("2026-08-11");

    println!("d1 parsed: {d1} (valid: {})", d1.isValid());
    println!("d2 parsed: {d2} (valid: {})", d2.isValid());
    println!("render: ymd {}   ymdhms {}", d1.ymd(), d1.ymdhms());
    println!("fmt(EEEE, dd MMM yyyy): {}", d1.fmt("EEEE, dd MMM yyyy"));
    println!(
        "fields: year {} month {} day {} dow {}/{} epochDay {}",
        d1.year(),
        d1.month(),
        d1.day(),
        d1.dayOfWeekName(),
        d1.dayOfWeekFull(),
        d1.toEpochDay()
    );
    println!(
        "round trip: ofEpochDay({}) -> {}",
        d1.toEpochDay(),
        UniDateTime::ofEpochDay(d1.toEpochDay()).ymd()
    );
    println!(
        "calendar: daysInMonth(2024, 2) {}   endOfMonth {}",
        UniDateTime::daysInMonth(2024, 2),
        endOfMonth(&d1).ymd()
    );
    println!();

    println!("shifts of d1:");
    println!("  plusDays 30     -> {}", d1.plusDays(30).ymdhms());
    println!("  minusMonths 6   -> {}", d1.minusMonths(6).ymdhms());
    println!("  plusYears 1     -> {}", d1.plusYears(1).ymdhms());
    println!("  plusHours 36    -> {}", d1.plusHours(36).ymdhms());
    println!("  minusSeconds 46 -> {}", d1.minusSeconds(46).ymdhms());
    println!(
        "  clamp: 2024-01-31 plusMonths 1 -> {}",
        parseDateSmart("2024-01-31").plusMonths(1).ymd()
    );
    println!();

    println!("with* setters on d1:");
    println!("  withYear 2000    -> {}", d1.withYear(2000).ymdhms());
    println!("  withMonth 2      -> {}", d1.withMonth(2).ymdhms());
    println!("  withDayOfMonth 1 -> {}", d1.withDayOfMonth(1).ymdhms());
    println!();

    println!("between d1 and d2:");
    println!("  daysBetween    {}", daysBetween(&d1, &d2));
    println!("  hoursBetween   {}", num(hoursBetween(&d1, &d2)));
    println!("  secondsBetween {}", secondsBetween(&d1, &d2));
    println!("  daysRounded    {}", num(daysRounded(&d1, &d2)));
    println!("  elapsedDays    {}", elapsedDays(&d1, &d2));
    let (dd, hh, mm, ss) = getDuration(&d1, &d2);
    println!("  getDuration    {dd}d {hh}h {mm}m {ss}s");
    println!();

    println!("epoch:");
    println!("  getMillis(d1)  {}", getMillis(&d1));
    println!(
        "  epoch2DateTime({}) -> {}",
        getMillis(&d1),
        epoch2DateTime(getMillis(&d1), 0).ymdhms()
    );
    println!();

    println!("quik parsers:");
    println!(
        "  quikDate(20240512)              -> {}",
        quikDate("20240512").ymd()
    );
    println!(
        "  quikDateTime(2024-05-12 14:30)  -> {}",
        quikDateTime("2024-05-12 14:30").ymdhms()
    );
    println!();

    println!("smart parsing:");
    for s in ["May 12, 2024", "12-May-2024", "2024.05.12", "5/12/2024"] {
        println!("  {s:<14} -> {}", parseDateSmart(s).ymd());
    }
    println!(
        "  3/4/2024 month-first -> {}",
        parseDateSmart("3/4/2024").ymd()
    );
    println!(
        "  3/4/2024 day-first   -> {}",
        parseDateSmartWith(
            "3/4/2024",
            TimeConfig {
                monthFirst: false,
                order: DateOrder::Auto
            }
        )
        .ymd()
    );
    println!();

    println!("sentinels:");
    let bad = parseDateSmart("total gibberish");
    println!("  parseDateSmart(total gibberish) valid: {}", bad.isValid());
    println!(
        "  BadDate.plusDays(5) still invalid: {}",
        !bad.plusDays(5).isValid()
    );
    println!("  monthAbbrev2Number(sep) -> {}", monthAbbrev2Number("sep"));
}

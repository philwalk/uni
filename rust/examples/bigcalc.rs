//! One half of the cross-language demo pair; `jsrc/bigcalc.sc` is the other.
//! Fixed inputs, so the outputs are byte-identical on every machine — a portable
//! end-to-end parity check of the udata surface, framed as the quasi-useful
//! thing Big exists for: exact decimal money math. See the Scala twin for the
//! run recipe and the feature list.

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
use uni::udata::big::RoundingMode;
use uni::udata::isBad;
use uni::udata::isNumeric;
use uni::udata::numStr;
use uni::udata::numStrPct;
use uni::udata::orBad;
use uni::udata::str2num;

fn ns(x: &Big) -> String {
    numStr(x, &NumFormat::default())
}

#[expect(
    clippy::too_many_lines,
    reason = "one linear report, mirroring the Scala twin's main statement for statement"
)]
fn main() {
    println!("parsing:");
    println!(
        "  Big(1234.5600)       -> {} (plain: {})",
        Big::parse("1234.5600").toString(),
        Big::parse("1234.5600").toPlainString()
    );
    println!(
        "  Big(1.23E+4)         -> {} (plain: {})",
        Big::parse("1.23E+4").toString(),
        Big::parse("1.23E+4").toPlainString()
    );
    for s in ["$1,234.56", "3.14", "12%", "1.5e3", "not-a-number"] {
        println!(
            "  str2num({s:<12}) -> {}   isNumeric: {}",
            str2num(s).toString(),
            isNumeric(s)
        );
    }
    println!();

    let a = Big::parse("12.34");
    let b = Big::parse("5.678");
    println!("arithmetic on a={} b={}:", a.toString(), b.toString());
    println!(
        "  a+b {}   a-b {}   a*b {}   a/b {}",
        (&a + &b).toString(),
        (&a - &b).toString(),
        (&a * &b).toString(),
        (&a / &b).toString()
    );
    println!(
        "  -a {}   abs(-a) {}   compare(a,b) {}   compare(b,a) {}",
        (-&a).toString(),
        (-&a).abs().toString(),
        a.compare(&b),
        b.compare(&a)
    );
    println!("  sqrt(2) {}", Big::from_i64(2).sqrt().toString());
    println!("  b pow 3 {}", b.pow(3).toString());
    println!("  2 powf 1.5 {}", Big::from_i64(2).powf(1.5).toString());
    println!();

    println!("rounding 2.345 to 2 decimals, every mode:");
    let x = Big::parse("2.345");
    for (name, mode) in [
        ("UP", RoundingMode::Up),
        ("DOWN", RoundingMode::Down),
        ("CEILING", RoundingMode::Ceiling),
        ("FLOOR", RoundingMode::Floor),
        ("HALF_UP", RoundingMode::HalfUp),
        ("HALF_DOWN", RoundingMode::HalfDown),
        ("HALF_EVEN", RoundingMode::HalfEven),
    ] {
        println!("  {name:<9} {}", x.setScale(2, mode).toString());
    }
    println!(
        "  round(3 sig, HALF_EVEN): {}",
        Big::parse("12345.678")
            .round(3, RoundingMode::HalfEven)
            .toString()
    );
    println!();

    println!("formatting:");
    println!("  numStr default:   [{}]", ns(&Big::parse("1234.5")));
    println!(
        "  numStr abbrev:    [{}]",
        numStr(&Big::parse("12345678901.5"), &NumFormat::Abbrev())
    );
    println!(
        "  numStr kUSD:      [{}]",
        numStr(
            &Big::parse("1234567"),
            &NumFormat {
                colWidth: 10,
                dec: 1,
                factor: 0.001,
                abbreviate: false,
                suffix: " kUSD".to_owned()
            }
        )
    );
    println!(
        "  numStrPct(0.1234) [{}]",
        numStrPct(&Big::parse("0.1234"), &NumFormat::Percent())
    );
    println!();

    println!("the BigNaN sentinel:");
    // sqrt of a negative is BigNaN in BOTH languages as of 0.16.0 -- Scala used to
    // throw here, which is the divergence this demo surfaced on its first run.
    let nan = Big::from_i64(-1).sqrt();
    println!(
        "  2 pow -2 (negative exponent): isBad {}",
        isBad(&Big::from_i64(2).pow(-2))
    );
    println!(
        "  sqrt(-1): isBad {}   renders as [{}]",
        isBad(&nan),
        nan.toString()
    );
    println!(
        "  nan + 5 stays bad: {}",
        isBad(&nan.add(&Big::from_i64(5)))
    );
    println!(
        "  orBad(None) is bad: {}   orBad(Some(a)): {}",
        isBad(&orBad(None)),
        orBad(Some(a.clone())).toString()
    );
    println!("  numStr(nan): [{}]", ns(&nan));
    println!();

    // the quasi-useful part: an exact-decimal invoice, no float in sight
    println!("invoice (exact decimal money math):");
    let items = [
        ("widget", Big::parse("19.99"), 3i64),
        ("gizmo", Big::parse("4.15"), 7),
        ("doohickey", Big::parse("102.50"), 1),
    ];
    let subtotal = items.iter().fold(Big::from_i64(0), |acc, it| {
        acc.add(&it.1.mul(&Big::from_i64(it.2)))
    });
    let taxRate = Big::parse("0.075");
    let tax = (&subtotal * &taxRate).setScale(2, RoundingMode::HalfEven);
    let total = &subtotal + &tax;
    for (name, price, qty) in &items {
        let line = price * Big::from_i64(*qty);
        println!(
            "  {name:<10} {qty} x {} ={}   ({} of subtotal)",
            ns(price),
            ns(&line),
            numStrPct(&(&line / &subtotal), &NumFormat::Percent())
        );
    }
    println!(
        "  subtotal {}   tax(7.5%) {}   total {}",
        ns(&subtotal),
        ns(&tax),
        ns(&total)
    );
}

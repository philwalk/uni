//! Checks the `SmartParse` port against `test-data/smartparse-parity/scala-reference.txt`,
//! the Scala implementation's own answers. Every `parse`, `classify` and `order` row must
//! reproduce exactly — including the `!bad` refusals, which pin *what does not parse* just
//! as hard as the successes pin what does.
//!
//! Regenerate the fixture with `sbt "Test/runMain uni.apps.SmartParseParityGen"`, only when
//! the change in answers is intended.

#![allow(
    clippy::expect_used,
    clippy::panic,
    reason = "a missing or malformed fixture should abort the test loudly"
)]

use t3prf::utime::UniDateTime;
use t3prf::utime::smartparse::{
    self, DateOrder, TimeConfig, classifyWith, numericDateOrder, parseDateSmartWith,
};

fn fixture_path() -> std::path::PathBuf {
    // CARGO_MANIFEST_DIR is rust/, the fixture lives beside it in the repo root.
    std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../test-data/smartparse-parity/scala-reference.txt")
}

fn config_for(mode: &str) -> TimeConfig {
    match mode {
        "Auto" => TimeConfig { monthFirst: true, order: DateOrder::Auto },
        "AutoDayPref" => TimeConfig { monthFirst: false, order: DateOrder::Auto },
        // `withDateOrder` derives `monthFirst` from the enforced order in Scala.
        "MonthFirst" => TimeConfig { monthFirst: true, order: DateOrder::MonthFirst },
        "DayFirst" => TimeConfig { monthFirst: false, order: DateOrder::DayFirst },
        other => panic!("unknown fixture mode {other}"),
    }
}

fn render(d: UniDateTime) -> String {
    if d == UniDateTime::BAD_DATE {
        "!bad".to_owned()
    } else if d == UniDateTime::EMPTY_DATE {
        "!empty".to_owned()
    } else {
        format!(
            "{},{},{},{},{},{},{}",
            d.year(),
            d.month(),
            d.day(),
            d.hour(),
            d.minute(),
            d.second(),
            d.nano()
        )
    }
}

#[test]
#[expect(clippy::too_many_lines, reason = "one row loop, one match; splitting the row
                                                   kinds apart would hide their symmetry")]
fn every_fixture_row_reproduces() {
    let text = std::fs::read_to_string(fixture_path())
        .expect("missing fixture; run SmartParseParityGen");
    let rows: Vec<&str> = text
        .lines()
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .collect();
    // A fixture that shrank is a fixture that stopped checking.
    assert!(rows.len() > 500, "suspiciously small fixture: {} rows", rows.len());

    let mut failures: Vec<String> = Vec::new();
    for row in rows {
        let parts: Vec<&str> = row.split('\t').collect();
        match parts.as_slice() {
            ["parse", mode, input, expected] => {
                let got = render(parseDateSmartWith(input, config_for(mode)));
                if got != *expected {
                    failures.push(format!("parse[{mode}] '{input}': got {got}, want {expected}"));
                }
            }
            // The empty-string input drops its column.
            ["parse", mode, expected] => {
                let got = render(parseDateSmartWith("", config_for(mode)));
                if got != *expected {
                    failures.push(format!("parse[{mode}] '': got {got}, want {expected}"));
                }
            }
            ["classify", input, expected] => {
                let got = classifyWith(input, TimeConfig::default()).name();
                if got != *expected {
                    failures.push(format!("classify '{input}': got {got}, want {expected}"));
                }
            }
            ["classify", expected] => {
                let got = classifyWith("", TimeConfig::default()).name();
                if got != *expected {
                    failures.push(format!("classify '': got {got}, want {expected}"));
                }
            }
            ["order", input, expected] => {
                let got = match numericDateOrder(input) {
                    Some(DateOrder::MonthFirst) => "MonthFirst",
                    Some(DateOrder::DayFirst) => "DayFirst",
                    Some(DateOrder::Auto) => "Auto",
                    None => "!none",
                };
                if got != *expected {
                    failures.push(format!("order '{input}': got {got}, want {expected}"));
                }
            }
            ["order", expected] => {
                let got = if numericDateOrder("").is_none() { "!none" } else { "some" };
                if got != *expected {
                    failures.push(format!("order '': got {got}, want {expected}"));
                }
            }
            _ => failures.push(format!("unparseable fixture row: {row}")),
        }
    }
    assert!(
        failures.is_empty(),
        "{} divergence(s):\n{}",
        failures.len(),
        failures.join("\n")
    );
}

#[test]
fn the_alias_and_default_forms_agree() {
    for s in ["2024-05-12", "May 12, 2024 2:30 PM", "garbage"] {
        assert_eq!(smartparse::parseDate(s), smartparse::parseDateSmart(s), "{s}");
        assert_eq!(
            smartparse::parseDateSmart(s),
            parseDateSmartWith(s, TimeConfig::default()),
            "{s}"
        );
    }
}

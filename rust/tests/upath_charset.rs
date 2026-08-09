//! Charset handling for the wider-than-one-byte encodings.
//!
//! Mirrors `uni.CharsetSuite` case for case. The point pinned here: `0x0A` is half of a code unit
//! in UTF-16, so a byte-level split before decoding cuts characters in half. Those charsets decode
//! the whole file and split the text, which costs laziness and is the only correct answer.

#![allow(
    clippy::expect_used,
    clippy::panic,
    clippy::unwrap_used,
    reason = "a fixture that cannot be written should abort the test loudly"
)]

use std::sync::Arc;

use t3prf::upath::PathContext;
use t3prf::upath::UPath;
use t3prf::upath::UserInfo;
use t3prf::upath::io::Charset;

fn with_bytes(dir: &std::path::Path, name: &str, bytes: &[u8]) -> UPath {
    let d = dir.to_string_lossy().replace('\\', "/");
    let ctx = Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", &d, &d),
        cfg!(windows),
    ));
    let p = UPath::resolve(&ctx, &format!("{d}/{name}")).unwrap_or_else(|e| panic!("resolve: {e}"));
    std::fs::write(p.as_std_path(), bytes).unwrap_or_else(|e| panic!("write: {e}"));
    p
}

/// "alpha\nbeta\n" as UTF-16, big- and little-endian.
fn be_bytes() -> Vec<u8> {
    "alpha\nbeta\n".encode_utf16().flat_map(u16::to_be_bytes).collect()
}
fn le_bytes() -> Vec<u8> {
    "alpha\nbeta\n".encode_utf16().flat_map(u16::to_le_bytes).collect()
}

#[test]
fn utf16_lines_decode_as_text_not_bytes() {
    let t = tempfile::tempdir().expect("tmp");
    let p = with_bytes(t.path(), "u16be.txt", &be_bytes());
    assert_eq!(p.try_lines_with(Charset::Utf16Be).unwrap_or_default(), ["alpha", "beta"], "lines");
    let streamed: Vec<String> = p.linesStream(Charset::Utf16Be).collect();
    assert_eq!(streamed, ["alpha", "beta"], "linesStream matches lines");
    assert_eq!(
        p.try_content_string(Charset::Utf16Be).unwrap_or_default(),
        "alpha\nbeta\n",
        "contentAsString"
    );
    // The regression: a NUL inside a line means the bytes were never decoded.
    assert!(
        !p.try_lines_with(Charset::Utf16Be).unwrap_or_default().iter().any(|l| l.contains('\u{0}')),
        "no raw bytes leaking through"
    );
}

#[test]
fn utf16_le_and_a_bom() {
    let t = tempfile::tempdir().expect("tmp");
    let le = with_bytes(t.path(), "u16le.txt", &le_bytes());
    assert_eq!(le.try_lines_with(Charset::Utf16Le).unwrap_or_default(), ["alpha", "beta"]);
    // `Charset::Utf16` takes the order from a BOM and consumes it.
    let mut bom = vec![0xFE, 0xFF];
    bom.extend(be_bytes());
    let p = with_bytes(t.path(), "u16bom.txt", &bom);
    assert_eq!(p.try_lines_with(Charset::Utf16).unwrap_or_default(), ["alpha", "beta"]);
    let mut lebom = vec![0xFF, 0xFE];
    lebom.extend(le_bytes());
    let q = with_bytes(t.path(), "u16lebom.txt", &lebom);
    assert_eq!(q.try_lines_with(Charset::Utf16).unwrap_or_default(), ["alpha", "beta"], "LE BOM");
    // No BOM means big-endian, which is what the JVM does.
    let r = with_bytes(t.path(), "u16nobom.txt", &be_bytes());
    assert_eq!(r.try_lines_with(Charset::Utf16).unwrap_or_default(), ["alpha", "beta"], "defaults to BE");
}

#[test]
fn round_trips_through_encode() {
    for cs in [Charset::Utf16, Charset::Utf16Be, Charset::Utf16Le] {
        let encoded = cs.encode("alpha\nbeta").expect("encode");
        let decoded = cs.decode(encoded).expect("decode");
        assert_eq!(decoded, "alpha\nbeta", "round trip through {cs:?}");
    }
}

#[test]
fn by_name_resolves_the_utf16_spellings() {
    assert_eq!(Charset::by_name("UTF-16").expect("utf-16"), Charset::Utf16);
    assert_eq!(Charset::by_name("utf16le").expect("le"), Charset::Utf16Le);
    assert_eq!(Charset::by_name("UTF_16BE").expect("be"), Charset::Utf16Be);
}

#[test]
fn a_real_but_unsupported_charset_is_refused_loudly() {
    // The former silent divergence: these decoded properly in Scala and silently mis-read as
    // UTF-8 here. A wrong answer with no symptom is worse than an error, so it is an error.
    for name in ["Shift_JIS", "EUC-JP", "GB18030", "Big5", "UTF-32", "ISO-8859-2",
                 "windows-1251", "KOI8-R", "IBM037", "cp850", "ISO-2022-JP", "MacRoman"] {
        let e = Charset::by_name(name).expect_err(name);
        assert_eq!(e.kind(), std::io::ErrorKind::Unsupported, "{name}");
    }
    // Unrecognised names still fall back to UTF-8, which IS the Scala behaviour there.
    assert_eq!(Charset::by_name("NoSuchCharset").expect("fallback"), Charset::Utf8);
    // And the supported page keeps working under all its spellings.
    assert_eq!(Charset::by_name("windows-1252").expect("1252"), Charset::Latin1);
    assert_eq!(Charset::by_name("cp1252").expect("cp1252"), Charset::Latin1);
}

#[test]
fn an_odd_byte_count_is_an_error_not_a_dropped_byte() {
    assert!(Charset::Utf16Be.decode(vec![0x00, 0x61, 0x00]).is_err());
    // And through the path API it reads as empty, matching contentAsString's catch in Scala.
    let t = tempfile::tempdir().expect("tmp");
    let p = with_bytes(t.path(), "odd.txt", &[0x00, 0x61, 0x00]);
    assert_eq!(p.try_content_string(Charset::Utf16Be).unwrap_or_default(), "");
    assert!(p.try_lines_with(Charset::Utf16Be).unwrap_or_default().is_empty());
}

#[test]
fn single_byte_charsets_still_stream() {
    // `newline_is_one_byte` is what selects the streaming path; these must keep it.
    assert!(Charset::Utf8.newline_is_one_byte());
    assert!(Charset::Latin1.newline_is_one_byte());
    assert!(Charset::Utf8ThenLatin1.newline_is_one_byte());
    assert!(!Charset::Utf16.newline_is_one_byte());
    assert!(!Charset::Utf16Be.newline_is_one_byte());
    assert!(!Charset::Utf16Le.newline_is_one_byte());
}

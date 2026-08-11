//! The BadPath family — total resolution for path strings the platform cannot
//! represent. Port of `uni.BadPath`; design: docs/PathProviderDesignNote.md.
//!
//! Rust's `PathBuf` parses any string, so unlike the JVM there is no parser
//! exception to absorb — but without this family an unrepresentable string
//! would resolve to a plausible-looking path (`"a:b:c"` becomes `A:/b:c` via
//! the drive-cwd rule) that can never exist, silently losing the original
//! input. Family membership keeps the two languages byte-identical: the same
//! marker, the same PUA-encoded payload, the same `badPathString` recovery.
//!
//! A family member's stored form is
//!
//! ```text
//! <X>:/__uni-BadPath__/<PUA-encoded original input>     (Windows hosts)
//!     /__uni-BadPath__/<PUA-encoded original input>     (elsewhere)
//! ```
//!
//! where `<X>` is chosen at runtime, per construction, from the drive letters
//! absent from the logical-drive bitmask — never assumed. A nonexistent drive
//! makes every create fail at the driver level; the never-created marker
//! directory is the backstop everywhere else.

pub(crate) const MARKER: &str = "__uni-BadPath__";

/// True when a filesystem following the given rule set would reject `s` — the
/// family's membership predicate. Windows rules: the rejected character set
/// plus a colon anywhere but the drive position. POSIX rules: NUL, the one
/// byte no POSIX filesystem accepts.
///
/// CONTEXT rules (`ctx.is_windows`), where Scala uses HOST rules — a deliberate
/// asymmetry. Scala's predicate guards the JVM's host-bound parser, which no
/// rule injection can reach (the TEST-HARNESS BOUNDARY note in Paths.scala);
/// Rust has no such parser — `PathBuf` accepts anything — so membership here is
/// pure modeling and can follow the context like every other resolution rule.
/// That is what lets the parity harness check Windows-block `badpath` rows from
/// any host. On the default context the two predicates agree: it is built from
/// the real platform.
pub(crate) fn is_unrepresentable(is_windows: bool, s: &str) -> bool {
    if is_windows {
        // Positions are char positions; they agree with Scala's UTF-16 charAt
        // verdicts everywhere, because a colon preceded by an astral char is
        // rejected under both indexings and an ASCII letter occupies one unit
        // in both.
        let mut prev: Option<char> = None;
        for (i, c) in s.chars().enumerate() {
            let bad = (c as u32) < 0x20
                || matches!(c, '"' | '*' | '<' | '>' | '?' | '|')
                || (c == ':' && !(i == 1 && prev.is_some_and(|p| p.is_ascii_alphabetic())));
            if bad {
                return true;
            }
            prev = Some(c);
        }
        false
    } else {
        s.contains('\0')
    }
}

/// The family member's stored posix form for unrepresentable `input` — the raw
/// string as the caller supplied it, never an absolutised or rewritten form.
pub(crate) fn bad_pathstr(input: &str) -> String {
    format!("{}/{MARKER}/{}", bad_root(), encode(input))
}

/// A root on a drive letter that does not exist right now — chosen fresh per
/// call from the `GetLogicalDrives` bitmask (hot-plug changes the free set, and
/// recognition never depends on the letter). Z down to C: A:/B: get legacy
/// floppy treatment. Mapped-but-disconnected network drives still occupy their
/// bit, so a complement letter is genuinely unmapped — raw probes fail fast
/// rather than hanging. Empty when every letter is in use (the marker-directory
/// invariant still guards) and on non-Windows hosts.
#[cfg(windows)]
fn bad_root() -> String {
    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetLogicalDrives() -> u32;
    }
    let taken = unsafe { GetLogicalDrives() };
    ('C'..='Z')
        .rev()
        .find(|&letter| taken & (1u32 << (letter as u32 - 'A' as u32)) == 0)
        .map_or_else(String::new, |letter| format!("{letter}:"))
}

#[cfg(not(windows))]
fn bad_root() -> String {
    String::new()
}

/// The PUA convention: cygwin/MSYS2's on-disk mapping of rejected characters to
/// their Unicode Private Use Area counterparts at `U+F000 + char`, plus one uni
/// extension — `/` maps to U+F02F so the entire payload is a single name
/// element and leading/doubled/trailing slashes survive the round trip.
/// Backslash is included: once a string is unrepresentable the whole of it is
/// opaque data, and encoding the backslash keeps the round trip byte-exact.
fn maps_to_pua(c: char) -> bool {
    (c as u32) < 0x20 || matches!(c, '"' | '*' | ':' | '<' | '>' | '?' | '|' | '/' | '\\')
}

const PUA_BASE: u32 = 0xF000;

pub(crate) fn encode(s: &str) -> String {
    s.chars()
        .map(|c| {
            if maps_to_pua(c) {
                // always a valid scalar: PUA_BASE + c < 0xF100 for every mapped char
                char::from_u32(PUA_BASE + c as u32).unwrap_or(c)
            } else {
                c
            }
        })
        .collect()
}

/// Exact inverse of [`encode`] over the escape alphabet. Known ambiguity,
/// accepted with cygwin precedent: input that already contained U+F0xx
/// characters decodes to their low-byte originals.
pub(crate) fn decode(s: &str) -> String {
    s.chars()
        .map(|c| {
            let u = c as u32;
            if (PUA_BASE..PUA_BASE + 0x100).contains(&u) {
                match char::from_u32(u - PUA_BASE) {
                    Some(low) if maps_to_pua(low) => low,
                    _ => c,
                }
            } else {
                c
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encode_decode_are_exact_inverses_over_the_alphabet() {
        let alphabet: String = "\"*:<>?|/\\"
            .chars()
            .chain((0u32..0x20).filter_map(char::from_u32))
            .collect();
        assert_eq!(decode(&encode(&alphabet)), alphabet);
        assert_eq!(encode("plain-name_1.txt"), "plain-name_1.txt");
    }

    #[test]
    fn nul_is_unrepresentable_under_both_rule_sets() {
        assert!(is_unrepresentable(true, "nul\0byte"));
        assert!(is_unrepresentable(false, "nul\0byte"));
    }

    // context rules, so both rule sets are testable from any host
    #[test]
    fn windows_membership_matches_the_scala_predicate() {
        for s in [
            "a:b:c",
            "1:foo",
            ":",
            "::",
            "a<b",
            "wild*card",
            "pi|pe",
            "what?",
            "tab\tchar",
            "C:/ok/until:here",
        ] {
            assert!(
                is_unrepresentable(true, s),
                "expected unrepresentable: [{s}]"
            );
            assert!(
                !is_unrepresentable(false, s),
                "posix rules must accept: [{s}]"
            );
        }
        for s in [
            "C:/Users",
            "c:",
            "q:pics",
            "plain/relative",
            "//server/share",
            "a\u{F03A}b",
        ] {
            assert!(
                !is_unrepresentable(true, s),
                "expected representable: [{s}]"
            );
        }
    }

    #[test]
    fn posix_renderings_decode_members_and_windows_renderings_stay_raw() {
        // The MSYS2 model: the posix world (ls, cygpath -u) shows decoded names,
        // the windows world (cygpath -m/-w) shows the on-disk PUA form. NARROW:
        // only family members decode -- a real PUA-named file keeps raw
        // renderings, so strings handed to Windows programs keep working.
        let c = ctx();
        let p = UPath::resolve(&c, "a:b:c").expect("total");
        assert!(
            p.posix().expect("posix").ends_with("/a:b:c"),
            "posix decodes"
        );
        assert!(p.stdpath().ends_with("/a:b:c"), "stdpath decodes");
        assert!(p.relpath().ends_with("/a:b:c"), "relpath decodes");
        assert!(p.posx().contains('\u{F03A}'), "posx stays raw");
        let real = UPath::resolve(&c, "dir/f\u{F03A}d").expect("ordinary");
        assert!(!real.isBadPath());
        let posix = real.posix().expect("posix");
        assert!(
            posix.contains('\u{F03A}') && !posix.contains(':'),
            "real PUA names stay raw in posix renderings: {posix}"
        );
    }

    // ── through UPath::resolve, mirroring the Scala BadPathSuite ────────────

    use std::sync::Arc;

    use crate::upath::PathContext;
    use crate::upath::UPath;
    use crate::upath::UserInfo;

    /// A Windows-rules context: membership follows the context, so the full
    /// input list is exercisable from any host.
    fn ctx() -> Arc<PathContext> {
        Arc::new(PathContext::synthetic(
            &[],
            UserInfo::new("tester", "C:/Persons/tester", "C:/munit/test"),
            true,
        ))
    }

    /// Inputs the Windows rule set rejects, matching the Scala suite; NUL is
    /// bad under both rule sets.
    fn host_bad() -> Vec<&'static str> {
        let mut v = vec!["nul\0byte"];
        {
            v.extend([
                "a:b:c",
                "1:foo",
                ":",
                "a<b",
                "wild*card",
                "quo\"te",
                "pi|pe",
                "what?",
                "tab\tchar",
                "C:/ok/until:here",
                "/lead<ing",
                "//doubled//slash<es//",
                "trail<ing/",
                "back\\slash<mix",
            ]);
        }
        v
    }

    #[test]
    fn resolve_is_total_and_round_trips_the_original() {
        let c = ctx();
        for s in host_bad() {
            let p = UPath::resolve(&c, s)
                .unwrap_or_else(|e| panic!("resolve must be total: [{s}]: {e}"));
            assert!(
                p.isBadPath(),
                "expected BadPath for [{s}], got [{}]",
                p.posx()
            );
            assert_eq!(p.badPathString(), s, "round trip failed for [{s}]");
        }
    }

    #[test]
    fn bad_paths_answer_false_to_predicates_and_mkdirs() {
        let c = ctx();
        for s in host_bad() {
            let p = UPath::resolve(&c, s).unwrap_or_else(|e| panic!("resolve: {e}"));
            assert!(!p.exists(), "exists must be false: [{s}]");
            assert!(!p.isFile(), "isFile must be false: [{s}]");
            assert!(!p.isDirectory(), "isDirectory must be false: [{s}]");
            assert!(!p.mkdirs(), "mkdirs must refuse: [{s}]");
        }
    }

    #[test]
    fn ordinary_paths_are_untouched() {
        let c = ctx();
        let p = UPath::resolve(&c, "/home/tester/x.txt").unwrap_or_else(|e| panic!("resolve: {e}"));
        assert!(!p.isBadPath());
        assert_eq!(p.badPathString(), p.posx());
        // legal PUA characters do not put a path in the family
        let q = UPath::resolve(&c, "a\u{F03A}b").unwrap_or_else(|e| panic!("resolve: {e}"));
        assert!(!q.isBadPath());
    }

    #[cfg(windows)]
    #[test]
    fn bad_root_is_a_drive_absent_at_runtime_or_empty() {
        let root = bad_root();
        if root.is_empty() {
            return; // every letter in use: legitimate fallback
        }
        // never assume any particular letter is free -- re-derive from the OS
        let letter = root.chars().next().unwrap_or('!');
        assert!(('C'..='Z').contains(&letter), "unexpected root [{root}]");
        assert!(
            !std::path::Path::new(&format!("{root}/")).exists(),
            "bad_root chose an existing drive: [{root}]"
        );
    }
}

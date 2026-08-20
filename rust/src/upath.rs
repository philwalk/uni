//! Mount-aware path resolution — a port of `uni.Paths` / `uni.PathsUtils`.
//!
//! The point is to let client code be written once and run on Windows, Linux and
//! macOS. `std::path` understands drive letters and UNC but knows nothing about
//! MSYS2/Cygwin mount tables, so a POSIX path like `/database-backups/x` cannot be
//! resolved to its Windows location without the mount map. That translation is
//! what this module provides, modelled on `uni.Paths.get`.
//!
//! # Everything is data, nothing is `cfg`-gated
//!
//! [`PathContext`] carries the mount table, the user, *and* an `is_windows` flag.
//! Rust's `cfg!(windows)` is compile-time, unlike the JVM's runtime `os.name`, so
//! gating the algorithm on it would make the Windows resolution rules
//! uncompilable — and therefore untestable — on Linux and macOS. Keeping them as
//! data means one fixture set exercises all three platforms' semantics from any
//! single machine, which is a property the Scala original does not have: its path
//! suites are `if isWin`-gated and skip entirely off Windows.
//!
//! Only [`PathContext::from_env`] consults the real environment.
//!
//! # Layout
//!
//! - [`mount`] — the mount table: parsing, and the two lookup directions.
//! - [`context`] — resolution inputs: mount maps, user, per-drive cwd.
//! - [`resolve`] — the algorithm: classify, then translate either direction.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name, so a script kept in both \n              languages needs no mental translation -- the same reason windows-rs spells \n              Win32 functions SetEvent rather than set_event. Internal helpers and Rust \n              trait contracts stay snake_case, so the case says whether a Scala \n              counterpart exists."
)]

pub(crate) mod badpath;
pub mod context;
pub mod csv;
pub mod delim;
pub mod ext;
pub mod hash;
pub mod io;
pub mod matcsv;
pub mod matresult;
pub mod meta;
pub mod mount;
pub mod mutate;
pub mod resolve;
pub mod strext;
pub mod times;
pub mod walk;

pub use context::DriveCwdSource;
pub use context::PathContext;
pub use context::UserInfo;
pub use csv::CsvConfig;
pub use csv::CsvSchema;
pub use csv::CsvWriter;
pub use csv::RowParser;
pub use delim::DelimState;
pub use ext::UPath;
pub use hash::Cksum;
pub use hash::CksumResult;
pub use hash::Hash64;
pub use hash::Md5;
pub use hash::Sha256;
pub use io::Charset;
pub use matcsv::CsvCell;
pub use matcsv::CsvTable;
pub use matresult::AggOp;
pub use matresult::JoinType;
pub use mount::MountMaps;
pub use resolve::PathKind;
pub use strext::StrExts;
pub use strext::StrPathExts;
pub use times::Ago;
pub use times::agoFromMillis;
pub use times::epoch2DateTime;
pub use walk::TreeWalk;

/// Why a path could not be resolved.
///
/// `#[non_exhaustive]`: resolution grows new rejection cases (UNC hosts, verbatim
/// `\\?\` prefixes) and adding a variant should not break a caller's `match`.
#[non_exhaustive]
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum PathError {
    /// A URI-like string with a scheme, e.g. `file:///c/tmp`. `uni` classifies
    /// these `Invalid` rather than guessing.
    #[error("invalid path type, looks like a URI: {0}")]
    UriLike(String),

    /// Backslashes must be normalised before resolution; the Scala side asserts
    /// this with `require(!raw.contains('\\'))`.
    #[error("path must not contain backslashes at this stage: {0}")]
    Unnormalized(String),

    /// A drive letter outside `A-Za-z`.
    #[error("not a valid drive letter: {0}")]
    BadDriveLetter(char),

    /// A root-only path has no final name element. Java's `getFileName` returns
    /// null here and the Scala throws; this reports it instead.
    #[error("path has no file name: {0}")]
    NoFileName(String),

    /// An I/O failure behind a `str`-level convenience method. Those resolve a path
    /// and then read or write it, so one error type has to span both halves.
    ///
    /// The kind and message are carried rather than the `std::io::Error` itself:
    /// `io::Error` is neither `Clone` nor `Eq`, and this enum is both — which tests
    /// rely on to compare errors directly.
    #[error("io error ({kind:?}): {message}")]
    Io {
        /// The underlying [`std::io::ErrorKind`].
        kind: std::io::ErrorKind,
        /// The error's `Display` text.
        message: String,
    },

    /// The cygdrive fallback was reached with a path that has no drive letter —
    /// typically a relative path containing a slash, which resolution leaves
    /// untouched and which therefore has no absolute POSIX form. `uni` fails the
    /// same way, via `require(cygMixed(1) == ':')` in `winAbsToPosixAbs`.
    #[error("not a Windows absolute path: {0}")]
    NotDriveQualified(String),
}

impl PathError {
    /// Wraps an [`std::io::Error`], keeping its kind and message.
    #[must_use]
    pub fn from_io(e: &std::io::Error) -> Self {
        Self::Io {
            kind: e.kind(),
            message: e.to_string(),
        }
    }
}

/// Map whose lookups ignore ASCII case, mirroring `uni.LcLookupMap`.
///
/// Windows paths are case-insensitive, so both mount directions must be. Keys are
/// stored already-lowercased and every lookup lowercases too; skipping either half
/// makes reverse lookups silently miss (the real fstab has `c:/users` mapped from
/// `/Users`).
#[derive(Debug, Clone, Default)]
pub struct LcMap<V> {
    inner: std::collections::HashMap<String, V>,
}

impl<V> LcMap<V> {
    pub(crate) fn from_pairs(pairs: impl IntoIterator<Item = (String, V)>) -> Self {
        Self {
            inner: pairs
                .into_iter()
                .map(|(k, v)| (k.to_lowercase(), v))
                .collect(),
        }
    }

    #[must_use]
    pub fn get(&self, key: &str) -> Option<&V> {
        self.inner.get(&key.to_lowercase())
    }

    #[must_use]
    pub fn contains(&self, key: &str) -> bool {
        self.inner.contains_key(&key.to_lowercase())
    }

    /// Keys sorted longest-first, matching `PathsConfig.keysArray`.
    ///
    /// `find_prefix` scans every key and keeps the longest match, so the order is
    /// an optimisation rather than a semantic — but it keeps iteration
    /// deterministic, which a `HashMap` otherwise would not.
    #[must_use]
    pub fn keys_longest_first(&self) -> Vec<String> {
        let mut keys: Vec<String> = self.inner.keys().cloned().collect();
        keys.sort_by(|a, b| b.len().cmp(&a.len()).then_with(|| a.cmp(b)));
        keys
    }

    #[must_use]
    pub fn len(&self) -> usize {
        self.inner.len()
    }

    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.inner.is_empty()
    }
}

// ── Shared string helpers, ported verbatim from PathsUtils ───────────────────

/// `Resolver.stripTrailingSlash`: leaves anything two chars or shorter alone, so
/// `/` and `C:` survive.
#[must_use]
pub(crate) fn strip_trailing_slash(s: &str) -> String {
    if s.len() <= 2 {
        s.to_owned()
    } else {
        s.strip_suffix('/').unwrap_or(s).to_owned()
    }
}

/// `PathsUtils.noTrailingSlash`: removes trailing separators, keeping `/` and a bare
/// drive root intact.
///
/// All of them, not one: this used to strip a single `/`, so `/usr/bin//` came back as
/// `/usr/bin/` — still trailing, from a function whose name says otherwise.
#[must_use]
pub(crate) fn no_trailing_slash(p: &str) -> String {
    if p == "/" {
        return "/".to_owned();
    }
    let bare = p.trim_end_matches('/');
    if bare.is_empty() {
        // All separators (`//`, `///`) reduces to the root; an *empty* input stays
        // empty. Conflating them made `"".local()` resolve to the msys root.
        return if p.is_empty() {
            String::new()
        } else {
            "/".to_owned()
        };
    }
    // `C:/` is the drive root and keeps its separator; bare `C:` is drive-relative
    // and must not gain one. Preserve, never add — an earlier attempt at this turned
    // `"C:"` into `"C:/"`, changing what the string means.
    if bare.len() == 2 && bare.as_bytes()[1] == b':' && p.len() > 2 {
        return format!("{bare}/");
    }
    bare.to_owned()
}

/// `PathsUtils.joinPosix`: exactly one slash between the parts, no trailing slash.
#[must_use]
pub(crate) fn join_posix(prefix: &str, suffix: &str) -> String {
    let pre = prefix.strip_suffix('/').unwrap_or(prefix);
    let post = suffix.strip_prefix('/').unwrap_or(suffix);
    no_trailing_slash(&format!("{pre}/{post}"))
}

/// `PathsUtils.normalizePosix`: backslashes to forward, then [`no_trailing_slash`].
///
/// A separator swap and nothing more — interior runs are left alone, deliberately.
/// `posx` is defined as that swap, not as path normalisation. `Paths.get` is what
/// collapses duplicate separators, so code wanting that should go through a path.
#[must_use]
pub(crate) fn normalize_posix(p: &str) -> String {
    let s = p.replace('\\', "/");
    if s == "/" { s } else { no_trailing_slash(&s) }
}

/// Case-insensitive `starts_with`.
///
/// Since 0.16.0 the *deciding* comparisons (`posix_rel`'s cwd test, `relativize`)
/// consult `PathContext::case_fold` and call this only where the context folds —
/// unconditional folding relativised `/home/Phil/x` against `/home/phil` on Linux.
/// The remaining unconditional caller is the explicitly-named
/// `StrExts::startsWithIgnoreCase`, whose insensitivity is its contract.
#[must_use]
pub(crate) fn startsWithIgnoreCase(s: &str, prefix: &str) -> bool {
    s.len() >= prefix.len() && s[..prefix.len()].eq_ignore_ascii_case(prefix)
}

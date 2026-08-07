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

pub mod context;
pub mod ext;
pub mod io;
pub mod mount;
pub mod resolve;

pub use context::DriveCwdSource;
pub use context::PathContext;
pub use context::UserInfo;
pub use ext::UPath;
pub use io::Charset;
pub use mount::MountMaps;
pub use resolve::PathKind;

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

    /// The cygdrive fallback was reached with a path that has no drive letter —
    /// typically a relative path containing a slash, which resolution leaves
    /// untouched and which therefore has no absolute POSIX form. `uni` fails the
    /// same way, via `require(cygMixed(1) == ':')` in `winAbsToPosixAbs`.
    #[error("not a Windows absolute path: {0}")]
    NotDriveQualified(String),
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

/// `PathsUtils.noTrailingSlash`: keeps `/` and a bare drive root `X:/` intact,
/// strips the trailing slash otherwise.
#[must_use]
pub(crate) fn no_trailing_slash(p: &str) -> String {
    if p == "/" {
        return "/".to_owned();
    }
    let b = p.as_bytes();
    if p.len() >= 3 && b[1] == b':' && b[2] == b'/' {
        // `C:/` is already minimal; `C:/foo/` is not
        if p.len() > 3 {
            return p.strip_suffix('/').unwrap_or(p).to_owned();
        }
        return p.to_owned();
    }
    p.strip_suffix('/').unwrap_or(p).to_owned()
}

/// `PathsUtils.joinPosix`: exactly one slash between the parts, no trailing slash.
#[must_use]
pub(crate) fn join_posix(prefix: &str, suffix: &str) -> String {
    let pre = prefix.strip_suffix('/').unwrap_or(prefix);
    let post = suffix.strip_prefix('/').unwrap_or(suffix);
    no_trailing_slash(&format!("{pre}/{post}"))
}

/// `PathsUtils.normalizePosix`: backslashes to forward, then `no_trailing_slash`.
#[must_use]
pub(crate) fn normalize_posix(p: &str) -> String {
    let s = p.replace('\\', "/");
    if s == "/" { s } else { no_trailing_slash(&s) }
}

/// Case-insensitive `starts_with`, as `posixAbs` uses throughout.
///
/// Right on Windows and on macOS, whose default filesystem is case-insensitive but
/// case-preserving; technically wrong on Linux. Load-bearing on two of the three
/// platforms, so it is ported as-is rather than "fixed".
#[must_use]
pub(crate) fn starts_with_ignore_case(s: &str, prefix: &str) -> bool {
    s.len() >= prefix.len() && s[..prefix.len()].eq_ignore_ascii_case(prefix)
}

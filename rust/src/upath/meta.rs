//! File metadata predicates — a port of the `exists`/`isFile`/`length` family in
//! `uni.ext.PathExts`.
//!
//! # `cfg` is allowed *here*, unlike in the path algorithm
//!
//! [`crate::upath`] states that nothing is `cfg`-gated: the Windows path rules are data, so a
//! single fixture exercises all three platforms' semantics from any host. That rule is about
//! the *path algorithm*, and it does not apply to this module.
//!
//! Here the questions are about the host filesystem — is this executable, is it a symlink —
//! and the answer genuinely differs per platform because the filesystem does. `cfg(unix)` for
//! a permission bit is therefore correct, not a shortcut. The distinction worth holding onto:
//! `cfg` for *what the OS provides*, data for *what the path rules are*.
//!
//! # Where Java's answer is not reproducible, the difference is documented
//!
//! `File.canRead()` and `canExecute()` consult the effective user's access, which `std` does
//! not expose; and on Windows Java's answers largely reduce to "does it exist", because the
//! JDK does not model ACLs. Each method below says what it actually tests.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name, so a script kept in both \
              languages needs no mental translation. Internal helpers and Rust trait \
              contracts stay snake_case, so the case says whether a Scala counterpart exists."
)]

use std::fs;

use crate::upath::UPath;

impl UPath {
    /// Identity. Present so a script written against the Scala keeps its shape.
    ///
    /// Scala's `asFile` converts a `java.nio.file.Path` to a `java.io.File` — two JVM types for one
    /// concept. Rust has one, so there is nothing to convert to and this returns `self`.
    ///
    /// That is more useful than omitting it. Scala's `File` extension methods mirror the `Path`
    /// ones almost exactly (`def lastModifiedYMD = f.toPath.lastModifiedYMD` and so on), so
    /// `p.asFile.lastModifiedYMD` and `p.asFile().lastModifiedYMD()` both work and a line ported
    /// between the languages needs no edit.
    ///
    /// For the underlying `std` path, use `as_std_path` — snake_case, because that one has no Scala
    /// counterpart.
    #[must_use]
    pub fn asFile(&self) -> Self {
        self.clone()
    }

    /// Whether the path exists, following symlinks. `Files.exists`.
    #[must_use]
    pub fn exists(&self) -> bool {
        self.as_std_path().exists()
    }

    /// Whether the path is a directory. `Files.isDirectory`.
    #[must_use]
    pub fn isDirectory(&self) -> bool {
        self.as_std_path().is_dir()
    }

    /// The file size in bytes, or `0` when the path does not exist.
    ///
    /// `0` for a missing file rather than an error, mirroring the Scala
    /// `if Files.exists(p) then Files.size(p) else 0L`. A directory's size is whatever the
    /// filesystem reports, as in Java.
    #[must_use]
    pub fn length(&self) -> i64 {
        fs::metadata(self.as_std_path())
            .ok()
            .and_then(|m| i64::try_from(m.len()).ok())
            .unwrap_or(0)
    }

    /// Whether the file is zero bytes — which a missing file also reports, since
    /// [`Self::length`] gives `0` for both. Mirrors the Scala.
    #[must_use]
    pub fn isEmpty(&self) -> bool {
        self.length() == 0
    }

    /// Whether the file has any content. The negation of [`Self::isEmpty`].
    #[must_use]
    pub fn nonEmpty(&self) -> bool {
        self.length() != 0
    }

    /// Whether the file can be opened for reading.
    ///
    /// Java's `File.canRead()` asks the OS about the effective user's access; `std` exposes no
    /// equivalent, so this **attempts to open the file** and reports whether that succeeded.
    /// Behaviourally closer to the question a caller is asking, but not identical: a file
    /// exclusively locked by another process reports `false` here where Java may report `true`.
    #[must_use]
    pub fn canRead(&self) -> bool {
        fs::File::open(self.as_std_path()).is_ok()
    }

    /// Whether the file is executable.
    ///
    /// On Unix, the mode's execute bits — any of user/group/other, matching what
    /// `File.canExecute()` effectively reports for the common case. On Windows there is no
    /// execute bit and the JDK does not model ACLs, so Java's answer reduces to "exists and is
    /// a file"; this does the same rather than inventing a stricter rule.
    #[must_use]
    pub fn canExecute(&self) -> bool {
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt as _;
            fs::metadata(self.as_std_path())
                .map(|m| m.permissions().mode() & 0o111 != 0)
                .unwrap_or(false)
        }
        #[cfg(not(unix))]
        {
            self.isFile()
        }
    }

    /// Whether the path is a symbolic link. `Files.isSymbolicLink`.
    ///
    /// Uses `symlink_metadata`, which does **not** follow the link — `metadata` would report
    /// the target's type and always answer `false`.
    #[must_use]
    pub fn isSymbolicLink(&self) -> bool {
        fs::symlink_metadata(self.as_std_path())
            .map(|m| m.file_type().is_symlink())
            .unwrap_or(false)
    }

    /// Whether two paths name the same file.
    ///
    /// Mirrors the Scala `sameFileTest`, which tries the cheap test first for a reason: two
    /// paths that are textually the same name the same file *even when neither exists*, and
    /// `Files.isSameFile` would throw there. So string equality of the absolute forms comes
    /// first, and only then does this touch the disk to catch links and distinct paths that
    /// resolve to one file.
    /// The textual comparison folds case on Windows and macOS, matching Scala's
    /// `samePathString`:
    ///
    /// ```text
    /// if (isWin || isMac) s1.equalsIgnoreCase(s2) else s1 == s2
    /// ```
    ///
    /// Case-sensitive comparison was a real bug here, not a nicety: on Windows
    /// `C:/x/File.txt` and `C:/x/file.txt` name one file, and for two paths that do not exist
    /// yet -- where `canonicalize` cannot help -- the string test is the only answer. Scala said
    /// `true`, this said `false`.
    ///
    /// Case folding is keyed on the context's `is_windows`, not on `cfg!(windows)`, so the rule
    /// travels with the path semantics rather than with the host.
    #[must_use]
    pub fn isSameFile(&self, other: &Self) -> bool {
        let (a, b) = (self.to_absolute(), other.to_absolute());
        let same_text = if self.ctx().is_windows || cfg!(target_os = "macos") {
            a.posx().eq_ignore_ascii_case(b.posx())
        } else {
            a.posx() == b.posx()
        };
        if same_text {
            return true;
        }
        match (
            fs::canonicalize(self.as_std_path()),
            fs::canonicalize(other.as_std_path()),
        ) {
            (Ok(x), Ok(y)) => x == y,
            _ => false,
        }
    }

    /// The path with symlinks and `.`/`..` resolved, tolerating a path that does not exist.
    ///
    /// Canonicalisation fails outright for a missing path, in both languages -- Java's
    /// `toRealPath` throws and Rust's `canonicalize` returns `Err`. The Scala works around that
    /// by finding the longest existing *prefix*, resolving that, and re-appending the remainder
    /// unresolved. This does the same, so a path to a file that has yet to be created still
    /// comes back with its existing directories canonicalised.
    ///
    /// Returns forward slashes, as the Scala does.
    #[must_use]
    pub fn realPath(&self) -> Self {
        let abs = self.to_absolute();
        let std_path = abs.as_std_path();
        // Longest existing ancestor, self included.
        let mut prefix: Option<&std::path::Path> = None;
        let mut cursor: Option<&std::path::Path> = Some(std_path.as_path());
        while let Some(c) = cursor {
            if c.exists() {
                prefix = Some(c);
                break;
            }
            cursor = c.parent();
        }
        let Some(found) = prefix else {
            return abs.normalized();
        };
        let Ok(resolved) = fs::canonicalize(found) else {
            return abs.normalized();
        };
        // Windows canonicalize yields a `\\?\` verbatim prefix, which no other API here emits.
        let resolved_str = resolved.to_string_lossy().replace('\\', "/");
        let resolved_str = resolved_str
            .strip_prefix("//?/")
            .map_or(resolved_str.clone(), str::to_owned);
        let remainder = std_path
            .strip_prefix(found)
            .ok()
            .map(|r| r.to_string_lossy().replace('\\', "/"))
            .unwrap_or_default();
        let joined = if remainder.is_empty() {
            resolved_str
        } else {
            format!("{}/{remainder}", resolved_str.trim_end_matches('/'))
        };
        Self::resolve(self.ctx(), &joined).unwrap_or_else(|_| abs.normalized())
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use crate::upath::PathContext;
    use crate::upath::UPath;
    use crate::upath::UserInfo;

    fn ctx(dir: &str) -> Arc<PathContext> {
        Arc::new(PathContext::synthetic(
            &[],
            UserInfo::new("tester", dir, dir),
            cfg!(windows),
        ))
    }

    fn probe(dir: &std::path::Path, name: &str) -> UPath {
        let d = dir.to_string_lossy().replace('\\', "/");
        UPath::resolve(&ctx(&d), &format!("{d}/{name}")).unwrap_or_else(|e| panic!("resolve: {e}"))
    }

    #[test]
    fn length_and_emptiness_treat_a_missing_file_as_zero() {
        let tmp = tempfile::tempdir().unwrap_or_else(|e| panic!("tempdir: {e}"));
        let missing = probe(tmp.path(), "not-there.txt");
        assert_eq!(missing.length(), 0);
        assert!(missing.isEmpty(), "a missing file reports empty, as in Scala");
        assert!(!missing.nonEmpty());
        assert!(!missing.exists());

        let empty = probe(tmp.path(), "empty.txt");
        std::fs::write(empty.as_std_path(), b"").unwrap_or_else(|e| panic!("write: {e}"));
        assert_eq!(empty.length(), 0);
        assert!(empty.isEmpty());
        assert!(empty.exists(), "...and is indistinguishable from the missing one by length");

        let full = probe(tmp.path(), "full.txt");
        std::fs::write(full.as_std_path(), b"hello").unwrap_or_else(|e| panic!("write: {e}"));
        assert_eq!(full.length(), 5);
        assert!(full.nonEmpty());
    }

    #[test]
    fn directory_and_file_predicates() {
        let tmp = tempfile::tempdir().unwrap_or_else(|e| panic!("tempdir: {e}"));
        let dir = probe(tmp.path(), "sub");
        std::fs::create_dir(dir.as_std_path()).unwrap_or_else(|e| panic!("mkdir: {e}"));
        assert!(dir.exists());
        assert!(dir.isDirectory());
        assert!(!dir.isFile());

        let f = probe(tmp.path(), "sub/x.txt");
        std::fs::write(f.as_std_path(), b"x").unwrap_or_else(|e| panic!("write: {e}"));
        assert!(f.isFile());
        assert!(!f.isDirectory());
        assert!(f.canRead());
        assert!(!f.isSymbolicLink());
    }

    #[test]
    fn is_same_file_matches_textually_even_when_absent() {
        let tmp = tempfile::tempdir().unwrap_or_else(|e| panic!("tempdir: {e}"));
        // The case that makes the cheap test first necessary: neither exists, so canonicalize
        // fails for both, yet they plainly name the same file.
        let a = probe(tmp.path(), "ghost.txt");
        let b = probe(tmp.path(), "ghost.txt");
        assert!(!a.exists());
        assert!(a.isSameFile(&b));

        let c = probe(tmp.path(), "other.txt");
        assert!(!a.isSameFile(&c));

        // And two spellings of one existing file.
        let real = probe(tmp.path(), "real.txt");
        std::fs::write(real.as_std_path(), b"x").unwrap_or_else(|e| panic!("write: {e}"));
        let dotted = probe(tmp.path(), "./real.txt");
        assert!(real.isSameFile(&dotted), "`./x` and `x` are the same file");
    }

    #[test]
    fn real_path_tolerates_a_missing_tail() {
        let tmp = tempfile::tempdir().unwrap_or_else(|e| panic!("tempdir: {e}"));
        let deep = probe(tmp.path(), "nope/also-nope/file.txt");
        let got = deep.realPath();
        // The existing prefix is canonicalised; the missing tail survives verbatim.
        assert!(
            got.posx().ends_with("nope/also-nope/file.txt"),
            "tail should survive: {}",
            got.posx()
        );
        assert!(
            !got.posx().contains("//?/"),
            "the Windows verbatim prefix must be stripped: {}",
            got.posx()
        );
    }
}

//! String extensions — a port of the context-free half of `uni.ext.StringExts`.
//!
//! # Why these exist when `str` already has equivalents
//!
//! `lc` is three characters shorter than `to_lowercase`, which on its own would not
//! justify a trait. The reason is **symmetry**: the point of this port is that a
//! script can be maintained in both languages side by side, and an API that drifts
//! into per-language idiom defeats that. `p.baseName.lc` in Scala should read
//! `p.base_name()?.lc()` here, not `p.base_name()?.to_lowercase()`. Every name below
//! is Scala's, adjusted only for `snake_case`.
//!
//! # Two traits
//!
//! [`StrExts`] is context-free: pure string work, no environment.
//!
//! [`StrPathExts`] covers the rest of `StringExts` — `asPath`, `absPath`, `posix`,
//! `local`, `readCsv`, `writeCsv` — which need a
//! [`PathContext`](crate::upath::PathContext). They resolve against
//! [`PathContext::default_context`], built from the environment on first use, which is
//! what makes `"path".as_path()` possible at all. Code that manages its own contexts
//! should keep using `UPath::resolve(&ctx, s)`; these exist so the Rust mirror of a
//! Scala one-liner stays a one-liner.
//!
//! The resolving methods return `Result`, where Scala throws. `s.as_path()?` is the
//! direct analogue of `s.asPath`, and resolution failure is real — an embedded NUL or
//! colon — so it is reported rather than swallowed.

use ndarray::Array2;

use crate::upath::PathContext;
use crate::upath::PathError;
use crate::upath::UPath;
use crate::upath::matcsv::CsvCell;
use crate::upath::normalize_posix;
use crate::upath::resolve::posix_abs;
use crate::upath::resolve::resolve_windows_pathstr;
use crate::upath::starts_with_ignore_case;

/// The context-free `String` extensions from `uni`.
///
/// Implemented for `str`, so it applies to `&str`, `String` and anything derefing to
/// `str` — including the `&str` that [`UPath::base_name`](crate::upath::UPath::base_name)
/// and friends return.
pub trait StrExts {
    /// Lowercase. `StringExts.lc`.
    ///
    /// The live replacement for `PathExts.lcbasename`, `lcname` and `lcsuffix`, all
    /// of which are deprecated in `uni` in favour of `baseName.lc`, `last.lc` and
    /// `ext.lc`. Composing beats three more methods, in both languages.
    fn lc(&self) -> String;

    /// Uppercase. `StringExts.uc`.
    fn uc(&self) -> String;

    /// Forward-slash form, with any trailing slash removed. `StringExts.posx`.
    ///
    /// Pure string work — no mount table and no context, which is what lets it live
    /// here rather than on `UPath`.
    fn posx(&self) -> String;

    /// Drops the last `.ext`. `StringExts.dropSuffix`.
    ///
    /// A leading dot is not an extension: `.gitignore` comes back unchanged, which is
    /// why the test is `i <= 0` and not `i < 0`.
    fn drop_suffix(&self) -> &str;

    /// Case-insensitive `starts_with`. `StringExts.startsWithIgnoreCase`.
    fn starts_with_ignore_case(&self, prefix: &str) -> bool;

    /// Removes `prefix` if present, else returns the string unchanged.
    /// `StringExts.stripPrefix`.
    ///
    /// Note the return type: Scala's shadowing `stripPrefix` yields a `String` either
    /// way, whereas std's [`str::strip_prefix`] yields an `Option`. This keeps
    /// Scala's total shape, so a ported script reads the same. Use the std method
    /// when you want to know *whether* it matched.
    fn strip_prefix_or_self(&self, prefix: &str) -> &str;
}

impl StrExts for str {
    fn lc(&self) -> String {
        self.to_lowercase()
    }

    fn uc(&self) -> String {
        self.to_uppercase()
    }

    fn posx(&self) -> String {
        normalize_posix(self)
    }

    fn drop_suffix(&self) -> &str {
        match self.rfind('.') {
            Some(i) if i > 0 => &self[..i],
            _ => self,
        }
    }

    fn starts_with_ignore_case(&self, prefix: &str) -> bool {
        starts_with_ignore_case(self, prefix)
    }

    fn strip_prefix_or_self(&self, prefix: &str) -> &str {
        self.strip_prefix(prefix).unwrap_or(self)
    }
}

#[cfg(test)]
mod tests {
    use super::StrExts;

    #[test]
    fn lc_and_uc_match_scala() {
        assert_eq!("MiXeD".lc(), "mixed");
        assert_eq!("MiXeD".uc(), "MIXED");
        assert_eq!("".lc(), "");
        // Applies to a String as well as a &str, via Deref.
        assert_eq!(String::from("ABC").lc(), "abc");
    }

    #[test]
    fn lc_composes_with_the_path_accessors() {
        // The reason this trait exists: `baseName.lc` in Scala reads `base_name()?.lc()`
        // here rather than drifting to `to_lowercase()`.
        assert_eq!("README".lc(), "readme");
        assert_eq!("TXT".lc(), "txt");
    }

    #[test]
    fn posx_swaps_separators_and_drops_a_trailing_slash() {
        assert_eq!(r"C:\foo\bar".posx(), "C:/foo/bar");
        assert_eq!("/usr/bin/".posx(), "/usr/bin");
        // Root keeps its slash; it would otherwise become empty.
        assert_eq!("/".posx(), "/");
    }

    #[test]
    fn drop_suffix_leaves_a_dotfile_alone() {
        assert_eq!("archive.tar.gz".drop_suffix(), "archive.tar");
        assert_eq!("plain".drop_suffix(), "plain");
        // The `i > 0` guard: a leading dot is a hidden file, not an extension.
        assert_eq!(".gitignore".drop_suffix(), ".gitignore");
        assert_eq!("".drop_suffix(), "");
        assert_eq!("trailing.".drop_suffix(), "trailing");
    }

    #[test]
    fn starts_with_ignore_case_ignores_case() {
        assert!("C:/Users".starts_with_ignore_case("c:/users"));
        assert!("c:/users".starts_with_ignore_case("C:/USERS"));
        assert!(!"C:/Users".starts_with_ignore_case("D:/"));
        assert!("anything".starts_with_ignore_case(""));
    }

    #[test]
    fn strip_prefix_or_self_is_total() {
        // Scala's `stripPrefix` returns a String whether or not it matched; std's
        // returns an Option. This keeps Scala's shape.
        assert_eq!("/c/Users".strip_prefix_or_self("/c"), "/Users");
        assert_eq!("/d/Users".strip_prefix_or_self("/c"), "/d/Users");
        assert_eq!("exact".strip_prefix_or_self("exact"), "");
    }
}

/// The `String` extensions that need a [`PathContext`], resolved against the
/// process-wide default.
///
/// Split from [`StrExts`] so it stays obvious which of these read the environment.
pub trait StrPathExts {
    /// This string as a path. `StringExts.asPath`.
    ///
    /// # Errors
    /// [`PathError`] when the string cannot name a path.
    fn as_path(&self) -> Result<UPath, PathError>;

    /// Alias for [`StrPathExts::as_path`]. `StringExts.path`.
    ///
    /// # Errors
    /// As [`StrPathExts::as_path`].
    fn path(&self) -> Result<UPath, PathError> {
        self.as_path()
    }

    /// Alias for [`StrPathExts::as_path`]. `StringExts.toPath`.
    ///
    /// # Errors
    /// As [`StrPathExts::as_path`].
    fn to_path(&self) -> Result<UPath, PathError> {
        self.as_path()
    }

    /// Absolute, normalised form. `StringExts.absPath`.
    ///
    /// # Errors
    /// As [`StrPathExts::as_path`].
    fn abs_path(&self) -> Result<UPath, PathError>;

    /// Absolute POSIX form, through the mount table. `StringExts.posix`.
    ///
    /// # Errors
    /// [`PathError`] when the string has no absolute POSIX form.
    fn posix(&self) -> Result<String, PathError>;

    /// MSYS2-aware POSIX-to-Windows conversion, in native form. `StringExts.local`.
    ///
    /// Differs from `as_path()?.localpath()` for input that is not a POSIX path: that
    /// is returned untouched rather than resolved. The Scala makes the same
    /// distinction, deliberately.
    ///
    /// # Errors
    /// [`PathError`] from resolving a POSIX input.
    fn local(&self) -> Result<String, PathError>;

    /// Reads this path as a CSV matrix. `StringExts.readCsv`, and its `readCsvB` /
    /// `readCsvF` siblings via the type parameter.
    ///
    /// Unlike the Scala, an `http://` or `https://` string is *not* fetched — there is
    /// no HTTP client here. Use `loadSmartUrl`'s equivalent explicitly if that is
    /// wanted.
    ///
    /// # Errors
    /// As [`StrPathExts::as_path`], plus any read failure.
    fn read_csv<T: CsvCell>(&self) -> Result<Array2<T>, PathError>;

    /// Writes a matrix to this path as CSV. `StringExts.writeCsv`.
    ///
    /// # Errors
    /// As [`StrPathExts::as_path`], plus any write failure.
    fn write_csv(&self, mat: &Array2<f64>) -> Result<(), PathError>;
}

impl StrPathExts for str {
    fn as_path(&self) -> Result<UPath, PathError> {
        UPath::resolve(PathContext::default_context(), self)
    }

    fn abs_path(&self) -> Result<UPath, PathError> {
        Ok(self.as_path()?.to_absolute().normalized())
    }

    fn posix(&self) -> Result<String, PathError> {
        posix_abs(PathContext::default_context(), self)
    }

    fn local(&self) -> Result<String, PathError> {
        let ctx = PathContext::default_context();
        let forward = normalize_posix(self);
        // Not a POSIX path, or not a platform with a mount table: hand it back as-is.
        if !ctx.is_windows || !forward.starts_with('/') {
            return Ok(self.to_owned());
        }
        Ok(resolve_windows_pathstr(ctx, &forward)?.replace('/', "\\"))
    }

    fn read_csv<T: CsvCell>(&self) -> Result<Array2<T>, PathError> {
        self.as_path()?.try_read_csv().map_err(|e| PathError::from_io(&e))
    }

    fn write_csv(&self, mat: &Array2<f64>) -> Result<(), PathError> {
        self.as_path()?.try_write_matrix(mat).map_err(|e| PathError::from_io(&e))
    }
}

#[cfg(test)]
mod default_context_tests {
    use super::StrPathExts;
    use crate::upath::PathContext;
    use crate::upath::UserInfo;

    // These run against the *real* environment, because that is what the default
    // context is. They therefore assert relationships rather than literal paths --
    // a literal would only hold on the machine that wrote it.

    #[test]
    fn as_path_resolves_against_the_default_context() {
        let p = "some/relative/file.txt".as_path().expect("resolves");
        // Resolution absolutises a bare relative path against the working directory,
        // so the tail survives and the result is longer than the input.
        assert!(p.posx().ends_with("some/relative/file.txt"), "got {}", p.posx());
    }

    #[test]
    fn the_aliases_agree_with_as_path() {
        let a = "x/y".as_path().expect("as_path");
        assert_eq!("x/y".path().expect("path").posx(), a.posx());
        assert_eq!("x/y".to_path().expect("to_path").posx(), a.posx());
    }

    #[test]
    fn abs_path_is_absolute_and_normalised() {
        let p = "a/./b/../c".abs_path().expect("abs_path");
        assert!(p.is_absolute(), "not absolute: {}", p.posx());
        assert!(p.posx().ends_with("/a/c"), "not normalised: {}", p.posx());
    }

    #[test]
    fn posx_and_posix_are_different_things() {
        // `posx` is exactly the separator swap: one slash per backslash, nothing
        // more. It does NOT collapse duplicate separators -- that is what
        // `Paths.get` does. So a doubled separator survives here while the same
        // path taken through `UPath` comes back single. Two different operations,
        // and conflating them is easy.
        //
        // Escaped strings, not raw ones: a raw literal makes the count of
        // backslashes genuinely hard to read, which is how this test came to
        // assert the wrong thing once already.
        use super::StrExts;
        assert_eq!("a\\b".posx(), "a/b", "one backslash, one slash");
        assert_eq!("a\\\\b".posx(), "a//b", "two backslashes, two slashes: no collapsing");
        assert_eq!("a//b".posx(), "a//b", "already-forward duplicates are left alone");
        assert_eq!("\\\\server\\share".posx(), "//server/share");
        assert_eq!("".posx(), "");
        // An absolute POSIX path always has an absolute POSIX form, on either
        // platform -- untranslated off Windows, mount-mapped on it.
        let out = "/usr/bin".posix().expect("posix");
        assert!(out.starts_with('/'), "not absolute: {out}");
    }

    #[test]
    fn local_leaves_a_non_posix_string_untouched() {
        // The documented difference from `as_path()?.localpath()`.
        assert_eq!("relative/thing".local().expect("local"), "relative/thing");
        assert_eq!("".local().expect("local"), "");
    }

    #[test]
    fn the_default_context_is_stable_across_calls() {
        // A `OnceLock`, so every caller sees the same instance -- which is what makes
        // it safe to hand out `Arc`s of it.
        let a = PathContext::default_context();
        let b = PathContext::default_context();
        assert!(std::ptr::eq(std::ptr::from_ref(a), std::ptr::from_ref(b)));
    }

    #[test]
    fn set_default_is_refused_once_a_default_is_live() {
        // Reading it first pins it, so this must fail rather than silently swap a
        // context that other holders already have an `Arc` to.
        let _ = PathContext::default_context();
        let replacement = PathContext::synthetic(&[], UserInfo::new("x", "/h", "/d"), false);
        assert!(!PathContext::set_default(replacement), "a live default was replaced");
    }
}

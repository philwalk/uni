//! Path-string conversions — a port of the `Path` extension methods in
//! `uni.ext.PathExts`.
//!
//! # Why there is a type here at all
//!
//! Every one of these methods is defined on a `java.nio.file.Path`, and leans on
//! what that type does on the way in: `Paths.get` collapses duplicate separators
//! and drops trailing ones, and `toString` renders native separators. `.posx` is
//! then `normalizePosix(p.toString)` — a *second* pass over an already-normalised
//! value. Rust has no object doing that first pass, so [`UPath`] reproduces it
//! explicitly; skip it and Rust's `.posix` diverges from Scala's even when the two
//! `posix_abs` agree.
//!
//! What `Paths.get` does and does not do, measured rather than assumed:
//!
//! | input | result |
//! |---|---|
//! | `a//b` | `a/b` — duplicate separators collapse |
//! | `a/b/` | `a/b` — trailing separator dropped |
//! | `a/./b` | `a/./b` — **`.` is not resolved** |
//! | `a/../b` | `a/../b` — **`..` is not resolved** |
//!
//! Only `normalize()` resolves dot segments, and none of these methods call it, so
//! `.` and `..` survive as ordinary name elements.

use std::sync::Arc;

use crate::upath::PathContext;
use crate::upath::resolve::posix_rel;
use crate::upath::PathError;
use crate::upath::no_trailing_slash;
use crate::upath::resolve::find_prefix;
use crate::upath::resolve::posix_abs;
use crate::upath::resolve::resolve_pathstr;

/// A resolved path, carrying the context it was resolved against.
///
/// Stored as the normalised forward-slash form — exactly what [`UPath::posx`]
/// returns — with the native rendering derived on demand. Java stores components
/// and joins them in `toString`; storing the joined form and splitting on demand
/// is the same information for far less code, and these are not hot paths.
#[derive(Debug, Clone)]
pub struct UPath {
    ctx: Arc<PathContext>,
    s: String,
}

impl UPath {
    /// Resolves `input` through the mount table, then applies `Paths.get`'s
    /// normalisation.
    ///
    /// # Errors
    /// Propagates [`PathError`] from resolution.
    pub fn resolve(ctx: &Arc<PathContext>, input: &str) -> Result<Self, PathError> {
        let target = file_uri_path(input);
        let win = resolve_pathstr(ctx, target.as_deref().unwrap_or(input), &[])?;
        Ok(Self {
            ctx: Arc::clone(ctx),
            s: java_normalize(&win),
        })
    }

    /// Forward-slash form. `PathExts.posx`.
    #[must_use]
    pub fn posx(&self) -> &str {
        &self.s
    }

    /// Native form: backslashes on Windows. `PathExts.localpath`, and `local`,
    /// which is an alias for it.
    #[must_use]
    pub fn localpath(&self) -> String {
        if self.ctx.is_windows {
            self.s.replace('/', "\\")
        } else {
            self.s.clone()
        }
    }

    /// Native Windows form. `PathExts.dospath`.
    ///
    /// Not the same as [`UPath::localpath`], though they agree for every path that
    /// is not bare root. `dospath` is `p.toString`, and Java renders a root *with*
    /// its trailing separator — `C:\` and `\\server\share\`. `posx` keeps that for
    /// the `X:/` shape but strips it from the UNC one, so it is put back here.
    #[must_use]
    pub fn dospath(&self) -> String {
        let mut s = self.s.clone();
        if self.root_len() == s.len() && !s.ends_with('/') {
            s.push('/');
        }
        if self.ctx.is_windows {
            s.replace('/', "\\")
        } else {
            s
        }
    }

    /// Absolute POSIX form. `PathExts.posix`.
    ///
    /// # Errors
    /// Propagates [`PathError`]; a relative path is returned unchanged rather than
    /// failing, matching `posixAbs`.
    pub fn posix(&self) -> Result<String, PathError> {
        posix_abs(&self.ctx, &self.s)
    }

    /// Path with any drive letter removed: `C:/foo` → `/foo`. `PathExts.noDrive`.
    #[must_use]
    pub fn no_drive(&self) -> &str {
        let b = self.s.as_bytes();
        if self.s.len() >= 2 && b[1] == b':' {
            &self.s[2..]
        } else {
            &self.s
        }
    }

    /// Length of the root prefix: `C:/`, `/`, or `//server/share`. Zero when the
    /// path is relative.
    ///
    /// A UNC root spans two components — Java's root for `\\server\share\x` is
    /// `\\server\share\`, so `share` is part of the root and not a name element.
    fn root_len(&self) -> usize {
        let b = self.s.as_bytes();
        if self.s.starts_with("//") {
            // second separator after the leading pair, else the whole string
            return self.s[2..]
                .match_indices('/')
                .nth(1)
                .map_or(self.s.len(), |(i, _)| i + 2);
        }
        if self.s.len() >= 3 && b[1] == b':' && b[2] == b'/' {
            return 3;
        }
        if self.s.starts_with('/') { 1 } else { 0 }
    }

    /// Name elements, excluding the root. `PathExts.segments`.
    ///
    /// Java's iterator yields only name elements, so `C:/Users` has one (`Users`)
    /// and `C:/` has none. `.` and `..` are ordinary elements here.
    #[must_use]
    pub fn segments(&self) -> Vec<&str> {
        self.s[self.root_len()..]
            .split('/')
            .filter(|c| !c.is_empty())
            .collect()
    }

    /// Name elements joined in reverse. `PathExts.reversePath`.
    #[must_use]
    pub fn reverse_path(&self) -> String {
        let mut parts = self.segments();
        parts.reverse();
        parts.join("/")
    }

    /// Final name element. `PathExts.last`.
    ///
    /// # Errors
    /// [`PathError::NoFileName`] for a root-only path, where Java's `getFileName`
    /// returns null and the Scala throws a `NullPointerException`.
    pub fn last(&self) -> Result<&str, PathError> {
        self.segments()
            .last()
            .copied()
            .ok_or_else(|| PathError::NoFileName(self.s.clone()))
    }

    /// Final element with any extension removed. `PathExts.baseName`.
    ///
    /// Splits on the *last* dot at any position, so a dotfile like `.gitignore`
    /// becomes empty — matching the Scala, which uses `lastIndexOf('.') == -1` as
    /// its only guard.
    ///
    /// # Errors
    /// As [`UPath::last`].
    pub fn base_name(&self) -> Result<&str, PathError> {
        let n = self.last()?;
        Ok(match n.rfind('.') {
            Some(i) => &n[..i],
            None => n,
        })
    }

    /// Extension including the leading dot, or empty. `PathExts.dotsuffix`.
    ///
    /// Note the `idx > 0` guard, which `baseName` lacks: a leading dot does not
    /// count, so `.gitignore` has no dotsuffix.
    ///
    /// # Errors
    /// As [`UPath::last`].
    pub fn dotsuffix(&self) -> Result<&str, PathError> {
        let n = self.last()?;
        Ok(match n.rfind('.') {
            Some(i) if i > 0 => &n[i..],
            _ => "",
        })
    }

    /// Extension without the leading dot, or empty. `PathExts.ext`.
    ///
    /// # Errors
    /// As [`UPath::last`].
    pub fn ext(&self) -> Result<&str, PathError> {
        Ok(self.dotsuffix()?.get(1..).unwrap_or(""))
    }

    /// Extension as an option. `PathExts.extension`.
    ///
    /// # Errors
    /// As [`UPath::last`].
    pub fn extension(&self) -> Result<Option<&str>, PathError> {
        let e = self.ext()?;
        Ok((!e.is_empty()).then_some(e))
    }

    /// Parent path, or this path when it has no parent. `PathExts.getParentNonNull`.
    #[must_use]
    pub fn parent(&self) -> Self {
        let root = self.root_len();
        let tail = &self.s[root..];
        let trimmed = tail.trim_end_matches('/');
        match trimmed.rfind('/') {
            Some(i) => self.with(&self.s[..root + i]),
            None if root > 0 => self.with(&self.s[..root]),
            None => self.clone(),
        }
    }

    fn with(&self, s: &str) -> Self {
        Self {
            ctx: Arc::clone(&self.ctx),
            s: no_trailing_slash(s),
        }
    }
}

/// The path component of a `file://` URI, if `input` is one.
///
/// `Paths.get` special-cases these (`Paths.scala:26-31`): a string starting
/// `file://` is handed to `Resolver.resolvePath(uri)` and resolved by its path
/// component, rather than reaching `classify`, which would reject anything holding
/// `://` as `Invalid`. So `Paths.get("file:///c/tmp").posx` is `C:/tmp` while
/// `Resolver.resolvePathstr("file:///c/tmp")` throws — an asymmetry that only
/// shows up once the extension methods are pinned separately from the raw
/// resolver.
fn file_uri_path(input: &str) -> Option<String> {
    let rest = input.strip_prefix("file://")?;
    if rest.starts_with('/') {
        // no authority: file:///c/tmp
        return Some(rest.to_owned());
    }
    // authority present: file://host/c/tmp — the path starts at its first slash
    rest.find('/').map(|i| rest[i..].to_owned())
}

/// `Paths.get`'s normalisation: native separators to forward, duplicate separators
/// collapsed, trailing separator dropped. Dot segments are deliberately left
/// alone — `Paths.get` does not resolve them, only `normalize()` does.
///
/// The leading pair of a UNC path is preserved; collapsing it would turn
/// `//server/share` into an ordinary rooted path.
fn java_normalize(win: &str) -> String {
    let fwd = win.replace('\\', "/");
    let unc = fwd.starts_with("//");
    let body = if unc { &fwd[2..] } else { &fwd[..] };

    let mut out = String::with_capacity(fwd.len());
    if unc {
        out.push_str("//");
    }
    let mut prev_slash = false;
    for c in body.chars() {
        if c == '/' {
            if !prev_slash {
                out.push(c);
            }
            prev_slash = true;
        } else {
            out.push(c);
            prev_slash = false;
        }
    }
    no_trailing_slash(&out)
}

impl UPath {
    /// True when the path has a drive or UNC root.
    ///
    /// A bare `/` root is *not* absolute on Windows — Java treats `oo` as
    /// relative to the current drive — which is why this checks for a root longer
    /// than one character rather than for any root at all.
    #[must_use]
    pub fn is_absolute(&self) -> bool {
        self.root_len() > 1
    }

    /// Resolved against the working directory when relative. `Path.toAbsolutePath`.
    #[must_use]
    pub fn to_absolute(&self) -> Self {
        if self.is_absolute() {
            return self.clone();
        }
        let dir = self.ctx.user.dir.trim_end_matches('/');
        self.with(&format!("{dir}/{}", self.s))
    }

    /// Dot segments resolved. `Path.normalize`.
    ///
    /// Not applied on construction: `Paths.get` leaves `.` and `..` alone, and only
    /// the methods that explicitly call `normalize` see them resolved.
    #[must_use]
    pub fn normalized(&self) -> Self {
        let root = &self.s[..self.root_len()];
        let mut out: Vec<&str> = Vec::new();
        for seg in self.segments() {
            match seg {
                "." => {}
                ".." => {
                    if out.last().is_some_and(|l| *l != "..") {
                        out.pop();
                    } else if root.is_empty() {
                        // A leading `..` is meaningful only for a relative path; at
                        // a root there is nothing above to climb to, so Java drops it.
                        out.push(seg);
                    }
                }
                _ => out.push(seg),
            }
        }
        self.with(&format!("{root}{}", out.join("/")))
    }

    /// Absolute, normalised, forward-slash form. `PathExts.abs`.
    ///
    /// The Scala only calls `toAbsolutePath` when the file exists, and normalises a
    /// relative path in place otherwise. That filesystem check is why `abs` is not
    /// in the cross-language fixture: the answer would depend on what happens to be
    /// on the machine that generated it.
    #[must_use]
    pub fn abs(&self, exists: bool) -> String {
        if exists {
            self.to_absolute().normalized().s
        } else {
            self.normalized().s
        }
    }

    /// Standardised POSIX form: absolute, normalised, then mapped back through the
    /// mount table. `PathsUtils.standardizePath`.
    ///
    /// Deliberate deviation: the Scala picks `toAbsolutePath.normalize` or
    /// `toFile.getAbsolutePath` depending on `canExist`, i.e. on whether the root
    /// drive is present on this machine. Only the first resolves dot segments, so
    /// the two differ solely for a dotted path on a drive that does not exist —
    /// and pinning that in the fixture would make the reference machine-dependent.
    /// This always normalises.
    #[must_use]
    pub fn stdpath(&self) -> String {
        let abs = self.to_absolute().normalized();
        if !self.ctx.is_windows {
            return abs.s;
        }
        // A plain strip, not `no_trailing_slash`: the Scala reduces `C:/` to `C:`
        // here, and the mount keys are stored in that bare form, so leaving the
        // separator on would append it to the mapped result (`/c/` for `/c`).
        let pstr = &if abs.s == "/" {
            abs.s.clone()
        } else {
            abs.s.strip_suffix('/').unwrap_or(&abs.s).to_owned()
        };
        match find_prefix(pstr, &self.ctx.win2posix_keys) {
            Some(win_root) => {
                let post = &pstr[win_root.len().min(pstr.len())..];
                match self
                    .ctx
                    .mounts
                    .win2posix
                    .get(&win_root)
                    .and_then(|v| v.first())
                {
                    Some(root) if root == "/" => post.to_owned(),
                    Some(root) => format!("{root}{post}"),
                    None => post.to_owned(),
                }
            }
            None => {
                let b = pstr.as_bytes();
                if pstr.len() >= 2 && b[1] == b':' {
                    let drive = pstr[..1].to_lowercase();
                    format!("{}{drive}{}", self.ctx.mounts.cygdrive, &pstr[2..])
                } else if let Some(unc) = pstr.strip_prefix("//") {
                    format!("{}unc/{unc}", self.ctx.mounts.cygdrive)
                } else {
                    pstr.clone()
                }
            }
        }
    }

    /// Relative to the working directory when it lies below it, else unchanged.
    /// This path relative to the working directory. `PathExts.relpath`.
    #[must_use]
    pub fn relpath(&self) -> String {
        // `posix_rel`, not `relative_to_cwd().stdpath()`. That older form was a
        // faithful port of a Scala defect: `relativePathToCwd` computed the relative
        // path and `standardizePath` immediately threw it away by re-absolutising,
        // and the two used different working directories into the bargain. Fixed in
        // `uni` first; this is the matching change.
        posix_rel(&self.ctx, &self.s).unwrap_or_else(|_| self.s.clone())
    }

    /// This path relative to the working directory, as a path rather than a string.
    /// `PathExts.relativePath`.
    ///
    /// Kept when [`UPath::relpath`] stopped using it: `relpath` now goes through
    /// `posix_rel`, but the `Path`-returning form is part of `uni`'s surface and this
    /// is what implements it.
    #[must_use]
    pub fn relative_path(&self) -> Self {
        self.relative_to_cwd()
    }

    /// `Internals.relativePathToCwd`.
    fn relative_to_cwd(&self) -> Self {
        if !self.is_absolute() {
            return self.clone();
        }
        let cwd = self.with(&self.ctx.user.dir);
        match relativize(&cwd, self) {
            // A result climbing out of the working directory is no better than the
            // absolute form, so the Scala keeps the absolute one.
            Some(rel) if !rel.starts_with("..") => self.with(&rel),
            _ => self.clone(),
        }
    }
}

/// `Path.relativize`: the route from `base` to `target`, or `None` when there is
/// none — different roots, as Java signals with `IllegalArgumentException`.
fn relativize(base: &UPath, target: &UPath) -> Option<String> {
    let (br, tr) = (&base.s[..base.root_len()], &target.s[..target.root_len()]);
    if !br.eq_ignore_ascii_case(tr) {
        return None;
    }
    let (bs, ts) = (base.segments(), target.segments());
    let common = bs
        .iter()
        .zip(ts.iter())
        .take_while(|(a, b)| a.eq_ignore_ascii_case(b))
        .count();
    let ups = std::iter::repeat_n("..", bs.len() - common);
    let down = ts[common..].iter().copied();
    Some(ups.chain(down).collect::<Vec<_>>().join("/"))
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::UPath;
    use crate::upath::PathContext;
    use crate::upath::UserInfo;

    /// Absolute inputs only. `stdpath`, `relpath` and `abs` consult a working
    /// directory or the filesystem for relative input, which is exactly why they
    /// are not in the cross-language fixture — see `PathParityGen`.
    fn ctx() -> Arc<PathContext> {
        Arc::new(PathContext::synthetic(
            &[
                "C:/msys64 on / type ntfs (binary)".to_owned(),
                "C:/ on /c type ntfs (binary)".to_owned(),
                "C:/opt on /opt type ntfs (binary)".to_owned(),
            ],
            UserInfo::new("liam", "C:/Persons/liam", "C:/munit/test"),
            true,
        ))
    }

    fn p(input: &str) -> UPath {
        UPath::resolve(&ctx(), input).expect("resolves")
    }

    #[test]
    fn stdpath_maps_back_through_the_mount_table() {
        assert_eq!(p("/opt/x").stdpath(), "/opt/x");
        assert_eq!(p("/usr/bin").stdpath(), "/usr/bin");
        // The root mount maps to "/", so the remainder is the whole answer.
        assert_eq!(p("C:/msys64/usr").stdpath(), "/usr");
        // No mount matches, so the cygdrive form takes over.
        assert_eq!(p("D:/data").stdpath(), "/d/data");
    }

    #[test]
    fn stdpath_reduces_a_bare_drive_root() {
        // `C:/` is stripped to `C:` before matching, because that is the form the
        // mount keys are stored in. Leaving the separator on appended it to the
        // result and produced `/c/`.
        assert_eq!(p("/c").stdpath(), "/c");
        assert_eq!(p("/c/").stdpath(), "/c");
    }

    #[test]
    fn stdpath_resolves_dot_segments() {
        assert_eq!(p("/opt/a/../b").stdpath(), "/opt/b");
        assert_eq!(p("/opt/./x").stdpath(), "/opt/x");
    }

    #[test]
    fn relpath_returns_a_relative_path_below_the_working_directory() {
        // This used to assert the opposite, and said so in its own name: `relpath`
        // relativised against the working directory and then handed the result to
        // `standardizePath`, which absolutised it again. Fixed in `uni` first --
        // along with the two halves disagreeing about *which* working directory --
        // and `relpath` is now a fixture field, so the two languages are held
        // together on it.
        let path = p("C:/munit/test/sub/f.txt");
        assert_eq!(path.relpath(), "sub/f.txt");
        assert_eq!(p("C:/munit/test").relpath(), ".");
    }

    #[test]
    fn relative_path_agrees_with_relpath_below_the_working_directory() {
        // The `Path`-returning sibling, `PathExts.relativePath`.
        assert_eq!(p("C:/munit/test/sub").relative_path().posx(), "sub");
    }

    #[test]
    fn relpath_keeps_the_absolute_form_when_it_would_climb_out() {
        // Relativising would need `..`, which the Scala rejects in favour of the
        // absolute path.
        assert_eq!(p("/opt/x").relpath(), "/opt/x");
        assert_eq!(p("/c").relpath(), "/c");
    }

    #[test]
    fn normalized_matches_java_semantics() {
        // `..` at a root has nothing to climb to and is dropped; a relative `..`
        // is kept, since it is meaningful there.
        assert_eq!(p("/opt/a/../../..").normalized().posx(), "C:/");
        assert_eq!(p("/opt/./a/./b").normalized().posx(), "C:/opt/a/b");
    }

    #[test]
    fn abs_takes_the_existence_flag_rather_than_touching_the_disk() {
        let path = p("/opt/a/../b");
        // Already absolute, so both branches agree here; the flag only decides
        // whether a *relative* path is resolved against the working directory.
        assert_eq!(path.abs(true), "C:/opt/b");
        assert_eq!(path.abs(false), "C:/opt/b");
    }
}

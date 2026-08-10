//! The resolution algorithm — a port of `uni.Resolver` and the conversion
//! functions in `uni.PathsUtils`.
//!
//! Two directions:
//!   - [`resolve_pathstr`] — anything to a syntactically valid Windows path
//!     (`uni.Paths.get`). Not necessarily absolute, matching the Scala.
//!   - [`posix_abs`] — anything to an absolute POSIX path (`cygpath -u`).
//!
//! Both work on strings and hand back strings; a `PathBuf` is built only at the
//! very end by the caller, exactly as `uni` defers to `JPaths.get(result)`. Doing
//! it the other way round loses information, because `std::path` on Windows will
//! happily reinterpret a POSIX mount path as a relative one.

use crate::upath::join_posix;
use crate::upath::no_trailing_slash;
use crate::upath::normalize_posix;
use crate::upath::startsWithIgnoreCase;
use crate::upath::strip_trailing_slash;
use crate::upath::PathContext;
use crate::upath::PathError;

/// The five Windows path shapes, plus root and the reject case.
///
/// `#[non_exhaustive]`: verbatim (`\\?\`) and device (`\\.\`) prefixes are the
/// obvious future additions and should not break a caller's `match`.
#[non_exhaustive]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PathKind {
    /// Exactly `/`.
    Root,
    /// `F:/...` — drive plus separator.
    Absolute,
    /// `//server/share`.
    Unc,
    /// `/usr/bin` — resolved through the mount table.
    Posix,
    /// `./bin`, `bin/x` — left alone.
    Relative,
    /// `F:config/bin` or bare `F:` — relative to *that drive's* current directory.
    DriveRel,
    /// Has a URI scheme; refused rather than guessed at.
    Invalid,
}

/// `Resolver.classify`.
///
/// Order matters: the `://` test comes first so `file://` is refused, and it uses
/// `> 1` so a two-char drive prefix (`C://`) still reads as absolute.
#[must_use]
pub fn classify(p: &str) -> PathKind {
    if p.find("://").is_some_and(|i| i > 1) {
        return PathKind::Invalid;
    }
    if p.starts_with("//") {
        return PathKind::Unc;
    }
    if p == "/" {
        return PathKind::Root;
    }
    let b = p.as_bytes();
    if p.len() >= 2 && b[1] == b':' {
        if p.len() == 2 {
            return PathKind::DriveRel;
        }
        return if b[2] == b'/' || b[2] == b'\\' {
            PathKind::Absolute
        } else {
            // `F:config` — a drive-relative path, not an absolute one. Treating
            // this as absolute is the single most consequential mistake available
            // here; it silently ignores the drive's working directory.
            PathKind::DriveRel
        };
    }
    if p.starts_with('/') {
        return PathKind::Posix;
    }
    PathKind::Relative
}

/// Longest mount prefix of `pathstr` among `keys`, or `None`.
///
/// The boundary check is what makes this a *path* prefix rather than a string
/// prefix: `/us` must not match `/usr/bin`. A first-segment lookup — which is what
/// `/opt/ue/src/platform.rs` does — cannot express a multi-segment mount point at
/// all, and silently picks the shallower of two overlapping mounts.
#[must_use]
pub fn find_prefix(pathstr: &str, keys: &[String]) -> Option<String> {
    let s = strip_trailing_slash(pathstr).to_lowercase();
    let mut best: Option<&String> = None;
    for key in keys {
        let matches = s.starts_with(key.as_str())
            && (s.len() == key.len()
                || s.as_bytes()
                    .get(key.len())
                    .is_some_and(|&c| c == b'/' || c == b':'));
        if matches && best.is_none_or(|b| key.len() > b.len()) {
            best = Some(key);
        }
    }
    best.cloned()
}

/// `Resolver.resolvePathstr`: joins the parts, expands `~`/`.`/`..`, then applies
/// the Windows rules when the context calls for them.
///
/// # Errors
/// Propagates [`PathError`] from the expansion and from classification.
pub fn resolve_pathstr(ctx: &PathContext, first: &str, more: &[&str]) -> Result<String, PathError> {
    let joined = std::iter::once(first)
        .chain(more.iter().copied())
        .collect::<Vec<_>>()
        .join("/")
        .replace('\\', "/");
    let pstr = apply_tilde_and_dots(ctx, &joined)?;
    if ctx.is_windows {
        resolve_windows_pathstr(ctx, &pstr)
    } else {
        Ok(pstr)
    }
}

/// `Resolver.resolveWindowsPathstr`.
///
/// # Errors
/// [`PathError::UriLike`] for a scheme-bearing string.
pub fn resolve_windows_pathstr(ctx: &PathContext, pstr: &str) -> Result<String, PathError> {
    match classify(pstr) {
        PathKind::Invalid => Err(PathError::UriLike(pstr.to_owned())),
        PathKind::Absolute | PathKind::Unc | PathKind::Relative => Ok(pstr.to_owned()),
        PathKind::Root => Ok(ctx.msys_root().to_owned()),
        PathKind::Posix => Ok(resolve_posix(ctx, pstr)),
        PathKind::DriveRel => resolve_drive_rel(ctx, pstr),
    }
}

/// The mounted branch: longest matching mount point, else under the msys root.
fn resolve_posix(ctx: &PathContext, pstr: &str) -> String {
    match find_prefix(pstr, &ctx.posix2win_keys) {
        Some(mount_key) => {
            let target = ctx
                .mounts
                .posix2win
                .get(&mount_key)
                .cloned()
                .unwrap_or_default();
            let trimmed = strip_trailing_slash(pstr);
            let post = &trimmed[mount_key.len().min(trimmed.len())..];
            if post.is_empty() {
                // A bare drive target must keep its separator to stay absolute:
                // `C:` alone is drive-relative, `C:/` is the drive root.
                if target.ends_with(':') {
                    format!("{target}/")
                } else {
                    target
                }
            } else {
                // `post` already begins with '/', so appending one to a bare drive
                // target duplicated it — `/c/Users` produced `C://Users`. Scala got
                // away with it because JPaths.get collapses the pair; there is no
                // such collapser here, so the raw string has to be right.
                format!("{target}{post}")
            }
        }
        None => {
            let root = ctx.mounts.posix2win.get("/").cloned().unwrap_or_default();
            if pstr.starts_with(&root) {
                pstr.to_owned()
            } else {
                format!("{root}{pstr}")
            }
        }
    }
}

/// `Resolver.resolveDriveRelPathstr`: resolve against that drive's own cwd.
fn resolve_drive_rel(ctx: &PathContext, pstr: &str) -> Result<String, PathError> {
    let drive = pstr.chars().next().ok_or_else(|| {
        // classify() guarantees at least two chars, so this is unreachable in
        // practice; returning an error beats an index panic if that ever changes.
        PathError::Unnormalized(pstr.to_owned())
    })?;
    let dir = ctx
        .drive_cwd(drive.to_ascii_lowercase())?
        .replace('\\', "/");
    let bare = dir.strip_suffix('/').unwrap_or(&dir);
    let suffix = &pstr[2..];
    Ok(if suffix.is_empty() {
        dir
    } else {
        format!("{bare}/{suffix}")
    })
}

/// `PathsUtils.applyTildeAndDots`: expands `~`, `.`, `..` and bare filenames.
///
/// Runs on every platform, not just Windows.
///
/// # Errors
/// [`PathError::Unnormalized`] if `raw` still contains a backslash — the Scala
/// asserts the same precondition with `require(!raw.contains('\\'))`.
pub fn apply_tilde_and_dots(ctx: &PathContext, raw: &str) -> Result<String, PathError> {
    if raw.contains('\\') {
        return Err(PathError::Unnormalized(raw.to_owned()));
    }
    if raw.is_empty() || raw == "." {
        return Ok(ctx.user.dir.clone());
    }
    if raw == ".." {
        return Ok(ctx.userdir_parent());
    }
    let dir = &ctx.user.dir;
    match raw.as_bytes()[0] {
        b'~' => Ok(if raw.len() == 1 {
            ctx.user.home.clone()
        } else {
            format!("{}{}", ctx.user.home, &raw[1..])
        }),
        b'.' => Ok(expand_dot(dir, &ctx.userdir_parent(), raw)),
        _ => expand_bare(ctx, raw),
    }
}

/// The `.`-leading cases. The hidden-file branch is the subtle one: `.gitignore`
/// must keep its dot, so it cannot go through the `./foo` path that drops the
/// first character.
fn expand_dot(dir: &str, parent: &str, raw: &str) -> String {
    if let Some(rest) = raw.strip_prefix("./") {
        return format!("{dir}/{rest}");
    }
    if let Some(rest) = raw.strip_prefix("../") {
        let parent = parent.strip_suffix('/').unwrap_or(parent);
        return format!("{parent}/{}", rest.strip_prefix('/').unwrap_or(rest));
    }
    // `.gitignore` and friends
    format!("{}/{raw}", dir.strip_suffix('/').unwrap_or(dir))
}

/// Everything not starting with `~` or `.`.
fn expand_bare(ctx: &PathContext, raw: &str) -> Result<String, PathError> {
    let b = raw.as_bytes();
    // A drive letter belongs to `classify`, which routes it to `resolve_drive_rel` --
    // the one place that knows the per-drive working directory and slash-converts
    // what it finds. Resolving it here got both drive-relative forms wrong: bare
    // `C:` came back as the raw `drive_cwd` string, backslashes and all, so
    // `posix_abs("C:")` produced `/c\munit	est`; and single-segment `C:foo` --
    // having no '/' -- fell through to the bare-filename branch and was glued onto
    // the user directory as `.../uni/C:foo`, with a colon buried inside it.
    //
    // No longer guarded by `is_windows` (0.16.0): drive-lettered shapes pass
    // through under EITHER rule set. Under POSIX rules a colon is semantically an
    // ordinary character, but `X:...` strings denote host-absolute paths in every
    // real corpus, and gluing them onto the working directory produced
    // `/munit/test/C:/...` -- unparseable on the very hosts that create such
    // strings. A genuine POSIX name like `C:x` is reachable as `./C:x`.
    if raw.len() >= 2 && b[1] == b':' {
        return Ok(raw.to_owned());
    }
    // EVERY other relative form resolves against the working directory (0.16.0),
    // not just bare filenames. `a/b` staying relative while `bare.txt` absolutised
    // was an asymmetry with no niche -- `posx` normalises without absolutising for
    // callers who want that -- and it left `posix_abs("a/b")` relative where
    // `UPath::resolve` is always absolute.
    if raw.starts_with('/') {
        Ok(raw.to_owned())
    } else {
        Ok(format!(
            "{}/{raw}",
            ctx.user.dir.strip_suffix('/').unwrap_or(&ctx.user.dir)
        ))
    }
}

/// `PathsUtils.posixAbs`: the reverse direction, to an absolute POSIX path.
///
/// # Errors
/// Propagates [`PathError`] from resolution.
pub fn posix_abs(ctx: &PathContext, raw0: &str) -> Result<String, PathError> {
    // Normalise the input first, as `toPosixAbs` does: separators swapped, runs of
    // them collapsed, trailing slash dropped. Skipping it let `//`-bearing input
    // survive into the answer, so `posix_abs("/usr//bin")` kept the double slash.
    let normalized = normalize_posix(raw0);
    let raw = normalized.as_str();
    if !ctx.is_windows {
        let s = resolve_pathstr(ctx, raw, &[])?;
        return Ok(if s == "/" {
            s
        } else {
            s.strip_suffix('/').unwrap_or(&s).to_owned()
        });
    }
    if raw.starts_with('/') {
        return Ok(no_trailing_slash(raw));
    }
    let cyg_mixed = resolve_pathstr(ctx, raw, &[])?;
    if classify(&cyg_mixed) == PathKind::Relative {
        // A relative path has no absolute POSIX form, and resolution deliberately
        // leaves it alone: `apply_tilde_and_dots` absolutises a bare filename but
        // treats anything with a slash as already a path. Such a value used to
        // reach the cygdrive fallback with no drive letter and fail, so
        // `posix_abs("a/b")` errored while `posix_abs("bare.txt")` succeeded.
        // Preserve it, which is what `cygpath -u a/b` does.
        return Ok(normalize_posix(&cyg_mixed));
    }
    windows_to_posix(ctx, &cyg_mixed)
}

/// Maps an already-resolved Windows path back to POSIX.
fn windows_to_posix(ctx: &PathContext, cyg_mixed: &str) -> Result<String, PathError> {
    let cyg_root = ctx.msys_root();
    // `find_prefix` rather than a bare `starts_with`: `C:/msys64extra` starts with the
    // `C:/msys64` root as a *string* while living somewhere else, and rewriting it as
    // though it were inside produced `extra` — a relative string standing in for an
    // absolute path. `find_prefix` requires the next character to be '/' or ':' and
    // treats an exact match as a match.
    if !cyg_root.is_empty() && find_prefix(cyg_mixed, &[cyg_root.to_lowercase()]).is_some() {
        let rest = &cyg_mixed[cyg_root.len()..];
        // The root itself maps to "/", not to "": dropping the whole prefix leaves
        // nothing, so the root of the filesystem came back as the empty string.
        return Ok(if rest.is_empty() || rest == "/" {
            "/".to_owned()
        } else {
            rest.to_owned()
        });
    }
    match find_prefix(cyg_mixed, &ctx.win2posix_keys) {
        Some(win_prefix) => {
            let suffix = cyg_mixed[win_prefix.len().min(cyg_mixed.len())..]
                .strip_suffix('/')
                .map_or_else(
                    || cyg_mixed[win_prefix.len().min(cyg_mixed.len())..].to_owned(),
                    str::to_owned,
                );
            // First POSIX name wins, so fstab order decides between `/Users` and
            // `/home` for the same Windows directory.
            match ctx
                .mounts
                .win2posix
                .get(&win_prefix)
                .and_then(|v| v.first())
            {
                Some(head) => Ok(join_posix(head, &suffix)),
                None => win_abs_to_posix_abs(cyg_mixed),
            }
        }
        None => win_abs_to_posix_abs(cyg_mixed),
    }
}

/// `PathsUtils.posixRel`: relative to the working directory when it is below it.
///
/// # Errors
/// Propagates [`PathError`] from resolution.
pub fn posix_rel(ctx: &PathContext, raw: &str) -> Result<String, PathError> {
    let cwd = posix_abs(ctx, &ctx.user.dir.clone())?;
    let abs = posix_abs(ctx, raw)?;
    // fold only where the context says paths fold (0.16.0): the unconditional
    // eq_ignore relativised `/home/Phil/x` against a cwd of `/home/phil` on Linux
    // -- a different, legal directory -- silently pointing callers elsewhere
    let is_cwd = if ctx.case_fold {
        abs.eq_ignore_ascii_case(&cwd)
    } else {
        abs == cwd
    };
    if is_cwd {
        return Ok(".".to_owned());
    }
    let with_slash = format!("{cwd}/");
    let below = if ctx.case_fold {
        startsWithIgnoreCase(&abs, &with_slash)
    } else {
        abs.starts_with(&with_slash)
    };
    if below {
        return Ok(abs[with_slash.len()..].to_owned());
    }
    Ok(abs)
}

/// `PathsUtils.winAbsToPosixAbs`: the cygdrive-style fallback, `C:/x` to `/c/x`.
///
/// # Errors
/// [`PathError::NotDriveQualified`] when there is no drive letter, mirroring the
/// Scala `require(cygMixed(1) == ':')`. This is reachable in normal use: a relative
/// path containing a slash (`a/b`) is left alone by resolution, matches no mount
/// prefix, and lands here — so `posix_abs("a/b")` fails rather than inventing an
/// absolute path. Returning the input unchanged instead would silently hand back
/// something that is not absolute.
pub fn win_abs_to_posix_abs(cyg_mixed: &str) -> Result<String, PathError> {
    let b = cyg_mixed.as_bytes();
    if cyg_mixed.len() > 1 && b[1] == b':' {
        let drive = cyg_mixed[..1].to_lowercase();
        return Ok(format!("/{drive}{}", &cyg_mixed[2..]));
    }
    Err(PathError::NotDriveQualified(cyg_mixed.to_owned()))
}

#[cfg(test)]
mod tests {
    use super::apply_tilde_and_dots;
    use super::classify;
    use super::find_prefix;
    use super::posix_abs;
    use super::resolve_pathstr;
    use super::PathKind;
    use crate::upath::PathContext;
    use crate::upath::UserInfo;

    fn keys(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| (*s).to_owned()).collect()
    }

    /// Overlapping mounts: the case a first-segment lookup cannot express.
    fn overlap_ctx(is_windows: bool) -> PathContext {
        PathContext::synthetic(
            &keys(&[
                "C:/msys64 on / type ntfs (binary)",
                "C:/opt on /opt type ntfs (binary)",
                "D:/ue on /opt/ue type ntfs (binary)",
            ]),
            UserInfo::new("liam", "C:/Persons/liam", "C:/munit/test"),
            is_windows,
        )
    }

    #[test]
    fn classify_covers_every_kind() {
        assert_eq!(classify("/"), PathKind::Root);
        assert_eq!(classify("/usr/bin"), PathKind::Posix);
        assert_eq!(classify("C:/x"), PathKind::Absolute);
        assert_eq!(classify(r"C:\x"), PathKind::Absolute);
        assert_eq!(classify("C:"), PathKind::DriveRel);
        // The distinction that matters: a drive letter with no separator is
        // relative to that drive's own working directory, not absolute.
        assert_eq!(classify("F:config/bin"), PathKind::DriveRel);
        assert_eq!(classify("//server/share"), PathKind::Unc);
        assert_eq!(classify("file:///c/tmp"), PathKind::Invalid);
        // `> 1` in the scheme test keeps a two-char drive prefix absolute
        assert_eq!(classify("C://x"), PathKind::Absolute);
        assert_eq!(classify("./x"), PathKind::Relative);
        assert_eq!(classify("x"), PathKind::Relative);
    }

    #[test]
    fn find_prefix_takes_the_longest_match() {
        let k = keys(&["/opt", "/opt/ue", "/"]);
        assert_eq!(find_prefix("/opt/ue/src", &k).as_deref(), Some("/opt/ue"));
        assert_eq!(find_prefix("/opt/other", &k).as_deref(), Some("/opt"));
    }

    #[test]
    fn find_prefix_respects_segment_boundaries() {
        // `/us` must not match `/usr/bin`; only a `/` or `:` may follow a prefix.
        assert_eq!(find_prefix("/usr/bin", &keys(&["/us"])), None);
        assert_eq!(
            find_prefix("/usr/bin", &keys(&["/usr"])).as_deref(),
            Some("/usr")
        );
        assert_eq!(find_prefix("C:/x", &keys(&["c:"])).as_deref(), Some("c:"));
    }

    #[test]
    fn overlapping_mounts_resolve_to_the_deeper_target() {
        let ctx = overlap_ctx(true);
        assert_eq!(resolve_pathstr(&ctx, "/opt", &[]).unwrap(), "C:/opt");
        // A first-segment lookup would yield C:/opt/ue/src here.
        assert_eq!(
            resolve_pathstr(&ctx, "/opt/ue/src", &[]).unwrap(),
            "D:/ue/src"
        );
    }

    #[test]
    fn windows_rules_run_regardless_of_host() {
        // No `cfg!(windows)` in the resolver, so this passes on Linux and macOS too
        // — the property the whole context-as-data design exists to provide.
        let ctx = overlap_ctx(true);
        assert_eq!(resolve_pathstr(&ctx, "/", &[]).unwrap(), "C:/msys64");
        assert_eq!(
            resolve_pathstr(&ctx, "/usr/bin/bash", &[]).unwrap(),
            "C:/msys64/usr/bin/bash"
        );
    }

    #[test]
    fn non_windows_is_pass_through_after_expansion() {
        let ctx = PathContext::synthetic(
            &[],
            UserInfo::new("liam", "/Persons/liam", "/munit/test"),
            false,
        );
        assert_eq!(resolve_pathstr(&ctx, "/usr/bin", &[]).unwrap(), "/usr/bin");
        assert_eq!(resolve_pathstr(&ctx, ".", &[]).unwrap(), "/munit/test");
        assert_eq!(resolve_pathstr(&ctx, "~", &[]).unwrap(), "/Persons/liam");
        assert_eq!(posix_abs(&ctx, "/usr/bin/").unwrap(), "/usr/bin");
    }

    #[test]
    fn tilde_and_dot_expansion() {
        let ctx = overlap_ctx(true);
        let dir = "C:/munit/test";
        assert_eq!(apply_tilde_and_dots(&ctx, "").unwrap(), dir);
        assert_eq!(apply_tilde_and_dots(&ctx, ".").unwrap(), dir);
        assert_eq!(apply_tilde_and_dots(&ctx, "..").unwrap(), "C:/munit");
        assert_eq!(
            apply_tilde_and_dots(&ctx, "./x").unwrap(),
            "C:/munit/test/x"
        );
        assert_eq!(apply_tilde_and_dots(&ctx, "../x").unwrap(), "C:/munit/x");
        assert_eq!(apply_tilde_and_dots(&ctx, "~").unwrap(), "C:/Persons/liam");
        assert_eq!(
            apply_tilde_and_dots(&ctx, "~/s").unwrap(),
            "C:/Persons/liam/s"
        );
        // A hidden file keeps its leading dot rather than losing it to the `./`
        // branch — the subtlest case in the function.
        assert_eq!(
            apply_tilde_and_dots(&ctx, ".gitignore").unwrap(),
            "C:/munit/test/.gitignore"
        );
        // EVERY relative form resolves against the working directory (0.16.0),
        // not just bare filenames.
        assert_eq!(
            apply_tilde_and_dots(&ctx, "bare.txt").unwrap(),
            "C:/munit/test/bare.txt"
        );
        assert_eq!(
            apply_tilde_and_dots(&ctx, "a/b").unwrap(),
            "C:/munit/test/a/b"
        );
    }

    #[test]
    fn backslashes_are_rejected_before_expansion() {
        let ctx = overlap_ctx(true);
        assert!(apply_tilde_and_dots(&ctx, r"a\b").is_err());
    }

    #[test]
    fn relative_paths_absolutise_like_bare_names() {
        // 0.16.0: `a/b` staying relative while `bare.txt` absolutised was an
        // asymmetry with no niche (`posx` normalises without absolutising). Both
        // sides changed together; the path-parity fixture pins it.
        let ctx = overlap_ctx(true);
        assert_eq!(posix_abs(&ctx, "a/b").unwrap(), "/c/munit/test/a/b");
        assert_eq!(
            posix_abs(&ctx, "src/main/scala").unwrap(),
            "/c/munit/test/src/main/scala"
        );
        assert_eq!(
            posix_abs(&ctx, "bare.txt").unwrap(),
            "/c/munit/test/bare.txt"
        );
    }

    // -----------------------------------------------------------------------
    // Drive-relative paths
    // -----------------------------------------------------------------------

    /// `is_windows` is data here, so these run on Linux and macOS too — the Scala
    /// equivalents can only run on Windows, where `isWin` comes from `os.name`.
    fn drive_ctx(is_windows: bool) -> PathContext {
        PathContext::synthetic(
            &keys(&["C:/msys64 on / type ntfs (binary)"]),
            UserInfo::new("liam", "C:/Persons/liam", "C:/munit/test"),
            is_windows,
        )
    }

    #[test]
    fn a_bare_drive_resolves_to_that_drives_working_directory() {
        let ctx = drive_ctx(true);
        assert_eq!(classify("C:"), PathKind::DriveRel);
        assert_eq!(
            resolve_pathstr(&ctx, "C:", &[]).expect("resolves"),
            "C:/munit/test"
        );
    }

    #[test]
    fn a_single_segment_drive_relative_path_resolves() {
        // The case that used to be mangled into `<userdir>/C:foo` and then rejected:
        // it has no slash, so the bare-filename branch claimed it.
        let ctx = drive_ctx(true);
        assert_eq!(classify("C:foo"), PathKind::DriveRel);
        assert_eq!(
            resolve_pathstr(&ctx, "C:foo", &[]).expect("resolves"),
            "C:/munit/test/foo"
        );
    }

    #[test]
    fn a_multi_segment_drive_relative_path_resolves_the_same_way() {
        let ctx = drive_ctx(true);
        assert_eq!(
            resolve_pathstr(&ctx, "C:foo/bar", &[]).expect("resolves"),
            "C:/munit/test/foo/bar"
        );
    }

    #[test]
    fn a_drive_relative_path_never_keeps_a_backslash_or_a_dot() {
        let ctx = drive_ctx(true);
        for input in ["C:", "C:foo", "C:foo/bar"] {
            let win = resolve_pathstr(&ctx, input, &[]).expect("resolves");
            let posix = posix_abs(&ctx, input).expect("posix");
            for out in [&win, &posix] {
                assert!(!out.contains('\\'), "{input} produced backslashes: {out}");
                assert!(!out.ends_with("/."), "{input} left a trailing dot: {out}");
                assert!(!out.contains("/./"), "{input} left an embedded dot: {out}");
            }
        }
    }

    #[test]
    fn a_drive_absolute_path_is_left_alone() {
        let ctx = drive_ctx(true);
        assert_eq!(classify("C:/foo"), PathKind::Absolute);
        assert_eq!(
            resolve_pathstr(&ctx, "C:/foo", &[]).expect("resolves"),
            "C:/foo"
        );
    }

    #[test]
    fn off_windows_a_colon_bearing_name_passes_through_like_every_drive_shape() {
        // Changed 0.16.0: drive-lettered shapes pass through under either rule set
        // -- resolving them against the working directory turned host-absolute
        // Windows strings into `/munit/test/C:/...`, unparseable on the very hosts
        // that create them. A genuine POSIX file named `C:foo` is reachable
        // explicitly, as `./C:foo`.
        let ctx = drive_ctx(false);
        assert_eq!(
            resolve_pathstr(&ctx, "C:foo", &[]).expect("resolves"),
            "C:foo"
        );
        assert_eq!(
            resolve_pathstr(&ctx, "./C:foo", &[]).expect("resolves"),
            "C:/munit/test/C:foo" // drive_ctx keeps a C:-style userdir even under posix rules
        );
    }

    #[test]
    fn apply_tilde_and_dots_passes_drive_letters_through_untouched() {
        // It must not resolve them itself; `classify` owns that decision.
        let ctx = drive_ctx(true);
        for input in ["C:", "C:foo", "C:foo/bar", "C:/foo"] {
            assert_eq!(
                apply_tilde_and_dots(&ctx, input).expect("expands"),
                input,
                "{input} was rewritten"
            );
        }
    }
}

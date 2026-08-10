//! Resolution inputs — a port of `uni.PathsConfig` and its two implementations.
//!
//! Everything the resolver needs is here as plain data, so a test can build a
//! context for a platform it is not running on. `uni` cannot do this: `isWin` comes
//! from `os.name` and its path suites skip off Windows.

use std::sync::Arc;
use std::sync::OnceLock;

use crate::upath::PathError;
use crate::upath::mount::DEFAULT_MSYS_ROOT;
use crate::upath::mount::MountMaps;
use crate::upath::normalize_posix;

/// Set at most once; see [`PathContext::default_context`].
static DEFAULT: OnceLock<Arc<PathContext>> = OnceLock::new();

/// The user identity resolution depends on, matching `uni.UserInfo`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UserInfo {
    pub name: String,
    /// POSIX-normalised home directory — expands `~`.
    pub home: String,
    /// POSIX-normalised working directory — expands `.`, `..` and bare names.
    pub dir: String,
}

impl UserInfo {
    #[must_use]
    pub fn new(name: &str, home: &str, dir: &str) -> Self {
        Self {
            name: name.to_owned(),
            home: normalize_posix(home),
            dir: normalize_posix(dir),
        }
    }
}

/// How to answer "what is the current directory on drive X?".
///
/// Windows keeps this **per process**, exposed as hidden `=X:` environment slots
/// and inherited by children. `GetFullPathNameW("X:.")` — what the JVM's
/// `toAbsolutePath` calls underneath — is defined in terms of those slots, so
/// reading them agrees with the Scala side by construction rather than by luck.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum DriveCwdSource {
    /// Consult this process's own per-drive slots. Production behaviour.
    Process,
    /// `userdir` when its drive matches, else the drive root. Mirrors
    /// `SyntheticPathsConfig.driveCwd`, so fixtures never depend on which drives
    /// the test machine happens to have or has visited.
    Synthetic,
}

/// Everything resolution needs, and nothing it does not.
#[derive(Debug, Clone)]
pub struct PathContext {
    /// Whether to apply the Windows translation rules. A parameter, not
    /// `cfg!(windows)` — see the module docs.
    pub is_windows: bool,
    /// Fold case when comparing whole paths (`relpath`'s cwd test, `relativize`)?
    ///
    /// Synthetic contexts follow the *simulated* platform, so fixtures stay
    /// deterministic on any host; [`from_env`](Self::from_env) applies the host
    /// rule — Windows and macOS fold (their default filesystems are
    /// case-insensitive), Linux compares exactly. Folding there relativised
    /// `/home/Phil/x` against a cwd of `/home/phil`, a different, legal directory.
    pub case_fold: bool,
    pub mounts: MountMaps,
    pub user: UserInfo,
    pub drive_cwd_source: DriveCwdSource,
    /// `posix2win` keys, longest first — the candidate set for `find_prefix`.
    pub(crate) posix2win_keys: Vec<String>,
    /// `win2posix` keys, longest first.
    pub(crate) win2posix_keys: Vec<String>,
}

impl PathContext {
    /// Builds a context from an explicit mount table and user — the form fixtures
    /// use, and the reason the Windows rules are testable on Linux.
    #[must_use]
    pub fn synthetic(mount_lines: &[String], user: UserInfo, is_windows: bool) -> Self {
        let mounts = MountMaps::parse(mount_lines, is_windows);
        // the simulated platform's rule, not the host's: fixtures must not vary by host
        Self::from_parts(is_windows, is_windows, mounts, user, DriveCwdSource::Synthetic)
    }

    /// Builds a context from the running environment. The only function here that
    /// touches the outside world.
    #[must_use]
    pub fn from_env() -> Self {
        let is_windows = cfg!(windows);
        let mounts = if is_windows {
            MountMaps::parse(&discover_mount_lines(), true)
        } else {
            MountMaps::empty()
        };
        let user = UserInfo::new(&env_user_name(), &env_home(), &env_cwd());
        // the host rule: fold where the default filesystem folds
        let case_fold = is_windows || cfg!(target_os = "macos");
        Self::from_parts(is_windows, case_fold, mounts, user, DriveCwdSource::Process)
    }

    fn from_parts(
        is_windows: bool,
        case_fold: bool,
        mounts: MountMaps,
        user: UserInfo,
        drive_cwd_source: DriveCwdSource,
    ) -> Self {
        let posix2win_keys = mounts.posix2win.keys_longest_first();
        let win2posix_keys = mounts.win2posix.keys_longest_first();
        Self {
            is_windows,
            case_fold,
            mounts,
            user,
            drive_cwd_source,
            posix2win_keys,
            win2posix_keys,
        }
    }

    /// The process-wide default context, built from the environment on first use.
    ///
    /// # Why this exists
    ///
    /// Threading a context explicitly is the right default for a library, and every
    /// function here still takes one. But this port's purpose is that a script can be
    /// maintained in Scala and Rust side by side, and `"path".asPath` is among the
    /// most-called things in `uni`'s API. Without a default there is nothing for
    /// `str::as_path` to resolve against, and the Rust mirror of a one-line script
    /// grows a context parameter that the Scala has no counterpart for.
    ///
    /// # What it deliberately is not
    ///
    /// A `OnceLock`, not a lock around a mutable slot. `uni` keeps its config in a
    /// `var` that `withMountLines` swaps at will; reproducing that would put shared
    /// mutable state back into a port that had none, and let one test's mount table
    /// leak into another's. Here the default can be *set once, before first use*, and
    /// is immutable after — see [`PathContext::set_default`]. Tests that need several
    /// tables build explicit contexts instead, which is what every test in this crate
    /// already does.
    pub fn default_context() -> &'static Arc<Self> {
        DEFAULT.get_or_init(|| Arc::new(Self::from_env()))
    }

    /// Installs the default context, if nothing has read or set it yet.
    ///
    /// Returns `false` when a default is already in place, in which case the argument
    /// is discarded and the existing one stands. Call it before touching any `str`
    /// path method — typically once at start-up — or not at all.
    ///
    /// This cannot swap a live default on purpose: code already holding an `Arc` from
    /// [`PathContext::default_context`] would keep the old one, so a "swap" would be
    /// visible to some callers and not others. Explicit contexts are the supported way
    /// to use more than one.
    pub fn set_default(ctx: Self) -> bool {
        DEFAULT.set(Arc::new(ctx)).is_ok()
    }

    /// `PathsConfig.userdirParent`: everything before the last slash, or `/`.
    #[must_use]
    pub fn userdir_parent(&self) -> String {
        match self.user.dir.rfind('/') {
            Some(0) | None => "/".to_owned(),
            Some(i) => self.user.dir[..i].to_owned(),
        }
    }

    /// Windows location of `/`, i.e. `PathsConfig.msysRoot` / `cygRoot`.
    #[must_use]
    pub fn msys_root(&self) -> &str {
        &self.mounts.msys_root
    }

    /// Current directory on `drive`, in the platform's **native** form.
    ///
    /// Native, i.e. backslashes on Windows, matching `PathsConfig.driveCwd`, which
    /// hands back a `java.nio.file.Path` whose `toString` is backslashed there. The
    /// parity fixture's `drivecwd` field records that form, so this has to agree.
    ///
    /// It used to matter more: `apply_tilde_and_dots` once consumed this value
    /// verbatim, so a bare `C:` resolved to `C:\munit\test` with separators no other
    /// path had. That was a defect in both languages, now fixed — drive letters reach
    /// `resolve_drive_rel`, which converts to forward slashes itself. Callers wanting
    /// POSIX form should still convert.
    ///
    /// # Errors
    /// [`PathError::BadDriveLetter`] if `drive` is not `A-Za-z`.
    pub fn drive_cwd(&self, drive: char) -> Result<String, PathError> {
        if !drive.is_ascii_alphabetic() {
            return Err(PathError::BadDriveLetter(drive));
        }
        let upper = drive.to_ascii_uppercase();

        if !self.is_windows {
            // `driveCwd` has no isWin guard: on POSIX `Paths.get("X:.")` does not
            // exist, so it falls through to `Paths.get("X:/")`, which POSIX reads
            // as the relative path `X:`. Returning "" instead would change what
            // `apply_tilde_and_dots` does with a bare `X:` input on Linux.
            return Ok(format!("{upper}:"));
        }

        Ok(to_native(&match self.drive_cwd_source {
            DriveCwdSource::Synthetic => self.synthetic_drive_cwd(upper),
            DriveCwdSource::Process => process_drive_cwd(upper),
        }))
    }

    /// `SyntheticPathsConfig.driveCwd`: the test user's dir when the drive matches,
    /// otherwise the drive root.
    fn synthetic_drive_cwd(&self, upper: char) -> String {
        let matches = self
            .user
            .dir
            .chars()
            .next()
            .is_some_and(|c| c.to_ascii_uppercase() == upper);
        if matches {
            self.user.dir.clone()
        } else {
            format!("{upper}:/")
        }
    }
}

/// Reads the process's per-drive slot for `upper`, falling back to the drive root.
///
/// A drive the process has never visited has no slot, and Windows itself answers
/// with the root in that case — so this is not a degraded fallback, it is the
/// same answer `GetFullPathNameW` gives.
fn process_drive_cwd(upper: char) -> String {
    if current_drive_letter() == Some(upper)
        && let Some(cwd) = std::env::current_dir()
            .ok()
            .and_then(|p| p.to_str().map(|s| s.replace('\\', "/")))
    {
        return cwd;
    }
    match std::env::var_os(format!("={upper}:")) {
        Some(v) => v.to_string_lossy().replace('\\', "/"),
        None => format!("{upper}:/"),
    }
}

/// Renders a forward-slashed Windows path the way `java.nio.file.Path.toString`
/// would: with backslashes.
///
/// Reached only once `is_windows` is known true, so it converts unconditionally
/// rather than consulting `cfg!(windows)` — which would make the Windows rules
/// untestable from Linux and defeat the point of carrying the flag as data.
fn to_native(s: &str) -> String {
    s.replace('/', "\\")
}

fn current_drive_letter() -> Option<char> {
    let cwd = std::env::current_dir().ok()?;
    let s = cwd.to_str()?;
    let mut chars = s.chars();
    let first = chars.next()?;
    (chars.next() == Some(':') && first.is_ascii_alphabetic()).then(|| first.to_ascii_uppercase())
}

// ── Environment discovery (production only) ──────────────────────────────────

fn env_user_name() -> String {
    std::env::var("USERNAME")
        .or_else(|_| std::env::var("USER"))
        .unwrap_or_default()
}

fn env_home() -> String {
    std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .unwrap_or_default()
}

fn env_cwd() -> String {
    std::env::current_dir()
        .ok()
        .and_then(|p| p.to_str().map(str::to_owned))
        .unwrap_or_default()
}

/// Locates a mount table, preferring `mount` output over `/etc/fstab`.
///
/// The order matters and is not a fallback nicety: the two sources describe
/// **different tables**. MSYS2 supplies `/`, the per-drive mounts and `/bin`
/// implicitly, so they appear in `mount` output but not in fstab — reading fstab
/// alone loses the root and every drive. uni parses `mount.exe` for this reason
/// (`MountExe.lines()`), and matching it is what keeps the two ports agreeing on
/// a real machine rather than only on synthetic fixtures.
fn discover_mount_lines() -> Vec<String> {
    if let Some(lines) = mount_command_lines() {
        return lines;
    }
    // fstab carries the custom mounts but *not* the root or the per-drive entries
    // — MSYS2 supplies those implicitly, which is why it alone is not a complete
    // table. Ask `cygpath -m /` for the root and prepend it, as uni originally did
    // before it grew the mount.exe path. A root the fstab declares itself still
    // wins, since later entries override earlier ones.
    let lines = read_fstab();
    let has_root = lines.iter().any(|l| {
        let mut it = l.split_whitespace();
        it.next();
        it.next() == Some("/")
    });
    if has_root {
        return lines;
    }
    match cygpath_root() {
        Some(root) => std::iter::once(format!("{root} / ntfs binary 0 0"))
            .chain(lines)
            .collect(),
        // Nothing found: `MountMaps::parse` synthesises a root at the default MSYS2
        // location rather than leaving `/` unresolvable.
        None => lines,
    }
}

/// Asks `cygpath -m /` where the shell root lives.
///
/// Available under MSYS2, Cygwin and Git-Bash alike, and it answers the root
/// question directly instead of requiring the whole table to be parsed. It gives
/// only the root, though — mounts like `/opt` are invisible to it — so it is a
/// fallback rather than a replacement for `mount` output.
fn cygpath_root() -> Option<String> {
    let out = std::process::Command::new("cygpath")
        .args(["-m", "/"])
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    // cygpath returns a trailing slash ("C:/msys64/"); every other entry in the
    // table is stored without one.
    let root = String::from_utf8_lossy(&out.stdout).trim().to_owned();
    let root = root.strip_suffix('/').unwrap_or(&root);
    (!root.is_empty()).then(|| root.to_owned())
}

/// Runs `mount` and returns its lines, or `None` if it is not available.
///
/// Mirrors `MountExe`: try the command on `PATH`, then the default MSYS2 location.
fn mount_command_lines() -> Option<Vec<String>> {
    for exe in ["mount", &format!("{DEFAULT_MSYS_ROOT}/usr/bin/mount.exe")] {
        let Ok(out) = std::process::Command::new(exe).output() else {
            continue;
        };
        if !out.status.success() {
            continue;
        }
        let lines: Vec<String> = String::from_utf8_lossy(&out.stdout)
            .lines()
            .map(str::trim)
            .filter(|l| l.contains(" on "))
            .map(str::to_owned)
            .collect();
        if !lines.is_empty() {
            return Some(lines);
        }
    }
    None
}

fn read_fstab() -> Vec<String> {
    for candidate in [
        format!("{DEFAULT_MSYS_ROOT}/etc/fstab"),
        "/etc/fstab".to_owned(),
    ] {
        if let Ok(text) = std::fs::read_to_string(&candidate) {
            let lines: Vec<String> = text
                .lines()
                .map(str::trim)
                .filter(|l| !l.is_empty() && !l.starts_with('#'))
                .map(str::to_owned)
                .collect();
            if !lines.is_empty() {
                return lines;
            }
        }
    }
    Vec::new()
}

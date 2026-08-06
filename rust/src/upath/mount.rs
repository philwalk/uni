//! The mount table — a port of `uni.ParseMounts` and `MountMaps`.
//!
//! Two directions, both case-insensitive:
//!   - `win2posix`: a Windows path to **one or more** POSIX mount points. The real
//!     fstab has `c:/users` reachable as both `/Users` and `/home`, so the value is
//!     a list and its order matters — `posixAbs` takes the first.
//!   - `posix2win`: a POSIX mount point to its single Windows path.

use crate::upath::LcMap;

/// Parsed mount table plus the cygdrive prefix derived from it.
#[derive(Debug, Clone)]
pub struct MountMaps {
    /// Prefix under which bare drives appear, e.g. `/` or `/cygdrive`.
    pub cygdrive: String,
    pub win2posix: LcMap<Vec<String>>,
    pub posix2win: LcMap<String>,
    /// Windows location of `/`. Empty when the table has no root entry.
    pub msys_root: String,
}

impl MountMaps {
    /// Parses `mount.exe` output or `/etc/fstab` lines.
    ///
    /// `is_windows` is a parameter, not `cfg!(windows)`: the synthetic drive and
    /// root entries below only exist on Windows, and tests need to exercise that
    /// from any platform.
    #[must_use]
    pub fn parse(lines: &[String], is_windows: bool) -> Self {
        let entries = normalized_entries(lines);
        let cygdrive = derive_cygdrive(&entries);

        // A `none` device is a marker, not a mountable path: fstab writes
        // `none / cygdrive ...` (MSYS2) or `none /cygdrive cygdrive ...` (Cygwin)
        // purely to declare the prefix above — the shipped line's own comment says
        // "It removes cygdrive prefix from path". It has already informed
        // `cygdrive`, and letting it reach the maps makes `/` resolve to the
        // literal string "none". `ParseMounts` filters it the same way.
        let entries: Vec<(String, String)> = entries
            .into_iter()
            .filter(|(win, _)| win != "none")
            .collect();

        let mut all = entries.clone();
        if is_windows {
            all.extend(synthetic_drives(&entries, &cygdrive));
            all.extend(synthetic_root(&entries));
        }
        // Re-normalise and de-duplicate, preserving first-seen order so the
        // one-to-many `win2posix` values keep fstab order.
        let all = dedup_normalized(all);

        let posix2win = LcMap::from_pairs(all.iter().map(|(w, p)| (p.clone(), w.clone())));
        let win2posix = group_by_windows_path(&all);
        let msys_root = posix2win.get("/").cloned().unwrap_or_default();

        Self {
            cygdrive,
            win2posix,
            posix2win,
            msys_root,
        }
    }

    /// An empty table, for non-Windows contexts where nothing is mounted.
    #[must_use]
    pub fn empty() -> Self {
        Self {
            cygdrive: "/".to_owned(),
            win2posix: LcMap::default(),
            posix2win: LcMap::default(),
            msys_root: String::new(),
        }
    }
}

/// Default MSYS2 install location, matching `MountExe.defaultMsysRoot`.
pub const DEFAULT_MSYS_ROOT: &str = "C:/msys64";

/// `\` to `/`, then drop a trailing slash — except from `/` itself.
fn strip_slash(s: &str) -> String {
    let s = s.replace('\\', "/");
    if s == "/" {
        s
    } else {
        s.strip_suffix('/').unwrap_or(&s).to_owned()
    }
}

/// Splits both accepted formats into `(windows, posix)` pairs.
///
/// `mount.exe` prints `C:/msys64 on / type ntfs (binary)`; fstab uses whitespace
/// columns. The ` on ` test is what picks between them, as in the Scala.
fn normalized_entries(lines: &[String]) -> Vec<(String, String)> {
    lines
        .iter()
        .map(|l| l.trim())
        // Comments and blanks. Only fstab has them, but MSYS2's shipped file has
        // 16 — including commented-out example mounts written with no space after
        // the `#`, which otherwise parse as live entries whose Windows side starts
        // with '#' and poison every path built from them.
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .filter_map(|line| {
            let (w, p) = if line.contains(" on ") {
                let mut it = line.split(" on ").map(str::trim);
                let w = it.next()?;
                // ` type ` follows the mount point in mount.exe output
                let rest = it.next()?;
                let p = rest.split(" type ").next()?.trim();
                (w, p)
            } else {
                let mut it = line.split_whitespace();
                (it.next()?, it.next()?)
            };
            if w.is_empty() || p.is_empty() {
                None
            } else {
                Some((strip_slash(w), strip_slash(p)))
            }
        })
        .collect()
}

fn is_drive_root(s: &str) -> bool {
    let b = s.as_bytes();
    s.len() == 2 && b[1] == b':' && b[0].is_ascii_alphabetic()
}

/// Derives the cygdrive prefix from the table, as `ParseMounts` does.
///
/// Either an explicit `none` device (Cygwin writes `none /cygdrive`), or inferred
/// from a drive-root entry whose POSIX side is the prefix plus one letter.
fn derive_cygdrive(entries: &[(String, String)]) -> String {
    for (win, posix) in entries {
        if win == "none" {
            return format!("{}/", posix.strip_suffix('/').unwrap_or(posix));
        }
        let b = posix.as_bytes();
        if is_drive_root(win)
            && posix.starts_with('/')
            && posix.len() >= 3
            && b[posix.len() - 2] == b'/'
        {
            return posix[..posix.len() - 1].to_owned();
        }
    }
    "/".to_owned()
}

/// Synthetic `X: -> <cygdrive>x` entries for every drive letter the table does not
/// already reach.
///
/// This is why `/q/file` resolves to `Q:/file` on a machine whose fstab never
/// mentions Q:. Without it, unmapped drives fall through to the msys-root branch
/// and resolve under `C:/msys64`.
fn synthetic_drives(entries: &[(String, String)], cygdrive: &str) -> Vec<(String, String)> {
    let is_real_drive = |posix: &str| {
        posix.len() == cygdrive.len() + 1
            && posix
                .chars()
                .next_back()
                .is_some_and(|c| posix == format!("{cygdrive}{c}"))
    };
    let mapped: std::collections::HashSet<char> = entries
        .iter()
        .filter(|(_, posix)| is_real_drive(posix))
        .filter_map(|(_, posix)| posix.chars().next_back())
        .map(|c| c.to_ascii_lowercase())
        .collect();

    ('A'..='Z')
        .filter(|d| !mapped.contains(&d.to_ascii_lowercase()))
        .map(|d| {
            (
                format!("{d}:"),
                format!("{cygdrive}{}", d.to_ascii_lowercase()),
            )
        })
        .collect()
}

/// A root entry if the table lacks one, so `/` always resolves somewhere.
fn synthetic_root(entries: &[(String, String)]) -> Vec<(String, String)> {
    if entries.iter().any(|(_, p)| p == "/") {
        Vec::new()
    } else {
        vec![(DEFAULT_MSYS_ROOT.to_owned(), "/".to_owned())]
    }
}

/// Re-normalise and drop duplicates, keeping first-seen order.
fn dedup_normalized(all: Vec<(String, String)>) -> Vec<(String, String)> {
    let mut seen = std::collections::HashSet::new();
    all.into_iter()
        .map(|(w, p)| (strip_slash(&w), strip_slash(&p)))
        .filter(|pair| seen.insert(pair.clone()))
        .collect()
}

/// Groups POSIX mount points by their Windows path, preserving order within a
/// group so `posixAbs`'s "take the first" picks the same one the JVM does.
fn group_by_windows_path(all: &[(String, String)]) -> LcMap<Vec<String>> {
    let mut order: Vec<String> = Vec::new();
    let mut groups: std::collections::HashMap<String, Vec<String>> =
        std::collections::HashMap::new();
    for (win, posix) in all {
        let key = win.to_lowercase();
        let slot = groups.entry(key.clone()).or_insert_with(|| {
            order.push(key.clone());
            Vec::new()
        });
        if !slot.contains(posix) {
            slot.push(posix.clone());
        }
    }
    LcMap::from_pairs(order.into_iter().map(|k| {
        let v = groups.remove(&k).unwrap_or_default();
        (k, v)
    }))
}

#[cfg(test)]
mod tests {
    use super::MountMaps;

    fn lines(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| (*s).to_owned()).collect()
    }

    #[test]
    fn parses_both_table_formats() {
        let from_mount_exe = MountMaps::parse(&lines(&["C:/msys64 on / type ntfs (binary)"]), true);
        let from_fstab = MountMaps::parse(&lines(&["C:/msys64 / ntfs binary 0 0"]), true);
        assert_eq!(from_mount_exe.msys_root, "C:/msys64");
        assert_eq!(from_fstab.msys_root, "C:/msys64");
    }

    #[test]
    fn derives_cygdrive_from_a_none_device() {
        let m = MountMaps::parse(
            &lines(&[
                "none on /cygdrive type ntfs (binary)",
                "C:/cygwin64 on / type ntfs (binary)",
            ]),
            true,
        );
        assert_eq!(m.cygdrive, "/cygdrive/");
    }

    #[test]
    fn synthesizes_entries_for_unmapped_drives() {
        // Why `/q/file` resolves to Q:/file on a machine whose fstab never
        // mentions Q:.
        let m = MountMaps::parse(&lines(&["C:/msys64 on / type ntfs (binary)"]), true);
        assert_eq!(m.posix2win.get("/q").map(String::as_str), Some("Q:"));
    }

    #[test]
    fn synthesizes_a_root_when_the_table_has_none() {
        let m = MountMaps::parse(&lines(&["C:/opt /opt ntfs binary 0 0"]), true);
        assert_eq!(m.msys_root, super::DEFAULT_MSYS_ROOT);
    }

    #[test]
    fn no_synthetic_entries_off_windows() {
        let m = MountMaps::parse(&lines(&["C:/opt /opt ntfs binary 0 0"]), false);
        assert!(m.msys_root.is_empty());
        assert!(!m.posix2win.contains("/q"));
    }

    #[test]
    fn one_windows_path_can_have_several_posix_names_in_order() {
        // fstab order decides which name the reverse direction reports.
        let m = MountMaps::parse(
            &lines(&[
                "C:/msys64 on / type ntfs (binary)",
                "C:/Users on /Users type ntfs (binary)",
                "C:/Users on /home type ntfs (binary)",
            ]),
            true,
        );
        assert_eq!(
            m.win2posix.get("c:/users").map(Vec::as_slice),
            Some(["/Users".to_owned(), "/home".to_owned()].as_slice())
        );
    }
}

//! Directory listing and tree walking — a port of the `files`/`paths`/`pathsTree` family in
//! `uni.ext.PathExts`.
//!
//! # Order is specified, and identical to the Scala
//!
//! `File.listFiles`, `Files.walk` and Rust's `read_dir` all promise **no** sibling order — it is
//! whatever the filesystem hands back, roughly alphabetical on NTFS and arbitrary on ext4. So the
//! same script listed a directory differently on Linux and Windows, and no fixture could pin it.
//!
//! Both languages now sort by the same key: **case-insensitive, then case-sensitive as a
//! tiebreak**. That yields `(a.txt, B.txt)` rather than `(B.txt, a.txt)`, which is what a reader
//! expects, while the second component keeps the order total where two names differ only in case
//! — possible on Linux, not on Windows.
//!
//! # Why not Java's own `Path` ordering
//!
//! `Seq[Path].sorted` uses `Path.compareTo`, which is **case-insensitive on Windows and
//! case-sensitive on Linux**. That could have been matched here — `PathContext::is_windows` is
//! data, and [`UPath::isSameFile`] already folds case that way — so this is a choice, not a
//! limitation. Two reasons against it:
//!
//! - **One order everywhere.** A script that lists a directory produces the same order on Linux
//!   and Windows, so generated files, reports and diffs are comparable across machines.
//! - **One fixture.** Platform-dependent ordering would split `test-data/walk-parity/` into
//!   per-platform references, which is what `test-data/path-parity/` had to do — it carries
//!   separate `scala-reference-windows.txt` and `-posix.txt` files because the path *rules* really
//!   are platform-specific. Sort order does not have to be.
//!
//! Locale-sensitive lowering is avoided for a plainer reason: Java's `toLowerCase()` with no
//! locale maps `I` to a dotless `i` under a Turkish locale, so listing order would depend on where
//! the machine thinks it is. The Scala passes `Locale.ROOT`; `to_lowercase` here has no locale to
//! get wrong.
//!
//! [`UPath::walkIter`] is the exception and stays unsorted — sorting needs the whole listing,
//! which is what its laziness exists to avoid.
//!
//! # Semantics worth getting right
//!
//! - A missing path, or one that is not a directory, lists **empty** rather than erroring —
//!   `listFiles` returns `null` there and the Scala maps that to an empty iterator.
//! - [`UPath::pathsTree`] **includes the root itself**, because `Files.walk(p)` yields `p` first.
//!   Easy to miss, and it changes every count by one.
//! - The walk is **pre-order** depth-first: a directory appears before its contents.
//! - The walk does **not follow symlinks**. `Files.walk` without `FOLLOW_LINKS` lists a symlinked
//!   directory but does not descend into it, so neither does this — which is also what stops a
//!   cyclic link from hanging the traversal.
//! - [`UPath::subfiles`] filters on *regular* files, not "not a directory", so a device or socket
//!   is excluded. Symlinks to regular files are included, since `isRegularFile` follows links.
//!
//! # `files` and `paths` are the same thing here
//!
//! In Scala they differ only in element type — `Seq[java.io.File]` versus `Seq[Path]`. Rust has
//! no second path type, so both return `Vec<UPath>`. `files` is kept as an alias so a script
//! ported from Scala compiles unchanged.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name, so a script kept in both \
              languages needs no mental translation. Internal helpers and Rust trait \
              contracts stay snake_case, so the case says whether a Scala counterpart exists."
)]

use std::fs;
use std::path::PathBuf;

use crate::upath::UPath;

/// The listing sort key: case-insensitive, then case-sensitive as a tiebreak.
///
/// Must stay identical to `PathExts.listOrder` on the Scala side, or the two languages order
/// listings differently and `test-data/walk-parity/` fails.
fn list_order(p: &UPath) -> (String, String) {
    let s = p.posx().to_owned();
    (s.to_lowercase(), s)
}

impl UPath {
    /// The immediate entries of this directory, sorted. See the module docs on the order.
    ///
    /// Empty when the path does not exist or is not a directory, mirroring `listFiles`
    /// returning `null` there.
    #[must_use]
    pub fn paths(&self) -> Vec<Self> {
        let Ok(entries) = fs::read_dir(self.as_std_path()) else {
            return Vec::new();
        };
        let mut v: Vec<Self> = entries
            .filter_map(Result::ok)
            .filter_map(|e| self.sibling(&e.path()))
            .collect();
        v.sort_by_key(list_order);
        v
    }

    /// Alias for [`Self::paths`]. In Scala this returns `Seq[java.io.File]`; Rust has no second
    /// path type, so the two coincide.
    #[must_use]
    pub fn files(&self) -> Vec<Self> {
        self.paths()
    }

    /// The immediate subdirectories, in unspecified order.
    #[must_use]
    pub fn subdirs(&self) -> Vec<Self> {
        self.paths().into_iter().filter(Self::isDirectory).collect()
    }

    /// The immediate *regular* files, in unspecified order.
    ///
    /// Regular files, not "everything that is not a directory" — the Scala filters on
    /// `Files.isRegularFile`, so a device or socket is excluded.
    #[must_use]
    pub fn subfiles(&self) -> Vec<Self> {
        self.paths().into_iter().filter(Self::isFile).collect()
    }

    /// Every path in the tree rooted here, **including this path itself**, pre-order.
    ///
    /// Matches `Files.walk(p)`: the root comes first, a directory precedes its contents, and
    /// symlinks are listed but not descended into. Sibling order is unspecified.
    ///
    /// Empty when the path does not exist. A path that exists but is not a directory yields just
    /// itself, as `Files.walk` does.
    #[must_use]
    pub fn pathsTree(&self) -> Vec<Self> {
        // Sorted by the same key as `paths`. A full sort of the flattened walk keeps every parent
        // before its descendants -- a path is a prefix of its children, so it compares smaller --
        // which preserves the one ordering guarantee `Files.walk` made.
        let mut v: Vec<Self> = self.walkIter().collect();
        v.sort_by_key(list_order);
        v
    }

    /// Alias for [`Self::walkIter`], matching the Scala `walk`.
    ///
    /// Lazy and in walk order, **not** the sorted [`Self::pathsTree`]. Scala's `walk` is an alias
    /// of `pathsTreeIter` for the same reason: the `Iter` spelling and the lazy spelling have to
    /// agree across both languages or a ported line changes meaning silently.
    pub fn walk(&self) -> TreeWalk {
        self.walkIter()
    }

    /// This directory, lazily, in filesystem order. `PathExts.pathsIter`.
    ///
    /// Yields the first entry as soon as `read_dir` returns it instead of waiting for the whole
    /// listing, which is what makes a slow USB or network directory usable. [`Self::paths`] cannot
    /// do that: it sorts, and a sort has to see everything first. Each spelling buys one of the two.
    ///
    /// Unreadable entries are skipped rather than failing the listing, as in [`Self::paths`].
    #[must_use]
    pub fn pathsIter(&self) -> DirIter {
        DirIter {
            inner: fs::read_dir(self.as_std_path()).ok(),
            parent: self.clone(),
        }
    }

    /// Alias for [`Self::pathsIter`]. `PathExts.filesIter`; Rust has no second path type.
    #[must_use]
    pub fn filesIter(&self) -> DirIter {
        self.pathsIter()
    }

    /// Applies `f` to each directory entry. `PathExts.eachPath`.
    ///
    /// In Scala this is the *safe* form: `pathsIter` holds a directory handle that leaks if
    /// the iterator is abandoned, and `eachPath` scopes the close. [`DirIter`] closes on
    /// drop, so here the method adds nothing over `pathsIter().for_each(f)` -- it exists so
    /// a line written against the Scala ports unchanged, the same contract as `asFile`.
    pub fn eachPath(&self, f: impl FnMut(Self)) {
        self.pathsIter().for_each(f);
    }

    /// The tree walk as a lazy iterator — the counterpart to Scala's `pathsTreeIter`.
    ///
    /// Explicit stack rather than recursion, so a deep tree cannot overflow, and so the caller
    /// can stop early without having listed the rest.
    pub fn walkIter(&self) -> TreeWalk {
        TreeWalk {
            root: self.clone(),
            stack: if self.exists() {
                vec![self.as_std_path()]
            } else {
                Vec::new()
            },
        }
    }

    /// Alias for [`Self::walkIter`]: the Scala spelling `pathsTreeIter`, kept so the lazy tree
    /// walk is greppable under both names.
    #[must_use]
    pub fn pathsTreeIter(&self) -> TreeWalk {
        self.walkIter()
    }

    /// A `UPath` for `child`, in this path's resolution context.
    ///
    /// Returns `None` when the child cannot be resolved, which drops the entry rather than
    /// failing the whole listing — a directory holding one undecodable name should still list.
    fn sibling(&self, child: &std::path::Path) -> Option<Self> {
        let s = child.to_string_lossy().replace('\\', "/");
        Self::resolve(self.ctx(), &s).ok()
    }
}

/// Pre-order depth-first walk, yielding the root first. See [`UPath::walkIter`].
/// A lazy directory listing. See [`UPath::pathsIter`].
///
/// Drops its handle when exhausted or dropped, so an abandoned listing releases the directory --
/// the Scala equivalent must be closed, which is why `eachPath` exists beside it there.
pub struct DirIter {
    /// `None` once exhausted, or from the start when the path was not a readable directory.
    inner: Option<fs::ReadDir>,
    /// Kept so each entry resolves in the same context as its parent.
    parent: UPath,
}

impl Iterator for DirIter {
    type Item = UPath;

    fn next(&mut self) -> Option<UPath> {
        let entries = self.inner.as_mut()?;
        loop {
            match entries.next() {
                None => {
                    self.inner = None;
                    return None;
                }
                // A single unreadable entry drops out; the rest of the directory still lists.
                Some(Err(_)) => continue,
                Some(Ok(entry)) => {
                    if let Some(p) = self.parent.sibling(&entry.path()) {
                        return Some(p);
                    }
                }
            }
        }
    }
}

pub struct TreeWalk {
    /// Kept for its resolution context, so every yielded path shares the root's.
    root: UPath,
    /// Paths still to visit, in reverse order so `pop` yields the next one.
    stack: Vec<PathBuf>,
}

impl Iterator for TreeWalk {
    type Item = UPath;

    fn next(&mut self) -> Option<Self::Item> {
        loop {
            let current = self.stack.pop()?;
            // Push children before yielding, so the next `pop` continues depth-first. Reversed,
            // so entries come back in the order `read_dir` gave them rather than backwards.
            //
            // `symlink_metadata`, not `metadata`: a symlinked directory must be listed without
            // being descended into, matching `Files.walk` without FOLLOW_LINKS. That is also
            // what keeps a cyclic link from looping forever.
            let is_real_dir = fs::symlink_metadata(&current)
                .map(|m| m.is_dir())
                .unwrap_or(false);
            if is_real_dir && let Ok(entries) = fs::read_dir(&current) {
                let mut children: Vec<PathBuf> =
                    entries.filter_map(Result::ok).map(|e| e.path()).collect();
                children.reverse();
                self.stack.extend(children);
            }
            let s = current.to_string_lossy().replace('\\', "/");
            if let Ok(p) = UPath::resolve(self.root.ctx(), &s) {
                return Some(p);
            }
            // Undecodable entry: skip it and continue rather than ending the walk.
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use crate::upath::PathContext;
    use crate::upath::UPath;
    use crate::upath::UserInfo;

    fn root_of(dir: &std::path::Path) -> UPath {
        let d = dir.to_string_lossy().replace('\\', "/");
        let ctx = Arc::new(PathContext::synthetic(
            &[],
            UserInfo::new("tester", &d, &d),
            cfg!(windows),
        ));
        UPath::resolve(&ctx, &d).unwrap_or_else(|e| panic!("resolve: {e}"))
    }

    /// `a/`, `a/b/`, `a/b/deep.txt`, `a/one.txt`, `a/two.txt`
    fn fixture() -> (tempfile::TempDir, UPath) {
        let tmp = tempfile::tempdir().unwrap_or_else(|e| panic!("tempdir: {e}"));
        let a = tmp.path().join("a");
        std::fs::create_dir_all(a.join("b")).unwrap_or_else(|e| panic!("mkdir: {e}"));
        std::fs::write(a.join("one.txt"), b"1").unwrap_or_else(|e| panic!("write: {e}"));
        std::fs::write(a.join("two.txt"), b"22").unwrap_or_else(|e| panic!("write: {e}"));
        std::fs::write(a.join("b/deep.txt"), b"333").unwrap_or_else(|e| panic!("write: {e}"));
        let root = root_of(&a);
        (tmp, root)
    }

    fn names(v: &[UPath]) -> Vec<String> {
        let mut out: Vec<String> = v
            .iter()
            .map(|p| p.posx().rsplit('/').next().unwrap_or("").to_owned())
            .collect();
        out.sort();
        out
    }

    #[test]
    fn lists_immediate_entries_only() {
        let (_tmp, root) = fixture();
        assert_eq!(names(&root.paths()), vec!["b", "one.txt", "two.txt"]);
        assert_eq!(names(&root.files()), names(&root.paths()), "files aliases paths");
        assert_eq!(names(&root.subdirs()), vec!["b"]);
        assert_eq!(names(&root.subfiles()), vec!["one.txt", "two.txt"]);
    }

    #[test]
    fn tree_includes_the_root_itself() {
        let (_tmp, root) = fixture();
        let tree = root.pathsTree();
        // 5 entries: the root, b, deep.txt, one.txt, two.txt. Forgetting the root is the classic
        // off-by-one against `Files.walk`.
        assert_eq!(names(&tree), vec!["a", "b", "deep.txt", "one.txt", "two.txt"]);
        // `walk` is the lazy alias now, so it matches `walkIter` and only matches
        // `pathsTree` as a set -- the sort is what `pathsTree` adds.
        let walked: Vec<UPath> = root.walk().collect();
        assert_eq!(names(&walked), names(&root.walkIter().collect::<Vec<_>>()));
        let mut a = names(&walked);
        let mut b = names(&tree);
        a.sort_unstable();
        b.sort_unstable();
        assert_eq!(a, b, "same entries as pathsTree, order aside");
    }

    #[test]
    fn walk_is_preorder_parent_before_children() {
        let (_tmp, root) = fixture();
        let order: Vec<String> = root
            .walkIter()
            .map(|p| p.posx().rsplit('/').next().unwrap_or("").to_owned())
            .collect();
        let idx = |n: &str| {
            order
                .iter()
                .position(|x| x == n)
                .unwrap_or_else(|| panic!("{n} missing from {order:?}"))
        };
        assert_eq!(order.first().map(String::as_str), Some("a"), "root first");
        assert!(idx("b") < idx("deep.txt"), "parent before child: {order:?}");
    }

    #[test]
    fn missing_and_non_directory_paths_behave_like_the_scala() {
        let (tmp, root) = fixture();
        let missing = root_of(&tmp.path().join("nope"));
        assert!(missing.paths().is_empty(), "missing path lists empty");
        assert!(missing.pathsTree().is_empty(), "missing path walks empty");

        // A path that exists but is not a directory: empty listing, but the walk yields itself.
        let one = root
            .paths()
            .into_iter()
            .find(|p| p.posx().ends_with("one.txt"))
            .unwrap_or_else(|| panic!("fixture missing one.txt"));
        assert!(one.paths().is_empty(), "a file lists empty");
        assert_eq!(names(&one.pathsTree()), vec!["one.txt"], "a file walks to itself");
    }

    #[test]
    fn walk_iter_is_lazy_enough_to_stop_early() {
        let (_tmp, root) = fixture();
        // Taking one item must not require listing the tree; asserting the root comes back is the
        // observable part, the point being that this does not collect first.
        let first: Vec<UPath> = root.walkIter().take(1).collect();
        assert_eq!(names(&first), vec!["a"]);
    }
}

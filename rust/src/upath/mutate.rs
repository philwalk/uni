//! Copy, move, delete and directory creation — a port of the mutating methods in
//! `uni.ext.PathExts`.
//!
//! # Every flag is explicit here, and that is a fix rather than a limitation
//!
//! The Scala defaults are inconsistent: `copyTo(dest, overwrite = true)` clobbers by default,
//! while `renameTo`, `renameToOpt` and `renameViaCopy` all default to `overwrite = false`. So
//! `copyTo(x)` overwrites and `renameTo(x)` does not, which is not something a reader can guess.
//!
//! Rust has no default arguments, so every call names the flag. That makes the asymmetry
//! impossible to trip over, at the cost of slightly longer call sites — a good trade for
//! operations that destroy data.
//!
//! # Failure: `try_*` is faithful, the short name is convenient
//!
//! Scala distinguishes three outcomes for `delete()`: `true` deleted, `false` did not exist, and
//! *thrown* if deletion genuinely failed on permissions or a lock. A Rust `-> bool` cannot say
//! all three, and silently reporting `false` for a permission error would be worse than the
//! Scala, which fails loudly.
//!
//! So each operation comes in two forms, matching the convention already in [`crate::upath::io`]:
//! `try_delete() -> io::Result<bool>` keeps the distinction, and `delete() -> bool` collapses it
//! for callers who do not care. The `try_` prefix is a Rust idiom with no Scala counterpart, so
//! those stay snake_case.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name, so a script kept in both \
              languages needs no mental translation. Internal helpers, Rust trait contracts and \
              the `try_*` Result variants stay snake_case, so the case says whether a Scala \
              counterpart exists."
)]

use std::fs;
use std::io;
use std::io::Write as _;

use crate::upath::UPath;
use crate::upath::io::Charset;

impl UPath {
    /// Copies this file to `dest`, returning `dest`.
    ///
    /// `overwrite` is required, and so is the Scala's as of 0.16.0 -- the default was removed there
    /// so the compiler flags every call site rather than letting a silent clobber through. The two
    /// signatures now agree; this comment previously claimed a divergence that no longer exists.
    ///
    /// `copyAttributes` covers the modification time and the permission bits, which is what
    /// `COPY_ATTRIBUTES` reaches for in practice. It does **not** carry owner or ACLs: `std`
    /// exposes neither, and silently dropping them while claiming to copy attributes would be
    /// worse than saying so.
    ///
    /// Measured on Windows, the flag makes no observable difference to the modification time:
    /// both `Files.copy` and `fs::copy` go through `CopyFileEx`, which preserves timestamps
    /// whatever is asked. The two languages agree, so the flag is honoured where the platform
    /// honours it and is redundant where the platform does not -- do not read a difference
    /// between them into a same-timestamp result.
    ///
    /// With `overwrite` false the destination is created with `create_new`, so the existence
    /// check is the kernel's and there is no window in which another process could create the
    /// file between a check and the write.
    ///
    /// # Errors
    ///
    /// The underlying copy failing, or `dest` existing with `overwrite` false — the latter
    /// mirroring `Files.copy` without `REPLACE_EXISTING`, which throws
    /// `FileAlreadyExistsException` rather than returning quietly.
    pub fn try_copyTo(&self, dest: &Self, overwrite: bool, copyAttributes: bool) -> io::Result<Self> {
        if overwrite {
            fs::copy(self.as_std_path(), dest.as_std_path())?;
        } else {
            // `create_new` maps to O_EXCL / CREATE_NEW, so the "does it already exist" question is
            // answered by the kernel as part of the create. Checking `dest.exists()` first and
            // then calling `fs::copy` -- which always overwrites -- leaves a TOCTOU window in
            // which another process creates the file and this clobbers it anyway.
            let mut out = fs::File::options()
                .write(true)
                .create_new(true)
                .open(dest.as_std_path())?;
            let mut src = fs::File::open(self.as_std_path())?;
            io::copy(&mut src, &mut out)?;
            out.flush()?;
        }
        if copyAttributes {
            let meta = fs::metadata(self.as_std_path())?;
            // Permissions first: setting the time and then the mode would have the mode write
            // update the time again on some filesystems.
            fs::set_permissions(dest.as_std_path(), meta.permissions())?;
            if let Ok(modified) = meta.modified() {
                fs::File::options()
                    .write(true)
                    .open(dest.as_std_path())?
                    .set_modified(modified)?;
            }
        }
        Ok(dest.clone())
    }

    /// Copies this file to `dest`, returning `dest`, or `None` on failure.
    ///
    /// The convenience form of [`Self::try_copyTo`]. The Scala throws where this yields `None`.
    #[must_use]
    pub fn copyTo(&self, dest: &Self, overwrite: bool, copyAttributes: bool) -> Option<Self> {
        self.try_copyTo(dest, overwrite, copyAttributes).ok()
    }

    /// Moves this path to `other`, returning the new path.
    ///
    /// Mirrors `renameToOpt`: `None` when the source does not exist, when `other` exists and
    /// `overwrite` is false, or when the move itself fails. Same-filesystem moves are atomic;
    /// across filesystems `fs::rename` fails, and [`Self::renameViaCopy`] is the fallback — as
    /// in Scala.
    #[must_use]
    pub fn renameToOpt(&self, other: &Self, overwrite: bool) -> Option<Self> {
        if !self.exists() || (!overwrite && other.exists()) {
            return None;
        }
        // `fs::rename` replaces an existing destination on both Unix and Windows, so the guard
        // above is what implements `overwrite = false` -- there is no non-replacing variant to
        // select, unlike Java's `Files.move` without REPLACE_EXISTING.
        fs::rename(self.as_std_path(), other.as_std_path())
            .ok()
            .map(|()| other.clone())
    }

    /// Whether the move succeeded. `renameToOpt(...).isDefined`, as in Scala.
    #[must_use]
    pub fn renameTo(&self, other: &Self, overwrite: bool) -> bool {
        self.renameToOpt(other, overwrite).is_some()
    }

    /// Move by copy-then-delete, which works across filesystems where a rename cannot.
    ///
    /// Returns `0` on success and `-1` on failure, mirroring the Scala exactly. An `i32` status
    /// rather than a `Result` is not how this would be written from scratch, but it is the
    /// contract client code was compiled against.
    ///
    /// Note the copy step replaces an existing destination unconditionally once the guard has
    /// passed — again mirroring the Scala, which passes `REPLACE_EXISTING` there.
    #[must_use]
    pub fn renameViaCopy(&self, newFile: &Self, overwrite: bool) -> i32 {
        if !self.exists() || (newFile.exists() && !overwrite) {
            return -1;
        }
        match fs::copy(self.as_std_path(), newFile.as_std_path()) {
            Ok(_) => match fs::remove_file(self.as_std_path()) {
                Ok(()) => 0,
                // The copy landed but the source survives. `-1` matches the Scala, which reports
                // the same for a failed delete -- worth knowing that a `-1` does not imply
                // nothing happened.
                Err(_) => -1,
            },
            Err(_) => -1,
        }
    }

    /// Deletes the path if it exists.
    ///
    /// `Ok(true)` deleted, `Ok(false)` did not exist, `Err` the deletion failed. Mirrors
    /// `Files.deleteIfExists`, including that an empty directory can be deleted this way and a
    /// non-empty one cannot.
    ///
    /// # Errors
    ///
    /// Permissions, a lock, or a non-empty directory.
    pub fn try_delete(&self) -> io::Result<bool> {
        // `symlink_metadata`: a symlink to a directory must be removed as a link, not descended.
        let Ok(meta) = fs::symlink_metadata(self.as_std_path()) else {
            return Ok(false); // does not exist
        };
        if meta.is_dir() {
            fs::remove_dir(self.as_std_path())?;
        } else {
            fs::remove_file(self.as_std_path())?;
        }
        Ok(true)
    }

    /// Deletes the path if it exists, reporting whether it did.
    ///
    /// Collapses the Scala's three outcomes into two: `false` covers both "did not exist" and
    /// "deletion failed", where the Scala returns `false` for the first and **throws** for the
    /// second. Use [`Self::try_delete`] when that difference matters — a permission error
    /// silently reading as "already gone" is exactly the kind of thing that hides a bug.
    #[must_use]
    pub fn delete(&self) -> bool {
        self.try_delete().unwrap_or(false)
    }

    /// Creates this directory and any missing parents, reporting whether it is now a directory.
    ///
    /// Mirrors the Scala, which calls `createDirectories` and then re-checks `isDirectory` rather
    /// than trusting the call — so an existing directory reports `true`, and a path already
    /// occupied by a *file* reports `false` instead of erroring.
    #[must_use]
    pub fn mkdirs(&self) -> bool {
        #[expect(
            clippy::let_underscore_must_use,
            reason = "the result is deliberately discarded: the Scala calls createDirectories and                       then re-checks isDirectory rather than trusting it, which is what makes an                       already-existing directory report true and a name occupied by a file report                       false instead of erroring. The check below is the real answer."
        )]
        let _ = fs::create_dir_all(self.as_std_path());
        self.isDirectory()
    }

    /// Writes to this path through a callback, in the given charset.
    ///
    /// The counterpart to Scala's `withWriter(charsetName, append)(func)`, which hands the body a
    /// `PrintWriter` -- so the body writes *text* and the charset does the encoding. The closure
    /// here gets a [`TextWriter`] for the same reason.
    ///
    /// An earlier version took the charset and **discarded it** (`let _ = charset`), handing over
    /// a raw byte sink. That is worse than not offering the parameter: a caller asking for
    /// Latin-1 got UTF-8 and nothing said so.
    ///
    /// The writer is flushed on the way out, so a body that forgets does not lose its tail.
    ///
    /// # Errors
    ///
    /// Opening, writing or flushing the file, whatever the closure returns, or text that the
    /// charset cannot represent.
    pub fn try_withWriter<F>(&self, charset: Charset, append: bool, f: F) -> io::Result<()>
    where
        F: FnOnce(&mut TextWriter<'_>) -> io::Result<()>,
    {
        let file = fs::File::options()
            .write(true)
            .create(true)
            .append(append)
            .truncate(!append)
            .open(self.as_std_path())?;
        let mut out = io::BufWriter::new(file);
        let mut tw = TextWriter {
            out: &mut out,
            charset,
        };
        f(&mut tw)?;
        out.flush()?;
        Ok(())
    }

    /// Writes through a callback, reporting success. See [`Self::try_withWriter`].
    #[must_use]
    pub fn withWriter<F>(&self, charset: Charset, append: bool, f: F) -> bool
    where
        F: FnOnce(&mut TextWriter<'_>) -> io::Result<()>,
    {
        self.try_withWriter(charset, append, f).is_ok()
    }
}

/// A text sink that encodes through a [`Charset`] — the counterpart to the `PrintWriter` that
/// Scala's `withWriter` hands its body.
///
/// Text in, encoded bytes out. Deliberately *not* an `io::Write`: that trait takes bytes, which
/// would let a caller bypass the encoding entirely and reintroduce the bug this replaced.
pub struct TextWriter<'a> {
    out: &'a mut dyn io::Write,
    charset: Charset,
}

impl TextWriter<'_> {
    /// Writes `s`, encoded.
    ///
    /// # Errors
    ///
    /// The underlying write, or text the charset cannot represent.
    pub fn print(&mut self, s: &str) -> io::Result<()> {
        let bytes = self.charset.encode(s)?;
        self.out.write_all(&bytes)
    }

    /// Writes `s` followed by `\n`, encoded.
    ///
    /// `\n` rather than the platform line separator, matching the project convention of not
    /// emitting carriage returns.
    ///
    /// # Errors
    ///
    /// The underlying write, or text the charset cannot represent.
    pub fn println(&mut self, s: &str) -> io::Result<()> {
        self.print(s)?;
        self.out.write_all(b"\n")
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use crate::upath::PathContext;
    use crate::upath::UPath;
    use crate::upath::UserInfo;
    use crate::upath::io::Charset;

    fn at(dir: &std::path::Path, name: &str) -> UPath {
        let d = dir.to_string_lossy().replace('\\', "/");
        let ctx = Arc::new(PathContext::synthetic(
            &[],
            UserInfo::new("tester", &d, &d),
            cfg!(windows),
        ));
        UPath::resolve(&ctx, &format!("{d}/{name}")).unwrap_or_else(|e| panic!("resolve: {e}"))
    }

    fn tmp() -> tempfile::TempDir {
        tempfile::tempdir().unwrap_or_else(|e| panic!("tempdir: {e}"))
    }

    #[test]
    fn copy_respects_overwrite_in_both_directions() {
        let t = tmp();
        let src = at(t.path(), "src.txt");
        let dst = at(t.path(), "dst.txt");
        src.write("hello");
        assert!(src.try_copyTo(&dst, false, false).is_ok(), "fresh copy");
        assert_eq!(dst.contentAsString(), "hello");

        src.write("second");
        // overwrite = false must refuse rather than clobber. This is the direction that loses
        // data if it is wrong.
        assert!(
            src.try_copyTo(&dst, false, false).is_err(),
            "must refuse to clobber"
        );
        assert_eq!(dst.contentAsString(), "hello", "destination untouched");

        assert!(src.try_copyTo(&dst, true, false).is_ok());
        assert_eq!(dst.contentAsString(), "second", "overwrite = true replaces");
    }

    #[test]
    fn copy_attributes_carries_the_modification_time() {
        let t = tmp();
        let src = at(t.path(), "src.txt");
        let dst = at(t.path(), "dst.txt");
        src.write("x");
        // A time far enough in the past that a fresh copy could not coincidentally match.
        let back = std::time::UNIX_EPOCH + std::time::Duration::from_secs(1_000_000_000);
        let f = std::fs::File::options()
            .write(true)
            .open(src.as_std_path())
            .unwrap_or_else(|e| panic!("open: {e}"));
        f.set_modified(back).unwrap_or_else(|e| panic!("set_modified: {e}"));
        drop(f);

        // Deliberately no assertion for `copyAttributes = false`. On Windows both `fs::copy` and
        // Java's `Files.copy` go through `CopyFileEx`, which preserves the timestamp regardless,
        // so the flag has no observable effect here; on Unix neither preserves it. The languages
        // agree on both platforms, but the *platform* varies -- asserting a difference would fail
        // on Windows, and asserting equality would fail on Linux.
        assert!(src.try_copyTo(&dst, true, true).is_ok());
        assert_eq!(
            dst.lastModified(),
            src.lastModified(),
            "with copyAttributes the time carries"
        );
    }

    #[test]
    fn no_clobber_copy_refuses_atomically() {
        let t = tmp();
        let src = at(t.path(), "src.txt");
        let dst = at(t.path(), "dst.txt");
        src.write("new");
        dst.write("original");
        let err = src
            .try_copyTo(&dst, false, false)
            .expect_err("must refuse an existing destination");
        assert_eq!(
            err.kind(),
            std::io::ErrorKind::AlreadyExists,
            "the kernel's answer, via create_new"
        );
        assert_eq!(dst.contentAsString(), "original", "and nothing was written");
    }

    #[test]
    fn rename_respects_overwrite_and_reports_failure() {
        let t = tmp();
        let a = at(t.path(), "a.txt");
        let b = at(t.path(), "b.txt");
        a.write("A");
        assert!(a.renameTo(&b, false), "move to a free name");
        assert!(!a.exists(), "source is gone");
        assert_eq!(b.contentAsString(), "A");

        let c = at(t.path(), "c.txt");
        c.write("C");
        assert!(!c.renameTo(&b, false), "must refuse an occupied destination");
        assert_eq!(b.contentAsString(), "A", "destination untouched");
        assert!(c.exists(), "source survives a refused move");

        assert!(c.renameTo(&b, true), "overwrite = true replaces");
        assert_eq!(b.contentAsString(), "C");

        // A missing source is a failure, not a silent success.
        let ghost = at(t.path(), "ghost.txt");
        assert!(!ghost.renameTo(&b, true));
        assert!(ghost.renameToOpt(&b, true).is_none());
    }

    #[test]
    fn rename_via_copy_uses_the_scala_status_codes() {
        let t = tmp();
        let a = at(t.path(), "a.txt");
        let b = at(t.path(), "b.txt");
        a.write("A");
        assert_eq!(a.renameViaCopy(&b, false), 0, "0 is success");
        assert!(!a.exists());
        assert_eq!(b.contentAsString(), "A");

        let c = at(t.path(), "c.txt");
        c.write("C");
        assert_eq!(c.renameViaCopy(&b, false), -1, "-1 for an occupied destination");
        assert!(c.exists(), "source survives");
        assert_eq!(c.renameViaCopy(&b, true), 0);

        let ghost = at(t.path(), "ghost.txt");
        assert_eq!(ghost.renameViaCopy(&b, true), -1, "-1 for a missing source");
    }

    #[test]
    fn delete_distinguishes_absent_from_failed_only_in_the_try_form() {
        let t = tmp();
        let f = at(t.path(), "f.txt");
        f.write("x");
        assert_eq!(f.try_delete().ok(), Some(true), "deleted");
        assert!(!f.exists());
        assert_eq!(f.try_delete().ok(), Some(false), "absent is Ok(false), not an error");
        assert!(!f.delete(), "the bool form reports false for absent");

        // An empty directory can go; a non-empty one cannot -- matching deleteIfExists.
        let d = at(t.path(), "d");
        assert!(d.mkdirs());
        assert_eq!(d.try_delete().ok(), Some(true), "empty directory deletes");
        assert!(d.mkdirs());
        at(t.path(), "d/inside.txt").write("x");
        assert!(
            d.try_delete().is_err(),
            "a non-empty directory must fail rather than report absent"
        );
        assert!(!d.delete(), "...which the bool form flattens to false");
        assert!(d.exists(), "and the directory survives");
    }

    #[test]
    fn mkdirs_is_idempotent_and_false_for_an_occupied_name() {
        let t = tmp();
        let d = at(t.path(), "x/y/z");
        assert!(d.mkdirs(), "creates missing parents");
        assert!(d.isDirectory());
        assert!(d.mkdirs(), "existing directory reports true");

        // A path already occupied by a file reports false rather than erroring, because the Scala
        // re-checks `isDirectory` instead of trusting `createDirectories`.
        let f = at(t.path(), "occupied");
        f.write("x");
        assert!(!f.mkdirs(), "a file in the way reports false");
    }

    #[test]
    fn with_writer_truncates_or_appends() {
        let t = tmp();
        let f = at(t.path(), "w.txt");
        assert!(f.withWriter(Charset::Utf8, false, |w| w.println("one")));
        assert_eq!(f.contentAsString(), "one\n");
        assert!(f.withWriter(Charset::Utf8, true, |w| w.println("two")));
        assert_eq!(f.contentAsString(), "one\ntwo\n", "append adds");
        assert!(f.withWriter(Charset::Utf8, false, |w| w.println("fresh")));
        assert_eq!(f.contentAsString(), "fresh\n", "non-append truncates");
    }

    #[test]
    fn with_writer_honours_the_charset() {
        // The defect this replaced: the charset argument was accepted and discarded, so Latin-1
        // silently produced UTF-8. `é` is one byte in Latin-1 and two in UTF-8, which is the
        // cheapest way to tell them apart.
        let t = tmp();
        let latin = at(t.path(), "latin.txt");
        assert!(latin.withWriter(Charset::Latin1, false, |w| w.print("é")));
        assert_eq!(latin.byteArray(), vec![0xE9], "one Latin-1 byte");

        let utf = at(t.path(), "utf.txt");
        assert!(utf.withWriter(Charset::Utf8, false, |w| w.print("é")));
        assert_eq!(utf.byteArray(), vec![0xC3, 0xA9], "two UTF-8 bytes");

        // And text Latin-1 cannot represent is an error rather than a silent substitution.
        let bad = at(t.path(), "bad.txt");
        assert!(
            bad.try_withWriter(Charset::Latin1, false, |w| w.print("\u{4e2d}"))
                .is_err(),
            "a CJK code point has no Latin-1 encoding"
        );
    }
}

//! File I/O — a port of the read/write methods in `uni.ext.PathExts`.
//!
//! # Two layers, on purpose
//!
//! `uni`'s I/O is deliberately *total*: a missing file yields an empty result, a
//! decode failure yields an empty string, nothing throws. That is good ergonomics
//! for scripting and it is preserved here — but a port offering only the total form
//! would make failures permanently unreportable, so every operation exists twice.
//! The `try_` form returns the error; the plain form is a thin wrapper that
//! swallows it, exactly as the Scala does.
//!
//! ```ignore
//! let text = p.content_string();          // "" if anything went wrong
//! let text = p.try_content_string(cs)?;   // io::Error says what
//! ```
//!
//! # Charsets
//!
//! Only UTF-8 and Latin-1 are decoded here, which is what `uni` actually uses:
//! `contentAsString` tries UTF-8 and falls back to Latin-1. Any other charset name
//! resolves to UTF-8, matching `Charset.forName(name)` catching and defaulting.
//! [`Charset`] is `#[non_exhaustive]` so a real encoding table can be added later
//! without breaking callers.

use std::fs;
use std::io;
use std::io::BufRead;
use std::io::Write;
use std::path::PathBuf;

use crate::upath::ext::UPath;

/// How to decode bytes into text.
#[non_exhaustive]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Charset {
    /// UTF-8, falling back to Latin-1 when the bytes are not valid UTF-8.
    ///
    /// The default, because it is what no-arg `contentAsString` does — and because
    /// Latin-1 cannot fail, this variant never errors.
    #[default]
    Utf8ThenLatin1,
    /// Strict UTF-8; invalid bytes are an error.
    Utf8,
    /// One byte per code point. Cannot fail.
    Latin1,
}

impl Charset {
    /// Resolves a charset *name*, defaulting to UTF-8 for anything unrecognised.
    ///
    /// `uni` does the same through `try Charset.forName(charset) catch => UTF_8`,
    /// so an unknown name is tolerated rather than rejected.
    #[must_use]
    pub fn by_name(name: &str) -> Self {
        match name.to_ascii_lowercase().replace(['-', '_'], "").as_str() {
            "" => Self::default(),
            "latin1" | "iso88591" | "cp1252" => Self::Latin1,
            _ => Self::Utf8,
        }
    }

    /// Decodes `bytes`.
    ///
    /// # Errors
    /// [`io::ErrorKind::InvalidData`] when [`Charset::Utf8`] meets invalid bytes.
    pub fn decode(self, bytes: Vec<u8>) -> io::Result<String> {
        match self {
            Self::Latin1 => Ok(bytes.iter().map(|&b| b as char).collect()),
            Self::Utf8 => {
                String::from_utf8(bytes).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
            }
            Self::Utf8ThenLatin1 => Ok(match String::from_utf8(bytes) {
                Ok(s) => s,
                Err(e) => e.into_bytes().iter().map(|&b| b as char).collect(),
            }),
        }
    }
}

impl UPath {
    /// This path as a `std::path::PathBuf`, in native form.
    ///
    /// The bridge to `std::fs`. There are deliberately no wrappers for `exists`,
    /// `len`, `read_dir` and the rest: they are one-liners on the other side of
    /// this call, and re-exporting them would add surface without adding meaning.
    #[must_use]
    pub fn as_std_path(&self) -> PathBuf {
        PathBuf::from(self.localpath())
    }

    /// True when this names an existing regular file — the guard every read in
    /// `PathExts` starts with.
    #[must_use]
    pub fn is_file(&self) -> bool {
        self.as_std_path().is_file()
    }

    /// Raw bytes. `PathExts.byteArray`.
    ///
    /// # Errors
    /// Any `std::fs::read` failure.
    pub fn try_bytes(&self) -> io::Result<Vec<u8>> {
        fs::read(self.as_std_path())
    }

    /// Raw bytes, empty when unreadable.
    #[must_use]
    pub fn bytes(&self) -> Vec<u8> {
        self.try_bytes().unwrap_or_default()
    }

    /// Whole file as text. `PathExts.contentAsString`.
    ///
    /// # Errors
    /// Any read failure, or invalid UTF-8 under [`Charset::Utf8`].
    pub fn try_content_string(&self, charset: Charset) -> io::Result<String> {
        charset.decode(self.try_bytes()?)
    }

    /// Whole file as text, empty when unreadable. UTF-8 with a Latin-1 fallback.
    #[must_use]
    pub fn content_string(&self) -> String {
        self.try_content_string(Charset::default())
            .unwrap_or_default()
    }

    /// Lines, without terminators. `PathExts.lines`.
    ///
    /// # Errors
    /// Any read failure, or invalid UTF-8 under [`Charset::Utf8`].
    pub fn try_lines_with(&self, charset: Charset) -> io::Result<Vec<String>> {
        if charset == Charset::Utf8 {
            // Streams, so a large file is not held twice.
            let file = fs::File::open(self.as_std_path())?;
            return io::BufReader::new(file).lines().collect();
        }
        // Latin-1 and the fallback need the bytes in hand to decide, so they cannot
        // go through `BufRead::lines`, which is UTF-8 only.
        Ok(split_lines(&self.try_content_string(charset)?))
    }

    /// Lines, using the default charset.
    ///
    /// # Errors
    /// As [`UPath::try_lines_with`].
    pub fn try_lines(&self) -> io::Result<Vec<String>> {
        self.try_lines_with(Charset::default())
    }

    /// Lines, empty when unreadable.
    #[must_use]
    pub fn lines(&self) -> Vec<String> {
        self.try_lines().unwrap_or_default()
    }

    /// First line, or empty. `PathExts.firstLine`.
    #[must_use]
    pub fn first_line(&self) -> String {
        self.lines().into_iter().next().unwrap_or_default()
    }

    /// Applies `f` to every line. `PathExts.eachLine`.
    ///
    /// # Errors
    /// As [`UPath::try_lines_with`].
    pub fn try_each_line(&self, mut f: impl FnMut(&str)) -> io::Result<()> {
        for line in self.try_lines()? {
            f(&line);
        }
        Ok(())
    }

    /// Applies `f` to every line, doing nothing when unreadable.
    pub fn each_line(&self, mut f: impl FnMut(&str)) {
        for line in self.lines() {
            f(&line);
        }
    }

    /// Writes `text`, replacing any existing content. `PathExts.write`.
    ///
    /// # Errors
    /// Any write failure.
    pub fn try_write(&self, text: &str) -> io::Result<()> {
        fs::write(self.as_std_path(), text)
    }

    /// Writes `text`; `false` when it failed.
    pub fn write(&self, text: &str) -> bool {
        self.try_write(text).is_ok()
    }

    /// Writes each line followed by a newline, including the last.
    /// `PathExts.writeLines`.
    ///
    /// The trailing newline is deliberate on the Scala side — it "ensures the file
    /// isn't missing a newline at EOF" — so it is not an accident to tidy away.
    /// Always LF, never CRLF, on every platform.
    ///
    /// # Errors
    /// Any write failure.
    pub fn try_write_lines<S: AsRef<str>>(&self, lines: &[S]) -> io::Result<()> {
        let file = fs::File::create(self.as_std_path())?;
        let mut w = io::BufWriter::new(file);
        for line in lines {
            w.write_all(line.as_ref().as_bytes())?;
            w.write_all(b"\n")?;
        }
        w.flush()
    }

    /// Writes each line with a trailing newline; `false` when it failed.
    pub fn write_lines<S: AsRef<str>>(&self, lines: &[S]) -> bool {
        self.try_write_lines(lines).is_ok()
    }
}

/// Splits on LF, dropping a preceding CR and any final empty piece.
///
/// `BufRead::lines` already behaves this way; the decoded-string path needs it too
/// so the two agree on CRLF input and on a trailing newline.
fn split_lines(text: &str) -> Vec<String> {
    let mut out: Vec<String> = text
        .split('\n')
        .map(|l| l.strip_suffix('\r').unwrap_or(l).to_owned())
        .collect();
    if out.last().is_some_and(String::is_empty) {
        out.pop();
    }
    out
}

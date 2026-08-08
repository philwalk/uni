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
//! let text = p.contentAsString();          // "" if anything went wrong
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

#![allow(
    non_snake_case,
    reason = "public methods mirror the Scala API name-for-name, so a script kept in \
              both languages needs no mental translation -- the same reason windows-rs \
              spells Win32 functions SetEvent rather than set_event. Internal helpers \
              and Rust trait contracts stay snake_case, so the case says whether a \
              Scala counterpart exists."
)]

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
    /// UTF-16 with the byte order taken from a BOM, big-endian when there is none, and the BOM
    /// consumed. Java's `UTF-16`, which is what `Charset.forName("UTF-16")` gives the Scala.
    Utf16,
    /// UTF-16, little-endian, no BOM assumed. Java's `UTF-16LE`.
    Utf16Le,
    /// UTF-16, big-endian, no BOM assumed. Java's `UTF-16BE`.
    Utf16Be,
}

/// Decodes UTF-16 code units of the given byte order.
///
/// An odd byte count is an error rather than a silently dropped trailing byte: the Scala's
/// `Files.readString` raises `MalformedInputException` for the same input, which `contentAsString`
/// turns into an empty string, so erroring here keeps the two ends aligned.
fn decode_utf16(bytes: &[u8], big_endian: bool) -> io::Result<String> {
    if !bytes.len().is_multiple_of(2) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("UTF-16 needs an even byte count, got {}", bytes.len()),
        ));
    }
    let units: Vec<u16> = bytes
        .chunks_exact(2)
        .map(|c| {
            if big_endian {
                u16::from_be_bytes([c[0], c[1]])
            } else {
                u16::from_le_bytes([c[0], c[1]])
            }
        })
        .collect();
    String::from_utf16(&units).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
}

impl Charset {
    /// Resolves a charset *name* to one of the supported encodings, falling back to UTF-8.
    ///
    /// The Scala resolves through `try Charset.forName(name) catch => UTF_8`, so it honours
    /// **every** charset the JVM knows. This supports UTF-8, Latin-1 and the three UTF-16
    /// forms; anything else -- Shift_JIS, EUC-JP, UTF-32, the EBCDIC family -- falls back to
    /// UTF-8 here while the Scala would decode it properly. That is the one silent divergence
    /// left in the port, and it is a missing-encoding gap rather than a design decision: `std`
    /// ships no charset tables, so each one has to be written out or a crate taken on.
    ///
    /// The fallback matches the Scala only for names the JVM *also* rejects.
    #[must_use]
    pub fn by_name(name: &str) -> Self {
        match name.to_ascii_lowercase().replace(['-', '_'], "").as_str() {
            "" => Self::default(),
            "latin1" | "iso88591" | "cp1252" => Self::Latin1,
            "utf16" => Self::Utf16,
            "utf16le" => Self::Utf16Le,
            "utf16be" => Self::Utf16Be,
            _ => Self::Utf8,
        }
    }

    /// Whether a newline encodes to the single byte `0x0A` in this charset.
    ///
    /// The byte-oriented line reader splits on that byte before decoding, which is only sound
    /// when the byte cannot appear inside a character. False for the UTF-16 forms, where `0x0A`
    /// is half of a code unit and a byte-level split would cut a character in two.
    #[must_use]
    pub const fn newline_is_one_byte(self) -> bool {
        match self {
            Self::Utf8 | Self::Utf8ThenLatin1 | Self::Latin1 => true,
            Self::Utf16 | Self::Utf16Le | Self::Utf16Be => false,
        }
    }

    /// Encodes text in this charset.
    ///
    /// The reverse of [`Self::decode`], and the reason `withWriter` can honour its charset
    /// argument at all -- it previously took one and discarded it, so a caller asking for
    /// Latin-1 silently got UTF-8.
    ///
    /// # Errors
    ///
    /// [`Self::Latin1`] cannot represent a code point above U+00FF, and says so rather than
    /// substituting a replacement character.
    pub fn encode(self, s: &str) -> io::Result<Vec<u8>> {
        match self {
            // Utf8ThenLatin1 is a *decoding* fallback; there is nothing to fall back to when
            // encoding, so it means UTF-8 here.
            Self::Utf8 | Self::Utf8ThenLatin1 => Ok(s.as_bytes().to_vec()),
            // Java's `UTF-16` encoder emits a big-endian BOM; `UTF-16BE`/`LE` emit none.
            Self::Utf16 => Ok(std::iter::once(0xFEu8)
                .chain(std::iter::once(0xFFu8))
                .chain(s.encode_utf16().flat_map(u16::to_be_bytes))
                .collect()),
            Self::Utf16Be => Ok(s.encode_utf16().flat_map(u16::to_be_bytes).collect()),
            Self::Utf16Le => Ok(s.encode_utf16().flat_map(u16::to_le_bytes).collect()),
            Self::Latin1 => s
                .chars()
                .map(|c| {
                    // The TryFromIntError carries nothing the caller needs -- the code point is
                    // the useful information, and it is in the message.
                    u8::try_from(u32::from(c)).map_err(|e| {
                        io::Error::new(
                            io::ErrorKind::InvalidData,
                            format!("[{c}] has no Latin-1 encoding ({e})"),
                        )
                    })
                })
                .collect(),
        }
    }

    /// Decodes `bytes`.
    ///
    /// # Errors
    /// [`io::ErrorKind::InvalidData`] when [`Charset::Utf8`] meets invalid bytes, or when a
    /// UTF-16 form meets an odd byte count or an unpaired surrogate.
    pub fn decode(self, bytes: Vec<u8>) -> io::Result<String> {
        match self {
            Self::Latin1 => Ok(bytes.iter().map(|&b| b as char).collect()),
            Self::Utf16Be => decode_utf16(&bytes, true),
            Self::Utf16Le => decode_utf16(&bytes, false),
            // A BOM decides, big-endian when absent, and it is consumed rather than returned as
            // a zero-width character -- both are what the JVM's `UTF-16` decoder does.
            Self::Utf16 => match bytes.get(..2) {
                Some([0xFF, 0xFE]) => decode_utf16(&bytes[2..], false),
                Some([0xFE, 0xFF]) => decode_utf16(&bytes[2..], true),
                _ => decode_utf16(&bytes, true),
            },
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
    pub fn isFile(&self) -> bool {
        // The inner call is `std::path::Path::is_file`, deliberately left snake_case: it is
        // Rust's method, not a mirror of a Scala one.
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
    pub fn byteArray(&self) -> Vec<u8> {
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
    pub fn contentAsString(&self) -> String {
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
            //
            // `BufRead::lines` already implements uni's rule exactly: it drops a `\r` only
            // when the `\n` consumed it, leaving an interior or end-of-file `\r` alone.
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
    pub fn firstLine(&self) -> String {
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
    pub fn eachLine(&self, mut f: impl FnMut(&str)) {
        for line in self.lines() {
            f(&line);
        }
    }

    /// Lines as a lazy iterator, so a large file is never held in memory.
    ///
    /// Empty when the path is not a file, mirroring the Scala, which returns `Iterator.empty`
    /// rather than failing. Carriage returns are removed as everywhere else -- see `split_lines`.
    ///
    /// # The resource concern does not carry over
    ///
    /// Scala's `linesStream` returns an `Iterator[String] & AutoCloseable` and leaks the file
    /// handle unless the caller closes it or exhausts it -- which is why `withLines` exists beside
    /// it. Here the iterator owns its reader, so the handle closes when it drops, whether the
    /// caller exhausts it or abandons it early. [`Self::withLines`] is still offered for symmetry
    /// and for scoped use, but it is not the safety net it is in Scala.
    #[must_use]
    pub fn linesStream(&self, charset: Charset) -> LineStream {
        // `isFile` rather than `exists`: a directory opens on Unix and then fails on read, which
        // would surface as a truncated stream rather than an empty one.
        if !charset.newline_is_one_byte() {
            // See `LineStream::decoded`. Eager, because a byte-level split cannot find a
            // terminator that is not a whole byte.
            let decoded = self.try_lines_with(charset).unwrap_or_default();
            return LineStream {
                reader: None,
                charset,
                decoded: Some(decoded.into_iter()),
            };
        }
        let reader = if self.isFile() {
            fs::File::open(self.as_std_path()).ok().map(io::BufReader::new)
        } else {
            None
        };
        LineStream {
            reader,
            charset,
            decoded: None,
        }
    }

    /// Passes a lazy line iterator to `f` and returns its result.
    ///
    /// The scoped counterpart to [`Self::linesStream`]. `f` receives an empty iterator when the
    /// path is not a file, matching the Scala.
    pub fn withLines<R, F>(&self, charset: Charset, f: F) -> R
    where
        F: FnOnce(&mut LineStream) -> R,
    {
        let mut stream = self.linesStream(charset);
        f(&mut stream)
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
    pub fn writeLines<S: AsRef<str>>(&self, lines: &[S]) -> bool {
        self.try_write_lines(lines).is_ok()
    }
}

/// Splits on LF, dropping a preceding CR and any final empty piece.
///
/// `BufRead::lines` already behaves this way; the decoded-string path needs it too
/// so the two agree on CRLF input and on a trailing newline.
/// A lazy line iterator that owns its reader. See [`UPath::linesStream`].
///
/// Yields `String`, not `io::Result<String>`: the Scala iterator yields plain lines and this
/// mirrors it, stopping at the first read error rather than reporting it. Use
/// [`UPath::try_lines`] when an error must be visible.
pub struct LineStream {
    /// `None` when the path was not a readable file, which makes the stream empty.
    reader: Option<io::BufReader<fs::File>>,
    charset: Charset,
    /// The wide-charset path: lines already decoded and split.
    ///
    /// `0x0A` is not a self-contained line terminator in UTF-16 -- it is half of a code unit --
    /// so reading to that byte and decoding each piece would cut characters in half. Those
    /// charsets decode the whole file and split it on the same CR-optional-newline rule, which
    /// costs the laziness and is the only way to get the right answer. `None` for the
    /// byte-oriented charsets, which stream as before.
    decoded: Option<std::vec::IntoIter<String>>,
}

impl Iterator for LineStream {
    type Item = String;

    fn next(&mut self) -> Option<String> {
        if let Some(decoded) = self.decoded.as_mut() {
            return decoded.next();
        }
        let reader = self.reader.as_mut()?;
        let mut buf = Vec::new();
        // Reads to `\n` as bytes rather than through `BufRead::lines`, so a non-UTF-8 charset is
        // decoded by `Charset` rather than rejected by the reader.
        match reader.read_until(b'\n', &mut buf) {
            Ok(0) => {
                // End of file: drop the reader so the handle closes now rather than when the
                // iterator does.
                self.reader = None;
                None
            }
            Ok(_) => {
                if buf.last() == Some(&b'\n') {
                    buf.pop();
                    // Only the CR the newline consumed; an interior one is data. Popping the
                    // byte before decoding is safe -- 0x0D is never a UTF-8 continuation byte.
                    if buf.last() == Some(&b'\r') {
                        buf.pop();
                    }
                }
                match self.charset.decode(buf) {
                    Ok(line) => Some(line),
                    Err(_) => {
                        self.reader = None;
                        None
                    }
                }
            }
            Err(_) => {
                self.reader = None;
                None
            }
        }
    }
}

/// Splits into lines exactly as a split on `\r?\n` would.
///
/// The only carriage return removed is the one a newline consumed. **An interior `\r` survives,
/// and so does a trailing `\r` at end-of-file**, because in neither case did a `\n` pair with
/// it. Rust's `BufRead::lines` applies the same rule, as does `PathExts.readNextLine` in Scala, so
/// all three agree byte for byte.
///
/// Splitting this way still makes the result independent of where the file came from -- `\r\n` and
/// `\n` land on the same lines, which is the point, since Windows emits either. What it does not do
/// is discard a `\r` that carries information: that is why the rule is stated as a pattern rather
/// than as "remove every `\r`".
///
/// For the bytes exactly as stored, use [`UPath::contentAsString`] or [`UPath::byteArray`].
fn split_lines(text: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    let mut rest = text;
    loop {
        match rest.find('\n') {
            Some(i) => {
                let line = &rest[..i];
                out.push(line.strip_suffix('\r').unwrap_or(line).to_owned());
                rest = &rest[i + 1..];
            }
            None => {
                // The final segment ended at EOF, not at a newline, so a trailing CR here is
                // data. Pushed as-is and the loop ends.
                out.push(rest.to_owned());
                break;
            }
        }
    }
    if out.last().is_some_and(String::is_empty) {
        out.pop();
    }
    out
}

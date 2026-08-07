//! CSV reading and writing — a port of `uni.io.FastCsv` and `uni.io.CsvWriter`.
//!
//! # No csv crate
//!
//! This is a hand-rolled byte state machine, matching the Scala rather than
//! delegating to a crate. Not for lack of one: the point of the port is that the two
//! implementations agree on the awkward cases, and those cases are exactly where a
//! general-purpose parser makes its own choices — unquoted-field trimming, a stray
//! quote mid-field, a lone `\r`, a row that is one field wide. Delegating would trade
//! the parity this exists to provide for a dependency.
//!
//! # Deliberate deviations from RFC 4180
//!
//! Inherited from `uni`, because they remove a large class of bugs in naive callers:
//!
//! - **Unquoted fields are trimmed** of ASCII whitespace; quoted fields are not. So
//!   ` 1, 2 ` parses as two numbers, while `" x "` keeps its spaces.
//! - **Blank rows are dropped.** A row with no non-whitespace field anywhere is not
//!   a row. A row with a *single* non-blank field is kept — one column is a
//!   legitimate CSV, and losing it silently is worse than keeping a delimiter-less
//!   line.
//! - **Short rows are padded**, not dropped, so callers can index by column. See
//!   [`Rows`] for how the width is chosen and why it can still come out jagged.
//! - **A quote that does not close a field is literal.** `a"b` is three characters,
//!   not a parse error.

use std::fs;
use std::io;
use std::io::BufWriter;
use std::io::Read;
use std::io::Write;

use crate::upath::delim;
use crate::upath::ext::UPath;
use crate::upath::io::Charset;

/// Reader and writer settings.
#[derive(Debug, Clone)]
#[non_exhaustive]
pub struct CsvConfig {
    /// Field separator. `None` sniffs it with [`delim::detect_lines`].
    pub delimiter: Option<u8>,
    /// Quote character.
    pub quote: u8,
    /// How to decode field bytes.
    pub charset: Charset,
    /// Read buffer size.
    pub buffer_size: usize,
    /// Rows buffered to measure a width, and rows sampled to sniff a delimiter.
    pub sample_rows: usize,
}

impl Default for CsvConfig {
    fn default() -> Self {
        Self {
            delimiter: None,
            quote: b'"',
            charset: Charset::default(),
            buffer_size: 1 << 20,
            sample_rows: 100,
        }
    }
}

impl CsvConfig {
    /// Config with an explicit delimiter, skipping sniffing.
    #[must_use]
    pub fn with_delimiter(delimiter: u8) -> Self {
        Self {
            delimiter: Some(delimiter),
            ..Self::default()
        }
    }
}

/// True when a row has at least one field with non-whitespace content.
#[must_use]
pub fn is_content_row(row: &[String]) -> bool {
    row.iter().any(|f| !f.trim().is_empty())
}

/// Pads every row out to the widest, so the result is rectangular.
///
/// For callers that hold all rows at once. The streaming reader cannot do this — it
/// has not seen the end of the file — which is why the two can disagree about the
/// final width even though they never disagree about which rows exist.
#[must_use]
pub fn rectangular(mut rows: Vec<Vec<String>>) -> Vec<Vec<String>> {
    let width = rows.iter().map(Vec::len).max().unwrap_or(0);
    for row in &mut rows {
        row.resize(width, String::new());
    }
    rows
}

/// Byte-at-a-time CSV state machine, shared by every reader.
///
/// Feeding is per byte rather than per buffer so the caller owns the read loop and
/// no row is ever split across a buffer boundary.
#[derive(Debug)]
pub struct RowParser {
    delimiter: u8,
    quote: u8,
    field: Vec<u8>,
    fields: Vec<Vec<u8>>,
    in_quotes: bool,
    pending_cr: bool,
    prev_was_quote: bool,
    field_was_quoted: bool,
    has_seen_quote_in_row: bool,
}

impl RowParser {
    /// A parser for `delimiter`, quoting with `quote`.
    #[must_use]
    pub fn new(delimiter: u8, quote: u8) -> Self {
        Self {
            delimiter,
            quote,
            field: Vec::with_capacity(128),
            fields: Vec::with_capacity(16),
            in_quotes: false,
            pending_cr: false,
            prev_was_quote: false,
            field_was_quoted: false,
            has_seen_quote_in_row: false,
        }
    }

    /// Trailing/leading ASCII whitespace, for unquoted fields only.
    fn trimmed(raw: &[u8]) -> &[u8] {
        let start = raw.iter().position(|&b| b > b' ').unwrap_or(raw.len());
        let end = raw.iter().rposition(|&b| b > b' ').map_or(start, |i| i + 1);
        &raw[start..end]
    }

    fn emit_field(&mut self) {
        let raw = std::mem::take(&mut self.field);
        let cleaned = if self.field_was_quoted {
            raw
        } else {
            Self::trimmed(&raw).to_vec()
        };
        self.fields.push(cleaned);
        self.field_was_quoted = false;
    }

    fn emit_row(&mut self) -> Vec<Vec<u8>> {
        std::mem::take(&mut self.fields)
    }

    /// Ends a field and a row on LF, or on CR with the following LF swallowed.
    fn end_row(&mut self, cr: bool) -> Option<Vec<Vec<u8>>> {
        self.emit_field();
        self.has_seen_quote_in_row = false;
        self.pending_cr = cr;
        Some(self.emit_row())
    }

    /// Feeds one byte; yields a row when one completes.
    pub fn feed(&mut self, b: u8) -> Option<Vec<Vec<u8>>> {
        if self.pending_cr {
            self.pending_cr = false;
            // The LF of a CRLF: the row already ended on the CR.
            if b == b'\n' {
                return None;
            }
        }
        self.feed_core(b)
    }

    fn feed_core(&mut self, b: u8) -> Option<Vec<Vec<u8>>> {
        if self.in_quotes {
            return self.feed_quoted(b);
        }
        // Outside quotes the two cases differ only in whether a quote can still open
        // a field, which `has_seen_quote_in_row` does not affect. The Scala splits
        // them for a branch-prediction fast path; the behaviour is identical.
        if b == self.delimiter {
            self.emit_field();
            None
        } else if b == b'\n' {
            self.end_row(false)
        } else if b == b'\r' {
            self.end_row(true)
        } else if b == self.quote {
            self.in_quotes = true;
            self.prev_was_quote = false;
            self.field_was_quoted = true;
            self.has_seen_quote_in_row = true;
            None
        } else {
            self.field.push(b);
            None
        }
    }

    fn feed_quoted(&mut self, b: u8) -> Option<Vec<Vec<u8>>> {
        if b == self.quote {
            if self.prev_was_quote {
                // "" inside a quoted field is one literal quote.
                self.field.push(self.quote);
                self.prev_was_quote = false;
            } else {
                // Might close the field; only the next byte settles it.
                self.prev_was_quote = true;
            }
            return None;
        }
        if !self.prev_was_quote {
            self.field.push(b);
            return None;
        }
        self.prev_was_quote = false;
        if b == self.delimiter || b == b'\n' || b == b'\r' {
            self.in_quotes = false;
            if b == self.delimiter {
                self.emit_field();
                None
            } else {
                self.end_row(b == b'\r')
            }
        } else {
            // Not a close after all, so the quote was content: `"a"b"` keeps both.
            self.field.push(self.quote);
            self.field.push(b);
            None
        }
    }

    /// Flushes a final row that had no terminator.
    pub fn eof(&mut self) -> Option<Vec<Vec<u8>>> {
        if self.field.is_empty() && self.fields.is_empty() {
            return None;
        }
        self.emit_field();
        Some(self.emit_row())
    }
}

/// Streaming rows, padded to a width learned from the first `sample_rows` rows.
///
/// # Why a window
///
/// A stream cannot know the true maximum width — only the last row settles that — so
/// it buffers a window, takes the widest row in it, and pads everything to that. A
/// later row wider than the window is emitted **at its own width rather than
/// truncated**: a jagged row beats a lost field.
///
/// The window is measured from rows this parser produced. Reusing the widths
/// [`delim::detect_lines`] already tallies looks free and does not work — it stops as
/// soon as a candidate dominates, which for rows over its check interval happens
/// partway through the first row, leaving no complete row measured at all.
pub struct Rows<R: Read> {
    reader: R,
    parser: RowParser,
    charset: Charset,
    buf: Vec<u8>,
    pos: usize,
    filled: usize,
    exhausted: bool,
    window: std::collections::VecDeque<Vec<String>>,
    width: usize,
    sample_rows: usize,
    primed: bool,
}

impl<R: Read> Rows<R> {
    fn new(reader: R, delimiter: u8, cfg: &CsvConfig) -> Self {
        Self {
            reader,
            parser: RowParser::new(delimiter, cfg.quote),
            charset: cfg.charset,
            buf: vec![0; cfg.buffer_size],
            pos: 0,
            filled: 0,
            exhausted: false,
            window: std::collections::VecDeque::new(),
            width: 0,
            sample_rows: cfg.sample_rows,
            primed: false,
        }
    }

    /// The next row straight from the parser, before windowing.
    fn raw_next(&mut self) -> Option<Vec<String>> {
        loop {
            while self.pos < self.filled {
                let b = self.buf[self.pos];
                self.pos += 1;
                if let Some(row) = self.parser.feed(b) {
                    let decoded = decode_row(row, self.charset);
                    if is_content_row(&decoded) {
                        return Some(decoded);
                    }
                }
            }
            if self.exhausted {
                return None;
            }
            match self.reader.read(&mut self.buf) {
                Ok(0) | Err(_) => {
                    // A mid-stream read error ends the stream, as the lenient layer
                    // ends everything else. `try_csv_rows` reports it instead.
                    self.exhausted = true;
                    let row = self.parser.eof().map(|r| decode_row(r, self.charset));
                    match row {
                        Some(r) if is_content_row(&r) => return Some(r),
                        _ => return None,
                    }
                }
                Ok(n) => {
                    self.pos = 0;
                    self.filled = n;
                }
            }
        }
    }

    fn prime(&mut self) {
        if self.primed {
            return;
        }
        self.primed = true;
        while self.window.len() < self.sample_rows {
            match self.raw_next() {
                Some(row) => {
                    self.width = self.width.max(row.len());
                    self.window.push_back(row);
                }
                None => break,
            }
        }
    }
}

impl<R: Read> Iterator for Rows<R> {
    type Item = Vec<String>;

    fn next(&mut self) -> Option<Vec<String>> {
        self.prime();
        let mut row = match self.window.pop_front() {
            Some(r) => r,
            None => self.raw_next()?,
        };
        if row.len() < self.width {
            row.resize(self.width, String::new());
        }
        Some(row)
    }
}

fn decode_row(fields: Vec<Vec<u8>>, charset: Charset) -> Vec<String> {
    fields
        .into_iter()
        .map(|f| charset.decode(f).unwrap_or_default())
        .collect()
}

/// Writes rows with minimal quoting. A port of `uni.io.CsvWriter`.
///
/// A field is quoted only when it has to be: it contains the delimiter, a quote, a
/// line break, or leading/trailing whitespace. That last one matters because the
/// reader trims unquoted fields — without it a round trip would silently lose the
/// spaces.
pub struct CsvWriter<W: Write> {
    out: W,
    delimiter: u8,
    quote: u8,
}

impl<W: Write> CsvWriter<W> {
    /// A writer over `out`.
    pub fn new(out: W, delimiter: u8, quote: u8) -> Self {
        Self {
            out,
            delimiter,
            quote,
        }
    }

    /// A comma/double-quote writer.
    pub fn comma(out: W) -> Self {
        Self::new(out, b',', b'"')
    }

    fn needs_quoting(&self, field: &str) -> bool {
        let d = char::from(self.delimiter);
        let q = char::from(self.quote);
        field.contains(d)
            || field.contains(q)
            || field.contains('\n')
            || field.contains('\r')
            || field.starts_with(char::is_whitespace)
            || field.ends_with(char::is_whitespace)
    }

    /// Writes one row, LF-terminated.
    ///
    /// # Errors
    /// Any write failure.
    pub fn write_row<S: AsRef<str>>(&mut self, row: &[S]) -> io::Result<()> {
        for (i, field) in row.iter().enumerate() {
            if i > 0 {
                self.out.write_all(&[self.delimiter])?;
            }
            let f = field.as_ref();
            if self.needs_quoting(f) {
                self.out.write_all(&[self.quote])?;
                for b in f.bytes() {
                    if b == self.quote {
                        self.out.write_all(&[self.quote])?;
                    }
                    self.out.write_all(&[b])?;
                }
                self.out.write_all(&[self.quote])?;
            } else {
                self.out.write_all(f.as_bytes())?;
            }
        }
        // Always LF; never CRLF, on any platform.
        self.out.write_all(b"\n")
    }

    /// Flushes the underlying writer.
    ///
    /// # Errors
    /// Any flush failure.
    pub fn flush(&mut self) -> io::Result<()> {
        self.out.flush()
    }
}

impl UPath {
    /// The delimiter for this file: the configured one, or sniffed from a sample.
    fn csv_delimiter(&self, cfg: &CsvConfig) -> u8 {
        cfg.delimiter.unwrap_or_else(|| {
            let sample: Vec<String> = self.lines().into_iter().take(cfg.sample_rows).collect();
            let ch = delim::detect_lines(sample, cfg.sample_rows).delimiter;
            u8::try_from(ch as u32).unwrap_or(b',')
        })
    }

    /// Streams rows, padded to the width of the reader's sampling window.
    ///
    /// # Errors
    /// Any failure opening the file.
    pub fn try_csv_rows_stream(
        &self,
        cfg: &CsvConfig,
    ) -> io::Result<Rows<io::BufReader<fs::File>>> {
        let delimiter = self.csv_delimiter(cfg);
        let file = fs::File::open(self.as_std_path())?;
        Ok(Rows::new(io::BufReader::new(file), delimiter, cfg))
    }

    /// All rows, padded to a common width so the result is rectangular.
    ///
    /// # Errors
    /// Any failure opening or reading the file.
    pub fn try_csv_rows(&self) -> io::Result<Vec<Vec<String>>> {
        let cfg = CsvConfig::default();
        let delimiter = self.csv_delimiter(&cfg);
        let bytes = self.try_bytes()?;
        let mut parser = RowParser::new(delimiter, cfg.quote);
        let mut rows: Vec<Vec<String>> = Vec::new();
        for &b in &bytes {
            if let Some(row) = parser.feed(b) {
                let decoded = decode_row(row, cfg.charset);
                if is_content_row(&decoded) {
                    rows.push(decoded);
                }
            }
        }
        if let Some(row) = parser.eof() {
            let decoded = decode_row(row, cfg.charset);
            if is_content_row(&decoded) {
                rows.push(decoded);
            }
        }
        Ok(rectangular(rows))
    }

    /// All rows, rectangular; empty when unreadable.
    ///
    /// This is the one form guaranteed rectangular — it reads the whole file, so it
    /// knows the true width. [`UPath::csv_rows_stream`] only knows its window.
    #[must_use]
    pub fn csv_rows(&self) -> Vec<Vec<String>> {
        self.try_csv_rows().unwrap_or_default()
    }

    /// Streams rows, yielding nothing when unreadable.
    #[must_use]
    pub fn csv_rows_stream(&self) -> Box<dyn Iterator<Item = Vec<String>>> {
        match self.try_csv_rows_stream(&CsvConfig::default()) {
            Ok(rows) => Box::new(rows),
            Err(_) => Box::new(std::iter::empty()),
        }
    }

    /// Writes rows as CSV, replacing any existing content.
    ///
    /// # Errors
    /// Any write failure.
    pub fn try_write_csv<S: AsRef<str>>(&self, rows: &[Vec<S>]) -> io::Result<()> {
        let file = fs::File::create(self.as_std_path())?;
        let mut w = CsvWriter::comma(BufWriter::new(file));
        for row in rows {
            w.write_row(row)?;
        }
        w.flush()
    }

    /// Writes rows as CSV; `false` when it failed.
    pub fn write_csv<S: AsRef<str>>(&self, rows: &[Vec<S>]) -> bool {
        self.try_write_csv(rows).is_ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(text: &str, delimiter: u8) -> Vec<Vec<String>> {
        let mut p = RowParser::new(delimiter, b'"');
        let mut out = Vec::new();
        for &b in text.as_bytes() {
            if let Some(r) = p.feed(b) {
                out.push(decode_row(r, Charset::default()));
            }
        }
        if let Some(r) = p.eof() {
            out.push(decode_row(r, Charset::default()));
        }
        out
    }

    #[test]
    fn parses_plain_rows() {
        assert_eq!(
            parse("a,b,c\n1,2,3\n", b','),
            vec![vec!["a", "b", "c"], vec!["1", "2", "3"]]
        );
    }

    #[test]
    fn crlf_is_one_terminator() {
        assert_eq!(parse("a,b\r\nc,d\n", b','), vec![vec!["a", "b"], vec!["c", "d"]]);
        // A lone CR still ends a row.
        assert_eq!(parse("a,b\rc,d\n", b','), vec![vec!["a", "b"], vec!["c", "d"]]);
    }

    #[test]
    fn a_final_row_without_a_terminator_is_kept() {
        assert_eq!(parse("a,b,c", b','), vec![vec!["a", "b", "c"]]);
    }

    #[test]
    fn quoted_fields_keep_delimiters_and_newlines() {
        assert_eq!(parse("\"a,b\",c\n", b','), vec![vec!["a,b", "c"]]);
        assert_eq!(parse("\"one\ntwo\",c\n", b','), vec![vec!["one\ntwo", "c"]]);
    }

    #[test]
    fn a_doubled_quote_is_one_literal_quote() {
        assert_eq!(parse("\"say \"\"hi\"\"\",b\n", b','), vec![vec!["say \"hi\"", "b"]]);
    }

    #[test]
    fn a_quote_that_closes_nothing_is_literal() {
        // Deliberate deviation from RFC 4180, which would call this malformed.
        assert_eq!(parse("\"a\"b\",c\n", b','), vec![vec!["a\"b", "c"]]);
    }

    #[test]
    fn unquoted_fields_are_trimmed_and_quoted_ones_are_not() {
        assert_eq!(parse("  1 ,\t2\t\n", b','), vec![vec!["1", "2"]]);
        assert_eq!(parse("\" x \",\" y \"\n", b','), vec![vec![" x ", " y "]]);
    }

    #[test]
    fn an_all_whitespace_field_trims_to_empty() {
        assert_eq!(parse("   ,a\n", b','), vec![vec!["", "a"]]);
    }

    #[test]
    fn a_tab_delimiter_works() {
        assert_eq!(parse("x\ty\n1\t2\n", b'\t'), vec![vec!["x", "y"], vec!["1", "2"]]);
    }

    #[test]
    fn empty_input_yields_no_rows() {
        assert_eq!(parse("", b','), Vec::<Vec<String>>::new());
    }

    #[test]
    fn blank_rows_are_not_content() {
        assert!(!is_content_row(&[]));
        assert!(!is_content_row(&["".into(), "  ".into()]));
        // One column is a legitimate CSV.
        assert!(is_content_row(&["solo".into()]));
    }

    #[test]
    fn rectangular_pads_without_truncating() {
        let rows = vec![
            vec!["a".to_owned()],
            vec!["b".to_owned(), "c".to_owned(), "d".to_owned()],
        ];
        let out = rectangular(rows);
        assert_eq!(out[0], vec!["a", "", ""]);
        assert_eq!(out[1], vec!["b", "c", "d"]);
        assert_eq!(rectangular(Vec::new()), Vec::<Vec<String>>::new());
    }

    #[test]
    fn the_writer_quotes_only_what_it_must() {
        let mut buf = Vec::new();
        {
            let mut w = CsvWriter::comma(&mut buf);
            w.write_row(&["plain", "a,b", "say \"hi\"", " pad ", "line\nbreak"])
                .expect("write");
            w.flush().expect("flush");
        }
        assert_eq!(
            String::from_utf8(buf).expect("utf8"),
            "plain,\"a,b\",\"say \"\"hi\"\"\",\" pad \",\"line\nbreak\"\n"
        );
    }

    #[test]
    fn writer_output_reparses_to_the_original() {
        let rows = vec![
            vec!["a,b".to_owned(), " pad ".to_owned()],
            vec!["q\"q".to_owned(), "plain".to_owned()],
        ];
        let mut buf = Vec::new();
        {
            let mut w = CsvWriter::comma(&mut buf);
            for r in &rows {
                w.write_row(r).expect("write");
            }
        }
        let text = String::from_utf8(buf).expect("utf8");
        assert_eq!(parse(&text, b','), rows);
    }
}

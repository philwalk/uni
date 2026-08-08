//! CSV → matrix bridge — a port of `uni.io.FileOps.loadSmart` and friends.
//!
//! [`UPath::csvRows`](crate::upath::UPath::csvRows) already returns rectangular
//! rows, so this layer only has to choose a shape, decide whether row 0 is a header,
//! and parse cells. The result is an [`Array2`], which is what `t3prf` already speaks
//! — so a matrix read here feeds the 3PRF port with no conversion at the seam.
//!
//! # Unparseable cells are missing, not errors
//!
//! `uni` runs every cell through `big(s)`, which yields NaN rather than throwing, and
//! a padded cell is `""` — already NaN once converted. That is the contract callers
//! rely on, so [`CsvCell`] reproduces it: parsing is total, and a cell that makes no
//! sense becomes [`CsvCell::missing`]. Structural failures (no such file) are still
//! errors, and still reported by the `try_` layer.

#![allow(
    non_snake_case,
    reason = "public methods mirror the Scala API name-for-name, so a script kept in \
              both languages needs no mental translation -- the same reason windows-rs \
              spells Win32 functions SetEvent rather than set_event. Internal helpers \
              and Rust trait contracts stay snake_case, so the case says whether a \
              Scala counterpart exists."
)]

use ndarray::Array2;
use ndarray::ArrayView1;
use ndarray::Axis;

use crate::upath::ext::UPath;

/// A cell type a CSV can be read into.
///
/// A trait rather than [`std::str::FromStr`] because the failure policy is `uni`'s,
/// not Rust's: unparseable means *missing*, not *error*. It is also the extension
/// point — `i64`, `String` and anything else can be added without touching the
/// methods that use it.
pub trait CsvCell: Sized + Clone {
    /// Parses a cell, or `None` when it does not represent a value.
    fn parse_cell(s: &str) -> Option<Self>;

    /// The value standing in for a cell that could not be parsed.
    fn missing() -> Self;

    /// Total parse: [`Self::parse_cell`] falling back to [`Self::missing`].
    fn from_cell(s: &str) -> Self {
        Self::parse_cell(s).unwrap_or_else(Self::missing)
    }
}

/// Parses the way `uni.data.Big.big(String)` does.
///
/// Strips `,` and `$` anywhere in the cell and reads a trailing `%` as a division by
/// 100, so `"$1,234.56"` and `"12%"` are numbers. Everything else is `None`.
///
/// The awkward part is matching `BigDecimal`'s grammar, which differs from Rust's
/// `f64::from_str` in both directions:
///
/// - `BigDecimal` refuses `inf`, `Infinity` and `NaN`, which Rust accepts. Left
///   alone, `"inf"` would read as infinity here and NaN in Scala.
/// - `BigDecimal` has no signed zero, so `"-0"` is `+0.0` there and `-0.0` here.
/// - `BigDecimal` *does* accept a trailing dot: `"4."` is 4, not junk. Both of these
///   were found by the parity fixture after being guessed wrong.
fn parse_big(s: &str) -> Option<f64> {
    let cleaned: String = s.trim().chars().filter(|c| *c != ',' && *c != '$').collect();
    let (body, percent) = match cleaned.strip_suffix('%') {
        Some(b) => (b, true),
        None => (cleaned.as_str(), false),
    };
    if body.is_empty() {
        return None;
    }
    // `e`/`E` is the only letter a BigDecimal literal may contain; anything else is
    // a word like `inf` or `abc`, which Scala turns into NaN.
    if body
        .chars()
        .any(|c| c.is_ascii_alphabetic() && c != 'e' && c != 'E')
    {
        return None;
    }
    let v: f64 = body.parse().ok()?;
    // Divide rather than multiply by 0.01: `0.01` is not exact in binary, so scaling
    // by it rounds twice and can land one ulp off what Scala's decimal division gives.
    let v = if percent { v / 100.0 } else { v };
    // BigDecimal has no signed zero, so `-0` and `-0.0` come back as +0.0 there.
    let v = if v == 0.0 { 0.0 } else { v };
    // A literal Rust accepted but BigDecimal would not, e.g. an overflow to infinity.
    v.is_finite().then_some(v)
}

impl CsvCell for f64 {
    fn parse_cell(s: &str) -> Option<Self> {
        parse_big(s)
    }
    fn missing() -> Self {
        Self::NAN
    }
}

impl CsvCell for f32 {
    fn parse_cell(s: &str) -> Option<Self> {
        // Via f64 so the accepted grammar is identical; only the width differs.
        parse_big(s).map(|v| v as Self)
    }
    fn missing() -> Self {
        Self::NAN
    }
}

impl CsvCell for i64 {
    fn parse_cell(s: &str) -> Option<Self> {
        // Truncates like `Big.toLong`, and rejects what `big` rejects.
        parse_big(s).map(|v| v as Self)
    }
    /// Integers have no NaN. 0 is the stand-in, which is lossy — prefer `f64` when
    /// missing cells need to stay distinguishable from real zeros.
    fn missing() -> Self {
        0
    }
}

impl CsvCell for String {
    fn parse_cell(s: &str) -> Option<Self> {
        Some(s.to_owned())
    }
    fn missing() -> Self {
        Self::new()
    }
}

/// A matrix plus the column names that came with it. `uni.io.FileOps.MatResult`.
#[derive(Debug, Clone)]
pub struct CsvTable<T> {
    /// Column names, or empty when the file had no header row.
    pub headers: Vec<String>,
    /// The data, excluding any header row.
    pub mat: Array2<T>,
}

impl<T> CsvTable<T> {
    /// Position of a named column.
    #[must_use]
    pub fn column_index(&self, name: &str) -> Option<usize> {
        self.headers.iter().position(|h| h == name)
    }

    /// A column by name, or `None` when there is no such header.
    #[must_use]
    pub fn col(&self, name: &str) -> Option<ArrayView1<'_, T>> {
        self.column_index(name)
            .map(|i| self.mat.index_axis(Axis(1), i))
    }

    /// Rows of data, excluding any header.
    #[must_use]
    pub fn rows(&self) -> usize {
        self.mat.nrows()
    }

    /// Columns.
    #[must_use]
    pub fn cols(&self) -> usize {
        self.mat.ncols()
    }
}

/// An empty matrix of the given width.
///
/// `Array2::default()` would force `T: Default` onto every caller; a zero-row
/// `from_elem` stores no elements, so the sample value is only a type witness.
fn empty_matrix<T: CsvCell>(cols: usize) -> Array2<T> {
    Array2::from_elem((0, cols), T::missing())
}

/// Builds a matrix from rectangular rows.
fn matrix_of<T: CsvCell>(rows: &[Vec<String>], width: usize) -> Array2<T> {
    let flat: Vec<T> = rows
        .iter()
        .flat_map(|r| {
            (0..width).map(move |c| T::from_cell(r.get(c).map_or("", String::as_str)))
        })
        .collect();
    // `from_shape_vec` can only fail on a length mismatch, which the comprehension
    // above rules out by construction.
    Array2::from_shape_vec((rows.len(), width), flat).unwrap_or_else(|_| empty_matrix(width))
}

/// True when row 0 looks like labels sitting above data.
///
/// `uni`'s rule exactly: every cell of row 0 is non-numeric **and** row 1 has at
/// least one numeric cell. Both halves matter — the first alone would call a
/// text-only file a header, and the second alone would strip the first row of any
/// file that happens to start with a number.
fn looks_like_header(rows: &[Vec<String>]) -> bool {
    let (Some(row0), Some(row1)) = (rows.first(), rows.get(1)) else {
        return false;
    };
    row0.iter().all(|s| parse_big(s).is_none()) && row1.iter().any(|s| parse_big(s).is_some())
}

/// Header labels, with blanks replaced by a canonical `colN`.
///
/// Numbered by position and 1-based, so `col3` is always the third column —
/// counting only the blanks would renumber the rest whenever one was filled in.
/// Blanks are not hypothetical: a header row shorter than the data gets padded, and
/// the padding arrives as `""`. An unnamed column cannot be looked up by name at
/// all, so leaving it blank would trade a lost row for an unreachable column.
fn named_headers(row: &[String]) -> Vec<String> {
    row.iter()
        .enumerate()
        .map(|(i, h)| {
            let t = h.trim();
            if t.is_empty() {
                format!("col{}", i + 1)
            } else {
                t.to_owned()
            }
        })
        .collect()
}

/// Splits rectangular rows into headers and data. Shared by both smart readers.
fn table_of<T: CsvCell>(rows: Vec<Vec<String>>) -> CsvTable<T> {
    let Some(first) = rows.first() else {
        return CsvTable {
            headers: Vec::new(),
            mat: empty_matrix(0),
        };
    };
    let width = first.len();
    let (headers, data) = if looks_like_header(&rows) {
        (named_headers(first), &rows[1..])
    } else {
        (Vec::new(), &rows[..])
    };
    CsvTable {
        headers,
        mat: matrix_of(data, width),
    }
}

impl UPath {
    /// Every content row as data, with no header detection.
    ///
    /// The analogue of `loadCSV(skipHeader = false)`. Use
    /// [`UPath::try_read_csv_smart`] when row 0 might be labels.
    ///
    /// # Errors
    /// Any failure opening or reading the file.
    /// Header-aware, matching Scala's `readCsv`/`loadMatD`, which are
    /// `loadSmart(p, map).mat` -- and `loadSmart`'s `mat` **excludes a detected header row**.
    ///
    /// This used to take every row as data, so a file with a header came back one row taller than
    /// the Scala's and with a row of `missing` cells on top. Nothing lost for a headerless file:
    /// `loadSmart` only drops row 0 when it looks like a header, so those still come back whole.
    pub fn try_read_csv<T: CsvCell>(&self) -> std::io::Result<Array2<T>> {
        Ok(self.try_read_csv_smart::<T>()?.mat)
    }

    /// Every content row as data; an empty matrix when unreadable.
    #[must_use]
    pub fn readCsv<T: CsvCell>(&self) -> Array2<T> {
        self.try_read_csv().unwrap_or_else(|_| empty_matrix(0))
    }

    /// Data plus column names, detecting a header row. `uni`'s `loadSmart`.
    ///
    /// # Errors
    /// Any failure opening or reading the file.
    pub fn try_read_csv_smart<T: CsvCell>(&self) -> std::io::Result<CsvTable<T>> {
        Ok(table_of(self.try_csv_rows()?))
    }

    /// `Array2<f64>` from a CSV. Scala's `loadMatD`, and `readCsv`, which aliases it.
    ///
    /// A named wrapper over [`Self::readCsv`], which is generic. The generic form covers this
    /// already, but a script ported from Scala says `loadMatD` -- so this exists for the same
    /// reason the method names match: no mental translation at the call site.
    #[must_use]
    pub fn loadMatD(&self) -> Array2<f64> {
        self.readCsv::<f64>()
    }

    /// `Array2<f32>` from a CSV. Scala's `loadMatF`, and `readCsvF`, which aliases it.
    #[must_use]
    pub fn loadMatF(&self) -> Array2<f32> {
        self.readCsv::<f32>()
    }

    /// Alias for [`Self::loadMatF`], matching Scala's `readCsvF`.
    #[must_use]
    pub fn readCsvF(&self) -> Array2<f32> {
        self.loadMatF()
    }

    /// A named table of `f64`, headers included. Scala's `loadSmartD`.
    #[must_use]
    pub fn loadSmartD(&self) -> CsvTable<f64> {
        self.read_csv_smart::<f64>()
    }

    /// Data plus column names; empty when unreadable.
    #[must_use]
    pub fn read_csv_smart<T: CsvCell>(&self) -> CsvTable<T> {
        table_of(self.csvRows())
    }

    /// Writes a matrix as CSV, one row per line.
    ///
    /// # Errors
    /// Any write failure.
    pub fn try_write_matrix(&self, mat: &Array2<f64>) -> std::io::Result<()> {
        let rows: Vec<Vec<String>> = mat
            .rows()
            .into_iter()
            .map(|r| r.iter().map(ToString::to_string).collect())
            .collect();
        self.try_write_csv(&rows)
    }

    /// Writes a matrix as CSV; `false` when it failed.
    pub fn write_matrix(&self, mat: &Array2<f64>) -> bool {
        self.try_write_matrix(mat).is_ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rows(cells: &[&[&str]]) -> Vec<Vec<String>> {
        cells
            .iter()
            .map(|r| r.iter().map(|s| (*s).to_owned()).collect())
            .collect()
    }

    #[test]
    fn plain_numbers_parse() {
        for (text, want) in [("1", 1.0), ("-2.5", -2.5), ("+5", 5.0), (".5", 0.5), ("1e3", 1000.0)]
        {
            assert_eq!(parse_big(text), Some(want), "for {text:?}");
        }
    }

    #[test]
    fn currency_and_percent_are_numbers() {
        assert_eq!(parse_big("$1,234.56"), Some(1234.56));
        assert_eq!(parse_big(" $42 "), Some(42.0));
        assert_eq!(parse_big("12%"), Some(0.12));
        assert_eq!(parse_big("-3.5%"), Some(-0.035));
    }

    #[test]
    fn what_bigdecimal_rejects_is_rejected_here_too() {
        // Rust's f64 parser accepts every one of these; BigDecimal does not, so
        // taking them would silently diverge from the Scala.
        for text in ["inf", "-inf", "Infinity", "NaN", "nan"] {
            assert_eq!(parse_big(text), None, "should reject {text:?}");
        }
    }

    #[test]
    fn bigdecimal_quirks_the_fixture_caught() {
        // A trailing dot is a valid BigDecimal: the fraction part is optional.
        assert_eq!(parse_big("4."), Some(4.0));
        assert_eq!(parse_big("."), None);
        // BigDecimal has no signed zero, so neither does this.
        assert_eq!(parse_big("-0").map(f64::to_bits), Some(0_f64.to_bits()));
        assert_eq!(parse_big("-0.0").map(f64::to_bits), Some(0_f64.to_bits()));
        // Percent scales by division, matching Scala's decimal divide to the bit.
        assert_eq!(parse_big("12%").map(f64::to_bits), Some(0.12_f64.to_bits()));
        assert_eq!(parse_big("-3.5%").map(f64::to_bits), Some((-0.035_f64).to_bits()));
    }

    #[test]
    fn nonsense_is_missing_not_an_error() {
        for text in ["", "   ", "abc", "1.2.3", "--4", "%", "."] {
            assert_eq!(parse_big(text), None, "should reject {text:?}");
        }
        assert!(f64::from_cell("abc").is_nan());
        assert!(f64::from_cell("").is_nan());
    }

    #[test]
    fn a_header_needs_text_above_numbers() {
        assert!(looks_like_header(&rows(&[&["a", "b"], &["1", "2"]])));
        // All text: no data row, so no header.
        assert!(!looks_like_header(&rows(&[&["a", "b"], &["c", "d"]])));
        // Numbers on top: not labels.
        assert!(!looks_like_header(&rows(&[&["1", "2"], &["3", "4"]])));
        // One row cannot be a header over nothing.
        assert!(!looks_like_header(&rows(&[&["a", "b"]])));
        assert!(!looks_like_header(&[]));
    }

    #[test]
    fn a_partly_numeric_first_row_is_not_a_header() {
        // `forall` on row 0: one number is enough to disqualify it.
        assert!(!looks_like_header(&rows(&[&["a", "2"], &["1", "2"]])));
    }

    #[test]
    fn blank_header_cells_get_positional_names() {
        let h = named_headers(&["a".into(), "".into(), "c".into(), "  ".into()]);
        assert_eq!(h, vec!["a", "col2", "c", "col4"]);
    }

    #[test]
    fn a_table_splits_headers_from_data() {
        let t: CsvTable<f64> = table_of(rows(&[&["x", "y"], &["1", "2"], &["3", "4"]]));
        assert_eq!(t.headers, vec!["x", "y"]);
        assert_eq!(t.mat.shape(), &[2, 2]);
        assert_eq!(t.mat[[1, 0]], 3.0);
        assert_eq!(t.col("y").expect("col y").to_vec(), vec![2.0, 4.0]);
        assert_eq!(t.column_index("x"), Some(0));
        assert!(t.col("nope").is_none());
    }

    #[test]
    fn a_headerless_table_keeps_every_row() {
        let t: CsvTable<f64> = table_of(rows(&[&["1", "2"], &["3", "4"]]));
        assert!(t.headers.is_empty());
        assert_eq!(t.rows(), 2);
        assert_eq!(t.cols(), 2);
    }

    #[test]
    fn a_header_only_file_yields_no_data_rows() {
        // Not a header at all by the rule — there is no numeric row beneath it — so
        // the single row stays data. Matching the Scala, which needs two rows.
        let t: CsvTable<String> = table_of(rows(&[&["a", "b"]]));
        assert!(t.headers.is_empty());
        assert_eq!(t.rows(), 1);
    }

    #[test]
    fn an_empty_input_gives_an_empty_table() {
        let t: CsvTable<f64> = table_of(Vec::new());
        assert!(t.headers.is_empty());
        assert_eq!(t.rows(), 0);
        assert_eq!(t.cols(), 0);
    }

    #[test]
    fn padded_cells_arrive_as_missing() {
        // A short row is padded with "", which must read as NaN and not 0.
        let t: CsvTable<f64> = table_of(rows(&[&["1", "2", "3"], &["4", "5", ""]]));
        assert!(t.mat[[1, 2]].is_nan());
    }

    #[test]
    fn integer_cells_fall_back_to_zero() {
        // Documented lossiness: i64 has no NaN.
        assert_eq!(i64::from_cell("7"), 7);
        assert_eq!(i64::from_cell("abc"), 0);
        assert_eq!(i64::from_cell("-2.9"), -2); // truncates, as Big.toLong does
    }

    #[test]
    fn string_cells_pass_through_untouched() {
        assert_eq!(String::from_cell(" x "), " x ");
        assert_eq!(String::from_cell(""), "");
    }
}

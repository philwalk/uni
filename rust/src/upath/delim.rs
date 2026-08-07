//! Delimiter sniffing — a port of `uni.io.Delimiter`.
//!
//! Scores four candidates (`,` `\t` `;` `|`) over a sample of rows and returns the
//! one that best explains the file. "Best" is deliberately not "most frequent": a
//! delimiter that never appears trivially produces one field per row, which is
//! perfectly consistent, so consistency alone would let it beat the real delimiter
//! on any file with ragged rows. Candidates that never appear are excluded instead,
//! and the winner is the one giving the fewest distinct row widths, breaking ties on
//! how many rows share the modal width and then on raw hit count.
//!
//! # Tie-breaking
//!
//! Scala's `maxBy` keeps the *first* maximum; Rust's `max_by_key` keeps the last.
//! Every ranking here goes through [`max_by_first`] so the two agree on ties, which
//! is what makes a file with two equally plausible delimiters resolve the same way in
//! both languages.

/// Delimiters considered, in preference order for ties.
pub const CANDIDATES: [char; 4] = [',', '\t', ';', '|'];

const ESCAPE: char = '\\';
const QUOTE: char = '"';

/// Per-candidate tally built up while scanning the sample.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DelimState {
    /// The candidate this state is scoring.
    pub delimiter: char,
    /// Delimiters seen in the row currently being scanned.
    fields_count: usize,
    /// Total unquoted occurrences across the whole sample.
    pub score: usize,
    in_quotes: bool,
    escaped: bool,
    /// Width of each row scanned to completion.
    pub row_counts: Vec<usize>,
    /// Width of each row abandoned early — an undercount, kept separate because it
    /// cannot be compared with a complete row.
    pub partial_counts: Vec<usize>,
}

impl DelimState {
    fn new(delimiter: char) -> Self {
        Self {
            delimiter,
            fields_count: 0,
            score: 0,
            in_quotes: false,
            escaped: false,
            row_counts: Vec::new(),
            partial_counts: Vec::new(),
        }
    }

    fn feed(&mut self, c: char) {
        if self.escaped {
            self.escaped = false;
        } else if c == ESCAPE {
            self.escaped = true;
        } else if c == QUOTE {
            self.in_quotes = !self.in_quotes;
        } else if c == self.delimiter && !self.in_quotes {
            self.fields_count += 1;
            self.score += 1;
        }
    }

    /// Flushes the row in progress. `partial` routes it to [`Self::partial_counts`].
    fn record_row(&mut self, partial: bool) {
        let width = self.fields_count + 1;
        if partial {
            self.partial_counts.push(width);
        } else {
            self.row_counts.push(width);
        }
        self.fields_count = 0;
    }

    /// Most common complete-row width, or 0 when no row was scanned in full.
    #[must_use]
    pub fn mode_columns(&self) -> usize {
        mode_of(&self.row_counts)
    }

    /// How many distinct complete-row widths were seen. Lower is a better fit.
    #[must_use]
    pub fn width_distinct(&self) -> usize {
        let mut seen: Vec<usize> = Vec::new();
        for &w in &self.row_counts {
            if !seen.contains(&w) {
                seen.push(w);
            }
        }
        seen.len()
    }

    /// How many rows match [`Self::mode_columns`]. Higher is a better fit.
    #[must_use]
    pub fn mode_support(&self) -> usize {
        let m = self.mode_columns();
        if m == 0 {
            0
        } else {
            self.row_counts.iter().filter(|&&w| w == m).count()
        }
    }
}

/// The maximum by `key`, keeping the **first** on ties.
///
/// `Iterator::max_by_key` keeps the last, which would silently diverge from the
/// Scala on any tie.
fn max_by_first<T, K: Ord>(items: &[T], key: impl Fn(&T) -> K) -> Option<&T> {
    items.iter().fold(None, |best: Option<&T>, x| match best {
        Some(b) if key(b) >= key(x) => Some(b),
        _ => Some(x),
    })
}

/// Most frequent value, first-encountered winning ties.
fn mode_of(counts: &[usize]) -> usize {
    // A `Vec` of pairs rather than a map: it preserves first-seen order, which is
    // the tie-break, and these lists hold a handful of distinct widths at most.
    let mut tally: Vec<(usize, usize)> = Vec::new();
    for &w in counts {
        match tally.iter_mut().find(|(v, _)| *v == w) {
            Some(entry) => entry.1 += 1,
            None => tally.push((w, 1)),
        }
    }
    max_by_first(&tally, |&(_, n)| n).map_or(0, |&(w, _)| w)
}

/// Index of the dominant candidate, if one has clearly won.
///
/// Candidates with `score == 0` are excluded — see the module note on why absence
/// must not read as consistency.
fn dominant(states: &[DelimState], factor: usize) -> Option<usize> {
    let active: Vec<usize> = (0..states.len()).filter(|&i| states[i].score > 0).collect();
    let best = *max_by_first(&active, |&i| {
        let s = &states[i];
        (
            -(isize::try_from(s.width_distinct()).unwrap_or(isize::MAX)),
            s.mode_support(),
            s.score,
        )
    })?;

    let b = &states[best];
    let clear = active.iter().all(|&i| {
        let s = &states[i];
        i == best
            || b.width_distinct() < s.width_distinct()
            || (b.width_distinct() == s.width_distinct()
                && (b.mode_support() > s.mode_support()
                    || (b.mode_support() == s.mode_support() && b.score > s.score * factor)))
    });
    clear.then_some(best)
}

/// Sniffs a delimiter from already-decoded lines.
///
/// Splitting this from any file access keeps it testable without a filesystem, and
/// lets the caller decide how bytes became text.
#[must_use]
pub fn detect_lines<I: IntoIterator<Item = String>>(lines: I, max_rows: usize) -> DelimState {
    detect_with(lines, max_rows, 2, 100, 8000)
}

/// [`detect_lines`] with the tuning knobs exposed.
///
/// `check_interval` is how often, in characters, dominance is re-tested; `max_chars_per_row`
/// caps work on a pathologically long line.
#[must_use]
pub fn detect_with<I: IntoIterator<Item = String>>(
    lines: I,
    max_rows: usize,
    dominance_factor: usize,
    check_interval: usize,
    max_chars_per_row: usize,
) -> DelimState {
    let mut states: Vec<DelimState> = CANDIDATES.iter().copied().map(DelimState::new).collect();
    let mut winner: Option<char> = None;

    for line in lines.into_iter().take(max_rows) {
        if winner.is_some() {
            break;
        }
        let mut chars = line.chars();
        let mut idx = 0_usize;
        while idx < max_chars_per_row && winner.is_none() {
            let Some(c) = chars.next() else { break };
            for st in &mut states {
                st.feed(c);
            }
            if idx.is_multiple_of(check_interval) {
                winner = dominant(&states, dominance_factor).map(|i| states[i].delimiter);
            }
            idx += 1;
        }
        // Anything left in the line, or a mid-row decision, means this width is an
        // undercount and must not be compared against complete rows.
        let truncated = !chars.as_str().is_empty() || winner.is_some();
        for st in &mut states {
            st.record_row(truncated);
        }
        if winner.is_none() {
            winner = dominant(&states, dominance_factor).map(|i| states[i].delimiter);
        }
    }

    if let Some(d) = winner
        && let Some(s) = states.iter().find(|s| s.delimiter == d)
    {
        return s.clone();
    }
    // Nothing dominated: fall back to the highest scorer, preferring one that
    // explains the file with fewer distinct widths. If nothing scored at all, every
    // candidate is equally useless and the first one wins.
    let pool: Vec<usize> = if states.iter().any(|s| s.score > 0) {
        (0..states.len()).filter(|&i| states[i].score > 0).collect()
    } else {
        (0..states.len()).collect()
    };
    let pick = max_by_first(&pool, |&i| {
        (
            states[i].score,
            -(isize::try_from(states[i].width_distinct()).unwrap_or(isize::MAX)),
        )
    })
    .copied()
    .unwrap_or(0);
    states.swap_remove(pick)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lines(s: &str) -> Vec<String> {
        s.lines().map(str::to_owned).collect()
    }

    #[test]
    fn picks_the_obvious_delimiter() {
        for (text, want) in [
            ("a,b,c\n1,2,3\n", ','),
            ("a;b;c\n1;2;3\n", ';'),
            ("a|b|c\n1|2|3\n", '|'),
            ("a\tb\tc\n1\t2\t3\n", '\t'),
        ] {
            assert_eq!(detect_lines(lines(text), 100).delimiter, want, "for {text:?}");
        }
    }

    #[test]
    fn a_delimiter_inside_quotes_does_not_count() {
        // Every row has one semicolon outside quotes and several commas inside.
        let st = detect_lines(lines("\"a,b,c\";x\n\"d,e,f\";y\n"), 100);
        assert_eq!(st.delimiter, ';');
    }

    #[test]
    fn an_absent_candidate_never_wins_on_consistency_alone() {
        // Ragged comma data: `;` would score a flawless one-field-per-row reading.
        let st = detect_lines(lines("a,b,c\nd,e\nf,g,h,i\n"), 100);
        assert_eq!(st.delimiter, ',');
        assert!(st.score > 0);
    }

    #[test]
    fn a_file_with_no_delimiter_at_all_still_returns_something() {
        let st = detect_lines(lines("alpha\nbeta\ngamma\n"), 100);
        assert_eq!(st.score, 0);
        // First candidate wins when none scored, rather than an arbitrary one.
        assert_eq!(st.delimiter, ',');
    }

    #[test]
    fn mode_prefers_the_first_of_equally_common_widths() {
        // Scala's `maxBy` keeps the first maximum; so must this.
        assert_eq!(mode_of(&[3, 3, 5, 5]), 3);
        assert_eq!(mode_of(&[5, 5, 3, 3]), 5);
        assert_eq!(mode_of(&[]), 0);
        assert_eq!(mode_of(&[2, 7, 7]), 7);
    }

    #[test]
    fn a_decision_reached_mid_row_marks_that_row_partial() {
        // The behaviour that made reusing these widths for padding unworkable, and
        // the reason the reader measures its own. Rows here are far over the check
        // interval, so the winner lands partway through the first one.
        let wide = (0..40).map(|i| format!("value_{i:05}")).collect::<Vec<_>>().join(",");
        let st = detect_lines(vec![wide.clone(), wide], 100);
        assert_eq!(st.delimiter, ',');
        assert!(st.row_counts.is_empty(), "expected no complete rows, got {:?}", st.row_counts);
        assert!(!st.partial_counts.is_empty());
    }

    #[test]
    fn max_by_first_keeps_the_earlier_of_two_equals() {
        let v = [(1, 9), (2, 9), (3, 1)];
        assert_eq!(max_by_first(&v, |&(_, k)| k), Some(&(1, 9)));
    }

    #[test]
    fn an_empty_sample_does_not_panic() {
        let st = detect_lines(Vec::<String>::new(), 100);
        assert_eq!(st.delimiter, ',');
        assert_eq!(st.mode_columns(), 0);
        assert_eq!(st.width_distinct(), 0);
        assert_eq!(st.mode_support(), 0);
    }
}

//! `groupBy` and `merge` on a named table — Scala's `uni.io.matResultOps` on
//! `MatResult[T]`, here on [`CsvTable<f64>`] (the `T` Scala accepts is anything
//! `Fractional`, and every value is taken through `toDouble` on the way out; `f64` in is
//! the same result). Tier 3 phase (f).
//!
//! Group keys are compared as `java.lang.Double` keys are in a Scala `Map`: by
//! `doubleToLongBits`, so `-0.0` and `0.0` are different groups and all NaNs are one.
//! Groups keep first-appearance order (Scala's `LinkedHashMap`); a join emits left rows
//! in order and, per left row, the matching right rows in their order — bit for bit the
//! Scala layout. Aggregates go through `MatD` (`sum` `mean` `min` `max` `std`), so they
//! carry the same association order and `Ordering[Double]` as the JVM.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name; see the note in mat.rs"
)]

use std::collections::HashMap;

use ndarray::Array2;

use crate::udata::MatD;
use crate::upath::matcsv::CsvTable;

/// Scala's `uni.io.AggOp`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum AggOp {
    /// Arithmetic mean of the group (`Mat.mean`).
    Mean,
    /// Sum (`Mat.sum`).
    Sum,
    /// Smallest under `Ordering[Double]`.
    Min,
    /// Largest under `Ordering[Double]`.
    Max,
    /// Number of rows in the group.
    Count,
    /// Population standard deviation (`Mat.std`).
    Std,
}

impl AggOp {
    fn suffix(self) -> &'static str {
        match self {
            Self::Mean => "mean",
            Self::Sum => "sum",
            Self::Min => "min",
            Self::Max => "max",
            Self::Count => "count",
            Self::Std => "std",
        }
    }
}

/// Scala's `uni.io.JoinType`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum JoinType {
    /// Rows whose key appears on both sides.
    Inner,
    /// Every left row; unmatched right columns are NaN.
    Left,
    /// Every right row; unmatched left columns are NaN.
    Right,
}

/// `java.lang.Double.doubleToLongBits`: every NaN collapses to one key.
fn key_bits(d: f64) -> u64 {
    if d.is_nan() {
        0x7ff8_0000_0000_0000
    } else {
        d.to_bits()
    }
}

/// Rows grouped by the bits of column `col`, in first-appearance order.
fn group_rows(mat: &Array2<f64>, col: usize) -> Vec<(f64, Vec<usize>)> {
    let mut order: Vec<(f64, Vec<usize>)> = Vec::new();
    let mut index: HashMap<u64, usize> = HashMap::new();
    for r in 0..mat.nrows() {
        let key = mat[(r, col)];
        match index.get(&key_bits(key)) {
            Some(&g) => order[g].1.push(r),
            None => {
                index.insert(key_bits(key), order.len());
                order.push((key, vec![r]));
            }
        }
    }
    order
}

fn table(headers: Vec<String>, flat: Vec<f64>, width: usize) -> CsvTable<f64> {
    let rows = flat.len().checked_div(width).unwrap_or(0);
    let mat = Array2::from_shape_vec((rows, width), flat)
        .unwrap_or_else(|_| Array2::from_elem((0, width), 0.0));
    CsvTable { headers, mat }
}

impl CsvTable<f64> {
    fn index_of(&self, what: &str, name: &str) -> usize {
        let found = self.column_index(name);
        assert!(
            found.is_some(),
            "{what}: column '{name}' not found in {:?}",
            self.headers
        );
        found.unwrap_or(0)
    }

    /// Scala's `groupBy(keyCol, op)`: group by the distinct values of `key_col` and apply
    /// `op` to every other column. Output headers: `key_col`, then `"<col>_<op>"`.
    ///
    /// # Panics
    /// If `key_col` is not a header.
    #[must_use]
    pub fn groupBy(&self, key_col: &str, op: AggOp) -> Self {
        let others: Vec<(&str, AggOp)> = self
            .headers
            .iter()
            .filter(|h| h.as_str() != key_col)
            .map(|h| (h.as_str(), op))
            .collect();
        self.groupByOps(key_col, &others)
    }

    /// Scala's `groupBy(keyCol, aggOps)`: a per-column operation. Aggregated columns come
    /// out in header order whatever order `agg_ops` lists them in.
    ///
    /// # Panics
    /// If `key_col` or any aggregated column is not a header.
    #[must_use]
    pub fn groupByOps(&self, key_col: &str, agg_ops: &[(&str, AggOp)]) -> Self {
        let key_idx = self.index_of("groupBy", key_col);
        let ops: HashMap<&str, AggOp> = agg_ops.iter().copied().collect();
        for (c, _) in agg_ops {
            assert!(
                self.column_index(c).is_some(),
                "groupBy: aggregated column '{c}' not found in {:?}",
                self.headers
            );
        }
        let agg_cols: Vec<(usize, &str, AggOp)> = self
            .headers
            .iter()
            .enumerate()
            .filter_map(|(i, h)| ops.get(h.as_str()).map(|&op| (i, h.as_str(), op)))
            .collect();
        let mut out_headers = vec![key_col.to_owned()];
        out_headers.extend(
            agg_cols
                .iter()
                .map(|(_, c, op)| format!("{c}_{}", op.suffix())),
        );
        let width = out_headers.len();
        let mut flat = Vec::new();
        for (key, rows) in group_rows(&self.mat, key_idx) {
            flat.push(key);
            for &(ci, _, op) in &agg_cols {
                let col: Vec<f64> = rows.iter().map(|&r| self.mat[(r, ci)]).collect();
                let v = MatD::create(col, rows.len(), 1);
                #[expect(clippy::cast_precision_loss, reason = "a row count")]
                let n = rows.len() as f64;
                flat.push(match op {
                    AggOp::Mean => v.mean(),
                    AggOp::Sum => v.sum(),
                    AggOp::Min => v.min(),
                    AggOp::Max => v.max(),
                    AggOp::Count => n,
                    AggOp::Std => v.std(),
                });
            }
        }
        table(out_headers, flat, width)
    }

    /// Scala's `merge(right, on, how)`: join on a numeric key column. `on` appears once;
    /// columns present on both sides are suffixed `_x` / `_y`; outer-join gaps are NaN.
    ///
    /// # Panics
    /// If `on` is not a header on both sides.
    #[must_use]
    pub fn merge(&self, right: &Self, on: &str, how: JoinType) -> Self {
        let l_key = self.index_of("merge", on);
        let r_key = right.index_of("merge", on);
        let mut build: HashMap<u64, Vec<usize>> = HashMap::new();
        for r in 0..right.mat.nrows() {
            build
                .entry(key_bits(right.mat[(r, r_key)]))
                .or_default()
                .push(r);
        }
        let l_cols: Vec<(&str, usize)> = self
            .headers
            .iter()
            .enumerate()
            .filter(|&(i, _)| i != l_key)
            .map(|(i, h)| (h.as_str(), i))
            .collect();
        let r_cols: Vec<(&str, usize)> = right
            .headers
            .iter()
            .enumerate()
            .filter(|&(i, _)| i != r_key)
            .map(|(i, h)| (h.as_str(), i))
            .collect();
        let l_names: Vec<&str> = l_cols.iter().map(|c| c.0).collect();
        let r_names: Vec<&str> = r_cols.iter().map(|c| c.0).collect();
        let mut out_headers = vec![on.to_owned()];
        out_headers.extend(l_cols.iter().map(|(n, _)| {
            if r_names.contains(n) {
                format!("{n}_x")
            } else {
                (*n).to_owned()
            }
        }));
        out_headers.extend(r_cols.iter().map(|(n, _)| {
            if l_names.contains(n) {
                format!("{n}_y")
            } else {
                (*n).to_owned()
            }
        }));
        let width = out_headers.len();
        let mut flat = Vec::new();
        let mut right_matched = vec![false; right.mat.nrows()];
        for l_row in 0..self.mat.nrows() {
            let key = self.mat[(l_row, l_key)];
            match build.get(&key_bits(key)) {
                Some(matches) => {
                    for &r_row in matches {
                        right_matched[r_row] = true;
                        flat.push(key);
                        flat.extend(l_cols.iter().map(|&(_, li)| self.mat[(l_row, li)]));
                        flat.extend(r_cols.iter().map(|&(_, ri)| right.mat[(r_row, ri)]));
                    }
                }
                None if how == JoinType::Left => {
                    flat.push(key);
                    flat.extend(l_cols.iter().map(|&(_, li)| self.mat[(l_row, li)]));
                    flat.extend(r_cols.iter().map(|_| f64::NAN));
                }
                None => {}
            }
        }
        if how == JoinType::Right {
            for (r_row, matched) in right_matched.iter().enumerate() {
                if !matched {
                    flat.push(right.mat[(r_row, r_key)]);
                    flat.extend(l_cols.iter().map(|_| f64::NAN));
                    flat.extend(r_cols.iter().map(|&(_, ri)| right.mat[(r_row, ri)]));
                }
            }
        }
        table(out_headers, flat, width)
    }
}

#[cfg(test)]
mod tests {
    use ndarray::Array2;

    use super::AggOp;
    use super::JoinType;
    use crate::upath::matcsv::CsvTable;

    fn t(headers: &[&str], rows: &[&[f64]]) -> CsvTable<f64> {
        let w = headers.len();
        let flat: Vec<f64> = rows.iter().flat_map(|r| r.iter().copied()).collect();
        CsvTable {
            headers: headers.iter().map(|s| (*s).to_owned()).collect(),
            mat: Array2::from_shape_vec((rows.len(), w), flat).expect("rectangular"),
        }
    }

    #[test]
    fn group_by_means_in_first_appearance_order() {
        let x = t(
            &["sector", "price", "vol"],
            &[
                &[2.0, 10.0, 1.0],
                &[1.0, 20.0, 2.0],
                &[2.0, 30.0, 3.0],
                &[1.0, 40.0, 4.0],
            ],
        );
        let g = x.groupBy("sector", AggOp::Mean);
        assert_eq!(g.headers, vec!["sector", "price_mean", "vol_mean"]);
        assert_eq!(g.mat.row(0).to_vec(), vec![2.0, 20.0, 2.0]);
        assert_eq!(g.mat.row(1).to_vec(), vec![1.0, 30.0, 3.0]);
        let g2 = x.groupByOps("sector", &[("vol", AggOp::Sum), ("price", AggOp::Max)]);
        assert_eq!(g2.headers, vec!["sector", "price_max", "vol_sum"]);
        assert_eq!(g2.mat.row(0).to_vec(), vec![2.0, 30.0, 4.0]);
        let g3 = x.groupByOps("sector", &[("price", AggOp::Count), ("vol", AggOp::Std)]);
        assert_eq!(g3.mat.row(1).to_vec(), vec![1.0, 2.0, 1.0]);
    }

    #[test]
    fn merge_inner_left_right() {
        let a = t(&["id", "p"], &[&[1.0, 10.0], &[2.0, 20.0], &[3.0, 30.0]]);
        let b = t(
            &["id", "p", "q"],
            &[&[2.0, 200.0, 2.5], &[4.0, 400.0, 4.5], &[2.0, 201.0, 2.6]],
        );
        let inner = a.merge(&b, "id", JoinType::Inner);
        assert_eq!(inner.headers, vec!["id", "p_x", "p_y", "q"]);
        assert_eq!(inner.rows(), 2);
        assert_eq!(inner.mat.row(0).to_vec(), vec![2.0, 20.0, 200.0, 2.5]);
        assert_eq!(inner.mat.row(1).to_vec(), vec![2.0, 20.0, 201.0, 2.6]);
        let left = a.merge(&b, "id", JoinType::Left);
        assert_eq!(left.rows(), 4);
        assert!(left.mat[(0, 2)].is_nan() && left.mat[(0, 0)] == 1.0);
        let right = a.merge(&b, "id", JoinType::Right);
        assert_eq!(right.rows(), 3);
        assert_eq!(right.mat.row(2).to_vec()[0], 4.0);
        assert!(right.mat[(2, 1)].is_nan());
    }
}

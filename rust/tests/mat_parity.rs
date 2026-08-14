//! Checks the `MatD` reduction family against the committed reference in
//! `test-data/mat-parity/`, produced by the Scala implementation.
//!
//! The Scala side (`uni.data.MatParitySuite`) checks itself against the same file, so
//! the pair pins both implementations to one association order without either test
//! needing the other language installed. A change that moves Rust's sums fails here; a
//! change that moves only Scala's fails there.
//!
//! # What this is really testing
//!
//! Not correctness of the sum — a naive fold passes that. What is pinned is the
//! floating-point ASSOCIATION ORDER of `Mat.sumD`: 8 unrolled accumulators combined as
//! `((s0+s1)+(s2+s3))+((s4+s5)+(s6+s7))`, then `min(MaxSumChunks=16, n/4096)` chunks
//! whose partials are combined sequentially. `iter().sum()` lands on a different last
//! ulp and would fail here, which is the point: any byte-identical Scala/Rust demo pair
//! built on these reductions depends on it.
//!
//! Comparisons are therefore on raw bit patterns. A tolerance would wave through exactly
//! the drift this exists to catch.
//!
//! Inputs are not carried in the fixture; both sides rebuild them from `NumPyRng`, which
//! is already bit-identical across the port. `math.sin` was rejected as a generator
//! because the JVM and Rust may round it differently in the last ulp.
//!
//! Regenerate the reference with `sbt "runMain uni.apps.MatParityGen"` — and only when
//! the values are meant to move.

#![allow(
    clippy::expect_used,
    clippy::panic,
    reason = "a missing or malformed fixture should abort the test loudly, not be handled"
)]

use std::fs;
use std::path::PathBuf;

use uni::NumPyRng;
use uni::udata::MatD;

/// Must match `MatParityGen.Seed`.
const SEED: u64 = 20_260_814;

fn fixture() -> String {
    let path: PathBuf = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../test-data/mat-parity/scala-reference.txt");
    fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()))
}

/// The shared corpus — must match `MatParityGen.corpus` exactly, including the
/// alternating magnitudes, which are what make association order observable.
fn corpus(n: usize) -> Vec<f64> {
    let mut rng = NumPyRng::new(SEED);
    (0..n)
        .map(|i| {
            if i % 2 == 0 {
                rng.uniform(-1e6, 1e6)
            } else {
                rng.uniform(-1e-6, 1e-6)
            }
        })
        .collect()
}

/// The recorded reductions, keyed as in `MatParityGen.cases`.
fn case_value(m: &MatD, label: &str) -> f64 {
    match label {
        "sum" => m.sum(),
        "mean" => m.mean(),
        "abssum" => m.abs().sum(),
        "sqsum" => m.power(2).sum(),
        "sqmean" => m.power(2).mean(),
        "cumlast" => {
            if m.isEmpty() {
                0.0
            } else {
                *m.cumsum()
                    .toArray()
                    .last()
                    .expect("non-empty checked immediately above")
            }
        }
        other => panic!("unknown case {other} in fixture — port it or regenerate"),
    }
}

#[test]
fn mat_reductions_match_the_scala_reference_bit_for_bit() {
    let text = fixture();
    let rows: Vec<(usize, String, u64)> = text
        .lines()
        .filter(|l| !l.starts_with('#') && !l.trim().is_empty())
        .map(|l| {
            let mut it = l.split_whitespace();
            let n: usize = it.next().expect("missing n").parse().expect("n not a number");
            let label = it.next().expect("missing case label").to_string();
            let bits = u64::from_str_radix(it.next().expect("missing hex"), 16)
                .expect("hex not parseable");
            (n, label, bits)
        })
        .collect();

    assert!(!rows.is_empty(), "fixture carried no rows");

    // Draw once to the largest size; every smaller size takes a prefix, exactly as the
    // generator does.
    let max_n = rows.iter().map(|(n, _, _)| *n).max().expect("rows non-empty");
    let all = corpus(max_n);

    let mut checked = 0usize;
    for (n, label, want) in &rows {
        let m = MatD::apply(&all[..*n]);
        let got = case_value(&m, label);
        assert_eq!(
            got.to_bits(),
            *want,
            "n={n} case={label}: got {:016x} ({got:e}), want {:016x} — association order drifted",
            got.to_bits(),
            want
        );
        checked += 1;
    }

    // Guard against a fixture that silently shrinks to nothing meaningful.
    assert!(
        checked >= 60,
        "only {checked} rows checked; the fixture should cover 13 sizes x 6 cases"
    );
}

#[test]
fn the_corpus_is_sensitive_to_association_order() {
    // A test that cannot fail proves nothing. This asserts the corpus actually
    // DISTINGUISHES association orders: a naive left fold must disagree with sumD at a
    // size above the chunking threshold. If this ever passes trivially, the parity test
    // above has stopped being evidence.
    let a = corpus(200_000);
    let chunked = MatD::apply(&a).sum();
    let naive = a.iter().fold(0.0f64, |acc, &x| acc + x);
    assert_ne!(
        chunked.to_bits(),
        naive.to_bits(),
        "corpus no longer distinguishes a naive fold from sumD — the parity fixture \
         would pass even for a wrong implementation"
    );
}

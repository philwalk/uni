//! Checks the `NumPyRng` port against the committed reference in
//! `test-data/numpy-rng-parity/`, produced by the Scala implementation.
//!
//! The Scala side (`uni.data.NumPyRngParitySuite`) checks itself against the
//! same file, so the pair pins both implementations to one stream without either
//! test needing the other language installed. A change that moves Rust's draws
//! fails here; a change that moves only Scala's fails there.
//!
//! Comparisons are on bit patterns, not measurements: the failure worth catching
//! is a generator that desynchronizes partway through a stream, and a loose
//! numeric comparison would hide exactly that. Every case is exact except
//! `randn`, which is snapped to a 2^-40 grid first — see [`quantize`].
//!
//! Regenerate the reference with `sbt "runMain uni.apps.NumPyRngParityGen"` —
//! and only when the values are meant to move.

#![allow(
    clippy::expect_used,
    clippy::panic,
    reason = "a missing or malformed fixture should abort the test loudly, not be handled"
)]
#![allow(
    clippy::cast_sign_loss,
    clippy::cast_possible_truncation,
    reason = "reproducing the Scala fixture's widening of Int draws to 64-bit words"
)]

use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;

use t3prf::NumPyRng;

/// Must match `NumPyRngParityGen`: FNV-1a over 64-bit words.
const FNV_OFFSET: u64 = 0xcbf2_9ce4_8422_2325;
const FNV_PRIME: u64 = 0x100_0000_01b3;

/// Must match `NumPyRngParityGen.QuantumScale`: 2^40.
const QUANTUM_SCALE: f64 = 1099511627776.0;

/// Snaps a `randn` draw to a 2^-40 grid, matching `NumPyRngParityGen.quantize`.
///
/// `randn`'s tail runs through `log1p`, and the JVM and C round it differently
/// on roughly 2.5 tail draws per million — always by exactly one ulp. This port
/// agrees with NumPy bit-for-bit, so it is the JVM that is the outlier and
/// neither answer is wrong. A digest cannot express a one-ulp tolerance, so both
/// sides snap to a grid coarse enough to absorb it and far finer than any real
/// difference: see the Scala doc comment for the full argument.
///
/// `floor(x + 0.5)`, not `round()`: Java rounds a negative half up and Rust
/// rounds it away from zero, so only this form is identical in both languages.
#[expect(
    clippy::cast_possible_truncation,
    reason = "|randn| stays far below 2^23, so the scaled value cannot approach i64 range"
)]
fn quantize(v: f64) -> u64 {
    ((v * QUANTUM_SCALE + 0.5).floor() as i64) as u64
}

fn reference_path() -> PathBuf {
    // CARGO_MANIFEST_DIR is <repo>/rust; the fixture is shared with Scala.
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../test-data/numpy-rng-parity/scala-reference.txt")
        .canonicalize()
        .expect(
            "test-data/numpy-rng-parity missing — regenerate with: \
             sbt \"runMain uni.apps.NumPyRngParityGen\"",
        )
}

/// One case in the fixture: a seed and a draw-kind label.
type CaseKey = (u64, String);

#[derive(Default)]
struct Reference {
    /// Verbatim leading draws, in order.
    heads: HashMap<CaseKey, Vec<u64>>,
    /// Draw count and the expected digest over that many draws.
    digests: HashMap<CaseKey, (usize, u64)>,
}

/// Parses the whitespace-delimited fixture:
///   `<seed> <case> head <index> <hex>` and `<seed> <case> fnv <count> <hex>`.
fn load() -> Reference {
    let path = reference_path();
    let text = fs::read_to_string(&path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()));
    let mut reference = Reference::default();

    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let field: Vec<&str> = line.split_whitespace().collect();
        let [seed, case, kind, n, hex] = field[..] else {
            panic!("malformed fixture line: {line}");
        };
        let seed: u64 = seed.parse().expect("seed");
        let n: usize = n.parse().expect("index or count");
        let value = u64::from_str_radix(hex, 16).expect("hex value");
        let key = (seed, case.to_owned());

        match kind {
            "head" => {
                let head = reference.heads.entry(key).or_default();
                assert_eq!(head.len(), n, "head indices out of order: {line}");
                head.push(value);
            }
            "fnv" => {
                reference.digests.insert(key, (n, value));
            }
            other => panic!("unknown record kind {other}: {line}"),
        }
    }
    reference
}

/// The i-th draw of `case`, reduced to a 64-bit word exactly as
/// `NumPyRngParityGen` reduces it on the Scala side.
fn draw(case: &str, i: usize, rng: &mut NumPyRng) -> u64 {
    match case {
        "u64" => rng.next_u64(),
        "i32" => u64::from(rng.next_i32() as u32),
        "f64" => rng.next_f64().to_bits(),
        "uniform" => rng.uniform(-2.5, 7.25).to_bits(),
        "bounded6" => u64::from(rng.next_bounded_u32(6)),
        "randn" => quantize(rng.randn()),
        // Interleaved, to pin the half-draw `next_bounded_u32` carries in state
        // across calls to the other methods.
        "mixed" => match i % 5 {
            0 => rng.next_u64(),
            1 => u64::from(rng.next_bounded_u32(1 + (i % 97) as u32)),
            2 => rng.next_f64().to_bits(),
            3 => quantize(rng.randn()),
            _ => u64::from(rng.next_bounded_u32(1_000_000)),
        },
        other => panic!("fixture names case {other}, which this test cannot generate"),
    }
}

#[test]
fn matches_scala_reference() {
    let reference = load();
    assert!(
        !reference.digests.is_empty(),
        "fixture parsed but held no cases"
    );

    // Sorted so a failure names the same cases in the same order run to run.
    let mut cases: Vec<(&CaseKey, &(usize, u64))> = reference.digests.iter().collect();
    cases.sort_unstable();

    // Every case is run before anything is asserted. Which cases diverge is the
    // most useful signal there is here — one method is a bug in that method,
    // everything is a bug in the seeding or the state advance — and aborting on
    // the alphabetically-first failure would throw that away.
    let mut failures: Vec<String> = Vec::new();

    for (key @ (seed, case), &(count, want_digest)) in cases {
        let head = reference
            .heads
            .get(key)
            .expect("every digest line has matching head lines");
        let mut rng = NumPyRng::new(*seed);
        let mut digest = FNV_OFFSET;
        let mut first_bad: Option<(usize, u64, u64)> = None;

        for i in 0..count {
            let word = draw(case, i, &mut rng);
            if let Some(&want) = head.get(i)
                && word != want
                && first_bad.is_none()
            {
                first_bad = Some((i, word, want));
            }
            digest = (digest ^ word).wrapping_mul(FNV_PRIME);
        }

        match first_bad {
            Some((i, got, want)) => failures.push(format!(
                "seed {seed} case {case}: draw {i} is {got:016x}, reference {want:016x}"
            )),
            None if digest != want_digest => failures.push(format!(
                "seed {seed} case {case}: first {} draws match, but the digest over \
                 {count} does not ({digest:016x} vs {want_digest:016x}) — \
                 the streams diverge later in the run",
                head.len()
            )),
            None => {}
        }
    }

    assert!(
        failures.is_empty(),
        "{} of the reference cases diverged:\n  {}",
        failures.len(),
        failures.join("\n  ")
    );
}

/// Pins specific tail draws against NumPy itself, by absolute value.
///
/// The rest of this file compares against a regenerable Scala fixture, which
/// cannot catch a constant that is wrong in both languages at once: regenerating
/// would simply bless the new numbers. These are `np.random.default_rng(0)
/// .standard_normal(...)` outputs, and they are tail draws, so they exercise
/// `ZIGNOR_R` and `ziggurat_nor_inv_r` — the constants that produce a plausible
/// bell curve with a displaced tail when wrong. An earlier `ZIGNOR_R` of
/// 3.442619855899 (Marsaglia & Tsang's, not NumPy's) put every one of these out
/// by 0.2115 while leaving 99.97% of draws correct.
///
/// Verified to hold for Scala, Rust and NumPy alike; the Scala suite asserts the
/// same four values.
#[test]
fn tail_draws_match_numpy_absolutely() {
    // (index into the seed-0 standard_normal stream, NumPy's exact bits)
    const ANCHORS: &[(usize, u64)] = &[
        (303, 0xc00e_2d9e_98d8_7bc6),
        (478, 0xc00f_3204_051f_2938),
        (10477, 0x400f_907c_5aa2_ff63),
        (13325, 0xc00f_5ad7_82db_91bd),
    ];

    let last = ANCHORS
        .iter()
        .map(|&(i, _)| i)
        .max()
        .expect("anchors is non-empty");
    let mut rng = NumPyRng::new(0);
    let mut checked = 0_usize;

    for i in 0..=last {
        let value = rng.randn();
        if let Some(&(_, want)) = ANCHORS.iter().find(|&&(index, _)| index == i) {
            assert_eq!(
                value.to_bits(),
                want,
                "draw {i}: got {value:.17e} ({:016x}), NumPy gives {:.17e} ({want:016x})",
                value.to_bits(),
                f64::from_bits(want)
            );
            checked += 1;
        }
    }
    assert_eq!(checked, ANCHORS.len(), "not every anchor was reached");
}

#[test]
#[should_panic(expected = "bound must be positive")]
fn zero_bound_is_rejected() {
    // Mirrors the Scala `require(bound > 0)`: Lemire's multiply would quietly
    // return 0 for an empty range rather than saying anything was wrong.
    let _ = NumPyRng::new(0).next_bounded_u32(0);
}

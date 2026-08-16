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
//! # The 2-D half
//!
//! Rows keyed `<rows>x<cols>` pin the **view model** rather than the arithmetic. In
//! Scala `transpose`/`slice`/`broadcastTo` return zero-copy strided views, and the
//! layout decides which summation ORDER runs — contiguous at offset 0 gets the chunked,
//! 8-way unrolled `sumD`, anything else is summed left to right through the stride
//! equation. So `m.sum` and `m.T.sum` are not required to agree, and on this corpus they
//! do not. A port that materialised every view into a contiguous copy would pick the
//! chunked order where Scala picks the sequential one, and only these rows would
//! notice.
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
use uni::udata::MatBool;
use uni::udata::MatD;

/// A fixture row's first field: a 1-D prefix length, a 2-D `<rows>x<cols>`, or an
/// `adv/<name>/<orientation>` ordering case.
enum Shape {
    Column(usize),
    Matrix(usize, usize),
    Adversarial(String, String),
}

/// The adversarial arrays, which must match `MatParityGen.adversarial` element for
/// element. These carry the values the random corpus cannot produce — NaN, signed zeros,
/// infinities — which is where `<`/`>` and Scala's `Ordering[Double]` part company.
fn adversarial_array(name: &str) -> Vec<f64> {
    let neg_nan = f64::from_bits(0xfff8_0000_0000_0000);
    match name {
        "nanmid" => vec![1.0, f64::NAN, -1.0, 2.0],
        "nanfirst" => vec![f64::NAN, 1.0, -1.0],
        "nanonly" => vec![f64::NAN, f64::NAN],
        "negnan" => vec![neg_nan, 1.0, -1.0],
        "zeros" => vec![0.0, -0.0],
        "zerosrev" => vec![-0.0, 0.0],
        "zerosmix" => vec![1.0, -0.0, 0.0, -1.0],
        "infs" => vec![f64::INFINITY, f64::NEG_INFINITY, 0.0],
        "infnan" => vec![f64::INFINITY, f64::NAN, f64::NEG_INFINITY],
        "ties" => vec![3.0, 1.0, 1.0, 3.0],
        other => panic!("unknown adversarial array {other}"),
    }
}

/// The three orientations, matching `MatParityGen.advShapes`. `colT` is a transposed
/// *view*, so the strided read path meets the same values.
fn adv_matrix(orient: &str, arr: Vec<f64>) -> MatD {
    let n = arr.len();
    match orient {
        "col" => MatD::create(arr, n, 1),
        "row" => MatD::create(arr, 1, n),
        "colT" => MatD::create(arr, n, 1).T(),
        other => panic!("unknown orientation {other}"),
    }
}

/// Bits with every NaN collapsed to one pattern — `MatParityGen.canon`.
///
/// IEEE does not specify which NaN payload an arithmetic operation propagates, so a raw
/// bit comparison of a sum over NaN would pin a platform's choice rather than the
/// language's semantics. This is also exactly what `Double.doubleToLongBits` does, which
/// is the comparison under test.
fn canon(d: f64) -> u64 {
    if d.is_nan() {
        0x7ff8_0000_0000_0000
    } else {
        d.to_bits()
    }
}

fn fnv_canon(xs: &[f64]) -> u64 {
    fnv_words(xs.iter().map(|&x| canon(x)))
}

/// The ordering cases. Every one of these was passing while `min`/`max` compared with
/// `<`/`>`, because the random corpus contains no NaN and no signed zero.
fn case_word_adv(m: &MatD, label: &str) -> u64 {
    let cols = m.cols() as u64;
    match label {
        "sum" => canon(m.sum()),
        "min" => canon(m.min()),
        "max" => canon(m.max()),
        "argmin" => {
            let (r, c) = m.argmin();
            r as u64 * cols + c as u64
        }
        "argmax" => {
            let (r, c) = m.argmax();
            r as u64 * cols + c as u64
        }
        "cmax0fnv" => fnv_canon(&m.cummax(0).toArray()),
        "cmax1fnv" => fnv_canon(&m.cummax(1).toArray()),
        "cmin0fnv" => fnv_canon(&m.cummin(0).toArray()),
        "cmin1fnv" => fnv_canon(&m.cummin(1).toArray()),
        "min0fnv" => fnv_canon(&m.minAxis(0).toArray()),
        "max1fnv" => fnv_canon(&m.maxAxis(1).toArray()),
        other => case_word_mask(m, other),
    }
}

/// A mask as an FNV digest, true as 1 — `MatParityGen.fnvMask`.
fn fnv_mask(mask: &MatBool) -> u64 {
    fnv_words(mask.toArray().into_iter().map(u64::from))
}

/// The mask family, over the same adversarial arrays as the ordering cases above.
///
/// These pin the OPPOSITE rule: the ordering cases use `java_double_compare`, where NaN
/// outranks every number and `-0.0 < 0.0`; masks are IEEE, so every comparison involving
/// NaN is false and `-0.0 == 0.0`. Routing masks through the ordering comparator passes
/// every row above and fails every row here.
fn case_word_mask(m: &MatD, label: &str) -> u64 {
    let pos = m.gt(0.0);
    let finite = m.isfinite();
    match label {
        "mask.gt0" => fnv_mask(&pos),
        "mask.lt0" => fnv_mask(&m.lt(0.0)),
        "mask.gte0" => fnv_mask(&m.gte(0.0)),
        "mask.lte0" => fnv_mask(&m.lte(0.0)),
        "mask.eq0" => fnv_mask(&m.eqTo(0.0)),
        "mask.ne0" => fnv_mask(&m.neTo(0.0)),
        "mask.eqnan" => fnv_mask(&m.eqTo(f64::NAN)),
        "mask.nenan" => fnv_mask(&m.neTo(f64::NAN)),
        "mask.gtinf" => fnv_mask(&m.gt(f64::INFINITY)),
        "mask.gteinf" => fnv_mask(&m.gte(f64::INFINITY)),
        "mask.ltninf" => fnv_mask(&m.lt(f64::NEG_INFINITY)),
        "mask.isnan" => fnv_mask(&m.isnan()),
        "mask.isinf" => fnv_mask(&m.isinf()),
        "mask.isfinite" => fnv_mask(&finite),
        "mask.and" => fnv_mask(&(&pos & &finite)),
        "mask.or" => fnv_mask(&(&pos | &finite)),
        "mask.not" => fnv_mask(&!&pos),
        "mask.all" => u64::from(pos.all()),
        "mask.any" => u64::from(pos.any()),
        "mask.all0" => fnv_mask(&pos.allAxis(0)),
        "mask.any0" => fnv_mask(&pos.anyAxis(0)),
        "mask.all1" => fnv_mask(&pos.allAxis(1)),
        "mask.any1" => fnv_mask(&pos.anyAxis(1)),
        "mask.selfnv" => fnv_canon(&m.applyMask(&pos).toArray()),
        "mask.selshape" => {
            let sel = m.applyMask(&pos);
            sel.rows() as u64 * 1000 + sel.cols() as u64
        }
        "mask.wherefnv" => fnv_canon(&pos.whereMat(m, &(m * -1.0)).toArray()),
        "mask.wheresfnv" => fnv_canon(&pos.whereScalar(1.0, -1.0).toArray()),
        other => panic!("unknown adversarial case {other} in fixture"),
    }
}

/// Must match `MatParityGen.Seed`.
const SEED: u64 = 20_260_814;

fn fixture() -> String {
    let path: PathBuf = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../test-data/mat-parity/scala-reference.txt");
    fs::read_to_string(&path).unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()))
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

/// The second corpus — must match `MatParityGen.corpusExp`. Kept in a range where `exp`
/// does not overflow, mirroring where marketSim actually applies it.
fn corpus_exp(n: usize) -> Vec<f64> {
    let mut rng = NumPyRng::new(SEED + 1);
    (0..n).map(|_| rng.uniform(-5.0, 0.5)).collect()
}

/// FNV-1a over 64-bit words — must match `MatParityGen.fnv`.
const FNV_OFFSET: u64 = 0xcbf2_9ce4_8422_2325;
const FNV_PRIME: u64 = 0x100_0000_01b3;

fn fnv(xs: &[f64]) -> u64 {
    fnv_words(xs.iter().map(|x| x.to_bits()))
}

fn fnv_words(ws: impl Iterator<Item = u64>) -> u64 {
    let mut digest = FNV_OFFSET;
    for w in ws {
        digest = (digest ^ w).wrapping_mul(FNV_PRIME);
    }
    digest
}

/// Must match `MatParityGen.QuantumScale`: 2^40.
const QUANTUM_SCALE: f64 = 1099511627776.0;

/// Snaps to a 2^-40 grid — must match `MatParityGen.quantize`.
///
/// `exp` is the one operation in this fixture that is NOT bit-identical across the port.
/// Measured: Java's `Math.exp` and Rust's `f64::exp` differ on 0.561% of 200,000 values,
/// never by more than 1 ulp, and exact agreement is unavailable (see the note in
/// `MatParityGen.quantize`). `floor(x * scale + 0.5)`, not `round`/`rint`, because the
/// languages disagree on exact halves and this form does not.
#[expect(
    clippy::cast_possible_truncation,
    reason = "mirroring the Scala fixture's .toLong on an already-floored value"
)]
fn quantize(d: f64) -> u64 {
    (d * QUANTUM_SCALE + 0.5).floor() as i64 as u64
}

/// Every recorded case, as the raw 64-bit word the fixture carries. Reductions go in as
/// their IEEE bits; the `*fnv` cases are already digests.
fn case_word(m: &MatD, me: &MatD, label: &str) -> u64 {
    match label {
        "sum" => m.sum().to_bits(),
        "mean" => m.mean().to_bits(),
        "abssum" => m.abs().sum().to_bits(),
        "sqsum" => m.power(2).sum().to_bits(),
        "sqmean" => m.power(2).mean().to_bits(),
        "cumlast" => {
            if m.isEmpty() {
                0.0f64.to_bits()
            } else {
                m.cumsum()
                    .toArray()
                    .last()
                    .copied()
                    .unwrap_or(0.0)
                    .to_bits()
            }
        }
        // The exp-corpus cases. Scala records 0 for all of them when the prefix is
        // empty, since min/max are undefined there.
        //
        // `expq` is digested over QUANTIZED values — the only case here not compared
        // bit-for-bit, because the two libms genuinely disagree by up to 1 ulp.
        "expq" => {
            if me.isEmpty() {
                0
            } else {
                fnv_words(me.exp().toArray().into_iter().map(quantize))
            }
        }
        "min" => {
            if me.isEmpty() {
                0
            } else {
                me.min().to_bits()
            }
        }
        "max" => {
            if me.isEmpty() {
                0
            } else {
                me.max().to_bits()
            }
        }
        "cummaxfnv" => {
            if me.isEmpty() {
                0
            } else {
                fnv(&me.cummax(0).toArray())
            }
        }
        other => panic!("unknown case {other} in fixture — port it or regenerate"),
    }
}

/// The 2-D cases. Each pins something the LAYOUT decides rather than the arithmetic:
/// `t*` run on a transposed view (summed in a different order), `slice*` on a slice
/// whose materialisation depends on the parent's aspect ratio, and `bcast*` on the 1×N
/// and N×1 broadcast shapes.
///
/// `std0fnv` and `tstd1fnv` reduce the SAME lanes by the same formula and are expected
/// to differ above a lane length of 8: Scala's `std(axis)` takes each lane's mean by a
/// plain fold when contiguous and via `sumD` when not.
fn case_word_2d(m: &MatD, label: &str) -> u64 {
    let (rows, cols) = (m.rows() as i64, m.cols() as i64);
    let sliced = || m.slice(0..rows, 1..cols);
    match label {
        "tsum" => m.T().sum().to_bits(),
        "tmean" => m.T().mean().to_bits(),
        "tstd" => m.T().std().to_bits(),
        "std" => m.std().to_bits(),
        "var" => m.variance().to_bits(),
        "slicesum" => sliced().sum().to_bits(),
        "normr" => m.ravel().norm().to_bits(),
        "sum0fnv" => fnv(&m.sumAxis(0).toArray()),
        "sum1fnv" => fnv(&m.sumAxis(1).toArray()),
        "mean0fnv" => fnv(&m.meanAxis(0).toArray()),
        "mean1fnv" => fnv(&m.meanAxis(1).toArray()),
        "min0fnv" => fnv(&m.minAxis(0).toArray()),
        "max1fnv" => fnv(&m.maxAxis(1).toArray()),
        "std0fnv" => fnv(&m.stdAxis(0).toArray()),
        "tstd1fnv" => fnv(&m.T().stdAxis(1).toArray()),
        "cum0fnv" => fnv(&m.cumsumAxis(0).toArray()),
        "cum1fnv" => fnv(&m.cumsumAxis(1).toArray()),
        "cmax0fnv" => fnv(&m.cummax(0).toArray()),
        "cmin1fnv" => fnv(&m.cummin(1).toArray()),
        "bcast0fnv" => fnv(&(m - &m.meanAxis(0)).toArray()),
        "bcast1fnv" => fnv(&(m - &m.meanAxis(1)).toArray()),
        "tfnv" => fnv(&m.T().toArray()),
        "slicefnv" => fnv(&sliced().toArray()),
        "divfnv" => fnv(&(m / &(&m.abs() + 1.0)).toArray()),
        "negfnv" => fnv(&(-m).toArray()),
        "argmax" => flat_index(m.argmax(), m.cols()),
        "argmin" => flat_index(m.argmin(), m.cols()),
        // The pinned matmul, with a transposed VIEW as the second operand in both.
        "mmfnv" => fnv(&m.matmulPure(&m.T()).toArray()),
        "tmmfnv" => fnv(&m.T().matmulPure(m).toArray()),
        other => panic!("unknown 2-D case {other} in fixture — port it or regenerate"),
    }
}

/// `(row, col)` flattened as the generator records it.
fn flat_index((r, c): (usize, usize), cols: usize) -> u64 {
    (r * cols + c) as u64
}

/// `12` is an n×1 column over a corpus prefix; `3x5` is a row-major 2-D matrix over the
/// same corpus.
fn parse_shape(tok: &str) -> Shape {
    if let Some(rest) = tok.strip_prefix("adv/") {
        let (name, orient) = rest
            .split_once('/')
            .expect("adv key needs name/orientation");
        return Shape::Adversarial(name.to_string(), orient.to_string());
    }
    match tok.split_once('x') {
        Some((r, c)) => Shape::Matrix(
            r.parse().expect("rows not a number"),
            c.parse().expect("cols not a number"),
        ),
        None => Shape::Column(tok.parse().expect("n not a number")),
    }
}

/// Why a row failed, chosen from its key so the message names the contract that broke
/// rather than making the reader go looking for it.
fn failure_hint(shape: &str, label: &str) -> &'static str {
    if label.starts_with("exp") {
        "Math.exp and f64::exp disagree on this corpus; see the exp note in mat.rs"
    } else if shape.starts_with("adv/") {
        "ordering semantics drifted: Scala compares through Ordering[Double] \
         (java.lang.Double.compare), where NaN outranks every number and -0.0 < 0.0 — \
         see java_double_compare in mat.rs"
    } else if label.starts_with('t') || label.contains("slice") {
        "the view model drifted; see the layout note in mat.rs"
    } else {
        "association order drifted"
    }
}

/// The fixture as `(shape key, case label, expected word)`.
fn fixture_rows(text: &str) -> Vec<(String, String, u64)> {
    text.lines()
        .filter(|l| !l.starts_with('#') && !l.trim().is_empty())
        .map(|l| {
            let mut it = l.split_whitespace();
            let shape = it.next().expect("missing shape").to_string();
            let label = it.next().expect("missing case label").to_string();
            let bits = u64::from_str_radix(it.next().expect("missing hex"), 16)
                .expect("hex not parseable");
            (shape, label, bits)
        })
        .collect()
}

#[test]
fn mat_reductions_match_the_scala_reference_bit_for_bit() {
    let rows = fixture_rows(&fixture());
    assert!(!rows.is_empty(), "fixture carried no rows");

    // Draw once to the largest prefix any row needs; every shape takes a prefix, exactly
    // as the generator does.
    let max_n = rows
        .iter()
        .map(|(shape, _, _)| match parse_shape(shape) {
            Shape::Column(n) => n,
            Shape::Matrix(r, c) => r * c,
            // Adversarial rows carry their own literal values, not a corpus prefix.
            Shape::Adversarial(..) => 0,
        })
        .max()
        .expect("rows non-empty");
    let all = corpus(max_n);
    let all_exp = corpus_exp(max_n);

    let mut checked = 0usize;
    let mut two_d = 0usize;
    let mut adv = 0usize;
    let mut masks = 0usize;
    let mut matmuls = 0usize;
    for (shape, label, want) in &rows {
        if label.starts_with("mask.") {
            masks += 1;
        }
        if label.ends_with("mmfnv") {
            matmuls += 1;
        }
        let got = match parse_shape(shape) {
            Shape::Column(n) => {
                let m = MatD::apply(&all[..n]);
                let me = MatD::apply(&all_exp[..n]);
                case_word(&m, &me, label)
            }
            Shape::Matrix(r, c) => {
                two_d += 1;
                case_word_2d(&MatD::create(all[..r * c].to_vec(), r, c), label)
            }
            Shape::Adversarial(name, orient) => {
                if !label.starts_with("mask.") {
                    adv += 1;
                }
                case_word_adv(&adv_matrix(&orient, adversarial_array(&name)), label)
            }
        };
        assert_eq!(
            got,
            *want,
            "shape={shape} case={label}: got {got:016x}, want {want:016x} — {}",
            failure_hint(shape, label)
        );
        checked += 1;
    }

    // Guard against a fixture that silently shrinks to nothing meaningful. Each count is
    // asserted separately because the three groups pin different things, and losing any
    // one of them would leave the others looking healthy.
    assert!(
        checked >= 1550,
        "only {checked} rows checked; expected 13 sizes x 10 + 11 shapes x 27 + 10 adversarial x 60"
    );
    assert!(
        masks >= 800,
        "only {masks} mask rows; IEEE comparison semantics would go unchecked — a port \
         using its ordering comparator would pass everything else"
    );
    assert!(
        two_d >= 250,
        "only {two_d} 2-D rows; the view model would go unchecked"
    );
    assert!(
        adv >= 300,
        "only {adv} adversarial rows; NaN and signed-zero ordering would go unchecked — \
         which is exactly how they were wrong before"
    );
    assert!(
        matmuls >= 22,
        "only {matmuls} matmul rows; the pinned matmul path would go unchecked"
    );
}

#[test]
fn the_matmul_rows_would_catch_a_reassociated_kernel() {
    // The pinned path is a sequential k-sum from 0.0 per cell. Any blocked kernel — a
    // BLAS, `matrixmultiply`, or a k-split — reassociates, and on this corpus that moves
    // bits. Demonstrate with the mildest possible reassociation: two half-sums combined.
    let m = MatD::create(corpus(300 * 400), 300, 400);
    let t = m.T();
    let pinned = m.matmulPure(&t);
    let (rows, k, cols) = (m.rows(), m.cols(), t.cols());
    let mut split = Vec::with_capacity(rows * cols);
    for i in 0..rows {
        for j in 0..cols {
            let (mut lo, mut hi) = (0.0f64, 0.0f64);
            for kk in 0..k / 2 {
                lo += m.at(i, kk) * t.at(kk, j);
            }
            for kk in k / 2..k {
                hi += m.at(i, kk) * t.at(kk, j);
            }
            split.push(lo + hi);
        }
    }
    assert_ne!(
        fnv(&pinned.toArray()),
        fnv(&split),
        "a k-split kernel agrees with the pinned path here; the matmul rows pin nothing"
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
        "corpus no longer distinguishes a naive fold from sumD — the parity fixture would pass even for a wrong implementation"
    );
}

#[test]
fn the_mask_rows_record_ieee_not_the_ordering_beside_them() {
    // The adversarial corpus carries both rules at once: the ordering rows use
    // `java_double_compare`, the `mask.` rows use IEEE. If the two agreed here the mask
    // rows would pin nothing, so assert they still disagree — on NaN, which the total
    // order ranks above every number, and on -0.0, which it ranks below 0.0.
    use std::cmp::Ordering;

    use uni::udata::mat::java_double_compare;

    let m = adv_matrix("col", adversarial_array("nanmid"));
    assert_eq!(
        java_double_compare(f64::NAN, 0.0),
        Ordering::Greater,
        "the total order must still rank NaN above 0.0"
    );
    assert_eq!(
        m.gt(0.0).toArray(),
        vec![true, false, false, true],
        "gt must be false at NaN; a port using its ordering comparator would say true"
    );

    let z = adv_matrix("col", adversarial_array("zeros"));
    assert_eq!(
        java_double_compare(-0.0, 0.0),
        Ordering::Less,
        "the total order must still rank -0.0 below 0.0"
    );
    assert_eq!(
        z.lt(0.0).toArray(),
        vec![false, false],
        "-0.0 < 0.0 must be false for a mask"
    );
}

#[test]
fn the_adversarial_rows_would_catch_the_primitive_comparisons() {
    // These rows exist because `<`/`>` passed all 427 of the others. Assert they really
    // do separate the two orderings, so they cannot quietly become decoration.
    let zeros = adversarial_array("zeros");
    let naive_min = zeros
        .iter()
        .fold(f64::INFINITY, |a, &b| if b < a { b } else { a });
    assert_ne!(
        canon(adv_matrix("col", zeros.clone()).min()),
        canon(naive_min),
        "min over [0.0, -0.0] no longer distinguishes Ordering[Double] from `<`"
    );

    let nan = adversarial_array("nanmid");
    let naive_max = nan
        .iter()
        .fold(f64::NEG_INFINITY, |a, &b| if b > a { b } else { a });
    assert_ne!(
        canon(adv_matrix("col", nan.clone()).max()),
        canon(naive_max),
        "max over a lane containing NaN no longer distinguishes Ordering[Double] from `>`"
    );

    // And that negnan separates Double.compare from f64::total_cmp, which is the trap a
    // port falls into after fixing the first two.
    let neg = adversarial_array("negnan");
    let total_cmp_min = neg
        .iter()
        .copied()
        .reduce(|a, b| {
            if b.total_cmp(&a) == std::cmp::Ordering::Less {
                b
            } else {
                a
            }
        })
        .expect("non-empty");
    assert_ne!(
        canon(adv_matrix("col", neg.clone()).min()),
        canon(total_cmp_min),
        "negnan no longer separates java.lang.Double.compare from f64::total_cmp"
    );
}

#[test]
fn the_corpus_is_sensitive_to_the_view_model() {
    // The 2-D half of the fixture is only evidence if a view really does sum differently
    // from a contiguous matrix on this corpus. Both of these would pass vacuously for a
    // port that materialised every view, so assert they do not.
    let m = MatD::create(corpus(120_000), 300, 400);
    assert_ne!(
        m.sum().to_bits(),
        m.T().sum().to_bits(),
        "a transposed view sums identically here; the tsum rows pin nothing"
    );
    // The reverse of what this asserted before 0.16.1, deliberately. `m.stdAxis(0)` and
    // `m.T().stdAxis(1)` reduce the SAME lanes -- row k of the transpose is column k of
    // the original -- so once `std(axis)` takes its per-lane mean the one way, the two
    // must agree bit for bit. They did not before: the strided path materialised each
    // lane and routed through `sum_d`, so identical data gave different answers depending
    // on layout. This guards the unification rather than the wart.
    assert_eq!(
        fnv(&m.stdAxis(0).toArray()),
        fnv(&m.T().stdAxis(1).toArray()),
        "std(axis) disagrees between a matrix and its transpose; the per-lane mean must          be the one meanAxis returns, whatever the layout"
    );
}

//! PCG64 (XSL RR 128) behind NumPy's SeedSequence — a bit-exact port of
//! `src/main/scala/uni/data/NumPyRNG.scala`, which itself reproduces
//! `np.random.default_rng(seed)`.
//!
//! For the same seed this yields the same stream as the Scala implementation by
//! construction, not by tolerance: the seeding, the state advance and the output
//! function are integer arithmetic, and `next_f64` closes with a division by
//! 2^53, which is exact.
//!
//! `randn` is the one method that cannot make that promise, because its wedge and
//! tail branches call `exp` and `ln_1p` and neither the JVM's `Math.*` nor Rust's
//! `f64::*` promises a bit-identical result — each is accurate to within an ulp,
//! which is a weaker claim. Measured rather than assumed, over six seeds and a
//! million draws each:
//!
//! - This port agrees with NumPy 2.4.6 on every one of those draws.
//! - It differs from the JVM on about 2.5 draws per million, always a tail draw
//!   and always by exactly one ulp — `Math.log1p` rounding, not an algorithmic
//!   difference. The JVM is the outlier of the three.
//! - The wedge's `exp` comparison never once disagreed. That is the failure that
//!   would actually matter: it decides whether to consume another draw, so a
//!   single disagreement desynchronizes the streams permanently instead of
//!   perturbing one value.
//!
//! `tests/numpy_rng_parity.rs` pins all of this.
//!
//! The 128-bit state is a plain `u128`. The Scala version splits it across two
//! `Long`s and hand-rolls the wide multiply, because the JVM has no 128-bit
//! integer; there is nothing to gain by imitating that here.

mod ziggurat;

/// PCG64 multiplier: 0x2360ED051FC65DA4_4385DF649FCCF645.
const MULT: u128 = 0x2360_ED05_1FC6_5DA4_4385_DF64_9FCC_F645;

/// NumPy's default PCG64 generator, seeded through SeedSequence.
///
/// Not thread-safe and deliberately not `Sync`-friendly by design: bit-exact
/// reproducibility requires that one thread seed it and take every draw, the
/// same contract the Scala side documents on `Mat.globalRNG`.
#[derive(Debug, Clone)]
pub struct NumPyRng {
    state: u128,
    inc: u128,
    /// `next_bounded_u32` splits one 64-bit draw into two 32-bit halves, low
    /// half first, and carries the unused half here. So a bounded-int sequence
    /// only reproduces from a freshly seeded generator, and interleaving
    /// `next_bounded_u32` with the other methods consumes draws in an order
    /// that depends on this flag — again matching Scala.
    use_upper: bool,
    cached_raw: u64,
}

impl NumPyRng {
    /// Seeds exactly as `np.random.default_rng(seed)` does.
    ///
    /// Scala takes a `Long` and rejects negatives, so every seed below 2^63 has
    /// a one-to-one Scala counterpart; the range above that is reachable only
    /// from Rust and has no Scala equivalent to be compared against.
    #[must_use]
    pub fn new(seed: u64) -> Self {
        let (state, inc) = initial_state(seed);
        Self {
            state,
            inc,
            use_upper: false,
            cached_raw: 0,
        }
    }

    /// LCG advance of the 128-bit state.
    fn step(&mut self) {
        self.state = self.state.wrapping_mul(MULT).wrapping_add(self.inc);
    }

    /// XSL RR output: xor-fold the state to 64 bits, then rotate right by the
    /// state's top 6 bits.
    fn output(&self) -> u64 {
        // Both truncations are the algorithm: the fold to 64 bits, and a
        // rotation count that `rotate_right` reduces modulo 64 anyway.
        let xored = ((self.state >> 64) as u64) ^ (self.state as u64);
        xored.rotate_right((self.state >> 122) as u32)
    }

    /// Next raw 64-bit draw — the unit every other method is built from.
    pub fn next_u64(&mut self) -> u64 {
        self.step();
        self.output()
    }

    /// Low 32 bits of the next draw, reinterpreted as signed.
    ///
    /// Matches Scala's `nextInt()`, which is `nextLong().toInt`. Note the Scala
    /// comment there claims the *upper* 32 bits; the code takes the lower, and
    /// the code is what the fixtures pin.
    pub fn next_i32(&mut self) -> i32 {
        self.next_u64() as i32
    }

    /// Uniform in [0, 1) from the top 53 bits of a draw.
    ///
    /// Exact in both languages: a 53-bit integer converts to `f64` without
    /// rounding, and 2^53 is a power of two, so the division is too.
    pub fn next_f64(&mut self) -> f64 {
        (self.next_u64() >> 11) as f64 / 9_007_199_254_740_992.0
    }

    /// Uniform in [`low`, `high`).
    pub fn uniform(&mut self, low: f64, high: f64) -> f64 {
        low + self.next_f64() * (high - low)
    }

    /// Uniform integer in [0, `bound`) via Lemire's 32-bit multiply-shift.
    ///
    /// This is the biased fast form, without Lemire's rejection step — kept
    /// as-is because parity with Scala is the point.
    ///
    /// # Panics
    /// If `bound` is zero, mirroring the Scala `require(bound > 0)`.
    pub fn next_bounded_u32(&mut self, bound: u32) -> u32 {
        assert!(bound > 0, "bound must be positive");

        // One 64-bit draw serves two calls: low half, then high half.
        let raw = if self.use_upper {
            self.cached_raw
        } else {
            let fresh = self.next_u64();
            self.cached_raw = fresh;
            fresh
        };
        let bits32 = if self.use_upper {
            (raw >> 32) & 0xFFFF_FFFF
        } else {
            raw & 0xFFFF_FFFF
        };
        self.use_upper = !self.use_upper;

        // Both factors are under 2^32, so the product cannot overflow u64, and
        // its high half fits u32 by construction.
        ((bits32 * u64::from(bound)) >> 32) as u32
    }

    /// Standard normal via the 256-layer Ziggurat, matching NumPy's
    /// `random_standard_normal` and the Scala `randn`.
    ///
    /// One draw supplies the layer index (bits 0-7), the sign (bit 8) and a
    /// 52-bit x coordinate (bits 9-60). Most draws land in a layer's
    /// rectangular part and return immediately, having consumed exactly one
    /// `next_u64`; the wedge and tail paths consume more, which is why any
    /// disagreement about whether to accept desynchronizes the two streams
    /// rather than merely perturbing one value.
    pub fn randn(&mut self) -> f64 {
        loop {
            let draw = self.next_u64();
            let idx = (draw & 0xFF) as usize; // masked to 8 bits: a valid table index
            let rest = draw >> 8;
            let sign = if rest & 1 != 0 { -1.0 } else { 1.0 };
            let rabs = (rest >> 1) & 0x000F_FFFF_FFFF_FFFF;

            // rabs is 52 bits, so the conversion to f64 is exact.
            let x = rabs as f64 * ziggurat::WI[idx];

            if rabs < ziggurat::KI[idx] {
                return sign * x; // rectangular part of the layer — accept
            }
            if idx == 0 {
                return self.sample_tail(rabs);
            }
            if self.wedge_accepts(idx, x) {
                return sign * x;
            }
        }
    }

    /// Marsaglia's exponential-rejection sampler for the tail beyond
    /// [`ziggurat::R`].
    ///
    /// Takes its sign from bit 8 of `rabs` rather than reusing the sign already
    /// extracted by the caller — an oddity of NumPy's implementation, preserved
    /// because dropping it would change the stream.
    ///
    /// `ln_1p` feeds the returned value directly, so this is the one path where a
    /// sub-ulp libm difference between the JVM and Rust shows up as a one-ulp
    /// difference in output rather than as a control-flow divergence. Reached on
    /// about 1 draw in 3900 — every |z| > R draw comes from here, and every
    /// measured Scala/Rust difference is one of them.
    ///
    /// `ziggurat::R` is read here and nowhere else, which is what made it
    /// possible for a wrong value to sit in the Scala source undetected: it
    /// displaces exactly these draws and leaves every other one correct.
    fn sample_tail(&mut self, rabs: u64) -> f64 {
        let mut xx = -ziggurat::INV_R * (-self.next_f64()).ln_1p();
        let mut yy = -(-self.next_f64()).ln_1p();
        while yy + yy <= xx * xx {
            xx = -ziggurat::INV_R * (-self.next_f64()).ln_1p();
            yy = -(-self.next_f64()).ln_1p();
        }
        let sign = if (rabs >> 8) & 1 != 0 { -1.0 } else { 1.0 };
        sign * (ziggurat::R + xx)
    }

    /// Accept/reject test for the curved wedge of layer `idx`.
    ///
    /// The comparison is the only place in the port where a sub-ulp difference in
    /// `exp` could change *control flow*, and so permanently desynchronize the two
    /// streams rather than perturb a single value. It needs the two sides to fall
    /// within an ulp of each other, which over six million draws never happened.
    fn wedge_accepts(&mut self, idx: usize, x: f64) -> bool {
        let (upper, lower) = (ziggurat::FI[idx - 1], ziggurat::FI[idx]);
        (upper - lower) * self.next_f64() + lower < (-0.5 * x * x).exp()
    }
}

// ── NumPy SeedSequence ──────────────────────────────────────────────────────
//
// Port of the entropy mixer in NumPy's `_seed_sequence.pyx`, narrowed to the
// only case this generator needs: one non-negative integer seed, the default
// four-word (128-bit) pool, and no spawn key. The Scala `SeedSequenceModule`
// carries the general form (strings, sequences, spawned child sequences); none
// of it has a caller on this side, so none of it is ported.

const INIT_A: u32 = 0x43b0_d7e5;
const MULT_A: u32 = 0x931e_8875;
const INIT_B: u32 = 0x8b51_f9dd;
const MULT_B: u32 = 0x58f3_8ded;
const MIX_MULT_L: u32 = 0xca01_f9dd;
const MIX_MULT_R: u32 = 0x4973_f715;
const XSHIFT: u32 = 16;
const POOL_SIZE: usize = 4;

/// `hashmix(value, hash_const)`: mixes one word and advances the chain constant.
fn hashmix(value: u32, hash_const: &mut u32) -> u32 {
    let mixed = value ^ *hash_const;
    *hash_const = hash_const.wrapping_mul(MULT_A);
    let mixed = mixed.wrapping_mul(*hash_const);
    mixed ^ (mixed >> XSHIFT)
}

/// `mix(x, y)`: combines two pool words.
fn mix(x: u32, y: u32) -> u32 {
    let result = MIX_MULT_L
        .wrapping_mul(x)
        .wrapping_sub(MIX_MULT_R.wrapping_mul(y));
    result ^ (result >> XSHIFT)
}

/// The seed as 32-bit little-endian words.
///
/// NumPy emits at least one word for any integer, so a zero seed is `[0]`
/// rather than empty — the pool would mix differently otherwise.
fn entropy_words(seed: u64) -> Vec<u32> {
    if seed == 0 {
        return vec![0];
    }
    let mut remaining = seed;
    let mut words = Vec::with_capacity(2);
    while remaining != 0 {
        words.push(remaining as u32);
        remaining >>= 32;
    }
    words
}

/// `mix_entropy`: fold the seed words into the entropy pool.
fn mix_entropy(entropy: &[u32]) -> [u32; POOL_SIZE] {
    let mut hash_const = INIT_A;
    let mut pool = [0_u32; POOL_SIZE];

    for (i, slot) in pool.iter_mut().enumerate() {
        *slot = hashmix(entropy.get(i).copied().unwrap_or(0), &mut hash_const);
    }

    // Mix every word into every other, so late bits can affect earlier slots.
    for i_src in 0..POOL_SIZE {
        for i_dst in 0..POOL_SIZE {
            if i_src != i_dst {
                let hashed = hashmix(pool[i_src], &mut hash_const);
                pool[i_dst] = mix(pool[i_dst], hashed);
            }
        }
    }

    // NumPy has a third stage here that folds in entropy beyond the pool size.
    // A u64 seed is at most two words and the pool holds four, so it could never
    // run; it is left out rather than carried as a loop that provably never
    // iterates, which would imply this accepts wider seeds than it does. Widening
    // the input — a u128 seed, a spawn key — means porting that stage from the
    // Scala `SeedSequenceModule.mixEntropy`, which has it.
    debug_assert!(
        entropy.len() <= POOL_SIZE,
        "entropy wider than the pool needs NumPy's third mixing stage"
    );

    pool
}

/// `generate_state(n_words, dtype=uint32)`: a second hash chain over the pool,
/// cycled if more words are asked for than the pool holds. The chain restarts
/// from `INIT_B` on every call, so asking for 8 words once is not the same as
/// asking for 4 words twice.
fn generate_state_u32(pool: &[u32; POOL_SIZE], n_words: usize) -> Vec<u32> {
    let mut hash_const = INIT_B;
    (0..n_words)
        .map(|i| {
            let data = pool[i % POOL_SIZE] ^ hash_const;
            hash_const = hash_const.wrapping_mul(MULT_B);
            let data = data.wrapping_mul(hash_const);
            data ^ (data >> XSHIFT)
        })
        .collect()
}

/// The `(state, inc)` pair that `np.random.default_rng(seed)` starts from.
fn initial_state(seed: u64) -> (u128, u128) {
    let pool = mix_entropy(&entropy_words(seed));

    // NumPy requests `generate_state(4, dtype=uint64)`, which is eight uint32
    // words packed into pairs, low word first.
    let words32 = generate_state_u32(&pool, 8);
    let word64 = |i: usize| (u64::from(words32[2 * i + 1]) << 32) | u64::from(words32[2 * i]);

    // pcg64_set_seed then packs each pair of uint64 into a 128-bit argument
    // high word first — the opposite order from the packing just above.
    let init_state = (u128::from(word64(0)) << 64) | u128::from(word64(1));
    let init_seq = (u128::from(word64(2)) << 64) | u128::from(word64(3));

    // pcg64_srandom_r: increment must be odd; then step, add the seed, step.
    let inc = (init_seq << 1) | 1;
    let state = inc.wrapping_add(init_state); // step(0, inc) == inc
    let state = state.wrapping_mul(MULT).wrapping_add(inc);
    (state, inc)
}

#[cfg(test)]
mod tests {
    use super::initial_state;

    /// Seed expansion checked against NumPy itself, not against Scala: these are
    /// the values `np.random.default_rng(seed).bit_generator.state` reports, and
    /// they are the same constants baked into
    /// `src/test/scala/uni/data/NumPyRNGTest.scala`. Agreeing here means the two
    /// ports agree with NumPy rather than merely with each other.
    #[test]
    fn seed_expansion_matches_numpy() {
        let cases: &[(u64, u128, u128)] = &[
            (
                0,
                35399562948360463058890781895381311971,
                87136372517582989555478159403783844777,
            ),
            (
                1,
                207833532711051698738587646355624148094,
                194290289479364712180083596243593368443,
            ),
            (
                42,
                274674114334540486603088602300644985544,
                332724090758049132448979897138935081983,
            ),
            (
                2046,
                198982872981414863821223506010253655692,
                257458945125046561499864971702341220287,
            ),
            (
                1844,
                156476218878725487961904583726022207393,
                93571803503315984792249732680669757431,
            ),
            (
                12345,
                33261208707367790463622745601869196757,
                268209174141567072605526753992732310247,
            ),
            (
                i64::MAX as u64,
                276272391916443190492159920656047484577,
                151628497062642123835381847185082110735,
            ),
        ];
        for &(seed, want_state, want_inc) in cases {
            assert_eq!(initial_state(seed), (want_state, want_inc), "seed {seed}");
        }
    }
}

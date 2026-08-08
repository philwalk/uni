//! Hashes and checksums — a port of `uni.io.Cksum`, `md5`, `sha256` and `Hash64`.
//!
//! # Hand-rolled, and why that is fine here
//!
//! Scala gets MD5 and SHA-256 from the JDK's `MessageDigest`; Rust's standard library
//! has no equivalent, so the choice is a crate or an implementation. These are
//! implemented, keeping the port dependency-free like the rest of it. That is a safe
//! trade *because these two are pinned by published test vectors* — RFC 1321 and FIPS
//! 180-4 — so correctness is checkable against the specification rather than against
//! my own reasoning. `cksum` is likewise checkable against the POSIX utility.
//!
//! **Not for security use.** These are file-identity and change-detection hashes, as
//! in `uni`. Nothing here is constant-time, and MD5 is not collision-resistant. For
//! anything adversarial, reach for a vetted crate.
//!
//! Each hash is an incremental hasher so a large file streams rather than being held
//! in memory, matching the Scala's buffered reads.

use std::fs;
use std::io;
use std::io::Read;

use crate::upath::ext::UPath;

/// Bytes read at a time when hashing a file, matching `uni`'s buffer.
const READ_BUF: usize = 64 * 1024;

fn to_hex(bytes: &[u8]) -> String {
    // Two nibbles per byte, written directly rather than through `format!` per byte.
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for &b in bytes {
        out.push(char::from(DIGITS[usize::from(b >> 4)]));
        out.push(char::from(DIGITS[usize::from(b & 0x0f)]));
    }
    out
}

// ---------------------------------------------------------------------------
// cksum — the POSIX CRC-32
// ---------------------------------------------------------------------------

/// Result of [`Cksum`]: the checksum and the byte count.
///
/// `uni` returns a bare `(Long, Long)`; naming the fields removes the question of
/// which one came first.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CksumResult {
    /// The CRC-32, as the `cksum` utility prints it.
    pub crc: u32,
    /// Bytes hashed.
    pub len: u64,
}

/// Incremental POSIX `cksum`.
///
/// The CRC-32 of `cksum(1)`: polynomial `0x04C11DB7`, MSB-first, no reflection, with
/// the message length folded in at the end and the result complemented. That last
/// step is what distinguishes it from the far more common reflected CRC-32 used by
/// zip and PNG — the two produce different values for the same input.
#[derive(Debug, Clone)]
pub struct Cksum {
    crc: u32,
    len: u64,
}

impl Default for Cksum {
    fn default() -> Self {
        Self::new()
    }
}

/// `crc_table[i]` is the CRC contribution of byte `i` in the top position.
fn crc_table() -> [u32; 256] {
    let mut table = [0_u32; 256];
    let mut i = 0;
    while i < 256 {
        let mut c = (i as u32) << 24;
        let mut bit = 0;
        while bit < 8 {
            c = if c & 0x8000_0000 == 0 {
                c << 1
            } else {
                (c << 1) ^ 0x04C1_1DB7
            };
            bit += 1;
        }
        table[i] = c;
        i += 1;
    }
    table
}

impl Cksum {
    /// A fresh checksum.
    #[must_use]
    pub fn new() -> Self {
        Self { crc: 0, len: 0 }
    }

    /// Adds bytes.
    pub fn update(&mut self, bytes: &[u8]) {
        let table = crc_table();
        for &b in bytes {
            self.crc = (self.crc << 8) ^ table[usize::from((self.crc >> 24) as u8 ^ b)];
        }
        self.len += bytes.len() as u64;
    }

    /// The checksum over everything added so far.
    #[must_use]
    pub fn finish(&self) -> CksumResult {
        let table = crc_table();
        let mut crc = self.crc;
        let mut len = self.len;
        // The length is folded in byte by byte, which is what makes `cksum` differ
        // from a plain CRC-32 and what lets it distinguish inputs that differ only
        // in trailing length.
        while len > 0 {
            crc = (crc << 8) ^ table[usize::from((crc >> 24) as u8 ^ (len & 0xFF) as u8)];
            len >>= 8;
        }
        CksumResult {
            crc: !crc,
            len: self.len,
        }
    }
}

// ---------------------------------------------------------------------------
// MD5 — RFC 1321
// ---------------------------------------------------------------------------

const MD5_S: [u32; 64] = [
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, //
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, //
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, //
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
];

/// `K[i] = floor(2^32 * abs(sin(i + 1)))`, per RFC 1321.
fn md5_k() -> [u32; 64] {
    let mut k = [0_u32; 64];
    for (i, slot) in k.iter_mut().enumerate() {
        // Computed rather than tabulated: a 64-entry table is 64 chances to fumble a
        // digit, and the definition is one line.
        *slot = ((f64::from(i as u32 + 1).sin().abs()) * f64::from(1_u32 << 31) * 2.0) as u32;
    }
    k
}

/// Incremental MD5. **Not collision-resistant**; file identity only.
#[derive(Debug, Clone)]
pub struct Md5 {
    state: [u32; 4],
    buf: [u8; 64],
    buf_len: usize,
    total: u64,
}

impl Default for Md5 {
    fn default() -> Self {
        Self::new()
    }
}

impl Md5 {
    /// A fresh digest.
    #[must_use]
    pub fn new() -> Self {
        Self {
            state: [0x6745_2301, 0xefcd_ab89, 0x98ba_dcfe, 0x1032_5476],
            buf: [0; 64],
            buf_len: 0,
            total: 0,
        }
    }

    fn compress(state: &mut [u32; 4], block: &[u8; 64]) {
        let k = md5_k();
        let mut m = [0_u32; 16];
        for (i, word) in m.iter_mut().enumerate() {
            *word = u32::from_le_bytes([
                block[i * 4],
                block[i * 4 + 1],
                block[i * 4 + 2],
                block[i * 4 + 3],
            ]);
        }
        let [mut a, mut b, mut c, mut d] = *state;
        for i in 0..64 {
            let (f, g) = match i / 16 {
                0 => ((b & c) | (!b & d), i),
                1 => ((d & b) | (!d & c), (5 * i + 1) % 16),
                2 => (b ^ c ^ d, (3 * i + 5) % 16),
                _ => (c ^ (b | !d), (7 * i) % 16),
            };
            let tmp = d;
            d = c;
            c = b;
            let sum = a
                .wrapping_add(f)
                .wrapping_add(k[i])
                .wrapping_add(m[g])
                .rotate_left(MD5_S[i]);
            b = b.wrapping_add(sum);
            a = tmp;
        }
        state[0] = state[0].wrapping_add(a);
        state[1] = state[1].wrapping_add(b);
        state[2] = state[2].wrapping_add(c);
        state[3] = state[3].wrapping_add(d);
    }

    /// Adds bytes.
    pub fn update(&mut self, bytes: &[u8]) {
        self.total = self.total.wrapping_add(bytes.len() as u64);
        let mut rest = bytes;
        // Top up a partial block first, so callers can feed arbitrary chunk sizes.
        if self.buf_len > 0 {
            let take = (64 - self.buf_len).min(rest.len());
            self.buf[self.buf_len..self.buf_len + take].copy_from_slice(&rest[..take]);
            self.buf_len += take;
            rest = &rest[take..];
            if self.buf_len == 64 {
                let block = self.buf;
                Self::compress(&mut self.state, &block);
                self.buf_len = 0;
            } else {
                // `take` consumed all of `rest`, so there is nothing left to do —
                // and falling through would reset `buf_len` to zero.
                return;
            }
        }
        while rest.len() >= 64 {
            let mut block = [0_u8; 64];
            block.copy_from_slice(&rest[..64]);
            Self::compress(&mut self.state, &block);
            rest = &rest[64..];
        }
        self.buf[..rest.len()].copy_from_slice(rest);
        self.buf_len = rest.len();
    }

    /// The digest as lowercase hex.
    #[must_use]
    pub fn hex(&self) -> String {
        let mut s = self.clone();
        let bits = s.total.wrapping_mul(8);
        s.update(&[0x80]);
        while s.buf_len != 56 {
            s.update(&[0]);
        }
        // Length is little-endian in MD5 and big-endian in SHA-256 — an easy thing
        // to get backwards, and the empty-input vector catches it immediately.
        s.update(&bits.to_le_bytes());
        let bytes: Vec<u8> = s.state.iter().flat_map(|w| w.to_le_bytes()).collect();
        to_hex(&bytes)
    }
}

// ---------------------------------------------------------------------------
// SHA-256 — FIPS 180-4
// ---------------------------------------------------------------------------

const SHA256_K: [u32; 64] = [
    0x428a_2f98, 0x7137_4491, 0xb5c0_fbcf, 0xe9b5_dba5, 0x3956_c25b, 0x59f1_11f1, 0x923f_82a4,
    0xab1c_5ed5, 0xd807_aa98, 0x1283_5b01, 0x2431_85be, 0x550c_7dc3, 0x72be_5d74, 0x80de_b1fe,
    0x9bdc_06a7, 0xc19b_f174, 0xe49b_69c1, 0xefbe_4786, 0x0fc1_9dc6, 0x240c_a1cc, 0x2de9_2c6f,
    0x4a74_84aa, 0x5cb0_a9dc, 0x76f9_88da, 0x983e_5152, 0xa831_c66d, 0xb003_27c8, 0xbf59_7fc7,
    0xc6e0_0bf3, 0xd5a7_9147, 0x06ca_6351, 0x1429_2967, 0x27b7_0a85, 0x2e1b_2138, 0x4d2c_6dfc,
    0x5338_0d13, 0x650a_7354, 0x766a_0abb, 0x81c2_c92e, 0x9272_2c85, 0xa2bf_e8a1, 0xa81a_664b,
    0xc24b_8b70, 0xc76c_51a3, 0xd192_e819, 0xd699_0624, 0xf40e_3585, 0x106a_a070, 0x19a4_c116,
    0x1e37_6c08, 0x2748_774c, 0x34b0_bcb5, 0x391c_0cb3, 0x4ed8_aa4a, 0x5b9c_ca4f, 0x682e_6ff3,
    0x748f_82ee, 0x78a5_636f, 0x84c8_7814, 0x8cc7_0208, 0x90be_fffa, 0xa450_6ceb, 0xbef9_a3f7,
    0xc671_78f2,
];

/// Incremental SHA-256.
#[derive(Debug, Clone)]
pub struct Sha256 {
    state: [u32; 8],
    buf: [u8; 64],
    buf_len: usize,
    total: u64,
}

impl Default for Sha256 {
    fn default() -> Self {
        Self::new()
    }
}

impl Sha256 {
    /// A fresh digest.
    #[must_use]
    pub fn new() -> Self {
        Self {
            state: [
                0x6a09_e667,
                0xbb67_ae85,
                0x3c6e_f372,
                0xa54f_f53a,
                0x510e_527f,
                0x9b05_688c,
                0x1f83_d9ab,
                0x5be0_cd19,
            ],
            buf: [0; 64],
            buf_len: 0,
            total: 0,
        }
    }

    fn compress(state: &mut [u32; 8], block: &[u8; 64]) {
        let mut w = [0_u32; 64];
        for (i, word) in w.iter_mut().take(16).enumerate() {
            *word = u32::from_be_bytes([
                block[i * 4],
                block[i * 4 + 1],
                block[i * 4 + 2],
                block[i * 4 + 3],
            ]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut h] = *state;
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ (!e & g);
            let t1 = h
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(SHA256_K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);
            h = g;
            g = f;
            f = e;
            e = d.wrapping_add(t1);
            d = c;
            c = b;
            b = a;
            a = t1.wrapping_add(t2);
        }
        for (slot, v) in state.iter_mut().zip([a, b, c, d, e, f, g, h]) {
            *slot = slot.wrapping_add(v);
        }
    }

    /// Adds bytes.
    pub fn update(&mut self, bytes: &[u8]) {
        self.total = self.total.wrapping_add(bytes.len() as u64);
        let mut rest = bytes;
        if self.buf_len > 0 {
            let take = (64 - self.buf_len).min(rest.len());
            self.buf[self.buf_len..self.buf_len + take].copy_from_slice(&rest[..take]);
            self.buf_len += take;
            rest = &rest[take..];
            if self.buf_len == 64 {
                let block = self.buf;
                Self::compress(&mut self.state, &block);
                self.buf_len = 0;
            } else {
                // `take` consumed all of `rest`, so there is nothing left to do —
                // and falling through would reset `buf_len` to zero.
                return;
            }
        }
        while rest.len() >= 64 {
            let mut block = [0_u8; 64];
            block.copy_from_slice(&rest[..64]);
            Self::compress(&mut self.state, &block);
            rest = &rest[64..];
        }
        self.buf[..rest.len()].copy_from_slice(rest);
        self.buf_len = rest.len();
    }

    /// The digest as lowercase hex.
    #[must_use]
    pub fn hex(&self) -> String {
        let mut s = self.clone();
        let bits = s.total.wrapping_mul(8);
        s.update(&[0x80]);
        while s.buf_len != 56 {
            s.update(&[0]);
        }
        s.update(&bits.to_be_bytes()); // big-endian, unlike MD5
        let bytes: Vec<u8> = s.state.iter().flat_map(|w| w.to_be_bytes()).collect();
        to_hex(&bytes)
    }
}

// ---------------------------------------------------------------------------
// hash64 — uni's own 64-bit file hash
// ---------------------------------------------------------------------------

const PRIME64_1: u64 = 0x9E37_79B1_85EB_CA87;
const PRIME64_2: u64 = 0xC2B2_AE3D_27D4_EB4F;
const PRIME64_3: u64 = 0x1656_67B1_9E37_79F9;
const PRIME64_4: u64 = 0x85EB_CA77_C2B2_AE63;
const PRIME64_5: u64 = 0x27D4_EB2F_1656_67C5;

/// Incremental `uni.io.Hash64`.
///
/// # This is not XXH3
///
/// The Scala calls itself "based on the fast XXH3 algorithm" and its own comment
/// concedes it is "a simplified 4-lane structure". It does not agree with xxHash on
/// any input, so it cannot be checked against xxHash's published vectors — the only
/// reference for it is the Scala, which is what the parity fixture pins.
///
/// # A defect fixed in both languages
///
/// [`Self::process_chunk`] used to mix only offsets 0, 8, 16 and 24 while
/// [`Self::update`] advanced a full 64 bytes, so **bytes 32–63 of every block never
/// affected the result** — two 128-byte inputs differing in 64 of their bytes hashed
/// identically. For a duplicate-file finder that means silently calling distinct
/// files equal, so it was fixed here and in `uni.io.Hash64` together, and the parity
/// fixture was regenerated.
///
/// **Hashes recorded before that change do not match hashes produced after it.**
/// Anything that stored a `hash64` value must recompute it.
#[derive(Debug, Clone)]
pub struct Hash64 {
    seed: u64,
    acc: [u64; 4],
    total: u64,
    buf: [u8; 64],
    buf_len: usize,
}

impl Default for Hash64 {
    fn default() -> Self {
        Self::new(0)
    }
}

fn read_le64(b: &[u8], i: usize) -> u64 {
    let mut out = [0_u8; 8];
    out.copy_from_slice(&b[i..i + 8]);
    u64::from_le_bytes(out)
}

fn mix_lane(acc: u64, lane: u64) -> u64 {
    acc.wrapping_add(lane.wrapping_mul(PRIME64_2))
        .rotate_left(31)
        .wrapping_mul(PRIME64_1)
}

impl Hash64 {
    /// A fresh hasher.
    #[must_use]
    pub fn new(seed: u64) -> Self {
        Self {
            seed,
            acc: [
                seed.wrapping_add(PRIME64_1).wrapping_add(PRIME64_2),
                seed.wrapping_add(PRIME64_2),
                seed,
                seed.wrapping_sub(PRIME64_1),
            ],
            total: 0,
            buf: [0; 64],
            buf_len: 0,
        }
    }

    /// Mixes a whole 64-byte chunk: two 32-byte stripes through the four lanes.
    ///
    /// The second stripe used to be missing — see the type's note.
    fn process_chunk(&mut self, b: &[u8], off: usize) {
        for stripe in [off, off + 32] {
            self.acc[0] = mix_lane(self.acc[0], read_le64(b, stripe));
            self.acc[1] = mix_lane(self.acc[1], read_le64(b, stripe + 8));
            self.acc[2] = mix_lane(self.acc[2], read_le64(b, stripe + 16));
            self.acc[3] = mix_lane(self.acc[3], read_le64(b, stripe + 24));
        }
    }

    /// Adds bytes.
    pub fn update(&mut self, input: &[u8]) {
        let mut off = 0;
        let mut len = input.len();
        self.total = self.total.wrapping_add(len as u64);

        if self.buf_len + len < 64 {
            self.buf[self.buf_len..self.buf_len + len].copy_from_slice(input);
            self.buf_len += len;
            return;
        }
        if self.buf_len > 0 {
            let fill = 64 - self.buf_len;
            let mut block = self.buf;
            block[self.buf_len..].copy_from_slice(&input[..fill]);
            self.process_chunk(&block, 0);
            off += fill;
            len -= fill;
            self.buf_len = 0;
        }
        while len >= 64 {
            let chunk = input;
            self.process_chunk(chunk, off);
            off += 64;
            len -= 64;
        }
        if len > 0 {
            self.buf[..len].copy_from_slice(&input[off..off + len]);
            self.buf_len = len;
        }
    }

    /// The 64-bit digest.
    #[must_use]
    pub fn finish(&self) -> u64 {
        let mut h = if self.total >= 64 {
            self.acc[0]
                .rotate_left(1)
                .wrapping_add(self.acc[1].rotate_left(7))
                .wrapping_add(self.acc[2].rotate_left(12))
                .wrapping_add(self.acc[3].rotate_left(18))
        } else {
            self.seed.wrapping_add(PRIME64_5)
        };
        h = h.wrapping_add(self.total);

        let mut i = 0;
        while i + 8 <= self.buf_len {
            h ^= mix_lane(0, read_le64(&self.buf, i));
            h = h
                .rotate_left(27)
                .wrapping_mul(PRIME64_1)
                .wrapping_add(PRIME64_4);
            i += 8;
        }
        while i < self.buf_len {
            h ^= u64::from(self.buf[i]).wrapping_mul(PRIME64_5);
            h = h.rotate_left(11).wrapping_mul(PRIME64_1);
            i += 1;
        }

        h ^= h >> 33;
        h = h.wrapping_mul(PRIME64_2);
        h ^= h >> 29;
        h = h.wrapping_mul(PRIME64_3);
        h ^= h >> 32;
        h
    }

    /// The digest as 16 lowercase hex digits, as `PathExts.hash64` returns it.
    #[must_use]
    pub fn hex(&self) -> String {
        format!("{:016x}", self.finish())
    }
}

// ---------------------------------------------------------------------------
// UPath methods
// ---------------------------------------------------------------------------

/// Streams a file through `f` in [`READ_BUF`] chunks.
fn stream<F: FnMut(&[u8])>(path: &UPath, mut f: F) -> io::Result<()> {
    let mut file = fs::File::open(path.as_std_path())?;
    let mut buf = vec![0_u8; READ_BUF];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            return Ok(());
        }
        f(&buf[..n]);
    }
}

impl UPath {
    /// POSIX `cksum` of this file.
    ///
    /// # Errors
    /// Any failure opening or reading the file.
    pub fn try_cksum(&self) -> io::Result<CksumResult> {
        let mut h = Cksum::new();
        stream(self, |b| h.update(b))?;
        Ok(h.finish())
    }

    /// POSIX `cksum`; a zero-length result when unreadable.
    #[must_use]
    pub fn cksum(&self) -> CksumResult {
        self.try_cksum().unwrap_or(CksumResult { crc: 0, len: 0 })
    }

    /// MD5 of this file, as lowercase hex.
    ///
    /// # Errors
    /// Any failure opening or reading the file.
    pub fn try_md5(&self) -> io::Result<String> {
        let mut h = Md5::new();
        stream(self, |b| h.update(b))?;
        Ok(h.hex())
    }

    /// MD5 as lowercase hex; empty when unreadable.
    #[must_use]
    pub fn md5(&self) -> String {
        self.try_md5().unwrap_or_default()
    }

    /// SHA-256 of this file, as lowercase hex.
    ///
    /// # Errors
    /// Any failure opening or reading the file.
    pub fn try_sha256(&self) -> io::Result<String> {
        let mut h = Sha256::new();
        stream(self, |b| h.update(b))?;
        Ok(h.hex())
    }

    /// SHA-256 as lowercase hex; empty when unreadable.
    #[must_use]
    pub fn sha256(&self) -> String {
        self.try_sha256().unwrap_or_default()
    }

    /// `uni`'s 64-bit file hash, as 16 hex digits. See [`Hash64`] for its limits.
    ///
    /// # Errors
    /// Any failure opening or reading the file.
    pub fn try_hash64(&self) -> io::Result<String> {
        let mut h = Hash64::new(0);
        stream(self, |b| h.update(b))?;
        Ok(h.hex())
    }

    /// `uni`'s 64-bit file hash; empty when unreadable, as `PathExts.hash64` is.
    #[must_use]
    pub fn hash64(&self) -> String {
        self.try_hash64().unwrap_or_default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn md5_of(s: &str) -> String {
        let mut h = Md5::new();
        h.update(s.as_bytes());
        h.hex()
    }

    fn sha_of(s: &str) -> String {
        let mut h = Sha256::new();
        h.update(s.as_bytes());
        h.hex()
    }

    fn cksum_of(s: &str) -> CksumResult {
        let mut h = Cksum::new();
        h.update(s.as_bytes());
        h.finish()
    }

    #[test]
    fn md5_matches_rfc_1321() {
        // The published test suite from the RFC's appendix.
        assert_eq!(md5_of(""), "d41d8cd98f00b204e9800998ecf8427e");
        assert_eq!(md5_of("a"), "0cc175b9c0f1b6a831c399e269772661");
        assert_eq!(md5_of("abc"), "900150983cd24fb0d6963f7d28e17f72");
        assert_eq!(md5_of("message digest"), "f96b697d7cb7938d525a2f31aaf161d0");
        assert_eq!(
            md5_of("abcdefghijklmnopqrstuvwxyz"),
            "c3fcd3d76192e4007dfb496cca67e13b"
        );
        assert_eq!(
            md5_of("12345678901234567890123456789012345678901234567890123456789012345678901234567890"),
            "57edf4a22be3c955ac49da2e2107b67a"
        );
    }

    #[test]
    fn sha256_matches_fips_180_4() {
        assert_eq!(
            sha_of(""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(
            sha_of("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert_eq!(
            sha_of("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
        );
        // Two blocks plus a padding block, i.e. the length spills past 56 bytes.
        assert_eq!(
            sha_of("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"),
            "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1"
        );
    }

    #[test]
    fn cksum_matches_the_posix_utility() {
        // Verified against coreutils `cksum` on this machine.
        assert_eq!(cksum_of(""), CksumResult { crc: 4_294_967_295, len: 0 });
        assert_eq!(cksum_of("abc"), CksumResult { crc: 1_219_131_554, len: 3 });
    }

    #[test]
    fn hashing_in_pieces_matches_hashing_at_once() {
        // The streaming path feeds arbitrary chunk boundaries, including ones that
        // split a 64-byte block; getting the carry-over buffer wrong shows up here.
        let data: Vec<u8> = (0..500_u32).map(|i| (i % 251) as u8).collect();
        for split in [1_usize, 7, 63, 64, 65, 127, 200] {
            let (mut a, mut b) = (Md5::new(), Md5::new());
            let (mut c, mut d) = (Sha256::new(), Sha256::new());
            let (mut e, mut f) = (Hash64::new(0), Hash64::new(0));
            let (mut g, mut i) = (Cksum::new(), Cksum::new());
            a.update(&data);
            c.update(&data);
            e.update(&data);
            g.update(&data);
            for piece in data.chunks(split) {
                b.update(piece);
                d.update(piece);
                f.update(piece);
                i.update(piece);
            }
            assert_eq!(a.hex(), b.hex(), "md5 at split {split}");
            assert_eq!(c.hex(), d.hex(), "sha256 at split {split}");
            assert_eq!(e.hex(), f.hex(), "hash64 at split {split}");
            assert_eq!(g.finish(), i.finish(), "cksum at split {split}");
        }
    }

    #[test]
    fn hex_is_zero_padded_to_a_fixed_width() {
        assert_eq!(md5_of("").len(), 32);
        assert_eq!(sha_of("").len(), 64);
        assert_eq!(Hash64::new(0).hex().len(), 16);
    }

    /// Regression for the fixed defect: every byte must now reach the hash.
    #[test]
    fn hash64_sees_the_second_half_of_every_block() {
        let a: Vec<u8> = (0..128_u32).map(|i| i as u8).collect();
        let mut b = a.clone();
        for byte in &mut b[32..64] {
            *byte = 0;
        }
        for byte in &mut b[96..128] {
            *byte = 0;
        }
        let hash = |v: &[u8]| {
            let mut h = Hash64::new(0);
            h.update(v);
            h.hex()
        };
        assert_ne!(
            hash(&a),
            hash(&b),
            "bytes 32..63 of a block must affect the hash"
        );
        // And the halves that always worked still do.
        let mut c = a.clone();
        c[8] = 0xFF;
        assert_ne!(hash(&a), hash(&c));

        // Every single byte position matters, not just the two probed above.
        let base: Vec<u8> = (0..128_u32).map(|i| i as u8).collect();
        let baseline = hash(&base);
        for i in 0..128 {
            let mut v = base.clone();
            v[i] ^= 0xFF;
            assert_ne!(hash(&v), baseline, "flipping byte {i} changed nothing");
        }
    }
}

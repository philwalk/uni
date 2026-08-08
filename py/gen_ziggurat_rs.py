#!/usr/bin/env python3
"""Emit rust/src/numpy_rng/ziggurat.rs from the tables in NumPyRNG.scala.

Transcribing 768 constants by hand is a bad idea, so don't: run this from the
repo root and let it write the file.

    python <this script> > rust/src/numpy_rng/ziggurat.rs
"""
import re
import sys

SRC = "src/main/scala/uni/data/NumPyRNG.scala"

text = open(SRC, encoding="utf-8").read()


def grab(decl):
    """Body of `val <decl> = Array[...](  ...  )`, up to the matching paren."""
    i = text.index(decl)
    start = text.index("(", i) + 1
    depth, j = 1, start
    while depth:
        if text[j] == "(":
            depth += 1
        elif text[j] == ")":
            depth -= 1
        j += 1
    return text[start:j - 1]


def scalar(name):
    m = re.search(rf"val {name}\s*=\s*([0-9.eE+-]+)", text)
    if not m:
        sys.exit(f"could not find scalar {name}")
    return m.group(1)


ki = [int(t) for t in re.findall(r"(-?\d+)L", grab("val ki = Array[Long]"))]
wi = [t.strip() for t in grab("val wi = Array[Double]").split(",") if t.strip()]
fi = [t.strip() for t in grab("val fi = Array[Double]").split(",") if t.strip()]

for name, arr in (("ki", ki), ("wi", wi), ("fi", fi)):
    if len(arr) != 256:
        sys.exit(f"{name}: expected 256 entries, parsed {len(arr)}")

# The Scala table is Array[Long] and the port uses u64; a negative entry would
# make the `rabs < KI[idx]` comparison mean something different in each language.
if any(v < 0 for v in ki):
    sys.exit("ki has a negative entry — the u64 port would compare differently")

# Rust and the JVM both parse decimal f64 literals with round-to-nearest, so
# identical literal text implies identical bits. That only holds if the text is
# the shortest round-tripping form, which is what Scala emitted and what Python
# reproduces here.
for name, arr in (("wi", wi), ("fi", fi)):
    for t in arr:
        if repr(float(t)) != t:
            sys.exit(f"{name}: literal {t} is not shortest-round-trip "
                     f"(python renders {repr(float(t))}) — check the parse")

zignor_r = scalar("ZIGNOR_R")
inv_r = scalar("ziggurat_nor_inv_r")

# The tail sampler returns `R + xx` with `xx` scaled by `inv_r`, so the two must
# be exact reciprocals. They are independent literals in the Scala source, and
# `R` appears in no comparison — a wrong value shifts every tail draw by a
# constant and perturbs nothing else, which is invisible to any distributional
# check. That is not hypothetical: this file's `R` was Marsaglia & Tsang's
# 3.442619855899 rather than NumPy's, and every |z| > R draw was out by 0.2115.
if float(zignor_r) != 1.0 / float(inv_r):
    sys.exit(f"ZIGNOR_R ({zignor_r}) is not the reciprocal of "
             f"ziggurat_nor_inv_r ({inv_r}); NumPy's pair is "
             f"3.6541528853610088 / 0.27366123732975828")

out = [
    "//! Ziggurat tables for `randn`, generated from the `initZiggurat` arrays in",
    "//! `src/main/scala/uni/data/NumPyRNG.scala` — do not hand-edit. The literals",
    "//! are byte-for-byte the Scala ones, which is what makes the two `randn`",
    "//! implementations agree: Rust and the JVM both parse shortest-round-trip",
    "//! decimal f64 text to the same bits.",
    "//!",
    "//! Regenerate with the script recorded in the parity fixture header:",
    "//!   python py/gen_ziggurat_rs.py > rust/src/numpy_rng/ziggurat.rs",
    "",
    "/// Ziggurat right edge: the x below which the tail sampler takes over.",
    "///",
    "/// NumPy's distributions.c writes this 3.6541528853610088; clippy wants the",
    "/// shortest round-tripping form. Both name the same f64, and the generator",
    "/// checks it against 1/INV_R.",
    f"pub(super) const R: f64 = {float(zignor_r)!r};",
    "",
    "/// 1/R, precomputed — the scale of the tail's exponential proposals.",
    "/// NumPy writes this 0.27366123732975828 — again the same f64.",
    f"pub(super) const INV_R: f64 = {float(inv_r)!r};",
    "",
]


def emit(name, ty, vals, doc):
    out.append(f"/// {doc}")
    # Four per line reads as a table; rustfmt would give each of the 256 entries
    # its own line. Skipping it also keeps a regenerated file format-clean, so
    # `make lint` does not fail purely because this script ran.
    out.append("#[rustfmt::skip]")
    out.append(f"pub(super) static {name}: [{ty}; 256] = [")
    for i in range(0, 256, 4):
        out.append("    " + ", ".join(str(v) for v in vals[i:i + 4]) + ",")
    out.append("];")
    out.append("")


emit("KI", "u64", ki, "Per-layer acceptance threshold on the 52-bit x coordinate.")
emit("WI", "f64", wi, "Per-layer width: scales the 52-bit draw to an x value.")
emit("FI", "f64", fi, "Density at each layer edge, for the wedge accept/reject test.")

print("\n".join(out).rstrip() + "\n")

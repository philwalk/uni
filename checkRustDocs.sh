#!/usr/bin/env bash
# Compile-checks every complete Rust example embedded in rust/docs/*.md — the Rust twin of
# checkDocScripts.sh.
#
# A "complete example" is a fenced ```rust block that contains `fn main`. Each is written
# to rust/doc-examples/examples/<doc>_<n>.rs and the lot is built by the uni-doc-examples
# crate (a path dependency on the library) with warnings denied, so a documented call that
# no longer exists, or an unused import, fails here rather than in a reader's editor.
# Fragments (blocks without `fn main`) are not checked.
#
# Runs in CI (rust.yml) and as release gate 6d.
#
# Usage:  ./checkRustDocs.sh [file.md ...]     (default: rust/docs/*.md rust/README.md)
set -u
cd "$(dirname "$0")"
files=("$@")
[ ${#files[@]} -eq 0 ] && files=(rust/docs/*.md rust/README.md)
out=rust/doc-examples/examples
rm -rf "$out"; mkdir -p "$out"

for md in "${files[@]}"; do
  base=$(basename "$md" .md)
  awk -v base="$base" -v out="$out" '
    /^```/ { if (inblock) { inblock=0; if (buf ~ /fn main/) { n++; f=sprintf("%s/%s_%02d.rs", out, base, n); printf "%s", buf > f; close(f) } buf=""; next }
             if ($0 ~ /^```rust/) { inblock=1; buf="" } ; next }
    inblock { buf = buf $0 "\n" }
  ' "$md"
done

count=$(ls "$out"/*.rs 2>/dev/null | wc -l)
if [ "$count" -eq 0 ]; then echo "no Rust doc examples found"; exit 0; fi
if RUSTFLAGS="-D warnings" cargo build --quiet --examples --manifest-path rust/doc-examples/Cargo.toml 2>rust/doc-examples/build.log; then
  echo "checked $count Rust doc examples, 0 failed"
else
  grep -E "^(error|warning)" -A 6 rust/doc-examples/build.log | head -60
  echo "checked $count Rust doc examples, build FAILED"
  exit 1
fi

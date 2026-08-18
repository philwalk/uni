#!/usr/bin/env bash
# Compile-checks every complete Rust example embedded in rust/docs/*.md (and the Rust
# blocks the Scala guides under docs/ carry) — the Rust twin of
# checkDocScripts.sh.
#
# A "complete example" is a fenced ```rust block that contains `fn main`. Each is written
# to rust/doc-examples/examples/<doc>_<n>.rs and the lot is built by the uni-doc-examples
# crate (a path dependency on the library) with warnings denied, so a documented call that
# no longer exists, or an unused import, fails here rather than in a reader's editor — and
# then every example is RUN: they are self-contained (temp dirs, no external files), so a
# panic or a non-zero exit fails the check too. Every ```rust block in the Rust docs is a
# complete program by policy; a fragment would silently escape both checks, so do not add
# one.
#
# Runs in CI (rust.yml) and as release gate 6d.
#
# Usage:  ./checkRustDocs.sh [file.md ...]     (default: rust/docs/*.md rust/README.md docs/*.md)
set -u
cd "$(dirname "$0")"
files=("$@")
[ ${#files[@]} -eq 0 ] && files=(rust/docs/*.md rust/README.md docs/*.md)   # docs/ holds the Rust columns of the Scala guides
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
if ! RUSTFLAGS="-D warnings" cargo build --quiet --examples --manifest-path rust/doc-examples/Cargo.toml 2>rust/doc-examples/build.log; then
  grep -E "^(error|warning)" -A 6 rust/doc-examples/build.log | head -60
  echo "checked $count Rust doc examples, build FAILED"
  exit 1
fi
failed=0
for src in "$out"/*.rs; do
  name=$(basename "$src" .rs)
  if ! (cd rust/doc-examples && cargo run --quiet --example "$name" >"examples/$name.out" 2>&1); then
    failed=$((failed+1))
    echo "FAIL (run) $name"
    tail -8 "rust/doc-examples/examples/$name.out" | sed 's/^/    /'
  fi
done
echo "checked $count Rust doc examples: all compiled, $failed failed to run"
[ "$failed" -eq 0 ]

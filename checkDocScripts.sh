#!/usr/bin/env bash
# Compile-checks every complete scala-cli script embedded in the docs.
#
# A "complete script" is a fenced code block whose first line is the scala-cli shebang
# (`#!/usr/bin/env -S scala-cli shebang …`). Each is extracted to target/doc-scripts/ and
# compiled with `-Wunused:imports -Werror`, so a documented example that no longer
# compiles, or carries an import it does not need, fails here instead of in a reader's
# terminal. Fragments (blocks without the shebang) are not checked; jsrc/docCheck.sc
# mirrors those by hand.
#
# Runs in CI (scala.yml `doc-scripts`, after publishLocal) and as release gate 6c.
#
# Usage:  ./checkDocScripts.sh [file.md ...]     (default: README.md docs/*.md)
# Needs the version named in the blocks' `//> using dep` to be resolvable — publishLocal
# first when checking an unreleased version.
set -u
cd "$(dirname "$0")"
files=("$@")
[ ${#files[@]} -eq 0 ] && files=(README.md docs/*.md)
out=target/doc-scripts
rm -rf "$out"; mkdir -p "$out"

# Split every shebang-led fenced block into its own file: <doc>_<n>.sc
for md in "${files[@]}"; do
  base=$(basename "$md" .md)
  awk -v base="$base" -v out="$out" '
    /^```/ { if (inblock) { inblock=0; if (f) close(f); f=""; next }
             inblock=1; first=1; next }
    inblock && first { first=0
                       if ($0 ~ /^#!\/usr\/bin\/env -S scala-cli/) { n++; f=sprintf("%s/%s_%02d.sc", out, base, n) }
                       else f="" }
    inblock && f { print > f }
  ' "$md"
done

total=0; failed=0
for sc in "$out"/*.sc; do
  [ -e "$sc" ] || continue
  total=$((total+1))
  if ! scala-cli compile -Wunused:imports -Wunused:locals -deprecation -Werror "$sc" >"$sc.log" 2>&1; then
    failed=$((failed+1))
    echo "FAIL $sc"
    grep -E "error|warn" "$sc.log" | grep -v "^\[warn\] \[warn\]" | head -8 | sed 's/^/    /'
  fi
done
echo "checked $total doc scripts, $failed failed"
[ "$failed" -eq 0 ]

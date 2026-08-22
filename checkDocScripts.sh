#!/usr/bin/env bash
# Compile-checks AND runs every scala-cli script embedded in the docs.
#
# Every ```scala block in README.md and docs/*.md is a complete script whose first line is
# the scala-cli shebang (`#!/usr/bin/env -S scala-cli shebang …`); the only exceptions are
# the one-line dependency directives. Each is extracted to target/doc-scripts/ and compiled
# with `-Wunused:imports -Werror`, so a documented example that no longer compiles, or
# carries an import it does not need, fails here instead of in a reader's terminal — and
# then RUN, unless it carries `// doc: compile-only` (needs a display, network, python, or
# would mutate a repository); `// doc: args a b` supplies arguments to CLI-style examples.
# Scripts run with cwd = the extraction dir (inside this git checkout, so `git` examples
# work), and a script that writes a relative file writes there. Signature listings and
# deliberately non-compiling snippets are ```text blocks. jsrc/docCheck.sc, the assertion
# mirror of the ReferenceGuide.md/QuickStartGuide.md examples, is compiled AND run as the
# last step, so this script is the one doc harness.
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

# Progress, because this gate is otherwise silent for minutes at a time: 135 scripts at ~1.7s
# each is four or five minutes of nothing, which is indistinguishable from a wedge -- and one
# release run DID wedge, on script 123 of 135, leaving the culprit to be reconstructed from
# file mtimes afterwards.  The phase matters as much as the name: that stall was in `run`, not
# `compile`, and the counter says which.
#
# \r only when stdout is a terminal.  CI logs render a carriage return as one unreadable line,
# so there each script gets its own line instead -- which also means a CI timeout names the
# script it died on rather than just stopping.
#
# The line is erased with \033[K rather than a fixed run of spaces: %-34s is a MINIMUM, so a
# basename longer than that leaves a tail of the previous script name sitting after the
# next one -- which reads as the harness having printed something it did not.
scripts=("$out"/*.sc)
nscripts=0; [ -e "${scripts[0]}" ] && nscripts=${#scripts[@]}
if [ -t 1 ]; then
  progress()     { printf "\r\033[K  [%3d/%3d] %-34s %-7s" "$1" "$nscripts" "$2" "$3"; }
  progress_end() { printf "\r\033[K"; }
else
  progress()     { printf "  [%3d/%3d] %-34s %s\n" "$1" "$nscripts" "$2" "$3"; }
  progress_end() { :; }
fi

total=0; failed=0; ran=0
for sc in "$out"/*.sc; do
  [ -e "$sc" ] || continue
  total=$((total+1))
  progress "$total" "$(basename "$sc")" compile
  if ! scala-cli compile -Wunused:imports -Wunused:locals -deprecation -Werror "$sc" >"$sc.log" 2>&1; then
    failed=$((failed+1))
    progress_end
    echo "FAIL $sc"
    grep -E "error|warn" "$sc.log" | grep -v "^\[warn\] \[warn\]" | head -8 | sed 's/^/    /'
    continue
  fi
  # Then RUN it, unless the block says it needs something the gate cannot assume (git,
  # bash, a display, a data file): `// doc: compile-only` anywhere in the block.
  if grep -q "// doc: compile-only" "$sc"; then continue; fi
  ran=$((ran+1))
  # `// doc: args a b c` supplies command-line arguments for CLI-style examples.
  docargs=$(sed -n 's|^// doc: args ||p' "$sc" | head -1)
  progress "$total" "$(basename "$sc")" run
  log="$PWD/$sc.out"
  if ! (cd "$out" && scala-cli run "$(basename "$sc")" -- $docargs >"$log" 2>&1); then
    failed=$((failed+1))
    progress_end
    echo "FAIL (run) $sc"
    grep -vE "^\[.*Compil|Compiled project|WARNING: Using incubator" "$sc.out" | tail -8 | sed 's/^/    /'
  fi
done
progress_end
echo "checked $total doc scripts ($ran run), $failed failed"

# The hand-mirrored fragments, with their value assertions: compile and run.
if [ "$*" = "" ] || echo "$*" | grep -q "ReferenceGuide\|QuickStartGuide"; then
  if scala-cli run -Wunused:imports -Wunused:locals -deprecation -Werror jsrc/docCheck.sc >target/doc-scripts/docCheck.log 2>&1; then
    echo "docCheck.sc (fragment mirror) compiled and ran clean"
  else
    failed=$((failed+1))
    echo "FAIL jsrc/docCheck.sc"
    grep -E "error|warn|Exception|assert" target/doc-scripts/docCheck.log | head -8 | sed 's/^/    /'
  fi
fi
[ "$failed" -eq 0 ]

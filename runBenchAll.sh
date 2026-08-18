#!/usr/bin/env bash
# Every cross-language benchmark table, apples to apples, in one run:
#   both Rust flavours are built and kept side by side, then uni.apps.BenchAll measures
#   NumPy | Scala | Rust | Scala·BLAS | Rust·BLAS on one table and prints a summary.
#
# The BLAS build needs an OpenBLAS the `openblas-src` crate can find:
#   Ubuntu:  sudo apt install libopenblas-dev
#   macOS:   brew install openblas          (and see the OPENBLAS_LIB_DIR hint below)
#   MSYS2:   pacman -S mingw-w64-ucrt-x86_64-openblas ; export OPENBLAS_LIB_DIR=/ucrt64/lib
# If it cannot be built the BLAS columns are simply absent; the rest of the table stands.
#
# Output goes to the terminal AND to a log named for the machine, so captures from several
# machines can sit in one directory without colliding:
#     bench-logs/benchAll-<os>-<host>-<yyyymmdd-HHMM>.out   e.g. …/benchAll-linux-quadd-20260816-1425.out
# (`bench-logs/` is git-ignored.) Pass -o <file> to choose the name, or -o - for terminal only.
set -u
cd "$(dirname "$0")" || exit 1

exe=""
os="linux"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) exe=".exe"; os="msys2" ;;
  Darwin*)              os="macos" ;;
esac
host="$(hostname -s 2>/dev/null || hostname 2>/dev/null || echo unknown)"
mkdir -p bench-logs
log="bench-logs/benchAll-${os}-${host}-$(date +%Y%m%d-%H%M).out"
if [ "${1:-}" = "-o" ]; then log="$2"; shift 2; fi
rel=rust/target/release

echo "── Rust: default build (pinned matmul, matrixmultiply in t3prf) ──"
( cd rust && cargo build --release --bin bench_mat --bin bench_tprf3 ) || exit 1
cp -f "$rel/bench_mat$exe"   "$rel/bench_mat_pure$exe"
cp -f "$rel/bench_tprf3$exe" "$rel/bench_tprf3_pure$exe"

# The BLAS flavour builds in its own target dir. Building both feature sets into one dir
# and copying binaries about leaves cargo's fingerprints for both "fresh", so a second
# consecutive run relinks neither and whatever sits at target/release/bench_mat — the
# other flavour — gets copied under the wrong name.
echo "── Rust: --features blas build (OpenBLAS) ──"
if ( cd rust && cargo build --release --features blas --target-dir target/blas --bin bench_mat --bin bench_tprf3 ); then
  cp -f "rust/target/blas/release/bench_mat$exe"   "$rel/bench_mat_blas$exe"
  cp -f "rust/target/blas/release/bench_tprf3$exe" "$rel/bench_tprf3_blas$exe"
else
  echo "   (BLAS build failed — no OpenBLAS for openblas-src? The Rust·BLAS column will be absent.)"
  rm -f "$rel/bench_mat_blas$exe" "$rel/bench_tprf3_blas$exe"
fi

if [ "$log" = "-" ]; then
  sbt "runMain uni.apps.BenchAll $*"
else
  echo "── logging to $log ──"
  # Strip sbt's "[info] " prefix in the log so the tables paste into markdown as they are.
  sbt "runMain uni.apps.BenchAll $*" 2>&1 | tee >(sed 's/^\[info\] //' > "$log")
  echo "── wrote $log ──"
fi

TOKEN_BYTE_LIMIT ?= 80000

all: test lint tokens

full:
	$(MAKE) lint
	$(MAKE) tokens
	cargo test --quiet

lint:
	cargo +nightly-2026-08-02 fmt --check
	cargo clippy --all-targets --all -- -D warnings

fix:
	cargo +nightly-2026-08-02 fmt
	cargo clippy --all-targets --all --allow-staged --allow-dirty --fix

test:
	cargo test --quiet

# Builds the Rust columns of the cross-language tables. --release matters: the
# binaries warn when run debug, and a debug number in a comparison table is noise.
# `sbt "runMain uni.apps.BenchAll"` from the repo root drives these alongside
# NumPy and Scala and emits the finished markdown.
bench:
	cargo build --release --bin bench_mat --bin bench_tprf3
	cargo run --release --bin bench_mat
	cargo run --release --bin bench_tprf3

tokens:
	@git ls-files '*.rs' | xargs wc -c \
		| awk -v limit=$(TOKEN_BYTE_LIMIT) \
			'$$2 != "total" && $$1 > limit {print int($$1/3.5) " tokens (est)  " $$2; f=1} \
			 END {exit f}' \
		&& echo "All files under ~$(TOKEN_BYTE_LIMIT) bytes."

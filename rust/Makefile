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

bench:
	cargo run --bin bench_tprf3

tokens:
	@git ls-files '*.rs' | xargs wc -c \
		| awk -v limit=$(TOKEN_BYTE_LIMIT) \
			'$$2 != "total" && $$1 > limit {print int($$1/3.5) " tokens (est)  " $$2; f=1} \
			 END {exit f}' \
		&& echo "All files under ~$(TOKEN_BYTE_LIMIT) bytes."

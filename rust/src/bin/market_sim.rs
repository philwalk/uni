//! The market simulator's command line: `uni::market_sim` is the program, this is its entry
//! point, so `cargo install vastblue-uni` and a dependent crate run the same code.
use uni::market_sim;

// the binary's allocator: see the `fast-alloc` feature
#[cfg(feature = "fast-alloc")]
#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

fn main() {
    market_sim::main();
}

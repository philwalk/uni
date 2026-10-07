//! The market simulator's command line: `uni::market_sim` is the program, this is its entry
//! point, so `cargo install vastblue-uni` and a dependent crate run the same code.
#[cfg(feature = "fast-alloc")]
use uni::fast_alloc;
use uni::market_sim;

// the binary's allocator: see the `fast-alloc` feature
#[cfg(feature = "fast-alloc")]
#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

fn main() {
    #[cfg(feature = "fast-alloc")]
    fast_alloc::keep_freed_memory();
    market_sim::main();
}

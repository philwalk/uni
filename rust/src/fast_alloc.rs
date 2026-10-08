//! The simulator binaries' mimalloc settings (`fast-alloc`). A binary that installs mimalloc as
//! its global allocator calls one at the top of `main`; a program linking the library keeps its
//! own allocator and never calls them.

use core::ffi::c_int;
use core::ffi::c_long;

// Linked for its native library: the functions below are mimalloc's.
use mimalloc as _;

/// `mi_option_purge_delay` in mimalloc v2's `mi_option_e` (`mimalloc.h`); its test pins the index
/// by the option's v2 default.
const MI_OPTION_PURGE_DELAY: c_int = 15;

unsafe extern "C" {
    #[cfg(test)]
    fn mi_option_get(option: c_int) -> c_long;
    fn mi_option_set(option: c_int, value: c_long);
}

/// Purges freed memory only after a second unused. A read frees and reallocates the same large
/// buffers path after path; purged at once they come back as page faults -- 1.7 million a 200 x
/// 100 read, a fifth of its CPU in the kernel -- while within the second they come back committed.
/// A long-lived process still returns what it stops using.
pub fn reuse_freed_memory() {
    // SAFETY: sets one integer option of the linked mimalloc; the index is checked by its test
    unsafe { mi_option_set(MI_OPTION_PURGE_DELAY, 1000) };
}

/// Never purges freed memory: a hair faster than `reuse_freed_memory`, but a long-lived process
/// then holds every thread's peak, and a sampler's chains grew to 24 GB. For a one-read process.
pub fn keep_freed_memory() {
    // SAFETY: as `reuse_freed_memory`
    unsafe { mi_option_set(MI_OPTION_PURGE_DELAY, -1) };
}

#[cfg(test)]
mod tests {
    use super::MI_OPTION_PURGE_DELAY;
    use super::keep_freed_memory;
    use super::mi_option_get;
    use super::reuse_freed_memory;

    #[test]
    fn the_purge_delay_index_is_mimalloc_v2s() {
        // SAFETY: reads one integer option of the linked mimalloc
        let get = || unsafe { mi_option_get(MI_OPTION_PURGE_DELAY) };
        if std::env::var_os("MIMALLOC_PURGE_DELAY").is_none() {
            assert_eq!(get(), 10, "v2's default purge delay in ms (v3's is 1000)");
        }
        keep_freed_memory();
        assert_eq!(get(), -1);
        reuse_freed_memory();
        assert_eq!(get(), 1000);
    }
}

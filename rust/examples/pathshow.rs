//! One half of the cross-language demo pair; `jsrc/pathshow.sc` is the other.
//! Both print every rendering of the same path strings, byte-identically, so the
//! pair doubles as an end-to-end parity check of the conversion layer — the
//! library's core MSYS2/cygwin mission. See the Scala twin for the run recipe
//! and the machine-dependence note.

#![allow(
    non_snake_case,
    reason = "mirrors the Scala twin line for line; the shared API is camelCase by design"
)]
#![allow(
    clippy::print_stdout,
    clippy::unwrap_used,
    clippy::expect_used,
    reason = "a demo prints its report and dies loudly"
)]

use uni::upath::StrExts;
use uni::upath::StrPathExts;
use uni::upath::UPath;

const DEFAULT_INPUTS: [&str; 9] = [
    ".",                   // dot expansion against the working directory
    "~",                   // home expansion
    "/usr/bin/bash",       // msys-mounted posix path
    "/c/temp",             // cygdrive-style drive mount
    "C:",                  // bare drive: resolves to that drive's working directory
    "C:foo",               // drive-relative on Windows rules; ordinary relative on posix
    "a:b:c",               // unrepresentable on Windows: the BadPath family
    "sub/dir/file.tar.gz", // relative with a multi-dot name
    "UPPER.TXT",
];

fn show(input: &str) {
    let p: UPath = input.as_path().expect("as_path is total for plain strings");
    println!("[{input}]");
    println!("  isBadPath:     {}", p.isBadPath());
    println!("  badPathString: {}", p.badPathString());
    println!("  posx:          {}", p.posx());
    println!("  localpath:     {}", p.localpath());
    println!("  dospath:       {}", p.dospath());
    println!("  noDrive:       {}", p.noDrive());
    println!(
        "  baseName:      {}   last: {}   ext: {}   dotsuffix: {}",
        p.baseName().unwrap_or(""),
        p.last().unwrap_or(""),
        p.ext().unwrap_or(""),
        p.dotsuffix().unwrap_or("")
    );
    let segs = p.segments();
    println!("  segments:      {}: [{}]", segs.len(), segs.join(", "));
    println!("  reversePath:   {}", p.reversePath());
    println!("  relpath:       {}", p.relpath());
    println!("  stdpath:       {}", p.stdpath());
    println!("  posix:         {}", p.posix().unwrap_or_default());
    println!();
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.is_empty() {
        for input in DEFAULT_INPUTS {
            show(input);
        }
    } else {
        for input in &args {
            show(input);
        }
    }

    println!("string extensions:");
    println!("  MixedCase.lc            -> {}", "MixedCase".lc());
    println!("  MixedCase.uc            -> {}", "MixedCase".uc());
    println!(
        "  archive.tar.gz          -> dropSuffix: {}",
        "archive.tar.gz".dropSuffix()
    );
    println!(
        "  README.startsWithIgnoreCase(read) -> {}",
        "README".startsWithIgnoreCase("read")
    );
    println!(
        "  prefix-rest.stripPrefix(prefix-)  -> {}",
        "prefix-rest".strip_prefix_or_self("prefix-")
    );
    println!("  a//b.posx               -> {}", "a//b".posx());
    println!();
    println!(
        "isSameFile: '.' vs './.'  -> {}",
        ".".as_path()
            .expect(".")
            .isSameFile(&"./.".as_path().expect("./."))
    );
}

//! One half of the cross-language demo pair; `jsrc/treestat.sc` is the other.
//! Both scan the same directory tree and print byte-identical reports, so the
//! pair doubles as an end-to-end parity check of every feature it touches:
//!
//! ```text
//! scala-cli run jsrc/treestat.sc -- src                               > scala.out
//! cargo run --manifest-path rust/Cargo.toml --example treestat -- src > rust.out
//! diff scala.out rust.out
//! ```
//!
//! Exercised, both sides: eachArg/showUsage CLI parsing, asPath with the BadPath
//! family, pathsTree traversal, file metadata (length, lastModifiedTime), Big
//! arithmetic with numStr formatting, date parsing (parseDateSmart) and
//! arithmetic (daysBetween), hash64, NumPyRng deterministic sampling, CSV
//! write/read with delimiter detection. See the Scala twin for the determinism
//! notes.

#![allow(
    non_snake_case,
    reason = "mirrors the Scala twin line for line; the shared API is camelCase by design"
)]
#![allow(
    clippy::print_stdout,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss,
    reason = "a demo prints its report and dies loudly; index/size casts are tree-scale"
)]

use uni::cli::ArgCtx;
use uni::cli::eachArg;
use uni::cli::showUsage;
use uni::numpy_rng::NumPyRng;
use uni::udata::Big;
use uni::udata::NumFormat;
use uni::udata::numStr;
use uni::upath::StrPathExts;
use uni::upath::UPath;
use uni::upath::io::Charset;
use uni::utime::UniDateTime;
use uni::utime::daysBetween;
use uni::utime::now;
use uni::utime::parseDateSmart;

fn usage(m: &str) -> ! {
    showUsage(
        m,
        &[
            "[-n <count>]    ; rows in the top-N tables (default 5)",
            "[-asof <date>]  ; reference date for ages (default: today)",
            "[-csv <path>]   ; write the by-extension table as CSV and read it back",
            "<dir>           ; directory tree to scan",
        ],
    )
}

fn midnight(d: &UniDateTime) -> UniDateTime {
    d.withHour(0).withMinute(0).withSecond(0).withNano(0)
}

#[expect(
    clippy::too_many_lines,
    reason = "one linear report, mirroring the Scala twin's main statement for statement; \
              splitting it would obscure the line-for-line correspondence the pair exists for"
)]
fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (mut topN, mut asofArg, mut csvPath, mut dirArg) =
        (5usize, String::new(), String::new(), String::new());

    eachArg(&args, &|m| usage(m), |ctx: &mut ArgCtx, arg| match arg {
        "-n" => topN = ctx.nextInt() as usize,
        "-asof" => asofArg = ctx.consumeNext().to_owned(),
        "-csv" => csvPath = ctx.consumeNext().to_owned(),
        a if !a.starts_with('-') && dirArg.is_empty() => dirArg = a.to_owned(),
        a => ctx.usage(&format!("unknown argument [{a}]")),
    });
    if dirArg.is_empty() {
        usage("no directory given");
    }

    // `as_path` never throws on hostile input: it comes back as a BadPath family
    // member, and `badPathString` recovers the original for display.
    let root = dirArg.as_path().unwrap_or_else(|e| usage(&format!("{e}")));
    if root.isBadPath() {
        usage(&format!("bad path [{}]", root.badPathString()));
    }
    if !root.isDirectory() {
        usage(&format!("not a directory: [{}]", root.posx()));
    }

    let asof = if asofArg.is_empty() {
        midnight(&now())
    } else {
        parseDateSmart(&asofArg)
    };
    if !asof.isValid() {
        usage(&format!("unparseable date [{asofArg}]"));
    }

    let rootPosx = root.posx().to_owned();
    let rel = |p: &UPath| -> String {
        let s = p.posx();
        match s.strip_prefix(&rootPosx) {
            Some(rest) => rest.trim_start_matches('/').to_owned(),
            None => s.to_owned(),
        }
    };

    // one traversal, files and dirs split; everything downstream sorts on `rel`
    let all = root.pathsTree();
    let mut files: Vec<(String, i64, String, UPath)> = all
        .iter()
        .filter(|p| p.isFile())
        .map(|p| {
            let ext = p.ext().unwrap_or("").to_owned();
            (rel(p), p.length(), ext, p.clone())
        })
        .collect();
    files.sort_by(|a, b| a.0.cmp(&b.0));
    let dirs = all.iter().filter(|p| p.isDirectory()).count();

    let n = files.len();
    let total = files
        .iter()
        .fold(Big::from_i64(0), |acc, f| acc.add(&Big::from_i64(f.1)));
    let abbr = NumFormat::Abbrev();
    let mean = if n > 0 {
        total.div(&Big::from_i64(n as i64))
    } else {
        Big::from_i64(0)
    };
    let mut sizes: Vec<i64> = files.iter().map(|f| f.1).collect();
    sizes.sort_unstable();
    let median = if n > 0 {
        Big::from_i64(sizes[(n - 1) / 2])
    } else {
        Big::from_i64(0)
    };

    println!("tree: {rootPosx}");
    println!("as of: {}", asof.ymd());
    println!(
        "files: {n}   dirs: {dirs}   total:{} ({} bytes)",
        numStr(&total, &abbr),
        total.toLong()
    );
    println!(
        "mean:{}   median:{}",
        numStr(&mean, &abbr),
        numStr(&median, &abbr)
    );

    // by extension: bytes descending, then extension ascending
    let mut agg: std::collections::BTreeMap<String, (usize, i64)> =
        std::collections::BTreeMap::new();
    for (_, size, ext, _) in &files {
        let label = if ext.is_empty() {
            "(none)".to_owned()
        } else {
            ext.clone()
        };
        let e = agg.entry(label).or_insert((0, 0));
        e.0 += 1;
        e.1 += size;
    }
    let mut byExt: Vec<(String, usize, i64)> =
        agg.into_iter().map(|(ext, (c, b))| (ext, c, b)).collect();
    byExt.sort_by(|a, b| b.2.cmp(&a.2).then(a.0.cmp(&b.0)));
    println!(
        "by extension (top {} of {}):",
        topN.min(byExt.len()),
        byExt.len()
    );
    for (ext, count, bytes) in byExt.iter().take(topN) {
        println!(
            "  {count:>6} {}  {ext}",
            numStr(&Big::from_i64(*bytes), &abbr)
        );
    }

    // age buckets against the reference date; a future mtime lands in 0-1
    let ages: Vec<i64> = files
        .iter()
        .map(|f| daysBetween(&f.3.lastModifiedTime(), &asof))
        .collect();
    let bucket = |lo: i64, hi: i64| ages.iter().filter(|a| **a >= lo && **a <= hi).count();
    println!(
        "age in days:  0-1: {}   2-7: {}   8-30: {}   31-365: {}   older: {}",
        ages.iter().filter(|a| **a <= 1).count(),
        bucket(2, 7),
        bucket(8, 30),
        bucket(31, 365),
        ages.iter().filter(|a| **a > 365).count()
    );

    let mut largest: Vec<&(String, i64, String, UPath)> = files.iter().collect();
    largest.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    let largest: Vec<_> = largest.into_iter().take(topN).collect();
    println!("largest {}:", largest.len());
    for (r, size, _, p) in &largest {
        let age = daysBetween(&p.lastModifiedTime(), &asof);
        println!(
            "  {} {age:>5}d  {}  {r}",
            numStr(&Big::from_i64(*size), &abbr),
            p.hash64()
        );
    }

    // deterministic sample, with replacement: both languages draw the same
    // uniform doubles from the same seed, so the same rows print
    let mut rng = NumPyRng::new(42);
    let k = topN.min(n);
    println!("sample (seed 42, k={k}, with replacement):");
    for _ in 0..k {
        let idx = rng.uniform(0.0, n as f64) as usize;
        println!("  {}", files[idx].0);
    }

    if !csvPath.is_empty() {
        let out = csvPath.as_path().unwrap_or_else(|e| usage(&format!("{e}")));
        let ok = out.withWriter(Charset::Utf8, false, |w| {
            w.print("ext,count,bytes\n")?;
            for (ext, count, bytes) in &byExt {
                w.print(&format!("{ext},{count},{bytes}\n"))?;
            }
            Ok(())
        });
        assert!(ok, "cannot write {}", out.posx());
        let rows = out.lines().len() - 1;
        println!("csv round-trip: {rows} data rows, delim [{}]", out.delim());
    }
}

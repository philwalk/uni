#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
//> using dep org.vastblue:uni_3:0.23.0

// One half of the cross-language demo pair; `rust/examples/treestat.rs` is the other.
// Both scan the same directory tree and print byte-identical reports, so the pair
// doubles as an end-to-end parity check of every feature it touches:
//
//   scala-cli run jsrc/treestat.sc -- src                          > scala.out
//   cargo run --manifest-path rust/Cargo.toml --example treestat -- src > rust.out
//   diff scala.out rust.out
//
// Exercised, both sides: eachArg/showUsage CLI parsing, asPath with the BadPath
// family, pathsTree traversal, file metadata (length, lastModifiedTime), Big
// arithmetic with numStr formatting, date parsing (parseDateSmart) and arithmetic
// (daysBetween), hash64, NumPyRNG deterministic sampling, CSV write/read with
// delimiter detection.
//
// Determinism notes: ages are measured against a DATE (midnight), not an instant,
// so two runs on the same day agree; listings sort on the root-relative path
// (ASCII byte order); the sample is seeded. Only stdout is compared — usage goes
// to stderr.
object Treestat {
  def println(s: String = ""): Unit = print(s"$s\n")

  import uni.*
  import uni.data.*
  import uni.time.*

  def usage(m: String = ""): Nothing =
    showUsage(m,
      "[-n [count]]        ; rows in the top-N tables (count optional, default 5)",
      "[-minsize <bytes>]  ; ignore files smaller than <bytes>",
      "[-asof <date>]  ; reference date for ages (default: today)",
      "[-csv <path>]   ; write the by-extension table as CSV and read it back",
      "<dir>           ; directory tree to scan",
    )

  def main(args: Array[String]): Unit = {
    var topN            = 5
    var minSize         = 0L
    var asofArg         = ""
    var csvPath         = ""
    var dirArg          = ""

    eachArg(args.toSeq, usage) {
      // `-n` takes an OPTIONAL count: peekNext looks ahead without consuming,
      // so `-n 8 dir` and `-n dir` both parse (the latter keeps the default)
      case "-n"    => if peekNext.matches("[0-9]+") then topN = nextInt
      case "-minsize" => minSize = nextLong
      case "-asof" => asofArg = consumeNext
      case "-csv"  => csvPath = consumeNext
      case arg if !arg.startsWith("-") && dirArg.isEmpty => dirArg = arg
      case arg => usage(s"unknown argument [$arg]")
    }
    if dirArg.isEmpty then usage("no directory given")

    // `asPath` is total: a hostile string comes back as a BadPath family member
    // instead of throwing, and `badPathString` recovers the original for display.
    val root = dirArg.asPath
    if root.isBadPath then usage(s"bad path [${root.badPathString}]")
    if !root.isDirectory then usage(s"not a directory: [${root.posx}]")

    val asof =
      if asofArg.nonEmpty then parseDateSmart(asofArg)
      else TimeUtils.now.withHour(0).withMinute(0).withSecond(0).withNano(0)
    if !asof.isValid then usage(s"unparseable date [$asofArg]")

    val rootPosx = root.posx
    def rel(p: Path): String =
      val s = p.posx
      if s.startsWith(rootPosx) then s.drop(rootPosx.length).dropWhile(_ == '/') else s

    // one traversal, files and dirs split; everything downstream sorts on `rel`
    val all   = root.pathsTree
    val files = all.filter(_.isFile).map(p => (rel(p), p.length, p.ext, p))
      .filter(_._2 >= minSize).sortBy(_._1)
    val dirs  = all.count(_.isDirectory)
    // immediate listings, distinct from the recursive walk above
    println(s"top level: ${root.paths.length} entries, ${root.subdirs.length} dirs, ${root.subfiles.length} files")

    val n     = files.length
    val total = files.foldLeft(Big(0))((acc, f) => acc + Big(f._2))
    val abbr  = NumFormat.Abbrev
    val mean  = if n > 0 then total / Big(n) else Big(0)
    val sizes = files.map(_._2).sorted
    val median = if n > 0 then Big(sizes((n - 1) / 2)) else Big(0)

    println(s"tree: $rootPosx")
    println(s"as of: ${asof.ymd}")
    println(s"files: $n   dirs: $dirs   total:${numStr(total, abbr)} (${total.toLong} bytes)")
    println(s"mean:${numStr(mean, abbr)}   median:${numStr(median, abbr)}")

    // by extension: bytes descending, then extension ascending
    val byExt = files.groupBy(f => if f._3.isEmpty then "(none)" else f._3)
      .map((ext, fs) => (ext, fs.length, fs.map(_._2).sum))
      .toSeq.sortBy((ext, _, bytes) => (-bytes, ext))
    println(s"by extension (top ${math.min(topN, byExt.length)} of ${byExt.length}):")
    for (ext, count, bytes) <- byExt.take(topN) do
      println(f"  $count%6d ${numStr(Big(bytes), abbr)}  $ext")

    // age buckets against the reference date; a future mtime lands in 0-1
    val ages = files.map(f => daysBetween(f._4.lastModifiedTime, asof))
    def bucket(lo: Long, hi: Long): Int = ages.count(a => a >= lo && a <= hi)
    println(s"age in days:  0-1: ${ages.count(_ <= 1)}   2-7: ${bucket(2, 7)}" +
      s"   8-30: ${bucket(8, 30)}   31-365: ${bucket(31, 365)}   older: ${ages.count(_ > 365)}")

    val largest = files.sortBy(f => (-f._2, f._1)).take(topN)
    println(s"largest ${largest.length}:")
    for (r, size, _, p) <- largest do
      val age = daysBetween(p.lastModifiedTime, asof)
      println(f"  ${numStr(Big(size), abbr)} $age%5dd  ${p.hash64}  $r")
    largest.headOption.foreach { (r, _, _, p) =>
      val (crc, len) = p.cksum
      println(s"  digests of $r:")
      println(s"    cksum $crc/$len   md5 ${p.md5}")
      println(s"    sha256 ${p.sha256}")
    }

    // deterministic sample, with replacement: both languages draw the same
    // uniform doubles from the same seed, so the same rows print
    val rng = new NumPyRNG(42L)
    val k = math.min(topN, n)
    println(s"sample (seed 42, k=$k, with replacement):")
    for _ <- 0 until k do
      val idx = rng.uniform(0.0, n.toDouble).toInt
      println(s"  ${files(idx)._1}")

    if csvPath.nonEmpty then {
      val out = csvPath.asPath
      out.withWriter() { w =>
        w.print("ext,count,bytes\n")
        for (ext, count, bytes) <- byExt do w.print(s"$ext,$count,$bytes\n")
      }
      val rows = out.lines.length - 1
      println(s"csv round-trip: $rows data rows, delim [${out.delim}]")
    }
  }
}

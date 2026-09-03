#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
//> using dep org.vastblue:uni_3:0.23.1

// One half of the cross-language demo pair; `rust/examples/pathshow.rs` is the other.
// Both print every rendering of the same path strings, byte-identically, so the pair
// doubles as an end-to-end parity check of the conversion layer — the library's core
// MSYS2/cygwin mission that no other example demonstrates:
//
//   scala-cli run jsrc/pathshow.sc                        > scala.out
//   cargo build --manifest-path rust/Cargo.toml --example pathshow
//   rust/target/debug/examples/pathshow > rust.out   # direct exe: cargo run's
//                             # Windows pipe plumbing can swallow blank lines
//   diff scala.out rust.out
//
// Exercised, both sides: posx/localpath/dospath/noDrive/segments/baseName/last/
// ext/dotsuffix/reversePath/relpath/stdpath/posix renderings, mount-table
// resolution of /c/... and /usr/... shapes, drive-relative C:foo, tilde and dot
// expansion, the BadPath family (badPathString degrading to posx on ordinary
// paths), isSameFile, and the context-free String extensions.
//
// The output is machine-dependent (mount table, cwd, home) but identical between
// the two programs on the same machine — that is the pair's contract.
object Pathshow {
  def println(s: String = ""): Unit = print(s"$s\n")

  import uni.*

  val defaultInputs = Seq(
    ".",                    // dot expansion against the working directory
    "~",                    // home expansion
    "/usr/bin/bash",        // msys-mounted posix path
    "/c/temp",              // cygdrive-style drive mount
    "C:",                   // bare drive: resolves to that drive's working directory
    "C:foo",                // drive-relative on Windows rules; ordinary relative on posix
    "a:b:c",                // unrepresentable on Windows: the BadPath family
    "sub/dir/file.tar.gz",  // relative with a multi-dot name
    "UPPER.TXT",
  )

  def main(args: Array[String]): Unit = {
    val inputs = if args.nonEmpty then args.toSeq else defaultInputs

    for in <- inputs do {
      val p = in.asPath
      println(s"[$in]")
      println(s"  isBadPath:     ${p.isBadPath}")
      println(s"  badPathString: ${p.badPathString}")
      println(s"  posx:          ${p.posx}")
      println(s"  localpath:     ${p.localpath}")
      println(s"  dospath:       ${p.dospath}")
      println(s"  noDrive:       ${p.noDrive}")
      println(s"  baseName:      ${p.baseName}   last: ${p.last}   ext: ${p.ext}   dotsuffix: ${p.dotsuffix}")
      println(s"  segments:      ${p.segments.length}: [${p.segments.mkString(", ")}]")
      println(s"  reversePath:   ${p.reversePath}")
      println(s"  relpath:       ${p.relpath}")
      println(s"  stdpath:       ${p.stdpath}")
      println(s"  posix:         ${p.posix}")
      println()
    }

    println("string extensions:")
    println(s"  MixedCase.lc            -> ${"MixedCase".lc}")
    println(s"  MixedCase.uc            -> ${"MixedCase".uc}")
    println(s"  archive.tar.gz          -> dropSuffix: ${"archive.tar.gz".dropSuffix}")
    println(s"  README.startsWithIgnoreCase(read) -> ${"README".startsWithIgnoreCase("read")}")
    println(s"  prefix-rest.stripPrefix(prefix-)  -> ${"prefix-rest".stripPrefix("prefix-")}")
    println(s"  a//b.posx               -> ${"a//b".posx}")
    println()
    println(s"isSameFile: '.' vs './.'  -> ${".".asPath.isSameFile("./.".asPath)}")
  }
}

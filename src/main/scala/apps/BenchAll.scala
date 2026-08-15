package uni.apps

import uni.*

/**
 * Every cross-language benchmark table in the markdown docs, in one command:
 *
 * {{{
 *   sbt "runMain uni.apps.BenchAll"
 * }}}
 *
 * Runs [[MatBench]] (the two MatD tables in `docs/MatDCheatSheet.md`) and then
 * [[Tprf3Bench]] (the 3PRF tables in `docs/MatDCheatSheet.md` and `README.md`), each
 * across NumPy, Scala and Rust, and prints finished markdown for all of them. Paste the
 * tables in; nothing needs transcribing.
 *
 * # Before running
 *
 * The Rust binaries are not built here on purpose — that keeps this out of the
 * cargo/toolchain business and lets you choose the build being compared, which matters
 * because `--features blas` changes who wins on some rows. Build them first:
 *
 * {{{
 *   cd rust && cargo build --release --bin bench_mat --bin bench_tprf3
 * }}}
 *
 * Each binary reports its own configuration, and both runners warn if the binary is
 * older than `rust/src`. A missing binary is not fatal: the column is dropped and the
 * table shape follows what actually ran.
 *
 * Flags are passed through to both: `-nopython`, `-norust`, `-python <exe>`.
 *
 * # Provenance
 *
 * Numbers are machine-specific. When pasting a table into a doc, keep the config lines
 * that follow it — JVM version, NumPy version and its BLAS, and the Rust build — because
 * without them a reader cannot tell whether a row is a property of the implementation or
 * of the box it ran on.
 */
object BenchAll:
  def println(s: String = ""): Unit = print(s"$s\n")

  def usage(m: String = ""): Nothing = showUsage(m, "",
    "-nopython      ; skip the NumPy columns",
    "-norust        ; skip the Rust columns",
    "-python <exe>  ; interpreter to use (default: first on PATH with a working numpy)",
    "",
    "Build the Rust halves first:",
    "  cd rust && cargo build --release --bin bench_mat --bin bench_tprf3",
  )

  def main(args: Array[String]): Unit =
    // Validate here so a typo fails immediately rather than half way through, after the
    // MatD suite has already spent a minute measuring.
    eachArg(args.toSeq, usage) {
      case "-nopython" | "-norust" => ()
      case "-python"               => consumeNext
      case a                       => usage(s"unrecognized arg [$a]")
    }

    println("=" * 78)
    println("MatD — docs/MatDCheatSheet.md")
    println("=" * 78)
    MatBench.main(args)

    println()
    println("=" * 78)
    println("3PRF — docs/MatDCheatSheet.md and README.md")
    println("=" * 78)
    Tprf3Bench.main(args)

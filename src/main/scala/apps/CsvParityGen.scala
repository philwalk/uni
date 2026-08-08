package uni.apps

import uni.*
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Regenerates the cross-language parity fixture in `test-data/csv-parity/` for
 * `uni.io.FastCsv` and its Rust port (`rust/src/upath/csv.rs`).
 *
 * Consumed by two tests that must agree with each other:
 *   - uni.io.CsvParitySuite      (Scala, src/test)
 *   - rust/tests/csv_parity.rs   (Rust)
 *
 * Neither needs the other language installed, because both compare against the
 * committed reference rather than against each other.
 *
 * Unlike the path fixture, nothing here is platform-dependent: the parser works on
 * bytes and never consults the OS, so one reference serves every host. Line endings
 * are part of the *input* rather than the environment, which is why the CRLF and
 * lone-CR cases are written as explicit bytes.
 *
 * Two readings are recorded per case, because they are allowed to differ:
 *   - `rows`   -- `csvRows`, which reads the whole file and is rectangular
 *   - `stream` -- `csvRowsStream`, which pads only to its 100-row window and so may
 *                 emit a later, wider row jagged
 *   - `hdr`    -- `loadSmart` header detection, including `colN` names for blanks
 *   - `mat`    -- `loadSmart` cell values, as raw IEEE bits so the comparison is
 *                 exact and does not go through either language's float printer
 *
 * Run ONLY when the reference is meant to move -- regenerating rewrites the very
 * values the tests check, so an unintended run masks a regression instead of
 * catching it. Review the diff before keeping it.
 *
 * Run:  sbt "runMain uni.apps.CsvParityGen"
 */
object CsvParityGen:
  def println(s: String = ""): Unit = print(s"$s\n")

  /** Inputs, chosen to pin the places a general-purpose CSV parser would diverge. */
  val cases: Seq[(String, String)] = Seq(
    "plain"            -> "a,b,c\n1,2,3\n",
    "crlf"             -> "a,b\r\n1,2\r\n",
    "lone-cr"          -> "a,b\r1,2\r",
    "no-final-newline" -> "a,b,c",
    "semicolon"        -> "a;b;c\n1;2;3\n",
    "tab"              -> "a\tb\tc\n1\t2\t3\n",
    "pipe"             -> "a|b|c\n1|2|3\n",
    // Trimming: unquoted fields lose surrounding space, quoted ones keep it.
    "untrimmed"        -> "  1 ,\t2\t\n 3 , 4 \n",
    "quoted-spaces"    -> "\" x \",\" y \"\n\"a\",\"b\"\n",
    // Quoting proper.
    "quoted-delimiter" -> "\"a,b\",c\n\"d,e\",f\n",
    "quoted-newline"   -> "\"one\ntwo\",c\n\"x\",y\n",
    "doubled-quote"    -> "\"say \"\"hi\"\"\",b\n\"q\",r\n",
    // Deliberate RFC 4180 deviation: a quote that closes nothing is literal.
    "stray-quote"      -> "\"a\"b\",c\n\"d\",e\n",
    // Row-level rules.
    "blank-rows"       -> "a,b\n\n , \nc,d\n",
    "single-column"    -> "alpha\nbeta\ngamma\n",
    "ragged-short"     -> "a,b,c\nsolo\nd,e,f\n",
    "ragged-long"      -> "a,b\nw,x,y,z\nc,d\n",
    "short-first-row"  -> "9\n1,2\n3,4\n",
    "empty-fields"     -> "a,,c\n,,\n1,2,3\n",
    // Rows well over the sniffer's 100-character check interval: the case that
    // exposed the padding being a no-op when its width came from the sniffer.
    "wide-rows"        -> Seq(wideRow(12), wideRow(9), wideRow(12)).mkString("", "\n", "\n"),
    // A row wider than the 100-row streaming window: `rows` pads it, `stream`
    // cannot and must emit it jagged rather than truncated.
    // Numeric cell parsing, i.e. `Big.big(String)`. Currency values are quoted
    // because their thousands separator is also the delimiter.
    "numeric-cells"    -> Seq(
      "plain,decimal,negative,scientific,currency,percent,blank,junk,trailing-dot,infinite,notanumber,plus,leading-dot",
      "1,2.5,-3.25,1e3,\"$1,234.56\",12%,,abc,4.,inf,NaN,+5,.5",
      "0,0.1,-0.035,2.5e-3,\"$0.99\",-3.5%, ,1.2.3,.,Infinity,nan,-0,5.0",
    ).mkString("", "\n", "\n"),
    "past-window"      -> (Seq.fill(100)("a,b") :+ "w,x,y,z").mkString("", "\n", "\n"),
  )

  /** A double as raw IEEE bits, so the fixture compares values and not the two
   *  languages' float printers. NaN is spelled out because its payload is not
   *  guaranteed to survive a round trip through either language. */
  def cell(d: Double): String =
    if d.isNaN then "NaN" else f"${java.lang.Double.doubleToRawLongBits(d)}%016x"

  def matRows(m: uni.data.MatD): Seq[Seq[String]] =
    (0 until m.rows).map(r => (0 until m.cols).map(c => cell(m(r, c))))

  def wideRow(n: Int): String = (1 to n).map(i => f"value_$i%05d").mkString(",")

  /** Fields are joined with tabs, so anything that could be mistaken for structure
   *  is escaped. Backslash first, or the other escapes would be re-escaped. */
  def esc(s: String): String =
    s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

  def encode(kind: String, name: String, rows: Seq[Seq[String]]): Seq[String] =
    rows.zipWithIndex.map: (row, i) =>
      s"$kind\t$name\t$i\t${row.map(esc).mkString("\t")}"

  def main(args: Array[String]): Unit =
    //val root    = Paths.get("test-data/csv-parity")
    val inputs  = Paths.get("test-data/csv-parity/inputs")
    java.nio.file.Files.createDirectories(inputs)

    val lines = cases.flatMap: (name, content) =>
      val file = Paths.get(s"test-data/csv-parity/inputs/$name.csv")
      java.nio.file.Files.write(file, content.getBytes(UTF_8))
      val smart = file.loadSmartD
      encode("rows", name, file.csvRows) ++
        encode("stream", name, file.csvRowsStream.toSeq) ++
        Seq(s"hdr\t$name\t0\t${smart.headers.map(esc).mkString("\t")}") ++
        encode("mat", name, matRows(smart.mat))

    val header = Seq(
      "# Cross-language CSV parity reference. Generated by uni.apps.CsvParityGen.",
      "# Do not hand-edit; see the generator for what each case pins.",
      "# kind<TAB>case<TAB>rowIndex<TAB>field...   (\\\\ \\t \\n \\r are escaped)",
      s"# cases: ${cases.length}",
    )
    val out = Paths.get("test-data/csv-parity/scala-reference.txt")
    out.writeLines(header ++ lines)
    println(s"wrote ${lines.length} rows for ${cases.length} cases -> ${out.posx}")

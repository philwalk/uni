package uni.apps

import uni.*
import uni.data.*
import uni.data.Big.Big

/**
 * Regenerates the cross-language parity fixture in `test-data/big-parity/` for
 * `uni.data.Big` and its Rust port (`rust/src/udata/big.rs`).
 *
 * Consumed by two tests that must agree with each other:
 *   - uni.data.BigParitySuite       (Scala, src/test)
 *   - rust/tests/big_parity.rs      (Rust)
 *
 * # Why this fixture carries the most weight of any of them
 *
 * The Scala delegates to `java.math.BigDecimal`; the Rust delegates to nothing and writes
 * out arbitrary-precision decimal arithmetic by hand. Every classical trap is represented:
 * exact (unbounded) +,-,* against DECIMAL128-rounded / and sqrt; HALF_EVEN ties at the
 * 34th digit; the preferred-scale and trailing-zero rules of division; `toString`'s
 * scientific-notation thresholds; `toInt`'s low-order-bits truncation; and the BigNaN
 * sentinel, which must survive every operation by short-circuit rather than by arithmetic.
 *
 * Values are rendered with `toString`, so scale is pinned as well as numeric value:
 * `2.50 + 0.25` must render `2.75`, and `1E+3` must stay scientific.
 *
 * Deliberately absent: negative `pow` exponents and `sqrt` of negatives, which THROW in
 * Scala (a wart) while the no-panic Rust returns BigNaN -- a documented divergence, so
 * neither answer is recorded.
 *
 * Run ONLY when the reference is meant to move; review the diff before keeping it.
 *
 * Run:  sbt "runMain uni.apps.BigParityGen"
 */
object BigParityGen:
  def println(s: String = ""): Unit = print(s"$s\n")

  private def render(b: Big): String = if b.isNaN then "!nan" else b.toString

  /** `!nan` in an input column means the sentinel itself. */
  private def read(s: String): Big = if s == "!nan" then BigNaN else Big(s)

  val parseInputs: Seq[String] = Seq(
    "0", "1", "-1", "+42", "0.00", "2.50", "-2.50", "1234.5678", "0.1", ".5", "5.",
    "1e10", "1.7E-8", "-3.14e+2", "0E-10", "-0", "-0.0",
    "$1,234.56", "1,000,000", "$-5", "12.5%", "-0.5%", "50%", "  77  ",
    "1234567890123456789012345678901234",       // 34 digits, the DECIMAL128 edge
    "12345678901234567890123456789012345",      // 35: parse keeps all (parse is exact)
    "99999999999999999999999999999999999999999999999999999999999999999999", // 68
    "-0.000000012345678901234567890123",        // the sentinel literal, arriving as data
    "abc", "", "NaN", "1.2.3", "1_000", "one",
  )

  val binaryPairs: Seq[(String, String)] = Seq(
    ("0.1", "0.2"), ("2.50", "0.25"), ("-7.5", "2.5"), ("0.00", "3"),
    ("1e30", "1e-30"), ("1E+3", "1"), ("2.0", "2.00"),
    ("9999999999999999999999999999999999", "9999999999999999999999999999999999"),
    ("9999999999999999999999999999999999", "1"),
    ("1", "3"), ("2", "3"), ("1", "7"), ("22", "7"), ("1", "32"),
    ("10000000000000000000000000000000005", "10"),  // HALF_EVEN tie at digit 34
    ("10000000000000000000000000000000015", "10"),  // tie the other way
    ("355", "113"), ("-1", "3"), ("1", "-3"), ("-1", "-3"),
    ("123.456", "0.001"), ("1.05", "0.15"),
    ("!nan", "1"), ("1", "!nan"), ("!nan", "!nan"), ("1", "0"), ("0", "0"),
  )

  val setScaleCases: Seq[(String, Int)] = Seq(
    ("2.567", 2), ("2.5", 0), ("-2.5", 0), ("1.005", 2), ("3.1", 5),
    ("0.0000001", 3), ("1234.5678", 0), ("!nan", 2),
  )
  val modes = Seq(
    RoundingMode.UP, RoundingMode.DOWN, RoundingMode.CEILING, RoundingMode.FLOOR,
    RoundingMode.HALF_UP, RoundingMode.HALF_DOWN, RoundingMode.HALF_EVEN,
  )

  val unaryInputs: Seq[String] = Seq(
    "2.50", "-2.50", "0.00", "1E+3", "-0.000001", "!nan",
  )

  val sqrtInputs: Seq[String] = Seq("4", "2", "2.25", "0.0001", "1E-34", "0", "152.2756", "!nan")

  val powCases: Seq[(String, Int)] = Seq(
    ("1.05", 10), ("2", 100), ("10", 34), ("0.1", 5), ("-2", 3), ("2.5", 0), ("!nan", 2),
  )
  val powfCases: Seq[(String, Double)] = Seq(
    ("2", 0.5), ("1.21", 0.5), ("2", 1.5), ("10", 0.25), ("!nan", 0.5),
  )

  val fromDoubleCases: Seq[Double] =
    Seq(0.1, 0.5, 3.14159, 1e7, 1e-4, 123456789.123, 2.0, -0.0)

  val convertInputs: Seq[String] = Seq(
    "2.75", "-2.75", "1e40", "4294967296", "2147483648", "-2147483649",
    "18446744073709551616", "9223372036854775808", "0.999", "!nan",
  )

  def main(args: Array[String]): Unit =
    val out = scala.collection.mutable.ArrayBuffer.empty[String]

    for s <- parseInputs do
      val b = Big(s)
      out += s"parse\t$s\t${render(b)}"
      if !b.isNaN then out += s"plain\t$s\t${b.toPlainString}"

    for (a, b) <- binaryPairs do
      val (x, y) = (read(a), read(b))
      out += s"add\t$a\t$b\t${render(x + y)}"
      out += s"sub\t$a\t$b\t${render(x - y)}"
      out += s"mul\t$a\t$b\t${render(x * y)}"
      out += s"div\t$a\t$b\t${render(x / y)}"
      out += s"cmp\t$a\t$b\t${x.compare(y)}"

    for (a, sc) <- setScaleCases; m <- modes do
      out += s"setscale\t$a\t$sc\t$m\t${render(read(a).setScale(sc, m))}"

    for a <- unaryInputs do
      val x = read(a)
      out += s"neg\t$a\t${render(-x)}"
      out += s"abs\t$a\t${render(x.abs)}"

    for a <- sqrtInputs do out += s"sqrt\t$a\t${render(read(a).sqrt)}"
    for (a, e) <- powCases do out += s"pow\t$a\t$e\t${render(read(a) ~^ e)}"
    for (a, e) <- powfCases do out += s"powf\t$a\t$e\t${render(read(a) ~^ e)}"
    for d <- fromDoubleCases do out += s"fromdouble\t$d\t${render(Big(d))}"

    for a <- convertInputs do
      val x = read(a)
      out += s"todouble\t$a\t${x.toDouble}"
      out += s"toint\t$a\t${x.toInt}"
      out += s"tolong\t$a\t${x.toLong}"

    // round(MathContext): significant-digit rounding, carry cases included (999.9 at
    // precision 3 must come back 1.00E+3, not 1000).
    val roundInputs = Seq("999.9", "0.0009995", "-123.456", "12345", "0.5", "-0.5",
                          "9.99", "1E+10", "1234.5678", "0.00", "!nan")
    for a <- roundInputs; p <- Seq(1, 3, 5); m <- modes do
      val mc = new java.math.MathContext(p, java.math.RoundingMode.valueOf(m.toString))
      out += s"round\t$a\t$p\t$m\t${render(read(a).round(mc))}"

    // numStr and friends: BigNaN-aware column formatting. Fields are the NumFormat.
    val numStrCases: Seq[(String, NumFormat)] = Seq(
      ("0", NumFormat.Default),
      ("1.5", NumFormat.Default),
      ("-1234.567", NumFormat.Default),
      ("-0.001", NumFormat.Default),           // the -0.00 blanking quirk
      ("12345678.9", NumFormat.Default),
      ("0.125", NumFormat.Default),            // half-up at the shortest repr
      ("2.675", NumFormat.Default),            // binary 2.67499…: formatter says 2.68
      ("!nan", NumFormat.Default),
      ("1.5E9", NumFormat.Abbrev),
      ("2.5E6", NumFormat.Abbrev),
      ("999999", NumFormat.Abbrev),            // below the M threshold
      ("-0.00001", NumFormat.Percent),         // suffix defeats the blanking: -0.00%
      ("0.4567", NumFormat.Percent),
      ("0.4567", NumFormat.IntPercent),
      ("!nan", NumFormat.IntPercent),
      ("7.5", NumFormat(colWidth = 12, dec = 4, factor = 2.0)),
    )
    for (a, f) <- numStrCases do
      out += s"numstr\t$a\t${f.colWidth}\t${f.dec}\t${f.factor}\t${f.abbreviate}\t${f.suffix}\t${numStr(read(a), f)}"

    val str2numInputs = Seq("12.5", "$2,000.00", " 15% ", "-3.5", "(45)", "abc", "",
                            ".5", "+7", "1e3", "1E3", "5.5%", "$-2.50", "12,34,56", "--5",
                            "%50", "2.5E-2%")
    for s <- str2numInputs do
      out += s"str2num\t$s\t${render(str2num(s))}"

    val isNumericInputs = Seq("123", "1.5", "-2", "5K", "5k", "(3.5)", "3.5%", "1,234",
                              "12-34", "1-2-3", "abc", "", "2024-05-12", "1/2", "1E5",
                              "1.5e5", "(  42%", "999B", "999b", "12x34E+5", "5.", "- 6")
    for s <- isNumericInputs do
      out += s"isnumeric\t$s\t${isNumeric(s)}"

    // The loader path: one committed CSV, every cell rendered. Pins CsvCell-for-Big.
    val csvDir = Paths.get("test-data/big-parity/inputs")
    java.nio.file.Files.createDirectories(csvDir)
    val csv = csvDir.resolve("cells.csv")
    csv.writeLines(Seq(
      "alpha,beta,gamma",
      "1.50,$2,000.00,12.5%",
      "-0.001,3e2,0.000",
      "oops,,42",
    ))
    val m = csv.loadMatBig
    out += s"csvdim\tcells.csv\t${m.rows},${m.cols}"
    for r <- 0 until m.rows; c <- 0 until m.cols do
      out += s"csvcell\t$r,$c\t${render(m(r, c))}"

    val header = Seq(
      "# Cross-language Big (DECIMAL128 decimal) parity reference.",
      "# Generated by uni.apps.BigParityGen. Do not hand-edit.",
      "# op<TAB>operand(s)<TAB>expected; values are toString renderings, so scale is pinned.",
      "# '!nan' is the BigNaN sentinel, as operand or result.",
      s"# rows: ${out.length}",
    )
    val file = Paths.get("test-data/big-parity/scala-reference.txt")
    file.writeLines(header ++ out.toSeq)
    println(s"wrote ${out.length} rows -> ${file.posx}")
    FixtureGuard.warnIfIgnored(file.getParent)

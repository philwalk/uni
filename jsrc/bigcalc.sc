#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
//> using dep org.vastblue:uni_3:0.19.3

// One half of the cross-language demo pair; `rust/examples/bigcalc.rs` is the other.
// Fixed inputs, so the outputs are byte-identical on every machine — a portable
// end-to-end parity check of the udata surface, framed as the quasi-useful thing
// Big exists for: exact decimal money math.
//
//   scala-cli run jsrc/bigcalc.sc > scala.out
//   cargo build --manifest-path rust/Cargo.toml --example bigcalc
//   rust/target/debug/examples/bigcalc > rust.out
//   diff scala.out rust.out
//
// Exercised, both sides: parse/toPlainString, str2num on messy real-world
// strings, isNumeric, add/sub/mul/div/neg/abs/compare, sqrt, integer and
// fractional pow, setScale across the rounding modes, round(MathContext),
// numStr (Default, Abbrev, custom factor/suffix), numStrPct, the BigNaN
// sentinel (absorption through arithmetic, isBad, orBad), and the same invoice as
// a `MatB`: elementwise ops, matmul, folds, masks, and exact-decimal LU.
object Bigcalc {
  def println(s: String = ""): Unit = print(s"$s\n")

  import uni.data.*

  def main(args: Array[String]): Unit = {
    println("parsing:")
    println(s"  Big(1234.5600)       -> ${Big("1234.5600")} (plain: ${Big("1234.5600").toPlainString})")
    println(s"  Big(1.23E+4)         -> ${Big("1.23E+4")} (plain: ${Big("1.23E+4").toPlainString})")
    for s <- Seq("$1,234.56", "3.14", "12%", "1.5e3", "not-a-number") do
      println(s"  str2num(${s.padTo(12, ' ')}) -> ${str2num(s)}   isNumeric: ${isNumeric(s)}")
    println()

    val a = Big("12.34"); val b = Big("5.678")
    println(s"arithmetic on a=$a b=$b:")
    println(s"  a+b ${a + b}   a-b ${a - b}   a*b ${a * b}   a/b ${a / b}")
    println(s"  -a ${-a}   abs(-a) ${(-a).abs}   compare(a,b) ${a.compare(b)}   compare(b,a) ${b.compare(a)}")
    println(s"  sqrt(2) ${Big(2).sqrt}")
    println(s"  b pow 3 ${b ~^ 3}")
    println(s"  2 powf 1.5 ${Big(2) ~^ 1.5}")
    println()

    import scala.math.BigDecimal.RoundingMode as RM
    println("rounding 2.345 to 2 decimals, every mode:")
    val x = Big("2.345")
    for (name, mode) <- Seq("UP" -> RM.UP, "DOWN" -> RM.DOWN, "CEILING" -> RM.CEILING,
                            "FLOOR" -> RM.FLOOR, "HALF_UP" -> RM.HALF_UP,
                            "HALF_DOWN" -> RM.HALF_DOWN, "HALF_EVEN" -> RM.HALF_EVEN) do
      println(s"  ${name.padTo(9, ' ')} ${x.setScale(2, mode)}")
    println(s"  round(3 sig, HALF_EVEN): ${Big("12345.678").round(new java.math.MathContext(3, java.math.RoundingMode.HALF_EVEN))}")
    println()

    println("formatting:")
    println(s"  numStr default:   [${numStr(Big("1234.5"))}]")
    println(s"  numStr abbrev:    [${numStr(Big("12345678901.5"), NumFormat.Abbrev)}]")
    println(s"  numStr kUSD:      [${numStr(Big("1234567"), NumFormat(colWidth = 10, dec = 1, factor = 0.001, suffix = " kUSD"))}]")
    println(s"  numStrPct(0.1234) [${numStrPct(Big("0.1234"))}]")
    println()

    println("the BigNaN sentinel:")
    // sqrt of a negative is BigNaN in BOTH languages as of 0.16.0 -- Scala used to
    // throw here, which is the divergence this demo surfaced on its first run.
    val nan = Big(-1).sqrt
    println(s"  2 pow -2 (negative exponent): isBad ${isBad(Big(2) ~^ -2)}")
    println(s"  sqrt(-1): isBad ${isBad(nan)}   renders as [${nan}]")
    println(s"  nan + 5 stays bad: ${isBad(nan + Big(5))}")
    println(s"  orBad(None) is bad: ${isBad(orBad(None))}   orBad(Some(a)): ${orBad(Some(a))}")
    println(s"  numStr(nan): [${numStr(nan)}]")
    println()

    // the quasi-useful part: an exact-decimal invoice, no float in sight
    println("invoice (exact decimal money math):")
    val items = Seq(("widget", Big("19.99"), 3), ("gizmo", Big("4.15"), 7), ("doohickey", Big("102.50"), 1))
    val subtotal = items.foldLeft(Big(0))((acc, it) => acc + it._2 * Big(it._3))
    val taxRate  = Big("0.075")
    val tax      = (subtotal * taxRate).setScale(2, RM.HALF_EVEN)
    val total    = subtotal + tax
    for (name, price, qty) <- items do
      val line = price * Big(qty)
      println(s"  ${name.padTo(10, ' ')} ${qty} x ${numStr(price)} =${numStr(line)}   (${numStrPct(line / subtotal)} of subtotal)")
    println(s"  subtotal ${numStr(subtotal)}   tax(7.5%) ${numStr(tax)}   total ${numStr(total)}")
    println()

    // the same invoice as a Mat[Big]: every cell an exact decimal, every fold sequential
    println("invoice as a MatB (Mat[Big]):")
    def cells(m: MatB): String = m.toArray.map(_.toString).mkString(", ")
    val prices = MatB.col(Big("19.99"), Big("4.15"), Big("102.50"))
    val qtys   = MatB.col(Big(3), Big(7), Big(1))
    val lines  = prices * qtys
    println(s"  lines      [${cells(lines)}]")
    println(s"  qtys.T *@ prices = [${cells(qtys.T *@ prices)}]   lines.sum = ${lines.sum}")
    println(s"  shares     [${cells(lines / lines.sum)}]")
    println(s"  taxed      [${cells((lines * taxRate).map(_.setScale(2, RM.HALF_EVEN)))}]")
    println(s"  mean ${lines.mean}   max ${lines.max} at ${lines.argmax}   std ${lines.std}")
    val ledger = Mat.hstack(prices, qtys, lines)
    println(s"  ledger 3x3 sum(0) [${cells(ledger.sum(0))}]   sum(1) [${cells(ledger.sum(1))}]")
    // BigNaN travels through the matrix as it does through the scalar
    val withNaN = MatB.col(Big("1.50"), BigNaN, Big("3"))
    println(s"  withNaN sum ${withNaN.sum == BigNaN}   min ${withNaN.min}   max isBad ${isBad(withNaN.max)}   hasNaN [${withNaN.hasNaN.toArray.mkString(", ")}]")
    println(s"  gt(1) [${withNaN.gt(Big(1)).toArray.mkString(", ")}]   sorted [${cells(withNaN.sort()).replace(nan.toString, "NaN")}]")
    println(s"  csv: ${withNaN.T.toArray.map(x => if x == BigNaN then "N/A" else x.toString).mkString(",")}")
    // exact-decimal linear algebra: LU with no float anywhere
    val A = MatB((Big(2), Big(1)), (Big(1), Big(3)))
    println(s"  A.inverse [${cells(A.inverse)}]   det ${A.determinant}   solve [${cells(A.solve(MatB.col(Big(3), Big(4))))}]")
  }
}

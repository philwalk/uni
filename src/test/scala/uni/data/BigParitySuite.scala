package uni.data

import munit.FunSuite
import uni.*
import uni.data.Big.Big

/** Checks `Big` against `test-data/big-parity/scala-reference.txt`.
  *
  * On the Scala side this is a regression pin -- the reference was generated from this
  * implementation -- while `rust/tests/big_parity.rs` reads the identical file against the
  * hand-rolled decimal, which is what makes `java.math.BigDecimal` the transitive oracle.
  *
  * Regenerate with `sbt "runMain uni.apps.BigParityGen"`, only when the change in answers
  * is intended.
  */
class BigParitySuite extends FunSuite:

  private val fixture = Paths.get("test-data/big-parity/scala-reference.txt")

  private def render(b: Big): String = if b.isNaN then "!nan" else b.toString
  private def read(s: String): Big = if s == "!nan" then BigNaN else Big(s)

  private val modeByName = Map(
    "UP" -> RoundingMode.UP, "DOWN" -> RoundingMode.DOWN,
    "CEILING" -> RoundingMode.CEILING, "FLOOR" -> RoundingMode.FLOOR,
    "HALF_UP" -> RoundingMode.HALF_UP, "HALF_DOWN" -> RoundingMode.HALF_DOWN,
    "HALF_EVEN" -> RoundingMode.HALF_EVEN,
  )

  test("every fixture row reproduces") {
    require(fixture.isFile, s"missing fixture ${fixture.posx}; run BigParityGen")
    val rows = fixture.lines.filterNot(l => l.isEmpty || l.startsWith("#"))
    require(rows.length > 300, s"suspiciously small fixture: ${rows.length} rows")

    lazy val csvMat = Paths.get("test-data/big-parity/inputs/cells.csv").loadMatBig

    val failures = rows.flatMap { row =>
      def diff(label: String, got: String, want: String): Option[String] =
        Option.when(got != want)(s"$label: got $got, want $want")
      row.split('\t') match
        case Array("parse", input, want) => diff(s"parse '$input'", render(Big(input)), want)
        case Array("parse", want)        => diff("parse ''", render(Big("")), want)
        case Array("plain", input, want) => diff(s"plain '$input'", Big(input).toPlainString, want)
        case Array(op @ ("add" | "sub" | "mul" | "div"), a, b, want) =>
          val (x, y) = (read(a), read(b))
          val got = op match
            case "add" => x + y
            case "sub" => x - y
            case "mul" => x * y
            case _     => x / y
          diff(s"$op $a $b", render(got), want)
        case Array("cmp", a, b, want) => diff(s"cmp $a $b", read(a).compare(read(b)).toString, want)
        case Array("setscale", a, sc, mode, want) =>
          diff(s"setscale $a $sc $mode", render(read(a).setScale(sc.toInt, modeByName(mode))), want)
        case Array("neg", a, want)  => diff(s"neg $a", render(-read(a)), want)
        case Array("abs", a, want)  => diff(s"abs $a", render(read(a).abs), want)
        case Array("sqrt", a, want) => diff(s"sqrt $a", render(read(a).sqrt), want)
        case Array("pow", a, e, want)  => diff(s"pow $a $e", render(read(a) ~^ e.toInt), want)
        case Array("powf", a, e, want) => diff(s"powf $a $e", render(read(a) ~^ e.toDouble), want)
        case Array("fromdouble", d, want) => diff(s"fromdouble $d", render(Big(d.toDouble)), want)
        case Array("todouble", a, want) => diff(s"todouble $a", read(a).toDouble.toString, want)
        case Array("toint", a, want)  => diff(s"toint $a", read(a).toInt.toString, want)
        case Array("tolong", a, want) => diff(s"tolong $a", read(a).toLong.toString, want)
        case Array("csvdim", _, want) => diff("csvdim", s"${csvMat.rows},${csvMat.cols}", want)
        case Array("csvcell", rc, want) =>
          val Array(r, c) = rc.split(',').map(_.toInt)
          diff(s"csvcell $rc", render(csvMat(r, c)), want)
        case other => Some(s"unparseable fixture row: $row")
    }
    assertEquals(failures, Seq.empty[String], s"${failures.length} divergence(s)")
  }

#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
//> using dep org.vastblue:uni_3:0.21.0

// One half of the cross-language demo pair; `rust/examples/forecast.rs` is the other.
// Both generate the same seeded dataset, write it as CSV, read it back with
// loadMatBig, and run the 3PRF closed forms — the first cross-language t3prf
// DEMO (the benchmark twins measure speed; this one shows the API):
//
//   scala-cli run jsrc/forecast.sc > scala.out
//   cargo build --manifest-path rust/Cargo.toml --example forecast
//   rust/target/debug/examples/forecast > rust.out
//   diff scala.out rust.out
//
// Exercised, both sides: NumPyRNG randn (bit-identical normals), CSV writing via
// Big's Double rendering (parity-pinned, so the files are byte-identical too),
// Path.loadMatBig, tprfClosedForm, plsClosedForm, pls1Fit (which must agree with
// plsClosedForm), and forecast3prf. Every float prints through Big + numStr, so
// no platform float-formatting can drift; outputs are byte-identical per machine.
object Forecast {
  def println(s: String = ""): Unit = print(s"$s\n")

  import uni.*
  import uni.data.*
  import uni.stats.Tprf3.*

  val T = 40; val N = 5; val L = 1
  def num(d: Double): String = numStr(Big(d), NumFormat(colWidth = 1, dec = 6)).trim

  def main(args: Array[String]): Unit = {
    val csvPath = if args.nonEmpty then args(0) else "target/forecast-demo.csv"

    // Deterministic data, drawn in a fixed order so both languages see the same
    // doubles: X row-major, then the noise, then the proxy Z.
    val rng = new NumPyRNG(7L)
    val x   = Array.fill(T, N)(rng.randn())
    val eps = Array.fill(T)(rng.randn())
    val z   = Array.fill(T, L)(rng.randn())
    val w   = Array(0.6, 0.3, -0.2, 0.1, 0.05)
    val y   = Array.tabulate(T)(t => (0 until N).map(i => w(i) * x(t)(i)).sum + 0.25 * eps(t))

    // CSV via Big's rendering of each double -- Double.toString semantics in both
    // languages, pinned by the big-parity fixture, so the files are byte-identical.
    val out = csvPath.asPath
    out.withWriter() { wtr =>
      wtr.print(("y" +: (1 to N).map(i => s"x$i")).mkString(",") + "\n")
      for t <- 0 until T do
        wtr.print((Big(y(t)) +: (0 until N).map(i => Big(x(t)(i)))).mkString(",") + "\n")
    }

    val m = out.loadMatBig
    println(s"loadMatBig: ${m.rows} x ${m.cols} from ${out.last}")
    println(s"  cell(0,0)  ${m(0, 0)}   round-trip ok: ${m(0, 0).toDouble == y(0)}")
    println(s"  cell(39,5) ${m(39, 5)}   round-trip ok: ${m(39, 5).toDouble == x(39)(4)}")
    println()

    // back to doubles, THROUGH the loaded Big matrix: the loader is in the loop
    val yM = Array.tabulate(T)(t => Array(m(t, 0).toDouble)).toMat
    val xM = Array.tabulate(T)(t => Array.tabulate(N)(i => m(t, i + 1).toDouble)).toMat
    val zM = z.toMat

    val tp = tprfClosedForm(yM, xM, zM)
    println(s"tprfClosedForm: rSquared ${num(tp.rSquared)}")
    println(s"  forecasts: first ${num(tp.forecasts(0, 0))}   last ${num(tp.forecasts(T - 1, 0))}")

    val pls = plsClosedForm(yM, xM)
    println(s"plsClosedForm:  rSquared ${num(pls.rSquared)}")
    println(s"  beta: intercept ${num(pls.beta(0, 0))}   slope ${num(pls.beta(1, 0))}")

    val p1 = pls1Fit(Array.tabulate(T)(t => Array.tabulate(N)(i => m(t, i + 1).toDouble)),
                     Array.tabulate(T)(t => m(t, 0).toDouble))
    println(s"pls1Fit:        rSquared ${num(p1.rSquared)}   agrees with plsClosedForm: ${p1.rSquared == pls.rSquared}")

    val fc = forecast3prf(yM, xM, Right(zM))
    println(s"forecast3prf(IS Full): first ${num(fc(0, 0))}   last ${num(fc(T - 1, 0))}")

    // the out-of-sample procedures: early entries are untrained, so only the
    // final forecast is printed
    val cv  = forecast3prf(yM, xM, Right(zM), procedure = "OOS Cross Val")
    val rec = forecast3prf(yM, xM, Right(zM), procedure = "OOS Recursive")
    println(s"forecast3prf(OOS Cross Val):  last ${num(cv(T - 1, 0))}")
    println(s"forecast3prf(OOS Recursive): last ${num(rec(T - 1, 0))}")
  }
}

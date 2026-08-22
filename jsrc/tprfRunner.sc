#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
//> using dep org.vastblue:uni_3:0.19.2

// One half of the cross-language demo pair; `rust/examples/tprf_runner.rs` is the other.
// Both run `TprfRunner.data_generator` — the four recurrences, `hstack`, the matmul with
// the factor loadings, the noise blend — from the same seed and print the same panel:
//
//   scala-cli run jsrc/tprfRunner.sc > scala.out
//   cargo run --release --manifest-path rust/Cargo.toml --example tprf_runner > rust.out
//   diff scala.out rust.out
//
// Two things make it byte-identical rather than merely close. The recurrences write
// through `update` here and through `MatMut` there, and every value they produce is a
// pinned reduction. And the factor product is `matmulPure` — the pure tiled loop, a
// sequential k-sum from 0.0 per cell, the same on both sides and on every machine.
// The default `*@` is BLAS (`-Duni.mat.blas=os-best`), whose last ulps depend on the
// library and the CPU; through it the diff would not be empty. Rust's `matmul` is the
// pinned loop by default, so the Rust half needs no such choice.
//
// Floats print through `%+.10f`, which the Rust side reproduces with `java_format_f`
// (half-up on the exact decimal expansion, as the JVM does).
object TprfRunner {
  def println(s: String = ""): Unit = print(s"$s\n")

  import uni.*
  import uni.data.*

  /** `TprfRunner.popStd`, formula for formula: `sqrt(sum((m - mu)^2) / n)`, `mu = sum/n`.
   *  The square is `x * x` rather than `~^ 2.0`, so both twins compute `fl(x*x)`. */
  def popStd(m: MatD): Double =
    val n  = m.rows * m.cols
    val mu = m.sum / n
    val d  = m - mu
    math.sqrt((d *:* d).sum / n)

  def f10(v: Double): String = f"$v%+.10f"

  /** `TprfRunner.data_generator` for `pf = pg = a = 0.9`, `d = 0`, `strength = 1`,
   *  without the `non_pervasive` branch. Returns the panel's six matrices. */
  def generate(T: Int, N: Int, relevant: Int, irr: Int): (MatD, MatD, MatD, MatD, MatD, MatD) =
    val (pf, pg, a, strength) = (0.9, 0.9, 0.9, 1.0)

    // Draw order is load-bearing: v, then the loadings, then f's seed row, then the
    // noise batches, in exactly this sequence.
    val v               = MatD.randn(T, N)
    val factor_loadings = MatD.randn(relevant + irr, N)

    val f = MatD.zeros(T, relevant)
    f(0, ::) = MatD.randn(relevant, 1)
    val fNoise = MatD.randn(T - 1, relevant)
    for t <- 1 until T do
      f(t, ::) = f(t - 1, ::) * pf + fNoise(t - 1, ::)
    val fStd = popStd(f)

    val g = MatD.zeros(T, irr)
    g(0, ::) = MatD.randn(irr, 1)
    val g_err = MatD.zeros(T, irr)
    for i <- 0 until math.min(4, irr) do
      g_err(::, i) = MatD.randn(T, 1)
    val g_var = Array(1.25, 1.75, 2.25, 2.75)
    for j <- 0 until irr do
      for t <- 1 until T do
        g(t, j) = g(t - 1, j) * pg + g_err(t, j)
      val gcolj = g(::, j)
      val scale = fStd * math.sqrt(g_var(math.min(j, g_var.length - 1))) / popStd(gcolj)
      g(::, j) = gcolj * scale

    val y      = MatD.zeros(T + 1, 1)
    val yNoise = MatD.randn(T, 1)
    for t <- 1 to T do
      y(t, 0) = f(t - 1, ::).mean + fStd * yNoise(t - 1, 0)

    val eta_tilda = v // d == 0: no cyclic neighbour blend
    val eta       = MatD.zeros(T, N)
    eta(0, ::) = MatD.randn(N, 1)
    for t <- 1 until T do
      eta(t, ::) = eta(t - 1, ::) * a + eta_tilda(t, ::)

    val factors  = MatD.hstack(f, g)
    val X1       = factors.matmulPure(factor_loadings)
    val etaNorm  = eta / popStd(eta)
    val constant = popStd(X1) * math.sqrt(strength)
    val X        = X1 + etaNorm * constant
    (f, g, eta, X1, X, y(1 until y.rows, ::))

  def main(args: Array[String]): Unit =
    val (t, n, relevant, irr) = (12, 6, 3, 4)
    MatD.setSeed(0)
    val (f, g, eta, x1, x, y) = generate(t, n, relevant, irr)

    println("── TprfRunner data_generator ──────────────────────────────────────────")
    println(s"T=$t N=$n relevant=$relevant irrelevant=$irr strength=1.0  seed=0")
    println()
    for (name, m) <- Seq(("f", f), ("g", g), ("eta", eta), ("X1", x1), ("X", x), ("y", y)) do
      println(f"$name%-3s ${m.rows}%2dx${m.cols}%-2d  first=${f10(m(0, 0))}  last=${f10(m(m.rows - 1, m.cols - 1))}  mean=${f10(m.mean)}  std=${f10(popStd(m))}")
    println()
    println("X, every row:")
    for r <- 0 until x.rows do
      println("  " + (0 until x.cols).map(c => f10(x(r, c))).mkString(" "))
    println("y: " + (0 until y.rows).map(r => f10(y(r, 0))).mkString(" "))
}

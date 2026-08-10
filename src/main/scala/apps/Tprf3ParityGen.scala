package uni.apps

import uni.*
import uni.data.*
import uni.stats.Tprf3

/**
 * Regenerates the cross-language parity fixtures in `test-data/tprf3-parity/`:
 * the input matrices and the golden reference of Tprf3 results.
 *
 * Those fixtures are consumed by two tests that must agree with each other:
 *   - uni.stats.Tprf3ParitySuite   (Scala, src/test)
 *   - rust/tests/scala_parity.rs   (Rust)
 *
 * Neither test needs the other language installed, because both compare
 * against the committed reference rather than against each other.
 *
 * Run ONLY when the reference is meant to move — regenerating it rewrites the
 * very values the tests check, so an unintended run would mask a regression
 * instead of catching it. Review the diff before keeping it.
 *
 * Run:  sbt "runMain uni.apps.Tprf3ParityGen"
 */
object Tprf3ParityGen {
  def println(s: String = ""): Unit = print(s"$s\n")

  /** Sizes chosen to cover both proxy counts and a range of T/N ratios while
   *  keeping the committed fixtures small. */
  val cases: Seq[(Int, Int, Int)] = Seq((100, 10, 2), (140, 12, 3), (60, 25, 2))

  /** Deterministic pseudo-random value in [-1, 1) from an index alone — a pure
   *  function, so the fixtures are reproducible without carrying generator
   *  state and without depending on any library's RNG. */
  def rnd(i: Long): Double =
    var h = i * 6364136223846793005L + 1442695040888963407L
    h ^= (h >>> 33); h *= 0xff51afd7ed558ccdL
    h ^= (h >>> 33); h *= 0xc4ceb9fe1a85ec53L
    h ^= (h >>> 33)
    ((h >>> 11).toDouble / 9007199254740992.0) * 2.0 - 1.0

  def mk(rows: Int, cols: Int, salt: Long): MatD =
    val m = MatD.zeros(rows, cols)
    for i <- 0 until rows; j <- 0 until cols do
      m(i, j) = rnd(salt * 1000003L + i.toLong * 131L + j.toLong)
    m

  /** 17 significant digits, so every value round-trips through text exactly. */
  def writeCsv(path: String, m: MatD): Unit =
    val sb = StringBuilder()
    for i <- 0 until m.rows do
      sb ++= (0 until m.cols).map(j => f"${m(i, j)}%.17e").mkString(",")
      sb ++= "\n"
    java.nio.file.Files.writeString(path.asPath, sb.toString)

  def append(sb: StringBuilder, tag: String, r: Tprf3.Tprf3Result): Unit =
    sb ++= f"$tag r2 ${r.rSquared}%.17e\n"
    sb ++= f"$tag enc ${r.encnew}%.17e\n"
    for i <- 0 until r.forecasts.rows do
      sb ++= f"$tag f$i ${r.forecasts(i, 0)}%.17e\n"
    for i <- 0 until r.rollfore.rows do
      sb ++= f"$tag roll$i ${r.rollfore(i, 0)}%.17e\n"

  /** Closed-form rows carry no enc/rollfore: tprfClosedForm never sets them. */
  def appendClosed(sb: StringBuilder, tag: String, r: Tprf3.Tprf3Result): Unit =
    sb ++= f"$tag r2 ${r.rSquared}%.17e\n"
    for i <- 0 until r.forecasts.rows do
      sb ++= f"$tag f$i ${r.forecasts(i, 0)}%.17e\n"

  /** PLS rows also pin the pass-3 coefficients and one raw-row prediction, so
   *  the predict path (internal re-normalisation) is pinned, not just the fit. */
  def appendPls(sb: StringBuilder, tag: String, m: Tprf3.Pls3prfModel, x: MatD): Unit =
    sb ++= f"$tag r2 ${m.rSquared}%.17e\n"
    sb ++= f"$tag b0 ${m.beta(0, 0)}%.17e\n"
    sb ++= f"$tag b1 ${m.beta(1, 0)}%.17e\n"
    for i <- 0 until m.forecasts.rows do
      sb ++= f"$tag f$i ${m.forecasts(i, 0)}%.17e\n"
    val row0 = Array.tabulate(x.cols)(j => x(0, j))
    sb ++= f"$tag pred0 ${m.predict(row0)}%.17e\n"

  def main(args: Array[String]): Unit =
    val root = sys.props.getOrElse("user.dir", ".")
    val dir  = s"$root/test-data/tprf3-parity"
    java.nio.file.Files.createDirectories(dir.asPath)

    val sb = StringBuilder()
    sb ++= "# Tprf3 cross-language parity reference — regenerate with\n"
    sb ++= "#   sbt \"runMain uni.apps.Tprf3ParityGen\"\n"
    for (t, n, l) <- cases do
      val tag = s"T${t}N${n}L${l}"
      val X = mk(t, n, 1L); val y = mk(t, 1, 2L); val Z = mk(t, l, 3L)
      writeCsv(s"$dir/${tag}_X.csv", X)
      writeCsv(s"$dir/${tag}_y.csv", y)
      writeCsv(s"$dir/${tag}_Z.csv", Z)

      append(sb, s"$tag/isfull",
        Tprf3.estimate3prf(y, X, Right(Z), procedure = "IS Full"))
      append(sb, s"$tag/oosrec",
        Tprf3.estimate3prf(y, X, Right(Z), procedure = "OOS Recursive", mintrain = (t / 2, 0)))
      append(sb, s"$tag/ooscv01",
        Tprf3.estimate3prf(y, X, Right(Z), procedure = "OOS Cross Val", window = (0, 1)))
      append(sb, s"$tag/ooscv23",
        Tprf3.estimate3prf(y, X, Right(Z), procedure = "OOS Cross Val", window = (2, 3)))
      appendClosed(sb, s"$tag/closed", Tprf3.tprfClosedForm(y, X, Z))
      appendPls(sb, s"$tag/pls", Tprf3.plsClosedForm(y, X), X)
      println(s"  $tag: wrote inputs + 4 procedures + closed + pls")

    java.nio.file.Files.writeString(s"$dir/scala-reference.txt".asPath, sb.toString)
    println(s"wrote $dir/scala-reference.txt")
    FixtureGuard.warnIfIgnored(dir.asPath)
}

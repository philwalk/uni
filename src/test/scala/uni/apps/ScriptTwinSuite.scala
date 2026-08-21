package uni.apps

import munit.FunSuite
import uni.*

/**
 * Guards the jsrc-script / apps-object twins whose synchrony is a CONTRACT, not a convenience.
 *
 * The two forms differ only in a two-line header toggle: the script has a live shebang and a
 * commented `package uni.apps`; the packaged object comments the shebang and uncomments the
 * package. Everything below that must be byte-identical, because the script is the parity twin
 * of a Rust program and the packaged object is what a library consumer runs — a drift between
 * them recreates the stale-fork failure documented in `docs/MarketSimUpgradePlan.md` (W0)
 * *inside* the repo, where a consumer would attribute the disagreement to the Rust port.
 *
 * Only pairs listed here are guarded. Several other jsrc/apps pairs have drifted historically;
 * they are dev tools, and enrolling them retroactively would fail the build over drift nobody
 * has promised to prevent. Enroll a pair when its packaged form becomes a product.
 */
class ScriptTwinSuite extends FunSuite:

  /** (script, packaged object) pairs under contract. */
  val guarded = Seq(
    ("jsrc/marketSim.sc", "src/main/scala/apps/MarketSim.scala"),
  )

  /** The packaged form, mapped back to what the script form must be. */
  def scriptForm(packaged: Vector[String]): Vector[String] =
    packaged.zipWithIndex.map {
      case (line, 0) if line.startsWith("//#!") => line.drop(2)
      case (line, _) if line == "package uni.apps" => "//" + line
      case (line, _) => line
    }

  for (script, packaged) <- guarded do
    test(s"$packaged is the header-twin of $script") {
      val s = script.asPath
      val p = packaged.asPath
      assume(s.exists && p.exists, "twin files not present in this tree (source tarball?)")
      val want = scriptForm(p.lines.toVector)
      val got  = s.lines.toVector
      val firstDiff = want.zip(got).indexWhere((a, b) => a != b)
      assertEquals(got.size, want.size,
        s"$script and $packaged differ in length — regenerate the packaged twin from the script")
      assert(firstDiff < 0,
        s"$script:${firstDiff + 1} differs from its packaged twin — regenerate " +
        s"$packaged from the script (comment the shebang, uncomment `package uni.apps`)")
    }

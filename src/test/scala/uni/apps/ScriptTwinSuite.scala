package uni.apps

import munit.FunSuite
import uni.*

/**
 * Guards the jsrc-script / apps-object relationship for scripts whose form is a CONTRACT.
 *
 * Two forms of contract are guarded, and marketSim moved from the first to the second:
 *
 *  - `twins` — the script and the packaged object are ONE source in two spellings, differing only
 *    in a two-line header toggle (live shebang + commented `package uni.apps` on the script side,
 *    the reverse on the packaged side). Everything below must be byte-identical, or the stale-fork
 *    failure documented in `docs/MarketSimUpgradePlan.md` (W0) reappears *inside* the repo.
 *
 *  - `launchers` — the script is a thin wrapper that dispatches into the packaged object, which is
 *    the only copy of the code. This is the STRONGER arrangement: a second copy cannot drift if it
 *    does not exist. `marketSim.sc` became a launcher so that the version its sidecar stamps
 *    (`uni.BuildInfo.version`, describing the jar) and the code that runs come from one artifact.
 *    The test below asserts it has NOT been restored into a copy — which is the one way the
 *    guarantee can be lost, and it is a single careless file overwrite away.
 *
 * Only entries listed here are guarded. Several other jsrc/apps pairs have drifted historically;
 * they are dev tools, and enrolling them retroactively would fail the build over drift nobody has
 * promised to prevent. Enroll a pair when its packaged form becomes a product.
 */
class ScriptTwinSuite extends FunSuite:

  /** (script, packaged object) pairs that must be byte-identical modulo the header toggle. */
  val twins = Seq.empty[(String, String)]

  /** (script, packaged object) pairs where the script only dispatches into the object. */
  val launchers = Seq(
    ("jsrc/marketSim.sc", "src/main/scala/apps/MarketSim.scala", "uni.apps.MarketSim.main(args)"),
  )

  /** A launcher is small by definition; the object it dispatches to is not. The bound is loose on
    * purpose — it is here to catch a restored COPY (thousands of lines), not to police comments. */
  val MaxLauncherLines = 120

  /** The packaged form, mapped back to what the script form must be. */
  def scriptForm(packaged: Vector[String]): Vector[String] =
    packaged.zipWithIndex.map {
      case (line, 0) if line.startsWith("//#!") => line.drop(2)
      case (line, _) if line == "package uni.apps" => "//" + line
      case (line, _) => line
    }

  for (script, packaged) <- twins do
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

  for (script, packaged, dispatch) <- launchers do
    test(s"$script is a launcher into $packaged, not a copy of it") {
      val s = script.asPath
      val p = packaged.asPath
      assume(s.exists && p.exists, "files not present in this tree (source tarball?)")
      val lines = s.lines.toVector
      assert(lines.exists(_.trim == dispatch),
        s"$script no longer dispatches to `$dispatch` — a launcher that does not launch " +
        s"silently stops exercising $packaged")
      assert(lines.size <= MaxLauncherLines,
        s"$script has ${lines.size} lines: it looks like $packaged was copied back over it. " +
        s"That reinstates a second copy of the model, and with it a sidecar whose stamped " +
        s"version can disagree with the code that produced it. Restore the launcher.")
    }

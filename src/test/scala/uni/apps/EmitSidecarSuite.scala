package uni.apps

import munit.FunSuite
import uni.*

/**
 * The `-emit` sidecar declares a schema number, and a consumer is told to read it first — a missing
 * `version` means schema 1, not a malformed file (`docs/MarketSimWorlds.md`). That instruction is
 * only safe while the declared number and the emitted shape agree.
 *
 * Nothing in the writer keeps them in step: the schema is one integer and the shape is a hand-built
 * list of lines. This suite closes that by comparing what is actually emitted against
 * `MarketSim.EmitSidecarKeys`, which the writer never reads. Adding, removing or renaming a key
 * therefore fails HERE — at the moment the discrepancy is created, beside the schema number that
 * then has to be decided about — instead of in a consumer that trusted the declaration.
 *
 * It cannot force a bump, and does not pretend to: a shape change with the contract updated and the
 * number left alone still passes. What it removes is the silent case.
 */
class EmitSidecarSuite extends FunSuite:

  /** A top-level key of the sidecar object: exactly two spaces of indent, then a quoted name.
    * Nested blocks (`path`, `world`, `gate`) indent by four, so this cannot reach into them. */
  val TopLevelKey = """^  "([^"]+)":.*""".r

  /** Emit one real path and hand the sidecar's lines to the body, then clean up.
    *
    * The smallest run that still produces a real sidecar: two years, and the gate verdict measured
    * on the single path we simulate (the `-emitgate 0` reading), so the suite costs one short
    * simulation rather than a 200-path ensemble. */
  def withSidecar(body: (Vector[String], String) => Unit): Unit =
    val dir   = java.nio.file.Files.createTempDirectory("emitSidecar")
    val tsv   = s"${dir.posx}/emitSidecarSuite.tsv"
    val json  = MarketSim.sidecarName(tsv)
    try
      val years = 2
      val seed  = 20260825L
      val w     = MarketSim.Defaults
      val p     = MarketSim.simulate(w, years, seed)
      val st    = MarketSim.measure(Vector(p), years)
      MarketSim.writeEmitted(tsv, p, 0, w, years, seed, "", st, 1)
      body(json.asPath.lines.toVector, json)
    finally
      tsv.asPath.delete()
      json.asPath.delete()
      dir.delete()

  test("the emitted sidecar declares MarketSim.EmitSchema") {
    withSidecar { (lines, json) =>
      val declared = lines.collectFirst { case s"""  "schema": ${n},""" => n.trim.toInt }
      assertEquals(declared, Some(MarketSim.EmitSchema),
        s"$json declares a schema that is not MarketSim.EmitSchema — the writer and the constant " +
        "have come apart")
    }
  }

  test("the emitted sidecar carries exactly the keys the schema promises") {
    withSidecar { (lines, json) =>
      val got  = lines.collect { case TopLevelKey(k) => k }
      val want = MarketSim.EmitSidecarKeys
      assertEquals(got, want,
        s"$json's top-level keys differ from MarketSim.EmitSidecarKeys.\n" +
        s"  emitted but not declared: ${got.diff(want).mkString("[", ", ", "]")}\n" +
        s"  declared but not emitted: ${want.diff(got).mkString("[", ", ", "]")}\n" +
        "The sidecar's SHAPE changed. Update EmitSidecarKeys, and decide in the same edit whether " +
        s"EmitSchema (now ${MarketSim.EmitSchema}) must be bumped — a reader that pins the schema " +
        "is relying on that number to mean this shape.")
    }
  }

  test("the Rust twin declares the same schema") {
    val rs = "rust/examples/market_sim.rs".asPath
    assume(rs.exists, "rust twin not present in this tree (source tarball?)")
    val declared = rs.lines.collectFirst { case s"const EMIT_SCHEMA: u32 = ${n};" => n.trim.toInt }
    assertEquals(declared, Some(MarketSim.EmitSchema),
      "EMIT_SCHEMA in the Rust twin differs from MarketSim.EmitSchema. The two write the same " +
      "sidecar, so a consumer reading the schema would get a different answer depending on which " +
      "twin produced the file.")
  }

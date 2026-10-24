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

  test("a sidecar is named beside its TSV, and the null device is its own") {
    assertEquals(MarketSim.sidecarName("out/run.tsv"), "out/run.json")
    assertEquals(MarketSim.sidecarName("out.d/run"), "out.d/run.json")
    // an -emit run made for the verdict alone leaves no nul.json behind
    assertEquals(MarketSim.sidecarName("nul"), "nul")
    assertEquals(MarketSim.sidecarName("C:/tmp/NUL"), "C:/tmp/NUL")
    assertEquals(MarketSim.sidecarName("/dev/null"), "/dev/null")
    assertEquals(MarketSim.sidecarName("annul"), "annul.json")
  }

  /** A top-level key of the sidecar object: exactly two spaces of indent, then a quoted name.
    * Nested blocks (`path`, `world`, `gate`) indent by four, so this cannot reach into them. */
  val TopLevelKey = """^  "([^"]+)":.*""".r

  /** Emit one real path and hand the sidecar's lines to the body, then clean up.
    *
    * The smallest run that still produces a real sidecar: two years, and the gate verdict measured
    * on the single path we simulate (the `-emitgate 0` reading), so the suite costs one short
    * simulation rather than a 200-path ensemble. */
  /** The verdict measured on the one path written (the `-emitgate 0` reading). */
  def onePathVerdict(w: MarketSim.World, p: MarketSim.Path, st: MarketSim.WorldStats,
                     rows: Vector[MarketSim.FidelityRow]): MarketSim.Verdict =
    MarketSim.Verdict(1, 2, w, st, rows, Vector.empty, p)

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
      val rows  = MarketSim.fidelityRows(MarketSim.SP500Anchors, st, None, years, 1, seed, w)
      MarketSim.writeEmitted(MarketSim.SP500Anchors, tsv, p, 0, MarketSim.EmitSpec(w, years, seed, ""),
        onePathVerdict(w, p, st, rows))
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

  test("the emitted sidecar carries the digest of the world it records") {
    withSidecar { (lines, _) =>
      val got = lines.collectFirst { case s"""  "worldDigest": "${d}",""" => d }
      assertEquals(got, Some(MarketSim.worldDigest(MarketSim.Defaults)))
    }
  }

  test("the Rust twin declares the same schema") {
    val rs = "rust/src/market_sim.rs".asPath
    assume(rs.exists, "rust twin not present in this tree (source tarball?)")
    val declared = rs.lines.collectFirst { case s"const EMIT_SCHEMA: u32 = ${n};" => n.trim.toInt }
    assertEquals(declared, Some(MarketSim.EmitSchema),
      "EMIT_SCHEMA in the Rust twin differs from MarketSim.EmitSchema. The two write the same " +
      "sidecar, so a consumer reading the schema would get a different answer depending on which " +
      "twin produced the file.")
  }

  test("an f32 chunk holds the table's cells, path-major, and its sidecar names the chunk") {
    val (years, seed) = (2, 20260825L)
    val w    = MarketSim.Defaults.copy(rangeScale = 0.63)
    val p    = MarketSim.simulate(w, years, seed)
    val st   = MarketSim.measure(Vector(p), years)
    val rows = MarketSim.fidelityRows(MarketSim.SP500Anchors, st, None, years, 1, seed, w)
    val dir  = java.nio.file.Files.createTempDirectory("emitF32")
    val file = s"${dir.posx}/chunk.f32"
    val side = MarketSim.sidecarName(file)
    try
      val written = MarketSim.writeF32Chunk(MarketSim.SP500Anchors, file, MarketSim.EmitSpec(w, years, seed, ""),
        (3, 2), Vector("logLow", "price", "logSat"), onePathVerdict(w, p, st, rows))
      val Right((cols, n)) = written: @unchecked
      val buf = java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(file.asPath))
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
      assertEquals((cols, buf.capacity), (2, 2 * 2 * n * 4))
      for (k, j) <- Vector(3, 4).zipWithIndex do
        val table = MarketSim.emitTable(MarketSim.simulate(w, years, seed + k * 7919L))
        for (name, c) <- Vector("logLow", "price").zipWithIndex do
          val col = table.find(_.name == name).get
          for i <- 0 until n do
            assert(buf.getFloat(((j * 2 + c) * n + i) * 4) == col.values(i).toFloat, s"path $k $name session $i")
      val lines = side.asPath.lines.toVector
      for line <- Vector(
          """  "columns": ["logLow", "price"],""",
          """  "format": "f32le",""",
          """  "columnsAbsent": ["logSat"],""",
          """    "first": 3,""",
          """    "count": 2,""",
          """    "gradedSeries": ["price", "logLow"],""") do
        assert(lines.contains(line), s"sidecar lacks [$line]")
      assert(lines.exists(_.contains(""""index": 4, "rows": [""")), "each path's episodes")
      assertEquals(lines.collect { case TopLevelKey(k) => k }, MarketSim.F32SidecarKeys)
    finally
      file.asPath.delete()
      side.asPath.delete()
      dir.delete()
  }

  test("the emit table follows the column lists") {
    val w = MarketSim.namedWorld("0.24.6-nasdaq-basket").get._1.copy(macroNull = 2, sectors = 3)
    val p = MarketSim.simulate(w, 2, 20260825L)
    val expect = MarketSim.EmitColumns.tail ++
      (if p.sat.isEmpty then Vector() else Vector("logSat")) ++
      (if p.logHi.isEmpty then Vector() else Vector("logHigh", "logLow")) ++
      (if p.logVolume.isEmpty then Vector() else Vector("logVolume")) ++
      (if p.traded.isEmpty then Vector() else Vector("logTraded", "divYield")) ++
      (if p.logOpen.isEmpty then Vector() else Vector("logOpen")) ++
      MarketSim.basketColumns(p) ++ MarketSim.sectorColumns(p) ++
      (if p.macroPanel.isEmpty then Vector() else MarketSim.MacroK.Columns) ++
      (if p.macroNullPanel.isEmpty then Vector() else MarketSim.MacroK.NullColumns)
    val table = MarketSim.emitTable(p)
    val names = table.map(_.name)
    assertEquals(names, expect)
    assert(names.contains("nullMacroOutput") && names.contains("logSector3"))
    assert(table.forall(_.values.length == p.price.length))
    assert(names.forall(MarketSim.emitColumnKnown))
  }

  test("a batch is refused whole before any file is written") {
    val (years, seed) = (2, 20260825L)
    val w    = MarketSim.Defaults
    val p    = MarketSim.simulate(w, years, seed)
    val st   = MarketSim.measure(Vector(p), years)
    val rows = MarketSim.fidelityRows(MarketSim.SP500Anchors, st, None, years, 1, seed, w)
    val v    = onePathVerdict(w, p, st, rows)
    val spec = MarketSim.EmitSpec(w, years, seed, "")
    val dir  = java.nio.file.Files.createTempDirectory("emitBatch")
    val emit = s"${dir.posx}/batch.tsv"
    try
      val poisoned = MarketSim.simulate(w, years, seed + 7919L)
      poisoned.price(3) = Double.NaN
      val refused = MarketSim.writeEmittedBatch(MarketSim.SP500Anchors, emit, Vector(p, poisoned), 0, spec, v)
      assertEquals(refused,
        Left(s"path 1 holds a non-finite value; nothing written to ${MarketSim.indexedName(emit, 1, 3)}"))
      assertEquals(dir.toFile.list().length, 0, "nothing written")
      val q = MarketSim.simulate(w, years, seed + 7919L)
      val names = MarketSim.writeEmittedBatch(MarketSim.SP500Anchors, emit, Vector(p, q), 0, spec, v)
        .getOrElse(fail("written"))
      assertEquals(names, Vector(MarketSim.indexedName(emit, 0, 3), MarketSim.indexedName(emit, 1, 3)))
      for f <- names do
        assert(f.asPath.isFile, f)
        assert(MarketSim.sidecarName(f).asPath.isFile, s"$f sidecar")
    finally
      Option(dir.toFile.listFiles).foreach(_.foreach(_.delete()))
      dir.delete()
  }

  // Every optional column the TSV carries is named in `ungradedChannelSeries`, and the base columns
  // in `gradedSeries` -- so a consumer cannot read a verdict as covering a series it never examined.
  // The channels are ON here precisely because that is the trap: with them off, the disclosure is
  // vacuously right.
  test("the gate names every series it did not grade") {
    val (years, seed) = (2, 20260825L)
    val w    = MarketSim.Defaults.copy(satBeta = 1.2, satIdio = 0.77, rangeScale = 0.63, volIdio = 0.34)
    val p    = MarketSim.simulate(w, years, seed)
    val st   = MarketSim.measure(Vector(p), years)
    val rows = MarketSim.fidelityRows(MarketSim.SP500Anchors, st, None, years, 1, seed, w)
    val dir  = java.nio.file.Files.createTempDirectory("emitScope")
    val tsv  = s"${dir.posx}/scope.tsv"
    val json = MarketSim.sidecarName(tsv)
    val (header, side) =
      try
        MarketSim.writeEmitted(MarketSim.SP500Anchors, tsv, p, 0, MarketSim.EmitSpec(w, years, seed, ""),
          onePathVerdict(w, p, st, rows))
        (tsv.asPath.lines.head, json.asPath.lines.toVector)
      finally
        tsv.asPath.delete()
        json.asPath.delete()
        dir.delete()
    val declared = side.filter(l => l.trim.startsWith("\"gradedSeries\"") || l.trim.startsWith("\"ungradedChannelSeries\""))
    assertEquals(declared.size, 2, "both scope fields must be present")
    val cols = header.split('\t').toVector
    // the graded half must name series the file actually carries
    for col <- Vector("price", "bond") do assert(cols.contains(col), s"graded series $col must be an emitted column")
    // COVERAGE: every emitted channel column is either graded or declared ungraded.  A channel is
    // graded exactly when its readings exist: the placement rows and the sidecar's `verdictSeries`
    // share that condition
    val (graded, ungraded) = (declared(0), declared(1))
    def gradedBy(col: String): Boolean = col match
      case "logSat"              => st.sat.isDefined
      case "logHigh" | "logLow"  => st.bars.isDefined
      case "logVolume"           => st.bars.exists(_.volSd.isFinite)
      case _                     => false
    for col <- Vector("logSat", "logHigh", "logLow", "logVolume") do
      assert(gradedBy(col) || ungraded.contains(col), s"$col is neither graded (no readings) nor declared ungraded")
      // and the graded list must SAY so: a verdict computed from a column that the scope record
      // does not name is the original defect inverted
      assertEquals(gradedBy(col), graded.contains(col),
        s"$col: gradedSeries must list exactly the channel columns the verdict reads")
    assert(!ungraded.contains("log"), "every channel here is graded; nothing should be declared ungraded")
  }

  test("emitColumnKnown takes the TSV's columns and no other") {
    for c <- Vector("price", "inflPress", "logSat", "divYield", "logBasket", "logName12", "logSector10",
                    "macroOutput", "nullMacroSpread") do
      assert(MarketSim.emitColumnKnown(c), c)
    for c <- Vector("date", "logName", "logName0", "logName01", "logNamex", "Price", "macroSpreadNull") do
      assert(!MarketSim.emitColumnKnown(c), c)
  }

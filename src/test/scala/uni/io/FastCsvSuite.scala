package uni.io

import munit.FunSuite
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import scala.annotation.nowarn

class FastCsvSuite extends FunSuite:

  private def writeTempCsv(content: String): Path =
    val p = Files.createTempFile("fastcsv-suite-", ".csv")
    Files.write(p, content.getBytes(StandardCharsets.UTF_8))
    p

  // ============================================================================
  // Config
  // ============================================================================

  test("Config.toString includes delimiter char") {
    val cfg = FastCsv.Config(delimiterChar = Some(','))
    assert(cfg.toString.contains(","), cfg.toString)
  }

  test("Config.toString shows '?' when delimiterChar is None") {
    val cfg = FastCsv.Config()
    assert(cfg.toString.contains("?"), cfg.toString)
  }

  test("Config.delimiter: Some when delimiterChar is set") {
    val cfg = FastCsv.Config(delimiterChar = Some('\t'))
    assertEquals(cfg.delimiter, Some('\t'.toByte))
  }

  test("Config.delimiter: None when delimiterChar is None") {
    assertEquals(FastCsv.Config().delimiter, None)
  }

  // ============================================================================
  // rowsPulled — pull-style iterator
  // ============================================================================

  test("rowsPulled: parses simple comma-delimited CSV") {
    val p = writeTempCsv("a,b,c\n1,2,3\n")
    try
      val rows = FastCsv.rowsPulled(p, FastCsv.Config(delimiterChar = Some(','))).toList
      assertEquals(rows.length, 2)
      assertEquals(rows(0).toList, List("a", "b", "c"))
      assertEquals(rows(1).toList, List("1", "2", "3"))
    finally Files.deleteIfExists(p)
  }

  test("rowsPulled: CRLF line endings produce correct rows") {
    val p = writeTempCsv("x,y\r\n1,2\r\n")
    try
      val rows = FastCsv.rowsPulled(p, FastCsv.Config(delimiterChar = Some(','))).toList
      assertEquals(rows.length, 2)
      assertEquals(rows(1).toList, List("1", "2"))
    finally Files.deleteIfExists(p)
  }

  test("rowsPulled: quoted field containing comma") {
    val p = writeTempCsv("\"a,b\",c\n")
    try
      val rows = FastCsv.rowsPulled(p, FastCsv.Config(delimiterChar = Some(','))).toList
      assertEquals(rows.length, 1)
      assertEquals(rows(0).toList, List("a,b", "c"))
    finally Files.deleteIfExists(p)
  }

  test("rowsPulled: quoted field with escaped quote (\"\")") {
    val p = writeTempCsv("\"say \"\"hi\"\"\",b\n")
    try
      val rows = FastCsv.rowsPulled(p, FastCsv.Config(delimiterChar = Some(','))).toList
      assertEquals(rows.length, 1)
      assertEquals(rows(0)(0), "say \"hi\"")
    finally Files.deleteIfExists(p)
  }

  test("rowsPulled: tab-delimited file with explicit Config") {
    val p = writeTempCsv("x\ty\tz\n1\t2\t3\n")
    try
      val rows = FastCsv.rowsPulled(p, FastCsv.Config(delimiterChar = Some('\t'))).toList
      assertEquals(rows.length, 2)
      assertEquals(rows(0).toList, List("x", "y", "z"))
    finally Files.deleteIfExists(p)
  }

  test("rowsPulled: no trailing newline at EOF is handled") {
    val p = writeTempCsv("a,b,c")
    try
      val rows = FastCsv.rowsPulled(p, FastCsv.Config(delimiterChar = Some(','))).toList
      assertEquals(rows.length, 1)
      assertEquals(rows(0).toList, List("a", "b", "c"))
    finally Files.deleteIfExists(p)
  }

  // ============================================================================
  // eachRow — synchronous callback API
  // ============================================================================

  test("eachRow: processes all rows via callback") {
    val p = writeTempCsv("a,b\n1,2\n3,4\n")
    try
      val buf = scala.collection.mutable.ArrayBuffer.empty[Seq[String]]
      FastCsv.eachRow(p, FastCsv.Config(delimiterChar = Some(','))) { row => buf += row }
      assertEquals(buf.length, 3)
      assertEquals(buf(0).toList, List("a", "b"))
    finally Files.deleteIfExists(p)
  }

  test("eachRow: works with tab-delimited Config") {
    val p = writeTempCsv("col1\tcol2\nval1\tval2\n")
    try
      val buf = scala.collection.mutable.ArrayBuffer.empty[Seq[String]]
      FastCsv.eachRow(p, FastCsv.Config(delimiterChar = Some('\t'))) { row => buf += row }
      assertEquals(buf.length, 2)
      assertEquals(buf(1).toList, List("val1", "val2"))
    finally Files.deleteIfExists(p)
  }

  // ============================================================================
  // parse — push API via RowSink
  // ============================================================================

  test("parse: RowSink receives all rows") {
    val p = writeTempCsv("a,b\n1,2\n")
    try
      val rows = scala.collection.mutable.ArrayBuffer.empty[Seq[String]]
      val sink = new FastCsv.RowSink:
        def onRow(fields: Array[Array[Byte]]): Unit =
          rows += FastCsv.decodeFields(fields, StandardCharsets.UTF_8).toSeq
      FastCsv.parse(p, sink, FastCsv.Config(delimiterChar = Some(',')))
      assertEquals(rows.length, 2)
      assertEquals(rows(0).toList, List("a", "b"))
    finally Files.deleteIfExists(p)
  }

  // ============================================================================
  // rowsAsync — background-thread iterator
  // ============================================================================

  test("rowsAsync: returns all multi-column rows") {
    val p = writeTempCsv("a,b\n1,2\n3,4\n")
    try
      val rows = FastCsv.rowsAsync(p, FastCsv.Config(delimiterChar = Some(','))).toList
      // rowsAsync filters _.size > 1, so all 3 rows qualify
      assertEquals(rows.length, 3)
    finally Files.deleteIfExists(p)
  }

  // ============================================================================
  // RowParser — direct unit tests
  // ============================================================================

  private val comma: Byte = ','.toByte
  private def mkParser(delim: Byte = comma): FastCsv.RowParser =
    new FastCsv.RowParser(FastCsv.Config(delimiterChar = Some(delim.toChar)), delim)

  private def feedString(parser: FastCsv.RowParser, s: String): Seq[Option[Array[Array[Byte]]]] =
    s.getBytes(StandardCharsets.UTF_8).map(b => parser.feed(b)).toSeq

  private def decode(r: Array[Array[Byte]]): List[String] =
    FastCsv.decodeFields(r, StandardCharsets.UTF_8).toList

  test("RowParser.eof: empty parser returns None") {
    assertEquals(mkParser().eof(), None)
  }

  test("RowParser.eof: pending data emits final row") {
    val p = mkParser()
    feedString(p, "a,b")   // no newline
    val row = p.eof()
    assert(row.isDefined)
    assertEquals(decode(row.get), List("a", "b"))
  }

  test("RowParser: simple LF-terminated row") {
    val p = mkParser()
    val results = feedString(p, "x,y\n")
    val row = results.flatten.headOption
    assert(row.isDefined)
    assertEquals(decode(row.get), List("x", "y"))
  }

  test("RowParser: CRLF counts as a single row terminator") {
    val p = mkParser()
    val bytes = "a,b\r\nc,d\n".getBytes(StandardCharsets.UTF_8)
    val rows = bytes.flatMap(b => p.feed(b)).toList
    // Should yield exactly 2 rows (CRLF → one row, not two)
    assertEquals(rows.length, 2)
    assertEquals(decode(rows(0)), List("a", "b"))
    assertEquals(decode(rows(1)), List("c", "d"))
  }

  test("RowParser: field capacity grows beyond initialFieldSize") {
    val p = new FastCsv.RowParser(FastCsv.Config(delimiterChar = Some(','), initialFieldSize = 4), comma)
    val longVal = "x" * 200
    feedString(p, longVal)
    val row = p.eof()
    assert(row.isDefined)
    assertEquals(decode(row.get).head, longVal)
  }

  test("RowParser: quoted field is unquoted in output") {
    val p = mkParser()
    val emitted = "\"hello\",world\n".getBytes(StandardCharsets.UTF_8).flatMap(b => p.feed(b))
    assert(emitted.nonEmpty)
    assertEquals(decode(emitted.head), List("hello", "world"))
  }

  test("RowParser: quote followed by non-closing char is treated as literal") {
    // "ab"cd"\n:
    //   open quote → enter inQuotes
    //   ab → normal chars
    //   " → prevWasQuote=true
    //   c → prevWasQuote + non-delimiter/newline → append literal '"', append 'c'; still inQuotes
    //   d → append 'd'
    //   " → prevWasQuote=true again
    //   \n → real closing quote (prevWasQuote + '\n') → emit field "ab\"cd", emit row
    val p = mkParser()
    val emitted = "\"ab\"cd\"\n".getBytes(StandardCharsets.UTF_8).flatMap(b => p.feed(b))
    assertEquals(emitted.length, 1)
    assertEquals(decode(emitted.head).head, "ab\"cd")
  }

  // ============================================================================
  // decodeFields
  // ============================================================================

  test("decodeFields: converts byte arrays to strings") {
    val fields = Array("hello".getBytes, "world".getBytes)
    assertEquals(FastCsv.decodeFields(fields, StandardCharsets.UTF_8).toList, List("hello", "world"))
  }

  // ============================================================================
  // autoDetectDelimiter (deprecated) — routed via @nowarn helper
  // ============================================================================

  // Suppress the deprecation warning: we intentionally test the deprecated method
  // to cover its branching logic, which still exists in the compiled code.
  @nowarn("msg=deprecated")
  private def autoDetect(text: String, fname: String, ignoreErrors: Boolean = true): String =
    FastCsv.autoDetectDelimiter(text, fname, ignoreErrors)

  test("autoDetectDelimiter: commas win") {
    assertEquals(autoDetect("a,b,c", "f.csv"), ",")
  }

  test("autoDetectDelimiter: tabs win") {
    assertEquals(autoDetect("a\tb\tc", "f.tsv"), "\t")
  }

  test("autoDetectDelimiter: pipes win") {
    assertEquals(autoDetect("a|b|c", "f.txt"), "|")
  }

  test("autoDetectDelimiter: semicolons win") {
    assertEquals(autoDetect("a;b;c", "f.txt"), ";")
  }

  test("autoDetectDelimiter: all-zero counts → commas win (tie goes to comma)") {
    assertEquals(autoDetect("abc", "f.csv"), ",")
  }

  test("autoDetectDelimiter: ambiguous (pipes=semis > commas=tabs) with ignoreErrors=true → empty") {
    val text = ",,," + "\t\t\t" + "|||||" + ";;;;;"
    assertEquals(autoDetect(text, "f.txt", ignoreErrors = true), "")
  }

  test("autoDetectDelimiter: ambiguous with ignoreErrors=false → throws") {
    val text = ",,," + "\t\t\t" + "|||||" + ";;;;;"
    intercept[RuntimeException] {
      autoDetect(text, "f.txt", ignoreErrors = false)
    }
  }

  // ---------------------------------------------------------------------------
  // The two readers must agree on which rows survive
  // ---------------------------------------------------------------------------

  test("rowsPulled and rowsAsync keep the same rows") {
    // They used to differ: rowsPulled dropped rows with no non-blank field, while
    // rowsAsync dropped rows with a single field. The second returned *nothing* for
    // a one-column file, and the two disagreed on blank and ragged rows too. Both
    // now share FastCsv.isContentRow.
    val cases = Seq(
      "one column"  -> "alpha\nbeta\ngamma\n",
      "two columns" -> "a,b\nc,d\n",
      "blank row"   -> "a,b\n , \nc,d\n",
      "ragged row"  -> "a,b\nsolo\nc,d\n",
    )
    for (label, content) <- cases do
      val p = java.nio.file.Files.createTempFile("fastcsv_agree_", ".csv")
      try
        java.nio.file.Files.writeString(p, content)
        val pulled = uni.io.FastCsv.rowsPulled(p).toList.map(_.toList)
        val async  = uni.io.FastCsv.rowsAsync(p).toList.map(_.toList)
        assertEquals(pulled, async, s"readers disagree for [$label]")
      finally java.nio.file.Files.deleteIfExists(p)
  }

  test("a single-column file yields all of its rows") {
    // rowsAsync returned an empty iterator here, discarding a perfectly valid file.
    val p = java.nio.file.Files.createTempFile("fastcsv_onecol_", ".csv")
    try
      java.nio.file.Files.writeString(p, "alpha\nbeta\ngamma\n")
      assertEquals(uni.io.FastCsv.rowsAsync(p).toList.map(_.toList),
                   List(List("alpha"), List("beta"), List("gamma")))
    finally java.nio.file.Files.deleteIfExists(p)
  }

  // ---------------------------------------------------------------------------
  // Streaming padding: the sniffing sample also decides a width
  // ---------------------------------------------------------------------------

  test("a short row is padded to the sampled width rather than left ragged") {
    // `solo` used to come back as a one-element row, so a caller indexing (1)
    // got an IndexOutOfBounds on a file that looked rectangular.
    val p = writeTempCsv("a,b\nsolo\nc,d\n")
    assertEquals(uni.io.FastCsv.rowsPulled(p).toList.map(_.toList),
                 List(List("a", "b"), List("solo", ""), List("c", "d")))
  }

  test("all three Seq[String] readers agree, including on ragged input") {
    // eachRow backs the callback overload of `PathExts.csvRows`; it must not
    // disagree with the no-arg one just because of how the caller asked.
    val cases = Seq(
      "one column"  -> "alpha\nbeta\n",
      "ragged row"  -> "a,b\nsolo\nc,d\n",
      "blank row"   -> "a,b\n , \nc,d\n",
      "wide row"    -> "a,b\nw,x,y,z\nc,d\n",
    )
    for (label, content) <- cases do
      val p = writeTempCsv(content)
      val pulled = uni.io.FastCsv.rowsPulled(p).toList.map(_.toList)
      val async  = uni.io.FastCsv.rowsAsync(p).toList.map(_.toList)
      val each   = List.newBuilder[List[String]]
      uni.io.FastCsv.eachRow(p)(r => each += r.toList)
      assertEquals(async, pulled, s"rowsAsync differs for [$label]")
      assertEquals(each.result(), pulled, s"eachRow differs for [$label]")
  }

  test("a row wider than the sample is emitted jagged, never truncated") {
    // The sample only sees the first `sampleRows` rows, so a later, wider row
    // cannot be padded for -- but losing its extra fields would be worse than
    // returning it at its own width.
    val narrow = List.fill(100)("a,b").mkString("", "\n", "\n")
    val p = writeTempCsv(narrow + "v,w,x,y,z\n")
    val rows = uni.io.FastCsv.rowsPulled(p).toList
    assertEquals(rows.length, 101)
    assertEquals(rows.last.toList, List("v", "w", "x", "y", "z"))
  }

  test("padding does not depend on how the delimiter was chosen") {
    // The width comes from the reader's own window, not from the sniffer, so
    // supplying the delimiter -- which skips sniffing entirely -- pads the same.
    val p = writeTempCsv("a,b\nsolo\nc,d\n")
    val cfg = FastCsv.Config(delimiterChar = Some(','))
    assertEquals(uni.io.FastCsv.rowsPulled(p, cfg).toList.map(_.toList),
                 uni.io.FastCsv.rowsPulled(p).toList.map(_.toList))
  }

  test("a wide row past the sniffer's check interval is still padded for") {
    // Regression: the width used to be taken from `Delimiter.detect`, which
    // declares a winner partway through the first row whenever that row is over
    // ~100 chars, and then records every row as truncated. `rowCounts` came back
    // empty and nothing was padded at all -- on most real files.
    val wide  = (1 to 12).map(i => f"value_$i%05d").mkString(",")
    val short = (1 to  9).map(i => f"v_$i%05d").mkString(",")
    val p = writeTempCsv(Seq(wide, short, wide).mkString("", "\n", "\n"))
    assertEquals(uni.io.FastCsv.rowsPulled(p).toList.map(_.length), List(12, 12, 12))
  }

  test("csvRows agrees with its own callback overload") {
    import uni.*
    val p = writeTempCsv("a,b\nsolo\nc,d\n")
    val viaCallback = List.newBuilder[List[String]]
    p.csvRows(r => viaCallback += r.toList)
    assertEquals(viaCallback.result(), p.csvRows.toList.map(_.toList))
  }

  test("rowsAsync: hasNext is idempotent once the stream is exhausted") {
    // It used to take from the queue every call. The producer puts exactly one
    // end-of-stream marker, so the second call blocked forever -- an outright hang
    // for any caller that checked twice.
    val p = writeTempCsv("a,b\n1,2\n")
    val it = FastCsv.rowsAsync(p, FastCsv.Config(delimiterChar = Some(',')))
    while it.hasNext do it.next()
    assert(!it.hasNext)
    assert(!it.hasNext)
  }


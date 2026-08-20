package uni.io

import munit.FunSuite
import uni.*

/**
 * Checks `uni.io.FastCsv` against the committed reference in `test-data/csv-parity/`,
 * produced by `uni.apps.CsvParityGen`.
 *
 * The Rust side (`rust/tests/csv_parity.rs`) checks itself against the same files, so
 * the pair pins both implementations to one set of expectations without either test
 * needing the other language installed.
 *
 * This half looks circular -- the generator calls the same methods this suite checks
 * -- and it is not quite. The fixture is committed, so any later change to the parser
 * shows up here as a diff against what was reviewed, and the Rust half is what stops
 * a regenerated fixture from quietly redefining correctness for both languages at
 * once.
 *
 * Regenerate with `sbt "runMain uni.apps.CsvParityGen"`, and only deliberately.
 */
class CsvParitySuite extends FunSuite:

  /** Reverses the generator's escaping. `\\` must be consumed as a unit, or `\\n`
   *  would wrongly decode to a newline. */
  private def unescape(s: String): String =
    val out = new StringBuilder(s.length)
    var i = 0
    while i < s.length do
      if s.charAt(i) == '\\' && i + 1 < s.length then
        s.charAt(i + 1) match
          case 't'  => out.append('\t'); i += 2
          case 'n'  => out.append('\n'); i += 2
          case 'r'  => out.append('\r'); i += 2
          case '\\' => out.append('\\'); i += 2
          case c    => fail(s"unknown escape [\\$c] in fixture")
      else
        out.append(s.charAt(i)); i += 1
    out.result()

  /** (kind, case) -> rows, in fixture order. */
  private lazy val reference: Map[(String, String), Vector[Vector[String]]] =
    val file = Paths.get("test-data/csv-parity/scala-reference.txt")
    assert(file.isFile, s"missing fixture ${file.posx}; run: sbt \"runMain uni.apps.CsvParityGen\"")
    file.lines
      .filterNot(l => l.isEmpty || l.startsWith("#"))
      .foldLeft(Map.empty[(String, String), Vector[Vector[String]]]): (acc, line) =>
        // -1 keeps trailing empty fields; a row ending in a delimiter really does
        // have an empty last cell, and dropping it would rewrite the arity pinned here.
        val parts = line.split("\t", -1)
        assert(parts.length >= 3, s"malformed fixture line: $line")
        val key  = (parts(0), parts(1))
        val idx  = parts(2).toInt
        val rows = acc.getOrElse(key, Vector.empty)
        assertEquals(rows.length, idx, s"fixture rows out of order at: $line")
        acc.updated(key, rows :+ parts.drop(3).toVector.map(unescape))

  private def input(name: String): Path = Paths.get(s"test-data/csv-parity/inputs/$name.csv")

  private def cases(kind: String): Seq[String] =
    reference.keys.collect { case (k, name) if k == kind => name }.toSeq.sorted

  test("csvRows matches the committed reference") {
    val names = cases("rows")
    assert(names.length >= 20, s"only ${names.length} cases in fixture")
    for name <- names do
      val actual = input(name).csvRows.map(_.toVector).toVector
      assertEquals(actual, reference(("rows", name)), s"csvRows differs for case [$name]")
  }

  test("csvRowsStream matches the committed reference") {
    val names = cases("stream")
    assert(names.length >= 20, s"only ${names.length} cases in fixture")
    for name <- names do
      val actual = input(name).csvRowsStream.map(_.toVector).toVector
      assertEquals(actual, reference(("stream", name)), s"csvRowsStream differs for case [$name]")
  }

  test("the callback overload of csvRows agrees with the streaming one") {
    // `csvRows(onRow)` runs through `eachRow`, a separate reader with its own copy
    // of the row rules. It shares the fixture so it cannot drift from them.
    for name <- cases("stream") do
      val collected = Vector.newBuilder[Vector[String]]
      input(name).csvRows(r => collected += r.toVector)
      assertEquals(collected.result(), reference(("stream", name)), s"eachRow differs for [$name]")
  }

  test("the two readings are identical, case for case") {
    // Neither reader reshapes a row, so how a caller asked cannot change the answer.
    for name <- cases("rows") do
      assertEquals(reference(("stream", name)), reference(("rows", name)),
                   s"readings differ for [$name]")
  }

  test("a row's position in the file does not change how it is reported") {
    // A wide row 101 rows in, past any window a reader might be tempted to keep.
    val p      = input("past-window")
    val stream = p.csvRowsStream.toVector
    val all    = p.csvRows.toVector
    assertEquals(stream, all)
    assertEquals(all.head.length, 2, "the narrow rows stay narrow")
    assertEquals(all(100).length, 4, "the wide row keeps every field")
  }

  test("csvSchema matches the committed reference") {
    // The expectation is decoded from the fixture, never recomputed with schema()
    // itself — recomputing would follow a changed tie-break and assert nothing.
    val names = cases("schema")
    assert(names.length >= 20, s"only ${names.length} schema cases in fixture")
    for name <- names do
      val fields = reference(("schema", name)).head
      val widths = fields.tail.map { f =>
        val parts = f.split(":")
        parts(0).toInt -> parts(1).toInt
      }.toMap
      val expected = uni.io.FastCsv.CsvSchema(widths, fields.head.toInt)
      assertEquals(input(name).csvSchema, expected, s"csvSchema differs for case [$name]")
  }

  test("unescape reverses the generator's escaping") {
    assertEquals(unescape("a\\tb"), "a\tb")
    assertEquals(unescape("a\\nb"), "a\nb")
    assertEquals(unescape("a\\rb"), "a\rb")
    assertEquals(unescape("a\\\\nb"), "a\\nb") // why `\\` is consumed as a unit
    assertEquals(unescape(""), "")
  }

  /** A double as raw IEEE bits, matching the generator. Comparing formatted decimals
   *  would test the float printers rather than the parsers. */
  private def cell(d: Double): String =
    if d.isNaN then "NaN" else f"${java.lang.Double.doubleToRawLongBits(d)}%016x"

  test("loadSmart cell values match the committed reference") {
    val names = cases("mat")
    assert(names.length >= 15, s"only ${names.length} cases had matrix data")
    for name <- names do
      val m = input(name).loadSmartD.mat
      val actual = (0 until m.rows).map(r => (0 until m.cols).map(c => cell(m(r, c))).toVector).toVector
      assertEquals(actual, reference(("mat", name)), s"matrix differs for case [$name]")
  }

  test("loadSmart header detection matches the committed reference") {
    for name <- cases("hdr") do
      // The generator writes one line per case; no headers arrives as a single
      // trailing empty field.
      val want = reference(("hdr", name)).head match
        case Vector("") => Vector.empty[String]
        case row        => row
      assertEquals(input(name).loadSmartD.headers, want, s"headers differ for [$name]")
  }

  test("the numeric-cells case pins big(String) parsing") {
    // Called out by name: this case caught the Rust port rejecting a trailing dot
    // and keeping the sign on -0, neither of which BigDecimal does.
    val t = input("numeric-cells").loadSmartD
    assertEquals(t.headers.head, "plain")
    def by(name: String, row: Int): Double = t(name)(row, 0)

    assertEquals(by("currency", 0), 1234.56)
    assertEquals(by("percent", 0), 0.12)
    assertEquals(by("trailing-dot", 0), 4.0)   // BigDecimal's fraction part is optional
    assertEquals(by("plus", 0), 5.0)
    assertEquals(by("leading-dot", 0), 0.5)
    assert(by("blank", 0).isNaN)
    assert(by("junk", 0).isNaN)
    assert(by("infinite", 0).isNaN, "inf must not survive as infinity")
    assert(by("notanumber", 0).isNaN)
    // BigDecimal has no signed zero.
    assertEquals(java.lang.Double.doubleToRawLongBits(by("plus", 1)), 0L)
  }


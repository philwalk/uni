package uni

import munit.FunSuite

/**
 * Checks `uni.Paths` resolution against the committed reference in
 * `test-data/path-parity/`.
 *
 * The Rust port (`rust/tests/path_parity.rs`) checks itself against the same file,
 * so the pair pins both implementations to one set of expectations without either
 * test needing the other language installed.
 *
 * Only the file matching this platform is checked. `isWin` comes from `os.name`
 * and cannot be overridden, so Scala can verify one rule set per host; the Rust
 * side takes `is_windows` as data and verifies every block from any host. That
 * asymmetry is the reason the fixture is split by platform rather than merged.
 *
 * Regenerate with `sbt "runMain uni.apps.PathParityGen"` — and only when the
 * expectations are meant to move.
 */
class PathParitySuite extends FunSuite:

  private val platform = if isWin then "windows" else "posix"
  private val fixture =
    s"${sys.props.getOrElse("user.dir", ".")}/test-data/path-parity/scala-reference-$platform.txt"

  override def afterEach(context: AfterEach): Unit = resetConfig()

  private def decode(s: String): String = if s == "!empty" then "" else s

  /** Only `!error` is encoded here. `!empty` is not: `decode` has already turned
   *  the fixture's `!empty` into "", so re-encoding an empty result would compare
   *  `!empty` against "" and never match. The generator still writes `!empty`,
   *  which is what keeps the committed file unambiguous. */
  private def attempt(f: => String): String =
    try f catch case _: Throwable => "!error"

  /** Records grouped by kind, each already split on ' | ' and trimmed. */
  private lazy val records: Vector[Vector[String]] =
    val p = fixture.asPath
    require(p.isFile,
      s"missing ${p.posx} — regenerate with: sbt \"runMain uni.apps.PathParityGen\"")
    p.lines.iterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .map(_.split('|').map(_.trim).toVector)
      .toVector

  private lazy val user: UserInfo = records.collectFirst {
    case Vector("user", name, home, dir) => UserInfo(name, home, dir)
  }.getOrElse(fail("fixture has no user record"))

  /** Mount lines per table id, in fixture order — the order decides one-to-many. */
  private lazy val tables: Map[String, Seq[String]] =
    records.collect { case Vector("table", id, rest*) => id -> rest.mkString(" | ") }
      .groupMap(_._1)(_._2)

  private lazy val derived: Map[(String, String), String] =
    records.collect { case Vector("derived", id, field, v) => (id, field) -> decode(v) }.toMap

  private lazy val cases: Vector[(String, String, String, String)] =
    records.collect {
      case Vector("case", id, field, in, want) => (id, field, decode(in), decode(want))
    }

  private def evaluate(field: String, input: String): String = field match
    case "classify" => attempt(Resolver.classify(input).toString)
    case "win"      => attempt(Resolver.resolvePathstr(input))
    case "posixabs" => attempt(toPosixAbs(input))
    case "drivecwd" => attempt(config.driveCwd(input.head).toString)
    case other      =>
      // Extension-method fields come straight from the generator's own table, so
      // the suite and the fixture cannot drift apart on what a field means.
      extByName.get(other) match
        case Some(f) => attempt(f(input))
        case None    => fail(s"fixture names field $other, which this suite cannot evaluate")

  private lazy val extByName: Map[String, String => String] =
    uni.apps.PathParityGen.extFields.toMap

  for id <- tables.keys.toSeq.sorted do
    test(s"$platform/$id matches the parity reference"):
      withMountLines(tables(id), user)

      // Derived facts first: a wrong cygdrive or msysRoot explains every case
      // under that table, so a separate assertion keeps the diagnosis short.
      for field <- Seq("cygdrive", "msysroot") do
        derived.get((id, field)).foreach { want =>
          val got = if field == "cygdrive" then config.cygdrive else config.msysRoot
          assertEquals(got, want, s"$id derived $field")
        }

      val failures = cases.collect {
        case (`id`, field, input, want) if evaluate(field, input) != want =>
          s"$field [$input]: got [${evaluate(field, input)}], want [$want]"
      }
      assert(failures.isEmpty,
        s"${failures.length} case(s) diverged for $id:\n  ${failures.mkString("\n  ")}")

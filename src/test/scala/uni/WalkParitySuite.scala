package uni

import munit.FunSuite

/**
 * Checks the directory-listing and tree-walking methods against the committed reference in
 * `test-data/walk-parity/`, which `rust/tests/walk_parity.rs` also checks itself against.
 *
 * # What this pins, and what it cannot
 *
 * Contents, not order. `File.listFiles` and `Files.walk` guarantee no sibling order, so both
 * sides sort before comparing — see `WalkParityGen`. The ordering property that *is* guaranteed
 * (pre-order: a directory before its contents) survives no sort, so it is asserted separately
 * below and in the Rust unit tests rather than through the fixture.
 *
 * Regenerate with `sbt "runMain uni.apps.WalkParityGen"`.
 */
class WalkParitySuite extends FunSuite:

  val root: java.nio.file.Path = Paths.get("test-data/walk-parity/inputs")

  lazy val rows: Seq[Vector[String]] =
    val f = Paths.get("test-data/walk-parity/scala-reference.txt")
    assert(f.exists, s"missing fixture ${f.posx}; regenerate with: sbt \"runMain uni.apps.WalkParityGen\"")
    f.lines.filterNot(_.startsWith("#")).filter(_.nonEmpty).map(_.split("\t", -1).toVector).toSeq

  def rel(p: java.nio.file.Path): String =
    val r = root.toAbsolutePath.normalize.relativize(p.toAbsolutePath.normalize)
      .toString.replace('\\', '/')
    if r.isEmpty then "." else r

  /** The order the API returns, **not** re-sorted.
   *
   *  This used to sort, because neither language promised an order. Both now specify the same one,
   *  so the fixture pins order too -- re-sorting here would hide the divergence it exists to
   *  catch.
   */
  def sortedRel(ps: Seq[java.nio.file.Path]): Seq[String] = ps.map(rel)

  /** The recorded list, split so an empty field yields an empty Seq rather than `Seq("")`. */
  def expected(r: Vector[String]): Seq[String] =
    if r(2).isEmpty then Seq.empty else r(2).split(",", -1).toSeq

  def start(r: Vector[String]): java.nio.file.Path =
    if r(1) == "." then root else root.resolve(r(1))

  test("the fixture and its input tree are present") {
    assert(rows.length >= 20, s"only ${rows.length} rows")
    assert(root.exists, s"missing input tree ${root.posx}")
    for kind <- Seq("paths", "subdirs", "subfiles", "tree", "alias-walk", "alias-files") do
      assert(rows.exists(_.head == kind), s"no [$kind] rows")
  }

  test("paths, subdirs and subfiles match the reference") {
    for r <- rows if Seq("paths", "subdirs", "subfiles").contains(r.head) do
      val p = start(r)
      val got = r.head match
        case "paths"    => sortedRel(p.paths)
        case "subdirs"  => sortedRel(p.subdirs)
        case "subfiles" => sortedRel(p.subfiles)
        case other      => fail(s"unexpected kind [$other]")
      assertEquals(got, expected(r), s"${r.head} of [${r(1)}]")
  }

  test("pathsTree matches the reference, root included") {
    for r <- rows if r.head == "tree" do
      assertEquals(sortedRel(start(r).pathsTree), expected(r), s"tree of [${r(1)}]")
  }

  test("walk and files are aliases, per the reference") {
    // `walk` is the lazy spelling -- raw readdir order, which no filesystem promises (NTFS
    // sorted, ext4 not) -- so both sides are sorted: the pinned property is "same elements".
    for r <- rows if r.head == "alias-walk" do
      assertEquals((root.walk.toSeq.map(rel).sorted == root.pathsTree.map(rel).sorted).toString, r(2))
    for r <- rows if r.head == "alias-files" do
      assertEquals((root.files.toSeq.map(f => rel(f.toPath)) == sortedRel(root.paths)).toString, r(2))
  }

  test("the tree walk is pre-order and includes the root") {
    // Not fixture-able: sorting destroys it. Asserted here, and mirrored in the Rust unit tests.
    val order = root.pathsTree.map(rel)
    assertEquals(order.head, ".", s"the root must come first: ${order.take(3)}")
    def idx(n: String): Int =
      val i = order.indexOf(n)
      assert(i >= 0, s"[$n] missing from $order")
      i
    assert(idx("a") < idx("a/b"), s"parent before child: $order")
    assert(idx("a/b") < idx("a/b/c"), s"parent before grandchild: $order")
    assert(idx("a/b/c") < idx("a/b/c/deeper.txt"), s"directory before its file: $order")
  }

  test("a file lists empty but walks to itself; a missing path does neither") {
    val f = root.resolve("top.txt")
    assert(f.isFile)
    assertEquals(f.paths, Seq.empty)
    assertEquals(sortedRel(f.pathsTree), Seq("top.txt"))
    val missing = root.resolve("no-such-entry")
    assert(!missing.exists)
    assertEquals(missing.paths, Seq.empty)
    assertEquals(missing.pathsTree, Seq.empty)
  }

  test("hidden files and names with spaces are listed, not filtered") {
    // Both are easy to drop by accident, and a port that filtered them would still look right
    // on a tidy tree.
    val names = root.paths.map(_.getFileName.toString)
    assert(names.contains(".dotfile"), s"dotfile missing from $names")
    assert(names.contains("with space.txt"), s"spaced name missing from $names")
  }

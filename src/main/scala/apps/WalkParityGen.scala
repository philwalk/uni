package uni.apps

import uni.*
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Regenerates the cross-language parity fixture in `test-data/walk-parity/` for the directory
 * listing and tree-walking methods, and their Rust port (`rust/src/upath/walk.rs`).
 *
 * Consumed by two tests that must agree with each other:
 *   - uni.WalkParitySuite         (Scala, src/test)
 *   - rust/tests/walk_parity.rs   (Rust)
 *
 * # The tree is built here, not found
 *
 * Every other fixture in this repo records values computed from committed inputs. This one
 * additionally has to *create* the inputs, because what is being pinned is how a directory tree
 * is traversed -- and the shapes that matter (an empty directory, a file beside a directory, a
 * nested level, a dotfile) have to exist to be walked.
 *
 * The tree is written under `inputs/` and committed, so both languages walk the identical
 * shape rather than each building its own and hoping they match.
 *
 * # Order IS pinned, as of the ordering fix
 *
 * `File.listFiles`, `Files.walk` and `read_dir` promise no sibling order, so this fixture used to
 * sort both sides and pin only *what* was traversed. Both languages now sort by the same key --
 * case-insensitive, then case-sensitive -- so the recorded order is the order the API returns,
 * and a divergence in ordering fails a test instead of being sorted away.
 *
 * Paths are recorded relative to the fixture root, because the absolute prefix differs per
 * machine.
 *
 * Run:  sbt "runMain uni.apps.WalkParityGen"
 */
object WalkParityGen:
  def println(s: String = ""): Unit = print(s"$s\n")

  val root: java.nio.file.Path = Paths.get("test-data/walk-parity/inputs")

  /** Files to create, as paths relative to the root. Directories come from their parents. */
  val tree: Seq[String] = Seq(
    "top.txt",
    "a/one.txt",
    "a/two.txt",
    "a/b/deep.txt",
    "a/b/c/deeper.txt",
    "empty-dir/.gitkeep",   // an otherwise-empty directory; git will not commit a bare one
    ".dotfile",             // hidden files are listed by both, and are easy to filter by accident
    "with space.txt",
    "UPPER.TXT",            // case: two names differing only in case cannot coexist on Windows
  )

  /** Directories to create with no files at all, to pin the empty-listing case. */
  val bareDirs: Seq[String] = Seq("a/b/c/leaf-dir")

  def build(): Unit =
    for rel <- tree do
      val f = root.resolve(rel)
      java.nio.file.Files.createDirectories(f.getParent)
      java.nio.file.Files.write(f, s"content of $rel\n".getBytes(UTF_8))
    for rel <- bareDirs do
      java.nio.file.Files.createDirectories(root.resolve(rel))

  /** A path relative to the fixture root, with forward slashes, so it is machine-independent. */
  def rel(p: java.nio.file.Path): String =
    val r = root.toAbsolutePath.normalize.relativize(p.toAbsolutePath.normalize)
      .toString.replace('\\', '/')
    // The root relativized against itself is the empty string. Recorded as "." instead:
    // an empty field in a comma-joined list is exactly where two languages' split
    // implementations disagree about leading and trailing empties.
    if r.isEmpty then "." else r

  /** The order the API returns, **not** re-sorted.
   *
   *  This used to sort, because neither language promised an order. Both now specify one -- and
   *  the same one -- so the fixture pins the order too. Re-sorting here would hide exactly the
   *  divergence it exists to catch.
   */
  def sortedRel(ps: Seq[java.nio.file.Path]): Seq[String] = ps.map(rel)

  def main(args: Array[String]): Unit =
    build()

    val out = collection.mutable.ArrayBuffer.empty[String]

    // Every directory in the tree, so each listing method is exercised at several depths --
    // including the leaf directory with no entries at all.
    val dirs: Seq[java.nio.file.Path] =
      (root +: root.pathsTree.filter(java.nio.file.Files.isDirectory(_))).distinct.sorted

    for d <- dirs do
      val key = rel(d)
      out += s"paths\t$key\t${sortedRel(d.paths).mkString(",")}"
      out += s"subdirs\t$key\t${sortedRel(d.subdirs).mkString(",")}"
      out += s"subfiles\t$key\t${sortedRel(d.subfiles).mkString(",")}"
      out += s"tree\t$key\t${sortedRel(d.pathsTree).mkString(",")}"

    // A file rather than a directory: lists empty, but walks to itself.
    val aFile = root.resolve("top.txt")
    out += s"paths\ttop.txt\t${sortedRel(aFile.paths).mkString(",")}"
    out += s"tree\ttop.txt\t${sortedRel(aFile.pathsTree).mkString(",")}"

    // A path that does not exist: both empty.
    val missing = root.resolve("no-such-entry")
    out += s"paths\tno-such-entry\t${sortedRel(missing.paths).mkString(",")}"
    out += s"tree\tno-such-entry\t${sortedRel(missing.pathsTree).mkString(",")}"

    // `walk` and `files` are aliases; recorded so a port cannot quietly diverge from them.
    // No `.sorted` here. The API specifies the order now, so re-sorting one side would compare a
    // sorted list against an unsorted one and report the aliases as differing -- which is exactly
    // what happened on the first run after the ordering change.
    out += s"alias-walk\t.\t${(root.walk.toSeq.map(rel) == sortedRel(root.pathsTree))}"
    out += s"alias-files\t.\t${(root.files.toSeq.map(f => rel(f.toPath)) == sortedRel(root.paths))}"

    val header = Seq(
      "# Cross-language directory-traversal parity reference.",
      "# Generated by uni.apps.WalkParityGen. Do not hand-edit.",
      "# kind<TAB>relative-start<TAB>comma-separated relative paths, SORTED",
      "# Order is NOT pinned: neither listFiles nor Files.walk guarantees sibling order.",
      s"# rows: ${out.length}",
    )
    val file = Paths.get("test-data/walk-parity/scala-reference.txt")
    java.nio.file.Files.createDirectories(file.getParent)
    file.writeLines(header ++ out.toSeq)
    println(s"wrote ${out.length} rows -> ${file.posx}")
    FixtureGuard.warnIfIgnored(file.getParent)

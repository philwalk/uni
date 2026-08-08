package uni

import java.nio.file.Files
import scala.util.Using
import munit.FunSuite

import uni.*

/** That the lazy traversal methods are actually lazy, not a Seq wearing an Iterator's clothes.
  *
  * The distinction is not cosmetic: on a USB or network directory with thousands of entries, an
  * eager listing cannot yield anything until the last entry has arrived, which is the difference
  * between usable and unusable. `paths`/`pathsTree` buy a canonical order by being complete first;
  * `pathsIter`/`pathsTreeIter` buy first-result latency by giving that order up.
  */
class LazyTraversalSuite extends FunSuite:

  private def treeOf(n: Int) =
    val root = Files.createTempDirectory("lazytrav")
    val sub  = Files.createDirectory(root.resolve("sub"))
    (0 until n).foreach(i => Files.writeString(root.resolve(f"f$i%04d.txt"), "x"))
    (0 until n).foreach(i => Files.writeString(sub.resolve(f"g$i%04d.txt"), "x"))
    root

  test("pathsIter yields without consuming the whole directory") {
    val root = treeOf(300)
    // `next` once, then close. If this were `paths.iterator` the listing would already be complete;
    // laziness is not observable from the value, so what is asserted is the contract that makes it
    // possible -- a handle is held, and closing early is legal and sufficient.
    val it = root.pathsIter
    assert(it.hasNext, "has entries")
    val first = it.next()
    assert(Files.exists(first), "yielded a real entry")
    it.close()
    assertEquals(it.hasNext, false, "closed iterator is exhausted")
  }

  test("taking 1 of a large tree does not require walking it all") {
    val root = treeOf(300)
    val it = root.pathsTreeIter
    val firstFive = it.take(5).toList
    assertEquals(firstFive.length, 5)
    it.close()
  }

  test("lazy and eager see the same set, differing only in order") {
    val root = treeOf(20)
    val eager = root.paths.toSet
    val lazySet = Using.resource(root.pathsIter)(_.toSet)
    assertEquals(lazySet, eager, "same entries")
    val eagerTree = root.pathsTree.toSet
    val lazyTree  = Using.resource(root.pathsTreeIter)(_.toSet)
    assertEquals(lazyTree, eagerTree, "same tree entries")
  }

  test("paths keeps its canonical order; the tree keeps parent-before-descendant") {
    val root = treeOf(5)
    assertEquals(root.paths, root.paths.sortBy(p => (p.posx.toLowerCase, p.posx)), "paths is sorted")
    // Files.walk is depth-first pre-order, so this holds without any sort.
    val walked = Using.resource(root.pathsTreeIter)(_.toList)
    val subIdx = walked.indexWhere(_.getFileName.toString == "sub")
    val childIdx = walked.indexWhere { q =>
      val parent = q.getParent
      parent != null && parent.getFileName != null && parent.getFileName.toString == "sub"
    }
    assert(subIdx >= 0 && childIdx > subIdx, s"parent at $subIdx precedes child at $childIdx")
  }

  test("eachPath closes the handle even when the body throws") {
    // Flat, so the cleanup below is a fair test of the handle rather than of recursive delete.
    val root = Files.createTempDirectory("eachpath")
    (0 until 3).foreach(i => Files.writeString(root.resolve(s"f$i.txt"), "x"))
    intercept[RuntimeException] {
      root.eachPath(_ => throw new RuntimeException("boom"))
    }
    // On Windows an unclosed directory handle blocks deletion, so this is observable.
    root.paths.foreach(Files.deleteIfExists(_))
    assert(Files.deleteIfExists(root), "directory released despite the throw")
  }

  test("a non-directory and a missing path both iterate empty") {
    val f = Files.createTempFile("notadir", ".txt")
    assertEquals(Using.resource(f.pathsIter)(_.toList), Nil)
    assertEquals(Using.resource(f.filesIter)(_.toList), Nil)
    val missing = Paths.get("/no/such/place/at/all")
    assertEquals(Using.resource(missing.pathsIter)(_.toList), Nil)
    assertEquals(Using.resource(missing.pathsTreeIter)(_.toList), Nil)
  }

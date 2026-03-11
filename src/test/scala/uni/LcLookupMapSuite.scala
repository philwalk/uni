package uni

import munit.FunSuite

class LcLookupMapSuite extends FunSuite:

  private def mkMap: LcLookupMap[Int] =
    new LcLookupMap(Map("apple" -> 1, "banana" -> 2, "cherry" -> 3))

  // ============================================================================
  // get
  // ============================================================================

  test("get: exact lowercase key") {
    assertEquals(mkMap.get("apple"), Some(1))
  }

  test("get: uppercase key normalised") {
    assertEquals(mkMap.get("APPLE"), Some(1))
  }

  test("get: mixed-case key") {
    assertEquals(mkMap.get("BaNaNa"), Some(2))
  }

  test("get: missing key returns None") {
    assertEquals(mkMap.get("mango"), None)
  }

  // ============================================================================
  // apply
  // ============================================================================

  test("apply: found key returns value") {
    assertEquals(mkMap("CHERRY"), 3)
  }

  test("apply: missing key throws NoSuchElementException") {
    intercept[NoSuchElementException] {
      mkMap("missing")
    }
  }

  // ============================================================================
  // contains
  // ============================================================================

  test("contains: present (case-insensitive)") {
    assert(mkMap.contains("APPLE"))
    assert(mkMap.contains("apple"))
  }

  test("contains: absent key returns false") {
    assert(!mkMap.contains("mango"))
  }

  // ============================================================================
  // getOrElse
  // ============================================================================

  test("getOrElse: hit returns mapped value") {
    assertEquals(mkMap.getOrElse("CHERRY", 0), 3)
  }

  test("getOrElse: miss returns default") {
    assertEquals(mkMap.getOrElse("mango", 99), 99)
  }

  // ============================================================================
  // removed
  // ============================================================================

  test("removed: specified key is gone, others remain") {
    val m2 = mkMap.removed("APPLE")
    assertEquals(m2.get("apple"), None)
    assertEquals(m2.get("banana"), Some(2))
  }

  // ============================================================================
  // Collection views
  // ============================================================================

  test("iterator: yields all 3 entries") {
    assertEquals(mkMap.iterator.size, 3)
  }

  test("keysIterator: yields all keys") {
    assertEquals(mkMap.keysIterator.toSet, Set("apple", "banana", "cherry"))
  }

  test("keys: contains all keys") {
    assertEquals(mkMap.keys.toSet, Set("apple", "banana", "cherry"))
  }

  test("keySet: correct set") {
    assertEquals(mkMap.keySet, Set("apple", "banana", "cherry"))
  }

  test("valueSet: correct values") {
    assertEquals(mkMap.valueSet.toSet, Set(1, 2, 3))
  }

  test("foreach: visits all entries") {
    var count = 0
    mkMap.foreach(_ => count += 1)
    assertEquals(count, 3)
  }

  test("collectFirst: finds matching entry") {
    val r = mkMap.collectFirst { case ("apple", v) => v }
    assertEquals(r, Some(1))
  }

  test("collectFirst: returns None when no match") {
    val r = mkMap.collectFirst { case ("mango", v) => v }
    assertEquals(r, None)
  }

  test("toSeq: length 3") {
    assertEquals(mkMap.toSeq.length, 3)
  }

  test("toMap: returns underlying map") {
    assertEquals(mkMap.toMap, Map("apple" -> 1, "banana" -> 2, "cherry" -> 3))
  }

  test("withFilter: filters by predicate") {
    val filtered = mkMap.withFilter(_._2 > 1).toSeq
    assertEquals(filtered.length, 2)
  }

  // ============================================================================
  // nonEmpty / isEmpty
  // ============================================================================

  test("nonEmpty: non-empty map") {
    assert(mkMap.nonEmpty)
    assert(!mkMap.isEmpty)
  }

  test("isEmpty: empty map") {
    val empty = new LcLookupMap[Int](Map.empty)
    assert(empty.isEmpty)
    assert(!empty.nonEmpty)
  }

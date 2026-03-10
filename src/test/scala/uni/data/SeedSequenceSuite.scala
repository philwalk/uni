package uni.data

import munit.FunSuite

class SeedSequenceSuite extends FunSuite {

  private def SS(entropy: Any) = new SeedSequenceModule.SeedSequence(entropy = entropy)

  // ============================================================================
  // SeedlessSeedSequence
  // ============================================================================

  test("SeedlessSeedSequence.generateStateUInt32 throws") {
    val s = new SeedSequenceModule.SeedlessSeedSequence()
    intercept[UnsupportedOperationException] { s.generateStateUInt32(4) }
  }

  test("SeedlessSeedSequence.generateStateUInt64 throws") {
    val s = new SeedSequenceModule.SeedlessSeedSequence()
    intercept[UnsupportedOperationException] { s.generateStateUInt64(4) }
  }

  test("SeedlessSeedSequence.spawn returns list of self") {
    val s = new SeedSequenceModule.SeedlessSeedSequence()
    val children = s.spawn(3)
    assertEquals(children.length, 3)
    assert(children.forall(_ eq s))
  }

  // ============================================================================
  // SeedSequence construction
  // ============================================================================

  test("SeedSequence with poolSize < 4 throws") {
    intercept[IllegalArgumentException] {
      new SeedSequenceModule.SeedSequence(entropy = BigInt(1), poolSize = 3)
    }
  }

  test("SeedSequence with no entropy (null) initialises randomly") {
    val s = new SeedSequenceModule.SeedSequence()
    val w = s.generateStateUInt32(4)
    assertEquals(w.length, 4)
  }

  // ============================================================================
  // SeedSequence.toString and state
  // ============================================================================

  test("SeedSequence.toString includes class name") {
    val s = SS(BigInt(42))
    assert(s.toString.contains("SeedSequence"), s.toString)
  }

  test("SeedSequence.state map contains entropy key") {
    val s = SS(BigInt(42))
    assert(s.state.contains("entropy"))
    assert(s.state.contains("pool_size"))
    assert(s.state.contains("n_children_spawned"))
  }

  // ============================================================================
  // SeedSequence.spawn
  // ============================================================================

  test("spawn creates requested number of children") {
    val s = SS(BigInt(0))
    val children = s.spawn(3)
    assertEquals(children.length, 3)
  }

  test("spawn increments n_children_spawned") {
    val s = SS(BigInt(0))
    s.spawn(2)
    assertEquals(s.state("n_children_spawned"), 2)
    s.spawn(3)
    assertEquals(s.state("n_children_spawned"), 5)
  }

  test("spawned children generate different state than parent") {
    val s     = SS(BigInt(42))
    val child = s.spawn(1).head.asInstanceOf[SeedSequenceModule.SeedSequence]
    val parentState = s.generateStateUInt32(4).toSeq
    val childState  = child.generateStateUInt32(4).toSeq
    assertNotEquals(parentState, childState)
  }

  // ============================================================================
  // generateStateUInt32 / generateStateUInt64
  // ============================================================================

  test("generateStateUInt32 returns array of requested length") {
    val w = SS(BigInt(1)).generateStateUInt32(4)
    assertEquals(w.length, 4)
  }

  test("generateStateUInt64 returns array of requested length") {
    val w = SS(BigInt(1)).generateStateUInt64(2)
    assertEquals(w.length, 2)
  }

  test("generateState(useUInt64=false) returns Array[Int]") {
    val s = SS(BigInt(1))
    val result = s.generateState(4, useUInt64 = false)
    assert(result.isInstanceOf[Array[Int]])
    assertEquals(result.asInstanceOf[Array[Int]].length, 4)
  }

  test("generateState(useUInt64=true) returns Array[Long]") {
    val s = SS(BigInt(1))
    val result = s.generateState(2, useUInt64 = true)
    assert(result.isInstanceOf[Array[Long]])
    assertEquals(result.asInstanceOf[Array[Long]].length, 2)
  }

  // ============================================================================
  // Entropy types via coerceToUint32Array (exercised through SeedSequence)
  // ============================================================================

  test("Int entropy produces valid state") {
    val w = SS(42).generateStateUInt32(4)
    assertEquals(w.length, 4)
  }

  test("Long entropy produces valid state") {
    val w = SS(42L).generateStateUInt32(4)
    assertEquals(w.length, 4)
  }

  test("Byte entropy produces valid state") {
    val w = SS(7.toByte).generateStateUInt32(4)
    assertEquals(w.length, 4)
  }

  test("Short entropy produces valid state") {
    val w = SS(100.toShort).generateStateUInt32(4)
    assertEquals(w.length, 4)
  }

  test("Array[Int] entropy produces valid state") {
    val w = new SeedSequenceModule.SeedSequence(entropy = Array(1, 2, 3)).generateStateUInt32(4)
    assertEquals(w.length, 4)
  }

  test("empty Array entropy produces valid state") {
    val w = new SeedSequenceModule.SeedSequence(entropy = Array.emptyIntArray).generateStateUInt32(4)
    assertEquals(w.length, 4)
  }

  test("Seq[Int] entropy produces valid state") {
    val w = new SeedSequenceModule.SeedSequence(entropy = Seq(10, 20, 30)).generateStateUInt32(4)
    assertEquals(w.length, 4)
  }

  test("empty Seq entropy produces valid state") {
    val w = new SeedSequenceModule.SeedSequence(entropy = Seq.empty[Int]).generateStateUInt32(4)
    assertEquals(w.length, 4)
  }

  test("hex String entropy produces valid state") {
    val w = new SeedSequenceModule.SeedSequence(entropy = "0xFF").generateStateUInt32(4)
    assertEquals(w.length, 4)
  }

  test("decimal String entropy produces valid state") {
    val w = new SeedSequenceModule.SeedSequence(entropy = "12345").generateStateUInt32(4)
    assertEquals(w.length, 4)
  }

  test("invalid String entropy throws IllegalArgumentException") {
    intercept[IllegalArgumentException] {
      new SeedSequenceModule.SeedSequence(entropy = "not-a-number").generateStateUInt32(4)
    }
  }

  test("Float entropy throws IllegalArgumentException") {
    intercept[IllegalArgumentException] {
      new SeedSequenceModule.SeedSequence(entropy = 1.0f).generateStateUInt32(4)
    }
  }

  test("Double entropy throws IllegalArgumentException") {
    intercept[IllegalArgumentException] {
      new SeedSequenceModule.SeedSequence(entropy = 1.0).generateStateUInt32(4)
    }
  }

  test("unknown entropy type throws IllegalArgumentException") {
    intercept[IllegalArgumentException] {
      new SeedSequenceModule.SeedSequence(entropy = List("bad")).generateStateUInt32(4)
    }
  }

  // ============================================================================
  // spawnKey padding path (entropy < poolSize with spawnKey)
  // ============================================================================

  test("SeedSequence with spawnKey produces valid state") {
    val s = new SeedSequenceModule.SeedSequence(
      entropy  = BigInt(1),
      spawnKey = Seq(0)
    )
    val w = s.generateStateUInt32(4)
    assertEquals(w.length, 4)
  }

  test("SeedSequence with spawnKey differs from without") {
    val base  = SS(BigInt(99)).generateStateUInt32(4).toSeq
    val child = new SeedSequenceModule.SeedSequence(
      entropy  = BigInt(99),
      spawnKey = Seq(0)
    ).generateStateUInt32(4).toSeq
    assertNotEquals(base, child)
  }
}

package uni.data

import uni.*

/**
 * The matmul contract: the default `*@` is the pure tiled loop, pinned bit for bit;
 * BLAS is an opt-in that trades that for speed.
 *
 * `-Duni.mat.blas` is read once per JVM, so this suite can only observe the mode the
 * test JVM was started in — the default — and pins that. What it CAN exercise regardless
 * of mode are the two named escape hatches.
 */
class MatmulModeSuite extends munit.FunSuite:

  def bits(m: Mat[Double]): Seq[Long] = m.toArray.toSeq.map(java.lang.Double.doubleToRawLongBits)

  def mat(rows: Int, cols: Int, seed: Long): Mat[Double] =
    val rng = NumPyRNG(seed)
    MatD(rows, cols, Array.fill(rows * cols)(rng.randn()))

  test("the default *@ is the pure path, bit for bit") {
    // 128x128 is well past the old BLAS crossover (216 ops), so this is a real assertion
    // about the default rather than about tiny products.
    val a = mat(128, 128, 1)
    val b = mat(128, 128, 2)
    assertEquals(bits(a *@ b), bits(a.matmulPure(b)))
    assertEquals(bits(a.matmul(b)), bits(a.matmulPure(b)))
    assertEquals(bits(a.dot(b)), bits(a.matmulPure(b)))
  }

  test("the pure path is deterministic across runs") {
    // It parallelises over row tiles; each owns disjoint output rows, so the k-order per
    // cell is fixed. If this ever fails the pure path can no longer be pinned.
    val a     = mat(96, 96, 7)
    val b     = mat(96, 96, 8)
    val first = bits(a.matmulPure(b))
    for _ <- 1 to 8 do assertEquals(bits(a.matmulPure(b)), first)
  }

  test("the pure path's association order is a sequential k-sum from 0.0") {
    // The whole contract in one assertion: every cell equals a plain left fold over k,
    // starting from 0.0 -- which is what the Rust port reproduces.
    val a = mat(37, 53, 3)
    val b = mat(53, 29, 4)
    val c = a.matmulPure(b)
    for i <- 0 until 37; j <- 0 until 29 do
      var acc = 0.0
      var k = 0
      while k < 53 do { acc += a(i, k) * b(k, j); k += 1 }
      assertEquals(
        java.lang.Double.doubleToRawLongBits(c(i, j)),
        java.lang.Double.doubleToRawLongBits(acc),
        s"cell ($i, $j)",
      )
  }

  test("matmulBlas is a different algorithm, and says so") {
    // The reason BLAS is opt-in. A tolerance-based comparison passes; a bit comparison
    // does not, on any threaded or blocked BLAS. If a BLAS ever agreed bit for bit here
    // the mode switch would be moot -- flag it rather than let it pass silently.
    val a    = mat(300, 400, 5)
    val b    = mat(400, 300, 6)
    val pure = a.matmulPure(b)
    val blas = a.matmulBlas(b)
    // Mixed tolerance, as the Tprf3 fixture uses: a pure relative metric explodes on the
    // near-zero cells that cancellation produces in a 400-term sum.
    val worst = (0 until pure.size).map { i =>
      val (p, q) = (pure.at(i), blas.at(i))
      math.abs(p - q) / (1e-12 + 1e-9 * math.max(math.abs(p), math.abs(q)))
    }.max
    assert(worst < 1.0, s"BLAS and pure disagree beyond atol=1e-12/rtol=1e-9: ${worst}x")
    assertNotEquals(bits(pure), bits(blas), "BLAS agrees with the pure loop bit for bit here; the opt-in pins nothing")
  }

  test("views multiply through the stride equation, same bits as their copies") {
    val a = mat(40, 60, 9)
    val b = mat(60, 40, 10)
    assertEquals(bits(a.T.matmulPure(b.T)), bits(a.T.matCopy.matmulPure(b.T.matCopy)))
    val s = a.slice(5 until 30, 10 until 50)
    assertEquals(bits(s.matmulPure(b.slice(10 until 50, 0 until 40))),
                 bits(s.matCopy.matmulPure(b.slice(10 until 50, 0 until 40).matCopy)))
  }

  test("Float follows the same mode") {
    val a = MatF(64, 64, Array.tabulate(64 * 64)(i => ((i * 7) % 13).toFloat * 0.5f - 3f))
    val b = MatF(64, 64, Array.tabulate(64 * 64)(i => ((i * 5) % 11).toFloat * 0.25f - 1f))
    assertEquals((a *@ b).toArray.toSeq.map(java.lang.Float.floatToRawIntBits),
                 a.matmulPure(b).toArray.toSeq.map(java.lang.Float.floatToRawIntBits))
  }

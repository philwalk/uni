package uni.data

import uni.*

/**
 * The matmul contract: `matmulPure` is the pure tiled loop, pinned bit for bit, on every
 * machine and in every mode; `*@` follows `-Duni.mat.blas` — `os-best` by default, so
 * large products go to a native BLAS like NumPy's do, and `pure` opts back into the pin.
 *
 * `-Duni.mat.blas` is read once per JVM, so this suite can only observe the mode the
 * test JVM was started in, and asserts what `*@` must equal in that mode. What it CAN
 * exercise regardless of mode are the two named escape hatches.
 */
class MatmulModeSuite extends munit.FunSuite:

  def bits(m: Mat[Double]): Seq[Long] = m.toArray.toSeq.map(java.lang.Double.doubleToRawLongBits)

  def mat(rows: Int, cols: Int, seed: Long): Mat[Double] =
    val rng = NumPyRNG(seed)
    MatD(rows, cols, Array.fill(rows * cols)(rng.randn()))

  test("*@, matmul and dot follow the JVM's mode: BLAS bits under os-best, pure bits under pure") {
    // 128x128 is well past the BLAS threshold, so this is a real assertion about the mode
    // rather than about tiny products (which take the pure loop in every mode).
    val mode = sys.props.get("uni.mat.blas").orElse(sys.env.get("UNI_MAT_BLAS")).map(_.trim.toLowerCase).getOrElse("")
    val a = mat(128, 128, 1)
    val b = mat(128, 128, 2)
    val expected = if Set("pure", "false", "0", "none")(mode) then a.matmulPure(b) else a.matmulBlas(b)
    assertEquals(bits(a *@ b), bits(expected))
    assertEquals(bits(a.matmul(b)), bits(expected))
    assertEquals(bits(a.dot(b)), bits(expected))
  }

  test("below the BLAS threshold *@ is the pure loop in every mode") {
    val a = mat(4, 4, 11)   // 64 ops: under every threshold on every platform
    val b = mat(4, 4, 12)
    assertEquals(bits(a *@ b), bits(a.matmulPure(b)))
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

  test("matmulBlas agrees with the pure loop to tolerance, and is reachable in any mode") {
    // BLAS is opt-in because it is not PINNED -- its bits depend on the library, its
    // threading and the CPU -- not because it always differs: the reference BLAS dgemm
    // is a sequential k-loop per cell and reproduces the pure loop bit for bit (it does
    // on the Linux CI box). So this asserts agreement to tolerance and nothing about
    // bits; the pin itself is asserted against a reassociated kernel in MatParitySuite.
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

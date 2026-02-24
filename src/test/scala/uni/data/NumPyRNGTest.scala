package uni.data


class NumPyRNGTest extends munit.FunSuite {

  // Test various seed types: small, zero, large, and "common"
  val testSeeds = Seq(0L, 1L, 42L, 2046, 1844, 12345L, Long.MaxValue)

  testSeeds.foreach { seed =>
    test(s"Seed $seed: Native seed expansion should match PCG64DXSM Python") {
      val (expectedState, expectedInc) = getInitialStateFromPython(seed) // numpy 2.4.0 
      val (obtainedState, obtainedInc) = getInitialStateNumpy242(seed)   // native scala implementation
      assertEquals(obtainedState, expectedState, s"State mismatch for seed $seed") // 3. Assert they match exactly
      assertEquals(obtainedInc, expectedInc, s"Increment mismatch for seed $seed")
    }
  }
  test(s"negative Seed is rejected") {
    intercept[IllegalArgumentException] { getInitialStateNumpy242(-1L) }
  }
}

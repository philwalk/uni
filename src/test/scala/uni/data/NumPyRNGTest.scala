package uni.data


class NumPyRNGTest extends munit.FunSuite {

  // Pre-computed expected values from numpy 2.x PCG64DXSM for each seed.
  // Generated once via Python: np.random.default_rng(seed).bit_generator.state
  // and baked in so tests run without a Python dependency.
  val expectedValues: Map[Long, (BigInt, BigInt)] = Map(
    0L                   -> (BigInt("35399562948360463058890781895381311971"),  BigInt("87136372517582989555478159403783844777")),
    1L                   -> (BigInt("207833532711051698738587646355624148094"), BigInt("194290289479364712180083596243593368443")),
    42L                  -> (BigInt("274674114334540486603088602300644985544"), BigInt("332724090758049132448979897138935081983")),
    2046L                -> (BigInt("198982872981414863821223506010253655692"), BigInt("257458945125046561499864971702341220287")),
    1844L                -> (BigInt("156476218878725487961904583726022207393"), BigInt("93571803503315984792249732680669757431")),
    12345L               -> (BigInt("33261208707367790463622745601869196757"),  BigInt("268209174141567072605526753992732310247")),
    Long.MaxValue        -> (BigInt("276272391916443190492159920656047484577"), BigInt("151628497062642123835381847185082110735")),
  )

  expectedValues.foreach { case (seed, (expectedState, expectedInc)) =>
    test(s"Seed $seed: Native seed expansion should match PCG64DXSM numpy") {
      val (obtainedState, obtainedInc) = getInitialStateNumpy242(seed)
      assertEquals(obtainedState, expectedState, s"State mismatch for seed $seed")
      assertEquals(obtainedInc,   expectedInc,   s"Increment mismatch for seed $seed")
    }
  }

  test(s"negative Seed is rejected") {
    intercept[IllegalArgumentException] { getInitialStateNumpy242(-1L) }
  }
}

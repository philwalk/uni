package uni.data

import Mat.*
class TestRandValues extends munit.FunSuite {
  test("diagnostic: first raw values seed=0") {
    Mat.setSeed(0)
    val raw1 = Mat.globalRNG.nextLong()
    val raw2 = Mat.globalRNG.nextLong()
    val raw3 = Mat.globalRNG.nextLong()
    //println(s"First 3 raw longs: $raw1, $raw2, $raw3")
    val values = Array(raw1, raw2, raw3)
    val expected = Array(-6696874842932477345L, 4976686463289251617L, 755828109848996024L)
    values.zip(expected).zipWithIndex.foreach { case ((actual, exp), i) =>
      assertEquals(actual, exp, s"Mismatch at index $i")
    }
  }

  test("rand matches NumPy 2.4.0 PCG64DXSM for seed=42") {
    Mat.setSeed(42)
    val values = (0 until 10).map(_ => Mat.rand(1, 1)(0, 0))
    
    // Precomputed from NumPy 2.4.0:
    // import numpy as np
    // np.random.seed(42)
    // [np.random.rand() for _ in range(10)]
    val expected = Array(0.7739560485559633, 0.4388784397520523, 0.8585979199113825, 0.6973680290593639, 0.09417734788764953, 0.9756223516367559, 0.761139701990353, 0.7860643052769538, 0.12811363267554587, 0.45038593789556713)
    values.zip(expected).zipWithIndex.foreach { case ((actual, exp), i) =>
      assert(math.abs(actual - exp) < 1e-15, s"Mismatch at index $i: $actual vs $exp")
    }
  }

  test("rand matches NumPy 2.4.0 PCG64DXSM for seed=0") {
    Mat.setSeed(0)
    val values = (0 until 10).map(_ => Mat.rand(1, 1)(0, 0))
    
    // Precomputed from NumPy 2.4.0:
    // import numpy as np
    // np.random.seed(0)
    // [np.random.rand() for _ in range(10)]
    val expected = Array(0.6369616873214543, 0.2697867137638703, 0.04097352393619469, 0.016527635528529094, 0.8132702392002724, 0.9127555772777217, 0.6066357757671799, 0.7294965609839984, 0.5436249914654229, 0.9350724237877682)
    values.zip(expected).zipWithIndex.foreach { case ((actual, exp), i) =>
      assert(math.abs(actual - exp) < 1e-15, s"Mismatch at index $i: $actual vs $exp")
    }
  }

  test("rand matrix matches NumPy 2.4.0 PCG64DXSM for seed=12345") {
    Mat.setSeed(12345)
    val m = Mat.rand(3, 3)
    val values = m.flatten
    
    // Precomputed from NumPy 2.4.0:
    // import numpy as np
    // np.random.seed(12345)
    // np.random.rand(3, 3).flatten().tolist()
    val expected = Array(0.22733602246716966, 0.31675833970975287, 0.7973654573327341, 0.6762546707509746, 0.391109550601909, 0.33281392786638453, 0.5983087535871898, 0.18673418560371335, 0.6727560440146213)
    values.zip(expected).zipWithIndex.foreach { case ((actual, exp), i) =>
      assert(math.abs(actual - exp) < 1e-15, s"Mismatch at index $i: $actual vs $exp")
    }
  }

  test("uniform matches NumPy 2.4.0 PCG64DXSM for seed=42") {
    Mat.setSeed(12345)
    val values = (0 until 5).map(_ => Mat.uniform(5.0, 10.0, 1, 1)(0, 0))
    
    // Precomputed from NumPy 2.4.0:
    // import numpy as np
    // np.random.seed(42)
    // [np.random.uniform(5.0, 10.0) for _ in range(5)]
    val expected = Array(6.136680112335848, 6.583791698548764, 8.98682728666367, 8.381273353754873, 6.9555477530095455)
    values.zip(expected).zipWithIndex.foreach { case ((actual, exp), i) =>
      assert(math.abs(actual - exp) < 1e-15, s"Mismatch at index $i: $actual vs $exp")
    }
  }

  test("randn matches NumPy 2.4.0 PCG64DXSM for seed=42") {
    Mat.setSeed(42)
    val values = (0 until 10).map(_ => Mat.randn(1, 1)(0, 0))
    
    // Precomputed from NumPy 2.4.0:
    // import numpy as np
    // np.random.seed(42)
    // [np.random.randn() for _ in range(10)]
    val expected = Array(0.30471707975443135, -1.0399841062404955, 0.7504511958064572, 0.9405647163912139, -1.9510351886538364, -1.302179506862318, 0.12784040316728537, -0.3162425923435822, -0.016801157504288795, -0.85304392757358)
    values.zip(expected).zipWithIndex.foreach { case ((actual, exp), i) =>
      assert(math.abs(actual - exp) < 1e-15, s"Mismatch at index $i: $actual vs $exp")
    }
  }

  test("randn matches NumPy 2.4.0 PCG64DXSM for seed=0") {
    Mat.setSeed(0)
    val values = (0 until 10).map(_ => Mat.randn(1, 1)(0, 0))
    
    // Precomputed from NumPy 2.4.0:
    // import numpy as np
    // np.random.seed(0)
    // [np.random.randn() for _ in range(10)]
    val expected = Array(0.1257302210933933, -0.1321048632913019, 0.6404226504432821, 0.10490011715303971, -0.535669373161111, 0.36159505490948474, 1.3040000451301372, 0.9470809631292422, -0.7037352358069926, -1.2654214710460525)
    
    values.zip(expected).zipWithIndex.foreach { case ((actual, exp), i) =>
      assert(math.abs(actual - exp) < 1e-15, s"Mismatch at index $i: $actual vs $exp")
    }
  }

  test("randint matches NumPy 2.4.0 PCG64DXSM for seed=42") {
    Mat.setSeed(42)
    val values = (0 until 10).map(_ => Mat.randint(0, 100))
    
    // Precomputed from NumPy 2.4.0:
    // import numpy as np
    // np.random.seed(42)
    // [np.random.randint(0, 100) for _ in range(10)]
    val expected = Array(8, 77, 65, 43, 43, 85, 8, 69, 20, 9)
    values.zip(expected).zipWithIndex.foreach { case ((actual, exp), i) =>
      assertEquals(actual, exp, s"Mismatch at index $i")
    }
  }
}

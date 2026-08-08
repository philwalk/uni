package uni

import munit.FunSuite
import uni.data.*

/** Regression guard: Fractional[Big] must be discoverable via the implicit scope of
 *  object Big (where the opaque type is defined) without any explicit given import.
 *
 *  If `given Fractional[Big]` is ever moved back to package level in uni.data,
 *  `import uni.data.*` will NOT pick it up (Scala 3 wildcard imports skip anonymous
 *  givens), and this file will fail to compile.
 */
class BigImplicitScopeSuite extends FunSuite:
  test("Seq[Big].sum resolves Numeric[Big] via implicit scope without explicit given import") {
    val xs     = Seq(big("1.0"), big("2.0"), big("3.0"))
    val result = xs.sum
    assertEquals(result, big("6.0"))
  }

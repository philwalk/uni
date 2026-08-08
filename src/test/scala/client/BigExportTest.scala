package client

import munit.FunSuite
import uni.data.* // This now pulls in the TypeTest automatically

class BigExportTest extends FunSuite {

  test("Evidence is automatically exported via uni.data.*") {
    import scala.reflect.TypeTest
    
    // This will now find 'bigTypeTest' from the uni.data package
    val evidence = summon[TypeTest[Any, Big]]
    val x: Any = BigDecimal(10)
    assert(evidence.unapply(x).isDefined, "TypeTest should match a BigDecimal value")
    assert(evidence.unapply("not a big").isEmpty, "TypeTest should reject non-BigDecimal")
  }

  // NOTE: no way to determine whether the compiler issued a warning here, but this shows intent.
  test("Pattern match has no warnings and works correctly") {
    val x: Any = BigDecimal(10)
    val isMatch = x match
      case _: Big => true
      case _      => false
    
    assert(isMatch)
  }
}

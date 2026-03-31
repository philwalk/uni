package uni.data

import munit.FunSuite
import uni.data.Big.*

/** Verifies that scalars (Int, Long, Float, Double) can appear on the
 *  LEFT side of +, -, *, / with both Big and Mat[Double]. */
class LeftOpsSuite extends FunSuite:

  val b: Big       = Big(10)
  val m: Mat[Double] = MatD.row(2.0, 4.0, 6.0)

  // ── Int left-ops on Big ────────────────────────────────────────────────────
  test("Int + Big")  { assertEquals((2 + b).value,  BigDecimal(12)) }
  test("Int - Big")  { assertEquals((2 - b).value,  BigDecimal(-8)) }
  test("Int * Big")  { assertEquals((2 * b).value,  BigDecimal(20)) }
  test("Int / Big")  { assertEquals((2 / b).value,  BigDecimal("0.2")) }

  // ── Long left-ops on Big ───────────────────────────────────────────────────
  test("Long + Big") { assertEquals((2L + b).value, BigDecimal(12)) }
  test("Long - Big") { assertEquals((2L - b).value, BigDecimal(-8)) }
  test("Long * Big") { assertEquals((2L * b).value, BigDecimal(20)) }
  test("Long / Big") { assertEquals((2L / b).value, BigDecimal("0.2")) }

  // ── Double left-ops on Big ─────────────────────────────────────────────────
  test("Double + Big") { assertEquals((2.0 + b).value, BigDecimal(12)) }
  test("Double - Big") { assertEquals((2.0 - b).value, BigDecimal(-8)) }
  test("Double * Big") { assertEquals((2.0 * b).value, BigDecimal(20)) }
  test("Double / Big") { assertEquals((2.0 / b).value, BigDecimal("0.2")) }

  // ── Float left-ops on Big ──────────────────────────────────────────────────
  test("Float + Big") { assertEquals((2.0f + b).value, BigDecimal(12)) }
  test("Float - Big") { assertEquals((2.0f - b).value, BigDecimal(-8)) }
  test("Float * Big") { assertEquals((2.0f * b).value, BigDecimal(20)) }
  test("Float / Big") { assertEquals((2.0f / b).value, BigDecimal("0.2")) }

  // ── Int left-ops on Mat[Double] ────────────────────────────────────────────
  // (/ not tested: existing scalar/1x1 extension returns Double, conflicts with element-wise)
  // Numeric literals widen to Double, resolving via `extension (scalar: Double)`
  // in object Mat (companion scope) — found before Scala 3.7 E134 fires.
  test("Int + Mat")  { assertEqualsDouble((2 + m)(0,0),   4.0, 1e-12) }
  test("Int - Mat")  { assertEqualsDouble((2 - m)(0,0),   0.0, 1e-12) }
  test("Int * Mat")  { assertEqualsDouble((2 * m)(0,0),   4.0, 1e-12) }

  // ── Long left-ops on Mat[Double] ───────────────────────────────────────────
  test("Long + Mat") { assertEqualsDouble((2L + m)(0,0),  4.0, 1e-12) }
  test("Long - Mat") { assertEqualsDouble((2L - m)(0,0),  0.0, 1e-12) }
  test("Long * Mat") { assertEqualsDouble((2L * m)(0,0),  4.0, 1e-12) }

  // ── Double left-ops on Mat[Double] ─────────────────────────────────────────
  test("Double + Mat") { assertEqualsDouble((2.0 + m)(0,0),  4.0, 1e-12) }
  test("Double - Mat") { assertEqualsDouble((2.0 - m)(0,0),  0.0, 1e-12) }
  test("Double * Mat") { assertEqualsDouble((2.0 * m)(0,0),  4.0, 1e-12) }

  // ── Float left-ops on Mat[Double] ──────────────────────────────────────────
  test("Float + Mat") { assertEqualsDouble((2.0f + m)(0,0), 4.0, 1e-12) }
  test("Float - Mat") { assertEqualsDouble((2.0f - m)(0,0), 0.0, 1e-12) }
  test("Float * Mat") { assertEqualsDouble((2.0f * m)(0,0), 4.0, 1e-12) }

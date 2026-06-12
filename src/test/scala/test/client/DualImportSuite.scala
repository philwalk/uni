package test.client

// Simulates external client code that combines `import uni.*` (paths, IO),
// `import uni.data.*` (matrices), and a third-party wildcard import that
// exports its own `norm` (stand-in for breeze.linalg.*, as in vast/math/Pls.scala).
// MUST live outside package uni/uni.data: members of those packages resolve by
// definition precedence (beating imports), which masks import ambiguities that
// only external clients see.  Regression test for the E049 "Reference to norm
// is ambiguous" break: neither uni nor uni.data may export `norm` (or other
// common linear-algebra names) as importable package-level terms.

import munit.*

// stand-in for breeze.linalg: a library exporting `norm` as a plain function
object FakeLinAlg:
  def norm(v: Vector[Double]): Double = math.sqrt(v.map(x => x * x).sum)

import uni.*
import uni.data.*
import FakeLinAlg.*

class DualImportSuite extends FunSuite:

  test("third-party norm resolves; uni does not export a norm term") {
    // would be E049-ambiguous if uni or uni.data exported `norm`
    assertEqualsDouble(norm(Vector(3.0, 4.0)), 5.0, 1e-12)
  }

  test("Mat norm via method syntax; shape accessors with all imports") {
    val wi: MatD = MatD.ones(3, 1)
    val w2 = wi / wi.norm
    assertEqualsDouble(w2.norm, 1.0, 1e-12)
    assertEquals(w2.rows, 3)
    assertEquals(w2.cols, 1)
    assertEquals(w2.shape, (3, 1))
  }

  test("CVec/RVec typed dispatch works with all imports") {
    val v: CVecD = CVec(1.0, 2.0, 3.0)
    val r: RVecD = v.T
    assert(v.show.startsWith("3x1 CVec[Double]:"))
    assert(r.show.startsWith("1x3 RVec[Double]:"))
    assertEqualsDouble(v.norm, math.sqrt(14.0), 1e-12)
    val outer: MatD = v *@ r
    assertEquals(outer.shape, (3, 3))
  }

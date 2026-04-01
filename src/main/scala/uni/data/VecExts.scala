package uni.data

// VecExts.scala — CVec[T]/RVec[T] extension dispatch
//
// MUST be a separate file from Mat.scala.
// In Mat.scala, opaque types CVec[T]/RVec[T] are transparent — methods declared
// `def T: RVec[T]` there compile to `Mat[T]` return types in TASTY, breaking
// dispatch for callers outside uni.data.  In this file, CVec[T] and RVec[T]
// are properly opaque so return types are preserved.
//
// DISPATCH MECHANISM:
// CVec/RVec extensions are in object VecOps (defined here where opaque types are
// correct), which is then `export VecOps.*`-ed at package level (this file).
// This makes the re-exports available at:
//   - package scope (step 3) for callers in uni.data
//   - explicit import (step 2) via `import uni.data.*` for external callers
//
// SUBTYPE BOUND: CVec[T]/RVec[T] have `<: Mat[T]` upper bound, so CVecD/RVecD widen
// to MatD implicitly — no `.toMat` needed in user code.  Dispatch still works because
// `import Mat.*` is absent from user code (removed); VecOps is at step 3 (package
// scope) while Mat companion methods land at step 4 — VecOps wins.
//
// INLINE LIMITATION: Scala 3 does NOT index `inline def` extension methods at
// package level for extension method lookup.  All `*@` overloads use plain `def`
// via `matmulRt` (ClassTag runtime dispatch in Mat.scala).
//
// E161: ALL `*` overloads must be in the same file (this file).
// `*@` lives only here (CVec/RVec) or in Mat companion (plain Mat[T]); no E161.

import Mat.*
import scala.reflect.ClassTag

// Re-export `*` (broadcast-axis sentinel) from object Mat in THIS FILE so the
// forwarder is compiled where CVec/RVec are opaque.
export Mat.{`*`}

// scalar * CVec/RVec (Int/Long left-side; these need explicit extensions since
// CVec/RVec aren't AnyVal types and don't participate in literal widening)
extension (scalar: Int)    @annotation.targetName("intTimesCVecD")    def *(cv: CVec[Double]): CVec[Double] = CVec.fromMat(cv.asMat * scalar.toDouble)
extension (scalar: Long)   @annotation.targetName("longTimesCVecD")   def *(cv: CVec[Double]): CVec[Double] = CVec.fromMat(cv.asMat * scalar.toDouble)
extension (scalar: Int)    @annotation.targetName("intTimesRVecD")    def *(rv: RVec[Double]): RVec[Double] = RVec.fromMat(rv.asMat * scalar.toDouble)
extension (scalar: Long)   @annotation.targetName("longTimesRVecD")   def *(rv: RVec[Double]): RVec[Double] = RVec.fromMat(rv.asMat * scalar.toDouble)
// scalar */+/- Mat[Double]: handled by `extension (scalar: Double) def */+/-` in
// object Mat (companion scope).  Numeric literals widen to Double at call sites.
// scalar */+/-/÷ Big: explicit extensions in object Big (companion scope of Big).

// All CVec/RVec extension methods are in object VecOps so they can be
// exported from this file via `export VecOps.*`.  Methods defined inside
// object VecOps (which is in VecExts.scala, OUTSIDE object Mat) have the
// correct opaque return types in TASTY (RVec[T] not Mat[T]).
object VecOps:

  // ── RVec[T] extensions ──────────────────────────────────────────────────

  extension [T](rv: RVec[T])
    @annotation.targetName("voRvecUniqueTest")
    def rvecUniqueTest(s: T)(using ct: ClassTag[T], num: Numeric[T]): RVec[T] =
      val m: Mat[T] = rv.asMat * s; RVec.fromMat(m)
    @annotation.targetName("voRvecT")
    def T: CVec[T] = CVec.fromMat(Mat.matTransposeOf(rv.asMat))
    @annotation.targetName("voRvecMulScalar") @annotation.unused
    def *(s: T)(using ct: ClassTag[T], num: Numeric[T]): RVec[T] =
      val m: Mat[T] = rv.asMat * s; RVec.fromMat(m)
    @annotation.targetName("voRvecAddRVec") @annotation.unused
    def +(other: RVec[T])(using ct: ClassTag[T], ev: Numeric[T]): RVec[T] =
      val m: Mat[T] = rv.asMat + other.asMat; RVec.fromMat(m)
    @annotation.targetName("voRvecAddScalar")
    def +(s: T)(using ct: ClassTag[T], ev: Numeric[T]): RVec[T] =
      val m: Mat[T] = rv.asMat + s; RVec.fromMat(m)
    @annotation.targetName("voRvecSubRVec")
    def -(other: RVec[T])(using ct: ClassTag[T], ev: Numeric[T]): RVec[T] =
      val m: Mat[T] = rv.asMat - other.asMat; RVec.fromMat(m)
    @annotation.targetName("voRvecSubScalar")
    def -(s: T)(using ct: ClassTag[T], ev: Numeric[T]): RVec[T] =
      val m: Mat[T] = rv.asMat - s; RVec.fromMat(m)
    @annotation.targetName("voRvecMulRVec")
    def *(other: RVec[T])(using ct: ClassTag[T], ev: Numeric[T]): RVec[T] =
      val m: Mat[T] = rv.asMat * other.asMat; RVec.fromMat(m)
    @annotation.targetName("voRvecDivRVec")
    def /(other: RVec[T])(using ct: ClassTag[T], ev: Fractional[T]): RVec[T] =
      val m: Mat[T] = rv.asMat / other.asMat; RVec.fromMat(m)
    @annotation.targetName("voRvecDivMat")
    def /(other: Mat[T])(using ct: ClassTag[T], ev: Fractional[T]): RVec[T] =
      val m: Mat[T] = rv.asMat / other; RVec.fromMat(m)
    @annotation.targetName("voRvecSubMat")
    def -(other: Mat[T])(using ct: ClassTag[T], ev: Numeric[T]): RVec[T] =
      val m: Mat[T] = rv.asMat - other; RVec.fromMat(m)
    @annotation.targetName("voRvecAddMat")
    def +(other: Mat[T])(using ct: ClassTag[T], ev: Numeric[T]): RVec[T] =
      val m: Mat[T] = rv.asMat + other; RVec.fromMat(m)
    @annotation.targetName("voRvecNeg")
    def unary_-(using ct: ClassTag[T], ev: Numeric[T]): RVec[T] =
      val m: Mat[T] = -rv.asMat; RVec.fromMat(m)
    @annotation.targetName("voRvecTimesMat")
    def *@(mat: Mat[T])(using ct: ClassTag[T]): RVec[T] =
      val m: Mat[T] = rv.asMat.matmulRt(mat); RVec.fromMat(m)
    @annotation.targetName("voRvecDotCvec") @annotation.unused
    def *@(cv: CVec[T])(using ClassTag[T], Numeric[T]): T =
      rv.asMat.matmulRt(cv.asMat).at(0, 0)
    @annotation.targetName("voRvecDotRvec") @annotation.unused
    def *@(other: RVec[T])(using ClassTag[T], Numeric[T]): T =
      rv.asMat.matmulRt(other.asMat.transpose).at(0, 0)
    @annotation.targetName("voRvecMean")
    def mean(using ct: ClassTag[T], ev: Fractional[T]): T = rv.toMat.mean
    @annotation.targetName("voRvecShow")
    def show(using frac: Fractional[T], ct: ClassTag[T]): String =
      val m = rv.toMat
      formatMatrix(m.tdata, m.rows, m.cols, m.offset, m.rs, m.cs,
        m.typeName(using ct), frac.toDouble, _.toString, fmt = None, label = "RVec")

  // ── CVec[T] extensions ──────────────────────────────────────────────────

  extension [T](cv: CVec[T])
    @annotation.targetName("voCvecT")
    def T: RVec[T] = RVec.fromMat(Mat.matTransposeOf(cv.asMat))
    @annotation.targetName("voCvecMulScalar")
    def *(s: T)(using ct: ClassTag[T], num: Numeric[T]): CVec[T] =
      val m: Mat[T] = cv.asMat * s; CVec.fromMat(m)
    @annotation.targetName("voCvecDivScalar")
    def /(s: T)(using ct: ClassTag[T], frac: Fractional[T]): CVec[T] =
      val m: Mat[T] = cv.asMat / s; CVec.fromMat(m)
    @annotation.targetName("voCvecAddCVec")
    def +(other: CVec[T])(using ct: ClassTag[T], ev: Numeric[T]): CVec[T] =
      val m: Mat[T] = cv.asMat + other.asMat; CVec.fromMat(m)
    @annotation.targetName("voCvecAddScalar")
    def +(s: T)(using ct: ClassTag[T], ev: Numeric[T]): CVec[T] =
      val m: Mat[T] = cv.asMat + s; CVec.fromMat(m)
    @annotation.targetName("voCvecSubCVec")
    def -(other: CVec[T])(using ct: ClassTag[T], ev: Numeric[T]): CVec[T] =
      val m: Mat[T] = cv.asMat - other.asMat; CVec.fromMat(m)
    @annotation.targetName("voCvecSubScalar")
    def -(s: T)(using ct: ClassTag[T], ev: Numeric[T]): CVec[T] =
      val m: Mat[T] = cv.asMat - s; CVec.fromMat(m)
    @annotation.targetName("voCvecNeg")
    def unary_-(using ct: ClassTag[T], ev: Numeric[T]): CVec[T] =
      val m: Mat[T] = -cv.asMat; CVec.fromMat(m)
    @annotation.targetName("voCvecMean")
    def mean(using ct: ClassTag[T], ev: Fractional[T]): T = cv.toMat.mean
    @annotation.targetName("voCvecTimesMat")
    def *@(mat: Mat[T])(using ct: ClassTag[T]): RVec[T] =
      val m: Mat[T] = cv.asMat.transpose.matmulRt(mat); RVec.fromMat(m)
    @annotation.targetName("voCvecDotCvec") @annotation.unused
    def *@(other: CVec[T])(using ClassTag[T], Numeric[T]): T =
      cv.asMat.transpose.matmulRt(other.asMat).at(0, 0)
    @annotation.targetName("voCvecOuterRvec") @annotation.unused
    def *@(rv: RVec[T])(using ClassTag[T], Numeric[T]): Mat[T] =
      cv.asMat.matmulRt(rv.asMat)
    @annotation.targetName("voCvecShow")
    def show(using frac: Fractional[T], ct: ClassTag[T]): String =
      val m = cv.toMat
      formatMatrix(m.tdata, m.rows, m.cols, m.offset, m.rs, m.cs,
        m.typeName(using ct), frac.toDouble, _.toString, fmt = None, label = "CVec")

// Re-export CVec/RVec extensions from VecOps at package level so that
// `import uni.data.*` (step 2, explicit) beats companion-scope Mat methods
// (step 5) for cross-package callers.  Must be in THIS file (VecExts.scala)
// so all `*` overloads share the same top-level definition group (E161).
export VecOps.*

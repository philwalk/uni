package uni.data

// VecExts.scala — CVec[T]/RVec[T] companions and extension dispatch
//
// MUST be a separate file from Mat.scala.
// In Mat.scala, opaque types CVec[T]/RVec[T] are transparent — methods declared
// `def T: RVec[T]` there compile to `Mat[T]` return types in TASTY, breaking
// dispatch for callers outside uni.data.  In this file, CVec[T] and RVec[T]
// are properly opaque so return types are preserved.  For the same reason the
// companions `object CVec`/`object RVec` live HERE (package level), not nested
// inside object Mat: their factory return types stay CVec[T]/RVec[T] in TASTY.
// Construction casts Mat[T] → CVec[T]/RVec[T] through the private[data]
// bridges Mat.mkCVec/Mat.mkRVec (the cast is only legal inside object Mat).
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
// Same-name candidates at the same step (e.g. VecOps `rows` vs the package-level
// `export Mat.rows`) resolve to the direct CVec[T]/RVec[T] receiver, which beats
// the widened Mat[T] receiver — provided the extension blocks here stay bare [T]
// (a [T: ClassTag] block would tie specificity and become ambiguous).
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

// Exported here (not Mat.scala) because VecOps defines CVec/RVec overloads of
// the same names and E161 requires all overloads of a top-level name to live
// in the same definition group (file).
export Mat.{rows, cols, shape, shapes, size, isEmpty, transposed, isContiguous, apply}

// ── CVec companion (package level — factory TASTY types stay CVec[T]) ────────
object CVec:
  def apply[T: ClassTag](elems: T*): CVec[T] =
    Mat.mkCVec(Mat.create(elems.toArray, elems.length, 1))
  def zeros[T: ClassTag: Fractional](n: Int): CVec[T] = Mat.mkCVec(Mat.zeros[T](n, 1))
  def ones[T: ClassTag: Fractional](n: Int): CVec[T]  = Mat.mkCVec(Mat.ones[T](n, 1))
  def fromArray[T: ClassTag](arr: Array[T]): CVec[T] =
    Mat.mkCVec(Mat.create(arr.clone(), arr.length, 1))
  def fromMat[T](m: Mat[T]): CVec[T] =
    require(m.cols == 1 || m.rows == 1,
      s"fromMat requires a vector-shaped matrix, got ${m.rows}×${m.cols}")
    Mat.mkCVec(if m.cols == 1 then m else Mat.matTransposeOf(m))

// ── RVec companion (package level — factory TASTY types stay RVec[T]) ────────
object RVec:
  def apply[T: ClassTag](elems: T*): RVec[T] =
    Mat.mkRVec(Mat.create(elems.toArray, 1, elems.length))
  def zeros[T: ClassTag: Fractional](n: Int): RVec[T] = Mat.mkRVec(Mat.zeros[T](1, n))
  def ones[T: ClassTag: Fractional](n: Int): RVec[T]  = Mat.mkRVec(Mat.ones[T](1, n))
  def fromArray[T: ClassTag](arr: Array[T]): RVec[T] =
    Mat.mkRVec(Mat.create(arr.clone(), 1, arr.length))
  def fromMat[T](m: Mat[T]): RVec[T] =
    require(m.rows == 1 || m.cols == 1,
      s"fromMat requires a vector-shaped matrix, got ${m.rows}×${m.cols}")
    Mat.mkRVec(if m.rows == 1 then m else Mat.matTransposeOf(m))

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
    @annotation.targetName("voRvecDivScalar")
    def /(s: T)(using ct: ClassTag[T], ev: Fractional[T]): RVec[T] =
      val m: Mat[T] = rv.asMat / s; RVec.fromMat(m)
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
    def mean(using ct: ClassTag[T], ev: Fractional[T]): T = rv.asMat.mean
    @annotation.targetName("voRvecShow")
    def show(using frac: Fractional[T], ct: ClassTag[T]): String =
      val m = rv.asMat
      formatMatrix(m.tdata, m.rows, m.cols, m.offset, m.rs, m.cs,
        m.typeName(using ct), frac.toDouble, _.toString, fmt = None, label = "RVec")

  // RVec accessors/converters (moved from the old nested companion; they
  // delegate via asMat — outside object Mat there is no transparency, so no
  // recursion hazard and no need for raw MatData field access)
  extension [T](rv: RVec[T])
    @annotation.targetName("rvecAsMat")
    def asMat: Mat[T] = rv                  // widening via the <: Mat[T] bound
    @annotation.targetName("rvecToMat")
    def toMat: Mat[T] = rv
    @annotation.targetName("rvecSize")
    def size: Int = rv.asMat.size
    @annotation.targetName("rvecApply")
    def apply(j: Int): T = rv.asMat.at(0, j)
    @annotation.targetName("rvecApply2d")
    def apply(row: Int, col: Int): T = rv.asMat.at(row, col)
    @annotation.targetName("rvecToArray")
    def toArray(using ClassTag[T]): Array[T] = rv.asMat.toArray
    @annotation.targetName("rvecFlatten")
    def flatten(using ClassTag[T]): Array[T] = rv.asMat.flatten
    // NO `norm` here: rv.norm resolves to Mat's norm via companion implicit
    // scope (RVec[T] <: Mat[T]).  A VecOps norm would become an importable
    // term of package uni.data (via `export VecOps.*`), making `norm` E049-
    // ambiguous for clients that also wildcard-import e.g. breeze.linalg.*
    @annotation.targetName("rvecShape")
    def shape: (Int, Int) = rv.asMat.shape
    @annotation.targetName("rvecRows")
    def rows: Int = rv.asMat.rows
    @annotation.targetName("rvecCols")
    def cols: Int = rv.asMat.cols
    @annotation.targetName("rvecReshape")
    def reshape(rows: Int, cols: Int)(using ClassTag[T]): Mat[T] =
      rv.asMat.reshape(rows, cols)
    @annotation.targetName("rvecIsEmpty")
    def isEmpty: Boolean = rv.asMat.isEmpty
    @annotation.targetName("rvecToColVec")
    def toColVec: CVec[T] = rv.T
    @annotation.targetName("rvecUpdate")
    def update(j: Int, value: T): Unit =
      val m = rv.asMat
      m.tdata(m.offset + j * m.cs) = value

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
    def mean(using ct: ClassTag[T], ev: Fractional[T]): T = cv.asMat.mean
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
      val m = cv.asMat
      formatMatrix(m.tdata, m.rows, m.cols, m.offset, m.rs, m.cs,
        m.typeName(using ct), frac.toDouble, _.toString, fmt = None, label = "CVec")

  // CVec accessors/converters (moved from the old nested companion)
  extension [T](cv: CVec[T])
    @annotation.targetName("cvecAsMat")
    def asMat: Mat[T] = cv                  // widening via the <: Mat[T] bound
    @annotation.targetName("cvecToMat")
    def toMat: Mat[T] = cv
    @annotation.targetName("cvecSize")
    def size: Int = cv.asMat.size
    @annotation.targetName("cvecApply")
    def apply(i: Int): T = cv.asMat.at(i, 0)
    @annotation.targetName("cvecApply2d")
    def apply(row: Int, col: Int): T = cv.asMat.at(row, col)
    @annotation.targetName("cvecToArray")
    def toArray(using ClassTag[T]): Array[T] = cv.asMat.toArray
    @annotation.targetName("cvecFlatten")
    def flatten(using ClassTag[T]): Array[T] = cv.asMat.flatten
    // NO `norm` here — see rvecFlatten note above (E049 with breeze et al.)
    @annotation.targetName("cvecShape")
    def shape: (Int, Int) = cv.asMat.shape
    @annotation.targetName("cvecRows")
    def rows: Int = cv.asMat.rows
    @annotation.targetName("cvecCols")
    def cols: Int = cv.asMat.cols
    @annotation.targetName("cvecReshape")
    def reshape(rows: Int, cols: Int)(using ClassTag[T]): Mat[T] =
      cv.asMat.reshape(rows, cols)
    @annotation.targetName("cvecIsEmpty")
    def isEmpty: Boolean = cv.asMat.isEmpty
    @annotation.targetName("cvecToRowVec")
    def toRowVec: RVec[T] = cv.T
    @annotation.targetName("cvecUpdate")
    def update(i: Int, value: T): Unit =
      val m = cv.asMat
      m.tdata(m.offset + i * m.rs) = value

// Re-export CVec/RVec extensions from VecOps at package level so that
// `import uni.data.*` (step 2, explicit) beats companion-scope Mat methods
// (step 5) for cross-package callers.  Must be in THIS file (VecExts.scala)
// so all `*` overloads share the same top-level definition group (E161).
export VecOps.*

// Re-export the unboxed Mat[Double] indexing facade (MatDOps.scala) here, in
// the SAME definition group as `export Mat.{… apply}` and `export VecOps.*`:
// E161 requires all re-exported overloads of `apply`/`update` to live together.
// External callers (import uni.data.*) select the Mat[Double] overloads by
// receiver specificity → unboxed m(i,j) / m(i,j)=v.
export MatDOps.*

// Mat[Float] twin of MatDOps, generated into sourceManaged by the build.sbt
// sourceGenerator (see MatDOps.scala header).  Same export-group requirement.
export MatFOps.*

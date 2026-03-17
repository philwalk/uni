package uni.io

import uni.data.Mat
import uni.data.Mat.*
import uni.io.FileOps.MatResult
import scala.collection.mutable
import scala.reflect.ClassTag

enum AggOp:
  case Mean, Sum, Min, Max, Count, Std

enum JoinType:
  case Inner, Left, Right

object matResultOps:

  private def extractCol[T: ClassTag](m: Mat[T], colIdx: Int): Mat[T] =
    val arr = Array.ofDim[T](m.rows)
    var r = 0; while r < m.rows do { arr(r) = m(r, colIdx); r += 1 }
    Mat.create(arr, m.rows, 1)

  private def extractRows[T: ClassTag](m: Mat[T], rowIdxs: Seq[Int]): Mat[T] =
    if rowIdxs.isEmpty then Mat.create(Array.ofDim[T](0), 0, m.cols)
    else
      val arr = Array.ofDim[T](rowIdxs.length * m.cols)
      rowIdxs.zipWithIndex.foreach { (orig, newR) =>
        var c = 0; while c < m.cols do { arr(newR * m.cols + c) = m(orig, c); c += 1 }
      }
      Mat.create(arr, rowIdxs.length, m.cols)

  extension [T: ClassTag](mr: MatResult[T])(using frac: Fractional[T], @annotation.unused ord: Ordering[T])

    /** Group rows by distinct values in `keyCol` and aggregate all other columns
     *  with a single operation.  Returns `MatResult[Double]`.
     *
     *  {{{
     *  val means = result.groupBy("sector")              // Mean of all non-key cols
     *  val sums  = result.groupBy("sector", AggOp.Sum)
     *  }}}
     */
    def groupBy(keyCol: String, op: AggOp = AggOp.Mean): MatResult[Double] =
      val nonKeyCols = mr.headers.filterNot(_ == keyCol)
      mr.groupBy(keyCol, nonKeyCols.map(_ -> op).toMap)

    /** Group rows by `keyCol`, applying a per-column aggregation operation.
     *
     *  {{{
     *  result.groupBy("sector", Map("price" -> AggOp.Mean, "vol" -> AggOp.Sum))
     *  }}}
     *
     *  Output headers: `keyCol` followed by `"<col>_<op>"` for each aggregated column.
     */
    def groupBy(keyCol: String, aggOps: Map[String, AggOp]): MatResult[Double] =
      val keyIdx = mr.columnIndex.getOrElse(keyCol,
        throw new NoSuchElementException(s"groupBy: key column '$keyCol' not found in ${mr.headers}"))

      val groups = mutable.LinkedHashMap[Double, mutable.ArrayBuffer[Int]]()
      var r = 0
      while r < mr.mat.rows do
        val key = frac.toDouble(mr.mat(r, keyIdx))
        groups.getOrElseUpdate(key, mutable.ArrayBuffer.empty) += r
        r += 1

      val aggCols    = aggOps.keys.toVector
      val outHeaders = keyCol +: aggCols.map(c => s"${c}_${aggOps(c).toString.toLowerCase}")
      val outWidth   = outHeaders.length
      val flatOut    = mutable.ArrayBuffer[Double]()

      for (key, rowIdxBuf) <- groups do
        flatOut += key
        val subMat = extractRows(mr.mat, rowIdxBuf.toSeq)
        for colName <- aggCols do
          val cIdx   = mr.columnIndex(colName)
          val colVec = extractCol(subMat, cIdx)
          flatOut += (aggOps(colName) match
            case AggOp.Mean  => frac.toDouble(colVec.mean)
            case AggOp.Sum   => frac.toDouble(colVec.sum)
            case AggOp.Min   => frac.toDouble(colVec.min)
            case AggOp.Max   => frac.toDouble(colVec.max)
            case AggOp.Count => rowIdxBuf.length.toDouble
            case AggOp.Std   => frac.toDouble(colVec.std))

      val arr = flatOut.toArray
      MatResult(outHeaders, Mat.create(arr, arr.length / outWidth, outWidth))

    /** Join two `MatResult` tables on a numeric key column.
     *
     *  {{{
     *  prices.merge(volumes, on = "id")                        // inner (default)
     *  prices.merge(volumes, on = "id", how = JoinType.Left)
     *  }}}
     *
     *  - The `on` column appears once in the output.
     *  - Columns that exist in both tables (other than `on`) are suffixed `_x` / `_y`.
     *  - Missing values in outer joins are filled with `Double.NaN`.
     */
    def merge(right: MatResult[T], on: String, how: JoinType = JoinType.Inner): MatResult[Double] =
      val left    = mr
      val lKeyIdx = left.columnIndex.getOrElse(on,
        throw new NoSuchElementException(s"merge: '$on' not found in left headers ${left.headers}"))
      val rKeyIdx = right.columnIndex.getOrElse(on,
        throw new NoSuchElementException(s"merge: '$on' not found in right headers ${right.headers}"))

      val buildMap = mutable.HashMap[Double, mutable.ArrayBuffer[Int]]()
      var r = 0
      while r < right.mat.rows do
        val key = frac.toDouble(right.mat(r, rKeyIdx))
        buildMap.getOrElseUpdate(key, mutable.ArrayBuffer.empty) += r
        r += 1

      val lCols    = left.headers.zipWithIndex.filterNot(_._2 == lKeyIdx)
      val rCols    = right.headers.zipWithIndex.filterNot(_._2 == rKeyIdx)
      val rNames   = rCols.map(_._1).toSet
      val lNames   = lCols.map(_._1).toSet
      val lColsOut = lCols.map { (n, i) => (if rNames(n) then s"${n}_x" else n, i) }
      val rColsOut = rCols.map { (n, i) => (if lNames(n) then s"${n}_y" else n, i) }

      val outHeaders   = on +: (lColsOut.map(_._1) ++ rColsOut.map(_._1))
      val outWidth     = outHeaders.length
      val flatOut      = mutable.ArrayBuffer[Double]()
      val rightMatched = if how == JoinType.Right then mutable.BitSet() else null

      var lRow = 0
      while lRow < left.mat.rows do
        val key     = frac.toDouble(left.mat(lRow, lKeyIdx))
        val matches = buildMap.getOrElse(key, mutable.ArrayBuffer.empty)

        if matches.nonEmpty then
          for rRow <- matches do
            if rightMatched != null then rightMatched += rRow
            flatOut += key
            for (_, li) <- lColsOut do flatOut += frac.toDouble(left.mat(lRow, li))
            for (_, ri) <- rColsOut do flatOut += frac.toDouble(right.mat(rRow, ri))
        else if how == JoinType.Left then
          flatOut += key
          for (_, li) <- lColsOut do flatOut += frac.toDouble(left.mat(lRow, li))
          for _ <- rColsOut do flatOut += Double.NaN

        lRow += 1

      if how == JoinType.Right then
        var rRow = 0
        while rRow < right.mat.rows do
          if !rightMatched.contains(rRow) then
            flatOut += frac.toDouble(right.mat(rRow, rKeyIdx))
            for _ <- lColsOut do flatOut += Double.NaN
            for (_, ri) <- rColsOut do flatOut += frac.toDouble(right.mat(rRow, ri))
          rRow += 1

      val arr = flatOut.toArray
      MatResult(outHeaders, Mat.create(arr, arr.length / outWidth, outWidth))

package uni.data

import scala.reflect.ClassTag

/** Pandas/NumPy-style ordering and descriptive statistics (sort/argsort/
 *  nlargest/nsmallest/unique/valueCounts, diff/shift/pct_change,
 *  percentile/median, describe, idxmin/idxmax, histogram, rolling), relocated
 *  from Mat.scala (file-split campaign). `export MatPandasOps.*` inside
 *  `object Mat` makes them companion members again, so implicit scope and
 *  `import Mat.*` dispatch are identical for clients.
 */
private[data] object MatPandasOps {
  // `Mat` (the opaque type) is imported as a direct member of object Mat — the
  // package-level forwarder cannot be used here (cyclic with the export inside
  // object Mat); see MatMathOps.scala.
  import Mat.{Mat, nanFill}

  extension [T](@annotation.unused m: Mat[T])(using @annotation.unused ct: ClassTag[T])

    def idxmin(axis: Int)(using ord: Ordering[T]): Mat[Int] =
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      if axis == 0 then
        val result = Array.ofDim[Int](m.cols)
        var j = 0
        while j < m.cols do
          var best = 0; var bestVal = m(0, j); var i = 1
          while i < m.rows do
            if ord.lt(m(i, j), bestVal) then { bestVal = m(i, j); best = i }
            i += 1
          result(j) = best; j += 1
        Mat.create(result, 1, m.cols)
      else
        val result = Array.ofDim[Int](m.rows)
        var i = 0
        while i < m.rows do
          var best = 0; var bestVal = m(i, 0); var j = 1
          while j < m.cols do
            if ord.lt(m(i, j), bestVal) then { bestVal = m(i, j); best = j }
            j += 1
          result(i) = best; i += 1
        Mat.create(result, m.rows, 1)

    def idxmax(axis: Int)(using ord: Ordering[T]): Mat[Int] =
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      if axis == 0 then
        val result = Array.ofDim[Int](m.cols)
        var j = 0
        while j < m.cols do
          var best = 0; var bestVal = m(0, j); var i = 1
          while i < m.rows do
            if ord.gt(m(i, j), bestVal) then { bestVal = m(i, j); best = i }
            i += 1
          result(j) = best; j += 1
        Mat.create(result, 1, m.cols)
      else
        val result = Array.ofDim[Int](m.rows)
        var i = 0
        while i < m.rows do
          var best = 0; var bestVal = m(i, 0); var j = 1
          while j < m.cols do
            if ord.gt(m(i, j), bestVal) then { bestVal = m(i, j); best = j }
            j += 1
          result(i) = best; i += 1
        Mat.create(result, m.rows, 1)

    def sort(axis: Int = -1)(using ord: Ordering[T]): Mat[T] = {
      if axis == -1 then
        // Sort flattened
        val flat = m.flatten
        val sorted = flat.sorted
        Mat.create(sorted, 1, flat.length)
      else
        require(axis == 0 || axis == 1, s"axis must be -1, 0 or 1, got $axis")
        val result = Array.ofDim[T](m.rows * m.cols)
        if axis == 0 then
          // Sort each column independently
          var j = 0
          while j < m.cols do
            val col = Array.tabulate(m.rows)(i => m(i, j))
            val sorted = col.sorted
            var i = 0
            while i < m.rows do
              result(i * m.cols + j) = sorted(i)
              i += 1
            j += 1
        else
          // Sort each row independently
          var i = 0
          while i < m.rows do
            val row = Array.tabulate(m.cols)(j => m(i, j))
            val sorted = row.sorted
            var j = 0
            while j < m.cols do
              result(i * m.cols + j) = sorted(j)
              j += 1
            i += 1
        Mat.create(result, m.rows, m.cols)
    }

    def nlargest(n: Int)(using ord: Ordering[T]): Mat[T] =
      val flat = m.flatten.sorted(using ord.reverse).take(math.min(n, m.size))
      Mat.create(flat, 1, flat.length)

    def nsmallest(n: Int)(using ord: Ordering[T]): Mat[T] =
      val flat = m.flatten.sorted.take(math.min(n, m.size))
      Mat.create(flat, 1, flat.length)

    def between(lo: T, hi: T)(using ord: Ordering[T]): Mat[Boolean] =
      m.map(x => ord.gteq(x, lo) && ord.lteq(x, hi))

    def argsort(axis: Int = -1)(using ord: Ordering[T]): Mat[Int] = {
      if axis == -1 then
        val flat = m.flatten
        val indices = flat.indices.sortBy(flat(_)).toArray
        Mat.create(indices, 1, indices.length)
      else
        require(axis == 0 || axis == 1, s"axis must be -1, 0 or 1, got $axis")
        val result = Array.ofDim[Int](m.rows * m.cols)
        if axis == 0 then
          var j = 0
          while j < m.cols do
            val col = Array.tabulate(m.rows)(i => m(i, j))
            val sorted = col.indices.sortBy(col(_)).toArray
            var i = 0
            while i < m.rows do
              result(i * m.cols + j) = sorted(i)
              i += 1
            j += 1
        else
          var i = 0
          while i < m.rows do
            val row = Array.tabulate(m.cols)(j => m(i, j))
            val sorted = row.indices.sortBy(row(_)).toArray
            var j = 0
            while j < m.cols do
              result(i * m.cols + j) = sorted(j)
              j += 1
            i += 1
        Mat.create(result, m.rows, m.cols)
    }

    def unique(using ord: Ordering[T]): (Array[T], Array[Int]) = {
      val flat = m.flatten
      val sorted = flat.sorted
      val vals = scala.collection.mutable.ArrayBuffer[T]()
      val counts = scala.collection.mutable.ArrayBuffer[Int]()
      var i = 0
      while i < sorted.length do
        if vals.isEmpty || ord.compare(sorted(i), vals.last) != 0 then
          vals += sorted(i)
          counts += 1
        else
          counts(counts.length - 1) += 1
        i += 1
      (vals.toArray, counts.toArray)
    }

    def nunique(using ord: Ordering[T]): Int = unique._1.length

    def valueCounts(using ord: Ordering[T]): Array[(T, Int)] =
      val (vals, counts) = unique
      vals.zip(counts).sortBy(-_._2)

    /** NumPy: np.diff(m) - first differences of flattened matrix */
    def diff(using num: Numeric[T]): Mat[T] = {
      val flat = m.flatten
      val result = Array.ofDim[T](flat.length - 1)
      var i = 0
      while i < result.length do
        result(i) = num.minus(flat(i + 1), flat(i))
        i += 1
      Mat.create(result, 1, result.length)
    }

    /** NumPy: np.diff(m, axis=0) - first differences along axis */
    def diff(axis: Int)(using num: Numeric[T]): Mat[T] = {
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      if axis == 0 then
        require(m.rows > 1, "diff axis=0 requires at least 2 rows")
        val result = Array.ofDim[T]((m.rows - 1) * m.cols)
        var i = 0
        while i < m.rows - 1 do
          var j = 0
          while j < m.cols do
            result(i * m.cols + j) = num.minus(m(i + 1, j), m(i, j))
            j += 1
          i += 1
        Mat.create(result, m.rows - 1, m.cols)
      else
        require(m.cols > 1, "diff axis=1 requires at least 2 cols")
        val result = Array.ofDim[T](m.rows * (m.cols - 1))
        var i = 0
        while i < m.rows do
          var j = 0
          while j < m.cols - 1 do
            result(i * (m.cols - 1) + j) = num.minus(m(i, j + 1), m(i, j))
            j += 1
          i += 1
        Mat.create(result, m.rows, m.cols - 1)
    }

    def shift(n: Int, fill: T, axis: Int = 0): Mat[T] =
      val result = Array.fill(m.rows * m.cols)(fill)
      if axis == 0 then
        val srcRow = if n >= 0 then 0     else -n
        val dstRow = if n >= 0 then n     else  0
        val nRows  = m.rows - math.abs(n)
        if nRows > 0 then
          var i = 0
          while i < nRows do
            var j = 0
            while j < m.cols do
              result((dstRow + i) * m.cols + j) = m(srcRow + i, j)
              j += 1
            i += 1
      else
        val srcCol = if n >= 0 then 0 else -n
        val dstCol = if n >= 0 then n else  0
        val nCols  = m.cols - math.abs(n)
        if nCols > 0 then
          var i = 0
          while i < m.rows do
            var j = 0
            while j < nCols do
              result(i * m.cols + (dstCol + j)) = m(i, srcCol + j)
              j += 1
            i += 1
      Mat.create(result, m.rows, m.cols)

    def pct_change(axis: Int = 0)(using frac: Fractional[T], elem: MatElem[T]): Mat[T] =
      val fill = nanFill[T]
      val prev = m.shift(1, fill, axis)
      val result = Array.ofDim[T](m.rows * m.cols)
      var i = 0
      while i < m.rows do
        var j = 0
        while j < m.cols do
          val p = prev(i, j)
          val pd = frac.toDouble(p)
          result(i * m.cols + j) =
            if pd.isNaN || pd == 0.0 then fill
            else frac.div(frac.minus(m(i, j), p), p)
          j += 1
        i += 1
      Mat.create(result, m.rows, m.cols)

    private def percentileOf(arr: Array[T], p: Double)(using frac: Fractional[T], elem: MatElem[T]): T = {
      require(p >= 0 && p <= 100, s"percentile must be in [0,100], got $p")  // guard here
      val sorted = arr.sorted(using summon[Ordering[T]])
      val n = sorted.length
      if n == 1 then
        sorted(0)
      else
        val idx  = (p / 100.0) * (n - 1)
        val lo   = idx.toInt
        val hi   = math.min(lo + 1, n - 1)
        val frac2 = idx - lo
        elem.fromDouble(frac.toDouble(sorted(lo)) + frac2 * (frac.toDouble(sorted(hi)) - frac.toDouble(sorted(lo))))
    }

    /** NumPy: np.percentile(m, p) - p-th percentile of all elements, p in [0,100] */
    def percentile(p: Double)(using frac: Fractional[T], elem: MatElem[T]): T =
      percentileOf(m.flatten, p)

    /** NumPy: np.median(m) - median of all elements */
    def median(using frac: Fractional[T], elem: MatElem[T]): T =
      percentileOf(m.flatten, 50.0)

    /** NumPy: np.percentile(m, p, axis=0/1) - percentile along axis */
    def percentile(p: Double, axis: Int)(using frac: Fractional[T], elem: MatElem[T]): Mat[T] = {
      require(axis == 0 || axis == 1, s"axis must be 0 or 1, got $axis")
      if axis == 0 then
        val result = Array.ofDim[T](m.cols)
        var j = 0
        while j < m.cols do
          result(j) = percentileOf(Array.tabulate(m.rows)(i => m(i, j)), p)
          j += 1
        Mat.create(result, 1, m.cols)
      else
        val result = Array.ofDim[T](m.rows)
        var i = 0
        while i < m.rows do
          result(i) = percentileOf(Array.tabulate(m.cols)(j => m(i, j)), p)
          i += 1
        Mat.create(result, m.rows, 1)
    }

    /** NumPy: np.median(m, axis=0/1) */
    def median(axis: Int)(using frac: Fractional[T], elem: MatElem[T]): Mat[T] =
      percentile(50.0, axis)

    def describe(using frac: Fractional[T], ord: Ordering[T], elem: MatElem[T]): (Array[String], Mat[Double]) =
      val toD: T => Double = frac.toDouble
      val countRow = Mat.full[Double](1, m.cols, m.rows.toDouble)
      val meanRow  = m.mean(axis = 0).map(toD)
      val stdRow   = m.std(axis = 0).map(toD)
      val minRow   = m.min(axis = 0).map(toD)
      val q1Row    = m.percentile(25, axis = 0).map(toD)
      val medRow   = m.median(axis = 0).map(toD)
      val q3Row    = m.percentile(75, axis = 0).map(toD)
      val maxRow   = m.max(axis = 0).map(toD)
      val labels   = Array("count", "mean", "std", "min", "25%", "50%", "75%", "max")
      (labels, Mat.vstack[Double](countRow, meanRow, stdRow, minRow, q1Row, medRow, q3Row, maxRow))

    def rolling(window: Int): Mat.RollingWindow[T] = Mat.RollingWindow(m, window)

    def histogram(bins: Int = 10, range: Option[(T, T)] = None)(using frac: Fractional[T]): (Array[Int], Array[T]) = {
      require(bins > 0, "bins must be positive")
      val data = m.flatten

      if (data.isEmpty) then
        (Array.fill(bins)(0), Array.fill(bins + 1)(frac.zero))
      else
        val (minVal, maxVal) = range.getOrElse {
          var mi = data(0); var ma = data(0)
          data.foreach { v =>
            if (frac.lt(v, mi)) mi = v
            if (frac.gt(v, ma)) ma = v
          }
          (mi, ma)
        }

        if (frac.equiv(minVal, maxVal)) then
          val step = frac.div(frac.fromInt(1), frac.fromInt(10000))
          val edges = Array.tabulate(bins + 1)(i => frac.plus(minVal, frac.times(frac.fromInt(i), step)))
          val counts = Array.fill(bins)(0)
          counts(0) = data.length
          (counts, edges)
        else
          val rangeDist = frac.minus(maxVal, minVal)
          val binWidth = frac.div(rangeDist, frac.fromInt(bins))
          val binEdges = Array.tabulate(bins + 1) { i =>
            if (i == bins) maxVal else frac.plus(minVal, frac.times(frac.fromInt(i), binWidth))
          }

          val counts = Array.fill(bins)(0)
          data.foreach { value =>
            if frac.gteq(value, minVal) && frac.lteq(value, maxVal) then
              if (frac.equiv(value, maxVal)) then
                counts(bins - 1) += 1
              else
                val diff = frac.minus(value, minVal)
                // We use toDouble only for the index calculation to handle Big/BigDecimal
                val idx = frac.toDouble(frac.div(diff, binWidth)).toInt
                val binIdx = Math.max(0, Math.min(idx, bins - 1))
                counts(binIdx) += 1
          }
          (counts, binEdges)
    }

    def histogram(binEdges: Seq[T])(using frac: Fractional[T]): (Array[Int], Array[T]) = {
      require(binEdges.length >= 2, "binEdges must have at least 2 elements")
      val edgesArray = binEdges.toArray
      val numBins = edgesArray.length - 1
      val counts = Array.fill(numBins)(0)
      val data = m.flatten

      data.foreach { value =>
        val first = edgesArray(0)
        val last = edgesArray(numBins)

        if (frac.equiv(value, last)) then
          counts(numBins - 1) += 1
        else if (frac.gteq(value, first) && frac.lt(value, last)) then
          var left = 0
          var right = numBins - 1
          // Robust binary search: Finds 'i' such that edges(i) <= value < edges(i+1)
          while (left < right) {
            // The +1 ensures that when left and right are adjacent, mid becomes right
            val mid = left + (right - left + 1) / 2
            if (frac.lteq(edgesArray(mid), value)) then
              left = mid // Moves left forward
            else
              right = mid - 1 // Moves right backward
          }
          counts(left) += 1
      }
      (counts, edgesArray)
    }
}

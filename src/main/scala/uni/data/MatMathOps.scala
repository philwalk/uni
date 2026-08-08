package uni.data

import scala.reflect.ClassTag

/** Element-wise Double-valued math (trig, hyperbolic, log/floor/ceil/trunc) and
 *  ML activations, relocated from Mat.scala (file-split campaign). These methods
 *  have no dispatch constraint requiring definition inside `object Mat`; the
 *  `export MatMathOps.*` there makes them companion members again, so implicit
 *  scope and `import Mat.*` dispatch are identical for clients.
 */
private[data] object MatMathOps {
  // `Mat` (the opaque type) is imported as a direct member of object Mat: the
  // package-level forwarder (`export Mat.{Mat, …}` in Mat.scala) cannot be used
  // here — elaborating it completes object Mat, whose `export MatMathOps.*`
  // needs these signatures first (cyclic; manifests as "Not found: type Mat").
  import Mat.{Mat, create, fastD, fillD, globalRNG}

  extension [T](@annotation.unused m: Mat[T])(using @annotation.unused ct: ClassTag[T])

    /** Shared kernel for element-wise Double-valued math (trig, rounding,
     *  activations): stride-aware, with a contiguous-Double fast path routed
     *  through fillD (parallel for ≥ ParallelThreshold elements). */
    private def mapToDouble(f: Double => Double)(using num: Fractional[T]): Mat[Double] =
      if fastD(m)
      then
        val a   = m.tdata.asInstanceOf[Array[Double]]
        val out = Array.ofDim[Double](a.length)
        fillD(out)(i => f(a(i)))
        Mat.create(out, m.rows, m.cols)
      else
        val data = Array.ofDim[Double](m.size)
        var idx = 0
        var r = 0
        while r < m.rows do
          var c = 0
          while c < m.cols do
            data(idx) = f(num.toDouble(m(r, c)))
            idx += 1
            c += 1
          r += 1
        Mat.create(data, m.rows, m.cols)

    /** NumPy: np.sin(m) - element-wise sine */
    def sin(using num: Fractional[T]): Mat[Double] = mapToDouble(math.sin)

    /** NumPy: np.cos(m) - element-wise cosine */
    def cos(using num: Fractional[T]): Mat[Double] = mapToDouble(math.cos)

    /** NumPy: np.tan(m) - element-wise tangent */
    def tan(using num: Fractional[T]): Mat[Double] = mapToDouble(math.tan)

    /** NumPy: np.arcsin(m) - element-wise arcsine */
    def arcsin(using num: Fractional[T]): Mat[Double] = mapToDouble(math.asin)

    /** NumPy: np.arccos(m) - element-wise arccosine */
    def arccos(using num: Fractional[T]): Mat[Double] = mapToDouble(math.acos)

    /** NumPy: np.arctan(m) - element-wise arctangent */
    def arctan(using num: Fractional[T]): Mat[Double] = mapToDouble(math.atan)

    /** NumPy: np.arctan2(y, x) - element-wise 2-argument arctangent */
    def arctan2(other: Mat[T])(using num: Fractional[T]): Mat[Double] = {
      require(m.rows == other.rows && m.cols == other.cols,
        s"Shape mismatch: ${m.rows}x${m.cols} vs ${other.rows}x${other.cols}")

      val data = Array.ofDim[Double](m.size)
      var idx = 0
      var r = 0
      while (r < m.rows) {
        var c = 0
        while (c < m.cols) {
          data(idx) = math.atan2(num.toDouble(m(r, c)), num.toDouble(other(r, c)))
          idx += 1
          c += 1
        }
        r += 1
      }
      create(data, m.rows, m.cols)
    }

    /** NumPy: np.sinh(m) - element-wise hyperbolic sine */
    def sinh(using num: Fractional[T]): Mat[Double] = mapToDouble(math.sinh)

    /** NumPy: np.cosh(m) - element-wise hyperbolic cosine */
    def cosh(using num: Fractional[T]): Mat[Double] = mapToDouble(math.cosh)

    /** NumPy: np.tanh(m) - element-wise hyperbolic tangent */
    def tanh(using num: Fractional[T]): Mat[Double] = mapToDouble(math.tanh)

    /** NumPy: np.floor(m) - element-wise floor */
    def floor(using num: Fractional[T]): Mat[Double] = mapToDouble(math.floor)

    /** NumPy: np.ceil(m) - element-wise ceiling */
    def ceil(using num: Fractional[T]): Mat[Double] = mapToDouble(math.ceil)

    /** ML: Sigmoid activation σ(x) = 1/(1 + e^(-x)) */
    def sigmoid(using num: Fractional[T]): Mat[Double] =
      if fastD(m)
      then
        // Fast path: parallel, no boxing, numerically stable.
        val src = m.tdata.asInstanceOf[Array[Double]]
        val out = new Array[Double](src.length)
        fillD(out) { i =>
          val x = src(i)
          if x >= 0 then 1.0 / (1.0 + math.exp(-x))
          else { val e = math.exp(x); e / (1.0 + e) }
        }
        Mat.create(out, m.rows, m.cols)
      else
        val data = Array.ofDim[Double](m.size)
        var idx = 0
        var r = 0
        while r < m.rows do
          var c = 0
          while c < m.cols do
            val x = num.toDouble(m(r, c))
            data(idx) = if x >= 0 then 1.0 / (1.0 + math.exp(-x))
                        else { val e = math.exp(x); e / (1.0 + e) }
            idx += 1
            c += 1
          r += 1
        create(data, m.rows, m.cols)

    /** ML: ReLU (Rectified Linear Unit) - max(0, x) */
    def relu(using num: Numeric[T]): Mat[T] =
      if fastD(m)
      then
        // Fast path: parallel, no boxing.
        val src = m.tdata.asInstanceOf[Array[Double]]
        val out = new Array[Double](src.length)
        fillD(out)(i => math.max(src(i), 0.0))
        Mat.create(out, m.rows, m.cols).asInstanceOf[Mat[T]]
      else
        val zero = num.zero
        val data = Array.ofDim[T](m.size)
        var idx = 0
        var r = 0
        while r < m.rows do
          var c = 0
          while c < m.cols do
            val x = m(r, c)
            data(idx) = if num.gt(x, zero) then x else zero
            idx += 1
            c += 1
          r += 1
        create(data, m.rows, m.cols)

    /** ML: Leaky ReLU - max(alpha*x, x) */
    def leakyRelu(alpha: Double = 0.01)(using num: Fractional[T]): Mat[Double] =
      mapToDouble(x => if x > 0 then x else alpha * x)

    /** ML: Softmax along axis - exp(x) / sum(exp(x))
     *
     *  Numerically stable: subtract max before exp to prevent overflow
     */
    def softmax(axis: Int = 1)(using num: Fractional[T]): Mat[Double] = {
      require(axis == 0 || axis == 1, "axis must be 0 (columns) or 1 (rows)")

      val result = Array.ofDim[Double](m.rows * m.cols)

      if (axis == 1) {
        // Softmax across columns for each row
        var r = 0
        while (r < m.rows) {
          // Find max in this row
          var maxVal = num.toDouble(m(r, 0))
          var c = 1
          while (c < m.cols) {
            val v = num.toDouble(m(r, c))
            if (v > maxVal) maxVal = v
            c += 1
          }

          // Compute exp(x - max) and sum
          var sum = 0.0
          c = 0
          while (c < m.cols) {
            val expVal = math.exp(num.toDouble(m(r, c)) - maxVal)
            result(r * m.cols + c) = expVal
            sum += expVal
            c += 1
          }

          // Normalize
          c = 0
          while (c < m.cols) {
            result(r * m.cols + c) /= sum
            c += 1
          }
          r += 1
        }
      } else {
        // Softmax down rows for each column
        var c = 0
        while (c < m.cols) {
          // Find max in this column
          var maxVal = num.toDouble(m(0, c))
          var r = 1
          while (r < m.rows) {
            val v = num.toDouble(m(r, c))
            if (v > maxVal) maxVal = v
            r += 1
          }

          // Compute exp(x - max) and sum
          var sum = 0.0
          r = 0
          while (r < m.rows) {
            val expVal = math.exp(num.toDouble(m(r, c)) - maxVal)
            result(r * m.cols + c) = expVal
            sum += expVal
            r += 1
          }

          // Normalize
          r = 0
          while (r < m.rows) {
            result(r * m.cols + c) /= sum
            r += 1
          }
          c += 1
        }
      }

      create(result, m.rows, m.cols)
    }

    /** ML: Log-softmax (numerically stable) - log(softmax(x)) */
    def logSoftmax(axis: Int = 1)(using num: Fractional[T]): Mat[Double] = {
      // More stable than log(softmax(x))
      require(axis == 0 || axis == 1, "axis must be 0 (columns) or 1 (rows)")

      val result = Array.ofDim[Double](m.rows * m.cols)

      if (axis == 1) {
        var r = 0
        while (r < m.rows) {
          // Find max
          var maxVal = num.toDouble(m(r, 0))
          var c = 1
          while (c < m.cols) {
            val v = num.toDouble(m(r, c))
            if (v > maxVal) maxVal = v
            c += 1
          }

          // Compute log(sum(exp(x - max)))
          var sumExp = 0.0
          c = 0
          while (c < m.cols) {
            sumExp += math.exp(num.toDouble(m(r, c)) - maxVal)
            c += 1
          }
          val logSumExp = maxVal + math.log(sumExp)

          // Result is x - log(sum(exp(x)))
          c = 0
          while (c < m.cols) {
            result(r * m.cols + c) = num.toDouble(m(r, c)) - logSumExp
            c += 1
          }
          r += 1
        }
      } else {
        var c = 0
        while (c < m.cols) {
          var maxVal = num.toDouble(m(0, c))
          var r = 1
          while (r < m.rows) {
            val v = num.toDouble(m(r, c))
            if (v > maxVal) maxVal = v
            r += 1
          }

          var sumExp = 0.0
          r = 0
          while (r < m.rows) {
            sumExp += math.exp(num.toDouble(m(r, c)) - maxVal)
            r += 1
          }
          val logSumExp = maxVal + math.log(sumExp)

          r = 0
          while (r < m.rows) {
            result(r * m.cols + c) = num.toDouble(m(r, c)) - logSumExp
            r += 1
          }
          c += 1
        }
      }

      create(result, m.rows, m.cols)
    }

    /** ML: ELU (Exponential Linear Unit) - x if x>0, alpha*(exp(x)-1) otherwise */
    def elu(alpha: Double = 1.0)(using num: Fractional[T]): Mat[Double] =
      mapToDouble(x => if x > 0 then x else alpha * (math.exp(x) - 1.0))

    /** ML: GELU (Gaussian Error Linear Unit) - tanh approximation:
     *  0.5 * x * (1 + tanh(sqrt(2/π) * (x + 0.044715 * x³))) */
    def gelu(using num: Fractional[T]): Mat[Double] =
      mapToDouble { x =>
        val inner = math.sqrt(2.0 / math.Pi) * (x + 0.044715 * x * x * x)
        0.5 * x * (1.0 + math.tanh(inner))
      }

    /** ML: Dropout - randomly zero elements during training
     *
     *  @param p Probability of dropping (zeroing) each element (default 0.5)
     *  @param training If false, returns unchanged matrix (inference mode)
     *  @param seed Random seed for reproducibility (default: use global RNG)
     */
    def dropout(p: Double = 0.5, training: Boolean = true, seed: Long = -1)(using num: Fractional[T]): Mat[Double] = {
      require(p >= 0.0 && p < 1.0, s"Dropout probability must be in [0, 1), got $p")

      if (training) {
        val scale = 1.0 / (1.0 - p)
        val rng = if (seed >= 0) new NumPyRNG(seed) else globalRNG
        val data = Array.ofDim[Double](m.size)

        var idx = 0
        var r = 0
        while (r < m.rows) {
          var c = 0
          while (c < m.cols) {
            val keep = rng.nextDouble() >= p
            data(idx) = if (keep) {
              num.toDouble(m(r, c)) * scale
            } else {
              0.0
            }
            idx += 1
            c += 1
          }
          r += 1
        }
        create(data, m.rows, m.cols)
      } else {
        // Inference mode: no dropout, no scaling
        val data = Array.ofDim[Double](m.size)
        var idx = 0
        var r = 0
        while (r < m.rows) {
          var c = 0
          while (c < m.cols) {
            data(idx) = num.toDouble(m(r, c))
            idx += 1
            c += 1
          }
          r += 1
        }
        create(data, m.rows, m.cols)
      }
    }

    /** Natural log base 10 (NumPy: np.log10) */
    def log10(using frac: Fractional[T]): Mat[Double] = mapToDouble(math.log10)

    /** Natural log base 2 (NumPy: np.log2) */
    def log2(using frac: Fractional[T]): Mat[Double] =
      mapToDouble(x => math.log(x) / math.log(2.0))

    /** Truncate to integer, toward zero (NumPy: np.trunc) */
    def trunc(using frac: Fractional[T]): Mat[Double] =
      mapToDouble(v => if v >= 0 then math.floor(v) else math.ceil(v))
}

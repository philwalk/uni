package uni.data

/** Signal-processing companion methods (polyfit/polyval/convolve/correlate),
 *  relocated from Mat.scala (file-split campaign). `export MatSignalOps.*`
 *  inside `object Mat` keeps `Mat.polyfit(...)` etc. working unchanged.
 */
private[data] object MatSignalOps {
  // Direct member import — the package-level forwarder cannot be used here
  // (cyclic with the export inside object Mat); see MatMathOps.scala.
  import Mat.Vec

  /** NumPy: np.polyfit(x, y, deg) - least squares polynomial fit
   *  Returns coefficients [a_n, a_{n-1}, ..., a_0] highest degree first */
  def polyfit(x: Vec[Double], y: Vec[Double], deg: Int): Vec[Double] = {
    require(deg >= 1, s"degree must be >= 1, got $deg")
    val xs = x.flatten
    val ys = y.flatten
    require(xs.length == ys.length, s"x and y must have same length")
    require(xs.length > deg, s"need more points than degree")
    val n = xs.length
    // Build Vandermonde matrix: each row is [x^deg, x^(deg-1), ..., x^0]
    val vand = Array.ofDim[Double](n * (deg + 1))
    var i = 0
    while i < n do
      var j = 0
      while j <= deg do
        vand(i * (deg + 1) + j) = math.pow(xs(i), (deg - j).toDouble)
        j += 1
      i += 1
    val A = Mat.create(vand, n, deg + 1)
    val b = Mat.create(ys, n, 1)
    val (coeffs, _, _, _) = A.lstsq(b)
    // coeffs is (deg+1)x1, returned as flat row vector
    Mat.create(coeffs.flatten, 1, deg + 1)
  }

  /** NumPy: np.polyval(coeffs, x) - evaluate polynomial at points
   * Horner's method for numerical stability.
   *  coeffs = [a_n, ..., a_0] highest degree first (matches polyfit output) */
  def polyval(coeffs: Vec[Double], x: Vec[Double]): Vec[Double] = {
    val cs = coeffs.flatten  // [a_n, ..., a_0]
    val xs = x.flatten
    val result = Array.ofDim[Double](xs.length)
    var i = 0
    while i < xs.length do
      // Horner's method: a_n * x^n + ... + a_0
      var acc = 0.0
      var k = 0
      while k < cs.length do
        acc = acc * xs(i) + cs(k)
        k += 1
      result(i) = acc
      i += 1
    Mat.create(result, 1, xs.length)
  }

  /** NumPy: np.convolve(a, b, mode='full') - discrete linear convolution
   *  mode: "full" (default), "same", "valid" */
  def convolve(a: Vec[Double], b: Vec[Double], mode: String = "full"): Vec[Double] = {
    val as = a.flatten
    val bs = b.flatten
    val na = as.length
    val nb = bs.length
    val nFull = na + nb - 1
    val full = Array.ofDim[Double](nFull)
    var i = 0
    while i < na do
      var j = 0
      while j < nb do
        full(i + j) += as(i) * bs(j)
        j += 1
      i += 1
    mode match
      case "full" =>
        Mat.create(full, 1, nFull)
      case "same" =>
        val start = (nb - 1) / 2
        val result = Array.ofDim[Double](na)
        var k = 0
        while k < na do
          result(k) = full(start + k)
          k += 1
        Mat.create(result, 1, na)
      case "valid" =>
        val nValid = math.max(na, nb) - math.min(na, nb) + 1
        val start  = math.min(na, nb) - 1
        val result = Array.ofDim[Double](nValid)
        var k = 0
        while k < nValid do
          result(k) = full(start + k)
          k += 1
        Mat.create(result, 1, nValid)
      case other =>
        throw IllegalArgumentException(s"unknown mode '$other', use 'full', 'same', or 'valid'")
  }

  /** NumPy: np.correlate(a, b, mode='valid') - cross-correlation
   *  Correlation is convolution with b reversed */
  def correlate(a: Vec[Double], b: Vec[Double], mode: String = "valid"): Vec[Double] = {
    val as = a.flatten
    val bs = b.flatten
    val na = as.length
    val nb = bs.length
    val nFull = na + nb - 1
    val full = Array.ofDim[Double](nFull)
    // c[k] = sum_j a[j] * b[j - (k - (nb-1))]
    // equivalent: pad and slide b across a without reversing
    var k = 0
    while k < nFull do
      var j = 0
      while j < nb do
        val ai = k - (nb - 1) + j
        if ai >= 0 && ai < na then
          full(k) += as(ai) * bs(j)
        j += 1
      k += 1
    mode match
      case "full" =>
        Mat.create(full, 1, nFull)
      case "same" =>
        val start = (nb - 1) / 2
        val result = Array.ofDim[Double](na)
        var i = 0
        while i < na do
          result(i) = full(start + i)
          i += 1
        Mat.create(result, 1, na)
      case "valid" =>
        val nValid = math.max(na, nb) - math.min(na, nb) + 1
        val start  = nb - 1
        val result = Array.ofDim[Double](nValid)
        var i = 0
        while i < nValid do
          result(i) = full(start + i)
          i += 1
        Mat.create(result, 1, nValid)
      case other =>
        throw IllegalArgumentException(s"unknown mode '$other', use 'full', 'same', or 'valid'")
  }
}

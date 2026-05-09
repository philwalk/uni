package uni.data

import munit.FunSuite
import scala.sys.process.*

// Detects the known Linux SONAME conflict: system libopenblas.so.0 (BLAS-only, no LAPACKE)
// shadows bytedeco's bundled OpenBLAS, causing a fatal 'undefined symbol: LAPACKE_dgeev'
// crash when eig/svd/cholesky first load the native library.
class BlasDiagSuite extends FunSuite:

  test("system libopenblas does not shadow LAPACKE symbols") {
    assume(sys.props("os.name").toLowerCase.contains("linux"), "Linux only")

    val ldOut   = shellOut("ldconfig" :: "-p" :: Nil).getOrElse("")
    val libPath = ldOut.linesIterator
      .find(l => l.contains("libopenblas.so.0") && l.contains("=>"))
      .flatMap(_.split("=>").lift(1).map(_.trim))

    libPath match
      case None => () // no system libopenblas — safe
      case Some(lib) =>
        val syms = shellOut("nm" :: "-D" :: lib :: Nil)
          .orElse(shellOut("readelf" :: "--syms" :: lib :: Nil))
          .getOrElse("")
        assert(syms.contains("LAPACKE_dgeev"),
          s"""System libopenblas ($lib) lacks LAPACKE_dgeev.
             |
             |The dynamic linker loads this library (SONAME: libopenblas.so.0) ahead of
             |bytedeco's bundled OpenBLAS, causing a fatal 'undefined symbol' crash
             |when eig, svd, cholesky, or any other LAPACK method is first called.
             |This is a system configuration issue, not a bug in uni.
             |
             |Fix:
             |  sudo apt-get remove --purge libopenblas0-pthread libopenblas0 \\
             |    libopenblas-dev libopenblas-pthread-dev
             |  sudo apt-get autoremove --purge && sudo ldconfig""".stripMargin)

  private def shellOut(cmd: List[String]): Option[String] =
    try
      val sb = new StringBuilder
      Process(cmd).!(ProcessLogger(line => sb.append(line).append('\n'), _ => ()))
      Some(sb.toString)
    catch case _: Exception => None

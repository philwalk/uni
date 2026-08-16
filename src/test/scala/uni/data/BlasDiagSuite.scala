package uni.data

import munit.FunSuite
import scala.sys.process.*
import uni.*

/**
 * The Linux OpenBLAS load-order hazard, and the proof it is handled.
 *
 * Ubuntu's packaged `libopenblas.so.0` is built without LAPACKE. If netlib's JNIBLAS
 * maps it, that SONAME is pinned for the process and bytedeco's later
 * `libjniopenblas.so` (eig/svd/cholesky) dies with `undefined symbol: LAPACKE_dgeev` — a
 * hard JVM kill, not an exception. `Mat.netlib` now loads bytedeco's bundled,
 * LAPACKE-complete OpenBLAS *before* netlib, so bytedeco's JNI library is bound to it
 * before the system copy is mapped for netlib's own use; the two coexist in their own JNI
 * scopes. On Linux BLAS mode then uses whichever is faster (a probe: system OpenBLAS via
 * netlib where the alternatives point at one, the bundled copy otherwise). Nothing needs
 * to be purged from the OS.
 *
 * The first test runs exactly the sequence that used to kill the JVM — a BLAS-mode
 * matmul (which loads netlib, and with it the system BLAS), then a LAPACKE call.
 * Surviving it is the assertion.
 */
class BlasDiagSuite extends FunSuite:

  test("BLAS mode then LAPACKE in one JVM: the sequence that used to crash survives") {
    // 1. A BLAS-mode matmul: resolves `Mat.netlib` (bytedeco first, then netlib and, on
    //    Linux, the system BLAS it links) and multiplies through whichever won the probe.
    val a = MatD.randn(16, 16)
    val b = MatD.randn(16, 16)
    val c = a.matmulBlas(b)
    assertEquals(c.shape, (16, 16))
    // 2. Now a LAPACKE entry point through bytedeco. Before the load-order fix, on a
    //    Linux box with the packaged OpenBLAS, this line ended the process.
    val ev = MatD((2.0, 1.0), (1.0, 2.0)).eigenvalues()
    assertEquals(ev.length, 2)
    assertEqualsDouble(ev.max, 3.0, 1e-9)
    assertEqualsDouble(ev.min, 1.0, 1e-9)
  }

  test("system libopenblas without LAPACKE is reported, not required absent") {
    assume(sys.props("os.name").toLowerCase.contains("linux"), "Linux only")
    val ldOut   = shellOut("ldconfig" :: "-p" :: Nil).getOrElse("")
    val libPath = ldOut.linesIterator
      .find(l => l.contains("libopenblas.so.0") && l.contains("=>"))
      .flatMap(_.split("=>").lift(1).map(_.trim))
    libPath match
      case None => ()
      case Some(lib) =>
        val syms = shellOut("nm" :: "-D" :: lib :: Nil)
          .orElse(shellOut("readelf" :: "--syms" :: lib :: Nil))
          .getOrElse("")
        if !syms.contains("LAPACKE_dgeev") then
          // Informational: this is precisely the configuration the load order guards
          // against, and the test above has just proved the guard holds here.
          println(s"  [BlasDiagSuite] system $lib lacks LAPACKE; uni binds LAPACKE to its bundled OpenBLAS before netlib maps this one, so this is fine")
  }

  private def shellOut(cmd: List[String]): Option[String] =
    try
      val sb = new StringBuilder
      Process(cmd).!(ProcessLogger(line => sb.append(line).append('\n'), _ => ()))
      Some(sb.toString)
    catch case _: Exception => None

package uni.data

import munit.FunSuite
import scala.sys.process.*
import uni.*

/**
 * The Linux one-OpenBLAS-per-process rule, and the proof `uni.mat.blas` respects it.
 *
 * Ubuntu's packaged `libopenblas.so.0` is built without LAPACKE and shares bytedeco's
 * SONAME; two OpenBLAS instances in one JVM interpose on each other and die
 * (`undefined symbol: LAPACKE_dgeev` or `SIGSEGV dgemm_oncopy_HASWELL`, by load order) —
 * a hard JVM kill, not an exception. So `-Duni.mat.blas=system` on Linux never loads the
 * bundled copy: `eig`/`eigenvalues`/`svd`/`cholesky` go through netlib's LAPACK instead
 * (`LapackNetlib`), and `os-best`/`bundled` never load the system one. Nothing needs to
 * be purged from the OS.
 *
 * The mode is read once per JVM, so each test observes the mode this JVM was started
 * with (`sbt 'set javaOptions ++= Seq("-Duni.mat.blas=system")' test` for the Linux
 * `system` case; sbt forks tests). The default `sbt test` runs with the flag unset.
 */
class BlasDiagSuite extends FunSuite:

  private val mode = sys.props.get("uni.mat.blas").orElse(sys.env.get("UNI_MAT_BLAS"))
    .map(_.trim.toLowerCase).getOrElse("")
  private val isLinux = sys.props("os.name").toLowerCase.contains("linux")
  private val systemOnLinux = isLinux && mode == "system"

  test("bundled OpenBLAS: BLAS matmul then LAPACKE in one JVM survives") {
    assume(!systemOnLinux, "under -Duni.mat.blas=system on Linux the LAPACKE routines are forbidden")
    // 1. A BLAS matmul (unset mode: matmulBlas goes to the bundled OpenBLAS; os-best on
    //    Linux is bundled too; elsewhere netlib's backend, which coexists with bytedeco).
    val a = MatD.randn(16, 16)
    val b = MatD.randn(16, 16)
    val c = a.matmulBlas(b)
    assertEquals(c.shape, (16, 16))
    // 2. A LAPACKE entry point through bytedeco, in the same process.
    val ev = MatD((2.0, 1.0), (1.0, 2.0)).eigenvalues()
    assertEquals(ev.length, 2)
    assertEqualsDouble(ev.max, 3.0, 1e-9)
    assertEqualsDouble(ev.min, 1.0, 1e-9)
  }

  test("Linux -Duni.mat.blas=system: system BLAS matmul, then every LAPACK routine via netlib, one JVM") {
    assume(systemOnLinux, "only observable when this JVM was started with -Duni.mat.blas=system on Linux")
    val a = MatD.randn(64, 64)
    val b = MatD.randn(64, 64)
    assertEquals((a *@ b).shape, (64, 64))
    println(s"  [BlasDiagSuite] LAPACK via ${LapackNetlib.backendName}")
    val sym = MatD((2.0, 1.0), (1.0, 2.0))
    val ev = sym.eigenvalues()
    assertEqualsDouble(ev.max, 3.0, 1e-9); assertEqualsDouble(ev.min, 1.0, 1e-9)
    val (wr, wi, _) = sym.eig
    assert(wi.forall(_ == 0.0)); assertEqualsDouble(wr.max, 3.0, 1e-9)
    val (_, s, _) = sym.svd
    assertEqualsDouble(s(0), 3.0, 1e-9); assertEqualsDouble(s(1), 1.0, 1e-9)
    val l = sym.cholesky
    assertEqualsDouble(l(0, 0), math.sqrt(2.0), 1e-12)
  }

  test("system libopenblas without LAPACKE is reported, not required absent") {
    assume(isLinux, "Linux only")
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
          // Informational: this is precisely the copy `system` mode maps and `bundled`
          // mode never touches.
          println(s"  [BlasDiagSuite] system $lib lacks LAPACKE; uni maps it only under -Duni.mat.blas=system, where LAPACK goes through netlib instead")
  }

  private def shellOut(cmd: List[String]): Option[String] =
    try
      val sb = new StringBuilder
      Process(cmd).!(ProcessLogger(line => sb.append(line).append('\n'), _ => ()))
      Some(sb.toString)
    catch case _: Exception => None

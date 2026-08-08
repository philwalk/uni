package uni

import munit.FunSuite

/**
 * `Path.relpath` -- which used to never return a relative path.
 *
 * It was `standardizePath(relativePathToCwd(p))`: the first computed the relative
 * form, the second immediately discarded it by calling `toAbsolutePath`. And the two
 * disagreed about which working directory they meant -- the config's `pwd` versus the
 * JVM's `user.dir` -- so under an injected user the answer named a different file.
 *
 * These run on every host: `toPosixRel` is pure string work over `config.userdir`.
 */
class RelpathSuite extends FunSuite:

  override def afterEach(context: AfterEach): Unit = resetConfig()

  /** An injected config, so "the working directory" is a known value rather than
   *  wherever the test happens to be run from. */
  private def injected(): String =
    withMountLines(Seq("C:/msys64 on / type ntfs (binary)"), TestUtils.windowsTestUser,
      isWindows = true)
    config.userdir

  test("the working directory itself is '.'") {
    val cwd = injected()
    assertEquals(Paths.get(cwd).relpath, ".")
  }

  test("a path below the working directory comes back relative") {
    val cwd = injected()
    assertEquals(Paths.get(s"$cwd/foo").relpath, "foo")
    assertEquals(Paths.get(s"$cwd/foo/bar/baz.txt").relpath, "foo/bar/baz.txt")
  }

  test("a path outside the working directory keeps its absolute POSIX form") {
    // There is no relative form worth inventing, and emitting `../..` chains would
    // be worse than an absolute answer.
    injected()
    val out = Paths.get("/usr/bin/bash").relpath
    assert(out.startsWith("/"), s"expected an absolute POSIX path, got: $out")
    assertEquals(out, "/usr/bin/bash")
  }

  test("relpath and posixRel agree, as posixRel's deprecation note promises") {
    // They did not: `posixRel` returned `src/main` where `relpath` returned
    // `/Users/philwalk/workspace/uni/src/main` for the same input.
    val cwd = injected()
    for input <- Seq(cwd, s"$cwd/foo", s"$cwd/foo/bar", "/usr/bin", "/") do
      assertEquals(Paths.get(input).relpath, posixRel(input), s"disagreement for [$input]")
  }

  test("relpath uses one working directory, the config's") {
    // The sharp end of the old bug: it relativised against the config's pwd and then
    // re-absolutised against the JVM's, so the result pointed somewhere else. If this
    // ever regresses, the answer will contain the real user.dir.
    val cwd = injected()
    val out = Paths.get(s"$cwd/foo").relpath
    assertEquals(out, "foo")
    val jvmDir = normalizePosix(sys.props("user.dir"))
    assert(!out.contains(jvmDir), s"leaked the JVM working directory: $out")
  }

  test("relpath never emits a backslash") {
    val cwd = injected()
    for input <- Seq(cwd, s"$cwd/foo", "/usr/bin") do
      assert(!Paths.get(input).relpath.contains('\\'), s"backslash in relpath of $input")
  }

  test("relpath follows the injected working directory") {
    // Two configs, one host: the same input is relative under one and absolute under
    // the other, which is only meaningful if relpath actually consults the config.
    val cwd = injected()
    val under = Paths.get(s"$cwd/foo").relpath
    withMountLines(Seq("C:/msys64 on / type ntfs (binary)"),
      UserInfo("liam", "C:/elsewhere/liam", "C:/elsewhere"), isWindows = true)
    val outside = Paths.get(s"$cwd/foo").relpath
    assertEquals(under, "foo")
    assertNotEquals(outside, "foo", "relpath ignored the new working directory")
  }

package uni

import munit.FunSuite
import TestUtils.testUser

/** Regression tests for the bug where hidden files like ".gitignore" had their
 *  leading dot stripped in applyTildeAndDots, producing paths like
 *  "c:/opt/uegitignore" instead of "c:/opt/ue/.gitignore".
 */
class DotfilePathSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    uni.resetConfig()

  private val mountLines = Seq("C:/msys64 on / type ntfs (binary)")

  // Each dotfile name we want to protect
  private val dotfiles = Seq(".gitignore", ".bashrc", ".env", ".dockerignore", ".hidden")

  for name <- dotfiles do
    test(s"dotfile '$name' preserves leading dot in posx") {
      if isWin then withMountLines(mountLines, testUser)
      val p    = name.asPath
      val posx = p.posx
      assert(
        posx.endsWith(s"/$name"),
        s"expected posx to end with '/$name' but got: $posx"
      )
    }

    test(s"dotfile '$name' produces absolute path") {
      if isWin then withMountLines(mountLines, testUser)
      val posx = name.asPath.posx
      assert(
        posx.startsWith("/") || (posx.length >= 2 && posx(1) == ':'),
        s"expected absolute path but got: $posx"
      )
    }

  test("dotfile: last path segment is '.gitignore' not 'gitignore'") {
    if isWin then withMountLines(mountLines, testUser)
    val p = ".gitignore".asPath
    assertEquals(p.last, ".gitignore")
  }

  test("dotfile: path does not contain 'uegitignore' (regression from dropped-dot bug)") {
    if isWin then withMountLines(mountLines, testUser)
    val posx = ".gitignore".asPath.posx
    assert(!posx.contains("uegitignore"), s"dropped-dot bug regressed: $posx")
    assert(!posx.contains("itignore") || posx.contains(".gitignore"),
      s"dot was dropped: $posx")
  }

  // Ensure adjacent dot-prefix cases still work correctly

  test("dotfile: './' prefix still resolves relative path") {
    if isWin then withMountLines(mountLines, testUser)
    val p = "./foo".asPath
    assert(p.posx.endsWith("/foo"), s"./foo should end with /foo, got: ${p.posx}")
  }

  test("dotfile: '../foo' resolves to parent-dir child") {
    if isWin then withMountLines(mountLines, testUser)
    val p = "../foo".asPath
    assert(p.posx.endsWith("/foo"), s"../foo should end with /foo, got: ${p.posx}")
  }

  test("dotfile: bare '.' resolves to cwd") {
    if isWin then withMountLines(mountLines, testUser)
    val cwdPosx = ".".asPath.posx
    assert(cwdPosx.nonEmpty, "cwd should not be empty")
    assert(!cwdPosx.endsWith("/."), s"cwd should not end with /. : $cwdPosx")
  }

  // Regression: ".gitignore".asPath.lastModified returned 0 because the path
  // resolved to a garbled string that pointed at no real file.
  test("dotfile: lastModified is nonzero for a newly created dotfile") {
    val dir  = java.nio.file.Files.createTempDirectory("dotfile-lm-")
    dir.toFile.deleteOnExit()
    val file = dir.resolve(".gitignore")
    file.toFile.deleteOnExit()
    java.nio.file.Files.write(file, "*.class\n".getBytes("UTF-8"))

    // Resolve via the same code path that was broken: bare dotfile name
    // resolved relative to cwd, which we temporarily set to dir.
    withMountLines(
      if isWin then mountLines else Seq.empty,
      UserInfo(testUser.name, testUser.home, dir.toString.replace('\\', '/'))
    )

    val p  = ".gitignore".asPath
    val ts = p.lastModified
    assert(ts != 0L,
      s"lastModified=0 means path resolved to wrong location; p.posx=${p.posx}")
  }

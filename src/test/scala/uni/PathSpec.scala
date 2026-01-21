package uni

import munit.FunSuite
import uni.Internals.*
import uni.TestUtils.*
import uni.*
import uni.io.*
import java.nio.file.{Files as JFiles}
import TestUtils.{prmsg, noisy}

class PathSpec extends FunSuite {
  lazy val testFile: Path = Paths.get(s"${TMP}/youMayDeleteThisDebrisFile.txt")

  var testfileb: Path = scala.compiletime.uninitialized

  override def beforeAll(): Unit = {
    uni.resetConfig()
    testfileb = Paths.get(homeDirTestFile)
    touch(testfileb)
  }

  override def beforeEach(context: BeforeEach): Unit = {
    if (!JFiles.exists(testFile)) {
      prmsg(s"create testFile[$testFile]")
      withFileWriter(testFile) { w =>
        testDataLines.foreach { line =>
          w.print(line + "\n")
        }
      }
    }
  }

  // invariants / working drive
  test("invariants: working drive should be correct for os") {
    val hd = hereDrive
    if (isWin) {
      assert(hd.matches("[a-zA-Z]:"))
    } else {
      assert(hd.isEmpty)
    }
  }

  // Path.suffix
  test("Path.suffix: should correctly derive dotsuffix") {
    val p = ".ue.scala.swp".path
    val expected = ".swp"
    val dsfx = p.dotsuffix
    assertEquals(dsfx, expected)
  }

  // Paths.get / shellRoot
  test("Paths.get: should correctly apply shellRoot") {
    if (isWinshell) {
      val etcFstab = Paths.get("/etc/fstab").posx
      val sr = shellRoot
      assert(etcFstab.startsWith(sr))
    }
  }

  // Path.relpath.stdpath
  test("Path.relpath.stdpath: should correctly relativize Path, if below pwd") {
    withMountLines(Nil, testUser)
    val p     = Paths.get(s"${pwd.posx}/src")
    val pabs: String = p.toAbsolutePath.toString.replace('\\', '/')
    val relp: Path  = Paths.get(posixRel(pabs)) // relativePathToCwd(p)
    val stdp  = relp.stdpath
    val ok = pabs.drop(2).endsWith(stdp.drop(2))
    assert(
      ok,
      clues(
        s"""
           |p.pabs           = [$pabs]
           |p.relpath        = [$relp]
           |p.relpath.stdpath= [$stdp]
           |""".stripMargin
      )
    )
  }

  // File.eachline
  test("File#eachline: should correctly deliver all file lines") {
    for ((line, lnum) <- testFile.lines.toSeq.zipWithIndex) {
      noisy(f"$lnum%d: $line%s")
      val expected = testDataLines(lnum)
      if (line != expected) {
        prmsg(s"line ${lnum}:\n  [$line]\n  [$expected]")
      }
    }

    for ((line, lnum) <- testFile.lines.zipWithIndex) {
      val expected = testDataLines(lnum)
      if (line != expected) {
        prmsg(s"failure: line ${lnum}:\n  [$line]\n  [$expected]")
      } else {
        noisy(s"success: line ${lnum}:  [$line],  [$expected]")
      }
      assertEquals(line, expected, s"line ${lnum}:  [$line],  [$expected]")
    }
  }

  // tilde-in-path-test
  test("File#tilde-in-path-test: should see file in user home directory if present") {

    prmsg(s"posixHomeDir: [$posixHomeDir]")
    prmsg(s"testfileb:    [$testfileb]")
    val ok = testfileb.exists
    if (ok) prmsg(s"tilde successfully converted to path '$testfileb'")
    assert(ok, s"error: cannot see file '$testfileb'")
  }

  test("File#tilde-in-path-test: should NOT see file in user home directory if NOT present") {
    val test: Boolean = testfileb.toFile.delete()
    val ok            = !testfileb.exists || !test
    if (ok) noisy(s"delete() correctly detected by 'exists' method on path '$testfileb'")
    assert(ok, s"error: can still see file '$testfileb'")
  }

  // Windows-specific tests
  if (isWin) {
    showTestInputs()

    for ((fname, expected) <- pathDospathPairs) {
      val name = s"File: should correctly handle posix drive for dos path $fname"
      test(name) {
        noisy(s"fname[$fname], expected[$expected]")
        val file = Paths.get(fname)
        noisy(f"${file.stdpath}%-22s : ${file.exists}")
        val a = expected.toLowerCase.replace('/', '\\')
        val d: String = file.dospath.toLowerCase
        val df       = Paths.get(a)
        val af       = Paths.get(d)
        val sameFile = af.isSameFile(df)
        val equivalent = a == d || a.path.abs == d.path.abs
        if (sameFile && equivalent) {
          noisy(s"a [$a] == d [$d]")
          assert(equivalent)
        } else {
          noisy(s"expected[${expected.toLowerCase}")
          noisy(s"file.localpath[${file.localpath.toLowerCase}]")
          prmsg(s"error:")
          prmsg(s"  expected[${expected.toLowerCase}]")
          prmsg(s"  dospath [${file.dospath.toLowerCase}]")
          val x = file.exists
          val y = new JFile(expected).exists
          if (x && y) {
            assert(sameFile)
          } else {
            noisy(s"[$file].exists: [$x]\n[$expected].exists: [$y]")
          }
        }
      }
    }

    test("File# stdpath test: generated pairs") {
      val upairs = toStringPairs.toArray.toSeq
      noisy (s"${upairs.size} pairs")

      for ((fname, expected) <- upairs) {
        val testName =
          "%-32s should map [%-12s] to [%s]".format(s"Paths.get(\"$fname\").toString", fname, expected)
        test(testName) {
          noisy("=====================\n")
          noisy(s"fname[$fname]")
          noisy(s"expec[$expected]")
          val abspath: Path = Paths.get(fname).toAbsolutePath.normalize()
          noisy(s"file exists: ${abspath.exists}")
          noisy(f"file.posx   [${abspath.posx}%-22s]")
          noisy(f"file.stdpath[${abspath.stdpath}%-22s]")

          val exp = expected.toLowerCase
          val std = abspath.stdpath.toLowerCase
          val nrm = abspath.posx.toLowerCase
          noisy(s"exp[$exp] : std[$std] : nrm[$nrm]")
          if (nonCanonicalDefaultDrive) {
            noisy(s"hereDrive[$hereDrive]")
            if (std.endsWith(expected)) {
              noisy(s"std[$std].endsWith(expected[$expected]) for hereDrive[$hereDrive]")
            }
            assert(std.endsWith(expected))
          } else {
            if (exp == std) {
              noisy(s"std[$std] == exp[$exp]")
            } else {
              noisy(s"error: expected[$exp] not equal to toString [$std]")
            }
            assertEquals(exp, std)
          }
        }
      }
    }
  }

  // bare filename
  test("# bare filename: bare path segments are valid files") {
    val s1 = Paths.get("s1")
    val stdpath = s1.stdpath
    assert(stdpath.startsWith("/"))
  }

  test("# bare filename: bare filenames always have parent files") {
    val s1 = Paths.get("s1")
    val par = s1.getParentPath
    assert(par != null)
  }

  // getParentPath extension method
  {
    val windowsPaths: List[String] =
      if (isWin) List("C:", "C:/") else Nil

    val testPaths: List[String] = windowsPaths ::: List(
      ".",
      "src",
      "/",
      "/bin"
    )

    for (pathstr <- testPaths.distinct) {
      test(s"# getParentPath extension method: does not return null on $pathstr") {
        val p = Paths.get(pathstr)
        val par = p.getParentNonNull
        noisy("par [${par.posx}]")
        assert(par != null)
      }
    }
  }

  // round trip consistency
  for (fname <- distinctKeys) {
    val testName = s"$fname variations are the same file"
    noisy(s"[$testName]")
    val f1: Path = Paths.get(fname)
    val variants: Seq[Path] = getVariants(f1).distinct
    for (v <- variants) {
      val matchtag = "%-12s to %s".format(fname, v)
      test(s"# round trip consistency: round trip conversion should match [$matchtag]") {
        val sameFile = f1.isSameFile(v)
        val bothPwd = isPwd(f1) && isPwd(v)
        if (f1 != v && !sameFile) {
          noisy(s"f1[$f1]\nv[$v]")
        }
        assert(sameFile, s"not sameFile: f1[$f1] != variant v[$v]")
        assert(f1.equals(v) || bothPwd, s"f1[$f1] != variant v[$v]")
      }
    }
  }

  // /proc files
  for (fname <- procFiles) {
    test(s"/proc files: $fname should be readable in Linux or Windows shell") {
      if (isLinux || isWinshell) {
        val p = fname.path
        val text: String = p.contentAsString.takeWhile(_ != '\n')
        noisy(s"# $fname :: [$text]")
        assert(text.nonEmpty)
      }
    }
  }
}

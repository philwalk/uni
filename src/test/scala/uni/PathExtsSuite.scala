package uni

import munit.FunSuite
import java.nio.file.{Files, Path, Paths as JPaths}
import java.nio.charset.StandardCharsets
import java.io.File as JFile

/** Covers extension methods in PathExts.scala on both Path and java.io.File. */
class PathExtsSuite extends FunSuite:

  private def tempFile(content: String): Path =
    val p = Files.createTempFile("pathexts-", ".txt")
    p.toFile.deleteOnExit()
    Files.write(p, content.getBytes(StandardCharsets.UTF_8.name))
    p

  private def emptyTempFile(): Path =
    val p = Files.createTempFile("pathexts-empty-", ".txt")
    p.toFile.deleteOnExit()
    p

  private def tempDir(): Path =
    val p = Files.createTempDirectory("pathexts-dir-")
    p.toFile.deleteOnExit()
    p

  // ============================================================================
  // baseName
  // ============================================================================

  test("baseName: filename with extension strips extension") {
    val p = JPaths.get("foo/bar.csv")
    assertEquals(p.baseName, "bar")
  }

  test("baseName: filename without dot returns full name") {
    val p = JPaths.get("foo/noext")
    assertEquals(p.baseName, "noext")
  }

  // ============================================================================
  // last / ext / dotsuffix / extension
  // ============================================================================

  test("last: returns filename component") {
    val p = JPaths.get("foo/file.txt")
    assertEquals(p.last, "file.txt")
  }

  test("ext: path with extension") {
    val p = JPaths.get("foo/file.txt")
    assertEquals(p.ext, "txt")
  }

  test("ext: path without extension returns empty string") {
    val p = JPaths.get("foo/noext")
    assertEquals(p.ext, "")
  }

  test("dotsuffix: returns dot + extension") {
    val p = JPaths.get("foo/file.csv")
    assertEquals(p.dotsuffix, ".csv")
  }

  test("dotsuffix: no extension returns empty string") {
    val p = JPaths.get("foo/noext")
    assertEquals(p.dotsuffix, "")
  }

  test("extension: Some when extension present") {
    val p = JPaths.get("foo/file.txt")
    assertEquals(p.extension, Some("txt"))
  }

  test("extension: None when no extension") {
    val p = JPaths.get("foo/noext")
    assertEquals(p.extension, None)
  }

  // ============================================================================
  // abs
  // ============================================================================

  test("abs: existing file returns absolute posix path") {
    val p = tempFile("hello")
    val a = p.abs
    assert(a.nonEmpty)
    assert(!a.contains('\\'), s"expected forward slashes, got: $a")
  }

  test("abs: non-existing path returns non-empty string") {
    val p = JPaths.get("/nonexistent/pathexts-abs-xyz.tmp")
    val a = p.abs
    assert(a.nonEmpty)
  }

  // ============================================================================
  // isEmpty / nonEmpty
  // ============================================================================

  test("isEmpty: empty file returns true") {
    val p = emptyTempFile()
    assert(p.isEmpty)
  }

  test("nonEmpty: non-empty file returns true") {
    val p = tempFile("data")
    assert(p.nonEmpty)
  }

  // ============================================================================
  // segments / reversePath
  // ============================================================================

  test("segments: path has at least one segment") {
    val p = tempFile("segs")
    assert(p.segments.nonEmpty)
    assert(p.segments.last.endsWith(".txt"))
  }

  test("reversePath: first element is the filename") {
    val p = tempFile("rev")
    val rev = p.reversePath
    assert(rev.startsWith(p.last), s"expected to start with ${p.last}, got $rev")
  }

  // ============================================================================
  // filesIter / files / pathsIter / paths / subdirs / subfiles
  // ============================================================================

  test("files: directory contains the created file") {
    val dir = tempDir()
    val f = Files.createTempFile(dir, "a-", ".txt")
    f.toFile.deleteOnExit()
    assert(dir.files.nonEmpty)
  }

  test("paths: directory paths contains the created file") {
    val dir = tempDir()
    val f = Files.createTempFile(dir, "b-", ".txt")
    f.toFile.deleteOnExit()
    assert(dir.paths.nonEmpty)
  }

  test("subfiles: returns only regular files") {
    val dir = tempDir()
    val f = Files.createTempFile(dir, "c-", ".txt")
    f.toFile.deleteOnExit()
    val sf = dir.subfiles
    assert(sf.nonEmpty)
    assert(sf.forall(Files.isRegularFile(_)))
  }

  test("subdirs: returns only directories") {
    val dir = tempDir()
    val sub = Files.createTempDirectory(dir, "subdir-")
    sub.toFile.deleteOnExit()
    val sd = dir.subdirs
    assert(sd.nonEmpty)
    assert(sd.forall(Files.isDirectory(_)))
  }

  test("filesIter: non-directory returns empty iterator") {
    val f = tempFile("not-a-dir")
    assert(f.filesIter.isEmpty)
  }

  // ============================================================================
  // pathsTree / pathsTreeIter
  // ============================================================================

  test("pathsTree: existing directory yields at least itself") {
    val dir = tempDir()
    val tree = dir.pathsTree
    assert(tree.nonEmpty)
  }

  test("pathsTreeIter: non-existing path yields empty iterator") {
    val p = JPaths.get("/nonexistent/pathexts-tree-xyz")
    assert(p.pathsTreeIter.isEmpty)
  }

  // ============================================================================
  // linesStream / firstLine
  // ============================================================================

  test("linesStream: yields all lines") {
    val p = tempFile("alpha\nbeta\ngamma")
    assertEquals(p.linesStream.toList, List("alpha", "beta", "gamma"))
  }

  test("firstLine: returns first line of file") {
    val p = tempFile("hello\nworld")
    assertEquals(p.firstLine, "hello")
  }

  test("firstLine: empty file returns empty string") {
    val p = emptyTempFile()
    assertEquals(p.firstLine, "")
  }

  // ============================================================================
  // lines (Seq) / lines (charset) / contentAsString / byteArray
  // ============================================================================

  test("lines (Seq): reads all lines") {
    val p = tempFile("line1\nline2")
    assertEquals(p.lines.toList, List("line1", "line2"))
  }

  test("lines(charset): reads with explicit charset") {
    val p = tempFile("x\ny\nz")
    val ls = p.lines(StandardCharsets.UTF_8.name).toList
    assertEquals(ls, List("x", "y", "z"))
  }

  test("contentAsString: reads whole file content") {
    val p = tempFile("hello world")
    assertEquals(p.contentAsString, "hello world")
  }

  test("contentAsString(charset): reads with explicit charset") {
    val p = tempFile("hello")
    assertEquals(p.contentAsString(StandardCharsets.UTF_8), "hello")
  }

  test("byteArray: reads raw bytes matching UTF-8 encoding") {
    val content = "abc"
    val p = tempFile(content)
    assertEquals(p.byteArray.toList, content.getBytes(StandardCharsets.UTF_8).toList)
  }

  // ============================================================================
  // Latin1 fallback: lines and contentAsString
  // ============================================================================

  test("lines (Seq): Latin1 fallback for non-UTF-8 file") {
    val p = Files.createTempFile("pathexts-latin1-", ".txt")
    p.toFile.deleteOnExit()
    Files.write(p, Array(0x80.toByte, '\n'.toByte, 0x81.toByte))
    val ls = p.lines
    assertEquals(ls.length, 2)
  }

  test("contentAsString: Latin1 fallback for non-UTF-8 file") {
    val p = Files.createTempFile("pathexts-latin1cs-", ".txt")
    p.toFile.deleteOnExit()
    Files.write(p, Array(0xC0.toByte))
    val s = p.contentAsString
    assertEquals(s.length, 1)
  }

  // ============================================================================
  // isSymbolicLink / isSameFile
  // ============================================================================

  test("isSymbolicLink: regular file returns false") {
    val p = tempFile("data")
    assert(!p.isSymbolicLink)
  }

  test("isSameFile: same path returns true") {
    val p = tempFile("data")
    assert(p.isSameFile(p))
  }

  test("isSameFile: different files returns false") {
    val p1 = tempFile("a")
    val p2 = tempFile("b")
    assert(!p1.isSameFile(p2))
  }

  test("isSameFile: non-Path argument returns false") {
    val p = tempFile("data")
    assert(!p.isSameFile("not a path"))
  }

  // ============================================================================
  // timestamps
  // ============================================================================

  test("lastModified timestamps: all non-negative for existing file") {
    val p = tempFile("ts")
    assert(p.lastModified > 0L)
    assert(p.lastModMillisAgo >= 0L)
    assert(p.lastModSecondsAgo >= 0.0)
    assert(p.lastModMinutesAgo >= 0.0)
    assert(p.lastModHoursAgo >= 0.0)
    assert(p.lastModDaysAgo >= 0.0)
    // aliases
    assert(p.lastModSeconds >= 0.0)
    assert(p.lastModMinutes >= 0.0)
    assert(p.lastModHours >= 0.0)
    assert(p.lastModDays >= 0.0)
  }

  test("lastModifiedYMD: returns non-empty string for existing file") {
    val p = tempFile("ts")
    assert(p.lastModifiedYMD.nonEmpty)
  }

  test("lastModifiedTime: returns year >= 2020") {
    val p = tempFile("ts")
    assert(p.lastModifiedTime.getYear >= 2020)
  }

  test("weekDay: returns non-null day of week") {
    val p = tempFile("ts")
    assert(p.weekDay != null)
  }

  test("ageInDays: freshly created file age is < 1 day") {
    val p = tempFile("age")
    assert(p.ageInDays >= 0.0 && p.ageInDays < 1.0)
  }

  // ============================================================================
  // epoch2DateTime (Path extension)
  // ============================================================================

  test("lastModifiedTime on Path for new temp file returns current year") {
    val p = tempFile("ep")
    val dt = p.lastModifiedTime
    val currentYear = java.time.LocalDate.now().getYear
    assertEquals(dt.getYear, currentYear)
  }

  // ============================================================================
  // newerThan / olderThan
  // ============================================================================

  test("newerThan / olderThan: non-file path returns false") {
    val real = tempFile("real")
    val ghost = JPaths.get("/nonexistent/pathexts-ghost.tmp")
    assert(!real.newerThan(ghost))
    assert(!ghost.newerThan(real))
    assert(!real.olderThan(ghost))
  }

  // ============================================================================
  // copyTo: all 4 branches of overwrite × copyAttributes
  // ============================================================================

  test("copyTo default (overwrite=true, copyAttributes=false): copies file") {
    val src = tempFile("copy-me")
    val dst = Files.createTempFile("pathexts-dst-", ".txt")
    dst.toFile.deleteOnExit()
    val result = src.copyTo(dst)
    assertEquals(result, dst)
    assertEquals(new String(Files.readAllBytes(dst), StandardCharsets.UTF_8), "copy-me")
  }

  test("copyTo overwrite=false, copyAttributes=false: throws if target exists") {
    val src = tempFile("src-content")
    val dst = tempFile("dst-content")
    intercept[java.nio.file.FileAlreadyExistsException] {
      src.copyTo(dst, overwrite = false, copyAttributes = false)
    }
  }

  test("copyTo overwrite=false, copyAttributes=true: throws if target exists") {
    val src = tempFile("src-content")
    val dst = tempFile("dst-content")
    intercept[java.nio.file.FileAlreadyExistsException] {
      src.copyTo(dst, overwrite = false, copyAttributes = true)
    }
  }

  test("copyTo overwrite=true, copyAttributes=true: copies with attributes") {
    val src = tempFile("attrs-test")
    val dst = Files.createTempFile("pathexts-dst2-", ".txt")
    dst.toFile.deleteOnExit()
    src.copyTo(dst, overwrite = true, copyAttributes = true)
    assertEquals(new String(Files.readAllBytes(dst), StandardCharsets.UTF_8), "attrs-test")
  }

  // ============================================================================
  // renameViaCopy: branches (-1 / -1 / 0)
  // ============================================================================

  test("renameViaCopy: source does not exist returns -1") {
    val src = JPaths.get("/nonexistent/pathexts-rvc-src.tmp")
    val dst = JPaths.get("/nonexistent/pathexts-rvc-dst.tmp")
    assertEquals(src.renameViaCopy(dst), -1)
  }

  test("renameViaCopy: destination exists with overwrite=false returns -1") {
    val src = tempFile("src-rvc")
    val dst = tempFile("dst-rvc")
    assertEquals(src.renameViaCopy(dst, overwrite = false), -1)
  }

  test("renameViaCopy: success returns 0") {
    val src = tempFile("rename-content")
    val dst = Files.createTempFile("pathexts-rvc-ok-", ".txt")
    Files.delete(dst)
    val code = src.renameViaCopy(dst)
    assertEquals(code, 0)
    assertEquals(new String(Files.readAllBytes(dst), StandardCharsets.UTF_8), "rename-content")
    dst.toFile.deleteOnExit()
  }

  // ============================================================================
  // renameToOpt / renameTo
  // ============================================================================

  test("renameToOpt: source does not exist returns None") {
    val src = JPaths.get("/nonexistent/pathexts-rto-src.tmp")
    val dst = JPaths.get("/nonexistent/pathexts-rto-dst.tmp")
    assertEquals(src.renameToOpt(dst), None)
  }

  test("renameToOpt: source exists and dest free returns Some") {
    val src = tempFile("rto-content")
    val dst = Files.createTempFile("pathexts-rto-ok-", ".txt")
    dst.toFile.deleteOnExit()
    Files.delete(dst)
    val result = src.renameToOpt(dst)
    assert(result.isDefined, "expected Some but got None")
    Files.deleteIfExists(dst)
  }

  test("renameToOpt: source exists but dest also exists and no overwrite returns None") {
    val src = tempFile("rto-src")
    val dst = tempFile("rto-dst")
    assertEquals(src.renameToOpt(dst, overwrite = false), None)
  }

  test("renameTo: success returns true") {
    val src = tempFile("rt-content")
    val dst = Files.createTempFile("pathexts-rt-", ".txt")
    dst.toFile.deleteOnExit()
    Files.delete(dst)
    assert(src.renameTo(dst))
    Files.deleteIfExists(dst)
  }

  test("renameTo: source does not exist returns false") {
    val src = JPaths.get("/nonexistent/pathexts-rt-src.tmp")
    val dst = JPaths.get("/nonexistent/pathexts-rt-dst.tmp")
    assert(!src.renameTo(dst))
  }

  // ============================================================================
  // delete / mkdirs
  // ============================================================================

  test("delete: existing file returns true") {
    val p = tempFile("to-be-deleted")
    assert(p.delete())
  }

  test("mkdirs: creates nested directories") {
    val base = Files.createTempDirectory("pathexts-mkdirs-")
    base.toFile.deleteOnExit()
    val sub = base.resolve("a/b/c")
    val result = sub.mkdirs
    assert(result)
    sub.toFile.deleteOnExit()
  }

  // ============================================================================
  // withWriter
  // ============================================================================

  test("withWriter: writes content to file") {
    val p = Files.createTempFile("pathexts-writer-", ".txt")
    p.toFile.deleteOnExit()
    p.withWriter() { pw => pw.print("hello") }
    assertEquals(new String(Files.readAllBytes(p), StandardCharsets.UTF_8), "hello")
  }

  // ============================================================================
  // delim
  // ============================================================================

  test("delim: non-existent path returns empty string") {
    val p = JPaths.get("/nonexistent/xyz.csv")
    assertEquals(p.delim, "")
  }

  test("delim: CSV file returns comma") {
    val p = tempFile("a,b,c\n1,2,3\n")
    assertEquals(p.delim, ",")
  }

  // ============================================================================
  // hash64 / cksum / md5 / sha256
  // ============================================================================

  test("hash64: returns non-empty string") {
    val p = tempFile("hash-content")
    assert(p.hash64.nonEmpty)
  }

  test("cksum: returns tuple without throwing") {
    val p = tempFile("cksum-content")
    val (a, b) = p.cksum
    assert(a != 0L || b != 0L)
  }

  test("md5: returns 32-char hex string") {
    val p = tempFile("md5-content")
    assertEquals(p.md5.length, 32)
  }

  test("sha256: returns 64-char hex string") {
    val p = tempFile("sha256-content")
    assertEquals(p.sha256.length, 64)
  }

  // ============================================================================
  // realPath
  // ============================================================================

  test("realPath: existing file returns a valid path") {
    val p = tempFile("realpath-content")
    val rp = p.realPath
    assert(Files.exists(rp))
  }

  test("realPath: non-existing path does not throw") {
    val p = JPaths.get("/nonexistent/pathexts-rp-xyz.tmp")
    val rp = p.realPath
    assert(rp != null)
  }

  // ============================================================================
  // noDrive
  // ============================================================================

  test("noDrive: returns non-empty string (POSIX or Windows)") {
    val p = tempFile("drive")
    val nd = p.noDrive
    assert(nd.nonEmpty)
  }

  // ============================================================================
  // csvRows extension on Path
  // ============================================================================

  test("csvRows (Seq): parses CSV file") {
    val p = tempFile("x,y\n1,2\n")
    val rows = p.csvRows
    assertEquals(rows.length, 2)
    assertEquals(rows(0).toList, List("x", "y"))
  }

  test("csvRows (callback): invokes callback for each row") {
    val p = tempFile("a,b\n1,2\n")
    var count = 0
    p.csvRows { _ => count += 1 }
    assertEquals(count, 2)
  }

  // ============================================================================
  // JFile extension methods
  // ============================================================================

  private def tempJFile(content: String): JFile = tempFile(content).toFile

  test("JFile.last: returns filename") {
    val f = new JFile("/tmp/foo/bar.csv")
    assertEquals(f.last, "bar.csv")
  }

  test("JFile.baseName: strips extension") {
    val f = new JFile("/tmp/foo/bar.csv")
    assertEquals(f.baseName, "bar")
  }

  test("JFile.ext: returns extension without dot") {
    val f = new JFile("/tmp/foo/bar.csv")
    assertEquals(f.ext, "csv")
  }

  test("JFile.dotsuffix: returns .extension") {
    val f = new JFile("/tmp/foo/bar.csv")
    assertEquals(f.dotsuffix, ".csv")
  }

  test("JFile.extension: Some when extension present") {
    val f = new JFile("/tmp/foo/bar.csv")
    assertEquals(f.extension, Some("csv"))
  }

  test("JFile.isEmpty: empty JFile") {
    val f = emptyTempFile().toFile
    assert(f.isEmpty)
  }

  test("JFile.nonEmpty: non-empty JFile") {
    val f = tempJFile("data")
    assert(f.nonEmpty)
  }

  test("JFile.contentAsString: reads file content") {
    val f = tempJFile("file-content")
    assertEquals(f.contentAsString, "file-content")
  }

  test("JFile.lines: reads all lines") {
    val f = tempJFile("line1\nline2")
    assertEquals(f.lines.toList, List("line1", "line2"))
  }

  test("JFile.byteArray: reads bytes") {
    val f = tempJFile("abc")
    assertEquals(f.byteArray.toList, "abc".getBytes(StandardCharsets.UTF_8).toList)
  }

  test("JFile.linesStream: yields all lines") {
    val f = tempJFile("p\nq\nr")
    assertEquals(f.linesStream.toList, List("p", "q", "r"))
  }

  test("JFile.firstLine: returns first line") {
    val f = tempJFile("first\nsecond")
    assertEquals(f.firstLine, "first")
  }

  test("JFile.isSameFile: same file") {
    val f = tempJFile("same")
    assert(f.isSameFile(f.toPath))
  }

  test("JFile.isSameFile: different type returns false") {
    val f = tempJFile("data")
    assert(!f.isSameFile("not-a-path"))
  }

  test("JFile timestamps: all non-negative") {
    val f = tempJFile("ts")
    assert(f.lastModMillisAgo >= 0L)
    assert(f.lastModSecondsAgo >= 0.0)
    assert(f.lastModMinutesAgo >= 0.0)
    assert(f.lastModHoursAgo >= 0.0)
    assert(f.lastModDaysAgo >= 0.0)
    assert(f.lastModSeconds >= 0.0)
    assert(f.lastModMinutes >= 0.0)
    assert(f.lastModHours >= 0.0)
    assert(f.lastModDays >= 0.0)
  }

  test("JFile.ageInDays: fresh file is < 1 day") {
    val f = tempJFile("age")
    assert(f.ageInDays >= 0.0 && f.ageInDays < 1.0)
  }

  test("JFile.lastModifiedYMD: returns non-empty string") {
    val f = tempJFile("ts")
    assert(f.lastModifiedYMD.nonEmpty)
  }

  test("JFile.lastModifiedTime: returns year >= 2020") {
    val f = tempJFile("ts")
    assert(f.lastModifiedTime.getYear >= 2020)
  }

  test("JFile.weekDay: returns non-null") {
    val f = tempJFile("ts")
    assert(f.weekDay != null)
  }

  test("JFile.hash64: returns non-empty string") {
    val f = tempJFile("hash")
    assert(f.hash64.nonEmpty)
  }

  test("JFile.md5: returns 32-char hex string") {
    val f = tempJFile("md5")
    assertEquals(f.md5.length, 32)
  }

  test("JFile.sha256: returns 64-char hex string") {
    val f = tempJFile("sha256")
    assertEquals(f.sha256.length, 64)
  }

  test("JFile.realPath: existing file returns valid path") {
    val f = tempJFile("realpath")
    val rp = f.realPath
    assert(Files.exists(rp))
  }

  test("JFile.filesIter: directory yields files") {
    val dir = tempDir().toFile
    val f = Files.createTempFile(dir.toPath, "jf-", ".txt")
    f.toFile.deleteOnExit()
    assert(dir.filesIter.nonEmpty)
  }

  test("JFile.filesTree: includes directory and contained files") {
    val dir = tempDir().toFile
    val f = Files.createTempFile(dir.toPath, "jft-", ".txt")
    f.toFile.deleteOnExit()
    assert(dir.filesTree.nonEmpty)
  }

  test("JFile.filesTreeIter: non-existing path is empty") {
    val f = new JFile("/nonexistent/jft-xyz-pathexts")
    assert(f.filesTreeIter.isEmpty)
  }

  test("JFile.csvRows (Seq): parses CSV") {
    val f = tempJFile("a,b\n1,2\n")
    val rows = f.csvRows
    assertEquals(rows.length, 2)
  }

  test("JFile.copyTo: copies file content") {
    val src = tempJFile("copy-content")
    val dst = Files.createTempFile("pathexts-jf-dst-", ".txt")
    dst.toFile.deleteOnExit()
    src.copyTo(dst)
    assertEquals(new String(Files.readAllBytes(dst), StandardCharsets.UTF_8), "copy-content")
  }

  test("JFile.newerThan / olderThan: do not throw") {
    val f1 = tempJFile("a")
    val f2 = tempJFile("b")
    f1.newerThan(f2.toPath)
    f1.olderThan(f2.toPath)
  }

  test("JFile.isSymbolicLink: regular file is false") {
    val f = tempJFile("sym")
    assert(!f.isSymbolicLink)
  }

  test("JFile.relpath / relativePath: do not throw") {
    val f = tempJFile("rel")
    f.relpath
    f.relativePath
  }

  test("JFile.cksum: does not throw") {
    val f = tempJFile("cksum")
    val (a, b) = f.cksum
    assert(a != 0L || b != 0L)
  }
